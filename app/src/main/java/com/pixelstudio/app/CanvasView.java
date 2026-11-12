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
import com.pixelstudio.core.OverlayBox;
import com.pixelstudio.core.Patch;
import com.pixelstudio.core.Raster;
import com.pixelstudio.core.Stroke;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

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
        /**
         * The picture that was being placed is done: job holds where and how big (null = the person dropped it).
         * The host puts it into the document and then moves on to the next picked picture.
         */
        void onOverlayFinished(OverlayJob job);
    }

    /** What the person decided in the image-placement frame. Positions are canvas pixels (can be negative). */
    public static final class OverlayJob {
        public ImportUtil.Media media;
        public int x, y, w, h;
        /** Smooth resampling (photos) instead of hard pixels (pixel art enlarged). */
        public boolean smooth;
        /** Goes to a new layer instead of the active layer (animations and multi-picture imports). */
        public boolean newLayer;
    }

    /** Receives the result of the eyedropper (the color palette is hidden while it runs). */
    public interface EyedropListener {
        void onPicked(int argb);
        void onCancel();
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
            M_SELECTING = 6, M_MOVING = 7, M_OVL = 8, M_OVL_TAP = 9, M_EYE = 10, M_WAND = 11;
    private int lastIx, lastIy, startIx, startIy;
    private float lastX, lastY;
    private int dirtyX0, dirtyY0, dirtyX1, dirtyY1;
    private boolean cancelledByGesture;

    // ---- selection / floating
    private int[] sel; // x0,y0,x1,y1 (exclusive end)
    /** Smart (magic wand) selection: mask local to the selection box; valid only while maskSel == sel. */
    private boolean[] selMask;
    private int[] maskSel;
    private Bitmap maskBmp;
    private boolean autoSel;
    private boolean floating, floatPersistent, moveStarted;
    private int[] fbuf;
    private int fw, fh, fx, fy, fFrame, fLayer;
    private int[] fSrc;
    private int[] layerBefore;
    private Bitmap fBmp;

    // ---- image placement frame (move / resize with handles, tap outside to place)
    private ImportUtil.Media ov;
    private OverlayBox ovBox;
    private boolean ovNewLayer;
    private int ovFrame;
    private Bitmap ovBmp;
    private float ovGrabX, ovGrabY;
    private final Runnable ovTick = new Runnable() {
        @Override public void run() {
            if (ov == null || !ov.animated() || ovBmp == null) return;
            ovFrame = (ovFrame + 1) % ov.frames.size();
            ovBmp.setPixels(ov.frames.get(ovFrame), 0, ov.w, 0, 0, ov.w, ov.h);
            invalidate();
            postDelayed(this, Math.max(30, ov.delays.get(ovFrame)));
        }
    };
    private final Paint handleFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handleRing = new Paint(Paint.ANTI_ALIAS_FLAG);

    // ---- eyedropper (touch the picture to take a color)
    private EyedropListener eyeListener;
    private boolean eyeShow;
    private int eyeIx, eyeIy, eyeColor;
    private float eyeSx, eyeSy;
    private final Paint eyeText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint swatchPaint = new Paint();
    private final Rect loupeSrc = new Rect();

    // ---- playback: the next frame is composed on a worker thread while the current one is shown
    private int[] spare;
    private Frame spareFrame;
    private int spareVersion;
    private volatile int preState; // 0 idle, 1 working, 2 ready
    private volatile int[] preBuf; // identifies the buffer the current worker fills
    private ExecutorService pool;

    // ---- reused drawing objects (nothing is allocated while drawing)
    private final RectF tmpR = new RectF();

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
        handleFill.setStyle(Paint.Style.FILL);
        handleFill.setColor(0xFFFFFFFF);
        handleRing.setStyle(Paint.Style.STROKE);
        handleRing.setColor(UI.ACCENT);
        handleRing.setStrokeWidth(UI.dp(c, 2));
        eyeText.setTextSize(UI.dp(c, 13));
        eyeText.setColor(0xFFFFFFFF);
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
        spare = null;
        spareFrame = null;
        preBuf = null;
        preState = 0;
        endOverlay();
        eyeListener = null;
        eyeShow = false;
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
        Frame f = doc.frame();
        boolean fromSpare = false;
        if (playing && preState == 2 && spareFrame == f && spareVersion == f.version && spare != null && spare.length == comp.length) {
            int[] t = comp; // the worker already composed this frame: just swap buffers
            comp = spare;
            spare = t;
            fromSpare = true;
        }
        if (!fromSpare) doc.composeRegion(f, 0, 0, doc.w, doc.h, comp);
        if (preState == 2) preState = 0; // consumed or stale
        bmp.setPixels(comp, 0, doc.w, 0, 0, doc.w, doc.h);
        if (!playing) updateOnion();
        else prefetchNext();
        invalidate();
    }

    private void prefetchNext() {
        int n = doc.frames.size();
        if (n < 2 || preState != 0) return;
        final Frame nf = doc.frames.get((doc.activeFrame + 1) % n);
        if (spare == null || spare.length != comp.length) spare = new int[comp.length];
        spareFrame = nf;
        spareVersion = nf.version;
        preState = 1;
        final int[] buf = spare;
        preBuf = buf;
        final Doc d = doc;
        if (pool == null) {
            pool = Executors.newSingleThreadExecutor(new ThreadFactory() {
                @Override public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "frame-prefetch");
                    t.setDaemon(true);
                    return t;
                }
            });
        }
        try {
            pool.execute(new Runnable() {
                @Override public void run() {
                    int result = 0;
                    try {
                        d.composeRegion(nf, 0, 0, d.w, d.h, buf);
                        result = 2;
                    } catch (Throwable ignored) {
                        // the document changed under us: the UI thread simply composes this frame itself
                    }
                    if (preBuf == buf) preState = result; // ignore a worker that was superseded
                }
            });
        } catch (RuntimeException e) {
            preState = 0;
        }
    }

    /** Stops background work (call when the screen is closed). */
    public void release() {
        removeCallbacks(ovTick);
        if (pool != null) {
            pool.shutdownNow();
            pool = null;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        removeCallbacks(ovTick);
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
            tmpR.set(L + fx * scale, T + fy * scale, L + (fx + fw) * scale, T + (fy + fh) * scale);
            imgPaint.setFilterBitmap(false);
            c.drawBitmap(fBmp, null, tmpR, imgPaint);
        }
        if (ov != null && ovBmp != null) {
            tmpR.set(L + ovBox.x * scale, T + ovBox.y * scale, L + (ovBox.x + ovBox.w) * scale, T + (ovBox.y + ovBox.h) * scale);
            imgPaint.setFilterBitmap(ovSmooth());
            c.drawBitmap(ovBmp, null, tmpR, imgPaint);
        }
        c.restore();

        if (ts.grid && !playing) drawGrid(c);
        c.drawRect(L - 0.5f, T - 0.5f, R + 0.5f, B + 0.5f, borderPaint);

        if (sel != null) {
            tmpR.set(L + sel[0] * scale, T + sel[1] * scale, L + sel[2] * scale, T + sel[3] * scale);
            if (activeMask() != null && maskBmp != null) {
                imgPaint.setFilterBitmap(false);
                c.drawBitmap(maskBmp, null, tmpR, imgPaint);
            }
            c.drawRect(tmpR, antsBlack);
            c.drawRect(tmpR, antsWhite);
        }
        if (ov != null) drawOverlayFrame(c);
        if (ts.preview) drawPreview(c);
        if (eyeListener != null) drawEyedrop(c);
    }

    // ------------------------------------------------------------------ image placement frame

    private boolean ovSmooth() {
        return !doc.pixelArt || (ovBox.w < ov.w && ovBox.h < ov.h);
    }

    private void drawOverlayFrame(Canvas c) {
        tmpR.set(tx + ovBox.x * scale, ty + ovBox.y * scale, tx + (ovBox.x + ovBox.w) * scale, ty + (ovBox.y + ovBox.h) * scale);
        c.drawRect(tmpR, antsBlack);
        c.drawRect(tmpR, antsWhite);
        float big = UI.dp(getContext(), 8), small = UI.dp(getContext(), 6);
        for (int hnd = 0; hnd < 8; hnd++) {
            boolean corner = hnd == OverlayBox.TL || hnd == OverlayBox.TR || hnd == OverlayBox.BR || hnd == OverlayBox.BL;
            float r = corner ? big : small;
            float x = tx + ovBox.handleX(hnd) * scale, y = ty + ovBox.handleY(hnd) * scale;
            c.drawCircle(x, y, r, handleFill);
            c.drawCircle(x, y, r, handleRing);
        }
    }

    /**
     * Shows a picture on the canvas inside a frame with handles. Drag inside to move, drag a corner to resize
     * (keeps proportions) or an edge handle to stretch; tap an empty spot (or press Place) to drop it into the
     * document. An animated GIF / video keeps playing in the frame and is placed with all its frames.
     * The media must already be scaled to fit the canvas.
     */
    public boolean beginOverlay(ImportUtil.Media m, boolean newLayer) {
        if (doc == null || m == null || m.frames.isEmpty()) return false;
        if (!newLayer && !m.animated() && !doc.layers.get(doc.activeLayer).visible) {
            if (host != null) host.onMessage(R.string.layer_hidden);
            return false;
        }
        commitFloating();
        endOverlay();
        float bw = m.w, bh = m.h;
        float f = Math.min(1f, Math.min((float) doc.w / bw, (float) doc.h / bh));
        bw = Math.max(1f, bw * f);
        bh = Math.max(1f, bh * f);
        ov = m;
        ovNewLayer = newLayer;
        ovBox = new OverlayBox(Math.round((doc.w - bw) / 2f), Math.round((doc.h - bh) / 2f), bw, bh);
        ovFrame = 0;
        ovBmp = Bitmap.createBitmap(m.w, m.h, Bitmap.Config.ARGB_8888);
        ovBmp.setPixels(m.frames.get(0), 0, m.w, 0, 0, m.w, m.h);
        if (m.animated()) postDelayed(ovTick, Math.max(30, m.delays.get(0)));
        sel = null;
        invalidate();
        return true;
    }

    public boolean hasOverlay() { return ov != null; }

    private void endOverlay() {
        removeCallbacks(ovTick);
        ov = null;
        ovBox = null;
        if (ovBmp != null) {
            ovBmp.recycle();
            ovBmp = null;
        }
    }

    /** Drops the picture without placing it. */
    public void cancelOverlay() {
        if (ov == null) return;
        endOverlay();
        invalidate();
        if (host != null) host.onOverlayFinished(null);
    }

    /** Places the picture where the frame is. */
    public void commitOverlay() {
        if (ov == null) return;
        OverlayJob j = new OverlayJob();
        j.media = ov;
        j.x = Math.round(ovBox.x);
        j.y = Math.round(ovBox.y);
        j.w = Math.max(1, Math.round(ovBox.w));
        j.h = Math.max(1, Math.round(ovBox.h));
        j.smooth = !doc.pixelArt || (j.w < ov.w && j.h < ov.h);
        j.newLayer = ovNewLayer || ov.animated();
        endOverlay();
        invalidate();
        if (host != null) host.onOverlayFinished(j);
    }

    /** Blends a single still picture onto the active layer of the active frame as one undo step. */
    public void placeStill(OverlayJob j) {
        if (doc == null || j == null) return;
        int rx0 = Math.max(0, j.x), ry0 = Math.max(0, j.y);
        int rx1 = Math.min(doc.w, j.x + j.w), ry1 = Math.min(doc.h, j.y + j.h);
        if (rx1 <= rx0 || ry1 <= ry0) return;
        int rw = rx1 - rx0, rh = ry1 - ry0;
        int[] win = Raster.resizeWindow(j.media.frames.get(0), j.media.w, j.media.h, j.w, j.h, rx0 - j.x, ry0 - j.y, rw, rh, j.smooth);
        int[] layer = doc.layerPixels();
        int[] before = new int[rw * rh], after = new int[rw * rh];
        boolean any = false;
        for (int y = 0; y < rh; y++) {
            for (int x = 0; x < rw; x++) {
                int li = (ry0 + y) * doc.w + rx0 + x, wi = y * rw + x;
                int b = layer[li];
                before[wi] = b;
                int sp = win[wi];
                int a = (sp >>> 24) == 0 ? b : Blend.over(b, sp);
                after[wi] = a;
                if (a != b) any = true;
            }
        }
        if (!any) return;
        for (int y = 0; y < rh; y++) System.arraycopy(after, y * rw, layer, (ry0 + y) * doc.w + rx0, rw);
        history.pushPatch(doc, doc.activeFrame, doc.activeLayer, new Patch(rx0, ry0, rw, rh, before, after));
        refreshRegion(rx0, ry0, rw, rh);
        if (host != null) {
            host.onHistoryChanged();
            host.onEdited();
        }
    }

    // ------------------------------------------------------------------ eyedropper

    /**
     * Starts a one-shot color pick: touch (and slide over) the picture, a magnifier shows the pixel, lift the finger
     * to take it. The caller hides its color palette meanwhile and brings it back from the callbacks.
     */
    public void beginEyedrop(EyedropListener l) {
        commitFloating();
        eyeListener = l;
        eyeShow = false;
        invalidate();
    }

    public boolean isEyedropping() { return eyeListener != null; }

    public void cancelEyedrop() {
        if (eyeListener == null) return;
        EyedropListener l = eyeListener;
        eyeListener = null;
        eyeShow = false;
        invalidate();
        l.onCancel();
    }

    private void eyeUpdate(float x, float y) {
        int ix = toIx(x), iy = toIy(y);
        eyeSx = x;
        eyeSy = y;
        if (ix < 0 || iy < 0 || ix >= doc.w || iy >= doc.h) {
            eyeShow = false;
        } else {
            eyeShow = true;
            eyeIx = ix;
            eyeIy = iy;
            eyeColor = comp[iy * doc.w + ix];
            if ((eyeColor >>> 24) == 0) eyeColor = 0;
        }
        invalidate();
    }

    private void eyeFinish() {
        if (!eyeShow) return;
        if ((eyeColor >>> 24) == 0) {
            if (host != null) host.onMessage(R.string.picked_transparent);
            eyeShow = false;
            invalidate();
            return;
        }
        EyedropListener l = eyeListener;
        eyeListener = null;
        eyeShow = false;
        invalidate();
        if (l != null) l.onPicked(eyeColor);
    }

    private void drawEyedrop(Canvas c) {
        if (!eyeShow) return;
        float L = tx + eyeIx * scale, T = ty + eyeIy * scale;
        tmpR.set(L, T, L + scale, T + scale);
        c.drawRect(tmpR, antsBlack);
        c.drawRect(tmpR, antsWhite);

        Context ctx = getContext();
        float box = UI.dp(ctx, 96), pad = UI.dp(ctx, 10), bar = UI.dp(ctx, 26);
        float left = Math.max(pad, Math.min(getWidth() - pad - box, eyeSx - box / 2f));
        float top = eyeSy - UI.dp(ctx, 48) - box - bar;
        if (top < pad) top = eyeSy + UI.dp(ctx, 48);
        c.drawRect(left - 3, top - 3, left + box + 3, top + box + bar + 3, boxPaint);
        tmpR.set(left, top, left + box, top + box);
        c.drawRect(tmpR, checker);
        int win = Math.max(1, Math.min(9, Math.min(doc.w, doc.h)));
        int wx0 = Math.max(0, Math.min(doc.w - win, eyeIx - win / 2));
        int wy0 = Math.max(0, Math.min(doc.h - win, eyeIy - win / 2));
        loupeSrc.set(wx0, wy0, wx0 + win, wy0 + win);
        imgPaint.setFilterBitmap(false);
        c.drawBitmap(bmp, loupeSrc, tmpR, imgPaint);
        float cell = box / win;
        float mx = left + (eyeIx - wx0) * cell, my = top + (eyeIy - wy0) * cell;
        c.drawRect(mx, my, mx + cell, my + cell, antsBlack);
        c.drawRect(mx, my, mx + cell, my + cell, antsWhite);
        // swatch + hex of the pixel under the finger
        tmpR.set(left, top + box, left + bar, top + box + bar);
        c.drawRect(tmpR, checker);
        swatchPaint.setColor(eyeColor);
        c.drawRect(tmpR, swatchPaint);
        String hex = String.format("#%06X", eyeColor & 0xFFFFFF);
        c.drawText(hex, left + bar + UI.dp(ctx, 6), top + box + bar * 0.72f, eyeText);
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
        if (mode == M_EYE) {
            eyeShow = false;
            invalidate();
        }
        if (mode == M_OVL && ovBox != null) ovBox.end();
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
        if (eyeListener != null) {
            mode = M_EYE;
            eyeUpdate(x, y);
            return;
        }
        if (ov != null) {
            ovDown(x, y);
            return;
        }
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
            case ToolState.WAND:
                if (sel != null && ix >= sel[0] && ix < sel[2] && iy >= sel[1] && iy < sel[3]) {
                    if (!canDraw()) return;
                    mode = M_MOVING; // drag a smart selection to move the cut-out
                    moveStarted = false;
                } else {
                    mode = M_WAND;
                }
                break;
            case ToolState.PENCIL:
            case ToolState.BRUSH:
            case ToolState.ERASER:
            case ToolState.SHADE:
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
            case M_EYE:
                eyeUpdate(x, y);
                break;
            case M_OVL: {
                if (ovBox == null) {
                    mode = M_NONE;
                    break;
                }
                ovBox.drag((x - tx) / scale - ovGrabX, (y - ty) / scale - ovGrabY);
                if (ovBox.activeHandle() == OverlayBox.BODY) keepOverlayReachable();
                invalidate();
                break;
            }
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
            case M_WAND:
                if (!cancel && !moved(e)) wandAt(toIx(e.getX()), toIy(e.getY()));
                break;
            case M_EYE:
                if (!cancel) eyeFinish();
                else {
                    eyeShow = false;
                    invalidate();
                }
                break;
            case M_OVL:
                if (ovBox != null) ovBox.end();
                break;
            case M_OVL_TAP:
                // a plain tap on an empty spot drops the picture; a swipe or a second finger (zoom) does not
                if (!cancel && !moved(e)) commitOverlay();
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

    private void ovDown(float x, float y) {
        float dx = (x - tx) / scale, dy = (y - ty) / scale;
        int hit = ovBox.hit(dx, dy, UI.dp(getContext(), 26) / scale);
        if (hit == OverlayBox.NONE) {
            mode = M_OVL_TAP;
            return;
        }
        ovBox.begin(hit);
        ovGrabX = dx;
        ovGrabY = dy;
        mode = M_OVL;
    }

    /** Keeps at least a grabbable part of the frame over the canvas so it can never be lost off screen. */
    private void keepOverlayReachable() {
        float keep = Math.min(Math.min(ovBox.w, ovBox.h), Math.max(2f, UI.dp(getContext(), 30) / scale));
        ovBox.x = Math.max(keep - ovBox.w, Math.min(doc.w - keep, ovBox.x));
        ovBox.y = Math.max(keep - ovBox.h, Math.min(doc.h - keep, ovBox.y));
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
        int style = Stroke.ST_NORMAL, opacity = 100;
        if (tool == ToolState.BRUSH) {
            style = ts.brushStyle;
            opacity = ts.brushOpacity;
        } else if (tool == ToolState.SHADE) {
            aa = !doc.pixelArt;
            square = doc.pixelArt;
            opacity = ts.shadeStrength;
            style = ts.shadeMode == ToolState.SHADE_LIGHT ? Stroke.ST_SHADE_LIGHT
                    : ts.shadeMode == ToolState.SHADE_HATCH ? Stroke.ST_HATCH : Stroke.ST_SHADE_DARK;
        }
        stroke.begin(doc.layerPixels(), ts.color, ts.size(), erase, aa, square, selClip());
        stroke.setStyle(style, opacity);
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
        int opacity = Math.max(1, Math.min(100, ts.fillOpacity)) * 255 / 100;
        int[] r;
        if (ts.fillMode == ToolState.FILL_INSIDE) {
            // the outline is read from what the person sees (all layers), the colour goes into the active layer
            r = FloodFill.fillInside(comp, layer, doc.w, doc.h, ix, iy, ts.color, opacity, ts.edgeColor,
                    ts.edgeTol, ts.gap, selClip());
            if (r == FloodFill.LEAK) {
                if (host != null) host.onMessage(R.string.fill_leak);
                return;
            }
            if (r == FloodFill.ON_EDGE) {
                if (host != null) host.onMessage(R.string.fill_on_edge);
                return;
            }
        } else {
            r = FloodFill.fill(layer, doc.w, doc.h, ix, iy, ts.color, ts.tolerance, opacity, selClip());
        }
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

    private boolean[] activeMask() {
        return (selMask != null && sel != null && sel == maskSel && !floating) ? selMask : null;
    }

    /** Smart selection: selects exactly the shape (or colour area) under the tap. */
    private void wandAt(int ix, int iy) {
        if (ix < 0 || iy < 0 || ix >= doc.w || iy >= doc.h) return;
        commitFloating();
        FloodFill.Region r = FloodFill.region(doc.layerPixels(), doc.w, doc.h, ix, iy,
                ts.wandTol, ts.wandObject, null);
        if (r == null || r.count == 0) {
            if (host != null) host.onMessage(R.string.wand_nothing);
            return;
        }
        sel = new int[]{r.x, r.y, r.x + r.w, r.y + r.h};
        autoSel = false;
        selMask = r.mask;
        maskSel = sel;
        int[] tint = new int[r.w * r.h];
        for (int i = 0; i < tint.length; i++) tint[i] = r.mask[i] ? 0x553B82F6 : 0;
        if (maskBmp != null) maskBmp.recycle();
        maskBmp = Bitmap.createBitmap(r.w, r.h, Bitmap.Config.ARGB_8888);
        maskBmp.setPixels(tint, 0, r.w, 0, 0, r.w, r.h);
        invalidate();
        if (host != null) host.onRequestTool(ToolState.WAND); // refreshes the options (cut / copy buttons)
    }

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
        boolean[] mk = activeMask();
        layerBefore = layer.clone();
        if (mk == null) {
            for (int y = 0; y < fh; y++) {
                System.arraycopy(layer, (sel[1] + y) * doc.w + sel[0], fbuf, y * fw, fw);
            }
            for (int y = 0; y < fh; y++) {
                java.util.Arrays.fill(layer, (sel[1] + y) * doc.w + sel[0], (sel[1] + y) * doc.w + sel[2], 0);
            }
        } else {
            for (int y = 0; y < fh; y++) {
                for (int x = 0; x < fw; x++) {
                    if (!mk[y * fw + x]) continue;
                    int li = (sel[1] + y) * doc.w + sel[0] + x;
                    fbuf[y * fw + x] = layer[li];
                    layer[li] = 0;
                }
            }
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
        commitOverlay();
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
        boolean[] mk = activeMask();
        for (int y = 0; y < h; y++) System.arraycopy(layer, (sel[1] + y) * doc.w + sel[0], buf, y * w, w);
        if (mk != null) for (int i = 0; i < buf.length; i++) if (!mk[i]) buf[i] = 0;
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
        int[] after = new int[w * h];
        boolean[] mk = activeMask();
        boolean any = false;
        for (int y = 0; y < h; y++) {
            System.arraycopy(layer, (sel[1] + y) * doc.w + sel[0], before, y * w, w);
        }
        for (int i = 0; i < before.length; i++) {
            boolean cut = mk == null || mk[i];
            after[i] = cut ? 0 : before[i];
            if (cut && before[i] != 0) any = true;
        }
        if (!any) return;
        for (int y = 0; y < h; y++) System.arraycopy(after, y * w, layer, (sel[1] + y) * doc.w + sel[0], w);
        history.pushPatch(doc, doc.activeFrame, doc.activeLayer, new Patch(sel[0], sel[1], w, h, before, after));
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
