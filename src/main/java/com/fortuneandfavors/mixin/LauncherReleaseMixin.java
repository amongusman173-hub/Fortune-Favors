package com.fortuneandfavors.mixin;

import com.fortuneandfavors.economy.BossManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public class LauncherReleaseMixin {
   @Inject(method = "releaseUsingItem", at = @At("HEAD"))
   private void ffLauncherRelease(CallbackInfo ci) {
      LivingEntity self = ((LivingEntity)(Object)this);
      if (self instanceof ServerPlayer sp) {
         BossManager.releaseLauncher(sp);
         BossManager.releaseFrostbow(sp);
      }
   }
}
