import com.xckeji.bj.file.FileParser;
import com.xckeji.bj.model.MapData;
import java.nio.file.*;
import java.util.*;

public class Conq {
    public static void main(String[] a) throws Exception {
        byte[] btl = Files.readAllBytes(Paths.get(a[0]));
        byte[] bin = Files.readAllBytes(Paths.get(a[1]));
        byte[] btl0 = btl.clone(), bin0 = bin.clone();
        MapData m = FileParser.loadFile(btl, new java.io.File(a[0]).getName());
        System.out.println("BTL: w=" + m.width + " h=" + m.height + " coordBase=" + m.coordBase
            + " buildings=" + m.buildings.size() + " armies=" + m.armies.size());
        FileParser.loadConquestTerrain(m, m.binOriginalData = bin);
        m.binFileName = new java.io.File(a[1]).getName();
        // 统计地形
        Map<Integer,Integer> groups = new TreeMap<>();
        for (int i = 0; i < m.tiles.size(); i++) groups.merge(m.tiles.get(i).bmTerrain1Group, 1, Integer::sum);
        System.out.println("地形组分布: " + groups);
        // 涂 3 格：一个变海、两个变陆地
        Set<Integer> painted = new HashSet<>();
        int[] idx = { 500, 501, 1500 };
        int[] gid = { 1, 0, 20 };
        for (int k = 0; k < idx.length; k++) { m.tiles.get(idx[k]).setTerrain(gid[k]); painted.add(idx[k]); }
        m.finishPaint(painted);
        byte[] outBtl = FileParser.saveAsBTL(m);
        byte[] outBin = FileParser.saveAsBIN(m);
        Files.write(Paths.get("/private/tmp/btltest/out_conq.btl"), outBtl);
        Files.write(Paths.get("/private/tmp/btltest/out_conq.bin"), outBin);
        System.out.println("BTL len " + btl0.length + " -> " + outBtl.length + "   BIN len " + bin0.length + " -> " + outBin.length);

        // BTL 差异分区
        FileParser.BtlHeaderInfo h = FileParser.parseBTLHeader(btl0);
        int total = h.width * h.height;
        int terrStart = h.terrainStart;
        int adminStart = h.adminStart, ownStart = h.ownershipStart, bldStart = h.buildingStart;
        int cH=0,cT=0,cA=0,cO=0,cR=0, otherAdmin=0;
        for (int i = 0; i < Math.min(btl0.length, outBtl.length); i++) {
            if (btl0[i]==outBtl[i]) continue;
            if (i<terrStart) cH++; else if (i<adminStart) cT++; else if (i<ownStart) cA++; else if (i<bldStart) cO++; else cR++;
        }
        for (int i = 0; i < total; i++) {
            int o = adminStart + i*2;
            if (btl0[o]==outBtl[o] && btl0[o+1]==outBtl[o+1]) continue;
            int ov = ((btl0[o+1]&0xFF)<<8)|(btl0[o]&0xFF), nv = ((outBtl[o+1]&0xFF)<<8)|(outBtl[o]&0xFF);
            if (!(painted.contains(i) && m.tiles.get(i).bmTerrain1Group==1 && nv==65535)) { otherAdmin++; if (otherAdmin<6) System.out.println("  非预期省规划改动 #"+i+" "+ov+"->"+nv+" grp="+m.tiles.get(i).bmTerrain1Group); }
        }
        System.out.println("BTL 差异: 头="+cH+" 地形="+cT+" 省规划="+cA+"(非海洋规则="+otherAdmin+") 归属="+cO+" 尾部="+cR);

        // BIN 差异
        int[] dims = {148,84,16};
        int binW = java.nio.ByteBuffer.wrap(bin0).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(8);
        int binH = java.nio.ByteBuffer.wrap(bin0).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(12);
        int hdr = 16, regionStart = hdr + binW*binH*16;
        int dTerr=0, dReg=0, dHead=0, dTail=0, regOther=0;
        for (int i = 0; i < Math.min(bin0.length, outBin.length); i++) {
            if (bin0[i]==outBin[i]) continue;
            if (i < hdr) dHead++;
            else if (i < regionStart) dTerr++;
            else if (i < regionStart + binW*binH*2) dReg++;
            else dTail++;
        }
        System.out.println("BIN: " + binW + "x" + binH + " 差异 头="+dHead+" 地形="+dTerr+" 省规划="+dReg+" 尾部="+dTail);
        // BIN 地形改动格
        java.util.TreeSet<Integer> changedTiles = new java.util.TreeSet<>();
        for (int i = 0; i < binW*binH; i++) {
            for (int b = 0; b < 16; b++) if (bin0[hdr+i*16+b] != outBin[hdr+i*16+b]) { changedTiles.add(i); break; }
        }
        System.out.println("BIN 地形改动格数=" + changedTiles.size() + " 前几个=" + new ArrayList<>(changedTiles).subList(0, Math.min(12, changedTiles.size())));
        List<String> bad = new ArrayList<>();
        for (int t : changedTiles) {
            int col = t % binW, row = t / binW;
            int li = (row - h.captureY) * h.width + (col - h.captureX);
            if (li < 0 || li >= total || !painted.contains(li)) bad.add(t + "(→本地" + li + ")");
        }
        System.out.println("BIN 地形改动中不属于被涂格牵连的格: " + bad);
    }
}
