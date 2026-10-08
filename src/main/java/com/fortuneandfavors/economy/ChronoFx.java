package com.fortuneandfavors.economy;

import java.util.List;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The look and sound of stopped time, shared by everything that stops it.
 *
 * <p>Two completely separate systems stop time in this mod - the Time Lord's
 * scripted boss fight (a genuine server tick freeze) and the Pocket-Watch (a tick
 * freeze plus per-player pins) - and before this file they each drew their own
 * handful of particles with no relationship to each other. That is why a stop
 * "<i>worked</i>" but never felt like one: nothing told you a moment had been
 * taken out of the world, and a frozen arrow just looked like an arrow that
 * missed.
 *
 * <p>So the payoff is the same everywhere:
 * <ul>
 *   <li><b>Onset</b> - a white flash, three expanding rings, a hard clock-tick
 *       sting, and an action-bar caption. You should never wonder whether time
 *       stopped.</li>
 *   <li><b>Ambient</b> - while the moment is held, every frozen thing gets a mote
 *       of light on it (so a held arrow is <i>visible</i> as held), a clock hand
 *       sweeps around the frozen bubble, and a tick sounds every half second at a
 *       pitch that climbs as the stop runs out - the world counting itself back
 *       down.</li>
 *   <li><b>Release</b> - the rings collapse inward instead of outward and a
 *       shatter chime marks the moment being spent.</li>
 * </ul>
 *
 * <p>Every method here is defensive: it is called from tick paths that also run
 * during a frozen server, so nothing may throw, allocate unboundedly, or assume
 * the level still has players in it.
 */
public final class ChronoFx {
   /** Gold, the colour of the Time Lord's dust and of frozen second-hands. */
   private static final int GOLD = -4456443;

   private ChronoFx() {
   }

   // ------------------------------------------------------------------ onset

