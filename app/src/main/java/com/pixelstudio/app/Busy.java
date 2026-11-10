package com.pixelstudio.app;

import android.app.Dialog;
import android.content.Context;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

/** A small "working…" dialog with a percentage and an optional Cancel button. Safe to update from any thread. */
final class Busy {
    private final Dialog dialog;
    private final TextView msg;
    private final String base;
    private volatile boolean cancelled;

    private Busy(Context c, String text, boolean cancellable) {
        base = text;
        msg = UI.label(c, text, 15, UI.TEXT);
        msg.setGravity(Gravity.CENTER);
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, UI.dp(c, 8), 0, UI.dp(c, 8));
        box.addView(msg, UI.lp(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        dialog = UI.dialog(c, null, box, null, null, cancellable ? c.getString(R.string.cancel) : null, new UI.Action() {
            @Override public void run() { cancelled = true; }
        });
        dialog.setCancelable(false);
    }

    static Busy show(Context c, String text, boolean cancellable) {
        Busy b = new Busy(c, text, cancellable);
        b.dialog.show();
        return b;
    }

    boolean cancelled() { return cancelled; }

    void progress(final int percent) {
        msg.post(new Runnable() {
            @Override public void run() { msg.setText(base + " " + percent + "%"); }
        });
    }

    /** UI thread only. */
    void dismiss() {
        try {
            dialog.dismiss();
        } catch (RuntimeException ignored) {
        }
    }
}
