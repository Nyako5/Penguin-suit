package dev.penguinsuit.reply;

import java.util.*;
import java.util.regex.*;

/** Minecraft formatting-only envelope. Never changes a signed chat body or emits a glyph. */
public final class HiddenReplyId {
    private static final String START="§r§r", END="§r";
    private static final Pattern TOKEN=Pattern.compile(START+"((?:§[0-9a-f]){32})"+END);
    // Read beta.1 quotes from existing client history, but never emit its font-dependent characters.
    private static final String OLD_START="\u2063\u2060", OLD_END="\u2060\u2063";
    private static final Pattern OLD_TOKEN=Pattern.compile(OLD_START+"([\uFE00-\uFE0F]{32})"+OLD_END);
    public static final int LENGTH=70;
    /** Reserved non-message identity: a preview without an original must never match a real chat line. */
    public static final UUID UNRESOLVED=new UUID(0,0);
    public record Parsed(String text,UUID id,boolean malformed) {}
    public static String encode(UUID id){
        String hex=id.toString().replace("-","");StringBuilder out=new StringBuilder(START);
        for(int i=0;i<hex.length();i++)out.append('§').append(hex.charAt(i));
        return out.append(END).toString();
    }
    public static Parsed parse(String value){
        if(value==null)return new Parsed("",null,false);
        UUID id=null;int count=0;
        for(Pattern pattern:List.of(TOKEN,OLD_TOKEN)){
            Matcher m=pattern.matcher(value);
            while(m.find()){
                count++;StringBuilder h=new StringBuilder();
                if(pattern==TOKEN)for(int i=1;i<m.group(1).length();i+=2)h.append(m.group(1).charAt(i));
                else for(char c:m.group(1).toCharArray())h.append(Integer.toHexString(c-0xFE00));
                String hex=h.toString();UUID next=UUID.fromString(hex.substring(0,8)+"-"+hex.substring(8,12)+"-"+hex.substring(12,16)+"-"+hex.substring(16,20)+"-"+hex.substring(20));
                if(id==null)id=next;else if(!id.equals(next))return new Parsed(strip(value),null,true);
            }
        }
        String clean=strip(value);
        return new Parsed(clean,id,count>1||clean.contains(START)||clean.contains(OLD_START)||clean.contains(OLD_END));
    }
    public static String strip(String value){return value==null?"":OLD_TOKEN.matcher(TOKEN.matcher(value).replaceAll("")).replaceAll("");}
    /** Do not truncate visible content to make space. Unmarked long quotes use unique matching. */
    public static String decorate(String text,UUID id){
        String clean=strip(text);return clean.length()+LENGTH<=256?encode(id)+clean:clean;
    }
    public static String unresolved(String preview){return encode(UNRESOLVED)+bounded(preview,256-LENGTH);}
    public static String plain(String text){return strip(text).replaceAll("§[0-9a-fk-orA-FK-OR]","");}
    public static String bounded(String text,int max){
        String s=plain(text);if(s.length()<=max)return s;
        int end=max;if(end>0&&Character.isHighSurrogate(s.charAt(end-1)))end--;return s.substring(0,end);
    }
    private HiddenReplyId(){}
}
