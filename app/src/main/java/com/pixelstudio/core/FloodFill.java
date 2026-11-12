package com.pixelstudio.core;

/** Scanline flood fill with tolerance. */
public final class FloodFill {
    private FloodFill() {}

    /**
     * Fills the region connected to (sx, sy) with newColor (replacing pixels).
     * @param clip {x0,y0,x1,y1} exclusive end, or null
     * @return changed bounds {x,y,w,h} or null when nothing changed
     */
    public static int[] fill(int[] px, int w, int h, int sx, int sy, int newColor, int tol, int[] clip) {
        return fill(px, w, h, sx, sy, newColor, tol, 255, clip);
    }

    /**
     * Same as above with a strength: opacity 1..255 blends the colour over what is there
     * (255 replaces the pixels completely).
     */
    public static int[] fill(int[] px, int w, int h, int sx, int sy, int newColor, int tol, int opacity, int[] clip) {
        final int op = Math.max(1, Math.min(255, opacity));
        final int paint = op >= 255 ? newColor : Blend.scaleAlpha(newColor, op);
        int cx0 = 0, cy0 = 0, cx1 = w, cy1 = h;
        if (clip != null) {
            cx0 = Math.max(0, clip[0]); cy0 = Math.max(0, clip[1]);
            cx1 = Math.min(w, clip[2]); cy1 = Math.min(h, clip[3]);
        }
        if (sx < cx0 || sy < cy0 || sx >= cx1 || sy >= cy1) return null;
        int target = px[sy * w + sx];
        if (tol == 0 && target == newColor && op >= 255) return null;

        boolean[] seen = new boolean[w * h];
        int[] stack = new int[1024];
        int sp = 0;
        stack[sp++] = sx; stack[sp++] = sy;
        int minX = w, minY = h, maxX = -1, maxY = -1;

        while (sp > 0) {
            int y = stack[--sp];
            int x = stack[--sp];
            int row = y * w;
            if (seen[row + x] || Blend.distance(px[row + x], target) > tol) continue;
            int xl = x, xr = x;
            while (xl - 1 >= cx0 && !seen[row + xl - 1] && Blend.distance(px[row + xl - 1], target) <= tol) xl--;
            while (xr + 1 < cx1 && !seen[row + xr + 1] && Blend.distance(px[row + xr + 1], target) <= tol) xr++;
            for (int i = xl; i <= xr; i++) {
                seen[row + i] = true;
                px[row + i] = op >= 255 ? newColor : Blend.over(px[row + i], paint);
            }
            if (xl < minX) minX = xl;
            if (xr > maxX) maxX = xr;
            if (y < minY) minY = y;
            if (y > maxY) maxY = y;
            for (int ny = y - 1; ny <= y + 1; ny += 2) {
                if (ny < cy0 || ny >= cy1) continue;
                int nrow = ny * w;
                boolean inRun = false;
                for (int i = xl; i <= xr; i++) {
                    boolean ok = !seen[nrow + i] && Blend.distance(px[nrow + i], target) <= tol;
                    if (ok && !inRun) {
                        if (sp + 2 > stack.length) stack = java.util.Arrays.copyOf(stack, stack.length * 2);
                        stack[sp++] = i; stack[sp++] = ny;
                        inRun = true;
                    } else if (!ok) {
                        inRun = false;
                    }
                }
            }
        }
        if (maxX < 0) return null;
        return new int[]{minX, minY, maxX - minX + 1, maxY - minY + 1};
    }

    // ------------------------------------------------------------------ fill inside an outline

    /** Returned by {@link #fillInside} when the outline is not closed (the area reaches the canvas border). */
    public static final int[] LEAK = new int[]{-1, -1, -1, -1};
    /** Returned by {@link #fillInside} when the tap landed on the outline itself. */
    public static final int[] ON_EDGE = new int[]{-2, -2, -2, -2};

    private static boolean isEdge(int c, int edgeColor, int edgeTol) {
        if ((c >>> 24) < 96) return false;
        return Blend.distance(c | 0xFF000000, edgeColor | 0xFF000000) <= edgeTol;
    }

