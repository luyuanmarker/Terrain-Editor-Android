package com.xckeji.xiaoxiong;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.xckeji.xiaoxiong.file.FileParser;
import com.xckeji.xiaoxiong.model.ArmyConfig;
import com.xckeji.xiaoxiong.model.CountryData;
import com.xckeji.xiaoxiong.model.GeneralData;
import com.xckeji.xiaoxiong.model.MapData;
import com.xckeji.xiaoxiong.model.TerrainTile;
import com.xckeji.xiaoxiong.render.HexMapView;

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * 晓雄编辑器 —— 按「BTL编辑器源码还原」的布局重写的安卓版：
 * 顶部工具栏（打开/保存/另存为/校验/地图/BTL转换/生成bin/转换非截取）
 * + 模式栏（F1 兵种 / F2 建筑 / F3 地形 / F4 全局 / F5 援军 / F6 空袭）
 * + 中间六边形地图 + 右侧字段面板 + 底部状态栏。
 */
public class MainActivity extends Activity implements HexMapView.OnTileSelectListener {

    private static final int REQUEST_OPEN = 101;
    private static final int REQUEST_SAVE = 102;
    private static final int REQUEST_SAVE_BIN = 103;
    private byte[] pendingBytes;
    private String pendingName;

    private static final String[] MODE_NAMES = {"F1 兵种", "F2 建筑", "F3 地形",
            "F4 全局", "F5 援军", "F6 空袭"};

    /** F1 兵种面板字段（与熊编辑器一致）。 */
    private static final String[][] ARMY_FIELDS = {
            {"坐标", "u16", "0x0"}, {"兵种", "u8", "0x2"}, {"等级", "u8", "0x3"},
            {"编制", "u8", "0x4"}, {"方向", "u8", "0x5"}, {"移动力", "u8", "0x6"},
            {"建造回合", "u8", "0x7"}, {"经验", "u16", "0x8"}, {"血量倍率", "u16", "0xA"},
            {"当前血量", "u16", "0xC"}, {"血量上限", "u16", "0xE"}, {"将领", "u16", "0x10"},
            {"军衔", "u8", "0x12"}, {"HP等级", "u8", "0x13"}, {"技能一", "u8", "0x17"},
            {"技能二", "u8", "0x18"}, {"技能三", "u8", "0x19"}, {"技能四", "u8", "0x1A"},
            {"关键据点", "u8", "0x1C"}, {"运输船", "u8", "0x1E"},
    };

    /** F2 建筑面板字段（与熊编辑器一致）。 */
    private static final String[][] BUILDING_FIELDS = {
            {"坐标", "u16", "0x0"}, {"名称", "u16", "0x2"}, {"建筑类型", "u8", "0x4"},
            {"外观", "u8", "0x5"}, {"关键据点", "u8", "0xD"}, {"防空武器", "u8", "0x16"},
            {"防空武器是否携带雷达", "u8", "0x17"}, {"工厂等级", "u8", "0x18"},
            {"科研所等级", "u8", "0x19"}, {"医疗等级", "u8", "0x1A"},
            {"航空等级", "u8", "0x1B"}, {"导弹等级", "u8", "0x1C"}, {"核弹等级", "u8", "0x1D"},
    };

    /** F3 地形面板字段（与熊编辑器一致）。 */
    private static final String[][] TERRAIN_FIELDS = {
            {"地块类型1", "u8", "0x0"}, {"变体1", "u8", "0x1"}, {"地形X偏移", "u8", "0x2"},
            {"地形Y偏移", "u8", "0x3"}, {"贴图1", "u8", "0x4"}, {"贴图1变体", "u8", "0x5"},
            {"贴图1X偏移", "u8", "0x6"}, {"贴图1Y偏移", "u8", "0x7"},
            {"贴图2", "u8", "0x8"}, {"贴图2变体", "u8", "0x9"},
            {"贴图2X偏移", "u8", "0xA"}, {"贴图2Y偏移", "u8", "0xB"},
            {"未知0xC", "u8", "0xC"}, {"未知0xD", "u8", "0xD"},
            {"底层地形", "u8", "0xE"}, {"未知0xF", "u8", "0xF"},
    };

    /** F5 援军 / F6 空袭字段（与熊编辑器一致，v1 与 v3 记录长度不同）。 */
    private static final String[][] REINF_FIELDS = {
            {"坐标", "u16", "0x0"}, {"爆兵事件", "u16", "0x2"}, {"兵种", "u16", "0x4"},
            {"等级", "u16", "0x8"}, {"目标", "u16", "0xA"}, {"编制", "u16", "0xC"},
            {"出现回合", "u32", "0x4C"},
    };
    private static final String[][] AIRSTRIKE_FIELDS = {
            {"坐标", "u32", "0x0"}, {"兵种", "u16", "0x4"}, {"兵种等级", "u16", "0x6"},
            {"弹药", "u32", "0x8"}, {"军团", "u32", "0xC"}, {"回合", "u32", "0x10"},
    };

