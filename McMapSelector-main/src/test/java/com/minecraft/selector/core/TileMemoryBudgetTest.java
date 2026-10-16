package com.minecraft.selector.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.minecraft.selector.gui.SelectionMath;
import java.awt.Rectangle;
import java.lang.reflect.Field;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 逐列元数据（方块状态 / 生物群系 / opaque+dry 高度）曾是每块地图 +4 MB 的来源：
 * RenderedMap 里有四个 int[width*height]。这些测试锁住"它们不会回来"。
 */
class TileMemoryBudgetTest {
    @TempDir Path directory;

    @Test void renderedMapHasNoPerColumnIntArrays() {
        for(Field f:new RenderedMap(4,4).getClass().getDeclaredFields()) {
            if(f.getType()==int[].class) {
                // 只允许 heights；任何额外的 int[] 都是每像素元数据回归。
                assertEquals("heights",f.getName(),"unexpected per-column int[] array: "+f.getName());
            }
        }
    }

    /** 512x512 瓦片：旧的四个 int[] 是 4 MB；现在只允许 heights + highlight + BitSet。 */
    @Test void tileFootprintStaysNearBaseline() throws Exception {
        RenderedMap tile=new RenderedMap(512,512);
        for(int z=0;z<512;z++) for(int x=0;x<512;x++) {tile.setHeightAt(x,z,64);tile.markLoaded(x,z);}
        long metadata=0;
        for(Field f:tile.getClass().getDeclaredFields()) {
            f.setAccessible(true);
            Object value=f.get(tile);
            if(value instanceof int[]&&!f.getName().equals("heights")) metadata+=((int[])value).length*4L;
            if(value instanceof java.util.BitSet) metadata+=((java.util.BitSet)value).size()/8L;
        }
        // 旧的 opaqueHeights+dryHeights+biomeIds+blockStates 至少 4 MB。
        assertTrue(metadata<200*1024,"metadata footprint should stay far below the old 4 MB, was "+metadata+" bytes");
    }

    @Test void columnCacheKeepsOnlyPerRunIdStrings() {
        for(Field f:ColumnCache.class.getDeclaredFields()) {
            if(f.getType()==String[].class)
                assertEquals("runId",f.getName(),"unexpected per-run String[] array: "+f.getName());
        }
    }

    /** 逐列元数据移除后，已加载列标记仍必须区分"已加载空列"与"缺失区块"。 */
    @Test void loadedMaskStillDistinguishesEmptyColumnsFromMissingChunks() {
        RenderedMap map=new RenderedMap(4,4);
        map.markLoaded(0,0);map.setHeightAt(0,0,RenderedMap.NO_HEIGHT);
        assertTrue(map.isLoadedAt(0,0));
        assertFalse(map.isLoadedAt(1,0));
        SelectionYFit.Result r=SelectionYFit.scan(map,0,0,1,new SelectionMath.Bounds(0,0,0,0),true);
        assertEquals(1,r.empty);assertEquals(0,r.missing);
        SelectionYFit.Result outside=SelectionYFit.scan(map,0,0,1,new SelectionMath.Bounds(1,0,1,0),true);
        assertEquals(1,outside.missing);assertEquals(0,outside.empty);
    }

    @Test void zoomExtentsUsesLoadedMaskNotHeights() {
        RenderedMap map=new RenderedMap(64,64);
        for(int z=10;z<20;z++) for(int x=10;x<20;x++) {map.markLoaded(x,z);map.setHeightAt(x,z,5);}
        Rectangle b=map.loadedPixelBounds();
        assertEquals(10,b.x);assertEquals(10,b.y);assertEquals(10,b.width);assertEquals(10,b.height);
    }
}
