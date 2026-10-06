package com.minecraft.selector.region;

import com.minecraft.selector.nbt.NBTReader;
import java.util.*;

/**
 * 表示Minecraft区块
 * 对应Python anvil库中的Chunk类
 */
public class Chunk {
    private NBTReader.NBTCompound nbtData;
    private int x;
    private int z;
    private List<Section> sections;
    
    public Chunk(NBTReader.NBTCompound nbtData) {
        this.nbtData = nbtData;

        // 1.17 及更早的存档会把区块数据包在 "Level" 里
        NBTReader.NBTCompound chunkData = nbtData;
        if (!nbtData.contains("sections") && !nbtData.contains("Sections") && nbtData.contains("Level")) {
            NBTReader.NBTCompound level = nbtData.getCompound("Level");
            if (level.contains("xPos")) {
                chunkData = level;
            }
        }

        this.x = chunkData.getInt("xPos");
        this.z = chunkData.getInt("zPos");
        this.sections = new ArrayList<>();
        
        // 解析区段数据（1.18+ 用小写 sections，1.17- 用大写 Sections）
        NBTReader.NBTList sectionsList = null;
        if (chunkData.contains("sections")) {
            sectionsList = chunkData.getList("sections");
        } else if (chunkData.contains("Sections")) {
            sectionsList = chunkData.getList("Sections");
        }
        if (sectionsList != null) {
            for (int i = 0; i < sectionsList.size(); i++) {
                NBTReader.NBTCompound sectionData = (NBTReader.NBTCompound) sectionsList.get(i);
                sections.add(new Section(sectionData));
            }
        }
    }
    
    /**
     * 从区域文件创建区块
     */
    public static Chunk fromRegion(Region region, int chunkX, int chunkZ) throws Exception {
        NBTReader.NBTCompound chunkData = region.getChunkData(chunkX, chunkZ);
        if (chunkData == null) {
            return null;
        }
        return new Chunk(chunkData);
    }
    
    /**
     * 获取区块X坐标
     */
    public int getX() {
        return x;
    }
    
    /**
     * 获取区块Z坐标
     */
    public int getZ() {
        return z;
    }
    
    /**
     * 获取指定Y层的区段
     */
    public Section getSection(int y) {
        for (Section section : sections) {
            if (section.getY() == y) {
                return section;
            }
        }
        return null;
    }
    
    /**
     * 获取所有区段
     */
    public List<Section> getSections() {
        return new ArrayList<>(sections);
    }
    
    /**
     * 检查是否有区段数据
     */
    public boolean hasSections() {
        return !sections.isEmpty();
    }
    
    /**
     * 获取指定坐标的方块
     */
    public Block getBlock(int x, int y, int z) {
        // 计算区段Y坐标
        int sectionY = y >> 4; // 等同于 y / 16
        
        Section section = getSection(sectionY);
        if (section == null) {
            return new Block("air"); // 如果区段不存在，返回空气
        }
        
        return section.getBlock(x, y & 15, z); // y & 15 等同于 y % 16
    }
    
    /**
     * 获取原始NBT数据
     */
    public NBTReader.NBTCompound getNbtData() {
        return nbtData;
    }
    
    /**
     * 区段类，表示16x16x16的方块区域
     */
    public static class Section {
        private int y;
        private List<Block> palette;
        private long[] blockStates;
        private int bitsPerBlock;
        
