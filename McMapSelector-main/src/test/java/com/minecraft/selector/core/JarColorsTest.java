package com.minecraft.selector.core;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import static org.junit.jupiter.api.Assertions.*;

class JarColorsTest {
    @TempDir Path temp;
    private static final String TEX = "assets/minecraft/textures/block/";

    @AfterEach void reset() { BlockColors.setResourceExtractor(null); }

    private Path jar(String name, String[] textures, Color[] colors) throws Exception {
        Path file = temp.resolve(name);
        Files.createDirectories(file.getParent());
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(file))) {
            for (int i = 0; i < textures.length; i++) {
                out.putNextEntry(new JarEntry(textures[i]));
                BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
                for (int y = 0; y < 2; y++) for (int x = 0; x < 2; x++) image.setRGB(x, y, colors[i].getRGB());
                assertTrue(ImageIO.write(image, "png", out));
                out.closeEntry();
            }
        }
        return file;
    }
    private MinecraftResourceExtractor palette(Color color) throws Exception {
        MinecraftResourceExtractor ex = new MinecraftResourceExtractor();
        Path jar = jar("source" + color.getRGB() + ".jar", new String[]{TEX + "stone.png", TEX + "dirt.png"}, new Color[]{color, color});
        assertTrue(ex.extractColorsFromMinecraftJar(jar.toString()));
        return ex;
    }
    @Test void textureAverageAndNamespaceComeFromJar() throws Exception {
        BlockColors.setResourceExtractor(palette(new Color(12,34,56)));
        assertEquals(new Color(12,34,56), BlockColors.getBlockColor("stone"));
        assertEquals(new Color(12,34,56), BlockColors.getBlockColor("minecraft:stone"));
    }
    @Test void emptyLoaderJarIsNotAValidColorSource() throws Exception {
        Path empty = jar("loader.jar", new String[0], new Color[0]);
        assertFalse(new MinecraftResourceExtractor().extractColorsFromMinecraftJar(empty.toString()));
    }
    @Test void topFaceWinsRegardlessOfArchiveOrder() throws Exception {
        for (boolean reverse : new boolean[]{false,true}) {
            String[] names = reverse ? new String[]{TEX+"sample_top.png", TEX+"sample_side.png"} : new String[]{TEX+"sample_side.png", TEX+"sample_top.png"};
            Color[] values = reverse ? new Color[]{Color.BLUE,Color.RED} : new Color[]{Color.RED,Color.BLUE};
            Path file = jar("faces"+reverse+".jar", names, values);
            MinecraftResourceExtractor ex = new MinecraftResourceExtractor();
            assertTrue(ex.extractColorsFromMinecraftJar(file.toString()));
            assertEquals(Color.BLUE, ex.getExtractedColor("sample"));
        }
    }
    @Test void variantsUseJarBaseColors() throws Exception {
        Path file = jar("variants.jar", new String[]{TEX+"oak_planks.png", TEX+"bricks.png"}, new Color[]{Color.BLUE,Color.RED});
        MinecraftResourceExtractor ex = new MinecraftResourceExtractor();
        assertTrue(ex.extractColorsFromMinecraftJar(file.toString()));
        BlockColors.setResourceExtractor(ex);
        assertEquals(Color.BLUE, BlockColors.getBlockColor("minecraft:oak_stairs"));
        assertEquals(Color.RED, BlockColors.getBlockColor("minecraft:brick_slab"));
    }
    @Test void airRemainsTransparentInJarMode() throws Exception {
        BlockColors.setResourceExtractor(palette(Color.BLUE));
        for (String id : new String[]{"air","minecraft:air","cave_air","void_air","none"})
            assertEquals(0, BlockColors.getBlockColor(id).getAlpha(), id);
    }
    @Test void publishedPaletteDoesNotShareMutableExtractor() throws Exception {
        MinecraftResourceExtractor ex = palette(Color.BLUE);
        BlockColors.setResourceExtractor(ex);
        Path newJar = jar("replacement.jar", new String[]{TEX+"stone.png"}, new Color[]{Color.RED});
        assertTrue(ex.extractColorsFromMinecraftJar(newJar.toString()));
        assertEquals(Color.BLUE, BlockColors.getBlockColor("stone"));
    }
    @Test void extractorDoesNotRetainColorsFromPreviousJar() throws Exception {
        MinecraftResourceExtractor ex = palette(Color.BLUE);
        Path newJar = jar("new.jar", new String[]{TEX+"stone.png"}, new Color[]{Color.RED});
        assertTrue(ex.extractColorsFromMinecraftJar(newJar.toString()));
        assertNull(ex.getExtractedColor("dirt"));
    }
    @Test void oldReaderCannotPoisonNewCache() throws Exception {
        BlockColors.setResourceExtractor(palette(Color.BLUE));
        BlockColors.Palette old = BlockColors.snapshot();
        BlockColors.setResourceExtractor(palette(Color.RED));
        assertEquals(Color.BLUE, old.getBlockColor("stone"));
        assertEquals(Color.RED, BlockColors.getBlockColor("stone"));
    }
    @Test void concurrentSnapshotsStayConsistent() throws Exception {
        MinecraftResourceExtractor red = palette(Color.RED), blue = palette(Color.BLUE);
        BlockColors.setResourceExtractor(red);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> writer = pool.submit(() -> {
                try { start.await(); } catch (InterruptedException e) { throw new RuntimeException(e); }
                for (int i=0;i<2000;i++) BlockColors.setResourceExtractor(i%2==0?red:blue);
            });
            Future<?> reader = pool.submit(() -> {
                try { start.await(); } catch (InterruptedException e) { throw new RuntimeException(e); }
                for (int i=0;i<2000;i++) {
                    BlockColors.Palette p=BlockColors.snapshot();
                    assertEquals(p.getBlockColor("stone"),p.getBlockColor("dirt"));
                }
            });
            start.countDown(); writer.get(10,TimeUnit.SECONDS); reader.get(10,TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
    }
    @Test void singleImageRetainsOnePaletteDuringSourceSwitch() throws Exception {
        BlockColors.setResourceExtractor(palette(Color.BLUE));
        MinecraftResourceExtractor replacement = palette(Color.RED);
        MapRenderer.BlockInfo[][] blocks = {{new MapRenderer.BlockInfo("stone",64) {
            @Override public String getBlockId() {
                BlockColors.setResourceExtractor(replacement); return super.getBlockId();
            }
        }, new MapRenderer.BlockInfo("dirt",64)}};
        MapRenderer renderer = new MapRenderer(1,null);
        try {
            BufferedImage image = renderer.renderToPng(blocks,1);
            assertEquals(Color.BLUE.getRGB(),image.getRGB(0,0));
            assertEquals(Color.BLUE.getRGB(),image.getRGB(1,0));
        } finally { renderer.shutdown(); }
    }
    @Test void modOnlyJarColorsArePublished() throws Exception {
        Path world=Files.createDirectories(temp.resolve("instance/saves/world"));
        jar("instance/mods/example.jar", new String[]{"assets/example/textures/block/test.png"}, new Color[]{Color.BLUE});
        assertTrue(BlockColors.initializeColorsFromSavePath(world.toString()));
        assertEquals(Color.BLUE,BlockColors.getBlockColor("example:test"));
    }
    @Test void blockstateModelInheritanceAndTextureAliasesResolveActualBlockId() throws Exception {
        Path file=jar("models.jar",new String[]{TEX+"custom_surface.png"},new Color[]{new Color(12,34,56)});
        Map<String,String> json=new LinkedHashMap<>();
        json.put("assets/minecraft/blockstates/gray_carpet.json","{\"variants\":{\"\":{\"model\":\"minecraft:block/carpet_child\"}}}");
        json.put("assets/minecraft/models/block/carpet_child.json","{\"parent\":\"minecraft:block/carpet_parent\",\"textures\":{\"wool\":\"minecraft:block/custom_surface\"}}");
        json.put("assets/minecraft/models/block/carpet_parent.json","{\"textures\":{\"surface\":\"#wool\"},\"elements\":[{\"faces\":{\"up\":{\"texture\":\"#surface\"}}}]}");
        // Copy the texture archive and append the model fixtures.
        Path combined=temp.resolve("models-combined.jar");
        try(JarFile source=new JarFile(file.toFile()); JarOutputStream out=new JarOutputStream(Files.newOutputStream(combined))) {
            Enumeration<JarEntry> entries=source.entries();
            while(entries.hasMoreElements()) {
                JarEntry entry=entries.nextElement(); out.putNextEntry(new JarEntry(entry.getName()));
                try(InputStream in=source.getInputStream(entry)) { byte[] buffer=new byte[4096]; int n;
                    while((n=in.read(buffer))!=-1) out.write(buffer,0,n); }
                out.closeEntry();
            }
            for(Map.Entry<String,String> entry:json.entrySet()) {
                out.putNextEntry(new JarEntry(entry.getKey()));
                out.write(entry.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8)); out.closeEntry();
            }
        }
        MinecraftResourceExtractor ex=new MinecraftResourceExtractor();
        assertTrue(ex.extractColorsFromMinecraftJar(combined.toString()));
        BlockColors.setResourceExtractor(ex);
        assertEquals(new Color(12,34,56),BlockColors.getBlockColor("minecraft:gray_carpet"));
    }
    @Test void renamedVanillaBlocksUseNewJarNames() throws Exception {
        Path file=jar("renamed.jar",new String[]{TEX+"short_grass.png",TEX+"iron_chain.png"},new Color[]{Color.BLUE,Color.RED});
        MinecraftResourceExtractor ex=new MinecraftResourceExtractor();
        assertTrue(ex.extractColorsFromMinecraftJar(file.toString())); BlockColors.setResourceExtractor(ex);
        assertEquals(Color.BLUE,BlockColors.getBlockColor("minecraft:grass"));
        assertEquals(Color.RED,BlockColors.getBlockColor("chain"));
        assertNull(BlockColors.lookupJarColor("example:chain",ex.getAllExtractedColors()));
    }
    @Test void truncatedRegionReportsIOExceptionInsteadOfArrayCrash() throws Exception {
        Path empty=temp.resolve("empty.mca"); Files.write(empty,new byte[0]);
        IOException error=assertThrows(IOException.class,() -> com.minecraft.selector.region.Region.fromFile(empty.toString()));
        assertTrue(error.getMessage().contains("Truncated MCA header"));
    }
    @Test void fullyTransparentTextureDoesNotProduceMagenta() throws Exception {
        Path file=jar("transparent.jar",new String[]{TEX+"invisible.png"},new Color[]{new Color(0,0,0,0)});
        MinecraftResourceExtractor ex=new MinecraftResourceExtractor();
        assertTrue(ex.extractColorsFromMinecraftJar(file.toString()));
        assertEquals(0,ex.getExtractedColor("invisible").getAlpha());
    }

    @Test void nullPixelsRenderAsTransparentInsteadOfMagenta() throws Exception {
        MapRenderer renderer = new MapRenderer(1,null);
        try {
            BufferedImage image=renderer.renderToPng(new MapRenderer.BlockInfo[2][2],1);
            for(int y=0;y<2;y++) for(int x=0;x<2;x++) assertEquals(0,image.getRGB(x,y)>>>24);
        } finally { renderer.shutdown(); }
    }
}
