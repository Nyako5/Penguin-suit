package dev.penguinsuit.mixin;

import dev.penguinsuit.PenguinSuit;
import dev.penguinsuit.mention.MentionService;
import net.minecraft.network.chat.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets="com.huhobot.penguin.PenguinServerMod",remap=false)
public abstract class PenguinPlayerMixin {
    @Inject(method="onInitialize$lambda$3",at=@At("HEAD"),cancellable=true,require=1)
    private static void penguinsuit$chat(PlayerChatMessage message,ServerPlayer sender,ChatType.Bound bound,CallbackInfo ci){
        if(!dev.penguinsuit.config.RuntimeSettings.outgoing(sender.getUUID(),sender.getGameProfile().name(),false)){ci.cancel();return;}
        if(MentionService.customPublication()||dev.penguinsuit.reply.ReplyService.customPublication())ci.cancel();
    }
    @Inject(method="onInitialize$lambda$4",at=@At("HEAD"),cancellable=true,require=1)
    private static void penguinsuit$join(ServerGamePacketListenerImpl handler,PacketSender packets,MinecraftServer server,CallbackInfo ci){
        var c=dev.penguinsuit.config.RuntimeSettings.get();
        if(c.enabled&&(!c.gameToQqEnabled||!c.forwarding.gameToQq||!c.forwarding.gameEvents||!c.forwarding.playerJoin||!dev.penguinsuit.config.RuntimeSettings.playerAllowed(handler.player.getUUID(),handler.player.getGameProfile().name()))){ci.cancel();return;}
        var service=PenguinSuit.mentions();if(service!=null&&service.fake(handler.player))ci.cancel();
    }
    @Inject(method="onInitialize$lambda$5",at=@At("HEAD"),cancellable=true,require=1)
    private static void penguinsuit$leave(ServerGamePacketListenerImpl handler,MinecraftServer server,CallbackInfo ci){
        var c=dev.penguinsuit.config.RuntimeSettings.get();
        if(c.enabled&&(!c.gameToQqEnabled||!c.forwarding.gameToQq||!c.forwarding.gameEvents||!c.forwarding.playerLeave||!dev.penguinsuit.config.RuntimeSettings.playerAllowed(handler.player.getUUID(),handler.player.getGameProfile().name()))){ci.cancel();return;}
        var service=PenguinSuit.mentions();if(service!=null&&service.fake(handler.player))ci.cancel();
    }
}
