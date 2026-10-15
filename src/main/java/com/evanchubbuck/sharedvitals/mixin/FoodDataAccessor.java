package com.evanchubbuck.sharedvitals.mixin;

import net.minecraft.world.food.FoodData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(FoodData.class)
public interface FoodDataAccessor {
    @Accessor("exhaustionLevel") float sharedVitals$getExhaustion();
    @Accessor("exhaustionLevel") void sharedVitals$setExhaustion(float value);
    @Accessor("tickTimer") int sharedVitals$getTimer();
    @Accessor("tickTimer") void sharedVitals$setTimer(int value);
}
