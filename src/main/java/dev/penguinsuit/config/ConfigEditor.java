package dev.penguinsuit.config;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Only known bridge keys can be edited; unrelated JSON keys remain intact. */
public final class ConfigEditor {
    public static Set<String> keys(){Set<String> keys=new TreeSet<>();collect("",JsonParser.parseString(BridgeConfig.defaultsJson()).getAsJsonObject(),keys);keys.remove("schemaVersion");return keys;}
    private static void collect(String prefix,JsonObject o,Set<String> keys){for(var e:o.entrySet()){String key=prefix+e.getKey();if(e.getValue().isJsonObject()&&!key.equals("gametoQQ.events.translations"))collect(key+".",e.getValue().getAsJsonObject(),keys);else keys.add(key);}}
    private static JsonObject document(Path file)throws IOException{var doc=BridgeConfig.readDocument(file);return BridgeConfig.complete(doc,BridgeConfig.decode(doc));}
    public static String canonicalKey(String key){return ConfigLayout.canonicalKey(key);}
    public static boolean requiresRestart(String key){key=canonicalKey(key);return key.equals("QQtogame.queue.workerThreads")||key.equals("QQtogame.queue.capacity");}
    private static JsonObject parent(JsonObject doc,String key)throws IOException{if(!keys().contains(key))throw new IOException("Unknown configuration key: "+key);String[] path=key.split("\\.");for(int i=0;i<path.length-1;i++)doc=doc.getAsJsonObject(path[i]);return doc;}
    public static String get(Path file,String key)throws IOException{key=canonicalKey(key);var doc=document(file);return parent(doc,key).get(key.substring(key.lastIndexOf('.')+1)).toString();}
    public static void set(Path file,String key,String value)throws IOException{
        key=canonicalKey(key);var doc=document(file);var parent=parent(doc,key);String leaf=key.substring(key.lastIndexOf('.')+1);JsonElement old=parent.get(leaf),next;
        try{next=JsonParser.parseString(value);}catch(RuntimeException e){next=new JsonPrimitive(value);}
        if(old.isJsonPrimitive()&&old.getAsJsonPrimitive().isString()&&(!next.isJsonPrimitive()||!next.getAsJsonPrimitive().isString()))next=new JsonPrimitive(value);
        if(!sameType(old,next))throw new IOException("Value type differs from configuration field");
        parent.add(leaf,next);
        BridgeConfig.decode(doc);
        BridgeConfig.writeDocument(file,doc,Files.readString(file));
    }
    private static boolean sameType(JsonElement a,JsonElement b){if(b==null||b.isJsonNull())return false;if(a.isJsonObject())return b.isJsonObject();if(a.isJsonArray())return b.isJsonArray();if(!b.isJsonPrimitive())return false;var x=a.getAsJsonPrimitive();var y=b.getAsJsonPrimitive();return x.isString()==y.isString()&&x.isBoolean()==y.isBoolean()&&x.isNumber()==y.isNumber();}
    private ConfigEditor(){}
}
