package com.pixelstudio.core;

/** Layer metadata shared by every frame of a document. */
public final class LayerInfo {
    public String name;
    public boolean visible = true;
    /** 0..255 */
    public int opacity = 255;

    public LayerInfo(String name) {
        this.name = name;
    }

    public LayerInfo copy() {
        LayerInfo l = new LayerInfo(name);
        l.visible = visible;
        l.opacity = opacity;
        return l;
    }
}
