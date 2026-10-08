package com.fortuneandfavors.mixin;

import com.fortuneandfavors.FortuneFavorsMod;
import com.fortuneandfavors.anticheat.AntiCheat;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.portal.TeleportTransition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;

/**
 * The other half of a teleport: the half that never sends a packet.
 *
 * <p>The outbound packet layer was built on a true statement that turns out to be
 * incomplete. It says that a server which moves a player tells the client where it
 * went, so watching the packets leave is the same as knowing the server is the author
 * of the move. That is how a dimension change works, how a portal works and how this
 * mod's own scripted carries work - and it is <b>not</b> how the quiet paths work.
 * {@code /tp} reaches {@code Entity.teleportTo}, whose {@code ServerPlayer} override
 * repositions the body without a {@code ClientboundPlayerPositionPacket} in sight. An
 * ender pearl, a respawn and a same-dimension {@code ServerPlayer.teleport} reach the
 * body through the same door. None of them announce themselves.
 *
 * <p>Which means the module had exactly two readings available for a {@code /tp}: the
 * body is somewhere it did not walk, and no position packet was watched going out.
 * That is the definition of a forged position, so the honest player was filed as
 * {@code invalid-move}, answered with a setback, and had the movement checks stood down
 * for the next two seconds <i>as a reward for being teleported</i> - two false
 * positives and one false negative from the same missing fact.
 *
 * <p>So the fact is taken from the source instead: anything that calls the server's own
 * teleport API tells the anticheat, by name, before it moves anything. Nothing is
 * judged here and nothing is refused here - this class only ever <i>informs</i>, and the
 * worst an exception can do is cost one stand-down, which is why every handler fails
 * quietly to a logged line rather than to a missing injection.
 *
 * <p>Three targets, chosen because between them they are the whole of the API the server
 * or a mod can use to move a player's body in a straight line: the modern
 * {@link TeleportTransition} entry point (pearls, portals, dimensions, the mod's warps
 * and carries), and both {@code teleportTo} overloads (vanilla's {@code /tp} and the
 * absolute-position call this mod makes in forty-odd places). Every one of them is
 * verified to exist on the class it is injected into, and the self-test counts the
 * handlers that reach the class rather than trusting that they did.
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerTeleportMixin {
   /**
    * The modern teleport: a transition carrying a level, a position, a velocity, a
    * rotation and the relative flags. Ender pearls, portals, dimension changes, respawn
    * transitions and every {@code player.teleport(new TeleportTransition(...))} in this
    * mod arrive here.
    */
   @Inject(method = "teleport(Lnet/minecraft/world/level/portal/TeleportTransition;)Lnet/minecraft/server/level/ServerPlayer;", at = @At("HEAD"))
   private void fortuneandfavors$anticheatTeleport(TeleportTransition transition, CallbackInfoReturnable<ServerPlayer> cir) {
      inform("a teleport the server performed");
   }

   /**
    * Vanilla's {@code /tp}, a {@code /spreadplayers}, a {@code /execute ... at} - the
    * absolute-position API with relative flags. This is the path that made the whole
    * class necessary: it is the most common way a player is moved on any server, and it
    * is the one that sends nothing.
    */
   @Inject(method = "teleportTo(Lnet/minecraft/server/level/ServerLevel;DDDLjava/util/Set;FFZ)Z", at = @At("HEAD"))
   private void fortuneandfavors$anticheatTeleportTo(
      ServerLevel level,
      double x,
      double y,
      double z,
      Set<Relative> relatives,
      float yaw,
      float pitch,
      boolean resetCamera,
      CallbackInfoReturnable<Boolean> cir
   ) {
      inform("a teleport the server performed");
   }

   /**
    * The same thing by delta rather than destination: {@code dismountTo}, a mod moving a
    * body a set distance, anything reading "put the player here" as "nudge the player
    * this far".
    */
   @Inject(method = "teleportTo(DDD)V", at = @At("HEAD"))
   private void fortuneandfavors$anticheatTeleportBy(double x, double y, double z, CallbackInfo ci) {
      inform("a teleport the server performed");
   }

   /** One door, so no handler can disagree with another about what a teleport is. */
   private void inform(String reason) {
      try {
         AntiCheat.onServerTeleport((ServerPlayer)(Object)this, reason);
      } catch (VirtualMachineError e) {
         throw e;
      } catch (Throwable t) {
         FortuneFavorsMod.LOGGER.error("Fortune & Favors: anticheat server teleport hook failed", t);
      }
   }
}
