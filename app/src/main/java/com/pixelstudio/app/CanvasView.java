package com.pixelstudio.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.LightingColorFilter;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import com.pixelstudio.core.Blend;
import com.pixelstudio.core.Doc;
import com.pixelstudio.core.FloodFill;
import com.pixelstudio.core.Frame;
import com.pixelstudio.core.History;
import com.pixelstudio.core.Patch;
import com.pixelstudio.core.Stroke;

/**
 * The drawing surface: renders the current frame, handles zoom/pan, drawing tools,
 * selection and floating (moving/pasted) content. All edits go through {@link History}.
 */
public class CanvasView extends View {

    public interface Host {
        /** Pixels were committed to the document (autosave / thumbnail refresh). */
        void onEdited();
        void onColorPicked(int argb);
        void onMessage(int resId);
        void onHistoryChanged();
        void onRequestUndo();
        void onRequestRedo();
        void onRequestTool(int tool);
    }

    /** Process-wide clipboard for cut/copy/paste of pixel regions. */
    public static final class Clipboard {
        public static int[] px;
        public static int w, h;
    }

    private Host host;
    private Doc doc;
    private History history;
    private ToolState ts;

    private Bitmap bmp, onionPrev, onionNext;
    private int[] comp;
    private Stroke stroke;

    private float scale = 1f, tx, ty;
    private boolean fitted;
    private boolean playing;

    private final Paint imgPaint = new Paint();
    private final Paint checker;
    private final Paint gridPaint = new Paint();
    private final Paint borderPaint = new Paint();
    private final Paint antsBlack = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint antsWhite = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint onionPaintPrev = new Paint();
    private final Paint onionPaintNext = new Paint();
    private final Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF dst = new RectF();
    private final Rect src = new Rect();
    private float[] gridPts = new float[0];

    // ---- gesture state
    private boolean gesture;
    private int maxPointers;
    private boolean gestureMoved;
    private long gestureStart;
    private float gStartX, gStartY;
    private float lastFx, lastFy, lastSpan;
    private final int touchSlop;

    // ---- single pointer state
    private int mode; // see M_*
    private static final int M_NONE = 0, M_STROKE = 1, M_SHAPE = 2, M_PAN = 3, M_BUCKET = 4, M_PICK = 5,
            M_SELECTING = 6, M_MOVING = 7;
    private int lastIx, lastIy, startIx, startIy;
    private float lastX, lastY;
    private int dirtyX0, dirtyY0, dirtyX1, dirtyY1;
    private boolean cancelledByGesture;

    // ---- selection / floating
    private int[] sel; // x0,y0,x1,y1 (exclusive end)
    private boolean autoSel;
    private boolean floating, floatPersistent, moveStarted;
    private int[] fbuf;
    private int fw, fh, fx, fy, fFrame, fLayer;
    private int[] fSrc;
    private int[] layerBefore;
    private Bitmap fBmp;

    public CanvasView(Context c) {
        super(c);
        touchSlop = ViewConfiguration.get(c).getScaledTouchSlop();
        checker = ColorDialog.checkerPaint(c, 8);
        gridPaint.setStrokeWidth(1f);
        gridPaint.setColor(0x66909090);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setColor(0x88FFFFFF);
        borderPaint.setStrokeWidth(1f);
        float d = UI.dp(c, 1);
        antsBlack.setStyle(Paint.Style.STROKE);
        antsBlack.setColor(0xFF000000);
        antsBlack.setStrokeWidth(Math.max(1.5f, d * 1.5f));
        antsBlack.setPathEffect(new DashPathEffect(new float[]{d * 5, d * 5}, 0));
        antsWhite.setStyle(Paint.Style.STROKE);
        antsWhite.setColor(0xFFFFFFFF);
        antsWhite.setStrokeWidth(Math.max(1.5f, d * 1.5f));
        antsWhite.setPathEffect(new DashPathEffect(new float[]{d * 5, d * 5}, d * 5));
        onionPaintPrev.setAlpha(90);
        onionPaintPrev.setColorFilter(new LightingColorFilter(0xFFFF7070, 0));
        onionPaintNext.setAlpha(90);
        onionPaintNext.setColorFilter(new LightingColorFilter(0xFF70A0FF, 0));
        boxPaint.setColor(0xCC000000);
    }

