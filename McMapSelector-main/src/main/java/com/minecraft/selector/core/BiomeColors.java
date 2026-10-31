package com.minecraft.selector.core;

import com.fasterxml.jackson.databind.*;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import java.util.zip.*;
import javax.imageio.ImageIO;

/** Resource-driven climate and effects. No biome-ID color or climate tables. */
final class BiomeColors {
    private final Map<String,JsonNode> definitions=new HashMap<>();
    private BufferedImage grass,foliage;
    void merge(BiomeColors other) {definitions.putAll(other.definitions);if(other.grass!=null) grass=other.grass;if(other.foliage!=null) foliage=other.foliage;}
    void read(ZipFile zip) throws IOException {
        Enumeration<? extends ZipEntry> entries=zip.entries();ObjectMapper json=new ObjectMapper()
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_COMMENTS);
        while(entries.hasMoreElements()) {
            ZipEntry e=entries.nextElement();String n=e.getName();
            if(n.matches("data/[^/]+/worldgen/biome/.+\\.json")) {
                String[] parts=n.split("/",5);try(InputStream in=zip.getInputStream(e)) {definitions.put(parts[1]+":"+parts[4].substring(0,parts[4].length()-5),json.readTree(in));}
                catch(IOException error) {throw new IOException("Cannot parse biome "+zip.getName()+"!"+n+": "+error.getMessage(),error);}
            } else if(n.equals("assets/minecraft/textures/colormap/grass.png")||n.equals("assets/minecraft/textures/colormap/foliage.png")) {
                try(InputStream in=zip.getInputStream(e)) {BufferedImage image=ImageIO.read(in);if(n.endsWith("/grass.png")) grass=image;else foliage=image;}
            }
        }
    }
    static Color sample(BufferedImage map,double temperature,double rainfall) {
        if(map==null) return null;
        double t=Math.max(0,Math.min(1,temperature)),r=Math.max(0,Math.min(1,rainfall))*t;
        return new Color(map.getRGB((int)((1-t)*(map.getWidth()-1)),(int)((1-r)*(map.getHeight()-1))),true);
    }
    Color tint(String biome,String block) {
        JsonNode d=definitions.get(biome);if(d==null) return null;
        String effect=PreviewOptions.water(block)?"water_color":block.contains("leaves")||block.contains("vine")?"foliage_color":"grass_color";
        JsonNode value=d.path("effects").get(effect);
        if(value!=null&&value.isIntegralNumber()) return new Color(value.asInt());
        if(effect.equals("water_color")) return null;
        return sample(effect.equals("foliage_color")?foliage:grass,d.path("temperature").asDouble(0.5),d.path("downfall").asDouble(0.5));
    }
    Color apply(Color base,String biome,String block,int mode) {
        if(mode==0||biome==null) return base;
        boolean tinted=PreviewOptions.water(block)||block.contains("grass")||block.contains("leaves")||block.contains("fern")||block.contains("vine");
        Color tint=tint(biome,mode==2?"grass":block);
        if(tint==null||mode==1&&!tinted) return base;
        if(mode==2) return new Color(tint.getRed(),tint.getGreen(),tint.getBlue(),base.getAlpha());
        return new Color(base.getRed()*tint.getRed()/255,base.getGreen()*tint.getGreen()/255,base.getBlue()*tint.getBlue()/255,base.getAlpha());
    }
}
