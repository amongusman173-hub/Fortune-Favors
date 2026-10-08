package com.fortuneandfavors.economy;

import com.fortuneandfavors.net.FfVfx;
import com.fortuneandfavors.util.FxKinds;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/**
 * The effect templates: one call draws an effect for everybody.
 *
 * <p>{@link FfVfx#shape} reaches only clients that have the mod, which draw the cue with the mod's
 * own particles. Particles sent between {@link FfVfx#enter} and {@link FfVfx#exit} reach only the
 * clients that do not - vanilla Java and Bedrock through Geyser. A shape sent on its own is
 * therefore invisible to half the server, which is how a few effects went quietly missing for
 * players without the mod. Every method here sends both: the cue, and a vanilla version of the
 * same shape drawn from the cue's own particle type and kept to a bounded count so Geyser's
 * one-packet-per-particle translation stays affordable.
 *
 * <p>The templates come in two families. The transport's own kinds ({@code FfVfx.RING},
 * {@code BEAM}, {@code NOVA} ...) and the newer {@link FxKinds} set (spiral, shockwave,
 * lightning, dome, vortex, crescent, starburst, rune circle, ember rain, chains, heartbeat,
 * petals, shatter, comet, aura, flare), which {@code client.FfVfxClient} draws. Use the named
 * methods; {@link #shape} is the general form they all go through.
 */
public final class Fx {
   /** Most vanilla particles one fallback may send, whatever the shape's size. */
   private static final int MAX_FALLBACK = 40;

   private Fx() {
   }

   /**
    * Sends a cue to modded clients and its vanilla version to everyone else.
    *
    * @param dir the cue's second vector: a direction for slashes and muzzles, the far end for
    *            beams, lightning, chains, meteors and comets
    */
   public static void shape(ServerLevel level, int kind, ParticleOptions particle, Vec3 at, Vec3 dir, double a, double b, int color) {
      if (level == null || at == null) {
         return;
      }
      Vec3 second = dir == null ? Vec3.ZERO : dir;
      FfVfx.shape(level, kind, particle, at, second, a, b, color);
      FfVfx.enter();
      try {
         fallback(level, kind, particle, at, second, a);
      } finally {
         FfVfx.exit();
      }
   }

   // ------------------------------------------------------------------ the transport's own kinds

   public static void beam(ServerLevel level, ParticleOptions p, Vec3 from, Vec3 to, int color) {
      shape(level, FfVfx.BEAM, p, from, to, 0.0, 0.0, color);
   }

   public static void ring(ServerLevel level, ParticleOptions p, Vec3 center, double radius, int color) {
      shape(level, FfVfx.RING, p, center, Vec3.ZERO, radius, 0.0, color);
   }

   public static void nova(ServerLevel level, ParticleOptions p, Vec3 center, double radius, int color) {
      shape(level, FfVfx.NOVA, p, center, Vec3.ZERO, radius, 0.0, color);
   }

   public static void pillar(ServerLevel level, ParticleOptions p, Vec3 base, double height, int color) {
      shape(level, FfVfx.PILLAR, p, base, Vec3.ZERO, height, 0.0, color);
   }

   public static void summonCircle(ServerLevel level, ParticleOptions p, Vec3 center, double radius, double ticks, int color) {
      shape(level, FfVfx.SUMMON_CIRCLE, p, center, Vec3.ZERO, radius, ticks, color);
   }

   public static void clash(ServerLevel level, ParticleOptions p, Vec3 at, Vec3 facing, int color) {
      shape(level, FfVfx.CLASH, p, at, facing, 0.0, 0.0, color);
   }

   public static void slash(ServerLevel level, ParticleOptions p, Vec3 origin, Vec3 facing, double reach, int color) {
      shape(level, FfVfx.SLASH, p, origin, facing, reach, 0.0, color);
   }

