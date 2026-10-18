import com.xckeji.bj.file.FileParser;
import com.xckeji.bj.model.MapData;
import java.nio.file.*;
public class RepairTest {
    static int u32(byte[] d,int o){return (d[o]&0xFF)|((d[o+1]&0xFF)<<8)|((d[o+2]&0xFF)<<16)|((d[o+3]&0xFF)<<24);}
    static int need(byte[] d){
        FileParser.BtlHeaderInfo h=FileParser.parseBTLHeader(d);
        int army=h.version==1?48:64, reinf=h.version==1?80:104;
        int p=128+h.legionCount*300;
        if(h.independentTerrain) p+=h.width*h.height*16;
        p+=h.sectionTiles*2+h.sectionTiles;
        p+=h.buildingCount*32+h.armyCount*army+h.mineCount*12+h.planCount*16;
        p+=h.weatherCount*16+h.eventCount*44+h.reinforceCount*reinf+h.airstrikeCount*20;
        p+=(h.placementCountA+h.placementCountB)*8+h.strategyCount*16+h.airSupportCount*16+h.capitalCount*4;
        if(h.version==3) p+=u32(d,0x54);
        return p;
    }
    static String ok(byte[] d){ int n=need(d); return "len="+d.length+" 声明="+n+(d.length==n?" ✔自洽":" ★短"+(n-d.length)); }
    public static void main(String[] a) throws Exception {
        byte[] v1 = Files.readAllBytes(Paths.get(a[0]));          // 原始 v1 整合版 conquest1.btl
        byte[] binRaw = Files.readAllBytes(Paths.get(a[1]));      // 原始 world.bin (148x54)
        byte[] userBin = Files.readAllBytes(Paths.get(a[2]));     // 用户已经扩过的 bin (148x56)
        MapData m = FileParser.loadFile(v1.clone(), "conquest1.btl");
        byte[] v3 = v1;   // 版本转换功能已按用户要求删除，直接用原始 v1
        MapData m3 = FileParser.loadFile(v3.clone(), "conquest1.btl");
        m3.binOriginalData = binRaw.clone();
        FileParser.loadConquestTerrain(m3, binRaw.clone());
        FileParser.expandConquestLossless(m3, "up", 2, 1, 0);     // 底图+2、窗口+2
        byte[] outBtl = FileParser.saveAsBTL(m3);
        byte[] outBin = FileParser.saveAsBIN(m3);
        int[] bs = FileParser.worldBinSize(outBin);
        System.out.println("重做结果：");
        System.out.println("  BTL  " + ok(outBtl));
        System.out.println("  BIN  " + bs[0] + "x" + bs[1] + " len=" + outBin.length
                + " 期望=" + (16+bs[0]*bs[1]*16+bs[0]*bs[1]*2));
        FileParser.BtlHeaderInfo h = FileParser.parseBTLHeader(outBtl);
        int[] ub = FileParser.worldBinSize(userBin);
        System.out.printf("  头部: v%d mapId=%d 截取(%d,%d) 窗口%dx%d 0x58=%d 建筑%d 兵种%d 陷阱%d 事件%d 援军%d 放置%d/%d 首都%d 空支%d%n",
            h.version,h.mapId,h.captureX,h.captureY,h.width,h.height,h.sectionTiles,
            h.buildingCount,h.armyCount,h.mineCount,h.eventCount,h.reinforceCount,
            h.placementCountA,h.placementCountB,h.capitalCount,h.airSupportCount);
        System.out.println("  用户已有 BIN: " + ub[0] + "x" + ub[1] + " len=" + userBin.length);
        boolean sameBin = java.util.Arrays.equals(outBin, userBin);
        System.out.println("  重做的 BIN 与用户已有 BIN " + (sameBin ? "完全一致 ✔（可以直接用原来那个）" : "不同（需要用重做的）"));
        Files.createDirectories(Paths.get("/tmp/fix"));
        Files.write(Paths.get("/tmp/fix/conquest1.btl"), outBtl);
        Files.write(Paths.get("/tmp/fix/world.bin"), outBin);
        System.out.println("\n已写出 /tmp/fix/conquest1.btl 和 /tmp/fix/world.bin");
    }
}
