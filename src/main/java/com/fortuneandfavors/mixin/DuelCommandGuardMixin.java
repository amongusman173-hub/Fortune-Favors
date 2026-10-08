package com.fortuneandfavors.mixin;

import com.fortuneandfavors.duel.DuelManager;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
public class DuelCommandGuardMixin {
   @Shadow
   public ServerPlayer player;

   /**
    * A duel keeps the same private door policy as the block - see
    * {@link PrisonCommandGuardMixin} for why this hangs off {@code tryHandleChat} rather than
    * {@code handleChatCommand}: a client with a chat session sends the signed command packet, and
    * the unsigned handler alone was a guard on one of two doors.
    */
   @Inject(method = "tryHandleChat", at = @At("HEAD"), cancellable = true)
   private void fortuneandfavors$blockCommandsInDuel(String message, boolean isCommand, Runnable dispatcher, CallbackInfo ci) {
      if (!isCommand) {
         return;
      }
      ServerPlayer p = this.player;
      if (p == null || p.level().getServer() == null) {
         return;
      }
      if (DuelManager.isInDuel(p.getUUID()) && !DuelManager.allowedCommand(message)) {
         ci.cancel();
         p.sendSystemMessage(Component.literal("§cYou're in a duel - only §f/duel vote§c, §f/duel quit§c and §f/cancel§c are allowed while it runs."));
      }
   }
}
