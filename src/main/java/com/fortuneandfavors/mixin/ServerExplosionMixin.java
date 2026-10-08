package com.fortuneandfavors.mixin;

import com.fortuneandfavors.NiceKeepInventoryManager;
import com.fortuneandfavors.duel.DuelManager;
import com.fortuneandfavors.economy.ClaimManager;
import com.fortuneandfavors.economy.ExplosionRebuildManager;
import com.fortuneandfavors.economy.ModConfig;
import com.fortuneandfavors.util.Safe;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.projectile.hurtingprojectile.WitherSkull;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.level.Explosion.BlockInteraction;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.SpawnerBlock;
import net.minecraft.world.level.block.TrialSpawnerBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerExplosion.class)
public abstract class ServerExplosionMixin {
   @Inject(method = "calculateExplodedPositions", at = @At("RETURN"))
   private void fortuneandfavors$protectClaims(CallbackInfoReturnable<List<BlockPos>> cir) {
      Safe.run(
         "explosion claim filter",
         () -> {
            List<BlockPos> list = (List<BlockPos>)cir.getReturnValue();
            if (list != null && !list.isEmpty()) {
               ServerExplosion self = ((ServerExplosion)(Object)this);
               if (destroysBlocks(self)) {
                  Level level = self.level();
                  // A standing Mirage Castle is unbreakable, and that has to mean its walls
                  // as much as its blocks: one creeper inside the courtyard would otherwise
                  // open a hole in a structure whose whole promise is that it cannot be
                  // touched - and the undo could never put it back exactly, because the hole
                  // would be filled from the blast rather than restored from the plan.
                  list.removeIf(pos -> com.fortuneandfavors.economy.MirageCastleManager.isProtected(level, pos));
                  if (DuelManager.isDuelRealm(level) && DuelManager.anyActiveDuelFight(level)) {
                     list.removeIf(pos -> !DuelManager.isPlayerPlacedInAnyDuel((ServerLevel)level, pos) && !DuelManager.isBedwarsBed((ServerLevel)level, pos));
                  } else {
                     ServerPlayer sourcePlayer = self.getIndirectSourceEntity() instanceof ServerPlayer sp ? sp : null;
                     if (sourcePlayer != null) {
                        list.removeIf(pos -> !ClaimManager.canBuild(sourcePlayer, pos));
                     } else {
                        list.removeIf(pos -> ClaimManager.claimAt(level, pos) != null);
                     }

                     list.removeIf(pos -> {
                        BlockState st = level.getBlockState(pos);
                        Block b = st.getBlock();
                        return b instanceof SpawnerBlock || b instanceof TrialSpawnerBlock;
                     });
                     // Containers, doors and beds are pulled out of the blast so
                     // their contents survive the rebuild. That protection has to
                     // apply to the wither even when Explosion Rebuild is off,
                     // because wither wreckage always rebuilds (see below).
                     if (ModConfig.explosionRebuild() || witherBlast(self) || meteorBlast(self)) {
                        list.removeIf(pos -> {
                           BlockState st = level.getBlockState(pos);
                           Block b = st.getBlock();
                           return b instanceof DoorBlock || b instanceof BedBlock || level.getBlockEntity(pos) instanceof Container;
                        });
                     }

                     list.removeIf(
                        pos -> level.getBlockState(pos).is(Blocks.PLAYER_HEAD)
                           && level instanceof ServerLevel sl
                           && NiceKeepInventoryManager.isGraveHead(sl, pos)
                     );
                  }
               }
            }
         }
      );
   }

   @Redirect(
      method = "interactWithBlocks",
      at = @At(
         value = "INVOKE",
         target = "Lnet/minecraft/world/level/block/Block;popResource(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/item/ItemStack;)V"
      )
   )
   private void fortuneandfavors$suppressExplosionDrops(Level level, BlockPos pos, ItemStack stack) {
      // A wither blast always rebuilds, so it never drops what it broke - a drop
      // plus a restored block is a duplicated item.
      ServerExplosion self = ((ServerExplosion)(Object)this);
      if (!ModConfig.explosionRebuild() && !witherBlast(self) && !meteorBlast(self)) {
         Block.popResource(level, pos, stack);
      }
   }