   /**
    * Time stops here: flash, rings, sting. {@code radius} is how far the rings
    * travel, so a pocket-watch bubble and a boss stop read the same way but at
    * different scales.
    */
   public static void stopOnset(ServerLevel level, Vec3 center, double radius) {
      if (level == null || center == null) {
         return;
      }
      // Everyone caught in it sees the world stop on their own screen (clients with the mod).
      double reach = radius * 1.5;
      for (ServerPlayer p : level.getPlayers(pl -> pl.distanceToSqr(center) < reach * reach)) {
         com.fortuneandfavors.net.FfNet.send(p, new com.fortuneandfavors.net.FfScreenFxPayload(com.fortuneandfavors.net.FfScreenFxPayload.FX_TIMESTOP, true));
      }
      double x = center.x;
      double y = center.y + 1.0;
      double z = center.z;

      com.fortuneandfavors.net.FfVfx.shape(level, com.fortuneandfavors.net.FfVfx.CLOCK_BURST, ParticleTypes.END_ROD, new Vec3(x, y, z), Vec3.ZERO, radius, 0.0, GOLD & 0xFFFFFF);
      com.fortuneandfavors.net.FfVfx.enter();
      try {
      // A brief white bloom sells "everything just stopped" better than any
      // quantity of dust. (Vanilla's FLASH particle is a colour-option type and
      // cannot be drawn through this overload, so the shutter is white dust.)
      com.fortuneandfavors.net.FfVfx.particles(level, new DustParticleOptions(-1, 2.2F), x, y, z, 40, 0.1, 0.1, 0.1, 0.4);

      // Three rings, drawn as real circles rather than a puff, because a circle
      // is legible as a wavefront from any angle.
      for (int ring = 0; ring < 3; ring++) {
         double r = radius * (0.25 + ring * 0.375);
         int points = 40 + ring * 20;
         for (int i = 0; i < points; i++) {
            double a = Math.PI * 2.0 * i / points;
            double px = x + Math.cos(a) * r;
            double pz = z + Math.sin(a) * r;
            com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.END_ROD, px, y, pz, 1, 0.0, 0.0, 0.0, 0.0);
            if (i % 4 == 0) {
               com.fortuneandfavors.net.FfVfx.particles(level, new DustParticleOptions(GOLD, 1.2F), px, y, pz, 1, 0.0, 0.0, 0.0, 0.0);
            }
         }
      }

      com.fortuneandfavors.net.FfVfx.particles(level, new DustParticleOptions(GOLD, 1.8F), x, y, z, 90, radius * 0.35, 1.4, radius * 0.35, 0.02);
      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.END_ROD, x, y, z, 60, radius * 0.25, 1.2, radius * 0.25, 0.03);

      } finally {
         com.fortuneandfavors.net.FfVfx.exit();
      }
      level.playSound(null, x, y, z, SoundEvents.BEACON_DEACTIVATE, SoundSource.HOSTILE, 1.4F, 0.4F);
      level.playSound(null, x, y, z, SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.HOSTILE, 1.3F, 0.5F);
      level.playSound(null, x, y, z, SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.HOSTILE, 1.2F, 0.6F);
   }

   // --------------------------------------------------------------- ambient

   /**
    * One frame of a held moment. Called on a timer, not every tick, so the cost
    * stays flat no matter how long a stop lasts.
    *
    * @param elapsed how many ticks the stop has been running
    * @param total   its full length, used for the clock face and the tick pitch
    */
   public static void stopAmbient(ServerLevel level, Vec3 center, double radius, int elapsed, int total) {
      if (level == null || center == null || total <= 0) {
         return;
      }
      double x = center.x;
      double y = center.y + 1.0;
      double z = center.z;

      // A clock face: twelve hour marks, and a hand that sweeps once per stop.
      double sweep = Math.min(1.0, elapsed / (double) total);
      com.fortuneandfavors.net.FfVfx.shape(level, com.fortuneandfavors.net.FfVfx.CLOCK, ParticleTypes.END_ROD, new Vec3(x, y, z), Vec3.ZERO, radius * 0.6, sweep, GOLD & 0xFFFFFF);
      com.fortuneandfavors.net.FfVfx.enter();
      try {
      double handAngle = Math.PI * 2.0 * sweep;
      for (int i = 0; i < 12; i++) {
         double a = Math.PI * 2.0 * i / 12.0;
         com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.END_ROD, x + Math.cos(a) * radius * 0.6, y, z + Math.sin(a) * radius * 0.6, 1, 0.0, 0.0, 0.0, 0.0);
      }
      for (int i = 1; i <= 8; i++) {
         double r = radius * 0.6 * (i / 8.0);
         com.fortuneandfavors.net.FfVfx.particles(level, new DustParticleOptions(GOLD, 0.9F), x + Math.cos(handAngle) * r, y, z + Math.sin(handAngle) * r, 1, 0.0, 0.0, 0.0, 0.0
         );
      }

      } finally {
         com.fortuneandfavors.net.FfVfx.exit();
      }

      // Motes on everything the stop is holding, so held things are visibly held.
      frostFrozen(level, center, radius);

      // The countdown, heard rather than read: a tick every half second, rising
      // in pitch as the stop runs out.
      if (elapsed % 10 == 0) {
         float pitch = 0.8F + 1.4F * (float) sweep;
         level.playSound(null, x, y, z, SoundEvents.NOTE_BLOCK_HAT.value(), SoundSource.HOSTILE, 0.5F, pitch);
      }
      if (elapsed % 40 == 0) {
         level.playSound(null, x, y, z, SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.HOSTILE, 0.35F, 0.5F + (float) sweep);
      }
   }

   /**
    * Puts a mote of light on every projectile, mob and dropped item inside the
    * bubble. This is the part that makes a stop <i>read</i>: an arrow hanging in
    * mid-air is otherwise identical to an arrow that flew past you.
    */
   public static void frostFrozen(ServerLevel level, Vec3 center, double radius) {
      if (level == null || center == null) {
         return;
      }
      AABB box = new AABB(center, center).inflate(radius);
      List<Entity> held = level.getEntities(
         (Entity) null,
         box,
         e -> e instanceof Projectile || e instanceof ItemEntity || e instanceof LivingEntity
      );
      for (Entity e : held) {
         if (e instanceof ServerPlayer) {
            // Players get their own treatment from the pin system; sparkling them
            // would just be noise around the one thing that is not really frozen.
            continue;
         }
         double ey = e.getY() + e.getBbHeight() * 0.5;
         boolean moving = e instanceof Projectile;
         com.fortuneandfavors.net.FfVfx.particles(level, moving ? ParticleTypes.END_ROD : ParticleTypes.SNOWFLAKE, e.getX(), ey, e.getZ(), moving ? 2 : 1, 0.12, 0.12, 0.12, 0.0
         );
         if (moving) {
            com.fortuneandfavors.net.FfVfx.particles(level, new DustParticleOptions(GOLD, 0.7F), e.getX(), ey, e.getZ(), 1, 0.1, 0.1, 0.1, 0.0);
         }
      }
   }

   // --------------------------------------------------------------- release

   /** Time resumes: the rings fall inward and the moment is spent. */
   public static void stopRelease(ServerLevel level, Vec3 center, double radius) {
      if (level == null || center == null) {
         return;
      }
      // Everyone in the level, not just whoever is still near: nobody may be left with a stopped screen.
      for (ServerPlayer p : level.players()) {
         com.fortuneandfavors.net.FfNet.send(p, new com.fortuneandfavors.net.FfScreenFxPayload(com.fortuneandfavors.net.FfScreenFxPayload.FX_TIMESTOP, false));
      }
      double x = center.x;
      double y = center.y + 1.0;
      double z = center.z;

      com.fortuneandfavors.net.FfVfx.shape(level, com.fortuneandfavors.net.FfVfx.NOVA, ParticleTypes.REVERSE_PORTAL, new Vec3(x, y - 1.0, z), Vec3.ZERO, radius * 0.9, 0.0, GOLD & 0xFFFFFF);
      com.fortuneandfavors.net.FfVfx.enter();
      try {
      for (int ring = 0; ring < 2; ring++) {
         double r = radius * (0.9 - ring * 0.35);
         int points = 36 - ring * 8;
         for (int i = 0; i < points; i++) {
            double a = Math.PI * 2.0 * i / points;
            com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.REVERSE_PORTAL, x + Math.cos(a) * r, y, z + Math.sin(a) * r, 1, 0.0, 0.0, 0.0, 0.0);
         }
      }

      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.REVERSE_PORTAL, x, y, z, 70, 1.2, 1.2, 1.2, 0.3);
      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.CLOUD, x, y, z, 30, 1.0, 0.6, 1.0, 0.08);
      com.fortuneandfavors.net.FfVfx.particles(level, new DustParticleOptions(GOLD, 1.4F), x, y, z, 50, 1.0, 1.0, 1.0, 0.06);
      } finally {
         com.fortuneandfavors.net.FfVfx.exit();
      }
      // The shatter: glass breaking is the perfect "the moment is spent" cue.
      level.playSound(null, x, y, z, SoundEvents.GLASS_BREAK, SoundSource.HOSTILE, 1.0F, 1.6F);
      level.playSound(null, x, y, z, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.HOSTILE, 1.2F, 1.8F);
      level.playSound(null, x, y, z, SoundEvents.BEACON_ACTIVATE, SoundSource.HOSTILE, 0.9F, 1.4F);
   }

   // ------------------------------------------------------------------ banner

   /**
    * Tells one player a moment has been taken out of the world.
    *
    * <p>This is deliberately an <b>action-bar</b> line, not a screen title. A
    * full-screen title is a giant slab of text over the middle of the view that
    * hides the very thing a stop is supposed to show you - the frozen arrow, the
    * frozen mob, the clock face on the ground - and it is drawn twice per stop
    * (once on onset, once on release), which reads as a title card being flashed
    * at the player rather than something happening in the fight. The stopped
    * moment is the spectacle; this is just the caption.
    */
   public static void banner(ServerPlayer player, String message) {
      if (player == null) {
         return;
      }
      try {
         player.sendOverlayMessage(Component.literal(message));
      } catch (Exception ignored) {
         // Cosmetic; a player mid-disconnect must never break the stop.
      }
   }

   /** The same caption to everyone in a list, used by the boss stop. */
   public static void bannerAll(Iterable<ServerPlayer> players, String message) {
      if (players == null) {
         return;
      }
      for (ServerPlayer p : players) {
         banner(p, message);
      }
   }

   /**
    * A ten-cell bar for the action bar, so a stopped player can read how much of
    * the moment is left without parsing a decimal.
    */
   public static String bar(int elapsed, int total) {
      if (total <= 0) {
         return "";
      }
      int filled = Math.max(0, Math.min(10, 10 - (int) ((long) elapsed * 10L / total)));
      StringBuilder sb = new StringBuilder(10);
      for (int i = 0; i < 10; i++) {
         sb.append(i < filled ? "\u2588" : "\u2591");
      }
      return sb.toString();
   }
}
