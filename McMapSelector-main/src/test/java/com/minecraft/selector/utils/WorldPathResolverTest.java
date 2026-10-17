package com.minecraft.selector.utils;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** 26.1+ dimensions/ layout vs legacy region/ / DIM-* paths. */
class WorldPathResolverTest {
    @TempDir Path tmp;

    @Test void modern26LayoutResolvesOverworldNetherEnd() throws Exception {
        Path world = tmp.resolve("modern");
        Files.createDirectories(world.resolve("dimensions/minecraft/overworld/region"));
        Files.createDirectories(world.resolve("dimensions/minecraft/the_nether/region"));
        Files.createDirectories(world.resolve("dimensions/minecraft/the_end/region"));
        Files.write(world.resolve("level.dat"), new byte[]{1});
        Files.write(world.resolve("dimensions/minecraft/overworld/region/r.0.0.mca"), new byte[]{1,2,3});
        Files.write(world.resolve("dimensions/minecraft/the_nether/region/r.0.0.mca"), new byte[]{1});
        Files.write(world.resolve("dimensions/minecraft/the_end/region/r.-1.2.mca"), new byte[]{1});

        File overworld = WorldPathResolver.getOverworldRegionDir(world.toString());
        assertTrue(overworld.getAbsolutePath().replace('\\','/').endsWith("dimensions/minecraft/overworld/region"));
        assertTrue(WorldPathResolver.isValidMinecraftSave(world.toString()));

        List<WorldPathResolver.DimensionInfo> dims = WorldPathResolver.discoverDimensions(world.toString());
        assertEquals(3, dims.size());
        assertEquals("minecraft:overworld", dims.get(0).getId());
        assertTrue(dims.stream().anyMatch(d -> "minecraft:the_nether".equals(d.getId())));
        assertTrue(dims.stream().anyMatch(d -> "minecraft:the_end".equals(d.getId())));
        assertNotNull(WorldPathResolver.findFirstMca(overworld));
    }

    @Test void legacyLayoutStillPreferredWhenOnlyLegacyPresent() throws Exception {
        Path world = tmp.resolve("legacy");
        Files.createDirectories(world.resolve("region"));
        Files.createDirectories(world.resolve("DIM-1/region"));
        Files.write(world.resolve("level.dat"), new byte[]{1});
        Files.write(world.resolve("region/r.1.2.mca"), new byte[]{9});
        Files.write(world.resolve("DIM-1/region/r.0.0.mca"), new byte[]{9});

        File overworld = WorldPathResolver.getOverworldRegionDir(world.toString());
        assertTrue(overworld.getAbsolutePath().replace('\\','/').endsWith("/region"));
        assertFalse(overworld.getAbsolutePath().replace('\\','/').contains("dimensions/"));
        assertTrue(WorldPathResolver.isValidMinecraftSave(world.toString()));
        assertEquals(2, WorldPathResolver.discoverDimensions(world.toString()).size());
    }

    @Test void modernLayoutWinsWhenBothExist() throws Exception {
        Path world = tmp.resolve("both");
        Files.createDirectories(world.resolve("region"));
        Files.createDirectories(world.resolve("dimensions/minecraft/overworld/region"));
        Files.write(world.resolve("level.dat"), new byte[]{1});
        Files.write(world.resolve("region/r.0.0.mca"), new byte[]{1});
        Files.write(world.resolve("dimensions/minecraft/overworld/region/r.0.0.mca"), new byte[]{1});
        File overworld = WorldPathResolver.getOverworldRegionDir(world.toString());
        assertTrue(overworld.getAbsolutePath().replace('\\','/').contains("dimensions/minecraft/overworld/region"));
    }
}
