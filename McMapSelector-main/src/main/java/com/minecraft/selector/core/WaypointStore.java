package com.minecraft.selector.core;
import java.util.*;
import java.io.*;
import com.fasterxml.jackson.databind.*;
/** Immutable marker records with bounded, atomic file loading. */
public final class WaypointStore {
    public static final class Marker {
        public final String label,dimension;public final int x,z;
        public Marker(String label,int x,int z,String dimension) {if(label==null||label.trim().isEmpty()||label.length()>128||dimension==null||!dimension.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))throw new IllegalArgumentException("Invalid waypoint label or dimension");this.label=label;this.x=x;this.z=z;this.dimension=dimension;}
        public Map<String,Object> json() {Map<String,Object> n=new LinkedHashMap<>();n.put("label",label);n.put("x",x);n.put("z",z);n.put("dimensionId",dimension);return n;}
        public String toString(){return label+" ("+x+", "+z+") ["+dimension+"]";}
    }
    private final List<Marker> markers=new ArrayList<>();
    public void add(Marker m){if(markers.size()>=1024)throw new IllegalArgumentException("Waypoint limit: 1024");markers.add(m);}
    public void remove(int i){markers.remove(i);}
    public List<Marker> list(){return Collections.unmodifiableList(new ArrayList<>(markers));}
    public void replace(WaypointStore other){markers.clear();markers.addAll(other.markers);}
    public List<Map<String,Object>> json(){List<Map<String,Object>> a=new ArrayList<>();for(Marker m:markers)a.add(m.json());return a;}
    public void save(File file)throws IOException {new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(file,json());}
    public static WaypointStore parse(JsonNode a){if(a==null||!a.isArray()||a.size()>1024)throw new IllegalArgumentException("Invalid waypoints");WaypointStore s=new WaypointStore();for(JsonNode m:a){if(!m.path("label").isTextual()||!m.path("dimensionId").isTextual())throw new IllegalArgumentException("Invalid waypoint strings");s.add(new Marker(m.get("label").asText(),com.minecraft.selector.gui.SelectionShape.integer(m,"x"),com.minecraft.selector.gui.SelectionShape.integer(m,"z"),m.get("dimensionId").asText()));}return s;}
    public static WaypointStore load(File f)throws IOException{if(f.length()>1024*1024)throw new IOException("Waypoint file exceeds 1 MiB");try{return parse(new ObjectMapper().readTree(f));}catch(IllegalArgumentException e){throw new IOException(e.getMessage(),e);}}
}
