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
     * @param buildingIds 建筑列表（有建筑的格子跳过）
     * @param includeSea 是否连海洋一起随机（默认 false 跳过海洋）
     * @param terrainPatterns 每组常见的完整 16 字节模式（组 → 模式列表），
     *                        优先从中挑选合法贴图；没有则用干净的标准地形兜底
     */
    public static void randomizeTerrain(java.util.List<TerrainTile> originalTiles,
                                         double probability,
                                         java.util.List<Integer> allowedTerrainIds,
                                         int seed,
                                         java.util.List<Integer> buildingIds,
                                         boolean includeSea,
                                         java.util.Map<Integer, java.util.List<byte[]>> terrainPatterns) {
        if (originalTiles == null || originalTiles.isEmpty()) {
            throw new RuntimeException("地图数据为空");
        }
        if (allowedTerrainIds == null || allowedTerrainIds.isEmpty()) {
            throw new RuntimeException("至少选择一种地形");
        }
        java.util.Random rng = new java.util.Random(seed);

        for (int i = 0; i < originalTiles.size(); i++) {
            TerrainTile tile = originalTiles.get(i);
            // 默认跳过海洋（可选连海洋一起随机）
            if (!includeSea && tile.bmTerrain1Group == 1) continue;
            // 跳过有建筑的地块
            if (buildingIds != null && i < buildingIds.size() && buildingIds.get(i) != 0) continue;

            if (rng.nextDouble() < probability) {
                int newGroup = allowedTerrainIds.get(rng.nextInt(allowedTerrainIds.size()));
                // 优先套用该地图里该地形组的真实模式（整格替换，保证游戏能识别）
                java.util.List<byte[]> pats = terrainPatterns == null ? null : terrainPatterns.get(newGroup);
                if (pats != null && !pats.isEmpty()) {
                    tile.parseFromBytes(pats.get(rng.nextInt(pats.size())), 0);
                } else {
                    // 兜底：干净的标准地形（组 + 标准ID + 覆盖层3F FF）
                    tile.setTerrain(newGroup);
                }
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
