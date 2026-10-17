package com.minecraft.selector.utils;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** 26.1+ dimensions/ layout vs legacy region/ / DIM-* paths; error/fallback messages. */
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

        WorldPathResolver.RegionResolve resolve = WorldPathResolver.resolveRegionDir(world.toString(), "minecraft:overworld");
        assertTrue(resolve.isFound());
        assertFalse(resolve.usedLegacyFallback());
        assertNull(resolve.getNote());
        assertFalse(resolve.getTriedPaths().isEmpty());
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

        WorldPathResolver.RegionResolve resolve = WorldPathResolver.resolveRegionDir(world.toString(), "minecraft:overworld");
        assertTrue(resolve.isFound());
        assertTrue(resolve.usedLegacyFallback());
        assertNotNull(resolve.getNote());
        assertTrue(resolve.getNote().contains("已回退到 legacy"));
        assertTrue(resolve.getNote().contains("region"));
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
        WorldPathResolver.RegionResolve resolve = WorldPathResolver.resolveRegionDir(world.toString(), "minecraft:overworld");
        assertTrue(resolve.isFound());
        assertFalse(resolve.usedLegacyFallback());
    }

    @Test void missingRegionListsTriedPaths() throws Exception {
        Path world = tmp.resolve("empty");
        Files.createDirectories(world);
        Files.write(world.resolve("level.dat"), new byte[]{1});

        WorldPathResolver.RegionResolve resolve = WorldPathResolver.resolveRegionDir(world.toString(), "minecraft:overworld");
        assertFalse(resolve.isFound());
        assertNotNull(resolve.getNote());
        assertTrue(resolve.getNote().contains("找不到 region"));
        assertTrue(resolve.getTriedPaths().stream().anyMatch(p -> p.replace('\\','/').contains("dimensions/minecraft/overworld/region")));
        assertTrue(resolve.getTriedPaths().stream().anyMatch(p -> p.replace('\\','/').endsWith("/region")));
        String missing = resolve.missingMessage("minecraft:overworld");
        assertTrue(missing.contains("找不到 region"));
        assertTrue(missing.contains("26.1+"));
        assertTrue(missing.contains("Tried:"));

        String explain = WorldPathResolver.explainInvalidSave(world.toString());
        assertNotNull(explain);
        assertTrue(explain.contains("找不到 region") || explain.contains("Tried"));
        assertFalse(WorldPathResolver.isValidMinecraftSave(world.toString()));
    }

    @Test void zeroByteMcaPlaceholdersAreValid() throws Exception {
        Path world = tmp.resolve("placeholder-mca");
        Files.createDirectories(world.resolve("region"));
        Files.write(world.resolve("level.dat"), new byte[]{1});
        // 0-byte .mca = ungenerated chunks; normal save, not an error
        Files.write(world.resolve("region/r.0.0.mca"), new byte[]{});

        assertNull(WorldPathResolver.explainInvalidSave(world.toString()));
        assertTrue(WorldPathResolver.isValidMinecraftSave(world.toString()));
        assertTrue(WorldPathResolver.hasMcaFiles(world.resolve("region").toFile()));
        assertNull(WorldPathResolver.findFirstMca(world.resolve("region").toFile()),
            "findFirstMca still skips 0-byte for content probing");
        assertEquals(1, WorldPathResolver.discoverDimensions(world.toString()).size());
    }

    @Test void regionDirWithNoMcaFilenamesIsInvalid() throws Exception {
        Path world = tmp.resolve("no-mca-names");
        Files.createDirectories(world.resolve("region"));
        Files.write(world.resolve("level.dat"), new byte[]{1});
        // region exists but has zero .mca files at all
        Files.write(world.resolve("region/readme.txt"), new byte[]{1});

        String explain = WorldPathResolver.explainInvalidSave(world.toString());
        assertNotNull(explain);
        assertTrue(explain.contains("没有 .mca") || explain.toLowerCase().contains("no .mca"),
            "expected no-.mca-filename message, got: " + explain);
        assertFalse(explain.toLowerCase().contains("empty region (no .mca)"));
        assertFalse(explain.contains("无非空"));
        assertFalse(WorldPathResolver.isValidMinecraftSave(world.toString()));
    }

    @Test void missingLevelDatExplained() throws Exception {
        Path world = tmp.resolve("no-level");
        Files.createDirectories(world.resolve("region"));
        Files.write(world.resolve("region/r.0.0.mca"), new byte[]{1});
        String explain = WorldPathResolver.explainInvalidSave(world.toString());
        assertNotNull(explain);
        assertTrue(explain.contains("level.dat"));
    }

    @Test void nonexistentPathExplained() {
        String explain = WorldPathResolver.explainInvalidSave(tmp.resolve("does-not-exist").toString());
        assertNotNull(explain);
        assertTrue(explain.contains("不存在") || explain.toLowerCase().contains("does not exist")
            || explain.toLowerCase().contains("not exist"));
    }

    /** Forward-slash children must work (Java File accepts '/' on Windows too). */
    @Test void forwardSlashChildPathsResolveModernLayout() throws Exception {
        Path world = tmp.resolve("slash-child");
        Files.createDirectories(world.resolve("dimensions/minecraft/overworld/region"));
        Files.write(world.resolve("level.dat"), new byte[]{1});
        Files.write(world.resolve("dimensions/minecraft/overworld/region/r.0.0.mca"), new byte[]{1});

        File saveDir = WorldPathResolver.normalizeSaveDir(world.toString());
        // Same construction WorldPathResolver uses — critical on Windows Blender paths.
        File viaChild = new File(saveDir, "dimensions/minecraft/overworld/region");
        assertTrue(viaChild.isDirectory(), "File(parent, \"dimensions/.../region\") must resolve");
        assertTrue(WorldPathResolver.isValidMinecraftSave(world.toString()));
        assertEquals("minecraft:overworld",
            WorldPathResolver.discoverDimensions(world.toString()).get(0).getId());
    }

    /** Paths containing spaces (common under Windows user profiles). */
    @Test void savePathWithSpacesStillResolves() throws Exception {
        Path world = tmp.resolve("My World 26.3");
        Files.createDirectories(world.resolve("dimensions/minecraft/overworld/region"));
        Files.write(world.resolve("level.dat"), new byte[]{1});
        Files.write(world.resolve("dimensions/minecraft/overworld/region/r.-1.0.mca"), new byte[]{9});

        assertTrue(WorldPathResolver.isValidMinecraftSave(world.toString()));
        File region = WorldPathResolver.getOverworldRegionDir(world.toString());
        assertTrue(region.getAbsolutePath().replace('\\','/').contains("dimensions/minecraft/overworld/region"));
        assertTrue(region.getAbsolutePath().contains("My World 26.3")
            || region.getAbsolutePath().contains("My World 26.3".replace(' ', ' ')));
        List<WorldPathResolver.DimensionInfo> dims = WorldPathResolver.discoverDimensions(world.toString());
        assertEquals(1, dims.size());
        assertEquals("minecraft:overworld", dims.get(0).getId());
    }

    /**
     * Simulate Windows-style absolute path strings that Blender may pass via os.path.normpath.
     * On Linux, backslashes are NOT separators — callers must pass a real filesystem path
     * (File/Path), never a raw "C:\\..." string. normalizeSaveDir(absPath) round-trips.
     */
    @Test void normalizeSaveDirRoundTripAndLegacyDimChild() throws Exception {
        Path world = tmp.resolve("win-legacy");
        Files.createDirectories(world.resolve("DIM-1/region"));
        Files.write(world.resolve("level.dat"), new byte[]{1});
        Files.write(world.resolve("DIM-1/region/r.0.0.mca"), new byte[]{1});
        Files.createDirectories(world.resolve("region"));
        Files.write(world.resolve("region/r.0.0.mca"), new byte[]{1});

        File normalized = WorldPathResolver.normalizeSaveDir(world.toString());
        assertTrue(normalized.isDirectory());
        // Legacy nether child uses "DIM-1/region" with forward slash — works on Windows+Unix.
        File nether = new File(normalized, "DIM-1/region");
        assertTrue(nether.isDirectory());
        WorldPathResolver.RegionResolve netherResolve =
            WorldPathResolver.resolveRegionDir(normalized.getAbsolutePath(), "minecraft:the_nether");
        assertTrue(netherResolve.isFound());
        assertTrue(netherResolve.getRegionDir().getAbsolutePath().replace('\\','/')
            .endsWith("DIM-1/region") || netherResolve.getRegionDir().getName().equals("region"));
    }

    @Test void discoverDimensionsIdsStableWithoutUriDriveCaseBug() throws Exception {
        Path world = tmp.resolve("uri-case");
        Files.createDirectories(world.resolve("dimensions/minecraft/overworld/region"));
        Files.createDirectories(world.resolve("dimensions/minecraft/the_nether/region"));
        Files.write(world.resolve("level.dat"), new byte[]{1});
        Files.write(world.resolve("dimensions/minecraft/overworld/region/r.0.0.mca"), new byte[]{1});
        Files.write(world.resolve("dimensions/minecraft/the_nether/region/r.0.0.mca"), new byte[]{1});

        List<WorldPathResolver.DimensionInfo> dims =
            WorldPathResolver.discoverDimensions(world.toAbsolutePath().toString());
        assertEquals(2, dims.size());
        assertEquals("minecraft:overworld", dims.get(0).getId());
        assertTrue(dims.stream().anyMatch(d -> "minecraft:the_nether".equals(d.getId())));
        // Must not produce ids like ":C:/..." or "minecraft:overworld/" from broken URI relativize.
        for (WorldPathResolver.DimensionInfo d : dims) {
            assertFalse(d.getId().startsWith(":"), d.getId());
            assertFalse(d.getId().endsWith("/"), d.getId());
            assertTrue(d.getId().contains(":"), d.getId());
        }
    }
}
