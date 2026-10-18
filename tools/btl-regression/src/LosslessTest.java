import com.xckeji.bj.file.FileParser;
import com.xckeji.bj.model.MapData;
import java.nio.file.*;
import java.util.*;

/**
 * 枭雄式「无损扩图」回归：底图 +n、征服窗口 +n（截取X/Y 不变）。
 *   java LosslessTest <conquest.btl> <world.bin> [n]
 * 断言：
 *   ① 窗口 = 原窗口宽 x (原高+n)；底图 = 原底图宽 x (原高+n)；截取 X/Y 不变
 *   ② 老窗口地形整体下移 n 行，逐格内容一致（无损）
 *   ③ 新窗口每一格都与底图对应位置一致（编辑器所见 = 游戏所见）
 *   ④ 建筑/兵种条数、类型、坐标逐条不变（世界坐标不动 → 仍落在原地形上）
 */
public class LosslessTest {
    static int fails = 0;
    static void check(boolean ok, String what) {
        System.out.println((ok ? "  [OK] " : "  [FAIL] ") + what);
        if (!ok) fails++;
    }
    static int le16(byte[] d, int o) { return (d[o] & 0xFF) | ((d[o + 1] & 0xFF) << 8); }
    static int le32(byte[] d, int o) {
        return (d[o] & 0xFF) | ((d[o + 1] & 0xFF) << 8) | ((d[o + 2] & 0xFF) << 16) | ((d[o + 3] & 0xFF) << 24);
    }
    static boolean same16(byte[] a, int ao, byte[] b, int bo) {
        for (int i = 0; i < 16; i++) if (a[ao + i] != b[bo + i]) return false;
        return true;
    }
    /** {坐标, 类型, 类别 0建筑/1兵种} */
    static List<int[]> content(byte[] d, FileParser.BtlHeaderInfo h) {
        List<int[]> out = new ArrayList<>();
        for (int k = 0; k < h.buildingCount; k++) {
            int p = h.buildingStart + k * 32;
            out.add(new int[]{le16(d, p), d[p + 4] & 0xFF, 0});
        }
        int as = h.buildingStart + h.buildingCount * 32;
        int rec = h.version >= 2 ? 64 : 48;
        for (int k = 0; k < h.armyCount; k++) {
            int p = as + k * rec;
            out.add(new int[]{le16(d, p), d[p + 2] & 0xFF, 1});
        }
        return out;
    }

