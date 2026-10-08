package com.fortuneandfavors.economy;

import com.fortuneandfavors.ModItems;
import com.fortuneandfavors.ModSounds;
import com.fortuneandfavors.util.Safe;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.BossEvent.BossBarColor;
import net.minecraft.world.BossEvent.BossBarOverlay;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * <b>The Gale Warden</b> - the breeze boss, and not a bigger breeze.
 *
 * <h2>The gimmick is momentum, not wind</h2>
 * He does not throw wind at you. He takes <em>how you are moving</em> and decides what happens to it.
 * Momentum Theft banks your own movement and hands it back as one launch; Reversal turns being
 * knocked back into being dragged in; Momentum Swap mirrors two players' movement onto each other;
 * Zero Point stops everything dead and then returns every stored scrap of it at once. A player who
 * stands still has nothing for him to steal, which is the fight's whole counterplay - and the reason
 * his phase-three moves start taking the choice away.
 *
 * <h2>Phases</h2>
 * <ul>
 *   <li><b>I - The Warden</b>: Momentum Theft, Reversal, Windstep, Dead Air, Pressure Point,
 *       Gale Counter.</li>
 *   <li><b>II - The Broken Sky</b> (60%): Sky Launch, Cyclone Dash, Momentum Swap, Falling Sky,
 *       Vacuum.</li>
 *   <li><b>III - The Gale</b> (20%): No Ground, Zero Point, Heaven's Drop, Four Winds,
 *       Absolute Momentum.</li>
 * </ul>
 *
 * <h2>He says nothing</h2>
 * He is the one boss in the mod who never sends a chat line - not a summon banner, not a taunt,
 * not a line when a phase turns over. The action bar still names each move as it starts, because
 * that is the fight telling you what is about to happen rather than the boss talking about it, and
 * a Warden who narrates his own moves is a Warden you read instead of watch. {@code ffAuditSources}
 * fails the build if a single chat sender comes back into this file.
 *
 * <h2>Two things make him answerable</h2>
 * Every launch in the list is a <em>telegraph</em>: the columns erupt on marks you can see, the
 * charges fall after a visible delay, and Absolute Momentum takes a hundred ticks of charging that
 * enough damage interrupts outright. And Gale Counter exists so the fight cannot be won by holding
 * attack through everything - hit him while he is braced and he throws the blow back at you, from
 * the side you are standing on.
 */
public final class GaleWardenManager {

   private static final String TAG = "ff_gale_warden";
   private static final String BOSS_NAME = "\u00a7f\u00a7l\uD83C\uDF2A The Gale Warden";

   private static final double MAX_HEALTH = 680.0;
   private static final double SEEK_RANGE = 90.0;
   /** See {@code ClockworkKingManager.STRAY_SWEEP_RADIUS}: just wider than the widest arena. */
   public static final double STRAY_SWEEP_RADIUS = 96.0;

   private static final double PHASE_2_AT = 0.60;
   private static final double PHASE_3_AT = 0.20;

   private static final int MOVE_GAP_1 = 70;
   private static final int MOVE_GAP_2 = 54;
   private static final int MOVE_GAP_3 = 40;

   /** How high above the floor he sits, and how fast he drifts at you. */
   private static final double HOVER = 2.2;
   private static final double DRIFT = 0.14;

   /** Momentum Theft: how long he banks your movement, and how hard he can hand it back. */
   private static final int THEFT_TICKS = 70;
   private static final double THEFT_RELEASE = 1.35;
   private static final double THEFT_CAP = 3.4;

   private static final int REVERSAL_TICKS = 70;
   private static final double REVERSAL_PULL = 0.16;

   private static final int DEAD_AIR_TICKS = 80;
   private static final double DEAD_AIR_RADIUS = 9.0;
   private static final double DEAD_AIR_SLOW = 0.55;
   private static final double DEAD_AIR_RELEASE = 2.6;

   private static final double COLUMN_RADIUS = 2.6;
   private static final float COLUMN_DAMAGE = 12.0F;

   private static final int STANCE_TICKS = 45;
   private static final float COUNTER_DAMAGE = 11.0F;
   private static final double COUNTER_PUSH = 2.6;

   private static final int LINK_TICKS = 80;
   private static final double LINK_SHARE = 0.9;

   private static final int SKYFALL_COUNT = 8;
   private static final float SKYFALL_DAMAGE = 10.0F;

   private static final int TUNNEL_TICKS = 90;
   private static final double TUNNEL_PUSH = 1.5;

   private static final int AERIAL_TICKS = 120;

   private static final int ZERO_POINT_TICKS = 40;
   private static final double ZERO_POINT_RELEASE = 2.2;

   private static final int CURRENT_TICKS = 140;
   private static final double CURRENT_PUSH = 0.55;

   private static final float FOUR_WINDS_DAMAGE = 7.0F;

   private static final int CHARGE_TICKS = 100;
   private static final float CHARGE_BREAK_DAMAGE = 60.0F;
   private static final float ABSOLUTE_DAMAGE = 22.0F;
   private static final double ABSOLUTE_RADIUS = 34.0;
   private static final double ABSOLUTE_LAUNCH = 3.2;
   /**
    * The most one hit may add upward, in blocks a tick, and how long the sky may hold a body.
    *
    * <p>Every launcher in this fight is a <i>horizontal</i> move with an upward garnish - a dash, a
    * blast, a shove - and the garnish is what makes them readable: you are thrown across the arena,
    * not lifted out of it. Left uncapped they add up instead. Two moves close together put a body
    * twenty blocks up with its own arc, a third arrives before it has come down, and what a player
    * experiences is a boss with no way back to the floor rather than a boss that throws them about.
    *
    * <p>So one rule with one owner. {@link #liftFor} caps a single lift at {@link #LIFT_MAX}, and
    * once a body has been off the ground for {@link #LIFT_CEILING_TICKS} the garnish pays nothing at
    * all and whatever climb it still has is bled off - gravity gets the body back, every time,
    * whatever the boss is in the middle of. Horizontal impulse is untouched: the fight is still
    * about momentum, and a body thrown flat across the room lands on its own.
    */
   public static final double LIFT_MAX = 1.2;
   public static final int LIFT_CEILING_TICKS = 60;

   /**
    * What is left of a lift for a body that has been airborne this long.
    *
    * <p>Full for a launch from the ground, tapering over the last second of the window and nothing
    * at all once the window is spent, so the ladder of lifts reads as a fight pressing and then
    * letting go rather than as a wall it stops at.
    */
   public static double liftFor(double up, int airTicks) {
      double capped = Math.max(0.0, Math.min(LIFT_MAX, up));
      int left = LIFT_CEILING_TICKS - Math.max(0, airTicks);
      if (left <= 0) {
         return 0.0;
      }
      int taper = LIFT_CEILING_TICKS / 3;
      return left >= taper ? capped : capped * (double)left / (double)taper;
   }

   /**
    * A tornado: a moving column that a player is dragged into and then lifted by.
    *
    * <p>The first of his moves that is a <em>place</em> rather than a moment - and the reason it is
    * here at all is that his other early moves all read as directions. A column that wanders the
    * arena for ten seconds gives the fight something to walk around, which is the one kind of
    * pressure momentum theft cannot apply: it does not care how fast you are moving, only that you
    * are near it.
    */
   private static final int TORNADO_TICKS = 200;
   private static final double TORNADO_RADIUS = 5.0;
   private static final double TORNADO_PULL = 0.34;
   private static final double TORNADO_LIFT = 0.30;
   private static final float TORNADO_DAMAGE = 6.0F;
   /** What one twister rolls into whenever its life crosses a multiple of this. */
   private static final int TORNADO_WANDER_TICKS = 20;

   /** How high a column of wind is drawn - the whole shape, above the base of it. */
   private static final double TORNADO_HEIGHT = 9.0;

   /**
    * Hurricane: the finale. A storm the width of the arena that leans on everything at once, and
    * then lets go of all of it.
    *
    * <p>Absolute Momentum is still the interruptible one - this is its opposite number, a move with
    * no check to pass and no way to stop it, which is why the pull is the readable part: it is a
    * whole move spent telling you where the centre is.
    */
   private static final int HURRICANE_TICKS = 220;
   private static final double HURRICANE_RADIUS = 34.0;
   /**
    * The particle the sky boss's big rings are drawn with, and how many sites each ring has.
    *
    * <p>These were {@code GUST_EMITTER_LARGE} in counts of fifty-six, seventy-two, ninety and
    * ninety-six, and two of those rings are drawn <b>every tick</b> for as long as the move is
    * running - the hurricane alone was 1,920 gust seeds a second for eleven seconds. A gust emitter
    * is not a particle, it is a seed that lives a second or more and spends that life spawning gusts
    * of its own, so that ring was not ninety-six particles, it was several thousand, arriving every
    * tick, and it is the whole of the lag report.
    *
    * <p>A ring is a shape, so what it needs is many things in a circle and not many of anything
    * expensive: a plain {@code GUST} puff is one short-lived particle that draws instantly and then
    * is gone, and a ring of thirty of them reads exactly like a ring of ninety seeds did. The seeds
    * are kept for the small signature shapes - the tornado's own column, the burst on release -
    * where a handful of them is the look, and {@code BossVfx.EMITTER_BUDGET} is the backstop for
    * anything that asks for more than a handful.
    */
   public static net.minecraft.core.particles.ParticleOptions ringParticle() {
      return ParticleTypes.GUST;
   }

   /** Sites in the ring the charge-up draws - a widening circle, hence the larger count. */
   public static final int CHARGE_RING_POINTS = 30;
   /** Sites in the ring each phase transition opens with. */
   public static final int PHASE_RING_POINTS = 28;
   /** Sites in the hurricane's ring, drawn once when it starts and once per tick after. */
   public static final int HURRICANE_RING_POINTS = 34;
   /** Sites in the ring a standing tornado draws - the smallest of the big rings. */
   public static final int TORNADO_RING_POINTS = 18;
   private static final double HURRICANE_PULL = 0.52;
   private static final double HURRICANE_LIFT = 0.16;
   private static final float HURRICANE_DAMAGE = 8.0F;
   private static final double HURRICANE_BLAST = 4.2;
   private static final float HURRICANE_BLAST_DAMAGE = 20.0F;

