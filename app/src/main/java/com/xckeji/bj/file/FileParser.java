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
            return result;
        } else {
            // 新建（随机生成）：生成游戏可用的BTL
            // 完全参照游戏空白模板文件的二进制布局
            int headerSize = 128;
            int legionCount = 2;  // 参考原始7x4地图也用的2
            int legionDataSize = legionCount * 300;
            int terrainStart = headerSize + legionDataSize;
            int terrainSize = totalTiles * 16;
            int adminSize = totalTiles * 2;
            int ownershipSize = totalTiles * 1;
            int totalSize = terrainStart + terrainSize + adminSize + ownershipSize;

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
            bb.putInt(0x1C, 0);           // buildingCount
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

            // ===== 军团数据段（全部填0）=====
            // 地形数据起始 = 128 + 2*300 = 728
            // 军团数据段全0，游戏不加载空军团时不会崩溃

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

            return result;
        }
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
