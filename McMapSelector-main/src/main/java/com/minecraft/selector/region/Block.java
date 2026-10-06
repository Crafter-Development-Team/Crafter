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
    public static Block fromPalette(NBTReader.NBTCompound paletteEntry) {
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
