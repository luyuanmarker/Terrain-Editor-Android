package com.xckeji.bj.file;

import com.xckeji.bj.model.MapData;
import com.xckeji.bj.model.TerrainTile;
import com.xckeji.bj.model.ArmyConfig;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.function.IntUnaryOperator;

public class FileParser {

    public static MapData loadFile(byte[] data, String fileName) throws IOException {
        if (data == null || data.length < 16) throw new IOException("文件太小");
        ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int first = bb.getInt(0);
        if (first >= 1 && first <= 3) return loadBTL(data, fileName);
        int w = bb.getInt(8), h = bb.getInt(12);
        if (w > 0 && w <= 200 && h > 0 && h <= 200) return loadBIN(data, fileName);
        throw new IOException("无法识别的文件格式");
    }

    // ========= BTL =========

    public static class BtlHeaderInfo {
        public int version, mapId, captureX, captureY;
        public int width, height, legionCount, buildingCount, armyCount;
        public int planCount, eventCount, weatherCount;
        public int reinforceCount, airstrikeCount, mineCount, strategyCount, airSupportCount;
        public int placementCountA, placementCountB, capitalCount;
        public int terrainStart;
        public int buildingStart;
        /** 地图序号==0 时 BTL 自带地形；征服地图（序号!=0）地形在 world BIN 中。 */
        public boolean independentTerrain;
    }

    public static BtlHeaderInfo parseBTLHeader(byte[] data) {
        ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        BtlHeaderInfo h = new BtlHeaderInfo();
        h.version = bb.getInt(0x00);
        h.mapId = bb.getInt(0x04);
        h.captureX = bb.getInt(0x08);
        h.captureY = bb.getInt(0x0C);
        h.width = bb.getInt(0x10);
        h.height = bb.getInt(0x14);
        h.legionCount = bb.getInt(0x18);
        h.buildingCount = bb.getInt(0x1C);
        h.armyCount = bb.getInt(0x20);
        h.planCount = bb.getInt(0x24);
        h.eventCount = bb.getInt(0x28);
        h.weatherCount = bb.getInt(0x2C);
        h.reinforceCount = bb.getInt(0x3C);
        h.airstrikeCount = bb.getInt(0x40);
        h.placementCountA = bb.getInt(0x44);
        h.placementCountB = bb.getInt(0x48);
        h.capitalCount = bb.getInt(0x4C);
        h.mineCount = bb.getInt(0x68);
        h.strategyCount = bb.getInt(0x70);
        h.airSupportCount = bb.getInt(0x7C);
        h.independentTerrain = (h.mapId == 0);
        h.terrainStart = 128 + h.legionCount * 300;
        int totalTiles = h.width * h.height;
        // 征服地图（地图序号!=0）地形不在 BTL 中，直接是省规划段
        int terrainBytes = h.independentTerrain ? totalTiles * 16 : 0;
        int adminStart = h.terrainStart + terrainBytes;
        int ownershipStart = adminStart + totalTiles * 2;
        h.buildingStart = ownershipStart + totalTiles * 1;
        return h;
    }

    /** 事件段起始偏移：建筑 → 兵种 → 陷阱 → 方案 → 天气 → 事件（44字节/条）。 */
    public static int eventStart(BtlHeaderInfo h) {
        int cursor = h.buildingStart + h.buildingCount * 32;
        cursor += h.armyCount * armyRecSize(h.version);
        cursor += h.mineCount * 12;
        cursor += h.planCount * 16;
        cursor += h.weatherCount * 16;
        return cursor;
    }

    /** 把编辑后的 128 字节主数据（头部）写回 BTL。 */
    public static void patchHeader(MapData mapData, byte[] raw128) throws IOException {
        if (mapData == null || mapData.btlOriginalData == null
                || raw128 == null || raw128.length < 128) {
            throw new IOException("主数据无效");
        }
        System.arraycopy(raw128, 0, mapData.btlOriginalData, 0, 128);
    }

    /** 把编辑后的 44 字节事件记录写回 BTL。 */
    public static void patchEvent(MapData mapData, int index, byte[] raw44) throws IOException {
        if (mapData == null || mapData.btlOriginalData == null
                || raw44 == null || raw44.length < 44) {
            throw new IOException("事件数据无效");
        }
        BtlHeaderInfo h = parseBTLHeader(mapData.btlOriginalData);
        int start = eventStart(h);
        int addr = start + index * 44;
        int end = start + h.eventCount * 44;
        if (index < 0 || addr + 44 > end || end > mapData.btlOriginalData.length) {
            throw new IOException("事件记录越界");
        }
        System.arraycopy(raw44, 0, mapData.btlOriginalData, addr, 44);
    }

    /** 兵种记录大小：版本1=48字节，版本2/3=64字节。 */
    private static int armyRecSize(int version) {
        return version == 1 ? 48 : 64;
    }

    /** 援军记录大小：版本1/2=80字节，版本3=104字节。 */
    private static int reinforceRecSize(int version) {
        return version <= 2 ? 80 : 104;
    }

    /**
     * 检测该 BTL 坐标的存储约定：征服文件把省规划/建筑/兵种坐标存成“世界坐标”
     * （地图本地坐标 + 截取偏移 captureY*宽+captureX）。
     * 若截取偏移非 0，且省规划/建筑/兵种坐标普遍 >= 偏移、减去偏移后全部合法，
     * 则判定为世界坐标并返回该偏移；否则返回 0（地图本地坐标）。
     */
    private static int detectCoordBase(byte[] data, BtlHeaderInfo h) {
        int total = h.width * h.height;
        int offset = h.captureY * h.width + h.captureX;
        if (total <= 0 || offset <= 0) return 0;
        int checked = 0, ok = 0;
        // 省规划（2字节/格）
        int adminStart = h.terrainStart + (h.independentTerrain ? total * 16 : 0);
        for (int i = 0; i < total; i++) {
            int addr = adminStart + i * 2;
            if (addr + 2 > data.length) break;
            int pv = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
                    .getShort(addr) & 0xFFFF;
            if (pv == 0 || pv == 0xFFFF) continue;
            checked++;
            if (pv >= offset && pv - offset < total) ok++;
        }
        // 建筑（32字节/条，坐标在 0x0）
        for (int i = 0; i < h.buildingCount; i++) {
            int addr = h.buildingStart + i * 32;
            if (addr + 32 > data.length) break;
            int coord = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
                    .getShort(addr) & 0xFFFF;
            if (coord == 0 || coord == 0xFFFF) continue;
            checked++;
            if (coord >= offset && coord - offset < total) ok++;
        }
        // 兵种（坐标在 0x0；type=0 为空占位）
        int armyStart = h.buildingStart + h.buildingCount * 32;
        int rec = armyRecSize(h.version);
        for (int i = 0; i < h.armyCount; i++) {
            int addr = armyStart + i * rec;
            if (addr + rec > data.length) break;
            int coord = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
                    .getShort(addr) & 0xFFFF;
            int type = data[addr + 2] & 0xFF;
            if (type == 0 || coord == 0 || coord == 0xFFFF) continue;
            checked++;
            if (coord >= offset && coord - offset < total) ok++;
        }
        // 允许少量异常记录（5% 容差）
        if (checked > 0 && ok >= checked - Math.max(1, checked / 20)) return offset;
        return 0;
    }

