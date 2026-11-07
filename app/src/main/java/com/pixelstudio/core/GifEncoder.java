package com.pixelstudio.core;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Animated GIF89a writer: global palette (exact when &lt;= 255 colors, median cut otherwise),
 * 1-bit transparency, per-frame delay, NETSCAPE loop extension, cropped frame rectangles.
 */
public final class GifEncoder {
    public interface Progress { void onFrame(int done, int total); }

    private GifEncoder() {}

    /**
     * @param frames ARGB full-canvas frames (alpha &lt; 128 is treated as transparent)
     * @param delays per-frame delay in ms
     * @param plays  number of plays; 0 = forever, 1 = once (no loop extension)
     */
    public static void write(OutputStream out, int w, int h, List<int[]> frames, int[] delays, int plays, Progress progress)
            throws IOException {
        int n = frames.size();
        if (n == 0) throw new IOException("no frames");

        // ---- palette
        boolean hasTransparent = false;
        int[] exact = new int[257];
        int exactCount = 0;
        boolean exactOk = true;
        int[] hist = new int[32768];
        long[] sumR = new long[32768], sumG = new long[32768], sumB = new long[32768];
        for (int[] f : frames) {
            for (int c : f) {
                if ((c >>> 24) < 128) { hasTransparent = true; continue; }
                int rgb = c & 0xFFFFFF;
                if (exactOk) {
                    boolean found = false;
                    for (int i = 0; i < exactCount; i++) if (exact[i] == rgb) { found = true; break; }
                    if (!found) {
                        if (exactCount >= 256) exactOk = false; else exact[exactCount++] = rgb;
                    }
                }
                int r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, b = rgb & 255;
                int key = ((r >> 3) << 10) | ((g >> 3) << 5) | (b >> 3);
                hist[key]++;
                sumR[key] += r; sumG[key] += g; sumB[key] += b;
            }
        }
        int maxColors = hasTransparent ? 255 : 256;
        int[] palette;
        boolean exactMode = exactOk && exactCount <= maxColors;
        int[] lookup = null;
        if (exactMode) {
            palette = Arrays.copyOf(exact, Math.max(exactCount, 1));
        } else {
            palette = medianCut(hist, sumR, sumG, sumB, maxColors);
            lookup = new int[32768];
            Arrays.fill(lookup, -1);
        }
        int colorCount = palette.length + (hasTransparent ? 1 : 0);
        int transIndex = hasTransparent ? palette.length : -1;
        int bits = 1;
        while ((1 << bits) < colorCount) bits++;
        if (bits < 1) bits = 1;
        int tableSize = 1 << bits;

        // ---- header
        out.write(new byte[]{'G', 'I', 'F', '8', '9', 'a'});
        put16(out, w);
        put16(out, h);
        out.write(0x80 | 0x70 | (bits - 1)); // global table, 8-bit color resolution, size
        out.write(0); // bg index
        out.write(0); // aspect
        for (int i = 0; i < tableSize; i++) {
            int c = i < palette.length ? palette[i] : 0;
            out.write((c >> 16) & 255);
            out.write((c >> 8) & 255);
            out.write(c & 255);
        }
        if (plays != 1) {
            int repeats = plays <= 0 ? 0 : plays - 1;
            out.write(new byte[]{0x21, (byte) 0xFF, 0x0B, 'N', 'E', 'T', 'S', 'C', 'A', 'P', 'E', '2', '.', '0', 3, 1});
            put16(out, repeats);
            out.write(0);
        }

        // ---- frames
        byte[] idx = new byte[w * h];
        byte[] prev = null;
        for (int fi = 0; fi < n; fi++) {
            int[] f = frames.get(fi);
            for (int i = 0; i < f.length; i++) {
                int c = f[i];
                if ((c >>> 24) < 128) { idx[i] = (byte) transIndex; continue; }
                idx[i] = (byte) mapColor(c & 0xFFFFFF, palette, exactMode, lookup);
            }
            int x0 = 0, y0 = 0, x1 = w, y1 = h;
            int disposal;
            if (hasTransparent) {
                disposal = 2; // each frame is a full composite: clear before the next one
                int[] bb = opaqueBounds(idx, w, h, transIndex);
                if (bb == null) { x0 = 0; y0 = 0; x1 = 1; y1 = 1; } else { x0 = bb[0]; y0 = bb[1]; x1 = bb[2]; y1 = bb[3]; }
            } else {
                disposal = 1;
                if (prev != null) {
                    int[] bb = diffBounds(prev, idx, w, h);
                    if (bb == null) { x0 = 0; y0 = 0; x1 = 1; y1 = 1; } else { x0 = bb[0]; y0 = bb[1]; x1 = bb[2]; y1 = bb[3]; }
                }
            }
            int fw = x1 - x0, fh = y1 - y0;
            int delay = Math.max(2, (int) Math.round(delays[fi] / 10.0));
            out.write(0x21); out.write(0xF9); out.write(4);
            out.write((disposal << 2) | (hasTransparent ? 1 : 0));
            put16(out, delay);
            out.write(hasTransparent ? transIndex : 0);
            out.write(0);
            out.write(0x2C);
            put16(out, x0); put16(out, y0); put16(out, fw); put16(out, fh);
            out.write(0);
            byte[] sub = new byte[fw * fh];
            for (int y = 0; y < fh; y++) System.arraycopy(idx, (y0 + y) * w + x0, sub, y * fw, fw);
            lzw(out, sub, Math.max(2, bits));
            if (!hasTransparent) {
                if (prev == null) prev = new byte[w * h];
                System.arraycopy(idx, 0, prev, 0, idx.length);
            }
            if (progress != null) progress.onFrame(fi + 1, n);
        }
        out.write(0x3B);
        out.flush();
    }

