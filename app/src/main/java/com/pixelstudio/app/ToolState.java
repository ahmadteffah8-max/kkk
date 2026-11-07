package com.pixelstudio.app;

/** Shared tool settings between the editor activity and the canvas. */
public final class ToolState {
    public static final int PENCIL = 0, BRUSH = 1, ERASER = 2, BUCKET = 3, LINE = 4, RECT = 5,
            ELLIPSE = 6, SELECT = 7, MOVE = 8, HAND = 9, PICKER = 10;
    public static final int TOOL_COUNT = 11;

    public int tool = PENCIL;
    public int color = 0xFF000000;
    /** Brush size per tool. */
    public final int[] sizes = new int[TOOL_COUNT];
    public boolean fillShape;
    public int tolerance;
    public boolean grid;
    public int gridStep = 1;
    public boolean onion;
    public boolean preview;

    public int size() { return Math.max(1, sizes[tool]); }

    public static boolean isDrawTool(int t) {
        return t == PENCIL || t == BRUSH || t == ERASER;
    }

    public static boolean isShapeTool(int t) {
        return t == LINE || t == RECT || t == ELLIPSE;
    }

    public boolean usesSize() {
        return isDrawTool(tool) || isShapeTool(tool);
    }
}
