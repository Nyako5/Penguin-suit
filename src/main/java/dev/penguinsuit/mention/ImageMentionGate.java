package dev.penguinsuit.mention;

import com.huhobot.penguin.PenguinServerMod;
import com.huhobot.penguin.filter.TextFilter;
import dev.errnick.chatremastered.network.packet.*;
import dev.errnick.chatremastered.server.*;
import dev.errnick.chatremastered.server.moderation.ModerationActions;
import dev.penguinsuit.PenguinSuit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import kotlin.Unit;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

/** Hold a complete native album before invoking any upstream broadcast or owner registration. */
public final class ImageMentionGate {
    public record ImagePlan(MentionMessage message,MentionMessage.Rendered published,long created) {}
    private record Slot(ImageUploadedPacket packet,ServerPlayNetworking.Context context,Consumer<ImageUploadedPacket> publish) {}
    private static final class Album {
        final Slot[] slots; final long created=System.currentTimeMillis();
        Album(int count){slots=new Slot[count];}
    }
    private final MentionService service;
    private final Map<String,Album> albums=new HashMap<>();
    private final Map<String,Long> completed=new HashMap<>();
    private final Map<String,ImagePlan> plans=new ConcurrentHashMap<>();
    public ImageMentionGate(MentionService service){this.service=service;}
    private static String key(ImageUploadedPacket p,UUID player){return player+"/"+(p.groupId().isEmpty()?"image/"+p.imageId():"album/"+p.groupId());}
    public void receive(ImageUploadedPacket packet,ServerPlayNetworking.Context context,Consumer<ImageUploadedPacket> publish){
        service.execute(()->receiveOnServer(packet,context,publish));
    }
    private void receiveOnServer(ImageUploadedPacket p,ServerPlayNetworking.Context context,Consumer<ImageUploadedPacket> publish){
        ServerPlayer player=context.player();service.observe(player);
        if(!service.enabled()){publish.accept(p);return;}
        int count=Math.max(1,p.groupCount());
        if(p.groupId()==null||p.imageId()==null||p.caption()==null||p.caption().length()>8192
                ||!p.imageId().matches("[A-Za-z0-9_-]{1,128}")||p.imageId().startsWith("pmb_")
                ||(!p.groupId().isEmpty()&&!p.groupId().matches("[A-Za-z0-9_-]{1,128}"))
                ||count>service.maxImages()||p.groupIndex()<0||p.groupIndex()>=count||(count>1&&p.groupId().isEmpty())){
            service.failure(player,"图片组参数无效");return;
        }
        if(!nativeAllowed(player)){publish.accept(p);return;} // Native denial/permission feedback remains authoritative.
        String key=key(p,player.getUUID());if(completed.containsKey(key))return;
        Album album=albums.get(key);
        if(album==null){
            if(albums.size()>=32||completed.size()>=4096){service.failure(player,"图片提及队列已满");return;}
            album=new Album(count);albums.put(key,album);
        }
        if(album.slots.length!=count){reject(key,album,player,"同组图片数量不一致");return;}
        if(album.slots[p.groupIndex()]!=null)return;
        if(Arrays.stream(album.slots).filter(Objects::nonNull).anyMatch(s->s.packet().imageId().equals(p.imageId()))){reject(key,album,player,"同组图片编号重复");return;}
        album.slots[p.groupIndex()]=new Slot(p,context,publish);
        if(Arrays.stream(album.slots).anyMatch(Objects::isNull))return;
        albums.remove(key);completed.put(key,System.currentTimeMillis());
        List<Slot> slots=List.of(album.slots);
        String first=slots.getFirst().packet().caption();
        // CR normally puts one caption on index 0. Accept replicated captions, never silently lose a later different one.
        List<String> nonempty=slots.stream().map(s->s.packet().caption()).filter(s->!s.isBlank()).distinct().toList();
        if(nonempty.size()>1){deny(slots,player,"同组图片正文不一致，请重新发送");return;}
        String caption=nonempty.isEmpty()?first:nonempty.getFirst();
        var replies=PenguinSuit.replies();
        if(replies!=null&&replies.handlesGame(player)){
            replies.images(player,slots.stream().map(Slot::packet).toList(),changed->{
                for(int i=0;i<slots.size();i++)slots.get(i).publish().accept(changed.get(i));
            });return;
        }
        if(!service.mentionsEnabled()||(!AtSyntax.isImageCommand(caption)&&!MentionMessage.hasExplicit(caption))){slots.forEach(s->s.publish().accept(s.packet()));return;}
        try{
            if(!service.allowedSender(player))throw new MemberDirectory.Invalid("当前禁言或私聊频道不可发送跨端提及");
            service.refresh();var scope=service.scope();
            String body=AtSyntax.imageCaption(caption,service.directory(),scope);
            var source=MentionMessage.parse(body,scope);var rendered=source.render(service.directory());
            if(!service.beginImageAudit(player))throw new MemberDirectory.Invalid("发送过快或提及队列已满");
            var cfg=PenguinServerMod.INSTANCE.getConfig();AtomicBoolean once=new AtomicBoolean();
            service.timeout(()->{if(once.compareAndSet(false,true)){service.endImageAudit();service.execute(()->deny(slots,player,"图片消息审核超时"));}});
            try{TextFilter.INSTANCE.audit(rendered.plain(),cfg,filtered->{
                if(!once.compareAndSet(false,true))return Unit.INSTANCE;
                service.endImageAudit();
                service.execute(()->{try{
                    if(!nativeAllowed(player)||!service.allowedSender(player)||PenguinServerMod.INSTANCE.getConfig()!=cfg)
                        throw new MemberDirectory.Invalid("图片发送条件已变化");
                    rendered.auditText(filtered);service.refresh();var current=source.render(service.directory());
                    ImagePlan plan=new ImagePlan(source,current,System.currentTimeMillis());
                    // Every member shares the one final snapshot, before invoking any original receiver.
                    for(Slot s:slots)plans.put(s.packet().imageId(),plan);
                    for(int i=0;i<slots.size();i++){
                        Slot s=slots.get(i);s.publish().accept(caption(s.packet(),i==0?current.legacy():""));
                    }
                    service.notifyOnce("image/"+key,current);service.logFiltered("MC->QQ image",key,current);
                    PenguinSuit.LOG.info("[mentions] MC image <{}> images={} targets={} published",
                            player.getGameProfile().name(),slots.size(),current.associations().size());
                }catch(MemberDirectory.Invalid e){deny(slots,player,e.getMessage());}});return Unit.INSTANCE;
            });}catch(RuntimeException e){if(once.compareAndSet(false,true))service.endImageAudit();throw e;}
        }catch(MemberDirectory.Invalid e){deny(slots,player,e.getMessage());}
        catch(RuntimeException e){PenguinSuit.LOG.error("Image mention admission failed: {}",e.getClass().getSimpleName());deny(slots,player,"图片提及处理出错，请查看服务器日志");}
    }
    public static boolean nativeAllowed(ServerPlayer player){return ChatRemasteredState.hasModInstalled(player.getUUID())
            &&!ChatRemasteredState.isPhotoBanned(player.getUUID())&&!ModerationActions.isEffectivelyMuted(player.getUUID())
            &&LuckPermsBridge.check(player.getUUID(),"chatremastered.photo.send",true);}
    private void reject(String key,Album album,ServerPlayer player,String reason){albums.remove(key);completed.put(key,System.currentTimeMillis());deny(Arrays.stream(album.slots).filter(Objects::nonNull).toList(),player,reason);}
    private void deny(List<Slot> slots,ServerPlayer player,String reason){
        // CR 0.5.1 has no custom rejection reason; unknown ImageErrorPacket codes produce a misleading version warning.
        service.failure(player,reason);
    }
    public ImagePlan plan(String id){return plans.get(id);}
    public boolean valid(String id){
        ImagePlan p=plans.get(id);if(p==null)return true;
        try{return p.message().render(service.directory()).equals(p.published());}catch(MemberDirectory.Invalid e){return false;}
    }
    public String markdown(String id,String group){
        ImagePlan plan=plans.get(id);if(plan==null)return null;
        if(!plan.message().scope().group().equals(group)||!valid(id))throw new MemberDirectory.Invalid("图片提及已失效");
        return plan.published().markdown();
    }
    public void tick(){
        long now=System.currentTimeMillis();
        for(var entry:List.copyOf(albums.entrySet()))if(now-entry.getValue().created>30000){
            Album a=entry.getValue();Slot first=Arrays.stream(a.slots).filter(Objects::nonNull).findFirst().orElse(null);
            if(first!=null)reject(entry.getKey(),a,first.context().player(),"图片组接收超时，未发布");
        }
        completed.entrySet().removeIf(e->now-e.getValue()>600000);plans.entrySet().removeIf(e->now-e.getValue().created()>600000);
    }
    public static ImageUploadedPacket caption(ImageUploadedPacket p,String body){
        return new ImageUploadedPacket(p.imageId(),p.sender(),body,p.width(),p.height(),p.replyToSender(),p.replyToText(),p.replyToImageId(),p.groupId(),p.groupIndex(),p.groupCount());
    }
}