        public Section(NBTReader.NBTCompound sectionData) {
            this.y = ((NBTReader.NBTByte) sectionData.get("Y")).getValue();
            
            if (sectionData.contains("block_states")) {
                NBTReader.NBTCompound blockStates = sectionData.getCompound("block_states");
                
                // 读取调色板
                this.palette = new ArrayList<>();
                if (blockStates.contains("palette")) {
                    NBTReader.NBTList paletteList = blockStates.getList("palette");
                    for (int i = 0; i < paletteList.size(); i++) {
                        NBTReader.NBTCompound paletteEntry = (NBTReader.NBTCompound) paletteList.get(i);
                        this.palette.add(Block.fromPalette(paletteEntry));
                    }
                }
                
                // 读取方块状态数据
                if (blockStates.contains("data")) {
                    NBTReader.NBTLongArray dataArray = (NBTReader.NBTLongArray) blockStates.get("data");
                    this.blockStates = dataArray.getValue();
                    
                    // 计算每个方块的位数
                    if (palette.size() <= 1) {
                        this.bitsPerBlock = 0;
                    } else {
                        this.bitsPerBlock = Math.max(4, Integer.SIZE - Integer.numberOfLeadingZeros(palette.size() - 1));
                    }
                } else {
                    this.blockStates = new long[0];
                    this.bitsPerBlock = 0;
                }
            } else if (sectionData.contains("Palette") && sectionData.contains("BlockStates")) {
                // 1.17 及更早的旧格式：Palette + BlockStates
                this.palette = new ArrayList<>();
                NBTReader.NBTList paletteList = sectionData.getList("Palette");
                for (int i = 0; i < paletteList.size(); i++) {
                    NBTReader.NBTCompound paletteEntry = (NBTReader.NBTCompound) paletteList.get(i);
                    this.palette.add(Block.fromPalette(paletteEntry));
                }
                NBTReader.NBTLongArray dataArray = (NBTReader.NBTLongArray) sectionData.get("BlockStates");
                this.blockStates = dataArray.getValue();
                if (palette.size() <= 1) {
                    this.bitsPerBlock = 0;
                } else {
                    this.bitsPerBlock = Math.max(4, Integer.SIZE - Integer.numberOfLeadingZeros(palette.size() - 1));
                }
            } else {
                this.palette = Arrays.asList(new Block("air"));
                this.blockStates = new long[0];
                this.bitsPerBlock = 0;
            }
        }
        
        /**
         * 获取区段Y坐标
         */
        public int getY() {
            return y;
        }
        
        /**
         * 获取调色板
         */
        public List<Block> getPalette() {
            return new ArrayList<>(palette);
        }
        
        /**
         * 获取指定坐标的方块
         */
        public Block getBlock(int x, int y, int z) {
            if (palette.isEmpty() || bitsPerBlock == 0) {
                return palette.isEmpty() ? new Block("air") : palette.get(0);
            }

            // 计算方块在区段中的索引
            int blockIndex = y * 256 + z * 16 + x; // 16*16*y + 16*z + x

            // 从压缩数据中提取方块状态索引
            int paletteIndex = extractBlockState(blockIndex);



            if (paletteIndex >= 0 && paletteIndex < palette.size()) {
                return palette.get(paletteIndex);
            }

            return new Block("air");
        }
        
        /**
         * 从压缩的方块状态数据中提取指定索引的方块状态
         * 完全按照Python anvil库的实现逻辑
         */
        private int extractBlockState(int blockIndex) {
            if (bitsPerBlock == 0 || blockStates.length == 0) {
                return 0;
            }

            // 对应Python: state = index // (64 // bits)
            int blocksPerLong = 64 / bitsPerBlock;
            int longIndex = blockIndex / blocksPerLong;

            if (longIndex >= blockStates.length) {
                return 0;
            }

            // 对应Python: data = states[state]
            long data = blockStates[longIndex];

            // 对应Python: if data < 0: data += 2**64
            // Java的long已经是64位有符号，但我们需要处理为无符号
            // 实际上Java的>>>运算符已经处理了这个问题

            // 对应Python: shifted_data = data >> (index % (64 // bits) * bits)
            int offsetInLong = (blockIndex % blocksPerLong) * bitsPerBlock;
            long shiftedData = data >>> offsetInLong;

            // 对应Python: palette_id = shifted_data & 2**bits - 1
            long mask = (1L << bitsPerBlock) - 1;
            return (int) (shiftedData & mask);
        }
    }
}
