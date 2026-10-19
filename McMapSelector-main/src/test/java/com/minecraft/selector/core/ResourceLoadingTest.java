package com.minecraft.selector.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.zip.*;
import javax.imageio.ImageIO;
import static org.junit.jupiter.api.Assertions.*;

class ResourceLoadingTest {
    @TempDir Path directory;
    private byte[] png(int color) throws Exception {
        BufferedImage image=new BufferedImage(2,2,BufferedImage.TYPE_INT_ARGB);
        for(int x=0;x<2;x++) for(int y=0;y<2;y++) image.setRGB(x,y,color);
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();ImageIO.write(image,"PNG",bytes);return bytes.toByteArray();
    }
    private Path archive(String name,Map<String,byte[]> entries) throws Exception {
        Path path=directory.resolve(name);
        try(ZipOutputStream zip=new ZipOutputStream(Files.newOutputStream(path))) {
            for(Map.Entry<String,byte[]> entry:entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));zip.write(entry.getValue());zip.closeEntry();
            }
        }return path;
    }
    private byte[] json(String text) throws Exception {return text.getBytes("UTF-8");}
    @Test void parallelArchivesPreserveOverrideOrderAndCrossArchiveModelInheritance() throws Exception {
        Map<String,byte[]> base=new HashMap<>(),mod=new HashMap<>(),pack=new HashMap<>();
        base.put("assets/test/models/block/parent.json",json("{\"textures\":{\"all\":\"#material\"},\"elements\":[{\"from\":[0,0,0],\"to\":[16,1,16],\"faces\":{\"up\":{\"texture\":\"#all\"}}}]}"));
        mod.put("assets/test/models/block/child.json",json("{\"parent\":\"test:block/parent\",\"textures\":{\"material\":\"test:block/material\"}}"));
        mod.put("assets/test/blockstates/child.json",json("{\"variants\":{\"\":{\"model\":\"test:block/child\"}}}"));
        mod.put("assets/test/textures/block/material.png",png(0xffb00b17));
        pack.put("assets/test/textures/block/material.png",png(0xff12ba34));
        List<String> paths=Arrays.asList(archive("base.jar",base).toString(),archive("mod.jar",mod).toString(),archive("pack.zip",pack).toString());
        List<Integer> progress=new ArrayList<>();MinecraftResourceExtractor parallel=new MinecraftResourceExtractor();
        parallel.addArchives(paths,(completed,total,message) -> {assertEquals(3,total);progress.add(completed);});
        MinecraftResourceExtractor serial=new MinecraftResourceExtractor();for(String path:paths) serial.addArchive(path);
        assertEquals(new Color(0x12ba34),parallel.getExtractedColor("test:child"));
        assertEquals(serial.getExtractedColor("test:child"),parallel.getExtractedColor("test:child"));
        assertEquals(Arrays.asList(1,2,3),progress);
    }
    @Test void sharedDiscoveryIndexAndContentHashPreventArchiveRescanAndDuplicatePngDecode() throws Exception {
        byte[] identical=png(0xff3192e7);
        Path first=archive("a.jar",Collections.singletonMap("assets/cachetest/textures/block/a.png",identical));
        Path second=archive("b.jar",Collections.singletonMap("assets/cachetest/textures/block/b.png",identical));
        int indexes=ResourceArchiveCache.INDEX_READS.get(),decodes=ResourceArchiveCache.PNG_DECODES.get();
        assertTrue(ResourceArchiveCache.index(first.toString()).namespaces.contains("cachetest"));
        MinecraftResourceExtractor extractor=new MinecraftResourceExtractor();
        extractor.addArchives(Arrays.asList(first.toString(),second.toString()),(c,t,m)->{});
        extractor.addArchive(first.toString());
        assertEquals(indexes+2,ResourceArchiveCache.INDEX_READS.get());assertEquals(decodes+1,ResourceArchiveCache.PNG_DECODES.get());
        assertEquals(extractor.getExtractedColor("cachetest:a"),extractor.getExtractedColor("cachetest:b"));
    }
    @Test void changedArchiveInvalidatesFingerprintEvenWhenMtimeIsRestoredAndKeepsOldPaletteImmutable() throws Exception {
        Path file=archive("change.zip",Collections.singletonMap("assets/change/textures/block/block.png",png(0xff128ace)));
        ResourceOptions options=new ResourceOptions();options.auto=false;options.packs.add(file.toString());
        BlockColors.Palette old=BlockColors.loadPalette(directory.toString(),options);
        String fingerprint=ResourceArchiveCache.index(file.toString()).fingerprint;FileTime mtime=Files.getLastModifiedTime(file);
        archive("change.zip",Collections.singletonMap("assets/change/textures/block/block.png",png(0xffce8a12)));
        Files.setLastModifiedTime(file,mtime);
        assertNotEquals(fingerprint,ResourceArchiveCache.index(file.toString()).fingerprint);
        BlockColors.Palette changed=BlockColors.loadPalette(directory.toString(),options);
        assertEquals(new Color(0xce8a12),changed.getBlockColor("change:block"));assertEquals(new Color(0x128ace),old.getBlockColor("change:block"));
    }
    @Test void cachedResourceColorsAreReusedWithoutSharingWorldUsageOrOverlays() throws Exception {
        Path base=archive("base.jar",Collections.singletonMap("assets/worldcache/textures/block/block.png",png(0xffabcd12)));
        Path pack=archive("overlay.zip",Collections.singletonMap("assets/worldcache/textures/block/block.png",png(0xff1234ab)));
        ResourceOptions a=new ResourceOptions();a.auto=false;a.jars.add(base.toString());a.packs.add(pack.toString());
        BlockColors.Palette first=BlockColors.loadPalette(directory.resolve("worldA").toString(),a);
        int decodes=ResourceArchiveCache.PNG_DECODES.get();List<String> messages=new ArrayList<>();
        BlockColors.Palette again=BlockColors.loadPalette(directory.resolve("worldA").toString(),a,(c,t,m)->messages.add(m));
        assertTrue(messages.stream().anyMatch(m->m.contains("Reusing")));assertEquals(decodes,ResourceArchiveCache.PNG_DECODES.get());
        assertNotSame(first,again);assertTrue(again.usageReport().isEmpty());
        ResourceOptions b=new ResourceOptions();b.auto=false;b.jars.add(base.toString());
        BlockColors.Palette other=BlockColors.loadPalette(directory.resolve("worldB").toString(),b);
        assertEquals(new Color(0xabcd12),other.getBlockColor("worldcache:block"));
        assertEquals(new Color(0x1234ab),again.getBlockColor("worldcache:block"));
        assertTrue(first.usageReport().isEmpty());
    }
    @Test void brokenArchiveFailsAndCanBeRetriedAfterRepair() throws Exception {
        Path file=directory.resolve("broken.zip");Files.write(file,new byte[]{1,2,3});
        MinecraftResourceExtractor extractor=new MinecraftResourceExtractor();
        assertThrows(IOException.class,()->extractor.addArchives(Collections.singletonList(file.toString()),(c,t,m)->{}));
        archive("broken.zip",Collections.singletonMap("assets/retry/textures/block/block.png",png(0xffcb12ad)));
        extractor.addArchives(Collections.singletonList(file.toString()),(c,t,m)->{});
        assertEquals(new Color(0xcb12ad),extractor.getExtractedColor("retry:block"));
    }
}
