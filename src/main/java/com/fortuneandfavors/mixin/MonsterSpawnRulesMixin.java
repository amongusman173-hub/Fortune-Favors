package com.fortuneandfavors.mixin;

import com.fortuneandfavors.economy.ServerDisasterManager;
import com.fortuneandfavors.util.Safe;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.ServerLevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The Solar Eclipse lets monsters out during the day.
 *
 * <p>A dark sky is a mood, not an event: without this the eclipse is a filter over the
 * screen for ten minutes while nothing changes about how the world behaves. The mechanical
 * half is that the monsters stop waiting for night - so the rule the game actually applies
 * ({@code Monster.isDarkEnoughToSpawn}) is what gets relaxed, rather than a second spawn
 * system being bolted on beside it. Everything else about spawning still applies: light
 * level inside caves, biome rules, the mob cap, the claim check in
 * {@code NaturalSpawnerMixin}, and the peace rule the Aurora uses.
 *
 * <p>This is deliberately the <i>only</i> thing the eclipse changes about spawning, and it
 * is asked once per spawn attempt through a one-line question, so switching the event off
 * restores vanilla behaviour on the next attempt with nothing to unwind.
 */
@Mixin(Monster.class)
public abstract class MonsterSpawnRulesMixin {
   @Inject(method = "isDarkEnoughToSpawn", at = @At("HEAD"), cancellable = true)
   private static void fortuneandfavors$eclipseLetsThemOut(
      ServerLevelAccessor level, BlockPos pos, RandomSource random, CallbackInfoReturnable<Boolean> cir
   ) {
      Safe.run("eclipse spawn rule", () -> {
         if (ServerDisasterManager.nightRulesActive()) {
            cir.setReturnValue(true);
         }
      });
   }
}
