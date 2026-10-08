package com.fortuneandfavors.client.mixin;

import com.fortuneandfavors.client.ScreenFx;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ItemInHandRenderer.class)
public class ItemInHandRendererMixin {
   @Unique
   private static boolean fortuneandfavors$blocking() {
      return ScreenFx.swordBlockActive();
   }

   @Redirect(
      method = "submitArmWithItem",
      at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemStack;getUseAnimation()Lnet/minecraft/world/item/ItemUseAnimation;")
   )
   private ItemUseAnimation fortuneandfavors$blockPoseAnimation(ItemStack stack) {
      return fortuneandfavors$blocking() ? ItemUseAnimation.BLOCK : stack.getUseAnimation();
   }

   @Redirect(method = "submitArmWithItem", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/AbstractClientPlayer;isUsingItem()Z"))
   private boolean fortuneandfavors$blockForcesUsing(AbstractClientPlayer player) {
      return fortuneandfavors$blocking() || player.isUsingItem();
   }

   @Redirect(
      method = "submitArmWithItem",
      at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/AbstractClientPlayer;getUseItemRemainingTicks()I")
   )
   private int fortuneandfavors$blockForcesRemaining(AbstractClientPlayer player) {
      return fortuneandfavors$blocking() ? 72000 : player.getUseItemRemainingTicks();
   }

   @Redirect(
      method = "submitArmWithItem",
      at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/AbstractClientPlayer;getUsedItemHand()Lnet/minecraft/world/InteractionHand;")
   )
   private InteractionHand fortuneandfavors$blockForcesHand(AbstractClientPlayer player) {
      return fortuneandfavors$blocking() ? InteractionHand.MAIN_HAND : player.getUsedItemHand();
   }
}
