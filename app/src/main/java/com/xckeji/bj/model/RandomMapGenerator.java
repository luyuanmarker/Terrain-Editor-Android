package com.xckeji.bj.model;

import java.util.ArrayList;
import java.util.List;

/** 随机地图生成器 — 移植自 Flutter 版的 random_map_generator.dart */
public class RandomMapGenerator {
    public static class Config {
        public int id;
        public String name;
        public int color;
        public boolean enabled;
        public double density;

        public Config(int id, String name, int color, boolean enabled, double density) {
            this.id = id; this.name = name; this.color = color;
            this.enabled = enabled; this.density = density;
        }
    }

    /** 生成地形图。返回 terrain group ID 列表（按 row-major 顺序）。 */
    public static List<Integer> generate(int width, int height, int seed,
                                          List<Config> configs,
                                          boolean addOceanBorder, boolean smoothTerrain) {
        PerlinNoise perlin = new PerlinNoise(seed);

        // 统计启用的地形 + 总密度
        List<Config> enabled = new ArrayList<>();
        for (Config c : configs) if (c.enabled) enabled.add(c);
        if (enabled.isEmpty()) throw new RuntimeException("至少启用一种地形");

        double totalDensity = 0;
        for (Config c : enabled) totalDensity += c.density;

        double scale = 0.1;
        List<Integer> map = new ArrayList<>(width * height);

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (addOceanBorder &&
                    (x == 0 || x == width - 1 || y == 0 || y == height - 1)) {
                    map.add(1); // ocean
                    continue;
                }
                double noise = perlin.fractalNoise2D(x * scale, y * scale, 4, 0.5, 2.0);
                noise = Math.max(0, Math.min(1, (noise + 1) / 2));

                int terrainId = enabled.get(0).id;
                double cumulative = 0;
                for (Config t : enabled) {
                    cumulative += t.density / totalDensity;
                    if (noise <= cumulative) { terrainId = t.id; break; }
                }
                map.add(terrainId);
            }
        }

        if (smoothTerrain) smooth(map, width, height);
        return map;
    }

    /**
     * 在现有地图上随机化地形（不改尺寸、不重建）。
     * @param originalTiles 原始地块列表
     * @param probability 0~1 的概率（0=不变，1=全改）
     * @param allowedTerrainIds 允许出现的地形 ID 列表（不能为空）
     * @param seed 随机种子
     */
    public static void randomizeTerrain(java.util.List<TerrainTile> originalTiles,
                                         double probability,
                                         java.util.List<Integer> allowedTerrainIds,
                                         int seed,
                                         java.util.List<Integer> buildingIds) {
        if (originalTiles == null || originalTiles.isEmpty()) {
            throw new RuntimeException("地图数据为空");
        }
        if (allowedTerrainIds == null || allowedTerrainIds.isEmpty()) {
            throw new RuntimeException("至少选择一种地形");
        }
        java.util.Random rng = new java.util.Random(seed);
        // 地形变体范围（variant 范围）
        java.util.Map<Integer, int[]> variantRanges = new java.util.HashMap<>();
        variantRanges.put(2, new int[]{1, 9});
        variantRanges.put(3, new int[]{1, 11});
        variantRanges.put(4, new int[]{1, 11});
        variantRanges.put(5, new int[]{1, 5});
        variantRanges.put(6, new int[]{1, 11});
        variantRanges.put(7, new int[]{1, 11});
        variantRanges.put(8, new int[]{1, 5});
        variantRanges.put(9, new int[]{1, 11});
        variantRanges.put(10, new int[]{1, 11});
        variantRanges.put(11, new int[]{1, 5});
        variantRanges.put(12, new int[]{1, 11});
        variantRanges.put(13, new int[]{1, 11});
        variantRanges.put(14, new int[]{1, 5});
        variantRanges.put(15, new int[]{1, 9});
        variantRanges.put(16, new int[]{1, 9});
        variantRanges.put(18, new int[]{1, 9});
        variantRanges.put(20, new int[]{1, 9});
        variantRanges.put(21, new int[]{1, 9});
        variantRanges.put(22, new int[]{1, 9});
        variantRanges.put(30, new int[]{1, 9});
        variantRanges.put(31, new int[]{1, 9});
        variantRanges.put(26, new int[]{1, 1});

        for (int i = 0; i < originalTiles.size(); i++) {
            TerrainTile tile = originalTiles.get(i);
            // 跳过海洋
            if (tile.bmTerrain1Group == 1) continue;
            // 跳过有建筑的地块
            if (buildingIds != null && i < buildingIds.size() && buildingIds.get(i) != 0) continue;

            if (rng.nextDouble() < probability) {
                int newGroup = allowedTerrainIds.get(rng.nextInt(allowedTerrainIds.size()));
                tile.bmTerrain1Group = newGroup;
                // 随机变体 ID：从 variantRanges 取范围，没有定义的组用 0
                int[] range = variantRanges.get(newGroup);
                if (range != null && range.length >= 2) {
                    tile.bmTerrain1Id = range[0] + rng.nextInt(range[1] - range[0] + 1);
                } else {
                    tile.bmTerrain1Id = 0;
                }
                // 重置 X/Y 偏移，避免旧值指向不存在的资源
                tile.bmTerrain1X = 0;
                tile.bmTerrain1Y = 0;
                // 注意：不修改装饰层(decoration)和地板层(floor)字段。
                // 游戏加载 BTL 时会读取这些字段，强制清零会导致游戏闪退。
                // 原装饰/地板数据保留，游戏会自动忽略不适合新地形的装饰。
            }
        }
    }

    private static void smooth(List<Integer> map, int w, int h) {
        int[] temp = new int[map.size()];
        for (int i = 0; i < map.size(); i++) temp[i] = map.get(i);

        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                int idx = y * w + x;
                int cur = map.get(idx);
                int[] nb = new int[8];
                nb[0] = map.get((y-1)*w + x);
                nb[1] = map.get((y+1)*w + x);
                nb[2] = map.get(y*w + (x-1));
                nb[3] = map.get(y*w + (x+1));
                nb[4] = map.get((y-1)*w + (x-1));
                nb[5] = map.get((y-1)*w + (x+1));
                nb[6] = map.get((y+1)*w + (x-1));
                nb[7] = map.get((y+1)*w + (x+1));

                int same = 0;
                for (int n : nb) if (n == cur) same++;
                if (same < 3) {
                    java.util.Map<Integer, Integer> counts = new java.util.HashMap<>();
                    for (int n : nb) counts.put(n, counts.getOrDefault(n, 0) + 1);
                    int best = cur, maxCount = 0;
                    for (java.util.Map.Entry<Integer, Integer> e : counts.entrySet()) {
                        if (e.getValue() > maxCount) { best = e.getKey(); maxCount = e.getValue(); }
                    }
                    temp[idx] = best;
                }
            }
        }
        for (int i = 0; i < map.size(); i++) map.set(i, temp[i]);
    }
}
