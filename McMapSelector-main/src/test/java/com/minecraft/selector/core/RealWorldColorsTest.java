package com.minecraft.selector.core;

import org.junit.jupiter.api.*;
import com.minecraft.selector.region.Region;
import com.minecraft.selector.region.Chunk;
import com.minecraft.selector.utils.WorldPathResolver;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

/** Opt-in local integration: mvn test -Dselector.world=... -Dselector.jar=... */
class RealWorldColorsTest {
    static Path world, jar, regionDir;
    static Map<String,Color> colors;
    @BeforeAll static void setup() throws Exception {
        assumeTrue(System.getProperty("selector.world") != null && System.getProperty("selector.jar") != null,
            "Provide selector.world and selector.jar for local integration");
        world=Paths.get(System.getProperty("selector.world"));
        jar=Paths.get(System.getProperty("selector.jar"));
        regionDir=WorldPathResolver.getOverworldRegionDir(world.toString()).toPath();
        MinecraftResourceExtractor ex=new MinecraftResourceExtractor();
        assertTrue(ex.extractColorsFromMinecraftJar(jar.toString()));
        colors=ex.getAllExtractedColors();
        assertFalse(colors.isEmpty());
        BlockColors.setResourceExtractor(ex);
        Files.createDirectories(Paths.get("target/color-validation"));
    }
    @AfterAll static void reset() { BlockColors.setResourceExtractor(null); }

