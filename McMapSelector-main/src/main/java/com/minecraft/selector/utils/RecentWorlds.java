package com.minecraft.selector.utils;
import java.io.*;
import java.nio.file.*;
import java.util.*;
/** A portable bounded properties file; no Java preferences backend dependence. */
public final class RecentWorlds {
    private final File file;private static final int LIMIT=10;
    public RecentWorlds() {this(new File(System.getProperty("user.home"),".minecraft-map-selector/recent-worlds.properties"));}
    public RecentWorlds(File file) {this.file=file;}
    public synchronized List<String> list() throws IOException {
        Properties p=new Properties();if(file.isFile()) try(InputStream in=new FileInputStream(file)) {p.load(in);}
        LinkedHashSet<String> paths=new LinkedHashSet<>();
        for(int i=0;i<LIMIT;i++) {String path=p.getProperty("world."+i);if(path!=null&&new File(path).isDirectory()) paths.add(new File(path).getCanonicalPath());}
        List<String> result=new ArrayList<>(paths);write(result);return result;
    }
    public synchronized void add(File world) throws IOException {
        if(!world.isDirectory()) throw new IOException("Missing world directory: "+world);
        List<String> paths=list();String path=world.getCanonicalPath();paths.remove(path);paths.add(0,path);write(paths.subList(0,Math.min(LIMIT,paths.size())));
    }
    private void write(List<String> paths) throws IOException {
        File parent=file.getAbsoluteFile().getParentFile();Files.createDirectories(parent.toPath());
        Properties p=new Properties();for(int i=0;i<paths.size();i++) p.setProperty("world."+i,paths.get(i));
        Path temp=Files.createTempFile(parent.toPath(),"recent-",".tmp");
        try {try(OutputStream out=Files.newOutputStream(temp)) {p.store(out,"McMapSelector recent worlds");}Files.move(temp,file.toPath(),StandardCopyOption.REPLACE_EXISTING);}finally {Files.deleteIfExists(temp);}
    }
}
