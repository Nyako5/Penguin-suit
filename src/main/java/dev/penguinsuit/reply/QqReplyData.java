package dev.penguinsuit.reply;

import com.huhobot.penguin.qq.GroupMessage;
import java.util.*;
import java.util.function.Supplier;

/** Retains only quote fields, never auth_token, signed URLs or a raw event. */
public record QqReplyData(String currentReference,String targetReference,String quotedSender,String quotedText,
                          boolean reply,boolean unsupportedBody,String body,String quotedOpenid,String quotedSourceId,
                          boolean quotedBot,String quoteShape,String eventTrace) {
    public QqReplyData(String currentReference,String targetReference,String quotedSender,String quotedText,
                       boolean reply,boolean unsupportedBody,String body,String quotedOpenid,String quotedSourceId,boolean quotedBot,String quoteShape){
        this(currentReference,targetReference,quotedSender,quotedText,reply,unsupportedBody,body,quotedOpenid,quotedSourceId,quotedBot,quoteShape,"");
    }
    public QqReplyData(String currentReference,String targetReference,String quotedSender,String quotedText,
                       boolean reply,boolean unsupportedBody,String body){
        this(currentReference,targetReference,quotedSender,quotedText,reply,unsupportedBody,body,"","",false,"unspecified");
    }
    public static final QqReplyData NONE=new QqReplyData("","","","",false,false,"");
    private record Context(GroupMessage message,QqReplyData data){}
    private static final ThreadLocal<Context> CURRENT=new ThreadLocal<>();
    public static QqReplyData capture(GroupMessage message){Context c=CURRENT.get();return c!=null&&c.message()==message?c.data():NONE;}
    public static <T>T during(GroupMessage message,QqReplyData data,Supplier<T> action){
        Context previous=CURRENT.get();CURRENT.set(new Context(message,data));try{return action.get();}finally{if(previous==null)CURRENT.remove();else CURRENT.set(previous);}
    }
    public static QqReplyData parse(Map<?,?> event){
        return parse(event,false);
    }
    public static QqReplyData parse(Map<?,?> event,boolean diagnostics){
        String own="",target="";
        if(event.get("message_scene") instanceof Map<?,?> scene&&scene.get("ext") instanceof List<?> ext)
            for(Object value:ext.stream().limit(64).toList())if(value instanceof String s){
                int eq=s.indexOf('=');if(eq<0)continue;String key=s.substring(0,eq),v=s.substring(eq+1);
                if(v.length()>4096)continue;if(key.equals("msg_idx"))own=v;else if(key.equals("ref_msg_idx"))target=v;
            }
        if(target.isEmpty()&&event.get("message_reference") instanceof Map<?,?> ref)target=string(ref,"message_id",4096);
        String content=string(event,"content",8192);
        boolean isReply=!target.isBlank()||number(event.get("message_type"))==103;
        Quoted quote=find(event.get("msg_elements"),target,0,new int[]{0});
        if(!quote.found()&&event.get("message_reference") instanceof Map<?,?> reference)
            quote=extract(reference,target,0);
        String shape=quote.conflict()?"conflict":quote.found()?"structured":"missing";
        // QQ's parallel view contains the quoted message's display body, including replies to
        // replies that are flattened in msg_elements. It does NOT establish an author or ID.
        if(isReply&&!quote.conflict()&&quote.text().isBlank()){
            String preview=parallelPreview(event.get("parallel_message"));
            if(!preview.isBlank()){
                quote=new Quoted(quote.sender(),quote.openid(),quote.source(),preview,quote.bot(),true,false);
                shape="parallel_preview";
            }
        }
        if(event.get("msg_elements") instanceof List<?> elements){
            for(Object item:elements.stream().limit(32).toList())if(item instanceof Map<?,?> element){
                String idx=string(element,"msg_idx",4096);
                if(content.isBlank()&&!own.isEmpty()&&idx.equals(own)&&number(element.get("message_type"))!=103){
                    content=string(element,"content",8192);
                }
            }
        }
        boolean attachments=event.get("attachments") instanceof List<?> list&&!list.isEmpty();
        boolean unsupported=isReply&&content.isBlank()&&!attachments&&event.get("msg_elements") instanceof List<?> list&&!list.isEmpty();
        // Flattened quote transcripts are not the new author's body. Never replay them as fresh @ or commands.
        if(unsupported)content="（QQ 回复消息正文暂无法解析）";
        return new QqReplyData(own,target,HiddenReplyId.bounded(quote.sender(),128),HiddenReplyId.bounded(quote.text(),8192),isReply,unsupported,content,
                quote.openid(),quote.source(),quote.bot(),shape,
                diagnostics?QqReplyTrace.describe(event):"");
    }
    private static String parallelPreview(Object value){
        if(!(value instanceof Map<?,?> parallel)||!(parallel.get("msg_nodes") instanceof List<?> nodes)
                ||nodes.size()!=1||!(nodes.getFirst() instanceof Map<?,?> node))return "";
        // Confirmed parallel-view variants: 0=text, 7=media placeholder. Do not concatenate
        // multiple nodes, recurse into history, or treat a flattened 103 transcript as text.
        int type=number(node.get("message_type"));
        return type==0||type==7?string(node,"content",8192):"";
    }
    private record Quoted(String sender,String openid,String source,String text,boolean bot,boolean found,boolean conflict){
        static final Quoted NONE=new Quoted("","","","",false,false,false);
    }
    /** Follow only the selected quote element; do not borrow the new author's identity or quoted siblings. */
    private static Quoted find(Object value,String target,int depth,int[] count){
        if(target.isBlank()||depth>8||!(value instanceof List<?> list))return Quoted.NONE;
        Quoted found=Quoted.NONE;
        for(Object item:list){
            if(++count[0]>64)break;if(!(item instanceof Map<?,?> element))continue;
            Quoted q=target.equals(string(element,"msg_idx",4096))?extract(element,target,0):find(element.get("msg_elements"),target,depth+1,count);
            if(q.found()){
                if(found.found()&&!found.equals(q))return new Quoted("","","","",false,true,true);
                found=q;
            }
        }
        return found;
    }
    private static Quoted extract(Map<?,?> element,String target,int depth){
        Map<?,?> author=element.get("author") instanceof Map<?,?> a?a:Map.of();
        String name=first(author,128,"username","nickname","nick","name"),id=first(author,256,"member_openid","id","user_openid");
        String source=first(element,4096,"id","message_id"),text=string(element,"content",8192);
        boolean bot=Boolean.TRUE.equals(author.get("bot"));
        if(depth<8&&element.get("msg_elements") instanceof List<?> children&&children.size()==1&&children.getFirst() instanceof Map<?,?> child){
            String childIdx=string(child,"msg_idx",4096);
            if(childIdx.isBlank()||childIdx.equals(target)){
                Quoted nested=extract(child,target,depth+1);
                if((!id.isBlank()&&!nested.openid().isBlank()&&!id.equals(nested.openid()))||nested.conflict())
                    return new Quoted("","","","",false,true,true);
                if(text.isBlank()||text.equals(nested.text())){
                    if(name.isBlank())name=nested.sender();if(id.isBlank())id=nested.openid();
                    if(source.isBlank())source=nested.source();if(text.isBlank())text=nested.text();bot|=nested.bot();
                }
            }
        }
        return new Quoted(name,id,source,text,bot,!name.isBlank()||!id.isBlank()||!source.isBlank()||!text.isBlank(),false);
    }
    private static String first(Map<?,?> map,int max,String... keys){for(String key:keys){String value=string(map,key,max);if(!value.isBlank())return value;}return "";}
    private static int number(Object value){return value instanceof Number n?n.intValue():-1;}
    private static String string(Map<?,?> map,String key,int max){Object v=map.get(key);return v instanceof String s?s.substring(0,Math.min(max,s.length())):"";}
    public GroupMessage normalize(GroupMessage original){
        if(!reply||body.equals(original.getContent()))return original;
        return new GroupMessage(original.getId(),original.getGroupId(),body,original.getUserId(),original.getUsername(),original.getMemberRole(),original.getTimestamp(),original.getAttachments());
    }
}
