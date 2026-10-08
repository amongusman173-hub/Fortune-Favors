package com.fortuneandfavors.economy;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.phys.Vec3;

/** The "Sonic Boom": break the sound barrier while gliding on an elytra.
 *
 *  <p>Three rules, and they are deliberately simple:
 *  <ul>
 *    <li><b>Charging is easy.</b> Any glide fast enough to be real flight
 *        charges the boom (a firework glide sits right at the edge of the old
 *        1.7 blocks/tick gate, which is why it so often never registered).
 *        Drop out of the sky and it charges in well under a second.</li>
 *    <li><b>Once per flight.</b> There is no 60-second lockout any more. Fire
 *        a boom and the next one is armed the moment you stop gliding - land,
 *        grab a ledge, release the wings - so a long trip can boom again and
 *        again, but never twice inside the same glide.</li>
 *    <li><b>It really is faster than normal.</b> The launch is a hard shove
 *        well past boosted-firework speed, in your look direction, so the boom
 *        reads as a burst of speed instead of a nudge.</li>
 *  </ul>
 *
 *  Fully server-side: vanilla clients see and hear everything. */
public final class SonicBoom {
   private static final Map<UUID, State> STATE = new HashMap<>();

   /** Blocks/tick that count as "I am actually flying". Boosted elytra flight
    *  hovers around 1.5-2.0 and a plain glide sits under 1.0, so 0.5 accepts
    *  both without ever counting a standing player. */
   private static final double TRIGGER_SPEED = 0.5;
   /** Ticks of flight the first boom of a flight needs. */
   private static final int CHARGE_TICKS = 14;
   /** Later booms in the same flight charge a little faster. */
   private static final int RECHARGE_TICKS = 8;
   /** Guard so a single boom can never fire twice in the same instant (the
    *  charge counter is already reset on re-arm; this is just belt and
    *  braces against two of our own ticks landing on one frame). */
   private static final int SPAM_GUARD_TICKS = 30;
   /** The launch lasts about a second. */
   private static final int BOOST_TICKS = 22;
   /** Launch speed in blocks/tick, kept up for the whole boost. A firework
    *  glide is ~1.5-2.0, so this is unmistakably "faster than normal". */
   private static final double BOOST_SPEED = 3.4;
   /** How much of your existing momentum the launch keeps (the rest is
    *  replaced by the look-direction shove, so it can't be aimed backwards). */
   private static final double MOMENTUM_KEEP = 0.25;

   private SonicBoom() {
   }

   private static final class State {
      boolean usedThisFlight;
      int numberToBoom = CHARGE_TICKS;
      int speedBoostDuration;
      long guardUntil;
      long lastBoomAt;
      Vec3 baseVelocity = Vec3.ZERO;
   }

   public static void tick(ServerPlayer p) {
      State s = STATE.computeIfAbsent(p.getUUID(), k -> new State());
      boolean flying = p.isFallFlying();
      double speed = p.getDeltaMovement().length();
      long now = p.level().getGameTime();

      if (!flying) {
         // Wings away: the next take-off is earned. This is the whole
         // "once per flight" rule - landing (or letting go of the glide) is
         // what arms the next boom, not a timer.
         if (s.usedThisFlight) {
            s.usedThisFlight = false;
            // Don't say this in the second the boom itself lands (the launch
            // briefly reads as "no longer flying"): it is a recharge hint, not
            // a post-boom notification, and the boom already announced itself.
            if (now - s.lastBoomAt > 40L) {
               bar(p, "§bWings folded - glide again for another §lSONIC BOOM§b.");
            }
         }
         s.numberToBoom = CHARGE_TICKS;
      } else if (!s.usedThisFlight && s.speedBoostDuration <= 0 && now >= s.guardUntil && speed > TRIGGER_SPEED) {
         s.numberToBoom--;
      }

      // Launch phase: hold the player at boom speed along their look vector.
      if (s.speedBoostDuration > 0) {
         Vec3 facing = p.getLookAngle();
         Vec3 drift = s.baseVelocity.scale(MOMENTUM_KEEP);
         Vec3 target = facing.scale(BOOST_SPEED).add(drift);
         p.setDeltaMovement(target);
         // Never touch onGround() here: a player who is still fall-flying and is
         // marked as standing on the ground has their elytra flight cancelled, so
         // the boom used to *stop* the glide it was supposed to launch. The only
         // thing the launch should force is the velocity.
         p.fallDistance = 0.0F;
         p.hurtMarked = true;
         s.speedBoostDuration--;
      }

      if (flying && !s.usedThisFlight && s.speedBoostDuration <= 0 && now >= s.guardUntil && s.numberToBoom <= 0) {
         boom(p, s, now);
      }
   }

   private static void boom(ServerPlayer p, State s, long now) {
      s.speedBoostDuration = BOOST_TICKS;
      s.baseVelocity = p.getDeltaMovement();
      s.usedThisFlight = true;
      s.guardUntil = now + SPAM_GUARD_TICKS;
      s.lastBoomAt = now;
      s.numberToBoom = RECHARGE_TICKS;

      var level = p.level();
      double x = p.getX();
      double y = p.getY();
      double z = p.getZ();
      level.playSound(null, x, y, z, SoundEvents.WARDEN_SONIC_BOOM, SoundSource.PLAYERS, 30.0F, 1.4F);
      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.SONIC_BOOM, x, y + 1.0, z, 1, 0.0, 0.0, 0.0, 0.0);
      for (int i = 0; i < 24; i++) {
         double a = Math.PI * 2.0 * i / 24.0;
         com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.ELECTRIC_SPARK, x + Math.cos(a), y + 0.3, z + Math.sin(a), 1, 0.0, 0.05, 0.0, 0.0);
      }
      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.CLOUD, x, y + 0.8, z, 10, 0.4, 0.3, 0.4, 0.02);
      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.GUST, x, y + 0.8, z, 4, 0.4, 0.3, 0.4, 0.02);

      bar(p, "§b§lSONIC BOOM! §r§7Land, or fold your wings, to charge another.");

      // First boom ever: the Sonic BOOOOM! advancement (once per player).
      CustomData got = (CustomData)p.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
      if (!got.copyTag().getBoolean("ff_sonic_boom_got").orElse(false)) {
         CompoundTag tag = got.copyTag();
         tag.putBoolean("ff_sonic_boom_got", true);
         p.setComponent(DataComponents.CUSTOM_DATA, CustomData.of(tag));
         Advancements.grant(p, "sonic_boom");
      }
   }

   private static void bar(ServerPlayer p, String text) {
      p.sendSystemMessage(Component.literal(text), true);
   }

   /** Dropped when a player leaves so the map cannot grow forever. */
   public static void forget(UUID id) {
      STATE.remove(id);
   }
}
