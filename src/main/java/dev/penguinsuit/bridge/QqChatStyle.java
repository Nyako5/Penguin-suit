package dev.penguinsuit.bridge;

import dev.penguinsuit.config.RuntimeSettings;
import dev.penguinsuit.mention.MentionMessage;
import dev.penguinsuit.reply.HiddenReplyId;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.*;

/** QQ body only. Components are used for MC chat; CR captions and replies require strings. */
public final class QqChatStyle {
    private QqChatStyle() {}
    public static String audit(String body) { return HiddenReplyId.plain(body); }
    public static String afterAudit(String styled, String submitted, String filtered) {
        return submitted.equals(filtered) ? styled : filtered;
    }
    public static MutableComponent component(MentionMessage.Rendered message) {
        var result=Component.empty();
        walk(message,(text,style,codes)->result.append(Component.literal(text).setStyle(style)));
        return result;
    }
    public static String wire(MentionMessage.Rendered message) {
        var result=new StringBuilder(); String[] last={""};
        walk(message,(text,style,codes)->{
            if(!codes.equals(last[0])) {
                if(codes.startsWith(last[0]))result.append(codes.substring(last[0].length()));
                else {if(codes.isEmpty()||"0123456789abcdef".indexOf(codes.charAt(1))<0)result.append("§r");result.append(codes);}
                last[0]=codes;
            }
            result.append(text);
        });
        if(!last[0].isEmpty())result.append("§r");
        return result.toString();
    }
    public static String wire(String body) {return wire(new MentionMessage.Rendered(java.util.List.of(new MentionMessage.Part(body,null)),java.util.Set.of(),java.util.Set.of(),java.util.Map.of()));}
    private interface Sink {void add(String text, Style style, String codes);}
    private static void walk(MentionMessage.Rendered message,Sink sink) {
        Style style=Style.EMPTY; String codes="";
        for(var part:message.parts()) {
            String text=HiddenReplyId.strip(part.text());
            if(part.target()!=null) {
                var color=part.target().online()?RuntimeSettings.color():null;
                sink.add(text,color==null?Style.EMPTY:Style.EMPTY.applyLegacyFormat(color),color==null?"":color.toString());continue;
            }
            int start=0;
            for(int i=0;i+1<text.length();i++) {
                if(text.charAt(i)!='§')continue;
                char c=Character.toLowerCase(text.charAt(i+1));var f=ChatFormatting.getByCode(c);if(f==null)continue;
                if(i>start)sink.add(text.substring(start,i),style,codes);
                style=style.applyLegacyFormat(f);
                if(f==ChatFormatting.RESET)codes="";
                else if("0123456789abcdef".indexOf(c)>=0)codes=f.toString();
                else if(!codes.contains(f.toString()))codes+=f.toString();
                start=++i+1;
            }
            if(start<text.length())sink.add(text.substring(start),style,codes);
        }
    }
}
