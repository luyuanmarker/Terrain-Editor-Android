package com.xckeji.bj.model;

/** 随机地形生成器（在现有地图上按概率与允许地形组随机重刷）。 */
public class RandomMapGenerator {
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

}
