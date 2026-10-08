package com.fortuneandfavors.mixin;

import com.fortuneandfavors.economy.CustomEnchantments;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Legacy bows charge 10% faster - full power lands a tick or two sooner, but
 *  the draw animation still plays and tapping still fires a weak shot, so it
 *  is a genuine charge-speed perk instead of instant full-power spam. The
 *  -30% damage tradeoff lives on swords and axes; bows only lose draw time. */
@Mixin(BowItem.class)
public class LegacyBowMixin {
   @WrapOperation(method = "releaseUsing", at = @At(value = "INVOKE", target = "getPowerForTime(I)F"))
   private static float fortuneandfavors$legacyFasterCharge(int charge, Operation<Float> original, ItemStack stack, Level level, LivingEntity shooter, int remaining) {
      if (shooter instanceof Player p && CustomEnchantments.has(p.getMainHandItem(), CustomEnchantments.LEGACY)) {
         // +10% charge speed: at 18 ticks you reach the power of ~20 ticks.
         return original.call((int)Math.min(72000, charge * 1.1));
      }
      return original.call(charge);
   }
}