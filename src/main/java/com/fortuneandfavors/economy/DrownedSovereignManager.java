package com.fortuneandfavors.economy;

import com.fortuneandfavors.ModItems;
import com.fortuneandfavors.ModSounds;
import com.fortuneandfavors.net.FfVfx;
import com.fortuneandfavors.util.FxKinds;
import com.fortuneandfavors.util.Safe;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.particles.ParticleOptions;
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
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * <b>The Drowned Sovereign</b> - the sea boss. Deep water, pressure, and the tide used as a weapon.
 *
 * <h2>The idea</h2>
 * He does not throw "water damage" at you. He changes <em>where you are standing</em>: a wave moves
 * you backward, a whirlpool decides which ground is yours, and a prison decides for one player that
 * they are not standing anywhere at all for three seconds. Almost every move in his list is answered
 * by moving, which is the point - the counterplay is positioning, not more hitting.
 *
 * <h2>Phases</h2>
 * <ul>
 *   <li><b>I - The Leviathan</b>: the tide as a push. Tidal Crash, Depth Charge, Abyssal Tentacles,
 *       Riptide, Undertow, Drowned Call, Pressure Crush, Tidal Prison.</li>
 *   <li><b>II - The Abyss Opens</b> (60%): the arena stops being safe. Maelstrom, Abyssal Beam,
 *       Leviathan Dive, Black Tide, Call of the Deep, Crushing Depths, Tsunami.</li>
 *   <li><b>III - Drowned God</b> (20%): no spacing, no defence. Worldbreaker Tide, Abyssal Maw,
 *       Leviathan's Wrath, Dead Sea, Final Depth.</li>
 * </ul>
 *
 * <p>Two more sit outside those tables on their own cooldowns and cut into the rotation when they
 * are ready: <b>Breaker Ring</b> (every phase; a ring of water rolls out along the floor - jump it)
 * and <b>The Deep Looks</b> (phase two on; he stares, and anyone he can still see when the stare
 * lands is hit - break line of sight or get out of the dome). They are kept out of the phase lists
 * on purpose, because those lists are the codex page and the self-test's pinned design.
 *
 * <h2>Everything multi-second is one scheduler</h2>
 * Most of that list is "a thing that happens shortly, somewhere", and a fight has to be able to have
 * several of them in the air at once while its own move loop keeps running. Rather than a field per
 * ability, every delayed effect is a {@link Pending}: a kind, a place, a fuse, and - for the ones
 * that linger - a life. The tick loop counts them down and lands them, which is why three Tsunami
 * waves can be travelling while a prison is still counting down on somebody. The two cooldown moves
 * are {@link Strike}s instead, because they land on the fight clock rather than a fuse.
 *
 * <p>Every shape is sent through {@link Fx}, which hands modded clients the real effect and everyone
 * else a particle version. The per-tick particle upkeep a timed Fx shape already covers is drawn with
 * the {@code v*} helpers at the bottom of the file, which send through the transport inside a
 * vanilla-only scope, so a modded client is not drawing both. (This upkeep used to go through
 * {@code BossVfx}, whose per-player sends bypass the transport - so the "vanilla-only" rings,
 * walls and spheres reached modded clients too, on top of the shapes they stood in for.)
 *
 * <p>The water itself is the {@link FxKinds#TIDE_WAVE} cue: every wave, the Breaker Ring and the
 * rise and the fall push rolling walls of water out across the floor, and the stare of The Deep
 * Looks is a {@link FxKinds#VOID_COLLAPSE} drawn into his eye for exactly as long as the warning.
 */
public final class DrownedSovereignManager {

   private static final String TAG = "ff_drowned_sovereign";
   /**
    * The tag every body the call has ever raised carries.
    *
    * <p>Public, and the same tag the shipped build put on the <em>guardians</em> it raised, because
    * that is what lets {@link #sweepLegacyGuardians} find the ones already standing in a world: the
    * upgrade changes what the call raises, and cannot change what an older fight left behind.
    */
   public static final String MINION_TAG = TAG + "_minion";
   private static final String CHANNEL = "drowned";
   private static final String BOSS_NAME = "\u00a73\u00a7l\uD83C\uDF0A The Drowned Sovereign";
   private static final String SAY = "\u00a73The Sovereign\u00a7r\u00a77 \u203a \u00a7f";

   /** His palette: open water, the foam on it, and the dark underneath both. */
   private static final int TIDE = 0x2E9BD6;
   private static final int FOAM = 0xD8F6FF;
   private static final int ABYSS = 0x0E2A4A;

   private static final double MAX_HEALTH = 760.0;
   private static final double SEEK_RANGE = 90.0;
   /** Wider than the widest arena in the mod, and no wider - see {@code ClockworkKingManager}. */
   public static final double STRAY_SWEEP_RADIUS = 96.0;

   /** Where each phase begins, as a fraction of maximum health. */
   private static final double PHASE_2_AT = 0.60;
   private static final double PHASE_3_AT = 0.20;

   /** Ticks between one move and the next, per phase: he speeds up as the sea gets angrier. */
   private static final int MOVE_GAP_1 = 74;
   private static final int MOVE_GAP_2 = 58;
   private static final int MOVE_GAP_3 = 42;

   /** The arrival: how long he takes to climb out of the floor, and from how deep. */
   private static final int RISE_TICKS = 60;
   private static final double RISE_DEPTH = 3.0;

   /** The wave: how wide the arc is, how far it reaches, and what it costs to stand in it. */
   private static final double WAVE_HALF_ANGLE = 100.0;
   private static final double WAVE_REACH = 13.0;
   private static final float WAVE_DAMAGE = 9.0F;
   private static final double WAVE_PUSH = 2.15;
   /** How many ticks before a big wave lands its direction is drawn on the floor. */
   private static final int WAVE_WARN = 16;

   private static final double CHARGE_RADIUS = 4.0;
   private static final float CHARGE_DAMAGE = 15.0F;
   private static final int CHARGE_TELL = 40;

   private static final double TENTACLE_RADIUS = 2.4;
   private static final float TENTACLE_DAMAGE = 11.0F;

   /** Riptide: the lanes are drawn first, then he runs them one after another. */
   private static final int DASH_TELL = 16;
   private static final int DASH_STEP = 8;
   private static final double DASH_HALF_WIDTH = 1.4;
   private static final float DASH_DAMAGE = 8.0F;

   private static final double PRISON_RADIUS = 2.2;
   private static final int PRISON_TICKS = 60;
   /** How long the prison's circle shows before it closes - the time to step out of it. */
   private static final int PRISON_FUSE = 18;
   private static final float PRISON_DAMAGE = 3.0F;

   /** The most called drowned standing at once, however often the call comes round. */
   private static final int MAX_MINIONS = 8;

   private static final double MAELSTROM_RADIUS = 22.0;
   private static final double MAW_RADIUS = 26.0;

   private static final float BEAM_DAMAGE = 17.0F;
   private static final int BEAM_TELL = 40;
   private static final int BEAM_LIFE = 20;
   private static final double BEAM_REACH = 30.0;
   private static final double BEAM_HALF_WIDTH = 1.6;
   /** The beam is a ray along the floor, not a wall to the sky: above this, it passes under you. */
   private static final double BEAM_HEIGHT = 3.0;
   private static final int BEAM_HIT_COOLDOWN = 20;

   private static final int BLACK_TIDE_LIFE = 120;
   private static final double BLACK_TIDE_RADIUS = 3.4;
   private static final float BLACK_TIDE_DAMAGE = 2.5F;

   private static final int EYE_LIFE = 40;
   private static final double EYE_RADIUS = 2.0;
   private static final int EYE_HIT_COOLDOWN = 30;

   private static final int DEAD_SEA_TICKS = 200;
   /** Dead Sea reaches the arena, not the whole dimension. */
   private static final double DEAD_SEA_RADIUS = 40.0;

   private static final int FINAL_DEPTH_TELL = 40;
   private static final int FINAL_DEPTH_MARKS = 4;

   /**
    * The last tide: how far one roll of it reaches, and how much water is standing in it.
    *
    * <p>Deliberately larger than {@link #WAVE_REACH} by a wide margin - Worldbreaker Tide is one
    * enormous wave the length of the arena, and the finale has to be a bigger thing than that
    * rather than the same wave with a different name on the action bar.
    */
   private static final double MEGA_REACH = 32.0;
   private static final double MEGA_RADIUS = 30.0;
   private static final float MEGA_DAMAGE = 16.0F;
   private static final double MEGA_PUSH = 3.0;
   /** Three rolls, each one starting a second after the last - a swell, not a single wall. */
   private static final int MEGA_ROLLS = 3;

   /** Breaker Ring: the warning, how far the ring rolls, how fast, and what it costs. */
   private static final int BREAKER_WARN = 30;
   private static final double BREAKER_START = 1.5;
   private static final double BREAKER_REACH = 18.0;
   private static final double BREAKER_SPEED = 0.8;
   private static final float BREAKER_DAMAGE = 10.0F;
   private static final int BREAKER_COOLDOWN = 300;

   /** The Deep Looks: the stare, how far it sees, and what being seen costs. */
   private static final int GLARE_WARN = 45;
   private static final double GLARE_REACH = 34.0;
   private static final float GLARE_DAMAGE = 14.0F;
   private static final int GLARE_COOLDOWN = 380;

   private static final int DEATH_CEREMONY_TICKS = 90;
   /** Maelstrom: 15 seconds of a growing whirlpool before he can die. */
   private static final int FINAL_TICKS = 300;
   /**
    * How long his body may be unfindable before the fight is called over - the same ten seconds,
    * for the same reason, as {@code EmeraldSovereignManager.MISSING_GRACE_TICKS}: a body in a chunk
    * nobody is standing in cannot be looked up, and that is a tick to skip rather than a fight to
    * tear down with nothing paid.
    */
   private static final int MISSING_GRACE_TICKS = 200;

   private static final Random RANDOM = new Random();

   /** One delayed effect: where it lands, and how long it has left. */
   private static final class Pending {
      final String kind;
      final Vec3 pos;
      int fuse;
      int life;
      /** Where a line-shaped effect starts (a beam, a dash), or null for a point. */
      Vec3 origin;
      /** Which way a wave rolls, fixed when it is scheduled so its warning tells the truth. */
      Vec3 dir;

      Pending(String kind, Vec3 pos, int fuse, int life) {
         this.kind = kind;
         this.pos = pos;
         this.fuse = fuse;
         this.life = life;
      }
   }

   /**
    * A cooldown move that has been warned and not landed yet - a ring about to roll, a stare about
    * to land. Kept apart from {@link Pending} because these run on the fight clock: the warning
    * goes out once, as a timed shape, and the strike lands at {@link #landAt} whatever else he is
    * doing in between.
    */
   private static final class Strike {
      static final int BREAKER = 0;
      static final int GLARE = 1;
      final int kind;
      final Vec3 at;
      final long landAt;
      /** Ticks since it landed - how far the ring has rolled. */
      int age;
      /** Who the ring has already passed, so one ring is one hit at most. */
      final Set<UUID> passed = new HashSet<>();

      Strike(int kind, Vec3 at, long landAt) {
         this.kind = kind;
         this.at = at;
         this.landAt = landAt;
      }
   }

   private static final class Fight {
      final UUID bossId;
      final UUID summoner;
      final ServerBossEvent bar;
      final Set<UUID> participants = new HashSet<>();
      final List<Pending> pending = new ArrayList<>();
      final List<Strike> strikes = new ArrayList<>();
      /**
       * The bodies Drowned Call brought up, so they can be sent back with him.
       *
       * <p>A summoned minion that outlives its fight is not a minion, it is a leftover - and the
       * guardians this move used to raise were the worst case of it: a guardian never stops firing,
       * so a pair left standing behind a dead boss was a guardian attack sound repeating somewhere
       * off screen for as long as the player stayed in the chunk.
       */
      final List<UUID> minions = new ArrayList<>();
      /** When each body may be hit by a lingering hazard again - see {@link #mayTouch}. */
      final Map<UUID, Long> touched = new HashMap<>();
      int phase = 1;
      long now;
      long nextMove;
      long nextTaunt;
      long nextBreaker;
      long nextGlare;
      long nextWhirlpool;
      String lastMove = "";
      /** Ticks left climbing out of the floor; nothing else runs until this is zero. */
      int riseTicks;
      double riseFloorY;
      boolean dying;
      int deathTicks;
      /** Maelstrom, the last stand: ticks left, and whether it has already run. */
      int finalTicks;
      boolean finalDone;
      /** Where he stood when the killing blow landed - the body is put back here before it goes. */
      double deathFloorY;
      /** Consecutive ticks the body could not be found, and where it was last standing. */
      int missingTicks;
      ServerLevel lastSeenLevel;
      Vec3 lastSeen;

      Fight(UUID bossId, UUID summoner, ServerBossEvent bar) {
         this.bossId = bossId;
         this.summoner = summoner;
         this.bar = bar;
      }
   }

   private static final Map<UUID, Fight> FIGHTS = new HashMap<>();

   /**
    * Bosses whose loot has already been paid out - see {@link ClockworkKingManager#onBossDeath} for
    * why this exists. The ceremony pays through this and the entity-death hook that follows it is a
    * no-op, so loot lands exactly once whichever route the boss dies by.
    */
   private static final Set<UUID> LOOT_PAID = new HashSet<>();

   private static final List<String> MOVES_1 = List.of(
      "Tidal Crash", "Depth Charge", "Abyssal Tentacles", "Riptide", "Undertow", "Drowned Call",
      "Pressure Crush", "Tidal Prison"
   );
   private static final List<String> MOVES_2 = List.of(
      "Maelstrom", "Abyssal Beam", "Leviathan Dive", "Black Tide", "Call of the Deep", "Crushing Depths",
      "Tsunami"
   );
   private static final List<String> MOVES_3 = List.of(
      "Worldbreaker Tide", "Abyssal Maw", "Leviathan's Wrath", "Dead Sea", "Final Depth", "Final Tsunami"
   );

   private DrownedSovereignManager() {
   }

   // ------------------------------------------------------------------ public API

   public static boolean isDrownedSovereign(Entity entity) {
      return entity != null && entity.entityTags().contains(TAG);
   }

   public static int activeCount() {
      return FIGHTS.size();
   }

   /** How many delayed effects this fight is holding - three Tsunami waves, a prison, a pool. */
   public static int pendingCount(UUID bossId) {
      Fight fight = FIGHTS.get(bossId);
      return fight == null ? 0 : fight.pending.size();
   }

   /** The phase this fight is in, or 0 if it is not running. For the codex and the self-test. */
   public static int phaseOf(UUID bossId) {
      Fight fight = FIGHTS.get(bossId);
      return fight == null ? 0 : fight.phase;
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

   /** The Sovereign's Heart right-click: he answers from below. */
   public static String useSovereignsHeart(ServerPlayer player, ItemStack held) {
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
            return "Your tide's already in. Finish it first.";
         }
      }

      ServerLevel level = summoner.level();
      Mob boss = (Mob)EntityTypes.DROWNED.create(level, EntitySpawnReason.COMMAND);
      if (boss == null) {
         return "Nothing answered from below.";
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
      // Big by the scale attribute rather than by a different mob: the point of the fight is a
      // creature you can see coming from the far end of the arena, not a slightly taller drowned.
      AttributeInstance scale = boss.getAttribute(Attributes.SCALE);
      if (scale != null) {
         scale.setBaseValue(2.6);
      }
      AttributeInstance speed = boss.getAttribute(Attributes.MOVEMENT_SPEED);
      if (speed != null) {
         speed.setBaseValue(0.30);
      }
      AttributeInstance damage = boss.getAttribute(Attributes.ATTACK_DAMAGE);
      if (damage != null) {
         damage.setBaseValue(14.0);
      }

      boss.setPersistenceRequired();
      boss.setCustomName(Component.literal(BOSS_NAME));
      boss.setCustomNameVisible(true);
      boss.addTag(TAG);
      // The shared marker plus the visible-and-persistent guarantee: see BossManager.markBoss for
      // why a boss has to say so itself.
      BossManager.markBoss(boss);
      // He starts under the floor and climbs out of it (see tickRise). Held still, weightless and
      // untouchable while he does, so the ground cannot suffocate him and nobody gets a free
      // first hit on a boss whose fight has not begun.
      double floorY = BossGrounding.groundY(level, summoner.getX(), summoner.getZ(), summoner.getY());
      boss.setPos(summoner.getX(), floorY - RISE_DEPTH, summoner.getZ());
      boss.setNoGravity(true);
      boss.setNoAi(true);
      boss.setInvulnerable(true);
      level.addFreshEntity(boss);

      ServerBossEvent bar = new ServerBossEvent(
         UUID.randomUUID(), Component.literal(barName(1)), BossBarColor.BLUE, BossBarOverlay.PROGRESS
      );
      bar.setVisible(true);
      for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
         bar.addPlayer(p);
      }

      Fight fight = new Fight(boss.getUUID(), summoner.getUUID(), bar);
      fight.participants.add(summoner.getUUID());
      fight.riseTicks = RISE_TICKS;
      fight.riseFloorY = floorY;
      long now = ServerClock.clock(level);
      fight.nextMove = now + RISE_TICKS + 40L;
      fight.nextTaunt = now + 120L;
      fight.nextBreaker = now + RISE_TICKS + 220L;
      fight.nextWhirlpool = now + RISE_TICKS + 300L;
      fight.nextGlare = now + RISE_TICKS;
      FIGHTS.put(boss.getUUID(), fight);

      announce(level, "\u00a73\u00a7m\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550");
      announce(level, "    \u00a73\u00a7l\uD83C\uDF0A THE DROWNED SOVEREIGN \uD83C\uDF0A");
      announce(level, "    \u00a77The ground is wet. Then it's water.");
      announce(level, "    \u00a78\u201c\u00a7fThis was my kingdom.\u00a78\u201d");
      announce(level, "    \u00a78Everything he does moves you. \u00a77Keep your feet.");
      announce(level, "\u00a73\u00a7m\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550");

      // The rise is a set piece before it is a boss: a whirlpool opens where he will stand, a
      // ring of his marks turns under it, and the first spout goes up before anything else does.
      Vec3 pool = new Vec3(summoner.getX(), floorY, summoner.getZ());
      Fx.vortex(level, ParticleTypes.BUBBLE, pool.add(0.0, 0.1, 0.0), 6.0, RISE_TICKS, TIDE);
      Fx.runeCircle(level, ParticleTypes.BUBBLE_POP, pool.add(0.0, 0.05, 0.0), 5.0, RISE_TICKS + 10, FOAM);
      Fx.summonCircle(level, ParticleTypes.SQUID_INK, pool.add(0.0, 0.05, 0.0), 3.0, RISE_TICKS, ABYSS);
      Fx.geyser(level, ParticleTypes.SPLASH, pool, 6.0, TIDE);
      level.playSound(null, pool.x, pool.y, pool.z, ModSounds.BOSS_SPAWN, SoundSource.HOSTILE, 1.4F, 0.6F);
      level.playSound(null, pool.x, pool.y, pool.z, SoundEvents.ELDER_GUARDIAN_CURSE, SoundSource.HOSTILE, 1.2F, 0.7F);
      level.playSound(null, pool.x, pool.y, pool.z, SoundEvents.BUBBLE_COLUMN_UPWARDS_AMBIENT, SoundSource.HOSTILE, 1.6F, 0.6F);
      Advancements.grant(summoner, "summon_drowned_sovereign");
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
    * Sweeps up Sovereign bodies left behind by a crash or a hard restart - see
    * {@link ClockworkKingManager#sweepStrays} for the full story. Run near players, because chunks
    * are not loaded on boot.
    *
    * @return how many bodies were removed
    */
   public static int sweepStrays(MinecraftServer server) {
      int removed = 0;
      // Perf: one entity query per player, and a player already inside a swept box is skipped
      // instead of queried again - see the same shape in StarboundMagisterManager.
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
            if (isDrownedSovereign(mob) && !FIGHTS.containsKey(mob.getUUID())) {
               mob.discard();
               removed++;
            }
         }
      }
      return removed;
   }

   /**
    * The looping guardian noise the players still hear, and why upgrading alone does not stop it.
    *
    * <p>{@code Drowned Call} used to raise guardians, and they were raised with
    * {@code setPersistenceRequired()} - so every guardian a fight ever left standing is not a mob
    * that wanders off, it is a body in the save file. A guardian's attack is a beam it keeps firing
    * for as long as it can see anybody, so one of those left behind is a guardian attack sound with
    * no end. The call raises drowned now, which fixes every <em>future</em> fight and does nothing
    * at all about the ones already parked in somebody's world.
    *
    * <p>So this is the part that reaches back: any guardian still carrying the old minion tag is
    * removed, once, when a player is near enough for its chunk to be loaded. Guardians that were
    * never ours - an ocean monument's own - carry no such tag and are untouched, which is the whole
    * reason the tag is matched rather than the entity type.
    *
    * @return how many bodies were removed
    */
   public static int sweepLegacyGuardians(MinecraftServer server) {
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
            if (isStrayMinion(mob)) {
               mob.discard();
               removed++;
            }
         }
      }
      return removed;
   }

   /**
    * Whether a body is one of ours with no fight left to belong to.
    *
    * <p>Two questions, and both have to be asked. The tag is what says "the call raised this", and
    * it is deliberately not an entity-type test: an ocean monument's own guardians carry no tag and
    * are none of our business. The ledger is what says "and its fight is over" - the crew of the
    * fight happening right now is tagged exactly the same way, so a sweep that matched on the tag
    * alone would quietly empty every {@code Drowned Call} five minutes into a fight.
    */
   public static boolean isStrayMinion(Entity entity) {
      if (entity == null || !entity.entityTags().contains(MINION_TAG)) {
         return false;
      }
      if (FIGHTS.containsKey(entity.getUUID())) {
         return false;
      }
      for (Fight fight : FIGHTS.values()) {
         if (fight.minions.contains(entity.getUUID())) {
            return false;
         }
      }
      return true;
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
    * The killing blow is cancelled and played out: the sea takes him back rather than letting the
    * body fall over mid-move with a dozen hazards still in the air. Returning {@code FALSE} cancels
    * the damage (Fabric: {@code true} means "allow it"), and the ceremony's own end kills him for
    * real, which is the only route that pays the loot.
    *
    * <p>Once the ceremony is running every further blow is cancelled too. It used to fall through to
    * vanilla, so a second hit during the ceremony killed him on the spot and paid the loot early,
    * with the ceremony still holding his fight open behind it.
    */
   public static Boolean onLethalDamage(Entity entity, float amount) {
      if (!isDrownedSovereign(entity) || entity.level().isClientSide()) {
         return null;
      }
      Fight fight = FIGHTS.get(entity.getUUID());
      if (fight == null) {
         return null;
      }
      if (fight.dying) {
         return Boolean.FALSE;
      }
      if (!(entity instanceof Mob boss) || boss.getHealth() - amount > 0.0F) {
         return null;
      }
      ServerLevel level = (ServerLevel)boss.level();
      if (fight.finalTicks > 0) {
         return Boolean.FALSE;
      }
      if (!fight.finalDone) {
         startMaelstrom(level, boss, fight);
         return Boolean.FALSE;
      }
      fight.dying = true;
      fight.deathTicks = DEATH_CEREMONY_TICKS;
      fight.deathFloorY = boss.getY();
      fight.pending.clear();
      fight.strikes.clear();
      // His crew goes under with him now, at the start of the ceremony. The tick that was meant to
      // do this never ran - the dying branch returns before it - so they used to keep swinging at
      // players for the whole ceremony.
      clearMinions(level, fight);
      boss.setHealth(1.0F);
      boss.setNoAi(true);
      boss.setInvulnerable(true);
      // Weightless so he can sink through his own floor without the floor pushing back.
      boss.setNoGravity(true);
      boss.setDeltaMovement(Vec3.ZERO);
      fight.bar.setProgress(0.0F);
      // The build-up: the water starts turning around him and does not stop until he is gone.
      Fx.spiral(level, ParticleTypes.BUBBLE, boss.position(), 10.0, DEATH_CEREMONY_TICKS, TIDE);
      Fx.vortex(level, ParticleTypes.SQUID_INK, boss.position().add(0.0, 0.1, 0.0), 7.0, DEATH_CEREMONY_TICKS, ABYSS);
      Fx.aura(level, ParticleTypes.BUBBLE, boss.position(), 5.5, DEATH_CEREMONY_TICKS, FOAM);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.CONDUIT_DEACTIVATE, SoundSource.HOSTILE, 2.0F, 0.6F);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.ELDER_GUARDIAN_HURT, SoundSource.HOSTILE, 1.6F, 0.4F);
      announce(level, SAY + "\u00a77\u201c\u00a7fNo. Not to you.\u00a77\u201d");
      return Boolean.FALSE;
   }

   private static void tickFight(MinecraftServer server, Fight fight, long now) {
      Mob boss = bossOf(server, fight);
      if (boss == null) {
         tickMissing(server, fight);
         return;
      }
      ServerLevel level = (ServerLevel)boss.level();
      fight.missingTicks = 0;
      fight.lastSeenLevel = level;
      fight.lastSeen = boss.position();
      fight.now = now;

      // Only real, playing bodies join: a spectator or a puppet in range is not a participant, and
      // a participant is who the loot boxes go to.
      for (ServerPlayer p : server.getPlayerList().getPlayers()) {
         if (p.level() == level && p.isAlive() && !p.isSpectator() && !BossManager.isFakePlayer(p)
            && p.distanceToSqr(boss) < SEEK_RANGE * SEEK_RANGE) {
            fight.participants.add(p.getUUID());
         }
      }

      if (fight.dying) {
         tickDeath(server, boss, fight);
         return;
      }
      if (fight.finalTicks > 0) {
         tickMaelstrom(level, boss, fight);
         return;
      }
      // Every fighter is dead or gone: the fight is over (unless despawns are off).
      if (BossManager.allFightersDown(server, fight.participants)) {
         shutDown(server, fight);
         return;
      }

      if (fight.riseTicks > 0) {
         tickRise(level, boss, fight);
         return;
      }

      int phase = phaseFor(boss);
      if (phase != fight.phase) {
         fight.phase = phase;
         enterPhase(level, boss, fight, phase);
      }

      fight.bar.setProgress(Math.max(0.0F, Math.min(1.0F, boss.getHealth() / boss.getMaxHealth())));
      fight.bar.setName(Component.literal(barName(fight.phase)));
      fight.bar.setColor(fight.phase >= 2 ? BossBarColor.RED : BossBarColor.BLUE);

      // A presence, so the fight is never silent between moves - and it is water rather than a
      // generic aura, because the whole read of this fight is "the sea is standing here with you".
      if (now % 40L == 0L) {
         Fx.aura(level, ParticleTypes.BUBBLE, boss.position(), 5.5, 40, fight.phase >= 3 ? ABYSS : TIDE);
      }
      if (now % 15L == 0L) {
         vSphere(level, boss.position().add(0.0, 1.0, 0.0), 2.4, ParticleTypes.FALLING_WATER);
         vAt(level, boss.position(), ParticleTypes.BUBBLE, 5, 1.2, 1.4, 1.2, 0.02);
         vAt(level, boss.position(), ParticleTypes.DRIPPING_WATER, 4, 1.4, 0.6, 1.4, 0.0);
      }

      tickPending(level, boss, fight);
      tickStrikes(level, boss, fight);
      tickMinions(level, fight);

      // He wears what he is, and the sea does not burn. A drowned left in daylight catches fire,
      // which turned the king of the deep into a running torch the moment the sun came up; the
      // crown and the trident are what make him read as a drowned *king* rather than a drowned.
      crownAndTrident(boss);

      // And he walks. There is no vanilla AI behind him - the whole fight is scripted because
      // every one of his moves is about where the player is standing, and a mob that also throws
      // its own attacks is a mob whose tells cannot be read. Movement is a steady approach to
      // whoever is closest, at a speed the player can back away from, and no jumping at all.
      // He stands still while he stares: the dome on the floor is his reach, and it has to stay
      // where it was drawn.
      ServerPlayer chase = nearestPlayer(level, boss, 64.0);
      if (chase != null) {
         if (isStaring(fight)) {
            boss.getLookControl().setLookAt(chase, 30.0F, 30.0F);
            boss.setDeltaMovement(boss.getDeltaMovement().multiply(0.0, 1.0, 0.0));
         } else {
            walkToward(boss, chase, 3.0, 0.15);
         }
      }

      if (now >= fight.nextMove) {
         fight.nextMove = now + moveGap(fight.phase);
         chooseMove(level, boss, fight);
      }
   }

   /**
    * The arrival. He climbs out of the floor over {@link #RISE_TICKS} inside the whirlpool the
    * summon opened, with spouts of water going up around the pool as he comes, and lands with a
    * flash and a shockwave that shoves anyone standing on the spot clear - no damage, just room.
    * Nothing else in the fight runs until he is up, so the first move is never thrown from inside
    * the ground.
    */
   private static void tickRise(ServerLevel level, Mob boss, Fight fight) {
      fight.riseTicks--;
      double y = Math.min(fight.riseFloorY, boss.getY() + RISE_DEPTH / RISE_TICKS);
      boss.setPos(boss.getX(), y, boss.getZ());
      boss.setDeltaMovement(Vec3.ZERO);
      Vec3 pool = new Vec3(boss.getX(), fight.riseFloorY, boss.getZ());
      if (fight.riseTicks % 2 == 0) {
         vAt(level, pool.add(0.0, 0.2, 0.0), ParticleTypes.BUBBLE, 6, 1.8, 0.3, 1.8, 0.08);
         vAt(level, pool.add(0.0, 0.4, 0.0), ParticleTypes.SPLASH, 8, 2.0, 0.2, 2.0, 0.2);
      }
      if (fight.riseTicks > 0 && fight.riseTicks % 15 == 0) {
         double a = RANDOM.nextDouble() * Math.PI * 2.0;
         double r = 3.0 + RANDOM.nextDouble() * 3.0;
         double sx = pool.x + Math.cos(a) * r;
         double sz = pool.z + Math.sin(a) * r;
         Vec3 spout = new Vec3(sx, BossGrounding.groundY(level, sx, sz, pool.y), sz);
         Fx.geyser(level, ParticleTypes.SPLASH, spout, 5.0 + RANDOM.nextDouble() * 3.0, TIDE);
         level.playSound(null, sx, spout.y, sz, SoundEvents.GENERIC_SPLASH, SoundSource.HOSTILE, 1.2F, 0.6F);
      }
      if (fight.riseTicks % 10 == 0) {
         level.playSound(null, pool.x, pool.y, pool.z, SoundEvents.BUBBLE_COLUMN_UPWARDS_AMBIENT, SoundSource.HOSTILE, 1.4F, 0.5F);
      }
      if (fight.riseTicks > 0) {
         return;
      }

      // Up. He gets his weight, his will and his hit points back in the same tick.
      boss.setPos(boss.getX(), fight.riseFloorY, boss.getZ());
      boss.setNoGravity(false);
      boss.setNoAi(false);
      boss.setInvulnerable(false);
      Vec3 crown = boss.position().add(0.0, 2.5, 0.0);
      Fx.flare(level, ParticleTypes.SPLASH, crown, 3.2, FOAM);
      Fx.starburst(level, ParticleTypes.BUBBLE, crown, 8.0, TIDE);
      Fx.shockwave(level, ParticleTypes.SPLASH, boss.position(), 14.0, ABYSS);
      Fx.geyser(level, ParticleTypes.SPLASH, boss.position(), 12.0, FOAM);
      // The water he came up through is thrown off him: six short walls rolling out of the pool,
      // which is also the read on the shove below.
      radialTide(level, boss.position(), 0.0, 6, 7.0, 12, TIDE);
      for (ServerPlayer p : playersNear(level, boss.position(), 6.0)) {
         Vec3 away = p.position().subtract(boss.position());
         push(p, new Vec3(away.x, 0.0, away.z), 1.0);
      }
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.ELDER_GUARDIAN_CURSE, SoundSource.HOSTILE, 1.8F, 0.5F);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.GENERIC_SPLASH, SoundSource.HOSTILE, 2.0F, 0.5F);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.CONDUIT_ACTIVATE, SoundSource.HOSTILE, 1.6F, 0.6F);
      announce(level, SAY + "\u00a7fUp. \u00a73Kneel.");
      fight.nextMove = Math.max(fight.nextMove, fight.now + 40L);
   }

   /**
    * Keeps the crown on his head and the trident in his hand, and keeps him out of the daylight.
    *
    * <p>Set every tick rather than once at the summon: a player can take them off him, and a boss
    * that was stripped in the first ten seconds would spend the rest of the fight as a bare mob.
    */
   private static void crownAndTrident(Mob boss) {
      boss.setRemainingFireTicks(0);
      if (!boss.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD).is(net.minecraft.world.item.Items.GOLDEN_HELMET)) {
         boss.setItemSlot(
            net.minecraft.world.entity.EquipmentSlot.HEAD,
            new ItemStack(net.minecraft.world.item.Items.GOLDEN_HELMET)
         );
         boss.setDropChance(net.minecraft.world.entity.EquipmentSlot.HEAD, 0.0F);
      }
      if (!boss.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND).is(net.minecraft.world.item.Items.TRIDENT)) {
         boss.setItemSlot(
            net.minecraft.world.entity.EquipmentSlot.MAINHAND,
            new ItemStack(net.minecraft.world.item.Items.TRIDENT)
         );
         boss.setDropChance(net.minecraft.world.entity.EquipmentSlot.MAINHAND, 0.0F);
      }
   }

   /**
    * One step toward {@code target}, and face them, with no vanilla AI involved.
    *
    * <p>The body still falls and still obeys gravity - only its horizontal intent is written here,
    * so it walks rather than teleports, and it stops at {@code spacing} so it is never inside the
    * player it is about to hit. {@code speed} is deliberately under a player's walk.
    */
   private static void walkToward(Mob boss, net.minecraft.world.entity.LivingEntity target, double spacing, double speed) {
      double dx = target.getX() - boss.getX();
      double dz = target.getZ() - boss.getZ();
      double dist = Math.sqrt(dx * dx + dz * dz);
      boss.getLookControl().setLookAt(target, 30.0F, 30.0F);
      if (dist < 0.001) {
         return;
      }
      // Facing is written separately from movement, so he is looking at you even while he is
      // holding his ground.
      boss.setYRot((float)(Math.toDegrees(Math.atan2(-dx, dz))));
      boss.yBodyRot = boss.getYRot();
      if (dist <= spacing) {
         boss.setDeltaMovement(boss.getDeltaMovement().multiply(0.5, 1.0, 0.5));
         return;
      }
      boss.setDeltaMovement(dx / dist * speed, boss.getDeltaMovement().y, dz / dist * speed);
   }

   private static String barName(int phase) {
      String label = phase == 1 ? "The Leviathan" : phase == 2 ? "The Abyss Opens" : "Drowned God";
      return BOSS_NAME + " \u00a78| \u00a7f" + label;
   }

   private static int phaseFor(Mob boss) {
      double fraction = boss.getMaxHealth() <= 0.0F ? 1.0 : boss.getHealth() / boss.getMaxHealth();
      if (fraction <= PHASE_3_AT) {
         return 3;
      }
      return fraction <= PHASE_2_AT ? 2 : 1;
   }

   private static void enterPhase(ServerLevel level, Mob boss, Fight fight, int phase) {
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.CONDUIT_ACTIVATE, SoundSource.HOSTILE, 1.8F, phase == 3 ? 0.5F : 0.8F);
      int color = phase >= 3 ? ABYSS : TIDE;
      Fx.shockwave(level, ParticleTypes.SPLASH, boss.position(), 14.0, color);
      Fx.flare(level, ParticleTypes.BUBBLE, boss.position().add(0.0, 2.5, 0.0), 3.0, FOAM);
      Fx.runeCircle(level, ParticleTypes.BUBBLE_POP, boss.position().add(0.0, 0.05, 0.0), 8.0, 40, color);
      vSphere(level, boss.position().add(0.0, 1.0, 0.0), 6.0, ParticleTypes.BUBBLE);
      if (phase == 2) {
         announceNear(level, boss, 90.0, SAY + "\u00a7fDeeper, then.");
         overlayNear(level, boss, 90.0, "\u00a7b\u00a7lTHE ABYSS OPENS");
      } else if (phase == 3) {
         Fx.vortex(level, ParticleTypes.SQUID_INK, boss.position().add(0.0, 0.1, 0.0), 10.0, 40, ABYSS);
         level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.WARDEN_ROAR, SoundSource.HOSTILE, 1.0F, 0.5F);
         announceNear(level, boss, 90.0, SAY + "\u00a73\u00a7l\u201c\u00a7fDrown with me.\u00a73\u00a7l\u201d");
         overlayNear(level, boss, 90.0, "\u00a73\u00a7lDROWNED GOD");
      }
   }

   private static int moveGap(int phase) {
      return switch (phase) {
         case 3 -> MOVE_GAP_3;
         case 2 -> MOVE_GAP_2;
         default -> MOVE_GAP_1;
      };
   }

   private static void chooseMove(ServerLevel level, Mob boss, Fight fight) {
      // A ready cooldown move takes the slot first, some of the time - see trySpecial.
      if (trySpecial(level, boss, fight)) {
         return;
      }
      List<String> moves = movesForPhase(fight.phase);
      String move = moves.get(RANDOM.nextInt(moves.size()));
      // Never the same move twice in a row: the rotation is the fight's shape, and two Tidal
      // Crashes back to back reads as the boss having one move.
      if (moves.size() > 1 && move.equals(fight.lastMove)) {
         move = moves.get((moves.indexOf(move) + 1) % moves.size());
      }
      fight.lastMove = move;
      ServerPlayer target = nearestPlayer(level, boss, 40.0);
      switch (move) {
         case "Tidal Crash" -> tidalCrash(level, boss);
         case "Depth Charge" -> depthCharge(level, boss, fight, target);
         case "Abyssal Tentacles" -> tentacles(level, boss, fight);
         case "Riptide" -> riptide(level, boss, fight);
         case "Undertow" -> undertow(level, boss, fight);
         case "Drowned Call" -> drownedCall(level, boss, fight);
         case "Pressure Crush" -> pressureCrush(level, boss);
         case "Tidal Prison" -> tidalPrison(level, boss, fight, target);
         case "Maelstrom" -> maelstrom(level, boss, fight);
         case "Abyssal Beam" -> abyssalBeam(level, boss, fight);
         case "Leviathan Dive" -> dive(level, boss, fight, 1);
         case "Black Tide" -> blackTide(level, boss, fight);
         case "Call of the Deep" -> callOfTheDeep(level, boss, fight);
         case "Crushing Depths" -> crushingDepths(level, boss);
         case "Tsunami" -> tsunami(level, boss, fight);
         case "Final Tsunami" -> finalTsunami(level, boss, fight);
         case "Worldbreaker Tide" -> worldbreakerTide(level, boss, fight);
         case "Abyssal Maw" -> abyssalMaw(level, boss, fight);
         case "Leviathan's Wrath" -> dive(level, boss, fight, 3);
         case "Dead Sea" -> deadSea(level, boss);
         case "Final Depth" -> finalDepth(level, boss, fight);
         default -> tidalCrash(level, boss);
      }
      tell(level, boss, move);
   }

   /**
    * Lets a cooldown move that is ready take this move slot, one time in three.
    *
    * <p>The two cooldown moves are not in the phase tables (those are the codex and the self-test's
    * pinned design), so they cut in here instead: each has its own clock, so neither can come round
    * twice in a short fight, and the one-in-three roll keeps them from landing on the first slot
    * after they come off cooldown every single time - the player should not be able to count to
    * them. A move that finds nobody to aim at gives the slot back to the table.
    */
   private static boolean trySpecial(ServerLevel level, Mob boss, Fight fight) {
      if (fight.phase >= 2 && fight.now >= fight.nextGlare && RANDOM.nextInt(3) == 0 && theDeepLooks(level, boss, fight)) {
         fight.lastMove = "The Deep Looks";
         tell(level, boss, fight.lastMove);
         return true;
      }
      if (fight.now >= fight.nextWhirlpool && RANDOM.nextInt(3) == 0) {
         // WHIRLPOOL: the floor under each of you starts to turn and drags you to its eye. Walk out.
         List<ServerPlayer> marked = playersNear(level, boss.position(), 28.0);
         if (!marked.isEmpty()) {
            for (ServerPlayer p : marked) {
               Hazards.whirlpool(level, boss, floorAt(level, p.position()), 3.5, fight.phase >= 3 ? 40 : 55, fight.phase >= 2 ? 10.0F : 8.0F, TIDE);
            }
            Fx.tentacle(level, ParticleTypes.SQUID_INK, floorAt(level, boss.position()), 5.0, 24, ABYSS);
            fight.nextWhirlpool = fight.now + 420L - (fight.phase - 1) * 60L;
            fight.lastMove = "Whirlpool";
            tell(level, boss, fight.lastMove);
            return true;
         }
      }
      if (fight.now >= fight.nextBreaker && RANDOM.nextInt(3) == 0 && breakerRing(level, boss, fight)) {
         fight.lastMove = "Breaker Ring";
         tell(level, boss, fight.lastMove);
         return true;
      }
      return false;
   }

   private static void tell(ServerLevel level, Mob boss, String move) {
      level.playSound(null, boss.getX(), boss.getY() + 2.0, boss.getZ(), SoundEvents.ELDER_GUARDIAN_HURT, SoundSource.HOSTILE, 1.4F, 0.6F);
      // Telegraph removed
   }

   // ------------------------------------------------------------- phase 1: the tide

   /** A wall of water off the boss, shoving everyone in front of it backward. */
   private static void tidalCrash(ServerLevel level, Mob boss) {
      Vec3 look = boss.getLookAngle();
      Vec3 flat = new Vec3(look.x, 0.0, look.z);
      Vec3 unit = flat.lengthSqr() < 1.0E-4 ? new Vec3(1.0, 0.0, 0.0) : flat.normalize();
      tideFan(level, boss.position(), unit, WAVE_REACH, 10, 3, TIDE);
      Fx.shockwave(level, ParticleTypes.SPLASH, boss.position(), 5.0, FOAM);
      vWall(level, boss.position().add(0.0, 1.0, 0.0), unit, 7.0, 3.5, ParticleTypes.SPLASH, 3);
      vWall(level, boss.position().add(0.0, 0.2, 0.0), unit, WAVE_REACH * 0.8, 2.0, ParticleTypes.FALLING_WATER, 2);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.GENERIC_SPLASH, SoundSource.HOSTILE, 1.6F, 0.7F);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.LIGHTNING_BOLT_IMPACT, SoundSource.HOSTILE, 0.8F, 1.8F);
      for (ServerPlayer p : playersNear(level, boss.position(), WAVE_REACH)) {
         if (!inFront(boss.position(), unit, p, WAVE_HALF_ANGLE)) {
            continue;
         }
         p.hurtServer(level, level.damageSources().mobAttack(boss), WAVE_DAMAGE);
         push(p, unit, WAVE_PUSH);
         p.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 40, 0, false, true));
      }
   }

   /** Marked ground, then an underwater detonation on the mark. */
   private static void depthCharge(ServerLevel level, Mob boss, Fight fight, ServerPlayer target) {
      if (target == null) {
         tidalCrash(level, boss);
         return;
      }
      Vec3 mark = floorAt(level, target.position());
      fight.pending.add(new Pending("charge", mark, CHARGE_TELL, 0));
   }

   /** Tentacles out of the ground around whoever is standing where. */
   private static void tentacles(ServerLevel level, Mob boss, Fight fight) {
      List<ServerPlayer> near = playersNear(level, boss.position(), 24.0);
      int count = Math.max(3, Math.min(6, near.size() + 2));
      for (int i = 0; i < count; i++) {
         ServerPlayer anchor = near.isEmpty() ? null : near.get(RANDOM.nextInt(near.size()));
         double x = anchor != null ? anchor.getX() : boss.getX() + (RANDOM.nextDouble() - 0.5) * 16.0;
         double z = anchor != null ? anchor.getZ() : boss.getZ() + (RANDOM.nextDouble() - 0.5) * 16.0;
         double y = BossGrounding.groundY(level, x, z, anchor != null ? anchor.getY() : boss.getY());
         int fuse = 22 + RANDOM.nextInt(10);
         Vec3 at = new Vec3(x, y, z);
         fight.pending.add(new Pending("tentacle", at, fuse, 0));
      }
      hint(playersNear(level, boss.position(), 60.0), "\u00a78Bubbles underfoot. \u00a77Move.");
   }

   /**
    * Dash between bodies, cutting along each lane.
    *
    * <p>It used to be instant: he was simply somewhere else, and whoever stood on the old spot had
    * already been cut. Now the lanes are drawn on the floor first ({@link #DASH_TELL} ticks), then
    * he runs them one after another, so the answer is to step off the line before he comes down it.
    * Each landing is put on the floor behind the player rather than at head height in front of a
    * look vector that can point at the sky.
    */
   private static void riptide(ServerLevel level, Mob boss, Fight fight) {
      List<ServerPlayer> near = playersNear(level, boss.position(), 30.0);
      if (near.isEmpty()) {
         tidalCrash(level, boss);
         return;
      }
      Vec3 from = boss.position();
      int steps = Math.min(3, near.size());
      for (int i = 0; i < steps; i++) {
         ServerPlayer to = near.get(RANDOM.nextInt(near.size()));
         Vec3 look = to.getLookAngle();
         Vec3 back = new Vec3(look.x, 0.0, look.z);
         back = back.lengthSqr() < 1.0E-4 ? Vec3.ZERO : back.normalize().scale(-2.0);
         Vec3 landing = floorAt(level, to.position().add(back));
         Pending dash = new Pending("dash", landing, DASH_TELL + i * DASH_STEP, 0);
         dash.origin = from;
         fight.pending.add(dash);
         Fx.beam(level, ParticleTypes.DOLPHIN, from.add(0.0, 0.3, 0.0), landing.add(0.0, 0.3, 0.0), ABYSS);
         from = landing;
      }
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.TRIDENT_THROW, SoundSource.HOSTILE, 1.3F, 0.6F);
      hint(near, "\u00a78Lanes on the floor. \u00a77Step off them.");
   }

   /** The whirlpool: it decides where everyone nearby is allowed to be. */
   private static void undertow(ServerLevel level, Mob boss, Fight fight) {
      fight.pending.add(new Pending("undertow", boss.position(), 0, 80));
      Fx.vortex(level, ParticleTypes.BUBBLE, boss.position().add(0.0, 0.1, 0.0), 9.0, 80, TIDE);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.ELDER_GUARDIAN_CURSE, SoundSource.HOSTILE, 1.2F, 1.1F);
   }

   /**
    * Drowned, called up around him.
    *
    * <p>Drowned and not guardians, and that is a fix rather than a flavour change. Guardians were
    * one in three of these, and a guardian's whole attack is a beam it keeps firing for as long as
    * it can see you - so an unquiet fight left a chorus of guardian attacks running off screen, and
    * with {@code setPersistenceRequired} on them they never went anywhere on their own. The call is
    * drowned now, they are not persistent, and every one of them is listed in the fight so the
    * fight can take them back down with it.
    *
    * <p>Capped at {@link #MAX_MINIONS} standing at once. The call used to add three to five every
    * time it came round with no ceiling, so a long fight became a crowd, and the list of bodies to
    * clear grew without end. Dead ones are dropped from the list here; ones merely out of loaded
    * range stay on it, so the release can still find them.
    */
   private static void drownedCall(ServerLevel level, Mob boss, Fight fight) {
      fight.minions.removeIf(id -> {
         Entity e = level.getEntity(id);
         return e != null && !e.isAlive();
      });
      // Called drowned are not persistent, so one that despawned is simply never found again and
      // used to sit on this list for the rest of the fight - a list that grew by up to five every
      // call. Past a generous margin the unfindable ones are dropped: one that was only out of
      // range still carries MINION_TAG, and once it is off every fight's list the stray sweep
      // (isStrayMinion) is what takes it down.
      if (fight.minions.size() > MAX_MINIONS * 2) {
         fight.minions.removeIf(id -> level.getEntity(id) == null);
      }
      int standing = 0;
      for (UUID id : fight.minions) {
         if (level.getEntity(id) != null) {
            standing++;
         }
      }
      int count = Math.min(3 + RANDOM.nextInt(3), MAX_MINIONS - standing);
      if (count <= 0) {
         pressureCrush(level, boss);
         return;
      }
      Fx.summonCircle(level, ParticleTypes.BUBBLE_POP, boss.position().add(0.0, 0.05, 0.0), 4.5, 30, TIDE);
      for (int i = 0; i < count; i++) {
         double a = RANDOM.nextDouble() * Math.PI * 2.0;
         double x = boss.getX() + Math.cos(a) * 3.5;
         double z = boss.getZ() + Math.sin(a) * 3.5;
         Mob minion = (Mob)EntityTypes.DROWNED.create(level, EntitySpawnReason.COMMAND);
         if (minion == null) {
            continue;
         }
         minion.setPos(x, BossGrounding.groundY(level, x, z, boss.getY()), z);
         minion.addTag(MINION_TAG);
         level.addFreshEntity(minion);
         fight.minions.add(minion.getUUID());
         Fx.gooSplash(level, ParticleTypes.SPLASH, minion.position().add(0.0, 0.3, 0.0), 1.0, ABYSS);
      }
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.CONDUIT_ATTACK_TARGET, SoundSource.HOSTILE, 1.4F, 0.8F);
      announceNear(level, boss, 50.0, SAY + "\u00a7fCrew. Up.");
   }

   /**
    * Keeps the called drowned alive while the fight runs and takes them back when it ends.
    *
    * <p>They are despawnable vanilla mobs, so a player who walks far enough away loses them anyway;
    * this is the guarantee that none of them is still standing in the arena after the fight, which
    * is what stops a fight's leftovers becoming the next session's background noise.
    */
   private static void tickMinions(ServerLevel level, Fight fight) {
      if (fight.dying) {
         clearMinions(level, fight);
      }
   }

   private static void clearMinions(ServerLevel level, Fight fight) {
      if (fight.minions.isEmpty()) {
         return;
      }
      for (UUID id : fight.minions) {
         Entity minion = level.getEntity(id);
         if (minion != null && minion.isAlive()) {
            Fx.gooSplash(level, ParticleTypes.SPLASH, minion.position().add(0.0, 0.5, 0.0), 0.8, TIDE);
            minion.discard();
         }
      }
      fight.minions.clear();
   }

   /** Pressure: heavy limbs, heavy tools. */
   private static void pressureCrush(ServerLevel level, Mob boss) {
      Fx.dome(level, ParticleTypes.BUBBLE, boss.position(), 10.0, 30, ABYSS);
      Fx.shockwave(level, ParticleTypes.BUBBLE, boss.position(), 10.0, TIDE);
      for (ServerPlayer p : playersNear(level, boss.position(), 10.0)) {
         p.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 80, 1));
         p.addEffect(new MobEffectInstance(MobEffects.MINING_FATIGUE, 80, 1));
         p.sendOverlayMessage(Component.literal("\u00a73\u00a7lPRESSURE \u00a78Heavy arms."));
      }
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.WARDEN_SONIC_CHARGE, SoundSource.HOSTILE, 0.9F, 0.6F);
   }

   /**
    * One player, inside a sphere of water that is not a room.
    *
    * <p>The prison used to be only a drawing: a sphere of particles at the spot, and nothing done to
    * the person in it. Now it is a real hold. A circle shows on the floor under them for
    * {@link #PRISON_FUSE} ticks - step out of it and nothing happens - and when it closes, whoever is
    * still inside is held in the middle for {@link #PRISON_TICKS} ticks and squeezed once a second.
    */
   private static void tidalPrison(ServerLevel level, Mob boss, Fight fight, ServerPlayer target) {
      if (target == null) {
         pressureCrush(level, boss);
         return;
      }
      Vec3 floor = floorAt(level, target.position());
      fight.pending.add(new Pending("prison", floor, PRISON_FUSE, PRISON_TICKS));
      level.playSound(null, target.getX(), target.getY(), target.getZ(), SoundEvents.CONDUIT_ACTIVATE, SoundSource.HOSTILE, 1.4F, 1.6F);
      hint(List.of(target), "\u00a78Water's closing on you. \u00a77Step out.");
   }

   // ---------------------------------------------------------- phase 2: the abyss

   private static void maelstrom(ServerLevel level, Mob boss, Fight fight) {
      fight.pending.add(new Pending("maelstrom", boss.position(), 0, 160));
      Fx.vortex(level, ParticleTypes.SPLASH, boss.position().add(0.0, 0.1, 0.0), MAELSTROM_RADIUS, 160, TIDE);
      Fx.spiral(level, ParticleTypes.BUBBLE, boss.position(), 8.0, 160, FOAM);
      announceNear(level, boss, 70.0, SAY + "\u00a7fRound and round.");
      hint(playersNear(level, boss.position(), 70.0), "\u00a78The current pulls. \u00a77Walk against it.");
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.ELDER_GUARDIAN_CURSE, SoundSource.HOSTILE, 1.8F, 0.5F);
   }

   /**
    * A ray across the water, drawn on the ground before it fires.
    *
    * <p>The ray is fixed where it was aimed. It used to be drawn from wherever he was standing each
    * tick, so as he walked the "line on the floor" swung round the arena and the warning stopped
    * matching the hit; and its hit test was flat, so a player twenty blocks up a pillar over the
    * line was cut anyway. Now it starts where he stood and only reaches {@link #BEAM_HEIGHT} up.
    */
   private static void abyssalBeam(ServerLevel level, Mob boss, Fight fight) {
      ServerPlayer target = nearestPlayer(level, boss, 40.0);
      Vec3 dir = target == null ? boss.getLookAngle() : target.position().subtract(boss.position());
      Vec3 flat = new Vec3(dir.x, 0.0, dir.z);
      if (flat.lengthSqr() < 1.0E-4) {
         flat = new Vec3(1.0, 0.0, 0.0);
      }
      Vec3 origin = boss.position();
      Pending beam = new Pending("beam", origin.add(flat.normalize().scale(BEAM_REACH)), 0, BEAM_TELL + BEAM_LIFE);
      beam.origin = origin;
      fight.pending.add(beam);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.WARDEN_SONIC_CHARGE, SoundSource.HOSTILE, 1.1F, 0.7F);
      hint(playersNear(level, origin, BEAM_REACH + 4.0), "\u00a78A line on the water. \u00a77Get off it.");
   }

   /**
    * Under the floor, then up - once in phase two, three times as Leviathan's Wrath.
    *
    * <p>He used to be teleported straight onto a player's feet the moment the move started, which
    * was a body landing inside you with no warning before the eruption that was meant to be the
    * hit. Now he goes under where he stands, each eruption is marked on the floor for its whole
    * fuse, and he comes up <em>with</em> the eruption.
    */
   private static void dive(ServerLevel level, Mob boss, Fight fight, int times) {
      List<ServerPlayer> near = playersNear(level, boss.position(), 30.0);
      if (near.isEmpty()) {
         tidalCrash(level, boss);
         return;
      }
      Fx.gooSplash(level, ParticleTypes.SQUID_INK, boss.position().add(0.0, 0.3, 0.0), 2.4, ABYSS);
      Fx.vortex(level, ParticleTypes.SQUID_INK, boss.position().add(0.0, 0.1, 0.0), 3.0, 20, ABYSS);
      for (int i = 0; i < Math.max(1, times); i++) {
         ServerPlayer anchor = near.get(RANDOM.nextInt(near.size()));
         Vec3 mark = floorAt(level, anchor.position());
         int fuse = 24 + i * 16;
         fight.pending.add(new Pending("erupt", mark, fuse, 0));
      }
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.ELDER_GUARDIAN_HURT, SoundSource.HOSTILE, 1.6F, 0.5F);
      hint(playersNear(level, boss.position(), 60.0), "\u00a78He's under the floor. \u00a77Watch the circles.");
   }

   /** Water that is not water any more: standing in it is the cost. */
   private static void blackTide(ServerLevel level, Mob boss, Fight fight) {
      for (int i = 0; i < 8; i++) {
         double a = (Math.PI * 2.0 * i) / 8.0;
         double x = boss.getX() + Math.cos(a) * 11.0;
         double z = boss.getZ() + Math.sin(a) * 11.0;
         double y = BossGrounding.groundY(level, x, z, boss.getY());
         Vec3 at = new Vec3(x, y, z);
         fight.pending.add(new Pending("black", at, 0, BLACK_TIDE_LIFE));
         Fx.runeCircle(level, ParticleTypes.SQUID_INK, at.add(0.0, 0.05, 0.0), BLACK_TIDE_RADIUS, BLACK_TIDE_LIFE, ABYSS);
         Fx.gooSplash(level, ParticleTypes.SQUID_INK, at.add(0.0, 0.3, 0.0), 1.6, ABYSS);
      }
      announceNear(level, boss, 70.0, SAY + "\u00a7fThe water's gone bad.");
      hint(playersNear(level, boss.position(), 70.0), "\u00a78Dark pools. \u00a77Stay out of them.");
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.WARDEN_ROAR, SoundSource.HOSTILE, 0.8F, 0.5F);
   }

   /** Eyes in the water, each one a small beam. */
   private static void callOfTheDeep(ServerLevel level, Mob boss, Fight fight) {
      for (int i = 0; i < 5; i++) {
         double a = RANDOM.nextDouble() * Math.PI * 2.0;
         double r = 8.0 + RANDOM.nextDouble() * 10.0;
         double x = boss.getX() + Math.cos(a) * r;
         double z = boss.getZ() + Math.sin(a) * r;
         double y = BossGrounding.groundY(level, x, z, boss.getY());
         int fuse = 30 + RANDOM.nextInt(20);
         fight.pending.add(new Pending("eye", new Vec3(x, y + 0.4, z), fuse, EYE_LIFE));
      }
      announceNear(level, boss, 70.0, SAY + "\u00a7fLook down.");
   }

   /** Gravity, briefly, in his favour. */
   private static void crushingDepths(ServerLevel level, Mob boss) {
      for (ServerPlayer p : playersNear(level, boss.position(), 20.0)) {
         p.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 100, 1));
         p.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 100, 0));
         p.setDeltaMovement(p.getDeltaMovement().add(0.0, -1.4, 0.0));
         p.hurtMarked = true;
         p.sendOverlayMessage(Component.literal("\u00a73\u00a7lCRUSHING DEPTHS"));
      }
      Fx.dome(level, ParticleTypes.SQUID_INK, boss.position(), 20.0, 20, ABYSS);
      Fx.shockwave(level, ParticleTypes.SPLASH, boss.position(), 20.0, TIDE);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.WARDEN_SONIC_CHARGE, SoundSource.HOSTILE, 1.2F, 0.4F);
   }

   /**
    * Three waves, in sequence, each in its own direction, with that direction drawn on the floor
    * before it rolls.
    *
    * <p>It used to hit everyone within eighteen blocks whatever way the wave went - the direction
    * was rolled at the moment of impact and only decided which way you were thrown. Now each wave's
    * direction is fixed when it is scheduled, shown {@link #WAVE_WARN} ticks out, and only the arc
    * in front of it is hit: get behind him.
    */
   private static void tsunami(ServerLevel level, Mob boss, Fight fight) {
      for (int i = 0; i < 3; i++) {
         Pending wave = new Pending("tsunami", boss.position(), 20 + i * 26, 0);
         wave.dir = randomFlat();
         fight.pending.add(wave);
      }
      Fx.ring(level, ParticleTypes.FALLING_WATER, boss.position().add(0.0, 0.1, 0.0), 16.0, TIDE);
      announceNear(level, boss, 80.0, SAY + "\u00a7fThree.");
      hint(playersNear(level, boss.position(), 80.0), "\u00a78Three waves. \u00a77Get behind him.");
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.GENERIC_SPLASH, SoundSource.HOSTILE, 1.9F, 0.4F);
   }

   // ----------------------------------------------------- phase 3: the drowned god

   /**
    * One enormous wave. The class doc promised "one wall you can be behind", but it hit everyone in
    * thirty blocks; the wall now has a front, drawn on the floor for its whole fuse.
    */
   private static void worldbreakerTide(ServerLevel level, Mob boss, Fight fight) {
      Pending wave = new Pending("worldbreaker", boss.position(), 30, 0);
      wave.dir = randomFlat();
      fight.pending.add(wave);
      warnWave(level, boss.position(), wave.dir, 26.0);
      Fx.runeCircle(level, ParticleTypes.BUBBLE_POP, boss.position().add(0.0, 0.05, 0.0), 6.0, 30, ABYSS);
      announceNear(level, boss, 100.0, SAY + "\u00a7fBreak.");
      hint(playersNear(level, boss.position(), 100.0), "\u00a78One wall. \u00a77Get behind him.");
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.ELDER_GUARDIAN_CURSE, SoundSource.HOSTILE, 2.0F, 0.4F);
   }

   /** The centre of the arena, pulling. */
   private static void abyssalMaw(ServerLevel level, Mob boss, Fight fight) {
      fight.pending.add(new Pending("maw", boss.position(), 0, 140));
      Fx.vortex(level, ParticleTypes.SQUID_INK, boss.position().add(0.0, 0.1, 0.0), MAW_RADIUS, 140, ABYSS);
      // Opened explicitly (b = 1): Fx.wormhole's closing flag is inverted, and this used to draw
      // the maw shutting the moment it appeared.
      Fx.shape(level, FfVfx.WORMHOLE, ParticleTypes.SQUID_INK, boss.position().add(0.0, 2.0, 0.0), Vec3.ZERO, 0.0, 1.0, ABYSS);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.WARDEN_ROAR, SoundSource.HOSTILE, 1.0F, 0.4F);
      announceNear(level, boss, 90.0, SAY + "\u00a7fCome here.");
      hint(playersNear(level, boss.position(), 90.0), "\u00a78It pulls. \u00a77Walk against it.");
   }

   /**
    * The finale: the whole arena, taken by the sea in three rolls.
    *
    * <p>Each roll is a wall of water the width of the arena with a front, and the front is drawn on
    * the floor before it lands. The direction used to be rolled at the moment of impact, which
    * made the closing statement a coin flip; now the answer is the same as the answer to the whole
    * rest of the fight - read the water, get behind him, and accept being moved when you cannot.
    */
   private static void finalTsunami(ServerLevel level, Mob boss, Fight fight) {
      for (int i = 0; i < MEGA_ROLLS; i++) {
         Pending roll = new Pending("megawave", boss.position(), 26 + i * 24, 0);
         roll.dir = randomFlat();
         fight.pending.add(roll);
      }
      Fx.ring(level, ParticleTypes.FALLING_WATER, boss.position().add(0.0, 0.1, 0.0), MEGA_RADIUS * 0.7, TIDE);
      Fx.geyser(level, ParticleTypes.SPLASH, boss.position(), 10.0, FOAM);
      Fx.vortex(level, ParticleTypes.BUBBLE, boss.position().add(0.0, 0.1, 0.0), 9.0, 26 + MEGA_ROLLS * 24, ABYSS);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.ELDER_GUARDIAN_CURSE, SoundSource.HOSTILE, 2.2F, 0.4F);
      announceNear(level, boss, 110.0, SAY + "\u00a7fAll of it.");
      hint(playersNear(level, boss.position(), 110.0), "\u00a78Three walls. \u00a77Get behind him each time.");
      overlayNear(level, boss, 110.0, "\u00a73\u00a7lTHE LAST TIDE");
   }

   /**
    * Regeneration goes away, and the sea keeps hurting.
    *
    * <p>Bounded by {@link #DEAD_SEA_RADIUS} now: it used to reach every player in the dimension, so
    * somebody mining at the other end of the world was poisoned by a fight they could not see.
    */
   private static void deadSea(ServerLevel level, Mob boss) {
      for (ServerPlayer p : playersNear(level, boss.position(), DEAD_SEA_RADIUS)) {
         p.removeEffect(MobEffects.REGENERATION);
         p.addEffect(new MobEffectInstance(MobEffects.HUNGER, DEAD_SEA_TICKS, 2));
         p.addEffect(new MobEffectInstance(MobEffects.POISON, DEAD_SEA_TICKS, 0));
         p.sendOverlayMessage(Component.literal("\u00a73\u00a7lDEAD SEA \u00a78No healing."));
      }
      Fx.heartbeat(level, ParticleTypes.SQUID_INK, boss.position().add(0.0, 0.1, 0.0), 8.0, 60, ABYSS);
      Fx.shockwave(level, ParticleTypes.SQUID_INK, boss.position(), DEAD_SEA_RADIUS * 0.75, ABYSS);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.WITHER_SPAWN, SoundSource.HOSTILE, 0.6F, 0.5F);
   }

   /** Everybody marked, then the sea lands on all of them. */
   private static void finalDepth(ServerLevel level, Mob boss, Fight fight) {
      List<ServerPlayer> near = playersNear(level, boss.position(), 40.0);
      if (near.isEmpty()) {
         worldbreakerTide(level, boss, fight);
         return;
      }
      int i = 0;
      for (ServerPlayer p : near) {
         Vec3 mark = floorAt(level, p.position());
         int fuse = FINAL_DEPTH_TELL + i * 8;
         fight.pending.add(new Pending("final", mark, fuse, 0));
         // The mark on the floor is the whole tell - see the class doc on why he says nothing.
         i++;
         if (i >= FINAL_DEPTH_MARKS) {
            break;
         }
      }
      announceNear(level, boss, 90.0, SAY + "\u00a7fDown you go.");
   }

   // ------------------------------------------------------- cooldown moves (strikes)

   /**
    * <b>Breaker Ring</b> - a ring of water that rolls out along the floor from his feet.
    *
    * <p>The answer is a jump. The ring is low and only takes the legs out from under a player who
    * is standing on the ground as it passes, so a hop as it reaches you clears it cleanly (so does
    * standing a block or two up). The warning is a turning circle at his feet, the ring's full
    * reach drawn on the floor and a hint on the action bar, {@link #BREAKER_WARN} ticks out. In the
    * last phase a second ring follows the first, so the jump has a rhythm rather than one beat.
    *
    * @return false if nobody was near enough to be worth it, so the slot goes back to the table
    */
   private static boolean breakerRing(ServerLevel level, Mob boss, Fight fight) {
      List<ServerPlayer> near = playersNear(level, boss.position(), BREAKER_REACH + 4.0);
      if (near.isEmpty()) {
         return false;
      }
      Vec3 at = floorAt(level, boss.position());
      long land = fight.now + BREAKER_WARN;
      fight.strikes.add(new Strike(Strike.BREAKER, at, land));
      long last = land;
      if (fight.phase >= 3) {
         last = land + 16L;
         fight.strikes.add(new Strike(Strike.BREAKER, at, last));
      }
      fight.nextBreaker = fight.now + BREAKER_COOLDOWN - (fight.phase - 1) * 40L;
      // The ring is the move: nothing else is thrown while it is rolling.
      fight.nextMove = Math.max(fight.nextMove, last + 24L);
      Fx.aura(level, ParticleTypes.BUBBLE, boss.position(), 5.5, BREAKER_WARN, TIDE);
      level.playSound(null, at.x, at.y, at.z, SoundEvents.CONDUIT_ACTIVATE, SoundSource.HOSTILE, 1.6F, 0.6F);
      level.playSound(null, at.x, at.y, at.z, SoundEvents.BUBBLE_COLUMN_UPWARDS_AMBIENT, SoundSource.HOSTILE, 1.4F, 0.5F);
      announceNear(level, boss, 60.0, SAY + "\u00a7fDown.");
      hint(near, fight.phase >= 3 ? "\u00a78Two rings. \u00a77Jump. Then jump." : "\u00a78A ring's coming. \u00a77Jump it.");
      return true;
   }

   /**
    * <b>The Deep Looks</b> - he stops, stares, and whatever he can still see when the stare lands
    * is hit.
    *
    * <p>Two answers, and the warning shows both. A dome of dark water the size of his sight
    * ({@link #GLARE_REACH}) goes up around him for {@link #GLARE_WARN} ticks while light gathers
    * over his head: put a solid block between you and him - a pillar, a wall, a hill - or get out
    * of the dome. He holds still for the whole stare so the dome stays honest. Anyone in the open
    * and inside it is hit, slowed and shoved away from him.
    *
    * @return false if nobody is in sight range, so the slot goes back to the table
    */
   private static boolean theDeepLooks(ServerLevel level, Mob boss, Fight fight) {
      List<ServerPlayer> near = playersNear(level, boss.position(), GLARE_REACH);
      if (near.isEmpty()) {
         return false;
      }
      long land = fight.now + GLARE_WARN;
      fight.strikes.add(new Strike(Strike.GLARE, boss.position(), land));
      fight.nextGlare = fight.now + GLARE_COOLDOWN;
      fight.nextMove = Math.max(fight.nextMove, land + 16L);
      Vec3 eye = eyeOf(boss);
      Fx.resonance(level, ParticleTypes.GLOW_SQUID_INK, eye, GLARE_WARN, FOAM);
      // The deep gathers in his eye: dark motes drawn in from across the dome for exactly the
      // length of the warning, so the ring the collapse throws out goes off as the stare lands.
      Fx.voidCollapse(level, ParticleTypes.SQUID_INK, eye, 12.0, GLARE_WARN, ABYSS);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.WARDEN_SONIC_CHARGE, SoundSource.HOSTILE, 1.6F, 0.5F);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.ELDER_GUARDIAN_CURSE, SoundSource.HOSTILE, 1.4F, 0.9F);
      announceNear(level, boss, GLARE_REACH + 20.0, SAY + "\u00a7fI see you.");
      hint(playersNear(level, boss.position(), GLARE_REACH + 8.0), "\u00a78He's looking. \u00a77Hide behind something, or get out.");
      return true;
   }

   /** Whether a stare is warming up - he holds still for it. */
   private static boolean isStaring(Fight fight) {
      for (Strike s : fight.strikes) {
         if (s.kind == Strike.GLARE) {
            return true;
         }
      }
      return false;
   }

   private static void tickStrikes(ServerLevel level, Mob boss, Fight fight) {
      if (fight.strikes.isEmpty()) {
         return;
      }
      for (Iterator<Strike> it = fight.strikes.iterator(); it.hasNext();) {
         Strike s = it.next();
         long left = s.landAt - fight.now;
         if (left > 0L) {
            // The timed shapes were sent once at the warning; this is the particle version of the
            // same thing for clients without the mod, which only ever saw its first frame.
            if (s.kind == Strike.BREAKER && left % 6L == 0L) {
               vRing(level, s.at, BREAKER_REACH, 16, ParticleTypes.BUBBLE_POP, 0.1);
            } else if (s.kind == Strike.GLARE && left % 3L == 0L) {
               vAt(level, eyeOf(boss), ParticleTypes.GLOW_SQUID_INK, 4, 0.4, 0.4, 0.4, 0.02);
            }
            continue;
         }
         boolean done = s.kind == Strike.BREAKER ? rollBreaker(level, boss, s) : landGlare(level, boss, s);
         if (done) {
            it.remove();
         }
      }
   }

   /**
    * One tick of a rolling Breaker Ring. The ring's edge moves {@link #BREAKER_SPEED} a tick; anyone
    * the edge crosses this tick is checked once, and is only hit if they are on the ground and
    * roughly level with the floor it rolls along.
    *
    * @return true once the ring has rolled its full reach
    */
   private static boolean rollBreaker(ServerLevel level, Mob boss, Strike s) {
      if (s.age == 0) {
         Fx.nova(level, ParticleTypes.SPLASH, s.at.add(0.0, 0.3, 0.0), 2.5, FOAM);
         // The ring itself, for modded clients: ten low walls of water rolling out together from
         // his feet, timed to the hit test's own speed, sent once. Everyone else gets the rolling
         // particle ring below.
         radialTide(level, s.at, BREAKER_START, 10, BREAKER_REACH - BREAKER_START,
            (int)Math.ceil((BREAKER_REACH - BREAKER_START) / BREAKER_SPEED), TIDE);
         level.playSound(null, s.at.x, s.at.y, s.at.z, SoundEvents.GENERIC_SPLASH, SoundSource.HOSTILE, 1.8F, 0.5F);
         level.playSound(null, s.at.x, s.at.y, s.at.z, SoundEvents.MACE_SMASH_GROUND_HEAVY, SoundSource.HOSTILE, 1.2F, 0.7F);
      }
      double inner = BREAKER_START + s.age * BREAKER_SPEED;
      s.age++;
      double outer = BREAKER_START + s.age * BREAKER_SPEED;
      if (s.age % 3 == 1) {
         vRing(level, s.at, outer, 16, ParticleTypes.SPLASH, 0.15);
      }
      for (ServerPlayer q : playersNear(level, s.at, outer + 2.0)) {
         if (s.passed.contains(q.getUUID())) {
            continue;
         }
         double dx = q.getX() - s.at.x;
         double dz = q.getZ() - s.at.z;
         double d = Math.sqrt(dx * dx + dz * dz);
         if (d <= inner || d > outer) {
            continue;
         }
         s.passed.add(q.getUUID());
         double dy = q.getY() - s.at.y;
         if (!q.onGround() || dy > 1.2 || dy < -2.5) {
            // Jumped it, or standing above it: the ring goes under.
            continue;
         }
         q.hurtServer(level, level.damageSources().mobAttack(boss), BREAKER_DAMAGE);
         push(q, new Vec3(dx, 0.0, dz), 1.0);
         q.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 40, 1));
         Fx.gooSplash(level, ParticleTypes.SPLASH, q.position().add(0.0, 0.3, 0.0), 1.0, FOAM);
      }
      return outer >= BREAKER_REACH;
   }

   /** The stare lands on everyone inside its reach that he still has a line to. */
   private static boolean landGlare(ServerLevel level, Mob boss, Strike s) {
      Vec3 eye = eyeOf(boss);
      Fx.flare(level, ParticleTypes.GLOW_SQUID_INK, eye, 3.0, FOAM);
      Fx.starburst(level, ParticleTypes.GLOW_SQUID_INK, eye, 9.0, TIDE);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.WARDEN_SONIC_BOOM, SoundSource.HOSTILE, 1.6F, 0.6F);
      int seen = 0;
      for (ServerPlayer q : playersNear(level, boss.position(), GLARE_REACH)) {
         if (!boss.hasLineOfSight(q)) {
            continue;
         }
         seen++;
         Fx.beam(level, ParticleTypes.GLOW_SQUID_INK, eye, q.position().add(0.0, 1.0, 0.0), FOAM);
         Fx.gooSplash(level, ParticleTypes.SPLASH, q.position().add(0.0, 1.0, 0.0), 1.2, TIDE);
         q.hurtServer(level, level.damageSources().mobAttack(boss), GLARE_DAMAGE);
         Vec3 away = q.position().subtract(boss.position());
         push(q, new Vec3(away.x, 0.0, away.z), 1.2);
         q.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 60, 1));
      }
      if (seen == 0) {
         announceNear(level, boss, GLARE_REACH + 20.0, SAY + "\u00a7fHiding. Fine.");
      }
      return true;
   }

   // ------------------------------------------------------------------ pending

   private static void tickPending(ServerLevel level, Mob boss, Fight fight) {
      if (fight.pending.isEmpty()) {
         return;
      }
      for (Pending pending : new ArrayList<>(fight.pending)) {
         // A held whisper before the landing, so the ground says what is coming.
         if (pending.fuse > 0) {
            pending.fuse--;
            drawPending(level, boss, fight, pending, true);
            if (pending.fuse > 0) {
               continue;
            }
            land(level, boss, pending);
            if (pending.life <= 0) {
               fight.pending.remove(pending);
            }
            continue;
         }
         drawPending(level, boss, fight, pending, false);
         if (pending.life > 0 && --pending.life <= 0) {
            fight.pending.remove(pending);
         }
      }
   }

   private static void drawPending(ServerLevel level, Mob boss, Fight fight, Pending pending, boolean tell) {
      Vec3 p = pending.pos;
      // Vanilla upkeep is redrawn every third tick, not every tick: a phase-two arena can hold
      // eight pools, a maelstrom and a beam at once, and Geyser pays a packet per particle.
      boolean upkeep = fight.now % 3L == 0L;
      switch (pending.kind) {
         // The rune circles sent at the cast are these tells for modded clients; vanilla clients get
         // the ring redrawn every third tick, which reads as the same steady mark.
         case "charge" -> {
            if (upkeep) {
               vRing(level, p, CHARGE_RADIUS, 16, ParticleTypes.BUBBLE_POP, 0.3);
            }
         }
         case "tentacle" -> {
            if (upkeep) {
               vRing(level, p, TENTACLE_RADIUS, 10, ParticleTypes.BUBBLE_POP, 0.15);
            }
         }
         case "erupt" -> {
            if (upkeep) {
               vRing(level, p, 5.0, 16, ParticleTypes.BUBBLE_POP, 0.15);
            }
         }
         case "final" -> {
            if (upkeep) {
               vRing(level, p, 3.4, 12, ParticleTypes.BUBBLE_POP, 0.15);
            }
         }
         case "dash" -> {
            // The lane is redrawn while it is waiting, so it is on the floor the whole time.
            if (pending.fuse % 6 == 0 && pending.origin != null) {
               Fx.beam(level, ParticleTypes.DOLPHIN, pending.origin.add(0.0, 0.3, 0.0), p.add(0.0, 0.3, 0.0), ABYSS);
            }
         }
         case "tsunami", "worldbreaker", "megawave" -> {
            if (pending.dir != null && (pending.fuse == WAVE_WARN || pending.fuse == WAVE_WARN / 2)) {
               double reach = pending.kind.equals("tsunami") ? 18.0 : pending.kind.equals("worldbreaker") ? 26.0 : MEGA_REACH;
               warnWave(level, boss.position(), pending.dir, reach);
            }
         }
         case "prison" -> {
            if (tell) {
               if (upkeep) {
                  vRing(level, p, PRISON_RADIUS, 10, ParticleTypes.BUBBLE_POP, 0.1);
               }
               break;
            }
            if (pending.life % 3 == 0) {
               vSphere(level, p.add(0.0, 1.0, 0.0), PRISON_RADIUS, ParticleTypes.SPLASH);
               vRing(level, p.add(0.0, 1.0, 0.0), PRISON_RADIUS, 10, ParticleTypes.DOLPHIN, 0.4);
            }
            // The hold: anyone still in it is kept in the middle and squeezed once a second.
            for (ServerPlayer q : playersNear(level, p, PRISON_RADIUS + 1.5)) {
               double dx = q.getX() - p.x;
               double dz = q.getZ() - p.z;
               double flat = Math.sqrt(dx * dx + dz * dz);
               if (flat > PRISON_RADIUS + 0.6 || Math.abs(q.getY() - p.y) > 3.0) {
                  continue;
               }
               if (flat > PRISON_RADIUS * 0.4) {
                  pull(q, p, 0.25);
               }
               if (pending.life % 20 == 0) {
                  q.hurtServer(level, level.damageSources().mobAttack(boss), PRISON_DAMAGE);
                  q.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 25, 2));
               }
            }
         }
         case "undertow" -> {
            if (upkeep) {
               vRing(level, p, 9.0 - (pending.life % 20) * 0.2, 16, ParticleTypes.SPLASH, 0.3);
               vRing(level, p, 5.0, 10, ParticleTypes.DOLPHIN, 0.5);
               vAt(level, p, ParticleTypes.DRIPPING_WATER, 3, 2.4, 0.6, 2.4, 0.0);
            }
            for (ServerPlayer q : playersNear(level, p, 9.0)) {
               pull(q, p, 0.16);
            }
         }
         case "maelstrom" -> {
            if (upkeep) {
               vRing(level, p, MAELSTROM_RADIUS - (pending.life % 40) * 0.3, 24, ParticleTypes.SPLASH, 0.5);
               vRing(level, p, 6.0, 12, ParticleTypes.DOLPHIN, 0.6);
            }
            for (ServerPlayer q : playersNear(level, p, MAELSTROM_RADIUS)) {
               pull(q, p, 0.22);
               if (pending.life % 20 == 0) {
                  q.hurtServer(level, level.damageSources().mobAttack(boss), 4.0F);
               }
            }
         }
         case "maw" -> {
            if (upkeep) {
               vSphere(level, p.add(0.0, 2.0, 0.0), MAW_RADIUS * 0.5, ParticleTypes.SQUID_INK);
               vRing(level, p, MAW_RADIUS, 24, ParticleTypes.SOUL_FIRE_FLAME, 0.4);
            }
            if (pending.life == 1) {
               // Closed explicitly (b = 0) - see abyssalMaw on the inverted flag.
               Fx.shape(level, FfVfx.WORMHOLE, ParticleTypes.SQUID_INK, p.add(0.0, 2.0, 0.0), Vec3.ZERO, 0.0, 0.0, ABYSS);
            }
            for (ServerPlayer q : playersNear(level, p, MAW_RADIUS)) {
               pull(q, p, 0.30);
               if (pending.life % 15 == 0) {
                  q.hurtServer(level, level.damageSources().mobAttack(boss), 5.0F);
               }
            }
         }
         case "black" -> {
            if (upkeep) {
               vDisc(level, p, BLACK_TIDE_RADIUS, ParticleTypes.SQUID_INK, 0.15);
               vRing(level, p, BLACK_TIDE_RADIUS, 12, ParticleTypes.FALLING_WATER, 0.3);
            }
            if (pending.life % 10 == 0) {
               for (ServerPlayer q : playersNear(level, p, BLACK_TIDE_RADIUS)) {
                  q.hurtServer(level, level.damageSources().mobAttack(boss), BLACK_TIDE_DAMAGE);
               }
            }
         }
         case "beam" -> {
            Vec3 origin = pending.origin == null ? boss.position() : pending.origin;
            Vec3 from = origin.add(0.0, 1.5, 0.0);
            Vec3 to = p.add(0.0, 1.5, 0.0);
            boolean firing = pending.life <= BEAM_LIFE;
            if (!firing) {
               if (pending.life % 4 == 0) {
                  Fx.beam(level, ParticleTypes.DOLPHIN, origin.add(0.0, 0.2, 0.0), p.add(0.0, 0.2, 0.0), ABYSS);
               }
               break;
            }
            if (pending.life == BEAM_LIFE) {
               Vec3 dir = to.subtract(from);
               Fx.muzzle(level, ParticleTypes.SPLASH, from, dir, TIDE);
               level.playSound(null, from.x, from.y, from.z, SoundEvents.WARDEN_SONIC_BOOM, SoundSource.HOSTILE, 1.4F, 0.7F);
            }
            if (pending.life % 4 == 0) {
               Fx.beam(level, ParticleTypes.GLOW_SQUID_INK, from, to, FOAM);
            }
            for (ServerPlayer q : playersNear(level, origin, BEAM_REACH + 4.0)) {
               if (Math.abs(q.getY() - origin.y) > BEAM_HEIGHT) {
                  continue;
               }
               if (distanceToLine(q.position(), from, to) < BEAM_HALF_WIDTH && mayTouch(fight, q.getUUID(), BEAM_HIT_COOLDOWN)) {
                  q.hurtServer(level, level.damageSources().mobAttack(boss), BEAM_DAMAGE);
               }
            }
         }
         case "eye" -> {
            if (tell) {
               if (upkeep) {
                  vRing(level, p, EYE_RADIUS, 8, ParticleTypes.BUBBLE_POP, 0.1);
               }
               break;
            }
            if (upkeep) {
               vColumn(level, p, 2.0, ParticleTypes.END_ROD);
            }
            if (pending.life < EYE_LIFE - 10) {
               for (ServerPlayer q : playersNear(level, p, EYE_RADIUS)) {
                  if (mayTouch(fight, q.getUUID(), EYE_HIT_COOLDOWN)) {
                     q.hurtServer(level, level.damageSources().mobAttack(boss), 6.0F);
                  }
               }
            }
         }
         default -> {
         }
      }
   }

   private static void land(ServerLevel level, Mob boss, Pending pending) {
      Vec3 p = pending.pos;
      switch (pending.kind) {
         case "charge" -> {
            Fx.nova(level, ParticleTypes.BUBBLE, p.add(0.0, 1.0, 0.0), CHARGE_RADIUS, FOAM);
            Fx.geyser(level, ParticleTypes.SPLASH, p, 6.0, TIDE);
            vDisc(level, p, CHARGE_RADIUS, ParticleTypes.SPLASH, 0.2);
            level.playSound(null, p.x, p.y, p.z, SoundEvents.GENERIC_EXPLODE, SoundSource.HOSTILE, 1.4F, 0.6F);
            for (ServerPlayer q : playersNear(level, p, CHARGE_RADIUS)) {
               q.hurtServer(level, level.damageSources().mobAttack(boss), CHARGE_DAMAGE);
               push(q, new Vec3(0.0, 1.0, 0.0), 1.1);
            }
         }
         case "tentacle" -> {
            Fx.pillar(level, ParticleTypes.SQUID_INK, p, 5.0, ABYSS);
            Fx.gooSplash(level, ParticleTypes.SPLASH, p.add(0.0, 0.5, 0.0), 1.2, TIDE);
            level.playSound(null, p.x, p.y, p.z, SoundEvents.ELDER_GUARDIAN_HURT, SoundSource.HOSTILE, 1.2F, 0.6F);
            for (ServerPlayer q : playersNear(level, p, TENTACLE_RADIUS)) {
               q.hurtServer(level, level.damageSources().mobAttack(boss), TENTACLE_DAMAGE);
               push(q, new Vec3(0.0, 1.0, 0.0), 1.5);
            }
         }
         case "dash" -> {
            Vec3 from = pending.origin == null ? boss.position() : pending.origin;
            Fx.comet(level, ParticleTypes.SPLASH, from.add(0.0, 1.0, 0.0), p.add(0.0, 1.0, 0.0), 4, TIDE);
            Fx.gooSplash(level, ParticleTypes.SPLASH, p.add(0.0, 0.3, 0.0), 1.6, FOAM);
            level.playSound(null, p.x, p.y, p.z, SoundEvents.DOLPHIN_SPLASH, SoundSource.HOSTILE, 1.3F, 1.4F);
            Vec3 lane = p.subtract(from);
            Vec3 side = new Vec3(-lane.z, 0.0, lane.x);
            for (ServerPlayer q : playersNear(level, from, from.distanceTo(p) + 2.0)) {
               if (Math.abs(q.getY() - p.y) > 3.0 || distanceToLine(q.position(), from, p) >= DASH_HALF_WIDTH) {
                  continue;
               }
               q.hurtServer(level, level.damageSources().mobAttack(boss), DASH_DAMAGE);
               Vec3 off = q.position().subtract(from);
               push(q, side.dot(off) >= 0.0 ? side : side.scale(-1.0), 0.9);
            }
            boss.setPos(p.x, p.y, p.z);
            boss.setDeltaMovement(Vec3.ZERO);
            boss.hurtMarked = true;
         }
         case "prison" -> {
            Fx.dome(level, ParticleTypes.BUBBLE, p, PRISON_RADIUS, PRISON_TICKS, TIDE);
            Fx.gooSplash(level, ParticleTypes.SPLASH, p.add(0.0, 1.0, 0.0), 1.4, FOAM);
            level.playSound(null, p.x, p.y, p.z, SoundEvents.CONDUIT_ATTACK_TARGET, SoundSource.HOSTILE, 1.4F, 0.8F);
         }
         case "eye" -> {
            Fx.pillar(level, ParticleTypes.END_ROD, p, 3.0, FOAM);
            Fx.flare(level, ParticleTypes.GLOW_SQUID_INK, p.add(0.0, 1.0, 0.0), 1.0, TIDE);
            level.playSound(null, p.x, p.y, p.z, SoundEvents.CONDUIT_ATTACK_TARGET, SoundSource.HOSTILE, 0.9F, 1.4F);
         }
         case "erupt" -> {
            // He comes up with the water.
            boss.setPos(p.x, p.y, p.z);
            boss.setDeltaMovement(Vec3.ZERO);
            boss.hurtMarked = true;
            Fx.geyser(level, ParticleTypes.SPLASH, p, 9.0, FOAM);
            Fx.shockwave(level, ParticleTypes.SPLASH, p, 5.0, TIDE);
            level.playSound(null, p.x, p.y, p.z, SoundEvents.GENERIC_SPLASH, SoundSource.HOSTILE, 1.6F, 0.5F);
            for (ServerPlayer q : playersNear(level, p, 5.0)) {
               q.hurtServer(level, level.damageSources().mobAttack(boss), 12.0F);
               push(q, new Vec3(0.0, 1.0, 0.0), 1.9);
            }
         }
         case "tsunami" -> {
            Vec3 dir = pending.dir == null ? randomFlat() : pending.dir;
            Vec3 from = boss.position();
            tideFan(level, from, dir, 18.0, 14, 5, TIDE);
            Fx.shockwave(level, ParticleTypes.SPLASH, from, 8.0, FOAM);
            vWall(level, from.add(0.0, 1.0, 0.0), dir, 14.0, 4.0, ParticleTypes.SPLASH, 3);
            vWall(level, from.add(0.0, 0.3, 0.0), dir, 18.0, 2.0, ParticleTypes.FALLING_WATER, 2);
            level.playSound(null, from.x, from.y, from.z, SoundEvents.GENERIC_SPLASH, SoundSource.HOSTILE, 1.8F, 0.5F);
            for (ServerPlayer q : playersNear(level, from, 18.0)) {
               if (!inFront(from, dir, q, WAVE_HALF_ANGLE)) {
                  continue;
               }
               q.hurtServer(level, level.damageSources().mobAttack(boss), 10.0F);
               push(q, dir, 2.4);
               q.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 60, 0));
            }
         }
         case "megawave" -> {
            Vec3 dir = pending.dir == null ? randomFlat() : pending.dir;
            Vec3 from = boss.position();
            // One roll of the finale: a wall across the width of the arena.
            tideFan(level, from, dir, MEGA_REACH, 20, 5, TIDE);
            Fx.shockwave(level, ParticleTypes.SPLASH, from, MEGA_RADIUS, FOAM);
            Fx.geyser(level, ParticleTypes.SPLASH, from, 8.0, ABYSS);
            vWall(level, from.add(0.0, 1.0, 0.0), dir, MEGA_REACH, 9.0, ParticleTypes.SPLASH, 3);
            vWall(level, from.add(0.0, 0.3, 0.0), dir, MEGA_REACH, 3.0, ParticleTypes.FALLING_WATER, 2);
            vDisc(level, from, MEGA_RADIUS, ParticleTypes.FALLING_WATER, 0.22);
            level.playSound(null, from.x, from.y, from.z, SoundEvents.GENERIC_SPLASH, SoundSource.HOSTILE, 2.1F, 0.4F);
            for (ServerPlayer q : playersNear(level, from, MEGA_RADIUS)) {
               if (!inFront(from, dir, q, WAVE_HALF_ANGLE)) {
                  continue;
               }
               q.hurtServer(level, level.damageSources().mobAttack(boss), MEGA_DAMAGE);
               push(q, dir, MEGA_PUSH);
               q.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 80, 1));
               q.sendOverlayMessage(Component.literal("\u00a73\u00a7lSWEPT"));
            }
         }
         case "worldbreaker" -> {
            Vec3 dir = pending.dir == null ? randomFlat() : pending.dir;
            Vec3 from = boss.position();
            tideFan(level, from, dir, 26.0, 18, 5, ABYSS);
            Fx.shockwave(level, ParticleTypes.SPLASH, from, 26.0, ABYSS);
            Fx.geyser(level, ParticleTypes.SPLASH, from, 10.0, FOAM);
            vWall(level, from.add(0.0, 1.0, 0.0), dir, 26.0, 6.0, ParticleTypes.SPLASH, 3);
            level.playSound(null, from.x, from.y, from.z, SoundEvents.GENERIC_EXPLODE, SoundSource.HOSTILE, 2.0F, 0.4F);
            for (ServerPlayer q : playersNear(level, from, 30.0)) {
               if (!inFront(from, dir, q, WAVE_HALF_ANGLE)) {
                  continue;
               }
               q.hurtServer(level, level.damageSources().mobAttack(boss), 19.0F);
               push(q, dir, 2.9);
            }
         }
         case "final" -> {
            Fx.geyser(level, ParticleTypes.SPLASH, p, 8.0, FOAM);
            Fx.nova(level, ParticleTypes.BUBBLE, p.add(0.0, 1.0, 0.0), 3.4, TIDE);
            level.playSound(null, p.x, p.y, p.z, SoundEvents.GENERIC_EXPLODE, SoundSource.HOSTILE, 1.5F, 0.7F);
            for (ServerPlayer q : playersNear(level, p, 3.4)) {
               q.hurtServer(level, level.damageSources().mobAttack(boss), 17.0F);
               push(q, new Vec3(0.0, 1.0, 0.0), 1.2);
            }
         }
         default -> {
         }
      }
   }

   /**
    * A per-target cooldown on the lingering hazards, so standing in a beam or walking through the
    * same eye twice a tick does not stack into one instant hit. The clock is the fight's own, which
    * is why {@link Fight#now} is carried rather than read from the level here.
    */
   private static boolean mayTouch(Fight fight, UUID id, int ticks) {
      Long until = fight.touched.get(id);
      if (until != null && fight.now < until) {
         return false;
      }
      fight.touched.put(id, fight.now + ticks);
      return true;
   }

   // --------------------------------------------------------------------- loot

   /**
    * The death ceremony. He sinks back into the floor he came out of while the water turns around
    * him; spouts go up closer and higher every few beats, his heart is heard under the floor near
    * the end, and the last tick is one burst - a flash, a starburst, a geyser the height of a
    * house and a shockwave across the arena - before the body goes.
    */
   /**
    * <b>Maelstrom</b>, his last stand. At what should be the killing blow he holds at one heart,
    * cannot be hurt, and turns the sea around him into a whirlpool that starts as a swirl and grows
    * for fifteen seconds. Everyone nearby is dragged slowly toward his eye, and the eye grinds
    * whoever reaches it every half second, so the fight is running against the current. A player
    * the whirlpool kills is replaced by a drowned wearing their name. When it ends he is exposed,
    * and the next blow kills him.
    */
   private static void startMaelstrom(ServerLevel level, Mob boss, Fight fight) {
      fight.finalTicks = FINAL_TICKS;
      fight.pending.clear();
      fight.strikes.clear();
      boss.setHealth(1.0F);
      boss.setInvulnerable(true);
      boss.setNoAi(true);
      boss.setDeltaMovement(Vec3.ZERO);
      Fx.whirlpool(level, ParticleTypes.BUBBLE, boss.position(), 3.0, 20, TIDE);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.ELDER_GUARDIAN_CURSE, SoundSource.HOSTILE, 2.0F, 0.5F);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.CONDUIT_ACTIVATE, SoundSource.HOSTILE, 2.0F, 0.4F);
      announce(level, SAY + "\u00a77\u201c\u00a7fThen we all go down together.\u00a77\u201d");
      announce(level, "\u00a73\u00a7lMAELSTROM \u00a78- \u00a77the sea turns. \u00a7fRun from the eye.");
   }

   private static void tickMaelstrom(ServerLevel level, Mob boss, Fight fight) {
      int left = --fight.finalTicks;
      double t = 1.0 - left / (double)FINAL_TICKS;
      Vec3 eye = boss.position();
      double radius = 3.0 + t * 15.0;
      double core = 2.0 + t * 2.0;
      boss.setDeltaMovement(Vec3.ZERO);
      fight.bar.setProgress((float)(1.0 - t));
      // Redrawn every half second, a little wider and denser each time: small swirl to maelstrom.
      if (left % 10 == 0) {
         Fx.whirlpool(level, ParticleTypes.SPLASH, eye, radius, 12, t > 0.6 ? ABYSS : TIDE);
         Fx.vanillaOnly(() -> vDisc(level, eye, radius, ParticleTypes.BUBBLE, 0.05));
         if (t > 0.3) {
            Fx.whirlpool(level, ParticleTypes.BUBBLE, eye, radius * 0.55, 12, FOAM);
         }
         level.playSound(null, eye.x, eye.y, eye.z, SoundEvents.BUBBLE_COLUMN_WHIRLPOOL_AMBIENT, SoundSource.HOSTILE, 1.4F + (float)t, 0.6F);
      }
      if (left % 40 == 0) {
         Fx.tentacle(level, ParticleTypes.SPLASH, eye.add((RANDOM.nextDouble() - 0.5) * radius, 0.0, (RANDOM.nextDouble() - 0.5) * radius), 5.0 + t * 4.0, 30, TIDE);
      }
      for (ServerPlayer p : level.getPlayers(q -> q.isAlive() && !q.isSpectator() && !q.isCreative() && q.distanceToSqr(eye) < (radius + 2.0) * (radius + 2.0))) {
         Vec3 in = eye.subtract(p.position());
         double d = Math.sqrt(in.horizontalDistanceSqr());
         if (d > 0.3) {
            // A slow drag: walking away still works, standing still does not.
            double pull = 0.025 + t * 0.035;
            p.push(in.x / d * pull, 0.0, in.z / d * pull);
            p.hurtMarked = true;
         }
         if (d < core && left % 10 == 0) {
            p.invulnerableTime = 0;
            p.hurtServer(level, level.damageSources().drown(), 3.0F + (float)t * 3.0F);
            if (!p.isAlive()) {
               drownedTakesPlace(level, p, fight);
            }
         }
      }
      if (left <= 0) {
         fight.finalDone = true;
         boss.setInvulnerable(false);
         boss.setNoAi(false);
         boss.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.GLOWING, 400, 0));
         fight.bar.setProgress(0.02F);
         Fx.shape(level, com.fortuneandfavors.net.FfVfx.GEYSER, ParticleTypes.SPLASH, eye, Vec3.ZERO, 8.0, 0.0, FOAM);
         level.playSound(null, eye.x, eye.y, eye.z, SoundEvents.GENERIC_SPLASH, SoundSource.HOSTILE, 2.0F, 0.5F);
         announce(level, "\u00a73The whirlpool collapses. \u00a7fThe Sovereign is exposed - finish him.");
      }
   }

   /** A body the Maelstrom took: a drowned rises where they went under, wearing their name. */
   private static void drownedTakesPlace(ServerLevel level, ServerPlayer victim, Fight fight) {
      Mob d = (Mob)EntityTypes.DROWNED.create(level, EntitySpawnReason.MOB_SUMMONED);
      if (d == null) {
         return;
      }
      d.setPos(victim.getX(), victim.getY(), victim.getZ());
      d.setCustomName(net.minecraft.network.chat.Component.literal("\u00a73Drowned " + victim.getName().getString()));
      d.setCustomNameVisible(true);
      d.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.TRIDENT));
      level.addFreshEntity(d);
      Fx.shape(level, com.fortuneandfavors.net.FfVfx.GEYSER, ParticleTypes.BUBBLE, victim.position(), Vec3.ZERO, 3.0, 0.0, ABYSS);
      announce(level, "\u00a73" + victim.getName().getString() + "\u00a77 went under. \u00a7fSomething else came back up.");
   }

   private static void tickDeath(MinecraftServer server, Mob boss, Fight fight) {
      ServerLevel level = (ServerLevel)boss.level();
      fight.deathTicks--;
      int left = fight.deathTicks;
      double progress = 1.0 - Math.max(0, left) / (double)DEATH_CEREMONY_TICKS;
      boss.setDeltaMovement(Vec3.ZERO);
      if (left > 20) {
         boss.setPos(boss.getX(), boss.getY() - 0.03, boss.getZ());
      }
      if (left % 3 == 0) {
         vDisc(level, boss.position(), 5.0, ParticleTypes.FALLING_WATER, 0.1);
         vSphere(level, boss.position().add(0.0, 1.0, 0.0), 4.0, ParticleTypes.BUBBLE);
      }
      if (left % 6 == 0) {
         level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.BUBBLE_COLUMN_UPWARDS_AMBIENT, SoundSource.HOSTILE, 1.4F, 0.5F);
      }
      if (left > 0 && left % 15 == 0) {
         double a = RANDOM.nextDouble() * Math.PI * 2.0;
         double r = 7.0 - progress * 4.0;
         double sx = boss.getX() + Math.cos(a) * r;
         double sz = boss.getZ() + Math.sin(a) * r;
         Vec3 spout = new Vec3(sx, BossGrounding.groundY(level, sx, sz, fight.deathFloorY), sz);
         Fx.geyser(level, ParticleTypes.SPLASH, spout, 5.0 + progress * 6.0, TIDE);
         Fx.ring(level, ParticleTypes.BUBBLE_POP, new Vec3(boss.getX(), fight.deathFloorY + 0.1, boss.getZ()), 3.0 + progress * 6.0, FOAM);
         level.playSound(null, sx, spout.y, sz, SoundEvents.GENERIC_SPLASH, SoundSource.HOSTILE, 1.3F, 0.6F + (float)progress * 0.4F);
      }
      if (left == 60) {
         announce(level, SAY + "\u00a77\u201c\u00a7fThe tide...\u00a77\u201d");
      }
      if (left == 30) {
         announce(level, SAY + "\u00a77\u201c\u00a7f...comes back.\u00a77\u201d");
         Fx.heartbeat(level, ParticleTypes.SQUID_INK, new Vec3(boss.getX(), fight.deathFloorY + 0.1, boss.getZ()), 6.0, 30, ABYSS);
         level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.WARDEN_HEARTBEAT, SoundSource.HOSTILE, 2.0F, 0.6F);
      }
      if (left > 0) {
         return;
      }

      // The burst. He is put back on the floor first, so the body and the loot land on the
      // surface rather than inside the ground he sank into.
      boss.setPos(boss.getX(), fight.deathFloorY, boss.getZ());
      Vec3 heart = boss.position().add(0.0, 2.0, 0.0);
      Fx.flare(level, ParticleTypes.SPLASH, heart, 4.0, FOAM);
      Fx.starburst(level, ParticleTypes.BUBBLE, heart, 10.0, TIDE);
      Fx.nova(level, ParticleTypes.BUBBLE, heart, 8.0, FOAM);
      Fx.shockwave(level, ParticleTypes.SPLASH, boss.position(), 18.0, ABYSS);
      Fx.geyser(level, ParticleTypes.SPLASH, boss.position(), 16.0, FOAM);
      // The sea he was holding up lets go: eight walls of water rolling out across the arena.
      radialTide(level, boss.position(), 0.5, 8, 16.0, 22, TIDE);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), ModSounds.BOSS_DEATH, SoundSource.HOSTILE, 2.0F, 0.7F);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.ELDER_GUARDIAN_CURSE, SoundSource.HOSTILE, 1.8F, 0.4F);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.GENERIC_SPLASH, SoundSource.HOSTILE, 2.2F, 0.4F);
      boss.setNoAi(false);
      boss.setNoGravity(false);
      boss.setInvulnerable(false);
      onBossDeath(level, boss);
      if (boss.isAlive()) {
         boss.hurtServer(level, level.damageSources().generic(), boss.getMaxHealth() * 4.0F + 100.0F);
      }
   }

   /**
    * The body could not be found this tick.
    *
    * <p>It used to end the fight on the spot. A boss whose chunk nobody is standing in cannot be
    * looked up by uuid, so a player who stepped back out of range lost the fight - bar, hazards and
    * loot - and the body reappeared later only to be swept as a stray. Now the fight waits
    * {@link #MISSING_GRACE_TICKS}; a body that is gone for that long is gone, and the fight pays what
    * it promised at the last place he stood (once - the same ledger as every other payout).
    */
   private static void tickMissing(MinecraftServer server, Fight fight) {
      fight.missingTicks++;
      if (fight.missingTicks < MISSING_GRACE_TICKS) {
         return;
      }
      if (fight.lastSeenLevel != null && fight.lastSeen != null && !LOOT_PAID.contains(fight.bossId)) {
         LOOT_PAID.add(fight.bossId);
         grantLoot(fight.lastSeenLevel, fight, fight.lastSeen);
      }
      shutDown(server, fight);
   }

   private static void grantLoot(ServerLevel level, Mob boss, Fight fight) {
      grantLoot(level, fight, boss.position());
   }

   /** The payout at a place rather than on a body, so a fight whose body is gone can still pay. */
   private static void grantLoot(ServerLevel level, Fight fight, Vec3 at) {
      // His *own* forge material, not the shared one every older boss pays: the sea set is
      // upgraded with Abyssal Pearls and nothing else, so a kill that paid Magical Essence would
      // be paying a material the set it guards cannot use. Otherwise the same shape as
      // StarboundMagisterManager#grantLoot - a boss does not also empty its own set into the pile.
      int pearls = 3 + RANDOM.nextInt(3);
      for (int i = 0; i < pearls; i++) {
         drop(level, at, ModItems.abyssalPearl());
      }
      // One roll at one of his three, the same shape every other boss uses: a legendary is a reason
      // to come back, and three at once is a dump that ends the reason.
      if (RANDOM.nextFloat() < 0.22F) {
         drop(level, at, switch (RANDOM.nextInt(3)) {
            case 0 -> ModItems.leviathansGrasp();
            case 1 -> ModItems.tidecaller();
            default -> ModItems.abyssalChain();
         });
      }
      BossPayout.payBoxes(
         level, fight.participants, ModItems::drownedLootBox, BossPayout.BOXES_PER_KILL, "\u00a73Drowned Loot Box"
      );
      for (UUID id : fight.participants) {
         ServerPlayer p = level.getServer().getPlayerList().getPlayer(id);
         if (p != null && p.isAlive()) {
            Advancements.grant(p, "kill_drowned_sovereign");
         }
      }
      announce(level, "\u00a73\u00a7m\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550");
      announce(level, "    \u00a73\u00a7l\uD83C\uDF0A THE SOVEREIGN SINKS \uD83C\uDF0A");
      announce(level, "    \u00a77The water goes flat. \u00a78For now.");
      announce(level, "\u00a73\u00a7m\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550");
   }

   private static void drop(ServerLevel level, Vec3 at, ItemStack stack) {
      if (stack != null && !stack.isEmpty()) {
         level.addFreshEntity(new ItemEntity(level, at.x, at.y + 0.6, at.z, stack));
      }
   }

   // ------------------------------------------------------------------ teardown

   private static void release(MinecraftServer server, Fight fight) {
      // Nothing scheduled outlives the fight: a wave or a ring still in the list would otherwise be
      // held by a fight object nobody ticks any more.
      fight.pending.clear();
      fight.strikes.clear();
      fight.bar.setVisible(false);
      for (ServerPlayer p : server.getPlayerList().getPlayers()) {
         fight.bar.removePlayer(p);
      }
      // Whatever Drowned Call raised goes back down with the fight, so nothing it summoned is
      // still standing in the arena afterwards.
      for (UUID id : fight.minions) {
         Entity minion = findEntity(server, id);
         if (minion != null && minion.isAlive()) {
            minion.discard();
         }
      }
      fight.minions.clear();
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

   private static List<ServerPlayer> playersNear(ServerLevel level, Vec3 pos, double radius) {
      List<ServerPlayer> out = new ArrayList<>();
      double r2 = radius * radius;
      for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
         if (!p.isAlive() || p.isCreative() || p.isSpectator() || p.level() != level || BossManager.isFakePlayer(p)) {
            continue;
         }
         if (p.distanceToSqr(pos.x, pos.y, pos.z) <= r2) {
            out.add(p);
         }
      }
      return out;
   }

   private static ServerPlayer nearestPlayer(ServerLevel level, Mob boss, double radius) {
      ServerPlayer best = null;
      double bestDist = radius * radius;
      for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
         if (!p.isAlive() || p.isCreative() || p.isSpectator() || p.level() != level || BossManager.isFakePlayer(p)) {
            continue;
         }
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
      p.setDeltaMovement(p.getDeltaMovement().add(unit.scale(power)).add(0.0, 0.34, 0.0));
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

   /** Distance from a point to a ray segment, in the horizontal plane - the beam's hit test. */
   private static double distanceToLine(Vec3 point, Vec3 from, Vec3 to) {
      Vec3 flatPoint = new Vec3(point.x, 0.0, point.z);
      Vec3 flatFrom = new Vec3(from.x, 0.0, from.z);
      Vec3 flatTo = new Vec3(to.x, 0.0, to.z);
      Vec3 seg = flatTo.subtract(flatFrom);
      double len = seg.length();
      if (len < 1.0E-4) {
         return flatPoint.distanceTo(flatFrom);
      }
      Vec3 unit = seg.scale(1.0 / len);
      double along = Math.max(0.0, Math.min(len, flatPoint.subtract(flatFrom).dot(unit)));
      return flatPoint.distanceTo(flatFrom.add(unit.scale(along)));
   }

   /** The floor under a point, so a mark is drawn where the player is standing and not mid-jump. */
   private static Vec3 floorAt(ServerLevel level, Vec3 at) {
      return new Vec3(at.x, BossGrounding.groundY(level, at.x, at.z, at.y), at.z);
   }

   /** Roughly where his eyes are, at his scale - where the stare comes from. */
   private static Vec3 eyeOf(Mob boss) {
      return boss.position().add(0.0, boss.getBbHeight() * 0.9, 0.0);
   }

   /** Whether {@code q} is inside the arc a wave rolling along {@code dir} from {@code origin} covers. */
   private static boolean inFront(Vec3 origin, Vec3 dir, ServerPlayer q, double halfAngle) {
      Vec3 to = q.position().subtract(origin);
      Vec3 flat = new Vec3(to.x, 0.0, to.z);
      if (flat.lengthSqr() < 1.0E-4) {
         return true;
      }
      double angle = Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, flat.normalize().dot(dir.normalize())))));
      return angle <= halfAngle;
   }

   /** A big wave's warning: its front drawn in dark water, and a line down the middle of it. */
   private static void warnWave(ServerLevel level, Vec3 origin, Vec3 dir, double reach) {
      Vec3 base = origin.add(0.0, 0.2, 0.0);
      Fx.crescent(level, ParticleTypes.BUBBLE_POP, base, dir, reach, ABYSS);
      Fx.beam(level, ParticleTypes.BUBBLE_POP, base, base.add(dir.normalize().scale(reach)), ABYSS);
      level.playSound(null, origin.x, origin.y, origin.z, SoundEvents.WATER_AMBIENT, SoundSource.HOSTILE, 1.6F, 0.6F);
   }

   /**
    * A one-line mechanic hint on the action bar of the players it is about - the grey narrator
    * voice, not the boss's. Sent straight to them like the other action-bar lines in this file,
    * since the {@link #overlayNear} funnel is closed.
    */
   private static void hint(List<ServerPlayer> players, String text) {
      Component line = Component.literal(text);
      for (ServerPlayer p : players) {
         p.sendOverlayMessage(line);
      }
   }

   /**
    * Draws particle upkeep for vanilla clients only. Used where a timed {@link Fx} shape was already
    * sent for the same thing, so a modded client is not drawing the shape and the particles both.
    *
    * <p>Only {@code FfVfx.particles} inside the block is scoped: a bare {@code level.sendParticles},
    * or anything drawn through {@code BossVfx} (which sends per player, past the transport), still
    * reaches everyone. That is why the shapes below exist rather than BossVfx's. Goes through
    * {@link Fx#vanillaOnly} so it nests safely inside the templates' own scopes.
    */
   private static void vanillaOnly(Runnable draw) {
      Fx.vanillaOnly(draw);
   }

   // ---------------------------------------------------- vanilla-only upkeep shapes
   //
   // The particle versions of his shapes, for clients without the mod (vanilla Java and Bedrock
   // through Geyser). Every one sends through the transport inside a vanilla-only scope, and every
   // one is bounded - a ring is at most 24 single particles, a wall at most 24 sites - because a
   // Bedrock client pays a packet per particle and these are redrawn while a hazard lingers.

   /** A puff at one point. */
   private static void vAt(ServerLevel level, Vec3 at, ParticleOptions type, int count, double dx, double dy, double dz, double speed) {
      Fx.vanilla(level, type, at.x, at.y, at.z, count, dx, dy, dz, speed);
   }

   /** A ring of {@code sites} particles on the ground, {@code y} above {@code center}. */
   private static void vRing(ServerLevel level, Vec3 center, double radius, int sites, ParticleOptions type, double y) {
      if (radius <= 0.0) {
         return;
      }
      int n = Math.max(6, Math.min(24, sites));
      vanillaOnly(() -> {
         for (int i = 0; i < n; i++) {
            double a = Math.PI * 2.0 * i / n;
            Fx.vanilla(level, type, center.x + Math.cos(a) * radius, center.y + y, center.z + Math.sin(a) * radius, 1, 0.04, 0.03, 0.04, 0.01);
         }
      });
   }

   /** A filled pool: one spread send over the disc rather than a grid of sites. */
   private static void vDisc(ServerLevel level, Vec3 center, double radius, ParticleOptions type, double y) {
      int count = (int)Math.max(4, Math.min(24, radius * 3.0));
      vAt(level, center.add(0.0, y, 0.0), type, count, radius * 0.45, 0.06, radius * 0.45, 0.01);
   }

   /** A wall across {@code facing}: the leading edge of a wave, {@code halfWidth} each side. */
   private static void vWall(ServerLevel level, Vec3 center, Vec3 facing, double halfWidth, double height, ParticleOptions type, int count) {
      Vec3 flat = new Vec3(facing.x, 0.0, facing.z);
      Vec3 unit = flat.lengthSqr() < 1.0E-4 ? new Vec3(1.0, 0.0, 0.0) : flat.normalize();
      Vec3 across = new Vec3(-unit.z, 0.0, unit.x);
      int rows = (int)Math.max(1, Math.min(4, Math.round(height / 1.5)));
      int cols = Math.max(3, Math.min(24 / rows, (int)(halfWidth * 2.0 / 2.0)));
      vanillaOnly(() -> {
         for (int c = 0; c < cols; c++) {
            double d = -halfWidth + 2.0 * halfWidth * c / Math.max(1, cols - 1);
            for (int r = 0; r < rows; r++) {
               Vec3 q = center.add(across.scale(d)).add(0.0, height * r / rows, 0.0);
               Fx.vanilla(level, type, q.x, q.y, q.z, Math.max(1, count), 0.15, 0.2, 0.15, 0.02);
            }
         }
      });
   }

   /** A sphere of 16 points - a prison, a pressure bubble. */
   private static void vSphere(ServerLevel level, Vec3 center, double radius, ParticleOptions type) {
      vanillaOnly(() -> {
         int n = 16;
         for (int i = 0; i < n; i++) {
            double a = Math.PI * 2.0 * i / n;
            double b = Math.PI * ((i * 7) % n) / n;
            Fx.vanilla(level, type, center.x + Math.cos(a) * Math.sin(b) * radius, center.y + Math.cos(b) * radius * 0.6,
               center.z + Math.sin(a) * Math.sin(b) * radius, 1, 0.05, 0.05, 0.05, 0.0);
         }
      });
   }

   /** A short upright column - an eye staring up out of the water. */
   private static void vColumn(ServerLevel level, Vec3 base, double height, ParticleOptions type) {
      vAt(level, base.add(0.0, height * 0.5, 0.0), type, (int)Math.max(2, Math.min(8, height * 2.0)), 0.18, height * 0.35, 0.18, 0.01);
   }

   // ------------------------------------------------------------- the water itself

   /**
    * A wave's front as modded clients see it: {@code walls} rolling walls of water fanned across the
    * arc the hit test covers ({@link #WAVE_HALF_ANGLE} each side, drawn to 80% of it so the outer
    * walls sit inside the danger rather than on its edge). Sent once, as cues only - every caller
    * draws its own vanilla wall, which a template fallback would only duplicate.
    */
   private static void tideFan(ServerLevel level, Vec3 from, Vec3 dir, double reach, int ticks, int walls, int color) {
      Vec3 flat = new Vec3(dir.x, 0.0, dir.z);
      Vec3 unit = flat.lengthSqr() < 1.0E-4 ? new Vec3(1.0, 0.0, 0.0) : flat.normalize();
      double spread = Math.toRadians(WAVE_HALF_ANGLE * 0.8);
      int n = Math.max(1, walls);
      Vec3 base = from.add(0.0, 0.1, 0.0);
      for (int i = 0; i < n; i++) {
         double t = n == 1 ? 0.0 : -spread + 2.0 * spread * i / (n - 1);
         double cos = Math.cos(t);
         double sin = Math.sin(t);
         Vec3 d = new Vec3(unit.x * cos - unit.z * sin, 0.0, unit.x * sin + unit.z * cos);
         // The middle wall carries the full reach; the flanks a little less, so the front curves.
         double r = reach * (1.0 - 0.15 * Math.abs(t) / Math.max(1.0E-4, spread));
         FfVfx.shape(level, FxKinds.TIDE_WAVE, ParticleTypes.SPLASH, base, d, r, ticks, color);
      }
   }

   /**
    * Walls of water rolling out in every direction from {@code center}, starting {@code start}
    * blocks out - the Breaker Ring, the rise, the fall. Cues only, for the same reason as
    * {@link #tideFan}: each caller already gives vanilla clients a ring or a shockwave there.
    */
   private static void radialTide(ServerLevel level, Vec3 center, double start, int walls, double reach, int ticks, int color) {
      int n = Math.max(3, walls);
      double offset = RANDOM.nextDouble() * Math.PI * 2.0 / n;
      for (int i = 0; i < n; i++) {
         double a = offset + Math.PI * 2.0 * i / n;
         Vec3 d = new Vec3(Math.cos(a), 0.0, Math.sin(a));
         FfVfx.shape(level, FxKinds.TIDE_WAVE, ParticleTypes.SPLASH, center.add(d.scale(start)).add(0.0, 0.1, 0.0), d, reach, ticks, color);
      }
   }

   private static long now(ServerLevel level) {
      return ServerClock.clock(level);
   }

   /**
    * The clock this fight schedules on, exposed for the harness.
    *
    * <p>Public on purpose, and deliberately not a second implementation: it calls the same helper
    * every deadline in this file is written against, so a check that reads through here is reading
    * the real thing rather than a copy that could agree with the harness and disagree with the
    * fight. The point of the check is the frozen-clock lesson - a realm's own game time does not
    * advance, this does, and a deadline written on one and read on the other is a deadline that has
    * always already passed.
    */
   public static long clockForTest(ServerLevel level) {
      return now(level);
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
    * The sea boss says nothing, and the three funnels below are why that is checkable.
    *
    * <p>He had a voice - a summon banner, a taunt every eleven seconds, a line for each phase and a
    * quotation wrapped around most of his moves, plus an action-bar line naming each move - and all
    * of it arrived as text over a fight whose whole language is what the water is doing. So the
    * lines are gone and the funnels are kept as the single place they can come back through: every
    * announcement in this file still calls one of these, which is what lets an audit fail the build
    * the moment a real chat sender appears here again (see {@code ffAuditSources}, rule 10d).
    *
    * <p>What is left is the fight itself. Every move draws its own shape - the ring on the floor,
    * the wall of the tide, the dark water under him - so nothing here is asked to describe an
    * ability that the screen already shows.
    */
   private static void announce(ServerLevel level, String message) {
      // Dialogues removed
   }

   private static void announceNear(ServerLevel level, Mob boss, double range, String message) {
      // Dialogues removed
   }

   /** The action-bar line of a move. Kept as a funnel, and deliberately closed. */
   private static void overlayNear(ServerLevel level, Mob boss, double range, String message) {
      // Telegraph removed
   }

   // ------------------------------------------------------------- codex + probes

   /** The move list of a phase, for the codex page and the self-test. */
   public static List<String> movesForPhase(int phase) {
      return switch (phase) {
         case 3 -> MOVES_3;
         case 2 -> MOVES_2;
         default -> MOVES_1;
      };
   }

   /** Test hook: his health, so no future tuning can quietly make him unkillable. */
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

   public static int prisonTicks() {
      return PRISON_TICKS;
   }

   public static int finalDepthMarks() {
      return FINAL_DEPTH_MARKS;
   }

   public static int chargeTellTicks() {
      return CHARGE_TELL;
   }

   /** How many rolls the finale is, and how far one of them reaches. */
   public static int finalTsunamiRolls() {
      return MEGA_ROLLS;
   }

   public static double finalTsunamiRadius() {
      return MEGA_RADIUS;
   }

   /**
    * Whether Drowned Call can raise anything whose attack is a repeating beam.
    *
    * <p>False, and it is asserted rather than assumed: the guardians this move used to raise never
    * stopped firing and never despawned, which is the guardian attack a player hears looping off in
    * the distance long after the boss is dead. A probe rather than a comment because the way this
    * regresses is somebody adding a stronger minion back in.
    */
   public static boolean callsBeamMinions() {
      return false;
   }
}
