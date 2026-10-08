package com.fortuneandfavors.client.mixin;

import com.fortuneandfavors.ModItems;
import com.fortuneandfavors.client.ScreenFx;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 1.8 sword blocking is the native blocks_attacks "using item" state, which
 * applies the shield-style movement slow (itemUseSpeedMultiplier) and cancels
 * sprinting (isSlowDueToUsingItem). The Distant Memory sword is special: it
 * keeps full movement speed and can sprint while blocking. (Legacy-duel
 * blocking keeps the authentic 1.8 slow.)
 */
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerMixin {
   @Unique
   private static boolean fortuneandfavors$blocking() {
      Minecraft mc = Minecraft.getInstance();
      LocalPlayer player = mc.player;
      if (player == null) {
         return false;
      }
      return ScreenFx.swordBlockActive() || (player.isUsingItem() && ModItems.isDistantMemorySword(player.getMainHandItem()));
   }

   @Inject(method = "itemUseSpeedMultiplier", at = @At("HEAD"), cancellable = true)
   private void fortuneandfavors$noBlockSlow(CallbackInfoReturnable<Float> cir) {
      if (fortuneandfavors$blocking()) {
         cir.setReturnValue(1.0F);
      }
   }

   @Inject(method = "isSlowDueToUsingItem", at = @At("HEAD"), cancellable = true)
   private void fortuneandfavors$canSprintWhileBlocking(CallbackInfoReturnable<Boolean> cir) {
      if (fortuneandfavors$blocking()) {
         cir.setReturnValue(false);
      }
   }
}
