package dev.penguinsuit.mixin;

import com.huhobot.penguin.qq.QQClient;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Reuse Penguin's bounded, rate-spaced queue and token lifecycle. */
@Mixin(value = QQClient.class, remap = false)
public interface QQClientAccess {
    @Invoker("enqueue") boolean penguinsuit$enqueue(Function0<Unit> task);
    @Invoker("invalidateToken") void penguinsuit$invalidateToken();
}