    public void setHost(Host h) { host = h; }

    public void setDoc(Doc d, History h, ToolState t) {
        doc = d;
        history = h;
        ts = t;
        sel = null;
        floating = false;
        fBmp = null;
        bmp = Bitmap.createBitmap(d.w, d.h, Bitmap.Config.ARGB_8888);
        comp = new int[d.w * d.h];
        stroke = new Stroke(d.w, d.h);
        onionPrev = null;
        onionNext = null;
        fitted = false;
        refreshAll();
        if (getWidth() > 0) fit();
    }

    public float getScale() { return scale; }
    public boolean hasSelection() { return sel != null; }
    public boolean isFloating() { return floating; }
    public void setPlaying(boolean p) {
        playing = p;
        if (!p && doc != null) updateOnion();
        invalidate();
    }

    // ------------------------------------------------------------------ view transform

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        // refit on first layout and on rotation (width change); not when only the height changes (panels toggled)
        if (doc != null && (!fitted || ow != w)) fit();
    }

    public void fit() {
        if (doc == null || getWidth() == 0) return;
        float m = UI.dp(getContext(), 12);
        float s = Math.min((getWidth() - 2 * m) / doc.w, (getHeight() - 2 * m) / doc.h);
        if (s >= 1f) s = (float) Math.floor(s);
        if (s <= 0) s = 0.1f;
        scale = s;
        tx = (getWidth() - doc.w * scale) / 2f;
        ty = (getHeight() - doc.h * scale) / 2f;
        fitted = true;
        invalidate();
    }

    public void zoomBy(float factor) {
        zoomAbout(factor, getWidth() / 2f, getHeight() / 2f);
    }

    private void zoomAbout(float factor, float fx, float fy) {
        float max = Math.max(64f, 4096f / Math.min(doc.w, doc.h));
        float ns = Math.max(0.05f, Math.min(max, scale * factor));
        float ix = (fx - tx) / scale, iy = (fy - ty) / scale;
        scale = ns;
        tx = fx - ix * ns;
        ty = fy - iy * ns;
        clampPan();
        invalidate();
    }

    private void clampPan() {
        float m = UI.dp(getContext(), 56);
        float cw = doc.w * scale, ch = doc.h * scale;
        tx = Math.max(m - cw, Math.min(getWidth() - m, tx));
        ty = Math.max(m - ch, Math.min(getHeight() - m, ty));
    }

    private int toIx(float x) { return (int) Math.floor((x - tx) / scale); }
    private int toIy(float y) { return (int) Math.floor((y - ty) / scale); }
    private int clampX(int x) { return Math.max(0, Math.min(doc.w - 1, x)); }
    private int clampY(int y) { return Math.max(0, Math.min(doc.h - 1, y)); }

    // ------------------------------------------------------------------ compositing

    /** Recomposes the whole active frame and the onion skin bitmaps. */
    public void refreshAll() {
        if (doc == null) return;
        doc.clampActive();
        doc.composeRegion(doc.frame(), 0, 0, doc.w, doc.h, comp);
        bmp.setPixels(comp, 0, doc.w, 0, 0, doc.w, doc.h);
        if (!playing) updateOnion();
        invalidate();
    }

    public void refreshRegion(int x, int y, int w, int h) {
        int x0 = Math.max(0, x), y0 = Math.max(0, y);
        int x1 = Math.min(doc.w, x + w), y1 = Math.min(doc.h, y + h);
        if (x1 <= x0 || y1 <= y0) return;
        doc.composeRegion(doc.frame(), x0, y0, x1 - x0, y1 - y0, comp);
        bmp.setPixels(comp, y0 * doc.w + x0, doc.w, x0, y0, x1 - x0, y1 - y0);
        invalidate();
    }

    /** Rebuilds the previous/next frame bitmaps used for onion skinning. */
    public void updateOnion() {
        if (doc == null) return;
        onionPrev = onionBitmap(doc.activeFrame - 1, onionPrev);
        onionNext = onionBitmap(doc.activeFrame + 1, onionNext);
    }

    private Bitmap onionBitmap(int index, Bitmap reuse) {
        if (!ts.onion || index < 0 || index >= doc.frames.size() || doc.pixelCount() > 4096 * 4096) return null;
        int[] px = doc.compose(doc.frames.get(index));
        Bitmap b = (reuse != null && reuse.getWidth() == doc.w && reuse.getHeight() == doc.h && reuse.isMutable())
                ? reuse : Bitmap.createBitmap(doc.w, doc.h, Bitmap.Config.ARGB_8888);
        b.setPixels(px, 0, doc.w, 0, 0, doc.w, doc.h);
        return b;
    }

    /** Current composited frame as a bitmap copy. */
    public Bitmap snapshotBitmap() {
        return bmp.copy(Bitmap.Config.ARGB_8888, false);
    }

    // ------------------------------------------------------------------ drawing

    @Override
    protected void onDraw(Canvas c) {
        if (doc == null) return;
        float L = tx, T = ty, R = tx + doc.w * scale, B = ty + doc.h * scale;
        dst.set(L, T, R, B);
        c.save();
        c.clipRect(Math.max(0, L), Math.max(0, T), Math.min(getWidth(), R), Math.min(getHeight(), B));
        c.drawRect(dst, checker);
        boolean filter = scale < 1f || (!doc.pixelArt && scale < 2f);
        imgPaint.setFilterBitmap(filter);
        c.drawBitmap(bmp, null, dst, imgPaint);
        if (ts.onion && !playing) {
            if (onionPrev != null) c.drawBitmap(onionPrev, null, dst, onionPaintPrev);
            if (onionNext != null) c.drawBitmap(onionNext, null, dst, onionPaintNext);
        }
        if (floating && fBmp != null) {
            RectF fr = new RectF(L + fx * scale, T + fy * scale, L + (fx + fw) * scale, T + (fy + fh) * scale);
            imgPaint.setFilterBitmap(false);
            c.drawBitmap(fBmp, null, fr, imgPaint);
        }
        c.restore();

        if (ts.grid && !playing) drawGrid(c);
        c.drawRect(L - 0.5f, T - 0.5f, R + 0.5f, B + 0.5f, borderPaint);

        if (sel != null) {
            RectF s = new RectF(L + sel[0] * scale, T + sel[1] * scale, L + sel[2] * scale, T + sel[3] * scale);
            c.drawRect(s, antsBlack);
            c.drawRect(s, antsWhite);
        }
        if (ts.preview) drawPreview(c);
    }

    private void drawGrid(Canvas c) {
        int step = Math.max(1, ts.gridStep);
        float cell = step * scale;
        if (cell < 5f) return;
        int i0x = Math.max(0, (int) Math.floor(-tx / cell));
        int i1x = Math.min(doc.w / step, (int) Math.ceil((getWidth() - tx) / cell));
        int i0y = Math.max(0, (int) Math.floor(-ty / cell));
        int i1y = Math.min(doc.h / step, (int) Math.ceil((getHeight() - ty) / cell));
        int nx = Math.max(0, i1x - i0x + 1), ny = Math.max(0, i1y - i0y + 1);
        int need = (nx + ny) * 4;
        if (gridPts.length < need) gridPts = new float[need];
        float top = Math.max(ty, 0), bottom = Math.min(ty + doc.h * scale, getHeight());
        float left = Math.max(tx, 0), right = Math.min(tx + doc.w * scale, getWidth());
        int k = 0;
        for (int i = i0x; i <= i1x; i++) {
            float x = tx + i * cell;
            if (x < left - 1 || x > right + 1) continue;
            gridPts[k++] = x; gridPts[k++] = top; gridPts[k++] = x; gridPts[k++] = bottom;
        }
        for (int j = i0y; j <= i1y; j++) {
            float y = ty + j * cell;
            if (y < top - 1 || y > bottom + 1) continue;
            gridPts[k++] = left; gridPts[k++] = y; gridPts[k++] = right; gridPts[k++] = y;
        }
        c.drawLines(gridPts, 0, k, gridPaint);
    }

    private void drawPreview(Canvas c) {
        float box = UI.dp(getContext(), 120), pad = UI.dp(getContext(), 8);
        float pw = Math.min(box, doc.w), ph = Math.min(box, doc.h);
        float s = Math.min(1f, Math.min(box / doc.w, box / doc.h));
        pw = doc.w * s;
        ph = doc.h * s;
        float x = pad, y = getHeight() - pad - ph;
        c.drawRect(x - 3, y - 3, x + pw + 3, y + ph + 3, boxPaint);
        dst.set(x, y, x + pw, y + ph);
        c.drawRect(dst, checker);
        imgPaint.setFilterBitmap(s < 1f);
        c.drawBitmap(bmp, null, dst, imgPaint);
    }

    // ------------------------------------------------------------------ touch

    private boolean canDraw() {
        if (doc.layers.get(doc.activeLayer).visible) return true;
        if (host != null) host.onMessage(R.string.layer_hidden);
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (doc == null) return false;
        int a = e.getActionMasked();
        switch (a) {
            case MotionEvent.ACTION_DOWN:
                gesture = false;
                gestureMoved = false;
                maxPointers = 1;
                gestureStart = e.getEventTime();
                gStartX = e.getX();
                gStartY = e.getY();
                cancelledByGesture = false;
                singleDown(e);
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                maxPointers = Math.max(maxPointers, e.getPointerCount());
                if (!gesture) {
                    cancelCurrent();
                    gesture = true;
                }
                resetGestureBase(e, -1);
                break;
            case MotionEvent.ACTION_MOVE:
                if (gesture) gestureMove(e);
                else singleMove(e);
                break;
            case MotionEvent.ACTION_POINTER_UP:
                resetGestureBase(e, e.getActionIndex());
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (gesture) {
                    if (a == MotionEvent.ACTION_UP && !gestureMoved && e.getEventTime() - gestureStart < 350 && host != null) {
                        if (maxPointers == 2) host.onRequestUndo();
                        else if (maxPointers == 3) host.onRequestRedo();
                    }
                    gesture = false;
                } else {
                    singleUp(e, a == MotionEvent.ACTION_CANCEL);
                }
                break;
            default:
                break;
        }
        return true;
    }

    private void resetGestureBase(MotionEvent e, int skip) {
        float sx = 0, sy = 0;
        int n = 0;
        for (int i = 0; i < e.getPointerCount(); i++) {
            if (i == skip) continue;
            sx += e.getX(i);
            sy += e.getY(i);
            n++;
        }
        if (n == 0) return;
        lastFx = sx / n;
        lastFy = sy / n;
        float span = 0;
        for (int i = 0; i < e.getPointerCount(); i++) {
            if (i == skip) continue;
            span += (float) Math.hypot(e.getX(i) - lastFx, e.getY(i) - lastFy);
        }
        lastSpan = n >= 2 ? span / n : 0f;
    }

    private void gestureMove(MotionEvent e) {
        float sx = 0, sy = 0;
        int n = e.getPointerCount();
        for (int i = 0; i < n; i++) { sx += e.getX(i); sy += e.getY(i); }
        float fx = sx / n, fy = sy / n;
        float span = 0;
        for (int i = 0; i < n; i++) span += (float) Math.hypot(e.getX(i) - fx, e.getY(i) - fy);
        span = n >= 2 ? span / n : 0f;
        if (Math.hypot(fx - gStartX, fy - gStartY) > touchSlop * 2 || Math.abs(span - lastSpan) > touchSlop) gestureMoved = true;
        float ix = (lastFx - tx) / scale, iy = (lastFy - ty) / scale;
        if (lastSpan > 0 && span > 0) {
            float max = Math.max(64f, 4096f / Math.min(doc.w, doc.h));
            scale = Math.max(0.05f, Math.min(max, scale * span / lastSpan));
        }
        tx = fx - ix * scale;
        ty = fy - iy * scale;
        clampPan();
        lastFx = fx;
        lastFy = fy;
        lastSpan = span;
        invalidate();
    }

    /** A second finger arrived: discard what the first finger started. */
    private void cancelCurrent() {
        if (mode == M_STROKE || mode == M_SHAPE) {
            int[] b = stroke.bounds();
            stroke.reset();
            if (b != null) refreshRegion(b[0], b[1], b[2], b[3]);
        }
        if (mode == M_MOVING && floating && !floatPersistent) {
            // drop the lifted content where it currently is
            commitFloating();
        }
        mode = M_NONE;
    }

    private void singleDown(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        int ix = toIx(x), iy = toIy(y);
        lastIx = ix;
        lastIy = iy;
        lastX = x;
        lastY = y;
        mode = M_NONE;
        int tool = ts.tool;
        if (playing) {
            mode = M_PAN; // while playing only navigation is allowed
            return;
        }
        if (floating && !(tool == ToolState.SELECT || tool == ToolState.MOVE)) commitFloating();
        switch (tool) {
            case ToolState.HAND:
                mode = M_PAN;
                break;
            case ToolState.PICKER:
                mode = M_PICK;
                pick(ix, iy);
                break;
            case ToolState.BUCKET:
                mode = M_BUCKET;
                break;
            case ToolState.PENCIL:
            case ToolState.BRUSH:
            case ToolState.ERASER:
                if (!canDraw()) return;
                beginStroke();
                mode = M_STROKE;
                resetDirty(ix, iy);
                stroke.stamp(ix, iy);
                flushDirty(ix, iy);
                break;
            case ToolState.LINE:
            case ToolState.RECT:
            case ToolState.ELLIPSE:
                if (!canDraw()) return;
                beginStroke();
                mode = M_SHAPE;
                startIx = ix;
                startIy = iy;
                drawShape(ix, iy);
                break;
            case ToolState.SELECT:
                if (sel != null && ix >= sel[0] && ix < sel[2] && iy >= sel[1] && iy < sel[3]) {
                    if (!canDraw()) return;
                    mode = M_MOVING;
                    moveStarted = false;
                } else {
                    if (floating) commitFloating();
                    sel = null;
                    mode = M_SELECTING;
                    startIx = clampX(ix);
                    startIy = clampY(iy);
                    sel = new int[]{startIx, startIy, startIx + 1, startIy + 1};
                    invalidate();
                }
                break;
            case ToolState.MOVE:
                if (!canDraw()) return;
                if (sel == null) {
                    sel = new int[]{0, 0, doc.w, doc.h};
                    autoSel = true;
                }
                mode = M_MOVING;
                moveStarted = false;
                break;
            default:
                break;
        }
    }

    private void singleMove(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        switch (mode) {
            case M_PAN:
                tx += x - lastX;
                ty += y - lastY;
                lastX = x;
                lastY = y;
                clampPan();
                invalidate();
                break;
            case M_STROKE: {
                int n = e.getHistorySize();
                int ix = lastIx, iy = lastIy;
                resetDirty(lastIx, lastIy);
                for (int i = 0; i < n; i++) {
                    ix = toIx(e.getHistoricalX(i));
                    iy = toIy(e.getHistoricalY(i));
                    strokeTo(ix, iy);
                }
                ix = toIx(x);
                iy = toIy(y);
                strokeTo(ix, iy);
                flushDirty(ix, iy);
                break;
            }
            case M_SHAPE: {
                int ix = toIx(x), iy = toIy(y);
                if (ix == lastIx && iy == lastIy) break;
                lastIx = ix;
                lastIy = iy;
                int[] old = stroke.bounds();
                stroke.reset();
                drawShape(ix, iy);
                int[] nb = stroke.bounds();
                unionRefresh(old, nb);
                break;
            }
            case M_PICK:
                pick(toIx(x), toIy(y));
                break;
            case M_SELECTING: {
                int ix = clampX(toIx(x)), iy = clampY(toIy(y));
                sel = new int[]{Math.min(startIx, ix), Math.min(startIy, iy), Math.max(startIx, ix) + 1, Math.max(startIy, iy) + 1};
                invalidate();
                break;
            }
            case M_MOVING: {
                int ix = toIx(x), iy = toIy(y);
                int dx = ix - lastIx, dy = iy - lastIy;
                if (dx == 0 && dy == 0) break;
                if (!floating) liftSelection();
                moveFloating(dx, dy);
                lastIx = ix;
                lastIy = iy;
                break;
            }
            default:
                break;
        }
    }

    private void singleUp(MotionEvent e, boolean cancel) {
        int m = mode;
        mode = M_NONE;
        switch (m) {
            case M_STROKE:
            case M_SHAPE: {
                if (cancel) {
                    int[] b = stroke.bounds();
                    stroke.reset();
                    if (b != null) refreshRegion(b[0], b[1], b[2], b[3]);
                    break;
                }
                Patch p = stroke.finish();
                if (p != null) {
                    history.pushPatch(doc, doc.activeFrame, doc.activeLayer, p);
                    if (host != null) {
                        host.onHistoryChanged();
                        host.onEdited();
                    }
                }
                break;
            }
            case M_BUCKET:
                if (!cancel) {
                    if (canDraw()) fillAt(toIx(e.getX()), toIy(e.getY()));
                }
                break;
            case M_SELECTING:
                if (sel != null && sel[2] - sel[0] == 1 && sel[3] - sel[1] == 1 && !moved(e)) sel = null;
                invalidate();
                break;
            case M_MOVING:
                if (floating && !floatPersistent) commitFloating();
                if (autoSel && !floating) {
                    sel = null;
                    autoSel = false;
                    invalidate();
                }
                break;
            default:
                break;
        }
    }

    private boolean moved(MotionEvent e) {
        return Math.hypot(e.getX() - gStartX, e.getY() - gStartY) > touchSlop;
    }

    // ------------------------------------------------------------------ strokes

    private void beginStroke() {
        int tool = ts.tool;
        boolean erase = tool == ToolState.ERASER;
        boolean aa, square;
        if (tool == ToolState.BRUSH) {
            aa = true;
            square = false;
        } else if (tool == ToolState.PENCIL) {
            aa = false;
            square = doc.pixelArt;
        } else if (tool == ToolState.ERASER) {
            aa = !doc.pixelArt;
            square = doc.pixelArt;
        } else {
            aa = !doc.pixelArt;
            square = doc.pixelArt;
        }
        stroke.begin(doc.layerPixels(), ts.color, ts.size(), erase, aa, square, selClip());
    }

    private int[] selClip() {
        return (sel != null && !floating) ? sel : null;
    }

    private void drawShape(int ix, int iy) {
        switch (ts.tool) {
            case ToolState.LINE:
                stroke.line(startIx, startIy, ix, iy);
                break;
            case ToolState.RECT:
                stroke.rect(startIx, startIy, ix, iy, ts.fillShape);
                break;
            default:
                stroke.ellipse(startIx, startIy, ix, iy, ts.fillShape);
                break;
        }
        int[] b = stroke.bounds();
        if (b != null) refreshRegion(b[0], b[1], b[2], b[3]);
    }

    private void strokeTo(int ix, int iy) {
        if (ix == lastIx && iy == lastIy) return;
        stroke.line(lastIx, lastIy, ix, iy);
        dirtyX0 = Math.min(dirtyX0, Math.min(lastIx, ix));
        dirtyY0 = Math.min(dirtyY0, Math.min(lastIy, iy));
        dirtyX1 = Math.max(dirtyX1, Math.max(lastIx, ix));
        dirtyY1 = Math.max(dirtyY1, Math.max(lastIy, iy));
        lastIx = ix;
        lastIy = iy;
    }

    private void resetDirty(int ix, int iy) {
        dirtyX0 = dirtyX1 = ix;
        dirtyY0 = dirtyY1 = iy;
    }

    private void flushDirty(int ix, int iy) {
        dirtyX0 = Math.min(dirtyX0, ix);
        dirtyY0 = Math.min(dirtyY0, iy);
        dirtyX1 = Math.max(dirtyX1, ix);
        dirtyY1 = Math.max(dirtyY1, iy);
        int r = ts.size() / 2 + 2;
        refreshRegion(dirtyX0 - r, dirtyY0 - r, dirtyX1 - dirtyX0 + 2 * r + 1, dirtyY1 - dirtyY0 + 2 * r + 1);
    }

    private void unionRefresh(int[] a, int[] b) {
        if (a == null && b == null) return;
        int x0, y0, x1, y1;
        if (a == null) { x0 = b[0]; y0 = b[1]; x1 = b[0] + b[2]; y1 = b[1] + b[3]; }
        else if (b == null) { x0 = a[0]; y0 = a[1]; x1 = a[0] + a[2]; y1 = a[1] + a[3]; }
        else {
            x0 = Math.min(a[0], b[0]); y0 = Math.min(a[1], b[1]);
            x1 = Math.max(a[0] + a[2], b[0] + b[2]); y1 = Math.max(a[1] + a[3], b[1] + b[3]);
        }
        refreshRegion(x0, y0, x1 - x0, y1 - y0);
    }

    private void pick(int ix, int iy) {
        if (ix < 0 || iy < 0 || ix >= doc.w || iy >= doc.h) return;
        int c = comp[iy * doc.w + ix];
        if ((c >>> 24) == 0) c = 0x00000000;
        if (host != null) host.onColorPicked(c);
    }

    private void fillAt(int ix, int iy) {
        if (ix < 0 || iy < 0 || ix >= doc.w || iy >= doc.h) return;
        int[] layer = doc.layerPixels();
        int[] before = layer.clone();
        int[] r = FloodFill.fill(layer, doc.w, doc.h, ix, iy, ts.color, ts.tolerance, selClip());
        if (r == null) return;
        Patch p = Patch.fromDiff(before, layer, doc.w, r[0], r[1], r[2], r[3]);
        if (p == null) return;
        history.pushPatch(doc, doc.activeFrame, doc.activeLayer, p);
        refreshRegion(r[0], r[1], r[2], r[3]);
        if (host != null) {
            host.onHistoryChanged();
            host.onEdited();
        }
    }

    // ------------------------------------------------------------------ selection & floating

    public void selectAll() {
        commitFloating();
        sel = new int[]{0, 0, doc.w, doc.h};
        autoSel = false;
        invalidate();
    }

    public void deselect() {
        commitFloating();
        sel = null;
        autoSel = false;
        invalidate();
    }

    private void liftSelection() {
        int[] layer = doc.layerPixels();
        fw = sel[2] - sel[0];
        fh = sel[3] - sel[1];
        fbuf = new int[fw * fh];
        for (int y = 0; y < fh; y++) {
            System.arraycopy(layer, (sel[1] + y) * doc.w + sel[0], fbuf, y * fw, fw);
        }
        layerBefore = layer.clone();
        for (int y = 0; y < fh; y++) {
            java.util.Arrays.fill(layer, (sel[1] + y) * doc.w + sel[0], (sel[1] + y) * doc.w + sel[2], 0);
        }
        fx = sel[0];
        fy = sel[1];
        fSrc = sel.clone();
        fFrame = doc.activeFrame;
        fLayer = doc.activeLayer;
        floating = true;
        floatPersistent = false;
        makeFloatBitmap();
        refreshRegion(fSrc[0], fSrc[1], fw, fh);
    }

    private void makeFloatBitmap() {
        fBmp = Bitmap.createBitmap(fw, fh, Bitmap.Config.ARGB_8888);
        fBmp.setPixels(fbuf, 0, fw, 0, 0, fw, fh);
    }

    private void moveFloating(int dx, int dy) {
        fx += dx;
        fy += dy;
        sel = new int[]{fx, fy, fx + fw, fy + fh};
        invalidate();
    }

    /** Drops floating content into the layer and records one undo step. */
    public void commitFloating() {
        if (!floating) return;
        floating = false;
        floatPersistent = false;
        Frame f = doc.frames.get(Math.min(fFrame, doc.frames.size() - 1));
        int[] layer = f.px[Math.min(fLayer, f.px.length - 1)];
        for (int y = 0; y < fh; y++) {
            int Y = fy + y;
            if (Y < 0 || Y >= doc.h) continue;
            for (int x = 0; x < fw; x++) {
                int X = fx + x;
                if (X < 0 || X >= doc.w) continue;
                int s = fbuf[y * fw + x];
                if ((s >>> 24) == 0) continue;
                layer[Y * doc.w + X] = Blend.over(layer[Y * doc.w + X], s);
            }
        }
        int ux0 = Math.max(0, fx), uy0 = Math.max(0, fy);
        int ux1 = Math.min(doc.w, fx + fw), uy1 = Math.min(doc.h, fy + fh);
        if (fSrc != null) {
            ux0 = Math.min(ux0, fSrc[0]); uy0 = Math.min(uy0, fSrc[1]);
            ux1 = Math.max(ux1, fSrc[2]); uy1 = Math.max(uy1, fSrc[3]);
        }
        if (ux1 > ux0 && uy1 > uy0) {
            Patch p = Patch.fromDiff(layerBefore, layer, doc.w, ux0, uy0, ux1 - ux0, uy1 - uy0);
            history.pushPatch(doc, fFrame, fLayer, p);
        }
        layerBefore = null;
        fbuf = null;
        fBmp = null;
        fSrc = null;
        if (sel != null) {
            sel = new int[]{Math.max(0, sel[0]), Math.max(0, sel[1]), Math.min(doc.w, sel[2]), Math.min(doc.h, sel[3])};
            if (sel[2] <= sel[0] || sel[3] <= sel[1]) sel = null;
        }
        if (fFrame == doc.activeFrame && ux1 > ux0) refreshRegion(ux0, uy0, ux1 - ux0, uy1 - uy0);
        invalidate();
        if (host != null) {
            host.onHistoryChanged();
            host.onEdited();
        }
    }

    private void startPasteFloat(int[] px, int w, int h) {
        if (!doc.layers.get(doc.activeLayer).visible) {
            if (host != null) host.onMessage(R.string.layer_hidden);
            return;
        }
        commitFloating();
        fw = w;
        fh = h;
        fbuf = px.clone();
        layerBefore = doc.layerPixels().clone();
        fSrc = null;
        float cx = (getWidth() / 2f - tx) / scale, cy = (getHeight() / 2f - ty) / scale;
        fx = Math.max(0, Math.min(doc.w - w, Math.round(cx - w / 2f)));
        fy = Math.max(0, Math.min(doc.h - h, Math.round(cy - h / 2f)));
        if (w > doc.w) fx = 0;
        if (h > doc.h) fy = 0;
        fFrame = doc.activeFrame;
        fLayer = doc.activeLayer;
        floating = true;
        floatPersistent = true;
        makeFloatBitmap();
        sel = new int[]{fx, fy, fx + fw, fy + fh};
        autoSel = false;
        if (host != null) host.onRequestTool(ToolState.MOVE);
        invalidate();
    }

    /** Pastes an external image (already scaled to fit) as floating content. */
    public void pasteImage(int[] px, int w, int h) {
        startPasteFloat(px, w, h);
    }

    public boolean copySelection() {
        commitFloating();
        if (sel == null) {
            if (host != null) host.onMessage(R.string.no_selection);
            return false;
        }
        int w = sel[2] - sel[0], h = sel[3] - sel[1];
        int[] layer = doc.layerPixels();
        int[] buf = new int[w * h];
        for (int y = 0; y < h; y++) System.arraycopy(layer, (sel[1] + y) * doc.w + sel[0], buf, y * w, w);
        Clipboard.px = buf;
        Clipboard.w = w;
        Clipboard.h = h;
        return true;
    }

    public void cutSelection() {
        if (copySelection()) deleteSelection();
    }

    public void deleteSelection() {
        commitFloating();
        if (sel == null) {
            if (host != null) host.onMessage(R.string.no_selection);
            return;
        }
        if (!canDraw()) return;
        int w = sel[2] - sel[0], h = sel[3] - sel[1];
        int[] layer = doc.layerPixels();
        int[] before = new int[w * h];
        boolean any = false;
        for (int y = 0; y < h; y++) {
            System.arraycopy(layer, (sel[1] + y) * doc.w + sel[0], before, y * w, w);
            java.util.Arrays.fill(layer, (sel[1] + y) * doc.w + sel[0], (sel[1] + y) * doc.w + sel[2], 0);
        }
        for (int v : before) if (v != 0) { any = true; break; }
        if (!any) return;
        history.pushPatch(doc, doc.activeFrame, doc.activeLayer, new Patch(sel[0], sel[1], w, h, before, new int[w * h]));
        refreshRegion(sel[0], sel[1], w, h);
        if (host != null) {
            host.onHistoryChanged();
            host.onEdited();
        }
    }

    public void pasteClipboard() {
        if (Clipboard.px == null) {
            if (host != null) host.onMessage(R.string.clipboard_empty);
            return;
        }
        startPasteFloat(Clipboard.px, Clipboard.w, Clipboard.h);
    }
}
