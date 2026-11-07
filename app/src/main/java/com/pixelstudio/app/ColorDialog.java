package com.pixelstudio.app;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/** Color picker: HSV square, hue and alpha bars, hex field, palettes, recent and saved colors. */
public final class ColorDialog {
    public interface Result { void onColor(int argb); }

    /** Draws a checkerboard used behind transparent colors. */
    static Paint checkerPaint(Context c, float cellDp) {
        int cell = Math.max(2, UI.dp(c, cellDp));
        Bitmap b = Bitmap.createBitmap(cell * 2, cell * 2, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        cv.drawColor(0xFFFFFFFF);
        Paint p = new Paint();
        p.setColor(0xFFC8C8C8);
        cv.drawRect(0, 0, cell, cell, p);
        cv.drawRect(cell, cell, cell * 2, cell * 2, p);
        Paint out = new Paint();
        out.setShader(new BitmapShader(b, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
        return out;
    }

    private static final class SvView extends View {
        float hue, sat = 1f, val = 1f;
        Runnable onChange;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);

        SvView(Context c) {
            super(c);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(UI.dp(c, 2));
        }

        @Override protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            RectF r = new RectF(0, 0, w, h);
            float rad = UI.dp(getContext(), 8);
            p.setShader(new LinearGradient(0, 0, w, 0, 0xFFFFFFFF, Color.HSVToColor(new float[]{hue, 1f, 1f}), Shader.TileMode.CLAMP));
            c.drawRoundRect(r, rad, rad, p);
            p.setShader(new LinearGradient(0, 0, 0, h, 0x00000000, 0xFF000000, Shader.TileMode.CLAMP));
            c.drawRoundRect(r, rad, rad, p);
            p.setShader(null);
            float x = sat * w, y = (1f - val) * h;
            ring.setColor(0xFF000000);
            c.drawCircle(x, y, UI.dp(getContext(), 9), ring);
            ring.setColor(0xFFFFFFFF);
            c.drawCircle(x, y, UI.dp(getContext(), 7), ring);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            getParent().requestDisallowInterceptTouchEvent(true);
            sat = Math.max(0f, Math.min(1f, e.getX() / getWidth()));
            val = 1f - Math.max(0f, Math.min(1f, e.getY() / getHeight()));
            invalidate();
            if (onChange != null) onChange.run();
            return true;
        }
    }

    private static final class BarView extends View {
        float value;
        int[] colors;
        boolean checker;
        Runnable onChange;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint thumb = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint checkerP;

        BarView(Context c, boolean checker) {
            super(c);
            this.checker = checker;
            checkerP = checkerPaint(c, 5);
            thumb.setStyle(Paint.Style.STROKE);
            thumb.setStrokeWidth(UI.dp(c, 2.5f));
        }

        @Override protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            float pad = UI.dp(getContext(), 11);
            RectF r = new RectF(pad, h * 0.25f, w - pad, h * 0.75f);
            float rad = r.height() / 2;
            if (checker) c.drawRoundRect(r, rad, rad, checkerP);
            float[] pos = null;
            p.setShader(new LinearGradient(r.left, 0, r.right, 0, colors, pos, Shader.TileMode.CLAMP));
            c.drawRoundRect(r, rad, rad, p);
            p.setShader(null);
            float x = r.left + value * r.width();
            p.setColor(0xFFFFFFFF);
            c.drawCircle(x, h / 2f, UI.dp(getContext(), 10), p);
            thumb.setColor(0xFF000000);
            c.drawCircle(x, h / 2f, UI.dp(getContext(), 10), thumb);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            getParent().requestDisallowInterceptTouchEvent(true);
            float pad = UI.dp(getContext(), 11);
            value = Math.max(0f, Math.min(1f, (e.getX() - pad) / (getWidth() - 2 * pad)));
            invalidate();
            if (onChange != null) onChange.run();
            return true;
        }
    }

    private final Context ctx;
    private final Result result;
    private final int initial;
    private final float[] hsv = new float[3];
    private int alpha = 255;

    private SvView sv;
    private BarView hueBar, alphaBar;
    private EditText hex;
    private View preview;
    private LinearLayout recentRow, customRow;
    private GridLayout grid;
    private boolean updating;

    private ColorDialog(Context c, int color, Result r) {
        ctx = c;
        result = r;
        initial = color;
        alpha = color >>> 24;
        Color.colorToHSV(color, hsv);
    }

    public static void show(Context c, int color, Result r) {
        new ColorDialog(c, color, r).build();
    }

    private int current() {
        return (alpha << 24) | (Color.HSVToColor(new float[]{hsv[0], hsv[1], hsv[2]}) & 0xFFFFFF);
    }

    private void build() {
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);

