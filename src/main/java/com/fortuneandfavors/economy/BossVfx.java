package com.fortuneandfavors.economy;

import com.fortuneandfavors.util.ModPlatform;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Every big particle effect a boss (or a collapsing castle) sends, in one paced place.
 *
 * <h2>Why this exists</h2>
 * A boss fight is read off the screen: a ring is the shape of a slam, a column is where a strike is
 * landing. So the effects are load-bearing and cannot just be made smaller. What they can be is
 * <em>budgeted</em>, and that is the whole of this class.
 *
 * <p>Two things made that necessary. The first is cost: {@code level.sendParticles} fans a count out
 * to every player who can see it, and the shapes these fights draw - rings, spheres, walls of water -
 * are hundreds of particles each, several times a tick, for several abilities at once. None of that
 * was ever wrong on a desktop client and all of it was expensive on the server, which is where the
 * packets are actually built.
 *
 * <p>The second is Bedrock, and it is the sharper of the two. A Bedrock client arrives through
 * Geyser, which translates each Java particle into a Bedrock one - a single packet per particle, on
 * a protocol with an order of magnitude less headroom than Java's. A shape that a Java client draws
 * as one comfortable burst arrives at a Bedrock client as thousands of individual particles, and the
 * client stops answering. That is not a slow frame; it is a player who cannot move until it drains,
 * which is what "the fade spams particles and crashes the other person" was.
 *
 * <h2>The rules</h2>
 * <ul>
 *   <li><b>Bedrock gets a smaller, sparser version of the same shape.</b> The count is divided and the
 *       player's own per-tick budget is small, but the shape is still drawn - a boss cannot have
 *       attacks that are invisible to some of the server.</li>
 *   <li><b>Everyone shares a per-tick budget.</b> When it is spent, later effects in the same tick
 *       are dropped rather than queued: the alternative is a spike that arrives all at once.</li>
 *   <li><b>The budget is reset once per server tick</b> ({@link #beginTick}), so a quiet tick always
 *       has room and no fight can accumulate credit for a bigger burst later.</li>
 * </ul>
 *
 * <p>Shapes are built from {@link #ring}, {@link #wall}, {@link #sphere}, {@link #beam} and
 * {@link #column}, which are the five a fight actually needs: where something is about to land, what
 * it will cover when it does, and which way it is going.
 */
public final class BossVfx {
   /**
    * Particles one Java client may be sent in a single tick before the rest are dropped.
    *
    * <p>The size is set by the biggest shape in the mod, not by a guess at frame budget. A shape
    * here is a loop of {@code at} calls - one per site - so a budget that runs out part way through
    * a wave does not make the wave smaller, it makes it <em>half a wave</em>: the columns up to the
    * cut draw and every column after it is dropped, and which half you see depends on where the loop
    * happened to start.
    *
    * <p>The arithmetic is the sea boss's worldbreaker tide, which is the largest single shape in the
    * mod: a wall 38 columns across and 7 high, 266 sites, five particles each, 1330 in one call -
    * plus the 113 his own presence costs on the same tick. At the old 240 that wall was drawn across
    * its first ninth and nothing after it, which is what "the ability has no VFX" means when the
    * effect is in fact being sent: a fifth of a wall, from one edge, is not a smaller wall. 1600
    * fits that shape whole with room for the ambient effects around it, and the graceful thinning
    * below means a tick that stacks two abilities at once degrades evenly instead of being cut off.
    */
   public static final int BUDGET = 1600;
   /**
    * What a Bedrock client gets, as a fraction and as a ceiling.
    *
    * <p>Divided rather than removed: at a fifth of the count a ring still reads as a ring, and the
    * per-tick ceiling is what matters - it is the difference between a client that keeps up and a
    * client that is being handed a queue it cannot drain.
    */
   public static final int BEDROCK_DIVISOR = 5;
   public static final int BEDROCK_BUDGET = 48;
   /**
    * Gust emitters one player may be sent in a single tick, out of the {@link #BUDGET} above.
    *
    * <p>Emitters are not particles the way the rest of this class means it. A {@code gust_emitter_*}
    * is a <i>seed</i>: it lives for a second or more and spends that life spawning gusts of its own,
    * so one of them costs a client dozens of particles and a long-lived entry in its particle
    * engine. A ring of ninety of them - which is exactly what the sky boss's hurricane drew - is
    * therefore not ninety particles, it is several thousand, arriving every time the ability
    * fires. That is the lag report, and it is a different bug from "too many particles" with a
    * different fix: the count that matters is the count of seeds.
    *
    * <p>Forty-eight is set by the largest legitimate use rather than by taste. The ender dragon
    * draws emitters in small clusters of two to eight through the same funnel, several times a tick
    * when its phases stack, and the sea and mirage bosses use a handful for their own weather; none
    * of them comes close to this. What it cuts is the one thing that does - a single shape asking
    * for fifty or ninety seeds in one call - and it cuts it evenly through the existing thinning
    * rather than chopping the ring in half.
    */
   public static final int EMITTER_BUDGET = 48;

   private static final Map<UUID, Integer> EMITTERS = new HashMap<>();

   /**
    * Whether one more gust emitter may be drawn for a player who has spent {@code already} of their
    * seed allowance this tick. The decision on its own, so it can be driven without a world.
    */
   public static boolean emitterAllowance(int already) {
      return already < EMITTER_BUDGET;
   }

   /** True when this particle is a gust seed rather than a puff - the expensive kind. */
   public static boolean isEmitter(ParticleOptions type) {
      return type == ParticleTypes.GUST_EMITTER_SMALL || type == ParticleTypes.GUST_EMITTER_LARGE;
   }

   /** Test hook: how many gust seeds this player has been sent this tick. */
   public static int emittersForTest(UUID id) {
      return EMITTERS.getOrDefault(id, 0);
   }

   private static final Map<UUID, Integer> SPENT = new HashMap<>();
   /**
    * How many draws this player has been offered this tick, spent or dropped.
    *
    * <p>It exists so the thinning below can be spread across a shape instead of applied to its
    * head: site one, three, five... draw and the rest are skipped, which leaves a sparser ring
    * rather than a ring with a bite out of it.
    */
   private static final Map<UUID, Integer> TICKET = new HashMap<>();

   private BossVfx() {
   }

   /** Zeroes every player's budget. Called once per server tick, before anything draws. */
   public static void beginTick() {
      if (!SPENT.isEmpty()) {
         SPENT.clear();
      }
      if (!TICKET.isEmpty()) {
         TICKET.clear();
      }
      if (!EMITTERS.isEmpty()) {
         EMITTERS.clear();
      }
   }

   /** Forgets one player's spending, for a client that has just joined mid-effect. */
   public static void forget(UUID id) {
      SPENT.remove(id);
      TICKET.remove(id);
      EMITTERS.remove(id);
   }

   /**
    * Test hook: what this player's share of the tick has been spent on, in particles.
    *
    * <p>Read from the ledger rather than argued about, because the whole failure mode here is
    * invisible: a budget that is never reset does not throw, log or warn - it just stops drawing,
    * and every boss in the mod reads as having no effects.
    */
   public static int spentForTest(UUID id) {
      return SPENT.getOrDefault(id, 0);
   }

   /**
    * Test hook: the thinning a player is on at {@code spent} of their share - 0 for "every site",
    * 4 for "every fourth site", 2 for "every second site".
    */
   public static int thinningForTest(int spent, int budget) {
      return thinning(spent, budget);
   }

   /**
    * How sparsely a player's effects are drawn at {@code spent} of their {@code budget}: 0 means
    * every site draws, 4 means one site in four, 2 means one in two. The schedule is the same
    * wherever it is asked for, which is why it lives here and not at the two call sites.
    */
   private static int thinning(int spent, int budget) {
      if (budget <= 0) {
         return 0;
      }
      double used = (double)spent / budget;
      return used < 0.75 ? 0 : used < 0.9 ? 4 : 2;
   }

   /**
    * Draws {@code type} at one point, scaled for every player who can see it.
    *
    * <p>A zero radius means "whoever is looking at this effect is already in the same arena": the
    * whole level's players are considered, which is what a boss wants - the player running away from
    * the fight still sees where the wave is.
    */
   public static void at(
      ServerLevel level,
      Vec3 pos,
      double radius,
      ParticleOptions type,
      int count,
      double dx,
      double dy,
      double dz,
      double speed
   ) {
      if (level == null || pos == null || type == null || count <= 0) {
         return;
      }
      for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
         if (p.level() != level) {
            continue;
         }
         if (radius > 0.0 && p.distanceToSqr(pos.x, pos.y, pos.z) > radius * radius) {
            continue;
         }
         to(p, type, pos.x, pos.y, pos.z, count, dx, dy, dz, speed);
      }
   }

   /** Convenience for the common case: a point built from three doubles. */
   public static void at(
      ServerLevel level,
      double x,
      double y,
      double z,
      double radius,
      ParticleOptions type,
      int count,
      double dx,
      double dy,
      double dz,
      double speed
   ) {
      at(level, new Vec3(x, y, z), radius, type, count, dx, dy, dz, speed);
   }

   /**
    * Draws {@code type} for one player, or nothing at all if their budget for this tick is spent.
    *
    * <p>This is the only place a boss effect should reach a player from. Everything above funnels
    * here so there is exactly one answer to "how many particles is this client being sent", and it
    * is a number rather than a hope.
    */
   public static void to(
      ServerPlayer p,
      ParticleOptions type,
      double x,
      double y,
      double z,
      int count,
      double dx,
      double dy,
      double dz,
      double speed
   ) {
      if (p == null || type == null || count <= 0) {
         return;
      }
      if (!(p.level() instanceof ServerLevel level)) {
         return;
      }
      boolean bedrock = ModPlatform.isBedrock(p.getUUID());
      int budget = bedrock ? BEDROCK_BUDGET : BUDGET;
      int want = bedrock ? Math.max(1, count / BEDROCK_DIVISOR) : count;
      int spent = SPENT.getOrDefault(p.getUUID(), 0);
      int left = budget - spent;
      if (left <= 0) {
         return;
      }
      // The seed allowance sits inside the ordinary one and is spent first, because a shape that
      // asks for a hundred seeds has to be told no before it has taken the whole tick: the sites it
      // does not get become nothing, and the expensive particle never enters a client's engine.
      // See EMITTER_BUDGET for why this is a separate number at all.
      boolean seed = isEmitter(type);
      if (seed) {
         int seeds = EMITTERS.getOrDefault(p.getUUID(), 0);
         if (!emitterAllowance(seeds)) {
            return;
         }
         EMITTERS.put(p.getUUID(), seeds + want);
      }
      // Past three quarters of the share, every fourth site is skipped; past ninety per cent, every
      // second. A tick that heavy is a boss landing three abilities at once, and the choice is
      // between an effect nobody sees and the same effect drawn at half density - so it thins, and
      // it thins evenly, rather than filling the budget with whichever shape asked first.
      int ticket = TICKET.merge(p.getUUID(), 1, Integer::sum);
      // Bedrock thins by *site* as well as by count, and it is the site stride that matters: with
      // only the count divided, a 266-site shape still sends 266 packets, so the client's ceiling
      // cut the shape off at whatever site the budget ran out on - the same half-a-wave the Java
      // budget used to draw, on the client least able to afford it. Every fifth site drawn spreads
      // the same shape across its whole width for the same number of packets.
      if (bedrock && ticket % BEDROCK_DIVISOR != 0) {
         return;
      }
      int skip = thinning(spent, budget);
      if (skip > 0 && ticket % skip != 0) {
         return;
      }
      int give = Math.min(want, left);
      if (seed && give < want) {
         // What the allowance actually paid for, so the ledger does not over-count seeds that were
         // dropped by the outer budget after the inner one had already agreed to them.
         EMITTERS.put(p.getUUID(), EMITTERS.getOrDefault(p.getUUID(), 0) - (want - give));
      }
      SPENT.put(p.getUUID(), spent + give);
      // One packet to one connection, with the same visibility flags vanilla's own level-wide send
      // uses (false, false) - the whole point of going per player here is that the count can differ
      // per player, which a broadcast cannot express.
      level.sendParticles(p, type, false, false, x, y, z, give, dx, dy, dz, speed);
   }

   // ------------------------------------------------------------------- shapes

   /**
    * A ring on the ground: where something is about to land, or what it covered when it did.
    *
    * <p>{@code points} particles around a circle of {@code radius}, jittered by nothing at all -
    * a tell that wobbles is a tell that is harder to read than the attack it warns about.
    */
   public static void ring(
      ServerLevel level, Vec3 center, double radius, int points, ParticleOptions type, double y
   ) {
      if (level == null || center == null || radius <= 0.0 || points <= 0) {
         return;
      }
      int step = Math.max(1, points / 8);
      for (int i = 0; i < points; i += step) {
         double a = (Math.PI * 2.0 * i) / points;
         double x = center.x + Math.cos(a) * radius;
         double z = center.z + Math.sin(a) * radius;
         at(level, x, center.y + y, z, 0.0, type, step, 0.06, 0.04, 0.06, 0.01);
      }
   }

   /** A filled disc of particles - the interior of a wave, a pool, a hazard field. */
   public static void disc(ServerLevel level, Vec3 center, double radius, ParticleOptions type, double y) {
      if (level == null || center == null || radius <= 0.0) {
         return;
      }
      double step = 1.6;
      for (double dx = -radius; dx <= radius; dx += step) {
         for (double dz = -radius; dz <= radius; dz += step) {
            if (dx * dx + dz * dz > radius * radius) {
               continue;
            }
            at(level, center.x + dx, center.y + y, center.z + dz, 0.0, type, 2, 0.1, 0.08, 0.1, 0.01);
         }
      }
   }

   /**
    * A wall of particles across a line, facing {@code facing} - a wave sweeping a room.
    *
    * <p>Built as a line perpendicular to the direction of travel, so a wave's leading edge reads as
    * an edge rather than as a blob.
    */
   public static void wall(
      ServerLevel level, Vec3 center, Vec3 facing, double halfWidth, double height, ParticleOptions type, int counts
   ) {
      if (level == null || center == null || type == null) {
         return;
      }
      Vec3 flat = new Vec3(facing.x, 0.0, facing.z);
      if (flat.lengthSqr() < 1.0E-4) {
         flat = new Vec3(1.0, 0.0, 0.0);
      }
      Vec3 flatUnit = flat.normalize();
      Vec3 across = new Vec3(-flatUnit.z, 0.0, flatUnit.x);
      double step = 1.4;
      for (double d = -halfWidth; d <= halfWidth; d += step) {
         for (double h = 0.0; h <= height; h += 1.0) {
            Vec3 p = center.add(across.scale(d)).add(0.0, h, 0.0);
            at(level, p.x, p.y, p.z, 0.0, type, counts, 0.12, 0.12, 0.12, 0.02);
         }
      }
   }

   /** A sphere of particles around a point - a prison, a pressure bubble, a vacuum. */
   public static void sphere(ServerLevel level, Vec3 center, double radius, int shells, ParticleOptions type) {
      if (level == null || center == null || radius <= 0.0 || shells <= 0) {
         return;
      }
      int points = 26;
      for (int s = 0; s < shells; s++) {
         double r = radius * (0.55 + 0.45 * ((double)s / Math.max(1, shells - 1)));
         for (int i = 0; i < points; i++) {
            double a = Math.toRadians((360.0 * i) / points);
            double b = Math.toRadians((180.0 * (i * 7 % points)) / points);
            double x = center.x + Math.cos(a) * Math.sin(b) * r;
            double y = center.y + Math.cos(b) * r * 0.6;
            double z = center.z + Math.sin(a) * Math.sin(b) * r;
            at(level, x, y, z, 0.0, type, 2, 0.05, 0.05, 0.05, 0.0);
         }
      }
   }

   /** A vertical column - a strike coming down, or a thing being pulled up. */
   public static void column(ServerLevel level, Vec3 base, double height, ParticleOptions type, int counts) {
      if (level == null || base == null || type == null) {
         return;
      }
      for (double h = 0.0; h <= height; h += 0.8) {
         at(level, base.x, base.y + h, base.z, 0.0, type, counts, 0.22, 0.06, 0.22, 0.01);
      }
   }

   /** A line between two points - a beam, a chain, a tentacle reaching. */
   public static void beam(ServerLevel level, Vec3 from, Vec3 to, double thickness, ParticleOptions type) {
      if (level == null || from == null || to == null || type == null) {
         return;
      }
      double length = from.distanceTo(to);
      if (length < 0.05) {
         return;
      }
      int steps = (int)Math.max(1.0, length);
      for (int i = 0; i <= steps; i++) {
         Vec3 p = from.add(to.subtract(from).scale((double)i / steps));
         at(level, p.x, p.y, p.z, 0.0, type, 2, thickness, thickness, thickness, 0.0);
      }
   }

   /** An upward spray, for water breaking on something. */
   public static void splash(ServerLevel level, Vec3 center, double radius, int count) {
      at(level, center, radius + 2.0, ParticleTypes.SPLASH, count, radius * 0.5, 0.6, radius * 0.5, 0.35);
      at(level, center, radius + 2.0, ParticleTypes.BUBBLE_POP, Math.max(1, count / 3), radius * 0.4, 0.4, radius * 0.4, 0.2);
   }
}
