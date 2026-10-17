package com.minecraft.selector.core;
import com.minecraft.selector.nbt.NBTReader;
import com.minecraft.selector.nbt.NBTReader.*;
import java.io.File;
import java.io.IOException;
import java.awt.Point;
/**
 * 读取世界出生点/玩家锚点。兼容旧 level.dat（SpawnX/Z + Player）与
 * Minecraft 26.1+（spawn 复合标签 + singleplayer_uuid → players/data/<uuid>.dat）。
 */
public final class WorldAnchors {
    public static final class Anchor {
        public final Point xz; public final String dimension;
        public Anchor(int x,int z,String dimension) {xz=new Point(x,z);this.dimension=dimension;}
    }
    public final Anchor spawn,player; public final String worldName;public final String versionName;public final Integer dataVersion;
    private WorldAnchors(Anchor spawn,Anchor player,String name,Integer version,String versionName) {this.spawn=spawn;this.player=player;worldName=name;dataVersion=version;this.versionName=versionName;}
    public static WorldAnchors empty() {return new WorldAnchors(null,null,null,null,null);}
    public static WorldAnchors read(File world) throws IOException {
        NBTCompound root = NBTReader.readFromFile(new File(world,"level.dat").getPath());
        WorldAnchors parsed = parse(root);
        if (parsed.player != null) return parsed;
        Anchor fromUuid = readPlayerBySingleplayerUuid(world, dataCompound(root));
        if (fromUuid == null) return parsed;
        return new WorldAnchors(parsed.spawn, fromUuid, parsed.worldName, parsed.dataVersion, parsed.versionName);
    }
    public static WorldAnchors parse(NBTCompound root) {
        NBTCompound d=dataCompound(root);
        Anchor spawn=readSpawn(d),player=null;
        if(d.get("Player") instanceof NBTCompound) player=anchorFromPlayerCompound(d.getCompound("Player"));
        return new WorldAnchors(spawn,player,d.get("LevelName") instanceof NBTString?d.getString("LevelName"):null,d.get("DataVersion") instanceof NBTInt?d.getInt("DataVersion"):null,d.get("Version") instanceof NBTCompound&&d.getCompound("Version").get("Name") instanceof NBTString?d.getCompound("Version").getString("Name"):null);
    }
    private static NBTCompound dataCompound(NBTCompound root) {
        return root.get("Data") instanceof NBTCompound ? root.getCompound("Data") : root;
    }
    /** Legacy SpawnX/Z, or 26.1+ spawn{pos,dimension}. */
    private static Anchor readSpawn(NBTCompound d) {
        if(d.get("SpawnX") instanceof NBTInt && d.get("SpawnZ") instanceof NBTInt)
            return new Anchor(d.getInt("SpawnX"),d.getInt("SpawnZ"),"minecraft:overworld");
        if(d.get("spawn") instanceof NBTCompound) {
            NBTCompound spawn=d.getCompound("spawn");
            int[] pos=intArray(spawn.get("pos"));
            if(pos!=null && pos.length>=3)
                return new Anchor(pos[0],pos[2],dimension(spawn.get("dimension")));
        }
        return null;
    }
    private static Anchor anchorFromPlayerCompound(NBTCompound p) {
        if(!(p.get("Pos") instanceof NBTList)) return null;
        NBTList pos=p.getList("Pos");
        if(pos.size()<3 || !(pos.get(0).getValue() instanceof Number) || !(pos.get(2).getValue() instanceof Number)) return null;
        double x=((Number)pos.get(0).getValue()).doubleValue(),z=((Number)pos.get(2).getValue()).doubleValue();
        if(!Double.isFinite(x)||!Double.isFinite(z)||x<Integer.MIN_VALUE||x>Integer.MAX_VALUE||z<Integer.MIN_VALUE||z>Integer.MAX_VALUE) return null;
        return new Anchor((int)Math.floor(x),(int)Math.floor(z),dimension(p.get("Dimension")));
    }
    private static Anchor readPlayerBySingleplayerUuid(File world, NBTCompound data) {
        int[] uuid=intArray(data.get("singleplayer_uuid"));
        if(uuid==null || uuid.length!=4) return null;
        String dashed=uuidToDashed(uuid);
        String undashed=dashed.replace("-","");
        File[] candidates=new File[]{
            new File(world,"players/data/"+dashed+".dat"),
            new File(world,"players/data/"+undashed+".dat"),
            new File(world,"playerdata/"+dashed+".dat"),
            new File(world,"playerdata/"+undashed+".dat")
        };
        for(File file:candidates) {
            if(!file.isFile()) continue;
            try {
                NBTCompound root=NBTReader.readFromFile(file.getPath());
                // player .dat root is the player compound itself (no Data wrapper)
                NBTCompound player=root.get("Pos")!=null?root:(root.get("Data") instanceof NBTCompound?root.getCompound("Data"):root);
                Anchor a=anchorFromPlayerCompound(player);
                if(a!=null) return a;
            } catch(Exception ignored) {}
        }
        return null;
    }
    private static int[] intArray(NBTTag tag) {
        if(tag instanceof NBTIntArray) return ((NBTIntArray)tag).getValue();
        return null;
    }
    /** UUID most/least from Minecraft IntArray[4] → dashed string. */
    static String uuidToDashed(int[] parts) {
        long msb=((long)parts[0]<<32)|(parts[1]&0xffffffffL);
        long lsb=((long)parts[2]<<32)|(parts[3]&0xffffffffL);
        return String.format("%08x-%04x-%04x-%04x-%012x",
            (msb>>>32)&0xffffffffL,(msb>>>16)&0xffffL,msb&0xffffL,
            (lsb>>>48)&0xffffL,lsb&0xffffffffffffL);
    }
    private static String dimension(NBTTag tag) {
        if(tag instanceof NBTString) return ((NBTString)tag).getValue();
        if(tag!=null && tag.getValue() instanceof Number) {int n=((Number)tag.getValue()).intValue();return n==-1?"minecraft:the_nether":n==1?"minecraft:the_end":"minecraft:overworld";}
        return "minecraft:overworld";
    }
}
