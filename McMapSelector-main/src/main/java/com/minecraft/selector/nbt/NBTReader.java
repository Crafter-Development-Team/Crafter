package com.minecraft.selector.nbt;

import java.io.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;

/**
 * NBT数据读取器，用于读取Minecraft的NBT格式数据
 * 对应Python nbt库的功能
 */
public class NBTReader {
    
    /**
     * NBT标签类型枚举
     */
    public enum TagType {
        TAG_End(0),
        TAG_Byte(1),
        TAG_Short(2),
        TAG_Int(3),
        TAG_Long(4),
        TAG_Float(5),
        TAG_Double(6),
        TAG_Byte_Array(7),
        TAG_String(8),
        TAG_List(9),
        TAG_Compound(10),
        TAG_Int_Array(11),
        TAG_Long_Array(12);
        
        private final int id;
        
        TagType(int id) {
            this.id = id;
        }
        
        public int getId() {
            return id;
        }
        
        public static TagType fromId(int id) {
            for (TagType type : values()) {
                if (type.id == id) {
                    return type;
                }
            }
            throw new IllegalArgumentException("Unknown NBT tag type: " + id);
        }
    }
    
    /**
     * NBT标签基类
     */
    public static abstract class NBTTag {
        protected String name;
        
        public NBTTag(String name) {
            this.name = name;
        }
        
        public String getName() {
            return name;
        }
        
        public abstract Object getValue();
        public abstract TagType getType();
    }
    
    /**
     * NBT复合标签，包含多个子标签
     */
    public static class NBTCompound extends NBTTag {
        private Map<String, NBTTag> tags = new HashMap<>();
        
        public NBTCompound(String name) {
            super(name);
        }
        
        public void put(String key, NBTTag tag) {
            tags.put(key, tag);
        }
        
        public NBTTag get(String key) {
            return tags.get(key);
        }
        
        public boolean contains(String key) {
            return tags.containsKey(key);
        }
        
        public Set<String> keySet() {
            return tags.keySet();
        }
        
        @Override
        public Object getValue() {
            return tags;
        }
        
        @Override
        public TagType getType() {
            return TagType.TAG_Compound;
        }
        
        /**
         * 便捷方法：获取整数值
         */
        public int getInt(String key) {
            NBTTag tag = get(key);
            if (tag instanceof NBTInt) {
                return ((NBTInt) tag).getValue();
            }
            throw new IllegalArgumentException("Tag " + key + " is not an integer");
        }
        
        /**
         * 便捷方法：获取字符串值
         */
        public String getString(String key) {
            NBTTag tag = get(key);
            if (tag instanceof NBTString) {
                return ((NBTString) tag).getValue();
            }
            throw new IllegalArgumentException("Tag " + key + " is not a string");
        }
        
        /**
         * 便捷方法：获取复合标签
         */
        public NBTCompound getCompound(String key) {
            NBTTag tag = get(key);
            if (tag instanceof NBTCompound) {
                return (NBTCompound) tag;
            }
            throw new IllegalArgumentException("Tag " + key + " is not a compound");
        }
        
        /**
         * 便捷方法：获取列表标签
         */
        public NBTList getList(String key) {
            NBTTag tag = get(key);
            if (tag instanceof NBTList) {
                return (NBTList) tag;
            }
            throw new IllegalArgumentException("Tag " + key + " is not a list");
        }
    }
    
    /**
     * NBT列表标签
     */
    public static class NBTList extends NBTTag {
        private List<NBTTag> tags = new ArrayList<>();
        private TagType listType;
        
        public NBTList(String name, TagType listType) {
            super(name);
            this.listType = listType;
        }
        
        public void add(NBTTag tag) {
            tags.add(tag);
        }
        
        public NBTTag get(int index) {
            return tags.get(index);
        }
        
        public int size() {
            return tags.size();
        }
        
        public TagType getListType() {
            return listType;
        }
        
        @Override
        public Object getValue() {
            return tags;
        }
        
        @Override
        public TagType getType() {
            return TagType.TAG_List;
        }
    }
    
    /**
     * NBT整数标签
     */
    public static class NBTInt extends NBTTag {
        private int value;
        
