import com.xckeji.bj.file.FileParser;
import com.xckeji.bj.model.MapData;
import java.nio.file.*;
import java.util.*;

public class BinTest {
    public static void main(String[] a) throws Exception {
        byte[] bin0 = Files.readAllBytes(Paths.get(a[0]));
        MapData m = FileParser.loadFile(bin0.clone(), "world.bin");
        int w = m.width, h = m.height;
        System.out.println("独立 BIN " + w + "x" + h + " 共" + (w*h) + " 格");
        int hdr = bin0[0]=='Y' ? 16 : 8;
        int regionStart = hdr + w*h*16;
        Set<Integer> painted = new HashSet<>();
        int[] idx = { 100, 200, 300 };
        int[] gid = { 1, 0, 2 };
        for (int k=0;k<3;k++){ m.tiles.get(idx[k]).setTerrain(gid[k]); painted.add(idx[k]); }
        m.finishPaint(painted);
        byte[] out = FileParser.saveAsBIN(m);
        System.out.println("len " + bin0.length + " -> " + out.length);
        int dH=0,dT=0,dR=0,dTail=0;
        for (int i=0;i<Math.min(bin0.length,out.length);i++){
            if (bin0[i]==out[i]) continue;
            if (i<hdr) dH++; else if (i<regionStart) dT++; else dR++;
        }
        System.out.println("差异 头="+dH+" 地形="+dT+" 省规划="+dR);
        TreeSet<Integer> ch=new TreeSet<>();
        for (int i=0;i<w*h;i++) for (int b=0;b<16;b++) if (bin0[hdr+i*16+b]!=out[hdr+i*16+b]) { ch.add(i); break; }
        System.out.println("地形改动格=" + ch.size() + " " + new ArrayList<>(ch).subList(0, Math.min(10,ch.size())));
        int regCh=0, regOther=0;
        for (int i=0;i<w*h;i++){
            int o=regionStart+i*2;
            if (bin0[o]==out[o] && bin0[o+1]==out[o+1]) continue;
            regCh++;
            boolean ok = painted.contains(i) && m.tiles.get(i).bmTerrain1Group==1
                    && (out[o]&0xFF)==0xFF && (out[o+1]&0xFF)==0xFF;
            if (!ok) { regOther++; if (regOther<5) System.out.println("  非预期区划改动 #"+i+" grp="+m.tiles.get(i).bmTerrain1Group); }
        }
        System.out.println("省规划改动格=" + regCh + " 非海洋规则=" + regOther);
    }
}
