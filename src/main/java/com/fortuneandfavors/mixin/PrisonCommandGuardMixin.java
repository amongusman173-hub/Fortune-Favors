package com.fortuneandfavors.mixin;

import com.fortuneandfavors.economy.PrisonManager;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
public class PrisonCommandGuardMixin {
   @Shadow
   public ServerPlayer player;

   /**
    * The block's door policy, asked of the prison rather than spelled out here.
    *
    * <p>The list itself is {@link PrisonManager#commandAllowedInPrison} - a prisoner keeps the jobs
    * they came for and the mod's own console, and everything else is the menu's. It is read from
    * there so the rule can be asked directly, which is how "no commands in prison" stopped being a
    * rule nobody could test without a client. The refusal sentence is
    * {@link PrisonManager#commandRefusal}, for the same reason.
    *
    * <p>This hangs off {@code tryHandleChat}, not off {@code handleChatCommand}, and that is the
    * whole point. A typed command arrives as one of two packets - {@code ServerboundChatCommand}
    * when the client has no chat session, {@code ServerboundChatCommandSigned} when it has one - and
    * both funnel through this one private method, message and an {@code isCommand} flag in hand. A
    * client in online mode (or with a chat session at all) sends the signed spelling, so a guard on
    * the unsigned handler alone was a lock on one of two doors: {@code /shop} still opened for
    * everyone whose client signs its commands. Guarding here also matters for the signed path
    * specifically - vanilla applies the packet's last-seen-messages update at the top of
    * {@code handleSignedChatCommand}, before it reaches this point, so cancelling here leaves the
    * client's message chain intact, where cancelling the packet outright would have quietly broken
    * its chat.
    */
   @Inject(method = "tryHandleChat", at = @At("HEAD"), cancellable = true)
   private void fortuneandfavors$blockCommandsInPrison(String message, boolean isCommand, Runnable dispatcher, CallbackInfo ci) {
      if (!isCommand) {
         return;
      }
      ServerPlayer p = this.player;
      if (p == null || p.level().getServer() == null) {
         return;
      }
      String refusal = PrisonManager.commandRefusal(p, message);
      if (refusal != null) {
         ci.cancel();
         p.sendSystemMessage(Component.literal(refusal));
      }
   }
}