    public static void main(String[] args) throws Exception {
        byte[] btl0 = Files.readAllBytes(Paths.get(args[0]));
        byte[] bin0 = Files.readAllBytes(Paths.get(args[1]));
        int n = args.length > 2 ? Integer.parseInt(args[2]) : 10;
        String dir = args.length > 3 ? args[3] : "up";
        boolean vertical = dir.equals("up") || dir.equals("down");
        FileParser.BtlHeaderInfo h0 = FileParser.parseBTLHeader(btl0);
        int W = h0.width, H = h0.height, capX = h0.captureX, capY = h0.captureY;
        int[] bd = FileParser.worldBinSize(bin0);
        int binW = bd[0], binH = bd[1];
        System.out.println("征服 " + W + "x" + H + " 截取(" + capX + "," + capY + ")  底图 " + binW + "x" + binH
                + "  建筑" + h0.buildingCount + " 兵种" + h0.armyCount
                + "  向" + dir + " 无损扩 n=" + n);
        int expW = vertical ? W : W + n;
        int expH = vertical ? H + n : H;
        int expBinW = vertical ? binW : binW + n;
        int expBinH = vertical ? binH + n : binH;
        int shift = dir.equals("up") || dir.equals("left") ? n : 0;   // 内容在局部坐标里的平移量

        MapData m = FileParser.loadFile(btl0.clone(), "c.btl");
        m.binOriginalData = bin0.clone();
        FileParser.loadConquestTerrain(m, bin0.clone());

        byte[][] before = new byte[W * H][];
        for (int i = 0; i < W * H; i++) {
            byte[] t = new byte[16];
            m.tiles.get(i).toBytes(t, 0);
            before[i] = t;
        }
        List<int[]> c0 = content(btl0, h0);

        FileParser.expandConquestLossless(m, dir, n, 1, 0);

        byte[] btl1 = m.btlOriginalData, bin1 = m.binOriginalData;
        FileParser.BtlHeaderInfo h1 = FileParser.parseBTLHeader(btl1);
        int nW = h1.width, nH = h1.height, bW = le32(bin1, 8), bH = le32(bin1, 12);

        check(nW == expW && nH == expH, "窗口 " + nW + "x" + nH + " 期望 " + expW + "x" + expH);
        check(bW == expBinW && bH == expBinH, "底图 " + bW + "x" + bH + " 期望 " + expBinW + "x" + expBinH);
        check(h1.captureX == capX && h1.captureY == capY,
                "截取 X/Y 不变 = (" + h1.captureX + "," + h1.captureY + ")");
        check(bin1.length == 16 + bW * bH * 16 + bW * bH * 2,
                "底图长度自洽 " + bin1.length);

        // 老窗口地形应在新窗口里整体平移 shift，逐格一致
        int bad = 0;
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int ny = y + (vertical ? shift : 0);
                int nx = x + (vertical ? 0 : shift);
                byte[] t = new byte[16];
                m.tiles.get(ny * nW + nx).toBytes(t, 0);
                if (!Arrays.equals(t, before[y * W + x])) bad++;
            }
        }
        check(bad == 0, "老窗口地形整体平移 " + shift + " 格、逐格一致（不符 " + bad + "）");

        int mm = 0, firstBadRow = -1;
        for (int y = 0; y < nH; y++) {
            for (int x = 0; x < nW; x++) {
                int bo = 16 + ((y + capY) * bW + (x + capX)) * 16;
                byte[] t = new byte[16];
                m.tiles.get(y * nW + x).toBytes(t, 0);
                if (!same16(t, 0, bin1, bo)) { mm++; if (firstBadRow < 0) firstBadRow = y; }
            }
        }
        check(mm == 0, "新窗口每格与底图对应位置一致（不符 " + mm
                + (firstBadRow >= 0 ? "，首个 row=" + firstBadRow : "") + "）");

        List<int[]> c1 = content(btl1, h1);
        check(c1.size() == c0.size(), "内容条数 " + c1.size() + " / " + c0.size());
        int typeBad = 0, kindBad = 0;
        for (int k = 0; k < Math.min(c0.size(), c1.size()); k++) {
            if (c0.get(k)[1] != c1.get(k)[1]) typeBad++;
            if (c0.get(k)[2] != c1.get(k)[2]) kindBad++;
        }
        check(typeBad == 0, "建筑/兵种类型字段逐条不变（异常 " + typeBad + "）");
        check(kindBad == 0, "记录顺序不变（异常 " + kindBad + "）");

        // 核心：内容坐标要跟着地形一起平移，单位必须仍落在同一块地形上
        int cbOld = capY * W + capX, cbNew = capY * expW + capX;
        int glued = 0, misaligned = 0, out = 0, moved = 0;
        int firstBad = -1;
        for (int k = 0; k < Math.min(c0.size(), c1.size()); k++) {
            int l0 = c0.get(k)[0] - cbOld, l1 = c1.get(k)[0] - cbNew;
            if (c1.get(k)[0] != c0.get(k)[0]) moved++;
            if (l0 < 0 || l0 >= W * H || l1 < 0 || l1 >= expW * expH) { out++; continue; }
            byte[] t = new byte[16];
            m.tiles.get(l1).toBytes(t, 0);
            if (Arrays.equals(t, before[l0])) glued++;
            else { misaligned++; if (firstBad < 0) firstBad = k; }
        }
        check(out == 0, "所有内容坐标仍落在窗口内（越界 " + out + "）");
        check(misaligned == 0, "每条记录仍落在同一块地形上（对齐 " + glued
                + " / 错位 " + misaligned + (firstBad >= 0 ? "，首个 #" + firstBad : "") + "）");
        System.out.println("  参考：内容坐标被平移了 " + moved + " / " + c0.size() + " 条（跟随地形）");

        int ocean = 0;
        for (int y = 0; y < n; y++) for (int x = 0; x < nW; x++) {
            byte[] t = new byte[16];
            m.tiles.get(y * nW + x).toBytes(t, 0);
            if ((t[0] & 0xFF) == 1) ocean++;
        }
        System.out.println("  参考：新增 " + n + " 行中 " + ocean + " 格是海洋（填充地形）；"
                + "底图顶部 " + capY + " 行不在窗口内（窗口自底图第 " + capY + " 行起）");

        System.out.println(fails == 0 ? "\n全部通过" : "\n失败 " + fails + " 项");
        if (fails != 0) System.exit(1);
    }
}
