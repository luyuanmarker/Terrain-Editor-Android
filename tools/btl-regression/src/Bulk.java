import com.xckeji.bj.file.FileParser;
import com.xckeji.bj.model.MapData;
import java.nio.file.*;
import java.util.*;

public class Bulk {
    public static void main(String[] args) throws Exception {
        Path dir = Paths.get(args[0]);
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.btl")) { for (Path p : ds) files.add(p); }
        Collections.sort(files);
        int n=0, skipped=0;
        Map<String,int[]> anomalies = new TreeMap<>();  // name -> counts
        List<String> details = new ArrayList<>();
        int diffLen=0, diffHead=0, diffTerr=0, diffAdmin=0, diffOwn=0, diffRest=0, savedOk=0, saveFail=0;
        for (Path p : files) {
            byte[] orig = Files.readAllBytes(p);
            if (orig.length < 128) { skipped++; continue; }
            FileParser.BtlHeaderInfo h = FileParser.parseBTLHeader(orig);
            if (!h.independentTerrain || h.width<=0 || h.height<=0 || h.width>500 || h.height>500) { skipped++; continue; }
            MapData m;
            try { m = FileParser.loadFile(orig.clone(), p.getFileName().toString()); }
            catch (Exception e) { skipped++; continue; }
            int total = m.width * m.height;
            if (m.tiles.size() != total) { skipped++; continue; }
            int beforeBuild = m.buildings.size(), beforeArmies = m.armies.size();
            int beforeBldCount = h.buildingCount, beforeArmyCount = h.armyCount;
            // 涂 3 格：一格变海洋(组1)、一格变沙漠(组2)、一格变针叶林(组20)
            int[] idx = { total/7, total/3, total/2 + 1 };
            int[] gid = { 1, 2, 20 };
            Set<Integer> painted = new HashSet<>();
            for (int k = 0; k < idx.length; k++) {
                int i = idx[k];
                if (i < 0 || i >= total) continue;
                m.tiles.get(i).setTerrain(gid[k]);
                painted.add(i);
            }
            m.finishPaint(painted);
            byte[] out;
            try { out = FileParser.saveAsBTL(m); } catch (Exception e) { saveFail++; details.add(p.getFileName()+" SAVE-EX "+e); continue; }
            savedOk++;
            if (out.length != orig.length) { diffLen++; details.add(p.getFileName()+" len "+orig.length+" -> "+out.length); }
            int terrainStart = h.terrainStart;
            int adminStart = terrainStart + total*16;
            int ownStart = adminStart + total*2;
            int bldStart = ownStart + total;
            int nn = Math.min(out.length, orig.length);
            int cHead=0,cTerr=0,cAdmin=0,cOwn=0,cRest=0;
            for (int i=0;i<nn;i++) {
                if (orig[i]==out[i]) continue;
                if (i<terrainStart) cHead++;
                else if (i<adminStart) cTerr++;
                else if (i<ownStart) cAdmin++;
                else if (i<bldStart) cOwn++;
                else cRest++;
            }
            diffHead+=cHead; diffTerr+=cTerr; diffAdmin+=cAdmin; diffOwn+=cOwn; diffRest+=cRest;
            // 头部改动只允许计数/尺寸字段
            if (cHead>0) {
                StringBuilder sb=new StringBuilder();
                for (int i=0;i<Math.min(128,orig.length);i++) if (orig[i]!=out[i]) sb.append(String.format("0x%X:%d->%d ", i, orig[i]&0xFF, out[i]&0xFF));
                details.add(p.getFileName()+" HEAD "+sb);
            }
            if (cRest>0) details.add(p.getFileName()+" REST changed="+cRest);
            // 省规划：非海洋格不许被改
            if (cAdmin>0) {
                for (int i=0;i<total;i++) {
                    int o1=adminStart+i*2;
                    if (orig[o1]==out[o1] && orig[o1+1]==out[o1+1]) continue;
                    // 允许：被涂成海洋的格子；其他一律记录
                    if (painted.contains(i) && m.tiles.get(i).bmTerrain1Group==1) continue;
                    details.add(p.getFileName()+" ADMIN tile "+i+" "+(((orig[o1+1]&0xFF)<<8)|(orig[o1]&0xFF))+" -> "+(((out[o1+1]&0xFF)<<8)|(out[o1]&0xFF))+" grp="+m.tiles.get(i).bmTerrain1Group);
                    break;
                }
            }
            n++;
        }
        System.out.println("处理文件数=" + n + " 跳过=" + skipped + " 保存成功=" + savedOk + " 保存异常=" + saveFail);
        System.out.println("长度变化文件数=" + diffLen + " 头部改动字节合计=" + diffHead + " 地形段改动格数=" + diffTerr
            + " 省规划改动字节=" + diffAdmin + " 归属改动=" + diffOwn + " 尾部改动字节=" + diffRest);
        System.out.println("---- 明细（前60条）----");
        for (int i=0;i<Math.min(100000, details.size());i++) System.out.println("  " + details.get(i));
        System.out.println("明细总数=" + details.size());
    }
}
