package com.pixelstudio.core;

/**
 * Rasterizer for one brush stroke or shape on a single layer.
 * Coverage is tracked per pixel (max rule), so overlapping stamps never accumulate alpha.
 * The layer is restored from a pre-stroke copy, which also makes live shape preview possible.
 */
public final class Stroke {
    /** Pen styles (see {@link #setStyle}). */
    public static final int ST_NORMAL = 0, ST_MARKER = 1, ST_CALLIGRAPHY = 2, ST_AIRBRUSH = 3, ST_WATERCOLOR = 4,
            ST_SPRAY = 5, ST_GRAPHITE = 6, ST_CHARCOAL = 7, ST_HATCH = 8, ST_SHADE_DARK = 9, ST_SHADE_LIGHT = 10;
    public static final int ST_COUNT = 11;

    private final int w, h;
    private final byte[] cov;
    private int[] before;
    private int[] target;

    private int color;
    private boolean erase, aa, square;
    private int size = 1;
    private int style = ST_NORMAL;
    private int opacity = 255;
    private long rng = 0x9E3779B97F4A7C15L;
    private int clipX0, clipY0, clipX1, clipY1;

    private int dx0, dy0, dx1, dy1; // dirty rect, exclusive end
    private boolean dirty;

    public Stroke(int w, int h) {
        this.w = w;
        this.h = h;
        this.cov = new byte[w * h];
    }

    /**
     * @param layer  live layer pixels (modified in place)
     * @param aa     antialiased round brush; false = hard edge
     * @param square hard square stamp (pixel art); ignored when aa
     * @param clip   {x0,y0,x1,y1} exclusive end, or null for the whole canvas
     */
    public void begin(int[] layer, int color, int size, boolean erase, boolean aa, boolean square, int[] clip) {
        target = layer;
        if (before == null || before.length != layer.length) before = new int[layer.length];
        System.arraycopy(layer, 0, before, 0, layer.length);
        this.color = color;
        this.size = Math.max(1, size);
        this.erase = erase;
        this.aa = aa;
        this.square = square;
        this.style = ST_NORMAL;
        this.opacity = 255;
        this.rng = 0x9E3779B97F4A7C15L ^ ((long) this.size * 2654435761L);
        if (clip == null) {
            clipX0 = 0; clipY0 = 0; clipX1 = w; clipY1 = h;
        } else {
            clipX0 = Math.max(0, clip[0]); clipY0 = Math.max(0, clip[1]);
            clipX1 = Math.min(w, clip[2]); clipY1 = Math.min(h, clip[3]);
        }
        dirty = false;
    }

    /**
     * Chooses the pen style and the stroke opacity (1..100). Call right after {@link #begin}.
     * Opacity applies to the whole stroke at once (overlaps never darken), so a marker or a
     * shading pass stays even.
     */
    public void setStyle(int style, int opacityPercent) {
        this.style = (style < 0 || style >= ST_COUNT) ? ST_NORMAL : style;
        int o = Math.max(1, Math.min(100, opacityPercent));
        this.opacity = o >= 100 ? 255 : (o * 255 + 50) / 100;
    }

    private int rnd() {
        rng ^= rng << 13;
        rng ^= rng >>> 7;
        rng ^= rng << 17;
        return (int) (rng >>> 33); // 31 bits
    }

    /** Stable per-pixel noise 0..255 (so overlapping stamps keep the same grain). */
    private static int grain(int x, int y) {
        int h = x * 374761393 + y * 668265263;
        h = (h ^ (h >>> 13)) * 1274126177;
        return (h ^ (h >>> 16)) & 255;
    }

    private static int shade(int b, int c, boolean light) {
        int a = b >>> 24;
        if (a == 0) return b; // nothing to shade on empty pixels
        int r = (b >> 16) & 255, g = (b >> 8) & 255, bl = b & 255;
        if (light) {
            r += ((255 - r) * c + 127) / 255;
            g += ((255 - g) * c + 127) / 255;
            bl += ((255 - bl) * c + 127) / 255;
        } else {
            r -= (r * c + 127) / 255;
            g -= (g * c + 127) / 255;
            bl -= (bl * c + 127) / 255;
        }
        return (a << 24) | (r << 16) | (g << 8) | bl;
    }

    public boolean isDirty() { return dirty; }

    private void touch(int x0, int y0, int x1, int y1) {
        if (!dirty) {
            dx0 = x0; dy0 = y0; dx1 = x1; dy1 = y1;
            dirty = true;
        } else {
            if (x0 < dx0) dx0 = x0;
            if (y0 < dy0) dy0 = y0;
            if (x1 > dx1) dx1 = x1;
            if (y1 > dy1) dy1 = y1;
        }
    }

    private void setCov(int i, int c) {
        if (opacity < 255) c = (c * opacity + 127) / 255;
        int old = cov[i] & 255;
        if (c <= old) return;
        cov[i] = (byte) c;
        int b = before[i];
        if (!erase && (style == ST_SHADE_DARK || style == ST_SHADE_LIGHT)) {
            target[i] = shade(b, c, style == ST_SHADE_LIGHT);
        } else if (erase) {
            int a = ((b >>> 24) * (255 - c) + 127) / 255;
            target[i] = a == 0 ? 0 : (a << 24) | (b & 0xFFFFFF);
        } else {
            int sc = c == 255 ? color : Blend.scaleAlpha(color, c);
            target[i] = Blend.over(b, sc);
        }
    }

