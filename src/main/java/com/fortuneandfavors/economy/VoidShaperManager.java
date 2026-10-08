package com.fortuneandfavors.economy;

import com.fortuneandfavors.ModItems;
import com.fortuneandfavors.ModSounds;
import com.fortuneandfavors.util.Chat;
import com.fortuneandfavors.util.FxKinds;
import com.fortuneandfavors.util.Safe;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
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
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * <b>The Void Shaper</b> - the boss who fights with the world instead of with
 * projectiles.
 *
 * <h2>He does not shoot fireballs. He shoots the ground.</h2>
 * Every attack begins by <em>taking a block out of the world</em>: he rips it up
 * as a floating {@link Display.BlockDisplay}, holds it for a moment, and then
 * throws it. Because the block is a real block from the terrain, what it does on
 * impact is read off the block itself:
 *
 * <ul>
 *   <li><b>Stone / deepslate</b> - heavy: a slow, brutal hit with huge knockback.</li>
 *   <li><b>Magma</b> - burning: sets you alight on impact.</li>
 *   <li><b>Ice / snow</b> - chilling: heavy Slowness.</li>
 *   <li><b>Leaves / grass</b> - it shatters into a spray of small fragments.</li>
 *   <li><b>Ores</b> - dense and fast: an ore flies far quicker than anything else.</li>
 *   <li><b>Logs</b> - massive knockback, poor damage: a battering ram.</li>
 * </ul>
 *
 * <p>That table is the fight's texture. You learn to watch what he is holding,
 * because an ore shard is a fast problem and a log is a flight problem, and the
 * answer to each is different.
 *
 * <h2>At 40% he loses his grip</h2>
 * He stops taking one block at a time and hoists five to eight of them into orbit.
 * Then the announcement lands - <b>THE VOID SHAPER HAS LOST CONTROL</b> - and they
 * start firing off in every direction at once. There is no arena and never was:
 * the final phase is the terrain you brought with you.
 *
 * <h2>The ground itself, marked first</h2>
 * Two moves do not throw anything. <b>Fault Line</b> lights runes along the floor toward
 * you and bursts them a second and a half later (step off the line, or jump it), and
 * <b>Event Horizon</b> opens a hole over his head that hits everyone outside the dome round
 * his feet who can see it (run in to him, or hide). Both are drawn before they land.
 *
 * <p>Blocks are taken from terrain only (never from anything a player built is
 * safe to say: player-placed detection is not attempted, so he does churn through
 * a base - the fight is meant to be destructive by design), and a block he is
 * holding is never written back into the world. Nothing here can duplicate or
 * destroy items.
 */
public final class VoidShaperManager {

   private static final String TAG = "ff_void_shaper";
   private static final String SHARD_TAG = "ff_void_shard";
   private static final String HOLD_TAG = "ff_void_hold";
   private static final String BOSS_NAME = "\u00a75\u00a7l\ud83d\udd2e The Void Shaper";

   private static final double MAX_HEALTH = 760.0;
   private static final double ARENA_RADIUS = 80.0;
   /** See {@code ClockworkKingManager.STRAY_SWEEP_RADIUS}: just wider than the
    *  widest arena in the mod, and no wider. */
   public static final double STRAY_SWEEP_RADIUS = 96.0;

   private static final float STONE_DAMAGE = 14.0F;
   private static final float MAGMA_DAMAGE = 11.0F;
   private static final float ICE_DAMAGE = 9.0F;
   private static final float LEAF_DAMAGE = 4.0F;
   private static final float ORE_DAMAGE = 16.0F;
   private static final float LOG_DAMAGE = 10.0F;

   private static final int HOLD_TICKS = 34;
   private static final int GRAB_RANGE = 12;

   private static final int THROW_COOLDOWN = 90;
   private static final int PULL_COOLDOWN = 300;
   private static final int WALL_COOLDOWN = 360;
   private static final int BARRAGE_COOLDOWN = 260;
   private static final int STEP_COOLDOWN = 180;
   private static final int SLAM_COOLDOWN = 320;
   private static final int DEATH_CEREMONY_TICKS = 100;

   /** His voice: a few words at a time, never a speech. */
   private static final String SAY = "§5The Void Shaper§r§7 › §f";

   // The palette: the violet of the tear, the pale light inside it, and the black behind both.
   private static final int VOID = 0x7A2BD9;
   private static final int VOID_LIGHT = 0xB06BFF;
   private static final int VOID_DARK = 0x2A0A4A;

   /** The arrival: he climbs out of the ground over three seconds, untouchable while he does. */
   private static final int RISE_TICKS = 60;
   private static final double RISE_DEPTH = 3.0;
   /** With nobody in reach for this long, he sinks back into the ground and the fight ends. */
   private static final long LONELY_TICKS = 600L;

   // --- Fault Line -----------------------------------------------------------

   /**
    * He splits the ground along a line toward you. Runes light up along the crack first,
    * and the floor under them bursts a second and a half later, rippling outward from him.
    * The answer is a sidestep (or a well-timed jump). In phase two he splits three at once,
    * fanned, so the sidestep has to pick a gap.
    */
   private static final int FAULT_COOLDOWN = 220;
   private static final int FAULT_WARN = 30;
   private static final int FAULT_NODES = 7;
   private static final double FAULT_SPACING = 2.6;
   private static final double FAULT_RADIUS = 1.7;
   private static final float FAULT_DAMAGE = 12.0F;

   // --- Event Horizon --------------------------------------------------------

   /**
    * He stops, and a hole opens over his head. A dome on the floor around him marks the
    * only safe ground; when the hole goes off, everyone outside it who can see it is hit.
    * Two answers, both readable: run <i>in</i> to him, or get something solid between you
    * and the hole.
    */
   private static final int HORIZON_COOLDOWN = 520;
   private static final int HORIZON_WARN = 56;
   private static final double HORIZON_SAFE = 6.0;
   private static final double HORIZON_REACH = 32.0;
   private static final double HORIZON_EYE = 7.0;
   private static final float HORIZON_DAMAGE = 15.0F;
   /**
    * Every thrown-block shove, scaled down.
    *
    * <p>His knockback is the whole point of the logs and the stone - the block you are hit
    * by should change how you are moved - but the old numbers were written as if a player
    * were a barrel: a log to the chest crossed most of an arena, and the lift on top of it
    * meant the landing was never where anyone was aiming. The kinds keep their order
    * (logs still shove hardest, deepslate still hits heaviest) and each one now pushes
    * roughly half as far, which is the difference between being moved and being thrown
    * out of the fight. The vertical is scaled with it so the arc matches the distance.
    */
   public static final double KNOCKBACK_SCALE = 0.55;

   /** The horizontal shove one thrown block gives, in blocks per tick. */
   public static double scaledKnockback(double raw) {
      return raw * KNOCKBACK_SCALE;
   }

   // --- Gravity Inversion ---------------------------------------------------

   /**
    * His signature: he turns the arena's gravity over.
    *
    * <p>Every other move he has is a projectile you can read and a wall you can put
    * behind you. This one takes the floor away instead - everyone in the arena lifts
    * off it for two and a half seconds, hangs there in the open where he can see you,
    * and then comes down hard. It is the only move in the fight with no tell at all
    * beyond the way the air starts moving, and the only answer to it is what you are
    * wearing or carrying when it starts: a bucket of water, a potion of slow falling,
    * or enough health to eat the landing.
    *
    * <p>It is deliberately readable on the way up - the arrows and dust all fall
    * <i>upward</i> while it holds - because being lifted without understanding why is
    * a bug, not a mechanic.
    */
   private static final int INVERT_COOLDOWN = 540;
   private static final int INVERT_TICKS = 50;
   private static final float INVERT_SLAM_DAMAGE = 9.0F;

   private static final Random RANDOM = new Random();

   /** What a thrown block does, decided entirely by the block it is. */
   static final class Kind {
      final String id;
      final float damage;
      final double speed;
      final double knockback;
      final int fireTicks;
      final int slownessTicks;
      final int fragments;

      Kind(String id, float damage, double speed, double knockback, int fireTicks, int slownessTicks, int fragments) {
         this.id = id;
         this.damage = damage;
         this.speed = speed;
         this.knockback = knockback;
         this.fireTicks = fireTicks;
         this.slownessTicks = slownessTicks;
         this.fragments = fragments;
      }
   }

   /** A block in flight - a display entity this class moves and resolves itself. */
   static final class Shot {
      final UUID displayId;
      Vec3 pos;
      final Vec3 vel;
      final Kind kind;
      int life;
      /**
       * A leaf fragment. Fragments never fragment again: a fragment that did - with the
       * same "leaves" kind as its parent - split into seven more on every impact, and a
       * single leaf block snowballed into hundreds of displays.
       */
      final boolean fragment;

      Shot(UUID displayId, Vec3 pos, Vec3 vel, Kind kind, int life) {
         this(displayId, pos, vel, kind, life, false);
      }

      Shot(UUID displayId, Vec3 pos, Vec3 vel, Kind kind, int life, boolean fragment) {
         this.displayId = displayId;
         this.pos = pos;
         this.vel = vel;
         this.kind = kind;
         this.life = life;
         this.fragment = fragment;
      }
   }

   /**
    * A blow marked on the floor that has not landed yet - a rune on the fault line, or the
    * hole over his head. Marked first and landed later, so each is a warning before a hit.
    */
   private static final class Strike {
      static final int FAULT = 0;
      static final int HORIZON = 1;
      final int kind;
      final Vec3 at;
      final Vec3 dir;
      final long landAt;
      final double radius;
      final float damage;
      /** Shared by every node of one cast, so one fault line hits a player once. */
      final Set<UUID> hit;
      boolean primed;

      Strike(int kind, Vec3 at, Vec3 dir, long landAt, double radius, float damage, Set<UUID> hit) {
         this.kind = kind;
         this.at = at;
         this.dir = dir;
         this.landAt = landAt;
         this.radius = radius;
         this.damage = damage;
         this.hit = hit;
      }
   }

   /** A block he is holding before the throw. */
   private static final class Held {
      final UUID displayId;
      final Kind kind;
      final double angle;
      final double radius;
      final double height;
      int fuse;
      /** The throw has been flagged with its flash - once, just before it leaves. */
      boolean told;

      Held(UUID displayId, Kind kind, double angle, double radius, double height, int fuse) {
         this.displayId = displayId;
         this.kind = kind;
         this.angle = angle;
         this.radius = radius;
         this.height = height;
         this.fuse = fuse;
      }
   }

   private static final class Fight {
      final UUID bossId;
      final UUID summoner;
      final ServerBossEvent bar;
      final Set<UUID> participants = new HashSet<>();
      int phase = 1;
      boolean dying;
      int deathTicks;
      long nextThrow;
      long nextPull;
      long nextWall;
      long nextBarrage;
      long nextStep;
      long nextSlam;
      long nextLoose;
      /** Gravity Inversion: when he may cast it, and how long it still holds. */
      long nextInvert;
      int invertTicks;
      int slamCharge;
      Vec3 slamTarget;
      int wallTicks;
      Vec3 wallCentre;
      /** The arrival: ticks left climbing out of the ground. */
      int riseTicks = RISE_TICKS;
      /** The last tick anyone was in reach - he leaves once nobody has been for a while. */
      long lastSeen;
      long nextFault;
      long nextHorizon;
      /** Ticks he stands still channelling the Event Horizon. */
      int channelTicks;
      final List<Strike> strikes = new ArrayList<>();
      final List<Held> held = new ArrayList<>();
      final List<Shot> shots = new ArrayList<>();
      final List<UUID> wallBlocks = new ArrayList<>();

      Fight(UUID bossId, UUID summoner, ServerBossEvent bar) {
         this.bossId = bossId;
         this.summoner = summoner;
         this.bar = bar;
      }
   }

   private static final Map<UUID, Fight> FIGHTS = new HashMap<>();

   private VoidShaperManager() {
   }

   // ------------------------------------------------------------------ public API

   public static boolean isVoidShaper(Entity entity) {
      return entity != null && entity.entityTags().contains(TAG);
   }

   public static boolean isShard(Entity entity) {
      return entity != null && entity.entityTags().contains(SHARD_TAG);
   }

   public static int activeCount() {
      return FIGHTS.size();
   }

