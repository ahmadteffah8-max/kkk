package com.pixelstudio.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Document model: fixed canvas size, a shared list of layers and a list of frames.
 * Structural operations never mutate existing pixel arrays in place, so snapshots
 * (see {@link Snapshot}) stay valid for undo/redo.
 */
public final class Doc {
    public static final int MAX_SIDE = 2048;

    public int w, h;
    public boolean pixelArt;
    /** Default export background (0 = transparent). */
    public int bg;
    /** Number of times the animation plays; 0 = forever. */
    public int loop;
    public int activeFrame, activeLayer;
    public List<LayerInfo> layers = new ArrayList<>();
    public List<Frame> frames = new ArrayList<>();

    public Doc(int w, int h, boolean pixelArt) {
        this.w = w;
        this.h = h;
        this.pixelArt = pixelArt;
    }

    /** New document with one layer and one frame. fill = ARGB layer fill (0 = transparent). */
    public static Doc create(int w, int h, boolean pixelArt, int fill, String layerName) {
        Doc d = new Doc(w, h, pixelArt);
        d.layers.add(new LayerInfo(layerName));
        int[] a = new int[w * h];
        if (fill != 0) java.util.Arrays.fill(a, fill);
        d.frames.add(new Frame(new int[][]{a}, 100));
        d.bg = fill;
        return d;
    }

    public Frame frame() { return frames.get(activeFrame); }
    public int[] layerPixels() { return frame().px[activeLayer]; }
    public int pixelCount() { return w * h; }

    public void clampActive() {
        if (activeFrame >= frames.size()) activeFrame = frames.size() - 1;
        if (activeFrame < 0) activeFrame = 0;
        if (activeLayer >= layers.size()) activeLayer = layers.size() - 1;
        if (activeLayer < 0) activeLayer = 0;
    }

    // ---------------------------------------------------------------- compositing

    /** Composite the visible layers of frame f for the given region into out (w*h array). */
    public void composeRegion(Frame f, int rx, int ry, int rw, int rh, int[] out) {
        int x0 = Math.max(0, rx), y0 = Math.max(0, ry);
        int x1 = Math.min(w, rx + rw), y1 = Math.min(h, ry + rh);
        if (x1 <= x0 || y1 <= y0) return;
        int n = layers.size();
        for (int y = y0; y < y1; y++) {
            int row = y * w;
            for (int x = x0; x < x1; x++) out[row + x] = 0;
        }
        for (int li = 0; li < n; li++) {
            LayerInfo info = layers.get(li);
            if (!info.visible || info.opacity == 0) continue;
            int[] src = f.px[li];
            int op = info.opacity;
            for (int y = y0; y < y1; y++) {
                int row = y * w;
                for (int x = x0; x < x1; x++) {
                    int s = src[row + x];
                    if ((s >>> 24) == 0) continue;
                    if (op < 255) s = Blend.scaleAlpha(s, op);
                    int d = out[row + x];
                    out[row + x] = (d == 0) ? s : Blend.over(d, s);
                }
            }
        }
    }

    public int[] compose(Frame f) {
        int[] out = new int[w * h];
        composeRegion(f, 0, 0, w, h, out);
        return out;
    }

    // ---------------------------------------------------------------- layers

    public void addLayer(int index, String name) {
        index = Math.max(0, Math.min(index, layers.size()));
        layers.add(index, new LayerInfo(name));
        for (Frame f : frames) {
            int[][] n = new int[layers.size()][];
            for (int i = 0, j = 0; i < n.length; i++) {
                n[i] = (i == index) ? new int[w * h] : f.px[j++];
            }
            f.px = n;
        }
        activeLayer = index;
    }

    public void removeLayer(int index) {
        if (layers.size() <= 1) return;
        layers.remove(index);
        for (Frame f : frames) {
            int[][] n = new int[layers.size()][];
            for (int i = 0, j = 0; j < f.px.length; j++) {
                if (j != index) n[i++] = f.px[j];
            }
            f.px = n;
        }
        clampActive();
    }

