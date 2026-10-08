package com.fortuneandfavors.mixin;

import com.fortuneandfavors.ModItems;
import com.fortuneandfavors.duel.DuelManager;
import com.fortuneandfavors.economy.MachineManager;
import com.fortuneandfavors.economy.SpawnerManager;
import com.fortuneandfavors.economy.NaturalBlocks;
import com.fortuneandfavors.menu.BackpackMenu;
import com.fortuneandfavors.util.Safe;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Block.class)
public abstract class BlockSetPlacedByMixin {
   @Inject(method = "setPlacedBy", at = @At("TAIL"))
   private void fortuneandfavors_onPlaced(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack, CallbackInfo ci) {
      Safe.run("block placed registration", () -> {
         if (placer instanceof ServerPlayer sp && ModItems.isBackpack(stack)) {
            BackpackMenu.confirmPlaced(sp, pos);
         }

         MachineManager.onBlockPlaced(level, pos, placer, stack);
         SpawnerManager.onPlaced(level, pos, placer, stack);
         if (level instanceof ServerLevel sl) {
            // One placement ledger, shared by every perk that must not pay out on a
            // block a player placed (excavator, mining pity, the mining zones). It is
            // durable across restarts, which is what makes it a rule rather than a
            // thirty-minute delay.
            NaturalBlocks.onBlockPlaced(sl, pos);
            DuelManager.onPlayerBlockPlaced(sl, pos, placer);
            if (level.getBlockState(pos).getBlock() == Blocks.TNT) {
               DuelManager.onTntPlaced(sl, pos, placer);
            }
         }
      });
   }
}