        public NBTInt(String name, int value) {
            super(name);
            this.value = value;
        }
        
        @Override
        public Integer getValue() {
            return value;
        }
        
        @Override
        public TagType getType() {
            return TagType.TAG_Int;
        }
    }
    
    /**
     * NBT字符串标签
     */
    public static class NBTString extends NBTTag {
        private String value;
        
        public NBTString(String name, String value) {
            super(name);
            this.value = value;
        }
        
        @Override
        public String getValue() {
            return value;
        }
        
        @Override
        public TagType getType() {
            return TagType.TAG_String;
        }
    }
    
    /**
     * NBT字节数组标签
     */
    public static class NBTByteArray extends NBTTag {
        private byte[] value;
        
        public NBTByteArray(String name, byte[] value) {
            super(name);
            this.value = value;
        }
        
        @Override
        public byte[] getValue() {
            return value;
        }
        
        @Override
        public TagType getType() {
            return TagType.TAG_Byte_Array;
        }
    }
    
    /**
     * NBT整数数组标签
     */
    public static class NBTIntArray extends NBTTag {
        private int[] value;
        
        public NBTIntArray(String name, int[] value) {
            super(name);
            this.value = value;
        }
        
        @Override
        public int[] getValue() {
            return value;
        }
        
        @Override
        public TagType getType() {
            return TagType.TAG_Int_Array;
        }
    }
    
    /**
     * NBT长整数数组标签
     */
    public static class NBTLongArray extends NBTTag {
        private long[] value;
        
        public NBTLongArray(String name, long[] value) {
            super(name);
            this.value = value;
        }
        
        @Override
        public long[] getValue() {
            return value;
        }
        
        @Override
        public TagType getType() {
            return TagType.TAG_Long_Array;
        }
    }
    
    /**
     * NBT字节标签
     */
    public static class NBTByte extends NBTTag {
        private byte value;
        
        public NBTByte(String name, byte value) {
            super(name);
            this.value = value;
        }
        
        @Override
        public Byte getValue() {
            return value;
        }
        
        @Override
        public TagType getType() {
            return TagType.TAG_Byte;
        }
    }
    
    /**
     * NBT短整数标签
     */
    public static class NBTShort extends NBTTag {
        private short value;
        
        public NBTShort(String name, short value) {
            super(name);
            this.value = value;
        }
        
        @Override
        public Short getValue() {
            return value;
        }
        
        @Override
        public TagType getType() {
            return TagType.TAG_Short;
        }
    }
    
    /**
     * NBT长整数标签
     */
    public static class NBTLong extends NBTTag {
        private long value;
        
        public NBTLong(String name, long value) {
            super(name);
            this.value = value;
        }
        
        @Override
        public Long getValue() {
            return value;
        }
        
        @Override
        public TagType getType() {
            return TagType.TAG_Long;
        }
    }
    
    /**
     * NBT浮点数标签
     */
    public static class NBTFloat extends NBTTag {
        private float value;
        
        public NBTFloat(String name, float value) {
            super(name);
            this.value = value;
        }
        
        @Override
        public Float getValue() {
            return value;
        }
        
        @Override
        public TagType getType() {
            return TagType.TAG_Float;
        }
    }
    
    /**
     * NBT双精度浮点数标签
     */
    public static class NBTDouble extends NBTTag {
        private double value;

        public NBTDouble(String name, double value) {
            super(name);
            this.value = value;
        }

        @Override
        public Double getValue() {
            return value;
        }

        @Override
        public TagType getType() {
            return TagType.TAG_Double;
        }
    }

    private DataInputStream input;

    public NBTReader(InputStream input) {
        this.input = new DataInputStream(input);
    }

    /**
     * 从文件读取NBT数据
     */
    public static NBTCompound readFromFile(String filePath) throws IOException {
        // 先读取所有字节到内存，避免mark/reset问题
        byte[] fileData;
        try (FileInputStream fis = new FileInputStream(filePath)) {
            fileData = readAllBytes(fis);
        }

        return readFromBytes(fileData);
    }

