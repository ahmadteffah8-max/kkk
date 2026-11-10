package com.pixelstudio.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Chooses which sampled video frames are worth keeping as animation frames.
 *
 * Every candidate is compared with the last frame that was kept. A candidate becomes a new frame only when it
 * differs visibly (average change, or a noticeable share of pixels changing, which catches a small object moving
 * over a still background). Near-identical candidates are dropped and the time they covered is added to the
 * kept frame's duration, so the animation keeps its real timing while static stretches cost one frame. Slow
 * motion still produces frames because the difference accumulates against the last kept frame until it shows.
 *
 * If more than maxKeep frames would be kept, the thresholds are raised step by step until the count fits.
 */
public final class FrameSelector {
    private FrameSelector() {}

    public static final int MIN_DELAY_MS = 20, MAX_DELAY_MS = 60000;
    /** Average per-channel change (0..255) that counts as a visible change. */
    static final float MEAN_THRESHOLD = 2.5f;
    /** Share of pixels with a clear change that counts as a visible change. */
    static final float FRACTION_THRESHOLD = 0.012f;
    /** A pixel counts as clearly changed above this largest-channel difference. */
    static final int PIXEL_DELTA = 32;

    public static final class Result {
        /** Indices of the kept candidates (ascending, always starts with the first valid one). */
        public int[] keep;
        /** Display time of each kept frame in ms; together they cover the whole clip. */
        public int[] delaysMs;
    }

    /**
     * @param thumbs  small RGB snapshots of the candidates (null entries are treated as unchanged)
     * @param timesMs capture time of each candidate in ms (ascending)
     * @param endMs   end of the clip, used for the duration of the last kept frame
     * @param maxKeep upper bound for the number of frames returned (at least 1)
     */
    public static Result select(int[][] thumbs, long[] timesMs, long endMs, int maxKeep) {
        int n = thumbs.length;
        maxKeep = Math.max(1, maxKeep);
        Result r = new Result();
        if (n == 0) {
            r.keep = new int[0];
            r.delaysMs = new int[0];
            return r;
        }
        int[][] t = new int[n][];
        int firstValid = -1;
        for (int i = 0; i < n; i++) if (thumbs[i] != null) { firstValid = i; break; }
        if (firstValid < 0) {
            r.keep = new int[]{0};
            r.delaysMs = new int[]{clampDelay(endMs - timesMs[0])};
            return r;
        }
        int[] prev = thumbs[firstValid];
        for (int i = 0; i < n; i++) {
            if (thumbs[i] != null) prev = thumbs[i];
            t[i] = prev;
        }

        List<Integer> keep = greedy(t, 1f);
        float scale = 1f;
        while (keep.size() > maxKeep && scale < 4000f) {
            scale *= 1.35f;
            keep = greedy(t, scale);
        }
        if (keep.size() > maxKeep) keep = even(n, maxKeep);

        int k = keep.size();
        r.keep = new int[k];
        r.delaysMs = new int[k];
        for (int j = 0; j < k; j++) {
            r.keep[j] = keep.get(j);
            long start = timesMs[r.keep[j]];
            long next = j + 1 < k ? timesMs[keep.get(j + 1)] : endMs;
            r.delaysMs[j] = clampDelay(next - start);
        }
        return r;
    }

    /**
     * Reduces a frame list to at most max frames, evenly spaced, keeping the total running time:
     * returns {indices of kept frames, their new delays}.
     */
    public static int[][] thin(int[] delaysMs, int max) {
        int n = delaysMs.length;
        max = Math.max(1, max);
        if (n <= max) {
            int[] idx = new int[n];
            for (int i = 0; i < n; i++) idx[i] = i;
            return new int[][]{idx, delaysMs.clone()};
        }
        int[] idx = new int[max], d = new int[max];
        for (int j = 0; j < max; j++) {
            int from = (int) ((long) j * n / max), to = (int) ((long) (j + 1) * n / max);
            idx[j] = from;
            long sum = 0;
            for (int i = from; i < to; i++) sum += delaysMs[i];
            d[j] = clampDelay(sum);
        }
        return new int[][]{idx, d};
    }

    private static int clampDelay(long ms) {
        return (int) Math.max(MIN_DELAY_MS, Math.min(MAX_DELAY_MS, ms));
    }

    private static List<Integer> greedy(int[][] t, float scale) {
        List<Integer> keep = new ArrayList<>();
        keep.add(0);
        int last = 0;
        float meanT = MEAN_THRESHOLD * scale, fracT = FRACTION_THRESHOLD * scale;
        for (int i = 1; i < t.length; i++) {
            if (t[i] == t[last]) continue;
            float[] d = difference(t[last], t[i]);
            if (d[0] > meanT || d[1] > fracT) {
                keep.add(i);
                last = i;
            }
        }
        return keep;
    }

    private static List<Integer> even(int n, int count) {
        List<Integer> out = new ArrayList<>();
        if (count >= n) {
            for (int i = 0; i < n; i++) out.add(i);
            return out;
        }
        for (int j = 0; j < count; j++) {
            int idx = (int) ((long) j * n / count);
            if (out.isEmpty() || out.get(out.size() - 1) != idx) out.add(idx);
        }
        return out;
    }

    /** {average change per channel (0..255), fraction of clearly changed pixels (0..1)} between two same-size snapshots. */
    public static float[] difference(int[] a, int[] b) {
        int n = Math.min(a.length, b.length);
        if (n == 0) return new float[]{0f, 0f};
        long sum = 0;
        int changed = 0;
        for (int i = 0; i < n; i++) {
            int p = a[i], q = b[i];
            if (p == q) continue;
            int dr = Math.abs(((p >> 16) & 255) - ((q >> 16) & 255));
            int dg = Math.abs(((p >> 8) & 255) - ((q >> 8) & 255));
            int db = Math.abs((p & 255) - (q & 255));
            sum += dr + dg + db;
            if (Math.max(dr, Math.max(dg, db)) > PIXEL_DELTA) changed++;
        }
        return new float[]{sum / (3f * n), changed / (float) n};
    }
}
