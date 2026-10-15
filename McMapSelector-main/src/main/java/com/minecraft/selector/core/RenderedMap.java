package com.minecraft.selector.core;

import java.awt.image.BufferedImage;
import java.util.Arrays;

/**
 * A 2D image and the world Y of each rendered column, published as one cache entry.
 * Build pixels/heights before publication; consumers must treat both as read-only.
 * Keeping the pair in one image preserves Swing and PNG APIs without a second cache.
 */
public final class RenderedMap extends BufferedImage {
    public static final int NO_HEIGHT = Integer.MIN_VALUE;
    private String renderKey="synthetic";
    public java.awt.Rectangle loadedPixelBounds() {
        int minX=getWidth(),minZ=getHeight(),maxX=-1,maxZ=-1;
        if(loaded!=null) {
            for(int i=loaded.nextSetBit(0);i>=0;i=loaded.nextSetBit(i+1)) {int x=i%getWidth(),z=i/getWidth();minX=Math.min(minX,x);minZ=Math.min(minZ,z);maxX=Math.max(maxX,x);maxZ=Math.max(maxZ,z);}
        } else for(int z=0;z<getHeight();z++) for(int x=0;x<getWidth();x++) if(getHeightAt(x,z)!=NO_HEIGHT) {minX=Math.min(minX,x);minZ=Math.min(minZ,z);maxX=Math.max(maxX,x);maxZ=Math.max(maxZ,z);}
        return maxX<minX?new java.awt.Rectangle(0,0,getWidth(),getHeight()):new java.awt.Rectangle(minX,minZ,maxX-minX+1,maxZ-minZ+1);
    }
    public String getRenderKey() {return renderKey;}
    void setRenderKey(String key) {renderKey=key;}
    private final int[] heights;
    /** 每像素 1 字节：1 = 该列在"兴趣带"里有方块（画布按屏幕坐标叠紫棋盘）；0 = 无 */
    private final byte[] highlight;
    private int[] blockStates;
    private int[] opaqueHeights,dryHeights,biomeIds;
    private java.util.BitSet loaded;
    private final java.util.List<String> biomes=new java.util.ArrayList<>();
    private final java.util.Map<String,Integer> biomeIndex=new java.util.HashMap<>();
    public boolean isLoadedAt(int x,int z) {return x>=0&&z>=0&&x<getWidth()&&z<getHeight()&&(loaded==null?getHeightAt(x,z)!=NO_HEIGHT:loaded.get(z*getWidth()+x));}
    public String getBiomeAt(int x,int z) {if(biomeIds==null||x<0||z<0||x>=getWidth()||z>=getHeight()) return null;int id=biomeIds[z*getWidth()+x];return id==0?null:biomes.get(id-1);}
    public static String biomeAt(BufferedImage image,int x,int z) {return image instanceof RenderedMap?((RenderedMap)image).getBiomeAt(x,z):null;}
    public int getFitHeightAt(int x,int z,boolean opaque) {
        if(x<0||z<0||x>=getWidth()||z>=getHeight()) return NO_HEIGHT;
        int[] a=opaque?opaqueHeights:dryHeights;return a==null?getHeightAt(x,z):a[z*getWidth()+x];
    }
    void setColumnMetadata(int x,int z,String biome,int opaque,int dry) {
        int i=z*getWidth()+x;
        if(loaded==null) loaded=new java.util.BitSet(heights.length);loaded.set(i);
        if(opaqueHeights==null) {opaqueHeights=new int[heights.length];dryHeights=new int[heights.length];Arrays.fill(opaqueHeights,NO_HEIGHT);Arrays.fill(dryHeights,NO_HEIGHT);}
        opaqueHeights[i]=opaque;dryHeights[i]=dry;
        if(biome!=null) {
            if(biomeIds==null) biomeIds=new int[heights.length];
            Integer id=biomeIndex.get(biome);if(id==null) {id=biomes.size()+1;biomes.add(biome);biomeIndex.put(biome,id);}biomeIds[i]=id;
        } else if(biomeIds!=null) biomeIds[i]=0;
    }

    private final java.util.List<String> palette = new java.util.ArrayList<>();
    private final java.util.Map<String,Integer> paletteIndex = new java.util.HashMap<>();

    public String getBlockAt(int x,int z) {
        if(blockStates==null||x<0||z<0||x>=getWidth()||z>=getHeight()) return null;
        int id=blockStates[z*getWidth()+x];return id==0?null:palette.get(id-1);
    }
    void setBlockAt(int x,int z,String state) {
        if(state==null) return;
        if(blockStates==null) blockStates=new int[heights.length];
        Integer id=paletteIndex.get(state);
        if(id==null) {id=palette.size()+1;palette.add(state);paletteIndex.put(state,id);}
        blockStates[z*getWidth()+x]=id;
    }
    public static String blockAt(BufferedImage image,int x,int z) {return image instanceof RenderedMap?((RenderedMap)image).getBlockAt(x,z):null;}


    public RenderedMap(int width, int height) {
        super(width, height, BufferedImage.TYPE_INT_ARGB);
        heights = new int[Math.multiplyExact(width, height)];
        Arrays.fill(heights, NO_HEIGHT);
        highlight = new byte[heights.length];
    }