    private static int mapColor(int rgb, int[] palette, boolean exact, int[] lookup) {
        if (exact) {
            for (int i = 0; i < palette.length; i++) if (palette[i] == rgb) return i;
            return 0;
        }
        int r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, b = rgb & 255;
        int key = ((r >> 3) << 10) | ((g >> 3) << 5) | (b >> 3);
        int v = lookup[key];
        if (v >= 0) return v;
        int best = 0, bestD = Integer.MAX_VALUE;
        for (int i = 0; i < palette.length; i++) {
            int pr = (palette[i] >> 16) & 255, pg = (palette[i] >> 8) & 255, pb = palette[i] & 255;
            int d = (pr - r) * (pr - r) + (pg - g) * (pg - g) + (pb - b) * (pb - b);
            if (d < bestD) { bestD = d; best = i; }
        }
        lookup[key] = best;
        return best;
    }

    private static int[] opaqueBounds(byte[] idx, int w, int h, int trans) {
        int minX = w, minY = h, maxX = -1, maxY = -1;
        byte t = (byte) trans;
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                if (idx[row + x] != t) {
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
            }
        }
        return maxX < 0 ? null : new int[]{minX, minY, maxX + 1, maxY + 1};
    }

    private static int[] diffBounds(byte[] a, byte[] b, int w, int h) {
        int minX = w, minY = h, maxX = -1, maxY = -1;
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                if (a[row + x] != b[row + x]) {
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
            }
        }
        return maxX < 0 ? null : new int[]{minX, minY, maxX + 1, maxY + 1};
    }

    // ------------------------------------------------------------ median cut

    private static int[] medianCut(int[] hist, long[] sr, long[] sg, long[] sb, int maxColors) {
        List<Integer> keys = new ArrayList<>();
        for (int i = 0; i < hist.length; i++) if (hist[i] > 0) keys.add(i);
        int[] kr = new int[hist.length], kg = new int[hist.length], kb = new int[hist.length];
        for (int k : keys) {
            kr[k] = (int) (sr[k] / hist[k]);
            kg[k] = (int) (sg[k] / hist[k]);
            kb[k] = (int) (sb[k] / hist[k]);
        }
        List<List<Integer>> boxes = new ArrayList<>();
        boxes.add(keys);
        while (boxes.size() < maxColors) {
            int bi = -1;
            long bestScore = 0;
            int bestCh = 0;
            for (int i = 0; i < boxes.size(); i++) {
                List<Integer> b = boxes.get(i);
                if (b.size() < 2) continue;
                int[] mn = {255, 255, 255}, mx = {0, 0, 0};
                long pop = 0;
                for (int k : b) {
                    pop += hist[k];
                    mn[0] = Math.min(mn[0], kr[k]); mx[0] = Math.max(mx[0], kr[k]);
                    mn[1] = Math.min(mn[1], kg[k]); mx[1] = Math.max(mx[1], kg[k]);
                    mn[2] = Math.min(mn[2], kb[k]); mx[2] = Math.max(mx[2], kb[k]);
                }
                int ch = 0, range = mx[0] - mn[0];
                if (mx[1] - mn[1] > range) { ch = 1; range = mx[1] - mn[1]; }
                if (mx[2] - mn[2] > range) { ch = 2; range = mx[2] - mn[2]; }
                long score = (long) range * Math.max(1, (long) Math.sqrt(pop));
                if (score > bestScore) { bestScore = score; bi = i; bestCh = ch; }
            }
            if (bi < 0) break;
            List<Integer> box = boxes.remove(bi);
            final int[] arr = (bestCh == 0) ? kr : (bestCh == 1 ? kg : kb);
            box.sort((p, q) -> Integer.compare(arr[p], arr[q]));
            long total = 0;
            for (int k : box) total += hist[k];
            long acc = 0;
            int split = 1;
            for (int i = 0; i < box.size(); i++) {
                acc += hist[box.get(i)];
                if (acc * 2 >= total) { split = Math.max(1, Math.min(i + 1, box.size() - 1)); break; }
            }
            boxes.add(new ArrayList<>(box.subList(0, split)));
            boxes.add(new ArrayList<>(box.subList(split, box.size())));
        }
        int[] pal = new int[boxes.size()];
        for (int i = 0; i < pal.length; i++) {
            long r = 0, g = 0, b = 0, c = 0;
            for (int k : boxes.get(i)) { r += sr[k]; g += sg[k]; b += sb[k]; c += hist[k]; }
            if (c == 0) c = 1;
            pal[i] = ((int) (r / c) << 16) | ((int) (g / c) << 8) | (int) (b / c);
        }
        return pal;
    }

    // ------------------------------------------------------------ LZW

    private static void put16(OutputStream o, int v) throws IOException {
        o.write(v & 255);
        o.write((v >> 8) & 255);
    }

    private static final class BitWriter {
        final OutputStream out;
        final byte[] block = new byte[255];
        int blockLen;
        int cur, curBits;
        BitWriter(OutputStream o) { out = o; }

        void write(int code, int nbits) throws IOException {
            cur |= code << curBits;
            curBits += nbits;
            while (curBits >= 8) {
                emit(cur & 255);
                cur >>>= 8;
                curBits -= 8;
            }
        }
        private void emit(int b) throws IOException {
            block[blockLen++] = (byte) b;
            if (blockLen == 255) flushBlock();
        }
        private void flushBlock() throws IOException {
            if (blockLen > 0) {
                out.write(blockLen);
                out.write(block, 0, blockLen);
                blockLen = 0;
            }
        }
        void finish() throws IOException {
            if (curBits > 0) { emit(cur & 255); cur = 0; curBits = 0; }
            flushBlock();
            out.write(0);
        }
    }

    private static void lzw(OutputStream out, byte[] data, int minCodeSize) throws IOException {
        out.write(minCodeSize);
        BitWriter bw = new BitWriter(out);
        int clear = 1 << minCodeSize, eoi = clear + 1;
        int codeSize = minCodeSize + 1;
        int next = eoi + 1;
        final int HSIZE = 8209;
        int[] hKey = new int[HSIZE];
        int[] hVal = new int[HSIZE];
        Arrays.fill(hKey, -1);
        bw.write(clear, codeSize);
        int prefix = data[0] & 255;
        for (int i = 1; i < data.length; i++) {
            int c = data[i] & 255;
            int key = (prefix << 8) | c;
            int hidx = (key * 31 + (key >>> 7)) % HSIZE;
            if (hidx < 0) hidx += HSIZE;
            int found = -1;
            while (hKey[hidx] != -1) {
                if (hKey[hidx] == key) { found = hVal[hidx]; break; }
                hidx = (hidx + 1) % HSIZE;
            }
            if (found >= 0) {
                prefix = found;
            } else {
                bw.write(prefix, codeSize);
                if (next < 4096) {
                    hKey[hidx] = key;
                    hVal[hidx] = next;
                    if (next == (1 << codeSize) && codeSize < 12) codeSize++;
                    next++;
                } else {
                    bw.write(clear, codeSize);
                    Arrays.fill(hKey, -1);
                    codeSize = minCodeSize + 1;
                    next = eoi + 1;
                }
                prefix = c;
            }
        }
        bw.write(prefix, codeSize);
        // decoder adds one more entry after this code; keep code size in sync
        if (next < 4096 && next == (1 << codeSize) && codeSize < 12) codeSize++;
        bw.write(eoi, codeSize);
        bw.finish();
    }
}
