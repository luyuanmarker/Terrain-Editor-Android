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

    // ══ 通用业务段（建筑之后的 6 个记录段）══════════════════════════
    public static final int SEC_TRAP = 0, SEC_PLAN = 1, SEC_WEATHER = 2,
            SEC_EVENT = 3, SEC_REINF = 4, SEC_AIR = 5;

    /** 该段的起始偏移（按头部各计数顺序累加）。 */
    public static int sectionStart(BtlHeaderInfo h, int which) {
        int c = h.buildingStart + h.buildingCount * 32 + h.armyCount * armyRecSize(h.version);
        if (which == SEC_TRAP) return c;
        c += h.mineCount * 12;
        if (which == SEC_PLAN) return c;
        c += h.planCount * 16;
        if (which == SEC_WEATHER) return c;
        c += h.weatherCount * 16;
        if (which == SEC_EVENT) return c;
        c += h.eventCount * 44;
        if (which == SEC_REINF) return c;
        c += h.reinforceCount * (h.version == 1 ? 80 : 104);
        return c;   // SEC_AIR
    }

    /** 该段单条记录长度。 */
    public static int sectionRecSize(BtlHeaderInfo h, int which) {
        switch (which) {
            case SEC_TRAP: return 12;
            case SEC_PLAN: return 16;
            case SEC_WEATHER: return 16;
            case SEC_EVENT: return 44;
            case SEC_REINF: return h.version == 1 ? 80 : 104;
            default: return 20;
        }
    }

    /** 该段条数。 */
    public static int sectionCount(BtlHeaderInfo h, int which) {
        switch (which) {
            case SEC_TRAP: return h.mineCount;
            case SEC_PLAN: return h.planCount;
            case SEC_WEATHER: return h.weatherCount;
            case SEC_EVENT: return h.eventCount;
            case SEC_REINF: return h.reinforceCount;
            default: return h.airstrikeCount;
        }
    }

    /** 该段条数在头部的位置。 */
    public static int sectionCountField(int which) {
        switch (which) {
            case SEC_TRAP: return 0x68;
            case SEC_PLAN: return 0x24;
            case SEC_WEATHER: return 0x2C;
            case SEC_EVENT: return 0x28;
            case SEC_REINF: return 0x3C;
            default: return 0x40;
        }
    }

    /** 读一条记录。 */
    public static byte[] readRecord(MapData m, int which, int index) {
        if (m == null || m.btlOriginalData == null) return new byte[sectionRecSizeSafe(which)];
        BtlHeaderInfo h = parseBTLHeader(m.btlOriginalData);
        int size = sectionRecSize(h, which);
        int off = sectionStart(h, which) + index * size;
        byte[] out = new byte[size];
        if (index >= 0 && index < sectionCount(h, which) && off + size <= m.btlOriginalData.length) {
            System.arraycopy(m.btlOriginalData, off, out, 0, size);
        }
        return out;
    }

    private static int sectionRecSizeSafe(int which) {
        return which == SEC_TRAP ? 12 : which == SEC_PLAN ? 16 : which == SEC_WEATHER ? 16
                : which == SEC_EVENT ? 44 : which == SEC_REINF ? 104 : 20;
    }

    /** 就地覆盖一条记录。 */
    public static void writeRecord(MapData m, int which, int index, byte[] rec) throws IOException {
        if (m == null || m.btlOriginalData == null || rec == null) throw new IOException("请先加载 BTL 地图");
        BtlHeaderInfo h = parseBTLHeader(m.btlOriginalData);
        int size = sectionRecSize(h, which);
        if (index < 0 || index >= sectionCount(h, which)) throw new IOException("序号越界");
        int off = sectionStart(h, which) + index * size;
        if (off + size > m.btlOriginalData.length) throw new IOException("记录越界");
        System.arraycopy(rec, 0, m.btlOriginalData, off, Math.min(size, rec.length));
    }

    /** 在段尾追加一条空记录，返回它的序号。 */
    public static int insertRecord(MapData m, int which) throws IOException {
        if (m == null || m.btlOriginalData == null) throw new IOException("请先加载 BTL 地图");
        BtlHeaderInfo h = parseBTLHeader(m.btlOriginalData);
        int size = sectionRecSize(h, which);
        int count = sectionCount(h, which);
        int at = sectionStart(h, which) + count * size;
        byte[] old = m.btlOriginalData;
        if (at > old.length) at = old.length;
        byte[] out = new byte[old.length + size];
        System.arraycopy(old, 0, out, 0, at);
        System.arraycopy(old, at, out, at + size, old.length - at);
        java.nio.ByteBuffer.wrap(out).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .putInt(sectionCountField(which), count + 1);
        m.btlOriginalData = out;
        refreshTraps(m);
        return count;
    }

    /** 删除第 index 条记录。 */
    public static void removeRecord(MapData m, int which, int index) throws IOException {
        if (m == null || m.btlOriginalData == null) throw new IOException("请先加载 BTL 地图");
        BtlHeaderInfo h = parseBTLHeader(m.btlOriginalData);
        int size = sectionRecSize(h, which);
        int count = sectionCount(h, which);
        if (index < 0 || index >= count) return;
        int off = sectionStart(h, which) + index * size;
        int end = off + size;
        if (end > m.btlOriginalData.length) end = m.btlOriginalData.length;
        byte[] out = new byte[m.btlOriginalData.length - (end - off)];
        System.arraycopy(m.btlOriginalData, 0, out, 0, off);
        System.arraycopy(m.btlOriginalData, end, out, off, m.btlOriginalData.length - end);
        java.nio.ByteBuffer.wrap(out).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .putInt(sectionCountField(which), count - 1);
        m.btlOriginalData = out;
        refreshTraps(m);
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
    public static int addEvent(MapData mapData) throws IOException {
        if (mapData == null || mapData.btlOriginalData == null) throw new IOException("请先加载 BTL 地图");
        byte[] old = mapData.btlOriginalData;
        BtlHeaderInfo h = parseBTLHeader(old);
        int start = eventStart(h);
        int bytes = h.eventCount * 44;
        if (start + bytes > old.length) throw new IOException("事件段越界");
        // 事件ID 取现有最大值 +1（与熊编辑器「＋新增事件」一致）
        int maxId = -1;
        for (int i = 0; i < h.eventCount; i++) {
            int id = le32(old, start + i * 44);
            if (id > maxId) maxId = id;
        }
        byte[] result = new byte[old.length + 44];
        System.arraycopy(old, 0, result, 0, start + bytes);
        // 新事件默认值照熊编辑器：触发条件=2(回合到)、触发事件=4(无效果)、默认结束段=0xCCCCCC00
        ByteBuffer nb = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN);
        nb.putInt(start + bytes + 0x00, maxId + 1);
        nb.putInt(start + bytes + 0x08, 2);
        nb.putInt(start + bytes + 0x0C, 4);
        nb.putInt(start + bytes + 0x28, 0xCCCCCC00);
        System.arraycopy(old, start + bytes, result, start + bytes + 44,
                old.length - (start + bytes));
        ByteBuffer hb = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN);
        hb.putInt(0x28, h.eventCount + 1);
        mapData.btlOriginalData = result;
        computeTailStarts(mapData, parseBTLHeader(result));
        return h.eventCount;                 // 新事件的下标
    }

    /** 删除第 index 条事件：整段前移 44 字节，事件总数 -1。 */
    public static boolean removeEvent(MapData mapData, int index) throws IOException {
        if (mapData == null || mapData.btlOriginalData == null) throw new IOException("请先加载 BTL 地图");
        byte[] old = mapData.btlOriginalData;
        BtlHeaderInfo h = parseBTLHeader(old);
        if (index < 0 || index >= h.eventCount) return false;
        int start = eventStart(h);
        int off = start + index * 44;
        if (off + 44 > old.length) throw new IOException("事件段越界");
        byte[] result = new byte[old.length - 44];
        System.arraycopy(old, 0, result, 0, off);
        System.arraycopy(old, off + 44, result, off, old.length - off - 44);
        ByteBuffer hb = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN);
        hb.putInt(0x28, h.eventCount - 1);
        mapData.btlOriginalData = result;
        computeTailStarts(mapData, parseBTLHeader(result));
        return true;
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
        // ★ 熊的 set_map_data() 载入时会先跑 validate_map_data()（同格多兵种/多建筑/陷阱压建筑）。
        //   这里解析前做同样的清理，保证编辑器里看到的就是游戏能接受的形态。
        try {
            byte[] cleaned = validateMapData(data);
            if (cleaned != null) data = cleaned;
        } catch (Exception ignored) {
        }
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

    /** 按头部各段计数算出文件应有的长度（v3 还要加特殊胜利条件字节数）。 */
    private static int declaredLength(BtlHeaderInfo h) {
        int army = armyRecSize(h.version);
        int reinf = h.version == 1 ? 80 : 104;
        int p = 128 + h.legionCount * 300;
        if (h.independentTerrain) p += h.width * h.height * 16;
        p += h.sectionTiles * 2 + h.sectionTiles;
        p += h.buildingCount * 32 + h.armyCount * army + h.mineCount * 12 + h.planCount * 16;
        p += h.weatherCount * 16 + h.eventCount * 44 + h.reinforceCount * reinf + h.airstrikeCount * 20;
        p += (h.placementCountA + h.placementCountB) * 8;
        p += h.strategyCount * 16 + h.airSupportCount * 16 + h.capitalCount * 4;
        return p;
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
        // 结构完整性：文件长度必须不小于头部各段声明之和。
        // 短了就是某些段（最常见是「兵种放置接口甲/乙」与「首都」）被截掉，
        // 游戏按头部计数读取会越界 → 进游戏直接闪退。
        int declared = declaredLength(h);
        if (data.length < declared) {
            issues.add("★ 结构不完整：文件比头部声明的短 " + (declared - data.length)
                    + " 字节（通常是「兵种放置接口甲/乙」(8×N) 与「首都」(4×N) 段缺失）——"
                    + "游戏按头部计数读取会越界，进游戏必闪退。");
        }
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
                int decoFixed = fixIllegalDecorations(mapData);
        if (decoFixed > 0) {
            issues.add("★ 已修正 " + decoFixed + " 个非法装饰（组31 编号>8）：官方 795 张关卡里"
                    + "组31 只用过编号 0~8，写成 10~73 会让游戏取不到装饰贴图而闪退 —— 已改成 63/255（不画）");
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
        // ★ 征服图（地图序号≠0）**整条规则都不套用**（实测官方两张图约定并不一致）：
        //   conquest2 窗口内 3390 个海洋格里 3388 个的省规划是 FFFF；
        //   但 conquest1 窗口内 7320 个海洋格里只有 3081 个是 FFFF，另 4239 个保留着省区。
        //   而征服图的省规划存的是「世界坐标」，强行按地形清会把省区擦掉
        //   （旧版"省区全没了"就是这么来的）。只有自带地形的战役图才按下面这套规则走。
        if (mapData.btlOriginalData != null) {
            try {
                if (!parseBTLHeader(mapData.btlOriginalData).independentTerrain) return 0;
            } catch (Exception ignored) { }
        }
        // 征服 BTL 自带地形为空，内存里的地形只是「未加载底图」的占位海洋，
        // 这时按地形去改省规划会把整张图的省规划清成 65535，必须跳过。
        if (mapData.btlOriginalData != null && mapData.binOriginalData == null) {
            try {
                if (!parseBTLHeader(mapData.btlOriginalData).independentTerrain) return 0;
            } catch (Exception ignored) {
            }
        }
        // 「陆地格必须有省区、且省区代表格落在陆地上」这条不变量**只对自带地形的战役图(BTL mapId=0)成立**：
        //   官方战役图违规格数恒为 0；但官方世界底图 world.bin/world2.bin 本身就有 61/491 个
        //   "陆地无省区"格，征服图的省规划还是世界坐标 —— 对它们套这条规则会把官方数据改坏。
        boolean campaignBtl = false;
        if (mapData.btlOriginalData != null) {
            try {
                campaignBtl = parseBTLHeader(mapData.btlOriginalData).independentTerrain;
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

        // 2) BTL 省规划段：只对"自带地形"的战役生效。
        //    征服（截取窗口）的省规划来自世界底图，游戏自己的文件里水上也有省区，
        //    强行按"水上必须 FFFF"清一遍会把省区擦掉（之前"省区全没了"就是这么来的）。
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
        // ★ 曾经这里还有一条「陆地格的省区代表格必须落在陆地上」的规则 ——**已删除**，因为它是错的：
        //   官方 1001 张关卡里，stage10103 有 1275 格、stage10201 有 3628 格「陆地但省规划=FFFF」，
        //   stage10101 有 12 格、stage10103 有 76 格「代表格落在水上」—— 官方自己就不满足这条不变量。
        //   照它改会把能正常玩的官方图的省区布局改坏（实测一张图会被改写 5000+ 格省规划）。
        //   真正的海陆规则只有第 1~3 步那条：**海洋格 → FFFF**（且只对自带地形的战役图生效）。
        return fixedCells.size();
    }

    /**
     * 官方关卡参战国表（来自游戏 assets/config/ShareAPPCampaign.xml）。
     * 游戏按这里的 country/enemy 去 BTL 的军团段里找对应国家，
     * **找不到就会在进入关卡时闪退** —— 实测 61 个关卡里 60 个的 BTL 都同时含这两个国家。
     * 每行 { 关卡号, 本国ID, 敌国ID }。
     */
    private static final int[][] STAGE_NATIONS = {
            { 10101, 3, 12 }, { 10102, 3, 18 }, { 10103, 3, 2 }, { 10104, 3, 1 }, { 10105, 7, 1 }, { 10106, 7, 13 },
            { 10107, 3, 5 }, { 10108, 3, 1 }, { 10109, 3, 5 }, { 10110, 3, 5 }, { 10111, 3, 5 }, { 10201, 1, 3 },
            { 10202, 1, 3 }, { 10203, 1, 3 }, { 10204, 6, 7 }, { 10205, 5, 3 }, { 10206, 5, 3 }, { 10207, 5, 3 },
            { 10208, 6, 3 }, { 10209, 6, 3 }, { 10210, 6, 3 }, { 10211, 5, 3 }, { 10301, 10, 6 }, { 10302, 10, 1 },
            { 10303, 10, 1 }, { 10304, 10, 6 }, { 10305, 10, 15 }, { 10306, 10, 1 }, { 10307, 10, 5 }, { 10308, 10, 14 },
            { 10309, 10, 6 }, { 10401, 6, 10 }, { 10402, 1, 10 }, { 10403, 6, 10 }, { 10404, 6, 10 }, { 10405, 1, 10 },
            { 10406, 6, 10 }, { 10407, 6, 10 }, { 10408, 5, 10 }, { 10409, 6, 10 }, { 10501, 5, 6 }, { 10502, 5, 6 },
            { 10503, 5, 28 }, { 10504, 5, 6 }, { 10505, 5, 6 }, { 10506, 5, 6 }, { 10601, 4, 5 }, { 10602, 6, 35 },
            { 10603, 6, 5 }, { 10604, 2, 5 }, { 10605, 6, 5 }, { 10606, 6, 5 }, { 10701, 2, 48 }, { 10702, 1, 48 },
            { 10703, 1, 48 }, { 10704, 6, 48 }, { 10705, 6, 48 }, { 10706, 5, 48 }, { 10707, 5, 48 }, { 10708, 6, 48 },
            { 10709, 6, 48 },
    };

    /** 关卡号 → {本国, 敌国}；不是官方关卡返回 null。 */
    public static int[] stageRequiredNations(String fileName) {
        if (fileName == null) return null;
        java.util.regex.Matcher mt = java.util.regex.Pattern
                .compile("(?i)^stage(\\d+)\\.btl$").matcher(fileName.trim());
        if (!mt.matches()) return null;
        int id;
        try { id = Integer.parseInt(mt.group(1)); } catch (Exception e) { return null; }
        for (int[] r : STAGE_NATIONS) if (r[0] == id) return new int[]{r[1], r[2]};
        return null;
    }

    /** 地图军团里已有的国家 ID 集合。 */
    public static java.util.Set<Integer> legionCountries(MapData m) {
        java.util.Set<Integer> s = new java.util.TreeSet<>();
        if (m == null || m.btlOriginalData == null) return s;
        BtlHeaderInfo h = parseBTLHeader(m.btlOriginalData);
        for (int i = 0; i < h.legionCount; i++) {
            int a = 128 + i * 300;
            if (a + 8 > m.btlOriginalData.length) break;
            s.add(le32(m.btlOriginalData, a + 4));
        }
        return s;
    }

    /**
     * 复制第一条军团记录、把国家 ID 改成 country，插到军团段末尾（军团数 +1）。
     * 用于补上官方关卡要求、但地图里缺的参战国。
     */
    public static byte[] addLegionForCountry(byte[] btl, int country) throws IOException {
        BtlHeaderInfo h = parseBTLHeader(btl);
        if (h.legionCount < 1) throw new IOException("地图里没有军团记录，无法复制");
        int src = 128;                                  // 拿第一条当模板
        byte[] rec = new byte[300];
        System.arraycopy(btl, src, rec, 0, 300);
        put32(rec, 0x04, country);                      // 国家 ID
        put32(rec, 0x14, 0);                            // 玩家控制 = 0（电脑控制）
        int fac = le32(rec, 0x18);                      // 阵营翻成对立方，别跟玩家成同盟
        put32(rec, 0x18, fac == 1 ? 2 : 1);
        put32(rec, 0x00, h.legionCount + 1);            // 行动顺序放到最后
        int legEnd = 128 + h.legionCount * 300;
        byte[] out = new byte[btl.length + 300];
        System.arraycopy(btl, 0, out, 0, legEnd);
        System.arraycopy(rec, 0, out, legEnd, 300);
        System.arraycopy(btl, legEnd, out, legEnd + 300, btl.length - legEnd);
        put32(out, 0x18, h.legionCount + 1);
        return out;
    }

    /**
     * 修正非法装饰值（**每次保存前也会自动跑**）。
     *
     * <p>官方实测：`def_mapterrain.xml` 里组 31 是 **snowfield（雪地）**、`layerterrain=false`，
     * 795 张自带地形关卡共 436765 格里组 31 用过 49066 次，但**编号只用过 0~8**。
     * 本编辑器早期版本错误地把组 31 当成"海岸线波浪组"，给每个海洋格写 编号 10~73，
     * 游戏取不到对应装饰贴图 → **进关卡闪退**（官方编辑器只画地形不画装饰层，所以照旧能打开）。
     *
     * @return 被修正的装饰个数
     */
    public static int fixIllegalDecorations(MapData mapData) {
        if (mapData == null || mapData.tiles == null) return 0;
        int n = 0;
        // 按下标遍历：不要用 mapData.tiles.indexOf(t) 反查（equals 被重写时会取到错误的格）
        for (int i = 0; i < mapData.tiles.size(); i++) {
            TerrainTile t = mapData.tiles.get(i);
            if (t == null) continue;
            boolean fixed = false;
            if (t.decoration1Group == 31 && (t.decoration1Id & 0xFF) > 8) {
                t.decoration1Group = 63; t.decoration1Id = 255;
                t.decoration1X = 0; t.decoration1Y = 0;
                fixed = true; n++;
            }
            if (t.decoration2Group == 31 && (t.decoration2Id & 0xFF) > 8) {
                t.decoration2Group = 63; t.decoration2Id = 255;
                t.decoration2X = 0; t.decoration2Y = 0;
                fixed = true; n++;
            }
            if (fixed && mapData.editedCells != null) mapData.editedCells.add(i);
        }
        return n;
    }

    /**
     * 截取/裁剪后按需清空若干业务段（兵力/建筑/陷阱/事件/援军/空袭），并把头部计数清零。
     * 逐段搬运、不重排任何字段，所以不会破坏结构。
     */
    public static byte[] clearSections(byte[] btl,
                                       boolean armies, boolean buildings, boolean traps,
                                       boolean events, boolean reinforcements, boolean airstrikes)
            throws IOException {
        if (btl == null || btl.length < 128) return btl;
        BtlHeaderInfo h = parseBTLHeader(btl);
        int armyRec = armyRecSize(h.version);
        int reinfRec = h.version == 1 ? 80 : 104;
        int armyStart = h.buildingStart + h.buildingCount * 32;
        int mineStart = armyStart + h.armyCount * armyRec;
        int planStart = mineStart + h.mineCount * 12;
        int weatherStart = planStart + h.planCount * 16;
        int eventStart = weatherStart + h.weatherCount * 16;
        int reinfStart = eventStart + h.eventCount * 44;
        int airStart = reinfStart + h.reinforceCount * reinfRec;
        int placeA = airStart + h.airstrikeCount * 20;

        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] header = new byte[128];
        System.arraycopy(btl, 0, header, 0, 128);
        if (buildings) put32(header, 0x1C, 0);
        if (armies) put32(header, 0x20, 0);
        if (traps) put32(header, 0x68, 0);
        if (events) put32(header, 0x28, 0);
        if (reinforcements) put32(header, 0x3C, 0);
        if (airstrikes) put32(header, 0x40, 0);
        out.write(header, 0, 128);
        out.write(padFrom(btl, 128, h.legionCount * 300));
        if (h.independentTerrain) out.write(padFrom(btl, h.terrainStart, h.width * h.height * 16));
        out.write(padFrom(btl, h.adminStart, h.sectionTiles * 2));
        out.write(padFrom(btl, h.ownershipStart, h.sectionTiles));
        if (!buildings) out.write(padFrom(btl, h.buildingStart, h.buildingCount * 32));
        if (!armies) out.write(padFrom(btl, armyStart, h.armyCount * armyRec));
        if (!traps) out.write(padFrom(btl, mineStart, h.mineCount * 12));
        out.write(padFrom(btl, planStart, h.planCount * 16));            // 方案：保留
        out.write(padFrom(btl, weatherStart, h.weatherCount * 16));      // 天气：保留
        if (!events) out.write(padFrom(btl, eventStart, h.eventCount * 44));
        if (!reinforcements) out.write(padFrom(btl, reinfStart, h.reinforceCount * reinfRec));
        if (!airstrikes) out.write(padFrom(btl, airStart, h.airstrikeCount * 20));
        int rest = btl.length - placeA;                                  // 放置/战略/空支/首都/尾段
        if (rest > 0) out.write(padFrom(btl, placeA, rest));
        return out.toByteArray();
    }

    /**
     * 完全按熊可视化编辑器 `map_extension.py::validate_map_data()` 的三条规则清理记录
     * （熊在 `set_map_data()` **载入地图时**调用）：
     * <ol>
     *   <li>一个地块只能有一个普通兵种（<b>39 号保护兵种可以叠加</b>）→ 删除靠后的多余兵种；</li>
     *   <li>一个地块只能有一个建筑 → 删除靠后的多余建筑；</li>
     *   <li>有建筑的地块不允许有陷阱 → 删除该格陷阱。</li>
     * </ol>
     * 官方 1083 张关卡实测：① ② 是零容忍（0 格违规），③ 有 5 处例外。
     *
     * @return 清理后的 BTL 字节；没有任何违规时返回 null（调用方原样使用）。
     */
    public static byte[] validateMapData(byte[] btl) throws IOException {
        if (btl == null || btl.length < 128) return null;
        BtlHeaderInfo h = parseBTLHeader(btl);
        int armyRec = armyRecSize(h.version);
        int reinfRec = h.version == 1 ? 80 : 104;
        int armyStart = h.buildingStart + h.buildingCount * 32;
        int mineStart = armyStart + h.armyCount * armyRec;
        int planStart = mineStart + h.mineCount * 12;
        int reinfStart = planStart + h.planCount * 16 + h.weatherCount * 16 + h.eventCount * 44;
        int airStart = reinfStart + h.reinforceCount * reinfRec;
        int placeA = airStart + h.airstrikeCount * 20;
        int placeB = placeA + h.placementCountA * 8;
        int stratStart = placeB + h.placementCountB * 8;
        int supStart = stratStart + h.strategyCount * 16;
        int capStart = supStart + h.airSupportCount * 16;

        java.util.List<byte[]> bldKeep = new java.util.ArrayList<>();
        java.util.Set<Integer> bldTiles = new java.util.HashSet<>();
        for (int i = 0; i < h.buildingCount; i++) {
            int a = h.buildingStart + i * 32;
            if (a + 32 > btl.length) break;
            if (bldTiles.add(le16(btl, a))) bldKeep.add(java.util.Arrays.copyOfRange(btl, a, a + 32));
        }
        java.util.List<byte[]> armyKeep = new java.util.ArrayList<>();
        java.util.Set<Integer> normalTiles = new java.util.HashSet<>();
        for (int i = 0; i < h.armyCount; i++) {
            int a = armyStart + i * armyRec;
            if (a + armyRec > btl.length) break;
            int type = btl[a + 2] & 0xFF;
            if (type == 39) { armyKeep.add(java.util.Arrays.copyOfRange(btl, a, a + armyRec)); continue; }
            if (normalTiles.add(le16(btl, a))) armyKeep.add(java.util.Arrays.copyOfRange(btl, a, a + armyRec));
        }
        java.util.List<byte[]> mineKeep = new java.util.ArrayList<>();
        for (int i = 0; i < h.mineCount; i++) {
            int a = mineStart + i * 12;
            if (a + 12 > btl.length) break;
            if (bldTiles.contains(le16(btl, a))) continue;
            mineKeep.add(java.util.Arrays.copyOfRange(btl, a, a + 12));
        }
        if (bldKeep.size() == h.buildingCount && armyKeep.size() == h.armyCount
                && mineKeep.size() == h.mineCount) return null;

        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] header = new byte[128];
        System.arraycopy(btl, 0, header, 0, 128);
        put32(header, 0x1C, bldKeep.size());
        put32(header, 0x20, armyKeep.size());
        put32(header, 0x68, mineKeep.size());
        out.write(header, 0, 128);
        out.write(padFrom(btl, 128, h.legionCount * 300));
        if (h.independentTerrain) out.write(padFrom(btl, h.terrainStart, h.width * h.height * 16));
        out.write(padFrom(btl, h.adminStart, h.sectionTiles * 2));
        out.write(padFrom(btl, h.ownershipStart, h.sectionTiles));
        for (byte[] r : bldKeep) out.write(r, 0, 32);
        for (byte[] r : armyKeep) out.write(r, 0, armyRec);
        for (byte[] r : mineKeep) out.write(r, 0, 12);
        int tailEnd = Math.min(capStart + h.capitalCount * 4, btl.length);
        if (tailEnd > planStart) out.write(padFrom(btl, planStart, tailEnd - planStart));
        if (tailEnd < btl.length) out.write(padFrom(btl, tailEnd, btl.length - tailEnd));
        return out.toByteArray();
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
        // 军团：u16（0xFFFF=中立），低字节 + 高字节都要写（原来漏了高字节）
        rec[2] = (byte) (legion & 0xFF);
        rec[3] = (byte) ((legion >>> 8) & 0xFF);
        rec[4] = (byte) (level & 0xFF);
        // 血量：官方实测 等级1=70（= 70 + 45×(等级-1)），不是 60
        rec[6] = 70;
        rec[7] = 0;
        // ★ 第 0x8 字节 = 「陆地地雷 33(0x21) / 海上水雷 34(0x22)」。
        //   官方 1083 张图 7197 颗陷阱实测：陆地一律 33、海上一律 34；
        //   写 0 的话游戏不认（没有贴图，等于隐藏雷）——这就是"新加的地雷没加到游戏里"的原因。
        boolean sea = false;
        TerrainTile here = mapData.getTile(x, y);
        if (here != null) sea = (here.bmTerrain1Group == 1);
        rec[8] = (byte) (sea ? 34 : 33);
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
    /** 删除一个兵种记录：重建兵种段（去掉该条），头部 0x20 计数 -1。 */
    public static void removeArmy(MapData mapData, int armyIndex) throws IOException {
        if (mapData == null || mapData.btlOriginalData == null) throw new IOException("请先加载 BTL 地图");
        byte[] old = mapData.btlOriginalData;
        BtlHeaderInfo h = parseBTLHeader(old);
        int armyStart = h.buildingStart + h.buildingCount * 32;
        int recSize = armyRecSize(h.version);
        if (h.armyCount <= 0) throw new IOException("没有兵种可删");
        if (armyIndex < 0 || armyIndex >= h.armyCount) throw new IOException("兵种序号无效: " + armyIndex);
        int off = armyStart + armyIndex * recSize;
        if (off + recSize > old.length) throw new IOException("兵种段越界");
        byte[] result = new byte[old.length - recSize];
        System.arraycopy(old, 0, result, 0, off);
        int rest = old.length - (off + recSize);
        if (rest > 0) System.arraycopy(old, off + recSize, result, off, rest);
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(result).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        bb.putInt(0x20, h.armyCount - 1);
        mapData.btlOriginalData = result;
        try { refreshArmies(mapData); } catch (Exception ignored) { }
    }

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
        // 保存前静默修正：非法装饰值（组31 编号>8）会让游戏取不到装饰贴图而闪退。
        fixIllegalDecorations(mapData);

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
        // ★ 只用 2 条通用军团，**不再照抄模板的具体国家**。
        //   模板里有 9 条（国家 3,3,2,20,2,2,3,2,2），抄进来会跟官方关卡要求的参战国对不上
        //   （ShareAPPCampaign.xml 里 stage10101 要求 country=3 / enemy=12），进关卡必闪退。
        //   官方 1001 张关卡统计：军团数最少就是 2 条（165 张正好 2 条），所以 2 条是安全下限。
        final int legionCount = 2;
        final int[] neutralCountries = {1, 2};          // 本国 / 敌国，可在「军团列表」里改
        int newTerrainStart = 128 + legionCount * 300;
        int newAdminStart = newTerrainStart + newTotal * 16;
        int newOwnershipStart = newAdminStart + newTotal * 2;
        int newBuildingStart = newOwnershipStart + newTotal;
        // ★ 尾段一律不要：新建图没有任何建筑/兵种，模板尾部这些记录**全是悬空引用** ——
        //   · 兵种放置接口甲/乙 各 20 条，坐标是 0xCDCDCDFF（0xCDCD 是未初始化填充值）→ 游戏按坐标定位会越界；
        //   · 战略建设 3 条，军团序号是 3/7/8，而新图只有 2 个军团 → 指向不存在的军团。
        //   官方战役图（stage10101 / stage10103 / stage10004）这些段**全是 0**，所以直接清空最安全。
        //   新文件 = 头部 + 2 条军团 + 地形 + 省规划 + 归属，正好到此为止。
        byte[] result = new byte[newBuildingStart];

        // 只复制头部；军团段改用 2 条通用记录（结构字段仍取自模板，保证游戏认）
        System.arraycopy(template, 0, result, 0, 128);
        for (int i = 0; i < legionCount; i++) {
            int src = 128 + i * 300;
            if (src + 300 <= template.length) System.arraycopy(template, src, result, 128 + i * 300, 300);
            int a = 128 + i * 300;
            put32(result, a + 0x00, i + 1);                       // 行动顺序
            put32(result, a + 0x04, neutralCountries[i]);         // 国家 ID
            put32(result, a + 0x14, i == 0 ? 1 : 0);              // 本国=玩家控制，敌国=电脑
            put32(result, a + 0x18, i == 0 ? 1 : 2);              // 阵营：对立方
        }

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
            System.arraycopy(fillTile, 0, result, newTerrainStart + i * 16, 16);
            // 省规划：每个格子自己当省区代表格。原来一律写 0（= 全图一格省区、中心在第 0 格）
            // 在官方 795 张自带地形关卡里**一张都没有**；每格自成一省才是干净的初值。
            result[newAdminStart + i * 2] = (byte) (i & 0xFF);
            result[newAdminStart + i * 2 + 1] = (byte) ((i >> 8) & 0xFF);
            result[newOwnershipStart + i] = (byte) 0xFF;
        }

        ByteBuffer header = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(0x18, legionCount);
        header.putInt(0x10, newWidth);
        header.putInt(0x14, newHeight);
        header.putInt(0x58, newTotal);
        // 官方 1001 张关卡里最常见的回合设置是 (胜利条件0, 最小20, 最大20)；
        // 模板里是 24/16（最小 > 最大，自相矛盾），换成官方众数。
        header.putInt(0x30, 0); // 关卡胜利条件
        header.putInt(0x34, 20); // 最小回合
        header.putInt(0x38, 20); // 最大回合
        // 清空所有有记录数组的计数；空 BTL 的末尾就是 ownership 数组。
        header.putInt(0x1C, 0); // 建筑
        header.putInt(0x20, 0); // 兵种
        header.putInt(0x24, 0); // 方案
        header.putInt(0x28, 0); // 事件
        header.putInt(0x2C, 0); // 天气
        header.putInt(0x3C, 0); // 援军
        header.putInt(0x40, 0); // 空袭
        header.putInt(0x44, 0); // 兵种放置接口甲
        header.putInt(0x48, 0); // 兵种放置接口乙
        header.putInt(0x4C, 0); // 国家首都
        header.putInt(0x68, 0); // 陷阱
        header.putInt(0x70, 0); // 战略建设
        header.putInt(0x7C, 0); // 空中支援

        // 尾段不复制（见上）；所有内容段的计数保持 0。

        return loadBTL(result, fileName);
    }

    /**
     * 中立空战役（BIN→BTL 转换等用）：行为已与 {@link #createEmptyBtlFromTemplate} 完全一致 ——
     * 都只写 2 条通用军团、不携带模板的具体国家。保留此入口只为兼容旧调用。
     */
    public static MapData createEmptyBtlNeutral(byte[] template, String fileName,
                                                int newWidth, int newHeight) throws IOException {
        return createEmptyBtlFromTemplate(template, fileName, newWidth, newHeight);
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
        // 省规划段（紧跟 宽×高×16 地形之后，2 字节/格）；文件里没有这一段就补 0xFFFF，
        // 保证「打开地图 → 改省区 → 保存」能正确写回，而不是把原有省区清空。
        int provOff = headerSize + totalTiles * 16;
        if (provOff + totalTiles * 2 <= data.length) {
            mapData.provinces = new int[totalTiles];
            for (int i = 0; i < totalTiles; i++) {
                mapData.provinces[i] = le16(data, provOff + i * 2);
            }
        } else {
            mapData.ensureProvincesSize();
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
            // 省区：只有「单独打开整张底图」（没有关联 BTL）时才写回省规划段，
            // 避免把征服 BTL 的窗口局部省区错写进底图。
            if (mapData.btlOriginalData == null
                    && mapData.width == binW && mapData.height == binH
                    && mapData.provinces != null && mapData.provinces.length == binW * binH) {
                int provOff = headerSize + binW * binH * 16;
                if (provOff + binW * binH * 2 <= bin.length) {
                    for (int i = 0; i < binW * binH; i++) {
                        int a = provOff + i * 2;
                        bin[a] = (byte) (mapData.provinces[i] & 0xFF);
                        bin[a + 1] = (byte) ((mapData.provinces[i] >>> 8) & 0xFF);
                    }
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
     *
     * @return true = 已用这个 BIN 载入地形；false = 这张 BTL 自带地形（战役/新建），
     *         BIN 用不上 —— 这不是错误，所以安静返回 false，不要抛异常、不要弹「自带地形无需 BIN」。
     */
    public static boolean loadConquestTerrain(MapData mapData, byte[] binData) throws IOException {
        if (mapData == null || mapData.btlOriginalData == null) throw new IOException("缺少 BTL 数据");
        BtlHeaderInfo h = parseBTLHeader(mapData.btlOriginalData);
        if (h.independentTerrain) return false;
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
        return true;
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
        if (m == null) throw new IOException("请先加载地图");
        // 单独打开的世界底图 BIN（没有关联 BTL）：直接扩缩 BIN 本体，走 resizeBin
        if (m.btlOriginalData == null) {
            if (m.binOriginalData == null) throw new IOException("请先加载 BTL 或世界底图 BIN");
            resizeBin(m, dir, n, expand, fillGid, fillTid);
            return;
        }
        if (n <= 0) throw new IOException("数量必须大于 0");
        BtlHeaderInfo h = parseBTLHeader(m.btlOriginalData);
        int cols = m.width, rows = m.height;
        boolean vertical = dir.equals("up") || dir.equals("down");
        int newCols = vertical ? cols : (expand ? cols + n : cols - n);
        int newRows = vertical ? (expand ? rows + n : rows - n) : rows;
        if (newCols < 1 || newRows < 1) throw new IOException("收缩数量超过当前尺寸");
        if (!expand && n >= (vertical ? rows : cols)) {
            throw new IOException("收缩数量不能超过或等于当前" + (vertical ? "行数" : "列数"));
        }
        int newTotal = newCols * newRows;
        // BTL 里地块坐标（建筑/兵种/陷阱/…）与省规划代表格都是 2 字节（0..65535）。
        // 超过这个格数继续下去会把坐标截断到错误的格子（writeCoord 只取低 16 位），
        // 属于静默损坏，直接拒绝。
        if (newTotal > 0xFFFF) {
            throw new IOException("扩到 " + newCols + "×" + newRows + " = " + newTotal
                    + " 格，超过 BTL 能表示的 65535 格上限（地块坐标是 2 字节）");
        }
        int[] map = new int[newTotal];
        java.util.Arrays.fill(map, -1);
        fillMapping(map, dir, n, cols, rows, newCols, newRows, expand);
        int[] oldToNew = new int[cols * rows];
        java.util.Arrays.fill(oldToNew, -1);
        for (int i = 0; i < newTotal; i++) if (map[i] >= 0) oldToNew[map[i]] = i;

        // 征服（地图序号≠0）：地形在底图里，扩缩时**挪窗口原点**、底图不动；
        // 战役：截取 X/Y 无意义，保持原值。
        int capX = h.captureX, capY = h.captureY;
        if (!h.independentTerrain) {
            int binW = cols, binH = h.height;
            if (m.binOriginalData != null && m.binOriginalData.length >= 16) {
                binW = le32(m.binOriginalData, 8);
                binH = le32(m.binOriginalData, 12);
            }
            if (dir.equals("up")) capY += (expand ? -n : n);
            if (dir.equals("down")) capY += (expand ? 0 : n);
            if (dir.equals("left")) capX += (expand ? -n : n);
            if (dir.equals("right")) capX += (expand ? 0 : n);
            if (expand && (capX < 0 || capY < 0 || capX + newCols > binW || capY + newRows > binH)) {
                throw new IOException("❌ 禁止扩展：截取窗口已占满底图（底图 " + binW + "×" + binH + "）");
            }
            if (capX < 0) capX = 0;
            if (capY < 0) capY = 0;
        }

        // 新窗口的地形 / 省规划 / 归属
        int coordBaseOld = m.coordBase;
        int coordBaseNew = capY * newCols + capX;
        int[] dims = m.binOriginalData != null ? binDims(m.binOriginalData) : null;
        java.util.List<TerrainTile> newTiles = new java.util.ArrayList<>(newTotal);
        int[] newProv = new int[newTotal];
        byte[] newBelong = new byte[newTotal];
        java.util.Arrays.fill(newProv, 0xFFFF);
        java.util.Arrays.fill(newBelong, (byte) 0xFF);
        for (int i = 0; i < newTotal; i++) {
            int old = map[i];
            if (old >= 0 && old < m.tiles.size()) {
                newTiles.add(m.tiles.get(old));                 // 旧格沿用（含未保存的涂改）
                int pv = (m.provinces != null && old < m.provinces.length) ? m.provinces[old] : 0xFFFF;
                if (pv == 0 || pv == 0xFFFF) newProv[i] = pv;
                else if (pv == old) newProv[i] = i;
                else newProv[i] = (pv < oldToNew.length && oldToNew[pv] >= 0) ? oldToNew[pv] : i;
                if (m.belongs != null && old < m.belongs.length) newBelong[i] = m.belongs[old];
            } else {
                // 新格：有底图就按新窗口位置从底图读那一格，否则用所选填充地形
                TerrainTile t = null;
                if (dims != null) {
                    int bw = dims[0], bh = dims[1], hs = dims[2];
                    int wx = (i % newCols) + capX, wy = (i / newCols) + capY;
                    if (wx >= 0 && wy >= 0 && wx < bw && wy < bh) {
                        int off = hs + (wy * bw + wx) * 16;
                        if (off + 16 <= m.binOriginalData.length) {
                            t = new TerrainTile();
                            t.parseFromBytes(m.binOriginalData, off);
                        }
                    }
                }
                if (t == null) t = makeFillTile(fillGid, fillTid);
                newTiles.add(t);
                newProv[i] = i;                                 // 新格自己就是省代表格
                newBelong[i] = (byte) 0xFF;
            }
        }

        // 两条路径统一走 rebuildBtl：它会「坐标在旧图内但被裁掉 → 丢弃并改小头部计数」，
        // 其余情况原样保留，保证输出长度恒等于头部声明。
        byte[] rebuilt = rebuildBtl(m, h, newTiles, newProv, newBelong, oldToNew,
                newCols, newRows, newTotal, true, coordBaseOld, coordBaseNew, capX, capY);
        replaceWith(m, rebuilt);
        m.tiles = newTiles;                 // 征服的 BTL 没有地形段，必须换回重算好的这套
        m.buildTerrainPatterns();
        // ★ 不再调用 normalizeWaterDistricts：熊的 _resize_map 里新格子的省区是
        //   「自己的格号」（new_districts.append({'地块编号': new_id})），**不会**因为填的是
        //   海洋就改成 0xFFFF。官方数据也证明这条规则不成立（官方 stage10101 有 78 格、
        //   stage10201 有大量海洋格保留着省区）。手动整理仍可用「校验并修复」。
    }

    /**
     * 整张世界底图 BIN 的扩展/收缩（对应枭雄「🌍 打开世界地图」里的 WASD/IJKL）。
     * 与 BTL 扩缩的区别：这里改的是 BIN 自身的宽高，底图真的变大/变小。
     * <ul>
     *   <li>头部：YSAE 格式改 0x8/0xC，裸格式改 0x0/0x4（tag/version 原样保留）；</li>
     *   <li>地形段：旧格按方向整体平移，新格填所选地形（海洋格省规划写 0xFFFF）；</li>
     *   <li>省规划段（16B 头 + 宽×高×16 之后，2 字节/格）：值是「省代表格的底图绝对格号」，
     *       同步重映射；新格自身即代表格；</li>
     *   <li>binOriginalData 换成新数据，保存时 saveAsBIN 直接沿用（不会退回旧尺寸）。</li>
     * </ul>
     */
    public static void resizeBin(MapData m, String dir, int n, boolean expand,
                                 int fillGid, int fillTid) throws IOException {
        if (m == null || m.binOriginalData == null) throw new IOException("请先打开世界底图 BIN");
        byte[] out = resizeBinBytes(m.binOriginalData, dir, n, expand, fillGid, fillTid);
        int[] dims = binDims(out);
        int headerSize = dims[2];
        int newCols = dims[0], newRows = dims[1];
        int newTotal = newCols * newRows;
        int newProvOff = headerSize + newTotal * 16;

        // 内存模型同步
        m.binOriginalData = out;
        m.width = newCols;
        m.height = newRows;
        java.util.List<TerrainTile> tiles = new java.util.ArrayList<>(newTotal);
        java.util.List<Integer> bIds = new java.util.ArrayList<>(newTotal);
        java.util.List<Integer> colors = new java.util.ArrayList<>(newTotal);
        for (int i = 0; i < newTotal; i++) {
            TerrainTile t = new TerrainTile();
            t.parseFromBytes(out, headerSize + i * 16);
            tiles.add(t);
            bIds.add(0);
            colors.add(0);
        }
        m.tiles = tiles;
        m.buildingIds = bIds;
        m.sampledColors = colors;
        m.ensureProvincesSize();
        for (int i = 0; i < newTotal; i++) m.provinces[i] = le16(out, newProvOff + i * 2);
        m.editedCells.clear();
        m.binDirty = true;
        m.buildTerrainPatterns();
    }

    /**
     * 世界底图 BIN 的尺寸重建（纯字节变换，不动 MapData）。
     * 抽出来是为了让「枭雄式无损扩图」能先把底图撑大、再按新底图重算征服窗口，
     * 而不会在中间把 MapData 的 width/height 改成底图尺寸。
     *
     * @return 新的 BIN 字节（头部宽高已更新，地形段与省规划段按新尺寸重建）
     */
    private static byte[] resizeBinBytes(byte[] bin, String dir, int n, boolean expand,
                                         int fillGid, int fillTid) throws IOException {
        if (bin == null) throw new IOException("请先打开世界底图 BIN");
        if (n <= 0) throw new IOException("数量必须大于 0");
        int[] dims = binDims(bin);
        if (dims == null) throw new IOException("BIN 地形数据不完整");
        int cols = dims[0], rows = dims[1], headerSize = dims[2];
        boolean vertical = dir.equals("up") || dir.equals("down");
        int newCols = cols, newRows = rows;
        if (vertical) newRows = expand ? rows + n : rows - n;
        else newCols = expand ? cols + n : cols - n;
        if (newCols < 1 || newRows < 1) throw new IOException("收缩数量超过当前尺寸");
        if (!expand && n >= (vertical ? rows : cols)) {
            throw new IOException("收缩数量不能超过或等于当前" + (vertical ? "行数" : "列数"));
        }
        int oldTotal = cols * rows, newTotal = newCols * newRows;
        // 省规划段的值是「代表格的底图绝对格号」，也是 2 字节，同样有 65535 格上限
        if (newTotal > 0xFFFF) {
            throw new IOException("扩到 " + newCols + "×" + newRows + " = " + newTotal
                    + " 格，超过底图能表示的 65535 格上限（省规划是 2 字节）");
        }
        if (headerSize + oldTotal * 16 > bin.length) {
            throw new IOException("BIN 地形数据不完整");
        }

        int[] map = new int[newTotal];
        java.util.Arrays.fill(map, -1);
        fillMapping(map, dir, n, cols, rows, newCols, newRows, expand);
        int[] oldToNew = new int[oldTotal];
        java.util.Arrays.fill(oldToNew, -1);
        for (int i = 0; i < newTotal; i++) if (map[i] >= 0) oldToNew[map[i]] = i;

        // 旧省规划段（可能缺失：某些底图只写到地形段为止）
        int oldProvOff = headerSize + oldTotal * 16;
        int[] oldProv = new int[oldTotal];
        for (int i = 0; i < oldTotal; i++) {
            int a = oldProvOff + i * 2;
            oldProv[i] = (a + 2 <= bin.length) ? le16(bin, a) : 0xFFFF;
        }

        byte[] out = new byte[headerSize + newTotal * 16 + newTotal * 2];
        System.arraycopy(bin, 0, out, 0, Math.min(headerSize, bin.length));
        if (headerSize == 16) {                     // YSAE：宽@0x8 高@0xC
            put32(out, 8, newCols);
            put32(out, 12, newRows);
        } else {                                     // 裸格式：宽@0x0 高@0x4
            put32(out, 0, newCols);
            put32(out, 4, newRows);
        }
        int newProvOff = headerSize + newTotal * 16;
        boolean fillSea = (fillGid == 1);
        TerrainTile fillTile = makeFillTile(fillGid, fillTid);
        for (int i = 0; i < newTotal; i++) {
            int np;
            int old = map[i];
            if (old >= 0) {
                System.arraycopy(bin, headerSize + old * 16, out, headerSize + i * 16, 16);
                int pv = oldProv[old];
                if (pv == 0 || pv == 0xFFFF) np = pv;
                else if (pv == old) np = i;
                else np = (pv < oldTotal && oldToNew[pv] >= 0) ? oldToNew[pv] : i;
            } else {
                fillTile.toBytes(out, headerSize + i * 16);
                np = fillSea ? 0xFFFF : i;
            }
            out[newProvOff + i * 2] = (byte) (np & 0xFF);
            out[newProvOff + i * 2 + 1] = (byte) ((np >>> 8) & 0xFF);
        }
        return out;
    }

    /**
     * 枭雄式「无损扩图」：**底图 +n，征服 +n**（对应小提示里的
     * 「征服地图允许扩图，缩图，但是尺寸不得超过底图。所以底图+n，征服+n就能实现无损扩图」）。
     *
     * <ol>
     *   <li>底图先在指定方向插入 n 行/列（新格填 fillGid/fillTid）；</li>
     *   <li>征服窗口在同一方向增长 n，但 **截取X/Y 保持不变**（枭雄做法：窗口原点不动、内容在局部坐标里平移），
     *       新格按新窗口位置重新从新底图读；</li>
     *   <li>内容段（建筑/兵种/陷阱/… 的世界坐标）一个字节都不动 → 老城市/兵种仍落在原来的地形上。</li>
     * </ol>
     * 与普通「扩展地图」的区别：普通扩展只挪窗口原点、底图不动（受底图边缘余量限制）；
     * 本方法把底图也撑大，因此没有余量限制，代价是必须一并写出 world*.bin。
     */
    public static void expandConquestLossless(MapData m, String dir, int n,
                                              int fillGid, int fillTid) throws IOException {
        if (m == null || m.btlOriginalData == null) throw new IOException("请先打开征服 BTL");
        BtlHeaderInfo h = parseBTLHeader(m.btlOriginalData);
        if (h.independentTerrain) {
            throw new IOException("这张 BTL 自带地形（战役/新建），地形就在 BTL 里，直接扩缩窗口即可，不需要无损扩图");
        }
        if (m.binOriginalData == null) {
            throw new IOException("未加载世界底图 BIN，无法执行无损扩图");
        }
        if (n <= 0) throw new IOException("数量必须大于 0");

        // ① 底图 +n（只改字节，先不动 MapData 的窗口尺寸）
        byte[] newBin = resizeBinBytes(m.binOriginalData.clone(), dir, n, true, fillGid, fillTid);
        m.binOriginalData = newBin;

        // ② 征服窗口同方向 +n，截取X/Y 不变
        int cols = m.width, rows = m.height;
        if (cols <= 0 || rows <= 0) throw new IOException("窗口尺寸无效");
        boolean vertical = dir.equals("up") || dir.equals("down");
        int newCols = vertical ? cols : cols + n;
        int newRows = vertical ? rows + n : rows;
        int newTotal = newCols * newRows;
        if (newTotal > 0xFFFF) {
            throw new IOException("窗口扩到 " + newCols + "×" + newRows + " = " + newTotal
                    + " 格，超过 BTL 能表示的 65535 格上限（地块坐标是 2 字节）");
        }
        int[] map = new int[newTotal];
        java.util.Arrays.fill(map, -1);
        fillMapping(map, dir, n, cols, rows, newCols, newRows, true);
        int[] oldToNew = new int[cols * rows];
        java.util.Arrays.fill(oldToNew, -1);
        for (int i = 0; i < newTotal; i++) if (map[i] >= 0) oldToNew[map[i]] = i;

        // ③ 新窗口的地形 / 省规划 / 归属（新格按新窗口位置从新底图读）
        int capX = h.captureX, capY = h.captureY;
        int[] bd = binDims(newBin);
        int bw = bd[0], bh = bd[1], hs = bd[2];
        java.util.List<TerrainTile> newTiles = new java.util.ArrayList<>(newTotal);
        int[] newProv = new int[newTotal];
        byte[] newBelong = new byte[newTotal];
        java.util.Arrays.fill(newProv, 0xFFFF);
        java.util.Arrays.fill(newBelong, (byte) 0xFF);
        for (int i = 0; i < newTotal; i++) {
            int old = map[i];
            if (old >= 0 && old < m.tiles.size()) {
                newTiles.add(m.tiles.get(old));                     // 旧格沿用（含未保存的涂改）
                int pv = (m.provinces != null && old < m.provinces.length) ? m.provinces[old] : 0xFFFF;
                if (pv == 0 || pv == 0xFFFF) newProv[i] = pv;
                else if (pv == old) newProv[i] = i;
                else newProv[i] = (pv < oldToNew.length && oldToNew[pv] >= 0) ? oldToNew[pv] : i;
                if (m.belongs != null && old < m.belongs.length) newBelong[i] = m.belongs[old];
            } else {
                int lx = i % newCols, ly = i / newCols;
                int wx = lx + capX, wy = ly + capY;
                TerrainTile t = null;
                if (wx >= 0 && wy >= 0 && wx < bw && wy < bh) {
                    int off = hs + (wy * bw + wx) * 16;
                    if (off + 16 <= newBin.length) {
                        t = new TerrainTile();
                        t.parseFromBytes(newBin, off);
                    }
                }
                if (t == null) t = makeFillTile(fillGid, fillTid);
                newTiles.add(t);
                newProv[i] = i;                                     // 新格自己就是省代表格
                newBelong[i] = (byte) 0xFF;
            }
        }

        // ④ 重建 BTL。关键：内容坐标必须跟着地形一起平移（rebuildBtl 的 remapCoord=true），
        //    否则地形整体下移 n 格而城市/兵种原地不动，单位就会落到别的地形上。
        //    rebuildBtl 不动头部 0x08/0x0C，正好符合枭雄「窗口原点不变」的做法。
        //    横向扩列会改变地图长，坐标基准 captureY*长+captureX 随宽度变，所以要分开传新旧基准。
        int coordBaseOld = m.coordBase;                     // 旧基准：把文件里的旧坐标换算成本地格号
        int coordBaseNew = capY * newCols + capX;           // 新基准：把新本地格号写回成坐标
        m.coordBase = coordBaseNew;                         // replaceWith 之后会按新文件重算
        byte[] rebuilt = rebuildBtl(m, h, newTiles, newProv, newBelong, oldToNew,
                newCols, newRows, newTotal, true, coordBaseOld, coordBaseNew, -1, -1);
        replaceWith(m, rebuilt);
        m.tiles = newTiles;
        m.buildTerrainPatterns();
        // 底图尺寸变了，保存时必须一并写出 world*.bin（不能只看 editedCells）
        m.binDirty = true;
    }

    /**
     * 由世界底图 BIN 生成配套的「征服 BTL」（对应枭雄保存世界地图后自动生成的 xxx.btl）。
     * 约定与官方 conquest*.btl 一致：v3、截取 (0,2)、窗口 = 底图宽 × (底图高-4)。
     * 2 个中立军团、无建筑/兵种、省规划全 0xFFFF、归属全 0xFF，方便直接进编辑器继续加内容。
     */
    public static byte[] createConquestBtlForBin(int binW, int binH, int mapId) {
        int w = Math.max(1, binW);
        int h = Math.max(1, binH - 4);
        int total = w * h;
        int headerSize = 128, legionBytes = 2 * 300;
        int adminStart = headerSize + legionBytes;
        int ownStart = adminStart + total * 2;
        byte[] out = new byte[ownStart + total];
        ByteBuffer bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(0x00, 3);        // btl 版本 v3（与官方 conquest*.btl 一致）
        bb.putInt(0x04, mapId);    // 地图序号 → def_map.xml 的 id
        bb.putInt(0x08, 0);        // 截取X
        bb.putInt(0x0C, 2);        // 截取Y（官方固定 2）
        bb.putInt(0x10, w);
        bb.putInt(0x14, h);
        bb.putInt(0x18, 2);        // 军团总数
        bb.putInt(0x1C, 0);        // 建筑总数
        bb.putInt(0x20, 0);        // 兵种总数
        bb.putInt(0x30, 1);        // 关卡胜利条件
        bb.putInt(0x34, 999);      // 最小回合
        bb.putInt(0x38, 999);      // 最大回合
        bb.putInt(0x54, 1);
        bb.putInt(0x58, total);    // 地块总数
        // 两个中立军团（国家 1 / 3），归属值 1/2 都合法
        bb.putInt(headerSize, 1);
        bb.putInt(headerSize + 0x04, 1);
        bb.putInt(headerSize + 300, 2);
        bb.putInt(headerSize + 300 + 0x04, 3);
        // 省规划：全 0xFFFF（无省区）
        for (int i = 0; i < total; i++) {
            out[adminStart + i * 2] = (byte) 0xFF;
            out[adminStart + i * 2 + 1] = (byte) 0xFF;
        }
        // 军团归属：全 0xFF（中立）
        for (int i = 0; i < total; i++) out[ownStart + i] = (byte) 0xFF;
        return out;
    }

    /** 供 UI 查询世界底图尺寸，返回 {宽, 高}；无法识别返回 null。 */
    public static int[] worldBinSize(byte[] data) {
        int[] d = binDims(data);
        return d == null ? null : new int[]{d[0], d[1]};
    }

    /** 重建整份 BTL（战役：地形内嵌；内容坐标按映射重排）。 */
    /**
     * 重建 BTL 的核心。coordBaseOld 用于把文件里的旧坐标换算成本地格号，
     * coordBaseNew 用于把新本地格号写回成坐标 —— 横向扩列改变「地图长」时两者不同。
     *
     * <p><b>记录丢弃规则</b>：只有「坐标原本落在旧图内、但被裁掉了」（收缩裁边）才丢弃该记录，
     * 并把对应段的头部计数改小；坐标本来就不在旧图范围的（首都的小索引、放置接口的
     * 0xCDCD 填充值、越界值）一律原样保留。这样输出长度恒等于头部声明，游戏不会读越界。
     */
    private static byte[] rebuildBtl(MapData m, BtlHeaderInfo h,
                                     java.util.List<TerrainTile> newTiles, int[] newProv,
                                     byte[] newBelong, int[] oldToNew,
                                     int newW, int newH, int newTotal, boolean remapCoord,
                                     int coordBaseOld, int coordBaseNew,
                                     int capXOverride, int capYOverride) {
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
        // 征服「只挪窗口」时截取 X/Y 要跟着走；战役 / 无损扩图传 -1 表示保持原值
        if (capXOverride >= 0) put32(header, 0x08, capXOverride);
        if (capYOverride >= 0) put32(header, 0x0C, capYOverride);
        out.write(header, 0, 128);
        byte[] legionBuf = padFrom(src, 128, h.legionCount * 300);  // 军团段
        out.write(legionBuf, 0, legionBuf.length);
        if (h.independentTerrain) {                                 // 地形段（仅自带地形的战役）
            byte[] buf = new byte[newTotal * 16];
            for (int i = 0; i < newTotal; i++) newTiles.get(i).toBytes(buf, i * 16);
            out.write(buf, 0, buf.length);
        }
        byte[] prov = new byte[newTotal * 2];                       // 省规划
        for (int i = 0; i < newTotal; i++) {
            int pv = newProv[i];
            int stored = (pv == 0 || pv == 0xFFFF) ? pv : pv + coordBaseNew;
            prov[i * 2] = (byte) (stored & 0xFF);
            prov[i * 2 + 1] = (byte) ((stored >>> 8) & 0xFF);
        }
        out.write(prov, 0, prov.length);
        out.write(newBelong, 0, newBelong.length);                  // 归属

        int bldKept = 0;                                            // 建筑（32B/条）
        for (int i = 0; i < h.buildingCount; i++) {
            byte[] rec = readRec(src, h.buildingStart + i * 32, 32);
            if (remapCoord && !remapInPlace(rec, 0, 2, -1, oldToNew, coordBaseOld, coordBaseNew, 0)) continue;
            out.write(rec, 0, 32);
            bldKept++;
        }
        int armyStart = h.buildingStart + h.buildingCount * 32;
        int armyKept = 0;                                           // 兵种
        for (int i = 0; i < h.armyCount; i++) {
            byte[] rec = readRec(src, armyStart + i * armyRec, armyRec);
            if (remapCoord && !remapInPlace(rec, 0, 2, -1, oldToNew, coordBaseOld, coordBaseNew, 0)) continue;
            out.write(rec, 0, armyRec);
            armyKept++;
        }
        int mineStart = armyStart + h.armyCount * armyRec;
        int mineKept = 0;                                           // 陷阱
        for (int i = 0; i < h.mineCount; i++) {
            byte[] rec = readRec(src, mineStart + i * 12, 12);
            if (remapCoord && !remapInPlace(rec, 0, 2, -1, oldToNew, coordBaseOld, coordBaseNew, 0)) continue;
            out.write(rec, 0, 12);
            mineKept++;
        }
        int planStart = mineStart + h.mineCount * 12;               // 方案 + 天气 + 事件（无地块坐标，不丢）
        int eventEnd = m.eventStart + h.eventCount * 44;
        byte[] midBuf = padFrom(src, planStart, Math.max(0, eventEnd - planStart));
        out.write(midBuf, 0, midBuf.length);

        int reinfKept = copyWithCoord(out, src, m.reinforceStart, h.reinforceCount, reinfRec,
                0, 2, -1, oldToNew, coordBaseOld, coordBaseNew, remapCoord);
        int airKept = copyWithCoord(out, src, m.airstrikeStart, h.airstrikeCount, 20,
                0, 4, -1, oldToNew, coordBaseOld, coordBaseNew, remapCoord);
        int paKept = copyWithCoord(out, src, m.placementAStart, h.placementCountA, 8,
                0, 4, 5, oldToNew, coordBaseOld, coordBaseNew, remapCoord);
        int pbKept = copyWithCoord(out, src, m.placementBStart, h.placementCountB, 8,
                0, 4, 5, oldToNew, coordBaseOld, coordBaseNew, remapCoord);
        copyWithCoord(out, src, m.strategyStart, h.strategyCount, 16,
                -1, 0, -1, oldToNew, coordBaseOld, coordBaseNew, false);
        copyWithCoord(out, src, m.airSupportStart, h.airSupportCount, 16,
                -1, 0, -1, oldToNew, coordBaseOld, coordBaseNew, false);
        int capKept = copyWithCoord(out, src, m.capitalStart, h.capitalCount, 4,
                0, 4, -1, oldToNew, coordBaseOld, coordBaseNew, remapCoord);
        int tailStart = m.capitalStart + h.capitalCount * 4;        // v3 特殊胜利条件尾字节
        if (tailStart < src.length) out.write(src, tailStart, src.length - tailStart);

        // 有记录被裁掉时同步改小头部计数，保证「声明长度 == 实际长度」
        byte[] result = out.toByteArray();
        put32(result, 0x1C, bldKept);
        put32(result, 0x20, armyKept);
        put32(result, 0x68, mineKept);
        put32(result, 0x3C, reinfKept);
        put32(result, 0x40, airKept);
        put32(result, 0x44, paKept);
        put32(result, 0x48, pbKept);
        put32(result, 0x4C, capKept);
        return result;
    }

    /** 取一条记录；源文件缺这一段就返回全零记录（长度仍按头部计数写出，不会让文件变短）。 */
    private static byte[] readRec(byte[] src, int off, int size) {
        byte[] rec = new byte[Math.max(0, size)];
        if (off >= 0 && off + size <= src.length) System.arraycopy(src, off, rec, 0, size);
        return rec;
    }

    /**
     * 就地重映射一条记录的坐标（可选同时重排序号）。
     *
     * @return false = 「坐标原本落在旧图内、但被裁掉了」→ 调用方应丢弃该记录并改小头部计数；
     *         true  = 保留（重映射成功，或坐标本就不在旧图范围 → 原样保留不重映射）。
     */
    private static boolean remapInPlace(byte[] rec, int coordOff, int coordBytes, int seqOff,
                                        int[] oldToNew, int coordBaseOld, int coordBaseNew, int seqVal) {
        if (coordOff >= 0 && coordOff + coordBytes <= rec.length) {
            int raw = (coordBytes == 2) ? le16(rec, coordOff) : le32(rec, coordOff);
            int c = raw - coordBaseOld;
            int nw = (c >= 0 && c < oldToNew.length) ? oldToNew[c] : -1;
            // ★ 完全按熊的可视化编辑器 map_extension.py::_resize_map 的语义：
            //   查不到新坐标（含坐标越界、0xCDCD 之类的垃圾值）就**丢弃这条记录**，
            //   头部计数由调用方同步改小。熊正是靠这一条把老图里那些坐标是垃圾的
            //   兵种放置接口（放置甲/乙）整段清掉的 —— 官方战役图的放置段也确实都是 0。
            if (nw < 0) return false;
            int v = nw + coordBaseNew;
            if (coordBytes == 2) writeCoord(rec, coordOff, v);
            else put32(rec, coordOff, v);
        }
        if (seqOff >= 0 && seqVal >= 0 && seqOff + 2 <= rec.length) {
            rec[seqOff] = (byte) (seqVal & 0xFF);
            rec[seqOff + 1] = (byte) ((seqVal >>> 8) & 0xFF);
        }
        return true;
    }

    /** 从 src[off] 起取 len 字节；源文件不够就补零（绝不让输出短于头部声明的长度）。 */
    private static byte[] padFrom(byte[] src, int off, int len) {
        byte[] out = new byte[Math.max(0, len)];
        if (off < 0 || len <= 0) return out;
        int avail = Math.min(len, Math.max(0, src.length - off));
        if (avail > 0) System.arraycopy(src, off, out, 0, avail);
        return out;
    }

    /**
     * 复制一段记录，可选对坐标字段重映射、并对 0x?? 的序号字段重排。
     * 读旧坐标用 coordBaseOld、写新坐标用 coordBaseNew —— 横向扩列会改变「地图长」，
     * 坐标基准 captureY*长+captureX 随之变化，两个基准必须分开。
     *
     * <p><b>绝不无故丢弃记录</b>：只有「原本在旧图内、但被裁掉」的才丢（返回的保留条数供调用方改头部计数），
     * 坐标本就不在旧图范围的一律原样保留。源文件缺这一段则补零写出，保证长度与声明一致。
     *
     * @return 实际写出的记录条数
     */
    private static int copyWithCoord(java.io.ByteArrayOutputStream out, byte[] src, int start,
                                     int count, int size, int coordOff, int coordBytes, int seqOff,
                                     int[] oldToNew, int coordBaseOld, int coordBaseNew, boolean remap) {
        int kept = 0;
        for (int i = 0; i < count; i++) {
            byte[] rec = readRec(src, start + i * size, size);
            if (remap && coordOff >= 0) {
                if (!remapInPlace(rec, coordOff, coordBytes, seqOff, oldToNew, coordBaseOld, coordBaseNew, kept)) {
                    continue;                                  // 被裁掉 → 丢弃（计数不增）
                }
            } else if (seqOff >= 0 && seqOff + 2 <= rec.length) {
                rec[seqOff] = (byte) (kept & 0xFF);
                rec[seqOff + 1] = (byte) ((kept >>> 8) & 0xFF);
            }
            out.write(rec, 0, size);
            kept++;
        }
        return kept;
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
