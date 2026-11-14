package dev.penguinsuit.mixin;

import com.huhobot.penguin.config.PenguinConfig;
import com.huhobot.penguin.filter.TextFilter;
import com.huhobot.penguin.qq.GroupMessage;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.penguinsuit.PenguinSuit;
import dev.penguinsuit.bridge.MessageSnapshot;
import dev.penguinsuit.bridge.QqMentions;
import kotlin.Unit;
import kotlin.jvm.functions.Function1;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(targets = "com.huhobot.penguin.command.CommandHandler", remap = false)
public abstract class PenguinForwardMixin {
    @WrapOperation(method = "handle(Lcom/huhobot/penguin/qq/GroupMessage;)V",
            at = @At(value = "INVOKE", target = "Lcom/huhobot/penguin/filter/TextFilter;audit(Ljava/lang/String;Lcom/huhobot/penguin/config/PenguinConfig;Lkotlin/jvm/functions/Function1;)V", remap = false),
            require = 1, expect = 1, allow = 1, remap = false)
    private void penguinsuit$wrapAudit(TextFilter instance, String value, PenguinConfig config,
            Function1<? super String, Unit> callback, Operation<Void> original, GroupMessage message) {
        if(!dev.penguinsuit.config.RuntimeSettings.incoming(message.getGroupId(),message.getUserId(),message.getAttachments()!=null&&!message.getAttachments().isEmpty()))return;
        // Decode before moderation/mention parsing. Keep trusted media metadata separate from text.
        final var emojis=PenguinSuit.qqEmojis(value);
        value=emojis.text();
        var directory = PenguinSuit.mentions();
        if (directory != null && directory.isScope(message.getGroupId())) {
            final String raw = value;
            final MessageSnapshot captured=MessageSnapshot.capture(message);
            directory.execute(() -> {
                try {
                    var incoming = directory.incoming(message, raw);
                    String audited = incoming == null ? QqMentions.forAudit(message, raw) : incoming.auditText();
                    MessageSnapshot snapshot = captured.withMention(incoming).withEmojis(emojis.audit(audited));
                    original.call(instance, audited, config, (Function1<String, Unit>) filtered -> {
                        try {
                            if (incoming != null) incoming.rendered().auditText(filtered);
                            if (!PenguinSuit.accept(snapshot, filtered, config, this)) callback.invoke(filtered);
                        } catch (dev.penguinsuit.mention.MemberDirectory.Invalid e) { directory.incomingFailure(snapshot,e.getMessage()); }
                        return Unit.INSTANCE;
                    });
                } catch (dev.penguinsuit.mention.MemberDirectory.Invalid e) { directory.incomingFailure(captured,e.getMessage()); }
            });
            return;
        }
        // Keep raw content for command parsing; restore readable mentions only for audited chat.
        value = QqMentions.forAudit(message, value);
        MessageSnapshot snapshot;
        try { snapshot = MessageSnapshot.capture(message).withEmojis(emojis.audit(value)); }
        catch (RuntimeException e) { original.call(instance, value, config, callback); return; }
        Function1<String, Unit> wrapped = filtered -> {
            if (!PenguinSuit.accept(snapshot, filtered, config, this)) callback.invoke(filtered);
            return Unit.INSTANCE;
        };
        original.call(instance, value, config, wrapped);
    }
}
