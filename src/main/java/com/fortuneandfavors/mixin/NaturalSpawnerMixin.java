package com.fortuneandfavors.mixin;

import com.fortuneandfavors.FortuneFavorsMod;
import com.fortuneandfavors.economy.BossManager;
import com.fortuneandfavors.economy.ClaimManager;
import com.fortuneandfavors.economy.ClaimManager.Claim;
import com.fortuneandfavors.economy.ExpeditionManager;
import com.fortuneandfavors.economy.ServerDisasterManager;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.Entity.RemovalReason;
import net.minecraft.world.entity.monster.cubemob.Slime;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.NaturalSpawner.AfterSpawnCallback;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(NaturalSpawner.class)
public abstract class NaturalSpawnerMixin {
   @Redirect(
      method = "spawnCategoryForPosition(Lnet/minecraft/world/entity/MobCategory;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/NaturalSpawner$SpawnPredicate;Lnet/minecraft/world/level/NaturalSpawner$AfterSpawnCallback;)V",
      at = @At(
         value = "INVOKE",
         target = "Lnet/minecraft/world/level/NaturalSpawner$AfterSpawnCallback;run(Lnet/minecraft/world/entity/Mob;Lnet/minecraft/world/level/chunk/ChunkAccess;)V"
      )
   )
   private static void fortuneandfavors$noHostileSpawns(AfterSpawnCallback callback, Mob mob, ChunkAccess chunk) {
      boolean discard = false;

      try {
         if (mob.getType().getCategory() == MobCategory.MONSTER) {
            // The Aurora's whole promise, and it is kept here rather than by a second
            // spawner: during it, nothing hostile appears at all, anywhere.
            if (ServerDisasterManager.peaceActive()) {
               discard = true;
            }

            // The expedition realm keeps its own monsters, and this is where that is enforced.
            // A site is carved in a void whose only solid surface is the dungeon's own roof, so
            // vanilla's spawner had exactly one place to put anything: on top of the maze.
            // Monsters standing on the roof are unmarked, unglowing and unreachable, which is
            // reported - correctly - as "the enemies spawn in the wrong place and never light
            // up". Every body inside a site comes out of the run's own factory instead, which is
            // also what puts the glow on it.
            if (ExpeditionManager.isExpeditionRealm(mob.level())) {
               discard = true;
            }

            Claim c = ClaimManager.claimAt(mob.level(), mob.blockPosition());
            if (c != null && !c.allowMobSpawns) {
               discard = true;
            }
         }

         if (mob instanceof Slime && BossManager.isSnowRealm(mob.level())) {
            discard = true;
         }
      } catch (Throwable t) {
         FortuneFavorsMod.LOGGER.warn("Fortune & Favors: error during mob spawn claim check", t);
      }

      if (discard) {
         mob.remove(RemovalReason.DISCARDED);
      } else {
         try {
            com.fortuneandfavors.economy.RareMobVariantManager.apply(mob);
         } catch (Throwable t) {
         }
         callback.run(mob, chunk);
      }
   }
}
