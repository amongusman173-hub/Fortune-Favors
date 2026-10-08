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
 * Two more run on their own cooldowns beside the rotation, both marked on the floor before they
 * land: <b>Shear</b> (a lane of wind cut through the arena - step sideways out of it) from the
 * start, and <b>Eye of the Storm</b> (everything outside a calm ring around him is hit - get close)
 * from phase two.
 *
 * <h2>He says nothing</h2>
 * He is the one boss in the mod who never sends a chat line - not a summon banner, not a taunt,
 * not a line when a phase turns over. The action bar names each move as it starts, with one grey
 * line of narrator on how to answer it, because that is the fight telling you what is about to
 * happen rather than the boss talking about it. {@code ffAuditSources} fails the build if a single
 * chat sender comes back into this file.
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

   /**
    * The arrival: how long the wind takes to gather into him. He grows from a wisp to full size
    * over this window, invulnerable and still, so nobody's first hit lands on a half-formed boss.
    */
   private static final int RISE_TICKS = 60;
   private static final double RISE_SCALE_FROM = 0.4;
   private static final double FULL_SCALE = 2.4;

   /**
    * The two backstops on being thrown. A body may not travel faster sideways than
    * {@link #MAX_FLING} blocks a tick (Momentum Theft could bank four and a half, which is out of
    * any arena), and a body he has kept in the air may not build more than {@link #FALL_CUSHION}
    * blocks of fall - the wind catches you, so a throw costs position and not a death to gravity.
    */
   private static final double MAX_FLING = 2.0;
   private static final float FALL_CUSHION = 10.0F;
   private static final int CUSHION_AFTER_AIR = 10;

   /** Tornadoes are slower than a walking player, so walking around one always works. */
   private static final double TORNADO_SPEED = 0.12;
   private static final double TORNADO_LEASH = 18.0;

   /**
    * Shear: a lane of wind is drawn on the floor from him through a fighter, and a beat and a half
    * later a blade of air runs down it. The answer is a sidestep - the lane is narrow and long, so
    * backing away down it does nothing. Two lanes in phase two, and in phase three each fighter
    * also gets a crossing lane, so the sidestep has to pick a corner.
    */
   private static final int SHEAR_COOLDOWN = 260;
   private static final int SHEAR_WARN = 32;
   private static final double SHEAR_LENGTH = 30.0;
   private static final double SHEAR_HALF_WIDTH = 1.8;
   private static final float SHEAR_DAMAGE = 11.0F;
   private static final double SHEAR_PUSH = 1.0;

   /**
    * Eye of the Storm: he plants himself and a calm ring opens around him while a wall of storm
    * turns outside it. When it closes, everything between the ring and the wall is hit. The answer
    * is the one his other moves punish - walk <em>in</em>, right up to him.
    */
   private static final int EYE_COOLDOWN = 420;
   private static final int EYE_WARN = 50;
   private static final double EYE_SAFE = 6.5;
   private static final double EYE_REACH = 26.0;
   private static final float EYE_DAMAGE = 14.0F;
   private static final double EYE_PUSH = 0.9;

   /** No fight keeps more than this many marked blows, however the cooldowns line up. */
   private static final int MAX_STRIKES = 16;
   /** No fight keeps more than this many lingering winds. */
   private static final int MAX_PENDING = 48;

   /** His colours, for the client effects: the white of the gust, the blue of the sky, the storm. */
   private static final int GALE_WHITE = 0xE6F4FF;
   private static final int SKY = 0x8FD3FF;
   private static final int STORM = 0x4A6FA5;

   private static final Random RANDOM = new Random();

   /** A delayed effect: a place, a fuse, and - for the lingering ones - a life. */
   private static final class Pending {
      final String kind;
      Vec3 pos;
      /** Not final: a tornado is re-steered every {@link #TORNADO_WANDER_TICKS}. */
      Vec3 drift;
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

   /**
    * A blow marked on the floor that has not landed yet: a Shear lane or the Eye's storm wall.
    * Marked first and landed later, so every one of them is a warning before it is a hit.
    */
   private static final class Strike {
      static final int SHEAR = 0;
      static final int EYE = 1;
      final int kind;
      /** The lane's start, or the eye's centre - on the floor either way. */
      final Vec3 at;
      /** The lane's direction (flat, unit length); zero for the eye. */
      final Vec3 dir;
      final long landAt;
      long nextDraw;
      boolean falling;

      Strike(int kind, Vec3 at, Vec3 dir, long landAt) {
         this.kind = kind;
         this.at = at;
         this.dir = dir;
         this.landAt = landAt;
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
      /** The marked blows (Shear, Eye of the Storm), and when each may next be cast. */
      final List<Strike> strikes = new ArrayList<>();
      long nextShear;
      long nextEye;
      long nextStorm;
      /** Ticks he stands planted for a wind-up, taking no other turn. */
      int hold;
      /** Ticks left of the arrival; the fight proper starts at zero. */
      int rise = RISE_TICKS;
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
            return "He's already on you. Finish that one.";
         }
      }

      ServerLevel level = summoner.level();
      Mob boss = (Mob)EntityTypes.BREEZE.create(level, EntitySpawnReason.COMMAND);
      if (boss == null) {
         return "The air didn't answer.";
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
      // He arrives as a wisp and grows into it: see tickRise.
      AttributeInstance scale = boss.getAttribute(Attributes.SCALE);
      if (scale != null) {
         scale.setBaseValue(RISE_SCALE_FROM);
      }
      attribute(boss, Attributes.MOVEMENT_SPEED, 0.28);
      attribute(boss, Attributes.ATTACK_DAMAGE, 12.0);
      attribute(boss, Attributes.FLYING_SPEED, 0.6);

      boss.setPersistenceRequired();
      boss.setCustomName(Component.literal(BOSS_NAME));
      boss.setCustomNameVisible(true);
      // Held still and untouchable for the arrival; tickRise hands him back his AI at the end.
      boss.setNoAi(true);
      boss.setInvulnerable(true);
      boss.setNoGravity(false);
      boss.addTag(TAG);
      BossManager.markBoss(boss);
      Vec3 spot = arrivalSpot(level, summoner);
      boss.setPos(spot.x, spot.y, spot.z);
      level.addFreshEntity(boss);

      ServerBossEvent bar = new ServerBossEvent(
         UUID.randomUUID(), Component.literal(barName(1)), BossBarColor.WHITE, BossBarOverlay.PROGRESS
      );
      bar.setVisible(true);
      // Only the people who can see him: the bar used to go to every player online, including
      // ones in another dimension who would never meet him. tickFight keeps it following the room.
      for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
         if (p.level() == level && p.distanceToSqr(boss) <= SEEK_RANGE * SEEK_RANGE) {
            bar.addPlayer(p);
         }
      }

      Fight fight = new Fight(boss.getUUID(), summoner.getUUID(), bar);
      fight.participants.add(summoner.getUUID());
      long now = ServerClock.clock(level);
      fight.nextMove = now + RISE_TICKS + 40L;
      fight.nextShear = now + RISE_TICKS + 140L;
      fight.nextEye = now + RISE_TICKS + 200L;
      fight.nextStorm = now + RISE_TICKS + 260L;
      fight.bar.setProgress(0.0F);
      FIGHTS.put(boss.getUUID(), fight);

      // The gathering: a ring of sigils on the floor, the air turning into it, and a spiral
      // climbing to where he will be.
      Vec3 floor = new Vec3(spot.x, spot.y - HOVER, spot.z);
      Fx.runeCircle(level, ParticleTypes.END_ROD, floor.add(0.0, 0.05, 0.0), 6.0, RISE_TICKS + 10, SKY);
      Fx.vortex(level, ParticleTypes.CLOUD, floor, 9.0, RISE_TICKS, GALE_WHITE);
      Fx.spiral(level, ParticleTypes.SMALL_GUST, floor, 10.0, RISE_TICKS, STORM);
      level.playSound(null, spot.x, spot.y, spot.z, SoundEvents.BREEZE_INHALE, SoundSource.HOSTILE, 1.8F, 0.5F);
      level.playSound(null, spot.x, spot.y, spot.z, SoundEvents.BREEZE_WHIRL, SoundSource.HOSTILE, 1.6F, 0.7F);
      overlayNear(level, boss, 60.0, "\u00a78The air goes still.");
      Advancements.grant(summoner, "summon_gale_warden");
      return null;
   }

   /**
    * Where he forms: six blocks ahead of the summoner if there is room for a full-size breeze
    * there, otherwise on the summoner's own spot. He never forms inside a wall.
    */
   private static Vec3 arrivalSpot(ServerLevel level, ServerPlayer summoner) {
      Vec3 look = summoner.getLookAngle();
      Vec3 flat = new Vec3(look.x, 0.0, look.z);
      if (flat.lengthSqr() > 1.0E-4) {
         Vec3 ahead = summoner.position().add(flat.normalize().scale(6.0));
         double y = BossGrounding.groundY(level, ahead.x, ahead.z, summoner.getY());
         if (Math.abs(y - summoner.getY()) <= 4.0) {
            net.minecraft.world.phys.AABB room = new net.minecraft.world.phys.AABB(
               ahead.x - 0.9, y + HOVER, ahead.z - 0.9, ahead.x + 0.9, y + HOVER + 4.4, ahead.z + 0.9
            );
            if (level.noCollision(room)) {
               return new Vec3(ahead.x, y + HOVER, ahead.z);
            }
         }
      }
      return new Vec3(
         summoner.getX(), BossGrounding.groundY(level, summoner.getX(), summoner.getZ(), summoner.getY()) + HOVER, summoner.getZ()
      );
   }

   /**
    * The arrival, one tick of it. The wind is visibly gathering - the circle turns, he grows from
    * a wisp to full size, the inhale gets louder - and the bar fills as he forms, so the fight has
    * a clear start line. At the end: a flash, a starburst of air and one soft shove off him (no
    * damage) so nobody begins the fight standing inside him.
    */
   private static void tickRise(ServerLevel level, Mob boss, Fight fight) {
      fight.rise--;
      double progress = 1.0 - fight.rise / (double)RISE_TICKS;
      attribute(boss, Attributes.SCALE, RISE_SCALE_FROM + (FULL_SCALE - RISE_SCALE_FROM) * progress);
      boss.setDeltaMovement(Vec3.ZERO);
      fight.bar.setProgress((float)Math.max(0.0, Math.min(1.0, progress)));
      Vec3 at = boss.position();
      if (fight.rise % 12 == 0 && fight.rise > 0) {
         Fx.ring(level, ParticleTypes.CLOUD, at.add(0.0, 0.2, 0.0), 9.0 - progress * 6.0, SKY);
         // The air arriving: a pair of gusts racing in from opposite sides to where he is forming.
         gustRing(level, floorUnder(level, at), 2.0, 7.0, 2, true, GALE_WHITE);
         level.playSound(null, at.x, at.y, at.z, SoundEvents.BREEZE_CHARGE, SoundSource.HOSTILE, 1.0F + (float)progress, 0.5F + (float)progress * 0.6F);
      }
      if (fight.rise == 20) {
         Fx.lightning(level, ParticleTypes.END_ROD, at.add(0.0, 22.0, 0.0), at.add(0.0, 2.0, 0.0), GALE_WHITE);
         level.playSound(null, at.x, at.y, at.z, SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.HOSTILE, 0.8F, 1.6F);
      }
      // Cheap vanilla breath around him while he forms; the client already has the vortex.
      if (fight.rise % 3 == 0) {
         Fx.vanilla(level, ParticleTypes.SMALL_GUST, at.x, at.y + 1.0, at.z, 4, 1.4, 1.0, 1.4, 0.05);
      }
      if (fight.rise > 0) {
         return;
      }
      attribute(boss, Attributes.SCALE, FULL_SCALE);
      boss.setInvulnerable(false);
      boss.setNoAi(false);
      fight.bar.setProgress(1.0F);
      Vec3 heart = at.add(0.0, 2.0, 0.0);
      Fx.flare(level, ParticleTypes.END_ROD, heart, 3.0, GALE_WHITE);
      Fx.starburst(level, ParticleTypes.CLOUD, heart, 8.0, SKY);
      Fx.shockwave(level, ParticleTypes.GUST, at, 14.0, STORM);
      Fx.sonicRing(level, ParticleTypes.GUST, heart, new Vec3(0.0, 1.0, 0.0), 10.0, 14, SKY);
      gustRing(level, floorUnder(level, at), 2.5, 9.0, 8, false, GALE_WHITE);
      level.playSound(null, at.x, at.y, at.z, ModSounds.BOSS_SPAWN, SoundSource.HOSTILE, 1.3F, 1.6F);
      level.playSound(null, at.x, at.y, at.z, SoundEvents.BREEZE_WIND_CHARGE_BURST, SoundSource.HOSTILE, 2.0F, 0.6F);
      for (ServerPlayer p : playersNear(level, at, 6.0)) {
         Vec3 away = p.position().subtract(at);
         push(p, new Vec3(away.x, 0.0, away.z), 0.9);
      }
      overlayNear(level, boss, 90.0, "\u00a7f\u00a7lTHE GALE WARDEN \u00a78He takes what moves. \u00a77Move less.");
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
      // Only a real player's blow counts: a puppet body hitting him is not somebody answering.
      if (!(attacker instanceof ServerPlayer) || BossManager.isFakePlayer(attacker)) {
         return;
      }
      if (fight.charge > 0) {
         fight.chargeTaken += amount;
         if (fight.chargeTaken >= CHARGE_BREAK_DAMAGE) {
            interruptCharge((ServerLevel)boss.level(), boss, fight);
         }
      }
      if (fight.stance > 0 && attacker instanceof ServerPlayer hitter && !hitter.isCreative() && !hitter.isSpectator()) {
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
      if (fight == null) {
         return null;
      }
      if (fight.dying) {
         // The ceremony is playing: every further blow is swallowed, so a second hit cannot cut
         // it short and drop the body mid-sentence. tickDeath releases the fight before the end.
         return Boolean.FALSE;
      }
      if (!(entity instanceof Mob boss) || boss.getHealth() - amount > 0.0F) {
         return null;
      }
      ServerLevel level = (ServerLevel)boss.level();
      fight.dying = true;
      fight.deathTicks = DEATH_CEREMONY_TICKS;
      // Everything he was holding lets go at once, and nothing he marked lands after he is gone.
      fight.pending.clear();
      fight.strikes.clear();
      fight.theft.clear();
      fight.theftLeft.clear();
      fight.reversed.clear();
      fight.link.clear();
      fight.linkLeft.clear();
      fight.frozen.clear();
      fight.charge = 0;
      fight.stance = 0;
      fight.zeroPoint = 0;
      fight.aerial = 0;
      fight.hold = 0;
      boss.setHealth(1.0F);
      boss.setNoAi(true);
      boss.setDeltaMovement(Vec3.ZERO);
      fight.bar.setProgress(0.0F);
      Vec3 at = boss.position();
      Fx.spiral(level, ParticleTypes.CLOUD, at, 10.0, DEATH_CEREMONY_TICKS, GALE_WHITE);
      Fx.aura(level, ParticleTypes.SMALL_GUST, at, 4.0, DEATH_CEREMONY_TICKS, SKY);
      Fx.runeCircle(level, ParticleTypes.END_ROD, floorUnder(level, at).add(0.0, 0.05, 0.0), 7.0, DEATH_CEREMONY_TICKS, STORM);
      level.playSound(null, at.x, at.y, at.z, SoundEvents.BREEZE_DEATH, SoundSource.HOSTILE, 2.0F, 0.7F);
      overlayNear(level, boss, 90.0, "\u00a78The wind is coming loose.");
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
         // The loot roll: anyone really there and alive (creative included, as before), but never
         // a spectator watching or a puppet body.
         boolean here = p.level() == level;
         double d2 = here ? p.distanceToSqr(boss) : Double.MAX_VALUE;
         if (here && p.isAlive() && !p.isSpectator() && !BossManager.isFakePlayer(p) && d2 < SEEK_RANGE * SEEK_RANGE) {
            fight.participants.add(p.getUUID());
         }
         // The bar follows the room: walk in and it appears (a late joiner included), change
         // dimension or walk well clear and it goes.
         if (here && d2 < SEEK_RANGE * SEEK_RANGE) {
            fight.bar.addPlayer(p);
         } else if (!here || d2 > SEEK_RANGE * SEEK_RANGE * 2.25) {
            fight.bar.removePlayer(p);
         }
      }

      if (fight.rise > 0) {
         tickRise(level, boss, fight);
         return;
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
      fight.bar.setColor(fight.phase >= 2 ? BossBarColor.RED : BossBarColor.WHITE);

      // The two systems that are not moves: he always drifts toward whoever is closest, and he is
      // always reading their movement.
      drift(level, boss, fight);
      tickMomentum(level, boss, fight);
      tickPending(level, boss, fight);
      tickStrikes(level, boss, fight, now);
      act(level, boss, fight, now);
      // And the floor gets everybody back. See liftFor: this is the half of the ladder that the
      // boss does not control, and it runs last, after every move and every lingering wind, so
      // nothing he does this tick can outlast it.
      holdDown(level, boss, fight);
   }

   /**
    * His turn, if he has one this tick: a wind-up that holds him, a marked blow off cooldown, or
    * the next move in the rotation - and between them, the stalk.
    */
   private static void act(ServerLevel level, Mob boss, Fight fight, long now) {
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
         if (fight.stance % 4 == 0) {
            vSphere(level, boss.position().add(0.0, 1.5, 0.0), 2.2, ParticleTypes.SMALL_GUST);
         }
         if (fight.stance == 0) {
            overlayNear(level, boss, 50.0, "\u00a78Guard's down. \u00a77Hit him.");
         }
         return;
      }
      if (fight.hold > 0) {
         // Planted for a marked blow: no stalking, no other move, so the tell stays where it is.
         fight.hold--;
         boss.setDeltaMovement(boss.getDeltaMovement().multiply(0.3, 1.0, 0.3));
         return;
      }
      // The marked blows run on their own clocks beside the rotation, never during a hurricane
      // (one arena-wide event at a time), and never stacked on another marked blow.
      if (fight.strikes.isEmpty() && !hurricaneUp(fight)) {
         if (now >= fight.nextStorm && RANDOM.nextInt(2) == 0) {
            // STORM CELL: a thunderhead parks over each of you and strikes the spot three times.
            List<ServerPlayer> marked = playersNear(level, boss.position(), 30.0);
            if (!marked.isEmpty()) {
               for (ServerPlayer p : marked) {
                  Hazards.stormCell(level, boss, floorUnder(level, p.position()), 3.0, fight.phase >= 3 ? 7.0F : 5.0F, SKY);
               }
               Fx.featherStorm(level, ParticleTypes.CLOUD, boss.position(), 4.0, 30, GALE_WHITE);
               fight.nextStorm = now + 360L - (fight.phase - 1) * 50L;
               tell(level, boss, "Storm Cell");
               return;
            }
         }
         if (fight.phase >= 2 && now >= fight.nextEye) {
            if (eyeOfTheStorm(level, boss, fight, now)) {
               return;
            }
         } else if (now >= fight.nextShear) {
            if (shear(level, boss, fight, now)) {
               return;
            }
         }
      }
      if (now >= fight.nextMove) {
         fight.nextMove = now + moveGap(fight.phase);
         chooseMove(level, boss, fight);
      }

      // And he moves, without a breeze's brain behind him. A vanilla breeze sprints and vaults on
      // a whim, which reads as a pinball rather than a boss and makes a fight built on *momentum*
      // impossible to read - so his movement is written here instead: a steady stalk toward
      // whoever is closest, slower than a player's walk, from which his dash, his charge and his
      // vacuum are the only things that ever move him fast.
      if (fight.stance == 0 && fight.hold == 0) {
         ServerPlayer chase = nearestPlayer(level, boss, 64.0);
         if (chase != null) {
            stalkToward(boss, chase, 2.5, 0.14);
         }
      }
   }

   private static boolean hurricaneUp(Fight fight) {
      for (Pending p : fight.pending) {
         if ("hurricane".equals(p.kind)) {
            return true;
         }
      }
      return false;
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
      Vec3 at = boss.position();
      vRing(level, at, 14.0, PHASE_RING_POINTS, ringParticle(), 0.0);
      Fx.shockwave(level, ParticleTypes.GUST, at, 16.0, phase == 3 ? STORM : SKY);
      // The sky turning over: eight gusts thrown out of him, and a ring climbing his column.
      gustRing(level, floorUnder(level, at), 2.0, 12.0, 8, false, GALE_WHITE);
      Fx.sonicRing(level, ParticleTypes.GUST, at.add(0.0, 1.0, 0.0), new Vec3(0.0, 1.0, 0.0), 14.0, 18, phase == 3 ? STORM : SKY);
      Fx.starburst(level, ParticleTypes.CLOUD, at.add(0.0, 2.0, 0.0), 7.0, GALE_WHITE);
      Fx.vortex(level, ParticleTypes.CLOUD, at, 8.0, 30, phase == 3 ? STORM : SKY);
      level.playSound(null, at.x, at.y, at.z, SoundEvents.BREEZE_WHIRL, SoundSource.HOSTILE, 1.8F, phase == 3 ? 0.6F : 0.9F);
      if (phase == 2) {
         overlayNear(level, boss, 90.0, "\u00a7f\u00a7lTHE BROKEN SKY \u00a78Now the sky moves too.");
      } else if (phase == 3) {
         Fx.lightning(level, ParticleTypes.END_ROD, at.add(0.0, 24.0, 0.0), at.add(0.0, 2.0, 0.0), GALE_WHITE);
         level.playSound(null, at.x, at.y, at.z, SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.HOSTILE, 1.2F, 0.8F);
         overlayNear(level, boss, 90.0, "\u00a7f\u00a7lTHE GALE \u00a78He stops touching the ground.");
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
         // His own weather: the wind he is made of, wrapping him a second at a time.
         Fx.aura(level, ParticleTypes.SMALL_GUST, boss.position(), 4.4, 20, SKY);
         Fx.vanilla(level, ParticleTypes.CLOUD, boss.getX(), boss.getY(), boss.getZ(), 3, 0.7, 0.5, 0.7, 0.02);
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
      for (ServerPlayer p : participants(level, boss)) {
         if (p.onGround() || p.isFallFlying() || p.isInWater() || p.getVehicle() != null) {
            fight.airTicks.remove(p.getUUID());
            continue;
         }
         int air = fight.airTicks.merge(p.getUUID(), 1, Integer::sum);
         Vec3 v = p.getDeltaMovement();
         double flat = Math.sqrt(v.x * v.x + v.z * v.z);
         if (flat > MAX_FLING) {
            // However many of his winds stacked this tick, a body crosses the arena - it is not
            // fired out of it.
            double k = MAX_FLING / flat;
            p.setDeltaMovement(v.x * k, v.y, v.z * k);
            p.hurtMarked = true;
         }
         if (air >= CUSHION_AFTER_AIR && p.fallDistance > FALL_CUSHION) {
            // The wind catches you: a throw costs ground, not a death to the fall.
            p.fallDistance = FALL_CUSHION;
         }
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
      for (ServerPlayer p : participants(level, boss)) {
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
               Double bank = fight.theft.remove(id);
               double power = Math.min(THEFT_CAP, bank == null ? 0.0 : bank) * THEFT_RELEASE;
               p.setDeltaMovement(
                  p.getDeltaMovement().add(release.scale(power)).add(0.0, liftFor(0.5, fight.airTicks.getOrDefault(id, 0)), 0.0)
               );
               p.hurtMarked = true;
               Fx.muzzle(level, ParticleTypes.GUST, p.position().add(0.0, 1.0, 0.0), release, GALE_WHITE);
               Fx.shockwave(level, ParticleTypes.SMALL_GUST, p.position(), 2.5 + power, SKY);
               // The banked speed, handed back as a visible wind behind the body it throws.
               Fx.gust(level, ParticleTypes.GUST, p.position().add(0.0, 1.0, 0.0), release, 3.0 + power * 2.0, GALE_WHITE);
               level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.BREEZE_WIND_CHARGE_BURST, SoundSource.HOSTILE, 1.3F, 0.9F);
            } else {
               fight.theftLeft.put(id, remaining);
               // The tether he is banking through, thicker the more he has banked.
               if (remaining % 10 == 0) {
                  Fx.chains(level, ParticleTypes.END_ROD, boss.position().add(0.0, 2.0, 0.0), p.position().add(0.0, 1.0, 0.0), SKY);
               }
               if (remaining % 4 == 0) {
                  vBeam(level, boss.position().add(0.0, 2.0, 0.0), p.position().add(0.0, 1.0, 0.0), 0.12, ParticleTypes.END_ROD);
               }
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
               if (rev % 15 == 0) {
                  Fx.aura(level, ParticleTypes.SCULK_SOUL, p.position(), 2.0, 15, STORM);
               }
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
               if (twin != null && twin.level() == level && twin.isAlive() && !twin.isSpectator()) {
                  if (linkLeft % 10 == 0 && id.compareTo(other) < 0) {
                     Fx.chains(level, ParticleTypes.END_ROD, p.position().add(0.0, 1.0, 0.0), twin.position().add(0.0, 1.0, 0.0), GALE_WHITE);
                  }
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
      // One storm at a time: a second hurricane, or a lingering wind on a full ledger, is a
      // different move instead.
      if (move.equals("Hurricane") && hurricaneUp(fight)) {
         move = "Four Winds";
      }
      if (fight.pending.size() >= MAX_PENDING) {
         move = fight.phase == 1 ? "Gale Counter" : fight.phase == 2 ? "Momentum Swap" : "Zero Point";
      }
      fight.lastMove = move;
      // Named first, so a move that falls back to another can name its replacement over it.
      tell(level, boss, move);
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
   }

   /**
    * The move's name on the action bar, and one grey line on how to answer it. Not speech: he
    * never talks, the fight just tells you what is coming.
    */
   private static void tell(ServerLevel level, Mob boss, String move) {
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.BREEZE_IDLE_AIR, SoundSource.HOSTILE, 1.5F, 0.7F);
      String hint = switch (move) {
         case "Momentum Theft" -> "He's banking your speed. \u00a77Stand still.";
         case "Reversal" -> "Knockback pulls now. \u00a77Don't trade hits.";
         case "Windstep" -> "Blasts where he stood. \u00a77Clear his trail.";
         case "Dead Air" -> "The air thickens. \u00a77Get out before it snaps.";
         case "Storm Cell" -> "A thunderhead over you. \u00a77Keep moving.";
         case "Pressure Point" -> "The ground under you is his. \u00a77Keep moving.";
         case "Gale Counter" -> "He's braced. \u00a77Don't hit him.";
         case "Tornado" -> "It wanders. \u00a77Walk around it.";
         case "Twin Twisters" -> "Two of them. \u00a77Stay in the gap.";
         case "Sky Launch" -> "Up you go. \u00a77Steer off the currents.";
         case "Cyclone Dash" -> "His wake still moves. \u00a77Stay off it.";
         case "Momentum Swap" -> "You move as they move. \u00a77Both stand still.";
         case "Falling Sky" -> "Charges coming down. \u00a77Don't stand still.";
         case "Vacuum" -> "He's pulling. \u00a77Walk away, then brace.";
         case "No Ground" -> "No floor. \u00a77Ride the currents down.";
         case "Zero Point" -> "Everything stops. \u00a77Stop first.";
         case "Heaven's Drop" -> "Way up. \u00a77Steer for the currents.";
         case "Four Winds" -> "It all slides to him. \u00a77Off the pillars.";
         case "Absolute Momentum" -> "Hit him hard. \u00a77Or be gone.";
         case "Hurricane" -> "No stopping it. \u00a77Lean out. Brace.";
         default -> "";
      };
      overlayNear(level, boss, 80.0, "\u00a7f\u00a7l" + move.toUpperCase(java.util.Locale.ROOT) + " \u00a78" + hint);
   }

   // ---- phase 1: the Warden

   /** He banks your movement for a few seconds, then hands all of it back in one launch. */
   private static void momentumTheft(ServerLevel level, Mob boss, Fight fight, ServerPlayer target) {
      if (target == null) {
         tell(level, boss, "Pressure Point");
         pressurePoint(level, boss, fight);
         return;
      }
      fight.theftLeft.put(target.getUUID(), THEFT_TICKS);
      fight.theft.put(target.getUUID(), 0.0);
      Fx.chains(level, ParticleTypes.END_ROD, boss.position().add(0.0, 2.0, 0.0), target.position().add(0.0, 1.0, 0.0), SKY);
      level.playSound(null, target.getX(), target.getY(), target.getZ(), SoundEvents.BREEZE_CHARGE, SoundSource.HOSTILE, 1.4F, 1.2F);
   }

   /** Knockback becomes pullback: being launched at him is being dragged to him. */
   private static void reversal(ServerLevel level, Mob boss, Fight fight, ServerPlayer target) {
      if (target == null) {
         tell(level, boss, "Pressure Point");
         pressurePoint(level, boss, fight);
         return;
      }
      fight.reversed.put(target.getUUID(), REVERSAL_TICKS);
      Fx.beam(level, ParticleTypes.END_ROD, boss.position().add(0.0, 2.0, 0.0), target.position().add(0.0, 1.0, 0.0), STORM);
      portalWind(level, ParticleTypes.SCULK_SOUL, target.position().add(0.0, 1.0, 0.0), true, STORM);
      // The wind turning round on them: a gust from where they stand back toward him.
      Vec3 home = boss.position().subtract(target.position());
      Fx.gust(level, ParticleTypes.GUST, target.position().add(0.0, 1.0, 0.0), new Vec3(home.x, 0.0, home.z), Math.min(12.0, home.length()), STORM);
      level.playSound(null, target.getX(), target.getY(), target.getZ(), SoundEvents.BREEZE_INHALE, SoundSource.HOSTILE, 1.2F, 1.3F);
   }

   /** Between bodies, with a delayed blast left at every place he was. */
   private static void windstep(ServerLevel level, Mob boss, Fight fight) {
      List<ServerPlayer> near = participants(level, boss);
      if (near.isEmpty()) {
         return;
      }
      int steps = Math.min(3, near.size() + 1);
      Vec3 at = boss.position();
      for (int i = 0; i < steps; i++) {
         ServerPlayer to = near.get(RANDOM.nextInt(near.size()));
         // He lands beside them, not in them: a step to their side, on the floor there.
         Vec3 side = randomFlat().scale(3.0);
         double lx = to.getX() + side.x;
         double lz = to.getZ() + side.z;
         Vec3 landing = new Vec3(lx, BossGrounding.groundY(level, lx, lz, to.getY()) + HOVER, lz);
         // The blast he leaves behind was a fuse of zero with a life, so it never landed at all;
         // it is a real fuse now, staggered so the trail goes off in the order he walked it.
         fight.pending.add(new Pending("gust", at, null, 16 + i * 6, 0));
         Fx.comet(level, ParticleTypes.CLOUD, at, landing, 4, GALE_WHITE);
         Fx.gust(level, ParticleTypes.GUST, at.add(0.0, 1.0, 0.0), landing.subtract(at), Math.min(24.0, landing.distanceTo(at)), SKY);
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
      Fx.dome(level, ParticleTypes.END_ROD, center, DEAD_AIR_RADIUS, DEAD_AIR_TICKS, GALE_WHITE);
      Fx.runeCircle(level, ParticleTypes.END_ROD, center.add(0.0, 0.05, 0.0), DEAD_AIR_RADIUS, DEAD_AIR_TICKS, STORM);
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
      Fx.dome(level, ParticleTypes.SMALL_GUST, boss.position(), 3.2, STANCE_TICKS, SKY);
      Fx.aura(level, ParticleTypes.END_ROD, boss.position(), 4.4, STANCE_TICKS, GALE_WHITE);
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
      Fx.clash(level, ParticleTypes.CRIT, boss.position().add(unit.scale(1.6)).add(0.0, 1.6, 0.0), unit, GALE_WHITE);
      Fx.crescent(level, ParticleTypes.GUST, boss.position().add(0.0, 0.6, 0.0), unit, 5.0, SKY);
      // The blow, thrown back: a ring of pressure and a gust down the line the hitter flies.
      Fx.sonicRing(level, ParticleTypes.GUST, boss.position().add(0.0, 1.6, 0.0), unit, 7.0, 10, GALE_WHITE);
      Fx.gust(level, ParticleTypes.GUST, hitter.position().add(0.0, 1.0, 0.0), unit, 6.0, SKY);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.BREEZE_DEFLECT, SoundSource.HOSTILE, 1.6F, 0.8F);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.BREEZE_WIND_CHARGE_BURST, SoundSource.HOSTILE, 1.6F, 1.1F);
      hitter.sendOverlayMessage(Component.literal("\u00a7f\u00a7lCOUNTERED \u00a78He was braced. \u00a77Wait for the drop."));
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
      Vec3 at = floorUnder(level, boss.position().add(randomFlat().scale(5.0)));
      fight.pending.add(new Pending("tornado", at, randomFlat(), 0, TORNADO_TICKS));
      // It touches down: a geyser of air and a ring blown off the floor. The turning column itself
      // (vortex and spiral) is sent by steerTornado on the first tick and every turn after.
      Fx.geyser(level, ParticleTypes.CLOUD, at, TORNADO_HEIGHT, GALE_WHITE);
      Fx.shockwave(level, ParticleTypes.GUST, at, TORNADO_RADIUS, SKY);
      level.playSound(null, at.x, at.y, at.z, SoundEvents.BREEZE_WHIRL, SoundSource.HOSTILE, 1.6F, 0.9F);
   }

   /** Two of them, at once, from opposite sides - walking between them is the counterplay. */
   private static void twinTwisters(ServerLevel level, Mob boss, Fight fight) {
      Vec3 side = randomFlat();
      for (int i = 0; i < 2; i++) {
         Vec3 at = floorUnder(level, boss.position().add(side.scale(i == 0 ? 9.0 : -9.0)));
         Vec3 drift = i == 0 ? new Vec3(-side.z, 0.0, side.x) : new Vec3(side.z, 0.0, -side.x);
         fight.pending.add(new Pending("tornado", at, drift, 0, TORNADO_TICKS));
         Fx.geyser(level, ParticleTypes.CLOUD, at, TORNADO_HEIGHT, GALE_WHITE);
         // The two of them, drawn together: a gust from each toward the gap between.
         Fx.gust(level, ParticleTypes.GUST, at.add(0.0, 1.5, 0.0), boss.position().subtract(at), 7.0, SKY);
         Fx.shockwave(level, ParticleTypes.GUST, at, TORNADO_RADIUS, SKY);
         level.playSound(null, at.x, at.y, at.z, SoundEvents.BREEZE_WHIRL, SoundSource.HOSTILE, 1.6F, 1.1F);
      }
   }

   // ---- phase 2: the Broken Sky

   /**
    * Everyone goes up - and comes down where the currents put them. The currents are the move: a
    * launch you cannot steer is a rollercoaster, and a launch you must steer is a phase-two fight.
    */
   private static void skyLaunch(ServerLevel level, Mob boss, Fight fight) {
      for (ServerPlayer p : participants(level, boss)) {
         p.setDeltaMovement(p.getDeltaMovement().add(0.0, liftFor(1.65, fight.airTicks.getOrDefault(p.getUUID(), 0)), 0.0));
         p.hurtMarked = true;
         Fx.geyser(level, ParticleTypes.CLOUD, p.position(), 6.0, GALE_WHITE);
         Fx.gust(level, ParticleTypes.GUST, p.position(), new Vec3(0.0, 1.0, 0.0), 7.0, SKY);
      }
      Vec3 center = boss.position();
      for (int i = 0; i < 3; i++) {
         double a = RANDOM.nextDouble() * Math.PI * 2.0;
         fight.pending.add(
            new Pending("current", center.add(Math.cos(a) * 6.0, 0.0, Math.sin(a) * 6.0), new Vec3(Math.cos(a), 0.0, Math.sin(a)), 0, CURRENT_TICKS)
         );
      }
      Fx.shockwave(level, ParticleTypes.GUST, center, 12.0, SKY);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.BREEZE_JUMP, SoundSource.HOSTILE, 1.6F, 0.7F);
   }

   /** A fast crossing that leaves the air behind it moving - touching a tunnel is the cost. */
   private static void cycloneDash(ServerLevel level, Mob boss, Fight fight) {
      // Across the fight rather than off in a random direction: past the nearest fighter if there
      // is one. And never through a wall - the dash was a teleport twenty blocks out, which could
      // put him inside stone or off the edge of the arena; it now stops at the first block.
      ServerPlayer near = nearestPlayer(level, boss, 40.0);
      Vec3 dir = near == null ? randomFlat() : new Vec3(near.getX() - boss.getX(), 0.0, near.getZ() - boss.getZ());
      dir = dir.lengthSqr() < 1.0E-4 ? randomFlat() : dir.normalize();
      Vec3 from = boss.position();
      double reach = 0.0;
      for (double d = 1.0; d <= 20.0; d += 1.0) {
         if (!level.noCollision(boss, boss.getBoundingBox().move(dir.x * d, 0.0, dir.z * d))) {
            break;
         }
         reach = d;
      }
      if (reach < 4.0) {
         tell(level, boss, "Vacuum");
         vacuum(level, boss, fight);
         return;
      }
      for (double d = 0.0; d < reach; d += 4.0) {
         fight.pending.add(new Pending("tunnel", from.add(dir.scale(d)), dir, 0, TUNNEL_TICKS));
      }
      Vec3 end = from.add(dir.scale(reach));
      Fx.comet(level, ParticleTypes.CLOUD, from.add(0.0, 2.0, 0.0), end.add(0.0, 2.0, 0.0), 5, GALE_WHITE);
      Fx.beam(level, ParticleTypes.SMALL_GUST, from.add(0.0, 0.6, 0.0), end.add(0.0, 0.6, 0.0), SKY);
      Fx.gust(level, ParticleTypes.GUST, from.add(0.0, 1.4, 0.0), dir, reach, GALE_WHITE);
      boss.setPos(end.x, boss.getY(), end.z);
      boss.hurtMarked = true;
      Fx.shockwave(level, ParticleTypes.GUST, end, 5.0, STORM);
      level.playSound(null, end.x, end.y, end.z, SoundEvents.BREEZE_SHOOT, SoundSource.HOSTILE, 1.7F, 0.7F);
   }

   /** Two players, one movement: each is pushed by however the other is moving. */
   private static void momentumSwap(ServerLevel level, Mob boss, Fight fight) {
      List<ServerPlayer> near = participants(level, boss);
      if (near.size() < 2) {
         tell(level, boss, "Sky Launch");
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
         Fx.chains(level, ParticleTypes.END_ROD, a.position().add(0.0, 1.0, 0.0), b.position().add(0.0, 1.0, 0.0), GALE_WHITE);
         Fx.resonance(level, ParticleTypes.END_ROD, a.position().add(0.0, 1.0, 0.0), 20, SKY);
         Fx.resonance(level, ParticleTypes.END_ROD, b.position().add(0.0, 1.0, 0.0), 20, SKY);
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
         int fuse = 30 + RANDOM.nextInt(45);
         fight.pending.add(new Pending("skyfall", new Vec3(x, y, z), null, fuse, 0));
      }
      Fx.pillar(level, ParticleTypes.CLOUD, boss.position(), 18.0, GALE_WHITE);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.BREEZE_CHARGE, SoundSource.HOSTILE, 1.6F, 0.6F);
   }

   /** Everything is pulled into one place, and then the place stops holding it. */
   private static void vacuum(ServerLevel level, Mob boss, Fight fight) {
      fight.pending.add(new Pending("vacuum", boss.position(), null, 0, 60));
      Fx.vortex(level, ParticleTypes.CLOUD, boss.position(), 18.0, 60, STORM);
      portalWind(level, ParticleTypes.SMALL_GUST, boss.position().add(0.0, 2.0, 0.0), true, GALE_WHITE);
      // The draw: six gusts racing in from the edge of the pull to him.
      gustRing(level, floorUnder(level, boss.position()), 2.0, 14.0, 6, true, SKY);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.BREEZE_INHALE, SoundSource.HOSTILE, 1.8F, 0.7F);
   }

   // ---- phase 3: the Gale

   /** The floor stops mattering: he rises, and currents run the arena for a while. */
   private static void noGround(ServerLevel level, Mob boss, Fight fight) {
      fight.aerial = AERIAL_TICKS;
      for (ServerPlayer p : participants(level, boss)) {
         // Through the lift cap like every other launch: this one alone was uncapped.
         p.setDeltaMovement(p.getDeltaMovement().add(0.0, liftFor(1.1, fight.airTicks.getOrDefault(p.getUUID(), 0)), 0.0));
         p.hurtMarked = true;
         Fx.geyser(level, ParticleTypes.CLOUD, p.position(), 5.0, GALE_WHITE);
         Fx.gust(level, ParticleTypes.GUST, p.position(), new Vec3(0.0, 1.0, 0.0), 6.0, SKY);
      }
      Fx.shockwave(level, ParticleTypes.GUST, boss.position(), 14.0, STORM);
      Fx.sonicRing(level, ParticleTypes.GUST, boss.position(), new Vec3(0.0, 1.0, 0.0), 12.0, 16, SKY);
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
      Fx.dome(level, ParticleTypes.END_ROD, boss.position(), 18.0, ZERO_POINT_TICKS, GALE_WHITE);
      Fx.clockBurst(level, ParticleTypes.END_ROD, boss.position().add(0.0, 2.0, 0.0), 4.0, SKY);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.BREEZE_INHALE, SoundSource.HOSTILE, 2.0F, 0.5F);
   }

   private static void tickZeroPoint(ServerLevel level, Mob boss, Fight fight) {
      fight.zeroPoint--;
      if (fight.zeroPoint % 3 == 0) {
         vRing(level, boss.position(), 3.0 + fight.zeroPoint * 0.4, 30, ParticleTypes.END_ROD, 0.6);
      }
      if (fight.zeroPoint == 10) {
         overlayNear(level, boss, 90.0, "\u00a78Here it comes.");
      }
      if (fight.zeroPoint > 0) {
         return;
      }
      for (ServerPlayer p : participants(level, boss)) {
         Vec3 stored = fight.frozen.get(p.getUUID());
         Vec3 give = stored == null ? Vec3.ZERO : stored;
         Vec3 flat = new Vec3(give.x, 0.0, give.z).scale(ZERO_POINT_RELEASE);
         p.setDeltaMovement(flat.add(0.0, liftFor(0.6, fight.airTicks.getOrDefault(p.getUUID(), 0)), 0.0));
         p.hurtMarked = true;
         Fx.shatter(level, ParticleTypes.END_ROD, p.position().add(0.0, 1.0, 0.0), 1.0, GALE_WHITE);
         if (flat.lengthSqr() > 1.0E-3) {
            // Everything it banked, released along the way it was going.
            Fx.gust(level, ParticleTypes.GUST, p.position().add(0.0, 1.0, 0.0), flat, Math.min(10.0, 3.0 + flat.length() * 4.0), GALE_WHITE);
         }
      }
      fight.frozen.clear();
      Fx.shockwave(level, ParticleTypes.GUST, boss.position(), 18.0, SKY);
      Fx.starburst(level, ParticleTypes.CLOUD, boss.position().add(0.0, 2.0, 0.0), 8.0, GALE_WHITE);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.BREEZE_WIND_CHARGE_BURST, SoundSource.HOSTILE, 2.0F, 0.6F);
   }

   /** Everyone thrown very high, with currents underneath to be steered toward safety. */
   private static void heavensDrop(ServerLevel level, Mob boss, Fight fight) {
      for (ServerPlayer p : participants(level, boss)) {
         Fx.geyser(level, ParticleTypes.CLOUD, p.position(), 8.0, GALE_WHITE);
         Fx.gust(level, ParticleTypes.GUST, p.position(), new Vec3(0.0, 1.0, 0.0), 10.0, SKY);
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
      Fx.shockwave(level, ParticleTypes.GUST, center, 16.0, STORM);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.BREEZE_JUMP, SoundSource.HOSTILE, 2.0F, 0.6F);
   }

   /** Four winds at the four points of the arena, each shoving everything toward the middle. */
   private static void fourWinds(ServerLevel level, Mob boss, Fight fight) {
      Vec3 center = boss.position();
      double r = 14.0;
      for (int i = 0; i < 4; i++) {
         double a = (Math.PI * 2.0 * i) / 4.0;
         Vec3 at = floorUnder(level, center.add(Math.cos(a) * r, 0.0, Math.sin(a) * r));
         fight.pending.add(new Pending("wind", at, null, 0, CURRENT_TICKS));
         Fx.spiral(level, ParticleTypes.CLOUD, at, 8.0, CURRENT_TICKS, GALE_WHITE);
         Fx.runeCircle(level, ParticleTypes.END_ROD, at.add(0.0, 0.05, 0.0), 3.0, CURRENT_TICKS, STORM);
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
      Fx.vortex(level, ParticleTypes.CLOUD, boss.position(), 10.0, CHARGE_TICKS, STORM);
      Fx.aura(level, ParticleTypes.END_ROD, boss.position(), 5.0, CHARGE_TICKS, GALE_WHITE);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.BREEZE_INHALE, SoundSource.HOSTILE, 2.0F, 0.6F);
   }

   private static void tickCharge(ServerLevel level, Mob boss, Fight fight) {
      fight.charge--;
      double progress = 1.0 - (fight.charge / (double)CHARGE_TICKS);
      boss.setDeltaMovement(boss.getDeltaMovement().multiply(0.3, 1.0, 0.3));
      if (fight.charge % 4 == 0) {
         vRing(level, boss.position(), 3.0 + progress * 26.0, CHARGE_RING_POINTS, ringParticle(), 0.4);
      }
      if (fight.charge % 10 == 0) {
         Fx.ring(level, ParticleTypes.GUST, boss.position().add(0.0, 0.3, 0.0), 3.0 + progress * 26.0, SKY);
         // The arena's air being drawn into him, harder as the charge fills.
         gustRing(level, floorUnder(level, boss.position()), 2.0, 6.0 + progress * 14.0, 4, true, STORM);
         level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.BREEZE_CHARGE, SoundSource.HOSTILE, 1.2F + (float)progress, 0.7F);
         // How close the break is, as a bar of its own: the damage check is the whole move.
         int broke = (int)Math.min(10.0F, fight.chargeTaken / CHARGE_BREAK_DAMAGE * 10.0F);
         overlayNear(level, boss, 90.0, "\u00a7f\u00a7lABSOLUTE MOMENTUM \u00a7b" + "|".repeat(broke) + "\u00a78" + "|".repeat(10 - broke)
            + " \u00a77" + (fight.charge / 20 + 1) + "s");
      }
      if (fight.charge > 0) {
         return;
      }
      releaseAbsolute(level, boss, fight);
   }

   private static void releaseAbsolute(ServerLevel level, Mob boss, Fight fight) {
      fight.charge = 0;
      Vec3 at = boss.position();
      Fx.flare(level, ParticleTypes.END_ROD, at.add(0.0, 2.0, 0.0), 3.6, GALE_WHITE);
      Fx.shockwave(level, ParticleTypes.GUST, at, ABSOLUTE_RADIUS, STORM);
      Fx.nova(level, ParticleTypes.CLOUD, at, ABSOLUTE_RADIUS * 0.6, SKY);
      Fx.starburst(level, ParticleTypes.CLOUD, at.add(0.0, 2.0, 0.0), 12.0, GALE_WHITE);
      gustRing(level, floorUnder(level, at), 3.0, 22.0, 8, false, GALE_WHITE);
      level.playSound(null, at.x, at.y, at.z, SoundEvents.BREEZE_WIND_CHARGE_BURST, SoundSource.HOSTILE, 2.2F, 0.5F);
      level.playSound(null, at.x, at.y, at.z, SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.HOSTILE, 1.4F, 0.7F);
      for (ServerPlayer p : playersNear(level, at, ABSOLUTE_RADIUS)) {
         Vec3 away = p.position().subtract(at);
         Vec3 unit = new Vec3(away.x, 0.0, away.z);
         unit = unit.lengthSqr() < 1.0E-4 ? new Vec3(1.0, 0.0, 0.0) : unit.normalize();
         p.hurtServer(level, level.damageSources().mobAttack(boss), ABSOLUTE_DAMAGE);
         // Through the lift cap: this was a flat 1.1 up on top of the shove.
         p.setDeltaMovement(
            p.getDeltaMovement().add(unit.scale(ABSOLUTE_LAUNCH)).add(0.0, liftFor(1.1, fight.airTicks.getOrDefault(p.getUUID(), 0)), 0.0)
         );
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
      // The storm is the move: nothing else from the rotation until it has let go.
      fight.nextMove = fight.now + HURRICANE_TICKS + 40L;
      vRing(level, boss.position(), HURRICANE_RADIUS * 0.6, HURRICANE_RING_POINTS, ringParticle(), 0.4);
      Fx.vortex(level, ParticleTypes.CLOUD, boss.position(), HURRICANE_RADIUS * 0.6, HURRICANE_TICKS, STORM);
      Fx.spiral(level, ParticleTypes.CLOUD, boss.position(), 14.0, HURRICANE_TICKS, GALE_WHITE);
      Fx.emberRain(level, ParticleTypes.WHITE_ASH, boss.position(), HURRICANE_RADIUS * 0.5, HURRICANE_TICKS, SKY);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.BREEZE_WHIRL, SoundSource.HOSTILE, 2.2F, 0.5F);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.HOSTILE, 1.0F, 0.6F);
   }

   /**
    * The whole arena, let go of in one blast. Every body inside it - not only the marked ones - is
    * thrown outward off him and left standing wherever it lands, which is the deliberate end of a
    * fight whose every other move asked you to keep your feet.
    */
   private static void releaseHurricane(ServerLevel level, Mob boss, Fight fight) {
      Vec3 at = boss.position();
      Fx.flare(level, ParticleTypes.END_ROD, at.add(0.0, 3.0, 0.0), 4.0, GALE_WHITE);
      Fx.shockwave(level, ParticleTypes.GUST, at, HURRICANE_RADIUS, STORM);
      Fx.nova(level, ParticleTypes.CLOUD, at, HURRICANE_RADIUS * 0.7, SKY);
      Fx.starburst(level, ParticleTypes.CLOUD, at.add(0.0, 3.0, 0.0), 14.0, GALE_WHITE);
      gustRing(level, floorUnder(level, at), 4.0, 24.0, 8, false, GALE_WHITE);
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
         p.sendOverlayMessage(Component.literal("\u00a7f\u00a7lIT LETS GO"));
      }
      fight.nextMove = fight.now + 40L;
   }

   private static void interruptCharge(ServerLevel level, Mob boss, Fight fight) {
      fight.charge = 0;
      Fx.shatter(level, ParticleTypes.END_ROD, boss.position().add(0.0, 2.0, 0.0), 2.4, GALE_WHITE);
      Fx.clash(level, ParticleTypes.CRIT, boss.position().add(0.0, 2.0, 0.0), new Vec3(0.0, 1.0, 0.0), SKY);
      // The charge spilling out of him, harmless: a burst and the drawn-in wind let loose.
      Fx.starburst(level, ParticleTypes.CLOUD, boss.position().add(0.0, 2.0, 0.0), 6.0, SKY);
      gustRing(level, floorUnder(level, boss.position()), 1.5, 6.0, 6, false, SKY);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.BREEZE_DEFLECT, SoundSource.HOSTILE, 1.6F, 0.8F);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.GLASS_BREAK, SoundSource.HOSTILE, 1.4F, 0.7F);
      overlayNear(level, boss, 90.0, "\u00a7f\u00a7lBROKEN \u00a78He's open. \u00a77Go.");
      // The reward for interrupting it is a window, not a free kill: he loses the blow and has to
      // gather himself again.
      fight.nextMove = fight.now + 60L;
   }

   // ------------------------------------------------------------------ marked blows

   /**
    * Shear. A lane of wind is drawn on the floor from him through a fighter - through two of them
    * in phase two, three in phase three - and {@link #SHEAR_WARN} ticks later a blade of air runs
    * the length of it. The lane is narrow and thirty blocks long, so backing away down it does
    * nothing: the answer is one sidestep. Phase three adds a crossing lane through each target, so
    * the sidestep has to pick a corner rather than any direction.
    *
    * @return whether anything was marked (nobody in reach means the cooldown is not spent)
    */
   private static boolean shear(ServerLevel level, Mob boss, Fight fight, long now) {
      List<ServerPlayer> targets = playersNear(level, boss.position(), 40.0);
      if (targets.isEmpty()) {
         return false;
      }
      java.util.Collections.shuffle(targets, RANDOM);
      int lanes = Math.min(targets.size(), fight.phase);
      Vec3 origin = floorUnder(level, boss.position());
      for (int i = 0; i < lanes && fight.strikes.size() < MAX_STRIKES; i++) {
         ServerPlayer t = targets.get(i);
         Vec3 dir = new Vec3(t.getX() - origin.x, 0.0, t.getZ() - origin.z);
         dir = dir.lengthSqr() < 1.0E-4 ? randomFlat() : dir.normalize();
         // It starts a little behind him, so standing at his back is not a gap in it.
         markShear(level, fight, origin.subtract(dir.scale(4.0)), dir, now);
         if (fight.phase >= 3) {
            Vec3 across = new Vec3(-dir.z, 0.0, dir.x);
            Vec3 mid = floorUnder(level, t.position());
            markShear(level, fight, mid.subtract(across.scale(SHEAR_LENGTH * 0.5)), across, now);
         }
      }
      fight.nextShear = now + SHEAR_COOLDOWN - (fight.phase - 1) * 40L;
      fight.hold = SHEAR_WARN;
      fight.nextMove = Math.max(fight.nextMove, now + SHEAR_WARN + 20L);
      Fx.aura(level, ParticleTypes.SMALL_GUST, boss.position(), 4.0, SHEAR_WARN, SKY);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.BREEZE_INHALE, SoundSource.HOSTILE, 1.6F, 1.2F);
      overlayNear(level, boss, 80.0, "§f§lSHEAR §8Lanes on the floor. §7Sidestep.");
      return true;
   }

   private static void markShear(ServerLevel level, Fight fight, Vec3 start, Vec3 dir, long now) {
      Strike s = new Strike(Strike.SHEAR, start, dir, now + SHEAR_WARN);
      fight.strikes.add(s);
      drawShearLane(level, s);
      s.nextDraw = now + 8L;
   }

   /** The lane as it is drawn while armed: both edges on the floor and a sigil at the far end. */
   private static void drawShearLane(ServerLevel level, Strike s) {
      Vec3 side = new Vec3(-s.dir.z, 0.0, s.dir.x).scale(SHEAR_HALF_WIDTH);
      Vec3 end = s.at.add(s.dir.scale(SHEAR_LENGTH));
      Fx.beam(level, ParticleTypes.END_ROD, s.at.add(side).add(0.0, 0.15, 0.0), end.add(side).add(0.0, 0.15, 0.0), SKY);
      Fx.beam(level, ParticleTypes.END_ROD, s.at.subtract(side).add(0.0, 0.15, 0.0), end.subtract(side).add(0.0, 0.15, 0.0), SKY);
   }

   /**
    * Eye of the Storm, from phase two. He plants himself; a calm dome opens around him and a wall
    * of storm turns outside it for {@link #EYE_WARN} ticks. When it closes, everything between the
    * calm and the wall is hit and thrown outward. The answer is the one his other moves punish:
    * walk in, right up to him.
    *
    * @return whether it was cast (nobody in the storm band means the cooldown is not spent)
    */
   private static boolean eyeOfTheStorm(ServerLevel level, Mob boss, Fight fight, long now) {
      if (playersNear(level, boss.position(), EYE_REACH).isEmpty()) {
         return false;
      }
      Vec3 center = floorUnder(level, boss.position());
      fight.strikes.add(new Strike(Strike.EYE, center, Vec3.ZERO, now + EYE_WARN));
      fight.nextEye = now + EYE_COOLDOWN - (fight.phase >= 3 ? 80L : 0L);
      fight.hold = EYE_WARN;
      fight.nextMove = Math.max(fight.nextMove, now + EYE_WARN + 20L);
      Fx.dome(level, ParticleTypes.END_ROD, center, EYE_SAFE, EYE_WARN, GALE_WHITE);
      Fx.vortex(level, ParticleTypes.CLOUD, center, EYE_REACH, EYE_WARN, STORM);
      level.playSound(null, center.x, center.y, center.z, SoundEvents.BREEZE_WHIRL, SoundSource.HOSTILE, 2.0F, 0.6F);
      overlayNear(level, boss, 90.0, "§f§lEYE OF THE STORM §8Calm in the middle. §7Get close.");
      return true;
   }

   /** The marked blows, on the fight's clock: redraw while armed, then land. */
   private static void tickStrikes(ServerLevel level, Mob boss, Fight fight, long now) {
      if (fight.strikes.isEmpty()) {
         return;
      }
      for (java.util.Iterator<Strike> it = fight.strikes.iterator(); it.hasNext();) {
         Strike s = it.next();
         // A strike from a clock that jumped (or a mark that somehow outlived its fight's pace) is
         // dropped rather than left armed for ever.
         if (s.landAt - now > 200L) {
            it.remove();
            continue;
         }
         if (now < s.landAt) {
            if (now >= s.nextDraw) {
               s.nextDraw = now + 8L;
               if (s.kind == Strike.SHEAR) {
                  drawShearLane(level, s);
               } else {
                  Fx.ring(level, ParticleTypes.END_ROD, s.at.add(0.0, 0.2, 0.0), EYE_SAFE, GALE_WHITE);
                  Fx.ring(level, ParticleTypes.CLOUD, s.at.add(0.0, 0.4, 0.0), EYE_REACH, STORM);
                  float pitch = 0.5F + 0.8F * (float)(1.0 - (s.landAt - now) / (double)EYE_WARN);
                  level.playSound(null, s.at.x, s.at.y, s.at.z, SoundEvents.BREEZE_CHARGE, SoundSource.HOSTILE, 1.4F, pitch);
               }
            }
            if (!s.falling && now >= s.landAt - 6L) {
               // The last beat: the lane flashes, or the storm wall leans in.
               s.falling = true;
               if (s.kind == Strike.SHEAR) {
                  Fx.muzzle(level, ParticleTypes.GUST, s.at.add(0.0, 1.0, 0.0), s.dir, GALE_WHITE);
                  Fx.gust(level, ParticleTypes.GUST, s.at.add(0.0, 1.0, 0.0), s.dir, 5.0, SKY);
                  level.playSound(null, s.at.x, s.at.y, s.at.z, SoundEvents.BREEZE_SHOOT, SoundSource.HOSTILE, 1.6F, 1.3F);
               } else {
                  Fx.shockwave(level, ParticleTypes.CLOUD, s.at, EYE_REACH * 0.8, SKY);
               }
            }
            continue;
         }
         it.remove();
         if (s.kind == Strike.SHEAR) {
            landShear(level, boss, fight, s);
         } else {
            landEye(level, boss, fight, s);
         }
      }
   }

   private static void landShear(ServerLevel level, Mob boss, Fight fight, Strike s) {
      Vec3 end = s.at.add(s.dir.scale(SHEAR_LENGTH));
      for (double d = 0.0; d <= SHEAR_LENGTH; d += 6.0) {
         Fx.crescent(level, ParticleTypes.SWEEP_ATTACK, s.at.add(s.dir.scale(d)), s.dir, 3.0, GALE_WHITE);
      }
      // The blade itself: pressure crescents racing the length of the lane with a gust riding them.
      Fx.sonicRing(level, ParticleTypes.GUST, s.at.add(0.0, 1.0, 0.0), s.dir, SHEAR_LENGTH, 14, SKY);
      Fx.gust(level, ParticleTypes.GUST, s.at.add(0.0, 1.0, 0.0), s.dir, SHEAR_LENGTH, GALE_WHITE);
      Fx.shockwave(level, ParticleTypes.GUST, end, 3.0, STORM);
      Vec3 mid = s.at.add(s.dir.scale(SHEAR_LENGTH * 0.5));
      level.playSound(null, mid.x, mid.y, mid.z, SoundEvents.BREEZE_WIND_CHARGE_BURST, SoundSource.HOSTILE, 1.8F, 1.2F);
      for (ServerPlayer p : playersNear(level, mid, SHEAR_LENGTH * 0.5 + 3.0)) {
         Vec3 rel = new Vec3(p.getX() - s.at.x, 0.0, p.getZ() - s.at.z);
         double along = rel.dot(s.dir);
         if (along < 0.0 || along > SHEAR_LENGTH || Math.abs(p.getY() - s.at.y) > 4.0) {
            continue;
         }
         Vec3 off = rel.subtract(s.dir.scale(along));
         if (off.length() > SHEAR_HALF_WIDTH) {
            continue;
         }
         p.hurtServer(level, level.damageSources().mobAttack(boss), SHEAR_DAMAGE);
         // Out of the lane, sideways - the way they should have stepped.
         Vec3 side = off.lengthSqr() < 1.0E-3 ? new Vec3(-s.dir.z, 0.0, s.dir.x) : off;
         push(p, side, SHEAR_PUSH);
         Fx.clash(level, ParticleTypes.CRIT, p.position().add(0.0, 1.0, 0.0), s.dir, GALE_WHITE);
      }
   }

   private static void landEye(ServerLevel level, Mob boss, Fight fight, Strike s) {
      Fx.shockwave(level, ParticleTypes.GUST, s.at, EYE_REACH, STORM);
      Fx.nova(level, ParticleTypes.CLOUD, s.at, EYE_REACH * 0.7, SKY);
      Fx.flare(level, ParticleTypes.END_ROD, s.at.add(0.0, 2.0, 0.0), 2.4, GALE_WHITE);
      // The wall throwing itself outward from the edge of the calm.
      gustRing(level, s.at, EYE_SAFE, EYE_REACH - EYE_SAFE, 8, false, STORM);
      level.playSound(null, s.at.x, s.at.y, s.at.z, SoundEvents.BREEZE_WIND_CHARGE_BURST, SoundSource.HOSTILE, 2.2F, 0.6F);
      level.playSound(null, s.at.x, s.at.y, s.at.z, SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.HOSTILE, 0.9F, 1.2F);
      for (ServerPlayer p : playersNear(level, s.at, EYE_REACH + 4.0)) {
         double dx = p.getX() - s.at.x;
         double dz = p.getZ() - s.at.z;
         double flat = Math.sqrt(dx * dx + dz * dz);
         if (flat <= EYE_SAFE || flat > EYE_REACH || Math.abs(p.getY() - s.at.y) > 8.0) {
            continue;
         }
         p.hurtServer(level, level.damageSources().mobAttack(boss), EYE_DAMAGE);
         push(p, new Vec3(dx, 0.0, dz), EYE_PUSH);
      }
   }

   // ------------------------------------------------------------------ pending

   private static void tickPending(ServerLevel level, Mob boss, Fight fight) {
      if (fight.pending.isEmpty()) {
         return;
      }
      for (Pending pending : new ArrayList<>(fight.pending)) {
         if (pending.drift != null) {
            if ("tornado".equals(pending.kind)) {
               steerTornado(level, boss, pending);
               pending.pos = pending.pos.add(pending.drift.scale(TORNADO_SPEED));
            } else {
               pending.pos = pending.pos.add(pending.drift.scale(0.22));
            }
         }
         if (pending.fuse > 0) {
            pending.fuse--;
            draw(level, boss, fight, pending, true);
            if (pending.fuse > 0) {
               continue;
            }
            land(level, boss, fight, pending);
            if (pending.life <= 0) {
               fight.pending.remove(pending);
            }
            continue;
         }
         draw(level, boss, fight, pending, false);
         if (pending.life > 0 && --pending.life <= 0) {
            expire(level, boss, fight, pending);
            fight.pending.remove(pending);
         } else if (pending.life <= 0) {
            // A fuse-less, life-less entry is a bookkeeping slip, never a hazard: drop it rather
            // than let it sit in the list for the rest of the fight.
            fight.pending.remove(pending);
         }
      }
   }

   /**
    * A twister's wander: every {@link #TORNADO_WANDER_TICKS} it picks a new heading, and if it has
    * strayed past {@link #TORNADO_LEASH} from him it heads back. It used to keep its first heading
    * for its whole life, which at its old speed carried it forty blocks out of the arena.
    */
   private static void steerTornado(ServerLevel level, Mob boss, Pending pending) {
      if (pending.life % TORNADO_WANDER_TICKS != 0) {
         return;
      }
      Vec3 home = new Vec3(boss.getX() - pending.pos.x, 0.0, boss.getZ() - pending.pos.z);
      if (home.length() > TORNADO_LEASH) {
         pending.drift = home.normalize();
      } else {
         Vec3 next = pending.drift.add(randomFlat().scale(0.9));
         pending.drift = next.lengthSqr() < 1.0E-4 ? randomFlat() : new Vec3(next.x, 0.0, next.z).normalize();
      }
      pending.pos = floorUnder(level, pending.pos.add(0.0, 2.0, 0.0));
      // The client column is re-sent at each turn, so it follows the floor hazard it draws.
      // Both halves of the funnel: the spiral climbing it and the vortex turning at its foot.
      Fx.spiral(level, ParticleTypes.CLOUD, pending.pos, TORNADO_HEIGHT, TORNADO_WANDER_TICKS + 2, GALE_WHITE);
      Fx.vortex(level, ParticleTypes.SMALL_GUST, pending.pos, TORNADO_RADIUS, TORNADO_WANDER_TICKS + 2, SKY);
   }

   /**
    * One tick of a lingering wind or a fused mark: the push or pull it applies, and its look.
    *
    * <p>The look is split in two. The modded client got its shape when the thing was cast (a rune
    * circle, a spiral, a vortex) or gets it on a slow refresh here; the per-tick particles are the
    * vanilla client's version only, thinned, and never the gust emitters that used to make up most
    * of the lag report.
    */
   private static void draw(ServerLevel level, Mob boss, Fight fight, Pending pending, boolean tell) {
      Vec3 p = pending.pos;
      int age = tell ? pending.fuse : pending.life;
      switch (pending.kind) {
         case "gust" -> {
            if (age % 4 == 0) {
               vSphere(level, p.add(0.0, 0.8, 0.0), 1.4, ParticleTypes.SMALL_GUST);
            }
         }
         case "column" -> {
            if (age % 4 == 0) {
               vRing(level, p, COLUMN_RADIUS, TORNADO_RING_POINTS, ParticleTypes.SMALL_GUST, 0.2);
            }
         }
         case "skyfall" -> {
            if (pending.fuse == 8) {
               // The charge itself, seen falling for the last few ticks so the hit is never a surprise.
               Fx.comet(level, ParticleTypes.CLOUD, p.add(0.0, 20.0, 0.0), p.add(0.0, 0.4, 0.0), 8, GALE_WHITE);
            }
            if (age % 4 == 0) {
               vRing(level, p.add(0.0, 0.2, 0.0), 2.4, 20, ParticleTypes.END_ROD, 0.05);
               double height = Math.max(1.0, pending.fuse * 0.9);
               Fx.vanilla(level, ParticleTypes.CLOUD, p.x, p.y + height, p.z, 2, 0.3, 0.3, 0.3, 0.01);
            }
         }
         case "current" -> {
            if (age % 10 == 0) {
               // A current is wind running along the floor, so it is drawn as one: a gust the
               // length of its push, re-sent as the last one blows out.
               Fx.gust(level, ParticleTypes.GUST, p.add(0.0, 0.4, 0.0), pending.drift, 9.0, SKY);
            }
            if (age % 4 == 0) {
               vBeam(level, p.add(0.0, 0.2, 0.0), p.add(pending.drift.scale(9.0)).add(0.0, 0.2, 0.0), 1.1, ParticleTypes.CLOUD);
            }
            for (ServerPlayer q : playersNear(level, p, 3.2)) {
               q.setDeltaMovement(q.getDeltaMovement().add(pending.drift.scale(CURRENT_PUSH)));
               q.hurtMarked = true;
            }
         }
         case "tunnel" -> {
            if (age % 30 == 0) {
               Fx.spiral(level, ParticleTypes.SMALL_GUST, p, 3.0, 30, SKY);
            }
            if (age % 15 == 0) {
               Fx.gust(level, ParticleTypes.GUST, p.add(0.0, 1.0, 0.0), pending.drift, 4.0, GALE_WHITE);
            }
            if (age % 4 == 0) {
               vSphere(level, p.add(0.0, 1.0, 0.0), 2.2, ParticleTypes.SMALL_GUST);
            }
            for (ServerPlayer q : playersNear(level, p, 2.6)) {
               q.setDeltaMovement(
                  q.getDeltaMovement().add(pending.drift.scale(TUNNEL_PUSH)).add(0.0, liftFor(0.3, fight.airTicks.getOrDefault(q.getUUID(), 0)), 0.0)
               );
               q.hurtMarked = true;
            }
         }
         case "wind" -> {
            if (age % 20 == 0) {
               // Each wind visibly leaning on the room toward him.
               Fx.gust(level, ParticleTypes.GUST, p.add(0.0, 1.2, 0.0), boss.position().subtract(p), 10.0, SKY);
            }
            if (age % 4 == 0) {
               vColumn(level, p, 8.0, ParticleTypes.CLOUD, 2);
            }
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
            if (age % 4 == 0) {
               double shrink = 1.0 - (pending.life / (double)DEAD_AIR_TICKS) * 0.35;
               vRing(level, p, DEAD_AIR_RADIUS * shrink, 32, ParticleTypes.CLOUD, 0.4);
               vSphere(level, p.add(0.0, 1.0, 0.0), DEAD_AIR_RADIUS * 0.55, ParticleTypes.END_ROD);
            }
            if (pending.life == 20) {
               Fx.ring(level, ParticleTypes.END_ROD, p.add(0.0, 0.2, 0.0), DEAD_AIR_RADIUS, STORM);
               level.playSound(null, p.x, p.y, p.z, SoundEvents.BREEZE_INHALE, SoundSource.HOSTILE, 1.4F, 1.4F);
            }
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
            if (age % 4 == 0) {
               vSphere(level, p.add(0.0, 2.0, 0.0), 6.0 + (60 - pending.life) * 0.1, ParticleTypes.CLOUD);
            }
            for (ServerPlayer q : playersNear(level, p, 22.0)) {
               pull(q, p, 0.30);
            }
         }
         case "tornado" -> {
            // The shape: a column the whole way up, a ring on the floor, and the spiral that says
            // which way it is turning - for the vanilla client, every other tick.
            if (age % 3 == 0) {
               vColumn(level, p, TORNADO_HEIGHT, ParticleTypes.CLOUD, 1);
               vRing(level, p.add(0.0, 0.2, 0.0), TORNADO_RADIUS, TORNADO_RING_POINTS, ParticleTypes.SMALL_GUST, 0.4);
               vanillaOnly(() -> {
                  for (int i = 0; i < 8; i++) {
                     double a = i * (Math.PI * 2.0 / 8.0) + pending.life * 0.22;
                     double r = TORNADO_RADIUS * (0.35 + 0.65 * ((i % 4) / 3.0));
                     Vec3 q = p.add(Math.cos(a) * r, 1.0 + (i % 5) * 1.6, Math.sin(a) * r);
                     Fx.vanilla(level, ParticleTypes.SMALL_GUST, q.x, q.y, q.z, 1, 0.0, 0.0, 0.0, 0.0);
                  }
               });
            }
            // Then the wind itself: being inside it is being dragged in and held up - through the
            // lift cap, so it cannot carry a body up for its whole ten seconds - and standing
            // there pays every ten ticks rather than every tick.
            for (ServerPlayer q : playersNear(level, p, TORNADO_RADIUS)) {
               pull(q, p, TORNADO_PULL);
               q.setDeltaMovement(q.getDeltaMovement().add(0.0, liftFor(TORNADO_LIFT, fight.airTicks.getOrDefault(q.getUUID(), 0)), 0.0));
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
            Vec3 c = pending.pos;
            double span = HURRICANE_RADIUS * (0.55 + 0.45 * (1.0 - pending.life / (double)HURRICANE_TICKS));
            if (age % 20 == 0) {
               Fx.ring(level, ParticleTypes.GUST, c.add(0.0, 0.4, 0.0), span, STORM);
               // The storm's spin: gusts running round the rim, tangent to it.
               double turn = -pending.life * 0.18;
               for (int i = 0; i < 4; i++) {
                  double a = turn + i * Math.PI * 0.5;
                  Vec3 rim = c.add(Math.cos(a) * span * 0.75, 1.5, Math.sin(a) * span * 0.75);
                  Fx.gust(level, ParticleTypes.GUST, rim, new Vec3(Math.sin(a), 0.0, -Math.cos(a)), 10.0, GALE_WHITE);
               }
               Fx.lightning(
                  level, ParticleTypes.END_ROD, c.add(randomFlat().scale(span * 0.7)).add(0.0, 18.0, 0.0), floorUnder(level, c.add(randomFlat().scale(span * 0.7))), GALE_WHITE
               );
            }
            if (age % 4 == 0) {
               vRing(level, c, span, HURRICANE_RING_POINTS, ringParticle(), 0.5);
               vRing(level, c, span * 0.7, 32, ParticleTypes.CLOUD, 6.0);
               vColumn(level, c, 12.0, ParticleTypes.CLOUD, 1);
               vanillaOnly(() -> {
                  for (int i = 0; i < 8; i++) {
                     double a = i * (Math.PI * 2.0 / 8.0) - pending.life * 0.18;
                     Vec3 q = c.add(Math.cos(a) * span * 0.6, 2.0 + (i % 4) * 2.0, Math.sin(a) * span * 0.6);
                     Fx.vanilla(level, ParticleTypes.SMALL_GUST, q.x, q.y, q.z, 1, 0.0, 0.0, 0.0, 0.0);
                  }
               });
            }
            for (ServerPlayer q : playersNear(level, c, HURRICANE_RADIUS)) {
               pull(q, c, HURRICANE_PULL);
               q.setDeltaMovement(q.getDeltaMovement().add(0.0, liftFor(HURRICANE_LIFT, fight.airTicks.getOrDefault(q.getUUID(), 0)), 0.0));
               q.hurtMarked = true;
               if (pending.life % 20 == 0) {
                  q.hurtServer(level, level.damageSources().mobAttack(boss), HURRICANE_DAMAGE);
                  q.sendOverlayMessage(Component.literal("§f§lHURRICANE §8" + (pending.life / 20) + "s §7Walk against it."));
               }
            }
            // Once every two seconds, not every half second: the storm is a drone, and a drone
            // retriggered that fast stops reading as weather and starts reading as a stuck sound.
            if (pending.life % 40 == 0) {
               level.playSound(null, c.x, c.y, c.z, SoundEvents.BREEZE_IDLE_AIR, SoundSource.HOSTILE, 2.0F, 0.5F);
            }
         }
         default -> {
         }
      }
   }

   /**
    * Runs vanilla-only particles: the modded client already has the Fx shape for this. Delegates
    * to {@link Fx#vanillaOnly} so a scope opened here nests safely inside a template's own.
    *
    * <p>Only {@code FfVfx.particles} calls inside it are routed - which is why the shapes below
    * ({@link #vRing}, {@link #vSphere}, {@link #vColumn}, {@link #vBeam}) exist. These used to be
    * {@code BossVfx} shapes, and BossVfx sends with a per-player {@code level.sendParticles}: that
    * never goes through the transport, so it reached modded clients too and piled vanilla puffs
    * on top of every custom tornado, ring and current - the exact bug the scopes were meant to fix.
    */
   private static void vanillaOnly(Runnable draw) {
      Fx.vanillaOnly(draw);
   }

   /** A vanilla-only ring of {@code points} sites (at most 36), sent as eight clustered puffs. */
   private static void vRing(ServerLevel level, Vec3 c, double radius, int points, net.minecraft.core.particles.ParticleOptions p, double y) {
      if (radius <= 0.0 || points <= 0) {
         return;
      }
      int sites = Math.min(36, points);
      int step = Math.max(1, sites / 8);
      vanillaOnly(() -> {
         for (int i = 0; i < sites; i += step) {
            double a = (Math.PI * 2.0 * i) / sites;
            Fx.vanilla(
               level, p, c.x + Math.cos(a) * radius, c.y + y, c.z + Math.sin(a) * radius, step, 0.08, 0.04, 0.08, 0.01
            );
         }
      });
   }

   /** A vanilla-only shell of fourteen puffs round {@code c}. */
   private static void vSphere(ServerLevel level, Vec3 c, double radius, net.minecraft.core.particles.ParticleOptions p) {
      vanillaOnly(() -> {
         int n = 14;
         for (int i = 0; i < n; i++) {
            // A Fibonacci spiral over the sphere, so fourteen points read as a shell, not a cluster.
            double y = 1.0 - 2.0 * (i + 0.5) / n;
            double ring = Math.sqrt(Math.max(0.0, 1.0 - y * y));
            double a = i * 2.399963;
            Fx.vanilla(
               level, p, c.x + Math.cos(a) * ring * radius, c.y + y * radius, c.z + Math.sin(a) * ring * radius, 1, 0.05, 0.05, 0.05, 0.01
            );
         }
      });
   }

   /** A vanilla-only column, one puff every block and a half up to {@code height}. */
   private static void vColumn(ServerLevel level, Vec3 base, double height, net.minecraft.core.particles.ParticleOptions p, int each) {
      vanillaOnly(() -> {
         int n = Math.max(2, Math.min(12, (int)(height / 1.5)));
         for (int i = 0; i <= n; i++) {
            Fx.vanilla(level, p, base.x, base.y + height * i / n, base.z, Math.max(1, each), 0.3, 0.1, 0.3, 0.02);
         }
      });
   }

   /** A vanilla-only line from {@code from} to {@code to}, a puff a block, at most twenty-four. */
   private static void vBeam(ServerLevel level, Vec3 from, Vec3 to, double thickness, net.minecraft.core.particles.ParticleOptions p) {
      vanillaOnly(() -> {
         int n = Math.max(2, Math.min(24, (int)from.distanceTo(to)));
         for (int i = 0; i <= n; i++) {
            Vec3 q = from.lerp(to, i / (double)n);
            Fx.vanilla(level, p, q.x, q.y, q.z, 1, thickness * 0.3, thickness * 0.3, thickness * 0.3, 0.0);
         }
      });
   }

   /**
    * The portal-wind cue, open or shut. {@code Fx.wormhole}'s flag reads backwards (its
    * {@code closing=true} draws the arrival), so every call here names what it wants instead:
    * {@code open} is the swirl widening out of a flash, shut is the swirl drawing in and sealing.
    */
   private static void portalWind(ServerLevel level, net.minecraft.core.particles.ParticleOptions p, Vec3 at, boolean open, int color) {
      Fx.shape(level, com.fortuneandfavors.net.FfVfx.WORMHOLE, p, at, Vec3.ZERO, 0.0, open ? 1.0 : 0.0, color);
   }

   /**
    * {@code count} gusts thrown out of a ring of {@code from} blocks round {@code center} (inward
    * when {@code inward}), {@code reach} blocks long - the shape of the whole arena's wind turning
    * at once. Kept to six or eight: each is one cue, and the fallback is one bounded line.
    */
   private static void gustRing(ServerLevel level, Vec3 center, double from, double reach, int count, boolean inward, int color) {
      double turn = RANDOM.nextDouble() * Math.PI * 2.0;
      for (int i = 0; i < count; i++) {
         double a = turn + Math.PI * 2.0 * i / count;
         Vec3 out = new Vec3(Math.cos(a), 0.0, Math.sin(a));
         Vec3 start = inward ? center.add(out.scale(from + reach)) : center.add(out.scale(from));
         Fx.gust(level, ParticleTypes.GUST, start.add(0.0, 1.2, 0.0), inward ? out.scale(-1.0) : out, reach, color);
      }
   }

   private static void land(ServerLevel level, Mob boss, Fight fight, Pending pending) {
      Vec3 p = pending.pos;
      switch (pending.kind) {
         case "gust" -> {
            Fx.shockwave(level, ParticleTypes.GUST, p, 3.4, SKY);
            Fx.starburst(level, ParticleTypes.CLOUD, p.add(0.0, 1.0, 0.0), 3.0, GALE_WHITE);
            // The footprint he left goes off: a pressure ring punched straight up out of it.
            Fx.sonicRing(level, ParticleTypes.GUST, p.add(0.0, 0.3, 0.0), new Vec3(0.0, 1.0, 0.0), 6.0, 10, SKY);
            level.playSound(null, p.x, p.y, p.z, SoundEvents.BREEZE_WIND_CHARGE_BURST, SoundSource.HOSTILE, 1.4F, 1.1F);
            for (ServerPlayer q : playersNear(level, p, 3.4)) {
               q.hurtServer(level, level.damageSources().mobAttack(boss), COLUMN_DAMAGE * 0.7F);
               Vec3 away = q.position().subtract(p);
               push(q, new Vec3(away.x, 0.0, away.z), 2.0);
            }
         }
         case "column" -> {
            Fx.geyser(level, ParticleTypes.CLOUD, p, 7.0, GALE_WHITE);
            Fx.gust(level, ParticleTypes.GUST, p, new Vec3(0.0, 1.0, 0.0), 7.0, SKY);
            Fx.shockwave(level, ParticleTypes.GUST, p, COLUMN_RADIUS + 0.6, SKY);
            level.playSound(null, p.x, p.y, p.z, SoundEvents.BREEZE_WHIRL, SoundSource.HOSTILE, 1.4F, 0.8F);
            for (ServerPlayer q : playersNear(level, p, COLUMN_RADIUS)) {
               q.hurtServer(level, level.damageSources().mobAttack(boss), COLUMN_DAMAGE);
               q.setDeltaMovement(q.getDeltaMovement().add(0.0, liftFor(1.4, fight.airTicks.getOrDefault(q.getUUID(), 0)), 0.0));
               q.hurtMarked = true;
            }
         }
         case "skyfall" -> {
            Fx.shockwave(level, ParticleTypes.GUST, p, 3.4, STORM);
            Fx.flare(level, ParticleTypes.END_ROD, p.add(0.0, 0.6, 0.0), 1.4, GALE_WHITE);
            level.playSound(null, p.x, p.y, p.z, SoundEvents.BREEZE_WIND_CHARGE_BURST, SoundSource.HOSTILE, 1.5F, 0.9F);
            for (ServerPlayer q : playersNear(level, p, 3.0)) {
               q.hurtServer(level, level.damageSources().mobAttack(boss), SKYFALL_DAMAGE);
               q.setDeltaMovement(q.getDeltaMovement().add(0.0, liftFor(1.2, fight.airTicks.getOrDefault(q.getUUID(), 0)), 0.0));
               q.hurtMarked = true;
            }
         }
         default -> {
         }
      }
   }

   /** What happens when a lingering thing finally runs out - only three of them do anything. */
   private static void expire(ServerLevel level, Mob boss, Fight fight, Pending pending) {
      Vec3 at = pending.pos;
      switch (pending.kind) {
         case "deadair" -> {
            Fx.shockwave(level, ParticleTypes.GUST, at, DEAD_AIR_RADIUS + 2.0, SKY);
            Fx.starburst(level, ParticleTypes.CLOUD, at.add(0.0, 1.0, 0.0), 6.0, GALE_WHITE);
            level.playSound(null, at.x, at.y, at.z, SoundEvents.BREEZE_WIND_CHARGE_BURST, SoundSource.HOSTILE, 2.0F, 0.7F);
            for (ServerPlayer q : playersNear(level, at, DEAD_AIR_RADIUS)) {
               Vec3 away = q.position().subtract(at);
               push(q, new Vec3(away.x, 0.0, away.z), DEAD_AIR_RELEASE);
            }
         }
         case "vacuum" -> {
            Fx.flare(level, ParticleTypes.END_ROD, at.add(0.0, 2.0, 0.0), 2.6, GALE_WHITE);
            Fx.shockwave(level, ParticleTypes.GUST, at, 20.0, STORM);
            portalWind(level, ParticleTypes.SMALL_GUST, at.add(0.0, 2.0, 0.0), false, SKY);
            gustRing(level, floorUnder(level, at), 2.0, 14.0, 6, false, GALE_WHITE);
            level.playSound(null, at.x, at.y, at.z, SoundEvents.BREEZE_WIND_CHARGE_BURST, SoundSource.HOSTILE, 2.0F, 0.5F);
            for (ServerPlayer q : playersNear(level, at, 20.0)) {
               Vec3 away = q.position().subtract(at);
               push(q, new Vec3(away.x, 0.0, away.z), 2.8);
               q.hurtServer(level, level.damageSources().mobAttack(boss), 9.0F);
            }
         }
         // The funnel unwinding: the swirl draws in and seals, and its last breath blows out.
         case "tornado" -> {
            portalWind(level, ParticleTypes.CLOUD, at.add(0.0, 2.0, 0.0), false, GALE_WHITE);
            Fx.shockwave(level, ParticleTypes.SMALL_GUST, at, TORNADO_RADIUS, SKY);
         }
         case "hurricane" -> releaseHurricane(level, boss, fight);
         default -> {
         }
      }
   }

   // --------------------------------------------------------------------- loot

   /**
    * The death ceremony. He does not fall: the killing blow leaves him hanging in the air while
    * the wind that made him comes loose. He rises a little and shrinks back toward the wisp he
    * arrived as, the circle under him turns, shards of air break off on a beat that speeds up, and
    * the inhale climbs in pitch. At the end he pulls everything in for one tick and lets it all go:
    * a flash, a starburst, a shockwave across the arena and a soft shove (no damage) - the fight's
    * own move, used as its signature.
    */
   private static void tickDeath(MinecraftServer server, Mob boss, Fight fight) {
      ServerLevel level = (ServerLevel)boss.level();
      fight.deathTicks--;
      double progress = 1.0 - Math.max(0, fight.deathTicks) / (double)DEATH_CEREMONY_TICKS;
      Vec3 at = boss.position();
      boss.setDeltaMovement(Vec3.ZERO);
      if (fight.deathTicks > 16) {
         boss.setPos(at.x, at.y + 0.03, at.z);
         attribute(boss, Attributes.SCALE, FULL_SCALE - (FULL_SCALE - 1.0) * progress);
      }
      int beat = fight.deathTicks > 40 ? 16 : fight.deathTicks > 16 ? 8 : 4;
      if (fight.deathTicks % beat == 0 && fight.deathTicks > 0) {
         Vec3 heart = at.add(0.0, 1.6, 0.0);
         Fx.shatter(level, ParticleTypes.END_ROD, heart, 0.8 + progress * 1.6, GALE_WHITE);
         Fx.ring(level, ParticleTypes.CLOUD, at.add(0.0, 0.5, 0.0), 3.0 + progress * 8.0, SKY);
         level.playSound(null, at.x, at.y, at.z, SoundEvents.BREEZE_DEFLECT, SoundSource.HOSTILE, 1.3F, 0.5F + (float)progress);
      }
      if (fight.deathTicks == 40) {
         Fx.lightning(level, ParticleTypes.END_ROD, at.add(0.0, 20.0, 0.0), at.add(0.0, 2.0, 0.0), GALE_WHITE);
         level.playSound(null, at.x, at.y, at.z, SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.HOSTILE, 1.0F, 1.4F);
         overlayNear(level, boss, 90.0, "§8He's coming apart.");
      }
      if (fight.deathTicks == 16) {
         // The last breath in: everything turns toward him before it is thrown out.
         Fx.vortex(level, ParticleTypes.CLOUD, at, 12.0, 16, STORM);
         // The swirl drawing in on him (shut, not open - the last breath is an inhale).
         portalWind(level, ParticleTypes.SMALL_GUST, at.add(0.0, 2.0, 0.0), false, GALE_WHITE);
         gustRing(level, floorUnder(level, at), 2.0, 10.0, 6, true, SKY);
         level.playSound(null, at.x, at.y, at.z, SoundEvents.BREEZE_INHALE, SoundSource.HOSTILE, 2.2F, 0.5F);
      }
      if (fight.deathTicks % 3 == 0) {
         vSphere(level, at.add(0.0, 1.0, 0.0), 2.0 + progress * 3.0, ParticleTypes.CLOUD);
      }
      if (fight.deathTicks > 0) {
         return;
      }
      Vec3 heart = at.add(0.0, 1.6, 0.0);
      Fx.flare(level, ParticleTypes.END_ROD, heart, 4.0, GALE_WHITE);
      Fx.starburst(level, ParticleTypes.CLOUD, heart, 12.0, SKY);
      Fx.shockwave(level, ParticleTypes.GUST, floorUnder(level, at), 20.0, STORM);
      Fx.nova(level, ParticleTypes.CLOUD, at, 10.0, GALE_WHITE);
      Fx.petals(level, ParticleTypes.WHITE_ASH, heart, 6.0, 60, SKY);
      // And the wind he was made of, thrown out across the arena and up into the sky.
      Fx.starburst(level, ParticleTypes.END_ROD, heart, 6.0, GALE_WHITE);
      Fx.sonicRing(level, ParticleTypes.GUST, heart, new Vec3(0.0, 1.0, 0.0), 16.0, 20, GALE_WHITE);
      gustRing(level, floorUnder(level, at), 2.0, 16.0, 8, false, SKY);
      for (ServerPlayer p : playersNear(level, at, 12.0)) {
         Vec3 away = p.position().subtract(at);
         push(p, new Vec3(away.x, 0.0, away.z), 1.2);
      }
      level.playSound(null, at.x, at.y, at.z, SoundEvents.BREEZE_WIND_CHARGE_BURST, SoundSource.HOSTILE, 2.4F, 0.5F);
      level.playSound(null, at.x, at.y, at.z, SoundEvents.BREEZE_DEATH, SoundSource.HOSTILE, 2.0F, 0.5F);
      level.playSound(null, at.x, at.y, at.z, ModSounds.BOSS_DEATH, SoundSource.HOSTILE, 2.0F, 1.1F);
      overlayNear(level, boss, 90.0, "§f§lTHE GALE WARDEN FALLS §8The wind drops.");
      onBossDeath(level, boss);
      if (boss.isAlive()) {
         boss.hurtServer(level, level.damageSources().generic(), boss.getMaxHealth() * 4.0F + 100.0F);
      }
      if (boss.isAlive()) {
         // Belt and braces: whatever refused the blow, the body does not outlive its fight.
         boss.discard();
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
      fight.strikes.clear();
      fight.pending.clear();
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

   /** The floor straight under a point, for anything drawn or marked on the ground. */
   private static Vec3 floorUnder(ServerLevel level, Vec3 at) {
      return new Vec3(at.x, BossGrounding.groundY(level, at.x, at.z, at.y), at.z);
   }

   private static Vec3 randomFlat() {
      Vec3 v = new Vec3(RANDOM.nextDouble() - 0.5, 0.0, RANDOM.nextDouble() - 0.5);
      return v.lengthSqr() < 1.0E-4 ? new Vec3(1.0, 0.0, 0.0) : v.normalize();
   }

   /** A player his winds may touch: alive, in survival or adventure, in this level, and real. */
   private static boolean fair(ServerPlayer p, ServerLevel level) {
      return p != null && p.isAlive() && !p.isCreative() && !p.isSpectator() && p.level() == level && !BossManager.isFakePlayer(p);
   }

   /**
    * Every fair player within his reach, which is who the momentum systems and the whole-arena
    * moves read. This used to be every player in the level, so Sky Launch and No Ground threw
    * people a thousand blocks away who had never seen him.
    */
   private static List<ServerPlayer> participants(ServerLevel level, Mob boss) {
      return playersNear(level, boss.position(), SEEK_RANGE);
   }

   private static List<ServerPlayer> playersNear(ServerLevel level, Vec3 pos, double radius) {
      List<ServerPlayer> out = new ArrayList<>();
      double r2 = radius * radius;
      for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
         if (fair(p, level) && p.distanceToSqr(pos.x, pos.y, pos.z) <= r2) {
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
      for (ServerPlayer p : playersNear(level, boss.position(), radius)) {
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
    * The action bar, for everyone near him. This is the only text the fight has.
    *
    * <p>He had a voice - a taunt every eleven seconds, a line for each phase, a quotation wrapped
    * around most of his moves - and all of it arrived in chat. A fight that describes itself is a
    * fight the player reads instead of watches, so he says nothing. What is left is the move's
    * name and one grey narrator line on how to answer it, on the action bar where it does not
    * scroll the chat, and anything carrying a quotation mark is still refused here, at the one
    * funnel every line passes through. This was a no-op for a while, which took the hints away
    * with the speech and left every move unreadable.
    */
   private static void overlayNear(ServerLevel level, Mob boss, double range, String message) {
      if (message == null || message.isEmpty() || message.indexOf('"') >= 0) {
         return;
      }
      Component line = Component.literal(message);
      double r2 = range * range;
      for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
         if (p.level() == level && !BossManager.isFakePlayer(p) && p.distanceToSqr(boss) <= r2) {
            p.sendOverlayMessage(line);
         }
      }
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
