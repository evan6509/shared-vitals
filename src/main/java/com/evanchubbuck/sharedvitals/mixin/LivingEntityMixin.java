package com.evanchubbuck.sharedvitals.mixin;

import com.evanchubbuck.sharedvitals.SharedVitalsState;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collection;

@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
    @WrapMethod(method = "hurtServer")
    private boolean sharedVitals$shareHurtFeedback(ServerLevel level, DamageSource source, float amount,
                                                  Operation<Boolean> original) {
        var player = (Object) this instanceof ServerPlayer candidate && candidate.connection != null ? candidate : null;
        float previousHealth = player == null ? 0 : player.getHealth();
        float previousAbsorption = player == null ? 0 : player.getAbsorptionAmount();
        boolean damaged = original.call(level, source, amount);
        // Wait for vanilla's defenses and totem handling. Health setters also handle healing,
        // joins and health-cap changes, none of which should sound like a shared hit.
        if (damaged && player != null && (player.getHealth() < previousHealth || player.getAbsorptionAmount() < previousAbsorption)) {
            SharedVitalsState.get(level.getServer()).shareHurtFeedback(player, source);
        }
        return damaged;
    }

    @Inject(method = "setHealth", at = @At("RETURN"))
    private void sharedVitals$healthChanged(float health, CallbackInfo ci) {
        if ((Object) this instanceof ServerPlayer player && player.connection != null) {
            SharedVitalsState.get(player.level().getServer()).healthChanged(player);
        }
    }

    @Inject(method = "setAbsorptionAmount", at = @At("RETURN"))
    private void sharedVitals$absorptionChanged(float absorption, CallbackInfo ci) {
        if ((Object) this instanceof ServerPlayer player && player.connection != null) {
            SharedVitalsState.get(player.level().getServer()).absorptionChanged(player);
        }
    }

    @Inject(method = "onEffectAdded", at = @At("RETURN"))
    private void sharedVitals$effectAdded(MobEffectInstance effect, Entity source, CallbackInfo ci) {
        if ((Object) this instanceof ServerPlayer player && player.connection != null) {
            SharedVitalsState.get(player.level().getServer()).effectChanged(player, effect.getEffect());
        }
    }

    @Inject(method = "onEffectUpdated", at = @At("RETURN"))
    private void sharedVitals$effectUpdated(MobEffectInstance effect, boolean refreshAttributes, Entity source, CallbackInfo ci) {
        if (refreshAttributes && (Object) this instanceof ServerPlayer player && player.connection != null) {
            SharedVitalsState.get(player.level().getServer()).effectChanged(player, effect.getEffect());
        }
    }

    @Inject(method = "onEffectsRemoved", at = @At("RETURN"))
    private void sharedVitals$effectsRemoved(Collection<MobEffectInstance> effects, CallbackInfo ci) {
        if ((Object) this instanceof ServerPlayer player && player.connection != null) {
            for (var effect : effects) SharedVitalsState.get(player.level().getServer()).effectChanged(player, effect.getEffect());
        }
    }
}
