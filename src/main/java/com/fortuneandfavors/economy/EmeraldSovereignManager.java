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
import net.minecraft.world.phys.Vec3;

/**
 * <b>The Emerald Sovereign</b> - a king who fights with subjects.
 *
 * <h2>His gimmick is the crown, not the sword</h2>
 * He does not duel you. He <em>decrees</em>: he tolls a bell that staggers you,
 * marks ground for an emerald volley, and calls <b>Royal Guards</b> - real,
 * killable vindicators that fight for him. His armour scales with his guard count
 * for the same reason the Clockwork King's does with his machines: the fight is
 * about the court around him, and clearing it is how you get to him.
 *
 * <h2>He never takes anything from you</h2>
 * Nothing in this fight touches a player's inventory, and his guards drop nothing
 * of yours. A king who taxes your items would be a theft bug wearing a crown, so
 * the only thing he collects is ground.
 *
 * <h2>Phases</h2>
 * <ul>
 *   <li><b>I - Court</b>: Bell Toll, Emerald Volley, Royal Decree (guards).</li>
 *   <li><b>II - The Throne</b> (50%): he stops holding back. Guards arrive in
 *       numbers, he empowers them, and <b>Sovereign's Judgement</b> - a green beam
 *       that has to be walked out of - joins the rotation.</li>
 * </ul>
 */
public final class EmeraldSovereignManager {

   private static final String TAG = "ff_emerald_sovereign";
   private static final String GUARD_TAG = "ff_royal_guard";
   private static final String GUARD_OWNER_KEY = "ff_royal_guard_boss";
   private static final String BOSS_NAME = "\u00a7a\u00a7l\ud83d\udc51 The Emerald Sovereign";
   private static final String SAY = "\u00a7aThe Sovereign\u00a7r\u00a77 \u203a \u00a7f";

   private static final double MAX_HEALTH = 680.0;
   /**
    * The most Resistance his court can be worth, in tiers.
    *
    * <p>This used to reach <b>three</b> tiers - a 60% damage cut, permanently
    * reapplied while any guard lived - on top of his sirens' healing, which is
    * what made him read as a boss with far more health than his bar showed. One
    * tier still rewards breaking the court without turning it into a wall.
    */
   private static final int MAX_COURT_TIERS = 1;
   private static final double ARENA_RADIUS = 68.0;
   /** See {@code ClockworkKingManager.STRAY_SWEEP_RADIUS}: just wider than the
    *  widest arena in the mod, and no wider. */
   public static final double STRAY_SWEEP_RADIUS = 96.0;

   private static final int BELL_COOLDOWN = 220;
   private static final int VOLLEY_COOLDOWN = 130;
   private static final int DECREE_COOLDOWN = 300;
   private static final int JUDGEMENT_COOLDOWN = 340;
   private static final int TRIBUTE_COOLDOWN = 260;
   /** The three added moves: his crown, his wheel, and the coffin. */
   private static final int CROWN_COOLDOWN = 380;
   private static final int WHEEL_COOLDOWN = 500;
   private static final int COFFIN_COOLDOWN = 440;
   private static final float CROWN_DAMAGE = 13.0F;
   private static final float WHEEL_DAMAGE = 7.0F;
   private static final float COFFIN_DAMAGE = 8.0F;
   /** Crownfall: how long each piece hangs in the air, and how wide its impact is. */
   private static final int CROWN_FUSE = 30;
   private static final double CROWN_RADIUS = 3.2;
   /** The wheel: a full turn in about fifty ticks, in three arms. */
   private static final int WHEEL_TICKS = 70;
   private static final double WHEEL_SPIN = 0.13;
   private static final double WHEEL_HALF_WIDTH = 1.3;
   private static final double WHEEL_REACH = 30.0;
   /** The coffin: how long the green circle sits there, and how long the walls hold. */
   private static final int COFFIN_TELL = 26;
   private static final int COFFIN_HOLD = 50;

   private static final int GUARD_CAP = 6;
   private static final int DEATH_CEREMONY_TICKS = 70;
   /**
    * How long his body may be unfindable before the fight is called over.
    *
    * <p>Ten seconds, and it is the answer to a specific false ending: an entity in a chunk with
    * no player near it cannot be looked up by uuid, so a boss whose court cleared out - or whose
    * owner walked away for a moment - read as "gone" and was torn down, boss bar and all, with
    * no loot. Long enough to be certain, short enough that a real ending still looks immediate.
    */
   private static final int MISSING_GRACE_TICKS = 200;

   private static final Random RANDOM = new Random();

   /** A volley emerald in flight. Tracked here, so no pickup rules apply. */
   private static final class Emerald {
      Vec3 pos;
      final Vec3 vel;
      final float damage;
      int life;

      Emerald(Vec3 pos, Vec3 vel, float damage, int life) {
         this.pos = pos;
         this.vel = vel;
         this.damage = damage;
         this.life = life;
      }
   }

   private static final class Fight {
      final UUID bossId;
      final UUID summoner;
      final ServerBossEvent bar;
      final Set<UUID> participants = new HashSet<>();
      final Set<UUID> guards = new HashSet<>();
      final List<Emerald> emeralds = new ArrayList<>();
      int phase = 1;
      boolean dying;
      int deathTicks;
      long nextBell;
      long nextVolley;
      long nextDecree;
      long nextJudgement;
      long nextTribute;
      long nextTaunt;
      /** When the crown, the wheel and the coffin may next be used. */
      long nextCrown;
      long nextWheel;
      long nextCoffin;
      int judgementCharge;
      Vec3 judgementOrigin;
      Vec3 judgementDir;
      /** Crownfall: one entry per piece of the crown still falling. */
      final List<Mark> marks = new ArrayList<>();
      /** The wheel: how long it spins for, where it is pointing, who it has caught. */
      int wheelTicks;
      double wheelAngle;
      final Map<UUID, Long> wheelHits = new HashMap<>();
      /** The coffin: the tell, the hold, where it was aimed, and what it raised. */
      int coffinTell;
      int coffinHold;
      Vec3 coffinAt;
      final List<BlockPos> coffinBlocks = new ArrayList<>();
      /**
       * Consecutive ticks the body could not be found, and where it was last seen.
       *
       * <p>Both exist because "the boss vanished and nothing dropped" has exactly two causes
       * and they need different answers. A body in a chunk that nobody is standing in is not a
       * body that is gone - the entity is still in the world, the lookup just cannot see it -
       * and that is a tick to skip, not a fight to end. A body that really is gone is a fight
       * that is over, and it owes its payout at the place it was last standing, because the
       * alternative is a boss that dies and drops nothing, which is the report.
       */
      int missingTicks;
      ServerLevel lastSeenLevel;
      double lastSeenX;
      double lastSeenY;
      double lastSeenZ;
      boolean everSeen;

