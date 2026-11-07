package com.pixelstudio.core;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.Deflater;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Binary project format (.pxs): gzip-compressed header + raw ARGB layers. */
public final class ProjectIO {
    private static final int MAGIC = 0x50585354; // "PXST"
    private static final int VERSION = 1;

    private ProjectIO() {}

    private static final class FastGzip extends GZIPOutputStream {
        FastGzip(OutputStream o) throws IOException {
            super(o, 1 << 16);
            def.setLevel(Deflater.BEST_SPEED);
        }
    }

    public static void write(OutputStream os, Doc d) throws IOException {
        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FastGzip(os), 1 << 16));
        out.writeInt(MAGIC);
        out.writeInt(VERSION);
        out.writeInt(d.w);
        out.writeInt(d.h);
        out.writeBoolean(d.pixelArt);
        out.writeInt(d.bg);
        out.writeInt(d.loop);
        out.writeInt(d.activeFrame);
        out.writeInt(d.activeLayer);
        out.writeInt(d.layers.size());
        for (LayerInfo l : d.layers) {
            out.writeUTF(l.name);
            out.writeBoolean(l.visible);
            out.writeInt(l.opacity);
        }
        out.writeInt(d.frames.size());
        byte[] bytes = new byte[d.w * d.h * 4];
        for (Frame f : d.frames) {
            out.writeInt(f.delayMs);
            for (int li = 0; li < d.layers.size(); li++) {
                ByteBuffer.wrap(bytes).asIntBuffer().put(f.px[li]);
                out.write(bytes);
            }
        }
        out.flush();
        out.close();
    }

    public static Doc read(InputStream is) throws IOException {
        DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(is, 1 << 16), 1 << 16));
        if (in.readInt() != MAGIC) throw new IOException("not a Pixel Studio project");
        int ver = in.readInt();
        if (ver > VERSION) throw new IOException("newer project version");
        int w = in.readInt(), h = in.readInt();
        if (w <= 0 || h <= 0 || w > Doc.MAX_SIDE || h > Doc.MAX_SIDE) throw new IOException("bad size");
        Doc d = new Doc(w, h, in.readBoolean());
        d.bg = in.readInt();
        d.loop = in.readInt();
        d.activeFrame = in.readInt();
        d.activeLayer = in.readInt();
        int nl = in.readInt();
        if (nl < 1 || nl > 256) throw new IOException("bad layer count");
        List<LayerInfo> layers = new ArrayList<>();
        for (int i = 0; i < nl; i++) {
            LayerInfo l = new LayerInfo(in.readUTF());
            l.visible = in.readBoolean();
            l.opacity = in.readInt();
            layers.add(l);
        }
        d.layers = layers;
        int nf = in.readInt();
        if (nf < 1 || nf > 5000) throw new IOException("bad frame count");
        byte[] bytes = new byte[w * h * 4];
        for (int fi = 0; fi < nf; fi++) {
            int delay = in.readInt();
            int[][] px = new int[nl][];
            for (int li = 0; li < nl; li++) {
                in.readFully(bytes);
                px[li] = new int[w * h];
                ByteBuffer.wrap(bytes).asIntBuffer().get(px[li]);
            }
            d.frames.add(new Frame(px, delay));
        }
        d.clampActive();
        in.close();
        return d;
    }
}
