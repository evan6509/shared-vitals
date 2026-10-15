package com.evanchubbuck.sharedvitals.mixin;

import com.evanchubbuck.sharedvitals.SharedVitalsState;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.food.FoodData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FoodData.class)
public abstract class FoodDataMixin {
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void sharedVitals$tickOnce(ServerPlayer player, CallbackInfo ci) {
        if (!SharedVitalsState.get(player.level().getServer()).allowFoodTick((FoodData) (Object) this, player)) {
            ci.cancel();
        }
    }
}
