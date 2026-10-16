package com.minecraft.selector.core;
import com.minecraft.selector.gui.SelectionMath.Bounds;
import com.minecraft.selector.nbt.NBTReader.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
class P0MetadataTest {
    @TempDir Path directory;
    @Test void yFitIgnoresSentinelsAndClipsToAvailableSamples() {
        RenderedMap map=new RenderedMap(3,2);map.setHeightAt(0,0,-128);map.setHeightAt(1,1,655);map.setHeightAt(2,0,10);
        SelectionYFit.Result r=SelectionYFit.scan(map,-5,-8,1,new Bounds(-100,-100,100,100));
        assertEquals(-128,r.min);assertEquals(655,r.max);assertEquals(3,r.count);
        r=SelectionYFit.scan(map,-5,-8,1,new Bounds(-4,-8,-3,-8));assertEquals(10,r.min);assertEquals(10,r.max);assertEquals(1,r.count);
        assertEquals(0,SelectionYFit.scan(map,-5,-8,1,new Bounds(0,0,10,10)).count);
        assertEquals(0,SelectionYFit.scan(new BufferedImage(2,2,2),0,0,1,new Bounds(0,0,1,1)).count);
        assertEquals(0,SelectionYFit.scan(null,0,0,1,new Bounds(0,0,1,1)).count);
        SelectionYFit.Result sampled=SelectionYFit.scan(map,0,0,0.5,new Bounds(0,0,0,0));assertEquals(-128,sampled.min);
    }
    @Test void jsonRoundTripKeepsLegacyFieldsAndEscapesOptionalWorldMetadata() throws Exception {
        ObjectMapper mapper=new ObjectMapper();String name="城市 \"test\"\nworld";
        JsonNode node=mapper.readTree(mapper.writeValueAsBytes(CoordinatesPayload.create(new Bounds(-2,-4,2,4),-64,100,42L,"mod:dimension",name,4321)));
        String[] required={"minX","minY","minZ","maxX","maxY","maxZ","timestamp"};for(String key:required) assertTrue(node.has(key));
        assertEquals(-2,node.get("minX").asInt());assertEquals(-64,node.get("minY").asInt());assertEquals(42L,node.get("timestamp").asLong());
        assertEquals(5,node.get("sizeX").asLong());assertEquals(9,node.get("sizeZ").asLong());assertEquals(165,node.get("sizeY").asLong());assertEquals(45,node.get("columnCount").asLong());
        assertEquals(name,node.get("worldName").asText());assertEquals("mod:dimension",node.get("dimensionId").asText());assertEquals(4321,node.get("dataVersion").asInt());
        JsonNode minimal=mapper.valueToTree(CoordinatesPayload.create(new Bounds(0,0,0,0),0,0,1,null,null,null));
        assertFalse(minimal.has("dimensionId"));assertFalse(minimal.has("worldName"));assertFalse(minimal.has("dataVersion"));
    }
    @Test void gzipLevelDatParsesSpawnPlayerDimensionAndMetadata() throws Exception {
        try(DataOutputStream out=new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(directory.resolve("level.dat"))))) {
            out.writeByte(10);out.writeUTF("");out.writeByte(10);out.writeUTF("Data");
            out.writeByte(3);out.writeUTF("SpawnX");out.writeInt(-12);out.writeByte(3);out.writeUTF("SpawnZ");out.writeInt(23);
            out.writeByte(8);out.writeUTF("LevelName");out.writeUTF("合成 World");out.writeByte(3);out.writeUTF("DataVersion");out.writeInt(4321);
            out.writeByte(10);out.writeUTF("Player");out.writeByte(9);out.writeUTF("Pos");out.writeByte(6);out.writeInt(3);out.writeDouble(-0.5);out.writeDouble(100);out.writeDouble(512.9);
            out.writeByte(8);out.writeUTF("Dimension");out.writeUTF("mod:custom");out.writeByte(0);out.writeByte(0);out.writeByte(0);
        }
        WorldAnchors a=WorldAnchors.read(directory.toFile());assertEquals(-12,a.spawn.xz.x);assertEquals(23,a.spawn.xz.y);assertEquals("minecraft:overworld",a.spawn.dimension);
        assertEquals(-1,a.player.xz.x);assertEquals(512,a.player.xz.y);assertEquals("mod:custom",a.player.dimension);assertEquals("合成 World",a.worldName);assertEquals(4321,a.dataVersion);
    }
    @Test void missingMalformedPlayerAndLegacyDimensionAreHandled() throws Exception {
        NBTCompound d=new NBTCompound("");assertNull(WorldAnchors.parse(d).spawn);assertNull(WorldAnchors.parse(d).player);
        assertThrows(IOException.class,()->WorldAnchors.read(directory.toFile()));
        NBTCompound p=new NBTCompound("Player");d.put("Player",p);p.put("Dimension",new NBTInt("Dimension",-1));
        NBTList pos=new NBTList("Pos",TagType.TAG_Double);pos.add(new NBTDouble("",1));pos.add(new NBTDouble("",2));pos.add(new NBTDouble("",3));p.put("Pos",pos);
        assertEquals("minecraft:the_nether",WorldAnchors.parse(d).player.dimension);
        NBTList malformed=new NBTList("Pos",TagType.TAG_Double);malformed.add(new NBTDouble("",Double.NaN));malformed.add(new NBTDouble("",2));malformed.add(new NBTDouble("",3));p.put("Pos",malformed);assertNull(WorldAnchors.parse(d).player);
        p.put("Pos",new NBTString("Pos","bad"));assertNull(WorldAnchors.parse(d).player);
        Files.write(directory.resolve("level.dat"),new byte[]{0});assertThrows(IOException.class,()->WorldAnchors.read(directory.toFile()));
    }
    @Test void blockPropertiesAreStableAndHeightsSurviveComposition() {
        Map<String,String> properties=new HashMap<>();properties.put("waterlogged","false");properties.put("axis","x");
        MapRenderer.BlockInfo block=new MapRenderer.BlockInfo("mod:column",80,properties,null);assertEquals("mod:column[axis=x,waterlogged=false]",block.formatState());
        assertEquals("minecraft:stone",new MapRenderer.BlockInfo("stone",0).formatState());
        // 逐列方块状态元数据已移除；高度与"已加载列"标记仍必须随合成一起搬运。
        RenderedMap source=new RenderedMap(2,2);source.setHeightAt(0,1,80);source.markLoaded(0,1);
        RenderedMap dest=new RenderedMap(4,4);dest.copyHeightsFrom(source,1,1);
        assertEquals(80,dest.getHeightAt(1,2));assertTrue(dest.isLoadedAt(1,2));assertFalse(dest.isLoadedAt(0,0));
        RenderedMap clipped=new RenderedMap(1,2);clipped.copyHeightsFrom(source,0,-1);assertEquals(80,clipped.getHeightAt(0,0));assertTrue(clipped.isLoadedAt(0,0));
        MapRenderer renderer=new MapRenderer(1,null);
        try {RenderedMap rendered=(RenderedMap)renderer.renderToPng(new MapRenderer.BlockInfo[][]{{block,MapRenderer.EMPTY}},1);assertEquals(80,rendered.getHeightAt(0,0));assertTrue(rendered.isLoadedAt(0,0));assertFalse(rendered.isLoadedAt(1,0));}
        finally {renderer.shutdown();}
    }
}
