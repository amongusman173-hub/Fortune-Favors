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
 * petals, shatter, comet, aura, flare, and the themed set: sculk bloom, soul stream, tide wave,
 * gust, gear spin, starfall, gem shards, blood splash, threads, void collapse, rift portal, sonic
 * ring), which {@code client.FfVfxClient} draws. Use the named methods; {@link #shape} is the
 * general form they all go through.
 *
 * <p><b>Vanilla particles next to a template.</b> A plain {@code level.sendParticles} (or
 * {@code FfVfx.particles} outside a scope) reaches everyone - so a modded player sees the custom
 * shape <i>and</i> the vanilla puffs piled on top of it. When an effect adds its own vanilla
 * particles beside a template, send them through {@link #vanilla} (one call) or wrap a block of
 * {@code FfVfx.particles} calls in {@link #vanillaOnly}: they then reach only the clients that
 * cannot draw the template.
 */
public final class Fx {
   /** Most vanilla particles one fallback may send, whatever the shape's size. */
   private static final int MAX_FALLBACK = 40;

   /**
    * How deep we are in vanilla-only scopes opened from here. Only the outermost opens and closes
    * the transport's scope, so a {@link #vanilla} call inside {@link #vanillaOnly} (or a template
    * inside either) cannot close the outer scope early and leak the rest of the block to modded
    * clients. The server ticks levels on one thread, so a plain counter is enough.
    */
   private static int vanillaDepth;

   private Fx() {
   }

   // ------------------------------------------------------------------ vanilla-only particles

   /**
    * Sends vanilla particles to the clients <b>without</b> the mod only - vanilla Java and Bedrock
    * through Geyser. Same arguments as {@code ServerLevel.sendParticles}. Use it for the vanilla
    * dressing around a template ({@link #sculkBloom}, {@link #riftPortal} ...): a modded client
    * already draws the template with the mod's own sprites, and the vanilla puffs on top of it
    * only muddy the shape.
    */
   public static void vanilla(
      ServerLevel level, ParticleOptions p, double x, double y, double z, int count, double dx, double dy, double dz, double speed
   ) {
      if (level == null || p == null) {
         return;
      }
      openVanilla();
      try {
         FfVfx.particles(level, p, x, y, z, count, dx, dy, dz, speed);
      } finally {
         closeVanilla();
      }
   }

   /**
    * Runs {@code r} with every {@code FfVfx.particles} call inside it reaching only clients without
    * the mod (always closed again, even if {@code r} throws). For a block of several sends, or a
    * helper that draws a whole vanilla shape. Inside it, send with {@code FfVfx.particles} or
    * {@link #vanilla}: a bare {@code level.sendParticles} is not routed through the transport and
    * still reaches everyone. Scopes nest safely.
    */
   public static void vanillaOnly(Runnable r) {
      if (r == null) {
         return;
      }
      openVanilla();
      try {
         r.run();
      } finally {
         closeVanilla();
      }
   }

   private static void openVanilla() {
      if (vanillaDepth++ == 0) {
         FfVfx.enter();
      }
   }

   private static void closeVanilla() {
      if (--vanillaDepth <= 0) {
         vanillaDepth = 0;
         FfVfx.exit();
      }
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
      openVanilla();
      try {
         fallback(level, kind, particle, at, second, a);
      } finally {
         closeVanilla();
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

   // ------------------------------------------------------------------ the themed templates

   /** Sculk veins creeping over the ground out to {@code radius}, spores swelling and popping along them. */
   public static void sculkBloom(ServerLevel level, ParticleOptions p, Vec3 center, double radius, int ticks, int color) {
      shape(level, FxKinds.SCULK_BLOOM, p, center, Vec3.ZERO, radius, ticks, color);
   }

   /** Soul wisps flowing from {@code from} to {@code to} on a weaving path {@code sway} blocks wide. */
   public static void soulStream(ServerLevel level, ParticleOptions p, Vec3 from, Vec3 to, double sway, int ticks, int color) {
      shape(level, FxKinds.SOUL_STREAM, p, from, to, sway, ticks, color);
   }

   /** A rolling wall of water travelling {@code reach} blocks from {@code at} along {@code direction}. */
   public static void tideWave(ServerLevel level, ParticleOptions p, Vec3 at, Vec3 direction, double reach, int ticks, int color) {
      shape(level, FxKinds.TIDE_WAVE, p, at, direction, reach, ticks, color);
   }

   /** A swirl of feathers and wind streaks corkscrewing {@code reach} blocks along {@code direction}. */
   public static void gust(ServerLevel level, ParticleOptions p, Vec3 at, Vec3 direction, double reach, int color) {
      shape(level, FxKinds.GUST, p, at, direction, reach, 0.0, color);
   }

   /**
    * Three interlocking cogs turning. They stand upright facing {@code facing}, or lie flat on the
    * ground when it is {@link Vec3#ZERO}.
    */
   public static void gearSpin(ServerLevel level, ParticleOptions p, Vec3 center, Vec3 facing, double size, int ticks, int color) {
      shape(level, FxKinds.GEAR_SPIN, p, center, facing, size, ticks, color);
   }

   /** Twinkling stars raining over a circle of {@code radius}, flashing where they land. */
   public static void starfall(ServerLevel level, ParticleOptions p, Vec3 center, double radius, int ticks, int color) {
      shape(level, FxKinds.STARFALL, p, center, Vec3.ZERO, radius, ticks, color);
   }

   /** Faceted gems bursting out of a flash, glinting as they tumble. */
   public static void gemShards(ServerLevel level, ParticleOptions p, Vec3 center, double size, int color) {
      shape(level, FxKinds.GEM_SHARDS, p, center, Vec3.ZERO, size, 0.0, color);
   }

   /** Droplets arcing out of a hit (thrown along {@code direction}, or all round when ZERO) and dripping. */
   public static void bloodSplash(ServerLevel level, ParticleOptions p, Vec3 at, Vec3 direction, double size, int color) {
      shape(level, FxKinds.BLOOD_SPLASH, p, at, direction, size, 0.0, color);
   }

   /** Puppet strings dropping from {@code height} blocks above onto {@code target}, hanging taut, then snapping. */
   public static void threads(ServerLevel level, ParticleOptions p, Vec3 target, double height, int ticks, int color) {
      shape(level, FxKinds.THREADS, p, target, Vec3.ZERO, height, ticks, color);
   }

   /** Dark motes imploding into {@code center} from {@code radius} over {@code ticks}, then a ring thrown out. */
   public static void voidCollapse(ServerLevel level, ParticleOptions p, Vec3 center, double radius, int ticks, int color) {
      shape(level, FxKinds.VOID_COLLAPSE, p, center, Vec3.ZERO, radius, ticks, color);
   }

   /**
    * An upright portal rift standing on {@code base}, facing {@code facing} (what steps out comes
    * that way): it tears open over 10 ticks, holds for {@code holdTicks} and seals over 10. Made
    * for summons - the Multidimensional Army's wither skeleton stepping through.
    */
   public static void riftPortal(ServerLevel level, ParticleOptions p, Vec3 base, Vec3 facing, double height, int holdTicks, int color) {
      shape(level, FxKinds.RIFT_PORTAL, p, base, facing, height, holdTicks, color);
   }

   /** Sculk shockwave crescents racing {@code reach} blocks from {@code at} along {@code direction}. */
   public static void sonicRing(ServerLevel level, ParticleOptions p, Vec3 at, Vec3 direction, double reach, int ticks, int color) {
      shape(level, FxKinds.SONIC_RING, p, at, direction, reach, ticks, color);
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
         // The themed set.
         case FxKinds.SCULK_BLOOM -> {
            ring(level, p, at, Math.max(0.5, a) * 0.7);
            burst(level, p, at.add(0.0, 0.2, 0.0), 0.4, 8);
         }
         case FxKinds.SOUL_STREAM -> line(level, p, at, dir, 0.8);
         case FxKinds.TIDE_WAVE, FxKinds.GUST, FxKinds.SONIC_RING -> {
            Vec3 end = at.add(flatDirection(dir).scale(Math.max(1.0, a)));
            line(level, p, at.add(0.0, 0.8, 0.0), end.add(0.0, 0.8, 0.0), kind == FxKinds.TIDE_WAVE ? 0.7 : 1.0);
            if (kind == FxKinds.TIDE_WAVE) {
               burst(level, p, end.add(0.0, 0.6, 0.0), 0.6, 8);
            }
         }
         case FxKinds.GEAR_SPIN -> ring(level, p, at, Math.max(0.5, a));
         case FxKinds.STARFALL -> FfVfx.particles(level, p, at.x, at.y + 4.0, at.z, Math.min(MAX_FALLBACK, (int)(a * 3.0) + 4), a * 0.6, 1.0, a * 0.6, 0.02);
         case FxKinds.GEM_SHARDS, FxKinds.BLOOD_SPLASH -> burst(level, p, at, Math.max(0.3, a * 0.4), (int)Math.min(MAX_FALLBACK, 10 + a * 6.0));
         case FxKinds.THREADS -> strings(level, p, at, Math.max(1.0, a));
         case FxKinds.VOID_COLLAPSE -> {
            ring(level, p, at, Math.max(0.5, a));
            burst(level, p, at, 0.15, 10);
         }
         case FxKinds.RIFT_PORTAL -> oval(level, p, at, dir, Math.max(1.0, a));
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

   /** The horizontal part of a direction, normalised; straight ahead (+z) when there is none. */
   private static Vec3 flatDirection(Vec3 dir) {
      Vec3 f = new Vec3(dir.x, 0.0, dir.z);
      return f.lengthSqr() < 1.0E-4 ? new Vec3(0.0, 0.0, 1.0) : f.normalize();
   }

   /** Four strings hanging from {@code height} above the target down onto it. */
   private static void strings(ServerLevel level, ParticleOptions p, Vec3 target, double height) {
      int per = MAX_FALLBACK / 4;
      for (int k = 0; k < 4; k++) {
         double ang = Math.PI * 0.5 * k + 0.4;
         Vec3 top = target.add(Math.cos(ang) * 0.6, height, Math.sin(ang) * 0.6);
         for (int i = 0; i < per; i++) {
            Vec3 q = top.lerp(target.add(0.0, 1.2, 0.0), i / (double)(per - 1));
            FfVfx.particles(level, p, q.x, q.y, q.z, 1, 0.0, 0.0, 0.0, 0.0);
         }
      }
   }

   /** An upright oval outline standing on {@code base}, its face toward {@code facing}. */
   private static void oval(ServerLevel level, ParticleOptions p, Vec3 base, Vec3 facing, double height) {
      Vec3 f = flatDirection(facing);
      Vec3 side = new Vec3(-f.z, 0.0, f.x);
      int n = Math.min(MAX_FALLBACK - 8, 28);
      double halfH = height * 0.5;
      double halfW = height * 0.3;
      for (int i = 0; i < n; i++) {
         double ang = Math.PI * 2.0 * i / n;
         Vec3 q = base.add(side.scale(Math.cos(ang) * halfW)).add(0.0, halfH + Math.sin(ang) * halfH, 0.0);
         FfVfx.particles(level, p, q.x, q.y, q.z, 1, 0.0, 0.0, 0.0, 0.0);
      }
      burst(level, p, base.add(0.0, halfH, 0.0), 0.3, 8);
   }

   private static void burst(ServerLevel level, ParticleOptions p, Vec3 at, double spread, int count) {
      FfVfx.particles(level, p, at.x, at.y, at.z, Math.max(1, Math.min(MAX_FALLBACK, count)), spread, spread, spread, 0.04);
   }

   // ------------------------------------------------------------------ templates 128-137

   public static void bloodMoon(ServerLevel level, ParticleOptions p, Vec3 under, double radius, int ticks, int color) {
      shape(level, FxKinds.BLOOD_MOON, p, under, Vec3.ZERO, radius, ticks, color);
   }

   public static void crimsonSigil(ServerLevel level, ParticleOptions p, Vec3 center, double radius, int ticks, int color) {
      shape(level, FxKinds.CRIMSON_SIGIL, p, center, Vec3.ZERO, radius, ticks, color);
   }

   public static void whirlpool(ServerLevel level, ParticleOptions p, Vec3 center, double radius, int ticks, int color) {
      shape(level, FxKinds.WHIRLPOOL, p, center, Vec3.ZERO, radius, ticks, color);
   }

   public static void tentacle(ServerLevel level, ParticleOptions p, Vec3 base, double height, int ticks, int color) {
      shape(level, FxKinds.TENTACLE, p, base, Vec3.ZERO, height, ticks, color);
   }

   public static void stormCell(ServerLevel level, ParticleOptions p, Vec3 ground, double radius, int ticks, int color) {
      shape(level, FxKinds.STORM_CELL, p, ground, Vec3.ZERO, radius, ticks, color);
   }

   public static void featherStorm(ServerLevel level, ParticleOptions p, Vec3 center, double radius, int ticks, int color) {
      shape(level, FxKinds.FEATHER_STORM, p, center, Vec3.ZERO, radius, ticks, color);
   }

   public static void cogBurst(ServerLevel level, ParticleOptions p, Vec3 at, double size, int color) {
      shape(level, FxKinds.COG_BURST, p, at, Vec3.ZERO, size, 0.0, color);
   }

   public static void gemRain(ServerLevel level, ParticleOptions p, Vec3 center, double radius, int ticks, int color) {
      shape(level, FxKinds.GEM_RAIN, p, center, Vec3.ZERO, radius, ticks, color);
   }

   public static void starTrail(ServerLevel level, ParticleOptions p, Vec3 center, double radius, int ticks, int color) {
      shape(level, FxKinds.STAR_TRAIL, p, center, Vec3.ZERO, radius, ticks, color);
   }

   public static void soulPillar(ServerLevel level, ParticleOptions p, Vec3 base, double height, int ticks, int color) {
      shape(level, FxKinds.SOUL_PILLAR, p, base, Vec3.ZERO, height, ticks, color);
   }
}
