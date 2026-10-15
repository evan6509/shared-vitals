package com.evanchubbuck.sharedvitals;

import com.evanchubbuck.sharedvitals.mixin.FoodDataAccessor;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.world.food.FoodData;

/** Includes vanilla's hidden exhaustion and regeneration timer, not just the visible food bar. */
public record FoodSnapshot(int food, float saturation, float exhaustion, int timer) {
    public static final Codec<FoodSnapshot> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.intRange(0, 20).fieldOf("food").forGetter(FoodSnapshot::food),
            Codec.floatRange(0, 20).fieldOf("saturation").forGetter(FoodSnapshot::saturation),
            Codec.floatRange(0, 40).fieldOf("exhaustion").forGetter(FoodSnapshot::exhaustion),
            Codec.intRange(0, 80).fieldOf("timer").forGetter(FoodSnapshot::timer)
    ).apply(instance, FoodSnapshot::new));

    public static FoodSnapshot capture(FoodData data) {
        var access = (FoodDataAccessor) data;
        return new FoodSnapshot(data.getFoodLevel(), data.getSaturationLevel(),
                access.sharedVitals$getExhaustion(), access.sharedVitals$getTimer());
    }

    public FoodData restore() {
        var data = new FoodData();
        data.setFoodLevel(food);
        data.setSaturation(saturation);
        var access = (FoodDataAccessor) data;
        access.sharedVitals$setExhaustion(exhaustion);
        access.sharedVitals$setTimer(timer);
        return data;
    }
}
