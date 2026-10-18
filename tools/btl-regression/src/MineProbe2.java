import com.xckeji.bj.file.FileParser;
import com.xckeji.bj.model.MapData;
import com.xckeji.bj.model.TerrainTile;
import java.io.File; import java.nio.file.*;
/** 埋一颗雷后，把保存出来的文件里那条记录原样打出来，跟官方可见雷的取值对照。 */
public class MineProbe2 {
    public static void main(String[] a) throws Exception {
        byte[] src = Files.readAllBytes(Paths.get(a[0]));
        MapData m = FileParser.loadFile(src.clone(), new File(a[0]).getName());
        int sx=-1, sy=-1;
        for (int y=0;y<m.height && sx<0;y++) for (int x=0;x<m.width;x++) {
            TerrainTile t=m.getTile(x,y);
            if (t!=null && t.bmTerrain1Group==1) { sx=x; sy=y; break; }
        }
        FileParser.addMine(m, sx, sy, 3);
        byte[] out = FileParser.saveAsBTL(m);
        FileParser.BtlHeaderInfo h = FileParser.parseBTLHeader(out);
        int army = h.version==1?48:64;
        int start = 128+h.legionCount*300+(h.width*h.height*16)+h.sectionTiles*2+h.sectionTiles
                  + h.buildingCount*32 + h.armyCount*army;
        int n = (out[start-8]&0xFF); // 仅作偏移参考
        int cnt = java.nio.ByteBuffer.wrap(out).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(0x68);
        System.out.printf("  新加的雷在第 %d 条（共 %d 条）；海洋格=(%d,%d)%n", cnt-1, cnt, sx, sy);
        int o = start + (cnt-1)*12;
        System.out.printf("  保存后文件里的记录：坐标=%d 军团=%d 等级=%d 血量=%d 类型字节(0x8)=%d%n",
            (out[o]&0xFF)|((out[o+1]&0xFF)<<8), (out[o+2]&0xFF)|((out[o+3]&0xFF)<<8),
            out[o+4]&0xFF, (out[o+6]&0xFF)|((out[o+7]&0xFF)<<8), out[o+8]&0xFF);
        System.out.printf("  对照官方可见水雷的取值：类型字节=34、血量=70  →  %s%n",
            ((out[o+8]&0xFF)==34 && ((out[o+6]&0xFF)|((out[o+7]&0xFF)<<8))==70) ? "✔ 完全一致（游戏会显示成水雷）" : "★★ 还对不上");
    }
}
