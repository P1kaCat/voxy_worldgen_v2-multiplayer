package com.ethan.voxyworldgenv2.core;

import com.ethan.voxyworldgenv2.VoxyWorldGenV2;
import com.ethan.voxyworldgenv2.integration.VoxyIntegration;
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

import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class ChunkGenerationManager {
    private static final ChunkGenerationManager INSTANCE = new ChunkGenerationManager();
    
    // sets
    private final Set<Long> completedChunks = ConcurrentHashMap.newKeySet();
    private final Set<Long> trackedChunks = ConcurrentHashMap.newKeySet();
    
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
    private ChunkPos lastPlayerPos = null;
    private java.util.function.BooleanSupplier pauseCheck = () -> false;
    
    // worker
    private Thread workerThread;
    private final AtomicBoolean workerRunning = new AtomicBoolean(false);

    private ChunkGenerationManager() {}
    
    public static ChunkGenerationManager getInstance() {
        return INSTANCE;
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
                if (server == null || currentLevel == null) {
                    Thread.sleep(100);
                    continue;
                }

                if (tpsMonitor.isThrottled() || pauseCheck.getAsBoolean()) {
                    Thread.sleep(500);
                    continue;
                }
                
                var players = PlayerTracker.getInstance().getPlayers();
                if (players.isEmpty()) {
                    Thread.sleep(1000);
                    continue;
                }
                ChunkPos center = players.iterator().next().chunkPosition();

                // get next batch
                List<ChunkPos> batch = distanceGraph.findWork(center, Config.DATA.generationRadius, trackedBatches);
                
                if (batch == null) {
                    Thread.sleep(100);
                    continue;
                }
                
                long batchKey = DistanceGraph.getBatchKey(batch.get(0).x, batch.get(0).z);
                batchCounters.put(batchKey, new AtomicInteger(batch.size()));

                // filter batch on main thread
                CompletableFuture<List<ChunkPos>> filterTask = CompletableFuture.supplyAsync(() -> {
                    List<ChunkPos> toGenerate = new ArrayList<>();
                    if (currentLevel == null) return toGenerate;
                    
                    for (ChunkPos pos : batch) {
                        try {
                            if (currentLevel.hasChunk(pos.x, pos.z)) {
                                onSuccess(pos); // already exists
                            } else {
                                toGenerate.add(pos);
                            }
                        } catch (Exception e) {
                            toGenerate.add(pos);
                        }
                    }
                    return toGenerate;
                }, server);

                List<ChunkPos> toGenerate = filterTask.join();
                
                if (toGenerate.isEmpty()) {
                    // all skipped, cleanup
                    trackedBatches.remove(batchKey);
                    batchCounters.remove(batchKey);
                    continue;
                }

                // submit tasks
                for (ChunkPos pos : toGenerate) {
                    if (!workerRunning.get()) break;
                    throttle.acquire();
                    if (!processChunk(pos)) {
                        throttle.release();
                        onFailure(pos); // mark as failed to decremement batch counter
                    }
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                VoxyWorldGenV2.LOGGER.error("error in generation worker", e);
                try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
            }
        }
    }

    // returns true if task submitted, false if skipped/failed
    private boolean processChunk(ChunkPos pos) {
        long key = pos.toLong();
        
        // note: checking completedchunks/haschunk is done in batch before this method
        
        if (trackedChunks.add(key)) {
            activeTaskCount.incrementAndGet();
            stats.incrementQueued();
            
            // dispatch to main thread for safe ticket addition
            server.execute(() -> generateChunk(currentLevel, pos));
            return true;
        }
        
        return false;
    }

    public void tick() {
        if (!running.get() || server == null) return;
        
        if (configReloadScheduled.compareAndSet(true, false)) {
            Config.load();
            updateThrottleCapacity();
            var players = PlayerTracker.getInstance().getPlayers();
            if (!players.isEmpty()) {
                restartScan(players.iterator().next().chunkPosition());
            }
        }
        
        tpsMonitor.tick();
        checkPlayerMovement();
    }
    
    private void checkPlayerMovement() {
        var players = PlayerTracker.getInstance().getPlayers();
        if (players.isEmpty()) return;

        ServerPlayer player = players.iterator().next();
        ChunkPos currentPos = player.chunkPosition();
        
        // update level if changed
        if (player.level() != currentLevel) {
             setupLevel((ServerLevel) player.level());
        }

        // update radius stats if moved
        if (lastPlayerPos == null || !lastPlayerPos.equals(currentPos)) {
            lastPlayerPos = currentPos;
            restartScan(currentPos);
        }
    }

    private void setupLevel(ServerLevel newLevel) {
        if (currentLevel != null && currentDimensionKey != null) {
            ChunkPersistence.save(currentLevel, currentDimensionKey, completedChunks);
        }
        
        currentLevel = newLevel;
        currentDimensionKey = newLevel.dimension();
        completedChunks.clear();
        trackedChunks.clear();
        trackedBatches.clear();
        batchCounters.clear();
        ChunkPersistence.load(newLevel, currentDimensionKey, completedChunks);
        
        // populate distance graph
        for (long pos : completedChunks) {
            distanceGraph.markChunkCompleted(ChunkPos.getX(pos), ChunkPos.getZ(pos));
        }
        
        var players = PlayerTracker.getInstance().getPlayers();
        if (!players.isEmpty()) {
            restartScan(players.iterator().next().chunkPosition());
        }
    }
    
    private void restartScan(ChunkPos center) {
        int radius = Config.DATA.generationRadius;
        remainingInRadius.set(distanceGraph.countMissingInRange(center, radius));
    }

    private void updateThrottleCapacity() {
        int target = Config.DATA.maxActiveTasks;
        int current = throttle.availablePermits() + activeTaskCount.get();
        // update permit count if config changed
        if (current != target) {
             int diff = target - current;
             if (diff > 0) throttle.release(diff);
             else throttle.tryAcquire(-diff); // simplistic, works for small changes
        }
    }
    
    private void generateChunk(ServerLevel level, ChunkPos pos) {
        ServerChunkCache cache = level.getChunkSource();
        
        cache.addTicketWithRadius(TicketType.FORCED, pos, 0);
        
        // force immediate update
        ((ServerChunkCacheMixin) cache).invokeRunDistanceManagerUpdates();
        
        ((ServerChunkCacheMixin) cache).invokeGetChunkFutureMainThread(pos.x, pos.z, ChunkStatus.FULL, true)
            .whenCompleteAsync((result, throwable) -> {
                if (throwable == null && result != null && result.isSuccess() && result.orElse(null) instanceof LevelChunk chunk) {
                    onSuccess(pos);
                    // offload voxy ingestion
                    CompletableFuture.runAsync(() -> VoxyIntegration.ingestChunk(chunk))
                        .whenComplete((v, t) -> cleanupTask(cache, pos));
                } else {
                    onFailure(pos);
                    cleanupTask(cache, pos);
                }
            }, server);
    }

    private void cleanupTask(ServerChunkCache cache, ChunkPos pos) {
        server.execute(() -> {
            cache.removeTicketWithRadius(TicketType.FORCED, pos, 0);
            ((MinecraftServerExtension) server).voxyworldgen$markHousekeeping();
            ((MinecraftServerAccess) server).setEmptyTicks(0);
            completeTask(pos);
        });
    }
    
    private void onSuccess(ChunkPos pos) {
        long key = pos.toLong();
        if (completedChunks.add(key)) {
            stats.incrementCompleted();
            distanceGraph.markChunkCompleted(pos.x, pos.z);
            remainingInRadius.decrementAndGet();
        } else {
            stats.incrementSkipped();
        }
        decrementBatch(pos);
    }
    
    private void onFailure(ChunkPos pos) {
        stats.incrementFailed();
        // decrement so it counts as attempted
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
        trackedChunks.remove(pos.toLong());
        activeTaskCount.decrementAndGet();
        throttle.release(); // wake up the worker thread
    }
    
    public void scheduleConfigReload() {
        configReloadScheduled.set(true);
    }
    
    public GenerationStats getStats() { return stats; }
    public int getActiveTaskCount() { return activeTaskCount.get(); }
    public int getRemainingInRadius() { return remainingInRadius.get(); }
    public boolean isThrottled() { return tpsMonitor.isThrottled(); }
    public int getQueueSize() { return 0; } // implicit in worker
    
    public void setPauseCheck(java.util.function.BooleanSupplier check) {
        this.pauseCheck = check;
    }
}
