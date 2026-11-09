package com.pixelstudio.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
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
import java.util.ArrayList;
import java.util.List;

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

    /** Decoded media: one frame for a still image, many for a GIF or a video. */
    public static final class Media {
        public int w, h;
        public final List<int[]> frames = new ArrayList<>();
        public final List<Integer> delays = new ArrayList<>();
        /** Play count for the animation (0 = forever). */
        public int plays;
        public boolean video;

        public boolean animated() { return frames.size() > 1; }

        /** Copy that holds only frame index (or every frame when index < 0). */
        public Media pick(int index) {
            Media m = new Media();
            m.w = w;
            m.h = h;
            m.plays = plays;
            m.video = video;
            for (int i = 0; i < frames.size(); i++) {
                if (index >= 0 && i != index) continue;
                m.frames.add(frames.get(i));
                m.delays.add(delays.get(i));
            }
            return m;
        }
    }

    /** Result of {@link #load}: either a saved project or decoded media. */
    public static final class Loaded {
        public Doc project;
        public Media media;
    }

    private static final int VIDEO_MAX_SIDE = 480;
    private static final int VIDEO_MAX_FRAMES = 60;
    private static final long VIDEO_MAX_BYTES = 150L * 1024 * 1024;

    private static boolean isVideo(Context c, Uri uri) {
        String t = null;
        try {
            t = c.getContentResolver().getType(uri);
        } catch (Throwable ignored) {
        }
        return t != null && t.startsWith("video/");
    }

    /** Reads a project, still image, GIF or video from a content Uri. */
    public static Loaded load(Context c, Uri uri, int maxSide) throws IOException {
        Loaded l = new Loaded();
        if (isVideo(c, uri)) {
            l.media = decodeVideo(c, uri);
            return l;
        }
        byte[] data = readAll(c, uri);
        if (isGzip(data)) {
            l.project = ProjectIO.read(new ByteArrayInputStream(data));
            return l;
        }
        Media m = new Media();
        if (isGif(data)) {
            GifDecoder g = GifDecoder.decode(data);
            m.w = g.width;
            m.h = g.height;
            m.plays = g.plays;
            for (int i = 0; i < g.frames.size(); i++) {
                m.frames.add(g.frames.get(i));
                m.delays.add(g.delaysMs.get(i));
            }
        } else {
            Img img = decodeBitmap(data, maxSide);
            m.w = img.w;
            m.h = img.h;
            m.frames.add(img.px);
            m.delays.add(100);
        }
        l.media = m;
        return l;
    }

    /** Samples a video into frames (at most 60, longest side 480px) keeping its real duration. */
    private static Media decodeVideo(Context c, Uri uri) throws IOException {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(c, uri);
            long dur = parseLong(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION), 0);
            int vw = (int) parseLong(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH), 0);
            int vh = (int) parseLong(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT), 0);
            int rot = (int) parseLong(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION), 0);
            if (vw <= 0 || vh <= 0) throw new IOException("bad video");
            if (rot == 90 || rot == 270) {
                int t = vw;
                vw = vh;
                vh = t;
            }
            float s = Math.min(1f, (float) VIDEO_MAX_SIDE / Math.max(vw, vh));
            int w = Math.max(1, Math.round(vw * s)), h = Math.max(1, Math.round(vh * s));
            int count = (int) Math.max(1, Math.min(VIDEO_MAX_FRAMES, dur / 100));
            count = (int) Math.max(1, Math.min(count, VIDEO_MAX_BYTES / ((long) w * h * 4)));
            long stepMs = Math.max(1, dur / count);

            Media m = new Media();
            m.video = true;
            m.plays = 0;
            for (int i = 0; i < count; i++) {
                Bitmap b = r.getScaledFrameAtTime(i * stepMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, w, h);
                if (b == null) {
                    if (m.frames.isEmpty()) continue;
                    // keep timing: repeat the previous frame
                    m.frames.add(m.frames.get(m.frames.size() - 1));
                    m.delays.add((int) Math.max(20, stepMs));
                    continue;
                }
                if (m.frames.isEmpty()) {
                    m.w = b.getWidth();
                    m.h = b.getHeight();
                }
                if (b.getWidth() != m.w || b.getHeight() != m.h) {
                    Bitmap n = Bitmap.createScaledBitmap(b, m.w, m.h, true);
                    b.recycle();
                    b = n;
                }
                int[] px = new int[m.w * m.h];
                b.getPixels(px, 0, m.w, 0, 0, m.w, m.h);
                b.recycle();
                m.frames.add(px);
                m.delays.add((int) Math.max(20, stepMs));
            }
            if (m.frames.isEmpty()) throw new IOException("no video frames");
            return m;
        } catch (RuntimeException e) {
            throw new IOException("video: " + e.getMessage());
        } finally {
            r.release();
        }
    }

    private static long parseLong(String s, long def) {
        if (s == null) return def;
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** Scales media down to fit inside maxW x maxH (never up). */
    public static Media fitMedia(Media m, int maxW, int maxH) {
        if (m.w <= maxW && m.h <= maxH) return m;
        Media r = new Media();
        r.plays = m.plays;
        r.video = m.video;
        r.delays.addAll(m.delays);
        for (int[] f : m.frames) {
            Img i = new Img();
            i.w = m.w;
            i.h = m.h;
            i.px = f;
            Img o = fit(i, maxW, maxH);
            r.w = o.w;
            r.h = o.h;
            r.frames.add(o.px);
        }
        return r;
    }

    /** Builds a new document from decoded media; frameIndex < 0 keeps every frame. */
    public static Doc docFromMedia(Media src, int frameIndex, String layerName) {
        Media m = src.pick(frameIndex);
        Doc d = new Doc(m.w, m.h, Math.max(m.w, m.h) <= 128);
        d.layers.add(new LayerInfo(layerName));
        for (int i = 0; i < m.frames.size(); i++) {
            d.frames.add(new Frame(new int[][]{m.frames.get(i)}, m.delays.get(i)));
        }
        d.loop = m.plays;
        return d;
    }

    /** Opens an image / GIF / video / project file as a new document (all frames). */
    public static Doc docFromUri(Context c, Uri uri, String layerName) throws IOException {
        Loaded l = load(c, uri, Doc.MAX_SIDE);
        if (l.project != null) return l.project;
        return docFromMedia(l.media, -1, layerName);
    }
}
