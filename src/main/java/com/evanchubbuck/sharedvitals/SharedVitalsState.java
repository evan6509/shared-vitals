package com.evanchubbuck.sharedvitals;

import com.evanchubbuck.sharedvitals.mixin.PlayerAccessor;
import com.evanchubbuck.sharedvitals.mixin.MobEffectInstanceAccessor;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.protocol.game.ClientboundHurtAnimationPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class SharedVitalsState extends SavedData {
    public static final Codec<SharedVitalsState> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.BOOL.fieldOf("initialized").forGetter(state -> state.initialized),
            Codec.floatRange(0, Float.MAX_VALUE).fieldOf("health").forGetter(state -> state.health),
            FoodSnapshot.CODEC.fieldOf("hunger").forGetter(state -> FoodSnapshot.capture(state.food)),
            Codec.BOOL.optionalFieldOf("waitingForTransfer", false).forGetter(state -> state.waitingForTransfer),
            Codec.BOOL.optionalFieldOf("absorptionInitialized", false).forGetter(state -> state.absorptionInitialized),
            Codec.floatRange(0, Float.MAX_VALUE).optionalFieldOf("absorption", 0F).forGetter(state -> state.absorption),
            MobEffectInstance.CODEC.optionalFieldOf("absorptionEffect").forGetter(state -> Optional.ofNullable(state.absorptionEffect)),
            SharedEffects.CODEC.optionalFieldOf("effects").forGetter(state -> Optional.of(state.effects))
    ).apply(instance, SharedVitalsState::new));
    public static final SavedDataType<SharedVitalsState> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(SharedVitalsMod.MOD_ID, "vitals"), SharedVitalsState::new, CODEC, null);

    private boolean initialized;
    private boolean waitingForTransfer;
    private float health = 20;
    private FoodData food = new FoodData();
    private boolean absorptionInitialized;
    private float absorption;
    private MobEffectInstance absorptionEffect;
    private boolean applyingAbsorption;
    private final SharedEffects effects;
    private static final Identifier ABSORPTION_CAPACITY = Identifier.fromNamespaceAndPath(SharedVitalsMod.MOD_ID, "absorption_capacity");
    // Runtime membership is deliberately not saved. Identity checks exclude player construction/loading.
    private final Map<UUID, ServerPlayer> members = new LinkedHashMap<>();
    private boolean applying;
    private int lastFoodTick = Integer.MIN_VALUE;
    private int lastPeacefulTick = Integer.MIN_VALUE;

    public SharedVitalsState() { effects = new SharedEffects(); }

    private SharedVitalsState(boolean initialized, float health, FoodSnapshot food, boolean waitingForTransfer,
                              boolean absorptionInitialized, float absorption, Optional<MobEffectInstance> absorptionEffect,
                              Optional<SharedEffects> effects) {
        this.initialized = initialized;
        this.health = health;
        this.food = food.restore();
        this.waitingForTransfer = waitingForTransfer;
        this.absorptionInitialized = absorptionInitialized;
        this.absorption = absorption;
        this.absorptionEffect = absorptionEffect.orElse(null);
        this.effects = effects.orElseGet(SharedEffects::new);
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
        if (absorptionInitialized) captureAbsorptionEffect();
        if (!initialized) {
            initialized = true;
            health = player.getHealth();
            food = FoodSnapshot.capture(player.getFoodData()).restore();
        }
        if (!absorptionInitialized) {
            // Older saved pools have no absorption fields; seed them once from a real player.
            absorptionInitialized = true;
            absorption = player.getAbsorptionAmount();
            absorptionEffect = copyAbsorptionEffect(player.getEffect(MobEffects.ABSORPTION));
        }
        effects.initialize(player, absorptionEffect);
        members.put(player.getUUID(), player);
        ((PlayerAccessor) player).sharedVitals$setFoodData(food);
        if (player.isAlive()) {
            applyingAbsorption = true;
            try {
                effects.join(player);
                applyAbsorptionAmount(player);
            } finally {
                applyingAbsorption = false;
            }
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
        if (removed) {
            effects.detach(player);
            player.getAttribute(Attributes.MAX_ABSORPTION).removeModifier(ABSORPTION_CAPACITY);
            player.setAbsorptionAmount(player.getAbsorptionAmount());
            captureAbsorptionEffect();
            if (members.values().stream().noneMatch(member -> contains(member) && member.isAlive())) effects.freeze();
            absorptionEffect = effects.get(MobEffects.ABSORPTION);
            setDirty();
        }
    }

    public void tick(MinecraftServer server) {
        effects.startTick();
        for (var member : new ArrayList<>(members.values())) {
            if (!eligible(member) || server.getPlayerList().getPlayer(member.getUUID()) != member) leave(member);
        }
        for (var player : server.getPlayerList().getPlayers()) join(player);
        if (!waitingForTransfer && initialized && health > 0) publishHealth(Math.min(health, maximumHealth()));
    }

    public void healthChanged(ServerPlayer player) {
        // Zero is provisional until vanilla has tried the damaged player's totem/death protection.
        // Join reconciliation may clamp private health before the player adopts the pool.
        if (!applying && !applyingAbsorption && contains(player) && health > 0 && player.getHealth() > 0) {
            publishHealth(Math.min(player.getHealth(), maximumHealth()));
        }
    }

    public void absorptionChanged(ServerPlayer player) {
        if (applyingAbsorption || effects.applying() || waitingForTransfer || health <= 0 || !contains(player)) return;
        absorption = player.getAbsorptionAmount();
        absorptionEffect = effects.get(MobEffects.ABSORPTION);
        publishAbsorption(player);
    }

    public void effectChanged(ServerPlayer player, Holder<MobEffect> type) {
        if (applyingAbsorption || effects.applying() || waitingForTransfer || health <= 0 || !contains(player)) return;
        effects.changed(player, type, members.values());
        if (type.equals(MobEffects.ABSORPTION)) {
            absorptionEffect = effects.get(type);
            // Vanilla already clamps to the capacity left after removing the effect.
            // A base attribute or direct grant can still support golden hearts.
            absorption = player.getAbsorptionAmount();
            publishAbsorption(player);
        }
        setDirty();
    }

    public boolean isSharedEffect(ServerPlayer player, MobEffectInstance effect) {
        return contains(player) && !waitingForTransfer && health > 0 && effects.contains(effect);
    }

    public boolean allowEffectTick(ServerPlayer player, MobEffectInstance effect) {
        return isFoodLeader(player) && effects.claimTick(effect);
    }

    private void publishAbsorption(ServerPlayer player) {
        applyingAbsorption = true;
        try {
            for (var member : members.values()) {
                if (!contains(member) || !member.isAlive()) continue;
                applyAbsorptionAmount(member);
            }
        } finally {
            applyingAbsorption = false;
        }
        setDirty();
    }

    private void applyAbsorptionAmount(ServerPlayer player) {
        var capacity = player.getAttribute(Attributes.MAX_ABSORPTION);
        capacity.removeModifier(ABSORPTION_CAPACITY);
        // Direct absorption grants can have no effect on teammates to supply capacity.
        // A transient modifier preserves their normal base attribute and effect modifiers.
        if (capacity.getValue() < absorption) {
            double base = capacity.getBaseValue();
            double baseMultiplier = 1;
            double totalMultiplier = 1;
            for (var modifier : capacity.getModifiers()) {
                switch (modifier.operation()) {
                    case ADD_VALUE -> base += modifier.amount();
                    case ADD_MULTIPLIED_BASE -> baseMultiplier += modifier.amount();
                    case ADD_MULTIPLIED_TOTAL -> totalMultiplier *= 1 + modifier.amount();
                }
            }
            // Vanilla also multiplies ADD_VALUE modifiers; solve for the unscaled bonus.
            capacity.addTransientModifier(new AttributeModifier(ABSORPTION_CAPACITY,
                    absorption / (baseMultiplier * totalMultiplier) - base, AttributeModifier.Operation.ADD_VALUE));
        }
        player.setAbsorptionAmount(absorption);
    }

    public void captureAbsorptionEffect() {
        if (waitingForTransfer || health <= 0 || !effects.initialized()) return;
        absorptionEffect = effects.get(MobEffects.ABSORPTION);
    }

    private static MobEffectInstance copyAbsorptionEffect(MobEffectInstance effect) {
        if (effect == null) return null;
        var hidden = ((MobEffectInstanceAccessor) effect).sharedVitals$getHiddenEffect();
        return new MobEffectInstance(MobEffects.ABSORPTION, effect.getDuration(), effect.getAmplifier(),
                effect.isAmbient(), effect.isVisible(), effect.showIcon(), copyAbsorptionEffect(hidden));
    }

    public void shareHurtFeedback(ServerPlayer victim, DamageSource source) {
        if (applying || waitingForTransfer || health <= 0 || !contains(victim)) return;
        // Keep the damage type for vanilla's hurt sound, but omit attacker IDs/positions:
        // teammates can be in another dimension where those entities do not exist.
        var feedbackSource = new DamageSource(source.typeHolder());
        for (var member : members.values()) {
            if (member == victim || !contains(member) || !member.isAlive()) continue;
            member.animateHurt(victim.getHurtDir());
            member.level().broadcastDamageEvent(member, feedbackSource);
            member.connection.send(new ClientboundHurtAnimationPacket(member));
            // The damage event plays the victim's own sound on their client; vanilla's
            // server sound path excludes that player and supplies it to nearby listeners.
            member.makeSound(feedbackSource.type().effects().sound());
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
        absorption = 0;
        absorptionEffect = null;
        effects.clear();
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
            absorption = 0;
            absorptionEffect = null;
            effects.clear();
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

    public boolean allowPeacefulRegeneration(ServerPlayer player) {
        if (!contains(player)) return true;
        if (!isFoodLeader(player)) return false;
        int tick = player.level().getServer().getTickCount();
        if (lastPeacefulTick == tick) return false;
        lastPeacefulTick = tick;
        return true;
    }

    public boolean waitingForTransfer() {
        return waitingForTransfer;
    }

    public void prepareLinkedTransfer(MinecraftServer server) {
        if (!SharedVitalsSettings.get(server).linkedHardcore()) return;
        waitingForTransfer = true;
        health = 0;
        absorption = 0;
        absorptionEffect = null;
        effects.clear();
        setDirty();
        for (var online : new ArrayList<>(server.getPlayerList().getPlayers())) join(online);
    }
}
