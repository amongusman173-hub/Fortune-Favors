package com.fortuneandfavors.mixin;

import com.fortuneandfavors.duel.DuelManager;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
public class ReleaseUseItemMixin {
   @Shadow
   public ServerPlayer player;

   @Inject(method = "handlePlayerAction", at = @At("HEAD"))
   private void ffReleaseSwordBlock(ServerboundPlayerActionPacket packet, CallbackInfo ci) {
      if (packet.getAction() == Action.RELEASE_USE_ITEM) {
         DuelManager.clearSwordBlock(this.player);
      }
   }
}
