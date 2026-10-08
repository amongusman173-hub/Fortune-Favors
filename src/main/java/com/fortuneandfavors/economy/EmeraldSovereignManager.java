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
 *   <li><b>I - Court</b>: Bell Toll, Emerald Volley, Royal Decree (guards), Crownfall,
 *       Royal Tribute, <b>Kneel</b> (jump the third bell) and <b>Appraisal</b> (break
 *       line of sight).</li>
 *   <li><b>II - The Throne</b> (50%): he stops holding back. Guards arrive in
 *       numbers, he empowers them, and <b>Sovereign's Judgement</b> - a green beam
 *       that has to be walked out of - joins the rotation with the Wheel and the Coffin.</li>
 * </ul>
 *
 * <h2>Arrival and abdication</h2>
 * He comes down out of the air onto a turning sigil, untouchable until his feet are on
 * the floor, and only then does the court assemble. He dies the same way he fought: the
 * bell tolls for him, his guards kneel one by one, and the crown goes last.
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

   /** Judgement: how long the line sits on the floor before the beam fires, and how far it goes. */
   private static final int JUDGEMENT_WARN = 30;
   private static final double JUDGEMENT_REACH = 34.0;
   /** The most a single Royal Tribute may heal him, however many people it catches. */
   private static final float TRIBUTE_HEAL_CAP = 15.0F;

   /**
    * Kneel: three bells, fifteen ticks apart, and the floor slams on the third. A jump
    * keeps a player airborne for about ten ticks, so the window is generous but real.
    */
   private static final int KNEEL_COOLDOWN = 420;
   private static final int KNEEL_BEAT = 15;
   private static final int KNEEL_WARN = KNEEL_BEAT * 3;
   private static final double KNEEL_RADIUS = 14.0;
   private static final float KNEEL_DAMAGE = 12.0F;
   /** Appraisal: how long he weighs a target before the lance, and what it costs them. */
   private static final int APPRAISAL_COOLDOWN = 320;
   private static final int APPRAISAL_WARN = 40;
   private static final float APPRAISAL_DAMAGE = 14.0F;

   /** The arrival: how long he takes to come down, and from how high. */
   private static final int ARRIVAL_TICKS = 50;
   private static final double ARRIVAL_DROP = 6.0;

   /** His colours: emerald for the work, gold for the crown, deep green for the shadow under it. */
   private static final int EMERALD = 0x3BE07A;
   private static final int CROWN_GOLD = 0xF2C94C;
   private static final int DEEP_GREEN = 0x0E6B3A;

   private static final int GUARD_CAP = 6;
   private static final int DEATH_CEREMONY_TICKS = 80;
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
      /** When Kneel and Appraisal may next be used. */
      long nextKneel;
      long nextAppraisal;
      /** Kneels and appraisals that are marked and have not landed yet. */
      final List<Strike> strikes = new ArrayList<>();
      /**
       * The arrival: ticks left of his descent, and the floor he is coming down onto.
       * While it runs he is untouchable and does nothing but arrive.
       */
      int arrivalTicks;
      Vec3 arrivalFloor;
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
      /** Set once the falling piece has been drawn, so it is drawn once. */
      boolean falling;

      Mark(Vec3 pos, double radius, float damage, int fuse) {
         this.pos = pos;
         this.radius = radius;
         this.damage = damage;
         this.fuse = fuse;
      }
   }

   /**
    * A blow that is announced before it lands: a Kneel counting down under his court, or
    * an Appraisal weighing one player. Marked first and landed later, so every one of them
    * is a warning before it is a hit.
    */
   private static final class Strike {
      static final int KNEEL = 0;
      static final int APPRAISAL = 1;
      final int kind;
      final Vec3 at;
      final long landAt;
      final double radius;
      final float damage;
      /** The player an Appraisal is weighing; null for a Kneel. */
      final UUID target;
      /** Bells already rung for a Kneel. */
      int beats;
      boolean charged;

      Strike(int kind, Vec3 at, long landAt, double radius, float damage, UUID target) {
         this.kind = kind;
         this.at = at;
         this.landAt = landAt;
         this.radius = radius;
         this.damage = damage;
         this.target = target;
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
      // He lands a couple of blocks in front of whoever put the crown on, not inside them -
      // unless that spot is a wall, in which case he takes their place.
      Vec3 look = summoner.getLookAngle();
      Vec3 flat = new Vec3(look.x, 0.0, look.z);
      Vec3 floor = summoner.position();
      if (flat.lengthSqr() > 1.0E-4) {
         Vec3 ahead = floor.add(flat.normalize().scale(3.0));
         BlockPos feet = BlockPos.containing(ahead);
         if (level.getBlockState(feet).isAir() && level.getBlockState(feet.above()).isAir() && level.getBlockState(feet.above(2)).isAir()
               && !level.getBlockState(feet.below()).isAir()) {
            floor = ahead;
         }
      }
      // He comes down from as high as the room allows, up to ARRIVAL_DROP.
      double drop = 0.0;
      BlockPos column = BlockPos.containing(floor);
      while (drop < ARRIVAL_DROP && level.getBlockState(column.above(4 + (int) drop)).isAir()) {
         drop += 1.0;
      }
      boss.setPos(floor.x, floor.y + drop, floor.z);
      boss.setYRot(summoner.getYRot() + 180.0F);
      // Untouchable until his feet are on the floor: the arrival is a cutscene, not a window.
      boss.setInvulnerable(true);
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
      // Every opening timer starts after he has landed, so nothing fires mid-descent.
      long now = ServerClock.clock(level) + ARRIVAL_TICKS;
      fight.nextBell = now + 120L;
      fight.nextVolley = now + 60L;
      fight.nextDecree = now + 200L;
      fight.nextTribute = now + 360L;
      fight.nextTaunt = now + 140L;
      fight.nextCrown = now + 180L;
      fight.nextAppraisal = now + 260L;
      fight.nextKneel = now + 420L;
      fight.nextCoffin = now + 520L;
      fight.nextWheel = now + 700L;
      fight.arrivalTicks = drop > 0.0 ? ARRIVAL_TICKS : 1;
      fight.arrivalFloor = floor;
      FIGHTS.put(boss.getUUID(), fight);

      announce(level, "\u00a72\u00a7m\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550");
      announce(level, "    \u00a7a\u00a7l\ud83d\udc51 THE EMERALD SOVEREIGN HOLDS COURT \ud83d\udc51");
      announce(level, "    \u00a77A bell. Then he comes down.");
      announce(level, "    \u00a78\u201c\u00a7fI have \u00a7apeople\u00a7f for this.\u00a78\u201d");
      announce(level, "    \u00a78Break his guards. \u00a77His court is his armour.");
      announce(level, "\u00a72\u00a7m\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550");
      // The throne is drawn before he reaches it: a summoning circle where he will land, a
      // ring of gold sigils round that, a shaft of light from above and a gold helix he
      // comes down through.
      Fx.summonCircle(level, ParticleTypes.HAPPY_VILLAGER, floor.add(0.0, 0.05, 0.0), 4.5, ARRIVAL_TICKS, EMERALD);
      Fx.runeCircle(level, ParticleTypes.END_ROD, floor.add(0.0, 0.08, 0.0), 6.5, ARRIVAL_TICKS + 10, CROWN_GOLD);
      Fx.pillar(level, ParticleTypes.END_ROD, floor, drop + 8.0, EMERALD);
      Fx.spiral(level, ParticleTypes.HAPPY_VILLAGER, floor, drop + 3.0, ARRIVAL_TICKS, CROWN_GOLD);
      level.playSound(null, floor.x, floor.y, floor.z, ModSounds.BOSS_SPAWN, SoundSource.HOSTILE, 1.3F, 0.9F);
      level.playSound(null, floor.x, floor.y, floor.z, SoundEvents.BELL_BLOCK, SoundSource.HOSTILE, 1.8F, 0.7F);
      level.playSound(null, floor.x, floor.y, floor.z, SoundEvents.BEACON_ACTIVATE, SoundSource.HOSTILE, 1.6F, 0.6F);
      Advancements.grant(summoner, "summon_sovereign");
      return null;
   }

   /**
    * The descent: he comes down through the helix onto the sigil, a bell for every ten
    * ticks of it, each one higher than the last. He is untouchable the whole way down.
    *
    * <p>On landing the room gets the message - a flare, a gold starburst and a shockwave
    * that shoves anyone standing on the sigil off it without hurting them - and the court
    * assembles round him. Only then does the fight start.
    */
   private static void tickArrival(ServerLevel level, Mob boss, Fight fight) {
      fight.arrivalTicks--;
      Vec3 floor = fight.arrivalFloor != null ? fight.arrivalFloor : boss.position();
      double height = ARRIVAL_DROP * fight.arrivalTicks / (double) ARRIVAL_TICKS;
      double y = Math.min(boss.getY(), floor.y + height);
      boss.setPos(floor.x, Math.max(floor.y, y), floor.z);
      boss.setDeltaMovement(Vec3.ZERO);
      vanillaOnly(() -> level.sendParticles(ParticleTypes.HAPPY_VILLAGER, boss.getX(), boss.getY() + 1.4, boss.getZ(), 4, 0.6, 1.0, 0.6, 0.02));
      if (fight.arrivalTicks > 0 && fight.arrivalTicks % 10 == 0) {
         float pitch = 0.6F + (ARRIVAL_TICKS - fight.arrivalTicks) * 0.012F;
         level.playSound(null, floor.x, floor.y, floor.z, SoundEvents.BELL_BLOCK, SoundSource.HOSTILE, 1.4F, pitch);
         Fx.ring(level, ParticleTypes.END_ROD, floor.add(0.0, 0.1, 0.0), 2.0 + fight.arrivalTicks * 0.08, CROWN_GOLD);
      }
      if (fight.arrivalTicks > 0) {
         return;
      }

      boss.setPos(floor.x, floor.y, floor.z);
      boss.setInvulnerable(false);
      Vec3 head = floor.add(0.0, boss.getBbHeight() * 0.8, 0.0);
      Fx.flare(level, ParticleTypes.END_ROD, head, 2.6, CROWN_GOLD);
      Fx.starburst(level, ParticleTypes.HAPPY_VILLAGER, head, 6.5, EMERALD);
      Fx.shockwave(level, ParticleTypes.HAPPY_VILLAGER, floor, 11.0, EMERALD);
      Fx.nova(level, ParticleTypes.END_ROD, floor.add(0.0, 0.2, 0.0), 5.0, DEEP_GREEN);
      level.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, head.x, head.y, head.z, 50, 1.2, 1.0, 1.2, 0.25);
      level.playSound(null, floor.x, floor.y, floor.z, SoundEvents.MACE_SMASH_GROUND_HEAVY, SoundSource.HOSTILE, 1.6F, 0.7F);
      level.playSound(null, floor.x, floor.y, floor.z, SoundEvents.BELL_RESONATE, SoundSource.HOSTILE, 1.8F, 0.8F);
      // Off the throne: a shove, not a hit - nobody is punished for standing where he landed.
      for (ServerPlayer p : playersNear(level, floor.x, floor.y, floor.z, 4.5)) {
         Vec3 away = new Vec3(p.getX() - floor.x, 0.0, p.getZ() - floor.z);
         away = away.lengthSqr() < 1.0E-4 ? new Vec3(1.0, 0.0, 0.0) : away.normalize();
         p.push(away.x * 0.9, 0.35, away.z * 0.9);
         p.hurtMarked = true;
      }
      summonGuards(level, boss, fight, 2);
      announce(level, SAY + "\"\u00a7fCourt's in session.\"");
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
         // Spectators and the mod's puppet bodies watch; they do not get paid for it.
         if (p.level() == level && p.isAlive() && !p.isSpectator() && !BossManager.isFakePlayer(p)
               && p.distanceToSqr(boss) < ARENA_RADIUS * ARENA_RADIUS) {
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

      if (fight.arrivalTicks > 0) {
         tickArrival(level, boss, fight);
         return;
      }

      tickEmeralds(level, boss, fight);

      float share = boss.getHealth() / boss.getMaxHealth();
      if (share <= 0.5F && fight.phase < 2) {
         enterPhase(level, boss, fight, 2);
      }

      applyCourt(level, boss, fight);
      // Crownfall keeps falling while he does other things, so its marks are ticked
      // here rather than from inside the move.
      tickMarks(level, boss, fight);
      // So do kneels and appraisals: they are marked, and land on their own clock.
      tickStrikes(level, boss, fight, now);

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
         if (raw != null && raw.level() == level) {
            Vec3 at = raw.position().add(0.0, 0.9, 0.0);
            Fx.shatter(level, ParticleTypes.HAPPY_VILLAGER, at, 1.0, EMERALD);
            Fx.ring(level, ParticleTypes.END_ROD, raw.position().add(0.0, 0.1, 0.0), 1.4, DEEP_GREEN);
            vanillaOnly(() -> level.sendParticles(ParticleTypes.LARGE_SMOKE, at.x, at.y, at.z, 12, 0.4, 0.4, 0.4, 0.06));
            level.playSound(null, raw.getX(), raw.getY(), raw.getZ(), SoundEvents.VINDICATOR_CELEBRATE, SoundSource.HOSTILE, 1.0F, 0.8F);
         }
         announceNear(level, boss, ARENA_RADIUS, fight.guards.isEmpty()
            ? "&8The court is empty. &7He's open."
            : "&8A guard falls. &f" + fight.guards.size() + "&7 left.");
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
         Vec3 spot = new Vec3(boss.getX() + Math.cos(angle) * radius, boss.getY() + 0.2, boss.getZ() + Math.sin(angle) * radius);
         // A guard is never summoned into a wall to suffocate: no room there, he stands at the throne.
         BlockPos feet = BlockPos.containing(spot);
         if (!level.getBlockState(feet).isAir() || !level.getBlockState(feet.above()).isAir()) {
            spot = boss.position().add(0.0, 0.2, 0.0);
         }
         guard.setPos(spot.x, spot.y, spot.z);
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
         // Each guard steps out of a small green circle under a shaft of gold.
         Fx.summonCircle(level, ParticleTypes.HAPPY_VILLAGER, guard.position().add(0.0, 0.05, 0.0), 1.2, 20, EMERALD);
         Fx.pillar(level, ParticleTypes.END_ROD, guard.position(), 4.0, CROWN_GOLD);
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
      if (now >= fight.nextKneel) {
         // On the throne the bells come round faster.
         fight.nextKneel = now + KNEEL_COOLDOWN - (fight.phase >= 2 ? 100L : 0L);
         startKneel(level, boss, fight, now);
         return;
      }
      if (now >= fight.nextAppraisal) {
         fight.nextAppraisal = now + APPRAISAL_COOLDOWN;
         startAppraisal(level, boss, fight, now);
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

   /**
    * The bell: staggers everyone within 22 blocks, and stirs the court to fight harder.
    *
    * <p>No damage, so no wind-up: the answer is distance, and the shockwave drawn out to
    * exactly the edge of its reach is how a player learns where that is.
    */
   private static void bellToll(ServerLevel level, Mob boss, Fight fight) {
      double x = boss.getX();
      double y = boss.getY() + 1.0;
      double z = boss.getZ();
      level.playSound(null, x, y, z, SoundEvents.BELL_BLOCK, SoundSource.HOSTILE, 2.0F, 0.7F);
      level.playSound(null, x, y, z, SoundEvents.BELL_RESONATE, SoundSource.HOSTILE, 1.6F, 0.6F);
      Vec3 head = boss.position().add(0.0, boss.getBbHeight() * 0.85, 0.0);
      Fx.resonance(level, ParticleTypes.NOTE, head, 20.0, CROWN_GOLD);
      Fx.shockwave(level, ParticleTypes.HAPPY_VILLAGER, boss.position(), 22.0, EMERALD);
      Fx.ring(level, ParticleTypes.END_ROD, boss.position().add(0.0, 0.1, 0.0), 11.0, DEEP_GREEN);
      for (ServerPlayer p : participantsNear(level, boss, 22.0)) {
         p.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 80, 2, false, true, true));
         p.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 80, 0, false, true, true));
         level.sendParticles(ParticleTypes.NOTE, p.getX(), p.getY() + 2.0, p.getZ(), 10, 0.4, 0.4, 0.4, 0.1);
      }
      announce(level, SAY + "\"§fOrder.\"");
      announceNear(level, boss, ARENA_RADIUS, "&8BELL TOLL &7- slowed. Distance beats it.");
   }

   private static void emeraldVolley(ServerLevel level, Mob boss, Fight fight) {
      ServerPlayer target = nearestPlayer(boss, ARENA_RADIUS);
      if (target == null) {
         return;
      }
      int count = 4 + fight.guards.size();
      Vec3 from = boss.position().add(0.0, boss.getBbHeight() * 0.7, 0.0);
      Vec3 to = target.position().add(0.0, target.getBbHeight() * 0.5, 0.0);
      Vec3 dir = to.subtract(from);
      if (dir.lengthSqr() < 1.0E-4) {
         dir = new Vec3(0.0, 0.0, 1.0);
      }
      dir = dir.normalize();
      for (int i = 0; i < count; i++) {
         double spread = (i - (count - 1) / 2.0) * 0.05;
         Vec3 vel = new Vec3(dir.x + spread, dir.y + 0.08, dir.z + spread).normalize().scale(0.9);
         fight.emeralds.add(new Emerald(from, vel, 6.0F, 100));
      }
      Fx.muzzle(level, ParticleTypes.HAPPY_VILLAGER, from, dir, EMERALD);
      level.playSound(null, from.x, from.y, from.z, SoundEvents.VILLAGER_TRADE, SoundSource.HOSTILE, 1.2F, 0.6F);
   }

   /**
    * Royal Tribute: drags a tithe of health out of everyone within 18 blocks - never items.
    *
    * <p>Each tithe is a gold chain drawn from the player to him, so it is obvious who paid
    * and why; the answer is not to be that close when it comes round. What he banks from
    * it is capped per cast, so a crowd standing next to him cannot out-heal itself.
    */
   private static void royalTribute(ServerLevel level, Mob boss, Fight fight) {
      double x = boss.getX();
      double y = boss.getY() + 1.0;
      double z = boss.getZ();
      Vec3 chest = boss.position().add(0.0, boss.getBbHeight() * 0.6, 0.0);
      int taken = 0;
      float healed = 0.0F;
      for (ServerPlayer p : participantsNear(level, boss, 18.0)) {
         if (p.distanceToSqr(boss) < 1.0) {
            continue;
         }
         Fx.chains(level, ParticleTypes.HAPPY_VILLAGER, p.position().add(0.0, 1.0, 0.0), chest, CROWN_GOLD);
         // A tithe of vitality, not of property: he banks a little healing, and
         // the player goes hungry - but nothing leaves an inventory.
         p.addEffect(new MobEffectInstance(MobEffects.HUNGER, 120, 1, false, true, true));
         p.hurtServer(level, level.damageSources().mobAttack(boss), 4.0F);
         // A modest tithe, not a lifeline: 5 a head, and never more than the cap a cast.
         float heal = Math.min(5.0F, TRIBUTE_HEAL_CAP - healed);
         if (heal > 0.0F) {
            boss.heal(heal);
            healed += heal;
         }
         taken++;
      }
      if (taken > 0) {
         Fx.aura(level, ParticleTypes.TOTEM_OF_UNDYING, boss.position(), boss.getBbHeight(), 30, CROWN_GOLD);
      }
      level.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, x, y, z, 20, 1.6, 1.2, 1.6, 0.1);
      level.playSound(null, x, y, z, SoundEvents.BELL_RESONATE, SoundSource.HOSTILE, 1.4F, 1.2F);
      announce(level, SAY + "\"§fTax day.\"");
      announceNear(level, boss, ARENA_RADIUS, taken == 0
         ? "&8Nobody close enough to tax."
         : "&8He took his cut from &f" + taken + "&8. &7Stay out of reach.");
   }

   private static void royalDecree(ServerLevel level, Mob boss, Fight fight) {
      summonGuards(level, boss, fight, fight.phase >= 2 ? 3 : 2);
      Fx.runeCircle(level, ParticleTypes.HAPPY_VILLAGER, boss.position().add(0.0, 0.05, 0.0), 6.0, 30, EMERALD);
      Fx.flare(level, ParticleTypes.END_ROD, boss.position().add(0.0, boss.getBbHeight() * 0.9, 0.0), 1.6, CROWN_GOLD);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.RAVAGER_ROAR, SoundSource.HOSTILE, 1.2F, 1.6F);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.RAID_HORN, SoundSource.HOSTILE, 0.9F, 1.2F);
      announce(level, SAY + "\"§fGuards!\"");
      announceNear(level, boss, ARENA_RADIUS, "&8More guards. &7Thin them out.");
   }

   /**
    * Crownfall: he throws the crown off his own head and it comes down in pieces.
    *
    * <p>Seven impacts - six thrown around whoever is nearest, and one on himself,
    * because a king who will not stand where his crown is falling is a king who has
    * not committed. Each one is a ring of sigils on the ground for the length of its
    * fuse, and the piece is seen falling for its last few ticks, so the answer is
    * movement, and the punishment for standing still is being under it when it lands.
    */
   private static void crownfall(ServerLevel level, Mob boss, Fight fight) {
      Vec3 origin = boss.position().add(0.0, boss.getBbHeight() + 1.4, 0.0);
      ServerPlayer target = nearestPlayer(boss, ARENA_RADIUS);
      Fx.flare(level, ParticleTypes.END_ROD, origin, 1.8, CROWN_GOLD);
      Fx.starburst(level, ParticleTypes.TOTEM_OF_UNDYING, origin, 4.0, CROWN_GOLD);
      level.playSound(null, origin.x, origin.y, origin.z, SoundEvents.BELL_RESONATE, SoundSource.HOSTILE, 1.8F, 1.5F);
      for (int i = 0; i < 6; i++) {
         double angle = RANDOM.nextDouble() * Math.PI * 2.0;
         double dist = 3.0 + RANDOM.nextDouble() * 10.0;
         double x = (target != null ? target.getX() : boss.getX()) + Math.cos(angle) * dist;
         double z = (target != null ? target.getZ() : boss.getZ()) + Math.sin(angle) * dist;
         double y = BossGrounding.groundY(level, x, z, target != null ? target.getY() : boss.getY());
         addMark(level, fight, new Mark(new Vec3(x, y, z), CROWN_RADIUS, CROWN_DAMAGE, CROWN_FUSE + i * 4));
      }
      addMark(level, fight, new Mark(boss.position(), CROWN_RADIUS + 0.4, CROWN_DAMAGE, CROWN_FUSE - 6));
      announce(level, SAY + "\"§fMind your heads.\"");
      announceNear(level, boss, ARENA_RADIUS, "&8CROWNFALL &7- get out of the rings.");
   }

   /** Marks one piece's landing spot with a sigil that lasts exactly as long as its fuse. */
   private static void addMark(ServerLevel level, Fight fight, Mark mark) {
      fight.marks.add(mark);
      Fx.runeCircle(level, ParticleTypes.HAPPY_VILLAGER, mark.pos.add(0.0, 0.05, 0.0), mark.radius, mark.fuse, CROWN_GOLD);
   }

   /** Runs every crown mark's fuse and lands it. */
   private static void tickMarks(ServerLevel level, Mob boss, Fight fight) {
      if (fight.marks.isEmpty()) {
         return;
      }
      for (Iterator<Mark> it = fight.marks.iterator(); it.hasNext();) {
         Mark mark = it.next();
         // The rune circle is the warning for modded clients; everyone else gets the ring
         // in plain particles, every other tick.
         if (mark.fuse % 2 == 0) {
            vanillaOnly(() -> {
               int points = 16;
               for (int i = 0; i < points; i++) {
                  double a = i * (Math.PI * 2.0 / points) + mark.fuse * 0.05;
                  level.sendParticles(ParticleTypes.HAPPY_VILLAGER, mark.pos.x + Math.cos(a) * mark.radius, mark.pos.y + 0.2,
                     mark.pos.z + Math.sin(a) * mark.radius, 1, 0.0, 0.0, 0.0, 0.0);
               }
            });
         }
         mark.fuse--;
         if (!mark.falling && mark.fuse <= 6) {
            mark.falling = true;
            Fx.comet(level, ParticleTypes.END_ROD, mark.pos.add(0.0, 14.0, 0.0), mark.pos.add(0.0, 0.5, 0.0), Math.max(1, mark.fuse), CROWN_GOLD);
         }
         if (mark.fuse > 0) {
            continue;
         }
         Fx.shockwave(level, ParticleTypes.HAPPY_VILLAGER, mark.pos, mark.radius + 0.6, EMERALD);
         Fx.rockburst(level, ParticleTypes.TOTEM_OF_UNDYING, mark.pos.add(0.0, 0.4, 0.0), 1.2, CROWN_GOLD);
         level.sendParticles(ParticleTypes.EXPLOSION, mark.pos.x, mark.pos.y + 0.5, mark.pos.z, 2, 0.4, 0.2, 0.4, 0.0);
         level.playSound(null, mark.pos.x, mark.pos.y, mark.pos.z, SoundEvents.GENERIC_EXPLODE, SoundSource.HOSTILE, 1.2F, 1.4F);
         // The hit matches the ring that was drawn: a player whose feet are outside it is
         // safe. This used to reach 1.4 blocks past the drawn edge, in all three axes.
         double reach = mark.radius + 0.4;
         for (ServerPlayer p : playersNear(level, mark.pos.x, mark.pos.y, mark.pos.z, reach + 3.0)) {
            double dx = p.getX() - mark.pos.x;
            double dz = p.getZ() - mark.pos.z;
            if (dx * dx + dz * dz > reach * reach || Math.abs(p.getY() - mark.pos.y) > 2.5) {
               continue;
            }
            p.hurtServer(level, level.damageSources().mobAttack(boss), mark.damage);
            p.push(0.0, 0.45, 0.0);
            p.hurtMarked = true;
         }
         it.remove();
      }
   }

   /**
    * Kneel: he rings three bells and the floor round him slams down on the third.
    *
    * <p>A gold rune circle the size of the blast counts the bells down, and every player
    * inside it sees the count on their action bar. Anyone whose feet are on the ground
    * when the third bell lands is forced to kneel - hurt, and pinned for two seconds. The
    * answer is a jump timed on the third bell; being outside the circle works too, but it
    * is wide enough that running is the slow answer and jumping is the clean one.
    */
   private static void startKneel(ServerLevel level, Mob boss, Fight fight, long now) {
      Vec3 at = boss.position();
      fight.strikes.add(new Strike(Strike.KNEEL, at, now + KNEEL_WARN, KNEEL_RADIUS, KNEEL_DAMAGE, null));
      Fx.runeCircle(level, ParticleTypes.END_ROD, at.add(0.0, 0.05, 0.0), KNEEL_RADIUS, KNEEL_WARN, CROWN_GOLD);
      Fx.summonCircle(level, ParticleTypes.HAPPY_VILLAGER, at.add(0.0, 0.08, 0.0), 3.0, KNEEL_WARN, EMERALD);
      level.playSound(null, at.x, at.y, at.z, SoundEvents.EVOKER_PREPARE_ATTACK, SoundSource.HOSTILE, 1.4F, 0.7F);
      announce(level, SAY + "\"§fKneel.\"");
      announceNear(level, boss, ARENA_RADIUS, "&8Three bells. &7Jump on the third.");
   }

   /**
    * Appraisal: he picks a subject and weighs them, and the price is one lance down a line.
    *
    * <p>A gold chain runs from his crown to the target every few ticks for two seconds.
    * The chain stops at the first solid block between them, so a player can see the moment
    * they are safe: the chain hits the wall instead of them. When the time is up the lance
    * follows the same rule. The answer is to break line of sight - a pillar, a wall, a hole,
    * a block placed in time. On the throne he weighs two people at once.
    */
   private static void startAppraisal(ServerLevel level, Mob boss, Fight fight, long now) {
      List<ServerPlayer> room = new ArrayList<>();
      for (ServerPlayer p : participantsNear(level, boss, ARENA_RADIUS)) {
         if (fight.participants.contains(p.getUUID())) {
            room.add(p);
         }
      }
      if (room.isEmpty()) {
         return;
      }
      int picks = Math.min(room.size(), fight.phase >= 2 ? 2 : 1);
      for (int i = 0; i < picks; i++) {
         ServerPlayer p = room.remove(RANDOM.nextInt(room.size()));
         fight.strikes.add(new Strike(Strike.APPRAISAL, p.position(), now + APPRAISAL_WARN, 0.0, APPRAISAL_DAMAGE, p.getUUID()));
         Fx.runeCircle(level, ParticleTypes.END_ROD, p.position().add(0.0, 0.05, 0.0), 1.4, APPRAISAL_WARN, CROWN_GOLD);
         p.addEffect(new MobEffectInstance(MobEffects.GLOWING, APPRAISAL_WARN + 10, 0, false, false, true));
         p.sendOverlayMessage(Component.literal("§6◆ §fYou're being appraised §8- §fbreak line of sight."));
         announceNear(level, boss, ARENA_RADIUS, "&8He's weighing &f" + p.getName().getString() + "&8. &7Get behind something.");
      }
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.BEACON_POWER_SELECT, SoundSource.HOSTILE, 1.4F, 0.8F);
      announce(level, SAY + "\"§fWhat are you worth?\"");
   }

   /** Rings the Kneel bells, draws the Appraisal chains, and lands both when their time is up. */
   private static void tickStrikes(ServerLevel level, Mob boss, Fight fight, long now) {
      if (fight.strikes.isEmpty()) {
         return;
      }
      for (Iterator<Strike> it = fight.strikes.iterator(); it.hasNext();) {
         Strike strike = it.next();
         if (strike.kind == Strike.KNEEL) {
            long left = strike.landAt - now;
            // Bells one and two at 30 and 15 ticks out; the third is the slam itself.
            int due = left <= KNEEL_BEAT ? 2 : left <= KNEEL_BEAT * 2L ? 1 : 0;
            while (strike.beats < due) {
               strike.beats++;
               kneelBell(level, strike);
            }
            if (now < strike.landAt) {
               continue;
            }
            it.remove();
            landKneel(level, boss, strike);
            continue;
         }

         ServerPlayer target = level.getServer().getPlayerList().getPlayer(strike.target);
         if (target == null || !target.isAlive() || target.isSpectator() || target.isCreative() || target.level() != level) {
            // Gone, dead or elsewhere: the appraisal is dropped, not redirected.
            it.remove();
            continue;
         }
         Vec3 crown = boss.position().add(0.0, boss.getBbHeight() * 0.85, 0.0);
         Vec3 chest = target.position().add(0.0, target.getBbHeight() * 0.6, 0.0);
         Vec3 end = sightEnd(level, crown, chest);
         if (now < strike.landAt) {
            if ((strike.landAt - now) % 8 == 0) {
               Fx.chains(level, ParticleTypes.END_ROD, crown, end, CROWN_GOLD);
            }
            if (!strike.charged && strike.landAt - now <= 8) {
               strike.charged = true;
               Fx.flare(level, ParticleTypes.END_ROD, crown, 1.2, CROWN_GOLD);
               level.playSound(null, crown.x, crown.y, crown.z, SoundEvents.BEACON_ACTIVATE, SoundSource.HOSTILE, 1.4F, 1.8F);
            }
            continue;
         }
         it.remove();
         landAppraisal(level, boss, strike, target, crown, chest, end);
      }
   }

   /** One of the Kneel count-in bells, with the count on the action bar of everyone inside. */
   private static void kneelBell(ServerLevel level, Strike strike) {
      Vec3 at = strike.at;
      level.playSound(null, at.x, at.y, at.z, SoundEvents.BELL_BLOCK, SoundSource.HOSTILE, 2.0F, 0.9F);
      Fx.ring(level, ParticleTypes.END_ROD, at.add(0.0, 0.1, 0.0), strike.radius, CROWN_GOLD);
      Fx.resonance(level, ParticleTypes.NOTE, at.add(0.0, 3.4, 0.0), 12.0, EMERALD);
      String count = strike.beats == 1
         ? "§6● §8● ●"
         : "§6● ● §8● §f- jump on the next";
      for (ServerPlayer p : playersNear(level, at.x, at.y, at.z, strike.radius + 4.0)) {
         p.sendOverlayMessage(Component.literal(count));
      }
   }

   /** The third bell: everyone with their feet on the floor inside the circle kneels. */
   private static void landKneel(ServerLevel level, Mob boss, Strike strike) {
      Vec3 at = strike.at;
      level.playSound(null, at.x, at.y, at.z, SoundEvents.BELL_BLOCK, SoundSource.HOSTILE, 2.0F, 0.5F);
      level.playSound(null, at.x, at.y, at.z, SoundEvents.MACE_SMASH_GROUND_HEAVY, SoundSource.HOSTILE, 1.8F, 0.6F);
      Fx.shockwave(level, ParticleTypes.HAPPY_VILLAGER, at, strike.radius, EMERALD);
      Fx.nova(level, ParticleTypes.END_ROD, at.add(0.0, 0.2, 0.0), strike.radius * 0.6, CROWN_GOLD);
      Fx.rockburst(level, ParticleTypes.HAPPY_VILLAGER, at.add(0.0, 0.3, 0.0), 2.5, DEEP_GREEN);
      int knelt = 0;
      for (ServerPlayer p : playersNear(level, at.x, at.y, at.z, strike.radius + 4.0)) {
         double dx = p.getX() - at.x;
         double dz = p.getZ() - at.z;
         if (dx * dx + dz * dz > strike.radius * strike.radius || Math.abs(p.getY() - at.y) > 3.0) {
            continue;
         }
         if (!p.onGround()) {
            p.sendOverlayMessage(Component.literal("§aClean."));
            continue;
         }
         p.hurtServer(level, level.damageSources().mobAttack(boss), strike.damage);
         p.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 40, 4, false, true, true));
         Fx.shatter(level, ParticleTypes.HAPPY_VILLAGER, p.position().add(0.0, 0.4, 0.0), 0.8, EMERALD);
         knelt++;
      }
      announce(level, SAY + (knelt > 0 ? "\"§fGood. Stay down.\"" : "\"§f...Rude.\""));
   }

   /** The lance: down the line to the target, or into whatever they put in its way. */
   private static void landAppraisal(ServerLevel level, Mob boss, Strike strike, ServerPlayer target, Vec3 crown, Vec3 chest, Vec3 end) {
      Fx.beam(level, ParticleTypes.END_ROD, crown, end, CROWN_GOLD);
      Fx.lightning(level, ParticleTypes.HAPPY_VILLAGER, crown, end, EMERALD);
      level.playSound(null, crown.x, crown.y, crown.z, SoundEvents.BEACON_DEACTIVATE, SoundSource.HOSTILE, 1.6F, 1.8F);
      if (end.distanceToSqr(chest) > 1.0) {
         // Blocked: the lance breaks on the cover, and the player learns that cover works.
         Fx.shatter(level, ParticleTypes.END_ROD, end, 1.0, CROWN_GOLD);
         level.playSound(null, end.x, end.y, end.z, SoundEvents.ANVIL_LAND, SoundSource.HOSTILE, 1.0F, 1.6F);
         target.sendOverlayMessage(Component.literal("§aBlocked."));
         announce(level, SAY + "\"§fHiding. Cheap.\"");
         return;
      }
      Fx.flare(level, ParticleTypes.END_ROD, chest, 1.4, CROWN_GOLD);
      Fx.clash(level, ParticleTypes.HAPPY_VILLAGER, chest, chest.subtract(crown), EMERALD);
      target.hurtServer(level, level.damageSources().mobAttack(boss), strike.damage);
      target.addEffect(new MobEffectInstance(MobEffects.GLOWING, 100, 0, false, false, true));
      announce(level, SAY + "\"§fOverpriced.\"");
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
      Fx.runeCircle(level, ParticleTypes.END_ROD, boss.position().add(0.0, 0.05, 0.0), 3.0, WHEEL_TICKS, CROWN_GOLD);
      Fx.vortex(level, ParticleTypes.HAPPY_VILLAGER, boss.position(), 2.5, WHEEL_TICKS, EMERALD);
      announce(level, SAY + "\"§fRound we go.\"");
      announceNear(level, boss, ARENA_RADIUS, "&8Three rays. &7Stand in the gaps or behind a wall.");
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
         double reach = rayReach(level, origin, dir, WHEEL_REACH);
         if (fight.wheelTicks % 2 == 0) {
            Vec3 tip = origin.add(dir.scale(reach));
            Fx.beam(level, ParticleTypes.HAPPY_VILLAGER, origin, tip, EMERALD);
            if (reach < WHEEL_REACH - 0.5) {
               // Where an arm is broken by cover, the break is visible.
               vanillaOnly(() -> level.sendParticles(ParticleTypes.CRIT, tip.x, tip.y, tip.z, 4, 0.2, 0.2, 0.2, 0.04));
            }
         }
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
            p.hurtServer(level, level.damageSources().mobAttack(boss), WHEEL_DAMAGE);
            p.push(dir.x * 0.5, 0.12, dir.z * 0.5);
            p.hurtMarked = true;
            Fx.clash(level, ParticleTypes.HAPPY_VILLAGER, p.position().add(0.0, 1.0, 0.0), dir, EMERALD);
         }
      }

      if (fight.wheelTicks % 8 == 0) {
         level.playSound(null, origin.x, origin.y, origin.z, SoundEvents.BEACON_AMBIENT, SoundSource.HOSTILE, 1.4F, 1.7F);
      }
      if (fight.wheelTicks <= 0) {
         fight.wheelHits.clear();
         Fx.ring(level, ParticleTypes.END_ROD, boss.position().add(0.0, 0.1, 0.0), 4.0, CROWN_GOLD);
         announceNear(level, boss, ARENA_RADIUS, "&8The wheel stops.");
      }
   }

   /**
    * How far a ray gets before the first non-air block stops it, in half-block steps.
    *
    * @param dir a unit vector
    */
   private static double rayReach(ServerLevel level, Vec3 from, Vec3 dir, double max) {
      double reach = 0.0;
      for (double d = 0.0; d <= max; d += 0.5) {
         if (!level.getBlockState(BlockPos.containing(from.add(dir.scale(d)))).isAir()) {
            break;
         }
         reach = d;
      }
      return reach;
   }

   /** Where a line from {@code from} to {@code to} ends: at {@code to}, or at the first block in the way. */
   private static Vec3 sightEnd(ServerLevel level, Vec3 from, Vec3 to) {
      Vec3 delta = to.subtract(from);
      double dist = delta.length();
      if (dist < 1.0E-3) {
         return to;
      }
      Vec3 dir = delta.scale(1.0 / dist);
      double reach = rayReach(level, from, dir, dist);
      return reach >= dist - 0.5 ? to : from.add(dir.scale(reach));
   }

   /**
    * Emerald Coffin: a green circle, then four glass walls around whoever is standing on it.
    *
    * <p>The tell is the whole move. The circle is on the ground for a second and a
    * half, it does not follow anybody, and it leaves the spot it was aimed at - so a
    * fighter who moves is never in it. Anybody still standing there when the walls
    * come up is boxed in with the court for two and a half seconds, which is a
    * sentence rather than a death sentence: the walls are glass, and the way out is
    * through them or past his guards.
    *
    * <p>The walls used to be emerald blocks, which a fighter with a pickaxe could mine
    * out for nine emeralds apiece - twelve blocks a cast, in an economy mod. Glass drops
    * nothing worth having, and the green comes from the effects instead.
    */
   private static void startCoffin(ServerLevel level, Mob boss, Fight fight) {
      ServerPlayer target = nearestPlayer(boss, ARENA_RADIUS);
      if (target == null) {
         return;
      }
      fight.coffinAt = target.position();
      fight.coffinTell = COFFIN_TELL;
      Fx.runeCircle(level, ParticleTypes.HAPPY_VILLAGER, fight.coffinAt.add(0.0, 0.05, 0.0), 1.7, COFFIN_TELL, EMERALD);
      Fx.pillar(level, ParticleTypes.HAPPY_VILLAGER, fight.coffinAt, 3.5, DEEP_GREEN);
      announce(level, SAY + "\"§fHold still.\"");
      announceNear(level, boss, ARENA_RADIUS, "&8Off the green. &7Now.");
      level.playSound(null, target.getX(), target.getY(), target.getZ(), SoundEvents.RESPAWN_ANCHOR_CHARGE, SoundSource.HOSTILE, 1.5F, 0.8F);
   }

   private static void tickCoffin(ServerLevel level, Mob boss, Fight fight) {
      if (fight.coffinTell > 0) {
         fight.coffinTell--;
         Vec3 at = fight.coffinAt;
         if (fight.coffinTell % 2 == 0) {
            vanillaOnly(() -> {
               for (int i = 0; i < 20; i++) {
                  double a = i * (Math.PI * 2.0 / 20.0);
                  level.sendParticles(ParticleTypes.HAPPY_VILLAGER, at.x + Math.cos(a) * 1.7, at.y + 0.15, at.z + Math.sin(a) * 1.7, 1, 0.0, 0.0, 0.0, 0.0);
               }
            });
         }
         if (fight.coffinTell > 0) {
            return;
         }
         raiseCoffin(level, boss, fight);
         return;
      }

      fight.coffinHold--;
      Vec3 at = fight.coffinAt;
      if (fight.coffinHold % 4 == 0) {
         vanillaOnly(() -> level.sendParticles(ParticleTypes.HAPPY_VILLAGER, at.x, at.y + 1.0, at.z, 8, 1.0, 1.0, 1.0, 0.05));
      }
      if (fight.coffinHold <= 0) {
         Fx.shatter(level, ParticleTypes.HAPPY_VILLAGER, at.add(0.0, 1.2, 0.0), 1.4, EMERALD);
         level.playSound(null, at.x, at.y, at.z, SoundEvents.GLASS_BREAK, SoundSource.HOSTILE, 1.2F, 0.8F);
         dropCoffin(level, fight);
         announceNear(level, boss, ARENA_RADIUS, "&8The coffin opens.");
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
               level.setBlock(pos, Blocks.GLASS.defaultBlockState(), 3);
               fight.coffinBlocks.add(pos);
            }
         }
      }
      fight.coffinHold = COFFIN_HOLD;
      Fx.rockburst(level, ParticleTypes.HAPPY_VILLAGER, fight.coffinAt.add(0.0, 0.5, 0.0), 1.6, EMERALD);
      Fx.aura(level, ParticleTypes.HAPPY_VILLAGER, fight.coffinAt, 3.0, COFFIN_HOLD, EMERALD);
      level.playSound(null, fight.coffinAt.x, fight.coffinAt.y, fight.coffinAt.z, SoundEvents.BELL_BLOCK, SoundSource.HOSTILE, 1.8F, 1.2F);
      level.playSound(null, fight.coffinAt.x, fight.coffinAt.y, fight.coffinAt.z, SoundEvents.GLASS_PLACE, SoundSource.HOSTILE, 1.4F, 0.7F);
      int caught = 0;
      for (ServerPlayer p : playersNear(level, fight.coffinAt.x, fight.coffinAt.y + 1.0, fight.coffinAt.z, 2.2)) {
         p.hurtServer(level, level.damageSources().mobAttack(boss), COFFIN_DAMAGE);
         p.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, COFFIN_HOLD, 0, false, true, true));
         caught++;
      }
      if (caught > 0) {
         announceNear(level, boss, ARENA_RADIUS, "&8Boxed in. &7Glass breaks.");
      }
   }

   /**
    * Takes the walls down again.
    *
    * <p>Only blocks this move put there are removed, and only while they are still
    * its glass - a fighter who mined one out and built something in its place keeps
    * what they built.
    */
   private static void dropCoffin(ServerLevel level, Fight fight) {
      for (BlockPos pos : fight.coffinBlocks) {
         if (level.getBlockState(pos).is(Blocks.GLASS)) {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
         }
      }
      fight.coffinBlocks.clear();
      fight.coffinTell = 0;
      fight.coffinHold = 0;
   }

   /**
    * Phase two's signature: a green beam that has to be walked out of.
    *
    * <p>The line it will fire down sits on the floor for a second and a half first, and
    * stops where the beam will stop - at the first solid block - so a wall is cover too.
    */
   private static void startJudgement(ServerLevel level, Mob boss, Fight fight) {
      ServerPlayer target = nearestPlayer(boss, ARENA_RADIUS);
      if (target == null) {
         return;
      }
      fight.judgementOrigin = boss.position().add(0.0, boss.getBbHeight() * 0.7, 0.0);
      Vec3 dir = target.position().add(0.0, target.getBbHeight() * 0.5, 0.0).subtract(fight.judgementOrigin);
      dir = new Vec3(dir.x, 0.0, dir.z);
      if (dir.lengthSqr() < 1.0E-4) {
         dir = new Vec3(1.0, 0.0, 0.0);
      }
      fight.judgementDir = dir.normalize();
      fight.judgementCharge = JUDGEMENT_WARN;
      Fx.runeCircle(level, ParticleTypes.END_ROD, boss.position().add(0.0, 0.05, 0.0), 2.5, JUDGEMENT_WARN, CROWN_GOLD);
      Fx.aura(level, ParticleTypes.HAPPY_VILLAGER, boss.position(), boss.getBbHeight() + 0.5, JUDGEMENT_WARN, EMERALD);
      announce(level, SAY + "\"§fGuilty.\"");
      announceNear(level, boss, ARENA_RADIUS, "&8JUDGEMENT &7- step off the line. Walls stop it.");
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.BEACON_ACTIVATE, SoundSource.HOSTILE, 2.0F, 0.8F);
   }

   private static void tickJudgement(ServerLevel level, Mob boss, Fight fight) {
      fight.judgementCharge--;
      Vec3 origin = fight.judgementOrigin;
      Vec3 dir = fight.judgementDir;
      double reach = rayReach(level, origin, dir, JUDGEMENT_REACH);

      if (fight.judgementCharge > 0) {
         // Telegraph: the line on the floor where the beam will land, as far as it will land.
         Vec3 floorStart = new Vec3(origin.x, boss.getY() + 0.15, origin.z);
         Vec3 floorEnd = floorStart.add(dir.scale(reach));
         if (fight.judgementCharge % 6 == 0) {
            Fx.beam(level, ParticleTypes.HAPPY_VILLAGER, floorStart, floorEnd, DEEP_GREEN);
         } else if (fight.judgementCharge % 2 == 0) {
            vanillaOnly(() -> {
               for (double d = 0; d < reach; d += 1.0) {
                  Vec3 point = floorStart.add(dir.scale(d));
                  level.sendParticles(ParticleTypes.HAPPY_VILLAGER, point.x, point.y, point.z, 1, 0.05, 0.0, 0.05, 0.0);
               }
            });
         }
         boss.addEffect(new MobEffectInstance(MobEffects.GLOWING, 8, 0, false, false, false));
         return;
      }

      // Fire: one thick beam to the first block, a flare at his chest and a burst where it stops.
      Vec3 end = origin.add(dir.scale(reach));
      Fx.beam(level, ParticleTypes.END_ROD, origin, end, EMERALD);
      Fx.beam(level, ParticleTypes.HAPPY_VILLAGER, origin.add(0.0, 0.3, 0.0), end.add(0.0, 0.3, 0.0), CROWN_GOLD);
      Fx.flare(level, ParticleTypes.END_ROD, origin, 1.8, EMERALD);
      Fx.rockburst(level, ParticleTypes.HAPPY_VILLAGER, end, 1.4, DEEP_GREEN);
      level.playSound(null, origin.x, origin.y, origin.z, SoundEvents.BEACON_DEACTIVATE, SoundSource.HOSTILE, 1.6F, 1.6F);
      level.playSound(null, origin.x, origin.y, origin.z, SoundEvents.WARDEN_SONIC_BOOM, SoundSource.HOSTILE, 1.0F, 1.4F);

      for (ServerPlayer p : participantsNear(level, boss, JUDGEMENT_REACH + 2.0)) {
         Vec3 rel = p.position().add(0.0, p.getBbHeight() * 0.5, 0.0).subtract(origin);
         if (Math.abs(rel.y) > 2.5) {
            continue;
         }
         double along = rel.x * dir.x + rel.z * dir.z;
         if (along < 0.0 || along > reach + 0.5) {
            continue;
         }
         // Signed: which side of the line the player is on decides which way they are thrown.
         double cross = rel.x * dir.z - rel.z * dir.x;
         if (Math.abs(cross) > 2.2) {
            continue;
         }
         p.hurtServer(level, level.damageSources().mobAttack(boss), 20.0F);
         Vec3 push = new Vec3(dir.z, 0.0, -dir.x).scale(cross >= 0.0 ? 1.4 : -1.4);
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
         ServerPlayer struck = null;
         for (int s = 0; s < steps && !done; s++) {
            emerald.pos = emerald.pos.add(emerald.vel.scale(1.0 / steps));
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, emerald.pos.x, emerald.pos.y, emerald.pos.z, 1, 0.05, 0.05, 0.05, 0.0);
            if (!level.getBlockState(BlockPos.containing(emerald.pos)).isAir()) {
               done = true;
               break;
            }
            for (ServerPlayer p : playersNear(level, emerald.pos.x, emerald.pos.y, emerald.pos.z, 1.1)) {
               p.hurtServer(level, level.damageSources().mobAttack(boss), emerald.damage);
               struck = p;
               done = true;
               break;
            }
         }
         emerald.life--;
         if (done || emerald.life <= 0) {
            if (struck != null) {
               Fx.clash(level, ParticleTypes.HAPPY_VILLAGER, emerald.pos, emerald.vel, EMERALD);
            } else {
               level.sendParticles(ParticleTypes.CRIT, emerald.pos.x, emerald.pos.y, emerald.pos.z, 6, 0.2, 0.2, 0.2, 0.05);
            }
            it.remove();
         }
      }
   }

   // --------------------------------------------------------------------- phase

   private static void enterPhase(ServerLevel level, Mob boss, Fight fight, int phase) {
      fight.phase = phase;
      summonGuards(level, boss, fight, 4);
      announce(level, SAY + "\"§fFine. §aThrone room.\"");
      announceNear(level, boss, ARENA_RADIUS, "&8PHASE II &7- more guards, and they hit harder.");
      Vec3 head = boss.position().add(0.0, boss.getBbHeight() * 0.85, 0.0);
      Fx.flare(level, ParticleTypes.END_ROD, head, 2.4, CROWN_GOLD);
      Fx.shockwave(level, ParticleTypes.HAPPY_VILLAGER, boss.position(), 14.0, EMERALD);
      Fx.runeCircle(level, ParticleTypes.END_ROD, boss.position().add(0.0, 0.05, 0.0), 7.0, 60, CROWN_GOLD);
      Fx.pillar(level, ParticleTypes.HAPPY_VILLAGER, boss.position(), 10.0, DEEP_GREEN);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.BELL_RESONATE, SoundSource.HOSTILE, 2.0F, 0.5F);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.RAID_HORN, SoundSource.HOSTILE, 1.2F, 0.8F);
   }

   private static void taunt(ServerLevel level, Mob boss, Fight fight) {
      String[] lines = switch (fight.guards.isEmpty() ? 3 : fight.phase) {
         case 1 -> new String[]{"You're haggling with the wrong man.", "Everyone has a price.", "Bow. It's easier."};
         case 2 -> new String[]{"Pay up.", "This crown cost more than you.", "I own this floor."};
         default -> new String[]{"Where'd my court go?", "Fine. I'll do it myself."};
      };
      announce(level, SAY + "\"§f" + lines[RANDOM.nextInt(lines.length)] + "\"");
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
      // Everything in the air stops with him: nothing he marked lands after the blow that
      // ended him, and his walls come down now rather than at the end of the ceremony.
      fight.strikes.clear();
      fight.marks.clear();
      fight.emeralds.clear();
      fight.wheelTicks = 0;
      fight.wheelHits.clear();
      fight.judgementCharge = 0;
      fight.arrivalTicks = 0;
      dropCoffin(level, fight);
      Vec3 floor = boss.position();
      Fx.runeCircle(level, ParticleTypes.END_ROD, floor.add(0.0, 0.05, 0.0), 5.0, DEATH_CEREMONY_TICKS, CROWN_GOLD);
      Fx.spiral(level, ParticleTypes.HAPPY_VILLAGER, floor, 7.0, DEATH_CEREMONY_TICKS, EMERALD);
      Fx.aura(level, ParticleTypes.TOTEM_OF_UNDYING, floor, boss.getBbHeight(), DEATH_CEREMONY_TICKS, CROWN_GOLD);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.BEACON_DEACTIVATE, SoundSource.HOSTILE, 2.0F, 0.9F);
      announce(level, SAY + "\"§fNo. §aI'm the king.\"");
      // FALSE cancels the blow so the death ceremony - and its loot - runs.
      return Boolean.FALSE;
   }

   /**
    * The abdication: the bell he fought with tolls for him, lower each time, and his court
    * kneels and leaves one guard at a time. The crown cracks on every toll, and on the last
    * one it goes - a gold flare, a starburst, a shockwave across the hall and a slow rain of
    * gold over the place he stood.
    */
   private static void tickDeath(MinecraftServer server, Mob boss, Fight fight) {
      ServerLevel level = (ServerLevel) boss.level();
      // Held on his feet for the ceremony: a second blow, a fall or the void must not
      // take him to zero and end it early with nothing said.
      if (boss.getHealth() <= 0.0F) {
         boss.setHealth(1.0F);
      }
      fight.deathTicks--;
      double progress = 1.0 - Math.min(1.0, fight.deathTicks / (double) DEATH_CEREMONY_TICKS);
      Vec3 head = boss.position().add(0.0, boss.getBbHeight() * 0.85, 0.0);

      // The court kneels as he falls.
      if (fight.deathTicks % 10 == 0 && !fight.guards.isEmpty()) {
         UUID id = fight.guards.iterator().next();
         fight.guards.remove(id);
         Entity raw = findEntity(server, id);
         if (raw != null) {
            if (raw.level() == level) {
               Fx.ring(level, ParticleTypes.END_ROD, raw.position().add(0.0, 0.1, 0.0), 1.2, CROWN_GOLD);
               Fx.shatter(level, ParticleTypes.HAPPY_VILLAGER, raw.position().add(0.0, 0.9, 0.0), 0.9, EMERALD);
               level.playSound(null, raw.getX(), raw.getY(), raw.getZ(), SoundEvents.VINDICATOR_CELEBRATE, SoundSource.HOSTILE, 0.8F, 0.6F);
            }
            raw.discard();
         }
      }

      // The bell tolls for him, lower each time, and the crown cracks with it.
      if (fight.deathTicks > 0 && fight.deathTicks % 16 == 0) {
         float pitch = (float) Math.max(0.5, 1.0 - progress * 0.5);
         level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.BELL_BLOCK, SoundSource.HOSTILE, 2.0F, pitch);
         Fx.shatter(level, ParticleTypes.TOTEM_OF_UNDYING, head, 0.6 + progress, CROWN_GOLD);
         Fx.ring(level, ParticleTypes.HAPPY_VILLAGER, boss.position().add(0.0, 0.1, 0.0), 3.0 + progress * 6.0, EMERALD);
      }
      if (fight.deathTicks == 40) {
         announce(level, SAY + "\"§fTake it, then. §aIt's heavy.\"");
      }
      // The last second: everything is pulled in before it goes.
      if (fight.deathTicks == 20) {
         Fx.vortex(level, ParticleTypes.HAPPY_VILLAGER, boss.position(), 4.0, 20, DEEP_GREEN);
         Fx.summonCircle(level, ParticleTypes.END_ROD, boss.position().add(0.0, 0.05, 0.0), 3.0, 20, CROWN_GOLD);
         level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.BEACON_POWER_SELECT, SoundSource.HOSTILE, 1.8F, 0.5F);
      }
      vanillaOnly(() -> level.sendParticles(ParticleTypes.HAPPY_VILLAGER, boss.getX(), boss.getY() + 1.5, boss.getZ(), 8, 1.4, 1.2, 1.4, 0.1));
      // He sinks, a little, towards one knee.
      if (fight.deathTicks > 20) {
         boss.setPos(boss.getX(), boss.getY() - 0.006, boss.getZ());
      }

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
      fight.strikes.clear();
      // A coffin left standing because the king died inside it would be a wall in the
      // arena forever, so his death takes it down with him.
      dropCoffin(level, fight);
      fight.marks.clear();
      fight.wheelTicks = 0;

      // The crown goes.
      Vec3 floor = boss.position();
      Fx.flare(level, ParticleTypes.END_ROD, head, 3.4, CROWN_GOLD);
      Fx.starburst(level, ParticleTypes.TOTEM_OF_UNDYING, head, 8.0, CROWN_GOLD);
      Fx.shockwave(level, ParticleTypes.HAPPY_VILLAGER, floor, 16.0, EMERALD);
      Fx.nova(level, ParticleTypes.END_ROD, floor.add(0.0, 0.3, 0.0), 8.0, DEEP_GREEN);
      Fx.pillar(level, ParticleTypes.HAPPY_VILLAGER, floor, 14.0, EMERALD);
      Fx.emberRain(level, ParticleTypes.TOTEM_OF_UNDYING, floor, 8.0, 70, CROWN_GOLD);
      level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, boss.getX(), boss.getY() + 1.0, boss.getZ(), 3, 1.5, 1.5, 1.5, 0.1);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), ModSounds.BOSS_DEATH, SoundSource.HOSTILE, 2.0F, 0.9F);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.BELL_BLOCK, SoundSource.HOSTILE, 2.0F, 0.5F);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.TOTEM_USE, SoundSource.HOSTILE, 1.4F, 0.7F);
      announce(level, "§8The crown hits the floor and rolls.");
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
         announce(level, "&8The hall is empty. &7He left what he owed on the floor.");
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
      // The coffin comes down even when his body cannot be found: a fight ended by the
      // missing-body timer used to skip this and leave the walls standing for good.
      ServerLevel coffinLevel = boss != null && boss.level() instanceof ServerLevel bl ? bl : fight.lastSeenLevel;
      if (coffinLevel != null && !fight.coffinBlocks.isEmpty()) {
         Safe.run("sovereign coffin teardown", () -> dropCoffin(coffinLevel, fight));
      }
      if (boss != null) {
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
      fight.strikes.clear();
      fight.marks.clear();
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

   private static List<ServerPlayer> participantsNear(ServerLevel level, Entity at, double range) {
      return playersNear(level, at.getX(), at.getY(), at.getZ(), range);
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
    * Runs plain-particle drawing as the vanilla-only fallback: modded clients already see
    * the Fx shape it stands in for, so only clients without the mod get these.
    */
   private static void vanillaOnly(Runnable draw) {
      com.fortuneandfavors.net.FfVfx.enter();
      try {
         draw.run();
      } finally {
         com.fortuneandfavors.net.FfVfx.exit();
      }
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
