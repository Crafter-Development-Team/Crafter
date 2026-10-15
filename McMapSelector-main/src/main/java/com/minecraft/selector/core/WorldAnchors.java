package com.minecraft.selector.core;
import com.minecraft.selector.nbt.NBTReader;
import com.minecraft.selector.nbt.NBTReader.*;
import java.io.File;
import java.io.IOException;
import java.awt.Point;
public final class WorldAnchors {
    public static final class Anchor {
        public final Point xz; public final String dimension;
        public Anchor(int x,int z,String dimension) {xz=new Point(x,z);this.dimension=dimension;}
    }
    public final Anchor spawn,player; public final String worldName;public final String versionName;public final Integer dataVersion;
    private WorldAnchors(Anchor spawn,Anchor player,String name,Integer version,String versionName) {this.spawn=spawn;this.player=player;worldName=name;dataVersion=version;this.versionName=versionName;}
    public static WorldAnchors empty() {return new WorldAnchors(null,null,null,null,null);}
    public static WorldAnchors read(File world) throws IOException {return parse(NBTReader.readFromFile(new File(world,"level.dat").getPath()));}
    public static WorldAnchors parse(NBTCompound root) {
        NBTCompound d=root.get("Data") instanceof NBTCompound?root.getCompound("Data"):root;
        Anchor spawn=null,player=null;
        if(d.get("SpawnX") instanceof NBTInt && d.get("SpawnZ") instanceof NBTInt) spawn=new Anchor(d.getInt("SpawnX"),d.getInt("SpawnZ"),"minecraft:overworld");
        if(d.get("Player") instanceof NBTCompound) {
            NBTCompound p=d.getCompound("Player");
            if(p.get("Pos") instanceof NBTList) {
                NBTList pos=p.getList("Pos");
                if(pos.size()>=3 && pos.get(0).getValue() instanceof Number && pos.get(2).getValue() instanceof Number) {
                    double x=((Number)pos.get(0).getValue()).doubleValue(),z=((Number)pos.get(2).getValue()).doubleValue();
                    if(Double.isFinite(x)&&Double.isFinite(z)&&x>=Integer.MIN_VALUE&&x<=Integer.MAX_VALUE&&z>=Integer.MIN_VALUE&&z<=Integer.MAX_VALUE)
                        player=new Anchor((int)Math.floor(x),(int)Math.floor(z),dimension(p.get("Dimension")));
                }
            }
        }
        return new WorldAnchors(spawn,player,d.get("LevelName") instanceof NBTString?d.getString("LevelName"):null,d.get("DataVersion") instanceof NBTInt?d.getInt("DataVersion"):null,d.get("Version") instanceof NBTCompound&&d.getCompound("Version").get("Name") instanceof NBTString?d.getCompound("Version").getString("Name"):null);
    }
    private static String dimension(NBTTag tag) {
        if(tag instanceof NBTString) return ((NBTString)tag).getValue();
        if(tag!=null && tag.getValue() instanceof Number) {int n=((Number)tag.getValue()).intValue();return n==-1?"minecraft:the_nether":n==1?"minecraft:the_end":"minecraft:overworld";}
        return "minecraft:overworld";
    }
}
