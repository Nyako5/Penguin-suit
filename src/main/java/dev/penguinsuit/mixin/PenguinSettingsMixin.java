package dev.penguinsuit.mixin;

import dev.penguinsuit.config.RuntimeSettings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Optional format overrides keep every upstream/bridge formatter consistent. */
@Mixin(targets="com.huhobot.penguin.config.PenguinConfig",remap=false)
public abstract class PenguinSettingsMixin {
    @Inject(method={"getGroupMemberEventToGame","getGroupJoinRequestToGame"},at=@At("HEAD"),cancellable=true)
    private void events(CallbackInfoReturnable<Boolean> ci){var c=RuntimeSettings.get();if(c.enabled&&(!c.forwarding.qqToGame||!c.forwarding.qqEvents))ci.setReturnValue(false);}
    @Inject(method="getChatFromGame",at=@At("HEAD"),cancellable=true)
    private void game(CallbackInfoReturnable<String> ci){var c=RuntimeSettings.get();if(c.enabled&&!c.messages.fromGame.isEmpty())ci.setReturnValue(c.messages.fromGame);}
    @Inject(method="getChatFromGroup",at=@At("HEAD"),cancellable=true)
    private void group(CallbackInfoReturnable<String> ci){var c=RuntimeSettings.get();if(c.enabled&&!c.messages.fromGroup.isEmpty())ci.setReturnValue(c.messages.fromGroup);}
    @Inject(method="getJoinFormat",at=@At("HEAD"),cancellable=true)
    private void join(CallbackInfoReturnable<String> ci){var c=RuntimeSettings.get();if(c.enabled&&!c.messages.playerJoin.isEmpty())ci.setReturnValue(c.messages.playerJoin);}
    @Inject(method="getLeaveFormat",at=@At("HEAD"),cancellable=true)
    private void leave(CallbackInfoReturnable<String> ci){var c=RuntimeSettings.get();if(c.enabled&&!c.messages.playerLeave.isEmpty())ci.setReturnValue(c.messages.playerLeave);}
}
