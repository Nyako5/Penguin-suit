package dev.penguinsuit.mixin;

import dev.penguinsuit.PenguinSuit;
import java.util.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets="com.huhobot.penguin.qq.QQClient",remap=false)
public abstract class QQMemberMixin {
    @Inject(method="onGroupMessage(Ljava/util/Map;)V",at=@At("HEAD"),require=1)
    private void penguinsuit$botIdentity(Map<?,?> event,CallbackInfo ci){
        var service=PenguinSuit.mentions();if(service!=null)service.observeQqAuthor(event);
    }
    @Inject(method="onSocketClose",at=@At("TAIL"),require=1)
    private void penguinsuit$disconnect(com.huhobot.penguin.qq.WsConnection connection,int code,String reason,CallbackInfo ci){
        var service=PenguinSuit.mentions();if(service!=null)service.disconnected();
    }
    @Inject(method="onDispatch(Ljava/util/Map;)V",at=@At("HEAD"),require=1)
    private void penguinsuit$memberEvent(Map<?,?> event,CallbackInfo ci){
        var service=PenguinSuit.mentions();if(service!=null)service.dispatch(event);
    }
    @ModifyVariable(method="onDispatch(Ljava/util/Map;)V",at=@At("HEAD"),argsOnly=true,require=1)
    private Map<?,?> penguinsuit$memberShape(Map<?,?> event){
        Object type=event.get("t");
        if(!"GROUP_MEMBER_ADD".equals(type)&&!"GROUP_MEMBER_REMOVE".equals(type))return event;
        if(!(event.get("d")instanceof Map<?,?> d)||!(d.get("member_openid")instanceof String id)||id.isBlank())return event;
        Map<Object,Object> user=new HashMap<>();if(d.get("user")instanceof Map<?,?> u)user.putAll(u);user.put("id",id);
        Map<Object,Object> data=new HashMap<>(d);data.put("user",user);Map<Object,Object> result=new HashMap<>(event);result.put("d",data);return result;
    }
    @ModifyVariable(method="onGroupMessage(Ljava/util/Map;)V",at=@At("HEAD"),argsOnly=true,require=1)
    private Map<?,?> penguinsuit$authorShape(Map<?,?> event){
        if(!(event.get("author")instanceof Map<?,?> a)||!(a.get("member_openid")instanceof String id)||id.isBlank())return event;
        Map<Object,Object> author=new HashMap<>(a);author.put("id",id);Map<Object,Object> result=new HashMap<>(event);result.put("author",author);return result;
    }
}
