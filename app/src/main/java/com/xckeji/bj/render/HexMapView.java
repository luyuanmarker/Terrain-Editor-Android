package com.xckeji.bj.render;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.Region;
import android.graphics.RectF;
import com.xckeji.bj.R;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;

import com.xckeji.bj.model.MapData;
import com.xckeji.bj.model.TerrainColors;
import com.xckeji.bj.model.TerrainTile;

import java.io.InputStream;
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
    // 纯移动模式：只允许拖动/缩放画面，点击不选中、不编辑
    private boolean viewOnly = false;
    // 省规划视图：每个地块所在省份解析后的归属军团（0xFF=无归属），
    // 优先取 军团归属[省规划坐标]，失联/中立时用该省城市的军团归属兜底
    private int[] provinceOwnerLegion;
    private OnTileSelectListener listener;
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
    // 整图缓存：整张地图都可见时，把地形/贴图/国家色/建筑渲染成一张离屏位图，
    // 之后每帧只 drawBitmap 一次，避免大地图每帧逐格重绘导致卡顿掉帧。
    private Bitmap fullMapCache;
    private boolean fullMapDirty = true;

    // 图片——懒加载，首次绘制时初始化
    private Bitmap landBmp;
    private Bitmap seaBmp;
    private Map<String, Bitmap> terrainBmps;
    private Map<Integer, Bitmap> legionBmps;
    private Map<Integer, Bitmap> flagBmps;
    private Map<Integer, Bitmap> buildingBmps;
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
            landBmp = load("map/land.png");
            seaBmp = load("map/sea.png");
            for (int i = 1; i <= 38; i++) {
                Bitmap b = load("legion/legion_icon_" + i + ".png");
                if (b != null) legionBmps.put(i, b);
            }
            for (int i = 1; i <= 49; i++) {
                Bitmap b = load("flag/flag_" + i + ".png");
                if (b != null) flagBmps.put(i, b);
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
            int[] bids = {1,2,3,11,12,13,14,15,16,17,21,22,23,31,32,33,34};
            for (int id : bids) {
                Bitmap b = load("btl/building_" + id + ".png");
                if (b != null) buildingBmps.put(id, b);
            }
        } catch (Exception ignored) {}
    }

    private Bitmap load(String path) {
        try {
            return BitmapFactory.decodeStream(getContext().getAssets().open(path));
        } catch (Exception e) { return null; }
    }

    public void setMapData(MapData data) {
        mapData = data; selectedX = -1; selectedY = -1; scale = 1f;
        clearCropRect();
        hexTileCache.clear(); cachedHexSize = -1f;
        fullMapDirty = true;
        rebuildProvinceOwner();
        post(() -> { centerMap(); invalidate(); });
    }
    public MapData getMapData() { return mapData; }
    public interface OnTileSelectListener { void onTileSelected(int x, int y, TerrainTile tile); }
    public void setOnTileSelectListener(OnTileSelectListener l) { listener = l; }
    public int getSelectedX() { return selectedX; }
    public int getSelectedY() { return selectedY; }
    public void refresh() {
        fullMapDirty = true;
        rebuildProvinceOwner();
        invalidate();
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

    public void setViewOnly(boolean v) {
        viewOnly = v;
        invalidate();
    }
    public boolean isViewOnly() { return viewOnly; }

    /** 把整张地图按基准比例渲染成一张 PNG 位图（用于导出/截图分享）。 */
    public Bitmap renderFullMap() {
        if (mapData == null) return null;
        float oldScale = scale;
        float oldOx = offsetX, oldOy = offsetY;
        scale = 1f;
        offsetX = 0;
        offsetY = 0;
        hexTileCache.clear();
        cachedHexSize = -1f;
        try {
            float s = hs();
            int W = (int) Math.ceil(s * 1.5f * mapData.width + s);
            int H = (int) Math.ceil(s * (float) Math.sqrt(3) * (mapData.height + 0.5f));
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
                    if (hexBmp != null) c.drawBitmap(hexBmp, px - s, py - s, bitmapPaint);
                    buildHexPath(px, py);
                    gridPaint.setStrokeWidth(Math.max(0.5f, scale * 0.8f));
                    c.drawPath(sharedPath, gridPaint);
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
        offsetX = 0;
        offsetY = 0;
        hexTileCache.clear();
        cachedHexSize = -1f;
        try {
            float s = hs();
            int W = (int) Math.ceil(s * 1.5f * mapData.width + s);
            int H = (int) Math.ceil(s * (float) Math.sqrt(3) * (mapData.height + 0.5f));
            if (W <= 0 || H <= 0) return;
            fullMapCache = Bitmap.createBitmap(Math.max(W, 1), Math.max(H, 1), Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(fullMapCache);
            c.drawColor(0xFFe8ecef);
            boolean drawGrid = s >= 3f;
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
                    // 默认直接用地块归属色实心填充（法国地块就是蓝），不再叠半透明滤镜
                    int terrLeg = (ownershipTint && provinceOwnerLegion != null
                            && cellIdx < provinceOwnerLegion.length)
                            ? provinceOwnerLegion[cellIdx] : 0xFF;
                    boolean solidCountry = !provinceView && ownershipTint
                            && terrLeg != 0xFF && terrLeg >= 0
                            && terrLeg < mapData.legionColors.length;
                    if (provinceView) {
                        int pv = (mapData.provinces != null && cellIdx < mapData.provinces.length)
                                ? mapData.provinces[cellIdx] : 0;
                        tilePaint.setColor(provinceColor(pv));
                        c.drawPath(sharedPath, tilePaint);
                    } else if (solidCountry) {
                        tilePaint.setColor(mapData.legionColors[terrLeg]);
                        c.drawPath(sharedPath, tilePaint);
                    } else {
                        tilePaint.setColor(baseColor);
                        c.drawPath(sharedPath, tilePaint);
                    }
                    if (provinceView || solidCountry || useSampled) {
                        // 不画贴图
                    } else {
                        c.save();
                        c.clipPath(sharedPath);
                        float hh = s;
                        Rect dst = new Rect((int) (px - hh), (int) (py - hh),
                                (int) (px + hh), (int) (py + hh));
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
                    int bid = mapData.getBuildingId(x, y);
                    if (bid > 0 && buildingBmps != null) {
                        Bitmap bb = buildingBmps.get(bid);
                        if (bb != null) {
                            c.save();
                            c.clipPath(sharedPath);
                            c.drawBitmap(bb, null,
                                    new Rect((int) (px - s), (int) (py - s),
                                            (int) (px + s), (int) (py + s)), tilePaint);
                            c.restore();
                        }
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
        drawCropRect(canvas);
        drawGuideImage(canvas);
    }

    /** 选中/多选高亮/笔刷光标（整图缓存模式下单独绘制）。 */
    private void drawSelectionOverlays(Canvas canvas) {
        if (mapData == null) return;
        float s = hs();
        if (mapData.multiSelectMode && mapData.selectedBlocks != null) {
            for (int idx : mapData.selectedBlocks) {
                int x = idx % mapData.width, y = idx / mapData.width;
                float px = hcx(x), py = hcy(x, y);
                if (px + s < 0 || px - s > getWidth() || py + s < 0 || py - s > getHeight()) continue;
                buildHexPath(px, py);
                canvas.drawPath(sharedPath, multiPaint);
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
        if (mapData.armies == null) return;
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
                legionIcon = legionBmps.get(a.type);
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
            if (legion != 0xFF && legion >= 0 && mapData.legionCountries != null
                    && legion < mapData.legionCountries.length && flagBmps != null) {
                Bitmap flag = flagBmps.get(mapData.legionCountries[legion]);
                if (flag != null) {
                    float fw = iconSize * 0.95f;
                    float fh = fw * flag.getHeight() / (float) flag.getWidth();
                    canvas.drawBitmap(flag, null,
                            new RectF(px - fw / 2f, py - iconSize / 2f - fh,
                                    px + fw / 2f, py - iconSize / 2f), bitmapPaint);
                }
            }
            if (s >= 6) {
                float br = Math.max(5f, s * 0.24f);
                float badgeX = px + iconSize / 2f - br * 0.35f;
                float badgeY = py + iconSize / 2f - br * 0.35f;
                Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                bgPaint.setColor(0xD9000000);
                canvas.drawCircle(badgeX, badgeY, br, bgPaint);
                Paint numPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                numPaint.setColor(0xFFFFFFFF);
                numPaint.setTextSize(br * 1.15f);
                numPaint.setTextAlign(Paint.Align.CENTER);
                numPaint.setFakeBoldText(true);
                canvas.drawText(String.valueOf(a.level), badgeX,
                        badgeY + numPaint.getTextSize() * 0.36f, numPaint);
            }
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
        canvas.drawBitmap(guideImage, null,
                new RectF(minX, minY, maxX, maxY), guidePaint);
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

    private float hs() { return 20f * scale; }
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
        float mw = 20f*1.5f*mapData.width+20f, mh = 20f*(float)Math.sqrt(3)*(mapData.height+0.5f);
        float sx = (getWidth()-40)/mw, sy = (getHeight()-40)/mh;
        scale = Math.min(sx, sy); if (scale < 0.3f) scale = 0.3f; if (scale > 1.5f) scale = 1.5f;
        offsetX = (getWidth() - mw())/2f; offsetY = (getHeight() - mh())/2f;
    }
    private float mw() { return hs()*1.5f*mapData.width + hs(); }
    private float mh() { return hs()*(float)Math.sqrt(3)*(mapData.height+0.5f); }
    private void clamp() {
        float W = mw(), H = mh(), vw = getWidth(), vh = getHeight();
        if (vw <= 0 || vh <= 0) return;
        if (W <= vw) offsetX = (vw-W)/2f; else { if (offsetX > 20) offsetX = 20; if (offsetX+W < vw-20) offsetX = vw-W-20; }
        if (H <= vh) offsetY = (vh-H)/2f; else { if (offsetY > 20) offsetY = 20; if (offsetY+H < vh-20) offsetY = vh-H-20; }
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
                float W = s * 1.5f * mapData.width + s;
                float H = s * (float) Math.sqrt(3) * (mapData.height + 0.5f);
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
        int vx0 = Math.max(0, (int) ((offsetX - vs * 2f) / (1.5f * vs)) - 1);
        int vx1 = Math.min(mapData.width - 1,
                (int) ((getWidth() - offsetX + vs * 2f) / (1.5f * vs)) + 1);
        int vy0 = Math.max(0, (int) ((offsetY - vs * 2f) / (vs * (float) Math.sqrt(3))) - 2);
        int vy1 = Math.min(mapData.height - 1,
                (int) ((getHeight() - offsetY + vs * 2f) / (vs * (float) Math.sqrt(3))) + 2);
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

                // 1. 底色：默认直接用地块归属色实心填充（法国地块就是蓝），不再叠半透明滤镜
                buildHexPath(px, py);
                int terrLeg = (ownershipTint && provinceOwnerLegion != null
                        && cellIdx < provinceOwnerLegion.length)
                        ? provinceOwnerLegion[cellIdx] : 0xFF;
                boolean solidCountry = !provinceView && ownershipTint
                        && terrLeg != 0xFF && terrLeg >= 0
                        && terrLeg < mapData.legionColors.length;
                if (provinceView) {
                    // 省规划视图：每个省按省规划值生成不同颜色，便于区分省份
                    int pv = (mapData.provinces != null && cellIdx < mapData.provinces.length)
                            ? mapData.provinces[cellIdx] : 0;
                    tilePaint.setColor(provinceColor(pv));
                    canvas.drawPath(sharedPath, tilePaint);
                } else if (solidCountry) {
                    tilePaint.setColor(mapData.legionColors[terrLeg]);
                    canvas.drawPath(sharedPath, tilePaint);
                } else {
                    tilePaint.setColor(baseColor);
                    canvas.drawPath(sharedPath, tilePaint);
                }

                // 2. clip + 贴图（实心国家色/采样色格子不画贴图）
                if (provinceView || solidCountry || useSampled) {
                    // 不画贴图
                } else {
                    canvas.save();
                    canvas.clipPath(sharedPath);
                    float hh = hs();
                    float hx1 = px - hh, hy1 = py - hh, hx2 = px + hh, hy2 = py + hh;
                    Rect dst = new Rect((int) hx1, (int) hy1, (int) hx2, (int) hy2);
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

                // 3. 网格（缩得太小时网格不可见，跳过以省大量 drawPath）
                if (s >= 3f) {
                    gridPaint.setStrokeWidth(Math.max(0.5f, scale*0.8f));
                    canvas.drawPath(sharedPath, gridPaint);
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
                if (bid > 0 && buildingBmps != null) {
                    Bitmap bb = buildingBmps.get(bid);
                    if (bb != null) {
                        canvas.save();
                        canvas.clipPath(sharedPath);
                        float sh = hs();
                        canvas.drawBitmap(bb, null,
                            new Rect((int)(px-sh), (int)(py-sh), (int)(px+sh), (int)(py+sh)),
                            tilePaint);
                        canvas.restore();
                    }
                }
            }
        }

        // 兵种标记（覆盖在建筑之上）：军团色圆标 + 等级 + 名称
        if (mapData.armies != null) {
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
                // 图标按兵种代码对应 legion_icon_N.png；兵种39=城市，用城市建筑图标
                Bitmap legionIcon = null;
                if (a.type == 39) {
                    if (buildingBmps != null) {
                        legionIcon = buildingBmps.get(13);
                        if (legionIcon == null) legionIcon = buildingBmps.get(11);
                    }
                } else if (legionBmps != null) {
                    legionIcon = legionBmps.get(a.type);
                }
                float r = Math.max(4f, s * 0.38f);
                float iconSize = Math.max(10f, s * 1.3f);
                if (legionIcon != null) {
                    canvas.drawBitmap(legionIcon, null,
                            new RectF(px - iconSize / 2f, py - iconSize / 2f,
                                    px + iconSize / 2f, py + iconSize / 2f), bitmapPaint);
                } else {
                    // 无军团图标时回退为军团色圆
                    Paint unitPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                    unitPaint.setColor(color);
                    canvas.drawCircle(px, py, r, unitPaint);
                    unitPaint.setStyle(Paint.Style.STROKE);
                    unitPaint.setStrokeWidth(Math.max(1f, s * 0.08f));
                    unitPaint.setColor(0xFFFFFFFF);
                    canvas.drawCircle(px, py, r, unitPaint);
                }
                // 国旗（部队国籍）：军团归属 -> 国家ID -> flag_N.png，画在图标上方
                if (legion != 0xFF && legion >= 0 && mapData.legionCountries != null
                        && legion < mapData.legionCountries.length && flagBmps != null) {
                    Bitmap flag = flagBmps.get(mapData.legionCountries[legion]);
                    if (flag != null) {
                        float fw = iconSize * 0.95f;
                        float fh = fw * flag.getHeight() / (float) flag.getWidth();
                        canvas.drawBitmap(flag, null,
                                new RectF(px - fw / 2f, py - iconSize / 2f - fh,
                                        px + fw / 2f, py - iconSize / 2f), bitmapPaint);
                    }
                }
                // 等级角标（图标右下角）
                if (s >= 6) {
                    float br = Math.max(5f, s * 0.24f);
                    float badgeX = px + iconSize / 2f - br * 0.35f;
                    float badgeY = py + iconSize / 2f - br * 0.35f;
                    Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                    bgPaint.setColor(0xD9000000);
                    canvas.drawCircle(badgeX, badgeY, br, bgPaint);
                    Paint numPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                    numPaint.setColor(0xFFFFFFFF);
                    numPaint.setTextSize(br * 1.15f);
                    numPaint.setTextAlign(Paint.Align.CENTER);
                    numPaint.setFakeBoldText(true);
                    canvas.drawText(String.valueOf(a.level), badgeX,
                            badgeY + numPaint.getTextSize() * 0.36f, numPaint);
                }
            }
        }

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
            canvas.drawBitmap(guideImage, null,
                new RectF(minX, minY, maxX, maxY), guidePaint);
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
    private boolean dragging = false, scaling = false;
    @Override public boolean onTouchEvent(MotionEvent e) {
        gestureDetector.onTouchEvent(e);
        switch (e.getAction() & MotionEvent.ACTION_MASK) {
            case MotionEvent.ACTION_DOWN:
                lastX=e.getX();lastY=e.getY();downX=e.getX();downY=e.getY();
                dragging=false;scaling=false;
                if (mapData != null && !mapData.brushMode) {
                    PointF h = p2h(e.getX(),e.getY());
                    if (h != null) { int x=(int)h.x,y=(int)h.y; if (x>=0&&x<mapData.width&&y>=0&&y<mapData.height) selectCell(x,y); }
                }
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                scaling=true; lastDist=sp(e);
                // 记录双指中心作为缩放锚点
                if (e.getPointerCount() >= 2) {
                    float cx = (e.getX(0) + e.getX(1)) / 2f;
                    float cy = (e.getY(0) + e.getY(1)) / 2f;
                    pivotX = cx; pivotY = cy;
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                if (mapData == null) break;
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
                // 双指缩放（以两指中心为锚点）
                if (scaling && e.getPointerCount() >= 2) {
                    float nd = sp(e);
                    if (lastDist > 0) {
                        float f = nd / lastDist;
                        float os = scale;
                        scale *= f;
                        if (scale < 0.3f) scale = 0.3f;
                        if (scale > 3f) scale = 3f;
                        f = scale / os;
                        offsetX = pivotX - (pivotX - offsetX) * f;
                        offsetY = pivotY - (pivotY - offsetY) * f;
                        clamp(); invalidate();
                    }
                    lastDist = nd;
                    break;
                }
                // 单指拖动（降低触发阈值到1px，拖动更跟手）
                if (!scaling) {
                    float dx = e.getX() - lastX;
                    float dy = e.getY() - lastY;
                    if (Math.abs(dx) > 1 || Math.abs(dy) > 1) {
                        dragging = true;
                        offsetX += dx;
                        offsetY += dy;
                        lastX = e.getX();
                        lastY = e.getY();
                        clamp(); invalidate();
                    }
                }
                break;
            case MotionEvent.ACTION_POINTER_UP:
                if (e.getPointerCount() <= 2) scaling = false;
                break;
            case MotionEvent.ACTION_UP:
                if (!dragging && !scaling && mapData != null && mapData.brushMode) tap(e.getX(),e.getY());
                dragging=false; scaling=false;
                break;
        }
        return true;
    }

    // 选中一个格子（拆出独立方法，ACTION_DOWN 也能调用）
    private void selectCell(int x, int y) {
        if (mapData == null || viewOnly) return;
        int idx = y * mapData.width + x;
        if (mapData.brushMode) { applyBrush(x, y); return; }
        if (mapData.multiSelectMode) {
            mapData.toggleBlockSelection(idx);
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
        if (mapData == null || viewOnly || mapData.selectedTerrainGroup < 0) return;
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
        for (int idx : cellsToPaint) {
            if (idx < 0 || idx >= mapData.tiles.size()) continue;
            TerrainTile tt = mapData.tiles.get(idx);
            if (tt == null) continue;
            byte[] pat = mapData.getTerrainPattern(g);
            if (pat != null) tt.parseFromBytes(pat, 0);
            else tt.setTerrain(g);
            mapData.editedCells.add(idx);
        }
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

    private void tap(float px, float py) {
        if (mapData == null || viewOnly) return; PointF h = p2h(px,py); if (h == null) return;
        int x = (int)h.x, y = (int)h.y;
        if (x>=0 && x<mapData.width && y>=0 && y<mapData.height) {
            int idx = y * mapData.width + x;
            // 画笔模式：直接涂抹
            if (mapData.brushMode) {
                applyBrush(x, y);
                return;
            }
            // 多选模式：切换选中/取消
            if (mapData.multiSelectMode) {
                mapData.toggleBlockSelection(idx);
                selectedX = x; selectedY = y;
                invalidate();
                if (listener != null) listener.onTileSelected(x,y,mapData.getTile(x,y));
                return;
            }
            // 笔刷模式：如果右侧选了地形(G>=0)，点击直接应用
            if (mapData.selectedTerrainGroup >= 0) {
                applyBrush(x, y);
                return;
            }
            // 普通选择模式
            selectedX=x;selectedY=y;
            invalidate();
            if (listener != null) listener.onTileSelected(x,y,mapData.getTile(x,y));
        }
    }
    private class GestureListener extends GestureDetector.SimpleOnGestureListener {
        @Override public boolean onSingleTapUp(MotionEvent e) { return true; }
        @Override public void onLongPress(MotionEvent e) {
            if (mapData == null || viewOnly) return; PointF h = p2h(e.getX(),e.getY()); if (h == null) return;
            int x = (int)h.x, y = (int)h.y; if (x>=0&&x<mapData.width&&y>=0&&y<mapData.height) { mapData.setBuildingId(x,y,0); if (selectedX==x&&selectedY==y&&listener!=null) listener.onTileSelected(x,y,mapData.getTile(x,y)); invalidate(); }
        }
    }
    public void zoomIn() { float os=scale; scale*=1.3f; if(scale>3f)scale=3f; float f=scale/os, cx=getWidth()/2f, cy=getHeight()/2f; offsetX=cx-(cx-offsetX)*f; offsetY=cy-(cy-offsetY)*f; clamp(); invalidate(); }
    public void zoomOut() { float os=scale; scale/=1.3f; if(scale<0.3f)scale=0.3f; float f=scale/os, cx=getWidth()/2f, cy=getHeight()/2f; offsetX=cx-(cx-offsetX)*f; offsetY=cy-(cy-offsetY)*f; clamp(); invalidate(); }
    public void resetView() { centerMap(); invalidate(); }
}
