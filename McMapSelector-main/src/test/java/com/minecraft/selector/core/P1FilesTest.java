package com.minecraft.selector.core;
import com.minecraft.selector.gui.*;
import com.minecraft.selector.utils.RecentWorlds;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.awt.Color;
import static org.junit.jupiter.api.Assertions.*;

class P1FilesTest {
    @TempDir Path directory;
    @Test void presetRoundTripKeepsExtendedYAndDimensionAndRejectsMalformedData() throws Exception {
        Path file=directory.resolve("selection.json");SelectionPreset original=new SelectionPreset(new SelectionMath.Bounds(-513,-1,20,30),-128,655,"mod:city/underground");original.save(file.toFile());SelectionPreset loaded=SelectionPreset.load(file.toFile());
        assertEquals(original.bounds,loaded.bounds);assertEquals(original.minY,loaded.minY);assertEquals(original.maxY,loaded.maxY);assertEquals(original.dimension,loaded.dimension);
        String good=new String(Files.readAllBytes(file),"UTF-8");
        for(String bad:new String[]{"[]",good.replace("\"version\" : 1","\"version\" : 2"),good.replace("\"minX\" : -513","\"minX\" : 999"),good.replace("\"minX\" : -513","\"minX\" : 2147483648"),good.replace("\"minX\" : -513","\"minX\" : 1.5"),good.replace("\"minY\" : -128,",""),good.replace("mod:city/underground","invalid")}) {
            Files.write(file,bad.getBytes("UTF-8"));assertThrows(java.io.IOException.class,()->SelectionPreset.load(file.toFile()),bad);
        }
    }
    @Test void coordinatePayloadCanAlsoLoadAsPresetWithOptionalYAndDimension() throws Exception {
        Path file=directory.resolve("coordinates.json");Files.write(file,"{\"minX\":-1,\"minZ\":-2,\"maxX\":3,\"maxZ\":4}".getBytes("UTF-8"));SelectionPreset p=SelectionPreset.load(file.toFile());assertNull(p.minY);assertNull(p.dimension);
    }
    @Test void seedRejectsPartialDuplicateReversedAndOverflowWhilePreservingOtherFlags() {
        String[] args={"--world-path","world","--min-x","-513","--max-x","0","--min-z","-2","--max-z","8","--output-file","coordinates.json","--min-y","-64"};
        assertEquals(new SelectionMath.Bounds(-513,-2,0,8),SelectionSeed.parse(args));assertArrayEquals(new String[]{"--world-path","world","--output-file","coordinates.json","--min-y","-64"},SelectionSeed.withoutFlags(args));
        assertNull(SelectionSeed.parse(new String[]{"--world-path","world"}));
        assertArrayEquals(new String[]{"--world-path","world"},SelectionSeed.withoutFlags(new String[]{"--min-x","--world-path","world"}));
        for(String[] invalid:new String[][]{{"--min-x","1"},{"--min-x"},{"--min-x","1","--min-x","2"},{"--min-x","2147483648"},{"--min-x","2","--max-x","1","--min-z","0","--max-z","0"}}) assertThrows(IllegalArgumentException.class,()->SelectionSeed.parse(invalid));
    }
    @Test void recentWorldsPersistReorderBoundAndPruneMissingPaths() throws Exception {
        Path file=directory.resolve("config/recent.properties");RecentWorlds recent=new RecentWorlds(file.toFile());assertTrue(recent.list().isEmpty());
        for(int i=0;i<12;i++) recent.add(Files.createDirectories(directory.resolve("world "+i)).toFile());
        assertEquals(10,recent.list().size());assertTrue(recent.list().get(0).endsWith("world 11"));
        recent.add(directory.resolve("world 3").toFile());assertTrue(new RecentWorlds(file.toFile()).list().get(0).endsWith("world 3"));assertEquals(10,recent.list().size());
        Files.delete(directory.resolve("world 3"));assertEquals(9,recent.list().size());assertFalse(new String(Files.readAllBytes(file),"ISO-8859-1").contains("world 3"));
    }
    @Test void coverageUsesVisibleColumnCountsAndSeparatesUnknownNamespaces() {
        BlockColors.Palette palette=BlockColors.snapshot();palette.appearance("unknown_p1:chair",Collections.emptyMap());palette.appearance("stone",Collections.emptyMap());Map<String,Long> counts=new HashMap<>();counts.put("unknown_p1:chair",21L);counts.put("minecraft:stone",3L);
        String report=ResourceCoveragePanel.report(counts,palette);assertTrue(report.contains("24 columns"));assertTrue(report.contains("unknown_p1=21"));assertTrue(report.contains("unknown_p1:chair  21 columns"));assertFalse(report.contains("minecraft:stone  3 columns"));
    }
    @Test void scaleBarNiceDistancesStayReadableAcrossZooms() {
        for(double zoom:new double[]{.001,.1,.25,1,3,10,100}) {double d=ViewOverlays.scaleDistance(zoom);assertTrue(d*zoom<=120.000001);assertTrue(d*zoom>=40);}
        assertEquals(100,ViewOverlays.scaleDistance(1));assertEquals(10,ViewOverlays.scaleDistance(10));assertThrows(IllegalArgumentException.class,()->ViewOverlays.scaleDistance(0));
    }
}
