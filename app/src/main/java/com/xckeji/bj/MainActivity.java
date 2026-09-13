package com.xckeji.bj;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import java.lang.Thread.UncaughtExceptionHandler;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.SoundPool;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupWindow;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.xckeji.bj.file.FileParser;
import com.xckeji.bj.model.ArmyConfig;
import com.xckeji.bj.model.CountryData;
import com.xckeji.bj.model.GeneralData;
import com.xckeji.bj.model.MapData;
import com.xckeji.bj.model.OperationHistory;
import com.xckeji.bj.model.RandomMapGenerator;
import com.xckeji.bj.model.TerrainTile;
import com.xckeji.bj.render.HexMapView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class MainActivity extends Activity implements HexMapView.OnTileSelectListener {
    private static final int REQUEST_OPEN = 200;
    private static final int REQUEST_SAVE = 201;

    /**
     * 本地固定黑名单（保留空数组即可）。
     * 注意：后台“远程拦截”是唯一管理入口，写死在这里的机型后台移除不掉，
     * 所以不再放具体机型，统一由服务器 blocklist.txt 管理。
     */
    private static final String[] BLOCKED_MODELS = {};
    /** 远程拦截名单地址：服务器 telemetry/blocklist.php 返回 {"models":[...]}。 */
    private static final String BLOCKLIST_URL =
            "https://dx.xckeji.xyz/telemetry/blocklist.php";
    private java.util.List<String> remoteBlockedModels = new java.util.ArrayList<>();
    /** 远程停用版本名单（命中则禁止进入并引导更新）。 */
    private java.util.List<String> remoteBlockedVersions = new java.util.ArrayList<>();
    private String cachedAnnounceTitle = "";
    private String cachedAnnounceContent = "";
    private boolean versionGateDialogShowing = false;

    // ===== 设备使用统计上报（宝塔后台查看）=====
    // 改成你自己的地址，例如 https://你的域名/telemetry/collect.php
    private static final String TELEMETRY_URL = "https://dx.xckeji.xyz/telemetry/collect.php";
    // 与 server/config.php 里的 TELEMETRY_SECRET 保持一致
    private static final String TELEMETRY_SECRET = "wc4_editor_2026";

    // 远程公告地址（同域名）：公告内容在服务器 telemetry/announcement.txt 里改
    private static final String ANNOUNCEMENT_URL =
            "https://dx.xckeji.xyz/telemetry/announcement.php";
    /** 应用内更新检查（服务器 update.php 读取 apk/latest.json） */
    private static final String UPDATE_URL = "https://dx.xckeji.xyz/update.php";
    /** 当前版本标识（用于远程版本停用判断）。 */
    private static final String APP_LABEL = "Terrain Editor正式版";
    private HexMapView hexMapView;
    private LinearLayout rightPanel;
    private TextView selectedInfo, mapInfo, titleText, blockIdText;
    private Button undoBtn, redoBtn;
    private LinearLayout contentArea;
    private LinearLayout terrainScroll, buildingScroll;
    private LinearLayout armyScroll;
    private LinearLayout cityScroll;
    private LinearLayout provinceScroll;
    private LinearLayout dataPanelBox;
    private LinearLayout dataCityContent;
    private ScrollView dataArmySv, dataCitySv, dataProvinceSv, dataMineSv;
    private TextView dataArmyTabTv, dataCityTabTv, dataProvinceTabTv, dataMineTabTv;
    private View dataArmyTabLine, dataCityTabLine, dataProvinceTabLine, dataMineTabLine;
    private LinearLayout dataMineContent;
    private EditText[] dataCityEds;
    private android.widget.Spinner[] dataCitySpinners;
    private android.widget.Spinner armyFormationSp;
    private android.widget.Spinner armyAiSp, armyMoraleSp, armyBadgeSp, armyTransportSp;
    private boolean dataPanelOpen = false;
    private String dataTab = "army";
    private LinearLayout provinceListBox;
    private Button provinceShowBtn;
    private TextView provinceEditBtn;
    private TextView provinceHint;
    private TextView fileInfoView;
    private boolean provinceTakeMode = false; // 取省模式：下一次点格子只读取其省区，不绘制
    private LinearLayout armyEditorArea;
    private java.util.List<LinearLayout> armyAddRows;
    private java.util.List<ImageView> armyIconViews;
    private MapData.Building selectedBuilding;
    private MapData.Building lastEditedBuilding;
    private MapData.Building draftBuilding; // 新放置建筑（无原文件记录）的编辑草稿
    private MapData.Trap selectedTrap;
    private MapData.Trap lastEditedTrap;
    private boolean addingMine = false;
    private android.widget.Spinner mineLegionSp, mineLevelSp;
    private EditText mineHpEt;
    // 扩展前待选 BTL 的参数（选完 BTL 后自动继续扩展）
    private MapData mapData;
    /** 最近打开过的世界地形 BIN：先开 BIN 再开征服 BTL 时自动匹配，无需重复选择。 */
    private byte[] pendingWorldBin;
    private String pendingWorldBinName;
    /** 最近打开过的世界地形 BIN（名称→数据，最多保留 4 个），按 HTML 版转换器的匹配规则自动关联征服底图。 */
    private final java.util.LinkedHashMap<String, byte[]> recentWorldBins = new java.util.LinkedHashMap<>();
    private static final int MAX_RECENT_WORLD_BINS = 4;
    private OperationHistory history = new OperationHistory();
    private String currentFileName = "未命名地图";
    // 截取模式：起点 -> 终点 -> 已框选（确认保存 / 取消）
    private static final int CROP_NONE = 0, CROP_START = 1, CROP_END = 2, CROP_FRAMED = 3;
    private int cropState = CROP_NONE;
    private int cropX1 = -1, cropY1 = -1, cropX2 = -1, cropY2 = -1;
    private LinearLayout cropOverlay;
    private TextView cropStatus;
    private LinearLayout cropBtnsRow;
    private Button cropConfirmBtn, cropCancelBtn;
    private FrameLayout rootFrame;
    /** 首页模式：0=打开战役stage 1=打开征服 2=打开地图(仅右侧面板) 3=新建战役 */
    private int editorMode = 0;
    private View topBarView;
    private View leftPanelView;
    private Button floatOpenBtn, floatSaveBtn, floatMusicBtn, floatSfxBtn;
    private View fileLabelView, soundLabelView, leftDividerView;
    private Button topNewBtlBtn;
    private View panelFrameView;
    private LinearLayout infoPanelView;
    private View groupTextView;
    private FrameLayout homeOverlay;
    private FrameLayout legionOverlay;
    private LinearLayout legionPanel;
    private FrameLayout legionDetailOverlay;
    private LinearLayout legionDetailPanel;
    private EditText[] legionEds;
    private FrameLayout buildingOverlay;
    private LinearLayout buildingPanel;
    private FrameLayout buildingDetailOverlay;
    private LinearLayout buildingDetailPanel;
    private EditText[] buildingEds;
    private FrameLayout btlOverlay;
    private LinearLayout btlPanel;
    private EditText[] btlEds;
    private android.widget.Spinner btlVictorySp, btlEraSp;
    private FrameLayout btlEventListOverlay;
    private LinearLayout btlEventListPanel;
    private FrameLayout btlEventDetailOverlay;
    private LinearLayout btlEventDetailPanel;
    private EditText[] btlEventEds;
    private android.widget.Spinner btlEventCondSp, btlEventTypeSp;
    private int btlEventEditIndex = -1;
    private boolean viewOnlyMode = false;
    private java.util.Map<Integer, Bitmap> flagIcons;
    private MapData.Army selectedArmy;
    private MapData.Army lastEditedArmy;
    private Button armySaveBtn;
    private EditText[] armyEds;
    private int armyTypePickerValue = -1;
    private boolean addingArmy = false;
    private ArmyConfig pendingArmyType;
    private int pendingArmyLegion = -1;
    private boolean provinceViewOn = false; // 省规划视图需手动开启；默认只在原地形上叠半透明国家色
    private long lastBrushHistorySave = 0;  // 笔刷连续涂抹时按时间节流快照

    private static final int[] LAND_TYPES = {1,2,3,4,5,6,7,8,9,10,11,12,13,14};
    private static final int[] NAVAL_TYPES = {15,16,17,18,19};

    /** 兵种48 记录字段：名称 / 类型 / 偏移（与 BTL 兵种段一致）。 */
    private static final String[][] ARMY_FIELDS = {
        {"坐标", "u16", "0x0"}, {"兵种", "u8", "0x2"}, {"等级", "u8", "0x3"},
        {"编制", "u8", "0x4"}, {"方向", "u8", "0x5"}, {"移动力", "u8", "0x6"},
        {"建造回合", "u8", "0x7"}, {"兵种经验", "u16", "0x8"}, {"血量加成", "u16", "0xA"},
        {"当前血量", "u16", "0xC"}, {"血量上限", "u16", "0xE"}, {"将领", "u16", "0x10"},
        {"军衔", "u8", "0x12"}, {"爵位", "u8", "0x13"}, {"胸章一", "u8", "0x14"},
        {"胸章二", "u8", "0x15"}, {"胸章三", "u8", "0x16"}, {"技能等级1", "u8", "0x17"},
        {"技能等级2", "u8", "0x18"}, {"技能等级3", "u8", "0x19"}, {"技能等级4", "u8", "0x1A"},
        {"技能等级5", "u8", "0x1B"}, {"关键据点", "u8", "0x1C"}, {"AI行动模式", "u8", "0x1D"},
        {"运输船", "u8", "0x1E"}, {"仇恨值", "u16", "0x20"}, {"移动目标", "u16", "0x22"},
        {"行为方案", "u16", "0x24"}, {"改变回合", "u16", "0x26"}, {"士气", "u8", "0x28"},
        {"士气持续回合", "u8", "0x29"}, {"关联事件", "u8", "0x2A"}, {"等级标志显示", "u8", "0x2B"},
        {"固守距离", "i32", "0x2C"}, {"勋章一", "u8", "0x30"}, {"勋章二", "u8", "0x31"},
        {"勋章三", "u8", "0x32"}, {"勋带一", "u8", "0x33"}, {"勋带二", "u8", "0x34"},
        {"勋带三", "u8", "0x35"}
    };

    /** 城市/建筑 32 字节记录字段：名称 / 类型 / 偏移（0x4=类型，对应 building_N.png）。 */
    private static final String[][] BUILDING_FIELDS = {
        {"坐标", "u16", "0x0"},
        {"名称", "u16", "0x2"},
        {"类型", "u8", "0x4"},
        {"外观", "u8", "0x5"},
        {"地标", "u8", "0x6"},
        {"城心奇观", "u8", "0x7"},
        {"奖励类型", "u8", "0x8"},
        {"奖励数量", "u8", "0x9"},
        {"仇恨值(有符号)", "s8", "0xC"},
        {"据点(0无1红2绿)", "u8", "0xD"},
        {"触发事件", "u8", "0xE"},
        {"火焰类型", "u8", "0x14"},
        {"持续回合", "u8", "0x15"},
        {"防空武器", "u8", "0x16"},
        {"防空雷达", "u8", "0x17"},
        {"工厂", "u8", "0x18"},
        {"科研所", "u8", "0x19"},
        {"补给站", "u8", "0x1A"},
        {"机场", "u8", "0x1B"},
        {"导弹基地", "u8", "0x1C"},
        {"核工厂", "u8", "0x1D"}
    };

    /** 军团 300 记录字段：名称 / 类型 / 偏移。 */
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
        {"破防等级", "i32", "0x108"}, {"核聚变等级", "i32", "0x10C"}, {"科技等级", "i32", "0x11C"}
    };
    private String customSavePath = "";
    private Map<Integer, Bitmap> terrainThumbs = new HashMap<>();
    private Map<Integer, Bitmap> buildingThumbs = new HashMap<>();
    private java.util.List<LinearLayout> terrainRows = new java.util.ArrayList<>();

    // 音频
    private MediaPlayer bgMusicPlayer;
    private SoundPool soundPool;
    private int sfxSelectId;
    private boolean musicEnabled = true;
    private boolean sfxEnabled = true;


    // ===== 魔棒工具：从HTML移植的多选功能 =====
    private void magicWandSelect(int startX, int startY, boolean exactMatch) {
        if (mapData == null) return;
        int w = mapData.width, h = mapData.height;
        TerrainTile startTile = mapData.getTile(startX, startY);
        if (startTile == null) return;

        mapData.multiSelectMode = true;
        mapData.selectedBlocks.clear();
        closeProvinceEdit();

        int startG = startTile.bmTerrain1Group;
        int startId = startTile.bmTerrain1Id;
        boolean[] visited = new boolean[w * h];
        java.util.Queue<Integer> queue = new java.util.LinkedList<>();
        int startIdx = startY * w + startX;
        queue.add(startIdx);
        visited[startIdx] = true;

        while (!queue.isEmpty()) {
            int idx = queue.poll();
            int cx = idx % w, cy = idx / w;
            mapData.selectedBlocks.add(idx);

            // 六边形邻居偏移（偶数列和奇数列不同）
            int[][] evenNeighbors = {{-1,0},{-1,-1},{0,-1},{1,-1},{1,0},{0,1}};
            int[][] oddNeighbors = {{-1,0},{0,-1},{1,-1},{1,0},{1,1},{0,1}};
            int[][] neighbors = (cx % 2 == 0) ? evenNeighbors : oddNeighbors;

            for (int[] nb : neighbors) {
                int nx = cx + nb[0], ny = cy + nb[1];
                if (nx < 0 || nx >= w || ny < 0 || ny >= h) continue;
                int nIdx = ny * w + nx;
                if (visited[nIdx]) continue;
                visited[nIdx] = true;

                TerrainTile nt = mapData.getTile(nx, ny);
                if (nt == null) continue;
                boolean match;
                if (exactMatch) {
                    match = (nt.bmTerrain1Group == startG && nt.bmTerrain1Id == startId);
                } else {
                    match = (nt.bmTerrain1Group == startG);
                }
                if (match) queue.add(nIdx);
            }
        }
        hexMapView.refresh();
        updateInfo();
        Toast.makeText(this, "已选中 " + mapData.selectedBlocks.size() + " 个格子", Toast.LENGTH_SHORT).show();
    }

    private void clearMultiSelection() {
        if (mapData == null) return;
        mapData.multiSelectMode = false;
        mapData.selectedBlocks.clear();
        hexMapView.refresh();
        updateInfo();
        Toast.makeText(this, "已清除多选", Toast.LENGTH_SHORT).show();
    }

    // ===== 校验并修复（规则与枭雄一致） =====
    private void validateAndFixDialog() {
        if (mapData == null || mapData.btlOriginalData == null) {
            Toast.makeText(this, "请先加载地图", Toast.LENGTH_SHORT).show();
            return;
        }
        java.util.List<String> issues = FileParser.validateAndFix(mapData);
        AlertDialog.Builder b = new AlertDialog.Builder(this, R.style.DarkDialog);
        b.setTitle("校验结果（自动修复）");
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        TextView tv = new TextView(this);
        tv.setTextSize(12);
        tv.setTextColor(0xFFd1d5db);
        tv.setPadding(24, 12, 24, 12);
        if (issues.isEmpty()) {
            tv.setText("没有发现问题，数据正常。");
        } else {
            tv.setText("发现 " + issues.size() + " 项问题，已全部自动修复：\n\n"
                    + String.join("\n", issues));
        }
        sv.addView(tv);
        b.setView(sv);
        b.setPositiveButton("确定", (d, w) -> {
            hexMapView.refresh();
            updateInfo();
        });
        b.show();
    }

    // ================= BTL 地图库（首页左侧） =================
    // ===== 将领选择器（带搜索，仿枭雄） =====
    private final java.util.Map<Integer, Bitmap> generalThumbCache = new java.util.HashMap<>();

    /** 将领信息文本：名字 + 技能 + 将领勋章 + 单位已装备勋章/勋带。 */
    private String generalInfoText(int generalId, byte[] raw) {
        if (generalId <= 0) return "名字：未选择将领（点下面按钮可搜索选择）";
        StringBuilder sb = new StringBuilder("名字：" + GeneralData.name(generalId));
        GeneralData gd = GeneralData.BY_ID.get(generalId);
        if (gd != null) {
            if (gd.skills.length > 0) {
                java.util.List<String> sn = new java.util.ArrayList<>();
                for (int sk : gd.skills) {
                    String n = GeneralData.SKILL_NAMES.get(sk);
                    sn.add(n != null ? n : ("技能" + sk));
                }
                sb.append("\n技能：").append(String.join("、", sn));
            }
            if (gd.medals != null) {
                StringBuilder ms = new StringBuilder("\n将领勋章：");
                boolean any = false;
                String[] tags = {"胸章一", "胸章二", "胸章三", "勋带一", "勋带二", "勋带三"};
                for (int m = 0; m < gd.medals.length && m < 6; m++) {
                    if (gd.medals[m] > 0) {
                        ms.append(tags[m]).append("=").append(gd.medals[m]).append(" ");
                        any = true;
                    }
                }
                if (any) sb.append(ms);
            }
        }
        if (raw != null && raw.length > 0x35) {
            StringBuilder ms = new StringBuilder("\n单位勋章/勋带：");
            boolean any = false;
            String[] tags = {"勋章一", "勋章二", "勋章三", "勋带一", "勋带二", "勋带三"};
            for (int m = 0; m < 6; m++) {
                int v = raw[0x30 + m] & 0xFF;
                if (v > 0) {
                    ms.append(tags[m]).append("=").append(v).append(" ");
                    any = true;
                }
            }
            if (any) sb.append(ms);
        }
        return sb.toString();
    }

    private Bitmap generalThumb(int id) {
        Bitmap b = generalThumbCache.get(id);
        if (b == null) {
            GeneralData g = GeneralData.BY_ID.get(id);
            if (g != null && g.photo > 0) b = loadBmp("general/" + g.photo + ".webp");
            if (b != null) generalThumbCache.put(id, b);
        }
        return b;
    }

    private class GeneralAdapter extends android.widget.BaseAdapter {
        private final java.util.List<GeneralData> items;

        GeneralAdapter(java.util.List<GeneralData> items) { this.items = items; }

        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int i) { return items.get(i); }
        @Override public long getItemId(int i) { return items.get(i).id; }

        @Override public View getView(int pos, View convert, android.view.ViewGroup parent) {
            final int density = (int) getResources().getDisplayMetrics().density;
            LinearLayout row = new LinearLayout(MainActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(6, 6, 6, 6);
            GeneralData g = items.get(pos);
            ImageView iv = new ImageView(MainActivity.this);
            Bitmap bm = generalThumb(g.id);
            if (bm != null) iv.setImageBitmap(bm);
            iv.setLayoutParams(new LinearLayout.LayoutParams(
                    46 * density, 46 * density));
            row.addView(iv);
            LinearLayout info = new LinearLayout(MainActivity.this);
            info.setOrientation(LinearLayout.VERTICAL);
            info.setPadding(10, 0, 0, 0);
            TextView nm = new TextView(MainActivity.this);
            nm.setText(g.name.isEmpty() ? ("将领" + g.id) : g.name);
            nm.setTextSize(14);
            nm.setTextColor(0xFFe5e7eb);
            info.addView(nm);
            TextView sub = new TextView(MainActivity.this);
            sub.setText("ID " + g.id + (g.ename.isEmpty() ? "" : " · " + g.ename));
            sub.setTextSize(11);
            sub.setTextColor(0xFF94a3b8);
            info.addView(sub);
            row.addView(info);
            return row;
        }
    }

    /** 将领选择悬浮窗：搜索 + 列表点选（0 = 无将领）。 */
    private void showGeneralPicker(final java.util.function.IntConsumer onPick) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(16, 10, 16, 10);
        final EditText search = new EditText(this);
        search.setHint("搜索将领名 / 英文名 / ID");
        styleDialogEdit(search);
        final android.widget.ListView lv = new android.widget.ListView(this);
        final java.util.List<GeneralData> shown = new java.util.ArrayList<>(GeneralData.ALL);
        final GeneralAdapter adapter = new GeneralAdapter(shown);
        lv.setAdapter(adapter);
        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(android.text.Editable e) {
                String q = e.toString().trim().toLowerCase();
                shown.clear();
                for (GeneralData g : GeneralData.ALL) {
                    if (q.isEmpty() || g.name.toLowerCase().contains(q)
                            || g.ename.toLowerCase().contains(q)
                            || String.valueOf(g.id).contains(q)) {
                        shown.add(g);
                    }
                }
                adapter.notifyDataSetChanged();
            }
        });
        root.addView(search);
        root.addView(lv, new LinearLayout.LayoutParams(-1, 0, 1f));
        AlertDialog.Builder b = new AlertDialog.Builder(this, R.style.DarkDialog);
        b.setTitle("选择将领（" + GeneralData.ALL.size() + " 位）");
        b.setView(root);
        b.setNeutralButton("清除（无将领）", (d, w) -> onPick.accept(0));
        b.setNegativeButton("取消", null);
        final AlertDialog dlg = b.create();
        dlg.show();
        lv.setOnItemClickListener((p, v, pos, id) -> {
            onPick.accept(shown.get(pos).id);
            dlg.dismiss();
        });
    }

    // ===== 兵种选择窗（带搜索，仿枭雄） =====
    private void showArmyPicker(final java.util.function.IntConsumer onPick) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(16, 10, 16, 10);
        final EditText search = new EditText(this);
        search.setHint("搜索兵种名 / 代码");
        styleDialogEdit(search);
        final android.widget.ListView lv = new android.widget.ListView(this);
        final java.util.List<ArmyConfig> all = new java.util.ArrayList<>();
        java.util.LinkedHashMap<Integer, ArmyConfig> uniq = new java.util.LinkedHashMap<>();
        if (ArmyConfig.ALL != null) {
            for (ArmyConfig c : ArmyConfig.ALL) {
                if (c != null && c.army >= 1 && (c.army <= 40 || c.elite > 0)
                        && !uniq.containsKey(c.army)) uniq.put(c.army, c);
            }
        }
        all.addAll(uniq.values());
        final java.util.List<ArmyConfig> shown = new java.util.ArrayList<>(all);
        final android.widget.BaseAdapter adapter = new android.widget.BaseAdapter() {
            @Override public int getCount() { return shown.size(); }
            @Override public Object getItem(int i) { return shown.get(i); }
            @Override public long getItemId(int i) { return shown.get(i).army; }
            @Override public View getView(int pos, View convert, android.view.ViewGroup parent) {
                final int density = (int) getResources().getDisplayMetrics().density;
                LinearLayout row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(6, 6, 6, 6);
                ArmyConfig c = shown.get(pos);
                ImageView iv = new ImageView(MainActivity.this);
                Bitmap bm = loadArmyIcon(c.army);
                if (bm != null) iv.setImageBitmap(bm);
                iv.setLayoutParams(new LinearLayout.LayoutParams(46 * density, 46 * density));
                row.addView(iv);
                LinearLayout info = new LinearLayout(MainActivity.this);
                info.setOrientation(LinearLayout.VERTICAL);
                info.setPadding(10, 0, 0, 0);
                TextView nm = new TextView(MainActivity.this);
                nm.setText(c.name.isEmpty() ? ("兵种" + c.army) : c.name);
                nm.setTextSize(14);
                nm.setTextColor(0xFFe5e7eb);
                info.addView(nm);
                TextView sub = new TextView(MainActivity.this);
                sub.setText("代码 " + c.army + (c.elite > 0 ? " · 精英" : ""));
                sub.setTextSize(11);
                sub.setTextColor(0xFF94a3b8);
                info.addView(sub);
                row.addView(info);
                return row;
            }
        };
        lv.setAdapter(adapter);
        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(android.text.Editable e) {
                String q = e.toString().trim().toLowerCase();
                shown.clear();
                for (ArmyConfig c : all) {
                    if (q.isEmpty() || c.name.toLowerCase().contains(q)
                            || String.valueOf(c.army).contains(q)) {
                        shown.add(c);
                    }
                }
                adapter.notifyDataSetChanged();
            }
        });
        root.addView(search);
        root.addView(lv, new LinearLayout.LayoutParams(-1, 0, 1f));
        AlertDialog.Builder b = new AlertDialog.Builder(this, R.style.DarkDialog);
        b.setTitle("选择兵种（" + all.size() + " 个）");
        b.setView(root);
        b.setNegativeButton("取消", null);
        final AlertDialog dlg = b.create();
        dlg.show();
        lv.setOnItemClickListener((p, v, pos, id) -> {
            onPick.accept(shown.get(pos).army);
            dlg.dismiss();
        });
    }

    // ===== 建筑图标选择（类型/外观/首都，仿枭雄映射） =====
    private static final String[] BUILDING_ICON_NAMES = {
            "building_1", "building_2", "building_3", "building_11", "building_12",
            "building_13", "building_14", "building_15", "building_15b", "building_15f",
            "building_21", "building_22", "building_23",
            "building_31_1", "building_31_2", "building_31_3", "building_31_4",
            "capital_01", "capital_04", "capital_05", "capital_07", "capital_08",
            "capital_09", "capital_10", "capital_11", "capital_12", "capital_15",
            "capital_16", "capital_18", "capital_19", "capital_20", "capital_21",
            "capital_22", "capital_24", "capital_26", "capital_27", "capital_28",
            "capital_29", "tunnel_gate1", "tunnel_gate2",
    };

    private void showBuildingIconPicker(final MapData.Building b) {
        final int density = (int) getResources().getDisplayMetrics().density;
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setPadding(12, 10, 12, 10);
        LinearLayout row = null;
        int col = 0;
        for (final String name : BUILDING_ICON_NAMES) {
            if (col % 3 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                grid.addView(row);
            }
            col++;
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER);
            cell.setPadding(6, 6, 6, 6);
            ImageView iv = new ImageView(this);
            Bitmap bm = loadBmp("building/" + name + ".webp");
            if (bm != null) iv.setImageBitmap(bm);
            iv.setLayoutParams(new LinearLayout.LayoutParams(64 * density, 52 * density));
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            cell.addView(iv);
            TextView lb = new TextView(this);
            lb.setText(name.replace("building_", "建筑").replace("capital_", "首都")
                    .replace("tunnel_gate", "隧道"));
            lb.setTextSize(10);
            lb.setTextColor(0xFFcbd5e1);
            cell.addView(lb);
            cell.setClickable(true);
            cell.setOnClickListener(v -> applyBuildingIcon(b, name));
            if (row != null) row.addView(cell, new LinearLayout.LayoutParams(0, -2, 1f));
        }
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(grid);
        AlertDialog.Builder bd = new AlertDialog.Builder(this, R.style.DarkDialog);
        bd.setTitle("选择建筑图标（当前 类型" + (b.raw[4] & 0xFF) + " 外观" + (b.raw[5] & 0xFF) + "）");
        bd.setView(sv);
        bd.setNegativeButton("取消", null);
        bd.show();
    }

    /** 按选择的图标写回建筑类型/外观（>=100 视为首都，31-34 用外观选变体）。 */
    private void applyBuildingIcon(MapData.Building b, String name) {
        try {
            int type, appearance = 0;
            if (name.startsWith("capital_")) {
                type = 100 + Integer.parseInt(name.substring("capital_".length()));
            } else if (name.startsWith("building_31_")) {
                type = 31;
                appearance = Integer.parseInt(name.substring("building_31_".length()));
            } else if (name.startsWith("tunnel_gate")) {
                type = b.raw[4] & 0xFF; // 隧道口不改类型，仅提示
                Toast.makeText(this, "隧道口图标由游戏按隧道两端自动绘制", Toast.LENGTH_LONG).show();
                return;
            } else {
                String num = name.substring("building_".length());
                StringBuilder digits = new StringBuilder();
                for (char ch : num.toCharArray()) {
                    if (Character.isDigit(ch)) digits.append(ch);
                    else break;
                }
                type = Integer.parseInt(digits.toString());
            }
            byte[] raw = b.raw.clone();
            raw[4] = (byte) (type & 0xFF);
            raw[5] = (byte) (appearance & 0xFF);
            FileParser.BtlHeaderInfo h = FileParser.parseBTLHeader(mapData.btlOriginalData);
            FileParser.patchTailRecord(mapData, h.buildingStart, b.index, 32, raw);
            b.raw = raw;
            b.type = type;
            if (mapData.buildingIds != null) {
                mapData.setBuildingId(b.x, b.y, type);
            }
            hexMapView.refresh();
            rebuildCityEditor();
            updateInfo();
            Toast.makeText(this, "已设为 " + name, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "设置失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private FrameLayout mapLibOverlay;
    private LinearLayout onlineListBox, localListBox;
    private TextView mapLibStatus;

    private void showMapLibraryDialog() {
        if (mapLibOverlay == null) buildMapLibraryOverlay();
        mapLibOverlay.setVisibility(View.VISIBLE);
        mapLibStatus.setText("加载中…");
        refreshOnlineList();
        refreshLocalList();
    }

    private void buildMapLibraryOverlay() {
        final int density = (int) getResources().getDisplayMetrics().density;
        mapLibOverlay = new FrameLayout(this);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF0f172a, 0xFF1e293b});
        mapLibOverlay.setBackground(bg);
        rootFrame.addView(mapLibOverlay, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(16 * density, 12 * density, 16 * density, 12 * density);
        mapLibOverlay.addView(root, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("BTL 地图库");
        title.setTextSize(18);
        title.setTextColor(Color.WHITE);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        Button close = new Button(this);
        close.setText("✕ 关闭");
        close.setTextColor(Color.WHITE);
        close.setBackgroundColor(Color.parseColor("#475569"));
        close.setOnClickListener(v -> mapLibOverlay.setVisibility(View.GONE));
        head.addView(close);
        root.addView(head);

        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        final Button tabOnline = new Button(this);
        tabOnline.setText("在线地图库");
        tabOnline.setAllCaps(false);
        tabOnline.setTextColor(Color.WHITE);
        tabOnline.setBackgroundColor(Color.parseColor("#1e5fa8"));
        final Button tabLocal = new Button(this);
        tabLocal.setText("已下载");
        tabLocal.setAllCaps(false);
        tabLocal.setTextColor(Color.WHITE);
        tabLocal.setBackgroundColor(Color.parseColor("#3a3a40"));
        tabs.addView(tabOnline, new LinearLayout.LayoutParams(0, -2, 1));
        tabs.addView(tabLocal, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(tabs);

        final LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1f));

        // —— 在线页 ——
        LinearLayout online = new LinearLayout(this);
        online.setOrientation(LinearLayout.VERTICAL);
        LinearLayout ops = new LinearLayout(this);
        ops.setOrientation(LinearLayout.HORIZONTAL);
        Button refreshBtn = new Button(this);
        refreshBtn.setText("刷新列表");
        refreshBtn.setTextColor(Color.WHITE);
        refreshBtn.setBackgroundColor(Color.parseColor("#1e5fa8"));
        refreshBtn.setOnClickListener(v -> refreshOnlineList());
        Button uploadBtn = new Button(this);
        uploadBtn.setText("上传地图…");
        uploadBtn.setTextColor(Color.WHITE);
        uploadBtn.setBackgroundColor(Color.parseColor("#16a34a"));
        uploadBtn.setOnClickListener(v -> pickMapToUpload());
        ops.addView(refreshBtn, new LinearLayout.LayoutParams(0, -2, 1));
        ops.addView(uploadBtn, new LinearLayout.LayoutParams(0, -2, 1));
        online.addView(ops);
        onlineListBox = new LinearLayout(this);
        onlineListBox.setOrientation(LinearLayout.VERTICAL);
        android.widget.ScrollView osv = new android.widget.ScrollView(this);
        osv.addView(onlineListBox, new android.widget.ScrollView.LayoutParams(-1, -2));
        online.addView(osv, new LinearLayout.LayoutParams(-1, 0, 1f));

        // —— 已下载页 ——
        LinearLayout local = new LinearLayout(this);
        local.setOrientation(LinearLayout.VERTICAL);
        TextView localHint = new TextView(this);
        localHint.setText("已下载的地图存在应用私有目录，点「打开」进编辑器。");
        localHint.setTextSize(11);
        localHint.setTextColor(0xFF94a3b8);
        local.addView(localHint);
        localListBox = new LinearLayout(this);
        localListBox.setOrientation(LinearLayout.VERTICAL);
        android.widget.ScrollView lsv = new android.widget.ScrollView(this);
        lsv.addView(localListBox, new android.widget.ScrollView.LayoutParams(-1, -2));
        local.addView(lsv, new LinearLayout.LayoutParams(-1, 0, 1f));

        mapLibStatus = new TextView(this);
        mapLibStatus.setTextSize(12);
        mapLibStatus.setTextColor(0xFF94a3b8);
        mapLibStatus.setPadding(0, 8 * density, 0, 0);
        root.addView(mapLibStatus);

        final Runnable showOnline = () -> {
            content.removeAllViews();
            content.addView(online, new LinearLayout.LayoutParams(-1, -1));
            tabOnline.setBackgroundColor(Color.parseColor("#1e5fa8"));
            tabLocal.setBackgroundColor(Color.parseColor("#3a3a40"));
        };
        final Runnable showLocal = () -> {
            content.removeAllViews();
            content.addView(local, new LinearLayout.LayoutParams(-1, -1));
            tabOnline.setBackgroundColor(Color.parseColor("#3a3a40"));
            tabLocal.setBackgroundColor(Color.parseColor("#1e5fa8"));
            refreshLocalList();
        };
        tabOnline.setOnClickListener(v -> showOnline.run());
        tabLocal.setOnClickListener(v -> showLocal.run());
        showOnline.run();
    }

    private java.io.File mapLibDir() {
        java.io.File d = new java.io.File(getFilesDir(), "map_library");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private void refreshOnlineList() {
        onlineListBox.removeAllViews();
        mapLibStatus.setText("正在连接服务器…");
        new Thread(() -> {
            try {
                byte[] data = httpGet(MAPLIB_BASE + "/list.php");
                org.json.JSONObject o = new org.json.JSONObject(new String(data, "UTF-8"));
                final org.json.JSONArray maps = o.getJSONArray("maps");
                runOnUiThread(() -> {
                    onlineListBox.removeAllViews();
                    if (maps.length() == 0) {
                        mapLibStatus.setText("服务器上还没有地图，点「上传地图…」分享第一张");
                        return;
                    }
                    for (int i = 0; i < maps.length(); i++) {
                        try {
                            onlineListBox.addView(buildOnlineRow(maps.getJSONObject(i)));
                        } catch (Exception ignored) {
                        }
                    }
                    mapLibStatus.setText("共 " + maps.length() + " 张地图（点条目下载并打开）");
                });
            } catch (Exception e) {
                runOnUiThread(() -> mapLibStatus.setText("连接失败：" + e.getMessage()));
            }
        }).start();
    }

    private View buildOnlineRow(final org.json.JSONObject m) {
        final int density = (int) getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, 8 * density, 0, 8 * density);
        final ImageView thumb = new ImageView(this);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumb.setLayoutParams(new LinearLayout.LayoutParams(64 * density, 64 * density));
        thumb.setBackgroundColor(0xFF2a2a2f);
        row.addView(thumb);
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(10 * density, 0, 0, 0);
        TextView name = new TextView(this);
        name.setText(m.optString("name", m.optString("file", "")));
        name.setTextSize(15);
        name.setTextColor(Color.WHITE);
        name.setTypeface(null, android.graphics.Typeface.BOLD);
        info.addView(name);
        TextView meta = new TextView(this);
        meta.setText("作者 " + m.optString("author", "匿名") + " · " + m.optString("time", ""));
        meta.setTextSize(11);
        meta.setTextColor(0xFF94a3b8);
        info.addView(meta);
        if (!m.optString("desc", "").isEmpty()) {
            TextView desc = new TextView(this);
            desc.setText(m.optString("desc", ""));
            desc.setTextSize(12);
            desc.setTextColor(0xFFcbd5e1);
            desc.setMaxLines(2);
            info.addView(desc);
        }
        row.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
        row.setClickable(true);
        row.setOnClickListener(v -> downloadMap(m.optString("id", ""), m.optString("file", "")));
        final String thumbUrl = m.optString("thumb_url", "");
        if (!thumbUrl.isEmpty()) loadRemoteImage(thumbUrl, thumb);
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.addView(row);
        View div = new View(this);
        div.setBackgroundColor(0x22FFFFFF);
        div.setLayoutParams(new LinearLayout.LayoutParams(-1, 1));
        wrap.addView(div);
        return wrap;
    }

    private void downloadMap(final String id, final String file) {
        if (id.isEmpty()) return;
        mapLibStatus.setText("下载中：" + file);
        new Thread(() -> {
            try {
                byte[] data = httpGet(MAPLIB_BASE + "/get.php?id=" + id);
                if (data == null || data.length < 128) {
                    runOnUiThread(() -> mapLibStatus.setText("下载失败：文件无效"));
                    return;
                }
                final java.io.File out = new java.io.File(mapLibDir(), sanitizeFile(file));
                java.io.FileOutputStream fos = new java.io.FileOutputStream(out);
                fos.write(data);
                fos.close();
                runOnUiThread(() -> {
                    try {
                        loadBtlBytes(data, out.getName());
                        mapLibStatus.setText("已下载并打开：" + out.getName());
                        refreshLocalList();
                    } catch (Exception e) {
                        mapLibStatus.setText("打开失败：" + e.getMessage());
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> mapLibStatus.setText("下载失败：" + e.getMessage()));
            }
        }).start();
    }

    private void refreshLocalList() {
        if (localListBox == null) return;
        localListBox.removeAllViews();
        java.io.File[] files = mapLibDir().listFiles();
        if (files == null || files.length == 0) {
            TextView empty = new TextView(this);
            empty.setText("还没有下载过地图");
            empty.setTextSize(12);
            empty.setTextColor(0xFF94a3b8);
            localListBox.addView(empty);
            return;
        }
        java.util.Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (final java.io.File f : files) {
            if (!f.isFile()) continue;
            localListBox.addView(buildLocalRow(f));
        }
    }

    private View buildLocalRow(final java.io.File f) {
        final int density = (int) getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, 8 * density, 0, 8 * density);
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        TextView name = new TextView(this);
        name.setText(f.getName());
        name.setTextSize(14);
        name.setTextColor(Color.WHITE);
        info.addView(name);
        TextView meta = new TextView(this);
        meta.setText((f.length() / 1024) + " KB · " + new java.text.SimpleDateFormat("MM-dd HH:mm",
                java.util.Locale.ROOT).format(new java.util.Date(f.lastModified())));
        meta.setTextSize(11);
        meta.setTextColor(0xFF94a3b8);
        info.addView(meta);
        row.addView(info);
        Button open = new Button(this);
        open.setText("打开");
        open.setTextSize(11);
        open.setTextColor(Color.WHITE);
        open.setBackgroundColor(Color.parseColor("#1e5fa8"));
        open.setOnClickListener(v -> {
            try {
                loadBtlBytes(readFileBytes(f), f.getName());
            } catch (Exception e) {
                Toast.makeText(this, "打开失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
        Button exp = new Button(this);
        exp.setText("导出");
        exp.setTextSize(11);
        exp.setTextColor(Color.WHITE);
        exp.setBackgroundColor(Color.parseColor("#ea580c"));
        exp.setOnClickListener(v -> exportLocalMap(f));
        Button del = new Button(this);
        del.setText("删除");
        del.setTextSize(11);
        del.setTextColor(Color.WHITE);
        del.setBackgroundColor(Color.parseColor("#dc2626"));
        del.setOnClickListener(v -> {
            f.delete();
            refreshLocalList();
            Toast.makeText(this, "已删除本地文件", Toast.LENGTH_SHORT).show();
        });
        row.addView(open);
        row.addView(exp);
        row.addView(del);
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.addView(row);
        View div = new View(this);
        div.setBackgroundColor(0x22FFFFFF);
        div.setLayoutParams(new LinearLayout.LayoutParams(-1, 1));
        wrap.addView(div);
        return wrap;
    }

    private void exportLocalMap(java.io.File f) {
        try {
            java.io.File dir = new java.io.File(
                    android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_DOWNLOADS), "map_library");
            if (!dir.exists()) dir.mkdirs();
            java.io.File out = new java.io.File(dir, f.getName());
            copyFile(f, out);
            Toast.makeText(this, "已导出到 " + out.getPath(), Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "导出失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // ===== 上传 =====
    private void pickMapToUpload() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, REQUEST_MAPLIB_UPLOAD);
    }

    private void handleMapLibUpload(android.net.Uri uri) {
        try {
            final String displayName = queryDisplayName(uri);
            byte[] bytes = readUriBytes(uri);
            final byte[] btl = bytes;
            if (btl == null || btl.length < 128) {
                Toast.makeText(this, "文件无效", Toast.LENGTH_SHORT).show();
                return;
            }
            loadBtlBytes(btl, displayName);
            showUploadDialog(displayName, btl);
        } catch (Exception e) {
            Toast.makeText(this, "读取失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void showUploadDialog(final String defaultName, final byte[] btlBytes) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(28, 12, 28, 12);
        final EditText nameEt = new EditText(this);
        nameEt.setHint("地图名称");
        nameEt.setText(defaultName);
        styleDialogEdit(nameEt);
        final EditText authorEt = new EditText(this);
        authorEt.setHint("作者昵称");
        styleDialogEdit(authorEt);
        final EditText descEt = new EditText(this);
        descEt.setHint("简介（可选）");
        styleDialogEdit(descEt);
        l.addView(labelOf("地图名称"));
        l.addView(nameEt);
        l.addView(labelOf("作者昵称"));
        l.addView(authorEt);
        l.addView(labelOf("简介"));
        l.addView(descEt);
        AlertDialog.Builder b = new AlertDialog.Builder(this, R.style.DarkDialog);
        b.setTitle("上传地图到地图库");
        b.setView(l);
        b.setNegativeButton("取消", null);
        b.setPositiveButton("上传", (d, w) -> {
            String name = nameEt.getText().toString().trim();
            String author = authorEt.getText().toString().trim();
            String desc = descEt.getText().toString().trim();
            if (name.isEmpty()) name = defaultName;
            if (author.isEmpty()) author = "匿名";
            uploadMapToServer(btlBytes, name, author, desc);
        });
        b.show();
    }

    private TextView labelOf(String s) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(11);
        tv.setTextColor(0xFF9ca3af);
        tv.setPadding(0, 10, 0, 2);
        return tv;
    }

    private void styleDialogEdit(EditText et) {
        et.setTextColor(0xFFe5e7eb);
        et.setTextSize(13);
        et.setBackgroundColor(Color.parseColor("#2a2a2f"));
        et.setPadding(10, 8, 10, 8);
    }

    private void uploadMapToServer(final byte[] btlBytes, final String name,
                                   final String author, final String desc) {
        mapLibStatus.setText("正在生成缩略图…");
        new Thread(() -> {
            try {
                final byte[] thumb = renderThumbPng();
                runOnUiThread(() -> mapLibStatus.setText("正在上传…"));
                final java.util.Map<String, String> fields = new java.util.LinkedHashMap<>();
                fields.put("name", name);
                fields.put("author", author);
                fields.put("desc", desc);
                final String resp = httpUpload(MAPLIB_BASE + "/upload.php", fields,
                        "btl", "map.btl", btlBytes,
                        thumb != null ? "thumb" : null, thumb != null ? "thumb.png" : null, thumb);
                org.json.JSONObject o = new org.json.JSONObject(resp);
                runOnUiThread(() -> {
                    if (o.optBoolean("ok", false)) {
                        mapLibStatus.setText("上传成功：" + o.optString("name", name));
                        refreshOnlineList();
                    } else {
                        mapLibStatus.setText("上传失败：" + o.optString("error", "未知错误"));
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> mapLibStatus.setText("上传失败：" + e.getMessage()));
            }
        }).start();
    }

    /** 用当前打开的整图渲染出缩略图 PNG（与编辑器显示一致）。 */
    private byte[] renderThumbPng() {
        try {
            if (hexMapView == null || hexMapView.getMapData() == null) return null;
            Bitmap full = hexMapView.renderFullMap();
            if (full == null) return null;
            int w = full.getWidth(), h = full.getHeight();
            int nw = 320;
            int nh = Math.max(1, (int) (h * (nw / (float) w)));
            Bitmap small = Bitmap.createScaledBitmap(full, nw, nh, true);
            if (small != full) full.recycle();
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            small.compress(Bitmap.CompressFormat.PNG, 90, bos);
            small.recycle();
            return bos.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    private void loadBtlBytes(byte[] data, String fileName) throws Exception {
        MapData md = FileParser.loadFile(data, fileName);
        mapData = md;
        history.clear();
        hexMapView.setMapData(md);
        hexMapView.refresh();
        enterEditorAfterLoad();
        updateInfo();
        updateBtnState();
        currentFileName = fileName;
        if (fileInfoView != null) fileInfoView.setText("文件: " + fileName);
    }

    private static String sanitizeFile(String name) {
        String s = name == null ? "map.btl" : name.replaceAll("[^A-Za-z0-9._\\-]", "_");
        if (!s.toLowerCase().endsWith(".btl")) s += ".btl";
        return s;
    }

    private static byte[] readFileBytes(java.io.File f) throws java.io.IOException {
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        byte[] data = new byte[(int) f.length()];
        int off = 0;
        while (off < data.length) {
            int n = in.read(data, off, data.length - off);
            if (n < 0) break;
            off += n;
        }
        in.close();
        return data;
    }

    private static void copyFile(java.io.File src, java.io.File dst) throws java.io.IOException {
        java.io.FileInputStream in = new java.io.FileInputStream(src);
        java.io.FileOutputStream out = new java.io.FileOutputStream(dst);
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        out.close();
    }

    private String queryDisplayName(android.net.Uri uri) {
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
        return "map.btl";
    }

    private byte[] readUriBytes(android.net.Uri uri) throws java.io.IOException {
        java.io.InputStream in = getContentResolver().openInputStream(uri);
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return bos.toByteArray();
    }

    // ===== HTTP 工具 =====
    private static byte[] httpGet(String url) throws Exception {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        c.setConnectTimeout(12000);
        c.setReadTimeout(12000);
        c.setRequestMethod("GET");
        java.io.InputStream in = c.getInputStream();
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        c.disconnect();
        return bos.toByteArray();
    }

    private static String httpUpload(String url, java.util.Map<String, String> fields,
                                     String fileField, String fileName, byte[] fileBytes,
                                     String thumbField, String thumbName, byte[] thumbBytes) throws Exception {
        String boundary = "----wc4lib" + System.currentTimeMillis();
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream out = new java.io.DataOutputStream(bos);
        for (java.util.Map.Entry<String, String> e : fields.entrySet()) {
            out.writeBytes("--" + boundary + "\r\n");
            out.writeBytes("Content-Disposition: form-data; name=\"" + e.getKey() + "\"\r\n\r\n");
            out.writeBytes(e.getValue() + "\r\n");
        }
        out.writeBytes("--" + boundary + "\r\n");
        out.writeBytes("Content-Disposition: form-data; name=\"" + fileField + "\"; filename=\"" + fileName + "\"\r\n");
        out.writeBytes("Content-Type: application/octet-stream\r\n\r\n");
        out.write(fileBytes);
        out.writeBytes("\r\n");
        if (thumbField != null && thumbBytes != null) {
            out.writeBytes("--" + boundary + "\r\n");
            out.writeBytes("Content-Disposition: form-data; name=\"" + thumbField + "\"; filename=\"" + thumbName + "\"\r\n");
            out.writeBytes("Content-Type: image/png\r\n\r\n");
            out.write(thumbBytes);
            out.writeBytes("\r\n");
        }
        out.writeBytes("--" + boundary + "--\r\n");
        out.flush();
        byte[] body = bos.toByteArray();

        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setDoOutput(true);
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        c.setFixedLengthStreamingMode(body.length);
        java.io.OutputStream os = c.getOutputStream();
        os.write(body);
        os.close();
        java.io.InputStream in = c.getInputStream();
        java.io.ByteArrayOutputStream r = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) r.write(buf, 0, n);
        in.close();
        c.disconnect();
        return new String(r.toByteArray(), "UTF-8");
    }

    private void loadRemoteImage(final String url, final ImageView iv) {
        new Thread(() -> {
            try {
                final Bitmap bm = BitmapFactory.decodeStream(
                        (java.io.InputStream) new java.net.URL(url).getContent());
                runOnUiThread(() -> {
                    if (bm != null) iv.setImageBitmap(bm);
                });
            } catch (Exception ignored) {
            }
        }).start();
    }

    // ===== 数据段列表（仿枭雄：援军/空袭/首都/天气/放置/战略/空中支援） =====
    private void showTailSectionsDialog() {
        if (mapData == null || mapData.btlOriginalData == null) {
            Toast.makeText(this, "请先加载地图", Toast.LENGTH_SHORT).show();
            return;
        }
        FileParser.BtlHeaderInfo h = FileParser.parseBTLHeader(mapData.btlOriginalData);
        final int density = (int) getResources().getDisplayMetrics().density;
        final String[][] sections = {
                {"方案", String.valueOf(h.planCount), "plan"},
                {"天气", String.valueOf(h.weatherCount), "weather"},
                {"援军", String.valueOf(h.reinforceCount), "reinforce"},
                {"空袭", String.valueOf(h.airstrikeCount), "airstrike"},
                {"放置甲", String.valueOf(h.placementCountA), "placementA"},
                {"放置乙", String.valueOf(h.placementCountB), "placementB"},
                {"战略建设", String.valueOf(h.strategyCount), "strategy"},
                {"空中支援", String.valueOf(h.airSupportCount), "airSupport"},
                {"国家首都", String.valueOf(h.capitalCount), "capital"},
        };
        AlertDialog.Builder b = new AlertDialog.Builder(this, R.style.DarkDialog);
        b.setTitle("数据段列表");
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(24, 12, 24, 12);
        for (final String[] s : sections) {
            TextView row = new TextView(this);
            row.setText(s[0] + "（" + s[1] + " 条）");
            row.setTextSize(14);
            row.setTextColor(0xFFe2e8f0);
            row.setPadding(0, 10 * density, 0, 10 * density);
            row.setClickable(true);
            row.setOnClickListener(v -> showTailEntryList(s[0], s[2]));
            l.addView(row);
            View div = new View(this);
            div.setBackgroundColor(0x22FFFFFF);
            div.setLayoutParams(new LinearLayout.LayoutParams(-1, 1));
            l.addView(div);
        }
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(l);
        b.setView(sv);
        b.setPositiveButton("关闭", null);
        b.show();
    }

    private void showTailEntryList(final String title, final String kind) {
        if (mapData == null) return;
        final int density = (int) getResources().getDisplayMetrics().density;
        int start = startOf(kind);
        int count = countOf(kind);
        final int size = sizeOf(kind);
        if (start < 0) { Toast.makeText(this, "无此数据段", Toast.LENGTH_SHORT).show(); return; }
        AlertDialog.Builder b = new AlertDialog.Builder(this, R.style.DarkDialog);
        b.setTitle(title + "（" + count + " 条）");
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(24, 12, 24, 12);
        final int startF = start;
        for (int i = 0; i < count; i++) {
            final int idx = i;
            String key = keyText(kind, startF + i * size);
            TextView row = new TextView(this);
            row.setText("#" + (i + 1) + "  " + key);
            row.setTextSize(13);
            row.setTextColor(0xFFcbd5e1);
            row.setPadding(0, 8 * density, 0, 8 * density);
            row.setClickable(true);
            row.setOnClickListener(v -> showTailRecordEditor(title, kind, idx, size, startF));
            l.addView(row);
        }
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(l);
        b.setView(sv);
        b.setNegativeButton("返回", null);
        b.show();
    }

    private int startOf(String kind) {
        switch (kind) {
            case "plan": return mapData.planStart;
            case "weather": return mapData.weatherStart;
            case "reinforce": return mapData.reinforceStart;
            case "airstrike": return mapData.airstrikeStart;
            case "placementA": return mapData.placementAStart;
            case "placementB": return mapData.placementBStart;
            case "strategy": return mapData.strategyStart;
            case "airSupport": return mapData.airSupportStart;
            case "capital": return mapData.capitalStart;
        }
        return -1;
    }

    private int countOf(String kind) {
        FileParser.BtlHeaderInfo h = FileParser.parseBTLHeader(mapData.btlOriginalData);
        switch (kind) {
            case "plan": return h.planCount;
            case "weather": return h.weatherCount;
            case "reinforce": return h.reinforceCount;
            case "airstrike": return h.airstrikeCount;
            case "placementA": return h.placementCountA;
            case "placementB": return h.placementCountB;
            case "strategy": return h.strategyCount;
            case "airSupport": return h.airSupportCount;
            case "capital": return h.capitalCount;
        }
        return 0;
    }

    private int sizeOf(String kind) {
        FileParser.BtlHeaderInfo h = FileParser.parseBTLHeader(mapData.btlOriginalData);
        switch (kind) {
            case "reinforce": return h.version == 1 ? 80 : 104;
            case "airstrike": return 20;
            case "placementA":
            case "placementB": return 8;
            case "capital": return 4;
        }
        return 16;
    }

    private String keyText(String kind, int addr) {
        byte[] d = mapData.btlOriginalData;
        if (addr < 0 || addr + 8 > d.length) return "越界";
        switch (kind) {
            case "weather": return "回合" + readAt(d, addr + 8, "u32") + " 类型" + readAt(d, addr, "u32");
            case "reinforce": return "坐标" + readAt(d, addr, "u16") + " 兵种" + readAt(d, addr + 4, "u16")
                    + " 将领" + readAt(d, addr + 28, "u32") + " 出现回合" + readAt(d, addr + 76, "u32");
            case "airstrike": return "坐标" + readAt(d, addr, "u32") + " 兵种" + readAt(d, addr + 4, "u16")
                    + " 军团" + readAt(d, addr + 12, "u32") + " 回合" + readAt(d, addr + 16, "u32");
            case "placementA":
            case "placementB": return "坐标" + readAt(d, addr, "u32") + " 方向" + readAt(d, addr + 4, "u8");
            case "strategy": return "军团" + readAt(d, addr, "u32") + " 建设代码" + readAt(d, addr + 12, "u32");
            case "airSupport": return "空军" + readAt(d, addr, "u32") + " 弹药" + readAt(d, addr + 4, "u32")
                    + " 军团" + readAt(d, addr + 8, "u32");
            case "capital": return "地块坐标" + readAt(d, addr, "u32");
        }
        return "";
    }

    private static int readAt(byte[] d, int o, String type) {
        if (o < 0 || o + 4 > d.length) return 0;
        switch (type) {
            case "u8": return d[o] & 0xFF;
            case "u16": return (d[o] & 0xFF) | ((d[o + 1] & 0xFF) << 8);
            default: return (d[o] & 0xFF) | ((d[o + 1] & 0xFF) << 8)
                    | ((d[o + 2] & 0xFF) << 16) | ((d[o + 3] & 0xFF) << 24);
        }
    }

    private void showTailRecordEditor(final String title, final String kind,
                                      final int index, final int size, final int start) {
        final String[][] schema = tailSchema(kind);
        final byte[] raw = new byte[size];
        int addr = start + index * size;
        if (addr + size > mapData.btlOriginalData.length) {
            Toast.makeText(this, "数据越界", Toast.LENGTH_SHORT).show();
            return;
        }
        System.arraycopy(mapData.btlOriginalData, addr, raw, 0, size);
        final EditText[] eds = new EditText[schema.length];
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(28, 12, 28, 12);
        for (int i = 0; i < schema.length; i++) {
            TextView lb = new TextView(this);
            lb.setText(schema[i][0] + "（" + schema[i][1] + " @" + schema[i][2] + "）");
            lb.setTextSize(11);
            lb.setTextColor(0xFF9ca3af);
            l.addView(lb);
            EditText et = new EditText(this);
            et.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
            et.setText(String.valueOf(readAt(raw, Integer.decode(schema[i][2]), schema[i][1])));
            et.setTextColor(0xFFe5e7eb);
            et.setTextSize(13);
            et.setBackgroundColor(Color.parseColor("#2a2a2f"));
            l.addView(et);
            eds[i] = et;
        }
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(l);
        AlertDialog.Builder b = new AlertDialog.Builder(this, R.style.DarkDialog);
        b.setTitle(title + " #" + (index + 1));
        b.setView(sv);
        b.setNegativeButton("取消", null);
        b.setPositiveButton("保存", (d, w) -> {
            try {
                for (int i = 0; i < schema.length; i++) {
                    int val = eds[i].getText().toString().trim().isEmpty()
                            ? 0 : Integer.parseInt(eds[i].getText().toString().trim());
                    int off = Integer.decode(schema[i][2]);
                    writeAt(raw, off, schema[i][1], val);
                }
                FileParser.patchTailRecord(mapData, start, index, size, raw);
                hexMapView.refresh();
                updateInfo();
                Toast.makeText(this, "已保存 " + title + " #" + (index + 1), Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "保存失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
        b.show();
    }

    private static void writeAt(byte[] d, int o, String type, int v) {
        switch (type) {
            case "u8": d[o] = (byte) (v & 0xFF); break;
            case "u16": d[o] = (byte) (v & 0xFF); d[o + 1] = (byte) ((v >> 8) & 0xFF); break;
            default: d[o] = (byte) (v & 0xFF); d[o + 1] = (byte) ((v >> 8) & 0xFF);
                d[o + 2] = (byte) ((v >> 16) & 0xFF); d[o + 3] = (byte) ((v >> 24) & 0xFF);
        }
    }

    /** 各尾段字段（名称/类型/偏移），与枭雄 origin.py 一致。 */
    private static String[][] tailSchema(String kind) {
        switch (kind) {
            case "weather": return new String[][]{
                    {"天气类型", "u32", "0x0"}, {"未知", "u32", "0x4"},
                    {"触发回合", "u32", "0x8"}, {"持续回合", "u32", "0xC"}};
            case "reinforce": return new String[][]{
                    {"坐标", "u16", "0x0"}, {"爆兵事件", "u16", "0x2"}, {"兵种", "u16", "0x4"},
                    {"raw", "u16", "0x6"}, {"等级", "u16", "0x8"}, {"目标", "u16", "0xA"},
                    {"编制", "u16", "0xC"}, {"死亡事件", "u16", "0xE"}, {"行为", "u16", "0x10"},
                    {"血量比率", "u16", "0x12"}, {"朝向", "u16", "0x14"}, {"当前血量%", "u16", "0x16"},
                    {"状态1", "u16", "0x18"}, {"状态2", "u16", "0x1A"}, {"将领", "u32", "0x1C"},
                    {"军衔", "u32", "0x20"}, {"HP等级", "u32", "0x24"}, {"技能一", "u32", "0x28"},
                    {"技能二", "u32", "0x2C"}, {"技能三", "u32", "0x30"}, {"技能四", "u32", "0x34"},
                    {"技能五", "u32", "0x38"}, {"胸章一", "u32", "0x3C"}, {"胸章二", "u32", "0x40"},
                    {"胸章三", "u32", "0x44"}, {"所属军团", "u32", "0x48"}, {"出现回合", "u32", "0x4C"}};
            case "airstrike": return new String[][]{
                    {"坐标", "u32", "0x0"}, {"兵种", "u16", "0x4"}, {"兵种等级", "u16", "0x6"},
                    {"弹药", "u32", "0x8"}, {"军团", "u32", "0xC"}, {"回合", "u32", "0x10"}};
            case "placementA":
            case "placementB": return new String[][]{
                    {"坐标", "u32", "0x0"}, {"方向", "u8", "0x4"}, {"序号2", "u16", "0x5"},
                    {"运输船", "u8", "0x7"}};
            case "strategy": return new String[][]{
                    {"军团序号", "u32", "0x0"}, {"未知", "u32", "0x4"}, {"未知", "u32", "0x8"},
                    {"建设代码", "u32", "0xC"}};
            case "airSupport": return new String[][]{
                    {"空军序号", "u32", "0x0"}, {"弹药类型", "u32", "0x4"}, {"所属军团", "u32", "0x8"}};
            case "capital": return new String[][]{{"地块坐标", "u32", "0x0"}};
        }
        return new String[][]{};
    }

    // ===== 显示设置（仿枭雄 editor.json 分层开关） =====
    private void showDisplaySettingsDialog() {
        if (hexMapView == null) return;
        final int density = (int) getResources().getDisplayMetrics().density;
        final boolean[] cur = {
                hexMapView.isShowTerrainArt(), hexMapView.isShowBuildings(),
                hexMapView.isShowArmies(), hexMapView.isShowFlags(),
                hexMapView.isShowGenerals(), hexMapView.isShowFacilities(),
                hexMapView.isShowLabels(),
        };
        String[] names = {"地形贴图", "建筑", "兵种", "国旗", "将领", "设施", "省区编号"};
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(28, 12, 28, 12);
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            android.widget.CheckBox cb = new android.widget.CheckBox(this);
            cb.setText(names[i]);
            cb.setTextSize(14);
            cb.setTextColor(0xFFe2e8f0);
            cb.setChecked(cur[i]);
            l.addView(cb);
        }
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(l);
        AlertDialog.Builder b = new AlertDialog.Builder(this, R.style.DarkDialog);
        b.setTitle("显示设置");
        b.setView(sv);
        b.setNegativeButton("取消", null);
        b.setPositiveButton("应用", (d, w) -> {
            for (int i = 0; i < l.getChildCount(); i++) {
                boolean v = ((android.widget.CheckBox) l.getChildAt(i)).isChecked();
                switch (i) {
                    case 0: hexMapView.setShowTerrainArt(v); break;
                    case 1: hexMapView.setShowBuildings(v); break;
                    case 2: hexMapView.setShowArmies(v); break;
                    case 3: hexMapView.setShowFlags(v); break;
                    case 4: hexMapView.setShowGenerals(v); break;
                    case 5: hexMapView.setShowFacilities(v); break;
                    case 6: hexMapView.setShowLabels(v); break;
                }
            }
        });
        b.show();
    }
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 全局崩溃捕获，显示错误信息不闪退
        final Activity ctx = this;
        final UncaughtExceptionHandler defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, ex) -> {
            ex.printStackTrace();
            uploadCrashLog(ex);
            ctx.runOnUiThread(() -> {
                try {
                    AlertDialog.Builder b = new AlertDialog.Builder(ctx);
                    b.setTitle("程序崩溃");
                    android.widget.ScrollView sv = new android.widget.ScrollView(ctx);
                    android.widget.TextView tv = new android.widget.TextView(ctx);
                    tv.setText("错误信息：\n" + android.util.Log.getStackTraceString(ex));
                    tv.setTextSize(11);
                    tv.setPadding(20, 20, 20, 20);
                    sv.addView(tv);
                    b.setView(sv);
                    b.setPositiveButton("关闭", (d, w) -> {});
                    b.show();
                } catch (Exception ignored) {}
            });
        });
        super.onCreate(savedInstanceState);
        // 先读取本地缓存的远程黑名单，再判断本机是否被拦截
        loadRemoteBlockedCache();
        if (isBlockedDevice()) {
            // 被拦截设备：先静默联网刷新一次名单。
            // 后台已移除本机 -> 放行进入；断网或仍在名单 -> 静默退出。
            fetchRemoteBlocklist(true);
            return;
        }
        // 远程版本停用：命中则弹公告引导下载新版并退出，不进入编辑器
        loadVersionGateCache();
        if (isVersionBlocked()) {
            showBlockedVersionDialog();
            return;
        }
        // 正常设备：后台拉取远程黑名单并缓存；若本机刚被远程加入，使用中立即静默退出
        fetchRemoteBlocklist(false);
        initEditorAfterLaunch();
    }

    /** 通过拦截检查后，正式初始化编辑器（被拦截设备在后台移除后放行时也会走到这里）。 */
    private void initEditorAfterLaunch() {
        loadThumbs();
        // 兵种数据必须在 buildUI() 之前加载，右侧面板的兵种图标栏才会显示图标
        try {
            ArmyConfig.load(readAssetBytes("json/ArmySettings.json"));
        } catch (Exception ignored) {
        }
        try {
            GeneralData.load(readAssetBytes("json/GeneralSettings.json"));
        } catch (Exception ignored) {
        }
        try {
            GeneralData.loadSkills(readAssetBytes("json/SkillSettings.json"));
        } catch (Exception ignored) {
        }
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        buildUI();
        requestPerm();
        showDisclaimerOnce();
        initAudio();
        loadPrefs();
        // 打开时上报一次设备信息，使用中每 10 分钟心跳一次
        reportTelemetry("open");
        startTelemetryHeartbeat();
        // 拉取远程公告（有新公告才弹窗，一次）
        fetchAnnouncement();
        // 检查更新（有新版才提示）
        checkUpdate();
    }

    // ================= 应用内更新 =================
    private void checkUpdate() {
        new Thread(() -> {
            try {
                byte[] data = httpGet(UPDATE_URL);
                org.json.JSONObject o = new org.json.JSONObject(new String(data, "UTF-8"));
                if (!o.optBoolean("ok", false)) return;
                final int vc = o.optInt("versionCode", 0);
                final String vn = o.optString("versionName", "");
                final String url = o.optString("url", "");
                final String notes = o.optString("notes", "");
                if (vc <= BuildConfig.VERSION_CODE || url.isEmpty()) return;
                runOnUiThread(() -> showUpdateDialog(vn, notes, url));
            } catch (Exception ignored) {
            }
        }).start();
    }

    private void showUpdateDialog(String versionName, String notes, final String url) {
        AlertDialog.Builder b = new AlertDialog.Builder(this, R.style.DarkDialog);
        b.setTitle("发现新版本 " + (versionName.isEmpty() ? "" : versionName));
        TextView tv = new TextView(this);
        tv.setText((notes.isEmpty() ? "有新版本可用。" : notes) + "\n\n点「立即更新」下载并安装（约十几 MB，请连 Wi-Fi）。");
        tv.setTextSize(13);
        tv.setTextColor(0xFFd1d5db);
        tv.setPadding(28, 16, 28, 16);
        b.setView(tv);
        b.setNegativeButton("以后再说", null);
        b.setPositiveButton("立即更新", (d, w) -> downloadAndInstall(url));
        b.show();
    }

    private void downloadAndInstall(final String url) {
        Toast.makeText(this, "开始下载更新…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                        new java.net.URL(url).openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(120000);
                java.io.InputStream in = c.getInputStream();
                final java.io.File apk = new java.io.File(getCacheDir(), "update.apk");
                java.io.FileOutputStream fos = new java.io.FileOutputStream(apk);
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
                fos.close();
                in.close();
                c.disconnect();
                if (apk.length() < 1024) throw new Exception("下载内容无效");
                runOnUiThread(() -> installApk(apk));
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this,
                        "下载失败: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void installApk(java.io.File apk) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26
                    && !getPackageManager().canRequestPackageInstalls()) {
                Toast.makeText(this, "请先允许本应用「安装未知应用」，再点一次更新", Toast.LENGTH_LONG).show();
                Intent set = new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        android.net.Uri.parse("package:" + getPackageName()));
                startActivity(set);
                return;
            }
            android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(this,
                    getPackageName() + ".fileprovider", apk);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "安装失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /** 崩溃时把堆栈上报到服务器（远程诊断闪退原因），失败静默。 */
    private void uploadCrashLog(final Throwable ex) {
        try {
            final String trace = android.util.Log.getStackTraceString(ex);
            new Thread(() -> {
                try {
                    SharedPreferences prefs =
                            getSharedPreferences("wc4_editor_prefs", MODE_PRIVATE);
                    String installId = prefs.getString("install_id", "");
                    String version = "";
                    try {
                        version = getPackageManager()
                                .getPackageInfo(getPackageName(), 0).versionName;
                    } catch (Exception ignored) {
                    }
                    org.json.JSONObject o = new org.json.JSONObject();
                    o.put("secret", TELEMETRY_SECRET);
                    o.put("event", "crash");
                    o.put("install_id", installId);
                    o.put("brand", android.os.Build.MANUFACTURER);
                    o.put("model", android.os.Build.MODEL);
                    o.put("android_version", android.os.Build.VERSION.RELEASE);
                    o.put("sdk", android.os.Build.VERSION.SDK_INT);
                    o.put("app_version", version);
                    o.put("app_label", "Terrain Editor正式版");
                    o.put("msg", trace.length() > 4000 ? trace.substring(0, 4000) : trace);
                    byte[] body = o.toString().getBytes("UTF-8");
                    java.net.URL url = new java.net.URL(TELEMETRY_URL);
                    java.net.HttpURLConnection conn =
                            (java.net.HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setDoOutput(true);
                    conn.setConnectTimeout(6000);
                    conn.setReadTimeout(6000);
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                    conn.getOutputStream().write(body);
                    conn.getResponseCode();
                    java.io.InputStream in = conn.getErrorStream();
                    if (in != null) in.close();
                    conn.disconnect();
                } catch (Exception ignored) {
                }
            }).start();
        } catch (Exception ignored) {
        }
    }

    /** 读取本地缓存的远程拦截机型（断网时仍生效）。 */
    private void loadRemoteBlockedCache() {
        try {
            SharedPreferences prefs = getSharedPreferences("wc4_editor_prefs", MODE_PRIVATE);
            String raw = prefs.getString("remote_blocked_models", "");
            remoteBlockedModels = new java.util.ArrayList<>();
            if (raw != null && !raw.isEmpty()) {
                for (String s : raw.split("\n")) {
                    String t = s.trim();
                    if (!t.isEmpty()) remoteBlockedModels.add(t);
                }
            }
        } catch (Exception ignored) {
        }
    }

    /** 判断当前设备是否在黑名单内（本地固定 + 远程缓存，包含匹配，忽略大小写）。 */
    private boolean isBlockedDevice() {
        String model = android.os.Build.MODEL;
        if (model == null) return false;
        String m = model.trim().toLowerCase();
        java.util.List<String> all = new java.util.ArrayList<>();
        for (String b : BLOCKED_MODELS) all.add(b);
        if (remoteBlockedModels != null) all.addAll(remoteBlockedModels);
        for (String b : all) {
            String bm = b.trim().toLowerCase();
            if (bm.isEmpty()) continue;
            if (m.equals(bm) || m.contains(bm)) return true;
        }
        return false;
    }

    /** 读取本地缓存的停用版本名单与公告（断网时仍生效）。 */
    private void loadVersionGateCache() {
        try {
            SharedPreferences prefs = getSharedPreferences("wc4_editor_prefs", MODE_PRIVATE);
            String raw = prefs.getString("remote_blocked_versions", "");
            remoteBlockedVersions = new java.util.ArrayList<>();
            if (raw != null && !raw.isEmpty()) {
                for (String s : raw.split("\n")) {
                    String t = s.trim();
                    if (!t.isEmpty()) remoteBlockedVersions.add(t);
                }
            }
            cachedAnnounceTitle = prefs.getString("announcement_title", "");
            cachedAnnounceContent = prefs.getString("announcement_content", "");
        } catch (Exception ignored) {
        }
    }

    /** 当前版本是否在远程停用名单内（versionName 或 应用名 匹配）。 */
    private boolean isVersionBlocked() {
        String version = "";
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        if (remoteBlockedVersions == null) return false;
        for (String v : remoteBlockedVersions) {
            String t = v.trim();
            if (t.isEmpty()) continue;
            if (version != null && version.equalsIgnoreCase(t)) return true;
            if (APP_LABEL.equalsIgnoreCase(t)) return true;
        }
        return false;
    }

    /** 版本被停用：弹公告引导下载新版，确定后退出（不进入编辑器）。 */
    private void showBlockedVersionDialog() {
        if (versionGateDialogShowing) return;
        versionGateDialogShowing = true;
        String title = cachedAnnounceTitle != null && !cachedAnnounceTitle.isEmpty()
                ? cachedAnnounceTitle : "版本已停用";
        String msg = cachedAnnounceContent != null && !cachedAnnounceContent.isEmpty()
                ? cachedAnnounceContent
                : "当前版本已停止使用，请下载新版后继续使用。\n获取新版请进交流群：1001026138";
        AlertDialog.Builder b = new AlertDialog.Builder(this, R.style.DarkDialog);
        b.setTitle(title);
        b.setMessage(msg);
        b.setCancelable(false);
        b.setPositiveButton("知道了", (d, w) -> finish());
        b.setOnDismissListener(d -> finish());
        b.show();
    }

    /** 后台拉取服务器远程拦截机型并缓存；本机在名单内时静默退出。 */
    private void fetchRemoteBlocklist(final boolean blockedAtStart) {
        new Thread(() -> {
            final java.util.List<String> fetched = new java.util.ArrayList<>();
            boolean ok = false;
            try {
                java.net.URL url = new java.net.URL(BLOCKLIST_URL);
                java.net.HttpURLConnection conn =
                        (java.net.HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(6000);
                conn.setReadTimeout(6000);
                int code = conn.getResponseCode();
                if (code != 200) {
                    conn.disconnect();
                } else {
                    java.io.InputStream in = conn.getInputStream();
                    java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
                    in.close();
                    conn.disconnect();
                    org.json.JSONObject o = new org.json.JSONObject(
                            new String(bos.toByteArray(), "UTF-8"));
                    org.json.JSONArray arr = o.optJSONArray("models");
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            String s = arr.optString(i, "").trim();
                            if (!s.isEmpty()) fetched.add(s);
                        }
                    }
                    ok = true;
                }
            } catch (Exception e) {
                android.util.Log.w("Blocklist", "拉取远程黑名单失败: " + e.getMessage());
            }
            if (!ok) {
                // 拉取失败（断网等）：被拦截设备维持拦截，静默退出
                if (blockedAtStart) runOnUiThread(MainActivity.this::finish);
                return;
            }
            {
                StringBuilder sb = new StringBuilder();
                for (String s : fetched) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(s);
                }
                getSharedPreferences("wc4_editor_prefs", MODE_PRIVATE)
                        .edit().putString("remote_blocked_models", sb.toString()).apply();
                runOnUiThread(() -> {
                    remoteBlockedModels = fetched;
                    if (isBlockedDevice()) {
                        // 本机仍在名单（或刚被远程加入）：静默退出，不进入编辑器
                        finish();
                    } else if (blockedAtStart) {
                        // 被拦截设备：后台已移除本机，放行进入编辑器
                        initEditorAfterLaunch();
                    }
                });
            }
        }).start();
    }

    private void initAudio() {
        // 背景音乐
        try {
            bgMusicPlayer = MediaPlayer.create(this, R.raw.bg_music);
            bgMusicPlayer.setLooping(true);
            bgMusicPlayer.setVolume(0.5f, 0.5f);
        } catch (Exception e) { bgMusicPlayer = null; }

        // 音效
        AudioAttributes attrs = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build();
        soundPool = new SoundPool.Builder().setMaxStreams(3).setAudioAttributes(attrs).build();
        try {
            sfxSelectId = soundPool.load(this, R.raw.sfx_select, 1);
        } catch (Exception e) { sfxSelectId = 0; }
    }

    private void loadPrefs() {
        SharedPreferences prefs = getSharedPreferences("wc4_editor_prefs", MODE_PRIVATE);
        musicEnabled = prefs.getBoolean("music_enabled", true);
        sfxEnabled = prefs.getBoolean("sfx_enabled", true);
        customSavePath = prefs.getString("save_path", "");
        if (musicEnabled && bgMusicPlayer != null) bgMusicPlayer.start();
    }

    /** 每 10 分钟向后台上报一次心跳（App 在前台运行期间）。 */
    private void startTelemetryHeartbeat() {
        final Handler h = new Handler(Looper.getMainLooper());
        h.postDelayed(new Runnable() {
            @Override public void run() {
                reportTelemetry("heartbeat");
                h.postDelayed(this, 10 * 60 * 1000L);
            }
        }, 10 * 60 * 1000L);
    }

    /** 后台线程上报设备使用信息：设备型号/系统/版本/打开时间，失败静默不影响编辑器。 */
    private void reportTelemetry(final String event) {
        new Thread(() -> {
            try {
                SharedPreferences prefs = getSharedPreferences("wc4_editor_prefs", MODE_PRIVATE);
                String installId = prefs.getString("install_id", "");
                if (installId.isEmpty()) {
                    installId = java.util.UUID.randomUUID().toString();
                    prefs.edit().putString("install_id", installId).apply();
                }
                String version = "";
                try {
                    version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
                } catch (Exception ignored) {
                }
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("secret", TELEMETRY_SECRET);
                o.put("event", event);
                o.put("install_id", installId);
                o.put("brand", android.os.Build.MANUFACTURER);
                o.put("model", android.os.Build.MODEL);
                o.put("android_version", android.os.Build.VERSION.RELEASE);
                o.put("sdk", android.os.Build.VERSION.SDK_INT);
                o.put("app_version", version);
                o.put("app_label", "Terrain Editor正式版");
                byte[] body = o.toString().getBytes("UTF-8");
                java.net.URL url = new java.net.URL(TELEMETRY_URL);
                java.net.HttpURLConnection conn =
                        (java.net.HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setConnectTimeout(6000);
                conn.setReadTimeout(6000);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                conn.setRequestProperty("User-Agent", "Terrain-Editor-Android/" + version);
                conn.getOutputStream().write(body);
                int code = conn.getResponseCode();
                java.io.InputStream in = (code >= 200 && code < 300)
                        ? conn.getInputStream() : conn.getErrorStream();
                if (in != null) in.close();
                conn.disconnect();
                if (code != 200) {
                    android.util.Log.w("Telemetry", "上报失败: HTTP " + code);
                }
            } catch (Exception e) {
                // 网络异常/地址未配置时静默失败，绝不影响编辑器
                android.util.Log.w("Telemetry", "上报异常: " + e.getMessage());
            }
        }).start();
    }

    /** 启动时拉取远程公告：有新公告（hash 变化）才弹窗显示一次，失败静默。 */
    private void fetchAnnouncement() {
        new Thread(() -> {
            try {
                java.net.URL url = new java.net.URL(ANNOUNCEMENT_URL);
                java.net.HttpURLConnection conn =
                        (java.net.HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(6000);
                conn.setReadTimeout(6000);
                conn.setRequestProperty("User-Agent", "Terrain-Editor-Android");
                int code = conn.getResponseCode();
                if (code != 200) {
                    conn.disconnect();
                    return;
                }
                java.io.InputStream in = conn.getInputStream();
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
                in.close();
                conn.disconnect();
                org.json.JSONObject o = new org.json.JSONObject(
                        new String(bos.toByteArray(), "UTF-8"));
                final String title = o.optString("title", "");
                final String content = o.optString("content", "").trim();
                final String hash = o.optString("hash", "");
                final SharedPreferences prefs =
                        getSharedPreferences("wc4_editor_prefs", MODE_PRIVATE);
                org.json.JSONArray vArr = o.optJSONArray("blocked_versions");
                final java.util.List<String> fetchedVers = new java.util.ArrayList<>();
                if (vArr != null) {
                    for (int i = 0; i < vArr.length(); i++) {
                        String s = vArr.optString(i, "").trim();
                        if (!s.isEmpty()) fetchedVers.add(s);
                    }
                }
                StringBuilder vsb = new StringBuilder();
                for (String s : fetchedVers) {
                    if (vsb.length() > 0) vsb.append('\n');
                    vsb.append(s);
                }
                prefs.edit()
                        .putString("remote_blocked_versions", vsb.toString())
                        .putString("announcement_title", title)
                        .putString("announcement_content", content)
                        .apply();
                runOnUiThread(() -> {
                    remoteBlockedVersions = fetchedVers;
                    cachedAnnounceTitle = title;
                    cachedAnnounceContent = content;
                    // 版本被远程停用：弹公告引导下载新版并退出
                    if (isVersionBlocked()) {
                        showBlockedVersionDialog();
                        return;
                    }
                    if (content.isEmpty() || hash.isEmpty()) return;
                    if (hash.equals(prefs.getString("announcement_hash", ""))) return;
                    AlertDialog.Builder b = new AlertDialog.Builder(this, R.style.DarkDialog);
                    b.setTitle(title.isEmpty() ? "公告" : title);
                    b.setMessage(content);
                    b.setPositiveButton("知道了", null);
                    b.show();
                    prefs.edit().putString("announcement_hash", hash).apply();
                });
            } catch (Exception e) {
                android.util.Log.w("Announcement", "拉取公告失败: " + e.getMessage());
            }
        }).start();
    }

    private void playSelectSfx() {
        if (sfxEnabled && soundPool != null && sfxSelectId != 0) {
            soundPool.play(sfxSelectId, 1.0f, 1.0f, 1, 0, 1.0f);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (bgMusicPlayer != null) { bgMusicPlayer.stop(); bgMusicPlayer.release(); bgMusicPlayer = null; }
        if (soundPool != null) { soundPool.release(); soundPool = null; }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (bgMusicPlayer != null && bgMusicPlayer.isPlaying()) bgMusicPlayer.pause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (musicEnabled && bgMusicPlayer != null && !bgMusicPlayer.isPlaying()) bgMusicPlayer.start();
    }

    private void showDisclaimerOnce() {
        android.content.SharedPreferences prefs = getSharedPreferences("wc4_editor_prefs", MODE_PRIVATE);
        if (prefs.getBoolean("disclaimer_accepted", false)) return;

        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle("免责声明")
            .setMessage("本项目为非官方第三方工具，与 EasyTech（EasyTech 为《世界征服者4》官方开发公司）无任何关联。\n\n"
                + "使用本工具所产生的一切后果（包括但不限于游戏数据损坏、存档异常、账号风险等）由使用者自行承担。\n\n"
                + "请勿将本工具用于任何商业用途或侵犯他人权益的行为。\n"
                + "我们鼓励你在合法、合理的前提下使用本工具，尊重原游戏开发者的知识产权与用户协议。\n\n"
                + "如你使用本工具，即表示你已理解并同意上述声明。\n\n"
                + "——— 来自 AC小辰 · 小辰科技官方")
            .setPositiveButton("确认并进入（3秒）", null)
            .setCancelable(false)
            .create();

        dialog.setOnShowListener(d -> {
            android.widget.Button btn = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            btn.setEnabled(false);
            btn.setText("请阅读声明（3秒）");
            new android.os.CountDownTimer(3000, 100) {
                @Override public void onTick(long millisUntilFinished) {
                    btn.setText("请阅读声明（" + (millisUntilFinished / 1000 + 1) + "秒）");
                }
                @Override public void onFinish() {
                    btn.setEnabled(true);
                    btn.setText("确认并进入");
                    btn.setOnClickListener(v -> {
                        prefs.edit().putBoolean("disclaimer_accepted", true).apply();
                        dialog.dismiss();
                    });
                }
            }.start();
        });
        dialog.show();
    }

    private void loadThumbs() {
        try {
            terrainThumbs.put(0, loadBmp("map/land.png"));
            terrainThumbs.put(1, loadBmp("map/sea.png"));
            terrainThumbs.put(2, loadBmp("map/desert_1.png"));
            terrainThumbs.put(3, loadBmp("map/l1_mountain_1.png"));
            terrainThumbs.put(4, loadBmp("map/m1_mountain_1.png"));
            terrainThumbs.put(5, loadBmp("map/h1_mountain_1.png"));
            terrainThumbs.put(6, loadBmp("map/l2_mountain_1.png"));
            terrainThumbs.put(7, loadBmp("map/m3_mountain_1.png"));
            terrainThumbs.put(8, loadBmp("map/h2_mountain_1.png"));
            terrainThumbs.put(9, loadBmp("map/l3_mountain_1.png"));
            terrainThumbs.put(10, loadBmp("map/m3_mountain_1.png"));
            terrainThumbs.put(11, loadBmp("map/h3_mountain_1.png"));
            terrainThumbs.put(12, loadBmp("map/l4_mountain_1.png"));
            terrainThumbs.put(13, loadBmp("map/m4_mountain_1.png"));
            terrainThumbs.put(14, loadBmp("map/h4_mountain_1.png"));
            terrainThumbs.put(15, loadBmp("map/cactus_1.png"));
            terrainThumbs.put(16, loadBmp("map/broadleaf_1.png"));
            terrainThumbs.put(18, loadBmp("map/broadleaf2_1.png"));
            terrainThumbs.put(20, loadBmp("map/coniferous_1.png"));
            terrainThumbs.put(21, loadBmp("map/coniferous2_1.png"));
            terrainThumbs.put(22, loadBmp("map/palmae_1.png"));
            terrainThumbs.put(26, loadBmp("map/farmland_1.png"));
            terrainThumbs.put(30, loadBmp("map/hollow_1.png"));
            terrainThumbs.put(31, loadBmp("map/snowfield_1.png"));
            int[] bids = {1,2,3,11,12,13,14,15,16,17,21,22,23,31,32,33,34};
            for (int id : bids) {
                Bitmap b = loadBmp("btl/building_" + id + ".png");
                if (b != null) buildingThumbs.put(id, b);
            }
        } catch (Exception ignored) {}
    }

    private Bitmap loadBmp(String path) {
        try { return BitmapFactory.decodeStream(getAssets().open(path)); }
        catch (Exception e) { return null; }
    }

    /** 从 assets/flag/ 加载国家国旗：国家 ID -> flag_N.png（加载一次缓存复用）。 */
    private void ensureFlagIcons() {
        if (flagIcons != null) return;
        flagIcons = new java.util.HashMap<>();
        for (int id = 1; id <= 48; id++) {
            Bitmap b = loadBmp("flag/flag_" + id + ".png");
            if (b != null) flagIcons.put(id, b);
        }
        Bitmap err = loadBmp("flag/flag_error.png");
        if (err != null) flagIcons.put(-1, err);
    }

    private void requestPerm() {
        if (Build.VERSION.SDK_INT >= 30) {
            if (!Environment.isExternalStorageManager()) {
                Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                i.setData(Uri.parse("package:" + getPackageName()));
                startActivity(i);
            }
        } else if (Build.VERSION.SDK_INT >= 23) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE}, 100);
            }
        }
    }

    private void buildUI() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutParams(new ViewGroup.LayoutParams(-1, -1));
        root.setBackgroundColor(Color.parseColor("#16213e"));
        topBarView = createTopBar();
        root.addView(topBarView);

        LinearLayout body = new LinearLayout(this);
        int screenWidthDp = (int) (getResources().getDisplayMetrics().widthPixels
                / getResources().getDisplayMetrics().density);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 1));

        hexMapView = new HexMapView(this);
        hexMapView.setLayoutParams(new LinearLayout.LayoutParams(-1, -1));
        hexMapView.setOnTileSelectListener(this);
        hexMapView.setArmyBrushHandler((bx, by) -> addArmyAtBrush(bx, by));
        body.addView(hexMapView);

        // 用 FrameLayout 包裹地图区，便于放置左侧浮动按钮与可拖动的右面板
        FrameLayout bodyFrame = new FrameLayout(this);
        bodyFrame.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 1));
        body.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));
        bodyFrame.addView(body);
        int density = (int) getResources().getDisplayMetrics().density;
        int screenWidthPx = getResources().getDisplayMetrics().widthPixels;

        // 可拖动、可缩放的浮动右面板：外层容器（左竖条缩放把手 + 内容面板）
        rightPanel = createRightPanel();
        int panelW = (int) (Math.min(320, screenWidthDp - 32) * density);
        int screenH = getResources().getDisplayMetrics().heightPixels;
        LinearLayout panelFrame = new LinearLayout(this);
        panelFrameView = panelFrame;
        panelFrame.setOrientation(LinearLayout.HORIZONTAL);
        FrameLayout.LayoutParams rpLp = new FrameLayout.LayoutParams(panelW,
                screenH - 16 * density, Gravity.RIGHT | Gravity.TOP);
        rpLp.rightMargin = 8 * density;
        rpLp.topMargin = 8 * density;
        bodyFrame.addView(panelFrame, rpLp);
        setupDraggablePanel(panelFrame, rightPanel, screenWidthPx, density);
        panelFrame.addView(rightPanel, new LinearLayout.LayoutParams(0, -1, 1));

        // 左侧整合面板：文件（打开/保存）+ 声音（音乐/音效）
        LinearLayout leftPanel = new LinearLayout(this);
        leftPanel.setOrientation(LinearLayout.VERTICAL);
        leftPanel.setPadding(10, 10, 10, 10);
        android.graphics.drawable.GradientDrawable panelBg = new android.graphics.drawable.GradientDrawable();
        panelBg.setColor(0xE616213E);
        panelBg.setCornerRadius(14 * density);
        leftPanel.setBackground(panelBg);

        TextView fileLabel = new TextView(this);
        fileLabelView = fileLabel;
        fileLabel.setText("文件");
        fileLabel.setTextSize(11);
        fileLabel.setTypeface(null, android.graphics.Typeface.BOLD);
        fileLabel.setTextColor(Color.WHITE);
        fileLabel.setGravity(Gravity.CENTER);
        fileLabel.setPadding(4, 0, 4, 4);
        leftPanel.addView(fileLabel);
        Button floatOpen = makeFloatBtn("打开", 0xFF1E5FA8);
        floatOpenBtn = floatOpen;
        floatOpen.setOnClickListener(v -> openFile());
        Button floatSave = makeFloatBtn("保存", 0xFF1E5FA8);
        floatSaveBtn = floatSave;
        floatSave.setOnClickListener(v -> saveFile());
        leftPanel.addView(floatOpen);
        leftPanel.addView(floatSave);
        // 官方地图编辑器“导出测试BTL和底图”按钮
        Button exportTestBtn = makeFloatBtn("导出测试BTL和底图", 0xFF8B5CF6);
        exportTestBtn.setOnClickListener(v -> exportTestConquest());
        leftPanel.addView(exportTestBtn);
        // “显示省区规划”开关：放在保存下方（战役/征服模式下左侧面板只保留 保存 + 本开关）
        provinceShowBtn = makeFloatBtn("显示省区规划：关", 0xFF7C3AED);
        provinceShowBtn.setOnClickListener(v -> {
            boolean now = !hexMapView.isProvinceView();
            hexMapView.setProvinceView(now);
            provinceShowBtn.setText(now ? "显示省区规划：开" : "显示省区规划：关");
            hexMapView.refresh();
        });
        leftPanel.addView(provinceShowBtn);

        View divider = new View(this);
        leftDividerView = divider;
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-1, 1);
        dlp.topMargin = 8 * density;
        dlp.bottomMargin = 8 * density;
        divider.setLayoutParams(dlp);
        divider.setBackgroundColor(0x55FFFFFF);
        leftPanel.addView(divider);

        TextView soundLabel = new TextView(this);
        soundLabelView = soundLabel;
        soundLabel.setText("声音");
        soundLabel.setTextSize(11);
        soundLabel.setTypeface(null, android.graphics.Typeface.BOLD);
        soundLabel.setTextColor(Color.WHITE);
        soundLabel.setGravity(Gravity.CENTER);
        soundLabel.setPadding(4, 4, 4, 4);
        leftPanel.addView(soundLabel);
        Button floatMusic = makeFloatBtn(musicEnabled ? "关闭音乐" : "开启音乐", 0xFF2F6B3A);
        floatMusicBtn = floatMusic;
        floatMusic.setOnClickListener(v -> {
            musicEnabled = !musicEnabled;
            floatMusic.setText(musicEnabled ? "关闭音乐" : "开启音乐");
            getSharedPreferences("wc4_editor_prefs", MODE_PRIVATE)
                    .edit().putBoolean("music_enabled", musicEnabled).apply();
            if (musicEnabled && bgMusicPlayer != null) bgMusicPlayer.start();
            else if (bgMusicPlayer != null) bgMusicPlayer.pause();
        });
        Button floatSfx = makeFloatBtn(sfxEnabled ? "关闭音效" : "开启音效", 0xFF2F6B3A);
        floatSfxBtn = floatSfx;
        floatSfx.setOnClickListener(v -> {
            sfxEnabled = !sfxEnabled;
            floatSfx.setText(sfxEnabled ? "关闭音效" : "开启音效");
            getSharedPreferences("wc4_editor_prefs", MODE_PRIVATE)
                    .edit().putBoolean("sfx_enabled", sfxEnabled).apply();
        });
        leftPanel.addView(floatMusic);
        leftPanel.addView(floatSfx);
        leftPanelView = leftPanel;
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(110 * density, -2,
                Gravity.CENTER_VERTICAL | Gravity.LEFT);
        lp.leftMargin = 8 * density;
        bodyFrame.addView(leftPanel, lp);

        // 底部左下角：交流群文字
        TextView groupText = new TextView(this);
        groupTextView = groupText;
        groupText.setText("地形编辑器交流群1001026138，进群获取新版本，全新功能全新布局！");
        groupText.setTextColor(0xCCFFFFFF);
        groupText.setTextSize(10);
        groupText.setGravity(Gravity.LEFT);
        groupText.setPadding(8 * density, 4 * density, 8 * density, 4 * density);
        groupText.setBackgroundColor(0x66000000);
        FrameLayout.LayoutParams gtLp = new FrameLayout.LayoutParams(-2, -2,
                Gravity.BOTTOM | Gravity.LEFT);
        gtLp.leftMargin = 6 * density;
        gtLp.bottomMargin = 4 * density;
        bodyFrame.addView(groupText, gtLp);

        // 左上角：FPS / 设备 / 版本号
        LinearLayout infoPanel = new LinearLayout(this);
        infoPanelView = infoPanel;
        infoPanel.setOrientation(LinearLayout.VERTICAL);
        infoPanel.setPadding(6 * density, 4 * density, 6 * density, 4 * density);
        infoPanel.setBackgroundColor(0x66000000);

        TextView fpsView = new TextView(this);
        fpsView.setText("FPS: --");
        fpsView.setTextColor(0xCC00E676);
        fpsView.setTextSize(10);
        fpsView.setTypeface(null, android.graphics.Typeface.BOLD);
        infoPanel.addView(fpsView);

        TextView deviceView = new TextView(this);
        deviceView.setText("设备: " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL);
        deviceView.setTextColor(0xCCFFFFFF);
        deviceView.setTextSize(10);
        infoPanel.addView(deviceView);

        TextView versionView = new TextView(this);
        versionView.setText("Terrain Editor正式版");
        versionView.setTextColor(0xCCFFFFFF);
        versionView.setTextSize(10);
        infoPanel.addView(versionView);

        TextView dateView = new TextView(this);
        dateView.setText("日期: " + new java.text.SimpleDateFormat("yyyy-MM-dd")
                .format(new java.util.Date()));
        dateView.setTextColor(0xCCFFFFFF);
        dateView.setTextSize(10);
        infoPanel.addView(dateView);

        final TextView timeView = new TextView(this);
        timeView.setText("时间: --:--:--");
        timeView.setTextColor(0xCCFFFFFF);
        timeView.setTextSize(10);
        infoPanel.addView(timeView);
        final Runnable timeTick = new Runnable() {
            @Override public void run() {
                timeView.setText("时间: " + new java.text.SimpleDateFormat("HH:mm:ss")
                        .format(new java.util.Date()));
                timeView.postDelayed(this, 1000);
            }
        };
        timeView.postDelayed(timeTick, 1000);

        TextView fileView = new TextView(this);
        fileInfoView = fileView;
        fileView.setText("文件: 未命名地图");
        fileView.setTextColor(0xCCFFFFFF);
        fileView.setTextSize(10);
        infoPanel.addView(fileView);

        FrameLayout.LayoutParams infoLp = new FrameLayout.LayoutParams(-2, -2,
                Gravity.TOP | Gravity.LEFT);
        infoLp.leftMargin = 6 * density;
        infoLp.topMargin = 56; // 顶栏高 52px，FPS/设备放在其下方
        // 不在这里加入 bodyFrame，最后加到 rootFrame 顶层，保证 FPS/设备不被任何面板覆盖
        startFpsCounter(fpsView);

        // 顶部居中：截取模式指示 + 确认/取消按钮
        cropOverlay = new LinearLayout(this);
        cropOverlay.setOrientation(LinearLayout.VERTICAL);
        cropOverlay.setGravity(Gravity.CENTER_HORIZONTAL);
        cropOverlay.setPadding(10 * density, 6 * density, 10 * density, 6 * density);
        cropOverlay.setBackgroundColor(0xCC16213E);
        cropStatus = new TextView(this);
        cropStatus.setTextSize(12);
        cropStatus.setTextColor(Color.WHITE);
        cropStatus.setGravity(Gravity.CENTER);
        cropStatus.setPadding(0, 0, 0, 4 * density);
        cropOverlay.addView(cropStatus);
        cropBtnsRow = new LinearLayout(this);
        cropBtnsRow.setOrientation(LinearLayout.HORIZONTAL);
        cropConfirmBtn = new Button(this);
        cropConfirmBtn.setText("确认截取");
        cropConfirmBtn.setTextSize(11);
        cropConfirmBtn.setTextColor(Color.WHITE);
        cropConfirmBtn.setBackgroundColor(Color.parseColor("#22c55e"));
        cropConfirmBtn.setPadding(10 * density, 0, 10 * density, 0);
        cropConfirmBtn.setOnClickListener(v -> applyCrop());
        cropCancelBtn = new Button(this);
        cropCancelBtn.setText("取消");
        cropCancelBtn.setTextSize(11);
        cropCancelBtn.setTextColor(Color.WHITE);
        cropCancelBtn.setBackgroundColor(Color.parseColor("#e11d48"));
        cropCancelBtn.setPadding(10 * density, 0, 10 * density, 0);
        cropCancelBtn.setOnClickListener(v -> cancelCrop());
        cropBtnsRow.addView(cropConfirmBtn);
        View cropGap = new View(this);
        cropGap.setLayoutParams(new LinearLayout.LayoutParams(8 * density, 1));
        cropBtnsRow.addView(cropGap);
        cropBtnsRow.addView(cropCancelBtn);
        cropOverlay.addView(cropBtnsRow);
        FrameLayout.LayoutParams cropLp = new FrameLayout.LayoutParams(-2, -2,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        cropLp.topMargin = 6 * density;
        bodyFrame.addView(cropOverlay, cropLp);
        cropOverlay.setVisibility(View.GONE);

        root.addView(bodyFrame);

        // 根 FrameLayout：承载 80% 覆盖面板（四周露出地图）
        rootFrame = new FrameLayout(this);
        rootFrame.setLayoutParams(new ViewGroup.LayoutParams(-1, -1));
        rootFrame.addView(root);
        int sw = getResources().getDisplayMetrics().widthPixels;
        int sh = getResources().getDisplayMetrics().heightPixels;

        // 数据面板：右侧弹出，约占屏幕 1/3，平时隐藏（编辑器菜单 → 数据面板 或点地图兵种/城市时打开）
        dataPanelBox = new LinearLayout(this);
        dataPanelBox.setOrientation(LinearLayout.VERTICAL);
        dataPanelBox.setVisibility(View.GONE);
        android.graphics.drawable.GradientDrawable dpBg = new android.graphics.drawable.GradientDrawable();
        dpBg.setColor(0xFF1b1b1f);
        dpBg.setStroke((int) (1.5f * density), 0xFF3a3a40);
        dataPanelBox.setBackground(dpBg);

        LinearLayout dpHdr = new LinearLayout(this);
        dpHdr.setOrientation(LinearLayout.HORIZONTAL);
        dpHdr.setGravity(Gravity.CENTER_VERTICAL);
        dpHdr.setBackgroundColor(Color.parseColor("#2f2f35"));
        dpHdr.setPadding(10 * density, 6 * density, 4 * density, 6 * density);
        TextView dpTitle = new TextView(this);
        dpTitle.setText("数据面板");
        dpTitle.setTextSize(13);
        dpTitle.setTextColor(0xFFe5e7eb);
        dpTitle.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        dpHdr.addView(dpTitle);
        final TextView dpClose = new TextView(this);
        dpClose.setText("✕");
        dpClose.setTextSize(14);
        dpClose.setTextColor(0xFF9ca3af);
        dpClose.setGravity(Gravity.CENTER);
        dpClose.setPadding(10 * density, 4 * density, 10 * density, 4 * density);
        dpClose.setOnClickListener(v -> closeDataPanel());
        dpHdr.addView(dpClose);
        dataPanelBox.addView(dpHdr);

        // 军队 / 城市 / 省区 / 地雷：相邻文字按钮，选中用亮字+下划线，不靠颜色区分
        LinearLayout tabsRow = new LinearLayout(this);
        tabsRow.setOrientation(LinearLayout.HORIZONTAL);
        tabsRow.setBackgroundColor(Color.parseColor("#232329"));
        tabsRow.setPadding(4 * density, 2 * density, 4 * density, 0);
        dataPanelBox.addView(tabsRow);
        LinearLayout armyTab = new LinearLayout(this);
        armyTab.setOrientation(LinearLayout.VERTICAL);
        armyTab.setGravity(Gravity.CENTER_HORIZONTAL);
        armyTab.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        armyTab.setPadding(0, 8 * density, 0, 2 * density);
        dataArmyTabTv = new TextView(this);
        dataArmyTabTv.setText("军队");
        dataArmyTabTv.setTextSize(13);
        dataArmyTabTv.setTextColor(0xFFe5e7eb);
        dataArmyTabTv.setGravity(Gravity.CENTER);
        armyTab.addView(dataArmyTabTv);
        dataArmyTabLine = new View(this);
        dataArmyTabLine.setLayoutParams(new LinearLayout.LayoutParams(-1, 3 * density));
        dataArmyTabLine.setBackgroundColor(0xFF6f9bff);
        armyTab.addView(dataArmyTabLine);
        armyTab.setOnClickListener(v -> switchDataTab("army"));
        tabsRow.addView(armyTab);
        LinearLayout cityTab = new LinearLayout(this);
        cityTab.setOrientation(LinearLayout.VERTICAL);
        cityTab.setGravity(Gravity.CENTER_HORIZONTAL);
        cityTab.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        cityTab.setPadding(0, 8 * density, 0, 2 * density);
        dataCityTabTv = new TextView(this);
        dataCityTabTv.setText("城市");
        dataCityTabTv.setTextSize(13);
        dataCityTabTv.setTextColor(0xFF9ca3af);
        dataCityTabTv.setGravity(Gravity.CENTER);
        cityTab.addView(dataCityTabTv);
        dataCityTabLine = new View(this);
        dataCityTabLine.setLayoutParams(new LinearLayout.LayoutParams(-1, 3 * density));
        dataCityTabLine.setBackgroundColor(0x00000000);
        cityTab.addView(dataCityTabLine);
        cityTab.setOnClickListener(v -> switchDataTab("city"));
        tabsRow.addView(cityTab);
        LinearLayout provinceTab = new LinearLayout(this);
        provinceTab.setOrientation(LinearLayout.VERTICAL);
        provinceTab.setGravity(Gravity.CENTER_HORIZONTAL);
        provinceTab.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        provinceTab.setPadding(0, 8 * density, 0, 2 * density);
        dataProvinceTabTv = new TextView(this);
        dataProvinceTabTv.setText("省区");
        dataProvinceTabTv.setTextSize(13);
        dataProvinceTabTv.setTextColor(0xFF9ca3af);
        dataProvinceTabTv.setGravity(Gravity.CENTER);
        provinceTab.addView(dataProvinceTabTv);
        dataProvinceTabLine = new View(this);
        dataProvinceTabLine.setLayoutParams(new LinearLayout.LayoutParams(-1, 3 * density));
        dataProvinceTabLine.setBackgroundColor(0x00000000);
        provinceTab.addView(dataProvinceTabLine);
        provinceTab.setOnClickListener(v -> switchDataTab("province"));
        tabsRow.addView(provinceTab);
        LinearLayout mineTab = new LinearLayout(this);
        mineTab.setOrientation(LinearLayout.VERTICAL);
        mineTab.setGravity(Gravity.CENTER_HORIZONTAL);
        mineTab.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        mineTab.setPadding(0, 8 * density, 0, 2 * density);
        dataMineTabTv = new TextView(this);
        dataMineTabTv.setText("地雷");
        dataMineTabTv.setTextSize(13);
        dataMineTabTv.setTextColor(0xFF9ca3af);
        dataMineTabTv.setGravity(Gravity.CENTER);
        mineTab.addView(dataMineTabTv);
        dataMineTabLine = new View(this);
        dataMineTabLine.setLayoutParams(new LinearLayout.LayoutParams(-1, 3 * density));
        dataMineTabLine.setBackgroundColor(0x00000000);
        mineTab.addView(dataMineTabLine);
        mineTab.setOnClickListener(v -> switchDataTab("mine"));
        tabsRow.addView(mineTab);

        // 内容区：军队 / 城市 / 省区 / 地雷 四页，二选一显示，内容只在数据面板范围内
        FrameLayout dpBody = new FrameLayout(this);
        dataArmySv = new ScrollView(this);
        dataArmySv.setBackgroundColor(Color.parseColor("#1b1b1f"));
        dataArmySv.addView(armyEditorArea);
        dpBody.addView(dataArmySv);
        dataCitySv = new ScrollView(this);
        dataCitySv.setBackgroundColor(Color.parseColor("#1b1b1f"));
        dataCitySv.setVisibility(View.GONE);
        dataCityContent = new LinearLayout(this);
        dataCityContent.setOrientation(LinearLayout.VERTICAL);
        dataCityContent.setPadding(10 * density, 8 * density, 10 * density, 8 * density);
        dataCitySv.addView(dataCityContent);
        dpBody.addView(dataCitySv);
        dataProvinceSv = new ScrollView(this);
        dataProvinceSv.setBackgroundColor(Color.parseColor("#1b1b1f"));
        dataProvinceSv.setVisibility(View.GONE);
        if (provinceScroll != null) {
            provinceScroll.setVisibility(View.VISIBLE);
            dataProvinceSv.addView(provinceScroll);
        }
        dpBody.addView(dataProvinceSv);
        dataMineSv = new ScrollView(this);
        dataMineSv.setBackgroundColor(Color.parseColor("#1b1b1f"));
        dataMineSv.setVisibility(View.GONE);
        dataMineContent = new LinearLayout(this);
        dataMineContent.setOrientation(LinearLayout.VERTICAL);
        dataMineContent.setPadding(10 * density, 8 * density, 10 * density, 8 * density);
        dataMineSv.addView(dataMineContent);
        dpBody.addView(dataMineSv);
        dataPanelBox.addView(dpBody, new LinearLayout.LayoutParams(-1, 0, 1));

        int dataW = Math.max(140 * density, sw / 3);
        FrameLayout.LayoutParams dpLp = new FrameLayout.LayoutParams(dataW, -1,
                Gravity.RIGHT | Gravity.TOP);
        bodyFrame.addView(dataPanelBox, dpLp);

        legionOverlay = new FrameLayout(this);
        legionOverlay.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));
        legionOverlay.setVisibility(View.GONE);
        legionPanel = new LinearLayout(this);
        legionPanel.setOrientation(LinearLayout.VERTICAL);
        legionPanel.setBackground(gradientBorderBg(14, 3, 0xFF16213E,
                new int[]{0xFF3B82F6, 0xFF22D3EE}));
        legionPanel.setPadding(10 * density, 8 * density, 10 * density, 8 * density);
        legionOverlay.addView(legionPanel,
                new FrameLayout.LayoutParams((int) (sw * 0.8), (int) (sh * 0.8), Gravity.CENTER));
        rootFrame.addView(legionOverlay);

        legionDetailOverlay = new FrameLayout(this);
        legionDetailOverlay.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));
        legionDetailOverlay.setVisibility(View.GONE);
        legionDetailPanel = new LinearLayout(this);
        legionDetailPanel.setOrientation(LinearLayout.VERTICAL);
        legionDetailPanel.setBackground(gradientBorderBg(14, 3, 0xFF16213E,
                new int[]{0xFF3B82F6, 0xFF22D3EE}));
        legionDetailPanel.setPadding(10 * density, 8 * density, 10 * density, 8 * density);
        legionDetailOverlay.addView(legionDetailPanel,
                new FrameLayout.LayoutParams((int) (sw * 0.8), (int) (sh * 0.8), Gravity.CENTER));
        rootFrame.addView(legionDetailOverlay);

        buildingOverlay = new FrameLayout(this);
        buildingOverlay.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));
        buildingOverlay.setVisibility(View.GONE);
        buildingPanel = new LinearLayout(this);
        buildingPanel.setOrientation(LinearLayout.VERTICAL);
        buildingPanel.setBackground(gradientBorderBg(14, 3, 0xFF16213E,
                new int[]{0xFF3B82F6, 0xFF22D3EE}));
        buildingPanel.setPadding(10 * density, 8 * density, 10 * density, 8 * density);
        buildingOverlay.addView(buildingPanel,
                new FrameLayout.LayoutParams((int) (sw * 0.8), (int) (sh * 0.8), Gravity.CENTER));
        rootFrame.addView(buildingOverlay);

        buildingDetailOverlay = new FrameLayout(this);
        buildingDetailOverlay.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));
        buildingDetailOverlay.setVisibility(View.GONE);
        buildingDetailPanel = new LinearLayout(this);
        buildingDetailPanel.setOrientation(LinearLayout.VERTICAL);
        buildingDetailPanel.setBackground(gradientBorderBg(14, 3, 0xFF16213E,
                new int[]{0xFF3B82F6, 0xFF22D3EE}));
        buildingDetailPanel.setPadding(10 * density, 8 * density, 10 * density, 8 * density);
        buildingDetailOverlay.addView(buildingDetailPanel,
                new FrameLayout.LayoutParams((int) (sw * 0.8), (int) (sh * 0.8), Gravity.CENTER));
        rootFrame.addView(buildingDetailOverlay);

        btlOverlay = new FrameLayout(this);
        btlOverlay.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));
        btlOverlay.setVisibility(View.GONE);
        btlPanel = new LinearLayout(this);
        btlPanel.setOrientation(LinearLayout.VERTICAL);
        btlPanel.setBackground(gradientBorderBg(14, 3, 0xFF16213E,
                new int[]{0xFF3B82F6, 0xFF22D3EE}));
        btlPanel.setPadding(10 * density, 8 * density, 10 * density, 8 * density);
        btlOverlay.addView(btlPanel,
                new FrameLayout.LayoutParams((int) (sw * 0.9), (int) (sh * 0.85), Gravity.CENTER));
        rootFrame.addView(btlOverlay);

        btlEventListOverlay = new FrameLayout(this);
        btlEventListOverlay.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));
        btlEventListOverlay.setVisibility(View.GONE);
        btlEventListPanel = new LinearLayout(this);
        btlEventListPanel.setOrientation(LinearLayout.VERTICAL);
        btlEventListPanel.setBackground(gradientBorderBg(14, 3, 0xFF16213E,
                new int[]{0xFF3B82F6, 0xFF22D3EE}));
        btlEventListPanel.setPadding(10 * density, 8 * density, 10 * density, 8 * density);
        btlEventListOverlay.addView(btlEventListPanel,
                new FrameLayout.LayoutParams((int) (sw * 0.8), (int) (sh * 0.8), Gravity.CENTER));
        rootFrame.addView(btlEventListOverlay);

        btlEventDetailOverlay = new FrameLayout(this);
        btlEventDetailOverlay.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));
        btlEventDetailOverlay.setVisibility(View.GONE);
        btlEventDetailPanel = new LinearLayout(this);
        btlEventDetailPanel.setOrientation(LinearLayout.VERTICAL);
        btlEventDetailPanel.setBackground(gradientBorderBg(14, 3, 0xFF16213E,
                new int[]{0xFF3B82F6, 0xFF22D3EE}));
        btlEventDetailPanel.setPadding(10 * density, 8 * density, 10 * density, 8 * density);
        btlEventDetailOverlay.addView(btlEventDetailPanel,
                new FrameLayout.LayoutParams((int) (sw * 0.8), (int) (sh * 0.8), Gravity.CENTER));
        rootFrame.addView(btlEventDetailOverlay);

        // FPS / 设备 / 版本号：最后加入 rootFrame，永远在最顶层，不被覆盖
        rootFrame.addView(infoPanel, infoLp);

        setContentView(rootFrame);
        buildHomeOverlay();
    }

    /** 首页：紫色渐变背景 + 4 个居中按钮（上下间距 35px）。 */
    private void buildHomeOverlay() {
        homeOverlay = new FrameLayout(this);
        homeOverlay.setLayoutParams(new ViewGroup.LayoutParams(-1, -1));
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF2B1055, 0xFF7597DE});
        homeOverlay.setBackground(gd);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams colLp = new FrameLayout.LayoutParams(-1, -1);
        homeOverlay.addView(col, colLp);

        String[] labels = {"打开战役 stage", "打开征服", "新建战役", "打开地图", "打开 APK"};
        int density = (int) getResources().getDisplayMetrics().density;
        for (int i = 0; i < labels.length; i++) {
            final int mode = (i == 0) ? 0 : (i == 1) ? 1 : (i == 2) ? 3 : (i == 3) ? 2 : 4;
            Button btn = new Button(this);
            btn.setText(labels[i]);
            btn.setTextSize(14);
            btn.setTextColor(Color.WHITE);
            btn.setAllCaps(false);
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setColor(0xCC1E3A8A);
            bg.setCornerRadius(18);
            bg.setStroke(2, 0x66FFFFFF);
            btn.setBackground(bg);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    (int) (200 * density), (int) (46 * density));
            if (i > 0) lp.topMargin = 35; // 上下间距 35px
            btn.setLayoutParams(lp);
            final int m = mode;
            btn.setOnClickListener(v -> enterHomeMode(m));
            col.addView(btn);
        }
        TextView title = new TextView(this);
        title.setText("Terrain Editor");
        title.setTextSize(22);
        title.setTextColor(Color.WHITE);
        title.setGravity(Gravity.CENTER);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setPadding(0, 0, 0, 40);
        col.addView(title, 0);

        // 首页左侧：BTL 地图库（在线下载 / 上传分享）
        Button mapLibBtn = new Button(this);
        mapLibBtn.setText("BTL地图库");
        mapLibBtn.setTextSize(13);
        mapLibBtn.setTextColor(Color.WHITE);
        mapLibBtn.setAllCaps(false);
        android.graphics.drawable.GradientDrawable mlbg = new android.graphics.drawable.GradientDrawable();
        mlbg.setColor(0xCC1E3A8A);
        mlbg.setCornerRadius(18);
        mlbg.setStroke(2, 0x66FFFFFF);
        mapLibBtn.setBackground(mlbg);
        FrameLayout.LayoutParams mlp = new FrameLayout.LayoutParams(
                (int) (190 * density), (int) (46 * density),
                Gravity.LEFT | Gravity.CENTER_VERTICAL);
        mlp.leftMargin = 14 * density;
        mapLibBtn.setLayoutParams(mlp);
        mapLibBtn.setOnClickListener(v -> showMapLibraryDialog());
        homeOverlay.addView(mapLibBtn);

        rootFrame.addView(homeOverlay);
    }

    /** 首页按钮：0=打开战役 1=打开征服 2=打开地图 3=新建战役 */
    private void enterHomeMode(int mode) {
        editorMode = mode;
        // 先选文件/建图，读取成功后再进入编辑器；取消则留在首页
        if (mode == 3) {
            newBtlMap();
        } else if (mode == 4) {
            openApkFile();
        } else {
            openFile();
        }
    }

    /** 打开 APK/压缩包：列出里面的 .btl/.bin，点选后直接加载进编辑器（wc4Etest 同款功能）。 */
    private void openApkFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, REQUEST_APK);
    }

    /** 文件读取成功（或新建成功）后进入编辑器。 */
    private void enterEditorAfterLoad() {
        if (homeOverlay != null) homeOverlay.setVisibility(View.GONE);
        provinceTakeMode = false;
        addingMine = false;
        applyEditorMode();
    }

    /** 根据首页模式调整编辑器界面（删除/隐藏按钮）。 */
    private void applyEditorMode() {
        // 顶栏、左侧面板、FPS/设备信息、群文字：全局显示（所有模式）
        if (topBarView != null) topBarView.setVisibility(View.VISIBLE);
        if (leftPanelView != null) leftPanelView.setVisibility(View.VISIBLE);
        if (infoPanelView != null) infoPanelView.setVisibility(View.VISIBLE);
        if (groupTextView != null) groupTextView.setVisibility(View.VISIBLE);
        if (cropOverlay != null) cropOverlay.setVisibility(View.GONE);
        // “显示省区规划”开关：保存按钮下方，全局显示
        if (provinceShowBtn != null) provinceShowBtn.setVisibility(View.VISIBLE);
        if (editorMode == 2) {
            // 打开地图：完整显示，包含 打开/保存/音乐/音效 与 新建BTL
            if (floatOpenBtn != null) floatOpenBtn.setVisibility(View.VISIBLE);
            if (floatMusicBtn != null) floatMusicBtn.setVisibility(View.VISIBLE);
            if (floatSfxBtn != null) floatSfxBtn.setVisibility(View.VISIBLE);
            if (soundLabelView != null) soundLabelView.setVisibility(View.VISIBLE);
            if (leftDividerView != null) leftDividerView.setVisibility(View.VISIBLE);
            if (fileLabelView != null) fileLabelView.setVisibility(View.VISIBLE);
            if (floatSaveBtn != null) floatSaveBtn.setVisibility(View.VISIBLE);
            if (topNewBtlBtn != null) topNewBtlBtn.setVisibility(View.VISIBLE);
        } else {
            // 战役/征服/新建：删除“打开”与两个声音按钮，保留“保存”；“新建BTL”删除
            if (floatOpenBtn != null) floatOpenBtn.setVisibility(View.GONE);
            if (floatMusicBtn != null) floatMusicBtn.setVisibility(View.GONE);
            if (floatSfxBtn != null) floatSfxBtn.setVisibility(View.GONE);
            if (soundLabelView != null) soundLabelView.setVisibility(View.GONE);
            if (leftDividerView != null) leftDividerView.setVisibility(View.GONE);
            if (fileLabelView != null) fileLabelView.setVisibility(View.VISIBLE);
            if (floatSaveBtn != null) floatSaveBtn.setVisibility(View.VISIBLE);
            if (topNewBtlBtn != null) topNewBtlBtn.setVisibility(View.GONE);
        }
        // 右侧属性面板：所有模式都显示（征服也可添加兵种/城市/笔刷）
        if (panelFrameView != null) {
            panelFrameView.setVisibility(View.VISIBLE);
        }
        if (rightPanel != null) {
            rightPanel.setVisibility(View.VISIBLE);
        }
    }

    /** 左上角 FPS 计数：每秒统计一次 Choreographer 帧回调次数。 */
    private void startFpsCounter(final TextView fpsView) {
        final long[] lastTime = {0};
        final int[] frames = {0};
        android.view.Choreographer.getInstance().postFrameCallback(new android.view.Choreographer.FrameCallback() {
            @Override
            public void doFrame(long frameTimeNanos) {
                if (lastTime[0] == 0) {
                    lastTime[0] = frameTimeNanos;
                } else {
                    frames[0]++;
                    long elapsedMs = (frameTimeNanos - lastTime[0]) / 1_000_000L;
                    if (elapsedMs >= 1000) {
                        float fps = frames[0] * 1000f / elapsedMs;
                        fpsView.setText(String.format(java.util.Locale.US, "FPS: %.1f", fps));
                        frames[0] = 0;
                        lastTime[0] = frameTimeNanos;
                    }
                }
                android.view.Choreographer.getInstance().postFrameCallback(this);
            }
        });
    }

    /**
     * 右面板：frame 是可移动/可缩放的外层容器（左竖条缩放 + 内容），
     * content 是内容面板（顶部把手放在它最上面）。
     */
    private void setupDraggablePanel(final LinearLayout frame, final LinearLayout content,
                                     final int screenWidthPx, final int density) {
        // 顶部移动把手（放进内容面板顶部）
        LinearLayout handle = new LinearLayout(this);
        handle.setOrientation(LinearLayout.HORIZONTAL);
        handle.setGravity(Gravity.CENTER_VERTICAL);
        handle.setBackgroundColor(Color.parseColor("#3a3a40"));
        TextView grip = new TextView(this);
        grip.setText("☰");
        grip.setTextColor(0xFFe5e7eb);
        grip.setTextSize(12);
        grip.setPadding(8 * density, 8 * density, 8 * density, 8 * density);
        grip.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        handle.addView(grip);
        final TextView collapseTv = new TextView(this);
        collapseTv.setText("▾");
        collapseTv.setTextColor(0xFFe5e7eb);
        collapseTv.setTextSize(12);
        collapseTv.setPadding(12 * density, 8 * density, 12 * density, 8 * density);
        handle.addView(collapseTv);
        content.addView(handle, 0);
        final boolean[] collapsed = {false};
        collapseTv.setOnClickListener(v -> {
            collapsed[0] = !collapsed[0];
            for (int i = 1; i < content.getChildCount(); i++) {
                content.getChildAt(i).setVisibility(collapsed[0] ? View.GONE : View.VISIBLE);
            }
            collapseTv.setText(collapsed[0] ? "▸" : "▾");
            grip.setText(collapsed[0] ? "☰ ▸" : "☰");
        });

        // 左侧竖条缩放把手：按住 ⋮ 向右拖=变窄（向右缩小），向左拖=变宽
        LinearLayout vStrip = new LinearLayout(this);
        vStrip.setOrientation(LinearLayout.VERTICAL);
        vStrip.setGravity(Gravity.CENTER);
        vStrip.setBackgroundColor(Color.parseColor("#1e5fa8"));
        TextView stripTv = new TextView(this);
        stripTv.setText("⋮⋮");
        stripTv.setTextColor(Color.WHITE);
        stripTv.setTextSize(12);
        vStrip.addView(stripTv);
        frame.addView(vStrip, new LinearLayout.LayoutParams(18 * density, -1));

        final int[] rs = new int[2]; // 起始rawX / 起始宽
        vStrip.setOnTouchListener((v, ev) -> {
            switch (ev.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    rs[0] = (int) ev.getRawX();
                    rs[1] = ((FrameLayout.LayoutParams) frame.getLayoutParams()).width;
                    return true;
                case android.view.MotionEvent.ACTION_MOVE: {
                    int delta = (int) ev.getRawX() - rs[0];
                    int minW = 120 * density;
                    int maxW = screenWidthPx - 2 * 8 * density - 40 * density;
                    FrameLayout.LayoutParams lp =
                            (FrameLayout.LayoutParams) frame.getLayoutParams();
                    lp.width = Math.max(minW, Math.min(rs[1] - delta, maxW));
                    frame.setLayoutParams(lp);
                    return true;
                }
            }
            return false;
        });

        // down[0]=起始rawX, down[1]=起始translationX, down[2]=面板宽度
        final int[] down = new int[3];
        handle.setOnTouchListener((v, ev) -> {
            switch (ev.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    // 折叠按钮区域放行点击，避免被拖动监听吞掉
                    if (collapseTv.getVisibility() == View.VISIBLE) {
                        android.graphics.Rect r = new android.graphics.Rect();
                        collapseTv.getHitRect(r);
                        if (r.contains((int) ev.getX(), (int) ev.getY())) {
                            return false;
                        }
                    }
                    down[0] = (int) ev.getRawX();
                    down[1] = (int) frame.getTranslationX();
                    down[2] = frame.getWidth();
                    if (down[2] <= 0) {
                        down[2] = ((FrameLayout.LayoutParams) frame.getLayoutParams()).width;
                    }
                    return true;
                case android.view.MotionEvent.ACTION_MOVE: {
                    int margin = 8 * density;
                    int left = frame.getLeft(); // 布局位置（不含平移）
                    float tx = down[1] + (ev.getRawX() - down[0]);
                    float minTx = margin - left;
                    float maxTx = (screenWidthPx - down[2] - margin) - left;
                    frame.setTranslationX(Math.max(minTx, Math.min(tx, maxTx)));
                    return true;
                }
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL: {
                    int margin = 8 * density;
                    float center = frame.getLeft() + frame.getTranslationX() + down[2] / 2f;
                    FrameLayout.LayoutParams lp =
                            (FrameLayout.LayoutParams) frame.getLayoutParams();
                    if (center < screenWidthPx / 2f) {
                        lp.gravity = Gravity.LEFT | Gravity.TOP;
                        lp.leftMargin = margin;
                        lp.rightMargin = 0;
                    } else {
                        lp.gravity = Gravity.RIGHT | Gravity.TOP;
                        lp.rightMargin = margin;
                        lp.leftMargin = 0;
                    }
                    frame.setTranslationX(0);
                    frame.setLayoutParams(lp);
                    return true;
                }
            }
            return false;
        });
    }

    private View createTopBar() {
        HorizontalScrollView toolbarScroll = new HorizontalScrollView(this);
        toolbarScroll.setLayoutParams(new LinearLayout.LayoutParams(-1, 52));
        toolbarScroll.setFillViewport(true);
        toolbarScroll.setHorizontalScrollBarEnabled(false);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setLayoutParams(new HorizontalScrollView.LayoutParams(-2, 52));
        bar.setBackgroundColor(Color.parseColor("#1a1a3e"));
        bar.setPadding(8, 0, 8, 0);
        bar.setGravity(Gravity.CENTER_VERTICAL);

        // 撤销按钮；音乐/音效与打开/保存已移到左侧浮动按钮
        undoBtn = makeTopBtn("撤销");
        undoBtn.setOnClickListener(v -> {
            if (mapData==null||!history.canUndo()) return;
            String label = history.undo(mapData);
            hexMapView.refresh();
            updateInfo();
            updateBtnState();
            if (label != null) {
                Toast.makeText(this, "已撤销：" + label, Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "地图结构已改变，无法撤销", Toast.LENGTH_SHORT).show();
            }
        });
        bar.addView(undoBtn); bar.addView(spacer(4));

        redoBtn = makeTopBtn("重做");
        redoBtn.setOnClickListener(v -> {
            if (mapData == null || !history.canRedo()) return;
            history.redo(mapData);
            hexMapView.refresh();
            updateInfo();
            updateBtnState();
            Toast.makeText(this, "已重做", Toast.LENGTH_SHORT).show();
        });
        bar.addView(redoBtn); bar.addView(spacer(4));

        // 工具栏可横向滑动，手机窄屏时所有操作均可访问。
        String[] labels = {"新建BTL","编辑器","地图","视图"};
        for (int i = 0; i < labels.length; i++) {
            final int a = i;
            Button btn = makeTopBtn(labels[i]);
            if (a == 0) topNewBtlBtn = btn;
            btn.setOnClickListener(v -> {
                if (a == 1) showEditorMenu(btn);
                else if (a == 2) showMapPopup(btn);
                else if (a == 3) showViewPopup(btn);
                else topAction(a);
            });
            bar.addView(btn);
            if (i < labels.length - 1) bar.addView(spacer(4));
        }

        toolbarScroll.addView(bar);
        return toolbarScroll;
    }

    private Button makeTopBtn(String text) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextSize(12);
        btn.setTextColor(Color.WHITE);
        int density = (int) getResources().getDisplayMetrics().density;
        btn.setLayoutParams(new LinearLayout.LayoutParams(-2, 36 * density));
        btn.setPadding(10 * density, 0, 10 * density, 0);
        btn.setGravity(Gravity.CENTER);
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setColor(Color.parseColor("#2f3d75"));
        gd.setCornerRadius(8 * density);
        btn.setBackground(gd);
        return btn;
    }

    private View spacer(int w) {
        View sp = new View(this);
        sp.setLayoutParams(new LinearLayout.LayoutParams(w, 1));
        return sp;
    }

    private void topAction(int a) {
        switch (a) {
            case 0: newBtlMap(); break;
            case 4: toggleViewOnly(); break;
        }
    }

    /** 编辑器下拉菜单：btl数据 + 数据面板 + 属性面板开关。 */
    private void showEditorMenu(View anchor) {
        boolean panelVisible = rightPanel != null
                && rightPanel.getVisibility() == View.VISIBLE;
        java.util.List<Runnable> acts = new java.util.ArrayList<>();
        acts.add(() -> showBtlDataOverlay());
        acts.add(() -> openDataPanel());
        acts.add(() -> showTailSectionsDialog());
        acts.add(() -> {
            if (rightPanel == null) return;
            rightPanel.setVisibility(panelVisible ? View.GONE : View.VISIBLE);
        });
        showDropdownMenu(anchor, new String[]{"btl数据", "数据面板", "数据段列表…",
                (panelVisible ? "隐藏属性面板" : "显示属性面板")}, acts);
    }

    private void showMapPopup(View anchor) {
        if (mapData == null) {
            Toast.makeText(this, "请先加载地图", Toast.LENGTH_SHORT).show();
            return;
        }
        java.util.List<Runnable> acts = new java.util.ArrayList<>();
        acts.add(() -> validateAndFixDialog());
        acts.add(() -> showExpandDirectionDialog());
        acts.add(() -> startCropSelect());
        acts.add(() -> randomizeTerrainDialog());
        acts.add(() -> randomizeArmiesDialog());
        acts.add(() -> showBuildingListOverlay());
        showDropdownMenu(anchor, new String[]{"校验并修复…", "扩展地图…", "截取地图…", "随机地形…",
                "随机兵力…", "城市列表…"}, acts);
    }

    private void showViewPopup(View anchor) {
        boolean panelVisible = rightPanel != null
                && rightPanel.getVisibility() == View.VISIBLE;
        java.util.List<Runnable> acts = new java.util.ArrayList<>();
        acts.add(() -> showLegionsOverlay());
        acts.add(() -> toggleProvinceView());
        acts.add(() -> showDisplaySettingsDialog());
        acts.add(() -> rightPanel.setVisibility(panelVisible ? View.GONE : View.VISIBLE));
        acts.add(() -> importOverlay());
        acts.add(() -> importGuideImage());
        acts.add(() -> toggleOverlay());
        showDropdownMenu(anchor, new String[]{
                "军团列表",
                (provinceViewOn ? "✔ 省规划视图" : "省规划视图"),
                "显示设置…",
                (panelVisible ? "隐藏工具面板" : "显示工具面板"),
                "导入底图",
                "导入图填",
                (hexMapView != null && hexMapView.isOverlayVisible() ? "关闭遮罩" : "开启遮罩")
        }, acts);
    }

    /** 自定义深色下拉菜单（与 App 主题一致）。 */
    private void showDropdownMenu(View anchor, String[] items, java.util.List<Runnable> actions) {
        int density = (int) getResources().getDisplayMetrics().density;
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(gradientBorderBg(10, 2, 0xF21A1A3E,
                new int[]{0xFF3B82F6, 0xFF8B5CF6}));
        panel.setPadding(4 * density, 4 * density, 4 * density, 4 * density);

        final PopupWindow[] pw = new PopupWindow[1];
        for (int i = 0; i < items.length; i++) {
            final int idx = i;
            TextView tv = new TextView(this);
            tv.setText(items[i]);
            tv.setTextSize(13);
            tv.setTextColor(Color.WHITE);
            tv.setPadding(14 * density, 10 * density, 14 * density, 10 * density);
            tv.setGravity(Gravity.LEFT);
            tv.setClickable(true);
            tv.setOnClickListener(v -> {
                if (pw[0] != null) pw[0].dismiss();
                if (idx < actions.size()) actions.get(idx).run();
            });
            panel.addView(tv);
            if (i < items.length - 1) {
                View divider = new View(this);
                divider.setBackgroundColor(0x33FFFFFF);
                divider.setLayoutParams(new LinearLayout.LayoutParams(-1, 1));
                panel.addView(divider);
            }
        }
        int w = Math.max(150 * density, anchor.getWidth());
        pw[0] = new PopupWindow(panel, w, -2, true);
        pw[0].setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
        pw[0].showAsDropDown(anchor, 0, 2 * density);
    }

    /** 打开右侧数据面板：约占屏幕 1/3，原属性面板临时变灰。 */
    private void openDataPanel() {
        if (dataPanelBox == null) return;
        dataPanelBox.setVisibility(View.VISIBLE);
        dataPanelOpen = true;
        // 原 imgui 面板临时隐藏变灰（关闭后恢复）
        if (panelFrameView != null) panelFrameView.setAlpha(0.35f);
        switchDataTab(dataTab);
    }

    /** 关闭数据面板，恢复原属性面板。 */
    private void closeDataPanel() {
        dataPanelOpen = false;
        if (dataPanelBox != null) dataPanelBox.setVisibility(View.GONE);
        if (panelFrameView != null) panelFrameView.setAlpha(1f);
    }

    /** 数据面板顶部“军队/城市/省区/地雷”文字按钮：四选一切换显示。 */
    private void switchDataTab(String tab) {
        dataTab = tab;
        boolean army = "army".equals(tab);
        boolean city = "city".equals(tab);
        boolean prov = "province".equals(tab);
        boolean mine = "mine".equals(tab);
        if (dataArmySv != null) dataArmySv.setVisibility(army ? View.VISIBLE : View.GONE);
        if (dataCitySv != null) dataCitySv.setVisibility(city ? View.VISIBLE : View.GONE);
        if (dataProvinceSv != null) dataProvinceSv.setVisibility(prov ? View.VISIBLE : View.GONE);
        if (dataMineSv != null) dataMineSv.setVisibility(mine ? View.VISIBLE : View.GONE);
        if (dataArmyTabTv != null) dataArmyTabTv.setTextColor(army ? 0xFFe5e7eb : 0xFF9ca3af);
        if (dataCityTabTv != null) dataCityTabTv.setTextColor(city ? 0xFFe5e7eb : 0xFF9ca3af);
        if (dataProvinceTabTv != null) dataProvinceTabTv.setTextColor(prov ? 0xFFe5e7eb : 0xFF9ca3af);
        if (dataMineTabTv != null) dataMineTabTv.setTextColor(mine ? 0xFFe5e7eb : 0xFF9ca3af);
        if (dataArmyTabLine != null) dataArmyTabLine.setBackgroundColor(army ? 0xFF6f9bff : 0x00000000);
        if (dataCityTabLine != null) dataCityTabLine.setBackgroundColor(city ? 0xFF6f9bff : 0x00000000);
        if (dataProvinceTabLine != null) dataProvinceTabLine.setBackgroundColor(prov ? 0xFF6f9bff : 0x00000000);
        if (dataMineTabLine != null) dataMineTabLine.setBackgroundColor(mine ? 0xFF6f9bff : 0x00000000);
        if (prov) {
            rebuildProvinceList();
            if (hexMapView != null) hexMapView.refresh();
        }
        if (mine) {
            rebuildMineEditor();
            if (hexMapView != null) hexMapView.refresh();
        }
    }

    private void toggleProvinceView() {
        if (mapData == null) {
            Toast.makeText(this, "请先加载地图", Toast.LENGTH_SHORT).show();
            return;
        }
        provinceViewOn = !provinceViewOn;
        hexMapView.setProvinceView(provinceViewOn);
        Toast.makeText(this, provinceViewOn ? "省规划视图：按省规划值染色" : "已恢复地形显示",
                Toast.LENGTH_SHORT).show();
    }

    /** 军团列表覆盖面板：80% 屏，四周空出，右上角关闭，军团竖排。 */
    private void showLegionsOverlay() {
        if (mapData == null || mapData.legions == null || mapData.legions.isEmpty()) {
            Toast.makeText(this, "请先加载 BTL 地图", Toast.LENGTH_SHORT).show();
            return;
        }
        final int density = (int) getResources().getDisplayMetrics().density;
        ensureFlagIcons();
        legionPanel.removeAllViews();
        legionPanel.addView(makeOverlayHeader("军团列表（点击查看并编辑）",
                () -> legionOverlay.setVisibility(View.GONE),
                () -> legionOverlay.setVisibility(View.GONE)));
        TextView addCountryBtn = new TextView(this);
        addCountryBtn.setText("＋ 新建国家（输入国家ID和名称）");
        addCountryBtn.setTextSize(15);
        addCountryBtn.setTextColor(Color.WHITE);
        addCountryBtn.setAllCaps(false);
        addCountryBtn.setClickable(true);
        addCountryBtn.setGravity(Gravity.CENTER);
        addCountryBtn.setBackgroundColor(Color.parseColor("#3a3a40"));
        addCountryBtn.setLayoutParams(new LinearLayout.LayoutParams(-1, 40));
        addCountryBtn.setOnClickListener(v -> showAddCountryDialog());
        legionPanel.addView(addCountryBtn);
        ScrollView sv = new ScrollView(this);
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < mapData.legions.size(); i++) {
            final MapData.Legion lg = mapData.legions.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(6, 10, 6, 10);
            row.setClickable(true);
            row.setBackgroundResource(android.R.drawable.edit_text);
            row.setBackgroundColor(0x00ffffff);
            row.setOnClickListener(v -> {
                legionOverlay.setVisibility(View.GONE);
                showLegionDetailOverlay(lg);
            });
            Bitmap flag = flagIcons != null ? flagIcons.get(lg.country) : null;
            if (flag != null) {
                ImageView flagIv = new ImageView(this);
                flagIv.setImageBitmap(flag);
                flagIv.setLayoutParams(new LinearLayout.LayoutParams(42 * density, 28 * density));
                row.addView(flagIv);
                View sp = new View(this);
                sp.setLayoutParams(new LinearLayout.LayoutParams(6, 1));
                row.addView(sp);
            }
            View colorBlock = new View(this);
            colorBlock.setLayoutParams(new LinearLayout.LayoutParams(30, 30));
            colorBlock.setBackgroundColor(lg.color);
            row.addView(colorBlock);
            TextView tv = new TextView(this);
            tv.setText("  军团" + (i + 1) + "：" + CountryData.name(lg.country)
                    + "（国家ID " + lg.country + "｜序号" + lg.seq + " 阵营" + lg.faction
                    + " 控制" + lg.control + "）");
            tv.setTextSize(15);
            tv.setTextColor(Color.WHITE);
            row.addView(tv);
            l.addView(row);
        }
        sv.addView(l);
        legionPanel.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        legionOverlay.setVisibility(View.VISIBLE);
    }

    /** 添加自定义国家：新 ID + 名称，添加后可在军团详情里把“国家”字段设为该 ID。 */
    private void showAddCountryDialog() {
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("添加国家");
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(40, 20, 40, 20);
        EditText idEt = new EditText(this);
        idEt.setHint("国家 ID（如 49）");
        idEt.setInputType(InputType.TYPE_CLASS_NUMBER);
        EditText nameEt = new EditText(this);
        nameEt.setHint("国家名称（如 新国家）");
        l.addView(idEt);
        l.addView(nameEt);
        b.setView(l);
        b.setPositiveButton("添加", (d, w) -> {
            try {
                int id = Integer.parseInt(idEt.getText().toString().trim());
                String name = nameEt.getText().toString().trim();
                if (id < 1 || name.isEmpty()) {
                    Toast.makeText(this, "国家 ID 和名称不能为空", Toast.LENGTH_SHORT).show();
                    return;
                }
                CountryData.addCountry(id, name);
                Toast.makeText(this, "已添加国家 " + id + "：" + name
                        + "（可在军团详情把“国家”字段设为该 ID）", Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                Toast.makeText(this, "国家 ID 必须是数字", Toast.LENGTH_SHORT).show();
            }
        });
        b.setNegativeButton("取消", null);
        b.show();
    }

    /** 军团详情覆盖面板：同款 80% 屏，可修改 300 字节全部字段。 */
    private void showLegionDetailOverlay(MapData.Legion lg) {
        legionDetailPanel.removeAllViews();
        legionDetailPanel.addView(makeOverlayHeader(
                CountryData.name(lg.country) + "（序号" + lg.seq + "）",
                () -> {
                    legionDetailOverlay.setVisibility(View.GONE);
                    showLegionsOverlay();
                },
                () -> legionDetailOverlay.setVisibility(View.GONE)));
        ScrollView sv = new ScrollView(this);
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        legionEds = new EditText[LEGION_FIELDS.length];
        for (int i = 0; i < LEGION_FIELDS.length; i++) {
            String fname = LEGION_FIELDS[i][0];
            String ftype = LEGION_FIELDS[i][1];
            int off = Integer.decode(LEGION_FIELDS[i][2]);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, 2, 0, 2);
            TextView label = new TextView(this);
            label.setText(fname);
            label.setTextSize(14);
            label.setTextColor(Color.WHITE);
            label.setTypeface(null, android.graphics.Typeface.BOLD);
            label.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
            row.addView(label);
            EditText et = new EditText(this);
            et.setInputType("f32".equals(ftype)
                    ? (InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL)
                    : InputType.TYPE_CLASS_NUMBER);
            et.setText(readLegionField(lg.raw, off, ftype));
            et.setTextColor(Color.WHITE);
            et.setTextSize(14);
            et.setBackgroundColor(Color.parseColor("#1e293b"));
            et.setLayoutParams(new LinearLayout.LayoutParams(140, -2));
            legionEds[i] = et;
            row.addView(et);
            l.addView(row);
        }
        sv.addView(l);
        legionDetailPanel.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        Button saveBtn = new Button(this);
        saveBtn.setText("保存修改");
        saveBtn.setTextSize(13);
        saveBtn.setTextColor(Color.WHITE);
        saveBtn.setBackgroundColor(Color.parseColor("#22c55e"));
        saveBtn.setOnClickListener(v -> {
            byte[] raw = lg.raw.clone();
            if (legionEds != null) {
                for (int i = 0; i < LEGION_FIELDS.length && i < legionEds.length; i++) {
                    try {
                        writeLegionField(raw, Integer.decode(LEGION_FIELDS[i][2]),
                                LEGION_FIELDS[i][1], legionEds[i].getText().toString());
                    } catch (Exception ignored) {
                    }
                }
            }
            try {
                FileParser.patchLegion(mapData, lg, raw);
                FileParser.refreshArmies(mapData);
                hexMapView.refresh();
                updateInfo();
                legionDetailOverlay.setVisibility(View.GONE);
                Toast.makeText(this, "军团已更新", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "保存失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
        legionDetailPanel.addView(saveBtn);
        legionDetailOverlay.setVisibility(View.VISIBLE);
    }

    /** 城市列表覆盖面板：80% 屏，列出所有城市/建筑，点击进入编辑。 */
    private void showBuildingListOverlay() {
        if (mapData == null || mapData.buildings == null || mapData.buildings.isEmpty()) {
            Toast.makeText(this, "当前地图没有城市/建筑", Toast.LENGTH_SHORT).show();
            return;
        }
        buildingPanel.removeAllViews();
        buildingPanel.addView(makeOverlayHeader("城市列表（点击编辑）",
                () -> buildingOverlay.setVisibility(View.GONE),
                () -> buildingOverlay.setVisibility(View.GONE)));
        ScrollView sv = new ScrollView(this);
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        for (final MapData.Building b : mapData.buildings) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(6, 10, 6, 10);
            row.setClickable(true);
            row.setOnClickListener(v -> {
                buildingOverlay.setVisibility(View.GONE);
                showBuildingOverlay(b);
            });
            TextView tv = new TextView(this);
            String hint = cityHint(b);
            tv.setText(buildingTypeName(b.type) + "  #" + b.index
                    + "  (" + b.x + "," + b.y + ")" + (hint.isEmpty() ? "" : "  " + hint));
            tv.setTextSize(14);
            tv.setTextColor(Color.WHITE);
            row.addView(tv);
            l.addView(row);
            View div = new View(this);
            div.setBackgroundColor(0x22FFFFFF);
            div.setLayoutParams(new LinearLayout.LayoutParams(-1, 1));
            l.addView(div);
        }
        sv.addView(l);
        buildingPanel.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        buildingOverlay.setVisibility(View.VISIBLE);
    }

    /** 城市详情编辑面板：32 字节记录全部字段可改。 */
    private void showBuildingOverlay(final MapData.Building b) {
        buildingDetailPanel.removeAllViews();
        buildingDetailPanel.addView(makeOverlayHeader(
                buildingTypeName(b.type) + "（" + b.x + "," + b.y + "）",
                () -> {
                    buildingDetailOverlay.setVisibility(View.GONE);
                    showBuildingListOverlay();
                },
                () -> buildingDetailOverlay.setVisibility(View.GONE)));
        ScrollView sv = new ScrollView(this);
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        buildingEds = new EditText[BUILDING_FIELDS.length];
        for (int i = 0; i < BUILDING_FIELDS.length; i++) {
            String fname = BUILDING_FIELDS[i][0];
            String ftype = BUILDING_FIELDS[i][1];
            int off = Integer.decode(BUILDING_FIELDS[i][2]);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, 2, 0, 2);
            TextView label = new TextView(this);
            label.setText(fname);
            label.setTextSize(14);
            label.setTextColor(Color.WHITE);
            label.setTypeface(null, android.graphics.Typeface.BOLD);
            label.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
            row.addView(label);
            EditText et = new EditText(this);
            et.setInputType(InputType.TYPE_CLASS_NUMBER);
            et.setText(readBuildingField(b.raw, off, ftype));
            et.setTextColor(Color.WHITE);
            et.setTextSize(14);
            et.setBackgroundColor(Color.parseColor("#1e293b"));
            et.setLayoutParams(new LinearLayout.LayoutParams(140, -2));
            buildingEds[i] = et;
            row.addView(et);
            l.addView(row);
        }
        sv.addView(l);
        buildingDetailPanel.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        Button saveBtn = new Button(this);
        saveBtn.setText("保存修改");
        saveBtn.setTextSize(13);
        saveBtn.setTextColor(Color.WHITE);
        saveBtn.setBackgroundColor(Color.parseColor("#22c55e"));
        saveBtn.setOnClickListener(v -> {
            byte[] raw = b.raw.clone();
            if (buildingEds != null) {
                for (int i = 0; i < BUILDING_FIELDS.length && i < buildingEds.length; i++) {
                    try {
                        writeBuildingField(raw, Integer.decode(BUILDING_FIELDS[i][2]),
                                BUILDING_FIELDS[i][1], buildingEds[i].getText().toString());
                    } catch (Exception ignored) {
                    }
                }
            }
            try {
                history.save(mapData, "修改城市");
                FileParser.patchBuilding(mapData, b, raw);
                hexMapView.refresh();
                updateInfo();
                buildingDetailOverlay.setVisibility(View.GONE);
                Toast.makeText(this, "城市已更新", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "保存失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
        buildingDetailPanel.addView(saveBtn);
        buildingDetailOverlay.setVisibility(View.VISIBLE);
    }

    private static String cityHint(MapData.Building b) {
        StringBuilder sb = new StringBuilder();
        int stronghold = b.raw[0xD] & 0xFF;
        if (stronghold == 1) sb.append("红圈 ");
        else if (stronghold == 2) sb.append("绿圈 ");
        if ((b.raw[0x18] & 0xFF) != 0) sb.append("工厂 ");
        if ((b.raw[0x19] & 0xFF) != 0) sb.append("科研 ");
        if ((b.raw[0x1A] & 0xFF) != 0) sb.append("补给 ");
        if ((b.raw[0x1B] & 0xFF) != 0) sb.append("机场 ");
        if ((b.raw[0x1C] & 0xFF) != 0) sb.append("导弹基地 ");
        if ((b.raw[0x1D] & 0xFF) != 0) sb.append("核工厂 ");
        return sb.toString().trim();
    }

    private static void writeBuildingField(byte[] raw, int off, String type, String text) {
        int v = Integer.parseInt(text.trim());
        switch (type) {
            case "u16":
                java.nio.ByteBuffer.wrap(raw).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        .putShort(off, (short) v);
                break;
            default:
                raw[off] = (byte) v;
        }
    }

    private static String readBuildingField(byte[] raw, int off, String type) {
        if (type.equals("u16")) {
            return String.valueOf(java.nio.ByteBuffer.wrap(raw).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    .getShort(off) & 0xFFFF);
        }
        return String.valueOf(raw[off]);
    }

    private String buildingTypeName(int type) {
        switch (type) {
            case 1: return "农场";
            case 11: return "小城市";
            case 12: return "中城市";
            case 13: return "大城市";
            case 14: return "大都市";
            case 15: case 16: case 17: case 18: case 19:
                return "首都" + (type - 14);
            case 21: return "炼油厂";
            case 22: return "大工厂";
            case 23: return "核电站";
            case 31: return "军港";
            case 41: return "机场";
            case 42: return "要塞";
            case 43: return "堡垒";
            case 44: return "据点";
            case 45: return "工厂";
            default: return "建筑" + type;
        }
    }

    /** 纯移动模式：隐藏工具面板，只允许拖动/缩放画面，点击不选中不编辑。 */
    private void toggleViewOnly() {
        viewOnlyMode = !viewOnlyMode;
        closeProvinceEdit();
        if (hexMapView != null) hexMapView.setViewOnly(viewOnlyMode);
        if (rightPanel != null) {
            rightPanel.setVisibility(viewOnlyMode ? View.GONE : View.VISIBLE);
        }
        Toast.makeText(this, viewOnlyMode
                        ? "纯移动模式：已隐藏工具面板，拖动只移动画面（再点一次退出）"
                        : "已退出纯移动模式",
                Toast.LENGTH_SHORT).show();
    }

    /** 下拉单选框（深色面板白字，可读性好）。 */
    private android.widget.Spinner makeSpinner(String[] labels, int selected) {
        android.widget.ArrayAdapter<String> ad = new android.widget.ArrayAdapter<String>(
                this, android.R.layout.simple_spinner_item, labels) {
            @Override
            public View getView(int position, View convertView, android.view.ViewGroup parent) {
                TextView tv = (TextView) super.getView(position, convertView, parent);
                tv.setTextColor(Color.WHITE);
                tv.setTextSize(14);
                return tv;
            }
        };
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        android.widget.Spinner sp = new android.widget.Spinner(this);
        sp.setAdapter(ad);
        if (selected >= 0 && selected < labels.length) sp.setSelection(selected);
        sp.setBackgroundColor(Color.parseColor("#1e293b"));
        return sp;
    }

    private static int indexOfVal(int[] vals, int v) {
        for (int i = 0; i < vals.length; i++) if (vals[i] == v) return i;
        return -1;
    }

    private EditText addNumRow(LinearLayout parent, String label, int value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, 2, 0, 2);
        TextView lb = new TextView(this);
        lb.setText(label);
        lb.setTextSize(14);
        lb.setTextColor(Color.WHITE);
        lb.setTypeface(null, android.graphics.Typeface.BOLD);
        lb.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(lb);
        EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setText(String.valueOf(value));
        et.setTextColor(Color.WHITE);
        et.setTextSize(14);
        et.setBackgroundColor(Color.parseColor("#1e293b"));
        et.setLayoutParams(new LinearLayout.LayoutParams(140, -2));
        row.addView(et);
        parent.addView(row);
        return et;
    }

    private void addSpinnerRow(LinearLayout parent, String label, android.widget.Spinner sp) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, 2, 0, 2);
        TextView lb = new TextView(this);
        lb.setText(label);
        lb.setTextSize(14);
        lb.setTextColor(Color.WHITE);
        lb.setTypeface(null, android.graphics.Typeface.BOLD);
        lb.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(lb);
        sp.setLayoutParams(new LinearLayout.LayoutParams(140, -2));
        row.addView(sp);
        parent.addView(row);
    }

    /** BTL 主数据 + 事件编辑：胜利条件/战役时代/事件触发条件/触发事件均为下拉单选框。 */
    private void showBtlDataOverlay() {
        if (mapData == null || mapData.btlOriginalData == null) {
            Toast.makeText(this, "请先加载 BTL 地图", Toast.LENGTH_SHORT).show();
            return;
        }
        final int density = (int) getResources().getDisplayMetrics().density;
        final byte[] btl = mapData.btlOriginalData;
        final FileParser.BtlHeaderInfo h = FileParser.parseBTLHeader(btl);
        final java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(btl)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);

        btlPanel.removeAllViews();
        btlPanel.addView(makeOverlayHeader("BTL 主数据与事件",
                () -> btlOverlay.setVisibility(View.GONE),
                () -> btlOverlay.setVisibility(View.GONE)));
        ScrollView sv = new ScrollView(this);
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);

        TextView infoTv = new TextView(this);
        infoTv.setText(String.format(java.util.Locale.US,
                "版本 %d  地图序号 %d  截取(%d,%d)  %dx%d\n军团 %d  建筑 %d  兵种 %d  方案 %d  事件 %d  天气 %d  地块 %d",
                h.version, h.mapId, h.captureX, h.captureY, h.width, h.height,
                h.legionCount, h.buildingCount, h.armyCount, h.planCount,
                h.eventCount, h.weatherCount, h.width * h.height));
        infoTv.setTextSize(12);
        infoTv.setTextColor(0xFFcbd5e1);
        infoTv.setPadding(4, 4, 4, 8);
        l.addView(infoTv);

        // 可编辑主数据
        btlVictorySp = makeSpinner(new String[]{"00 占领红圈", "01 消灭全部", "02 保护红圈"},
                indexOfVal(new int[]{0, 1, 2}, bb.getInt(0x30)));
        btlEraSp = makeSpinner(new String[]{"00 二战", "01 冷战"},
                indexOfVal(new int[]{0, 1}, bb.getInt(0x50)));
        addSpinnerRow(l, "胜利条件", btlVictorySp);
        btlEds = new EditText[5];
        btlEds[0] = addNumRow(l, "最小回合", bb.getInt(0x34));
        btlEds[1] = addNumRow(l, "最大回合", bb.getInt(0x38));
        addSpinnerRow(l, "战役时代", btlEraSp);
        btlEds[2] = addNumRow(l, "积攒金钱", bb.getInt(0x5C));
        btlEds[3] = addNumRow(l, "积攒齿轮", bb.getInt(0x60));
        btlEds[4] = addNumRow(l, "积攒原子", bb.getInt(0x64));
        Button saveHdr = new Button(this);
        saveHdr.setText("保存主数据");
        saveHdr.setTextSize(13);
        saveHdr.setTextColor(Color.WHITE);
        saveHdr.setBackgroundColor(Color.parseColor("#22c55e"));
        saveHdr.setOnClickListener(v -> saveBtlHeader());
        l.addView(saveHdr);

        // 事件入口：主数据页单独一栏，点击进入事件列表，再点具体事件进编辑页
        if (h.eventCount > 0) {
            Button evBtn = new Button(this);
            evBtn.setText("事件列表（" + h.eventCount + " 条）→");
            evBtn.setTextSize(13);
            evBtn.setTextColor(Color.WHITE);
            evBtn.setBackgroundColor(Color.parseColor("#1e5fa8"));
            evBtn.setOnClickListener(v -> showBtlEventListOverlay());
            l.addView(evBtn);
        }

        sv.addView(l);
        btlPanel.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        btlOverlay.setVisibility(View.VISIBLE);
    }

    private void saveBtlHeader() {
        try {
            byte[] raw = mapData.btlOriginalData.clone();
            java.nio.ByteBuffer b2 = java.nio.ByteBuffer.wrap(raw)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN);
            int[] victoryVals = {0, 1, 2};
            b2.putInt(0x30, victoryVals[btlVictorySp.getSelectedItemPosition()]);
            b2.putInt(0x50, btlEraSp.getSelectedItemPosition());
            b2.putInt(0x34, Integer.parseInt(btlEds[0].getText().toString().trim()));
            b2.putInt(0x38, Integer.parseInt(btlEds[1].getText().toString().trim()));
            b2.putInt(0x5C, Integer.parseInt(btlEds[2].getText().toString().trim()));
            b2.putInt(0x60, Integer.parseInt(btlEds[3].getText().toString().trim()));
            b2.putInt(0x64, Integer.parseInt(btlEds[4].getText().toString().trim()));
            FileParser.patchHeader(mapData, raw);
            hexMapView.refresh();
            Toast.makeText(this, "主数据已保存", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "保存失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private static String eventCondLabel(int v) {
        switch (v) {
            case 0: return "占城触发";
            case 1: return "单位死亡";
            case 2: return "回合触发";
            case 4: return "连带触发";
            default: return String.format(java.util.Locale.US, "0x%02X", v);
        }
    }

    private static String eventTypeLabel(int v) {
        switch (v) {
            case 0: return "士气上升";
            case 1: return "士气下降";
            case 2: return "士气大降";
            case 3: return "混乱";
            case 4: return "调用对话";
            case 6: return "方针转变";
            case 7: return "阵营变化";
            case 8: return "向某方位移动";
            case 16: return "加钱";
            case 17: return "加工业";
            case 18: return "加科技";
            default: return String.format(java.util.Locale.US, "0x%02X", v);
        }
    }

    /** 事件列表页：一行一条，点击进入编辑页。 */
    private void showBtlEventListOverlay() {
        if (mapData == null || mapData.btlOriginalData == null) return;
        final byte[] btl = mapData.btlOriginalData;
        final FileParser.BtlHeaderInfo h = FileParser.parseBTLHeader(btl);
        final int start = FileParser.eventStart(h);
        final java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(btl)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        btlEventListPanel.removeAllViews();
        btlEventListPanel.addView(makeOverlayHeader("事件列表（点击进入编辑）",
                () -> btlEventListOverlay.setVisibility(View.GONE),
                () -> btlEventListOverlay.setVisibility(View.GONE)));
        ScrollView sv = new ScrollView(this);
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < h.eventCount; i++) {
            int addr = start + i * 44;
            if (addr + 44 > btl.length) break;
            final int idx = i;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(6, 10, 6, 10);
            row.setClickable(true);
            row.setBackgroundColor(Color.parseColor("#1e293b"));
            row.setOnClickListener(v -> {
                btlEventListOverlay.setVisibility(View.GONE);
                showBtlEventDetailOverlay(idx);
            });
            TextView tv = new TextView(this);
            tv.setText(String.format(java.util.Locale.US,
                    "事件 #%d   触发条件:%s   触发事件:%s   回合:%d",
                    idx, eventCondLabel(btl[addr + 0x8] & 0xFF),
                    eventTypeLabel(btl[addr + 0xC] & 0xFF), bb.getInt(addr + 0x20)));
            tv.setTextSize(14);
            tv.setTextColor(Color.WHITE);
            row.addView(tv);
            l.addView(row);
            View div = new View(this);
            div.setBackgroundColor(0x22FFFFFF);
            div.setLayoutParams(new LinearLayout.LayoutParams(-1, 1));
            l.addView(div);
        }
        sv.addView(l);
        btlEventListPanel.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        btlEventListOverlay.setVisibility(View.VISIBLE);
    }

    /** 单个事件编辑页：触发条件/触发事件下拉，其余数值可改。 */
    private void showBtlEventDetailOverlay(final int idx) {
        if (mapData == null || mapData.btlOriginalData == null) return;
        btlEventEditIndex = idx;
        final byte[] btl = mapData.btlOriginalData;
        final FileParser.BtlHeaderInfo h = FileParser.parseBTLHeader(btl);
        final int addr = FileParser.eventStart(h) + idx * 44;
        final java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(btl)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        if (addr + 44 > btl.length) {
            Toast.makeText(this, "事件记录越界", Toast.LENGTH_SHORT).show();
            return;
        }
        btlEventDetailPanel.removeAllViews();
        btlEventDetailPanel.addView(makeOverlayHeader("事件 #" + idx,
                () -> {
                    btlEventDetailOverlay.setVisibility(View.GONE);
                    showBtlEventListOverlay();
                },
                () -> btlEventDetailOverlay.setVisibility(View.GONE)));
        ScrollView sv = new ScrollView(this);
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);

        int[] condVals = {0, 1, 2, 4};
        int[] typeVals = {0, 1, 2, 3, 4, 6, 7, 8, 16, 17, 18};
        String[] condLabels = {"00 占城触发", "01 单位死亡", "02 回合触发", "04 连带触发"};
        String[] typeLabels = {"00 士气上升", "01 士气下降", "02 士气大降", "03 混乱",
                "04 调用对话", "06 方针转变", "07 阵营变化", "08 向某方位移动",
                "10 加钱", "11 加工业", "12 加科技"};
        btlEventCondSp = makeSpinner(condLabels, indexOfVal(condVals, btl[addr + 0x8] & 0xFF));
        btlEventTypeSp = makeSpinner(typeLabels, indexOfVal(typeVals, btl[addr + 0xC] & 0xFF));
        addSpinnerRow(l, "触发条件", btlEventCondSp);
        addSpinnerRow(l, "触发事件", btlEventTypeSp);
        btlEventEds = new EditText[7];
        btlEventEds[0] = addNumRow(l, "序号", bb.getInt(addr + 0x0));
        btlEventEds[1] = addNumRow(l, "关联事件", bb.getInt(addr + 0x4));
        btlEventEds[2] = addNumRow(l, "触发军团", bb.getInt(addr + 0x10));
        btlEventEds[3] = addNumRow(l, "加成军团", bb.getInt(addr + 0x14));
        btlEventEds[4] = addNumRow(l, "阵营变换", bb.getInt(addr + 0x18));
        btlEventEds[5] = addNumRow(l, "触发回合", bb.getInt(addr + 0x20));
        btlEventEds[6] = addNumRow(l, "对话代码", bb.getInt(addr + 0x24));

        Button saveBtn = new Button(this);
        saveBtn.setText("保存事件");
        saveBtn.setTextSize(13);
        saveBtn.setTextColor(Color.WHITE);
        saveBtn.setBackgroundColor(Color.parseColor("#22c55e"));
        saveBtn.setOnClickListener(v -> saveBtlEvent());
        l.addView(saveBtn);

        sv.addView(l);
        btlEventDetailPanel.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        btlEventDetailOverlay.setVisibility(View.VISIBLE);
    }

    private void saveBtlEvent() {
        try {
            if (btlEventEditIndex < 0 || btlEventEds == null) return;
            FileParser.BtlHeaderInfo h = FileParser.parseBTLHeader(mapData.btlOriginalData);
            int start = FileParser.eventStart(h);
            byte[] raw = new byte[44];
            System.arraycopy(mapData.btlOriginalData, start + btlEventEditIndex * 44, raw, 0, 44);
            java.nio.ByteBuffer b2 = java.nio.ByteBuffer.wrap(raw)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN);
            int[] condVals = {0, 1, 2, 4};
            int[] typeVals = {0, 1, 2, 3, 4, 6, 7, 8, 16, 17, 18};
            b2.putInt(0x0, Integer.parseInt(btlEventEds[0].getText().toString().trim()));
            b2.putInt(0x4, Integer.parseInt(btlEventEds[1].getText().toString().trim()));
            b2.putInt(0x10, Integer.parseInt(btlEventEds[2].getText().toString().trim()));
            b2.putInt(0x14, Integer.parseInt(btlEventEds[3].getText().toString().trim()));
            b2.putInt(0x18, Integer.parseInt(btlEventEds[4].getText().toString().trim()));
            b2.putInt(0x20, Integer.parseInt(btlEventEds[5].getText().toString().trim()));
            b2.putInt(0x24, Integer.parseInt(btlEventEds[6].getText().toString().trim()));
            raw[0x8] = (byte) condVals[btlEventCondSp.getSelectedItemPosition()];
            raw[0xC] = (byte) typeVals[btlEventTypeSp.getSelectedItemPosition()];
            FileParser.patchEvent(mapData, btlEventEditIndex, raw);
            hexMapView.refresh();
            btlEventDetailOverlay.setVisibility(View.GONE);
            Toast.makeText(this, "事件已保存", Toast.LENGTH_SHORT).show();
            showBtlEventListOverlay();
        } catch (Exception e) {
            Toast.makeText(this, "保存失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /** 渐变描边圆角面板背景：外圈四边渐变边框 + 内层纯色底。 */
    private android.graphics.drawable.Drawable gradientBorderBg(
            int cornerDp, int borderDp, int fillColor, int[] gradientColors) {
        int density = (int) getResources().getDisplayMetrics().density;
        android.graphics.drawable.GradientDrawable outer =
                new android.graphics.drawable.GradientDrawable();
        outer.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        outer.setCornerRadius(cornerDp * density);
        outer.setOrientation(android.graphics.drawable.GradientDrawable.Orientation.TL_BR);
        outer.setColors(gradientColors);
        android.graphics.drawable.GradientDrawable inner =
                new android.graphics.drawable.GradientDrawable();
        inner.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        inner.setCornerRadius(Math.max(1, (cornerDp - borderDp)) * density);
        inner.setColor(fillColor);
        android.graphics.drawable.LayerDrawable ld = new android.graphics.drawable.LayerDrawable(
                new android.graphics.drawable.Drawable[]{outer, inner});
        int b = Math.max(1, borderDp * density);
        ld.setLayerInset(1, b, b, b, b);
        return ld;
    }

    private LinearLayout makeOverlayHeader(String title, Runnable closeAction) {
        return makeOverlayHeader(title, null, closeAction);
    }

    private LinearLayout makeOverlayHeader(String title, Runnable backAction, Runnable closeAction) {
        int density = (int) getResources().getDisplayMetrics().density;
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(4, 4, 4, 6);
        if (backAction != null) {
            Button backBtn = new Button(this);
            backBtn.setText("← 返回");
            backBtn.setTextSize(13);
            backBtn.setTextColor(Color.WHITE);
            backBtn.setBackgroundColor(Color.parseColor("#475569"));
            backBtn.setPadding(10 * density, 0, 10 * density, 0);
            backBtn.setOnClickListener(v -> backAction.run());
            header.addView(backBtn);
            View sp = new View(this);
            sp.setLayoutParams(new LinearLayout.LayoutParams(6, 1));
            header.addView(sp);
        }
        TextView titleTv = new TextView(this);
        titleTv.setText(title);
        titleTv.setTextSize(16);
        titleTv.setTextColor(Color.WHITE);
        titleTv.setTypeface(null, android.graphics.Typeface.BOLD);
        titleTv.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        header.addView(titleTv);
        Button closeBtn = new Button(this);
        closeBtn.setText("✕");
        closeBtn.setTextSize(14);
        closeBtn.setTextColor(Color.WHITE);
        closeBtn.setBackgroundColor(Color.parseColor("#e11d48"));
        closeBtn.setPadding(10 * density, 0, 10 * density, 0);
        closeBtn.setOnClickListener(v -> closeAction.run());
        header.addView(closeBtn);
        return header;
    }

    private static void writeLegionField(byte[] raw, int off, String type, String text) {
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(raw).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        switch (type) {
            case "f32":
                bb.putFloat(off, Float.parseFloat(text.trim()));
                break;
            case "rgba": {
                String h = text.trim().replace("#", "");
                long c = Long.parseLong(h, 16);
                raw[off] = (byte) ((c >> 16) & 0xFF);
                raw[off + 1] = (byte) ((c >> 8) & 0xFF);
                raw[off + 2] = (byte) (c & 0xFF);
                break;
            }
            default:
                bb.putInt(off, Integer.parseInt(text.trim()));
        }
    }

    private static String readLegionField(byte[] raw, int off, String type) {
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(raw).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        switch (type) {
            case "f32":
                return String.valueOf(bb.getFloat(off));
            case "rgba": {
                int r = raw[off] & 0xFF, g = raw[off + 1] & 0xFF, bl = raw[off + 2] & 0xFF;
                return String.format(java.util.Locale.US, "#%02X%02X%02X", r, g, bl);
            }
            default:
                return String.valueOf(bb.getInt(off));
        }
    }

    private void toggleAddArmy() {
        if (mapData == null || mapData.btlOriginalData == null) {
            Toast.makeText(this, "请先加载 BTL 地图", Toast.LENGTH_SHORT).show();
            return;
        }
        if (addingArmy) {
            addingArmy = false;
            pendingArmyType = null;
            pendingArmyLegion = -1;
            Toast.makeText(this, "已取消添加兵种", Toast.LENGTH_SHORT).show();
            return;
        }
        if (ArmyConfig.ALL.isEmpty()) {
            Toast.makeText(this, "兵种数据未加载", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] names = new String[ArmyConfig.ALL.size()];
        for (int i = 0; i < ArmyConfig.ALL.size(); i++) {
            ArmyConfig c = ArmyConfig.ALL.get(i);
            names[i] = c.name + "（代码" + c.army + "）";
        }
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("选择要放置的兵种");
        b.setItems(names, (d, w) -> {
            pendingArmyType = ArmyConfig.ALL.get(w);
            // 选择归属军团（单位必须有归属，否则游戏闪退）
            if (mapData.legionColors != null && mapData.legionColors.length > 0) {
                String[] legions = new String[mapData.legionColors.length];
                for (int i = 0; i < legions.length; i++) legions[i] = "军团" + (i + 1);
                AlertDialog.Builder lb = new AlertDialog.Builder(this);
                lb.setTitle("选择归属军团");
                lb.setItems(legions, (ld, lw) -> {
                    pendingArmyLegion = lw;
                    addingArmy = true;
                    Toast.makeText(this, "已选择 " + pendingArmyType.name + "（军团" + (lw + 1)
                            + "），请点击地图上的地块放置", Toast.LENGTH_LONG).show();
                });
                lb.setNegativeButton("取消", null);
                lb.show();
            } else {
                pendingArmyLegion = 0;
                addingArmy = true;
                Toast.makeText(this, "已选择 " + pendingArmyType.name + "，请点击地图上的地块放置",
                        Toast.LENGTH_LONG).show();
            }
        });
        b.setNegativeButton("取消", null);
        b.show();
    }

    /** 随机兵力：输入数量，随机在已归属地块上放置兵种（海洋格出舰船、陆地格出地面部队）。 */
    private void randomizeArmiesDialog() {
        if (mapData == null || mapData.btlOriginalData == null) {
            Toast.makeText(this, "请先加载 BTL 地图", Toast.LENGTH_SHORT).show();
            return;
        }
        AlertDialog.Builder b = new AlertDialog.Builder(this, R.style.DarkDialog);
        b.setTitle("随机兵力");
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(32, 16, 32, 16);
        TextView infoTv = new TextView(this);
        infoTv.setText("按比例放置：100 = 所有符合条件的格子都放兵，70 = 放七成。海洋格出舰船，陆地格出地面部队。");
        infoTv.setTextSize(11);
        infoTv.setTextColor(0xFF9ca3af);
        l.addView(infoTv);
        final double[] probability = {0.1};
        LinearLayout probRow = new LinearLayout(this);
        probRow.setOrientation(LinearLayout.HORIZONTAL);
        probRow.setGravity(Gravity.CENTER_VERTICAL);
        probRow.setPadding(0, 8, 0, 0);
        TextView probLabel = new TextView(this);
        probLabel.setText("比例:");
        probLabel.setTextSize(12);
        probLabel.setTextColor(0xFF374151);
        probRow.addView(probLabel);
        android.widget.SeekBar probSb = new android.widget.SeekBar(this);
        probSb.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        probSb.setMax(100);
        probSb.setProgress(10);
        final TextView probVal = new TextView(this);
        probVal.setText("10%");
        probVal.setTextSize(12);
        probVal.setTextColor(0xFF374151);
        probVal.setMinWidth(40);
        probVal.setGravity(Gravity.CENTER);
        probSb.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar seekBar, int progress, boolean fromUser) {
                probability[0] = progress / 100.0;
                probVal.setText(progress + "%");
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(android.widget.SeekBar seekBar) {}
        });
        probRow.addView(probSb);
        probRow.addView(probVal);
        l.addView(probRow);
        b.setView(l);
        b.setPositiveButton("随机放置", (d, w) -> {
            randomizeArmies(probability[0]);
        });
        b.setNegativeButton("取消", null);
        showDarkDialog(b, l);
    }

    private void randomizeArmies(double ratio) {
        java.util.Random rng = new java.util.Random();
        // 1. 收集所有可用地块（无兵种、有归属；中立地块用省份归属兜底）
        boolean[] occupied = new boolean[mapData.getTotalTiles()];
        for (MapData.Army a : mapData.armies) {
            if (a.x >= 0 && a.x < mapData.width && a.y >= 0 && a.y < mapData.height) {
                occupied[a.y * mapData.width + a.x] = true;
            }
        }
        java.util.List<int[]> candidates = new java.util.ArrayList<>();
        for (int idx = 0; idx < mapData.getTotalTiles(); idx++) {
            if (occupied[idx]) continue;
            int legion = 0xFF;
            if (mapData.belongs != null && idx < mapData.belongs.length) {
                legion = mapData.belongs[idx] & 0xFF;
                if (legion == 0xFF && mapData.provinces != null && idx < mapData.provinces.length) {
                    int p = mapData.provinces[idx];
                    if (p != 0 && p != 0xFFFF && p < mapData.getTotalTiles()
                            && p < mapData.belongs.length) {
                        legion = mapData.belongs[p] & 0xFF;
                    }
                }
            }
            if (legion == 0xFF || legion >= mapData.legionColors.length) continue;
            candidates.add(new int[]{idx % mapData.width, idx / mapData.width, legion});
        }
        if (candidates.isEmpty()) {
            Toast.makeText(this, "没有可用地块（都无归属或无空格）", Toast.LENGTH_SHORT).show();
            return;
        }
        // 2. 按比例取前 N 个：100=全部，70=七成
        int count = (int) Math.round(ratio * candidates.size());
        java.util.Collections.shuffle(candidates, rng);
        int placed = 0;
        for (int i = 0; i < count; i++) {
            int[] c = candidates.get(i);
            int x = c[0], y = c[1], legion = c[2];
            int idx = y * mapData.width + x;
            boolean sea = mapData.tiles.get(idx).bmTerrain1Group == 1;
            int[] pool = sea ? NAVAL_TYPES : LAND_TYPES;
            ArmyConfig cfg = ArmyConfig.byArmy(pool[rng.nextInt(pool.length)]);
            if (cfg == null) continue;
            byte[] raw = buildNewArmyRaw(x, y, cfg);
            try {
                history.save(mapData, "随机兵力");
                FileParser.addArmy(mapData, x, y, cfg.army, raw, legion);
                placed++;
            } catch (Exception ignored) {
            }
        }
        hexMapView.refresh();
        updateInfo();
        Toast.makeText(this, "已放置 " + placed + "/" + candidates.size() + " 个可用地块",
                Toast.LENGTH_LONG).show();
    }

    private void startCropSelect() {
        if (mapData == null) {
            Toast.makeText(this, "请先加载地图", Toast.LENGTH_SHORT).show();
            return;
        }
        closeProvinceEdit();
        if (cropState != CROP_NONE) {
            cancelCrop();
            return;
        }
        cropState = CROP_START;
        updateCropUI();
        Toast.makeText(this, "截取模式：起点，请点击起点格子", Toast.LENGTH_LONG).show();
    }

    private void applyCrop() {
        if (cropState != CROP_FRAMED || mapData == null) return;
        try {
            int w = Math.abs(cropX2 - cropX1) + 1;
            int h = Math.abs(cropY2 - cropY1) + 1;
            history.clear(); // 尺寸改变，旧快照作废
            FileParser.cropMap(mapData, cropX1, cropY1, cropX2, cropY2);
            currentFileName = "截取_" + w + "x" + h + ".btl";
            cancelCrop();
            hexMapView.setMapData(mapData);
            hexMapView.refresh();
            updateInfo();
            Toast.makeText(this, "已截取为 " + w + "x" + h, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "截取失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void cancelCrop() {
        if (cropState == CROP_NONE) return;
        cropState = CROP_NONE;
        cropX1 = cropY1 = cropX2 = cropY2 = -1;
        hexMapView.clearCropRect();
        hexMapView.refresh();
        updateCropUI();
    }

    private void updateCropUI() {
        if (cropOverlay == null) return;
        if (cropState == CROP_NONE) {
            cropOverlay.setVisibility(View.GONE);
            return;
        }
        cropOverlay.setVisibility(View.VISIBLE);
        if (cropState == CROP_START) {
            cropStatus.setText("截取模式：起点（请点击起点格子）");
            cropConfirmBtn.setVisibility(View.GONE);
            cropCancelBtn.setVisibility(View.VISIBLE);
        } else if (cropState == CROP_END) {
            cropStatus.setText("截取模式：终点（请点击终点格子）");
            cropConfirmBtn.setVisibility(View.GONE);
            cropCancelBtn.setVisibility(View.VISIBLE);
        } else {
            int x1 = Math.min(cropX1, cropX2), y1 = Math.min(cropY1, cropY2);
            int x2 = Math.max(cropX1, cropX2), y2 = Math.max(cropY1, cropY2);
            cropStatus.setText("已框选 (" + x1 + "," + y1 + ") - (" + x2 + "," + y2
                    + ")  " + (x2 - x1 + 1) + "x" + (y2 - y1 + 1));
            cropConfirmBtn.setVisibility(View.VISIBLE);
            cropCancelBtn.setVisibility(View.VISIBLE);
        }
    }

    private void expandMap() {
        if (mapData == null) { Toast.makeText(this, "请先加载地图", Toast.LENGTH_SHORT).show(); return; }
        int curW = mapData.width, curH = mapData.height;
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("扩展地图 (当前 " + curW + "x" + curH + ")");
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(40, 20, 40, 20);
        EditText wi = new EditText(this);
        wi.setHint("新宽度"); wi.setInputType(InputType.TYPE_CLASS_NUMBER); wi.setText(String.valueOf(curW));
        EditText hi = new EditText(this);
        hi.setHint("新高度"); hi.setInputType(InputType.TYPE_CLASS_NUMBER); hi.setText(String.valueOf(curH));
        l.addView(wi); l.addView(hi);
        b.setView(l);
        b.setPositiveButton("扩展", (d, w) -> {
            int nw = Integer.parseInt(wi.getText().toString());
            int nh = Integer.parseInt(hi.getText().toString());
            if (nw < curW || nh < curH || nw > 200 || nh > 200) {
                Toast.makeText(this, "新尺寸不能小于当前，最大200", Toast.LENGTH_SHORT).show(); return;
            }
            if (nw == curW && nh == curH) { Toast.makeText(this, "尺寸未变化", Toast.LENGTH_SHORT).show(); return; }
            history.save(mapData);
            expandMapData(mapData, nw, nh);
            hexMapView.setMapData(mapData);
            hexMapView.refresh();
            updateInfo();
            currentFileName = "扩展地图_" + nw + "x" + nh + ".btl";
            Toast.makeText(this, "已扩展为 " + nw + "x" + nh, Toast.LENGTH_SHORT).show();
        });
        b.setNegativeButton("取消", null);
        b.show();
    }

    private void expandMapData(MapData mapData, int newW, int newH) {
        // 向右下方扩展：旧内容左上对齐，新格填海洋
        final int oldW = mapData.width;
        expandMapGeneric(mapData, newW, newH, idx -> (idx / oldW) * newW + (idx % oldW), makeFillTile(true));
    }

    // ===== 向上扩展（顶部插入n行，原内容向下平移） =====
    private void showExpandUpDialog() {
        if (mapData == null) { Toast.makeText(this, "请先加载地图", Toast.LENGTH_SHORT).show(); return; }

        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("向上扩展地图");
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(40, 20, 40, 20);

        final TextView info = new TextView(this);
        info.setText("当前尺寸: " + mapData.width + "x" + mapData.height);
        info.setTextSize(13);
        info.setPadding(0, 0, 0, 12);
        info.setTextColor(0xFF374151);
        l.addView(info);

        EditText rowsInput = new EditText(this);
        rowsInput.setHint("向上扩展行数");
        rowsInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        rowsInput.setText("5");
        l.addView(rowsInput);

        b.setView(l);
        b.setPositiveButton("扩展", (d, w) -> {
            try {
                int n = Integer.parseInt(rowsInput.getText().toString());
                if (n <= 0 || n > 50) { Toast.makeText(this, "行数范围 1~50", Toast.LENGTH_SHORT).show(); return; }
                int newH = mapData.height + n;
                if (newH > 200) { Toast.makeText(this, "最大高度200", Toast.LENGTH_SHORT).show(); return; }

                history.save(mapData);
                expandMapUp(mapData, n);
                hexMapView.setMapData(mapData);
                hexMapView.refresh();
                updateInfo();
                currentFileName = "上扩展+" + n + "行_" + mapData.width + "x" + mapData.height + ".btl";
                Toast.makeText(this, "已向上扩展 " + n + " 行", Toast.LENGTH_SHORT).show();
            } catch (Exception ex) {
                Toast.makeText(this, "扩展出错: " + ex.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
        b.setNegativeButton("取消", null);
        b.show();
    }

    private void expandMapUp(MapData mapData, int n) {
        // 向上扩展：顶部插入 n 行，原内容整体下移 n 行
        final int w = mapData.width;
        expandMapGeneric(mapData, w, mapData.height + n, idx -> idx + n * w, makeFillTile(true));
    }

    // ===== 顶部“扩展”按钮：四个方向 =====
    private void showExpandDirectionDialog() {
        if (mapData == null) { Toast.makeText(this, "请先加载地图", Toast.LENGTH_SHORT).show(); return; }
        AlertDialog.Builder b = new AlertDialog.Builder(this, R.style.DarkDialog);
        b.setTitle("扩展地图（当前 " + mapData.width + "x" + mapData.height + "）");
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(40, 20, 40, 20);

        // 方向单选框
        final RadioGroup dirGroup = new RadioGroup(this);
        final String[] dirs = {"向上扩展", "向下扩展", "向左扩展", "向右扩展"};
        final RadioButton[] dirBtns = new RadioButton[4];
        for (int i = 0; i < 4; i++) {
            dirBtns[i] = new RadioButton(this);
            dirBtns[i].setText(dirs[i]);
            dirBtns[i].setId(i + 1); // 1=向上 2=向下 3=向左 4=向右
            if (i == 0) dirBtns[i].setChecked(true);
            dirGroup.addView(dirBtns[i]);
        }
        l.addView(dirGroup);

        final EditText et = new EditText(this);
        et.setHint("扩展行数（1~50）");
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setText("5");
        l.addView(et);
        dirGroup.setOnCheckedChangeListener((g, checkedId) ->
                et.setHint(checkedId <= 2 ? "扩展行数（1~50）" : "扩展列数（1~50）"));

        // 填充地形下拉选择
        final Spinner fillSpinner = new Spinner(this);
        ArrayAdapter<String> fillAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item,
                new String[]{"填充海洋", "填充陆地（平原）"});
        fillAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        fillSpinner.setAdapter(fillAdapter);
        l.addView(fillSpinner);
        b.setView(l);
        b.setPositiveButton("扩展", (d, w) -> {
            try {
                int checkedId = dirGroup.getCheckedRadioButtonId();
                int dir = 0;
                for (int i = 0; i < 4; i++) if (dirBtns[i].getId() == checkedId) dir = i;
                int n = Integer.parseInt(et.getText().toString());
                if (n <= 0 || n > 50) {
                    Toast.makeText(this, "行/列数范围为 1~50", Toast.LENGTH_SHORT).show();
                    return;
                }
                boolean ifLand = fillSpinner.getSelectedItemPosition() == 1;
                // 官方地图编辑器：扩展只改 bin，不需要关联任何 BTL
                performExpand(dir, n, ifLand);
            } catch (Exception ex) {
                Toast.makeText(this, "扩展出错: " + ex.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
        b.setNegativeButton("取消", null);
        showDarkDialog(b, l);
    }

    /** 执行扩展（尺寸校验 / bin 官方模式或内存模式 / 文件名与提示）。 */
    private void performExpand(int dir, int n, boolean ifLand) {
        try {
            if (mapData == null) {
                Toast.makeText(this, "请先加载地图", Toast.LENGTH_SHORT).show();
                return;
            }
            int oldW = mapData.width, oldH = mapData.height;
            boolean rows = dir <= 1;
            int newW = oldW + (rows ? 0 : n);
            int newH = oldH + (rows ? n : 0);
            if (newW > 200 || newH > 200) {
                Toast.makeText(this, "地图最大 200x200", Toast.LENGTH_SHORT).show();
                return;
            }
            history.save(mapData);
            java.util.function.IntUnaryOperator remap;
            switch (dir) {
                case 0: remap = idx -> idx + n * oldW; break;                                   // 向上
                case 1: remap = idx -> idx; break;                                              // 向下：索引不变
                case 2: remap = idx -> (idx / oldW) * newW + n + (idx % oldW); break;           // 向左
                default: remap = idx -> (idx / oldW) * newW + (idx % oldW); break;              // 向右
            }
            TerrainTile fill = ifLand ? makeFillTile(false) : makeFillTile(true);
            if (mapData.binOriginalData != null) {
                // 征服地图：官方逻辑扩展世界底图 + 坐标重映射 + 海岸线
                FileParser.extendConquest(mapData, dir, n, ifLand);
                history.clear(); // 视图已切换为整张世界地图，旧撤销栈尺寸不匹配
            } else {
                expandMapGeneric(mapData, newW, newH, remap, fill);
            }
            hexMapView.setMapData(mapData);
            hexMapView.refresh();
            updateInfo();
            final String[] names = {"向上", "向下", "向左", "向右"};
            if (mapData.conquestExtended) {
                // 官方模式：输出文件就是世界底图本身
                currentFileName = (mapData.binFileName != null && !mapData.binFileName.isEmpty())
                        ? mapData.binFileName : "world.bin";
            } else {
                currentFileName = names[dir] + "扩展" + n + (rows ? "行" : "列") + "_"
                        + newW + "x" + newH + ".btl";
            }
            String extra = mapData.binOriginalData != null
                    ? (mapData.btlOriginalData != null
                        ? "（已同步 BTL 坐标，保存时 bin + btl 一起输出）"
                        : "（仅扩展世界底图，保存时只输出 world.bin）") : "";
            Toast.makeText(this, "已" + names[dir] + "扩展 " + n + (rows ? " 行" : " 列") + extra,
                    Toast.LENGTH_LONG).show();
        } catch (Exception ex) {
            Toast.makeText(this, "扩展出错: " + ex.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /**
     * 通用地图扩展引擎：把地图改为 newW×newH，remap 将旧格索引映射到新格索引。
     * 未被旧格占用的新格填海洋（省规划 0、归属 FF）；建筑及之后所有含地块索引的
     * 业务段按同一 remap 重映射；头部宽高与 0x58 地块总数同步更新。
     */
    private void expandMapGeneric(MapData mapData, int newW, int newH,
                                  java.util.function.IntUnaryOperator remap, TerrainTile fillTile) {
        if (mapData.binOriginalData != null) {
            Toast.makeText(this, "征服地图地形来自世界 BIN，不支持扩展", Toast.LENGTH_LONG).show();
            return;
        }
        int oldW = mapData.width, oldH = mapData.height;
        int oldTotal = oldW * oldH;
        int newTotal = newW * newH;

        // 旧格 -> 新格索引（四个方向的映射均为一一映射）
        int[] oldIndexOfNew = new int[newTotal];
        java.util.Arrays.fill(oldIndexOfNew, -1);
        for (int i = 0; i < oldTotal; i++) {
            int ni = remap.applyAsInt(i);
            if (ni >= 0 && ni < newTotal) oldIndexOfNew[ni] = i;
        }

        // 1. 内存 tiles/buildingIds 按新布局重建，新格填海洋
        java.util.List<TerrainTile> oldTiles = new java.util.ArrayList<>(mapData.tiles);
        java.util.List<Integer> oldBuildings = new java.util.ArrayList<>(mapData.buildingIds);
        mapData.width = newW;
        mapData.height = newH;
        mapData.tiles = new java.util.ArrayList<>(newTotal);
        mapData.buildingIds = new java.util.ArrayList<>(newTotal);
        for (int i = 0; i < newTotal; i++) {
            int oi = oldIndexOfNew[i];
            if (oi >= 0) {
                mapData.tiles.add(oldTiles.get(oi));
                mapData.buildingIds.add(oldBuildings.get(oi));
            } else {
                mapData.tiles.add(cloneTerrain(fillTile));
                mapData.buildingIds.add(0);
            }
        }

        // 2. 修正 BTL 原始数据
        if (mapData.btlOriginalData != null) {
            try {
                byte[] oldBtl = mapData.btlOriginalData;
                FileParser.BtlHeaderInfo header = FileParser.parseBTLHeader(oldBtl);
                int terrainStart = header.terrainStart;

                int oldAdminStart = terrainStart + oldTotal * 16;
                int oldOwnershipStart = oldAdminStart + oldTotal * 2;
                int oldBuildingStart = oldOwnershipStart + oldTotal;

                int newAdminStart = terrainStart + newTotal * 16;
                int newOwnershipStart = newAdminStart + newTotal * 2;
                int newBuildingStart = newOwnershipStart + newTotal;

                int afterBuildingSize = oldBtl.length - oldBuildingStart;
                if (afterBuildingSize < 0) afterBuildingSize = 0;

                int newFileSize = newBuildingStart + afterBuildingSize;
                byte[] newBtl = new byte[newFileSize];

                // 头部与军团段原样复制
                System.arraycopy(oldBtl, 0, newBtl, 0, terrainStart);

                // 地形（16字节/格）
                for (int i = 0; i < newTotal; i++) {
                    mapData.tiles.get(i).toBytes(newBtl, terrainStart + i * 16);
                }

                // 省规划（2字节/格）：值是“省份代表地块坐标”，扩展后必须重映射，
                // 否则地块移位后省坐标仍指向旧位置，游戏里省颜色会错乱
                for (int i = 0; i < newTotal; i++) {
                    int addr = newAdminStart + i * 2;
                    int oi = oldIndexOfNew[i];
                    if (oi < 0) {
                        byte v = (byte) (fillTile.bmTerrain1Group == 1 ? 0 : 0xFF);
                        newBtl[addr] = v;
                        newBtl[addr + 1] = v;
                        continue;
                    }
                    int oldPv = (oldBtl[oldAdminStart + oi * 2] & 0xFF)
                            | ((oldBtl[oldAdminStart + oi * 2 + 1] & 0xFF) << 8);
                    int npv = oldPv;
                    if (oldPv != 0 && oldPv != 0xFFFF) {
                        int cb = mapData.coordBase;
                        int localPv = oldPv - cb;
                        if (localPv >= 0 && localPv < oldTotal) {
                            int nLocal = remap.applyAsInt(localPv);
                            if (nLocal >= 0 && nLocal <= 0xFFFF - cb) npv = nLocal + cb;
                        }
                    }
                    newBtl[addr] = (byte) (npv & 0xFF);
                    newBtl[addr + 1] = (byte) ((npv >> 8) & 0xFF);
                }

                // 军团归属（1字节/格）
                for (int i = 0; i < newTotal; i++) {
                    int oi = oldIndexOfNew[i];
                    newBtl[newOwnershipStart + i] = oi >= 0 ? oldBtl[oldOwnershipStart + oi] : (byte) 0xFF;
                }

                // 建筑及之后所有数据原样搬运
                if (afterBuildingSize > 0) {
                    System.arraycopy(oldBtl, oldBuildingStart, newBtl, newBuildingStart, afterBuildingSize);
                }

                // 建筑坐标重映射
                if (header.buildingCount > 0 && afterBuildingSize > 0) {
                    for (int i = 0; i < header.buildingCount; i++) {
                        int addr = newBuildingStart + i * 32;
                        if (addr + 4 > newFileSize) break;
                        int coord = (ByteBuffer.wrap(newBtl).order(ByteOrder.LITTLE_ENDIAN)
                                .getShort(addr) & 0xFFFF) - mapData.coordBase;
                        if (coord >= 0 && coord < oldTotal) {
                            int nc = remap.applyAsInt(coord);
                            int stored = nc + mapData.coordBase;
                            newBtl[addr] = (byte) (stored & 0xFF);
                            newBtl[addr + 1] = (byte) ((stored >>> 8) & 0xFF);
                        }
                    }
                }

                // 建筑之后各业务段（兵种/方案/援军/空袭/陷阱）的地块索引重映射
                FileParser.remapSectionTileIndexes(newBtl, newBuildingStart, header, remap,
                        mapData.coordBase);

                // 头部宽高与地块总数
                ByteBuffer bb = ByteBuffer.wrap(newBtl).order(ByteOrder.LITTLE_ENDIAN);
                bb.putInt(0x10, newW);
                bb.putInt(0x14, newH);
                bb.putInt(0x58, newTotal);

                mapData.btlOriginalData = newBtl;
                FileParser.refreshArmies(mapData);
            } catch (Exception e) {
                android.util.Log.e("EXPAND_DIR", "BTL修正失败", e);
                Toast.makeText(this, "BTL数据修正失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }
    }

    /** 生成填充地形：true=海洋，false=标准平原（与 BTL 模板中的平原地块一致）。 */
    private static TerrainTile makeFillTile(boolean sea) {
        return makeFillTileByGroup(sea ? 1 : 0);
    }

    /**
     * 生成指定地形组的填充格。真实游戏文件中陆地和海洋都带有标准装饰层
     * (3F FF / 3F FF)，缺失会导致游戏不渲染该格子（例如海洋“消失”）。
     * 1=海洋(Id=0)，0=标准平原(Id=FF)，其余组 Id=0。
     */
    private static TerrainTile makeFillTileByGroup(int group) {
        TerrainTile t = new TerrainTile();
        t.bmTerrain1Group = group;
        t.decoration1Group = 0x3F;
        t.decoration1Id = 0xFF;
        t.decoration2Group = 0x3F;
        t.decoration2Id = 0xFF;
        if (group == 0) {
            t.bmTerrain1Id = 0xFF;
        }
        return t;
    }

    /** 复制一个 TerrainTile（避免新格子共用同一实例、一改全改）。 */
    private static TerrainTile cloneTerrain(TerrainTile src) {
        TerrainTile t = new TerrainTile();
        t.bmTerrain1Group = src.bmTerrain1Group;
        t.bmTerrain1Id = src.bmTerrain1Id;
        t.bmTerrain1X = src.bmTerrain1X;
        t.bmTerrain1Y = src.bmTerrain1Y;
        t.decoration1Group = src.decoration1Group;
        t.decoration1Id = src.decoration1Id;
        t.decoration1X = src.decoration1X;
        t.decoration1Y = src.decoration1Y;
        t.decoration2Group = src.decoration2Group;
        t.decoration2Id = src.decoration2Id;
        t.decoration2X = src.decoration2X;
        t.decoration2Y = src.decoration2Y;
        t.floorGroup = src.floorGroup;
        t.floorId = src.floorId;
        t.floorX = src.floorX;
        t.floorY = src.floorY;
        return t;
    }

    private void importGuideImage() {
        if (mapData == null) { Toast.makeText(this, "请先加载地图", Toast.LENGTH_SHORT).show(); return; }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        startActivityForResult(intent, REQUEST_GUIDE);
    }

    private void toggleOverlay() {
        if (hexMapView == null) return;
        // 如果引导图可见，关闭引导图；否则切换底图
        if (hexMapView.isGuideVisible()) {
            hexMapView.setGuideVisible(false);
            hexMapView.refresh();
            Toast.makeText(this, "引导图已隐藏", Toast.LENGTH_SHORT).show();
        } else {
            boolean now = !hexMapView.isOverlayVisible();
            hexMapView.setOverlayVisible(now);
            Toast.makeText(this, now ? "底图已显示" : "底图已隐藏", Toast.LENGTH_SHORT).show();
        }
    }

    private static final int REQUEST_OVERLAY = 300;
    private static final int REQUEST_GUIDE = 301;
    private static final int REQUEST_CONQUEST_BIN = 302;
    private static final int REQUEST_APK = 305;
    private static final int REQUEST_MAPLIB_UPLOAD = 306;
    private static final String MAPLIB_BASE = "https://dx.xckeji.xyz/map_library";
    private void importOverlay() {
        if (mapData == null) { Toast.makeText(this, "请先加载地图", Toast.LENGTH_SHORT).show(); return; }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        startActivityForResult(intent, REQUEST_OVERLAY);
    }

    /** ImGui 风格分节：标题栏可点击折叠，内容跟随显隐。 */
    private LinearLayout wrapImGuiSection(String title, boolean expanded,
                                          LinearLayout content, Runnable onExpand) {
        LinearLayout sec = new LinearLayout(this);
        sec.setOrientation(LinearLayout.VERTICAL);
        Button hdr = new Button(this);
        hdr.setText((expanded ? "▾ " : "▸ ") + title);
        hdr.setTextSize(14);
        hdr.setTextColor(0xFFe5e7eb);
        hdr.setAllCaps(false);
        hdr.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        hdr.setPadding(16, 0, 8, 0);
        hdr.setBackgroundColor(Color.parseColor("#2f2f35"));
        hdr.setLayoutParams(new LinearLayout.LayoutParams(-1, 40));
        content.setVisibility(expanded ? View.VISIBLE : View.GONE);
        hdr.setOnClickListener(v -> {
            boolean show = content.getVisibility() != View.VISIBLE;
            content.setVisibility(show ? View.VISIBLE : View.GONE);
            hdr.setText((show ? "▾ " : "▸ ") + title);
            if (show && onExpand != null) onExpand.run();
        });
        sec.addView(hdr);
        sec.addView(content);
        return sec;
    }

    /** 把对话框内容改成深色 ImGui 风格：深底、浅字、输入框深色。 */
    private void showDarkDialog(AlertDialog.Builder b, View content) {
        if (content != null) {
            content.setBackgroundColor(0xFF1b1b1f);
            darkenRecursive(content);
        }
        final AlertDialog dlg = b.create();
        dlg.setOnShowListener(d -> {
            if (dlg.getWindow() != null) {
                dlg.getWindow().setBackgroundDrawable(
                        new android.graphics.drawable.ColorDrawable(0xFF1b1b1f));
            }
            TextView titleView = dlg.findViewById(android.R.id.title);
            if (titleView != null) titleView.setTextColor(0xFFe5e7eb);
            TextView msgView = dlg.findViewById(android.R.id.message);
            if (msgView != null) msgView.setTextColor(0xFFd1d5db);
            Button p = dlg.getButton(AlertDialog.BUTTON_POSITIVE);
            if (p != null) { p.setTextColor(Color.WHITE); p.setAllCaps(false); }
            Button n = dlg.getButton(AlertDialog.BUTTON_NEGATIVE);
            if (n != null) { n.setTextColor(Color.WHITE); n.setAllCaps(false); }
        });
        dlg.show();
    }

    private void darkenRecursive(View v) {
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) darkenRecursive(vg.getChildAt(i));
        } else if (v instanceof android.widget.EditText) {
            android.widget.EditText et = (android.widget.EditText) v;
            et.setBackgroundColor(0xFF2a2a2f);
            et.setTextColor(0xFFe5e7eb);
            et.setHintTextColor(0xFF9ca3af);
        } else if (v instanceof android.widget.RadioButton) {
            ((android.widget.RadioButton) v).setTextColor(0xFFd1d5db);
        } else if (v instanceof android.widget.CheckBox) {
            ((android.widget.CheckBox) v).setTextColor(0xFFd1d5db);
        } else if (v instanceof android.widget.Button) {
            // 按钮保留自身样式，避免浅底浅字看不清
        } else if (v instanceof TextView) {
            ((TextView) v).setTextColor(0xFFd1d5db);
        }
    }

    private LinearLayout createRightPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setLayoutParams(new LinearLayout.LayoutParams(440, -1));
        panel.setBackgroundColor(Color.parseColor("#1e1e24")); // ImGui 深色底
        panel.setPadding(12, 8, 12, 8);

        blockIdText = new TextView(this);
        blockIdText.setText("未选中");
        blockIdText.setTextColor(Color.parseColor("#e5e7eb"));
        blockIdText.setTextSize(15);
        blockIdText.setTypeface(null, android.graphics.Typeface.BOLD);
        blockIdText.setPadding(8, 4, 8, 4);
        panel.addView(blockIdText);

        selectedInfo = new TextView(this);
        selectedInfo.setText("点击地图上的格子开始编辑");
        selectedInfo.setTextColor(Color.parseColor("#9ca3af"));
        selectedInfo.setTextSize(13);
        selectedInfo.setPadding(8, 0, 8, 8);
        panel.addView(selectedInfo);

        mapInfo = new TextView(this);
        mapInfo.setText("未加载地图");
        mapInfo.setTextColor(Color.parseColor("#6b7280"));
        mapInfo.setTextSize(12);
        mapInfo.setPadding(8, 0, 8, 6);
        panel.addView(mapInfo);

        // 笔刷模式按钮
        LinearLayout brushRow = new LinearLayout(this);
        brushRow.setOrientation(LinearLayout.HORIZONTAL);
        brushRow.setLayoutParams(new LinearLayout.LayoutParams(-1, 48));
        brushRow.setPadding(4, 4, 4, 4);

        Button penBtn = new Button(this);
        penBtn.setText("笔刷");
        penBtn.setTextSize(14);
        penBtn.setLayoutParams(new LinearLayout.LayoutParams(0, -1, 1));
        penBtn.setGravity(Gravity.CENTER);
        penBtn.setPadding(4, 0, 4, 0);
        penBtn.setBackgroundColor(Color.parseColor("#3a3a40"));
        penBtn.setTextColor(Color.WHITE);
        penBtn.setOnClickListener(v -> {
            if (mapData == null) { Toast.makeText(this,"请先加载地图",Toast.LENGTH_SHORT).show(); return; }
            mapData.brushMode = !mapData.brushMode;
            if (mapData.brushMode) closeProvinceEdit();
            penBtn.setText(mapData.brushMode ? "笔刷(开)" : "笔刷");
            penBtn.setBackgroundColor(Color.parseColor(mapData.brushMode ? "#22c55e" : "#2a2a5e"));
            hexMapView.refresh();
            Toast.makeText(this, mapData.brushMode ? "笔刷已开启：先选地形，滑动涂抹即可修改" : "笔刷已关闭", Toast.LENGTH_SHORT).show();
        });
        brushRow.addView(penBtn);
        // 笔刷功能作为独立 ImGui 分节（稍后加入内容区）
        LinearLayout brushSection = new LinearLayout(this);
        brushSection.setOrientation(LinearLayout.VERTICAL);
        brushSection.addView(brushRow);

        // 笔刷范围控制行
        LinearLayout brushRangeRow = new LinearLayout(this);
        brushRangeRow.setOrientation(LinearLayout.HORIZONTAL);
        brushRangeRow.setLayoutParams(new LinearLayout.LayoutParams(-1, 32));
        brushRangeRow.setPadding(4, 2, 4, 2);
        brushRangeRow.setGravity(Gravity.CENTER_VERTICAL);

        final TextView rangeLabel = new TextView(this);
        rangeLabel.setText("范围:");
        rangeLabel.setTextSize(11);
        rangeLabel.setTextColor(0xFFcbd5e1);
        rangeLabel.setLayoutParams(new LinearLayout.LayoutParams(-2, -2));
        brushRangeRow.addView(rangeLabel);

        final android.widget.SeekBar rangeBar = new android.widget.SeekBar(this);
        rangeBar.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        rangeBar.setMax(5);  // 0~5圈
        rangeBar.setProgress(0);
        final TextView rangeVal = new TextView(this);
        rangeVal.setText("0");
        rangeVal.setTextSize(11);
        rangeVal.setTextColor(0xFFcbd5e1);
        rangeVal.setMinWidth(40);
        rangeVal.setGravity(Gravity.CENTER);

        rangeBar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar seekBar, int progress, boolean fromUser) {
                rangeVal.setText(String.valueOf(progress));
                if (mapData != null) mapData.brushRadius = progress;
                if (hexMapView != null) hexMapView.refresh();
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(android.widget.SeekBar seekBar) {}
        });

        brushRangeRow.addView(rangeBar);
        brushRangeRow.addView(rangeVal);
        brushSection.addView(brushRangeRow);

        // 可滚动内容区（竖向列表，支持上下滑动）
        ScrollView scrollView = new ScrollView(this);
        scrollView.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 1));
        scrollView.setBackgroundColor(Color.parseColor("#1e1e24"));

        contentArea = new LinearLayout(this);
        contentArea.setOrientation(LinearLayout.VERTICAL);
        contentArea.setBackgroundColor(Color.parseColor("#1e1e24"));

        // 地形竖向列表 — 完整31种，对应HTML terrainGroupNameMap
        terrainScroll = new LinearLayout(this);
        terrainScroll.setOrientation(LinearLayout.VERTICAL);
        int[] gids = {0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,18,20,21,22,26,30,31};
        String[] tns = {"空地","海洋","沙漠","矮雪山","中雪山","高雪山","矮土山","中土山","高土山","矮绿山","中绿山","高绿山","矮沙山","中沙山","高沙山","仙人掌","阔叶林","积雪阔叶林","针叶林","积雪针叶林","热带森林","农田","坑","雪地"};
        final long[] lastTerrainClick = {0};
        for (int i = 0; i < gids.length; i++) {
            final int g = gids[i];
            final String tn = tns[i];
            final int rowIdx = i;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setLayoutParams(new LinearLayout.LayoutParams(-1, 52));
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(8, 2, 8, 2);
            row.setClickable(true);
            row.setBackgroundColor(0xFF26262c);
            row.setOnClickListener(v -> {
                long now = System.currentTimeMillis();
                boolean isDouble = now - lastTerrainClick[0] < 400;
                lastTerrainClick[0] = isDouble ? 0 : now;
                // 截取模式下：双击地形取消截取
                if (cropState != CROP_NONE) {
                    if (isDouble) {
                        cancelCrop();
                        Toast.makeText(this, "已取消截取", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "截取中：双击地形可取消", Toast.LENGTH_SHORT).show();
                    }
                    return;
                }
                // 双击地形：取消选择
                if (isDouble) {
                    if (mapData != null) {
                        mapData.selectedTerrainGroup = -1;
                        updateTerrainSelection(-1);
                        if (mapData.brushMode) {
                            mapData.brushMode = false;
                            penBtn.setText("笔刷");
            penBtn.setBackgroundColor(Color.parseColor("#3a3a40"));
                        }
                        hexMapView.refresh();
                        Toast.makeText(this, "已取消地形选择", Toast.LENGTH_SHORT).show();
                    }
                    return;
                }
                // 记住当前选中的地形
                if (mapData == null) { Toast.makeText(this, "请先加载地图", Toast.LENGTH_SHORT).show(); return; }
                mapData.selectedTerrainGroup = g;
                updateTerrainSelection(rowIdx);
                closeProvinceEdit();
                // 如果有多选模式且有多选格子，批量修改
                if (mapData.multiSelectMode && mapData.hasSelectedBlocks()) {
                    history.save(mapData);
                    mapData.applyTerrainToSelected(g, (g == 0) ? 255 : 0);
                    hexMapView.refresh(); updateInfo();
                    Toast.makeText(this, "已批量修改 " + mapData.selectedBlocks.size() + " 个格子为 " + tn, Toast.LENGTH_SHORT).show();
                    return;
                }
                // 如果已有选中的目标格子，直接修改
                if (hexMapView.getSelectedX() >= 0) {
                    history.save(mapData);
                    int x = hexMapView.getSelectedX(), y = hexMapView.getSelectedY();
                    byte[] pat = mapData.getTerrainPattern(g);
                    if (pat != null) mapData.getTile(x, y).parseFromBytes(pat, 0);
                    else mapData.getTile(x, y).setTerrain(g);
                    mapData.editedCells.add(y * mapData.width + x);
                    // 涂地后处理被涂格子：陆地按位置选真实变体
                    mapData.finishPaint(java.util.Collections.singleton(y * mapData.width + x));
                    hexMapView.refresh(); updateInfo();
                } else {
                    Toast.makeText(this, "请先点击地图上的格子", Toast.LENGTH_SHORT).show();
                }
            });
            ImageView iv = new ImageView(this);
            iv.setLayoutParams(new LinearLayout.LayoutParams(38, 38));
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setPadding(4, 4, 4, 4);
            Bitmap t = terrainThumbs.get(g);
            if (t != null) iv.setImageBitmap(t); else iv.setBackgroundColor(0xFFcccccc);
            row.addView(iv);
            TextView lb = new TextView(this);
            lb.setText(tns[i]);
            lb.setTextSize(14);
            lb.setTextColor(0xFFd1d5db);
            lb.setGravity(Gravity.CENTER_VERTICAL);
            lb.setPadding(12, 0, 0, 0);
            lb.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
            row.addView(lb);
            terrainRows.add(row);
            terrainScroll.addView(row);
        }

        // 设施竖向列表
        buildingScroll = new LinearLayout(this);
        buildingScroll.setOrientation(LinearLayout.VERTICAL);
        buildingScroll.setVisibility(View.GONE);
        int[] bids = {11,12,13,14,41,31,42,43,44,45,1,22,23,0};
        String[] bns = {"小城市","中城市","大城市","大都市","机场","军港","要塞","堡垒","据点","工厂","农场","大工厂","核电站","清除"};
        for (int i = 0; i < bids.length; i++) {
            final int bid = bids[i];
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setLayoutParams(new LinearLayout.LayoutParams(-1, 80));
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(8, 4, 8, 4);
            row.setClickable(true);
            row.setOnClickListener(v -> {
                if (mapData == null) { Toast.makeText(this, "请先加载地图", Toast.LENGTH_SHORT).show(); return; }
                mapData.selectedBuildingId = bid;
                closeProvinceEdit();
                // 多选模式下批量修改
                if (mapData.multiSelectMode && mapData.hasSelectedBlocks()) {
                    history.save(mapData);
                    mapData.applyBuildingToSelected(bid);
                    // 新加城市：每个新增建筑的地块成为新的省区代表格
                    if (bid > 0) {
                        mapData.ensureProvincesSize();
                        for (int idx : mapData.selectedBlocks) {
                            mapData.provinces[idx] = idx;
                            FileParser.patchProvince(mapData, idx);
                            mapData.editedCells.add(idx);
                        }
                    }
                    hexMapView.refresh(); updateInfo();
                    Toast.makeText(this, "已批量修改 " + mapData.selectedBlocks.size() + " 个格子", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (hexMapView.getSelectedX() >= 0) {
                    history.save(mapData);
                    int x = hexMapView.getSelectedX(), y = hexMapView.getSelectedY();
                    mapData.setBuildingId(x, y, bid);
                    // 新加城市：该地块成为新的省区代表格
                    if (bid > 0) {
                        mapData.ensureProvincesSize();
                        int idx = y * mapData.width + x;
                        mapData.provinces[idx] = idx;
                        FileParser.patchProvince(mapData, idx);
                        mapData.editedCells.add(idx);
                    }
                    hexMapView.refresh(); updateInfo();
                } else {
                    Toast.makeText(this, "请先点击地图上的格子", Toast.LENGTH_SHORT).show();
                }
            });
            ImageView iv = new ImageView(this);
            iv.setLayoutParams(new LinearLayout.LayoutParams(70, 70));
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setPadding(4, 4, 4, 4);
            Bitmap bm = buildingThumbs.get(bid);
            if (bm != null) iv.setImageBitmap(bm);
            else iv.setBackgroundColor(bid == 0 ? 0xFFfee2e2 : 0xFFe5e7eb);
            row.addView(iv);
            TextView lb = new TextView(this);
            lb.setText(bns[i]);
            lb.setTextSize(14);
            lb.setTextColor(bid == 0 ? 0xFFdc2626 : 0xFF374151);
            lb.setGravity(Gravity.CENTER_VERTICAL);
            lb.setPadding(12, 0, 0, 0);
            lb.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
            row.addView(lb);
            buildingScroll.addView(row);
        }

        contentArea.addView(wrapImGuiSection("笔刷", true, brushSection, null));
        contentArea.addView(wrapImGuiSection("地形", true, terrainScroll, null));
        contentArea.addView(wrapImGuiSection("设施", false, buildingScroll, null));

        // 兵种页：点击地图上已有兵种格子的 48 字段编辑区
        armyScroll = new LinearLayout(this);
        armyScroll.setOrientation(LinearLayout.VERTICAL);
        armyScroll.setVisibility(View.GONE);
        armyEditorArea = new LinearLayout(this);
        armyEditorArea.setOrientation(LinearLayout.VERTICAL);
        int d0 = (int) getResources().getDisplayMetrics().density;
        armyEditorArea.setPadding(12 * d0, 8 * d0, 12 * d0, 8 * d0);
        armyEditorArea.setBackgroundColor(Color.parseColor("#1b1b1f"));
        // 兵种分节 = 添加兵种：兵种图标栏移入本分节（点图标锁定，再点地图添加）
        buildArmyIconBar(armyScroll);
        contentArea.addView(wrapImGuiSection("兵种", false, armyScroll, null));

        // 省区规划页：视图开关 / 新建省区 / 省区列表 / 划入笔刷
        provinceScroll = new LinearLayout(this);
        provinceScroll.setOrientation(LinearLayout.VERTICAL);
        provinceScroll.setVisibility(View.GONE);

        provinceEditBtn = new TextView(this);
        provinceEditBtn.setText("省区编辑：关");
        provinceEditBtn.setTextSize(15);
        provinceEditBtn.setTextColor(Color.WHITE);
        provinceEditBtn.setAllCaps(false);
        provinceEditBtn.setClickable(true);
        provinceEditBtn.setGravity(Gravity.CENTER);
        provinceEditBtn.setBackgroundColor(Color.parseColor("#3a3a40"));
        provinceEditBtn.setLayoutParams(new LinearLayout.LayoutParams(-1, 48));
        provinceEditBtn.setOnClickListener(v -> {
            if (mapData == null) { Toast.makeText(this, "请先加载地图", Toast.LENGTH_SHORT).show(); return; }
            mapData.provinceEditMode = !mapData.provinceEditMode;
            if (!mapData.provinceEditMode) {
                mapData.provinceBrushSeed = -1;
                provinceTakeMode = false;
            }
            refreshProvinceEditUi();
            if (provinceHint != null) {
                provinceHint.setText(mapData.provinceEditMode
                        ? "已开启：没选省区时点地图=新建省区；选了省区时点地图=划入。点下方省区可切换"
                        : "点上面开关开启省区编辑");
            }
            hexMapView.refresh();
            rebuildProvinceList();
        });
        provinceScroll.addView(provinceEditBtn);

        // 官方 C/V：取省 / 刷省 两个虚拟按钮
        LinearLayout provRow = new LinearLayout(this);
        provRow.setOrientation(LinearLayout.HORIZONTAL);
        provRow.setPadding(0, 6, 0, 0);
        TextView takeProvBtn = new TextView(this);
        takeProvBtn.setText("取省");
        takeProvBtn.setTextSize(15);
        takeProvBtn.setTextColor(Color.WHITE);
        takeProvBtn.setAllCaps(false);
        takeProvBtn.setClickable(true);
        takeProvBtn.setGravity(Gravity.CENTER);
        takeProvBtn.setBackgroundColor(Color.parseColor("#3a3a40"));
        takeProvBtn.setLayoutParams(new LinearLayout.LayoutParams(0, 44, 1));
        takeProvBtn.setOnClickListener(v -> {
            if (mapData == null) { Toast.makeText(this, "请先加载地图", Toast.LENGTH_SHORT).show(); return; }
            // 进入取省模式：点地图任意有省区的格子读取该省区，不新建、不绘制
            mapData.provinceBrushSeed = -1;
            mapData.provinceEditMode = true;
            provinceTakeMode = true;
            refreshProvinceEditUi();
            if (provinceHint != null) {
                provinceHint.setText("取省中：点击一个有省区的格子");
            }
            rebuildProvinceList();
            hexMapView.refresh();
        });
        provRow.addView(takeProvBtn);
        View provGap = new View(this);
        provGap.setLayoutParams(new LinearLayout.LayoutParams(6, 1));
        provRow.addView(provGap);
        TextView paintProvBtn = new TextView(this);
        paintProvBtn.setText("刷省");
        paintProvBtn.setTextSize(15);
        paintProvBtn.setTextColor(Color.WHITE);
        paintProvBtn.setAllCaps(false);
        paintProvBtn.setClickable(true);
        paintProvBtn.setGravity(Gravity.CENTER);
        paintProvBtn.setBackgroundColor(Color.parseColor("#3a3a40"));
        paintProvBtn.setLayoutParams(new LinearLayout.LayoutParams(0, 44, 1));
        paintProvBtn.setOnClickListener(v -> {
            if (mapData == null) { Toast.makeText(this, "请先加载地图", Toast.LENGTH_SHORT).show(); return; }
            if (mapData.provinceBrushSeed < 0) { Toast.makeText(this, "请先点「取省」", Toast.LENGTH_SHORT).show(); return; }
            mapData.provinceEditMode = true;
            provinceTakeMode = false;
            refreshProvinceEditUi();
            if (provinceHint != null) {
                provinceHint.setText("刷省中：点地图或按住拖动连续把格子划入省区（值 "
                        + mapData.provinceBrushSeed + "）");
            }
            hexMapView.refresh();
        });
        provRow.addView(paintProvBtn);
        provinceScroll.addView(provRow);

        // 撤销按钮：撤销上一步省区操作（画错/新建错时用）
        TextView undoProvBtn = new TextView(this);
        undoProvBtn.setText("撤销上一步省区操作");
        undoProvBtn.setTextSize(14);
        undoProvBtn.setTextColor(0xFFe5e7eb);
        undoProvBtn.setAllCaps(false);
        undoProvBtn.setClickable(true);
        undoProvBtn.setGravity(Gravity.CENTER);
        undoProvBtn.setBackgroundColor(Color.parseColor("#3a3a40"));
        undoProvBtn.setLayoutParams(new LinearLayout.LayoutParams(-1, 44));
        undoProvBtn.setOnClickListener(v -> {
            if (mapData == null) {
                Toast.makeText(this, "请先加载地图", Toast.LENGTH_SHORT).show();
                return;
            }
            mapData.ensureProvincesSize();
            if (!mapData.undoProvince()) {
                Toast.makeText(this, "没有可撤销的省区操作", Toast.LENGTH_SHORT).show();
                return;
            }
            int n = mapData.getTotalTiles();
            for (int i = 0; i < n; i++) {
                FileParser.patchProvince(mapData, i);
            }
            rebuildProvinceList();
            refreshProvinceEditUi();
            hexMapView.refresh();
            updateInfo();
            Toast.makeText(this, "已撤销上一步省区操作", Toast.LENGTH_SHORT).show();
        });
        provinceScroll.addView(undoProvBtn);

        provinceHint = new TextView(this);
        provinceHint.setText("点上面开关开启省区编辑");
        provinceHint.setTextSize(12);
        provinceHint.setTextColor(0xFF6b7280);
        provinceHint.setPadding(4, 8, 4, 8);
        provinceScroll.addView(provinceHint);

        provinceListBox = new LinearLayout(this);
        provinceListBox.setOrientation(LinearLayout.VERTICAL);
        provinceScroll.addView(provinceListBox);

        scrollView.addView(contentArea);
        panel.addView(scrollView);

        return panel;
    }

    /** 重建省区列表：统计每种省区（代表格坐标）的地块数，点击选中作为省区笔刷。 */
    private void rebuildProvinceList() {
        if (provinceListBox == null) return;
        provinceListBox.removeAllViews();
        if (mapData == null || mapData.provinces == null) {
            provinceListBox.addView(provinceHintText("未加载地图"));
            return;
        }
        java.util.Map<Integer, Integer> counts = new java.util.LinkedHashMap<>();
        for (int pv : mapData.provinces) {
            if (pv == 0 || pv == 0xFFFF) continue;
            counts.merge(pv, 1, Integer::sum);
        }
        if (counts.isEmpty()) {
            provinceListBox.addView(provinceHintText("暂无省区，点“新建省区”后在地图上点击创建"));
            return;
        }
        for (java.util.Map.Entry<Integer, Integer> e : counts.entrySet()) {
            final int seed = e.getKey();
            int cnt = e.getValue();
            int sx = seed % mapData.width, sy = seed / mapData.width;
            TextView row = new TextView(this);
            row.setText("省区 #" + seed + " (" + sx + "," + sy + ") · " + cnt + " 格"
                    + (seed == mapData.provinceBrushSeed ? " ✓" : ""));
            row.setTextSize(15);
            row.setTextColor(Color.WHITE);
            row.setAllCaps(false);
            row.setClickable(true);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(16, 0, 8, 0);
            // 纯文字显示：不靠颜色区分，选中的省区用 ✓ 标记
            row.setBackgroundColor(Color.parseColor("#3a3a40"));
            row.setLayoutParams(new LinearLayout.LayoutParams(-1, 48));
            row.setOnClickListener(v -> {
                mapData.provinceBrushSeed = seed;
                mapData.provinceEditMode = true;
                provinceTakeMode = false;
                refreshProvinceEditUi();
                if (provinceHint != null) {
                    provinceHint.setText("已选省区 (" + sx + "," + sy + ")，点地图地块划入；点开关可关闭");
                }
                rebuildProvinceList();
                hexMapView.refresh();
                Toast.makeText(this, "已选择省区 (" + sx + "," + sy + ")，点击地图地块划入", Toast.LENGTH_SHORT).show();
            });
            provinceListBox.addView(row);
        }
    }

    /** 同步“省区编辑”开关的显示状态。 */
    private void refreshProvinceEditUi() {
        if (provinceEditBtn != null) {
            boolean on = mapData != null && mapData.provinceEditMode;
            provinceEditBtn.setText(on ? "省区编辑：开" : "省区编辑：关");
            // 纯文字显示：开关状态用文字表示，不靠颜色
            provinceEditBtn.setBackgroundColor(Color.parseColor("#3a3a40"));
        }
    }

    /** 切换到其他模式（笔刷/地形/设施/兵种/多选/截取等）时自动关闭省区编辑，避免误改归属。 */
    private void closeProvinceEdit() {
        if (mapData == null) return;
        boolean wasOn = mapData.provinceEditMode || mapData.provinceBrushSeed >= 0
                || provinceTakeMode;
        mapData.provinceEditMode = false;
        mapData.provinceBrushSeed = -1;
        provinceTakeMode = false;
        if (!wasOn) return;
        refreshProvinceEditUi();
        rebuildProvinceList();
        if (provinceHint != null) {
            provinceHint.setText("点上面开关开启省区编辑");
        }
        if (hexMapView != null) hexMapView.refresh();
    }

    private TextView provinceHintText(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(11);
        t.setTextColor(0xFF9ca3af);
        t.setPadding(4, 8, 4, 8);
        return t;
    }

    private void showArmyDetail(ArmyConfig c) {
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle(c.name);
        b.setMessage("攻击：" + c.minAttack + " - " + c.maxAttack + "\n"
                + "射程：" + c.minRange + (c.maxRange > c.minRange ? " - " + c.maxRange : "") + "\n"
                + "生命：" + c.hp + "\n"
                + "防御：" + c.defence + "\n"
                + "移动力：" + c.mobility + "\n"
                + "最大编制：" + c.maxFormation + "\n"
                + "载具：" + (c.carrier != 0 ? "是" : "否") + "\n"
                + "建造回合：" + c.buildTime + "\n"
                + "造价：金钱 " + c.costMoney + " / 齿轮 " + c.costGear + " / 原子 " + c.costAtomic);
        b.setPositiveButton("关闭", null);
        b.show();
    }

    /** 兵种选择列表（像地形一样）：点选后点击地图放置，可加入任意容器（如设施页建筑列表旁）。 */
    private void buildArmyAddRows(final LinearLayout target) {
        if (target == null) return;
        int density = (int) getResources().getDisplayMetrics().density;
        TextView hint = new TextView(this);
        hint.setText("点击兵种图标锁定，然后连续点击地图地块添加（再点一次取消）");
        hint.setTextSize(12);
        hint.setTextColor(0xFF9ca3af);
        hint.setPadding(8, 10, 8, 4);
        target.addView(hint);
        if (armyAddRows == null) armyAddRows = new java.util.ArrayList<>();
        if (ArmyConfig.ALL != null) {
            // 按兵种代码去重（同一代码只是国家变体），列表更清爽、图标一一对应
            java.util.LinkedHashMap<Integer, ArmyConfig> uniq = new java.util.LinkedHashMap<>();
            for (ArmyConfig c : ArmyConfig.ALL) {
                // 只显示 1~40 号兵种，41 及以后不显示
                if (c != null && c.army >= 1 && (c.army <= 40 || c.elite > 0)
                        && !uniq.containsKey(c.army)) uniq.put(c.army, c);
            }
            for (final ArmyConfig c : uniq.values()) {
                final LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(8, 6, 8, 6);
                row.setClickable(true);
                Bitmap icon = loadArmyIcon(c.army);
                if (icon == null) icon = makeArmyIconPlaceholder(c.army);
                if (icon != null) {
                    ImageView iv = new ImageView(this);
                    iv.setImageBitmap(icon);
                    iv.setLayoutParams(new LinearLayout.LayoutParams(38 * density, 38 * density));
                    iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    row.addView(iv);
                }
                TextView tv = new TextView(this);
                tv.setText(c.name + "（代码" + c.army + "）\n" + c.summary());
                tv.setTextSize(13);
                tv.setTextColor(0xFF374151);
                tv.setPadding(8, 0, 0, 0);
                row.addView(tv);
                row.setTag(c.army);
                row.setOnClickListener(v -> toggleArmyLock(c, row));
                armyAddRows.add(row);
                target.addView(row);
                View div = new View(this);
                div.setBackgroundColor(0x22FFFFFF);
                div.setLayoutParams(new LinearLayout.LayoutParams(-1, 1));
                target.addView(div);
            }
        }
    }

    /** 兵种图标：优先真实图标，缺图时退回红色变体。 */
    private Bitmap loadArmyIcon(int army) {
        Bitmap b = loadBmp("legion/legion_icon_" + army + ".png");
        if (b != null) return b;
        return loadBmp("legion/legion_icon_r_" + army + ".png");
    }

    /** 无图标的兵种代码：生成带代码数字的彩色占位图标，保证每行都有图标。 */
    private Bitmap makeArmyIconPlaceholder(int army) {
        int size = 48;
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(bmp);
        int[] colors = {0xFF3B82F6, 0xFF22C55E, 0xFFF59E0B, 0xFFEF4444,
                0xFF8B5CF6, 0xFF06B6D4, 0xFFF97316, 0xFF84CC16};
        android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        p.setColor(colors[army % colors.length]);
        c.drawRoundRect(new android.graphics.RectF(1, 1, size - 1, size - 1), 8, 8, p);
        p.setColor(0xFFFFFFFF);
        p.setTextSize(20);
        p.setTextAlign(android.graphics.Paint.Align.CENTER);
        p.setFakeBoldText(true);
        c.drawText(String.valueOf(army), size / 2f, size / 2f + 7, p);
        return bmp;
    }

    /** 右侧面板顶部：直接显示一排兵种图标，点图标锁定，点地图地块连续添加。 */
    private void buildArmyIconBar(LinearLayout panel) {
        int density = (int) getResources().getDisplayMetrics().density;
        TextView hint = new TextView(this);
        hint.setText("兵种：点图标锁定，再点地图添加（再点一次取消）");
        hint.setTextSize(12);
        hint.setTextColor(0xFF6b7280);
        hint.setPadding(6, 6, 6, 2);
        panel.addView(hint);
        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setLayoutParams(new LinearLayout.LayoutParams(-1, 56 * density));
        hsv.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        armyIconViews = new java.util.ArrayList<>();
        if (ArmyConfig.ALL != null) {
            java.util.LinkedHashMap<Integer, ArmyConfig> uniq = new java.util.LinkedHashMap<>();
            for (ArmyConfig c : ArmyConfig.ALL) {
                // 只显示 1~40 号兵种，41 及以后不显示
                if (c != null && c.army >= 1 && (c.army <= 40 || c.elite > 0)
                        && !uniq.containsKey(c.army)) uniq.put(c.army, c);
            }
            for (final ArmyConfig c : uniq.values()) {
                Bitmap icon = loadArmyIcon(c.army);
                if (icon == null) continue; // 没有图标的兵种不显示
                ImageView iv = new ImageView(this);
                iv.setTag(c.army);
                iv.setImageBitmap(icon);
                iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                LinearLayout.LayoutParams lp =
                        new LinearLayout.LayoutParams(48 * density, 48 * density);
                lp.setMargins(3 * density, 2 * density, 3 * density, 2 * density);
                iv.setLayoutParams(lp);
                iv.setPadding(2, 2, 2, 2);
                iv.setClickable(true);
                iv.setOnClickListener(v -> toggleArmyIconLock(c, iv));
                armyIconViews.add(iv);
                row.addView(iv);
            }
        }
        hsv.addView(row);
        panel.addView(hsv);
        View div2 = new View(this);
        div2.setLayoutParams(new LinearLayout.LayoutParams(-1, 1));
        div2.setBackgroundColor(Color.parseColor("#33333a"));
        panel.addView(div2);
    }

    /** 点兵种图标：锁定/取消锁定。 */
    private void toggleArmyIconLock(final ArmyConfig c, final ImageView iv) {
        if (mapData == null || mapData.btlOriginalData == null) {
            Toast.makeText(this, "请先加载 BTL 地图", Toast.LENGTH_SHORT).show();
            return;
        }
        if (mapData.selectedArmyType == c.army) {
            mapData.selectedArmyType = -1;
            highlightArmyIcons();
            Toast.makeText(this, "已取消锁定", Toast.LENGTH_SHORT).show();
            return;
        }
        mapData.selectedArmyType = c.army;
        highlightArmyIcons();
        closeProvinceEdit();
        Toast.makeText(this, "已锁定 " + c.name + "，连续点击地图地块添加（再点一次取消）",
                Toast.LENGTH_LONG).show();
    }

    /** 高亮当前锁定的兵种图标。 */
    private void highlightArmyIcons() {
        if (armyIconViews == null) return;
        for (ImageView iv : armyIconViews) {
            Object tag = iv.getTag();
            iv.setBackgroundColor(tag instanceof Integer
                    && (Integer) tag == mapData.selectedArmyType ? 0x663b82f6 : 0x00000000);
        }
    }

    /** 点选兵种：锁定/取消锁定，锁定后可连续点击地图地块添加多个兵。 */
    private void toggleArmyLock(final ArmyConfig c, final LinearLayout row) {
        if (mapData == null || mapData.btlOriginalData == null) {
            Toast.makeText(this, "请先加载 BTL 地图", Toast.LENGTH_SHORT).show();
            return;
        }
        if (mapData.selectedArmyType == c.army) {
            mapData.selectedArmyType = -1;
            highlightArmyRows();
            Toast.makeText(this, "已取消锁定", Toast.LENGTH_SHORT).show();
            return;
        }
        mapData.selectedArmyType = c.army;
        highlightArmyRows();
        closeProvinceEdit();
        Toast.makeText(this, "已锁定 " + c.name + "，连续点击地图地块添加（再点一次取消）",
                Toast.LENGTH_LONG).show();
    }

    /** 高亮当前锁定的兵种行。 */
    private void highlightArmyRows() {
        if (armyAddRows == null) return;
        if (armyAddRows != null) {
            for (LinearLayout r : armyAddRows) {
                Object tag = r.getTag();
                r.setBackgroundColor(tag instanceof Integer
                        && (Integer) tag == mapData.selectedArmyType ? 0x333b82f6 : 0x00000000);
            }
        }
    }

    /** 在“兵种”页编辑区展示/编辑“兵种48”记录的全部字段（输入框形式）。 */
    private void rebuildArmyEditor() {
        if (armyEditorArea == null) return;
        armyEditorArea.removeAllViews();
        if (selectedArmy == null || selectedArmy.raw == null) {
            TextView hint = new TextView(this);
            hint.setText("请先在地图上点击一个有兵种的格子查看/修改");
            hint.setTextSize(12);
            hint.setTextColor(0xFF9ca3af);
            hint.setPadding(8, 12, 8, 12);
            armyEditorArea.addView(hint);
            return;
        }
        final int density = (int) getResources().getDisplayMetrics().density;
        final MapData.Army army = selectedArmy;
        armyTypePickerValue = army.type;
        TextView head = new TextView(this);
        head.setText((army.name != null ? army.name : "兵种" + army.type) + " Lv" + army.level);
        head.setTextSize(13);
        head.setTextColor(0xFFe5e7eb);
        head.setTypeface(null, android.graphics.Typeface.BOLD);
        head.setGravity(Gravity.CENTER);
        head.setPadding(8, 6, 8, 2);
        armyEditorArea.addView(head);

        // 点击兵种后：编辑区顶部居中显示带图标的兵
        Bitmap icon = loadArmyIcon(army.type);
        if (icon != null) {
            LinearLayout iconWrap = new LinearLayout(this);
            iconWrap.setOrientation(LinearLayout.VERTICAL);
            iconWrap.setGravity(Gravity.CENTER);
            iconWrap.setPadding(0, 4 * density, 0, 4 * density);
            ImageView iv = new ImageView(this);
            iv.setImageBitmap(icon);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setLayoutParams(new LinearLayout.LayoutParams(96 * density, 72 * density));
            iconWrap.addView(iv);
            TextView iconLb = new TextView(this);
            iconLb.setText("兵种" + army.type);
            iconLb.setTextSize(10);
            iconLb.setTextColor(0xFF9ca3af);
            iconLb.setGravity(Gravity.CENTER);
            iconWrap.addView(iconLb);
            armyEditorArea.addView(iconWrap);
        }

        armySaveBtn = new Button(this);
        armySaveBtn.setText("保存修改");
        armySaveBtn.setTextSize(12);
        armySaveBtn.setTextColor(Color.WHITE);
        armySaveBtn.setBackgroundColor(Color.parseColor("#22c55e"));
        armySaveBtn.setOnClickListener(v -> {
            byte[] raw = army.raw.clone();
            if (armyEds != null) {
                for (int i = 0; i < ARMY_FIELDS.length && i < armyEds.length; i++) {
                    if (i == 0) continue; // 坐标固定，不显示不修改
                    if ("兵种".equals(ARMY_FIELDS[i][0])) continue; // 兵种用可视化选择
                    if ("编制".equals(ARMY_FIELDS[i][0])) continue; // 编制用下拉单选框
                    try {
                        int val = Integer.parseInt(armyEds[i].getText().toString().trim());
                        writeArmyField(raw, Integer.decode(ARMY_FIELDS[i][2]), ARMY_FIELDS[i][1], val);
                    } catch (Exception ignored) {
                    }
                }
            }
            if (armyFormationSp != null) {
                writeArmyField(raw, 0x4, "u8", armyFormationSp.getSelectedItemPosition() + 1);
            }
            if (armyAiSp != null) {
                writeArmyField(raw, 0x1D, "u8", Integer.parseInt(armyAiSp.getSelectedItem().toString().split(" ")[0]));
            }
            if (armyMoraleSp != null) {
                writeArmyField(raw, 0x28, "u8", Integer.parseInt(armyMoraleSp.getSelectedItem().toString().split(" ")[0]));
            }
            if (armyBadgeSp != null) {
                writeArmyField(raw, 0x2B, "u8", Integer.parseInt(armyBadgeSp.getSelectedItem().toString().split(" ")[0]));
            }
            if (armyTransportSp != null) {
                writeArmyField(raw, 0x1E, "u8", Integer.parseInt(armyTransportSp.getSelectedItem().toString().split(" ")[0]));
            }
            if (armyTypePickerValue > 0) {
                writeArmyField(raw, 0x2, "u8", armyTypePickerValue);
            }
            try {
                history.save(mapData, "修改兵种");
                FileParser.patchArmy(mapData, army, raw);
                FileParser.refreshArmies(mapData);
                hexMapView.refresh();
                updateInfo();
                Toast.makeText(this, "兵种已更新", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "保存失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
        armyEditorArea.addView(armySaveBtn);

        armyEds = new EditText[ARMY_FIELDS.length];
        for (int i = 0; i < ARMY_FIELDS.length; i++) {
            if (i == 0) continue; // 坐标：删除，不再显示
            final String fname = ARMY_FIELDS[i][0];
            final String ftype = ARMY_FIELDS[i][1];
            final int off = Integer.decode(ARMY_FIELDS[i][2]);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(0, 3 * density, 0, 3 * density);
            TextView label = new TextView(this);
            label.setText(fname);
            label.setTextSize(11);
            label.setTextColor(0xFFd1d5db);
            row.addView(label);
            if ("兵种".equals(fname)) {
                // 兵种：可视化选择（图标横排，点击切换，保存后生效）
                HorizontalScrollView hsv = new HorizontalScrollView(this);
                hsv.setHorizontalScrollBarEnabled(false);
                hsv.setLayoutParams(new LinearLayout.LayoutParams(-1, 58 * density));
                LinearLayout iconRow = new LinearLayout(this);
                iconRow.setOrientation(LinearLayout.HORIZONTAL);
                iconRow.setGravity(Gravity.CENTER_VERTICAL);
                java.util.LinkedHashMap<Integer, ArmyConfig> uniq = new java.util.LinkedHashMap<>();
                if (ArmyConfig.ALL != null) {
                    for (ArmyConfig c : ArmyConfig.ALL) {
                        if (c != null && c.army >= 1 && (c.army <= 40 || c.elite > 0)
                                && !uniq.containsKey(c.army)) {
                            uniq.put(c.army, c);
                        }
                    }
                }
                for (final ArmyConfig c : uniq.values()) {
                    Bitmap ic = loadArmyIcon(c.army);
                    if (ic == null) continue;
                    final ImageView iv = new ImageView(this);
                    iv.setImageBitmap(ic);
                    iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(48 * density, 48 * density);
                    lp.setMargins(3 * density, 2 * density, 3 * density, 2 * density);
                    iv.setLayoutParams(lp);
                    iv.setPadding(2, 2, 2, 2);
                    iv.setTag(c.army);
                    updateArmyPickerHighlight(iv);
                    iv.setOnClickListener(v -> {
                        armyTypePickerValue = (Integer) iv.getTag();
                        refreshArmyPickerHighlight(iconRow);
                    });
                    iconRow.addView(iv);
                }
                hsv.addView(iconRow);
                row.addView(hsv);
                TextView pickHint = new TextView(this);
                pickHint.setText("点击图标选择兵种（当前：代码" + armyTypePickerValue + "），保存后生效");
                pickHint.setTextSize(10);
                pickHint.setTextColor(0xFF9ca3af);
                row.addView(pickHint);
                Button pickA = new Button(this);
                pickA.setText("🔍 搜索选择兵种");
                pickA.setTextSize(11);
                pickA.setTextColor(Color.WHITE);
                pickA.setBackgroundColor(Color.parseColor("#7c3aed"));
                pickA.setLayoutParams(new LinearLayout.LayoutParams(-1, 40 * density));
                pickA.setOnClickListener(v -> showArmyPicker(id -> {
                    armyTypePickerValue = id;
                    refreshArmyPickerHighlight(iconRow);
                    ArmyConfig c = ArmyConfig.byArmy(id);
                    pickHint.setText("已选：" + (c != null ? c.name : ("代码" + id))
                            + "（代码" + id + "），保存后生效");
                }));
                row.addView(pickA);
            } else if ("编制".equals(fname)) {
                armyFormationSp = makeSpinner(new String[]{"1", "2", "3", "4"},
                        Math.max(0, Math.min(3, readArmyField(army.raw, off, ftype) - 1)));
                row.addView(armyFormationSp);
            } else if ("AI行动模式".equals(fname)) {
                armyAiSp = makeSpinner(new String[]{"0 自由行动", "1 稳步推进", "2 激烈进攻",
                        "3 坚守不动", "4 远离敌人"},
                        Math.max(0, Math.min(4, readArmyField(army.raw, off, ftype))));
                row.addView(armyAiSp);
            } else if ("士气".equals(fname)) {
                int cur = readArmyField(army.raw, off, ftype);
                String[] opts = {"1 士气上升", "255 士气下降", "254 士气双降", "253 混乱"};
                int idx = 0;
                for (int o = 0; o < opts.length; o++) {
                    if (Integer.parseInt(opts[o].split(" ")[0]) == cur) idx = o;
                }
                armyMoraleSp = makeSpinner(opts, idx);
                row.addView(armyMoraleSp);
            } else if ("等级标志显示".equals(fname)) {
                armyBadgeSp = makeSpinner(new String[]{"0 无", "9 护盾"},
                        readArmyField(army.raw, off, ftype) == 9 ? 1 : 0);
                row.addView(armyBadgeSp);
            } else if ("运输船".equals(fname)) {
                armyTransportSp = makeSpinner(new String[]{"0 自由行动", "1 无法上岸", "2 无法下海"},
                        Math.max(0, Math.min(2, readArmyField(army.raw, off, ftype))));
                row.addView(armyTransportSp);
            } else {
                EditText et = new EditText(this);
                et.setInputType(InputType.TYPE_CLASS_NUMBER);
                et.setText(String.valueOf(readArmyField(army.raw, off, ftype)));
                et.setTextColor(0xFFe5e7eb);
                et.setTextSize(12);
                et.setBackgroundColor(Color.parseColor("#2a2a2f"));
                et.setPadding(6 * density, 4 * density, 6 * density, 4 * density);
                armyEds[i] = et;
                row.addView(et);
                if ("将领".equals(fname)) {
                    final TextView gname = new TextView(this);
                    gname.setText(generalInfoText(army.general, army.raw));
                    gname.setTextSize(10);
                    gname.setTextColor(0xFF9ca3af);
                    row.addView(gname);
                    Button pickG = new Button(this);
                    pickG.setText("🔍 搜索选择将领");
                    pickG.setTextSize(11);
                    pickG.setTextColor(Color.WHITE);
                    pickG.setBackgroundColor(Color.parseColor("#7c3aed"));
                    pickG.setLayoutParams(new LinearLayout.LayoutParams(-1, 40 * density));
                    pickG.setOnClickListener(v -> showGeneralPicker(id -> {
                        et.setText(String.valueOf(id));
                        gname.setText(generalInfoText(id, army.raw));
                    }));
                    row.addView(pickG);
                }
            }
            armyEditorArea.addView(row);
        }
    }

    /** 刷新兵种图标选择行的高亮。 */
    private void refreshArmyPickerHighlight(LinearLayout iconRow) {
        if (iconRow == null) return;
        for (int i = 0; i < iconRow.getChildCount(); i++) {
            View v = iconRow.getChildAt(i);
            if (v instanceof ImageView) updateArmyPickerHighlight((ImageView) v);
        }
    }

    /** 单个兵种图标高亮：选中=蓝边框。 */
    private void updateArmyPickerHighlight(ImageView iv) {
        Object tag = iv.getTag();
        boolean sel = tag != null && tag instanceof Integer
                && (Integer) tag == armyTypePickerValue;
        final int density = (int) getResources().getDisplayMetrics().density;
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setColor(0xFF2a2a2f);
        gd.setCornerRadius(8 * density);
        gd.setStroke(sel ? 3 : 1, sel ? 0xFF6f9bff : 0xFF3a4356);
        iv.setBackground(gd);
    }

    /** 城市归属（国家/军团）编辑行：国旗 + 国家名，点击即设归属（含中立）。 */
    private void addCityOwnershipRow(final LinearLayout target, final MapData.Building b,
                                     final Runnable refresh) {
        final int density = (int) getResources().getDisplayMetrics().density;
        final int cityIdx = b.y * mapData.width + b.x;
        int curLeg = (mapData.belongs != null && cityIdx >= 0 && cityIdx < mapData.belongs.length)
                ? (mapData.belongs[cityIdx] & 0xFF) : 0xFF;
        TextView ownHead = new TextView(this);
        ownHead.setText("国家归属：当前 = " + legionName(curLeg));
        ownHead.setTextSize(13);
        ownHead.setTextColor(0xFFe5e7eb);
        ownHead.setTypeface(null, android.graphics.Typeface.BOLD);
        ownHead.setPadding(8, 8, 8, 2);
        target.addView(ownHead);
        if (mapData.legions != null && !mapData.legions.isEmpty()) {
            ensureFlagIcons();
            HorizontalScrollView ownHsv = new HorizontalScrollView(this);
            ownHsv.setHorizontalScrollBarEnabled(false);
            ownHsv.setLayoutParams(new LinearLayout.LayoutParams(-1, 64 * density));
            LinearLayout ownRow = new LinearLayout(this);
            ownRow.setOrientation(LinearLayout.HORIZONTAL);
            ownRow.setGravity(Gravity.CENTER_VERTICAL);
            addCountryCell(ownRow, 0xFF, null, "中立", curLeg, () -> {
                mapData.ensureProvincesSize();
                mapData.belongs[cityIdx] = (byte) 0xFF;
                FileParser.patchBelong(mapData, cityIdx);
                hexMapView.refresh();
                refresh.run();
                Toast.makeText(this, "已把城市 (" + b.x + "," + b.y + ") 归属设为：中立",
                        Toast.LENGTH_SHORT).show();
            });
            for (int li = 0; li < mapData.legions.size(); li++) {
                final int legion = li;
                final MapData.Legion lg = mapData.legions.get(li);
                Bitmap flag = flagIcons != null ? flagIcons.get(lg.country) : null;
                addCountryCell(ownRow, legion, flag, CountryData.name(lg.country), curLeg, () -> {
                    mapData.ensureProvincesSize();
                    mapData.belongs[cityIdx] = (byte) legion;
                    FileParser.patchBelong(mapData, cityIdx);
                    // 同步省代表格归属，让整个省跟随该城市国家
                    int pv = mapData.provinces[cityIdx];
                    if (pv != 0 && pv != 0xFFFF && pv >= 0 && pv < mapData.belongs.length
                            && pv != cityIdx) {
                        mapData.belongs[pv] = (byte) legion;
                        FileParser.patchBelong(mapData, pv);
                    }
                    hexMapView.refresh();
                    refresh.run();
                    Toast.makeText(this, "已把城市 (" + b.x + "," + b.y + ") 归属设为："
                            + CountryData.name(lg.country), Toast.LENGTH_SHORT).show();
                });
            }
            ownHsv.addView(ownRow);
            target.addView(ownHsv);
            TextView ownHint = new TextView(this);
            ownHint.setText("归属决定该省/城市的国家颜色；中立=无归属");
            ownHint.setTextSize(11);
            ownHint.setTextColor(0xFF9ca3af);
            ownHint.setPadding(8, 0, 8, 4);
            target.addView(ownHint);
        }
    }

    /** 归属选择单元格：国旗 + 国家名文字，当前归属高亮边框。 */
    private void addCountryCell(final LinearLayout row, final int legion, final Bitmap flag,
                                final String label, final int curLeg, final Runnable onClick) {
        final int density = (int) getResources().getDisplayMetrics().density;
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER);
        cell.setPadding(6 * density, 4 * density, 6 * density, 4 * density);
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setColor(legion == curLeg ? 0xFF1e3a5f : 0xFF232936);
        gd.setCornerRadius(8 * density);
        gd.setStroke(2 * density, legion == curLeg ? 0xFF6f9bff : 0xFF3a4356);
        cell.setBackground(gd);
        cell.setOnClickListener(v -> onClick.run());
        if (flag != null) {
            ImageView fv = new ImageView(this);
            fv.setImageBitmap(flag);
            fv.setLayoutParams(new LinearLayout.LayoutParams(44 * density, 30 * density));
            cell.addView(fv);
        } else {
            TextView noFlag = new TextView(this);
            noFlag.setText("◻");
            noFlag.setTextSize(20);
            noFlag.setTextColor(0xFF9aa3b5);
            noFlag.setGravity(Gravity.CENTER);
            noFlag.setLayoutParams(new LinearLayout.LayoutParams(44 * density, 30 * density));
            cell.addView(noFlag);
        }
        TextView nm = new TextView(this);
        nm.setText(label);
        nm.setTextSize(10);
        nm.setTextColor(0xFFe5e7eb);
        nm.setGravity(Gravity.CENTER);
        nm.setSingleLine(true);
        cell.addView(nm);
        row.addView(cell);
        View sp = new View(this);
        sp.setLayoutParams(new LinearLayout.LayoutParams(4 * density, 1));
        row.addView(sp);
    }

    /** 在“城市”页直接展示/编辑“建筑32”记录的全部字段（输入框形式）。 */
    private void rebuildCityEditor() {
        if (cityScroll == null) return;
        cityScroll.removeAllViews();
        int sx = hexMapView != null ? hexMapView.getSelectedX() : -1;
        int sy = hexMapView != null ? hexMapView.getSelectedY() : -1;
        if (selectedBuilding == null || selectedBuilding.raw == null) {
            TextView hint = new TextView(this);
            if (sx >= 0 && mapData != null && mapData.getBuildingId(sx, sy) > 0) {
                hint.setText("该建筑是新放置的，保存地图后可在此编辑");
            } else {
                hint.setText("请先在地图上点击一个有建筑/城市的格子");
            }
            hint.setTextSize(12);
            hint.setTextColor(0xFF9ca3af);
            hint.setPadding(8, 12, 8, 12);
            cityScroll.addView(hint);
            return;
        }
        final MapData.Building b = selectedBuilding;
        TextView head = new TextView(this);
        head.setText("建筑32 记录（" + buildingTypeName(b.type) + " (" + b.x + "," + b.y + ")）"
                + provinceInfoText(b.y * mapData.width + b.x));
        head.setTextSize(12);
        head.setTextColor(0xFF1f2937);
        head.setTypeface(null, android.graphics.Typeface.BOLD);
        head.setPadding(8, 6, 8, 6);
        cityScroll.addView(head);

        // 建筑图标（类型/外观/首都）可视化选择，仿枭雄建筑图集映射
        final int iconDensity = (int) getResources().getDisplayMetrics().density;
        LinearLayout iconRowBox = new LinearLayout(this);
        iconRowBox.setOrientation(LinearLayout.HORIZONTAL);
        iconRowBox.setGravity(Gravity.CENTER_VERTICAL);
        iconRowBox.setPadding(8, 4, 8, 4);
        final ImageView iconPreview = new ImageView(this);
        iconPreview.setLayoutParams(new LinearLayout.LayoutParams(64 * iconDensity, 52 * iconDensity));
        iconPreview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        Bitmap curIcon = loadBmp("building/"
                + HexMapView.buildingImageName(b.raw[4] & 0xFF, b.raw[5] & 0xFF) + ".webp");
        if (curIcon != null) iconPreview.setImageBitmap(curIcon);
        iconRowBox.addView(iconPreview);
        TextView iconLabel = new TextView(this);
        iconLabel.setText("类型 " + (b.raw[4] & 0xFF) + " · 外观 " + (b.raw[5] & 0xFF)
                + "\n（含首都图标）");
        iconLabel.setTextSize(11);
        iconLabel.setTextColor(0xFF9ca3af);
        iconLabel.setPadding(10, 0, 0, 0);
        iconRowBox.addView(iconLabel, new LinearLayout.LayoutParams(0, -2, 1f));
        Button pickIcon = new Button(this);
        pickIcon.setText("选择图标");
        pickIcon.setTextSize(11);
        pickIcon.setTextColor(Color.WHITE);
        pickIcon.setBackgroundColor(Color.parseColor("#7c3aed"));
        pickIcon.setOnClickListener(v -> showBuildingIconPicker(b));
        iconRowBox.addView(pickIcon);
        cityScroll.addView(iconRowBox);

        // 国家/军团归属：给这个城市设置或更改所属军团（0xFF=中立）
        addCityOwnershipRow(cityScroll, b, this::rebuildCityEditor);

        Button saveBtn = new Button(this);
        saveBtn.setText("保存修改");
        saveBtn.setTextSize(12);
        saveBtn.setTextColor(Color.WHITE);
        saveBtn.setBackgroundColor(Color.parseColor("#22c55e"));
        saveBtn.setOnClickListener(v -> {
            byte[] raw = b.raw.clone();
            if (buildingEds != null) {
                for (int i = 0; i < BUILDING_FIELDS.length && i < buildingEds.length; i++) {
                    try {
                        writeBuildingField(raw, Integer.decode(BUILDING_FIELDS[i][2]),
                                BUILDING_FIELDS[i][1], buildingEds[i].getText().toString());
                    } catch (Exception ignored) {
                    }
                }
            }
            try {
                history.save(mapData, "修改城市");
                FileParser.patchBuilding(mapData, b, raw);
                hexMapView.refresh();
                updateInfo();
                Toast.makeText(this, "城市已更新", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "保存失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
        cityScroll.addView(saveBtn);

        buildingEds = new EditText[BUILDING_FIELDS.length];
        for (int i = 0; i < BUILDING_FIELDS.length; i++) {
            final String fname = BUILDING_FIELDS[i][0];
            final String ftype = BUILDING_FIELDS[i][1];
            final int off = Integer.decode(BUILDING_FIELDS[i][2]);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, 2, 0, 2);
            TextView label = new TextView(this);
            label.setText(fname);
            label.setTextSize(12);
            label.setTextColor(0xFF374151);
            label.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
            row.addView(label);
            EditText et = new EditText(this);
            et.setInputType(InputType.TYPE_CLASS_NUMBER);
            et.setText(readBuildingField(b.raw, off, ftype));
            et.setLayoutParams(new LinearLayout.LayoutParams(120, -2));
            buildingEds[i] = et;
            row.addView(et);
            cityScroll.addView(row);
        }
    }

    /** 数据面板“城市”页：选中有建筑的地块后，显示/修改建筑32 记录与归属（仅在面板范围内）。 */
    private void rebuildDataCityEditor() {
        if (dataCityContent == null) return;
        dataCityContent.removeAllViews();
        if (selectedBuilding == null || selectedBuilding.raw == null) {
            TextView hint = new TextView(this);
            hint.setText("请先在地图上点击一个有建筑/城市的格子");
            hint.setTextSize(12);
            hint.setTextColor(0xFF9ca3af);
            hint.setPadding(4, 10, 4, 10);
            dataCityContent.addView(hint);
            return;
        }
        final int density = (int) getResources().getDisplayMetrics().density;
        final MapData.Building b = selectedBuilding;
        TextView head = new TextView(this);
        head.setText(buildingTypeName(b.type) + (b.index < 0 ? "（新放置）" : ""));
        head.setTextSize(12);
        head.setTextColor(0xFFe5e7eb);
        head.setTypeface(null, android.graphics.Typeface.BOLD);
        head.setPadding(2, 4, 2, 6);
        dataCityContent.addView(head);

        // 国家/军团归属
        addCityOwnershipRow(dataCityContent, b, this::rebuildDataCityEditor);

        Button saveBtn = new Button(this);
        saveBtn.setText("保存修改");
        saveBtn.setTextSize(12);
        saveBtn.setTextColor(Color.WHITE);
        saveBtn.setBackgroundColor(Color.parseColor("#22c55e"));
        saveBtn.setOnClickListener(v -> {
            byte[] raw = b.raw.clone();
            if (dataCityEds != null) {
                for (int i = 0; i < BUILDING_FIELDS.length && i < dataCityEds.length; i++) {
                    if (i == 0) continue; // 坐标固定，不显示不修改
                    try {
                        if (dataCitySpinners != null && dataCitySpinners[i] != null) {
                            writeBuildingField(raw, Integer.decode(BUILDING_FIELDS[i][2]),
                                    BUILDING_FIELDS[i][1],
                                    String.valueOf(dataCitySpinners[i].getSelectedItemPosition()));
                        } else if (dataCityEds[i] != null) {
                            writeBuildingField(raw, Integer.decode(BUILDING_FIELDS[i][2]),
                                    BUILDING_FIELDS[i][1], dataCityEds[i].getText().toString());
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
            try {
                if (b.index < 0) {
                    // 新放置建筑：保存草稿，保存地图时写入文件
                    history.save(mapData, "修改建筑");
                    b.raw = raw;
                    mapData.newBuildingRaws.put(b.coord, raw.clone());
                    hexMapView.refresh();
                    updateInfo();
                    Toast.makeText(this, "已保存（保存地图时写入文件）", Toast.LENGTH_SHORT).show();
                } else {
                    history.save(mapData, "修改建筑");
                    FileParser.patchBuilding(mapData, b, raw);
                    hexMapView.refresh();
                    updateInfo();
                    Toast.makeText(this, "城市已更新", Toast.LENGTH_SHORT).show();
                }
            } catch (Exception e) {
                Toast.makeText(this, "保存失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
        dataCityContent.addView(saveBtn);

        dataCityEds = new EditText[BUILDING_FIELDS.length];
        dataCitySpinners = new android.widget.Spinner[BUILDING_FIELDS.length];
        for (int i = 0; i < BUILDING_FIELDS.length; i++) {
            if (i == 0) continue; // 坐标：删除，不再显示
            final String fname = BUILDING_FIELDS[i][0];
            final String ftype = BUILDING_FIELDS[i][1];
            final int off = Integer.decode(BUILDING_FIELDS[i][2]);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(0, 2 * density, 0, 2 * density);
            TextView label = new TextView(this);
            label.setText(fname);
            label.setTextSize(11);
            label.setTextColor(0xFFd1d5db);
            row.addView(label);
            int value;
            try {
                value = Integer.parseInt(readBuildingField(b.raw, off, ftype).trim());
            } catch (Exception ex) {
                value = 0;
            }
            if ("据点(0无1红2绿)".equals(fname)) {
                // 据点：下拉单选框 0无 / 1红 / 2绿
                dataCitySpinners[i] = makeSpinner(new String[]{"0 无", "1 红", "2 绿"},
                        Math.max(0, Math.min(2, value)));
                row.addView(dataCitySpinners[i]);
            } else {
                EditText et = new EditText(this);
                et.setInputType(InputType.TYPE_CLASS_NUMBER);
                et.setText(String.valueOf(value));
                et.setTextColor(0xFFe5e7eb);
                et.setTextSize(12);
                et.setBackgroundColor(Color.parseColor("#2a2a2f"));
                et.setPadding(6 * density, 4 * density, 6 * density, 4 * density);
                dataCityEds[i] = et;
                row.addView(et);
            }
            dataCityContent.addView(row);
        }
    }

    /** 数据面板“地雷”页：添加地雷、点选编辑等级/军团/血量、删除。 */
    private void rebuildMineEditor() {
        if (dataMineContent == null) return;
        dataMineContent.removeAllViews();
        final int density = (int) getResources().getDisplayMetrics().density;

        Button addBtn = new Button(this);
        addBtn.setText(addingMine ? "取消添加地雷" : "＋ 添加地雷（点地图放置）");
        addBtn.setTextSize(12);
        addBtn.setTextColor(Color.WHITE);
        addBtn.setAllCaps(false);
        addBtn.setBackgroundColor(Color.parseColor(addingMine ? "#e11d48" : "#1e5fa8"));
        addBtn.setOnClickListener(v -> {
            addingMine = !addingMine;
            if (addingMine) {
                closeProvinceEdit();
                Toast.makeText(this, "添加地雷模式：点击地图地块放置（等级1，血量60）",
                        Toast.LENGTH_SHORT).show();
            }
            rebuildMineEditor();
            hexMapView.refresh();
        });
        dataMineContent.addView(addBtn);

        TextView count = new TextView(this);
        count.setText("当前地雷 " + (mapData != null && mapData.traps != null
                ? mapData.traps.size() : 0) + " 个"
                + (addingMine ? "　添加模式：点地图地块放置" : ""));
        count.setTextSize(11);
        count.setTextColor(0xFF9ca3af);
        count.setPadding(2, 6, 2, 6);
        dataMineContent.addView(count);

        if (selectedTrap == null || selectedTrap.raw == null) {
            TextView hint = new TextView(this);
            hint.setText("点击地图上的地雷可选中编辑（等级/军团/血量）");
            hint.setTextSize(12);
            hint.setTextColor(0xFF9ca3af);
            hint.setPadding(2, 4, 2, 6);
            dataMineContent.addView(hint);
            return;
        }

        final MapData.Trap t = selectedTrap;
        TextView head = new TextView(this);
        head.setText("地雷 (" + t.x + "," + t.y + ") · 等级" + t.level);
        head.setTextSize(13);
        head.setTextColor(0xFFe5e7eb);
        head.setTypeface(null, android.graphics.Typeface.BOLD);
        head.setPadding(2, 4, 2, 6);
        dataMineContent.addView(head);

        // 所属军团：下拉（中立 + 各军团）
        java.util.List<String> opts = new java.util.ArrayList<>();
        final java.util.List<Integer> vals = new java.util.ArrayList<>();
        opts.add("中立 (FF)");
        vals.add(0xFFFF);
        if (mapData.legions != null) {
            for (int i = 0; i < mapData.legions.size(); i++) {
                opts.add("军团" + (i + 1) + " "
                        + CountryData.name(mapData.legions.get(i).country));
                vals.add(i);
            }
        }
        int sel = 0;
        for (int i = 0; i < vals.size(); i++) {
            if (vals.get(i) == t.legion) sel = i;
        }
        mineLegionSp = makeSpinner(opts.toArray(new String[0]), sel);
        dataMineContent.addView(makeMineRow("所属军团", mineLegionSp, density));

        // 陷阱等级：1~4 下拉
        mineLevelSp = makeSpinner(new String[]{"1", "2", "3", "4"},
                Math.max(0, Math.min(3, t.level - 1)));
        dataMineContent.addView(makeMineRow("陷阱等级", mineLevelSp, density));

        // 陷阱血量：数字输入（官方默认 60×等级）
        mineHpEt = new EditText(this);
        mineHpEt.setInputType(InputType.TYPE_CLASS_NUMBER);
        mineHpEt.setText(String.valueOf(t.hp));
        mineHpEt.setTextColor(0xFFe5e7eb);
        mineHpEt.setTextSize(12);
        mineHpEt.setBackgroundColor(Color.parseColor("#2a2a2f"));
        mineHpEt.setPadding(6 * density, 4 * density, 6 * density, 4 * density);
        dataMineContent.addView(makeMineRow("陷阱血量", mineHpEt, density));

        Button saveBtn = new Button(this);
        saveBtn.setText("保存修改");
        saveBtn.setTextSize(12);
        saveBtn.setTextColor(Color.WHITE);
        saveBtn.setBackgroundColor(Color.parseColor("#22c55e"));
        saveBtn.setOnClickListener(v -> {
            try {
                byte[] raw = t.raw.clone();
                int legion = vals.get(Math.max(0,
                        Math.min(vals.size() - 1, mineLegionSp.getSelectedItemPosition())));
                int level = Math.max(1, Math.min(4,
                        mineLevelSp.getSelectedItemPosition() + 1));
                int hp;
                try {
                    hp = Integer.parseInt(mineHpEt.getText().toString().trim());
                } catch (Exception ex) {
                    hp = level * 60;
                }
                if (hp < 0 || hp > 0xFFFF) hp = level * 60;
                int stored = (t.y * mapData.width + t.x) + mapData.coordBase;
                raw[0] = (byte) (stored & 0xFF);
                raw[1] = (byte) ((stored >>> 8) & 0xFF);
                raw[2] = (byte) (legion & 0xFF);
                raw[4] = (byte) (level & 0xFF);
                raw[6] = (byte) (hp & 0xFF);
                raw[7] = (byte) ((hp >>> 8) & 0xFF);
                history.save(mapData, "修改地雷");
                FileParser.patchTrap(mapData, t, raw);
                t.legion = legion;
                t.level = level;
                t.hp = hp;
                System.arraycopy(raw, 0, t.raw, 0, 12);
                hexMapView.refresh();
                updateInfo();
                Toast.makeText(this, "地雷已更新", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "保存失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
        dataMineContent.addView(saveBtn);

        Button delBtn = new Button(this);
        delBtn.setText("删除这颗地雷");
        delBtn.setTextSize(12);
        delBtn.setTextColor(Color.WHITE);
        delBtn.setBackgroundColor(Color.parseColor("#e11d48"));
        delBtn.setOnClickListener(v -> {
            try {
                history.save(mapData, "删除地雷");
                FileParser.removeMine(mapData, t);
                selectedTrap = null;
                lastEditedTrap = null;
                hexMapView.refresh();
                updateInfo();
                rebuildMineEditor();
                Toast.makeText(this, "地雷已删除", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "删除失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
        dataMineContent.addView(delBtn);
    }

    /** 数据面板内的“标签 + 控件”竖排行。 */
    private LinearLayout makeMineRow(String label, View control, int density) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, 3 * density, 0, 3 * density);
        TextView lb = new TextView(this);
        lb.setText(label);
        lb.setTextSize(11);
        lb.setTextColor(0xFFd1d5db);
        row.addView(lb);
        row.addView(control);
        return row;
    }

    private static int readArmyField(byte[] raw, int off, String type) {
        int need = type.equals("u8") ? 1 : type.equals("u16") ? 2 : 4;
        if (raw == null || off < 0 || off + need > raw.length) return 0;
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(raw).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        switch (type) {
            case "u8": return raw[off] & 0xFF;
            case "u16": return bb.getShort(off) & 0xFFFF;
            default: return bb.getInt(off);
        }
    }

    private static void writeArmyField(byte[] raw, int off, String type, int v) {
        int need = type.equals("u8") ? 1 : type.equals("u16") ? 2 : 4;
        if (raw == null || off < 0 || off + need > raw.length) return;
        switch (type) {
            case "u8": raw[off] = (byte) (v & 0xFF); break;
            case "u16": raw[off] = (byte) (v & 0xFF); raw[off + 1] = (byte) ((v >> 8) & 0xFF); break;
            default:
                raw[off] = (byte) (v & 0xFF);
                raw[off + 1] = (byte) ((v >> 8) & 0xFF);
                raw[off + 2] = (byte) ((v >> 16) & 0xFF);
                raw[off + 3] = (byte) ((v >> 24) & 0xFF);
        }
    }

    private Button makeBtn(String t) {
        Button btn = new Button(this);
        btn.setText(t); btn.setTextSize(11); btn.setPadding(8,0,8,0);
        btn.setLayoutParams(new LinearLayout.LayoutParams(0,-1,1));
        btn.setBackgroundColor(Color.parseColor("#2a5a8a")); btn.setTextColor(Color.WHITE); btn.setGravity(Gravity.CENTER);
        return btn;
    }

    /** 左侧浮动按钮：固定高度、自适应宽度、指定底色（配合纵向堆叠使用）。 */
    private Button makeFloatBtn(String t, int bgColor) {
        Button btn = makeBtn(t);
        int h = (int) (40 * getResources().getDisplayMetrics().density);
        btn.setLayoutParams(new LinearLayout.LayoutParams(-1, h));
        btn.setBackgroundColor(bgColor);
        return btn;
    }

    private void updateBtnState() { if (undoBtn != null) undoBtn.setAlpha(history.canUndo()?1f:0.4f); if (redoBtn != null) redoBtn.setAlpha(history.canRedo()?1f:0.4f); }

    private void updateTerrainSelection(int selectedRowIdx) {
        for (int i = 0; i < terrainRows.size(); i++) {
            LinearLayout row = terrainRows.get(i);
            if (i == selectedRowIdx) {
                // 选中：亮黄色边框 + 浅黄背景
                row.setBackgroundColor(0x33FFD700);
                row.setPadding(8, 4, 8, 4);
                // 画边框：用一个带边框的 drawable
                android.graphics.drawable.GradientDrawable border = new android.graphics.drawable.GradientDrawable();
                border.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
                border.setStroke(4, 0xFFFFD700);
                border.setColor(0x33FFD700);
                row.setBackground(border);
            } else {
                // 未选中：无边框
                row.setBackgroundColor(0x00ffffff);
                row.setPadding(8, 4, 8, 4);
                android.graphics.drawable.GradientDrawable border = new android.graphics.drawable.GradientDrawable();
                border.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
                border.setStroke(0, 0x00000000);
                border.setColor(0x00ffffff);
                row.setBackground(border);
            }
        }
    }

    @Override public void onTileSelected(int x, int y, TerrainTile tile) {
        playSelectSfx();
        if (addingArmy && pendingArmyType != null && mapData != null) {
            try {
                history.save(mapData, "放置兵种");
                byte[] raw = buildNewArmyRaw(x, y, pendingArmyType);
                String name = pendingArmyType.name;
                FileParser.addArmy(mapData, x, y, pendingArmyType.army, raw, pendingArmyLegion);
                addingArmy = false;
                pendingArmyType = null;
                pendingArmyLegion = -1;
                hexMapView.refresh();
                updateInfo();
                Toast.makeText(this, "已放置 " + name, Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                Toast.makeText(this, "放置失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
            return;
        }
        // 添加地雷模式：点击地块放置地雷（等级1，血量60，归属跟随地块）
        if (addingMine && mapData != null && mapData.btlOriginalData != null) {
            try {
                history.save(mapData, "放置地雷");
                int legion = tileOwnershipLegion(x, y);
                FileParser.addMine(mapData, x, y, legion);
                addingMine = false;
                hexMapView.refresh();
                updateInfo();
                rebuildMineEditor();
                Toast.makeText(this, "已放置地雷（等级1，血量60），可在「地雷」页修改",
                        Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                Toast.makeText(this, "放置地雷失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
            return;
        }
        if (cropState != CROP_NONE && mapData != null) {
            if (cropState == CROP_START) {
                cropX1 = x;
                cropY1 = y;
                cropState = CROP_END;
                updateCropUI();
                Toast.makeText(this, "起点 (" + x + "," + y + ")，请点击终点格子", Toast.LENGTH_LONG).show();
                return;
            } else if (cropState == CROP_END) {
                cropX2 = x;
                cropY2 = y;
                cropState = CROP_FRAMED;
                hexMapView.setCropRect(cropX1, cropY1, cropX2, cropY2);
                hexMapView.refresh();
                updateCropUI();
                Toast.makeText(this, "已框选区域，点击“确认截取”保存，或双击右侧地形取消",
                        Toast.LENGTH_LONG).show();
                return;
            } else {
                return; // 已框选：等待确认/取消
            }
        }
        // 取省模式：点击格子读取其省区（不绘制、不新建）
        if (mapData != null && provinceTakeMode) {
            mapData.ensureProvincesSize();
            int pv = mapData.provinces[y * mapData.width + x];
            if (pv == 0 || pv == 0xFFFF) {
                Toast.makeText(this, "该格没有省区（0/未设置）", Toast.LENGTH_SHORT).show();
                return;
            }
            mapData.provinceBrushSeed = pv;
            provinceTakeMode = false;
            mapData.provinceEditMode = true;
            refreshProvinceEditUi();
            rebuildProvinceList();
            if (provinceHint != null) {
                provinceHint.setText("已取省（值 " + pv + "），点「刷省」或按住拖动划入");
            }
            hexMapView.refresh();
            Toast.makeText(this, "已取省区 " + pv, Toast.LENGTH_SHORT).show();
            return;
        }
        // 省区编辑：未选代表格时，点击的格子成为新省区代表格
        if (mapData != null && mapData.provinceEditMode && mapData.provinceBrushSeed < 0) {
            int idx = y * mapData.width + x;
            mapData.ensureProvincesSize();
            mapData.saveProvinceUndo();
            mapData.provinces[idx] = idx;
            mapData.provinceBrushSeed = idx;
            FileParser.patchProvince(mapData, idx);
            mapData.editedCells.add(idx);
            hexMapView.refresh();
            rebuildProvinceList();
            refreshProvinceEditUi();
            if (provinceHint != null) {
                provinceHint.setText("已新建省区 (" + x + "," + y + ")，点地图其他地块划入；点开关可关闭");
            }
            Toast.makeText(this, "已建立省区（代表格 " + x + "," + y + "），点击其他地块划入该省区",
                    Toast.LENGTH_LONG).show();
            return;
        }
        // 锁定的兵种：点击地块连续添加（归属跟随地块）
        if (mapData != null && mapData.selectedArmyType >= 0
                && mapData.btlOriginalData != null) {
            ArmyConfig cfg = ArmyConfig.byArmy(mapData.selectedArmyType);
            if (cfg == null) {
                mapData.selectedArmyType = -1;
                highlightArmyIcons();
            } else {
                try {
                    history.save(mapData, "放置兵种");
                    int legion = tileOwnershipLegion(x, y);
                    byte[] raw = buildNewArmyRaw(x, y, cfg);
                    FileParser.addArmy(mapData, x, y, cfg.army, raw, legion);
                    hexMapView.refresh();
                    updateInfo();
                    Toast.makeText(this, "已放置 " + cfg.name + "（军团" + (legion + 1)
                            + "），可继续点击其他地块",
                            Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    Toast.makeText(this, "放置失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            }
            return;
        }
        updateInfo();
    }

    /** 地块归属军团：优先按格归属，中立格用省份归属兜底，都没有归第一军团。 */
    private int tileOwnershipLegion(int x, int y) {
        if (mapData == null) return 0;
        int idx = y * mapData.width + x;
        // 省区归属优先：该格所在省的代表格归属（省区决定国家）
        if (mapData.provinces != null && idx < mapData.provinces.length) {
            int p = mapData.provinces[idx];
            if (p != 0 && p != 0xFFFF && p < mapData.getTotalTiles()
                    && mapData.belongs != null && p < mapData.belongs.length) {
                int leg = mapData.belongs[p] & 0xFF;
                if (leg != 0xFF && leg < mapData.legionColors.length) return leg;
            }
        }
        // 兜底：地块自身归属
        if (mapData.belongs != null && idx < mapData.belongs.length) {
            int leg = mapData.belongs[idx] & 0xFF;
            if (leg != 0xFF && leg < mapData.legionColors.length) return leg;
        }
        return 0;
    }

    /** 新兵种的默认 48 字节记录：坐标 + 兵种 + 等级1 + 编制1 + 基础数值。 */
    private byte[] buildNewArmyRaw(int x, int y, ArmyConfig cfg) {
        byte[] raw = new byte[48];
        int coord = y * mapData.width + x + (mapData.coordBase != 0 ? mapData.coordBase : 0);
        raw[0] = (byte) (coord & 0xFF);
        raw[1] = (byte) ((coord >> 8) & 0xFF);
        raw[2] = (byte) cfg.army;   // 兵种
        raw[3] = 1;                 // 等级
        raw[4] = 1;                 // 编制
        raw[6] = (byte) Math.min(255, cfg.mobility); // 移动力
        raw[0xA] = (byte) 100;                        // 血量加成（与真实记录一致）
        raw[0xC] = (byte) (cfg.hp & 0xFF);           // 当前血量
        raw[0xD] = (byte) ((cfg.hp >> 8) & 0xFF);
        raw[0xE] = (byte) (cfg.hp & 0xFF);           // 血量上限
        raw[0xF] = (byte) ((cfg.hp >> 8) & 0xFF);
        return raw;
    }

    /** 兵种笔刷：涂抹时逐个添加兵种（归属跟随地块；已有兵种的格子跳过）。 */
    private void addArmyAtBrush(int x, int y) {
        if (mapData == null || mapData.selectedArmyType < 0 || mapData.btlOriginalData == null) return;
        if (mapData.armies != null) {
            for (MapData.Army a : mapData.armies) {
                if (a.x == x && a.y == y) return; // 该格已有兵种，跳过
            }
        }
        ArmyConfig cfg = ArmyConfig.byArmy(mapData.selectedArmyType);
        if (cfg == null) return;
        try {
            if (System.currentTimeMillis() - lastBrushHistorySave > 800) {
                history.save(mapData, "笔刷放置兵种");
                lastBrushHistorySave = System.currentTimeMillis();
            }
            byte[] raw = buildNewArmyRaw(x, y, cfg);
            int idx = y * mapData.width + x;
            int leg = tileOwnershipLegion(x, y); // 归属跟随省区（省代表格归属）
            FileParser.addArmy(mapData, x, y, cfg.army, raw, leg);
        } catch (Exception ignored) {
        }
        hexMapView.refresh();
    }

    private void updateInfo() {
        if (mapData == null) return;
        if (fileInfoView != null) fileInfoView.setText("文件: " + currentFileName);
        int sx = hexMapView.getSelectedX(), sy = hexMapView.getSelectedY();
        int selProvince = -1;
        if (sx >= 0) {
            TerrainTile t = mapData.getTile(sx, sy);
            int bid = mapData.getBuildingId(sx, sy);
            int idx = sy * mapData.width + sx;
            if (mapData.provinces != null && idx >= 0 && idx < mapData.provinces.length) {
                selProvince = mapData.provinces[idx];
            }
            blockIdText.setText(String.format("地块 #%d", idx));
            String info = String.format("ID: %d (G=%d, Id=%d)", idx, t.bmTerrain1Group, t.bmTerrain1Id);
            info += provinceInfoText(idx);
            selectedArmy = null;
            if (mapData.armies != null) {
                for (MapData.Army a : mapData.armies) {
                    if (a.x == sx && a.y == sy) {
                        selectedArmy = a;
                        info += "  " + (a.name != null ? a.name : "兵种" + a.type) + " Lv" + a.level;
                        ArmyConfig cfg = ArmyConfig.byArmy(a.type);
                        if (cfg != null) {
                            info += "\n" + cfg.summary()
                                    + "  编" + cfg.maxFormation
                                    + " 造" + cfg.costMoney + "/" + cfg.costGear + "/" + cfg.costAtomic;
                        }
                        break;
                    }
                }
            }
            // 关联高亮：选中兵种时高亮同编制单位 + 所在省
            if (selectedArmy != null) {
                int f = (selectedArmy.raw != null && selectedArmy.raw.length > 4)
                        ? (selectedArmy.raw[4] & 0xFF) : 1;
                hexMapView.setHighlightFormation(f);
                info += "\n编制 " + f + "：地图上同编制单位已高亮";
            } else {
                hexMapView.setHighlightFormation(-1);
            }
            hexMapView.setHighlightProvince(selProvince);
            selectedInfo.setText(info);
        } else {
            selectedArmy = null;
            hexMapView.clearHighlights();
        }
        // 同步选中的城市/建筑记录（新放置的建筑也允许编辑）
        selectedBuilding = null;
        if (mapData != null && mapData.buildings != null) {
            if (sx >= 0) {
                for (MapData.Building b : mapData.buildings) {
                    if (b.x == sx && b.y == sy) {
                        selectedBuilding = b;
                        break;
                    }
                }
            }
            if (selectedBuilding == null && sx >= 0
                    && mapData.getBuildingId(sx, sy) > 0) {
                // 新放置的建筑：生成可编辑草稿（保存地图时写入文件）
                if (draftBuilding == null || draftBuilding.x != sx || draftBuilding.y != sy) {
                    draftBuilding = new MapData.Building();
                    draftBuilding.index = -1;
                    draftBuilding.x = sx;
                    draftBuilding.y = sy;
                    draftBuilding.coord = sy * mapData.width + sx;
                    draftBuilding.type = mapData.getBuildingId(sx, sy);
                    draftBuilding.raw = new byte[32];
                    int stored = draftBuilding.coord + mapData.coordBase;
                    draftBuilding.raw[0] = (byte) (stored & 0xFF);
                    draftBuilding.raw[1] = (byte) ((stored >>> 8) & 0xFF);
                    draftBuilding.raw[4] = (byte) draftBuilding.type;
                }
                selectedBuilding = draftBuilding;
            }
            if (selectedBuilding != null) {
                // 关联高亮：建筑所在省整片描边
                hexMapView.setHighlightFormation(-1);
                hexMapView.setHighlightProvince(selProvince);
            }
        }
        if (selectedArmy != lastEditedArmy) {
            lastEditedArmy = selectedArmy;
            rebuildArmyEditor();
        }
        if (selectedBuilding != lastEditedBuilding) {
            lastEditedBuilding = selectedBuilding;
            rebuildCityEditor();
            rebuildDataCityEditor();
        }
        // 选中的地雷：点击有地雷的地块后，在“地雷”页编辑
        selectedTrap = null;
        if (mapData != null && mapData.traps != null && sx >= 0) {
            for (MapData.Trap t : mapData.traps) {
                if (t.x == sx && t.y == sy) {
                    selectedTrap = t;
                    break;
                }
            }
        }
        if (selectedTrap != lastEditedTrap) {
            lastEditedTrap = selectedTrap;
            rebuildMineEditor();
        }
        int total = mapData.getTotalTiles();
        mapInfo.setText(String.format(" %dx%d %d格 %d%% %d", mapData.width, mapData.height, total, total>0?mapData.getWaterCount()*100/total:0, mapData.getBuildingCount()));
    }

    /** 省区与归属文本（用于地块信息/城市页显示）。 */
    private String provinceInfoText(int cellIdx) {
        if (mapData == null || mapData.provinces == null
                || cellIdx < 0 || cellIdx >= mapData.provinces.length) return "";
        int pv = mapData.provinces[cellIdx];
        if (pv == 0 || pv == 0xFFFF) return "\n省区：无（中立地块）";
        int leg = (mapData.belongs != null && pv < mapData.belongs.length)
                ? (mapData.belongs[pv] & 0xFF) : 0xFF;
        String own = (leg != 0xFF && mapData.legionCountries != null && leg < mapData.legionCountries.length)
                ? ("军团" + (leg + 1) + "（" + CountryData.name(mapData.legionCountries[leg]) + "）")
                : "中立";
        return "\n省区：#" + pv + " · 归属：" + own;
    }

    private String getBName(int id) {
        String[] n = {"","农场","风车","小镇","","","","","","","","小城市","中城市","大城市","大都市","首都1","首都2","首都3","首都4","首都5","","炼油厂","大工厂","核电站"};
        if (id>=0&&id<n.length&&!n[id].isEmpty()) return n[id];
        if (id==41) return "机场"; if (id==42) return "要塞"; if (id==43) return "堡垒"; if (id==44) return "据点"; if (id==45) return "工厂";
        return "建筑"+id;
    }

    /** 军团序号 -> 显示名（0xFF=中立）。 */
    private String legionName(int leg) {
        if (leg == 0xFF || mapData == null || mapData.legions == null
                || leg < 0 || leg >= mapData.legions.size()) {
            return "中立";
        }
        return "军团" + (leg + 1) + " " + CountryData.name(mapData.legions.get(leg).country);
    }

    /** 新建标准 BTL 战役。MapData 不附带原文件，保存时会由 FileParser 写出完整基础 BTL。 */
    private void newBtlMap() {
        AlertDialog.Builder b = new AlertDialog.Builder(this, R.style.DarkDialog);
        b.setTitle("新建战役 BTL");
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(40, 20, 40, 20);
        EditText wi = new EditText(this);
        wi.setHint("宽度（3–200）"); wi.setInputType(InputType.TYPE_CLASS_NUMBER); wi.setText("20");
        EditText hi = new EditText(this);
        hi.setHint("高度（3–200）"); hi.setInputType(InputType.TYPE_CLASS_NUMBER); hi.setText("15");
        l.addView(wi); l.addView(hi);

        // 整图地形单选框（与右侧面板地形列表一致）
        final int[] gids = {0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,18,20,21,22,26,30,31};
        final String[] tns = {"空地（平原）","海洋","沙漠","矮雪山","中雪山","高雪山",
                "矮土山","中土山","高土山","矮绿山","中绿山","高绿山",
                "矮沙山","中沙山","高沙山","仙人掌","阔叶林","积雪阔叶林",
                "针叶林","积雪针叶林","热带森林","农田","坑","雪地"};
        final RadioGroup terrainChoice = new RadioGroup(this);
        final RadioButton[] terrainBtns = new RadioButton[gids.length];
        for (int i = 0; i < gids.length; i++) {
            terrainBtns[i] = new RadioButton(this);
            terrainBtns[i].setText(tns[i]);
            terrainBtns[i].setId(i + 1);
            if (i == 0) terrainBtns[i].setChecked(true);
            terrainChoice.addView(terrainBtns[i]);
        }
        ScrollView terrainScrollBox = new ScrollView(this);
        int maxH = (int) (300 * getResources().getDisplayMetrics().density);
        terrainScrollBox.setLayoutParams(new LinearLayout.LayoutParams(-1, maxH));
        terrainScrollBox.addView(terrainChoice);
        l.addView(terrainScrollBox);
        b.setView(l);
        b.setPositiveButton("创建", (d, w) -> {
            try {
                int wv = Integer.parseInt(wi.getText().toString());
                int hv = Integer.parseInt(hi.getText().toString());
                // BTL 内的地块坐标为 uint16；200×200 也在安全范围内。
                if (wv < 3 || wv > 200 || hv < 3 || hv > 200) {
                    Toast.makeText(this, "宽高范围为 3–200", Toast.LENGTH_SHORT).show();
                    return;
                }
                // 使用用户验证可正常进入游戏的战役模板；其头部中含有未公开的固定字段。
                byte[] template = readAssetBytes("templates/stage10103.btl");
                mapData = FileParser.createEmptyBtlFromTemplate(template,
                    "新战役_" + wv + "x" + hv + ".btl", wv, hv);
                int checkedId = terrainChoice.getCheckedRadioButtonId();
                int sel = 0;
                for (int i = 0; i < terrainBtns.length; i++) {
                    if (terrainBtns[i].getId() == checkedId) sel = i;
                }
                TerrainTile fill = makeFillTileByGroup(gids[sel]);
                for (int i = 0; i < mapData.tiles.size(); i++) {
                    mapData.tiles.set(i, cloneTerrain(fill));
                }
                currentFileName = "新战役_" + wv + "x" + hv + ".btl";
                history.clear();
                mapData.historyRef = history;
                hexMapView.setMapData(mapData);
                updateInfo();
                blockIdText.setText("未选中");
                selectedInfo.setText("已创建空白" + tns[sel] + "战役；编辑后直接保存为 .btl");
                enterEditorAfterLoad();
            } catch (Exception e) {
                Toast.makeText(this, "新建失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
        b.setNegativeButton("取消", null);
        showDarkDialog(b, l);
    }

    private byte[] readAssetBytes(String assetName) throws IOException {
        InputStream input = getAssets().open(assetName);
        try {
            byte[] bytes = new byte[input.available()];
            int offset = 0;
            while (offset < bytes.length) {
                int read = input.read(bytes, offset, bytes.length - offset);
                if (read < 0) break;
                offset += read;
            }
            if (offset != bytes.length) throw new IOException("模板读取不完整");
            return bytes;
        } finally {
            input.close();
        }
    }

    // ===== 随机化地形（在现有地图上按概率替换） =====
    private void randomizeTerrainDialog() {
        if (mapData == null) {
            Toast.makeText(this, "请先加载地图", Toast.LENGTH_SHORT).show();
            return;
        }

        // 地形选项列表
        int[][] terrainDefs = {
            {0, 0xFFf0f0f0, 0},  // 平地
            {2, 0xFFf4e7c3, 0},  // 沙漠
            {3, 0xFFe0f7fa, 0},  // 矮雪山
            {4, 0xFFb2ebf2, 0},  // 中雪山
            {5, 0xFF80deea, 0},  // 高雪山
            {6, 0xFF8d6e63, 0},  // 矮土山
            {7, 0xFF6d4c41, 0},  // 中土山
            {8, 0xFF4e342e, 0},  // 高土山
            {9, 0xFF81c784, 0},  // 矮绿山
            {10, 0xFF4caf50, 0}, // 中绿山
            {11, 0xFF388e3c, 0}, // 高绿山
            {12, 0xFFffcc80, 0}, // 矮沙山
            {13, 0xFFffb74d, 0}, // 中沙山
            {14, 0xFFff9800, 0}, // 高沙山
            {15, 0xFF689f38, 0}, // 仙人掌
            {16, 0xFF2e7d32, 0}, // 阔叶林
            {18, 0xFFa5d6a7, 0}, // 积雪阔叶林
            {20, 0xFF1b5e20, 0}, // 针叶林
            {21, 0xFFb2dfdb, 0}, // 积雪针叶林
            {22, 0xFF1b5e20, 0}, // 热带森林
            {26, 0xFFffd54f, 0}, // 农田
            {30, 0xFF795548, 0}, // 坑
            {31, 0xFFffffff, 0}, // 雪地
        };
        String[] terrainNames = {"平地","沙漠","矮雪山","中雪山","高雪山","矮土山","中土山","高土山","矮绿山","中绿山","高绿山","矮沙山","中沙山","高沙山","仙人掌","阔叶林","积雪阔叶林","针叶林","积雪针叶林","热带森林","农田","坑","雪地"};

        AlertDialog.Builder b = new AlertDialog.Builder(this, R.style.DarkDialog);
        b.setTitle("随机化地形");

        ScrollView sv = new ScrollView(this);
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(32, 12, 32, 12);

        // 说明
        TextView infoTv = new TextView(this);
        infoTv.setText("在现有地图上按概率随机替换地形");
        infoTv.setTextSize(11);
        infoTv.setTextColor(0xFF9ca3af);
        l.addView(infoTv);

        // 概率滑块
        final double[] probability = {0.3};
        LinearLayout probRow = new LinearLayout(this);
        probRow.setOrientation(LinearLayout.HORIZONTAL);
        probRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView probLabel = new TextView(this);
        probLabel.setText("概率:");
        probLabel.setTextSize(12);
        probLabel.setTextColor(0xFF374151);
        probRow.addView(probLabel);
        android.widget.SeekBar probSb = new android.widget.SeekBar(this);
        probSb.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        probSb.setMax(100);
        probSb.setProgress(30);
        final TextView probVal = new TextView(this);
        probVal.setText("30%");
        probVal.setTextSize(12);
        probVal.setTextColor(0xFF374151);
        probVal.setMinWidth(40);
        probVal.setGravity(Gravity.CENTER);
        probSb.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar seekBar, int progress, boolean fromUser) {
                probability[0] = progress / 100.0;
                probVal.setText(progress + "%");
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(android.widget.SeekBar seekBar) {}
        });
        probRow.addView(probSb);
        probRow.addView(probVal);
        l.addView(probRow);

        // 快速预设按钮
        int[] presetValues = {0, 10, 25, 50, 75, 100};
        String[] presetLabels = {"0%","10%","25%","50%","75%","100%"};
        LinearLayout presetRow = new LinearLayout(this);
        presetRow.setOrientation(LinearLayout.HORIZONTAL);
        presetRow.setPadding(0, 4, 0, 4);
        for (int pi = 0; pi < presetValues.length; pi++) {
            final int pv = presetValues[pi];
            Button presetBtn = new Button(this);
            presetBtn.setText(presetLabels[pi]);
            presetBtn.setTextSize(10);
            presetBtn.setLayoutParams(new LinearLayout.LayoutParams(0, 32, 1));
            presetBtn.setGravity(Gravity.CENTER);
            presetBtn.setPadding(2, 0, 2, 0);
            presetBtn.setBackgroundColor(0xFFe5e7eb);
            presetBtn.setTextColor(0xFF374151);
            presetBtn.setOnClickListener(v -> {
                probSb.setProgress(pv);
                probability[0] = pv / 100.0;
                probVal.setText(pv + "%");
            });
            presetRow.addView(presetBtn);
            if (pi < presetValues.length - 1) {
                View sp2 = new View(this); sp2.setLayoutParams(new LinearLayout.LayoutParams(4, 1));
                presetRow.addView(sp2);
            }
        }
        l.addView(presetRow);

        // 分隔
        View dv2 = new View(this);
        dv2.setLayoutParams(new LinearLayout.LayoutParams(-1, 1));
        dv2.setBackgroundColor(0xFFe5e7eb);
        l.addView(dv2);

        TextView tv = new TextView(this);
        tv.setText("允许出现的地形（默认全部，海洋除外）:");
        tv.setTextSize(11);
        tv.setPadding(0, 8, 0, 4);
        tv.setTextColor(0xFF374151);
        l.addView(tv);

        boolean[] allowedFlags = new boolean[terrainDefs.length];
        for (int i = 0; i < terrainDefs.length; i++) allowedFlags[i] = true; // 排除海洋

        // 全选/全不选/默认按钮
        LinearLayout toggleRow = new LinearLayout(this);
        toggleRow.setOrientation(LinearLayout.HORIZONTAL);
        String[] toggleLabels = {"全选","全不选","默认"};  // 默认排除海洋（不显示海洋选项）
        // 实际上海洋就被排除在列表之外了，所以默认就是全选
        // 加全选/全不选按钮
        Button allBtn = new Button(this);
        allBtn.setText("全选"); allBtn.setTextSize(10);
        allBtn.setPadding(8, 0, 8, 0);
        allBtn.setBackgroundColor(0xFFe5e7eb); allBtn.setTextColor(0xFF374151);
        allBtn.setOnClickListener(v -> {
            // 需要刷新UI，简单点用Toast提示
            Toast.makeText(this, "全选已应用", Toast.LENGTH_SHORT).show();
        });
        Button noneBtn = new Button(this);
        noneBtn.setText("全不选"); noneBtn.setTextSize(10);
        noneBtn.setPadding(8, 0, 8, 0);
        noneBtn.setBackgroundColor(0xFFe5e7eb); noneBtn.setTextColor(0xFF374151);
        noneBtn.setOnClickListener(v -> Toast.makeText(this, "至少要选一种地形", Toast.LENGTH_SHORT).show());

        // 因为StatefulBuilder不好搞，用final数组跟踪选中状态
        for (int i = 0; i < terrainDefs.length; i++) {
            final int idx = i;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, 2, 0, 2);

            CheckBox cb = new CheckBox(this);
            cb.setChecked(allowedFlags[i]);
            cb.setOnCheckedChangeListener((btn, isChecked) -> allowedFlags[idx] = isChecked);
            row.addView(cb);

            View colorBlock = new View(this);
            colorBlock.setLayoutParams(new LinearLayout.LayoutParams(12, 12));
            colorBlock.setBackgroundColor(terrainDefs[i][1]);
            row.addView(colorBlock);

            TextView nameTv = new TextView(this);
            nameTv.setText(terrainNames[i]);
            nameTv.setTextSize(10);
            nameTv.setTextColor(0xFF374151);
            nameTv.setPadding(4, 0, 0, 0);
            row.addView(nameTv);

            l.addView(row);
        }

        // 海洋开关（默认关闭，避免破坏陆海边界）
        final CheckBox seaCb = new CheckBox(this);
        seaCb.setText("同时随机海洋（默认关闭）");
        seaCb.setChecked(false);
        seaCb.setTextSize(11);
        seaCb.setTextColor(0xFF374151);
        l.addView(seaCb);

        sv.addView(l);
        b.setView(sv);

        b.setPositiveButton("随机化", (d, w) -> {
            java.util.List<Integer> allowedIds = new java.util.ArrayList<>();
            for (int i = 0; i < terrainDefs.length; i++) {
                if (allowedFlags[i]) allowedIds.add(terrainDefs[i][0]);
            }
            if (allowedIds.isEmpty()) {
                Toast.makeText(this, "至少选择一种地形", Toast.LENGTH_SHORT).show();
                return;
            }

            history.save(mapData);
            java.util.List<Integer> buildingIds = new java.util.ArrayList<>();
            for (int i = 0; i < mapData.tiles.size(); i++) {
                buildingIds.add(mapData.buildingIds != null && i < mapData.buildingIds.size() ? mapData.buildingIds.get(i) : 0);
            }

            int seed = (int)(System.currentTimeMillis() & 0x7FFFFFFF);
            RandomMapGenerator.randomizeTerrain(mapData.tiles, probability[0], allowedIds, seed,
                    buildingIds, seaCb.isChecked(), mapData.terrainPatternList);
            hexMapView.refresh();
            updateInfo();
            int changedCount = (int)(probability[0] * mapData.tiles.size());
            Toast.makeText(this, "随机化完成: ~" + changedCount + "个格子改变 (" + (int)(probability[0]*100) + "%)", Toast.LENGTH_SHORT).show();
        });
        b.setNegativeButton("取消", null);
        showDarkDialog(b, sv);
    }

    private void openFile() {
        // 一律使用系统文件选择器（所有安卓版本都能正常打开文件，避免旧路径找不到目录）
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, REQUEST_OPEN);
    }

    private void pickFile(File dir) {
        if(!dir.exists()){pickFile(Environment.getExternalStorageDirectory());return;}
        File[] fs=dir.listFiles((d,n)->n.endsWith(".btl")||n.endsWith(".bin")||n.endsWith(".BTL")||n.endsWith(".BIN")||d.isDirectory());
        if(fs==null||fs.length==0){Toast.makeText(this,"未找到文件",Toast.LENGTH_SHORT).show();return;}
        String[] ns=new String[fs.length]; for(int i=0;i<fs.length;i++) ns[i]=fs[i].isDirectory()?"📁 "+fs[i].getName():"📄 "+fs[i].getName();
        new AlertDialog.Builder(this).setTitle("选择文件").setItems(ns,(d,w)->{if(fs[w].isDirectory())pickFile(fs[w]);else loadFile(fs[w]);}).setNegativeButton("取消",null).show();
    }

    private void loadFile(File f) {
        try {
            FileInputStream fis = new FileInputStream(f);
            byte[] d = new byte[(int) f.length()];
            fis.read(d);
            fis.close();
            // 当前已是征服 BTL、打开的是 BIN 时，把它当作该 BTL 的世界地形加载
            if (mapData != null && mapData.btlOriginalData != null) {
                FileParser.BtlHeaderInfo hi = FileParser.parseBTLHeader(mapData.btlOriginalData);
                if (!hi.independentTerrain && f.getName().toLowerCase().endsWith(".bin")) {
                    try {
                        FileParser.loadConquestTerrain(mapData, d);
                        mapData.binOriginalData = d;
                        mapData.binFileName = f.getName();
                        rememberWorldBin(f.getName(), d);
                        hexMapView.setMapData(mapData);
                        hexMapView.refresh();
                        updateInfo();
                        selectedInfo.setText("已加载征服地形: " + f.getName());
                        Toast.makeText(this, "世界地形已加载，可像战役一样修改征服", Toast.LENGTH_LONG).show();
                        return;
                    } catch (Exception e) {
                        Toast.makeText(this, "征服地形加载失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        return;
                    }
                }
            }
            mapData = FileParser.loadFile(d, f.getName());
            currentFileName = f.getName();
            history.clear();
            if (mapData != null) mapData.historyRef = history;
            // 记住刚打开的世界地形 BIN，供后续征服 BTL 自动匹配
            if (mapData.binOriginalData != null) {
                rememberWorldBin(f.getName(), mapData.binOriginalData);
            }
            hexMapView.setMapData(mapData);
            updateInfo();
            updateBtnState();
            blockIdText.setText("未选中");
            selectedInfo.setText("已加载: " + f.getName());
            maybeLoadConquestBin();
        } catch (Exception e) {
            Toast.makeText(this, "加载失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /** 加载的 BTL 若为征服地图（地图序号!=0，地形在 world BIN 中），自动匹配底图或提示选择 BIN。 */
    private void maybeLoadConquestBin() {
        if (mapData == null || mapData.btlOriginalData == null) return;
        FileParser.BtlHeaderInfo hi = FileParser.parseBTLHeader(mapData.btlOriginalData);
        if (hi.independentTerrain) return;
        String mapId = String.valueOf(hi.mapId);
        // 与 HTML 版转换器一致：world/worldmap 优先，其次 map{id} 精确名，最后其他候选；
        // 按优先级逐个尝试，截取窗口匹配的第一个即自动加载，不再弹窗
        java.util.List<java.util.Map.Entry<String, byte[]>> candidates =
                new java.util.ArrayList<>(recentWorldBins.entrySet());
        candidates.sort((a, b) -> Integer.compare(
                scoreWorldBinName(a.getKey(), mapId), scoreWorldBinName(b.getKey(), mapId)));
        for (java.util.Map.Entry<String, byte[]> e : candidates) {
            try {
                FileParser.loadConquestTerrain(mapData, e.getValue());
                mapData.binOriginalData = e.getValue();
                mapData.binFileName = e.getKey();
                hexMapView.setMapData(mapData);
                hexMapView.refresh();
                updateInfo();
                selectedInfo.setText("已自动匹配世界地形: " + e.getKey());
                Toast.makeText(this, "已自动匹配世界地形 " + e.getKey() + "，可像战役一样修改征服",
                        Toast.LENGTH_LONG).show();
                return;
            } catch (Exception ignored) {
                // 截取窗口不匹配，尝试下一个候选
            }
        }
        Toast.makeText(this, "征服地图：请选择对应的世界地形 BIN 文件", Toast.LENGTH_LONG).show();
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, REQUEST_CONQUEST_BIN);
    }

    /** 底图候选优先级：world.bin/worldmap.bin=0，map{id}*.bin=1，其余=2（越小越优先）。 */
    private int scoreWorldBinName(String name, String mapId) {
        String n = (name == null ? "" : name).toLowerCase();
        if (n.equals("world.bin") || n.equals("worldmap.bin")) return 0;
        if (n.equals("map" + mapId + ".bin")
                || n.equals("map" + mapId + "_hd.bin")
                || n.equals("map" + mapId + "@2x.bin")) return 1;
        return 2;
    }

    /** 记住最近打开的世界地形 BIN，供征服 BTL 自动匹配（最多保留 4 个）。 */
    private void rememberWorldBin(String name, byte[] data) {
        if (data == null) return;
        String key = (name == null || name.isEmpty()) ? "world.bin" : name;
        recentWorldBins.remove(key);
        recentWorldBins.put(key, data);
        while (recentWorldBins.size() > MAX_RECENT_WORLD_BINS) {
            String oldest = recentWorldBins.keySet().iterator().next();
            recentWorldBins.remove(oldest);
        }
        pendingWorldBin = data;
        pendingWorldBinName = key;
    }

    /** 官方地图编辑器“导出测试BTL和底图”：生成 xx_Bin.bin + xx_TestConquest.btl，并弹官方两段提示。 */
    private void exportTestConquest() {
        if (mapData == null || mapData.binOriginalData == null) {
            Toast.makeText(this, "请先打开世界底图", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            int w = mapData.width, h = mapData.height;
            int defmapW = w * 54;
            int defMapH = (int) (62.5f * h);
            int mapBinW = 2 * defmapW;
            int mapBinH = (int) (2.0683076f * defMapH);
            int mapBinGW = mapBinW / 125 + 1;
            int mapBinGH = mapBinH / 125 + 1;
            int sumGrid = mapBinGW * mapBinGH;
            int mapId = 2;
            if (mapData.btlOriginalData != null) {
                mapId = FileParser.parseBTLHeader(mapData.btlOriginalData).mapId;
            }
            String base = (mapData.binFileName != null && !mapData.binFileName.isEmpty())
                    ? stripBtlExt(mapData.binFileName) : "world";
            if (!ensureStorageAccess()) return;
            File dir = new File(Environment.getExternalStorageDirectory(), "地图编辑器");
            if (!dir.exists()) dir.mkdirs();
            byte[] testBin = FileParser.createTestMapBin(mapBinGW, mapBinGH, sumGrid);
            File binOut = new File(dir, base + "_Bin.bin");
            FileOutputStream bos = new FileOutputStream(binOut);
            bos.write(testBin);
            bos.close();
            byte[] testBtl = FileParser.createTestConquestBtl(w, h, mapId);
            File btlOut = new File(dir, base + "_TestConquest.btl");
            FileOutputStream btlOs = new FileOutputStream(btlOut);
            btlOs.write(testBtl);
            btlOs.close();
            // 官方两段提示
            AlertDialog.Builder b1 = new AlertDialog.Builder(this, R.style.DarkDialog);
            b1.setTitle("提示");
            b1.setMessage("请到def_map.xml中修改相关数值,defmapW:" + defmapW + " defMapH:" + defMapH
                    + "\n\n已生成：\n" + binOut.getName() + "\n" + btlOut.getName()
                    + "\n位置：" + dir.getAbsolutePath());
            b1.setPositiveButton("确定", null);
            b1.show();
            AlertDialog.Builder b2 = new AlertDialog.Builder(this, R.style.DarkDialog);
            b2.setTitle("提示");
            b2.setMessage("生成的测试conquest的数值与你在编辑器中注册的mapId一致,请确保与你的def_map.xml中的id一致,"
                    + "请将生成的" + base + "_Bin.bin改名为map?_hd.bin,并修改def_map.xml中的tile为map?");
            b2.setPositiveButton("确定", null);
            b2.show();
        } catch (Exception e) {
            Toast.makeText(this, "导出失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void saveFile() {
        if(mapData==null){Toast.makeText(this,"无可保存",Toast.LENGTH_SHORT).show();return;}

        // 先检查是否有自定义路径
        if (!customSavePath.isEmpty()) {
            doSave(customSavePath);
            return;
        }

        // 弹窗让用户选择保存方式
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("选择保存位置");
        b.setItems(new String[]{"默认位置（地图编辑器/）", "自定义路径...", "另存为 BTL（BIN 转 BTL）"}, (d, w) -> {
            if (w == 0) {
                File dir = new File(Environment.getExternalStorageDirectory(), "地图编辑器");
                doSave(dir.getAbsolutePath());
            } else if (w == 1) {
                // 用系统文件选择器选目录
                if (Build.VERSION.SDK_INT >= 21) {
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                    startActivityForResult(intent, REQUEST_SAVE);
                } else {
                    // 低版本手动输入路径
                    showSavePathDialog();
                }
            } else {
                // 另存为 BTL：把 BIN/内存地图转为标准 BTL 保存
                saveAsBtl();
            }
        });
        b.setNegativeButton("取消", null);
        b.show();
    }

    private void saveAsBtl() {
        if (mapData == null) return;
        try {
            ensureBtlData();
            currentFileName = stripBtlExt(currentFileName) + ".btl";
            File dir = new File(Environment.getExternalStorageDirectory(), "地图编辑器");
            doSave(dir.getAbsolutePath());
        } catch (Exception e) {
            android.util.Log.e("SAVE_BTL", "转BTL失败", e);
            Toast.makeText(this, "转BTL失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /**
     * 若当前地图不是 BTL 数据（BIN 加载或内存新建），用已验证的战役模板重建为完整 BTL，
     * 保留地形与建筑；BTL 地图直接跳过。
     */
    private void ensureBtlData() throws IOException {
        if (mapData.btlOriginalData != null) return;
        byte[] template = readAssetBytes("templates/stage10103.btl");
        // 用中立军团槽位转换，避免把地图国家替换成 stage10103 模板的那几个国家
        MapData conv = FileParser.createEmptyBtlNeutral(template,
                stripBtlExt(currentFileName) + ".btl", mapData.width, mapData.height);
        for (int i = 0; i < mapData.getTotalTiles(); i++) {
            conv.tiles.set(i, mapData.tiles.get(i));
            conv.buildingIds.set(i, mapData.buildingIds.get(i));
        }
        conv.historyRef = mapData.historyRef;
        mapData = conv;
        hexMapView.setMapData(mapData);
        hexMapView.refresh();
        updateInfo();
        Toast.makeText(this, "已转换为 BTL 格式", Toast.LENGTH_SHORT).show();
    }

    private String stripBtlExt(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".btl") || n.endsWith(".bin")) {
            return name.substring(0, name.length() - 4);
        }
        return name;
    }

    private void doSave(String dirPath) {
        try{
            // 保存前检查外部存储写入权限（安卓 11+ 需要“所有文件访问”）
            if (!ensureStorageAccess()) return;
            // 官方模式（扩展后保存）：与官方地图编辑器一致——只输出扩展后的底图 world.bin
            if (mapData.conquestExtended && mapData.binOriginalData != null) {
                byte[] binData = mapData.binOriginalData;
                String binName = (mapData.binFileName != null && !mapData.binFileName.isEmpty())
                        ? mapData.binFileName : "world.bin";
                File dir = new File(dirPath);
                if (!dir.exists()) dir.mkdirs();
                File binOut = new File(dir, binName);
                FileOutputStream bos = new FileOutputStream(binOut);
                bos.write(binData);
                bos.close();
                Toast.makeText(this, "✅ 已生成文件（官方模式，仅输出底图）：\n"
                        + binName + "\n位置：" + dir.getAbsolutePath(), Toast.LENGTH_LONG).show();
                return;
            }
            String fileName = currentFileName;
            if (!fileName.toLowerCase().endsWith(".btl") && !fileName.toLowerCase().endsWith(".bin")) {
                fileName = fileName + ".btl";
            }
            // 无扩展名时默认保存为标准 BTL（修复扩展地图后误存成 BIN 的问题）
            boolean isBTL = fileName.toLowerCase().endsWith(".btl");
            // 保存为 BTL 但数据不是 BTL（如 BIN 文件起了 .btl 后缀）：先转换为标准 BTL
            if (isBTL && mapData.btlOriginalData == null) {
                ensureBtlData();
            }
            // 征服 BTL 不含地形：未加载世界地形 BIN 时提示，避免误以为地形改动已保存
            if (isBTL && mapData.btlOriginalData != null && mapData.binOriginalData == null
                    && !FileParser.parseBTLHeader(mapData.btlOriginalData).independentTerrain) {
                Toast.makeText(this, "⚠️ 征服 BTL 不含地形，未加载世界地形 BIN，地形改动不会保存",
                        Toast.LENGTH_LONG).show();
            }
            byte[] data = isBTL ? FileParser.saveAsBTL(mapData) : FileParser.saveAsBIN(mapData);
            File dir = new File(dirPath);
            if (!dir.exists()) dir.mkdirs();
            File outFile = new File(dir, fileName);
            FileOutputStream fos = new FileOutputStream(outFile);
            fos.write(data);
            fos.close();
            // 征服地图：地形在世界 BIN 中，一并保存（保留原文件名与省规划段）
            if (mapData.binOriginalData != null && mapData.btlOriginalData != null) {
                byte[] binData = FileParser.saveAsBIN(mapData);
                String binName = (mapData.binFileName != null && !mapData.binFileName.isEmpty())
                        ? mapData.binFileName : "world.bin";
                File binOut = new File(dir, binName);
                FileOutputStream bos = new FileOutputStream(binOut);
                bos.write(binData);
                bos.close();
                Toast.makeText(this, "✅ 已生成文件：\n" + outFile.getName() + "\n"
                        + binOut.getName() + "\n位置：" + dir.getAbsolutePath(),
                        Toast.LENGTH_LONG).show();
                return;
            }
            Toast.makeText(this,"✅ 已保存到: " + outFile.getAbsolutePath(),Toast.LENGTH_LONG).show();
        }catch(Exception e){
            android.util.Log.e("SAVE","error",e);
            Toast.makeText(this,"❌ 保存失败: "+e.getMessage(),Toast.LENGTH_LONG).show();
        }
    }

    /** 检查/引导外部存储写入权限：安卓 11+ 需要用户开启“所有文件访问”。 */
    private boolean ensureStorageAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            if (android.os.Environment.isExternalStorageManager()) return true;
            try {
                Intent i = new Intent(
                        android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivity(i);
            } catch (Exception e) {
                try {
                    startActivity(new Intent(
                            android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                } catch (Exception ignored) {
                }
            }
            Toast.makeText(this, "请在设置里开启“所有文件访问”权限后再保存", Toast.LENGTH_LONG).show();
            return false;
        }
        if (Build.VERSION.SDK_INT >= 23) {
            if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{
                        android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 100);
                Toast.makeText(this, "请授予存储权限后再保存", Toast.LENGTH_SHORT).show();
                return false;
            }
        }
        return true;
    }

    private String getRealPathFromUri(Uri uri) {
        String path = uri.getPath();
        if (path == null) return null;
        // tree URI 格式: /tree/primary:目录名
        if (path.startsWith("/tree/")) {
            path = path.substring(6); // 去掉 /tree/
            if (path.startsWith("primary:")) {
                return Environment.getExternalStorageDirectory() + "/" + path.substring(8);
            }
        }
        return null;
    }

    private void showSavePathDialog() {
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("自定义保存路径");
        EditText et = new EditText(this);
        et.setHint("例如: /sdcard/我的地图");
        et.setText(customSavePath.isEmpty() ? Environment.getExternalStorageDirectory() + "/地图编辑器" : customSavePath);
        b.setView(et);
        b.setPositiveButton("保存并记住", (d, w) -> {
            String path = et.getText().toString().trim();
            if (path.isEmpty()) { Toast.makeText(this, "路径不能为空", Toast.LENGTH_SHORT).show(); return; }
            customSavePath = path;
            getSharedPreferences("wc4_editor_prefs", MODE_PRIVATE).edit().putString("save_path", path).apply();
            doSave(path);
        });
        b.setNegativeButton("取消", null);
        b.show();
    }

    @Override
    protected void onActivityResult(int req,int res,Intent data){
        super.onActivityResult(req,res,data);
        if (req == REQUEST_MAPLIB_UPLOAD && res == RESULT_OK && data != null && data.getData() != null) {
            handleMapLibUpload(data.getData());
            return;
        }
        if(req==REQUEST_OPEN&&res==RESULT_OK&&data!=null&&data.getData()!=null){
            try{Uri uri=data.getData();FileInputStream fis=(FileInputStream)getContentResolver().openInputStream(uri);byte[] buf=new byte[fis.available()];fis.read(buf);fis.close();
                String name=uri.getLastPathSegment();if(name!=null&&name.contains("/"))name=name.substring(name.lastIndexOf('/')+1);if(name==null)name="打开的文件";
                // 当前已是征服 BTL、打开的是 BIN 时，把它当作该 BTL 的世界地形加载
                if (mapData != null && mapData.btlOriginalData != null
                        && name.toLowerCase().endsWith(".bin")) {
                    FileParser.BtlHeaderInfo hi = FileParser.parseBTLHeader(mapData.btlOriginalData);
                    if (!hi.independentTerrain) {
                        try {
                            FileParser.loadConquestTerrain(mapData, buf);
                            mapData.binOriginalData = buf;
                            mapData.binFileName = name;
                            rememberWorldBin(name, buf);
                            hexMapView.setMapData(mapData);
                            hexMapView.refresh();
                            updateInfo();
                            selectedInfo.setText("已加载征服地形: " + name);
                            Toast.makeText(this, "世界地形已加载，可像战役一样修改征服", Toast.LENGTH_LONG).show();
                            enterEditorAfterLoad();
                            return;
                        } catch (Exception e) {
                            Toast.makeText(this, "征服地形加载失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                            return;
                        }
                    }
                }
                mapData=FileParser.loadFile(buf,name);currentFileName=name;history.clear();if(mapData!=null)mapData.historyRef=history;
                if (mapData != null && mapData.binOriginalData != null) {
                    rememberWorldBin(name, mapData.binOriginalData);
                }
                hexMapView.setMapData(mapData);updateInfo();updateBtnState();
                blockIdText.setText("未选中");selectedInfo.setText("已加载: "+name);
                maybeLoadConquestBin();
                enterEditorAfterLoad();
            }catch(Exception e){Toast.makeText(this,"加载失败",Toast.LENGTH_LONG).show();}
        } else if (req == REQUEST_CONQUEST_BIN && res == RESULT_OK && data != null && data.getData() != null) {
            try {
                Uri uri = data.getData();
                FileInputStream fis = (FileInputStream) getContentResolver().openInputStream(uri);
                byte[] buf = new byte[fis.available()];
                fis.read(buf);
                fis.close();
                String name = uri.getLastPathSegment();
                if (name != null && name.contains("/")) name = name.substring(name.lastIndexOf('/') + 1);
                FileParser.loadConquestTerrain(mapData, buf);
                mapData.binOriginalData = buf;
                mapData.binFileName = name;
                rememberWorldBin(name, buf);
                hexMapView.setMapData(mapData);
                hexMapView.refresh();
                updateInfo();
                selectedInfo.setText("已加载征服地形: " + (name == null ? "world.bin" : name));
                Toast.makeText(this, "世界地形已加载，可编辑地形后保存", Toast.LENGTH_LONG).show();
                enterEditorAfterLoad();
            } catch (Exception e) {
                Toast.makeText(this, "世界地形加载失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        } else if (req == REQUEST_APK && res == RESULT_OK && data != null && data.getData() != null) {
            final Uri apkUri = data.getData();
            Toast.makeText(this, "正在扫描 APK，请稍候…", Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                final java.util.List<String[]> apkMaps = new java.util.ArrayList<>();
                Exception err = null;
                try {
                    InputStream apkIs = getContentResolver().openInputStream(apkUri);
                    java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(apkIs);
                    java.util.zip.ZipEntry ze;
                    while ((ze = zis.getNextEntry()) != null) {
                        if (!ze.isDirectory()) {
                            String n = ze.getName().toLowerCase();
                            if (n.endsWith(".btl") || n.endsWith(".bin")) {
                                apkMaps.add(new String[]{ze.getName(), String.valueOf(ze.getSize())});
                            }
                        }
                        zis.closeEntry();
                    }
                    zis.close();
                    apkIs.close();
                } catch (Exception e) {
                    err = e;
                }
                final Exception ferr = err;
                runOnUiThread(() -> {
                    if (ferr != null) {
                        Toast.makeText(this, "打开 APK 失败: " + ferr.getMessage(), Toast.LENGTH_LONG).show();
                        return;
                    }
                    if (apkMaps.isEmpty()) {
                        Toast.makeText(this, "APK 里没有 .btl/.bin 地图文件", Toast.LENGTH_LONG).show();
                        return;
                    }
                    // 排序：.btl 优先，world/map 次之，其余最后
                    apkMaps.sort((x, y) -> {
                        String a = x[0].toLowerCase(), b = y[0].toLowerCase();
                        int sa = a.endsWith(".btl") ? 0 : (a.startsWith("world") || a.startsWith("map") ? 1 : 2);
                        int sb = b.endsWith(".btl") ? 0 : (b.startsWith("world") || b.startsWith("map") ? 1 : 2);
                        if (sa != sb) return sa - sb;
                        return a.compareTo(b);
                    });
                    final java.util.List<String> allNames = new java.util.ArrayList<>();
                    final java.util.Map<String, String> labelToName = new java.util.HashMap<>();
                    for (String[] m : apkMaps) {
                        String label = m[0] + "（" + m[1] + "B）";
                        allNames.add(label);
                        labelToName.put(label, m[0]);
                    }
                    final ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                            android.R.layout.simple_list_item_1, allNames);
                    final EditText search = new EditText(this);
                    search.setHint("搜索（如 stage / world / map2）");
                    search.setSingleLine(true);
                    search.addTextChangedListener(new android.text.TextWatcher() {
                        @Override public void beforeTextChanged(CharSequence s2, int a, int b2, int c) {}
                        @Override public void onTextChanged(CharSequence s2, int a, int b2, int c) {
                            adapter.getFilter().filter(s2);
                        }
                        @Override public void afterTextChanged(android.text.Editable s2) {}
                    });
                    final ListView lv = new ListView(this);
                    lv.setAdapter(adapter);
                    lv.setOnItemClickListener((parent, view, pos, id) -> {
                        try {
                            String label = adapter.getItem(pos);
                            String target = labelToName.get(label);
                            if (target == null) return;
                            InputStream is2 = getContentResolver().openInputStream(apkUri);
                            java.util.zip.ZipInputStream z2 = new java.util.zip.ZipInputStream(is2);
                            java.util.zip.ZipEntry e2;
                            byte[] picked = null;
                            while ((e2 = z2.getNextEntry()) != null) {
                                if (e2.getName().equals(target)) {
                                    java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                                    byte[] buf = new byte[8192];
                                    int n;
                                    while ((n = z2.read(buf)) != -1) bos.write(buf, 0, n);
                                    picked = bos.toByteArray();
                                    break;
                                }
                                z2.closeEntry();
                            }
                            z2.close();
                            is2.close();
                            if (picked == null) {
                                Toast.makeText(this, "读取失败", Toast.LENGTH_SHORT).show();
                                return;
                            }
                            String fname = target.substring(target.lastIndexOf('/') + 1);
                            mapData = FileParser.loadFile(picked, fname);
                            currentFileName = fname;
                            history.clear();
                            if (mapData != null) mapData.historyRef = history;
                            hexMapView.setMapData(mapData);
                            updateInfo();
                            updateBtnState();
                            blockIdText.setText("未选中");
                            selectedInfo.setText("已从 APK 加载: " + fname);
                            // BIN 用完整布局，BTL 用战役布局
                            editorMode = fname.toLowerCase().endsWith(".bin") ? 2 : 0;
                            maybeLoadConquestBin();
                            enterEditorAfterLoad();
                        } catch (Exception ex) {
                            Toast.makeText(this, "加载失败: " + ex.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    });
                    LinearLayout apkLayout = new LinearLayout(this);
                    apkLayout.setOrientation(LinearLayout.VERTICAL);
                    apkLayout.setPadding(20, 8, 20, 8);
                    apkLayout.addView(search);
                    apkLayout.addView(lv, new LinearLayout.LayoutParams(-1, 0, 1));
                    AlertDialog.Builder b = new AlertDialog.Builder(this);
                    b.setTitle("选择 APK 内的地图文件（共 " + apkMaps.size() + " 个）");
                    b.setView(apkLayout);
                    b.setNegativeButton("取消", null);
                    b.show();
                });
            }).start();
        } else if (req == REQUEST_SAVE && res == RESULT_OK && data != null && data.getData() != null) {
            // 用户选择了保存目录
            Uri treeUri = data.getData();
            if (Build.VERSION.SDK_INT >= 21) {
                // 获取持久化权限
                getContentResolver().takePersistableUriPermission(treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                // 转成真实路径
                String path = getRealPathFromUri(treeUri);
                if (path != null) {
                    customSavePath = path;
                    getSharedPreferences("wc4_editor_prefs", MODE_PRIVATE).edit().putString("save_path", path).apply();
                    doSave(path);
                } else {
                    showSavePathDialog();
                }
            }
        } else if (req == REQUEST_OVERLAY && res == RESULT_OK && data != null && data.getData() != null) {
            try {
                Uri uri = data.getData();
                InputStream is = getContentResolver().openInputStream(uri);
                Bitmap bmp = BitmapFactory.decodeStream(is);
                if (is != null) is.close();
                if (bmp != null) {
                    // 对比度增强
                    Bitmap enhanced = Bitmap.createBitmap(bmp.getWidth(), bmp.getHeight(), bmp.getConfig());
                    android.graphics.Canvas c = new android.graphics.Canvas(enhanced);
                    android.graphics.ColorMatrix cm = new android.graphics.ColorMatrix();
                    cm.set(new float[]{
                        2.0f, 0, 0, 0, -80,   // R: 对比度*2, 亮度-80
                        0, 2.0f, 0, 0, -80,   // G
                        0, 0, 2.0f, 0, -80,   // B
                        0, 0, 0, 1f, 0         // A不变
                    });
                    android.graphics.Paint cp = new android.graphics.Paint();
                    cp.setColorFilter(new android.graphics.ColorMatrixColorFilter(cm));
                    c.drawBitmap(bmp, 0, 0, cp);
                    // 导入底图
                    hexMapView.setOverlayImage(enhanced);
                    bmp.recycle();
                    hexMapView.refresh();
                    Toast.makeText(this, "底图已导入，点击遮罩切换显示", Toast.LENGTH_SHORT).show();
                }
            } catch (Exception e) {
                Toast.makeText(this, "底图加载失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        } else if (req == REQUEST_GUIDE && res == RESULT_OK && data != null && data.getData() != null) {
            try {
                Uri uri = data.getData();
                InputStream is = getContentResolver().openInputStream(uri);
                Bitmap bmp = BitmapFactory.decodeStream(is);
                if (is != null) is.close();
                if (bmp != null) {
                    hexMapView.setGuideImage(bmp);
                    hexMapView.refresh();
                    Toast.makeText(this, "引导图已导入，图片+六边形网格已显示", Toast.LENGTH_LONG).show();
                }
            } catch (Exception e) {
                Toast.makeText(this, "引导图加载失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }
    }
}
