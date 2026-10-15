package com.evanchubbuck.sharedvitals;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Entry point for Shared Vitals. Shared health and hunger will be implemented here. */
public final class SharedVitalsMod implements ModInitializer {
    public static final String MOD_ID = "shared_vitals";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("Shared Vitals initialized (project scaffold)");
    }
}