    @Test void realRegionsRenderIdenticallySeriallyAndConcurrently() throws Exception {
        String[] names={"r.0.0.mca","r.0.-1.mca","r.-1.0.mca","r.-1.-1.mca"};
        MapRenderer renderer=new MapRenderer(4,null);
        Map<String,Long> missing=new TreeMap<>();
        Map<String,int[]> baseline=new HashMap<>();
        Map<String,MapRenderer.BlockInfo[][]> data=new HashMap<>();
        ExecutorService pool=Executors.newFixedThreadPool(4);
        try {
            for(String name:names) {
                Path path=regionDir.resolve(name);
                assertTrue(Files.exists(path),name);
                MapRenderer.BlockInfo[][] blocks=renderer.getTopBlocks(path.toString(),32,1);
                data.put(name,blocks);
                int populated=0;
                for(MapRenderer.BlockInfo[] row:blocks) for(MapRenderer.BlockInfo b:row) {
                    if(b==null || BlockColors.isAirBlock(b.getBlockId())) continue;
                    populated++;
                    if(BlockColors.lookupJarColor(b.getBlockId(),colors)==null)
                        missing.merge(b.getBlockId(),1L,Long::sum);
                }
                assertTrue(populated>0,"Region must contain visible blocks: "+name);
                BufferedImage image=renderer.renderToPng(blocks,1);
                baseline.put(name,image.getRGB(0,0,512,512,null,0,512));
                ImageIO.write(image,"png",new File("target/color-validation/"+name+".png"));
            }
            List<Future<?>> results=new ArrayList<>();
            for(int round=0;round<3;round++) for(String name:names) results.add(pool.submit(() -> {
                BufferedImage image=renderer.renderToPng(data.get(name),1);
                assertArrayEquals(baseline.get(name),image.getRGB(0,0,512,512,null,0,512),name);
            }));
            for(Future<?> f:results) f.get(60,TimeUnit.SECONDS);
            List<String> lines=new ArrayList<>();
            missing.forEach((id,count) -> lines.add(id+"\t"+count));
            Files.write(Paths.get("target/color-validation/unmapped-blocks.tsv"),lines,java.nio.charset.StandardCharsets.UTF_8);
            System.out.println("Real-world audit: "+colors.size()+" extracted colors, "+missing.size()+" unmapped block IDs; 12 concurrent images pixel-identical");
            assertTrue(missing.isEmpty(), "Unmapped IDs: " + missing);
        } finally { renderer.shutdown(); pool.shutdownNow(); }
    }
    @Test void fullWorldTopBlockCoverage() throws Exception {
        assumeTrue(Boolean.getBoolean("selector.fullAudit"), "Enable selector.fullAudit for all regions");
        List<Path> regions=new ArrayList<>();
        try(java.util.stream.Stream<Path> paths=Files.list(regionDir)) {
            paths.filter(p -> p.toString().endsWith(".mca")).sorted().forEach(regions::add);
        }
        List<String> invalidFiles=new ArrayList<>();
        Iterator<Path> iter=regions.iterator();
        while(iter.hasNext()) {
            Path file=iter.next();
            if(Files.size(file)<8192) { invalidFiles.add(file.getFileName()+"\t"+Files.size(file)); iter.remove(); }
        }
        Files.write(Paths.get("target/color-validation/invalid-region-files.tsv"),invalidFiles,java.nio.charset.StandardCharsets.UTF_8);
        ExecutorService pool=Executors.newFixedThreadPool(4);
        Map<String,Long> missing=new ConcurrentHashMap<>();
        Set<String> ids=ConcurrentHashMap.newKeySet();
        java.util.concurrent.atomic.AtomicLong pixels=new java.util.concurrent.atomic.AtomicLong();
        try {
            List<Future<?>> jobs=new ArrayList<>();
            for(Path path:regions) jobs.add(pool.submit(() -> {
                MapRenderer renderer=new MapRenderer(2,null);
                try {
                    MapRenderer.BlockInfo[][] blocks=renderer.getTopBlocks(path.toString(),32,1);
                    for(MapRenderer.BlockInfo[] row:blocks) for(MapRenderer.BlockInfo b:row) {
                        if(b==null || BlockColors.isAirBlock(b.getBlockId())) continue;
                        pixels.incrementAndGet(); ids.add(b.getBlockId());
                        if(BlockColors.lookupJarColor(b.getBlockId(),colors)==null)
                            missing.merge(b.getBlockId(),1L,Long::sum);
                    }
                } catch(IOException e) { throw new UncheckedIOException(e); }
                finally { renderer.shutdown(); }
            }));
            for(Future<?> job:jobs) job.get(180,TimeUnit.SECONDS);
            List<String> lines=new ArrayList<>();
            new TreeMap<>(missing).forEach((id,count) -> lines.add(id+"\t"+count));
            Files.write(Paths.get("target/color-validation/full-world-unmapped.tsv"),lines,java.nio.charset.StandardCharsets.UTF_8);
            String report="Full-world audit: "+regions.size()+" valid regions, "+invalidFiles.size()+" invalid files excluded, "+pixels.get()+" visible pixels, "+ids.size()+" block IDs, "+missing.size()+" unmapped IDs";
            Files.write(Paths.get("target/color-validation/full-world-summary.txt"),Collections.singletonList(report),java.nio.charset.StandardCharsets.UTF_8);
            System.out.println(report);
            assertTrue(missing.isEmpty(),"Unmapped IDs: "+missing);
        } finally { pool.shutdownNow(); }
    }

    @Test void legacyRegionAndEmptyRegionDecodeAndRender() throws Exception {
        Region legacy=Region.fromFile(regionDir.resolve("r.6.-8.mca").toString());
        Chunk chunk=legacy.getChunk(0,0);
        assertNotNull(chunk);
        assertFalse(chunk.getSections().isEmpty());
        MapRenderer renderer=new MapRenderer(2,null);
        try {
            MapRenderer.BlockInfo[][] empty=renderer.getTopBlocks(regionDir.resolve("r.16.-6.mca").toString(),32,1);
            BufferedImage image=renderer.renderToPng(empty,1);
            for(int y=0;y<512;y++) for(int x=0;x<512;x++) assertEquals(0,image.getRGB(x,y)>>>24);
        } finally { renderer.shutdown(); }
    }
}
