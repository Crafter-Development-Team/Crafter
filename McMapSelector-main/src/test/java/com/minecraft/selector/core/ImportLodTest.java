package com.minecraft.selector.core;

import com.minecraft.selector.gui.SelectionMath.Bounds;
import com.minecraft.selector.gui.SelectionShape;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ImportLodTest {
    @Test void matchesEuclideanInclusiveRingsAndFallbackAtEveryMaxLevel() {
        for(int max=0;max<=4;max++) {
            ImportLod lod=new ImportLod(max,false,0,0,5,5,5,5);
            assertEquals(0,lod.levelAtChunk(0,0));
            assertEquals(0,lod.levelAtChunk(3,4)); // exactly radius 5, not Manhattan distance
            assertEquals(Math.min(max,1),lod.levelAtChunk(4,4));
            assertEquals(Math.min(max,1),lod.levelAtChunk(-6,-8)); // exactly radius 10
            assertEquals(Math.min(max,2),lod.levelAtChunk(10,1));
            assertEquals(Math.min(max,2),lod.levelAtChunk(9,12)); // exactly radius 15
            assertEquals(Math.min(max,3),lod.levelAtChunk(16,0));
            assertEquals(Math.min(max,3),lod.levelAtChunk(12,16)); // exactly radius 20
            assertEquals(max,lod.levelAtChunk(21,0));
        }
    }
    @Test void negativeManualBlocksAreFlooredToChunksAndZeroWidthsCollapse() {
        ImportLod lod=new ImportLod(4,false,-1,-17,0,0,0,0);
        assertEquals(0,lod.levelAtChunk(-1,-2));
        assertEquals(4,lod.levelAtChunk(0,-2));
        assertEquals(4,lod.levelAtChunk(-1,-1));
    }
    @Test void autoCenterUsesChunkMidpointWithCppTruncation() {
        ImportLod lod=new ImportLod(2,true,123,456,8,8,8,8);
        ImportLod center=lod.resolved(new Bounds(-33,-17,-1,0),WorldAnchors.empty(),"minecraft:overworld");
        assertEquals(-32,center.centerX); // chunks -3..-1 -> -2
        assertEquals(-16,center.centerZ); // chunks -2..0 -> -1
        center=lod.resolved(new Bounds(-16,-1,15,0),WorldAnchors.empty(),"minecraft:overworld");
        assertEquals(0,center.centerX);assertEquals(0,center.centerZ); // chunks -1..0 -> 0, toward zero
    }
    @Test void autoFallbackUsesOnlyAnchorsInTheCurrentDimension() {
        com.minecraft.selector.nbt.NBTReader.NBTCompound data=new com.minecraft.selector.nbt.NBTReader.NBTCompound("");
        data.put("SpawnX",new com.minecraft.selector.nbt.NBTReader.NBTInt("SpawnX",35));
        data.put("SpawnZ",new com.minecraft.selector.nbt.NBTReader.NBTInt("SpawnZ",-2));
        WorldAnchors anchors=WorldAnchors.parse(data);
        assertEquals(35,ImportLod.DEFAULT.resolved(null,anchors,"minecraft:overworld").centerX);
        assertEquals(0,ImportLod.DEFAULT.resolved(null,anchors,"minecraft:the_nether").centerX);
        assertEquals(123,new ImportLod(2,false,123,0,8,8,8,8).resolved(null,anchors,"minecraft:overworld").centerX);
    }
    @Test void arithmeticDoesNotOverflowWithLargeSupportedIntegers() {
        ImportLod lod=new ImportLod(4,false,Integer.MIN_VALUE,Integer.MAX_VALUE,
            Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE);
        assertEquals(4L*Integer.MAX_VALUE,lod.radiusChunks(3));
        assertEquals(0,lod.levelAtChunk(Math.floorDiv(Integer.MIN_VALUE,16),Math.floorDiv(Integer.MAX_VALUE,16)));
        ImportLod center=ImportLod.DEFAULT.resolved(new Bounds(Integer.MIN_VALUE,Integer.MIN_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE),WorldAnchors.empty(),"minecraft:overworld");
        assertEquals(0,center.centerX);assertEquals(0,center.centerZ);
    }
    /** 中键拖动用：方块坐标按 floorDiv 向下取整到所属区块的最小方块坐标。 */
    @Test void alignCenterToChunkFloorsTowardNegativeInfinity() {
        assertEquals(0,ImportLod.alignCenterToChunk(0));
        assertEquals(0,ImportLod.alignCenterToChunk(15));
        assertEquals(16,ImportLod.alignCenterToChunk(16));
        assertEquals(16,ImportLod.alignCenterToChunk(31));
        assertEquals(-16,ImportLod.alignCenterToChunk(-1));
        assertEquals(-16,ImportLod.alignCenterToChunk(-16));
        assertEquals(-32,ImportLod.alignCenterToChunk(-17));
        // 极端值不溢出
        assertEquals(Integer.MIN_VALUE,ImportLod.alignCenterToChunk(Integer.MIN_VALUE));
        assertEquals(2147483632,ImportLod.alignCenterToChunk(Integer.MAX_VALUE));
    }

    /** 拖动必须退出自动居中，否则 resolved() 会用选区/出生点覆盖手动中心。 */
    @Test void withManualCenterForcesAutoOffAndAlignsButKeepsEverythingElse() {
        ImportLod auto=new ImportLod(4,true,123,456,1,2,3,4);
        ImportLod dragged=auto.withManualCenter(20,-1);
        assertFalse(dragged.autoCenter,"dragging must leave auto center");
        assertEquals(16,dragged.centerX);   // 20 -> chunk 1 -> block 16
        assertEquals(-16,dragged.centerZ);  // -1 -> chunk -1 -> block -16
        assertEquals(4,dragged.maxLevel);
        for(int i=0;i<4;i++) assertEquals(auto.distance(i),dragged.distance(i),"distance"+i+" must survive");
        // 拖出来的中心不再被选区重算覆盖
        ImportLod settled=dragged.resolved(new Bounds(0,0,511,511),WorldAnchors.empty(),"minecraft:overworld");
        assertEquals(16,settled.centerX);assertEquals(-16,settled.centerZ);
    }

    /** 对齐后的中心必须让 levelAtChunk 落在预期的环里（拖动的实际效果）。 */
    @Test void draggedCenterDrivesRingMembership() {
        ImportLod lod=new ImportLod(4,false,0,0,5,5,5,5).withManualCenter(160,0); // chunk 10，累计半径 5/10/15/20
        assertEquals(0,lod.levelAtChunk(10,0));    // 圆心所在区块
        assertEquals(0,lod.levelAtChunk(13,4));    // 距圆心恰好 5 区块
        assertEquals(1,lod.levelAtChunk(14,4));    // 距圆心 √32，落在 L1
        assertEquals(1,lod.levelAtChunk(0,0));     // 距圆心恰好 10 区块 = L1 边界（含）
        assertEquals(3,lod.levelAtChunk(-10,0));   // 距圆心恰好 20 区块 = L3 边界（含）
        assertEquals(4,lod.levelAtChunk(31,0));    // 距圆心 21 区块，超出 L3
    }

    /** 阶梯圆的每行半宽：必须与 C++ 的 dx*dx+dz*dz <= r*r 完全一致（含等号、整区块判定）。 */
    @Test void chunkRingHalfWidthMatchesTheCppChunkInclusiveTest() {
        // r=5：该行包含区块满足 |dx| <= floor(sqrt(25-dz^2))
        assertEquals(5,ImportLod.chunkRingHalfWidth(5,0));
        assertEquals(4,ImportLod.chunkRingHalfWidth(5,3));   // sqrt(16)=4
        assertEquals(3,ImportLod.chunkRingHalfWidth(5,4));   // sqrt(9)=3
        assertEquals(0,ImportLod.chunkRingHalfWidth(5,5));   // sqrt(0)=0：边界那一格也算在内
        assertEquals(-1,ImportLod.chunkRingHalfWidth(5,6));  // 行完全在圆外
        assertEquals(-1,ImportLod.chunkRingHalfWidth(5,-6));
        assertEquals(0,ImportLod.chunkRingHalfWidth(0,0));   // 半径为 0 只剩圆心那一格
        // 与 levelAtChunk 的判定互为印证：区块 (dx,dz) 落在 level 0 当且仅当 |dx| <= half(dz)
        ImportLod lod=new ImportLod(4,false,0,0,5,5,5,5);
        for(int dz=-6;dz<=6;dz++) for(int dx=-6;dx<=6;dx++) {
            int half=ImportLod.chunkRingHalfWidth(5,dz);
            boolean inside=half>=0 && Math.abs(dx)<=half;
            assertEquals(inside?0:Math.min(4,1),lod.levelAtChunk(dx,dz),
                "mismatch at chunk ("+dx+","+dz+")");
        }
    }

    @Test void cliSeedsAllFieldsAndStripsOnlyItsOwnFlags() {
        String[] args={"--world-path","/tmp/world","--import-lod-max","2","--import-lod-auto-center","false",
            "--import-lod-cx","-17","--import-lod-cz","31","--import-lod-d0","0","--import-lod-d1","3",
            "--import-lod-d2","4","--import-lod-d3","5","--min-x","-1"};
        ImportLod lod=ImportLod.parse(args);
        assertEquals(2,lod.maxLevel);assertFalse(lod.autoCenter);assertEquals(-17,lod.centerX);
        assertEquals(31,lod.centerZ);assertEquals(0,lod.distance(0));assertEquals(12,lod.radiusChunks(3));
        assertArrayEquals(new String[]{"--world-path","/tmp/world","--min-x","-1"},ImportLod.withoutFlags(args));
        assertEquals(ImportLod.DEFAULT.json(),ImportLod.parse(new String[0]).json());
    }
    @Test void invalidSeedsAreAtomicAndStrippedWithoutConsumingOtherFlags() {
        String[][] invalid={{"--import-lod-max","5"},{"--import-lod-d0","-1"},{"--import-lod-cx","2147483648"},
            {"--import-lod-auto-center","yes"},{"--import-lod-max","2","--import-lod-max","3"},{"--import-lod-max"},
            {"--import-lod-max","--world-path","/tmp/world"},{"--import-lod-d1","1.5"}};
        for(String[] args:invalid) assertThrows(IllegalArgumentException.class,()->ImportLod.parse(args));
        assertArrayEquals(new String[]{"--world-path","/tmp/world"},ImportLod.withoutFlags(invalid[6]));
    }
    @Test void exportedJsonAlwaysIncludesOffAndPreservesAabbAndUnits() throws Exception {
        for(int max:new int[]{0,2}) {
            ImportLod lod=new ImportLod(max,false,-17,31,0,8,16,24);
            JsonNode json=new ObjectMapper().valueToTree(CoordinatesPayload.create(new Bounds(-32,-16,47,63),-64,100,42,
                "minecraft:overworld",null,null,new SelectionShape(),false,new WaypointStore(),false,lod));
            assertEquals(-32,json.get("minX").intValue());assertEquals(63,json.get("maxZ").intValue());
            assertEquals(-64,json.get("minY").intValue());assertEquals(100,json.get("maxY").intValue());
            assertEquals(max,json.at("/importLod/maxLevel").intValue());
            assertEquals(-17,json.at("/importLod/centerX").intValue());
            assertEquals(16,json.at("/importLod/distance2").intValue());
        }
    }
}
