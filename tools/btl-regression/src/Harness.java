import com.xckeji.bj.file.FileParser;
import com.xckeji.bj.model.MapData;
import java.nio.file.*;
import java.util.*;

public class Harness {
    public static void main(String[] args) throws Exception {
        String path = args[0];
        byte[] data = Files.readAllBytes(Paths.get(path));
        byte[] orig = data.clone();
        MapData m = FileParser.loadFile(data, new java.io.File(path).getName());
        System.out.println("file=" + path);
        System.out.println("w=" + m.width + " h=" + m.height + " tiles=" + m.tiles.size()
            + " coordBase=" + m.coordBase + " buildings=" + m.buildings.size()
            + " armies=" + m.armies.size() + " legions=" + m.legions.size());
        FileParser.BtlHeaderInfo h = FileParser.parseBTLHeader(data);
        int total = m.width * m.height;
        int terrainStart = h.terrainStart;
        int adminStart = terrainStart + (h.independentTerrain ? total*16 : 0);
        int ownStart = adminStart + total*2;
        int bldStart = ownStart + total;
        System.out.println("independentTerrain=" + h.independentTerrain + " terrainStart=" + terrainStart
            + " adminStart=" + adminStart + " ownStart=" + ownStart + " bldStart=" + bldStart + " fileLen=" + data.length
            + " tailBytes=" + (data.length - bldStart - h.buildingCount*32));

        Set<Integer> painted = new HashSet<>();
        for (int k = 1; k < args.length; k++) {
            if (!args[k].startsWith("paint:")) continue;
            String[] c = args[k].substring(6).split(",");
            int x = Integer.parseInt(c[0]), y = Integer.parseInt(c[1]), g = Integer.parseInt(c[2]);
            int idx = y * m.width + x;
            m.tiles.get(idx).setTerrain(g);
            painted.add(idx);
            System.out.println("paint (" + x + "," + y + ") idx=" + idx + " -> group " + g);
        }
        if (!painted.isEmpty()) m.finishPaint(painted);

        // 保存前状态
        Map<Integer,Integer> oc = new TreeMap<>(), ld = new TreeMap<>();
        for (int i = 0; i < total; i++) {
            int g = m.tiles.get(i).bmTerrain1Group;
            if (g == 1) oc.merge(m.provinces[i], 1, Integer::sum); else ld.merge(m.provinces[i], 1, Integer::sum);
        }
        System.out.println("保存前 内存海洋格区划(top6)=" + topN(oc,6) + " 海洋格数=" + sum(oc) + " | 陆地格区划不同值数=" + ld.size() + " 陆地格数=" + sum(ld));

        byte[] out = FileParser.saveAsBTL(m);
        Files.write(Paths.get("/private/tmp/btltest/out.btl"), out);
        System.out.println("saved len=" + out.length + " (orig " + data.length + ", delta " + (out.length-data.length) + ")");

        // ---- 差异分析 ----
        int n = Math.min(data.length, out.length);
        int dHead=0,dTerr=0,dAdmin=0,dOwn=0,dRest=0;
        List<Integer> headOff=new ArrayList<>(), adminOff=new ArrayList<>(), ownOff=new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (orig[i]==out[i]) continue;
            if (i < terrainStart) { dHead++; if (headOff.size()<16) headOff.add(i); }
            else if (i < adminStart) dTerr++;
            else if (i < ownStart) { dAdmin++; if (adminOff.size()<16) adminOff.add(i); }
            else if (i < bldStart) { dOwn++; if (ownOff.size()<16) ownOff.add(i); }
            else dRest++;
        }
        System.out.println("差异: 头部=" + dHead + " 地形段=" + dTerr + " 省规划段=" + dAdmin + " 归属段=" + dOwn + " 尾部=" + dRest);
        System.out.print("  头部位移:"); for (int o : headOff) System.out.printf(" 0x%X:%d->%d", o, orig[o]&0xFF, out[o]&0xFF); System.out.println();
        System.out.print("  省规划:"); for (int o : adminOff) { int i0=adminStart+((o-adminStart)/2)*2; int ov=((orig[i0+1]&0xFF)<<8)|(orig[i0]&0xFF); int nv=((out[i0+1]&0xFF)<<8)|(out[i0]&0xFF); System.out.printf(" #%d:%d->%d", (o-adminStart)/2, ov, nv);} System.out.println();
        System.out.print("  归属:"); for (int o : ownOff) System.out.printf(" #%d:%d->%d", o-ownStart, orig[o]&0xFF, out[o]&0xFF); System.out.println();
        // 地形段逐格差异
        if (dTerr > 0) {
            List<Integer> changed = new ArrayList<>();
            for (int i = 0; i < total; i++) {
                boolean same = true;
                for (int b = 0; b < 16; b++) if (orig[terrainStart+i*16+b] != out[terrainStart+i*16+b]) { same=false; break; }
                if (!same) changed.add(i);
            }
            System.out.println("  地形段改动格数=" + changed.size() + " -> " + changed.subList(0, Math.min(20, changed.size())));
            for (int i : changed.subList(0, Math.min(6, changed.size()))) {
                System.out.printf("    格%d 原:", i); for (int b=0;b<16;b++) System.out.printf("%02X ", orig[terrainStart+i*16+b]);
                System.out.printf(" 新:"); for (int b=0;b<16;b++) System.out.printf("%02X ", out[terrainStart+i*16+b]);
                System.out.println();
            }
        }
    }
    static int sum(Map<Integer,Integer> mm){int s=0;for(int v:mm.values())s+=v;return s;}
    static String topN(Map<Integer,Integer> mm, int n) {
        List<Map.Entry<Integer,Integer>> l = new ArrayList<>(mm.entrySet());
        l.sort((a,b)-> b.getValue()-a.getValue());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(n, l.size()); i++) sb.append(l.get(i).getKey()).append("x").append(l.get(i).getValue()).append(" ");
        return sb.toString();
    }
}