        // preview: old | new
        LinearLayout pv = new LinearLayout(ctx);
        final Paint chk = checkerPaint(ctx, 6);
        preview = new View(ctx) {
            @Override protected void onDraw(Canvas c) {
                float w = getWidth(), h = getHeight();
                RectF r = new RectF(0, 0, w, h);
                float rad = UI.dp(getContext(), 8);
                c.drawRoundRect(r, rad, rad, chk);
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                p.setColor(initial);
                c.save();
                c.clipRect(0, 0, w / 2, h);
                c.drawRoundRect(r, rad, rad, p);
                c.restore();
                p.setColor(current());
                c.save();
                c.clipRect(w / 2, 0, w, h);
                c.drawRoundRect(r, rad, rad, p);
                c.restore();
            }
        };
        pv.addView(preview, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UI.dp(ctx, 36)));
        root.addView(pv, UI.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(UI.spacer(ctx, 1, 10));

        sv = new SvView(ctx);
        sv.hue = hsv[0];
        sv.sat = hsv[1];
        sv.val = hsv[2];
        sv.onChange = new Runnable() {
            @Override public void run() {
                hsv[1] = sv.sat;
                hsv[2] = sv.val;
                sync(false);
            }
        };
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UI.dp(ctx, 170)));

        hueBar = new BarView(ctx, false);
        hueBar.colors = new int[]{0xFFFF0000, 0xFFFFFF00, 0xFF00FF00, 0xFF00FFFF, 0xFF0000FF, 0xFFFF00FF, 0xFFFF0000};
        hueBar.value = hsv[0] / 360f;
        hueBar.onChange = new Runnable() {
            @Override public void run() {
                hsv[0] = hueBar.value * 359.99f;
                sv.hue = hsv[0];
                sv.invalidate();
                sync(false);
            }
        };
        root.addView(hueBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UI.dp(ctx, 34)));

        alphaBar = new BarView(ctx, true);
        alphaBar.value = alpha / 255f;
        alphaBar.onChange = new Runnable() {
            @Override public void run() {
                alpha = Math.round(alphaBar.value * 255f);
                sync(false);
            }
        };
        root.addView(alphaBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UI.dp(ctx, 34)));

        // hex row
        LinearLayout hexRow = new LinearLayout(ctx);
        hexRow.setGravity(Gravity.CENTER_VERTICAL);
        hexRow.addView(UI.label(ctx, "HEX  #", 14, UI.TEXT2));
        hex = new EditText(ctx);
        hex.setSingleLine(true);
        hex.setTextColor(UI.TEXT);
        hex.setTextSize(15);
        hex.setFilters(new InputFilter[]{new InputFilter.LengthFilter(8), new InputFilter.AllCaps()});
        hex.setBackground(UI.roundedStroke(UI.SURFACE2, UI.SURFACE3, 1, 8, ctx));
        int pad = UI.dp(ctx, 8);
        hex.setPadding(pad, pad, pad, pad);
        hex.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable e) {
                if (updating) return;
                String t = e.toString();
                try {
                    int col;
                    if (t.length() == 6) col = 0xFF000000 | (int) Long.parseLong(t, 16);
                    else if (t.length() == 8) col = (int) Long.parseLong(t, 16);
                    else return;
                    alpha = col >>> 24;
                    Color.colorToHSV(col, hsv);
                    sv.hue = hsv[0]; sv.sat = hsv[1]; sv.val = hsv[2];
                    hueBar.value = hsv[0] / 360f;
                    alphaBar.value = alpha / 255f;
                    sv.invalidate(); hueBar.invalidate(); alphaBar.invalidate();
                    preview.invalidate();
                    updateAlphaBar();
                } catch (NumberFormatException ignored) {
                }
            }
        });
        hexRow.addView(hex, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        hexRow.addView(UI.spacer(ctx, 8, 1));
        hexRow.addView(UI.button(ctx, ctx.getString(R.string.save_color), UI.SURFACE2, UI.TEXT, new View.OnClickListener() {
            @Override public void onClick(View v) {
                Settings.addCustomColor(ctx, current());
                refreshLists();
            }
        }));
        LinearLayout.LayoutParams hl = UI.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hl.topMargin = UI.dp(ctx, 8);
        root.addView(hexRow, hl);

        // palette chips
        HorizontalScrollView chipScroll = new HorizontalScrollView(ctx);
        chipScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = new LinearLayout(ctx);
        for (int i = 0; i < Palettes.NAMES.length; i++) {
            final int idx = i;
            TextView t = UI.button(ctx, Palettes.NAMES[i].equals("Basic") ? ctx.getString(R.string.palette_basic) : Palettes.NAMES[i],
                    UI.SURFACE2, UI.TEXT, new View.OnClickListener() {
                        @Override public void onClick(View v) { fillGrid(idx); }
                    });
            t.setTextSize(12);
            LinearLayout.LayoutParams lp = UI.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = UI.dp(ctx, 6);
            chips.addView(t, lp);
        }
        chipScroll.addView(chips);
        LinearLayout.LayoutParams cl = UI.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cl.topMargin = UI.dp(ctx, 12);
        root.addView(chipScroll, cl);

        grid = new GridLayout(ctx);
        grid.setColumnCount(8);
        LinearLayout.LayoutParams gl = UI.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gl.topMargin = UI.dp(ctx, 8);
        root.addView(grid, gl);
        fillGrid(1); // PICO-8 by default

        root.addView(UI.label(ctx, ctx.getString(R.string.recent_colors), 12, UI.TEXT2), titleLp());
        HorizontalScrollView rs = new HorizontalScrollView(ctx);
        rs.setHorizontalScrollBarEnabled(false);
        recentRow = new LinearLayout(ctx);
        rs.addView(recentRow);
        root.addView(rs);
        root.addView(UI.label(ctx, ctx.getString(R.string.my_colors), 12, UI.TEXT2), titleLp());
        HorizontalScrollView cs = new HorizontalScrollView(ctx);
        cs.setHorizontalScrollBarEnabled(false);
        customRow = new LinearLayout(ctx);
        cs.addView(customRow);
        root.addView(cs);
        refreshLists();

        sync(true);

        final Dialog d = UI.dialog(ctx, ctx.getString(R.string.color), root, ctx.getString(R.string.ok), new UI.Action() {
            @Override public void run() {
                int c = current();
                Settings.addRecentColor(ctx, c);
                result.onColor(c);
            }
        }, ctx.getString(R.string.cancel), null);
        d.show();
    }

    private LinearLayout.LayoutParams titleLp() {
        LinearLayout.LayoutParams l = UI.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        l.topMargin = UI.dp(ctx, 10);
        l.bottomMargin = UI.dp(ctx, 4);
        return l;
    }

    private void updateAlphaBar() {
        int base = Color.HSVToColor(new float[]{hsv[0], hsv[1], hsv[2]}) & 0xFFFFFF;
        alphaBar.colors = new int[]{base, 0xFF000000 | base};
        alphaBar.invalidate();
    }

    private void sync(boolean initialSetup) {
        updateAlphaBar();
        preview.invalidate();
        updating = true;
        int c = current();
        String s = alpha == 255 ? String.format("%06X", c & 0xFFFFFF) : String.format("%08X", c);
        hex.setText(s);
        updating = false;
    }

    private View swatch(final int color, boolean removable) {
        View v = new View(ctx);
        int size = UI.dp(ctx, 32);
        GridLayout.LayoutParams gp = null;
        v.setBackground(UI.roundedStroke(color, 0x55FFFFFF, 1, 6, ctx));
        v.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View x) { applyColor(color); }
        });
        if (removable) {
            v.setOnLongClickListener(new View.OnLongClickListener() {
                @Override public boolean onLongClick(View x) {
                    Settings.removeCustomColor(ctx, color);
                    refreshLists();
                    return true;
                }
            });
        }
        v.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        return v;
    }

    private void applyColor(int color) {
        alpha = color >>> 24;
        Color.colorToHSV(color, hsv);
        sv.hue = hsv[0]; sv.sat = hsv[1]; sv.val = hsv[2];
        hueBar.value = hsv[0] / 360f;
        alphaBar.value = alpha / 255f;
        sv.invalidate(); hueBar.invalidate(); alphaBar.invalidate();
        sync(false);
    }

    private void fillGrid(int paletteIndex) {
        grid.removeAllViews();
        int[] cols = Palettes.get(paletteIndex);
        int size = UI.dp(ctx, 32);
        int m = UI.dp(ctx, 3);
        for (final int c : cols) {
            View v = new View(ctx);
            v.setBackground(UI.roundedStroke(c, 0x55FFFFFF, 1, 6, ctx));
            v.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View x) { applyColor(c); }
            });
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = size;
            lp.height = size;
            lp.setMargins(m, m, m, m);
            grid.addView(v, lp);
        }
    }

    private void refreshLists() {
        recentRow.removeAllViews();
        customRow.removeAllViews();
        List<Integer> rec = Settings.recentColors(ctx);
        int m = UI.dp(ctx, 3);
        for (int c : rec) {
            View s = swatch(c, false);
            ((LinearLayout.LayoutParams) s.getLayoutParams()).setMargins(m, m, m, m);
            recentRow.addView(s);
        }
        List<Integer> cus = Settings.customColors(ctx);
        for (int c : cus) {
            View s = swatch(c, true);
            ((LinearLayout.LayoutParams) s.getLayoutParams()).setMargins(m, m, m, m);
            customRow.addView(s);
        }
    }
}
