package dev.penguinsuit.config;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.penguinsuit.PenguinSuit;
import net.minecraft.commands.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.Permissions;
import java.nio.file.Path;

public final class ManagementCommands {
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher,Path file){
        var config=Commands.literal("config").then(Commands.literal("list").executes(c->{tell(c.getSource(),String.join(", ",ConfigEditor.keys()));return 1;}));
        for(String action:new String[]{"get","set"}){
            var key=Commands.argument("key",StringArgumentType.word()).suggests((c,b)->{for(String k:ConfigEditor.keys())if(k.startsWith(b.getRemaining()))b.suggest(k);return b.buildFuture();});
            if(action.equals("get"))key.executes(c->run(c.getSource(),()->ConfigEditor.get(file,StringArgumentType.getString(c,"key"))));
            else key.then(Commands.argument("value",StringArgumentType.greedyString()).executes(c->run(c.getSource(),()->{
                String k=StringArgumentType.getString(c,"key");ConfigEditor.set(file,k,StringArgumentType.getString(c,"value"));
                return "已保存 "+ConfigEditor.canonicalKey(k)+"；"+(ConfigEditor.requiresRestart(k)?"需要重启服务器。":"执行 /penguinsuit reload 生效；会取消未完成认证和未发送出站任务。");
            })));
            config.then(Commands.literal(action).then(key));
        }
        var members=Commands.literal("members").then(Commands.literal("list").executes(c->listMembers(c.getSource(),1))
            .then(Commands.argument("page",com.mojang.brigadier.arguments.IntegerArgumentType.integer(1)).executes(c->listMembers(c.getSource(),com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c,"page")))))
            .then(Commands.literal("prune").then(Commands.literal("confirm").executes(c->run(c.getSource(),()->{
            var m=PenguinSuit.mentions();return m==null?"成员服务未运行":"已清理 "+m.pruneDirectory()+" 个过期未绑定成员";
        })))).then(Commands.literal("forget").then(Commands.argument("identity",StringArgumentType.word()).then(Commands.literal("confirm").executes(c->run(c.getSource(),()->{
            var m=PenguinSuit.mentions();return m==null?"成员服务未运行":m.forgetMember(StringArgumentType.getString(c,"identity"))?"记录已删除；旧编号保持失效":"未找到记录";
        })))));
        var cache=Commands.literal("cache");
        cache.then(Commands.literal("preview").executes(c->{PenguinSuit.manageCache("preview",text->tell(c.getSource(),text));return 1;}));
        for(String action:new String[]{"expired","clear"})cache.then(Commands.literal(action).then(Commands.literal("confirm").executes(c->{PenguinSuit.manageCache(action,text->tell(c.getSource(),text));return 1;})));
        dispatcher.register(Commands.literal("penguinsuit").requires(s->s.permissions().hasPermission(Permissions.COMMANDS_ADMIN)).then(config).then(members).then(cache));
    }
    @FunctionalInterface private interface Action{String run()throws Exception;}
    private static int listMembers(CommandSourceStack source,int page){return run(source,()->{
        var m=PenguinSuit.mentions();if(m==null)return "成员服务未运行";
        var rows=m.directory().records();int pages=Math.max(1,(rows.size()+19)/20);if(page>pages)return "页码超出范围，共 "+pages+" 页";
        rows.stream().skip((long)(page-1)*20).limit(20).forEach(line->tell(source,line));return "成员 "+rows.size()+" 个，第 "+page+"/"+pages+" 页";
    });}
    private static int run(CommandSourceStack source,Action action){try{tell(source,action.run());return 1;}catch(Exception e){source.sendFailure(Component.literal("操作失败："+dev.penguinsuit.mention.MemberDirectory.safeName(e.getMessage())));return 0;}}
    private static void tell(CommandSourceStack source,String text){source.sendSuccess(()->Component.literal(text),false);}
    private ManagementCommands(){}
}
