package com.fortuneandfavors.mixin;

import com.fortuneandfavors.economy.ClaimManager;
import com.fortuneandfavors.economy.ClaimManager.Claim;
import com.fortuneandfavors.util.Safe;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PistonBaseBlock.class)
public abstract class PistonBaseBlockMixin {
   @Inject(method = "moveBlocks", at = @At("HEAD"), cancellable = true)
   private void fortuneandfavors$protectClaims(Level level, BlockPos pos, Direction direction, boolean extend, CallbackInfoReturnable<Boolean> cir) {
      if (!level.isClientSide()) {
         Safe.run("piston claim check", () -> {
            BlockPos first = pos.relative(direction, extend ? 1 : 2);
            Claim target = ClaimManager.claimAt(level, first);
            if (target != null && !target.allowBuild) {
               Claim piston = ClaimManager.claimAt(level, pos);
               if (piston == null || !piston.owner.equals(target.owner)) {
                  cir.setReturnValue(false);
               }
            }
         });
      }
   }
}
