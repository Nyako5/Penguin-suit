package dev.penguinsuit.bridge;

import dev.errnicraft.chatremastered.network.packet.ImageChatPacket;
import dev.errnicraft.chatremastered.network.packet.ImageDeletedPacket;
import dev.errnicraft.chatremastered.server.ChatRemasteredState;
import dev.errnicraft.chatremastered.tcp.ImageTcpServer;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

public final class ChatBackend {
    public record Limits(boolean gifEnabled, int gifDimension, int maxImages, long maxBytes) {}
    public static Limits limits() {
        var config = ChatRemasteredState.cachedConfig;
        if (config == null || ChatRemasteredState.currentServer == null) return null;
        return new Limits(config.gifEnabled(), config.gifMaxDim(), config.maxPhotosPerMessage(),
                ImageTcpServer.getMaxUploadBytes());
    }
    public static boolean capable(ServerPlayer player) {
        return ChatRemasteredState.hasModInstalled(player.getUUID())
                && ServerPlayNetworking.canSend(player, ImageChatPacket.TYPE);
    }
    public static void image(ServerPlayer player, Prepared image, String sender, String caption, String group, int index, int count) {
        sendImage(player, new ImageChatPacket(image.id(), sender, caption, image.width(), image.height(),
                Component.literal(sender), "", "", "", group, index, count));
    }
    public static void image(ServerPlayer player,Prepared image,String sender,String caption,String group,int index,int count,
                             dev.penguinsuit.reply.ReplyService.Quote quote){
        sendImage(player,new ImageChatPacket(image.id(),sender,caption,image.width(),image.height(),
                Component.literal(sender),quote.sender(),quote.text(),quote.image(),group,index,count));
    }
    /** CR 0.4.1 encodes groupId with writeUtf(..., 40). Keep the complete random UUID at 37 chars. */
    public static String newGroupId(){return "pmbg_"+java.util.UUID.randomUUID().toString().replace("-","");}
    private static void sendImage(ServerPlayer player,ImageChatPacket packet){
        // Netty encodes asynchronously, too late for DeliveryRouter's fallback. Validate through the
        // pinned codec first so an invalid packet never enters the connection or disconnects a player.
        var buffer=new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        try{ImageChatPacket.STREAM_CODEC.encode(buffer,packet);}finally{buffer.release();}
        ServerPlayNetworking.send(player,packet);
    }
    public static void deleted(ServerPlayer player, String id) {
        if (ChatRemasteredState.hasModInstalled(player.getUUID()) && ServerPlayNetworking.canSend(player, ImageDeletedPacket.TYPE))
            ServerPlayNetworking.send(player, new ImageDeletedPacket(id, true));
    }
    public record Prepared(String id, int width, int height) {}
    private ChatBackend() {}
}
