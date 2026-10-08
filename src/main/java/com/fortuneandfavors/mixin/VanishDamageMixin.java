package com.fortuneandfavors.mixin;

import com.fortuneandfavors.economy.VanishManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A vanished player takes zero damage from everything - player attacks, mobs,
 *  fall, fire, void, explosions, TNT - so they stay fully safe while hidden. */
@Mixin(LivingEntity.class)
public abstract class VanishDamageMixin {
   @Inject(method = "hurtServer", at = @At("HEAD"), cancellable = true)
   private void fortuneandfavors$vanishImmunity(ServerLevel level, DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
      if ((Object) this instanceof ServerPlayer p && VanishManager.isVanished(p)) {
         cir.setReturnValue(false);
      }
   }
}