      Fight(UUID bossId, UUID summoner, ServerBossEvent bar) {
         this.bossId = bossId;
         this.summoner = summoner;
         this.bar = bar;
      }
   }

   /** A marked impact: a ring on the ground with a fuse burning under it. */
   private static final class Mark {
      final Vec3 pos;
      final double radius;
      final float damage;
      int fuse;

      Mark(Vec3 pos, double radius, float damage, int fuse) {
         this.pos = pos;
         this.radius = radius;
         this.damage = damage;
         this.fuse = fuse;
      }
   }

   private static final Map<UUID, Fight> FIGHTS = new HashMap<>();

   private EmeraldSovereignManager() {
   }

   // ------------------------------------------------------------------ public API

   public static boolean isSovereign(Entity entity) {
      return entity != null && entity.entityTags().contains(TAG);
   }

   public static boolean isRoyalGuard(Entity entity) {
      return entity != null && entity.entityTags().contains(GUARD_TAG);
   }

   public static int activeCount() {
      return FIGHTS.size();
   }

   public static int guardCount(UUID bossId) {
      Fight fight = FIGHTS.get(bossId);
      return fight == null ? 0 : fight.guards.size();
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
         Safe.run("sovereign abandon", () -> shutDown(server, fight));
         ended++;
      }
      return ended;
   }

   public static void onServerStopping(MinecraftServer server) {
      for (Fight fight : new ArrayList<>(FIGHTS.values())) {
         Safe.run("sovereign shutdown", () -> shutDown(server, fight));
      }
      FIGHTS.clear();
   }

   /** Sovereign's Crown right-click: put it on and he comes to take it back. */
   public static String useCrown(ServerPlayer player, ItemStack held) {
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
            return "You already have a Sovereign holding court - dismiss him first!";
         }
      }

      ServerLevel level = summoner.level();
      Mob boss = (Mob) EntityTypes.VINDICATOR.create(level, EntitySpawnReason.COMMAND);
      if (boss == null) {
         return "The court did not assemble - he did not arrive.";
      }

      AttributeInstance maxHp = boss.getAttribute(Attributes.MAX_HEALTH);
      if (maxHp != null) {
         maxHp.setBaseValue(MAX_HEALTH);
      }
      boss.setHealth((float) MAX_HEALTH);
      AttributeInstance dmg = boss.getAttribute(Attributes.ATTACK_DAMAGE);
      if (dmg != null) {
         dmg.setBaseValue(16.0);
      }
      AttributeInstance follow = boss.getAttribute(Attributes.FOLLOW_RANGE);
      if (follow != null) {
         follow.setBaseValue(ARENA_RADIUS);
      }
      AttributeInstance kb = boss.getAttribute(Attributes.KNOCKBACK_RESISTANCE);
      if (kb != null) {
         kb.setBaseValue(1.0);
      }
      AttributeInstance scale = boss.getAttribute(Attributes.SCALE);
      if (scale != null) {
         scale.setBaseValue(1.6);
      }

      boss.setPersistenceRequired();
      boss.setCustomName(Component.literal(BOSS_NAME));
      boss.setCustomNameVisible(true);
      boss.setNoAi(true);
      boss.addTag(TAG);
      // The shared marker plus the visible-and-persistent guarantee: see
      // BossManager.markBoss for why a boss has to say so itself.
      BossManager.markBoss(boss);
      boss.setPos(summoner.getX(), summoner.getY(), summoner.getZ());
      level.addFreshEntity(boss);

      ServerBossEvent bar = new ServerBossEvent(
         UUID.randomUUID(), Component.literal(BOSS_NAME), BossBarColor.GREEN, BossBarOverlay.PROGRESS
      );
      bar.setVisible(true);
      for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
         bar.addPlayer(p);
      }

      Fight fight = new Fight(boss.getUUID(), summoner.getUUID(), bar);
      fight.participants.add(summoner.getUUID());
      long now = ServerClock.clock(level);
      fight.nextBell = now + 120L;
      fight.nextVolley = now + 60L;
      fight.nextDecree = now + 200L;
      fight.nextTribute = now + 360L;
      fight.nextTaunt = now + 140L;
      fight.nextCrown = now + 180L;
      fight.nextCoffin = now + 520L;
      fight.nextWheel = now + 700L;
      FIGHTS.put(boss.getUUID(), fight);
      summonGuards(level, boss, fight, 2);

      announce(level, "\u00a72\u00a7m\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550");
      announce(level, "    \u00a7a\u00a7l\ud83d\udc51 THE EMERALD SOVEREIGN HOLDS COURT \ud83d\udc51");
      announce(level, "    \u00a77A bell rings somewhere behind him.");
      announce(level, "    \u00a78\u201c\u00a7fI do not need to kill you. I have \u00a7apeople\u00a7f for that.\u00a78\u201d");
      announce(level, "    \u00a77\u00a7oBreak his guards - his court is his armour.");
      announce(level, "\u00a72\u00a7m\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550");
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), ModSounds.BOSS_SPAWN, SoundSource.HOSTILE, 1.3F, 0.9F);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.BELL_BLOCK, SoundSource.HOSTILE, 1.8F, 0.7F);
      Advancements.grant(summoner, "summon_sovereign");
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
         Safe.run("sovereign tick", () -> tickFight(server, fight, now));
      }
   }

   /**
    * Bosses whose loot has already been paid out - see
    * {@link ClockworkKingManager#onBossDeath} for why this exists. The ceremony
    * pays through this and the entity-death hook that follows it is a no-op, so
    * loot lands exactly once whichever route the boss dies by.
    */
   private static final Set<UUID> LOOT_PAID = new HashSet<>();

   /**
    * Sweeps up Sovereign bodies left behind by a crash or a hard restart - see
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
            if (isSovereign(mob) && !FIGHTS.containsKey(mob.getUUID())) {
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

   private static void tickFight(MinecraftServer server, Fight fight, long now) {
      Mob boss = bossOf(server, fight);
      if (boss == null) {
         tickMissing(server, fight);
         return;
      }
      fight.missingTicks = 0;
      fight.everSeen = true;
      fight.lastSeenLevel = (ServerLevel) boss.level();
      fight.lastSeenX = boss.getX();
      fight.lastSeenY = boss.getY();
      fight.lastSeenZ = boss.getZ();
      ServerLevel level = (ServerLevel) boss.level();

      for (ServerPlayer p : server.getPlayerList().getPlayers()) {
         if (p.level() == level && p.isAlive() && p.distanceToSqr(boss) < ARENA_RADIUS * ARENA_RADIUS) {
            fight.participants.add(p.getUUID());
         }
      }

      reapGuards(server, fight, boss, level);

      if (fight.dying) {
         tickDeath(server, boss, fight);
         return;
      }

      fight.bar.setProgress(Math.max(0.0F, Math.min(1.0F, boss.getHealth() / boss.getMaxHealth())));
      fight.bar.setName(Component.literal(barName(fight)));
      // The bar's art follows the phase: red is the client's cue for the throne-room bar.
      fight.bar.setColor(fight.phase >= 2 ? BossBarColor.RED : BossBarColor.GREEN);

      tickEmeralds(level, boss, fight);

      float share = boss.getHealth() / boss.getMaxHealth();
      if (share <= 0.5F && fight.phase < 2) {
         enterPhase(level, boss, fight, 2);
      }

      applyCourt(level, boss, fight);
      // Crownfall keeps falling while he does other things, so its marks are ticked
      // here rather than from inside the move.
      tickMarks(level, boss, fight);

      if (fight.coffinTell > 0 || fight.coffinHold > 0) {
         tickCoffin(level, boss, fight);
         return;
      }
      if (fight.wheelTicks > 0) {
         tickWheel(level, boss, fight);
         return;
      }
      if (fight.judgementCharge > 0) {
         tickJudgement(level, boss, fight);
         return;
      }
      chooseMove(level, boss, fight, now);

      if (now >= fight.nextTaunt) {
         fight.nextTaunt = now + 300L + RANDOM.nextInt(200);
         taunt(level, boss, fight);
      }
   }

   private static String barName(Fight fight) {
      String phase = fight.phase == 1 ? "Phase I" : "Phase II - THE THRONE";
      return BOSS_NAME + " \u00a78| \u00a7f" + phase + " \u00a78| \u00a77" + fight.guards.size() + " guard(s)";
   }

   /**
    * His armour is his court: each living guard is worth a tier of Resistance,
    * refreshed every tick so the moment the court empties he is genuinely open.
    */
   private static void applyCourt(ServerLevel level, Mob boss, Fight fight) {
      int tiers = Math.min(MAX_COURT_TIERS, Math.max(0, fight.guards.size() - 1));
      if (tiers > 0) {
         boss.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 8, tiers, false, false, true));
      }
      // And he shares his own buffs with the court, which is what makes clearing
      // them a real decision rather than an obvious chore.
      for (UUID id : fight.guards) {
         Entity raw = findEntity(level.getServer(), id);
         if (raw instanceof Mob guard && guard.isAlive()) {
            guard.addEffect(new MobEffectInstance(MobEffects.SPEED, 40, fight.phase >= 2 ? 1 : 0, false, false, true));
            if (fight.phase >= 2) {
               guard.addEffect(new MobEffectInstance(MobEffects.STRENGTH, 40, 0, false, false, true));
            }
         }
      }
   }

   private static void reapGuards(MinecraftServer server, Fight fight, Mob boss, ServerLevel level) {
      if (fight.guards.isEmpty()) {
         return;
      }
      for (Iterator<UUID> it = fight.guards.iterator(); it.hasNext();) {
         UUID id = it.next();
         Entity raw = findEntity(server, id);
         if (raw instanceof Mob guard && guard.isAlive()) {
            continue;
         }
         it.remove();
         if (raw != null) {
            level.sendParticles(ParticleTypes.LARGE_SMOKE, raw.getX(), raw.getY() + 0.8, raw.getZ(), 16, 0.4, 0.4, 0.4, 0.06);
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, raw.getX(), raw.getY() + 0.8, raw.getZ(), 20, 0.5, 0.5, 0.5, 0.06);
            level.playSound(null, raw.getX(), raw.getY(), raw.getZ(), SoundEvents.VINDICATOR_CELEBRATE, SoundSource.HOSTILE, 1.0F, 0.8F);
         }
         announceNear(level, boss, ARENA_RADIUS, "&a\u2694 Court thinned&7 - &f" + fight.guards.size() + "&7 guard(s) left. His armour thins with it.");
      }
   }

   private static void summonGuards(ServerLevel level, Mob boss, Fight fight, int count) {
      for (int i = 0; i < count; i++) {
         if (fight.guards.size() >= GUARD_CAP) {
            return;
         }
         Mob guard = (Mob) EntityTypes.VINDICATOR.create(level, EntitySpawnReason.COMMAND);
         if (guard == null) {
            continue;
         }
         double angle = RANDOM.nextDouble() * Math.PI * 2.0;
         double radius = 3.0 + RANDOM.nextDouble() * 3.0;
         guard.setPos(
            boss.getX() + Math.cos(angle) * radius,
            boss.getY() + 0.2,
            boss.getZ() + Math.sin(angle) * radius
         );
         guard.setPersistenceRequired();
         guard.setCustomName(Component.literal("\u00a7a\u00a7l\u2694 Royal Guard"));
         guard.setCustomNameVisible(true);
         guard.addTag(GUARD_TAG);
         guard.addTag(GUARD_OWNER_KEY + ":" + boss.getUUID());
         AttributeInstance hp = guard.getAttribute(Attributes.MAX_HEALTH);
         if (hp != null) {
            hp.setBaseValue(60.0);
         }
         guard.setHealth(60.0F);
         // Guards are people, not loot piñatas: what they carry is theirs.
         guard.setDropChance(net.minecraft.world.entity.EquipmentSlot.MAINHAND, 0.0F);
         guard.setDropChance(net.minecraft.world.entity.EquipmentSlot.OFFHAND, 0.0F);
         level.addFreshEntity(guard);
         fight.guards.add(guard.getUUID());
         level.sendParticles(ParticleTypes.HAPPY_VILLAGER, guard.getX(), guard.getY() + 1.0, guard.getZ(), 16, 0.4, 0.5, 0.4, 0.05);
      }
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.VINDICATOR_AMBIENT, SoundSource.HOSTILE, 1.4F, 0.9F);
   }

   // --------------------------------------------------------------------- moves

   private static void chooseMove(ServerLevel level, Mob boss, Fight fight, long now) {
      if (fight.phase >= 2 && now >= fight.nextJudgement) {
         fight.nextJudgement = now + JUDGEMENT_COOLDOWN;
         startJudgement(level, boss, fight);
         return;
      }
      if (fight.phase >= 2 && now >= fight.nextWheel) {
         fight.nextWheel = now + WHEEL_COOLDOWN;
         startWheel(level, boss, fight);
         return;
      }
      if (fight.phase >= 2 && now >= fight.nextCoffin) {
         fight.nextCoffin = now + COFFIN_COOLDOWN;
         startCoffin(level, boss, fight);
         return;
      }
      if (now >= fight.nextCrown) {
         fight.nextCrown = now + CROWN_COOLDOWN;
         crownfall(level, boss, fight);
         return;
      }
      if (now >= fight.nextTribute) {
         fight.nextTribute = now + TRIBUTE_COOLDOWN;
         royalTribute(level, boss, fight);
         return;
      }
      if (now >= fight.nextDecree) {
         fight.nextDecree = now + DECREE_COOLDOWN;
         royalDecree(level, boss, fight);
         return;
      }
      if (now >= fight.nextBell) {
         fight.nextBell = now + BELL_COOLDOWN;
         bellToll(level, boss, fight);
         return;
      }
      if (now >= fight.nextVolley) {
         fight.nextVolley = now + VOLLEY_COOLDOWN;
         emeraldVolley(level, boss, fight);
      }
   }

   /** The bell: staggers players, and stirs the court to fight harder. */
   private static void bellToll(ServerLevel level, Mob boss, Fight fight) {
      double x = boss.getX();
      double y = boss.getY() + 1.0;
      double z = boss.getZ();
      level.playSound(null, x, y, z, SoundEvents.BELL_BLOCK, SoundSource.HOSTILE, 2.0F, 0.7F);
      level.playSound(null, x, y, z, SoundEvents.BELL_RESONATE, SoundSource.HOSTILE, 1.6F, 0.6F);
      for (int i = 0; i < 3; i++) {
         double r = 3.0 + i * 4.0;
         for (int p = 0; p < 28; p++) {
            double a = p * (Math.PI * 2.0 / 28.0);
            level.sendParticles(
               ColorParticleOption.create(ParticleTypes.FLASH, 0x55FF88),
               x + Math.cos(a) * r,
               y - 0.6,
               z + Math.sin(a) * r,
               1,
               0.0,
               0.0,
               0.0,
               0.0
            );
         }
      }
      for (ServerPlayer p : participantsNear(level, boss, 22.0)) {
         p.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 80, 2, false, true, true));
         p.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 80, 0, false, true, true));
         level.sendParticles(ParticleTypes.NOTE, p.getX(), p.getY() + 2.0, p.getZ(), 10, 0.4, 0.4, 0.4, 0.1);
      }
      announceNear(level, boss, ARENA_RADIUS, "&a&lBELL TOLL&7 - the court answers. You stumble.");
   }

   private static void emeraldVolley(ServerLevel level, Mob boss, Fight fight) {
      ServerPlayer target = nearestPlayer(boss, ARENA_RADIUS);
      if (target == null) {
         return;
      }
      int count = 4 + fight.guards.size();
      Vec3 from = boss.position().add(0.0, boss.getBbHeight() * 0.7, 0.0);
      for (int i = 0; i < count; i++) {
         Vec3 to = target.position().add(0.0, target.getBbHeight() * 0.5, 0.0);
         Vec3 dir = to.subtract(from);
         if (dir.lengthSqr() < 1.0E-4) {
            dir = new Vec3(0.0, 0.0, 1.0);
         }
         dir = dir.normalize();
         double spread = (i - (count - 1) / 2.0) * 0.05;
         Vec3 vel = new Vec3(dir.x + spread, dir.y + 0.08, dir.z + spread).normalize().scale(0.9);
         fight.emeralds.add(new Emerald(from, vel, 6.0F, 100));
      }
      level.playSound(null, from.x, from.y, from.z, SoundEvents.VILLAGER_TRADE, SoundSource.HOSTILE, 1.2F, 0.6F);
   }

   /** Drags a tithe of experience and health out of you - never your items. */
   private static void royalTribute(ServerLevel level, Mob boss, Fight fight) {
      double x = boss.getX();
      double y = boss.getY() + 1.0;
      double z = boss.getZ();
      int taken = 0;
      for (ServerPlayer p : participantsNear(level, boss, 18.0)) {
         Vec3 pull = boss.position().add(0.0, 1.0, 0.0).subtract(p.position());
         double len = pull.length();
         if (len < 1.0) {
            continue;
         }
         Vec3 unit = pull.scale(1.0 / len);
         for (double d = 1.0; d < len; d += 1.2) {
            Vec3 point = p.position().add(unit.scale(d));
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, point.x, point.y + 1.0, point.z, 1, 0.05, 0.05, 0.05, 0.0);
         }
         // A tithe of vitality, not of property: he banks a little healing, and
         // the player is slowed - but nothing leaves an inventory.
         p.addEffect(new MobEffectInstance(MobEffects.HUNGER, 120, 1, false, true, true));
         p.hurtServer(level, level.damageSources().mobAttack(boss), 4.0F);
         // A modest tithe, not a lifeline: at 12 HP a head with six guards in
         // range this move healed more than most players could out-damage.
         boss.heal(5.0F);
         taken++;
      }
      level.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, x, y, z, 40, 1.6, 1.2, 1.6, 0.1);
      level.playSound(null, x, y, z, SoundEvents.BELL_RESONATE, SoundSource.HOSTILE, 1.4F, 1.2F);
      announceNear(level, boss, ARENA_RADIUS, taken == 0
         ? "&a&lROYAL TRIBUTE&7 - nobody is close enough to tax."
         : "&a&lROYAL TRIBUTE&7 - he takes a tithe of \u00a7f" + taken + "&7 subject(s).");
   }

   private static void royalDecree(ServerLevel level, Mob boss, Fight fight) {
      summonGuards(level, boss, fight, fight.phase >= 2 ? 3 : 2);
      double x = boss.getX();
      double y = boss.getY() + 1.0;
      double z = boss.getZ();
      level.sendParticles(ParticleTypes.HAPPY_VILLAGER, x, y, z, 60, 2.0, 1.0, 2.0, 0.2);
      level.playSound(null, x, y, z, SoundEvents.RAVAGER_ROAR, SoundSource.HOSTILE, 1.2F, 1.6F);
      announceNear(level, boss, ARENA_RADIUS, "&a&lROYAL DECREE&7 - he calls more of the court.");
   }

   /**
    * Crownfall: he throws the crown off his own head and it comes down in pieces.
    *
    * <p>Seven impacts - six thrown around whoever is nearest, and one on himself,
    * because a king who will not stand where his crown is falling is a king who has
    * not committed. Each one is a ring on the ground with a fuse burning under it, so
    * the answer is movement, and the punishment for standing still is being under the
    * crown when it lands.
    */
   private static void crownfall(ServerLevel level, Mob boss, Fight fight) {
      Vec3 origin = boss.position().add(0.0, boss.getBbHeight() + 1.4, 0.0);
      ServerPlayer target = nearestPlayer(boss, ARENA_RADIUS);
      level.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, origin.x, origin.y, origin.z, 60, 0.8, 0.5, 0.8, 0.15);
      level.playSound(null, origin.x, origin.y, origin.z, SoundEvents.BELL_RESONATE, SoundSource.HOSTILE, 1.8F, 1.5F);
      for (int i = 0; i < 6; i++) {
         double angle = RANDOM.nextDouble() * Math.PI * 2.0;
         double dist = 3.0 + RANDOM.nextDouble() * 10.0;
         double x = (target != null ? target.getX() : boss.getX()) + Math.cos(angle) * dist;
         double z = (target != null ? target.getZ() : boss.getZ()) + Math.sin(angle) * dist;
         double y = BossGrounding.groundY(level, x, z, boss.getY());
         fight.marks.add(new Mark(new Vec3(x, y, z), CROWN_RADIUS, CROWN_DAMAGE, CROWN_FUSE + i * 4));
      }
      fight.marks.add(new Mark(boss.position(), CROWN_RADIUS + 0.4, CROWN_DAMAGE, CROWN_FUSE - 6));
      announceNear(level, boss, ARENA_RADIUS, "&a&lCROWNFALL&7 - his crown breaks apart overhead. Step out of the rings!");
   }

   /** Runs every crown mark's fuse and lands it. */
   private static void tickMarks(ServerLevel level, Mob boss, Fight fight) {
      if (fight.marks.isEmpty()) {
         return;
      }
      for (Iterator<Mark> it = fight.marks.iterator(); it.hasNext();) {
         Mark mark = it.next();
         int points = 20;
         for (int i = 0; i < points; i++) {
            double a = i * (Math.PI * 2.0 / points) + mark.fuse * 0.05;
            level.sendParticles(
               ParticleTypes.HAPPY_VILLAGER,
               mark.pos.x + Math.cos(a) * mark.radius,
               mark.pos.y + 0.2,
               mark.pos.z + Math.sin(a) * mark.radius,
               1,
               0.0,
               0.0,
               0.0,
               0.0
            );
         }
         level.sendParticles(ParticleTypes.END_ROD, mark.pos.x, mark.pos.y + 1.0, mark.pos.z, 2, 0.2, 0.3, 0.2, 0.02);
         mark.fuse--;
         if (mark.fuse > 0) {
            continue;
         }
         level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, mark.pos.x, mark.pos.y + 0.5, mark.pos.z, 2, 0.4, 0.2, 0.4, 0.0);
         level.sendParticles(ParticleTypes.HAPPY_VILLAGER, mark.pos.x, mark.pos.y + 0.5, mark.pos.z, 40, 1.0, 0.8, 1.0, 0.2);
         level.playSound(null, mark.pos.x, mark.pos.y, mark.pos.z, SoundEvents.GENERIC_EXPLODE, SoundSource.HOSTILE, 1.2F, 1.4F);
         for (ServerPlayer p : playersNear(level, mark.pos.x, mark.pos.y + 0.5, mark.pos.z, mark.radius + 1.4)) {
            p.hurtServer(level, level.damageSources().mobAttack(boss), mark.damage);
            p.push(0.0, 0.45, 0.0);
            p.hurtMarked = true;
         }
         it.remove();
      }
   }

   /**
    * The Sovereign's Wheel: three green rays turning a full circle around him.
    *
    * <p>The counterplay is the shape - three arms with a gap between them, so there is
    * always somewhere to stand, and it turns slowly enough to walk around with it. The
    * rays stop at the first solid block, so a pillar, a tree or a wall breaks one arm.
    */
   private static void startWheel(ServerLevel level, Mob boss, Fight fight) {
      fight.wheelTicks = WHEEL_TICKS;
      fight.wheelAngle = RANDOM.nextDouble() * Math.PI * 2.0;
      fight.wheelHits.clear();
      announce(level, SAY + "\"\u00a7fThe whole hall turns. \u00a7aKeep up.\"");
      announceNear(level, boss, ARENA_RADIUS, "&a&lSOVEREIGN'S WHEEL&7 - three rays sweep the room. Stand in the gaps!");
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.BEACON_ACTIVATE, SoundSource.HOSTILE, 2.0F, 1.3F);
   }

   private static void tickWheel(ServerLevel level, Mob boss, Fight fight) {
      fight.wheelTicks--;
      fight.wheelAngle += WHEEL_SPIN;
      long now = ServerClock.clock(level);
      Vec3 origin = boss.position().add(0.0, boss.getBbHeight() * 0.6, 0.0);

      for (int arm = 0; arm < 3; arm++) {
         double angle = fight.wheelAngle + arm * (Math.PI * 2.0 / 3.0);
         Vec3 dir = new Vec3(Math.cos(angle), 0.0, Math.sin(angle));
         double reach = drawRay(level, origin, dir);
         for (ServerPlayer p : playersNear(level, origin.x, origin.y, origin.z, reach + 2.0)) {
            Vec3 rel = p.position().add(0.0, p.getBbHeight() * 0.5, 0.0).subtract(origin);
            if (Math.abs(rel.y) > 2.2) {
               continue;
            }
            double along = rel.x * dir.x + rel.z * dir.z;
            if (along < 0.0 || along > reach) {
               continue;
            }
            if (Math.abs(rel.x * dir.z - rel.z * dir.x) > WHEEL_HALF_WIDTH) {
               continue;
            }
            Long ready = fight.wheelHits.get(p.getUUID());
            if (ready != null && now < ready) {
               continue;
            }
            fight.wheelHits.put(p.getUUID(), now + 8L);
            fight.participants.add(p.getUUID());
            p.hurtServer(level, level.damageSources().mobAttack(boss), WHEEL_DAMAGE);
            p.push(dir.x * 0.5, 0.12, dir.z * 0.5);
            p.hurtMarked = true;
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, p.getX(), p.getY() + 1.0, p.getZ(), 8, 0.3, 0.4, 0.3, 0.05);
         }
      }

      if (fight.wheelTicks % 8 == 0) {
         level.playSound(null, origin.x, origin.y, origin.z, SoundEvents.BEACON_AMBIENT, SoundSource.HOSTILE, 1.4F, 1.7F);
      }
      if (fight.wheelTicks <= 0) {
         fight.wheelHits.clear();
         announceNear(level, boss, ARENA_RADIUS, "&aThe wheel comes to rest.");
      }
   }

   /** Draws a ray and reports how far it reached before a block stopped it. */
   private static double drawRay(ServerLevel level, Vec3 from, Vec3 dir) {
      double reach = 0.0;
      for (double d = 0.0; d < WHEEL_REACH; d += 0.5) {
         Vec3 point = from.add(dir.scale(d));
         if (!level.getBlockState(BlockPos.containing(point)).isAir()) {
            level.sendParticles(ParticleTypes.CRIT, point.x, point.y, point.z, 5, 0.2, 0.2, 0.2, 0.04);
            break;
         }
         reach = d;
         level.sendParticles(ParticleTypes.HAPPY_VILLAGER, point.x, point.y, point.z, 2, 0.1, 0.1, 0.1, 0.0);
      }
      return reach;
   }

   /**
    * Emerald Coffin: a green circle, then four walls of emerald block around whoever
    * is standing on it.
    *
    * <p>The tell is the whole move. The circle is on the ground for a second and a
    * half, it does not follow anybody, and it leaves the spot it was aimed at - so a
    * fighter who moves is never in it. Anybody still standing there when the walls
    * come up is boxed in with the court for two and a half seconds, which is a
    * sentence rather than a death sentence: the walls are emerald, and the way out is
    * the one his guards are standing in.
    */
   private static void startCoffin(ServerLevel level, Mob boss, Fight fight) {
      ServerPlayer target = nearestPlayer(boss, ARENA_RADIUS);
      if (target == null) {
         return;
      }
      fight.coffinAt = target.position();
      fight.coffinTell = COFFIN_TELL;
      announceNear(level, boss, ARENA_RADIUS, "&a&lEMERALD COFFIN&7 - do not be standing on the green.");
      level.playSound(null, target.getX(), target.getY(), target.getZ(), SoundEvents.RESPAWN_ANCHOR_CHARGE, SoundSource.HOSTILE, 1.5F, 0.8F);
   }

   private static void tickCoffin(ServerLevel level, Mob boss, Fight fight) {
      if (fight.coffinTell > 0) {
         fight.coffinTell--;
         Vec3 at = fight.coffinAt;
         for (int i = 0; i < 28; i++) {
            double a = i * (Math.PI * 2.0 / 28.0);
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, at.x + Math.cos(a) * 1.7, at.y + 0.15, at.z + Math.sin(a) * 1.7, 1, 0.0, 0.0, 0.0, 0.0);
         }
         if (fight.coffinTell > 0) {
            return;
         }
         raiseCoffin(level, boss, fight);
         return;
      }

      fight.coffinHold--;
      Vec3 at = fight.coffinAt;
      level.sendParticles(ParticleTypes.HAPPY_VILLAGER, at.x, at.y + 1.0, at.z, 10, 1.0, 1.0, 1.0, 0.05);
      if (fight.coffinHold <= 0) {
         dropCoffin(level, fight);
         announceNear(level, boss, ARENA_RADIUS, "&aThe coffin opens.");
      }
   }

   /** The walls, and the one blow they land on anybody inside. */
   private static void raiseCoffin(ServerLevel level, Mob boss, Fight fight) {
      BlockPos feet = BlockPos.containing(fight.coffinAt);
      for (int dy = 0; dy <= 2; dy++) {
         for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
               // The ring only: the middle column is the fighter, and the corners are
               // left out so the shape reads as a coffin rather than a cube.
               if (Math.abs(dx) + Math.abs(dz) != 1) {
                  continue;
               }
               BlockPos pos = feet.offset(dx, dy, dz);
               if (!level.getBlockState(pos).isAir()) {
                  continue;
               }
               level.setBlock(pos, Blocks.EMERALD_BLOCK.defaultBlockState(), 3);
               fight.coffinBlocks.add(pos);
            }
         }
      }
      fight.coffinHold = COFFIN_HOLD;
      level.sendParticles(ParticleTypes.EXPLOSION, fight.coffinAt.x, fight.coffinAt.y + 0.5, fight.coffinAt.z, 8, 0.8, 0.5, 0.8, 0.05);
      level.playSound(null, fight.coffinAt.x, fight.coffinAt.y, fight.coffinAt.z, SoundEvents.BELL_BLOCK, SoundSource.HOSTILE, 1.8F, 1.2F);
      for (ServerPlayer p : playersNear(level, fight.coffinAt.x, fight.coffinAt.y + 1.0, fight.coffinAt.z, 2.2)) {
         p.hurtServer(level, level.damageSources().mobAttack(boss), COFFIN_DAMAGE);
         p.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, COFFIN_HOLD, 0, false, true, true));
      }
      announceNear(level, boss, ARENA_RADIUS, "&a&lEMERALD COFFIN&7 - it closed.");
   }

   /**
    * Takes the walls down again.
    *
    * <p>Only blocks this move put there are removed, and only while they are still
    * emerald - a fighter who mined one out and built something in its place keeps what
    * they built.
    */
   private static void dropCoffin(ServerLevel level, Fight fight) {
      for (BlockPos pos : fight.coffinBlocks) {
         if (level.getBlockState(pos).is(Blocks.EMERALD_BLOCK)) {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
         }
      }
      fight.coffinBlocks.clear();
      fight.coffinTell = 0;
      fight.coffinHold = 0;
   }

   /** Phase two's signature: a green beam that has to be out-walked. */
   private static void startJudgement(ServerLevel level, Mob boss, Fight fight) {
      ServerPlayer target = nearestPlayer(boss, ARENA_RADIUS);
      if (target == null) {
         return;
      }
      fight.judgementOrigin = boss.position().add(0.0, boss.getBbHeight() * 0.7, 0.0);
      Vec3 dir = target.position().add(0.0, target.getBbHeight() * 0.5, 0.0).subtract(fight.judgementOrigin);
      if (dir.lengthSqr() < 1.0E-4) {
         dir = new Vec3(1.0, 0.0, 0.0);
      }
      fight.judgementDir = new Vec3(dir.x, 0.0, dir.z).normalize();
      fight.judgementCharge = 46;
      announce(level, SAY + "\"\u00a7fThen let judgement be \u00a7asimple\u00a7f.\"");
      announceNear(level, boss, ARENA_RADIUS, "&a&l\u26a0 SOVEREIGN'S JUDGEMENT&7 - move!");
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.BEACON_ACTIVATE, SoundSource.HOSTILE, 2.0F, 0.8F);
   }

   private static void tickJudgement(ServerLevel level, Mob boss, Fight fight) {
      fight.judgementCharge--;
      Vec3 origin = fight.judgementOrigin;
      Vec3 dir = fight.judgementDir;
      int elapsed = 46 - fight.judgementCharge;

      if (elapsed < 20) {
         // Telegraph: a growing line on the floor where the beam will land.
         for (double d = 0; d < 32; d += 1.0) {
            Vec3 point = origin.add(dir.scale(d));
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, point.x, boss.getY() + 0.2, point.z, 1, 0.05, 0.0, 0.05, 0.0);
         }
         boss.addEffect(new MobEffectInstance(MobEffects.GLOWING, 8, 0, false, false, false));
         if (fight.judgementCharge > 0) {
            return;
         }
      }

      // Fire: a proper beam with a knockback and a burn, drawn thick.
      for (double d = 0; d < 34; d += 0.6) {
         Vec3 point = origin.add(dir.scale(d));
         level.sendParticles(ParticleTypes.HAPPY_VILLAGER, point.x, point.y, point.z, 3, 0.25, 0.25, 0.25, 0.0);
         level.sendParticles(ParticleTypes.END_ROD, point.x, point.y, point.z, 1, 0.1, 0.1, 0.1, 0.0);
      }
      level.playSound(null, origin.x, origin.y, origin.z, SoundEvents.BEACON_DEACTIVATE, SoundSource.HOSTILE, 1.6F, 1.6F);

      for (ServerPlayer p : participantsNear(level, boss, 34.0)) {
         Vec3 rel = p.position().add(0.0, p.getBbHeight() * 0.5, 0.0).subtract(origin);
         double along = rel.x * dir.x + rel.z * dir.z;
         if (along < 0.0 || along > 34.0) {
            continue;
         }
         double lateral = Math.abs(rel.x * dir.z - rel.z * dir.x);
         if (lateral > 2.2) {
            continue;
         }
         p.hurtServer(level, level.damageSources().mobAttack(boss), 20.0F);
         Vec3 push = new Vec3(dir.z, 0.0, -dir.x).scale(lateral >= 0 ? 1.4 : -1.4);
         p.push(push.x, 0.5, push.z);
         p.hurtMarked = true;
         p.igniteForTicks(60);
      }
      fight.judgementCharge = 0;
   }

   private static void tickEmeralds(ServerLevel level, Mob boss, Fight fight) {
      if (fight.emeralds.isEmpty()) {
         return;
      }
      for (Iterator<Emerald> it = fight.emeralds.iterator(); it.hasNext();) {
         Emerald emerald = it.next();
         int steps = 3;
         boolean done = false;
         for (int s = 0; s < steps && !done; s++) {
            emerald.pos = emerald.pos.add(emerald.vel.scale(1.0 / steps));
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, emerald.pos.x, emerald.pos.y, emerald.pos.z, 1, 0.05, 0.05, 0.05, 0.0);
            if (!level.getBlockState(BlockPos.containing(emerald.pos)).isAir()) {
               done = true;
               break;
            }
            for (ServerPlayer p : playersNear(level, emerald.pos.x, emerald.pos.y, emerald.pos.z, 1.1)) {
               p.hurtServer(level, level.damageSources().mobAttack(boss), emerald.damage);
               done = true;
               break;
            }
         }
         emerald.life--;
         if (done || emerald.life <= 0) {
            level.sendParticles(ParticleTypes.CRIT, emerald.pos.x, emerald.pos.y, emerald.pos.z, 6, 0.2, 0.2, 0.2, 0.05);
            it.remove();
         }
      }
   }

   // --------------------------------------------------------------------- phase

   private static void enterPhase(ServerLevel level, Mob boss, Fight fight, int phase) {
      fight.phase = phase;
      summonGuards(level, boss, fight, 4);
      announce(level, SAY + "\"\u00a7fEnough courtesy. \u00a7aThe throne takes over.\"");
      announceNear(level, boss, ARENA_RADIUS, "&a&lTHE THRONE&7 - the court doubles and leads with steel.");
      level.sendParticles(ColorParticleOption.create(ParticleTypes.FLASH, 0x55FF88), boss.getX(), boss.getY() + 1.5, boss.getZ(), 1, 0.0, 0.0, 0.0, 0.0);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.BELL_RESONATE, SoundSource.HOSTILE, 2.0F, 0.5F);
   }

   private static void taunt(ServerLevel level, Mob boss, Fight fight) {
      String line = switch (fight.guards.isEmpty() ? 3 : fight.phase) {
         case 1 -> "\u00a78\u201c\u00a7fYou are negotiating with the wrong man.\u00a78\u201d";
         case 2 -> "\u00a78\u201c\u00a7fA crown is a promise that someone else will bleed.\u00a78\u201d";
         default -> "\u00a78\u201c\u00a7fWhere is my court? \u00a7f...oh. You did that.\u00a78\u201d";
      };
      announce(level, SAY + "\"" + line + "\"");
   }

   // -------------------------------------------------------------- lethal blow

   public static Boolean onLethalDamage(Entity entity, float amount) {
      if (!isSovereign(entity) || !(entity instanceof Mob boss) || !(boss.level() instanceof ServerLevel level)) {
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
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.BEACON_DEACTIVATE, SoundSource.HOSTILE, 2.0F, 0.9F);
      announce(level, SAY + "\"\u00a7f...dismissed. \u00a7fAll of it, \u00a7adismissed\u00a7f.\"");
      // FALSE cancels the blow so the death ceremony - and its loot - runs.
      return Boolean.FALSE;
   }

   private static void tickDeath(MinecraftServer server, Mob boss, Fight fight) {
      ServerLevel level = (ServerLevel) boss.level();
      fight.deathTicks--;

      // The court kneels as he falls.
      if (fight.deathTicks % 10 == 0 && !fight.guards.isEmpty()) {
         UUID id = fight.guards.iterator().next();
         fight.guards.remove(id);
         Entity raw = findEntity(server, id);
         if (raw != null) {
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, raw.getX(), raw.getY() + 0.8, raw.getZ(), 24, 0.4, 0.5, 0.4, 0.08);
            level.sendParticles(ParticleTypes.LARGE_SMOKE, raw.getX(), raw.getY() + 0.8, raw.getZ(), 14, 0.4, 0.4, 0.4, 0.05);
            raw.discard();
         }
      }

      level.sendParticles(ParticleTypes.HAPPY_VILLAGER, boss.getX(), boss.getY() + 1.5, boss.getZ(), 14, 1.4, 1.2, 1.4, 0.1);
      level.sendParticles(ParticleTypes.NOTE, boss.getX(), boss.getY() + 1.0, boss.getZ(), 10, 1.0, 0.8, 1.0, 0.1);

      if (fight.deathTicks > 0) {
         return;
      }
      for (UUID id : new ArrayList<>(fight.guards)) {
         Entity raw = findEntity(server, id);
         if (raw != null) {
            raw.discard();
         }
      }
      fight.guards.clear();
      fight.emeralds.clear();
      // A coffin left standing because the king died inside it would be a wall in the
      // arena forever, so his death takes it down with him.
      dropCoffin(level, fight);
      fight.marks.clear();
      fight.wheelTicks = 0;

      level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, boss.getX(), boss.getY() + 1.0, boss.getZ(), 6, 1.5, 1.5, 1.5, 0.1);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), ModSounds.BOSS_DEATH, SoundSource.HOSTILE, 2.0F, 0.9F);
      boss.setNoAi(false);
      onBossDeath(level, boss);
      boss.hurtServer(level, level.damageSources().generic(), boss.getMaxHealth() * 4.0F + 100.0F);
   }

   // --------------------------------------------------------------------- loot

   /**
    * The body could not be found this tick.
    *
    * <p>Ten seconds, then the fight is over. The window is what separates the two causes: a
    * boss standing in an unloaded chunk comes back the moment somebody walks near him, and
    * ending the fight for that is how a boss "just dies" while the player is still reading the
    * announcement - but a body that has been nowhere for ten seconds is a body that is not
    * coming back, and leaving the fight running would leave a boss bar over an empty room.
    *
    * <p>When it is really gone, the payout happens here, at the last place he was standing,
    * rather than being lost with him. That is the difference between "he died and dropped
    * nothing" and "he died": whatever route took him out - a command, a plugin, the void, a
    * crash - the fight he was in pays what it promised.
    */
   private static void tickMissing(MinecraftServer server, Fight fight) {
      fight.missingTicks++;
      if (fight.missingTicks < MISSING_GRACE_TICKS) {
         return;
      }
      if (fight.everSeen && !fight.dying && fight.lastSeenLevel != null && !LOOT_PAID.contains(fight.bossId)) {
         LOOT_PAID.add(fight.bossId);
         ServerLevel level = fight.lastSeenLevel;
         announce(level, "&7His court is empty and the hall is quiet - he is gone, and what he owed is on the floor where he stood.");
         grantLoot(level, fight, fight.lastSeenX, fight.lastSeenY, fight.lastSeenZ);
      }
      shutDown(server, fight);
   }

   private static void grantLoot(ServerLevel level, Mob boss, Fight fight) {
      grantLoot(level, fight, boss.getX(), boss.getY(), boss.getZ());
   }

   /**
    * The payout at a place instead of on a body - see {@link #tickMissing}.
    *
    * <p>Its own method rather than a branch inside the one above, because the two callers ask
    * the same question with different answers available: a death ceremony has a body to drop
    * from, and a boss that left the world has only the last place it stood.
    */
   private static void grantLoot(ServerLevel level, Fight fight, double x, double y, double z) {
      dropAt(level, x, y, z, ModItems.sovereignTrophy());
      // Forge material only: the emeralds, gold and bread that used to fall with
      // it were padding, and padding is what makes a boss feel like a piñata.
      int tribute = 2 + RANDOM.nextInt(3);
      for (int i = 0; i < tribute; i++) {
         dropAt(level, x, y, z, ModItems.royalTribute());
      }
      // His own levy: the tax the crown collects from everything it strikes.
      if (RANDOM.nextFloat() < 0.15F) {
         dropAt(level, x, y, z, CustomEnchantments.tome(CustomEnchantments.LEVY, 1 + RANDOM.nextInt(3)));
      }

      // One roll at one legendary instead of the whole court's regalia.
      if (RANDOM.nextFloat() < 0.2F) {
         dropAt(level, x, y, z, switch (RANDOM.nextInt(3)) {
            case 0 -> ModItems.royalContract();
            case 1 -> ModItems.sovereignsBell();
            default -> ModItems.emeraldSeal();
         });
      }

      BossPayout.payBoxes(level, fight.participants, ModItems::sovereignLootBox, BossPayout.BOXES_PER_KILL, "\u00a7aSovereign Loot Box");
      for (UUID id : fight.participants) {
         ServerPlayer p = level.getServer().getPlayerList().getPlayer(id);
         if (p != null && p.isAlive()) {
            Advancements.grant(p, "kill_sovereign");
         }
      }
      announce(level, "\u00a72\u00a7m\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550");
      announce(level, "    \u00a7a\u00a7l\ud83d\udc51 THE EMERALD SOVEREIGN ABDICATES \ud83d\udc51");
      announce(level, "\u00a72\u00a7m\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550");
   }

   private static void dropAt(ServerLevel level, double x, double y, double z, ItemStack stack) {
      if (stack != null && !stack.isEmpty()) {
         level.addFreshEntity(new ItemEntity(level, x, y + 0.6, z, stack));
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
         if (boss.level() instanceof ServerLevel level) {
            Safe.run("sovereign coffin teardown", () -> dropCoffin(level, fight));
         }
         boss.discard();
      }
      for (UUID id : new ArrayList<>(fight.guards)) {
         Entity raw = findEntity(server, id);
         if (raw != null) {
            raw.discard();
         }
      }
      fight.guards.clear();
      fight.emeralds.clear();
      release(server, fight);
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
      if (!BossChat.allowed("sovereign", message)) {
         return;
      }
      for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
         Chat.raw(p, message);
      }
   }

   private static void announceNear(ServerLevel level, Mob boss, double range, String message) {
      if (!BossChat.allowed("sovereign", message)) {
         return;
      }
      double r2 = range * range;
      for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
         if (p.level() == boss.level() && p.distanceToSqr(boss) <= r2) {
            Chat.raw(p, message);
         }
      }
   }

   /** Test hook: guards held at once. */
   public static int guardCap() {
      return GUARD_CAP;
   }

   /** Test hook: how long the coffin's walls hold once they are up. */
   public static int coffinHoldTicks() {
      return COFFIN_HOLD;
   }

   /** Test hook: how long the coffin is telegraphed on the ground before it closes. */
   public static int coffinTellTicks() {
      return COFFIN_TELL;
   }

   /** Test hook: how long the wheel spins. */
   public static int wheelTicks() {
      return WHEEL_TICKS;
   }

   /** Test hook: his health, so no future tuning can quietly make him unkillable. */
   public static double maxHealth() {
      return MAX_HEALTH;
   }

   /** Test hook: the most Resistance his court can ever be worth. */
   public static int maxCourtTiers() {
      return MAX_COURT_TIERS;
   }
}
