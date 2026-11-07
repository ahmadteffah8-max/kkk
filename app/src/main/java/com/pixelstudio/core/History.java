package com.pixelstudio.core;

import java.util.ArrayDeque;
import java.util.Iterator;

/** Undo/redo stack. Pixel edits are stored as region patches, structural edits as cheap snapshots. */
public final class History {
    public interface Command {
        void undo(Doc d);
        void redo(Doc d);
        long size();
    }

    private static final class PatchCmd implements Command {
        final int frame, layer;
        final Patch p;
        PatchCmd(int frame, int layer, Patch p) { this.frame = frame; this.layer = layer; this.p = p; }
        public void undo(Doc d) { apply(d, true); }
        public void redo(Doc d) { apply(d, false); }
        private void apply(Doc d, boolean undo) {
            if (frame >= d.frames.size() || layer >= d.layers.size()) return;
            Frame f = d.frames.get(frame);
            if (undo) p.applyBefore(f.px[layer], d.w); else p.applyAfter(f.px[layer], d.w);
            f.version++;
        }
        public long size() { return p.bytes(); }
    }

    private static final class StructCmd implements Command {
        final Doc.Snapshot before, after;
        StructCmd(Doc.Snapshot b, Doc.Snapshot a) { before = b; after = a; }
        public void undo(Doc d) { d.restore(before); }
        public void redo(Doc d) { d.restore(after); }
        public long size() { return 256L * (before.frames.size() + after.frames.size() + 4); }
    }

    private final ArrayDeque<Command> undo = new ArrayDeque<>();
    private final ArrayDeque<Command> redo = new ArrayDeque<>();
    private long bytes;
    private final int maxSteps;
    private final long maxBytes;

    public History(int maxSteps, long maxBytes) {
        this.maxSteps = maxSteps;
        this.maxBytes = maxBytes;
    }

    public History() { this(1000, 96L * 1024 * 1024); }

    public void push(Command c) {
        undo.addLast(c);
        bytes += c.size();
        for (Command r : redo) bytes -= r.size();
        redo.clear();
        trim();
    }

    private void trim() {
        while (undo.size() > 1 && (undo.size() > maxSteps || bytes > maxBytes)) {
            bytes -= undo.removeFirst().size();
        }
    }

    public void pushPatch(Doc d, int frame, int layer, Patch p) {
        if (p == null) return;
        d.frames.get(frame).version++;
        push(new PatchCmd(frame, layer, p));
    }

    /** Runs a structural change on the document and records it. */
    public void struct(Doc d, Runnable op) {
        Doc.Snapshot b = d.snapshot();
        op.run();
        Doc.Snapshot a = d.snapshot();
        for (Frame f : d.frames) f.version++;
        push(new StructCmd(b, a));
    }

    /** Records a structural change that was applied manually (e.g. a slider drag). */
    public void structManual(Doc.Snapshot before, Doc.Snapshot after) {
        push(new StructCmd(before, after));
    }

    public boolean canUndo() { return !undo.isEmpty(); }
    public boolean canRedo() { return !redo.isEmpty(); }
    public int undoSize() { return undo.size(); }

    public boolean undo(Doc d) {
        if (undo.isEmpty()) return false;
        Command c = undo.removeLast();
        c.undo(d);
        redo.addLast(c);
        return true;
    }

    public boolean redo(Doc d) {
        if (redo.isEmpty()) return false;
        Command c = redo.removeLast();
        c.redo(d);
        undo.addLast(c);
        return true;
    }

    public void clear() {
        undo.clear();
        redo.clear();
        bytes = 0;
    }
}
