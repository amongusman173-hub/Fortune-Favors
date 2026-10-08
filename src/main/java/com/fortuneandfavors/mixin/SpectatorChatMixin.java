package com.fortuneandfavors.mixin;

import com.fortuneandfavors.duel.DuelManager;
import net.minecraft.network.protocol.game.ServerboundChatPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
public class SpectatorChatMixin {
   @Shadow
   public ServerPlayer player;

   @Inject(method = "handleChat", at = @At("HEAD"), cancellable = true)
   private void ffSpectatorBetChat(ServerboundChatPacket packet, CallbackInfo ci) {
      if (this.player != null && this.player.level().getServer() != null) {
         String content = packet.message();
         if (content != null && !content.isBlank()) {
            if (DuelManager.handleSpectatorBetInput(this.player, content)) {
               ci.cancel();
            }
         }
      }
   }
}
