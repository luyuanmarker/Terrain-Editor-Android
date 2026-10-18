import com.xckeji.bj.file.FileParser;
import com.xckeji.bj.model.MapData;
import java.io.File;
import java.nio.file.*;
/** 验证「官方关卡参战国检查 + 自动补国家」。 */
public class StageCheckTest {
    public static void main(String[] a) throws Exception {
        String fname = a[0];
        byte[] btl = Files.readAllBytes(Paths.get(a[1]));
        MapData m = FileParser.loadFile(btl.clone(), fname);
        int[] req = FileParser.stageRequiredNations(fname);
        System.out.println("文件名 " + fname + "  →  游戏要求的参战国 " + (req == null ? "（不是官方关卡槽位）" : ("本国 " + req[0] + " / 敌国 " + req[1])));
        java.util.Set<Integer> have = FileParser.legionCountries(m);
        System.out.println("地图军团现有国家 = " + have);
        if (req == null) return;
        java.util.List<Integer> miss = new java.util.ArrayList<>();
        for (int c : req) if (!have.contains(c)) miss.add(c);
        if (miss.isEmpty()) { System.out.println("参战国齐全 ✔ 不会闪退"); return; }
        System.out.println("★ 缺国家 " + miss + " → 会自动补上");
        byte[] cur = btl.clone();
        for (int c : miss) cur = FileParser.addLegionForCountry(cur, c);
        MapData m2 = FileParser.loadFile(cur, fname);
        System.out.println("补完后军团国家 = " + FileParser.legionCountries(m2)
            + "   军团数 " + FileParser.parseBTLHeader(cur).legionCount);
        byte[] out = FileParser.saveAsBTL(m2);
        System.out.println("保存后 " + out.length + " 字节");
        Files.write(Paths.get(a[2]), out);
    }
}
