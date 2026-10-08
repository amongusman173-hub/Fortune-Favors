package com.fortuneandfavors.mixin;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The sibling of {@link MobGoalAccessor} for the other half of a mob's AI.
 *
 * <p>A scripted boss that wants real pathfinding needs a target for the movement
 * goals to walk at, and the target selector is {@code protected} on {@code Mob}
 * - so it is reached through an accessor exactly the way the goal selector is.
 */
@Mixin(Mob.class)
public interface MobTargetAccessor {
   @Accessor("targetSelector")
   GoalSelector fortuneandfavors$targetSelector();
}
