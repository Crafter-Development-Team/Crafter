package com.minecraft.selector.core;

import com.minecraft.selector.nbt.NBTReader.*;
import com.minecraft.selector.region.Block;
import java.awt.Color;
import java.util.*;

/** Dynamic material references; no mod runtime or GPU is required. */
final class BlockEntityColors {
    static Color resolve(String id,NBTCompound entity,BlockColors.Palette palette) {
        if(entity==null) return null;
        List<Color> colors=new ArrayList<>();
        boolean show=id.equals("yuushya:showblock");
        double projectedCoverage=0;
        if(id.equals("yuushya:showblock") && entity.contains("Blocks")) {
            NBTList blocks=entity.getList("Blocks");
            for(int i=0;i<blocks.size();i++) {
                if(!(blocks.get(i) instanceof NBTCompound)) continue;
                NBTCompound item=(NBTCompound)blocks.get(i);
                if(item.contains("isShown") && ((Number)item.get("isShown").getValue()).intValue()==0) continue;
                if(item.contains("BlockState")) {
                    Block block=Block.fromPalette(item.getCompound("BlockState"));
                    Color c=palette.appearance(block.getId(),block.getProperties()).color;
                    double scale=1;
                    if(item.contains("ShowScales")) {
                        NBTList scales=item.getList("ShowScales");
                        if(scales.size()>=3) scale=Math.abs(((Number)scales.get(0).getValue()).doubleValue()*((Number)scales.get(2).getValue()).doubleValue());
                    }
                    double coverage=Math.min(1,c.getAlpha()/255.0*scale);
                    if(coverage>0) {colors.add(new Color(c.getRed(),c.getGreen(),c.getBlue(),Math.max(1,(int)Math.round(coverage*255))));projectedCoverage+=coverage;}
                }
            }
        } else if(id.startsWith("littletiles:") || id.startsWith("framedblocks:")) {
            collect(entity,palette,colors,0,new int[]{0});
        }
        if(colors.isEmpty()) return show && entity.contains("Blocks")?new Color(0,0,0,0):null;
        long r=0,g=0,b=0,a=0;
        for(Color c:colors) {r+=(long)c.getRed()*c.getAlpha();g+=(long)c.getGreen()*c.getAlpha();b+=(long)c.getBlue()*c.getAlpha();a+=c.getAlpha();}
        if(a==0) return new Color(0,0,0,0);
        // Union is approximated by summed projected areas. Positions/rotation/tile boxes are not rasterized.
        return new Color((int)(r/a),(int)(g/a),(int)(b/a),show?(int)Math.round(Math.min(1,projectedCoverage)*255):255);
    }
    private static void collect(NBTTag tag,BlockColors.Palette palette,List<Color> colors,int depth,int[] visited) {
        if(depth>12 || ++visited[0]>10000) return;
        if(tag instanceof NBTCompound) {
            NBTCompound c=(NBTCompound)tag;
            if(c.contains("Name") && c.get("Name") instanceof NBTString) {
                Block block=Block.fromPalette(c);colors.add(palette.appearance(block.getId(),block.getProperties()).color);return;
            }
            for(String key:c.keySet()) {
                NBTTag child=c.get(key);
                if(child instanceof NBTString && Arrays.asList("block","Block","blockName","blockname","b","state","material").contains(key)) {
                    String value=((NBTString)child).getValue();
                    String name=value.split("\\[",2)[0];
                    if(name.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) colors.add(palette.getBlockColor(name));
                } else collect(child,palette,colors,depth+1,visited);
            }
        } else if(tag instanceof NBTList) {
            NBTList l=(NBTList)tag;for(int i=0;i<l.size();i++) collect(l.get(i),palette,colors,depth+1,visited);
        }
    }
}
