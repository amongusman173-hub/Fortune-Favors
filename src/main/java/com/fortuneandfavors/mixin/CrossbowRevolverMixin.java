package com.fortuneandfavors.mixin;

import com.fortuneandfavors.economy.CCEnchantments;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Revolver (crossbow): the reload is a 6x-slower charge. In this MC version the
 * bolt is loaded when the use time reaches {@code getChargeDuration} ticks, so
 * that is the value we scale (Quick Charge still cuts into it, via
 * CCEnchantments.revolverChargeTicks). The fan-fire shots themselves are
 * instant: after each trigger pull CrossbowMixin re-arms the next bolt, and
 * vanilla fires a loaded crossbow on use.
 */
@Mixin(CrossbowItem.class)
public abstract class CrossbowRevolverMixin {
   @Inject(method = "getChargeDuration", at = @At("HEAD"), cancellable = true)
   private static void ffRevolverChargeDuration(ItemStack stack, LivingEntity entity, CallbackInfoReturnable<Integer> cir) {
      if (CCEnchantments.isRevolverCrossbow(stack) || CCEnchantments.hasCustomQuickCharge(stack)) {
         cir.setReturnValue(CCEnchantments.revolverChargeTicks(stack, entity));
      }
   }
}
