package com.xckeji.bj.mod;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 模组里的地图（assets/stage/*.btl）：列表、读取、保存回写、自动备份。
 * 用 SAF 的树 URI 直接读/写模组文件夹，不复制那 1000 多个 btl。
 */
public class ModMaps {

    public static class Entry {
        public final String name;      // stage10103.btl
        public final String docId;
        public Entry(String name, String docId) { this.name = name; this.docId = docId; }
        public String kind() {
            String n = name.toLowerCase(Locale.US);
            if (n.startsWith("conquest")) return "征服";
            if (n.startsWith("event")) return "事件";
            if (n.startsWith("stage")) return "战役";
            return "其他";
        }
    }

    private ModMaps() {}

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("mod_prefs", Context.MODE_PRIVATE);
    }

    public static Uri modUri(Context ctx, String modName) {
        String s = prefs(ctx).getString("uri_" + modName, null);
        return s == null ? null : Uri.parse(s);
    }

    private static String rootDocId(Context ctx, String modName) {
        return prefs(ctx).getString("rootdoc_" + modName, null);
    }

    private static String stageDocId(Context ctx, String modName) {
        String cached = prefs(ctx).getString("stagedoc_" + modName, null);
        if (cached != null) return cached;
        Uri tree = modUri(ctx, modName);
        if (tree == null) return null;
        String root = rootDocId(ctx, modName);
        if (root == null) root = DocumentsContract.getTreeDocumentId(tree);
        String doc = ModImporter.findDocIdPublic(ctx, tree, root, "stage");
        if (doc != null) prefs(ctx).edit().putString("stagedoc_" + modName, doc).apply();
        return doc;
    }

    /** 列出模组的 assets/stage/ 里所有 btl。 */
    public static List<Entry> list(Context ctx, String modName) {
        List<Entry> out = new ArrayList<>();
        Uri tree = modUri(ctx, modName);
        if (tree == null) return out;
        String stage = stageDocId(ctx, modName);
        if (stage == null) return out;
        for (String[] child : ModImporter.listChildren(ctx, tree, stage)) {
            if (child[0].toLowerCase(Locale.US).endsWith(".btl")) out.add(new Entry(child[0], child[1]));
        }
        Collections.sort(out, (a, b) -> a.name.compareToIgnoreCase(b.name));
        return out;
    }

    /** 读取一条地图的字节。 */
    public static byte[] read(Context ctx, String modName, Entry e) throws Exception {
        Uri tree = modUri(ctx, modName);
        if (tree == null) throw new Exception("模组位置已失效，请重新导入模组");
        return ModImporter.readDoc(ctx, tree, e.docId);
    }

    /**
     * 保存回写：先备份到 /sdcard/地图编辑器/模组备份/&lt;模组名&gt;/&lt;文件名&gt;.&lt;时间&gt;.bak，
     * 再把新内容覆盖回模组里的那个 btl。返回备份文件路径（失败返回 null）。
     */
    public static String writeBack(Context ctx, String modName, Entry e, byte[] data, byte[] originalBytes) {
        String backupPath = null;
        try {
            if (originalBytes != null && originalBytes.length > 0) {
                File dir = new File(android.os.Environment.getExternalStorageDirectory(),
                        "地图编辑器/模组备份/" + modName);
                if (!dir.exists()) dir.mkdirs();
                String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
                File bak = new File(dir, e.name + "." + ts + ".bak");
                FileOutputStream fos = new FileOutputStream(bak);
                try { fos.write(originalBytes); } finally { ModAssets.close(fos); }
                backupPath = bak.getAbsolutePath();
            }
        } catch (Exception ignored) { }
        try {
            Uri tree = modUri(ctx, modName);
            if (tree == null) return backupPath;
            Uri doc = DocumentsContract.buildDocumentUriUsingTree(tree, e.docId);
            OutputStream out = ctx.getContentResolver().openOutputStream(doc, "wt");
            if (out == null) out = ctx.getContentResolver().openOutputStream(doc);
            if (out == null) return backupPath;
            try {
                out.write(data);
                out.flush();
            } finally {
                ModAssets.close(out);
            }
        } catch (Exception ignored) { }
        return backupPath;
    }
}
