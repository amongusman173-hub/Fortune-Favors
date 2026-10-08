package com.fortuneandfavors.client.mixin;

import com.fortuneandfavors.economy.SwordBlockManager;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Vanilla short-circuits block interactions when the held item carries the
 * blocks_attacks component (sword blocking): right-clicking a chest, door,
 * button, lever, furnace etc. starts blocking instead of using the block, and
 * doors can desync into ghost blocks.
 *
 * Fix: temporarily drop the component during {@code useItemOn} so the block
 * interaction runs first (chests open, doors toggle), then restore it ONLY
 * for items that should legitimately block (Distant Memory / legacy-duel
 * swords). Regular swords must NOT get it back — otherwise swords start

 * blocking after every useItemOn (the bug where a sword blocks after hitting
 * an entity).
 */
@Mixin(MultiPlayerGameMode.class)
public abstract class MultiPlayerGameModeMixin {
   /** True only for the items this mod actually manages block state on: the
    *  Distant Memory sword, any sword, or a weapon carrying Guard. A vanilla
    *  SHIELD also carries {@code blocks_attacks}, and stripping that from it
    *  without restoring it (the restore below only re-adds managed weapons)
    *  left shields unable to block until the stack was refreshed - a real
    *  client/server desync. Shields are never touched now. */
   @Unique
   private static boolean fortuneandfavors$managedBlock(ItemStack stack) {
      return com.fortuneandfavors.ModItems.isDistantMemorySword(stack)
         || stack.is(net.minecraft.tags.ItemTags.SWORDS)
         || com.fortuneandfavors.economy.AdvancedEnchantments.has(stack, com.fortuneandfavors.economy.AdvancedEnchantments.GUARD);
   }

   @Inject(method = "useItemOn", at = @At("HEAD"))
   private void fortuneandfavors$letBlockInteractionsWin(
      LocalPlayer player, InteractionHand hand, BlockHitResult result, CallbackInfoReturnable<InteractionResult> cir
   ) {
      if (player == null) {
         return;
      }
      ItemStack stack = player.getItemInHand(hand);
      if (fortuneandfavors$managedBlock(stack) && stack.has(DataComponents.BLOCKS_ATTACKS)) {
         stack.remove(DataComponents.BLOCKS_ATTACKS);
      }
   }

   @Inject(method = "useItemOn", at = @At("RETURN"))
   private void fortuneandfavors$restoreSwordBlocking(
      LocalPlayer player, InteractionHand hand, BlockHitResult result, CallbackInfoReturnable<InteractionResult> cir
   ) {
      if (player == null) {
         return;
      }
      ItemStack stack = player.getItemInHand(hand);
      // Only re-add blocks_attacks if the item is SUPPOSED to block: the
      // Distant Memory sword (native), a sword during a legacy 1.8 duel
      // (DuelManager syncs the component server-side), or any weapon carrying
      // the Guard enchantment (the server re-applies it every tick). Vanilla
      // swords stay non-blocking, fixing the block-after-hit bug.
      if (com.fortuneandfavors.ModItems.isDistantMemorySword(stack)
         || (stack.is(net.minecraft.tags.ItemTags.SWORDS) && com.fortuneandfavors.duel.DuelManager.isLegacyFight(player))
         || com.fortuneandfavors.economy.AdvancedEnchantments.has(stack, com.fortuneandfavors.economy.AdvancedEnchantments.GUARD)) {
         if (!stack.has(DataComponents.BLOCKS_ATTACKS)) {
            stack.set(DataComponents.BLOCKS_ATTACKS, SwordBlockManager.blockComponent());
         }
      }
   }
}
