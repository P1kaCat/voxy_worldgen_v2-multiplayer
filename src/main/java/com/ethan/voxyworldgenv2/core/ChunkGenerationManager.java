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

import java.util.Set;
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
    private final ChunkScanner scanner = new ChunkScanner();
    private Semaphore throttle;
    private MinecraftServer server;
    private ResourceKey<Level> currentDimensionKey = null;
    private ServerLevel currentLevel = null;
    
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
        server = null;
        stats.reset();
        activeTaskCount.set(0);
        remainingInRadius.set(0);
        tpsMonitor.reset();
        scanner.stop();
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
                if (server == null || currentLevel == null || !scanner.hasNext()) {
                    Thread.sleep(100);
                    continue;
                }

                if (tpsMonitor.isThrottled()) {
                    Thread.sleep(500);
                    continue;
                }

                // acquire permit - will block if full
                throttle.acquire();

                // double check after acquire
                if (!workerRunning.get()) break;

                ChunkPos pos = scanner.next();
                if (pos == null) {
                    throttle.release();
                    continue;
                }

                if (processChunk(pos)) {
                    // task submitted, permit retained until completion
                } else {
                    // skipped
                    throttle.release();
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                VoxyWorldGenV2.LOGGER.error("error in generation worker", e);
                throttle.release();
            }
        }
    }

    // returns true if task submitted, false if skipped
    private boolean processChunk(ChunkPos pos) {
        long key = pos.toLong();
        
        if (completedChunks.contains(key)) {
            // silently skip known completed chunks to avoid stat spam
            remainingInRadius.decrementAndGet();
            return false;
        }

        // read-only check on map - reasonably safe on background thread for skipping
        if (currentLevel.hasChunk(pos.x, pos.z)) {
            completedChunks.add(key);
            stats.incrementSkipped();
            remainingInRadius.decrementAndGet();
            return false;
        }

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

        // update scanner if moved significantly
        if (scanner.getCenter() == null || currentPos.getChessboardDistance(scanner.getCenter()) > 16) {
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
        ChunkPersistence.load(newLevel, currentDimensionKey, completedChunks);
    }
    
    private void restartScan(ChunkPos center) {
        scanner.stop();
        // clear tracked for new radius to allow retrying dropped tasks if any
        trackedChunks.clear(); 
        
        int radius = Config.DATA.generationRadius;
        long totalArea = (long) (2 * radius + 1) * (2 * radius + 1);
        remainingInRadius.set((int) totalArea);
        
        scanner.start(center, radius);
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
                    onSuccess(chunk);
                    // offload voxy ingestion
                    CompletableFuture.runAsync(() -> VoxyIntegration.ingestChunk(chunk))
                        .whenComplete((v, t) -> cleanupTask(cache, pos));
                } else {
                    onFailure();
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
    
    private void onSuccess(LevelChunk chunk) {
        completedChunks.add(chunk.getPos().toLong());
        stats.incrementCompleted();
        remainingInRadius.decrementAndGet();
    }
    
    private void onFailure() {
        stats.incrementFailed();
        remainingInRadius.decrementAndGet();
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
    public int getQueueSize() { return 0; } // queue is now implicit in the worker thread
}
