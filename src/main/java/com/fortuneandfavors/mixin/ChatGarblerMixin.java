package com.fortuneandfavors.mixin;

import com.fortuneandfavors.economy.BossManager;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundChatPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
public class ChatGarblerMixin {
   @Shadow
   public ServerPlayer player;

   @Inject(method = "handleChat", at = @At("HEAD"), cancellable = true)
   private void ffBreakCorruptedChat(ServerboundChatPacket packet, CallbackInfo ci) {
      if (this.player.level().getServer() == null || !this.player.level().getServer().enforceSecureProfile()) {
         // Typing a chat message proves you're at the keyboard - clears AFK.
         com.fortuneandfavors.economy.AfkManager.noteActivity(this.player);
         if (com.fortuneandfavors.economy.VanishManager.isVanished(this.player)) {
            ci.cancel();
            this.player.sendSystemMessage(Component.literal("§7(You're vanished - your chat stays offline.)"));
            return;
         }
         int stage = BossManager.corruptionOf(this.player.getUUID());
         if (stage > 0) {
            ci.cancel();
            this.player.sendSystemMessage(Component.literal("§cYour words shatter before they leave you - §5the Mindbinder§c owns your voice."));
            return;
         }

         // Manners, at the only moment they are worth anything: five seconds after a duel,
         // while the rematch screen is up. Needs the raw message, which only exists here -
         // by the time chat reaches the server it is a decorated component, and "gg" with
         // a rank prefix in front of it would stop being a two-letter word.
         com.fortuneandfavors.duel.DuelManager.noteRematchChat(this.player, packet.message());
      }
   }
}
