package com.evanchubbuck.sharedvitals.mixin;

import com.evanchubbuck.sharedvitals.SharedVitalsState;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Also latch occupied sources when the death happened on a different backend or the proxy recovered. */
@Pseudo
@Mixin(targets = "dev.linkedhardcore.fabric.death.TransferCountdown", remap = false)
public abstract class LinkedHardcoreTransferMixin {
    @Inject(method = {"waitForServer", "start"}, at = @At("HEAD"), remap = false)
    private void sharedVitals$prepareTransfer(MinecraftServer server, CallbackInfo ci) {
        SharedVitalsState.get(server).prepareLinkedTransfer(server);
    }
}
