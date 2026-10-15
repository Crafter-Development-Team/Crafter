package com.minecraft.selector.core;
import java.io.*;
import java.math.BigInteger;
import com.fasterxml.jackson.databind.*;
import com.minecraft.selector.gui.*;
/** No Swing or renderer initialization; shared by CLI and CI tests. */
public final class SelectionValidator {
    private SelectionValidator(){}
    public static BigInteger validate(File f,BigInteger limit)throws IOException {
        if(limit==null||limit.signum()<0)throw new IOException("max-columns must be nonnegative");
        if(!f.isFile()||f.length()>16*1024*1024)throw new IOException("Selection file missing or exceeds 16 MiB");
        try{JsonNode n=new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readTree(f);if(n==null||!n.isObject())throw new IllegalArgumentException("Expected object");
            SelectionMath.Bounds b=new SelectionMath.Bounds(SelectionShape.integer(n,"minX"),SelectionShape.integer(n,"minZ"),SelectionShape.integer(n,"maxX"),SelectionShape.integer(n,"maxZ"));
            int lo=SelectionShape.integer(n,"minY"),hi=SelectionShape.integer(n,"maxY");if(lo>hi)throw new IllegalArgumentException("Reversed Y range");
            JsonNode t=n.get("timestamp");if(t==null||!t.isIntegralNumber()||!t.canConvertToLong()||t.longValue()<0)throw new IllegalArgumentException("Invalid timestamp");
            SelectionStats stats=new SelectionStats(b,lo,hi);BigInteger columns=stats.columns;
            for(String key:new String[]{"sizeX","sizeY","sizeZ"})if(n.has(key)){long expected=key.equals("sizeX")?stats.sizeX:key.equals("sizeY")?stats.sizeY:stats.sizeZ;JsonNode v=n.get(key);if(!v.isIntegralNumber()||!v.canConvertToLong()||v.longValue()!=expected)throw new IllegalArgumentException(key+" disagrees with bounds");}if(columns.compareTo(limit)>0)throw new IllegalArgumentException("Column limit exceeded: "+columns+" > "+limit);
            if(n.has("columnCount")&&(!n.get("columnCount").isIntegralNumber()||!columns.equals(n.get("columnCount").bigIntegerValue())))throw new IllegalArgumentException("columnCount disagrees with bounds");
            if(n.has("boxes")){JsonNode a=n.get("boxes");if(!a.isArray()||a.size()==0||a.size()>SelectionShape.MAX_PARTS)throw new IllegalArgumentException("Invalid boxes");SelectionShape shape=new SelectionShape();for(JsonNode v:a){shape.addBox(new SelectionMath.Bounds(SelectionShape.integer(v,"minX"),SelectionShape.integer(v,"minZ"),SelectionShape.integer(v,"maxX"),SelectionShape.integer(v,"maxZ")));if(SelectionShape.integer(v,"minY")!=lo||SelectionShape.integer(v,"maxY")!=hi)throw new IllegalArgumentException("Box Y disagrees with bounds");}if(!b.equals(shape.bounds()))throw new IllegalArgumentException("Box union disagrees with root bounds");}
            return columns;
        }catch(IllegalArgumentException e){throw new IOException("Invalid selection: "+e.getMessage(),e);}
    }
    public static int run(String[] args,PrintStream out,PrintStream err){try{File file=null;BigInteger limit=null;for(int i=0;i<args.length;i++){String flag=args[i];if(i+1>=args.length)throw new IllegalArgumentException("Missing value for "+flag);String value=args[++i];if("--validate-selection".equals(flag)&&file==null)file=new File(value);else if("--max-columns".equals(flag)&&limit==null)limit=new BigInteger(value);else throw new IllegalArgumentException("Unknown or duplicate option: "+flag);}if(file==null||limit==null)throw new IllegalArgumentException("Required: --validate-selection FILE --max-columns N");out.println("Valid selection: "+validate(file,limit)+" columns");return 0;}catch(Exception e){err.println(e.getMessage());return 1;}}
}
