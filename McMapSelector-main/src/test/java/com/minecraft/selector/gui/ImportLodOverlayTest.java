package com.minecraft.selector.gui;

import com.minecraft.selector.core.ImportLod;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * LOD 圆环必须画成 WorldImporter 真实判定的形状：按<b>整块区块</b>的阶梯圆，
 * 而不是光滑圆弧。关键差别是包含判定用的是区块索引距离，
 * 所以编号 ccx+r 的区块整个被包含，外边界落在 (ccx+r+1)*16 方块上 —— 比光滑圆大最多 16 方块。
 */
class ImportLodOverlayTest {
    private BufferedImage paint(ViewOverlays overlays,double scale,Function<Point,Point> screen,SelectionMath.Bounds bounds) {
        BufferedImage image=new BufferedImage(700,700,BufferedImage.TYPE_INT_ARGB);
        Graphics2D g=image.createGraphics();
        try {overlays.draw(g,700,700,scale,screen,bounds,null,null,null);} finally {g.dispose();}
        return image;
    }
    private ViewOverlays overlays(ImportLod lod) {
        ViewOverlays overlays=new ViewOverlays();overlays.markers=false;overlays.importLod=lod;return overlays;
    }
    /** 邻域内是否有像素被画过（描边有 2px 宽且开了抗锯齿，单点取样太脆）。 */
    private static boolean ink(BufferedImage image,int x,int y,int pad) {
        for(int dx=-pad;dx<=pad;dx++) for(int dy=-pad;dy<=pad;dy++) {
            int px=x+dx,py=y+dy;
            if(px<0||py<0||px>=image.getWidth()||py>=image.getHeight()) continue;
            if((image.getRGB(px,py)>>>24)!=0) return true;
        }
        return false;
    }

    @Test void ringsAreChunkQuantizedSteppedCirclesAndToggleOffWithoutChangingSettings() {
        // maxLevel=2、距离 2/3：半径 r0=2、r1=5 区块。中心方块 (-1,-17) → 区块 (-1,-2)。
        ViewOverlays overlays=overlays(new ImportLod(2,false,-1,-17,2,3,99,99));
        Function<Point,Point> screen=p->new Point(350+p.x,350+p.y);
        BufferedImage image=paint(overlays,1,screen,null);
        int baseX=334, rowMidY=326;   // 圆心方块 (-16,-32)；+8 取在 dz=0 那一行的中间，避开台阶拐角
        // r0=2：dz=0 行包含区块 dx∈[-2,2]，外边界在 (ccx+2+1)*16 = +48 方块处。
        // 光滑圆会画在 +32 —— 这正是本改动要修正的地方。
        assertTrue(ink(image,baseX+48,rowMidY,2),"level 0 boundary must sit one whole chunk out at +48");
        assertFalse(ink(image,baseX+32,rowMidY,2),"interior of the level 0 disc must not be outlined");
        // r1=5：dz=0 行外边界在方块 x=(ccx+5+1)*16=80 处，绝对屏幕 x=430 = baseX+96。
        assertTrue(ink(image,baseX+96,rowMidY,2),"level 1 boundary at +96 from the center block");
        // 两带之间（方块 x=48 → 区块 3 → |dx|=4，落在 LOD1 内部）不应有线
        assertFalse(ink(image,baseX+64,rowMidY,2),"no spurious ring between the two bands");
        // 被禁用的宽度绝不能凭空多出一圈（旧断言的原意）
        assertFalse(ink(image,baseX+160,rowMidY,2),"disabled distances must not add rings");

        overlays.importLodVisible=false;
        assertFalse(ink(paint(overlays,1,screen,null),baseX+48,rowMidY,2),"hidden overlay paints nothing");
        assertEquals(2,overlays.importLod.maxLevel);
        overlays.importLodVisible=true;overlays.importLod=ImportLod.DEFAULT;
        assertFalse(ink(paint(overlays,1,screen,null),baseX+48,rowMidY,2),"maxLevel 0 paints nothing");
    }

