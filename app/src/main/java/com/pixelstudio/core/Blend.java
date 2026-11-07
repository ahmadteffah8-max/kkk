package com.pixelstudio.core;

/** Straight-alpha ARGB blending helpers. */
public final class Blend {
    private Blend() {}

    /** Source-over compositing of straight-alpha ARGB colors. */
    public static int over(int dst, int src) {
        int sa = src >>> 24;
        if (sa == 255) return src;
        if (sa == 0) return dst;
        int da = dst >>> 24;
        if (da == 0) return src;
        int outA = sa * 255 + da * (255 - sa);
        int dw = da * (255 - sa);
        int sw = sa * 255;
        int r = (((src >> 16) & 255) * sw + ((dst >> 16) & 255) * dw + outA / 2) / outA;
        int g = (((src >> 8) & 255) * sw + ((dst >> 8) & 255) * dw + outA / 2) / outA;
        int b = ((src & 255) * sw + (dst & 255) * dw + outA / 2) / outA;
        int a = (outA + 127) / 255;
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /** Scales the alpha channel by factor/255. */
    public static int scaleAlpha(int c, int factor) {
        if (factor >= 255) return c;
        int a = ((c >>> 24) * factor + 127) / 255;
        if (a == 0) return 0;
        return (a << 24) | (c & 0xFFFFFF);
    }

    public static int argb(int a, int r, int g, int b) {
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /** Largest per-channel difference between two ARGB colors (alpha included). */
    public static int distance(int a, int b) {
        int d = Math.abs((a >>> 24) - (b >>> 24));
        d = Math.max(d, Math.abs(((a >> 16) & 255) - ((b >> 16) & 255)));
        d = Math.max(d, Math.abs(((a >> 8) & 255) - ((b >> 8) & 255)));
        d = Math.max(d, Math.abs((a & 255) - (b & 255)));
        return d;
    }
}