    private static MapData loadBTL(byte[] data, String fileName) throws IOException {
        BtlHeaderInfo header = parseBTLHeader(data);
        MapData mapData = new MapData(header.width, header.height);
        mapData.fileName = fileName;
        mapData.btlOriginalData = data;
        mapData.coordBase = detectCoordBase(data, header);

        int totalTiles = header.width * header.height;
        if (header.independentTerrain) {
            for (int i = 0; i < totalTiles; i++) {
                mapData.tiles.get(i).parseFromBytes(data, header.terrainStart + i * 16);
            }
        } else {
            // 征服地图：地形来自 world BIN，此处只保留内容段
            for (int i = 0; i < totalTiles; i++) {
                TerrainTile t = mapData.tiles.get(i);
                t.bmTerrain1Group = 1; // 占位海洋，加载 BIN 后会覆盖
                t.bmTerrain1Id = 0;
            }
        }

        if (header.buildingCount > 0) {
            for (int i = 0; i < header.buildingCount; i++) {
                int addr = header.buildingStart + i * 32;
                if (addr + 32 > data.length) break;
                int coord = (ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
                        .getShort(addr) & 0xFFFF) - mapData.coordBase;
                if (coord < 0) continue;
                int bx = coord % header.width;
                int by = coord / header.width;
                int type = data[addr + 4] & 0xFF;
                if (bx < header.width && by < header.height) mapData.setBuildingId(bx, by, type);
            }
        }
        parseContentSections(mapData, data, header);
        mapData.buildTerrainPatterns();
        return mapData;
    }

