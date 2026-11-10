package com.pixelstudio.app;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.view.View;
import android.widget.LinearLayout;

/**
 * Opens the phone's gallery (the system photo picker on Android 13+, or a gallery app on older phones)
 * instead of the file manager. Results arrive in onActivityResult as a single Uri or a ClipData.
 */
final class MediaPicker {
    private MediaPicker() {}

    private static final int MAX_PICK = 20;

    static void pick(final Activity a, final int req, final boolean multiple) {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                Intent i = new Intent(MediaStore.ACTION_PICK_IMAGES); // photos, GIFs and videos together
                if (multiple) i.putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, MAX_PICK);
                a.startActivityForResult(i, req);
                return;
            } catch (ActivityNotFoundException | SecurityException ignored) {
                // phones without the photo picker fall through to the gallery apps
            }
        }
        legacy(a, req, multiple);
    }

    /** Older phones: a gallery app handles images and videos separately, so ask which one first. */
    private static void legacy(final Activity a, final int req, final boolean multiple) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        final android.app.Dialog[] dd = new android.app.Dialog[1];
        box.addView(choice(a, R.string.pick_photo, new View.OnClickListener() {
            @Override public void onClick(View v) {
                dd[0].dismiss();
                launch(a, req, multiple, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*");
            }
        }));
        box.addView(choice(a, R.string.pick_video, new View.OnClickListener() {
            @Override public void onClick(View v) {
                dd[0].dismiss();
                launch(a, req, multiple, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "video/*");
            }
        }));
        dd[0] = UI.dialog(a, a.getString(R.string.choose_source), box, null, null, a.getString(R.string.cancel), null);
        dd[0].show();
    }

    private static android.widget.TextView choice(Activity a, int text, View.OnClickListener l) {
        android.widget.TextView t = UI.button(a, a.getString(text), UI.SURFACE2, UI.TEXT, l);
        LinearLayout.LayoutParams lp = UI.lp(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = UI.dp(a, 8);
        t.setLayoutParams(lp);
        return t;
    }

    private static void launch(Activity a, int req, boolean multiple, Uri store, String type) {
        try {
            Intent i = new Intent(Intent.ACTION_PICK, store);
            i.setType(type);
            if (multiple) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            a.startActivityForResult(i, req);
            return;
        } catch (ActivityNotFoundException ignored) {
        }
        try {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType(type);
            if (multiple) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            a.startActivityForResult(i, req);
        } catch (ActivityNotFoundException e) {
            UI.toast(a, R.string.no_app_found);
        }
    }
}