    /** Stamps the brush centered on pixel (cx, cy). */
    public void stamp(int cx, int cy) {
        int s = size;
        int x0 = cx - s / 2, y0 = cy - s / 2;
        int ex = x0 + s, ey = y0 + s;
        int ax0 = Math.max(x0, clipX0), ay0 = Math.max(y0, clipY0);
        int ax1 = Math.min(ex, clipX1), ay1 = Math.min(ey, clipY1);
        if (ax1 <= ax0 || ay1 <= ay0) return;
        touch(ax0, ay0, ax1, ay1);
        if (style != ST_NORMAL && !erase) {
            if (style == ST_SPRAY) spray(cx, cy);
            else styled(ax0, ay0, ax1, ay1, x0 + s / 2.0, y0 + s / 2.0);
            return;
        }
        if (!aa && (square || s <= 2)) {
            for (int y = ay0; y < ay1; y++) {
                int row = y * w;
                for (int x = ax0; x < ax1; x++) setCov(row + x, 255);
            }
            return;
        }
        double r = s / 2.0;
        double ccx = x0 + r, ccy = y0 + r;
        for (int y = ay0; y < ay1; y++) {
            int row = y * w;
            double dy = y + 0.5 - ccy;
            for (int x = ax0; x < ax1; x++) {
                double dxx = x + 0.5 - ccx;
                double dist = Math.sqrt(dxx * dxx + dy * dy);
                int c;
                if (aa) {
                    double v = r - dist + 0.5;
                    if (v <= 0) continue;
                    c = v >= 1 ? 255 : (int) (v * 255 + 0.5);
                } else {
                    if (dist > r) continue;
                    c = 255;
                }
                setCov(row + x, c);
            }
        }
    }

    private static double clamp01(double v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }

    private void styled(int ax0, int ay0, int ax1, int ay1, double ccx, double ccy) {
        double r = size / 2.0;
        for (int y = ay0; y < ay1; y++) {
            int row = y * w;
            double dy = y + 0.5 - ccy;
            for (int x = ax0; x < ax1; x++) {
                double dx = x + 0.5 - ccx;
                int c = styleCoverage(x, y, dx, dy, r);
                if (c > 0) setCov(row + x, c);
            }
        }
    }

    /** Coverage 0..255 of one pixel for the current pen style (dx, dy = offset from the stamp centre). */
    private int styleCoverage(int x, int y, double dx, double dy, double r) {
        double dist = Math.sqrt(dx * dx + dy * dy);
        switch (style) {
            case ST_MARKER: // flat chisel tip
                return (Math.abs(dx) <= r && Math.abs(dy) <= Math.max(1.0, r * 0.5)) ? 255 : 0;
            case ST_CALLIGRAPHY: { // 45 degree flat nib
                double u = (dx + dy) * 0.70710678, v = (dy - dx) * 0.70710678;
                double minor = Math.max(0.7, r * 0.25);
                double t = Math.sqrt((u / r) * (u / r) + (v / minor) * (v / minor));
                return (int) (255 * clamp01((1 - t) * minor + 0.5) + 0.5);
            }
            case ST_AIRBRUSH: {
                double t = dist / r;
                if (t >= 1) return 0;
                double k = 1 - t;
                return (int) (255 * k * k + 0.5);
            }
            case ST_WATERCOLOR: {
                double t = dist / r;
                if (t >= 1) return 0;
                double k = Math.min(1.0, (1 - t) * 2.5);
                double n = 0.8 + 0.2 * (grain(x >> 1, y >> 1) / 255.0);
                return (int) (255 * k * n + 0.5);
            }
            case ST_GRAPHITE: {
                double e = clamp01(r - dist + 0.5);
                if (e <= 0) return 0;
                int g = grain(x, y);
                double k = g < 64 ? 0.35 : 0.65 + 0.35 * (g / 255.0);
                return (int) (255 * e * k + 0.5);
            }
            case ST_CHARCOAL: {
                double e = clamp01(r - dist + 0.5);
                if (e <= 0) return 0;
                int g = grain(x * 3 + 1, y * 3 + 7);
                double k = g < 80 ? 0.12 : Math.min(1.0, (g / 255.0) * 1.3);
                return (int) (255 * e * k + 0.5);
            }
            case ST_HATCH: {
                if (dist > r) return 0;
                int period = size >= 12 ? 8 : 4;
                int m = ((x + y) % period + period) % period;
                return m < period / 2 ? 255 : 0;
            }
            case ST_SHADE_DARK:
            case ST_SHADE_LIGHT: {
                if (!aa) return (square || dist <= r) ? 255 : 0; // pixel art: hard blocks
                double t = dist / r;
                if (t >= 1) return 0;
                return (int) (255 * Math.min(1.0, (1 - t) * 2.0) + 0.5);
            }
            default:
                return 0;
        }
    }