    /**
     * Java 8 兼容的 readAllBytes 实现
     */
    private static byte[] readAllBytes(InputStream inputStream) throws IOException {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        int nRead;
        byte[] data = new byte[8192];
        while ((nRead = inputStream.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, nRead);
        }
        return buffer.toByteArray();
    }

    /**
     * 从字节数组读取NBT数据
     */
    public static NBTCompound readFromBytes(byte[] data) throws IOException {
        try (ByteArrayInputStream bis = new ByteArrayInputStream(data)) {
            // 检测压缩格式
            if (data.length >= 2) {
                if (data[0] == (byte) 0x1f && data[1] == (byte) 0x8b) {
                    // GZIP压缩
                    try (GZIPInputStream gis = new GZIPInputStream(bis)) {
                        NBTReader reader = new NBTReader(gis);
                        return reader.readCompound();
                    }
                } else if (data[0] == (byte) 0x78) {
                    // Zlib压缩
                    try (InflaterInputStream iis = new InflaterInputStream(bis)) {
                        NBTReader reader = new NBTReader(iis);
                        return reader.readCompound();
                    }
                }
            }

            // 未压缩
            NBTReader reader = new NBTReader(bis);
            return reader.readCompound();
        }
    }

    /**
     * 读取NBT复合标签
     */
    public NBTCompound readCompound() throws IOException {
        TagType type = TagType.fromId(input.readByte());
        if (type != TagType.TAG_Compound) {
            throw new IOException("Expected compound tag, got " + type);
        }

        String name = readString();
        return readCompoundPayload(name);
    }

    /**
     * 读取复合标签的内容
     */
    private NBTCompound readCompoundPayload(String name) throws IOException {
        NBTCompound compound = new NBTCompound(name);

        while (true) {
            TagType type = TagType.fromId(input.readByte());
            if (type == TagType.TAG_End) {
                break;
            }

            String tagName = readString();
            NBTTag tag = readTagPayload(type, tagName);
            compound.put(tagName, tag);
        }

        return compound;
    }

    /**
     * 读取标签内容
     */
    private NBTTag readTagPayload(TagType type, String name) throws IOException {
        switch (type) {
            case TAG_Byte:
                return new NBTByte(name, input.readByte());
            case TAG_Short:
                return new NBTShort(name, input.readShort());
            case TAG_Int:
                return new NBTInt(name, input.readInt());
            case TAG_Long:
                return new NBTLong(name, input.readLong());
            case TAG_Float:
                return new NBTFloat(name, input.readFloat());
            case TAG_Double:
                return new NBTDouble(name, input.readDouble());
            case TAG_Byte_Array:
                int byteLength = input.readInt();
                byte[] bytes = new byte[byteLength];
                input.readFully(bytes);
                return new NBTByteArray(name, bytes);
            case TAG_String:
                return new NBTString(name, readString());
            case TAG_List:
                return readListPayload(name);
            case TAG_Compound:
                return readCompoundPayload(name);
            case TAG_Int_Array:
                int intLength = input.readInt();
                int[] ints = new int[intLength];
                for (int i = 0; i < intLength; i++) {
                    ints[i] = input.readInt();
                }
                return new NBTIntArray(name, ints);
            case TAG_Long_Array:
                int longLength = input.readInt();
                long[] longs = new long[longLength];
                for (int i = 0; i < longLength; i++) {
                    longs[i] = input.readLong();
                }
                return new NBTLongArray(name, longs);
            default:
                throw new IOException("Unknown tag type: " + type);
        }
    }

    /**
     * 读取列表标签内容
     */
    private NBTList readListPayload(String name) throws IOException {
        TagType listType = TagType.fromId(input.readByte());
        int length = input.readInt();

        NBTList list = new NBTList(name, listType);

        for (int i = 0; i < length; i++) {
            NBTTag tag = readTagPayload(listType, "");
            list.add(tag);
        }

        return list;
    }

    /**
     * 读取字符串
     */
    private String readString() throws IOException {
        int length = input.readUnsignedShort();
        byte[] bytes = new byte[length];
        input.readFully(bytes);
        return new String(bytes, "UTF-8");
    }
}
