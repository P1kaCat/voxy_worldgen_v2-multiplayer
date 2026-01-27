package com.ethan.voxyworldgenv2.core;

import net.minecraft.world.level.ChunkPos;

public class ChunkScanner {
    private int x = 0, z = 0;
    private int dx = 0, dz = -1;
    private int radius = 0;
    private boolean scanning = false;
    private ChunkPos center = null;

    public void start(ChunkPos center, int radius) {
        this.center = center;
        this.radius = radius;
        this.x = 0;
        this.z = 0;
        this.dx = 0;
        this.dz = -1;
        this.scanning = true;
    }

    public void stop() {
        this.scanning = false;
        this.center = null;
    }

    public boolean hasNext() {
        return scanning && center != null;
    }

    public ChunkPos next() {
        if (!hasNext()) return null;

        ChunkPos pos = new ChunkPos(center.x + x, center.z + z);

        // spiral logic for next step
        if (x == z || (x < 0 && x == -z) || (x > 0 && x == 1 - z)) {
            int temp = dx;
            dx = -dz;
            dz = temp;
        }
        x += dx;
        z += dz;

        if (Math.max(Math.abs(x), Math.abs(z)) > radius) {
            scanning = false;
        }

        return pos;
    }

    public boolean isScanning() {
        return scanning;
    }

    public ChunkPos getCenter() {
        return center;
    }
}
