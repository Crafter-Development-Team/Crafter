package com.minecraft.selector;

import com.minecraft.selector.nbt.NBTReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NBT读取器测试类
 */
public class NBTReaderTest {
    
    private byte[] testNbtData;
    
    @BeforeEach
    void setUp() throws IOException {
        // 创建测试用的NBT数据
        testNbtData = createTestNbtData();
    }
    
    @Test
    @DisplayName("测试NBT复合标签读取")
    void testReadCompound() throws IOException {
        NBTReader.NBTCompound compound = NBTReader.readFromBytes(testNbtData);
        
        assertNotNull(compound);
        assertEquals("Test", compound.getName());
        assertTrue(compound.contains("testInt"));
        assertTrue(compound.contains("testString"));
    }
    
    @Test
    @DisplayName("测试整数标签读取")
    void testReadInt() throws IOException {
        NBTReader.NBTCompound compound = NBTReader.readFromBytes(testNbtData);
        
        int value = compound.getInt("testInt");
        assertEquals(42, value);
    }
    
    @Test
    @DisplayName("测试字符串标签读取")
    void testReadString() throws IOException {
        NBTReader.NBTCompound compound = NBTReader.readFromBytes(testNbtData);
        
        String value = compound.getString("testString");
        assertEquals("Hello World", value);
    }
    
    @Test
    @DisplayName("测试不存在的标签")
    void testNonExistentTag() throws IOException {
        NBTReader.NBTCompound compound = NBTReader.readFromBytes(testNbtData);
        
        assertFalse(compound.contains("nonExistent"));
        assertThrows(IllegalArgumentException.class, () -> {
            compound.getInt("nonExistent");
        });
    }
    
    /**
     * 创建测试用的NBT数据
     */
    private byte[] createTestNbtData() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        
        // 写入复合标签头
        dos.writeByte(10); // TAG_Compound
        dos.writeUTF("Test"); // 标签名
        
        // 写入整数标签
        dos.writeByte(3); // TAG_Int
        dos.writeUTF("testInt");
        dos.writeInt(42);
        
        // 写入字符串标签
        dos.writeByte(8); // TAG_String
        dos.writeUTF("testString");
        dos.writeUTF("Hello World");
        
        // 写入结束标签
        dos.writeByte(0); // TAG_End
        
        dos.close();
        return baos.toByteArray();
    }
}
