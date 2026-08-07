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
    private HexMapView hexMapView;
    private LinearLayout rightPanel;
    private TextView selectedInfo, mapInfo, titleText, blockIdText;
    private Button undoBtn, redoBtn;
    private Button terrainTabBtn, buildingTabBtn;
    private LinearLayout contentArea;
    private LinearLayout terrainScroll, buildingScroll;
    private Button armyTabBtn;
    private LinearLayout armyScroll;
    private Button cityTabBtn;
    private LinearLayout cityScroll;
    private LinearLayout armyEditorArea;
    private java.util.List<LinearLayout> armyAddRows;
    private java.util.List<ImageView> armyIconViews;
    private MapData.Building selectedBuilding;
    private MapData.Building lastEditedBuilding;
    private MapData mapData;
    /** 最近打开过的世界地形 BIN：先开 BIN 再开征服 BTL 时自动匹配，无需重复选择。 */
    private byte[] pendingWorldBin;
    private String pendingWorldBinName;
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
    private boolean addingArmy = false;
    private ArmyConfig pendingArmyType;
    private int pendingArmyLegion = -1;
    private boolean provinceViewOn = false; // 省规划视图需手动开启；默认只在原地形上叠半透明国家色

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
        {"技能等级5", "u8", "0x1B"}, {"关键据点", "u8", "0x1C"}, {"方针", "u8", "0x1D"},
        {"运输船", "u8", "0x1E"}, {"仇恨值", "u16", "0x20"}, {"移动目标", "u16", "0x22"},
        {"行为方案", "u16", "0x24"}, {"改变回合", "u16", "0x26"}, {"士气", "u8", "0x28"},
        {"士气持续回合", "u8", "0x29"}, {"关联事件", "u8", "0x2A"}, {"金盾标志", "u8", "0x2B"},
        {"固守距离", "i32", "0x2C"}
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
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 全局崩溃捕获，显示错误信息不闪退
        final Activity ctx = this;
        final UncaughtExceptionHandler defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, ex) -> {
            ex.printStackTrace();
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
        loadThumbs();
        // 兵种数据必须在 buildUI() 之前加载，右侧面板的兵种图标栏才会显示图标
        try {
            ArmyConfig.load(readAssetBytes("json/ArmySettings.json"));
        } catch (Exception ignored) {
        }
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        buildUI();
        requestPerm();
        showDisclaimerOnce();
        initAudio();
        loadPrefs();
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
        root.addView(createTopBar());

        LinearLayout body = new LinearLayout(this);
        int screenWidthDp = (int) (getResources().getDisplayMetrics().widthPixels
                / getResources().getDisplayMetrics().density);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 1));

        hexMapView = new HexMapView(this);
        hexMapView.setLayoutParams(new LinearLayout.LayoutParams(-1, -1));
        hexMapView.setOnTileSelectListener(this);
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
        fileLabel.setText("文件");
        fileLabel.setTextSize(11);
        fileLabel.setTypeface(null, android.graphics.Typeface.BOLD);
        fileLabel.setTextColor(Color.WHITE);
        fileLabel.setGravity(Gravity.CENTER);
        fileLabel.setPadding(4, 0, 4, 4);
        leftPanel.addView(fileLabel);
        Button floatOpen = makeFloatBtn("打开", 0xFF1E5FA8);
        floatOpen.setOnClickListener(v -> openFile());
        Button floatSave = makeFloatBtn("保存", 0xFF1E5FA8);
        floatSave.setOnClickListener(v -> saveFile());
        leftPanel.addView(floatOpen);
        leftPanel.addView(floatSave);

        View divider = new View(this);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-1, 1);
        dlp.topMargin = 8 * density;
        dlp.bottomMargin = 8 * density;
        divider.setLayoutParams(dlp);
        divider.setBackgroundColor(0x55FFFFFF);
        leftPanel.addView(divider);

        TextView soundLabel = new TextView(this);
        soundLabel.setText("声音");
        soundLabel.setTextSize(11);
        soundLabel.setTypeface(null, android.graphics.Typeface.BOLD);
        soundLabel.setTextColor(Color.WHITE);
        soundLabel.setGravity(Gravity.CENTER);
        soundLabel.setPadding(4, 4, 4, 4);
        leftPanel.addView(soundLabel);
        Button floatMusic = makeFloatBtn(musicEnabled ? "关闭音乐" : "开启音乐", 0xFF2F6B3A);
        floatMusic.setOnClickListener(v -> {
            musicEnabled = !musicEnabled;
            floatMusic.setText(musicEnabled ? "关闭音乐" : "开启音乐");
            getSharedPreferences("wc4_editor_prefs", MODE_PRIVATE)
                    .edit().putBoolean("music_enabled", musicEnabled).apply();
            if (musicEnabled && bgMusicPlayer != null) bgMusicPlayer.start();
            else if (bgMusicPlayer != null) bgMusicPlayer.pause();
        });
        Button floatSfx = makeFloatBtn(sfxEnabled ? "关闭音效" : "开启音效", 0xFF2F6B3A);
        floatSfx.setOnClickListener(v -> {
            sfxEnabled = !sfxEnabled;
            floatSfx.setText(sfxEnabled ? "关闭音效" : "开启音效");
            getSharedPreferences("wc4_editor_prefs", MODE_PRIVATE)
                    .edit().putBoolean("sfx_enabled", sfxEnabled).apply();
        });
        leftPanel.addView(floatMusic);
        leftPanel.addView(floatSfx);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(110 * density, -2,
                Gravity.CENTER_VERTICAL | Gravity.LEFT);
        lp.leftMargin = 8 * density;
        bodyFrame.addView(leftPanel, lp);

        // 底部左下角：交流群文字
        TextView groupText = new TextView(this);
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
        versionView.setText("v1.5内测版");
        versionView.setTextColor(0xCCFFFFFF);
        versionView.setTextSize(10);
        infoPanel.addView(versionView);

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
        handle.setGravity(Gravity.CENTER);
        handle.setBackgroundColor(Color.parseColor("#2f3d75"));
        TextView grip = new TextView(this);
        grip.setText("☰ 按住拖动 ← →");
        grip.setTextColor(Color.WHITE);
        grip.setTextSize(12);
        grip.setPadding(0, 8 * density, 0, 8 * density);
        handle.addView(grip);
        content.addView(handle, 0);

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
            history.undo(mapData); hexMapView.refresh(); updateInfo(); updateBtnState();
        });
        bar.addView(undoBtn); bar.addView(spacer(4));

        // 工具栏可横向滑动，手机窄屏时所有操作均可访问。
        String[] labels = {"新建BTL","BTL数据","地图","视图","纯移动"};
        for (int i = 0; i < labels.length; i++) {
            final int a = i;
            Button btn = makeTopBtn(labels[i]);
            btn.setOnClickListener(v -> {
                if (a == 2) showMapPopup(btn);
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
            case 1: showBtlDataOverlay(); break;
            case 4: toggleViewOnly(); break;
        }
    }

    private void showMapPopup(View anchor) {
        if (mapData == null) {
            Toast.makeText(this, "请先加载地图", Toast.LENGTH_SHORT).show();
            return;
        }
        java.util.List<Runnable> acts = new java.util.ArrayList<>();
        acts.add(() -> showExpandDirectionDialog());
        acts.add(() -> startCropSelect());
        acts.add(() -> randomizeTerrainDialog());
        acts.add(() -> randomizeArmiesDialog());
        acts.add(() -> showBuildingListOverlay());
        showDropdownMenu(anchor, new String[]{"扩展地图…", "截取地图…", "随机地形…",
                "随机兵力…", "城市列表…"}, acts);
    }

    private void showViewPopup(View anchor) {
        boolean panelVisible = rightPanel != null
                && rightPanel.getVisibility() == View.VISIBLE;
        java.util.List<Runnable> acts = new java.util.ArrayList<>();
        acts.add(() -> showLegionsOverlay());
        acts.add(() -> toggleProvinceView());
        acts.add(() -> rightPanel.setVisibility(panelVisible ? View.GONE : View.VISIBLE));
        acts.add(() -> importOverlay());
        acts.add(() -> importGuideImage());
        acts.add(() -> toggleOverlay());
        showDropdownMenu(anchor, new String[]{
                "军团列表",
                (provinceViewOn ? "✔ 省规划视图" : "省规划视图"),
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
                    + "（序号" + lg.seq + " 阵营" + lg.faction + " 控制" + lg.control + "）");
            tv.setTextSize(15);
            tv.setTextColor(Color.WHITE);
            row.addView(tv);
            l.addView(row);
        }
        sv.addView(l);
        legionPanel.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        legionOverlay.setVisibility(View.VISIBLE);
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
        AlertDialog.Builder b = new AlertDialog.Builder(this);
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
        b.show();
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
        AlertDialog.Builder b = new AlertDialog.Builder(this);
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
                TerrainTile fill = fillSpinner.getSelectedItemPosition() == 0
                        ? makeFillTile(true) : makeFillTile(false);
                expandMapGeneric(mapData, newW, newH, remap, fill);
                hexMapView.setMapData(mapData);
                hexMapView.refresh();
                updateInfo();
                final String[] names = {"向上", "向下", "向左", "向右"};
                currentFileName = names[dir] + "扩展" + n + (rows ? "行" : "列") + "_"
                        + newW + "x" + newH + ".btl";
                Toast.makeText(this, "已" + names[dir] + "扩展 " + n + (rows ? " 行" : " 列"),
                        Toast.LENGTH_LONG).show();
            } catch (Exception ex) {
                Toast.makeText(this, "扩展出错: " + ex.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
        b.setNegativeButton("取消", null);
        b.show();
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
    private void importOverlay() {
        if (mapData == null) { Toast.makeText(this, "请先加载地图", Toast.LENGTH_SHORT).show(); return; }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        startActivityForResult(intent, REQUEST_OVERLAY);
    }

    private LinearLayout createRightPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setLayoutParams(new LinearLayout.LayoutParams(440, -1));
        panel.setBackgroundColor(Color.parseColor("#f8fafc"));
        panel.setPadding(12, 8, 12, 8);

        blockIdText = new TextView(this);
        blockIdText.setText("未选中");
        blockIdText.setTextColor(Color.parseColor("#374151"));
        blockIdText.setTextSize(14);
        blockIdText.setTypeface(null, android.graphics.Typeface.BOLD);
        blockIdText.setPadding(8, 4, 8, 4);
        panel.addView(blockIdText);

        selectedInfo = new TextView(this);
        selectedInfo.setText("点击地图上的格子开始编辑");
        selectedInfo.setTextColor(Color.parseColor("#6b7280"));
        selectedInfo.setTextSize(12);
        selectedInfo.setPadding(8, 0, 8, 8);
        panel.addView(selectedInfo);

        mapInfo = new TextView(this);
        mapInfo.setText("未加载地图");
        mapInfo.setTextColor(Color.parseColor("#9ca3af"));
        mapInfo.setTextSize(11);
        mapInfo.setPadding(8, 0, 8, 6);
        panel.addView(mapInfo);

        // 笔刷模式按钮
        LinearLayout brushRow = new LinearLayout(this);
        brushRow.setOrientation(LinearLayout.HORIZONTAL);
        brushRow.setLayoutParams(new LinearLayout.LayoutParams(-1, 36));
        brushRow.setPadding(4, 4, 4, 4);

        Button penBtn = new Button(this);
        penBtn.setText("笔刷");
        penBtn.setTextSize(12);
        penBtn.setLayoutParams(new LinearLayout.LayoutParams(0, -1, 1));
        penBtn.setGravity(Gravity.CENTER);
        penBtn.setPadding(4, 0, 4, 0);
        penBtn.setBackgroundColor(Color.parseColor("#2a2a5e"));
        penBtn.setTextColor(Color.WHITE);
        penBtn.setOnClickListener(v -> {
            if (mapData == null) { Toast.makeText(this,"请先加载地图",Toast.LENGTH_SHORT).show(); return; }
            mapData.brushMode = !mapData.brushMode;
            penBtn.setText(mapData.brushMode ? "笔刷(开)" : "笔刷");
            penBtn.setBackgroundColor(Color.parseColor(mapData.brushMode ? "#22c55e" : "#2a2a5e"));
            hexMapView.refresh();
            Toast.makeText(this, mapData.brushMode ? "笔刷已开启：先选地形，滑动涂抹即可修改" : "笔刷已关闭", Toast.LENGTH_SHORT).show();
        });
        brushRow.addView(penBtn);
        panel.addView(brushRow);

        // 笔刷范围控制行
        LinearLayout brushRangeRow = new LinearLayout(this);
        brushRangeRow.setOrientation(LinearLayout.HORIZONTAL);
        brushRangeRow.setLayoutParams(new LinearLayout.LayoutParams(-1, 32));
        brushRangeRow.setPadding(4, 2, 4, 2);
        brushRangeRow.setGravity(Gravity.CENTER_VERTICAL);

        final TextView rangeLabel = new TextView(this);
        rangeLabel.setText("范围:");
        rangeLabel.setTextSize(11);
        rangeLabel.setTextColor(0xFF374151);
        rangeLabel.setLayoutParams(new LinearLayout.LayoutParams(-2, -2));
        brushRangeRow.addView(rangeLabel);

        final android.widget.SeekBar rangeBar = new android.widget.SeekBar(this);
        rangeBar.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        rangeBar.setMax(5);  // 0~5圈
        rangeBar.setProgress(0);
        final TextView rangeVal = new TextView(this);
        rangeVal.setText("0");
        rangeVal.setTextSize(11);
        rangeVal.setTextColor(0xFF374151);
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
        panel.addView(brushRangeRow);

        View div = new View(this);
        div.setLayoutParams(new LinearLayout.LayoutParams(-1, 1));
        div.setBackgroundColor(Color.parseColor("#e5e7eb"));
        panel.addView(div);

        // 兵种图标栏：直接显示在面板顶部，点图标锁定后点地图地块连续添加
        buildArmyIconBar(panel);

        // 标签
        LinearLayout tabRow = new LinearLayout(this);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        tabRow.setLayoutParams(new LinearLayout.LayoutParams(-1, 40));
        tabRow.setPadding(0, 8, 0, 0);

        terrainTabBtn = new Button(this);
        terrainTabBtn.setText("地形");
        terrainTabBtn.setTextSize(13);
        terrainTabBtn.setLayoutParams(new LinearLayout.LayoutParams(0, -1, 1));
        terrainTabBtn.setGravity(Gravity.CENTER);
        terrainTabBtn.setOnClickListener(v -> switchTab(0));

        buildingTabBtn = new Button(this);
        buildingTabBtn.setText("设施");
        buildingTabBtn.setTextSize(13);
        buildingTabBtn.setLayoutParams(new LinearLayout.LayoutParams(0, -1, 1));
        buildingTabBtn.setGravity(Gravity.CENTER);
        buildingTabBtn.setOnClickListener(v -> switchTab(1));

        armyTabBtn = new Button(this);
        armyTabBtn.setText("兵种");
        armyTabBtn.setTextSize(13);
        armyTabBtn.setLayoutParams(new LinearLayout.LayoutParams(0, -1, 1));
        armyTabBtn.setGravity(Gravity.CENTER);
        armyTabBtn.setOnClickListener(v -> switchTab(2));

        cityTabBtn = new Button(this);
        cityTabBtn.setText("城市");
        cityTabBtn.setTextSize(13);
        cityTabBtn.setLayoutParams(new LinearLayout.LayoutParams(0, -1, 1));
        cityTabBtn.setGravity(Gravity.CENTER);
        cityTabBtn.setOnClickListener(v -> switchTab(3));

        tabRow.addView(terrainTabBtn);
        tabRow.addView(buildingTabBtn);
        tabRow.addView(armyTabBtn);
        tabRow.addView(cityTabBtn);
        panel.addView(tabRow);

        // 可滚动内容区（竖向列表，支持上下滑动）
        ScrollView scrollView = new ScrollView(this);
        scrollView.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 1));

        contentArea = new LinearLayout(this);
        contentArea.setOrientation(LinearLayout.VERTICAL);

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
            row.setLayoutParams(new LinearLayout.LayoutParams(-1, 80));
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(8, 4, 8, 4);
            row.setClickable(true);
            row.setBackgroundResource(android.R.drawable.edit_text);
            row.setBackgroundColor(0x00ffffff);
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
                            penBtn.setBackgroundColor(Color.parseColor("#2a2a5e"));
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
                    hexMapView.refresh(); updateInfo();
                } else {
                    Toast.makeText(this, "请先点击地图上的格子", Toast.LENGTH_SHORT).show();
                }
            });
            ImageView iv = new ImageView(this);
            iv.setLayoutParams(new LinearLayout.LayoutParams(70, 70));
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setPadding(4, 4, 4, 4);
            Bitmap t = terrainThumbs.get(g);
            if (t != null) iv.setImageBitmap(t); else iv.setBackgroundColor(0xFFcccccc);
            row.addView(iv);
            TextView lb = new TextView(this);
            lb.setText(tns[i]);
            lb.setTextSize(14);
            lb.setTextColor(0xFF374151);
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
                // 多选模式下批量修改
                if (mapData.multiSelectMode && mapData.hasSelectedBlocks()) {
                    history.save(mapData);
                    mapData.applyBuildingToSelected(bid);
                    hexMapView.refresh(); updateInfo();
                    Toast.makeText(this, "已批量修改 " + mapData.selectedBlocks.size() + " 个格子", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (hexMapView.getSelectedX() >= 0) {
                    history.save(mapData);
                    int x = hexMapView.getSelectedX(), y = hexMapView.getSelectedY();
                    mapData.setBuildingId(x, y, bid);
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

        contentArea.addView(terrainScroll);
        contentArea.addView(buildingScroll);

        // 兵种页：点击地图上已有兵种格子的 48 字段编辑区
        armyScroll = new LinearLayout(this);
        armyScroll.setOrientation(LinearLayout.VERTICAL);
        armyScroll.setVisibility(View.GONE);
        TextView editHint = new TextView(this);
        editHint.setText("点击地图上已有兵种的格子查看/修改 48 字段");
        editHint.setTextSize(12);
        editHint.setTextColor(0xFF9ca3af);
        editHint.setPadding(8, 10, 8, 2);
        armyScroll.addView(editHint);
        armyEditorArea = new LinearLayout(this);
        armyEditorArea.setOrientation(LinearLayout.VERTICAL);
        armyScroll.addView(armyEditorArea);
        contentArea.addView(armyScroll);

        // 城市编辑页：选中有建筑的地块后，由 rebuildCityEditor 填充 32 字段输入框
        cityScroll = new LinearLayout(this);
        cityScroll.setOrientation(LinearLayout.VERTICAL);
        cityScroll.setVisibility(View.GONE);
        TextView cityHint = new TextView(this);
        cityHint.setText("请先在地图上点击一个有建筑/城市的格子");
        cityHint.setTextSize(12);
        cityHint.setTextColor(0xFF9ca3af);
        cityHint.setPadding(8, 12, 8, 12);
        cityScroll.addView(cityHint);
        contentArea.addView(cityScroll);
        scrollView.addView(contentArea);
        panel.addView(scrollView);

        switchTab(0);
        return panel;
    }

    private void switchTab(int tab) {
        terrainScroll.setVisibility(tab == 0 ? View.VISIBLE : View.GONE);
        buildingScroll.setVisibility(tab == 1 ? View.VISIBLE : View.GONE);
        armyScroll.setVisibility(tab == 2 ? View.VISIBLE : View.GONE);
        cityScroll.setVisibility(tab == 3 ? View.VISIBLE : View.GONE);
        if (tab == 2) rebuildArmyEditor();
        if (tab == 3) rebuildCityEditor();
        int ab = 0xFF3b82f6, ib = 0xFFe5e7eb;
        terrainTabBtn.setBackgroundColor(tab == 0 ? ab : ib);
        terrainTabBtn.setTextColor(tab == 0 ? Color.WHITE : 0xFF374151);
        buildingTabBtn.setBackgroundColor(tab == 1 ? ab : ib);
        buildingTabBtn.setTextColor(tab == 1 ? Color.WHITE : 0xFF374151);
        armyTabBtn.setBackgroundColor(tab == 2 ? ab : ib);
        armyTabBtn.setTextColor(tab == 2 ? Color.WHITE : 0xFF374151);
        cityTabBtn.setBackgroundColor(tab == 3 ? ab : ib);
        cityTabBtn.setTextColor(tab == 3 ? Color.WHITE : 0xFF374151);
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
                if (c != null && c.army >= 1 && c.army <= 40
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
        hint.setTextSize(11);
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
                if (c != null && c.army >= 1 && c.army <= 40
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
        div2.setBackgroundColor(Color.parseColor("#e5e7eb"));
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
        final MapData.Army army = selectedArmy;
        TextView head = new TextView(this);
        head.setText("兵种48 记录（" + (army.name != null ? army.name : "兵种" + army.type)
                + " Lv" + army.level + "）");
        head.setTextSize(12);
        head.setTextColor(0xFF1f2937);
        head.setTypeface(null, android.graphics.Typeface.BOLD);
        head.setPadding(8, 6, 8, 6);
        armyEditorArea.addView(head);

        armySaveBtn = new Button(this);
        armySaveBtn.setText("保存修改");
        armySaveBtn.setTextSize(12);
        armySaveBtn.setTextColor(Color.WHITE);
        armySaveBtn.setBackgroundColor(Color.parseColor("#22c55e"));
        armySaveBtn.setOnClickListener(v -> {
            byte[] raw = army.raw.clone();
            if (armyEds != null) {
                for (int i = 0; i < ARMY_FIELDS.length && i < armyEds.length; i++) {
                    try {
                        int val = Integer.parseInt(armyEds[i].getText().toString().trim());
                        writeArmyField(raw, Integer.decode(ARMY_FIELDS[i][2]), ARMY_FIELDS[i][1], val);
                    } catch (Exception ignored) {
                    }
                }
            }
            try {
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
            final String fname = ARMY_FIELDS[i][0];
            final String ftype = ARMY_FIELDS[i][1];
            final int off = Integer.decode(ARMY_FIELDS[i][2]);
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
            et.setText(String.valueOf(readArmyField(army.raw, off, ftype)));
            et.setLayoutParams(new LinearLayout.LayoutParams(120, -2));
            armyEds[i] = et;
            row.addView(et);
            armyEditorArea.addView(row);
        }
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
        head.setText("建筑32 记录（" + buildingTypeName(b.type) + " (" + b.x + "," + b.y + ")）");
        head.setTextSize(12);
        head.setTextColor(0xFF1f2937);
        head.setTypeface(null, android.graphics.Typeface.BOLD);
        head.setPadding(8, 6, 8, 6);
        cityScroll.addView(head);

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

    private static int readArmyField(byte[] raw, int off, String type) {
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(raw).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        switch (type) {
            case "u8": return raw[off] & 0xFF;
            case "u16": return bb.getShort(off) & 0xFFFF;
            default: return bb.getInt(off);
        }
    }

    private static void writeArmyField(byte[] raw, int off, String type, int v) {
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
        // 锁定的兵种：点击地块连续添加（归属跟随地块）
        if (mapData != null && mapData.selectedArmyType >= 0
                && mapData.btlOriginalData != null) {
            ArmyConfig cfg = ArmyConfig.byArmy(mapData.selectedArmyType);
            if (cfg == null) {
                mapData.selectedArmyType = -1;
                highlightArmyIcons();
            } else {
                try {
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
        if (mapData.belongs != null && idx < mapData.belongs.length) {
            int leg = mapData.belongs[idx] & 0xFF;
            if (leg != 0xFF && leg < mapData.legionColors.length) return leg;
        }
        if (mapData.provinces != null && idx < mapData.provinces.length) {
            int p = mapData.provinces[idx];
            if (p != 0 && p != 0xFFFF && p < mapData.getTotalTiles()
                    && mapData.belongs != null && p < mapData.belongs.length) {
                int leg = mapData.belongs[p] & 0xFF;
                if (leg != 0xFF && leg < mapData.legionColors.length) return leg;
            }
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

    private void updateInfo() {
        if (mapData == null) return;
        int sx = hexMapView.getSelectedX(), sy = hexMapView.getSelectedY();
        if (sx >= 0) {
            TerrainTile t = mapData.getTile(sx, sy);
            int bid = mapData.getBuildingId(sx, sy);
            int idx = sy * mapData.width + sx;
            blockIdText.setText(String.format("地块 #%d", idx));
            String info = String.format("ID: %d (G=%d, Id=%d)", idx, t.bmTerrain1Group, t.bmTerrain1Id);
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
            selectedInfo.setText(info);
        } else {
            selectedArmy = null;
        }
        // 同步选中的城市/建筑记录
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
        }
        if (armyScroll != null && selectedArmy != lastEditedArmy) {
            lastEditedArmy = selectedArmy;
            rebuildArmyEditor();
        }
        if (cityScroll != null && selectedBuilding != lastEditedBuilding) {
            lastEditedBuilding = selectedBuilding;
            rebuildCityEditor();
        }
        int total = mapData.getTotalTiles();
        mapInfo.setText(String.format(" %dx%d %d格 %d%% %d", mapData.width, mapData.height, total, total>0?mapData.getWaterCount()*100/total:0, mapData.getBuildingCount()));
    }

    private String getBName(int id) {
        String[] n = {"","农场","风车","小镇","","","","","","","","小城市","中城市","大城市","大都市","首都1","首都2","首都3","首都4","首都5","","炼油厂","大工厂","核电站"};
        if (id>=0&&id<n.length&&!n[id].isEmpty()) return n[id];
        if (id==41) return "机场"; if (id==42) return "要塞"; if (id==43) return "堡垒"; if (id==44) return "据点"; if (id==45) return "工厂";
        return "建筑"+id;
    }

    /** 新建标准 BTL 战役。MapData 不附带原文件，保存时会由 FileParser 写出完整基础 BTL。 */
    private void newBtlMap() {
        AlertDialog.Builder b = new AlertDialog.Builder(this);
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
            } catch (Exception e) {
                Toast.makeText(this, "新建失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
        b.setNegativeButton("取消", null);
        b.show();
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

        AlertDialog.Builder b = new AlertDialog.Builder(this);
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
        b.show();
    }

    private void openFile() {
        if(Build.VERSION.SDK_INT>=30){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("*/*");startActivityForResult(i,REQUEST_OPEN);}
        else pickFile(new File(Environment.getExternalStorageDirectory(),"综合开发"));
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
                        pendingWorldBin = d;
                        pendingWorldBinName = f.getName();
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
                pendingWorldBin = mapData.binOriginalData;
                pendingWorldBinName = f.getName();
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

    /** 加载的 BTL 若为征服地图（地图序号!=0，地形在 world BIN 中），提示选择 BIN。 */
    private void maybeLoadConquestBin() {
        if (mapData == null || mapData.btlOriginalData == null) return;
        FileParser.BtlHeaderInfo hi = FileParser.parseBTLHeader(mapData.btlOriginalData);
        if (hi.independentTerrain) return;
        // 若最近打开过世界地形 BIN 且窗口匹配，直接自动加载，不再弹窗
        if (pendingWorldBin != null) {
            try {
                FileParser.loadConquestTerrain(mapData, pendingWorldBin);
                mapData.binOriginalData = pendingWorldBin;
                mapData.binFileName = pendingWorldBinName;
                hexMapView.setMapData(mapData);
                hexMapView.refresh();
                updateInfo();
                selectedInfo.setText("已自动匹配世界地形: "
                        + (pendingWorldBinName == null ? "world.bin" : pendingWorldBinName));
                Toast.makeText(this, "已自动匹配最近打开的世界地形，可像战役一样修改征服",
                        Toast.LENGTH_LONG).show();
                return;
            } catch (Exception ignored) {
                // 窗口不匹配时继续走选择流程
            }
        }
        Toast.makeText(this, "征服地图：请选择对应的世界地形 BIN 文件", Toast.LENGTH_LONG).show();
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, REQUEST_CONQUEST_BIN);
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
                Toast.makeText(this, "✅ 已保存 BTL 与地形 " + binOut.getName() + " 到: "
                        + dir.getAbsolutePath(), Toast.LENGTH_LONG).show();
            }
            Toast.makeText(this,"✅ 已保存到: " + outFile.getAbsolutePath(),Toast.LENGTH_LONG).show();
        }catch(Exception e){
            android.util.Log.e("SAVE","error",e);
            Toast.makeText(this,"❌ 保存失败: "+e.getMessage(),Toast.LENGTH_LONG).show();
        }
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
                            pendingWorldBin = buf;
                            pendingWorldBinName = name;
                            hexMapView.setMapData(mapData);
                            hexMapView.refresh();
                            updateInfo();
                            selectedInfo.setText("已加载征服地形: " + name);
                            Toast.makeText(this, "世界地形已加载，可像战役一样修改征服", Toast.LENGTH_LONG).show();
                            return;
                        } catch (Exception e) {
                            Toast.makeText(this, "征服地形加载失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                            return;
                        }
                    }
                }
                mapData=FileParser.loadFile(buf,name);currentFileName=name;history.clear();if(mapData!=null)mapData.historyRef=history;
                if (mapData != null && mapData.binOriginalData != null) {
                    pendingWorldBin = mapData.binOriginalData;
                    pendingWorldBinName = name;
                }
                hexMapView.setMapData(mapData);updateInfo();updateBtnState();
                blockIdText.setText("未选中");selectedInfo.setText("已加载: "+name);
                maybeLoadConquestBin();
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
                hexMapView.setMapData(mapData);
                hexMapView.refresh();
                updateInfo();
                selectedInfo.setText("已加载征服地形: " + (name == null ? "world.bin" : name));
                Toast.makeText(this, "世界地形已加载，可编辑地形后保存", Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                Toast.makeText(this, "世界地形加载失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
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
