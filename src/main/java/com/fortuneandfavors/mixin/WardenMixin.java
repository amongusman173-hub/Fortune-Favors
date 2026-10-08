package com.fortuneandfavors.mixin;

import com.fortuneandfavors.economy.BossManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Unit;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Warden.class)
public abstract class WardenMixin {
   @Inject(method = "applyDarknessAround", at = @At("HEAD"), cancellable = true)
   private static void fortuneandfavors$noBossDarkness(ServerLevel level, Vec3 pos, Entity entity, int radius, CallbackInfo ci) {
      try {
         if (BossManager.isElderWarden(entity)) {
            ci.cancel();
         }
      } catch (Exception var6) {
      }
   }

   @Inject(method = "isDiggingOrEmerging", at = @At("HEAD"), cancellable = true)
   private void fortuneandfavors$preventDigging(CallbackInfoReturnable<Boolean> cir) {
      try {
         if (BossManager.isElderWarden(((Entity)(Object)this))) {
            cir.setReturnValue(false);
         }
      } catch (Exception var3) {
      }
   }

   @Inject(method = "customServerAiStep", at = @At("HEAD"))
   private void fortuneandfavors$neverDig(ServerLevel level, CallbackInfo ci) {
      try {
         Warden self = ((Warden)(Object)this);
         if (BossManager.isElderWarden(self)) {
            self.getBrain().setMemoryWithExpiry(MemoryModuleType.DIG_COOLDOWN, Unit.INSTANCE, 200L);
         }
      } catch (Exception var4) {
      }
   }
}
