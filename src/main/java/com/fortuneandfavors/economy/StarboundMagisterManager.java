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
 * <b>The Starbound Magister</b> - a wandering caster with no arena at all.
 *
 * <h2>The design</h2>
 * Most bosses in this mod pin you into a ring and make you solve the ring. She is
 * the opposite: she keeps her distance, blinks away when you close, and the fight
 * happens wherever it happens - a forest, a village, the Nether. There is no
 * summoning circle to stand in and no border to stay inside.
 *
 * <h2>Astral Energy is the whole fight</h2>
 * From 60% health she starts <em>charging</em>: every few seconds she gains a
 * point of Astral Energy, and each point makes her spells wider, faster and
 * harder-hitting. Charge six of them and she spends all six at once on
 * <b>Judgement</b> - the sky opens along a line she chooses, and a beam sweeps
 * across it.
 *
 * <p>Damage is the interrupt. Every {@link #INTERRUPT_DAMAGE} health she loses while
 * holding a charge breaks one of them, so the answer to the meter is the obvious one:
 * stay on her. The chat used to tell players to "interrupt her" with nothing behind it.
 *
 * <p>She used to detonate the six charges and then stand there defenceless for
 * five seconds, which made the sixth charge a reward for the player instead of a
 * threat: the best case was to let her fill up. Judgement inverts that. There is no
 * free-hit window any more - the cost of the sixth charge is that she loses every
 * charge she had and stands still to aim, so the answer is the same as it always
 * was: interrupt her before it completes, and if you cannot, keep something solid
 * between you and the line she is drawing on the ground.
 *
 * <h2>Phases</h2>
 * <ul>
 *   <li><b>I - Spellcaster</b>: Star Bolt, Comet (a marked ground strike), Astral
 *       Burst (a radial volley), Constellation (lines on the floor: jump them or step
 *       off) and Blink.</li>
 *   <li><b>II - Overcharge</b> (60%): energy accumulates, spells scale, Judgement is on
 *       the table, and Eclipse (the stars fall everywhere except her circle: get in
 *       close) joins the rotation.</li>
 *   <li><b>III - Starfall</b> (25%): marked meteor rain, a Gravity Well, and her
 *       last spell - <b>Celestial Collapse</b>, which has to be out-run before it
 *       lands and leaves her spent.</li>
 * </ul>
 *
 * <p>Bolts are tracked here rather than spawned as vanilla projectiles: they are
 * points with a trail that this class moves and collides itself, so nothing
 * inherits a wither effect, a fire tick or an arrow's pickup rules.
 */
public final class StarboundMagisterManager {

   private static final String TAG = "ff_starbound_magister";
   private static final String BOSS_NAME = "\u00a7b\u00a7l\u2728 The Starbound Magister";
   private static final String SAY = "\u00a7bThe Magister\u00a7r\u00a77 \u203a \u00a7f";

   /** Her palette: cold starlight, the violet of the gaps between, and a sun's gold for the hits. */
   private static final int STARLIGHT = 0x88DDFF;
   private static final int NEBULA = 0xB58CFF;
   private static final int SOLAR = 0xFFD27A;

   private static final double MAX_HEALTH = 620.0;
   private static final double SEEK_RANGE = 90.0;
   /** See {@code ClockworkKingManager.STRAY_SWEEP_RADIUS}: just wider than the
    *  widest arena in the mod, and no wider. */
   public static final double STRAY_SWEEP_RADIUS = 96.0;
   /** She likes to sit about here from her target, and blinks if you get closer. */
   private static final double PREFERRED_RANGE = 13.0;
   private static final double BLINK_RANGE = 6.0;
   /** How long before she may reposition again. Long, on purpose - see the blink. */
   private static final int BLINK_COOLDOWN = 220;

   /** The arrival: how long she takes to come down, and from how high at most. */
   private static final int ARRIVAL_TICKS = 60;
   private static final int ARRIVAL_HEIGHT = 14;
   /** Nobody within her seek range for this long and she leaves, instead of waiting forever. */
   private static final int LONELY_TICKS = 1200;

   private static final int MAX_ENERGY = 6;
   private static final int ENERGY_INTERVAL = 60;
   /** Health she has to lose while charged to drop one charge. */
   private static final float INTERRUPT_DAMAGE = 24.0F;
   /**
    * Judgement: how long the line is drawn before the beam fires, how long it burns,
    * how far it reaches, and how wide it is where it crosses a body.
    *
    * <p>The tell is deliberately almost as long as the beam itself. This is a fixed
    * ray across open ground, not a homing attack: two seconds of a bright line
    * sweeping back and forth is enough to read it, step out of it, or put a tree
    * between yourself and it, and not long enough to walk away from the fight.
    */
   private static final int JUDGEMENT_TELL = 44;
   private static final int JUDGEMENT_BEAM = 44;
   private static final double JUDGEMENT_RANGE = 36.0;
   private static final double JUDGEMENT_HALF_WIDTH = 1.8;
   /** Half the arc it sweeps through on its way across - about 110 degrees in all. */
   private static final double JUDGEMENT_SWEEP = 0.96;
   /** One tick of contact, and how often the same body can be caught by it. */
   private static final float JUDGEMENT_DAMAGE = 7.0F;
   private static final int JUDGEMENT_HIT_COOLDOWN = 10;
   /** How long Celestial Collapse leaves her standing still and open. */
   private static final int EXHAUSTED_TICKS = 80;

   private static final float BOLT_DAMAGE = 7.0F;
   private static final float COMET_DAMAGE = 16.0F;
   private static final float COLLAPSE_DAMAGE = 34.0F;
   private static final float WELL_DAMAGE = 9.0F;
   private static final double WELL_PULL_RANGE = 18.0;
   private static final double WELL_BLAST_RADIUS = 7.0;

   /**
    * Constellation: star points on the floor joined by lines. The lines light for a
    * handful of ticks and only hurt feet that are on the ground, so jumping on the last
    * chime is as good an answer as walking off them.
    */
   private static final int CONSTELLATION_WARN = 40;
   private static final int CONSTELLATION_BURN = 5;
   private static final double CONSTELLATION_WIDTH = 0.9;
   private static final float CONSTELLATION_DAMAGE = 11.0F;
   /**
    * Eclipse: a dome marked round where she stands. When it lands every star falls
    * outside it, so the one safe place on the field is next to her.
    */
   private static final int ECLIPSE_WARN = 50;
   private static final double ECLIPSE_SAFE = 6.0;
   private static final float ECLIPSE_DAMAGE = 13.0F;

   private static final int BOLT_COOLDOWN = 55;
   private static final int COMET_COOLDOWN = 130;
   private static final int BURST_COOLDOWN = 200;
   private static final int WELL_COOLDOWN = 260;
   private static final int COLLAPSE_COOLDOWN = 300;
   private static final int CONSTELLATION_COOLDOWN = 240;
   private static final int ECLIPSE_COOLDOWN = 380;

   private static final int DEATH_CEREMONY_TICKS = 90;

   private static final Random RANDOM = new Random();

   /** One moving bolt. Position is authoritative; the trail is cosmetic. */
   private static final class Bolt {
      Vec3 pos;
      final Vec3 vel;
      final float damage;
      final UUID owner;
      int life;
      final int hue;

      Bolt(Vec3 pos, Vec3 vel, float damage, UUID owner, int life, int hue) {
         this.pos = pos;
         this.vel = vel;
         this.damage = damage;
         this.owner = owner;
         this.life = life;
         this.hue = hue;
      }
   }

   /** A telegraphed ground strike: shows where it will land, then lands. */
   private static final class Mark {
      final Vec3 pos;
      final double radius;
      final float damage;
      final int hue;
      int fuse;
      /** The falling star has been sent, so the landing is seen coming. */
      boolean falling;

      Mark(Vec3 pos, double radius, float damage, int hue, int fuse) {
         this.pos = pos;
         this.radius = radius;
         this.damage = damage;
         this.hue = hue;
         this.fuse = fuse;
      }
   }

   /**
    * A spell marked on the floor that has not landed yet: one line of a Constellation, or
    * the Eclipse's circle. Marked first and landed later, so each is a warning before it is
    * a hit.
    */
   private static final class Strike {
      static final int LINE = 0;
      static final int ECLIPSE = 1;
      final int kind;
      /** A line's two ends; the Eclipse uses {@code a} as its centre. */
      final Vec3 a;
      final Vec3 b;
      final long landAt;
      /** When a lit line goes out. */
      final long endAt;
      final double radius;
      final float damage;
      /** Who has already been hurt by this cast - shared by every line of one Constellation. */
      final Set<UUID> hit;
      /** The one strike of a cast that plays the cast's sounds, so six lines do not chime six times. */
      final boolean leader;
      boolean lit;

      Strike(int kind, Vec3 a, Vec3 b, long landAt, long endAt, double radius, float damage, Set<UUID> hit, boolean leader) {
         this.kind = kind;
         this.a = a;
         this.b = b;
         this.landAt = landAt;
         this.endAt = endAt;
         this.radius = radius;
         this.damage = damage;
         this.hit = hit;
         this.leader = leader;
      }
   }

   private static final class Fight {
      final UUID bossId;
      final UUID summoner;
      final ServerBossEvent bar;
      final Set<UUID> participants = new HashSet<>();
      int phase = 1;
      int energy;
      long nextEnergy;
      boolean dying;
      int deathTicks;
      /** The descent: ticks left, and the heights she comes down from and lands on. */
      int arrivalTicks;
      double arrivalFrom;
      double arrivalGround;
      /** Her health last tick and the damage banked toward breaking a charge. */
      float lastHealth = (float) MAX_HEALTH;
      float interruptDamage;
      /** When the field first went empty, or -1 while somebody is near. */
      long lonelySince = -1L;
      /** After Celestial Collapse: she does nothing until this tick. */
      long spentUntil;
      /** During Eclipse: she holds her circle and does not step or blink until this tick. */
      long holdUntil;
      long nextBolt;
      long nextComet;
      long nextBurst;
      long nextBlink;
      long nextWell;
      long nextCollapse;
      long nextConstellation;
      long nextEclipse;
      long nextTaunt;
      int collapseCharge;
      /** Judgement: the tell countdown, then the beam's burn, and where it points. */
      int judgementTell;
      int judgementBeam;
      double judgementFrom;
      double judgementTo;
      Vec3 judgementOrigin;
      /** When each body may be caught by the beam again, so contact cannot stack. */
      final Map<UUID, Long> judgementHits = new HashMap<>();
      int wellTicks;
      Vec3 wellCenter;
      final List<Bolt> bolts = new ArrayList<>();
      final List<Mark> marks = new ArrayList<>();
      final List<Strike> strikes = new ArrayList<>();

      Fight(UUID bossId, UUID summoner, ServerBossEvent bar) {
         this.bossId = bossId;
         this.summoner = summoner;
         this.bar = bar;
      }

      /** Drops every spell in flight - used by the death ceremony and every teardown. */
      void clearSpells() {
         bolts.clear();
         marks.clear();
         strikes.clear();
         judgementTell = 0;
         judgementBeam = 0;
         judgementHits.clear();
         wellTicks = 0;
         collapseCharge = 0;
         holdUntil = 0L;
      }
   }

   private static final Map<UUID, Fight> FIGHTS = new HashMap<>();

   private StarboundMagisterManager() {
   }

   // ------------------------------------------------------------------ public API

   public static boolean isMagister(Entity entity) {
      return entity != null && entity.entityTags().contains(TAG);
   }

   public static int activeCount() {
      return FIGHTS.size();
   }

   public static int boltCount(UUID bossId) {
      Fight fight = FIGHTS.get(bossId);
      return fight == null ? 0 : fight.bolts.size();
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
         Safe.run("magister abandon", () -> shutDown(server, fight));
         ended++;
      }
      return ended;
   }

   public static void onServerStopping(MinecraftServer server) {
      for (Fight fight : new ArrayList<>(FIGHTS.values())) {
         Safe.run("magister shutdown", () -> shutDown(server, fight));
      }
      FIGHTS.clear();
   }

   /** Astral Compass right-click: follow the needle and she will be there. */
   public static String useAstralCompass(ServerPlayer player, ItemStack held) {
      String err = summon(player);
      if (err != null) {
         return err;
      }
      if (!player.getAbilities().instabuild) {
         held.shrink(1);
      }
      return null;
   }

   /**
    * Calls her down. She does not appear at your feet: a star is marked on the ground a
    * few blocks ahead of you, a rune circle turns under it, and she comes down out of the
    * sky onto it over three seconds. She cannot be hurt on the way down, and when she
    * lands the shockwave shoves anyone standing on the mark back out of it.
    */
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
            return "You already have a Magister walking - let her finish first!";
         }
      }

      ServerLevel level = summoner.level();
      Mob boss = (Mob) EntityTypes.EVOKER.create(level, EntitySpawnReason.COMMAND);
      if (boss == null) {
         return "The stars would not answer - she did not arrive.";
      }

      AttributeInstance maxHp = boss.getAttribute(Attributes.MAX_HEALTH);
      if (maxHp != null) {
         maxHp.setBaseValue(MAX_HEALTH);
      }
      boss.setHealth((float) MAX_HEALTH);
      AttributeInstance follow = boss.getAttribute(Attributes.FOLLOW_RANGE);
      if (follow != null) {
         follow.setBaseValue(SEEK_RANGE);
      }
      AttributeInstance kb = boss.getAttribute(Attributes.KNOCKBACK_RESISTANCE);
      if (kb != null) {
         kb.setBaseValue(1.0);
      }

      // The landing spot: a few blocks ahead of the summoner, so the descent is in front
      // of them rather than on their head. If that spot is a cliff or a wall, under the
      // summoner it is.
      Vec3 look = summoner.getLookAngle();
      Vec3 flat = new Vec3(look.x, 0.0, look.z);
      flat = flat.lengthSqr() < 1.0E-4 ? new Vec3(0.0, 0.0, 1.0) : flat.normalize();
      double x = summoner.getX() + flat.x * 7.0;
      double z = summoner.getZ() + flat.z * 7.0;
      double ground = BossGrounding.groundY(level, x, z, summoner.getY());
      if (Math.abs(ground - summoner.getY()) > 6.0) {
         x = summoner.getX();
         z = summoner.getZ();
         ground = BossGrounding.groundY(level, x, z, summoner.getY());
      }
      // As high as the open air above the spot allows, so she never starts inside a roof.
      int height = 0;
      for (int h = 1; h <= ARRIVAL_HEIGHT; h++) {
         if (solid(level, new Vec3(x, ground + h + 1.5, z))) {
            break;
         }
         height = h;
      }

      boss.setPersistenceRequired();
      boss.setCustomName(Component.literal(BOSS_NAME));
      boss.setCustomNameVisible(true);
      boss.setNoAi(true);
      boss.setNoGravity(true);
      boss.setInvulnerable(true);
      boss.addTag(TAG);
      // The shared marker plus the visible-and-persistent guarantee: see
      // BossManager.markBoss for why a boss has to say so itself.
      BossManager.markBoss(boss);
      boss.setPos(x, ground + height, z);
      level.addFreshEntity(boss);

      ServerBossEvent bar = new ServerBossEvent(
         UUID.randomUUID(), Component.literal(BOSS_NAME), BossBarColor.BLUE, BossBarOverlay.PROGRESS
      );
      bar.setVisible(true);
      // Her own world only: the bar used to go to every player on the server, the Nether and
      // the End included, for a fight none of them could see. Late arrivals in her world are
      // given it by the fight tick as they walk in.
      for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
         if (p.level() == level) {
            bar.addPlayer(p);
         }
      }

      Fight fight = new Fight(boss.getUUID(), summoner.getUUID(), bar);
      fight.participants.add(summoner.getUUID());
      fight.arrivalTicks = ARRIVAL_TICKS;
      fight.arrivalFrom = ground + height;
      fight.arrivalGround = ground;
      long now = ServerClock.clock(level);
      long landed = now + ARRIVAL_TICKS;
      fight.nextBolt = landed + 30L;
      fight.nextComet = landed + 110L;
      fight.nextBurst = landed + 190L;
      fight.nextConstellation = landed + 160L;
      fight.nextBlink = landed + 60L;
      fight.nextEnergy = landed + ENERGY_INTERVAL;
      fight.nextTaunt = landed + 160L;
      FIGHTS.put(boss.getUUID(), fight);

      announce(level, "\u00a73\u00a7m\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550");
      announce(level, "    \u00a7b\u00a7l\u2728 THE STARBOUND MAGISTER \u2728");
      announce(level, "    \u00a77A star is falling. Toward you.");
      announce(level, "    \u00a78\u201c\u00a7fLook up.\u00a78\u201d");
      announce(level, "    \u00a78Below 60% she charges stars. \u00a77Hit her to break them.");
      announce(level, "\u00a73\u00a7m\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550");

      // The mark she lands on: a summoning circle turning under a wider ring of her
      // runes, a spiral climbing to meet her, and stars raining onto the spot for as long
      // as she takes to come down.
      Vec3 spot = new Vec3(x, ground, z);
      Fx.summonCircle(level, ParticleTypes.END_ROD, spot.add(0.0, 0.05, 0.0), 3.5, ARRIVAL_TICKS, STARLIGHT);
      Fx.runeCircle(level, ParticleTypes.ENCHANT, spot.add(0.0, 0.05, 0.0), 6.0, ARRIVAL_TICKS + 10, SOLAR);
      Fx.spiral(level, ParticleTypes.END_ROD, spot, Math.max(4.0, height + 2.0), ARRIVAL_TICKS, NEBULA);
      Fx.pillar(level, ParticleTypes.END_ROD, spot, height + 6.0, STARLIGHT);
      Fx.starfall(level, ParticleTypes.END_ROD, spot, 9.0, ARRIVAL_TICKS + 20, STARLIGHT);
      level.playSound(null, x, ground, z, ModSounds.BOSS_SPAWN, SoundSource.HOSTILE, 1.3F, 1.5F);
      level.playSound(null, x, ground, z, SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.HOSTILE, 1.6F, 0.6F);
      level.playSound(null, x, ground, z, SoundEvents.BEACON_AMBIENT, SoundSource.HOSTILE, 1.6F, 1.4F);
      Advancements.grant(summoner, "summon_magister");
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
      long now = ServerClock.clock(server);
      for (Fight fight : new ArrayList<>(FIGHTS.values())) {
         Safe.run("magister tick", () -> tickFight(server, fight, now));
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
    * Sweeps up Magister bodies left behind by a crash or a hard restart - see
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
            if (isMagister(mob) && !FIGHTS.containsKey(mob.getUUID())) {
               mob.discard();
               removed++;
            }
         }
      }
      return removed;
   }

   /** Pays this boss's loot exactly once, however she dies, and releases the fight. */
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
         shutDown(server, fight);
         return;
      }
      ServerLevel level = (ServerLevel) boss.level();

      // Who is in the fight: anybody alive in her dimension and in range. Spectators and
      // the mod's puppet bodies are not, and late arrivals get the bar as they walk in.
      boolean anyoneNear = false;
      for (ServerPlayer p : server.getPlayerList().getPlayers()) {
         if (p.level() != level) {
            // Gone through a portal: the bar goes with them rather than hanging over a
            // fight in another world.
            fight.bar.removePlayer(p);
            continue;
         }
         if (!p.isAlive() || p.isSpectator() || BossManager.isFakePlayer(p)) {
            continue;
         }
         if (p.distanceToSqr(boss) < SEEK_RANGE * SEEK_RANGE) {
            fight.participants.add(p.getUUID());
            fight.bar.addPlayer(p);
            anyoneNear = true;
         }
      }

      tickBolts(level, fight, boss);
      tickMarks(level, fight, boss);

      if (fight.dying) {
         tickDeath(server, boss, fight);
         return;
      }
      if (fight.arrivalTicks > 0) {
         tickArrival(level, boss, fight);
         return;
      }
      tickStrikes(level, boss, fight, now);

      // Nobody left within reach: she does not stand in a field forever holding a bar
      // over the server. She leaves, the same way she came.
      if (anyoneNear) {
         fight.lonelySince = -1L;
      } else if (fight.lonelySince < 0L) {
         fight.lonelySince = now;
      } else if (now - fight.lonelySince >= LONELY_TICKS) {
         leave(server, level, boss, fight);
         return;
      }

      fight.bar.setProgress(Math.max(0.0F, Math.min(1.0F, boss.getHealth() / boss.getMaxHealth())));
      fight.bar.setName(Component.literal(barName(fight)));
      fight.bar.setColor(fight.phase >= 2 ? BossBarColor.RED : BossBarColor.BLUE);

      // She stands on the ground. She used to hover three blocks above the
      // summoner for the entire fight, which put her out of melee reach and made
      // her blink - a repositioning tool - a way to get further away than anyone
      // could follow. Gravity stays off so a ravine cannot swallow her, but the
      // floor under her is what decides her height.
      BossGrounding.clampToGround(level, boss, 0.9);

      float share = boss.getHealth() / boss.getMaxHealth();
      if (share <= 0.25F && fight.phase < 3) {
         enterPhase(level, boss, fight, 3);
      } else if (share <= 0.6F && fight.phase < 2) {
         enterPhase(level, boss, fight, 2);
      }

      trackInterrupts(level, boss, fight);

      boolean judging = fight.judgementTell > 0 || fight.judgementBeam > 0;
      boolean busy = judging || fight.collapseCharge > 0 || fight.wellTicks > 0 || now < fight.spentUntil || now < fight.holdUntil;
      if (busy) {
         // No charge lands in the middle of another spell. It used to: a charge ticking
         // in during the beam announced "1/6" while the beam was still burning, and one
         // landing mid-Collapse started a Judgement on top of it.
         fight.nextEnergy = Math.max(fight.nextEnergy, now + 20L);
      } else if (fight.phase >= 2 && now >= fight.nextEnergy) {
         fight.nextEnergy = now + ENERGY_INTERVAL;
         fight.energy = Math.min(MAX_ENERGY, fight.energy + 1);
         Vec3 core = boss.position().add(0.0, 1.4, 0.0);
         Fx.flare(level, ParticleTypes.END_ROD, core, 0.8 + fight.energy * 0.2, hue(fight.energy));
         Fx.ring(level, ParticleTypes.END_ROD, boss.position().add(0.0, 0.1, 0.0), 1.5 + fight.energy * 0.4, hue(fight.energy));
         level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.HOSTILE, 1.0F, 0.8F + fight.energy * 0.12F);
         if (fight.energy >= MAX_ENERGY) {
            startJudgement(level, boss, fight);
            return;
         }
         announceNear(level, boss, 64.0, "&b\u2726 &7Charge &b" + fight.energy + "&7/&b" + MAX_ENERGY);
      }

      // Judgement owns her whole body while it runs: she does not step, blink or
      // cast anything else, which is the window the move costs her.
      if (judging) {
         tickJudgement(level, boss, fight, now);
         return;
      }
      if (fight.collapseCharge > 0) {
         tickCollapse(level, boss, fight, now);
         return;
      }
      if (fight.wellTicks > 0) {
         tickWell(level, boss, fight);
         return;
      }
      // Spent after the Collapse, or holding the Eclipse's circle: she stands and takes it.
      if (now < fight.spentUntil || now < fight.holdUntil) {
         return;
      }

      // The wander: keep her own distance instead of chasing you down.
      ServerPlayer target = nearestPlayer(boss, SEEK_RANGE);
      if (target != null) {
         maintainDistance(level, boss, fight, target, now);
      }
      chooseMove(level, boss, fight, now, target);

      if (now >= fight.nextTaunt) {
         fight.nextTaunt = now + 300L + RANDOM.nextInt(200);
         taunt(level, boss, fight);
      }
   }

   private static String barName(Fight fight) {
      String phase = fight.phase == 1 ? "Phase I" : fight.phase == 2 ? "Phase II" : "Phase III";
      String energy = fight.phase >= 2 ? " \u00a78| \u00a7b" + fight.energy + "/" + MAX_ENERGY + " energy" : "";
      if (fight.judgementTell > 0 || fight.judgementBeam > 0) {
         energy = energy + " \u00a78| \u00a7c\u00a7lJUDGEMENT";
      }
      return BOSS_NAME + " \u00a78| \u00a7f" + phase + energy;
   }

   /**
    * The descent. She comes down out of the sky onto the marked spot, fast at first and
    * settling at the end, so the landing reads as a landing rather than a teleport. The
    * runes under her pulse wider every half second and the chime climbs with them, which
    * is the count-down to the fight starting.
    */
   private static void tickArrival(ServerLevel level, Mob boss, Fight fight) {
      fight.arrivalTicks--;
      double t = 1.0 - (double) fight.arrivalTicks / ARRIVAL_TICKS;
      double ease = 1.0 - (1.0 - t) * (1.0 - t);
      double y = fight.arrivalFrom + (fight.arrivalGround - fight.arrivalFrom) * ease;
      boss.setPos(boss.getX(), y, boss.getZ());
      boss.setDeltaMovement(Vec3.ZERO);
      boss.hurtMarked = true;
      // The spiral and the starfall sent at the summon carry the descent for modded clients;
      // this trail is the vanilla version. A bare sendParticles inside FfVfx.enter/exit is not
      // routed through the transport, so it used to reach modded clients too.
      Fx.vanilla(level, ParticleTypes.END_ROD, boss.getX(), y + 1.0, boss.getZ(), 3, 0.3, 0.6, 0.3, 0.02);
      Vec3 spot = new Vec3(boss.getX(), fight.arrivalGround, boss.getZ());
      if (fight.arrivalTicks > 0 && fight.arrivalTicks % 10 == 0) {
         Fx.ring(level, ParticleTypes.END_ROD, spot.add(0.0, 0.1, 0.0), 2.0 + t * 5.0, t > 0.5 ? SOLAR : STARLIGHT);
         level.playSound(null, spot.x, spot.y, spot.z, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.HOSTILE, 1.4F, 0.6F + (float) t);
      }
      if (fight.arrivalTicks <= 0) {
         arrive(level, boss, fight, spot);
      }
   }

   /** Touchdown: the flash, the shockwave, and anyone standing on the mark is shoved off it. */
   private static void arrive(ServerLevel level, Mob boss, Fight fight, Vec3 spot) {
      boss.setPos(spot.x, spot.y, spot.z);
      boss.setInvulnerable(false);
      Vec3 heart = spot.add(0.0, 1.2, 0.0);
      Fx.flare(level, ParticleTypes.END_ROD, heart, 3.0, SOLAR);
      Fx.starburst(level, ParticleTypes.END_ROD, heart, 7.0, STARLIGHT);
      Fx.shockwave(level, ParticleTypes.END_ROD, spot, 11.0, NEBULA);
      Fx.nova(level, ParticleTypes.FIREWORK, spot.add(0.0, 0.3, 0.0), 4.0, STARLIGHT);
      level.playSound(null, spot.x, spot.y, spot.z, SoundEvents.LIGHTNING_BOLT_IMPACT, SoundSource.HOSTILE, 1.6F, 1.2F);
      level.playSound(null, spot.x, spot.y, spot.z, SoundEvents.FIREWORK_ROCKET_LARGE_BLAST, SoundSource.HOSTILE, 1.6F, 0.8F);
      level.playSound(null, spot.x, spot.y, spot.z, SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.HOSTILE, 1.6F, 1.2F);
      for (ServerPlayer p : playersNear(level, spot.x, spot.y, spot.z, 5.0)) {
         Vec3 out = new Vec3(p.getX() - spot.x, 0.0, p.getZ() - spot.z);
         out = out.lengthSqr() < 1.0E-4 ? new Vec3(1.0, 0.0, 0.0) : out.normalize();
         p.setDeltaMovement(out.x * 0.8, 0.45, out.z * 0.8);
         p.hurtMarked = true;
      }
      announce(level, SAY + "\"\u00a7fThere you are.\"");
   }

   /**
    * Banks the damage she takes while holding charges and breaks one for every
    * {@link #INTERRUPT_DAMAGE}. At most one per tick and the bank is capped, so a single
    * huge blow breaks two rather than the whole meter.
    */
   private static void trackInterrupts(ServerLevel level, Mob boss, Fight fight) {
      float hp = boss.getHealth();
      float lost = fight.lastHealth - hp;
      fight.lastHealth = hp;
      if (fight.phase < 2 || fight.energy <= 0) {
         fight.interruptDamage = 0.0F;
         return;
      }
      if (lost > 0.0F) {
         fight.interruptDamage += lost;
      }
      if (fight.interruptDamage < INTERRUPT_DAMAGE) {
         return;
      }
      fight.interruptDamage = Math.min(INTERRUPT_DAMAGE, fight.interruptDamage - INTERRUPT_DAMAGE);
      fight.energy--;
      Vec3 core = boss.position().add(0.0, 1.4, 0.0);
      // A broken charge breaks like crystal: faceted shards of the star's own colour burst off
      // her, with a short spray of rays, so every interrupt is seen landing.
      Fx.gemShards(level, ParticleTypes.END_ROD, core, 1.1, hue(fight.energy + 1));
      Fx.starburst(level, ParticleTypes.END_ROD, core, 2.2, hue(fight.energy + 1));
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.GLASS_BREAK, SoundSource.HOSTILE, 1.2F, 1.4F);
      announceNear(level, boss, 64.0, "&b\u2726 Star broken &8(&7" + fight.energy + "/" + MAX_ENERGY + "&8)");
   }

   /** She has been alone too long. Out through a closing wormhole; nobody is paid. */
   private static void leave(MinecraftServer server, ServerLevel level, Mob boss, Fight fight) {
      wormhole(level, boss.position().add(0.0, 1.0, 0.0), false, NEBULA);
      Fx.pillar(level, ParticleTypes.END_ROD, boss.position(), 20.0, STARLIGHT);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.BEACON_DEACTIVATE, SoundSource.HOSTILE, 1.6F, 1.2F);
      announce(level, "\u00a78The Magister finds nobody watching. \u00a77She leaves.");
      shutDown(server, fight);
   }

   /**
    * She keeps roughly {@link #PREFERRED_RANGE} between herself and her target,
    * and blinks outright if anyone gets inside {@link #BLINK_RANGE}.
    *
    * <p>What she no longer does is <b>run</b>. The band used to be a strafe - inside it she
    * circled sideways every single tick - so a fight against her was a chase with no
    * moment to swing, and the report "she will not stop running" is what that looks like
    * from the arena. Inside the band she now holds her ground and casts: she still backs off
    * if you close and steps in if you run, which is what keeps her a duelist, but the
    * decision is per distance rather than per tick, and the step itself is slow enough to
    * be walked down.
    */
   private static void maintainDistance(ServerLevel level, Mob boss, Fight fight, ServerPlayer target, long now) {
      Vec3 toTarget = target.position().subtract(boss.position());
      double dist = toTarget.length();
      if (dist >= 0.01) {
         Vec3 unit = toTarget.scale(1.0 / dist);
         Vec3 wish;
         if (dist < PREFERRED_RANGE - 3.0) {
            wish = unit.scale(-1.0);
         } else if (dist > PREFERRED_RANGE + 3.0) {
            wish = unit;
         } else {
            // Standing in her band: no step at all. This is the change that stops the
            // fight being a footrace - she is a caster, and a caster who has her range
            // has nothing to do but cast.
            wish = Vec3.ZERO;
         }
         if (wish != Vec3.ZERO) {
            // Moved by position, not by delta movement: a no-AI puppet never runs
            // its own physics step, exactly like the Clockwork machines. Feeding
            // setDeltaMovement here would have left her hovering in place.
            double speed = 0.085 + fight.energy * 0.005;
            double x = boss.getX() + wish.x * speed;
            double z = boss.getZ() + wish.z * speed;
            // Follow the ground rather than a sine wave: at a fixed altitude a
            // scripted caster walks into hills and out over cliffs.
            double y = BossGrounding.groundY(level, x, z, boss.getY());
            boss.setPos(x, y, z);
            boss.hurtMarked = true;
            // Stardust where she steps: a few stars settling round her feet every few ticks
            // for modded clients (one cue that animates itself), a single mote for the rest.
            if (boss.tickCount % 8 == 0) {
               com.fortuneandfavors.net.FfVfx.shape(level, FxKinds.STARFALL, ParticleTypes.END_ROD, new Vec3(x, y, z), Vec3.ZERO, 1.0, 10.0, STARLIGHT);
            }
            Fx.vanilla(level, ParticleTypes.END_ROD, x, y + 0.6, z, 1, 0.25, 0.2, 0.25, 0.0);
         }
      }

      // The blink is her answer to being cornered, and nothing else. It is on a long
      // cooldown and it puts her back inside her own casting band rather than out of
      // it, so it is a way to stop being in melee, not a way to leave the fight: a
      // player who sprints after her is on top of her again in under two seconds.
      if (dist <= BLINK_RANGE && now >= fight.nextBlink) {
         fight.nextBlink = now + BLINK_COOLDOWN;
         blink(level, boss, target);
      }
   }

   private static void blink(ServerLevel level, Mob boss, ServerPlayer target) {
      double angle = RANDOM.nextDouble() * Math.PI * 2.0;
      double dist = PREFERRED_RANGE - 1.0 + RANDOM.nextDouble() * 3.0;
      double x = target.getX() + Math.cos(angle) * dist;
      double z = target.getZ() + Math.sin(angle) * dist;
      // Blink onto the ground, not into the sky. Landing two to four blocks up
      // made every reposition a free escape from melee.
      double y = BossGrounding.groundY(level, x, z, target.getY());
      Vec3 from = boss.position();
      Vec3 to = new Vec3(x, y, z);
      wormhole(level, from.add(0.0, 1.0, 0.0), false, NEBULA);
      Fx.lightning(level, ParticleTypes.ELECTRIC_SPARK, from.add(0.0, 1.0, 0.0), to.add(0.0, 1.0, 0.0), STARLIGHT);
      boss.setPos(x, y, z);
      boss.setDeltaMovement(Vec3.ZERO);
      boss.hurtMarked = true;
      wormhole(level, to.add(0.0, 1.0, 0.0), true, NEBULA);
      Fx.flare(level, ParticleTypes.END_ROD, to.add(0.0, 1.0, 0.0), 1.4, STARLIGHT);
      Fx.ring(level, ParticleTypes.END_ROD, to.add(0.0, 0.1, 0.0), 2.0, NEBULA);
      level.playSound(null, x, y, z, SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE, 1.2F, 1.6F);
   }

   // --------------------------------------------------------------------- moves

   private static void chooseMove(ServerLevel level, Mob boss, Fight fight, long now, ServerPlayer target) {
      if (target == null) {
         return;
      }
      if (fight.phase >= 3 && now >= fight.nextCollapse) {
         fight.nextCollapse = now + COLLAPSE_COOLDOWN;
         startCollapse(level, boss, fight, target);
         return;
      }
      if (fight.phase >= 3 && now >= fight.nextWell) {
         fight.nextWell = now + WELL_COOLDOWN;
         startWell(level, boss, fight, target);
         return;
      }
      if (fight.phase >= 2 && now >= fight.nextEclipse) {
         fight.nextEclipse = now + ECLIPSE_COOLDOWN;
         eclipse(level, boss, fight, now);
         return;
      }
      if (now >= fight.nextConstellation) {
         fight.nextConstellation = now + CONSTELLATION_COOLDOWN;
         constellation(level, boss, fight, target, now);
         return;
      }
      if (now >= fight.nextBurst) {
         fight.nextBurst = now + BURST_COOLDOWN;
         astralBurst(level, boss, fight);
         return;
      }
      if (now >= fight.nextComet) {
         fight.nextComet = now + COMET_COOLDOWN;
         comet(level, boss, fight, target);
         return;
      }
      if (now >= fight.nextBolt) {
         fight.nextBolt = now + BOLT_COOLDOWN;
         starBolt(level, boss, fight, target);
      }
   }

   private static void starBolt(ServerLevel level, Mob boss, Fight fight, ServerPlayer target) {
      int count = 3 + fight.energy;
      Vec3 aim = target.position().add(0.0, target.getBbHeight() * 0.5, 0.0);
      for (int i = 0; i < count; i++) {
         Vec3 from = boltOrigin(boss, i, count);
         Vec3 dir = aim.subtract(from);
         if (dir.lengthSqr() < 1.0E-4) {
            dir = new Vec3(0.0, 0.0, 1.0);
         }
         dir = dir.normalize();
         fight.bolts.add(new Bolt(from, dir.scale(0.85), BOLT_DAMAGE, boss.getUUID(), 90, STARLIGHT));
      }
      Vec3 hand = boss.position().add(0.0, boss.getBbHeight() * 0.65, 0.0);
      Fx.muzzle(level, ParticleTypes.END_ROD, hand, aim.subtract(hand), STARLIGHT);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.EVOKER_CAST_SPELL, SoundSource.HOSTILE, 1.1F, 1.4F);
   }

   private static void astralBurst(ServerLevel level, Mob boss, Fight fight) {
      int count = 10 + fight.energy * 3;
      Vec3 from = boss.position().add(0.0, boss.getBbHeight() * 0.6, 0.0);
      for (int i = 0; i < count; i++) {
         double angle = i * (Math.PI * 2.0 / count);
         Vec3 dir = new Vec3(Math.cos(angle), 0.06, Math.sin(angle)).normalize();
         fight.bolts.add(new Bolt(from, dir.scale(0.7), BOLT_DAMAGE * 0.8F, boss.getUUID(), 70, 0xD9A0FF));
      }
      Fx.nova(level, ParticleTypes.END_ROD, from, 3.0, NEBULA);
      Fx.starburst(level, ParticleTypes.END_ROD, from, 4.0, NEBULA);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.EVOKER_PREPARE_ATTACK, SoundSource.HOSTILE, 1.4F, 1.2F);
      announceNear(level, boss, 64.0, "&d\u2726 Astral Burst &8- &7find a gap.");
   }

   private static void comet(ServerLevel level, Mob boss, Fight fight, ServerPlayer target) {
      // On the floor under them, not at their feet: a mark cast on a jumping player used
      // to hang in the air and land on nothing.
      Vec3 at = new Vec3(target.getX(), BossGrounding.groundY(level, target.getX(), target.getZ(), target.getY()), target.getZ());
      addMark(level, fight, new Mark(at, 4.0, COMET_DAMAGE, SOLAR, 30));
      level.playSound(null, at.x, at.y, at.z, SoundEvents.RESPAWN_ANCHOR_CHARGE, SoundSource.HOSTILE, 1.0F, 1.2F);
      announceNear(level, boss, 64.0, "&e\u2604 Comet &8- &7leave the ring.");
   }

   /**
    * Puts a mark down with its rune circle, timed to run out when the star lands, and a
    * starfall over exactly the ring the hit will cover: small stars come down inside it for
    * the whole fuse, so the circle to leave reads as "the sky is falling here".
    */
   private static void addMark(ServerLevel level, Fight fight, Mark mark) {
      fight.marks.add(mark);
      Fx.runeCircle(level, ParticleTypes.END_ROD, mark.pos.add(0.0, 0.05, 0.0), mark.radius, mark.fuse, mark.hue);
      Fx.starfall(level, ParticleTypes.END_ROD, mark.pos, mark.radius, mark.fuse, mark.hue);
   }

   // ----------------------------------------------------------- new: constellation

   /**
    * <b>Constellation.</b> Four to seven stars are pinned to the floor in a zig-zag
    * that runs straight through her target, joined by faint lines. Two seconds later the
    * lines light for a quarter of a second, and anyone standing on one is burnt.
    *
    * <p>The answers: step off the lines, or jump. Only feet on the ground are caught, and
    * the last three chimes are spaced so a jump on the final one clears the whole burn.
    * One hit per player per cast, however many lines meet where they stand.
    */
   private static void constellation(ServerLevel level, Mob boss, Fight fight, ServerPlayer target, long now) {
      Fx.starTrail(level, ParticleTypes.END_ROD, boss.position(), 4.0, 24, NEBULA);
      int nodes = 4 + Math.min(2, fight.energy / 2) + (fight.phase >= 3 ? 1 : 0);
      Vec3[] stars = new Vec3[nodes];
      int mid = nodes / 2;
      double heading = RANDOM.nextDouble() * Math.PI * 2.0;
      stars[mid] = new Vec3(target.getX(), BossGrounding.groundY(level, target.getX(), target.getZ(), target.getY()), target.getZ());
      for (int i = mid - 1; i >= 0; i--) {
         stars[i] = nextStar(level, stars[i + 1], heading + Math.PI);
      }
      for (int i = mid + 1; i < nodes; i++) {
         stars[i] = nextStar(level, stars[i - 1], heading);
      }

      long land = now + CONSTELLATION_WARN;
      Set<UUID> hit = new HashSet<>();
      for (int i = 0; i + 1 < nodes; i++) {
         fight.strikes.add(new Strike(Strike.LINE, stars[i], stars[i + 1], land, land + CONSTELLATION_BURN,
            CONSTELLATION_WIDTH, CONSTELLATION_DAMAGE, hit, i == 0));
         Fx.chains(level, ParticleTypes.ENCHANT, stars[i].add(0.0, 0.15, 0.0), stars[i + 1].add(0.0, 0.15, 0.0), NEBULA);
      }
      for (Vec3 star : stars) {
         Fx.runeCircle(level, ParticleTypes.END_ROD, star.add(0.0, 0.05, 0.0), 1.1, CONSTELLATION_WARN, SOLAR);
         Fx.flare(level, ParticleTypes.END_ROD, star.add(0.0, 0.6, 0.0), 0.6, STARLIGHT);
      }
      Fx.aura(level, ParticleTypes.ENCHANT, boss.position(), 2.4, CONSTELLATION_WARN, NEBULA);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.HOSTILE, 1.6F, 1.0F);
      announce(level, SAY + "\"\u00a7fConnect the dots.\"");
      announceNear(level, boss, 64.0, "&8Lines on the ground. &7Step off, or jump the last chime.");
   }

   /** The next star of a constellation: four to six blocks on, turned up to 50 degrees either way. */
   private static Vec3 nextStar(ServerLevel level, Vec3 from, double heading) {
      double angle = heading + (RANDOM.nextDouble() - 0.5) * 1.75;
      double step = 4.0 + RANDOM.nextDouble() * 2.0;
      double x = from.x + Math.cos(angle) * step;
      double z = from.z + Math.sin(angle) * step;
      return new Vec3(x, BossGrounding.groundY(level, x, z, from.y), z);
   }

   // ---------------------------------------------------------------- new: eclipse

   /**
    * <b>Eclipse.</b> She marks a dome six blocks round where she stands and stops moving.
    * Two and a half seconds later every star in the sky falls on everyone <em>outside</em>
    * it.
    *
    * <p>The answer is the opposite of every other spell she has: get close. She does not
    * step back or blink while the dome is up, so for once the melee player is the safe one
    * and the archer at range has to commit.
    */
   private static void eclipse(ServerLevel level, Mob boss, Fight fight, long now) {
      Vec3 center = boss.position();
      long land = now + ECLIPSE_WARN;
      fight.strikes.add(new Strike(Strike.ECLIPSE, center, center, land, land, ECLIPSE_SAFE, ECLIPSE_DAMAGE, new HashSet<>(), true));
      fight.holdUntil = land;
      fight.nextBlink = Math.max(fight.nextBlink, land + 40L);
      Fx.dome(level, ParticleTypes.END_ROD, center, ECLIPSE_SAFE, ECLIPSE_WARN, STARLIGHT);
      Fx.runeCircle(level, ParticleTypes.ENCHANT, center.add(0.0, 0.05, 0.0), ECLIPSE_SAFE, ECLIPSE_WARN, SOLAR);
      Fx.emberRain(level, ParticleTypes.REVERSE_PORTAL, center, 22.0, ECLIPSE_WARN, NEBULA);
      level.playSound(null, center.x, center.y, center.z, SoundEvents.BEACON_DEACTIVATE, SoundSource.HOSTILE, 2.0F, 0.5F);
      level.playSound(null, center.x, center.y, center.z, SoundEvents.BEACON_POWER_SELECT, SoundSource.HOSTILE, 1.4F, 0.6F);
      announce(level, SAY + "\"\u00a7fLights out.\"");
      announceNear(level, boss, 72.0, "&8The sky goes dark outside her circle. &7Get inside it.");
   }

   /** Runs the pending Constellation lines and Eclipse circles: warn, then land. */
   private static void tickStrikes(ServerLevel level, Mob boss, Fight fight, long now) {
      if (fight.strikes.isEmpty()) {
         return;
      }
      for (Iterator<Strike> it = fight.strikes.iterator(); it.hasNext();) {
         Strike s = it.next();
         if (now < s.landAt) {
            warnStrike(level, s, now);
            continue;
         }
         if (s.kind == Strike.ECLIPSE) {
            it.remove();
            landEclipse(level, boss, fight, s);
            continue;
         }
         if (!s.lit) {
            s.lit = true;
            Vec3 a = s.a.add(0.0, 0.2, 0.0);
            Vec3 b = s.b.add(0.0, 0.2, 0.0);
            Fx.lightning(level, ParticleTypes.ELECTRIC_SPARK, a, b, SOLAR);
            Fx.beam(level, ParticleTypes.END_ROD, a, b, STARLIGHT);
            Fx.starburst(level, ParticleTypes.END_ROD, a.add(0.0, 0.4, 0.0), 1.6, SOLAR);
            if (s.leader) {
               level.playSound(null, s.a.x, s.a.y, s.a.z, SoundEvents.LIGHTNING_BOLT_IMPACT, SoundSource.HOSTILE, 1.2F, 1.6F);
               level.playSound(null, s.a.x, s.a.y, s.a.z, SoundEvents.AMETHYST_BLOCK_HIT, SoundSource.HOSTILE, 1.6F, 0.8F);
            }
         }
         burnLine(level, boss, s);
         if (now >= s.endAt) {
            it.remove();
         }
      }
   }

   /** The tell while a strike waits: the faint line redrawn, and the chimes that time the jump. */
   private static void warnStrike(ServerLevel level, Strike s, long now) {
      long left = s.landAt - now;
      if (s.kind == Strike.LINE) {
         if (left % 8 == 0) {
            Fx.beam(level, ParticleTypes.ENCHANT, s.a.add(0.0, 0.12, 0.0), s.b.add(0.0, 0.12, 0.0), NEBULA);
         }
         if (s.leader && left <= 15 && left % 5 == 0) {
            level.playSound(null, s.a.x, s.a.y, s.a.z, SoundEvents.NOTE_BLOCK_PLING, SoundSource.HOSTILE, 1.6F, 1.0F + (15 - left) / 15.0F);
         }
      } else if (s.leader && left % 10 == 0) {
         Fx.ring(level, ParticleTypes.END_ROD, s.a.add(0.0, 0.1, 0.0), s.radius, SOLAR);
         level.playSound(null, s.a.x, s.a.y, s.a.z, SoundEvents.WARDEN_HEARTBEAT, SoundSource.HOSTILE, 1.6F, 1.2F);
      }
   }

   /** A lit Constellation line: grounded feet on it are burnt, once per cast. */
   private static void burnLine(ServerLevel level, Mob boss, Strike s) {
      double[] height = new double[1];
      for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
         if (!isTarget(p, level) || s.hit.contains(p.getUUID()) || !p.onGround()) {
            continue;
         }
         double d = segmentDistance(p.position(), s.a, s.b, height);
         if (d > s.radius || Math.abs(p.getY() - height[0]) > 1.2) {
            continue;
         }
         s.hit.add(p.getUUID());
         p.hurtServer(level, level.damageSources().mobAttack(boss), s.damage);
         p.push(0.0, 0.35, 0.0);
         p.hurtMarked = true;
         Fx.shatter(level, ParticleTypes.ELECTRIC_SPARK, p.position().add(0.0, 0.8, 0.0), 0.6, SOLAR);
      }
   }

   /** The Eclipse lands: a star on everybody in the fight standing outside the circle. */
   private static void landEclipse(ServerLevel level, Mob boss, Fight fight, Strike s) {
      Vec3 c = s.a;
      Fx.nova(level, ParticleTypes.END_ROD, c.add(0.0, 0.2, 0.0), s.radius, STARLIGHT);
      Fx.flare(level, ParticleTypes.END_ROD, c.add(0.0, 1.4, 0.0), 2.0, SOLAR);
      // The stars land: a second of starfall over the whole field as the comets come down.
      Fx.starfall(level, ParticleTypes.END_ROD, c, 20.0, 24, SOLAR);
      level.playSound(null, c.x, c.y, c.z, SoundEvents.FIREWORK_ROCKET_LARGE_BLAST, SoundSource.HOSTILE, 2.0F, 0.6F);
      double safe2 = s.radius * s.radius;
      int hits = 0;
      for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
         if (!isTarget(p, level) || !fight.participants.contains(p.getUUID())) {
            continue;
         }
         double dx = p.getX() - c.x;
         double dz = p.getZ() - c.z;
         double d2 = dx * dx + dz * dz;
         if (d2 <= safe2 || d2 > SEEK_RANGE * SEEK_RANGE) {
            continue;
         }
         Vec3 feet = p.position();
         Fx.comet(level, ParticleTypes.END_ROD, feet.add(2.0, 18.0, 1.0), feet.add(0.0, 0.4, 0.0), 3, SOLAR);
         Fx.shockwave(level, ParticleTypes.END_ROD, feet, 2.5, NEBULA);
         level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.LIGHTNING_BOLT_IMPACT, SoundSource.HOSTILE, 1.2F, 1.4F);
         p.hurtServer(level, level.damageSources().mobAttack(boss), s.damage);
         p.hurtMarked = true;
         hits++;
      }
      // A few stars into the empty ground outside the circle, so the whole field reads as
      // dangerous even with nobody standing in it.
      for (int i = 0; i < 6; i++) {
         double a = RANDOM.nextDouble() * Math.PI * 2.0;
         double r = s.radius + 3.0 + RANDOM.nextDouble() * 10.0;
         double x = c.x + Math.cos(a) * r;
         double z = c.z + Math.sin(a) * r;
         Vec3 at = new Vec3(x, BossGrounding.groundY(level, x, z, c.y), z);
         Fx.comet(level, ParticleTypes.END_ROD, at.add(1.0, 16.0, -1.0), at.add(0.0, 0.3, 0.0), 4, NEBULA);
      }
      if (hits == 0) {
         announce(level, SAY + "\"\u00a7fHm. Close enough.\"");
      }
   }

   // --------------------------------------------------------------- judgement

   /**
    * The sixth charge, spent all at once: she stops, draws a line, and cuts along it.
    *
    * <p>Three things make it answerable rather than lethal. The line is on the ground
    * for two seconds before anything happens, and the far edge of the sweep is drawn too,
    * so the whole wedge is visible. It is a <b>ray</b>, so it stops at the first solid
    * block - a tree, a boulder, a hill, a wall somebody built - and it cannot be steered,
    * only swept across a fixed arc. And she cannot walk, blink or cast while it runs, so
    * the whole move is her standing in the open with a target painted on her.
    */
   private static void startJudgement(ServerLevel level, Mob boss, Fight fight) {
      ServerPlayer target = nearestPlayer(boss, SEEK_RANGE);
      fight.energy = 0;
      fight.interruptDamage = 0.0F;
      if (target == null) {
         // Nobody to judge. She keeps the pause and starts charging again rather than
         // firing the beam into a forest.
         fight.nextEnergy = ServerClock.clock(level) + ENERGY_INTERVAL;
         return;
      }
      fight.judgementOrigin = boss.position().add(0.0, boss.getBbHeight() * 0.65, 0.0);
      Vec3 dir = target.position().subtract(boss.position());
      double aim = dir.lengthSqr() < 1.0E-4 ? 0.0 : Math.atan2(dir.z, dir.x);
      fight.judgementFrom = aim - JUDGEMENT_SWEEP;
      fight.judgementTo = aim + JUDGEMENT_SWEEP;
      fight.judgementTell = JUDGEMENT_TELL;
      fight.judgementHits.clear();
      Fx.runeCircle(level, ParticleTypes.END_ROD, boss.position().add(0.0, 0.05, 0.0), 2.5, JUDGEMENT_TELL + JUDGEMENT_BEAM, SOLAR);
      Fx.aura(level, ParticleTypes.END_ROD, boss.position(), 2.6, JUDGEMENT_TELL, SOLAR);
      Fx.crescent(level, ParticleTypes.ENCHANT, boss.position(), new Vec3(Math.cos(aim), 0.0, Math.sin(aim)), 9.0, NEBULA);
      announce(level, SAY + "\"\u00a7cSix. \u00a7fStay off the line.\"");
      announceNear(level, boss, 72.0, "&c&l\u26a0 JUDGEMENT &8- &7step off the line, or get behind something.");
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.WARDEN_SONIC_CHARGE, SoundSource.HOSTILE, 2.0F, 0.7F);
   }

   /** The tell, then the beam. Called from the fight tick while either is running. */
   private static void tickJudgement(ServerLevel level, Mob boss, Fight fight, long now) {
      if (fight.judgementTell > 0) {
         fight.judgementTell--;
         drawJudgementLine(level, boss, fight);
         if (fight.judgementTell % 8 == 0) {
            level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.HOSTILE, 1.4F, 0.6F + (1.0F - (float)fight.judgementTell / JUDGEMENT_TELL) * 1.6F);
         }
         if (fight.judgementTell <= 0) {
            fight.judgementBeam = JUDGEMENT_BEAM;
            Vec3 hand = boss.position().add(0.0, boss.getBbHeight() * 0.65, 0.0);
            Fx.flare(level, ParticleTypes.END_ROD, hand, 2.4, SOLAR);
            Fx.shockwave(level, ParticleTypes.END_ROD, boss.position(), 6.0, STARLIGHT);
            level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.WARDEN_SONIC_BOOM, SoundSource.HOSTILE, 3.0F, 1.1F);
         }
         return;
      }

      fight.judgementBeam--;
      double progress = 1.0 - (double)fight.judgementBeam / JUDGEMENT_BEAM;
      double angle = fight.judgementFrom + (fight.judgementTo - fight.judgementFrom) * progress;
      Vec3 dir = new Vec3(Math.cos(angle), 0.0, Math.sin(angle));
      // The ray starts at her hands and is re-anchored to her every tick, so the beam
      // follows her if something moves her - it just cannot be aimed.
      Vec3 from = boss.position().add(0.0, boss.getBbHeight() * 0.65, 0.0);
      fight.judgementOrigin = from;
      double reach = beamReach(level, from, dir);
      drawBeam(level, from, dir, reach, fight.judgementBeam);

      for (ServerPlayer p : participantsNear(level, boss, JUDGEMENT_RANGE + 4.0)) {
         Vec3 rel = p.position().add(0.0, p.getBbHeight() * 0.5, 0.0).subtract(from);
         if (Math.abs(rel.y) > 2.0) {
            continue;
         }
         double along = rel.x * dir.x + rel.z * dir.z;
         if (along < 0.0 || along > reach) {
            continue;
         }
         double lateral = Math.abs(rel.x * dir.z - rel.z * dir.x);
         if (lateral > JUDGEMENT_HALF_WIDTH) {
            continue;
         }
         Long ready = fight.judgementHits.get(p.getUUID());
         if (ready != null && now < ready) {
            continue;
         }
         fight.judgementHits.put(p.getUUID(), now + JUDGEMENT_HIT_COOLDOWN);
         p.hurtServer(level, level.damageSources().mobAttack(boss), JUDGEMENT_DAMAGE);
         // Pushed along the beam, not away from her: the sweep carries you with it,
         // which is what makes standing in it feel like being swept rather than hit.
         p.push(dir.x * 0.9, 0.18, dir.z * 0.9);
         p.hurtMarked = true;
         Fx.clash(level, ParticleTypes.CRIT, p.position().add(0.0, 1.0, 0.0), dir, SOLAR);
      }

      if (fight.judgementBeam <= 0) {
         fight.judgementHits.clear();
         // Spent: every charge is gone, and the pause before she can start gathering
         // again is twice her usual interval.
         fight.nextEnergy = now + ENERGY_INTERVAL * 2L;
         Fx.shatter(level, ParticleTypes.END_ROD, from, 1.2, STARLIGHT);
         announceNear(level, boss, 72.0, "&8The line goes out. &7She's empty. Hit her.");
         level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.BEACON_DEACTIVATE, SoundSource.HOSTILE, 2.0F, 1.5F);
      }
   }

   /**
    * The wedge she is about to sweep, drawn on the ground while the tell runs: the edge
    * the beam starts on in bright light (gold once it is close to firing), the edge it
    * ends on in violet. Redrawn every four ticks; each line stops where the beam will.
    */
   private static void drawJudgementLine(ServerLevel level, Mob boss, Fight fight) {
      if (fight.judgementTell % 4 != 0) {
         return;
      }
      Vec3 hand = boss.position().add(0.0, boss.getBbHeight() * 0.65, 0.0);
      Vec3 floor = boss.position().add(0.0, 0.15, 0.0);
      double heat = 1.0 - (double)fight.judgementTell / JUDGEMENT_TELL;
      Vec3 start = new Vec3(Math.cos(fight.judgementFrom), 0.0, Math.sin(fight.judgementFrom));
      Vec3 end = new Vec3(Math.cos(fight.judgementTo), 0.0, Math.sin(fight.judgementTo));
      Fx.beam(level, ParticleTypes.END_ROD, floor, floor.add(start.scale(beamReach(level, hand, start))), heat > 0.5 ? SOLAR : STARLIGHT);
      Fx.beam(level, ParticleTypes.ENCHANT, floor, floor.add(end.scale(beamReach(level, hand, end))), NEBULA);
   }

   /**
    * How far the beam gets before something solid stops it.
    *
    * <p>The blocking check is the whole reason the beam is fair: the reach it returns
    * is the reach the damage uses, so cover is real cover rather than a visual effect
    * on top of an attack that still hits through a hill. Grass and flowers are not
    * cover; it stops on anything with a collision box.
    */
   private static double beamReach(ServerLevel level, Vec3 from, Vec3 dir) {
      double reach = 0.0;
      for (double d = 0.0; d < JUDGEMENT_RANGE; d += 0.4) {
         if (solid(level, from.add(dir.scale(d)))) {
            break;
         }
         reach = d;
      }
      return reach;
   }

   /** The beam itself, every other tick, with sparks where it meets cover. */
   private static void drawBeam(ServerLevel level, Vec3 from, Vec3 dir, double reach, int tick) {
      if (tick % 2 != 0) {
         return;
      }
      Vec3 end = from.add(dir.scale(reach));
      Fx.beam(level, ParticleTypes.END_ROD, from, end, SOLAR);
      if (reach < JUDGEMENT_RANGE - 0.5 && tick % 4 == 0) {
         Fx.clash(level, ParticleTypes.CRIT, end, dir.scale(-1.0), STARLIGHT);
      }
   }

   // ------------------------------------------------------------------ the well

   /**
    * Gravity Well: a vortex opens on the floor under her target and drags everyone near it
    * inward for three and a half seconds, then implodes. The pull comes in pulses that a
    * sprint out-runs and a walk barely does, and the rune circle is the blast radius.
    */
   private static void startWell(ServerLevel level, Mob boss, Fight fight, ServerPlayer target) {
      fight.wellCenter = new Vec3(target.getX(), BossGrounding.groundY(level, target.getX(), target.getZ(), target.getY()), target.getZ());
      fight.wellTicks = 70;
      Fx.vortex(level, ParticleTypes.REVERSE_PORTAL, fight.wellCenter, 6.0, fight.wellTicks, NEBULA);
      Fx.runeCircle(level, ParticleTypes.ENCHANT, fight.wellCenter.add(0.0, 0.05, 0.0), WELL_BLAST_RADIUS, fight.wellTicks, NEBULA);
      announce(level, SAY + "\"\u00a7fCome here.\"");
      announceNear(level, boss, 64.0, "&5Gravity Well &8- &7sprint out of the ring.");
      level.playSound(null, fight.wellCenter.x, fight.wellCenter.y, fight.wellCenter.z, SoundEvents.ENDER_DRAGON_FLAP, SoundSource.HOSTILE, 1.2F, 0.7F);
   }

   private static void tickWell(ServerLevel level, Mob boss, Fight fight) {
      fight.wellTicks--;
      Vec3 c = fight.wellCenter;
      // The vortex sent at the cast draws the well for modded clients; this is the vanilla one.
      if (fight.wellTicks % 4 == 0) {
         Fx.vanilla(level, ParticleTypes.PORTAL, c.x, c.y + 1.0, c.z, 8, 1.2, 1.2, 1.2, 0.1);
      }

      // Pulled in pulses, and the pull is set rather than added. It used to be added every
      // tick to a velocity the server never decays for a player, so it stacked into a yank
      // nobody could walk against - and it measured range from her, not from the well.
      if (fight.wellTicks % 5 == 0) {
         level.playSound(null, c.x, c.y, c.z, SoundEvents.WARDEN_HEARTBEAT, SoundSource.HOSTILE, 1.0F, 0.6F);
         for (ServerPlayer p : playersNear(level, c.x, c.y, c.z, WELL_PULL_RANGE)) {
            Vec3 pull = c.add(0.0, 1.0, 0.0).subtract(p.position());
            double len = Math.sqrt(pull.x * pull.x + pull.z * pull.z);
            if (len < 1.5) {
               continue;
            }
            p.setDeltaMovement(pull.x / len * 0.32, Math.min(p.getDeltaMovement().y, 0.05), pull.z / len * 0.32);
            p.hurtMarked = true;
            p.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 20, 0, false, true, true));
         }
      }
      if (fight.wellTicks <= 0) {
         Fx.nova(level, ParticleTypes.REVERSE_PORTAL, c.add(0.0, 0.5, 0.0), WELL_BLAST_RADIUS, NEBULA);
         Fx.shockwave(level, ParticleTypes.END_ROD, c, WELL_BLAST_RADIUS + 1.0, STARLIGHT);
         Fx.flare(level, ParticleTypes.END_ROD, c.add(0.0, 1.0, 0.0), 2.0, NEBULA);
         Fx.vanilla(level, ParticleTypes.EXPLOSION_EMITTER, c.x, c.y + 0.5, c.z, 1, 0.0, 0.0, 0.0, 0.0);
         level.playSound(null, c.x, c.y, c.z, SoundEvents.GENERIC_EXPLODE, SoundSource.HOSTILE, 1.6F, 0.8F);
         for (ServerPlayer p : playersNear(level, c.x, c.y, c.z, WELL_BLAST_RADIUS)) {
            p.hurtServer(level, level.damageSources().mobAttack(boss), WELL_DAMAGE);
         }
      }
   }

   // ---------------------------------------------------------------- collapse

   /**
    * Her last spell: a long telegraph you have to walk out of, then an opening. The ring
    * on the floor is where it lands; the circle closing round her is the timer. When it
    * lands she is spent for four seconds and does nothing at all.
    */
   private static void startCollapse(ServerLevel level, Mob boss, Fight fight, ServerPlayer target) {
      fight.collapseCharge = 60;
      Vec3 at = new Vec3(target.getX(), BossGrounding.groundY(level, target.getX(), target.getZ(), target.getY()), target.getZ());
      addMark(level, fight, new Mark(at, 6.5, COLLAPSE_DAMAGE, 0xFFE08A, 60));
      Fx.summonCircle(level, ParticleTypes.END_ROD, boss.position().add(0.0, 0.05, 0.0), 3.0, 60, SOLAR);
      Fx.spiral(level, ParticleTypes.END_ROD, boss.position(), 6.0, 60, STARLIGHT);
      Fx.flare(level, ParticleTypes.END_ROD, boss.position().add(0.0, 1.4, 0.0), 1.6, SOLAR);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.BEACON_ACTIVATE, SoundSource.HOSTILE, 2.0F, 0.6F);
      announce(level, SAY + "\"\u00a7fLast spell. \u00a7bRun.\"");
      announceNear(level, boss, 72.0, "&e\u2604 Celestial Collapse &8- &7get out of the big ring.");
   }

   private static void tickCollapse(ServerLevel level, Mob boss, Fight fight, long now) {
      fight.collapseCharge--;
      // The turning ring is the vanilla clients' timer (the summon circle and spiral sent at the
      // cast are the modded one). Every fourth tick and twelve points: it used to be twenty
      // points every other tick, six hundred packets a cast for a Bedrock player through Geyser.
      if (fight.collapseCharge % 4 == 0) {
         double r = 2.0 + (60 - fight.collapseCharge) * 0.1;
         Fx.vanillaOnly(() -> {
            for (int i = 0; i < 12; i++) {
               double a = i * (Math.PI * 2.0 / 12.0) + fight.collapseCharge * 0.06;
               Fx.vanilla(level, ParticleTypes.END_ROD, boss.getX() + Math.cos(a) * r, boss.getY() + 1.2, boss.getZ() + Math.sin(a) * r, 1, 0.0, 0.0, 0.0, 0.0);
            }
         });
      }
      if (fight.collapseCharge % 10 == 0 && fight.collapseCharge > 0) {
         Fx.ring(level, ParticleTypes.END_ROD, boss.position().add(0.0, 1.2, 0.0), 2.0 + (60 - fight.collapseCharge) * 0.1, SOLAR);
      }
      boss.addEffect(new MobEffectInstance(MobEffects.GLOWING, 8, 0, false, false, false));
      if (fight.collapseCharge > 0) {
         return;
      }
      // Landing the spell empties her completely: the cost of her best move is
      // the window you get to punish it. Weakness and Slowness did nothing to a puppet
      // that never swings and is moved by hand, so the window is a real stop instead.
      fight.spentUntil = now + EXHAUSTED_TICKS;
      fight.nextBlink = Math.max(fight.nextBlink, fight.spentUntil + 20L);
      boss.addEffect(new MobEffectInstance(MobEffects.GLOWING, EXHAUSTED_TICKS, 0, false, false, false));
      Fx.aura(level, ParticleTypes.ENCHANT, boss.position(), 2.4, EXHAUSTED_TICKS, NEBULA);
      Fx.shatter(level, ParticleTypes.END_ROD, boss.position().add(0.0, 1.2, 0.0), 1.2, STARLIGHT);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.RESPAWN_ANCHOR_DEPLETE.value(), SoundSource.HOSTILE, 1.6F, 1.0F);
      announceNear(level, boss, 64.0, "&8She's spent. &7Hit her now.");
   }

   // --------------------------------------------------------------------- phases

   private static void enterPhase(ServerLevel level, Mob boss, Fight fight, int phase) {
      Fx.starTrail(level, ParticleTypes.END_ROD, boss.position(), 7.0, 30, STARLIGHT);
      Fx.starfall(level, ParticleTypes.END_ROD, boss.position(), 10.0, 40, SOLAR);
      fight.phase = phase;
      long now = ServerClock.clock(level);
      Vec3 core = boss.position().add(0.0, 1.4, 0.0);
      if (phase == 2) {
         announce(level, SAY + "\"\u00a7fGood. Now I try.\"");
         announceNear(level, boss, 72.0, "&b&lOVERCHARGE &8- &7she charges stars. Hit her to break them.");
         fight.nextEnergy = now + 40L;
         fight.nextEclipse = now + 140L;
         Fx.runeCircle(level, ParticleTypes.ENCHANT, boss.position().add(0.0, 0.05, 0.0), 8.0, 60, NEBULA);
         Fx.flare(level, ParticleTypes.END_ROD, core, 2.4, STARLIGHT);
         Fx.shockwave(level, ParticleTypes.END_ROD, boss.position(), 9.0, NEBULA);
      } else {
         announce(level, SAY + "\"\u00a7bFine. All of the sky, then.\"");
         announceNear(level, boss, 72.0, "&b&lSTARFALL &8- &7the sky is falling. Keep moving.");
         fight.energy = MAX_ENERGY;
         fight.nextWell = Math.max(fight.nextWell, now + 120L);
         fight.nextCollapse = Math.max(fight.nextCollapse, now + 200L);
         // The phase is named for it: the whole sky round her starts coming down.
         Fx.starfall(level, ParticleTypes.END_ROD, boss.position(), 16.0, 100, SOLAR);
         Fx.starburst(level, ParticleTypes.END_ROD, core, 8.0, SOLAR);
         Fx.pillar(level, ParticleTypes.END_ROD, boss.position(), 24.0, STARLIGHT);
      }
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.BEACON_ACTIVATE, SoundSource.HOSTILE, 2.0F, 1.4F);
   }

   private static void taunt(ServerLevel level, Mob boss, Fight fight) {
      String line = switch (fight.phase) {
         case 1 -> RANDOM.nextBoolean() ? "You look small from up here." : "Keep up.";
         case 2 -> RANDOM.nextBoolean() ? "I'm filling up." : "Count with me.";
         default -> RANDOM.nextBoolean() ? "Every wish you made? Mine." : "Still standing. Huh.";
      };
      // The quotes used to be doubled - curly quotes inside straight ones.
      announce(level, SAY + "\"\u00a7f" + line + "\"");
   }

   // ------------------------------------------------------------------ projectiles

   private static Vec3 boltOrigin(Mob boss, int index, int count) {
      double spread = (index - (count - 1) / 2.0) * 0.45;
      Vec3 side = new Vec3(Math.cos(spread), 0.0, Math.sin(spread));
      return boss.position().add(side.x * 0.9, boss.getBbHeight() * 0.65, side.z * 0.9);
   }

   /** Moves every bolt, draws its trail, and resolves impacts. */
   private static void tickBolts(ServerLevel level, Fight fight, Mob boss) {
      if (fight.bolts.isEmpty()) {
         return;
      }
      for (Iterator<Bolt> it = fight.bolts.iterator(); it.hasNext();) {
         Bolt bolt = it.next();
         // The trail. Modded clients get one glowing beam segment over this tick's path in
         // the bolt's own colour; everyone else one mote, and a glow every other tick. It used
         // to be a vanilla mote on every sub-step to everyone: an Astral Burst at six charges
         // was over a hundred particle packets a tick, drawn on top of the modded trail.
         Vec3 start = bolt.pos;
         Vec3 end = bolt.pos.add(bolt.vel);
         com.fortuneandfavors.net.FfVfx.shape(level, com.fortuneandfavors.net.FfVfx.BEAM, ParticleTypes.END_ROD, start, end, 0.0, 0.0, bolt.hue);
         Fx.vanilla(level, ParticleTypes.END_ROD, start.x, start.y, start.z, 1, 0.02, 0.02, 0.02, 0.0);
         if ((bolt.life & 1) == 0) {
            Fx.vanilla(level, ParticleTypes.GLOW, end.x, end.y, end.z, 1, 0.02, 0.02, 0.02, 0.0);
         }
         // Deliberately a fast, small step: at 0.85 blocks a tick a bolt can
         // otherwise tunnel through a player standing still.
         int steps = 4;
         boolean done = false;
         for (int s = 0; s < steps && !done; s++) {
            bolt.pos = bolt.pos.add(bolt.vel.scale(1.0 / steps));
            // Stopped by anything with a collision box - not by tall grass or flowers,
            // which used to eat every bolt fired across a meadow.
            if (solid(level, bolt.pos)) {
               Fx.clash(level, ParticleTypes.END_ROD, bolt.pos, bolt.vel.scale(-1.0), bolt.hue);
               level.playSound(null, bolt.pos.x, bolt.pos.y, bolt.pos.z, SoundEvents.AMETHYST_BLOCK_HIT, SoundSource.HOSTILE, 0.6F, 1.6F);
               done = true;
               break;
            }
            for (ServerPlayer p : playersNear(level, bolt.pos.x, bolt.pos.y, bolt.pos.z, 3.0)) {
               // Against the body, not the feet: the old 1.1-block sphere round the feet
               // missed bolts through the chest and hit ones under the boots.
               if (!p.getBoundingBox().inflate(0.3).contains(bolt.pos)) {
                  continue;
               }
               p.hurtServer(level, level.damageSources().mobAttack(boss), bolt.damage);
               Fx.shatter(level, ParticleTypes.END_ROD, bolt.pos, 0.5, bolt.hue);
               done = true;
               break;
            }
         }
         bolt.life--;
         if (done || bolt.life <= 0) {
            it.remove();
         }
      }
   }

   /** Runs every mark's fuse, drops the star in the last few ticks, and lands it. */
   private static void tickMarks(ServerLevel level, Fight fight, Mob boss) {
      if (fight.marks.isEmpty()) {
         return;
      }
      for (Iterator<Mark> it = fight.marks.iterator(); it.hasNext();) {
         Mark mark = it.next();
         mark.fuse--;
         if (mark.fuse > 0) {
            if (!mark.falling && mark.fuse <= 6) {
               // The star is seen falling for the last few ticks, so the landing is never a surprise.
               mark.falling = true;
               Fx.comet(level, ParticleTypes.END_ROD, mark.pos.add(3.0, 18.0, 2.0), mark.pos.add(0.0, 0.4, 0.0), 6, mark.hue);
            }
            if (mark.fuse % 4 == 0) {
               // The rune circle and starfall cover modded clients; this ring is for everyone
               // else - and only for them now (a bare sendParticles in the old enter/exit scope
               // reached modded clients as well), at twelve points every fourth tick.
               Fx.vanillaOnly(() -> {
                  int points = 12;
                  for (int i = 0; i < points; i++) {
                     double a = i * (Math.PI * 2.0 / points) + mark.fuse * 0.08;
                     Fx.vanilla(level, ParticleTypes.END_ROD, mark.pos.x + Math.cos(a) * mark.radius, mark.pos.y + 0.15, mark.pos.z + Math.sin(a) * mark.radius, 1, 0.0, 0.0, 0.0, 0.0);
                  }
               });
            }
            continue;
         }
         it.remove();
         Fx.shockwave(level, ParticleTypes.END_ROD, mark.pos, mark.radius + 1.0, mark.hue);
         Fx.flare(level, ParticleTypes.END_ROD, mark.pos.add(0.0, 0.8, 0.0), mark.radius * 0.5, SOLAR);
         if (mark.radius > 5.0) {
            Fx.starburst(level, ParticleTypes.END_ROD, mark.pos.add(0.0, 1.0, 0.0), mark.radius, STARLIGHT);
         }
         Fx.vanillaOnly(() -> {
            Fx.vanilla(level, ParticleTypes.EXPLOSION_EMITTER, mark.pos.x, mark.pos.y + 0.4, mark.pos.z, 1, 0.0, 0.0, 0.0, 0.0);
            Fx.vanilla(level, ParticleTypes.GUST, mark.pos.x, mark.pos.y + 0.4, mark.pos.z, 8, mark.radius * 0.6, 0.4, mark.radius * 0.6, 0.15);
         });
         level.playSound(null, mark.pos.x, mark.pos.y, mark.pos.z, ModSounds.BOSS_SLAM, SoundSource.HOSTILE, 1.8F, 1.0F);
         for (ServerPlayer p : playersNear(level, mark.pos.x, mark.pos.y, mark.pos.z, mark.radius)) {
            p.hurtServer(level, level.damageSources().mobAttack(boss), mark.damage);
            p.push(0.0, 0.6, 0.0);
            p.hurtMarked = true;
         }
      }
   }

   // -------------------------------------------------------------- lethal blow

   /**
    * The killing blow is cancelled and the death ceremony starts: every spell in flight is
    * dropped, the field goes quiet, and she lifts off the ground while stars come down
    * round her, then goes out in one flash.
    */
   public static Boolean onLethalDamage(Entity entity, float amount) {
      if (!isMagister(entity) || !(entity instanceof Mob boss) || !(boss.level() instanceof ServerLevel level)) {
         return null;
      }
      Fight fight = FIGHTS.get(boss.getUUID());
      if (fight == null) {
         return null;
      }
      if (fight.dying || fight.arrivalTicks > 0) {
         return Boolean.FALSE;
      }
      if (boss.getHealth() - amount > 0.5F) {
         return null;
      }
      fight.dying = true;
      fight.deathTicks = DEATH_CEREMONY_TICKS;
      boss.setHealth(1.0F);
      boss.setNoAi(true);
      boss.setInvulnerable(true);
      fight.bar.setProgress(0.0F);
      fight.clearSpells();
      fight.spentUntil = 0L;
      Vec3 at = boss.position();
      Fx.spiral(level, ParticleTypes.END_ROD, at, 10.0, DEATH_CEREMONY_TICKS, STARLIGHT);
      Fx.aura(level, ParticleTypes.END_ROD, at, 3.0, DEATH_CEREMONY_TICKS, SOLAR);
      Fx.runeCircle(level, ParticleTypes.ENCHANT, at.add(0.0, 0.05, 0.0), 5.0, DEATH_CEREMONY_TICKS, NEBULA);
      // Her own sky comes down round her for the whole ceremony.
      Fx.starfall(level, ParticleTypes.END_ROD, at, 9.0, DEATH_CEREMONY_TICKS, NEBULA);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.BEACON_DEACTIVATE, SoundSource.HOSTILE, 2.0F, 1.4F);
      announce(level, SAY + "\"\u00a7f...oh. \u00a7bYou looked up.\"");
      // FALSE cancels the blow (Fabric: true means "allow the damage"). Returning
      // TRUE here let the killing hit land, which ran vanilla's die() immediately,
      // skipped the whole ceremony and - because grantLoot only runs from
      // tickDeath - meant she dropped literally nothing.
      return Boolean.FALSE;
   }

   /**
    * The ceremony. She drifts up off the ground, stars fall round her and she cracks a
    * little more each time; in the last second the light pulls into her, and then it all
    * goes at once - a flash, a starburst, a shockwave, a column of light to the sky and a
    * slow fall of starlight afterwards.
    */
   private static void tickDeath(MinecraftServer server, Mob boss, Fight fight) {
      ServerLevel level = (ServerLevel) boss.level();
      fight.deathTicks--;
      double progress = 1.0 - (double) fight.deathTicks / DEATH_CEREMONY_TICKS;
      if (fight.deathTicks > 15) {
         boss.setPos(boss.getX(), boss.getY() + 0.03, boss.getZ());
         boss.hurtMarked = true;
      }
      // The spiral, aura and starfall sent when the ceremony began carry it for modded clients.
      if ((fight.deathTicks & 1) == 0) {
         Fx.vanilla(level, ParticleTypes.END_ROD, boss.getX(), boss.getY() + 1.4, boss.getZ(), 4, 1.2, 1.0, 1.2, 0.08);
      }
      Vec3 heart = boss.position().add(0.0, 1.2, 0.0);
      if (fight.deathTicks > 15 && fight.deathTicks % 12 == 0) {
         // A star comes down somewhere close, and she cracks a little more.
         double a = RANDOM.nextDouble() * Math.PI * 2.0;
         double r = 3.0 + RANDOM.nextDouble() * 6.0;
         double x = boss.getX() + Math.cos(a) * r;
         double z = boss.getZ() + Math.sin(a) * r;
         Vec3 ground = new Vec3(x, BossGrounding.groundY(level, x, z, boss.getY()), z);
         Fx.comet(level, ParticleTypes.END_ROD, ground.add(2.0, 16.0, 1.0), ground.add(0.0, 0.3, 0.0), 5, RANDOM.nextBoolean() ? SOLAR : NEBULA);
         Fx.shatter(level, ParticleTypes.END_ROD, heart, 0.6 + progress, STARLIGHT);
         level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.AMETHYST_BLOCK_HIT, SoundSource.HOSTILE, 1.4F, 0.6F + (float) progress);
      }
      if (fight.deathTicks == 60) {
         announce(level, SAY + "\"\u00a7fWait. I'm not done-\"");
      } else if (fight.deathTicks == 30) {
         announce(level, SAY + "\"\u00a7fIt's so quiet up there.\"");
      } else if (fight.deathTicks == 15) {
         // The last second: the light pulls back in before it goes.
         Fx.vortex(level, ParticleTypes.REVERSE_PORTAL, boss.position(), 5.0, 15, NEBULA);
         level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.WARDEN_SONIC_CHARGE, SoundSource.HOSTILE, 2.0F, 1.3F);
      }
      if (fight.deathTicks > 0) {
         return;
      }
      Vec3 base = new Vec3(boss.getX(), BossGrounding.groundY(level, boss.getX(), boss.getZ(), boss.getY()), boss.getZ());
      Fx.flare(level, ParticleTypes.END_ROD, heart, 4.0, SOLAR);
      Fx.starburst(level, ParticleTypes.END_ROD, heart, 9.0, STARLIGHT);
      Fx.shockwave(level, ParticleTypes.END_ROD, base, 14.0, NEBULA);
      Fx.nova(level, ParticleTypes.FIREWORK, heart, 6.0, STARLIGHT);
      Fx.pillar(level, ParticleTypes.END_ROD, base, 32.0, STARLIGHT);
      // She goes out like a star: the light breaks into shards, and what is left of her sky
      // keeps falling over the spot for four seconds after.
      Fx.gemShards(level, ParticleTypes.END_ROD, heart, 2.4, SOLAR);
      Fx.starfall(level, ParticleTypes.END_ROD, base, 12.0, 80, STARLIGHT);
      Fx.vanilla(level, ParticleTypes.EXPLOSION_EMITTER, boss.getX(), boss.getY() + 1.0, boss.getZ(), 3, 1.5, 1.0, 1.5, 0.0);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), ModSounds.BOSS_DEATH, SoundSource.HOSTILE, 2.0F, 1.3F);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.FIREWORK_ROCKET_LARGE_BLAST, SoundSource.HOSTILE, 2.0F, 0.7F);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.FIREWORK_ROCKET_TWINKLE, SoundSource.HOSTILE, 2.0F, 1.0F);
      announce(level, "\u00a7b\u00a7lThe Magister \u00a7r\u00a77goes out like a star.");
      boss.setNoAi(false);
      boss.setInvulnerable(false);
      onBossDeath(level, boss);
      boss.hurtServer(level, level.damageSources().generic(), boss.getMaxHealth() * 4.0F + 100.0F);
      if (boss.isAlive()) {
         // Loot is already paid and the fight released; never leave a body behind.
         boss.discard();
      }
   }

   // --------------------------------------------------------------------- loot

   private static void grantLoot(ServerLevel level, Mob boss, Fight fight) {
      drop(level, boss, ModItems.starboundTrophy());
      // Forge material only - the amethyst, lapis and pearls that used to fall
      // alongside it were there to make the pile look bigger, and a boss that
      // empties its own crafting set is a boss you only need to kill once.
      int essence = 2 + RANDOM.nextInt(3);
      for (int i = 0; i < essence; i++) {
         drop(level, boss, ModItems.magicalEssence());
      }
      // Starfall is hers: the same star she calls down, handed to the player.
      if (RANDOM.nextFloat() < 0.15F) {
         drop(level, boss, CustomEnchantments.tome(CustomEnchantments.STARFALL, 1 + RANDOM.nextInt(3)));
      }

      // One roll at one legendary. Three of them, on every kill, is a dump.
      if (RANDOM.nextFloat() < 0.2F) {
         drop(level, boss, switch (RANDOM.nextInt(3)) {
            case 0 -> ModItems.starpiercer();
            case 1 -> ModItems.astralMantle();
            default -> ModItems.magistersCodex();
         });
      }

      BossPayout.payBoxes(level, fight.participants, ModItems::starboundLootBox, BossPayout.BOXES_PER_KILL, "\u00a7bStarbound Loot Box");
      for (UUID id : fight.participants) {
         ServerPlayer p = level.getServer().getPlayerList().getPlayer(id);
         if (p != null && p.isAlive()) {
            Advancements.grant(p, "kill_magister");
         }
      }
      announce(level, "\u00a73\u00a7m\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550");
      announce(level, "    \u00a7b\u00a7l\u2728 THE MAGISTER FALLS SILENT \u2728");
      announce(level, "\u00a73\u00a7m\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550");
   }

   private static void drop(ServerLevel level, Mob boss, ItemStack stack) {
      if (stack != null && !stack.isEmpty()) {
         level.addFreshEntity(new ItemEntity(level, boss.getX(), boss.getY() + 0.6, boss.getZ(), stack));
      }
   }

   // ------------------------------------------------------------------ teardown

   private static void release(MinecraftServer server, Fight fight) {
      fight.clearSpells();
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

   /**
    * A wormhole that opens ({@code open}) or seals. Sent as the raw cue rather than through
    * {@code Fx.wormhole}, whose flag runs the wrong way round (its "closing" draws an opening):
    * every exit of hers used to be drawn as a hole opening behind her.
    */
   private static void wormhole(ServerLevel level, Vec3 at, boolean open, int color) {
      Fx.shape(level, com.fortuneandfavors.net.FfVfx.WORMHOLE, ParticleTypes.REVERSE_PORTAL, at, Vec3.ZERO, 0.0, open ? 1.0 : 0.0, color);
   }

   private static int hue(int energy) {
      int[] hues = {0x88DDFF, 0x99CCFF, 0xAABBFF, 0xBBA0FF, 0xCC99FF, 0xDD88FF, 0xFFCC66};
      return hues[Math.max(0, Math.min(hues.length - 1, energy))];
   }

   /** Anything with a collision box at this point - the test bolts, beams and the arrival use. */
   private static boolean solid(ServerLevel level, Vec3 at) {
      BlockPos pos = BlockPos.containing(at);
      return !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
   }

   /**
    * Horizontal distance from a point to a segment. {@code heightOut[0]} receives the
    * segment's height at the nearest point, so a caller can check the point is on it and
    * not a floor above or below.
    */
   private static double segmentDistance(Vec3 p, Vec3 a, Vec3 b, double[] heightOut) {
      double dx = b.x - a.x;
      double dz = b.z - a.z;
      double len2 = dx * dx + dz * dz;
      double t = len2 < 1.0E-6 ? 0.0 : ((p.x - a.x) * dx + (p.z - a.z) * dz) / len2;
      t = Math.max(0.0, Math.min(1.0, t));
      double nx = a.x + dx * t;
      double nz = a.z + dz * t;
      heightOut[0] = a.y + (b.y - a.y) * t;
      double ox = p.x - nx;
      double oz = p.z - nz;
      return Math.sqrt(ox * ox + oz * oz);
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

   /** Somebody her spells may hit: alive, in her dimension, playing, and a real player. */
   private static boolean isTarget(ServerPlayer p, ServerLevel level) {
      return p != null && p.level() == level && p.isAlive() && !p.isCreative() && !p.isSpectator() && !BossManager.isFakePlayer(p);
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
      if (!BossChat.allowed("starbound", message)) {
         return;
      }
      for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
         Chat.raw(p, message);
      }
   }

   private static void announceNear(ServerLevel level, Mob boss, double range, String message) {
      if (!BossChat.allowed("starbound", message)) {
         return;
      }
      double r2 = range * range;
      for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
         if (p.level() == boss.level() && p.distanceToSqr(boss) <= r2) {
            Chat.raw(p, message);
         }
      }
   }

   /** Test hook: energy needed to spend on a Judgement. */
   public static int maxEnergy() {
      return MAX_ENERGY;
   }

   /** Test hook: how long the beam burns once the sixth charge is spent. */
   public static int judgementTicks() {
      return JUDGEMENT_BEAM;
   }

   /**
    * Test hook: how long the line is on the ground before the beam fires.
    *
    * <p>This number is the difference between a mechanic and an ambush. It is
    * deliberately close to the beam's own length, and the suite pins that: a tell
    * shorter than a player's reaction time would make the sixth charge unfair rather
    * than hard, and the beam would still be reachable by every tuning pass after this
    * one.
    */
   public static int judgementTellTicks() {
      return JUDGEMENT_TELL;
   }

   /** Test hook: how far the beam reaches before something stops it. */
   public static double judgementRange() {
      return JUDGEMENT_RANGE;
   }

   /** Test hook: her health, so no future tuning can quietly make her unkillable. */
   public static double maxHealth() {
      return MAX_HEALTH;
   }
}
