package com.minecraft.selector.core;
import com.minecraft.selector.nbt.NBTReader.*;
/** Optional AND conditions; absent/malformed metadata never counts as a match. */
public final class ChunkFilter {
    public final Long inhabitedAbove;public final Integer minVersion,maxVersion;
    public ChunkFilter(Long inhabitedAbove,Integer minVersion,Integer maxVersion){if(inhabitedAbove==null&&minVersion==null&&maxVersion==null)throw new IllegalArgumentException("At least one filter is required");if(inhabitedAbove!=null&&inhabitedAbove<0||minVersion!=null&&minVersion<0||maxVersion!=null&&maxVersion<0||minVersion!=null&&maxVersion!=null&&minVersion>maxVersion)throw new IllegalArgumentException("Invalid filter range");this.inhabitedAbove=inhabitedAbove;this.minVersion=minVersion;this.maxVersion=maxVersion;}
    public boolean matches(NBTCompound root){NBTCompound data=root.get("Level") instanceof NBTCompound?root.getCompound("Level"):root;Long time=number(data,"InhabitedTime"),version=number(root,"DataVersion");if(version==null)version=number(data,"DataVersion");return (inhabitedAbove==null||time!=null&&time>inhabitedAbove)&&(minVersion==null||version!=null&&version>=minVersion)&&(maxVersion==null||version!=null&&version<=maxVersion);}
    private static Long number(NBTCompound n,String k){NBTTag t=n.get(k);return t instanceof NBTInt||t instanceof NBTLong?((Number)t.getValue()).longValue():null;}
}
