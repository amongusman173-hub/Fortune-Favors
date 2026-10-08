package com.fortuneandfavors.mixin;

import com.fortuneandfavors.economy.BossManager;
import com.fortuneandfavors.economy.PlayerRaidManager;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.level.storage.loot.LootTable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Mob.class)
public abstract class MobMixin {
   @Shadow
   protected GoalSelector goalSelector;
   @Shadow
   protected GoalSelector targetSelector;

   @Inject(method = "setTarget", at = @At("HEAD"), cancellable = true)
   private void fortuneandfavors$protectFriendlyTarget(LivingEntity target, CallbackInfo ci) {
      Mob self = ((Mob)(Object)this);
      // The End's natives never turn on each other: the dragon does not hunt the endermen and the
      // endermen do not mob the dragon. Enforced the moment a target is adopted, so no AI has to be
      // cleaned up afterwards.
      if (!com.fortuneandfavors.economy.EnderDragonManager.endNativeTruce(self, target)) {
         ci.cancel();
         return;
      }
      // Raid mobs never target a player who joined the Warlord - they fight for
      // him now. (Friends can still hurt the betrayer; only the raid mobs spare
      // them, matching the Warlord's promise.)
      if (target != null && target instanceof ServerPlayer p
         && PlayerRaidManager.isActiveBetrayer(p.getUUID())
         && PlayerRaidManager.isRaidMob(self)) {
         ci.cancel();
         return;
      }
      if (target != null && BossManager.isFriendlySkeleton(self)) {
         UUID owner = BossManager.friendlyOwner(self);
         if (owner != null) {
            if (owner.equals(target.getUUID())) {
               ci.cancel();
               return;
            }

            // The shared clock, matching the stamps BossManager's own aggressive windows are made
            // on - reading the level's game time here disagrees with it outside the overworld.
            if (target instanceof ServerPlayer p && !BossManager.isAggressor(
               owner, p.getUUID(), com.fortuneandfavors.economy.ServerClock.clock(self.level())
            )) {
               ci.cancel();
               return;
            }
         }
      }

      if (target != null && BossManager.isFriendlySkeleton(self) && BossManager.isFriendlySkeleton(target)) {
         ci.cancel();
      } else {
         if (target != null
            && BossManager.isGuard(self)
            && (BossManager.isGuard(target) || BossManager.isBoss(target) || BossManager.isFriendlySkeleton(target))) {
            ci.cancel();
         }
      }
   }

   @Inject(method = "aiStep", at = @At("HEAD"))
   private void fortuneandfavors$stripFriendlyGoals(CallbackInfo ci) {
      Mob self = ((Mob)(Object)this);
      boolean friendly = (self.getType() == EntityTypes.WITHER_SKELETON || self.getType() == EntityTypes.VEX) && BossManager.isFriendlySkeleton(self);
      if (friendly && !this.goalSelector.getAvailableGoals().isEmpty()) {
         this.goalSelector.removeAllGoals(g -> true);
         this.targetSelector.removeAllGoals(g -> true);
      }
   }

   // Backfill for rare mob variants that were hidden before tracking started
   // (e.g. a server restart): only invisible mobs pay the cost of the tag read,
   // so ordinary mobs stay free.
   @Inject(method = "aiStep", at = @At("HEAD"))
   private void fortuneandfavors$variantBackfill(CallbackInfo ci) {
      Mob self = ((Mob)(Object)this);
      if (self.level().isClientSide() || self.tickCount % 10 != 0 || !self.isInvisible()) {
         return;
      }
      com.fortuneandfavors.economy.RareMobVariantManager.registerIfUntracked(self);
   }

   @Inject(method = "getLootTable", at = @At("HEAD"), cancellable = true)
   private void fortuneandfavors$noLoot(CallbackInfoReturnable<Optional<ResourceKey<LootTable>>> cir) {
      Mob self = ((Mob)(Object)this);
      if (BossManager.isKingLike(self)) {
         cir.setReturnValue(Optional.empty());
         return;
      }
      // A duplicate risen out of a player's own death carries no loot table at all. Its gear is a
      // *copy*, so a killed duplicate handing anything back would be a way to duplicate a kit - and
      // the one thing that must never happen is killing your own reflection being profitable.
      if (com.fortuneandfavors.economy.ReactiveZombie.isDuplicate(self)) {
         cir.setReturnValue(Optional.empty());
      }
   }
}
