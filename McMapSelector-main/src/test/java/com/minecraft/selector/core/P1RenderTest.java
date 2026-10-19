package com.minecraft.selector.core;
import com.minecraft.selector.region.Chunk;
import com.minecraft.selector.nbt.NBTReader.*;
import com.minecraft.selector.gui.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import javax.imageio.ImageIO;
import static org.junit.jupiter.api.Assertions.*;

class P1RenderTest {
    @TempDir Path directory;
    private HeightmapTest fixture() {HeightmapTest f=new HeightmapTest();f.directory=directory;return f;}
    private RenderedMap render(Path path,PreviewOptions options,Integer top,BlockColors.Palette palette) throws Exception {
        MapRenderer r=new MapRenderer(1,null,MapRenderer.SurfaceMode.TOP,top,palette,options);
        try {return (RenderedMap)r.renderToPng(r.getTopBlocks(path.toString(),32,1),1);}finally {r.shutdown();}
    }
    private NBTCompound chunkRoot() {NBTCompound n=new NBTCompound("");n.put("xPos",new NBTInt("xPos",0));n.put("zPos",new NBTInt("zPos",0));return n;}
    @Test void waterDisplayAndFitPolicyRemainStableAcrossComposition() throws Exception {
        HeightmapTest f=fixture();Path path=f.mca("r.0.0.mca",f.section(0,true,new String[]{"air","stone","water","glass"},i->i/256==0?1:i/256==6?2:i/256==8&&i%16==1?3:0));
        RenderedMap ordinary=render(path,PreviewOptions.DEFAULT,null,BlockColors.snapshot());
        RenderedMap transparent=render(path,new PreviewOptions(true,false,0),null,BlockColors.snapshot());
        assertEquals(6,ordinary.getHeightAt(0,0));assertEquals(0,transparent.getHeightAt(0,0));
        // 逐列 opaque/dry 高度元数据已移除：两种 Fit-Y 策略都使用可见顶高。
        assertEquals(0,transparent.getFitHeightAt(0,0,true));assertEquals(0,transparent.getFitHeightAt(0,0,false));
        SelectionMath.Bounds box=new SelectionMath.Bounds(0,0,15,15);
        assertEquals(8,SelectionYFit.scan(transparent,0,0,1,box,true).max);assertEquals(8,SelectionYFit.scan(transparent,0,0,1,box,false).max);
        RenderedMap composite=new RenderedMap(520,520);composite.copyHeightsFrom(transparent,4,4);
        assertEquals(0,composite.getFitHeightAt(4,4,true));
        assertNotEquals(ordinary.getRenderKey(),transparent.getRenderKey());assertEquals(transparent.getRenderKey(),composite.getRenderKey());
        assertTrue(PreviewOptions.water("custom:still_water"));assertTrue(PreviewOptions.fitTransparent("custom:stained_glass_panel"));assertFalse(PreviewOptions.water("custom:stone"));
    }
    @Test void waterOnlyColumnsAreLoadedButHaveNoSurfaceHeight() throws Exception {
        HeightmapTest f=fixture();Path path=f.mca("water-only.mca",f.section(0,true,new String[]{"air","custom:water"},i->i/256==5?1:0));
        RenderedMap map=render(path,new PreviewOptions(true,false,0),null,BlockColors.snapshot());
        assertEquals(RenderedMap.NO_HEIGHT,map.getHeightAt(0,0));
        assertEquals(RenderedMap.NO_HEIGHT,map.getFitHeightAt(0,0,true));
        assertEquals(RenderedMap.NO_HEIGHT,map.getFitHeightAt(0,0,false));
        assertTrue(map.isLoadedAt(0,0));
    }
    @Test void managerCachesAreIsolatedByPreviewPolicy() throws Exception {
        HeightmapTest f=fixture();Path file=f.mca("r.0.0.mca",f.section(0,true,new String[]{"air","stone","water"},i->i/256==0?1:i/256==5?2:0));
        DynamicRegionManager opaque=new DynamicRegionManager(directory.toString(),directory.toFile(),MapRenderer.SurfaceMode.TOP,null,PreviewOptions.DEFAULT);
        DynamicRegionManager dry=new DynamicRegionManager(directory.toString(),directory.toFile(),MapRenderer.SurfaceMode.TOP,null,new PreviewOptions(true,false,0));
        try {
            opaque.updateViewport(new Rectangle(0,0,16,16));dry.updateViewport(new Rectangle(0,0,16,16));opaque.awaitIdle(5,java.util.concurrent.TimeUnit.SECONDS);dry.awaitIdle(5,java.util.concurrent.TimeUnit.SECONDS);
            RenderedMap a=(RenderedMap)opaque.getLoadedRegion(new Point(0,0)).getImage(),b=(RenderedMap)dry.getLoadedRegion(new Point(0,0)).getImage();
            assertEquals(5,a.getHeightAt(0,0));assertEquals(0,b.getHeightAt(0,0));assertNotEquals(a.getRenderKey(),b.getRenderKey());
        } finally {opaque.shutdown();dry.shutdown();}
    }
    @Test void caveFindsRoofPocketAndDimsExposedSkyIncludingCeilingInsidePocket() throws Exception {
        HeightmapTest f=fixture();Path path=f.mca("cave.mca",f.section(0,true,new String[]{"air","stone"},i->i/256==0||i/256==12&&i%16==0?1:0));
        RenderedMap surface=render(path,PreviewOptions.DEFAULT,15,BlockColors.snapshot());
        RenderedMap cave=render(path,new PreviewOptions(false,true,0),15,BlockColors.snapshot());
        assertEquals(12,surface.getHeightAt(0,0));assertEquals(0,cave.getHeightAt(0,0));
        assertTrue((cave.getRGB(0,0)&255)>(cave.getRGB(1,0)&255));
        RenderedMap inside=render(path,new PreviewOptions(false,true,0),8,BlockColors.snapshot());
        assertEquals(cave.getRGB(0,0),inside.getRGB(0,0));
    }
    @Test void missingChunksAndLoadedAirHaveDifferentCoverageAndShading() throws Exception {
        HeightmapTest f=fixture();Path path=f.mca("air.mca",f.section(0,true,new String[]{"air"},i->0));
        RenderedMap map=render(path,PreviewOptions.DEFAULT,null,BlockColors.snapshot());
        assertTrue(map.isLoadedAt(0,0));assertFalse(map.isLoadedAt(16,0));
        SelectionYFit.Result coverage=SelectionYFit.scan(map,0,0,1,new SelectionMath.Bounds(0,0,31,15),true);
        assertEquals(256,coverage.empty);assertEquals(256,coverage.missing);assertEquals(0,coverage.count);
        BufferedImage layer=ViewLayers.overlay(map,false,false,true);assertNotEquals(layer.getRGB(0,0),layer.getRGB(16,0));assertEquals(0,map.getRGB(0,0));
        RenderedMap copy=new RenderedMap(520,520);copy.copyHeightsFrom(map,4,4);assertTrue(copy.isLoadedAt(4,4));assertFalse(copy.isLoadedAt(20,4));
    }    @Test void modernPackedBiomesUseQuartCoordinatesAndPreserveUnknownIds() {
        HeightmapTest f=fixture();NBTCompound section=f.section(-1,true,new String[]{"stone"},i->0);
        NBTCompound biome=new NBTCompound("biomes");NBTList palette=new NBTList("palette",TagType.TAG_String);
        for(int i=0;i<5;i++) palette.add(new NBTString("","mod:biome_"+i));biome.put("palette",palette);
        long[] packed=new long[4];for(int i=0;i<64;i++) packed[i/21]|=(long)(i%5)<<((i%21)*3);biome.put("data",new NBTLongArray("data",packed));section.put("biomes",biome);
        NBTCompound nbt=chunkRoot();NBTList sections=new NBTList("sections",TagType.TAG_Compound);sections.add(section);nbt.put("sections",sections);
        Chunk chunk=new Chunk(nbt);
        for(int y=0;y<16;y++) for(int z=0;z<16;z++) for(int x=0;x<16;x++) assertEquals("mod:biome_"+(((y>>2)*16+(z>>2)*4+(x>>2))%5),chunk.getBiome(x,y-16,z));
        biome.put("data",new NBTLongArray("data",new long[0]));assertNull(new Chunk(nbt).getBiome(0,-16,0));
    }
    @Test void legacyBiomeArraysExposeNumericIdentityWithoutInventedRegistry() {
        NBTCompound root=chunkRoot();byte[] ids=new byte[256];ids[255]=(byte)200;root.put("Biomes",new NBTByteArray("Biomes",ids));
        assertEquals("legacy:200",new Chunk(root).getBiome(15,60,15));
        int[] quart=new int[1024];quart[4*16+15]=4321;root.put("Biomes",new NBTIntArray("Biomes",quart));assertEquals("legacy:4321",new Chunk(root).getBiome(15,16,15));
    }
    @Test void resourceBiomeDefinitionsColormapsAndPackOverridesAreFingerprintScoped() throws Exception {
        Path jar=directory.resolve("biomes.jar"),pack=directory.resolve("override.zip");
        writeResources(jar,0x123456);writeResources(pack,0x654321);
        ResourceOptions options=new ResourceOptions();options.auto=false;options.jars.add(jar.toString());
        BlockColors.Palette base=BlockColors.loadPalette(directory.toString(),options);
        Color color=base.biomeColor(Color.WHITE,"custom:forest","custom:water",1);assertEquals(new Color(0x123456),color);
        assertEquals(Color.WHITE,base.biomeColor(Color.WHITE,"missing:forest","custom:water",1));
        options.packs.add(pack.toString());BlockColors.Palette override=BlockColors.loadPalette(directory.toString(),options);
        assertEquals(new Color(0x654321),override.biomeColor(Color.WHITE,"custom:forest","custom:water",1));assertNotEquals(base.fingerprint(),override.fingerprint());
        assertEquals(new Color(0x123456),base.biomeColor(Color.WHITE,"custom:forest","custom:water",1));
        Color tint=override.biomeColor(Color.WHITE,"custom:forest","grass_block",1);assertEquals(new Color(30,80,140),tint);
        BufferedImage map=new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB);map.setRGB(1,1,0xff765432);assertEquals(new Color(0x765432),BiomeColors.sample(map,0,1));assertEquals(new Color(0),BiomeColors.sample(map,1,1));
    }
    private void writeResources(Path file,int water) throws Exception {
        try(ZipOutputStream zip=new ZipOutputStream(Files.newOutputStream(file))) {
            zip.putNextEntry(new ZipEntry("data/custom/worldgen/biome/forest.json"));zip.write(("{\"temperature\":0.5,\"downfall\":0.5,\"effects\":{\"water_color\":"+water+"}}").getBytes("UTF-8"));zip.closeEntry();
            zip.putNextEntry(new ZipEntry("assets/minecraft/textures/colormap/grass.png"));BufferedImage map=new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB);for(int y=0;y<2;y++) for(int x=0;x<2;x++) map.setRGB(x,y,new Color(30,80,140).getRGB());ImageIO.write(map,"PNG",zip);zip.closeEntry();
        }
    }
    @Test void biomeTintPathRendersWithoutPerColumnBiomeMetadata() throws Exception {
        HeightmapTest f=fixture();NBTCompound section=f.section(0,true,new String[]{"stone"},i->0);NBTCompound b=new NBTCompound("biomes");NBTList palette=new NBTList("palette",TagType.TAG_String);palette.add(new NBTString("","custom:plateau"));b.put("palette",palette);section.put("biomes",b);
        NBTCompound root=chunkRoot();NBTList sections=new NBTList("sections",TagType.TAG_Compound);sections.add(section);root.put("sections",sections);assertEquals("custom:plateau",new Chunk(root).getBiome(0,15,0));
        // 群系着色（像素）保留；逐列生物群系元数据（getBiomeAt）已随 +4 MB 数组移除。
        // 全石柱的 section Y=0 顶层是 Y=15；带群系渲染仍必须得到同样的高度。
        RenderedMap actual=render(f.mca("biome.mca",section),new PreviewOptions(false,false,2),null,BlockColors.snapshot());
        assertEquals(15,actual.getHeightAt(0,0));
    }
    @Test void nightAndContourOverlaysDoNotChangeSourceOrFitHeight() {
        RenderedMap map=new RenderedMap(2,1);map.setHeightAt(0,0,-1);map.setHeightAt(1,0,0);map.setRGB(0,0,Color.WHITE.getRGB());
        assertTrue(ViewLayers.contour(-1,0,16));assertFalse(ViewLayers.contour(RenderedMap.NO_HEIGHT,0,16));assertEquals(-1,ViewLayers.band(-1,16));
        BufferedImage night=ViewLayers.overlay(map,true,false,false);assertTrue((night.getRGB(0,0)>>>24)>0);assertSame(night,ViewLayers.overlay(map,true,false,false));
        assertEquals(0,ViewLayers.overlay(map,false,false,false).getRGB(0,0));assertNotEquals(0,ViewLayers.overlay(map,false,true,false).getRGB(1,0));assertEquals(Color.WHITE.getRGB(),map.getRGB(0,0));assertEquals(-1,map.getHeightAt(0,0));
    }
}
