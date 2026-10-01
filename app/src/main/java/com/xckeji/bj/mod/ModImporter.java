package com.xckeji.bj.mod;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 模组导入：从「解包后的模组文件夹（含 assets/）」或「直接用 assets 文件夹」里，
 * 把编辑器要用的素材捞出来、按内置目录结构存进 App 私有目录 files/mods/&lt;模组名&gt;/。
 *
 * - 图集（webp/png + xml）按 &lt;Image name x y w h&gt; 裁成单张，文件名保持和内置素材一致；
 * - 单张图片（将领头像等）直接拷；
 * - json 配置整目录拷（ArmySettings / CountrySettings / BuildingSettings …）；
 * - 记住模组根目录（SAF 树 URI），供「打开模组地图」「保存回写」使用。
 */
public class ModImporter {

    public interface Progress { void onStep(String msg); }

    public static class Result {
        public String name;
        public String error;
        public int images;        // 切出来的单张图
        public int copied;        // 直接拷过来的文件（json/单图）
        public int skipped;       // 内置里没有对应文件的（用不到）
        public File dir;          // 素材落地的模组目录
        public final List<String> atlasInfo = new ArrayList<>();
        public boolean ok() { return error == null && name != null; }
        public String summary() {
            if (!ok()) return "导入失败：" + error;
            StringBuilder sb = new StringBuilder();
            sb.append("模组「").append(name).append("」导入完成\n");
            sb.append("切出图片 ").append(images).append(" 张，直接拷贝 ").append(copied).append(" 个文件");
            if (skipped > 0) sb.append("（" ).append(skipped).append(" 张内置用不到的跳过）");
            sb.append("\n");
            for (String s : atlasInfo) sb.append("· ").append(s).append("\n");
            sb.append("\n【素材落盘统计】\n").append(countText(dir));
            return sb.toString();
        }
    }

    /** 模组目录里各类素材的数量（导入报告和模组页诊断都用它）。 */
    public static String countText(File dir) {
        if (dir == null || !dir.isDirectory()) return "（模组目录不存在）";
        return "国旗 " + countFiles(new File(dir, "flag")) + " 面"
                + "　兵种图标 " + countFiles(new File(dir, "legion")) + " 个"
                + "　建筑 " + countFiles(new File(dir, "building")) + " 个"
                + "\n地形贴图 " + countFiles(new File(dir, "map")) + " 张"
                + "　设施 " + countFiles(new File(dir, "image/status")) + " 个"
                + "　将领头像 " + countFiles(new File(dir, "general")) + " 张"
                + "　配置 " + countFiles(new File(dir, "json")) + " 个"
                + "　底图 " + countFiles(new File(dir, "bin")) + " 个";
    }

    private static int countFiles(File d) {
        File[] fs = d.listFiles();
        return fs == null ? 0 : fs.length;
    }

    /** 要切图的图集（名字 = assets 里 xml/webp 的文件名主干）。 */
    private static final String[] ATLASES = {
            "image_flags_hd", "image_legion_icon_hd", "buildings_hd", "terrain_hd",
            "image_facilities_hd", "image_cityfeature_hd", "image_units_hd",
    };
    /** 内置素材目录优先级：切出来的图按名字落到这里面对应的目录。 */
    private static final String[] BUILTIN_DIRS = {"flag", "legion", "building", "map", "image/status", "general", "btl", "pixmap"};
    /** 要拷进模组的 json 配置。 */
    private static final String[] JSONS = {
            "ArmySettings.json", "ArmySubTypeSettings.json", "ArmyLevelSettings.json",
            "ArmyBuffSettings.json", "ArmyFeatureSettings.json", "ArmyNumberSettings.json",
            "CountrySettings.json", "BuildingSettings.json", "CitySettings.json",
            "CityFeatureSettings.json", "FacilitySettings.json", "EliteArmySettings.json",
            "GeneralSettings.json", "GeneralLevelSettings.json", "GeneralPhotoSettings.json",
            "GeneralQualitySettings.json", "SkillSettings.json", "TechResearchSettings.json",
    };

    private ModImporter() {}

