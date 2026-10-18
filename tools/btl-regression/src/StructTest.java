import com.xckeji.bj.file.FileParser;
import com.xckeji.bj.model.MapData;
import java.nio.file.*;
public class StructTest {
    static int fails=0;
    static int u32(byte[] d,int o){return (d[o]&0xFF)|((d[o+1]&0xFF)<<8)|((d[o+2]&0xFF)<<16)|((d[o+3]&0xFF)<<24);}
    static int u16(byte[] d,int o){return (d[o]&0xFF)|((d[o+1]&0xFF)<<8);}
    static int declared(byte[] d){
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
    /** 建筑/兵种/陷阱 的坐标必须落在 [coordBase, coordBase + W*H) */
    static int orphan(byte[] d){
        FileParser.BtlHeaderInfo h=FileParser.parseBTLHeader(d);
        int army=h.version==1?48:64;
        int base=h.captureY*h.width+h.captureX, tot=h.width*h.height;
        int bad=0;
        for(int i=0;i<h.buildingCount;i++){int o=h.buildingStart+i*32;
            if(o+2>d.length) break; int c=u16(d,o)-base; if(c<0||c>=tot) bad++;}
        int as=h.buildingStart+h.buildingCount*32;
        for(int i=0;i<h.armyCount;i++){int o=as+i*army;
            if(o+army>d.length) break; if((d[o+2]&0xFF)==0) continue;   // 空占位跳过
            int c=u16(d,o)-base; if(c<0||c>=tot) bad++;}
        for(int i=0;i<h.mineCount;i++){int o=as+h.armyCount*army+i*12;
            if(o+12>d.length) break; int c=u16(d,o)-base; if(c<0||c>=tot) bad++;}
        return bad;
    }
    static void step(String tag, byte[] d){
        int dec=declared(d), orp=orphan(d);
        boolean ok = (dec==d.length) && (orp==0);
        if(!ok) fails++;
        System.out.printf("    %-22s len=%-6d 声明=%-6d %s   越界坐标=%d %s%n", tag, d.length, dec,
            dec==d.length?"✔":"★短"+(dec-d.length), orp, orp==0?"✔":"★");
    }
    public static void main(String[] a) throws Exception {
        byte[] btl=Files.readAllBytes(Paths.get(a[0]));
        byte[] bin=a.length>1?Files.readAllBytes(Paths.get(a[1])):null;
        FileParser.BtlHeaderInfo h0=FileParser.parseBTLHeader(btl);
        System.out.println(Paths.get(a[0]).getFileName()+"  v"+h0.version+" mapId="+h0.mapId
            +" 窗口"+h0.width+"x"+h0.height+(bin!=null?"  底图":"  (无底图)"));
        System.out.println("  原始:"); step("原始文件", btl);
        String[] dirs={"up","down","left","right"};
        for(String d:dirs) run("扩展 "+d+" 3", btl, bin, "resize", d, 3, true);
        for(String d:dirs) run("收缩 "+d+" 2", btl, bin, "resize", d, 2, false);
        if(bin!=null) for(String d:dirs) run("无损扩图 "+d+" 3", btl, bin, "lossless", d, 3, true);
        System.out.println(fails==0?"  ✅ 全部自洽":"  ❌ 有 "+fails+" 项异常");
        if(fails!=0) System.exit(1);
    }
    static void run(String tag, byte[] btl, byte[] bin, String mode, String dir, int k, boolean expand){
        try { step(tag, FileParser.saveAsBTL(mk(btl,bin,null,mode,dir,k,expand))); }
        catch(Exception e){ System.out.printf("    %-22s 拒绝：%s%n", tag, e.getMessage()); }
    }
    interface Noop { void run(String s); }
    static MapData mk(byte[] btl, byte[] bin, Noop n, String mode, String dir, int k, boolean expand) throws Exception {
        MapData m=FileParser.loadFile(btl.clone(),"t.btl");
        if(bin!=null){ m.binOriginalData=bin.clone(); FileParser.loadConquestTerrain(m,bin.clone()); }
        if(mode.equals("lossless")) FileParser.expandConquestLossless(m,dir,k,1,0);
        else FileParser.resizeMap(m,dir,k,expand,1,0);
        return m;
    }
}
