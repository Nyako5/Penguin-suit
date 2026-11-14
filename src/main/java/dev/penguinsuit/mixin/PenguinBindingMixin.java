package dev.penguinsuit.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.penguinsuit.PenguinSuit;
import org.spongepowered.asm.mixin.Mixin;

/** Serialize binding mutations with directory snapshots without changing Penguin's saved format. */
@Mixin(targets="com.huhobot.penguin.state.BotState",remap=false)
public abstract class PenguinBindingMixin {
    @WrapMethod(method="save")
    private void penguinsuit$serializeSave(Operation<Void> original){synchronized(this){original.call();}}
    @WrapMethod(method="bindName")
    private String penguinsuit$bind(String group,String openid,String name,Operation<String> original){
        String result;boolean changed;
        synchronized(this){
            var state=(com.huhobot.penguin.state.BotState)(Object)this;
            String before=state.getBindingName(group,openid);result=original.call(group,openid,name);
            changed=!java.util.Objects.equals(before,state.getBindingName(group,openid));
        }
        var service=PenguinSuit.mentions();if(service!=null&&!dev.penguinsuit.mention.PenguinBindings.ownWrite()){if(changed)service.bindingChanged(group,openid);service.execute(service::refresh);}return result;
    }
    @WrapMethod(method="unbindOpenid")
    private String penguinsuit$unbind(String group,String openid,Operation<String> original){
        String result;boolean changed;
        synchronized(this){
            var state=(com.huhobot.penguin.state.BotState)(Object)this;
            String before=state.getBindingName(group,openid);result=original.call(group,openid);
            changed=!java.util.Objects.equals(before,state.getBindingName(group,openid));
        }
        var service=PenguinSuit.mentions();if(service!=null&&changed&&!dev.penguinsuit.mention.PenguinBindings.ownWrite())service.bindingChanged(group,openid);
        // The directory owns deletion intent; refresh may itself call unbind, so defer reconciliation to the tick.
        return result;
    }
}
