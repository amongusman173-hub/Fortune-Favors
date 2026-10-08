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
 * than left to vanilla: a telegraphed piston slam, a blade sweep, a magnet pull, a
 * synchronised turret barrage, and - in his last phase - a core detonation that
 * is survivable only by leaving the marked ring, after which he is genuinely
 * vulnerable for a few seconds. He is given no melee goal on purpose: the
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
   /** Entity tag on every block display of his rig - his armour and his machines'
    *  chassis - so an orphaned one can be swept up after a crash. */
   private static final String PLATE_TAG = "ff_clockwork_rig";

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

   /** Machines he holds at each phase. Everything past the first is rebuilt. */
   private static final int P1_MACHINES = 4;
   private static final int P2_MACHINES = 6;
   private static final int P3_MACHINES = 8;
   private static final int HARD_MACHINE_CAP = 10;

   /** How long the death ceremony pauses before the loot lands. */
   private static final int DEATH_CEREMONY_TICKS = 70;

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
      int nextAction;

      Machine(UUID id, Role role, int nextAction) {
         this.id = id;
         this.role = role;
         this.nextAction = nextAction;
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
      int slamCharge;
      int detonateCharge;
      Vec3 slamTarget;
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
      return ended;
   }

   /** Never leave a King or his machines behind on a server stop. */
   public static void onServerStopping(MinecraftServer server) {
      for (Fight fight : new ArrayList<>(FIGHTS.values())) {
         Safe.run("clockwork shutdown", () -> shutDown(server, fight, true));
      }
      FIGHTS.clear();
      MACHINE_OWNER.clear();
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
      level.addFreshEntity(boss);

      ServerBossEvent bar = new ServerBossEvent(
         UUID.randomUUID(), Component.literal(BOSS_NAME), BossBarColor.YELLOW, BossBarOverlay.PROGRESS
      );
      bar.setVisible(true);
      for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
         bar.addPlayer(p);
      }

      Fight fight = new Fight(boss.getUUID(), summoner.getUUID(), bar);
      fight.participants.add(summoner.getUUID());
      long now = ServerClock.clock(level);
      fight.nextSlam = now + 100L;
      fight.nextSweep = now + 200L;
      fight.nextPull = now + 320L;
      fight.nextBarrage = now + 420L;
      fight.nextDetonate = now + 600L;
      fight.nextRebuild = now + REBUILD_COOLDOWN;
      fight.nextTaunt = now + 140L;
      FIGHTS.put(boss.getUUID(), fight);

      // First assembly: the shop floor opens with two turrets and two blades.
      deploy(level, boss, fight, Role.TURRET, 2);
      deploy(level, boss, fight, Role.BLADE, 2);
      buildPlating(fight);

      announce(level, "\u00a78\u00a7m\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550");
      announce(level, "    \u00a76\u00a7l\u2699 THE CLOCKWORK KING ASSEMBLES \u2699");
      announce(level, "    \u00a77A hundred gears find their places at once.");
      announce(level, "    \u00a78\u201c\u00a7fEvery part of me was built to end you. I am the prototype.\u00a78\u201d");
      announce(level, "    \u00a77\u00a7oTear the machines off him - his armour is his arsenal.");
      announce(level, "\u00a78\u00a7m\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550");
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
      if (FIGHTS.isEmpty()) {
         return;
      }
      long now = ServerClock.clock(server.overworld());

      for (Fight fight : new ArrayList<>(FIGHTS.values())) {
         Safe.run("clockwork king tick", () -> tickFight(server, fight, now));
      }

      // Orphan sweep. Plating is saved to chunk data like any other entity, so a
      // crash mid-fight would otherwise resurrect a suit of armour standing over
      // an empty patch of ground forever. Every 15s, near each player, any plate
      // no live fight claims is taken away.
      if (now % 300L == 0L) {
         Safe.run("clockwork plating sweep", () -> sweepOrphanPlating(server));
      }

      // Orphaned machines (their King is gone) shut themselves down, so a kill or a
      // crash can never leave a stray turret firing at an empty field.
      if (MACHINE_OWNER.isEmpty()) {
         return;
      }
      for (Iterator<Map.Entry<UUID, UUID>> it = MACHINE_OWNER.entrySet().iterator(); it.hasNext();) {
         Map.Entry<UUID, UUID> entry = it.next();
         Fight fight = FIGHTS.get(entry.getValue());
         if (fight != null && !fight.dying) {
            continue;
         }
         Entity machine = findEntity(server, entry.getKey());
         if (machine != null) {
            shutDownMachine(machine, false);
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

   private static void sweepOrphanPlating(MinecraftServer server) {
      Set<UUID> owned = new HashSet<>();
      for (Fight fight : FIGHTS.values()) {
         for (Plate plate : fight.plates) {
            if (plate.displayId != null) {
               owned.add(plate.displayId);
            }
         }
      }
      // Every loaded entity in every loaded level, not just the ones within 96 blocks
      // of somebody. The radius version could only ever clean up plating near a player,
      // which is precisely the plating nobody had to look at - a suit left standing in
      // an arena the group walked away from stayed there until somebody came back, and
      // the report of "the block displays are still there" is what that looks like.
      List<Entity> orphans = new ArrayList<>();
      for (ServerLevel level : server.getAllLevels()) {
         for (Entity entity : level.getAllEntities()) {
            if (entity instanceof Display.BlockDisplay display
               && display.entityTags().contains(PLATE_TAG)
               && !owned.contains(display.getUUID())) {
               orphans.add(display);
            }
         }
      }
      for (Entity orphan : orphans) {
         orphan.discard();
      }
   }

   private static void tickFight(MinecraftServer server, Fight fight, long now) {
      Mob boss = bossOf(server, fight);
      if (boss == null) {
         shutDown(server, fight, true);
         return;
      }

      // Track everyone who has been in the arena, so loot goes to the people who
      // actually fought rather than whoever is standing nearest at the end.
      for (ServerPlayer p : server.getPlayerList().getPlayers()) {
         if (p.level() == boss.level() && p.isAlive() && p.distanceToSqr(boss) < ARENA_RADIUS * ARENA_RADIUS) {
            fight.participants.add(p.getUUID());
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

      applyShield(server, boss, fight);
      tickMachines(server, boss, fight, now);
      tickPlating((ServerLevel) boss.level(), boss, fight, now);

      float share = boss.getHealth() / boss.getMaxHealth();
      int wanted = share > 0.6F ? 1 : share > 0.25F ? 2 : 3;
      if (wanted > fight.phase) {
         enterPhase(server, boss, fight, wanted);
      }

      if (now >= fight.nextRebuild && fight.machines.size() < phaseMachineCap(fight.phase)) {
         fight.nextRebuild = now + REBUILD_COOLDOWN;
         rebuild((ServerLevel) boss.level(), boss, fight);
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
      fight.machines.put(machine.getUUID(), new Machine(machine.getUUID(), role, 0));
      MACHINE_OWNER.put(machine.getUUID(), boss.getUUID());

      level.sendParticles(ParticleTypes.ELECTRIC_SPARK, x, y + 0.4, z, 18, 0.4, 0.4, 0.4, 0.05);
      level.sendParticles(ParticleTypes.CRIT, x, y + 0.4, z, 10, 0.3, 0.3, 0.3, 0.06);
      level.playSound(null, x, y, z, SoundEvents.SMITHING_TABLE_USE, SoundSource.HOSTILE, 1.0F, 1.2F);
      spawnChassis(level, machine, role);
      return machine;
   }

   /**
    * A block bolted onto a machine so it reads as machinery rather than as the
    * vanilla mob it is built on: a dispenser for a turret, a trapdoor for a blade,
    * a piston head for the ram and an observer for the drone.
    *
    * <p>The chassis RIDES the machine, so it follows it with no per-tick work at
    * all, and it is tagged so the orphan sweep can clear it if a machine is ever
    * removed in a way that ejects its passengers.
    */
   private static void spawnChassis(ServerLevel level, Mob machine, Role role) {
      Display.BlockDisplay display = (Display.BlockDisplay) EntityTypes.BLOCK_DISPLAY.create(level, EntitySpawnReason.COMMAND);
      if (display == null) {
         return;
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
      display.addTag(PLATE_TAG);
      level.addFreshEntity(display);
      display.startRiding(machine);
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
         announceNear(level, boss, 64.0, "&6&lTHE KING REBUILDS&7 - his arsenal is whole again!");
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
               // It is being torn off right now: a real clatter of parts leaving.
               level.sendParticles(ParticleTypes.ELECTRIC_SPARK, display.getX(), display.getY(), display.getZ(), 26, 0.35, 0.35, 0.35, 0.12);
               level.sendParticles(ParticleTypes.ITEM_SNOWBALL, display.getX(), display.getY(), display.getZ(), 14, 0.35, 0.35, 0.35, 0.06);
               level.sendParticles(ParticleTypes.LARGE_SMOKE, display.getX(), display.getY(), display.getZ(), 10, 0.3, 0.3, 0.3, 0.04);
               level.playSound(null, display.getX(), display.getY(), display.getZ(), SoundEvents.ITEM_BREAK, SoundSource.HOSTILE, 1.0F, 0.6F);
               level.playSound(null, display.getX(), display.getY(), display.getZ(), SoundEvents.COPPER_BREAK, SoundSource.HOSTILE, 1.0F, 0.8F);
               display.discard();
            }
            plate.displayId = null;
            continue;
         }

         if (display == null) {
            display = spawnPlate(level, plate);
            if (display == null) {
               continue;
            }
            plate.displayId = display.getUUID();
            level.sendParticles(ParticleTypes.ELECTRIC_SPARK, display.getX(), display.getY(), display.getZ(), 18, 0.3, 0.3, 0.3, 0.1);
            level.playSound(null, display.getX(), display.getY(), display.getZ(), SoundEvents.COPPER_PLACE, SoundSource.HOSTILE, 1.0F, 0.7F);
         }

         Vec3 at = worldOffset(boss, plate.ox, plate.oy, plate.oz);
         display.setPos(at.x, at.y, at.z);
         display.setYRot(boss.getYRot());
         display.hurtMarked = true;
         if (now % 15L == 0L) {
            level.sendParticles(ParticleTypes.ELECTRIC_SPARK, at.x, at.y, at.z, 1, 0.25, 0.25, 0.25, 0.0);
         }
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
         if (!(raw instanceof Mob mob) || !mob.isAlive()) {
            it.remove();
            MACHINE_OWNER.remove(machine.id);
            continue;
         }
         if (now < machine.nextAction) {
            continue;
         }
         switch (machine.role) {
            case TURRET -> {
               ServerPlayer target = nearestPlayer(mob, 34.0);
               if (target != null) {
                  fireArrow(mob, target, 5.0F, 2.0);
                  machine.nextAction = (int) (now + 30L + RANDOM.nextInt(20));
               } else {
                  machine.nextAction = (int) (now + 20L);
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
               ServerLevel level = (ServerLevel) mob.level();
               level.sendParticles(ParticleTypes.SWEEP_ATTACK, mob.getX(), mob.getY() + 0.4, mob.getZ(), 1, 0.0, 0.0, 0.0, 0.0);
               for (ServerPlayer p : participantsNear(level, mob, 3.0)) {
                  if (now >= machine.nextAction) {
                     machine.nextAction = (int) (now + 22L);
                     p.hurtServer(level, level.damageSources().mobAttack(mob), 7.0F);
                     level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.HOSTILE, 1.0F, 0.9F);
                  }
               }
            }
            case PISTON -> {
               ServerPlayer target = nearestPlayer(mob, 14.0);
               if (target != null) {
                  Vec3 toward = target.position().subtract(mob.position()).normalize();
                  mob.setDeltaMovement(toward.scale(1.1).add(0.0, 0.45, 0.0));
                  mob.setNoGravity(true);
                  mob.hurtMarked = true;
                  ServerLevel level = (ServerLevel) mob.level();
                  if (mob.distanceToSqr(target) < 6.0) {
                     target.hurtServer(level, level.damageSources().mobAttack(mob), 12.0F);
                     target.push(toward.x * 1.4, 1.1, toward.z * 1.4);
                     target.hurtMarked = true;
                     level.sendParticles(ParticleTypes.GUST, target.getX(), target.getY() + 0.4, target.getZ(), 6, 0.4, 0.3, 0.4, 0.05);
                     level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), ModSounds.BOSS_SLAM, SoundSource.HOSTILE, 0.8F, 1.4F);
                     machine.nextAction = (int) (now + 60L);
                  } else {
                     machine.nextAction = (int) (now + 14L);
                  }
               } else {
                  machine.nextAction = (int) (now + 30L);
               }
            }
            case DRONE -> {
               // Hover above him and snipe: higher ground, longer reach.
               double angle = now * 0.05 + machine.id.hashCode() % 9;
               mob.setPos(boss.getX() + Math.cos(angle) * 5.5, boss.getY() + 5.0, boss.getZ() + Math.sin(angle) * 5.5);
               mob.hurtMarked = true;
               ServerPlayer target = nearestPlayer(mob, 44.0);
               if (target != null) {
                  fireArrow(mob, target, 4.0F, 2.2);
                  machine.nextAction = (int) (now + 45L + RANDOM.nextInt(25));
               } else {
                  machine.nextAction = (int) (now + 25L);
               }
            }
         }
      }
   }

   /** A machine was torn off him: strip a tier, chip his real health, open a window. */
   public static void onMachineKilled(ServerLevel level, Mob machine, ServerPlayer killer) {
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

      level.sendParticles(ParticleTypes.ELECTRIC_SPARK, machine.getX(), machine.getY() + 0.5, machine.getZ(), 40, 0.6, 0.6, 0.6, 0.12);
      level.sendParticles(ParticleTypes.LARGE_SMOKE, machine.getX(), machine.getY() + 0.5, machine.getZ(), 16, 0.4, 0.4, 0.4, 0.04);
      level.sendParticles(ParticleTypes.ITEM_SNOWBALL, machine.getX(), machine.getY() + 0.5, machine.getZ(), 12, 0.4, 0.4, 0.4, 0.06);
      level.playSound(null, machine.getX(), machine.getY(), machine.getZ(), SoundEvents.ITEM_BREAK, SoundSource.HOSTILE, 1.2F, 0.8F);
      level.playSound(null, machine.getX(), machine.getY(), machine.getZ(), SoundEvents.STONE_BREAK, SoundSource.HOSTILE, 1.0F, 0.7F);

      String who = killer != null ? killer.getName().getString() : "someone";
      announceNear(level, boss, 64.0, "&6\u2726 " + who + " &7tore off " + machine.getCustomName().getString()
         + "&7 - armour down to &f" + fight.machines.size() + "&7 part(s).");
      machine.discard();
   }

   // --------------------------------------------------------------------- moves

   private static void chooseMove(MinecraftServer server, Mob boss, Fight fight, long now) {
      if (fight.phase >= 3 && now >= fight.nextDetonate) {
         fight.nextDetonate = now + DETONATE_COOLDOWN;
         startDetonate(server, boss, fight);
         return;
      }
      if (now >= fight.nextBarrage) {
         fight.nextBarrage = now + BARRAGE_COOLDOWN;
         turretBarrage(server, boss, fight);
         return;
      }
      if (now >= fight.nextPull) {
         fight.nextPull = now + PULL_COOLDOWN;
         magnetPull(server, boss, fight);
         return;
      }
      if (now >= fight.nextSweep) {
         fight.nextSweep = now + SWEEP_COOLDOWN;
         bladeSweep(server, boss, fight);
         return;
      }
      if (now >= fight.nextSlam) {
         fight.nextSlam = now + SLAM_COOLDOWN;
         startSlam(server, boss, fight);
      }
   }

   /** Telegraphed slam: the ring shows where it lands before it lands. */
   private static void startSlam(MinecraftServer server, Mob boss, Fight fight) {
      ServerPlayer target = nearestPlayer(boss, 48.0);
      if (target == null) {
         return;
      }
      fight.slamTarget = target.position();
      fight.slamCharge = 30;
      ServerLevel level = (ServerLevel) boss.level();
      boss.teleportTo(target.getX(), target.getY() + 6.0, target.getZ());
      boss.setDeltaMovement(Vec3.ZERO);
      boss.hurtMarked = true;
      announceNear(level, boss, 64.0, "&6&lPISTON SLAM&7 - get out of the ring!");
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.RESPAWN_ANCHOR_CHARGE, SoundSource.HOSTILE, 1.4F, 0.6F);
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
      double r = 4.5;
      int points = 28;
      for (int i = 0; i < points; i++) {
         double a = i * (Math.PI * 2.0 / points);
         level.sendParticles(
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
      level.playSound(null, x, y, z, ModSounds.BOSS_SLAM, SoundSource.HOSTILE, 2.0F, 0.6F);
      level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, x, y + 0.5, z, 2, 0.5, 0.2, 0.5, 0.0);
      level.sendParticles(ParticleTypes.GUST, x, y + 0.4, z, 24, 2.5, 0.3, 2.5, 0.1);
      level.sendParticles(ParticleTypes.LARGE_SMOKE, x, y + 0.5, z, 40, 2.6, 0.4, 2.6, 0.08);

      for (ServerPlayer p : playersNear(level, x, y, z, 5.0)) {
         Vec3 away = p.position().subtract(x, y, z);
         if (away.lengthSqr() < 0.01) {
            away = new Vec3(0.0, 1.0, 0.0);
         }
         away = away.normalize();
         p.push(away.x * 2.0, 0.9, away.z * 2.0);
         p.hurtMarked = true;
         p.hurtServer(level, level.damageSources().mobAttack(boss), 14.0F);
      }
      fight.slamCharge = 0;
      fight.slamTarget = null;
   }

   /** Every blade dashes outward from the King, then snaps back. */
   private static void bladeSweep(MinecraftServer server, Mob boss, Fight fight) {
      ServerLevel level = (ServerLevel) boss.level();
      int swept = 0;
      for (Machine machine : fight.machines.values()) {
         if (machine.role != Role.BLADE) {
            continue;
         }
         Entity raw = findEntity(server, machine.id);
         if (!(raw instanceof Mob blade) || !blade.isAlive()) {
            continue;
         }
         swept++;
         Vec3 outward = new Vec3(RANDOM.nextDouble() - 0.5, 0.1, RANDOM.nextDouble() - 0.5).normalize();
         blade.setPos(boss.getX() + outward.x * 7.0, boss.getY() + 1.0, boss.getZ() + outward.z * 7.0);
         blade.hurtMarked = true;
         for (ServerPlayer p : participantsNear(level, blade, 4.0)) {
            p.hurtServer(level, level.damageSources().mobAttack(blade), 9.0F);
         }
         level.sendParticles(ParticleTypes.SWEEP_ATTACK, blade.getX(), blade.getY() + 0.4, blade.getZ(), 6, 1.2, 0.4, 1.2, 0.05);
      }
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.HOSTILE, 1.6F, 0.7F);
      announceNear(level, boss, 64.0, swept == 0
         ? "&6&lBLADE SWEEP&7 - the blades are gone, so it spins at nothing."
         : "&6&lBLADE SWEEP&7 - " + swept + " blade(s) cut outward!");
   }

   /** Drags everyone toward the King, into the middle of the arsenal. */
   private static void magnetPull(MinecraftServer server, Mob boss, Fight fight) {
      ServerLevel level = (ServerLevel) boss.level();
      for (ServerPlayer p : participantsNear(level, boss, 40.0)) {
         Vec3 pull = boss.position().add(0.0, 1.0, 0.0).subtract(p.position());
         double len = pull.length();
         if (len < 1.0) {
            continue;
         }
         Vec3 unit = pull.scale(1.0 / len);
         p.push(unit.x * 1.5, 0.35, unit.z * 1.5);
         p.hurtMarked = true;
         p.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 40, 0, false, true, true));
         for (double d = 1.0; d < len; d += 1.2) {
            Vec3 point = p.position().add(unit.scale(d));
            level.sendParticles(ParticleTypes.ELECTRIC_SPARK, point.x, point.y + 1.0, point.z, 1, 0.05, 0.05, 0.05, 0.0);
         }
      }
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.BEACON_ACTIVATE, SoundSource.HOSTILE, 1.6F, 0.7F);
      announceNear(level, boss, 64.0, "&6&lMAGNETIC HAUL&7 - he reels you into the machinery!");
   }

   /** Every turret and drone fires together, so the volley has to be read as one. */
   private static void turretBarrage(MinecraftServer server, Mob boss, Fight fight) {
      ServerLevel level = (ServerLevel) boss.level();
      int firing = 0;
      for (Machine machine : fight.machines.values()) {
         if (machine.role != Role.TURRET && machine.role != Role.DRONE) {
            continue;
         }
         Entity raw = findEntity(server, machine.id);
         if (!(raw instanceof Mob shooter) || !shooter.isAlive()) {
            continue;
         }
         ServerPlayer target = nearestPlayer(shooter, 40.0);
         if (target == null) {
            continue;
         }
         firing++;
         for (int i = -1; i <= 1; i++) {
            fireArrow(shooter, target, 5.0F, 2.1 + i * 0.12);
         }
      }
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.CROSSBOW_SHOOT, SoundSource.HOSTILE, 1.6F, 0.6F);
      announceNear(level, boss, 64.0, firing == 0
         ? "&6&lTURRET BARRAGE&7 - nothing left to fire. He is bare."
         : "&6&lTURRET BARRAGE&7 - " + firing + " emplacement(s) open fire!");
   }

   /** Phase-three finale: a marked ring, then a blast that must be walked out of. */
   private static void startDetonate(MinecraftServer server, Mob boss, Fight fight) {
      fight.detonateCharge = 60;
      ServerLevel level = (ServerLevel) boss.level();
      boss.addEffect(new MobEffectInstance(MobEffects.GLOWING, 70, 0, false, false, false));
      announceNear(level, boss, 64.0, "&6&l\u26a0 CORE DETONATION&7 - clear the marked ring!");
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.RESPAWN_ANCHOR_CHARGE, SoundSource.HOSTILE, 2.0F, 0.4F);
   }

   private static void tickDetonateCharge(MinecraftServer server, Mob boss, Fight fight) {
      ServerLevel level = (ServerLevel) boss.level();
      fight.detonateCharge--;
      int elapsed = 60 - fight.detonateCharge;
      double r = 3.0 + elapsed * 0.22;
      int points = 36;
      for (int i = 0; i < points; i++) {
         double a = i * (Math.PI * 2.0 / points) + elapsed * 0.05;
         level.sendParticles(
            ParticleTypes.ELECTRIC_SPARK,
            boss.getX() + Math.cos(a) * r,
            boss.getY() + 0.15,
            boss.getZ() + Math.sin(a) * r,
            1,
            0.0,
            0.0,
            0.0,
            0.0
         );
      }
      level.sendParticles(ParticleTypes.CRIT, boss.getX(), boss.getY() + 1.2, boss.getZ(), 8, 1.0, 1.0, 1.0, 0.1);
      if (fight.detonateCharge > 0) {
         return;
      }

      double x = boss.getX();
      double y = boss.getY() + 1.0;
      double z = boss.getZ();
      level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, x, y, z, 8, 2.0, 1.0, 2.0, 0.1);
      level.sendParticles(ParticleTypes.GUST, x, y, z, 60, 8.0, 1.5, 8.0, 0.2);
      level.sendParticles(ParticleTypes.LARGE_SMOKE, x, y, z, 200, 9.0, 2.0, 9.0, 0.18);
      level.sendParticles(ParticleTypes.FLAME, x, y, z, 120, 7.0, 1.5, 7.0, 0.14);
      level.playSound(null, x, y, z, SoundEvents.GENERIC_EXPLODE, SoundSource.HOSTILE, 3.0F, 0.5F);
      level.playSound(null, x, y, z, SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.HOSTILE, 1.4F, 0.5F);

      for (ServerPlayer p : participantsNear(level, boss, 16.0)) {
         p.hurtServer(level, level.damageSources().mobAttack(boss), 26.0F);
         Vec3 away = p.position().subtract(boss.position());
         if (away.lengthSqr() < 0.01) {
            away = new Vec3(0.0, 1.0, 0.0);
         }
         away = away.normalize();
         p.push(away.x * 2.6, 1.0, away.z * 2.6);
         p.hurtMarked = true;
      }

      // The cost of firing it: he is winded, which is the window to punish.
      boss.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 80, 1, false, true, true));
      boss.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 80, 1, false, true, true));
      announceNear(level, boss, 64.0, "&6\u26a0 The King is running cold&7 - \u00a7fhit him now!");
      fight.detonateCharge = 0;
   }

   // -------------------------------------------------------------------- phases

   private static void enterPhase(MinecraftServer server, Mob boss, Fight fight, int phase) {
      fight.phase = phase;
      ServerLevel level = (ServerLevel) boss.level();
      if (phase == 2) {
         announceNear(level, boss, 72.0, "&6&l\u2699 OVERDRIVE&7 - the King spins up his whole assembly line.");
         announce(level, SAY + "\"\u00a7fFine. Let us run the \u00a76full\u00a7f program.\"");
         deploy(level, boss, fight, Role.DRONE, 2);
         deploy(level, boss, fight, Role.PISTON, 1);
         fight.nextRebuild = ServerClock.clock(level) + 60L;
      } else {
         announceNear(level, boss, 72.0, "&6&l\u2699 MELTDOWN&7 - he is venting the core. Do not stand still.");
         announce(level, SAY + "\"\u00a7fI am the prototype. \u00a7fPrototypes \u00a7cdetonate\u00a7f.\"");
         deploy(level, boss, fight, Role.TURRET, 2);
         deploy(level, boss, fight, Role.BLADE, 2);
         fight.nextRebuild = ServerClock.clock(level) + 40L;
         fight.nextDetonate = ServerClock.clock(level) + 100L;
      }
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.BEACON_ACTIVATE, SoundSource.HOSTILE, 2.0F, 0.6F);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.RESPAWN_ANCHOR_CHARGE, SoundSource.HOSTILE, 1.6F, 0.5F);
      level.sendParticles(ParticleTypes.ELECTRIC_SPARK, boss.getX(), boss.getY() + 1.5, boss.getZ(), 90, 3.0, 1.5, 3.0, 0.2);
      level.sendParticles(
         net.minecraft.core.particles.ColorParticleOption.create(ParticleTypes.FLASH, 0xFFAA33),
         boss.getX(),
         boss.getY() + 1.5,
         boss.getZ(),
         1,
         0.0,
         0.0,
         0.0,
         0.0
      );
   }

   private static String phaseName(Fight fight, Mob boss) {
      int live = fight.machines.size();
      String phase = fight.phase == 1 ? "Phase I" : fight.phase == 2 ? "Phase II" : "Phase III";
      return BOSS_NAME + " \u00a78| \u00a7f" + phase + " \u00a78| \u00a77" + live + " part" + (live == 1 ? "" : "s") + " running";
   }

   private static void taunt(Mob boss, Fight fight) {
      if (!(boss.level() instanceof ServerLevel level)) {
         return;
      }
      String line = switch (fight.machines.isEmpty() ? 3 : fight.phase) {
         case 1 -> "&8\u201c&7You break a turret and call it progress. I have a hundred more.&8\u201d";
         case 2 -> "&8\u201c&7Escalation is just a gear ratio.&8\u201d";
         default -> "&8\u201c&7Every bolt you pull out is one I fitted myself. I remember all of them.&8\u201d";
      };
      announce(level, SAY + "\"" + line + "\"");
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
      boss.setHealth(1.0F);
      boss.setNoAi(true);
      fight.bar.setProgress(0.0F);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.BEACON_DEACTIVATE, SoundSource.HOSTILE, 2.0F, 0.5F);
      announce(level, SAY + "\"\u00a7f...then let us see what I was worth.\"");
      // FALSE cancels the blow so the death ceremony - which is what actually
      // drops the loot - gets to run. TRUE let the killing hit land and killed
      // him on the spot with no ceremony and no loot.
      return Boolean.FALSE;
   }

   private static void tickDeath(MinecraftServer server, Mob boss, Fight fight) {
      ServerLevel level = (ServerLevel) boss.level();
      fight.deathTicks--;

      // His own suit comes apart first, plate by plate, and then the arsenal.
      if (fight.deathTicks % 5 == 0) {
         for (Plate plate : fight.plates) {
            if (plate.displayId == null) {
               continue;
            }
            Entity display = findEntity(server, plate.displayId);
            if (display != null) {
               level.sendParticles(ParticleTypes.ELECTRIC_SPARK, display.getX(), display.getY(), display.getZ(), 20, 0.3, 0.3, 0.3, 0.1);
               level.sendParticles(ParticleTypes.ITEM_SNOWBALL, display.getX(), display.getY(), display.getZ(), 10, 0.3, 0.3, 0.3, 0.05);
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
               level.sendParticles(ParticleTypes.ELECTRIC_SPARK, raw.getX(), raw.getY() + 0.4, raw.getZ(), 24, 0.3, 0.3, 0.3, 0.1);
               level.playSound(null, raw.getX(), raw.getY(), raw.getZ(), SoundEvents.ITEM_BREAK, SoundSource.HOSTILE, 0.9F, 0.7F);
               raw.discard();
            }
            fight.machines.remove(machine.id);
            MACHINE_OWNER.remove(machine.id);
         }
      }

      level.sendParticles(ParticleTypes.CRIT, boss.getX(), boss.getY() + 1.5, boss.getZ(), 10, 1.6, 1.2, 1.6, 0.12);
      level.sendParticles(ParticleTypes.LARGE_SMOKE, boss.getX(), boss.getY() + 1.0, boss.getZ(), 8, 1.2, 1.0, 1.2, 0.05);

      if (fight.deathTicks > 0) {
         return;
      }

      discardPlating(server, fight);
      level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, boss.getX(), boss.getY() + 1.0, boss.getZ(), 6, 1.5, 1.0, 1.5, 0.1);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), ModSounds.BOSS_DEATH, SoundSource.HOSTILE, 2.0F, 0.6F);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.GENERIC_EXPLODE, SoundSource.HOSTILE, 2.0F, 0.7F);

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
            raw.discard();
         }
         MACHINE_OWNER.remove(machine.id);
      }
      fight.machines.clear();
      FIGHTS.remove(fight.bossId);
   }

   private static void shutDown(MinecraftServer server, Fight fight, boolean removeBoss) {
      Mob boss = bossOf(server, fight);
      if (boss != null && removeBoss) {
         boss.discard();
      }
      release(server, fight);
   }

   private static void shutDownMachine(Entity machine, boolean fx) {
      if (fx && machine.level() instanceof ServerLevel level) {
         level.sendParticles(ParticleTypes.ELECTRIC_SPARK, machine.getX(), machine.getY() + 0.4, machine.getZ(), 16, 0.3, 0.3, 0.3, 0.08);
         level.playSound(null, machine.getX(), machine.getY(), machine.getZ(), SoundEvents.ITEM_BREAK, SoundSource.HOSTILE, 0.8F, 0.7F);
      }
      machine.discard();
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

   private static void fireArrow(Mob shooter, ServerPlayer target, float damage, double speed) {
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
         if (!p.isAlive() || p.isCreative() || p.isSpectator() || p.level() != level) {
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

   /** Everyone in the arena, so machines do not waste shots on bystanders. */
   private static List<ServerPlayer> participantsNear(ServerLevel level, Entity at, double range) {
      List<ServerPlayer> out = new ArrayList<>();
      double r2 = range * range;
      for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
         if (!p.isAlive() || p.isCreative() || p.isSpectator() || p.level() != level) {
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
         if (!p.isAlive() || p.isCreative() || p.isSpectator() || p.level() != level) {
            continue;
         }
         if (p.distanceToSqr(x, y, z) <= r2) {
            out.add(p);
         }
      }
      return out;
   }

   private static void announce(ServerLevel level, String message) {
      if (!BossChat.allowed("clockwork", message)) {
         return;
      }
      for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
         Chat.raw(p, message);
      }
   }

   private static void announceNear(ServerLevel level, Mob boss, double range, String message) {
      if (!BossChat.allowed("clockwork", message)) {
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