    /** 从系统文件夹选择器的树 URI 导入。 */
    public static Result importFromTree(Context ctx, Uri treeUri, Progress cb) {
        Result r = new Result();
        try {
            String modName = displayName(ctx, treeUri);
            if (modName == null || modName.trim().isEmpty()) modName = "模组";
            String assetsDocId = findAssetsDocId(ctx, treeUri);
            String rootDocId = assetsDocId != null ? assetsDocId : DocumentsContract.getTreeDocumentId(treeUri);
            r.name = modName;
            File modDir = new File(ModAssets.modsBase(ctx), modName);
            if (modDir.exists()) ModAssets.delete(ctx, modName);
            if (!modDir.exists() && !modDir.mkdirs()) { r.error = "无法创建模组目录"; return r; }
            r.dir = modDir;

            // 记住模组位置，供「打开模组地图 / 保存回写」用
            SharedPreferences sp = ctx.getSharedPreferences("mod_prefs", Context.MODE_PRIVATE);
            sp.edit()
                    .putString("uri_" + modName, treeUri.toString())
                    .putString("rootdoc_" + modName, rootDocId)
                    .apply();

            step(cb, "正在读取图集…");
            Map<String, String> builtinIndex = indexBuiltin(ctx);
            for (String atlas : ATLASES) {
                String xmlDoc = findDoc(ctx, treeUri, rootDocId, atlas + ".xml");
                if (xmlDoc == null) continue;
                Bitmap atlasBmp = null;
                for (String ext : new String[]{".webp", ".png", ".jpg"}) {
                    String imgDoc = findDoc(ctx, treeUri, rootDocId, atlas + ext);
                    if (imgDoc != null) {
                        atlasBmp = decodeDoc(ctx, treeUri, imgDoc);
                        break;
                    }
                }
                if (atlasBmp == null) continue;
                String xml = new String(readDoc(ctx, treeUri, xmlDoc), "UTF-8");
                int cut = cutAtlas(modDir, xml, atlasBmp, builtinIndex, r,
                        "terrain_hd".equals(atlas) ? "map/" : null);
                atlasBmp.recycle();
                r.atlasInfo.add(atlas + "：" + cut + " 张");
                step(cb, atlas + " → " + cut + " 张");
            }

            // json 配置
            step(cb, "正在读取配置 json…");
            String jsonDocId = findDocId(ctx, treeUri, rootDocId, "json", true);
            if (jsonDocId != null) {
                for (String js : JSONS) {
                    String doc = findDocIn(ctx, treeUri, jsonDocId, js);
                    if (doc == null) continue;
                    byte[] data = readDoc(ctx, treeUri, doc);
                    ModAssets.writeModFile(modDir, "json/" + js, data);
                    r.copied++;
                }
            }
            // 将领头像（单张 webp/png）：generalphoto/general_XXX.webp → general/XXX.webp
            step(cb, "正在读取将领头像…");
            String gpDocId = findDocId(ctx, treeUri, rootDocId, "image/generalphoto", true);
            if (gpDocId == null) gpDocId = findDocId(ctx, treeUri, rootDocId, "generalphoto", true);
            if (gpDocId != null) {
                for (String[] child : listChildren(ctx, treeUri, gpDocId)) {
                    String nm = child[0];
                    if (!(nm.toLowerCase().endsWith(".webp") || nm.toLowerCase().endsWith(".png"))) continue;
                    String base = stripExt(nm);
                    if (base.startsWith("general_")) base = base.substring("general_".length());
                    String target = builtinIndex.get((base + ".webp").toLowerCase());
                    if (target == null) target = builtinIndex.get((base + ".png").toLowerCase());
                    if (target == null) target = "general/" + base + ".webp";   // 模组特有将领
                    ModAssets.writeModFile(modDir, target, readDocUri(ctx, treeUri, child[1]));
                    r.copied++;
                }
            }
            // def_map.xml（地图序号 → 底图文件名）
            String cfgDocId = findDocId(ctx, treeUri, rootDocId, "config", true);
            if (cfgDocId != null) {
                String dm = findDocIn(ctx, treeUri, cfgDocId, "def_map.xml");
                if (dm != null) {
                    ModAssets.writeModFile(modDir, "config/def_map.xml", readDoc(ctx, treeUri, dm));
                    r.copied++;
                }
            }
            // 世界底图（world.bin / world2.bin / mapN.bin）：存到 bin/，打开征服时自动用
            step(cb, "正在读取世界底图…");
            for (String binName : new String[]{"world.bin", "world2.bin", "world3.bin",
                    "map1.bin", "map2.bin", "map1_hd.bin", "map2_hd.bin"}) {
                String doc = findDocIn(ctx, treeUri, rootDocId, binName);
                if (doc == null) continue;
                ModAssets.writeModFile(modDir, "bin/" + binName, readDoc(ctx, treeUri, doc));
                r.copied++;
            }
            if (r.images == 0 && r.copied == 0) {
                r.error = "没找到可用素材（要选解包后的模组根目录，里面应有 assets/ 或 image/、json/）";
            }
            return r;
        } catch (Exception e) {
            r.error = e.getMessage() == null ? e.toString() : e.getMessage();
            return r;
        }
    }

