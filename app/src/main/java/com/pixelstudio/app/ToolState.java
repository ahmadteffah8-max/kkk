package com.pixelstudio.app;

import com.pixelstudio.core.Stroke;

/** Shared tool settings between the editor activity and the canvas. */
public final class ToolState {
    public static final int PENCIL = 0, BRUSH = 1, ERASER = 2, BUCKET = 3, LINE = 4, RECT = 5,
            ELLIPSE = 6, SELECT = 7, MOVE = 8, HAND = 9, PICKER = 10, SHADE = 11, WAND = 12;
    public static final int TOOL_COUNT = 13;

    /** Shading modes of the SHADE tool. */
    public static final int SHADE_DARK = 0, SHADE_LIGHT = 1, SHADE_HATCH = 2;
    /** Bucket modes. */
    public static final int FILL_NORMAL = 0, FILL_INSIDE = 1;

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

    // ---- pens (BRUSH tool)
    public int brushStyle = Stroke.ST_NORMAL;
    /** Pen opacity 1..100. */
    public int brushOpacity = 100;

    // ---- shading tool
    public int shadeMode = SHADE_DARK;
    /** Shading strength 1..100. */
    public int shadeStrength = 30;

    // ---- bucket
    public int fillMode = FILL_NORMAL;
    /** How strongly the bucket colours, 1..100 (%). */
    public int fillOpacity = 100;
    /** Outline colour for "fill inside outline". */
    public int edgeColor = 0xFF000000;
    /** How far from the outline colour a pixel can be and still count as outline (0..128). */
    public int edgeTol = 64;
    /** Closes small holes in the outline, 0..4. */
    public int gap = 1;

    // ---- smart select (magic wand)
    /** true = the whole connected shape, false = connected area of a similar colour. */
    public boolean wandObject = true;
    public int wandTol = 24;

    public int size() { return Math.max(1, sizes[tool]); }

    public static boolean isDrawTool(int t) {
        return t == PENCIL || t == BRUSH || t == ERASER || t == SHADE;
    }

    public static boolean isShapeTool(int t) {
        return t == LINE || t == RECT || t == ELLIPSE;
    }

    public boolean usesSize() {
        return isDrawTool(tool) || isShapeTool(tool);
    }
}
