package com.pixelstudio.core;

/** Before/after pixels of a rectangular region of one layer. */
public final class Patch {
    public final int x, y, w, h;
    public final int[] before, after;

    public Patch(int x, int y, int w, int h, int[] before, int[] after) {
        this.x = x; this.y = y; this.w = w; this.h = h;
        this.before = before; this.after = after;
    }

    /** Builds a patch from two full-size layer arrays, restricted to the given rect. Returns null if identical. */
    public static Patch fromDiff(int[] oldPx, int[] newPx, int stride, int rx, int ry, int rw, int rh) {
        int[] b = new int[rw * rh];
        int[] a = new int[rw * rh];
        boolean changed = false;
        for (int j = 0; j < rh; j++) {
            int src = (ry + j) * stride + rx;
            System.arraycopy(oldPx, src, b, j * rw, rw);
            System.arraycopy(newPx, src, a, j * rw, rw);
        }
        for (int i = 0; i < b.length; i++) {
            if (b[i] != a[i]) { changed = true; break; }
        }
        return changed ? new Patch(rx, ry, rw, rh, b, a) : null;
    }

    private static void write(int[] layer, int stride, int x, int y, int w, int h, int[] data) {
        for (int j = 0; j < h; j++) {
            System.arraycopy(data, j * w, layer, (y + j) * stride + x, w);
        }
    }

    public void applyBefore(int[] layer, int stride) { write(layer, stride, x, y, w, h, before); }
    public void applyAfter(int[] layer, int stride) { write(layer, stride, x, y, w, h, after); }
    public long bytes() { return (long) w * h * 8L; }
}
