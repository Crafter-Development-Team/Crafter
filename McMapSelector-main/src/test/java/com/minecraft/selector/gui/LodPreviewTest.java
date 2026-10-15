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

    @Test void explicitLodOverridesFileCount() {
        String[] options={"1 (full)","2 (1/2)","4 (1/4)","8 (1/8)"};
        int[] intervals={1,2,4,8};
        for(int i=0;i<options.length;i++) for(int count:new int[]{1,9,25,49})
            assertEquals(intervals[i],MinecraftMapGUI.resolveLodLevel(options[i],count));
    }
}
