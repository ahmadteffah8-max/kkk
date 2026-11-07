import com.pixelstudio.core.*;

import java.io.*;
import java.util.*;

/** Plain-Java self test for the engine (no Android needed). Run: tools/run-core-tests.sh */
public class CoreSelfTest {
    static int failures = 0, checks = 0;

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) { failures++; System.out.println("FAIL: " + what); }
    }

    public static void main(String[] a) throws Exception {
        testBlend();
        testStroke();
        testFill();
        testLayersAndHistory();
        testGifRoundTrip();
        testGifStress();
        testProjectIO();
        testLayerOps();
        testExportPipeline();
        testTemplates();
        testFloatingMoveLogic();
        if (a.length > 0) writeSampleGif(a[0]);
        System.out.println(checks + " checks, " + failures + " failures");
        System.exit(failures == 0 ? 0 : 1);
    }

    static void testBlend() {
        check(Blend.over(0xFF112233, 0x00FFFFFF) == 0xFF112233, "transparent src keeps dst");
        check(Blend.over(0xFF112233, 0xFFAABBCC) == 0xFFAABBCC, "opaque src replaces");
        int m = Blend.over(0xFF000000, 0x80FFFFFF);
        check((m >>> 24) == 255 && ((m >> 16) & 255) >= 127 && ((m >> 16) & 255) <= 129, "half white over black ~ gray " + Integer.toHexString(m));
        check(Blend.scaleAlpha(0xFF123456, 0) == 0, "scale alpha 0");
    }

    static void testStroke() {
        int w = 32, h = 32;
        int[] layer = new int[w * h];
        Stroke s = new Stroke(w, h);
        s.begin(layer, 0xFFFF0000, 1, false, false, true, null);
        s.line(2, 2, 10, 2);
        Patch p = s.finish();
        check(p != null && p.w == 9 && p.h == 1, "line patch rect");
        boolean all = true;
        for (int x = 2; x <= 10; x++) all &= layer[2 * w + x] == 0xFFFF0000;
        check(all && layer[2 * w + 11] == 0, "horizontal line pixels");
        p.applyBefore(layer, w);
        check(layer[2 * w + 5] == 0, "patch undo");
        p.applyAfter(layer, w);
        check(layer[2 * w + 5] == 0xFFFF0000, "patch redo");

        // alpha strokes must not accumulate on overlap
        int[] l2 = new int[w * h];
        s.begin(l2, 0x80FF0000, 3, false, false, true, null);
        s.line(5, 5, 15, 5);
        s.line(15, 5, 5, 5);
        s.finish();
        check(l2[5 * w + 10] == l2[5 * w + 6], "no alpha accumulation");

        // eraser
        s.begin(layer, 0, 3, true, false, true, null);
        s.stamp(5, 2);
        s.finish();
        check(layer[2 * w + 5] == 0, "eraser clears");

        // clip
        int[] l3 = new int[w * h];
        s.begin(l3, 0xFF00FF00, 5, false, false, true, new int[]{0, 0, 10, 10});
        s.stamp(10, 10);
        s.finish();
        boolean outside = false;
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) if ((x >= 10 || y >= 10) && l3[y * w + x] != 0) outside = true;
        check(!outside && l3[9 * w + 9] != 0, "clip respected");

        // shapes + reset preview
        int[] l4 = new int[w * h];
        s.begin(l4, 0xFF0000FF, 1, false, false, true, null);
        s.rect(4, 4, 12, 10, false);
        check(l4[4 * w + 8] != 0 && l4[7 * w + 8] == 0 && l4[7 * w + 4] != 0, "rect outline");
        s.reset();
        boolean empty = true;
        for (int v : l4) if (v != 0) empty = false;
        check(empty, "reset restores layer");
        s.ellipse(2, 2, 20, 12, true);
        check(l4[7 * w + 11] != 0 && l4[2 * w + 2] == 0, "filled ellipse");
        s.finish();
        int[] l5 = new int[w * h];
        s.begin(l5, 0xFF0000FF, 1, false, false, true, null);
        s.ellipse(2, 2, 20, 12, false);
        check(l5[7 * w + 2] != 0 && l5[7 * w + 11] == 0, "ellipse outline");
        s.finish();
        // AA brush: edge pixels partially transparent
        int[] l6 = new int[w * h];
        s.begin(l6, 0xFF000000, 9, false, true, false, null);
        s.stamp(16, 16);
        s.finish();
        check(l6[16 * w + 16] == 0xFF000000 && (l6[16 * w + 12] >>> 24) > 0, "aa brush center solid");
    }

    static void testFill() {
        int w = 16, h = 16;
        int[] px = new int[w * h];
        for (int i = 0; i < w; i++) px[8 * w + i] = 0xFF000000; // wall
        int[] r = FloodFill.fill(px, w, h, 3, 3, 0xFFFF0000, 0, null);
        check(r != null && r[1] == 0 && r[3] == 8, "fill bounds");
        check(px[3 * w + 3] == 0xFFFF0000 && px[12 * w + 3] == 0, "fill stops at wall");
        check(FloodFill.fill(px, w, h, 3, 3, 0xFFFF0000, 0, null) == null, "no-op fill");
        int[] c = FloodFill.fill(new int[w * h], w, h, 0, 0, 0xFF00FF00, 0, new int[]{0, 0, 4, 4});
        check(c != null && c[2] == 4 && c[3] == 4, "fill respects clip");
        // tolerance
        int[] t = new int[w * h];
        Arrays.fill(t, 0xFF808080);
        t[5] = 0xFF858585;
        FloodFill.fill(t, w, h, 0, 0, 0xFFFF0000, 10, null);
        check(t[5] == 0xFFFF0000, "tolerance fill");
    }

    static void testLayersAndHistory() {
        Doc d = Doc.create(8, 8, true, 0, "L1");
        History hist = new History();
        d.layers.get(0).name = "L1";
        hist.struct(d, () -> d.addLayer(1, "L2"));
        check(d.layers.size() == 2 && d.frames.get(0).px.length == 2, "add layer");
        d.frames.get(0).px[0][0] = 0xFF111111;
        d.frames.get(0).px[1][0] = 0x80FF0000;
        hist.struct(d, () -> d.mergeDown(1));
        check(d.layers.size() == 1 && (d.frames.get(0).px[0][0] >>> 24) == 255, "merge down");
        hist.undo(d);
        check(d.layers.size() == 2 && d.frames.get(0).px[0][0] == 0xFF111111 && d.frames.get(0).px[1][0] == 0x80FF0000, "undo merge restores both layers");
        hist.redo(d);
        check(d.layers.size() == 1, "redo merge");
        hist.undo(d);
        hist.struct(d, () -> d.addFrame(1, 0));
        check(d.frames.size() == 2 && d.frames.get(1).px[1][0] == 0x80FF0000, "duplicate frame copies pixels");
        hist.struct(d, () -> d.moveLayer(0, 1));
        check(d.layers.size() == 2 && d.frames.get(0).px[1][0] == 0xFF111111, "move layer reorders arrays");
        hist.struct(d, () -> d.removeFrame(0));
        check(d.frames.size() == 1, "remove frame");
        hist.undo(d);
        check(d.frames.size() == 2, "undo remove frame");
        // pixel patch interleaved with struct ops
        int[] before = d.frames.get(1).px[0].clone();
        Stroke s = new Stroke(8, 8);
        s.begin(d.frames.get(1).px[0], 0xFF00FF00, 1, false, false, true, null);
        s.stamp(4, 4);
        hist.pushPatch(d, 1, 0, s.finish());
        check(d.frames.get(1).px[0][4 * 8 + 4] == 0xFF00FF00, "stamp applied");
        hist.undo(d);
        check(Arrays.equals(before, d.frames.get(1).px[0]), "patch undo through history");
        int[] comp = d.compose(d.frames.get(0));
        check(comp[0] != 0, "compose");
        // many undo steps
        History big = new History();
        Doc d2 = Doc.create(16, 16, true, 0, "x");
        Stroke s2 = new Stroke(16, 16);
        for (int i = 0; i < 300; i++) {
            s2.begin(d2.frames.get(0).px[0], 0xFF000000 | i, 1, false, false, true, null);
            s2.stamp(i % 16, (i / 16) % 16);
            big.pushPatch(d2, 0, 0, s2.finish());
        }
        int undone = 0;
        while (big.undo(d2)) undone++;
        check(undone == 300, "300 undo steps");
        boolean clear = true;
        for (int v : d2.frames.get(0).px[0]) if (v != 0) clear = false;
        check(clear, "all undone");
    }


    static Doc threeLayers() {
        Doc d = Doc.create(4, 4, true, 0, "A");
        d.addLayer(1, "B");
        d.addLayer(2, "C");
        d.frames.get(0).px[0][0] = 0xFF0000AA;
        d.frames.get(0).px[1][0] = 0xFF00BB00;
        d.frames.get(0).px[2][0] = 0xFFCC0000;
        return d;
    }

    static void testLayerOps() {
        Doc d = threeLayers();
        d.moveLayer(0, 2);
        check(d.layers.get(2).name.equals("A") && d.frames.get(0).px[2][0] == 0xFF0000AA && d.frames.get(0).px[0][0] == 0xFF00BB00, "moveLayer 0->2");
        d.moveLayer(2, 0);
        check(d.layers.get(0).name.equals("A") && d.frames.get(0).px[0][0] == 0xFF0000AA && d.frames.get(0).px[1][0] == 0xFF00BB00 && d.frames.get(0).px[2][0] == 0xFFCC0000, "moveLayer 2->0 restores order");
        d.moveLayer(1, 0);
        check(d.layers.get(0).name.equals("B") && d.frames.get(0).px[0][0] == 0xFF00BB00 && d.frames.get(0).px[1][0] == 0xFF0000AA, "moveLayer 1->0");
        d = threeLayers();
        d.duplicateLayer(1);
        check(d.layers.size() == 4 && d.frames.get(0).px[2][0] == 0xFF00BB00 && d.frames.get(0).px[3][0] == 0xFFCC0000 && d.frames.get(0).px[2] != d.frames.get(0).px[1], "duplicateLayer deep copies");
        d.removeLayer(2);
        check(d.layers.size() == 3 && d.frames.get(0).px[2][0] == 0xFFCC0000, "removeLayer");
        // hidden layers are skipped by compose; opacity applies
        d = threeLayers();
        d.layers.get(2).visible = false;
        check(d.compose(d.frames.get(0))[0] == 0xFF00BB00, "hidden layer skipped");
        d.layers.get(2).visible = true;
        d.layers.get(2).opacity = 0;
        check(d.compose(d.frames.get(0))[0] == 0xFF00BB00, "opacity 0 skipped");
        d.layers.get(2).opacity = 128;
        int c = d.compose(d.frames.get(0))[0];
        check((c >>> 24) == 255 && ((c >> 16) & 255) > 90 && ((c >> 16) & 255) < 110, "half opacity blends " + Integer.toHexString(c));
        // layers are shared across frames; frames reorder
        d = threeLayers();
        d.addFrame(1, 0);
        d.addFrame(2, -1);
        check(d.frames.size() == 3 && d.frames.get(2).px.length == 3 && d.frames.get(2).px[0][0] == 0, "blank frame has all layers");
        d.addLayer(3, "D");
        check(d.frames.get(0).px.length == 4 && d.frames.get(2).px.length == 4, "addLayer extends every frame");
        d.moveFrame(0, 2);
        check(d.frames.get(2).px[0][0] == 0xFF0000AA && d.frames.get(0).px[0][0] == 0xFF0000AA && d.frames.get(1).px[0][0] == 0, "moveFrame");
        // merge down with hidden top layer keeps lower layer untouched
        d = threeLayers();
        d.layers.get(2).visible = false;
        d.mergeDown(2);
        check(d.layers.size() == 2 && d.frames.get(0).px[1][0] == 0xFF00BB00, "merge hidden layer adds nothing");
    }

    static void testExportPipeline() throws Exception {
        // 2 frames x 2 layers, opacity, background, scale -> GIF -> decode and compare with direct composition
        Doc d = Doc.create(6, 5, true, 0, "bottom");
        d.addLayer(1, "top");
        d.layers.get(1).opacity = 128;
        d.frames.get(0).px[0][7] = 0xFFFF0000;
        d.frames.get(0).px[1][7] = 0xFF0000FF;
        d.addFrame(1, 0);
        d.frames.get(1).px[0][8] = 0xFF00FF00;
        d.frames.get(1).delayMs = 250;
        d.loop = 2;
        for (int bg : new int[]{0, 0xFFFFFFFF}) {
            for (int k : new int[]{1, 3}) {
                List<int[]> fr = new ArrayList<>();
                int[] delays = new int[d.frames.size()];
                for (int i = 0; i < d.frames.size(); i++) {
                    fr.add(Raster.scaleNearest(Raster.withBackground(d, d.frames.get(i), bg), d.w, d.h, k));
                    delays[i] = d.frames.get(i).delayMs;
                }
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                GifEncoder.write(bo, d.w * k, d.h * k, fr, delays, d.loop, null);
                GifDecoder g = GifDecoder.decode(bo.toByteArray());
                boolean ok = g.width == d.w * k && g.height == d.h * k && g.frames.size() == 2 && g.plays == 2 && g.delaysMs.get(1) == 250;
                for (int f = 0; f < 2 && ok; f++) {
                    int[] want = fr.get(f);
                    int[] got = g.frames.get(f);
                    for (int i = 0; i < want.length; i++) {
                        boolean wantT = (want[i] >>> 24) < 128;
                        boolean gotT = (got[i] >>> 24) < 128;
                        if (wantT != gotT || (!wantT && (want[i] & 0xFFFFFF) != (got[i] & 0xFFFFFF))) { ok = false; break; }
                    }
                }
                check(ok, "document -> GIF roundtrip bg=" + Integer.toHexString(bg) + " scale=" + k);
            }
        }
        int[] up = Raster.scaleNearest(new int[]{1, 2, 3, 4}, 2, 2, 2);
        check(Arrays.equals(up, new int[]{1, 1, 2, 2, 1, 1, 2, 2, 3, 3, 4, 4, 3, 3, 4, 4}), "scaleNearest");
    }

    static void testTemplates() {
        for (int i = 0; i < Templates.COUNT; i++) {
            Doc d = Templates.build(i, "L");
            boolean ok = d.w == 16 && d.h == 16 && d.pixelArt && d.layers.size() == 1 && !d.frames.isEmpty();
            for (Frame f : d.frames) ok &= f.px.length == 1 && f.px[0].length == 256;
            check(ok, "template " + Templates.KEYS[i]);
        }
        check(Templates.build(3, "L").frames.size() == 2, "invader template is animated");
        boolean any = false;
        for (int v : Templates.build(0, "L").frames.get(0).px[0]) if (v != 0) any = true;
        check(any, "heart template has pixels");
    }

    /** Mirrors CanvasView select/move/commit: lift region, move, drop with undo patch. */
    static void testFloatingMoveLogic() {
        int w = 10, h = 10;
        int[] layer = new int[w * h];
        for (int y = 2; y < 5; y++) for (int x = 2; x < 5; x++) layer[y * w + x] = 0xFFFF0000;
        int[] before = layer.clone();
        int[] sel = {2, 2, 5, 5};
        int fw = 3, fh = 3;
        int[] fbuf = new int[9];
        for (int y = 0; y < fh; y++) System.arraycopy(layer, (sel[1] + y) * w + sel[0], fbuf, y * fw, fw);
        for (int y = 0; y < fh; y++) Arrays.fill(layer, (sel[1] + y) * w + sel[0], (sel[1] + y) * w + sel[2], 0);
        int fx = 6, fy = 6;
        for (int y = 0; y < fh; y++) for (int x = 0; x < fw; x++) {
            int s = fbuf[y * fw + x];
            if ((s >>> 24) != 0) layer[(fy + y) * w + fx + x] = Blend.over(layer[(fy + y) * w + fx + x], s);
        }
        Patch p = Patch.fromDiff(before, layer, w, 2, 2, 8, 8);
        check(p != null && layer[3 * w + 3] == 0 && layer[7 * w + 7] == 0xFFFF0000, "move drops content at new place");
        p.applyBefore(layer, w);
        check(Arrays.equals(layer, before), "move undo restores layer");
        p.applyAfter(layer, w);
        check(layer[3 * w + 3] == 0 && layer[7 * w + 7] == 0xFFFF0000, "move redo");
    }

    static Doc gradientDoc() {
        return null;
    }

    static void testGifRoundTrip() throws Exception {
        // pixel art, exact palette, no transparency
        int w = 20, h = 14;
        List<int[]> frames = new ArrayList<>();
        Random rnd = new Random(1);
        int[] pal = {0xFF000000, 0xFFFF0000, 0xFF00FF00, 0xFF0000FF, 0xFFFFFFFF};
        for (int f = 0; f < 5; f++) {
            int[] px = new int[w * h];
            for (int i = 0; i < px.length; i++) px[i] = pal[(i / 3 + f) % pal.length];
            frames.add(px);
        }
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        GifEncoder.write(bo, w, h, frames, new int[]{100, 200, 50, 300, 100}, 0, null);
        GifDecoder g = GifDecoder.decode(bo.toByteArray());
        check(g.width == w && g.height == h && g.frames.size() == 5, "gif dims/frames");
        boolean same = true;
        for (int f = 0; f < 5; f++) same &= Arrays.equals(g.frames.get(f), frames.get(f));
        check(same, "exact palette roundtrip identical");
        check(g.delaysMs.get(1) == 200 && g.delaysMs.get(3) == 300, "delays preserved");
        check(g.plays == 0, "infinite loop flag");

        // loop count
        bo.reset();
        GifEncoder.write(bo, w, h, frames, new int[]{100, 100, 100, 100, 100}, 3, null);
        check(GifDecoder.decode(bo.toByteArray()).plays == 3, "plays=3 roundtrip");
        bo.reset();
        GifEncoder.write(bo, w, h, frames, new int[]{100, 100, 100, 100, 100}, 1, null);
        check(GifDecoder.decode(bo.toByteArray()).plays == 1, "plays=1 no extension");

        // transparency
        List<int[]> tf = new ArrayList<>();
        for (int f = 0; f < 4; f++) {
            int[] px = new int[w * h];
            for (int y = 2 + f; y < 8 + f; y++) for (int x = 3; x < 12; x++) px[y * w + x] = pal[1 + (f % 3)];
            tf.add(px);
        }
        bo.reset();
        GifEncoder.write(bo, w, h, tf, new int[]{80, 80, 80, 80}, 0, null);
        g = GifDecoder.decode(bo.toByteArray());
        boolean ok = g.frames.size() == 4;
        for (int f = 0; f < 4 && ok; f++) ok &= Arrays.equals(g.frames.get(f), tf.get(f));
        check(ok, "transparent frames roundtrip (disposal 2)");

        // many colors -> median cut; check average error is small
        int W = 64, H = 64;
        List<int[]> pf = new ArrayList<>();
        for (int f = 0; f < 3; f++) {
            int[] px = new int[W * H];
            for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
                px[y * W + x] = 0xFF000000 | ((x * 4 + f * 10) & 255) << 16 | ((y * 4) & 255) << 8 | ((x * y + f) & 255);
            }
            pf.add(px);
        }
        bo.reset();
        GifEncoder.write(bo, W, H, pf, new int[]{100, 100, 100}, 0, null);
        g = GifDecoder.decode(bo.toByteArray());
        long err = 0;
        for (int f = 0; f < 3; f++) for (int i = 0; i < W * H; i++) {
            int p = pf.get(f)[i], q = g.frames.get(f)[i];
            err += Math.abs(((p >> 16) & 255) - ((q >> 16) & 255)) + Math.abs(((p >> 8) & 255) - ((q >> 8) & 255)) + Math.abs((p & 255) - (q & 255));
        }
        double avg = err / (3.0 * W * H * 3);
        check(g.frames.size() == 3 && avg < 14, "median cut average error " + avg);
    }

    static void testGifStress() throws Exception {
        Random rnd = new Random(42);
        boolean ok = true;
        for (int iter = 0; iter < 60 && ok; iter++) {
            int w = 1 + rnd.nextInt(90), h = 1 + rnd.nextInt(90);
            int colors = 2 + rnd.nextInt(60);
            int[] pal = new int[colors];
            for (int i = 0; i < colors; i++) pal[i] = 0xFF000000 | rnd.nextInt(0xFFFFFF);
            int[] px = new int[w * h];
            int mode = iter % 3;
            for (int i = 0; i < px.length; i++) {
                if (mode == 0) px[i] = pal[rnd.nextInt(colors)];            // noise: hits the 4096 table limit
                else if (mode == 1) px[i] = pal[(i / (1 + rnd.nextInt(2))) % colors];
                else px[i] = pal[0];                                          // long runs
            }
            List<int[]> fr = new ArrayList<>();
            fr.add(px);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            GifEncoder.write(bo, w, h, fr, new int[]{100}, 0, null);
            GifDecoder g = GifDecoder.decode(bo.toByteArray());
            ok &= Arrays.equals(g.frames.get(0), px);
            if (!ok) System.out.println("stress mismatch iter=" + iter + " " + w + "x" + h + " colors=" + colors + " mode=" + mode);
        }
        check(ok, "60 random LZW roundtrips (incl. table overflow)");
        // large noise to force many clear codes
        int w = 300, h = 300;
        int[] px = new int[w * h];
        Random r2 = new Random(7);
        int[] pal = new int[200];
        for (int i = 0; i < pal.length; i++) pal[i] = 0xFF000000 | r2.nextInt(0xFFFFFF);
        for (int i = 0; i < px.length; i++) px[i] = pal[r2.nextInt(pal.length)];
        List<int[]> fr = new ArrayList<>();
        fr.add(px);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        GifEncoder.write(bo, w, h, fr, new int[]{100}, 0, null);
        check(Arrays.equals(GifDecoder.decode(bo.toByteArray()).frames.get(0), px), "large noisy frame roundtrip");
    }

    static void testProjectIO() throws Exception {
        Doc d = Doc.create(12, 9, true, 0, "A");
        d.addLayer(1, "B");
        d.layers.get(1).opacity = 128;
        d.layers.get(0).visible = false;
        d.addFrame(1, 0);
        d.frames.get(1).px[1][5] = 0xFFABCDEF;
        d.frames.get(1).delayMs = 333;
        d.loop = 4;
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        ProjectIO.write(bo, d);
        Doc r = ProjectIO.read(new ByteArrayInputStream(bo.toByteArray()));
        check(r.w == 12 && r.h == 9 && r.pixelArt && r.loop == 4, "project header");
        check(r.layers.size() == 2 && r.layers.get(1).opacity == 128 && !r.layers.get(0).visible && r.layers.get(1).name.equals("B"), "project layers");
        check(r.frames.size() == 2 && r.frames.get(1).delayMs == 333 && r.frames.get(1).px[1][5] == 0xFFABCDEF, "project frames");
    }

    static void writeSampleGif(String path) throws Exception {
        int w = 32, h = 32;
        List<int[]> fr = new ArrayList<>();
        int[] delays = new int[8];
        for (int f = 0; f < 8; f++) {
            int[] px = new int[w * h];
            int cx = 4 + f * 3;
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                int dx = x - cx, dy = y - 16;
                if (dx * dx + dy * dy < 36) px[y * w + x] = 0xFFFF4040;
            }
            fr.add(px);
            delays[f] = 80;
        }
        try (FileOutputStream o = new FileOutputStream(path)) {
            GifEncoder.write(o, w, h, fr, delays, 0, null);
        }
    }
}
