package com.minecraft.selector.region;

import com.minecraft.selector.nbt.NBTReader.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 26.3+ palette entries: bare id, {"": id}, {id, properties}; legacy {Name, Properties}. */
public class PaletteFormatTest {
    @Test
    public void parsesAllPaletteForms() {
        assertEquals("stone", Block.fromPaletteTag(new NBTString("", "minecraft:stone")).getId());

        NBTCompound wrapped = new NBTCompound("");
        wrapped.put("", new NBTString("", "minecraft:grass_block"));
        assertEquals("grass_block", Block.fromPaletteTag(wrapped).getId());

        NBTCompound modern = new NBTCompound("");
        modern.put("id", new NBTString("id", "minecraft:oak_log"));
        NBTCompound props = new NBTCompound("properties");
        props.put("axis", new NBTString("axis", "x"));
        modern.put("properties", props);
        Block log = Block.fromPaletteTag(modern);
        assertEquals("oak_log", log.getId());
        assertEquals("x", log.getProperty("axis"));

        NBTCompound legacy = new NBTCompound("");
        legacy.put("Name", new NBTString("Name", "minecraft:water"));
        assertEquals("water", Block.fromPaletteTag(legacy).getId());
    }
}
