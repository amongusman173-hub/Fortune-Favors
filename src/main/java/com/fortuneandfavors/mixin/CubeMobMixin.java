package com.fortuneandfavors.mixin;

import com.fortuneandfavors.economy.BossManager;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.monster.cubemob.AbstractCubeMob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(AbstractCubeMob.class)
public abstract class CubeMobMixin {
   @WrapOperation(method = "remove", at = @At(value = "INVOKE", target = "isDeadOrDying()Z"))
   private boolean fortuneandfavors$noBossSplit(AbstractCubeMob slime, Operation<Boolean> original) {
      return BossManager.isFortuneSlime(slime) ? false : (Boolean)original.call(new Object[]{slime});
   }
}
