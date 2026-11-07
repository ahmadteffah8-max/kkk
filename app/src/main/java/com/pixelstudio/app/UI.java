package com.pixelstudio.app;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Small view-building helpers and the dark theme palette. */
public final class UI {
    private UI() {}

    public static final int BG = 0xFF0F1016;
    public static final int SURFACE = 0xFF1A1C27;
    public static final int SURFACE2 = 0xFF262938;
    public static final int SURFACE3 = 0xFF343850;
    public static final int ACCENT = 0xFF8B6CFF;
    public static final int ACCENT2 = 0xFF22D3B6;
    public static final int TEXT = 0xFFEEF0FA;
    public static final int TEXT2 = 0xFF9BA1BB;
    public static final int DANGER = 0xFFFF5470;

    public static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    public static GradientDrawable rounded(int color, float radiusDp, Context c) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(c, radiusDp));
        return g;
    }

    public static GradientDrawable roundedStroke(int fill, int stroke, float strokeDp, float radiusDp, Context c) {
        GradientDrawable g = rounded(fill, radiusDp, c);
        g.setStroke(Math.max(1, dp(c, strokeDp)), stroke);
        return g;
    }

    public static TextView label(Context c, CharSequence text, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    public static TextView bold(Context c, CharSequence text, float sp, int color) {
        TextView t = label(c, text, sp, color);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    public static TextView button(Context c, CharSequence text, int fill, int textColor, View.OnClickListener l) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(14);
        t.setTextColor(textColor);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        int ph = dp(c, 16), pv = dp(c, 10);
        t.setPadding(ph, pv, ph, pv);
        t.setBackground(rounded(fill, 10, c));
        t.setClickable(true);
        t.setOnClickListener(l);
        return t;
    }

    public static IconView iconButton(Context c, int icon, int sizeDp, View.OnClickListener l) {
        IconView v = new IconView(c);
        v.setIcon(icon);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp)));
        int p = dp(c, sizeDp * 0.22f);
        v.setPadding(p, p, p, p);
        v.setOnClickListener(l);
        v.setClickable(true);
        return v;
    }

    public static LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    public static LinearLayout.LayoutParams lp(Context c, float wDp, float hDp) {
        return new LinearLayout.LayoutParams(wDp < 0 ? (int) wDp : dp(c, wDp), hDp < 0 ? (int) hDp : dp(c, hDp));
    }

    public static LinearLayout.LayoutParams weight(float w) {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w);
    }

    public static View spacer(Context c, float wDp, float hDp) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(c, wDp), dp(c, hDp)));
        return v;
    }

    public static void toast(Context c, int resId) {
        Toast.makeText(c, resId, Toast.LENGTH_SHORT).show();
    }

    public static void toast(Context c, String s) {
        Toast.makeText(c, s, Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------ dialogs

    public interface Action { void run(); }

    /**
     * Styled dark dialog. posText/negText may be null (no button). Dialog is dismissed after
     * a button is pressed unless the caller keeps a reference and re-shows.
     */
    public static Dialog dialog(Context c, CharSequence title, View content, CharSequence posText,
                                final Action onPos, CharSequence negText, final Action onNeg) {
        final Dialog d = new Dialog(c);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(rounded(SURFACE, 18, c));
        int p = dp(c, 18);
        root.setPadding(p, p, p, dp(c, 12));
        if (title != null) {
            TextView t = bold(c, title, 18, TEXT);
            root.addView(t, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            root.addView(spacer(c, 1, 10));
        }
        if (content != null) {
            ScrollView sv = new ScrollView(c);
            sv.addView(content);
            root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        }
        if (posText != null || negText != null) {
            LinearLayout row = new LinearLayout(c);
            row.setGravity(Gravity.END);
            row.setPadding(0, dp(c, 12), 0, 0);
            if (negText != null) {
                TextView n = button(c, negText, SURFACE2, TEXT, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        d.dismiss();
                        if (onNeg != null) onNeg.run();
                    }
                });
                row.addView(n);
                row.addView(spacer(c, 8, 1));
            }
            if (posText != null) {
                TextView ok = button(c, posText, ACCENT, Color.WHITE, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        d.dismiss();
                        if (onPos != null) onPos.run();
                    }
                });
                row.addView(ok);
            }
            root.addView(row, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        d.setContentView(root);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int sw = c.getResources().getDisplayMetrics().widthPixels;
            int sh = c.getResources().getDisplayMetrics().heightPixels;
            int width = Math.min(sw - dp(c, 24), dp(c, 440));
            w.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT);
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            WindowManager.LayoutParams a = w.getAttributes();
            a.dimAmount = 0.6f;
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            // keep tall content inside the screen
            root.setMinimumHeight(0);
            if (content != null) {
                root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(sh, View.MeasureSpec.AT_MOST));
            }
        }
        return d;
    }

    public interface TextResult { void onText(String s); }

    public static void promptText(Context c, CharSequence title, String initial, CharSequence ok,
                                  CharSequence cancel, final TextResult r) {
        final EditText et = new EditText(c);
        et.setText(initial);
        et.setSelectAllOnFocus(true);
        et.setSingleLine(true);
        et.setTextColor(TEXT);
        et.setHintTextColor(TEXT2);
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        et.setBackground(roundedStroke(SURFACE2, SURFACE3, 1, 10, c));
        int p = dp(c, 12);
        et.setPadding(p, p, p, p);
        final Dialog d = dialog(c, title, et, ok, new Action() {
            @Override public void run() { r.onText(et.getText().toString().trim()); }
        }, cancel, null);
        d.show();
        et.requestFocus();
        if (d.getWindow() != null) d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    }

    public interface IntResult { void onInt(int v); }

    public static void promptNumber(Context c, CharSequence title, int initial, final int min, final int max,
                                    CharSequence ok, CharSequence cancel, final IntResult r) {
        final EditText et = new EditText(c);
        et.setText(String.valueOf(initial));
        et.setSelectAllOnFocus(true);
        et.setSingleLine(true);
        et.setTextColor(TEXT);
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setBackground(roundedStroke(SURFACE2, SURFACE3, 1, 10, c));
        int p = dp(c, 12);
        et.setPadding(p, p, p, p);
        final Dialog d = dialog(c, title, et, ok, new Action() {
            @Override public void run() {
                try {
                    int v = Integer.parseInt(et.getText().toString().trim());
                    r.onInt(Math.max(min, Math.min(max, v)));
                } catch (NumberFormatException ignored) {
                }
            }
        }, cancel, null);
        d.show();
        et.requestFocus();
        if (d.getWindow() != null) d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    }

    public static void confirm(Context c, CharSequence title, CharSequence msg, CharSequence ok,
                               CharSequence cancel, Action onOk) {
        TextView t = label(c, msg, 15, TEXT2);
        dialog(c, title, t, ok, onOk, cancel, null).show();
    }
}
