package dev.penguinsuit.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.errnick.chatremastered.network.packet.ReplyMetaPacket;
import dev.errnick.chatremastered.server.network.ReplyHandler;
import dev.penguinsuit.PenguinSuit;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(value=ReplyHandler.class,remap=false)
public abstract class ChatReplyMixin {
    @WrapMethod(method="lambda$register$0")
    private static void penguinsuit$reply(ReplyMetaPacket packet,ServerPlayNetworking.Context context,Operation<Void> original){
        var service=PenguinSuit.replies();
        if(service==null||!service.handlesGame(context.player())){original.call(packet,context);return;}
        service.receive(packet,context);
    }
}
