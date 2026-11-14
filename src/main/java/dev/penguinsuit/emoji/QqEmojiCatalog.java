package dev.penguinsuit.emoji;

import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Pinned metadata only. No user-supplied ID, ext, or URL can create a download destination. */
public final class QqEmojiCatalog {
    public record Face(String id, String name, URI source, String zipEntry, URI animationSource,String animationEntry) {
        public String label() { return "[" + name + "]"; }
    }
    private static final Map<String,Face> IDS;
    private static final Map<String,List<Face>> CANDIDATES;
    private static final Map<String,Face> NAMES;
    private static final Map<URI,Face> SOURCES;
    static {
        Map<String,Face> ids=new HashMap<>(), names=new HashMap<>();
        Map<String,List<Face>> candidates=new HashMap<>();
        Map<URI,Face> sources=new HashMap<>(); Set<String> ambiguous=new HashSet<>();
        try(var input=QqEmojiCatalog.class.getResourceAsStream("/qq-emoji-catalog.json")) {
            if(input==null)throw new IllegalStateException("Missing QQ emoji catalog");
            var root=JsonParser.parseReader(new InputStreamReader(input,StandardCharsets.UTF_8)).getAsJsonObject();
            for(var item:root.getAsJsonArray("faces")) {
                var row=item.getAsJsonObject();
                URI uri=row.has("source")?URI.create(row.get("source").getAsString()):null;
                String entry=row.has("entry")?row.get("entry").getAsString():"";
                URI animation=row.has("animationSource")?URI.create(row.get("animationSource").getAsString()):null;
                String animationEntry=row.has("animationEntry")?row.get("animationEntry").getAsString():"";
                Face face=new Face(row.get("id").getAsString(),row.get("name").getAsString(),uri,entry,animation,animationEntry);
                if(uri!=null) {
                    boolean packed="wa.qq.com".equals(uri.getHost())&&uri.getPath().matches("/qgif-web-permanent/sysemoji/v1/singleres/[0-9]+_base_[0-9]+\\.zip")
                            &&entry.matches("[0-9]+/png/[0-9]+(?:_[0-9]+)?\\.png");
                    boolean icon="qzonestyle.gtimg.cn".equals(uri.getHost())&&uri.getPath().matches("/qzone/em/e[0-9]+\\.png")&&entry.isEmpty();
                    if(!"https".equals(uri.getScheme())||uri.getPort()!=-1||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null||!(packed||icon))
                        throw new IllegalStateException("Invalid QQ emoji catalog source");
                    sources.put(uri,face);
                }
                if(animation!=null){
                    if(!"https".equals(animation.getScheme())||!"wa.qq.com".equals(animation.getHost())||animation.getPort()!=-1
                            ||animation.getUserInfo()!=null||animation.getQuery()!=null||animation.getFragment()!=null
                            ||!animation.getPath().matches("/qgif-web-permanent/sysemoji/v1/singleres/"+face.id()+"_adv_[0-9]+\\.zip")
                            ||!animationEntry.equals(face.id()+"/apng/"+face.id()+".png"))throw new IllegalStateException("Invalid emoji animation source");
                    sources.put(animation,face);
                }
                candidates.computeIfAbsent(face.id(),k->new ArrayList<>()).add(face);
                if(row.has("aliases"))for(var alias:row.getAsJsonArray("aliases"))candidates.computeIfAbsent(alias.getAsString(),k->new ArrayList<>()).add(face);
                if(names.putIfAbsent(face.name(),face)!=null)ambiguous.add(face.name());
            }
        }catch(Exception e){throw new ExceptionInInitializerError(e);}
        ambiguous.forEach(names::remove);
        candidates.replaceAll((k,v)->v.stream().distinct().toList());
        candidates.forEach((k,v)->{if(v.size()==1)ids.put(k,v.getFirst());});
        IDS=Map.copyOf(ids);CANDIDATES=Map.copyOf(candidates);NAMES=Map.copyOf(names);SOURCES=Map.copyOf(sources);
    }
    public static Face byId(String id){return IDS.get(id);}
    public static Face byName(String name){return NAMES.get(name);}
    public static Face byId(String id,String name){
        var face=byId(id);if(face!=null)return face;
        var matching=CANDIDATES.getOrDefault(id,List.of()).stream().filter(f->f.name().equals(name)||f.id().equals(name)).toList();
        return matching.size()==1?matching.getFirst():null;
    }
    public static Face bySource(URI source){return SOURCES.get(source);}
    public static Collection<Face> faces(){return CANDIDATES.values().stream().flatMap(List::stream).distinct().toList();}
    private QqEmojiCatalog(){}
}
