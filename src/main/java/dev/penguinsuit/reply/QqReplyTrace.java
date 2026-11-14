package dev.penguinsuit.reply;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** Bounded, value-redacted event structure for diagnosing opaque QQ quote references. Not an identity resolver. */
public final class QqReplyTrace {
    public static final int MAX_LENGTH=8192;
    private static final Set<String> IDS=Set.of("id","message_id","msg_id","member_openid","user_openid","union_openid","openid","sender_id","author_id","group_openid");
    private static final Set<String> REFERENCES=Set.of("msg_idx","ref_msg_idx","ref_idx");
    private final StringBuilder out=new StringBuilder();
    private int nodes;
    private boolean truncated;
    public static String describe(Map<?,?> event){
        var trace=new QqReplyTrace();trace.node(event,"event",0);
        String result=trace.out.toString();
        if(trace.truncated||result.length()>MAX_LENGTH)result=result.substring(0,Math.min(result.length(),MAX_LENGTH-14))+" [trace-limit]";
        return result;
    }
    private static boolean sensitive(String key){
        String k=key.toLowerCase(Locale.ROOT);
        return k.contains("token")||k.contains("secret")||k.contains("password")||k.contains("authorization")||k.contains("cookie")||k.contains("url");
    }
    private static String key(Object value){return value instanceof String s&&s.matches("[A-Za-z_][A-Za-z0-9_]{0,47}")?s:"unknown_key";}
    private static String digest(String s){return UUID.nameUUIDFromBytes(s.substring(0,Math.min(8192,s.length())).getBytes(StandardCharsets.UTF_8)).toString().substring(0,8);}
    private static String tag(String value){return (value.startsWith("REFIDX_")?"REFIDX":value.startsWith("TMP_")?"TMP":"other")+"/"+digest(value);}
    private static String type(Object value){
        return value==null?"null":value instanceof Map?"object":value instanceof List<?> l?"array("+l.size()+")":value instanceof String s?"string("+s.length()+")":value instanceof Boolean?"bool":value instanceof Number?"number":"other";
    }
    private String value(String key,Object value){
        if(value instanceof String s){
            if(REFERENCES.contains(key))return tag(s)+"(len="+s.length()+")";
            if(IDS.contains(key)||key.equals("content"))return "hash/"+digest(s)+"(len="+s.length()+")";
            if(key.equals("timestamp")&&s.matches("[0-9TtZz:+.\\-]{10,40}"))return s;
        }
        if(key.equals("bot")&&value instanceof Boolean b)return b.toString();
        if(key.equals("message_type")&&value instanceof Number n&&n.intValue()>=0&&n.intValue()<1000)return Integer.toString(n.intValue());
        return type(value);
    }
    private void node(Object value,String path,int depth){
        if(depth>8||++nodes>64||out.length()>MAX_LENGTH){truncated=true;return;}
        if(value instanceof Map<?,?> map){
            out.append(path).append('{');int count=0;
            for(var entry:map.entrySet()){
                if(++count>32){truncated=true;break;}
                String key=key(entry.getKey());if(sensitive(key))continue;
                out.append(key).append('=').append(value(key,entry.getValue())).append(',');
            }
            out.append("}; ");
            count=0;
            for(var entry:map.entrySet()){
                if(++count>32){truncated=true;break;}
                String key=key(entry.getKey());
                if(sensitive(key))continue;
                // Unknown nested structures are exactly what this diagnostic must expose.
                // Values remain redacted; the shared depth/node/length limits still apply.
                Object child=entry.getValue();
                if(key.equals("ext")&&child instanceof List<?> list)ext(list,path+".ext");
                else if(child instanceof Map<?,?>||child instanceof List<?>)node(child,path+'.'+key,depth+1);
                if(nodes>=64||out.length()>MAX_LENGTH){truncated=true;break;}
            }
        }else if(value instanceof List<?> list){
            for(int i=0;i<Math.min(32,list.size());i++){
                node(list.get(i),path+'['+i+']',depth+1);
                if(nodes>=64||out.length()>MAX_LENGTH){truncated=true;break;}
            }
            if(list.size()>32)truncated=true;
        }else out.append(path).append('=').append(type(value)).append("; ");
    }
    private void ext(List<?> list,String path){
        out.append(path).append('{');
        for(Object item:list.stream().limit(32).toList())if(item instanceof String s){
            int eq=s.indexOf('=');if(eq<0)continue;String key=key(s.substring(0,eq));
            if(REFERENCES.contains(key))out.append(key).append('=').append(tag(s.substring(eq+1))).append(',');
        }
        out.append("}; ");if(list.size()>32)truncated=true;
    }
    private QqReplyTrace(){}
}
