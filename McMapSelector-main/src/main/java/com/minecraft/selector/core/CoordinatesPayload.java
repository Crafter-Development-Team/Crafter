package com.minecraft.selector.core;
import com.minecraft.selector.gui.SelectionMath.Bounds;
import com.minecraft.selector.gui.SelectionStats;
import java.util.LinkedHashMap;
import java.util.Map;
public final class CoordinatesPayload {
    private CoordinatesPayload() {}
    public static Map<String,Object> create(Bounds b,int minY,int maxY,long timestamp,String dimensionId,String worldName,Integer dataVersion,com.minecraft.selector.gui.SelectionShape shape,boolean multiBox,WaypointStore waypoints,boolean includeWaypoints) {
        Map<String,Object> p=create(b,minY,maxY,timestamp,dimensionId,worldName,dataVersion);
        if(!shape.isEmpty()) {
            if(!b.equals(shape.bounds()))throw new IllegalArgumentException("Selection shape changed");
            p.put("selectionShape",shape.json());p.put("selectionProtocol","union-aabb");
        }
        if(multiBox&&!shape.isCircle()){java.util.List<Map<String,Object>> boxes=new java.util.ArrayList<>();for(Bounds box:shape.isEmpty()?java.util.Collections.singletonList(b):shape.boxes()){Map<String,Object> v=new LinkedHashMap<>(com.minecraft.selector.gui.SelectionShape.boxJson(box));v.put("minY",minY);v.put("maxY",maxY);boxes.add(v);}p.put("boxes",boxes);p.put("selectionProtocol","boxes-with-union-aabb");}
        if(includeWaypoints)p.put("waypoints",waypoints.json());return p;
    }
    public static Map<String,Object> create(Bounds b,int minY,int maxY,long timestamp,String dimensionId,String worldName,Integer dataVersion,com.minecraft.selector.gui.SelectionShape shape,boolean multiBox,WaypointStore waypoints,boolean includeWaypoints,ImportLod importLod) {
        Map<String,Object> p=create(b,minY,maxY,timestamp,dimensionId,worldName,dataVersion,shape,multiBox,waypoints,includeWaypoints);
        p.put("importLod",importLod.json());
        return p;
    }
    public static Map<String,Object> create(Bounds b,int minY,int maxY,long timestamp,String dimensionId,String worldName,Integer dataVersion) {
        SelectionStats s=new SelectionStats(b,minY,maxY);Map<String,Object> p=new LinkedHashMap<>();
        p.put("minX",b.minX);p.put("minY",minY);p.put("minZ",b.minZ);p.put("maxX",b.maxX);p.put("maxY",maxY);p.put("maxZ",b.maxZ);p.put("timestamp",timestamp);
        p.put("sizeX",s.sizeX);p.put("sizeZ",s.sizeZ);p.put("sizeY",s.sizeY);p.put("columnCount",s.columns);
        if(dimensionId!=null) p.put("dimensionId",dimensionId);if(worldName!=null) p.put("worldName",worldName);if(dataVersion!=null) p.put("dataVersion",dataVersion);
        return p;
    }
}
