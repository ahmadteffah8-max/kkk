package com.pixelstudio.core;

/** Pure pixel helpers shared by export code and tests. */
public final class Raster {
    private Raster() {}

    /** Composites a frame and lays it over an opaque background (bg == 0 keeps transparency). */
    public static int[] withBackground(Doc d, Frame f, int bg) {
        int[] px = d.compose(f);
        if (bg != 0) {
            int opaque = 0xFF000000 | bg;
            for (int i = 0; i < px.length; i++) px[i] = (px[i] >>> 24) == 255 ? px[i] : Blend.over(opaque, px[i]);
        }
        return px;
    }

    /** Integer nearest-neighbour upscale. */
    public static int[] scaleNearest(int[] src, int w, int h, int k) {
        if (k <= 1) return src;
        int nw = w * k, nh = h * k;
        int[] out = new int[nw * nh];
        for (int y = 0; y < nh; y++) {
            int sy = (y / k) * w;
            int row = y * nw;
            for (int x = 0; x < nw; x++) out[row + x] = src[sy + x / k];
        }
        return out;
    }
}
