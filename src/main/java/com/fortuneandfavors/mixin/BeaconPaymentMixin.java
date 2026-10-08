package com.fortuneandfavors.mixin;

import com.fortuneandfavors.util.Safe;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Wither skeleton skulls are the King-offering currency now - they can no
 *  longer be burned as beacon payment. Any other payment item is untouched.
 *  The payment slot is a private inner class of BeaconMenu, so it is targeted
 *  by runtime name rather than a class reference. */
@Mixin(targets = "net.minecraft.world.inventory.BeaconMenu$PaymentSlot")
public abstract class BeaconPaymentMixin {
   @Inject(method = "mayPlace", at = @At("HEAD"), cancellable = true)
   private void fortuneandfavors$noSkullPayment(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
      if (stack != null && !stack.isEmpty()) {
         Safe.run("beacon payment gate", () -> {
            if (stack.is(net.minecraft.world.item.Items.WITHER_SKELETON_SKULL)) {
               cir.setReturnValue(Boolean.FALSE);
            }
         });
      }
   }
}
