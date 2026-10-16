package com.evanchubbuck.sharedvitals;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Server-side shared vitals; vanilla clients receive the ordinary health/food packets. */
public final class SharedVitalsMod implements ModInitializer {
    public static final String MOD_ID = "shared_vitals";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, access, environment) -> SharedVitalsCommands.register(dispatcher));
        ServerLifecycleEvents.SERVER_STARTING.register(SharedVitalsSettings::start);
        ServerLifecycleEvents.SERVER_STOPPED.register(SharedVitalsSettings::stop);
        ServerPlayerEvents.JOIN.register(player -> SharedVitalsState.get(player.level().getServer()).join(player));
        ServerPlayerEvents.LEAVE.register(player -> SharedVitalsState.get(player.level().getServer()).leave(player));
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) ->
                SharedVitalsState.get(newPlayer.level().getServer()).respawn(oldPlayer, newPlayer, alive));
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (entity instanceof ServerPlayer player) {
                SharedVitalsState.get(player.level().getServer()).died(player, source);
            }
        });
        ServerTickEvents.START_SERVER_TICK.register(server -> SharedVitalsState.get(server).tick(server));
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            var state = SharedVitalsState.get(server);
            state.captureAbsorptionEffect();
            state.setDirty();
        });
        LOGGER.info("Shared Vitals initialized: survival and adventure players share health and hunger");
    }
}
