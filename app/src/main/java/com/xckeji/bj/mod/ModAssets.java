package com.xckeji.bj.mod;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 模组素材读取：优先用「当前选中的模组」（App 私有目录 files/mods/&lt;模组名&gt;/），
 * 里面没有的再回退到 APK 内置 assets —— 所以没导入模组时，行为与以前完全一致。
 *
 * 素材按内置目录结构存放（flag/、legion/、building/、map/、image/status/、general/、json/…），
 * 查找时还会做扩展名兜底（模组给 .png、内置是 .webp 也能对上）。
 */
public class ModAssets {

    private static final String PREF = "mod_prefs";
    private static final String KEY_CURRENT = "current_mod";
    private static final String[] EXTS = {".png", ".webp", ".jpg", ".jpeg"};

    private static String current;
    private static File currentDir;

    private ModAssets() {}

    public static void init(Context ctx) {
        SharedPreferences sp = ctx.getApplicationContext()
                .getSharedPreferences(PREF, Context.MODE_PRIVATE);
        setCurrent(ctx, sp.getString(KEY_CURRENT, null), true);
    }

    public static void setCurrent(Context ctx, String name) {
        setCurrent(ctx, name, false);
    }

    private static void setCurrent(Context ctx, String name, boolean fromPrefs) {
        current = (name == null || name.trim().isEmpty()) ? null : name.trim();
        File dir = current == null ? null : new File(modsBase(ctx), current);
        if (dir != null && !dir.isDirectory()) {          // 模组被删了 → 回到内置
            current = null;
            dir = null;
        }
        currentDir = dir;
        if (!fromPrefs) {
            ctx.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE)
                    .edit().putString(KEY_CURRENT, current).apply();
        }
    }

    public static String currentMod() { return current; }
    public static File currentDir() { return currentDir; }
    public static boolean hasMod() { return currentDir != null; }

    public static File modsBase(Context ctx) {
        return new File(ctx.getApplicationContext().getFilesDir(), "mods");
    }

    public static List<String> list(Context ctx) {
        List<String> out = new ArrayList<>();
        File[] fs = modsBase(ctx).listFiles();
        if (fs != null) {
            for (File f : fs) if (f.isDirectory()) out.add(f.getName());
        }
        Collections.sort(out);
        return out;
    }

    public static boolean delete(Context ctx, String name) {
        if (name == null) return false;
        File dir = new File(modsBase(ctx), name);
        boolean ok = deleteRec(dir);
        if (name.equals(current)) setCurrent(ctx, null);
        return ok;
    }

    private static boolean deleteRec(File f) {
        if (f == null || !f.exists()) return false;
        if (f.isDirectory()) {
            File[] fs = f.listFiles();
            if (fs != null) for (File c : fs) deleteRec(c);
        }
        return f.delete();
    }

    /** 模组目录体积（字节）。 */
    public static long sizeOf(File dir) {
        if (dir == null || !dir.exists()) return 0;
        if (dir.isFile()) return dir.length();
        long s = 0;
        File[] fs = dir.listFiles();
        if (fs != null) for (File c : fs) s += sizeOf(c);
        return s;
    }

    /** 打开素材流：先模组（含扩展名兜底），再内置 assets。 */
    public static InputStream open(Context ctx, String path) throws IOException {
        InputStream in = openMod(path);
        if (in != null) return in;
        return ctx.getAssets().open(path);
    }

    private static InputStream openMod(String path) {
        if (currentDir == null || path == null) return null;
        for (String cand : candidates(path)) {
            File f = new File(currentDir, cand);
            if (f.isFile()) {
                try { return new FileInputStream(f); } catch (IOException ignored) { }
            }
        }
        return null;
    }

    /** 该路径在模组里有没有（不查内置）。 */
    public static boolean inMod(String path) {
        if (currentDir == null || path == null) return false;
        for (String cand : candidates(path)) {
            if (new File(currentDir, cand).isFile()) return true;
        }
        return false;
    }

    /** 原名 + 换扩展名的候选写法。 */
    public static List<String> candidates(String path) {
        List<String> out = new ArrayList<>();
        if (path == null) return out;
        out.add(path);
        int slash = path.lastIndexOf('/');
        int dot = path.lastIndexOf('.');
        String stem = (dot > slash) ? path.substring(0, dot) : path;
        for (String ext : EXTS) out.add(stem + ext);
        return out;
    }

    /**
     * 列出「目录 + 前缀」下的编号集合（模组目录和内置 assets 合并），
     * 例如 idsIn(ctx,"flag","flag_") 会给出 1..95（模组有 95 面国旗时也能全加载）。
     */
    public static java.util.TreeSet<Integer> idsIn(Context ctx, String dir, String prefix) {
        java.util.TreeSet<Integer> out = new java.util.TreeSet<>();
        if (currentDir != null) {
            File d = new File(currentDir, dir);
            File[] fs = d.listFiles();
            if (fs != null) for (File f : fs) addNum(f.getName(), prefix, out);
        }
        try {
            String[] fs = ctx.getAssets().list(dir);
            if (fs != null) for (String f : fs) addNum(f, prefix, out);
        } catch (Exception ignored) { }
        return out;
    }

    private static void addNum(String fileName, String prefix, java.util.Set<Integer> out) {
        String n = fileName;
        int dot = n.lastIndexOf('.');
        if (dot > 0) n = n.substring(0, dot);
        if (!n.startsWith(prefix)) return;
        try { out.add(Integer.parseInt(n.substring(prefix.length()).trim())); } catch (Exception ignored) { }
    }

    public static Bitmap decode(Context ctx, String path) {
        InputStream in = null;
        try {
            in = open(ctx, path);
            return BitmapFactory.decodeStream(in);
        } catch (Exception | OutOfMemoryError e) {
            return null;
        } finally {
            close(in);
        }
    }

    public static byte[] read(Context ctx, String path) throws IOException {
        InputStream in = open(ctx, path);
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        } finally {
            close(in);
        }
    }

    /** 写入模组目录里的文件（导入切图时用）。 */
    public static void writeModFile(File modDir, String relPath, byte[] data) throws IOException {
        File out = new File(modDir, relPath);
        File parent = out.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        FileOutputStream fos = new FileOutputStream(out);
        try {
            fos.write(data);
        } finally {
            close(fos);
        }
    }

    public static void close(Closeable c) {
        if (c != null) try { c.close(); } catch (IOException ignored) { }
    }
}
