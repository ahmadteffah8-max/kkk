package com.pixelstudio.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;

import com.pixelstudio.core.Doc;
import com.pixelstudio.core.Frame;
import com.pixelstudio.core.FrameSelector;
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
        /** Video only: the clip was longer than the limit and only its first part was used. */
        public boolean trimmed;
        /** Video only: length of the source clip in ms. */
        public long sourceMs;

        public boolean animated() { return frames.size() > 1; }

        /** At most max frames, evenly spaced, with the same total running time. */
        public Media thin(int max) {
            if (frames.size() <= max) return this;
            int[] d = new int[delays.size()];
            for (int i = 0; i < d.length; i++) d[i] = delays.get(i);
            int[][] t = FrameSelector.thin(d, max);
            Media m = new Media();
            m.w = w;
            m.h = h;
            m.plays = plays;
            m.video = video;
            m.trimmed = trimmed;
            m.sourceMs = sourceMs;
            for (int j = 0; j < t[0].length; j++) {
                m.frames.add(frames.get(t[0][j]));
                m.delays.add(t[1][j]);
            }
            return m;
        }

        /** Copy that holds only frame index (or every frame when index < 0). */
        public Media pick(int index) {
            Media m = new Media();
            m.w = w;
            m.h = h;
            m.plays = plays;
            m.video = video;
            m.trimmed = trimmed;
            m.sourceMs = sourceMs;
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

    /** Reports progress of a long import and lets the person cancel it. Called from the worker thread. */
    public interface Progress {
        /** 0..100 */
        void onProgress(int percent);
        boolean cancelled();
    }

    /** Thrown when the person cancelled a running import. */
    public static final class CancelledException extends IOException {
        public CancelledException() { super("cancelled"); }
    }

    /** Longest part of a video that is imported. */
    public static final long VIDEO_MAX_MS = 60_000L;
    private static final int VIDEO_MAX_SIDE = 480;
    private static final int VIDEO_ANALYSIS_SIDE = 64;
    private static final int VIDEO_MAX_CANDIDATES = 300;
    private static final long VIDEO_MIN_STEP_MS = 100;
    private static final int VIDEO_MAX_KEEP = 200;
    private static final long VIDEO_BUDGET_BYTES = 128L * 1024 * 1024;

    private static boolean isVideo(Context c, Uri uri) {
        String t = null;
        try {
            t = c.getContentResolver().getType(uri);
        } catch (Throwable ignored) {
        }
        if (t != null && t.startsWith("video/")) return true;
        if (t != null && t.startsWith("image/")) return false;
        return sniffVideo(c, uri);
    }

    /** Looks at the first bytes (MP4/MOV/3GP "ftyp", WebM/MKV, AVI) when the provider gives no usable MIME type. */
    private static boolean sniffVideo(Context c, Uri uri) {
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            if (in == null) return false;
            byte[] h = new byte[12];
            int n = 0, r;
            while (n < h.length && (r = in.read(h, n, h.length - n)) > 0) n += r;
            if (n < 12) return false;
            if (h[4] == 'f' && h[5] == 't' && h[6] == 'y' && h[7] == 'p') return true;
            if ((h[0] & 255) == 0x1A && (h[1] & 255) == 0x45 && (h[2] & 255) == 0xDF && (h[3] & 255) == 0xA3) return true;
            return h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F' && h[8] == 'A' && h[9] == 'V' && h[10] == 'I';
        } catch (Throwable t) {
            return false;
        }
    }

    /** Reads a project, still image, GIF or video from a content Uri. */
    public static Loaded load(Context c, Uri uri, int maxSide) throws IOException {
        return load(c, uri, maxSide, null);
    }

    public static Loaded load(Context c, Uri uri, int maxSide, Progress pg) throws IOException {
        Loaded l = new Loaded();
        if (isVideo(c, uri)) {
            l.media = decodeVideo(c, uri, pg);
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

    private static int[] pixelsOf(Bitmap b, int w, int h) {
        if (b.getWidth() != w || b.getHeight() != h) {
            Bitmap n = Bitmap.createScaledBitmap(b, w, h, true);
            if (n != b) b.recycle();
            b = n;
        }
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        b.recycle();
        return px;
    }

    private static void checkCancel(Progress pg) throws CancelledException {
        if (pg != null && pg.cancelled()) throw new CancelledException();
    }

    /**
     * Turns a video into editable frames. At most the first 60 seconds are used. The clip is sampled at an even
     * rate into tiny analysis snapshots, {@link FrameSelector} drops the ones that look the same as the last kept
     * frame (their time is added to the kept frame's duration) while keeping every visible change, and only the
     * chosen frames are then read at full size. Timing of the clip is preserved.
     */
    private static Media decodeVideo(Context c, Uri uri, Progress pg) throws IOException {
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
            if (dur <= 0) dur = 1;
            boolean trimmed = dur > VIDEO_MAX_MS;
            long useMs = Math.min(dur, VIDEO_MAX_MS);

            float s = Math.min(1f, (float) VIDEO_MAX_SIDE / Math.max(vw, vh));
            int w = Math.max(1, Math.round(vw * s)), h = Math.max(1, Math.round(vh * s));
            float as = Math.min(1f, (float) VIDEO_ANALYSIS_SIDE / Math.max(w, h));
            int aw = Math.max(1, Math.round(w * as)), ah = Math.max(1, Math.round(h * as));

            long step = Math.max(VIDEO_MIN_STEP_MS, (useMs + VIDEO_MAX_CANDIDATES - 1) / VIDEO_MAX_CANDIDATES);
            int count = (int) Math.max(1, useMs / step);

            // pass 1: tiny snapshots of evenly spaced moments (0..70 % of the work)
            int[][] thumbs = new int[count][];
            long[] times = new long[count];
            boolean any = false;
            for (int i = 0; i < count; i++) {
                checkCancel(pg);
                times[i] = i * step;
                Bitmap b = null;
                try {
                    b = r.getScaledFrameAtTime(times[i] * 1000L, MediaMetadataRetriever.OPTION_CLOSEST, aw, ah);
                } catch (RuntimeException ignored) {
                }
                if (b != null) {
                    thumbs[i] = pixelsOf(b, aw, ah);
                    any = true;
                }
                if (pg != null) pg.onProgress((int) ((i + 1) * 70L / count));
            }
            if (!any) throw new IOException("no video frames");

            long perFrame = (long) w * h * 4L;
            int maxKeep = (int) Math.max(4, Math.min(VIDEO_MAX_KEEP, VIDEO_BUDGET_BYTES / perFrame));
            FrameSelector.Result sel = FrameSelector.select(thumbs, times, useMs, maxKeep);

            // pass 2: full size only for the frames worth keeping (70..100 %)
            Media m = new Media();
            m.video = true;
            m.plays = 0;
            m.trimmed = trimmed;
            m.sourceMs = dur;
            long carry = 0;
            for (int j = 0; j < sel.keep.length; j++) {
                checkCancel(pg);
                Bitmap b = null;
                try {
                    b = r.getScaledFrameAtTime(times[sel.keep[j]] * 1000L, MediaMetadataRetriever.OPTION_CLOSEST, w, h);
                } catch (RuntimeException ignored) {
                }
                int delay = (int) Math.min(FrameSelector.MAX_DELAY_MS, sel.delaysMs[j] + carry);
                if (b == null) {
                    if (m.frames.isEmpty()) {
                        carry += sel.delaysMs[j]; // nothing to repeat yet: give its time to the next frame
                    } else {
                        // keep the timing: lengthen the previous frame
                        int last = m.delays.size() - 1;
                        m.delays.set(last, (int) Math.min(FrameSelector.MAX_DELAY_MS, m.delays.get(last) + delay));
                        carry = 0;
                    }
                } else {
                    if (m.frames.isEmpty()) {
                        m.w = b.getWidth();
                        m.h = b.getHeight();
                    }
                    m.frames.add(pixelsOf(b, m.w, m.h));
                    m.delays.add(delay);
                    carry = 0;
                }
                if (pg != null) pg.onProgress(70 + (int) ((j + 1) * 30L / sel.keep.length));
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
        r.trimmed = m.trimmed;
        r.sourceMs = m.sourceMs;
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
