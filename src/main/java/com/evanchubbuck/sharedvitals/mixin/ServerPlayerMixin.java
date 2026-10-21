package com.evanchubbuck.sharedvitals.mixin;

import com.evanchubbuck.sharedvitals.SharedVitalsState;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin {
    @Inject(method = "tickRegeneration", at = @At("HEAD"), cancellable = true)
    private void sharedVitals$peacefulRegenerationOnce(CallbackInfo ci) {
        var player = (ServerPlayer) (Object) this;
        var state = SharedVitalsState.get(player.level().getServer());
        if (!state.allowPeacefulRegeneration(player)) ci.cancel();
    }
}
