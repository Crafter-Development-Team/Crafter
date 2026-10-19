package com.minecraft.selector.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.*;

/** Merged assets: later archives override individual paths, including texture-only packs. */
final class JarModelColors {
    static final class Surface {
        final Color color;
        final String source;
        Surface(Color color, String source) { this.color=color; this.source=source; }
    }
    final BiomeColors biomes=new BiomeColors();
    private final Map<String,JsonNode> json = new TreeMap<>();
    private final Map<String,Color> textures = new TreeMap<>();
    private final Map<String,Model> models = new HashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();
    private static final class Model {
        final Map<String,String> textures = new LinkedHashMap<>();
        JsonNode elements;
    }
    JarModelColors copy() {
        JarModelColors result=new JarModelColors();result.biomes.merge(biomes);result.json.putAll(json);result.textures.putAll(textures);return result;
    }
    void addArchive(String path) throws IOException {
        merge(ResourceArchiveCache.assets(ResourceArchiveCache.index(path)));
    }
    void merge(ResourceArchiveCache.Assets archive) {
        biomes.merge(archive.biomes);json.putAll(archive.json);textures.putAll(archive.textures);models.clear();
    }
    static Color average(BufferedImage image) {
        long r=0,g=0,b=0,a=0;
        // Include every pixel: tall model textures must not be mistaken for animation strips.
        int height=image.getHeight();
        for(int y=0;y<height;y++) for(int x=0;x<image.getWidth();x++) {
            int p=image.getRGB(x,y), alpha=p>>>24;
            a+=alpha; r+=(long)((p>>16)&255)*alpha; g+=(long)((p>>8)&255)*alpha; b+=(long)(p&255)*alpha;
        }
        if(a==0) return new Color(0,0,0,0);
        return new Color((int)(r/a),(int)(g/a),(int)(b/a),(int)(a/(image.getWidth()*(long)height)));
    }
    synchronized Surface resolve(String block, Map<String,String> properties) {
        String full=id(block,"minecraft"), ns=full.split(":",2)[0], name=full.split(":",2)[1];
        JsonNode state=json.get("assets/"+ns+"/blockstates/"+name+".json");
        List<JsonNode> refs=new ArrayList<>();
        if(state!=null) {
            JsonNode variants=state.get("variants");
            if(variants!=null) {
                Iterator<Map.Entry<String,JsonNode>> fields=variants.fields();
                JsonNode first=null;
                while(fields.hasNext()) {
                    Map.Entry<String,JsonNode> e=fields.next(); if(first==null) first=e.getValue();
                    if(matchesVariant(e.getKey(),properties)) { addReference(e.getValue(),refs); break; }
                }
                if(refs.isEmpty() && properties.isEmpty() && first!=null) addReference(first,refs);
            }
            JsonNode parts=state.get("multipart");
            if(parts!=null) for(JsonNode part:parts)
                if(!part.has("when") || matches(part.get("when"),properties)) addReference(part.get("apply"),refs);
        }
        if(refs.isEmpty() && state==null) {
            com.fasterxml.jackson.databind.node.ObjectNode ref=mapper.createObjectNode();
            ref.put("model",full.replace(":",":block/")); refs.add(ref);
        }
        List<Face> faces=new ArrayList<>(); Color particle=null; boolean geometry=false;
        for(JsonNode ref:refs) {
            Model m=model(id(ref.path("model").asText(),ns),new HashSet<>());
            if(m==null) continue;
            if(particle==null) for(String key:new String[]{"top","all","end","particle","side","texture"}) {
                Color c=texture(resolveTexture("#"+key,m.textures));
                if(c!=null) {particle=c; break;}
            }
            if(particle==null) for(String value:m.textures.values()) {
                Color c=texture(resolveTexture(value,m.textures)); if(c!=null) {particle=c;break;}
            }
            if(m.elements!=null) {
                geometry=true;
                for(JsonNode element:m.elements) project(element,m,ref,faces);
            }
        }
        if(geometry) return new Surface(tint(block,paint(faces)),"model");
        if(particle!=null) return new Surface(tint(block,particle),"model-particle");
        for(String candidate:new String[]{"block/"+name,"blocks/"+name,"item/"+name,name}) {
            Color c=texture(ns+":"+candidate); if(c!=null) return new Surface(tint(block,c),"texture");
        }
        // Same namespace only. Token overlap avoids borrowing an unrelated vanilla name.
        Set<String> tokens=tokens(name); String best=null; int score=0;
        for(String path:textures.keySet()) if(path.startsWith("assets/"+ns+"/textures/")) {
            String leaf=path.substring(path.lastIndexOf('/')+1).replace(".png","");
            Set<String> common=tokens(leaf); common.retainAll(tokens);
            int value=common.size()*10-(Math.abs(leaf.length()-name.length()));
            if(!common.isEmpty() && value>score) {score=value;best=path;}
        }
        return best==null?null:new Surface(textures.get(best),"similar-texture");
    }
    private static Set<String> tokens(String name) {
        Set<String> result=new HashSet<>(Arrays.asList(name.split("[_/]+")));
        result.removeAll(Arrays.asList("block","slab","half","side","top","bottom","stairs","roof","0","1","2","3","a","b",""));
        return result;
    }
    private static void addReference(JsonNode ref,List<JsonNode> refs) {
        if(ref==null) return;
        // Weighted alternatives describe equivalent shapes; deterministic first avoids random map flicker.
        if(ref.isArray()) { if(ref.size()>0) refs.add(ref.get(0)); } else refs.add(ref);
    }
    private static boolean matchesVariant(String key,Map<String,String> props) {
        if(key.isEmpty()) return true;
        for(String term:key.split(",")) {
            String[] pair=term.split("=",2);
            if(pair.length!=2 || (props.containsKey(pair[0]) && !Arrays.asList(pair[1].split("\\|")).contains(props.get(pair[0])))) return false;
        }
        return true;
    }
    private static boolean matches(JsonNode when,Map<String,String> props) {
        if(when.has("OR")) { for(JsonNode child:when.get("OR")) if(matches(child,props)) return true; return false; }
        if(when.has("AND")) { for(JsonNode child:when.get("AND")) if(!matches(child,props)) return false; return true; }
        Iterator<Map.Entry<String,JsonNode>> fields=when.fields();
        while(fields.hasNext()) { Map.Entry<String,JsonNode> e=fields.next();
            if(!Arrays.asList(e.getValue().asText().split("\\|")).contains(props.get(e.getKey()))) return false;
        }
        return true;
    }
    private Model model(String name,Set<String> visiting) {
        if(models.containsKey(name)) return models.get(name);
        if(!visiting.add(name) || visiting.size()>32) return null;
        String[] split=name.split(":",2);
        JsonNode node=json.get("assets/"+split[0]+"/models/"+split[1]+".json");
        if(node==null) {visiting.remove(name); return null;}
        Model m=new Model();
        if(node.has("parent")) {
            Model parent=model(id(node.get("parent").asText(),"minecraft"),visiting);
            if(parent!=null) {m.textures.putAll(parent.textures);m.elements=parent.elements;}
        }
        if(node.has("textures")) node.get("textures").fields().forEachRemaining(e -> {
            String value=e.getValue().asText(); m.textures.put(e.getKey(),value.startsWith("#")?value:id(value,"minecraft"));
        });
        if(node.has("elements")) m.elements=node.get("elements");
        visiting.remove(name);models.put(name,m);return m;
    }
    private static String resolveTexture(String ref,Map<String,String> map) {
        Set<String> seen=new HashSet<>();
        while(ref!=null && ref.startsWith("#")) {if(!seen.add(ref)) return null;ref=map.get(ref.substring(1));}
        return ref;
    }
    private Color texture(String name) {
        if(name==null) return null;
        String[] split=id(name,"minecraft").split(":",2);
        return textures.get("assets/"+split[0]+"/textures/"+split[1]+".png");
    }
    private static String id(String name,String ns) {return name.contains(":")?name:ns+":"+name;}
    Set<String> blockIds() {
        Set<String> ids=new TreeSet<>();
        for(String path:json.keySet()) if(path.contains("/blockstates/")) {
            String[] p=path.split("/",4);ids.add(p[1]+":"+p[3].substring(0,p[3].length()-5));
        }
        return ids;
    }
    private static final class Face {
        final double[][] points; final Color color; final double height;
        Face(double[][] points,Color color) {this.points=points;this.color=color;
            double sum=0;for(double[] p:points) sum+=p[1];height=sum/points.length;}
    }
    private void project(JsonNode element,Model model,JsonNode ref,List<Face> faces) {
        JsonNode from=element.get("from"),to=element.get("to");
        if(from==null || to==null) return;
        double[][] corners=new double[8][3];
        for(int i=0;i<8;i++) {
            double[] p={((i&1)==0?from:to).get(0).asDouble(),((i&2)==0?from:to).get(1).asDouble(),((i&4)==0?from:to).get(2).asDouble()};
            JsonNode rot=element.get("rotation");
            if(rot!=null) {
                double[] origin={8,8,8}; if(rot.has("origin")) for(int j=0;j<3;j++) origin[j]=rot.get("origin").get(j).asDouble();
                rotate(p,rot.path("axis").asText(),rot.path("angle").asDouble(),origin);
            }
            rotate(p,"x",ref.path("x").asDouble(),new double[]{8,8,8});
            rotate(p,"y",ref.path("y").asDouble(),new double[]{8,8,8});corners[i]=p;
        }
        String[] directions={"up","down","north","south","west","east"};
        // Winding viewed from outside. Only upward projected faces contribute.
        int[][] indices={{2,6,7,3},{0,1,5,4},{0,2,3,1},{4,5,7,6},{0,4,6,2},{1,3,7,5}};
        JsonNode node=element.path("faces");
        for(int i=0;i<indices.length;i++) {
            JsonNode face=node.get(directions[i]); if(face==null) continue;
            double[][] points=new double[4][];for(int j=0;j<4;j++) points[j]=corners[indices[i][j]];
            double ax=points[1][0]-points[0][0],az=points[1][2]-points[0][2];
            double bx=points[2][0]-points[0][0],bz=points[2][2]-points[0][2];
            if(az*bx-ax*bz<=0.00001) continue;
            Color color=texture(resolveTexture(face.path("texture").asText(),model.textures));
            if(color!=null) faces.add(new Face(points,color));
        }
    }
    private static void rotate(double[] p,String axis,double degrees,double[] origin) {
        if(degrees==0) return;
        int a="x".equals(axis)?1:0,b="z".equals(axis)?1:2;
        double u=p[a]-origin[a],v=p[b]-origin[b],c=Math.cos(Math.toRadians(degrees)),s=Math.sin(Math.toRadians(degrees));
        p[a]=origin[a]+u*c-v*s;p[b]=origin[b]+u*s+v*c;
    }
    private static Color paint(List<Face> faces) {
        faces.sort(Comparator.comparingDouble(f -> f.height));
        double r=0,g=0,b=0,a=0;
        for(int z=0;z<16;z++) for(int x=0;x<16;x++) {
            double pr=0,pg=0,pb=0,pa=0;
            for(Face f:faces) if(inside(f.points,x+0.5,z+0.5)) {
                double alpha=f.color.getAlpha()/255.0;
                pr=f.color.getRed()*alpha+pr*(1-alpha);pg=f.color.getGreen()*alpha+pg*(1-alpha);
                pb=f.color.getBlue()*alpha+pb*(1-alpha);pa=alpha+pa*(1-alpha);
            }
            r+=pr;g+=pg;b+=pb;a+=pa;
        }
        if(a==0) return new Color(0,0,0,0);
        return new Color((int)Math.round(r/a),(int)Math.round(g/a),(int)Math.round(b/a),(int)Math.round(a/256*255));
    }
    private static boolean inside(double[][] points,double x,double z) {
        boolean result=false;
        for(int i=0,j=points.length-1;i<points.length;j=i++) {
            if((points[i][2]>z)!=(points[j][2]>z) && x<(points[j][0]-points[i][0])*(z-points[i][2])/(points[j][2]-points[i][2])+points[i][0]) result=!result;
        }
        return result;
    }
    private static Color tint(String block,Color c) {
        String name=block.replace("minecraft:","");
        if(name.contains(":")) return c;
        int diff=Math.max(c.getRed(),Math.max(c.getGreen(),c.getBlue()))-Math.min(c.getRed(),Math.min(c.getGreen(),c.getBlue()));
        if(diff<40 && (name.contains("leaves") || name.equals("grass_block") || name.equals("grass") || name.equals("short_grass") || name.contains("fern") || name.equals("vine")))
            return new Color(85,155,58,c.getAlpha());
        if(diff<40 && name.equals("water")) return new Color(63,118,228,c.getAlpha());
        return c;
    }
}
