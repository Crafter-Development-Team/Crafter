package com.minecraft.selector.gui;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class LodPreviewTest {
    @Test void autoUsesFileCountBoundaries() {
        int[] counts={0,1,2,9,10,25,26,49,1000};
        int[] intervals={1,1,2,2,4,4,8,8,8};
        for(int i=0;i<counts.length;i++)
            assertEquals(intervals[i],MinecraftMapGUI.resolveLodLevel("Auto (by file count)",counts[i]));
    }

    @Test void dynamicAutoIsStableAndExplicitQualityStillOverrides() {
        assertEquals(2,MinecraftMapGUI.resolveDynamicPreviewQuality("Auto"));
        assertEquals(1,MinecraftMapGUI.resolveDynamicPreviewQuality("1 (full)"));
        assertEquals(2,MinecraftMapGUI.resolveDynamicPreviewQuality("2 (1/2)"));
        assertEquals(4,MinecraftMapGUI.resolveDynamicPreviewQuality("4 (1/4)"));
        assertEquals(8,MinecraftMapGUI.resolveDynamicPreviewQuality("8 (1/8)"));
    }

    @Test void explicitLodOverridesFileCount() {
        String[] options={"1 (full)","2 (1/2)","4 (1/4)","8 (1/8)"};
        int[] intervals={1,2,4,8};
        for(int i=0;i<options.length;i++) for(int count:new int[]{1,9,25,49})
            assertEquals(intervals[i],MinecraftMapGUI.resolveLodLevel(options[i],count));
    }

    /**
     * 默认必须是 1 (full)：只有 sampleInterval == 1 才会走列缓存的换带即时重合成，
     * 否则拖 Y 滑块会退回整区重扫。这条断言防止默认值被改回 Auto。
     */
    @Test void defaultPreviewQualityKeepsInstantBandRecomposition() {
        assertEquals("1 (full)",MinecraftMapGUI.DEFAULT_PREVIEW_QUALITY);
        assertEquals(1,MinecraftMapGUI.resolveDynamicPreviewQuality(MinecraftMapGUI.DEFAULT_PREVIEW_QUALITY));
        assertEquals(1,MinecraftMapGUI.resolveLodLevel(MinecraftMapGUI.DEFAULT_PREVIEW_QUALITY,49));
    }
}