    /** 解析军团颜色、军团归属与兵种列表（兵种段 48 字节/条）。 */
    private static void parseContentSections(MapData mapData, byte[] data, BtlHeaderInfo header) {
        mapData.coordBase = detectCoordBase(data, header);
        int totalTiles = header.width * header.height;
        // 省规划（2字节/格）
        int adminStart = header.terrainStart + (header.independentTerrain ? totalTiles * 16 : 0);
        mapData.provinces = new int[totalTiles];
        for (int i = 0; i < totalTiles; i++) {
            if (adminStart + i * 2 + 2 <= data.length) {
                int pv = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
                        .getShort(adminStart + i * 2) & 0xFFFF;
                // 征服文件省规划也是世界坐标：统一减掉截取偏移
                mapData.provinces[i] = (pv == 0 || pv == 0xFFFF)
                        ? pv : Math.max(0, pv - mapData.coordBase);
            }
        }
        int ownershipStart = header.buildingStart - totalTiles;
        mapData.belongs = new byte[totalTiles];
        if (ownershipStart >= 0 && ownershipStart + totalTiles <= data.length) {
            System.arraycopy(data, ownershipStart, mapData.belongs, 0, totalTiles);
        }
        mapData.legionColors = new int[header.legionCount];
        mapData.legionCountries = new int[header.legionCount];
        mapData.legions.clear();
        for (int i = 0; i < header.legionCount; i++) {
            int addr = 128 + i * 300;
            if (addr + 300 > data.length) break;
            mapData.legionCountries[i] = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getShort(addr + 0x4) & 0xFFFF;
            int r = data[addr + 0x28] & 0xFF;
            int g = data[addr + 0x29] & 0xFF;
            int b = data[addr + 0x2A] & 0xFF;
            mapData.legionColors[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
            MapData.Legion lg = new MapData.Legion();
            System.arraycopy(data, addr, lg.raw, 0, 300);
            ByteBuffer lb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            lg.seq = lb.getInt(addr);
            lg.country = lb.getInt(addr + 0x4);
            lg.control = lb.getInt(addr + 0x14);
            lg.faction = lb.getInt(addr + 0x18);
            lg.color = mapData.legionColors[i];
            mapData.legions.add(lg);
        }
        mapData.armies.clear();
        int armyStart = header.buildingStart + header.buildingCount * 32;
        int recSize = armyRecSize(header.version);
        for (int i = 0; i < header.armyCount; i++) {
            int addr = armyStart + i * recSize;
            if (addr + recSize > data.length) break;
            int coord = (ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
                    .getShort(addr) & 0xFFFF) - mapData.coordBase;
            int type = data[addr + 2] & 0xFF;
            // 兵种代码 0 = 空占位记录（坐标 0/0xFFFF、全字段为 0），不显示
            if (type == 0) continue;
            if (coord < 0) continue;
            int level = data[addr + 3] & 0xFF;
            int ax = coord % header.width;
            int ay = coord / header.width;
            if (ax < header.width && ay < header.height) {
                MapData.Army a = new MapData.Army(ax, ay, type, level);
                a.index = i;
                a.raw = new byte[recSize];
                System.arraycopy(data, addr, a.raw, 0, recSize);
                ArmyConfig cfg = ArmyConfig.byArmy(type);
                if (cfg != null) a.name = cfg.name;
                mapData.armies.add(a);
            }
        }
        // 城市/建筑记录（32 字节/条）：0x0 坐标、0x2 名称、0x4 类型、0x5 外观…
        mapData.buildings.clear();
        for (int i = 0; i < header.buildingCount; i++) {
            int addr = header.buildingStart + i * 32;
            if (addr + 32 > data.length) break;
            int coord = (ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
                    .getShort(addr) & 0xFFFF) - mapData.coordBase;
            if (coord < 0) continue;
            int type = data[addr + 4] & 0xFF;
            int bx = coord % header.width;
            int by = coord / header.width;
            if (bx < header.width && by < header.height) {
                MapData.Building b = new MapData.Building();
                b.index = i;
                b.coord = coord;
                b.x = bx;
                b.y = by;
                b.type = type;
                System.arraycopy(data, addr, b.raw, 0, 32);
                mapData.buildings.add(b);
            }
        }
    }

    /** 把编辑后的 32 字节城市记录写回 BTL 对应偏移，并同步内存地块建筑。 */
    public static void patchBuilding(MapData mapData, MapData.Building b, byte[] raw32)
            throws IOException {
        if (mapData == null || mapData.btlOriginalData == null || b == null
                || raw32 == null || raw32.length < 32) {
            throw new IOException("城市数据无效");
        }
        BtlHeaderInfo h = parseBTLHeader(mapData.btlOriginalData);
        int addr = h.buildingStart + b.index * 32;
        int sectionEnd = h.buildingStart + h.buildingCount * 32;
        if (addr + 32 > sectionEnd || sectionEnd > mapData.btlOriginalData.length) {
            throw new IOException("城市记录越界");
        }
        System.arraycopy(raw32, 0, mapData.btlOriginalData, addr, 32);
        // 内存同步：旧地块清除建筑，新坐标地块写入新类型
        int oldTile = b.y * mapData.width + b.x;
        int coord = ByteBuffer.wrap(raw32).order(ByteOrder.LITTLE_ENDIAN)
                .getShort(0) & 0xFFFF;
        int mapCoord = coord - mapData.coordBase;
        if (mapCoord < 0 || mapCoord >= mapData.getTotalTiles()) {
            throw new IOException("城市坐标越界");
        }
        int type = raw32[4] & 0xFF;
        int nx = mapCoord % mapData.width;
        int ny = mapCoord / mapData.width;
        int newTile = ny * mapData.width + nx;
        if (oldTile >= 0 && oldTile < mapData.buildingIds.size() && oldTile != newTile) {
            mapData.buildingIds.set(oldTile, 0);
        }
        if (newTile >= 0 && newTile < mapData.buildingIds.size()) {
            mapData.buildingIds.set(newTile, type);
        }
        b.coord = coord;
        b.x = nx;
        b.y = ny;
        b.type = type;
        System.arraycopy(raw32, 0, b.raw, 0, 32);
    }

    /** 把编辑后的 48 字节兵种记录写回 BTL 对应偏移。 */
    public static void patchArmy(MapData mapData, MapData.Army army, byte[] raw48) throws IOException {
        if (mapData == null || mapData.btlOriginalData == null || army == null
                || raw48 == null || raw48.length < 48) {
            throw new IOException("兵种数据无效");
        }
        BtlHeaderInfo h = parseBTLHeader(mapData.btlOriginalData);
        int armyStart = h.buildingStart + h.buildingCount * 32;
        int recSize = armyRecSize(h.version);
        if (raw48 == null || raw48.length < recSize) throw new IOException("兵种记录无效");
        int addr = armyStart + army.index * recSize;
        int sectionEnd = armyStart + h.armyCount * recSize;
        if (addr + recSize > sectionEnd || sectionEnd > mapData.btlOriginalData.length) {
            throw new IOException("兵种记录越界");
        }
        System.arraycopy(raw48, 0, mapData.btlOriginalData, addr, recSize);
    }

    /**
     * 在兵种段末尾新增一条 48 字节兵种记录（后续各段整体后移），更新兵种总数，
     * 并把该地块的军团归属设为指定军团（单位无归属会导致游戏闪退）。
     */
    public static void addArmy(MapData mapData, int x, int y, int type, byte[] raw48, int legion) throws IOException {
        if (mapData == null || mapData.btlOriginalData == null) {
            throw new IOException("请先加载 BTL 地图");
        }
        if (x < 0 || y < 0 || x >= mapData.width || y >= mapData.height) {
            throw new IOException("地块坐标越界");
        }
        if (raw48 == null || raw48.length < 48) {
            throw new IOException("兵种记录无效");
        }
        byte[] oldBtl = mapData.btlOriginalData;
        BtlHeaderInfo h = parseBTLHeader(oldBtl);
        int armyStart = h.buildingStart + h.buildingCount * 32;
        int recSize = armyRecSize(h.version);
        if (raw48 == null || raw48.length < 48) {
            throw new IOException("兵种记录无效");
        }
        byte[] rec = new byte[recSize];
        System.arraycopy(raw48, 0, rec, 0, Math.min(raw48.length, recSize));
        int armyBytes = h.armyCount * recSize;
        if (armyStart + armyBytes > oldBtl.length) {
            throw new IOException("兵种段越界");
        }
        byte[] result = new byte[oldBtl.length + recSize];
        // 兵种段之前（含军团/地形/省规划/归属/建筑）
        System.arraycopy(oldBtl, 0, result, 0, armyStart + armyBytes);
        // 新增记录
        System.arraycopy(rec, 0, result, armyStart + armyBytes, recSize);
        // 兵种段之后整体后移
        int rest = oldBtl.length - (armyStart + armyBytes);
        if (rest > 0) {
            System.arraycopy(oldBtl, armyStart + armyBytes, result, armyStart + armyBytes + recSize, rest);
        }
        ByteBuffer bb = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(0x20, h.armyCount + 1);
        // 军团归属：地块归入指定军团（0xFF=中立；单位必须有归属）
        int ownershipStart = h.buildingStart - h.width * h.height;
        int tileIdx = y * mapData.width + x;
        if (legion >= 0 && legion <= 0xFF && ownershipStart >= 0
                && ownershipStart + tileIdx < result.length) {
            result[ownershipStart + tileIdx] = (byte) legion;
        }
        if (mapData.belongs != null && tileIdx < mapData.belongs.length) {
            mapData.belongs[tileIdx] = (byte) legion;
        }
        mapData.btlOriginalData = result;
        refreshArmies(mapData);
    }

    /** 把编辑后的 300 字节军团记录写回 BTL。 */
    public static void patchLegion(MapData mapData, MapData.Legion legion, byte[] raw300) throws IOException {
        if (mapData == null || mapData.btlOriginalData == null || legion == null
                || raw300 == null || raw300.length < 300) {
            throw new IOException("军团数据无效");
        }
        int index = mapData.legions.indexOf(legion);
        if (index < 0) throw new IOException("军团不存在");
        int addr = 128 + index * 300;
        if (addr + 300 > mapData.btlOriginalData.length) throw new IOException("军团记录越界");
        System.arraycopy(raw300, 0, mapData.btlOriginalData, addr, 300);
    }

    /** 从当前 btlOriginalData 重新解析兵种与军团数据（扩展/裁剪后调用）。 */
    public static void refreshArmies(MapData mapData) {
        if (mapData == null || mapData.btlOriginalData == null) return;
        parseContentSections(mapData, mapData.btlOriginalData, parseBTLHeader(mapData.btlOriginalData));
    }

    public static byte[] saveAsBTL(MapData mapData) throws IOException {
        int totalTiles = mapData.width * mapData.height;
        if (mapData.width < 1 || mapData.height < 1 || totalTiles > 65535) {
            throw new IOException("BTL 地图尺寸无效（地块坐标最大支持 65535）");
        }

        if (mapData.btlOriginalData != null) {
            // 有原始BTL：基于原始数据修改（支持扩展后的文件）
            BtlHeaderInfo h = parseBTLHeader(mapData.btlOriginalData);
            byte[] oldBtl = mapData.btlOriginalData;
            int oldTotalTiles = h.width * h.height;
            int newTotalTiles = mapData.width * mapData.height;
            int terrainStart = h.terrainStart;

            // 征服地图（地图序号!=0）地形不在 BTL 中，各段偏移不含地形
            int oldTerrainBytes = h.independentTerrain ? oldTotalTiles * 16 : 0;
            int newTerrainBytes = h.independentTerrain ? newTotalTiles * 16 : 0;

            // 旧文件各段偏移
            int oldAdminStart = terrainStart + oldTerrainBytes;
            int oldOwnershipStart = oldAdminStart + oldTotalTiles * 2;
            int oldBuildingStart = oldOwnershipStart + oldTotalTiles;
            int oldBuildingBytes = h.buildingCount * 32;
            int oldAfterBuildings = oldBtl.length - (oldBuildingStart + oldBuildingBytes);
            if (oldAfterBuildings < 0) oldAfterBuildings = 0;

            // 新文件各段偏移
            int newAdminStart = terrainStart + newTerrainBytes;
            int newOwnershipStart = newAdminStart + newTotalTiles * 2;
            int newBuildingStart = newOwnershipStart + newTotalTiles;

            // 建筑段：按内存建筑状态重建（保留未修改记录的全部字段）
            byte[] newBuildings = mergeBuildings(oldBtl, oldBuildingStart, h.buildingCount, mapData);
            int newBuildingBytes = newBuildings.length;

            int newSize = newBuildingStart + newBuildingBytes + oldAfterBuildings;
            byte[] result = new byte[newSize];

            // 1. 头部与军团段
            System.arraycopy(oldBtl, 0, result, 0, terrainStart);

            // 2. 地形（16字节/格）——仅独立地图（地图序号==0）写入 BTL
            if (h.independentTerrain) {
                for (int i = 0; i < newTotalTiles; i++) {
                    mapData.tiles.get(i).toBytes(result, terrainStart + i * 16);
                }
            }

            // 3. 省规划（2字节/格）
            for (int i = 0; i < newTotalTiles; i++) {
                int addr = newAdminStart + i * 2;
                if (i < oldTotalTiles) {
                    result[addr] = oldBtl[oldAdminStart + i * 2];
                    result[addr + 1] = oldBtl[oldAdminStart + i * 2 + 1];
                } else {
                    result[addr] = 0;
                    result[addr + 1] = 0;
                }
            }

            // 4. 军团归属（1字节/格）
            for (int i = 0; i < newTotalTiles; i++) {
                result[newOwnershipStart + i] = i < oldTotalTiles ? oldBtl[oldOwnershipStart + i] : (byte) 0xFF;
            }

            // 5. 建筑段
            System.arraycopy(newBuildings, 0, result, newBuildingStart, newBuildingBytes);

            // 6. 建筑之后的业务段/尾段原样搬运（保留版本私有字段）
            if (oldAfterBuildings > 0) {
                System.arraycopy(oldBtl, oldBuildingStart + oldBuildingBytes, result,
                        newBuildingStart + newBuildingBytes, oldAfterBuildings);
            }

            // 7. 头部宽高、地块总数与建筑计数
            ByteBuffer bb = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN);
            bb.putInt(0x10, mapData.width);
            bb.putInt(0x14, mapData.height);
            bb.putInt(0x58, newTotalTiles);
            bb.putInt(0x1C, newBuildingBytes / 32);
            return result;
        } else {
            // 新建战役：写出标准 BTL 的完整基础段。可选业务段计数均为 0，
            // 因而不存在伪造或错位的未知记录；游戏和本编辑器都可按普通 BTL 读取。
            int headerSize = 128;
            int legionCount = 2;
            int legionDataSize = legionCount * 300;
            int terrainStart = headerSize + legionDataSize;
            int terrainSize = totalTiles * 16;
            int adminSize = totalTiles * 2;
            int ownershipSize = totalTiles * 1;
            byte[] buildings = buildBuildings(mapData);
            int totalSize = terrainStart + terrainSize + adminSize + ownershipSize + buildings.length;

            byte[] result = new byte[totalSize];
            ByteBuffer bb = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN);

            // ===== header（128字节）=====
            bb.putInt(0x00, 1);           // version=1（必须=1）
            bb.putInt(0x04, 0);           // mapId=0（自定义地图）
            bb.putInt(0x08, 0);           // captureX
            bb.putInt(0x0C, 0);           // captureY
            bb.putInt(0x10, mapData.width);
            bb.putInt(0x14, mapData.height);
            bb.putInt(0x18, legionCount); // legionCount=2
            bb.putInt(0x1C, buildings.length / 32);
            bb.putInt(0x20, 0);           // armyCount
            bb.putInt(0x24, 0);           // planCount
            bb.putInt(0x28, 0);           // eventCount
            bb.putInt(0x2C, 0);           // weatherCount
            bb.putInt(0x30, 1);           // victoryCondition=1（征服）
            bb.putInt(0x34, 999);         // minTurns
            bb.putInt(0x38, 999);         // maxTurns
            bb.putInt(0x3C, 0);           // reinforceCount
            bb.putInt(0x40, 0);           // airstrikeCount
            // 0x44-0x4F 默认0
            bb.putInt(0x50, 0);           // padding
            bb.putInt(0x54, 1);           // 参考原始
            bb.putInt(0x58, totalTiles);  // 总格子数
            bb.putInt(0x5C, 0);           // 参考原始
            bb.putInt(0x60, 0);
            bb.putInt(0x64, 0);
            bb.putInt(0x68, 0);           // mineCount=0
            bb.putInt(0x6C, 0);
            bb.putInt(0x70, 0);           // strategyCount=0
            bb.putInt(0x74, 0);
            bb.putInt(0x78, 0);
            bb.putInt(0x7C, 0);           // airSupportCount=0

            // ===== 军团数据段 =====
            // 保留两个有效阵营槽位，其他科技、资源默认均为 0。
            // 这样空白战役可以在游戏数据或后续编辑器功能中继续填充。
            bb.putInt(0x80, 1);           // 第一军团序号
            bb.putInt(0x84, 1);           // 英国（国家 ID 1）
            bb.putInt(0x80 + 300, 2);     // 第二军团序号
            bb.putInt(0x84 + 300, 3);     // 德国（国家 ID 3）

            // ===== 地形数据（16字节/格）=====
            for (int i = 0; i < totalTiles; i++) {
                mapData.tiles.get(i).toBytes(result, terrainStart + i * 16);
            }

            // ===== admin区域（2字节/格）=====
            int adminStart = terrainStart + terrainSize;
            for (int i = 0; i < totalTiles; i++) {
                TerrainTile tile = mapData.tiles.get(i);
                if (tile.bmTerrain1Group == 1) {
                    result[adminStart + i * 2] = 0;
                    result[adminStart + i * 2 + 1] = 0;
                } else {
                    result[adminStart + i * 2] = (byte)0xFF;
                    result[adminStart + i * 2 + 1] = (byte)0xFF;
                }
            }

            // ===== 城市归属（1字节/格）=====
            int ownershipStart = adminStart + adminSize;
            for (int i = 0; i < totalTiles; i++) {
                result[ownershipStart + i] = (byte)0xFF;
            }

            System.arraycopy(buildings, 0, result, ownershipStart + ownershipSize, buildings.length);

            return result;
        }
    }

    /**
     * 从正常 BTL 的固定头部和军团段新建空战役。
     * 所有计数型业务段均为 0；因此文件在归属数组后结束，不会残留模板的单位、建筑、
     * 事件或未知尾部记录。地形统一使用模板中已有的平原记录。
     */
    public static MapData createEmptyBtlFromTemplate(byte[] template, String fileName, int newWidth, int newHeight)
            throws IOException {
        if (newWidth < 3 || newWidth > 200 || newHeight < 3 || newHeight > 200) {
            throw new IOException("地图宽高范围为 3–200");
        }
        BtlHeaderInfo h = parseBTLHeader(template);
        int oldTotal = h.width * h.height;
        if (h.width <= 0 || h.height <= 0 || h.terrainStart + oldTotal * 16 > template.length) {
            throw new IOException("BTL 模板结构不完整");
        }

        int newTotal = newWidth * newHeight;
        int newAdminStart = h.terrainStart + newTotal * 16;
        int newOwnershipStart = newAdminStart + newTotal * 2;
        int newBuildingStart = newOwnershipStart + newTotal;
        // 0x44 / 0x48 对应的固定尾段未包含在已知字段表中。必须保留它，
        // 否则官方编辑器虽能识别头部，却无法建立主数据并显示黑屏。
        int fixedTailStart = h.buildingStart
                + h.buildingCount * 32
                + h.armyCount * 48
                + h.planCount * 16
                + h.eventCount * 44
                + h.weatherCount * 16
                + h.reinforceCount * 80
                + h.airstrikeCount * 20
                + h.mineCount * 12
                + h.strategyCount * 16
                + h.airSupportCount * 16;
        if (fixedTailStart > template.length) throw new IOException("模板固定尾段越界");
        byte[] result = new byte[newBuildingStart + (template.length - fixedTailStart)];

        // 头部和军团段是模板的固定业务数据，完整复制。
        System.arraycopy(template, 0, result, 0, h.terrainStart);

        // 找到模板中实际使用的平原地块（BTL 中平原主地形组为 0）。
        byte[] fillTile = new byte[16];
        boolean foundPlain = false;
        for (int i = 0; i < oldTotal; i++) {
            int offset = h.terrainStart + i * 16;
            // 标准平原：主地形 00 FF，两层装饰均为 3F FF 且没有偏移或底层覆盖。
            // 仅按 group=0 取第一个格会误取带道路/特殊装饰的平原，官方编辑器会黑屏。
            if ((template[offset] & 0xFF) == 0
                    && (template[offset + 1] & 0xFF) == 0xFF
                    && (template[offset + 4] & 0xFF) == 0x3F
                    && (template[offset + 5] & 0xFF) == 0xFF
                    && (template[offset + 8] & 0xFF) == 0x3F
                    && (template[offset + 9] & 0xFF) == 0xFF
                    && template[offset + 2] == 0 && template[offset + 3] == 0
                    && template[offset + 6] == 0 && template[offset + 7] == 0
                    && template[offset + 10] == 0 && template[offset + 11] == 0
                    && template[offset + 12] == 0 && template[offset + 13] == 0
                    && template[offset + 14] == 0 && template[offset + 15] == 0) {
                System.arraycopy(template, offset, fillTile, 0, 16);
                foundPlain = true;
                break;
            }
        }
        if (!foundPlain) throw new IOException("模板中未找到平原地形记录");
        for (int i = 0; i < newTotal; i++) {
            System.arraycopy(fillTile, 0, result, h.terrainStart + i * 16, 16);
            result[newAdminStart + i * 2] = 0;
            result[newAdminStart + i * 2 + 1] = 0;
            result[newOwnershipStart + i] = (byte) 0xFF;
        }

        ByteBuffer header = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(0x10, newWidth);
        header.putInt(0x14, newHeight);
        header.putInt(0x58, newTotal);
        // 清空所有有记录数组的计数；空 BTL 的末尾就是 ownership 数组。
        header.putInt(0x1C, 0); // 建筑
        header.putInt(0x20, 0); // 兵种
        header.putInt(0x24, 0); // 方案
        header.putInt(0x28, 0); // 事件
        header.putInt(0x2C, 0); // 天气
        header.putInt(0x3C, 0); // 援军
        header.putInt(0x40, 0); // 空袭
        header.putInt(0x68, 0); // 陷阱
        header.putInt(0x70, 0); // 战略建设
        header.putInt(0x7C, 0); // 空中支援

        // 只复制固定尾段；建筑、单位等依据已清零的计数不会被复制。
        System.arraycopy(template, fixedTailStart, result, newBuildingStart,
                template.length - fixedTailStart);

        return loadBTL(result, fileName);
    }

    /**
     * 从模板新建“中立”空战役：结构与 createEmptyBtlFromTemplate 一致（保留官方编辑器
     * 所需的固定尾段），但军团段不再克隆 stage10103 模板的具体国家，只写两个通用空槽位。
     * 用于 BIN→BTL 转换，避免转换后把地图的国家替换成模板的那几个国家。
     */
    public static MapData createEmptyBtlNeutral(byte[] template, String fileName,
                                                int newWidth, int newHeight) throws IOException {
        if (newWidth < 3 || newWidth > 200 || newHeight < 3 || newHeight > 200) {
            throw new IOException("地图宽高范围为 3–200");
        }
        BtlHeaderInfo h = parseBTLHeader(template);
        int oldTotal = h.width * h.height;
        if (h.width <= 0 || h.height <= 0 || h.terrainStart + oldTotal * 16 > template.length) {
            throw new IOException("BTL 模板结构不完整");
        }

        int newTotal = newWidth * newHeight;
        int legionCount = 2; // 两个通用军团槽位，不携带模板的具体国家
        int terrainStart = 128 + legionCount * 300;
        int newAdminStart = terrainStart + newTotal * 16;
        int newOwnershipStart = newAdminStart + newTotal * 2;
        int newBuildingStart = newOwnershipStart + newTotal;
        // 0x44 / 0x48 对应的固定尾段必须保留，否则官方编辑器无法建立主数据（黑屏）。
        int fixedTailStart = h.buildingStart
                + h.buildingCount * 32
                + h.armyCount * armyRecSize(h.version)
                + h.planCount * 16
                + h.eventCount * 44
                + h.weatherCount * 16
                + h.reinforceCount * reinforceRecSize(h.version)
                + h.airstrikeCount * 20
                + h.mineCount * 12
                + h.strategyCount * 16
                + h.airSupportCount * 16;
        if (fixedTailStart > template.length) throw new IOException("模板固定尾段越界");
        byte[] result = new byte[newBuildingStart + (template.length - fixedTailStart)];

        // 只复制 128 字节头部，不复制模板的军团段
        System.arraycopy(template, 0, result, 0, 128);
        ByteBuffer lb = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN);
        lb.putInt(0x18, legionCount);
        lb.putInt(0x80, 1);       // 第一军团序号
        lb.putInt(0x84, 1);       // 英国（游戏可识别的通用国家）
        lb.putInt(0x80 + 300, 2); // 第二军团序号
        lb.putInt(0x84 + 300, 3); // 德国

        // 找到模板中实际使用的平原地块（BTL 中平原主地形组为 0）。
        byte[] fillTile = new byte[16];
        boolean foundPlain = false;
        for (int i = 0; i < oldTotal; i++) {
            int offset = h.terrainStart + i * 16;
            if ((template[offset] & 0xFF) == 0
                    && (template[offset + 1] & 0xFF) == 0xFF
                    && (template[offset + 4] & 0xFF) == 0x3F
                    && (template[offset + 5] & 0xFF) == 0xFF
                    && (template[offset + 8] & 0xFF) == 0x3F
                    && (template[offset + 9] & 0xFF) == 0xFF
                    && template[offset + 2] == 0 && template[offset + 3] == 0
                    && template[offset + 6] == 0 && template[offset + 7] == 0
                    && template[offset + 10] == 0 && template[offset + 11] == 0
                    && template[offset + 12] == 0 && template[offset + 13] == 0
                    && template[offset + 14] == 0 && template[offset + 15] == 0) {
                System.arraycopy(template, offset, fillTile, 0, 16);
                foundPlain = true;
                break;
            }
        }
        if (!foundPlain) throw new IOException("模板中未找到平原地形记录");
        for (int i = 0; i < newTotal; i++) {
            System.arraycopy(fillTile, 0, result, terrainStart + i * 16, 16);
            result[newAdminStart + i * 2] = 0;
            result[newAdminStart + i * 2 + 1] = 0;
            result[newOwnershipStart + i] = (byte) 0xFF;
        }

        ByteBuffer header = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(0x10, newWidth);
        header.putInt(0x14, newHeight);
        header.putInt(0x58, newTotal);
        header.putInt(0x1C, 0); // 建筑
        header.putInt(0x20, 0); // 兵种
        header.putInt(0x24, 0); // 方案
        header.putInt(0x28, 0); // 事件
        header.putInt(0x2C, 0); // 天气
        header.putInt(0x3C, 0); // 援军
        header.putInt(0x40, 0); // 空袭
        header.putInt(0x68, 0); // 陷阱
        header.putInt(0x70, 0); // 战略建设
        header.putInt(0x7C, 0); // 空中支援

        System.arraycopy(template, fixedTailStart, result, newBuildingStart,
                template.length - fixedTailStart);
        return loadBTL(result, fileName);
    }

    /**
     * 按统一的 BTL 业务段布局，把建筑段之后各记录段中的“地块索引”字段交给 remap 处理。
     * 段尺寸与顺序：建筑 32(由调用方处理) → 兵种 48 → 陷阱 12 → 方案 16 → 天气 16 → 事件 44
     *   → 援军 80 → 空袭 20 → 放置单位 8 → 首都 4 → 战略建设 16 → 空中支援 16
     * 只有含地块索引的字段才会被修正：兵种 0x0、陷阱 0x0、方案 0xC(目标地块)、援军 0x0、
     * 空袭 0x0、放置单位 0x0、首都 0x0。
     * 0 视为“无目标/未设置”，0xFFFF 视为哨兵，超出旧地块总数的值一律不修改。
     *
     * @param base 建筑段在目标文件中的起始偏移（从建筑段之后开始遍历）
     */
    public static void remapSectionTileIndexes(byte[] btl, int base, BtlHeaderInfo h,
                                               IntUnaryOperator remap, int coordBase) {
        int oldTotal = h.width * h.height;
        int cursor = base + h.buildingCount * 32;

        int armyRec = armyRecSize(h.version);
        int reinforceRec = reinforceRecSize(h.version);
        remapSection(btl, cursor, h.armyCount, armyRec, 0, oldTotal, remap, coordBase);
        cursor += h.armyCount * armyRec;
        remapSection(btl, cursor, h.mineCount, 12, 0, oldTotal, remap, coordBase);
        cursor += h.mineCount * 12;
        remapSection(btl, cursor, h.planCount, 16, 12, oldTotal, remap, coordBase); // 方案：目标地块在 0xC
        cursor += h.planCount * 16;
        cursor += h.weatherCount * 16;  // 天气：无地块索引
        cursor += h.eventCount * 44;    // 事件：无地块索引
        remapSection(btl, cursor, h.reinforceCount, reinforceRec, 0, oldTotal, remap, coordBase);
        cursor += h.reinforceCount * reinforceRec;
        remapSection(btl, cursor, h.airstrikeCount, 20, 0, oldTotal, remap, coordBase);
        cursor += h.airstrikeCount * 20;
        // 放置单位（0x44+0x48 计数）8字节/条：坐标在 0x0
        remapSection(btl, cursor, h.placementCountA + h.placementCountB, 8, 0, oldTotal, remap, coordBase);
        cursor += (h.placementCountA + h.placementCountB) * 8;
        // 首都（0x4C 计数）4字节/条：坐标在 0x0
        remapSection(btl, cursor, h.capitalCount, 4, 0, oldTotal, remap, coordBase);
        cursor += h.capitalCount * 4;
        cursor += h.strategyCount * 16;   // 战略建设：无地块索引
        cursor += h.airSupportCount * 16; // 空中支援：无地块索引
    }

    private static void remapSection(byte[] btl, int base, int count, int recordSize, int fieldOffset,
                                     int oldTotal, IntUnaryOperator remap, int coordBase) {
        if (count <= 0 || base < 0) return;
        for (int i = 0; i < count; i++) {
            int addr = base + i * recordSize;
            if (addr + fieldOffset + 2 > btl.length) break;
            int idx = (ByteBuffer.wrap(btl).order(ByteOrder.LITTLE_ENDIAN)
                    .getShort(addr + fieldOffset) & 0xFFFF) - coordBase;
            if (idx < 0) continue;
            if (idx > 0 && idx < oldTotal) {
                int v = remap.applyAsInt(idx);
                if (v < 0 || v > 0xFFFF) continue;
                int stored = v + coordBase;
                btl[addr + fieldOffset] = (byte) (stored & 0xFF);
                btl[addr + fieldOffset + 1] = (byte) ((stored >>> 8) & 0xFF);
            }
        }
    }

    /** 将编辑器中的建筑格子转换为紧凑的 32 字节 BTL 建筑记录。 */
    private static byte[] buildBuildings(MapData mapData) {
        int count = mapData.getBuildingCount();
        byte[] records = new byte[count * 32];
        int record = 0;
        for (int i = 0; i < mapData.getTotalTiles(); i++) {
            int type = mapData.buildingIds.get(i);
            if (type <= 0) continue;
            int offset = record++ * 32;
            // 0x00: uint16 地块坐标；0x04: 建筑类型/名称代码（与 loadBTL 保持一致）。
            records[offset] = (byte) (i & 0xFF);
            records[offset + 1] = (byte) ((i >>> 8) & 0xFF);
            records[offset + 4] = (byte) type;
        }
        return records;
    }

    /**
     * 将编辑器内存中的建筑状态合并回原始建筑记录（32 字节/条，0x0=地块坐标，0x4=类型代码）：
     * - 原记录仍存在的建筑：保留记录的全部字段，仅更新类型代码；
     * - 原记录类型为 0（编辑器视为无建筑，但可能是游戏标记）：原样保留；
     * - 原记录有建筑、内存中已被清除：丢弃该记录；
     * - 内存新增的建筑：追加新记录（坐标 + 类型代码，其余字段默认 0）。
     */
    private static byte[] mergeBuildings(byte[] oldBtl, int oldBuildingStart, int oldBuildingCount,
                                         MapData mapData) {
        int total = mapData.getTotalTiles();
        int newCount = mapData.getBuildingCount();
        for (int i = 0; i < oldBuildingCount; i++) {
            int addr = oldBuildingStart + i * 32;
            if (addr + 32 > oldBtl.length) break;
            int coord = (ByteBuffer.wrap(oldBtl).order(ByteOrder.LITTLE_ENDIAN)
                    .getShort(addr) & 0xFFFF) - mapData.coordBase;
            int bid = (coord >= 0 && coord < total) ? mapData.buildingIds.get(coord) : 0;
            if (bid <= 0 && (oldBtl[addr + 4] & 0xFF) == 0) newCount++;
        }
        byte[] out = new byte[newCount * 32];
        java.util.Map<Integer, Integer> oldRecordByTile = new java.util.HashMap<>();
        for (int i = 0; i < oldBuildingCount; i++) {
            int addr = oldBuildingStart + i * 32;
            if (addr + 32 > oldBtl.length) break;
            int coord = (ByteBuffer.wrap(oldBtl).order(ByteOrder.LITTLE_ENDIAN)
                    .getShort(addr) & 0xFFFF) - mapData.coordBase;
            oldRecordByTile.put(coord, addr);
        }
        int outIdx = 0;
        // 原有记录：仍在内存中则保留并更新类型
        for (int i = 0; i < oldBuildingCount; i++) {
            int addr = oldBuildingStart + i * 32;
            if (addr + 32 > oldBtl.length) break;
            int coord = (ByteBuffer.wrap(oldBtl).order(ByteOrder.LITTLE_ENDIAN)
                    .getShort(addr) & 0xFFFF) - mapData.coordBase;
            int bid = (coord >= 0 && coord < total) ? mapData.buildingIds.get(coord) : 0;
            if (bid > 0) {
                System.arraycopy(oldBtl, addr, out, outIdx * 32, 32);
                out[outIdx * 32 + 4] = (byte) bid;
                outIdx++;
            } else if ((oldBtl[addr + 4] & 0xFF) == 0) {
                // 类型 0 的原记录：不是建筑，原样保留
                System.arraycopy(oldBtl, addr, out, outIdx * 32, 32);
                outIdx++;
            }
        }
        // 新增建筑：内存有、原文件没有
        for (int i = 0; i < total; i++) {
            int bid = mapData.buildingIds.get(i);
            if (bid <= 0 || oldRecordByTile.containsKey(i)) continue;
            int off = outIdx * 32;
            int stored = i + mapData.coordBase;
            out[off] = (byte) (stored & 0xFF);
            out[off + 1] = (byte) ((stored >>> 8) & 0xFF);
            out[off + 4] = (byte) bid;
            outIdx++;
        }
        return out;
    }

    /**
     * 裁剪地图：仅保留 [ax,ay] 到 [bx,by] 矩形区域（含端点），其余删除。
     * - 内存 tiles/buildingIds/采样色 裁剪；
     * - 独立 BTL：裁剪地形/省规划/归属数组，建筑及各坐标记录筛选+坐标重映射；
     * - 征服地图：同时更新头部截取坐标（0x08/0x0C）与宽高，世界 BIN 保持不动；
     * - 纯 BIN 地图：重建简化 BIN。
     */
    public static MapData cropMap(MapData mapData, int ax, int ay, int bx, int by) throws IOException {
        if (mapData == null) throw new IOException("未加载地图");
        int x1 = Math.min(ax, bx), x2 = Math.max(ax, bx);
        int y1 = Math.min(ay, by), y2 = Math.max(ay, by);
        int newW = x2 - x1 + 1, newH = y2 - y1 + 1;
        if (newW < 3 || newH < 3 || newW > 200 || newH > 200) {
            throw new IOException("裁剪后宽高范围为 3–200");
        }
        int oldW = mapData.width, oldH = mapData.height;
        if (x1 < 0 || y1 < 0 || x2 >= oldW || y2 >= oldH) throw new IOException("裁剪区域超出地图");

        // 1. 内存裁剪
        java.util.List<TerrainTile> newTiles = new java.util.ArrayList<>(newW * newH);
        java.util.List<Integer> newBids = new java.util.ArrayList<>(newW * newH);
        java.util.List<Integer> newSampled = new java.util.ArrayList<>(newW * newH);
        for (int y = y1; y <= y2; y++) {
            for (int x = x1; x <= x2; x++) {
                int si = y * oldW + x;
                newTiles.add(mapData.tiles.get(si));
                newBids.add(mapData.buildingIds.get(si));
                newSampled.add(mapData.sampledColors != null && si < mapData.sampledColors.size()
                        ? mapData.sampledColors.get(si) : 0);
            }
        }
        mapData.tiles = newTiles;
        mapData.buildingIds = newBids;
        mapData.sampledColors = newSampled;
        mapData.width = newW;
        mapData.height = newH;
        mapData.selectedBlocks.clear();
        mapData.multiSelectMode = false;
        mapData.editedCells.clear();

        if (mapData.btlOriginalData != null) {
            byte[] oldBtl = mapData.btlOriginalData;
            BtlHeaderInfo h = parseBTLHeader(oldBtl);
            int oldTotal = oldW * oldH, newTotal = newW * newH;
            int terrainStart = h.terrainStart;
            int oldTerrainBytes = h.independentTerrain ? oldTotal * 16 : 0;
            int oldAdminStart = terrainStart + oldTerrainBytes;
            int oldOwnershipStart = oldAdminStart + oldTotal * 2;
            int oldBuildingStart = oldOwnershipStart + oldTotal;
            int oldBuildingBytes = h.buildingCount * 32;
            int oldAfterBuildings = oldBtl.length - (oldBuildingStart + oldBuildingBytes);
            if (oldAfterBuildings < 0) oldAfterBuildings = 0;

            int newTerrainBytes = h.independentTerrain ? newTotal * 16 : 0;
            int newAdminStart = terrainStart + newTerrainBytes;
            int newOwnershipStart = newAdminStart + newTotal * 2;

            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            out.write(oldBtl, 0, terrainStart);
            // 地形（仅独立地图）
            if (h.independentTerrain) {
                for (int y = y1; y <= y2; y++) {
                    for (int x = x1; x <= x2; x++) {
                        out.write(oldBtl, terrainStart + (y * oldW + x) * 16, 16);
                    }
                }
            }
            // 省规划（2字节/格）：值是“省份代表地块坐标”，裁剪后重映射到新坐标
            for (int y = y1; y <= y2; y++) {
                for (int x = x1; x <= x2; x++) {
                    int si = y * oldW + x;
                    int oldPv = (oldBtl[oldAdminStart + si * 2] & 0xFF)
                            | ((oldBtl[oldAdminStart + si * 2 + 1] & 0xFF) << 8);
                    int npv = oldPv;
                    if (oldPv != 0 && oldPv != 0xFFFF) {
                        int cb = mapData.coordBase;
                        int localPv = oldPv - cb;
                        if (localPv >= 0 && localPv < oldTotal) {
                            int px = localPv % oldW, py = localPv / oldW;
                            if (px >= x1 && px < x1 + newW && py >= y1 && py < y1 + newH) {
                                npv = (py - y1) * newW + (px - x1) + cb;
                            }
                        }
                    }
                    out.write(npv & 0xFF);
                    out.write((npv >> 8) & 0xFF);
                }
            }
            // 军团归属（1字节/格）
            for (int y = y1; y <= y2; y++) {
                for (int x = x1; x <= x2; x++) {
                    out.write(oldBtl[oldOwnershipStart + y * oldW + x]);
                }
            }
            // 建筑：区域内保留 + 坐标重映射
            int[] bCount = {0};
            cropBuildings(out, oldBtl, oldBuildingStart, h.buildingCount,
                    oldW, x1, y1, newW, newH, oldTotal, mapData, bCount);
            // 建筑之后各段
            int cursor = oldBuildingStart + oldBuildingBytes;
            int armyRec = armyRecSize(h.version);
            int reinforceRec = reinforceRecSize(h.version);
            int[] armyKept = {0};
            cursor = cropCoordSection(out, oldBtl, cursor, h.armyCount, armyRec, 0,
                    oldW, x1, y1, newW, newH, oldTotal, false, armyKept, mapData.coordBase);
            int[] mineKept = {0};
            cursor = cropCoordSection(out, oldBtl, cursor, h.mineCount, 12, 0,
                    oldW, x1, y1, newW, newH, oldTotal, false, mineKept, mapData.coordBase);
            int[] planKept = {0};
            cursor = cropCoordSection(out, oldBtl, cursor, h.planCount, 16, 12,
                    oldW, x1, y1, newW, newH, oldTotal, true, planKept, mapData.coordBase);
            cursor = copySection(out, oldBtl, cursor, h.weatherCount, 16);
            cursor = copySection(out, oldBtl, cursor, h.eventCount, 44);
            int[] reinfKept = {0};
            cursor = cropCoordSection(out, oldBtl, cursor, h.reinforceCount, reinforceRec, 0,
                    oldW, x1, y1, newW, newH, oldTotal, false, reinfKept, mapData.coordBase);
            int[] airKept = {0};
            cursor = cropCoordSection(out, oldBtl, cursor, h.airstrikeCount, 20, 0,
                    oldW, x1, y1, newW, newH, oldTotal, false, airKept, mapData.coordBase);
            int[] placeAKept = {0};
            cursor = cropCoordSection(out, oldBtl, cursor, h.placementCountA, 8, 0,
                    oldW, x1, y1, newW, newH, oldTotal, false, placeAKept, mapData.coordBase);
            int[] placeBKept = {0};
            cursor = cropCoordSection(out, oldBtl, cursor, h.placementCountB, 8, 0,
                    oldW, x1, y1, newW, newH, oldTotal, false, placeBKept, mapData.coordBase);
            int[] capKept = {0};
            cursor = cropCoordSection(out, oldBtl, cursor, h.capitalCount, 4, 0,
                    oldW, x1, y1, newW, newH, oldTotal, false, capKept, mapData.coordBase);
            cursor = copySection(out, oldBtl, cursor, h.strategyCount, 16);
            copySection(out, oldBtl, cursor, h.airSupportCount, 16);

            byte[] result = out.toByteArray();
            ByteBuffer bb = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN);
            if (!h.independentTerrain) {
                // 征服地图：截取窗口向右下移动
                bb.putInt(0x08, h.captureX + x1);
                bb.putInt(0x0C, h.captureY + y1);
            }
            bb.putInt(0x10, newW);
            bb.putInt(0x14, newH);
            bb.putInt(0x58, newTotal);
            bb.putInt(0x1C, bCount[0]);
            bb.putInt(0x20, armyKept[0]);
            bb.putInt(0x24, planKept[0]);
            bb.putInt(0x3C, reinfKept[0]);
            bb.putInt(0x40, airKept[0]);
            bb.putInt(0x44, placeAKept[0]);
            bb.putInt(0x48, placeBKept[0]);
            bb.putInt(0x4C, capKept[0]);
            bb.putInt(0x68, mineKept[0]);
            mapData.btlOriginalData = result;
        } else if (mapData.binOriginalData != null) {
            // 纯 BIN 地图：重建简化 BIN（16字节头 + 裁剪后地形）
            byte[] bin = new byte[16 + newW * newH * 16];
            ByteBuffer bb = ByteBuffer.wrap(bin).order(ByteOrder.LITTLE_ENDIAN);
            bb.putInt(8, newW);
            bb.putInt(12, newH);
            for (int i = 0; i < newW * newH; i++) {
                mapData.tiles.get(i).toBytes(bin, 16 + i * 16);
            }
            mapData.binOriginalData = bin;
        }
        refreshArmies(mapData);
        mapData.buildTerrainPatterns();
        return mapData;
    }

    private static int copySection(java.io.ByteArrayOutputStream out, byte[] src, int base,
                                   int count, int recSize) {
        int bytes = count * recSize;
        if (bytes > 0) out.write(src, base, bytes);
        return base + bytes;
    }

    /**
     * 裁剪含坐标的记录段：仅保留落在 [x1,x2]×[y1,y2] 内的记录，坐标重映射到新地图。
     * keepZero 为 true 时坐标 0 视为“无目标/未设置”，原样保留（用于方案段）。
     */
    private static int cropCoordSection(java.io.ByteArrayOutputStream out, byte[] src, int base, int count,
                                        int recSize, int fieldOffset, int oldW, int x1, int y1,
                                        int newW, int newH, int oldTotal, boolean keepZero, int[] kept,
                                        int coordBase) {
        for (int i = 0; i < count; i++) {
            int addr = base + i * recSize;
            if (addr + recSize > src.length) break;
            int coord = (ByteBuffer.wrap(src).order(ByteOrder.LITTLE_ENDIAN)
                    .getShort(addr + fieldOffset) & 0xFFFF) - coordBase;
            if (coord < 0) continue;
            if (keepZero && coord == 0) {
                out.write(src, addr, recSize);
                if (kept != null) kept[0]++;
                continue;
            }
            if (coord >= oldTotal) continue;
            int x = coord % oldW, y = coord / oldW;
            if (x >= x1 && x < x1 + newW && y >= y1 && y < y1 + newH) {
                int nc = (y - y1) * newW + (x - x1);
                byte[] rec = new byte[recSize];
                System.arraycopy(src, addr, rec, 0, recSize);
                int stored = nc + coordBase;
                rec[fieldOffset] = (byte) (stored & 0xFF);
                rec[fieldOffset + 1] = (byte) ((stored >> 8) & 0xFF);
                out.write(rec, 0, recSize);
                if (kept != null) kept[0]++;
            }
        }
        return base + count * recSize;
    }

    /** 裁剪建筑段：区域内保留，坐标重映射；类型代码按内存状态更新。 */
    private static void cropBuildings(java.io.ByteArrayOutputStream out, byte[] src, int base, int count,
                                      int oldW, int x1, int y1, int newW, int newH,
                                      int oldTotal, MapData mapData, int[] outCount) {
        for (int i = 0; i < count; i++) {
            int addr = base + i * 32;
            if (addr + 32 > src.length) break;
            int coord = (ByteBuffer.wrap(src).order(ByteOrder.LITTLE_ENDIAN)
                    .getShort(addr) & 0xFFFF) - mapData.coordBase;
            if (coord < 0) continue;
            if (coord >= oldTotal) continue;
            int x = coord % oldW, y = coord / oldW;
            if (x >= x1 && x < x1 + newW && y >= y1 && y < y1 + newH) {
                byte[] rec = new byte[32];
                System.arraycopy(src, addr, rec, 0, 32);
                int nc = (y - y1) * newW + (x - x1);
                int stored = nc + mapData.coordBase;
                rec[0] = (byte) (stored & 0xFF);
                rec[1] = (byte) ((stored >> 8) & 0xFF);
                int bid = mapData.buildingIds.get((y - y1) * newW + (x - x1));
                if (bid > 0) rec[4] = (byte) bid;
                out.write(rec, 0, 32);
                outCount[0]++;
            }
        }
    }
    // ========= BIN =========

    private static MapData loadBIN(byte[] data, String fileName) throws IOException {
        ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int width = bb.getInt(8), height = bb.getInt(12);
        MapData mapData = new MapData(width, height);
        mapData.fileName = fileName;
        mapData.binOriginalData = data;
        int totalTiles = width * height;
        for (int i = 0; i < totalTiles; i++) {
            int addr = 16 + i * 16;
            if (addr + 16 > data.length) break;
            mapData.tiles.get(i).parseFromBytes(data, addr);
        }
        mapData.buildTerrainPatterns();
        return mapData;
    }

    public static byte[] saveAsBIN(MapData mapData) throws IOException {
        int totalTiles = mapData.width * mapData.height;
        if (mapData.binOriginalData != null) {
            // 世界地形 BIN：保留头 16 字节与地形之后的省规划段，仅原地更新截取区域的地形
            byte[] bin = mapData.binOriginalData.clone();
            ByteBuffer bb = ByteBuffer.wrap(bin).order(ByteOrder.LITTLE_ENDIAN);
            int binW = bb.getInt(8), binH = bb.getInt(12);
            if (binW <= 0 || binH <= 0 || 16 + binW * binH * 16 > bin.length) {
                throw new IOException("BIN 地形数据不完整");
            }
            int cropX = 0, cropY = 0;
            if (mapData.btlOriginalData != null) {
                BtlHeaderInfo h = parseBTLHeader(mapData.btlOriginalData);
                cropX = h.captureX;
                cropY = h.captureY;
            }
            if (cropX < 0 || cropY < 0 || cropX + mapData.width > binW || cropY + mapData.height > binH) {
                throw new IOException("地图截取区域超出 BIN 范围");
            }
            for (int y = 0; y < mapData.height; y++) {
                for (int x = 0; x < mapData.width; x++) {
                    int dst = 16 + ((y + cropY) * binW + (x + cropX)) * 16;
                    mapData.tiles.get(y * mapData.width + x).toBytes(bin, dst);
                }
            }
            return bin;
        }
        int totalSize = 16 + totalTiles * 16;
        byte[] result = new byte[totalSize];
        ByteBuffer bb = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(0, 0);
        bb.putInt(4, 0);
        bb.putInt(8, mapData.width);
        bb.putInt(12, mapData.height);
        for (int i = 0; i < totalTiles; i++) {
            mapData.tiles.get(i).toBytes(result, 16 + i * 16);
        }
        return result;
    }

    /**
     * 征服地图：从 world BIN 中按 BTL 的截取区域（0x08/0x0C）读取地形填入 mapData。
     * 地图格子 (bx,by) 对应 BIN 格子 (bx+截取X, by+截取Y)。
     */
    public static void loadConquestTerrain(MapData mapData, byte[] binData) throws IOException {
        if (mapData == null || mapData.btlOriginalData == null) throw new IOException("缺少 BTL 数据");
        BtlHeaderInfo h = parseBTLHeader(mapData.btlOriginalData);
        if (h.independentTerrain) throw new IOException("该 BTL 自带地形，无需 BIN");
        if (binData == null || binData.length < 16) throw new IOException("BIN 文件无效");
        ByteBuffer bb = ByteBuffer.wrap(binData).order(ByteOrder.LITTLE_ENDIAN);
        int binW = bb.getInt(8), binH = bb.getInt(12);
        if (binW <= 0 || binH <= 0 || 16 + binW * binH * 16 > binData.length) {
            throw new IOException("BIN 地形数据不完整");
        }
        if (h.captureX < 0 || h.captureY < 0
                || h.captureX + h.width > binW || h.captureY + h.height > binH) {
            throw new IOException("BTL 截取区域超出 BIN 范围");
        }
        for (int y = 0; y < h.height; y++) {
            for (int x = 0; x < h.width; x++) {
                int src = 16 + ((y + h.captureY) * binW + (x + h.captureX)) * 16;
                mapData.tiles.get(y * h.width + x).parseFromBytes(binData, src);
            }
        }
        mapData.buildTerrainPatterns();
    }
}
