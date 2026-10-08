package com.fortuneandfavors.mixin;

import com.fortuneandfavors.economy.TagManager;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Player.class)
public abstract class PlayerDisplayNameMixin {
   @Inject(method = "getDisplayName", at = @At("RETURN"), cancellable = true)
   private void fortuneandfavors$appendTag(CallbackInfoReturnable<Component> cir) {
      if (((Object)this) instanceof ServerPlayer serverPlayer) {
         Component original = (Component)cir.getReturnValue();
         if (original != null) {
            Component withTag = TagManager.appendTag(original, serverPlayer);
            if (withTag != original) {
               cir.setReturnValue(withTag);
            }
         }
      }
   }
}
