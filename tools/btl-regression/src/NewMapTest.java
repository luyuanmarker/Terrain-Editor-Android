import com.xckeji.bj.file.FileParser;
import com.xckeji.bj.model.MapData;
import java.nio.file.*;
/** 验证新建空白图：2 条通用军团、回合 20/20、省规划每格自成省区。 */
public class NewMapTest {
    public static void main(String[] a) throws Exception {
        byte[] tpl = Files.readAllBytes(Paths.get(a[0]));
        int w = Integer.parseInt(a[1]), h = Integer.parseInt(a[2]);
        MapData m = FileParser.createEmptyBtlFromTemplate(tpl, "新战役_" + w + "x" + h + ".btl", w, h);
        byte[] out = FileParser.saveAsBTL(m);
        Files.write(Paths.get(a[3]), out);
        FileParser.BtlHeaderInfo hh = FileParser.parseBTLHeader(out);
        System.out.println("新建 " + w + "x" + h + " → " + out.length + " 字节  v" + hh.version
            + " mapId=" + hh.mapId + " 军团" + hh.legionCount
            + " 建筑" + hh.buildingCount + " 兵种" + hh.armyCount + " 放置" + hh.placementCountA + "/" + hh.placementCountB);
        for (int i = 0; i < hh.legionCount; i++) {
            int o = 128 + i * 300;
            System.out.printf("   军团%d: 顺序=%d 国家=%d 玩家控制=%d 阵营=%d%n", i,
                le32(out, o), le32(out, o + 4), le32(out, o + 0x14), le32(out, o + 0x18));
        }
        System.out.printf("   胜利条件=%d 最小回合=%d 最大回合=%d%n",
            le32(out, 0x30), le32(out, 0x34), le32(out, 0x38));
        int t0 = 128 + hh.legionCount * 300, p0 = t0 + w * h * 16;
        int self = 0;
        for (int i = 0; i < w * h; i++) if (le16(out, p0 + i * 2) == i) self++;
        System.out.println("   省规划「每格自己当代表格」= " + self + "/" + (w * h));
        System.out.println("   军团国家 = " + FileParser.legionCountries(m));
        int[] req = FileParser.stageRequiredNations("stage10101.btl");
        java.util.Set<Integer> have = FileParser.legionCountries(m);
        java.util.List<Integer> miss = new java.util.ArrayList<>();
        for (int c : req) if (!have.contains(c)) miss.add(c);
        System.out.println("   存成 stage10101 时缺的国家 = " + miss + "（保存时会自动补）");
    }
    static int le32(byte[] d, int o) { return (d[o]&0xFF)|((d[o+1]&0xFF)<<8)|((d[o+2]&0xFF)<<16)|((d[o+3]&0xFF)<<24); }
    static int le16(byte[] d, int o) { return (d[o]&0xFF)|((d[o+1]&0xFF)<<8); }
}
