package com.fortuneandfavors.mixin;

import com.fortuneandfavors.economy.AdvancedEnchantments;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Projectile.class)
public abstract class AdvancedProjectileMixin {
   @Inject(method = "tick", at = @At("HEAD"))
   private void fortuneandfavors$advancedProjectileTick(CallbackInfo ci) {
      Projectile projectile = (Projectile)(Object)this;
      if (!projectile.level().isClientSide()) {
         AdvancedEnchantments.markProjectileFromOwner(projectile);
         AdvancedEnchantments.tickDeadeyeProjectile(projectile);
      }
   }

   @Inject(method = "onHit", at = @At("HEAD"), cancellable = true)
   private void fortuneandfavors$advancedProjectileHit(HitResult result, CallbackInfo ci) {
      Projectile projectile = (Projectile)(Object)this;
      if (projectile.level().isClientSide()) {
         return;
      }
      if (result instanceof EntityHitResult entityHit) {
         Entity target = entityHit.getEntity();
         if (target instanceof net.minecraft.world.entity.LivingEntity living
            && AdvancedEnchantments.tryParryProjectile(projectile, living)) {
            ci.cancel();
            return;
         }
         if (AdvancedEnchantments.isFlareProjectile(projectile)) {
            AdvancedEnchantments.triggerFlare(projectile, result.getLocation().x, result.getLocation().y, result.getLocation().z);
            ci.cancel();
         }
      } else if (result.getType() == HitResult.Type.BLOCK && AdvancedEnchantments.isFlareProjectile(projectile)) {
         AdvancedEnchantments.triggerFlare(projectile, result.getLocation().x, result.getLocation().y, result.getLocation().z);
         ci.cancel();
      }
   }
}
