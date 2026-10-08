package com.fortuneandfavors.mixin;

import com.fortuneandfavors.FortuneFavorsMod;
import com.fortuneandfavors.anticheat.AntiCheat;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The outbound packet layer: the server's own side of the conversation, seen as
 * it is sent.
 *
 * <p>Section 2 of the specification asks for both directions, and section 3 gives
 * the reason. An anticheat that only watches what the client sends has to
 * <i>infer</i> what the server told it, and the inference is exactly where the
 * false positives live: the server teleports a player, the client's next position
 * packet arrives a hundred blocks away, and from the inbound side alone that is
 * indistinguishable from a forged position. Vanilla papers over it by setting a
 * field the client is expected to confirm, and every anticheat that has ever
 * banned someone for a lag spike is a story about that inference going wrong.
 *
 * <p>So this records the two things the server says that change what a client's
 * next packet is allowed to look like:
 *
 * <ul>
 *   <li><b>Teleports</b> - {@link ClientboundPlayerPositionPacket} and
 *       {@link ClientboundTeleportEntityPacket}. The movement checks stand down
 *       for {@code SERVER_MOTION_GRACE_TICKS} from the moment the packet goes
 *       out, not from the moment the jump is observed. That is the difference
 *       between knowing and guessing: the grace starts before the client could
 *       possibly answer, and lasts long enough to cover its latency.</li>
 *   <li><b>Velocity</b> - {@link ClientboundSetEntityMotionPacket}. When the
 *       server shoves a player, the player is supposed to move, and a client that
 *       does not is the anti-knockback hack. Recording the impulse the client was
 *       actually <i>told</i> about gives the knockback check a second, independent
 *       figure to compare its travel against, which is the evidence fusion
 *       section 28 asks for.</li>
 * </ul>
 *
 * <p>Nothing is judged here. A teleport is only ever evidence that the player's
 * movement afterwards is not their own, and this class' whole job is to make that
 * knowable. Everything it learns is handed to {@link AntiCheat}.
 *
 * <p>Outbound traffic is produced by the server itself, on the server thread, so
 * the thread guard the inbound layer needs is a formality here - but it is kept,
 * because a handler that can throw on a netty thread is still a crash.
 */
@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class ServerOutboundMixin {
   @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"))
   private void fortuneandfavors$outbound(Packet<?> packet, CallbackInfo ci) {
      try {
         ServerPlayer player = player();
         if (player == null) {
            return;
         }
         if (packet instanceof ClientboundPlayerPositionPacket position) {
            // The correction, absolute or relative - either way the client is
            // being told where it now is, and its next packet answers that.
            AntiCheat.onOutboundTeleport(player, position.change().position());
         } else if (packet instanceof ClientboundTeleportEntityPacket entity && entity.id() == player.getId()) {
            AntiCheat.onOutboundTeleport(player, entity.change().position());
         } else if (packet instanceof ClientboundSetEntityMotionPacket motion && motion.id() == player.getId()) {
            AntiCheat.onOutboundVelocity(player, motion.movement());
         }
      } catch (VirtualMachineError e) {
         throw e;
      } catch (Throwable t) {
         FortuneFavorsMod.LOGGER.error("Fortune & Favors: anticheat outbound packet hook failed", t);
      }
   }

   /**
    * The player behind this connection, or null for the login and configuration
    * listeners that share this base class and have no player yet.
    *
    * <p>Also stands down off the server thread, for the same reason the inbound
    * handlers do: everything downstream is server-thread state.
    */
   private ServerPlayer player() {
      if (!((Object)this instanceof ServerGamePacketListenerImpl game)) {
         return null;
      }
      ServerPlayer player = game.player;
      if (player == null || !(player.level() instanceof ServerLevel level)) {
         return null;
      }
      if (level.getServer() == null || !level.getServer().isSameThread()) {
         return null;
      }
      return player;
   }
}
