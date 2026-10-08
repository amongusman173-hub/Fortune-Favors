package com.fortuneandfavors.mixin;

import com.fortuneandfavors.economy.ExplosionRebuildManager;
import com.fortuneandfavors.util.Safe;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FallingBlock.class)
public abstract class FallingBlockMixin {
   @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
   private void fortuneandfavors$holdSandOverRebuild(BlockState state, ServerLevel level, BlockPos pos, RandomSource random, CallbackInfo ci) {
      Safe.run("rebuild sand freeze", () -> {
         if (ExplosionRebuildManager.isRebuildFrozen(pos)) {
            if (FallingBlock.isFree(level.getBlockState(pos.below())) && pos.getY() >= level.getMinY()) {
               level.scheduleTick(pos, state.getBlock(), 8);
               ci.cancel();
            }
         }
      });
   }
}
