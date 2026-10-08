package com.fortuneandfavors.net;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Where a visual splits by who is watching.
 *
 * <p>Players whose client has the mod get {@link FfVfxPayload} cues, batched per level and flushed
 * once a tick, and draw them themselves. Everyone else gets plain particle packets. A visual is
 * therefore written once, as a call here, and never has to know which kind of client is looking.
 *
 * <p>Shapes (ring, beam, nova...) send one cue and then draw their vanilla version between
 * {@link #enter()} and {@link #exit()}: inside that bracket {@link #particles} only feeds vanilla
 * clients, so a modded client gets the shape and not also the hundred particles it is made of.
 */
public final class FfVfx {
   /** Plain particles: (x,y,z) anchor, (ax,ay,az) spread, a = speed, count. */
   public static final int RAW = 0;
   /** Flat ring: (x,y,z) centre, a = radius, b = outward speed (negative pulls in). */
   public static final int RING = 1;
   /** Line: (x,y,z) from, (ax,ay,az) to, color = helix colour. */
   public static final int BEAM = 2;
   /** Void rift: (x,y,z) centre, a = size. */
   public static final int RIFT = 3;
   /** Impact: (x,y,z) centre, a = radius, color, particle = the shockwave's material. */
   public static final int NOVA = 4;
   /** Rising column: (x,y,z) base, a = height. */
   public static final int PILLAR = 5;
   /** A rift tearing open: (x,y,z) centre, (ax,ay,az) the slit's sideways axis, a = half-height, b = lifetime in ticks. */
   public static final int TEAR = 6;
   /** One frame of a clock face: (x,y,z) centre, a = radius, b = sweep 0..1. Sent every tick it shows. */
   public static final int CLOCK = 7;
   /** Time stopping: (x,y,z) centre, a = radius. A shutter flash and gold rings snapping outward. */
   public static final int CLOCK_BURST = 8;
   /** The dragon's last stand: (x,y,z) the dragon, (ax,ay,az) the arena centre, a = duration in ticks. */
   public static final int ULTIMATE = 9;
   /** The dragon's death: (x,y,z) where it died, a = duration in ticks. */
   public static final int DEATH = 10;
   /** The exit gateway opening: (x,y,z) base, a = duration in ticks. */
   public static final int GATEWAY = 11;
   /** A splash of gel: (x,y,z) where it lands, a = radius, color. */
   public static final int GOO_SPLASH = 12;
   /** A geyser erupting: (x,y,z) its base, a = height, color. */
   public static final int GEYSER = 13;
   /** A falling gel meteor: (x,y,z) from, (ax,ay,az) to, b = flight time in ticks, color. */
   public static final int METEOR = 14;
   /** Rock breaking: (x,y,z) where, a = radius, color = the dust's colour. Chunks thrown up and out. */
   public static final int ROCKBURST = 15;
   /** The Time Lord blown back into his rift: (x,y,z) him, (ax,ay,az) the rift's sideways axis, a = duration. */
   public static final int RIFT_DEATH = 16;
   /** A summoning circle: (x,y,z) its centre on the ground, a = radius, b = how long it plays in ticks, color. */
   public static final int SUMMON_CIRCLE = 17;
   /** A wormhole: (x,y,z) where, b = 0 for leaving (a funnel closing on you), 1 for arriving (bursting open). */
   public static final int WORMHOLE = 18;
   /** Cut short any set piece (an ultimate) playing within 12 blocks of (x,y,z): a channel was broken. */
   public static final int STOP = 19;
   /** A golden clash (a parry, a perfect block): (x,y,z) the point of contact, (ax,ay,az) the way it faces. */
   public static final int CLASH = 20;
   /** A small muzzle puff: (x,y,z) the muzzle, (ax,ay,az) the way it fired, color. */
   public static final int MUZZLE = 21;
   /** Burning rods orbiting an entity: count = its entity id, a = phase (1..3), b = how long to keep drawing them. */
   public static final int ROD_ORBIT = 22;
   /** A great slash that cuts first and lands later: (x,y,z) the target, (ax,ay,az) the swing's facing, a = ticks until the cut lands, color. */
   public static final int SLASH = 23;
   /** The Mindbinder's aura bound to its body: count = entity id, a = phase (1..2), b = how long to keep drawing it. */
   public static final int MIND_AURA = 24;
   /** A ring of ice racing out along the ground: (x,y,z) centre, a = radius. */
   public static final int FROST_NOVA = 25;
   /** A crystal spire erupting and breaking: (x,y,z) its foot, a = height. */
   public static final int ICE_ERUPT = 26;
   /** A great icicle falling and shattering: (x,y,z) where it lands, a = ticks to fall. */
   public static final int ICICLE = 27;
   /** A frost lance: (x,y,z) from, (ax,ay,az) to. */
   public static final int FROST_LANCE = 28;
   /** The Snow Queen's aura: count = entity id, a = phase (1..3), b = how long to keep drawing it. */
   public static final int SNOW_AURA = 29;
   /** A whiteout closing on a body: count = entity id, a = ticks until it bursts. */
   public static final int WHITEOUT = 30;
   /** The Snow Queen's death: (x,y,z) her feet, a = how long it plays (she shatters at 70%). */
   public static final int SNOW_DEATH = 31;
   /** The Snow Queen's arrival: (x,y,z) where she rises, a = how long it plays (the funnel bursts at 75%). */
   public static final int SNOW_SPAWN = 32;
   /** The ice staff's breath: (x,y,z) the mouth, (ax,ay,az) the far end, a = spread. */
   public static final int FROST_SPRAY = 33;
   /** An ice shard in flight: count = entity id, b = how long to follow it. */
   public static final int ICE_TRAIL = 34;
   /** Ice bursting apart: (x,y,z) where, a = size. */
   public static final int ICE_BURST = 35;
   /** A sonic lance: (x,y,z) from, (ax,ay,az) to, color. */
   public static final int SONIC = 36;
   /** The Elder Warden's ultimate: (x,y,z) his feet, a = how long it plays (charge 42%, then three shockwaves 12 ticks apart). */
   public static final int RESONANCE = 37;
   /** The Elder Warden's aura: count = entity id, a = phase (1..2), b = how long to keep drawing it, color. */
   public static final int SCULK_AURA = 38;

   private static final Map<ServerLevel, List<FfVfxPayload.Cue>> PENDING = new IdentityHashMap<>();
   private static int depth;
   private static final java.util.Set<java.util.UUID> ANNOUNCED = java.util.concurrent.ConcurrentHashMap.newKeySet();

   private FfVfx() {
   }

   public static boolean modded(ServerPlayer player) {
      try {
         return ServerPlayNetworking.canSend(player, FfVfxPayload.TYPE);
      } catch (Throwable t) {
         return false;
      }
   }

   private static boolean anyModded(ServerLevel level) {
      for (ServerPlayer p : level.players()) {
         if (modded(p)) {
            return true;
         }
      }
      return false;
   }

   /** Opens a shape: particles drawn until {@link #exit()} are for vanilla clients only. */
   public static void enter() {
      depth++;
   }

   public static void exit() {
      depth = Math.max(0, depth - 1);
   }

   /** Queues a shape for modded clients. Ignored inside another shape - the outer one already said it. */
   public static void shape(ServerLevel level, int kind, ParticleOptions particle, Vec3 at, Vec3 aux, double a, double b, int color) {
      if (depth > 0 || level == null || particle == null || at == null || aux == null) {
         return;
      }
      queue(level, new FfVfxPayload.Cue(
         kind, particle, (float)at.x, (float)at.y, (float)at.z, (float)aux.x, (float)aux.y, (float)aux.z, (float)a, (float)b, 0, color
      ));
   }

   /** A shape that follows an entity: the client looks the body up by id each tick. */
   public static void follow(ServerLevel level, int kind, ParticleOptions particle, net.minecraft.world.entity.Entity body, double a, double b, int color) {
      if (depth > 0 || level == null || body == null) {
         return;
      }
      queue(level, new FfVfxPayload.Cue(
         kind, particle, (float)body.getX(), (float)body.getY(), (float)body.getZ(), 0.0F, 0.0F, 0.0F, (float)a, (float)b, body.getId(), color
      ));
   }

   /** The drop-in for {@code level.sendParticles(...)}: vanilla clients get the packet, modded ones a cue. */
   public static void particles(
      ServerLevel level, ParticleOptions particle, double x, double y, double z, int count, double dx, double dy, double dz, double speed
   ) {
      if (!anyModded(level)) {
         level.sendParticles(particle, x, y, z, count, dx, dy, dz, speed);
         return;
      }
      if (depth == 0) {
         queue(level, new FfVfxPayload.Cue(
            RAW, particle, (float)x, (float)y, (float)z, (float)dx, (float)dy, (float)dz, (float)speed, 0.0F, count, 0
         ));
      }
      for (ServerPlayer p : level.players()) {
         if (!modded(p)) {
            level.sendParticles(p, particle, false, false, x, y, z, count, dx, dy, dz, speed);
         }
      }
   }

   private static void queue(ServerLevel level, FfVfxPayload.Cue cue) {
      if (!anyModded(level)) {
         return;
      }
      List<FfVfxPayload.Cue> list = PENDING.computeIfAbsent(level, k -> new ArrayList<>());
      if (list.size() < FfVfxPayload.MAX_CUES) {
         list.add(cue);
      }
   }

   /** Sends the tick's cues. Called once per server tick. */
   public static void flush(MinecraftServer server) {
      depth = 0;
      if (PENDING.isEmpty()) {
         return;
      }
      for (Map.Entry<ServerLevel, List<FfVfxPayload.Cue>> e : PENDING.entrySet()) {
         FfVfxPayload payload = new FfVfxPayload(List.copyOf(e.getValue()));
         for (ServerPlayer p : e.getKey().players()) {
            if (modded(p) && FfNet.send(p, payload) && ANNOUNCED.add(p.getUUID())) {
               com.fortuneandfavors.FortuneFavorsMod.LOGGER.info("Fortune & Favors VFX: sending effect cues to {} (client has the mod)", p.getName().getString());
            }
         }
      }
      PENDING.clear();
   }
}
