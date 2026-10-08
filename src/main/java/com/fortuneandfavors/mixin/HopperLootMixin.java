package com.fortuneandfavors.mixin;

import com.fortuneandfavors.economy.ClaimManager;
import com.fortuneandfavors.economy.MachineManager;
import com.fortuneandfavors.economy.ClaimManager.Claim;
import com.fortuneandfavors.util.Safe;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.Container;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.Hopper;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(HopperBlockEntity.class)
public abstract class HopperLootMixin {
   private static BlockPos fortuneandfavors$sideTarget(Level level, BlockPos pos) {
      if (pos != null && level.getBlockState(pos).getBlock() instanceof HopperBlock) {
         Direction facing = (Direction)level.getBlockState(pos).getValue(HopperBlock.FACING);
         return facing == Direction.DOWN ? null : pos.relative(facing);
      } else {
         return null;
      }
   }

   private static boolean fortuneandfavors$outsiderTouching(Level level, BlockPos hopperPos, BlockPos containerPos) {
      if (level.isClientSide() || hopperPos == null || containerPos == null) {
         return false;
      } else if (!(level.getBlockEntity(containerPos) instanceof Container)) {
         return false;
      } else {
         Claim containerClaim = ClaimManager.claimAt(level, containerPos);
         if (containerClaim != null && !containerClaim.allowChests && !containerClaim.allowBuild) {
            Claim hopperClaim = ClaimManager.claimAt(level, hopperPos);
            return hopperClaim == null || !hopperClaim.owner.equals(containerClaim.owner);
         } else {
            return false;
         }
      }
   }

   @Inject(method = "suckInItems", at = @At("HEAD"), cancellable = true)
   private static void fortuneandfavors$blockClaimSuck(Level level, Hopper hopper, CallbackInfoReturnable<Boolean> cir) {
      if (!level.isClientSide()) {
         Safe.run("hopper suck claim check", () -> {
            BlockPos pos = new BlockPos((int)hopper.getLevelX(), (int)hopper.getLevelY(), (int)hopper.getLevelZ());
            if (!MachineManager.isAutoSellHopper(level, pos)) {
               if (fortuneandfavors$outsiderTouching(level, pos, pos.above())) {
                  cir.setReturnValue(false);
               } else {
                  BlockPos side = fortuneandfavors$sideTarget(level, pos);
                  if (side != null && fortuneandfavors$outsiderTouching(level, pos, side)) {
                     cir.setReturnValue(false);
                  }
               }
            }
         });
      }
   }

   @Inject(method = "ejectItems", at = @At("HEAD"), cancellable = true)
   private static void fortuneandfavors$blockClaimEject(Level level, BlockPos pos, HopperBlockEntity hopper, CallbackInfoReturnable<Boolean> cir) {
      if (!level.isClientSide()) {
         Safe.run("hopper eject claim check", () -> {
            if (fortuneandfavors$outsiderTouching(level, pos, pos.below())) {
               cir.setReturnValue(false);
            } else {
               BlockPos side = fortuneandfavors$sideTarget(level, pos);
               if (side != null && fortuneandfavors$outsiderTouching(level, pos, side)) {
                  cir.setReturnValue(false);
               }
            }
         });
      }
   }
}
