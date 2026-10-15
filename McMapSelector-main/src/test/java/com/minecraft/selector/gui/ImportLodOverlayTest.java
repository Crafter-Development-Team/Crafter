package com.minecraft.selector.gui;

import com.minecraft.selector.core.ImportLod;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

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
    @Test void circlesUseCumulativeChunkWidthsAndToggleOffWithoutChangingSettings() {
        ViewOverlays overlays=overlays(new ImportLod(2,false,-1,-17,2,3,99,99));
        Function<Point,Point> screen=p->new Point(350+p.x,350+p.y);
        BufferedImage image=paint(overlays,1,screen,null);
        // Manual center -1,-17 is chunk -1,-2, drawn at block -16,-32 = pixel 334,318.
        assertNotEquals(0,image.getRGB(334+32,318));
        assertNotEquals(0,image.getRGB(334,318+80));
        assertEquals(0,image.getRGB(334+48,318)); // no spurious radius from disabled widths
        assertEquals(0,image.getRGB(334+96,318));
        overlays.importLodVisible=false;
        assertEquals(0,paint(overlays,1,screen,null).getRGB(334+32,318));
        assertEquals(2,overlays.importLod.maxLevel);
        overlays.importLodVisible=true;overlays.importLod=ImportLod.DEFAULT;
        assertEquals(0,paint(overlays,1,screen,null).getRGB(334+32,318));
    }
    @Test void selectionAndZoomMoveTheAutoCenterAndScaleTheRadiusLive() {
        ViewOverlays overlays=overlays(new ImportLod(1,true,999,999,2,8,8,8));
        Function<Point,Point> screen=p->new Point(350+2*p.x,350+2*p.y);
        BufferedImage image=paint(overlays,2,screen,new SelectionMath.Bounds(0,0,31,31));
        assertNotEquals(0,image.getRGB(350+64,350));
        image=paint(overlays,2,screen,new SelectionMath.Bounds(32,0,63,31));
        assertNotEquals(0,image.getRGB(350+128,350)); // center moved 2 chunks, radius still 2 chunks
        assertEquals(0,image.getRGB(350-64,350));
    }
}
