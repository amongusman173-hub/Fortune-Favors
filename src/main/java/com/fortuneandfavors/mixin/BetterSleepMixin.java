package com.fortuneandfavors.mixin;

import net.minecraft.server.players.SleepStatus;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SleepStatus.class)
public abstract class BetterSleepMixin {
   @Shadow
   private int activePlayers;

   // Only 50% of online players need to sleep to skip the night (rounds up, minimum 1).
   // The vanilla default gamerule is 100%, but we lower it only when the server left it at the
   // default - an admin who explicitly changed playersSleepingPercentage keeps their value.
   @Inject(method = "sleepersNeeded", at = @At("RETURN"), cancellable = true)
   private void fortuneandfavors$halfSleepersNeeded(int percentage, CallbackInfoReturnable<Integer> cir) {
      if (percentage >= 100) {
         cir.setReturnValue(Math.max(1, Mth.ceil(this.activePlayers * 50.0F / 100.0F)));
      }
   }

}