    /** 阶梯的台阶：同一圆的外边界必须整区块地收窄，而不是沿光滑弧线连续变化。 */
    @Test void stepsAdvanceByWholeChunksPerRow() {
        ViewOverlays overlays=overlays(new ImportLod(1,false,0,0,3,8,8,8));   // r0=3 区块
        Function<Point,Point> screen=p->new Point(350+p.x,350+p.y);
        BufferedImage image=paint(overlays,1,screen,null);
        // 圆心区块 (0,0) → 圆心方块 (0,0) → 像素 (350,350)。各行取中点探测。
        // dz=0 行（z 0..16）：half=3 → 右边界 (0+3+1)*16 = +64 方块。
        assertTrue(ink(image,350+64,350+8,2),"dz=0 row boundary at +64");
        // dz=2 行（z 32..48）：half=floor(sqrt(9-4))=2 → 右边界 +48 方块。
        assertTrue(ink(image,350+48,350+40,2),"dz=2 row boundary steps in to +48");
        // dz=3 行（z 48..64）：half=floor(sqrt(0))=0 → 右边界 +16 方块。
        assertTrue(ink(image,350+16,350+56,2),"dz=3 row boundary steps in to +16");
        // dz=4 行已在半径外（|dz|>3），这一行不应有任何竖直边
        assertFalse(ink(image,350+16,350+80,2),"rows beyond the radius are empty");
        // 台阶只会出现在整区块的 x 上：光滑弧在 dz=1 行会落在 +56 附近，这里必须为空
        assertFalse(ink(image,350+56,350+24,2),"no smooth arc between chunk-aligned steps");
    }

    /** 十字必须画在"所属区块的中心"，且是严格对称的加号。 */
    @Test void crosshairIsCentredInItsChunkAndSymmetrical() {
        ViewOverlays overlays=overlays(new ImportLod(1,false,0,0,2,8,8,8));   // r0=2 区块
        Function<Point,Point> screen=p->new Point(350+p.x,350+p.y);
        BufferedImage image=paint(overlays,1,screen,null);
        // 中心区块 (0,0) 的中心是方块 (8,8) → 像素 (358,358)。
        // 旧实现画在区块角 (0,0) → (350,350)，相对阶梯圆偏半格。
        assertTrue(ink(image,358,358,1),"crosshair must sit at the chunk centre, not its corner");
        assertFalse(ink(image,350,350,0),"chunk corner must stay clear");
        // 白十字的包围盒：两臂各 13 像素，几何中心 (358,358)
        int minX=9999,maxX=-1,minY=9999,maxY=-1;
        for(int x=340;x<=376;x++) for(int y=340;y<=376;y++)
            if((image.getRGB(x,y)&0xFFFFFF)==0xFFFFFF){
                minX=Math.min(minX,x);maxX=Math.max(maxX,x);minY=Math.min(minY,y);maxY=Math.max(maxY,y);
            }
        assertEquals(13,maxX-minX+1,"horizontal arm length");
        assertEquals(13,maxY-minY+1,"vertical arm length");
        assertEquals(358,(minX+maxX)/2,"horizontally centred");
        assertEquals(358,(minY+maxY)/2,"vertically centred");
    }

    @Test void selectionAndZoomMoveTheAutoCenterAndScaleTheRadiusLive() {
        ViewOverlays overlays=overlays(new ImportLod(1,true,999,999,2,8,8,8));
        Function<Point,Point> screen=p->new Point(350+2*p.x,350+2*p.y);
        // 选区区块 0..1 → 中心区块 0；r0=2 → 右边界 (0+2+1)*16 = 48 方块 → 屏幕 350+96。
        BufferedImage image=paint(overlays,2,screen,new SelectionMath.Bounds(0,0,31,31));
        assertTrue(ink(image,350+96,350,3),"first selection boundary");
        // 选区区块 2..3 → 中心区块 2 → 右边界 (2+2+1)*16 = 80 方块 → 屏幕 350+160。
        image=paint(overlays,2,screen,new SelectionMath.Bounds(32,0,63,31));
        assertTrue(ink(image,350+160,350,3),"center moved 2 chunks, boundary follows");
        assertFalse(ink(image,350-64,350,3),"outside the ring stays clear");
    }
}