    public int getHeightAt(int x, int z) {
        if (x < 0 || z < 0 || x >= getWidth() || z >= getHeight()) return NO_HEIGHT;
        return heights[z * getWidth() + x];
    }

    void setHeightAt(int x, int z, int height) {
        heights[z * getWidth() + x] = height;
    }

    /** 供增量裁剪就地改写高度（GUI 的 Y 带实时路径用）。 */
    public void putHeightAt(int x, int z, int height) {
        setHeightAt(x, z, height);
    }

    /** 一次性写入整幅高度（长度必须等于 width*height，行优先）。 */
    public void putHeights(int[] source) {
        if (source.length != heights.length) throw new IllegalArgumentException("heights length mismatch");
        System.arraycopy(source, 0, heights, 0, heights.length);
    }

    /** Keep selection data consistent with the cloud's temporary lower-band cut. */
    public void clipSelectionMetadataBelow(int bottom) {
        for(int i=0;i<heights.length;i++) {
            if(opaqueHeights!=null && opaqueHeights[i]<bottom) opaqueHeights[i]=NO_HEIGHT;
            if(dryHeights!=null && dryHeights[i]<bottom) dryHeights[i]=NO_HEIGHT;
            if(heights[i]==NO_HEIGHT && blockStates!=null) blockStates[i]=0;
        }
    }

    public boolean isHighlighted(int x, int z) {
        if (x < 0 || z < 0 || x >= getWidth() || z >= getHeight()) return false;
        return highlight[z * getWidth() + x] != 0;
    }

    public void setHighlighted(int x, int z, boolean value) {
        if (x < 0 || z < 0 || x >= getWidth() || z >= getHeight()) return;
        highlight[z * getWidth() + x] = (byte) (value ? 1 : 0);
    }

    /** 一次性写入整幅兴趣带标记（可传 null 表示清空）。 */
    public void putHighlights(byte[] source) {
        if (source == null) {
            Arrays.fill(highlight, (byte) 0);
            return;
        }
        if (source.length != highlight.length) throw new IllegalArgumentException("highlight length mismatch");
        System.arraycopy(source, 0, highlight, 0, highlight.length);
    }

    /** 合成大图时把兴趣带标记一起搬过去。 */
    public void copyHighlightsFrom(BufferedImage source, int offsetX, int offsetZ) {
        if (!(source instanceof RenderedMap)) return;
        RenderedMap map = (RenderedMap) source;
        int startX = Math.max(0, -offsetX);
        int endX = Math.min(source.getWidth(), getWidth() - offsetX);
        if (startX >= endX) return;
        for (int z = Math.max(0, -offsetZ); z < Math.min(source.getHeight(), getHeight() - offsetZ); z++) {
            System.arraycopy(map.highlight, z * map.getWidth() + startX,
                highlight, (z + offsetZ) * getWidth() + offsetX + startX, endX - startX);
        }
    }

    public static int heightAt(BufferedImage image, int x, int z) {
        return image instanceof RenderedMap ? ((RenderedMap) image).getHeightAt(x, z) : NO_HEIGHT;
    }

    /** Copy metadata while composing images, before publishing the destination. */
    public void copyHeightsFrom(BufferedImage source, int offsetX, int offsetZ) {
        if (!(source instanceof RenderedMap)) return;
        RenderedMap map = (RenderedMap) source;
        renderKey=map.renderKey;
        // Translate the source palette once, then copy indices without per-pixel strings.
        int[] translation=null;
        if(map.blockStates!=null) {
            if(blockStates==null) blockStates=new int[heights.length];
            translation=new int[map.palette.size()+1];
            for(int i=0;i<map.palette.size();i++) {
                String state=map.palette.get(i);Integer id=paletteIndex.get(state);
                if(id==null) {id=palette.size()+1;palette.add(state);paletteIndex.put(state,id);}
                translation[i+1]=id;
            }
        }
        int startX = Math.max(0, -offsetX);
        int endX = Math.min(source.getWidth(), getWidth() - offsetX);
        if (startX >= endX) return;
        for (int z = Math.max(0, -offsetZ); z < Math.min(source.getHeight(), getHeight() - offsetZ); z++) {
            System.arraycopy(map.heights, z * map.getWidth() + startX,
                heights, (z + offsetZ) * getWidth() + offsetX + startX, endX - startX);
            if(translation!=null) for(int x=startX;x<endX;x++) blockStates[(z+offsetZ)*getWidth()+x+offsetX]=translation[map.blockStates[z*map.getWidth()+x]];
            else if(blockStates!=null) Arrays.fill(blockStates,(z+offsetZ)*getWidth()+offsetX+startX,(z+offsetZ)*getWidth()+offsetX+endX,0);
            for(int x=startX;x<endX;x++) {
                int dx=x+offsetX,dz=z+offsetZ;
                if(map.isLoadedAt(x,z)) setColumnMetadata(dx,dz,map.getBiomeAt(x,z),map.getFitHeightAt(x,z,true),map.getFitHeightAt(x,z,false));
                else {int i=dz*getWidth()+dx;if(loaded!=null) loaded.clear(i);if(biomeIds!=null) biomeIds[i]=0;if(opaqueHeights!=null) {opaqueHeights[i]=NO_HEIGHT;dryHeights[i]=NO_HEIGHT;}}
            }
        }
    }
}
