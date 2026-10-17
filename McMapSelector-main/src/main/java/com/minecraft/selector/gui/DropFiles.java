package com.minecraft.selector.gui;
import com.minecraft.selector.utils.WorldPathResolver;
import java.io.*;
import java.util.Locale;
/** Classification is independent of native drag/drop and can be tested headlessly. */
public final class DropFiles {
    public enum Kind { WORLD,PRESET,PACK }
    public static Kind classify(File f)throws IOException {
        if(f.isDirectory()) {
            if(new File(f,"level.dat").isFile()
                || new File(f,"region").isDirectory()
                || new File(f,"dimensions").isDirectory()
                || WorldPathResolver.isValidMinecraftSave(f.getAbsolutePath()))
                return Kind.WORLD;
        }
        if(!f.isFile())throw new IOException("Drop an existing world, preset JSON or resource pack ZIP");
        String name=f.getName().toLowerCase(Locale.ROOT);
        if(name.equals("level.dat"))return Kind.WORLD;
        if(name.endsWith(".json"))return Kind.PRESET;
        if(name.endsWith(".zip")){
            try(java.util.zip.ZipFile zip=new java.util.zip.ZipFile(f)){
                if(zip.getEntry("pack.mcmeta")==null)throw new IOException("Resource pack needs pack.mcmeta");
            }
            return Kind.PACK;
        }
        throw new IOException("Unsupported dropped file: "+f.getName());
    }
    public static File world(File f){return f.isDirectory()?f:f.getParentFile();}
}
