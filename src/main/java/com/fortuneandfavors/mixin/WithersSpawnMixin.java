package com.fortuneandfavors.mixin;

import com.fortuneandfavors.util.Safe;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hands every server-side WitherBoss to the rework manager the moment the
 *  vanilla spawn sequence starts ticking - that is the one point where the
 *  mod can claim the entity before it moves or attacks. */
@Mixin(WitherBoss.class)
public abstract class WithersSpawnMixin {
   @Inject(method = "customServerAiStep", at = @At("HEAD"))
   private void fortuneandfavors$claimSpawn(CallbackInfo ci) {
      if ((Object)this instanceof WitherBoss wither && wither.level() instanceof ServerLevel) {
         Safe.run("wither rework claim", () -> com.fortuneandfavors.economy.WitherReworkManager.onWitherSpawn(wither));
      }
   }
}
