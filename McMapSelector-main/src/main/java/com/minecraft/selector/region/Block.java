package com.minecraft.selector.region;

import com.minecraft.selector.nbt.NBTReader;
import java.util.Map;
import java.util.HashMap;

/**
 * 表示Minecraft中的一个方块
 * 对应Python anvil库中的Block类
 */
public class Block {
    private String id;
    private Map<String, String> properties;
    
    public Block(String id) {
        this.id = id;
        this.properties = new HashMap<>();
    }
    
    public Block(String id, Map<String, String> properties) {
        this.id = id;
        this.properties = properties != null ? new HashMap<>(properties) : new HashMap<>();
    }
    
    /**
     * 从NBT调色板数据创建方块
     */
    /**
     * 从任意调色板条目创建方块：兼容 26.3+ 默认状态简写（裸字符串、混合列表里的 {"": id}）
     * 与 {id, properties} 新字段名，以及旧版 {Name, Properties}。
     */
    public static Block fromPaletteTag(NBTReader.NBTTag entry) {
        if (entry instanceof NBTReader.NBTString) {
            return fromPaletteId(((NBTReader.NBTString) entry).getValue(), null);
        }
        if (entry instanceof NBTReader.NBTCompound) {
            NBTReader.NBTCompound c = (NBTReader.NBTCompound) entry;
            if (c.get("Name") instanceof NBTReader.NBTString) return fromPalette(c);
            if (c.get("id") instanceof NBTReader.NBTString) {
                NBTReader.NBTTag props = c.get("properties");
                return fromPaletteId(c.getString("id"),
                        props instanceof NBTReader.NBTCompound ? (NBTReader.NBTCompound) props : null);
            }
            if (c.get("") instanceof NBTReader.NBTString) return fromPaletteId(c.getString(""), null);
        }
        return new Block("air");
    }

    private static Block fromPaletteId(String name, NBTReader.NBTCompound props) {
        if (name.startsWith("minecraft:")) name = name.substring("minecraft:".length());
        Map<String, String> properties = new HashMap<>();
        if (props != null) {
            for (String key : props.keySet()) {
                NBTReader.NBTTag tag = props.get(key);
                if (tag instanceof NBTReader.NBTString) properties.put(key, ((NBTReader.NBTString) tag).getValue());
            }
        }
        return new Block(name, properties);
    }

    public static Block fromPalette(NBTReader.NBTCompound paletteEntry) {
        if (!(paletteEntry.get("Name") instanceof NBTReader.NBTString)) return fromPaletteTag(paletteEntry);
        String name = paletteEntry.getString("Name");
        
        // 移除minecraft:前缀
        if (name.startsWith("minecraft:")) {
            name = name.substring("minecraft:".length());
        }
        
        Map<String, String> properties = new HashMap<>();
        
        // 读取方块属性
        if (paletteEntry.contains("Properties")) {
            NBTReader.NBTCompound props = paletteEntry.getCompound("Properties");
            for (String key : props.keySet()) {
                NBTReader.NBTTag tag = props.get(key);
                if (tag instanceof NBTReader.NBTString) {
                    properties.put(key, ((NBTReader.NBTString) tag).getValue());
                }
            }
        }
        
        return new Block(name, properties);
    }
    
    /**
     * 获取方块ID
     */
    public String getId() {
        return id;
    }
    
    /**
     * 获取方块属性
     */
    public Map<String, String> getProperties() {
        return new HashMap<>(properties);
    }
    
    /**
     * 获取指定属性值
     */
    public String getProperty(String key) {
        return properties.get(key);
    }
    
    /**
     * 检查是否有指定属性
     */
    public boolean hasProperty(String key) {
        return properties.containsKey(key);
    }
    
    /**
     * 检查是否为空气方块
     */
    public boolean isAir() {
        return "air".equals(id) || "cave_air".equals(id) || "void_air".equals(id);
    }
    
    @Override
    public String toString() {
        if (properties.isEmpty()) {
            return id;
        }
        
        StringBuilder sb = new StringBuilder(id);
        sb.append("[");
        boolean first = true;
        for (Map.Entry<String, String> entry : properties.entrySet()) {
            if (!first) {
                sb.append(",");
            }
            sb.append(entry.getKey()).append("=").append(entry.getValue());
            first = false;
        }
        sb.append("]");
        return sb.toString();
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        
        Block block = (Block) obj;
        return id.equals(block.id) && properties.equals(block.properties);
    }
    
    @Override
    public int hashCode() {
        return id.hashCode() * 31 + properties.hashCode();
    }
}
