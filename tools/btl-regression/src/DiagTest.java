import com.xckeji.bj.file.FileParser;
import com.xckeji.bj.model.MapData;
import java.nio.file.*;
public class DiagTest {
    static int u32(byte[] d,int o){return (d[o]&0xFF)|((d[o+1]&0xFF)<<8)|((d[o+2]&0xFF)<<16)|((d[o+3]&0xFF)<<24);}
    static int armyRec(int v){return v==1?48:64;}
    static int reinfRec(int v){return v==1?80:104;}
    static void layout(String tag, byte[] d){
        FileParser.BtlHeaderInfo h=FileParser.parseBTLHeader(d);
        int p=128;
        System.out.printf("  %-10s len=%-7d 头128", tag, d.length);
        p+=h.legionCount*300;                       System.out.printf(" 军团→%d",p);
        if(h.independentTerrain){p+=h.width*h.height*16; System.out.printf(" 地形→%d",p);}
        int prov=p; p+=h.sectionTiles*2;             System.out.printf(" 省规划(%d格)→%d",h.sectionTiles,p);
        int own=p;  p+=h.sectionTiles;               System.out.printf(" 归属→%d",p);
        int bld=p;  p+=h.buildingCount*32;           System.out.printf(" 建筑→%d",p);
        int arm=p;  p+=h.armyCount*armyRec(h.version);System.out.printf(" 兵种→%d",p);
        System.out.printf("\n             陷阱@%d(+%d) 方案@%d(+%d) 事件@%d(+%d) 援军@%d(+%d) 放置@%d(+%d) 空支@%d(+%d) 首都@%d(+%d)%n",
            p,h.mineCount*12,p+h.mineCount*12,h.planCount*16,
            p+h.mineCount*12+h.planCount*16,h.eventCount*44,
            p+h.mineCount*12+h.planCount*16+h.eventCount*44,h.reinforceCount*reinfRec(h.version),
            p+h.mineCount*12+h.planCount*16+h.eventCount*44+h.reinforceCount*reinfRec(h.version),
            (h.placementCountA+h.placementCountB)*8,
            p+h.mineCount*12+h.planCount*16+h.eventCount*44+h.reinforceCount*reinfRec(h.version)+(h.placementCountA+h.placementCountB)*8,
            h.airSupportCount*16,
            p+h.mineCount*12+h.planCount*16+h.eventCount*44+h.reinforceCount*reinfRec(h.version)+(h.placementCountA+h.placementCountB)*8+h.airSupportCount*16,
            h.capitalCount*4);
        int need=p+h.mineCount*12+h.planCount*16+h.eventCount*44+h.reinforceCount*reinfRec(h.version)
            +(h.placementCountA+h.placementCountB)*8+h.airSupportCount*16+h.capitalCount*4;
        System.out.printf("             声明总长=%d  实际=%d  差额=%d%n", need, d.length, d.length-need);
        System.out.printf("             省规划@%d 归属@%d 建筑@%d 兵种@%d%n", prov, own, bld, arm);
    }
    public static void main(String[] a) throws Exception {
        byte[] btl=Files.readAllBytes(Paths.get(a[0]));
        byte[] bin=Files.readAllBytes(Paths.get(a[1]));
        System.out.println("== 原始文件 ==");
        layout("原文件", btl);
        FileParser.BtlHeaderInfo h=FileParser.parseBTLHeader(btl);
        System.out.printf("  解析到: 军团%d 建筑%d 兵种%d 陷阱%d 方案%d 事件%d 援军%d 空袭%d 放置a%d b%d 战略%d 空支%d 首都%d%n",
            h.legionCount,h.buildingCount,h.armyCount,h.mineCount,h.planCount,h.eventCount,
            h.reinforceCount,h.airstrikeCount,h.placementCountA,h.placementCountB,h.strategyCount,h.airSupportCount,h.capitalCount);
        MapData m=FileParser.loadFile(btl.clone(),"c1.btl");
        System.out.printf("  载入内存: 军团%d 建筑%d 兵种%d 陷阱%d%n",
            m.legions.size(),m.buildings.size(),m.armies.size(),m.traps.size());
        // 直接保存（不改动）
        byte[] out=FileParser.saveAsBTL(m);
        System.out.println("== 直接保存（未改动）==");
        layout("saveAsBTL", out);
        // 挂底图 + 无损扩 2
        MapData m2=FileParser.loadFile(btl.clone(),"c1.btl");
        m2.binOriginalData=bin.clone();
        FileParser.loadConquestTerrain(m2, bin.clone());
        FileParser.expandConquestLossless(m2,"up",2,1,0);
        byte[] out2=FileParser.saveAsBTL(m2);
        System.out.println("== 无损向上扩 2 后保存 ==");
        layout("expanded", out2);
        System.out.printf("  扩后内存: 军团%d 建筑%d 兵种%d 陷阱%d%n",
            m2.legions.size(),m2.buildings.size(),m2.armies.size(),m2.traps.size());
    }
}
