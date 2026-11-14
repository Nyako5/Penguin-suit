package dev.penguinsuit.mixin;

import com.huhobot.penguin.qq.GroupMessage;
import dev.penguinsuit.PenguinSuit;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** At this point the native group allowlist has passed, but command parameters are not logged yet. */
@Mixin(targets="com.huhobot.penguin.command.CommandHandler",remap=false)
public abstract class AuthenticationMixin {
    @Shadow private kotlin.Pair<String,String> findCommand(String input){throw new AssertionError();}
    @Shadow private String normalizeContent(String input){throw new AssertionError();}
    @Inject(method="handle",at=@At(value="INVOKE",target="Lcom/huhobot/penguin/command/CommandHandler;findCommand(Ljava/lang/String;)Lkotlin/Pair;"),cancellable=true)
    private void penguinsuit$authenticate(GroupMessage message,CallbackInfo ci){
        var options=dev.penguinsuit.config.RuntimeSettings.get();
        if(options.enabled&&(options.blockedGroups.contains(message.getGroupId())||options.blockedUsers.contains(message.getUserId()))){ci.cancel();return;}
        var service=PenguinSuit.mentions();
        if(options.enabled&&!options.forwarding.qqCommands&&findCommand(normalizeContent(message.getContent()))!=null){ci.cancel();return;}
        String command="/"+normalizeContent(message.getContent());
        if(options.enabled&&!options.forwarding.qqCommands&&dev.penguinsuit.auth.AuthenticationService.isCommand(command)){ci.cancel();return;}
        if(service!=null&&service.authentication().incoming(message,command,this))ci.cancel();
        else if(service==null&&dev.penguinsuit.auth.AuthenticationService.isCommand(command))ci.cancel();
    }
}
