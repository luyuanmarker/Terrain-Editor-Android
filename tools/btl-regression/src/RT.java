import com.xckeji.bj.file.FileParser;
import com.xckeji.bj.model.MapData;
import java.nio.file.*;
import java.util.*;

public class RT {
    public static void main(String[] args) throws Exception {
        Path dir = Paths.get(args[0]);
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.btl")) { for (Path p : ds) files.add(p); }
        Collections.sort(files);
        int same=0, differ=0, fail=0, skipped=0;
        Map<Integer,Integer> byVerSame=new TreeMap<>(), byVerDiff=new TreeMap<>(), byVerFail=new TreeMap<>();
        List<String> diffs=new ArrayList<>(), fails=new ArrayList<>();
        int declaredMismatch=0;
        for (Path p : files) {
            byte[] orig = Files.readAllBytes(p);
            if (orig.length<128) { skipped++; continue; }
            FileParser.BtlHeaderInfo h = FileParser.parseBTLHeader(orig);
            if (h.width<=0||h.height<=0||h.width>500||h.height>500) { skipped++; continue; }
            int dec = java.nio.ByteBuffer.wrap(orig).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(0x58);
            boolean dm = dec != h.width*h.height;
            if (dm) declaredMismatch++;
            MapData m;
            try { m = FileParser.loadFile(orig.clone(), p.getFileName().toString()); }
            catch (Exception e) { skipped++; continue; }
            byte[] out;
            try { out = FileParser.saveAsBTL(m); }
            catch (Exception e) {
                fail++; byVerFail.merge(h.version,1,Integer::sum);
                if (fails.size()<10) fails.add(p.getFileName()+" v"+h.version+" 声明地块="+dec+" W*H="+(h.width*h.height)+" EX:"+e.getClass().getSimpleName());
                continue;
            }
            boolean identical = out.length==orig.length;
            if (identical) for (int i=0;i<out.length;i++) if (out[i]!=orig[i]) { identical=false; break; }
            if (identical) { same++; byVerSame.merge(h.version,1,Integer::sum); }
            else {
                differ++; byVerDiff.merge(h.version,1,Integer::sum);
                if (diffs.size()<25) {
                    int cHead=0,cTerr=0,cAdmin=0,cOwn=0,cRest=0;
                    int otherAdmin=0;
                    int ts=h.terrainStart, as=ts+(h.independentTerrain?h.width*h.height*16:0), os=as+h.width*h.height*2, bs=os+h.width*h.height;
                    int nn=Math.min(out.length,orig.length);
                    for (int i=0;i<nn;i++){ if(orig[i]==out[i])continue; if(i<ts)cHead++; else if(i<as)cTerr++; else if(i<os)cAdmin++; else if(i<bs)cOwn++; else cRest++; }
                    // 省规划改动是否全部是「海洋格强制 65535」
                    for (int i=0;i<Math.min(dec,h.width*h.height);i++){
                        int a0=as+i*2;
                        if (a0+1>=nn) break;
                        if (orig[a0]==out[a0] && orig[a0+1]==out[a0+1]) continue;
                        boolean isSea = h.independentTerrain && (orig[ts+i*16]&0xFF)==1;
                        boolean toFF = (out[a0]&0xFF)==0xFF && (out[a0+1]&0xFF)==0xFF;
                        if (!(isSea && toFF)) otherAdmin++;
                    }
                    diffs.add(String.format("%s v%d len %d->%d(声明%d W*H=%d) 头%d 地形%d 省%d(非海洋规则%d) 归属%d 尾%d",
                        p.getFileName(), h.version, orig.length, out.length, dec, h.width*h.height, cHead,cTerr,cAdmin,otherAdmin,cOwn,cRest));
                }
            }
        }
        System.out.println("完全一致=" + same + " 有差异=" + differ + " 保存异常=" + fail + " 跳过=" + skipped);
        System.out.println("声明地块总数 != W*H 的文件数=" + declaredMismatch);
        System.out.println("按版本 一致:" + byVerSame + " 差异:" + byVerDiff + " 异常:" + byVerFail);
        System.out.println("---- 差异样例（未做任何修改就直接保存）----");
        for (String s : diffs) System.out.println("  " + s);
        System.out.println("---- 异常样例 ----");
        for (String s : fails) System.out.println("  " + s);
    }
}
