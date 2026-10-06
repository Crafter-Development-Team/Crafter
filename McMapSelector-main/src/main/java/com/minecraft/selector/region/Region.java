package com.minecraft.selector.region;

import com.minecraft.selector.nbt.NBTReader;
import java.io.*;
import java.util.zip.InflaterInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 表示Minecraft区域文件(.mca)
 * 对应Python anvil库中的Region类
 */
public class Region {
    private byte[] data;
    
    public Region(byte[] data) {
        this.data = data;
    }
    
    /**
     * 从文件创建Region对象
     */
    public static Region fromFile(String filePath) throws IOException {
        try (FileInputStream fis = new FileInputStream(filePath)) {
            byte[] data = readAllBytes(fis);
            if (data.length < 8192) {
                throw new IOException("Truncated MCA header (" + data.length + " bytes): " + filePath);
            }
            return new Region(data);
        }
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
     * 计算区块在头部的偏移量
     */
    public static int headerOffset(int chunkX, int chunkZ) {
        return 4 * ((chunkX % 32) + (chunkZ % 32) * 32);
    }
    
    /**
     * 获取区块位置信息
     * 返回数组：[偏移量(4KB扇区), 长度(4KB扇区)]
     */
    public int[] getChunkLocation(int chunkX, int chunkZ) {
        int byteOffset = headerOffset(chunkX, chunkZ);
        
        // 读取3字节偏移量和1字节长度
        int offset = ((data[byteOffset] & 0xFF) << 16) |
                    ((data[byteOffset + 1] & 0xFF) << 8) |
                    (data[byteOffset + 2] & 0xFF);
        int sectors = data[byteOffset + 3] & 0xFF;
        
        return new int[]{offset, sectors};
    }
    
    /**
     * 检查区块是否存在
     */
    public boolean chunkExists(int chunkX, int chunkZ) {
        int[] location = getChunkLocation(chunkX, chunkZ);
        return location[0] != 0 && location[1] != 0;
    }
    
    /**
     * 获取区块的NBT数据
     */
    public NBTReader.NBTCompound getChunkData(int chunkX, int chunkZ) throws IOException {
        int[] location = getChunkLocation(chunkX, chunkZ);
        
        // 如果区块不存在
        if (location[0] == 0 && location[1] == 0) {
            return null;
        }
        
        int offset = location[0] * 4096; // 转换为字节偏移
        
        // 读取区块数据长度（4字节，大端序）
        int length = ByteBuffer.wrap(data, offset, 4).order(ByteOrder.BIG_ENDIAN).getInt();
        
        // 读取压缩类型（1字节）
        int compression = data[offset + 4] & 0xFF;
        
        if (compression == 1) {
            throw new IOException("GZip compression is not supported");
        } else if (compression != 2) {
            throw new IOException("Unknown compression type: " + compression);
        }
        
        // 读取压缩数据
        byte[] compressedData = new byte[length - 1];
        System.arraycopy(data, offset + 5, compressedData, 0, length - 1);
        
        // 解压缩数据
        try (ByteArrayInputStream bis = new ByteArrayInputStream(compressedData);
             InflaterInputStream iis = new InflaterInputStream(bis)) {

            return NBTReader.readFromBytes(readAllBytes(iis));
        }
    }
    
    /**
     * 获取区块对象
     */
    public Chunk getChunk(int chunkX, int chunkZ) throws IOException {
        NBTReader.NBTCompound chunkData = getChunkData(chunkX, chunkZ);
        if (chunkData == null) {
            return null;
        }
        return new Chunk(chunkData);
    }
    
    /**
     * 获取所有存在的区块坐标
     */
    public java.util.List<int[]> getChunkCoordinates() {
        java.util.List<int[]> coordinates = new java.util.ArrayList<>();
        
        for (int x = 0; x < 32; x++) {
            for (int z = 0; z < 32; z++) {
                if (chunkExists(x, z)) {
                    coordinates.add(new int[]{x, z});
                }
            }
        }
        
        return coordinates;
    }
    
    /**
     * 获取区域文件的原始数据
     */
    public byte[] getData() {
        return data.clone();
    }
    
    /**
     * 获取区域文件大小
     */
    public int getSize() {
        return data.length;
    }
}
