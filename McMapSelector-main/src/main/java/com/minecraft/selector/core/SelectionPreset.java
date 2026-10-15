package com.minecraft.selector.core;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.minecraft.selector.gui.SelectionMath.Bounds;
import com.minecraft.selector.gui.SelectionShape;
import java.io.*;
/** Validated preset, parsed fully before callers mutate UI state. */
public final class SelectionPreset {
    public final Bounds bounds;public final Integer minY,maxY;public final String dimension;
    public final SelectionShape shape;public final WaypointStore waypoints;
    public SelectionPreset(Bounds bounds,Integer minY,Integer maxY,String dimension) {
        this(bounds,minY,maxY,dimension,new SelectionShape(),new WaypointStore());
    }
    public SelectionPreset(Bounds bounds,Integer minY,Integer maxY,String dimension,SelectionShape shape,WaypointStore waypoints) {
        if(bounds==null)throw new IllegalArgumentException("Selection is required");
        if(!shape.isEmpty()&&!bounds.equals(shape.bounds()))throw new IllegalArgumentException("Shape union differs from preset bounds");
        this.shape=shape.copy();this.waypoints=new WaypointStore();this.waypoints.replace(waypoints);
        if((minY==null)!=(maxY==null)||minY!=null&&(minY==Integer.MIN_VALUE||minY>maxY)) throw new IllegalArgumentException("Invalid Import Y range");
        if(dimension!=null&&!dimension.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) throw new IllegalArgumentException("Invalid dimension id");
        this.bounds=bounds;this.minY=minY;this.maxY=maxY;this.dimension=dimension;
    }
    public void save(File file) throws IOException {
        ObjectMapper mapper=new ObjectMapper();ObjectNode n=mapper.createObjectNode();n.put("format","McMapSelector selection");n.put("version",1);
        n.put("minX",bounds.minX);n.put("maxX",bounds.maxX);n.put("minZ",bounds.minZ);n.put("maxZ",bounds.maxZ);
        if(minY!=null) {n.put("minY",minY);n.put("maxY",maxY);}if(dimension!=null) n.put("dimensionId",dimension);
        if(!shape.isEmpty())n.set("selectionShape",mapper.valueToTree(shape.json()));n.set("waypoints",mapper.valueToTree(waypoints.json()));
        mapper.writerWithDefaultPrettyPrinter().writeValue(file,n);
    }
    public static SelectionPreset load(File file) throws IOException {
        if(file.length()>16*1024*1024) throw new IOException("Preset exceeds 16 MiB");
        try {
            JsonNode n=new ObjectMapper().readTree(file);if(n==null||!n.isObject()) throw new IllegalArgumentException("Expected JSON object");
            if(n.has("version")&&integer(n,"version")!=1) throw new IllegalArgumentException("Unsupported preset version");
            JsonNode d=n.get("dimensionId");if(d!=null&&!d.isTextual()) throw new IllegalArgumentException("Invalid dimension id");
            return new SelectionPreset(new Bounds(integer(n,"minX"),integer(n,"minZ"),integer(n,"maxX"),integer(n,"maxZ")),n.has("minY")?integer(n,"minY"):null,n.has("maxY")?integer(n,"maxY"):null,d==null?null:d.asText(),n.has("selectionShape")?SelectionShape.parse(n.get("selectionShape")):new SelectionShape(),n.has("waypoints")?WaypointStore.parse(n.get("waypoints")):new WaypointStore());
        } catch(IllegalArgumentException e) {throw new IOException("Invalid selection preset: "+e.getMessage(),e);}
    }
    private static int integer(JsonNode n,String key) {JsonNode v=n.get(key);if(v==null||!v.isIntegralNumber()||!v.canConvertToInt()) throw new IllegalArgumentException("Expected integer "+key);return v.intValue();}
}
