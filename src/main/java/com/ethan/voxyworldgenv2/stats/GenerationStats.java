package com.ethan.voxyworldgenv2.stats;

import java.util.concurrent.atomic.AtomicLong;

public class GenerationStats {
    private final AtomicLong chunksQueued = new AtomicLong(0);
    private final AtomicLong chunksCompleted = new AtomicLong(0);
    private final AtomicLong chunksFailed = new AtomicLong(0);
    private final AtomicLong chunksSkipped = new AtomicLong(0);
    private volatile long startTime = System.currentTimeMillis();
    
    public void incrementQueued() { chunksQueued.incrementAndGet(); }
    public void incrementCompleted() { chunksCompleted.incrementAndGet(); }
    public void incrementFailed() { chunksFailed.incrementAndGet(); }
    public void incrementSkipped() { chunksSkipped.incrementAndGet(); }
    
    public long getQueued() { return chunksQueued.get(); }
    public long getCompleted() { return chunksCompleted.get(); }
    public long getFailed() { return chunksFailed.get(); }
    public long getSkipped() { return chunksSkipped.get(); }
    
    public double getChunksPerSecond() {
        long elapsed = System.currentTimeMillis() - startTime;
        return elapsed <= 0 ? 0 : (chunksCompleted.get() * 1000.0) / elapsed;
    }
    
    public void reset() {
        chunksQueued.set(0);
        chunksCompleted.set(0);
        chunksFailed.set(0);
        chunksSkipped.set(0);
        startTime = System.currentTimeMillis();
    }
}
