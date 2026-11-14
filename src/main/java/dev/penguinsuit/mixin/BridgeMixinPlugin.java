package dev.penguinsuit.mixin;

import java.util.List;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/** Inspect class bytes without defining Penguin classes before Mixin transformation. */
public final class BridgeMixinPlugin implements IMixinConfigPlugin {
    public static final String TARGET = "com.huhobot.penguin.command.CommandHandler";
    public static final String HANDLE = "(Lcom/huhobot/penguin/qq/GroupMessage;)V";
    public static final String AUDIT = "(Ljava/lang/String;Lcom/huhobot/penguin/config/PenguinConfig;Lkotlin/jvm/functions/Function1;)V";
    public static final String UPLOAD = "dev.errnicraft.chatremastered.server.network.ImageUploadHandler";
    public static final String QQCLIENT = "com.huhobot.penguin.qq.QQClient";
    private boolean enabled;
    @Override public void onLoad(String mixinPackage) {
        var loader = FabricLoader.getInstance();
        enabled = version("minecraft", "26.2") && version("penguin-server-fabric", "1.1.7")
                && version("chat-remastered", "0.4.1+26.2");
        if (enabled) {
            try {
                var mod = loader.getModContainer("penguin-server-fabric").orElseThrow();
                var path = mod.findPath(TARGET.replace('.', '/') + ".class").orElseThrow();
                byte[] commands=java.nio.file.Files.readAllBytes(path);
                enabled = matches(commands)&&matchesAuthentication(commands);
                var chat = loader.getModContainer("chat-remastered").orElseThrow();
                byte[] qqBytes = java.nio.file.Files.readAllBytes(mod.findPath(QQCLIENT.replace('.', '/') + ".class").orElseThrow());
                enabled = enabled && matchesUpload(java.nio.file.Files.readAllBytes(chat.findPath(UPLOAD.replace('.', '/') + ".class").orElseThrow()))
                        && matchesQueue(qqBytes) && matchesMentions(qqBytes) && matchesMemberEvents(qqBytes)
                        && matchesReply(java.nio.file.Files.readAllBytes(chat.findPath("dev/errnicraft/chatremastered/server/network/ReplyHandler.class").orElseThrow()))
                        && matchesPlayerEvents(java.nio.file.Files.readAllBytes(mod.findPath("com/huhobot/penguin/PenguinServerMod.class").orElseThrow()))
                        && matchesSettings(java.nio.file.Files.readAllBytes(mod.findPath("com/huhobot/penguin/config/PenguinConfig.class").orElseThrow()));
            } catch (Exception e) { enabled = false; }
        }
        System.setProperty("penguinsuit.mixin.ready", Boolean.toString(enabled));
        if (!enabled) System.err.println("[Penguin suit] Unsupported upstream call site; bridge disabled, original forwarding retained.");
    }
    private static boolean version(String id, String expected) {
        return FabricLoader.getInstance().getModContainer(id)
                .map(m -> expected.equals(m.getMetadata().getVersion().getFriendlyString())).orElse(false);
    }
    public static boolean matches(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        int count = 0;
        for (var method : node.methods) {
            if (!method.name.equals("handle") || !method.desc.equals(HANDLE)) continue;
            for (var instruction : method.instructions)
                if (instruction instanceof MethodInsnNode call && call.name.equals("audit")
                        && call.owner.equals("com/huhobot/penguin/filter/TextFilter") && call.desc.equals(AUDIT)) count++;
        }
        return count == 1;
    }
    @Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) { return enabled; }
    public static boolean matchesMentions(byte[] bytes) {
        ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        int count = 0;
        for (var method : node.methods) {
            if (!method.name.equals("onGroupMessage") || !method.desc.equals("(Ljava/util/Map;)V")) continue;
            for (var instruction : method.instructions)
                if (instruction instanceof MethodInsnNode call && call.owner.equals("kotlin/jvm/functions/Function1")
                        && call.name.equals("invoke") && call.desc.equals("(Ljava/lang/Object;)Ljava/lang/Object;")) count++;
        }
        return count == 1;
    }
    public static boolean matchesAuthentication(byte[] bytes){
        ClassNode node=new ClassNode();new ClassReader(bytes).accept(node,0);int calls=0;
        if(node.methods.stream().noneMatch(m->m.name.equals("normalizeContent")&&m.desc.equals("(Ljava/lang/String;)Ljava/lang/String;")))return false;
        for(var method:node.methods)if(method.name.equals("handle")&&method.desc.equals(HANDLE)){
            boolean allowlist=false;
            for(var instruction:method.instructions)if(instruction instanceof MethodInsnNode call){
                if(call.name.equals("getBotGroups"))allowlist=true;
                if(call.owner.equals(TARGET.replace('.','/'))&&call.name.equals("findCommand")&&call.desc.equals("(Ljava/lang/String;)Lkotlin/Pair;")){
                    if(!allowlist)return false;calls++;
                }
            }
        }
        return calls==1;
    }
    public static boolean matchesUpload(byte[] bytes) {
        ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, 0);
        String desc = "(Lnet/minecraft/server/MinecraftServer;Ljava/lang/String;)V";
        if (node.methods.stream().filter(m -> m.name.equals("scheduleUploadTimeout") && m.desc.equals(desc)).count() != 1) return false;
        int calls = 0;
        for (var method : node.methods) for (var instruction : method.instructions)
            if (instruction instanceof MethodInsnNode call && call.owner.equals(UPLOAD.replace('.', '/'))
                    && call.name.equals("scheduleUploadTimeout") && call.desc.equals(desc)) calls++;
        return calls == 1;
    }
    public static boolean matchesQueue(byte[] bytes) {
        ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, 0);
        return node.methods.stream().anyMatch(m -> m.name.equals("enqueue") && m.desc.equals("(Lkotlin/jvm/functions/Function0;)Z"))
                && node.methods.stream().anyMatch(m -> m.name.equals("invalidateToken") && m.desc.equals("()V"));
    }
    public static boolean matchesReply(byte[] bytes){
        ClassNode node=new ClassNode();new ClassReader(bytes).accept(node,0);
        return node.methods.stream().filter(m->m.name.equals("lambda$register$0")&&m.desc.equals("(Ldev/errnicraft/chatremastered/network/packet/ReplyMetaPacket;Lnet/fabricmc/fabric/api/networking/v1/ServerPlayNetworking$Context;)V")).count()==1;
    }
    public static boolean matchesMemberEvents(byte[] bytes) {
        ClassNode node=new ClassNode();new ClassReader(bytes).accept(node,0);
        return node.methods.stream().filter(m->m.name.equals("onDispatch")&&m.desc.equals("(Ljava/util/Map;)V")).count()==1
                && node.methods.stream().filter(m->m.name.equals("onSocketClose")&&m.desc.equals("(Lcom/huhobot/penguin/qq/WsConnection;ILjava/lang/String;)V")).count()==1;
    }
    public static boolean matchesPlayerEvents(byte[] bytes) {
        ClassNode node=new ClassNode();new ClassReader(bytes).accept(node,0);
        return node.methods.stream().filter(m->m.name.equals("onInitialize$lambda$3")&&m.desc.equals("(Lnet/minecraft/network/chat/PlayerChatMessage;Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/network/chat/ChatType$Bound;)V")).count()==1
                &&node.methods.stream().filter(m->m.name.equals("onInitialize$lambda$4")&&m.desc.equals("(Lnet/minecraft/server/network/ServerGamePacketListenerImpl;Lnet/fabricmc/fabric/api/networking/v1/PacketSender;Lnet/minecraft/server/MinecraftServer;)V")).count()==1
                &&node.methods.stream().filter(m->m.name.equals("onInitialize$lambda$5")&&m.desc.equals("(Lnet/minecraft/server/network/ServerGamePacketListenerImpl;Lnet/minecraft/server/MinecraftServer;)V")).count()==1
                &&node.methods.stream().anyMatch(m->m.name.equals("forwardGameMessage")&&m.desc.equals("(Ljava/lang/String;Ljava/lang/String;)V"))
                &&node.methods.stream().anyMatch(m->m.name.equals("sendToAllGroups")&&m.desc.equals("(Ljava/lang/String;)V"))
                &&node.methods.stream().anyMatch(m->m.name.equals("registerQqEventListeners$lambda$0")&&m.desc.equals("(Lcom/huhobot/penguin/qq/GroupMemberJoin;)Lkotlin/Unit;"))
                &&node.methods.stream().anyMatch(m->m.name.equals("registerQqEventListeners$lambda$1")&&m.desc.equals("(Lcom/huhobot/penguin/qq/GroupMemberLeave;)Lkotlin/Unit;"))
                &&node.methods.stream().anyMatch(m->m.name.equals("registerQqEventListeners$lambda$2")&&m.desc.equals("(Lcom/huhobot/penguin/qq/GroupJoinRequest;)Lkotlin/Unit;"));
    }
    public static boolean matchesSettings(byte[] bytes){
        ClassNode node=new ClassNode();new ClassReader(bytes).accept(node,0);
        return java.util.Map.of("getChatFromGroup","()Ljava/lang/String;","getChatFromGame","()Ljava/lang/String;","getJoinFormat","()Ljava/lang/String;","getLeaveFormat","()Ljava/lang/String;","getGroupMemberEventToGame","()Z","getGroupJoinRequestToGame","()Z")
            .entrySet().stream().allMatch(e->node.methods.stream().anyMatch(m->m.name.equals(e.getKey())&&m.desc.equals(e.getValue())));
    }
    @Override public String getRefMapperConfig() { return null; }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String target, ClassNode node, String mixin, IMixinInfo info) {}
    @Override public void postApply(String target, ClassNode node, String mixin, IMixinInfo info) {}
}