   private static final int DEATH_CEREMONY_TICKS = 80;

   private static final Random RANDOM = new Random();

   /** A delayed effect: a place, a fuse, and - for the lingering ones - a life. */
   private static final class Pending {
      final String kind;
      Vec3 pos;
      final Vec3 drift;
      int fuse;
      int life;

      Pending(String kind, Vec3 pos, Vec3 drift, int fuse, int life) {
         this.kind = kind;
         this.pos = pos;
         this.drift = drift;
         this.fuse = fuse;
         this.life = life;
      }
   }

   private static final class Fight {
      final UUID bossId;
      final UUID summoner;
      final ServerBossEvent bar;
      final Set<UUID> participants = new HashSet<>();
      final List<Pending> pending = new ArrayList<>();
      /** Momentum banked per player by Momentum Theft, and how long is left of their mark. */
      final Map<UUID, Double> theft = new HashMap<>();
      final Map<UUID, Integer> theftLeft = new HashMap<>();
      /** Reversal: pull instead of push, for this many more ticks. */
      final Map<UUID, Integer> reversed = new HashMap<>();
      /** Momentum Swap: the other half of the pair, and ticks left. */
      final Map<UUID, UUID> link = new HashMap<>();
      final Map<UUID, Integer> linkLeft = new HashMap<>();
      /** Zero Point banks everyone's movement, so it can be given back in one go. */
      final Map<UUID, Vec3> frozen = new HashMap<>();
      int phase = 1;
      long now;
      long nextMove;
      String lastMove = "";
      int stance;
      int charge;
      float chargeTaken;
      Vec3 chargeFrom = Vec3.ZERO;
      int aerial;
      int zeroPoint;
      /** Ticks each participant has been off the ground, for the lift ceiling. See holdDown. */
      final Map<UUID, Integer> airTicks = new HashMap<>();
      boolean dying;
      int deathTicks;

      Fight(UUID bossId, UUID summoner, ServerBossEvent bar) {
         this.bossId = bossId;
         this.summoner = summoner;
         this.bar = bar;
      }
   }

   private static final Map<UUID, Fight> FIGHTS = new HashMap<>();

   /** See {@code ClockworkKingManager.onBossDeath} for why a boss pays through a ledger. */
   private static final Set<UUID> LOOT_PAID = new HashSet<>();

   private static final List<String> MOVES_1 = List.of(
      "Momentum Theft", "Reversal", "Windstep", "Dead Air", "Pressure Point", "Gale Counter", "Tornado"
   );
   private static final List<String> MOVES_2 = List.of(
      "Sky Launch", "Cyclone Dash", "Momentum Swap", "Falling Sky", "Vacuum", "Twin Twisters"
   );
   private static final List<String> MOVES_3 = List.of(
      "No Ground", "Zero Point", "Heaven's Drop", "Four Winds", "Absolute Momentum", "Hurricane"
   );

   private GaleWardenManager() {
   }

   // ------------------------------------------------------------------ public API

   public static boolean isGaleWarden(Entity entity) {
      return entity != null && entity.entityTags().contains(TAG);
   }

   public static int activeCount() {
      return FIGHTS.size();
   }

   public static int pendingCount(UUID bossId) {
      Fight fight = FIGHTS.get(bossId);
      return fight == null ? 0 : fight.pending.size();
   }

   public static int phaseOf(UUID bossId) {
      Fight fight = FIGHTS.get(bossId);
      return fight == null ? 0 : fight.phase;
   }

   /** How many players he is currently banking momentum off - for the self-test. */
   public static int theftCount(UUID bossId) {
      Fight fight = FIGHTS.get(bossId);
      return fight == null ? 0 : fight.theft.size();
   }

   public static boolean isParticipant(ServerPlayer player) {
      if (player == null) {
         return false;
      }
      for (Fight f : FIGHTS.values()) {
         if (!f.dying && f.participants.contains(player.getUUID())) {
            return true;
         }
      }
      return false;
   }

   public static int abandonAll(MinecraftServer server) {
      int ended = 0;
      for (Fight fight : new ArrayList<>(FIGHTS.values())) {
         Safe.run("gale abandon", () -> shutDown(server, fight));
         ended++;
      }
      return ended;
   }

   public static void onServerStopping(MinecraftServer server) {
      for (Fight fight : new ArrayList<>(FIGHTS.values())) {
         Safe.run("gale shutdown", () -> shutDown(server, fight));
      }
      FIGHTS.clear();
   }

   /** The Gale Sigil right-click: he arrives on a current that was already moving. */
   public static String useGaleSigil(ServerPlayer player, ItemStack held) {
      String err = summon(player);
      if (err != null) {
         return err;
      }
      if (!player.getAbilities().instabuild) {
         held.shrink(1);
      }
      return null;
   }

   private static String summon(ServerPlayer summoner) {
      if (!ModConfig.is("boss")) {
         return "Bosses are disabled on this server.";
      }
      for (Fight f : new ArrayList<>(FIGHTS.values())) {
         Entity live = findEntity(summoner.level().getServer(), f.bossId);
         if (live == null || !live.isAlive()) {
            shutDown(summoner.level().getServer(), f);
         }
      }
      for (Fight f : FIGHTS.values()) {
         if (summoner.getUUID().equals(f.summoner)) {
            return "He is already circling you - finish this one first!";
         }
      }

      ServerLevel level = summoner.level();
      Mob boss = (Mob)EntityTypes.BREEZE.create(level, EntitySpawnReason.COMMAND);
      if (boss == null) {
         return "The air did not move - he did not arrive.";
      }
      AttributeInstance maxHp = boss.getAttribute(Attributes.MAX_HEALTH);
      if (maxHp != null) {
         maxHp.setBaseValue(MAX_HEALTH);
      }
      boss.setHealth((float)MAX_HEALTH);
      AttributeInstance follow = boss.getAttribute(Attributes.FOLLOW_RANGE);
      if (follow != null) {
         follow.setBaseValue(SEEK_RANGE);
      }
      AttributeInstance kb = boss.getAttribute(Attributes.KNOCKBACK_RESISTANCE);
      if (kb != null) {
         kb.setBaseValue(1.0);
      }
      // A giant breeze, and giant by the attribute rather than by a different mob - he is a breeze,
      // and the fight should read as one enormous one.
      AttributeInstance scale = boss.getAttribute(Attributes.SCALE);
      if (scale != null) {
         scale.setBaseValue(2.4);
      }
      attribute(boss, Attributes.MOVEMENT_SPEED, 0.28);
      attribute(boss, Attributes.ATTACK_DAMAGE, 12.0);
      attribute(boss, Attributes.FLYING_SPEED, 0.6);

      boss.setPersistenceRequired();
      boss.setCustomName(Component.literal(BOSS_NAME));
      boss.setCustomNameVisible(true);
      boss.setNoAi(false);
      boss.setNoGravity(false);
      boss.addTag(TAG);
      BossManager.markBoss(boss);
      boss.setPos(
         summoner.getX(),
         BossGrounding.groundY(level, summoner.getX(), summoner.getZ(), summoner.getY()) + HOVER,
         summoner.getZ()
      );
      level.addFreshEntity(boss);

      ServerBossEvent bar = new ServerBossEvent(
         UUID.randomUUID(), Component.literal(barName(1)), BossBarColor.WHITE, BossBarOverlay.PROGRESS
      );
      bar.setVisible(true);
      for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
         bar.addPlayer(p);
      }

