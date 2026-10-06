package com.minecraft.selector.nbt;

import java.io.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;

/**
 * 快速NBT读取器
 * 专门优化用于快速提取区块数据，跳过不需要的数据
 */
public class FastNBTReader {
    private final DataInputStream input;
    private final ByteBuffer buffer;
    
    // 缓存常用的字符串，减少重复创建
    private static final Map<String, String> STRING_CACHE = new HashMap<>();
    private static final int MAX_CACHE_SIZE = 1000;
    
    public FastNBTReader(InputStream input) {
        this.input = new DataInputStream(input);
        this.buffer = ByteBuffer.allocate(8192).order(ByteOrder.BIG_ENDIAN);
    }
    
    /**
     * 快速读取区块数据，只提取必要的信息
     */
    public ChunkData readChunkData() throws IOException {
        // 跳过根标签名
        skipTag();
        
        ChunkData chunkData = new ChunkData();
        
        // 读取根复合标签
        readCompoundForChunk(chunkData);
        
        return chunkData;
    }
    
    /**
     * 专门用于区块的复合标签读取
     */
    private void readCompoundForChunk(ChunkData chunkData) throws IOException {
        while (true) {
            byte tagType = input.readByte();
            if (tagType == 0) break; // TAG_End
            
            String name = readString();
            
            switch (name) {
                case "xPos":
                    chunkData.xPos = readInt();
                    break;
                case "zPos":
                    chunkData.zPos = readInt();
                    break;
                case "sections":
                    chunkData.sections = readSectionsList();
                    break;
                default:
                    // 跳过不需要的标签
                    skipTagValue(tagType);
                    break;
            }
        }
    }
    
    /**
     * 读取区段列表
     */
    private List<SectionData> readSectionsList() throws IOException {
        byte listType = input.readByte();
        int length = input.readInt();
        
        List<SectionData> sections = new ArrayList<>(length);
        
        for (int i = 0; i < length; i++) {
            SectionData section = readSection();
            if (section != null) {
                sections.add(section);
            }
        }
        
        return sections;
    }
    
    /**
     * 读取单个区段
     */
    private SectionData readSection() throws IOException {
        SectionData section = new SectionData();
        
        while (true) {
            byte tagType = input.readByte();
            if (tagType == 0) break; // TAG_End
            
            String name = readString();
            
            switch (name) {
                case "Y":
                    section.y = readByte();
                    break;
                case "block_states":
                    section.blockStates = readBlockStates();
                    break;
                default:
                    skipTagValue(tagType);
                    break;
            }
        }
        
        return section.blockStates != null ? section : null;
    }
    
    /**
     * 读取方块状态数据
     */
    private BlockStatesData readBlockStates() throws IOException {
        BlockStatesData blockStates = new BlockStatesData();
        
        while (true) {
            byte tagType = input.readByte();
            if (tagType == 0) break; // TAG_End
            
            String name = readString();
            
            switch (name) {
                case "palette":
                    blockStates.palette = readPalette();
                    break;
                case "data":
                    blockStates.data = readLongArray();
                    break;
                default:
                    skipTagValue(tagType);
                    break;
            }
        }
        
        return blockStates;
    }
    
    /**
     * 读取调色板
     */
    private List<String> readPalette() throws IOException {
        byte listType = input.readByte();
        int length = input.readInt();
        
        List<String> palette = new ArrayList<>(length);
        
        for (int i = 0; i < length; i++) {
            // 读取复合标签
            String blockName = null;
            
            while (true) {
                byte tagType = input.readByte();
                if (tagType == 0) break; // TAG_End
                
                String name = readString();
                
                if ("Name".equals(name)) {
                    blockName = readString();
                } else {
                    skipTagValue(tagType);
                }
            }
            
            palette.add(blockName != null ? blockName : "minecraft:air");
        }
        
        return palette;
    }
    
    /**
     * 读取长整型数组
     */
    private long[] readLongArray() throws IOException {
        int length = input.readInt();
        long[] array = new long[length];
        
        for (int i = 0; i < length; i++) {
            array[i] = input.readLong();
        }
        
        return array;
    }
    
    /**
     * 优化的字符串读取，使用缓存
     */
    private String readString() throws IOException {
        int length = input.readUnsignedShort();
        
        if (length == 0) {
            return "";
        }
        
        byte[] bytes = new byte[length];
        input.readFully(bytes);
        String str = new String(bytes, "UTF-8");
        
        // 缓存常用字符串
        if (STRING_CACHE.size() < MAX_CACHE_SIZE) {
            String cached = STRING_CACHE.get(str);
            if (cached != null) {
                return cached;
            }
            STRING_CACHE.put(str, str);
        }
        
        return str;
    }
    
    /**
     * 跳过标签值
     */
    private void skipTagValue(byte tagType) throws IOException {
        switch (tagType) {
            case 1: input.readByte(); break; // TAG_Byte
            case 2: input.readShort(); break; // TAG_Short
            case 3: input.readInt(); break; // TAG_Int
            case 4: input.readLong(); break; // TAG_Long
            case 5: input.readFloat(); break; // TAG_Float
            case 6: input.readDouble(); break; // TAG_Double
            case 7: skipByteArray(); break; // TAG_Byte_Array
            case 8: skipString(); break; // TAG_String
            case 9: skipList(); break; // TAG_List
            case 10: skipCompound(); break; // TAG_Compound
            case 11: skipIntArray(); break; // TAG_Int_Array
            case 12: skipLongArray(); break; // TAG_Long_Array
        }
    }
    
    private void skipTag() throws IOException {
        byte tagType = input.readByte();
        if (tagType != 0) {
            skipString(); // 跳过标签名
        }
    }
    
    private void skipString() throws IOException {
        int length = input.readUnsignedShort();
        input.skipBytes(length);
    }
    
    private void skipByteArray() throws IOException {
        int length = input.readInt();
        input.skipBytes(length);
    }
    
    private void skipIntArray() throws IOException {
        int length = input.readInt();
        input.skipBytes(length * 4);
    }
    
    private void skipLongArray() throws IOException {
        int length = input.readInt();
        input.skipBytes(length * 8);
    }
    
    private void skipList() throws IOException {
        byte listType = input.readByte();
        int length = input.readInt();
        
        for (int i = 0; i < length; i++) {
            skipTagValue(listType);
        }
    }
    
    private void skipCompound() throws IOException {
        while (true) {
            byte tagType = input.readByte();
            if (tagType == 0) break; // TAG_End
            
            skipString(); // 跳过标签名
            skipTagValue(tagType);
        }
    }
    
    private byte readByte() throws IOException {
        return input.readByte();
    }
    
    private int readInt() throws IOException {
        return input.readInt();
    }
    
    /**
     * 区块数据结构
     */
    public static class ChunkData {
        public int xPos;
        public int zPos;
        public List<SectionData> sections = new ArrayList<>();
    }
    
    /**
     * 区段数据结构
     */
    public static class SectionData {
        public byte y;
        public BlockStatesData blockStates;
    }
    
    /**
     * 方块状态数据结构
     */
    public static class BlockStatesData {
        public List<String> palette;
        public long[] data;
    }
}
