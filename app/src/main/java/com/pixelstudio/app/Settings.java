package com.pixelstudio.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.LocaleList;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Persistent preferences and locale handling. */
public final class Settings {
    private Settings() {}

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("pixelstudio", Context.MODE_PRIVATE);
    }

    // ---------------------------------------------------------------- language

    /** "ar", "en" or "" for the system default. */
    public static String language(Context c) {
        return sp(c).getString("lang", "");
    }

    public static void setLanguage(Context c, String lang) {
        sp(c).edit().putString("lang", lang).apply();
    }

    public static boolean isArabic(Context c) {
        String l = language(c);
        if (l.isEmpty()) return Locale.getDefault().getLanguage().equals("ar");
        return l.equals("ar");
    }

    /** Wraps a base context with the chosen language (Arabic gets RTL automatically). */
    public static Context wrap(Context base) {
        String l = language(base);
        if (l.isEmpty()) return base;
        Locale loc = new Locale(l);
        Locale.setDefault(loc);
        Configuration conf = new Configuration(base.getResources().getConfiguration());
        conf.setLocale(loc);
        conf.setLocales(new LocaleList(loc));
        conf.setLayoutDirection(loc);
        return base.createConfigurationContext(conf);
    }

    // ---------------------------------------------------------------- colors

    public static List<Integer> recentColors(Context c) {
        return readList(c, "recent");
    }

    public static void addRecentColor(Context c, int color) {
        List<Integer> l = readList(c, "recent");
        l.remove(Integer.valueOf(color));
        l.add(0, color);
        while (l.size() > 16) l.remove(l.size() - 1);
        writeList(c, "recent", l);
    }

    public static List<Integer> customColors(Context c) {
        return readList(c, "custom");
    }

    public static void addCustomColor(Context c, int color) {
        List<Integer> l = readList(c, "custom");
        if (!l.contains(color)) l.add(0, color);
        while (l.size() > 32) l.remove(l.size() - 1);
        writeList(c, "custom", l);
    }

    public static void removeCustomColor(Context c, int color) {
        List<Integer> l = readList(c, "custom");
        l.remove(Integer.valueOf(color));
        writeList(c, "custom", l);
    }

    private static List<Integer> readList(Context c, String key) {
        List<Integer> out = new ArrayList<>();
        String s = sp(c).getString(key, "");
        if (s.isEmpty()) return out;
        for (String p : s.split(",")) {
            try {
                out.add((int) Long.parseLong(p.trim(), 16));
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    private static void writeList(Context c, String key, List<Integer> l) {
        StringBuilder sb = new StringBuilder();
        for (int v : l) {
            if (sb.length() > 0) sb.append(',');
            sb.append(Integer.toHexString(v));
        }
        sp(c).edit().putString(key, sb.toString()).apply();
    }

    // ---------------------------------------------------------------- misc

    public static int getInt(Context c, String key, int def) { return sp(c).getInt(key, def); }
    public static void putInt(Context c, String key, int v) { sp(c).edit().putInt(key, v).apply(); }
    public static boolean getBool(Context c, String key, boolean def) { return sp(c).getBoolean(key, def); }
    public static void putBool(Context c, String key, boolean v) { sp(c).edit().putBoolean(key, v).apply(); }
}
