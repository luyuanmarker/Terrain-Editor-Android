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
        if (binDims(data) != null) return loadBIN(data, fileName);
        throw new IOException("无法识别的文件格式");
    }

    /**
     * 解析世界地形 BIN 的尺寸与头部长度：优先 YSAE 魔数（宽高在 0x8/0xC，16 字节头），
     * 兼容裸格式（宽高在 0x0/0x4，8 字节头）。与 HTML 版转换器的加载规则一致。
     * 返回 {宽度, 高度, 头部长度}；无法识别时返回 null。
     */
    private static int[] binDims(byte[] data) {
        if (data == null || data.length < 16) return null;
        ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        boolean ySAE = data[0] == 'Y' && data[1] == 'S' && data[2] == 'A' && data[3] == 'E';
        if (ySAE) {
            int w = bb.getInt(8), h = bb.getInt(12);
            if (w > 0 && w <= 2000 && h > 0 && h <= 2000) return new int[]{w, h, 16};
            return null;
        }
        int w0 = bb.getInt(0), h0 = bb.getInt(4);
        if (w0 > 0 && w0 <= 2000 && h0 > 0 && h0 <= 2000) return new int[]{w0, h0, 8};
        return null;
    }

    // ========= BTL =========

    public static class BtlHeaderInfo {
        public int version, mapId, captureX, captureY;
        public int width, height, legionCount, buildingCount, armyCount;
        public int planCount, eventCount, weatherCount;
        public int reinforceCount, airstrikeCount, mineCount, strategyCount, airSupportCount;
        public int placementCountA, placementCountB, capitalCount;
        public int terrainStart;
        public int adminStart;
        public int ownershipStart;
        public int buildingStart;
        /**
         * 省规划/归属段的格数（官方 rule_wc4_btl.xml 里 bm3/bm4 的 Count = 地块总数 bm0_23）。
         * 真实游戏文件里它常常比 宽×高 多几格（历史扩展残留），
         * 段偏移必须按这个值算，否则建筑/兵种/尾段全部错位、保存出来的文件进游戏会崩。
         */
        public int sectionTiles;
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
        // 官方 BTLTooL：地形段数量 = 宽×高(sumGride)；省规划/归属段数量 = 地块总数(bm0_23)。
        int declaredTiles = bb.getInt(0x58);
        h.sectionTiles = (declaredTiles >= totalTiles && declaredTiles <= totalTiles + 4096)
                ? declaredTiles : totalTiles;
        // 征服地图（地图序号!=0）地形不在 BTL 中，直接是省规划段
        int terrainBytes = h.independentTerrain ? totalTiles * 16 : 0;
        h.adminStart = h.terrainStart + terrainBytes;
        h.ownershipStart = h.adminStart + h.sectionTiles * 2;
        h.buildingStart = h.ownershipStart + h.sectionTiles * 1;
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

    /** 陷阱段起始偏移：建筑 → 兵种 → 陷阱（12字节/条）。 */
    public static int mineStart(BtlHeaderInfo h) {
        return h.buildingStart + h.buildingCount * 32
                + h.armyCount * armyRecSize(h.version);
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

    /** 援军记录大小：版本1=80字节，版本2/3=104字节（与熊编辑器/游戏内实际文件一致）。 */
    private static int reinforceRecSize(int version) {
        return version == 1 ? 80 : 104;
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
        int adminStart = h.adminStart;
        int scan = Math.min(total, h.sectionTiles);
        for (int i = 0; i < scan; i++) {
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
        int adminStart = header.adminStart;
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
        // 记录载入值，保存时未改动过的格子直接写回原始字节（避免坐标换算丢信息）
        mapData.provincesAtLoad = mapData.provinces.clone();
        int ownershipStart = header.ownershipStart;
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
                if (a.raw.length > 17) a.general = (a.raw[16] & 0xFF) | ((a.raw[17] & 0xFF) << 8);
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
        parseTraps(mapData, data, header);
        computeTailStarts(mapData, header);
    }

    /** 尾段起始偏移：建筑 → 兵种 → 陷阱 → 方案 → 天气 → 事件 → 援军 → 空袭 → 放置甲/乙 → 战略 → 空中支援 → 首都。 */
    public static void computeTailStarts(MapData mapData, BtlHeaderInfo h) {
        int cursor = h.buildingStart + h.buildingCount * 32
                + h.armyCount * armyRecSize(h.version)
                + h.mineCount * 12;
        mapData.planStart = cursor;
        cursor += h.planCount * 16;
        mapData.weatherStart = cursor;
        cursor += h.weatherCount * 16;
        mapData.eventStart = cursor;
        cursor += h.eventCount * 44;
        mapData.reinforceStart = cursor;
        cursor += h.reinforceCount * (h.version == 1 ? 80 : 104);
        mapData.airstrikeStart = cursor;
        cursor += h.airstrikeCount * 20;
        mapData.placementAStart = cursor;
        cursor += h.placementCountA * 8;
        mapData.placementBStart = cursor;
        cursor += h.placementCountB * 8;
        mapData.strategyStart = cursor;
        cursor += h.strategyCount * 16;
        mapData.airSupportStart = cursor;
        cursor += h.airSupportCount * 16;
        mapData.capitalStart = cursor;
    }

    private static int le16(byte[] d, int o) {
        return (d[o] & 0xFF) | ((d[o + 1] & 0xFF) << 8);
    }

    private static int le32(byte[] d, int o) {
        return (d[o] & 0xFF) | ((d[o + 1] & 0xFF) << 8)
                | ((d[o + 2] & 0xFF) << 16) | ((d[o + 3] & 0xFF) << 24);
    }

    /** 校验并修复（规则与枭雄一致）：将领=0 时军衔/HP等级/技能/胸章/勋章须为 0；将领!=0 时军衔/HP等级不能为 0；
     *  编制 1-4、方向 0/1；援军同规则。返回问题列表（已就地修复）。 */
    public static java.util.List<String> validateAndFix(MapData mapData) {
        java.util.List<String> issues = new java.util.ArrayList<>();
        if (mapData == null || mapData.btlOriginalData == null) return issues;
        byte[] data = mapData.btlOriginalData;
        BtlHeaderInfo h = parseBTLHeader(data);
        int rec = armyRecSize(h.version);
        int armyStart = h.buildingStart + h.buildingCount * 32;
        for (int i = 0; i < h.armyCount; i++) {
            int addr = armyStart + i * rec;
            if (addr + rec > data.length) break;
            int coord = le16(data, addr) - mapData.coordBase;
            int type = data[addr + 2] & 0xFF;
            int comp = data[addr + 4] & 0xFF;
            int dir = data[addr + 5] & 0xFF;
            int general = le16(data, addr + 16);
            boolean fixed = false;
            String tag = "兵种#" + (i + 1) + "(" + coord + ") ";
            if (type != 0) {
                if (comp < 1 || comp > 4) { issues.add(tag + "编制=" + comp + " 需 1-4"); data[addr + 4] = 1; fixed = true; }
                if (dir > 1) { issues.add(tag + "方向=" + dir + " 需 0/1"); data[addr + 5] = 0; fixed = true; }
            } else if ((data[addr + 3] & 0xFF) != 0) {
                issues.add(tag + "兵种为0 等级应为0"); data[addr + 3] = 0; fixed = true;
            }
            if (general == 0) {
                if ((data[addr + 18] & 0xFF) != 0) { issues.add(tag + "将领为0 军衔应0"); data[addr + 18] = 0; fixed = true; }
                if ((data[addr + 19] & 0xFF) != 0) { issues.add(tag + "将领为0 HP等级应0"); data[addr + 19] = 0; fixed = true; }
                for (int f = 0x14; f <= 0x1B && f + 1 <= rec; f++) {
                    if ((data[addr + f] & 0xFF) != 0) { issues.add(tag + "将领为0 技能/胸章应0 @" + Integer.toHexString(f)); data[addr + f] = 0; fixed = true; }
                }
                for (int f = 0x30; f <= 0x35 && f + 1 <= rec; f++) {
                    if ((data[addr + f] & 0xFF) != 0) { issues.add(tag + "将领为0 勋章/勋带应0 @" + Integer.toHexString(f)); data[addr + f] = 0; fixed = true; }
                }
            } else {
                if ((data[addr + 18] & 0xFF) == 0) { issues.add(tag + "将领" + general + " 军衔不能为0"); data[addr + 18] = 1; fixed = true; }
                if ((data[addr + 19] & 0xFF) == 0) { issues.add(tag + "将领" + general + " HP等级不能为0"); data[addr + 19] = 1; fixed = true; }
            }
            if (fixed) {
                for (MapData.Army a : mapData.armies) {
                    if (a.index == i && a.raw != null && a.raw.length >= rec) {
                        System.arraycopy(data, addr, a.raw, 0, rec);
                    }
                }
            }
        }
        computeTailStarts(mapData, h);
        int reinfStart = mapData.reinforceStart;
        int rsize = h.version == 1 ? 80 : 104;
        for (int i = 0; i < h.reinforceCount; i++) {
            int addr = reinfStart + i * rsize;
            if (addr + rsize > data.length) break;
            int general = le32(data, addr + 28);
            String tag = "援军#" + (i + 1) + "(" + le16(data, addr) + ") ";
            boolean fixed = false;
            if (general == 0) {
                if (le32(data, addr + 32) != 0) { issues.add(tag + "将领为0 军衔应0"); put32(data, addr + 32, 0); fixed = true; }
                if (le32(data, addr + 36) != 0) { issues.add(tag + "将领为0 HP等级应0"); put32(data, addr + 36, 0); fixed = true; }
                for (int f = 40; f <= 72 && f + 4 <= rsize; f += 4) {
                    if (le32(data, addr + f) != 0) { issues.add(tag + "将领为0 技能/胸章应0 @" + Integer.toHexString(f)); put32(data, addr + f, 0); fixed = true; }
                }
                if (rsize >= 104) {
                    for (int f = 80; f <= 100 && f + 4 <= rsize; f += 4) {
                        if (le32(data, addr + f) != 0) { issues.add(tag + "将领为0 勋章/勋带应0 @" + Integer.toHexString(f)); put32(data, addr + f, 0); fixed = true; }
                    }
                }
            } else {
                if (le32(data, addr + 32) == 0) { issues.add(tag + "将领" + general + " 军衔不能为0"); put32(data, addr + 32, 1); fixed = true; }
                if (le32(data, addr + 36) == 0) { issues.add(tag + "将领" + general + " HP等级不能为0"); put32(data, addr + 36, 1); fixed = true; }
            }
            if (fixed) { /* 援军无内存副本，直接改原始数据即可 */ }
        }
        // 海洋区划：地形组=1（海洋）的格子，行政区划必须为 0xFFFF（官方规则）
        int oceanFixed = fixOceanDistricts(mapData);
        if (oceanFixed > 0) {
            issues.add("海洋区划修复：" + oceanFixed + " 个海洋地块（地形组=1）的行政区划已强制为 65535");
        }
        // 特殊胜利条件：v1 / v2 必须为 1
        if (h.version <= 2) {
            int sv = le32(data, 0x54);
            if (sv != 1) {
                issues.add("特殊胜利条件：v" + h.version + " 应为 1，当前 " + sv + "，已修正");
                put32(data, 0x54, 1);
            }
        }
        return issues;
    }

    private static void put32(byte[] d, int o, int v) {
        d[o] = (byte) (v & 0xFF);
        d[o + 1] = (byte) ((v >> 8) & 0xFF);
        d[o + 2] = (byte) ((v >> 16) & 0xFF);
        d[o + 3] = (byte) ((v >> 24) & 0xFF);
    }

    /**
     * 生成官方 world.bin（仅地图序号=0 的战役 BTL）：
     * 8 字节魔数 59 53 41 45 04 00 00 00 + uint32 宽 + uint32 高 + 每格 16 字节地形 + 每格 2 字节行政区划。
     * 海洋格（地形组=1）的行政区划按官方规则强制写 0xFFFF。
     */
    public static byte[] generateWorldBin(MapData mapData) throws IOException {
        if (mapData == null || mapData.btlOriginalData == null) {
            throw new IOException("请先加载战役 BTL（地图序号=0）");
        }
        BtlHeaderInfo h = parseBTLHeader(mapData.btlOriginalData);
        if (h.mapId != 0) {
            throw new IOException("仅地图序号=0 的 BTL 支持生成 world.bin（征服地图请用「保存」输出底图）");
        }
        int w = mapData.width, ht = mapData.height;
        if (w <= 0 || ht <= 0) throw new IOException("地图长宽无效");
        int total = w * ht;
        byte[] out = new byte[16 + total * 16 + total * 2];
        out[0] = 89; out[1] = 83; out[2] = 65; out[3] = 69; // "YSAE"
        out[4] = 4;
        put32(out, 8, w);
        put32(out, 12, ht);
        for (int i = 0; i < total; i++) {
            TerrainTile t = mapData.tiles.get(i);
            if (t != null) t.toBytes(out, 16 + i * 16);
            int pv = (mapData.provinces != null && i < mapData.provinces.length)
                    ? mapData.provinces[i] : 0xFFFF;
            if (t != null && t.bmTerrain1Group == 1) pv = 0xFFFF;   // 海洋区划规则
            if (pv != 0xFFFF && (pv < 0 || pv >= total)) pv = 0xFFFF;
            int off = 16 + total * 16 + i * 2;
            out[off] = (byte) (pv & 0xFF);
            out[off + 1] = (byte) ((pv >>> 8) & 0xFF);
        }
        return out;
    }

    /**
     * 把“海洋地块的行政区划/省规划”统一修正为 0xFFFF（无省份）。
     *
     * 依据（1.17.2 游戏自带文件实测）：
     * - world.bin：3670/3670 个海洋格区划都是 65535；world2.bin：7681/7683；
     * - 官方 MapEdit（Wc4MapBinDAO.checkMapTerrainIds）读地图与编辑后会强制这一条，
     *   熊编辑器也有同样的校验/一键修复；
     * - 少数旧战役文件（event/frontier 系列）确实存在海洋格带省份值的情况，游戏能容忍，
     *   所以这是“与官方编辑器一致的标准清理”，不是游戏崩溃的硬性原因。
     *
     * 同时修正内存数组与原始字节（BTL 省规划段 + 世界底图 BIN 的省规划段），
     * 返回被修正的格数。
     */
    public static int normalizeWaterDistricts(MapData mapData) {
        if (mapData == null || mapData.tiles == null || mapData.tiles.isEmpty()) return 0;
        // 征服 BTL 自带地形为空，内存里的地形只是「未加载底图」的占位海洋，
        // 这时按地形去改省规划会把整张图的省规划清成 65535，必须跳过。
        if (mapData.btlOriginalData != null && mapData.binOriginalData == null) {
            try {
                if (!parseBTLHeader(mapData.btlOriginalData).independentTerrain) return 0;
            } catch (Exception ignored) {
            }
        }
        int total = mapData.tiles.size();
        java.util.TreeSet<Integer> fixedCells = new java.util.TreeSet<>();

        // 1) 内存省规划数组（数据面板显示、保存 btl 都用它）
        if (mapData.provinces != null) {
            int n = Math.min(total, mapData.provinces.length);
            for (int i = 0; i < n; i++) {
                TerrainTile t = mapData.tiles.get(i);
                if (t == null || t.bmTerrain1Group != 1) continue;
                if (mapData.provinces[i] == 0xFFFF) continue;
                mapData.provinces[i] = 0xFFFF;
                fixedCells.add(i);
            }
        }

        // 2) BTL 省规划段（战役自带地形；征服就是截取窗口的省规划）
        if (mapData.btlOriginalData != null) {
            try {
                BtlHeaderInfo h = parseBTLHeader(mapData.btlOriginalData);
                int n = Math.min(total, h.width * h.height);
                int adminStart = h.adminStart;
                for (int i = 0; i < n; i++) {
                    TerrainTile t = mapData.tiles.get(i);
                    if (t == null || t.bmTerrain1Group != 1) continue;
                    int off = adminStart + i * 2;
                    if (off + 1 >= mapData.btlOriginalData.length) break;
                    if ((mapData.btlOriginalData[off] & 0xFF) == 0xFF
                            && (mapData.btlOriginalData[off + 1] & 0xFF) == 0xFF) continue;
                    mapData.btlOriginalData[off] = (byte) 0xFF;
                    mapData.btlOriginalData[off + 1] = (byte) 0xFF;
                    fixedCells.add(i);
                }
            } catch (Exception ignored) {
            }
        }

        // 3) 世界底图 BIN 的省规划段（换算方式与 saveAsBIN 一致）
        if (mapData.binOriginalData != null) {
            try {
                int[] dims = binDims(mapData.binOriginalData);
                if (dims != null) {
                    int binW = dims[0], binH = dims[1], headerSize = dims[2];
                    int regionStart = headerSize + binW * binH * 16;
                    int cropX = 0, cropY = 0;
                    if (mapData.btlOriginalData != null) {
                        BtlHeaderInfo h = parseBTLHeader(mapData.btlOriginalData);
                        if (mapData.width == h.width && mapData.height == h.height) {
                            cropX = h.captureX;
                            cropY = h.captureY;
                        }
                    }
                    for (int y = 0; y < mapData.height; y++) {
                        for (int x = 0; x < mapData.width; x++) {
                            int i = y * mapData.width + x;
                            if (i >= total) break;
                            TerrainTile t = mapData.tiles.get(i);
                            if (t == null || t.bmTerrain1Group != 1) continue;
                            int reg = (y + cropY) * binW + (x + cropX);
                            int off = regionStart + reg * 2;
                            if (off + 1 >= mapData.binOriginalData.length) continue;
                            if ((mapData.binOriginalData[off] & 0xFF) == 0xFF
                                    && (mapData.binOriginalData[off + 1] & 0xFF) == 0xFF) continue;
                            mapData.binOriginalData[off] = (byte) 0xFF;
                            mapData.binOriginalData[off + 1] = (byte) 0xFF;
                            fixedCells.add(i);
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return fixedCells.size();
    }

    /** 海洋区划自动修正（等价于 {@link #normalizeWaterDistricts}，保留旧入口）。 */
    public static int fixOceanDistricts(MapData mapData) {
        return normalizeWaterDistricts(mapData);
    }

    /** 通用尾段记录写回：把 raw 覆盖到 btlOriginalData 对应位置（坐标字段如需重映射请自行处理）。 */
    public static void patchTailRecord(MapData mapData, int start, int index, int size, byte[] raw) throws IOException {
        if (mapData == null || mapData.btlOriginalData == null || raw == null || raw.length != size) {
            throw new IOException("尾段数据无效");
        }
        int addr = start + index * size;
        if (addr < 0 || addr + size > mapData.btlOriginalData.length) {
            throw new IOException("尾段越界");
        }
        System.arraycopy(raw, 0, mapData.btlOriginalData, addr, size);
    }

    /** 解析陷阱段（12 字节/条：0x0 坐标、0x2 军团、0x4 等级、0x6 血量、0x8 保留）。 */
    private static void parseTraps(MapData mapData, byte[] data, BtlHeaderInfo header) {
        mapData.traps.clear();
        int start = mineStart(header);
        for (int i = 0; i < header.mineCount; i++) {
            int addr = start + i * 12;
            if (addr + 12 > data.length) break;
            ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            int coord = (bb.getShort(addr) & 0xFFFF) - mapData.coordBase;
            if (coord < 0) continue;
            int mx = coord % header.width;
            int my = coord / header.width;
            if (mx >= header.width || my >= header.height) continue;
            MapData.Trap t = new MapData.Trap();
            t.index = i;
            t.coord = coord;
            t.x = mx;
            t.y = my;
            t.legion = bb.getShort(addr + 2) & 0xFFFF;
            t.level = bb.getShort(addr + 4) & 0xFFFF;
            t.hp = bb.getShort(addr + 6) & 0xFFFF;
            System.arraycopy(data, addr, t.raw, 0, 12);
            mapData.traps.add(t);
        }
    }

    /** 从当前 BTL 重新解析陷阱列表（添加/删除/裁剪后调用）。 */
    public static void refreshTraps(MapData mapData) {
        if (mapData == null || mapData.btlOriginalData == null) return;
        try {
            parseTraps(mapData, mapData.btlOriginalData,
                    parseBTLHeader(mapData.btlOriginalData));
        } catch (Exception ignored) {
        }
    }

    /** 把编辑后的 12 字节地雷记录写回 BTL 陷阱段对应偏移。 */
    public static void patchTrap(MapData mapData, MapData.Trap t, byte[] raw12)
            throws IOException {
        if (mapData == null || mapData.btlOriginalData == null || t == null
                || raw12 == null || raw12.length < 12) {
            throw new IOException("地雷数据无效");
        }
        BtlHeaderInfo h = parseBTLHeader(mapData.btlOriginalData);
        int start = mineStart(h);
        int addr = start + t.index * 12;
        int end = start + h.mineCount * 12;
        if (t.index < 0 || addr + 12 > end || end > mapData.btlOriginalData.length) {
            throw new IOException("地雷记录越界");
        }
        System.arraycopy(raw12, 0, mapData.btlOriginalData, addr, 12);
    }

    /** 新增地雷：在陷阱段末尾插入一条记录（等级默认 1，血量 = 60×等级）。 */
    public static MapData.Trap addMine(MapData mapData, int x, int y, int legion)
            throws IOException {
        if (mapData == null || mapData.btlOriginalData == null) {
            throw new IOException("请先加载 BTL 地图");
        }
        if (x < 0 || y < 0 || x >= mapData.width || y >= mapData.height) {
            throw new IOException("地块坐标越界");
        }
        byte[] oldBtl = mapData.btlOriginalData;
        BtlHeaderInfo h = parseBTLHeader(oldBtl);
        int start = mineStart(h);
        int mineBytes = h.mineCount * 12;
        if (start + mineBytes > oldBtl.length) throw new IOException("地雷段越界");
        int level = 1;
        byte[] rec = new byte[12];
        int stored = (y * mapData.width + x) + mapData.coordBase;
        rec[0] = (byte) (stored & 0xFF);
        rec[1] = (byte) ((stored >>> 8) & 0xFF);
        rec[2] = (byte) (legion & 0xFF);
        rec[4] = (byte) (level & 0xFF);
        rec[6] = (byte) ((level * 60) & 0xFF);
        rec[7] = (byte) (((level * 60) >>> 8) & 0xFF);
        byte[] result = new byte[oldBtl.length + 12];
        System.arraycopy(oldBtl, 0, result, 0, start + mineBytes);
        System.arraycopy(rec, 0, result, start + mineBytes, 12);
        int rest = oldBtl.length - (start + mineBytes);
        if (rest > 0) {
            System.arraycopy(oldBtl, start + mineBytes, result,
                    start + mineBytes + 12, rest);
        }
        ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(0x68, h.mineCount + 1);
        mapData.btlOriginalData = result;
        refreshTraps(mapData);
        return mapData.traps.isEmpty() ? null : mapData.traps.get(mapData.traps.size() - 1);
    }

    /** 删除地雷：移除陷阱段中的一条记录，后续段整体前移。 */
    public static void removeMine(MapData mapData, MapData.Trap t) throws IOException {
        if (mapData == null || mapData.btlOriginalData == null || t == null) {
            throw new IOException("地雷数据无效");
        }
        byte[] oldBtl = mapData.btlOriginalData;
        BtlHeaderInfo h = parseBTLHeader(oldBtl);
        int start = mineStart(h);
        int addr = start + t.index * 12;
        int end = start + h.mineCount * 12;
        if (t.index < 0 || addr + 12 > end || end > oldBtl.length) {
            throw new IOException("地雷记录越界");
        }
        byte[] result = new byte[oldBtl.length - 12];
        System.arraycopy(oldBtl, 0, result, 0, addr);
        int rest = oldBtl.length - (addr + 12);
        if (rest > 0) {
            System.arraycopy(oldBtl, addr + 12, result, addr, rest);
        }
        ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(0x68, h.mineCount - 1);
        mapData.btlOriginalData = result;
        refreshTraps(mapData);
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
        int ownershipStart = h.ownershipStart;
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

    /** 把内存省区值写回 BTL 省规划段对应偏移（编辑省区后立即生效，供后续扩展/裁剪使用）。 */
    public static void patchProvince(MapData mapData, int index) {
        if (mapData == null || mapData.btlOriginalData == null || mapData.provinces == null) return;
        if (index < 0 || index >= mapData.provinces.length) return;
        try {
            BtlHeaderInfo h = parseBTLHeader(mapData.btlOriginalData);
            int addr = h.adminStart + index * 2;
            if (addr + 2 > mapData.btlOriginalData.length) return;
            int pv = mapData.provinces[index];
            int stored = (pv == 0 || pv == 0xFFFF) ? pv : pv + mapData.coordBase;
            mapData.btlOriginalData[addr] = (byte) (stored & 0xFF);
            mapData.btlOriginalData[addr + 1] = (byte) ((stored >> 8) & 0xFF);
            if (mapData.provincesAtLoad != null && index < mapData.provincesAtLoad.length) {
                mapData.provincesAtLoad[index] = pv;
            }
        } catch (Exception ignored) {
        }
    }

    /** 把内存军团归属写回 BTL 归属段对应偏移（给城市/地块设置国家归属后立即生效）。 */
    public static void patchBelong(MapData mapData, int index) {
        if (mapData == null || mapData.btlOriginalData == null || mapData.belongs == null) return;
        if (index < 0 || index >= mapData.belongs.length) return;
        try {
            BtlHeaderInfo h = parseBTLHeader(mapData.btlOriginalData);
            int addr = h.ownershipStart + index;
            if (addr < 0 || addr >= mapData.btlOriginalData.length) return;
            mapData.btlOriginalData[addr] = mapData.belongs[index];
        } catch (Exception ignored) {
        }
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
        // 保存前静默修正：海洋地块的省规划必须是 0xFFFF（游戏要求，缺了会读档闪退）。
        normalizeWaterDistricts(mapData);

        if (mapData.btlOriginalData != null) {
            // 有原始BTL：基于原始数据修改（支持扩展后的文件）
            BtlHeaderInfo h = parseBTLHeader(mapData.btlOriginalData);
            byte[] oldBtl = mapData.btlOriginalData;
            int oldTotalTiles = h.width * h.height;
            int newTotalTiles = mapData.width * mapData.height;
            int terrainStart = h.terrainStart;
            boolean resized = (newTotalTiles != oldTotalTiles);

            // 征服地图（地图序号!=0）地形不在 BTL 中，各段偏移不含地形
            int oldTerrainBytes = h.independentTerrain ? oldTotalTiles * 16 : 0;
            int newTerrainBytes = h.independentTerrain ? newTotalTiles * 16 : 0;

            // 官方约定：地形段 = 宽×高 格；省规划/归属段 = 地块总数(bm0_23) 格。
            // 未改尺寸时沿用原文件的地块总数，改尺寸（扩展/裁剪）时收缩为新的宽×高。
            int oldSectionTiles = h.sectionTiles;
            int newSectionTiles = resized ? newTotalTiles : oldSectionTiles;

            // 旧文件各段偏移
            int oldAdminStart = h.adminStart;
            int oldOwnershipStart = h.ownershipStart;
            int oldBuildingStart = h.buildingStart;
            int oldBuildingBytes = h.buildingCount * 32;
            int oldAfterBuildings = oldBtl.length - (oldBuildingStart + oldBuildingBytes);
            if (oldAfterBuildings < 0) oldAfterBuildings = 0;

            // 新文件各段偏移
            int newAdminStart = terrainStart + newTerrainBytes;
            int newOwnershipStart = newAdminStart + newSectionTiles * 2;
            int newBuildingStart = newOwnershipStart + newSectionTiles;

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

            // 3. 省规划（2字节/格）：从内存省区数组写回（值=省区代表格坐标，加坐标基准）。
            //    未改动过的格子（含超出宽×高的额外条目）直接写回原始字节——
            //    征服文件省规划存的是世界坐标，重新编码会把小于坐标基准的旧值写成 0。
            int[] loadedProv = mapData.provincesAtLoad;
            for (int i = 0; i < newSectionTiles; i++) {
                int addr = newAdminStart + i * 2;
                int pv = (i < newTotalTiles && mapData.provinces != null && i < mapData.provinces.length)
                        ? mapData.provinces[i] : 0xFFFF;
                if (!resized && oldAdminStart + i * 2 + 1 < oldBtl.length) {
                    boolean untouched = (i >= newTotalTiles)
                            || (loadedProv != null && i < loadedProv.length && pv == loadedProv[i]);
                    if (untouched) {
                        result[addr] = oldBtl[oldAdminStart + i * 2];
                        result[addr + 1] = oldBtl[oldAdminStart + i * 2 + 1];
                        continue;
                    }
                }
                int stored = (pv == 0 || pv == 0xFFFF) ? pv : pv + mapData.coordBase;
                result[addr] = (byte) (stored & 0xFF);
                result[addr + 1] = (byte) ((stored >> 8) & 0xFF);
            }

            // 4. 军团归属（1字节/格）：内存数组就是原始字节，超出宽×高的额外条目原样保留
            for (int i = 0; i < newSectionTiles; i++) {
                if (i < newTotalTiles && mapData.belongs != null && i < mapData.belongs.length) {
                    result[newOwnershipStart + i] = mapData.belongs[i];
                } else if (!resized && oldOwnershipStart + i < oldBtl.length) {
                    result[newOwnershipStart + i] = oldBtl[oldOwnershipStart + i];
                } else {
                    result[newOwnershipStart + i] = (byte) 0xFF;
                }
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
            bb.putInt(0x58, newSectionTiles);
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
                // 官方约定：海洋(组1) → 0xFFFF；陆地 → 0（无省区）
                if (tile.bmTerrain1Group == 1) {
                    result[adminStart + i * 2] = (byte) 0xFF;
                    result[adminStart + i * 2 + 1] = (byte) 0xFF;
                } else {
                    result[adminStart + i * 2] = 0;
                    result[adminStart + i * 2 + 1] = 0;
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
            byte[] draft = mapData.newBuildingRaws != null
                    ? mapData.newBuildingRaws.get(i) : null;
            if (draft != null && draft.length >= 32) {
                System.arraycopy(draft, 0, records, offset, 32);
            } else {
                records[offset] = (byte) (i & 0xFF);
                records[offset + 1] = (byte) ((i >>> 8) & 0xFF);
            }
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
        // 第一遍统计数量（判定必须与第二遍完全一致，否则会写越界）
        java.util.Map<Integer, Integer> oldRecordByTile = new java.util.HashMap<>();
        java.util.List<Integer> keepAddrs = new java.util.ArrayList<>();
        for (int i = 0; i < oldBuildingCount; i++) {
            int addr = oldBuildingStart + i * 32;
            if (addr + 32 > oldBtl.length) break;
            int coord = buildingCoord(oldBtl, addr, mapData.coordBase);
            if (coord >= 0 && coord < total && !oldRecordByTile.containsKey(coord)) {
                oldRecordByTile.put(coord, addr);
            }
            if (keepOldBuilding(oldBtl, addr, total, mapData)) keepAddrs.add(addr);
        }
        java.util.List<Integer> addTiles = new java.util.ArrayList<>();
        for (int i = 0; i < total; i++) {
            int bid = mapData.buildingIds.get(i);
            if (bid <= 0 || oldRecordByTile.containsKey(i)) continue;
            addTiles.add(i);
        }
        byte[] out = new byte[(keepAddrs.size() + addTiles.size()) * 32];
        int outIdx = 0;
        // 原有记录：坐标能对上地块的按内存类型更新，对不上的（占位/越界记录）原样保留
        for (int addr : keepAddrs) {
            System.arraycopy(oldBtl, addr, out, outIdx * 32, 32);
            int coord = buildingCoord(oldBtl, addr, mapData.coordBase);
            if (coord >= 0 && coord < total) {
                int bid = mapData.buildingIds.get(coord);
                if (bid > 0) out[outIdx * 32 + 4] = (byte) bid;
            }
            outIdx++;
        }
        // 新增建筑：内存有、原文件没有
        for (int i : addTiles) {
            int bid = mapData.buildingIds.get(i);
            int off = outIdx * 32;
            int stored = i + mapData.coordBase;
            byte[] draft = mapData.newBuildingRaws != null
                    ? mapData.newBuildingRaws.get(i) : null;
            if (draft != null && draft.length >= 32) {
                System.arraycopy(draft, 0, out, off, 32);
            } else {
                out[off] = (byte) (stored & 0xFF);
                out[off + 1] = (byte) ((stored >>> 8) & 0xFF);
            }
            out[off + 4] = (byte) bid;
            outIdx++;
        }
        return out;
    }

    /** 建筑记录坐标（0x0，uint16）→ 地图本地坐标。 */
    private static int buildingCoord(byte[] src, int addr, int coordBase) {
        return ((src[addr] & 0xFF) | ((src[addr + 1] & 0xFF) << 8)) - coordBase;
    }

    /**
     * 原有建筑记录是否保留：
     * - 坐标对不上地图地块（越界/占位记录）→ 原样保留（真实游戏文件里存在这类记录，丢了会改变建筑总数导致后续段错位）；
     * - 该地块内存里仍有建筑 → 保留（类型按内存更新）；
     * - 类型 0 的占位记录 → 保留；
     * - 其余（该地块建筑被删掉）→ 丢弃。
     */
    private static boolean keepOldBuilding(byte[] oldBtl, int addr, int total, MapData mapData) {
        int coord = buildingCoord(oldBtl, addr, mapData.coordBase);
        if (coord < 0 || coord >= total) return true;
        if (mapData.buildingIds.get(coord) > 0) return true;
        return (oldBtl[addr + 4] & 0xFF) == 0;
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
        refreshTraps(mapData);
        mapData.buildTerrainPatterns();
        normalizeWaterDistricts(mapData);
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
        int[] dims = binDims(data);
        if (dims == null) throw new IOException("BIN 地形数据不完整");
        int width = dims[0], height = dims[1], headerSize = dims[2];
        MapData mapData = new MapData(width, height);
        mapData.fileName = fileName;
        mapData.binOriginalData = data;
        int totalTiles = width * height;
        for (int i = 0; i < totalTiles; i++) {
            int addr = headerSize + i * 16;
            if (addr + 16 > data.length) break;
            mapData.tiles.get(i).parseFromBytes(data, addr);
        }
        mapData.buildTerrainPatterns();
        return mapData;
    }

    public static byte[] saveAsBIN(MapData mapData) throws IOException {
        int totalTiles = mapData.width * mapData.height;
        // 保存前静默修正：海洋地块的省规划必须是 0xFFFF（世界底图同样适用）。
        normalizeWaterDistricts(mapData);
        if (mapData.binOriginalData != null) {
            // 世界地形 BIN：保留头 16 字节与地形之后的省规划段，仅原地更新截取区域的地形
            byte[] bin = mapData.binOriginalData.clone();
            int[] dims = binDims(mapData.binOriginalData);
            if (dims == null) throw new IOException("BIN 地形数据不完整");
            int binW = dims[0], binH = dims[1], headerSize = dims[2];
            if (binW <= 0 || binH <= 0 || headerSize + binW * binH * 16 > bin.length) {
                throw new IOException("BIN 地形数据不完整");
            }
            int cropX = 0, cropY = 0;
            if (mapData.btlOriginalData != null) {
                BtlHeaderInfo h = parseBTLHeader(mapData.btlOriginalData);
                // 仅当当前地图就是该 BTL 的窗口尺寸时才按截取偏移写回；
                // 打开地图（整张 BIN + 关联 BTL）时按整图写，忽略偏移
                if (mapData.width == h.width && mapData.height == h.height) {
                    cropX = h.captureX;
                    cropY = h.captureY;
                }
            }
            if (cropX < 0 || cropY < 0 || cropX + mapData.width > binW || cropY + mapData.height > binH) {
                throw new IOException("地图截取区域超出 BIN 范围");
            }
            for (int y = 0; y < mapData.height; y++) {
                for (int x = 0; x < mapData.width; x++) {
                    int dst = headerSize + ((y + cropY) * binW + (x + cropX)) * 16;
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
        int[] dims = binDims(binData);
        if (dims == null) throw new IOException("BIN 地形数据不完整");
        int binW = dims[0], binH = dims[1], headerSize = dims[2];
        if (binW <= 0 || binH <= 0 || headerSize + binW * binH * 16 > binData.length) {
            throw new IOException("BIN 地形数据不完整");
        }
        if (h.captureX < 0 || h.captureY < 0
                || h.captureX + h.width > binW || h.captureY + h.height > binH) {
            throw new IOException("BTL 截取区域超出 BIN 范围");
        }
        for (int y = 0; y < h.height; y++) {
            for (int x = 0; x < h.width; x++) {
                int src = headerSize + ((y + h.captureY) * binW + (x + h.captureX)) * 16;
                mapData.tiles.get(y * h.width + x).parseFromBytes(binData, src);
            }
        }
        mapData.buildTerrainPatterns();
    }

    /** 官方测试底图 _Bin.bin：8 字节头（宽@0、高@4）+ 每格 2×u16（65534/65535）。 */
    public static byte[] createTestMapBin(int mapBinGW, int mapBinGH, int sumGrid) {
        byte[] out = new byte[8 + sumGrid * 4];
        ByteBuffer bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(0, mapBinGW);
        bb.putInt(4, mapBinGH);
        for (int i = 0; i < sumGrid; i++) {
            int off = 8 + i * 4;
            out[off] = (byte) 0xFE;
            out[off + 1] = (byte) 0xFF;
            out[off + 2] = (byte) 0xFF;
            out[off + 3] = (byte) 0xFF;
        }
        return out;
    }

    /** 官方 initTestConquest：空白测试征服 btl（窗口 w×(h-4)，capture(0,2)，2 军团，无内容）。 */
    public static byte[] createTestConquestBtl(int w, int h, int mapId) {
        int hh = h - 4;
        if (hh < 1) hh = 1;
        int sumGrid = w * hh;
        int headerSize = 128;
        int legionBytes = 2 * 300;
        int adminBytes = sumGrid * 2;
        int ownBytes = sumGrid;
        byte[] out = new byte[headerSize + legionBytes + adminBytes + ownBytes];
        ByteBuffer bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(0x00, 1);      // version
        bb.putInt(0x04, mapId);  // 征服 mapId（引用世界底图）
        bb.putInt(0x08, 0);      // captureX
        bb.putInt(0x0C, 2);      // captureY（官方固定 2）
        bb.putInt(0x10, w);
        bb.putInt(0x14, hh);
        bb.putInt(0x18, 2);      // legions
        bb.putInt(0x1C, 0);      // buildings
        bb.putInt(0x20, 0);      // armies
        bb.putInt(0x24, 0);      // plans
        bb.putInt(0x28, 0);      // events
        bb.putInt(0x2C, 0);      // weather
        bb.putInt(0x30, 1);      // victory
        bb.putInt(0x34, 999);
        bb.putInt(0x38, 999);
        bb.putInt(0x54, 1);
        bb.putInt(0x58, sumGrid);
        // 两个中性军团
        bb.putInt(headerSize, 1);
        bb.putInt(headerSize + 0x04, 1);
        bb.putInt(headerSize + 300, 2);
        bb.putInt(headerSize + 300 + 0x04, 3);
        // 归属：全部中立 0xFF
        int ownStart = headerSize + legionBytes + adminBytes;
        for (int i = 0; i < ownBytes; i++) out[ownStart + i] = (byte) 0xFF;
        return out;
    }

    private static void remapSection(byte[] btl, int base, int count, int recSize,
                                     int fieldOffset, int dir, int n, int oldW) {
        if (count <= 0 || base < 0) return;
        for (int i = 0; i < count; i++) {
            int addr = base + i * recSize;
            if (addr + fieldOffset + 2 > btl.length) break;
            int raw = (btl[addr + fieldOffset] & 0xFF)
                    | ((btl[addr + fieldOffset + 1] & 0xFF) << 8);
            if (raw == 0 || raw == 0xFFFF) continue;
            int nv = remapIndex(raw, dir, n, oldW);
            if (nv < 0 || nv > 0xFFFF) continue;
            btl[addr + fieldOffset] = (byte) (nv & 0xFF);
            btl[addr + fieldOffset + 1] = (byte) ((nv >>> 8) & 0xFF);
        }
    }

    /** 旧绝对索引 -> 新底图中的绝对索引。 */
    private static int remapIndex(int abs, int dir, int n, int oldW) {
        if (abs < 0) return -1;
        switch (dir) {
            case 0: return abs + n * oldW;               // 向上：整幅下移 n 行
            case 1: return abs;                           // 向下：不变
            case 2: {                                     // 向左：x+n，按新宽重排
                int x = abs % oldW, y = abs / oldW;
                return y * (oldW + n) + (x + n);
            }
            default: {                                    // 向右：x 不变，按新宽重排
                int x = abs % oldW, y = abs / oldW;
                return y * (oldW + n) + x;
            }
        }
    }

    /** 新旧格映射（完全按枭雄/熊的 WASD/IJKL 规则）。 */
    private static void fillMapping(int[] map, String dir, int n, int cols, int rows,
                                    int newCols, int newRows, boolean expand) {
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                int old = row * cols + col;
                int nr = row, nc = col;
                if (dir.equals("up")) nr = expand ? row + n : row - n;
                else if (dir.equals("down")) {
                    if (!expand && row >= rows - n) continue;
                } else if (dir.equals("left")) nc = expand ? col + n : col - n;
                else if (dir.equals("right")) {
                    if (!expand && col >= cols - n) continue;
                }
                if (nr < 0 || nr >= newRows || nc < 0 || nc >= newCols) continue;
                map[nr * newCols + nc] = old;
            }
        }
    }

    /** 新格子填充地形：按枭雄模板（装饰写 63/255 = 无装饰）。 */
    private static TerrainTile makeFillTile(int gid, int tid) {
        byte[] raw = new byte[16];
        raw[0] = (byte) (gid & 0xFF);
        raw[1] = (byte) (tid & 0xFF);
        raw[4] = 63; raw[5] = (byte) 0xFF;
        raw[8] = 63; raw[9] = (byte) 0xFF;
        TerrainTile t = new TerrainTile();
        t.parseFromBytes(raw, 0);
        return t;
    }

    private static void writeCoord(byte[] raw, int off, int coord) {
        if (raw == null || off + 2 > raw.length) return;
        raw[off] = (byte) (coord & 0xFF);
        raw[off + 1] = (byte) ((coord >>> 8) & 0xFF);
    }

    /**
     * 地图扩展/收缩（完全按枭雄/熊的 WASD/IJKL 规则）：
     * 重建索引映射、新格子填「选择的地形」、省规划与归属重映射、
     * 建筑/兵种/陷阱/援军/空袭/首都/放置坐标全部改写、头部尺寸更新；
     * 征服（截取）地图只改截取窗口，超出底图直接拒绝。
     */
    public static void resizeMap(MapData m, String dir, int n, boolean expand,
                                 int fillGid, int fillTid) throws IOException {
        if (m == null || m.btlOriginalData == null) throw new IOException("请先加载 BTL");
        if (n <= 0) throw new IOException("数量必须大于 0");
        BtlHeaderInfo h = parseBTLHeader(m.btlOriginalData);
        int cols = m.width, rows = m.height;
        int newCols = cols, newRows = rows;
        boolean vertical = dir.equals("up") || dir.equals("down");
        if (vertical) newRows = expand ? rows + n : rows - n;
        else newCols = expand ? cols + n : cols - n;
        if (newCols < 1 || newRows < 1) throw new IOException("收缩数量超过当前尺寸");
        if (!expand && n >= (vertical ? rows : cols)) {
            throw new IOException("收缩数量不能超过或等于当前" + (vertical ? "行数" : "列数"));
        }
        int newTotal = newCols * newRows;
        int[] map = new int[newTotal];
        java.util.Arrays.fill(map, -1);
        fillMapping(map, dir, n, cols, rows, newCols, newRows, expand);
        int[] oldToNew = new int[cols * rows];
        java.util.Arrays.fill(oldToNew, -1);
        for (int i = 0; i < newTotal; i++) if (map[i] >= 0) oldToNew[map[i]] = i;

        if (!h.independentTerrain) {
            resizeSubmap(m, h, newCols, newRows, newTotal, map, oldToNew, cols, n, dir, expand);
            // 扩充出来的海洋格省规划必须同步为 0xFFFF
            normalizeWaterDistricts(m);
            return;
        }

        java.util.List<TerrainTile> newTiles = new java.util.ArrayList<>(newTotal);
        int[] newProv = new int[newTotal];
        byte[] newBelong = new byte[newTotal];
        for (int i = 0; i < newTotal; i++) {
            int old = map[i];
            if (old >= 0 && old < m.tiles.size()) {
                newTiles.add(m.tiles.get(old));
                int pv = (m.provinces != null && old < m.provinces.length) ? m.provinces[old] : 0xFFFF;
                if (pv == 0xFFFF || pv == 0) newProv[i] = pv;
                else if (pv == old) newProv[i] = i;
                else newProv[i] = (pv < oldToNew.length && oldToNew[pv] >= 0) ? oldToNew[pv] : i;
                newBelong[i] = (m.belongs != null && old < m.belongs.length) ? m.belongs[old] : (byte) 0xFF;
            } else {
                newTiles.add(makeFillTile(fillGid, fillTid));
                newProv[i] = i;
                newBelong[i] = (byte) 0xFF;
            }
        }
        byte[] rebuilt = rebuildBtl(m, h, newTiles, newProv, newBelong, oldToNew,
                newCols, newRows, newTotal, true);
        replaceWith(m, rebuilt);
        // 扩充出来的海洋格（或改过的地形）省规划同步为 0xFFFF
        normalizeWaterDistricts(m);
    }

    /** 重建整份 BTL（战役：地形内嵌；内容坐标按映射重排）。 */
    private static byte[] rebuildBtl(MapData m, BtlHeaderInfo h,
                                     java.util.List<TerrainTile> newTiles, int[] newProv,
                                     byte[] newBelong, int[] oldToNew,
                                     int newW, int newH, int newTotal, boolean remapCoord) {
        byte[] src = m.btlOriginalData;
        int armyRec = armyRecSize(h.version);
        int reinfRec = h.version == 1 ? 80 : 104;
        computeTailStarts(m, h);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] header = new byte[128];
        System.arraycopy(src, 0, header, 0, 128);
        put32(header, 0x10, newW);
        put32(header, 0x14, newH);
        put32(header, 0x58, newTotal);
        out.write(header, 0, 128);
        out.write(src, 128, h.legionCount * 300);
        if (h.independentTerrain) {
            byte[] buf = new byte[newTotal * 16];
            for (int i = 0; i < newTotal; i++) newTiles.get(i).toBytes(buf, i * 16);
            out.write(buf, 0, buf.length);
        }
        byte[] prov = new byte[newTotal * 2];
        for (int i = 0; i < newTotal; i++) {
            prov[i * 2] = (byte) (newProv[i] & 0xFF);
            prov[i * 2 + 1] = (byte) ((newProv[i] >>> 8) & 0xFF);
        }
        out.write(prov, 0, prov.length);
        out.write(newBelong, 0, newBelong.length);
        // 建筑段
        for (int i = 0; i < h.buildingCount; i++) {
            int addr = h.buildingStart + i * 32;
            if (addr + 32 > src.length) break;
            byte[] rec = new byte[32];
            System.arraycopy(src, addr, rec, 0, 32);
            if (!remapCoord) { out.write(rec, 0, 32); continue; }
            int coord = le16(rec, 0) - m.coordBase;
            int nw = (coord >= 0 && coord < oldToNew.length) ? oldToNew[coord] : -1;
            if (nw < 0) continue;
            writeCoord(rec, 0, nw + m.coordBase);
            out.write(rec, 0, 32);
        }
        // 兵种段
        int armyStart = h.buildingStart + h.buildingCount * 32;
        for (int i = 0; i < h.armyCount; i++) {
            int addr = armyStart + i * armyRec;
            if (addr + armyRec > src.length) break;
            byte[] rec = new byte[armyRec];
            System.arraycopy(src, addr, rec, 0, armyRec);
            if (remapCoord) {
                int coord = le16(rec, 0) - m.coordBase;
                int nw = (coord >= 0 && coord < oldToNew.length) ? oldToNew[coord] : -1;
                if (nw < 0) continue;
                writeCoord(rec, 0, nw + m.coordBase);
            }
            out.write(rec, 0, armyRec);
        }
        // 陷阱段
        int mineStart = armyStart + h.armyCount * armyRec;
        for (int i = 0; i < h.mineCount; i++) {
            int addr = mineStart + i * 12;
            if (addr + 12 > src.length) break;
            byte[] rec = new byte[12];
            System.arraycopy(src, addr, rec, 0, 12);
            if (remapCoord) {
                int coord = le16(rec, 0) - m.coordBase;
                int nw = (coord >= 0 && coord < oldToNew.length) ? oldToNew[coord] : -1;
                if (nw < 0) continue;
                writeCoord(rec, 0, nw + m.coordBase);
            }
            out.write(rec, 0, 12);
        }
        // 方案 + 天气 + 事件：原样
        int planStart = mineStart + h.mineCount * 12;
        int eventEnd = m.eventStart + h.eventCount * 44;
        if (planStart < src.length) {
            out.write(src, planStart, Math.min(eventEnd, src.length) - planStart);
        }
        // 尾段
        copyWithCoord(out, src, m.reinforceStart, h.reinforceCount, reinfRec, 0, 2,
                oldToNew, m.coordBase, remapCoord);
        copyWithCoord(out, src, m.airstrikeStart, h.airstrikeCount, 20, 0, 4,
                oldToNew, m.coordBase, remapCoord);
        copyWithCoord(out, src, m.placementAStart, h.placementCountA, 8, 0, 4,
                oldToNew, m.coordBase, remapCoord);
        copyWithCoord(out, src, m.placementBStart, h.placementCountB, 8, 0, 4,
                oldToNew, m.coordBase, remapCoord);
        copyWithCoord(out, src, m.strategyStart, h.strategyCount, 16, -1, 0,
                oldToNew, m.coordBase, false);
        copyWithCoord(out, src, m.airSupportStart, h.airSupportCount, 16, -1, 0,
                oldToNew, m.coordBase, false);
        copyWithCoord(out, src, m.capitalStart, h.capitalCount, 4, 0, 4,
                oldToNew, m.coordBase, remapCoord);
        int tailStart = m.capitalStart + h.capitalCount * 4;
        if (tailStart < src.length) out.write(src, tailStart, src.length - tailStart);
        return out.toByteArray();
    }

    /** 复制一段记录，可选对地块索引字段重映射。 */
    private static void copyWithCoord(java.io.ByteArrayOutputStream out, byte[] src, int start,
                                      int count, int size, int coordOff, int coordBytes,
                                      int[] oldToNew, int coordBase, boolean remap) {
        for (int i = 0; i < count; i++) {
            int addr = start + i * size;
            if (addr < 0 || addr + size > src.length) break;
            byte[] rec = new byte[size];
            System.arraycopy(src, addr, rec, 0, size);
            if (remap && coordOff >= 0) {
                int coord = (coordBytes == 2 ? le16(rec, coordOff) : le32(rec, coordOff)) - coordBase;
                int nw = (coord >= 0 && coord < oldToNew.length) ? oldToNew[coord] : -1;
                if (nw < 0) continue;
                if (coordBytes == 2) writeCoord(rec, coordOff, nw + coordBase);
                else put32(rec, coordOff, nw + coordBase);
            }
            out.write(rec, 0, size);
        }
    }

    /** 征服（截取）地图：只改截取窗口（captureX/Y + 长宽 + 省规划/归属长度），地形与内容坐标不动。 */
    private static void resizeSubmap(MapData m, BtlHeaderInfo h, int newCols, int newRows,
                                     int newTotal, int[] map, int[] oldToNew,
                                     int cols, int n, String dir, boolean expand) throws IOException {
        int binW = cols, binH = h.height;
        if (m.binOriginalData != null && m.binOriginalData.length >= 16) {
            binW = le32(m.binOriginalData, 8);
            binH = le32(m.binOriginalData, 12);
        }
        int capX = h.captureX, capY = h.captureY;
        if (dir.equals("up")) capY += (expand ? -n : n);
        if (dir.equals("down")) capY += (expand ? 0 : n);
        if (dir.equals("left")) capX += (expand ? -n : n);
        if (dir.equals("right")) capX += (expand ? 0 : n);
        if (expand && (capX < 0 || capY < 0 || capX + newCols > binW || capY + newRows > binH)) {
            throw new IOException("❌ 禁止扩展：截取窗口已占满底图（底图 " + binW + "×" + binH + "）");
        }
        if (capX < 0) capX = 0;
        if (capY < 0) capY = 0;
        int[] newProv = new int[newTotal];
        byte[] newBelong = new byte[newTotal];
        java.util.Arrays.fill(newProv, 0xFFFF);
        java.util.Arrays.fill(newBelong, (byte) 0xFF);
        for (int i = 0; i < newTotal; i++) {
            int old = map[i];
            if (old < 0) continue;
            if (m.belongs != null && old < m.belongs.length) newBelong[i] = m.belongs[old];
            if (m.provinces != null && old < m.provinces.length) {
                int oldWx = old % cols + h.captureX, oldWy = old / cols + h.captureY;
                newProv[i] = oldWy * newCols + oldWx;   // 世界坐标（新窗口宽度）
            }
        }
        byte[] src = m.btlOriginalData;
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] header = new byte[128];
        System.arraycopy(src, 0, header, 0, 128);
        put32(header, 0x08, capX);
        put32(header, 0x0C, capY);
        put32(header, 0x10, newCols);
        put32(header, 0x14, newRows);
        put32(header, 0x58, newTotal);
        out.write(header, 0, 128);
        out.write(src, 128, h.legionCount * 300);
        byte[] prov = new byte[newTotal * 2];
        for (int i = 0; i < newTotal; i++) {
            prov[i * 2] = (byte) (newProv[i] & 0xFF);
            prov[i * 2 + 1] = (byte) ((newProv[i] >>> 8) & 0xFF);
        }
        out.write(prov, 0, prov.length);
        out.write(newBelong, 0, newBelong.length);
        out.write(src, h.buildingStart, src.length - h.buildingStart);   // 内容段原样（世界坐标不变）
        replaceWith(m, out.toByteArray());
    }

    /** 用重建后的字节替换 MapData 的全部内容（保持对象引用不变）。 */
    private static void replaceWith(MapData m, byte[] rebuilt) throws IOException {
        MapData fresh = loadFile(rebuilt, m.fileName == null ? "resized.btl" : m.fileName);
        m.btlOriginalData = rebuilt;
        m.width = fresh.width;
        m.height = fresh.height;
        m.tiles = fresh.tiles;
        m.buildingIds = fresh.buildingIds;
        m.provinces = fresh.provinces;
        m.belongs = fresh.belongs;
        m.armies = fresh.armies;
        m.buildings = fresh.buildings;
        m.traps = fresh.traps;
        m.legions = fresh.legions;
        m.legionColors = fresh.legionColors;
        m.legionCountries = fresh.legionCountries;
        m.coordBase = fresh.coordBase;
        m.editedCells.clear();
        computeTailStarts(m, parseBTLHeader(rebuilt));
    }
}
