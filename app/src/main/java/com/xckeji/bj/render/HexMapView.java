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
import com.xckeji.bj.model.TerrainTile;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

public class HexMapView extends View {
    private MapData mapData;
    private float offsetX = 20, offsetY = 20;
    private float scale = 1.0f;
    private int selectedX = -1, selectedY = -1;
    private OnTileSelectListener listener;
    private GestureDetector gestureDetector;
    private Paint tilePaint, gridPaint, selectedPaint;

    // 图片——懒加载，首次绘制时初始化
    private Bitmap landBmp;
    private Bitmap seaBmp;
    private Map<String, Bitmap> terrainBmps;
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
        gestureDetector = new GestureDetector(context, new GestureListener());
    }

    private void ensureImages() {
        if (imagesLoaded) return;
        imagesLoaded = true;
        try {
            terrainBmps = new HashMap<>();
            buildingBmps = new HashMap<>();
            landBmp = load("map/land.png");
            seaBmp = load("map/sea.png");
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

    public void setMapData(MapData data) { mapData = data; selectedX = -1; selectedY = -1; scale = 1f; post(() -> { centerMap(); invalidate(); }); }
    public MapData getMapData() { return mapData; }
    public interface OnTileSelectListener { void onTileSelected(int x, int y, TerrainTile tile); }
    public void setOnTileSelectListener(OnTileSelectListener l) { listener = l; }
    public int getSelectedX() { return selectedX; }
    public int getSelectedY() { return selectedY; }
    public void refresh() { invalidate(); }

    /** 设置底图（自适应铺满地图区域） */
    /** 设置底图并自动采样每个六边形中心颜色 */
    private boolean overlayVisible = true;
    public boolean isOverlayVisible() { return overlayVisible; }
    public void setOverlayVisible(boolean v) {
        overlayVisible = v;
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

        for (int y = 0; y < mapData.height; y++) {
            for (int x = 0; x < mapData.width; x++) {
                TerrainTile tile = mapData.getTile(x, y);
                if (tile == null) continue;

                float px = hcx(x), py = hcy(x, y), s = hs();
                if (px + s < 0 || px - s > getWidth() || py + s < 0 || py - s > getHeight()) continue;

                int gid = tile.bmTerrain1Group;
                int tid = tile.bmTerrain1Id;
                if (tid == 0) tid = 1;

                Path path = hp(px, py);

                // 1. 底色
                int cellIdx = y * mapData.width + x;
                boolean useSampled = overlayVisible && mapData.sampledColors != null
                    && cellIdx < mapData.sampledColors.size()
                    && mapData.sampledColors.get(cellIdx) != 0
                    && !mapData.editedCells.contains(cellIdx);
                int baseColor;
                if (useSampled) {
                    baseColor = mapData.sampledColors.get(cellIdx);
                } else {
                    baseColor = tile.getTerrainColor();
                }
                tilePaint.setColor(baseColor);
                canvas.drawPath(path, tilePaint);

                // 2. clip + 贴图（采样色格子不画贴图，只显示纯色）
                if (useSampled) {
                    // 不画贴图
                } else {
                    tilePaint.setAlpha(255);
                    canvas.save();
                    canvas.clipPath(path);
                    float hh = hs();
                    float hx1 = px - hh, hy1 = py - hh, hx2 = px + hh, hy2 = py + hh;
                    if (gid == 0 && landBmp != null) {
                        canvas.drawBitmap(landBmp, null, new Rect((int)hx1,(int)hy1,(int)hx2,(int)hy2), tilePaint);
                    } else if (gid == 1 && seaBmp != null) {
                        canvas.drawBitmap(seaBmp, null, new Rect((int)hx1,(int)hy1,(int)hx2,(int)hy2), tilePaint);
                    } else {
                        if (landBmp != null) {
                            canvas.drawBitmap(landBmp, null, new Rect((int)hx1,(int)hy1,(int)hx2,(int)hy2), tilePaint);
                        }
                        String base = G2B.get(gid);
                        if (base != null && terrainBmps != null) {
                            String key = gid + "_" + tid;
                            Bitmap bmp = terrainBmps.get(key);
                            if (bmp == null && tid > 1) bmp = terrainBmps.get(gid + "_1");
                            if (bmp != null) {
                                canvas.drawBitmap(bmp, null, new Rect((int)hx1,(int)hy1,(int)hx2,(int)hy2), tilePaint);
                            }
                        }
                    }
                    canvas.restore();
                }

                // 3. 网格
                gridPaint.setStrokeWidth(Math.max(0.5f, scale*0.8f));
                canvas.drawPath(path, gridPaint);

                // 4a. 多选高亮
                if (mapData != null && mapData.multiSelectMode) {
                    int idx = y * mapData.width + x;
                    if (mapData.selectedBlocks.contains(idx)) {
                        Paint multiPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                        multiPaint.setStyle(Paint.Style.STROKE);
                        multiPaint.setStrokeWidth(3f);
                        multiPaint.setColor(0xFF10b981);
                        canvas.drawPath(path, multiPaint);
                    }
                }
                // 4b. 当前选中（自定义边框图片）
                if (selectedX == x && selectedY == y) {
                    if (borderSelectedBmp != null) {
                        canvas.save();
                        canvas.clipPath(path);
                        RectF bounds = new RectF();
                        path.computeBounds(bounds, true);
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
                            tilePaint);
                        canvas.restore();
                    } else {
                        canvas.drawPath(path, selectedPaint);
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
                        canvas.clipPath(path);
                        float sh = hs();
                        canvas.drawBitmap(bb, null,
                            new Rect((int)(px-sh), (int)(py-sh), (int)(px+sh), (int)(py+sh)),
                            tilePaint);
                        canvas.restore();
                    }
                }
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
                if (mapData.brushMode && e.getPointerCount() == 1) {
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
        if (mapData == null) return;
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
        if (mapData == null || mapData.selectedTerrainGroup < 0) return;
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
            tt.bmTerrain1Group = g;
            tt.bmTerrain1Id = (g == 0) ? 255 : 0;
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
        if (mapData == null) return; PointF h = p2h(px,py); if (h == null) return;
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
            if (mapData == null) return; PointF h = p2h(e.getX(),e.getY()); if (h == null) return;
            int x = (int)h.x, y = (int)h.y; if (x>=0&&x<mapData.width&&y>=0&&y<mapData.height) { mapData.setBuildingId(x,y,0); if (selectedX==x&&selectedY==y&&listener!=null) listener.onTileSelected(x,y,mapData.getTile(x,y)); invalidate(); }
        }
    }
    public void zoomIn() { float os=scale; scale*=1.3f; if(scale>3f)scale=3f; float f=scale/os, cx=getWidth()/2f, cy=getHeight()/2f; offsetX=cx-(cx-offsetX)*f; offsetY=cy-(cy-offsetY)*f; clamp(); invalidate(); }
    public void zoomOut() { float os=scale; scale/=1.3f; if(scale<0.3f)scale=0.3f; float f=scale/os, cx=getWidth()/2f, cy=getHeight()/2f; offsetX=cx-(cx-offsetX)*f; offsetY=cy-(cy-offsetY)*f; clamp(); invalidate(); }
    public void resetView() { centerMap(); invalidate(); }
}
