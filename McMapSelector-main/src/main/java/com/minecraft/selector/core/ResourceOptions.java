package com.minecraft.selector.core;

import com.minecraft.selector.utils.WorldPathResolver;
import java.io.*;
import java.util.*;

/** Shared by headless CLI, Blender integration and GUI. Explicit categories replace discovery. */
public final class ResourceOptions {
    public final List<String> jars=new ArrayList<>(), mods=new ArrayList<>(), packs=new ArrayList<>();
    public boolean auto=true;
    public Integer renderY;
    private static volatile ResourceOptions launch=new ResourceOptions();
    public static ResourceOptions launch() {return launch.copy();}
    public static void setLaunch(ResourceOptions options) {launch=options.copy();}
    public ResourceOptions copy() {
        ResourceOptions o=new ResourceOptions();o.jars.addAll(jars);o.mods.addAll(mods);o.packs.addAll(packs);o.auto=auto;o.renderY=renderY;return o;
    }
    public static boolean isResourceFlag(String s) {
        return Arrays.asList("--jar-path","--mod-jar","--mods-dir","--resource-pack","--no-auto-resources","--render-y").contains(s);
    }
    public static ResourceOptions parse(String[] args) {
        ResourceOptions o=new ResourceOptions();
        for(int i=0;i<args.length;i++) {
            String flag=args[i];if(!isResourceFlag(flag)) continue;
            if(flag.equals("--no-auto-resources")) {o.auto=false;continue;}
            if(++i>=args.length) throw new IllegalArgumentException("Missing path for " + flag);
            if(flag.equals("--render-y")) {o.renderY=Integer.valueOf(args[i]);continue;}
            File f=new File(args[i]);
            if(flag.equals("--mods-dir")) {
                if(!f.isDirectory()) throw new IllegalArgumentException("Missing mods directory: " + f);
                o.mods.addAll(archives(f,".jar",null));
            } else {
                if(!f.isFile()) throw new IllegalArgumentException("Missing resource archive: " + f);
                (flag.equals("--jar-path")?o.jars:flag.equals("--mod-jar")?o.mods:o.packs).add(f.getAbsolutePath());
            }
        }
        return o;
    }
    public static String[] withoutResourceFlags(String[] args) {
        List<String> positional=new ArrayList<>();
        for(int i=0;i<args.length;i++) {
            if(isResourceFlag(args[i])) {if(!args[i].equals("--no-auto-resources")) i++;}
            else positional.add(args[i]);
        }
        return positional.toArray(new String[0]);
    }
    public List<String> archivesFor(String save) {
        List<String> vanilla=new ArrayList<>(jars),mod=new ArrayList<>(mods),pack=new ArrayList<>(packs);
        if(auto) {
            File input=new File(save==null?System.getProperty("user.dir"):save).getAbsoluteFile();
            Probe probe=probe(input);
            File directory=input.isFile()?input.getParentFile():input;
            Set<File> inferredInstances=new LinkedHashSet<>();
            int depth=0;
            for(File parent=directory;parent!=null && parent.getParentFile()!=null && depth++<8;parent=parent.getParentFile()) {
                List<File> instances=new ArrayList<>();instances.add(parent);
                File[] children=parent.listFiles(File::isDirectory);
                if(children!=null) {Arrays.sort(children);instances.addAll(Arrays.asList(children));}
                if(vanilla.isEmpty()) {
                    List<String> candidates=new ArrayList<>();
                    for(File instance:instances) candidates.addAll(versionJars(new File(instance,"versions")));
                    String chosen=closestVersion(candidates,probe.version);
                    if(chosen!=null) vanilla.add(chosen);
                }
                if(mod.isEmpty()) {
                    for(File instance:instances) {
                        List<String> found=archives(new File(instance,"mods"),".jar",null);
                        if(probe.namespaces.isEmpty()) {
                            // A local instance is authoritative even if this region contains only vanilla blocks.
                            if(instance.equals(parent) && !found.isEmpty()) {mod.addAll(found);inferredInstances.add(instance);break;}
                        } else {
                            for(String archive:found) if(matchesNamespaces(archive,probe.namespaces)) {mod.add(archive);inferredInstances.add(instance);}
                        }
                    }
                }
                if(!vanilla.isEmpty() && (!mod.isEmpty() || probe.namespaces.isEmpty())) break;
            }
            if(vanilla.isEmpty()) {
                String chosen=closestVersion(MinecraftResourceExtractor.findMinecraftJars(),probe.version);
                if(chosen!=null) vanilla.add(chosen);
            }
            if(pack.isEmpty()) {
                // Discover packs from the actual instances whose archives matched world namespaces.
                // Prefer options.txt enabled pack order; otherwise merge matching packs by filename.
                for(String path:mod) {File parent=new File(path).getParentFile();if(parent!=null && parent.getName().equals("mods")) inferredInstances.add(parent.getParentFile());}
                for(File instance:inferredInstances) {
                    List<String> enabled=enabledPacks(instance);
                    if(enabled!=null) pack.addAll(enabled);
                    else {
                        Set<String> packNamespaces=new HashSet<>(probe.namespaces);packNamespaces.add("minecraft");
                        for(String path:archives(new File(instance,"resourcepacks"),".zip",null))
                            if(matchesNamespaces(path,packNamespaces)) pack.add(path);
                    }
                }
            }
        }
        LinkedHashSet<String> stack=new LinkedHashSet<>();stack.addAll(vanilla);stack.addAll(mod);stack.addAll(pack);
        return new ArrayList<>(stack);
    }
    private static final class Probe {int version;final Set<String> namespaces=new TreeSet<>();}
    private static Probe probe(File input) {
        Probe result=new Probe();
        File directory=input.isFile()?input.getParentFile():input;
        for(File parent=directory;parent!=null;parent=parent.getParentFile()) {
            File level=new File(parent,"level.dat");
            if(level.isFile()) {
                try {result.version=com.minecraft.selector.nbt.NBTReader.readFromFile(level.toString()).getCompound("Data").getInt("DataVersion");}
                catch(Exception e) {System.err.println("Cannot probe level.dat version: "+e.getMessage());}break;
            }
        }
        File mca=input.isFile() && input.getName().endsWith(".mca") && input.length()>0?input:null;
        if(mca==null) {
            File region=null;
            // Walk up to world root (level.dat) then resolve 26.1+/legacy region dir.
            for(File parent=directory;parent!=null;parent=parent.getParentFile()) {
                if(new File(parent,"level.dat").isFile()) {
                    region=WorldPathResolver.getOverworldRegionDir(parent.getAbsolutePath());
                    break;
                }
            }
            if(region==null || !region.isDirectory()) {
                region=new File(directory,"region");
                if(!region.isDirectory() && directory.getName().equals("region")) region=directory;
            }
            // Only probe non-empty .mca; 0-byte placeholders are normal and have no chunk data.
            File first=WorldPathResolver.findFirstMca(region);
            if(first!=null) mca=first;
        }
        if(mca!=null) try {
            com.minecraft.selector.region.Region region=com.minecraft.selector.region.Region.fromFile(mca.toString());
            for(int z=0;z<32;z++) for(int x=0;x<32;x++) if(region.chunkExists(x,z)) {
                com.minecraft.selector.region.Chunk chunk=region.getChunk(x,z);
                if(chunk==null) continue;
                if(result.version==0 && chunk.getNbtData().contains("DataVersion")) result.version=chunk.getNbtData().getInt("DataVersion");
                for(com.minecraft.selector.region.Chunk.Section section:chunk.getSections())
                    for(com.minecraft.selector.region.Block block:section.getPalette()) {
                        String id=block.getId();if(id.contains(":")) result.namespaces.add(id.split(":",2)[0]);
                    }
            }
        } catch(Exception e) {System.err.println("Cannot probe world resource namespaces: "+e.getMessage());}
        result.namespaces.remove("minecraft");
        return result;
    }
    private static List<String> versionJars(File versions) {
        List<String> result=new ArrayList<>();File[] dirs=versions.listFiles(File::isDirectory);
        if(dirs!=null) {Arrays.sort(dirs);for(File dir:dirs) {File jar=new File(dir,dir.getName()+".jar");if(jar.isFile()) result.add(jar.getAbsolutePath());}}return result;
    }
    private static String closestVersion(List<String> candidates,int desired) {
        String chosen=null;long best=Long.MAX_VALUE;
        for(String path:candidates) try {
            Integer version=ResourceArchiveCache.index(path).version;if(version==null) continue;
            long difference=desired==0?Integer.MAX_VALUE-(long)version:Math.abs((long)desired-version);
            if(difference<best) {chosen=path;best=difference;}
        } catch(IOException e) {System.err.println("Cannot inspect client archive: "+path);}
        return chosen;
    }
    private static boolean matchesNamespaces(String path,Set<String> namespaces) {
        if(namespaces.isEmpty()) return false;
        try {return !Collections.disjoint(ResourceArchiveCache.index(path).namespaces,namespaces);}
        catch(IOException e) {System.err.println("Cannot inspect resource archive: "+path);return false;}
    }
    private static List<String> enabledPacks(File instance) {
        File options=new File(instance,"options.txt");if(!options.isFile()) return null;
        try(BufferedReader reader=new BufferedReader(new InputStreamReader(new FileInputStream(options),"UTF-8"))) {
            String line;while((line=reader.readLine())!=null) if(line.startsWith("resourcePacks:")) {
                List<String> result=new ArrayList<>();
                for(com.fasterxml.jackson.databind.JsonNode name:new com.fasterxml.jackson.databind.ObjectMapper().readTree(line.substring("resourcePacks:".length()))) {
                    String value=name.asText();if(value.startsWith("file/")) value=value.substring(5);
                    File archive=new File(instance,"resourcepacks/"+value);if(archive.isFile()) result.add(archive.getAbsolutePath());
                }return result;
            }
        } catch(IOException e) {System.err.println("Cannot inspect enabled resource packs: "+e.getMessage());}return null;
    }
    private static List<String> archives(File directory,String suffix,String prefix) {
        File[] files=directory.listFiles((dir,name)->name.toLowerCase(Locale.ROOT).endsWith(suffix) && (prefix==null || name.startsWith(prefix)));
        List<String> result=new ArrayList<>();if(files!=null) {Arrays.sort(files);for(File f:files) result.add(f.getAbsolutePath());}return result;
    }
}
