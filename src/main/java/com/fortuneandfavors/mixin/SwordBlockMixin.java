package com.fortuneandfavors.mixin;

import com.fortuneandfavors.ModItems;
import com.fortuneandfavors.duel.DuelManager;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Prevents vanilla (non-mod) swords from using the native {@code blocks_attacks}
 * blocking behaviour. Only the Distant Memory sword may block. Mod swords with
 * right-click abilities (Wither Cloak Sword, Golem Fist, etc.) are handled by
 * their own event handlers which fire before this mixin can cancel.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public class SwordBlockMixin {
   @Shadow
   public ServerPlayer player;

   @Inject(method = "handleUseItem", at = @At("HEAD"), cancellable = true)
   private void ffSwordBlock(ServerboundUseItemPacket packet, CallbackInfo ci) {
      ItemStack held = this.player.getItemInHand(packet.getHand());
      if (ModItems.isDistantMemorySword(this.player.getMainHandItem()) && held.is(Items.SHIELD)) {
         ci.cancel();
      } else if (DuelManager.tryFeatherBoost(this.player, held)) {
         ci.cancel();
      } else if (held.is(ItemTags.SWORDS) && !ModItems.isDistantMemorySword(held)
         && ModItems.typeOf(held) == null
         && !com.fortuneandfavors.economy.AdvancedEnchantments.has(held, com.fortuneandfavors.economy.AdvancedEnchantments.GUARD)) {
         // Prevent vanilla swords from blocking — only the Distant Memory sword blocks.
         ci.cancel();
      }
   }

   // NOTE: handleUseItemOn is ONLY sent by the client after a real block
   // interaction succeeded (chest opened, door toggled, button pressed). It must
   // never be cancelled for swords - that is what caused doors to ghost-block
   // and chests to refuse to open while holding a blocking sword. The client
   // (MultiPlayerGameModeMixin) already lets block interactions win over
   // starting to block, so only the shield-hold and feather-boost cases are
   // handled here.
   @Inject(method = "handleUseItemOn", at = @At("HEAD"), cancellable = true)
   private void ffSwordBlockOnBlock(ServerboundUseItemOnPacket packet, CallbackInfo ci) {
      ItemStack held = this.player.getItemInHand(packet.getHand());
      if (ModItems.isDistantMemorySword(this.player.getMainHandItem()) && held.is(Items.SHIELD)) {
         ci.cancel();
      } else if (DuelManager.tryFeatherBoost(this.player, held)) {
         ci.cancel();
      }
   }
}
