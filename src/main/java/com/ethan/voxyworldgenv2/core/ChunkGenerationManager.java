package com.ethan.voxyworldgenv2.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import com.ethan.voxyworldgenv2.VoxyWorldGenV2;
import com.ethan.voxyworldgenv2.integration.VoxyIntegration;
import com.ethan.voxyworldgenv2.integration.tellus.TellusIntegration;
import com.ethan.voxyworldgenv2.mixin.MinecraftServerAccess;
import com.ethan.voxyworldgenv2.mixin.ServerChunkCacheMixin;
import com.ethan.voxyworldgenv2.stats.GenerationStats;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
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

public final class ChunkGenerationManager {
    private static final ChunkGenerationManager INSTANCE = new ChunkGenerationManager();
    
    private static class DimensionState {
        final ServerLevel level;
        final ResourceKey<Level> dimensionKey;
        final LongSet completedChunks = LongSets.synchronize(new LongOpenHashSet());
        final LongSet trackedChunks = LongSets.synchronize(new LongOpenHashSet());
        final DistanceGraph distanceGraph = new DistanceGraph();
        final Set<Long> trackedBatches = ConcurrentHashMap.newKeySet();
        final Map<Long, AtomicInteger> batchCounters = new ConcurrentHashMap<>();
        final AtomicInteger remainingInRadius = new AtomicInteger(0);
        boolean tellusActive = false;
        boolean loaded = false;

        DimensionState(ServerLevel level) {
            this.level = level;
            // Safety check: if level is null, we skip initialization to avoid NPE
            if (level != null) {
                this.dimensionKey = level.dimension();
                this.tellusActive = false;

                try {
                    // Only load if the server instance is valid
                    if (level.getServer() != null) {
                        ChunkPersistence.load(level, this.dimensionKey, this.completedChunks);
                        synchronized(this.completedChunks) {
                            for (long posLong : this.completedChunks) {
                                int cx = net.minecraft.world.level.ChunkPos.getX(posLong);
                                int cz = net.minecraft.world.level.ChunkPos.getZ(posLong);
                                this.distanceGraph.markChunkCompleted(cx, cz);
                            }
                        }
                    }
                } catch (Exception e) {
                    VoxyWorldGenV2.LOGGER.error("Error initializing DimensionState: " + e.getMessage());
                }
            } else {
                this.dimensionKey = null;
            }
            this.loaded = true;
        }
    }

    private final Map<ResourceKey<Level>, DimensionState> dimensionStates = new ConcurrentHashMap<>();
    
    // global state
    private final AtomicInteger activeTaskCount = new AtomicInteger(0);
    private final GenerationStats stats = new GenerationStats();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean configReloadScheduled = new AtomicBoolean(false);
    
    // components
    private final TpsMonitor tpsMonitor = new TpsMonitor();
    private Semaphore throttle;
    private MinecraftServer server;
    private ResourceKey<Level> currentDimensionKey = null;
    private ServerLevel currentLevel = null;
    private final java.util.Map<java.util.UUID, ChunkPos> lastPlayerPositions = new java.util.concurrent.ConcurrentHashMap<>();
    private java.util.function.BooleanSupplier pauseCheck = () -> false;

    // worker
    private Thread workerThread;
    private final AtomicBoolean workerRunning = new AtomicBoolean(false);
    
    // c2me compatibility - queue ticket operations to process at safe time
    private record TicketOp(ServerLevel level, ChunkPos pos, boolean add) {}
    private final ConcurrentLinkedQueue<TicketOp> pendingTicketOps = new ConcurrentLinkedQueue<>();

    private ChunkGenerationManager() {}
    
    public static ChunkGenerationManager getInstance() {
        return INSTANCE;
    }

