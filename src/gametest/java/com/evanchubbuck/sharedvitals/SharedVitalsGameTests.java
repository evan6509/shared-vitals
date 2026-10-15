package com.evanchubbuck.sharedvitals;

import com.evanchubbuck.sharedvitals.mixin.FoodDataAccessor;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.serialization.JsonOps;
import io.netty.channel.embedded.EmbeddedChannel;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.SavedDataStorage;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.HashMap;
import java.util.Map;
import java.io.IOException;
import java.lang.reflect.Proxy;

public final class SharedVitalsGameTests {
    private static final Map<UUID, Integer> DEATHS = new HashMap<>();
    static {
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (entity instanceof ServerPlayer player) DEATHS.computeIfPresent(player.getUUID(), (id, count) -> count + 1);
        });
    }

    @GameTest
    public void damageAndHealingShareImmediately(GameTestHelper helper) {
        try (var f = new Fixture(helper)) {
            var a = f.player();
            var b = f.player();
            a.hurtServer(a.level(), a.damageSources().generic(), 6);
            helper.assertValueEqual(a.getHealth(), 14F, "Vanilla damage is applied");
            helper.assertValueEqual(b.getHealth(), 14F, "Damage reaches the second player immediately");
            b.heal(3);
            helper.assertValueEqual(a.getHealth(), 17F, "Healing from either player reaches the pool");
            helper.assertValueEqual(b.getHealth(), 17F, "Both players have identical health");
        }
        helper.succeed();
    }

    @GameTest
    public void simultaneousHitsAccumulate(GameTestHelper helper) {
        try (var f = new Fixture(helper)) {
            var a = f.player();
            var b = f.player();
            a.hurtServer(a.level(), a.damageSources().generic(), 4);
            b.hurtServer(b.level(), b.damageSources().generic(), 5);
            helper.assertValueEqual(a.getHealth(), 11F, "Hits on different players in one tick accumulate");
            helper.assertValueEqual(b.getHealth(), 11F, "No stale health overwrites a hit");
        }
        helper.succeed();
    }

    @GameTest
    public void armorAndAbsorptionApplyToVictimOnly(GameTestHelper helper) {
        try (var f = new Fixture(helper)) {
            var a = f.player();
            var b = f.player();
            a.getAttribute(Attributes.MAX_ABSORPTION).setBaseValue(20);
            a.setAbsorptionAmount(4);
            a.hurtServer(a.level(), a.damageSources().generic(), 6);
            helper.assertValueEqual(a.getHealth(), 18F, "Personal absorption intercepts four damage");
            helper.assertValueEqual(b.getHealth(), 18F, "Only health damage is shared");
            a.invulnerableTime = 0;
            a.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
            a.doTick(); // Vanilla installs equipment attribute modifiers during the entity tick.
            helper.assertTrue(a.getArmorValue() > 0, "Equipped armor attributes are active");
            a.hurtServer(a.level(), a.damageSources().mobAttack(b), 6);
            helper.assertTrue(a.getHealth() > 12F, "Victim's armor reduces health damage");
            helper.assertValueEqual(a.getHealth(), b.getHealth(), "Armor reduction is applied once");
        }
        helper.succeed();
    }

    @GameTest
    public void foodAndExhaustionCombine(GameTestHelper helper) {
        try (var f = new Fixture(helper)) {
            var a = f.player();
            var b = f.player();
            a.getFoodData().setFoodLevel(10);
            a.getFoodData().setSaturation(0);
            b.getFoodData().eat(new FoodProperties(4, 2, false));
            helper.assertValueEqual(a.getFoodData().getFoodLevel(), 14, "Eating feeds the group");
            helper.assertValueEqual(a.getFoodData().getSaturationLevel(), 2F, "Saturation is shared");
            a.causeFoodExhaustion(2);
            b.causeFoodExhaustion(3);
            helper.assertValueEqual(FoodSnapshot.capture(a.getFoodData()).exhaustion(), 5F,
                    "Exhaustion from both players accumulates");
            a.getFoodData().tick(a);
            b.getFoodData().tick(b);
            helper.assertValueEqual(a.getFoodData().getSaturationLevel(), 1F, "Exhaustion is consumed once");
            helper.assertValueEqual(FoodSnapshot.capture(b.getFoodData()).exhaustion(), 1F,
                    "The group retains its remaining exhaustion");
        }
        helper.succeed();
    }

    @GameTest
    public void regenerationAndStarvationRunOnce(GameTestHelper helper) {
        try (var f = new Fixture(helper)) {
            var a = f.player();
            var b = f.player();
            a.setHealth(10);
            ((FoodDataAccessor) a.getFoodData()).sharedVitals$setTimer(8);
            a.getFoodData().tick(a);
            b.getFoodData().tick(b);
            helper.assertValueEqual(FoodSnapshot.capture(a.getFoodData()).timer(), 9,
                    "Two players advance the shared food timer once");
            helper.assertValueEqual(a.getHealth(), 10F, "Player count does not speed up healing");
        }
        try (var f = new Fixture(helper)) {
            var a = f.player();
            var b = f.player();
            a.getFoodData().setFoodLevel(0);
            a.getFoodData().setSaturation(0);
            ((FoodDataAccessor) a.getFoodData()).sharedVitals$setTimer(79);
            a.getFoodData().tick(a);
            b.getFoodData().tick(b);
            helper.assertValueEqual(a.getHealth(), 19F, "Starvation removes one point from the pool");
            helper.assertValueEqual(b.getHealth(), 19F, "Starvation reaches the group");
        }
        helper.succeed();
    }

    @GameTest
    public void naturalHealingAndPeacefulHealingRunOnce(GameTestHelper helper) {
        try (var f = new Fixture(helper)) {
            var a = f.player();
            var b = f.player();
            a.setHealth(10);
            a.getFoodData().setFoodLevel(18);
            a.getFoodData().setSaturation(0);
            ((FoodDataAccessor) a.getFoodData()).sharedVitals$setTimer(79);
            a.getFoodData().tick(a);
            b.getFoodData().tick(b);
            helper.assertValueEqual(a.getHealth(), 11F, "Slow natural regeneration heals the pool once");
            helper.assertValueEqual(b.getHealth(), 11F, "Natural regeneration reaches every participant");
        }
        try (var f = new Fixture(helper)) {
            var a = f.player();
            var b = f.player();
            f.server.setDifficulty(Difficulty.PEACEFUL, true);
            a.setHealth(10);
            // ServerLevel normally increments tickCount before doTick; this test invokes it directly.
            a.tickCount = 20;
            b.tickCount = 20;
            a.doTick();
            b.doTick();
            helper.assertValueEqual(a.getHealth(), 11F, "Peaceful healing does not multiply with player count");
            helper.assertValueEqual(b.getHealth(), 11F, "Peaceful healing is shared");
        }
        helper.succeed();
    }

    @GameTest
    public void lateJoinAndReconnectKeepPool(GameTestHelper helper) {
        try (var f = new Fixture(helper)) {
            var a = f.player();
            a.setHealth(7);
            a.getFoodData().setFoodLevel(6);
            var b = f.player();
            helper.assertValueEqual(b.getHealth(), 7F, "Late join cannot refill health");
            helper.assertValueEqual(b.getFoodData().getFoodLevel(), 6, "Late join cannot refill hunger");
            f.server.getPlayerList().remove(a);
            f.server.getPlayerList().remove(b);
            var c = f.player();
            helper.assertValueEqual(c.getHealth(), 7F, "An empty server retains the pool");
            helper.assertValueEqual(c.getFoodData().getFoodLevel(), 6, "An empty server retains hunger");
        }
        helper.succeed();
    }

    @GameTest
    public void creativeAndSpectatorAreIsolated(GameTestHelper helper) {
        try (var f = new Fixture(helper)) {
            var a = f.player();
            var b = f.player();
            b.setGameMode(GameType.CREATIVE);
            b.getFoodData().setFoodLevel(1);
            b.setHealth(3);
            helper.assertValueEqual(a.getHealth(), 20F, "Creative health changes do not propagate");
            helper.assertValueEqual(a.getFoodData().getFoodLevel(), 20, "Creative hunger is detached immediately");
            a.setHealth(8);
            b.setGameMode(GameType.SURVIVAL);
            helper.assertValueEqual(b.getHealth(), 8F, "Returning to survival adopts the pool");
            b.setGameMode(GameType.SPECTATOR);
            a.hurtServer(a.level(), a.damageSources().genericKill(), Float.MAX_VALUE);
            helper.assertTrue(b.isAlive(), "Spectators survive group death");
        }
        helper.succeed();
    }

    @GameTest
    public void adventurePlayersParticipate(GameTestHelper helper) {
        try (var f = new Fixture(helper)) {
            var a = f.player();
            var b = f.player();
            b.setGameMode(GameType.ADVENTURE);
            a.setHealth(9);
            helper.assertValueEqual(b.getHealth(), 9F, "Adventure shares survival health");
            helper.assertTrue(a.getFoodData() == b.getFoodData(), "Adventure shares the hunger pool");
        }
        helper.succeed();
    }

    @GameTest
    public void totemPreventsSharedDeath(GameTestHelper helper) {
        try (var f = new Fixture(helper)) {
            var a = f.player();
            var b = f.player();
            a.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.TOTEM_OF_UNDYING));
            a.hurtServer(a.level(), a.damageSources().generic(), 100);
            helper.assertTrue(a.isAlive() && b.isAlive(), "Vanilla totem resolves before shared death");
            helper.assertValueEqual(a.getHealth(), 1F, "Totem leaves one shared health point");
            helper.assertValueEqual(b.getHealth(), 1F, "Totem recovery reaches the group");
            helper.assertTrue(a.getOffhandItem().isEmpty(), "The victim's totem is consumed");
        }
        helper.succeed();
    }

    @GameTest
    public void sharedDeathAndRespawns(GameTestHelper helper) {
        try (var f = new Fixture(helper)) {
            var a = f.player();
            var b = f.player();
            b.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.TOTEM_OF_UNDYING));
            a.getFoodData().setFoodLevel(2);
            a.hurtServer(a.level(), a.damageSources().genericKill(), Float.MAX_VALUE);
            helper.assertFalse(a.isAlive() || b.isAlive(), "One actual death kills the group");
            var c = f.player();
            helper.assertFalse(c.isAlive(), "Joining a dead pool cannot bypass death");
            var nextA = f.respawn(a);
            helper.assertValueEqual(nextA.getHealth(), 20F, "First respawn opens a full health pool");
            helper.assertValueEqual(nextA.getFoodData().getFoodLevel(), 20, "First respawn resets food");
            helper.assertValueEqual(nextA.getFoodData().getSaturationLevel(), 5F, "Respawn restores vanilla saturation");
            nextA.setHealth(12);
            nextA.getFoodData().setFoodLevel(15);
            var nextB = f.respawn(b);
            helper.assertValueEqual(nextB.getHealth(), 12F, "Later respawns do not heal the new life");
            helper.assertValueEqual(nextB.getFoodData().getFoodLevel(), 15, "Later respawns do not refill food");
        }
        helper.succeed();
    }

    @GameTest
    public void differentDimensionsShareVitals(GameTestHelper helper) {
        try (var f = new Fixture(helper)) {
            var a = f.player();
            var b = f.player();
            var other = f.server.getLevel(net.minecraft.world.level.Level.NETHER);
            b.teleportTo(other, 0, 90, 0, java.util.Set.of(), 0, 0, false);
            a.setHealth(5);
            helper.assertValueEqual(b.getHealth(), 5F, "Dimension changes retain shared health");
            helper.assertTrue(a.getFoodData() == b.getFoodData(), "Dimensions share the same food pool");
            a.hurtServer(a.level(), a.damageSources().genericKill(), Float.MAX_VALUE);
            helper.assertFalse(b.isAlive(), "Shared death crosses dimensions");
        }
        helper.succeed();
    }

    @GameTest
    public void maximumHealthChangesCannotDesynchronize(GameTestHelper helper) {
        try (var f = new Fixture(helper)) {
            var a = f.player();
            var b = f.player();
            b.getAttribute(Attributes.MAX_HEALTH).setBaseValue(10);
            f.state.tick(f.server);
            helper.assertValueEqual(a.getHealth(), 10F, "Pool fits the smallest maximum health");
            helper.assertValueEqual(b.getHealth(), 10F, "Lower maximum does not desynchronize health");
            a.heal(20);
            helper.assertValueEqual(a.getHealth(), 10F, "Healing cannot exceed the common maximum");
        }
        helper.succeed();
    }

    @GameTest
    public void worldDataSurvivesDiskReload(GameTestHelper helper) throws Exception {
        try (var f = new Fixture(helper)) {
            var a = f.player();
            a.setHealth(7);
            a.getFoodData().setFoodLevel(9);
            a.getFoodData().setSaturation(3);
            a.causeFoodExhaustion(2);
            ((FoodDataAccessor) a.getFoodData()).sharedVitals$setTimer(42);
            var snapshot = FoodSnapshot.capture(a.getFoodData());
            var directory = Files.createTempDirectory("shared-vitals-save-test");
            try {
                try (var storage = new SavedDataStorage(directory, DataFixers.getDataFixer(), f.server.registryAccess())) {
                    storage.set(SharedVitalsState.TYPE, f.state);
                    storage.saveAndJoin();
                }
                SharedVitalsState loaded;
                try (var storage = new SavedDataStorage(directory, DataFixers.getDataFixer(), f.server.registryAccess())) {
                    loaded = storage.get(SharedVitalsState.TYPE);
                }
                helper.assertTrue(loaded != null, "World data is read back from disk");
                f.state.leave(a);
                f.server.getDataStorage().set(SharedVitalsState.TYPE, loaded);
                var b = f.player();
                helper.assertValueEqual(b.getHealth(), 7F, "Saved health survives a new storage instance");
                helper.assertValueEqual(FoodSnapshot.capture(b.getFoodData()), snapshot,
                        "Food, saturation, exhaustion and timer survive a disk reload");
            } finally {
                try (var paths = Files.walk(directory)) {
                    for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
                }
            }
        }
        helper.succeed();
    }

    @GameTest
    public void linkedModeSharesVitalsButReportsOnlyOriginalDeath(GameTestHelper helper) {
        try (var f = new Fixture(helper)) {
            f.linkedMode(true);
            var a = f.player();
            var b = f.player();
            a.setHealth(6);
            helper.assertValueEqual(b.getHealth(), 6F, "Linked mode still shares health during play");
            b.getFoodData().setFoodLevel(8);
            helper.assertValueEqual(a.getFoodData().getFoodLevel(), 8, "Linked mode still shares hunger");
            a.hurtServer(a.level(), a.damageSources().genericKill(), Float.MAX_VALUE);
            helper.assertValueEqual(DEATHS.get(a.getUUID()), 1, "The real death remains visible to Linked Hardcore");
            helper.assertValueEqual(DEATHS.get(b.getUUID()), 0, "No synthetic death is sent for a teammate");
            helper.assertTrue(b.isAlive() && b.isSpectator(), "Survivors wait safely in spectator");
            helper.assertTrue(f.state.waitingForTransfer(), "The ended source run is latched");
            if (SharedVitalsSettings.linkedHardcoreInstalled()) {
                helper.assertValueEqual(f.deathFrames(), 1, "Actual Linked Hardcore sends exactly one PLAYER_DIED frame");
            }
        }
        helper.succeed();
    }

    @GameTest
    public void linkedModeDoesNotTreatTotemAsDeath(GameTestHelper helper) {
        try (var f = new Fixture(helper)) {
            f.linkedMode(true);
            var a = f.player();
            var b = f.player();
            a.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.TOTEM_OF_UNDYING));
            a.hurtServer(a.level(), a.damageSources().generic(), 100);
            helper.assertFalse(f.state.waitingForTransfer(), "A totem does not end the linked run");
            helper.assertFalse(b.isSpectator(), "Teammates keep playing after a totem save");
            helper.assertValueEqual(b.getHealth(), 1F, "Totem health still propagates");
            helper.assertValueEqual(DEATHS.get(a.getUUID()), 0, "No death callback occurs for a totem save");
        }
        helper.succeed();
    }

    @GameTest
    public void linkedRespawnsAndLateArrivalsStaySpectator(GameTestHelper helper) {
        try (var f = new Fixture(helper)) {
            f.linkedMode(true);
            var a = f.player();
            a.hurtServer(a.level(), a.damageSources().genericKill(), Float.MAX_VALUE);
            var replacement = f.respawn(a);
            helper.assertTrue(replacement.isAlive() && replacement.isSpectator(), "Proxy respawn does not reopen the source world");
            var late = f.player();
            helper.assertTrue(late.isAlive() && late.isSpectator(), "Late source arrivals wait without another death");
            late.setGameMode(GameType.SURVIVAL);
            helper.assertTrue(late.isSpectator(), "Linked Hardcore's survival-on-join cannot reopen an ended source");
            f.state.tick(f.server);
            helper.assertTrue(f.state.waitingForTransfer(), "Tick and respawn leave the pending transfer intact");
            helper.assertValueEqual(DEATHS.get(late.getUUID()), 0, "Late arrivals do not inflate death totals");
        }
        helper.succeed();
    }

    @GameTest
    public void linkedEndedWorldPersistsButFreshWorldStartsFull(GameTestHelper helper) {
        try (var f = new Fixture(helper)) {
            f.linkedMode(true);
            var a = f.player();
            a.hurtServer(a.level(), a.damageSources().genericKill(), Float.MAX_VALUE);
            var encoded = SharedVitalsState.CODEC.encodeStart(JsonOps.INSTANCE, f.state).getOrThrow();
            var restored = SharedVitalsState.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();
            f.server.getDataStorage().set(SharedVitalsState.TYPE, restored);
            var late = f.player();
            helper.assertTrue(restored.waitingForTransfer() && late.isSpectator(), "An ordinary restart preserves the ended run");
            helper.assertValueEqual(DEATHS.get(late.getUUID()), 0, "Restart recovery creates no synthetic deaths");
            f.server.getPlayerList().remove(a);
            f.server.getPlayerList().remove(late);
            // The executor deletes world saved data, while preserving backend configuration.
            f.server.getDataStorage().set(SharedVitalsState.TYPE, new SharedVitalsState());
            var fresh = f.player();
            helper.assertTrue(f.settings.linkedHardcore(), "Integration survives the fresh-world boundary");
            helper.assertFalse(SharedVitalsState.get(f.server).waitingForTransfer(), "A fresh world opens the next run");
            helper.assertValueEqual(fresh.getHealth(), 20F, "Fresh world starts at full health");
            helper.assertValueEqual(fresh.getFoodData().getFoodLevel(), 20, "Fresh world starts at full food");
        }
        helper.succeed();
    }

    @GameTest
    public void linkedCommandRequiresOperatorAndInstalledMod(GameTestHelper helper) throws Exception {
        try (var f = new Fixture(helper)) {
            var a = f.player();
            f.expectCommandFailure(a, "sharedvitals linkedhardcore true", false);
            helper.assertFalse(f.settings.linkedHardcore(), "Non-operators cannot change the setting");
            helper.assertValueEqual(f.command(a, "sharedvitals status", true), 1, "Operators can inspect status");
            if (SharedVitalsSettings.linkedHardcoreInstalled()) {
                helper.assertValueEqual(f.command(a, "sharedvitals linkedhardcore true", true), 1, "Operator enables integration explicitly");
                helper.assertTrue(f.settings.linkedHardcore(), "Command enables Linked Hardcore mode");
                a.hurtServer(a.level(), a.damageSources().genericKill(), Float.MAX_VALUE);
                f.expectCommandFailure(a, "sharedvitals linkedhardcore false", true);
                helper.assertTrue(f.settings.linkedHardcore(), "Disabling mid-transfer cannot reopen the ended world");
                helper.assertValueEqual(f.command(a, "sharedvitals linkedhardcore true", true), 1, "Repeated enable is harmless");
            } else {
                f.expectCommandFailure(a, "sharedvitals linkedhardcore true", true);
                helper.assertFalse(f.settings.linkedHardcore(), "Enabling without the companion mod fails safely");
                helper.assertValueEqual(f.command(a, "sharedvitals linkedhardcore false", true), 1, "Standalone mode remains available");
            }
        }
        helper.succeed();
    }

    @GameTest
    public void linkedConfigPersistsAndRejectsFailedWrites(GameTestHelper helper) throws Exception {
        var directory = Files.createTempDirectory("shared-vitals-config-test");
        var file = directory.resolve("shared-vitals.json");
        try {
            var config = new SharedVitalsSettings(file);
            helper.assertFalse(config.linkedHardcore(), "Integration is opt-in by default");
            config.setLinkedHardcore(true);
            helper.assertTrue(new SharedVitalsSettings(file).linkedHardcore(), "Configuration survives a reload");
            config.setLinkedHardcore(false);
            Files.delete(file);
            Files.createDirectory(file);
            try {
                config.setLinkedHardcore(true);
                throw helper.assertionException("A failed write unexpectedly succeeded");
            } catch (IOException expected) {
                helper.assertFalse(config.linkedHardcore(), "A failed write does not activate an unsaved setting");
            }
        } finally {
            Files.deleteIfExists(file);
            try (var paths = Files.list(directory)) {
                helper.assertValueEqual(paths.count(), 0L, "Temporary config files are cleaned up after failure");
            }
            Files.delete(directory);
        }
        helper.succeed();
    }

    @GameTest
    public void enabledConfigCannotSilentlyFallBackWithoutCompanion(GameTestHelper helper) {
        try (var f = new Fixture(helper)) {
            f.linkedMode(true);
            if (SharedVitalsSettings.linkedHardcoreInstalled()) {
                SharedVitalsSettings.start(f.server);
                helper.assertTrue(f.settings.linkedHardcore(), "Installed companion permits enabled startup");
            } else {
                try {
                    SharedVitalsSettings.start(f.server);
                    throw helper.assertionException("Missing companion silently fell back to standalone behavior");
                } catch (IllegalStateException expected) {
                    helper.assertTrue(expected.getMessage().contains("linkedhardcore"), "Startup explains the missing companion");
                }
            }
        }
        helper.succeed();
    }

    @GameTest
    public void malformedConfigIsRejected(GameTestHelper helper) throws Exception {
        var file = Files.createTempFile("shared-vitals-invalid-", ".json");
        try {
            for (var content : List.of("{", "null", "{}", "{\"linkedHardcore\":\"true\"}")) {
                Files.writeString(file, content);
                try {
                    new SharedVitalsSettings(file);
                    throw helper.assertionException("Malformed config was accepted: " + content);
                } catch (IOException expected) {
                    // Configuration mistakes must not silently change the death policy.
                }
            }
        } finally {
            Files.delete(file);
        }
        helper.succeed();
    }

    @GameTest
    public void existingWorldDataRemainsReadable(GameTestHelper helper) {
        try (var f = new Fixture(helper)) {
            var a = f.player();
            a.setHealth(13);
            a.getFoodData().setFoodLevel(4);
            var original = SharedVitalsState.CODEC.encodeStart(JsonOps.INSTANCE, f.state).getOrThrow().getAsJsonObject();
            original.remove("waitingForTransfer");
            var loaded = SharedVitalsState.CODEC.parse(JsonOps.INSTANCE, original).getOrThrow();
            f.state.leave(a);
            f.server.getDataStorage().set(SharedVitalsState.TYPE, loaded);
            var b = f.player();
            helper.assertFalse(loaded.waitingForTransfer(), "Old world data defaults to an active run");
            helper.assertValueEqual(b.getHealth(), 13F, "Old shared health is preserved");
            helper.assertValueEqual(b.getFoodData().getFoodLevel(), 4, "Old shared hunger is preserved");
        }
        helper.succeed();
    }

    @GameTest
    public void actualLinkedHardcoreWaitAndCountdownAreCompatible(GameTestHelper helper) throws Exception {
        if (!SharedVitalsSettings.linkedHardcoreInstalled()) {
            helper.succeed(); // Enable the optional external-JAR run for this compatibility case.
            return;
        }
        try (var f = new Fixture(helper)) {
            f.linkedMode(true);
            var a = f.player();
            var b = f.player();
            Class<?> configType = Class.forName("dev.linkedhardcore.fabric.config.ModConfig");
            Class<?> notifierType = Class.forName("dev.linkedhardcore.fabric.death.TransferReadyNotifier");
            Class<?> countdownType = Class.forName("dev.linkedhardcore.fabric.death.TransferCountdown");
            Object config = configType.getConstructor(String.class, int.class, int.class).newInstance("test", 10, 0);
            int[] ready = {0};
            Object notifier = Proxy.newProxyInstance(notifierType.getClassLoader(), new Class<?>[]{notifierType},
                    (proxy, method, args) -> { ready[0]++; return null; });
            Object countdown = countdownType.getConstructor(configType, notifierType).newInstance(config, notifier);
            countdownType.getMethod("waitForServer", MinecraftServer.class).invoke(countdown, f.server);
            helper.assertTrue(f.state.waitingForTransfer(), "Actual WAIT_FOR_SERVER latches sources without a local death");
            helper.assertTrue(a.isSpectator() && b.isSpectator(), "Actual wait flow leaves everyone in spectator");
            countdownType.getMethod("start", MinecraftServer.class).invoke(countdown, f.server);
            countdownType.getMethod("tick", MinecraftServer.class).invoke(countdown, f.server);
            helper.assertValueEqual(ready[0], 1, "Actual countdown still signals TRANSFER_READY");
            helper.assertValueEqual(DEATHS.get(a.getUUID()) + DEATHS.get(b.getUUID()), 0, "Remote deaths create no local deaths");
        }
        try (var f = new Fixture(helper)) {
            f.linkedMode(true);
            var a = f.player();
            Class<?> configType = Class.forName("dev.linkedhardcore.fabric.config.ModConfig");
            Class<?> notifierType = Class.forName("dev.linkedhardcore.fabric.death.TransferReadyNotifier");
            Class<?> countdownType = Class.forName("dev.linkedhardcore.fabric.death.TransferCountdown");
            Object config = configType.getConstructor(String.class, int.class, int.class).newInstance("test", 10, 0);
            Object notifier = Proxy.newProxyInstance(notifierType.getClassLoader(), new Class<?>[]{notifierType},
                    (proxy, method, args) -> null);
            Object countdown = countdownType.getConstructor(configType, notifierType).newInstance(config, notifier);
            countdownType.getMethod("start", MinecraftServer.class).invoke(countdown, f.server);
            helper.assertTrue(f.state.waitingForTransfer() && a.isSpectator(), "Direct PREPARE_TRANSFER also latches the source");
        }
        helper.succeed();
    }

    @GameTest
    public void actualLinkedWaitRespawnsVictimWithoutReopeningRun(GameTestHelper helper) throws Exception {
        if (!SharedVitalsSettings.linkedHardcoreInstalled()) {
            helper.succeed();
            return;
        }
        try (var f = new Fixture(helper)) {
            f.linkedMode(true);
            var a = f.player();
            var b = f.player();
            a.hurtServer(a.level(), a.damageSources().genericKill(), Float.MAX_VALUE);
            Class<?> configType = Class.forName("dev.linkedhardcore.fabric.config.ModConfig");
            Class<?> notifierType = Class.forName("dev.linkedhardcore.fabric.death.TransferReadyNotifier");
            Class<?> countdownType = Class.forName("dev.linkedhardcore.fabric.death.TransferCountdown");
            Object config = configType.getConstructor(String.class, int.class, int.class).newInstance("test", 10, 0);
            Object notifier = Proxy.newProxyInstance(notifierType.getClassLoader(), new Class<?>[]{notifierType},
                    (proxy, method, args) -> null);
            Object countdown = countdownType.getConstructor(configType, notifierType).newInstance(config, notifier);
            countdownType.getMethod("waitForServer", MinecraftServer.class).invoke(countdown, f.server);
            var replacement = a.connection.player;
            helper.assertTrue(replacement != a && replacement.isAlive(), "Linked Hardcore uses vanilla respawn for the dead victim");
            helper.assertTrue(replacement.isSpectator() && b.isSpectator(), "Victim and survivors remain spectators");
            helper.assertTrue(f.state.waitingForTransfer(), "Vanilla respawn leaves the source run ended");
            helper.assertValueEqual(f.deathFrames(), 1, "Wait/respawn produces no second death report");
        }
        helper.succeed();
    }

    private static final class Fixture implements AutoCloseable {
        final GameTestHelper helper;
        final MinecraftServer server;
        final SharedVitalsState state;
        final Difficulty originalDifficulty;
        final SharedVitalsSettings settings;
        final boolean originalLinkedMode;
        final List<ServerPlayer> players = new ArrayList<>();
        final List<EmbeddedChannel> channels = new ArrayList<>();

        Fixture(GameTestHelper helper) {
            this.helper = helper;
            server = helper.getLevel().getServer();
            settings = SharedVitalsSettings.get(server);
            originalLinkedMode = settings.linkedHardcore();
            linkedMode(false);
            state = new SharedVitalsState();
            server.getDataStorage().set(SharedVitalsState.TYPE, state);
            originalDifficulty = server.getWorldData().getDifficulty();
            server.setDifficulty(Difficulty.NORMAL, true);
        }

        ServerPlayer player() {
            // Vanilla's deprecated helper hardcodes creative mode, even after setGameMode.
            // Use actual ServerPlayer instances and normal login/respawn events instead.
            var profile = new GameProfile(UUID.randomUUID(), "vitals" + players.size());
            var cookie = CommonListenerCookie.createInitial(profile, false);
            var player = new ServerPlayer(server, helper.getLevel(), profile, ClientInformation.createDefault());
            var connection = new Connection(PacketFlow.SERVERBOUND);
            channels.add(new EmbeddedChannel(connection));
            server.getPlayerList().placeNewPlayer(connection, player, cookie);
            players.add(player);
            DEATHS.putIfAbsent(player.getUUID(), 0);
            player.setGameMode(GameType.SURVIVAL);
            player.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
            return player;
        }

        ServerPlayer respawn(ServerPlayer oldPlayer) {
            var player = server.getPlayerList().respawn(oldPlayer, false, Entity.RemovalReason.KILLED);
            players.add(player);
            return player;
        }

        void linkedMode(boolean enabled) {
            try {
                settings.setLinkedHardcore(enabled);
            } catch (IOException error) {
                throw new IllegalStateException(error);
            }
        }

        int command(ServerPlayer player, String command, boolean operator) throws CommandSyntaxException {
            var source = player.createCommandSourceStack().withPermission(
                    operator ? LevelBasedPermissionSet.GAMEMASTER : PermissionSet.NO_PERMISSIONS);
            return server.getCommands().getDispatcher().execute(command, source);
        }

        void expectCommandFailure(ServerPlayer player, String command, boolean operator) throws CommandSyntaxException {
            try {
                command(player, command, operator);
                throw helper.assertionException("Command unexpectedly succeeded: " + command);
            } catch (CommandSyntaxException expected) {
                // Required permission, missing companion mod, or ended-run guard rejected it.
            }
        }

        int deathFrames() {
            int count = 0;
            for (var channel : channels) {
                Object packet;
                while ((packet = channel.readOutbound()) != null) {
                    if (packet instanceof ClientboundCustomPayloadPacket custom
                            && custom.payload().type().id().toString().equals("linkedhardcore:main")) {
                        try {
                            byte[] frame = (byte[]) custom.payload().getClass().getMethod("data").invoke(custom.payload());
                            if (frame.length > 0 && frame[0] == 1) count++;
                        } catch (ReflectiveOperationException error) {
                            throw new IllegalStateException(error);
                        }
                    }
                }
            }
            return count;
        }

        @Override
        public void close() {
            for (var player : players) {
                var current = server.getPlayerList().getPlayer(player.getUUID());
                if (current != null) {
                    server.getPlayerList().remove(current);
                    current.discard();
                }
                player.discard();
                DEATHS.remove(player.getUUID());
            }
            server.getDataStorage().set(SharedVitalsState.TYPE, new SharedVitalsState());
            server.setDifficulty(originalDifficulty, true);
            linkedMode(originalLinkedMode);
            for (var channel : channels) channel.finishAndReleaseAll();
        }
    }
}
