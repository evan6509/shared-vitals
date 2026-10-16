package com.evanchubbuck.sharedvitals;

import com.evanchubbuck.sharedvitals.mixin.MobEffectInstanceAccessor;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** One mutable instance per effect: every participant sees it, only one ticks it. */
public final class SharedEffects {
    public static final Codec<SharedEffects> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.BOOL.fieldOf("initialized").forGetter(state -> state.initialized),
            MobEffectInstance.CODEC.listOf().fieldOf("active").forGetter(state -> new ArrayList<>(state.active.values()))
    ).apply(instance, SharedEffects::new));

    private boolean initialized;
    private final Map<Holder<MobEffect>, MobEffectInstance> active = new LinkedHashMap<>();
    private final Set<Holder<MobEffect>> ticked = new HashSet<>();
    private boolean applying;

    public SharedEffects() { }

    private SharedEffects(boolean initialized, List<MobEffectInstance> effects) {
        this.initialized = initialized;
        for (var effect : effects) active.put(effect.getEffect(), effect);
    }

    public boolean applying() { return applying; }
    public boolean initialized() { return initialized; }

    public void initialize(ServerPlayer player, MobEffectInstance legacyAbsorption) {
        if (initialized) return;
        initialized = true;
        for (var effect : player.getActiveEffects()) active.put(effect.getEffect(), effect);
        // Pre-effect-sharing worlds already persisted the group's absorption lifetime.
        active.remove(MobEffects.ABSORPTION);
        if (legacyAbsorption != null) active.put(MobEffects.ABSORPTION, legacyAbsorption);
    }

    public void join(ServerPlayer player) {
        applying = true;
        try {
            for (var type : new ArrayList<>(player.getActiveEffectsMap().keySet())) {
                if (!active.containsKey(type)) player.removeEffect(type);
            }
            for (var effect : active.values()) {
                // In 26.2 forceAddEffect replaces the map entry even for equal values.
                if (player.getEffect(effect.getEffect()) != effect) player.forceAddEffect(effect, null);
            }
        } finally {
            applying = false;
        }
    }

    public void changed(ServerPlayer source, Holder<MobEffect> type, Collection<ServerPlayer> participants) {
        if (applying) return;
        var effect = source.getEffect(type);
        if (effect == null) active.remove(type);
        else active.put(type, effect);
        applying = true;
        try {
            for (var player : participants) {
                if (player == source || !SharedVitalsState.eligible(player) || !player.isAlive()) continue;
                // It also refreshes modifiers/packets when this same instance mutated in place.
                if (effect == null) player.removeEffect(type);
                else player.forceAddEffect(effect, null);
            }
        } finally {
            applying = false;
        }
    }

    public void detach(ServerPlayer player) {
        // Creative/spectator/offline players must stop mutating the group's instances.
        for (var effect : new ArrayList<>(player.getActiveEffects())) player.forceAddEffect(copy(effect), null);
    }

    public void freeze() { active.replaceAll((type, effect) -> copy(effect)); }

    public MobEffectInstance get(Holder<MobEffect> type) { return active.get(type); }

    public boolean contains(MobEffectInstance effect) { return active.get(effect.getEffect()) == effect; }

    public void startTick() { ticked.clear(); }

    public boolean claimTick(MobEffectInstance effect) { return ticked.add(effect.getEffect()); }

    public void clear() {
        active.clear();
        ticked.clear();
        initialized = true;
    }

    private static MobEffectInstance copy(MobEffectInstance effect) {
        var hidden = ((MobEffectInstanceAccessor) effect).sharedVitals$getHiddenEffect();
        return new MobEffectInstance(effect.getEffect(), effect.getDuration(), effect.getAmplifier(),
                effect.isAmbient(), effect.isVisible(), effect.showIcon(), hidden == null ? null : copy(hidden));
    }
}
