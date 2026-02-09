package com.ethan.voxyworldgenv2.core;

import com.ethan.voxyworldgenv2.VoxyWorldGenV2;
import com.ethan.voxyworldgenv2.integration.VoxyIntegration;
import com.ethan.voxyworldgenv2.integration.tellus.TellusIntegration;
import com.ethan.voxyworldgenv2.mixin.MinecraftServerAccess;

import com.ethan.voxyworldgenv2.mixin.ServerChunkCacheMixin;
import com.ethan.voxyworldgenv2.stats.GenerationStats;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;

import java.util.UUID;
import java.util.HashSet;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class ChunkGenerationManager {
    private static final ChunkGenerationManager INSTANCE = new ChunkGenerationManager();
    
    // sets - we use primitive sets to reduce gc pressure
    private final LongSet completedChunks = LongSets.synchronize(new LongOpenHashSet());
    private final LongSet trackedChunks = LongSets.synchronize(new LongOpenHashSet());
    
    // state
    private final AtomicInteger activeTaskCount = new AtomicInteger(0);
    private final GenerationStats stats = new GenerationStats();
    private final AtomicInteger remainingInRadius = new AtomicInteger(0);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean configReloadScheduled = new AtomicBoolean(false);
    
    // components
    private final TpsMonitor tpsMonitor = new TpsMonitor();
    private final DistanceGraph distanceGraph = new DistanceGraph();
    private final Set<Long> trackedBatches = ConcurrentHashMap.newKeySet();
    private final Map<Long, AtomicInteger> batchCounters = new ConcurrentHashMap<>();
    private Semaphore throttle;
    private MinecraftServer server;
    private ResourceKey<Level> currentDimensionKey = null;
    private ServerLevel currentLevel = null;
    private final java.util.Map<java.util.UUID, ChunkPos> lastPlayerPositions = new java.util.concurrent.ConcurrentHashMap<>();
    private java.util.function.BooleanSupplier pauseCheck = () -> false;
    private boolean tellusActive = false;


    
    // worker
    private Thread workerThread;
    private final AtomicBoolean workerRunning = new AtomicBoolean(false);
    
    // c2me compatibility - queue ticket operations to process at safe time
    private record TicketOp(ChunkPos pos, boolean add) {}
    private final ConcurrentLinkedQueue<TicketOp> pendingTicketOps = new ConcurrentLinkedQueue<>();

    private ChunkGenerationManager() {}
    
    public static ChunkGenerationManager getInstance() {
        return INSTANCE;
    }
    public ServerLevel getCurrentLevel() {
        return currentLevel;
    }
    
    public void initialize(MinecraftServer server) {
        this.server = server;
        this.running.set(true);
        // unpaused by default
        this.pauseCheck = () -> false; 
        Config.load();
        this.throttle = new Semaphore(Config.DATA.maxActiveTasks);
        startWorker();
        VoxyWorldGenV2.LOGGER.info("voxy world gen initialized");
    }
    
    public void shutdown() {
        running.set(false);
        stopWorker();
        
        if (currentLevel != null && currentDimensionKey != null) {
            ChunkPersistence.save(currentLevel, currentDimensionKey, completedChunks);
        }
        
        trackedChunks.clear();
        completedChunks.clear();
        trackedBatches.clear();
        batchCounters.clear();
        server = null;
        stats.reset();
        activeTaskCount.set(0);
        remainingInRadius.set(0);
        tpsMonitor.reset();
        currentDimensionKey = null;
        currentLevel = null;
    }

    private void startWorker() {
        if (workerRunning.getAndSet(true)) return;
        workerThread = new Thread(this::workerLoop, "Voxy-WorldGen-Worker");
        workerThread.setDaemon(true);
        workerThread.start();
    }

    private void stopWorker() {
        workerRunning.set(false);
        if (workerThread != null) {
            workerThread.interrupt();
            workerThread = null;
        }
    }

    private void workerLoop() {
        while (workerRunning.get() && running.get()) {
            try {
                if (!Config.DATA.enabled || server == null || currentLevel == null) {
                    Thread.sleep(100);
                    continue;
                }

                if (tpsMonitor.isThrottled() || pauseCheck.getAsBoolean()) {
                    Thread.sleep(500);
                    continue;
                }
                
                var players = new ArrayList<>(PlayerTracker.getInstance().getPlayers());
                if (players.isEmpty()) {
                    Thread.sleep(1000);
                    continue;
                }
                
                int radius = tellusActive ? Math.max(Config.DATA.generationRadius, 128) : Config.DATA.generationRadius;
                List<ChunkPos> batch = null;
                
                // try to find work around any player
                for (ServerPlayer player : players) {
                    batch = distanceGraph.findWork(player.chunkPosition(), radius, trackedBatches);
                    if (batch != null) break;
                }
                
                if (batch == null) {
                    // if no generation work, try to catch up on syncing for any player
                    List<ChunkPos> syncBatch = new ArrayList<>();
                    for (ServerPlayer player : players) {
                        var synced = PlayerTracker.getInstance().getSyncedChunks(player.getUUID());
                        if (synced == null) continue;
                        
                        distanceGraph.collectCompletedInRange(player.chunkPosition(), radius, synced, syncBatch, 64);
                        if (!syncBatch.isEmpty()) {
                            for (ChunkPos syncPos : syncBatch) {
                                LevelChunk c = currentLevel.getChunkSource().getChunk(syncPos.x, syncPos.z, false);
                                if (c != null) {
                                    com.ethan.voxyworldgenv2.network.NetworkHandler.sendLODData(player, c);
                                }
                            }
                        }
                        
                        if (!syncBatch.isEmpty()) break;
                    }
                    
                    if (!syncBatch.isEmpty()) continue; // skip sleep if we processed work
                    
                    Thread.sleep(100);
                    continue;
                }
                
                long batchKey = DistanceGraph.getBatchKey(batch.get(0).x, batch.get(0).z);
                batchCounters.put(batchKey, new AtomicInteger(batch.size()));

                // skip if already tracked locally
                List<ChunkPos> preFiltered = new ArrayList<>(batch.size());
                for (ChunkPos pos : batch) {
                    long key = pos.toLong();
                    if (completedChunks.contains(key) || trackedChunks.contains(key)) {
                        onSuccess(pos);
                    } else {
                        preFiltered.add(pos);
                    }
                }

                if (preFiltered.isEmpty()) {
                    trackedBatches.remove(batchKey);
                    batchCounters.remove(batchKey);
                    continue;
                }

                // dispatch tasks - use tryAcquire to avoid deadlock when batch size > maxActiveTasks
                List<ChunkPos> readyToGenerate = new ArrayList<>();
                int processedCount = 0;
                for (ChunkPos pos : preFiltered) {
                    if (!workerRunning.get()) break;
                    
                    // try to acquire with short timeout to avoid blocking forever
                    boolean acquired = false;
                    try {
                        acquired = throttle.tryAcquire(50, java.util.concurrent.TimeUnit.MILLISECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    
                    if (!acquired) {
                        // couldn't get permit, dispatch what we have so far
                        break;
                    }
                    
                    processedCount++;
                    if (trackedChunks.add(pos.toLong())) {
                        activeTaskCount.incrementAndGet();
                        stats.incrementQueued();
                        
                        if (tellusActive) {
                            TellusIntegration.enqueueGenerate(currentLevel, pos, () -> {
                                onSuccess(pos);
                                completeTask(pos);
                            });
                            continue;
                        }
                        
                        readyToGenerate.add(pos);
                    } else {
                        throttle.release();
                        onFailure(pos);
                    }
                }
                
                // if we couldn't process all chunks in batch, remove from tracking to allow retry
                if (processedCount < preFiltered.size()) {
                    trackedBatches.remove(batchKey);
                    batchCounters.remove(batchKey);
                }

                if (!readyToGenerate.isEmpty()) {
                    server.execute(() -> {
                        if (currentLevel == null) {
                            for (ChunkPos p : readyToGenerate) completeTask(p);
                            return;
                        }
                        
                        ServerChunkCache cache = currentLevel.getChunkSource();
                        List<ChunkPos> actuallyGenerate = new ArrayList<>();
                        
                        for (ChunkPos pos : readyToGenerate) {
                            if (currentLevel.hasChunk(pos.x, pos.z)) {
                                // chunk already loaded by vanilla - still ingest to voxy
                                LevelChunk existingChunk = currentLevel.getChunk(pos.x, pos.z);
                                if (existingChunk != null && !existingChunk.isEmpty()) {
                                    VoxyIntegration.ingestChunk(existingChunk);
                                    com.ethan.voxyworldgenv2.network.NetworkHandler.broadcastLODData(existingChunk);
                                }
                                onSuccess(pos);
                                completeTask(pos);
                            } else {
                                // queue ticket add for next tick (c2me safe)
                                queueTicketAdd(pos);
                                actuallyGenerate.add(pos);
                            }
                        }
                        
                        // tickets will be processed in next tick() call
                        // schedule chunk generation after ticket is applied
                        if (!actuallyGenerate.isEmpty()) {
                            server.execute(() -> {
                                if (currentLevel == null) return;
                                ServerChunkCache c = currentLevel.getChunkSource();
                                
                                for (ChunkPos pos : actuallyGenerate) {
                                    ((ServerChunkCacheMixin) c).invokeGetChunkFutureMainThread(pos.x, pos.z, ChunkStatus.FULL, true)
                                        .whenCompleteAsync((result, throwable) -> {
                                            if (throwable == null && result != null && result.isSuccess() && result.orElse(null) instanceof LevelChunk chunk) {
                                                onSuccess(pos);
                                                if (!chunk.isEmpty()) {
                                                    VoxyIntegration.ingestChunk(chunk);
                                                    com.ethan.voxyworldgenv2.network.NetworkHandler.broadcastLODData(chunk);
                                                }
                                            } else {
                                                onFailure(pos);
                                            }
                                            cleanupTask(c, pos);
                                        }, server);
                                }
                            });
                        }
                    });
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                VoxyWorldGenV2.LOGGER.error("error in worker loop", e);
                try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
            }
        }
    }

    public void tick() {
        if (!running.get() || server == null) return;
        
        // process pending ticket operations at safe point before c2me parallelizes
        processPendingTickets();
        
        if (configReloadScheduled.compareAndSet(true, false)) {
            Config.load();
            updateThrottleCapacity();
            var players = PlayerTracker.getInstance().getPlayers();
            if (!players.isEmpty()) {
                restartScan();
            }
        }
        
        tpsMonitor.tick();
        stats.tick();
        checkPlayerMovement();
        
        // broadcast changes
        ChunkUpdateTracker.getInstance().processDirty(currentLevel);
    }
    
    private void checkPlayerMovement() {
        var players = PlayerTracker.getInstance().getPlayers();
        if (players.isEmpty()) {
            if (!lastPlayerPositions.isEmpty()) {
                lastPlayerPositions.clear();
                remainingInRadius.set(0);
            }
            return;
        }

        boolean shouldRescan = false;
        
        // check for new players, removed players, or significant movement
        Set<java.util.UUID> currentPlayerIds = new java.util.HashSet<>();
        for (ServerPlayer player : players) {
            currentPlayerIds.add(player.getUUID());
            ChunkPos currentPos = player.chunkPosition();
            ChunkPos lastPos = lastPlayerPositions.get(player.getUUID());
            
            if (lastPos == null || distSq(lastPos, currentPos) >= 4) {
                lastPlayerPositions.put(player.getUUID(), currentPos);
                shouldRescan = true;
            }
            
            if (player.level() != currentLevel) {
                 setupLevel((ServerLevel) player.level());
                 return; // setupLevel will call restartScan
            }
        }
        
        // clean up players who left
        if (lastPlayerPositions.size() > currentPlayerIds.size()) {
            lastPlayerPositions.keySet().removeIf(uuid -> !currentPlayerIds.contains(uuid));
            shouldRescan = true;
        }

        if (shouldRescan) {
            restartScan();
        }
    }

    private double distSq(ChunkPos a, ChunkPos b) {
        int dx = a.x - b.x;
        int dz = a.z - b.z;
        return (double) dx * dx + dz * dz;
    }

    private void setupLevel(ServerLevel newLevel) {
        if (currentLevel != null && currentDimensionKey != null) {
            ChunkPersistence.save(currentLevel, currentDimensionKey, completedChunks);
        }
        
        currentLevel = newLevel;
        currentDimensionKey = newLevel.dimension();
        tellusActive = TellusIntegration.isTellusWorld(newLevel);

        
        if (tellusActive) {
            VoxyWorldGenV2.LOGGER.info("tellus world detected, enabling fast generation");
        }
        
        completedChunks.clear();
        trackedChunks.clear();
        trackedBatches.clear();
        batchCounters.clear();
        ChunkPersistence.load(newLevel, currentDimensionKey, completedChunks);
        
        for (long pos : completedChunks) {
            distanceGraph.markChunkCompleted(ChunkPos.getX(pos), ChunkPos.getZ(pos));
        }
        
        var players = PlayerTracker.getInstance().getPlayers();
        if (!players.isEmpty()) {
            restartScan();
        }
    }
    
    private void restartScan() {
        var players = PlayerTracker.getInstance().getPlayers();
        if (players.isEmpty()) {
            remainingInRadius.set(0);
            return;
        }
        
        int radius = tellusActive ? Math.max(Config.DATA.generationRadius, 128) : Config.DATA.generationRadius;
        
        remainingInRadius.set(distanceGraph.countMissingInRange(players.iterator().next().chunkPosition(), radius));
    }

    private void updateThrottleCapacity() {
        int target = Config.DATA.maxActiveTasks;
        // only increase capacity, never decrease (to avoid blocking)
        // decreased capacity takes effect naturally as tasks complete
        int available = throttle.availablePermits();
        int maxPossible = available + activeTaskCount.get();
        if (target > maxPossible) {
            throttle.release(target - maxPossible);
        }
    }
    
    // c2me compatibility: process all queued ticket operations at a single safe point
    private void processPendingTickets() {
        if (currentLevel == null) {
            pendingTicketOps.clear();
            return;
        }
        
        ServerChunkCache cache = currentLevel.getChunkSource();
        boolean processed = false;
        TicketOp op;
        while ((op = pendingTicketOps.poll()) != null) {
            processed = true;
            if (op.add()) {
                cache.addTicketWithRadius(TicketType.FORCED, op.pos(), 0);
            } else {
                cache.removeTicketWithRadius(TicketType.FORCED, op.pos(), 0);
            }
        }
        
        // run distance manager updates once after batch processing
        if (processed) {
            ((ServerChunkCacheMixin) cache).invokeRunDistanceManagerUpdates();
        }
    }
    
    private void queueTicketAdd(ChunkPos pos) {
        pendingTicketOps.add(new TicketOp(pos, true));
    }
    
    private void queueTicketRemove(ChunkPos pos) {
        pendingTicketOps.add(new TicketOp(pos, false));
    }
    
    private void cleanupTask(ServerChunkCache cache, ChunkPos pos) {
        queueTicketRemove(pos);
        ((MinecraftServerAccess) server).setEmptyTicks(0);
        completeTask(pos);
    }

    
    private void onSuccess(ChunkPos pos) {
        long key = pos.toLong();
        if (completedChunks.add(key)) {
            stats.incrementCompleted();
            distanceGraph.markChunkCompleted(pos.x, pos.z);
            remainingInRadius.decrementAndGet();
        } else {
            stats.incrementSkipped();
            distanceGraph.markChunkCompleted(pos.x, pos.z);
        }
        decrementBatch(pos);
    }
    
    private void onFailure(ChunkPos pos) {
        stats.incrementFailed();
        remainingInRadius.decrementAndGet();
        decrementBatch(pos);
    }

    private void decrementBatch(ChunkPos pos) {
        long batchKey = DistanceGraph.getBatchKey(pos.x, pos.z);
        AtomicInteger counter = batchCounters.get(batchKey);
        if (counter != null && counter.decrementAndGet() <= 0) {
            trackedBatches.remove(batchKey);
            batchCounters.remove(batchKey);
        }
    }
    
    private void completeTask(ChunkPos pos) {
        if (trackedChunks.remove(pos.toLong())) {
            activeTaskCount.decrementAndGet();
            throttle.release();
        }
    }
    
    public void scheduleConfigReload() {
        configReloadScheduled.set(true);
    }
    
    public GenerationStats getStats() { return stats; }
    public int getActiveTaskCount() { return activeTaskCount.get(); }
    public int getRemainingInRadius() { return remainingInRadius.get(); }
    public boolean isThrottled() { return tpsMonitor.isThrottled(); }
    public int getQueueSize() { return 0; }
    
    public void setPauseCheck(java.util.function.BooleanSupplier check) {
        this.pauseCheck = check;
    }
}
