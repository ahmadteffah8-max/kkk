package com.pixelstudio.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.OverScroller;

import com.pixelstudio.core.Doc;
import com.pixelstudio.core.Frame;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Horizontal frame strip: thumbnails, index and delay per frame, tap to select, long-press for a menu.
 *
 * It is ONE view that draws only the cells on screen and scrolls itself, instead of one child view per frame:
 *  - tapping a frame selects it and never moves the strip (the old strip re-centred itself on every tap and
 *    jumped to the start or end; it also rebuilt every cell, which reset the scroll position);
 *  - only the visible cells cost anything, so hundreds of frames stay smooth;
 *  - thumbnails are composed straight at thumbnail size and kept in a small LRU cache, so memory stays flat
 *    no matter how big the canvas or how many frames there are;
 *  - the strip always runs left-to-right (frame 1 on the left) in both Arabic and English.
 */
public class TimelineView extends View {

    public interface Listener {
        void onSelect(int index);
        void onMenu(int index, View anchor);
        void onAdd();
    }

    private static final int MAX_CACHED_THUMBS = 48;
    private static final int THUMBS_PER_DRAW = 4;

    private Doc doc;
    private Listener listener;

    private final OverScroller scroller;
    private final int touchSlop, minFling;
    private final int cellW, cellH, gap, pad;
    private float off; // how far the strip is scrolled, in px

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bmpPaint = new Paint();
    private final Paint checker;
    private final RectF rf = new RectF();
    private final RectF dst = new RectF();

    // ---- thumbnails
    private static final class Thumb {
        Bitmap bmp;
        int version;
        int docW, docH;
    }

