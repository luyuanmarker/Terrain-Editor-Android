import com.xckeji.bj.file.FileParser;
import com.xckeji.bj.model.MapData;
import java.nio.file.*;
import java.util.*;

/**
 * 世界底图 BIN「本体扩缩」+「配套征服 BTL 生成」的无头回归。
 *   java BinResize <world.bin>
 * 断言：尺寸/长度/头部宽高、旧格整体平移、新格填充与省规划、扩缩往返一致、
 *       生成器头字段与段内容、生成物能被重新读回。
 */
public class BinResize {
    static int fails = 0;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "  [OK] " : "  [FAIL] ") + what);
        if (!ok) fails++;
    }

    static int le32(byte[] d, int o) {
        return (d[o] & 0xFF) | ((d[o + 1] & 0xFF) << 8) | ((d[o + 2] & 0xFF) << 16) | ((d[o + 3] & 0xFF) << 24);
    }

    static int le16(byte[] d, int o) {
        return (d[o] & 0xFF) | ((d[o + 1] & 0xFF) << 8);
    }

    static boolean same16(byte[] a, int ao, byte[] b, int bo) {
        for (int i = 0; i < 16; i++) if (a[ao + i] != b[bo + i]) return false;
        return true;
    }

    public static void main(String[] args) throws Exception {
        byte[] bin0 = Files.readAllBytes(Paths.get(args[0]));
        int[] size = FileParser.worldBinSize(bin0);
        if (size == null) { System.out.println("不是可识别的世界底图 BIN"); return; }
        int hdr = (bin0[0] == 'Y') ? 16 : 8;
        int W = size[0], H = size[1];
        System.out.println("底图 " + W + "x" + H + "  头部 " + hdr + "B  长度 " + bin0.length
                + (bin0[0] == 'Y' ? "  (YSAE)" : "  (裸格式)"));

        // ==================== 1. 向上扩展 2 行 ====================
        System.out.println("\n[1] 向上扩展 2 行（填充海洋）");
        MapData m = FileParser.loadFile(bin0.clone(), "world.bin");
        FileParser.resizeMap(m, "up", 2, true, 1, 0);
        int nW = W, nH = H + 2;
        byte[] up = m.binOriginalData;
        check(m.width == nW && m.height == nH, "内存尺寸 " + m.width + "x" + m.height + " 期望 " + nW + "x" + nH);
        check(up.length == hdr + nW * nH * 16 + nW * nH * 2,
                "文件长度 " + up.length + " 期望 " + (hdr + nW * nH * 16 + nW * nH * 2));
        if (hdr == 16) check(le32(up, 8) == nW && le32(up, 12) == nH,
                "头部宽高 " + le32(up, 8) + "x" + le32(up, 12));

        int moved = 0, mism = 0;
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                if (same16(bin0, hdr + (y * W + x) * 16, up, hdr + ((y + 2) * nW + x) * 16)) moved++;
                else mism++;
            }
        }
        check(mism == 0, "旧地形整体下移 2 行（一致 " + moved + " / 不符 " + mism + "）");

        int fillOk = 0, fillBad = 0;
        for (int y = 0; y < 2; y++) for (int x = 0; x < nW; x++) {
            if ((up[hdr + (y * nW + x) * 16] & 0xFF) == 1) fillOk++; else fillBad++;
        }
        check(fillBad == 0, "新增 " + (2 * nW) + " 格地形=海洋（异常 " + fillBad + "）");

        int provOff = hdr + nW * nH * 16, pBad = 0;
        for (int y = 0; y < 2; y++) for (int x = 0; x < nW; x++) {
            if (le16(up, provOff + (y * nW + x) * 2) != 0xFFFF) pBad++;
        }
        check(pBad == 0, "新增格省规划=0xFFFF（异常 " + pBad + "）");

        // 省规划代表格重映射：旧值 pv → pv + 2*W（宽度不变，整体下移 2 行）
        int oldProvOff = hdr + W * H * 16;
        int provMoved = 0, provWrong = 0, provSpecial = 0;
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int oi = y * W + x;
                int pv = (oldProvOff + oi * 2 + 2 <= bin0.length) ? le16(bin0, oldProvOff + oi * 2) : 0xFFFF;
                int got = le16(up, provOff + ((y + 2) * nW + x) * 2);
                if (pv == 0 || pv == 0xFFFF) { if (got == pv) provSpecial++; else provWrong++; continue; }
                int want = (pv < W * H) ? pv + 2 * W : (y + 2) * nW + x;
                if (got == want) provMoved++; else provWrong++;
            }
        }
        check(provWrong == 0, "省规划代表格重映射（重映射 " + provMoved + " / 特殊值 " + provSpecial + " / 不符 " + provWrong + "）");

        // ==================== 2. 扩缩往返 ====================
        System.out.println("\n[2] 扩展 2 行 → 再按同方向收缩 2 行，应逐字节还原");
        MapData m2 = FileParser.loadFile(bin0.clone(), "world.bin");
        FileParser.resizeMap(m2, "up", 2, true, 1, 0);
        FileParser.resizeMap(m2, "up", 2, false, 1, 0);
        byte[] back = m2.binOriginalData;
        check(back.length == bin0.length, "长度 " + back.length + " == " + bin0.length);
        int tDiff = 0, pDiff = 0;
        int lim = Math.min(back.length, bin0.length);
        for (int i = 0; i < lim; i++) {
            if (back[i] == bin0[i]) continue;
            int terrainEnd = hdr + W * H * 16;
            if (i < terrainEnd) tDiff++; else pDiff++;
        }
        check(tDiff == 0, "地形段逐字节一致（差异 " + tDiff + "）");
        check(pDiff == 0, "省规划段逐字节一致（差异 " + pDiff + "）");

        System.out.println("\n[3] 向左扩展 1 列 → 再向左收缩 1 列，应逐字节还原");
        MapData m3 = FileParser.loadFile(bin0.clone(), "world.bin");
        FileParser.resizeMap(m3, "left", 1, true, 0, 0xFF);
        check(m3.width == W + 1 && m3.height == H, "扩展后 " + m3.width + "x" + m3.height);
        FileParser.resizeMap(m3, "left", 1, false, 0, 0xFF);
        byte[] back3 = m3.binOriginalData;
        int d3 = 0;
        for (int i = 0; i < Math.min(back3.length, bin0.length); i++) if (back3[i] != bin0[i]) d3++;
        check(back3.length == bin0.length && d3 == 0, "还原（长度 " + back3.length + "，差异 " + d3 + "）");

        // ==================== 4. 配套征服 BTL ====================
        System.out.println("\n[4] createConquestBtlForBin");
        int mapId = 1;
        byte[] btl = FileParser.createConquestBtlForBin(W, H, mapId);
        int winW = W, winH = Math.max(1, H - 4), total = winW * winH;
        check(btl.length == 128 + 600 + total * 2 + total, "长度 " + btl.length);
        check(le32(btl, 0x00) == 3, "版本 " + le32(btl, 0x00));
        check(le32(btl, 0x04) == mapId, "地图序号 " + le32(btl, 0x04));
        check(le32(btl, 0x08) == 0 && le32(btl, 0x0C) == 2,
                "截取 (" + le32(btl, 0x08) + "," + le32(btl, 0x0C) + ")");
        check(le32(btl, 0x10) == winW && le32(btl, 0x14) == winH,
                "窗口 " + le32(btl, 0x10) + "x" + le32(btl, 0x14));
        check(le32(btl, 0x58) == total, "地块总数 " + le32(btl, 0x58));
        check(le32(btl, 0x18) == 2, "军团数 " + le32(btl, 0x18));
        int adminStart = 128 + 600, ownStart = adminStart + total * 2;
        int aBad = 0;
        for (int i = 0; i < total; i++) if (le16(btl, adminStart + i * 2) != 0xFFFF) aBad++;
        check(aBad == 0, "省规划全 0xFFFF（异常 " + aBad + "）");
        int oBad = 0;
        for (int i = 0; i < total; i++) if ((btl[ownStart + i] & 0xFF) != 0xFF) oBad++;
        check(oBad == 0, "归属全 0xFF（异常 " + oBad + "）");

        MapData reload = FileParser.loadFile(btl.clone(), "world.btl");
        check(reload.width == winW && reload.height == winH,
                "生成物可被读回：" + reload.width + "x" + reload.height);
        check(reload.btlOriginalData != null && reload.binOriginalData == null,
                "生成物被识别为 BTL（无内嵌地形）");

        System.out.println(fails == 0 ? "\n全部通过" : "\n失败 " + fails + " 项");
        if (fails != 0) System.exit(1);
    }
}
