package com.fortuneandfavors.mixin;

import com.fortuneandfavors.economy.BossManager;
import com.fortuneandfavors.economy.PlayerRaidManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.projectile.EvokerFangs;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EvokerFangs.class)
public abstract class EvokerFangsMixin {
   @Inject(method = "dealDamageTo", at = @At("HEAD"), cancellable = true)
   private void fortuneandfavors$friendlyFangsSkipFriendly(LivingEntity entity, CallbackInfo ci) {
      try {
         EvokerFangs fangs = ((EvokerFangs)(Object)this);
         LivingEntity owner = fangs.getOwner();
         // Raid evoker fangs never hit a player who joined the Warlord.
         if (entity instanceof ServerPlayer p
            && PlayerRaidManager.isActiveBetrayer(p.getUUID())
            && owner instanceof Mob raidMob
            && PlayerRaidManager.isRaidMob(raidMob)) {
            ci.cancel();
            return;
         }
         if (owner instanceof Mob mob
            && BossManager.isFriendlySkeleton(mob)
            && owner.level() instanceof ServerLevel sl
            && !BossManager.squadMayHurt(mob, sl, entity)) {
            ci.cancel();
         }
      } catch (Exception var8) {
      }
   }
}
