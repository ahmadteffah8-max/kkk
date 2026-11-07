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
        int cx0 = 0, cy0 = 0, cx1 = w, cy1 = h;
        if (clip != null) {
            cx0 = Math.max(0, clip[0]); cy0 = Math.max(0, clip[1]);
            cx1 = Math.min(w, clip[2]); cy1 = Math.min(h, clip[3]);
        }
        if (sx < cx0 || sy < cy0 || sx >= cx1 || sy >= cy1) return null;
        int target = px[sy * w + sx];
        if (tol == 0 && target == newColor) return null;

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
                px[row + i] = newColor;
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
}
