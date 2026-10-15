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
        Color decorated=decoration(id,entity,palette);if(decorated!=null)return decorated;
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
        } else {
            collect(entity,palette,colors,0,new int[]{0});
        }
        if(colors.isEmpty()) return show && entity.contains("Blocks")?new Color(0,0,0,0):null;
        long r=0,g=0,b=0,a=0;
        for(Color c:colors) {r+=(long)c.getRed()*c.getAlpha();g+=(long)c.getGreen()*c.getAlpha();b+=(long)c.getBlue()*c.getAlpha();a+=c.getAlpha();}
        if(a==0) return new Color(0,0,0,0);
        // Union is approximated by summed projected areas. Positions/rotation/tile boxes are not rasterized.
        return new Color((int)(r/a),(int)(g/a),(int)(b/a),show?(int)Math.round(Math.min(1,projectedCoverage)*255):255);
    }
    // Vanilla dye metadata names only. All RGB values are sampled through jar/pack palettes.
    private static final String[] DYES={"white","orange","magenta","light_blue","yellow","lime","pink","gray","light_gray","cyan","purple","blue","brown","green","red","black"};
    private static String dye(NBTTag tag,boolean legacyBanner){if(tag instanceof NBTString){String name=((NBTString)tag).getValue().replace("minecraft:","");for(String dye:DYES)if(dye.equals(name))return name;}else if(tag!=null&&tag.getValue() instanceof Number){int index=((Number)tag.getValue()).intValue();if(index>=0&&index<16)return DYES[legacyBanner?15-index:index];}return null;}
    private static Color sampled(String dye,BlockColors.Palette palette){if(dye==null)return null;BlockColors.Appearance a=palette.appearance("minecraft:"+dye+"_wool",Collections.emptyMap());return "unknown".equals(a.source)?null:a.color;}
    private static Color decoration(String id,NBTCompound entity,BlockColors.Palette palette){String path=id.contains(":")?id.substring(id.indexOf(':')+1):id;
        boolean banner=path.equals("banner")||path.endsWith("_banner"),bed=path.equals("bed")||path.endsWith("_bed"),shulker=path.equals("shulker_box")||path.endsWith("_shulker_box");if(!banner&&!bed&&!shulker)return null;
        String base=dye(entity.get("color"),false);if(base==null)base=dye(entity.get("Color"),false);if(base==null&&banner)base=dye(entity.get("Base"),true);
        if(base==null)for(String name:DYES)if(path.startsWith(name+"_")){base=name;break;}
        Color color=sampled(base,palette);long r=0,g=0,b=0,weight=0;if(color!=null){r=color.getRed()*3L;g=color.getGreen()*3L;b=color.getBlue()*3L;weight=3;}
        if(banner){NBTTag patterns=entity.get("patterns");if(!(patterns instanceof NBTList))patterns=entity.get("Patterns");if(patterns instanceof NBTList){NBTList list=(NBTList)patterns;for(int i=0;i<Math.min(64,list.size());i++)if(list.get(i) instanceof NBTCompound){NBTCompound pattern=(NBTCompound)list.get(i);String name=dye(pattern.get("color"),false);if(name==null)name=dye(pattern.get("Color"),true);Color c=sampled(name,palette);if(c!=null){r+=c.getRed();g+=c.getGreen();b+=c.getBlue();weight++;}}}}
        return weight==0?null:new Color((int)(r/weight),(int)(g/weight),(int)(b/weight));
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
