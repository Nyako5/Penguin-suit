package dev.penguinsuit.mention;

import com.huhobot.penguin.state.BotState;
import java.lang.reflect.Field;
import java.util.*;

/** Pinned 1.1.7 read adapter; mutations use the upstream bind/unbind API. */
public final class PenguinBindings {
    private static final Field DATA, BINDINGS, FILE;
    private static final ThreadLocal<Boolean> OWN_WRITE=ThreadLocal.withInitial(()->false);
    public static boolean ownWrite(){return OWN_WRITE.get();}
    static {
        try {
            DATA=BotState.class.getDeclaredField("data"); DATA.setAccessible(true);
            BINDINGS=DATA.getType().getDeclaredField("bindings"); BINDINGS.setAccessible(true);
            FILE=BotState.class.getDeclaredField("file");FILE.setAccessible(true);
        } catch(ReflectiveOperationException e) { throw new ExceptionInInitializerError(e); }
    }
    public static void change(BotState state,String group,String member,String expected,String next)throws java.io.IOException{
        synchronized(state){
            Map<String,String> before=snapshot(state,group);
            if(!Objects.equals(before.get(member),expected))throw new java.io.IOException("Penguin mirror changed concurrently");
            if(next!=null&&before.entrySet().stream().anyMatch(e->!e.getKey().equals(member)&&e.getValue().equalsIgnoreCase(next)))
                throw new java.io.IOException("Penguin mirror has duplicate game name");
            OWN_WRITE.set(true);
            try{
                if(next==null)state.unbindOpenid(group,member);else state.bindName(group,member,next);
                var path=((java.io.File)FILE.get(state)).toPath();
                // Penguin uses File.renameTo, which does not replace an existing file on Windows.
                // Only promote its own completed temp snapshot after checking it against live state.
                var tmp=path.resolveSibling(path.getFileName()+".tmp");
                if(java.nio.file.Files.exists(tmp)){
                    var staged=com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(tmp));
                    var current=new com.google.gson.Gson().toJsonTree(DATA.get(state));
                    if(!staged.equals(current))throw new java.io.IOException("Penguin staged snapshot does not match current state");
                    try(var channel=java.nio.channels.FileChannel.open(tmp,java.nio.file.StandardOpenOption.WRITE)){channel.force(true);}
                    try{java.nio.file.Files.move(tmp,path,java.nio.file.StandardCopyOption.ATOMIC_MOVE,java.nio.file.StandardCopyOption.REPLACE_EXISTING);}
                    catch(java.nio.file.AtomicMoveNotSupportedException e){java.nio.file.Files.move(tmp,path,java.nio.file.StandardCopyOption.REPLACE_EXISTING);}
                }
                var root=com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(path)).getAsJsonObject();
                var all=root.getAsJsonObject("bindings");
                var saved=all==null?null:all.getAsJsonObject(group);
                String value=saved==null||!saved.has(member)?null:saved.get(member).getAsString();
                if(!Objects.equals(value,next)||!Objects.equals(snapshot(state,group).get(member),next))
                    throw new java.io.IOException("Penguin mirror durable readback failed");
            }catch(ReflectiveOperationException|RuntimeException e){throw new java.io.IOException("Cannot verify Penguin mirror",e);}
            finally{OWN_WRITE.remove();}
        }
    }
    public static Map<String,String> snapshot(BotState state,String group) {
        synchronized(state) {
            try {
                Object raw=((Map<?,?>)BINDINGS.get(DATA.get(state))).get(group);
                if(!(raw instanceof Map<?,?> map)) return Map.of();
                Map<String,String> result=new HashMap<>();
                map.forEach((k,v) -> { if(k instanceof String key && v instanceof String value)result.put(key,value); });
                return Map.copyOf(result);
            } catch(IllegalAccessException e) { throw new IllegalStateException("Penguin binding adapter unavailable",e); }
        }
    }
    private PenguinBindings() {}
}