    private DimensionState getOrSetupState(ServerLevel level) {
        return dimensionStates.computeIfAbsent(level.dimension(), k -> {
            DimensionState state = new DimensionState(level);
            state.tellusActive = TellusIntegration.isTellusWorld(level);
            // Charger les données de persistance immédiatement pour cette dimension
            if (!state.loaded) {
                ChunkPersistence.load(level, level.dimension(), state.completedChunks);
                synchronized(state.completedChunks) {
                    for (long pos : state.completedChunks) {
                        state.distanceGraph.markChunkCompleted(ChunkPos.getX(pos), ChunkPos.getZ(pos));
                    }
                }
                state.loaded = true;
            }
            return state;
        });
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
        
        for (var entry : dimensionStates.entrySet()) {
            DimensionState state = entry.getValue();
            if (state.loaded) {
                ChunkPersistence.save(state.level, entry.getKey(), state.completedChunks);
            }
        }
        
        dimensionStates.clear();
        server = null;
        stats.reset();
        activeTaskCount.set(0);
        tpsMonitor.reset();
        currentDimensionKey = null;
        currentLevel = null;
        lastPlayerPositions.clear();
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
                if (!Config.DATA.enabled || server == null) {
                    Thread.sleep(1000);
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

                boolean workFound = false;

                for (ServerPlayer player : players) {
                    // 1. Get the current dimension for EACH player
                    ServerLevel playerLevel = (ServerLevel) player.level();
                    DimensionState ds = getOrSetupState(playerLevel);
                    
                    // 2. Calculate radius (Tellus support)
                    int radius = ds.tellusActive ? Math.max(Config.DATA.generationRadius, 128) : Config.DATA.generationRadius;
                    
                    // 3. Search for work around the player
                    List<ChunkPos> batch = ds.distanceGraph.findWork(player.chunkPosition(), radius, ds.trackedBatches);
                    
                    if (batch != null && !batch.isEmpty()) {
                        workFound = true;
                        processBatch(ds, batch);
                    } else {
                        // 4. If no generation needed, check if we need to sync existing LODs
                        var synced = PlayerTracker.getInstance().getSyncedChunks(player.getUUID());
                        if (synced != null) {
                            List<ChunkPos> syncBatch = new ArrayList<>();
                            ds.distanceGraph.collectCompletedInRange(player.chunkPosition(), radius, synced, syncBatch, 64);
                            
                            if (!syncBatch.isEmpty()) {
                                workFound = true;
                                dispatchSyncBatch(player, ds, syncBatch);
                            }
                        }
                    }
                }

                // Slow down the loop if no work was found for anyone
                if (!workFound) {
                    Thread.sleep(100);
                } else {
                    Thread.sleep(10); // Short breathing delay
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                VoxyWorldGenV2.LOGGER.error("Error in worker loop", e);
                try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
            }
        }
    }

    private void processBatch(DimensionState ds, List<ChunkPos> batch) {
        long batchKey = DistanceGraph.getBatchKey(batch.get(0).x, batch.get(0).z);
        ds.batchCounters.put(batchKey, new AtomicInteger(batch.size()));

        for (ChunkPos pos : batch) {
            if (!workerRunning.get()) break;
            
            long key = pos.toLong();
            if (ds.completedChunks.contains(key) || ds.trackedChunks.contains(key)) {
                onSuccess(ds, pos);
                continue;
            }

            try {
                if (throttle.tryAcquire(50, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    if (ds.trackedChunks.add(key)) {
                        activeTaskCount.incrementAndGet();
                        stats.incrementQueued();

                        if (ds.tellusActive) {
                            TellusIntegration.enqueueGenerate(ds.level, pos, () -> {
                                onSuccess(ds, pos);
                                completeTask(ds, pos);
                            });
                        } else {
                            // Dispatch to main thread for Minecraft generation
                            server.execute(() -> {
                                queueTicketAdd(ds.level, pos);
                                processPendingTickets();
                                ((ServerChunkCacheMixin) ds.level.getChunkSource()).invokeGetChunkFutureMainThread(pos.x, pos.z, net.minecraft.world.level.chunk.status.ChunkStatus.FULL, true)
                                    .whenCompleteAsync((result, throwable) -> {
                                    
                                    if (throwable == null && result != null && result.isSuccess() && result.orElse(null) instanceof LevelChunk chunk) {
                                        // 1. Verification: is the chunk in the expected dimension?
                                        if (chunk.getLevel().dimension().equals(ds.dimensionKey)) {
                                            onSuccess(ds, pos);
                                            
                                            // 2. Local ingestion for Voxy
                                            VoxyIntegration.ingestChunk(chunk);
                                            
                                            // 3. FILTERED SEND: Only to players in this dimension
                                            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                                                if (player.level().dimension().equals(ds.dimensionKey)) {
                                                    com.ethan.voxyworldgenv2.network.NetworkHandler.sendLODData(player, chunk);
                                                }
                                            }
                                        }
                                    }
                                        cleanupTask(ds.level, pos);
                                    }, server);
                            });
                        }
                    } else {
                        throttle.release();
                    }
                }
            } catch (InterruptedException ignored) {}
        }
    }

