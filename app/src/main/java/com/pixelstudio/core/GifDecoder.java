package com.pixelstudio.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** GIF87a/89a decoder producing full-canvas ARGB frames (disposal methods applied). */
public final class GifDecoder {
    public int width, height;
    /** Number of plays; 0 = forever. */
    public int plays = 1;
    public final List<int[]> frames = new ArrayList<>();
    public final List<Integer> delaysMs = new ArrayList<>();

    private byte[] d;
    private int pos;

    public static GifDecoder decode(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int r;
        while ((r = in.read(buf)) > 0) bo.write(buf, 0, r);
        return decode(bo.toByteArray());
    }

    public static GifDecoder decode(byte[] data) throws IOException {
        GifDecoder g = new GifDecoder();
        g.d = data;
        try {
            g.run();
        } catch (ArrayIndexOutOfBoundsException e) {
            if (g.frames.isEmpty()) throw new IOException("corrupt gif");
        }
        if (g.frames.isEmpty()) throw new IOException("no frames");
        return g;
    }

    private int u8() { return d[pos++] & 255; }
    private int u16() { int a = u8(); return a | (u8() << 8); }

    private int[] readTable(int count) {
        int[] t = new int[count];
        for (int i = 0; i < count; i++) {
            int r = u8(), g = u8(), b = u8();
            t[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
        }
        return t;
    }

    private void skipBlocks() {
        int n;
        while ((n = u8()) != 0) pos += n;
    }

    private void run() throws IOException {
        if (d.length < 13 || d[0] != 'G' || d[1] != 'I' || d[2] != 'F') throw new IOException("not a gif");
        pos = 6;
        width = u16();
        height = u16();
        if (width <= 0 || height <= 0 || width > Doc.MAX_SIDE || height > Doc.MAX_SIDE) throw new IOException("size");
        int flags = u8();
        u8(); u8();
        int[] gct = null;
        if ((flags & 0x80) != 0) gct = readTable(1 << ((flags & 7) + 1));

        int[] canvas = new int[width * height];
        int[] backup = null;
        int disposal = 0, transIdx = -1, delay = 100;
        int prevDisposal = 0, px = 0, py = 0, pw = 0, ph = 0;
        long budget = 0;

        while (pos < d.length) {
            int b = u8();
            if (b == 0x3B) break;
            if (b == 0x21) {
                int label = u8();
                if (label == 0xF9) {
                    u8();
                    int pf = u8();
                    disposal = (pf >> 2) & 7;
                    delay = u16() * 10;
                    if (delay <= 0) delay = 100;
                    int ti = u8();
                    transIdx = (pf & 1) != 0 ? ti : -1;
                    u8();
                } else if (label == 0xFF) {
                    int n = u8();
                    boolean netscape = n == 11 && d[pos] == 'N' && d[pos + 1] == 'E' && d[pos + 2] == 'T';
                    pos += n;
                    int sub;
                    while ((sub = u8()) != 0) {
                        if (netscape && sub >= 3 && d[pos] == 1) {
                            int loops = (d[pos + 1] & 255) | ((d[pos + 2] & 255) << 8);
                            plays = loops == 0 ? 0 : loops + 1;
                        }
                        pos += sub;
                    }
                } else {
                    skipBlocks();
                }
            } else if (b == 0x2C) {
                int ix = u16(), iy = u16(), iw = u16(), ih = u16();
                int lf = u8();
                int[] ct = gct;
                if ((lf & 0x80) != 0) ct = readTable(1 << ((lf & 7) + 1));
                boolean interlace = (lf & 0x40) != 0;
                int minCode = u8();
                ByteArrayOutputStream data = new ByteArrayOutputStream();
                int n;
                while ((n = u8()) != 0) { data.write(d, pos, n); pos += n; }
                byte[] idx = lzwDecode(data.toByteArray(), minCode, iw * ih);

                budget += (long) width * height * 4;
                if (budget > 300L * 1024 * 1024) throw new IOException("gif too large");

                // apply disposal of the previous frame
                if (!frames.isEmpty()) {
                    if (prevDisposal == 2) {
                        for (int y = py; y < Math.min(height, py + ph); y++)
                            for (int x = px; x < Math.min(width, px + pw); x++) canvas[y * width + x] = 0;
                    } else if (prevDisposal == 3 && backup != null) {
                        System.arraycopy(backup, 0, canvas, 0, canvas.length);
                    }
                }
                if (disposal == 3) backup = canvas.clone();

                int[] rowOrder = rowOrder(ih, interlace);
                for (int r = 0; r < ih; r++) {
                    int y = iy + rowOrder[r];
                    if (y < 0 || y >= height) continue;
                    for (int x = 0; x < iw; x++) {
                        int cx = ix + x;
                        if (cx >= width) break;
                        int ci = idx[r * iw + x] & 255;
                        if (ci == transIdx || ct == null || ci >= ct.length) continue;
                        canvas[y * width + cx] = ct[ci];
                    }
                }
                frames.add(canvas.clone());
                delaysMs.add(delay);
                prevDisposal = disposal;
                px = ix; py = iy; pw = iw; ph = ih;
                disposal = 0; transIdx = -1; delay = 100;
            } else {
                // unknown block; stop
                break;
            }
        }
    }

    private static int[] rowOrder(int h, boolean interlace) {
        int[] order = new int[h];
        if (!interlace) {
            for (int i = 0; i < h; i++) order[i] = i;
            return order;
        }
        int k = 0;
        int[] start = {0, 4, 2, 1}, step = {8, 8, 4, 2};
        for (int p = 0; p < 4; p++) {
            for (int y = start[p]; y < h; y += step[p]) order[k++] = y;
        }
        return order;
    }

    private static byte[] lzwDecode(byte[] data, int minCode, int outLen) {
        byte[] out = new byte[outLen];
        if (minCode < 1 || minCode > 11) return out;
        int clear = 1 << minCode, eoi = clear + 1;
        int codeSize = minCode + 1;
        int[] prefix = new int[4096];
        byte[] suffix = new byte[4096];
        byte[] stack = new byte[4097];
        int next = eoi + 1;
        for (int i = 0; i < clear; i++) suffix[i] = (byte) i;
        int old = -1;
        byte first = 0;
        int bitBuf = 0, bits = 0, p = 0, o = 0;
        while (o < outLen) {
            while (bits < codeSize && p < data.length) {
                bitBuf |= (data[p++] & 255) << bits;
                bits += 8;
            }
            if (bits < codeSize) break;
            int code = bitBuf & ((1 << codeSize) - 1);
            bitBuf >>>= codeSize;
            bits -= codeSize;
            if (code == clear) {
                codeSize = minCode + 1;
                next = eoi + 1;
                old = -1;
                continue;
            }
            if (code == eoi) break;
            if (old == -1) {
                if (code >= clear) break;
                out[o++] = suffix[code];
                old = code;
                first = suffix[code];
                continue;
            }
            int in = code;
            int sp = 0;
            if (code >= next) {
                if (code > next) break;
                stack[sp++] = first;
                code = old;
            }
            while (code >= clear) {
                if (sp >= 4096) return out;
                stack[sp++] = suffix[code];
                code = prefix[code];
            }
            first = suffix[code];
            stack[sp++] = first;
            if (next < 4096) {
                prefix[next] = old;
                suffix[next] = first;
                next++;
                if (next == (1 << codeSize) && codeSize < 12) codeSize++;
            }
            old = in;
            while (sp > 0 && o < outLen) out[o++] = stack[--sp];
        }
        return out;
    }
}
