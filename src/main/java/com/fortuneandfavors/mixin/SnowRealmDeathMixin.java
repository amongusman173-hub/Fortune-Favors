package com.fortuneandfavors.mixin;

import com.fortuneandfavors.economy.BossManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public class SnowRealmDeathMixin {
   @Inject(method = "dropAllDeathLoot", at = @At("HEAD"), cancellable = true)
   private void ffSnowRealmHoldsDrops(ServerLevel level, DamageSource source, CallbackInfo ci) {
      if (((Object)this) instanceof ServerPlayer player) {
         // With NKI on, NKI owns the death: cancelling here used to skip the drop hook that takes
         // NKI's snapshot, so NKI kept nothing and the realm's ice cube was the only copy left.
         if (BossManager.realmHoldsDrops(player) && !com.fortuneandfavors.NiceKeepInventoryManager.isEnabled()) {
            ci.cancel();
            BossManager.captureRealmDeath(player);
         }
      }
   }
}
