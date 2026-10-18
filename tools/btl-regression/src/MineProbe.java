import com.xckeji.bj.file.FileParser;
import com.xckeji.bj.model.MapData;
import com.xckeji.bj.model.TerrainTile;
import java.io.File; import java.nio.file.*;
/** 模拟「创造模式在海上埋水雷」，检查地形段有没有被动过。 */
public class MineProbe {
    public static void main(String[] a) throws Exception {
        byte[] src = Files.readAllBytes(Paths.get(a[0]));
        MapData m = FileParser.loadFile(src.clone(), new File(a[0]).getName());
        FileParser.BtlHeaderInfo h = FileParser.parseBTLHeader(m.btlOriginalData);
        int t0 = h.terrainStart;
        int before = java.util.Arrays.hashCode(java.util.Arrays.copyOfRange(m.btlOriginalData, t0, t0 + h.width*h.height*16));
        // 找第一格海洋
        int sx=-1, sy=-1;
        for (int y=0;y<m.height && sx<0;y++) for (int x=0;x<m.width;x++) {
            TerrainTile t = m.getTile(x,y);
            if (t!=null && t.bmTerrain1Group==1) { sx=x; sy=y; break; }
        }
        System.out.printf("  目标海洋格 = (%d,%d)  地形段起点=%d%n", sx, sy, t0);
        TerrainTile beforeTile = m.getTile(sx, sy);
        System.out.printf("  埋雷前：组=%d 号=%d 装饰=(%d,%d)/(%d,%d)%n",
            beforeTile.bmTerrain1Group, beforeTile.bmTerrain1Id,
            beforeTile.decoration1Group, beforeTile.decoration1Id,
            beforeTile.decoration2Group, beforeTile.decoration2Id);
        MapData.Trap t = FileParser.addMine(m, sx, sy, 3);
        System.out.printf("  addMine 返回：%s  x=%d y=%d level=%d hp=%d legion=%d raw8=%d%n",
            t==null?"null":"Trap", t==null?-1:t.x, t==null?-1:t.y, t==null?-1:t.level, t==null?-1:t.hp,
            t==null?-1:t.legion, t==null?-1:(t.raw[8]&0xFF));
        if (t!=null) {
            byte[] raw = t.raw.clone();
            raw[2]=(byte)3; raw[4]=1; raw[6]=70; raw[7]=0; raw[8]=34;
            FileParser.patchTrap(m, t, raw);
            System.arraycopy(raw, 0, t.raw, 0, 12);      // ← 就是新加的那行
            System.out.printf("  回写内存后 t.raw[8] = %d  %s%n", t.raw[8]&0xFF,
                (t.raw[8]&0xFF)==34 ? "✔ 内存副本已更新" : "★★ 仍是旧值");
        }
        int after = java.util.Arrays.hashCode(java.util.Arrays.copyOfRange(m.btlOriginalData, t0, t0 + h.width*h.height*16));
        System.out.printf("  地形段哈希：前=%d 后=%d  %s%n", before, after, before==after?"✔ 完全没动":"★★ 地形段被改了！");
        TerrainTile afterTile = m.getTile(sx, sy);
        System.out.printf("  埋雷后：组=%d 号=%d 装饰=(%d,%d)/(%d,%d)%n",
            afterTile.bmTerrain1Group, afterTile.bmTerrain1Id,
            afterTile.decoration1Group, afterTile.decoration1Id,
            afterTile.decoration2Group, afterTile.decoration2Id);
        byte[] out = FileParser.saveAsBTL(m);
        FileParser.BtlHeaderInfo h2 = FileParser.parseBTLHeader(out);
        int n2 = h2.terrainStart;
        int after2 = java.util.Arrays.hashCode(java.util.Arrays.copyOfRange(out, n2, n2 + h2.width*h2.height*16));
        System.out.printf("  保存后再读：地形段哈希=%d  %s%n", after2, after2==before?"✔ 与埋雷前一致":"★★ 保存把地形改了！");
        // 全部陷阱
        System.out.printf("  当前陷阱共 %d 颗：%n", m.traps.size());
        for (MapData.Trap x : m.traps)
            System.out.printf("     x=%d y=%d level=%d hp=%d legion=%d raw8=%d%n", x.x, x.y, x.level, x.hp, x.legion, x.raw[8]&0xFF);
    }
}
