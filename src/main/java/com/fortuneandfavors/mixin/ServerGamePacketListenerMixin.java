package com.fortuneandfavors.mixin;

import com.fortuneandfavors.FortuneFavorsMod;
import com.fortuneandfavors.anticheat.AntiCheat;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The inbound packet layer: every packet the client sends, seen at the door.
 *
 * <p>Everything the rest of the anticheat knows about a player is a
 * <i>consequence</i> - a position the server already applied, a break it already
 * timed. The packets themselves are the one place where the raw claim is still
 * visible, and some claims are impossible on their face rather than merely
 * suspicious: a coordinate that is not a number, a body that crossed a chunk
 * inside one packet, a client ticking forty times a second, a hotbar slot that
 * does not exist. None of those can be reconstructed afterwards, because vanilla
 * quietly absorbs all four - it clamps a teleport, logs a debug line, ignores a
 * slot - which is exactly how a client that does them keeps doing them.
 *
 * <p>These handlers do not decide anything themselves. Each one hands what the
 * packet says to {@link AntiCheat}, which decides, records, and answers whether
 * the packet may be applied; a handler cancels only when told to. The two that
 * can refuse are the two where vanilla's own answer is to ignore the packet
 * anyway, so a refusal changes no behaviour except that it is now on the record.
 *
 * <p>Three things worth stating plainly. Nothing here runs at all unless an admin
 * has switched the anticheat on and the player is not exempt. The move handler
 * mirrors vanilla's own guards - a client that is still loading its world, or a
 * singleplayer game that has already been won, is not judged - because the packets
 * it sends in those states are not movement at all. And every handler stands down
 * unless it is on the server thread: a packet is first offered to its handler on
 * the connection's own thread and re-dispatched by vanilla's
 * {@code ensureRunningOnSameThread}, which runs <i>after</i> this point. Everything
 * the anticheat touches is server-thread state, and an alert sent from a netty
 * thread is a crash rather than a false positive - so the off-thread pass is
 * skipped, and the packet lands here again a moment later, on the right thread.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerMixin {
   @Shadow
   public ServerPlayer player;

   @Shadow
   private Vec3 awaitingPositionFromClient;

   @Shadow
   public abstract boolean hasClientLoaded();

   /**
    * True when this packet is being offered to us on the server thread, which is
    * the only thread the anticheat's state may be read or written from. The first
    * pass over a packet happens on the connection's thread; vanilla re-schedules
    * it from {@code ensureRunningOnSameThread} and this handler runs again, on the
    * server thread, a moment later.
    */
   private boolean onServerThread() {
      return this.player != null
         && this.player.level() instanceof ServerLevel level
         && level.getServer() != null
         && level.getServer().isSameThread();
   }

   /**
    * Movement, position and rotation. Runs before vanilla's own clamping - a
    * client that sent a forged position is visible here and nowhere else, because
    * the first thing vanilla does with it is quietly pull it back.
    */
   @Inject(method = "handleMovePlayer", at = @At("HEAD"), cancellable = true)
   private void fortuneandfavors$anticheatMove(ServerboundMovePlayerPacket packet, CallbackInfo ci) {
      try {
         if (this.player == null || this.player.wonGame || !this.hasClientLoaded() || !this.onServerThread()) {
            return;
         }
         // Non-null for exactly as long as the server has a position in flight
         // waiting for the client to confirm it. Movement sent in that window is
         // the answer to a question the server asked, so it is never judged and
         // never refused.
         boolean teleportPending = this.awaitingPositionFromClient != null;
         if (!AntiCheat.allowMovePacket(this.player, packet, teleportPending)) {
            ci.cancel();
         }
      } catch (VirtualMachineError e) {
         throw e;
      } catch (Throwable t) {
         // Movement that is not judged is a gap in the checks; movement that is
         // dropped by a broken check is a player stuck in the air.
         FortuneFavorsMod.LOGGER.error("Fortune & Favors: anticheat packet hook failed on a move packet", t);
      }
   }

   @Inject(method = "handleAttack", at = @At("HEAD"))
   private void fortuneandfavors$anticheatAttack(ServerboundAttackPacket packet, CallbackInfo ci) {
      probe(() -> AntiCheat.onAttackPacket(this.player));
   }

   @Inject(method = "handleInteract", at = @At("HEAD"))
   private void fortuneandfavors$anticheatInteract(ServerboundInteractPacket packet, CallbackInfo ci) {
      probe(() -> AntiCheat.onActionPacket(this.player));
   }

   @Inject(method = "handlePlayerAction", at = @At("HEAD"))
   private void fortuneandfavors$anticheatAction(ServerboundPlayerActionPacket packet, CallbackInfo ci) {
      probe(() -> AntiCheat.onActionPacket(this.player));
   }

   @Inject(method = "handleUseItem", at = @At("HEAD"))
   private void fortuneandfavors$anticheatUseItem(ServerboundUseItemPacket packet, CallbackInfo ci) {
      probe(() -> AntiCheat.onActionPacket(this.player));
   }

   @Inject(method = "handleUseItemOn", at = @At("HEAD"))
   private void fortuneandfavors$anticheatUseItemOn(ServerboundUseItemOnPacket packet, CallbackInfo ci) {
      probe(() -> AntiCheat.onActionPacket(this.player));
   }

   /**
    * The selected hotbar slot. Vanilla already refuses to apply a slot outside
    * the hotbar - it logs a warning and returns - so cancelling here is the same
    * behaviour with the packet placed on the record.
    */
   @Inject(method = "handleSetCarriedItem", at = @At("HEAD"), cancellable = true)
   private void fortuneandfavors$anticheatCarriedItem(ServerboundSetCarriedItemPacket packet, CallbackInfo ci) {
      try {
         if (this.player != null && this.onServerThread() && !AntiCheat.allowCarriedSlot(this.player, packet.getSlot())) {
            ci.cancel();
         }
      } catch (VirtualMachineError e) {
         throw e;
      } catch (Throwable t) {
         FortuneFavorsMod.LOGGER.error("Fortune & Favors: anticheat packet hook failed on a carried-item packet", t);
      }
   }

   /**
    * Inventory clicks. The slot is checked against the menu the client actually
    * has open, which is the one question a click's own contents cannot answer.
    */
   @Inject(method = "handleContainerClick", at = @At("HEAD"), cancellable = true)
   private void fortuneandfavors$anticheatContainerClick(ServerboundContainerClickPacket packet, CallbackInfo ci) {
      try {
         if (this.player != null && this.onServerThread() && !AntiCheat.allowContainerClick(this.player, packet)) {
            ci.cancel();
         }
      } catch (VirtualMachineError e) {
         throw e;
      } catch (Throwable t) {
         FortuneFavorsMod.LOGGER.error("Fortune & Favors: anticheat packet hook failed on a container click", t);
      }
   }

   /**
    * The client finished a tick. One of these per tick of the client's own, which
    * makes it the only place the server is ever told how fast the client thinks
    * time is passing - the packet a timer hack inflates and nothing else shows.
    */
   @Inject(method = "handleClientTickEnd", at = @At("HEAD"))
   private void fortuneandfavors$anticheatClientTick(ServerboundClientTickEndPacket packet, CallbackInfo ci) {
      probe(() -> AntiCheat.onClientTickPacket(this.player));
   }

   /**
    * The client's own commands - and, for one of them, a request that is kept rather than
    * decided.
    *
    * <p>Vanilla answers a {@code START_FALL_FLYING} request by asking whether the body can
    * glide, and if the chest slot has not caught up with the wings it answers by stopping
    * the glide - permanently, because the client believes it is already gliding and never
    * asks again. So the request is handed to the anticheat before vanilla answers it: not
    * to refuse anything, but so the server can ask the same question again for a few ticks
    * and grant the glide the moment the slot can answer it. Without that, every tick of a
    * glide the server never agreed to reads as flight and speed.
    */
   @Inject(method = "handlePlayerCommand", at = @At("HEAD"))
   private void fortuneandfavors$anticheatCommand(ServerboundPlayerCommandPacket packet, CallbackInfo ci) {
      if (packet == null || packet.getAction() != ServerboundPlayerCommandPacket.Action.START_FALL_FLYING) {
         return;
      }
      probe(() -> AntiCheat.onGlideRequest(this.player));
   }

   /** Mod payloads: counted against the budget, never judged on their contents. */
   @Inject(method = "handleCustomPayload", at = @At("HEAD"))
   private void fortuneandfavors$anticheatPayload(ServerboundCustomPayloadPacket packet, CallbackInfo ci) {
      probe(() -> AntiCheat.onPayloadPacket(this.player));
   }

   /**
    * One shape for the handlers that only count: they observe and no more, so the
    * worst an exception can cost is a missed packet in the budget.
    */
   private void probe(Runnable observation) {
      try {
         if (this.player != null && this.onServerThread()) {
            observation.run();
         }
      } catch (VirtualMachineError e) {
         throw e;
      } catch (Throwable t) {
         FortuneFavorsMod.LOGGER.error("Fortune & Favors: anticheat packet observation failed", t);
      }
   }
}
