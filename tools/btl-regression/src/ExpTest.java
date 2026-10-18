import com.xckeji.bj.file.FileParser;
import com.xckeji.bj.model.MapData;
import java.nio.file.*;
import java.util.*;

/**
 * 战役 BTL 扩展时「内容段是否还贴着地形」的检查。
 *   java ExpTest <campaign.btl> [n]
 * 对四个方向分别：扩展 n 行/列 → 保存 → 校验
 *   ① 建筑/兵种条数不变
 *   ② 记录顺序不变、类型字段不变
 *   ③ 每条记录新坐标处的 16 字节地形 == 它原来坐标处的地形（内容与地形仍对齐）
 *   ④ 报告哪些方向的坐标字段字节完全没动
 */
public class ExpTest {
    static int le16(byte[] d, int o) { return (d[o] & 0xFF) | ((d[o + 1] & 0xFF) << 8); }
    static int le32(byte[] d, int o) {
        return (d[o] & 0xFF) | ((d[o + 1] & 0xFF) << 8) | ((d[o + 2] & 0xFF) << 16) | ((d[o + 3] & 0xFF) << 24);
    }
    static boolean same16(byte[] a, int ao, byte[] b, int bo) {
        for (int i = 0; i < 16; i++) if (a[ao + i] != b[bo + i]) return false;
        return true;
    }

    /** 取内容段所有记录的 {坐标字段偏移, 坐标, 类型, 类别(0=建筑 1=兵种)} 列表 */
    static List<int[]> content(byte[] d, FileParser.BtlHeaderInfo h) {
        List<int[]> out = new ArrayList<>();
        for (int k = 0; k < h.buildingCount; k++) {
            int a = h.buildingStart + k * 32;
            out.add(new int[]{a, le16(d, a), d[a + 4] & 0xFF, 0});
        }
        int armyStart = h.buildingStart + h.buildingCount * 32;
        int rec = h.version >= 2 ? 64 : 48;
        for (int k = 0; k < h.armyCount; k++) {
            int a = armyStart + k * rec;
            out.add(new int[]{a, le16(d, a), d[a + 2] & 0xFF, 1});
        }
        return out;
    }

    /** 只统计某一类（0=建筑 1=兵种）在扩展后是否还在、坐标是否还有效、是否仍贴着原地形 */
    static void report(String label, List<int[]> c0, List<int[]> c1, byte[] src, byte[] out,
                       int terr0, boolean conquest) {
        int n0 = 0, n1 = 0, typeBad = 0, alignBad = 0, coordSame = 0, outOfRange = 0;
        int total1 = 0;
        for (int[] r : c1) if (r[3] == (label.equals("建筑") ? 0 : 1)) total1++;
        for (int k = 0; k < Math.min(c0.size(), c1.size()); k++) {
            int[] a = c0.get(k), b = c1.get(k);
            if (a[3] != b[3]) continue;
            boolean want = (a[3] == 0 && label.equals("建筑")) || (a[3] == 1 && label.equals("兵种"));
            if (!want) continue;
            n0++;
            if (a[2] != b[2]) typeBad++;
            if (a[1] == b[1]) coordSame++;
            if (conquest) continue;
            int bs = terr0 + b[1] * 16, as = terr0 + a[1] * 16;
            if (bs + 16 > out.length || as + 16 > src.length || b[1] < 0) { outOfRange++; continue; }
            if (!same16(out, bs, src, as)) alignBad++;
        }
        System.out.printf("       %s 条数 %d→%d  类型异常%d  坐标未变%d/%d  越界%d  地形错位%d%n",
                label, n0, total1, typeBad, coordSame, n0, outOfRange, alignBad);
    }

    public static void main(String[] args) throws Exception {
        byte[] src = Files.readAllBytes(Paths.get(args[0]));
        int n = args.length > 1 ? Integer.parseInt(args[1]) : 2;
        FileParser.BtlHeaderInfo h0 = FileParser.parseBTLHeader(src);
        int W0 = h0.width, H0 = h0.height;
        int terr0 = 128 + h0.legionCount * 300;
        List<int[]> c0 = content(src, h0);
        System.out.println(Paths.get(args[0]).getFileName() + "  v" + h0.version + "  " + W0 + "x" + H0
                + "  地图序号" + h0.mapId + "  军团" + h0.legionCount + "  建筑" + h0.buildingCount
                + "  兵种" + h0.armyCount + "  内容记录 " + c0.size() + " 条，扩展量 n=" + n);

        byte[] bin = (args.length > 2) ? Files.readAllBytes(Paths.get(args[2])) : null;
        boolean conquest = !h0.independentTerrain;
        if (conquest) {
            System.out.println("  （征服地图：BTL 无地形段，内容坐标是世界坐标，应当一个字节都不动）");
        }

        for (String dir : new String[]{"up", "down", "left", "right"}) {
            MapData m = FileParser.loadFile(src.clone(), "t.btl");
            if (conquest && bin != null) {
                try {
                    m.binOriginalData = bin.clone();
                    FileParser.loadConquestTerrain(m, bin.clone());
                } catch (Exception e) {
                    System.out.println("  载入底图失败: " + e.getMessage());
                }
            }
            try {
                FileParser.resizeMap(m, dir, n, true, 1, 0);
            } catch (Exception e) {
                System.out.printf("  %-5s → 拒绝：%s%n", dir, e.getMessage());
                continue;
            }
            byte[] out = m.btlOriginalData;
            FileParser.BtlHeaderInfo h1 = FileParser.parseBTLHeader(out);
            List<int[]> c1 = content(out, h1);

            boolean countOk = (c1.size() == c0.size())
                    && h1.buildingCount == h0.buildingCount && h1.armyCount == h0.armyCount;
            int typeBad = 0, alignBad = 0, coordSame = 0;
            for (int k = 0; k < Math.min(c0.size(), c1.size()); k++) {
                int[] a = c0.get(k), b = c1.get(k);
                if (a[2] != b[2]) typeBad++;
                if (a[1] == b[1]) coordSame++;
                if (conquest) continue;                 // 征服：地形不在 BTL 里，另做整段比对
                int bs = terr0 + b[1] * 16, as = terr0 + a[1] * 16;
                if (bs + 16 <= out.length && as + 16 <= src.length) {
                    if (!same16(out, bs, src, as)) alignBad++;
                } else alignBad++;
            }
            String extra = "";
            if (conquest) {
                // 内容段（含尾段）应逐字节不变
                int tail0 = h0.buildingStart, tail1 = h1.buildingStart;
                int len = src.length - tail0;
                boolean same = (out.length - tail1 == len);
                if (same) for (int i = 0; i < len; i++) {
                    if (src[tail0 + i] != out[tail1 + i]) { same = false; break; }
                }
                extra = "  内容段逐字节不变=" + (same ? "是" : "否");
            }
            System.out.printf("  %-5s → %dx%d  条数%s%s%n",
                    dir, h1.width, h1.height, countOk ? "OK" : "丢失!", extra);
            report("建筑", c0, c1, src, out, terr0, conquest);
            report("兵种", c0, c1, src, out, terr0, conquest);
        }
    }
}
