package com.fortuneandfavors.mixin;

import com.fortuneandfavors.economy.MachineManager;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(HopperBlockEntity.class)
public abstract class HopperBlockEntityMixin {
   @Inject(method = "pushItemsTick", at = @At("HEAD"), cancellable = true)
   private static void fortuneandfavors_autoSellTick(Level level, BlockPos pos, BlockState state, HopperBlockEntity blockEntity, CallbackInfo ci) {
      if (level.isClientSide()) {
         return;
      }

      if (MachineManager.isAutoSellHopper(level, pos)) {
         MachineManager.tickAutoSell(level, pos);
         ci.cancel();
      } else if (MachineManager.isUpwardsHopper(level, pos)) {
         MachineManager.tickUpwardsHopper(level, pos);
         ci.cancel();
      } else if (MachineManager.isItemSorter(level, pos)) {
         // The sorter is a hopper with a filter: vanilla's own push/pull is cancelled outright and
         // replaced by one that only ever moves what it was told to move. It is silent while it
         // works - see the note at the end of ItemSorter for why.
         com.fortuneandfavors.economy.ItemSorter.tick(level, pos);
         ci.cancel();
      } else if (MachineManager.isTransferHopper(level, pos)) {
         // The Transfer Hopper is the sorter's movement with no filter, plus tag links in and out.
         com.fortuneandfavors.economy.ItemSorter.tickTransfer(level, pos);
         ci.cancel();
      } else if (MachineManager.isOverflowHopper(level, pos)) {
         // The Overflow Hopper is the same machine with its tag moved to the end of the list: it
         // fills what it points at first, and only spills what will not fit. See tickOverflow.
         com.fortuneandfavors.economy.ItemSorter.tickOverflow(level, pos);
         ci.cancel();
      } else if (MachineManager.isSuperHopper(level, pos)) {
         com.fortuneandfavors.economy.HeavyHoppers.tickSuperHopper(level, pos);
         ci.cancel();
      } else if (MachineManager.isTwoWaySplitter(level, pos)) {
         com.fortuneandfavors.economy.HeavyHoppers.tickSplitter(level, pos);
         ci.cancel();
      } else if (MachineManager.isCheckerHopper(level, pos)) {
         com.fortuneandfavors.economy.HeavyHoppers.tickCheckerHopper(level, pos);
         ci.cancel();
      }
   }
}
