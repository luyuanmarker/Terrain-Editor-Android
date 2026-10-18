import com.xckeji.bj.file.FileParser;
import com.xckeji.bj.model.MapData;
import java.nio.file.*;
import java.util.*;

/**
 * 无损扩图后「记录级」核对：除了坐标字段（0x00-0x01）之外，每条建筑/兵种记录必须逐字节不变；
 * 坐标必须按映射一致地改写；重新载入保存结果后仍然成立。
 *   java LosslessFullTest <conquest.btl> <world.bin> [n] [dir]
 */
public class LosslessFullTest {
    static int fails = 0;
    static void check(boolean ok, String what) {
        System.out.println((ok ? "  [OK] " : "  [FAIL] ") + what);
        if (!ok) fails++;
    }
    static int le16(byte[] d, int o) { return (d[o] & 0xFF) | ((d[o + 1] & 0xFF) << 8); }

    static List<byte[]> recs(byte[] d, FileParser.BtlHeaderInfo h, boolean armies) {
        List<byte[]> out = new ArrayList<>();
        if (armies) {
            int rec = h.version >= 2 ? 64 : 48;
            int p0 = h.buildingStart + h.buildingCount * 32;
            for (int k = 0; k < h.armyCount; k++) {
                int p = p0 + k * rec;
                if (p + rec > d.length) break;
                out.add(Arrays.copyOfRange(d, p, p + rec));
            }
        } else {
            for (int k = 0; k < h.buildingCount; k++) {
                int p = h.buildingStart + k * 32;
                if (p + 32 > d.length) break;
                out.add(Arrays.copyOfRange(d, p, p + 32));
            }
        }
        return out;
    }

    /** 除坐标（0..1）外的字节是否完全一致 */
    static int nonCoordDiffs(byte[] a, byte[] b) {
        int n = Math.min(a.length, b.length), c = 0;
        for (int i = 2; i < n; i++) if (a[i] != b[i]) c++;
        return c;
    }

