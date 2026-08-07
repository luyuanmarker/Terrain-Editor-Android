package com.xckeji.bj.model;

import java.util.ArrayList;
import java.util.List;

public class MapData {
    public int width, height;
    public List<TerrainTile> tiles;
    public List<Integer> buildingIds;
    public byte[] btlOriginalData;
    // 征服地图：地形来自世界 BIN（btlOriginalData 内不包含地形）
    public byte[] binOriginalData;
    public String binFileName;
    /** 每种地形组在本图中最常见的完整 16 字节模式（游戏按完整模式找贴图，只改组/ID 会导致显示异常）。 */
    public java.util.Map<Integer, byte[]> terrainPatterns = new java.util.HashMap<>();
    /** 每种地形组在本图中出现过的常见完整 16 字节模式列表（随机地形从中挑选合法变体）。 */
    public java.util.Map<Integer, java.util.List<byte[]>> terrainPatternList = new java.util.HashMap<>();
    /** 地图上的兵种（从 BTL 兵种段 48 字节/条解析）。 */
    public java.util.List<Army> armies = new java.util.ArrayList<>();
    /** 军团地块颜色（军团段 0x28 处 RGBA），按军团顺序。 */
    public int[] legionColors = new int[0];
    /** 军团所属国家 ID（军团段 0x4），按军团顺序。 */
    public int[] legionCountries = new int[0];
    /** 军团归属（1字节/格：0xFF=中立，否则为军团索引）。 */
    public byte[] belongs = new byte[0];
    /** 军团 300 字节记录列表。 */
    public java.util.List<Legion> legions = new java.util.ArrayList<>();
    /** 省规划（2字节/格）。 */
    public int[] provinces = new int[0];
    /**
     * 存储坐标基准偏移：普通 BTL=0；官方整合版征服文件把建筑/兵种坐标存成
     * “世界坐标”（地图本地坐标 + 截取偏移），解析时减、写回时加这个值。
     */
    public int coordBase = 0;
    /** 城市/建筑记录列表（建筑段 32 字节/条，按文件顺序）。 */
    public java.util.List<Building> buildings = new java.util.ArrayList<>();

    /** 建筑记录（32 字节：0x0 坐标、0x2 名称、0x4 类型、0x5 外观等）。 */
    public static class Building {
        public int index;   // 在建筑段中的序号（用于写回）
        public int x, y;    // 当前坐标
        public int coord;   // 0x0 地块坐标（tile index）
        public int type;    // 0x4 建筑类型（对应 building_N.png）
        public byte[] raw = new byte[32];
    }

    /** BTL 兵种记录（48 字节/条：0x0 坐标、0x2 兵种、0x3 等级）。 */
    public static class Army {
        public int x, y;
        public int type;   // 兵种代码（ArmySettings 的 Army 字段）
        public int level;  // 等级
        public String name;
        public int index;          // 在兵种段中的序号（用于写回）
        public byte[] raw;         // 原始记录（版本1=48字节，版本2/3=64字节）

        public Army(int x, int y, int type, int level) {
            this.x = x;
            this.y = y;
            this.type = type;
            this.level = level;
        }
    }

    /** 军团记录（300 字节：0x0 序号、0x4 国家、0x14 控制、0x18 阵营、0x28 地块颜色等）。 */
    public static class Legion {
        public byte[] raw = new byte[300];
        public int seq;
        public int country;
        public int faction;
        public int control;
        public int color;
    }
    // 多选相关
    public java.util.Set<Integer> selectedBlocks = new java.util.HashSet<>();
    public boolean multiSelectMode = false;
    public int selectedTerrainGroup = -1;
    public int selectedBuildingId = -1;
    /** 锁定的兵种代码（-1=未锁定）：点击地图地块连续放置该兵种。 */
    public int selectedArmyType = -1;
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
        byte[] pat = getTerrainPattern(group);
        for (int idx : selectedBlocks) {
            if (idx >= 0 && idx < tiles.size()) {
                if (pat != null) tiles.get(idx).parseFromBytes(pat, 0);
                else tiles.get(idx).setTerrain(group);
                editedCells.add(idx);
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

    public byte[] getTerrainPattern(int group) {
        return terrainPatterns.get(group);
    }

    public java.util.List<byte[]> getTerrainPatterns(int group) {
        return terrainPatternList.get(group);
    }

    /** 统计本图每种地形组最常见的完整 16 字节模式，作为涂色的标准贴图模板。 */
    public void buildTerrainPatterns() {
        terrainPatterns.clear();
        terrainPatternList.clear();
        java.util.HashMap<Integer, java.util.HashMap<String, Integer>> counts = new java.util.HashMap<>();
        for (TerrainTile t : tiles) {
            byte[] p = new byte[16];
            t.toBytes(p, 0);
            String key = java.util.Arrays.toString(p);
            java.util.HashMap<String, Integer> g = counts.computeIfAbsent(
                    t.bmTerrain1Group, k -> new java.util.HashMap<>());
            g.put(key, g.getOrDefault(key, 0) + 1);
        }
        for (java.util.Map.Entry<Integer, java.util.HashMap<String, Integer>> e : counts.entrySet()) {
            // 按出现次数降序排列
            java.util.List<java.util.Map.Entry<String, Integer>> sorted = new java.util.ArrayList<>(e.getValue().entrySet());
            sorted.sort((a, b) -> b.getValue() - a.getValue());
            java.util.List<byte[]> pats = new java.util.ArrayList<>();
            for (int k = 0; k < sorted.size() && k < 8; k++) {
                String[] parts = sorted.get(k).getKey().substring(1, sorted.get(k).getKey().length() - 1).split(", ");
                byte[] pat = new byte[16];
                for (int i = 0; i < 16 && i < parts.length; i++) {
                    pat[i] = Byte.parseByte(parts[i].trim());
                }
                pats.add(pat);
            }
            if (!pats.isEmpty()) {
                terrainPatternList.put(e.getKey(), pats);
                terrainPatterns.put(e.getKey(), pats.get(0));
            }
        }
    }
}
