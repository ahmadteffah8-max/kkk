package com.pixelstudio.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;

import com.pixelstudio.core.Doc;
import com.pixelstudio.core.Frame;

/** Horizontal frame strip: thumbnails, index and delay per frame, tap to select, long-press for a menu. */
public class TimelineView extends HorizontalScrollView {

    public interface Listener {
        void onSelect(int index);
        void onMenu(int index, View anchor);
        void onAdd();
    }

    private Doc doc;
    private Listener listener;
    private final LinearLayout row;
    private final Paint checker;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int cellH, cellW;

    public TimelineView(Context c) {
        super(c);
        setHorizontalScrollBarEnabled(false);
        row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int pad = UI.dp(c, 6);
        row.setPadding(pad, pad, pad, pad);
        addView(row, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        checker = ColorDialog.checkerPaint(c, 5);
        cellH = UI.dp(c, 64);
        cellW = UI.dp(c, 64);
    }

    public void bind(Doc d, Listener l) {
        doc = d;
        listener = l;
        refresh();
    }

    /** Rebuilds cells (structure changed). */
    public void refresh() {
        if (doc == null) return;
        row.removeAllViews();
        for (int i = 0; i < doc.frames.size(); i++) {
            Cell c = new Cell(getContext(), i);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(cellW, cellH);
            lp.rightMargin = UI.dp(getContext(), 6);
            row.addView(c, lp);
        }
        IconView add = new IconView(getContext());
        add.setIcon(IconView.ADD);
        add.setBackground(UI.roundedStroke(UI.SURFACE2, UI.SURFACE3, 1, 10, getContext()));
        int p6 = UI.dp(getContext(), 18);
        add.setPadding(p6, p6, p6, p6);
        add.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { if (listener != null) listener.onAdd(); }
        });
        row.addView(add, new LinearLayout.LayoutParams(cellW, cellH));
        scrollToActive();
    }

    /** Redraws cells (pixels or selection changed) without rebuilding. */
    public void invalidateCells() {
        for (int i = 0; i < row.getChildCount(); i++) row.getChildAt(i).invalidate();
    }

    public void scrollToActive() {
        if (doc == null) return;
        final int target = Math.max(0, doc.activeFrame * (cellW + UI.dp(getContext(), 6)) - getWidth() / 2 + cellW);
        post(new Runnable() {
            @Override public void run() { smoothScrollTo(target, 0); }
        });
    }

    private Bitmap thumb(Frame f) {
        if (f.cache instanceof Bitmap && f.cacheVersion == f.version) return (Bitmap) f.cache;
        int[] px = doc.compose(f);
        Bitmap full = Bitmap.createBitmap(doc.w, doc.h, Bitmap.Config.ARGB_8888);
        full.setPixels(px, 0, doc.w, 0, 0, doc.w, doc.h);
        int box = cellH;
        float s = Math.min((float) box / doc.w, (float) box / doc.h);
        Bitmap t;
        if (s >= 1f && doc.pixelArt) {
            int k = Math.max(1, (int) s);
            t = Bitmap.createScaledBitmap(full, doc.w * k, doc.h * k, false);
        } else if (s < 1f) {
            t = Bitmap.createScaledBitmap(full, Math.max(1, Math.round(doc.w * s)), Math.max(1, Math.round(doc.h * s)), true);
        } else {
            t = Bitmap.createScaledBitmap(full, Math.round(doc.w * s), Math.round(doc.h * s), false);
        }
        if (t != full) full.recycle();
        f.cache = t;
        f.cacheVersion = f.version;
        return t;
    }

    private final class Cell extends View {
        final int index;

        Cell(Context c, int index) {
            super(c);
            this.index = index;
            setClickable(true);
            setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { if (listener != null) listener.onSelect(Cell.this.index); }
            });
            setOnLongClickListener(new View.OnLongClickListener() {
                @Override public boolean onLongClick(View v) {
                    if (listener != null) listener.onMenu(Cell.this.index, Cell.this);
                    return true;
                }
            });
        }

        @Override protected void onDraw(Canvas c) {
            if (doc == null || index >= doc.frames.size()) return;
            Frame f = doc.frames.get(index);
            float w = getWidth(), h = getHeight();
            RectF r = new RectF(0, 0, w, h);
            float rad = UI.dp(getContext(), 8);
            boolean active = index == doc.activeFrame;
            p.setStyle(Paint.Style.FILL);
            p.setColor(UI.SURFACE2);
            c.drawRoundRect(r, rad, rad, p);
            c.save();
            RectF inner = new RectF(3, 3, w - 3, h - 3);
            c.clipRect(inner);
            Bitmap t = thumb(f);
            float s = Math.min(inner.width() / t.getWidth(), inner.height() / t.getHeight());
            float tw = t.getWidth() * s, th = t.getHeight() * s;
            RectF d = new RectF((w - tw) / 2f, (h - th) / 2f, (w + tw) / 2f, (h + th) / 2f);
            c.drawRect(d, checker);
            Paint ip = new Paint();
            ip.setFilterBitmap(!doc.pixelArt);
            c.drawBitmap(t, null, d, ip);
            c.restore();
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(UI.dp(getContext(), active ? 2.5f : 1f));
            p.setColor(active ? UI.ACCENT : UI.SURFACE3);
            c.drawRoundRect(new RectF(1, 1, w - 1, h - 1), rad, rad, p);
            // labels
            p.setStyle(Paint.Style.FILL);
            p.setTextSize(UI.dp(getContext(), 10));
            String idx = String.valueOf(index + 1);
            float pad = UI.dp(getContext(), 4);
            p.setColor(0xAA000000);
            c.drawRoundRect(new RectF(pad, pad, pad + p.measureText(idx) + pad * 2, pad + UI.dp(getContext(), 14)), 4, 4, p);
            p.setColor(0xFFFFFFFF);
            c.drawText(idx, pad * 2, pad + UI.dp(getContext(), 11), p);
            String dl = f.delayMs + "ms";
            float dw = p.measureText(dl);
            p.setColor(0xAA000000);
            c.drawRoundRect(new RectF(w - dw - pad * 3, h - pad - UI.dp(getContext(), 14), w - pad, h - pad), 4, 4, p);
            p.setColor(0xFFFFFFFF);
            c.drawText(dl, w - dw - pad * 2, h - pad - UI.dp(getContext(), 3), p);
        }
    }
}
