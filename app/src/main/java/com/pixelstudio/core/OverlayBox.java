package com.pixelstudio.core;

/**
 * Geometry of the "place an image" frame: a rectangle in canvas pixels with eight resize handles
 * and a body that moves it. Pure math (no Android) so it can be tested on a JVM.
 *
 * Corner handles keep the proportions the box had when the drag started; edge handles stretch one axis.
 */
public final class OverlayBox {
    public static final int NONE = -1, TL = 0, T = 1, TR = 2, R = 3, BR = 4, B = 5, BL = 6, L = 7, BODY = 8;
    public static final float MIN_SIZE = 1f;
    public static final float MAX_SIZE = 16384f;

    public float x, y, w, h;

    private int active = NONE;
    private float sx, sy, sw, sh;

    public OverlayBox(float x, float y, float w, float h) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
    }

    /** Position of a handle (corner or edge midpoint) in canvas pixels. */
    public float handleX(int handle) {
        switch (handle) {
            case TL: case L: case BL: return x;
            case T: case B: return x + w / 2f;
            default: return x + w;
        }
    }

    public float handleY(int handle) {
        switch (handle) {
            case TL: case T: case TR: return y;
            case L: case R: return y + h / 2f;
            default: return y + h;
        }
    }

    /**
     * What is under the point (px, py)? r is the touch radius of a handle in canvas pixels; it is
     * limited to a third of the box so a small box can always still be grabbed by its middle.
     */
    public int hit(float px, float py, float r) {
        float rr = Math.max(0.5f, Math.min(r, Math.min(w, h) / 3f));
        int best = NONE;
        float bestD = Float.MAX_VALUE;
        // corners first (so they win over edge handles on a tiny box), then edge midpoints
        int[] order = {TL, TR, BR, BL, T, R, B, L};
        for (int hnd : order) {
            float dx = px - handleX(hnd), dy = py - handleY(hnd);
            float d = dx * dx + dy * dy;
            if (d <= rr * rr && d < bestD) {
                best = hnd;
                bestD = d;
            }
        }
        if (best != NONE) return best;
        if (px >= x && px <= x + w && py >= y && py <= y + h) return BODY;
        return NONE;
    }

    /** Remembers the current box; later {@link #drag} calls are relative to this moment. */
    public void begin(int handle) {
        active = handle;
        sx = x;
        sy = y;
        sw = w;
        sh = h;
    }

    public int activeHandle() { return active; }

    public void end() { active = NONE; }

    /** Applies the drag distance since {@link #begin} (canvas pixels). */
    public void drag(float dx, float dy) {
        switch (active) {
            case BODY:
                x = sx + dx;
                y = sy + dy;
                break;
            case L: {
                float nw = clamp(sw - dx);
                x = sx + sw - nw;
                w = nw;
                break;
            }
            case R:
                w = clamp(sw + dx);
                break;
            case T: {
                float nh = clamp(sh - dy);
                y = sy + sh - nh;
                h = nh;
                break;
            }
            case B:
                h = clamp(sh + dy);
                break;
            case TL: case TR: case BR: case BL: {
                boolean left = active == TL || active == BL;
                boolean top = active == TL || active == TR;
                float fx = ((left ? sw - dx : sw + dx)) / sw;
                float fy = ((top ? sh - dy : sh + dy)) / sh;
                float f = (fx + fy) / 2f;
                float minF = Math.max(MIN_SIZE / sw, MIN_SIZE / sh);
                float maxF = Math.min(MAX_SIZE / sw, MAX_SIZE / sh);
                f = Math.max(minF, Math.min(maxF, f));
                w = sw * f;
                h = sh * f;
                x = left ? sx + sw - w : sx;
                y = top ? sy + sh - h : sy;
                break;
            }
            default:
                break;
        }
    }

    private static float clamp(float v) {
        return Math.max(MIN_SIZE, Math.min(MAX_SIZE, v));
    }
}
