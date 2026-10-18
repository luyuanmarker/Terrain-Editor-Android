package com.xckeji.bj.model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 全量地图快照：地形 16 字节记录 / 省区 / 归属 / 建筑 / 兵种 / 地雷 / 已编辑格，用于统一撤销重做。 */
public class MapSnapshot {
    final int width, height;
    final byte[] tileRecords;
    final int[] provinces;
    final byte[] belongs;
    final int[] buildingIds;
    final List<MapData.Army> armies = new ArrayList<>();
    final List<MapData.Building> buildings = new ArrayList<>();
    final List<MapData.Trap> traps = new ArrayList<>();
    final Set<Integer> editedCells = new HashSet<>();

    private MapSnapshot(MapData d) {
        width = d.width;
        height = d.height;
        int n = d.getTotalTiles();
        tileRecords = new byte[n * 16];
        buildingIds = new int[n];
        for (int i = 0; i < n; i++) {
            TerrainTile t = d.tiles.get(i);
            if (t != null) t.toBytes(tileRecords, i * 16);
            buildingIds[i] = d.buildingIds != null && i < d.buildingIds.size()
                    ? d.buildingIds.get(i) : 0;
        }
        provinces = d.provinces != null && d.provinces.length == n
                ? d.provinces.clone() : new int[n];
        belongs = d.belongs != null && d.belongs.length == n
                ? d.belongs.clone() : new byte[n];
        if (d.armies != null) {
            for (MapData.Army a : d.armies) {
                if (a == null) continue;
                MapData.Army c = new MapData.Army(a.x, a.y, a.type, a.level);
                c.index = a.index;
                c.name = a.name;
                c.general = a.general;
                c.raw = a.raw != null ? a.raw.clone() : null;
                armies.add(c);
            }
        }
        if (d.buildings != null) {
            for (MapData.Building b : d.buildings) {
                if (b == null) continue;
                MapData.Building c = new MapData.Building();
                c.index = b.index; c.coord = b.coord; c.x = b.x; c.y = b.y; c.type = b.type;
                c.raw = b.raw != null ? b.raw.clone() : new byte[32];
                buildings.add(c);
            }
        }
        if (d.traps != null) {
            for (MapData.Trap t : d.traps) {
                if (t == null) continue;
                MapData.Trap c = new MapData.Trap();
                c.index = t.index; c.coord = t.coord; c.x = t.x; c.y = t.y;
                c.legion = t.legion; c.level = t.level; c.hp = t.hp;
                c.raw = t.raw != null ? t.raw.clone() : new byte[12];
                traps.add(c);
            }
        }
        if (d.editedCells != null) editedCells.addAll(d.editedCells);
    }

    public static MapSnapshot of(MapData d) { return new MapSnapshot(d); }

    /** 恢复到地图；尺寸不一致时返回 false（例如截取/扩展后结构已变，不做撤销）。 */
    public boolean restore(MapData d) {
        if (d == null || d.width != width || d.height != height) return false;
        int n = d.getTotalTiles();
        for (int i = 0; i < n; i++) {
            TerrainTile t = d.tiles.get(i);
            if (t != null) t.parseFromBytes(tileRecords, i * 16);
        }
        for (int i = 0; i < n && i < d.buildingIds.size(); i++) {
            d.buildingIds.set(i, buildingIds[i]);
        }
        d.provinces = provinces.clone();
        d.belongs = belongs.clone();
        d.armies.clear();
        for (MapData.Army a : armies) {
            MapData.Army c = new MapData.Army(a.x, a.y, a.type, a.level);
            c.index = a.index; c.name = a.name; c.general = a.general;
            c.raw = a.raw != null ? a.raw.clone() : null;
            d.armies.add(c);
        }
        d.buildings.clear();
        for (MapData.Building b : buildings) {
            MapData.Building c = new MapData.Building();
            c.index = b.index; c.coord = b.coord; c.x = b.x; c.y = b.y; c.type = b.type;
            c.raw = b.raw != null ? b.raw.clone() : new byte[32];
            d.buildings.add(c);
        }
        d.traps.clear();
        for (MapData.Trap t : traps) {
            MapData.Trap c = new MapData.Trap();
            c.index = t.index; c.coord = t.coord; c.x = t.x; c.y = t.y;
            c.legion = t.legion; c.level = t.level; c.hp = t.hp;
            c.raw = t.raw != null ? t.raw.clone() : new byte[12];
            d.traps.add(c);
        }
        d.editedCells.clear();
        d.editedCells.addAll(editedCells);
        return true;
    }
}
