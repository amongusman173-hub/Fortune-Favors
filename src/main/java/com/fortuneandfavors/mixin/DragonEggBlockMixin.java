package com.fortuneandfavors.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DragonEggBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Prevents the dragon egg teleport-on-use exploit. Vanilla DragonEggBlock
 * teleports the egg to a random nearby position when right-clicked, which
 * can be abused to duplicate the block. This mixin cancels the teleport
 * so the egg stays put and behaves like a normal block.
 */
@Mixin(DragonEggBlock.class)
public abstract class DragonEggBlockMixin {

   @Inject(method = "teleport", at = @At("HEAD"), cancellable = true)
   private void fortuneandfavors$noDragonEggTeleport(BlockState state, Level level, BlockPos pos, CallbackInfo ci) {
      // Cancel the vanilla teleport that dragon eggs do on interaction
      ci.cancel();
   }
}