   @Inject(method = "interactWithBlocks", at = @At("HEAD"))
   private void fortuneandfavors$captureRebuild(List<BlockPos> positions, CallbackInfo ci) {
      Safe.run("explosion rebuild capture", () -> {
         ServerExplosion self = ((ServerExplosion)(Object)this);
         if (destroysBlocks(self)) {
            if (self.level() instanceof ServerLevel sl) {
               if (witherBlast(self) || meteorBlast(self)) {
                  ExplosionRebuildManager.captureAlways(sl, positions);
               } else {
                  ExplosionRebuildManager.capture(sl, positions);
               }
            }
         }
      });
   }

   /** Safety net: after interactWithBlocks processes all positions, remove any item
    *  entities that spawned at pending-rebuild positions. This catches drops from
    *  Block.wasExploded, Block.dropResources, or any other path the popResource
    *  redirect might miss. */
   @Inject(method = "interactWithBlocks", at = @At("RETURN"))
   private void fortuneandfavors$suppressRebuildDrops(List<BlockPos> positions, CallbackInfo ci) {
      Safe.run("explosion rebuild drop cleanup", () -> {
         ServerExplosion self = ((ServerExplosion)(Object)this);
         if (destroysBlocks(self)) {
            if (self.level() instanceof ServerLevel sl) {
               if (witherBlast(self) || meteorBlast(self)) {
                  ExplosionRebuildManager.suppressItemDropsAlways(sl, positions);
               } else {
                  ExplosionRebuildManager.suppressItemDrops(sl, positions);
               }
            }
         }
      });
   }

   private static boolean destroysBlocks(ServerExplosion self) {
      BlockInteraction bi = self.getBlockInteraction();
      return bi == BlockInteraction.DESTROY || bi == BlockInteraction.DESTROY_WITH_DECAY;
   }

   /**
    * True when the Wither is behind this blast: its own nuke and slam explosions
    * name it as the source, and a wither skull names it as the projectile's
    * owner. These always rebuild their wreckage, whatever the global Explosion
    * Rebuild setting says - a boss that permanently eats the arena is not a
    * configuration choice.
    */
   private static boolean witherBlast(ServerExplosion self) {
      if (self == null) {
         return false;
      }
      try {
         Entity direct = self.getDirectSourceEntity();
         if (isWither(direct) || self.getIndirectSourceEntity() instanceof WitherBoss) {
            return true;
         }
         return direct instanceof WitherSkull skull && isWither(skull.getOwner());
      } catch (Throwable ignored) {
         return false;
      }
   }

   /**
    * True when a meteor is behind this blast.
    *
    * <p>Weather always heals its own wreckage, for the same reason the wither's does: a
    * crater left by an event nobody aimed is not a choice anybody made. The block-display
    * body a meteor falls as is handed to the explosion as its source entity and carries the
    * tag below, so the whole of "this is a meteor" is one lookup on the thing that caused
    * it - no global state to fall out of step when the event ends mid-fall.
    */
   private static boolean meteorBlast(ServerExplosion self) {
      if (self == null) {
         return false;
      }
      try {
         return isMeteor(self.getDirectSourceEntity()) || isMeteor(self.getIndirectSourceEntity());
      } catch (Throwable ignored) {
         return false;
      }
   }

   private static boolean isMeteor(Entity entity) {
      return entity != null && entity.entityTags().contains(com.fortuneandfavors.economy.ServerDisasterManager.METEOR_TAG);
   }

   private static boolean isWither(Entity entity) {
      // Deliberately not gated on the rework being enabled: the vanilla wither
      // carves just as much, and the promise is about the wither, not the mod.
      return entity instanceof WitherBoss;
   }
}