    private final LinkedHashMap<Frame, Thumb> cache = new LinkedHashMap<Frame, Thumb>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Frame, Thumb> eldest) {
            return size() > MAX_CACHED_THUMBS;
        }
    };
    private int[] thumbBuf = new int[0];

    // ---- touch state
    private float downX, downY, lastX;
    private boolean dragging, longFired, flingWasActive;
    private int downIndex = -1;
    private VelocityTracker velocity;
    private final Runnable longPress = new Runnable() {
        @Override public void run() {
            if (dragging || doc == null || listener == null) return;
            if (downIndex >= 0 && downIndex < doc.frames.size()) {
                longFired = true;
                listener.onMenu(downIndex, TimelineView.this);
            }
        }
    };

    public TimelineView(Context c) {
        super(c);
        scroller = new OverScroller(c);
        ViewConfiguration vc = ViewConfiguration.get(c);
        touchSlop = vc.getScaledTouchSlop();
        minFling = vc.getScaledMinimumFlingVelocity();
        checker = ColorDialog.checkerPaint(c, 5);
        cellW = UI.dp(c, 64);
        cellH = UI.dp(c, 64);
        gap = UI.dp(c, 6);
        pad = UI.dp(c, 6);
        setClickable(true);
    }

    public void bind(Doc d, Listener l) {
        doc = d;
        listener = l;
        refresh();
    }

    // ------------------------------------------------------------------ scrolling model

    private int step() { return cellW + gap; }

    private float contentWidth() {
        int n = doc == null ? 0 : doc.frames.size();
        return pad + (n + 1) * step() - gap + pad; // n frames + the "+" cell
    }

    private float maxOff() {
        return Math.max(0f, contentWidth() - getWidth());
    }

    private float clampOff(float v) {
        return Math.max(0f, Math.min(maxOff(), v));
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        off = clampOff(off);
        if (ow == 0) ensureVisible(doc == null ? 0 : doc.activeFrame, false);
    }

    @Override
    public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            off = clampOff(scroller.getCurrX());
            postInvalidateOnAnimation();
        }
    }

    /**
     * Scrolls the least amount needed so that frame index is fully visible. Does nothing when it already is,
     * so selecting a frame that is on screen never moves the strip.
     */
    public void ensureVisible(int index, boolean animate) {
        if (doc == null || getWidth() <= 0) return;
        float left = pad + index * step();
        float right = left + cellW;
        float target = off;
        if (left - pad < off) target = left - pad;
        else if (right + pad > off + getWidth()) target = right + pad - getWidth();
        target = clampOff(target);
        if (Math.abs(target - off) < 1f) return;
        scroller.abortAnimation();
        if (animate) {
            scroller.startScroll(Math.round(off), 0, Math.round(target - off), 0, 180);
            postInvalidateOnAnimation();
        } else {
            off = target;
            invalidate();
        }
    }

    /** Brings the active frame into view only if it is off screen (used by Play, step buttons, add/undo). */
    public void scrollToActive() {
        if (doc != null) ensureVisible(doc.activeFrame, true);
    }

    /** The frame list changed (add, delete, move, undo...): redraw, keep the scroll position, reveal the active frame. */
    public void refresh() {
        if (doc == null) return;
        off = clampOff(off);
        ensureVisible(doc.activeFrame, true);
        invalidate();
    }

    /** Pixels or the selection changed: redraw only. */
    public void invalidateCells() {
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        removeCallbacks(longPress);
        scroller.abortAnimation();
        cache.clear();
    }

    // ------------------------------------------------------------------ thumbnails

    private Bitmap thumb(Frame f, int[] budget, boolean[] missing) {
        Thumb t = cache.get(f);
        if (t != null && t.version == f.version && t.docW == doc.w && t.docH == doc.h) return t.bmp;
        if (budget[0] <= 0) {
            missing[0] = true;
            return t != null ? t.bmp : null; // a slightly old thumbnail is better than an empty cell
        }
        budget[0]--;
        int box = Math.max(8, cellH - 6);
        float s = Math.min((float) box / doc.w, (float) box / doc.h);
        int tw, th;
        if (s >= 1f && doc.pixelArt) {
            int k = Math.max(1, (int) s);
            tw = doc.w * k;
            th = doc.h * k;
        } else {
            tw = Math.max(1, Math.round(doc.w * s));
            th = Math.max(1, Math.round(doc.h * s));
        }
        if (thumbBuf.length < tw * th) thumbBuf = new int[tw * th];
        doc.composeThumb(f, tw, th, thumbBuf);
        Bitmap b = t != null && t.bmp != null && t.bmp.getWidth() == tw && t.bmp.getHeight() == th && t.bmp.isMutable()
                ? t.bmp : Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888);
        b.setPixels(thumbBuf, 0, tw, 0, 0, tw, th);
        if (t == null) t = new Thumb();
        t.bmp = b;
        t.version = f.version;
        t.docW = doc.w;
        t.docH = doc.h;
        cache.put(f, t);
        return b;
    }

    // ------------------------------------------------------------------ drawing

    @Override
    protected void onDraw(Canvas c) {
        if (doc == null) return;
        int n = doc.frames.size();
        off = clampOff(off);
        int st = step();
        float top = (getHeight() - cellH) / 2f;
        int first = Math.max(0, (int) Math.floor((off - pad) / st));
        int last = Math.min(n, (int) Math.ceil((off + getWidth() - pad) / (float) st));
        int[] budget = {THUMBS_PER_DRAW};
        boolean[] missing = {false};
        for (int i = first; i <= last && i <= n; i++) {
            float left = pad + i * st - off;
            if (i == n) drawAdd(c, left, top);
            else drawCell(c, i, left, top, budget, missing);
        }
        if (missing[0]) postInvalidateOnAnimation(); // more thumbnails to build, a few per frame
    }

    private void drawAdd(Canvas c, float left, float top) {
        float rad = UI.dp(getContext(), 10);
        rf.set(left, top, left + cellW, top + cellH);
        p.setStyle(Paint.Style.FILL);
        p.setColor(UI.SURFACE2);
        c.drawRoundRect(rf, rad, rad, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(UI.dp(getContext(), 1));
        p.setColor(UI.SURFACE3);
        c.drawRoundRect(rf, rad, rad, p);
        float cx = left + cellW / 2f, cy = top + cellH / 2f, r = cellW * 0.18f;
        p.setStrokeWidth(UI.dp(getContext(), 2.5f));
        p.setColor(UI.TEXT);
        c.drawLine(cx - r, cy, cx + r, cy, p);
        c.drawLine(cx, cy - r, cx, cy + r, p);
    }

    private void drawCell(Canvas c, int index, float left, float top, int[] budget, boolean[] missing) {
        Frame f = doc.frames.get(index);
        float w = cellW, h = cellH;
        float rad = UI.dp(getContext(), 8);
        boolean active = index == doc.activeFrame;

        rf.set(left, top, left + w, top + h);
        p.setStyle(Paint.Style.FILL);
        p.setColor(UI.SURFACE2);
        c.drawRoundRect(rf, rad, rad, p);

        Bitmap t = thumb(f, budget, missing);
        if (t != null) {
            c.save();
            c.clipRect(left + 3, top + 3, left + w - 3, top + h - 3);
            float s = Math.min((w - 6) / t.getWidth(), (h - 6) / t.getHeight());
            float tw = t.getWidth() * s, th = t.getHeight() * s;
            dst.set(left + (w - tw) / 2f, top + (h - th) / 2f, left + (w + tw) / 2f, top + (h + th) / 2f);
            c.drawRect(dst, checker);
            bmpPaint.setFilterBitmap(!doc.pixelArt);
            c.drawBitmap(t, null, dst, bmpPaint);
            c.restore();
        }

        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(UI.dp(getContext(), active ? 2.5f : 1f));
        p.setColor(active ? UI.ACCENT : UI.SURFACE3);
        rf.set(left + 1, top + 1, left + w - 1, top + h - 1);
        c.drawRoundRect(rf, rad, rad, p);

        // labels: frame number (top-left) and delay (bottom-right)
        p.setStyle(Paint.Style.FILL);
        p.setTextSize(UI.dp(getContext(), 10));
        float pd = UI.dp(getContext(), 4), lh = UI.dp(getContext(), 14);
        String idx = String.valueOf(index + 1);
        rf.set(left + pd, top + pd, left + pd + p.measureText(idx) + pd * 2, top + pd + lh);
        p.setColor(0xAA000000);
        c.drawRoundRect(rf, 4, 4, p);
        p.setColor(0xFFFFFFFF);
        c.drawText(idx, left + pd * 2, top + pd + UI.dp(getContext(), 11), p);
        String dl = f.delayMs + "ms";
        float dw = p.measureText(dl);
        rf.set(left + w - dw - pd * 3, top + h - pd - lh, left + w - pd, top + h - pd);
        p.setColor(0xAA000000);
        c.drawRoundRect(rf, 4, 4, p);
        p.setColor(0xFFFFFFFF);
        c.drawText(dl, left + w - dw - pd * 2, top + h - pd - UI.dp(getContext(), 3), p);
    }

    // ------------------------------------------------------------------ touch

    /** Index of the cell under x (the "+" cell is frames.size()), or -1 for a gap / outside. */
    private int indexAt(float x, float y) {
        if (doc == null) return -1;
        float top = (getHeight() - cellH) / 2f;
        if (y < top || y > top + cellH) return -1;
        float rel = x + off - pad;
        if (rel < 0) return -1;
        int st = step();
        int i = (int) (rel / st);
        if (rel - i * st > cellW) return -1;
        return i <= doc.frames.size() ? i : -1;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (doc == null) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                flingWasActive = !scroller.isFinished();
                scroller.abortAnimation();
                downX = lastX = e.getX();
                downY = e.getY();
                dragging = false;
                longFired = false;
                downIndex = indexAt(downX, downY);
                if (velocity != null) velocity.recycle();
                velocity = VelocityTracker.obtain();
                velocity.addMovement(e);
                if (downIndex >= 0 && downIndex < doc.frames.size() && !flingWasActive) {
                    postDelayed(longPress, ViewConfiguration.getLongPressTimeout());
                }
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_MOVE: {
                if (velocity != null) velocity.addMovement(e);
                float x = e.getX();
                if (!dragging && Math.abs(x - downX) > touchSlop) {
                    dragging = true;
                    removeCallbacks(longPress);
                }
                if (dragging) {
                    off = clampOff(off - (x - lastX));
                    invalidate();
                }
                lastX = x;
                return true;
            }
            case MotionEvent.ACTION_UP: {
                removeCallbacks(longPress);
                if (velocity != null) velocity.addMovement(e);
                if (longFired) {
                    // the menu already opened
                } else if (dragging) {
                    if (velocity != null) {
                        velocity.computeCurrentVelocity(1000);
                        float vx = velocity.getXVelocity();
                        if (Math.abs(vx) > minFling && maxOff() > 0) {
                            scroller.fling(Math.round(off), 0, Math.round(-vx), 0, 0, Math.round(maxOff()), 0, 0);
                            postInvalidateOnAnimation();
                        }
                    }
                } else if (!flingWasActive && downIndex >= 0 && listener != null) {
                    // a plain tap: select the frame. The strip does not move.
                    if (downIndex == doc.frames.size()) listener.onAdd();
                    else if (downIndex < doc.frames.size()) listener.onSelect(downIndex);
                }
                endTouch();
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(longPress);
                endTouch();
                return true;
            default:
                return true;
        }
    }

    private void endTouch() {
        dragging = false;
        downIndex = -1;
        if (velocity != null) {
            velocity.recycle();
            velocity = null;
        }
    }
}
