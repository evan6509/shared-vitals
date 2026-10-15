package com.evanchubbuck.sharedvitals;

import com.evanchubbuck.sharedvitals.mixin.PlayerAccessor;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class SharedVitalsState extends SavedData {
    public static final Codec<SharedVitalsState> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.BOOL.fieldOf("initialized").forGetter(state -> state.initialized),
            Codec.floatRange(0, Float.MAX_VALUE).fieldOf("health").forGetter(state -> state.health),
            FoodSnapshot.CODEC.fieldOf("hunger").forGetter(state -> FoodSnapshot.capture(state.food)),
            Codec.BOOL.optionalFieldOf("waitingForTransfer", false).forGetter(state -> state.waitingForTransfer)
    ).apply(instance, SharedVitalsState::new));
    public static final SavedDataType<SharedVitalsState> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(SharedVitalsMod.MOD_ID, "vitals"), SharedVitalsState::new, CODEC, null);

    private boolean initialized;
    private boolean waitingForTransfer;
    private float health = 20;
    private FoodData food = new FoodData();
    // Runtime membership is deliberately not saved. Identity checks exclude player construction/loading.
    private final Map<UUID, ServerPlayer> members = new LinkedHashMap<>();
    private boolean applying;
    private int lastFoodTick = Integer.MIN_VALUE;

    public SharedVitalsState() { }

    private SharedVitalsState(boolean initialized, float health, FoodSnapshot food, boolean waitingForTransfer) {
        this.initialized = initialized;
        this.health = health;
        this.food = food.restore();
        this.waitingForTransfer = waitingForTransfer;
    }

    public static SharedVitalsState get(MinecraftServer server) {
        return server.getDataStorage().computeIfAbsent(TYPE);
    }

    public static boolean eligible(ServerPlayer player) {
        return !player.isCreative() && !player.isSpectator();
    }

    public boolean contains(ServerPlayer player) {
        return eligible(player) && members.get(player.getUUID()) == player;
    }

    public void join(ServerPlayer player) {
        if (waitingForTransfer) {
            // Source-world joins and proxy-requested respawns cannot start another run here.
            leave(player);
            if (eligible(player) && player.isAlive()) player.setGameMode(GameType.SPECTATOR);
            return;
        }
        if (!eligible(player)) {
            leave(player);
            return;
        }
        if (members.get(player.getUUID()) == player) return;
        if (!initialized) {
            initialized = true;
            health = player.getHealth();
            food = FoodSnapshot.capture(player.getFoodData()).restore();
        }
        members.put(player.getUUID(), player);
        ((PlayerAccessor) player).sharedVitals$setFoodData(food);
        if (player.isAlive()) {
            if (health <= 0) kill(player, player.damageSources().genericKill());
            else publishHealth(Math.min(health, maximumHealth()));
        }
        setDirty();
    }

    public void leave(ServerPlayer player) {
        boolean removed = members.remove(player.getUUID(), player);
        // A creative/spectator player's own food must never retain the shared mutable object.
        if (player.getFoodData() == food) {
            ((PlayerAccessor) player).sharedVitals$setFoodData(FoodSnapshot.capture(food).restore());
        }
        if (removed) setDirty();
    }

    public void tick(MinecraftServer server) {
        for (var member : new ArrayList<>(members.values())) {
            if (!eligible(member) || server.getPlayerList().getPlayer(member.getUUID()) != member) leave(member);
        }
        for (var player : server.getPlayerList().getPlayers()) join(player);
        if (!waitingForTransfer && initialized && health > 0) publishHealth(Math.min(health, maximumHealth()));
    }

    public void healthChanged(ServerPlayer player) {
        // Zero is provisional until vanilla has tried the damaged player's totem/death protection.
        if (!applying && contains(player) && health > 0 && player.getHealth() > 0) {
            publishHealth(Math.min(player.getHealth(), maximumHealth()));
        }
    }

    private float maximumHealth() {
        float maximum = Float.MAX_VALUE;
        for (var member : members.values()) {
            if (contains(member) && member.isAlive()) maximum = Math.min(maximum, member.getMaxHealth());
        }
        return maximum;
    }

    private void publishHealth(float value) {
        health = value;
        applying = true;
        try {
            for (var member : members.values()) {
                if (contains(member) && member.isAlive() && member.getHealth() != health) member.setHealth(health);
            }
        } finally {
            applying = false;
        }
        setDirty();
    }

    public void died(ServerPlayer player, DamageSource source) {
        if (applying || waitingForTransfer) return;
        var server = player.level().getServer();
        if (SharedVitalsSettings.get(server).linkedHardcore()) {
            // Linked Hardcore reports ANY player death. Preserve the original event and avoid
            // synthetic deaths/counter inflation while its proxy chooses the next world.
            prepareLinkedTransfer(server);
            return;
        }
        if (!contains(player)) return;
        health = 0;
        applying = true;
        try {
            for (var member : new ArrayList<>(members.values())) {
                if (member != player && contains(member) && member.isAlive()) kill(member, source);
            }
        } finally {
            applying = false;
        }
        setDirty();
    }

    private void kill(ServerPlayer player, DamageSource source) {
        player.setHealth(0);
        player.die(source);
    }

    public void respawn(ServerPlayer oldPlayer, ServerPlayer newPlayer, boolean alive) {
        leave(oldPlayer);
        if (waitingForTransfer) {
            join(newPlayer);
            return;
        }
        if (!eligible(newPlayer)) {
            leave(newPlayer);
            return;
        }
        if (!alive && initialized && health <= 0) {
            // The first respawn opens the next life; later respawns join its current pool.
            health = newPlayer.getMaxHealth();
            food = new FoodData();
            lastFoodTick = Integer.MIN_VALUE;
            for (var member : members.values()) ((PlayerAccessor) member).sharedVitals$setFoodData(food);
        }
        join(newPlayer);
    }

    public boolean isFoodLeader(ServerPlayer player) {
        for (var member : members.values()) {
            if (contains(member) && member.isAlive()) return member == player;
        }
        return false;
    }

    public boolean allowFoodTick(FoodData data, ServerPlayer player) {
        if (data != food) return true;
        if (waitingForTransfer) return false;
        if (!contains(player) || !isFoodLeader(player)) return false;
        int tick = player.level().getServer().getTickCount();
        if (lastFoodTick == tick) return false;
        lastFoodTick = tick;
        return true;
    }

    public boolean waitingForTransfer() {
        return waitingForTransfer;
    }

    public void prepareLinkedTransfer(MinecraftServer server) {
        if (!SharedVitalsSettings.get(server).linkedHardcore()) return;
        waitingForTransfer = true;
        health = 0;
        setDirty();
        for (var online : new ArrayList<>(server.getPlayerList().getPlayers())) join(online);
    }
}
