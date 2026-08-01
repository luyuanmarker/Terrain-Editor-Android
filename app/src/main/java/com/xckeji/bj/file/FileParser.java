package com.xckeji.bj.file;

import com.xckeji.bj.model.MapData;
import com.xckeji.bj.model.TerrainTile;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

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
        public int terrainStart;
        public int buildingStart;
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
        h.mineCount = bb.getInt(0x68);
        h.strategyCount = bb.getInt(0x70);
        h.airSupportCount = bb.getInt(0x7C);
        h.terrainStart = 128 + h.legionCount * 300;
        int totalTiles = h.width * h.height;
        int adminStart = h.terrainStart + totalTiles * 16;
        int ownershipStart = adminStart + totalTiles * 2;
        h.buildingStart = ownershipStart + totalTiles * 1;
        return h;
    }

    private static MapData loadBTL(byte[] data, String fileName) throws IOException {
        BtlHeaderInfo header = parseBTLHeader(data);
        MapData mapData = new MapData(header.width, header.height);
        mapData.fileName = fileName;
        mapData.btlOriginalData = data;

        int totalTiles = header.width * header.height;
        for (int i = 0; i < totalTiles; i++) {
            mapData.tiles.get(i).parseFromBytes(data, header.terrainStart + i * 16);
        }

        if (header.buildingCount > 0) {
            for (int i = 0; i < header.buildingCount; i++) {
                int addr = header.buildingStart + i * 32;
                if (addr + 32 > data.length) break;
                int coord = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getShort(addr) & 0xFFFF;
                int bx = coord % header.width;
                int by = coord / header.width;
                int type = data[addr + 4] & 0xFF;
                if (bx < header.width && by < header.height) mapData.setBuildingId(bx, by, type);
            }
        }
        return mapData;
    }

    public static byte[] saveAsBTL(MapData mapData) throws IOException {
        int totalTiles = mapData.width * mapData.height;
        if (mapData.width < 1 || mapData.height < 1 || totalTiles > 65535) {
            throw new IOException("BTL 地图尺寸无效（地块坐标最大支持 65535）");
        }

        if (mapData.btlOriginalData != null) {
            // 有原始BTL：基于原始数据修改（支持扩展后的文件）
            BtlHeaderInfo h = parseBTLHeader(mapData.btlOriginalData);
            byte[] result = mapData.btlOriginalData.clone();
            ByteBuffer bb = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN);
            bb.putInt(0x10, mapData.width);
            bb.putInt(0x14, mapData.height);

            int oldTotalTiles = h.width * h.height;
            int newTotalTiles = mapData.width * mapData.height;
            int terrainStart = h.terrainStart;

            // 如果格子数没变（普通编辑），直接原地写入
            if (oldTotalTiles == newTotalTiles) {
                for (int i = 0; i < totalTiles; i++) {
                    mapData.tiles.get(i).toBytes(result, terrainStart + i * 16);
                }
            } else {
                // 格子数变了（扩展后），需要重建各段
                int adminStart = terrainStart + newTotalTiles * 16;
                int ownershipStart = adminStart + newTotalTiles * 2;
                int buildingStart = ownershipStart + newTotalTiles * 1;

                // 从扩展后文件中的 oldBtl 已包含正确扩展的数据，直接更新地形
                // 文件大小可能已经变了，确保 result 长度正确
                if (result.length < buildingStart) {
                    // 文件太短，重建
                    byte[] newBtl = new byte[buildingStart];
                    System.arraycopy(result, 0, newBtl, 0, Math.min(result.length, terrainStart));
                    result = newBtl;
                }
                for (int i = 0; i < totalTiles; i++) {
                    mapData.tiles.get(i).toBytes(result, terrainStart + i * 16);
                }
            }
            // 对已有 BTL 不重建其后的业务段。它们包含不同版本游戏的私有字段，
            // 原样保留才是游戏兼容的最安全方式。
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

    // ========= BIN =========

    private static MapData loadBIN(byte[] data, String fileName) throws IOException {
        ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int width = bb.getInt(8), height = bb.getInt(12);
        MapData mapData = new MapData(width, height);
        mapData.fileName = fileName;
        int totalTiles = width * height;
        for (int i = 0; i < totalTiles; i++) {
            int addr = 16 + i * 16;
            if (addr + 16 > data.length) break;
            mapData.tiles.get(i).parseFromBytes(data, addr);
        }
        return mapData;
    }

    public static byte[] saveAsBIN(MapData mapData) throws IOException {
        int totalTiles = mapData.width * mapData.height;
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
}
