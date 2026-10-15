package com.evanchubbuck.sharedvitals.mixin;

import com.evanchubbuck.sharedvitals.SharedVitalsState;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
    @Inject(method = "setHealth", at = @At("RETURN"))
    private void sharedVitals$healthChanged(float health, CallbackInfo ci) {
        if ((Object) this instanceof ServerPlayer player && player.connection != null) {
            SharedVitalsState.get(player.level().getServer()).healthChanged(player);
        }
    }
}
