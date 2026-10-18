import com.xckeji.bj.file.FileParser;
import com.xckeji.bj.model.MapData;
import java.io.File;
import java.nio.file.*;
/** 加载 + 保存（保存时会自动修正海洋省区与陆地省区代表格）。 */
public class FixTest {
    public static void main(String[] a) throws Exception {
        byte[] in = Files.readAllBytes(Paths.get(a[0]));
        MapData m = FileParser.loadFile(in.clone(), new File(a[0]).getName());
        byte[] out = FileParser.saveAsBTL(m);
        Files.write(Paths.get(a[1]), out);
        System.out.println("  " + in.length + " 字节 → " + out.length + " 字节");
    }
}