    private void dispatchSyncBatch(ServerPlayer player, DimensionState ds, List<ChunkPos> syncBatch) {
        final UUID uuid = player.getUUID();
        final List<ChunkPos> toSync = new ArrayList<>(syncBatch);
        server.execute(() -> {
            ServerPlayer p = server.getPlayerList().getPlayer(uuid);
            if (p != null) {
                for (ChunkPos pos : toSync) {
                    LevelChunk c = ds.level.getChunkSource().getChunk(pos.x, pos.z, false);
                    if (c != null) {
                        com.ethan.voxyworldgenv2.network.NetworkHandler.sendLODData(p, c);
                    }
                }
            }
        });
    }

    public void tick() {
        if (!running.get() || server == null) return;
        
        processPendingTickets();
        
        if (configReloadScheduled.compareAndSet(true, false)) {
            Config.load();
            updateThrottleCapacity();
            restartScan();
        }
        
        tpsMonitor.tick();
        stats.tick();
        checkPlayerMovement();
        
        // broadcast changes for all active dimensions
        Set<ServerLevel> activeLevels = new HashSet<>();
        for (ServerPlayer player : PlayerTracker.getInstance().getPlayers()) {
            activeLevels.add((ServerLevel) player.level());
        }
        for (ServerLevel level : activeLevels) {
            ChunkUpdateTracker.getInstance().processDirty(level);
        }
    }
    
    private void checkPlayerMovement() {
        var players = PlayerTracker.getInstance().getPlayers();
        if (players.isEmpty()) return;

        boolean shouldRescan = false;
        for (ServerPlayer player : players) {
            // Ensure the dimension the player is in is initialized
            getOrSetupState((ServerLevel) player.level()); 
            
            ChunkPos currentPos = player.chunkPosition();
            UUID uuid = player.getUUID();
            ChunkPos lastPos = lastPlayerPositions.get(uuid);
            
            if (lastPos == null || distSq(lastPos, currentPos) >= 4) {
                lastPlayerPositions.put(uuid, currentPos);
                shouldRescan = true;
            }
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
            DimensionState oldState = dimensionStates.get(currentDimensionKey);
            if (oldState != null) {
                ChunkPersistence.save(currentLevel, currentDimensionKey, oldState.completedChunks);
            }
        }
        
        currentLevel = newLevel;
        currentDimensionKey = newLevel.dimension();
        DimensionState state = getOrSetupState(newLevel);
        
        if (!state.loaded) {
            if (state.tellusActive) {
                VoxyWorldGenV2.LOGGER.info("tellus world detected for {}, enabling fast generation", currentDimensionKey);
            }
            ChunkPersistence.load(newLevel, currentDimensionKey, state.completedChunks);
            synchronized(state.completedChunks) {
                for (long pos : state.completedChunks) {
                    state.distanceGraph.markChunkCompleted(ChunkPos.getX(pos), ChunkPos.getZ(pos));
                }
            }
            state.loaded = true;
        }
        
        restartScan();
    }
    
