package dev.penguinsuit.mixin;

import dev.penguinsuit.config.RuntimeSettings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets="com.huhobot.penguin.PenguinServerMod",remap=false)
public abstract class PenguinDirectionMixin {
    @Inject(method="registerQqEventListeners$lambda$0",at=@At("HEAD"),cancellable=true)
    private static void memberJoin(com.huhobot.penguin.qq.GroupMemberJoin event,org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<kotlin.Unit> ci){if(!RuntimeSettings.incomingEvent(event.getGroupId(),event.getUserId()))ci.setReturnValue(kotlin.Unit.INSTANCE);}
    @Inject(method="registerQqEventListeners$lambda$1",at=@At("HEAD"),cancellable=true)
    private static void memberLeave(com.huhobot.penguin.qq.GroupMemberLeave event,org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<kotlin.Unit> ci){if(!RuntimeSettings.incomingEvent(event.getGroupId(),event.getUserId()))ci.setReturnValue(kotlin.Unit.INSTANCE);}
    @Inject(method="registerQqEventListeners$lambda$2",at=@At("HEAD"),cancellable=true)
    private static void joinRequest(com.huhobot.penguin.qq.GroupJoinRequest event,org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<kotlin.Unit> ci){if(!RuntimeSettings.incomingEvent(event.getGroupId(),event.getUserId()))ci.setReturnValue(kotlin.Unit.INSTANCE);}
    @com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(method="sendToAllGroups",at=@At(value="INVOKE",target="Lcom/huhobot/penguin/config/PenguinConfig;getBotGroups()Ljava/util/List;"))
    private java.util.List<String> groups(com.huhobot.penguin.config.PenguinConfig penguin,com.llamalad7.mixinextras.injector.wrapoperation.Operation<java.util.List<String>> original){
        var groups=original.call(penguin);var c=RuntimeSettings.get();
        if(!c.enabled)return groups;
        if(!c.gameToQqEnabled||!c.forwarding.gameToQq)return java.util.List.of();
        return groups.stream().filter(g->!c.blockedGroups.contains(g)).toList();
    }
    @Inject(method="forwardGameMessage",at=@At("HEAD"),cancellable=true)
    private void text(String name,String message,CallbackInfo ci){
        var server=com.huhobot.penguin.PenguinServerMod.INSTANCE.getServer();
        var player=server==null?null:server.getPlayerList().getPlayerByName(name);
        if(!RuntimeSettings.outgoing(player==null?null:player.getUUID(),name,false))ci.cancel();
    }
}