   public static int shotCount(UUID bossId) {
      Fight fight = FIGHTS.get(bossId);
      return fight == null ? 0 : fight.shots.size();
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
         Safe.run("void shaper abandon", () -> shutDown(server, fight));
         ended++;
      }
      return ended;
   }

   public static void onServerStopping(MinecraftServer server) {
      for (Fight fight : new ArrayList<>(FIGHTS.values())) {
         Safe.run("void shaper shutdown", () -> shutDown(server, fight));
      }
      FIGHTS.clear();
      // Players' gripped and thrown blocks are display entities, and a display is saved with its
      // chunk: one left in the world at shutdown hung in mid-air there for good, a ghost block
      // nothing would ever tick or discard again.
      List<UUID> loose = new ArrayList<>(LIFTED.keySet());
      for (Loose l : LOOSE) {
         loose.add(l.displayId);
      }
      for (UUID id : loose) {
         Safe.run("void shaper loose block", () -> {
            Entity e = findEntity(server, id);
            if (e != null) {
               e.discard();
            }
         });
      }
      LIFTED.clear();
      LOOSE.clear();
      // Last, with nothing tracked: whatever is still loaded and tagged is not saved with its chunk.
      Safe.run("void shaper orphan blocks", () -> sweepOrphanedBlocks(server));
   }

   /**
    * A player's throw: a tear in the world where the block came loose, the void flaring out
    * behind it along the throw, and a brief tear standing across the line it leaves on.
    */
   private static void playerTearFx(ServerLevel level, Vec3 at, Vec3 dir) {
      Vec3 d = dir.lengthSqr() < 1.0E-6 ? new Vec3(0.0, 0.0, 1.0) : dir.normalize();
      Fx.shape(level, com.fortuneandfavors.net.FfVfx.RIFT, ParticleTypes.REVERSE_PORTAL, at, Vec3.ZERO, 0.8, 0.0, VOID);
      Fx.shape(level, com.fortuneandfavors.net.FfVfx.MUZZLE, ParticleTypes.REVERSE_PORTAL, at, d, 0.0, 0.0, VOID_LIGHT);
      Fx.tear(level, ParticleTypes.REVERSE_PORTAL, at, new Vec3(-d.z, 0.0, d.x), 1.0, 8, VOID_LIGHT);
   }

   /** Void Anchor right-click: throw the hook and he follows it up. */
   public static String useVoidAnchor(ServerPlayer player, ItemStack held) {
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
            return "Your Void Shaper is still out. Finish him first.";
         }
      }

      ServerLevel level = summoner.level();
      Mob boss = (Mob) EntityTypes.ENDERMAN.create(level, EntitySpawnReason.COMMAND);
      if (boss == null) {
         return "The ground didn't move. He didn't come.";
      }

      AttributeInstance maxHp = boss.getAttribute(Attributes.MAX_HEALTH);
      if (maxHp != null) {
         maxHp.setBaseValue(MAX_HEALTH);
      }
      boss.setHealth((float) MAX_HEALTH);
      AttributeInstance dmg = boss.getAttribute(Attributes.ATTACK_DAMAGE);
      if (dmg != null) {
         dmg.setBaseValue(20.0);
      }
      AttributeInstance follow = boss.getAttribute(Attributes.FOLLOW_RANGE);
      if (follow != null) {
         follow.setBaseValue(ARENA_RADIUS);
      }
      AttributeInstance kb = boss.getAttribute(Attributes.KNOCKBACK_RESISTANCE);
      if (kb != null) {
         kb.setBaseValue(1.0);
      }
      // He is a colossus: bigger than the body he borrowed.
      AttributeInstance scale = boss.getAttribute(Attributes.SCALE);
      if (scale != null) {
         scale.setBaseValue(1.8);
      }

      boss.setPersistenceRequired();
      boss.setCustomName(Component.literal(BOSS_NAME));
      boss.setCustomNameVisible(true);
      boss.setNoAi(true);
      boss.addTag(TAG);
      // The shared marker plus the visible-and-persistent guarantee: see
      // BossManager.markBoss for why a boss has to say so itself.
      BossManager.markBoss(boss);
      // He comes up out of the ground a few blocks in front of whoever threw the anchor, not on
      // top of them: the arrival is something to watch, and a 1.8x enderman spawned on a player's
      // head was a fight that started inside its own summoner.
      Vec3 look = summoner.getViewVector(1.0F);
      Vec3 flat = new Vec3(look.x, 0.0, look.z);
      flat = flat.lengthSqr() < 0.01 ? new Vec3(1.0, 0.0, 0.0) : flat.normalize();
      double sx = summoner.getX() + flat.x * 5.0;
      double sz = summoner.getZ() + flat.z * 5.0;
      double sy = BossGrounding.groundY(level, sx, sz, summoner.getY() + 1.0);
      if (!BossGrounding.standable(level, sx, sy, sz)) {
         sx = summoner.getX();
         sz = summoner.getZ();
         sy = summoner.getY();
      }
      // Buried to the chest and untouchable until he is out: the rise is a set piece, not a
      // free three seconds of damage, and an entity inside a block would otherwise suffocate.
      boss.setInvulnerable(true);
      boss.setPos(sx, sy - RISE_DEPTH, sz);
      level.addFreshEntity(boss);

      ServerBossEvent bar = new ServerBossEvent(
         UUID.randomUUID(), Component.literal(BOSS_NAME), BossBarColor.PURPLE, BossBarOverlay.PROGRESS
      );
      bar.setVisible(true);
      // His own world only; the fight tick hands it to anyone who walks into reach later.
      for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
         if (p.level() == level) {
            bar.addPlayer(p);
         }
      }

      Fight fight = new Fight(boss.getUUID(), summoner.getUUID(), bar);
      fight.participants.add(summoner.getUUID());
      long now = ServerClock.clock(level);
      long arrive = now + RISE_TICKS;
      fight.lastSeen = now;
      fight.nextThrow = arrive + 40L;
      fight.nextPull = arrive + 260L;
      fight.nextWall = arrive + 400L;
      fight.nextBarrage = arrive + 320L;
      fight.nextStep = arrive + 200L;
      fight.nextSlam = arrive + 480L;
      fight.nextInvert = arrive + 780L;
      fight.nextFault = arrive + 140L;
      fight.nextHorizon = arrive + 600L;
      FIGHTS.put(boss.getUUID(), fight);

      announce(level, "\u00a75\u00a7m\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550");
      announce(level, "    \u00a75\u00a7l\ud83d\udd2e THE VOID SHAPER RISES \ud83d\udd2e");
      announce(level, "    \u00a77The ground is his now.");
      announce(level, "    \u00a78Watch what he holds. \u00a77The block decides the hit.");
      announce(level, "\u00a75\u00a7m\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550");

      // The ground tears open before anything comes out of it: a rift in the floor, a turning
      // ring of runes round it, the void showing through the middle, and a slow column of
      // violet winding up out of the hole for as long as he climbs.
      Vec3 hole = new Vec3(sx, sy, sz);
      Fx.rift(level, ParticleTypes.REVERSE_PORTAL, hole.add(0.0, 0.1, 0.0), 3.0, VOID);
      Fx.runeCircle(level, ParticleTypes.PORTAL, hole.add(0.0, 0.05, 0.0), 6.0, RISE_TICKS + 10, VOID);
      wormhole(level, hole.add(0.0, 0.15, 0.0), true, VOID_DARK);
      // The void showing through: dark motes drawn down into the hole for the whole climb.
      Fx.voidCollapse(level, ParticleTypes.REVERSE_PORTAL, hole, 6.0, RISE_TICKS, VOID_DARK);
      Fx.spiral(level, ParticleTypes.REVERSE_PORTAL, hole, 9.0, RISE_TICKS, VOID_LIGHT);
      Fx.vortex(level, ParticleTypes.PORTAL, hole.add(0.0, 0.2, 0.0), 4.5, RISE_TICKS, VOID_DARK);
      level.playSound(null, sx, sy, sz, ModSounds.BOSS_SPAWN, SoundSource.HOSTILE, 1.5F, 0.7F);
      level.playSound(null, sx, sy, sz, SoundEvents.WARDEN_EMERGE, SoundSource.HOSTILE, 1.8F, 0.7F);
      level.playSound(null, sx, sy, sz, SoundEvents.DEEPSLATE_BREAK, SoundSource.HOSTILE, 1.8F, 0.6F);
      Advancements.grant(summoner, "summon_voidshaper");
      return null;
   }

   // ------------------------------------------------------------------------ tick

   public static void tick(MinecraftServer server) {
      if (!LOOT_PAID.isEmpty()) {
         LOOT_PAID.removeIf(id -> findEntity(server, id) == null);
      }
      // Server ticks, not a level's game time: this only spaces the sweep out.
      if (server.getTickCount() % ORPHAN_SWEEP_TICKS == ORPHAN_SWEEP_OFFSET) {
         Safe.run("void shaper orphan blocks", () -> sweepOrphanedBlocks(server));
      }
      if (FIGHTS.isEmpty()) {
         return;
      }
      long now = ServerClock.clock(server.overworld());
      for (Fight fight : new ArrayList<>(FIGHTS.values())) {
         Safe.run("void shaper tick", () -> tickFight(server, fight, now));
      }
   }

   private static void tickFight(MinecraftServer server, Fight fight, long serverNow) {
      Mob boss = bossOf(server, fight);
      if (boss == null) {
         shutDown(server, fight);
         return;
      }
      ServerLevel level = (ServerLevel) boss.level();
      // His own world's clock, not the overworld's: every timer below was stamped from it.
      long now = ServerClock.clock(level);

      for (ServerPlayer p : server.getPlayerList().getPlayers()) {
         if (p.level() != level) {
            // Through a portal: the bar does not follow them into another world.
            fight.bar.removePlayer(p);
            continue;
         }
         if (isTarget(p, level) && p.distanceToSqr(boss) < ARENA_RADIUS * ARENA_RADIUS) {
            fight.participants.add(p.getUUID());
            fight.bar.addPlayer(p);
         }
      }

      tickHeld(level, boss, fight, now);
      tickShots(level, boss, fight);
      tickWall(level, boss, fight);

      if (fight.dying) {
         tickDeath(server, boss, fight);
         return;
      }

      fight.bar.setProgress(Math.max(0.0F, Math.min(1.0F, boss.getHealth() / boss.getMaxHealth())));
      fight.bar.setName(Component.literal(barName(fight)));
      fight.bar.setColor(fight.phase >= 2 ? BossBarColor.RED : BossBarColor.PURPLE);

      if (fight.riseTicks > 0) {
         tickRise(level, boss, fight);
         return;
      }

      // Nobody in reach for half a minute: he goes back into the ground and the fight is over,
      // instead of standing in an empty field forever holding the summoner's one boss slot.
      if (nearestPlayer(boss, ARENA_RADIUS) != null) {
         fight.lastSeen = now;
      } else if (now - fight.lastSeen > LONELY_TICKS) {
         wormhole(level, boss.position().add(0.0, 0.2, 0.0), false, VOID_DARK);
         Fx.rift(level, ParticleTypes.REVERSE_PORTAL, boss.position().add(0.0, 0.1, 0.0), 2.4, VOID);
         Fx.voidCollapse(level, ParticleTypes.REVERSE_PORTAL, boss.position(), 4.0, 16, VOID_DARK);
         level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.WARDEN_DIG, SoundSource.HOSTILE, 1.4F, 0.8F);
         announce(level, "\u00a78The ground settles. \u00a77The Void Shaper is gone.");
         shutDown(server, fight);
         return;
      }

      float share = boss.getHealth() / boss.getMaxHealth();
      if (share <= 0.4F && fight.phase < 2) {
         enterPhase(level, boss, fight, 2);
      }

      // Marked blows land on their own clock, whatever he is doing when they come due.
      tickStrikes(level, boss, fight, now);

      if (fight.channelTicks > 0) {
         tickChannel(level, boss, fight);
         return;
      }

      if (fight.invertTicks > 0) {
         tickInvert(level, boss, fight);
         return;
      }

      if (fight.slamCharge > 0) {
         tickSlam(level, boss, fight);
         return;
      }

      // He closes the gap himself. A no-AI puppet never runs its goals, so a
      // boss who could not walk would just be a turret in the middle of a field.
      advance(level, boss);
      // ...and he stays on the ground doing it. The slam lifts him twelve blocks
      // up and the walk only descends a block at a time *while he is closing the
      // gap* - so a player who stood underneath him after a slam left him parked
      // in the sky for the rest of the fight.
      BossGrounding.clampToGround(level, boss, 1.5);

      chooseMove(level, boss, fight, now);
   }

   /**
    * The arrival. He climbs out of the hole a few centimetres a tick while the floor round it
    * keeps breaking, and on the last tick the rift flares, the light bursts outward in spokes
    * and a shockwave rolls across the ground. Then the first blocks come up into his hands and
    * the fight starts - nobody is hit by any of it.
    */
   private static void tickRise(ServerLevel level, Mob boss, Fight fight) {
      fight.riseTicks--;
      boss.setPos(boss.getX(), boss.getY() + RISE_DEPTH / RISE_TICKS, boss.getZ());
      boss.setDeltaMovement(Vec3.ZERO);
      boss.hurtMarked = true;
      if (fight.riseTicks % 10 == 0) {
         // The floor round the hole keeps giving way as he pushes up through it.
         double a = RANDOM.nextDouble() * Math.PI * 2.0;
         double r = 2.0 + RANDOM.nextDouble() * 2.5;
         double x = boss.getX() + Math.cos(a) * r;
         double z = boss.getZ() + Math.sin(a) * r;
         double y = BossGrounding.groundY(level, x, z, boss.getY() + RISE_DEPTH);
         Fx.rockburst(level, groundDust(level, x, y, z), new Vec3(x, y + 0.2, z), 1.4, VOID_DARK);
         level.playSound(null, x, y, z, SoundEvents.DEEPSLATE_BREAK, SoundSource.HOSTILE, 1.2F, 0.6F + RANDOM.nextFloat() * 0.3F);
      }
      if (fight.riseTicks == 30) {
         level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.ENDERMAN_AMBIENT, SoundSource.HOSTILE, 1.6F, 0.4F);
      }
      if (fight.riseTicks > 0) {
         return;
      }
      boss.setInvulnerable(false);
      Vec3 at = boss.position().add(0.0, 2.6, 0.0);
      Fx.flare(level, ParticleTypes.END_ROD, at, 3.0, VOID_LIGHT);
      Fx.starburst(level, ParticleTypes.REVERSE_PORTAL, at, 8.0, VOID);
      Fx.shockwave(level, ParticleTypes.REVERSE_PORTAL, boss.position().add(0.0, 0.1, 0.0), 12.0, VOID_DARK);
      Fx.rockburst(level, groundDust(level, boss.getX(), boss.getY(), boss.getZ()), boss.position().add(0.0, 0.3, 0.0), 3.0, VOID_DARK);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.ENDERMAN_SCREAM, SoundSource.HOSTILE, 2.0F, 0.5F);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.GENERIC_EXPLODE, SoundSource.HOSTILE, 1.4F, 0.6F);
      grab(level, boss, fight, 3, HOLD_TICKS + 20);
      announceNear(level, boss, ARENA_RADIUS, SAY + "\"Mine.\"");
   }

   private static String barName(Fight fight) {
      String phase = fight.phase == 1 ? "Phase I" : "Phase II - UNSTABLE";
      return BOSS_NAME + " \u00a78| \u00a7f" + phase + " \u00a78| \u00a77" + fight.held.size() + " held";
   }

   // ---------------------------------------------------------------- the blocks

   /**
    * Classifies a block into the attack it becomes. This is the single place the
    * "the block decides the blow" rule lives, so the combat behaviour and the
    * announce text can never disagree about what a block does.
    */
   static Kind kindOf(BlockState state) {
      var block = state.getBlock();
      if (block == Blocks.MAGMA_BLOCK || block == Blocks.LAVA) {
         return new Kind("magma", MAGMA_DAMAGE, 0.85, 0.6, 80, 0, 1);
      }
      if (block == Blocks.ICE || block == Blocks.PACKED_ICE || block == Blocks.BLUE_ICE
         || block == Blocks.SNOW_BLOCK || block == Blocks.POWDER_SNOW) {
         return new Kind("ice", ICE_DAMAGE, 0.95, 0.5, 0, 120, 1);
      }
      // Only full-cube foliage reaches this point (see isGrabbable), so the
      // fragmentation list is leaf blocks rather than grass - grass is not a
      // full cube and can never be held or thrown.
      if (block == Blocks.OAK_LEAVES || block == Blocks.BIRCH_LEAVES || block == Blocks.SPRUCE_LEAVES
         || block == Blocks.JUNGLE_LEAVES || block == Blocks.ACACIA_LEAVES || block == Blocks.DARK_OAK_LEAVES
         || block == Blocks.MANGROVE_LEAVES || block == Blocks.CHERRY_LEAVES || block == Blocks.AZALEA_LEAVES
         || block == Blocks.FLOWERING_AZALEA_LEAVES) {
         return new Kind("leaves", LEAF_DAMAGE, 1.0, 0.2, 0, 0, 7);
      }
      String path = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
      if (path.endsWith("_ore") || path.endsWith("_log") || path.endsWith("_wood")) {
         if (path.endsWith("_ore")) {
            return new Kind("ore", ORE_DAMAGE, 1.7, 0.5, 0, 0, 1);
         }
         return new Kind("log", LOG_DAMAGE, 0.9, 2.4, 0, 0, 1);
      }
      if (path.contains("planks") || path.endsWith("_stem")) {
         return new Kind("log", LOG_DAMAGE * 0.9F, 0.95, 2.0, 0, 0, 1);
      }
      // Stone and everything else: heavy, slow, and it moves you a long way.
      return new Kind("stone", STONE_DAMAGE, 0.8, 1.9, 0, 0, 1);
   }

   /** Rips {@code count} blocks out of the terrain and holds them. */
   private static void grab(ServerLevel level, Mob boss, Fight fight, int count, int fuse) {
      int got = 0;
      for (int attempt = 0; attempt < 40 && got < count; attempt++) {
         double angle = RANDOM.nextDouble() * Math.PI * 2.0;
         int r = 2 + RANDOM.nextInt(GRAB_RANGE);
         BlockPos pos = BlockPos.containing(
            boss.getX() + Math.cos(angle) * r,
            boss.getY() + (RANDOM.nextInt(5) - 2),
            boss.getZ() + Math.sin(angle) * r
         );
         BlockState state = level.getBlockState(pos);
         // Never take the floor out from under the fight: the block must be a
         // real, full block and must have ground beneath it, or he would simply
         // delete the arena he is standing on.
         if (!isGrabbable(level, pos)) {
            continue;
         }
         if (spawnHeld(level, boss, fight, state, pos, angle, 2.2 + RANDOM.nextDouble() * 1.6, 1.6 + RANDOM.nextDouble() * 1.4, fuse)) {
            got++;
         }
      }
      if (got > 0) {
         level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.DEEPSLATE_BREAK, SoundSource.HOSTILE, 1.4F, 0.7F);
      }
      if (got >= 3) {
         // A big grab is seen as one: the void folds in on him and throws a ring back out as
         // the blocks come up.
         Fx.voidCollapse(level, ParticleTypes.REVERSE_PORTAL, boss.position().add(0.0, 1.0, 0.0), GRAB_RANGE * 0.6, 14, VOID);
      }
   }

   /**
    * Terrain only: no air, no fluids, no block entities, and it has to be a full
    * collision cube.
    *
    * <p>The test is the <em>collision</em> shape, not {@code isSolidRender()}:
    * leaves are a full cube you can stand on but are not solid-rendered, so a
    * solid-render test silently excluded every leaf from the fight - which would
    * have made the documented "leaves shatter into fragments" behaviour dead code
    * that no player could ever see.
    */
   public static boolean isGrabbable(ServerLevel level, BlockPos pos) {
      BlockState state = level.getBlockState(pos);
      if (state.isAir() || !state.getFluidState().isEmpty()) {
         return false;
      }
      if (state.hasBlockEntity()) {
         return false;
      }
      return state.isCollisionShapeFullBlock(level, pos);
   }

   private static boolean spawnHeld(
      ServerLevel level, Mob boss, Fight fight, BlockState state, BlockPos socket, double angle, double radius, double height, int fuse
   ) {
      if (fight.held.size() >= 10) {
         return false;
      }
      Display.BlockDisplay display = (Display.BlockDisplay) EntityTypes.BLOCK_DISPLAY.create(level, EntitySpawnReason.COMMAND);
      if (display == null) {
         return false;
      }
      display.setBlockState(state);
      display.addTag(HOLD_TAG);
      display.setPos(
         boss.getX() + Math.cos(angle) * radius,
         boss.getY() + height,
         boss.getZ() + Math.sin(angle) * radius
      );
      level.addFreshEntity(display);
      fight.held.add(new Held(display.getUUID(), kindOf(state), angle, radius, height, fuse));
      // The block is torn out of its socket and drawn up into his orbit along a weaving stream
      // of void, so every block he holds is seen leaving the ground it came from.
      Vec3 hole = Vec3.atCenterOf(socket);
      Fx.rift(level, ParticleTypes.REVERSE_PORTAL, hole, 0.7, VOID);
      Fx.soulStream(level, ParticleTypes.REVERSE_PORTAL, hole, display.position(), 0.5, 12, VOID_LIGHT);
      return true;
   }

   /** Orbits what he is holding, then throws it when the fuse runs out. */
   private static void tickHeld(ServerLevel level, Mob boss, Fight fight, long now) {
      if (fight.held.isEmpty()) {
         return;
      }
      for (Iterator<Held> it = fight.held.iterator(); it.hasNext();) {
         Held held = it.next();
         Entity display = findEntity(level.getServer(), held.displayId);
         if (display == null) {
            it.remove();
            continue;
         }
         double spin = now * 0.14 + held.angle;
         double x = boss.getX() + Math.cos(spin) * held.radius;
         double y = boss.getY() + held.height + Math.sin(spin * 0.7) * 0.25;
         double z = boss.getZ() + Math.sin(spin) * held.radius;
         display.setPos(x, y, z);
         display.hurtMarked = true;
         // The void keeps hold of it: modded clients see dark motes drawn into the block every
         // half second (one cue that animates itself), everyone else a portal mote now and then.
         if (Math.floorMod(now + held.displayId.hashCode(), 10L) == 0L) {
            com.fortuneandfavors.net.FfVfx.shape(level, FxKinds.VOID_COLLAPSE, ParticleTypes.REVERSE_PORTAL, new Vec3(x, y - 1.0, z), Vec3.ZERO, 0.9, 10.0, VOID);
         }
         if ((now & 1L) == 0L) {
            Fx.vanilla(level, ParticleTypes.PORTAL, x, y, z, 1, 0.1, 0.1, 0.1, 0.0);
         }
         if (fight.dying) {
            // The ceremony takes them down one at a time: nothing leaves his hands as a throw.
            continue;
         }
         held.fuse--;
         if (!held.told && held.fuse <= 6) {
            // The tell: the block flashes its own colour a beat before it flies, so the
            // "what is he holding" read has a moment attached to it.
            held.told = true;
            Fx.flare(level, ParticleTypes.END_ROD, new Vec3(x, y + 0.5, z), 0.9, kindColor(held.kind));
            level.playSound(null, x, y, z, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.HOSTILE, 1.0F, 0.6F);
         }

         if (held.fuse > 0) {
            continue;
         }
         it.remove();
         ServerPlayer target = nearestPlayer(boss, ARENA_RADIUS);
         Vec3 dir;
         if (fight.phase >= 2 && !fight.held.isEmpty()) {
            // Phase two does not aim. That is the entire point of it.
            double a = RANDOM.nextDouble() * Math.PI * 2.0;
            dir = new Vec3(Math.cos(a), -0.05 + RANDOM.nextDouble() * 0.2, Math.sin(a)).normalize();
         } else if (target != null) {
            dir = target.position().add(0.0, target.getBbHeight() * 0.5, 0.0).subtract(display.position()).normalize();
         } else {
            double a = RANDOM.nextDouble() * Math.PI * 2.0;
            dir = new Vec3(Math.cos(a), 0.05, Math.sin(a)).normalize();
         }
         launch(level, fight, held, display.position(), dir);
      }
   }

   private static void launch(ServerLevel level, Fight fight, Held held, Vec3 from, Vec3 dir) {
      Vec3 vel = dir.scale(held.kind.speed);
      fight.shots.add(new Shot(held.displayId, from, vel, held.kind, 120));
      Fx.muzzle(level, ParticleTypes.REVERSE_PORTAL, from, dir, kindColor(held.kind));
      Fx.vanilla(level, ParticleTypes.PORTAL, from.x, from.y, from.z, 12, 0.3, 0.3, 0.3, 0.08);
      level.playSound(null, from.x, from.y, from.z, SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE, 0.8F, 1.6F);
   }

   /** Moves every block in flight, draws it, and resolves the impact. */
   private static void tickShots(ServerLevel level, Mob boss, Fight fight) {
      if (fight.shots.isEmpty()) {
         return;
      }
      for (Iterator<Shot> it = fight.shots.iterator(); it.hasNext();) {
         Shot shot = it.next();
         Entity display = findEntity(level.getServer(), shot.displayId);
         if (display == null) {
            it.remove();
            continue;
         }
         // The trail: a beam segment in the block's own colour over this tick's flight for modded
         // clients, every other tick, and one portal mote a tick for the rest. Fragments fly
         // without one. It used to be a vanilla mote on every sub-step, to everyone.
         if (!shot.fragment) {
            if ((shot.life & 1) == 0) {
               com.fortuneandfavors.net.FfVfx.shape(level, com.fortuneandfavors.net.FfVfx.BEAM, ParticleTypes.REVERSE_PORTAL, shot.pos, shot.pos.add(shot.vel.scale(2.0)), 0.0, 0.0, kindColor(shot.kind));
            }
            Fx.vanilla(level, ParticleTypes.PORTAL, shot.pos.x, shot.pos.y, shot.pos.z, 1, 0.05, 0.05, 0.05, 0.0);
         }
         int steps = shot.kind.speed >= 1.5 ? 5 : 3;
         boolean done = false;
         for (int s = 0; s < steps && !done; s++) {
            shot.pos = shot.pos.add(shot.vel.scale(1.0 / steps));
            display.setPos(shot.pos.x, shot.pos.y, shot.pos.z);
            display.hurtMarked = true;

            if (!level.getBlockState(BlockPos.containing(shot.pos)).isAir()) {
               impact(level, boss, fight, shot, true);
               done = true;
               break;
            }
            for (ServerPlayer p : playersNear(level, shot.pos.x, shot.pos.y, shot.pos.z, 1.3)) {
               impact(level, boss, fight, shot, false);
               p.addEffect(new MobEffectInstance(MobEffects.GLOWING, 10, 0, false, false, false));
               done = true;
               break;
            }
         }
         shot.life--;
         if (done || shot.life <= 0) {
            Entity gone = findEntity(level.getServer(), shot.displayId);
            if (gone != null) {
               gone.discard();
            }
            it.remove();
         }
      }
   }

   private static void impact(ServerLevel level, Mob boss, Fight fight, Shot shot, boolean onTerrain) {
      Kind kind = shot.kind;
      double x = shot.pos.x;
      double y = shot.pos.y;
      double z = shot.pos.z;
      Vec3 at = new Vec3(x, y + 0.2, z);
      if (shot.fragment) {
         // A leaf fragment: a puff and a scratch, not a second explosion.
         Fx.shatter(level, ParticleTypes.CHERRY_LEAVES, at, 0.3, kindColor(kind));
      } else {
         impactFx(level, kind, at, shot.vel);
         Fx.vanillaOnly(() -> {
            com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.EXPLOSION, x, y + 0.2, z, 1, 0.0, 0.0, 0.0, 0.0);
            com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.LARGE_SMOKE, x, y + 0.3, z, 8, 0.4, 0.3, 0.4, 0.05);
            com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.CRIT, x, y + 0.2, z, 6, 0.3, 0.3, 0.3, 0.06);
         });
      }
      level.playSound(null, x, y, z, onTerrain ? SoundEvents.STONE_BREAK : SoundEvents.STONE_HIT, SoundSource.HOSTILE, shot.fragment ? 0.6F : 1.2F, 1.0F);

      List<ServerPlayer> hit = playersNear(level, x, y, z, shot.fragment ? 1.4 : 2.0);
      for (ServerPlayer p : hit) {
         p.hurtServer(level, level.damageSources().mobAttack(boss), kind.damage);
         if (kind.knockback > 0.0) {
            Vec3 away = p.position().subtract(x, y, z);
            if (away.lengthSqr() < 0.01) {
               away = shot.vel;
            }
            away = new Vec3(away.x, 0.0, away.z);
            away = away.lengthSqr() < 0.0001 ? new Vec3(1.0, 0.0, 0.0) : away.normalize();
            p.push(
               away.x * scaledKnockback(kind.knockback),
               0.35 + scaledKnockback(kind.knockback) * 0.15,
               away.z * scaledKnockback(kind.knockback)
            );
            p.hurtMarked = true;
         }
         if (kind.fireTicks > 0) {
            p.igniteForTicks(kind.fireTicks);
         }
         if (kind.slownessTicks > 0) {
            p.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, kind.slownessTicks, 1, false, true, true));
         }
      }

      // Leaves do not hit like a rock - they spray. Only the block itself sprays: see
      // Shot.fragment for the snowball this used to be.
      if (kind.fragments > 1 && !shot.fragment) {
         // Spawned a little back along the path, not at the impact point: on a terrain hit
         // that point is inside the block, and a fragment born inside a block "hit" it on
         // its first step and vanished.
         Vec3 back = shot.vel.lengthSqr() < 0.0001 ? Vec3.ZERO : shot.vel.normalize().scale(0.6);
         Vec3 frag = new Vec3(x, y + 0.2, z).subtract(back);
         for (int i = 0; i < kind.fragments; i++) {
            double a = RANDOM.nextDouble() * Math.PI * 2.0;
            Vec3 dir = new Vec3(Math.cos(a), 0.05, Math.sin(a)).normalize();
            UUID id = spawnFragment(level, frag);
            if (id != null) {
               fight.shots.add(new Shot(id, frag, dir.scale(0.7), kind, 30, true));
            }
         }
         Fx.vanilla(level, ParticleTypes.CHERRY_LEAVES, x, y + 0.3, z, 12, 0.6, 0.4, 0.6, 0.06);
      }
   }

   /**
    * What a landing block looks like, by kind: stone and deepslate burst into rubble, magma
    * splashes fire, ice shatters into frost, leaves scatter, an ore cracks with a bright
    * clash, and a log lands with a flat shockwave. Same table as the damage, so the hit you
    * see is the hit you took.
    */
   private static void impactFx(ServerLevel level, Kind kind, Vec3 at, Vec3 vel) {
      int color = kindColor(kind);
      switch (kind.id) {
         case "magma" -> {
            Fx.gooSplash(level, ParticleTypes.LAVA, at, 1.6, color);
            Fx.nova(level, ParticleTypes.FLAME, at, 2.0, color);
         }
         case "ice" -> Fx.iceBurst(level, ParticleTypes.SNOWFLAKE, at, 1.8, color);
         case "leaves" -> Fx.shatter(level, ParticleTypes.CHERRY_LEAVES, at, 1.2, color);
         case "ore" -> {
            Vec3 facing = vel.lengthSqr() < 0.0001 ? new Vec3(1.0, 0.0, 0.0) : vel.normalize();
            Fx.clash(level, ParticleTypes.ELECTRIC_SPARK, at, facing, color);
            Fx.starburst(level, ParticleTypes.CRIT, at, 2.2, color);
         }
         case "log" -> Fx.shockwave(level, ParticleTypes.CLOUD, at.add(0.0, -0.1, 0.0), 3.0, color);
         default -> Fx.rockburst(level, ParticleTypes.CLOUD, at, 1.8, color);
      }
      Fx.shatter(level, ParticleTypes.REVERSE_PORTAL, at, 0.8, VOID);
   }

   /** Each kind's colour, for the flash before the throw and the burst when it lands. */
   private static int kindColor(Kind kind) {
      return switch (kind.id) {
         case "magma" -> 0xFF6A1A;
         case "ice" -> 0xA8E4FF;
         case "leaves" -> 0x5FB043;
         case "ore" -> 0xF2E6A0;
         case "log" -> 0x8A6A3C;
         default -> 0x9C8AB8;
      };
   }

   /** Dust of whatever the floor is made of at this spot, so the rubble matches the ground. */
   private static BlockParticleOption groundDust(ServerLevel level, double x, double y, double z) {
      BlockState floor = level.getBlockState(BlockPos.containing(x, y - 0.5, z));
      if (floor.isAir() || !floor.getFluidState().isEmpty()) {
         floor = Blocks.DEEPSLATE.defaultBlockState();
      }
      return new BlockParticleOption(ParticleTypes.BLOCK, floor);
   }

   /**
    * A leaf fragment's display, or null when one could not be made. It used to fall back to
    * the parent's own display - which was discarded the same tick, so the fragment either
    * vanished at once or, worse, kept a handle on an entity it did not own.
    */
   private static UUID spawnFragment(ServerLevel level, Vec3 at) {
      Display.BlockDisplay display = (Display.BlockDisplay) EntityTypes.BLOCK_DISPLAY.create(level, EntitySpawnReason.COMMAND);
      if (display == null) {
         return null;
      }
      display.setBlockState(Blocks.OAK_LEAVES.defaultBlockState());
      display.addTag(SHARD_TAG);
      display.setPos(at.x, at.y, at.z);
      level.addFreshEntity(display);
      return display.getUUID();
   }

   /** Walks him toward the nearest player, ignoring terrain rather than pathing. */
   private static void advance(ServerLevel level, Mob boss) {
      ServerPlayer target = nearestPlayer(boss, ARENA_RADIUS);
      if (target == null) {
         return;
      }
      Vec3 to = target.position().subtract(boss.position());
      double dist = to.length();
      if (dist < 3.5) {
         return;
      }
      Vec3 unit = to.scale(1.0 / dist);
      double step = 0.11;
      double x = boss.getX() + unit.x * step;
      double z = boss.getZ() + unit.z * step;
      // Step up or down one block to follow the ground rather than bury himself.
      double y = boss.getY();
      BlockPos here = BlockPos.containing(x, y, z);
      if (!level.getBlockState(here).isAir() && level.getBlockState(here.above()).isAir()) {
         y += 1.0;
      } else if (level.getBlockState(here).isAir() && level.getBlockState(here.below()).isAir()) {
         y -= 1.0;
      }
      boss.setPos(x, y, z);
      boss.hurtMarked = true;
      // Each stride drags the void with it: motes sucked into his footprints for modded
      // clients, a sculk soul for the rest - both a few times a second, not every tick.
      if (boss.tickCount % 12 == 0) {
         com.fortuneandfavors.net.FfVfx.shape(level, FxKinds.VOID_COLLAPSE, ParticleTypes.REVERSE_PORTAL, new Vec3(x, y - 1.3, z), Vec3.ZERO, 1.4, 10.0, VOID_DARK);
      }
      if (boss.tickCount % 3 == 0) {
         Fx.vanilla(level, ParticleTypes.SCULK_SOUL, boss.getX(), boss.getY() + 0.2, boss.getZ(), 1, 0.2, 0.1, 0.2, 0.0);
      }
   }

   // --------------------------------------------------------------------- wall

   private static void startWall(ServerLevel level, Mob boss, Fight fight, ServerPlayer target) {
      Vec3 to = target.position().subtract(boss.position());
      Vec3 flat = new Vec3(to.x, 0.0, to.z);
      if (flat.lengthSqr() < 0.01) {
         flat = new Vec3(1.0, 0.0, 0.0);
      }
      flat = flat.normalize();
      Vec3 side = new Vec3(-flat.z, 0.0, flat.x);
      Vec3 centre = boss.position().add(flat.scale(5.0));
      int blocks = 0;
      for (int row = 0; row < 3; row++) {
         for (int col = -2; col <= 2; col++) {
            Vec3 at = centre.add(side.scale(col)).add(0.0, row, 0.0);
            Display.BlockDisplay display = (Display.BlockDisplay) EntityTypes.BLOCK_DISPLAY.create(level, EntitySpawnReason.COMMAND);
            if (display == null) {
               continue;
            }
            display.setBlockState(Blocks.DEEPSLATE.defaultBlockState());
            display.addTag(HOLD_TAG);
            display.setPos(at.x, at.y, at.z);
            level.addFreshEntity(display);
            fight.wallBlocks.add(display.getUUID());
            blocks++;
         }
      }
      fight.wallTicks = 120;
      fight.wallCentre = centre.add(0.0, 1.0, 0.0);
      // The wall comes up out of a tear along its own base, rubble first, and void smoulders up
      // its face for as long as it stands - one self-timed cue per column, for modded clients.
      Fx.tear(level, ParticleTypes.REVERSE_PORTAL, centre.add(0.0, 0.2, 0.0), side, 5.0, 20, VOID);
      for (int col = -2; col <= 2; col++) {
         com.fortuneandfavors.net.FfVfx.shape(level, FxKinds.AURA, ParticleTypes.REVERSE_PORTAL, centre.add(side.scale(col)), Vec3.ZERO, 3.0, fight.wallTicks, VOID);
      }
      Fx.rockburst(level, groundDust(level, centre.x, centre.y, centre.z), centre.add(0.0, 0.5, 0.0), 2.6, VOID_DARK);
      level.playSound(null, centre.x, centre.y, centre.z, SoundEvents.DEEPSLATE_PLACE, SoundSource.HOSTILE, 1.8F, 0.6F);
      if (blocks > 0) {
         announceNear(level, boss, ARENA_RADIUS, "\u00a75\u00a7lBLOCK WALL \u00a78- \u00a77it shoves. Go round it.");
      }
   }

   /** A wall is a real obstacle: it is a wall of shoving, not of geometry. */
   private static void tickWall(ServerLevel level, Mob boss, Fight fight) {
      if (fight.wallBlocks.isEmpty()) {
         return;
      }
      if (fight.wallTicks > 0) {
         fight.wallTicks--;
      }
      for (UUID id : new ArrayList<>(fight.wallBlocks)) {
         Entity display = findEntity(level.getServer(), id);
         if (display == null) {
            fight.wallBlocks.remove(id);
            continue;
         }
         if (fight.wallTicks % 8 == 0) {
            Fx.vanilla(level, ParticleTypes.SCULK_SOUL, display.getX(), display.getY() + 0.5, display.getZ(), 1, 0.1, 0.1, 0.1, 0.0);
         }
         for (ServerPlayer p : playersNear(level, display.getX(), display.getY(), display.getZ(), 1.4)) {
            Vec3 away = p.position().subtract(display.position());
            away = new Vec3(away.x, 0.0, away.z);
            away = away.lengthSqr() < 0.0001 ? new Vec3(1.0, 0.0, 0.0) : away.normalize();
            // Set, not added: a push() every tick from every block in reach stacked up into a
            // launch, so brushing a 15-block wall threw a player halfway across the arena.
            Vec3 v = p.getDeltaMovement();
            p.setDeltaMovement(away.x * 0.7, Math.max(v.y, 0.25), away.z * 0.7);
            p.hurtMarked = true;
         }
         if (fight.wallTicks <= 0) {
            // The shatter below draws the collapse for modded clients.
            Fx.vanilla(level, ParticleTypes.LARGE_SMOKE, display.getX(), display.getY() + 0.4, display.getZ(), 4, 0.3, 0.3, 0.3, 0.05);
            display.discard();
         }
      }
      if (fight.wallTicks <= 0) {
         fight.wallBlocks.clear();
         if (fight.wallCentre != null) {
            Fx.shatter(level, new BlockParticleOption(ParticleTypes.BLOCK, Blocks.DEEPSLATE.defaultBlockState()), fight.wallCentre, 2.4, VOID_DARK);
            level.playSound(null, fight.wallCentre.x, fight.wallCentre.y, fight.wallCentre.z, SoundEvents.DEEPSLATE_BREAK, SoundSource.HOSTILE, 1.4F, 0.7F);
            fight.wallCentre = null;
         }
      }
   }

   // --------------------------------------------------------------------- moves

   private static void chooseMove(ServerLevel level, Mob boss, Fight fight, long now) {
      if (fight.phase >= 2 && now >= fight.nextInvert) {
         fight.nextInvert = now + INVERT_COOLDOWN;
         startInvert(level, boss, fight);
         return;
      }
      if (now >= fight.nextHorizon) {
         // A phase-two horizon comes round sooner: there is less fight left to wait for it.
         fight.nextHorizon = now + (fight.phase >= 2 ? HORIZON_COOLDOWN * 3L / 4L : HORIZON_COOLDOWN);
         startHorizon(level, boss, fight, now);
         return;
      }
      if (fight.phase >= 2 && now >= fight.nextLoose) {
         fight.nextLoose = now + 80L;
         looseControl(level, boss, fight);
         return;
      }
      if (now >= fight.nextSlam) {
         fight.nextSlam = now + SLAM_COOLDOWN;
         startSlam(level, boss, fight);
         return;
      }
      if (now >= fight.nextWall) {
         fight.nextWall = now + WALL_COOLDOWN;
         ServerPlayer target = nearestPlayer(boss, ARENA_RADIUS);
         if (target != null) {
            startWall(level, boss, fight, target);
         }
         return;
      }
      if (now >= fight.nextFault) {
         fight.nextFault = now + FAULT_COOLDOWN;
         ServerPlayer target = nearestPlayer(boss, ARENA_RADIUS);
         if (target != null) {
            startFault(level, boss, fight, target, now);
         }
         return;
      }
      if (now >= fight.nextPull) {
         fight.nextPull = now + PULL_COOLDOWN;
         enderPull(level, boss, fight);
         return;
      }
      if (now >= fight.nextBarrage) {
         fight.nextBarrage = now + BARRAGE_COOLDOWN;
         grab(level, boss, fight, 3 + RANDOM.nextInt(3), HOLD_TICKS);
         Fx.vortex(level, ParticleTypes.PORTAL, boss.position().add(0.0, 0.2, 0.0), 3.5, HOLD_TICKS, VOID_DARK);
         announceNear(level, boss, ARENA_RADIUS, "§5§lBARRAGE §8- §7read the blocks.");
         return;
      }
      if (now >= fight.nextStep) {
         fight.nextStep = now + STEP_COOLDOWN;
         enderStep(level, boss, fight);
         return;
      }
      if (now >= fight.nextThrow) {
         fight.nextThrow = now + THROW_COOLDOWN;
         if (fight.held.isEmpty()) {
            grab(level, boss, fight, 1, HOLD_TICKS);
         } else {
            // Shorten the fuses so a throw actually happens on the cadence.
            for (Held held : fight.held) {
               held.fuse = Math.min(held.fuse, 6);
            }
         }
      }
   }

   // --------------------------------------------------------------- marked blows

   /**
    * Fault Line: the ground splits toward you.
    *
    * <p>Runes light up in a line from his feet out along the floor toward the target, one
    * every few blocks, and a thin tear joins them so the line reads as one crack rather than
    * a scatter of circles. A second and a half later the crack bursts, node by node, rippling
    * away from him. Anyone standing on a node is thrown up and hurt; the answer is to step off
    * the line, or jump it as it reaches you. In phase two he cracks three lines at once, fanned
    * out, and the sidestep has to find the gap between them.
    */
   private static void startFault(ServerLevel level, Mob boss, Fight fight, ServerPlayer target, long now) {
      Vec3 to = target.position().subtract(boss.position());
      Vec3 flat = new Vec3(to.x, 0.0, to.z);
      flat = flat.lengthSqr() < 0.01 ? new Vec3(1.0, 0.0, 0.0) : flat.normalize();
      double[] spread = fight.phase >= 2 ? new double[]{-0.45, 0.0, 0.45} : new double[]{0.0};
      for (double turn : spread) {
         double cos = Math.cos(turn);
         double sin = Math.sin(turn);
         Vec3 dir = new Vec3(flat.x * cos - flat.z * sin, 0.0, flat.x * sin + flat.z * cos);
         Set<UUID> hit = new HashSet<>();
         Vec3 first = null;
         Vec3 last = null;
         for (int i = 1; i <= FAULT_NODES; i++) {
            double x = boss.getX() + dir.x * FAULT_SPACING * i;
            double z = boss.getZ() + dir.z * FAULT_SPACING * i;
            double y = BossGrounding.groundY(level, x, z, boss.getY() + 1.0);
            Vec3 node = new Vec3(x, y, z);
            long land = now + FAULT_WARN + i * 2L;
            fight.strikes.add(new Strike(Strike.FAULT, node, dir, land, FAULT_RADIUS, FAULT_DAMAGE, hit));
            Fx.runeCircle(level, ParticleTypes.PORTAL, node.add(0.0, 0.05, 0.0), FAULT_RADIUS, (int) (land - now), VOID);
            if (first == null) {
               first = node;
            }
            last = node;
         }
         if (first != null) {
            Fx.beam(level, ParticleTypes.REVERSE_PORTAL, first.add(0.0, 0.1, 0.0), last.add(0.0, 0.1, 0.0), VOID_LIGHT);
         }
      }
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.EVOKER_PREPARE_ATTACK, SoundSource.HOSTILE, 1.4F, 0.6F);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.WARDEN_DIG, SoundSource.HOSTILE, 1.2F, 0.8F);
      announceNear(level, boss, ARENA_RADIUS, SAY + "\"Split.\"");
      announceNear(level, boss, ARENA_RADIUS, "§8The ground cracks toward you. §7Step off the line.");
   }

   /**
    * Event Horizon: he stops, and a hole opens over his head.
    *
    * <p>The dome on the floor round him is the only safe ground. A rune ring traces its edge
    * so the boundary is never a guess, the hole swirls and grows over the two and a half
    * seconds, and it flares white just before it goes. When it does, everyone outside the
    * dome who can see it takes the hit and is thrown outward. So there are two answers and
    * the player picks one: run in to him (into the slam and the throws), or put something
    * solid between them and the hole. He does not walk while it charges, so the dome is
    * exactly where it was drawn.
    */
   private static void startHorizon(ServerLevel level, Mob boss, Fight fight, long now) {
      Vec3 anchor = new Vec3(boss.getX(), boss.getY(), boss.getZ());
      Vec3 eye = anchor.add(0.0, HORIZON_EYE, 0.0);
      fight.channelTicks = HORIZON_WARN;
      fight.strikes.add(new Strike(Strike.HORIZON, anchor, Vec3.ZERO, now + HORIZON_WARN, HORIZON_SAFE, HORIZON_DAMAGE, new HashSet<>()));
      // The hole itself: a wormhole opening (it was drawn sealing - the old helper's flag runs
      // backwards), a tear ripped across the sky through it for the whole charge, and the
      // rift at its heart.
      wormhole(level, eye, true, VOID_DARK);
      Fx.tear(level, ParticleTypes.REVERSE_PORTAL, eye, new Vec3(1.0, 0.0, 0.0), 3.5, HORIZON_WARN, VOID_LIGHT);
      Fx.rift(level, ParticleTypes.REVERSE_PORTAL, eye, 2.6, VOID);
      Fx.vortex(level, ParticleTypes.PORTAL, eye, 3.0, HORIZON_WARN, VOID);
      Fx.dome(level, ParticleTypes.END_ROD, anchor, HORIZON_SAFE, HORIZON_WARN, VOID_LIGHT);
      Fx.runeCircle(level, ParticleTypes.PORTAL, anchor.add(0.0, 0.05, 0.0), HORIZON_SAFE, HORIZON_WARN, VOID_LIGHT);
      Fx.resonance(level, ParticleTypes.REVERSE_PORTAL, eye, HORIZON_WARN, VOID);
      level.playSound(null, eye.x, eye.y, eye.z, SoundEvents.END_PORTAL_SPAWN, SoundSource.HOSTILE, 1.2F, 1.4F);
      level.playSound(null, eye.x, eye.y, eye.z, SoundEvents.BEACON_ACTIVATE, SoundSource.HOSTILE, 1.6F, 0.5F);
      announceNear(level, boss, ARENA_RADIUS, SAY + "\"Look up.\"");
      announceNear(level, boss, ARENA_RADIUS, "§8A hole opens over him. §7Get under it, or get behind something.");
   }

   /** He holds still while the horizon charges: the hole swirls, he does not walk or throw. */
   private static void tickChannel(ServerLevel level, Mob boss, Fight fight) {
      fight.channelTicks--;
      BossGrounding.clampToGround(level, boss, 1.5);
      // The vortex, tear and rift sent at the cast draw the hole for modded clients.
      if (fight.channelTicks % 6 == 0) {
         Fx.vanilla(level, ParticleTypes.REVERSE_PORTAL, boss.getX(), boss.getY() + HORIZON_EYE, boss.getZ(), 12, 1.2, 0.6, 1.2, 0.05);
      }
   }

   /** Lands every marked blow that has come due. */
   private static void tickStrikes(ServerLevel level, Mob boss, Fight fight, long now) {
      if (fight.strikes.isEmpty()) {
         return;
      }
      for (Iterator<Strike> it = fight.strikes.iterator(); it.hasNext();) {
         Strike s = it.next();
         long lead = s.kind == Strike.HORIZON ? 12L : 5L;
         if (!s.primed && now >= s.landAt - lead) {
            s.primed = true;
            if (s.kind == Strike.HORIZON) {
               // The last-second flash: the hole goes white before it goes off.
               Vec3 eye = s.at.add(0.0, HORIZON_EYE, 0.0);
               Fx.flare(level, ParticleTypes.END_ROD, eye, 2.0, VOID_LIGHT);
               // ...and draws everything round it in for the last half second.
               Fx.voidCollapse(level, ParticleTypes.REVERSE_PORTAL, eye, 9.0, (int) lead, VOID_DARK);
               level.playSound(null, eye.x, eye.y, eye.z, SoundEvents.WARDEN_SONIC_CHARGE, SoundSource.HOSTILE, 1.8F, 0.8F);
            } else {
               // The crack opens under the rune a moment before it bursts.
               Fx.tear(level, ParticleTypes.REVERSE_PORTAL, s.at.add(0.0, 0.1, 0.0), s.dir, 1.6, (int) lead, VOID_LIGHT);
            }
         }
         if (now < s.landAt) {
            continue;
         }
         it.remove();
         if (s.kind == Strike.HORIZON) {
            landHorizon(level, boss, s);
         } else {
            landFault(level, boss, s);
         }
      }
   }

   private static void landFault(ServerLevel level, Mob boss, Strike s) {
      Fx.geyser(level, ParticleTypes.REVERSE_PORTAL, s.at, 4.5, VOID);
      Fx.rockburst(level, groundDust(level, s.at.x, s.at.y, s.at.z), s.at.add(0.0, 0.3, 0.0), 1.6, VOID_DARK);
      level.playSound(null, s.at.x, s.at.y, s.at.z, SoundEvents.DEEPSLATE_BREAK, SoundSource.HOSTILE, 1.0F, 0.6F + RANDOM.nextFloat() * 0.3F);
      double r2 = s.radius * s.radius;
      for (ServerPlayer p : playersNear(level, s.at.x, s.at.y, s.at.z, s.radius + 3.0)) {
         double dx = p.getX() - s.at.x;
         double dz = p.getZ() - s.at.z;
         double dy = p.getY() - s.at.y;
         // A player who jumped as it reached them is above the burst, not in it.
         if (dx * dx + dz * dz > r2 || dy > 1.1 || dy < -1.5 || !s.hit.add(p.getUUID())) {
            continue;
         }
         p.hurtServer(level, level.damageSources().mobAttack(boss), s.damage);
         Vec3 v = p.getDeltaMovement();
         p.setDeltaMovement(v.x * 0.3, 0.75, v.z * 0.3);
         p.hurtMarked = true;
      }
   }

   private static void landHorizon(ServerLevel level, Mob boss, Strike s) {
      Vec3 eye = s.at.add(0.0, HORIZON_EYE, 0.0);
      Fx.starburst(level, ParticleTypes.END_ROD, eye, 10.0, VOID_LIGHT);
      Fx.nova(level, ParticleTypes.REVERSE_PORTAL, eye, 8.0, VOID);
      Fx.shockwave(level, ParticleTypes.REVERSE_PORTAL, s.at.add(0.0, 0.1, 0.0), 18.0, VOID_DARK);
      wormhole(level, eye, false, VOID_DARK);
      level.playSound(null, eye.x, eye.y, eye.z, SoundEvents.WARDEN_SONIC_BOOM, SoundSource.HOSTILE, 2.0F, 0.6F);
      level.playSound(null, eye.x, eye.y, eye.z, SoundEvents.GENERIC_EXPLODE, SoundSource.HOSTILE, 1.4F, 0.5F);
      double safe2 = s.radius * s.radius;
      for (ServerPlayer p : playersNear(level, eye.x, eye.y, eye.z, HORIZON_REACH)) {
         double dx = p.getX() - s.at.x;
         double dz = p.getZ() - s.at.z;
         if (dx * dx + dz * dz <= safe2) {
            continue;
         }
         // Line of sight from the hole to the player's eyes: anything solid in between is cover.
         Vec3 eyes = p.getEyePosition();
         if (level.clip(new ClipContext(eye, eyes, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p)).getType() != HitResult.Type.MISS) {
            continue;
         }
         p.hurtServer(level, level.damageSources().mobAttack(boss), s.damage);
         Vec3 away = new Vec3(dx, 0.0, dz);
         away = away.lengthSqr() < 0.0001 ? new Vec3(1.0, 0.0, 0.0) : away.normalize();
         p.push(away.x * 1.1, 0.45, away.z * 1.1);
         p.hurtMarked = true;
         Fx.lightning(level, ParticleTypes.END_ROD, eye, eyes.add(0.0, -0.4, 0.0), VOID_LIGHT);
      }
   }

   /**
    * Inverts the arena's gravity: everyone lifts, hangs, and is dropped.
    *
    * <p>Levitation rather than an impulse, because an impulse is a jump and this is
    * meant to be a loss of footing: they drift up, they stay up while the fight
    * continues around them, and the landing is the part that hurts. The effect is
    * refreshed every tick and then removed by this method rather than left to expire
    * on its own, so nobody is still floating after the move ends - and the anticheat
    * knows about levitation, so being lifted is not mistaken for a flight module.
    */
   private static void startInvert(ServerLevel level, Mob boss, Fight fight) {
      fight.invertTicks = INVERT_TICKS;
      announceNear(level, boss, ARENA_RADIUS, SAY + "\"Up.\"");
      announceNear(level, boss, ARENA_RADIUS, "§5§lGRAVITY INVERTED §8- §7you're going up. Water or slow falling for the drop.");
      // The whole arena turns over: a dome of dark sky over it, a column winding upward
      // through the middle, and a glow round every player he has lifted.
      Fx.dome(level, ParticleTypes.REVERSE_PORTAL, boss.position(), 20.0, INVERT_TICKS, VOID_DARK);
      Fx.spiral(level, ParticleTypes.REVERSE_PORTAL, boss.position(), 16.0, INVERT_TICKS, VOID_LIGHT);
      for (ServerPlayer p : participantsNear(level, boss, ARENA_RADIUS)) {
         Fx.aura(level, ParticleTypes.REVERSE_PORTAL, p.position(), 2.2, INVERT_TICKS, VOID_LIGHT);
      }
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.ENDER_DRAGON_FLAP, SoundSource.HOSTILE, 2.0F, 0.6F);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.RESPAWN_ANCHOR_DEPLETE, SoundSource.HOSTILE, 1.6F, 1.4F);
   }

   private static void tickInvert(ServerLevel level, Mob boss, Fight fight) {
      fight.invertTicks--;
      boolean release = fight.invertTicks <= 0;
      for (ServerPlayer p : participantsNear(level, boss, ARENA_RADIUS)) {
         if (release) {
            p.removeEffect(MobEffects.LEVITATION);
            // The drop: no fall-distance bookkeeping, just a hard shove down and a
            // flat hit, so the landing is the same for someone in leather and
            // someone in netherite and never compounds with the fall itself.
            p.push(0.0, -1.35, 0.0);
            p.hurtMarked = true;
            p.hurtServer(level, level.damageSources().mobAttack(boss), INVERT_SLAM_DAMAGE);
            Fx.shockwave(level, ParticleTypes.REVERSE_PORTAL, p.position().add(0.0, 0.1, 0.0), 2.5, VOID);
            continue;
         }
         fight.participants.add(p.getUUID());
         p.addEffect(new MobEffectInstance(MobEffects.LEVITATION, 12, 1, false, false, true));
         p.hurtMarked = true;
      }

      // Everything that falls, falls upward while it holds - the read for the move. The
      // geysers carry it for modded clients; the spray below is the vanilla version.
      double x = boss.getX();
      double y = boss.getY() + 1.0;
      double z = boss.getZ();
      if (fight.invertTicks % 8 == 0) {
         double a = RANDOM.nextDouble() * Math.PI * 2.0;
         double r = 4.0 + RANDOM.nextDouble() * 14.0;
         double gx = x + Math.cos(a) * r;
         double gz = z + Math.sin(a) * r;
         Fx.geyser(level, ParticleTypes.REVERSE_PORTAL, new Vec3(gx, BossGrounding.groundY(level, gx, gz, y), gz), 9.0, VOID_LIGHT);
      }
      // Every other tick and ten points: fifty-two packets every tick was 2,600 per cast for a
      // Bedrock player through Geyser.
      if ((fight.invertTicks & 1) == 0) {
         Fx.vanillaOnly(() -> {
            for (int i = 0; i < 10; i++) {
               double ox = (RANDOM.nextDouble() - 0.5) * 2.0 * ARENA_RADIUS * 0.5;
               double oz = (RANDOM.nextDouble() - 0.5) * 2.0 * ARENA_RADIUS * 0.5;
               com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.END_ROD, x + ox, y + RANDOM.nextDouble() * 2.0, z + oz, 1, 0.0, 0.9, 0.0, 0.25);
               com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.REVERSE_PORTAL, x + ox, y, z + oz, 1, 0.0, 1.2, 0.0, 0.3);
            }
         });
      }
      if (RANDOM.nextInt(6) == 0) {
         level.playSound(null, x, y, z, SoundEvents.ENDERMAN_AMBIENT, SoundSource.HOSTILE, 1.2F, 0.5F);
      }
      if (release) {
         Fx.nova(level, ParticleTypes.REVERSE_PORTAL, boss.position().add(0.0, 1.0, 0.0), 10.0, VOID);
         announceNear(level, boss, ARENA_RADIUS, SAY + "\"Down.\"");
         level.playSound(null, x, y, z, SoundEvents.GENERIC_EXPLODE, SoundSource.HOSTILE, 1.6F, 0.7F);
      }
   }

   /** Drags everyone in reach toward him along visible chains, into the throws and the slam. */
   private static void enderPull(ServerLevel level, Mob boss, Fight fight) {
      List<ServerPlayer> caught = participantsNear(level, boss, 26.0);
      if (caught.isEmpty()) {
         return;
      }
      Vec3 heart = boss.position().add(0.0, 1.0, 0.0);
      Fx.vortex(level, ParticleTypes.PORTAL, boss.position().add(0.0, 0.2, 0.0), 4.0, 20, VOID_DARK);
      for (ServerPlayer p : caught) {
         Vec3 pull = heart.subtract(p.position());
         double len = pull.length();
         if (len < 1.0) {
            continue;
         }
         Vec3 unit = pull.scale(1.0 / len);
         p.push(unit.x * 1.8, 0.4, unit.z * 1.8);
         p.hurtMarked = true;
         // The chain, and the void streaming back along it into him. The chain's own vanilla
         // version is the line clients without the mod see; the extra dotted line that used to
         // follow it reached modded clients as well.
         Fx.chains(level, ParticleTypes.REVERSE_PORTAL, heart, p.position().add(0.0, 1.0, 0.0), VOID_LIGHT);
         Fx.soulStream(level, ParticleTypes.REVERSE_PORTAL, p.position().add(0.0, 1.0, 0.0), heart, 0.6, 16, VOID);
      }
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.ENDERMAN_SCREAM, SoundSource.HOSTILE, 1.4F, 1.4F);
      announceNear(level, boss, ARENA_RADIUS, SAY + "\"Come here.\"");
   }

   /**
    * He folds space and steps out behind his target, holding two fresh blocks. The spot
    * behind them is checked first: a step that would have put him inside a hillside is
    * shortened to the target's own side, and refused outright if that is no better.
    */
   private static void enderStep(ServerLevel level, Mob boss, Fight fight) {
      ServerPlayer target = nearestPlayer(boss, ARENA_RADIUS);
      if (target == null) {
         return;
      }
      Vec3 look = target.getViewVector(1.0F);
      Vec3 back = new Vec3(look.x, 0.0, look.z);
      back = back.lengthSqr() < 0.01 ? new Vec3(1.0, 0.0, 0.0) : back.normalize();
      Vec3 dest = null;
      for (double d : new double[]{2.5, 1.5}) {
         double x = target.getX() - back.x * d;
         double z = target.getZ() - back.z * d;
         double y = BossGrounding.groundY(level, x, z, target.getY() + 1.0);
         if (BossGrounding.standable(level, x, y, z) && Math.abs(y - target.getY()) < 3.0) {
            dest = new Vec3(x, y, z);
            break;
         }
      }
      if (dest == null) {
         return;
      }
      Vec3 from = boss.position();
      // He goes into the dark where he stood and steps out of a portal torn open behind them,
      // facing their back. (Both wormholes used to be drawn the wrong way round.)
      wormhole(level, from.add(0.0, 1.5, 0.0), false, VOID_DARK);
      Fx.voidCollapse(level, ParticleTypes.REVERSE_PORTAL, from, 2.5, 10, VOID_DARK);
      boss.setPos(dest.x, dest.y, dest.z);
      boss.hurtMarked = true;
      Vec3 facing = new Vec3(target.getX() - dest.x, 0.0, target.getZ() - dest.z);
      Fx.riftPortal(level, ParticleTypes.REVERSE_PORTAL, dest.subtract(facing.lengthSqr() < 1.0E-4 ? Vec3.ZERO : facing.normalize().scale(0.6)), facing, 4.2, 8, VOID);
      Fx.tear(level, ParticleTypes.REVERSE_PORTAL, dest.add(0.0, 1.8, 0.0), new Vec3(0.0, 1.0, 0.0), 3.0, 10, VOID_LIGHT);
      Vec3 out = dest;
      Fx.vanillaOnly(() -> {
         com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.REVERSE_PORTAL, from.x, from.y + 1.5, from.z, 24, 0.6, 1.0, 0.6, 0.15);
         com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.REVERSE_PORTAL, out.x, out.y + 1.5, out.z, 24, 0.6, 1.0, 0.6, 0.15);
      });
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE, 1.5F, 0.8F);
      // He arrives holding something, always.
      grab(level, boss, fight, 2, 18);
      announceNear(level, boss, ARENA_RADIUS, SAY + "\"Behind you.\"");
   }

   /**
    * Void Slam: he blinks twelve blocks over the target, a rune ring marks where he will
    * land, and a little over a second later he comes straight down on it. Out of the ring
    * is the whole answer - the ring is drawn the size of the hit.
    */
   private static void startSlam(ServerLevel level, Mob boss, Fight fight) {
      ServerPlayer target = nearestPlayer(boss, ARENA_RADIUS);
      if (target == null) {
         return;
      }
      fight.slamTarget = target.position();
      fight.slamCharge = 26;
      wormhole(level, boss.position().add(0.0, 1.5, 0.0), false, VOID_DARK);
      boss.setPos(target.getX(), target.getY() + 12.0, target.getZ());
      boss.setDeltaMovement(Vec3.ZERO);
      boss.hurtMarked = true;
      Fx.runeCircle(level, ParticleTypes.PORTAL, fight.slamTarget.add(0.0, 0.05, 0.0), 5.5, 26, VOID);
      wormhole(level, boss.position().add(0.0, 1.5, 0.0), true, VOID);
      announceNear(level, boss, ARENA_RADIUS, "§5§lVOID SLAM §8- §7he's above you. Get out of the ring.");
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.ENDERMAN_SCREAM, SoundSource.HOSTILE, 1.6F, 0.7F);
   }

   private static void tickSlam(ServerLevel level, Mob boss, Fight fight) {
      if (fight.slamTarget == null) {
         fight.slamCharge = 0;
         return;
      }
      Vec3 target = fight.slamTarget;
      double r = 5.5;
      // The rune circle sent at the cast is the modded ring; this one is for everyone else.
      if (fight.slamCharge % 4 == 0) {
         Fx.vanillaOnly(() -> {
            for (int i = 0; i < 20; i++) {
               double a = i * (Math.PI * 2.0 / 20.0);
               com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.PORTAL, target.x + Math.cos(a) * r, target.y + 0.2, target.z + Math.sin(a) * r, 1, 0.0, 0.0, 0.0, 0.0);
            }
         });
      }
      fight.slamCharge--;
      if (fight.slamCharge == 6) {
         // The drop is seen, not just felt: a streak from where he hangs to where he lands.
         Fx.comet(level, ParticleTypes.REVERSE_PORTAL, boss.position().add(0.0, 1.5, 0.0), target.add(0.0, 0.3, 0.0), 6, VOID_LIGHT);
      }
      if (fight.slamCharge > 0) {
         return;
      }
      Fx.shockwave(level, ParticleTypes.REVERSE_PORTAL, target.add(0.0, 0.1, 0.0), 8.0, VOID);
      Fx.rockburst(level, groundDust(level, target.x, target.y, target.z), target.add(0.0, 0.3, 0.0), 3.2, VOID_DARK);
      Fx.starburst(level, ParticleTypes.END_ROD, target.add(0.0, 0.6, 0.0), 5.0, VOID_LIGHT);
      Fx.vanillaOnly(() -> {
         com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.EXPLOSION_EMITTER, target.x, target.y + 0.3, target.z, 2, 1.5, 0.5, 1.5, 0.0);
         com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.GUST, target.x, target.y + 0.3, target.z, 16, 4.0, 0.4, 4.0, 0.2);
         com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.REVERSE_PORTAL, target.x, target.y + 0.5, target.z, 24, 4.0, 0.8, 4.0, 0.2);
      });
      level.playSound(null, target.x, target.y, target.z, ModSounds.BOSS_SLAM, SoundSource.HOSTILE, 2.2F, 0.6F);
      for (ServerPlayer p : playersNear(level, target.x, target.y, target.z, 5.5)) {
         Vec3 away = p.position().subtract(target);
         away = new Vec3(away.x, 0.0, away.z);
         away = away.lengthSqr() < 0.0001 ? new Vec3(1.0, 0.0, 0.0) : away.normalize();
         p.push(away.x * 2.5, 1.0, away.z * 2.5);
         p.hurtMarked = true;
         p.hurtServer(level, level.damageSources().mobAttack(boss), 18.0F);
      }
      fight.slamCharge = 0;
      fight.slamTarget = null;
      // Land him on the floor he just hit, rather than leaving him hovering over
      // the crater for the tick loop to (maybe) walk down.
      boss.setPos(target.x, BossGrounding.groundY(level, target.x, target.z, target.y), target.z);
      boss.setDeltaMovement(Vec3.ZERO);
      boss.hurtMarked = true;
      grab(level, boss, fight, 2, 30);
   }

   // --------------------------------------------------------------------- phase

   private static void enterPhase(ServerLevel level, Mob boss, Fight fight, int phase) {
      fight.phase = phase;
      // He stops holding one thing. He holds everything.
      grab(level, boss, fight, 5 + RANDOM.nextInt(4), 60);
      announceNear(level, boss, ARENA_RADIUS, "§5§l⚠ THE VOID SHAPER HAS LOST CONTROL ⚠");
      announceNear(level, boss, ARENA_RADIUS, SAY + "\"Can't hold it. §5Won't try.\"");
      announceNear(level, boss, ARENA_RADIUS, "§8He's stopped aiming. §7Get behind something.");
      Vec3 heart = boss.position().add(0.0, 2.2, 0.0);
      Fx.flare(level, ParticleTypes.END_ROD, heart, 3.2, VOID_LIGHT);
      Fx.starburst(level, ParticleTypes.REVERSE_PORTAL, heart, 9.0, VOID);
      Fx.shockwave(level, ParticleTypes.REVERSE_PORTAL, boss.position().add(0.0, 0.1, 0.0), 14.0, VOID_DARK);
      Fx.spiral(level, ParticleTypes.REVERSE_PORTAL, boss.position(), 10.0, 40, VOID);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.ENDERMAN_SCREAM, SoundSource.HOSTILE, 2.0F, 0.6F);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.WARDEN_ROAR, SoundSource.HOSTILE, 1.2F, 1.3F);
   }

   /** Phase two's signature: every held block leaves at once, in random directions. */
   private static void looseControl(ServerLevel level, Mob boss, Fight fight) {
      if (fight.held.isEmpty()) {
         if (RANDOM.nextInt(3) == 0) {
            grab(level, boss, fight, 2 + RANDOM.nextInt(3), 30);
         }
         return;
      }
      for (Held held : fight.held) {
         held.fuse = Math.min(held.fuse, 1);
      }
      Fx.nova(level, ParticleTypes.REVERSE_PORTAL, boss.position().add(0.0, 2.0, 0.0), 6.0, VOID_LIGHT);
      Fx.vanilla(level, ParticleTypes.REVERSE_PORTAL, boss.getX(), boss.getY() + 2.0, boss.getZ(), 24, 1.5, 1.5, 1.5, 0.25);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.SHULKER_SHOOT, SoundSource.HOSTILE, 1.4F, 0.8F);
   }

   // His voice is a handful of words at a time. He is a thing that takes the world
   // apart, not a character with opinions about it, and a speech would only compete
   // with the one message the fight needs to deliver - which block is in his hands.
   // Where a mechanic needs explaining, a grey narrator line does it instead of him.

   // -------------------------------------------------------------- lethal blow

   public static Boolean onLethalDamage(Entity entity, float amount) {
      if (!isVoidShaper(entity) || !(entity instanceof Mob boss) || !(boss.level() instanceof ServerLevel level)) {
         return null;
      }
      Fight fight = FIGHTS.get(boss.getUUID());
      if (fight == null) {
         return null;
      }
      if (fight.dying) {
         return Boolean.FALSE;
      }
      if (boss.getHealth() - amount > 0.5F) {
         return null;
      }
      fight.dying = true;
      fight.deathTicks = DEATH_CEREMONY_TICKS;
      boss.setHealth(1.0F);
      boss.setNoAi(true);
      fight.bar.setProgress(0.0F);
      // Everything still pending is called off: no rune lands, no hole goes off and nobody is
      // left floating once he is beaten.
      fight.strikes.clear();
      // A blow that bypasses invulnerability (the void, /kill) can land mid-rise: the final
      // generic hit of the ceremony must still be able to kill him.
      fight.riseTicks = 0;
      boss.setInvulnerable(false);
      fight.channelTicks = 0;
      fight.slamCharge = 0;
      fight.slamTarget = null;
      if (fight.invertTicks > 0) {
         fight.invertTicks = 0;
         for (ServerPlayer p : participantsNear(level, boss, ARENA_RADIUS)) {
            p.removeEffect(MobEffects.LEVITATION);
         }
      }
      // Blocks in flight burst where they are rather than finish their throw.
      for (Shot shot : new ArrayList<>(fight.shots)) {
         Entity display = findEntity(level.getServer(), shot.displayId);
         if (display != null) {
            if (!shot.fragment) {
               Fx.shatter(level, ParticleTypes.REVERSE_PORTAL, display.position(), 0.8, VOID);
            }
            display.discard();
         }
      }
      fight.shots.clear();
      // Back on the floor for the ceremony, wherever the blow caught him (a slam leaves him
      // twelve blocks up).
      boss.setPos(boss.getX(), BossGrounding.groundY(level, boss.getX(), boss.getZ(), boss.getY()), boss.getZ());
      boss.setDeltaMovement(Vec3.ZERO);
      boss.hurtMarked = true;
      Vec3 heart = boss.position().add(0.0, 2.2, 0.0);
      Fx.flare(level, ParticleTypes.END_ROD, heart, 2.4, VOID_LIGHT);
      Fx.spiral(level, ParticleTypes.REVERSE_PORTAL, boss.position(), 10.0, DEATH_CEREMONY_TICKS, VOID_LIGHT);
      Fx.vortex(level, ParticleTypes.PORTAL, boss.position().add(0.0, 0.2, 0.0), 6.0, DEATH_CEREMONY_TICKS, VOID_DARK);
      Fx.aura(level, ParticleTypes.REVERSE_PORTAL, boss.position(), 5.5, DEATH_CEREMONY_TICKS, VOID);
      // The void he borrowed is called back: dark motes drawn in from the whole arena for the
      // length of the ceremony, timed so the ring they throw out is the moment he folds.
      Fx.voidCollapse(level, ParticleTypes.REVERSE_PORTAL, boss.position(), 14.0, DEATH_CEREMONY_TICKS, VOID_DARK);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.BEACON_DEACTIVATE, SoundSource.HOSTILE, 2.0F, 0.6F);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.ENDERMAN_HURT, SoundSource.HOSTILE, 1.8F, 0.5F);
      announceNear(level, boss, ARENA_RADIUS, SAY + "\"No. §5Not yet-\"");
      // FALSE cancels the blow so the death ceremony - and its loot - runs.
      return Boolean.FALSE;
   }

   /**
    * The ceremony: he loses his grip on everything at once.
    *
    * <p>Five seconds. He lifts off the ground a little at a time while the blocks he is still
    * holding drop out of orbit one by one, each falling back to the floor it came from. Rings
    * of void close in on him, faster and tighter as it goes, and the cracks in the air round
    * him get wider. At the end he folds in on himself: a flare, spokes of light, a shockwave
    * across the arena, and the ground he took for the fight comes back down out of the sky.
    */
   private static void tickDeath(MinecraftServer server, Mob boss, Fight fight) {
      ServerLevel level = (ServerLevel) boss.level();
      // Held on his feet for the length of the ceremony: a fall or a stray hit that takes
      // him to zero would otherwise end it with no ceremony and no loot.
      if (boss.getHealth() <= 0.0F) {
         boss.setHealth(1.0F);
      }
      fight.deathTicks--;
      double progress = 1.0 - Math.max(0.0, fight.deathTicks / (double) DEATH_CEREMONY_TICKS);
      if (fight.deathTicks > 15) {
         boss.setPos(boss.getX(), boss.getY() + 0.025, boss.getZ());
         boss.setDeltaMovement(Vec3.ZERO);
         boss.hurtMarked = true;
      }
      Vec3 heart = boss.position().add(0.0, 2.2, 0.0);

      // Everything he was holding falls back out of the air, losing its grip
      // one block at a time.
      if (fight.deathTicks % 8 == 0 && !fight.held.isEmpty()) {
         Held held = fight.held.remove(0);
         Entity display = findEntity(server, held.displayId);
         if (display != null) {
            Vec3 from = display.position();
            double floor = BossGrounding.groundY(level, from.x, from.z, from.y);
            Vec3 to = new Vec3(from.x, floor + 0.2, from.z);
            Fx.comet(level, ParticleTypes.REVERSE_PORTAL, from, to, 6, kindColor(held.kind));
            Fx.rockburst(level, groundDust(level, to.x, to.y, to.z), to, 1.2, kindColor(held.kind));
            level.playSound(null, to.x, to.y, to.z, SoundEvents.STONE_BREAK, SoundSource.HOSTILE, 1.0F, 0.7F);
            display.discard();
         }
      }
      // The void closes in: a ring every ten ticks, each one smaller than the last.
      if (fight.deathTicks % 10 == 0 && fight.deathTicks > 0) {
         double r = 2.0 + 10.0 * (1.0 - progress);
         Fx.ring(level, ParticleTypes.REVERSE_PORTAL, boss.position().add(0.0, 0.2, 0.0), r, VOID);
         level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.ENDERMAN_HURT, SoundSource.HOSTILE, 1.2F, 0.5F + (float) progress);
      }
      // Cracks open in the air round him, wider as he goes.
      if (fight.deathTicks % 6 == 0 && fight.deathTicks > 0) {
         double a = RANDOM.nextDouble() * Math.PI * 2.0;
         Vec3 at = heart.add(Math.cos(a) * 1.6, (RANDOM.nextDouble() - 0.5) * 2.0, Math.sin(a) * 1.6);
         Fx.tear(level, ParticleTypes.REVERSE_PORTAL, at, new Vec3(RANDOM.nextDouble() - 0.5, 1.0, RANDOM.nextDouble() - 0.5), 1.0 + progress * 2.0, 12, VOID_LIGHT);
      }
      if (fight.deathTicks == 60) {
         announceNear(level, boss, ARENA_RADIUS, SAY + "\"It's... slipping.\"");
         Fx.resonance(level, ParticleTypes.REVERSE_PORTAL, heart, 60, VOID);
      }
      if (fight.deathTicks == 25) {
         announceNear(level, boss, ARENA_RADIUS, "§8The ground he took wants to come back down.");
         Fx.flare(level, ParticleTypes.END_ROD, heart, 2.0, VOID_LIGHT);
         level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.WARDEN_SONIC_CHARGE, SoundSource.HOSTILE, 1.8F, 0.6F);
      }
      // The spiral, vortex, aura and collapse sent at the blow carry it for modded clients.
      if ((fight.deathTicks & 1) == 0) {
         Fx.vanilla(level, ParticleTypes.PORTAL, boss.getX(), boss.getY() + 1.5, boss.getZ(), 6, 1.2, 1.2, 1.2, 0.1);
      }

      if (fight.deathTicks > 0) {
         return;
      }
      for (Held held : new ArrayList<>(fight.held)) {
         Entity display = findEntity(server, held.displayId);
         if (display != null) {
            display.discard();
         }
      }
      fight.held.clear();
      for (Shot shot : new ArrayList<>(fight.shots)) {
         Entity display = findEntity(server, shot.displayId);
         if (display != null) {
            display.discard();
         }
      }
      fight.shots.clear();
      for (UUID id : new ArrayList<>(fight.wallBlocks)) {
         Entity display = findEntity(server, id);
         if (display != null) {
            display.discard();
         }
      }
      fight.wallBlocks.clear();
      fight.strikes.clear();

      // The collapse. He folds into the hole he came out of, and the light goes the other way.
      Vec3 feet = boss.position();
      Fx.flare(level, ParticleTypes.END_ROD, heart, 4.0, VOID_LIGHT);
      Fx.starburst(level, ParticleTypes.REVERSE_PORTAL, heart, 12.0, VOID);
      Fx.nova(level, ParticleTypes.REVERSE_PORTAL, heart, 9.0, VOID_LIGHT);
      Fx.shockwave(level, ParticleTypes.REVERSE_PORTAL, new Vec3(feet.x, BossGrounding.groundY(level, feet.x, feet.z, feet.y) + 0.1, feet.z), 18.0, VOID_DARK);
      wormhole(level, heart, false, VOID_DARK);
      Fx.rift(level, ParticleTypes.REVERSE_PORTAL, heart, 3.0, VOID);
      Fx.shatter(level, ParticleTypes.REVERSE_PORTAL, heart, 2.6, VOID);
      // ...and the ground he borrowed rains back down round the spot he stood.
      for (int i = 0; i < 8; i++) {
         double a = i * (Math.PI * 2.0 / 8.0) + RANDOM.nextDouble() * 0.4;
         double r = 4.0 + RANDOM.nextDouble() * 8.0;
         double x = feet.x + Math.cos(a) * r;
         double z = feet.z + Math.sin(a) * r;
         double y = BossGrounding.groundY(level, x, z, feet.y);
         Fx.meteor(level, groundDust(level, x, y, z), new Vec3(x - Math.cos(a) * 3.0, y + 18.0, z - Math.sin(a) * 3.0), new Vec3(x, y + 0.2, z), 14 + RANDOM.nextInt(10), VOID_LIGHT);
      }
      Fx.vanillaOnly(() -> {
         com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.EXPLOSION_EMITTER, boss.getX(), boss.getY() + 1.5, boss.getZ(), 3, 2.0, 2.0, 2.0, 0.15);
         com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.REVERSE_PORTAL, boss.getX(), boss.getY() + 1.5, boss.getZ(), 40, 5.0, 3.0, 5.0, 0.3);
      });
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), ModSounds.BOSS_DEATH, SoundSource.HOSTILE, 2.2F, 0.7F);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.ENDERMAN_SCREAM, SoundSource.HOSTILE, 2.0F, 0.4F);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.GENERIC_EXPLODE, SoundSource.HOSTILE, 1.8F, 0.5F);

      boss.setNoAi(false);
      onBossDeath(level, boss);
      boss.hurtServer(level, level.damageSources().generic(), boss.getMaxHealth() * 4.0F + 100.0F);
   }

   // --------------------------------------------------------------------- loot

   /**
    * Bosses whose loot has already been paid out - see
    * {@link ClockworkKingManager#onBossDeath} for why this exists. The ceremony
    * pays through this and the entity-death hook that follows it is a no-op, so
    * loot lands exactly once whichever route the boss dies by.
    */
   private static final Set<UUID> LOOT_PAID = new HashSet<>();

   /**
    * Sweeps up Void Shaper bodies left behind by a crash or a hard restart - see
    * {@link ClockworkKingManager#sweepStrays} for the full story. Run near
    * players, because chunks are not loaded on boot.
    *
    * @return how many bodies were removed
    */
   public static int sweepStrays(MinecraftServer server) {
      int removed = 0;
      // Perf: one 257-block entity query per player, repeated verbatim for every
      // player standing together, in a single tick. Now the radius matches the
      // arena (96 >= the widest arena's 90) and a player already inside a box
      // swept this pass is skipped instead of queried again.
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
            if (isVoidShaper(mob) && !FIGHTS.containsKey(mob.getUUID())) {
               mob.discard();
               removed++;
            }
         }
      }
      // The blocks a stray body left behind go with it.
      return removed + sweepOrphanedBlocks(server);
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

   private static void grantLoot(ServerLevel level, Mob boss, Fight fight) {
      drop(level, boss, ModItems.colossusTrophy());
      // Forge material only, and the pile of obsidian, pearls and deepslate that
      // used to come with it is gone: a boss that drops the raw ingredients as
      // well as the upgraded gear has nothing left for a second kill to give.
      int scrap = 2 + RANDOM.nextInt(3);
      for (int i = 0; i < scrap; i++) {
         drop(level, boss, ModItems.voidsteelScrap());
      }
      // Voidrend is his own: Seismic belongs to the stone golem.
      if (RANDOM.nextFloat() < 0.15F) {
         drop(level, boss, CustomEnchantments.tome(CustomEnchantments.VOIDREND, 1 + RANDOM.nextInt(3)));
      }

      // One roll at one legendary instead of two of the three, guaranteed.
      if (RANDOM.nextFloat() < 0.2F) {
         drop(level, boss, switch (RANDOM.nextInt(3)) {
            case 0 -> ModItems.voidReaver();
            case 1 -> ModItems.colossusPlate();
            default -> ModItems.shapingSigil();
         });
      }

      BossPayout.payBoxes(level, fight.participants, ModItems::voidshaperLootBox, BossPayout.BOXES_PER_KILL, "\u00a75Void Shaper Loot Box");
      for (UUID id : fight.participants) {
         ServerPlayer p = level.getServer().getPlayerList().getPlayer(id);
         if (p != null && p.isAlive()) {
            Advancements.grant(p, "kill_voidshaper");
         }
      }
      announce(level, "\u00a75\u00a7m\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550");
      announce(level, "    \u00a75\u00a7l\ud83d\udd2e THE VOID SHAPER IS UNMADE \ud83d\udd2e");
      announce(level, "\u00a75\u00a7m\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550");
   }

   private static void drop(ServerLevel level, Mob boss, ItemStack stack) {
      if (stack != null && !stack.isEmpty()) {
         level.addFreshEntity(new ItemEntity(level, boss.getX(), boss.getY() + 0.8, boss.getZ(), stack));
      }
   }

   // ------------------------------------------------------------------ teardown

   /**
    * Ends a fight's bookkeeping - and takes every block he made with it.
    *
    * <p>The blocks used to be cleared only by {@link #shutDown} and by the end of the death
    * ceremony. A death by any other route - one the entity-death hook reports straight to
    * {@link #onBossDeath} - released the fight here and left whatever he was holding, throwing
    * or walling with hanging in the air: displays nothing tracked any more, saved with their
    * chunk for good. Anything this pass cannot reach (an unloaded chunk) is caught by
    * {@link #sweepOrphanedBlocks} when it loads again.
    */
   private static void release(MinecraftServer server, Fight fight) {
      discardBlocks(server, fight);
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

   /** Discards every display this fight made - held, in flight, or standing in a wall. */
   private static void discardBlocks(MinecraftServer server, Fight fight) {
      for (Held held : new ArrayList<>(fight.held)) {
         Entity display = findEntity(server, held.displayId);
         if (display != null) {
            display.discard();
         }
      }
      for (Shot shot : new ArrayList<>(fight.shots)) {
         Entity display = findEntity(server, shot.displayId);
         if (display != null) {
            display.discard();
         }
      }
      for (UUID id : new ArrayList<>(fight.wallBlocks)) {
         Entity display = findEntity(server, id);
         if (display != null) {
            display.discard();
         }
      }
      fight.held.clear();
      fight.shots.clear();
      fight.wallBlocks.clear();
      fight.strikes.clear();
      // A gravity inversion cut off mid-hold left its players floating until the effect ran out.
      if (fight.invertTicks > 0) {
         fight.invertTicks = 0;
         for (UUID id : fight.participants) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p != null) {
               p.removeEffect(MobEffects.LEVITATION);
            }
         }
      }
   }

   // ------------------------------------------------------------- orphaned blocks

   /** How often the orphan sweep runs (server ticks), and where in that cycle. */
   private static final int ORPHAN_SWEEP_TICKS = 200;
   private static final int ORPHAN_SWEEP_OFFSET = 37;

   /**
    * Discards every block display of his - and of the Sigil and the Reaver - that nothing is
    * tracking any more.
    *
    * <p>Each one carries {@link #HOLD_TAG} (a block he holds, or one of a wall) or
    * {@link #SHARD_TAG} (a block in flight, a leaf fragment, or a player's gripped or thrown
    * block). They are tracked in memory and discarded by uuid, but a display is saved with its
    * chunk, so a uuid lookup misses every one that is not loaded at the time: a fight torn down
    * while its arena was unloaded, a player's throw that flew into an unloaded chunk, or any of
    * them after a crash. Those come back the next time the chunk loads as a block hanging in the
    * air that no tick would ever move or remove. This finds them by tag instead, every ten
    * seconds, in every loaded world - so it also catches them the first time their chunk loads.
    *
    * @return how many displays were removed
    */
   public static int sweepOrphanedBlocks(MinecraftServer server) {
      if (server == null) {
         return 0;
      }
      Set<UUID> tracked = new HashSet<>(LIFTED.keySet());
      for (Loose l : LOOSE) {
         tracked.add(l.displayId);
      }
      for (Fight fight : FIGHTS.values()) {
         for (Held h : fight.held) {
            tracked.add(h.displayId);
         }
         for (Shot shot : fight.shots) {
            tracked.add(shot.displayId);
         }
         tracked.addAll(fight.wallBlocks);
      }
      int removed = 0;
      for (ServerLevel level : server.getAllLevels()) {
         List<Entity> orphans = new ArrayList<>();
         for (Entity e : level.getAllEntities()) {
            if (e instanceof Display && !e.isRemoved() && isVoidBlock(e) && !tracked.contains(e.getUUID())) {
               orphans.add(e);
            }
         }
         for (Entity e : orphans) {
            e.discard();
            removed++;
         }
      }
      if (removed > 0) {
         com.fortuneandfavors.FortuneFavorsMod.LOGGER.info("[FF] Discarded {} orphaned Void Shaper block display(s)", removed);
      }
      return removed;
   }

   /** One of the displays this class makes: held, walled, thrown, fragmented or gripped. */
   private static boolean isVoidBlock(Entity e) {
      Set<String> tags = e.entityTags();
      return tags.contains(HOLD_TAG) || tags.contains(SHARD_TAG);
   }

   // ---------------------------------------------------------- throwable support

   /**
    * The Shaping Sigil's throw, and the Void Reaver's on-hit version: rip the
    * block at {@code pos} out of the world as a display and hurl it. Shared with
    * {@code VoidShaperGear} so a player's version of his gimmick obeys exactly the
    * same "the block decides the blow" table the boss does.
    */
   public static boolean hurlBlock(ServerLevel level, ServerPlayer thrower, BlockPos pos, Vec3 dir) {
      if (!isGrabbable(level, pos)) {
         return false;
      }
      BlockState state = level.getBlockState(pos);
      Display.BlockDisplay display = (Display.BlockDisplay) EntityTypes.BLOCK_DISPLAY.create(level, EntitySpawnReason.COMMAND);
      if (display == null) {
         return false;
      }
      display.setBlockState(state);
      display.addTag(SHARD_TAG);
      Vec3 from = new Vec3(pos.getX() + 0.5, pos.getY() + 0.2, pos.getZ() + 0.5);
      display.setPos(from.x, from.y, from.z);
      level.addFreshEntity(display);
      Kind kind = kindOf(state);
      Vec3 vel = dir.normalize().scale(kind.speed);
      playerTearFx(level, from, dir);
      level.playSound(null, from.x, from.y, from.z, SoundEvents.DEEPSLATE_BREAK, SoundSource.PLAYERS, 1.0F, 0.9F);
      // Player throws are ticked on their own, short-lived list.
      LOOSE.add(new Loose(display.getUUID(), from, vel, kind, thrower, 90));
      return true;
   }

   // ------------------------------------------------------- Shaping Sigil holds

   /**
    * Lifts the block at {@code pos} out of the world as a display that hovers in
    * front of its owner. Nothing is thrown and nothing is damaged - this is the
    * Sigil's "pick up" half, so gripping a block and hurling it are two separate,
    * legible actions instead of one surprising one.
    *
    * @return the display's uuid, or null if there is nothing grabbable there.
    */
   public static UUID liftBlock(ServerLevel level, ServerPlayer owner, BlockPos pos) {
      if (!isGrabbable(level, pos)) {
         return null;
      }
      BlockState state = level.getBlockState(pos);
      Display.BlockDisplay display = (Display.BlockDisplay)EntityTypes.BLOCK_DISPLAY.create(level, EntitySpawnReason.COMMAND);
      if (display == null) {
         return null;
      }
      display.setBlockState(state);
      display.addTag(SHARD_TAG);
      display.setPos(owner.getX(), owner.getY() + 1.6, owner.getZ());
      level.addFreshEntity(display);
      LIFTED.put(display.getUUID(), new Lift(owner.getUUID(), kindOf(state), ServerClock.clock(level) + LIFT_LIFETIME));
      // The block is pulled out of its socket and up into the hand along a thread of void.
      Vec3 socket = Vec3.atCenterOf(pos);
      Fx.shape(level, com.fortuneandfavors.net.FfVfx.RIFT, ParticleTypes.REVERSE_PORTAL, socket, Vec3.ZERO, 0.6, 0.0, VOID);
      Fx.soulStream(level, ParticleTypes.REVERSE_PORTAL, socket, display.position(), 0.35, 10, VOID_LIGHT);
      level.playSound(null, display.getX(), display.getY(), display.getZ(), SoundEvents.DEEPSLATE_BREAK, SoundSource.PLAYERS, 0.8F, 0.7F);
      return display.getUUID();
   }

   /** Throws a block that is currently held by the Sigil. */
   public static boolean hurlLifted(ServerLevel level, ServerPlayer owner, UUID displayId, Vec3 dir) {
      // Checked before it is taken off the list: removing it first and then refusing the throw
      // left a gripped block that nothing tracked, hovering where it was for good.
      Lift lift = LIFTED.get(displayId);
      if (lift == null || !lift.owner.equals(owner.getUUID())) {
         return false;
      }
      Entity raw = findEntity(level.getServer(), displayId);
      if (raw == null) {
         LIFTED.remove(displayId);
         return false;
      }
      LIFTED.remove(displayId);
      Vec3 from = raw.position();
      Vec3 vel = dir.normalize().scale(lift.kind.speed);
      playerTearFx(level, from, dir);
      level.playSound(null, from.x, from.y, from.z, SoundEvents.DEEPSLATE_BREAK, SoundSource.PLAYERS, 1.0F, 1.1F);
      LOOSE.add(new Loose(displayId, from, vel, lift.kind, owner, 90));
      return true;
   }

   /** The display this player is currently holding, or null. */
   public static UUID heldBy(ServerPlayer owner) {
      for (Map.Entry<UUID, Lift> e : LIFTED.entrySet()) {
         if (e.getValue().owner.equals(owner.getUUID())) {
            return e.getKey();
         }
      }
      return null;
   }

   /** Puts a held block back down (it was only ever a display - the world never changed). */
   public static void dropLifted(MinecraftServer server, UUID displayId, boolean poof) {
      LIFTED.remove(displayId);
      Entity display = findEntity(server, displayId);
      if (display != null) {
         if (poof && display.level() instanceof ServerLevel level) {
            // Let go, the block folds back into the void it was borrowed from.
            Fx.voidCollapse(level, ParticleTypes.REVERSE_PORTAL, display.position().add(0.0, -1.0, 0.0), 1.2, 8, VOID);
            Fx.vanilla(level, ParticleTypes.POOF, display.getX(), display.getY(), display.getZ(), 6, 0.3, 0.3, 0.3, 0.04);
         }
         display.discard();
      }
   }

   /**
    * Keeps held blocks hovering in front of their owner and discards them when the
    * owner leaves, dies or lets the hold time out. These displays are inert: they
    * have no hitbox and deal no damage until they are thrown.
    */
   public static void tickLifted(MinecraftServer server) {
      if (LIFTED.isEmpty()) {
         return;
      }
      long now = ServerClock.clock(server.overworld());
      for (Map.Entry<UUID, Lift> e : new ArrayList<>(LIFTED.entrySet())) {
         Lift lift = e.getValue();
         ServerPlayer owner = server.getPlayerList().getPlayer(lift.owner);
         if (owner == null || !owner.isAlive() || now > lift.expires) {
            dropLifted(server, e.getKey(), true);
            continue;
         }
         Entity display = findEntity(server, e.getKey());
         if (display == null) {
            LIFTED.remove(e.getKey());
            continue;
         }
         if (display.level() != owner.level()) {
            dropLifted(server, e.getKey(), false);
            continue;
         }
         Vec3 look = owner.getViewVector(1.0F).normalize();
         Vec3 at = owner.getEyePosition().add(look.scale(1.7)).add(0.0, -0.35, 0.0);
         display.setPos(at.x, at.y, at.z);
         display.setYRot(owner.getYRot());
         display.hurtMarked = true;
         if (display.level() instanceof ServerLevel level) {
            // Held by the void: dark motes drawn into the gripped block every half second for
            // modded clients, a portal mote now and then for everyone else.
            if (now % 10L == 0L) {
               com.fortuneandfavors.net.FfVfx.shape(level, FxKinds.VOID_COLLAPSE, ParticleTypes.REVERSE_PORTAL, at.add(0.0, -1.0, 0.0), Vec3.ZERO, 0.8, 10.0, VOID_LIGHT);
            }
            if (now % 4L == 0L) {
               Fx.vanilla(level, ParticleTypes.PORTAL, at.x, at.y, at.z, 1, 0.1, 0.1, 0.1, 0.0);
            }
         }
      }
   }

   /** A block held by the Sigil. */
   private static final class Lift {
      final UUID owner;
      final Kind kind;
      final long expires;

      Lift(UUID owner, Kind kind, long expires) {
         this.owner = owner;
         this.kind = kind;
         this.expires = expires;
      }
   }

   /** Held blocks time out after 40 seconds so a forgotten one cannot linger. */
   private static final long LIFT_LIFETIME = 800L;
   private static final Map<UUID, Lift> LIFTED = new java.util.LinkedHashMap<>();

   /** Clears a player's hold - used on disconnect so nothing is left hovering. */
   public static void forgetLifted(MinecraftServer server, UUID owner) {
      for (Map.Entry<UUID, Lift> e : new ArrayList<>(LIFTED.entrySet())) {
         if (e.getValue().owner.equals(owner)) {
            dropLifted(server, e.getKey(), false);
         }
      }
   }

   /** A player-thrown block. Kept separate from boss fights so it needs no boss. */
   private static final class Loose {
      final UUID displayId;
      Vec3 pos;
      final Vec3 vel;
      final Kind kind;
      final ServerPlayer thrower;
      int life;

      Loose(UUID displayId, Vec3 pos, Vec3 vel, Kind kind, ServerPlayer thrower, int life) {
         this.displayId = displayId;
         this.pos = pos;
         this.vel = vel;
         this.kind = kind;
         this.thrower = thrower;
         this.life = life;
      }
   }

   private static final List<Loose> LOOSE = new ArrayList<>();

   /** Ticked alongside the fights; a player's block is a fight of its own. */
   public static void tickLoose(MinecraftServer server) {
      if (LOOSE.isEmpty()) {
         return;
      }
      for (Iterator<Loose> it = LOOSE.iterator(); it.hasNext();) {
         Loose loose = it.next();
         Entity display = findEntity(server, loose.displayId);
         if (display == null || !(display.level() instanceof ServerLevel level)) {
            it.remove();
            continue;
         }
         boolean done = false;
         // The trail: a violet beam segment over the flight for modded clients, every other tick,
         // and a couple of portal motes for the rest. The motes were a bare FfVfx.particles,
         // which reached modded clients as well.
         if ((loose.life & 1) == 0) {
            com.fortuneandfavors.net.FfVfx.shape(level, com.fortuneandfavors.net.FfVfx.BEAM, ParticleTypes.REVERSE_PORTAL, loose.pos, loose.pos.add(loose.vel.scale(2.0)), 0.0, 0.0, VOID_LIGHT);
         }
         Fx.vanilla(level, ParticleTypes.REVERSE_PORTAL, loose.pos.x, loose.pos.y, loose.pos.z, 2, 0.08, 0.08, 0.08, 0.0);
         int steps = loose.kind.speed >= 1.5 ? 5 : 3;
         for (int s = 0; s < steps && !done; s++) {
            loose.pos = loose.pos.add(loose.vel.scale(1.0 / steps));
            display.setPos(loose.pos.x, loose.pos.y, loose.pos.z);
            display.hurtMarked = true;
            if (!level.getBlockState(BlockPos.containing(loose.pos)).isAir()) {
               done = true;
               break;
            }
            LivingEntity victim = livingNear(level, loose.thrower, loose.pos, 1.3, loose.kind);
            if (victim != null) {
               Fx.shape(level, com.fortuneandfavors.net.FfVfx.CLASH, ParticleTypes.CRIT, victim.position().add(0.0, victim.getBbHeight() * 0.6, 0.0), loose.vel.normalize(), 0.0, 0.0, 0xB06BFF);
               done = true;
               break;
            }
         }
         loose.life--;
         if (done || loose.life <= 0) {
            // The block shatters back into the void it was borrowed from.
            // ...through the block's own landing (fire for magma, frost for ice: the same table
            // the boss's throws use) and a small implosion of the void taking it back.
            impactFx(level, loose.kind, loose.pos.add(0.0, 0.2, 0.0), loose.vel);
            Fx.voidCollapse(level, ParticleTypes.REVERSE_PORTAL, loose.pos.add(0.0, -1.0, 0.0), 1.6, 8, VOID);
            Fx.vanilla(level, ParticleTypes.LARGE_SMOKE, loose.pos.x, loose.pos.y + 0.3, loose.pos.z, 6, 0.4, 0.3, 0.4, 0.05);
            level.playSound(null, loose.pos.x, loose.pos.y, loose.pos.z, SoundEvents.STONE_HIT, SoundSource.PLAYERS, 1.0F, 1.0F);
            if (display != null) {
               display.discard();
            }
            it.remove();
         }
      }
   }

   /**
    * The living thing a loose block lands on, if any - and the only place a
    * player-thrown block applies damage. The kind is re-read from the block that
    * the display is showing, so the effect always matches the visible block.
    */
   private static LivingEntity livingNear(ServerLevel level, ServerPlayer thrower, Vec3 at, double range, Kind kind) {
      // Searched round the block, not round the thrower. A throw outlives 48 blocks of flight, and
      // past that the old box around the thrower simply had nothing in it, so a long throw passed
      // through whoever it reached.
      for (Entity e : level.getEntities(thrower, new net.minecraft.world.phys.AABB(at, at).inflate(range + 2.0))) {
         if (!(e instanceof LivingEntity living) || living == thrower || !living.isAlive()) {
            continue;
         }
         if (living instanceof ServerPlayer other && (other.isCreative() || other.isSpectator())) {
            continue;
         }
         if (living.distanceToSqr(at.x, at.y, at.z) > range * range) {
            continue;
         }
         if (living instanceof ServerPlayer target && ScarletGear.isAlly(thrower, target)) {
            continue;
         }
         // The THROWN block decides the blow. This used to re-read the block at the
         // impact point, which is air the moment the projectile arrives - so every
         // block behaved like the default and magma never burned, ice never slowed.
         living.invulnerableTime = 0;
         living.hurtServer(level, level.damageSources().playerAttack(thrower), kind.damage);
         Vec3 away = living.position().subtract(at);
         if (away.lengthSqr() > 0.01) {
            away = new Vec3(away.x, 0.0, away.z).normalize();
            living.push(away.x * scaledKnockback(kind.knockback), 0.3, away.z * scaledKnockback(kind.knockback));
            living.hurtMarked = true;
         }
         if (kind.fireTicks > 0) {
            living.igniteForTicks(kind.fireTicks);
         }
         if (kind.slownessTicks > 0) {
            living.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, kind.slownessTicks, 1, false, true, true));
         }
         return living;
      }
      return null;
   }

   // ------------------------------------------------------------------ helpers

   /**
    * A wormhole that opens ({@code open}) or seals. Sent as the raw cue: {@code Fx.wormhole}'s
    * flag runs the wrong way round, so the rise, the Event Horizon and every step and slam were
    * drawn sealing where they opened and opening where they closed.
    */
   private static void wormhole(ServerLevel level, Vec3 at, boolean open, int color) {
      Fx.shape(level, com.fortuneandfavors.net.FfVfx.WORMHOLE, ParticleTypes.REVERSE_PORTAL, at, Vec3.ZERO, 0.0, open ? 1.0 : 0.0, color);
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

   private static ServerPlayer nearestPlayer(Mob from, double range) {
      if (!(from.level() instanceof ServerLevel level)) {
         return null;
      }
      ServerPlayer best = null;
      double bestDist = range * range;
      for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
         if (!isTarget(p, level)) {
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

   /**
    * Someone he can hit: alive, in his world, not in creative or spectator, and a real player
    * rather than one of the mod's puppet bodies.
    */
   private static boolean isTarget(ServerPlayer p, ServerLevel level) {
      return p != null && p.isAlive() && p.level() == level && !p.isCreative() && !p.isSpectator() && !BossManager.isFakePlayer(p);
   }

   private static List<ServerPlayer> participantsNear(ServerLevel level, Entity at, double range) {
      return playersNear(level, at.getX(), at.getY(), at.getZ(), range);
   }

   private static List<ServerPlayer> playersNear(ServerLevel level, double x, double y, double z, double range) {
      List<ServerPlayer> out = new ArrayList<>();
      double r2 = range * range;
      for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
         if (!isTarget(p, level)) {
            continue;
         }
         if (p.distanceToSqr(x, y, z) <= r2) {
            out.add(p);
         }
      }
      return out;
   }

   private static void announce(ServerLevel level, String message) {
      if (!BossChat.allowed("voidshaper", message)) {
         return;
      }
      for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
         Chat.raw(p, message);
      }
   }

   private static void announceNear(ServerLevel level, Mob boss, double range, String message) {
      if (!BossChat.allowed("voidshaper", message)) {
         return;
      }
      double r2 = range * range;
      for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
         if (p.level() == boss.level() && p.distanceToSqr(boss) <= r2) {
            Chat.raw(p, message);
         }
      }
   }

   /** Test hook: the kind table, exposed so the documented effects cannot drift. */
   public static String kindIdFor(BlockState state) {
      return kindOf(state).id;
   }

   /** Test hook: his health, so no future tuning can quietly make him unkillable. */
   public static double maxHealth() {
      return MAX_HEALTH;
   }
}
