package dev.penguinsuit.config;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** JSON with line/block comments. Comment markers inside quoted strings remain data. */
public final class ConfigComments {
    private static final Gson JSON=new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final String MARKER="// Penguin suit 配置说明";
    private static final List<String> HEADER=List.of(MARKER,
            "// 保存后执行 /penguinsuit reload 以重载");
    private static final String CUSTOM="// 自定义注释备忘（保留原文；与下方设置分别维护）：";
    private static final Map<String,String> NOTES=loadNotes();
    private static final Set<String> GENERATED=generated();
    private record Scanned(String json,List<String> comments){}

    private static Map<String,String> loadNotes(){
        try(var stream=ConfigComments.class.getResourceAsStream("/config-comments-zh_cn.json")){
            if(stream==null)throw new IOException("Missing configuration comments resource");
            var object=JsonParser.parseString(new String(stream.readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();
            Map<String,String> notes=new LinkedHashMap<>();
            for(var entry:object.entrySet())notes.put(entry.getKey(),entry.getValue().getAsString());
            return Collections.unmodifiableMap(notes);
        }catch(IOException|RuntimeException e){throw new ExceptionInInitializerError(e);}
    }
    private static Set<String> generated(){
        Set<String> result=new HashSet<>(HEADER);result.add(CUSTOM);
        result.add("// Penguin suit 配置说明 v1");
        result.add("// 此文件使用带注释的 JSON（JSONC），文件名仍为 penguin-suit.json。");
        result.add("// Penguin Media Bridge 配置说明 v1");
        result.add("// Penguin Media Bridge 配置说明 v2");
        result.add("// 此文件使用带注释的 JSON（JSONC），文件名仍为 penguin-media-bridge.json。");
        result.add("// 此文件使用带注释的 JSON（JSONC），文件名为 penguin-media-bridge.jsonc。");
        result.add("// 旧 .json 仅在 .jsonc 不存在时读取并迁移；两者同时存在时只使用 .jsonc。");
        result.add("// 保存后执行 /penguinmedia reload；下载线程数、等待队列容量以及更换 JAR 需要重启。");
        result.add("// Penguin suit 配置说明");
        result.add("// 保存后执行 /penguinsuit reload 以重载");
        try(var stream=ConfigComments.class.getResourceAsStream("/legacy-config-comments-zh_cn.json")){
            if(stream!=null)for(var note:JsonParser.parseString(new String(stream.readAllBytes(),StandardCharsets.UTF_8)).getAsJsonArray())note.getAsString().lines().forEach(line->result.add("// "+line));
        }catch(IOException e){throw new ExceptionInInitializerError(e);}
        NOTES.values().forEach(note->note.lines().forEach(line->result.add("// "+line)));return Set.copyOf(result);
    }
    static Set<String> documentedKeys(){return NOTES.keySet();}
    public static JsonElement parse(String text)throws IOException{
        try{return JsonParser.parseString(scan(text).json());}
        catch(RuntimeException e){throw new IOException("Invalid bridge JSON with comments",e);}
    }
    private static String commentKey(String comment){
        String value=comment.strip();return value.startsWith("//")?"// "+value.substring(2).stripLeading():value;
    }
    static boolean documented(String text)throws IOException{
        var comments=scan(text).comments().stream().map(ConfigComments::commentKey).collect(java.util.stream.Collectors.toSet());
        return comments.containsAll(HEADER);
    }

    private static Scanned scan(String text)throws IOException{
        var clean=new StringBuilder(text);List<String> comments=new ArrayList<>();boolean quoted=false,escaped=false;
        if(!text.isEmpty()&&text.charAt(0)=='\uFEFF')clean.setCharAt(0,' ');
        for(int i=0;i<text.length();i++){
            char c=text.charAt(i);
            if(quoted){if(escaped)escaped=false;else if(c=='\\')escaped=true;else if(c=='"')quoted=false;continue;}
            if(c=='"'){quoted=true;continue;}
            if(c!='/'||i+1>=text.length())continue;
            char next=text.charAt(i+1);if(next!='/'&&next!='*')continue;
            int end=i+2;
            if(next=='/')while(end<text.length()&&text.charAt(end)!='\n'&&text.charAt(end)!='\r')end++;
            else{
                int closing=text.indexOf("*/",end);if(closing<0)throw new IOException("Unterminated block comment in bridge configuration");
                end=closing+2;
            }
            comments.add(text.substring(i,end));
            for(int j=i;j<end;j++)if(text.charAt(j)!='\n'&&text.charAt(j)!='\r')clean.setCharAt(j,' ');
            i=end-1;
        }
        return new Scanned(clean.toString(),List.copyOf(comments));
    }

    public static String render(JsonObject document,String previous)throws IOException{
        var out=new StringBuilder();HEADER.forEach(line->out.append(line).append('\n'));
        List<String> custom=previous==null?List.of():scan(previous).comments().stream()
                .filter(comment->!GENERATED.contains(commentKey(comment))).toList();
        if(!custom.isEmpty()){
            out.append('\n').append(CUSTOM).append('\n');
            custom.forEach(comment->out.append(comment).append('\n'));
        }
        out.append('\n');appendObject(out,document,"",0);out.append('\n');return out.toString();
    }
    private static void appendObject(StringBuilder out,JsonObject object,String path,int depth){
        if(object.isEmpty()){out.append("{}");return;}
        out.append("{\n");String indent="  ".repeat(depth+1);int count=0;
        for(var entry:object.entrySet()){
            String key=path.isEmpty()?entry.getKey():path+"."+entry.getKey();
            if(depth==0&&count>0)out.append('\n');
            String note=NOTES.get(key);if(note!=null)note.lines().forEach(line->out.append(indent).append("// ").append(line).append('\n'));
            out.append(indent).append(JSON.toJson(entry.getKey())).append(": ");
            if(entry.getValue().isJsonObject())appendObject(out,entry.getValue().getAsJsonObject(),key,depth+1);
            else out.append(JSON.toJson(entry.getValue()).replace("\n","\n"+indent));
            if(++count<object.size())out.append(',');out.append('\n');
        }
        out.append("  ".repeat(depth)).append('}');
    }
    private ConfigComments(){}
}
