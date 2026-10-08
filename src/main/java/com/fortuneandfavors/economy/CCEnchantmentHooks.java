package com.fortuneandfavors.economy;

/**
 * Thin forwarding shim so the mixin package doesn't need to know the internals.
 * All CCEnchantment damage-multiplier logic lives in CCEnchantments.
 */
public final class CCEnchantmentHooks {
   private CCEnchantmentHooks() {
   }

   public static float pipeline(
      net.minecraft.world.entity.LivingEntity instance,
      net.minecraft.world.damagesource.DamageSource source,
      float amount,
      com.llamalad7.mixinextras.injector.wrapoperation.Operation<java.lang.Float> original
   ) {
      return CCEnchantments.applyDamageHooks(instance, source, amount, original);
   }
}