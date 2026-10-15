package com.minecraft.selector.core;
import com.minecraft.selector.gui.SelectionMath.Bounds;
import java.util.*;
/** Optional CLI XZ box: reject partial, duplicate and reversed bounds atomically. */
public final class SelectionSeed {
    public static boolean isFlag(String s) {return Arrays.asList("--min-x","--max-x","--min-z","--max-z").contains(s);}
    public static Bounds parse(String[] args) {
        Map<String,Integer> values=new HashMap<>();
        for(int i=0;i<args.length;i++) if(isFlag(args[i])) {
            String flag=args[i];if(values.containsKey(flag)||++i>=args.length) throw new IllegalArgumentException("Missing or duplicate "+flag);
            try {values.put(flag,Integer.valueOf(args[i]));}catch(NumberFormatException e) {throw new IllegalArgumentException("Invalid "+flag+": "+args[i]);}
        }
        if(values.isEmpty()) return null;
        if(values.size()!=4) throw new IllegalArgumentException("Selection seed requires --min-x --max-x --min-z --max-z");
        return new Bounds(values.get("--min-x"),values.get("--min-z"),values.get("--max-x"),values.get("--max-z"));
    }
    public static String[] withoutFlags(String[] args) {List<String> out=new ArrayList<>();for(int i=0;i<args.length;i++) {if(isFlag(args[i])) {if(i+1<args.length&&!args[i+1].startsWith("--")) i++;}else out.add(args[i]);}return out.toArray(new String[0]);}
}
