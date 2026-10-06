package com.minecraft.selector.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import java.util.function.BiFunction;
import java.util.jar.*;
import javax.imageio.ImageIO;

/** Resolve block IDs through JAR blockstates -> inherited models -> texture references. */
final class JarModelColors {
    private final JarFile jar;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, Model> models = new HashMap<>();
    private final Map<String, BufferedImage> textures = new HashMap<>();
    private final BiFunction<String, BufferedImage, Color> average;
    private static final class Model {
        final Map<String,String> textures = new LinkedHashMap<>();
        JsonNode elements;
    }
    private JarModelColors(JarFile jar, BiFunction<String,BufferedImage,Color> average) {
        this.jar=jar; this.average=average;
    }
    static Map<String,Color> resolve(JarFile jar, BiFunction<String,BufferedImage,Color> average) {
        return new JarModelColors(jar,average).resolve();
    }
    private Map<String,Color> resolve() {
        Map<String,Color> result=new TreeMap<>();
        List<String> entries=new ArrayList<>();
        Enumeration<JarEntry> en=jar.entries();
        while(en.hasMoreElements()) {
            String name=en.nextElement().getName();
            if(name.matches("assets/[^/]+/blockstates/[^/]+\\.json")) entries.add(name);
        }
        Collections.sort(entries);
        for(String path:entries) {
            String[] parts=path.split("/");
            String namespace=parts[1], block=parts[3].substring(0,parts[3].length()-5);
            try {
                JsonNode state=read(path);
                List<String> references=new ArrayList<>();
                collectModels(state,references,namespace);
                for(String ref:references) {
                    Model model=model(ref,new HashSet<>());
                    if(model==null) continue;
                    List<String> candidates=new ArrayList<>();
                    if(model.elements!=null) for(JsonNode element:model.elements) {
                        JsonNode face=element.path("faces").get("up");
                        if(face!=null && face.has("texture")) candidates.add(face.get("texture").asText());
                    }
                    for(String key:new String[]{"top","all","end","side","texture","particle"})
                        if(model.textures.containsKey(key)) candidates.add("#"+key);
                    List<String> rest=new ArrayList<>(model.textures.keySet()); Collections.sort(rest);
                    for(String key:rest) candidates.add("#"+key);
                    Color color=null;
                    for(String candidate:candidates) {
                        String texture=resolveTexture(candidate,model.textures);
                        if(texture==null) continue;
                        BufferedImage image=texture(texture);
                        if(image!=null) { color=average.apply(block,image); break; }
                    }
                    if(color!=null) {
                        result.put(namespace+":"+block,color);
                        if("minecraft".equals(namespace)) result.put(block,color);
                        break;
                    }
                }
            } catch(Exception e) {
                System.err.println("Cannot resolve block texture " + path + ": " + e.getMessage());
            }
        }
        return result;
    }
    private void collectModels(JsonNode node,List<String> refs,String ns) {
        if(node==null) return;
        if(node.isObject()) {
            if(node.has("model")) {
                String ref=id(node.get("model").asText(),ns);
                if(!refs.contains(ref)) refs.add(ref);
            }
            Iterator<JsonNode> children=node.elements();
            while(children.hasNext()) collectModels(children.next(),refs,ns);
        } else if(node.isArray()) for(JsonNode child:node) collectModels(child,refs,ns);
    }
    private Model model(String id,Set<String> visiting) throws IOException {
        if(models.containsKey(id)) return models.get(id);
        if(!visiting.add(id) || visiting.size()>32) return null;
        String[] split=id.split(":",2);
        JsonNode json=read("assets/"+split[0]+"/models/"+split[1]+".json");
        if(json==null) { visiting.remove(id); models.put(id,null); return null; }
        Model result=new Model();
        if(json.has("parent") && !json.get("parent").asText().startsWith("builtin/")) {
            Model parent=model(id(json.get("parent").asText(),split[0]),visiting);
            if(parent!=null) { result.textures.putAll(parent.textures); result.elements=parent.elements; }
        }
        JsonNode tex=json.get("textures");
        if(tex!=null) {
            Iterator<Map.Entry<String,JsonNode>> fields=tex.fields();
            while(fields.hasNext()) {
                Map.Entry<String,JsonNode> e=fields.next(); String value=e.getValue().asText();
                result.textures.put(e.getKey(),value.startsWith("#")?value:id(value,split[0]));
            }
        }
        if(json.has("elements")) result.elements=json.get("elements");
        visiting.remove(id); models.put(id,result); return result;
    }
    private String resolveTexture(String ref,Map<String,String> map) {
        Set<String> seen=new HashSet<>();
        while(ref!=null && ref.startsWith("#")) {
            if(!seen.add(ref)) return null;
            ref=map.get(ref.substring(1));
        }
        return ref;
    }
    private BufferedImage texture(String id) throws IOException {
        if(textures.containsKey(id)) return textures.get(id);
        String[] split=id(id,"minecraft").split(":",2);
        JarEntry entry=jar.getJarEntry("assets/"+split[0]+"/textures/"+split[1]+".png");
        BufferedImage image=null;
        if(entry!=null) try(InputStream in=jar.getInputStream(entry)) { image=ImageIO.read(in); }
        textures.put(id,image); return image;
    }
    private JsonNode read(String path) throws IOException {
        JarEntry entry=jar.getJarEntry(path);
        if(entry==null) return null;
        try(InputStream in=jar.getInputStream(entry)) { return mapper.readTree(in); }
    }
    private static String id(String name,String ns) { return name.contains(":")?name:ns+":"+name; }
}
