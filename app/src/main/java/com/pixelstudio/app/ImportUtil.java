package com.pixelstudio.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import com.pixelstudio.core.Doc;
import com.pixelstudio.core.Frame;
import com.pixelstudio.core.GifDecoder;
import com.pixelstudio.core.LayerInfo;
import com.pixelstudio.core.ProjectIO;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Reads images, GIFs and .pxs projects from a content Uri. */
public final class ImportUtil {
    private ImportUtil() {}

    public static final class Img {
        public int w, h;
        public int[] px;
    }

    private static byte[] readAll(Context c, Uri uri) throws IOException {
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("cannot open");
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int r;
            long total = 0;
            while ((r = in.read(buf)) > 0) {
                bo.write(buf, 0, r);
                total += r;
                if (total > 120L * 1024 * 1024) throw new IOException("file too large");
            }
            return bo.toByteArray();
        }
    }

    private static boolean isGif(byte[] b) {
        return b.length > 6 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F';
    }

    private static boolean isGzip(byte[] b) {
        return b.length > 2 && (b[0] & 255) == 0x1f && (b[1] & 255) == 0x8b;
    }

    public static Img decodeBitmap(byte[] data, int maxSide) throws IOException {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        if (o.outWidth <= 0 || o.outHeight <= 0) throw new IOException("unsupported image");
        int sample = 1;
        while (Math.max(o.outWidth, o.outHeight) / sample > maxSide) sample *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = sample;
        o2.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length, o2);
        if (b == null) throw new IOException("decode failed");
        if (Math.max(b.getWidth(), b.getHeight()) > maxSide) {
            float s = (float) maxSide / Math.max(b.getWidth(), b.getHeight());
            Bitmap n = Bitmap.createScaledBitmap(b, Math.max(1, Math.round(b.getWidth() * s)),
                    Math.max(1, Math.round(b.getHeight() * s)), true);
            b.recycle();
            b = n;
        }
        Img img = new Img();
        img.w = b.getWidth();
        img.h = b.getHeight();
        img.px = new int[img.w * img.h];
        b.getPixels(img.px, 0, img.w, 0, 0, img.w, img.h);
        b.recycle();
        return img;
    }

    /** Image for pasting into an existing document (scaled down to fit when larger). */
    public static Img imageForPaste(Context c, Uri uri, int maxW, int maxH) throws IOException {
        byte[] data = readAll(c, uri);
        if (isGif(data)) {
            GifDecoder g = GifDecoder.decode(data);
            Img i = new Img();
            i.w = g.width;
            i.h = g.height;
            i.px = g.frames.get(0);
            return fit(i, maxW, maxH);
        }
        return fit(decodeBitmap(data, Doc.MAX_SIDE), maxW, maxH);
    }

    private static Img fit(Img i, int maxW, int maxH) {
        if (i.w <= maxW && i.h <= maxH) return i;
        float s = Math.min((float) maxW / i.w, (float) maxH / i.h);
        int nw = Math.max(1, Math.round(i.w * s)), nh = Math.max(1, Math.round(i.h * s));
        Bitmap b = Bitmap.createBitmap(i.w, i.h, Bitmap.Config.ARGB_8888);
        b.setPixels(i.px, 0, i.w, 0, 0, i.w, i.h);
        Bitmap n = Bitmap.createScaledBitmap(b, nw, nh, true);
        Img r = new Img();
        r.w = nw;
        r.h = nh;
        r.px = new int[nw * nh];
        n.getPixels(r.px, 0, nw, 0, 0, nw, nh);
        b.recycle();
        n.recycle();
        return r;
    }

    /** Opens an image / GIF / project file as a new document. */
    public static Doc docFromUri(Context c, Uri uri, String layerName) throws IOException {
        byte[] data = readAll(c, uri);
        if (isGzip(data)) {
            return ProjectIO.read(new ByteArrayInputStream(data));
        }
        if (isGif(data)) {
            GifDecoder g = GifDecoder.decode(data);
            Doc d = new Doc(g.width, g.height, Math.max(g.width, g.height) <= 128);
            d.layers.add(new LayerInfo(layerName));
            for (int i = 0; i < g.frames.size(); i++) {
                d.frames.add(new Frame(new int[][]{g.frames.get(i)}, g.delaysMs.get(i)));
            }
            d.loop = g.plays;
            return d;
        }
        Img img = decodeBitmap(data, Doc.MAX_SIDE);
        Doc d = new Doc(img.w, img.h, Math.max(img.w, img.h) <= 128);
        d.layers.add(new LayerInfo(layerName));
        d.frames.add(new Frame(new int[][]{img.px}, 100));
        return d;
    }
}
