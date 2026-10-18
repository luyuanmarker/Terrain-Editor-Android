import com.xckeji.bj.file.FileParser;
import com.xckeji.bj.model.MapData;
import java.io.File;
import java.nio.file.*;
/** 跑编辑器自己的「校验并修复」，把问题列出来。 */
public class ValidateTest {
    public static void main(String[] a) throws Exception {
        byte[] btl = Files.readAllBytes(Paths.get(a[0]));
        MapData m = FileParser.loadFile(btl.clone(), new File(a[0]).getName());
        java.util.List<String> issues = FileParser.validateAndFix(m);
        System.out.println("  校验问题数 = " + issues.size());
        for (String s : issues) System.out.println("    · " + s);
        if (a.length > 1) {
            Files.write(Paths.get(a[1]), FileParser.saveAsBTL(m));
            System.out.println("  已写出修复版 " + a[1]);
        }
    }
}
