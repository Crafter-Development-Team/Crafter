package com.minecraft.selector.core;

import com.minecraft.selector.nbt.NBTReader.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.zip.*;
import javax.imageio.ImageIO;
import static org.junit.jupiter.api.Assertions.*;

class BlockColorsTest {
    @TempDir Path directory;
    @AfterEach void reset() {BlockColors.setResourceExtractor(null);}
    private byte[] png(int... rgba) throws Exception {
        BufferedImage image=new BufferedImage(rgba.length,1,BufferedImage.TYPE_INT_ARGB);
        for(int i=0;i<rgba.length;i++) image.setRGB(i,0,rgba[i]);
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();ImageIO.write(image,"PNG",bytes);return bytes.toByteArray();
    }
    private Path archive(String name,Map<String,byte[]> entries) throws Exception {
        Path path=directory.resolve(name);
        try(ZipOutputStream zip=new ZipOutputStream(Files.newOutputStream(path))) {
            for(Map.Entry<String,byte[]> e:entries.entrySet()) {zip.putNextEntry(new ZipEntry(e.getKey()));zip.write(e.getValue());zip.closeEntry();}
        }
        return path;
    }
    private void json(Map<String,byte[]> entries,String path,String text) throws Exception {entries.put(path,text.getBytes("UTF-8"));}
    private String shape(int x,int y,int z) {
        return "{\"textures\":{\"all\":\"#material\"},\"elements\":[{\"from\":[0,0,0],\"to\":["+x+","+y+","+z+"],\"faces\":{\"up\":{\"texture\":\"#all\"}}}]}";
    }
    private MinecraftResourceExtractor synthetic() throws Exception {
        Map<String,byte[]> base=new LinkedHashMap<>(),mod=new LinkedHashMap<>(),pack=new LinkedHashMap<>();
        json(base,"assets/minecraft/models/block/template.json",shape(16,16,16));
        json(base,"assets/minecraft/models/block/thin.json",shape(16,1,16));
        json(base,"assets/minecraft/models/block/side.json",shape(8,16,16));
        mod.put("assets/yuushya/textures/block_/concrete/material.png",png(0xffff0000));
        mod.put("assets/yuushya/textures/item/translucent.png",png(0xffff0000,0x800000ff,0x00ffffff));
        for(String block:new String[]{"white_concrete_top","half_slab_white_concrete","half_side_slab_white_concrete"}) {
            String template=block.startsWith("half_side")?"side":block.startsWith("half")?"thin":"template";
            json(mod,"assets/yuushya/models/template/"+block+".json","{\"parent\":\"minecraft:block/"+template+"\",\"textures\":{\"material\":\"yuushya:block_/concrete/material\"}}");
            json(mod,"assets/yuushya/blockstates/"+block+".json","{\"variants\":{\"\":{\"model\":\"yuushya:template/"+block+"\"}}}");
        }
        json(mod,"assets/yuushya/blockstates/switch.json","{\"variants\":{\"facing=north\":{\"model\":\"yuushya:template/white_concrete_top\"},\"facing=south\":{\"model\":\"yuushya:template/half_side_slab_white_concrete\"}}}");
        // Texture-only pack overrides a model in a different archive and directory.
        pack.put("assets/yuushya/textures/block_/concrete/material.png",png(0xff00ff00));
        MinecraftResourceExtractor e=new MinecraftResourceExtractor();
        e.addArchive(archive("vanilla.jar",base).toString());e.addArchive(archive("mod.jar",mod).toString());e.addArchive(archive("pack.zip",pack).toString());return e;
    }
    @Test void mergedArchivesResolveInheritedTemplatesAndTextureOnlyOverride() throws Exception {
        MinecraftResourceExtractor e=synthetic();BlockColors.setResourceExtractor(e);
        assertEquals(new Color(0,255,0),BlockColors.getBlockColor("yuushya:white_concrete_top"));
        assertEquals("model",BlockColors.snapshot().appearance("yuushya:white_concrete_top",Collections.emptyMap()).source);
        Color c=e.getExtractedColor("yuushya:translucent");
        assertNotNull(c);assertEquals(169,c.getRed());assertEquals(85,c.getBlue());assertEquals(127,c.getAlpha());
        Map<String,byte[]> replacement=new HashMap<>();replacement.put("assets/yuushya/textures/block_/concrete/material.png",png(0xff0000ff));
        e.addArchive(archive("higher.zip",replacement).toString());
        BlockColors.setResourceExtractor(e);assertEquals(Color.BLUE,BlockColors.getBlockColor("yuushya:white_concrete_top"));
    }
    @Test void slabThinRoofAndSideSlabUseHorizontalAreaNotVolume() throws Exception {
        BlockColors.setResourceExtractor(synthetic());BlockColors.Palette palette=BlockColors.snapshot();
        Color slab=MapRenderer.surfaceColor("yuushya:half_slab_white_concrete",Collections.emptyMap(),palette);
        assertEquals(255,slab.getAlpha());assertEquals(Color.GREEN,MapRenderer.over(slab,Color.RED));
        Color side=MapRenderer.surfaceColor("yuushya:half_side_slab_white_concrete",Collections.emptyMap(),palette);
        assertEquals(128,side.getAlpha());Color blended=MapRenderer.over(side,Color.RED);
        assertEquals(127,blended.getRed());assertEquals(128,blended.getGreen());
        assertEquals(255,MapRenderer.surfaceColor("yuushya:switch",Collections.singletonMap("facing","north"),palette).getAlpha());
        assertEquals(128,MapRenderer.surfaceColor("yuushya:switch",Collections.singletonMap("facing","south"),palette).getAlpha());
    }
    @Test void snowLayerWhiteCoverAndEightLayersOccludeSubstrate() {
        BlockColors.Palette palette=BlockColors.snapshot();
        Color thin=MapRenderer.surfaceColor("snow",Collections.singletonMap("layers","1"),palette);
        Color full=MapRenderer.surfaceColor("snow",Collections.singletonMap("layers","8"),palette);
        assertEquals(179,thin.getAlpha());assertEquals(255,full.getAlpha());
        Color mixed=MapRenderer.over(thin,new Color(60,80,40));
        assertTrue(mixed.getRed()>190);assertTrue(mixed.getGreen()>190);assertEquals(Color.WHITE,MapRenderer.over(full,Color.RED));
    }
    @Test void openTrapdoorPlateAndGlassTransmitBelowButCarpetAndClosedTrapdoorCover() {
        BlockColors.Palette p=BlockColors.snapshot();
        assertEquals(48,MapRenderer.surfaceColor("oak_trapdoor",Collections.singletonMap("open","true"),p).getAlpha());
        assertEquals(255,MapRenderer.surfaceColor("oak_trapdoor",Collections.singletonMap("open","false"),p).getAlpha());
        assertEquals(195,MapRenderer.surfaceColor("stone_pressure_plate",Collections.emptyMap(),p).getAlpha());
        assertEquals(255,MapRenderer.surfaceColor("white_carpet",Collections.emptyMap(),p).getAlpha());
        assertTrue(MapRenderer.surfaceColor("glass",Collections.emptyMap(),p).getAlpha()<128);
    }
    private NBTCompound state(String name) {NBTCompound c=new NBTCompound("BlockState");c.put("Name",new NBTString("Name",name));return c;}
    @Test void showblockReadsVisibleAssociatedMaterialsAndTileFrameReferences() throws Exception {
        BlockColors.setResourceExtractor(synthetic());BlockColors.Palette p=BlockColors.snapshot();
        NBTCompound entity=new NBTCompound("");NBTList blocks=new NBTList("Blocks",TagType.TAG_Compound);
        NBTCompound item=new NBTCompound("");item.put("BlockState",state("yuushya:white_concrete_top"));item.put("isShown",new NBTByte("isShown",(byte)1));blocks.add(item);
        NBTCompound hidden=new NBTCompound("");hidden.put("BlockState",state("minecraft:stone"));hidden.put("isShown",new NBTByte("isShown",(byte)0));blocks.add(hidden);entity.put("Blocks",blocks);
        assertEquals(Color.GREEN,BlockEntityColors.resolve("yuushya:showblock",entity,p));
        NBTCompound tile=new NBTCompound("");tile.put("b",new NBTString("b","minecraft:red_concrete"));
        assertEquals(p.getBlockColor("red_concrete"),BlockEntityColors.resolve("littletiles:tiles",tile,p));
        NBTCompound frame=new NBTCompound("");frame.put("camo",state("minecraft:stone"));
        assertEquals(p.getBlockColor("stone"),BlockEntityColors.resolve("framedblocks:framed_cube",frame,p));
        assertEquals(new Color(128,128,128),p.getBlockColor("littletiles:opaque_dynamic_container"));
    }
    @Test void unknownNamespaceDoesNotBorrowVanillaAndReloadInvalidatesStateCache() throws Exception {
        BlockColors.setResourceExtractor(synthetic());BlockColors.Palette old=BlockColors.snapshot();
        assertEquals(Color.GREEN,old.getBlockColor("yuushya:white_concrete_top"));
        assertEquals(new Color(128,128,128),old.getBlockColor("absent:white_concrete_top"));
        assertEquals("unknown",old.appearance("absent:white_concrete_top",Collections.emptyMap()).source);
        ResourceOptions options=new ResourceOptions();options.auto=false;
        BlockColors.initializeColorsFromSavePath(directory.toString(),options);
        assertEquals("unknown",BlockColors.snapshot().appearance("yuushya:white_concrete_top",Collections.emptyMap()).source);
        assertEquals(Color.GREEN,old.getBlockColor("yuushya:white_concrete_top"));
        assertEquals(0,old.getBlockColor("minecraft:air").getAlpha());
    }
    @Test void fullyTransparentTextureHasNoBlackSurface() throws Exception {
        Map<String,byte[]> entries=new HashMap<>();entries.put("assets/example/textures/block/invisible.png",png(0x00ff0000));
        MinecraftResourceExtractor e=new MinecraftResourceExtractor();e.addArchive(archive("transparent.jar",entries).toString());
        assertEquals(0,e.getExtractedColor("example:invisible").getAlpha());
    }
    @Test void realYuushyaIdsResolveAcrossActualJarLayouts() throws Exception {
        Path vanilla=Paths.get("/workspace/crafter-pipeline/mc/versions/1.20.1/1.20.1.jar");
        Path mod=Paths.get("/workspace/crafter-pipeline/zzz-map/mods/yuushya-1.20.1-fabric-2.2.0.jar");
        Assumptions.assumeTrue(Files.isRegularFile(vanilla)&&Files.isRegularFile(mod),"Local real-world resource fixture is unavailable");
        MinecraftResourceExtractor e=new MinecraftResourceExtractor();e.addArchive(vanilla.toString());e.addArchive(mod.toString());
        BlockColors.setResourceExtractor(e);
        for(String id:new String[]{"yuushya:white_concrete_top","yuushya:asphalt","yuushya:half_slab_white_concrete","yuushya:road_marking_1"}) {
            BlockColors.Appearance a=BlockColors.snapshot().appearance(id,Collections.singletonMap("facing","north"));
            assertTrue(a.source.startsWith("model"),id+": "+a.source);assertTrue(a.color.getAlpha()>0,id);
        }
        assertEquals(255,BlockColors.getBlockColor("yuushya:half_slab_white_concrete").getAlpha());
        ResourceOptions o=new ResourceOptions();List<String> discovered=o.archivesFor("/workspace/crafter-pipeline/mc-worlds/绝区零六分街复刻地图");
        assertTrue(discovered.contains(mod.toString()));assertTrue(discovered.stream().anyMatch(path->path.endsWith("yuushya_modpack_inside_wi.zip")));
    }
    @Test void realColumnScanCompositesSnowAndSkipsTransparentWithoutLosingHeightCache() throws Exception {
        BlockColors.setResourceExtractor(synthetic());
        Map<String,byte[]> entries=new HashMap<>();entries.put("assets/test/textures/block/empty.png",png(0));
        MinecraftResourceExtractor e=synthetic();e.addArchive(archive("empty.jar",entries).toString());BlockColors.setResourceExtractor(e);
        HeightmapTest h=new HeightmapTest();h.directory=directory;
        NBTCompound section=h.section(0,false,new String[]{"minecraft:air","minecraft:stone","minecraft:snow","yuushya:half_slab_white_concrete","test:empty"},i -> {
            int y=i/256,x=i%16;if(y==0) return 1;if(y==1) return x<4?2:x<8?3:4;return 0;
        });
        NBTCompound snow=(NBTCompound)section.getCompound("block_states").getList("palette").get(2);
        NBTCompound props=new NBTCompound("Properties");props.put("layers",new NBTString("layers","1"));snow.put("Properties",props);
        Path path=h.mca("region/r.0.0.mca",section);
        MapRenderer renderer=new MapRenderer(1,null);
        try {
            MapRenderer.BlockInfo[][] blocks=renderer.getTopBlocks(path.toString(),1,1);
            RenderedMap map=(RenderedMap)renderer.renderToPng(blocks,1);
            assertEquals("snow",blocks[0][0].getBlockId());assertEquals("1",blocks[0][0].getProperties().get("layers"));
            assertEquals(1,map.getHeightAt(0,0));assertTrue(new Color(map.getRGB(0,0),true).getRed()>190);
            assertEquals(Color.GREEN.getRGB(),map.getRGB(4,0));assertEquals(1,map.getHeightAt(4,0));
            assertEquals("stone",blocks[0][8].getBlockId());assertEquals(0,map.getHeightAt(8,0));
        } finally {renderer.shutdown();}
    }
    @Test void explicitSliceKeepsNetherInteriorAndHonoursBandBottom() throws Exception {
        HeightmapTest h=new HeightmapTest();h.directory=directory;
        Path path=h.mca("DIM-1/region/r.0.0.mca",h.section(40,true,new String[]{"minecraft:stone"},i->0),
            h.section(7,false,new String[]{"minecraft:bedrock"},i->0),h.section(-8,false,new String[]{"minecraft:dirt"},i->0));
        // Y 带只取 640..650: 天花板在带外, 按普通表面返回带顶那块石头
        MapRenderer band=new MapRenderer(1,null,MapRenderer.SurfaceMode.AUTO,650,640);
        // 只给上界(无带底): 下界去顶床仍然生效, 返回天花板之下的方块
        MapRenderer openBottom=new MapRenderer(1,null,MapRenderer.SurfaceMode.AUTO,650);
        // 上界压到天花板以下: 照常按普通表面取
        MapRenderer below=new MapRenderer(1,null,MapRenderer.SurfaceMode.AUTO,-120);
        try {
            assertEquals(650,band.getTopBlocks(path.toString(),1,1)[0][0].getHeight());
            assertEquals(-113,openBottom.getTopBlocks(path.toString(),1,1)[0][0].getHeight());
            assertEquals(-120,below.getTopBlocks(path.toString(),1,1)[0][0].getHeight());
        } finally {band.shutdown();openBottom.shutdown();below.shutdown();}
    }
    @Test void verticalRangeSliderClampsHandlesAndFiresOncePerSettledChange() throws Exception {
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            List<String> changes=new ArrayList<>();
            com.minecraft.selector.gui.StyledComponents.VerticalRangeSlider slider=
                new com.minecraft.selector.gui.StyledComponents.VerticalRangeSlider(-128,384,-64,319);
            slider.addChangeListener(e -> changes.add(slider.getLowerValue()+":"+slider.getUpperValue()));
            assertEquals("-64:319",slider.getLowerValue()+":"+slider.getUpperValue());
            slider.setValues(-128,400);   // 超出模型范围: 上限被钳到 384
            assertEquals("-128:384",slider.getLowerValue()+":"+slider.getUpperValue());
            slider.setLowerValue(900);    // 不允许穿过上界
            assertEquals("384:384",slider.getLowerValue()+":"+slider.getUpperValue());
            slider.setRange(-2048,2048);slider.setValues(-1500,1500);
            assertEquals("-1500:1500",slider.getLowerValue()+":"+slider.getUpperValue());
            assertEquals(3,changes.size());
        });
    }
    @Test void commandLineResourceOptionsStripFlagsAndRespectExplicitPriority() throws Exception {
        MinecraftResourceExtractor ignored=synthetic();
        String[] args={"world","--jar-path",directory.resolve("vanilla.jar").toString(),"out.json","--mods-dir",directory.toString(),"out.png","--resource-pack",directory.resolve("pack.zip").toString(),"--no-auto-resources","--render-y","-128"};
        ResourceOptions o=ResourceOptions.parse(args);
        assertArrayEquals(new String[]{"world","out.json","out.png"},ResourceOptions.withoutResourceFlags(args));
        assertEquals(Integer.valueOf(-128),o.renderY);assertFalse(o.auto);
        List<String> paths=o.archivesFor("world");assertEquals(directory.resolve("pack.zip").toString(),paths.get(paths.size()-1));
        assertThrows(IllegalArgumentException.class,()->ResourceOptions.parse(new String[]{"--mod-jar","missing.jar"}));
    }

    @Test void discoveryUsesWorldNamespacesAndArchiveMetadataWithoutMapModOrVersionNames() throws Exception {
        HeightmapTest h=new HeightmapTest();h.directory=directory;
        Path world=h.mca("arbitrary/saves/example/region/r.0.0.mca",h.section(0,false,new String[]{"wizard:brick"},i->0));
        Map<String,byte[]> resources=new HashMap<>();resources.put("assets/wizard/textures/special/brick.png",png(0xffff0000));
        Path mod=archive("arbitrary/unrelated_instance/mods/anything.jar",withParents("arbitrary/unrelated_instance/mods",resources));
        Map<String,byte[]> pack=new HashMap<>();pack.put("assets/wizard/textures/special/brick.png",png(0xff00ff00));
        Path overlay=archive("arbitrary/unrelated_instance/resourcepacks/overlay.zip",withParents("arbitrary/unrelated_instance/resourcepacks",pack));
        Map<String,byte[]> old=new HashMap<>(),recent=new HashMap<>();
        json(old,"version.json","{\"world_version\":10}");json(recent,"version.json","{\"world_version\":100}");
        archive("arbitrary/client/versions/old/old.jar",withParents("arbitrary/client/versions/old",old));
        Path client=archive("arbitrary/client/versions/new/new.jar",withParents("arbitrary/client/versions/new",recent));
        List<String> found=new ResourceOptions().archivesFor(world.toString());
        assertEquals(Arrays.asList(client.toString(),mod.toString(),overlay.toString()),found);
    }
    private Map<String,byte[]> withParents(String parent,Map<String,byte[]> entries) throws Exception {
        Files.createDirectories(directory.resolve(parent));return entries;
    }

}