    public static void main(String[] args) throws Exception {
        byte[] btl0 = Files.readAllBytes(Paths.get(args[0]));
        byte[] bin0 = Files.readAllBytes(Paths.get(args[1]));
        int n = args.length > 2 ? Integer.parseInt(args[2]) : 10;
        String dir = args.length > 3 ? args[3] : "up";
        boolean vertical = dir.equals("up") || dir.equals("down");
        FileParser.BtlHeaderInfo h0 = FileParser.parseBTLHeader(btl0);
        int W = h0.width, H = h0.height, capX = h0.captureX, capY = h0.captureY;
        System.out.println(Paths.get(args[0]).getFileName() + "  v" + h0.version + "  " + W + "x" + H
                + "  截取(" + capX + "," + capY + ")  建筑" + h0.buildingCount
                + " 兵种" + h0.armyCount + "  向" + dir + " 扩 n=" + n);

        List<byte[]> bld0 = recs(btl0, h0, false);
        List<byte[]> arm0 = recs(btl0, h0, true);

        MapData m = FileParser.loadFile(btl0.clone(), "c.btl");
        m.binOriginalData = bin0.clone();
        FileParser.loadConquestTerrain(m, bin0.clone());
        FileParser.expandConquestLossless(m, dir, n, 1, 0);

        int expW = vertical ? W : W + n, expH = vertical ? H + n : H;
        int cbOld = capY * W + capX, cbNew = capY * expW + capX;

        System.out.println("\n[1] 扩完后：只有坐标字段变，其余字节必须不变");
        byte[] btl1 = m.btlOriginalData;
        FileParser.BtlHeaderInfo h1 = FileParser.parseBTLHeader(btl1);
        List<byte[]> bld1 = recs(btl1, h1, false);
        List<byte[]> arm1 = recs(btl1, h1, true);
        check(bld1.size() == bld0.size(), "建筑条数 " + bld1.size() + " / " + bld0.size());
        check(arm1.size() == arm0.size(), "兵种条数 " + arm1.size() + " / " + arm0.size());

        int bldNon = 0, armNon = 0, firstBad = -1;
        for (int k = 0; k < Math.min(bld0.size(), bld1.size()); k++) {
            int d = nonCoordDiffs(bld0.get(k), bld1.get(k));
            if (d > 0) { bldNon += d; if (firstBad < 0) firstBad = k; }
        }
        for (int k = 0; k < Math.min(arm0.size(), arm1.size()); k++) {
            int d = nonCoordDiffs(arm0.get(k), arm1.get(k));
            if (d > 0) { armNon += d; if (firstBad < 0) firstBad = k; }
        }
        check(bldNon == 0, "建筑记录除坐标外逐字节不变（差异字节 " + bldNon + "）");
        check(armNon == 0, "兵种记录除坐标外逐字节不变（差异字节 " + armNon
                + (firstBad >= 0 ? "，首个 #" + firstBad : "") + "）");

        System.out.println("\n[2] 坐标必须按映射一致改写（跟随地形；下/右扩不动）");
        int shift = dir.equals("up") || dir.equals("left") ? n : 0;
        int badCoord = 0;
        for (int k = 0; k < Math.min(bld0.size(), bld1.size()); k++) {
            int c0 = le16(bld0.get(k), 0), c1 = le16(bld1.get(k), 0);
            int r = c0 / 148, cc = c0 % 148;            // 底图坐标 → 行列（底图宽 148）
            r += vertical ? shift : 0;
            cc += vertical ? 0 : shift;
            int want = r * expW + cc;
            if (c1 != want) badCoord++;
        }
        for (int k = 0; k < Math.min(arm0.size(), arm1.size()); k++) {
            int c0 = le16(arm0.get(k), 0), c1 = le16(arm1.get(k), 0);
            int r = c0 / 148, cc = c0 % 148;
            r += vertical ? shift : 0;
            cc += vertical ? 0 : shift;
            int want = r * expW + cc;
            if (c1 != want) badCoord++;
        }
        check(badCoord == 0, "所有记录的坐标都改写正确（异常 " + badCoord + "）");

        System.out.println("\n[3] saveAsBTL 之后再核对一遍");
        byte[] out = FileParser.saveAsBTL(m);
        FileParser.BtlHeaderInfo h2 = FileParser.parseBTLHeader(out);
        List<byte[]> bld2 = recs(out, h2, false);
        List<byte[]> arm2 = recs(out, h2, true);
        check(bld2.size() == bld0.size() && arm2.size() == arm0.size(),
                "保存后条数 建筑 " + bld2.size() + " 兵种 " + arm2.size());
        int non2 = 0, i2 = -1;
        for (int k = 0; k < Math.min(bld0.size(), bld2.size()); k++) {
            int d = nonCoordDiffs(bld0.get(k), bld2.get(k));
            if (d > 0) { non2 += d; if (i2 < 0) i2 = k; }
        }
        for (int k = 0; k < Math.min(arm0.size(), arm2.size()); k++) {
            int d = nonCoordDiffs(arm0.get(k), arm2.get(k));
            if (d > 0) { non2 += d; if (i2 < 0) i2 = k; }
        }
        check(non2 == 0, "保存后除坐标外逐字节不变（差异字节 " + non2
                + (i2 >= 0 ? "，首个 #" + i2 : "") + "）");

        System.out.println("\n[4] 重新载入保存结果");
        MapData back = FileParser.loadFile(out.clone(), "c2.btl");
        check(back.buildings.size() == h0.buildingCount,
                "载入后建筑 " + back.buildings.size() + " / " + h0.buildingCount);
        check(back.armies.size() == h0.armyCount,
                "载入后兵种 " + back.armies.size() + " / " + h0.armyCount);
        check(back.width == expW && back.height == expH,
                "载入后窗口 " + back.width + "x" + back.height);
        check(back.coordBase == cbNew, "载入后坐标基准 " + back.coordBase + " 期望 " + cbNew);

        System.out.println(fails == 0 ? "\n全部通过" : "\n失败 " + fails + " 项");
        if (fails != 0) System.exit(1);
    }
}
