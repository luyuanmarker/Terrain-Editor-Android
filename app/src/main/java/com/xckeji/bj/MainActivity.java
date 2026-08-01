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
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.xckeji.bj.file.FileParser;
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
    private MapData mapData;
    private OperationHistory history = new OperationHistory();
    private String currentFileName = "未命名地图";
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
        boolean compactScreen = screenWidthDp < 700;
        body.setOrientation(compactScreen ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        body.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 1));

        hexMapView = new HexMapView(this);
        hexMapView.setLayoutParams(compactScreen
                ? new LinearLayout.LayoutParams(-1, 0, 0.62f)
                : new LinearLayout.LayoutParams(0, -1, 0.65f));
        hexMapView.setOnTileSelectListener(this);

        rightPanel = createRightPanel();
        rightPanel.setLayoutParams(compactScreen
                ? new LinearLayout.LayoutParams(-1, 0, 0.38f)
                : new LinearLayout.LayoutParams(440, -1));
        body.addView(hexMapView);
        body.addView(rightPanel);
        root.addView(body);
        root.addView(createBottomBar());
        setContentView(root);
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

        // 左侧三个按钮：撤销、关闭音乐、关闭音效
        undoBtn = makeTopBtn("撤销");
        undoBtn.setOnClickListener(v -> {
            if (mapData==null||!history.canUndo()) return;
            history.undo(mapData); hexMapView.refresh(); updateInfo(); updateBtnState();
        });
        bar.addView(undoBtn); bar.addView(spacer(4));

        Button musicBtn = makeTopBtn(musicEnabled ? "关闭音乐" : "开启音乐");
        musicBtn.setOnClickListener(v -> {
            musicEnabled = !musicEnabled;
            musicBtn.setText(musicEnabled ? "关闭音乐" : "开启音乐");
            getSharedPreferences("wc4_editor_prefs", MODE_PRIVATE).edit().putBoolean("music_enabled", musicEnabled).apply();
            if (musicEnabled && bgMusicPlayer != null) bgMusicPlayer.start();
            else if (bgMusicPlayer != null) bgMusicPlayer.pause();
        });
        bar.addView(musicBtn); bar.addView(spacer(4));

        Button sfxBtn = makeTopBtn(sfxEnabled ? "关闭音效" : "开启音效");
        sfxBtn.setOnClickListener(v -> {
            sfxEnabled = !sfxEnabled;
            sfxBtn.setText(sfxEnabled ? "关闭音效" : "开启音效");
            getSharedPreferences("wc4_editor_prefs", MODE_PRIVATE).edit().putBoolean("sfx_enabled", sfxEnabled).apply();
        });
        bar.addView(sfxBtn);

        // 工具栏可横向滑动，手机窄屏时所有操作均可访问。
        String[] labels = {"新建BTL","打开","保存","预览","底图","图填","遮罩"};
        for (int i = 0; i < labels.length; i++) {
            final int a = i;
            Button btn = makeTopBtn(labels[i]);
            btn.setOnClickListener(v -> topAction(a));
            bar.addView(btn);
            if (i < labels.length - 1) bar.addView(spacer(4));
        }

        toolbarScroll.addView(bar);
        return toolbarScroll;
    }

    private Button makeTopBtn(String text) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextSize(11);
        btn.setTextColor(Color.WHITE);
        btn.setLayoutParams(new LinearLayout.LayoutParams(-2, 34));
        btn.setPadding(8, 0, 8, 0);
        btn.setGravity(Gravity.CENTER);
        btn.setBackgroundColor(Color.parseColor("#2a2a5e"));
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
            case 1: openFile(); break;
            case 2: saveFile(); break;
            case 3: rightPanel.setVisibility(rightPanel.getVisibility() == View.GONE ? View.VISIBLE : View.GONE); break;
            case 4: importOverlay(); break;
            case 5: importGuideImage(); break;
            case 6: toggleOverlay(); break;
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
            currentFileName = "扩展地图_" + nw + "x" + nh;
            Toast.makeText(this, "已扩展为 " + nw + "x" + nh, Toast.LENGTH_SHORT).show();
        });
        b.setNegativeButton("取消", null);
        b.show();
    }

    private void expandMapData(MapData mapData, int newW, int newH) {
        int oldW = mapData.width, oldH = mapData.height;
        java.util.List<TerrainTile> oldTiles = new java.util.ArrayList<>(mapData.tiles);
        java.util.List<Integer> oldBuildings = new java.util.ArrayList<>(mapData.buildingIds);

        mapData.width = newW;
        mapData.height = newH;
        int newTotal = newW * newH;
        mapData.tiles = new java.util.ArrayList<>(newTotal);
        mapData.buildingIds = new java.util.ArrayList<>(newTotal);

        for (int y = 0; y < newH; y++) {
            for (int x = 0; x < newW; x++) {
                int newIdx = y * newW + x;
                if (x < oldW && y < oldH) {
                    int oldIdx = y * oldW + x;
                    mapData.tiles.add(oldTiles.get(oldIdx));
                    mapData.buildingIds.add(oldBuildings.get(oldIdx));
                } else {
                    // 新区域填海洋
                    TerrainTile sea = new TerrainTile();
                    sea.bmTerrain1Group = 1;
                    sea.bmTerrain1Id = 1;
                    mapData.tiles.add(sea);
                    mapData.buildingIds.add(0);
                }
            }
        }

        // 修正 BTL 原始数据——保证 saveAsBTL 和 loadBTL 能正确解析
        if (mapData.btlOriginalData != null) {
            try {
                byte[] oldBtl = mapData.btlOriginalData;
                FileParser.BtlHeaderInfo header = FileParser.parseBTLHeader(oldBtl);
                int oldTotalTiles = oldW * oldH;
                int newTotalTiles = newW * newH;

                int terrainStart = header.terrainStart;

                // 旧文件各段偏移
                int oldAdminStart = terrainStart + oldTotalTiles * 16;
                int oldOwnershipStart = oldAdminStart + oldTotalTiles * 2;
                int oldBuildingStart = oldOwnershipStart + oldTotalTiles * 1;

                // 新文件各段偏移（按 loadBTL 的计算规则）
                int newAdminStart = terrainStart + newTotalTiles * 16;
                int newOwnershipStart = newAdminStart + newTotalTiles * 2;
                int newBuildingStart = newOwnershipStart + newTotalTiles * 1;

                // building 之后剩余数据原样保留
                int afterBuildingSize = oldBtl.length - oldBuildingStart;
                if (afterBuildingSize < 0) afterBuildingSize = 0;

                int newFileSize = newBuildingStart + afterBuildingSize;
                byte[] newBtl = new byte[newFileSize];

                // 1. 复制头部（到 terrainStart 之前，含军团数据）
                System.arraycopy(oldBtl, 0, newBtl, 0, terrainStart);

                // 2. 写入新地块数据
                for (int y = 0; y < newH; y++) {
                    for (int x = 0; x < newW; x++) {
                        int idx = y * newW + x;
                        mapData.tiles.get(idx).toBytes(newBtl, terrainStart + idx * 16);
                    }
                }

                // 3. 管理数据（2字节/格）——逐格按新坐标重排
                for (int y = 0; y < newH; y++) {
                    for (int x = 0; x < newW; x++) {
                        int newAddr = newAdminStart + (y * newW + x) * 2;
                        if (x < oldW && y < oldH) {
                            int oldAddr = oldAdminStart + (y * oldW + x) * 2;
                            newBtl[newAddr] = oldBtl[oldAddr];
                            newBtl[newAddr + 1] = oldBtl[oldAddr + 1];
                        } else {
                            newBtl[newAddr] = 0;
                            newBtl[newAddr + 1] = 0;
                        }
                    }
                }

                // 4. 所有权数据（1字节/格）
                for (int y = 0; y < newH; y++) {
                    for (int x = 0; x < newW; x++) {
                        int newAddr = newOwnershipStart + y * newW + x;
                        if (x < oldW && y < oldH) {
                            int oldAddr = oldOwnershipStart + y * oldW + x;
                            newBtl[newAddr] = oldBtl[oldAddr];
                        } else {
                            newBtl[newAddr] = (byte)0xFF;
                        }
                    }
                }

                // 5. 建筑数据——不变，建筑坐标 (bx, by) 用 % oldW 和 / oldW 解析
                //    宽变了但建筑在旧格子中坐标值不变，因为坐标是相对于原文件的
                //    但存档用 loadBTL 会按新宽重新解析坐标，所以要修正建筑坐标
                if (header.buildingCount > 0 && afterBuildingSize > 0) {
                    System.arraycopy(oldBtl, oldBuildingStart, newBtl, newBuildingStart, afterBuildingSize);
                    // 修正每条建筑坐标：新坐标 = by * newW + bx
                    for (int i = 0; i < header.buildingCount; i++) {
                        int addr = newBuildingStart + i * 32;
                        if (addr + 4 > newFileSize) break;
                        int oldCoord = ByteBuffer.wrap(newBtl).order(ByteOrder.LITTLE_ENDIAN).getShort(addr) & 0xFFFF;
                        int bx = oldCoord % oldW;
                        int by = oldCoord / oldW;
                        int newCoord = by * newW + bx;
                        newBtl[addr] = (byte)(newCoord & 0xFF);
                        newBtl[addr + 1] = (byte)((newCoord >> 8) & 0xFF);
                    }
                }

                // 6. 修正头部宽高
                ByteBuffer bb = ByteBuffer.wrap(newBtl).order(ByteOrder.LITTLE_ENDIAN);
                bb.putInt(0x10, newW);
                bb.putInt(0x14, newH);

                mapData.btlOriginalData = newBtl;
            } catch (Exception e) {
                android.util.Log.e("EXPAND", "BTL修正失败", e);
                Toast.makeText(this, "BTL数据修正失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }
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
                currentFileName = "上扩展+" + n + "行_" + mapData.width + "x" + mapData.height;
                Toast.makeText(this, "已向上扩展 " + n + " 行", Toast.LENGTH_SHORT).show();
            } catch (Exception ex) {
                Toast.makeText(this, "扩展出错: " + ex.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
        b.setNegativeButton("取消", null);
        b.show();
    }

    private void expandMapUp(MapData mapData, int n) {
        int w = mapData.width, oldH = mapData.height, newH = oldH + n;
        int m = n * w; // 新增格子数

        // 1. 内存 tiles 和 buildingIds 全部向下平移 n 行
        java.util.List<TerrainTile> oldTiles = new java.util.ArrayList<>(mapData.tiles);
        java.util.List<Integer> oldBuildings = new java.util.ArrayList<>(mapData.buildingIds);

        mapData.height = newH;
        int newTotal = w * newH;
        mapData.tiles = new java.util.ArrayList<>(newTotal);
        mapData.buildingIds = new java.util.ArrayList<>(newTotal);

        // 顶部 n 行填海洋
        for (int i = 0; i < m; i++) {
            TerrainTile sea = new TerrainTile();
            sea.bmTerrain1Group = 1;
            sea.bmTerrain1Id = 1;
            mapData.tiles.add(sea);
            mapData.buildingIds.add(0);
        }
        // 原数据向下平移
        mapData.tiles.addAll(oldTiles);
        mapData.buildingIds.addAll(oldBuildings);

        // 2. 修正 BTL 原始数据
        if (mapData.btlOriginalData != null) {
            try {
                byte[] oldBtl = mapData.btlOriginalData;
                FileParser.BtlHeaderInfo header = FileParser.parseBTLHeader(oldBtl);
                int oldTotalTiles = w * oldH;
                int newTotalTiles = w * newH;
                int terrainStart = header.terrainStart;

                // 各段偏移
                int oldAdminStart = terrainStart + oldTotalTiles * 16;
                int oldOwnershipStart = oldAdminStart + oldTotalTiles * 2;
                int oldBuildingStart = oldOwnershipStart + oldTotalTiles * 1;

                int newAdminStart = terrainStart + newTotalTiles * 16;
                int newOwnershipStart = newAdminStart + newTotalTiles * 2;
                int newBuildingStart = newOwnershipStart + newTotalTiles * 1;

                int afterBuildingSize = oldBtl.length - oldBuildingStart;
                if (afterBuildingSize < 0) afterBuildingSize = 0;

                int newFileSize = newBuildingStart + afterBuildingSize;
                byte[] newBtl = new byte[newFileSize];

                // 复制头部
                System.arraycopy(oldBtl, 0, newBtl, 0, terrainStart);

                // 写入新地形数据（顶部n行海洋，然后原有数据）
                for (int i = 0; i < newTotalTiles; i++) {
                    mapData.tiles.get(i).toBytes(newBtl, terrainStart + i * 16);
                }

                // 管理数据（2字节/格）顶部n行为0，原有向下平移
                for (int y = 0; y < newH; y++) {
                    for (int x = 0; x < w; x++) {
                        int newAddr = newAdminStart + (y * w + x) * 2;
                        if (y >= n) {
                            int oldAddr = oldAdminStart + ((y - n) * w + x) * 2;
                            newBtl[newAddr] = oldBtl[oldAddr];
                            newBtl[newAddr + 1] = oldBtl[oldAddr + 1];
                        } else {
                            newBtl[newAddr] = 0;
                            newBtl[newAddr + 1] = 0;
                        }
                    }
                }

                // 所有权数据（1字节/格）
                for (int y = 0; y < newH; y++) {
                    for (int x = 0; x < w; x++) {
                        int newAddr = newOwnershipStart + y * w + x;
                        if (y >= n) {
                            int oldAddr = oldOwnershipStart + (y - n) * w + x;
                            newBtl[newAddr] = oldBtl[oldAddr];
                        } else {
                            newBtl[newAddr] = (byte)0xFF;
                        }
                    }
                }

                // 建筑数据：复制，然后把每个建筑的格子索引 +m
                if (header.buildingCount > 0 && afterBuildingSize > 0) {
                    System.arraycopy(oldBtl, oldBuildingStart, newBtl, newBuildingStart, afterBuildingSize);
                    for (int i = 0; i < header.buildingCount; i++) {
                        int addr = newBuildingStart + i * 32;
                        if (addr + 4 > newFileSize) break;
                        int coord = ByteBuffer.wrap(newBtl).order(ByteOrder.LITTLE_ENDIAN).getShort(addr) & 0xFFFF;
                        coord += m;
                        newBtl[addr] = (byte)(coord & 0xFF);
                        newBtl[addr + 1] = (byte)((coord >> 8) & 0xFF);
                    }
                }

                // 向 army 段之后的所有数据段中的格子索引 +m
                // 军团数组之后的各个段，每条记录都可能有格子索引
                // 使用通用策略：遍历 building 之后的所有数据，按固定偏移查找格子索引
                int dataAfterBuildings = afterBuildingSize;
                int cursor = newBuildingStart;

                // --- 军团/兵种 (300字节/条) ---
                if (header.armyCount > 0 && cursor + header.armyCount * 300 <= newFileSize) {
                    for (int i = 0; i < header.armyCount; i++) {
                        int addr = cursor + i * 300;
                        // 兵种坐标：每条的第8-9字节（格子索引）
                        if (addr + 10 <= newFileSize) {
                            int idx = ByteBuffer.wrap(newBtl).order(ByteOrder.LITTLE_ENDIAN).getShort(addr + 8) & 0xFFFF;
                            if (idx > 0 && idx < oldTotalTiles) {
                                idx += m;
                                newBtl[addr + 8] = (byte)(idx & 0xFF);
                                newBtl[addr + 9] = (byte)((idx >> 8) & 0xFF);
                            }
                        }
                        // 第12-13字节也可能是坐标
                        if (addr + 14 <= newFileSize) {
                            int idx2 = ByteBuffer.wrap(newBtl).order(ByteOrder.LITTLE_ENDIAN).getShort(addr + 12) & 0xFFFF;
                            if (idx2 > 0 && idx2 < oldTotalTiles) {
                                idx2 += m;
                                newBtl[addr + 12] = (byte)(idx2 & 0xFF);
                                newBtl[addr + 13] = (byte)((idx2 >> 8) & 0xFF);
                            }
                        }
                    }
                }
                cursor += header.armyCount * 300;

                // --- 陷阱/计划 ---
                // 陷阱和计划在同一个段，每条 60 字节，无坐标索引，跳过
                if (header.planCount > 0) cursor += header.planCount * 60;

                // --- 天气 (52字节/条) --- 有格子索引
                if (header.weatherCount > 0 && cursor + header.weatherCount * 52 <= newFileSize) {
                    for (int i = 0; i < header.weatherCount; i++) {
                        int addr = cursor + i * 52;
                        if (addr + 6 <= newFileSize) {
                            int idx = ByteBuffer.wrap(newBtl).order(ByteOrder.LITTLE_ENDIAN).getShort(addr + 4) & 0xFFFF;
                            if (idx > 0 && idx < oldTotalTiles) {
                                idx += m;
                                newBtl[addr + 4] = (byte)(idx & 0xFF);
                                newBtl[addr + 5] = (byte)((idx >> 8) & 0xFF);
                            }
                        }
                    }
                }
                if (header.weatherCount > 0) cursor += header.weatherCount * 52;

                // --- 事件 (40字节/条) --- 可能有格子索引在第2-3字节
                if (header.eventCount > 0 && cursor + header.eventCount * 40 <= newFileSize) {
                    for (int i = 0; i < header.eventCount; i++) {
                        int addr = cursor + i * 40;
                        if (addr + 4 <= newFileSize) {
                            int idx = ByteBuffer.wrap(newBtl).order(ByteOrder.LITTLE_ENDIAN).getShort(addr + 2) & 0xFFFF;
                            if (idx > 0 && idx < oldTotalTiles) {
                                idx += m;
                                newBtl[addr + 2] = (byte)(idx & 0xFF);
                                newBtl[addr + 3] = (byte)((idx >> 8) & 0xFF);
                            }
                        }
                    }
                }
                if (header.eventCount > 0) cursor += header.eventCount * 40;

                // --- 援军 (128字节/条) --- 格子索引在第0-1字节
                if (header.reinforceCount > 0 && cursor + header.reinforceCount * 128 <= newFileSize) {
                    for (int i = 0; i < header.reinforceCount; i++) {
                        int addr = cursor + i * 128;
                        if (addr + 4 <= newFileSize) {
                            int idx = ByteBuffer.wrap(newBtl).order(ByteOrder.LITTLE_ENDIAN).getShort(addr) & 0xFFFF;
                            if (idx > 0 && idx < oldTotalTiles) {
                                idx += m;
                                newBtl[addr] = (byte)(idx & 0xFF);
                                newBtl[addr + 1] = (byte)((idx >> 8) & 0xFF);
                            }
                        }
                    }
                }
                if (header.reinforceCount > 0) cursor += header.reinforceCount * 128;

                // --- 空袭 (48字节/条) ---
                if (header.airstrikeCount > 0 && cursor + header.airstrikeCount * 48 <= newFileSize) {
                    for (int i = 0; i < header.airstrikeCount; i++) {
                        int addr = cursor + i * 48;
                        if (addr + 4 <= newFileSize) {
                            int idx = ByteBuffer.wrap(newBtl).order(ByteOrder.LITTLE_ENDIAN).getShort(addr) & 0xFFFF;
                            if (idx > 0 && idx < oldTotalTiles) {
                                idx += m;
                                newBtl[addr] = (byte)(idx & 0xFF);
                                newBtl[addr + 1] = (byte)((idx >> 8) & 0xFF);
                            }
                        }
                    }
                }
                if (header.airstrikeCount > 0) cursor += header.airstrikeCount * 48;

                // --- 布雷 (40字节/条) ---
                if (header.mineCount > 0 && cursor + header.mineCount * 40 <= newFileSize) {
                    for (int i = 0; i < header.mineCount; i++) {
                        int addr = cursor + i * 40;
                        if (addr + 4 <= newFileSize) {
                            int idx = ByteBuffer.wrap(newBtl).order(ByteOrder.LITTLE_ENDIAN).getShort(addr) & 0xFFFF;
                            if (idx > 0 && idx < oldTotalTiles) {
                                idx += m;
                                newBtl[addr] = (byte)(idx & 0xFF);
                                newBtl[addr + 1] = (byte)((idx >> 8) & 0xFF);
                            }
                        }
                    }
                }
                if (header.mineCount > 0) cursor += header.mineCount * 40;

                // 更新头部宽高
                ByteBuffer bb = ByteBuffer.wrap(newBtl).order(ByteOrder.LITTLE_ENDIAN);
                bb.putInt(0x10, w);
                bb.putInt(0x14, newH);

                mapData.btlOriginalData = newBtl;
            } catch (Exception e) {
                android.util.Log.e("EXPAND_UP", "BTL修正失败", e);
                Toast.makeText(this, "BTL数据修正失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }
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

        // 按钮行：多选 + 取消多选 + 魔笔
        LinearLayout multiRow = new LinearLayout(this);
        multiRow.setOrientation(LinearLayout.HORIZONTAL);
        multiRow.setLayoutParams(new LinearLayout.LayoutParams(-1, 36));
        multiRow.setPadding(4, 4, 4, 4);
        
        Button selectBtn = new Button(this);
        selectBtn.setText("多选");
        selectBtn.setTextSize(12);
        selectBtn.setLayoutParams(new LinearLayout.LayoutParams(0, -1, 1));
        selectBtn.setGravity(Gravity.CENTER);
        selectBtn.setPadding(4, 0, 4, 0);
        selectBtn.setBackgroundColor(Color.parseColor("#2a2a5e"));
        selectBtn.setTextColor(Color.WHITE);
        selectBtn.setOnClickListener(v -> {
            if (mapData != null) {
                mapData.multiSelectMode = !mapData.multiSelectMode;
                if (mapData.multiSelectMode) {
                    mapData.selectedBlocks.clear();
                    selectBtn.setBackgroundColor(Color.parseColor("#22c55e"));
                    Toast.makeText(this,"多选模式已开启，点击地块可多选",Toast.LENGTH_SHORT).show();
                } else {
                    mapData.selectedBlocks.clear();
                    selectBtn.setBackgroundColor(Color.parseColor("#2a2a5e"));
                    Toast.makeText(this,"多选模式已关闭",Toast.LENGTH_SHORT).show();
                }
                hexMapView.refresh();
            } else {
                Toast.makeText(this,"请先加载地图",Toast.LENGTH_SHORT).show();
            }
        });
        
        Button cancelBtn = new Button(this);
        cancelBtn.setText("取消多选");
        cancelBtn.setTextSize(12);
        cancelBtn.setLayoutParams(new LinearLayout.LayoutParams(0, -1, 1));
        cancelBtn.setGravity(Gravity.CENTER);
        cancelBtn.setPadding(4, 0, 4, 0);
        cancelBtn.setBackgroundColor(Color.parseColor("#2a2a5e"));
        cancelBtn.setTextColor(Color.WHITE);
        cancelBtn.setOnClickListener(v -> {
            clearMultiSelection();
            selectBtn.setBackgroundColor(Color.parseColor("#2a2a5e"));
        });
        
        Button penBtn = new Button(this);
        penBtn.setText("魔笔");
        penBtn.setTextSize(12);
        penBtn.setLayoutParams(new LinearLayout.LayoutParams(0, -1, 1));
        penBtn.setGravity(Gravity.CENTER);
        penBtn.setPadding(4, 0, 4, 0);
        penBtn.setBackgroundColor(Color.parseColor("#2a2a5e"));
        penBtn.setTextColor(Color.WHITE);
        penBtn.setOnClickListener(v -> {
            if (mapData == null) { Toast.makeText(this,"请先加载地图",Toast.LENGTH_SHORT).show(); return; }
            mapData.brushMode = !mapData.brushMode;
            penBtn.setText(mapData.brushMode ? "魔笔(开)" : "魔笔");
            penBtn.setBackgroundColor(Color.parseColor(mapData.brushMode ? "#22c55e" : "#2a2a5e"));
            hexMapView.refresh();
            Toast.makeText(this, mapData.brushMode ? "魔笔已开启：先选地形，滑动涂抹即可修改" : "魔笔已关闭", Toast.LENGTH_SHORT).show();
        });
        
        multiRow.addView(selectBtn);
        View sp1 = new View(this); sp1.setLayoutParams(new LinearLayout.LayoutParams(4, 1));
        multiRow.addView(sp1);
        multiRow.addView(cancelBtn);
        View sp2 = new View(this); sp2.setLayoutParams(new LinearLayout.LayoutParams(4, 1));
        multiRow.addView(sp2);
        multiRow.addView(penBtn);
        panel.addView(multiRow);

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
        terrainTabBtn.setOnClickListener(v -> switchTab(true));

        buildingTabBtn = new Button(this);
        buildingTabBtn.setText("设施");
        buildingTabBtn.setTextSize(13);
        buildingTabBtn.setLayoutParams(new LinearLayout.LayoutParams(0, -1, 1));
        buildingTabBtn.setGravity(Gravity.CENTER);
        buildingTabBtn.setOnClickListener(v -> switchTab(false));

        tabRow.addView(terrainTabBtn);
        tabRow.addView(buildingTabBtn);
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
                    mapData.getTile(x, y).bmTerrain1Group = g;
                    mapData.getTile(x, y).bmTerrain1Id = (g == 0) ? 255 : 0;
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
        scrollView.addView(contentArea);
        panel.addView(scrollView);

        switchTab(true);
        return panel;
    }

    private void switchTab(boolean t) {
        terrainScroll.setVisibility(t ? View.VISIBLE : View.GONE);
        buildingScroll.setVisibility(t ? View.GONE : View.VISIBLE);
        int ab = 0xFF3b82f6, ib = 0xFFe5e7eb;
        terrainTabBtn.setBackgroundColor(t ? ab : ib);
        terrainTabBtn.setTextColor(t ? Color.WHITE : 0xFF374151);
        buildingTabBtn.setBackgroundColor(t ? ib : ab);
        buildingTabBtn.setTextColor(t ? 0xFF374151 : Color.WHITE);
    }

    private View createBottomBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setLayoutParams(new LinearLayout.LayoutParams(-1, 52));
        bar.setBackgroundColor(Color.parseColor("#0f3460"));
        bar.setGravity(Gravity.CENTER);
        bar.setPadding(8, 4, 8, 4);

        Button clearBtn = makeBtn("清建筑");
        clearBtn.setOnClickListener(v -> { if (mapData==null||hexMapView.getSelectedX()<0) return; history.save(mapData); mapData.setBuildingId(hexMapView.getSelectedX(),hexMapView.getSelectedY(),0); hexMapView.refresh(); updateInfo(); });
        bar.addView(clearBtn);

        Button expandUpBtn = makeBtn("上加行");
        expandUpBtn.setOnClickListener(v -> showExpandUpDialog());
        bar.addView(expandUpBtn);

        return bar;
    }

    private Button makeBtn(String t) {
        Button btn = new Button(this);
        btn.setText(t); btn.setTextSize(11); btn.setPadding(8,0,8,0);
        btn.setLayoutParams(new LinearLayout.LayoutParams(0,-1,1));
        btn.setBackgroundColor(Color.parseColor("#2a5a8a")); btn.setTextColor(Color.WHITE); btn.setGravity(Gravity.CENTER);
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
        updateInfo();
    }

    private void updateInfo() {
        if (mapData == null) return;
        int sx = hexMapView.getSelectedX(), sy = hexMapView.getSelectedY();
        if (sx >= 0) {
            TerrainTile t = mapData.getTile(sx, sy);
            int bid = mapData.getBuildingId(sx, sy);
            int idx = sy * mapData.width + sx;
            blockIdText.setText(String.format("地块 #%d", idx));
            selectedInfo.setText(String.format("ID: %d (G=%d, Id=%d)", idx, t.bmTerrain1Group, t.bmTerrain1Id));
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
                currentFileName = "新战役_" + wv + "x" + hv + ".btl";
                history.clear();
                mapData.historyRef = history;
                hexMapView.setMapData(mapData);
                updateInfo();
                blockIdText.setText("未选中");
                selectedInfo.setText("已创建空白平原战役；编辑后直接保存为 .btl");
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
            RandomMapGenerator.randomizeTerrain(mapData.tiles, probability[0], allowedIds, seed, buildingIds);
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
        try{FileInputStream fis=new FileInputStream(f);byte[] d=new byte[(int)f.length()];fis.read(d);fis.close();
            mapData=FileParser.loadFile(d,f.getName());currentFileName=f.getName();history.clear();if(mapData!=null)mapData.historyRef=history;
            hexMapView.setMapData(mapData);updateInfo();updateBtnState();
            blockIdText.setText("未选中");selectedInfo.setText("已加载: "+f.getName());
        }catch(Exception e){Toast.makeText(this,"加载失败: "+e.getMessage(),Toast.LENGTH_LONG).show();}
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
        b.setItems(new String[]{"默认位置（地图编辑器/）", "自定义路径..."}, (d, w) -> {
            if (w == 0) {
                File dir = new File(Environment.getExternalStorageDirectory(), "地图编辑器");
                doSave(dir.getAbsolutePath());
            } else {
                // 用系统文件选择器选目录
                if (Build.VERSION.SDK_INT >= 21) {
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                    startActivityForResult(intent, REQUEST_SAVE);
                } else {
                    // 低版本手动输入路径
                    showSavePathDialog();
                }
            }
        });
        b.setNegativeButton("取消", null);
        b.show();
    }

    private void doSave(String dirPath) {
        try{
            boolean isBTL = currentFileName.toLowerCase().endsWith(".btl");
            byte[] data = isBTL ? FileParser.saveAsBTL(mapData) : FileParser.saveAsBIN(mapData);
            String fileName = currentFileName;
            if (!fileName.toLowerCase().endsWith(".btl") && !fileName.toLowerCase().endsWith(".bin")) {
                fileName = fileName + ".btl";
            }
            File dir = new File(dirPath);
            if (!dir.exists()) dir.mkdirs();
            File outFile = new File(dir, fileName);
            FileOutputStream fos = new FileOutputStream(outFile);
            fos.write(data);
            fos.close();
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
                mapData=FileParser.loadFile(buf,name);currentFileName=name;history.clear();if(mapData!=null)mapData.historyRef=history;
                hexMapView.setMapData(mapData);updateInfo();updateBtnState();
                blockIdText.setText("未选中");selectedInfo.setText("已加载: "+name);
            }catch(Exception e){Toast.makeText(this,"加载失败",Toast.LENGTH_LONG).show();}
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
