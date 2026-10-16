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
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundHurtAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateMobEffectPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveMobEffectPacket;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
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
            helper.assertValueEqual(b.invulnerableTime, 0, "Shared feedback does not grant damage immunity");
            b.hurtServer(b.level(), b.damageSources().generic(), 5);
            helper.assertValueEqual(a.getHealth(), 11F, "Hits on different players in one tick accumulate");
            helper.assertValueEqual(b.getHealth(), 11F, "No stale health overwrites a hit");
        }
        helper.succeed();
    }

    @GameTest
    public void sharedHitSendsHurtFeedbackWithoutAnotherHit(GameTestHelper helper) {
        try (var f = new Fixture(helper)) {
            var a = f.player();
            var b = f.player();
            var observer = f.player();
            observer.setGameMode(GameType.CREATIVE);
            b.setPos(a.position());
            observer.setPos(a.position());
            var armor = new ItemStack(Items.DIAMOND_CHESTPLATE);
            b.setItemSlot(EquipmentSlot.CHEST, armor);
            var movement = b.getDeltaMovement();
            f.takePackets(a);
            f.takePackets(b);
            f.takePackets(observer);
            a.hurtServer(a.level(), a.damageSources().generic(), 6);
            var packets = f.takePackets(b);
            helper.assertValueEqual(packets.stream().filter(packet -> packet instanceof ClientboundDamageEventPacket event
                    && event.entityId() == b.getId()).count(), 1L, "Teammate receives one vanilla damage feedback event");
            helper.assertValueEqual(packets.stream().filter(packet -> packet instanceof ClientboundHurtAnimationPacket event
                    && event.id() == b.getId() && event.yaw() == a.getHurtDir()).count(), 1L,
                    "Teammate receives one camera flinch with the victim's hurt direction");
            var victimPackets = f.takePackets(a);
            helper.assertValueEqual(victimPackets.stream().filter(packet -> packet instanceof ClientboundDamageEventPacket event
                    && event.entityId() == a.getId()).count(), 1L, "The original victim's feedback is not duplicated");
            helper.assertValueEqual(victimPackets.stream().filter(packet -> packet instanceof ClientboundSoundPacket sound
                    && sound.getSound().value() == SoundEvents.PLAYER_HURT).count(), 1L,
                    "Nearby players hear the teammate's hurt sound through the server sound path");
            helper.assertValueEqual(f.takePackets(observer).stream().filter(packet -> packet instanceof ClientboundSoundPacket sound
                    && sound.getSound().value() == SoundEvents.PLAYER_HURT).count(), 2L,
                    "An observer hears the actual victim and the teammate once each");
            helper.assertValueEqual(b.getHealth(), 14F, "Shared health damage is applied exactly once");
            helper.assertValueEqual(b.getAbsorptionAmount(), 0F, "Feedback does not generate absorption");
            helper.assertValueEqual(armor.getDamageValue(), 0, "Feedback does not damage teammate armor");
            helper.assertValueEqual(b.invulnerableTime, 0, "Feedback does not suppress a later real hit");
            helper.assertValueEqual(b.hurtTime, 10, "Server also records the hurt animation");
            helper.assertValueEqual(b.getDeltaMovement(), movement, "Visual feedback does not move teammates");
        }
        helper.succeed();
    }

    @GameTest
    public void healthSynchronizationDoesNotSendHurtFeedback(GameTestHelper helper) {
        try (var f = new Fixture(helper)) {
            var a = f.player();
            a.setHealth(12);
            var b = f.player();
            a.heal(2);
            b.getAttribute(Attributes.MAX_HEALTH).setBaseValue(10);
            f.state.tick(f.server);
            b.setGameMode(GameType.SPECTATOR);
            b.setGameMode(GameType.SURVIVAL);
            for (var player : List.of(a, b)) {
                helper.assertFalse(f.takePackets(player).stream().anyMatch(packet ->
                                packet instanceof ClientboundDamageEventPacket || packet instanceof ClientboundHurtAnimationPacket),
                        "Joins, setters, healing, health caps, and mode changes do not imitate a hit");
            }
        }
        helper.succeed();
    }

    @GameTest
    public void absorbedHitsSendFeedbackButRejectedHitsDoNot(GameTestHelper helper) {
        try (var f = new Fixture(helper)) {
            var a = f.player();
            var b = f.player();
            a.getAttribute(Attributes.MAX_ABSORPTION).setBaseValue(20);
            a.setAbsorptionAmount(4);
            f.takePackets(b);
            a.hurtServer(a.level(), a.damageSources().generic(), 4);
            helper.assertValueEqual(f.takePackets(b).stream().filter(packet -> packet instanceof ClientboundDamageEventPacket event
                    && event.entityId() == b.getId()).count(), 1L, "A hit on shared golden hearts also sends hurt feedback");
            helper.assertValueEqual(b.getAbsorptionAmount(), 0F, "The golden hearts are consumed for everyone");
            helper.assertFalse(a.hurtServer(a.level(), a.damageSources().generic(), 4), "Cooldown rejects the next hit");
            a.invulnerableTime = 0;
            helper.assertFalse(a.hurtServer(a.level(), a.damageSources().generic(), 0), "Zero damage is rejected");
            helper.assertValueEqual(b.getHealth(), 20F, "These hits do not lower the shared pool");
            helper.assertFalse(f.takePackets(b).stream().anyMatch(packet ->
                            packet instanceof ClientboundDamageEventPacket event && event.entityId() == b.getId()
                            || packet instanceof ClientboundHurtAnimationPacket animation && animation.id() == b.getId()),
                    "Rejected hits send no additional shared hurt feedback");
        }
        helper.succeed();
    }

    @GameTest
    public void hurtFeedbackCrossesDimensionsAndExcludesNonparticipants(GameTestHelper helper) {
        try (var f = new Fixture(helper)) {
            var a = f.player();
            var b = f.player();
            var creative = f.player();
            var spectator = f.player();
            creative.setGameMode(GameType.CREATIVE);
            spectator.setGameMode(GameType.SPECTATOR);
            b.teleportTo(f.server.getLevel(net.minecraft.world.level.Level.NETHER), 0, 90, 0, java.util.Set.of(), 0, 0, false);
            for (var player : List.of(a, b, creative, spectator)) f.takePackets(player);
            a.hurtServer(a.level(), a.damageSources().playerAttack(creative), 3);
            var packets = f.takePackets(b);
            var feedback = packets.stream().filter(packet -> packet instanceof ClientboundDamageEventPacket event
                            && event.entityId() == b.getId()).map(ClientboundDamageEventPacket.class::cast).toList();
            helper.assertValueEqual(feedback.size(), 1, "The teammate in another dimension receives hurt feedback");
            helper.assertValueEqual(feedback.getFirst().sourceType(), a.damageSources().playerAttack(creative).typeHolder(),
                    "Damage type is preserved for the vanilla hurt sound");
            helper.assertValueEqual(feedback.getFirst().sourceCauseId(), -1, "Remote attacker IDs are omitted");
            helper.assertValueEqual(feedback.getFirst().sourceDirectId(), -1, "Remote direct entity IDs are omitted");
            helper.assertTrue(feedback.getFirst().sourcePosition().isEmpty(), "Remote source coordinates are omitted");
            helper.assertTrue(packets.stream().anyMatch(packet -> packet instanceof ClientboundHurtAnimationPacket event
                    && event.id() == b.getId()), "Camera feedback also crosses dimensions");
            helper.assertValueEqual(b.getHealth(), 17F, "Cross-dimension damage still applies once");
            for (var player : List.of(creative, spectator)) {
                helper.assertValueEqual(player.getHealth(), 20F, "Excluded players keep their health");
                helper.assertValueEqual(player.hurtTime, 0, "Excluded players do not animate a shared hit");
                helper.assertFalse(f.takePackets(player).stream().anyMatch(packet ->
                                packet instanceof ClientboundDamageEventPacket event && event.entityId() == player.getId()
                                || packet instanceof ClientboundHurtAnimationPacket animation && animation.id() == player.getId()),
                        "Excluded players receive no hurt feedback for themselves");
            }
        }
        helper.succeed();
    }

    @GameTest
    public void victimArmorProtectsTheSharedPools(GameTestHelper helper) {
        try (var f = new Fixture(helper)) {
            var a = f.player();
            var b = f.player();
            a.getAttribute(Attributes.MAX_ABSORPTION).setBaseValue(20);
            a.setAbsorptionAmount(4);
            a.hurtServer(a.level(), a.damageSources().generic(), 6);
            helper.assertValueEqual(a.getHealth(), 18F, "Shared absorption intercepts four damage");
            helper.assertValueEqual(b.getHealth(), 18F, "Remaining health damage is shared");
            helper.assertValueEqual(b.getAbsorptionAmount(), 0F, "Absorption is consumed once for the group");
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
    public void goldenAppleSharesAbsorptionAndVisibleEffects(GameTestHelper helper) {
        try (var f = new Fixture(helper)) {
            var a = f.player();
            var b = f.player();
            new ItemStack(Items.GOLDEN_APPLE).finishUsingItem(a.level(), a);
            helper.assertValueEqual(a.getAbsorptionAmount(), 4F, "A golden apple supplies two golden hearts");
            helper.assertValueEqual(b.getAbsorptionAmount(), 4F, "The same golden hearts reach the teammate");
            helper.assertValueEqual(a.getEffect(MobEffects.ABSORPTION).getDuration(),
                    b.getEffect(MobEffects.ABSORPTION).getDuration(), "The absorption lifetime is shared");
            helper.assertTrue(a.hasEffect(MobEffects.REGENERATION), "The eater receives vanilla regeneration");
            helper.assertTrue(b.hasEffect(MobEffects.REGENERATION), "Regeneration is visible on the teammate too");
            a.addEffect(new MobEffectInstance(MobEffects.POISON, 100));
            b.addEffect(new MobEffectInstance(MobEffects.SPEED, 100));
            helper.assertTrue(b.hasEffect(MobEffects.POISON) && a.hasEffect(MobEffects.SPEED),
                    "Both harmful and beneficial effects are shared");
        }
        helper.succeed();
    }

    @GameTest
    public void hitsConsumeSharedAbsorptionOnceAcrossDimensions(GameTestHelper helper) {
        try (var f = new Fixture(helper)) {
            var a = f.player();
            var b = f.player();
            a.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 1200));
            b.teleportTo(f.server.getLevel(net.minecraft.world.level.Level.NETHER), 0, 90, 0, java.util.Set.of(), 0, 0, false);
            b.hasChangedDimension();
            b.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
            a.hurtServer(a.level(), a.damageSources().generic(), 2);
            helper.assertValueEqual(a.getHealth(), 20F, "Golden hearts protect shared normal health");
            helper.assertValueEqual(a.getAbsorptionAmount(), 2F, "The first hit consumes two absorption points");
            helper.assertValueEqual(b.getAbsorptionAmount(), 2F, "The remaining absorption reaches another dimension");
            helper.assertValueEqual(b.invulnerableTime, 0, "Shared feedback does not suppress the other player's hit");
            helper.assertTrue(b.hurtServer(b.level(), b.damageSources().generic(), 5), "The second player receives a real hit");
            helper.assertValueEqual(a.getAbsorptionAmount(), 0F, "The second hit exhausts the shared absorption");
            helper.assertValueEqual(b.getAbsorptionAmount(), 0F, "Both players lose the exhausted golden hearts");
            helper.assertValueEqual(a.getHealth(), 17F, "Only the three remaining damage points reach shared health");
            helper.assertValueEqual(b.getHealth(), 17F, "Damage is not applied again to teammates");
        }
        helper.succeed();
    }

    @GameTest
    public void absorptionRefreshAndHiddenDowngradeStayShared(GameTestHelper helper) {
        try (var f = new Fixture(helper)) {
            var a = f.player();
            var b = f.player();
            a.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 100));
            a.hurtServer(a.level(), a.damageSources().generic(), 2);
            b.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 2, 1));
            helper.assertValueEqual(a.getAbsorptionAmount(), 8F, "A stronger absorption grant refreshes the shared supply");
            helper.assertValueEqual(b.getAbsorptionAmount(), 8F, "Refreshing does not add a second copy of the supply");
            a.doTick();
            b.doTick();
            f.state.tick(f.server);
            a.doTick();
            b.doTick();
            for (var player : List.of(a, b)) {
                helper.assertTrue(player.hasEffect(MobEffects.ABSORPTION), "The hidden weaker absorption effect is retained");
                helper.assertValueEqual(player.getEffect(MobEffects.ABSORPTION).getAmplifier(), 0,
                        "The stronger effect downgrades for the whole group");
                helper.assertValueEqual(player.getAbsorptionAmount(), 4F, "Remaining golden hearts fit the weaker capacity");
            }
            a.removeEffect(MobEffects.ABSORPTION);
            a.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 2, 1));
            b.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 100));
            f.state.tick(f.server);
            a.doTick();
            b.doTick();
            f.state.tick(f.server);
            a.doTick();
            b.doTick();
            helper.assertTrue(a.hasEffect(MobEffects.ABSORPTION) && b.hasEffect(MobEffects.ABSORPTION),
                    "A weaker effect added behind a stronger one is shared as a hidden fallback");
            helper.assertValueEqual(a.getEffect(MobEffects.ABSORPTION).getAmplifier(), 0,
                    "The hidden fallback also works when the weaker effect was added second");
        }
        helper.succeed();
    }

    @GameTest
    public void milkAndExpiryClearSharedEffects(GameTestHelper helper) {
        try (var f = new Fixture(helper)) {
            var a = f.player();
            var b = f.player();
            a.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 100));
            a.addEffect(new MobEffectInstance(MobEffects.SPEED, 100));
            new ItemStack(Items.MILK_BUCKET).finishUsingItem(b.level(), b);
            for (var player : List.of(a, b)) {
                helper.assertValueEqual(player.getAbsorptionAmount(), 0F, "One player's milk clears the shared golden hearts");
                helper.assertFalse(player.hasEffect(MobEffects.ABSORPTION), "Milk removes the shared absorption effect");
            }
            helper.assertFalse(a.hasEffect(MobEffects.SPEED), "Milk clears the shared status effects too");
            a.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 2));
            f.server.getPlayerList().remove(a);
            b.doTick();
            f.state.tick(f.server);
            b.doTick();
            helper.assertValueEqual(b.getAbsorptionAmount(), 0F, "Absorption expires after its original recipient disconnects");
            helper.assertFalse(b.hasEffect(MobEffects.ABSORPTION), "The shared duration actually expires");
            var late = f.player();
            helper.assertValueEqual(late.getAbsorptionAmount(), 0F, "A late join cannot revive expired absorption");
        }
        helper.succeed();
    }

    @GameTest
    public void joinsAndModeChangesAdoptRemainingAbsorption(GameTestHelper helper) {
        try (var f = new Fixture(helper)) {
            var a = f.player();
            var b = f.player();
            a.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 100));
            a.hurtServer(a.level(), a.damageSources().generic(), 1);
            a.doTick();
            b.doTick();
            var duration = a.getEffect(MobEffects.ABSORPTION).getDuration();
            var late = f.player();
            helper.assertValueEqual(late.getAbsorptionAmount(), 3F, "Joining does not refill consumed golden hearts");
            helper.assertValueEqual(late.getEffect(MobEffects.ABSORPTION).getDuration(), duration,
                    "Joining preserves the remaining absorption duration");
            b.setGameMode(GameType.CREATIVE);
            b.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 200, 2));
            helper.assertValueEqual(a.getAbsorptionAmount(), 3F, "Creative absorption grants do not enter the pool");
            a.invulnerableTime = 0;
            a.hurtServer(a.level(), a.damageSources().generic(), 1);
            helper.assertValueEqual(b.getAbsorptionAmount(), 12F, "Creative players do not consume shared absorption");
            b.setGameMode(GameType.SURVIVAL);
            helper.assertValueEqual(b.getAbsorptionAmount(), 2F, "Returning to survival adopts the spent pool");
            helper.assertValueEqual(b.getEffect(MobEffects.ABSORPTION).getAmplifier(), 0,
                    "Returning also adopts the shared effect strength");
            b.setGameMode(GameType.SPECTATOR);
            a.removeEffect(MobEffects.ABSORPTION);
            helper.assertValueEqual(b.getAbsorptionAmount(), 2F, "Spectators are detached from absorption changes");
            helper.assertValueEqual(late.getAbsorptionAmount(), 0F, "Active players share effect removal");
            a.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 100));
            a.invulnerableTime = 0;
            a.hurtServer(a.level(), a.damageSources().generic(), 1);
            f.server.getPlayerList().remove(a);
            f.server.getPlayerList().remove(late);
            var returning = f.player();
            helper.assertValueEqual(returning.getAbsorptionAmount(), 3F, "An empty server retains the spent absorption pool");
        }
        helper.succeed();
    }

    @GameTest
    public void directAbsorptionGrantsPreservePersonalBaseAttributes(GameTestHelper helper) {
        try (var f = new Fixture(helper)) {
            var a = f.player();
            var b = f.player();
            a.getAttribute(Attributes.MAX_ABSORPTION).setBaseValue(20);
            a.setAbsorptionAmount(8);
            helper.assertValueEqual(b.getAbsorptionAmount(), 8F, "Absorption without a status effect is also shared");
            helper.assertValueEqual(b.getAttribute(Attributes.MAX_ABSORPTION).getBaseValue(), 0D,
                    "Sharing does not change the teammate's base attribute");
            helper.assertFalse(b.hasEffect(MobEffects.ABSORPTION), "A direct grant does not invent a timed status effect");
            a.setAbsorptionAmount(0);
            helper.assertValueEqual(b.getMaxAbsorption(), 0F, "Temporary shared capacity is removed when the pool empties");
        }
        helper.succeed();
    }

    @GameTest
    public void legacyWorldSeedsAbsorptionFromExistingPlayer(GameTestHelper helper) {
        try (var f = new Fixture(helper)) {
            var a = f.player();
            a.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 300));
            a.hurtServer(a.level(), a.damageSources().generic(), 1);
            var ops = RegistryOps.create(JsonOps.INSTANCE, f.server.registryAccess());
            var encoded = SharedVitalsState.CODEC.encodeStart(ops, f.state).getOrThrow().getAsJsonObject();
            for (var field : List.of("absorptionInitialized", "absorption", "absorptionEffect", "effects")) encoded.remove(field);
            var loaded = SharedVitalsState.CODEC.parse(ops, encoded).getOrThrow();
            f.state.leave(a);
            f.server.getDataStorage().set(SharedVitalsState.TYPE, loaded);
            loaded.join(a);
            var b = f.player();
            helper.assertValueEqual(a.getAbsorptionAmount(), 3F, "Upgrading preserves an existing player's spent absorption");
            helper.assertValueEqual(b.getAbsorptionAmount(), 3F, "The upgraded pool shares that remaining absorption");
            helper.assertValueEqual(b.getEffect(MobEffects.ABSORPTION).getDuration(), 300,
                    "Upgrading also preserves the absorption lifetime");
        }
        helper.succeed();
    }

    @GameTest
    public void effectIconsAndDurationsAreSharedWithoutMultiplyingTicks(GameTestHelper helper) {
        try (var f = new Fixture(helper)) {
            var a = f.player();
            var b = f.player();
            var c = f.player();
            a.setHealth(10);
            a.getFoodData().setFoodLevel(0);
            f.takePackets(b);
            c.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 50));
            helper.assertTrue(f.takePackets(b).stream().anyMatch(packet -> packet instanceof ClientboundUpdateMobEffectPacket effect
                            && effect.getEntityId() == b.getId() && effect.getEffect().equals(MobEffects.REGENERATION)
                            && effect.effectShowsIcon() && effect.getEffectDurationTicks() == 50),
                    "The teammate receives the native visible regeneration icon and duration");
            // Tick followers first to prove their displayed effect cannot apply healing.
            c.doTick();
            b.doTick();
            helper.assertValueEqual(a.getHealth(), 10F, "Followers do not apply extra regeneration");
            a.doTick();
            for (var player : List.of(a, b, c)) {
                helper.assertValueEqual(player.getHealth(), 11F, "Three players receive one regeneration heal");
                helper.assertValueEqual(player.getEffect(MobEffects.REGENERATION).getDuration(), 49,
                        "The common duration decreases once instead of once per player");
            }
            helper.assertTrue(a.getEffect(MobEffects.REGENERATION) == b.getEffect(MobEffects.REGENERATION),
                    "Players reference one shared effect instance");
            b.removeEffect(MobEffects.REGENERATION);
            helper.assertFalse(a.hasEffect(MobEffects.REGENERATION) || c.hasEffect(MobEffects.REGENERATION),
                    "Removing an effect clears it for the group");
            helper.assertTrue(f.takePackets(a).stream().anyMatch(packet -> packet instanceof ClientboundRemoveMobEffectPacket effect
                    && effect.entityId() == a.getId() && effect.effect().equals(MobEffects.REGENERATION)),
                    "The removal also clears the native effect icon");
        }
        helper.succeed();
    }

    @GameTest
    public void poisonWitherAndHungerRunOnceForTheGroup(GameTestHelper helper) {
        for (var effect : List.of(MobEffects.POISON, MobEffects.WITHER)) {
            try (var f = new Fixture(helper)) {
                var a = f.player();
                var b = f.player();
                var c = f.player();
                a.setHealth(10);
                a.getFoodData().setFoodLevel(0);
                a.addEffect(new MobEffectInstance(effect, effect.equals(MobEffects.POISON) ? 25 : 40));
                a.doTick();
                b.doTick();
                c.doTick();
                helper.assertValueEqual(a.getHealth(), 9F, "The harmful effect applies one point to the shared pool");
                helper.assertValueEqual(c.getHealth(), 9F, "A third participant does not triple damage");
            }
        }
        try (var f = new Fixture(helper)) {
            var a = f.player();
            var b = f.player();
            var c = f.player();
            a.addEffect(new MobEffectInstance(MobEffects.HUNGER, 100));
            a.doTick();
            b.doTick();
            c.doTick();
            helper.assertValueEqual(FoodSnapshot.capture(a.getFoodData()).exhaustion(), 0.005F,
                    "Hunger adds exhaustion once regardless of participant count");
        }
        helper.succeed();
    }

    @GameTest
    public void passiveEffectsApplyNormallyToEveryPlayer(GameTestHelper helper) {
        try (var f = new Fixture(helper)) {
            var a = f.player();
            var b = f.player();
            var baseSpeed = a.getAttributeValue(Attributes.MOVEMENT_SPEED);
            a.addEffect(new MobEffectInstance(MobEffects.SPEED, 200, 1));
            var speed = a.getAttributeValue(Attributes.MOVEMENT_SPEED);
            helper.assertTrue(Math.abs(speed / baseSpeed - 1.4D) < 0.00001D, "Speed II has its vanilla forty-percent bonus");
            helper.assertValueEqual(b.getAttributeValue(Attributes.MOVEMENT_SPEED), speed, "The teammate receives that bonus too");
            b.addEffect(new MobEffectInstance(MobEffects.SPEED, 200, 1));
            helper.assertValueEqual(a.getAttributeValue(Attributes.MOVEMENT_SPEED), speed, "Sharing/refreshing does not stack modifiers");
            a.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 200));
            helper.assertFalse(b.hurtServer(b.level(), b.damageSources().inFire(), 5), "Fire resistance protects the teammate");
            helper.assertValueEqual(a.getHealth(), 20F, "The resisted hit does not damage the shared pool");
        }
        helper.succeed();
    }

    @GameTest
    public void effectJoinsAndLeaderChangesPreserveOneClock(GameTestHelper helper) {
        try (var f = new Fixture(helper)) {
            var a = f.player();
            var b = f.player();
            a.setHealth(10);
            a.getFoodData().setFoodLevel(0);
            a.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 51));
            a.doTick();
            b.doTick();
            var late = f.player();
            helper.assertValueEqual(late.getEffect(MobEffects.REGENERATION).getDuration(), 50,
                    "A late join inherits the remaining duration without refreshing it");
            a.setGameMode(GameType.CREATIVE);
            var isolated = a.getEffect(MobEffects.REGENERATION);
            helper.assertTrue(isolated != b.getEffect(MobEffects.REGENERATION), "Excluded players detach from the shared clock");
            f.state.tick(f.server);
            b.doTick();
            late.doTick();
            helper.assertValueEqual(b.getHealth(), 11F, "A replacement leader applies the next heal once");
            helper.assertValueEqual(isolated.getDuration(), 50, "Group ticking does not advance an excluded player's effect");
            a.setGameMode(GameType.SURVIVAL);
            helper.assertValueEqual(a.getEffect(MobEffects.REGENERATION).getDuration(), 49, "Returning adopts the group's clock");
            f.server.getPlayerList().remove(a);
            f.server.getPlayerList().remove(b);
            f.server.getPlayerList().remove(late);
            var returning = f.player();
            helper.assertValueEqual(returning.getEffect(MobEffects.REGENERATION).getDuration(), 49,
                    "An empty server preserves the group's effect duration");
        }
        helper.succeed();
    }

    @GameTest
    public void reloadedEqualEffectsReplacePrivateInstancesOnJoin(GameTestHelper helper) {
        try (var f = new Fixture(helper)) {
            var a = f.player();
            var b = f.player();
            a.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 50));
            var ops = RegistryOps.create(JsonOps.INSTANCE, f.server.registryAccess());
            var saved = SharedVitalsState.CODEC.encodeStart(ops, f.state).getOrThrow();
            var loaded = SharedVitalsState.CODEC.parse(ops, saved).getOrThrow();
            f.state.leave(a);
            f.state.leave(b);
            var privateA = a.getEffect(MobEffects.REGENERATION);
            var privateB = b.getEffect(MobEffects.REGENERATION);
            helper.assertTrue(privateA != privateB && privateA.equals(privateB),
                    "Reload begins with separate but equal player effects");
            f.server.getDataStorage().set(SharedVitalsState.TYPE, loaded);
            loaded.join(a);
            loaded.join(b);
            var shared = a.getEffect(MobEffects.REGENERATION);
            helper.assertTrue(shared != privateA && shared != privateB && shared == b.getEffect(MobEffects.REGENERATION),
                    "Joining replaces equal private instances with the group's actual instance");
            helper.assertTrue(loaded.isSharedEffect(a, shared) && loaded.isSharedEffect(b, shared),
                    "Both reloaded players use the shared clock");
            for (var mode : List.of(GameType.CREATIVE, GameType.SPECTATOR)) {
                b.setGameMode(mode);
                var detached = b.getEffect(MobEffects.REGENERATION);
                helper.assertTrue(detached != shared && detached.equals(shared), "Exclusion creates an independent equal instance");
                b.setGameMode(GameType.SURVIVAL);
                helper.assertTrue(b.getEffect(MobEffects.REGENERATION) == shared, "Rejoining replaces the equal detached instance");
            }
            a.setHealth(10);
            a.getFoodData().setFoodLevel(0);
            b.doTick();
            a.doTick();
            helper.assertValueEqual(a.getHealth(), 11F, "Reloaded players apply one regeneration pulse");
            helper.assertValueEqual(shared.getDuration(), 49, "Reloaded players advance the clock once");
        }
        helper.succeed();
    }

    @GameTest
    public void sharedAmplifierChangesRefreshModifiersAndPackets(GameTestHelper helper) {
        var types = List.of(MobEffects.SPEED, MobEffects.STRENGTH, MobEffects.ABSORPTION);
        var attributes = List.of(Attributes.MOVEMENT_SPEED, Attributes.ATTACK_DAMAGE, Attributes.MAX_ABSORPTION);
        for (int index = 0; index < types.size(); index++) {
            try (var f = new Fixture(helper)) {
                var a = f.player();
                var b = f.player();
                var type = types.get(index);
                var attribute = attributes.get(index);
                a.addEffect(new MobEffectInstance(type, 100));
                var weakValue = b.getAttributeValue(attribute);
                f.takePackets(b);
                a.addEffect(new MobEffectInstance(type, 2, 1));
                helper.assertTrue(a.getEffect(type) == b.getEffect(type), "An upgrade mutates the common instance");
                helper.assertTrue(b.getAttributeValue(attribute) > weakValue, "The teammate's actual attribute is upgraded");
                helper.assertValueEqual(b.getAttributeValue(attribute), a.getAttributeValue(attribute),
                        "Both players' actual attributes match after upgrading");
                type.value().createModifiers(1, (modifiedAttribute, modifier) -> {
                    helper.assertValueEqual(b.getAttribute(modifiedAttribute).getModifier(modifier.id()).amount(), modifier.amount(),
                            "The teammate's native effect modifier has the new amplifier");
                });
                helper.assertTrue(f.takePackets(b).stream().anyMatch(packet -> packet instanceof ClientboundUpdateMobEffectPacket effect
                                && effect.getEntityId() == b.getId() && effect.getEffect().equals(type) && effect.getEffectAmplifier() == 1),
                        "The teammate receives the upgraded amplifier packet");
                a.doTick();
                b.doTick();
                f.state.tick(f.server);
                a.doTick();
                b.doTick();
                helper.assertValueEqual(b.getAttributeValue(attribute), weakValue,
                        "Promotion of the hidden weaker effect restores its actual attribute value");
                type.value().createModifiers(0, (modifiedAttribute, modifier) -> {
                    helper.assertValueEqual(b.getAttribute(modifiedAttribute).getModifier(modifier.id()).amount(), modifier.amount(),
                            "The native modifier also downgrades with the hidden effect");
                });
                helper.assertTrue(f.takePackets(b).stream().anyMatch(packet -> packet instanceof ClientboundUpdateMobEffectPacket effect
                                && effect.getEntityId() == b.getId() && effect.getEffect().equals(type) && effect.getEffectAmplifier() == 0),
                        "The teammate receives the hidden-effect downgrade packet");
            }
        }
        helper.succeed();
    }

    @GameTest
    public void timedAbsorptionRemovalPreservesRemainingDirectCapacity(GameTestHelper helper) {
        for (var removal : List.of("remove", "milk", "expire")) {
            for (double baseCapacity : List.of(20D, 2D)) {
                try (var f = new Fixture(helper)) {
                    var a = f.player();
                    var b = f.player();
                    a.getAttribute(Attributes.MAX_ABSORPTION).setBaseValue(baseCapacity);
                    a.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 1));
                    a.setAbsorptionAmount(baseCapacity == 20D ? 8 : 6);
                    if (removal.equals("remove")) a.removeEffect(MobEffects.ABSORPTION);
                    else if (removal.equals("milk")) new ItemStack(Items.MILK_BUCKET).finishUsingItem(a.level(), a);
                    else a.doTick();
                    float remaining = baseCapacity == 20D ? 8 : 2;
                    for (var player : List.of(a, b)) {
                        helper.assertFalse(player.hasEffect(MobEffects.ABSORPTION), "The timed effect is removed via " + removal);
                        helper.assertValueEqual(player.getAbsorptionAmount(), remaining,
                                "Post-removal absorption is preserved within remaining vanilla capacity via " + removal);
                    }
                    var late = f.player();
                    helper.assertValueEqual(late.getAbsorptionAmount(), remaining, "A late join inherits the surviving direct grant");
                    b.hurtServer(b.level(), b.damageSources().generic(), 3);
                    helper.assertValueEqual(a.getAbsorptionAmount(), Math.max(0F, remaining - 3),
                            "Damage consumes the remaining shared absorption once");
                    helper.assertValueEqual(a.getHealth(), remaining >= 3 ? 20F : 19F,
                            "Only unabsorbed damage reaches the shared health pool");
                }
            }
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
            helper.assertValueEqual(b.getAbsorptionAmount(), a.getAbsorptionAmount(), "Totem absorption protects the group");
            helper.assertTrue(a.hasEffect(MobEffects.REGENERATION) && b.hasEffect(MobEffects.REGENERATION),
                    "The totem's status effects are visible to the whole group");
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
            helper.assertValueEqual(nextA.getAbsorptionAmount(), 0F, "A new life starts without golden hearts");
            helper.assertFalse(nextA.hasEffect(MobEffects.ABSORPTION), "A new life does not retain the old absorption effect");
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
            a.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 600, 2));
            a.addEffect(new MobEffectInstance(MobEffects.SPEED, 200, 1));
            a.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 51));
            a.hurtServer(a.level(), a.damageSources().generic(), 3);
            ((FoodDataAccessor) a.getFoodData()).sharedVitals$setTimer(42);
            var snapshot = FoodSnapshot.capture(a.getFoodData());
            f.state.captureAbsorptionEffect();
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
                helper.assertValueEqual(b.getAbsorptionAmount(), 9F, "Spent absorption survives a disk reload without refilling");
                helper.assertValueEqual(b.getEffect(MobEffects.ABSORPTION).getDuration(), 600,
                        "The absorption lifetime survives a disk reload");
                helper.assertValueEqual(b.getEffect(MobEffects.ABSORPTION).getAmplifier(), 2,
                        "The absorption strength survives a disk reload");
                helper.assertValueEqual(b.getEffect(MobEffects.SPEED).getDuration(), 200,
                        "Other shared status effects survive a real saved-data reload");
                helper.assertValueEqual(b.getEffect(MobEffects.REGENERATION).getDuration(), 51,
                        "Reloading does not refresh the regeneration timer");
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
            for (var field : List.of("absorptionInitialized", "absorption", "absorptionEffect", "effects")) original.remove(field);
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

        List<Object> takePackets(ServerPlayer player) {
            var packets = new ArrayList<>();
            var channel = channels.get(players.indexOf(player));
            Object packet;
            while ((packet = channel.readOutbound()) != null) packets.add(packet);
            return packets;
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
