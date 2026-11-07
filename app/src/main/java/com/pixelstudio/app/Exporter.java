package com.pixelstudio.app;

import android.graphics.Bitmap;

import com.pixelstudio.core.Doc;
import com.pixelstudio.core.Frame;
import com.pixelstudio.core.GifEncoder;
import com.pixelstudio.core.ProjectIO;
import com.pixelstudio.core.Raster;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/** Renders a document to PNG / JPG / GIF / project bytes. Pure data in, stream out; safe on a worker thread. */
public final class Exporter {
    private Exporter() {}

    public static final int PNG = 0, JPG = 1, GIF = 2, PROJECT = 3;
    public static final int MAX_SIDE = 4096;

    public static final class Options {
        public int format = PNG;
        public int scale = 1;
        /** 0 = transparent background, otherwise an opaque ARGB color. */
        public int background = 0;
        public int jpgQuality = 92;
        /** Frame to export for still images. */
        public int frame;
    }

    public static String extension(int format) {
        switch (format) {
            case JPG: return "jpg";
            case GIF: return "gif";
            case PROJECT: return "pxs";
            default: return "png";
        }
    }

    public static String mime(int format) {
        switch (format) {
            case JPG: return "image/jpeg";
            case GIF: return "image/gif";
            case PROJECT: return "application/octet-stream";
            default: return "image/png";
        }
    }

    public static int maxScale(Doc d) {
        return Math.max(1, Math.min(32, MAX_SIDE / Math.max(d.w, d.h)));
    }

    /** Scale for photos: bilinear through a Bitmap. */
    private static int[] scaleSmooth(int[] src, int w, int h, int k) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        b.setPixels(src, 0, w, 0, 0, w, h);
        Bitmap s = Bitmap.createScaledBitmap(b, w * k, h * k, true);
        int[] out = new int[w * k * h * k];
        s.getPixels(out, 0, w * k, 0, 0, w * k, h * k);
        b.recycle();
        if (s != b) s.recycle();
        return out;
    }

    private static int[] scale(Doc d, int[] px, int k) {
        if (k <= 1) return px;
        return d.pixelArt ? Raster.scaleNearest(px, d.w, d.h, k) : scaleSmooth(px, d.w, d.h, k);
    }

    public static void write(Doc d, Options o, OutputStream out, GifEncoder.Progress progress) throws IOException {
        int k = Math.max(1, Math.min(o.scale, maxScale(d)));
        switch (o.format) {
            case PROJECT:
                ProjectIO.write(out, d);
                return;
            case GIF: {
                List<int[]> frames = new ArrayList<>();
                int[] delays = new int[d.frames.size()];
                for (int i = 0; i < d.frames.size(); i++) {
                    frames.add(scale(d, Raster.withBackground(d, d.frames.get(i), o.background), k));
                    delays[i] = d.frames.get(i).delayMs;
                }
                GifEncoder.write(out, d.w * k, d.h * k, frames, delays, d.loop, progress);
                return;
            }
            default: {
                Frame f = d.frames.get(Math.max(0, Math.min(o.frame, d.frames.size() - 1)));
                int bg = o.background;
                if (o.format == JPG && bg == 0) bg = 0xFFFFFFFF;
                int[] px = scale(d, Raster.withBackground(d, f, bg), k);
                int w = d.w * k, h = d.h * k;
                Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                b.setPixels(px, 0, w, 0, 0, w, h);
                boolean ok;
                if (o.format == JPG) {
                    ok = b.compress(Bitmap.CompressFormat.JPEG, Math.max(1, Math.min(100, o.jpgQuality)), out);
                } else {
                    ok = b.compress(Bitmap.CompressFormat.PNG, 100, out);
                }
                b.recycle();
                if (!ok) throw new IOException("encode failed");
            }
        }
    }
}