    private static void step(Progress cb, String msg) { if (cb != null) cb.onStep(msg); }

    /** apk 模式：assets 根目录下的 world*.bin / mapN.bin / mapN_hd.bin（世界底图）。 */
    private static boolean isWorldBinEntry(String en) {
        if (!en.startsWith("assets/") || !en.endsWith(".bin")) return false;
        String fn = en.substring("assets/".length());
        if (fn.contains("/")) return false;
        String low = fn.toLowerCase();
        if (!(low.startsWith("world") || low.startsWith("map"))) return false;
        return !low.contains("anim") && !low.startsWith("maptext");
    }

    /** 按图集 xml 把里面的小图裁出来，落到内置素材对应的目录。返回裁出张数。 */
    private static int cutAtlas(File modDir, String xml, Bitmap atlasBmp,
                                Map<String, String> builtinIndex, Result r) throws Exception {
        return cutAtlas(modDir, xml, atlasBmp, builtinIndex, r, null);
    }

    /**
     * @param fallbackDir 内置素材里找不到同名文件时的兜底目录（null = 跳过）。
     *                    模组特有的国旗/兵种图标/建筑编号就靠它存下来。
     */
    private static int cutAtlas(File modDir, String xml, Bitmap atlasBmp,
                                Map<String, String> builtinIndex, Result r,
                                String fallbackDir) throws Exception {
        int cut = 0;
        Matcher m = Pattern.compile(
                "<Image\\s+name=\"([^\"]+)\"\\s+x=\"(\\d+)\"\\s+y=\"(\\d+)\"\\s+w=\"(\\d+)\"\\s+h=\"(\\d+)\"")
                .matcher(xml);
        // 缩放判定：不能直接拿位图尺寸比（很多图集右侧/底部是留白的，例如光宇3.0 的
        // image_flags_hd.webp 是 744×692，但真正有画的区域只有 572×536，跟 xml 布局一致）。
        // 所以先量出「有内容的最大范围」，只有它确实不等于 xml 布局时才按比例缩放裁图。
        float scale = 1f;
        {
            int maxX = 0, maxY = 0;
            Matcher mm = Pattern.compile("<Image\\s+name=\"[^\"]+\"\\s+x=\"(\\d+)\"\\s+y=\"(\\d+)\"\\s+w=\"(\\d+)\"\\s+h=\"(\\d+)\"")
                    .matcher(xml);
            while (mm.find()) {
                maxX = Math.max(maxX, Integer.parseInt(mm.group(1)) + Integer.parseInt(mm.group(3)));
                maxY = Math.max(maxY, Integer.parseInt(mm.group(2)) + Integer.parseInt(mm.group(4)));
            }
            if (maxX > 0 && maxY > 0 && atlasBmp.hasAlpha()) {
                int contentW = atlasBmp.getWidth(), contentH = atlasBmp.getHeight();
                // 从右/下往回找第一列(行)有非透明像素的位置
                outer:
                for (int x = atlasBmp.getWidth() - 1; x >= 0; x--) {
                    for (int y = atlasBmp.getHeight() - 1; y >= 0; y -= 2) {
                        if ((atlasBmp.getPixel(x, y) >>> 24) != 0) { contentW = x + 1; break outer; }
                    }
                }
                outer2:
                for (int y = atlasBmp.getHeight() - 1; y >= 0; y--) {
                    for (int x = atlasBmp.getWidth() - 1; x >= 0; x -= 2) {
                        if ((atlasBmp.getPixel(x, y) >>> 24) != 0) { contentH = y + 1; break outer2; }
                    }
                }
                float sx = contentW / (float) maxX;
                float sy = contentH / (float) maxY;
                // 只在“内容范围明显不等于 xml 布局”时才缩放（真正的 2 倍/更高密度图集），
                // 差异小的（留白、1~2 像素误差）一律按 1:1 裁，避免误伤
                if (Math.abs(sx - sy) / Math.max(sx, sy) < 0.05f && (sx > 1.35f || sx < 0.65f)) {
                    scale = (sx + sy) / 2f;
                }
            }
        }
        while (m.find()) {
            String name = m.group(1);
            int x = Math.round(Integer.parseInt(m.group(2)) * scale);
            int y = Math.round(Integer.parseInt(m.group(3)) * scale);
            int w = Math.round(Integer.parseInt(m.group(4)) * scale);
            int h = Math.round(Integer.parseInt(m.group(5)) * scale);
            if (x < 0 || y < 0 || w <= 0 || h <= 0
                    || x + w > atlasBmp.getWidth() || y + h > atlasBmp.getHeight()) continue;
            String target = builtinIndex.get(name.toLowerCase());
            if (target == null) target = builtinIndex.get(stripExt(name).toLowerCase());
            if (target == null) {
                String fb = fallbackDirFor(name, fallbackDir);
                if (fb == null) { r.skipped++; continue; }
                target = fb + name;
            }
            Bitmap sub = Bitmap.createBitmap(atlasBmp, x, y, w, h);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            sub.compress(Bitmap.CompressFormat.PNG, 100, bos);
            ModAssets.writeModFile(modDir, target, bos.toByteArray());
            if (sub != atlasBmp) sub.recycle();
            r.images++;
            cut++;
        }
        return cut;
    }

