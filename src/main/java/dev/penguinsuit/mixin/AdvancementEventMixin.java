package dev.penguinsuit.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.penguinsuit.PenguinSuit;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(value = PlayerAdvancements.class, remap = false)
public abstract class AdvancementEventMixin {
    @Shadow private ServerPlayer player;
    @Shadow public abstract AdvancementProgress getOrStartProgress(AdvancementHolder holder);
    @WrapMethod(method = "award(Lnet/minecraft/advancements/AdvancementHolder;Ljava/lang/String;)Z")
    private boolean penguinsuit$completed(AdvancementHolder holder, String criterion, Operation<Boolean> original) {
        var events = PenguinSuit.gameEvents();
        if (events == null) return original.call(holder, criterion);
        boolean done = getOrStartProgress(holder).isDone();
        boolean changed = original.call(holder, criterion);
        if (changed && !done && getOrStartProgress(holder).isDone()) events.advancement(player, holder);
        return changed;
    }
}
