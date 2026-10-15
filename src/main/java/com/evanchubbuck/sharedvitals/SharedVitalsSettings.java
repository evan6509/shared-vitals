package com.evanchubbuck.sharedvitals;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.WeakHashMap;

/** Backend configuration lives outside the world so Linked Hardcore's executor preserves it. */
public final class SharedVitalsSettings {
    private static final Map<MinecraftServer, SharedVitalsSettings> SERVERS = new WeakHashMap<>();
    private final Path file;
    private boolean linkedHardcore;

    SharedVitalsSettings(Path file) throws IOException {
        this.file = file;
        if (Files.exists(file)) {
            try {
                var json = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                var value = json.get("linkedHardcore");
                if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
                    throw new IllegalArgumentException("linkedHardcore must be true or false");
                }
                linkedHardcore = value.getAsBoolean();
            } catch (RuntimeException error) {
                throw new IOException("Invalid Shared Vitals configuration: " + file, error);
            }
        }
    }

    public static SharedVitalsSettings get(MinecraftServer server) {
        return SERVERS.computeIfAbsent(server, ignored -> {
            try {
                return new SharedVitalsSettings(FabricLoader.getInstance().getConfigDir().resolve("shared-vitals.json"));
            } catch (IOException error) {
                throw new IllegalStateException("Could not load Shared Vitals configuration", error);
            }
        });
    }

    public static void start(MinecraftServer server) {
        if (get(server).linkedHardcore() && !linkedHardcoreInstalled()) {
            throw new IllegalStateException("Shared Vitals Linked Hardcore mode is enabled, but the linkedhardcore mod "
                    + "is missing. Install it or set linkedHardcore to false in config/shared-vitals.json.");
        }
    }

    public static void stop(MinecraftServer server) {
        SERVERS.remove(server);
    }

    public static boolean linkedHardcoreInstalled() {
        return FabricLoader.getInstance().isModLoaded("linkedhardcore");
    }

    public boolean linkedHardcore() {
        return linkedHardcore;
    }

    void setLinkedHardcore(boolean enabled) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        var json = new JsonObject();
        json.addProperty("linkedHardcore", enabled);
        Path temporary = Files.createTempFile(file.toAbsolutePath().getParent(), "shared-vitals-", ".tmp");
        try {
            Files.writeString(temporary, new GsonBuilder().setPrettyPrinting().create().toJson(json) + "\n");
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            // Never claim the setting changed if persistence failed.
            linkedHardcore = enabled;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
