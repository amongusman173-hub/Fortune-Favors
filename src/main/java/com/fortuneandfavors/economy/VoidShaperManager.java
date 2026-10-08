package com.fortuneandfavors.economy;

import com.fortuneandfavors.ModItems;
import com.fortuneandfavors.ModSounds;
import com.fortuneandfavors.util.Chat;
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
import net.minecraft.core.particles.ColorParticleOption;
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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
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
   private static final int DEATH_CEREMONY_TICKS = 70;
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

      Shot(UUID displayId, Vec3 pos, Vec3 vel, Kind kind, int life) {
         this.displayId = displayId;
         this.pos = pos;
         this.vel = vel;
         this.kind = kind;
         this.life = life;
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
   }

   /** A player's throw or grip: a tear in the world where the block came loose. */
   private static void playerTearFx(ServerLevel level, Vec3 at, Vec3 dir) {
      Fx.shape(level, com.fortuneandfavors.net.FfVfx.RIFT, ParticleTypes.REVERSE_PORTAL, at, Vec3.ZERO, 0.8, 0.0, 0x7A2BD9);
      Fx.shape(level, com.fortuneandfavors.net.FfVfx.MUZZLE, ParticleTypes.REVERSE_PORTAL, at, dir.normalize(), 0.0, 0.0, 0xB06BFF);
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
            return "You already have a Void Shaper running - finish him first!";
         }
      }

      ServerLevel level = summoner.level();
      Mob boss = (Mob) EntityTypes.ENDERMAN.create(level, EntitySpawnReason.COMMAND);
      if (boss == null) {
         return "The ground refused to move - he did not come.";
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
      boss.setPos(summoner.getX(), summoner.getY() + 1.0, summoner.getZ());
      level.addFreshEntity(boss);

      ServerBossEvent bar = new ServerBossEvent(
         UUID.randomUUID(), Component.literal(BOSS_NAME), BossBarColor.PURPLE, BossBarOverlay.PROGRESS
      );
      bar.setVisible(true);
      for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
         bar.addPlayer(p);
      }

      Fight fight = new Fight(boss.getUUID(), summoner.getUUID(), bar);
      fight.participants.add(summoner.getUUID());
      long now = ServerClock.clock(level);
      fight.nextThrow = now + 60L;
      fight.nextPull = now + 260L;
      fight.nextWall = now + 400L;
      fight.nextBarrage = now + 320L;
      fight.nextStep = now + 200L;
      fight.nextSlam = now + 480L;
      fight.nextInvert = now + 780L;
      FIGHTS.put(boss.getUUID(), fight);

      grab(level, boss, fight, 3, HOLD_TICKS + 20);

      announce(level, "\u00a75\u00a7m\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550");
      announce(level, "    \u00a75\u00a7l\ud83d\udd2e THE VOID SHAPER RISES \ud83d\udd2e");
      announce(level, "    \u00a77The ground near him has stopped being scenery.");
       announce(level, "    \u00a77\u00a7oWatch what he is holding - the block decides the blow.");
      announce(level, "\u00a75\u00a7m\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550");
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), ModSounds.BOSS_SPAWN, SoundSource.HOSTILE, 1.5F, 0.7F);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.DEEPSLATE_BREAK, SoundSource.HOSTILE, 1.8F, 0.6F);
      Advancements.grant(summoner, "summon_voidshaper");
      return null;
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
         Safe.run("void shaper tick", () -> tickFight(server, fight, now));
      }
   }

   private static void tickFight(MinecraftServer server, Fight fight, long now) {
      Mob boss = bossOf(server, fight);
      if (boss == null) {
         shutDown(server, fight);
         return;
      }
      ServerLevel level = (ServerLevel) boss.level();

      for (ServerPlayer p : server.getPlayerList().getPlayers()) {
         if (p.level() == level && p.isAlive() && p.distanceToSqr(boss) < ARENA_RADIUS * ARENA_RADIUS) {
            fight.participants.add(p.getUUID());
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

      float share = boss.getHealth() / boss.getMaxHealth();
      if (share <= 0.4F && fight.phase < 2) {
         enterPhase(level, boss, fight, 2);
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
         if (spawnHeld(level, boss, fight, state, angle, 2.2 + RANDOM.nextDouble() * 1.6, 1.6 + RANDOM.nextDouble() * 1.4, fuse)) {
            got++;
         }
      }
      if (got > 0) {
         level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.DEEPSLATE_BREAK, SoundSource.HOSTILE, 1.4F, 0.7F);
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
      ServerLevel level, Mob boss, Fight fight, BlockState state, double angle, double radius, double height, int fuse
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
         held.fuse--;
         double spin = now * 0.14 + held.angle;
         double x = boss.getX() + Math.cos(spin) * held.radius;
         double y = boss.getY() + held.height + Math.sin(spin * 0.7) * 0.25;
         double z = boss.getZ() + Math.sin(spin) * held.radius;
         display.setPos(x, y, z);
         display.hurtMarked = true;
         level.sendParticles(ParticleTypes.PORTAL, x, y, z, 1, 0.1, 0.1, 0.1, 0.0);

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
      level.sendParticles(ParticleTypes.PORTAL, from.x, from.y, from.z, 18, 0.3, 0.3, 0.3, 0.08);
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
         int steps = shot.kind.speed >= 1.5 ? 5 : 3;
         boolean done = false;
         for (int s = 0; s < steps && !done; s++) {
            shot.pos = shot.pos.add(shot.vel.scale(1.0 / steps));
            display.setPos(shot.pos.x, shot.pos.y, shot.pos.z);
            display.hurtMarked = true;
            level.sendParticles(ParticleTypes.PORTAL, shot.pos.x, shot.pos.y, shot.pos.z, 1, 0.05, 0.05, 0.05, 0.0);

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
      level.sendParticles(ParticleTypes.EXPLOSION, x, y + 0.2, z, 1, 0.0, 0.0, 0.0, 0.0);
      level.sendParticles(ParticleTypes.LARGE_SMOKE, x, y + 0.3, z, 12, 0.4, 0.3, 0.4, 0.05);
      level.playSound(null, x, y, z, onTerrain ? SoundEvents.STONE_BREAK : SoundEvents.STONE_HIT, SoundSource.HOSTILE, 1.2F, 1.0F);

      List<ServerPlayer> hit = playersNear(level, x, y, z, 2.0);
      for (ServerPlayer p : hit) {
         p.hurtServer(level, level.damageSources().mobAttack(boss), kind.damage);
         if (kind.knockback > 0.0) {
            Vec3 away = p.position().subtract(x, y, z);
            if (away.lengthSqr() < 0.01) {
               away = shot.vel;
            }
            away = new Vec3(away.x, 0.0, away.z).normalize();
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

      // Leaves and grass do not hit like a rock - they spray.
      if (kind.fragments > 1) {
         for (int i = 0; i < kind.fragments; i++) {
            double a = RANDOM.nextDouble() * Math.PI * 2.0;
            Vec3 dir = new Vec3(Math.cos(a), 0.05, Math.sin(a)).normalize();
            Vec3 frag = new Vec3(x, y + 0.2, z);
            fight.shots.add(new Shot(spawnFragment(level, shot, frag), frag, dir.scale(0.7), kind, 30));
         }
         level.sendParticles(ParticleTypes.CHERRY_LEAVES, x, y + 0.3, z, 20, 0.6, 0.4, 0.6, 0.06);
      }
      level.sendParticles(ParticleTypes.CRIT, x, y + 0.2, z, 10, 0.3, 0.3, 0.3, 0.06);
   }

   /** A fragment reuses the shard's own display where possible, else a fresh one. */
   private static UUID spawnFragment(ServerLevel level, Shot parent, Vec3 at) {
      Display.BlockDisplay display = (Display.BlockDisplay) EntityTypes.BLOCK_DISPLAY.create(level, EntitySpawnReason.COMMAND);
      if (display == null) {
         return parent.displayId;
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
      level.sendParticles(ParticleTypes.SCULK_SOUL, boss.getX(), boss.getY() + 0.2, boss.getZ(), 1, 0.2, 0.1, 0.2, 0.0);
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
      level.playSound(null, centre.x, centre.y, centre.z, SoundEvents.DEEPSLATE_PLACE, SoundSource.HOSTILE, 1.8F, 0.6F);
      announceNear(level, boss, ARENA_RADIUS, "&5&lBLOCK WALL&7 - " + blocks + " blocks, ripped up and stacked.");
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
         level.sendParticles(ParticleTypes.SCULK_SOUL, display.getX(), display.getY() + 0.5, display.getZ(), 1, 0.1, 0.1, 0.1, 0.0);
         for (ServerPlayer p : playersNear(level, display.getX(), display.getY(), display.getZ(), 1.4)) {
            Vec3 away = p.position().subtract(display.position());
            if (away.lengthSqr() < 0.01) {
               away = new Vec3(1.0, 0.0, 0.0);
            }
            away = new Vec3(away.x, 0.0, away.z).normalize();
            p.push(away.x * 0.9, 0.2, away.z * 0.9);
            p.hurtMarked = true;
         }
         if (fight.wallTicks <= 0) {
            level.sendParticles(ParticleTypes.LARGE_SMOKE, display.getX(), display.getY() + 0.4, display.getZ(), 14, 0.3, 0.3, 0.3, 0.05);
            display.discard();
         }
      }
      if (fight.wallTicks <= 0) {
         fight.wallBlocks.clear();
      }
   }

   // --------------------------------------------------------------------- moves

   private static void chooseMove(ServerLevel level, Mob boss, Fight fight, long now) {
      if (fight.phase >= 2 && now >= fight.nextInvert) {
         fight.nextInvert = now + INVERT_COOLDOWN;
         startInvert(level, boss, fight);
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
      if (now >= fight.nextPull) {
         fight.nextPull = now + PULL_COOLDOWN;
         enderPull(level, boss, fight);
         return;
      }
      if (now >= fight.nextBarrage) {
         fight.nextBarrage = now + BARRAGE_COOLDOWN;
         grab(level, boss, fight, 3 + RANDOM.nextInt(3), HOLD_TICKS);
         announceNear(level, boss, ARENA_RADIUS, "&5&lBLOCK BARRAGE&7 - he is loading up.");
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
      announce(level, "\u00a75The Void Shaper\u00a7r\u00a77 \u203a \u00a7f\u201cUp is a very temporary arrangement.\u201d");
      announceNear(level, boss, ARENA_RADIUS, "&5&lGRAVITY INVERTED&7 - you are about to be dropped. Water, or wings!");
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
            level.sendParticles(ParticleTypes.LARGE_SMOKE, p.getX(), p.getY() + 0.2, p.getZ(), 16, 0.5, 0.2, 0.5, 0.05);
            continue;
         }
         fight.participants.add(p.getUUID());
         p.addEffect(new MobEffectInstance(MobEffects.LEVITATION, 12, 1, false, false, true));
         p.hurtMarked = true;
         level.sendParticles(ParticleTypes.PORTAL, p.getX(), p.getY() + 0.4, p.getZ(), 4, 0.4, 0.4, 0.4, 0.02);
      }

      // Everything that falls, falls upward while it holds - the read for the move.
      double x = boss.getX();
      double y = boss.getY() + 1.0;
      double z = boss.getZ();
      for (int i = 0; i < 26; i++) {
         double ox = (RANDOM.nextDouble() - 0.5) * 2.0 * ARENA_RADIUS * 0.5;
         double oz = (RANDOM.nextDouble() - 0.5) * 2.0 * ARENA_RADIUS * 0.5;
         level.sendParticles(ParticleTypes.END_ROD, x + ox, y + RANDOM.nextDouble() * 2.0, z + oz, 1, 0.0, 0.9, 0.0, 0.25);
         level.sendParticles(ParticleTypes.REVERSE_PORTAL, x + ox, y, z + oz, 1, 0.0, 1.2, 0.0, 0.3);
      }
      if (RANDOM.nextInt(6) == 0) {
         level.playSound(null, x, y, z, SoundEvents.ENDERMAN_AMBIENT, SoundSource.HOSTILE, 1.2F, 0.5F);
      }
      if (release) {
         announceNear(level, boss, ARENA_RADIUS, "&5The floor comes back. &7Mind how you land.");
         level.playSound(null, x, y, z, SoundEvents.GENERIC_EXPLODE, SoundSource.HOSTILE, 1.6F, 0.7F);
      }
   }

   private static void enderPull(ServerLevel level, Mob boss, Fight fight) {
      for (ServerPlayer p : participantsNear(level, boss, 26.0)) {
         Vec3 pull = boss.position().add(0.0, 1.0, 0.0).subtract(p.position());
         double len = pull.length();
         if (len < 1.0) {
            continue;
         }
         Vec3 unit = pull.scale(1.0 / len);
         p.push(unit.x * 1.8, 0.4, unit.z * 1.8);
         p.hurtMarked = true;
         for (double d = 1.0; d < len; d += 1.4) {
            Vec3 point = p.position().add(unit.scale(d));
            level.sendParticles(ParticleTypes.PORTAL, point.x, point.y + 1.0, point.z, 1, 0.05, 0.05, 0.05, 0.0);
         }
      }
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.ENDERMAN_SCREAM, SoundSource.HOSTILE, 1.4F, 1.4F);
      announceNear(level, boss, ARENA_RADIUS, "&5&lENDER PULL&7 - he is reeling you in.");
   }

   private static void enderStep(ServerLevel level, Mob boss, Fight fight) {
      ServerPlayer target = nearestPlayer(boss, ARENA_RADIUS);
      if (target == null) {
         return;
      }
      Vec3 behind = target.position().subtract(target.getViewVector(1.0F).scale(2.5));
      level.sendParticles(ParticleTypes.REVERSE_PORTAL, boss.getX(), boss.getY() + 1.5, boss.getZ(), 60, 0.6, 1.0, 0.6, 0.15);
      boss.setPos(behind.x, target.getY(), behind.z);
      boss.hurtMarked = true;
      level.sendParticles(ParticleTypes.REVERSE_PORTAL, boss.getX(), boss.getY() + 1.5, boss.getZ(), 60, 0.6, 1.0, 0.6, 0.15);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE, 1.5F, 0.8F);
      // He arrives holding something, always.
      grab(level, boss, fight, 2, 18);
      announceNear(level, boss, ARENA_RADIUS, "&5&lENDER STEP&7 - he is behind you.");
   }

   private static void startSlam(ServerLevel level, Mob boss, Fight fight) {
      ServerPlayer target = nearestPlayer(boss, ARENA_RADIUS);
      if (target == null) {
         return;
      }
      fight.slamTarget = target.position();
      fight.slamCharge = 26;
      boss.setPos(target.getX(), target.getY() + 12.0, target.getZ());
      boss.setDeltaMovement(Vec3.ZERO);
      boss.hurtMarked = true;
      announceNear(level, boss, ARENA_RADIUS, "&5&lVOID SLAM&7 - he is above you!");
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.ENDERMAN_SCREAM, SoundSource.HOSTILE, 1.6F, 0.7F);
   }

   private static void tickSlam(ServerLevel level, Mob boss, Fight fight) {
      if (fight.slamTarget == null) {
         fight.slamCharge = 0;
         return;
      }
      Vec3 target = fight.slamTarget;
      double r = 5.0;
      for (int i = 0; i < 32; i++) {
         double a = i * (Math.PI * 2.0 / 32.0);
         level.sendParticles(ParticleTypes.PORTAL, target.x + Math.cos(a) * r, target.y + 0.2, target.z + Math.sin(a) * r, 1, 0.0, 0.0, 0.0, 0.0);
      }
      fight.slamCharge--;
      if (fight.slamCharge > 0) {
         return;
      }
      level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, target.x, target.y + 0.3, target.z, 4, 1.5, 0.5, 1.5, 0.0);
      level.sendParticles(ParticleTypes.GUST, target.x, target.y + 0.3, target.z, 40, 4.0, 0.4, 4.0, 0.2);
      level.sendParticles(ParticleTypes.REVERSE_PORTAL, target.x, target.y + 0.5, target.z, 80, 4.0, 0.8, 4.0, 0.2);
      level.playSound(null, target.x, target.y, target.z, ModSounds.BOSS_SLAM, SoundSource.HOSTILE, 2.2F, 0.6F);
      for (ServerPlayer p : playersNear(level, target.x, target.y, target.z, 5.5)) {
         Vec3 away = p.position().subtract(target);
         if (away.lengthSqr() < 0.01) {
            away = new Vec3(0.0, 1.0, 0.0);
         }
         away = away.normalize();
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
      announceNear(level, boss, ARENA_RADIUS, "&5&l\u26a0 THE VOID SHAPER HAS LOST CONTROL \u26a0");
      announceNear(level, boss, ARENA_RADIUS, "&7He is no longer aiming. Do not stand in the open.");
      level.sendParticles(ColorParticleOption.create(ParticleTypes.FLASH, 0xBB66FF), boss.getX(), boss.getY() + 1.5, boss.getZ(), 1, 0.0, 0.0, 0.0, 0.0);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.ENDERMAN_SCREAM, SoundSource.HOSTILE, 2.0F, 0.6F);
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
      level.sendParticles(ParticleTypes.REVERSE_PORTAL, boss.getX(), boss.getY() + 2.0, boss.getZ(), 60, 1.5, 1.5, 1.5, 0.25);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.SHULKER_SHOOT, SoundSource.HOSTILE, 1.4F, 0.8F);
   }

   // He has no dialogue at all. He is a thing that takes the world apart, not a
   // character with opinions about it, and the running commentary only competed
   // with the one message the fight actually needs to deliver - which block is in
   // his hands. Every mechanic telegraph below is kept, because those are the
   // fight's rules rather than his voice.

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
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.BEACON_DEACTIVATE, SoundSource.HOSTILE, 2.0F, 0.6F);
      // FALSE cancels the blow so the death ceremony - and its loot - runs.
      return Boolean.FALSE;
   }

   private static void tickDeath(MinecraftServer server, Mob boss, Fight fight) {
      ServerLevel level = (ServerLevel) boss.level();
      fight.deathTicks--;

      // Everything he was holding falls back out of the air, losing its grip
      // one block at a time.
      if (fight.deathTicks % 8 == 0 && !fight.held.isEmpty()) {
         Held held = fight.held.remove(0);
         Entity display = findEntity(server, held.displayId);
         if (display != null) {
            level.sendParticles(ParticleTypes.LARGE_SMOKE, display.getX(), display.getY(), display.getZ(), 10, 0.2, 0.2, 0.2, 0.04);
            display.discard();
         }
      }
      level.sendParticles(ParticleTypes.PORTAL, boss.getX(), boss.getY() + 1.5, boss.getZ(), 12, 1.2, 1.2, 1.2, 0.1);

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

      level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, boss.getX(), boss.getY() + 1.5, boss.getZ(), 8, 2.0, 2.0, 2.0, 0.15);
      level.sendParticles(ParticleTypes.REVERSE_PORTAL, boss.getX(), boss.getY() + 1.5, boss.getZ(), 200, 5.0, 3.0, 5.0, 0.3);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), ModSounds.BOSS_DEATH, SoundSource.HOSTILE, 2.2F, 0.7F);

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
      release(server, fight);
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
      Fx.shape(level, com.fortuneandfavors.net.FfVfx.RIFT, ParticleTypes.REVERSE_PORTAL, socket, Vec3.ZERO, 0.6, 0.0, 0x7A2BD9);
      Fx.shape(level, com.fortuneandfavors.net.FfVfx.BEAM, ParticleTypes.REVERSE_PORTAL, socket, display.position(), 0.0, 0.0, 0xB06BFF);
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
            level.sendParticles(ParticleTypes.POOF, display.getX(), display.getY(), display.getZ(), 10, 0.3, 0.3, 0.3, 0.04);
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
         if (now % 4L == 0L && display.level() instanceof ServerLevel level) {
            level.sendParticles(ParticleTypes.PORTAL, at.x, at.y, at.z, 1, 0.1, 0.1, 0.1, 0.0);
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
         int steps = loose.kind.speed >= 1.5 ? 5 : 3;
         for (int s = 0; s < steps && !done; s++) {
            loose.pos = loose.pos.add(loose.vel.scale(1.0 / steps));
            display.setPos(loose.pos.x, loose.pos.y, loose.pos.z);
            display.hurtMarked = true;
            if (s == 0) {
               com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.REVERSE_PORTAL, loose.pos.x, loose.pos.y, loose.pos.z, 2, 0.08, 0.08, 0.08, 0.0);
            }
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
            Fx.shape(level, com.fortuneandfavors.net.FfVfx.ROCKBURST, ParticleTypes.CLOUD, loose.pos.add(0.0, 0.2, 0.0), Vec3.ZERO, 1.8, 0.0, 0x9C8AB8);
            Fx.shatter(level, ParticleTypes.REVERSE_PORTAL, loose.pos.add(0.0, 0.3, 0.0), 1.2, 0x7A2BD9);
            com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.LARGE_SMOKE, loose.pos.x, loose.pos.y + 0.3, loose.pos.z, 10, 0.4, 0.3, 0.4, 0.05);
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

   private static List<ServerPlayer> participantsNear(ServerLevel level, Entity at, double range) {
      return playersNear(level, at.getX(), at.getY(), at.getZ(), range);
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
