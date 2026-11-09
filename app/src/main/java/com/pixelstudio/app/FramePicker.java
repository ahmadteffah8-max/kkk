package com.pixelstudio.app;

import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

/** Lets the person choose one frame of a GIF / video, or all of its frames. */
final class FramePicker {
    private FramePicker() {}

    interface Result {
        /** frameIndex >= 0: only that frame; -1: every frame. */
        void onPick(int frameIndex);
    }

    static void show(final Context c, final ImportUtil.Media m, final Result r) {
        final int n = m.frames.size();
        final int[] cur = {0};

        final PreviewView preview = new PreviewView(c, m);
        final TextView label = UI.label(c, c.getString(R.string.frame_of, 1, n), 14, UI.TEXT2);
        final SeekBar bar = new SeekBar(c);
        bar.setMax(Math.max(1, n - 1));
        bar.setProgress(0);
        bar.setProgressTintList(ColorStateList.valueOf(UI.ACCENT));
        bar.setThumbTintList(ColorStateList.valueOf(UI.ACCENT));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                cur[0] = Math.max(0, Math.min(n - 1, p));
                label.setText(c.getString(R.string.frame_of, cur[0] + 1, n));
                preview.show(cur[0]);
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });

        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(preview, UI.lp(ViewGroup.LayoutParams.MATCH_PARENT, UI.dp(c, 230)));
        box.addView(label, UI.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        box.addView(bar, UI.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Dialog d = UI.dialog(c, c.getString(m.video ? R.string.pick_frame_video : R.string.pick_frame_gif), box,
                c.getString(R.string.this_frame_only), new UI.Action() {
                    @Override public void run() { r.onPick(cur[0]); }
                },
                c.getString(R.string.all_frames, n), new UI.Action() {
                    @Override public void run() { r.onPick(-1); }
                });
        d.show();
    }

    private static final class PreviewView extends View {
        private final Paint checker;
        private final Paint paint = new Paint();
        private final ImportUtil.Media media;
        private final Bitmap bmp;
        private final RectF r = new RectF();

        PreviewView(Context c, ImportUtil.Media m) {
            super(c);
            media = m;
            checker = ColorDialog.checkerPaint(c, 8);
            bmp = Bitmap.createBitmap(m.w, m.h, Bitmap.Config.ARGB_8888);
            show(0);
        }

        void show(int i) {
            bmp.setPixels(media.frames.get(i), 0, media.w, 0, 0, media.w, media.h);
            invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            float s = Math.min(w / bmp.getWidth(), h / bmp.getHeight());
            float tw = bmp.getWidth() * s, th = bmp.getHeight() * s;
            r.set((w - tw) / 2f, (h - th) / 2f, (w + tw) / 2f, (h + th) / 2f);
            c.drawRect(r, checker);
            paint.setFilterBitmap(media.w * media.h > 128 * 128);
            c.drawBitmap(bmp, null, r, paint);
        }
    }
}
