package dev.penguinsuit.config;

import com.google.gson.*;
import java.io.IOException;
import java.lang.reflect.*;
import java.util.*;

/** Check JSON before Gson can coerce strings, booleans or fractional integers. Unknown keys survive migration. */
final class ConfigTypes {
    static void validate(JsonElement json) throws IOException { check(json, BridgeConfig.class, "config"); }
    private static void check(JsonElement json, Type type, String path) throws IOException {
        if(json==null||json.isJsonNull())throw invalid(path);
        if(type instanceof ParameterizedType p){
            if(p.getRawType()==List.class){
                if(!json.isJsonArray())throw invalid(path);
                for(var item:json.getAsJsonArray())check(item,p.getActualTypeArguments()[0],path+"[]");
            }else if(p.getRawType()==Map.class){
                if(!json.isJsonObject())throw invalid(path);
                for(var entry:json.getAsJsonObject().entrySet())check(entry.getValue(),p.getActualTypeArguments()[1],path+"."+entry.getKey());
            }else throw invalid(path);
            return;
        }
        Class<?> c=(Class<?>)type;
        if(c==String.class){if(!json.isJsonPrimitive()||!json.getAsJsonPrimitive().isString())throw invalid(path);return;}
        if(c==boolean.class){if(!json.isJsonPrimitive()||!json.getAsJsonPrimitive().isBoolean())throw invalid(path);return;}
        if(c==int.class||c==long.class||c==float.class){
            if(!json.isJsonPrimitive()||!json.getAsJsonPrimitive().isNumber())throw invalid(path);
            try {var number=json.getAsBigDecimal();if(c==int.class)number.intValueExact();else if(c==long.class)number.longValueExact();else if(!Float.isFinite(number.floatValue()))throw invalid(path);}
            catch(ArithmeticException|NumberFormatException e){throw invalid(path);}return;
        }
        if(!json.isJsonObject())throw invalid(path);
        for(Field field:c.getFields())if(!Modifier.isStatic(field.getModifiers())&&!Modifier.isTransient(field.getModifiers())&&json.getAsJsonObject().has(field.getName()))
            check(json.getAsJsonObject().get(field.getName()),field.getGenericType(),path+"."+field.getName());
    }
    private static IOException invalid(String path){return new IOException("Invalid JSON value type or integer range: "+path);}
    private ConfigTypes(){}
}
