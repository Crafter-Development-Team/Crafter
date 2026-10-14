package com.minecraft.selector.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.*;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;

/** Bounded, single-flight caches of immutable archive metadata and decoded asset colors. */
final class ResourceArchiveCache {
    static final AtomicInteger INDEX_READS=new AtomicInteger(), PNG_DECODES=new AtomicInteger();
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final Map<String,FutureTask<Index>> INDEX=cache(512);
    private static final Map<String,FutureTask<Assets>> ASSETS=cache(256);
    private static final Map<String,FutureTask<Color>> PNG=cache(32768);
    static final class Index {
        final String path, fingerprint;
        final List<String> entries;
        final Set<String> namespaces;
        final Integer version;
        Index(String path,String fingerprint,List<String> entries,Set<String> namespaces,Integer version) {
            this.path=path;this.fingerprint=fingerprint;this.entries=Collections.unmodifiableList(entries);
            this.namespaces=Collections.unmodifiableSet(namespaces);this.version=version;
        }
    }
    static final class Assets {
        final Map<String,JsonNode> json=new HashMap<>();
        final Map<String,Color> textures=new HashMap<>();
    }
    private static <T> Map<String,FutureTask<T>> cache(final int limit) {
        return new LinkedHashMap<String,FutureTask<T>>(16,0.75f,true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String,FutureTask<T>> e) {return size()>limit;}
        };
    }
    private static <T> T cached(Map<String,FutureTask<T>> cache,String key,Callable<T> read) throws IOException {
        FutureTask<T> task;boolean owner=false;
        synchronized(cache) {
            task=cache.get(key);
            if(task==null) {task=new FutureTask<>(read);cache.put(key,task);owner=true;}
        }
        if(owner) task.run();
        try {return task.get();}
        catch(InterruptedException e) {Thread.currentThread().interrupt();throw new IOException("Resource loading interrupted",e);}
        catch(ExecutionException e) {
            synchronized(cache) {cache.remove(key,task);}
            throw new IOException("Cannot read resource: "+key,e.getCause());
        }
    }
    private static String fileKey(String path) throws IOException {
        Path file=Paths.get(path).toRealPath();
        BasicFileAttributes a=Files.readAttributes(file,BasicFileAttributes.class);
        Object changed="";
        try {changed=Files.getAttribute(file,"unix:ctime");} catch(UnsupportedOperationException | IllegalArgumentException ignored) {}
        return file+"|"+a.size()+"|"+a.lastModifiedTime()+"|"+a.fileKey()+"|"+changed;
    }
    static Index index(String path) throws IOException {
        String key=fileKey(path);
        return cached(INDEX,key,() -> {
            INDEX_READS.incrementAndGet();
            String real=Paths.get(path).toRealPath().toString();
            List<String> entries=new ArrayList<>();Set<String> namespaces=new TreeSet<>();
            MessageDigest digest=MessageDigest.getInstance("SHA-256");Integer version=null;
            try(ZipFile zip=new ZipFile(real)) {
                Enumeration<? extends ZipEntry> all=zip.entries();
                while(all.hasMoreElements()) {
                    ZipEntry e=all.nextElement();String name=e.getName();
                    // Include all entries in the stack fingerprint, including version metadata.
                    digest.update((name+"|"+e.getCrc()+"|"+e.getSize()+"\n").getBytes("UTF-8"));
                    if(e.isDirectory() || !name.startsWith("assets/")) continue;
                    String[] parts=name.split("/",3);if(parts.length>=3) namespaces.add(parts[1]);
                    if((name.contains("/textures/") && name.endsWith(".png")) ||
                        ((name.contains("/models/") || name.contains("/blockstates/")) && name.endsWith(".json"))) entries.add(name);
                }
                ZipEntry metadata=zip.getEntry("version.json");
                if(metadata!=null) try(InputStream in=zip.getInputStream(metadata)) {version=JSON.readTree(in).path("world_version").asInt();}
                catch(IOException e) {System.err.println("Cannot inspect optional client version metadata: "+real);}
            }
            if(!key.equals(fileKey(real))) throw new IOException("Resource changed while indexing: "+real);
            return new Index(real,key+"|"+hex(digest.digest()),entries,namespaces,version);
        });
    }
    static Assets assets(Index index) throws IOException {
        return cached(ASSETS,index.fingerprint,() -> {
            Assets result=new Assets();
            try(ZipFile zip=new ZipFile(index.path)) {
                for(String name:index.entries) {
                    if(Thread.currentThread().isInterrupted()) throw new IOException("Resource loading interrupted");
                    ZipEntry entry=zip.getEntry(name);
                    if(entry==null) throw new IOException("Resource changed: "+index.path);
                    try(InputStream in=zip.getInputStream(entry)) {
                        if(name.endsWith(".png")) {
                            ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;
                            while((n=in.read(buffer))!=-1) bytes.write(buffer,0,n);
                            byte[] data=bytes.toByteArray();String hash=hex(MessageDigest.getInstance("SHA-256").digest(data));
                            Color color=cached(PNG,hash,() -> {
                                PNG_DECODES.incrementAndGet();
                                try(MemoryCacheImageInputStream imageStream=new MemoryCacheImageInputStream(new ByteArrayInputStream(data))) {
                                    Iterator<javax.imageio.ImageReader> readers=ImageIO.getImageReaders(imageStream);
                                    if(!readers.hasNext()) return null;
                                    javax.imageio.ImageReader reader=readers.next();
                                    try {reader.setInput(imageStream);return JarModelColors.average(reader.read(0));}
                                    finally {reader.dispose();}
                                }
                            });
                            if(color!=null) result.textures.put(name,color);
                        } else result.json.put(name,JSON.readTree(in));
                    }
                }
            }
            // Validate against metadata again before publishing this immutable snapshot.
            if(!index.fingerprint.equals(index(index.path).fingerprint)) throw new IOException("Resource changed while extracting: "+index.path);
            return result;
        });
    }
    private static String hex(byte[] bytes) {
        char[] digits="0123456789abcdef".toCharArray(),result=new char[bytes.length*2];
        for(int i=0;i<bytes.length;i++) {result[i*2]=digits[(bytes[i]>>>4)&15];result[i*2+1]=digits[bytes[i]&15];}
        return new String(result);
    }
}