    /** 内置里没有同名文件时，按名字前缀决定放到哪个目录（模组特有素材靠这个）。 */
    private static String fallbackDirFor(String name, String atlasFallback) {
        String n = name.toLowerCase();
        if (n.startsWith("flag_")) return "flag/";
        if (n.startsWith("legion_icon_")) return "legion/";
        if (n.startsWith("building_")) return "building/";
        if (n.startsWith("facility_") || n.startsWith("city_feature_")
                || n.startsWith("antiair") || n.startsWith("formation") || n.startsWith("lv_")) {
            return "image/status/";
        }
        if (n.startsWith("wonder_")) return "image/status/";
        return atlasFallback;
    }

    /**
     * 直接选 apk 导入：只从 zip 里挑需要的条目（十来个图集 + json + 将领头像），
     * 不解包整个 apk，也不读 classes.dex / res / .pkm。
     */
    public static Result importFromApk(Context ctx, Uri apkUri, String apkName, Progress cb) {
        Result r = new Result();
        try {
            String modName = apkName == null ? "模组" : stripExt(apkName);
            r.name = modName;
            File modDir = new File(ModAssets.modsBase(ctx), modName);
            if (modDir.exists()) ModAssets.delete(ctx, modName);
            if (!modDir.exists() && !modDir.mkdirs()) { r.error = "无法创建模组目录"; return r; }
            r.dir = modDir;

            java.util.Set<String> want = new java.util.HashSet<>();
            for (String a : ATLASES) {
                for (String ext : new String[]{".xml", ".webp", ".png", ".jpg"}) {
                    want.add("assets/" + a + ext);
                    want.add("assets/image/" + a + ext);
                }
            }
            for (String js : JSONS) want.add("assets/json/" + js);
            want.add("assets/config/def_map.xml");
            // 世界底图（征服用）：world.bin / world2.bin / mapN.bin / mapN_hd.bin
            for (String bin : new String[]{"world.bin", "world2.bin", "world3.bin",
                    "map1.bin", "map2.bin", "map1_hd.bin", "map2_hd.bin"}) {
                want.add("assets/" + bin);
            }

            Map<String, String> builtinIndex = indexBuiltin(ctx);
            java.util.Map<String, byte[]> got = new java.util.HashMap<>();
            java.util.List<String[]> generals = new ArrayList<>();   // [文件名, 内容]

            InputStream raw = ctx.getContentResolver().openInputStream(apkUri);
            java.util.zip.ZipInputStream zin = new java.util.zip.ZipInputStream(
                    new java.io.BufferedInputStream(raw, 1 << 16));
            try {
                java.util.zip.ZipEntry e;
                while ((e = zin.getNextEntry()) != null) {
                    String en = e.getName();
                    if (e.isDirectory()) continue;
                    boolean need = want.contains(en)
                            || en.startsWith("assets/image/generalphoto/")
                            || en.startsWith("assets/generalphoto/")
                            || isWorldBinEntry(en);
                    if (!need) continue;
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = zin.read(buf)) > 0) bos.write(buf, 0, n);
                    byte[] data = bos.toByteArray();
                    if (en.startsWith("assets/image/generalphoto/") || en.startsWith("assets/generalphoto/")) {
                        generals.add(new String[]{en.substring(en.lastIndexOf('/') + 1), null});
                        got.put("gen:" + en.substring(en.lastIndexOf('/') + 1), data);
                    } else {
                        got.put(en, data);
                    }
                }
            } finally {
                ModAssets.close(zin);
            }

