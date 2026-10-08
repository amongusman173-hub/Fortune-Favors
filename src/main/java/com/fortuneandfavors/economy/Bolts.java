package com.fortuneandfavors.economy;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Spell projectiles that are not arrows: a star, a blood spear. The server sweeps each one along
 * its line tick by tick and the client draws it from one cue, so it looks like what it is, never
 * sticks in a wall, and lands its damage through the target's hurt-immunity window - a volley of
 * three used to lose two of its three hits to the first one's i-frames.
 */
public final class Bolts {
   private static final class Bolt {
      final ServerLevel level;
      final ServerPlayer owner;
      final Vec3 end;
      final Vec3 step;
      final float damage;
      final boolean pierce;
      final Set<Integer> hit = new HashSet<>();
      Vec3 pos;
      int ticks;

      Bolt(ServerLevel level, ServerPlayer owner, Vec3 from, Vec3 end, Vec3 step, int ticks, float damage, boolean pierce) {
         this.level = level;
         this.owner = owner;
         this.pos = from;
         this.end = end;
         this.step = step;
         this.ticks = ticks;
         this.damage = damage;
         this.pierce = pierce;
      }
   }

   private static final List<Bolt> BOLTS = new ArrayList<>();

   private Bolts() {
   }

   /** Fires a bolt from {@code from} along {@code dir}: stops at the first wall, or at {@code range}. */
   public static void fire(ServerLevel level, ServerPlayer owner, ParticleOptions look, Vec3 from, Vec3 dir, double speed, double range,
      float damage, boolean pierce, int color) {
      Vec3 d = dir.normalize();
      Vec3 end = level.clip(new ClipContext(from, from.add(d.scale(range)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, owner)).getLocation();
      int ticks = Math.max(1, (int)Math.ceil(from.distanceTo(end) / speed));
      // The client draws the whole flight from this one cue (a star head and its trail).
      Fx.shape(level, com.fortuneandfavors.net.FfVfx.METEOR, look, from, end, 0.0, ticks, color);
      BOLTS.add(new Bolt(level, owner, from, end, d.scale(speed), ticks, damage, pierce));
   }

   public static void tick() {
      for (Iterator<Bolt> it = BOLTS.iterator(); it.hasNext(); ) {
         Bolt b = it.next();
         Vec3 next = --b.ticks <= 0 ? b.end : b.pos.add(b.step);
         Vec3 seg = next.subtract(b.pos);
         boolean stop = false;
         for (LivingEntity e : b.level.getEntitiesOfClass(LivingEntity.class, new AABB(b.pos, next).inflate(0.8),
            x -> x.isAlive() && x != b.owner && !b.hit.contains(x.getId()) && !x.isSpectator())) {
            Vec3 at = e.position().add(0.0, e.getBbHeight() * 0.5, 0.0);
            double t = seg.lengthSqr() < 1.0E-6 ? 0.0 : Math.max(0.0, Math.min(1.0, at.subtract(b.pos).dot(seg) / seg.lengthSqr()));
            if (at.distanceTo(b.pos.add(seg.scale(t))) > 0.6 + e.getBbWidth() * 0.5 + e.getBbHeight() * 0.25) {
               continue;
            }
            if (e instanceof ServerPlayer other && !ClaimManager.pvpAllowed(b.owner, other)) {
               continue;
            }
            b.hit.add(e.getId());
            e.invulnerableTime = 0;   // through the i-frames: every bolt of a volley lands
            e.hurtServer(b.level, b.level.damageSources().playerAttack(b.owner), b.damage);
            if (!b.pierce) {
               stop = true;
               break;
            }
         }
         b.pos = next;
         if (stop || b.ticks <= 0 || !b.owner.isAlive()) {
            it.remove();
         }
      }
   }
}
