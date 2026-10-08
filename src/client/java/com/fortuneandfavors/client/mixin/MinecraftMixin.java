package com.fortuneandfavors.client.mixin;

import com.fortuneandfavors.ModItems;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
   // Vanilla suppresses attacks while using an item (which is what blocking is).
   // Only the Distant Memory sword lets you attack WHILE blocking - hold
   // right-click to block and keep left-clicking to swing. Normal swords and
   // other items keep the vanilla trade-off.
   @Redirect(
      method = "continueAttack",
      at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;isUsingItem()Z")
   )
   private boolean fortuneandfavors$attackWhileBlocking(LocalPlayer player) {
      return player.isUsingItem() && !ModItems.isDistantMemorySword(player.getMainHandItem());
   }
}
