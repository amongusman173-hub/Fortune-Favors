package com.fortuneandfavors.mixin;

import com.fortuneandfavors.ModItems;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BowItem.class)
public class FrostbowReleaseMixin {
   @Inject(method = "releaseUsing", at = @At("HEAD"), cancellable = true)
   private void ffFrostbowNoVanillaArrow(ItemStack stack, Level level, LivingEntity user, int remaining, CallbackInfoReturnable<Boolean> cir) {
      if (ModItems.isFrostboundCrown(stack)) {
         cir.setReturnValue(true);
      }
   }
}
