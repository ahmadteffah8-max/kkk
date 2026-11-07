package com.pixelstudio.core;

import java.util.ArrayList;
import java.util.List;

/** Built-in pixel art starter templates (drawn from ASCII maps, '.' = transparent). */
public final class Templates {
    private Templates() {}

    public static final int COUNT = 6;
    /** Name keys resolved by the UI. */
    public static final String[] KEYS = {"heart", "smiley", "mushroom", "invader", "sword", "blank_sprite"};

    private static int color(char c) {
        switch (c) {
            case 'k': return 0xFF1B1B2F;
            case 'r': return 0xFFE63946;
            case 'd': return 0xFFA4161A;
            case 'w': return 0xFFFFFFFF;
            case 'y': return 0xFFFFD60A;
            case 'o': return 0xFFFF9F1C;
            case 'g': return 0xFF2DC653;
            case 'c': return 0xFFF1E3C8;
            case 's': return 0xFFB8C0CC;
            case 'b': return 0xFF4361EE;
            case 'n': return 0xFF8D5524;
            case 'p': return 0xFFB5179E;
            default: return 0;
        }
    }

    private static final String[] HEART = {
            "................",
            "..kkkk....kkkk..",
            ".krrrrk..krrrrk.",
            "krwwrrrkkrrrrrrk",
            "krwrrrrrrrrrrrrk",
            "krrrrrrrrrrrrrrk",
            "krrrrrrrrrrrrdrk",
            ".krrrrrrrrrrrdk.",
            "..krrrrrrrrrdk..",
            "...krrrrrrrdk...",
            "....krrrrrdk....",
            ".....krrrdk.....",
            "......krdk......",
            ".......kk.......",
            "................",
            "................"};

    private static final String[] MUSHROOM = {
            "................",
            ".....kkkkkk.....",
            "...kkrrrrrrkk...",
            "..krrwwrrrrrrk..",
            ".krrwwwrrrwwrrk.",
            ".krrrwrrrrwwrrk.",
            "krrrrrrrwrrrrrrk",
            "krwwrrrrrrrrwwrk",
            "krwwrrrrrrrrwwrk",
            "kkddddddddddddkk",
            "..kccccccccck...",
            "..kccwccckccck..",
            "..kccwcccccck...",
            "..kcccccccdck...",
            "...kkkkkkkkk....",
            "................"};

    private static final String[] INVADER_A = {
            "..k.....k..",
            "...k...k...",
            "..kkkkkkk..",
            ".kk.kkk.kk.",
            "kkkkkkkkkkk",
            "k.kkkkkkk.k",
            "k.k.....k.k",
            "...kk.kk..."};

    private static final String[] INVADER_B = {
            "..k.....k..",
            "k..k...k..k",
            "k.kkkkkkk.k",
            "kkk.kkk.kkk",
            "kkkkkkkkkkk",
            ".kkkkkkkkk.",
            "..k.....k..",
            ".k.......k."};

    private static int[] fromMap(String[] rows, int size, int ox, int oy, int override) {
        int[] px = new int[size * size];
        for (int y = 0; y < rows.length; y++) {
            for (int x = 0; x < rows[y].length(); x++) {
                char ch = rows[y].charAt(x);
                if (ch == '.') continue;
                int X = x + ox, Y = y + oy;
                if (X < 0 || Y < 0 || X >= size || Y >= size) continue;
                px[Y * size + X] = override != 0 ? override : color(ch);
            }
        }
        return px;
    }

    private static int[] smiley() {
        int s = 16;
        int[] px = new int[s * s];
        for (int y = 0; y < s; y++) {
            for (int x = 0; x < s; x++) {
                double dx = x + 0.5 - 8, dy = y + 0.5 - 8;
                double d = Math.sqrt(dx * dx + dy * dy);
                if (d <= 7.0) px[y * s + x] = color('y');
                if (d > 6.0 && d <= 7.5) px[y * s + x] = color('k');
            }
        }
        int[][] eyes = {{5, 5}, {5, 6}, {10, 5}, {10, 6}};
        for (int[] e : eyes) px[e[1] * s + e[0]] = color('k');
        int[][] mouth = {{4, 9}, {5, 10}, {6, 11}, {7, 11}, {8, 11}, {9, 11}, {10, 10}, {11, 9}};
        for (int[] m : mouth) px[m[1] * s + m[0]] = color('k');
        return px;
    }

    private static final String[] SWORD = {
            ".......kk.......",
            "......kwsk......",
            "......kwsk......",
            "......kwsk......",
            "......kwsk......",
            "......kwsk......",
            "......kwsk......",
            "......kwsk......",
            "......kwsk......",
            "...kkkkkkkkkk...",
            "...kooooooook...",
            "...kkkkkkkkkk...",
            "......knnk......",
            "......knnk......",
            ".....kooook.....",
            ".....kkkkkk....."};

    /** Builds template number idx. Name is set by the caller. */
    public static Doc build(int idx, String layerName) {
        Doc d = new Doc(16, 16, true);
        d.layers.add(new LayerInfo(layerName));
        List<int[]> frames = new ArrayList<>();
        switch (idx) {
            case 0: frames.add(fromMap(HEART, 16, 0, 0, 0)); break;
            case 1: frames.add(smiley()); break;
            case 2: frames.add(fromMap(MUSHROOM, 16, 0, 0, 0)); break;
            case 3:
                frames.add(fromMap(INVADER_A, 16, 2, 4, 0xFF2DC653));
                frames.add(fromMap(INVADER_B, 16, 2, 4, 0xFF2DC653));
                break;
            case 4: frames.add(fromMap(SWORD, 16, 0, 0, 0)); break;
            default: frames.add(new int[256]); break;
        }
        for (int[] f : frames) d.frames.add(new Frame(new int[][]{f}, idx == 3 ? 400 : 100));
        d.loop = 0;
        return d;
    }
}
