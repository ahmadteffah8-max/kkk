package com.pixelstudio.core;

/** One animation frame: one ARGB pixel array per document layer. */
public final class Frame {
    public int[][] px;
    /** Display time in milliseconds. */
    public int delayMs = 100;
    /** Incremented whenever pixels change; used by UI caches (thumbnails). */
    public int version;
    /** Free slot for UI caches. */
    public Object cache;
    public int cacheVersion = -1;

    public Frame(int[][] px, int delayMs) {
        this.px = px;
        this.delayMs = delayMs;
    }

    /** Copy with the same layer arrays (shallow). */
    public Frame shallowCopy() {
        return new Frame(px.clone(), delayMs);
    }

    /** Copy with duplicated pixel data. */
    public Frame deepCopy() {
        int[][] n = new int[px.length][];
        for (int i = 0; i < px.length; i++) n[i] = px[i].clone();
        return new Frame(n, delayMs);
    }
}