   public static void muzzle(ServerLevel level, ParticleOptions p, Vec3 at, Vec3 facing, int color) {
      shape(level, FfVfx.MUZZLE, p, at, facing, 0.0, 0.0, color);
   }

   public static void tear(ServerLevel level, ParticleOptions p, Vec3 at, Vec3 axis, double size, int ticks, int color) {
      shape(level, FfVfx.TEAR, p, at, axis, size, ticks, color);
   }

   public static void clockBurst(ServerLevel level, ParticleOptions p, Vec3 at, double radius, int color) {
      shape(level, FfVfx.CLOCK_BURST, p, at, Vec3.ZERO, radius, 0.0, color);
   }

   public static void rockburst(ServerLevel level, ParticleOptions p, Vec3 at, double size, int color) {
      shape(level, FfVfx.ROCKBURST, p, at, Vec3.ZERO, size, 0.0, color);
   }

   public static void gooSplash(ServerLevel level, ParticleOptions p, Vec3 at, double size, int color) {
      shape(level, FfVfx.GOO_SPLASH, p, at, Vec3.ZERO, size, 0.0, color);
   }

   public static void geyser(ServerLevel level, ParticleOptions p, Vec3 base, double height, int color) {
      shape(level, FfVfx.GEYSER, p, base, Vec3.ZERO, height, 0.0, color);
   }

   public static void meteor(ServerLevel level, ParticleOptions p, Vec3 from, Vec3 to, double fallTicks, int color) {
      shape(level, FfVfx.METEOR, p, from, to, 0.0, fallTicks, color);
   }

   public static void wormhole(ServerLevel level, ParticleOptions p, Vec3 at, boolean closing, int color) {
      shape(level, FfVfx.WORMHOLE, p, at, Vec3.ZERO, 0.0, closing ? 1.0 : 0.0, color);
   }

   public static void rift(ServerLevel level, ParticleOptions p, Vec3 at, double size, int color) {
      shape(level, FfVfx.RIFT, p, at, Vec3.ZERO, size, 0.0, color);
   }

   public static void resonance(ServerLevel level, ParticleOptions p, Vec3 at, double ticks, int color) {
      shape(level, FfVfx.RESONANCE, p, at, Vec3.ZERO, ticks, 0.0, color);
   }

   public static void iceBurst(ServerLevel level, ParticleOptions p, Vec3 at, double size, int color) {
      shape(level, FfVfx.ICE_BURST, p, at, Vec3.ZERO, size, 0.0, color);
   }

   // ------------------------------------------------------------------ the newer templates

   public static void spiral(ServerLevel level, ParticleOptions p, Vec3 base, double height, int ticks, int color) {
      shape(level, FxKinds.SPIRAL, p, base, Vec3.ZERO, height, ticks, color);
   }

   public static void shockwave(ServerLevel level, ParticleOptions p, Vec3 center, double radius, int color) {
      shape(level, FxKinds.SHOCKWAVE, p, center, Vec3.ZERO, radius, 0.0, color);
   }

   public static void lightning(ServerLevel level, ParticleOptions p, Vec3 from, Vec3 to, int color) {
      shape(level, FxKinds.LIGHTNING, p, from, to, 0.0, 0.0, color);
   }

   public static void dome(ServerLevel level, ParticleOptions p, Vec3 center, double radius, int ticks, int color) {
      shape(level, FxKinds.DOME, p, center, Vec3.ZERO, radius, ticks, color);
   }

   public static void vortex(ServerLevel level, ParticleOptions p, Vec3 center, double radius, int ticks, int color) {
      shape(level, FxKinds.VORTEX, p, center, Vec3.ZERO, radius, ticks, color);
   }

   public static void crescent(ServerLevel level, ParticleOptions p, Vec3 origin, Vec3 facing, double reach, int color) {
      shape(level, FxKinds.CRESCENT, p, origin, facing, reach, 0.0, color);
   }

