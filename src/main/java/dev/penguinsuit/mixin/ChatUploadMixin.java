package dev.penguinsuit.mixin;

import dev.errnicraft.chatremastered.server.network.ImageUploadHandler;
import dev.penguinsuit.PenguinSuit;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Reached only after native handshake, mute, permission and attachment-count checks. */
@Mixin(value = ImageUploadHandler.class, remap = false)
public abstract class ChatUploadMixin {
    @com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod(method="lambda$register$0")
    private static void penguinsuit$validateCaption(dev.errnicraft.chatremastered.network.packet.ImageUploadedPacket packet,
            net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.Context context,
            com.llamalad7.mixinextras.injector.wrapoperation.Operation<Void> original) {
        var service = PenguinSuit.mentions();
        if (service == null) { original.call(packet,context); return; }
        service.images().receive(packet,context,changed -> original.call(changed,context));
    }
    @Inject(method = "scheduleUploadTimeout(Lnet/minecraft/server/MinecraftServer;Ljava/lang/String;)V",
            at = @At("HEAD"), require = 1, remap = false)
    private static void penguinsuit$acceptedUpload(MinecraftServer server, String imageId, CallbackInfo ci) {
        PenguinSuit.playerImageAccepted(server, imageId);
    }
}