    /**
     * Fills everything enclosed by an outline, whatever colours are inside it.
     * Boundary pixels are those of {@code sample} (what the person sees) that are opaque and within
     * {@code edgeTol} of {@code edgeColor}; every other pixel connected to (sx, sy) is painted into
     * {@code target}. {@code gap} (0..4) seals small holes in the outline (holes up to about 2*gap pixels).
     *
     * @return changed bounds {x,y,w,h}, null when nothing changed, {@link #LEAK} or {@link #ON_EDGE}
     */
    public static int[] fillInside(int[] sample, int[] target, int w, int h, int sx, int sy, int newColor,
                                   int opacity, int edgeColor, int edgeTol, int gap, int[] clip) {
        int cx0 = 0, cy0 = 0, cx1 = w, cy1 = h;
        if (clip != null) {
            cx0 = Math.max(0, clip[0]); cy0 = Math.max(0, clip[1]);
            cx1 = Math.min(w, clip[2]); cy1 = Math.min(h, clip[3]);
        }
        if (sx < cx0 || sy < cy0 || sx >= cx1 || sy >= cy1) return null;
        final int op = Math.max(1, Math.min(255, opacity));
        final int paint = op >= 255 ? newColor : Blend.scaleAlpha(newColor, op);
        int n = w * h;
        boolean[] edge = new boolean[n];
        for (int i = 0; i < n; i++) edge[i] = isEdge(sample[i], edgeColor, edgeTol);
        if (edge[sy * w + sx]) return ON_EDGE;

        gap = Math.max(0, Math.min(4, gap));
        boolean[] blocked = gap == 0 ? edge : close(edge, w, h, gap);
        if (blocked[sy * w + sx]) {
            blocked = edge; // the tap is inside a closed gap: use the exact outline instead
        }
        boolean[] seen = new boolean[n];
        int[] stack = new int[1024];
        int sp = 0;
        stack[sp++] = sx; stack[sp++] = sy;
        boolean leak = false;
        while (sp > 0) {
            int y = stack[--sp];
            int x = stack[--sp];
            int row = y * w;
            if (seen[row + x] || blocked[row + x]) continue;
            int xl = x, xr = x;
            while (xl - 1 >= cx0 && !seen[row + xl - 1] && !blocked[row + xl - 1]) xl--;
            while (xr + 1 < cx1 && !seen[row + xr + 1] && !blocked[row + xr + 1]) xr++;
            for (int i = xl; i <= xr; i++) seen[row + i] = true;
            if (clip == null && (xl == 0 || xr == w - 1 || y == 0 || y == h - 1)) { leak = true; break; }
            for (int ny = y - 1; ny <= y + 1; ny += 2) {
                if (ny < cy0 || ny >= cy1) continue;
                int nrow = ny * w;
                boolean inRun = false;
                for (int i = xl; i <= xr; i++) {
                    boolean ok = !seen[nrow + i] && !blocked[nrow + i];
                    if (ok && !inRun) {
                        if (sp + 2 > stack.length) stack = java.util.Arrays.copyOf(stack, stack.length * 2);
                        stack[sp++] = i; stack[sp++] = ny;
                        inRun = true;
                    } else if (!ok) {
                        inRun = false;
                    }
                }
            }
        }
        if (leak) return LEAK;
        int minX = w, minY = h, maxX = -1, maxY = -1;
        for (int y = cy0; y < cy1; y++) {
            for (int x = cx0; x < cx1; x++) {
                int i = y * w + x;
                if (!seen[i]) continue;
                int old = target[i];
                int nv = op >= 255 ? newColor : Blend.over(old, paint);
                if (nv == old) continue;
                target[i] = nv;
                if (x < minX) minX = x;
                if (x > maxX) maxX = x;
                if (y < minY) minY = y;
                if (y > maxY) maxY = y;
            }
        }
        if (maxX < 0) return null;
        return new int[]{minX, minY, maxX - minX + 1, maxY - minY + 1};
    }

    /** Morphological closing: seals holes up to about 2r pixels wide while keeping the outline thickness. */
    private static boolean[] close(boolean[] src, int w, int h, int r) {
        boolean[] d = dilate(src, w, h, r);
        boolean[] inv = new boolean[d.length];
        for (int i = 0; i < d.length; i++) inv[i] = !d[i];
        boolean[] di = dilate(inv, w, h, r);
        boolean[] out = new boolean[d.length];
        for (int i = 0; i < d.length; i++) out[i] = !di[i];
        return out;
    }