      Fight fight = new Fight(boss.getUUID(), summoner.getUUID(), bar);
      fight.participants.add(summoner.getUUID());
      long now = ServerClock.clock(level);
      fight.nextMove = now + 50L;
      FIGHTS.put(boss.getUUID(), fight);

      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), ModSounds.BOSS_SPAWN, SoundSource.HOSTILE, 1.3F, 1.6F);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.BREEZE_WHIRL, SoundSource.HOSTILE, 1.6F, 0.7F);
      Advancements.grant(summoner, "summon_gale_warden");
      return null;
   }

   private static void attribute(Mob mob, net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> key, double value) {
      AttributeInstance instance = mob.getAttribute(key);
      if (instance != null) {
         instance.setBaseValue(value);
      }
   }

   // ------------------------------------------------------------------------ tick

   public static void tick(MinecraftServer server) {
      if (!LOOT_PAID.isEmpty()) {
         LOOT_PAID.removeIf(id -> findEntity(server, id) == null);
      }
      if (FIGHTS.isEmpty()) {
         return;
      }
      long now = ServerClock.clock(server.overworld());
      for (Fight fight : new ArrayList<>(FIGHTS.values())) {
         Safe.run("gale tick", () -> tickFight(server, fight, now));
      }
   }

   /**
    * Sweeps up Warden bodies left behind by a crash or a hard restart - see
    * {@link ClockworkKingManager#sweepStrays} for the full story.
    */
   public static int sweepStrays(MinecraftServer server) {
      int removed = 0;
      List<double[]> swept = new ArrayList<>();
      for (ServerPlayer p : server.getPlayerList().getPlayers()) {
         if (!(p.level() instanceof ServerLevel level)) {
            continue;
         }
         double r = STRAY_SWEEP_RADIUS;
         double[] box = new double[]{p.getX() - r, p.getY() - r, p.getZ() - r, p.getX() + r, p.getY() + r, p.getZ() + r};
         boolean covered = false;
         for (double[] s : swept) {
            if (s[0] <= box[0] && s[1] <= box[1] && s[2] <= box[2] && s[3] >= box[3] && s[4] >= box[4] && s[5] >= box[5]) {
               covered = true;
               break;
            }
         }
         if (covered) {
            continue;
         }
         swept.add(box);
         for (Mob mob : level.getEntitiesOfClass(Mob.class, p.getBoundingBox().inflate(r))) {
            if (isGaleWarden(mob) && !FIGHTS.containsKey(mob.getUUID())) {
               mob.discard();
               removed++;
            }
         }
      }
      return removed;
   }

   /** Pays this boss's loot exactly once, however he dies, and releases the fight. */
   public static void onBossDeath(ServerLevel level, Mob boss) {
      if (level == null || boss == null || LOOT_PAID.contains(boss.getUUID())) {
         return;
      }
      Fight fight = FIGHTS.get(boss.getUUID());
      if (fight == null) {
         return;
      }
      LOOT_PAID.add(boss.getUUID());
      grantLoot(level, boss, fight);
      release(level.getServer(), fight);
   }

   /**
    * Damage landed on him, read in the damage pipeline because that is the only place a blow can be
    * scaled or answered exactly once.
    *
    * <p>This is where two of his mechanics live. While he is <b>braced</b> (Gale Counter) a hit from
    * a player is thrown back at whoever landed it, from the side they are standing on. And while he
    * is <b>charging</b> (Absolute Momentum) damage is banked toward interrupting him, so the move is
    * a damage check rather than a timer nobody can influence.
    */
   public static void onBossDamaged(Entity entity, float amount, Entity attacker) {
      if (!isGaleWarden(entity) || !(entity instanceof Mob boss) || amount <= 0.0F) {
         return;
      }
      Fight fight = FIGHTS.get(entity.getUUID());
      if (fight == null || fight.dying) {
         return;
      }
      if (fight.charge > 0 && attacker instanceof ServerPlayer) {
         fight.chargeTaken += amount;
         if (fight.chargeTaken >= CHARGE_BREAK_DAMAGE) {
            interruptCharge((ServerLevel)boss.level(), boss, fight);
         }
      }
      if (fight.stance > 0 && attacker instanceof ServerPlayer hitter) {
         counter((ServerLevel)boss.level(), boss, fight, hitter);
      }
   }

   /**
    * The killing blow is cancelled and played out, so the last of his momentum is spent on the way
    * down rather than the body simply falling out of the air mid-move.
    */
   public static Boolean onLethalDamage(Entity entity, float amount) {
      if (!isGaleWarden(entity) || entity.level().isClientSide()) {
         return null;
      }
      Fight fight = FIGHTS.get(entity.getUUID());
      if (fight == null || fight.dying) {
         return null;
      }
      if (!(entity instanceof Mob boss) || boss.getHealth() - amount > 0.0F) {
         return null;
      }
      ServerLevel level = (ServerLevel)boss.level();
      fight.dying = true;
      fight.deathTicks = DEATH_CEREMONY_TICKS;
      fight.pending.clear();
      fight.charge = 0;
      fight.stance = 0;
      boss.setHealth(1.0F);
      fight.bar.setProgress(0.0F);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.BREEZE_DEATH, SoundSource.HOSTILE, 2.0F, 0.7F);
      return Boolean.FALSE;
   }

   private static void tickFight(MinecraftServer server, Fight fight, long now) {
      Mob boss = bossOf(server, fight);
      if (boss == null) {
         shutDown(server, fight);
         return;
      }
      ServerLevel level = (ServerLevel)boss.level();
      fight.now = now;

      for (ServerPlayer p : server.getPlayerList().getPlayers()) {
         if (p.level() == level && p.isAlive() && p.distanceToSqr(boss) < SEEK_RANGE * SEEK_RANGE) {
            fight.participants.add(p.getUUID());
         }
      }

      if (fight.dying) {
         tickDeath(server, boss, fight);
         return;
      }

      int phase = phaseFor(boss);
      if (phase != fight.phase) {
         fight.phase = phase;
         enterPhase(level, boss, fight, phase);
      }

      fight.bar.setProgress(Math.max(0.0F, Math.min(1.0F, boss.getHealth() / boss.getMaxHealth())));
      fight.bar.setName(Component.literal(barName(fight.phase)));

      // The two systems that are not moves: he always drifts toward whoever is closest, and he is
      // always reading their movement.
      drift(level, boss, fight);
      tickMomentum(level, boss, fight);
      // And the floor gets everybody back. See liftFor: this is the half of the ladder that the
      // boss does not control, and it runs after every move so nothing he does can outlast it.
      holdDown(level, boss, fight);
      tickPending(level, boss, fight);

      if (fight.charge > 0) {
         tickCharge(level, boss, fight);
         return;
      }
      if (fight.zeroPoint > 0) {
         tickZeroPoint(level, boss, fight);
         return;
      }
      if (fight.stance > 0) {
         fight.stance--;
         BossVfx.sphere(level, boss.position(), 2.0, 2, ParticleTypes.GUST_EMITTER_SMALL);
         if (fight.stance == 0) {
            overlayNear(level, boss, 50.0, "\u00a77The Warden lowers his guard.");
         }
         return;
      }
      if (now >= fight.nextMove) {
         fight.nextMove = now + moveGap(fight.phase);
         chooseMove(level, boss, fight);
      }

      // And he moves, without a breeze's brain behind him. A vanilla breeze sprints and vaults on
      // a whim, which reads as a pinball rather than a boss and makes a fight built on *momentum*
      // impossible to read - so he has no AI at all, and his movement is written here instead: a
      // steady stalk toward whoever is closest, slower than a player's walk, from which his dash,
      // his charge and his vacuum are the only things that ever move him fast.
      //
      // Held still through his own wind-ups, so the tells stay readable: a boss that walks out of
      // its own telegraph is a boss whose moves cannot be learned.
      if (fight.stance == 0) {
         ServerPlayer chase = nearestPlayer(level, boss, 64.0);
         if (chase != null) {
            stalkToward(boss, chase, 2.5, 0.14);
         }
      }
   }

   /**
    * One step toward {@code target}, and face them, with no vanilla AI involved.
    *
    * <p>Only horizontal intent is written; gravity still applies, so he walks rather than floats.
    * He stops at {@code spacing}, which is what keeps him out of the player's face - his moves are
    * what close the distance, and {@code speed} is deliberately under a player's walk.
    */
   private static void stalkToward(Mob boss, net.minecraft.world.entity.LivingEntity target, double spacing, double speed) {
      double dx = target.getX() - boss.getX();
      double dz = target.getZ() - boss.getZ();
      double dist = Math.sqrt(dx * dx + dz * dz);
      boss.getLookControl().setLookAt(target, 30.0F, 30.0F);
      if (dist < 0.001) {
         return;
      }
      boss.setYRot((float)(Math.toDegrees(Math.atan2(-dx, dz))));
      boss.yBodyRot = boss.getYRot();
      if (dist <= spacing) {
         boss.setDeltaMovement(boss.getDeltaMovement().multiply(0.5, 1.0, 0.5));
         return;
      }
      // Check collision so Gale Warden does NOT go through walls
      double mx = dx / dist * speed;
      double mz = dz / dist * speed;
      net.minecraft.world.phys.AABB nextBox = boss.getBoundingBox().move(mx, 0.0, mz);
      if (!boss.level().noCollision(boss, nextBox)) {
         // Blocked by wall: adjust movement along non-colliding axis
         if (boss.level().noCollision(boss, boss.getBoundingBox().move(mx, 0.0, 0.0))) {
            mz = 0.0;
         } else if (boss.level().noCollision(boss, boss.getBoundingBox().move(0.0, 0.0, mz))) {
            mx = 0.0;
         } else {
            mx = 0.0;
            mz = 0.0;
         }
      }
      boss.setDeltaMovement(mx, boss.getDeltaMovement().y, mz);
   }

   private static String barName(int phase) {
      String label = phase == 1 ? "The Warden" : phase == 2 ? "The Broken Sky" : "THE GALE";
      return BOSS_NAME + " \u00a78| \u00a7f" + label;
   }

   private static int phaseFor(Mob boss) {
      double fraction = boss.getMaxHealth() <= 0.0F ? 1.0 : boss.getHealth() / boss.getMaxHealth();
      if (fraction <= PHASE_3_AT) {
         return 3;
      }
      return fraction <= PHASE_2_AT ? 2 : 1;
   }

   private static int moveGap(int phase) {
      return switch (phase) {
         case 3 -> MOVE_GAP_3;
         case 2 -> MOVE_GAP_2;
         default -> MOVE_GAP_1;
      };
   }

   private static void enterPhase(ServerLevel level, Mob boss, Fight fight, int phase) {
      BossVfx.ring(level, boss.position(), 14.0, PHASE_RING_POINTS, ringParticle(), 0.0);
      BossVfx.sphere(level, boss.position(), 6.0, 3, ParticleTypes.CLOUD);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.BREEZE_WHIRL, SoundSource.HOSTILE, 1.8F, phase == 3 ? 0.6F : 0.9F);
      if (phase == 2) {
         overlayNear(level, boss, 90.0, "\u00a7f\u00a7lTHE BROKEN SKY");
      } else if (phase == 3) {
         overlayNear(level, boss, 90.0, "\u00a7f\u00a7lTHE GALE");
         fight.aerial = AERIAL_TICKS;
      }
   }

   /**
    * The one thing he does between moves: walk at you.
    *
    * <p>This used to hold him at a fixed height above the floor and zero his velocity every tick,
    * which is a hover by another name - he never fell, never landed, and could not be walked away
    * from across uneven ground. Gravity is his now, and all this writes is the lift he makes when
    * No Ground is running: a body pushed up hard enough to stay up is a body that comes down the
    * moment the wind stops.
    */
   private static void drift(ServerLevel level, Mob boss, Fight fight) {
      if (fight.aerial > 0) {
         fight.aerial--;
         double floor = BossGrounding.groundY(level, boss.getX(), boss.getZ(), boss.getY());
         double want = floor + HOVER + 3.5;
         Vec3 delta = boss.getDeltaMovement();
         if (boss.getY() < want - 0.4) {
            boss.setDeltaMovement(delta.x, Math.max(delta.y, 0.26), delta.z);
         } else if (boss.getY() > want + 1.4) {
            boss.setDeltaMovement(delta.x, Math.min(delta.y, -0.06), delta.z);
         }
         boss.fallDistance = 0.0F;
         boss.hurtMarked = true;
      }
      if (fight.now % 20L == 0L) {
         BossVfx.at(level, boss.position(), 0.0, ParticleTypes.CLOUD, 3, 0.7, 0.5, 0.7, 0.02);
      }
   }

   /**
    * The systems that read your movement, and give it back: Momentum Theft, Reversal, Momentum Swap
    * and the bank Zero Point keeps. All four run every tick because all four are about what players
    * are doing between his turns rather than about his turn.
    */
   /**
    * The end of every launch, whatever else is happening: a body that has been in the air for the
    * whole window loses the rest of its climb and comes down.
    *
    * <p>Deliberately not "stop him launching": the throws are the fight, and a player who has just
    * been thrown across the arena is playing it. What is not the fight is being kept up there - a
    * body with no floor under it cannot read a telegraph, cannot walk out of a column and cannot
    * reach the arena's edge, so a boss that can hold somebody off the ground indefinitely is a boss
    * whose phases stop being legible. Gravity is the answer and it is applied here rather than
    * trusted to the moves, because fourteen call sites is fourteen chances to forget.
    */
   private static void holdDown(ServerLevel level, Mob boss, Fight fight) {
      for (ServerPlayer p : participants(level)) {
         if (p.onGround() || p.isFallFlying() || p.isInWater() || p.getVehicle() != null) {
            fight.airTicks.remove(p.getUUID());
            continue;
         }
         int air = fight.airTicks.merge(p.getUUID(), 1, Integer::sum);
         if (air < LIFT_CEILING_TICKS) {
            continue;
         }
         Vec3 now = p.getDeltaMovement();
         if (now.y > 0.22) {
            // Not a snap to zero: the body keeps most of its arc and only loses the part of the
            // climb the boss was still paying for, so the landing is a fall rather than a drop.
            p.setDeltaMovement(now.x, 0.22, now.z);
            p.hurtMarked = true;
         }
      }
   }

   private static void tickMomentum(ServerLevel level, Mob boss, Fight fight) {
      for (ServerPlayer p : participants(level)) {
         UUID id = p.getUUID();
         Integer left = fight.theftLeft.get(id);
         if (left != null) {
            int remaining = left - 1;
            // What he takes is how far you actually moved, so a player who is standing still banks
            // nothing - and a player who is sprinting away from him banks the most.
            double banked = fight.theft.getOrDefault(id, 0.0) + p.getDeltaMovement().horizontalDistance();
            fight.theft.put(id, Math.min(THEFT_CAP, banked));
            if (remaining <= 0) {
               fight.theftLeft.remove(id);
               Vec3 release = new Vec3(boss.getX() - p.getX(), 0.0, boss.getZ() - p.getZ());
               if (release.lengthSqr() < 1.0E-4) {
                  release = new Vec3(1.0, 0.0, 0.0);
               }
               release = release.normalize().scale(-1.0);
               double power = Math.min(THEFT_CAP, fight.theft.remove(id)) * THEFT_RELEASE;
               p.setDeltaMovement(p.getDeltaMovement().add(release.scale(power)).add(0.0, 0.5, 0.0));
               p.hurtMarked = true;
               BossVfx.at(level, p.position(), 0.0, ParticleTypes.GUST, 24, 0.6, 0.5, 0.6, 0.35);
               level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.BREEZE_WIND_CHARGE_BURST, SoundSource.HOSTILE, 1.3F, 0.9F);
            } else {
               fight.theftLeft.put(id, remaining);
               BossVfx.beam(level, boss.position(), p.position().add(0.0, 1.0, 0.0), 0.12, ParticleTypes.END_ROD);
            }
         }
         Integer rev = fight.reversed.get(id);
         if (rev != null) {
            if (rev <= 1) {
               fight.reversed.remove(id);
            } else {
               fight.reversed.put(id, rev - 1);
               // Knockback becomes pullback: any horizontal speed leaving him is inverted instead of
               // carrying the player away, which is what makes Reversal a position swap rather than
               // a slow.
               Vec3 away = new Vec3(p.getX() - boss.getX(), 0.0, p.getZ() - boss.getZ());
               if (away.lengthSqr() > 1.0E-4) {
                  Vec3 unit = away.normalize();
                  Vec3 delta = new Vec3(p.getDeltaMovement().x, 0.0, p.getDeltaMovement().z);
                  if (delta.dot(unit) > 0.02) {
                     p.setDeltaMovement(p.getDeltaMovement().subtract(unit.scale(delta.dot(unit) * 2.0)));
                     p.hurtMarked = true;
                  }
                  pull(p, boss.position(), REVERSAL_PULL);
               }
               BossVfx.at(level, p.position().add(0.0, 1.0, 0.0), 0.0, ParticleTypes.SCULK_SOUL, 2, 0.3, 0.3, 0.3, 0.0);
            }
         }
         UUID other = fight.link.get(id);
         Integer linkLeft = fight.linkLeft.get(id);
         if (other != null && linkLeft != null) {
            if (linkLeft <= 1) {
               fight.link.remove(id);
               fight.linkLeft.remove(id);
            } else {
               fight.linkLeft.put(id, linkLeft - 1);
               ServerPlayer twin = level.getServer().getPlayerList().getPlayer(other);
               if (twin != null && twin.level() == level && twin.isAlive()) {
                  Vec3 theirs = twin.getDeltaMovement();
                  p.setDeltaMovement(p.getDeltaMovement().add(new Vec3(-theirs.x, theirs.y * 0.1, -theirs.z).scale(LINK_SHARE)));
                  p.hurtMarked = true;
               }
            }
         }
         if (fight.zeroPoint > 0) {
            fight.frozen.put(id, p.getDeltaMovement());
            p.setDeltaMovement(Vec3.ZERO);
            p.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 10, 6, false, false, false));
            p.hurtMarked = true;
         }
      }
   }

   private static void chooseMove(ServerLevel level, Mob boss, Fight fight) {
      List<String> moves = movesForPhase(fight.phase);
      String move = moves.get(RANDOM.nextInt(moves.size()));
      if (moves.size() > 1 && move.equals(fight.lastMove)) {
         move = moves.get((moves.indexOf(move) + 1) % moves.size());
      }
      fight.lastMove = move;
      ServerPlayer target = nearestPlayer(level, boss, 45.0);
      switch (move) {
         case "Momentum Theft" -> momentumTheft(level, boss, fight, target);
         case "Reversal" -> reversal(level, boss, fight, target);
         case "Windstep" -> windstep(level, boss, fight);
         case "Dead Air" -> deadAir(level, boss, fight, target);
         case "Pressure Point" -> pressurePoint(level, boss, fight);
         case "Gale Counter" -> galeCounter(level, boss, fight);
         case "Tornado" -> tornado(level, boss, fight);
         case "Twin Twisters" -> twinTwisters(level, boss, fight);
         case "Hurricane" -> hurricane(level, boss, fight);
         case "Sky Launch" -> skyLaunch(level, boss, fight);
         case "Cyclone Dash" -> cycloneDash(level, boss, fight);
         case "Momentum Swap" -> momentumSwap(level, boss, fight);
         case "Falling Sky" -> fallingSky(level, boss, fight);
         case "Vacuum" -> vacuum(level, boss, fight);
         case "No Ground" -> noGround(level, boss, fight);
         case "Zero Point" -> zeroPoint(level, boss, fight);
         case "Heaven's Drop" -> heavensDrop(level, boss, fight);
         case "Four Winds" -> fourWinds(level, boss, fight);
         case "Absolute Momentum" -> absoluteMomentum(level, boss, fight);
         default -> momentumTheft(level, boss, fight, target);
      }
      tell(level, boss, move);
   }

   private static void tell(ServerLevel level, Mob boss, String move) {
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.BREEZE_IDLE_AIR, SoundSource.HOSTILE, 1.5F, 0.7F);
      // Telegraph removed
   }

   // ---- phase 1: the Warden

   /** He banks your movement for a few seconds, then hands all of it back in one launch. */
   private static void momentumTheft(ServerLevel level, Mob boss, Fight fight, ServerPlayer target) {
      if (target == null) {
         pressurePoint(level, boss, fight);
         return;
      }
      fight.theftLeft.put(target.getUUID(), THEFT_TICKS);
      fight.theft.put(target.getUUID(), 0.0);
      level.playSound(null, target.getX(), target.getY(), target.getZ(), SoundEvents.BREEZE_CHARGE, SoundSource.HOSTILE, 1.4F, 1.2F);
   }

   /** Knockback becomes pullback: being launched at him is being dragged to him. */
   private static void reversal(ServerLevel level, Mob boss, Fight fight, ServerPlayer target) {
      if (target == null) {
         momentumTheft(level, boss, fight, nearestPlayer(level, boss, 45.0));
         return;
      }
      fight.reversed.put(target.getUUID(), REVERSAL_TICKS);
      BossVfx.beam(level, boss.position(), target.position().add(0.0, 1.0, 0.0), 0.25, ParticleTypes.END_ROD);
   }

   /** Between bodies, with a delayed blast left at every place he was. */
   private static void windstep(ServerLevel level, Mob boss, Fight fight) {
      List<ServerPlayer> near = participants(level);
      if (near.isEmpty()) {
         return;
      }
      int steps = Math.min(3, near.size() + 1);
      Vec3 at = boss.position();
      for (int i = 0; i < steps; i++) {
         ServerPlayer to = near.get(RANDOM.nextInt(near.size()));
         Vec3 landing = to.position().add(0.0, HOVER, 0.0);
         fight.pending.add(new Pending("gust", at, null, 0, 14));
         BossVfx.beam(level, at, landing, 0.3, ParticleTypes.GUST_EMITTER_SMALL);
         level.playSound(null, at.x, at.y, at.z, SoundEvents.BREEZE_IDLE_GROUND, SoundSource.HOSTILE, 1.2F, 1.4F);
         at = landing;
      }
      boss.setPos(at.x, at.y, at.z);
      boss.hurtMarked = true;
   }

   /**
    * A zone where nothing wants to move - and then one moment where everything does. Projectiles are
    * slowed rather than stopped (an arrow that hangs in the air is a lie a player cannot see), and
    * the release is what the move is actually for.
    */
   private static void deadAir(ServerLevel level, Mob boss, Fight fight, ServerPlayer target) {
      Vec3 center = target == null ? boss.position() : target.position();
      fight.pending.add(new Pending("deadair", center, null, 0, DEAD_AIR_TICKS));
      level.playSound(null, center.x, center.y, center.z, SoundEvents.BREEZE_IDLE_AIR, SoundSource.HOSTILE, 1.4F, 0.6F);
   }

   /** Columns of wind out of marked ground, on a fuse. */
   private static void pressurePoint(ServerLevel level, Mob boss, Fight fight) {
      int marks = 4;
      for (int i = 0; i < marks; i++) {
         ServerPlayer anchor = nearestPlayer(level, boss, 45.0);
         double x = anchor != null ? anchor.getX() + (RANDOM.nextDouble() - 0.5) * 10.0 : boss.getX() + (RANDOM.nextDouble() - 0.5) * 16.0;
         double z = anchor != null ? anchor.getZ() + (RANDOM.nextDouble() - 0.5) * 10.0 : boss.getZ() + (RANDOM.nextDouble() - 0.5) * 16.0;
         double y = BossGrounding.groundY(level, x, z, boss.getY());
         fight.pending.add(new Pending("column", new Vec3(x, y, z), null, 24 + i * 6, 0));
      }
   }

   /** He braces: the next player to hit him wears their own blow, thrown back. */
   private static void galeCounter(ServerLevel level, Mob boss, Fight fight) {
      fight.stance = STANCE_TICKS;
      BossVfx.sphere(level, boss.position(), 2.4, 3, ParticleTypes.GUST_EMITTER_SMALL);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.BREEZE_INHALE, SoundSource.HOSTILE, 1.4F, 0.8F);
   }

   private static void counter(ServerLevel level, Mob boss, Fight fight, ServerPlayer hitter) {
      fight.stance = 0;
      Vec3 away = hitter.position().subtract(boss.position());
      Vec3 unit = new Vec3(away.x, 0.0, away.z);
      if (unit.lengthSqr() < 1.0E-4) {
         unit = new Vec3(1.0, 0.0, 0.0);
      }
      unit = unit.normalize();
      hitter.hurtServer(level, level.damageSources().mobAttack(boss), COUNTER_DAMAGE);
      push(hitter, unit, COUNTER_PUSH);
      BossVfx.wall(level, boss.position().add(0.0, 1.0, 0.0), unit, 5.0, 2.6, ParticleTypes.GUST, 3);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.BREEZE_WIND_CHARGE_BURST, SoundSource.HOSTILE, 1.6F, 1.1F);
      overlayNear(level, boss, 50.0, "\u00a7f\u00a7lGALE COUNTER");
   }

   /**
    * A twister: one column that walks the arena, dragging bodies into it and then up.
    *
    * <p>It wanders rather than chasing, which is the point - the player chooses whether to be near
    * it, and the move is a floor hazard instead of another automatic hit. The wind inside a real
    * tornado is the fastest thing about it, so being dragged in and lifted is the damage: standing
    * in the column is what costs, every ten ticks.
    */
   private static void tornado(ServerLevel level, Mob boss, Fight fight) {
      Vec3 at = boss.position().add(randomFlat().scale(5.0));
      fight.pending.add(new Pending("tornado", at, randomFlat(), 0, TORNADO_TICKS));
      BossVfx.column(level, at, TORNADO_HEIGHT, ParticleTypes.CLOUD, 3);
      BossVfx.ring(level, at, TORNADO_RADIUS, 34, ParticleTypes.GUST, 0.3);
      level.playSound(null, at.x, at.y, at.z, SoundEvents.BREEZE_WHIRL, SoundSource.HOSTILE, 1.6F, 0.9F);
      overlayNear(level, boss, 70.0, "\u00a7f\u00a7lTORNADO \u00a77- \u00a7fit moves, and it is not aimed at you");
   }

   /** Two of them, at once, from opposite sides - walking between them is the counterplay. */
   private static void twinTwisters(ServerLevel level, Mob boss, Fight fight) {
      Vec3 side = randomFlat();
      for (int i = 0; i < 2; i++) {
         Vec3 at = boss.position().add(side.scale(i == 0 ? 9.0 : -9.0));
         Vec3 drift = i == 0 ? new Vec3(-side.z, 0.0, side.x) : new Vec3(side.z, 0.0, -side.x);
         fight.pending.add(new Pending("tornado", at, drift, 0, TORNADO_TICKS));
         BossVfx.column(level, at, TORNADO_HEIGHT, ParticleTypes.CLOUD, 3);
         BossVfx.ring(level, at, TORNADO_RADIUS, 34, ParticleTypes.GUST, 0.3);
         level.playSound(null, at.x, at.y, at.z, SoundEvents.BREEZE_WHIRL, SoundSource.HOSTILE, 1.6F, 1.1F);
      }
      overlayNear(level, boss, 80.0, "\u00a7f\u00a7lTWIN TWISTERS \u00a77- \u00a7tthere is a way between them");
   }

   // ---- phase 2: the Broken Sky

   /**
    * Everyone goes up - and comes down where the currents put them. The currents are the move: a
    * launch you cannot steer is a rollercoaster, and a launch you must steer is a phase-two fight.
    */
   private static void skyLaunch(ServerLevel level, Mob boss, Fight fight) {
      for (ServerPlayer p : participants(level)) {
         p.setDeltaMovement(p.getDeltaMovement().add(0.0, liftFor(1.65, fight.airTicks.getOrDefault(p.getUUID(), 0)), 0.0));
         p.hurtMarked = true;
      }
      Vec3 center = boss.position();
      for (int i = 0; i < 3; i++) {
         double a = RANDOM.nextDouble() * Math.PI * 2.0;
         fight.pending.add(
            new Pending("current", center.add(Math.cos(a) * 6.0, 0.0, Math.sin(a) * 6.0), new Vec3(Math.cos(a), 0.0, Math.sin(a)), 0, CURRENT_TICKS)
         );
      }
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.BREEZE_JUMP, SoundSource.HOSTILE, 1.6F, 0.7F);
   }

   /** A fast crossing that leaves the air behind it moving - touching a tunnel is the cost. */
   private static void cycloneDash(ServerLevel level, Mob boss, Fight fight) {
      Vec3 dir = randomFlat();
      Vec3 from = boss.position();
      for (int i = 0; i < 5; i++) {
         Vec3 at = from.add(dir.scale(i * 4.0));
         fight.pending.add(new Pending("tunnel", at, dir, 0, TUNNEL_TICKS));
      }
      Vec3 end = from.add(dir.scale(20.0));
      BossVfx.beam(level, from, end, 0.6, ParticleTypes.GUST_EMITTER_LARGE);
      boss.setPos(end.x, boss.getY(), end.z);
      boss.hurtMarked = true;
      level.playSound(null, end.x, end.y, end.z, SoundEvents.BREEZE_SHOOT, SoundSource.HOSTILE, 1.7F, 0.7F);
   }

   /** Two players, one movement: each is pushed by however the other is moving. */
   private static void momentumSwap(ServerLevel level, Mob boss, Fight fight) {
      List<ServerPlayer> near = participants(level);
      if (near.size() < 2) {
         skyLaunch(level, boss, fight);
         return;
      }
      for (int i = 0; i + 1 < near.size() && i < 4; i += 2) {
         ServerPlayer a = near.get(i);
         ServerPlayer b = near.get(i + 1);
         fight.link.put(a.getUUID(), b.getUUID());
         fight.link.put(b.getUUID(), a.getUUID());
         fight.linkLeft.put(a.getUUID(), LINK_TICKS);
         fight.linkLeft.put(b.getUUID(), LINK_TICKS);
         BossVfx.beam(level, a.position().add(0.0, 1.0, 0.0), b.position().add(0.0, 1.0, 0.0), 0.3, ParticleTypes.END_ROD);
      }
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.BREEZE_IDLE_AIR, SoundSource.HOSTILE, 1.4F, 1.3F);
   }

   /** Charges thrown upward that come back down on their own marks. */
   private static void fallingSky(ServerLevel level, Mob boss, Fight fight) {
      for (int i = 0; i < SKYFALL_COUNT; i++) {
         double a = RANDOM.nextDouble() * Math.PI * 2.0;
         double r = 4.0 + RANDOM.nextDouble() * 13.0;
         double x = boss.getX() + Math.cos(a) * r;
         double z = boss.getZ() + Math.sin(a) * r;
         double y = BossGrounding.groundY(level, x, z, boss.getY());
         fight.pending.add(new Pending("skyfall", new Vec3(x, y, z), null, 30 + RANDOM.nextInt(45), 0));
      }
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.BREEZE_CHARGE, SoundSource.HOSTILE, 1.6F, 0.6F);
   }

   /** Everything is pulled into one place, and then the place stops holding it. */
   private static void vacuum(ServerLevel level, Mob boss, Fight fight) {
      fight.pending.add(new Pending("vacuum", boss.position(), null, 0, 60));
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.BREEZE_INHALE, SoundSource.HOSTILE, 1.8F, 0.7F);
   }

   // ---- phase 3: the Gale

   /** The floor stops mattering: he rises, and currents run the arena for a while. */
   private static void noGround(ServerLevel level, Mob boss, Fight fight) {
      fight.aerial = AERIAL_TICKS;
      for (ServerPlayer p : participants(level)) {
         p.setDeltaMovement(p.getDeltaMovement().add(0.0, 1.1, 0.0));
         p.hurtMarked = true;
         p.sendOverlayMessage(Component.literal("\u00a7f\u00a7lNO GROUND"));
      }
      Vec3 center = boss.position();
      for (int i = 0; i < 4; i++) {
         double a = (Math.PI * 2.0 * i) / 4.0;
         fight.pending.add(
            new Pending("current", center.add(Math.cos(a) * 8.0, 0.0, Math.sin(a) * 8.0), new Vec3(Math.cos(a + 1.5), 0.0, Math.sin(a + 1.5)), 0, CURRENT_TICKS)
         );
      }
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.BREEZE_WHIRL, SoundSource.HOSTILE, 2.0F, 0.6F);
   }

   /** Everything stops for two seconds - and then everything he banked moves at once. */
   private static void zeroPoint(ServerLevel level, Mob boss, Fight fight) {
      fight.zeroPoint = ZERO_POINT_TICKS;
      fight.frozen.clear();
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.BREEZE_INHALE, SoundSource.HOSTILE, 2.0F, 0.5F);
   }

   private static void tickZeroPoint(ServerLevel level, Mob boss, Fight fight) {
      fight.zeroPoint--;
      BossVfx.ring(level, boss.position(), 3.0 + fight.zeroPoint * 0.4, 60, ParticleTypes.END_ROD, 0.6);
      if (fight.zeroPoint > 0) {
         return;
      }
      for (ServerPlayer p : participants(level)) {
         Vec3 stored = fight.frozen.get(p.getUUID());
         Vec3 give = stored == null ? Vec3.ZERO : stored;
         p.setDeltaMovement(give.scale(ZERO_POINT_RELEASE).add(0.0, 0.6, 0.0));
         p.hurtMarked = true;
         BossVfx.at(level, p.position(), 0.0, ParticleTypes.GUST, 30, 0.7, 0.6, 0.7, 0.4);
      }
      fight.frozen.clear();
      BossVfx.sphere(level, boss.position(), 12.0, 3, ParticleTypes.GUST_EMITTER_LARGE);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.BREEZE_WIND_CHARGE_BURST, SoundSource.HOSTILE, 2.0F, 0.6F);
      overlayNear(level, boss, 90.0, "\u00a7f\u00a7lEVERYTHING AT ONCE");
   }

   /** Everyone thrown very high, with currents underneath to be steered toward safety. */
   private static void heavensDrop(ServerLevel level, Mob boss, Fight fight) {
      for (ServerPlayer p : participants(level)) {
         p.setDeltaMovement(p.getDeltaMovement().add(0.0, liftFor(2.5, fight.airTicks.getOrDefault(p.getUUID(), 0)), 0.0));
         p.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 120, 0, false, false, false));
         p.hurtMarked = true;
      }
      Vec3 center = boss.position();
      for (int i = 0; i < 3; i++) {
         double a = RANDOM.nextDouble() * Math.PI * 2.0;
         fight.pending.add(
            new Pending("current", center.add(Math.cos(a) * 10.0, 0.0, Math.sin(a) * 10.0), new Vec3(Math.cos(a), 0.0, Math.sin(a)), 0, CURRENT_TICKS)
         );
      }
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.BREEZE_JUMP, SoundSource.HOSTILE, 2.0F, 0.6F);
   }

   /** Four winds at the four points of the arena, each shoving everything toward the middle. */
   private static void fourWinds(ServerLevel level, Mob boss, Fight fight) {
      Vec3 center = boss.position();
      double r = 14.0;
      for (int i = 0; i < 4; i++) {
         double a = (Math.PI * 2.0 * i) / 4.0;
         Vec3 at = center.add(Math.cos(a) * r, 0.0, Math.sin(a) * r);
         fight.pending.add(new Pending("wind", at, null, 0, CURRENT_TICKS));
      }
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.BREEZE_WHIRL, SoundSource.HOSTILE, 2.0F, 0.8F);
   }

   /**
    * The final move: a hundred ticks of charging, interrupted by enough damage, and answered with
    * one arena-wide blast if nobody manages it.
    */
   private static void absoluteMomentum(ServerLevel level, Mob boss, Fight fight) {
      fight.charge = CHARGE_TICKS;
      fight.chargeTaken = 0.0F;
      fight.chargeFrom = boss.position();
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.BREEZE_INHALE, SoundSource.HOSTILE, 2.0F, 0.6F);
   }

   private static void tickCharge(ServerLevel level, Mob boss, Fight fight) {
      fight.charge--;
      double progress = 1.0 - (fight.charge / (double)CHARGE_TICKS);
      BossVfx.ring(level, boss.position(), 3.0 + progress * 26.0, CHARGE_RING_POINTS, ringParticle(), 0.4);
      BossVfx.sphere(level, boss.position(), 2.0 + progress * 4.0, 2, ParticleTypes.CLOUD);
      if (fight.charge % 10 == 0) {
         level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.BREEZE_CHARGE, SoundSource.HOSTILE, 1.2F + (float)progress, 0.7F);
         overlayNear(level, boss, 90.0, "\u00a7fAbsolute Momentum \u00a77" + (CHARGE_TICKS - fight.charge) + "/" + CHARGE_TICKS);
      }
      if (fight.charge > 0) {
         return;
      }
      releaseAbsolute(level, boss, fight);
   }

   private static void releaseAbsolute(ServerLevel level, Mob boss, Fight fight) {
      fight.charge = 0;
      BossVfx.sphere(level, boss.position(), ABSOLUTE_RADIUS * 0.5, 2, ringParticle());
      BossVfx.disc(level, boss.position(), ABSOLUTE_RADIUS, ParticleTypes.GUST, 0.3);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.BREEZE_WIND_CHARGE_BURST, SoundSource.HOSTILE, 2.2F, 0.5F);
      for (ServerPlayer p : playersNear(level, boss.position(), ABSOLUTE_RADIUS)) {
         Vec3 away = p.position().subtract(boss.position());
         Vec3 unit = new Vec3(away.x, 0.0, away.z);
         unit = unit.lengthSqr() < 1.0E-4 ? new Vec3(1.0, 0.0, 0.0) : unit.normalize();
         p.hurtServer(level, level.damageSources().mobAttack(boss), ABSOLUTE_DAMAGE);
         p.setDeltaMovement(p.getDeltaMovement().add(unit.scale(ABSOLUTE_LAUNCH)).add(0.0, 1.1, 0.0));
         p.hurtMarked = true;
      }
   }

   /**
    * The finale: the arena becomes the storm.
    *
    * <p>Everything he has done up to now has been about momentum - taking it, returning it,
    * mirroring it. This is the one move where the weather itself is the opponent: a hurricane the
    * width of the arena that leans on every body in it at once for eleven seconds, and then lets go
    * of all of it in a single blast. It cannot be interrupted, which is why its counterplay is
    * spatial rather than tactical - the pull is loud and visible, and the walking is the fight.
    */
   private static void hurricane(ServerLevel level, Mob boss, Fight fight) {
      fight.pending.add(new Pending("hurricane", boss.position(), null, 0, HURRICANE_TICKS));
      BossVfx.ring(level, boss.position(), HURRICANE_RADIUS * 0.6, HURRICANE_RING_POINTS, ringParticle(), 0.4);
      BossVfx.sphere(level, boss.position().add(0.0, 3.0, 0.0), 10.0, 4, ParticleTypes.CLOUD);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.BREEZE_WHIRL, SoundSource.HOSTILE, 2.2F, 0.5F);
      overlayNear(level, boss, 110.0, "\u00a7f\u00a7lTHE HURRICANE");
   }

   /**
    * The whole arena, let go of in one blast. Every body inside it - not only the marked ones - is
    * thrown outward off him and left standing wherever it lands, which is the deliberate end of a
    * fight whose every other move asked you to keep your feet.
    */
   private static void releaseHurricane(ServerLevel level, Mob boss, Fight fight) {
      BossVfx.sphere(level, boss.position(), HURRICANE_RADIUS * 0.5, 4, ParticleTypes.GUST_EMITTER_LARGE);
      BossVfx.disc(level, boss.position(), HURRICANE_RADIUS, ParticleTypes.GUST, 0.3);
      BossVfx.disc(level, boss.position(), HURRICANE_RADIUS * 0.7, ParticleTypes.CLOUD, 0.2);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.BREEZE_WIND_CHARGE_BURST, SoundSource.HOSTILE, 2.4F, 0.5F);
      for (ServerPlayer p : playersNear(level, boss.position(), HURRICANE_RADIUS)) {
         Vec3 away = p.position().subtract(boss.position());
         Vec3 unit = new Vec3(away.x, 0.0, away.z);
         unit = unit.lengthSqr() < 1.0E-4 ? new Vec3(1.0, 0.0, 0.0) : unit.normalize();
         p.hurtServer(level, level.damageSources().mobAttack(boss), HURRICANE_BLAST_DAMAGE);
         p.setDeltaMovement(
            p.getDeltaMovement().add(unit.scale(HURRICANE_BLAST))
               .add(0.0, liftFor(1.4, fight.airTicks.getOrDefault(p.getUUID(), 0)), 0.0)
         );
         p.hurtMarked = true;
         p.sendOverlayMessage(Component.literal("\u00a7f\u00a7lTHE HURRICANE LETS GO"));
      }
      fight.nextMove = fight.now + 40L;
   }

   private static void interruptCharge(ServerLevel level, Mob boss, Fight fight) {
      fight.charge = 0;
      BossVfx.sphere(level, boss.position(), 4.0, 2, ParticleTypes.CLOUD);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.BREEZE_DEFLECT, SoundSource.HOSTILE, 1.6F, 0.8F);
      // The reward for interrupting it is a window, not a free kill: he loses the blow and has to
      // gather himself again.
      fight.nextMove = fight.now + 60L;
   }

   // ------------------------------------------------------------------ pending

   private static void tickPending(ServerLevel level, Mob boss, Fight fight) {
      if (fight.pending.isEmpty()) {
         return;
      }
      for (Pending pending : new ArrayList<>(fight.pending)) {
         if (pending.drift != null) {
            pending.pos = pending.pos.add(pending.drift.scale(0.22));
         }
         if (pending.fuse > 0) {
            pending.fuse--;
            draw(level, boss, fight, pending, true);
            if (pending.fuse > 0) {
               continue;
            }
            land(level, boss, pending);
            if (pending.life <= 0) {
               fight.pending.remove(pending);
            }
            continue;
         }
         draw(level, boss, fight, pending, false);
         if (pending.life > 0 && --pending.life <= 0) {
            expire(level, boss, fight, pending);
            fight.pending.remove(pending);
         }
      }
   }

   private static void draw(ServerLevel level, Mob boss, Fight fight, Pending pending, boolean tell) {
      Vec3 p = pending.pos;
      switch (pending.kind) {
         case "gust" -> BossVfx.sphere(level, p, 1.6, 2, ParticleTypes.GUST_EMITTER_SMALL);
         case "column" -> {
            BossVfx.ring(level, p, COLUMN_RADIUS, TORNADO_RING_POINTS, ParticleTypes.GUST_EMITTER_SMALL, 0.2);
            if (!tell) {
               BossVfx.column(level, p, 5.0, ParticleTypes.CLOUD, 2);
            }
         }
         case "skyfall" -> {
            BossVfx.ring(level, p.add(0.0, 0.2, 0.0), 2.4, 20, ParticleTypes.END_ROD, 0.05);
            // The charge itself, falling: the mark is the ground, the charge is the column above it.
            double height = Math.max(1.0, pending.fuse * 0.9);
            BossVfx.column(level, p.add(0.0, height * 0.5, 0.0), 1.0, ParticleTypes.CLOUD, 1);
         }
         case "current" -> {
            BossVfx.beam(level, p.add(0.0, 0.2, 0.0), p.add(pending.drift.scale(9.0)).add(0.0, 0.2, 0.0), 1.1, ParticleTypes.CLOUD);
            for (ServerPlayer q : playersNear(level, p, 3.2)) {
               q.setDeltaMovement(q.getDeltaMovement().add(pending.drift.scale(CURRENT_PUSH)));
               q.hurtMarked = true;
            }
         }
         case "tunnel" -> {
            BossVfx.sphere(level, p.add(0.0, 1.0, 0.0), 2.2, 2, ParticleTypes.GUST_EMITTER_LARGE);
            for (ServerPlayer q : playersNear(level, p, 2.6)) {
               q.setDeltaMovement(q.getDeltaMovement().add(pending.drift.scale(TUNNEL_PUSH)).add(0.0, 0.3, 0.0));
               q.hurtMarked = true;
            }
         }
         case "wind" -> {
            BossVfx.column(level, p, 8.0, ParticleTypes.CLOUD, 2);
            // Each wind shoves everything toward the middle of the arena, which is where he is.
            for (ServerPlayer q : playersNear(level, p, 30.0)) {
               pull(q, boss.position(), 0.10);
            }
            if (pending.life % 20 == 0) {
               for (ServerPlayer q : playersNear(level, p, 3.0)) {
                  q.hurtServer(level, level.damageSources().mobAttack(boss), FOUR_WINDS_DAMAGE);
               }
            }
         }
         case "deadair" -> {
            double shrink = 1.0 - (pending.life / (double)DEAD_AIR_TICKS) * 0.35;
            BossVfx.ring(level, p, DEAD_AIR_RADIUS * shrink, 48, ParticleTypes.CLOUD, 0.4);
            BossVfx.sphere(level, p.add(0.0, 1.0, 0.0), DEAD_AIR_RADIUS * 0.55, 2, ParticleTypes.END_ROD);
            for (ServerPlayer q : playersNear(level, p, DEAD_AIR_RADIUS)) {
               q.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 10, 2, false, false, false));
               q.setDeltaMovement(q.getDeltaMovement().scale(DEAD_AIR_SLOW));
               q.hurtMarked = true;
            }
            // Arrows slow to a crawl in it - the move is about projectiles too, and a bow that
            // silently stops working is worse than one that visibly stops working.
            for (AbstractArrow arrow : level.getEntitiesOfClass(AbstractArrow.class, box(p, DEAD_AIR_RADIUS))) {
               arrow.setDeltaMovement(arrow.getDeltaMovement().scale(0.55));
            }
         }
         case "vacuum" -> {
            BossVfx.sphere(level, p.add(0.0, 2.0, 0.0), 6.0 + (60 - pending.life) * 0.1, 3, ParticleTypes.CLOUD);
            for (ServerPlayer q : playersNear(level, p, 22.0)) {
               pull(q, p, 0.34);
            }
         }
         case "tornado" -> {
            // The shape first: a column the whole way up, a ring on the floor, and the spiral that
            // says which way it is turning.
            BossVfx.column(level, p, TORNADO_HEIGHT, ParticleTypes.CLOUD, 3);
            BossVfx.ring(level, p.add(0.0, 0.2, 0.0), TORNADO_RADIUS, TORNADO_RING_POINTS, ParticleTypes.GUST_EMITTER_SMALL, 0.4);
            for (int i = 0; i < 10; i++) {
               double a = i * (Math.PI * 2.0 / 10.0) + pending.life * 0.22;
               double r = TORNADO_RADIUS * (0.35 + 0.65 * ((i % 4) / 3.0));
               BossVfx.at(
                  level,
                  p.add(Math.cos(a) * r, 1.0 + (i % 5) * 1.6, Math.sin(a) * r),
                  0.0,
                  ParticleTypes.SMALL_GUST,
                  1,
                  0.0,
                  0.0,
                  0.0,
                  0.0
               );
            }
            // Then the wind itself: being inside it is being dragged in and then held up, and
            // standing there pays every ten ticks rather than every tick.
            for (ServerPlayer q : playersNear(level, p, TORNADO_RADIUS)) {
               pull(q, p, TORNADO_PULL);
               q.setDeltaMovement(q.getDeltaMovement().add(0.0, TORNADO_LIFT, 0.0));
               q.hurtMarked = true;
               q.fallDistance = 0.0F;
               if (pending.life % 10 == 0) {
                  q.hurtServer(level, level.damageSources().mobAttack(boss), TORNADO_DAMAGE);
               }
            }
         }
         case "hurricane" -> {
            // It is centred on him and travels with him, so the move is the storm rather than the
            // patch of ground he happened to be standing on when it started.
            pending.pos = boss.position();
            double span = HURRICANE_RADIUS * (0.55 + 0.45 * (1.0 - pending.life / (double)HURRICANE_TICKS));
            BossVfx.ring(level, p.add(0.0, 0.4, 0.0), span, HURRICANE_RING_POINTS, ringParticle(), 0.5);
            BossVfx.ring(level, p.add(0.0, 6.0, 0.0), span * 0.7, 72, ParticleTypes.CLOUD, 0.4);
            BossVfx.column(level, p, 12.0, ParticleTypes.CLOUD, 4);
            for (int i = 0; i < 12; i++) {
               double a = i * (Math.PI * 2.0 / 12.0) - pending.life * 0.18;
               BossVfx.at(
                  level,
                  p.add(Math.cos(a) * span * 0.6, 2.0 + (i % 4) * 2.0, Math.sin(a) * span * 0.6),
                  0.0,
                  ParticleTypes.SMALL_GUST,
                  1,
                  0.0,
                  0.0,
                  0.0,
                  0.0
               );
            }
            for (ServerPlayer q : playersNear(level, p, HURRICANE_RADIUS)) {
               pull(q, p, HURRICANE_PULL);
               q.setDeltaMovement(q.getDeltaMovement().add(0.0, HURRICANE_LIFT, 0.0));
               q.hurtMarked = true;
               if (pending.life % 20 == 0) {
                  q.hurtServer(level, level.damageSources().mobAttack(boss), HURRICANE_DAMAGE);
                  q.sendOverlayMessage(Component.literal("\u00a7f\u00a7lHURRICANE \u00a77- \u00a7fhold your ground"));
               }
            }
            // Once every two seconds, not every half second: the storm is a drone, and a drone
            // retriggered that fast stops reading as weather and starts reading as a stuck sound.
            if (pending.life % 40 == 0) {
               level.playSound(null, p.x, p.y, p.z, SoundEvents.BREEZE_IDLE_AIR, SoundSource.HOSTILE, 2.0F, 0.5F);
            }
         }
         default -> {
         }
      }
   }

   private static void land(ServerLevel level, Mob boss, Pending pending) {
      Vec3 p = pending.pos;
      switch (pending.kind) {
         case "gust" -> {
            BossVfx.sphere(level, p, 3.4, 3, ParticleTypes.GUST_EMITTER_LARGE);
            level.playSound(null, p.x, p.y, p.z, SoundEvents.BREEZE_WIND_CHARGE_BURST, SoundSource.HOSTILE, 1.4F, 1.1F);
            for (ServerPlayer q : playersNear(level, p, 3.4)) {
               q.hurtServer(level, level.damageSources().mobAttack(boss), COLUMN_DAMAGE * 0.7F);
               Vec3 away = q.position().subtract(p);
               push(q, new Vec3(away.x, 0.0, away.z), 2.0);
            }
         }
         case "column" -> {
            BossVfx.column(level, p, 7.0, ParticleTypes.CLOUD, 3);
            BossVfx.ring(level, p, COLUMN_RADIUS, TORNADO_RING_POINTS, ringParticle(), 0.2);
            level.playSound(null, p.x, p.y, p.z, SoundEvents.BREEZE_WHIRL, SoundSource.HOSTILE, 1.4F, 0.8F);
            for (ServerPlayer q : playersNear(level, p, COLUMN_RADIUS)) {
               q.hurtServer(level, level.damageSources().mobAttack(boss), COLUMN_DAMAGE);
               q.setDeltaMovement(
                  q.getDeltaMovement().add(0.0, liftFor(1.4, 0), 0.0)
               );
               q.hurtMarked = true;
            }
         }
         case "skyfall" -> {
            BossVfx.sphere(level, p.add(0.0, 0.5, 0.0), 3.0, 3, ParticleTypes.GUST_EMITTER_LARGE);
            BossVfx.disc(level, p, 3.0, ParticleTypes.GUST, 0.2);
            level.playSound(null, p.x, p.y, p.z, SoundEvents.BREEZE_WIND_CHARGE_BURST, SoundSource.HOSTILE, 1.5F, 0.9F);
            for (ServerPlayer q : playersNear(level, p, 3.0)) {
               q.hurtServer(level, level.damageSources().mobAttack(boss), SKYFALL_DAMAGE);
               q.setDeltaMovement(
                  q.getDeltaMovement().add(0.0, liftFor(1.2, 0), 0.0)
               );
               q.hurtMarked = true;
            }
         }
         default -> {
         }
      }
   }

   /** What happens when a lingering thing finally runs out - only two of them do anything. */
   private static void expire(ServerLevel level, Mob boss, Fight fight, Pending pending) {
      switch (pending.kind) {
         case "deadair" -> {
            BossVfx.sphere(level, pending.pos.add(0.0, 1.0, 0.0), DEAD_AIR_RADIUS * 0.8, 3, ParticleTypes.GUST_EMITTER_LARGE);
            level.playSound(null, pending.pos.x, pending.pos.y, pending.pos.z, SoundEvents.BREEZE_WIND_CHARGE_BURST, SoundSource.HOSTILE, 2.0F, 0.7F);
            for (ServerPlayer q : playersNear(level, pending.pos, DEAD_AIR_RADIUS)) {
               Vec3 away = q.position().subtract(pending.pos);
               push(q, new Vec3(away.x, 0.0, away.z), DEAD_AIR_RELEASE);
            }
         }
         case "vacuum" -> {
            BossVfx.sphere(level, pending.pos.add(0.0, 1.0, 0.0), 12.0, 3, ParticleTypes.GUST_EMITTER_LARGE);
            level.playSound(null, pending.pos.x, pending.pos.y, pending.pos.z, SoundEvents.BREEZE_WIND_CHARGE_BURST, SoundSource.HOSTILE, 2.0F, 0.5F);
            for (ServerPlayer q : playersNear(level, pending.pos, 20.0)) {
               Vec3 away = q.position().subtract(pending.pos);
               push(q, new Vec3(away.x, 0.0, away.z), 2.8);
               q.hurtServer(level, level.damageSources().mobAttack(boss), 9.0F);
            }
         }
         case "hurricane" -> releaseHurricane(level, boss, fight);
         default -> {
         }
      }
   }

   // --------------------------------------------------------------------- loot

   private static void tickDeath(MinecraftServer server, Mob boss, Fight fight) {
      ServerLevel level = (ServerLevel)boss.level();
      fight.deathTicks--;
      // He comes apart as air rather than falling: the last of the momentum is spent on the way out.
      BossVfx.sphere(level, boss.position(), 3.0 + (DEATH_CEREMONY_TICKS - fight.deathTicks) * 0.08, 2, ParticleTypes.CLOUD);
      if (ServerClock.clock(level) % 6L == 0L) {
         level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.BREEZE_IDLE_AIR, SoundSource.HOSTILE, 1.3F, 0.6F);
      }
      if (fight.deathTicks > 0) {
         return;
      }
      // One last gust, outward, on the way down - the fight's own move, used as its signature.
      BossVfx.disc(level, boss.position(), 16.0, ParticleTypes.GUST, 0.4);
      BossVfx.sphere(level, boss.position(), 8.0, 3, ParticleTypes.GUST_EMITTER_LARGE);
      for (ServerPlayer p : playersNear(level, boss.position(), 12.0)) {
         Vec3 away = p.position().subtract(boss.position());
         push(p, new Vec3(away.x, 0.0, away.z), 1.6);
      }
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), ModSounds.BOSS_DEATH, SoundSource.HOSTILE, 2.0F, 1.1F);
      onBossDeath(level, boss);
      if (boss.isAlive()) {
         boss.hurtServer(level, level.damageSources().generic(), boss.getMaxHealth() * 4.0F + 100.0F);
      }
   }

   private static void grantLoot(ServerLevel level, Mob boss, Fight fight) {
      // The sky set's own forge material, for the same reason the sea boss pays pearls rather
      // than the shared essence: Gale Cores are what a Warden's legendary is upgraded with, and
      // the set cannot be taken past Tier I without them.
      int cores = 3 + RANDOM.nextInt(3);
      for (int i = 0; i < cores; i++) {
         drop(level, boss, ModItems.galeCore());
      }
      if (RANDOM.nextFloat() < 0.22F) {
         drop(level, boss, switch (RANDOM.nextInt(3)) {
            case 0 -> ModItems.skybreaker();
            case 1 -> ModItems.galeChakram();
            default -> ModItems.wardensMantle();
         });
      }
      BossPayout.payBoxes(
         level, fight.participants, ModItems::galeLootBox, BossPayout.BOXES_PER_KILL, "\u00a7fGale Loot Box"
      );
      for (UUID id : fight.participants) {
         ServerPlayer p = level.getServer().getPlayerList().getPlayer(id);
         if (p != null && p.isAlive()) {
            Advancements.grant(p, "kill_gale_warden");
         }
      }
   }

   private static void drop(ServerLevel level, Mob boss, ItemStack stack) {
      if (stack != null && !stack.isEmpty()) {
         level.addFreshEntity(new ItemEntity(level, boss.getX(), boss.getY() + 0.6, boss.getZ(), stack));
      }
   }

   // ------------------------------------------------------------------ teardown

   private static void release(MinecraftServer server, Fight fight) {
      fight.bar.setVisible(false);
      for (ServerPlayer p : server.getPlayerList().getPlayers()) {
         fight.bar.removePlayer(p);
      }
      FIGHTS.remove(fight.bossId);
   }

   private static void shutDown(MinecraftServer server, Fight fight) {
      Mob boss = bossOf(server, fight);
      if (boss != null) {
         boss.discard();
      }
      release(server, fight);
   }

   // ------------------------------------------------------------------ helpers

   private static Vec3 randomFlat() {
      Vec3 v = new Vec3(RANDOM.nextDouble() - 0.5, 0.0, RANDOM.nextDouble() - 0.5);
      return v.lengthSqr() < 1.0E-4 ? new Vec3(1.0, 0.0, 0.0) : v.normalize();
   }

   /** Every living player in this fight's level, which is who the momentum systems read. */
   private static List<ServerPlayer> participants(ServerLevel level) {
      List<ServerPlayer> out = new ArrayList<>();
      for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
         if (p.isAlive() && !p.isCreative() && !p.isSpectator() && p.level() == level) {
            out.add(p);
         }
      }
      return out;
   }

   private static List<ServerPlayer> playersNear(ServerLevel level, Vec3 pos, double radius) {
      List<ServerPlayer> out = new ArrayList<>();
      double r2 = radius * radius;
      for (ServerPlayer p : participants(level)) {
         if (p.distanceToSqr(pos.x, pos.y, pos.z) <= r2) {
            out.add(p);
         }
      }
      return out;
   }

   private static net.minecraft.world.phys.AABB box(Vec3 pos, double radius) {
      return new net.minecraft.world.phys.AABB(
         pos.x - radius, pos.y - radius, pos.z - radius, pos.x + radius, pos.y + radius, pos.z + radius
      );
   }

   private static ServerPlayer nearestPlayer(ServerLevel level, Mob boss, double radius) {
      ServerPlayer best = null;
      double bestDist = radius * radius;
      for (ServerPlayer p : participants(level)) {
         double d = p.distanceToSqr(boss);
         if (d < bestDist) {
            bestDist = d;
            best = p;
         }
      }
      return best;
   }

   private static void push(ServerPlayer p, Vec3 dir, double power) {
      Vec3 unit = dir.lengthSqr() < 1.0E-5 ? new Vec3(1.0, 0.0, 0.0) : dir.normalize();
      p.setDeltaMovement(p.getDeltaMovement().add(unit.scale(power)).add(0.0, 0.36, 0.0));
      p.hurtMarked = true;
   }

   private static void pull(ServerPlayer p, Vec3 center, double power) {
      Vec3 to = center.subtract(p.position());
      Vec3 flat = new Vec3(to.x, 0.0, to.z);
      if (flat.lengthSqr() < 0.04) {
         return;
      }
      p.setDeltaMovement(p.getDeltaMovement().add(flat.normalize().scale(power)));
      p.hurtMarked = true;
   }

   private static Mob bossOf(MinecraftServer server, Fight fight) {
      Entity raw = findEntity(server, fight.bossId);
      return raw instanceof Mob mob && mob.isAlive() ? mob : null;
   }

   private static Entity findEntity(MinecraftServer server, UUID id) {
      if (server == null || id == null) {
         return null;
      }
      for (ServerLevel level : server.getAllLevels()) {
         Entity e = level.getEntity(id);
         if (e != null) {
            return e;
         }
      }
      return null;
   }

   /**
    * Whether the boss says this out loud. Speech is off; the fight is not narrated.
    *
    * <p>He had a voice - a taunt every eleven seconds, a line for each phase, a quotation wrapped
    * around most of his moves - and all of it arrived in chat. A fight that describes itself is a
    * fight the player reads instead of watches, so anything carrying a quotation mark is dropped
    * here, at the one funnel every line passes through. The move *tells* are not speech and are
    * untouched: "VACUUM" and "the floor is marked" are how a move is read, so they stay.
    */
   private static void overlayNear(ServerLevel level, Mob boss, double range, String message) {
      // Telegraph removed
   }

   // ------------------------------------------------------------- codex + probes

   public static List<String> movesForPhase(int phase) {
      return switch (phase) {
         case 3 -> MOVES_3;
         case 2 -> MOVES_2;
         default -> MOVES_1;
      };
   }

   public static double maxHealth() {
      return MAX_HEALTH;
   }

   public static double phase2At() {
      return PHASE_2_AT;
   }

   public static double phase3At() {
      return PHASE_3_AT;
   }

   public static int moveGapTicks(int phase) {
      return moveGap(phase);
   }

   public static int chargeTicks() {
      return CHARGE_TICKS;
   }

   public static float chargeBreakDamage() {
      return CHARGE_BREAK_DAMAGE;
   }

   public static int stanceTicks() {
      return STANCE_TICKS;
   }

   public static int theftTicks() {
      return THEFT_TICKS;
   }
}
