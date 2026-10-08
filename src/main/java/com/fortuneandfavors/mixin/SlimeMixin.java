package com.fortuneandfavors.mixin;

import com.fortuneandfavors.economy.BossManager;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.cubemob.Slime;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Slime.class)
public abstract class SlimeMixin {
   @Inject(method = "setSize", at = @At("TAIL"))
   private void fortuneandfavors$capBossTouchDamage(int size, boolean resetHealth, CallbackInfo ci) {
      Slime self = ((Slime)(Object)this);
      if (BossManager.isFortuneSlime(self)) {
         AttributeInstance atk = self.getAttribute(Attributes.ATTACK_DAMAGE);
         if (atk != null) {
            atk.setBaseValue(Math.min(3.0, size));
         }

         AttributeInstance follow = self.getAttribute(Attributes.FOLLOW_RANGE);
         if (follow != null) {
            follow.setBaseValue(64.0);
         }
      }
   }
}