            step(cb, "正在切图…");
            for (String atlas : ATLASES) {
                byte[] xmlB = got.get("assets/" + atlas + ".xml");
                if (xmlB == null) xmlB = got.get("assets/image/" + atlas + ".xml");
                if (xmlB == null) continue;
                byte[] imgB = got.get("assets/" + atlas + ".webp");
                if (imgB == null) imgB = got.get("assets/" + atlas + ".png");
                if (imgB == null) imgB = got.get("assets/image/" + atlas + ".webp");
                if (imgB == null) imgB = got.get("assets/image/" + atlas + ".png");
                if (imgB == null) continue;
                Bitmap bmp = BitmapFactory.decodeByteArray(imgB, 0, imgB.length);
                if (bmp == null) continue;
                int cut = cutAtlas(modDir, new String(xmlB, java.nio.charset.Charset.forName("UTF-8")),
                        bmp, builtinIndex, r, "terrain_hd".equals(atlas) ? "map/" : null);
                bmp.recycle();
                r.atlasInfo.add(atlas + "：" + cut + " 张");
                step(cb, atlas + " → " + cut + " 张");
            }
            for (String js : JSONS) {
                byte[] d = got.get("assets/json/" + js);
                if (d == null) continue;
                ModAssets.writeModFile(modDir, "json/" + js, d);
                r.copied++;
            }
            byte[] dm = got.get("assets/config/def_map.xml");
            if (dm != null) {
                ModAssets.writeModFile(modDir, "config/def_map.xml", dm);
                r.copied++;
            }
            // 世界底图存到 bin/，打开征服时自动用（不用再让用户手动选 bin）
            for (java.util.Map.Entry<String, byte[]> en : got.entrySet()) {
                String k = en.getKey();
                if (!isWorldBinEntry(k)) continue;
                String fn = k.substring("assets/".length());
                ModAssets.writeModFile(modDir, "bin/" + fn, en.getValue());
                r.copied++;
            }
            for (String[] g : generals) {
                String base = stripExt(g[0]);
                if (base.startsWith("general_")) base = base.substring("general_".length());
                String target = builtinIndex.get((base + ".webp").toLowerCase());
                if (target == null) target = builtinIndex.get((base + ".png").toLowerCase());
                if (target == null) target = "general/" + base + ".webp";   // 模组特有将领
                byte[] d = got.get("gen:" + g[0]);
                if (d == null) { r.skipped++; continue; }
                ModAssets.writeModFile(modDir, target, d);
                r.copied++;
            }
            if (r.images == 0 && r.copied == 0) {
                r.error = "这个 apk 里没找到可用素材（要选模组的 apk，不是游戏的原始 apk）";
            }
            return r;
        } catch (Exception e) {
            r.error = e.getMessage() == null ? e.toString() : e.getMessage();
            return r;
        }
    }

    private static String stripExt(String name) {
        int d = name.lastIndexOf('.');
        return d > 0 ? name.substring(0, d) : name;
    }

    /** 内置素材索引：小写名 → 相对路径（含原扩展名）。 */
    private static Map<String, String> indexBuiltin(Context ctx) {
        Map<String, String> map = new LinkedHashMap<>();
        for (String dir : BUILTIN_DIRS) {
            try {
                String[] fs = ctx.getAssets().list(dir);
                if (fs == null) continue;
                for (String f : fs) {
                    map.put(f.toLowerCase(), dir + "/" + f);
                    map.put(stripExt(f).toLowerCase(), dir + "/" + f);
                }
            } catch (Exception ignored) { }
        }
        return map;
    }

    // ---------- SAF 小工具 ----------

    private static String displayName(Context ctx, Uri treeUri) {
        try {
            String docId = DocumentsContract.getTreeDocumentId(treeUri);
            Uri doc = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId);
            android.database.Cursor c = ctx.getContentResolver().query(doc, null, null, null, null);
            if (c != null) {
                try {
                    if (c.moveToFirst()) {
                        int i = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
                        if (i >= 0) return c.getString(i);
                    }
                } finally { c.close(); }
            }
        } catch (Exception ignored) { }
        return null;
    }

    /** 树里有没有 assets 子目录，有就返回它的 docId。 */
    private static String findAssetsDocId(Context ctx, Uri treeUri) {
        return findDocId(ctx, treeUri, DocumentsContract.getTreeDocumentId(treeUri), "assets", true);
    }

    private static String findDocId(Context ctx, Uri treeUri, String parentDocId, String name, boolean dir) {
        for (String[] child : listChildren(ctx, treeUri, parentDocId)) {
            if (!child[0].equalsIgnoreCase(name)) continue;
            return child[1];
        }
        // 允许 "image/generalphoto" 这种多级路径
        if (name.contains("/")) {
            String[] parts = name.split("/");
            String cur = parentDocId;
            for (String p : parts) {
                cur = findDocId(ctx, treeUri, cur, p, true);
                if (cur == null) return null;
            }
            return cur;
        }
        return null;
    }

    private static String findDoc(Context ctx, Uri treeUri, String rootDocId, String fileName) {
        String hit = findDocIn(ctx, treeUri, rootDocId, fileName);
        if (hit != null) return hit;
        // 图集 xml 可能在 image/ 子目录里
        String imgDir = findDocId(ctx, treeUri, rootDocId, "image", true);
        if (imgDir != null) return findDocIn(ctx, treeUri, imgDir, fileName);
        return null;
    }

    private static String findDocIn(Context ctx, Uri treeUri, String parentDocId, String fileName) {
        for (String[] child : listChildren(ctx, treeUri, parentDocId)) {
            if (child[0].equalsIgnoreCase(fileName)) return child[1];
        }
        return null;
    }

    /** 给 ModMaps 用：在某个父目录下找子目录/文件的 docId。 */
    public static String findDocIdPublic(Context ctx, Uri treeUri, String parentDocId, String name) {
        return findDocId(ctx, treeUri, parentDocId, name, true);
    }

    /** 列出子项：返回 [名字, docId] 列表。 */
    public static List<String[]> listChildren(Context ctx, Uri treeUri, String parentDocId) {
        List<String[]> out = new ArrayList<>();
        try {
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId);
            String[] proj = {
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            };
            android.database.Cursor c = ctx.getContentResolver().query(children, proj, null, null, null);
            if (c != null) {
                try {
                    while (c.moveToNext()) out.add(new String[]{c.getString(1), c.getString(0)});
                } finally { c.close(); }
            }
        } catch (Exception ignored) { }
        return out;
    }

    public static byte[] readDoc(Context ctx, Uri treeUri, String docId) throws Exception {
        return readDocUri(ctx, treeUri, docId);
    }

    public static byte[] readDocUri(Context ctx, Uri treeUri, String docId) throws Exception {
        Uri doc = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId);
        InputStream in = ctx.getContentResolver().openInputStream(doc);
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        } finally {
            ModAssets.close(in);
        }
    }

    private static Bitmap decodeDoc(Context ctx, Uri treeUri, String docId) {
        try {
            Uri doc = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId);
            InputStream in = ctx.getContentResolver().openInputStream(doc);
            try {
                return BitmapFactory.decodeStream(in);
            } finally {
                ModAssets.close(in);
            }
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }
}
