package com.evanchubbuck.sharedvitals.mixin;

import com.evanchubbuck.sharedvitals.SharedVitalsState;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(MobEffectInstance.class)
public abstract class MobEffectInstanceMixin {
    @WrapMethod(method = "tickServer")
    private boolean sharedVitals$tickOnce(ServerLevel level, LivingEntity entity, Runnable onUpdate,
                                          Operation<Boolean> original) {
        if (entity instanceof ServerPlayer player) {
            var state = SharedVitalsState.get(level.getServer());
            var effect = (MobEffectInstance) (Object) this;
            if (state.isSharedEffect(player, effect) && !state.allowEffectTick(player, effect)) return true;
        }
        return original.call(level, entity, onUpdate);
    }
}
