package com.fortuneandfavors.mixin;

import com.fortuneandfavors.economy.RaidGearManager;
import com.fortuneandfavors.economy.VanishManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Mobs never adopt a vanished player as a target, so nothing in the world
 *  chases or stares at you while you're hidden. */
@Mixin(Mob.class)
public abstract class VanishMobTargetMixin {
   @Inject(method = "setTarget", at = @At("HEAD"), cancellable = true)
   private void fortuneandfavors$noVanishedTarget(LivingEntity target, CallbackInfo ci) {
      if (target instanceof ServerPlayer p && VanishManager.isVanished(p)) {
         ci.cancel();
         return;
      }
      // The Illusioner's true invisibility hides its bearer the same way: no mob
      // may adopt them as a target, so the spell does not fall apart the moment
      // they are wearing armour. Vanilla invisibility only shortens mob sight
      // range, and armour widens it right back up.
      if (target instanceof ServerPlayer hidden && RaidGearManager.isTrulyHidden(hidden)) {
         ci.cancel();
      }
   }
}