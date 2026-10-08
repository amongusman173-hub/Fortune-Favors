package com.fortuneandfavors.mixin;

import com.fortuneandfavors.economy.CCEnchantments;
import com.fortuneandfavors.economy.CombatGear;
import com.fortuneandfavors.economy.CustomEnchantments;
import com.fortuneandfavors.economy.VanishManager;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
   // Single damage-pipeline hook: CombatGear (legacy item effects) then
   // CCEnchantments (combo / sharpshooter / hunter's mark / berserker).
   @WrapOperation(
      method = "actuallyHurt",
      at = @At(
         value = "INVOKE",
         target = "Lnet/minecraft/world/entity/LivingEntity;getDamageAfterArmorAbsorb(Lnet/minecraft/world/damagesource/DamageSource;F)F"
      )
   )
   private float fortuneandfavors$ffDamagePipeline(LivingEntity instance, DamageSource source, float amount, Operation<Float> operation) {
      float dmg = CombatGear.apply(instance, source, amount, operation);
      dmg = CCEnchantments.applyDamageHooks(instance, source, dmg, operation);
      // The expedition's own fall answer, last in the chain so it is the final word on a fall and
      // cannot be undone by anything above it. It answers only a fall inside the site, so nothing
      // about the overworld - or a boss's arena - changes here.
      return com.fortuneandfavors.economy.ExpeditionManager.fallDamage(instance, source, dmg);
   }

   /**
    * The locator bar, and the reason a vanished moderator was still a dot on everybody's screen.
    *
    * <p>Vanilla asks this one method - {@code WAYPOINT_TRANSMIT_RANGE} greater than zero, and
    * nothing else - whenever a viewer comes into range of a body ({@code ServerLevel}'s entity
    * callbacks), and a positive answer is what puts a marker on the locator bar. Invisibility is
    * not part of it, so the most careful vanish in the world still broadcast a position. Saying
    * no here is the declarative half of the fix: it is asked on every new tracking pair, so a
    * player who walks into range a minute after the vanish gets nothing. The other half -
    * dropping connections that already exist - is in {@link VanishManager}.
    */
   @Inject(method = "isTransmittingWaypoint", at = @At("RETURN"), cancellable = true)
   private void fortuneandfavors$vanishLeavesTheLocatorBar(CallbackInfoReturnable<Boolean> cir) {
      try {
         if ((Object)this instanceof ServerPlayer sp && VanishManager.isVanished(sp)) {
            cir.setReturnValue(false);
         }
      } catch (Exception ignored) {
      }
   }

   /**
    * The potion cloud, and the last thing that made an invisible body visible.
    *
    * <p>Vanilla hides an entity's effect particles exactly once, when its invisibility flag
    * changes; every effect added afterwards re-syncs the list and the swirl is back. A vanished
    * moderator with a speed potion, or with one of this mod's own scripted invisibility flashes,
    * walks around inside a labelled cloud. Refusing the sync for a body this mod is hiding is the
    * declarative half - the imperative half is {@code VanishManager.apply} emptying the list on
    * every tick, because either one alone leaves a window.
    */
   @Inject(method = "updateSynchronizedMobEffectParticles", at = @At("HEAD"), cancellable = true)
   private void fortuneandfavors$vanishedBodyHasNoEffectCloud(CallbackInfo ci) {
      try {
         if ((Object)this instanceof LivingEntity self && VanishManager.isHiddenBody(self)) {
            ci.cancel();
         }
      } catch (Exception ignored) {
      }
   }

   // Moonwalk: vanilla elytra physics recomputes the glide velocity from look
   // angle + gravity every tick, so a velocity nudge in a tick handler gets
   // overwritten before it can do anything. Cutting the gravity the physics
   // reads is the one hook that survives - reduced descent while gliding.
   // NOTE: getEffectiveGravity() is a no-arg instance method in 26.2 - the
   // handler must not declare the entity as a parameter, use (Object)this.
   @Inject(method = "getEffectiveGravity", at = @At("RETURN"), cancellable = true)
   private void fortuneandfavors$moonwalkGravity(CallbackInfoReturnable<Double> cir) {
      try {
         if ((Object)this instanceof ServerPlayer sp && sp.isFallFlying()) {
            ItemStack chest = sp.getItemBySlot(EquipmentSlot.CHEST);
            int moonwalk = CustomEnchantments.levelOf(chest, CustomEnchantments.MOONWALK);
            if (moonwalk > 0) {
               double g = cir.getReturnValue();
               cir.setReturnValue(g * Math.max(0.35, 1.0 - 0.25 * moonwalk));
            }
         }
      } catch (Exception ignored) {
      }
   }
}