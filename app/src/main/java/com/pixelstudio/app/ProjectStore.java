package com.pixelstudio.app;

import android.content.Context;
import android.graphics.Bitmap;

import com.pixelstudio.core.Doc;
import com.pixelstudio.core.ProjectIO;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

/** Projects live in files/projects: id.pxs (data), id.png (thumbnail), id.meta (properties). */
public final class ProjectStore {
    private ProjectStore() {}

    public static final class Info {
        public String id, name;
        public long modified, created;
        public int w, h, frames;
        public boolean pixel;
        public File thumb;
    }

    public static File dir(Context c) {
        File d = new File(c.getFilesDir(), "projects");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static String newId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    public static List<Info> list(Context c) {
        List<Info> out = new ArrayList<>();
        File[] files = dir(c).listFiles();
        if (files == null) return out;
        for (File f : files) {
            if (!f.getName().endsWith(".meta")) continue;
            Info i = readMeta(c, f);
            if (i != null) out.add(i);
        }
        Collections.sort(out, new Comparator<Info>() {
            @Override public int compare(Info a, Info b) { return Long.compare(b.modified, a.modified); }
        });
        return out;
    }

    private static Info readMeta(Context c, File f) {
        try (InputStream in = new FileInputStream(f)) {
            Properties p = new Properties();
            p.load(in);
            Info i = new Info();
            String fn = f.getName();
            i.id = fn.substring(0, fn.length() - 5);
            if (!new File(dir(c), i.id + ".pxs").exists()) return null;
            i.name = p.getProperty("name", "Untitled");
            i.modified = Long.parseLong(p.getProperty("modified", "0"));
            i.created = Long.parseLong(p.getProperty("created", "0"));
            i.w = Integer.parseInt(p.getProperty("w", "0"));
            i.h = Integer.parseInt(p.getProperty("h", "0"));
            i.frames = Integer.parseInt(p.getProperty("frames", "1"));
            i.pixel = Boolean.parseBoolean(p.getProperty("pixel", "false"));
            File t = new File(dir(c), i.id + ".png");
            i.thumb = t.exists() ? t : null;
            return i;
        } catch (Exception e) {
            return null;
        }
    }

    public static Info info(Context c, String id) {
        return readMeta(c, new File(dir(c), id + ".meta"));
    }

    public static Doc load(Context c, String id) throws IOException {
        try (InputStream in = new FileInputStream(new File(dir(c), id + ".pxs"))) {
            return ProjectIO.read(in);
        }
    }

    /** Saves the project atomically (temp file + rename) and refreshes thumbnail and metadata. */
    public static synchronized void save(Context c, String id, String name, Doc d) throws IOException {
        File dir = dir(c);
        File tmp = new File(dir, id + ".pxs.tmp");
        try (OutputStream o = new FileOutputStream(tmp)) {
            ProjectIO.write(o, d);
        }
        File dst = new File(dir, id + ".pxs");
        if (!tmp.renameTo(dst)) {
            dst.delete();
            if (!tmp.renameTo(dst)) throw new IOException("rename failed");
        }
        writeThumb(new File(dir, id + ".png"), d);

        Info old = readMeta(c, new File(dir, id + ".meta"));
        Properties p = new Properties();
        p.setProperty("name", name);
        long now = System.currentTimeMillis();
        p.setProperty("modified", String.valueOf(now));
        p.setProperty("created", String.valueOf(old != null && old.created > 0 ? old.created : now));
        p.setProperty("w", String.valueOf(d.w));
        p.setProperty("h", String.valueOf(d.h));
        p.setProperty("frames", String.valueOf(d.frames.size()));
        p.setProperty("pixel", String.valueOf(d.pixelArt));
        try (OutputStream o = new FileOutputStream(new File(dir, id + ".meta"))) {
            p.store(o, null);
        }
    }

    private static void writeThumb(File f, Doc d) {
        try {
            int[] comp = d.compose(d.frames.get(0));
            Bitmap full = Bitmap.createBitmap(d.w, d.h, Bitmap.Config.ARGB_8888);
            full.setPixels(comp, 0, d.w, 0, 0, d.w, d.h);
            int max = 320;
            float s = Math.min(1f, (float) max / Math.max(d.w, d.h));
            Bitmap t = full;
            if (s < 1f) {
                t = Bitmap.createScaledBitmap(full, Math.max(1, Math.round(d.w * s)), Math.max(1, Math.round(d.h * s)), true);
            } else if (d.pixelArt) {
                int k = Math.max(1, Math.min(max / Math.max(d.w, d.h), 16));
                if (k > 1) t = Bitmap.createScaledBitmap(full, d.w * k, d.h * k, false);
            }
            try (OutputStream o = new FileOutputStream(f)) {
                t.compress(Bitmap.CompressFormat.PNG, 100, o);
            }
            if (t != full) t.recycle();
            full.recycle();
        } catch (Exception ignored) {
        }
    }

    public static void rename(Context c, String id, String name) {
        File mf = new File(dir(c), id + ".meta");
        Properties p = new Properties();
        try (InputStream in = new FileInputStream(mf)) {
            p.load(in);
        } catch (Exception e) {
            return;
        }
        p.setProperty("name", name);
        try (OutputStream o = new FileOutputStream(mf)) {
            p.store(o, null);
        } catch (Exception ignored) {
        }
    }

    public static void delete(Context c, String id) {
        File dir = dir(c);
        new File(dir, id + ".pxs").delete();
        new File(dir, id + ".png").delete();
        new File(dir, id + ".meta").delete();
    }

    public static String duplicate(Context c, String id, String newName) throws IOException {
        Doc d = load(c, id);
        String nid = newId();
        save(c, nid, newName, d);
        return nid;
    }
}