    /** Square dilation of a boolean mask by r pixels (separable). */
    private static boolean[] dilate(boolean[] src, int w, int h, int r) {
        boolean[] a = new boolean[src.length];
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                if (!src[row + x]) continue;
                int x0 = Math.max(0, x - r), x1 = Math.min(w - 1, x + r);
                for (int k = x0; k <= x1; k++) a[row + k] = true;
            }
        }
        boolean[] b = new boolean[src.length];
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                if (!a[y * w + x]) continue;
                int y0 = Math.max(0, y - r), y1 = Math.min(h - 1, y + r);
                for (int k = y0; k <= y1; k++) b[k * w + x] = true;
            }
        }
        return b;
    }

    // ------------------------------------------------------------------ smart selection (magic wand)

    /** A selected area: bounding box plus a mask local to that box. */
    public static final class Region {
        public final int x, y, w, h;
        public final boolean[] mask; // w * h
        public final int count;

        Region(int x, int y, int w, int h, boolean[] mask, int count) {
            this.x = x; this.y = y; this.w = w; this.h = h; this.mask = mask; this.count = count;
        }
    }

    /**
     * Selects the connected area around (sx, sy).
     * @param object false: pixels similar in colour to the start pixel (within tol);
     *               true: the whole shape, i.e. every connected pixel that is visible (alpha > 0), whatever its colour
     *               (when the start pixel is empty, the connected empty area is selected instead)
     * @return the region or null when the point is outside
     */
    public static Region region(int[] px, int w, int h, int sx, int sy, int tol, boolean object, int[] clip) {
        int cx0 = 0, cy0 = 0, cx1 = w, cy1 = h;
        if (clip != null) {
            cx0 = Math.max(0, clip[0]); cy0 = Math.max(0, clip[1]);
            cx1 = Math.min(w, clip[2]); cy1 = Math.min(h, clip[3]);
        }
        if (sx < cx0 || sy < cy0 || sx >= cx1 || sy >= cy1) return null;
        int target = px[sy * w + sx];
        boolean targetEmpty = (target >>> 24) == 0;
        boolean[] seen = new boolean[w * h];
        int[] stack = new int[1024];
        int sp = 0;
        stack[sp++] = sx; stack[sp++] = sy;
        int minX = w, minY = h, maxX = -1, maxY = -1, count = 0;
        while (sp > 0) {
            int y = stack[--sp];
            int x = stack[--sp];
            int row = y * w;
            if (seen[row + x] || !wandMatch(px[row + x], target, targetEmpty, tol, object)) continue;
            int xl = x, xr = x;
            while (xl - 1 >= cx0 && !seen[row + xl - 1] && wandMatch(px[row + xl - 1], target, targetEmpty, tol, object)) xl--;
            while (xr + 1 < cx1 && !seen[row + xr + 1] && wandMatch(px[row + xr + 1], target, targetEmpty, tol, object)) xr++;
            for (int i = xl; i <= xr; i++) seen[row + i] = true;
            count += xr - xl + 1;
            if (xl < minX) minX = xl;
            if (xr > maxX) maxX = xr;
            if (y < minY) minY = y;
            if (y > maxY) maxY = y;
            for (int ny = y - 1; ny <= y + 1; ny += 2) {
                if (ny < cy0 || ny >= cy1) continue;
                int nrow = ny * w;
                boolean inRun = false;
                for (int i = xl; i <= xr; i++) {
                    boolean ok = !seen[nrow + i] && wandMatch(px[nrow + i], target, targetEmpty, tol, object);
                    if (ok && !inRun) {
                        if (sp + 2 > stack.length) stack = java.util.Arrays.copyOf(stack, stack.length * 2);
                        stack[sp++] = i; stack[sp++] = ny;
                        inRun = true;
                    } else if (!ok) {
                        inRun = false;
                    }
                }
            }
        }
        if (maxX < 0) return null;
        int rw = maxX - minX + 1, rh = maxY - minY + 1;
        boolean[] m = new boolean[rw * rh];
        for (int y = 0; y < rh; y++) {
            System.arraycopy(seen, (minY + y) * w + minX, m, y * rw, rw);
        }
        return new Region(minX, minY, rw, rh, m, count);
    }

    private static boolean wandMatch(int c, int target, boolean targetEmpty, int tol, boolean object) {
        if (object) return targetEmpty ? (c >>> 24) == 0 : (c >>> 24) != 0;
        return Blend.distance(c, target) <= tol;
    }
}
