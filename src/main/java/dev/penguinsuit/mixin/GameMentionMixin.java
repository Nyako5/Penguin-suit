package dev.penguinsuit.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.penguinsuit.PenguinSuit;
import net.minecraft.network.chat.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import java.util.function.Predicate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value=PlayerList.class,remap=false)
public abstract class GameMentionMixin {
    @com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(
            method="broadcastChatMessage(Lnet/minecraft/network/chat/PlayerChatMessage;Ljava/util/function/Predicate;Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/network/chat/ChatType$Bound;)V",
            at=@At(value="INVOKE",target="Lnet/minecraft/server/MinecraftServer;logChatMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/ChatType$Bound;Ljava/lang/String;)V"),require=1)
    private void penguinsuit$cleanLog(net.minecraft.server.MinecraftServer server,Component content,ChatType.Bound bound,String trust,Operation<Void> original){
        String text=content.getString(),clean=dev.penguinsuit.reply.HiddenReplyId.strip(text);
        original.call(server,text.equals(clean)?content:Component.literal(clean),bound,trust);
    }
    @WrapMethod(method="broadcastChatMessage(Lnet/minecraft/network/chat/PlayerChatMessage;Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/network/chat/ChatType$Bound;)V")
    private void penguinsuit$mentions(PlayerChatMessage message,ServerPlayer player,ChatType.Bound bound,Operation<Void> original){
        var service=PenguinSuit.mentions();
        if(service==null){original.call(message,player,bound);return;}
        service.handleGame(message,player,bound,changed->{
            var replies=PenguinSuit.replies();
            if(replies==null)original.call(changed,player,bound);
            else replies.decorateGame(changed,player,bound,marked->original.call(marked,player,bound));
        });
    }
    @Inject(method="broadcastChatMessage(Lnet/minecraft/network/chat/PlayerChatMessage;Ljava/util/function/Predicate;Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/network/chat/ChatType$Bound;)V",at=@At("TAIL"),require=1)
    private void penguinsuit$published(PlayerChatMessage message,Predicate<ServerPlayer> filter,ServerPlayer player,ChatType.Bound bound,CallbackInfo ci){
        var replies=PenguinSuit.replies();if(replies!=null)replies.gamePublished();
        var service=PenguinSuit.mentions();if(service!=null)service.published();
    }
}
