package dev.penguinsuit.mixin;

import com.huhobot.penguin.qq.GroupMessage;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.penguinsuit.bridge.QqMentions;
import java.util.Map;
import kotlin.jvm.functions.Function1;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(targets = "com.huhobot.penguin.qq.QQClient", remap = false)
public abstract class QQMentionMixin {
    @WrapOperation(method = "onGroupMessage(Ljava/util/Map;)V",
            at = @At(value = "INVOKE", target = "Lkotlin/jvm/functions/Function1;invoke(Ljava/lang/Object;)Ljava/lang/Object;", remap = false),
            require = 1, expect = 1, allow = 1, remap = false)
    private Object penguinsuit$mentionScope(Function1<?, ?> listener, Object value,
            Operation<Object> original, Map<?, ?> event) {
        if (!(value instanceof GroupMessage message)) return original.call(listener, value);
        var replies=dev.penguinsuit.PenguinSuit.replies();
        var reference=replies!=null&&replies.enabled()?replies.captureIncoming(event):dev.penguinsuit.reply.QqReplyData.NONE;
        GroupMessage normalized=reference==dev.penguinsuit.reply.QqReplyData.NONE?message:reference.normalize(message);
        var directory = dev.penguinsuit.PenguinSuit.mentions();
        if (directory != null) directory.observeQq(normalized, event.get("mentions"), event.get("author"));
        return dev.penguinsuit.reply.QqReplyData.during(normalized,reference,
                ()->QqMentions.during(normalized,event.get("mentions"),()->original.call(listener,normalized)));
    }
}
