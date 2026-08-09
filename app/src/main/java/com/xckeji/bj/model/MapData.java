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
    /** 官方 MapEdit 的 getBorderId 查表（64 项）：index = t1*32+t2*16+t3*8+t4*4+t5*2+t6，
     *  t1..t6 = 六个邻居是否同为海面。海面波浪装饰 id = 表值 + 10。 */
    private static final int[] OFFICIAL_COAST_BORDER = {
        1,5,9,13,17,21,25,29,   // 000xxx
        3,7,11,15,19,23,27,31,  // 001xxx
        2,6,10,14,18,22,26,30,  // 010xxx
        4,8,12,16,20,24,28,32,  // 011xxx
        33,37,41,45,49,53,57,61,// 100xxx
        35,39,43,47,51,55,59,63,// 101xxx
        34,38,42,46,50,54,58,62,// 110xxx
        36,40,44,48,52,56,60,0  // 111xxx
    };
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
    /** 官方模式标记：征服扩展后保存时仅输出世界底图 world.bin（与官方“地图编辑器”一致）。 */
    public boolean conquestExtended = false;

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
        finishPaint(selectedBlocks);
    }

    /**
     * 涂地后处理：被涂格子 + 其六边形邻居。
     * - 陆地/山地等：按地块坐标从本图常见变体中选一个完整模板，避免整块区域
     *   都是同一贴图、看起来像一块矩形纯色；
     * - 海面：被涂的格子用标准波浪海面；靠岸的格子（被涂或邻居）按“陆地邻居方向
     *   掩码”补方向波浪（只改第 4-7 字节，海底与第二装饰保持原样），并按位置轮流
     *   选该方向的真实变体，避免整段海岸同一贴图。
     */
    public void finishPaint(java.util.Set<Integer> paintedCells) {
        if (paintedCells == null || paintedCells.isEmpty() || tiles == null) return;
        if (width <= 0 || height <= 0) return;
        java.util.Set<Integer> affected = new java.util.HashSet<>(paintedCells);
        for (int idx : paintedCells) {
            if (idx < 0 || idx >= tiles.size()) continue;
            for (int d = 1; d <= 6; d++) {
                int nb = officialNeighbor(idx, d);
                if (nb >= 0) affected.add(nb);
            }
        }
        for (int idx : affected) {
            if (idx < 0 || idx >= tiles.size()) continue;
            TerrainTile t = tiles.get(idx);
            boolean painted = paintedCells.contains(idx);
            if (t.bmTerrain1Group == 1) {
                // 海面：被涂的格子先铺标准海面，然后按官方海岸线算法写波浪装饰
                if (painted) {
                    byte[] pat = terrainPatterns.get(1);
                    if (pat != null) t.parseFromBytes(pat, 0);
                    else t.setTerrain(1);
                }
                // 官方海岸线：波浪装饰组 31，id = 邻居海陆掩码查表 + 10；第二装饰清 63/255
                t.decoration1Group = 31;
                t.decoration1Id = officialCoastWaveId(idx);
                t.decoration1X = 0;
                t.decoration1Y = 0;
                t.decoration2Group = 63;
                t.decoration2Id = 255;
                t.decoration2X = 0;
                t.decoration2Y = 0;
                editedCells.add(idx);
            } else {
                // 陆地：被涂的格子按位置选真实变体（避免整块矩形纯色）
                if (painted) {
                    byte[] pat = null;
                    java.util.List<byte[]> list = terrainPatternList.get(t.bmTerrain1Group);
                    if (list != null && !list.isEmpty()) {
                        int x = idx % width, y = idx / width;
                        pat = list.get(Math.abs(x * 7 + y * 13) % list.size());
                    }
                    if (pat == null) pat = terrainPatterns.get(t.bmTerrain1Group);
                    if (pat != null) t.parseFromBytes(pat, 0);
                    else t.setTerrain(t.bmTerrain1Group);
                    editedCells.add(idx);
                }
                // 官方 dealBorder：陆地不能带波浪装饰（组31且id>=10 → 63/255）
                if (t.decoration1Group == 31 && t.decoration1Id >= 10) {
                    t.decoration1Group = 63;
                    t.decoration1Id = 255;
                    t.decoration1X = 0;
                    t.decoration1Y = 0;
                }
                if (t.decoration2Group == 31 && t.decoration2Id >= 10) {
                    t.decoration2Group = 63;
                    t.decoration2Id = 255;
                    t.decoration2X = 0;
                    t.decoration2Y = 0;
                }
            }
        }
    }

    /** 官方 MapEdit 的相邻方向 direct=1..6（偶/奇列偏移不同），越界或索引 0 返回 -1。 */
    private int officialNeighbor(int id, int direct) {
        int w = width;
        int x = id % w;
        int t;
        if ((x & 1) == 1) {
            switch (direct) {
                case 1: t = id - 1; break;
                case 2: t = id - w; break;
                case 3: t = id + 1; break;
                case 4: t = id + w - 1; break;
                case 5: t = id + w; break;
                default: t = id + w + 1; break;
            }
        } else {
            switch (direct) {
                case 1: t = id - w - 1; break;
                case 2: t = id - w; break;
                case 3: t = id - w + 1; break;
                case 4: t = id - 1; break;
                case 5: t = id + w; break;
                default: t = id + w + 1; break;
            }
        }
        return (t > 0 && t < tiles.size()) ? t : -1;
    }

    /** 官方海岸线：返回海面格子的波浪装饰 id（组 31，id = 邻居掩码查表 + 10）。 */
    private int officialCoastWaveId(int id) {
        int bits = 0;
        for (int d = 1; d <= 6; d++) {
            int nb = officialNeighbor(id, d);
            if (nb >= 0 && tiles.get(nb).bmTerrain1Group == 1) bits |= (1 << (6 - d));
        }
        return OFFICIAL_COAST_BORDER[bits] + 10;
    }

    /** 官方 checkCoast：全图重算海岸线。海面按官方掩码查表补波浪；陆地清除波浪装饰。 */
    public void recomputeCoastAll() {
        if (tiles == null) return;
        for (int i = 0; i < tiles.size(); i++) {
            TerrainTile t = tiles.get(i);
            if (t.bmTerrain1Group == 1) {
                t.decoration1Group = 31;
                t.decoration1Id = officialCoastWaveId(i);
                t.decoration1X = 0;
                t.decoration1Y = 0;
                t.decoration2Group = 63;
                t.decoration2Id = 255;
                t.decoration2X = 0;
                t.decoration2Y = 0;
                editedCells.add(i);
            } else {
                if (t.decoration1Group == 31 && t.decoration1Id >= 10) {
                    t.decoration1Group = 63;
                    t.decoration1Id = 255;
                    t.decoration1X = 0;
                    t.decoration1Y = 0;
                }
                if (t.decoration2Group == 31 && t.decoration2Id >= 10) {
                    t.decoration2Group = 63;
                    t.decoration2Id = 255;
                    t.decoration2X = 0;
                    t.decoration2Y = 0;
                }
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
