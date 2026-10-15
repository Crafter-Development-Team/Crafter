package com.minecraft.selector.core;
/** Immutable render policy, included in preview cache identity. */
public final class PreviewOptions {
    public static final PreviewOptions DEFAULT=new PreviewOptions(false,false,0);
    public final boolean transparentWater,cave; public final int biomeMode;
    public PreviewOptions(boolean water,boolean cave,int biome) {transparentWater=water;this.cave=cave;biomeMode=Math.floorMod(biome,3);}
    public String cacheKey() {return "p1-v1-water="+transparentWater+"-cave="+cave+"-biome="+biomeMode;}
    public static boolean water(String id) {return id!=null&&(id.contains("water")||id.endsWith("bubble_column"));}
    public static boolean fitTransparent(String id) {return water(id)||(id!=null&&id.contains("glass"));}
}
