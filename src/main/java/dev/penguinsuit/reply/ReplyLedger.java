package dev.penguinsuit.reply;

import com.google.gson.*;
import dev.penguinsuit.config.BridgeConfig;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Bounded durable logical messages. QQ send IDs and quote indices are deliberately separate. */
public final class ReplyLedger {
    public record Scope(String app,String group){}
    public record Author(String origin,UUID player,String openid,String name){}
    public record Entry(UUID id,Scope scope,Author author,String display,String text,long created,
                        UUID replyTo,List<String> images,Set<String> qqIds,Set<String> references,Set<UUID> viewers,boolean published,
                        Map<String,String> imageReferences){
        public Entry {images=List.copyOf(images);qqIds=Set.copyOf(qqIds);references=Set.copyOf(references);viewers=Set.copyOf(viewers);
            imageReferences=imageReferences==null?Map.of():Map.copyOf(imageReferences);}
        public String reference(){
            if(!images.isEmpty()&&!imageReferences.isEmpty())return imageReferences.getOrDefault(images.getFirst(),"");
            return references.size()==1?references.iterator().next():"";
        }
    }
    private static final Gson JSON=new GsonBuilder().disableHtmlEscaping().create();
    private static final class State {int schema=1;List<Entry> messages=new ArrayList<>();}
    public static final class Invalid extends IllegalArgumentException {public Invalid(String reason){super(reason);}}
    private final Path path;
    private final LinkedHashMap<UUID,Entry> entries=new LinkedHashMap<>();
    private final Map<String,UUID> references=new HashMap<>(),sources=new HashMap<>(),images=new HashMap<>();
    private boolean dirty;
    private long bytes;
    private int maxEntries;
    private long maxBytes,retention;
    public ReplyLedger(Path path,int maxEntries,long maxBytes,long retention)throws IOException{
        this.path=path;configure(maxEntries,maxBytes,retention);
        if(Files.exists(path)){
            if(Files.size(path)>Math.max(maxBytes*2,128L*1024*1024))throw new IOException("Reply index exceeds safe load limit");
            try{
                State state=JSON.fromJson(Files.readString(path),State.class);
                if(state==null||state.schema!=1||state.messages==null)throw new IOException("Invalid reply index");
                for(Entry e:state.messages){validate(e);if(entries.putIfAbsent(e.id(),e)!=null)throw new IOException("Duplicate reply ID");}
                rebuild();
            }catch(RuntimeException e){throw new IOException("Invalid reply index; refusing reset",e);}
        }
        prune(System.currentTimeMillis());
    }
    private static void validate(Entry e){
        if(e==null||e.id()==null||HiddenReplyId.UNRESOLVED.equals(e.id())||e.scope()==null||e.author()==null||e.display()==null||e.text()==null||e.text().length()>8192
                ||e.images()==null||e.qqIds()==null||e.references()==null||e.viewers()==null||e.images().size()>10||e.references().size()>32||e.qqIds().size()>32
                ||!e.images().containsAll(e.imageReferences().keySet())||!e.references().containsAll(e.imageReferences().values()))
            throw new Invalid("invalid_reply_record");
    }
    private static String key(Scope scope,String id){return scope.app()+"/"+scope.group()+"/"+id;}
    private static long weight(Entry e){return JSON.toJson(e).getBytes(StandardCharsets.UTF_8).length+1L;}
    private void rebuild(){
        references.clear();sources.clear();images.clear();bytes=0;
        for(Entry e:entries.values()){
            bytes+=weight(e);for(String id:e.qqIds())index(sources,key(e.scope(),id),e.id());
            for(String ref:e.references())index(references,key(e.scope(),ref),e.id());
            for(String image:e.images())index(images,key(e.scope(),image),e.id());
        }
    }
    private static void index(Map<String,UUID> map,String key,UUID id){UUID previous=map.putIfAbsent(key,id);if(previous!=null&&!previous.equals(id))throw new Invalid("reply_index_collision");}
    public synchronized void configure(int maxEntries,long maxBytes,long retention){
        if(maxEntries<1||maxBytes<1024||retention<1000)throw new IllegalArgumentException();
        this.maxEntries=maxEntries;this.maxBytes=maxBytes;this.retention=retention;
    }
    public synchronized Entry create(Scope scope,Author author,String display,String text,UUID replyTo,List<String> media,String qqId,Collection<String> refs,long now){
        prune(now);
        if(qqId!=null&&!qqId.isBlank()){
            Entry old=get(sources.get(key(scope,qqId)));if(old!=null)return old;
        }
        UUID id;do{id=UUID.randomUUID();}while(id.equals(HiddenReplyId.UNRESOLVED)||entries.containsKey(id));
        Entry e=new Entry(id,scope,author,HiddenReplyId.bounded(display,128),HiddenReplyId.bounded(text,8192),now,replyTo,
                media,qqId==null||qqId.isBlank()?Set.of():Set.of(qqId),safeRefs(refs),Set.of(),false,Map.of());
        put(e);return e;
    }
    private static Set<String> safeRefs(Collection<String> values){
        Set<String> result=new LinkedHashSet<>();if(values!=null)for(String s:values)if(s!=null&&!s.isBlank()&&s.length()<=4096){result.add(s);if(result.size()==32)break;}return result;
    }
    private void put(Entry e){
        validate(e);Entry before=entries.get(e.id());
        // Validate collisions BEFORE mutating the existing record.
        for(String ref:e.references()){UUID old=references.get(key(e.scope(),ref));if(old!=null&&!old.equals(e.id()))throw new Invalid("reply_reference_collision");}
        for(String src:e.qqIds()){UUID old=sources.get(key(e.scope(),src));if(old!=null&&!old.equals(e.id()))throw new Invalid("reply_source_collision");}
        for(String img:e.images()){UUID old=images.get(key(e.scope(),img));if(old!=null&&!old.equals(e.id()))throw new Invalid("reply_image_collision");}
        entries.put(e.id(),e);if(before!=null)bytes-=weight(before);bytes+=weight(e);
        for(String ref:e.references())references.put(key(e.scope(),ref),e.id());for(String src:e.qqIds())sources.put(key(e.scope(),src),e.id());for(String img:e.images())images.put(key(e.scope(),img),e.id());dirty=true;
    }
    public synchronized Entry get(UUID id){return id==null?null:entries.get(id);}
    public synchronized Entry byReference(Scope scope,String reference){
        Entry e=get(references.get(key(scope,reference)));if(e==null)return null;
        List<String> matched=e.images().stream().filter(id->reference.equals(e.imageReferences().get(id))).toList();
        return matched.size()==1?focus(e,matched.getFirst()):e;
    }
    public synchronized Entry bySource(Scope scope,String id){return get(sources.get(key(scope,id)));}
    public record QqResolution(Entry entry,String reason){}
    /** A quote index can differ from the one delivered with the original event. Never guess by recency. */
    public synchronized QqResolution resolveQq(Scope scope,QqReplyData quote,java.util.function.Function<Entry,String> botText,long now){
        prune(now);
        if(quote.quoteShape().equals("conflict"))return new QqResolution(null,"quote_metadata_conflict");
        Entry ref=quote.targetReference().isBlank()?null:byReference(scope,quote.targetReference());
        Entry source=quote.quotedSourceId().isBlank()?null:bySource(scope,quote.quotedSourceId());
        if(ref!=null&&source!=null&&!ref.id().equals(source.id()))return new QqResolution(null,"reference_source_conflict");
        Entry exact=ref!=null?ref:source;
        if(exact!=null){
            if(exact.author().origin().equals("QQ")&&(quote.quotedBot()||!quote.quotedOpenid().isBlank()&&!quote.quotedOpenid().equals(exact.author().openid())))
                return new QqResolution(null,"reference_author_conflict");
            return new QqResolution(exact.published()?exact:null,exact.published()?(ref!=null?"reference":"source"):"original_unpublished");
        }
        // Nicknames are neither unique nor stable. They may label a fallback preview, never prove identity.
        if(quote.quotedOpenid().isBlank()&&!quote.quotedBot())return new QqResolution(null,"missing_author_identity");
        String text=HiddenReplyId.plain(quote.quotedText()).strip();
        if(text.isEmpty())return new QqResolution(null,"missing_quote_text");
        List<Entry> matches=entries.values().stream().filter(e->e.scope().equals(scope)&&e.published()&&e.images().isEmpty())
                .filter(e->quote.quotedBot()
                        ? e.author().origin().equals("MC")&&!e.qqIds().isEmpty()&&text.equals(botText.apply(e).strip())
                        : e.author().origin().equals("QQ")&&quote.quotedOpenid().equals(e.author().openid())&&text.equals(e.text().strip()))
                .limit(2).toList();
        return matches.size()==1?new QqResolution(matches.getFirst(),"unique_author_text")
                :new QqResolution(null,matches.isEmpty()?"original_not_found":"original_ambiguous");
    }
    public synchronized Entry byImage(Scope scope,String id){Entry e=get(images.get(key(scope,id)));return e==null?null:focus(e,id);}
    /** Keep the selected image's receipt, including older albums sent as separate bubbles. */
    private static Entry focus(Entry e,String image){
        List<String> ordered=new ArrayList<>(e.images());ordered.remove(image);ordered.addFirst(image);
        String ref=e.imageReferences().get(image);
        Set<String> refs=ref!=null?Set.of(ref):e.imageReferences().isEmpty()&&e.references().size()<=1?e.references():Set.of();
        return new Entry(e.id(),e.scope(),e.author(),e.display(),e.text(),e.created(),e.replyTo(),ordered,e.qqIds(),refs,e.viewers(),e.published(),
                ref==null?Map.of():Map.of(image,ref));
    }
    public synchronized Entry publish(UUID id,Collection<UUID> viewers){
        Entry e=require(id);Set<UUID> all=new HashSet<>(e.viewers());all.addAll(viewers);
        Entry next=new Entry(e.id(),e.scope(),e.author(),e.display(),e.text(),e.created(),e.replyTo(),e.images(),e.qqIds(),e.references(),all,true,e.imageReferences());put(next);return next;
    }
    public synchronized void receipt(UUID id,String qqId,String ref)throws IOException{
        receipt(id,qqId,ref,"");
    }
    public synchronized void receipt(UUID id,String qqId,String ref,String image)throws IOException{
        albumReceipt(id,qqId,ref,image==null||image.isBlank()?List.of():List.of(image));
    }
    /** All images of a newly forwarded album share one QQ bubble and one durable receipt. */
    public synchronized void albumReceipt(UUID id,String qqId,String ref,List<String> album)throws IOException{
        Entry e=require(id);Set<String> ids=new LinkedHashSet<>(e.qqIds()),refs=new LinkedHashSet<>(e.references());
        if(qqId!=null&&!qqId.isBlank())ids.add(qqId);if(ref!=null&&!ref.isBlank())refs.add(ref);
        Map<String,String> mediaRefs=new HashMap<>(e.imageReferences());
        for(String image:album){
            if(!e.images().contains(image))throw new Invalid("reply_receipt_image_unknown");
            if(ref!=null&&!ref.isBlank()) {
                String old=mediaRefs.putIfAbsent(image,ref);if(old!=null&&!old.equals(ref))throw new Invalid("reply_image_receipt_conflict");
            }
        }
        put(new Entry(e.id(),e.scope(),e.author(),e.display(),e.text(),e.created(),e.replyTo(),e.images(),ids,refs,e.viewers(),e.published(),mediaRefs));flush();
    }
    public synchronized Entry resolve(Scope scope,UUID viewer,String sender,String text,String image,long now){
        prune(now);HiddenReplyId.Parsed parsed=HiddenReplyId.parse(text);
        if(parsed.malformed())throw new Invalid("hidden_marker_malformed");
        if(HiddenReplyId.UNRESOLVED.equals(parsed.id()))throw new Invalid("original_unresolved");
        Entry exact=null;
        if(parsed.id()!=null){exact=get(parsed.id());if(exact==null)throw new Invalid("original_expired");}
        if(image!=null&&!image.isBlank()){
            Entry media=byImage(scope,image);if(media==null)throw new Invalid("original_image_unknown");
            if(exact!=null&&!exact.id().equals(media.id()))throw new Invalid("quote_image_marker_mismatch");exact=media;
        }
        if(exact!=null){
            if(!eligible(exact,scope,viewer))throw new Invalid("original_not_visible");
            if((image==null||image.isBlank())&&!matches(exact,sender,parsed.text()))throw new Invalid("quote_text_mismatch");
            return exact;
        }
        List<Entry> found=entries.values().stream().filter(e->eligible(e,scope,viewer)&&matches(e,sender,parsed.text())).limit(2).toList();
        if(found.size()!=1)throw new Invalid(found.isEmpty()?"original_not_found":"original_ambiguous");return found.getFirst();
    }
    private static boolean eligible(Entry e,Scope s,UUID viewer){return e.scope().equals(s)&&e.published()&&e.viewers().contains(viewer);}
    private static boolean matches(Entry e,String sender,String quoted){
        String s=HiddenReplyId.plain(sender==null?"":sender).strip(),q=HiddenReplyId.plain(quoted).strip();
        if(!s.isEmpty()&&!s.equals(e.display())&&!s.equals(e.author().name()))return false;
        String plain=e.text().strip();if(q.equals(plain))return !s.isEmpty();
        // Some client timestamp mods copy the full line. Its time is display-only, never identity.
        String noTime=q.replaceFirst("^\\[\\d{1,2}:\\d{2}(?::\\d{2})?\\]\\s*","");
        return noTime.equals("<"+e.display()+"> "+plain)||noTime.equals("<"+e.author().name()+"> "+plain);
    }
    private Entry require(UUID id){Entry e=get(id);if(e==null)throw new Invalid("original_expired");return e;}
    public synchronized void prune(long now){
        boolean changed=entries.values().removeIf(e->now-e.created()>retention);
        if(changed){dirty=true;rebuild();}
        var it=entries.values().iterator();while((entries.size()>maxEntries||bytes>maxBytes)&&it.hasNext()){
            Entry e=it.next();it.remove();bytes-=weight(e);changed=true;dirty=true;
        }
        if(changed)rebuild();
    }
    public synchronized void flush()throws IOException{
        if(!dirty)return;prune(System.currentTimeMillis());State state=new State();state.messages.addAll(entries.values());
        BridgeConfig.atomicWrite(path,JSON.toJson(state));dirty=false;
    }
    public synchronized int size(){return entries.size();}
    public synchronized long bytes(){return bytes;}
}