    public void duplicateLayer(int index) {
        LayerInfo c = layers.get(index).copy();
        c.name = c.name + " +";
        layers.add(index + 1, c);
        for (Frame f : frames) {
            int[][] n = new int[layers.size()][];
            for (int i = 0, j = 0; i < n.length; i++) {
                if (i == index + 1) n[i] = f.px[index].clone();
                else n[i] = f.px[j++];
            }
            f.px = n;
        }
        activeLayer = index + 1;
    }

    /** Moves a layer to a new position (index 0 = bottom). */
    public void moveLayer(int from, int to) {
        if (from == to || to < 0 || to >= layers.size()) return;
        LayerInfo li = layers.remove(from);
        layers.add(to, li);
        for (Frame f : frames) {
            int[] a = f.px[from];
            int[][] n = new int[f.px.length][];
            int k = 0;
            for (int i = 0; i < n.length; i++) {
                if (i == to) { n[i] = a; continue; }
                if (k == from) k++;
                n[i] = f.px[k++];
            }
            f.px = n;
        }
        activeLayer = to;
    }

    /** Merges layer index into the one below it (index-1) respecting visibility and opacity. */
    public void mergeDown(int index) {
        if (index <= 0 || index >= layers.size()) return;
        LayerInfo top = layers.get(index), low = layers.get(index - 1);
        for (Frame f : frames) {
            int[] a = f.px[index - 1].clone();
            int[] b = f.px[index];
            if (top.visible) {
                for (int i = 0; i < a.length; i++) {
                    int s = b[i];
                    if ((s >>> 24) == 0) continue;
                    s = Blend.scaleAlpha(s, top.opacity);
                    a[i] = Blend.over(a[i], s);
                }
            }
            int[][] n = new int[layers.size() - 1][];
            for (int i = 0, j = 0; j < f.px.length; j++) {
                if (j == index) continue;
                n[i++] = (j == index - 1) ? a : f.px[j];
            }
            f.px = n;
        }
        layers.remove(index);
        low.visible = low.visible || top.visible;
        activeLayer = index - 1;
    }

    // ---------------------------------------------------------------- frames

    /** Inserts a frame at index. If copyOf >= 0 it is a duplicate of that frame, else empty. */
    public void addFrame(int index, int copyOf) {
        index = Math.max(0, Math.min(index, frames.size()));
        Frame nf;
        if (copyOf >= 0) {
            nf = frames.get(copyOf).deepCopy();
        } else {
            int[][] px = new int[layers.size()][];
            for (int i = 0; i < px.length; i++) px[i] = new int[w * h];
            nf = new Frame(px, frames.isEmpty() ? 100 : frames.get(Math.min(activeFrame, frames.size() - 1)).delayMs);
        }
        frames.add(index, nf);
        activeFrame = index;
    }

    public void removeFrame(int index) {
        if (frames.size() <= 1) return;
        frames.remove(index);
        clampActive();
    }

    public void moveFrame(int from, int to) {
        if (from == to || to < 0 || to >= frames.size()) return;
        Frame f = frames.remove(from);
        frames.add(to, f);
        activeFrame = to;
    }

    // ---------------------------------------------------------------- snapshots

    /** Cheap structural snapshot: layer metadata copies and shallow frame copies (pixel arrays are shared). */
    public static final class Snapshot {
        final List<LayerInfo> layers = new ArrayList<>();
        final List<Frame> frames = new ArrayList<>();
        final int loop, bg;

        Snapshot(Doc d) {
            for (LayerInfo l : d.layers) layers.add(l.copy());
            for (Frame f : d.frames) frames.add(f.shallowCopy());
            loop = d.loop;
            bg = d.bg;
        }
    }

    public Snapshot snapshot() { return new Snapshot(this); }

    public void restore(Snapshot s) {
        layers = new ArrayList<>();
        for (LayerInfo l : s.layers) layers.add(l.copy());
        frames = new ArrayList<>();
        for (Frame f : s.frames) {
            Frame c = f.shallowCopy();
            c.version = f.version + 1;
            frames.add(c);
        }
        loop = s.loop;
        bg = s.bg;
        clampActive();
    }

    public long approxBytes() {
        return (long) w * h * 4L * layers.size() * frames.size();
    }
}