    private void restartScan() {
        var players = PlayerTracker.getInstance().getPlayers();
        if (players.isEmpty()) return;
        
        java.util.Map<DimensionState, Integer> maxCounts = new java.util.HashMap<>();
        for (ServerPlayer player : players) {
            DimensionState state = getOrSetupState((ServerLevel) player.level());
            int radius = state.tellusActive ? Math.max(Config.DATA.generationRadius, 128) : Config.DATA.generationRadius;
            int missing = state.distanceGraph.countMissingInRange(player.chunkPosition(), radius);
            maxCounts.merge(state, missing, Math::max);
        }
        
        maxCounts.forEach((state, count) -> state.remainingInRadius.set(count));
    }

    private void updateThrottleCapacity() {
        int target = Config.DATA.maxActiveTasks;
        int available = throttle.availablePermits();
        int maxPossible = available + activeTaskCount.get();
        if (target > maxPossible) {
            throttle.release(target - maxPossible);
        }
    }
    
    private void processPendingTickets() {
        TicketOp op;
        java.util.Set<ServerLevel> modifiedLevels = new java.util.HashSet<>();
        while ((op = pendingTicketOps.poll()) != null) {
            ServerChunkCache cache = op.level().getChunkSource();
            if (op.add()) {
                cache.addTicketWithRadius(TicketType.FORCED, op.pos(), 0);
            } else {
                cache.removeTicketWithRadius(TicketType.FORCED, op.pos(), 0);
            }
            modifiedLevels.add(op.level());
        }
        for (ServerLevel level : modifiedLevels) {
            ((ServerChunkCacheMixin) level.getChunkSource()).invokeRunDistanceManagerUpdates();
        }
    }
    
    private void queueTicketAdd(ServerLevel level, ChunkPos pos) {
        pendingTicketOps.add(new TicketOp(level, pos, true));
    }
    
    private void queueTicketRemove(ServerLevel level, ChunkPos pos) {
        pendingTicketOps.add(new TicketOp(level, pos, false));
    }
    
    private void cleanupTask(ServerLevel level, ChunkPos pos) {
        queueTicketRemove(level, pos);
        ((MinecraftServerAccess) server).setEmptyTicks(0);
        DimensionState state = dimensionStates.get(level.dimension());
        if (state != null) completeTask(state, pos);
    }

    private void onSuccess(DimensionState state, ChunkPos pos) {
        long key = pos.toLong();
        if (state.completedChunks.add(key)) {
            stats.incrementCompleted();
            state.distanceGraph.markChunkCompleted(pos.x, pos.z);
            state.remainingInRadius.decrementAndGet();
        } else {
            stats.incrementSkipped();
            state.distanceGraph.markChunkCompleted(pos.x, pos.z);
        }
        decrementBatch(state, pos);
    }
    
    private void onFailure(DimensionState state, ChunkPos pos) {
        stats.incrementFailed();
        state.remainingInRadius.updateAndGet(v -> Math.max(0, v - 1));
        decrementBatch(state, pos);
    }

    private void decrementBatch(DimensionState state, ChunkPos pos) {
        long batchKey = DistanceGraph.getBatchKey(pos.x, pos.z);
        AtomicInteger counter = state.batchCounters.get(batchKey);
        if (counter != null && counter.decrementAndGet() <= 0) {
            state.trackedBatches.remove(batchKey);
            state.batchCounters.remove(batchKey);
        }
    }
    
    private void completeTask(DimensionState state, ChunkPos pos) {
        if (state.trackedChunks.remove(pos.toLong())) {
            activeTaskCount.decrementAndGet();
            throttle.release();
        }
    }
    
    public void scheduleConfigReload() {
        configReloadScheduled.set(true);
    }
    
    public GenerationStats getStats() { return stats; }
    public int getActiveTaskCount() { return activeTaskCount.get(); }
    public int getRemainingInRadius() { 
        return dimensionStates.values().stream()
            .mapToInt(state -> state.remainingInRadius.get())
            .sum();
    }
    public boolean isThrottled() { return tpsMonitor.isThrottled(); }
    public int getQueueSize() { return 0; }
    
    public void setPauseCheck(java.util.function.BooleanSupplier check) {
        this.pauseCheck = check;
    }
}