   public static void starburst(ServerLevel level, ParticleOptions p, Vec3 center, double length, int color) {
      shape(level, FxKinds.STARBURST, p, center, Vec3.ZERO, length, 0.0, color);
   }

   public static void runeCircle(ServerLevel level, ParticleOptions p, Vec3 center, double radius, int ticks, int color) {
      shape(level, FxKinds.RUNE_CIRCLE, p, center, Vec3.ZERO, radius, ticks, color);
   }

   public static void emberRain(ServerLevel level, ParticleOptions p, Vec3 center, double radius, int ticks, int color) {
      shape(level, FxKinds.EMBER_RAIN, p, center, Vec3.ZERO, radius, ticks, color);
   }

   public static void chains(ServerLevel level, ParticleOptions p, Vec3 from, Vec3 to, int color) {
      shape(level, FxKinds.CHAINS, p, from, to, 0.0, 0.0, color);
   }

   public static void heartbeat(ServerLevel level, ParticleOptions p, Vec3 center, double radius, int ticks, int color) {
      shape(level, FxKinds.HEARTBEAT, p, center, Vec3.ZERO, radius, ticks, color);
   }

   public static void petals(ServerLevel level, ParticleOptions p, Vec3 center, double radius, int ticks, int color) {
      shape(level, FxKinds.PETALS, p, center, Vec3.ZERO, radius, ticks, color);
   }

   public static void shatter(ServerLevel level, ParticleOptions p, Vec3 center, double size, int color) {
      shape(level, FxKinds.SHATTER, p, center, Vec3.ZERO, size, 0.0, color);
   }

   public static void comet(ServerLevel level, ParticleOptions p, Vec3 from, Vec3 to, int ticks, int color) {
      shape(level, FxKinds.COMET, p, from, to, 0.0, ticks, color);
   }

   public static void aura(ServerLevel level, ParticleOptions p, Vec3 feet, double height, int ticks, int color) {
      shape(level, FxKinds.AURA, p, feet, Vec3.ZERO, height, ticks, color);
   }

   public static void flare(ServerLevel level, ParticleOptions p, Vec3 center, double size, int color) {
      shape(level, FxKinds.FLARE, p, center, Vec3.ZERO, size, 0.0, color);
   }

   // ------------------------------------------------------------------ the vanilla versions

   /**
    * What a client without the mod sees for each kind: the same shape in plain particles, once.
    * Timed effects (a dome that holds, a rune circle that turns) are drawn as their first frame.
    */
   private static void fallback(ServerLevel level, int kind, ParticleOptions p, Vec3 at, Vec3 dir, double a) {
      switch (kind) {
         case FfVfx.BEAM, FfVfx.SONIC, FxKinds.LIGHTNING, FxKinds.CHAINS -> line(level, p, at, dir, 0.6);
         case FfVfx.METEOR, FxKinds.COMET -> {
            line(level, p, at, dir, 1.2);
            burst(level, p, dir, 0.6, 10);
         }
         case FfVfx.RING, FfVfx.SUMMON_CIRCLE, FxKinds.RUNE_CIRCLE, FxKinds.HEARTBEAT, FxKinds.PETALS -> ring(level, p, at, Math.max(0.5, a));
         case FfVfx.NOVA, FfVfx.FROST_NOVA, FxKinds.SHOCKWAVE -> {
            ring(level, p, at, Math.max(0.5, a));
            ring(level, p, at, Math.max(0.5, a) * 0.5);
         }
         case FxKinds.DOME -> {
            ring(level, p, at, Math.max(0.5, a));
            ring(level, p, at.add(0.0, Math.max(0.5, a) * 0.7, 0.0), Math.max(0.5, a) * 0.7);
         }
         case FfVfx.PILLAR, FfVfx.GEYSER, FxKinds.SPIRAL, FxKinds.AURA -> column(level, p, at, Math.max(1.0, a));
         case FfVfx.SLASH, FxKinds.CRESCENT -> arc(level, p, at, dir, Math.max(1.0, a));
         case FxKinds.VORTEX -> {
            ring(level, p, at, Math.max(0.5, a));
            column(level, p, at, 2.0);
         }
         case FxKinds.EMBER_RAIN -> FfVfx.particles(level, p, at.x, at.y + 4.0, at.z, Math.min(MAX_FALLBACK, (int)(a * 3.0) + 4), a * 0.6, 1.0, a * 0.6, 0.02);
         case FxKinds.STARBURST, FxKinds.SHATTER, FxKinds.FLARE, FfVfx.ROCKBURST, FfVfx.GOO_SPLASH, FfVfx.ICE_BURST, FfVfx.CLOCK_BURST ->
            burst(level, p, at, Math.max(0.4, a * 0.5), (int)Math.min(MAX_FALLBACK, 12 + a * 6.0));
         case FfVfx.CLASH, FfVfx.MUZZLE -> burst(level, p, at, 0.25, 10);
         case FfVfx.TEAR, FfVfx.RIFT, FfVfx.WORMHOLE, FfVfx.RESONANCE -> burst(level, p, at, Math.max(0.4, Math.min(1.5, a * 0.5)), 18);
         default -> burst(level, p, at, 0.5, 12);
      }
   }

