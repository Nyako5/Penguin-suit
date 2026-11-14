package dev.penguinsuit.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.penguinsuit.PenguinSuit;
import dev.penguinsuit.events.GameEventService;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(value = ServerPlayer.class, remap = false)
public abstract class PlayerDeathEventMixin {
    @WrapMethod(method = "die(Lnet/minecraft/world/damagesource/DamageSource;)V")
    private void penguinsuit$death(DamageSource source, Operation<Void> original) {
        var events = PenguinSuit.gameEvents(); var player = (ServerPlayer)(Object)this;
        if (events == null) { original.call(source); return; }
        Component message = null; int before = 0;
        try {
            // Capture before vanilla clears combat history; use death stat transition to confirm completion.
            before = player.getStats().getValue(Stats.CUSTOM.get(Stats.DEATHS));
            message = player.getCombatTracker().getDeathMessage().copy();
        } catch (RuntimeException | LinkageError e) { GameEventService.hookFailure("death-snapshot", e); }
        original.call(source);
        try {
            if (message != null && player.getStats().getValue(Stats.CUSTOM.get(Stats.DEATHS)) != before) events.death(player, message);
        } catch (RuntimeException | LinkageError e) { GameEventService.hookFailure("death-confirm", e); }
    }
}
