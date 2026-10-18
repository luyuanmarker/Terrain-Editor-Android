import com.xckeji.bj.file.FileParser;
import com.xckeji.bj.model.MapData;
import java.nio.file.*;
public class CheckTest {
    static int u32(byte[] d,int o){return (d[o]&0xFF)|((d[o+1]&0xFF)<<8)|((d[o+2]&0xFF)<<16)|((d[o+3]&0xFF)<<24);}
    static int need(byte[] d){
        FileParser.BtlHeaderInfo h=FileParser.parseBTLHeader(d);
        int army=h.version==1?48:64, reinf=h.version==1?80:104;
        int p=128+h.legionCount*300;
        if(h.independentTerrain) p+=h.width*h.height*16;
        p+=h.sectionTiles*2+h.sectionTiles+h.buildingCount*32+h.armyCount*army+h.mineCount*12+h.planCount*16;
        p+=h.weatherCount*16+h.eventCount*44+h.reinforceCount*reinf+h.airstrikeCount*20;
        p+=(h.placementCountA+h.placementCountB)*8+h.strategyCount*16+h.airSupportCount*16+h.capitalCount*4;
        if(h.version==3) p+=u32(d,0x54);
        return p;
    }
    public static void main(String[] a) throws Exception {
        for (String f : a) {
            byte[] d=Files.readAllBytes(Paths.get(f));
            FileParser.BtlHeaderInfo h=FileParser.parseBTLHeader(d);
            MapData m=FileParser.loadFile(d.clone(), new java.io.File(f).getName());
            byte[] out=FileParser.saveAsBTL(m);
            System.out.printf("%-34s len=%-6d 声明=%-6d %s%n", new java.io.File(f).getName(), d.length, need(d),
                d.length==need(d)?"✔自洽":"★短"+(need(d)-d.length));
            System.out.printf("    载入: v%d mapId=%d 窗口%dx%d 军团%d 建筑%d 兵种%d 陷阱%d | 再保存 len=%d %s%n",
                h.version,h.mapId,h.width,h.height,m.legions.size(),m.buildings.size(),m.armies.size(),m.traps.size(),
                out.length, (out.length==need(out)?"✔自洽":"★短"+(need(out)-out.length)));
        }
    }
}
