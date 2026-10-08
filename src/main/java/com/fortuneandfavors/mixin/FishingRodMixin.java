package com.fortuneandfavors.mixin;

import com.fortuneandfavors.duel.DuelManager;
import com.fortuneandfavors.economy.CustomEnchantments;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(FishingHook.class)
public class FishingRodMixin {
   /**
    * Prison fishing pond payout. {@code PrisonManager.onFishCatch} existed but was
    * never called from anywhere, so catches at the pond silently paid nothing.
    * {@code retrieve} is invoked once per reel-in with the caught stack; an empty
    * stack means nothing was caught, so only non-empty catches are rewarded.
    */
   @Inject(method = "retrieve", at = @At("HEAD"))
   private void fortuneandfavors$prisonCatch(ItemStack caught, CallbackInfoReturnable<Integer> cir) {
      if (caught == null || caught.isEmpty()) {
         return;
      }
      Entity owner = ((FishingHook)(Object)this).getOwner();
      if (owner instanceof ServerPlayer sp) {
         if (com.fortuneandfavors.economy.PrisonManager.isInPrison(sp)) {
            com.fortuneandfavors.economy.PrisonManager.onFishCatch(sp);
         }

         // The fishing tree's one entry point. It is here rather than in a block-break handler
         // because a catch is the only thing a fishing session produces, and because the stack
         // handed to this method is the catch itself - so the perks can pay out around exactly
         // what the player actually pulled up instead of rolling their own fish beside it.
         com.fortuneandfavors.economy.SkillManager.onFishCaught(sp, caught);
      }
   }

   @Inject(method = "onHitEntity", at = @At("HEAD"), cancellable = true)
   private void fortuneandfavors$legacyRodKnockback(EntityHitResult hitResult, CallbackInfo ci) {
      FishingHook hook = ((FishingHook)(Object)this);
      Entity owner = hook.getOwner();
      Entity target = hitResult.getEntity();
      if (owner != null && target != null && legacyRod(owner)) {
         ci.cancel();
         double dx = target.getX() - owner.getX();
         double dz = target.getZ() - owner.getZ();
         double dist = Math.max(0.01, Math.sqrt(dx * dx + dz * dz));
         double power = 1.2;
         target.push(dx / dist * power, 0.35, dz / dist * power);
         target.hurtMarked = true;
         if (owner.level() instanceof ServerLevel sl) {
            if (target instanceof LivingEntity le) {
               DamageSource src = owner instanceof Player pl ? sl.damageSources().playerAttack(pl) : sl.damageSources().mobAttack(le);
               le.hurtServer(sl, src, 1.1E-5F);
            }

            sl.sendParticles(ParticleTypes.CRIT, target.getX(), target.getY() + 1.0, target.getZ(), 8, 0.3, 0.4, 0.3, 0.1);
            sl.playSound(null, target.getX(), target.getY(), target.getZ(), SoundEvents.PLAYER_ATTACK_STRONG, SoundSource.PLAYERS, 1.0F, 1.2F);
         }
      }
   }

   @Inject(method = "tick", at = @At("HEAD"))
   private void fortuneandfavors$speedUpBobber(CallbackInfo ci) {
      FishingHook hook = ((FishingHook)(Object)this);
      Entity owner = hook.getOwner();
      if (owner != null && legacyRod(owner)) {
         if (!hook.isInWater() && !hook.onGround() && !hook.isPassenger()) {
            Vec3 vel = hook.getDeltaMovement();
            double speed = vel.length();
            if (!(speed < 0.05)) {
               hook.setDeltaMovement(vel.scale(1.35));
               hook.hurtMarked = true;
            }
         }
      }
   }

   /** Legacy rod in hand: 1.8-style fast bobber + knockback on hit. */
   private static boolean legacyRod(Entity owner) {
      if (DuelManager.isLegacyFight(owner)) {
         return true;
      }
      return owner instanceof Player p && CustomEnchantments.has(p.getMainHandItem(), CustomEnchantments.LEGACY);
   }
}
