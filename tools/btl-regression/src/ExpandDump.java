import com.xckeji.bj.file.FileParser;
import com.xckeji.bj.model.MapData;
import java.io.File;
import java.nio.file.*;
/** 按编辑器的真实代码路径做「扩展地图」，把结果写出来供全字段校验。 */
public class ExpandDump {
    public static void main(String[] a) throws Exception {
        byte[] btl = Files.readAllBytes(Paths.get(a[0]));
        String dir = a[1]; int n = Integer.parseInt(a[2]);
        int gid = Integer.parseInt(a[3]), tid = Integer.parseInt(a[4]);
        MapData m = FileParser.loadFile(btl.clone(), new File(a[0]).getName());
        FileParser.resizeMap(m, dir, n, true, gid, tid);
        byte[] out = FileParser.saveAsBTL(m);
        Files.write(Paths.get(a[5]), out);
        System.out.printf("  %s %s+%d 填充(组%d,号%d) → %d 字节%n",
            new File(a[0]).getName(), dir, n, gid, tid, out.length);
    }
}
