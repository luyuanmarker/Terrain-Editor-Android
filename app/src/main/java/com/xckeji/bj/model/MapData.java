package com.xckeji.bj.model;

import java.util.ArrayList;
import java.util.List;

public class MapData {
    public int width, height;
    public List<TerrainTile> tiles;
    public List<Integer> buildingIds;
    public byte[] btlOriginalData;
    // 多选相关
    public java.util.Set<Integer> selectedBlocks = new java.util.HashSet<>();
    public boolean multiSelectMode = false;
    public int selectedTerrainGroup = -1;
    public int selectedBuildingId = -1;
    public boolean brushMode = false;
    public int brushRadius = 0;
    // 笔刷模式辅助
    public com.xckeji.bj.model.OperationHistory historyRef;
    public int lastEditX = -1;
    public int lastEditY = -1;


    // 底图
    public android.graphics.Bitmap overlayImage;
    public float overlayAlpha = 0.35f;
    // 从底图采样的每个格子的颜色值
    public java.util.List<Integer> sampledColors;
    // 标记哪些格子被手动编辑过
    public java.util.Set<Integer> editedCells = new java.util.HashSet<>();

    public String fileName;

    public MapData(int w, int h) {
        width = w; height = h;
        int n = w * h;
        tiles = new ArrayList<>(n);
        buildingIds = new ArrayList<>(n);
        sampledColors = new java.util.ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            tiles.add(new TerrainTile());
            buildingIds.add(0);
            sampledColors.add(0);
        }
    }

    public int getTotalTiles() { return width * height; }
    public TerrainTile getTile(int x, int y) {
        if (x < 0 || x >= width || y < 0 || y >= height) return null;
        return tiles.get(y * width + x);
    }
    public int getBuildingId(int x, int y) {
        if (x < 0 || x >= width || y < 0 || y >= height) return 0;
        return buildingIds.get(y * width + x);
    }
    public void setBuildingId(int x, int y, int id) {
        if (x < 0 || x >= width || y < 0 || y >= height) return;
        buildingIds.set(y * width + x, id);
    }
    public int getWaterCount() {
        int c = 0;
        for (TerrainTile t : tiles) if (t.bmTerrain1Group == 1) c++;
        return c;
    }
    
    public void clearSelectedBlocks() { selectedBlocks.clear(); }
    public boolean hasSelectedBlocks() { return !selectedBlocks.isEmpty(); }
    public void toggleBlockSelection(int index) {
        if (selectedBlocks.contains(index)) selectedBlocks.remove(index);
        else selectedBlocks.add(index);
    }
    public void applyTerrainToSelected(int group, int id) {
        for (int idx : selectedBlocks) {
            if (idx >= 0 && idx < tiles.size()) {
                tiles.get(idx).bmTerrain1Group = group;
                tiles.get(idx).bmTerrain1Id = id;
            }
        }
    }
    public void applyBuildingToSelected(int bid) {
        for (int idx : selectedBlocks) {
            if (idx >= 0 && idx < buildingIds.size()) {
                buildingIds.set(idx, bid);
            }
        }
    }
    public int getBuildingCount() {
        int c = 0;
        for (int id : buildingIds) if (id > 0) c++;
        return c;
    }
}
