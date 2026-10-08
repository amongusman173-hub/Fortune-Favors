package com.fortuneandfavors.mixin;

import com.fortuneandfavors.economy.CCEnchantments;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Arrow-level enchant hooks:
 *   - Sniper: gravity = 0, velocity never drops → arrows fly straight forever.
 *   - Heavy Bolt: extra knockback on hit.
 *   - The sniper flag is stamped onto the arrow at fire time (see CrossbowMixin
 *     helper); we only read it here.
 */
@Mixin(AbstractArrow.class)
public abstract class ArrowEnchantMixin {
   // Straight arrows (Sniper + Velocity) fly with no gravity and no speed
   // falloff. The weapon survives the whole flight (firedFromWeapon is set in
   // the constructor and read by getWeaponItem(); there is no setWeaponItem
   // setter in these mappings), and AdvancedEnchantments also stamps
   // ff_no_falloff onto the arrow so the behavior holds even if the weapon is
   // somehow dropped. Sniper-only arrows additionally keep a legacy ff_sniper_arrow
   // flag for older stacks.
   private boolean straightArrow() {
      AbstractArrow self = (AbstractArrow)(Object)this;
      ItemStack weapon = self.getWeaponItem();
      if (CCEnchantments.isSniperCrossbow(weapon) || com.fortuneandfavors.economy.CustomEnchantments.levelOf(weapon, com.fortuneandfavors.economy.CustomEnchantments.VELOCITY) > 0) {
         return true;
      }
      CustomData data = self.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
      if (data != null) {
         net.minecraft.nbt.CompoundTag tag = data.copyTag();
         return tag.getBoolean("ff_sniper_arrow").orElse(false) || tag.getBoolean("ff_no_falloff").orElse(false);
      }
      return false;
   }

   // No gravity for Sniper / Velocity arrows.
   @Inject(method = "getDefaultGravity", at = @At("HEAD"), cancellable = true)
   private void fortuneandfavors$sniperGravity(CallbackInfoReturnable<Double> cir) {
      if (straightArrow()) {
         cir.setReturnValue(0.0);
      }
   }

   // No velocity falloff — AbstractArrow multiplies deltaMovement by this
   // factor each tick; 1.0 = speed stays constant.
   @Inject(method = "getAirDrag", at = @At("HEAD"), cancellable = true)
   private void fortuneandfavors$sniperAirDrag(CallbackInfoReturnable<Float> cir) {
      if (straightArrow()) {
         cir.setReturnValue(1.0F);
      }
   }

   // Heavy Bolt: more knockback (the base arrow knockback is small).
   @Inject(method = "doKnockback", at = @At("HEAD"))
   private void fortuneandfavors$heavyBolt(LivingEntity target, DamageSource source, CallbackInfo ci) {
      AbstractArrow self = (AbstractArrow)(Object)this;
      ItemStack weapon = self.getWeaponItem();
      if (weapon == null || weapon.isEmpty()) {
         return;
      }
      int heavy = CCEnchantments.levelOf(weapon, CCEnchantments.HEAVY_BOLT);
      if (heavy > 0 && target != null && !target.level().isClientSide()) {
         // Knock back along the arrow's travel direction, scaled by level.
         Vec3 dir = self.getDeltaMovement().normalize();
         float power = 0.3F + heavy * 0.25F;
         target.setDeltaMovement(target.getDeltaMovement().add(dir.x * power, 0.15 + heavy * 0.05, dir.z * power));
         target.hurtMarked = true;
      }
   }
}