package com.evanchubbuck.sharedvitals;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.Permissions;
import org.slf4j.LoggerFactory;

import java.io.IOException;

public final class SharedVitalsCommands {
    private SharedVitalsCommands() { }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("sharedvitals")
                .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                .executes(context -> status(context.getSource()))
                .then(Commands.literal("status").executes(context -> status(context.getSource())))
                .then(Commands.literal("linkedhardcore")
                        .executes(context -> status(context.getSource()))
                        .then(Commands.argument("enabled", BoolArgumentType.bool()).executes(context ->
                                configure(context.getSource(), BoolArgumentType.getBool(context, "enabled"))))));
    }

    private static int configure(CommandSourceStack source, boolean enabled) throws CommandSyntaxException {
        var server = source.getServer();
        var settings = SharedVitalsSettings.get(server);
        if (enabled && !SharedVitalsSettings.linkedHardcoreInstalled()) {
            throw error("Install the Linked Hardcore Fabric mod on this backend before enabling integration.");
        }
        if (settings.linkedHardcore() != enabled && SharedVitalsState.get(server).waitingForTransfer()) {
            throw error("This world's run has ended. Let Linked Hardcore finish the transfer before changing mode.");
        }
        try {
            settings.setLinkedHardcore(enabled);
        } catch (IOException failure) {
            LoggerFactory.getLogger(SharedVitalsMod.MOD_ID).error("Could not save Shared Vitals settings", failure);
            throw error("Could not save config/shared-vitals.json. The mode was not changed; check the server log.");
        }
        source.sendSuccess(() -> Component.literal("Linked Hardcore integration " + (enabled ? "enabled" : "disabled")
                + " on this backend. Saved across restarts and world resets."), true);
        return 1;
    }

    private static int status(CommandSourceStack source) {
        var server = source.getServer();
        source.sendSuccess(() -> Component.literal("Linked Hardcore integration: "
                + SharedVitalsSettings.get(server).linkedHardcore()
                + ". Linked Hardcore installed: " + SharedVitalsSettings.linkedHardcoreInstalled()
                + ". " + (SharedVitalsState.get(server).waitingForTransfer()
                ? "This run has ended; waiting for Linked Hardcore's transfer."
                : "Shared health and hunger are active for survival/adventure players.")), false);
        return 1;
    }

    private static CommandSyntaxException error(String message) {
        return new SimpleCommandExceptionType(Component.literal(message)).create();
    }
}