    private MapData mapData;
    private HexMapView hexMapView;
    private TextView statusBar, panelTitle, mapInfoView;
    private LinearLayout panelBox;
    private int mode = 0;
    private int selIdx = -1;
    private String currentFileName = "未打开文件";
    private Button[] modeButtons;
    private final com.xckeji.xiaoxiong.model.OperationHistory history =
            new com.xckeji.xiaoxiong.model.OperationHistory();
    // 虚拟按键模式：0 无 / 1 地形刷 / 2 放兵种 / 3 放建筑 / 4 取省 / 5 刷省
    private int keyAction = 0;
    private int keyTerrainGroup = 1, keyArmyType = -1, keyBuildingType = 11, keyProvince = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUI();
        loadEditorAssets();
    }

    private void loadEditorAssets() {
        try {
            GeneralData.load(readAsset("json/GeneralSettings.json"));
        } catch (Exception ignored) {
        }
        try {
            GeneralData.loadSkills(readAsset("json/SkillSettings.json"));
        } catch (Exception ignored) {
        }
        try {
            ArmyConfig.load(readAsset("json/ArmySettings.json"));
        } catch (Exception ignored) {
        }
    }

    private byte[] readAsset(String path) throws Exception {
        InputStream in = getAssets().open(path);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return bos.toByteArray();
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void buildUI() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF16213e);

        root.addView(buildToolbar());
        root.addView(buildModeBar());
        root.addView(buildKeyBar());

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.HORIZONTAL);
        hexMapView = new HexMapView(this);
        hexMapView.setOnTileSelectListener(this);
        content.addView(hexMapView, new LinearLayout.LayoutParams(0, -1, 1f));

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setBackgroundColor(0xFF1b1b22);
        panelTitle = new TextView(this);
        panelTitle.setText("字段面板");
        panelTitle.setTextColor(0xFFe5e7eb);
        panelTitle.setTextSize(13);
        panelTitle.setTypeface(null, Typeface.BOLD);
        panelTitle.setPadding(dp(8), dp(8), dp(8), dp(6));
        right.addView(panelTitle);
        panelBox = new LinearLayout(this);
        panelBox.setOrientation(LinearLayout.VERTICAL);
        panelBox.setPadding(dp(8), 0, dp(8), dp(8));
        ScrollView ps = new ScrollView(this);
        ps.addView(panelBox);
        right.addView(ps, new LinearLayout.LayoutParams(-1, 0, 1f));
        content.addView(right, new LinearLayout.LayoutParams(dp(240), -1));

        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1f));

        statusBar = new TextView(this);
        statusBar.setText("未打开文件");
        statusBar.setTextColor(0xFF9ca3af);
        statusBar.setTextSize(11);
        statusBar.setPadding(dp(10), dp(6), dp(10), dp(6));
        statusBar.setBackgroundColor(0xFF0d1526);
        root.addView(statusBar);

        setContentView(root);
        rebuildPanel();
    }

    private View buildToolbar() {
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setBackgroundColor(0xFF1a1a3e);
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(6), dp(4), dp(6), dp(4));
        String[] labels = {"📂 打开BTL", "💾 保存", "📄 另存为", "🔍 校验",
                "🗺️ 地图", "🔄 BTL转换", "📦 生成bin", "🔓 转换非截取"};
        for (String label : labels) {
            Button b = new Button(this);
            b.setText(label);
            b.setTextSize(12);
            b.setAllCaps(false);
            b.setTextColor(Color.WHITE);
            b.setBackgroundColor(0xFF2f3d75);
            b.setPadding(dp(8), 0, dp(8), 0);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(36));
            lp.rightMargin = dp(6);
            b.setLayoutParams(lp);
            if (label.contains("打开BTL")) b.setOnClickListener(v -> openBtl());
            else if (label.contains("另存为")) b.setOnClickListener(v -> saveBtl(true));
            else if (label.contains("保存")) b.setOnClickListener(v -> saveBtl(false));
            else if (label.contains("校验")) b.setOnClickListener(v -> validateFile());
            else if (label.contains("地图")) b.setOnClickListener(v -> loadWorldBin());
            else if (label.contains("BTL转换")) b.setOnClickListener(v -> convertVersionDialog());
            else if (label.contains("生成bin")) b.setOnClickListener(v -> genWorldBin());
            else if (label.contains("转换非截取")) b.setOnClickListener(v -> convertToFullBtl());
            bar.addView(b);
        }
        hs.addView(bar);
        return hs;
    }

    private View buildModeBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(0xFF232329);
        bar.setPadding(dp(6), dp(3), dp(6), dp(3));
        modeButtons = new Button[MODE_NAMES.length];
        for (int i = 0; i < MODE_NAMES.length; i++) {
            final int m = i;
            Button b = new Button(this);
            b.setText(MODE_NAMES[i]);
            b.setTextSize(11);
            b.setAllCaps(false);
            b.setTextColor(Color.WHITE);
            b.setPadding(dp(6), 0, dp(6), 0);
            b.setOnClickListener(v -> {
                mode = m;
                refreshModeButtons();
                rebuildPanel();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(34), 1f);
            lp.rightMargin = dp(3);
            b.setLayoutParams(lp);
            modeButtons[i] = b;
            bar.addView(b);
        }
        refreshModeButtons();
        return bar;
    }

    private void refreshModeButtons() {
        if (modeButtons == null) return;
        for (int i = 0; i < modeButtons.length; i++) {
            modeButtons[i].setBackgroundColor(i == mode ? 0xFF1e5fa8 : 0xFF3a3a40);
        }
    }

    // ================= 打开 / 保存 =================
    private void openBtl() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, REQUEST_OPEN);
    }

    private void saveBtl(boolean asNew) {
        if (mapData == null) {
            Toast.makeText(this, "没有打开的文件", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        String name = currentFileName.replaceAll("(?i)\\.btl$", "");
        i.putExtra(Intent.EXTRA_TITLE, asNew ? (name + "_副本.btl") : (name + ".btl"));
        startActivityForResult(i, REQUEST_SAVE);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (req == REQUEST_OPEN) {
            try {
                byte[] bytes = readUri(uri);
                MapData md = FileParser.loadFile(bytes, displayName(uri));
                mapData = md;
                hexMapView.setMapData(md);
                hexMapView.refresh();
                selIdx = -1;
                currentFileName = displayName(uri);
                updateStatus();
                rebuildPanel();
            } catch (Exception e) {
                Toast.makeText(this, "打开失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        } else if (req == 104) {
            try {
                byte[] bin = readUri(uri);
                FileParser.loadConquestTerrain(mapData, bin);
                mapData.binOriginalData = bin;
                mapData.binFileName = displayName(uri);
                hexMapView.setMapData(mapData);
                hexMapView.refresh();
                rebuildPanel();
                Toast.makeText(this, "世界地形已加载：" + displayName(uri), Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                Toast.makeText(this, "加载失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        } else if (req == REQUEST_SAVE_BIN) {
            try {
                java.io.OutputStream os = getContentResolver().openOutputStream(uri);
                if (os == null || pendingBytes == null) throw new Exception("无法写入");
                os.write(pendingBytes);
                os.close();
                Toast.makeText(this, "已生成：" + displayName(uri), Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                Toast.makeText(this, "写入失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
            } finally {
                pendingBytes = null;
            }
        } else if (req == REQUEST_SAVE) {
            try {
                byte[] out = FileParser.saveAsBTL(mapData);
                java.io.OutputStream fos = getContentResolver().openOutputStream(uri);
                if (fos == null) throw new Exception("无法写入");
                fos.write(out);
                fos.close();
                Toast.makeText(this, "已保存：" + displayName(uri), Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                Toast.makeText(this, "保存失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }
    }

    private byte[] readUri(Uri uri) throws Exception {
        InputStream in = getContentResolver().openInputStream(uri);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return bos.toByteArray();
    }

    private String displayName(Uri uri) {
        try {
            android.database.Cursor c = getContentResolver().query(uri, null, null, null, null);
            if (c != null) {
                int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (idx >= 0 && c.moveToFirst()) {
                    String n = c.getString(idx);
                    c.close();
                    return n;
                }
                c.close();
            }
        } catch (Exception ignored) {
        }
        return "未命名.btl";
    }

    private void validateFile() {
        if (mapData == null || mapData.btlOriginalData == null) {
            Toast.makeText(this, "没有打开的文件", Toast.LENGTH_SHORT).show();
            return;
        }
        java.util.List<String> issues = FileParser.validateAndFix(mapData);
        hexMapView.refresh();
        updateStatus();
        if (issues.isEmpty()) {
            Toast.makeText(this, "✅ 校验通过，没有发现问题", Toast.LENGTH_LONG).show();
        } else {
            StringBuilder sb = new StringBuilder("发现 " + issues.size() + " 项问题（已自动修复）：\n");
            for (int i = 0; i < Math.min(8, issues.size()); i++) sb.append("• ").append(issues.get(i)).append("\n");
            if (issues.size() > 8) sb.append("…共 ").append(issues.size()).append(" 项");
            new android.app.AlertDialog.Builder(this)
                    .setTitle("校验结果")
                    .setMessage(sb.toString())
                    .setPositiveButton("确定", null)
                    .show();
        }
    }

    private void updateStatus() {
        // 工具栏功能实现（追加在文件末尾，见下方）：
        // 地图 / BTL转换 / 生成bin / 转换非截取
        if (mapData == null) {
            statusBar.setText("未打开文件");
            return;
        }
        FileParser.BtlHeaderInfo h = FileParser.parseBTLHeader(mapData.btlOriginalData);
        statusBar.setText(String.format("文件: %s　btl版本 %d　地图序号 %d　截取(%d,%d)　尺寸 %d×%d　"
                        + "军团 %d　建筑 %d　兵种 %d　陷阱 %d　援军 %d　空袭 %d　事件 %d　地块 %d",
                currentFileName, h.version, h.mapId, h.captureX, h.captureY,
                h.width, h.height, h.legionCount, h.buildingCount, h.armyCount,
                h.mineCount, h.reinforceCount, h.airstrikeCount, h.eventCount,
                h.width * h.height));
    }

    // ================= 右侧字段面板 =================
    @Override
    public void onTileSelected(int x, int y, TerrainTile tile) {
        if (mapData == null) return;
        if (handleKeyAction(x, y)) return;
        selIdx = y * mapData.width + x;
        rebuildPanel();
    }

    private void rebuildPanel() {
        if (panelBox == null) return;
        panelBox.removeAllViews();
        panelTitle.setText(MODE_NAMES[mode] + "　字段");
        if (mapData == null) {
            panelBox.addView(hint("请先打开 .btl 文件"));
            return;
        }
        if (mode == 5) {
            buildReinforceOrAirstrikePanel(true);
            return;
        }
        if (mode == 4) {
            buildReinforceOrAirstrikePanel(false);
            return;
        }
        if (selIdx < 0) {
            panelBox.addView(hint("点击地图上的格子查看/编辑字段"));
            return;
        }
        int x = selIdx % mapData.width, y = selIdx / mapData.width;
        switch (mode) {
            case 0: buildArmyPanel(x, y); break;
            case 1: buildBuildingPanel(x, y); break;
            case 2: buildTerrainPanel(selIdx); break;
            default: buildGlobalPanel(x, y); break;
        }
    }

    private TextView hint(String s) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(12);
        tv.setTextColor(0xFF9ca3af);
        tv.setPadding(0, dp(8), 0, dp(8));
        return tv;
    }

    private void addField(String name, String value) {
        addField(name, value, null);
    }

    private void addField(String name, String value, Runnable onEdit) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(3), 0, dp(3));
        TextView k = new TextView(this);
        k.setText(name);
        k.setTextSize(11);
        k.setTextColor(0xFF9ca3af);
        k.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.1f));
        TextView v = new TextView(this);
        v.setText(onEdit == null ? value : (value + "  ✎"));
        v.setTextSize(12);
        v.setTextColor(onEdit == null ? 0xFFe5e7eb : 0xFF7dd3fc);
        v.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(k);
        row.addView(v);
        if (onEdit != null) {
            row.setClickable(true);
            row.setOnClickListener(x -> onEdit.run());
        }
        panelBox.addView(row);
    }

    private void buildArmyPanel(int x, int y) {
        MapData.Army a = findArmy(x, y);
        if (a == null || a.raw == null) {
            panelBox.addView(hint("该格没有兵种（F1 模式下点有兵种的格子）"));
            return;
        }
        addField("btl版本", String.valueOf(FileParser.parseBTLHeader(mapData.btlOriginalData).version));
        for (String[] f : ARMY_FIELDS) {
            final String fname = f[0], ftype = f[1];
            final int off = Integer.decode(f[2]);
            addField(fname, String.valueOf(readField(a.raw, ftype, off)), () -> {
                history.save(mapData, "修改兵种");
                editRecordField(a.raw, ftype, off, fname, () -> {
                    try {
                        FileParser.patchArmy(mapData, a, a.raw);
                    } catch (Exception ignored) {
                    }
                    hexMapView.refresh();
                    rebuildPanel();
                });
            });
        }
        addField("将领名", a.general > 0 ? GeneralData.name(a.general) : "无");
        ArmyConfig cfg = ArmyConfig.byArmy(a.type);
        addField("兵种名", cfg != null ? cfg.name : ("代码" + a.type));
    }

    private void buildBuildingPanel(int x, int y) {
        MapData.Building b = findBuilding(x, y);
        if (b == null || b.raw == null) {
            panelBox.addView(hint("该格没有建筑（F2 模式下点有建筑的格子）"));
            return;
        }
        for (String[] f : BUILDING_FIELDS) {
            final String fname = f[0], ftype = f[1];
            final int off = Integer.decode(f[2]);
            addField(fname, String.valueOf(readField(b.raw, ftype, off)), () -> {
                history.save(mapData, "修改建筑");
                editRecordField(b.raw, ftype, off, fname, () -> {
                    try {
                        FileParser.patchBuilding(mapData, b, b.raw);
                    } catch (Exception ignored) {
                    }
                    mapData.setBuildingId(b.x, b.y, b.raw[4] & 0xFF);
                    hexMapView.refresh();
                    rebuildPanel();
                });
            });
        }
    }

    private void buildTerrainPanel(int idx) {
        TerrainTile t = mapData.tiles.get(idx);
        byte[] raw = new byte[16];
        t.toBytes(raw, 0);
        for (String[] f : TERRAIN_FIELDS) {
            final String fname = f[0], ftype = f[1];
            final int off = Integer.decode(f[2]);
            addField(fname, String.valueOf(readField(raw, ftype, off)), () -> {
                history.save(mapData, "修改地形");
                editRecordField(raw, ftype, off, fname, () -> {
                    mapData.tiles.get(idx).parseFromBytes(raw, 0);
                    mapData.editedCells.add(idx);
                    writeTerrainBack(idx);
                    hexMapView.refresh();
                    rebuildPanel();
                });
            });
        }
        addField("地形组名", com.xckeji.xiaoxiong.model.TerrainColors.getName(t.bmTerrain1Group));
    }

    private void buildGlobalPanel(int x, int y) {
        int idx = y * mapData.width + x;
        int pv = (mapData.provinces != null && idx < mapData.provinces.length)
                ? mapData.provinces[idx] : -1;
        int own = (mapData.belongs != null && idx < mapData.belongs.length)
                ? (mapData.belongs[idx] & 0xFF) : 0xFF;
        final int fIdx = idx;
        addField("归属（军团序号）", own == 0xFF ? "无（255）" : String.valueOf(own), () -> {
            final EditText et = new EditText(this);
            et.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
            et.setText(String.valueOf(own));
            et.setTextColor(0xFFe5e7eb);
            new android.app.AlertDialog.Builder(this)
                    .setTitle("修改归属（0-255，255=中立）")
                    .setView(et)
                    .setNegativeButton("取消", null)
                    .setPositiveButton("确定", (d, w) -> {
                        try {
                            int v = Integer.parseInt(et.getText().toString().trim());
                            history.save(mapData, "修改归属");
                            mapData.belongs[fIdx] = (byte) (v & 0xFF);
                            FileParser.patchBelong(mapData, fIdx);
                            hexMapView.refresh();
                            rebuildPanel();
                        } catch (Exception e) {
                            Toast.makeText(this, "输入无效", Toast.LENGTH_SHORT).show();
                        }
                    }).show();
        });
        addField("行政区划", pv == 0xFFFF ? "无（65535）" : String.valueOf(pv), () -> {
            final EditText et = new EditText(this);
            et.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
            et.setText(String.valueOf(pv));
            et.setTextColor(0xFFe5e7eb);
            new android.app.AlertDialog.Builder(this)
                    .setTitle("修改行政区划（地块序号，65535=无）")
                    .setView(et)
                    .setNegativeButton("取消", null)
                    .setPositiveButton("确定", (d, w) -> {
                        try {
                            int v = Integer.parseInt(et.getText().toString().trim());
                            history.save(mapData, "修改行政区划");
                            mapData.provinces[fIdx] = v;
                            FileParser.patchProvince(mapData, fIdx);
                            hexMapView.refresh();
                            rebuildPanel();
                        } catch (Exception e) {
                            Toast.makeText(this, "输入无效", Toast.LENGTH_SHORT).show();
                        }
                    }).show();
        });
        addField("地块坐标", idx + "（" + x + "," + y + "）");
        boolean isCapital = false;
        for (int i = 0; i < mapData.legions.size(); i++) {
            MapData.Legion lg = mapData.legions.get(i);
            if (lg.country > 0 && own != 0xFF && i == own) isCapital = false;
        }
        addField("国家首都", String.valueOf(readFieldTail(idx, 4)));
        addField("陷阱", hasTrap(x, y) ? "有" : "无");
        addField("关键据点", mapData.getBuildingId(x, y) > 0 ? "建筑格" : "空地");
        addField("军团数", String.valueOf(mapData.legions.size()));
    }

    private void buildReinforceOrAirstrikePanel(boolean airstrike) {
        FileParser.BtlHeaderInfo h = FileParser.parseBTLHeader(mapData.btlOriginalData);
        int count = airstrike ? h.airstrikeCount : h.reinforceCount;
        int start = airstrike ? mapData.airstrikeStart : mapData.reinforceStart;
        int size = airstrike ? 20 : (h.version == 1 ? 80 : 104);
        panelBox.addView(hint((airstrike ? "空袭" : "援军") + " 共 " + count + " 条"));
        byte[] data = mapData.btlOriginalData;
        String[][] fields = airstrike ? AIRSTRIKE_FIELDS : REINF_FIELDS;
        for (int i = 0; i < count && i < 60; i++) {
            int addr = start + i * size;
            if (addr + size > data.length) break;
            StringBuilder head = new StringBuilder("#" + (i + 1));
            for (String[] f : fields) {
                int off = Integer.decode(f[2]);
                if (off + 4 > size) continue;
                head.append("  ").append(f[0]).append(":").append(readField(data, f[1], addr + off));
            }
            TextView tv = new TextView(this);
            tv.setText(head.toString());
            tv.setTextSize(11);
            tv.setTextColor(0xFFcbd5e1);
            tv.setPadding(0, dp(2), 0, dp(2));
            panelBox.addView(tv);
        }
    }

    private int readFieldTail(int idx, int size) {
        return 0;
    }

    private boolean hasTrap(int x, int y) {
        if (mapData.traps == null) return false;
        for (MapData.Trap t : mapData.traps) {
            if (t.x == x && t.y == y) return true;
        }
        return false;
    }

    private MapData.Army findArmy(int x, int y) {
        if (mapData.armies == null) return null;
        for (MapData.Army a : mapData.armies) {
            if (a.x == x && a.y == y) return a;
        }
        return null;
    }

    private MapData.Building findBuilding(int x, int y) {
        if (mapData.buildings == null) return null;
        for (MapData.Building b : mapData.buildings) {
            if (b.x == x && b.y == y) return b;
        }
        return null;
    }

    private static int readField(byte[] raw, String type, int off) {
        if (raw == null || off < 0) return 0;
        switch (type) {
            case "u8":
                return off < raw.length ? (raw[off] & 0xFF) : 0;
            case "u16":
                if (off + 2 > raw.length) return 0;
                return (raw[off] & 0xFF) | ((raw[off + 1] & 0xFF) << 8);
            default:
                if (off + 4 > raw.length) return 0;
                return (raw[off] & 0xFF) | ((raw[off + 1] & 0xFF) << 8)
                        | ((raw[off + 2] & 0xFF) << 16) | ((raw[off + 3] & 0xFF) << 24);
        }
    }

    // ================= 虚拟按键条（替代熊编辑器的键盘快捷键） =================
    private View buildKeyBar() {
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setBackgroundColor(0xFF141426);
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(6), dp(3), dp(6), dp(3));
        String[] keys = {"✏️地形刷(R)", "🏠放建筑(B)", "🎖️放兵种(G)", "🈯取省(C)",
                "🖌️刷省(V)", "↩️撤销(Z)", "🗑️清除按键", "🏳️国家配置",
                "⬆️扩展W", "⬇️扩展S", "⬅️扩展A", "➡️扩展D",
                "⬆️收缩I", "⬇️收缩K", "⬅️收缩J", "➡️收缩L"};
        for (String k : keys) {
            Button b = new Button(this);
            b.setText(k);
            b.setTextSize(11);
            b.setAllCaps(false);
            b.setTextColor(Color.WHITE);
            b.setBackgroundColor(0xFF3a3a40);
            b.setPadding(dp(8), 0, dp(8), 0);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(32));
            lp.rightMargin = dp(5);
            b.setLayoutParams(lp);
            b.setOnClickListener(v -> onVirtualKey(k));
            bar.addView(b);
        }
        hs.addView(bar);
        return hs;
    }

    private void onVirtualKey(String key) {
        if (key.contains("清除")) {
            keyAction = 0;
            Toast.makeText(this, "已退出按键模式", Toast.LENGTH_SHORT).show();
            return;
        }
        if (key.contains("国家配置")) {
            showNationConfigDialog();
            return;
        }
        // 扩展 / 收缩：完全按枭雄（WASD 扩展 · IJKL 收缩，扩展时要选填充地形）
        if (key.contains("扩展") || key.contains("收缩")) {
            final boolean expand = key.contains("扩展");
            String dir = key.endsWith("W") || key.endsWith("I") ? "up"
                    : key.endsWith("S") || key.endsWith("K") ? "down"
                    : key.endsWith("A") || key.endsWith("J") ? "left" : "right";
            startResize(dir, expand);
            return;
        }
        if (key.contains("撤销")) {
            if (mapData == null || !history.canUndo()) {
                Toast.makeText(this, "没有可撤销的操作", Toast.LENGTH_SHORT).show();
                return;
            }
            history.undo(mapData);
            hexMapView.refresh();
            updateStatus();
            rebuildPanel();
            Toast.makeText(this, "已撤销", Toast.LENGTH_SHORT).show();
            return;
        }
        if (key.contains("地形刷")) {
            final String[] names = new String[32];
            for (int g = 0; g < 32; g++) names[g] = g + " " + com.xckeji.xiaoxiong.model.TerrainColors.getName(g);
            new android.app.AlertDialog.Builder(this)
                    .setTitle("选择要刷的地形组")
                    .setItems(names, (d, w) -> {
                        keyTerrainGroup = w;
                        keyAction = 1;
                        Toast.makeText(this, "地形刷：点地图即刷「" + names[w] + "」", Toast.LENGTH_LONG).show();
                    }).show();
        } else if (key.contains("放建筑")) {
            final String[] names = new String[40];
            for (int i = 0; i < 40; i++) names[i] = "建筑类型 " + i;
            new android.app.AlertDialog.Builder(this)
                    .setTitle("选择建筑类型")
                    .setItems(names, (d, w) -> {
                        keyBuildingType = w;
                        keyAction = 3;
                        Toast.makeText(this, "放建筑：点地图放置类型 " + w, Toast.LENGTH_LONG).show();
                    }).show();
        } else if (key.contains("放兵种")) {
            if (ArmyConfig.ALL == null || ArmyConfig.ALL.isEmpty()) {
                Toast.makeText(this, "兵种表未加载", Toast.LENGTH_SHORT).show();
                return;
            }
            final java.util.List<ArmyConfig> list = new java.util.ArrayList<>();
            java.util.LinkedHashMap<Integer, ArmyConfig> uniq = new java.util.LinkedHashMap<>();
            for (ArmyConfig c : ArmyConfig.ALL) {
                if (c != null && c.army >= 1 && (c.army <= 40 || c.elite > 0)
                        && !uniq.containsKey(c.army)) uniq.put(c.army, c);
            }
            list.addAll(uniq.values());
            String[] names = new String[list.size()];
            for (int i = 0; i < list.size(); i++) {
                ArmyConfig c = list.get(i);
                names[i] = c.army + " " + (c.name == null ? "" : c.name);
            }
            new android.app.AlertDialog.Builder(this)
                    .setTitle("选择兵种")
                    .setItems(names, (d, w) -> {
                        keyArmyType = list.get(w).army;
                        keyAction = 2;
                        Toast.makeText(this, "放兵种：点地图放置 " + names[w], Toast.LENGTH_LONG).show();
                    }).show();
        } else if (key.contains("取省")) {
            keyAction = 4;
            Toast.makeText(this, "取省：点一个有省区的格子", Toast.LENGTH_LONG).show();
        } else if (key.contains("刷省")) {
            if (keyProvince < 0) {
                Toast.makeText(this, "请先「取省」", Toast.LENGTH_SHORT).show();
                return;
            }
            keyAction = 5;
            Toast.makeText(this, "刷省：点/拖地图把格子划入省区 #" + keyProvince, Toast.LENGTH_LONG).show();
        }
    }

    /** 枭雄流程：先输入距离 → 扩展时选填充地形 → 执行。 */
    private void startResize(final String dir, final boolean expand) {
        if (mapData == null || mapData.btlOriginalData == null) {
            Toast.makeText(this, "请先打开 BTL", Toast.LENGTH_SHORT).show();
            return;
        }
        boolean vertical = dir.equals("up") || dir.equals("down");
        final EditText et = new EditText(this);
        et.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        et.setText("1");
        et.setTextColor(0xFFe5e7eb);
        new android.app.AlertDialog.Builder(this)
                .setTitle("请输入要" + (expand ? "扩展" : "收缩") + "的"
                        + (vertical ? "行数" : "列数") + "（当前 " + mapData.width + "×" + mapData.height + "）")
                .setView(et)
                .setNegativeButton("取消", null)
                .setPositiveButton("确定", (d, w) -> {
                    int n;
                    try {
                        n = Integer.parseInt(et.getText().toString().trim());
                    } catch (Exception e) {
                        n = 0;
                    }
                    if (n <= 0) {
                        Toast.makeText(this, "数量必须是正整数", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (expand) pickFillTerrain(dir, n);
                    else doResize(dir, n, false, 1, 0);
                }).show();
    }

    /** 扩展时选填充地形：一级选地形类型，二级选装饰变体（默认海洋 1/0）。 */
    private void pickFillTerrain(final String dir, final int n) {
        final String[] names = new String[32];
        for (int g = 0; g < 32; g++) {
            names[g] = g + "　" + com.xckeji.xiaoxiong.model.TerrainColors.getName(g);
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle("选择填充地形（新增地块用什么）")
                .setItems(names, (d, group) -> pickDecoration(dir, n, group))
                .setNegativeButton("取消", null)
                .show();
    }

    private void pickDecoration(final String dir, final int n, final int group) {
        final int variants = 9;   // 装饰变体 1..9（与图集一致）
        final String[] names = new String[variants];
        for (int i = 0; i < variants; i++) names[i] = "装饰变体 " + (i + 1);
        new android.app.AlertDialog.Builder(this)
                .setTitle("地形组 " + group + "　选择装饰变体")
                .setItems(names, (d, w) -> doResize(dir, n, true, group, w + 1))
                .setNegativeButton("取消", null)
                .show();
    }

    private void doResize(String dir, int n, boolean expand, int fillGid, int fillTid) {
        try {
            int oldW = mapData.width, oldH = mapData.height;
            FileParser.resizeMap(mapData, dir, n, expand, fillGid, fillTid);
            hexMapView.setMapData(mapData);
            hexMapView.refresh();
            updateStatus();
            rebuildPanel();
            int newTotal = mapData.width * mapData.height;
            Toast.makeText(this, "地图尺寸已调整\n" + oldW + "×" + oldH + " → "
                            + mapData.width + "×" + mapData.height + "\n地块 " + newTotal,
                    Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /** 点地图时的按键动作分发（在 onTileSelected 之前调用）。 */
    private boolean handleKeyAction(int x, int y) {
        if (mapData == null || keyAction == 0) return false;
        int idx = y * mapData.width + x;
        try {
            switch (keyAction) {
                case 1: {
                    history.save(mapData, "地形刷");
                    TerrainTile t = mapData.getTile(x, y);
                    byte[] pat = mapData.getTerrainPattern(keyTerrainGroup);
                    if (pat != null) t.parseFromBytes(pat, 0); else t.setTerrain(keyTerrainGroup);
                    mapData.editedCells.add(idx);
                    mapData.finishPaint(java.util.Collections.singleton(idx));
                    writeTerrainBack(idx);
                    break;
                }
                case 2: {
                    history.save(mapData, "放兵种");
                    FileParser.addArmy(mapData, x, y, keyArmyType, makeArmyRaw(x, y, keyArmyType), 0xFF);
                    break;
                }
                case 3: {
                    history.save(mapData, "放建筑");
                    mapData.setBuildingId(x, y, keyBuildingType);
                    mapData.newBuildingRaws.put(idx, makeBuildingRaw(idx, keyBuildingType));
                    break;
                }
                case 4: {
                    keyProvince = (mapData.provinces != null && idx < mapData.provinces.length)
                            ? mapData.provinces[idx] : -1;
                    Toast.makeText(this, "已取省 #" + keyProvince + "（切到「刷省」）", Toast.LENGTH_LONG).show();
                    keyAction = 0;
                    break;
                }
                case 5: {
                    history.save(mapData, "刷省");
                    if (mapData.provinces != null && idx < mapData.provinces.length) {
                        mapData.provinces[idx] = keyProvince;
                        FileParser.patchProvince(mapData, idx);
                    }
                    break;
                }
            }
            hexMapView.refresh();
            updateStatus();
            rebuildPanel();
            return true;
        } catch (Exception e) {
            Toast.makeText(this, "操作失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
            return true;
        }
    }

    private byte[] makeArmyRaw(int x, int y, int type) {
        FileParser.BtlHeaderInfo h = FileParser.parseBTLHeader(mapData.btlOriginalData);
        int rec = h.version == 1 ? 48 : 64;
        byte[] raw = new byte[rec];
        int coord = (y * mapData.width + x) + mapData.coordBase;
        raw[0] = (byte) (coord & 0xFF);
        raw[1] = (byte) ((coord >>> 8) & 0xFF);
        raw[2] = (byte) type;
        raw[3] = 1;
        raw[4] = 1;
        return raw;
    }

    private byte[] makeBuildingRaw(int idx, int type) {
        byte[] raw = new byte[32];
        int coord = idx + mapData.coordBase;
        raw[0] = (byte) (coord & 0xFF);
        raw[1] = (byte) ((coord >>> 8) & 0xFF);
        raw[4] = (byte) type;
        return raw;
    }

    private void writeTerrainBack(int idx) {
        if (mapData.btlOriginalData == null) return;
        FileParser.BtlHeaderInfo h = FileParser.parseBTLHeader(mapData.btlOriginalData);
        if (!h.independentTerrain) return;   // 征服地图地形在 BIN 里，改动点保存时处理
        int off = h.terrainStart + idx * 16;
        if (off + 16 <= mapData.btlOriginalData.length) {
            mapData.tiles.get(idx).toBytes(mapData.btlOriginalData, off);
        }
    }

    // ================= 字段编辑 =================
    private void editRecordField(final byte[] raw, final String type, final int off,
                                String name, final Runnable after) {
        final EditText et = new EditText(this);
        et.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);
        et.setText(String.valueOf(readField(raw, type, off)));
        et.setTextColor(0xFFe5e7eb);
        new android.app.AlertDialog.Builder(this)
                .setTitle("修改「" + name + "」")
                .setView(et)
                .setNegativeButton("取消", null)
                .setPositiveButton("确定", (d, w) -> {
                    try {
                        int v = Integer.parseInt(et.getText().toString().trim());
                        writeField(raw, type, off, v);
                        if (after != null) after.run();
                    } catch (Exception e) {
                        Toast.makeText(this, "输入无效", Toast.LENGTH_SHORT).show();
                    }
                }).show();
    }

    private static void writeField(byte[] raw, String type, int off, int v) {
        if (raw == null || off < 0) return;
        switch (type) {
            case "u8":
                if (off < raw.length) raw[off] = (byte) (v & 0xFF);
                break;
            case "u16":
                if (off + 2 <= raw.length) {
                    raw[off] = (byte) (v & 0xFF);
                    raw[off + 1] = (byte) ((v >> 8) & 0xFF);
                }
                break;
            default:
                if (off + 4 <= raw.length) {
                    raw[off] = (byte) (v & 0xFF);
                    raw[off + 1] = (byte) ((v >> 8) & 0xFF);
                    raw[off + 2] = (byte) ((v >> 16) & 0xFF);
                    raw[off + 3] = (byte) ((v >> 24) & 0xFF);
                }
        }
    }

    /** 🗺️ 地图：加载世界底图 BIN。 */
    private void loadWorldBin() {
        if (mapData == null) {
            Toast.makeText(this, "请先打开征服 BTL", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, 104);
    }

    /** 🔄 BTL转换：v1 ⇄ v3。 */
    private void convertVersionDialog() {
        if (mapData == null || mapData.btlOriginalData == null) {
            Toast.makeText(this, "没有打开的文件", Toast.LENGTH_SHORT).show();
            return;
        }
        FileParser.BtlHeaderInfo h = FileParser.parseBTLHeader(mapData.btlOriginalData);
        final int target = h.version == 1 ? 3 : 1;
        new android.app.AlertDialog.Builder(this)
                .setTitle("BTL 版本转换")
                .setMessage("当前版本：v" + h.version + "\n\n转换为 v" + target + "？\n\n"
                        + "• 兵种段 48 ⇄ 64 字节\n• 援军段 80 ⇄ 104 字节\n"
                        + "• 胸章 ⇄ 勋章/勋带\n• 版本号与特殊胜利条件同步修正\n\n（改完记得点保存）")
                .setNegativeButton("取消", null)
                .setPositiveButton("转换", (d, w) -> {
                    try {
                        byte[] out = FileParser.convertBtlVersion(mapData, target);
                        mapData = FileParser.loadFile(out, currentFileName);
                        hexMapView.setMapData(mapData);
                        hexMapView.refresh();
                        updateStatus();
                        rebuildPanel();
                        Toast.makeText(this, "已转换为 v" + target + "，请点保存写文件",
                                Toast.LENGTH_LONG).show();
                    } catch (Exception e) {
                        Toast.makeText(this, "转换失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                }).show();
    }

    /** 📦 生成bin：战役 BTL → 官方 world.bin。 */
    private void genWorldBin() {
        if (mapData == null || mapData.btlOriginalData == null) {
            Toast.makeText(this, "没有打开的文件", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            pendingBytes = FileParser.generateWorldBin(mapData);
            pendingName = "world" + currentFileName.replaceAll("(?i)\\.btl$", "") + ".bin";
            savePendingBytes(pendingName);
        } catch (Exception e) {
            Toast.makeText(this, "生成失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /** 🔓 转换非截取：截取地图 → 内嵌地形的完整 BTL。 */
    private void convertToFullBtl() {
        if (mapData == null || mapData.btlOriginalData == null) {
            Toast.makeText(this, "没有打开的文件", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            pendingBytes = FileParser.bakeConquestToStandaloneBtl(mapData);
            pendingName = currentFileName.replaceAll("(?i)\\.btl$", "") + "_非截取.btl";
            savePendingBytes(pendingName);
        } catch (Exception e) {
            Toast.makeText(this, "转换失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void savePendingBytes(String name) {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_TITLE, name);
        startActivityForResult(i, REQUEST_SAVE_BIN);
    }

    private static final String[][] LEGION_FIELDS = {
        {"序号", "i32", "0x0"}, {"国家", "i32", "0x4"}, {"金钱", "i32", "0x8"},
        {"齿轮", "i32", "0xC"}, {"原子", "i32", "0x10"}, {"控制", "i32", "0x14"},
        {"阵营", "i32", "0x18"}, {"战败条件", "i32", "0x1C"},
        {"兵种加成", "f32", "0x20"}, {"税率加成", "f32", "0x24"},
        {"地块颜色", "rgba", "0x28"},
        {"原子弹", "i32", "0x2C"}, {"氢弹", "i32", "0x30"}, {"三相弹", "i32", "0x34"}, {"反物质弹", "i32", "0x38"},
        {"机动等级", "i32", "0x3C"}, {"步枪等级", "i32", "0x40"}, {"迷彩等级", "i32", "0x44"},
        {"工兵等级", "i32", "0x48"}, {"手雷等级", "i32", "0x4C"}, {"迫击炮等级", "i32", "0x50"},
        {"行军等级", "i32", "0x54"}, {"防弹衣等级", "i32", "0x58"}, {"装甲等级", "i32", "0x5C"},
        {"主炮等级", "i32", "0x60"}, {"车体等级", "i32", "0x64"}, {"引擎等级", "i32", "0x68"},
        {"机枪等级", "i32", "0x6C"}, {"突袭等级", "i32", "0x70"}, {"坦克防空等级", "i32", "0x74"},
        {"强化车体等级", "i32", "0x78"}, {"火炮炮击等级", "i32", "0x7C"}, {"火箭弹等级", "i32", "0x80"},
        {"火炮牵引等级", "i32", "0x84"}, {"火炮装甲等级", "i32", "0x88"}, {"火炮火力等级", "i32", "0x8C"},
        {"火炮火箭等级", "i32", "0x90"}, {"伪装等级", "i32", "0x94"}, {"舰艇船体等级", "i32", "0x98"},
        {"推进器等级", "i32", "0x9C"}, {"舰艇装甲等级", "i32", "0xA0"}, {"武器等级", "i32", "0xA4"},
        {"舰艇舰炮等级", "i32", "0xA8"}, {"鱼雷等级", "i32", "0xAC"}, {"舰艇扫雷", "i32", "0xB0"},
        {"防空武器等级", "i32", "0xB4"}, {"现代舰艇等级", "i32", "0xB8"}, {"航空燃油等级", "i32", "0xBC"},
        {"航空发动机等级", "i32", "0xC0"}, {"航空炸弹等级", "i32", "0xC4"}, {"空袭等级", "i32", "0xC8"},
        {"轰炸等级", "i32", "0xCC"}, {"战略轰炸等级", "i32", "0xD0"}, {"空降兵等级", "i32", "0xD4"},
        {"喷气发动机等级", "i32", "0xD8"}, {"机枪堡等级", "i32", "0xDC"}, {"要塞炮等级", "i32", "0xE0"},
        {"海岸炮等级", "i32", "0xE4"}, {"火箭发射器等级", "i32", "0xE8"}, {"工事等级", "i32", "0xEC"},
        {"高射机枪等级", "i32", "0xF0"}, {"防空炮等级", "i32", "0xF4"}, {"对空导弹等级", "i32", "0xF8"},
        {"雷达等级", "i32", "0xFC"}, {"弹头", "i32", "0x100"}, {"固体火箭发动机等级", "i32", "0x104"},
        {"破防等级", "i32", "0x108"}, {"核聚变等级", "i32", "0x10C"},
        {"未知1", "i32", "0x110"}, {"未知2", "i32", "0x114"}, {"未知3", "i32", "0x118"},
        {"国家AI行为", "i32", "0x11C"}, {"初始科技等级", "i32", "0x120"},
        {"离子炮", "i32", "0x124"}, {"初始激光炮等级", "i32", "0x128"}
    };

    /** 国家配置表列名（与熊编辑器 headers 一一对应，按偏移查）。 */
    private static final java.util.Map<Integer, String> LEGION_NAMES = new java.util.HashMap<>();
    static {
        LEGION_NAMES.put(0x0, "行动顺序"); LEGION_NAMES.put(0x4, "国家ID");
        LEGION_NAMES.put(0x8, "初始经济"); LEGION_NAMES.put(0xC, "初始工业");
        LEGION_NAMES.put(0x10, "初始科技"); LEGION_NAMES.put(0x14, "玩家控制");
        LEGION_NAMES.put(0x18, "阵营"); LEGION_NAMES.put(0x1C, "战败条件");
        LEGION_NAMES.put(0x20, "国家血率"); LEGION_NAMES.put(0x24, "国家税率");
        LEGION_NAMES.put(0x28, "国家颜色");
        LEGION_NAMES.put(0x2C, "原子弹"); LEGION_NAMES.put(0x30, "氢弹");
        LEGION_NAMES.put(0x34, "三相弹"); LEGION_NAMES.put(0x38, "反物质弹");
        LEGION_NAMES.put(0x3C, "机动等级"); LEGION_NAMES.put(0x40, "步枪等级");
        LEGION_NAMES.put(0x44, "迷彩等级"); LEGION_NAMES.put(0x48, "工兵等级");
        LEGION_NAMES.put(0x4C, "手雷等级"); LEGION_NAMES.put(0x50, "迫击炮等级");
        LEGION_NAMES.put(0x54, "行军等级"); LEGION_NAMES.put(0x58, "防弹衣等级");
        LEGION_NAMES.put(0x5C, "装甲等级"); LEGION_NAMES.put(0x60, "主炮等级");
        LEGION_NAMES.put(0x64, "车体等级"); LEGION_NAMES.put(0x68, "引擎等级");
        LEGION_NAMES.put(0x6C, "机枪等级"); LEGION_NAMES.put(0x70, "突袭等级");
        LEGION_NAMES.put(0x74, "车载防空等级"); LEGION_NAMES.put(0x78, "强化车体等级");
        LEGION_NAMES.put(0x7C, "火炮等级"); LEGION_NAMES.put(0x80, "火箭弹等级");
        LEGION_NAMES.put(0x84, "牵引等级"); LEGION_NAMES.put(0x88, "火炮装甲等级");
        LEGION_NAMES.put(0x8C, "火力等级"); LEGION_NAMES.put(0x90, "火箭等级");
        LEGION_NAMES.put(0x94, "伪装等级"); LEGION_NAMES.put(0x98, "舰体等级");
        LEGION_NAMES.put(0x9C, "推进器等级"); LEGION_NAMES.put(0xA0, "装甲等级2");
        LEGION_NAMES.put(0xA4, "武器等级"); LEGION_NAMES.put(0xA8, "舰炮等级");
        LEGION_NAMES.put(0xAC, "鱼雷等级"); LEGION_NAMES.put(0xB0, "扫雷等级");
        LEGION_NAMES.put(0xB4, "舰载防空等级"); LEGION_NAMES.put(0xB8, "现代舰体等级");
        LEGION_NAMES.put(0xBC, "航空燃油等级"); LEGION_NAMES.put(0xC0, "航空发动机等级");
        LEGION_NAMES.put(0xC4, "航空炸弹等级"); LEGION_NAMES.put(0xC8, "空袭等级");
        LEGION_NAMES.put(0xCC, "轰炸等级"); LEGION_NAMES.put(0xD0, "战略轰炸等级");
        LEGION_NAMES.put(0xD4, "空降等级"); LEGION_NAMES.put(0xD8, "喷气发动机等级");
        LEGION_NAMES.put(0xDC, "机枪堡等级"); LEGION_NAMES.put(0xE0, "要塞炮等级");
        LEGION_NAMES.put(0xE4, "海岸炮等级"); LEGION_NAMES.put(0xE8, "火箭发射器等级");
        LEGION_NAMES.put(0xEC, "工事等级"); LEGION_NAMES.put(0xF0, "高射机枪等级");
        LEGION_NAMES.put(0xF4, "防空炮等级"); LEGION_NAMES.put(0xF8, "对空导弹等级");
        LEGION_NAMES.put(0xFC, "雷达等级"); LEGION_NAMES.put(0x100, "导弹弹头等级");
        LEGION_NAMES.put(0x104, "固体火箭发动机等级"); LEGION_NAMES.put(0x108, "核弹破防等级");
        LEGION_NAMES.put(0x10C, "核聚变等级");
        LEGION_NAMES.put(0x110, "未知1"); LEGION_NAMES.put(0x114, "未知2");
        LEGION_NAMES.put(0x118, "未知3");
        LEGION_NAMES.put(0x11C, "国家AI行为"); LEGION_NAMES.put(0x120, "初始科技等级");
        LEGION_NAMES.put(0x124, "离子炮"); LEGION_NAMES.put(0x128, "初始激光炮等级");
    }

    // ================= 国家配置表（对应熊编辑器「国家配置」栏） =================
    private int[][] nationValues;
    private int nationSelRow = 0;
    private String nationClipboard;
    private LinearLayout nationHeaderBox, nationRowsBox;

    private static int readLegionInt(byte[] raw, int off) {
        if (raw == null || off < 0 || off + 4 > raw.length) return 0;
        return (raw[off] & 0xFF) | ((raw[off + 1] & 0xFF) << 8)
                | ((raw[off + 2] & 0xFF) << 16) | ((raw[off + 3] & 0xFF) << 24);
    }

    private static void writeLegionInt(byte[] raw, int off, int v) {
        if (raw == null || off < 0 || off + 4 > raw.length) return;
        raw[off] = (byte) (v & 0xFF);
        raw[off + 1] = (byte) ((v >> 8) & 0xFF);
        raw[off + 2] = (byte) ((v >> 16) & 0xFF);
        raw[off + 3] = (byte) ((v >> 24) & 0xFF);
    }

    private static int legionColorOf(byte[] raw) {
        if (raw == null || raw.length < 0x2C) return 0xFFFF00FF;
        return 0xFF000000 | ((raw[0x28] & 0xFF) << 16) | ((raw[0x29] & 0xFF) << 8) | (raw[0x2A] & 0xFF);
    }

    private static void setLegionColor(byte[] raw, int color) {
        if (raw == null || raw.length < 0x2C) return;
        raw[0x28] = (byte) ((color >> 16) & 0xFF);
        raw[0x29] = (byte) ((color >> 8) & 0xFF);
        raw[0x2A] = (byte) (color & 0xFF);
        raw[0x2B] = (byte) 0xFF;
    }

    private void showNationConfigDialog() {
        if (mapData == null || mapData.legions == null || mapData.legions.isEmpty()) {
            Toast.makeText(this, "请先打开带军团的 BTL", Toast.LENGTH_SHORT).show();
            return;
        }
        final int n = mapData.legions.size();
        final int cols = LEGION_FIELDS.length;
        nationValues = new int[n][cols];
        for (int i = 0; i < n; i++) {
            byte[] raw = mapData.legions.get(i).raw;
            for (int c = 0; c < cols; c++) {
                int off = Integer.decode(LEGION_FIELDS[c][2]);
                nationValues[i][c] = (off == 0x28) ? legionColorOf(raw) : readLegionInt(raw, off);
            }
        }
        if (nationSelRow >= n) nationSelRow = 0;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(8), dp(8), dp(8), dp(8));
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        String[] labels = {"复制配置", "粘贴配置", "应用到所有", "批量编辑", "保存"};
        Runnable[] acts = {
                () -> {
                    nationClipboard = nationRowToString(nationSelRow);
                    Toast.makeText(this, "已复制军团" + (nationSelRow + 1) + " 配置", Toast.LENGTH_SHORT).show();
                },
                () -> {
                    if (nationClipboard == null) { Toast.makeText(this, "请先复制配置", Toast.LENGTH_SHORT).show(); return; }
                    nationStringToRow(nationClipboard, nationSelRow);
                    renderNationTable();
                },
                () -> {
                    if (nationClipboard == null) { Toast.makeText(this, "请先复制配置", Toast.LENGTH_SHORT).show(); return; }
                    for (int i = 0; i < nationValues.length; i++) nationStringToRow(nationClipboard, i);
                    renderNationTable();
                    Toast.makeText(this, "已应用到全部军团", Toast.LENGTH_SHORT).show();
                },
                this::batchEditNationField,
                this::saveNationConfig,
        };
        for (int i = 0; i < labels.length; i++) {
            final Runnable a = acts[i];
            Button b = new Button(this);
            b.setText(labels[i]);
            b.setTextSize(11);
            b.setTextColor(Color.WHITE);
            b.setAllCaps(false);
            b.setBackgroundColor(i == 4 ? 0xFF16a34a : 0xFF1e5fa8);
            b.setPadding(dp(4), 0, dp(4), 0);
            b.setOnClickListener(v -> a.run());
            bar.addView(b, new LinearLayout.LayoutParams(0, dp(38), 1f));
        }
        root.addView(bar);
        TextView hint = new TextView(this);
        hint.setText("点最左列选中行（整行变蓝）· 点单元格改值 · 国家ID/国家颜色 有专门选择器 · 列与熊编辑器一致");
        hint.setTextSize(10);
        hint.setTextColor(0xFF9ca3af);
        hint.setPadding(dp(2), dp(6), dp(2), dp(6));
        root.addView(hint);

        LinearLayout table = new LinearLayout(this);
        table.setOrientation(LinearLayout.VERTICAL);
        nationHeaderBox = new LinearLayout(this);
        nationHeaderBox.setOrientation(LinearLayout.HORIZONTAL);
        nationRowsBox = new LinearLayout(this);
        nationRowsBox.setOrientation(LinearLayout.VERTICAL);
        table.addView(nationHeaderBox);
        ScrollView vs = new ScrollView(this);
        vs.addView(nationRowsBox);
        table.addView(vs, new LinearLayout.LayoutParams(-2, 0, 1f));
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.addView(table, new HorizontalScrollView.LayoutParams(-2, -1));
        root.addView(hs, new LinearLayout.LayoutParams(-1, 0, 1f));

        new android.app.AlertDialog.Builder(this)
                .setTitle("国家配置（" + n + " 个军团）")
                .setView(root)
                .setNegativeButton("关闭", null)
                .show();
        renderNationTable();
    }

    private String nationRowToString(int row) {
        if (row < 0 || row >= nationValues.length) return null;
        StringBuilder sb = new StringBuilder();
        for (int c = 0; c < nationValues[row].length; c++) {
            if (c > 0) sb.append(',');
            sb.append(nationValues[row][c]);
        }
        return sb.toString();
    }

    private void nationStringToRow(String s, int row) {
        if (s == null || row < 0 || row >= nationValues.length) return;
        String[] parts = s.split(",");
        for (int c = 0; c < nationValues[row].length && c < parts.length; c++) {
            try {
                nationValues[row][c] = Integer.parseInt(parts[c]);
            } catch (Exception ignored) {
            }
        }
    }

    private void renderNationTable() {
        if (nationHeaderBox == null || nationRowsBox == null) return;
        nationHeaderBox.removeAllViews();
        nationRowsBox.removeAllViews();
        nationHeaderBox.addView(nationCell("军团", dp(60), 0xFF1f2937, null, -1));
        for (int c = 0; c < LEGION_FIELDS.length; c++) {
            int off = Integer.decode(LEGION_FIELDS[c][2]);
            String name = LEGION_NAMES.containsKey(off) ? LEGION_NAMES.get(off) : LEGION_FIELDS[c][0];
            nationHeaderBox.addView(nationCell(name, dp(92), 0xFF1f2937, null, -1));
        }
        for (int i = 0; i < nationValues.length; i++) {
            final int row = i;
            boolean sel = (i == nationSelRow);
            LinearLayout rowBox = new LinearLayout(this);
            rowBox.setOrientation(LinearLayout.HORIZONTAL);
            TextView idx = nationCell("军团" + (i + 1), dp(60), sel ? 0xFF1d4ed8 : 0xFF26262c, null, -1);
            idx.setOnClickListener(v -> {
                nationSelRow = row;
                renderNationTable();
            });
            rowBox.addView(idx);
            for (int c = 0; c < LEGION_FIELDS.length; c++) {
                final int col = c;
                int off = Integer.decode(LEGION_FIELDS[c][2]);
                int val = nationValues[i][c];
                String text;
                int bg = sel ? 0xFF1e3a8a : 0xFF26262c;
                if (off == 0x28) {
                    text = String.format("#%06X", val & 0xFFFFFF);
                    bg = val;
                } else if (off == 0x4) {
                    text = val + " - " + CountryData.name(val);
                } else {
                    text = String.valueOf(val);
                }
                TextView cell = nationCell(text, dp(92), bg, LEGION_FIELDS[c][1], off);
                cell.setOnClickListener(v -> editNationCell(row, col));
                rowBox.addView(cell);
            }
            nationRowsBox.addView(rowBox);
        }
    }

    private TextView nationCell(String text, int width, int bg, String type, int off) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(11);
        tv.setSingleLine(true);
        tv.setGravity(Gravity.CENTER_VERTICAL);
        tv.setPadding(dp(5), dp(7), dp(5), dp(7));
        tv.setLayoutParams(new LinearLayout.LayoutParams(width, -2));
        tv.setBackgroundColor(bg);
        tv.setTextColor(off == 0x28 ? 0xFF111111 : 0xFFe5e7eb);
        return tv;
    }

    private void editNationCell(final int row, final int col) {
        final int off = Integer.decode(LEGION_FIELDS[col][2]);
        String name = LEGION_NAMES.containsKey(off) ? LEGION_NAMES.get(off) : LEGION_FIELDS[col][0];
        if (off == 0x4) {
            String[] items = new String[48];
            for (int i = 0; i < 48; i++) items[i] = (i + 1) + " - " + CountryData.name(i + 1);
            new android.app.AlertDialog.Builder(this)
                    .setTitle("选择国家（军团" + (row + 1) + "）")
                    .setItems(items, (d, w) -> {
                        nationValues[row][col] = w + 1;
                        renderNationTable();
                    })
                    .setNeutralButton("手动输入ID", (d, w) -> inputNationNumber(row, col, name))
                    .show();
            return;
        }
        if (off == 0x28) {
            showColorPicker(nationValues[row][col], c -> {
                nationValues[row][col] = c;
                renderNationTable();
            });
            return;
        }
        inputNationNumber(row, col, name);
    }

    private void inputNationNumber(final int row, final int col, String name) {
        final EditText et = new EditText(this);
        et.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);
        et.setText(String.valueOf(nationValues[row][col]));
        et.setTextColor(0xFFe5e7eb);
        new android.app.AlertDialog.Builder(this)
                .setTitle(name + "（军团" + (row + 1) + "）")
                .setView(et)
                .setNegativeButton("取消", null)
                .setPositiveButton("确定", (d, w) -> {
                    try {
                        nationValues[row][col] = Integer.parseInt(et.getText().toString().trim());
                        renderNationTable();
                    } catch (Exception e) {
                        Toast.makeText(this, "输入无效", Toast.LENGTH_SHORT).show();
                    }
                }).show();
    }

    private void showColorPicker(int current, final java.util.function.IntConsumer onPick) {
        final int[] palette = {0xFFe53935, 0xFFd81b60, 0xFF8e24aa, 0xFF5e35b1, 0xFF3949ab, 0xFF1e88e5,
                0xFF039be5, 0xFF00acc1, 0xFF00897b, 0xFF43a047, 0xFF7cb342, 0xFFc0ca33,
                0xFFfdd835, 0xFFFFb300, 0xFFfb8c00, 0xFFf4511e, 0xFF6d4c41, 0xFF757575,
                0xFF546e7a, 0xFFe0e0e0, 0xFFfafa96, 0xFF96aaaa, 0xFF4e342e, 0xFFFFFFFF};
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setPadding(dp(10), dp(10), dp(10), dp(10));
        for (int i = 0; i < palette.length; i += 6) {
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            for (int k = i; k < i + 6 && k < palette.length; k++) {
                final int color = palette[k];
                View sw = new View(this);
                sw.setBackgroundColor(color);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(44), dp(36));
                lp.setMargins(dp(3), dp(3), dp(3), dp(3));
                sw.setLayoutParams(lp);
                sw.setClickable(true);
                line.addView(sw);
            }
            grid.addView(line);
        }
        final EditText et = new EditText(this);
        et.setHint("或输入 HEX，如 fafa96");
        et.setText(String.format("%06X", current & 0xFFFFFF));
        et.setTextColor(0xFFe5e7eb);
        grid.addView(et);
        final android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(this)
                .setTitle("选择国家颜色（当前 #" + String.format("%06X", current & 0xFFFFFF) + "）")
                .setView(grid)
                .setNegativeButton("取消", null)
                .setPositiveButton("确定", (d, w) -> {
                    try {
                        int rgb = (int) Long.parseLong(et.getText().toString().trim().replace("#", ""), 16);
                        onPick.accept(0xFF000000 | (rgb & 0xFFFFFF));
                    } catch (Exception e) {
                        Toast.makeText(this, "HEX 无效", Toast.LENGTH_SHORT).show();
                    }
                }).create();
        dlg.show();
        for (int i = 0; i < palette.length; i++) {
            final int color = palette[i];
            View sw = ((LinearLayout) grid.getChildAt(i / 6)).getChildAt(i % 6);
            sw.setOnClickListener(v -> {
                onPick.accept(color);
                dlg.dismiss();
            });
        }
    }

    private void batchEditNationField() {
        String[] items = new String[LEGION_FIELDS.length];
        for (int c = 0; c < LEGION_FIELDS.length; c++) {
            int off = Integer.decode(LEGION_FIELDS[c][2]);
            items[c] = LEGION_NAMES.containsKey(off) ? LEGION_NAMES.get(off) : LEGION_FIELDS[c][0];
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle("批量编辑配置：选择字段")
                .setItems(items, (d, which) -> {
                    final int col = which;
                    final EditText et = new EditText(this);
                    et.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                            | android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);
                    et.setText(String.valueOf(nationValues[nationSelRow][col]));
                    et.setTextColor(0xFFe5e7eb);
                    new android.app.AlertDialog.Builder(this)
                            .setTitle("「" + items[col] + "」设为（应用到所有军团）")
                            .setView(et)
                            .setNegativeButton("取消", null)
                            .setPositiveButton("应用", (dd, ww) -> {
                                try {
                                    int v = Integer.parseInt(et.getText().toString().trim());
                                    for (int i = 0; i < nationValues.length; i++) nationValues[i][col] = v;
                                    renderNationTable();
                                    Toast.makeText(this, "已应用到全部军团", Toast.LENGTH_SHORT).show();
                                } catch (Exception e) {
                                    Toast.makeText(this, "输入无效", Toast.LENGTH_SHORT).show();
                                }
                            }).show();
                }).show();
    }

    private void saveNationConfig() {
        if (mapData == null || mapData.legions == null) return;
        try {
            history.save(mapData, "国家配置");
            for (int i = 0; i < nationValues.length && i < mapData.legions.size(); i++) {
                MapData.Legion lg = mapData.legions.get(i);
                byte[] raw = lg.raw.clone();
                for (int c = 0; c < LEGION_FIELDS.length; c++) {
                    int off = Integer.decode(LEGION_FIELDS[c][2]);
                    if (off == 0x28) setLegionColor(raw, nationValues[i][c]);
                    else writeLegionInt(raw, off, nationValues[i][c]);
                }
                FileParser.patchLegion(mapData, lg, raw);
                lg.raw = raw.clone();
                lg.color = legionColorOf(raw);
                lg.country = readLegionInt(raw, 0x4);
                if (mapData.legionColors != null && i < mapData.legionColors.length) {
                    mapData.legionColors[i] = lg.color;
                }
                if (mapData.legionCountries != null && i < mapData.legionCountries.length) {
                    mapData.legionCountries[i] = lg.country;
                }
            }
            hexMapView.refresh();
            updateStatus();
            Toast.makeText(this, "国家配置已写入地图（记得点保存写文件）", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "保存失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

}
