package com.ethan.voxyworldgenv2.core;

import com.ethan.voxyworldgenv2.network.NetworkHandler;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class ChunkUpdateTracker {
    private static final ChunkUpdateTracker INSTANCE = new ChunkUpdateTracker();
    private final Set<Long> dirtyChunks = ConcurrentHashMap.newKeySet();
    private long lastProcessTime = 0;

    private ChunkUpdateTracker() {}

    public static ChunkUpdateTracker getInstance() {
        return INSTANCE;
    }

    public void markDirty(LevelChunk chunk) {
        dirtyChunks.add(chunk.getPos().toLong());
    }

    public void processDirty(ServerLevel level) {
        if (level == null || dirtyChunks.isEmpty()) return;

        // throttle processing to every 2 seconds (40 ticks)
        long now = System.currentTimeMillis();
        if (now - lastProcessTime < 2000) return;
        lastProcessTime = now;

        Set<Long> toProcess = new java.util.HashSet<>(dirtyChunks);
        dirtyChunks.clear();

        for (long posLong : toProcess) {
            ChunkPos pos = new ChunkPos(posLong);
            LevelChunk chunk = level.getChunkSource().getChunk(pos.x, pos.z, false);
            if (chunk != null) {
                NetworkHandler.broadcastLODData(chunk);
            }
        }
    }
}