   private static void line(ServerLevel level, ParticleOptions p, Vec3 from, Vec3 to, double spacing) {
      double length = from.distanceTo(to);
      int n = Math.max(2, Math.min(MAX_FALLBACK, (int)(length / spacing)));
      for (int i = 0; i <= n; i++) {
         Vec3 q = from.lerp(to, i / (double)n);
         FfVfx.particles(level, p, q.x, q.y, q.z, 1, 0.02, 0.02, 0.02, 0.0);
      }
   }

   private static void ring(ServerLevel level, ParticleOptions p, Vec3 center, double radius) {
      int n = Math.max(8, Math.min(MAX_FALLBACK, (int)(Math.PI * 2.0 * radius / 0.8)));
      for (int i = 0; i < n; i++) {
         double angle = Math.PI * 2.0 * i / n;
         FfVfx.particles(level, p, center.x + Math.cos(angle) * radius, center.y + 0.1, center.z + Math.sin(angle) * radius, 1, 0.0, 0.0, 0.0, 0.0);
      }
   }

   private static void column(ServerLevel level, ParticleOptions p, Vec3 base, double height) {
      int n = Math.max(4, Math.min(MAX_FALLBACK, (int)(height * 2.5)));
      for (int i = 0; i < n; i++) {
         FfVfx.particles(level, p, base.x, base.y + height * i / n, base.z, 1, 0.15, 0.05, 0.15, 0.01);
      }
   }

   private static void arc(ServerLevel level, ParticleOptions p, Vec3 origin, Vec3 facing, double reach) {
      Vec3 f = new Vec3(facing.x, 0.0, facing.z);
      f = f.lengthSqr() < 1.0E-4 ? new Vec3(0.0, 0.0, 1.0) : f.normalize();
      Vec3 side = new Vec3(-f.z, 0.0, f.x);
      int n = Math.max(6, Math.min(MAX_FALLBACK, (int)(reach * 3.0)));
      for (int i = 0; i <= n; i++) {
         double angle = -1.0 + 2.0 * i / n;
         Vec3 d = f.scale(Math.cos(angle)).add(side.scale(Math.sin(angle)));
         Vec3 q = origin.add(0.0, 1.0, 0.0).add(d.scale(reach * 0.8));
         FfVfx.particles(level, p, q.x, q.y, q.z, 1, 0.0, 0.0, 0.0, 0.0);
      }
   }

   private static void burst(ServerLevel level, ParticleOptions p, Vec3 at, double spread, int count) {
      FfVfx.particles(level, p, at.x, at.y, at.z, Math.max(1, Math.min(MAX_FALLBACK, count)), spread, spread, spread, 0.04);
   }
}
