package com.fortuneandfavors.mixin;

import com.fortuneandfavors.ModItems;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Slime Launcher (a slime ball with the "slime_launcher" type tag) keeps its
 * use-duration/animation at the base Item level: the item itself doesn't
 * override these, so the injected hooks run for it. Crossbow-specific hooks
 * (the Revolver) live in CrossbowRevolverMixin instead, because CrossbowItem
 * overrides every one of these methods.
 */
@Mixin(Item.class)
public class ItemMixin {
   @Inject(method = "getUseDuration", at = @At("HEAD"), cancellable = true)
   private void ffLauncherDuration(ItemStack stack, LivingEntity entity, CallbackInfoReturnable<Integer> cir) {
      if (ModItems.isSlimeLauncher(stack)) {
         cir.setReturnValue(7200);
      }
   }

   @Inject(method = "getUseAnimation", at = @At("HEAD"), cancellable = true)
   private void ffLauncherAnim(ItemStack stack, CallbackInfoReturnable<ItemUseAnimation> cir) {
      if (ModItems.isSlimeLauncher(stack)) {
         cir.setReturnValue(ItemUseAnimation.BOW);
      }
   }
}
