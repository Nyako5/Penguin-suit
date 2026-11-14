package dev.penguinsuit.reverse;

import com.google.gson.*;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Prepare every album resource before publishing one QQ Markdown message. */
public final class QqMediaApi {
    public record Image(byte[] bytes, int width, int height, boolean textOnly) {
        public Image(byte[] bytes,int width,int height) { this(bytes,width,height,false); }
        public static Image gifPlaceholder() { return new Image(null,0,0,true); }
    }
    public interface Transport {
        Response post(String path, JsonObject body) throws Exception;
        String putImage(String signedUrl, String contentType, byte[] bytes) throws Exception;
        Response delete(String path) throws Exception;
    }
    public record Response(int status, JsonObject body) {}
    public record SendReceipt(String id,String reference){
        public static SendReceipt from(Response response)throws Failure{
            check("message",response);String id=required(response,"id",4096),ref="";
            if(response.body().get("ext_info") instanceof JsonObject ext&&ext.get("ref_idx") instanceof JsonPrimitive p
                    &&p.isString()&&p.getAsString().length()<=4096)ref=p.getAsString();
            return new SendReceipt(id,ref);
        }
    }
    public static final class Failure extends IOException {
        public final String stage; public final int status; public final int code;
        public Failure(String stage, int status, int code) {
            super(stage + " http=" + status + " code=" + code);
            this.stage=stage; this.status=status; this.code=code;
        }
    }
    private final Transport transport;
    private final QqMaterialLedger ledger;
    private final String scope;
    private final Consumer<String> cleanupWarning;
    private final LongSupplier clock;
    private final int maxMaterials;
    private final long retentionMillis, graceMillis;
    public QqMediaApi(Transport transport, QqMaterialLedger ledger, String appId, Consumer<String> cleanupWarning) {
        this(transport,ledger,appId,cleanupWarning,System::currentTimeMillis,90,86400000,120000);
    }
    public QqMediaApi(Transport transport,QqMaterialLedger ledger,String appId,Consumer<String> cleanupWarning,
                      LongSupplier clock,int maxMaterials,long retentionMillis,long graceMillis) {
        this.transport=transport; this.ledger=ledger; this.scope=QqMaterialLedger.scope(appId); this.cleanupWarning=cleanupWarning;
        if(maxMaterials<1||maxMaterials>95||retentionMillis<600000||graceMillis<0||graceMillis>600000) throw new IllegalArgumentException("invalid_material_retention");
        this.clock=clock; this.maxMaterials=maxMaterials; this.retentionMillis=retentionMillis; this.graceMillis=graceMillis;
    }
    public void send(String group, String text, byte[] bytes, int width, int height, BooleanSupplier allowed) throws Exception {
        sendAlbum(group,text,List.of(new Image(bytes,width,height)),allowed);
    }
    /** Only the structured identity renderer may supply pre-escaped Markdown. */
    public void sendMarkdown(String group,String markdown,byte[] bytes,int width,int height,BooleanSupplier allowed) throws Exception {
        sendAlbumMarkdown(group,markdown,List.of(new Image(bytes,width,height)),allowed,"");
    }
    public SendReceipt sendReplyMarkdown(String group,String markdown,byte[] bytes,int width,int height,BooleanSupplier allowed,String reference)throws Exception{
        return sendAlbumMarkdown(group,markdown,List.of(new Image(bytes,width,height)),allowed,reference);
    }
    public SendReceipt sendAlbum(String group,String text,List<Image> images,BooleanSupplier allowed)throws Exception {
        return sendAlbumInternal(group,text,images,allowed,false,"");
    }
    /** Structured text comes only from the identity/reply renderer; image references remain server-owned. */
    public SendReceipt sendAlbumMarkdown(String group,String markdown,List<Image> images,BooleanSupplier allowed,String reference)throws Exception {
        return sendAlbumInternal(group,markdown,images,allowed,true,reference);
    }
    private SendReceipt sendAlbumInternal(String group,String text,List<Image> images,BooleanSupplier allowed,boolean structured,String quoteReference)throws Exception {
        authorize(allowed);
        if(text == null || text.isBlank()) throw new Failure("missing_sender_text",0,0);
        if(images==null||images.isEmpty()||images.size()>10) throw new Failure("invalid_album_count",0,0);
        int uploads=0;
        for(Image image:images) {
            if(image==null) throw new Failure("invalid_album_image",0,0);
            if(image.textOnly()) {
                if(image.bytes()!=null||image.width()!=0||image.height()!=0) throw new Failure("invalid_gif_placeholder",0,0);
            } else {
                if(image.width()<1||image.height()<1||image.width()>8192||image.height()>8192) throw new Failure("invalid_image_dimensions",0,0);
                extension(image.bytes()); uploads++;
            }
        }
        // Reserve room for the complete album. Never publish a prefix of a user's message.
        if(uploads>maxMaterials) throw new Failure("material_album_capacity",0,0);
        if(uploads>0) {
            cleanupDue();
            while(ledger.count(scope)+uploads>maxMaterials)
                if(!evictOldest(Set.of())) throw new Failure("material_capacity_protected_or_cleanup_failed",0,0);
            if(ledger.size()+uploads>100) throw new Failure("material_cleanup_pending",0,0);
        }
        Map<String,QqMaterialLedger.Entry> previous=new LinkedHashMap<>();
        boolean posted=false,confirmed=false;
        try {
            StringBuilder content=new StringBuilder(structured?text:QqMarkdown.literalText(text));
            for(Image image:images) {
                if(image.textOnly()) content.append("\n\n[GIF]");
                else content.append(QqMarkdown.imageMessage("",upload(image.bytes(),allowed,previous),image.width(),image.height()));
            }
            authorize(allowed);
            JsonObject markdown=new JsonObject();markdown.addProperty("content",content.toString());
            if(uploads>0) markdown.addProperty("force_verify_image_resource",true);
            JsonObject message=new JsonObject();message.addProperty("msg_type",2);message.add("markdown",markdown);
            if(quoteReference!=null&&!quoteReference.isBlank()) {
                JsonObject quoted=new JsonObject();quoted.addProperty("message_id",quoteReference);message.add("message_reference",quoted);
            }
            posted=true;
            SendReceipt receipt=SendReceipt.from(transport.post("/v2/groups/"+URLEncoder.encode(group,StandardCharsets.UTF_8)+"/messages",message));
            confirmed=true;return receipt;
        } finally {
            if(!posted) {
                for(var entry:previous.entrySet()) {
                    if(entry.getValue()==null) cleanup(entry.getKey());
                    else restore(entry.getValue());
                }
            } else if(confirmed) {
                long now=clock.getAsLong();
                for(String id:previous.keySet()) try { ledger.track(scope,id,now+retentionMillis,now,now+graceMillis); }
                catch(IOException e) { cleanupWarning.accept("material_retention_update_failed"); }
            } else if(!previous.isEmpty()) cleanupWarning.accept("material_retained_after_unconfirmed_message");
        }
    }
    private String upload(byte[] bytes,BooleanSupplier allowed,Map<String,QqMaterialLedger.Entry> previous)throws Exception {
        String ext=extension(bytes),mime=ext.equals("jpg")?"image/jpeg":"image/"+ext;
        authorize(allowed);
        String md5=digest("MD5",bytes);
        JsonObject prepare=new JsonObject();
        prepare.addProperty("file_name","pmb_"+md5+"."+ext); prepare.addProperty("file_size",bytes.length);
        prepare.addProperty("content_type",mime); prepare.addProperty("md5",md5); prepare.addProperty("sha1",digest("SHA-1",bytes));
        Response prepared=transport.post("/v2/media-images/prepare",prepare);
        try { check("prepare",prepared); }
        catch(Failure e) {
            if(e.code!=40035007||!evictOldest(previous.keySet())) throw e;
            authorize(allowed);
            prepared=transport.post("/v2/media-images/prepare",prepare); check("prepare",prepared);
        }
        String uploadId=required(prepared,"upload_id",1024), signedUrl=required(prepared,"presigned_url",16384);
        authorize(allowed);
        String etag=transport.putImage(signedUrl,mime,bytes);
        if(etag == null || !etag.replace("\"","").equalsIgnoreCase(md5)) throw new Failure("upload_checksum",0,0);
        authorize(allowed);
        JsonObject complete=new JsonObject(); complete.addProperty("upload_id",uploadId); complete.addProperty("md5",md5);
        Response completed=transport.post("/v2/media-images/complete",complete); check("complete",completed);
        String id=required(completed,"image_id",256);
        if(!QqMaterialLedger.validId(id)) throw new Failure("invalid_image_id",0,0);
        if(!previous.containsKey(id)) previous.put(id,ledger.get(scope,id));
        long now=clock.getAsLong();
        ledger.track(scope,id,now+retentionMillis,now,now+Math.max(graceMillis,600000));
        String ref=required(completed,"url",4096);
        if(!ref.matches("qqbot-media://image/v1/[A-Za-z0-9_-]+")) throw new Failure("invalid_media_reference",0,0);
        return ref;
    }
    private void restore(QqMaterialLedger.Entry previous) {
        try { ledger.track(previous.scope(),previous.id(),previous.retryAfter(),previous.createdAt(),previous.protectedUntil()); }
        catch(IOException e) { cleanupWarning.accept("material_retention_restore_failed"); }
    }
    private void cleanupDue() {
        for(var e:ledger.due(scope,clock.getAsLong())) { if(!cleanup(e.id())) break; }
    }
    private boolean evictOldest(Set<String> pinned) {
        var oldest=ledger.oldestEvictable(scope,clock.getAsLong(),pinned);
        return oldest!=null&&cleanup(oldest.id());
    }
    private boolean cleanup(String id) {
        try {
            Response response=transport.delete("/v2/media-images/"+id+"/delete");
            try { check("material_delete",response); }
            catch(Failure e) { if(e.code != 40035008) throw e; }
            ledger.remove(scope,id); return true;
        } catch(Exception e) {
            if(e instanceof InterruptedException) Thread.currentThread().interrupt();
            cleanupWarning.accept(e instanceof Failure f ? f.getMessage() : "material_cleanup_"+e.getClass().getSimpleName());
            return false;
        }
    }
    private static String extension(byte[] bytes) throws Failure {
        if(bytes == null || bytes.length<10 || bytes.length>10*1024*1024) throw new Failure("material_size",0,0);
        if(bytes[0]=='G'&&bytes[1]=='I'&&bytes[2]=='F'&&bytes[3]=='8'&&(bytes[4]=='7'||bytes[4]=='9')&&bytes[5]=='a') return "gif";
        if((bytes[0]&255)==137&&bytes[1]=='P'&&bytes[2]=='N'&&bytes[3]=='G') return "png";
        if((bytes[0]&255)==255&&(bytes[1]&255)==216&&(bytes[2]&255)==255) return "jpg";
        throw new Failure("material_format",0,0);
    }
    private static String digest(String algo,byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance(algo).digest(bytes)); }
    private static void authorize(BooleanSupplier allowed) throws Failure { if(!allowed.getAsBoolean()) throw new Failure("cancelled",0,0); }
    private static String required(Response r,String name,int max) throws Failure {
        JsonElement e=r.body().get(name);
        if(e==null||!e.isJsonPrimitive()||!e.getAsJsonPrimitive().isString()||e.getAsString().isBlank()||e.getAsString().length()>max)
            throw new Failure("missing_"+name,r.status(),0);
        return e.getAsString();
    }
    private static void check(String stage,Response r) throws Failure {
        int code=0;
        try { if(r.body().has("code")) code=r.body().get("code").getAsInt(); }
        catch(RuntimeException e) { throw new Failure("invalid_response",r.status(),0); }
        if(r.status()<200||r.status()>=300||code!=0) throw new Failure(stage,r.status(),code);
    }
}
