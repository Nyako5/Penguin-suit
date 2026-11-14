package dev.penguinsuit;

import com.huhobot.penguin.config.PenguinConfig;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.penguinsuit.bridge.BridgeService;
import dev.penguinsuit.bridge.MessageSnapshot;
import dev.penguinsuit.config.BridgeConfig;
import dev.penguinsuit.reverse.ReverseBridge;
import dev.errnicraft.chatremastered.server.ChatRemasteredState;
import net.minecraft.server.MinecraftServer;
import java.nio.file.Path;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.Permissions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PenguinSuit implements ModInitializer {
    private static volatile dev.penguinsuit.events.GameEventService gameEvents;
    public static dev.penguinsuit.events.GameEventService gameEvents() { return gameEvents; }
    private static volatile dev.penguinsuit.reply.ReplyService replies;
    public static dev.penguinsuit.reply.ReplyService replies(){return replies;}
    private static volatile dev.penguinsuit.mention.MentionService mentions;
    public static dev.penguinsuit.mention.MentionService mentions() { return mentions; }
    public static final Logger LOG = LoggerFactory.getLogger("Penguin suit");
    private static volatile BridgeService service;
    private static volatile ReverseBridge reverse;
    private static volatile String state = "not started";
    private static final Path CONFIG = FabricLoader.getInstance().getConfigDir().resolve(BridgeConfig.FILE_NAME);
    public static void manageCache(String action,java.util.function.Consumer<String> response){var current=service;if(current==null)response.accept("媒体服务未运行");else current.manageCache(action,response);}
    @Override public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher,registry,environment)->dev.penguinsuit.config.ManagementCommands.register(dispatcher,CONFIG));
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            if (!Boolean.getBoolean("penguinsuit.mixin.ready")) { state = "disabled: unsupported call site"; return; }
            try {
                BridgeConfig config = BridgeConfig.load(CONFIG);
                LOG.info("Bridge configuration: {}", CONFIG);
                Path stateDirectory = dev.penguinsuit.config.StatePaths.prepare(FabricLoader.getInstance().getConfigDir());
                dev.penguinsuit.config.RuntimeSettings.apply(config);
                mentions = new dev.penguinsuit.mention.MentionService(server, config,
                        stateDirectory.resolve("members.json"));
                try{replies=new dev.penguinsuit.reply.ReplyService(server,mentions,config,
                        stateDirectory.resolve("replies.json"));}
                catch(java.io.IOException e){LOG.error("Reply index unavailable; existing media bridge remains active: {}",e.getClass().getSimpleName());}
                service = new BridgeService(server, config,
                        stateDirectory.resolve("owned-media.json"));
                reverse = new ReverseBridge(server, config,
                        stateDirectory.resolve("qq-materials.json"));
                gameEvents = new dev.penguinsuit.events.GameEventService(server, config,
                        stateDirectory);
                state = "ready";
                LOG.info("26.2 Penguin suit ready. Penguin 1.1.7 + Chat Remastered 0.4.1+26.2.");
            } catch (Exception e) {
                if (gameEvents != null) { gameEvents.close(); gameEvents = null; }
                if(replies!=null){replies.close();replies=null;}
                if (mentions != null) { mentions.close(); mentions = null; }
                if (reverse != null) { reverse.close(); reverse = null; }
                if (service != null) { service.close(); service = null; }
                state = "disabled: " + e.getClass().getSimpleName();
                LOG.error("Bridge startup failed; Penguin forwarding remains available: {}", e.getMessage());
            }
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            var events = gameEvents; gameEvents = null; if (events != null) events.close();
            var replyService=replies;replies=null;if(replyService!=null)replyService.close();
            var directory = mentions; mentions = null; if (directory != null) directory.close();
            ReverseBridge outbound = reverse; reverse = null;
            if (outbound != null) outbound.close();
            BridgeService current = service; service = null;
            if (current != null) current.close();
            state = "stopped";
        });
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(server -> {
            var current = mentions; if (current != null) current.tick();
        });
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            var current = mentions; if (current != null) { current.observe(handler.player); current.refresh(); }
        });
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.DISCONNECT.register((handler,server)->{
            var current=mentions;if(current!=null)current.authentication().disconnect(handler.player.getUUID());
        });
        CommandRegistrationCallback.EVENT.register((dispatcher,registry,environment)->dev.penguinsuit.auth.AuthenticationService.register(dispatcher));
        net.fabricmc.fabric.api.message.v1.ServerMessageEvents.CHAT_MESSAGE.register((message, player, bound) -> {
            var current = mentions; if (current != null) current.observe(player);
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> dispatcher.register(
            Commands.literal("at").requires(source -> source.getPlayer() != null)
                .then(Commands.argument("targets", StringArgumentType.greedyString())
                    .suggests((ctx, builder) -> {
                        var current = mentions;
                        if (current != null) try {
                            current.refresh(); String input = builder.getInput(); int start = input.lastIndexOf(' ') + 1;
                            var next = builder.createOffset(Math.max(builder.getStart(), start));
                            String remaining = next.getRemaining().toLowerCase(java.util.Locale.ROOT);
                            for (String candidate : current.candidates(true))
                                if (candidate.toLowerCase(java.util.Locale.ROOT).startsWith(remaining)
                                        || candidate.substring(1).toLowerCase(java.util.Locale.ROOT).startsWith(remaining)) next.suggest(candidate);
                            return next.buildFuture();
                        } catch (dev.penguinsuit.mention.MemberDirectory.Invalid ignored) {}
                        return builder.buildFuture();
                    }).executes(ctx -> {
                        var current = mentions; var player = ctx.getSource().getPlayerOrException();
                        if (current == null || !current.mentionsEnabled()) { ctx.getSource().sendFailure(Component.literal("提及服务未启用")); return 0; }
                        try {
                            current.observe(player); current.refresh();
                            String body = dev.penguinsuit.mention.AtSyntax.expand(StringArgumentType.getString(ctx, "targets"),current.directory(),current.scope());
                            var bound = net.minecraft.network.chat.ChatType.bind(net.minecraft.network.chat.ChatType.CHAT, player);
                            // Command arguments have no player chat signature. The system sender makes
                            // vanilla use disguised chat, while retaining the bound player name and events.
                            ctx.getSource().getServer().getPlayerList().broadcastChatMessage(net.minecraft.network.chat.PlayerChatMessage.system(body),player,bound);
                            return 1;
                        } catch (dev.penguinsuit.mention.MemberDirectory.Invalid e) { current.failure(player,e.getMessage()); return 0; }
                    }))));
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) ->
                dispatcher.register(Commands.literal("penguinsuit")
                        .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_ADMIN))
                        .then(Commands.literal("bot").then(botCommand("exclude",true)).then(botCommand("include",false)))
                        .then(Commands.literal("status").executes(ctx -> {
                            BridgeService current = service;
                            ctx.getSource().sendSuccess(() -> Component.literal(current == null ? state : current.status()), false);
                            ReverseBridge outbound = reverse;
                            if (outbound != null) ctx.getSource().sendSuccess(() -> Component.literal(outbound.status()), false);
                            if (mentions != null) ctx.getSource().sendSuccess(() -> Component.literal(mentions.status()), false);
                            if (replies != null) ctx.getSource().sendSuccess(() -> Component.literal(replies.status()), false);
                            if (gameEvents != null) ctx.getSource().sendSuccess(() -> Component.literal(gameEvents.status()), false);
                            return 1;
                        }))
                        .then(Commands.literal("reload").executes(ctx -> {
                            try {
                                BridgeService current = service;
                                if (current == null) throw new IllegalStateException("Bridge not running; inspect startup log");
                                BridgeConfig next = BridgeConfig.load(CONFIG);
                                current.reconfigure(next);
                                if (reverse != null) reverse.reconfigure(next);
                                if (mentions != null) mentions.reconfigure(next);
                                if (replies != null) replies.reconfigure(next);
                                if (gameEvents != null) gameEvents.reconfigure(next);
                                dev.penguinsuit.config.RuntimeSettings.apply(next);
                                ctx.getSource().sendSuccess(() -> Component.literal("桥接配置已重载；未发送出站任务和待完成认证已取消，入站任务投递前重新检查转发权限。"), false); return 1;
                            } catch (Exception e) { ctx.getSource().sendFailure(Component.literal("重载失败：" + e.getMessage())); return 0; }
                        }))
                        .then(Commands.literal("delete").then(Commands.argument("imageId", StringArgumentType.word())
                                .suggests((ctx, builder) -> {
                                    BridgeService current = service;
                                    if (current != null) for (String id : current.ids()) if (id.startsWith(builder.getRemaining())) builder.suggest(id);
                                    return builder.buildFuture();
                                }).executes(ctx -> {
                                    BridgeService current = service;
                                    if (current == null) { ctx.getSource().sendFailure(Component.literal(state)); return 0; }
                                    current.delete(StringArgumentType.getString(ctx, "imageId"),
                                            text -> ctx.getSource().sendSuccess(() -> Component.literal(text), false)); return 1;
                                })))));
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) ->
                dispatcher.register(Commands.literal("penguinmedia")
                        .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_ADMIN))
                        .redirect(dispatcher.getRoot().getChild("penguinsuit"))));
    }
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<net.minecraft.commands.CommandSourceStack> botCommand(String action,boolean exclude){
        return Commands.literal(action).then(Commands.argument("member",StringArgumentType.greedyString())
                .suggests((ctx,builder)->{
                    var current=mentions;if(current!=null)try{
                        String remaining=builder.getRemaining().toLowerCase(java.util.Locale.ROOT);
                        for(String candidate:current.directory().qqAdminCandidates(current.scope()))
                            if(candidate.toLowerCase(java.util.Locale.ROOT).startsWith(remaining)||candidate.substring(1).toLowerCase(java.util.Locale.ROOT).startsWith(remaining))builder.suggest(candidate);
                    }catch(dev.penguinsuit.mention.MemberDirectory.Invalid ignored){}
                    return builder.buildFuture();
                }).executes(ctx->{
                    var current=mentions;if(current==null){ctx.getSource().sendFailure(Component.literal("提及服务未运行"));return 0;}
                    try{
                        String message=current.manualBot(StringArgumentType.getString(ctx,"member"),exclude);
                        ctx.getSource().sendSuccess(()->Component.literal(message),false);return 1;
                    }catch(Exception e){ctx.getSource().sendFailure(Component.literal("操作失败："+dev.penguinsuit.mention.MemberDirectory.safeName(e.getMessage())));return 0;}
                }));
    }
    public static boolean accept(MessageSnapshot message, String filtered, PenguinConfig config, Object handler) {
        BridgeService current = service;
        if (current == null) return false;
        try {
            var settings=dev.penguinsuit.config.RuntimeSettings.get();
            if(!dev.penguinsuit.config.RuntimeSettings.incoming(message.group(),message.user(),!message.media().isEmpty()))return true;
            if (current.accept(message, filtered, config, handler)) return true;
            if(settings.enabled&&!settings.forwarding.qqImages&&!message.media().isEmpty()){
                deliverIncomingText(message,filtered,config,handler);return true;
            }
            return incomingText(message,filtered,config,handler);
        }
        catch (RuntimeException | LinkageError e) {
            if (message.mention() != null) {
                LOG.error("Mention forwarding failed closed: {}", e.getClass().getSimpleName());
                if (mentions != null) mentions.incomingFailure(message, "提及转发内部错误");
                return true;
            }
            LOG.error("Bridge admission failed; preserving Penguin fallback: {}", e.getClass().getSimpleName());
            return false;
        }
    }
    private static boolean incomingText(MessageSnapshot message,String filtered,PenguinConfig config,Object handler){
        if(replies!=null&&replies.incomingText(message,filtered,config,handler))return true;
        if(message.mention()!=null&&mentions!=null){mentions.incomingText(message,config,handler);return true;}
        return false;
    }
    public static void deliverIncomingText(MessageSnapshot message,String filtered,PenguinConfig config,Object handler){
        if(incomingText(message,filtered,config,handler))return;
        var server=com.huhobot.penguin.PenguinServerMod.INSTANCE.getServer();
        if(server!=null)server.execute(()->{
            var penguin=com.huhobot.penguin.PenguinServerMod.INSTANCE;
            if(config!=penguin.getConfig()||handler!=penguin.getCommandHandler()||!config.getChatPostChat()
                    ||!penguin.getState().isFullForwarding(message.group())
                    ||!dev.penguinsuit.config.RuntimeSettings.incoming(message.group(),message.user(),!message.media().isEmpty()))return;
            String body=filtered+(message.media().isEmpty()?"":filtered.isBlank()?"[图片]":" [图片]");
            String line=penguin.formatGroupMessage(message.name(),message.filteredMessage(body));
            server.getPlayerList().broadcastSystemMessage(Component.literal(line),false);
        });
    }
    public static dev.penguinsuit.emoji.QqEmojiMessage.Parsed qqEmojis(String text){
        var current=service;
        var parsed=current!=null&&current.enabled()?dev.penguinsuit.emoji.QqEmojiMessage.parse(text)
                :dev.penguinsuit.emoji.QqEmojiMessage.untouched(text);
        if(parsed.count()>0&&dev.penguinsuit.config.BridgeLog.detailed("emoji"))LOG.info("[emoji] decoded tags={} imageCandidates={} unknown={} shape={}",
                parsed.count(),parsed.faces().size(),parsed.unknown(),dev.penguinsuit.emoji.QqEmojiMessage.shape(text));
        return parsed;
    }
    public static void playerImageAccepted(MinecraftServer server, String imageId) {
        ReverseBridge current = reverse;
        if (current == null || ChatRemasteredState.currentServer != server) return;
        try { current.accepted(imageId, ChatRemasteredState.externalPendingBroadcasts.get(imageId)); }
        catch (RuntimeException | LinkageError e) {
            LOG.warn("[Penguin suit] MC->QQ admission failed: {}", e.getClass().getSimpleName());
        }
    }
}
