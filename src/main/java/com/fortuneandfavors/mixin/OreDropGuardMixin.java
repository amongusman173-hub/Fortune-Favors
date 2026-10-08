package com.fortuneandfavors.mixin;

import com.fortuneandfavors.economy.ExpeditionManager;
import com.fortuneandfavors.util.Safe;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * What a break inside a site is allowed to leave behind.
 *
 * <p>Two rules, both about the same site, both answered by {@code ExpeditionManager.swallowsBreak}:
 *
 * <p>An <b>ore</b> pays in coin, not in kind. The maze's own loot is a ledger: every chest, bounty
 * and ore writes a number onto the run, and the number is what extraction is worth. An ore that
 * also handed over its item was two rewards for one swing - and the second one was the one that
 * mattered, because a stack of diamonds in your pocket is worth far more than the ledger credited
 * you for it and it survives the trip home whether or not the run does. Instant diamonds made
 * every chest in the site a formality. The ore still breaks, still pays
 * {@code ExpeditionManager.onBlockBroken}'s value onto the run, and leaves nothing behind.
 *
 * <p><b>Gravel</b> drops only once the site has started coming down. The site's gravel is its
 * ceiling - the cracked roof of an unstable chamber and the rubble that roof sheds - and mining the
 * hazard out for a stack of blocks was an income the run's ledger never priced. The collapse is the
 * exception and deliberately so: that is the site's last act, the rubble is what it leaves, and a
 * run that ended in the roof coming down is exactly the run that has nothing else to show for it.
 *
 * <p>{@code Block.dropResources} is the funnel every player break's drops go through, and it is the
 * only one of them that is handed the entity that did the breaking. So both rules live here, keyed
 * on the breaker rather than on the block's position.
 *
 * <p>Nothing else is touched. A player outside a site breaks ore and gravel exactly as they always
 * did, a dungeon's own rock still drops, and an explosion inside a site is not this rule's business
 * - it was never a player's break and it never paid the ledger.
 */
@Mixin(Block.class)
public abstract class OreDropGuardMixin {
   @Inject(
      method = "dropResources(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/entity/BlockEntity;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/item/ItemStack;)V",
      at = @At("HEAD"),
      cancellable = true
   )
   private static void fortuneandfavors$expeditionBreakPaysTheRun(
      BlockState state,
      Level level,
      BlockPos pos,
      BlockEntity blockEntity,
      Entity entity,
      ItemStack tool,
      CallbackInfo ci
   ) {
      Safe.run("expedition break drops", () -> {
         if (!(entity instanceof ServerPlayer player) || player.level() != level) {
            return;
         }
         if (ExpeditionManager.swallowsBreak(player.getUUID(), state)) {
            ci.cancel();
         }
      });
   }
}