    private void spray(int cx, int cy) {
        double r = size / 2.0;
        int n = (int) Math.max(3, Math.min(120, r * r * 0.9));
        for (int k = 0; k < n; k++) {
            double ang = (rnd() / 2147483648.0) * 2 * Math.PI;
            double d = r * Math.sqrt(rnd() / 2147483648.0);
            int px = (int) Math.floor(cx + 0.5 + d * Math.cos(ang));
            int py = (int) Math.floor(cy + 0.5 + d * Math.sin(ang));
            if (px < clipX0 || px >= clipX1 || py < clipY0 || py >= clipY1) continue;
            touch(px, py, px + 1, py + 1);
            setCov(py * w + px, 140 + (rnd() % 116));
        }
    }

    /** Stamps along a line from (x0,y0) to (x1,y1). */
    public void line(int x0, int y0, int x1, int y1) {
        int dx = x1 - x0, dy = y1 - y0;
        int n = Math.max(Math.abs(dx), Math.abs(dy));
        if (n == 0) { stamp(x0, y0); return; }
        int step = aa ? Math.max(1, size / 4) : (size > 8 ? size / 4 : 1);
        if (style == ST_SPRAY) step = Math.max(1, size / 5);
        else if (style != ST_NORMAL) step = Math.max(1, size / 8);
        for (int k = 0; k < n; k += step) {
            stamp((int) Math.round(x0 + dx * (double) k / n), (int) Math.round(y0 + dy * (double) k / n));
        }
        stamp(x1, y1);
    }

    private void hline(int y, int xa, int xb) {
        if (y < clipY0 || y >= clipY1) return;
        int a = Math.max(Math.min(xa, xb), clipX0);
        int b = Math.min(Math.max(xa, xb), clipX1 - 1);
        if (b < a) return;
        touch(a, y, b + 1, y + 1);
        int row = y * w;
        for (int x = a; x <= b; x++) setCov(row + x, 255);
    }

    /** Rectangle between two corner pixels (inclusive). */
    public void rect(int x0, int y0, int x1, int y1, boolean fill) {
        int ax = Math.min(x0, x1), bx = Math.max(x0, x1);
        int ay = Math.min(y0, y1), by = Math.max(y0, y1);
        if (fill) {
            for (int y = ay; y <= by; y++) hline(y, ax, bx);
            return;
        }
        line(ax, ay, bx, ay);
        line(bx, ay, bx, by);
        line(bx, by, ax, by);
        line(ax, by, ax, ay);
    }

    /** Ellipse inscribed in the box between two corner pixels (inclusive). */
    public void ellipse(int x0, int y0, int x1, int y1, boolean fill) {
        int ax = Math.min(x0, x1), bx = Math.max(x0, x1);
        int ay = Math.min(y0, y1), by = Math.max(y0, y1);
        double cx = (ax + bx) / 2.0, cy = (ay + by) / 2.0;
        double rx = (bx - ax) / 2.0, ry = (by - ay) / 2.0;
        if (fill) {
            for (int y = ay; y <= by; y++) {
                if (ry == 0) { hline(y, ax, bx); continue; }
                double t = (y - cy) / ry;
                double q = 1 - t * t;
                if (q < 0) q = 0;
                double half = rx * Math.sqrt(q);
                hline(y, (int) Math.round(cx - half), (int) Math.round(cx + half));
            }
            return;
        }
        int steps = (int) Math.max(16, Math.ceil(2 * Math.PI * Math.max(rx, ry) * 1.5));
        int px = (int) Math.round(cx + rx), py = (int) Math.round(cy);
        for (int i = 1; i <= steps; i++) {
            double t = 2 * Math.PI * i / steps;
            int nx = (int) Math.round(cx + rx * Math.cos(t));
            int ny = (int) Math.round(cy + ry * Math.sin(t));
            line(px, py, nx, ny);
            px = nx; py = ny;
        }
    }

    /** Dirty rect {x, y, w, h} or null. */
    public int[] bounds() {
        return dirty ? new int[]{dx0, dy0, dx1 - dx0, dy1 - dy0} : null;
    }

    /** Undo everything drawn so far (restores the layer inside the dirty rect). */
    public void reset() {
        if (!dirty) return;
        for (int y = dy0; y < dy1; y++) {
            int row = y * w;
            System.arraycopy(before, row + dx0, target, row + dx0, dx1 - dx0);
            java.util.Arrays.fill(cov, row + dx0, row + dx1, (byte) 0);
        }
        dirty = false;
    }

    /** Ends the stroke. Returns an undo patch or null when nothing changed. */
    public Patch finish() {
        if (!dirty) return null;
        Patch p = Patch.fromDiff(before, target, w, dx0, dy0, dx1 - dx0, dy1 - dy0);
        for (int y = dy0; y < dy1; y++) {
            java.util.Arrays.fill(cov, y * w + dx0, y * w + dx1, (byte) 0);
        }
        dirty = false;
        return p;
    }
}
