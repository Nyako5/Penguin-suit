package dev.penguinsuit.emoji;

import com.google.gson.JsonParser;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

/** Restore names BEFORE text moderation; never reintroduce content removed by moderation. */
public final class QqEmojiMessage {
    private static final Pattern TAG=Pattern.compile("<faceType=([^<>]*)>");
    private static final Pattern FIELDS=Pattern.compile("\\s*([0-9]{1,2})\\s*,\\s*faceId\\s*=\\s*(?:\"([^\"<>]{0,128})\"|([0-9]{1,12}))(?:\\s*,\\s*ext\\s*=\\s*\"([^\"<>]{0,2048})\")?\\s*");
    public record Occurrence(int start,int end,QqEmojiCatalog.Face face) {}
    public record Parsed(String text,List<QqEmojiCatalog.Face> faces,int count,int unknown,List<Occurrence> occurrences) {
        public Plan audit(String audited){return new Plan(faces,count,unknown,audited,text,occurrences);}
    }
    public record Plan(List<QqEmojiCatalog.Face> faces,int count,int unknown,String auditText,String sourceText,List<Occurrence> occurrences) {
        public static final Plan EMPTY=new Plan(List.of(),0,0,"","",List.of());
        public List<URI> images(String filtered){
            if(!auditText.equals(filtered))return List.of();
            return faces.stream().map(QqEmojiCatalog.Face::source).filter(Objects::nonNull).toList();
        }
        public boolean emojiOnlyMedia(List<?> attachments){return count>0&&attachments.isEmpty();}
        /** Only successful generated occurrences disappear from the media caption. Never search/replace labels. */
        public String caption(dev.penguinsuit.mention.MentionMessage.Rendered rendered,String current,Set<Integer> successful) {
            if(successful.isEmpty())return current;
            var parts=rendered==null?List.of(new dev.penguinsuit.mention.MentionMessage.Part(sourceText,null,0)):rendered.parts();
            if(rendered==null&&!sourceText.equals(auditText))return current;
            String original=rendered==null?sourceText:dev.penguinsuit.bridge.QqChatStyle.wire(rendered);
            if(!dev.penguinsuit.media.AttachmentResolver.cleanCaption(original).equals(current))return current;
            List<dev.penguinsuit.mention.MentionMessage.Part> result=new ArrayList<>();
            for(var part:parts){
                if(part.target()!=null||part.sourceStart()<0){result.add(part);continue;}
                StringBuilder text=new StringBuilder(part.text());
                for(int i=occurrences.size()-1;i>=0;i--){
                    var o=occurrences.get(i);int from=o.start()-part.sourceStart(),to=o.end()-part.sourceStart();
                    if(successful.contains(i)&&from>=0&&to<=part.text().length()
                            &&part.text().substring(from,to).equals(o.face().label()))text.delete(from,to);
                }
                result.add(new dev.penguinsuit.mention.MentionMessage.Part(text.toString(),null));
            }
            var compact=new dev.penguinsuit.mention.MentionMessage.Rendered(result,Set.of(),Set.of(),Map.of());
            return dev.penguinsuit.media.AttachmentResolver.cleanCaption(dev.penguinsuit.bridge.QqChatStyle.wire(compact));
        }
    }
    public static Parsed untouched(String text){return new Parsed(text,List.of(),0,0,List.of());}
    public static Parsed parse(String text) {
        if(text==null)return untouched("");
        Matcher matcher=TAG.matcher(text);StringBuilder out=new StringBuilder();
        List<QqEmojiCatalog.Face> faces=new ArrayList<>();List<Occurrence> occurrences=new ArrayList<>();int count=0,unknown=0;
        while(matcher.find()) {
            Matcher f=FIELDS.matcher(matcher.group(1));String replacement="[未知表情]";QqEmojiCatalog.Face selected=null;
            if(f.matches()) {
                int type=Integer.parseInt(f.group(1));String id=f.group(2)!=null?f.group(2):f.group(3);
                // Type 6 is an attachment marker, not a system emoji. Existing image handling owns it.
                if(type==6){matcher.appendReplacement(out,Matcher.quoteReplacement(matcher.group()));continue;}
                String name=extName(f.group(4));QqEmojiCatalog.Face face=null;
                if(type>=1&&type<=4) {
                    if(!id.isBlank())face=QqEmojiCatalog.byId(id,name);
                    else if(!name.isEmpty()){
                        face=QqEmojiCatalog.byName(name);
                        if(face==null&&!name.matches("[0-9]+"))face=QqEmojiCatalog.byId(name);
                    }
                }
                if(face!=null) {
                    // Some Unicode emoji ext values contain the character rather than its Chinese name.
                    var named=QqEmojiCatalog.byId(name);
                    if(!name.isEmpty()&&!name.equals(face.name())&&(named==null||!named.id().equals(face.id())))face=null;
                }
                if(face!=null) {
                    replacement=face.label();if(count<64&&face.source()!=null){faces.add(face);selected=face;}
                } else {
                    unknown++;
                    replacement=!name.isEmpty()?"["+name+"]":id.matches("[0-9]{1,12}")?"[QQ表情:"+id+"]":"[未知表情]";
                }
            }else unknown++;
            count++;
            matcher.appendReplacement(out,Matcher.quoteReplacement(replacement));
            if(selected!=null)occurrences.add(new Occurrence(out.length()-replacement.length(),out.length(),selected));
        }
        matcher.appendTail(out);
        return new Parsed(out.toString(),List.copyOf(faces),count,unknown,List.copyOf(occurrences));
    }
    /** Diagnostic schema/emoji IDs only: no user IDs, ext/base64, message text or URLs. */
    public static String shape(String text){
        Matcher m=TAG.matcher(text);List<String> shapes=new ArrayList<>();
        while(m.find()&&shapes.size()<8){
            Matcher f=FIELDS.matcher(m.group(1));
            if(!f.matches()){shapes.add("malformed");continue;}
            if(f.group(1).equals("6"))continue;
            String id=f.group(2)!=null?f.group(2):f.group(3);
            shapes.add("type="+f.group(1)+"/id="+(id.isEmpty()?"empty":id.matches("[0-9]{1,7}")?id:"non-numeric"));
        }
        return String.join(",",shapes);
    }
    private static String extName(String ext) {
        if(ext==null||ext.isEmpty()||ext.length()>2048)return "";
        try {
            byte[] bytes=Base64.getDecoder().decode(ext);
            String json=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            // Reject deep JSON before parsing. ext is an object of scalar metadata, not a document.
            int depth=0;boolean quoted=false,escaped=false;
            for(char c:json.toCharArray()){
                if(escaped){escaped=false;continue;}if(quoted&&c=='\\'){escaped=true;continue;}
                if(c=='\"'){quoted=!quoted;continue;}if(quoted)continue;
                if(c=='{'||c=='['){if(++depth>4)return "";}else if(c=='}'||c==']')depth--;
            }
            var root=JsonParser.parseString(json);
            if(!root.isJsonObject())return "";var value=root.getAsJsonObject().get("text");
            if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isString())return "";
            String name=value.getAsString().strip();
            if(name.startsWith("[")&&name.endsWith("]"))name=name.substring(1,name.length()-1);
            if(name.startsWith("/"))name=name.substring(1);
            if(name.isEmpty()||name.codePointCount(0,name.length())>64)return "";
            StringBuilder safe=new StringBuilder();
            name.codePoints().forEach(cp->{
                if(Character.isISOControl(cp)||Character.getType(cp)==Character.FORMAT||cp=='§')return;
                // Names are inert text: never synthesize a native/explicit @, tag, command or log line.
                safe.appendCodePoint(switch(cp){case '<'->'＜';case '>'->'＞';case '@'->'＠';case '#'->'＃';case '['->'［';case ']'->'］';default->cp;});
            });
            return safe.toString().replaceAll("\\s+"," ").strip();
        }catch(Exception e){return "";}
    }
    private QqEmojiMessage(){}
}
