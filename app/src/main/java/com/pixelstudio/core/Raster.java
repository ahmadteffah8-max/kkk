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

    /**
     * Resamples src (sw x sh) to a virtual dw x dh image and returns only the window
     * [rx, rx+rw) x [ry, ry+rh) of it, so the memory used never depends on how large the target is.
     * smooth=false is nearest neighbour; smooth=true averages when shrinking and interpolates when enlarging.
     * Colors are averaged with premultiplied alpha, so transparent pixels never leave dark fringes.
     */
    public static int[] resizeWindow(int[] src, int sw, int sh, int dw, int dh,
                                     int rx, int ry, int rw, int rh, boolean smooth) {
        if (rw <= 0 || rh <= 0) return new int[0];
        int[] out = new int[rw * rh];
        if (sw <= 0 || sh <= 0 || dw <= 0 || dh <= 0) return out;
        if (dw == sw && dh == sh) {
            for (int y = 0; y < rh; y++) {
                int Y = ry + y;
                if (Y < 0 || Y >= sh) continue;
                for (int x = 0; x < rw; x++) {
                    int X = rx + x;
                    if (X >= 0 && X < sw) out[y * rw + x] = src[Y * sw + X];
                }
            }
            return out;
        }
        if (!smooth) {
            int[] cx = new int[rw];
            for (int x = 0; x < rw; x++) cx[x] = (int) Math.min(sw - 1, ((long) (rx + x) * 2 + 1) * sw / (2L * dw));
            for (int y = 0; y < rh; y++) {
                int Y = ry + y;
                if (Y < 0 || Y >= dh) continue;
                int sy = (int) Math.min(sh - 1, ((long) Y * 2 + 1) * sh / (2L * dh));
                for (int x = 0; x < rw; x++) {
                    int X = rx + x;
                    if (X < 0 || X >= dw) continue;
                    out[y * rw + x] = src[sy * sw + cx[x]];
                }
            }
            return out;
        }
        if (dw <= sw && dh <= sh) {
            for (int y = 0; y < rh; y++) {
                int Y = ry + y;
                if (Y < 0 || Y >= dh) continue;
                int y0 = (int) ((long) Y * sh / dh);
                int y1 = (int) (((long) (Y + 1) * sh + dh - 1) / dh);
                if (y1 <= y0) y1 = y0 + 1;
                if (y1 > sh) y1 = sh;
                for (int x = 0; x < rw; x++) {
                    int X = rx + x;
                    if (X < 0 || X >= dw) continue;
                    int x0 = (int) ((long) X * sw / dw);
                    int x1 = (int) (((long) (X + 1) * sw + dw - 1) / dw);
                    if (x1 <= x0) x1 = x0 + 1;
                    if (x1 > sw) x1 = sw;
                    long sa = 0, sr = 0, sg = 0, sb = 0;
                    int n = 0;
                    for (int yy = y0; yy < y1; yy++) {
                        int row = yy * sw;
                        for (int xx = x0; xx < x1; xx++) {
                            int c = src[row + xx];
                            int a = c >>> 24;
                            n++;
                            if (a == 0) continue;
                            sa += a;
                            sr += ((c >> 16) & 255) * a;
                            sg += ((c >> 8) & 255) * a;
                            sb += (c & 255) * a;
                        }
                    }
                    if (sa == 0) continue;
                    int a = (int) ((sa + n / 2) / n);
                    int r = (int) ((sr + sa / 2) / sa), g = (int) ((sg + sa / 2) / sa), b = (int) ((sb + sa / 2) / sa);
                    out[y * rw + x] = (a << 24) | (r << 16) | (g << 8) | b;
                }
            }
            return out;
        }
        // enlarging (or mixed): bilinear on premultiplied values
        int[] x0s = new int[rw], x1s = new int[rw];
        float[] tx = new float[rw];
        for (int x = 0; x < rw; x++) {
            float fx = ((rx + x) + 0.5f) * sw / dw - 0.5f;
            int i0 = (int) Math.floor(fx);
            tx[x] = fx - i0;
            x0s[x] = Math.max(0, Math.min(sw - 1, i0));
            x1s[x] = Math.max(0, Math.min(sw - 1, i0 + 1));
        }
        for (int y = 0; y < rh; y++) {
            int Y = ry + y;
            if (Y < 0 || Y >= dh) continue;
            float fy = (Y + 0.5f) * sh / dh - 0.5f;
            int j0 = (int) Math.floor(fy);
            float ty = fy - j0;
            int ya = Math.max(0, Math.min(sh - 1, j0)) * sw, yb = Math.max(0, Math.min(sh - 1, j0 + 1)) * sw;
            for (int x = 0; x < rw; x++) {
                int X = rx + x;
                if (X < 0 || X >= dw) continue;
                int c00 = src[ya + x0s[x]], c10 = src[ya + x1s[x]], c01 = src[yb + x0s[x]], c11 = src[yb + x1s[x]];
                if (c00 == c10 && c00 == c01 && c00 == c11) {
                    out[y * rw + x] = c00;
                    continue;
                }
                float fx = tx[x];
                float w00 = (1 - fx) * (1 - ty), w10 = fx * (1 - ty), w01 = (1 - fx) * ty, w11 = fx * ty;
                float a00 = (c00 >>> 24) * w00, a10 = (c10 >>> 24) * w10, a01 = (c01 >>> 24) * w01, a11 = (c11 >>> 24) * w11;
                float a = a00 + a10 + a01 + a11;
                if (a <= 0.0001f) continue;
                float r = ((c00 >> 16) & 255) * a00 + ((c10 >> 16) & 255) * a10 + ((c01 >> 16) & 255) * a01 + ((c11 >> 16) & 255) * a11;
                float g = ((c00 >> 8) & 255) * a00 + ((c10 >> 8) & 255) * a10 + ((c01 >> 8) & 255) * a01 + ((c11 >> 8) & 255) * a11;
                float b = (c00 & 255) * a00 + (c10 & 255) * a10 + (c01 & 255) * a01 + (c11 & 255) * a11;
                int ia = Math.min(255, Math.round(a));
                int ir = Math.min(255, Math.round(r / a)), ig = Math.min(255, Math.round(g / a)), ib = Math.min(255, Math.round(b / a));
                out[y * rw + x] = (ia << 24) | (ir << 16) | (ig << 8) | ib;
            }
        }
        return out;
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
