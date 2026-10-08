package com.fortuneandfavors.mixin;

import com.fortuneandfavors.economy.SpawnerManager;
import com.fortuneandfavors.util.Safe;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SpawnerBlockEntity.class)
public abstract class SpawnerBlockEntityMixin {
   @Inject(method = "serverTick", at = @At("HEAD"), cancellable = true)
   private static void fortuneandfavors$itemGainNoSpawn(Level level, BlockPos pos, BlockState state, SpawnerBlockEntity blockEntity, CallbackInfo ci) {
      if (!level.isClientSide()) {
         Safe.run("spawner mode check", () -> {
            if (SpawnerManager.isItemGain(level, pos)) {
               ci.cancel();
            }
         });
      }
   }
}
