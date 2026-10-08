package com.fortuneandfavors.economy;

import com.fortuneandfavors.ModItems;
import com.fortuneandfavors.ModSounds;
import com.fortuneandfavors.mixin.MobGoalAccessor;
import com.fortuneandfavors.mixin.MobTargetAccessor;
import com.fortuneandfavors.util.Chat;
import com.fortuneandfavors.util.Safe;
import com.mojang.math.Transformation;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.particles.DustParticleOptions;
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
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MoveTowardsTargetGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * <b>The Clockwork King</b> - the raid boss called through by the Clockwork Core.
 *
 * <h2>He is fought as an arsenal, not as a health bar</h2>
 * The whole design lives in one rule: <b>his armour is his machinery</b>. While
 * he still has a full arsenal, his machines are worth <i>one</i> tier of
 * Resistance - enough that ignoring them costs you, never so much that the
 * armour becomes a wall you cannot damage through. The health bar only really
 * moves once you have torn the machines off him, which is what makes the fight a
 * room-management problem instead of a damage race.
 *
 * <p>So killing a machine is never wasted effort: it strips a tier of armour, it
 * drops a chunk of his real health, and it leaves him slowed and lit up for a
 * short window. The counter-play is deliberate in both directions - ignoring the
 * machines means hitting a wall, and clearing them all means he rebuilds.
 *
 * <h2>He also fights back</h2>
 * He walks on real mob AI - he falls, he paths around obstacles, and he closes on
 * whatever player he has picked - but every <i>attack</i> is scripted here rather
 * than left to vanilla, and every hit is shown before it lands: a piston slam onto a
 * marked ring (leave it), a blade sweep through a marked band (hug him or back off),
 * a magnet pull, a turret barrage that paints its sightlines first (break line of
 * sight), a pendulum struck down a drawn lane (step off it), a live floor that goes
 * off half a second after a bell (jump), and - in his last phase - a core detonation
 * inside a dome (get out), after which he is genuinely vulnerable for a few seconds. He is given no melee goal on purpose: the
 * machinery is the damage, and a fist on top of it turns the fight into a
 * shoving match.
 *
 * <h2>Machines</h2>
 * Each machine is a real, killable entity wearing a role tag:
 * <ul>
 *   <li>{@link Role#TURRET} - a stationary gun that fires arrows at the nearest player.</li>
 *   <li>{@link Role#BLADE} - an orbiting blade that cuts anyone who stands close.</li>
 *   <li>{@link Role#PISTON} - a ram that launches itself at a nearby player.</li>
 *   <li>{@link Role#DRONE} - a hovering gun that hovers over the King and snipes.</li>
 * </ul>
 */
public final class ClockworkKingManager {

   /** Entity tag marking the King himself. */
   private static final String TAG = "ff_clockwork_king";
   /** Entity tag on every machine he deploys, whatever its role. */
   private static final String MACHINE_TAG = "ff_clockwork_machine";
   private static final String MACHINE_ROLE_KEY = "ff_machine_role";
   private static final String MACHINE_BOSS_KEY = "ff_machine_boss";
   /** Entity tag on every block display of his own armour, so an orphaned plate can be
    *  swept up after a crash or a chunk unload. */
   private static final String PLATE_TAG = "ff_clockwork_rig";
   /**
    * Entity tag on the block display bolted to each machine (its chassis).
    *
    * <p>The chassis used to carry {@link #PLATE_TAG} too, while the orphan sweep only counted
    * <i>plate</i> ids as owned - so every fifteen seconds the sweep tore the chassis off every
    * live machine and the turrets went back to looking like shulkers. A chassis has its own
    * tag now and is owned while the machine it belongs to is alive in a running fight.
    */
   private static final String CHASSIS_TAG = "ff_clockwork_chassis";
   /**
    * How often (in ticks) the rig sweep runs: every loaded display carrying one of his tags
    * whose owner is not alive and loaded is discarded. It runs whether or not a fight is
    * live, because the displays left behind by an unloaded or crashed fight come back when
    * their chunk does - typically long after the fight itself is gone.
    */
   private static final long RIG_SWEEP_INTERVAL = 100L;

   private static final String BOSS_NAME = "\u00a76\u00a7l\u2699 The Clockwork King";
   private static final String SAY = "\u00a76The Clockwork King\u00a7r\u00a77 \u203a \u00a7f";

   private static final double MAX_HEALTH = 700.0;
   private static final double ARENA_RADIUS = 72.0;
   /** How far from a player a stray King body is looked for. Deliberately just
    *  wider than the widest arena in the mod (90), so a King standing anywhere
    *  in his arena is still found - and no wider, because this is a box query
    *  charged to every player who triggers it. */
   public static final double STRAY_SWEEP_RADIUS = 96.0;
   /**
    * The most Resistance his machinery can ever be worth, in tiers.
    *
    * <p>This used to scale to <b>four</b> tiers - an 80% damage cut, permanently
    * reapplied while any machine lived - which made a 1200 HP boss worth more
    * than 6000 effective HP and read to players as a boss that simply would not
    * die. One tier is the armour: it makes clearing the arsenal worthwhile without
    * turning a damage race into a damage wall.
    */
   private static final int MAX_SHIELD_TIERS = 1;
   /** His walking pace, as a multiplier on the iron golem's own movement speed. */
   private static final double WALK_SPEED = 0.45;
   /** How close he brings himself before he stops walking. Deliberately just
    *  outside player reach: he is meant to loom over the fight, not shove you. */
   private static final float WALK_STOP_DISTANCE = 3.5F;

   /** Fraction of max health a machine's destruction tears off the shield. */
   private static final float SHIELD_BREAK_HP = 0.03F;
   /** How long the vulnerability window lasts after a machine dies. */
   private static final int BREAK_WINDOW_TICKS = 60;

   private static final int SLAM_COOLDOWN = 150;
   private static final int SWEEP_COOLDOWN = 200;
   private static final int PULL_COOLDOWN = 280;
   private static final int BARRAGE_COOLDOWN = 340;
   private static final int DETONATE_COOLDOWN = 420;
   private static final int REBUILD_COOLDOWN = 240;
   private static final int PENDULUM_COOLDOWN = 260;
   private static final int CURRENT_COOLDOWN = 360;
   /** The breath between any two of his moves, so two warnings never land on the same beat. */
   private static final int MOVE_GAP = 50;

   /** Piston Slam: the ring he lands in. */
   private static final double SLAM_RADIUS = 4.5;
   /** Blade Sweep: the warning, and the band the blades cut (inside is safe, outside is safe). */
   private static final int SWEEP_WARN = 16;
   private static final double SWEEP_INNER = 4.0;
   private static final double SWEEP_OUTER = 10.0;
   /** Turret Barrage: how long the sightlines are shown before the guns fire. */
   private static final int VOLLEY_WARN = 25;
   /** Pendulum: a lane through a player, struck end to end. */
   private static final int PENDULUM_WARN = 40;
   private static final double PENDULUM_REACH = 9.0;
   private static final double PENDULUM_HALF_WIDTH = 1.75;
   private static final float PENDULUM_DAMAGE = 13.0F;
   /** Live Current: the floor round him electrified; the bell is the cue to jump. */
   private static final int CURRENT_WARN = 50;
   private static final int CURRENT_BELL = 10;
   private static final double CURRENT_RADIUS = 14.0;
   private static final float CURRENT_DAMAGE = 10.0F;
   /** Core Detonation: the dome you have to be outside of. */
   private static final double DETONATE_RADIUS = 16.0;

   /** How long he takes to bolt himself together before the fight starts. */
   private static final int RISE_TICKS = 60;
   /** A minute with nobody in the arena and he powers down instead of standing there forever. */
   private static final int EMPTY_ARENA_TICKS = 1200;

   /** His colours: polished brass, the furnace in his chest, and the arc off his coils. */
   private static final int BRASS = 0xE0A93B;
   private static final int EMBER = 0xFF5A1F;
   private static final int ARC = 0x8FE3FF;

   /** Machines he holds at each phase. Everything past the first is rebuilt. */
   private static final int P1_MACHINES = 4;
   private static final int P2_MACHINES = 6;
   private static final int P3_MACHINES = 8;
   private static final int HARD_MACHINE_CAP = 10;

   /** How long the death ceremony pauses before the loot lands. */
   private static final int DEATH_CEREMONY_TICKS = 90;

   private static final Random RANDOM = new Random();

   private enum Role {
      TURRET, BLADE, PISTON, DRONE
   }

   /**
    * One plate of the King's own armour.
    *
    * <p>The fight's central promise is that his armour <em>is</em> his machinery,
    * and a health bar cannot say that on its own. Each plate is a real block
    * display bolted to a spot on his body, gated on a tier: as the arsenal is torn
    * off him the plates physically fly off, and when he rebuilds he reassembles
    * them. Nothing here deals damage or has a hitbox - it is the visible state of
    * the fight, not another thing to fight.
    */
   private static final class Plate {
      /** Offset in his own frame: right, up, forward. */
      final double ox;
      final double oy;
      final double oz;
      final float scale;
      final BlockState state;
      /** The armour tier this plate stays attached to. */
      final int tier;
      UUID displayId;

      Plate(double ox, double oy, double oz, float scale, BlockState state, int tier) {
         this.ox = ox;
         this.oy = oy;
         this.oz = oz;
         this.scale = scale;
         this.state = state;
         this.tier = tier;
      }
   }

   /** A deployed machine. The entity is the source of truth; this tracks its job. */
   private static final class Machine {
      final UUID id;
      final Role role;
      /** Clock time of its next shot or ram. A long: the clock is, and an int cast wraps. */
      long nextAction;
      /** Piston ram only: the lane it has shown, the wind-up before it goes, and the dash itself. */
      Vec3 dashDir;
      int windup;
      int dashTicks;
      /** The block display riding it, so it can be discarded with the machine. */
      UUID chassisId;

      Machine(UUID id, Role role, long nextAction) {
         this.id = id;
         this.role = role;
         this.nextAction = nextAction;
      }
   }

   /**
    * A hit that has been shown and has not landed yet.
    *
    * <p>Every new attack is a promise first: the floor is marked, the warning plays, and the
    * hit lands {@code landAt} later exactly where it was drawn. Keeping them in a list (rather
    * than one charge counter per move) is what lets the death ceremony and the teardown cancel
    * all of them in one line.
    */
   private static final class Strike {
      static final int SWEEP = 0;
      static final int VOLLEY = 1;
      static final int PENDULUM = 2;
      static final int CURRENT = 3;
      final int kind;
      final Vec3 at;
      /** Pendulum only: the lane's direction, flat and normalised. */
      final Vec3 dir;
      final long landAt;
      final double radius;
      final float damage;
      /** Set once the last-moment cue (the falling bob, the bell) has played. */
      boolean cued;

      Strike(int kind, Vec3 at, Vec3 dir, long landAt, double radius, float damage) {
         this.kind = kind;
         this.at = at;
         this.dir = dir;
         this.landAt = landAt;
         this.radius = radius;
         this.damage = damage;
      }
   }

   private static final class Fight {
      final UUID bossId;
      final UUID summoner;
      final ServerBossEvent bar;
      /**
       * Set once the shutdown sequence has run and the body is allowed to die.
       *
       * <p>Until this exists the ceremony cancels every killing blow, including the one
       * it sends itself - so the King survived his own funeral and stood in an empty
       * suit forever, with the fight held open and the plating sweep treating him as
       * owed. The flag is what makes "nobody may kill him mid-sequence" and "the
       * sequence must be able to kill him at the end" two different states instead of
       * a contradiction.
       */
      boolean paid;
      final Set<UUID> participants = new HashSet<>();
      final Map<UUID, Machine> machines = new HashMap<>();
      final List<Plate> plates = new ArrayList<>();
      final List<Strike> strikes = new ArrayList<>();
      /** Ticks left of his assembly. Counted down per tick, never on the clock. */
      int riseTicks = RISE_TICKS;
      /** Consecutive ticks with nobody in the arena. */
      int emptyTicks;
      int phase = 1;
      boolean dying;
      int deathTicks;
      long nextSlam;
      long nextSweep;
      long nextPull;
      long nextBarrage;
      long nextDetonate;
      long nextRebuild;
      long nextTaunt;
      long nextPendulum;
      long nextCurrent;
      long nextMove;
      int slamCharge;
      int detonateCharge;
      Vec3 slamTarget;
      /** Where the core blast was armed. Fixed, so the dome he showed is the dome that goes off. */
      Vec3 detonateAt;
      /** Game time until which a broken machine has left him slowed and lit up. */
      long breakWindowUntil;

      Fight(UUID bossId, UUID summoner, ServerBossEvent bar) {
         this.bossId = bossId;
         this.summoner = summoner;
         this.bar = bar;
      }
   }

   private static final Map<UUID, Fight> FIGHTS = new HashMap<>();
   /** Boss id -> the machine ids it owns, so a stray machine can always find home. */
   private static final Map<UUID, UUID> MACHINE_OWNER = new HashMap<>();

   private ClockworkKingManager() {
   }

   // ------------------------------------------------------------------ public API

   public static boolean isClockworkKing(Entity entity) {
      return entity != null && entity.entityTags().contains(TAG);
   }

   public static boolean isMachine(Entity entity) {
      return entity != null && entity.entityTags().contains(MACHINE_TAG);
   }

   /**
    * Test hook: the bar of a live fight, handed out rather than read here because the assertion
    * that matters is made *after* the fight is released, when no lookup by boss can find it.
    */
   public static ServerBossEvent barForTest(UUID id) {
      Fight f = id == null ? null : FIGHTS.get(id);
      return f == null ? null : f.bar;
   }

   /** Test hook: is this body's fight still registered? */
   public static boolean hasFightForTest(UUID id) {
      return id != null && FIGHTS.containsKey(id);
   }

   /**
    * Test hook: end every live fight, take the arsenal and the plating with it.
    *
    * <p>The plating half is the point: a display entity nobody discards outlives every
    * other trace of the fight, and it is the one piece of a boss that a player can still
    * see after everybody involved has logged off.
    */
   public static void clearForTest(MinecraftServer server) {
      for (Fight fight : new ArrayList<>(FIGHTS.values())) {
         Mob boss = bossOf(server, fight);
         if (boss != null) {
            boss.discard();
         }
         release(server, fight);
      }
      FIGHTS.clear();
      sweepOrphanPlating(server);
   }

   public static int activeCount() {
      return FIGHTS.size();
   }

   public static int machineCount(UUID bossId) {
      Fight fight = FIGHTS.get(bossId);
      return fight == null ? 0 : fight.machines.size();
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

   /** True when this entity is one of the King's deployed machines. Used by the
    *  death hook so a torn-off turret pays out its shield break without needing
    *  the machine to be part of the boss registry. */
   public static boolean isOwnedMachine(Mob mob) {
      return mob != null && (isMachine(mob) || MACHINE_OWNER.containsKey(mob.getUUID()));
   }

   /** Ends every active fight, boss and machines included. Used by {@code /ff boss}. */
   public static int abandonAll(MinecraftServer server) {
      int ended = 0;
      for (Fight fight : new ArrayList<>(FIGHTS.values())) {
         Safe.run("clockwork abandon", () -> shutDown(server, fight, true));
         ended++;
      }
      Safe.run("clockwork rig sweep", () -> sweepOrphanPlating(server));
      return ended;
   }

   /** Never leave a King or his machines behind on a server stop. */
   public static void onServerStopping(MinecraftServer server) {
      for (Fight fight : new ArrayList<>(FIGHTS.values())) {
         Safe.run("clockwork shutdown", () -> shutDown(server, fight, true));
      }
      FIGHTS.clear();
      MACHINE_OWNER.clear();
      // With no fight left, every loaded display or machine carrying one of his tags is an
      // orphan: take them all now, so none of them is saved into a chunk on the way down.
      Safe.run("clockwork rig sweep", () -> sweepOrphanPlating(server));
   }

   /** Clockwork Core right-click: wind the key, spend it, and let him assemble. */
   public static String useClockworkCore(ServerPlayer player, ItemStack held) {
      String err = summon(player);
      if (err != null) {
         return err;
      }
      if (!player.getAbilities().instabuild) {
         held.shrink(1);
      }
      return null;
   }

   /** Clockwork Core right-click: wind the key and let him assemble. */
   private static String summon(ServerPlayer summoner) {
      if (!ModConfig.is("boss")) {
         return "Bosses are disabled on this server.";
      }

      // A fight whose King has vanished is not a fight. Without this sweep one lost
      // entity (a chunk unload, a restart, a crash) left the entry in FIGHTS
      // forever and the summoner was told they already had a fight they could not
      // find - so he could never be summoned again.
      for (Fight f : new ArrayList<>(FIGHTS.values())) {
         Entity live = findEntity(summoner.level().getServer(), f.bossId);
         if (live == null || !live.isAlive()) {
            shutDown(summoner.level().getServer(), f, true);
         }
      }

      for (Fight f : FIGHTS.values()) {
         if (summoner.getUUID().equals(f.summoner)) {
            return "You already have a Clockwork King running - shut him down first!";
         }
      }

      ServerLevel level = summoner.level();
      Mob boss = (Mob) EntityTypes.IRON_GOLEM.create(level, EntitySpawnReason.COMMAND);
      if (boss == null) {
         return "The gears seized - he did not assemble.";
      }

      AttributeInstance maxHp = boss.getAttribute(Attributes.MAX_HEALTH);
      if (maxHp != null) {
         maxHp.setBaseValue(MAX_HEALTH);
      }
      boss.setHealth((float) MAX_HEALTH);
      AttributeInstance dmg = boss.getAttribute(Attributes.ATTACK_DAMAGE);
      if (dmg != null) {
         dmg.setBaseValue(18.0);
      }
      AttributeInstance follow = boss.getAttribute(Attributes.FOLLOW_RANGE);
      if (follow != null) {
         follow.setBaseValue(ARENA_RADIUS);
      }
      AttributeInstance kb = boss.getAttribute(Attributes.KNOCKBACK_RESISTANCE);
      if (kb != null) {
         kb.setBaseValue(1.0);
      }

      boss.setPersistenceRequired();
      boss.setCustomName(Component.literal(BOSS_NAME));
      boss.setCustomNameVisible(true);
      // He is a real mob, not a puppet. This is what gives him gravity - and so
      // stops him hovering after the Piston Slam throws him six blocks up - and
      // real pathfinding, so a wall sends him round it instead of into it.
      boss.setNoAi(false);
      installAi(boss);
      boss.addTag(TAG);
      // The shared marker plus the visible-and-persistent guarantee: see
      // BossManager.markBoss for why a boss has to say so itself.
      BossManager.markBoss(boss);
      double x = summoner.getX();
      double y = summoner.getY();
      double z = summoner.getZ();
      boss.setPos(x, y, z);
      boss.setYRot(summoner.getYRot());
      // He is put together on the spot: held still and untouchable until the last plate is on.
      // tickRise lets him go.
      boss.setNoAi(true);
      boss.setInvulnerable(true);
      level.addFreshEntity(boss);

      ServerBossEvent bar = new ServerBossEvent(
         UUID.randomUUID(), Component.literal(BOSS_NAME), BossBarColor.YELLOW, BossBarOverlay.PROGRESS
      );
      bar.setVisible(true);
      bar.setProgress(0.0F);
      // Only the room gets the bar. It used to go to every player on the server, the Nether
      // and the End included; tickFight now adds people as they walk in.
      for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
         if (p.level() == level && p.distanceToSqr(boss) < ARENA_RADIUS * ARENA_RADIUS) {
            bar.addPlayer(p);
         }
      }
      bar.addPlayer(summoner);

      Fight fight = new Fight(boss.getUUID(), summoner.getUUID(), bar);
      fight.participants.add(summoner.getUUID());
      long now = ServerClock.clock(level);
      // Real cooldowns are set when he finishes assembling (arrive); these only keep the
      // fields sane if something reads them before then.
      fight.nextRebuild = now + RISE_TICKS + REBUILD_COOLDOWN;
      fight.nextTaunt = now + RISE_TICKS + 200L;
      FIGHTS.put(boss.getUUID(), fight);
      buildPlating(fight);

      announce(level, "\u00a78\u00a7m\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550");
      announce(level, "    \u00a76\u00a7l\u2699 THE CLOCKWORK KING ASSEMBLES \u2699");
      announce(level, "    \u00a77Gears drop into place. Something starts ticking.");
      announce(level, "\u00a78\u00a7m\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550");
      // The workshop floor: a brass summoning circle under him, a slower ring of ember runes
      // round it, and a spiral of arc climbing his frame while the plates go on.
      Vec3 pad = new Vec3(x, y, z);
      Fx.summonCircle(level, ParticleTypes.ELECTRIC_SPARK, pad.add(0.0, 0.05, 0.0), 4.5, RISE_TICKS + 10, BRASS);
      Fx.runeCircle(level, ParticleTypes.ELECTRIC_SPARK, pad.add(0.0, 0.1, 0.0), 7.0, RISE_TICKS, EMBER);
      Fx.spiral(level, ParticleTypes.ELECTRIC_SPARK, pad, 4.0, RISE_TICKS, ARC);
      // The workshop's own machinery: a great set of cogs turning flat in the floor under him,
      // and an upright set behind him winding him up like the key that was just turned.
      double yaw = Math.toRadians(summoner.getYRot());
      Vec3 facing = new Vec3(-Math.sin(yaw), 0.0, Math.cos(yaw));
      Fx.gearSpin(level, ParticleTypes.ELECTRIC_SPARK, pad.add(0.0, 0.15, 0.0), Vec3.ZERO, 3.0, RISE_TICKS, BRASS);
      Fx.gearSpin(level, ParticleTypes.ELECTRIC_SPARK, pad.add(facing.scale(-1.4)).add(0.0, 1.8, 0.0), facing, 1.2, RISE_TICKS, EMBER);
      level.playSound(null, x, y, z, ModSounds.BOSS_SPAWN, SoundSource.HOSTILE, 1.4F, 0.6F);
      level.playSound(null, x, y, z, SoundEvents.SMITHING_TABLE_USE, SoundSource.HOSTILE, 1.6F, 0.5F);
      level.playSound(null, x, y, z, SoundEvents.RESPAWN_ANCHOR_CHARGE, SoundSource.HOSTILE, 1.2F, 0.6F);
      Advancements.grant(summoner, "summon_clockwork");
      return null;
   }

   // ------------------------------------------------------------------------ tick

   public static void tick(MinecraftServer server) {
      // A boss that no longer exists can never be paid again, so its ledger entry
      // is dead weight - drop it before the early return so an idle server does
      // not hold on to the last fight's UUID forever.
      if (!LOOT_PAID.isEmpty()) {
         LOOT_PAID.removeIf(id -> findEntity(server, id) == null);
      }
      long now = ServerClock.clock(server.overworld());

      // Orphan sweep. Plating and chassis are saved to chunk data like any other entity, so
      // a crash, a restart or a chunk unloading under a live fight would otherwise bring a
      // suit of armour back standing over an empty patch of ground forever. This runs before
      // the no-fight early return on purpose: those displays reappear when their chunk
      // loads again, which is usually after the fight that owned them has been released.
      if (now % RIG_SWEEP_INTERVAL == 0L) {
         Safe.run("clockwork plating sweep", () -> sweepOrphanPlating(server));
      }

      if (FIGHTS.isEmpty()) {
         return;
      }

      for (Fight fight : new ArrayList<>(FIGHTS.values())) {
         Safe.run("clockwork king tick", () -> tickFight(server, fight, now));
      }

      // Orphaned machines (their King is gone) shut themselves down, so a kill or a
      // crash can never leave a stray turret firing at an empty field.
      if (MACHINE_OWNER.isEmpty()) {
         return;
      }
      for (Iterator<Map.Entry<UUID, UUID>> it = MACHINE_OWNER.entrySet().iterator(); it.hasNext();) {
         Map.Entry<UUID, UUID> entry = it.next();
         Fight fight = FIGHTS.get(entry.getValue());
         // A dying fight still owns its machines: the ceremony powers them down one at a
         // time. Sweeping them here as well ("!fight.dying") pulled the whole arsenal out on
         // the ceremony's first tick, so its clank-by-clank shutdown never had anything to do.
         if (fight != null) {
            continue;
         }
         Entity machine = findEntity(server, entry.getKey());
         if (machine != null) {
            shutDownMachine(machine, null, false);
         }
         it.remove();
      }
   }

   /** Removes plating that belongs to no running fight. */
   /**
    * Sweeps up King bodies left behind by a crash or a hard restart.
    *
    * <p>The whole fight lives in an in-memory map, so a King saved into a chunk
    * when the server died comes back with nothing to drive him: he does not tick,
    * does not move, and never dies, because the ceremony that kills him only runs
    * from {@link #tick}. That is a statue standing in the arena forever, and it is
    * exactly what "he is just flying and does not drop anything" looked like.
    * Run near players, because chunks are not loaded on boot.
    *
    * @return how many bodies were removed
    */
   public static int sweepStrays(MinecraftServer server) {
      int removed = 0;
      // Perf: this ran one 257-block entity query per player, so a server with a
      // dozen players standing together paid for the same answer a dozen times
      // in the same tick. The radius now matches the largest arena in the mod
      // (90), and a player already inside a box swept this pass is skipped
      // rather than queried again.
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
            if (isClockworkKing(mob) && !FIGHTS.containsKey(mob.getUUID())) {
               mob.discard();
               removed++;
            }
         }
      }
      return removed;
   }

   /**
    * Discards every loaded piece of his rig whose owner is not alive and loaded: armour plates
    * of a King with no running fight, chassis whose machine is dead, gone or no longer part of
    * a running fight, and machines nobody is driving any more.
    *
    * <p>What counts as owned is rebuilt from the live fights each pass, and only a fight whose
    * King is actually alive in a loaded chunk owns anything - a fight whose body has vanished
    * is torn down on its next tick anyway. A plate is owned by id. A chassis is owned only
    * while it is still riding the machine it was bolted to, so one thrown off a dying machine
    * (vanilla ejects passengers when an entity is removed) goes even before the machine's
    * entry is cleared. Every loaded entity in every loaded level is checked, not just the
    * ones near a player: a suit left standing in an arena the group walked away from is
    * exactly the one nobody comes back to clean up.
    */
   private static void sweepOrphanPlating(MinecraftServer server) {
      Set<UUID> ownedPlates = new HashSet<>();
      Set<UUID> ownedMachines = new HashSet<>();
      Set<UUID> ownedChassis = new HashSet<>();
      for (Fight fight : FIGHTS.values()) {
         Mob boss = bossOf(server, fight);
         if (boss == null || boss.isRemoved()) {
            continue;
         }
         for (Plate plate : fight.plates) {
            if (plate.displayId != null) {
               ownedPlates.add(plate.displayId);
            }
         }
         for (Machine machine : fight.machines.values()) {
            ownedMachines.add(machine.id);
            if (machine.chassisId != null) {
               ownedChassis.add(machine.chassisId);
            }
         }
      }
      List<Entity> orphans = new ArrayList<>();
      for (ServerLevel level : server.getAllLevels()) {
         for (Entity entity : level.getAllEntities()) {
            if (entity == null || entity.isRemoved()) {
               continue;
            }
            Set<String> tags = entity.entityTags();
            if (entity instanceof Display) {
               if (tags.contains(PLATE_TAG)) {
                  if (!ownedPlates.contains(entity.getUUID())) {
                     orphans.add(entity);
                  }
               } else if (tags.contains(CHASSIS_TAG)) {
                  Entity ride = entity.getVehicle();
                  boolean seated = ride != null && ride.isAlive() && !ride.isRemoved() && ownedMachines.contains(ride.getUUID());
                  if (!seated || !ownedChassis.contains(entity.getUUID())) {
                     orphans.add(entity);
                  }
               }
            } else if (tags.contains(MACHINE_TAG) && !ownedMachines.contains(entity.getUUID())) {
               // A machine no running fight drives: left by a crash, a restart, or a chunk that
               // unloaded under it. It would otherwise stand in the arena forever with a name
               // over it and nothing to make it move.
               orphans.add(entity);
            }
         }
      }
      for (Entity orphan : orphans) {
         if (!orphan.isRemoved()) {
            if (isMachine(orphan)) {
               shutDownMachine(orphan, null, false);
            } else {
               orphan.discard();
            }
         }
      }
   }

   private static void tickFight(MinecraftServer server, Fight fight, long now) {
      Mob boss = bossOf(server, fight);
      if (boss == null) {
         shutDown(server, fight, true);
         return;
      }
      ServerLevel level = (ServerLevel) boss.level();

      // Track everyone who has been in the arena, so loot goes to the people who
      // actually fought rather than whoever is standing nearest at the end. The bar
      // follows the room: walk in and it appears, leave the dimension and it goes.
      int present = 0;
      double r2 = ARENA_RADIUS * ARENA_RADIUS;
      for (ServerPlayer p : server.getPlayerList().getPlayers()) {
         boolean here = p.level() == level;
         if (here && p.isAlive() && !p.isSpectator() && !BossManager.isFakePlayer(p) && p.distanceToSqr(boss) < r2) {
            fight.participants.add(p.getUUID());
            fight.bar.addPlayer(p);
            present++;
         } else if (!here || p.distanceToSqr(boss) > r2 * 2.25) {
            fight.bar.removePlayer(p);
         }
      }

      if (fight.dying) {
         tickDeath(server, boss, fight);
         return;
      }

      // His health bar is driven by hand. He carries an ordinary iron golem's
      // entity, and vanilla only writes bar progress for its own boss mobs - so
      // leaving this to vanilla is the bug that left the Scarlet Devil's bar full
      // for an entire fight.
      fight.bar.setProgress(Math.max(0.0F, Math.min(1.0F, boss.getHealth() / boss.getMaxHealth())));
      fight.bar.setName(Component.literal(phaseName(fight, boss)));
      // The bar's art follows the phase: red is the client's cue for the overheated bar.
      fight.bar.setColor(fight.phase >= 2 ? BossBarColor.RED : BossBarColor.YELLOW);

      if (fight.riseTicks > 0) {
         tickRise(level, boss, fight, now);
         return;
      }

      // Nobody left in the room: he winds down rather than guarding an empty arena
      // until the next restart.
      fight.emptyTicks = present > 0 ? 0 : fight.emptyTicks + 1;
      if (fight.emptyTicks >= EMPTY_ARENA_TICKS) {
         Fx.clockBurst(level, ParticleTypes.ELECTRIC_SPARK, boss.position().add(0.0, 1.6, 0.0), 3.0, BRASS);
         level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.BEACON_DEACTIVATE, SoundSource.HOSTILE, 1.6F, 0.6F);
         announce(level, "\u00a78The Clockwork King winds down. \u00a77Nobody left to fight.");
         shutDown(server, fight, true);
         return;
      }

      applyShield(server, boss, fight);
      tickMachines(server, boss, fight, now);
      tickPlating(level, boss, fight, now);
      tickStrikes(server, level, boss, fight, now);

      float share = boss.getHealth() / boss.getMaxHealth();
      int wanted = share > 0.6F ? 1 : share > 0.25F ? 2 : 3;
      if (wanted > fight.phase) {
         enterPhase(server, boss, fight, wanted);
      }

      if (now >= fight.nextRebuild && fight.machines.size() < phaseMachineCap(fight.phase)) {
         fight.nextRebuild = now + REBUILD_COOLDOWN;
         rebuild(level, boss, fight);
      }

      if (fight.slamCharge > 0) {
         tickSlamCharge(server, boss, fight);
      } else if (fight.detonateCharge > 0) {
         tickDetonateCharge(server, boss, fight);
      } else {
         chooseMove(server, boss, fight, now);
      }

      if (now >= fight.nextTaunt) {
         fight.nextTaunt = now + 260L + RANDOM.nextInt(160);
         taunt(boss, fight);
      }
   }

   // ------------------------------------------------------------------- arrival

   /**
    * His assembly. For three seconds he stands in the summoning circle, untouchable, while
    * arms of arc feed him from the four compass points and his plates bolt on tier by tier
    * (see {@link #visibleTiers}) - the room gets to watch him become a boss instead of being
    * hit by one the moment he exists. The bar fills as he is built.
    */
   private static void tickRise(ServerLevel level, Mob boss, Fight fight, long now) {
      fight.riseTicks--;
      int built = RISE_TICKS - fight.riseTicks;
      fight.bar.setProgress(Math.min(1.0F, built / (float) RISE_TICKS));
      tickPlating(level, boss, fight, now);
      if (fight.riseTicks > 0 && fight.riseTicks % 10 == 0) {
         // One arm per beat, walking round the compass: a pillar where it stands and a
         // beam of arc into his chest.
         double a = (built / 10) * (Math.PI / 2.0);
         Vec3 foot = boss.position().add(Math.cos(a) * 4.5, 0.0, Math.sin(a) * 4.5);
         Fx.pillar(level, ParticleTypes.ELECTRIC_SPARK, foot, 5.0, BRASS);
         Fx.beam(level, ParticleTypes.ELECTRIC_SPARK, foot.add(0.0, 3.0, 0.0), boss.position().add(0.0, 1.6, 0.0), ARC);
         level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.NOTE_BLOCK_HAT, SoundSource.HOSTILE, 1.4F, 0.6F + built / (float) RISE_TICKS);
         level.playSound(null, foot.x, foot.y, foot.z, SoundEvents.ANVIL_USE, SoundSource.HOSTILE, 0.7F, 1.4F);
      }
      if (fight.riseTicks == 30) {
         announceNear(level, boss, ARENA_RADIUS, SAY + "\"\u00a7fWho wound me?\"");
      }
      if (fight.riseTicks == 0) {
         arrive(level, boss, fight, now);
      }
   }

   /**
    * The last plate goes on: a brass flare, a starburst of sparks and a shockwave that shoves
    * (never hurts) anyone standing too close, then the first four machines unfold and the
    * clock starts on his moves.
    */
   private static void arrive(ServerLevel level, Mob boss, Fight fight, long now) {
      boss.setInvulnerable(false);
      boss.setNoAi(false);
      Vec3 chest = boss.position().add(0.0, 1.6, 0.0);
      Fx.flare(level, ParticleTypes.END_ROD, chest, 3.0, BRASS);
      Fx.starburst(level, ParticleTypes.ELECTRIC_SPARK, chest, 7.0, EMBER);
      Fx.shockwave(level, ParticleTypes.ELECTRIC_SPARK, boss.position(), 10.0, BRASS);
      Fx.clockBurst(level, ParticleTypes.ELECTRIC_SPARK, chest, 3.5, ARC);
      sonicBurst(level, boss.position().add(0.0, 0.3, 0.0), 8.0, 14, BRASS, 4);
      for (ServerPlayer p : participantsNear(level, boss, 6.0)) {
         Vec3 away = flatAway(p.position(), boss.position());
         p.push(away.x * 0.9, 0.4, away.z * 0.9);
         p.hurtMarked = true;
      }
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.ANVIL_LAND, SoundSource.HOSTILE, 1.6F, 0.5F);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.BELL_BLOCK, SoundSource.HOSTILE, 2.0F, 0.5F);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.LIGHTNING_BOLT_IMPACT, SoundSource.HOSTILE, 1.0F, 1.4F);

      // First assembly: the shop floor opens with two turrets and two blades.
      deploy(level, boss, fight, Role.TURRET, 2);
      deploy(level, boss, fight, Role.BLADE, 2);

      fight.nextMove = now + 40L;
      fight.nextSlam = now + 60L;
      fight.nextSweep = now + 140L;
      fight.nextPendulum = now + 200L;
      fight.nextPull = now + 280L;
      fight.nextBarrage = now + 340L;
      fight.nextCurrent = now + 300L;
      fight.nextDetonate = now + 600L;
      fight.nextRebuild = now + REBUILD_COOLDOWN;
      fight.nextTaunt = now + 200L;

      announce(level, SAY + "\"\u00a7fWound. \u00a76Running.\"");
      announceNear(level, boss, ARENA_RADIUS, "\u00a78His armour is his machines. \u00a77Break them first.");
   }

   // ------------------------------------------------------------------ machinery

   private static int phaseMachineCap(int phase) {
      return Math.min(HARD_MACHINE_CAP, phase == 1 ? P1_MACHINES : phase == 2 ? P2_MACHINES : P3_MACHINES);
   }

   /** Spawns {@code count} machines of one role in a ring around the King. */
   private static void deploy(ServerLevel level, Mob boss, Fight fight, Role role, int count) {
      for (int i = 0; i < count; i++) {
         if (fight.machines.size() >= HARD_MACHINE_CAP) {
            return;
         }
         double angle = RANDOM.nextDouble() * Math.PI * 2.0;
         double radius = 3.5 + RANDOM.nextDouble() * 3.0;
         double x = boss.getX() + Math.cos(angle) * radius;
         double y = boss.getY() + 1.0 + RANDOM.nextDouble() * 1.2;
         double z = boss.getZ() + Math.sin(angle) * radius;
         spawnMachine(level, boss, fight, role, x, y, z);
      }
   }

   private static Mob spawnMachine(ServerLevel level, Mob boss, Fight fight, Role role, double x, double y, double z) {
      net.minecraft.world.entity.EntityType<?> type = switch (role) {
         case TURRET -> EntityTypes.SHULKER;
         case BLADE -> EntityTypes.VEX;
         case PISTON -> EntityTypes.MAGMA_CUBE;
         case DRONE -> EntityTypes.BLAZE;
      };
      if (!(type.create(level, EntitySpawnReason.COMMAND) instanceof Mob machine)) {
         return null;
      }

      machine.setPersistenceRequired();
      machine.setNoAi(true);
      machine.setNoGravity(true);
      machine.setInvulnerable(false);
      machine.setCustomName(Component.literal(machineName(role)));
      machine.setCustomNameVisible(true);
      machine.addTag(MACHINE_TAG);
      machine.addTag(MACHINE_ROLE_KEY + ":" + role.name().toLowerCase(java.util.Locale.ROOT));
      machine.addTag(MACHINE_BOSS_KEY + ":" + boss.getUUID());
      machine.setPos(x, y, z);

      AttributeInstance maxHp = machine.getAttribute(Attributes.MAX_HEALTH);
      if (maxHp != null) {
         maxHp.setBaseValue(machineHealth(role));
      }
      machine.setHealth((float) machineHealth(role));

      // Machines are equipment, not loot: they must never drop a free slime ball or
      // a vex's sword into the arena when they are torn apart.
      machine.setDropChance(net.minecraft.world.entity.EquipmentSlot.MAINHAND, 0.0F);
      machine.setDropChance(net.minecraft.world.entity.EquipmentSlot.OFFHAND, 0.0F);
      machine.setDropChance(net.minecraft.world.entity.EquipmentSlot.HEAD, 0.0F);
      machine.setDropChance(net.minecraft.world.entity.EquipmentSlot.CHEST, 0.0F);
      machine.setDropChance(net.minecraft.world.entity.EquipmentSlot.LEGS, 0.0F);
      machine.setDropChance(net.minecraft.world.entity.EquipmentSlot.FEET, 0.0F);

      level.addFreshEntity(machine);
      Machine tracked = new Machine(machine.getUUID(), role, 0);
      fight.machines.put(machine.getUUID(), tracked);
      MACHINE_OWNER.put(machine.getUUID(), boss.getUUID());

      // Unfolded out of him: a beam from his chest to the spot, a small circle where it lands
      // and a cog spinning up in the air as the part winds itself together.
      Vec3 at = new Vec3(x, y + 0.4, z);
      Fx.beam(level, ParticleTypes.ELECTRIC_SPARK, boss.position().add(0.0, 1.6, 0.0), at, ARC);
      Fx.summonCircle(level, ParticleTypes.ELECTRIC_SPARK, at, 1.1, 14, BRASS);
      Fx.gearSpin(level, ParticleTypes.ELECTRIC_SPARK, at, flatAway(at, boss.position()), 0.7, 16, BRASS);
      Fx.vanilla(level, ParticleTypes.ELECTRIC_SPARK, x, y + 0.4, z, 18, 0.4, 0.4, 0.4, 0.05);
      Fx.vanilla(level, ParticleTypes.CRIT, x, y + 0.4, z, 10, 0.3, 0.3, 0.3, 0.06);
      level.playSound(null, x, y, z, SoundEvents.SMITHING_TABLE_USE, SoundSource.HOSTILE, 1.0F, 1.2F);
      tracked.chassisId = spawnChassis(level, machine, role);
      return machine;
   }

   /**
    * A block bolted onto a machine so it reads as machinery rather than as the
    * vanilla mob it is built on: a dispenser for a turret, a trapdoor for a blade,
    * a piston head for the ram and an observer for the drone.
    *
    * <p>The chassis RIDES the machine, so it follows it with no per-tick work at
    * all. It carries its own tag ({@link #CHASSIS_TAG}) and its id is kept on the machine,
    * so it is discarded with the machine however that goes, and the orphan sweep clears any
    * chassis that ends up riding nothing.
    *
    * @return the chassis's id, or null when none could be bolted on
    */
   private static UUID spawnChassis(ServerLevel level, Mob machine, Role role) {
      Display.BlockDisplay display = (Display.BlockDisplay) EntityTypes.BLOCK_DISPLAY.create(level, EntitySpawnReason.COMMAND);
      if (display == null) {
         return null;
      }
      BlockState state = switch (role) {
         case TURRET -> Blocks.DISPENSER.defaultBlockState();
         case BLADE -> Blocks.IRON_TRAPDOOR.defaultBlockState();
         case PISTON -> Blocks.PISTON.defaultBlockState();
         case DRONE -> Blocks.OBSERVER.defaultBlockState();
      };
      float lift = switch (role) {
         case TURRET -> 0.55F;
         case BLADE -> 0.20F;
         case PISTON -> 0.35F;
         case DRONE -> 0.45F;
      };
      display.setBlockState(state);
      display.setTransformation(
         new Transformation(new Vector3f(0.0F, lift, 0.0F), new Quaternionf(), new Vector3f(0.75F), new Quaternionf())
      );
      display.addTag(CHASSIS_TAG);
      display.setPos(machine.getX(), machine.getY(), machine.getZ());
      level.addFreshEntity(display);
      if (!display.startRiding(machine)) {
         // A chassis that could not mount would hang in the air where the machine was
         // made; better a bare machine than a floating block.
         display.discard();
         return null;
      }
      return display.getUUID();
   }

   private static String machineName(Role role) {
      return switch (role) {
         case TURRET -> "\u00a76\u00a7l\u2726 Clockwork Turret";
         case BLADE -> "\u00a76\u00a7l\u2726 Rotating Blade";
         case PISTON -> "\u00a76\u00a7l\u2726 Piston Ram";
         case DRONE -> "\u00a76\u00a7l\u2726 Scrap Drone";
      };
   }

   private static double machineHealth(Role role) {
      return switch (role) {
         case TURRET -> 44.0;
         case BLADE -> 34.0;
         case PISTON -> 38.0;
         case DRONE -> 28.0;
      };
   }

   /** Tops the arsenal back up, alternating roles so the mix never flattens out. */
   private static void rebuild(ServerLevel level, Mob boss, Fight fight) {
      if (fight.machines.size() >= phaseMachineCap(fight.phase)) {
         return;
      }
      if (fight.machines.size() < phaseMachineCap(fight.phase)) {
         deploy(level, boss, fight, Role.TURRET, 1);
      }
      if (fight.machines.size() < phaseMachineCap(fight.phase)) {
         deploy(level, boss, fight, Role.BLADE, 1);
      }
      if (fight.phase >= 2 && fight.machines.size() < phaseMachineCap(fight.phase)) {
         deploy(level, boss, fight, Role.DRONE, 1);
      }
      if (fight.phase >= 2 && fight.machines.size() < phaseMachineCap(fight.phase)) {
         deploy(level, boss, fight, Role.PISTON, 1);
      }
      if (fight.machines.size() >= phaseMachineCap(fight.phase)) {
         Fx.clockBurst(level, ParticleTypes.ELECTRIC_SPARK, boss.position().add(0.0, 1.6, 0.0), 3.0, BRASS);
         announceNear(level, boss, 64.0, SAY + "\"\u00a7fParts replaced.\"");
         announceNear(level, boss, 64.0, "\u00a78The arsenal is whole again. \u00a77So is his armour.");
      }
   }

   // ------------------------------------------------------------------- plating

   /**
    * The armour, laid out once. Offsets are in his own frame (right, up, forward),
    * so the whole suit turns with him rather than sliding around his body.
    *
    * <p>The tiers line up with {@link #visibleTiers}: tier 1 is the core he never
    * loses, tiers 2-3 are the plating you strip by killing machines, tiers 4-5 are
    * the heavy suit he only wears once the arsenal is complete.
    */
   private static void buildPlating(Fight fight) {
      fight.plates.clear();
      // Head and visor - the only part that never comes off, so he always reads
      // as a machine rather than a golem with some blocks stuck to it.
      fight.plates.add(new Plate(0.0, 2.45, 0.30, 0.62F, Blocks.IRON_BLOCK.defaultBlockState(), 1));
      fight.plates.add(new Plate(0.0, 2.45, 0.62, 0.34F, Blocks.REDSTONE_LAMP.defaultBlockState(), 1));
      // Chest and glowing core.
      fight.plates.add(new Plate(0.0, 1.90, 0.34, 0.86F, Blocks.IRON_BLOCK.defaultBlockState(), 2));
      fight.plates.add(new Plate(0.0, 1.60, 0.56, 0.42F, Blocks.REDSTONE_BLOCK.defaultBlockState(), 2));
      // Shoulders, hung off brackets.
      fight.plates.add(new Plate(0.92, 2.20, 0.0, 0.52F, Blocks.DEEPSLATE_TILES.defaultBlockState(), 3));
      fight.plates.add(new Plate(-0.92, 2.20, 0.0, 0.52F, Blocks.DEEPSLATE_TILES.defaultBlockState(), 3));
      fight.plates.add(new Plate(0.92, 2.52, 0.0, 0.30F, Blocks.IRON_BARS.defaultBlockState(), 3));
      fight.plates.add(new Plate(-0.92, 2.52, 0.0, 0.30F, Blocks.IRON_BARS.defaultBlockState(), 3));
      // Back plating and the hip carriage.
      fight.plates.add(new Plate(0.0, 1.92, -0.42, 0.90F, Blocks.DEEPSLATE_BRICKS.defaultBlockState(), 4));
      fight.plates.add(new Plate(0.0, 1.18, 0.0, 0.74F, Blocks.POLISHED_DEEPSLATE.defaultBlockState(), 4));
      // Legs - the last thing he loses, and the last thing he rebuilds.
      fight.plates.add(new Plate(0.48, 0.78, 0.0, 0.58F, Blocks.IRON_BLOCK.defaultBlockState(), 5));
      fight.plates.add(new Plate(-0.48, 0.78, 0.0, 0.58F, Blocks.IRON_BLOCK.defaultBlockState(), 5));
   }

   /**
    * How many tiers of plating he is still wearing. One machine per tier, exactly
    * like the Resistance shield, plus one cosmetic tier so the suit reads as a
    * suit at full strength; a stripped-down King is therefore visibly bare rather
    * than looking untouched.
    */
   private static int visibleTiers(Fight fight) {
      // While he is being assembled the suit goes on a tier every 14 ticks, stopping at the
      // four tiers his opening arsenal will hold - so nothing tears off the moment he arrives.
      if (fight.riseTicks > 0) {
         return Math.min(4, 1 + (RISE_TICKS - fight.riseTicks) / 14);
      }
      return Math.min(5, Math.max(0, fight.machines.size() - 1)) + 1;
   }

   /** Re-lays his suit every tick: attached plates follow him, the rest tear off. */
   private static void tickPlating(ServerLevel level, Mob boss, Fight fight, long now) {
      if (fight.plates.isEmpty()) {
         return;
      }
      int visible = visibleTiers(fight);
      for (Plate plate : fight.plates) {
         Entity display = plate.displayId == null ? null : findEntity(level.getServer(), plate.displayId);
         boolean attached = plate.tier <= visible;

         if (!attached) {
            if (display != null) {
               // It is being torn off right now: a real clatter of parts leaving, and the cog
               // that held it spinning loose.
               Fx.shatter(level, ParticleTypes.ELECTRIC_SPARK, display.position(), 0.8, BRASS);
               Fx.gearSpin(level, ParticleTypes.ELECTRIC_SPARK, display.position(), flatAway(display.position(), boss.position()), 0.45, 10, BRASS);
               Fx.vanilla(level, ParticleTypes.ELECTRIC_SPARK, display.getX(), display.getY(), display.getZ(), 14, 0.35, 0.35, 0.35, 0.12);
               Fx.vanilla(level, ParticleTypes.ITEM_SNOWBALL, display.getX(), display.getY(), display.getZ(), 8, 0.35, 0.35, 0.35, 0.06);
               Fx.vanilla(level, ParticleTypes.LARGE_SMOKE, display.getX(), display.getY(), display.getZ(), 6, 0.3, 0.3, 0.3, 0.04);
               level.playSound(null, display.getX(), display.getY(), display.getZ(), SoundEvents.ITEM_BREAK, SoundSource.HOSTILE, 1.0F, 0.6F);
               level.playSound(null, display.getX(), display.getY(), display.getZ(), SoundEvents.COPPER_BREAK, SoundSource.HOSTILE, 1.0F, 0.8F);
               display.discard();
            }
            plate.displayId = null;
            continue;
         }

         Vec3 at = worldOffset(boss, plate.ox, plate.oy, plate.oz);
         if (display == null) {
            display = spawnPlate(level, plate);
            if (display == null) {
               continue;
            }
            plate.displayId = display.getUUID();
            // Bolted on: a small cog turns it home. Drawn where the plate goes, not where the
            // fresh display was made - that is the world origin until it is moved below, which
            // is where these sparks used to land.
            Fx.gearSpin(level, ParticleTypes.ELECTRIC_SPARK, at, Vec3.ZERO, 0.4, 8, BRASS);
            Fx.vanilla(level, ParticleTypes.ELECTRIC_SPARK, at.x, at.y, at.z, 10, 0.3, 0.3, 0.3, 0.1);
            level.playSound(null, at.x, at.y, at.z, SoundEvents.COPPER_PLACE, SoundSource.HOSTILE, 1.0F, 0.7F);
         }

         display.setPos(at.x, at.y, at.z);
         display.setYRot(boss.getYRot());
         display.hurtMarked = true;
         // A spark off each plate now and then for clients without the mod; a modded client
         // gets the turning cog over his core below instead.
         if (now % 15L == 0L) {
            Fx.vanilla(level, ParticleTypes.ELECTRIC_SPARK, at.x, at.y, at.z, 1, 0.25, 0.25, 0.25, 0.0);
         }
      }
      // His core is a clock: a cog turns over his chest while he is running. One short shape
      // every second and a half, so it keeps up with him as he walks.
      if (fight.riseTicks <= 0 && !fight.dying && now % 30L == 0L) {
         Vec3 core = worldOffset(boss, 0.0, 1.6, 0.75);
         double yaw = Math.toRadians(boss.getYRot());
         // Straight to modded clients: everyone else already has the plate sparks above, and a
         // fallback every second and a half would be a steady stream of packets for Geyser.
         com.fortuneandfavors.net.FfVfx.shape(level, com.fortuneandfavors.util.FxKinds.GEAR_SPIN, ParticleTypes.ELECTRIC_SPARK, core,
            new Vec3(-Math.sin(yaw), 0.0, Math.cos(yaw)), 0.45, 14, fight.phase >= 3 ? EMBER : BRASS);
      }
   }

   private static Display.BlockDisplay spawnPlate(ServerLevel level, Plate plate) {
      Display.BlockDisplay display = (Display.BlockDisplay) EntityTypes.BLOCK_DISPLAY.create(level, EntitySpawnReason.COMMAND);
      if (display == null) {
         return null;
      }
      display.setBlockState(plate.state);
      display.setTransformation(
         new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(plate.scale), new Quaternionf())
      );
      display.addTag(PLATE_TAG);
      display.setPos(0.0, 0.0, 0.0);
      level.addFreshEntity(display);
      return display;
   }

   /** A point on his body, rotated into world space by his facing. */
   private static Vec3 worldOffset(Mob boss, double ox, double oy, double oz) {
      double yaw = Math.toRadians(boss.getYRot());
      double right = Math.cos(yaw);
      double rightZ = Math.sin(yaw);
      double forward = -Math.sin(yaw);
      double forwardZ = Math.cos(yaw);
      return new Vec3(
         boss.getX() + right * ox + forward * oz,
         boss.getY() + oy,
         boss.getZ() + rightZ * ox + forwardZ * oz
      );
   }

   /** Drops every plate of his suit without any of the tear-off noise. */
   private static void discardPlating(MinecraftServer server, Fight fight) {
      for (Plate plate : fight.plates) {
         if (plate.displayId == null) {
            continue;
         }
         Entity display = findEntity(server, plate.displayId);
         if (display != null) {
            display.discard();
         }
         plate.displayId = null;
      }
   }

   /**
    * His armour IS the machinery: each live machine is worth a tier of Resistance,
    * refreshed every tick so it tracks the arsenal exactly. No machines means no
    * armour at all, which is what makes clearing them the point of the fight.
    */
   private static void applyShield(MinecraftServer server, Mob boss, Fight fight) {
      int tiers = shieldTiersFor(fight.machines.size());
      if (tiers > 0) {
         boss.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 8, tiers, false, false, true));
      }
      // A broken machine leaves him slow and lit up, so the window is visible from
      // across the arena and worth committing to.
      if (fight.breakWindowUntil > ServerClock.clock(server.overworld())) {
         boss.addEffect(new MobEffectInstance(MobEffects.GLOWING, 8, 0, false, false, false));
      }
   }

   private static void tickMachines(MinecraftServer server, Mob boss, Fight fight, long now) {
      if (fight.machines.isEmpty()) {
         return;
      }
      for (Iterator<Map.Entry<UUID, Machine>> it = fight.machines.entrySet().iterator(); it.hasNext();) {
         Map.Entry<UUID, Machine> entry = it.next();
         Machine machine = entry.getValue();
         Entity raw = findEntity(server, machine.id);
         if (!(raw instanceof Mob mob) || !mob.isAlive() || mob.level() != boss.level()) {
            // Gone, dead, or carried into another dimension: it is no longer his. The chassis
            // goes with it, and a machine still standing elsewhere is shut down rather than
            // left running loose with nothing to drive it.
            if (raw != null && !raw.isRemoved()) {
               shutDownMachine(raw, machine.chassisId, false);
            } else {
               discardChassis(server, raw, machine.chassisId);
            }
            it.remove();
            MACHINE_OWNER.remove(machine.id);
            continue;
         }
         ServerLevel level = (ServerLevel) mob.level();
         // Movement runs every tick and only the attack waits on the cooldown. The old
         // gate wrapped the whole switch, so a blade that had just cut someone froze in
         // mid-air for a second and a drone that had just fired hung still for three,
         // then both teleported back into their orbit.
         switch (machine.role) {
            case TURRET -> {
               if (now >= machine.nextAction) {
                  // Turrets only fire down a clear line, so cover is a real answer to them.
                  ServerPlayer target = nearestVisible(mob, 34.0);
                  if (target != null) {
                     fireArrow(mob, target, 5.0F, 2.0, 0.0);
                     machine.nextAction = now + 30L + RANDOM.nextInt(20);
                  } else {
                     machine.nextAction = now + 20L;
                  }
               }
            }
            case BLADE -> {
               // Orbit the King, spinning. A blade is a hazard, not a chaser: it cuts
               // whatever is standing where it passes.
               double angle = now * 0.16 + machine.id.hashCode() % 6;
               double radius = 4.6;
               double x = boss.getX() + Math.cos(angle) * radius;
               double z = boss.getZ() + Math.sin(angle) * radius;
               mob.setPos(x, boss.getY() + 1.0, z);
               mob.hurtMarked = true;
               // The spin glyph is the blade's whole silhouette while it orbits, so it goes to
               // everyone (one particle every other tick): no template can ride a moving entity,
               // and a modded player with no cue would only see a bare trapdoor gliding past.
               if (now % 2L == 0L) {
                  level.sendParticles(ParticleTypes.SWEEP_ATTACK, mob.getX(), mob.getY() + 0.4, mob.getZ(), 1, 0.0, 0.0, 0.0, 0.0);
               }
               if (now >= machine.nextAction) {
                  boolean cut = false;
                  for (ServerPlayer p : participantsNear(level, mob, 3.0)) {
                     p.hurtServer(level, level.damageSources().mobAttack(mob), 7.0F);
                     level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.HOSTILE, 1.0F, 0.9F);
                     cut = true;
                  }
                  if (cut) {
                     Fx.slash(level, ParticleTypes.CRIT, mob.position().add(0.0, 0.4, 0.0), new Vec3(-Math.sin(angle), 0.0, Math.cos(angle)), 2.5, BRASS);
                     machine.nextAction = now + 22L;
                  }
               }
            }
            case PISTON -> tickPiston(level, boss, mob, machine, now);
            case DRONE -> {
               // Hover above him and snipe: higher ground, longer reach.
               double angle = now * 0.05 + machine.id.hashCode() % 9;
               mob.setPos(boss.getX() + Math.cos(angle) * 5.5, boss.getY() + 5.0, boss.getZ() + Math.sin(angle) * 5.5);
               mob.hurtMarked = true;
               if (now >= machine.nextAction) {
                  ServerPlayer target = nearestVisible(mob, 44.0);
                  if (target != null) {
                     fireArrow(mob, target, 4.0F, 2.2, 0.0);
                     machine.nextAction = now + 45L + RANDOM.nextInt(25);
                  } else {
                     machine.nextAction = now + 25L;
                  }
               }
            }
         }
      }
   }

   /**
    * The Piston Ram, rebuilt as a readable charge. It picks a player, shows the lane it will
    * take (a red line from its face), winds up for 12 ticks with steam hissing off it, then
    * drives along that lane - so the answer is to sidestep the line. It stops on the first
    * player it hits or the first wall it meets.
    *
    * <p>The old ram set a velocity on a mob with no AI, which does not move, so it only ever
    * hurt someone who happened to be standing on it; and it was free to wander off after a
    * target forever. It is leashed back to the King when it strays.
    */
   private static void tickPiston(ServerLevel level, Mob boss, Mob mob, Machine machine, long now) {
      if (machine.dashTicks > 0 && machine.dashDir != null) {
         machine.dashTicks--;
         Vec3 step = machine.dashDir.scale(0.9);
         if (!level.noCollision(mob, mob.getBoundingBox().move(step))) {
            machine.dashTicks = 0;
            Fx.rockburst(level, ParticleTypes.LARGE_SMOKE, mob.position(), 0.8, EMBER);
            level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.ANVIL_LAND, SoundSource.HOSTILE, 0.8F, 1.2F);
         } else {
            mob.setPos(mob.getX() + step.x, mob.getY() + step.y, mob.getZ() + step.z);
            mob.hurtMarked = true;
            Fx.vanilla(level, ParticleTypes.LARGE_SMOKE, mob.getX(), mob.getY() + 0.3, mob.getZ(), 2, 0.1, 0.1, 0.1, 0.0);
            for (ServerPlayer p : participantsNear(level, mob, 1.8)) {
               p.hurtServer(level, level.damageSources().mobAttack(mob), 10.0F);
               p.push(machine.dashDir.x * 1.4, 0.8, machine.dashDir.z * 1.4);
               p.hurtMarked = true;
               Fx.clash(level, ParticleTypes.CRIT, p.position().add(0.0, 1.0, 0.0), machine.dashDir, EMBER);
               level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), ModSounds.BOSS_SLAM, SoundSource.HOSTILE, 0.8F, 1.4F);
               machine.dashTicks = 0;
            }
         }
         if (machine.dashTicks == 0) {
            machine.nextAction = now + 60L;
         }
         return;
      }
      if (machine.windup > 0) {
         machine.windup--;
         if (machine.windup % 4 == 0) {
            level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.FIRE_EXTINGUISH, SoundSource.HOSTILE, 0.5F, 1.6F);
         }
         if (machine.windup == 0) {
            machine.dashTicks = 10;
            // The steam goes: a blast of it driven down the lane it showed.
            if (machine.dashDir != null) {
               Fx.gust(level, ParticleTypes.CLOUD, mob.position().add(0.0, 0.4, 0.0), machine.dashDir, 6.0, EMBER);
            }
            level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.IRON_DOOR_CLOSE, SoundSource.HOSTILE, 1.2F, 0.6F);
         }
         return;
      }
      // Leash: a ram that has wandered off drifts back to the King between charges.
      Vec3 home = boss.position().add(0.0, 1.0, 0.0);
      if (mob.position().distanceToSqr(home) > 16.0 * 16.0) {
         Vec3 back = home.subtract(mob.position()).normalize().scale(0.5);
         mob.setPos(mob.getX() + back.x, mob.getY() + back.y, mob.getZ() + back.z);
         mob.hurtMarked = true;
         return;
      }
      if (now < machine.nextAction) {
         return;
      }
      ServerPlayer target = nearestVisible(mob, 14.0);
      if (target == null) {
         machine.nextAction = now + 30L;
         return;
      }
      Vec3 toward = target.position().add(0.0, 0.5, 0.0).subtract(mob.position());
      if (toward.lengthSqr() < 1.0E-4) {
         machine.nextAction = now + 20L;
         return;
      }
      machine.dashDir = toward.normalize();
      machine.windup = 12;
      Vec3 face = mob.position().add(0.0, 0.4, 0.0);
      Fx.beam(level, new DustParticleOptions(EMBER, 0.9F), face, face.add(machine.dashDir.scale(9.0)), EMBER);
      Fx.muzzle(level, ParticleTypes.LARGE_SMOKE, face, machine.dashDir, EMBER);
   }

   /** A machine was torn off him: strip a tier, chip his real health, open a window. */
   public static void onMachineKilled(ServerLevel level, Mob machine, ServerPlayer killer) {
      // Its chassis comes off with it whatever else happens below - a machine killed after
      // its King (or during his ceremony) used to throw its block off and leave it standing.
      UUID chassisId = null;
      for (Fight f : FIGHTS.values()) {
         Machine tracked = f.machines.get(machine.getUUID());
         if (tracked != null) {
            chassisId = tracked.chassisId;
            break;
         }
      }
      discardChassis(level.getServer(), machine, chassisId);
      UUID ownerId = machineOwnerOf(machine);
      Entity rawBoss = ownerId == null ? null : findEntity(level.getServer(), ownerId);
      if (!(rawBoss instanceof Mob boss) || !boss.isAlive()) {
         return;
      }
      Fight fight = FIGHTS.get(boss.getUUID());
      if (fight == null || fight.dying) {
         return;
      }
      fight.machines.values().removeIf(m -> m.id.equals(machine.getUUID()));
      MACHINE_OWNER.remove(machine.getUUID());

      // The shield collapsing is a real injury, deliberately NOT blocked by his
      // Resistance - otherwise tearing machines off him would do nothing at all,
      // which is precisely the mistake the design exists to avoid.
      float chip = boss.getMaxHealth() * SHIELD_BREAK_HP;
      float before = boss.getHealth();
      boss.setHealth(Math.max(1.0F, before - chip));
      fight.breakWindowUntil = ServerClock.clock(level) + BREAK_WINDOW_TICKS;

      // The part bursts - its cogs fly loose and spin down - and the shock runs back up the
      // line into him.
      Vec3 at = machine.position().add(0.0, 0.5, 0.0);
      Fx.shatter(level, ParticleTypes.ELECTRIC_SPARK, at, 1.2, BRASS);
      Fx.flare(level, ParticleTypes.END_ROD, at, 1.2, EMBER);
      Fx.gearSpin(level, ParticleTypes.ELECTRIC_SPARK, at, flatAway(at, boss.position()), 0.9, 14, EMBER);
      Fx.lightning(level, ParticleTypes.ELECTRIC_SPARK, at, boss.position().add(0.0, 1.6, 0.0), ARC);
      Fx.vanilla(level, ParticleTypes.ELECTRIC_SPARK, at.x, at.y, at.z, 40, 0.6, 0.6, 0.6, 0.12);
      Fx.vanilla(level, ParticleTypes.LARGE_SMOKE, at.x, at.y, at.z, 16, 0.4, 0.4, 0.4, 0.04);
      Fx.vanilla(level, ParticleTypes.ITEM_SNOWBALL, at.x, at.y, at.z, 12, 0.4, 0.4, 0.4, 0.06);
      level.playSound(null, machine.getX(), machine.getY(), machine.getZ(), SoundEvents.ITEM_BREAK, SoundSource.HOSTILE, 1.2F, 0.8F);
      level.playSound(null, machine.getX(), machine.getY(), machine.getZ(), SoundEvents.STONE_BREAK, SoundSource.HOSTILE, 1.0F, 0.7F);

      // A machine with its name stripped (a name tag, a command) used to throw here and
      // skip the discard below, leaving a dead part standing in the arena.
      String who = killer != null ? killer.getName().getString() : "Someone";
      Component name = machine.getCustomName();
      String part = name != null ? name.getString() : "a part";
      int left = fight.machines.size();
      announceNear(level, boss, 64.0, "\u00a76\u2726 " + who + " \u00a77tore off " + part
         + "\u00a77. \u00a7f" + left + "\u00a77 left.");
      machine.discard();
   }

   // --------------------------------------------------------------------- moves

   /**
    * Picks his next move. One move per {@link #MOVE_GAP}, and none while a shown hit is
    * still waiting to land, so the room only ever has one warning to read at a time.
    */
   private static void chooseMove(MinecraftServer server, Mob boss, Fight fight, long now) {
      if (now < fight.nextMove || !fight.strikes.isEmpty()) {
         return;
      }
      ServerLevel level = (ServerLevel) boss.level();
      boolean moved = false;
      if (fight.phase >= 3 && now >= fight.nextDetonate) {
         fight.nextDetonate = now + DETONATE_COOLDOWN;
         moved = startDetonate(server, boss, fight);
      } else if (fight.phase >= 2 && now >= fight.nextCurrent) {
         fight.nextCurrent = now + CURRENT_COOLDOWN;
         moved = startCurrent(level, boss, fight, now);
      } else if (now >= fight.nextBarrage) {
         fight.nextBarrage = now + BARRAGE_COOLDOWN;
         moved = startVolley(server, level, boss, fight, now);
      } else if (now >= fight.nextPendulum) {
         fight.nextPendulum = now + PENDULUM_COOLDOWN;
         moved = startPendulum(level, boss, fight, now);
      } else if (now >= fight.nextPull) {
         fight.nextPull = now + PULL_COOLDOWN;
         moved = magnetPull(server, boss, fight);
      } else if (now >= fight.nextSweep) {
         fight.nextSweep = now + SWEEP_COOLDOWN;
         moved = startSweep(server, level, boss, fight, now);
      } else if (now >= fight.nextSlam) {
         fight.nextSlam = now + SLAM_COOLDOWN;
         moved = startSlam(server, boss, fight);
      }
      if (moved) {
         fight.nextMove = now + MOVE_GAP;
      }
   }

   /**
    * Piston Slam. He throws himself up over a player and comes down on a marked ring a
    * second and a half later. The answer is to leave the ring.
    *
    * <p>He used to be thrown a flat six blocks up wherever he was, which under a roof put
    * him inside the ceiling; he now takes the highest free spot up to six blocks.
    */
   private static boolean startSlam(MinecraftServer server, Mob boss, Fight fight) {
      ServerPlayer target = nearestPlayer(boss, 48.0);
      if (target == null) {
         return false;
      }
      ServerLevel level = (ServerLevel) boss.level();
      Vec3 floor = new Vec3(target.getX(), BossGrounding.groundY(level, target.getX(), target.getZ(), target.getY()), target.getZ());
      fight.slamTarget = floor;
      fight.slamCharge = 30;
      for (double lift = 6.0; lift >= 2.0; lift -= 1.0) {
         double dx = floor.x - boss.getX();
         double dy = floor.y + lift - boss.getY();
         double dz = floor.z - boss.getZ();
         if (level.noCollision(boss, boss.getBoundingBox().move(dx, dy, dz))) {
            boss.teleportTo(floor.x, floor.y + lift, floor.z);
            break;
         }
      }
      boss.setDeltaMovement(Vec3.ZERO);
      boss.hurtMarked = true;
      Fx.runeCircle(level, ParticleTypes.ELECTRIC_SPARK, floor.add(0.0, 0.05, 0.0), SLAM_RADIUS, fight.slamCharge, EMBER);
      Fx.pillar(level, ParticleTypes.ELECTRIC_SPARK, floor, 7.0, BRASS);
      announceNear(level, boss, 64.0, SAY + "\"\u00a7fStay there.\"");
      announceNear(level, boss, 64.0, "\u00a78The floor is marked. \u00a77Get out of the ring.");
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.RESPAWN_ANCHOR_CHARGE, SoundSource.HOSTILE, 1.4F, 0.6F);
      return true;
   }

   private static void tickSlamCharge(MinecraftServer server, Mob boss, Fight fight) {
      if (fight.slamTarget == null) {
         fight.slamCharge = 0;
         return;
      }
      // He is a real mob now, so he genuinely falls the six blocks he was thrown -
      // and his own move must not hurt him on the way down.
      boss.resetFallDistance();
      ServerLevel level = (ServerLevel) boss.level();
      double r = SLAM_RADIUS;
      int points = 20;
      // The rune circle draws this ring for modded clients; this is everyone else's copy,
      // redrawn every third tick so Geyser is not paying for a full ring on every tick.
      for (int i = 0; fight.slamCharge % 3 == 0 && i < points; i++) {
         double a = i * (Math.PI * 2.0 / points);
         Fx.vanilla(level,
            ParticleTypes.ELECTRIC_SPARK,
            fight.slamTarget.x + Math.cos(a) * r,
            fight.slamTarget.y + 0.2,
            fight.slamTarget.z + Math.sin(a) * r,
            1,
            0.0,
            0.0,
            0.0,
            0.0
         );
      }
      fight.slamCharge--;
      if (fight.slamCharge > 0) {
         return;
      }

      double x = fight.slamTarget.x;
      double y = fight.slamTarget.y;
      double z = fight.slamTarget.z;
      Vec3 at = fight.slamTarget;
      level.playSound(null, x, y, z, ModSounds.BOSS_SLAM, SoundSource.HOSTILE, 2.0F, 0.6F);
      level.playSound(null, x, y, z, SoundEvents.MACE_SMASH_GROUND_HEAVY, SoundSource.HOSTILE, 1.6F, 0.7F);
      Fx.shockwave(level, ParticleTypes.ELECTRIC_SPARK, at, SLAM_RADIUS + 1.0, BRASS);
      Fx.rockburst(level, ParticleTypes.LARGE_SMOKE, at.add(0.0, 0.3, 0.0), 1.8, EMBER);
      Fx.flare(level, ParticleTypes.END_ROD, at.add(0.0, 0.6, 0.0), 1.6, BRASS);
      // The landing rings out through the floor like a struck bell, and the cogs of his legs
      // grind round flat under him.
      sonicBurst(level, at.add(0.0, 0.2, 0.0), SLAM_RADIUS + 2.0, 12, EMBER, 4);
      Fx.gearSpin(level, ParticleTypes.ELECTRIC_SPARK, at.add(0.0, 0.1, 0.0), Vec3.ZERO, 1.6, 16, BRASS);
      Fx.vanilla(level, ParticleTypes.EXPLOSION_EMITTER, x, y + 0.5, z, 2, 0.5, 0.2, 0.5, 0.0);
      Fx.vanilla(level, ParticleTypes.GUST, x, y + 0.4, z, 12, 2.5, 0.3, 2.5, 0.1);
      Fx.vanilla(level, ParticleTypes.LARGE_SMOKE, x, y + 0.5, z, 24, 2.6, 0.4, 2.6, 0.08);

      // The hit is the ring that was drawn: flat distance, plus a little for the player's
      // own width. It used to be a 5-block sphere round a 4.5-block ring.
      double reach = SLAM_RADIUS + 0.3;
      for (ServerPlayer p : playersNear(level, x, y, z, SLAM_RADIUS + 3.0)) {
         double dx = p.getX() - x;
         double dz = p.getZ() - z;
         if (dx * dx + dz * dz > reach * reach || Math.abs(p.getY() - y) > 3.0) {
            continue;
         }
         Vec3 away = flatAway(p.position(), at);
         p.push(away.x * 2.0, 0.9, away.z * 2.0);
         p.hurtMarked = true;
         p.hurtServer(level, level.damageSources().mobAttack(boss), 14.0F);
      }
      fight.slamCharge = 0;
      fight.slamTarget = null;
   }

   /**
    * Blade Sweep. The blades are flung out to a ring around him and cut everything in the
    * band between {@link #SWEEP_INNER} and {@link #SWEEP_OUTER}. The band is drawn for most
    * of a second first, so the answer is either to hug him or to back right off.
    */
   private static boolean startSweep(MinecraftServer server, ServerLevel level, Mob boss, Fight fight, long now) {
      if (liveMachines(server, fight, Role.BLADE).isEmpty()) {
         announceNear(level, boss, 64.0, "\u00a78He reaches for his blades. \u00a77They're gone.");
         return true;
      }
      Vec3 at = boss.position();
      fight.strikes.add(new Strike(Strike.SWEEP, at, null, now + SWEEP_WARN, SWEEP_OUTER, 0.0F));
      Fx.runeCircle(level, ParticleTypes.CRIT, at.add(0.0, 0.05, 0.0), SWEEP_OUTER, SWEEP_WARN, BRASS);
      Fx.ring(level, ParticleTypes.ELECTRIC_SPARK, at.add(0.0, 0.1, 0.0), SWEEP_INNER, ARC);
      level.playSound(null, at.x, at.y, at.z, SoundEvents.TRIPWIRE_CLICK_ON, SoundSource.HOSTILE, 1.4F, 0.6F);
      announceNear(level, boss, 64.0, SAY + "\"\u00a7fBlades out.\"");
      announceNear(level, boss, 64.0, "\u00a78The blades spin out. \u00a77Hug him or back off.");
      return true;
   }

   private static void landSweep(MinecraftServer server, ServerLevel level, Mob boss, Fight fight, Strike strike) {
      List<Mob> blades = liveMachines(server, fight, Role.BLADE);
      if (blades.isEmpty()) {
         return;
      }
      Vec3 at = strike.at;
      double mid = (SWEEP_INNER + SWEEP_OUTER) * 0.5;
      for (int i = 0; i < blades.size(); i++) {
         double a = i * (Math.PI * 2.0 / blades.size()) + RANDOM.nextDouble() * 0.5;
         Vec3 out = new Vec3(Math.cos(a), 0.0, Math.sin(a));
         Mob blade = blades.get(i);
         blade.setPos(at.x + out.x * mid, at.y + 1.0, at.z + out.z * mid);
         blade.hurtMarked = true;
         Fx.crescent(level, ParticleTypes.CRIT, at.add(0.0, 1.0, 0.0), out, SWEEP_OUTER, BRASS);
      }
      Fx.shockwave(level, ParticleTypes.CRIT, at, SWEEP_OUTER, BRASS);
      Fx.gearSpin(level, ParticleTypes.CRIT, at.add(0.0, 0.1, 0.0), Vec3.ZERO, SWEEP_INNER * 0.6, 12, BRASS);
      // More blades, a meaner cut - but never more than a slam.
      float damage = Math.min(11.0F, 4.0F + 2.5F * blades.size());
      for (ServerPlayer p : playersNear(level, at.x, at.y, at.z, SWEEP_OUTER + 3.0)) {
         double dx = p.getX() - at.x;
         double dz = p.getZ() - at.z;
         double d = Math.sqrt(dx * dx + dz * dz);
         if (d < SWEEP_INNER || d > SWEEP_OUTER + 0.3 || Math.abs(p.getY() - at.y) > 3.0) {
            continue;
         }
         p.hurtServer(level, level.damageSources().mobAttack(boss), damage);
         Fx.slash(level, ParticleTypes.CRIT, p.position().add(0.0, 1.0, 0.0), flatAway(p.position(), at), 1.6, EMBER);
      }
      level.playSound(null, at.x, at.y, at.z, SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.HOSTILE, 1.6F, 0.7F);
      level.playSound(null, at.x, at.y, at.z, SoundEvents.CHAIN_BREAK, SoundSource.HOSTILE, 1.2F, 0.6F);
   }

   /**
    * Magnetic Haul. A displacement, not a hit: everyone within 40 blocks is dragged a few
    * blocks toward him and slowed for two seconds, which sets up the blades and the slam.
    * Chains of arc show who is caught.
    */
   private static boolean magnetPull(MinecraftServer server, Mob boss, Fight fight) {
      ServerLevel level = (ServerLevel) boss.level();
      Vec3 core = boss.position().add(0.0, 1.0, 0.0);
      Fx.vortex(level, ParticleTypes.ELECTRIC_SPARK, boss.position(), 6.0, 30, ARC);
      for (ServerPlayer p : participantsNear(level, boss, 40.0)) {
         Vec3 pull = core.subtract(p.position());
         double len = pull.length();
         if (len < 1.0) {
            continue;
         }
         Vec3 unit = pull.scale(1.0 / len);
         p.push(unit.x * 1.5, 0.35, unit.z * 1.5);
         p.hurtMarked = true;
         p.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 40, 0, false, true, true));
         Fx.chains(level, ParticleTypes.ELECTRIC_SPARK, core.add(0.0, 0.6, 0.0), p.position().add(0.0, 1.0, 0.0), ARC);
      }
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.BEACON_ACTIVATE, SoundSource.HOSTILE, 1.6F, 0.7F);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.CHAIN_FALL, SoundSource.HOSTILE, 1.4F, 0.6F);
      announceNear(level, boss, 64.0, SAY + "\"\u00a7fCome here.\"");
      announceNear(level, boss, 64.0, "\u00a78Magnets. \u00a77Walk against it.");
      return true;
   }

   /**
    * Turret Barrage, now aimed. Every turret and drone paints a red sightline onto the
    * player it has picked and holds it for {@link #VOLLEY_WARN} ticks; then they all fire
    * together, three shots each, but only down a line that is still clear. The answer is to
    * put a wall, a pillar or the King himself between you and the guns.
    */
   private static boolean startVolley(MinecraftServer server, ServerLevel level, Mob boss, Fight fight, long now) {
      List<Mob> guns = liveMachines(server, fight, Role.TURRET);
      guns.addAll(liveMachines(server, fight, Role.DRONE));
      if (guns.isEmpty()) {
         announceNear(level, boss, 64.0, "\u00a78The guns are gone. \u00a77Nothing fires.");
         return true;
      }
      fight.strikes.add(new Strike(Strike.VOLLEY, boss.position(), null, now + VOLLEY_WARN, 0.0, 5.0F));
      drawSightlines(level, guns);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.LEVER_CLICK, SoundSource.HOSTILE, 1.6F, 0.6F);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.TRIPWIRE_CLICK_ON, SoundSource.HOSTILE, 1.4F, 1.4F);
      announceNear(level, boss, 64.0, SAY + "\"\u00a7fAim.\"");
      announceNear(level, boss, 64.0, "\u00a78Red lines are his sightlines. \u00a77Get behind cover.");
      return true;
   }

   private static void drawSightlines(ServerLevel level, List<Mob> guns) {
      for (Mob gun : guns) {
         ServerPlayer target = nearestVisible(gun, 40.0);
         if (target == null) {
            continue;
         }
         Fx.beam(level, new DustParticleOptions(EMBER, 0.8F), gun.position().add(0.0, gun.getBbHeight() * 0.6, 0.0),
            target.position().add(0.0, target.getBbHeight() * 0.5, 0.0), EMBER);
      }
   }

   private static void landVolley(MinecraftServer server, ServerLevel level, Mob boss, Fight fight, Strike strike) {
      List<Mob> guns = liveMachines(server, fight, Role.TURRET);
      guns.addAll(liveMachines(server, fight, Role.DRONE));
      int firing = 0;
      for (Mob gun : guns) {
         ServerPlayer target = nearestVisible(gun, 40.0);
         if (target == null) {
            continue;
         }
         firing++;
         // A real fan this time: the old "spread" only changed the arrows' speed, so all
         // three flew down the same line and two of them were eaten by hurt-immunity.
         for (int i = -1; i <= 1; i++) {
            fireArrow(gun, target, strike.damage, 2.1, i * 0.07);
         }
         Vec3 from = gun.position().add(0.0, gun.getBbHeight() * 0.6, 0.0);
         Fx.muzzle(level, ParticleTypes.FLAME, from, target.position().add(0.0, 1.0, 0.0).subtract(from), EMBER);
      }
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.CROSSBOW_SHOOT, SoundSource.HOSTILE, 1.6F, 0.6F);
      if (firing > 0) {
         announceNear(level, boss, 64.0, SAY + "\"\u00a7fFire.\"");
      }
   }

   /**
    * <b>Pendulum</b> (new). A lane is drawn through a player, from one runed post to the
    * other, and ticks for two seconds; then a brass pendulum comes down along its length.
    * The answer is a single sidestep - the lane is three and a half blocks wide. In the
    * last phase a second lane crosses the first half a second later, so the sidestep has to
    * go diagonally out of the cross.
    */
   private static boolean startPendulum(ServerLevel level, Mob boss, Fight fight, long now) {
      List<ServerPlayer> room = new ArrayList<>();
      for (ServerPlayer p : participantsNear(level, boss, 40.0)) {
         if (fight.participants.contains(p.getUUID())) {
            room.add(p);
         }
      }
      if (room.isEmpty()) {
         return false;
      }
      ServerPlayer target = room.get(RANDOM.nextInt(room.size()));
      Vec3 dir = new Vec3(target.getX() - boss.getX(), 0.0, target.getZ() - boss.getZ());
      if (dir.lengthSqr() < 1.0E-3) {
         double yaw = Math.toRadians(boss.getYRot());
         dir = new Vec3(-Math.sin(yaw), 0.0, Math.cos(yaw));
      }
      dir = dir.normalize();
      addLane(level, fight, target.position(), dir, now, now + PENDULUM_WARN);
      if (fight.phase >= 3) {
         addLane(level, fight, target.position(), new Vec3(-dir.z, 0.0, dir.x), now, now + PENDULUM_WARN + 10L);
      }
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.BELL_RESONATE, SoundSource.HOSTILE, 1.4F, 0.5F);
      announceNear(level, boss, 64.0, SAY + "\"\u00a7fTick.\"");
      announceNear(level, boss, 64.0, "\u00a78A pendulum lane. \u00a77Step off the line.");
      return true;
   }

   private static void addLane(ServerLevel level, Fight fight, Vec3 through, Vec3 dir, long now, long landAt) {
      Vec3 floor = new Vec3(through.x, BossGrounding.groundY(level, through.x, through.z, through.y), through.z);
      fight.strikes.add(new Strike(Strike.PENDULUM, floor, dir, landAt, PENDULUM_HALF_WIDTH, PENDULUM_DAMAGE));
      int warn = (int) (landAt - now);
      Vec3 a = floor.subtract(dir.scale(PENDULUM_REACH));
      Vec3 b = floor.add(dir.scale(PENDULUM_REACH));
      Fx.runeCircle(level, ParticleTypes.ELECTRIC_SPARK, a.add(0.0, 0.05, 0.0), 1.4, warn, BRASS);
      Fx.runeCircle(level, ParticleTypes.ELECTRIC_SPARK, b.add(0.0, 0.05, 0.0), 1.4, warn, BRASS);
      drawLane(level, floor, dir);
   }

   /** The lane's two edges, in ember on the floor. Redrawn while the warning runs. */
   private static void drawLane(ServerLevel level, Vec3 floor, Vec3 dir) {
      Vec3 side = new Vec3(-dir.z, 0.0, dir.x).scale(PENDULUM_HALF_WIDTH);
      Vec3 a = floor.subtract(dir.scale(PENDULUM_REACH)).add(0.0, 0.15, 0.0);
      Vec3 b = floor.add(dir.scale(PENDULUM_REACH)).add(0.0, 0.15, 0.0);
      DustParticleOptions ember = new DustParticleOptions(EMBER, 0.9F);
      Fx.beam(level, ember, a.add(side), b.add(side), EMBER);
      Fx.beam(level, ember, a.subtract(side), b.subtract(side), EMBER);
   }

   private static void landPendulum(ServerLevel level, Mob boss, Strike strike) {
      Vec3 dir = strike.dir;
      Vec3 a = strike.at.subtract(dir.scale(PENDULUM_REACH));
      Vec3 b = strike.at.add(dir.scale(PENDULUM_REACH));
      Fx.slash(level, ParticleTypes.CRIT, a.add(0.0, 1.0, 0.0), dir, PENDULUM_REACH * 2.0, BRASS);
      Fx.rockburst(level, ParticleTypes.LARGE_SMOKE, strike.at.add(0.0, 0.2, 0.0), 1.4, EMBER);
      Fx.rockburst(level, ParticleTypes.LARGE_SMOKE, a.add(0.0, 0.2, 0.0), 0.9, EMBER);
      Fx.rockburst(level, ParticleTypes.LARGE_SMOKE, b.add(0.0, 0.2, 0.0), 0.9, EMBER);
      level.playSound(null, strike.at.x, strike.at.y, strike.at.z, SoundEvents.ANVIL_LAND, SoundSource.HOSTILE, 1.6F, 0.6F);
      level.playSound(null, strike.at.x, strike.at.y, strike.at.z, SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.HOSTILE, 1.6F, 0.5F);
      Vec3 perp = new Vec3(-dir.z, 0.0, dir.x);
      for (ServerPlayer p : playersNear(level, strike.at.x, strike.at.y, strike.at.z, PENDULUM_REACH + 3.0)) {
         double rx = p.getX() - strike.at.x;
         double rz = p.getZ() - strike.at.z;
         double along = rx * dir.x + rz * dir.z;
         double side = rx * perp.x + rz * perp.z;
         if (Math.abs(along) > PENDULUM_REACH + 0.5 || Math.abs(side) > strike.radius + 0.3 || Math.abs(p.getY() - strike.at.y) > 3.0) {
            continue;
         }
         p.hurtServer(level, level.damageSources().mobAttack(boss), strike.damage);
         // Knocked clear of the lane, to whichever side they were already leaning.
         double sign = side >= 0.0 ? 1.0 : -1.0;
         p.push(perp.x * sign * 1.1, 0.35, perp.z * sign * 1.1);
         p.hurtMarked = true;
      }
   }

   /**
    * <b>Live Current</b> (new, phase two on). He grounds his coils and the floor round him
    * hums: a fourteen-block rune circle, a click every half second, and then a bell. Half a
    * second after the bell the current goes through the floor, and anyone standing on it is
    * shocked and slowed. The answer is to jump on the bell (or be on something high).
    */
   private static boolean startCurrent(ServerLevel level, Mob boss, Fight fight, long now) {
      Vec3 c = new Vec3(boss.getX(), BossGrounding.groundY(level, boss.getX(), boss.getZ(), boss.getY()), boss.getZ());
      fight.strikes.add(new Strike(Strike.CURRENT, c, null, now + CURRENT_WARN, CURRENT_RADIUS, CURRENT_DAMAGE));
      Fx.runeCircle(level, ParticleTypes.ELECTRIC_SPARK, c.add(0.0, 0.05, 0.0), CURRENT_RADIUS, CURRENT_WARN, ARC);
      Fx.resonance(level, ParticleTypes.ELECTRIC_SPARK, c.add(0.0, 1.0, 0.0), CURRENT_WARN, ARC);
      Fx.aura(level, ParticleTypes.ELECTRIC_SPARK, boss.position(), 3.0, CURRENT_WARN, ARC);
      level.playSound(null, c.x, c.y, c.z, SoundEvents.BEACON_POWER_SELECT, SoundSource.HOSTILE, 1.6F, 0.6F);
      level.playSound(null, c.x, c.y, c.z, SoundEvents.CONDUIT_ACTIVATE, SoundSource.HOSTILE, 1.4F, 1.2F);
      announceNear(level, boss, 64.0, SAY + "\"\u00a7fGround's live.\"");
      announceNear(level, boss, 64.0, "\u00a78The floor hums. \u00a77Jump on the bell.");
      return true;
   }

   private static void landCurrent(ServerLevel level, Mob boss, Strike strike) {
      Vec3 c = strike.at;
      Fx.shockwave(level, ParticleTypes.ELECTRIC_SPARK, c, strike.radius, ARC);
      for (int i = 0; i < 6; i++) {
         double a = i * (Math.PI / 3.0) + RANDOM.nextDouble() * 0.4;
         double r = strike.radius * (0.45 + RANDOM.nextDouble() * 0.55);
         Fx.lightning(level, ParticleTypes.ELECTRIC_SPARK, c.add(0.0, 2.5, 0.0), c.add(Math.cos(a) * r, 0.1, Math.sin(a) * r), ARC);
      }
      level.playSound(null, c.x, c.y, c.z, SoundEvents.LIGHTNING_BOLT_IMPACT, SoundSource.HOSTILE, 1.6F, 1.2F);
      level.playSound(null, c.x, c.y, c.z, SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.HOSTILE, 0.8F, 1.6F);
      for (ServerPlayer p : playersNear(level, c.x, c.y, c.z, strike.radius + 3.0)) {
         double dx = p.getX() - c.x;
         double dz = p.getZ() - c.z;
         if (dx * dx + dz * dz > strike.radius * strike.radius || Math.abs(p.getY() - c.y) > 2.5) {
            continue;
         }
         // Airborne is safe: that is the whole answer.
         if (!p.onGround()) {
            continue;
         }
         p.hurtServer(level, level.damageSources().mobAttack(boss), strike.damage);
         p.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 40, 1, false, true, true));
         Fx.lightning(level, ParticleTypes.ELECTRIC_SPARK, c.add(0.0, 2.5, 0.0), p.position().add(0.0, 1.0, 0.0), ARC);
      }
   }

   /** Runs every shown hit: refreshes its warning, plays its last cue, and lands it on time. */
   private static void tickStrikes(MinecraftServer server, ServerLevel level, Mob boss, Fight fight, long now) {
      if (fight.strikes.isEmpty()) {
         return;
      }
      for (Iterator<Strike> it = fight.strikes.iterator(); it.hasNext();) {
         Strike strike = it.next();
         long left = strike.landAt - now;
         switch (strike.kind) {
            case Strike.PENDULUM -> {
               if (left > 0 && left % 8 == 0) {
                  drawLane(level, strike.at, strike.dir);
               }
               if (left > 0 && left % 10 == 0) {
                  level.playSound(null, strike.at.x, strike.at.y, strike.at.z, SoundEvents.NOTE_BLOCK_HAT, SoundSource.HOSTILE, 1.4F, 0.7F);
               }
               if (!strike.cued && left <= 6) {
                  // The bob is seen swinging down for the last few ticks.
                  strike.cued = true;
                  Vec3 top = strike.at.subtract(strike.dir.scale(PENDULUM_REACH)).add(0.0, 9.0, 0.0);
                  Fx.comet(level, ParticleTypes.END_ROD, top, strike.at.add(0.0, 0.8, 0.0), 6, BRASS);
               }
            }
            case Strike.CURRENT -> {
               if (left > CURRENT_BELL && left % 10 == 0) {
                  level.playSound(null, strike.at.x, strike.at.y, strike.at.z, SoundEvents.NOTE_BLOCK_HAT, SoundSource.HOSTILE, 1.6F,
                     0.8F + (CURRENT_WARN - left) / (float) CURRENT_WARN);
               }
               if (!strike.cued && left <= CURRENT_BELL) {
                  strike.cued = true;
                  Fx.ring(level, ParticleTypes.ELECTRIC_SPARK, strike.at.add(0.0, 0.2, 0.0), strike.radius, ARC);
                  level.playSound(null, strike.at.x, strike.at.y, strike.at.z, SoundEvents.BELL_BLOCK, SoundSource.HOSTILE, 2.0F, 1.0F);
                  for (ServerPlayer p : playersNear(level, strike.at.x, strike.at.y, strike.at.z, strike.radius + 3.0)) {
                     p.sendOverlayMessage(Component.literal("\u00a7b\u26a1 \u00a7fJump!"));
                  }
               }
            }
            case Strike.VOLLEY -> {
               if (left > 0 && left % 5 == 0) {
                  List<Mob> guns = liveMachines(server, fight, Role.TURRET);
                  guns.addAll(liveMachines(server, fight, Role.DRONE));
                  drawSightlines(level, guns);
               }
            }
            case Strike.SWEEP -> {
               if (left > 0 && left % 5 == 0) {
                  Fx.ring(level, ParticleTypes.ELECTRIC_SPARK, strike.at.add(0.0, 0.1, 0.0), SWEEP_INNER, ARC);
               }
            }
            default -> {
            }
         }
         if (left > 0) {
            continue;
         }
         it.remove();
         switch (strike.kind) {
            case Strike.PENDULUM -> landPendulum(level, boss, strike);
            case Strike.CURRENT -> landCurrent(level, boss, strike);
            case Strike.VOLLEY -> landVolley(server, level, boss, fight, strike);
            case Strike.SWEEP -> landSweep(server, level, boss, fight, strike);
            default -> {
            }
         }
      }
   }

   /**
    * Core Detonation, phase three. He stops dead, a dome goes up around the spot he
    * armed it on and his core beats for three seconds; then it goes off. Inside the dome is
    * 26 damage. The answer is to get out, and then to punish: he is winded afterwards.
    *
    * <p>The ring used to follow him while he walked, and the blast went off wherever he
    * ended up; it is now fixed to where it was shown, and he is held still while it charges.
    */
   private static boolean startDetonate(MinecraftServer server, Mob boss, Fight fight) {
      fight.detonateCharge = 60;
      fight.detonateAt = boss.position();
      ServerLevel level = (ServerLevel) boss.level();
      boss.addEffect(new MobEffectInstance(MobEffects.GLOWING, 70, 0, false, false, false));
      boss.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 70, 6, false, false, false));
      Vec3 at = fight.detonateAt;
      Fx.dome(level, ParticleTypes.FLAME, at, DETONATE_RADIUS, fight.detonateCharge, EMBER);
      Fx.runeCircle(level, ParticleTypes.FLAME, at.add(0.0, 0.05, 0.0), DETONATE_RADIUS, fight.detonateCharge, EMBER);
      Fx.heartbeat(level, ParticleTypes.FLAME, at.add(0.0, 0.1, 0.0), 4.0, fight.detonateCharge, BRASS);
      // His mainspring winding past its stop: a big cog turning faster and faster over the core.
      Fx.gearSpin(level, ParticleTypes.FLAME, at.add(0.0, 0.2, 0.0), Vec3.ZERO, 2.4, fight.detonateCharge, EMBER);
      announceNear(level, boss, 64.0, SAY + "\"\u00a7fStand back. \u00a7cOr don't.\"");
      announceNear(level, boss, 64.0, "\u00a78His core is going. \u00a77Get out of the dome.");
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.RESPAWN_ANCHOR_CHARGE, SoundSource.HOSTILE, 2.0F, 0.4F);
      return true;
   }

   private static void tickDetonateCharge(MinecraftServer server, Mob boss, Fight fight) {
      ServerLevel level = (ServerLevel) boss.level();
      if (fight.detonateAt == null) {
         fight.detonateAt = boss.position();
      }
      Vec3 at = fight.detonateAt;
      fight.detonateCharge--;
      int elapsed = 60 - fight.detonateCharge;
      double r = 3.0 + elapsed * 0.22;
      int points = 24;
      // The dome and rune circle draw this for modded clients. Everyone else gets a ring that
      // widens with the charge, redrawn every third tick to keep Geyser's packet count sane.
      if (fight.detonateCharge % 3 == 0) {
         for (int i = 0; i < points; i++) {
            double a = i * (Math.PI * 2.0 / points) + elapsed * 0.05;
            Fx.vanilla(level, ParticleTypes.ELECTRIC_SPARK, at.x + Math.cos(a) * r, at.y + 0.15, at.z + Math.sin(a) * r, 1, 0.0, 0.0, 0.0, 0.0);
         }
         Fx.vanilla(level, ParticleTypes.CRIT, boss.getX(), boss.getY() + 1.2, boss.getZ(), 8, 1.0, 1.0, 1.0, 0.1);
      }
      if (fight.detonateCharge == 20) {
         level.playSound(null, at.x, at.y, at.z, SoundEvents.CREEPER_PRIMED, SoundSource.HOSTILE, 2.0F, 0.5F);
         Fx.flare(level, ParticleTypes.FLAME, boss.position().add(0.0, 1.6, 0.0), 1.6, EMBER);
         // The last second: the heat is pulled into the core before it goes.
         Fx.voidCollapse(level, ParticleTypes.FLAME, at.add(0.0, 1.0, 0.0), 6.0, 20, EMBER);
      }
      if (fight.detonateCharge > 0) {
         return;
      }

      double x = at.x;
      double y = at.y + 1.0;
      double z = at.z;
      Vec3 core = new Vec3(x, y, z);
      Fx.flare(level, ParticleTypes.FLAME, core, 4.0, EMBER);
      Fx.shockwave(level, ParticleTypes.FLAME, at, DETONATE_RADIUS + 1.0, EMBER);
      Fx.starburst(level, ParticleTypes.ELECTRIC_SPARK, core, 9.0, BRASS);
      Fx.rockburst(level, ParticleTypes.LARGE_SMOKE, at.add(0.0, 0.3, 0.0), 2.5, EMBER);
      // The mainspring lets go: his cogs blown out flat across the floor, and the blast
      // rolling out to the dome's edge.
      Fx.gearSpin(level, ParticleTypes.ELECTRIC_SPARK, at.add(0.0, 0.2, 0.0), Vec3.ZERO, 4.0, 24, BRASS);
      sonicBurst(level, at.add(0.0, 0.3, 0.0), DETONATE_RADIUS, 16, EMBER, 6);
      // Bounded: these used to be nearly four hundred particles in one tick, which Geyser
      // turns into four hundred packets for every Bedrock player in range.
      Fx.vanilla(level, ParticleTypes.EXPLOSION_EMITTER, x, y, z, 4, 2.0, 1.0, 2.0, 0.1);
      Fx.vanilla(level, ParticleTypes.GUST, x, y, z, 20, 8.0, 1.5, 8.0, 0.2);
      Fx.vanilla(level, ParticleTypes.LARGE_SMOKE, x, y, z, 50, 9.0, 2.0, 9.0, 0.18);
      Fx.vanilla(level, ParticleTypes.FLAME, x, y, z, 40, 7.0, 1.5, 7.0, 0.14);
      level.playSound(null, x, y, z, SoundEvents.GENERIC_EXPLODE, SoundSource.HOSTILE, 3.0F, 0.5F);
      level.playSound(null, x, y, z, SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.HOSTILE, 1.4F, 0.5F);

      for (ServerPlayer p : playersNear(level, x, y, z, DETONATE_RADIUS)) {
         p.hurtServer(level, level.damageSources().mobAttack(boss), 26.0F);
         Vec3 away = flatAway(p.position(), at);
         p.push(away.x * 2.6, 1.0, away.z * 2.6);
         p.hurtMarked = true;
      }

      // The cost of firing it: he is winded, which is the window to punish.
      boss.removeEffect(MobEffects.SLOWNESS);
      boss.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 80, 1, false, true, true));
      boss.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 80, 1, false, true, true));
      announceNear(level, boss, 64.0, "\u00a78He's running cold. \u00a77Hit him now.");
      fight.detonateCharge = 0;
      fight.detonateAt = null;
   }

   // -------------------------------------------------------------------- phases

   private static void enterPhase(MinecraftServer server, Mob boss, Fight fight, int phase) {
      fight.phase = phase;
      ServerLevel level = (ServerLevel) boss.level();
      long now = ServerClock.clock(level);
      if (phase == 2) {
         announceNear(level, boss, 72.0, "\u00a76\u00a7l\u2699 OVERDRIVE \u00a78- \u00a77drones and rams join in.");
         announce(level, SAY + "\"\u00a7fOverclock. \u00a76Full program.\"");
         deploy(level, boss, fight, Role.DRONE, 2);
         deploy(level, boss, fight, Role.PISTON, 1);
         fight.nextRebuild = now + 60L;
         // The new floor attack opens the phase, so it is learned early rather than mid-chaos.
         fight.nextCurrent = now + 80L;
      } else {
         announceNear(level, boss, 72.0, "\u00a76\u00a7l\u2699 MELTDOWN \u00a78- \u00a77his core is venting. Keep moving.");
         announce(level, SAY + "\"\u00a7fRedline. \u00a7cEverything goes.\"");
         deploy(level, boss, fight, Role.TURRET, 2);
         deploy(level, boss, fight, Role.BLADE, 2);
         fight.nextRebuild = now + 40L;
         fight.nextDetonate = now + 100L;
      }
      Vec3 chest = boss.position().add(0.0, 1.6, 0.0);
      Fx.clockBurst(level, ParticleTypes.ELECTRIC_SPARK, chest, 5.0, BRASS);
      Fx.flare(level, ParticleTypes.END_ROD, chest, 2.6, phase >= 3 ? EMBER : BRASS);
      Fx.shockwave(level, ParticleTypes.ELECTRIC_SPARK, boss.position(), 9.0, phase >= 3 ? EMBER : ARC);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.BEACON_ACTIVATE, SoundSource.HOSTILE, 2.0F, 0.6F);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.RESPAWN_ANCHOR_CHARGE, SoundSource.HOSTILE, 1.6F, 0.5F);
      // His gears shift up a ratio: an upright set of cogs over his shoulders spinning up.
      double yaw = Math.toRadians(boss.getYRot());
      Fx.gearSpin(level, ParticleTypes.ELECTRIC_SPARK, worldOffset(boss, 0.0, 3.2, -0.3), new Vec3(-Math.sin(yaw), 0.0, Math.cos(yaw)), 1.4, 30,
         phase >= 3 ? EMBER : BRASS);
      Fx.vanilla(level, ParticleTypes.ELECTRIC_SPARK, boss.getX(), boss.getY() + 1.5, boss.getZ(), 40, 3.0, 1.5, 3.0, 0.2);
   }

   private static String phaseName(Fight fight, Mob boss) {
      if (fight.riseTicks > 0) {
         return BOSS_NAME + " \u00a78| \u00a77Assembling...";
      }
      int live = fight.machines.size();
      String phase = fight.phase == 1 ? "Phase I" : fight.phase == 2 ? "Phase II" : "Phase III";
      return BOSS_NAME + " \u00a78| \u00a7f" + phase + " \u00a78| \u00a77" + live + " part" + (live == 1 ? "" : "s") + " running";
   }

   private static final String[] TAUNTS_P1 = {
      "That's one bolt. I have thousands.",
      "Every part of me is spare.",
      "Keep tinkering.",
   };
   private static final String[] TAUNTS_P2 = {
      "Faster. Keep up.",
      "Tighter. Tighter.",
      "You're behind schedule.",
   };
   private static final String[] TAUNTS_P3 = {
      "Still ticking.",
      "I don't stop. I break.",
      "Hot. Too hot. Good.",
   };

   private static void taunt(Mob boss, Fight fight) {
      if (!(boss.level() instanceof ServerLevel level)) {
         return;
      }
      String[] pool = fight.machines.isEmpty() || fight.phase >= 3 ? TAUNTS_P3 : fight.phase == 2 ? TAUNTS_P2 : TAUNTS_P1;
      announceNear(level, boss, ARENA_RADIUS, SAY + "\"\u00a7f" + pool[RANDOM.nextInt(pool.length)] + "\"");
   }

   // -------------------------------------------------------------- lethal blow

   /**
    * The killing blow is cancelled and played out as a shutdown ceremony, the same
    * way the Scarlet Devil and the Time Lord refuse to simply fall over - so the
    * loot lands once, at the end of the sequence, instead of twice.
    */
   public static Boolean onLethalDamage(Entity entity, float amount) {
      if (!isClockworkKing(entity) || !(entity instanceof Mob boss) || !(boss.level() instanceof ServerLevel level)) {
         return null;
      }
      Fight fight = FIGHTS.get(boss.getUUID());
      // A King we are not running the fight for is not ours to hold up. Cancelling the
      // damage of an untracked one - an orphan left by a crash, say - made it immortal
      // instead of merely unlooted, which is how "unkillable boss" reports start.
      if (fight == null) {
         return null;
      }
      // Already mid-ceremony: keep cancelling blows so a stray hit cannot kill him
      // out from under the sequence that pays the loot. The one exception is the
      // ceremony's own final blow, which is the only thing that may end him.
      if (fight.dying) {
         return fight.paid ? null : Boolean.FALSE;
      }
      if (boss.getHealth() - amount > 0.5F) {
         return null;
      }
      fight.dying = true;
      fight.deathTicks = DEATH_CEREMONY_TICKS;
      // Every warning on the floor dies with him: nothing he showed lands after he falls.
      fight.strikes.clear();
      fight.slamCharge = 0;
      fight.slamTarget = null;
      fight.detonateCharge = 0;
      fight.detonateAt = null;
      fight.riseTicks = 0;
      // He may have been caught mid-assembly; the ceremony's last blow must be able to land.
      boss.setInvulnerable(false);
      boss.setHealth(1.0F);
      boss.setNoAi(true);
      fight.bar.setProgress(0.0F);
      Vec3 chest = boss.position().add(0.0, 1.6, 0.0);
      Fx.spiral(level, ParticleTypes.ELECTRIC_SPARK, boss.position(), 6.0, DEATH_CEREMONY_TICKS, BRASS);
      Fx.aura(level, ParticleTypes.LARGE_SMOKE, boss.position(), 3.2, DEATH_CEREMONY_TICKS, EMBER);
      Fx.runeCircle(level, ParticleTypes.ELECTRIC_SPARK, boss.position().add(0.0, 0.05, 0.0), 5.0, DEATH_CEREMONY_TICKS, BRASS);
      Fx.clockBurst(level, ParticleTypes.ELECTRIC_SPARK, chest, 2.0, ARC);
      // The works run down: his cogs keep turning flat under him for the whole ceremony.
      Fx.gearSpin(level, ParticleTypes.ELECTRIC_SPARK, boss.position().add(0.0, 0.1, 0.0), Vec3.ZERO, 2.2, DEATH_CEREMONY_TICKS, BRASS);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.BEACON_DEACTIVATE, SoundSource.HOSTILE, 2.0F, 0.5F);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.IRON_GOLEM_HURT, SoundSource.HOSTILE, 1.6F, 0.5F);
      announce(level, SAY + "\"\u00a7fNo. \u00a76I'm still ticking.\"");
      // FALSE cancels the blow so the death ceremony - which is what actually
      // drops the loot - gets to run. TRUE let the killing hit land and killed
      // him on the spot with no ceremony and no loot.
      return Boolean.FALSE;
   }

   /**
    * His shutdown, played out over four and a half seconds. The plates come off one by one,
    * each machine is pulled back into him with a last arc, and a clock face pulses over him
    * on a slowing tick while he talks himself down. In the last second the arc all folds
    * into his chest - and then he goes: flare, starburst, a brass shockwave across the
    * arena, a shatter of parts and a rain of embers over the loot.
    */
   private static void tickDeath(MinecraftServer server, Mob boss, Fight fight) {
      ServerLevel level = (ServerLevel) boss.level();
      fight.deathTicks--;
      Vec3 chest = boss.position().add(0.0, 1.6, 0.0);
      double progress = 1.0 - Math.max(0.0, fight.deathTicks / (double) DEATH_CEREMONY_TICKS);

      // His own suit comes apart first, plate by plate, and then the arsenal.
      if (fight.deathTicks % 5 == 0) {
         for (Plate plate : fight.plates) {
            if (plate.displayId == null) {
               continue;
            }
            Entity display = findEntity(server, plate.displayId);
            if (display != null) {
               Fx.shatter(level, ParticleTypes.ELECTRIC_SPARK, display.position(), 0.7, BRASS);
               Fx.vanilla(level, ParticleTypes.ELECTRIC_SPARK, display.getX(), display.getY(), display.getZ(), 20, 0.3, 0.3, 0.3, 0.1);
               Fx.vanilla(level, ParticleTypes.ITEM_SNOWBALL, display.getX(), display.getY(), display.getZ(), 10, 0.3, 0.3, 0.3, 0.05);
               level.playSound(null, display.getX(), display.getY(), display.getZ(), SoundEvents.COPPER_BREAK, SoundSource.HOSTILE, 1.0F, 0.7F);
               display.discard();
            }
            plate.displayId = null;
            break;
         }
      }

      // The whole arsenal powers down with him, one clank at a time.
      if (fight.deathTicks % 10 == 0) {
         List<Machine> remaining = new ArrayList<>(fight.machines.values());
         if (!remaining.isEmpty()) {
            Machine machine = remaining.get(0);
            Entity raw = findEntity(server, machine.id);
            if (raw != null) {
               Vec3 at = raw.position().add(0.0, 0.4, 0.0);
               Fx.lightning(level, ParticleTypes.ELECTRIC_SPARK, at, chest, ARC);
               Fx.shatter(level, ParticleTypes.ELECTRIC_SPARK, at, 0.9, BRASS);
               level.playSound(null, raw.getX(), raw.getY(), raw.getZ(), SoundEvents.ITEM_BREAK, SoundSource.HOSTILE, 0.9F, 0.7F);
               shutDownMachine(raw, machine.chassisId, false);
            } else {
               discardChassis(server, null, machine.chassisId);
            }
            fight.machines.remove(machine.id);
            MACHINE_OWNER.remove(machine.id);
         }
      }

      // The clock winds down: a pulse of his face on a tick that keeps getting lower.
      if (fight.deathTicks % 18 == 0 && fight.deathTicks > 20) {
         Fx.clockBurst(level, ParticleTypes.ELECTRIC_SPARK, chest, 2.0 + progress * 3.0, BRASS);
         level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.NOTE_BLOCK_HAT, SoundSource.HOSTILE, 1.6F, (float) (1.4 - progress));
      }
      if (fight.deathTicks == 70) {
         announce(level, SAY + "\"\u00a7fWho loosened that?\"");
      } else if (fight.deathTicks == 45) {
         announce(level, SAY + "\"\u00a7fI can hear the gears stop.\"");
      } else if (fight.deathTicks == 20) {
         // The last second: everything folds into his core.
         Fx.vortex(level, ParticleTypes.ELECTRIC_SPARK, boss.position(), 6.0, 20, ARC);
         Fx.dome(level, ParticleTypes.END_ROD, chest, 2.5, 20, BRASS);
         Fx.voidCollapse(level, ParticleTypes.ELECTRIC_SPARK, chest, 5.0, 20, ARC);
         level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.WARDEN_SONIC_CHARGE, SoundSource.HOSTILE, 1.6F, 0.8F);
         level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.RESPAWN_ANCHOR_CHARGE, SoundSource.HOSTILE, 1.6F, 1.4F);
      } else if (fight.deathTicks == 8) {
         announce(level, SAY + "\"\u00a7f...tick.\"");
      }

      // Sparks and smoke venting off him for clients without the mod; the spiral and the aura
      // laid down when the ceremony began are the modded clients' version.
      if (fight.deathTicks % 2 == 0) {
         Fx.vanilla(level, ParticleTypes.CRIT, boss.getX(), boss.getY() + 1.5, boss.getZ(), 8, 1.6, 1.2, 1.6, 0.12);
         Fx.vanilla(level, ParticleTypes.LARGE_SMOKE, boss.getX(), boss.getY() + 1.0, boss.getZ(), 6, 1.2, 1.0, 1.2, 0.05);
      }

      if (fight.deathTicks > 0) {
         return;
      }

      Fx.flare(level, ParticleTypes.END_ROD, chest, 4.5, BRASS);
      Fx.starburst(level, ParticleTypes.ELECTRIC_SPARK, chest, 10.0, EMBER);
      Fx.shockwave(level, ParticleTypes.ELECTRIC_SPARK, boss.position(), 14.0, BRASS);
      Fx.clockBurst(level, ParticleTypes.ELECTRIC_SPARK, chest, 6.0, ARC);
      Fx.shatter(level, ParticleTypes.ELECTRIC_SPARK, chest, 2.0, BRASS);
      Fx.pillar(level, ParticleTypes.ELECTRIC_SPARK, boss.position(), 14.0, ARC);
      Fx.emberRain(level, ParticleTypes.FLAME, boss.position(), 8.0, 60, EMBER);
      // He comes apart into his works: a great set of cogs thrown upright over the wreck,
      // still turning as they fall, and the bell-ring of it running out across the floor.
      double yaw = Math.toRadians(boss.getYRot());
      Fx.gearSpin(level, ParticleTypes.ELECTRIC_SPARK, chest.add(0.0, 0.8, 0.0), new Vec3(-Math.sin(yaw), 0.0, Math.cos(yaw)), 2.6, 50, BRASS);
      sonicBurst(level, boss.position().add(0.0, 0.3, 0.0), 14.0, 16, BRASS, 6);
      announce(level, "\u00a76\u00a7lThe Clockwork King \u00a7rbursts into brass and springs. \u00a77The ticking stops.");
      discardPlating(server, fight);
      Fx.vanilla(level, ParticleTypes.EXPLOSION_EMITTER, boss.getX(), boss.getY() + 1.0, boss.getZ(), 6, 1.5, 1.0, 1.5, 0.1);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), ModSounds.BOSS_DEATH, SoundSource.HOSTILE, 2.0F, 0.6F);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.GENERIC_EXPLODE, SoundSource.HOSTILE, 2.0F, 0.7F);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.BELL_BLOCK, SoundSource.HOSTILE, 2.0F, 0.5F);

      // Wound him down to nothing, then let vanilla take the body so the normal
      // death path (and its own drop handling) still runs exactly once. Loot goes
      // through the once-only ledger, so the entity-death hook that follows this
      // kill cannot pay a second time.
      boss.setNoAi(false);
      onBossDeath(level, boss);
      // Declared before the blow, not after: this hook is the thing that would refuse
      // it, and this is the one blow in the fight it must let through.
      fight.paid = true;
      boss.hurtServer(level, level.damageSources().generic(), boss.getMaxHealth() * 4.0F + 100.0F);
   }

   /**
    * Gives him a curated goal set instead of the iron golem's own.
    *
    * <p>He used to be a {@code setNoAi(true)} puppet moved by hand every tick,
    * which is why he "just flew": no AI means no gravity and no pathfinding, so the
    * Piston Slam left him hovering six blocks in the air and nothing ever brought
    * him down. He walks with vanilla pathfinding now, so a wall sends him round it
    * rather than into it, and he falls like anything else.
    *
    * <p>The iron golem's default goals are cleared first, because they belong to a
    * different mob: village strolling would walk him out of the arena entirely,
    * and its melee attack would put an 18-damage fist on top of an arsenal that is
    * supposed to <i>be</i> his damage. What remains is what this fight actually
    * wants - pick a player, and walk at them.
    */
   private static void installAi(Mob boss) {
      if (!(boss instanceof PathfinderMob pathfinder)) {
         return;
      }

      if (boss instanceof MobGoalAccessor goals) {
         GoalSelector selector = goals.fortuneandfavors$goalSelector();
         selector.removeAllGoals(goal -> true);
         selector.addGoal(0, new FloatGoal(pathfinder));
         selector.addGoal(6, new MoveTowardsTargetGoal(pathfinder, WALK_SPEED, WALK_STOP_DISTANCE));
         selector.addGoal(8, new LookAtPlayerGoal(pathfinder, Player.class, 32.0F));
         selector.addGoal(9, new RandomLookAroundGoal(pathfinder));
      }

      if (boss instanceof MobTargetAccessor targets) {
         GoalSelector selector = targets.fortuneandfavors$targetSelector();
         selector.removeAllGoals(goal -> true);
         // Every player in the arena is fair game, whether or not they have struck
         // him first: a raid boss that waits to be provoked can be ignored.
         selector.addGoal(1, new NearestAttackableTargetGoal<>(boss, Player.class, false));
      }
   }

   // --------------------------------------------------------------------- loot

   private static void grantLoot(ServerLevel level, Mob boss, Fight fight) {
      drop(level, boss, ModItems.clockworkTrophy());
      // Forge material only. He used to also scatter iron, redstone and copper,
      // which is a mob's loot table, not a boss's - the box is where the volume
      // lives, and a boss that hands out its own crafting set on every kill makes
      // the second kill pointless.
      int scrap = 2 + RANDOM.nextInt(3);
      for (int i = 0; i < scrap; i++) {
         drop(level, boss, ModItems.mechScrap());
      }
      // His own enchant, not a spare from another boss: Overclock is what the
      // gauntlet's arc is made of, and the tome lets players put it on anything.
      if (RANDOM.nextFloat() < 0.15F) {
         drop(level, boss, CustomEnchantments.tome(CustomEnchantments.OVERCLOCK, 1 + RANDOM.nextInt(3)));
      }

      // One roll at one legendary, never the whole set. The other two are what the
      // loot boxes are for.
      if (RANDOM.nextFloat() < 0.2F) {
         drop(level, boss, switch (RANDOM.nextInt(3)) {
            case 0 -> ModItems.clockworkGauntlet();
            case 1 -> ModItems.mechanicalHeart();
            default -> ModItems.automatonArmor();
         });
      }

      // Three loot boxes each, into the inventory of everyone who fought him.
      BossPayout.payBoxes(level, fight.participants, ModItems::clockworkLootBox, BossPayout.BOXES_PER_KILL, "\u00a76Clockwork Loot Box");
      for (UUID id : fight.participants) {
         ServerPlayer p = level.getServer().getPlayerList().getPlayer(id);
         if (p != null && p.isAlive()) {
            Advancements.grant(p, "kill_clockwork");
         }
      }
      announce(level, "\u00a78\u00a7m\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550");
      announce(level, "    \u00a76\u00a7l\u2699 THE CLOCKWORK KING IS SCRAPPED \u2699");
      announce(level, "\u00a78\u00a7m\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550");
   }

   private static void drop(ServerLevel level, Mob boss, ItemStack stack) {
      if (stack != null && !stack.isEmpty()) {
         level.addFreshEntity(new ItemEntity(level, boss.getX(), boss.getY() + 0.6, boss.getZ(), stack));
      }
   }

   // ------------------------------------------------------------------ teardown

   private static void release(MinecraftServer server, Fight fight) {
      discardPlating(server, fight);
      fight.bar.setVisible(false);
      for (ServerPlayer p : server.getPlayerList().getPlayers()) {
         fight.bar.removePlayer(p);
      }
      for (Machine machine : new ArrayList<>(fight.machines.values())) {
         Entity raw = findEntity(server, machine.id);
         if (raw != null) {
            shutDownMachine(raw, machine.chassisId, false);
         } else {
            discardChassis(server, null, machine.chassisId);
         }
         MACHINE_OWNER.remove(machine.id);
      }
      fight.machines.clear();
      fight.strikes.clear();
      FIGHTS.remove(fight.bossId);
   }

   private static void shutDown(MinecraftServer server, Fight fight, boolean removeBoss) {
      Mob boss = bossOf(server, fight);
      if (boss != null && removeBoss) {
         boss.discard();
      }
      release(server, fight);
   }

   /**
    * Takes one machine out of the world together with its chassis.
    *
    * <p>The chassis has to go first and by name: vanilla throws a removed entity's passengers
    * off rather than removing them, so discarding only the machine left its dispenser or
    * piston block hanging in the air where the machine had been.
    *
    * @param chassisId the chassis recorded for it, if any; whatever display is still riding
    *                  it is discarded as well
    */
   private static void shutDownMachine(Entity machine, UUID chassisId, boolean fx) {
      if (machine == null) {
         return;
      }
      if (fx && machine.level() instanceof ServerLevel level) {
         Fx.shatter(level, ParticleTypes.ELECTRIC_SPARK, machine.position().add(0.0, 0.4, 0.0), 0.7, BRASS);
         Fx.vanilla(level, ParticleTypes.ELECTRIC_SPARK, machine.getX(), machine.getY() + 0.4, machine.getZ(), 16, 0.3, 0.3, 0.3, 0.08);
         level.playSound(null, machine.getX(), machine.getY(), machine.getZ(), SoundEvents.ITEM_BREAK, SoundSource.HOSTILE, 0.8F, 0.7F);
      }
      discardChassis(machine.level().getServer(), machine, chassisId);
      machine.discard();
   }

   /** Discards a machine's chassis: the one recorded for it, and any display still riding it. */
   private static void discardChassis(MinecraftServer server, Entity machine, UUID chassisId) {
      if (machine != null) {
         for (Entity rider : new ArrayList<>(machine.getPassengers())) {
            if (rider instanceof Display) {
               rider.discard();
            }
         }
      }
      if (chassisId != null) {
         Entity chassis = findEntity(server, chassisId);
         if (chassis != null) {
            chassis.discard();
         }
      }
   }

   // ------------------------------------------------------------------ helpers

   private static UUID machineOwnerOf(Entity machine) {
      for (String tag : machine.entityTags()) {
         if (tag.startsWith(MACHINE_BOSS_KEY + ":")) {
            try {
               return UUID.fromString(tag.substring(MACHINE_BOSS_KEY.length() + 1));
            } catch (IllegalArgumentException ignored) {
               return null;
            }
         }
      }
      return null;
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
    * One bolt from a machine at a player.
    *
    * @param spread yaw offset in radians, so a volley can fan rather than stack on one line
    */
   private static void fireArrow(Mob shooter, ServerPlayer target, float damage, double speed, double spread) {
      if (!(shooter.level() instanceof ServerLevel level)) {
         return;
      }
      Vec3 from = shooter.position().add(0.0, shooter.getBbHeight() * 0.6, 0.0);
      Vec3 to = target.position().add(0.0, target.getBbHeight() * 0.5, 0.0);
      Vec3 dir = to.subtract(from);
      if (dir.lengthSqr() < 1.0E-4) {
         dir = new Vec3(0.0, -1.0, 0.0);
      }
      // Aim a touch high so the shot arcs into the target instead of burying
      // itself in the floor a block short - the gravity drop at speed 2.0 is
      // otherwise enough to miss a standing player at range.
      Vec3 flat = dir.normalize();
      double distance = dir.length();
      double lift = Math.min(0.5, distance * 0.008);
      Vec3 aim = flat.add(0.0, lift, 0.0).normalize();
      if (spread != 0.0) {
         double cos = Math.cos(spread);
         double sin = Math.sin(spread);
         aim = new Vec3(aim.x * cos - aim.z * sin, aim.y, aim.x * sin + aim.z * cos);
      }

      Arrow arrow = new Arrow(level, shooter, new ItemStack(Items.ARROW), new ItemStack(Items.CROSSBOW));
      arrow.setPos(from.x, from.y, from.z);
      arrow.setDeltaMovement(aim.scale(speed));
      arrow.setBaseDamage(damage);
      arrow.setCritArrow(false);
      arrow.pickup = AbstractArrow.Pickup.DISALLOWED;
      level.addFreshEntity(arrow);
      level.playSound(null, from.x, from.y, from.z, SoundEvents.ARROW_SHOOT, SoundSource.HOSTILE, 1.0F, 1.1F);
   }

   private static ServerPlayer nearestPlayer(Mob from, double range) {
      if (!(from.level() instanceof ServerLevel level)) {
         return null;
      }
      ServerPlayer best = null;
      double bestDist = range * range;
      for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
         if (!p.isAlive() || p.isCreative() || p.isSpectator() || p.level() != level || BossManager.isFakePlayer(p)) {
            continue;
         }
         double d = p.distanceToSqr(from);
         if (d < bestDist) {
            bestDist = d;
            best = p;
         }
      }
      return best;
   }

   /** The nearest player this machine can actually see - so cover always works against the guns. */
   private static ServerPlayer nearestVisible(Mob from, double range) {
      if (!(from.level() instanceof ServerLevel level)) {
         return null;
      }
      ServerPlayer best = null;
      double bestDist = range * range;
      for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
         if (!p.isAlive() || p.isCreative() || p.isSpectator() || p.level() != level || BossManager.isFakePlayer(p)) {
            continue;
         }
         double d = p.distanceToSqr(from);
         if (d < bestDist && from.hasLineOfSight(p)) {
            bestDist = d;
            best = p;
         }
      }
      return best;
   }

   /** His live machines of one role, as entities. */
   private static List<Mob> liveMachines(MinecraftServer server, Fight fight, Role role) {
      List<Mob> out = new ArrayList<>();
      for (Machine machine : fight.machines.values()) {
         if (machine.role != role) {
            continue;
         }
         if (findEntity(server, machine.id) instanceof Mob mob && mob.isAlive()) {
            out.add(mob);
         }
      }
      return out;
   }

   /**
    * A struck-bell ring through the floor: {@code spokes} sonic crescents racing out from
    * {@code at} along evenly spaced compass lines.
    *
    * <p>Sent to modded clients only. A sonic ring is a single directed wave, so the all-round
    * version is several of them, and every call site already sends a {@link Fx#shockwave}
    * for the same moment - that is the vanilla clients' copy, and a fallback per spoke would
    * only pile a few hundred more particles onto Geyser.
    */
   private static void sonicBurst(ServerLevel level, Vec3 at, double reach, int ticks, int color, int spokes) {
      double offset = RANDOM.nextDouble() * Math.PI;
      for (int i = 0; i < spokes; i++) {
         double a = offset + i * (Math.PI * 2.0 / spokes);
         com.fortuneandfavors.net.FfVfx.shape(level, com.fortuneandfavors.util.FxKinds.SONIC_RING, ParticleTypes.ELECTRIC_SPARK, at,
            new Vec3(Math.cos(a), 0.0, Math.sin(a)), reach, ticks, color);
      }
   }

   /** A flat unit vector from {@code from} out to {@code at}; straight up-free and never zero. */
   private static Vec3 flatAway(Vec3 at, Vec3 from) {
      Vec3 away = new Vec3(at.x - from.x, 0.0, at.z - from.z);
      if (away.lengthSqr() < 1.0E-4) {
         double a = RANDOM.nextDouble() * Math.PI * 2.0;
         return new Vec3(Math.cos(a), 0.0, Math.sin(a));
      }
      return away.normalize();
   }

   /** Everyone in the arena, so machines do not waste shots on bystanders. */
   private static List<ServerPlayer> participantsNear(ServerLevel level, Entity at, double range) {
      List<ServerPlayer> out = new ArrayList<>();
      double r2 = range * range;
      for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
         if (!p.isAlive() || p.isCreative() || p.isSpectator() || p.level() != level || BossManager.isFakePlayer(p)) {
            continue;
         }
         if (p.distanceToSqr(at) <= r2) {
            out.add(p);
         }
      }
      return out;
   }

   private static List<ServerPlayer> playersNear(ServerLevel level, double x, double y, double z, double range) {
      List<ServerPlayer> out = new ArrayList<>();
      double r2 = range * range;
      for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
         if (!p.isAlive() || p.isCreative() || p.isSpectator() || p.level() != level || BossManager.isFakePlayer(p)) {
            continue;
         }
         if (p.distanceToSqr(x, y, z) <= r2) {
            out.add(p);
         }
      }
      return out;
   }

   /**
    * Only his own dialogue (lines starting with {@link #SAY}) goes through the chat limiter.
    * Banners and the grey narrator hints are what players act on, and the limiter's
    * one-line-per-1.2s channel gap used to eat every line of the summon banner after the
    * first, and every hint sent in the same tick as his line. The boss-dialogue switch in the
    * config still silences all of it.
    */
   private static boolean mayAnnounce(String message) {
      if (!message.startsWith(SAY)) {
         return message != null && !message.isEmpty() && ModConfig.bossDialogue();
      }
      return BossChat.allowed("clockwork", message);
   }

   private static void announce(ServerLevel level, String message) {
      if (!mayAnnounce(message)) {
         return;
      }
      for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
         Chat.raw(p, message);
      }
   }

   private static void announceNear(ServerLevel level, Mob boss, double range, String message) {
      if (!mayAnnounce(message)) {
         return;
      }
      double r2 = range * range;
      for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
         if (p.level() == boss.level() && p.distanceToSqr(boss) <= r2) {
            Chat.raw(p, message);
         }
      }
   }

   /** Test hook: proves the armour really tracks the live machinery count. */
   public static int shieldTiersFor(int machines) {
      return Math.min(MAX_SHIELD_TIERS, Math.max(0, machines - 1));
   }

   /** Test hook: his health, so no future tuning can quietly make him unkillable. */
   public static double maxHealth() {
      return MAX_HEALTH;
   }

   /** Test hook: the most Resistance his machinery can ever be worth. */
   public static int maxShieldTiers() {
      return MAX_SHIELD_TIERS;
   }

   /**
    * Test hook: run the AI install against a probe mob, so the goal rig can be
    * asserted from the self-test without staging a live fight. A silently broken
    * goal set is otherwise invisible until someone watches him stand still.
    */
   public static void installAiForTest(Mob boss) {
      installAi(boss);
   }

   // ------------------------------------------------------------------- loot

   /**
    * Bosses whose loot has already been paid out.
    *
    * <p>Loot used to be granted only from the death ceremony, so any death that
    * found another route - a command, a {@code /kill}, vanilla's own body falling
    * over - paid nothing at all. Every route now funnels through
    * {@link #onBossDeath}, and this ledger is what makes that idempotent: the
    * ceremony pays, the death hook that follows it finds the UUID already spent,
    * and nothing drops twice. Entries are pruned in {@link #tick} once the boss
    * itself is gone.
    */
   private static final Set<UUID> LOOT_PAID = new HashSet<>();

   /**
    * Pays this boss's loot exactly once, however he dies, and releases the fight.
    * Called by the death ceremony and by the entity-death hook, in whichever order
    * they happen to run.
    */
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
}
