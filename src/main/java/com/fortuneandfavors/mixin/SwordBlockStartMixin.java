package com.fortuneandfavors.mixin;

import com.fortuneandfavors.ModItems;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public abstract class SwordBlockStartMixin {
   // When the Distant Memory sword starts its native 1.8 block (blocks_attacks
   // use-item), give it its signature enchant-sparkle VFX. Regular sword blocking
   // (e.g. legacy duels) gets none - that's reserved for the special sword.
   @Inject(method = "startUsingItem", at = @At("HEAD"))
   private void fortuneandfavors$distantBlockVfx(InteractionHand hand, CallbackInfo ci) {
      LivingEntity self = (LivingEntity)(Object)this;
      if (self instanceof Player p && self.level() instanceof ServerLevel sl && ModItems.isDistantMemorySword(p.getItemInHand(hand))) {
         com.fortuneandfavors.net.FfVfx.particles(sl, ParticleTypes.ENCHANT, self.getX(), self.getY() + 1.2, self.getZ(), 10, 0.3, 0.4, 0.3, 0.05);
      }
   }
}
