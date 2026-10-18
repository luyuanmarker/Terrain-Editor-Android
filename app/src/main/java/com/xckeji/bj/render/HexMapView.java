package com.xckeji.bj.render;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import com.xckeji.bj.R;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;

import com.xckeji.bj.model.MapData;
import com.xckeji.bj.model.TerrainColors;
import com.xckeji.bj.model.TerrainTile;
import com.xckeji.bj.file.FileParser;

import java.util.HashMap;
import java.util.Map;

public class HexMapView extends View {
    private MapData mapData;
    private float offsetX = 20, offsetY = 20;
    private float scale = 1.0f;
    private int selectedX = -1, selectedY = -1;
    // 截取框选区域（-1 表示未框选）
    private int cropRx1 = -1, cropRy1 = -1, cropRx2 = -1, cropRy2 = -1;
    // 省规划视图：按省规划值给每个省不同的颜色（需手动开启）
    private boolean provinceView = false;
    // 国家颜色半透明覆盖（默认开启）：在地形上叠一层归属颜色，不遮挡地形
    private boolean ownershipTint = true;
    // 分层显示开关（仿枭雄 editor.json）
    private boolean showTerrainArt = true;
    private boolean showBuildings = true;
    private boolean showArmies = true;
    private boolean showFlags = true;
    private boolean showGenerals = true;
    private boolean showFacilities = true;
    private int ownerAlpha = 0xFF;   // 归属/省区着色浓度：默认「满」= 纯色填满（视频那种地块显示）
    private int selectTargetFilter = 0;
    // ★ 多选高亮：亮黄填充 + 粗白边（原来太淡看不清）
    private final Paint selFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selEdge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selGlow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean selPaintReady = false;   // 0=地块 1=兵种 2=城市（批量页的框选目标）
    private boolean ownerMultiply = true;   // ★ 默认「熊式」乘法叠加：颜色×地形纹理，地形看得见
    private final android.graphics.PorterDuffXfermode multiplyXfermode =
            new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.MULTIPLY);
    private final android.graphics.PorterDuffColorFilter ownerMulFilter =
            new android.graphics.PorterDuffColorFilter(0xFFFFFFFF, android.graphics.PorterDuff.Mode.MULTIPLY);
    private boolean showLabels = true;
    // 关联高亮：同编制单位 / 同省地块（选中兵种/建筑时联动）
    private int hlFormation = -1;
    private int hlProvince = -1;

    public void setHighlightFormation(int formation) { hlFormation = formation; invalidate(); }
    public void setHighlightProvince(int province) { hlProvince = province; invalidate(); }
    public void clearHighlights() { hlFormation = -1; hlProvince = -1; invalidate(); }

    private static int armyFormation(MapData.Army a) {
        return (a != null && a.raw != null && a.raw.length > 4) ? (a.raw[4] & 0xFF) : 1;
    }
    // 纯移动模式：只允许拖动/缩放画面，点击不选中、不编辑
    private boolean viewOnly = false;
    // 省规划视图：每个地块所在省份解析后的归属军团（0xFF=无归属），
    // 优先取 军团归属[省规划坐标]，失联/中立时用该省城市的军团归属兜底
    private int[] provinceOwnerLegion;
    private OnTileSelectListener listener;
    /** 兵种笔刷回调（由 MainActivity 注入）：锁定兵种 + 开启笔刷时，涂抹逐个添加兵种。 */
    private java.util.function.BiConsumer<Integer, Integer> armyBrushHandler;

    public void setArmyBrushHandler(java.util.function.BiConsumer<Integer, Integer> handler) {
        armyBrushHandler = handler;
    }
    private GestureDetector gestureDetector;
    private Paint tilePaint, gridPaint, selectedPaint;
    private Paint multiPaint;
    // 专门用于绘制位图的 Paint：固定白色（白色=不染色），避免残留颜色把贴图染花
    private final Paint bitmapPaint;
    // 性能优化：复用同一个 Path，避免每格每帧 new
    private final Path sharedPath = new Path();
    // 六边形贴图缓存：每个 (地形组,ID) 预渲染一次，避免每帧 clipPath
    private final Map<String, Bitmap> hexTileCache = new HashMap<>();
    private float cachedHexSize = -1f;
    /**
     * 画质/分辨率档位（只影响**渲染**，绝不影响数据）。
     * 1.0 = 100%、0.75 = 75%、0.5 = 50%。
     * 六边形基础边长 = {@link #BASE_HEX} × qualityScale × {@link #scale}，
     * 所有几何、命中反算、缓存位图都必须走 {@link #hs()} / {@link #baseHex()}，否则点击会错位。
     */
    private static final float BASE_HEX = 20f;
    private float qualityScale = 1f;
    // 整图缓存：整张地图都可见时，把地形/贴图/国家色/建筑渲染成一张离屏位图，
    // 之后每帧只 drawBitmap 一次，避免大地图每帧逐格重绘导致卡顿掉帧。
    private Bitmap fullMapCache;
    private boolean fullMapDirty = true;

    // 图片——懒加载，首次绘制时初始化
    private Bitmap landBmp;
    private Bitmap seaBmp;
    private Map<String, Bitmap> terrainBmps;
    private Map<Integer, Bitmap> legionBmps;
    private final Map<Integer, Bitmap> legionBmpsR = new HashMap<>();
    private final Map<Integer, Bitmap> generalBmps = new HashMap<>();
    private Map<Integer, Bitmap> flagBmps;
    private Map<Integer, Bitmap> buildingBmps;
    // 城市名字：名称字段(u16@0x2) → city_names.json 里的中文名
    private Map<Integer, String> cityNames;
    private boolean drawGrid = false;   // ★ 编辑器界面不显示网格
    private boolean showCityNames = false;
    private final Paint cityNameText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cityNameBg = new Paint(Paint.ANTI_ALIAS_FLAG);

    public void setCityNames(Map<Integer, String> names) { cityNames = names; invalidate(); }
    public void setShowCityNames(boolean on) { showCityNames = on; invalidate(); }
    public boolean isShowCityNames() { return showCityNames; }
    // 建筑圆形裁剪缓存（按半径缓存 Path，避免每帧新建）
    private android.graphics.Path bldCirclePath;
    private float bldCircleR = -1f;
    private final Paint bldRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** 建筑按圆形绘制：取半径对应的圆形 Path（带缓存）。 */
    private android.graphics.Path buildingCircle(float r) {
        if (bldCirclePath == null || Math.abs(bldCircleR - r) > 0.5f) {
            android.graphics.Path p = new android.graphics.Path();
            p.addCircle(0, 0, r, android.graphics.Path.Direction.CW);
            bldCirclePath = p;
            bldCircleR = r;
        }
        return bldCirclePath;
    }

    /** 建筑：圆形裁剪 + 一圈描边（按用户要求，不再显示矩形贴图）。 */
    private void drawBuildingCircle(Canvas c, Bitmap bb, float px, float py, float r) {
        c.save();
        c.translate(px, py);
        c.clipPath(buildingCircle(r * 0.92f));
        c.drawBitmap(bb, null, new RectF(-r, -r, r, r), tilePaint);
        c.restore();
        bldRingPaint.setStyle(Paint.Style.STROKE);
        bldRingPaint.setStrokeWidth(Math.max(1.5f, r * 0.10f));
        bldRingPaint.setColor(0xCCFFFFFF);
        c.drawCircle(px, py, r * 0.92f, bldRingPaint);
    }
    private Bitmap trapLandBmp, trapSeaBmp;
    // 设施/编制图标（仿枭雄：image/status）
    private Bitmap facAirport, facDepot, facFactory, facLab, facLaunch, facNuclear;
    private Bitmap antiair1, antiair2, antiair3, radarIcon, formationIcon;
    private Bitmap[] rankIcons = new Bitmap[7];   // 军衔图标：level_2~6.png（金色V形徽章/星）
    private final Bitmap[] levelIcons = new Bitmap[5];
    private final Paint formationBg = new Paint();
    private boolean imagesLoaded = false;
    private Bitmap borderSelectedBmp;

    private static final Map<Integer, String> G2B = new HashMap<>();
    static {
        G2B.put(2,"desert"); G2B.put(3,"l1_mountain"); G2B.put(4,"m1_mountain");
        G2B.put(5,"h1_mountain"); G2B.put(6,"l2_mountain"); G2B.put(7,"m3_mountain");
        G2B.put(8,"h2_mountain"); G2B.put(9,"l3_mountain"); G2B.put(10,"m3_mountain");
        G2B.put(11,"h3_mountain"); G2B.put(12,"l4_mountain"); G2B.put(13,"m4_mountain");
        G2B.put(14,"h4_mountain"); G2B.put(15,"cactus"); G2B.put(16,"broadleaf");
        G2B.put(18,"broadleaf2"); G2B.put(20,"coniferous"); G2B.put(21,"coniferous2");
        G2B.put(22,"palmae"); G2B.put(26,"farmland"); G2B.put(30,"hollow"); G2B.put(31,"snowfield");
    }
    private static final int[] IGS = {2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,18,20,21,22,26,30,31};
    private static final int[] VCs = {9,11,11,5,11,11,5,11,11,5,11,11,5,9,9,9,9,9,9,1,9,9};

    public HexMapView(Context context) {
        super(context);
        tilePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        tilePaint.setFilterBitmap(true);
        gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(1f);
        gridPaint.setColor(0xFFc8d6e5);
        selectedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        selectedPaint.setStyle(Paint.Style.STROKE);
        selectedPaint.setStrokeWidth(3f);
        selectedPaint.setColor(0xFFfbbf24);
        multiPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        multiPaint.setStyle(Paint.Style.STROKE);
        multiPaint.setStrokeWidth(3f);
        multiPaint.setColor(0xFF10b981);
        bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bitmapPaint.setFilterBitmap(true);
        bitmapPaint.setColor(0xFFFFFFFF);
        gestureDetector = new GestureDetector(context, new GestureListener());
    }

    private void ensureImages() {
        if (imagesLoaded) return;
        imagesLoaded = true;
        try {
            terrainBmps = new HashMap<>();
            legionBmps = new HashMap<>();
            flagBmps = new HashMap<>();
            buildingBmps = new HashMap<>();
            // 国旗优先加载：后面任何一段出问题都不该连累国旗显示
            loadAllFlags();
            landBmp = load("map/land.png");
            seaBmp = load("map/sea.png");
            trapLandBmp = load("pixmap/buildmark/land_trap.png");
            trapSeaBmp = load("pixmap/buildmark/sea_trap.png");
            facAirport = load("image/status/facility_airport.png");
            facDepot = load("image/status/facility_depot.png");
            facFactory = load("image/status/facility_factory.png");
            facLab = load("image/status/facility_lab.png");
            facLaunch = load("image/status/facility_launch.png");
            facNuclear = load("image/status/facility_nuclear.png");
            antiair1 = load("image/status/antiair_1x.png");
            antiair2 = load("image/status/antiair_2x.png");
            antiair3 = load("image/status/antiair_3x.png");
            radarIcon = load("image/status/雷达圆.png");
            formationIcon = load("image/status/formation.png");
            // ★ 军衔图标：熊的 level_2~6.png（金色V形徽章 + 星 = 军衔）
            for (int r = 2; r <= 6; r++) rankIcons[r] = load("image/status/level_" + r + ".png");
            for (int i = 1; i <= 4; i++) {
                levelIcons[i] = load("image/status/lv_" + i + ".png");
            }
            // 精英兵种：加载 1~128 号图标（含 41+ 精英），缺失时用 _r_ 变体兜底
            // 兵种图标：按实际存在的编号加载（模组里有更多编号时也能全读）
            java.util.TreeSet<Integer> legionIds = com.xckeji.bj.mod.ModAssets.idsIn(getContext(), "legion", "legion_icon_");
            if (legionIds.isEmpty()) for (int i = 1; i <= 101; i++) legionIds.add(i);
            for (int i : legionIds) {
                Bitmap b = load("legion/legion_icon_" + i + ".png");
                if (b != null) legionBmps.put(i, b);
                Bitmap br = load("legion/legion_icon_r_" + i + ".png");
                if (br != null) legionBmpsR.put(i, br);
            }
            borderSelectedBmp = BitmapFactory.decodeResource(getResources(), R.drawable.border_selected);
            for (int i = 0; i < IGS.length; i++) {
                int g = IGS[i]; String base = G2B.get(g);
                if (base == null) continue;
                for (int v = 1; v <= VCs[i]; v++) {
                    Bitmap b = load("map/" + base + "_" + v + ".png");
                    if (b != null) terrainBmps.put(g + "_" + v, b);
                }
            }
            // 建筑图标：固定那批 + 模组里多出来的编号
            java.util.TreeSet<Integer> bset = com.xckeji.bj.mod.ModAssets.idsIn(getContext(), "btl", "building_");
            bset.add(1); bset.add(2); bset.add(3); bset.add(11); bset.add(12); bset.add(13); bset.add(14);
            bset.add(15); bset.add(16); bset.add(17); bset.add(21); bset.add(22); bset.add(23);
            bset.add(31); bset.add(32); bset.add(33); bset.add(34);
            int[] bids = new int[bset.size()];
            int bi = 0;
            for (int v : bset) bids[bi++] = v;
            for (int id : bids) {
                Bitmap b = load("btl/building_" + id + ".png");
                if (b != null) buildingBmps.put(id, b);
            }
        } catch (Exception ignored) {}
    }

    private Bitmap load(String path) {
        // 模组优先，缺的回退内置 assets
        return com.xckeji.bj.mod.ModAssets.decode(getContext(), path);
    }

    public void setMapData(MapData data) {
        mapData = data; selectedX = -1; selectedY = -1; scale = 1f;
        clearCropRect();
        hexTileCache.clear(); cachedHexSize = -1f;
        fullMapDirty = true;
        rebuildBuildingInfo();
        rebuildProvinceOwner();
        post(() -> { centerMap(); invalidate(); });
    }
    public MapData getMapData() { return mapData; }
    public interface OnTileSelectListener { void onTileSelected(int x, int y, TerrainTile tile); }

    /** 截取：手指拖动选终点时的回调。 */
    public interface OnCropDragListener {
        void onCropDrag(int x, int y);
        void onCropDone();
    }

    private boolean cropDragging = false;
    private OnCropDragListener cropDragListener;

    /** 打开/关闭「拖动选截取终点」。 */
    public void setCropDragging(boolean on) { cropDragging = on; }

    public void setCropDragListener(OnCropDragListener l) { cropDragListener = l; }
    public void setOnTileSelectListener(OnTileSelectListener l) { listener = l; }
    public int getSelectedX() { return selectedX; }
    public int getSelectedY() { return selectedY; }
    public void refresh() {
        fullMapDirty = true;
        rebuildBuildingInfo();
        rebuildProvinceOwner();
        invalidate();
    }

    // 建筑类型/外观按格缓存，供「类型+外观+首都」选图标用（仿枭雄）
    private int[] bTypeByCell;
    private int[] bAppearanceByCell;
    private final Map<String, Bitmap> buildingByName = new HashMap<>();

    private void rebuildBuildingInfo() {
        if (mapData == null) { bTypeByCell = null; bAppearanceByCell = null; return; }
        int n = mapData.getTotalTiles();
        if (bTypeByCell == null || bTypeByCell.length != n) {
            bTypeByCell = new int[n];
            bAppearanceByCell = new int[n];
        } else {
            java.util.Arrays.fill(bTypeByCell, 0);
            java.util.Arrays.fill(bAppearanceByCell, 0);
        }
        if (mapData.buildings != null) {
            for (MapData.Building b : mapData.buildings) {
                if (b == null || b.raw == null || b.raw.length < 6) continue;
                int idx = b.y * mapData.width + b.x;
                if (idx < 0 || idx >= n) continue;
                bTypeByCell[idx] = b.raw[4] & 0xFF;
                bAppearanceByCell[idx] = b.raw[5] & 0xFF;
            }
        }
    }

    /** 建筑图片名（规则与枭雄一致）：16-19→15；31-34→building_31_外观；>=100→capital_类型；其余→building_类型。 */
    public static String buildingImageName(int type, int appearance) {
        if (type <= 0) return null;
        if (type >= 100) return "capital_" + (type - 100 < 10 ? "0" : "") + (type - 100);
        if (type >= 16 && type <= 19) type = 15;
        if (type >= 31 && type <= 34) return "building_31_" + (appearance < 1 ? 1 : appearance);
        return "building_" + type;
    }

    /** 建筑图标：优先新图集 assets/building/，缺失时回退旧的 btl/building_N.png。 */
    private Bitmap buildingIcon(int type, int appearance) {
        String name = buildingImageName(type, appearance);
        if (name == null) return null;
        Bitmap b = buildingByName.get(name);
        if (b == null) {
            b = load("building/" + name + ".webp");
            if (b != null) buildingByName.put(name, b);
        }
        if (b == null && buildingBmps != null) {
            int t = type >= 100 ? type - 100 : type;
            if (t >= 16 && t <= 19) t = 15;
            b = buildingBmps.get(t);
        }
        return b;
    }

    /**
     * 重建省规划归属表：省份归属 = 军团归属[省规划坐标]；
     * 若该坐标已失联（裁剪/扩展后越界）或归属为中立，
     * 则用该省内城市（建筑/39兵种）所在格的军团归属作为省份颜色来源。
     */
    private void rebuildProvinceOwner() {
        provinceOwnerLegion = null;
        if (mapData == null || mapData.provinces == null) return;
        int n = mapData.getTotalTiles();
        int w = mapData.width;
        int[] prov = mapData.provinces;
        byte[] belongs = mapData.belongs;
        int lc = mapData.legionColors.length;
        if (belongs == null || lc == 0) return;
        Map<Integer, Integer> owner = new HashMap<>();
        for (int i = 0; i < n; i++) {
            int pv = prov[i];
            if (pv == 0 || pv == 0xFFFF || pv >= n || pv >= belongs.length) continue;
            int leg = belongs[pv] & 0xFF;
            if (leg != 0xFF && leg < lc && !owner.containsKey(pv)) {
                owner.put(pv, leg);
            }
        }
        // 收集兵种段里的城市（type=39）坐标
        java.util.Set<Integer> cityIdxs = null;
        if (mapData.armies != null) {
            for (MapData.Army a : mapData.armies) {
                if (a.type == 39) {
                    int ai = a.y * w + a.x;
                    if (ai >= 0 && ai < n) {
                        if (cityIdxs == null) cityIdxs = new java.util.HashSet<>();
                        cityIdxs.add(ai);
                    }
                }
            }
        }
        // 城市兜底：省份归属失联/中立时，用该省城市所在格的军团归属
        for (int i = 0; i < n; i++) {
            int pv = prov[i];
            if (pv == 0 || pv == 0xFFFF) continue;
            if (owner.containsKey(pv)) continue;
            boolean isCity = mapData.getBuildingId(i % w, i / w) > 0
                    || (cityIdxs != null && cityIdxs.contains(i));
            if (!isCity) continue;
            int leg = belongs[i] & 0xFF;
            if (leg != 0xFF && leg < lc) owner.put(pv, leg);
        }
        provinceOwnerLegion = new int[n];
        for (int i = 0; i < n; i++) {
            Integer leg = owner.get(prov[i]);
            provinceOwnerLegion[i] = leg != null ? leg : 0xFF;
        }
    }

    public void setCropRect(int x1, int y1, int x2, int y2) {
        cropRx1 = Math.min(x1, x2);
        cropRy1 = Math.min(y1, y2);
        cropRx2 = Math.max(x1, x2);
        cropRy2 = Math.max(y1, y2);
    }

    public void clearCropRect() {
        cropRx1 = cropRy1 = cropRx2 = cropRy2 = -1;
    }

    public void setProvinceView(boolean v) {
        provinceView = v;
        fullMapDirty = true;
        invalidate();
    }

    // ===== 分层显示开关（仿枭雄） =====
    public void setShowTerrainArt(boolean v) { showTerrainArt = v; fullMapDirty = true; invalidate(); }
    public void setShowBuildings(boolean v) { showBuildings = v; fullMapDirty = true; invalidate(); }
    public void setShowArmies(boolean v) { showArmies = v; invalidate(); }
    public void setShowFlags(boolean v) { showFlags = v; invalidate(); }
    public void setShowGenerals(boolean v) { showGenerals = v; invalidate(); }
    public void setShowFacilities(boolean v) { showFacilities = v; invalidate(); }
    /** 归属/省区着色浓度：0x55 淡 / 0x99 中 / 0xCC 浓 / 0xFF 满（不透明）。 */
    public void setOwnerAlpha(int a) { ownerAlpha = Math.max(0, Math.min(255, a)); invalidate(); }
    public int getOwnerAlpha() { return ownerAlpha; }
    public void setOwnerMultiply(boolean on) { ownerMultiply = on; invalidate(); }
    public void setOwnershipTint(boolean v) { ownershipTint = v; invalidate(); }
    public void setSelectTargetFilter(int t) { selectTargetFilter = t; }
    public boolean isOwnershipTint() { return ownershipTint; }
    public boolean isOwnerMultiply() { return ownerMultiply; }

    /** 熊式着色：陆地格才涂；「乘法叠加」时颜色×地形纹理，否则按浓度半透明覆盖。 */
    private void drawOwnerTint(Canvas c, android.graphics.Path hexPath, int gid, int color) {
        if (color == 0) return;
        if (ownerMultiply) {
            // ① 海域不涂（熊：is_sea 直接跳过）  ② 乘法叠加（熊：r×r g×g b×b）
            if (gid == 1) return;
            // ★ 必须用 Xfermode：源色 × 背景像素（也就是已经画好的地形贴图），地形才看得见
            //   （用 PorterDuffColorFilter 只会把画笔颜色变一下、根本不和背景相乘 → 纯色盖住地形 ✗）
            tilePaint.setColor(color | 0xFF000000);
            tilePaint.setXfermode(multiplyXfermode);
            c.drawPath(hexPath, tilePaint);
            tilePaint.setXfermode(null);
        } else {
            tilePaint.setColor((color & 0x00FFFFFF) | (ownerAlpha << 24));
            c.drawPath(hexPath, tilePaint);
        }
    }
    public void setShowLabels(boolean v) { showLabels = v; invalidate(); }
    public boolean isShowTerrainArt() { return showTerrainArt; }
    public boolean isShowBuildings() { return showBuildings; }
    public boolean isShowArmies() { return showArmies; }
    public boolean isShowFlags() { return showFlags; }
    public boolean isShowGenerals() { return showGenerals; }
    public boolean isShowFacilities() { return showFacilities; }
    public boolean isShowLabels() { return showLabels; }

    public boolean isProvinceView() {
        return provinceView;
    }

    public void setViewOnly(boolean v) {
        viewOnly = v;
        invalidate();
    }
    /** 兵种图标：优先普通 legion_icon_N，缺失时用 _r_ 变体。 */
    /** 兵种图标按"朝向"取：方向 0 = 朝左(legion_icon_N)，方向 1 = 朝右(legion_icon_r_N)，缺哪个用另一个兜底。 */
    private Bitmap armyIcon(int type, int direction) {
        Bitmap b = direction == 1 ? legionBmpsR.get(type) : legionBmps.get(type);
        if (b == null) b = direction == 1 ? legionBmps.get(type) : legionBmpsR.get(type);
        return b;
    }

    private Bitmap armyIcon(int type) {
        Bitmap b = legionBmps != null ? legionBmps.get(type) : null;
        if (b == null) b = legionBmpsR.get(type);
        return b;
    }

    /** 将领头像：按将领ID懒加载 assets/general/<id>.png，缓存复用。 */
    private Bitmap generalPortrait(int generalId) {
        if (generalId <= 0) return null;
        Bitmap b = generalBmps.get(generalId);
        if (b == null) {
            com.xckeji.bj.model.GeneralData g = com.xckeji.bj.model.GeneralData.BY_ID.get(generalId);
            String photo = (g != null && g.photo != null) ? g.photo : "";
            if (!photo.isEmpty()) {
                // Photo 是名字（Manstein / 姚子青）→ general/<名字>.webp；
                // 当前素材源没有时，再去已选中的模组里找一次（模组将领头像常常只有模组里有）
                b = com.xckeji.bj.mod.ModAssets.decodeWithSelectedMod(getContext(), "general/" + photo + ".webp");
                if (b == null) b = com.xckeji.bj.mod.ModAssets.decodeWithSelectedMod(getContext(), "general/" + photo + ".png");
            }
            if (b != null) generalBmps.put(generalId, b);
        }
        return b;
    }

    /** 将领头像（仿枭雄 general 层）：画在单位上方，0.8 倍图集尺寸，Y 偏移 -65。 */
    private void drawGeneralPortrait(Canvas canvas, MapData.Army a, float px, float py, float s) {
        Bitmap img = generalPortrait(a.general);
        if (img == null) return;
        float sr = s / 50f;
        // 统一按内置头像的显示框（54×60）来定尺寸：模组头像原图可能好几百像素，不能按原图尺寸放
        float refW = 54f * 1.2f * sr;      // 原来 0.8 倍，偏小，放大到 1.2 倍
        float refH = 60f * 1.2f * sr;
        float ratio = img.getHeight() / (float) Math.max(1, img.getWidth());
        float sw = refH / ratio;
        float sh = refH;
        if (sw > refW) { sw = refW; sh = refW * ratio; }
        if (sw < 2f) sw = 2f;
        if (sh < 2f) sh = 2f;
        float cx = px;
        float cy = py - 65f * sr;
        canvas.drawBitmap(img, null, new RectF(cx - sw / 2f, cy - sh / 2f, cx + sw / 2f, cy + sh / 2f), bitmapPaint);
    }

    /** 把整张地图按基准比例渲染成一张 PNG 位图（用于导出/截图分享）。 */
    public Bitmap renderFullMap() {
        if (mapData == null) return null;
        float oldScale = scale;
        float oldOx = offsetX, oldOy = offsetY;
       scale = 1f;
        // 留一格边距：位图左上角对应地图坐标 (-s,-s)，否则贴到屏幕上会整体偏一格
        offsetX = baseHex();
        offsetY = baseHex();
        hexTileCache.clear();
        cachedHexSize = -1f;
        try {
            float s = hs();
            int W = (int) Math.ceil(s * 1.5f * (mapData.width - 1) + 2f * s);
            int H = (int) Math.ceil(s * (float) Math.sqrt(3) * (mapData.height - 0.5f) + 2f * s);
            Bitmap bmp = Bitmap.createBitmap(Math.max(W, 1), Math.max(H, 1), Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            c.drawColor(0xFFe8ecef);
            for (int y = 0; y < mapData.height; y++) {
                for (int x = 0; x < mapData.width; x++) {
                    TerrainTile tile = mapData.getTile(x, y);
                    if (tile == null) continue;
                    float px = hcx(x), py = hcy(x, y);
                    int gid = tile.bmTerrain1Group;
                    int tid = tile.bmTerrain1Id;
                    if (tid == 0) tid = 1;
                    Bitmap hexBmp = getHexTileBmp(gid, tid);
                    if (hexBmp != null) {
                        // ★ 放大 3.5% 画：把贴图自带的边缘格线挤到格子外（被邻格盖住 → 看不见网格）
                        float k = s * 1.035f;
                        c.drawBitmap(hexBmp, null, new RectF(px - k, py - k, px + k, py + k), bitmapPaint);
                    }
                    buildHexPath(px, py);
                    // ★ 网格已按要求关闭（原来这里无条件画 ✗）
                    // c.drawPath(sharedPath, gridPaint);
                }
            }
            return bmp;
        } finally {
            scale = oldScale;
            offsetX = oldOx;
            offsetY = oldOy;
            hexTileCache.clear();
            cachedHexSize = -1f;
            invalidate();
        }
    }

    /** 切换画质档位（0.5 / 0.75 / 1.0）。会清掉所有离屏缓存并重新居中。 */
    public void setQualityScale(float q) {
        if (q <= 0f) q = 1f;
        if (Math.abs(q - qualityScale) < 0.001f) return;
        qualityScale = q;
        hexTileCache.clear();
        cachedHexSize = -1f;
        fullMapCache = null;
        fullMapDirty = true;
        if (mapData != null) {          // 还没加载地图（启动时恢复档位）就只记下档位
            centerMap();
            clamp();
        }
        invalidate();
    }

    public float getQualityScale() { return qualityScale; }

    /** 整张地图是否完全落在视口内（此时可用整图位图缓存，一帧一次 drawBitmap）。 */
    private boolean mapFitsViewport() {
        if (mapData == null || getWidth() <= 0 || getHeight() <= 0) return false;
        float s = hs();
        float W = s * 1.5f * mapData.width + s;
        float H = s * (float) Math.sqrt(3) * (mapData.height + 0.5f);
        return W <= getWidth() && H <= getHeight();
    }

    /** 把整张地图（底色/贴图/国家色/建筑）一次性渲染进离屏位图。 */
    private void rebuildFullMap() {
        fullMapCache = null;
        if (mapData == null || getWidth() <= 0 || getHeight() <= 0) return;
        float oldScale = scale;
        float oldOx = offsetX, oldOy = offsetY;
        scale = 1f;
        // 与 rebuildFullMap 一致：留一格边距，保证缓存位图与 overlay 的六边形坐标严格对齐
        offsetX = baseHex();
        offsetY = baseHex();
        hexTileCache.clear();
        cachedHexSize = -1f;
        try {
            float s = hs();
            int W = (int) Math.ceil(s * 1.5f * (mapData.width - 1) + 2f * s);
            int H = (int) Math.ceil(s * (float) Math.sqrt(3) * (mapData.height - 0.5f) + 2f * s);
            if (W <= 0 || H <= 0) return;
            // 像素上限：按**可用堆的 1/4**（ARGB_8888 每像素 4B）算，超了就干脆不走整图缓存。
            // 以前世界图那种 27909 MP（≈106 GB）也要先试着分配一次、靠 catch OOM 兜底，
            // 每次 fullMapDirty 都要白试一遍。148×70 的征服图在 100% 档约 10.7 MP（41 MB）照旧走缓存，
            // 降低画质档位后占用按平方下降（75% → 6 MP，50% → 2.7 MP）。
            long maxPx = Math.max(1_000_000L, Runtime.getRuntime().maxMemory() / 4L / 4L);
            if ((long) W * H > maxPx) { fullMapDirty = false; return; }
            fullMapCache = Bitmap.createBitmap(Math.max(W, 1), Math.max(H, 1), Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(fullMapCache);
            c.drawColor(0xFFe8ecef);
            boolean drawGrid = false;   // ★ 编辑器界面不显示网格（按用户要求）
            for (int y = 0; y < mapData.height; y++) {
                for (int x = 0; x < mapData.width; x++) {
                    TerrainTile tile = mapData.getTile(x, y);
                    if (tile == null) continue;
                    float px = hcx(x), py = hcy(x, y);
                    int gid = tile.bmTerrain1Group;
                    int tid = tile.bmTerrain1Id;
                    if (tid == 0) tid = 1;
                    int cellIdx = y * mapData.width + x;
                    buildHexPath(px, py);
                    boolean useSampled = overlayVisible && mapData.sampledColors != null
                            && cellIdx < mapData.sampledColors.size()
                            && mapData.sampledColors.get(cellIdx) != 0
                            && !mapData.editedCells.contains(cellIdx);
                    int baseColor = useSampled ? mapData.sampledColors.get(cellIdx) : tile.getTerrainColor();
                    tilePaint.setColor(baseColor);
                    c.drawPath(sharedPath, tilePaint);
                    if (useSampled) {
                        // 不画贴图
                    } else {
                        c.save();
                        c.clipPath(sharedPath);
                        float hh = s;
                        Rect dst = new Rect((int) (px - hh), (int) (py - hh),
                                (int) (px + hh), (int) (py + hh));
                        dst.inset(-Math.max(1, (int)(hh * 0.035f)), -Math.max(1, (int)(hh * 0.035f)));   // ★ 扩 3.5% 盖住贴图格线
                        if (gid == 0 && landBmp != null) {
                            c.drawBitmap(landBmp, null, dst, bitmapPaint);
                        } else if (gid == 1 && seaBmp != null) {
                            c.drawBitmap(seaBmp, null, dst, bitmapPaint);
                        } else {
                            if (landBmp != null) c.drawBitmap(landBmp, null, dst, bitmapPaint);
                            String base = G2B.get(gid);
                            if (base != null && terrainBmps != null) {
                                Bitmap bmp = terrainBmps.get(gid + "_" + tid);
                                if (bmp == null && tid > 1) bmp = terrainBmps.get(gid + "_1");
                                if (bmp != null) c.drawBitmap(bmp, null, dst, bitmapPaint);
                            }
                        }
                        c.restore();
                    }
                    // 半透明覆盖层：省区视图=叠省区颜色且地形照常显示；否则叠国家归属颜色
                    if (provinceView) {
                        int pv = (mapData.provinces != null && cellIdx < mapData.provinces.length)
                                ? mapData.provinces[cellIdx] : 0;
                        if (pv != 0 && pv != 0xFFFF) {
                            drawOwnerTint(c, sharedPath, gid, provinceColor(pv));
                        }
                    } else if (ownershipTint) {
                        int leg = (provinceOwnerLegion != null && cellIdx < provinceOwnerLegion.length)
                                ? provinceOwnerLegion[cellIdx] : 0xFF;
                        if (leg != 0xFF && leg >= 0 && leg < mapData.legionColors.length) {
                            drawOwnerTint(c, sharedPath, gid, mapData.legionColors[leg]);
                        }
                    }
                    int bid = mapData.getBuildingId(x, y);
                    Bitmap bb = (showBuildings && bid > 0)
                            ? buildingIcon(bid, bAppearanceByCell != null && cellIdx < bAppearanceByCell.length
                                    ? bAppearanceByCell[cellIdx] : 0)
                            : null;
                    if (bb != null) {
                        drawBuildingCircle(c, bb, px, py, s);   // ★ 圆形绘制
                    }
                    if (drawGrid) {
                        gridPaint.setStrokeWidth(Math.max(0.5f, 0.8f));
                        c.drawPath(sharedPath, gridPaint);
                    }
                }
            }
        } catch (OutOfMemoryError oom) {
            fullMapCache = null; // 超大图内存不足时退回逐格绘制
            fullMapDirty = false; // 避免每帧反复尝试分配
        } finally {
            scale = oldScale;
            offsetX = oldOx;
            offsetY = oldOy;
            hexTileCache.clear();
            cachedHexSize = -1f;
        }

    }

    /** 动态覆盖层：兵种标记、截取框、图填引导图（整图缓存模式下也每帧绘制）。 */
    private void drawDynamicOverlays(Canvas canvas) {
        drawArmyMarkers(canvas);
        drawTraps(canvas);
        drawCityNames(canvas);
        drawBuildingFacilities(canvas);
        drawCropRect(canvas);
        drawGuideImage(canvas);
    }

    /** 加载全部国旗（模组目录 + 内置 assets 的编号并集）。 */
    private void loadAllFlags() {
        if (flagBmps == null) flagBmps = new HashMap<>();
        java.util.TreeSet<Integer> flagIds = com.xckeji.bj.mod.ModAssets.idsIn(getContext(), "flag", "flag_");
        if (flagIds.isEmpty()) for (int i = 1; i <= 48; i++) flagIds.add(i);
        int ok = 0;
        for (int i : flagIds) {
            Bitmap b = load("flag/flag_" + i + ".png");
            if (b != null) { flagBmps.put(i, b); ok++; }
        }
        android.util.Log.i("MOD", "国旗加载：候选 " + flagIds.size() + " 个，成功 " + ok + " 个");
    }

    /** 当前已加载的国旗数量（供「模组」页诊断显示）。 */
    public int loadedFlagCount() {
        return flagBmps == null ? 0 : flagBmps.size();
    }

    /**
     * 取国旗位图：**按国家 ID** 找 flag_&lt;ID&gt;.png（模组的国旗编号就是国家ID）。
     * 注意不要用 CountrySettings.json 的 Logo 兜底——模组里「俄罗斯」Id=66 的 Logo 写的是 5（苏联），
     * 用它就会把苏联旗显示成俄罗斯的旗。没有对应文件就不画旗。
     */
    private Bitmap flagFor(int country) {
        if (country <= 0) return null;
        if (flagBmps == null) flagBmps = new HashMap<>();
        Bitmap b = flagBmps.get(country);
        if (b == null) {
            b = load("flag/flag_" + country + ".png");
            if (b != null && flagBmps != null) flagBmps.put(country, b);
        }
        return b;
    }

    /** 在 (px,py) 居中绘制小国旗（归属 -> 国家 -> flag_N.png），中立不画。 */
    private void drawCountryFlag(Canvas canvas, float px, float py, float size, int legion) {
        if (legion == 0xFF || legion == 0xFFFF || legion < 0
                || mapData.legionCountries == null
                || legion >= mapData.legionCountries.length || flagBmps == null) return;
        Bitmap flag = flagFor(mapData.legionCountries[legion]);
        if (flag == null) return;
        float fw = size;
        float fh = fw * flag.getHeight() / (float) flag.getWidth();
        canvas.drawBitmap(flag, null,
                new RectF(px - fw / 2f, py - fh / 2f, px + fw / 2f, py + fh / 2f), bitmapPaint);
    }

    /** 兵种右下角：国旗 + 编制黑底块（仿枭雄布局：旗在右下角，编制块在旗左侧）。 */
    private void drawFlagAndFormation(Canvas canvas, MapData.Army a,
                                      float px, float py, float iconSize, float s, int legion) {
        float fw = 0f, fh = 0f;
        if (legion != 0xFF && legion >= 0 && mapData.legionCountries != null
                && legion < mapData.legionCountries.length && flagBmps != null) {
            Bitmap flag = flagFor(mapData.legionCountries[legion]);
            if (flag != null) {
                fw = iconSize * 0.55f;
                fh = fw * flag.getHeight() / (float) flag.getWidth();
                canvas.drawBitmap(flag, null,
                        new RectF(px + iconSize / 2f - fw, py + iconSize / 2f - fh,
                                px + iconSize / 2f, py + iconSize / 2f), bitmapPaint);
            }
        }
        // 编制：formation.png 重复显示 count 个（最多4，仿枭雄），黑底堆在右下角、旗子左侧
        int count = (a.raw != null && a.raw.length > 4) ? (a.raw[4] & 0xFF) : 1;
        if (count < 1) count = 1;
        if (count > 4) count = 4;
        if (formationIcon != null && s >= 6) {
            float icon = s * 0.28f;
            float pad = s * 0.04f;
            float right = px + iconSize / 2f - (fw > 0 ? fw + s * 0.05f : 0);
            float totalH = count * icon + (count - 1) * pad;
            float left = right - icon;
            float top = py + iconSize / 2f - totalH - s * 0.02f;
            float m = s * 0.05f;
            formationBg.setColor(0xB3000000);
            canvas.drawRoundRect(new RectF(left - m, top - m, right + m, top + totalH + m),
                    s * 0.08f, s * 0.08f, formationBg);
            for (int i = 0; i < count; i++) {
                float y = top + i * (icon + pad);
                canvas.drawBitmap(formationIcon, null,
                        new RectF(left, y, right, y + icon), bitmapPaint);
            }
        }
        // ★ 军衔：level_2~6.png（军衔字段 u8@0x12），画在单位左上角
        int rank = (a.raw != null && a.raw.length > 0x12) ? (a.raw[0x12] & 0xFF) : 0;
        if (rank > 6) rank = 6;
        if (rank >= 2 && s >= 6 && rankIcons[rank] != null) {
            float ri = s * 0.34f;
            float rx = px - iconSize / 2f + s * 0.03f;
            float ry = py - iconSize / 2f + s * 0.02f;
            formationBg.setColor(0xB3000000);
            float m2 = s * 0.035f;
            canvas.drawRoundRect(new RectF(rx - m2, ry - m2, rx + ri + m2, ry + ri + m2),
                    s * 0.06f, s * 0.06f, formationBg);
            canvas.drawBitmap(rankIcons[rank], null, new RectF(rx, ry, rx + ri, ry + ri), bitmapPaint);
        }
    }

    /** 建筑基础设施图标（仿枭雄）：设施网格在建筑上方，防空/雷达在右侧，建筑左上角小国旗。 */
    private void drawBuildingFacilities(Canvas canvas) {
        if (mapData == null || mapData.buildings == null || mapData.buildings.isEmpty()
                || !showFacilities || !showBuildings) return;
        float s = hs();
        if (s < 5f) return;
        for (MapData.Building b : mapData.buildings) {
            if (b == null || b.raw == null || b.raw.length < 0x20) continue;
            float px = hcx(b.x), py = hcy(b.x, b.y);
            if (px + s < 0 || px - s > getWidth() || py + s < 0 || py - s > getHeight()) continue;
            byte[] raw = b.raw;
            // 建筑国旗（左上角）：归属 -> 国家 -> flag
            int bLegion = 0xFF;
            int bidx = b.y * mapData.width + b.x;
            if (mapData.belongs != null && bidx >= 0 && bidx < mapData.belongs.length) {
                bLegion = mapData.belongs[bidx] & 0xFF;
            }
            drawCountryFlag(canvas, px - s * 0.52f, py - s * 0.48f, s * 0.64f, bLegion);
            java.util.List<Bitmap> facs = new java.util.ArrayList<>();
            java.util.List<Integer> facLv = new java.util.ArrayList<>();
            if ((raw[0x1B] & 0xFF) > 0 && facAirport != null) { facs.add(facAirport); facLv.add(raw[0x1B] & 0xFF); }
            if ((raw[0x1A] & 0xFF) > 0 && facDepot != null) { facs.add(facDepot); facLv.add(raw[0x1A] & 0xFF); }
            if ((raw[0x18] & 0xFF) > 0 && facFactory != null) { facs.add(facFactory); facLv.add(raw[0x18] & 0xFF); }
            if ((raw[0x19] & 0xFF) > 0 && facLab != null) { facs.add(facLab); facLv.add(raw[0x19] & 0xFF); }
            if ((raw[0x1C] & 0xFF) > 0 && facLaunch != null) { facs.add(facLaunch); facLv.add(raw[0x1C] & 0xFF); }
            if ((raw[0x1D] & 0xFF) > 0 && facNuclear != null) { facs.add(facNuclear); facLv.add(raw[0x1D] & 0xFF); }
            if (!facs.isEmpty()) {
                float icon = s * 0.66f;      // ★ 放大（原来 0.5 太小看不清楚）
                float spacing = s * 0.06f;
                int cols = 3;
                int rows = (facs.size() + cols - 1) / cols;
                float totalW = cols * icon + (cols - 1) * spacing;
                float totalH = rows * icon + (rows - 1) * spacing;
                float gx = px - totalW / 2f;
                float gy = py - s * 0.72f - totalH / 2f;
                // ★ 完全照熊的画法：
                //   ① 先铺一层半透明圆角底（FACILITY_BG_ALPHA / FACILITY_BG_ROUND）
                //   ② 设施图标原图直绘（不裁剪、不描边 —— 图标本身已经是圆的）
                //   ③ 等级图标居中盖在图标上（熊的 lv_x = x + icon_w/2）
                // ★ 全部按【圆形】绘制：不要矩形底、不要方框 —— 每个设施图标裁成圆形 + 一圈白描边
                for (int i = 0; i < facs.size(); i++) {
                    int c = i % cols, r = i / cols;
                    float x = gx + c * (icon + spacing);
                    float y = gy + r * (icon + spacing);
                    float cx = x + icon / 2f, cy = y + icon / 2f;
                    drawBuildingCircle(canvas, facs.get(i), cx, cy, icon * 0.56f);   // 圆形 + 白描边
                    int lv = facLv.get(i);
                    if (lv >= 1 && lv <= 4 && levelIcons[lv] != null) {
                        // 等级（罗马数字 lv_1~4）居中盖在圆形图标上，同样不套方框
                        float li = icon * 0.60f;
                        canvas.drawBitmap(levelIcons[lv], null,
                                new RectF(cx - li / 2f, cy - li / 2f, cx + li / 2f, cy + li / 2f), bitmapPaint);
                    }
                }
            }
            int antiair = raw[0x16] & 0xFF;
            int radar = raw[0x17] & 0xFF;
            if (antiair > 0) {
                int tier = antiair / 10;
                int lv = antiair % 10;
                Bitmap aa = tier == 1 ? antiair1 : tier == 2 ? antiair2 : tier == 3 ? antiair3 : null;
                if (aa != null) {
                    float ai = s * 0.48f;
                    float ax = px + s * 0.6f - ai;
                    float ay = py - s * 0.28f - ai;
                    if (radar == 2 && radarIcon != null) {
                        canvas.drawBitmap(radarIcon, null,
                                new RectF(ax, ay, ax + ai, ay + ai), bitmapPaint);
                    }
                    canvas.drawBitmap(aa, null,
                            new RectF(ax, ay, ax + ai, ay + ai), bitmapPaint);
                    if (lv >= 1 && lv <= 4 && levelIcons[lv] != null) {
                        float li = ai * 0.6f;
                        canvas.drawBitmap(levelIcons[lv], null,
                                new RectF(ax + ai - li * 0.5f, ay + ai - li * 0.5f,
                                        ax + ai + li * 0.5f, ay + ai + li * 0.5f), bitmapPaint);
                    }
                }
            }
        }
    }

    /** 建筑类型的中文名（名字字段为 0 时的兜底）。 */
    private static String buildingTypeLabel(int type) {
        switch (type) {
            case 1: return "农场";
            case 2: return "风车";
            case 3: return "小镇";
            case 11: return "小城市";
            case 12: return "中城市";
            case 13: return "大城市";
            case 14: return "大都市";
            case 15: case 16: case 17: case 18: case 19: return "首都" + (type - 14);
            case 21: return "炼油厂";
            case 22: return "大工厂";
            case 23: return "核电站";
            case 31: case 32: case 33: case 34: return "军港";
            case 41: return "机场";
            case 42: return "要塞";
            case 43: return "堡垒";
            case 44: return "据点";
            case 45: return "工厂";
            default: return type > 0 ? ("建筑" + type) : "";
        }
    }

    /** 城市名字：在每个建筑格下方画一行小字（带半透明底，看得清）。 */
    private void drawCityNames(Canvas canvas) {
        if (!showCityNames || mapData == null || mapData.buildings == null || mapData.buildings.isEmpty()) return;
        float s = hs();
        if (s < 5f) return;                       // 太小就不画，免得糊成一团
        cityNameText.setTextSize(s * 0.36f);
        cityNameText.setTextAlign(Paint.Align.CENTER);
        cityNameText.setFakeBoldText(true);
        cityNameText.setColor(0xFFFFFFFF);
        cityNameBg.setColor(0xD9000000);
        for (MapData.Building b : mapData.buildings) {
            if (b == null) continue;
            float px = hcx(b.x), py = hcy(b.x, b.y);
            if (px + s < 0 || px - s > getWidth() || py + s < 0 || py - s > getHeight()) continue;
            String nm = null;
            if (cityNames != null && b.raw != null && b.raw.length > 2) {
                int id = (b.raw[2] & 0xFF) | ((b.raw[3] & 0xFF) << 8);
                nm = cityNames.get(id);
            }
            if (nm == null || nm.isEmpty()) {
                // 官方图里绝大多数建筑的「名称」字段是 0 → 兜底显示类型名，不然开关看不出效果
                nm = buildingTypeLabel(b.type);
            }
            if (nm == null || nm.isEmpty()) continue;
            float tw = cityNameText.measureText(nm);
            float ty = py + s * 0.78f;
            float pad = s * 0.08f;
            canvas.drawRoundRect(new RectF(px - tw / 2f - pad, ty - s * 0.34f,
                    px + tw / 2f + pad, ty + s * 0.10f), s * 0.12f, s * 0.12f, cityNameBg);
            canvas.drawText(nm, px, ty, cityNameText);
        }
    }

    /** 地雷/陷阱标记：官方图标（海洋 sea_trap、陆地 land_trap），右上角标等级。 */
    private void drawTraps(Canvas canvas) {
        if (mapData == null || mapData.traps == null || mapData.traps.isEmpty()) return;
        for (MapData.Trap t : mapData.traps) {
            if (t == null) continue;
            float px = hcx(t.x), py = hcy(t.x, t.y), s = hs();
            if (px + s < 0 || px - s > getWidth() || py + s < 0 || py - s > getHeight()) continue;
            TerrainTile tile = mapData.getTile(t.x, t.y);
            Bitmap icon = (tile != null && tile.bmTerrain1Group == 1)
                    ? trapSeaBmp : trapLandBmp;
            // ★ 原来画 s*1.5f（盖满整格还压到邻居），视觉上像「格子贴图被换了」；
            //   现在缩到 0.8 格 + 略微透明，让底下的地形看得见，明确是「标记」。
            float iconW = s * 0.80f;
            if (icon != null) {
                float ratio = icon.getHeight() / (float) icon.getWidth();
                float h2 = iconW * ratio;
                Paint ip = new Paint(bitmapPaint);
                ip.setAlpha(235);
                canvas.drawBitmap(icon, null,
                        new RectF(px - iconW / 2f, py - h2 / 2f,
                                px + iconW / 2f, py + h2 / 2f), ip);
            } else {
                Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
                tp.setColor(0xFFb91c1c);
                canvas.drawCircle(px, py, Math.max(4f, s * 0.32f), tp);
            }
            // 地雷国旗（图标右下角）
            drawCountryFlag(canvas, px + s * 0.42f, py + s * 0.34f, s * 0.32f, t.legion);
            // 等级角标（右上）
            if (s >= 6 && t.level > 0) {
                float bx = px + s * 0.30f, by = py - s * 0.28f;
                float br = Math.max(4f, s * 0.17f);
                Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                bgPaint.setColor(0xD9000000);
                canvas.drawCircle(bx, by, br, bgPaint);
                Paint numPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                numPaint.setColor(0xFFFFFFFF);
                numPaint.setTextSize(br * 1.1f);
                numPaint.setTextAlign(Paint.Align.CENTER);
                numPaint.setFakeBoldText(true);
                canvas.drawText(String.valueOf(t.level), bx,
                        by + numPaint.getTextSize() * 0.36f, numPaint);
            }
        }
    }

    /** 选中/多选高亮/笔刷光标（整图缓存模式下单独绘制）。 */
    private void drawSelectionOverlays(Canvas canvas) {
        if (mapData == null) return;
        float s = hs();
        // 按用户要求：选中兵种/建筑时不再描「同省」的一整片绿框
        if (mapData.multiSelectMode && mapData.selectedBlocks != null) {
            if (!selPaintReady) {
                selFill.setStyle(Paint.Style.FILL);
                selFill.setColor(0x59C026D3);            // 紫红填充
                selEdge.setStyle(Paint.Style.STROKE);
                selEdge.setColor(0xFFE040FB);            // ★ 亮紫环（高饱和）
                selGlow.setStyle(Paint.Style.STROKE);
                selGlow.setColor(0xFF000000);            // ★ 外圈黑（任何底色上都跳出来）
                selPaintReady = true;
            }
            // ★ 性能：选中格很多时只画一层环（原来每格 3 个圆 → 几千格就卡 ✗）
            int selN = mapData.selectedBlocks.size();
            boolean light = selN > 400 || s < 10f;
            for (int idx : mapData.selectedBlocks) {
                int x = idx % mapData.width, y = idx / mapData.width;
                float px = hcx(x), py = hcy(x, y);
                if (px + s < 0 || px - s > getWidth() || py + s < 0 || py - s > getHeight()) continue;
                if (!light) {
                    selGlow.setStrokeWidth(Math.max(8f, s * 0.30f));
                    canvas.drawCircle(px, py, s * 0.52f, selGlow);      // 黑外圈
                }
                canvas.drawCircle(px, py, s * 0.52f, selFill);          // 紫红填充
                selEdge.setStrokeWidth(light ? Math.max(2f, s * 0.08f) : Math.max(4f, s * 0.15f));
                canvas.drawCircle(px, py, s * 0.52f, selEdge);          // 亮紫环
            }
        }
        if (selectedX >= 0 && selectedY >= 0) {
            float px = hcx(selectedX), py = hcy(selectedX, selectedY);
            if (px + s >= 0 && px - s <= getWidth() && py + s >= 0 && py - s <= getHeight()) {
                buildHexPath(px, py);
                if (borderSelectedBmp != null) {
                    canvas.save();
                    canvas.clipPath(sharedPath);
                    RectF bounds = new RectF();
                    sharedPath.computeBounds(bounds, true);
                    float scaleFactor = Math.max(bounds.width() / borderSelectedBmp.getWidth(),
                            bounds.height() / borderSelectedBmp.getHeight());
                    float drawW = borderSelectedBmp.getWidth() * scaleFactor;
                    float drawH = borderSelectedBmp.getHeight() * scaleFactor;
                    canvas.drawBitmap(borderSelectedBmp, null,
                            new RectF(bounds.centerX() - drawW / 2f, bounds.centerY() - drawH / 2f,
                                    bounds.centerX() + drawW / 2f, bounds.centerY() + drawH / 2f),
                            bitmapPaint);
                    canvas.restore();
                } else {
                    canvas.drawPath(sharedPath, selectedPaint);
                }
                if (mapData.brushMode) {
                    Paint brushCursor = new Paint(Paint.ANTI_ALIAS_FLAG);
                    brushCursor.setStyle(Paint.Style.STROKE);
                    brushCursor.setStrokeWidth(2f);
                    brushCursor.setColor(0xFF22c55e);
                    float brushRadius = s * (0.6f + mapData.brushRadius * 0.9f);
                    canvas.drawCircle(px, py, brushRadius, brushCursor);
                }
            }
        }
    }

    /** 兵种标记（军团色圆标/图标 + 国旗 + 等级角标）。 */
    private void drawArmyMarkers(Canvas canvas) {
        if (mapData.armies == null || !showArmies) return;
        for (MapData.Army a : mapData.armies) {
            if (a == null) continue;
            float px = hcx(a.x), py = hcy(a.x, a.y), s = hs();
            if (px + s < 0 || px - s > getWidth() || py + s < 0 || py - s > getHeight()) continue;
            int idx = a.y * mapData.width + a.x;
            int legion = (mapData.belongs != null && idx >= 0 && idx < mapData.belongs.length)
                    ? (mapData.belongs[idx] & 0xFF) : 0xFF;
            int color = 0xFF374151;
            if (legion != 0xFF && legion >= 0 && legion < mapData.legionColors.length) {
                color = mapData.legionColors[legion];
            }
            Bitmap legionIcon = null;
            if (a.type == 39) {
                if (buildingBmps != null) {
                    legionIcon = buildingBmps.get(13);
                    if (legionIcon == null) legionIcon = buildingBmps.get(11);
                }
            } else if (legionBmps != null) {
                int dir = (a.raw != null && a.raw.length > 5) ? (a.raw[5] & 0xFF) : 0;
                legionIcon = armyIcon(a.type, dir);
            }
            float r = Math.max(4f, s * 0.38f);
            float iconSize = Math.max(10f, s * 1.3f);
            if (legionIcon != null) {
                canvas.drawBitmap(legionIcon, null,
                        new RectF(px - iconSize / 2f, py - iconSize / 2f,
                                px + iconSize / 2f, py + iconSize / 2f), bitmapPaint);
            } else {
                Paint unitPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                unitPaint.setColor(color);
                canvas.drawCircle(px, py, r, unitPaint);
                unitPaint.setStyle(Paint.Style.STROKE);
                unitPaint.setStrokeWidth(Math.max(1f, s * 0.08f));
                unitPaint.setColor(0xFFFFFFFF);
                canvas.drawCircle(px, py, r, unitPaint);
            }
            // 右下角：国旗 + 编制黑底块（仿枭雄；国旗可单独关）
            if (showFlags) drawFlagAndFormation(canvas, a, px, py, iconSize, s, legion);
            // 将领头像（仿枭雄 general 层）
            if (showGenerals) drawGeneralPortrait(canvas, a, px, py, s);
            // 按用户要求：不再画「同编制」的黄圈
        }
    }

    /** 截取框选高亮（半透明绿框）。 */
    private void drawCropRect(Canvas canvas) {
        if (cropRx1 < 0 || mapData == null) return;
        float s = hs();
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (int yy = cropRy1; yy <= cropRy2; yy++) {
            for (int xx = cropRx1; xx <= cropRx2; xx++) {
                float px = hcx(xx), py = hcy(xx, yy);
                minX = Math.min(minX, px - s);
                maxX = Math.max(maxX, px + s);
                minY = Math.min(minY, py - s);
                maxY = Math.max(maxY, py + s);
            }
        }
        if (maxX > minX && maxY > minY) {
            Paint cropPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            cropPaint.setColor(0x3310b981);
            canvas.drawRect(minX, minY, maxX, maxY, cropPaint);
            cropPaint.setStyle(Paint.Style.STROKE);
            cropPaint.setStrokeWidth(3f);
            cropPaint.setColor(0xFF10b981);
            canvas.drawRect(minX, minY, maxX, maxY, cropPaint);
        }
    }

    /** 图填引导图：原图 + 黑色六边形网格，已编辑格挖空。 */
    private void drawGuideImage(Canvas canvas) {
        if (!guideVisible || guideImage == null || mapData == null) return;
        Paint guidePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        guidePaint.setAlpha(120);
        float s = hs();
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
        float maxX = Float.MIN_VALUE, maxY = Float.MIN_VALUE;
        for (int yy = 0; yy < mapData.height; yy++) {
            for (int xx = 0; xx < mapData.width; xx++) {
                float px = hcx(xx), py = hcy(xx, yy);
                if (px - s < minX) minX = px - s;
                if (py - s < minY) minY = py - s;
                if (px + s > maxX) maxX = px + s;
                if (py + s > maxY) maxY = py + s;
            }
        }
        canvas.save();
        canvas.clipRect(minX, minY, maxX, maxY);
        for (int yy = 0; yy < mapData.height; yy++) {
            for (int xx = 0; xx < mapData.width; xx++) {
                int cellIdx = yy * mapData.width + xx;
                if (mapData.editedCells.contains(cellIdx)) {
                    Path hexPath = hp(hcx(xx), hcy(xx, yy));
                    canvas.clipOutPath(hexPath);
                }
            }
        }
        guidePaint.setAlpha((int) (guideAlpha * 255));
        {
                float gcx = (minX + maxX) / 2f, gcy = (minY + maxY) / 2f;
                float ghw = (maxX - minX) / 2f * guideScale, ghh = (maxY - minY) / 2f * guideScale;
                canvas.drawBitmap(guideImage, null,
                        new RectF(gcx - ghw + guideOffX, gcy - ghh + guideOffY,
                                  gcx + ghw + guideOffX, gcy + ghh + guideOffY), guidePaint);
        }
        Paint gridP = new Paint(Paint.ANTI_ALIAS_FLAG);
        gridP.setStyle(Paint.Style.STROKE);
        gridP.setStrokeWidth(Math.max(1.5f, scale * 1.5f));
        gridP.setColor(0xFF000000);
        for (int yy = 0; yy < mapData.height; yy++) {
            for (int xx = 0; xx < mapData.width; xx++) {
                float px = hcx(xx), py = hcy(xx, yy);
                canvas.drawPath(hp(px, py), gridP);
            }
        }
        canvas.restore();
    }

    /** 设置底图（自适应铺满地图区域） */
    /** 设置底图并自动采样每个六边形中心颜色 */
    private boolean overlayVisible = true;
    public boolean isOverlayVisible() { return overlayVisible; }
    private float guideAlpha = 0.6f;
    private float guideOffX = 0, guideOffY = 0;   // 照片拖动偏移
    private float guideScale = 1f;                // 照片缩放
    public void setGuideScale(float s2) { guideScale = Math.max(0.2f, Math.min(5f, s2)); invalidate(); }
    public float getGuideScale() { return guideScale; }
    private boolean guideDragMode = false;        // 图填页：单指拖照片
    public void setGuideDragMode(boolean on) { guideDragMode = on; }
    public void resetGuideOffset() { guideOffX = 0; guideOffY = 0; invalidate(); }
    public void setGuideAlpha(float a) { guideAlpha = Math.max(0f, Math.min(1f, a)); invalidate(); }
    public void setOverlayVisible(boolean v) {
        overlayVisible = v;
        fullMapDirty = true;
        invalidate();
    }

    // 图填：导入图片直接显示原图+六边形网格，不采样不读色块
    private Bitmap guideImage;
    private boolean guideVisible = false;
    public boolean isGuideVisible() { return guideVisible; }
    public void setGuideVisible(boolean v) { guideVisible = v; invalidate(); }
    public void setGuideImage(Bitmap bmp) {
        guideImage = bmp;
        guideVisible = true;
        invalidate();
    }

    public void setOverlayImage(android.graphics.Bitmap bmp) {
        if (mapData != null) {
            mapData.overlayImage = bmp;
            overlayVisible = true;
            sampleColorsFromOverlay();
            mapData.editedCells.clear();
            invalidate();
        }
    }

    /** 从底图采样每个六边形中心点的颜色 */
    private void sampleColorsFromOverlay() {
        if (mapData == null || mapData.overlayImage == null) return;
        Bitmap src = mapData.overlayImage;
        int sw = src.getWidth(), sh = src.getHeight();
        int[] pixels = new int[sw * sh];
        src.getPixels(pixels, 0, sw, 0, 0, sw, sh);
        float s = hs();
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
        float maxX = Float.MIN_VALUE, maxY = Float.MIN_VALUE;
        for (int y = 0; y < mapData.height; y++) {
            for (int x = 0; x < mapData.width; x++) {
                float px = hcx(x), py = hcy(x, y);
                if (px - s < minX) minX = px - s;
                if (py - s < minY) minY = py - s;
                if (px + s > maxX) maxX = px + s;
                if (py + s > maxY) maxY = py + s;
            }
        }
        float ow = maxX - minX, oh = maxY - minY;
        for (int y = 0; y < mapData.height; y++) {
            for (int x = 0; x < mapData.width; x++) {
                int idx = y * mapData.width + x;
                float cx = hcx(x), cy = hcy(x, y);
                int ix = Math.max(0, Math.min(sw - 1, (int)((cx - minX) / ow * sw)));
                int iy = Math.max(0, Math.min(sh - 1, (int)((cy - minY) / oh * sh)));
                int color = pixels[iy * sw + ix];
                if (idx < mapData.sampledColors.size()) {
                    mapData.sampledColors.set(idx, color);
                }
            }
        }
    }

    /** 按当前地图尺寸重新从底图采样每格颜色（“按图生成地形”前调用，保证采样是最新的）。 */
    public void resampleOverlayColors() {
        sampleColorsFromOverlay();
    }

    /** 切换模组素材后清掉图片/贴图缓存，下一次绘制重新按新素材加载。 */
    public void clearCachesForModChange() {
        flagBmps = null; legionBmps = null; buildingBmps = null;
        legionBmpsR.clear(); generalBmps.clear();
        terrainBmps = null;
        landBmp = null; seaBmp = null; trapLandBmp = null; trapSeaBmp = null;
        facAirport = null; facDepot = null; facFactory = null; facLab = null;
        facLaunch = null; facNuclear = null;
        antiair1 = null; antiair2 = null; antiair3 = null; radarIcon = null; formationIcon = null;
        rankIcons = new Bitmap[7];
        java.util.Arrays.fill(levelIcons, null);
        imagesLoaded = false;                 // 下次绘制时 ensureImages() 会按新素材重新加载
        hexTileCache.clear(); cachedHexSize = -1f;
        fullMapDirty = true; fullMapCache = null;
    }

    private float hs() { return BASE_HEX * qualityScale * scale; }

    /** 当前档位下的基础六边形边长（scale=1 时的 hs()）。缓存位图/初始适配都要用它。 */
    private float baseHex() { return BASE_HEX * qualityScale; }
    private float hcx(int q) { return hs() * 1.5f * q + offsetX; }
    private float hcy(int q, int r) { return hs() * (float)Math.sqrt(3) * (r + (q%2==0?0:0.5f)) + offsetY; }

    private Path hp(float cx, float cy) {
        float s = hs(); Path p = new Path();
        for (int i = 0; i < 6; i++) { double a = 2*Math.PI*i/6; float x = cx + s*(float)Math.cos(a), y = cy + s*(float)Math.sin(a); if (i==0) p.moveTo(x,y); else p.lineTo(x,y); }
        p.close(); return p;
    }

    /** 复用 sharedPath 构建当前六边形路径（避免每帧 new Path）。 */
    private void buildHexPath(float cx, float cy) {
        float s = hs();
        sharedPath.reset();
        for (int i = 0; i < 6; i++) {
            double a = 2 * Math.PI * i / 6;
            float x = cx + s * (float) Math.cos(a);
            float y = cy + s * (float) Math.sin(a);
            if (i == 0) sharedPath.moveTo(x, y); else sharedPath.lineTo(x, y);
        }
        sharedPath.close();
    }

    /** 按省规划值生成稳定的颜色（0/0xFFFF=中性浅灰）。 */
    private int provinceColor(int pv) {
        if (pv == 0 || pv == 0xFFFF) return 0xFFe8ecef;
        float hue = (pv * 137.508f) % 360f;
        return android.graphics.Color.HSVToColor(new float[]{hue, 0.45f, 0.92f});
    }

    /**
     * 获取 (gid,tid) 的六边形贴图缓存：底色+贴图一次性 clip 到六边形，
     * 之后每帧只需一次 drawBitmap，不再逐格 clipPath。
     */
    private Bitmap getHexTileBmp(int gid, int tid) {
        float s = hs();
        if (s != cachedHexSize) {
            hexTileCache.clear();
            cachedHexSize = s;
        }
        String key = gid + "_" + tid;
        Bitmap cached = hexTileCache.get(key);
        if (cached != null) return cached;

        int size = (int) Math.ceil(s * 2);
        if (size <= 0) return null;
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Path p = new Path();
        float cx = size / 2f, cy = size / 2f;
        for (int i = 0; i < 6; i++) {
            double a = 2 * Math.PI * i / 6;
            float x = cx + s * (float) Math.cos(a);
            float y = cy + s * (float) Math.sin(a);
            if (i == 0) p.moveTo(x, y); else p.lineTo(x, y);
        }
        p.close();

        Paint basePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        basePaint.setColor(TerrainColors.getColor(gid));
        c.drawPath(p, basePaint);

        Bitmap tex = null;
        if (gid == 0) tex = landBmp;
        else if (gid == 1) tex = seaBmp;
        else {
            String base = G2B.get(gid);
            if (base != null && terrainBmps != null) {
                tex = terrainBmps.get(gid + "_" + tid);
                if (tex == null && tid > 1) tex = terrainBmps.get(gid + "_1");
            }
            if (tex == null) tex = landBmp;
        }
        if (tex != null) {
            Paint texPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            texPaint.setFilterBitmap(true);
            texPaint.setColor(0xFFFFFFFF); // 白色 = 不染色
            c.save();
            c.clipPath(p);
            c.drawBitmap(tex, null, new RectF(0, 0, size, size), texPaint);
            c.restore();
        }
        hexTileCache.put(key, bmp);
        return bmp;
    }

    private void centerMap() {
        if (mapData == null || getWidth() <= 0 || getHeight() <= 0) return;
        float b = baseHex();
        float mw = b*1.5f*mapData.width+b, mh = b*(float)Math.sqrt(3)*(mapData.height+0.5f);
        float sx = (getWidth()-40)/mw, sy = (getHeight()-40)/mh;
        scale = Math.min(sx, sy); if (scale < 0.3f) scale = 0.3f; if (scale > 1.5f) scale = 1.5f;
        offsetX = (getWidth() - mw())/2f; offsetY = (getHeight() - mh())/2f;
    }
    private float mw() { return hs()*1.5f*mapData.width + hs(); }
    private float mh() { return hs()*(float)Math.sqrt(3)*(mapData.height+0.5f); }
    private void clamp() {
        // ★ mapData 为空时 mw()/mh() 会解引用 mapData.width → NPE。
        //   启动时 setQualityScale() 会在还没加载地图的情况下调用到这里（选过 75%/50% 后重启必崩），
        //   所以保护必须放在 clamp() 内部，而不是只放在某一个调用方。
        if (mapData == null) return;
        float W = mw(), H = mh(), vw = getWidth(), vh = getHeight();
        if (vw <= 0 || vh <= 0) return;
        // 地图小于视口时居中；大于视口时允许平移但边缘与屏幕对齐（无死区）
        if (W <= vw) offsetX = (vw - W) / 2f;
        else { if (offsetX > 0) offsetX = 0; if (offsetX + W < vw) offsetX = vw - W; }
        if (H <= vh) offsetY = (vh - H) / 2f;
        else { if (offsetY > 0) offsetY = 0; if (offsetY + H < vh) offsetY = vh - H; }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (mapData == null) {
            Paint p = new Paint(); p.setColor(0xFF9ca3af); p.setTextSize(18); p.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("请加载地图文件", getWidth()/2f, getHeight()/2f, p); return;
        }

        // 首次绘制时加载图片
        ensureImages();

        canvas.drawColor(0xFFe8ecef);

        // 0. 底图（最底层背景，铺满整个地图区域）
        if (overlayVisible && mapData != null && mapData.overlayImage != null) {
            Paint overlayPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            float s = hs();
            float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
            float maxX = Float.MIN_VALUE, maxY = Float.MIN_VALUE;
            for (int yy = 0; yy < mapData.height; yy++) {
                for (int xx = 0; xx < mapData.width; xx++) {
                    float px = hcx(xx), py = hcy(xx, yy);
                    if (px - s < minX) minX = px - s;
                    if (py - s < minY) minY = py - s;
                    if (px + s > maxX) maxX = px + s;
                    if (py + s > maxY) maxY = py + s;
                }
            }
            canvas.drawBitmap(mapData.overlayImage, null,
                new RectF(minX, minY, maxX, maxY), overlayPaint);
        }

        // 整图可见：用离屏位图缓存，一帧只 drawBitmap 一次，大地图不再逐格重绘
        if (mapFitsViewport()) {
            if (fullMapCache == null || fullMapDirty) {
                rebuildFullMap();
                fullMapDirty = false;
            }
            if (fullMapCache != null) {
                float s = hs();
                // 位图按基准比例(20px)绘制，这里按当前缩放等比拉到屏幕上：
                // 左边缘 = hcx(0) - s，正好对应位图里的 -s 边距，因此 overlay 与地形严格重合
                float k = s / baseHex();
                float W = fullMapCache.getWidth() * k;
                float H = fullMapCache.getHeight() * k;
                canvas.drawBitmap(fullMapCache, null,
                        new RectF(offsetX - s, offsetY - s, offsetX - s + W, offsetY - s + H),
                        bitmapPaint);
                drawSelectionOverlays(canvas);
                drawDynamicOverlays(canvas);
                return;
            }
        }

        // 可见范围裁剪：只遍历视口内的格子，大地图放大时不遍历整图
        float vs = hs();
        int vx0 = Math.max(0, (int) Math.floor(-offsetX / (1.5f * vs)) - 1);
        int vx1 = Math.min(mapData.width - 1,
                (int) Math.ceil((getWidth() - offsetX) / (1.5f * vs)) + 1);
        int vy0 = Math.max(0, (int) Math.floor(-offsetY / (vs * (float) Math.sqrt(3))) - 2);
        int vy1 = Math.min(mapData.height - 1,
                (int) Math.ceil((getHeight() - offsetY) / (vs * (float) Math.sqrt(3))) + 2);
        // 刷省反馈画笔：绿色描边 + 白底黑字编号
        Paint provincePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        provincePaint.setStyle(Paint.Style.STROKE);
        provincePaint.setStrokeWidth(3f);
        provincePaint.setColor(0xFF22c55e);
        Paint feedbackBg = new Paint();
        feedbackBg.setColor(0xFFFFFFFF);
        Paint feedbackTxt = new Paint(Paint.ANTI_ALIAS_FLAG);
        feedbackTxt.setTextSize(13);
        feedbackTxt.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        feedbackTxt.setColor(0xFF111827);
        for (int y = vy0; y <= vy1; y++) {
            for (int x = vx0; x <= vx1; x++) {
                TerrainTile tile = mapData.getTile(x, y);
                if (tile == null) continue;

                float px = hcx(x), py = hcy(x, y), s = hs();
                if (px + s < 0 || px - s > getWidth() || py + s < 0 || py - s > getHeight()) continue;

                int gid = tile.bmTerrain1Group;
                int tid = tile.bmTerrain1Id;
                if (tid == 0) tid = 1;

                int cellIdx = y * mapData.width + x;
                boolean useSampled = overlayVisible && mapData.sampledColors != null
                    && cellIdx < mapData.sampledColors.size()
                    && mapData.sampledColors.get(cellIdx) != 0
                    && !mapData.editedCells.contains(cellIdx);
                int baseColor = useSampled ? mapData.sampledColors.get(cellIdx) : tile.getTerrainColor();

                // 1. 底色（地形颜色）
                buildHexPath(px, py);
                tilePaint.setColor(baseColor);
                canvas.drawPath(sharedPath, tilePaint);

                // 2. clip + 贴图（采样色格子不画贴图，只显示纯色）
                if (useSampled) {
                    // 不画贴图
                } else {
                    canvas.save();
                    canvas.clipPath(sharedPath);
                    float hh = hs();
                    float hx1 = px - hh, hy1 = py - hh, hx2 = px + hh, hy2 = py + hh;
                    Rect dst = new Rect((int) hx1, (int) hy1, (int) hx2, (int) hy2);
                    dst.inset(-Math.max(1, (int)((hx2-hx1) * 0.035f * 0.5f)), -Math.max(1, (int)((hy2-hy1) * 0.035f * 0.5f)));   // ★ 扩 3.5%
                    if (gid == 0 && landBmp != null) {
                        canvas.drawBitmap(landBmp, null, dst, bitmapPaint);
                    } else if (gid == 1 && seaBmp != null) {
                        canvas.drawBitmap(seaBmp, null, dst, bitmapPaint);
                    } else {
                        if (landBmp != null) {
                            canvas.drawBitmap(landBmp, null, dst, bitmapPaint);
                        }
                        String base = G2B.get(gid);
                        if (base != null && terrainBmps != null) {
                            String key = gid + "_" + tid;
                            Bitmap bmp = terrainBmps.get(key);
                            if (bmp == null && tid > 1) bmp = terrainBmps.get(gid + "_1");
                            if (bmp != null) {
                                canvas.drawBitmap(bmp, null, dst, bitmapPaint);
                            }
                        }
                    }
                    canvas.restore();
                }

                // 半透明覆盖层：省区视图=叠省区颜色且地形照常显示；否则叠国家归属颜色
                if (provinceView) {
                    int pv = (mapData.provinces != null && cellIdx < mapData.provinces.length)
                            ? mapData.provinces[cellIdx] : 0;
                    if (pv != 0 && pv != 0xFFFF) {
                        drawOwnerTint(canvas, sharedPath, gid, provinceColor(pv));
                    }
                } else if (ownershipTint) {
                    int leg = (provinceOwnerLegion != null && cellIdx < provinceOwnerLegion.length)
                            ? provinceOwnerLegion[cellIdx] : 0xFF;
                    if (leg != 0xFF && leg >= 0 && leg < mapData.legionColors.length) {
                        drawOwnerTint(canvas, sharedPath, gid, mapData.legionColors[leg]);
                    }
                }

                // 3. 网格（缩得太小时网格不可见，跳过以省大量 drawPath）
                if (s >= 3f) {
                    // ★ 网格已按要求关闭（原来这里无条件画 ✗）
                    // canvas.drawPath(sharedPath, gridPaint);
                }

                // 刷省反馈：刚划入的地块显示绿色描边 + 省区编号文字
                if (mapData.provinceEditMode && mapData.provinceBrushSeed >= 0
                        && mapData.provinces != null && cellIdx < mapData.provinces.length
                        && mapData.provinces[cellIdx] == mapData.provinceBrushSeed
                        && mapData.editedCells.contains(cellIdx)) {
                    canvas.drawPath(sharedPath, provincePaint);
                    String lb = "#" + mapData.provinces[cellIdx];
                    float tw = feedbackTxt.measureText(lb);
                    canvas.drawRect(px - tw / 2f - 3, py - 10, px + tw / 2f + 3, py + 10, feedbackBg);
                    canvas.drawText(lb, px - tw / 2f, py + 4, feedbackTxt);
                }

                // 4. 多选高亮
                if (mapData.multiSelectMode && mapData.selectedBlocks.contains(cellIdx)) {
                    canvas.drawPath(sharedPath, multiPaint);
                }
                // 5. 当前选中（自定义边框图片）
                if (selectedX == x && selectedY == y) {
                    if (borderSelectedBmp != null) {
                        canvas.save();
                        canvas.clipPath(sharedPath);
                        RectF bounds = new RectF();
                        sharedPath.computeBounds(bounds, true);
                        float bw = bounds.width();
                        float bh = bounds.height();
                        float imgW = borderSelectedBmp.getWidth();
                        float imgH = borderSelectedBmp.getHeight();
                        // 保持宽高比，铺满六边形
                        float scaleFactor = Math.max(bw / imgW, bh / imgH);
                        float drawW = imgW * scaleFactor;
                        float drawH = imgH * scaleFactor;
                        float left = bounds.centerX() - drawW / 2f;
                        float top = bounds.centerY() - drawH / 2f;
                        canvas.drawBitmap(borderSelectedBmp, null,
                            new RectF(left, top, left + drawW, top + drawH),
                            bitmapPaint);
                        canvas.restore();
                    } else {
                        canvas.drawPath(sharedPath, selectedPaint);
                    }
                }

                // 5. 画笔光标（画笔模式下跟随触摸位置，显示笔刷范围）
                if (mapData != null && mapData.brushMode && selectedX == x && selectedY == y) {
                    Paint brushCursor = new Paint(Paint.ANTI_ALIAS_FLAG);
                    brushCursor.setStyle(Paint.Style.STROKE);
                    brushCursor.setStrokeWidth(2f);
                    brushCursor.setColor(0xFF22c55e);
                    // 笔刷半径：每圈约增加 hex 间距的 0.9 倍
                    float brushRadius = hs() * (0.6f + mapData.brushRadius * 0.9f);
                    canvas.drawCircle(px, py, brushRadius, brushCursor);
                }
                // 6. 建筑（铺满六角格，clipPath裁剪）
                int bid = mapData.getBuildingId(x, y);
                Bitmap bb = (showBuildings && bid > 0)
                        ? buildingIcon(bid, bAppearanceByCell != null && cellIdx < bAppearanceByCell.length
                                ? bAppearanceByCell[cellIdx] : 0)
                        : null;
                if (bb != null) {
                    drawBuildingCircle(canvas, bb, px, py, hs());   // ★ 圆形绘制
                }
            }
        }

        // 省区视图：在每个省的代表位置显示省区编号文字（颜色看不懂时看编号）
        if (provinceView && showLabels && mapData.provinces != null) {
            int n = mapData.getTotalTiles();
            java.util.Map<Integer, float[]> centers = new java.util.HashMap<>();
            java.util.Map<Integer, int[]> counts = new java.util.HashMap<>();
            for (int i = 0; i < n; i++) {
                int pv = mapData.provinces[i];
                if (pv == 0 || pv == 0xFFFF) continue;
                float px = hcx(i % mapData.width);
                float py = hcy(i % mapData.width, i / mapData.width);
                float[] c = centers.get(pv);
                if (c == null) {
                    centers.put(pv, new float[]{px, py});
                    counts.put(pv, new int[]{1});
                } else {
                    c[0] += px;
                    c[1] += py;
                    counts.get(pv)[0]++;
                }
            }
            Paint labelBg = new Paint();
            labelBg.setColor(0xFFFFFFFF);
            Paint labelBorder = new Paint();
            labelBorder.setColor(0xFF374151);
            labelBorder.setStyle(Paint.Style.STROKE);
            labelBorder.setStrokeWidth(2f);
            Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            labelPaint.setTextSize(15);
            labelPaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            labelPaint.setColor(0xFF111827);
            for (java.util.Map.Entry<Integer, float[]> e : centers.entrySet()) {
                int cnt = counts.get(e.getKey())[0];
                float cx = e.getValue()[0] / cnt;
                float cy = e.getValue()[1] / cnt;
                String label = "#" + e.getKey();
                float tw = labelPaint.measureText(label);
                canvas.drawRect(cx - tw / 2f - 4, cy - 12, cx + tw / 2f + 4, cy + 12, labelBg);
                canvas.drawRect(cx - tw / 2f - 4, cy - 12, cx + tw / 2f + 4, cy + 12, labelBorder);
                canvas.drawText(label, cx - tw / 2f, cy + 5, labelPaint);
            }
        }

        // 兵种标记：统一走 drawArmyMarkers，才会跟随「显示设置 → 兵种」开关（放大时也要能隐藏）
        drawArmyMarkers(canvas);

        // 地雷/陷阱标记（整图缓存模式以外逐帧绘制）
        drawTraps(canvas);
        // 建筑上的设施图标 + 城市国旗：放大后走的是这条路径，之前漏了，所以放大看不到国旗
        drawBuildingFacilities(canvas);

        // 截取框选高亮（半透明绿框）
        if (cropRx1 >= 0 && mapData != null) {
            float s = hs();
            float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
            float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
            for (int yy = cropRy1; yy <= cropRy2; yy++) {
                for (int xx = cropRx1; xx <= cropRx2; xx++) {
                    float px = hcx(xx), py = hcy(xx, yy);
                    minX = Math.min(minX, px - s);
                    maxX = Math.max(maxX, px + s);
                    minY = Math.min(minY, py - s);
                    maxY = Math.max(maxY, py + s);
                }
            }
            if (maxX > minX && maxY > minY) {
                Paint cropPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                cropPaint.setColor(0x3310b981);
                canvas.drawRect(minX, minY, maxX, maxY, cropPaint);
                cropPaint.setStyle(Paint.Style.STROKE);
                cropPaint.setStrokeWidth(3f);
                cropPaint.setColor(0xFF10b981);
                canvas.drawRect(minX, minY, maxX, maxY, cropPaint);
            }
        }

        // 7. 引导图（图填：底图之上铺原图+六边形网格，已编辑格子不遮盖）
        if (guideVisible && guideImage != null) {
            Paint guidePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            guidePaint.setAlpha(120);
            float s = hs();
            float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
            float maxX = Float.MIN_VALUE, maxY = Float.MIN_VALUE;
            for (int yy = 0; yy < mapData.height; yy++) {
                for (int xx = 0; xx < mapData.width; xx++) {
                    float px = hcx(xx), py = hcy(xx, yy);
                    if (px - s < minX) minX = px - s;
                    if (py - s < minY) minY = py - s;
                    if (px + s > maxX) maxX = px + s;
                    if (py + s > maxY) maxY = py + s;
                }
            }
            canvas.save();
            canvas.clipRect(minX, minY, maxX, maxY);
            // 对已编辑的格子挖掉，让地形显示
            for (int yy = 0; yy < mapData.height; yy++) {
                for (int xx = 0; xx < mapData.width; xx++) {
                    int cellIdx = yy * mapData.width + xx;
                    if (mapData.editedCells.contains(cellIdx)) {
                        Path hexPath = hp(hcx(xx), hcy(xx, yy));
                        canvas.clipOutPath(hexPath);
                    }
                }
            }
            // 原图铺满（已编辑区域被挖空）
            guidePaint.setAlpha((int) (guideAlpha * 255));
            {
                float gcx = (minX + maxX) / 2f, gcy = (minY + maxY) / 2f;
                float ghw = (maxX - minX) / 2f * guideScale, ghh = (maxY - minY) / 2f * guideScale;
                canvas.drawBitmap(guideImage, null,
                        new RectF(gcx - ghw + guideOffX, gcy - ghh + guideOffY,
                                  gcx + ghw + guideOffX, gcy + ghh + guideOffY), guidePaint);
        }
            // 上面画黑色六边形网格
            Paint gridP = new Paint(Paint.ANTI_ALIAS_FLAG);
            gridP.setStyle(Paint.Style.STROKE);
            gridP.setStrokeWidth(Math.max(1.5f, scale * 1.5f));
            gridP.setColor(0xFF000000);
            for (int yy = 0; yy < mapData.height; yy++) {
                for (int xx = 0; xx < mapData.width; xx++) {
                    float px = hcx(xx), py = hcy(xx, yy);
                    Path hexPath = hp(px, py);
                    canvas.drawPath(hexPath, gridP);
                }
            }
            canvas.restore();
        }

        // ★ 选中高亮画在最末尾：无论走「整图缓存」还是「逐格回退」，都一定看得见
        try { drawSelectionOverlays(canvas); } catch (Exception ignored) { }
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) { super.onSizeChanged(w,h,ow,oh); if (mapData != null && w > 0 && h > 0) centerMap(); }

    // 坐标转换（提高命中精度，阈值从0.9放大到1.05）
    private PointF p2h(float px, float py) {
        if (mapData == null) return null;
        float s = hs(), aq = (px-offsetX)/(s*1.5f);
        for (int d = -1; d <= 1; d++) { int q = Math.round(aq)+d; float odd = (q%2==0?0:0.5f), ar = (py-offsetY)/(s*(float)Math.sqrt(3))-odd; int r = Math.round(ar); if (Math.hypot(px-hcx(q), py-hcy(q,r)) < s*1.05f) return new PointF(q,r); }
        return null;
    }

    private float lastX, lastY, lastDist = -1, downX, downY;
    private float pivotX, pivotY;
    private float lastPivotX, lastPivotY;
    private boolean dragging = false, scaling = false;
    // 一次省区笔刷手势内只保存一次撤销快照
    private boolean provinceUndoSaved = false;
    // 惯性滑动（fling）：手指快速划过时地图继续滑行
    private float flingVx = 0, flingVy = 0;
    private final Runnable flingRunnable = new Runnable() {
        @Override public void run() {
            if (mapData == null || (Math.abs(flingVx) < 2 && Math.abs(flingVy) < 2)) {
                flingVx = 0; flingVy = 0;
                return;
            }
            offsetX += flingVx / 60f;
            offsetY += flingVy / 60f;
            flingVx *= 0.92f;
            flingVy *= 0.92f;
            clamp();
            invalidate();
            postDelayed(this, 16);
        }
    };
    @Override public boolean onTouchEvent(MotionEvent e) {
        gestureDetector.onTouchEvent(e);
        switch (e.getAction() & MotionEvent.ACTION_MASK) {
            case MotionEvent.ACTION_DOWN:
                lastX=e.getX();lastY=e.getY();downX=e.getX();downY=e.getY();
                dragging=false;scaling=false;
                provinceUndoSaved = false;
                // 选中改到单击抬起时触发，避免想拖动却误选中格子
                stopFling();
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                scaling=true; lastDist=sp(e);
                // 记录双指中心作为缩放锚点
                if (e.getPointerCount() >= 2) {
                    lastPivotX = (e.getX(0) + e.getX(1)) / 2f;
                    lastPivotY = (e.getY(0) + e.getY(1)) / 2f;
                    pivotX = lastPivotX; pivotY = lastPivotY;
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                if (mapData == null) break;
                // 截取：手指拖动 = 选终点（松手成框）
                if (cropDragging && !viewOnly) {
                    PointF cp = p2h(e.getX(), e.getY());
                    if (cp != null && cropDragListener != null) {
                        cropDragListener.onCropDrag((int) cp.x, (int) cp.y);
                    }
                    lastX = e.getX(); lastY = e.getY();
                    break;
                }
                // 省区笔刷：选了省区且开启省区编辑时，按住拖动连续划入
                // ★ 批量页多选：手指在地图上滑动 = 连续选中经过的格子（不再只能一格一格点）
                if (!viewOnly && mapData.multiSelectMode && e.getPointerCount() == 1) {
                    PointF hp = p2h(e.getX(), e.getY());
                    if (hp != null) {
                        int bx = (int) hp.x, by = (int) hp.y;
                        if (bx >= 0 && bx < mapData.width && by >= 0 && by < mapData.height) {
                            boolean ok = true;
                            if (selectTargetFilter == 1) ok = (armyAt(bx, by) != null);
                            else if (selectTargetFilter == 2) ok = mapData.getBuildingId(bx, by) > 0;
                            if (ok) {
                                mapData.selectedBlocks.add(by * mapData.width + bx);
                                invalidate();
                                if (listener != null) listener.onTileSelected(bx, by, mapData.getTile(bx, by));
                            }
                        }
                    }
                    lastX = e.getX(); lastY = e.getY();
                    break;
                }
                if (!viewOnly && e.getPointerCount() == 1
                        && mapData.provinceEditMode && mapData.provinceBrushSeed >= 0) {
                    PointF hp = p2h(e.getX(), e.getY());
                    if (hp != null) {
                        int bx = (int) hp.x, by = (int) hp.y;
                        if (bx >= 0 && bx < mapData.width && by >= 0 && by < mapData.height) {
                            paintProvinceAt(bx, by);
                        }
                    }
                    lastX = e.getX(); lastY = e.getY();
                    break;
                }
                // 画笔模式
                if (!viewOnly && mapData.brushMode && e.getPointerCount() == 1) {
                    PointF hp = p2h(e.getX(), e.getY());
                    if (hp != null) {
                        int bx = (int)hp.x, by = (int)hp.y;
                        if (bx >= 0 && bx < mapData.width && by >= 0 && by < mapData.height) applyBrush(bx, by);
                    }
                    lastX = e.getX(); lastY = e.getY();
                    break;
                }
                // 双指缩放 + 双指拖动（以两指中心为锚点）
                if (guideDragMode && e.getPointerCount() >= 2) {
                    float nd2 = sp(e);
                    if (lastDist > 0) { guideScale = Math.max(0.2f, Math.min(5f, guideScale * (nd2 / lastDist))); invalidate(); }
                    lastDist = nd2;
                    lastX = e.getX(); lastY = e.getY();
                    break;
                }
                if (scaling && e.getPointerCount() >= 2) {
                    float nd = sp(e);
                    if (lastDist > 0) {
                        float f = nd / lastDist;
                        float os = scale;
                        scale *= f;
                        if (scale < 0.3f) scale = 0.3f;
                        if (scale > 8.0f) scale = 8.0f;      // 放大上限调大（原来 4.5 倍）
                        f = scale / os;
                        offsetX = pivotX - (pivotX - offsetX) * f;
                        offsetY = pivotY - (pivotY - offsetY) * f;
                        // 双指整体拖动：平移画面
                        float cx = (e.getX(0) + e.getX(1)) / 2f;
                        float cy = (e.getY(0) + e.getY(1)) / 2f;
                        offsetX += cx - lastPivotX;
                        offsetY += cy - lastPivotY;
                        lastPivotX = cx; lastPivotY = cy;
                        clamp(); invalidate();
                    }
                    lastDist = nd;
                    break;
                }
                break;
            case MotionEvent.ACTION_POINTER_UP:
                if (e.getPointerCount() <= 2) scaling = false;
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging=false; scaling=false;
                if (cropDragging && cropDragListener != null) cropDragListener.onCropDone();
                break;
        }
        return true;
    }

    /** 停止惯性滑动。 */
    private void stopFling() {
        removeCallbacks(flingRunnable);
        flingVx = 0;
        flingVy = 0;
    }

    /** 该格上的兵种（没有返回 null）。 */
    private MapData.Army armyAt(int x, int y) {
        if (mapData == null || mapData.armies == null) return null;
        for (MapData.Army a : mapData.armies) {
            if (a != null && a.x == x && a.y == y) return a;
        }
        return null;
    }

    // 选中一个格子（拆出独立方法，ACTION_DOWN 也能调用）
    private void selectCell(int x, int y) {
        if (mapData == null || viewOnly) return;
        int idx = y * mapData.width + x;
        // 省区笔刷：开启且已选省区时，点击地块即划入该省区
        if (mapData.provinceEditMode && mapData.provinceBrushSeed >= 0) {
            paintProvinceAt(x, y);
            return;
        }
        if (mapData.brushMode) { applyBrush(x, y); return; }
        if (mapData.multiSelectMode) {
            // 批量页的框选目标：1=只选有兵种的格，2=只选有建筑的格，0=任意地块
            boolean ok = true;
            if (selectTargetFilter == 1) {
                ok = false;
                if (mapData.armies != null) {
                    for (MapData.Army a : mapData.armies) {
                        if (a != null && a.x == x && a.y == y) { ok = true; break; }
                    }
                }
            } else if (selectTargetFilter == 2) {
                ok = mapData.getBuildingId(x, y) > 0;
            }
            if (ok) mapData.toggleBlockSelection(idx);
            selectedX = x; selectedY = y;
            invalidate();
            if (listener != null) listener.onTileSelected(x,y,mapData.getTile(x,y));
            return;
        }
        if (mapData.selectedTerrainGroup >= 0) { applyBrush(x, y); return; }
        selectedX=x;selectedY=y;
        invalidate();
        if (listener != null) listener.onTileSelected(x,y,mapData.getTile(x,y));
    }
    private float sp(MotionEvent e) { if (e.getPointerCount()<2) return 0; float dx=e.getX(0)-e.getX(1), dy=e.getY(0)-e.getY(1); return (float)Math.sqrt(dx*dx+dy*dy); }
    private void applyBrush(int x, int y) {
        if (mapData == null || viewOnly) return;
        // 兵种笔刷：锁定了兵种图标且开启了笔刷时，涂抹=批量添加兵种
        boolean armyBrush = mapData.selectedArmyType >= 0 && armyBrushHandler != null;
        if (!armyBrush && mapData.selectedTerrainGroup < 0) return;
        int g = mapData.selectedTerrainGroup;
        int radius = mapData.brushRadius;

        // 先判断是否需要保存历史（只在中心格变化或第一次涂抹时保存）
        if (mapData.historyRef != null && (mapData.lastEditX != x || mapData.lastEditY != y)) {
            mapData.historyRef.save(mapData);
            mapData.lastEditX = x;
            mapData.lastEditY = y;
        }

        // 获取半径内的所有格子
        java.util.Set<Integer> cellsToPaint = getBrushCells(x, y, radius);
        if (armyBrush) {
            for (int idx : cellsToPaint) {
                if (idx < 0 || idx >= mapData.tiles.size()) continue;
                armyBrushHandler.accept(idx % mapData.width, idx / mapData.width);
            }
            selectedX = x; selectedY = y;
            invalidate();
            return;
        }
        for (int idx : cellsToPaint) {
            if (idx < 0 || idx >= mapData.tiles.size()) continue;
            TerrainTile tt = mapData.tiles.get(idx);
            if (tt == null) continue;
            byte[] pat = mapData.getTerrainPattern(g);
            if (pat != null) tt.parseFromBytes(pat, 0);
            else tt.setTerrain(g);
            mapData.editedCells.add(idx);
        }
        // 涂地后处理被涂格子：陆地按位置选真实变体，避免整块矩形纯色贴图
        mapData.finishPaint(cellsToPaint);
        // 涂出来的海洋格，省规划跟着改成 0xFFFF（与官方编辑器一致）
        FileParser.normalizeWaterDistricts(mapData);
        selectedX = x; selectedY = y;
        invalidate();
        if (listener != null) listener.onTileSelected(x,y,mapData.getTile(x,y));
    }

    /** 获取 (cx,cy) 为中心、radius 圈范围内的所有格子索引 */
    private java.util.Set<Integer> getBrushCells(int cx, int cy, int radius) {
        java.util.Set<Integer> result = new java.util.HashSet<>();
        int w = mapData.width, h = mapData.height;
        // BFS 在六边形网格上扩展 radius 层
        boolean[][] visited = new boolean[h][w];
        java.util.Queue<int[]> queue = new java.util.LinkedList<>();
        queue.add(new int[]{cx, cy, 0});
        visited[cy][cx] = true;
        int[][] evenNeighbors = {{-1,0},{-1,-1},{0,-1},{1,-1},{1,0},{0,1}};
        int[][] oddNeighbors = {{-1,0},{0,-1},{1,-1},{1,0},{1,1},{0,1}};

        while (!queue.isEmpty()) {
            int[] cur = queue.poll();
            int x = cur[0], y = cur[1], dist = cur[2];
            result.add(y * w + x);
            if (dist >= radius) continue;
            int[][] nbs = (x % 2 == 0) ? evenNeighbors : oddNeighbors;
            for (int[] nb : nbs) {
                int nx = x + nb[0], ny = y + nb[1];
                if (nx < 0 || nx >= w || ny < 0 || ny >= h) continue;
                if (visited[ny][nx]) continue;
                visited[ny][nx] = true;
                queue.add(new int[]{nx, ny, dist + 1});
            }
        }
        return result;
    }

    /** 省区笔刷：把单个地块划入当前选中的省区（可点、可按住拖动连续刷）。 */
    private void paintProvinceAt(int x, int y) {
        if (mapData == null || viewOnly) return;
        if (x < 0 || x >= mapData.width || y < 0 || y >= mapData.height) return;
        // ★ 支持笔刷大小：一次刷一圈（1~5）
        int rad = Math.max(1, mapData.brushRadius);
        if (rad > 0) {
            for (int idx : getBrushCells(x, y, rad)) {
                int bx = idx % mapData.width, by = idx / mapData.width;
                paintProvinceOne(bx, by);
            }
            return;
        }
        paintProvinceOne(x, y);
    }

    /** 只刷一格（内部用）。 */
    private void paintProvinceOne(int x, int y) {
        if (mapData == null || viewOnly) return;
        if (x < 0 || x >= mapData.width || y < 0 || y >= mapData.height) return;
        if (!provinceUndoSaved) {
            // 用统一的撤销栈（OperationHistory 快照整张地图），这样「撤销」按钮能退回刷省前
            if (mapData.historyRef != null) mapData.historyRef.save(mapData, "刷省区");
            else mapData.saveProvinceUndo();
            provinceUndoSaved = true;
        }
        int idx = y * mapData.width + x;
        if (mapData.provinces == null || idx >= mapData.provinces.length) {
            mapData.ensureProvincesSize();
        }
        if (mapData.provinces[idx] == mapData.provinceBrushSeed) return;
        mapData.provinces[idx] = mapData.provinceBrushSeed;
        mapData.editedCells.add(idx);
        com.xckeji.bj.file.FileParser.patchProvince(mapData, idx);
        invalidate();
    }
    private class GestureListener extends GestureDetector.SimpleOnGestureListener {
        @Override public boolean onDown(MotionEvent e) { return true; }
        /** 单指拖动画面：超过系统触摸阈值才算拖动，不再 1px 就动。 */
        @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
            if (mapData == null || scaling || e2.getPointerCount() > 1) return false;
            if (mapData.brushMode && !viewOnly) return false; // 画笔模式下手指是用来画的
            if (mapData.provinceEditMode && mapData.provinceBrushSeed >= 0 && !viewOnly) return false;
            if (mapData.multiSelectMode && !viewOnly) return false;   // ★ 多选模式：手指用来框选，不要平移地图
            if (guideDragMode) {                                       // ★ 图填页：单指拖照片
                guideOffX -= distanceX;
                guideOffY -= distanceY;
                invalidate();
                return true;
            }
            dragging = true;
            stopFling();
            offsetX -= distanceX;
            offsetY -= distanceY;
            clamp();
            invalidate();
            return true;
        }
        /** 单击（没有拖动）才选中/涂抹格子。 */
        @Override public boolean onSingleTapUp(MotionEvent e) {
            if (dragging || scaling || mapData == null || viewOnly) return true;
            PointF h = p2h(e.getX(), e.getY());
            if (h == null) return true;
            int x = (int) h.x, y = (int) h.y;
            if (x >= 0 && x < mapData.width && y >= 0 && y < mapData.height) selectCell(x, y);
            return true;
        }
        /** 惯性滑动：松手后地图继续滑行一段。 */
        @Override public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
            if (mapData == null || scaling || e2.getPointerCount() > 1) return false;
            if (mapData.brushMode && !viewOnly) return false;
            if (mapData.provinceEditMode && mapData.provinceBrushSeed >= 0 && !viewOnly) return false;
            flingVx = velocityX;
            flingVy = velocityY;
            postDelayed(flingRunnable, 16);
            return true;
        }
        @Override public void onLongPress(MotionEvent e) {
            if (mapData == null || viewOnly) return; PointF h = p2h(e.getX(),e.getY()); if (h == null) return;
            int x = (int)h.x, y = (int)h.y; if (x>=0&&x<mapData.width&&y>=0&&y<mapData.height) { mapData.setBuildingId(x,y,0); if (selectedX==x&&selectedY==y&&listener!=null) listener.onTileSelected(x,y,mapData.getTile(x,y)); invalidate(); }
        }
    }
}
