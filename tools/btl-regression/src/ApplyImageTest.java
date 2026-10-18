import com.xckeji.bj.file.FileParser;
import com.xckeji.bj.model.MapData;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.*;
import javax.imageio.ImageIO;
import java.util.*;

/**
 * 给一张图 → 直接生成地形写进地图。
 * 战役/新建（地图序号=0，BTL 自带地形）→ 改 BTL 自己的地形段；
 * 征服（地图序号≠0）→ 改世界底图 world.bin（同时把海洋格的省规划清成 FFFF）。
 * 用法：--apply <图片> <btl> <输出目录> [world.bin]
 */
public class ApplyImageTest {
    static float hueOf(int c) {
        int r=(c>>16)&255, g=(c>>8)&255, b=c&255;
        int mx=Math.max(r,Math.max(g,b)), mn=Math.min(r,Math.min(g,b));
        int v=mx, d=mx-mn;
        if (v<50) return -1f;
        if (d*100/Math.max(1,v)<18) return -1f;
        if (mx==mn) return -1f;
        float h;
        if (mx==r) h=60f*(((g-b)/(float)d)%6);
        else if (mx==g) h=60f*((b-r)/(float)d+2);
        else h=60f*((r-g)/(float)d+4);
        return h<0?h+360f:h;
    }
    static boolean isSea(int c){ float h=hueOf(c); return h>=0 && h>=260f && h<=330f; }

    public static void main(String[] a) throws Exception {
        BufferedImage img = ImageIO.read(new File(a[0]));
        byte[] btl = Files.readAllBytes(Paths.get(a[1]));
        String outDir = a[2];
        byte[] bin = (a.length > 3) ? Files.readAllBytes(Paths.get(a[3])) : null;
        Files.createDirectories(Paths.get(outDir));

        FileParser.BtlHeaderInfo h0 = FileParser.parseBTLHeader(btl);
        MapData m = FileParser.loadFile(btl.clone(), new File(a[1]).getName());
        if (bin != null && !h0.independentTerrain) {
            m.binOriginalData = bin.clone();
            FileParser.loadConquestTerrain(m, bin.clone());
        }
        int W=m.width, H=m.height;
        boolean campaign = h0.independentTerrain;
        System.out.println("地图 " + (campaign ? "战役/新建（自带地形）" : "征服（地形在底图）")
            + "  v" + h0.version + "  " + W + "x" + H + "   图片 " + img.getWidth() + "x" + img.getHeight());

        // ① 逐格取图上该格中心的颜色，先得到一张海陆布尔图
        boolean[] sea0 = new boolean[W*H];
        for (int y=0; y<H; y++) {
            for (int x=0; x<W; x++) {
                // 与 App 内一致：图片拉伸铺满地图，取每格中心的颜色
                int ix = Math.min(img.getWidth()-1,  Math.max(0,(int)((x+0.5)/W*img.getWidth())));
                int iy = Math.min(img.getHeight()-1, Math.max(0,(int)((y+0.5)/H*img.getHeight())));
                sea0[y*W+x] = isSea(img.getRGB(ix,iy) & 0xFFFFFF);
            }
        }
        // ② 降噪：3x3 多数投票走两遍。地图上的文字/描边会留下 1-2 格的孤立杂点，
        //    落在格子网格上就是"海里冒小岛"，投一遍就干净了。
        boolean[] cur = sea0;
        for (int pass=0; pass<2; pass++) {
            boolean[] nx = cur.clone();
            for (int y=1; y<H-1; y++) {
                for (int x=1; x<W-1; x++) {
                    int c=0;
                    for (int dy=-1; dy<=1; dy++)
                        for (int dx=-1; dx<=1; dx++)
                            if (cur[(y+dy)*W+(x+dx)]) c++;
                    nx[y*W+x] = c >= 5;
                }
            }
            cur = nx;
        }
        // ③ 写进地图
        Set<Integer> painted = new HashSet<>();
        int sea=0, land=0;
        for (int i=0; i<W*H; i++) {
            m.tiles.get(i).setTerrain(cur[i]?1:0);
            painted.add(i); m.editedCells.add(i);
            if (cur[i]) sea++; else land++;
        }
        m.finishPaint(painted);
        int fixed = FileParser.normalizeWaterDistricts(m);
        Files.write(Paths.get(outDir+"/"+new File(a[1]).getName()), FileParser.saveAsBTL(m));
        System.out.println("已写入 " + outDir+"/"+new File(a[1]).getName());
        if (bin != null && !campaign) {
            Files.write(Paths.get(outDir+"/world.bin"), FileParser.saveAsBIN(m));
            System.out.println("已写入 " + outDir+"/world.bin");
        }
        System.out.println("地形格：海洋 " + sea + "（" + (sea*100/(W*H)) + "%）　陆地 " + land
            + "　水上省规划已清 FFFF " + fixed + " 格");

        int S = Math.max(2, Math.min(6, 640/Math.max(W,H)));
        BufferedImage pv = new BufferedImage(W*S, H*S, BufferedImage.TYPE_INT_RGB);
        for (int y=0;y<H;y++) for (int x=0;x<W;x++) {
            int col = (m.tiles.get(y*W+x).bmTerrain1Group == 1) ? 0x3E6FB0 : 0x7FA85A;
            for (int dy=0;dy<S;dy++) for (int dx=0;dx<S;dx++) pv.setRGB(x*S+dx, y*S+dy, col);
        }
        ImageIO.write(pv, "png", new File(outDir+"/预览_地形.png"));
        System.out.println("已出预览 " + outDir+"/预览_地形.png");
    }
}
