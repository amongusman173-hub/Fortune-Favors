package com.fortuneandfavors.mixin;

import com.fortuneandfavors.economy.VanishManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A vanished moderator who lands after a jump leaves a puff of whatever they landed on.
 *
 * <p>This one is not a sound and not a flag, which is why it survived every earlier pass: the
 * landing dust is a <b>packet</b>. {@code ServerPlayer.checkFallDamage} asks its own
 * {@code spawnExtraParticlesOnFall} field, sends a {@code BlockParticleOption(ParticleTypes.BLOCK,
 * state)} through {@code ServerLevel.sendParticles} to every client in range, and only then calls
 * super to apply the fall damage. So everyone standing nearby watches a hidden player arrive on
 * the ground.
 *
 * <p>The fix is vanilla's own gate rather than a redirect of that call: the field is written
 * <i>at the top</i> of the same method the dust is gated on, one line before it is read, and
 * vanilla puts it back by itself (it clears it after each landing). Fall damage is untouched -
 * this is the switch that means "and puff some dust", not the one that means "and hurt".
 */
@Mixin(ServerPlayer.class)
public abstract class VanishLandingMixin {
   @Shadow
   private boolean spawnExtraParticlesOnFall;

   @Inject(method = "checkFallDamage", at = @At("HEAD"))
   private void fortuneandfavors$vanishedLandingLeavesNoDust(
      double heightDifference,
      boolean onGround,
      BlockState state,
      BlockPos pos,
      CallbackInfo ci
   ) {
      try {
         if (VanishManager.isHiddenBody((ServerPlayer)(Object)this)) {
            this.spawnExtraParticlesOnFall = false;
         }
      } catch (Exception ignored) {
      }
   }
}
