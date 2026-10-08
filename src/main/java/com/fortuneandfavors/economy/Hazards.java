package com.fortuneandfavors.economy;

import com.fortuneandfavors.util.FxKinds;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

/**
 * Timed zone attacks a boss drops on the floor: the Drowned Sovereign's whirlpools and the Gale
 * Warden's storm cells. The client draws each from one cue; the server pulls and strikes here.
 */
public final class Hazards {
   private static final int WHIRLPOOL = 0;
   private static final int STORM = 1;

   private static final class Zone {
      final int kind;
      final ServerLevel level;
      final Mob boss;
      final Vec3 at;
      final double radius;
      final float damage;
      int age;
      final int life;

      Zone(int kind, ServerLevel level, Mob boss, Vec3 at, double radius, float damage, int life) {
         this.kind = kind;
         this.level = level;
         this.boss = boss;
         this.at = at;
         this.radius = radius;
         this.damage = damage;
         this.life = life;
      }
   }

   private static final List<Zone> ZONES = new ArrayList<>();

   private Hazards() {
   }

   /** WHIRLPOOL: drags everyone near toward its eye for {@code ticks}, then the eye crushes. */
   public static void whirlpool(ServerLevel level, Mob boss, Vec3 floor, double radius, int ticks, float damage, int color) {
      Fx.whirlpool(level, ParticleTypes.SPLASH, floor, radius, ticks, color);
      ZONES.add(new Zone(WHIRLPOOL, level, boss, floor, radius, damage, ticks));
   }

   /** STORM CELL: a cloud over the spot strikes it every 10 ticks, three times. */
   public static void stormCell(ServerLevel level, Mob boss, Vec3 floor, double radius, float damage, int color) {
      Fx.stormCell(level, ParticleTypes.ELECTRIC_SPARK, floor, radius, 32, color);
      ZONES.add(new Zone(STORM, level, boss, floor, radius, damage, 32));
   }

   public static void tick() {
      for (Iterator<Zone> it = ZONES.iterator(); it.hasNext(); ) {
         Zone z = it.next();
         if (!z.boss.isAlive() || ++z.age > z.life) {
            it.remove();
            continue;
         }
         List<ServerPlayer> near = z.level.getPlayers(p -> p.isAlive() && !p.isSpectator() && !p.isCreative() && p.distanceToSqr(z.at) < z.radius * z.radius * 2.6);
         if (z.kind == WHIRLPOOL) {
            for (ServerPlayer p : near) {
               Vec3 in = z.at.subtract(p.position());
               if (in.horizontalDistanceSqr() > 0.2) {
                  Vec3 flat = new Vec3(in.x, 0.0, in.z).normalize();
                  p.push(flat.x * 0.07, 0.0, flat.z * 0.07);
                  p.hurtMarked = true;
               }
            }
            if (z.age == z.life) {
               for (ServerPlayer p : near) {
                  if (p.distanceToSqr(z.at) < 2.2 * 2.2) {
                     p.hurtServer(z.level, z.level.damageSources().mobAttack(z.boss), z.damage);
                     p.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 60, 1));
                  }
               }
               Fx.shape(z.level, com.fortuneandfavors.net.FfVfx.GEYSER, ParticleTypes.SPLASH, z.at, Vec3.ZERO, 4.0, 0.0, 0xD8F4FF);
               z.level.playSound(null, z.at.x, z.at.y, z.at.z, SoundEvents.GENERIC_SPLASH, SoundSource.HOSTILE, 1.6F, 0.6F);
            }
         } else if (z.age % 10 == 9) {
            for (ServerPlayer p : near) {
               if (p.distanceToSqr(z.at) < 2.5 * 2.5) {
                  p.hurtServer(z.level, z.level.damageSources().lightningBolt(), z.damage);
               }
            }
            z.level.playSound(null, z.at.x, z.at.y, z.at.z, SoundEvents.LIGHTNING_BOLT_IMPACT, SoundSource.HOSTILE, 1.4F, 1.2F);
         }
      }
   }
}
