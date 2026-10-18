import com.xckeji.bj.file.FileParser;
import com.xckeji.bj.model.MapData;
import java.nio.file.*;
public class PipeTest {
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
    static void show(String tag, byte[] d){
        int n=need(d);
        System.out.printf("  %-26s len=%-7d 声明=%-7d 差额=%-5d %s%n", tag, d.length, n, d.length-n,
            d.length==n?"✔ 自洽":"★ 不一致（游戏会读越界 → 闪退）");
    }
    public static void main(String[] a) throws Exception {
        byte[] v1=Files.readAllBytes(Paths.get(a[0]));
        System.out.println("步骤 0：载入 整合版 conquest1.btl (v1)");
        show("原始 v1", v1);
        MapData m=FileParser.loadFile(v1.clone(),"c1.btl");
        System.out.println("\n步骤 1：（版本转换功能已按用户要求删除，直接用 v1 继续跑流水线）");
        byte[] v3 = v1;
        MapData m3=FileParser.loadFile(v3.clone(),"c1.btl");
        System.out.printf("     载入 v3: 军团%d 建筑%d 兵种%d 陷阱%d%n",
            m3.legions.size(),m3.buildings.size(),m3.armies.size(),m3.traps.size());
        System.out.println("\n步骤 2a：直接保存（不改动）");
        show("saveAsBTL", FileParser.saveAsBTL(m3));
        byte[] bin=Files.readAllBytes(Paths.get(a[1]));
        System.out.println("\n步骤 2b：向上扩 2（普通扩展地图，只挪窗口）");
        MapData m4=FileParser.loadFile(v3.clone(),"c1.btl");
        m4.binOriginalData=bin.clone();
        FileParser.loadConquestTerrain(m4,bin.clone());
        FileParser.resizeMap(m4,"up",2,true,1,0);
        show("resizeMap up2", FileParser.saveAsBTL(m4));
        System.out.println("\n步骤 2c：无损扩图（底图+窗口）");
        MapData m5=FileParser.loadFile(v3.clone(),"c1.btl");
        m5.binOriginalData=bin.clone();
        FileParser.expandConquestLossless(m5,"up",2,1,0);
        show("expandConquestLossless up2", FileParser.saveAsBTL(m5));
    }
}
