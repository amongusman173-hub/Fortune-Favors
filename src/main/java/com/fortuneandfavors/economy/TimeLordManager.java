package com.fortuneandfavors.economy;

import com.fortuneandfavors.ModItems;
import com.fortuneandfavors.ModSounds;
import com.fortuneandfavors.util.Chat;
import com.fortuneandfavors.util.Safe;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
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
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.clock.WorldClocks;
import net.minecraft.world.BossEvent.BossBarOverlay;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.skeleton.Skeleton;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * <b>The Time Lord</b> - the raid boss summoned by the Space-Time Rift, plus the
 * three legendaries his loot box spins (Pocket-Watch, Chrono Shard, Hourglass of
 * Haste).
 *
 * <p>This lives in its own file rather than inside {@code BossManager} because the
 * Time Lord is completely scripted: {@code BossManager} drives its bosses off
 * vanilla mob AI plus per-boss state machines, and this fight is the opposite -
 * the entity is a {@code setNoAi(true)} puppet whose every action the server tick
 * performs. The two share nothing, so they share no code.
 *
 * <h2>Time stop and the server tick rate</h2>
 * His signature move is a <b>genuine</b> freeze: {@code ServerTickRateManager.setFrozen(true)},
 * so the whole world really does stop - mobs, redstone, falling blocks, everything
 * but the fight script. That is the one dangerous API in this file, so it is
 * fenced four ways:
 * <ol>
 *   <li><b>Bounded.</b> Every stop has a hard duration (max {@link #MAX_FREEZE_TICKS})
 *       and a hard wall-clock deadline; there is no code path that freezes without
 *       scheduling its own release.</li>
 *   <li><b>Owned.</b> We only unfreeze what we froze. If the server was already
 *       frozen (an operator ran {@code /tick freeze}) we track that and never
 *       touch it.</li>
 *   <li><b>Wall-clock backstop.</b> A daemon thread queues the release through
 *       {@code MinecraftServer.execute}, so the world thaws even if the tick
 *       handler itself never runs again.</li>
 *   <li><b>Never during the self-test.</b> A frozen server would hang
 *       {@code ./gradlew ffSelfTest}, so the freeze degrades to a no-op there.</li>
 * </ol>
 *
 * <p>The <b>Pocket-Watch</b> uses the same freeze, which is safe because the
 * frozen moment still runs the server tick and still accepts packets: the wielder
 * walks through it and keeps firing, while arrows loosed during it hang in the
 * air until the world resumes. Everyone else caught in the bubble is pinned, and
 * every point of damage dealt inside is <i>banked</i> rather than lost, then paid
 * out in one hit the moment time starts again.
 */
public final class TimeLordManager {
   /** Entity tag marking a Time Lord. Kept in sync with {@link #isTimeLord(Entity)}. */
   private static final String TAG = "ff_time_lord";
   /** Marks an entity as suspended by a time stop, so we can restore it exactly. */
   private static final String STASIS_TAG = "ff_time_stasis";
   private static final String BOSS_NAME = "\u00a7d\u00a7lThe Time Lord";
   private static final String SAY = "\u00a7dThe Time Lord\u00a7r\u00a77 \u203a \u00a7f";

   private static final int SPACETIME_RIFT_MODEL = 4550210;
   private static final int TIME_LORD_BOX_MODEL = 4550211;

   /** Fight tuning. Every number here is a balance knob; the safety caps are not. */
   private static final double MAX_HEALTH = 600.0;
   /** His own hand, and it has to hurt: twelve was a boss that could be out-traded. */
   private static final double ATTACK_DAMAGE = 16.0;
   private static final int STOP_TICKS = 60;
   private static final int GRAND_STOP_TICKS = 100;
   /** Absolute ceiling on a single freeze, whatever else is configured. */
   private static final int MAX_FREEZE_TICKS = 140;
   private static final int STOP_COOLDOWN = 300;
   private static final int TELEPORT_COOLDOWN = 160;
   private static final int RIFT_COOLDOWN = 200;
   private static final int VOLLEY_COOLDOWN = 120;
   /** "The same second, again": how long the fight is wound back, and how rarely. */
   private static final int LOOP_TICKS = 160;
   private static final int LOOP_SAMPLE_EVERY = 2;
   private static final int LOOP_COOLDOWN = 900;
   private static final int LOOP_WINDUP = 44;
   /** He can only take back a third of his own health with one rewind. */
   private static final float LOOP_HEAL_CAP = 0.35F;
   /**
    * How far you have to have moved during the tell for the rewind to have
    * somewhere to put you. A player who stands still is not sent anywhere - the
    * whole answer to this move is to stop moving, which is the one thing nobody
    * tries against a boss that has spent the fight punishing them for standing
    * still.
    */
   private static final double LOOP_STILL_BLOCKS = 3.0;
   /** How far the fight reaches before the participants count as gone. */
   private static final double ARENA_RADIUS = 80.0;
   /** Teleports are capped per fight so he can never "TP spam" a player. */
   private static final int MAX_TELEPORTS = 12;

   // --- time-stop plumbing -------------------------------------------------

   /** Ticks between his scripted strikes while the world is frozen. */
   private static final int STOP_STRIKE_INTERVAL = 18;
   /** How far a pinned player may drift before we correct them. */
   private static final double PIN_DRIFT = 0.4;
   /** Knockback gained per point of damage taken during a stop... */
   private static final double KNOCKBACK_PER_DAMAGE = 0.11;
   /** ...and the ceiling on both the damage that counts and the shove itself. */
   private static final float MAX_KNOCKBACK_DAMAGE = 24.0F;
   private static final double MAX_KNOCKBACK = 2.8;
   /** Identical boss lines inside this window are spoken once, not three times. */
   private static final int LINE_DEDUPE_TICKS = 80;

   /**
    * When he stops trading blows and starts judging.
    *
    * <p>This was one percent - six hearts of a six hundred heart bar - and that is not a
    * phase, it is a coin flip. A raid that landed one big hit took him from above the
    * threshold straight through it, so the whole of his last stand was a mechanic most players
    * never saw once. Eight percent is a beat the fight can be built around: it arrives with
    * the third phase, there is still health to take off him during it, and the beam is
    * therefore something that happens in every fight rather than a rumour.
    */
   private static final float FINALE_AT = 0.08F;

   /**
    * "The hour that never was": his phase-three ring.
    *
    * <p>The one move in the fight that is answered by a <b>direction</b> rather than by a
    * block, a hit or a shield: three seconds of a clock face closing around him, and then
    * everything still inside it is aged, hurled and slowed at once. It is deliberately the
    * loudest thing he does - the ring is drawn on the floor the whole time it is coming, so
    * the counter is to walk out of a circle he has shown you.
    */
   private static final int NOVA_TELL = 60;
   private static final int NOVA_COOLDOWN = 500;
   private static final double NOVA_RANGE = 16.0;
   private static final float NOVA_DAMAGE = 9.0F;
   private static final int NOVA_YEARS = 1;

   /**
    * The finale at {@link #FINALE_AT} of his health. He steps out of the fight for ten seconds, glowing,
    * firing a beam of stolen years at every player, and anyone who cannot land a
    * single hit on him in that window is aged to a skeleton. Landing a hit ends
    * the judgment for that player immediately.
    */
   /**
    * The judgment's length and how often a beam lands.
    *
    * <p>It takes five stacks of aging to be reduced to bone, so the old numbers
    * (a beam every second, five seconds to die) gave a player who was caught out
    * of position almost no room to reach him - and the beams are the only thing
    * that ages you, so the fight could end before anyone worked out the rule.
    * A beam every 1.5s is 7.5 seconds of walking, which is a fight you can lose
    * and understand, rather than one you lose to a stopwatch.
    */
   private static final int FINALE_TICKS = 200;
   private static final int FINALE_AGING_EVERY = 30;
   private static final double FINALE_RANGE = 26.0;
   /**
    * How many years one blast of his aging beam takes.
    *
    * <p>The beam is his headline move in both halves of the fight - the ray he
    * fires across the arena, and the beams of the final judgment - and a blast is
    * worth a full five years, not the two it used to take. Five years is a whole
    * life at the ledger's own threshold, so one beam leaves a player on their
    * last year: the counter goes to 4/5, the overlay says so, and <i>anything</i>
    * that ages them after that finishes it - the next beam, the Aging
    * enchantment, or the judgment's own end. It is deliberately not a one-shot:
    * the beam is a hitscan with no dodge, and a hitscan that kills outright is
    * the unfair thing, not the dangerous one.
    */
   private static final int BEAM_YEARS = 5;
   /** What the ordinary aging ray takes in phases one and two. The finale's beam keeps BEAM_YEARS. */
   private static final int RAY_YEARS = 1;
   /** Hard ceiling on junk thrown by a rift, so no volley can one-shot. */
   private static final float MAX_ARROW_DAMAGE = 4.0F;
   private static final int BOMB_COOLDOWN = 180;
   private static final int RAY_COOLDOWN = 240;

   private static final Random RANDOM = new Random();
   private static final Map<UUID, Fight> FIGHTS = new HashMap<>();
   private static final List<Volley> VOLLEYS = new ArrayList<>();
   /** Players held in place by a time stop - the boss's or a Pocket-Watch's. */
   private static final Map<UUID, Pin> PINS = new HashMap<>();
   /** Players thrown by one of his moves: their landing is free for a few seconds. */
   private static final Map<UUID, Integer> NO_FALL = new HashMap<>();
   /** Live Pocket-Watch stops and the bubble each one holds. */
   private static final List<PocketStop> POCKET_STOPS = new ArrayList<>();
   /** Damage a stop swallowed, keyed by victim, paid out when the stop lifts. */
   private static final Map<UUID, Deferred> DEFERRED = new HashMap<>();
   /**
    * Projectiles caught hanging in a stop, with the wall-clock moment they stop
    * counting. While time is held these are knives already in the air: when the
    * stop lifts they land <b>through</b> invulnerability frames, so a salvo fired
    * into a frozen moment all connects instead of only the first arrow doing so.
    */
   private static final Map<UUID, Long> FROZEN_SHOTS = new HashMap<>();
   private static final long SHOT_GRACE_MILLIS = 15_000L;
   /**
    * When each attacker last had a melee swing banked, in wall-clock millis.
    *
    * <p>The stop holds the server tick, and with it the swing cooldown: nothing
    * advances {@code attackStrengthTicker} while the world is frozen, so a player
    * can click as fast as a mouse repeats and every one of those swings reads as
    * fully charged. The bank then pays out a burst nobody earned. This is the
    * clock the stop cannot freeze - the one on the wall - and the charge is
    * measured off it instead.
    */
   private static final Map<UUID, Long> LAST_BANKED_SWING = new HashMap<>();

   /**
    * What a player's blow on another player is worth inside a Pocket-Watch stop.
    *
    * <p>Sixty percent. The stop exists to delay damage, not to delete the defender's
    * chance to trade: a PvP fight that happens inside the bubble used to resolve as one
    * full-strength burst on whoever was standing in it, which made the watch the best
    * duelling item in the game for whoever held it.
    */
   private static final float POCKET_STOP_PVP_FACTOR = 0.6F;

   /** How long a swing takes to charge, in millis, for the item in the attacker's hand. */
   private static double swingIntervalMillis(ServerPlayer attacker) {
      double speed = 1.6;
      try {
         AttributeInstance attr = attacker.getAttribute(Attributes.ATTACK_SPEED);
         if (attr != null) {
            speed = Math.max(0.1, attr.getValue());
         }
      } catch (Throwable ignored) {
      }
      return Math.max(50.0, 1000.0 / speed);
   }

   /**
    * Brings a banked swing down to the damage that swing was actually worth.
    *
    * <p>A projectile - an arrow, a thrown trinket - is not a swing and is passed
    * through untouched. A melee blow is scaled by how far the attacker's own swing
    * had charged when it landed, using vanilla's own curve ({@code 0.2 + c^2 * 0.8}),
    * so an unhurried hit banks exactly what it dealt and a flurry of unmounted
    * clicks banks a flurry of uncharged taps. The first swing of a stop is left
    * alone: there was no previous one to measure against, and holding it against
    * the attacker would punish them for the stop itself.
    */
   private static float chargeAdjusted(DamageSource source, float amount) {
      Entity direct = source == null ? null : source.getDirectEntity();
      if (!(direct instanceof ServerPlayer attacker) || source.getEntity() != attacker) {
         return amount;
      }
      long nowMs = System.currentTimeMillis();
      Long previous = LAST_BANKED_SWING.put(attacker.getUUID(), nowMs);
      if (previous == null) {
         return amount;
      }
      double charge = (nowMs - previous) / swingIntervalMillis(attacker);
      charge = Math.max(0.0, Math.min(1.0, charge));
      double factor = 0.2 + charge * charge * 0.8;
      return (float)(amount * factor);
   }

   /** Who currently owns the server tick freeze, so two stops cannot unfreeze each other. */
   private static final Map<String, Long> FREEZE_OWNERS = new HashMap<>();
   /** Ticking chrono-bombs the Time Lord leaves underfoot. */
   private static final List<Bomb> BOMBS = new ArrayList<>();
   /** Recently spoken lines, so a repeated announce cannot spam the chat. */
   private static final Map<String, Integer> RECENT_LINES = new HashMap<>();
   /** Our own tick counter: unlike the level clock, it keeps running while the
    *  world is frozen, which is exactly what the stop script needs. */
   private static int scriptTick = 0;
   /** Clock watchdog state: the last world-clock reading and when we took it. */
   private static long clockProbeTicks = Long.MIN_VALUE;
   private static long clockProbeAtMillis;
   private static boolean clockProbePrimed;
   /**
    * How many live fights own a running sprint right now, and whether this module has
    * accelerated a clock it has not put back yet.
    *
    * <p>The rate is a lease, not an event: these two are what let the module say "nobody
    * owns this clock, so it goes back" rather than "somebody's script ran a branch that
    * put it back". See {@link #forceClockIdle}.
    */
   private static int sprintsRunning;
   private static boolean clockAccelerated;
   /** Set on the first tick after a boot, so a leftover rate is put back exactly once. */
   private static boolean clockResetThisBoot;
   private static final Map<UUID, List<Vec3>> TRACE = new HashMap<>();
   private static final Map<UUID, List<Float>> TRACE_HP = new HashMap<>();

   /** Junk flung through rifts - the arrows carry them as their pickup item, so
    *  surviving a volley literally rains random objects onto the floor. */
   private static final ItemStack[] JUNK = new ItemStack[]{
      new ItemStack(Items.BRICK), new ItemStack(Items.BONE), new ItemStack(Items.STICK),
      new ItemStack(Items.FLINT), new ItemStack(Items.IRON_NUGGET), new ItemStack(Items.CLAY_BALL),
      new ItemStack(Items.CLOCK), new ItemStack(Items.COPPER_INGOT)
   };

   /** Daemon release backstop - see point 3 of the class comment. */
   private static final ScheduledExecutorService RELEASE = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = new Thread(r, "ff-timelord-release");
      t.setDaemon(true);
      return t;
   });

   private TimeLordManager() {
   }

   // ------------------------------------------------------------------ state

   private static final class Fight {
      final UUID bossId;
      final UUID summoner;
      final ServerBossEvent bar;
      final Set<UUID> participants = new HashSet<>();
      int riseTicks = 46;
      int phase = 1;
      boolean phaseTwoAnnounced;
      boolean grandStopDone;
      boolean rewound;
      long nextStop;
      long nextTeleport;
      long nextRift;
      long nextVolley;
      long nextAura;
      long nextBomb;
      long nextRay;
      /** The clock sprint: day time is being spent at a ramped multiple while this runs. */
      long nextClockSprint;
      int clockSprintTicks;
      int nextStopStrike;
      /** Our own clock reading at which the current stop ends, for the action bar. */
      int stopEndsAtScript;
      /** Full length of the current stop, so the action bar can show a fraction. */
      int stopTotalTicks = 1;
      /** Where the current stop is centred, for its particles and its release. */
      Vec3 stopCenter;
      /** Non-null while this fight owns the server tick freeze. */
      String freezeOwner;
      long freezeUntil;
      long freezeUntilMillis;
      boolean frozeServer;
      int teleports;
      boolean dying;
      int deathTicks;
      int agingStacks;
      /** Arena beams fired this fight. He opens with one now, so this is zero only
       *  before his first move - which is what the finale's gate below waits for. */
      int rays;
      /** Set once, by whichever exit pays him out. A ceremony and a body found dead
       *  are two exits from the same fight, and only one of them may pay. */
      boolean paid;
      /** The final stand: he blinks away, glows, and judges everyone at once. */
      boolean finale;
      boolean finaleDone;
      int finaleTicks;
      final Set<UUID> finaleHits = new HashSet<>();
      /** "The same second, again": the last eight seconds, sampled, and the tell. */
      long nextLoop;
      long nextLoopTrail;
      int loopTicks;
      /** The ring: when the next one may start, and when the current one goes off. */
      long nextNova;
      long novaUntil;
      final ArrayDeque<Moment> bossTrail = new ArrayDeque<>();
      final Map<UUID, ArrayDeque<Vec3>> trails = new HashMap<>();
      final Map<UUID, Vec3> loopWatch = new HashMap<>();

      Fight(UUID bossId, UUID summoner, ServerBossEvent bar) {
         this.bossId = bossId;
         this.summoner = summoner;
         this.bar = bar;
      }
   }

   /** A player held motionless by a time stop. The anchor is where they stood
    *  when time stopped; everything else is book-keeping for the release. */
   private static final class Pin {
      final Vec3 anchor;
      final float yaw;
      final float pitch;
      final boolean airborne;
      int ticksLeft;
      float damage;
      Vec3 knockFrom;

      Pin(Vec3 anchor, float yaw, float pitch, boolean airborne, int ticksLeft, Vec3 knockFrom) {
         this.anchor = anchor;
         this.yaw = yaw;
         this.pitch = pitch;
         this.airborne = airborne;
         this.ticksLeft = ticksLeft;
         this.knockFrom = knockFrom;
      }
   }

   /** A chrono-bomb: a spot that ticks down and then goes off. It only exists
    *  so the move reads as a countdown instead of instant damage. */
   private static final class Bomb {
      final ServerLevel level;
      final Vec3 at;
      final float damage;
      int ticks;

      Bomb(ServerLevel level, Vec3 at, float damage, int ticks) {
         this.level = level;
         this.at = at;
         this.damage = damage;
         this.ticks = ticks;
      }
   }

   /** A ring of arrows that fires one at a time (the "five arrows around you"). */
   private static final class Volley {
      final UUID target;
      final ServerLevel level;
      final Mob boss;
      int remaining;
      long next;
      float damage;

      Volley(UUID target, ServerLevel level, Mob boss, int remaining, long next, float damage) {
         this.target = target;
         this.level = level;
         this.boss = boss;
         this.remaining = remaining;
         this.next = next;
         this.damage = damage;
      }
   }

   /** One sampled instant of the fight: where somebody stood and what they had left. */
   private record Moment(Vec3 at, float health) {
   }

   /**
    * A Pocket-Watch stop: a bubble of stopped time around the wielder.
    *
    * <p>The server tick freeze suspends mobs, projectiles and falling blocks; the
    * wielder is deliberately not pinned so they can keep acting, and other players
    * are pinned because the freeze cannot touch client-driven movement. Anything
    * damaged inside is banked (see {@link #tryDelayDamage}) and paid out by
    * {@link #endPocketStop}.
    */
   private static final class PocketStop {
      final ServerLevel level;
      final Vec3 center;
      final double radius;
      final UUID caster;
      final int endsAtScript;
      final String owner;
      final List<Entity> frozen;
      /** False when the watch only stilled the mobs - a Time Lord refused it. */
      final boolean worldFrozen;
      /** Its full length, so the visual layer can show how much is left. */
      final int lengthTicks;

      PocketStop(
         ServerLevel level,
         Vec3 center,
         double radius,
         UUID caster,
         int endsAtScript,
         String owner,
         List<Entity> frozen,
         boolean worldFrozen,
         int lengthTicks
      ) {
         this.level = level;
         this.center = center;
         this.radius = radius;
         this.caster = caster;
         this.endsAtScript = endsAtScript;
         this.owner = owner;
         this.frozen = frozen;
         this.worldFrozen = worldFrozen;
         this.lengthTicks = Math.max(1, lengthTicks);
      }
   }

   /**
    * Damage swallowed by a stop, waiting for the moment time resumes.
    *
    * <p><b>The individual hits are kept, not just their total.</b> That is the whole of the fix
    * for the report that the Pocket-Watch "does TRUE damage": a bank paid out as one number
    * through a damage source the game never saw applies armor, protection, resistance and
    * absorption <i>once</i>, to the sum - so five sword hits that each would have been halved by
    * a netherite chestplate landed as one blow nothing reduced. Delivering the hits one at a
    * time through the source each of them actually arrived with hands every point of that
    * arithmetic back to vanilla, including how much health the victim has left to lose, and it
    * is still all inside one tick: time resumes once and everything the stop swallowed is
    * already on the floor by the time anyone can move.
    *
    * <p>{@code amount} is kept as the running total because the victim's overlay and the
    * stinger read it, and a number in chat is a number in chat.
    */
   private static final class Deferred {
      final PocketStop stop;
      final DamageSource source;
      final List<Float> hits = new ArrayList<>();
      float amount;

      Deferred(PocketStop stop, float amount, DamageSource source) {
         this.stop = stop;
         this.amount = amount;
         this.source = source;
         this.hits.add(amount);
      }
   }

   // ------------------------------------------------------------- public API

   public static boolean isTimeLord(Entity entity) {
      return entity != null && entity.entityTags().contains(TAG);
   }

   /**
    * Tags a probe body as one of his, for the self-test's lethal-hook check. The
    * script itself is not started: the question that check asks is what a tagged
    * body with <i>no</i> fight behind it does with a killing blow.
    */
   public static void tagForTest(Mob boss) {
      boss.addTag(TAG);
      BossManager.markBoss(boss);
   }

   /** How many Time Lords are alive right now (the {@code /ff boss} surface). */
   public static int activeCount() {
      return FIGHTS.size();
   }

   /** True while the given player is inside a Time Lord's stop. Used by the
    *  attack gate in {@code ModEvents} - a stopped player cannot swing. */
   public static boolean isTimeStopped(ServerPlayer player) {
      return player != null && PINS.containsKey(player.getUUID());
   }

   /** True when any fight or Pocket-Watch currently holds the server tick frozen. */
   public static boolean isHoldingTime(MinecraftServer server) {
      for (PocketStop stop : POCKET_STOPS) {
         if (stop.worldFrozen) {
            return true;
         }
      }
      for (Fight f : FIGHTS.values()) {
         if (f.frozeServer) {
            return true;
         }
      }
      return false;
   }

   /**
    * The clock sprint: he spends time instead of stopping it.
    *
    * <p>The sky is what moves, not the server tick: the world clock is asked for a ramped
    * multiple of its own rate, so a minute of standing still covers five complete
    * day-and-night cycles. Nothing else changes speed - mobs, cooldowns, damage and the
    * fight all run at their ordinary rate - which is the difference between this and a
    * tick sprint, and the reason it can be used in a live fight at all.
    */
   private static final long CLOCK_SPRINT_COOLDOWN = 2400L;
   /** How long one sprint lasts, in server ticks. A minute. */
   private static final int CLOCK_SPRINT_TICKS = 1200;
   /** How many full day-and-night cycles a whole sprint is worth. */
   private static final int CLOCK_SPRINT_CYCLES = 5;
   /** Ticks spent easing in and easing out, so the start and end are not a lurch. */
   private static final int CLOCK_SPRINT_RAMP_TICKS = 60;
   private static final long DAY_LENGTH_TICKS = 24_000L;
   /**
    * The clock's ordinary rate: one tick of sky per tick of world.
    *
    * <p>Restored whenever a sprint ends <i>and</i> whenever a fight is released or the
    * server stops, because the clock manager is saved data. A sprint interrupted by a
    * shutdown would otherwise leave the world running five hundred times too fast until
    * somebody noticed - a bug that only shows up after the crash it was written for.
    */
   public static final float CLOCK_RATE_IDLE = 1.0F;
   /**
    * The clock rate at the top of the ramp.
    *
    * <p>Derived rather than guessed: the sprint has to be worth {@code CYCLES} full
    * cycles, the two ramps average half the peak, and the plateau covers the rest - so
    * the peak is {@code cycles * DAY_LENGTH / (ticks - ramp)}. Change any of the numbers
    * above and this follows them, which is what the self-test checks the integral of.
    */
   private static final float CLOCK_SPRINT_PEAK_RATE = clockSprintPeakRate();

   private static float clockSprintPeakRate() {
      long wanted = (long)CLOCK_SPRINT_CYCLES * DAY_LENGTH_TICKS;
      long effective = Math.max(1L, CLOCK_SPRINT_TICKS - CLOCK_SPRINT_RAMP_TICKS);
      return Math.max(2.0F, (float)Math.round(wanted / (double)effective));
   }

   /**
    * The clock rate for one tick of a sprint: eased in, held, eased out.
    *
    * <p>A rate rather than a set time, which is what makes it smooth instead of a jump
    * cut: the world clock already advances by whatever it is asked to, and the sky is
    * drawn from the value in between, so a ramped multiplier reads as the sun and moon
    * sweeping across an afternoon. Note that this moves <b>time</b>, not ticks - mobs,
    * cooldowns, damage and the fight all run at their ordinary rate while it happens.
    */
   public static float clockSprintRate(int elapsedTicks, int ticksLeft) {
      double ramp = Math.min(1.0, elapsedTicks / (double)CLOCK_SPRINT_RAMP_TICKS);
      double down = Math.min(1.0, ticksLeft / (double)CLOCK_SPRINT_RAMP_TICKS);
      double share = Math.max(0.0, Math.min(ramp, down));
      return (float)(CLOCK_RATE_IDLE + (CLOCK_SPRINT_PEAK_RATE - CLOCK_RATE_IDLE) * share);
   }

   /**
    * What one whole sprint is worth in sky, for the self-test.
    *
    * <p>Summed from the same function the live tick uses, so "five day-and-night cycles"
    * is a property of the curve rather than a comment about the constants.
    */
   public static long clockSprintTotalTicks() {
      double total = 0.0;
      for (int elapsed = 0; elapsed < CLOCK_SPRINT_TICKS; elapsed++) {
         total += clockSprintRate(elapsed, CLOCK_SPRINT_TICKS - elapsed);
      }
      return Math.round(total);
   }

   /** How many day-and-night cycles one sprint is worth, to two places. */
   public static double clockSprintCycles() {
      return clockSprintTotalTicks() / (double)DAY_LENGTH_TICKS;
   }

   /** How long the sky runs at its own rate between sprints. For the self-test. */
   public static int clockSprintIdleTicks() {
      return (int)CLOCK_SPRINT_COOLDOWN;
   }

   /** The overworld clock, or null if this server has no such thing. */
   private static Holder<WorldClock> overworldClock(MinecraftServer server) {
      try {
         return server.registryAccess().lookupOrThrow(Registries.WORLD_CLOCK).getOrThrow(WorldClocks.OVERWORLD);
      } catch (Throwable t) {
         return null;
      }
   }

   /**
    * Asks every level's clock manager for a rate, so the sky everywhere in this world runs
    * at it. Fails silently: a server whose clock API says no is a fight with a normal sun.
    */
   private static void applyClockRate(MinecraftServer server, float rate) {
      if (server == null) {
         return;
      }
      Holder<WorldClock> clock = overworldClock(server);
      if (clock == null) {
         return;
      }
      for (ServerLevel level : server.getAllLevels()) {
         Safe.run("time lord clock rate", () -> level.clockManager().setRate(clock, rate));
      }
   }

   /**
    * Swallows damage dealt inside an active Pocket-Watch stop and banks it.
    *
    * <p>"Time is stopped" has to mean the hit has not landed yet: rather than let
    * a player be bursted down while their own watch holds the world still, every
    * point of damage inside the bubble is deferred, added up, and delivered as a
    * single blow when the stop lifts. Hooked from {@code ModEvents}' damage gate;
    * returning {@code true} means "cancel this hit, I have it".
    */
   /**
    * The share of a banked Pocket-Watch hit that actually lands.
    *
    * <p>0.6 for player against player, 1.0 for everything else. Written as three
    * booleans and returned as a number rather than checked inline, because "player to
    * player in a stopped world" is the one case where the trinket changes the outcome of
    * a fight, and a rule like that should be checkable without staging one: see
    * {@code timelord.a-pocket-watch-softens-pvp-only}.
    */
   public static float pocketStopDamageFactor(boolean victimIsPlayer, boolean attackerIsPlayer, boolean sameEntity) {
      return victimIsPlayer && attackerIsPlayer && !sameEntity ? POCKET_STOP_PVP_FACTOR : 1.0F;
   }

   public static boolean tryDelayDamage(LivingEntity victim, DamageSource source, float amount) {
      if (POCKET_STOPS.isEmpty() || victim == null || amount <= 0.0F) {
         return false;
      }
      if (victim.entityTags().contains(TAG) || !(victim.level() instanceof ServerLevel level)) {
         return false;
      }
      // A Time Lord wearing a damage bank would be the trinket winning. He is
      // not covered by the bubble at all (see {@link #counterWatch}).
      if (isTimeLord(victim)) {
         return false;
      }
      for (PocketStop stop : POCKET_STOPS) {
         if (stop.level != level || victim.position().distanceToSqr(stop.center) > stop.radius * stop.radius) {
            continue;
         }
         // Only a stop that actually holds the world banks damage. When the Time
         // Lord refuses the watch the world keeps running, and banking his blows
         // would hand every one of them back as a single combined hit at the end
         // - which is precisely the burst the bank exists to prevent.
         if (!stop.worldFrozen) {
            continue;
         }
         float banked = chargeAdjusted(source, amount);
         // Player against player is the one exchange the watch must not turn into a free
         // kill: the world is held still, so the defender cannot answer, and a full-power
         // blow delivered all at once when the stop lifts is exactly the burst this bank
         // exists to prevent. Everything a player deals to another player in here lands at
         // 60%; mobs, the environment and the boss are untouched.
         boolean attackerIsPlayer = source.getEntity() instanceof ServerPlayer;
         float pvpFactor = pocketStopDamageFactor(
            victim instanceof ServerPlayer, attackerIsPlayer, attackerIsPlayer && source.getEntity() == victim
         );
         if (pvpFactor != 1.0F) {
            banked *= pvpFactor;
         }
         Deferred previous = DEFERRED.get(victim.getUUID());
         if (previous != null && previous.stop == stop) {
            // The same stop, another hit: added to the bank as its own entry rather than folded
            // into the total, so the payout can hand each one back through the source it came in
            // on - see {@link Deferred}.
            previous.hits.add(banked);
            previous.amount += banked;
            DEFERRED.put(victim.getUUID(), previous);
         } else {
            DEFERRED.put(victim.getUUID(), new Deferred(stop, banked, source));
         }
         float total = DEFERRED.get(victim.getUUID()).amount;
         com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.END_ROD, victim.getX(), victim.getY() + 1.0, victim.getZ(), 6, 0.3, 0.4, 0.3, 0.04);
         if (victim instanceof ServerPlayer p) {
            p.sendOverlayMessage(Component.literal("\u00a7dDamage suspended \u00a78| \u00a7f" + String.format(java.util.Locale.ROOT, "%.1f", total) + " banked"));
         }
         return true;
      }
      return false;
   }

   /** Called on server shutdown: never leave a world frozen behind us. */
   public static void onServerStopping(MinecraftServer server) {
      for (Fight f : new ArrayList<>(FIGHTS.values())) {
         if (f.frozeServer) {
            unfreeze(server, f);
         }
      }
      FIGHTS.clear();
      VOLLEYS.clear();
      BOMBS.clear();
      TRACE.clear();
      TRACE_HP.clear();
      releaseAllPins(server);
      // Never leave a sprinted clock behind on shutdown: the rate is saved with the world.
      sprintsRunning = 0;
      clockAccelerated = false;
      applyClockRate(server, CLOCK_RATE_IDLE);
      POCKET_STOPS.clear();
      DEFERRED.clear();
      FROZEN_SHOTS.clear();
      FREEZE_OWNERS.clear();
      LAST_BANKED_SWING.clear();
      // Any stasis we applied to mobs is left alone on purpose: mobs are saved
      // with their abilities, and a world saved mid-stasis would freeze a mob
      // forever. Clearing it here is cheap and always correct.
      if (server != null) {
         Safe.run("time lord stasis cleanup", () -> clearAllStasis(server));
      }
   }

   /**
    * Ends every active fight immediately - boss gone, bar gone, time released.
    *
    * <p>This is the "make it stop" button: the self-test probes the Space-Time
    * Rift for real, which summons him, so the probe has to be able to undo that
    * without leaving a boss and a boss bar behind in the dev world. It is also the
    * honest cleanup for any fight whose participants have all vanished.
    *
    * @return how many fights were ended
    */
   public static int abandonAll(MinecraftServer server) {
      int ended = 0;
      for (Fight fight : new ArrayList<>(FIGHTS.values())) {
         Safe.run("time lord abandon", () -> {
            if (server != null) {
               for (ServerLevel level : server.getAllLevels()) {
                  Entity boss = level.getEntity(fight.bossId);
                  if (boss != null) {
                     boss.discard();
                  }
               }
            }
            release(server, fight);
         });
         ended++;
      }
      VOLLEYS.clear();
      BOMBS.clear();
      releaseAllPins(server);
      return ended;
   }

   // -------------------------------------------------------------- summoning

   /** Space-Time Rift right-click: tear a hole and let him through. */
   public static String summon(ServerPlayer summoner) {
      if (!ModConfig.is("boss")) {
         return "Bosses are disabled on this server.";
      }

      for (Fight f : FIGHTS.values()) {
         if (summoner.getUUID().equals(f.summoner)) {
            return "You already have an active Time Lord - finish him first!";
         }
      }

      ServerLevel level = summoner.level();
      Mob boss = (Mob) EntityTypes.EVOKER.create(level, EntitySpawnReason.COMMAND);
      if (boss == null) {
         return "The rift closed before he could step through.";
      }

      AttributeInstance maxHp = boss.getAttribute(Attributes.MAX_HEALTH);
      if (maxHp != null) {
         maxHp.setBaseValue(MAX_HEALTH);
      }
      boss.setHealth((float) MAX_HEALTH);
      AttributeInstance dmg = boss.getAttribute(Attributes.ATTACK_DAMAGE);
      if (dmg != null) {
         dmg.setBaseValue(ATTACK_DAMAGE);
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
      boss.setNoAi(true);
      boss.setNoGravity(true);
      boss.addTag(TAG);
      // The shared marker plus the visible-and-persistent guarantee: see
      // BossManager.markBoss for why a boss has to say so itself.
      BossManager.markBoss(boss);
      // He arrives *through* the rift: the tear opens where the clock was used
      // and he rises out of it, so the summon is never a pop-in.
      double x = summoner.getX();
      double y = summoner.getY();
      double z = summoner.getZ();
      boss.setPos(x, y - 2.4, z);
      boss.setYRot(summoner.getYRot());
      boss.setXRot(summoner.getXRot());
      // The answer to `addFreshEntity` is the difference between a boss and a fight
      // built around nothing. If the chunk he is stepping into is not ticking yet the
      // server refuses the body with no error at all - the chunk reads as loaded from
      // the outside - and the fight that follows has a bar, a script and nobody in it:
      // the tick finds no body, releases, and the player is left with "he never
      // attacked and then he was gone". Bring the chunk in and try once more, and say
      // so rather than build a fight on a place where nobody arrived.
      if (!level.addFreshEntity(boss)) {
         level.getChunkAt(boss.blockPosition());
         if (!level.addFreshEntity(boss)) {
            return "The rift would not open there - try again on solid, loaded ground.";
         }
      }

      // Modded clients see the tear itself open and hold for the rise; the per-tick rift the rise
      // draws below is the vanilla clients' version of the same thing.
      double facing = Math.toRadians(summoner.getYRot());
      com.fortuneandfavors.net.FfVfx.shape(level, com.fortuneandfavors.net.FfVfx.TEAR, ParticleTypes.REVERSE_PORTAL, new Vec3(x, y + 1.2, z),
         new Vec3(Math.cos(facing), 0.0, Math.sin(facing)), 2.6, 52, 0xB06BFF);
      ServerBossEvent bar = new ServerBossEvent(
         UUID.randomUUID(), Component.literal(BOSS_NAME), BossBarColor.PURPLE, BossBarOverlay.PROGRESS
      );
      bar.setVisible(true);
      // Only the summoner for now; refreshParticipants adds whoever is actually in range next tick.
      // Adding the whole server flashed the bar at players in other dimensions.
      bar.addPlayer(summoner);

      Fight fight = new Fight(boss.getUUID(), summoner.getUUID(), bar);
      fight.participants.add(summoner.getUUID());
      long now = ServerClock.clock(level);
      fight.nextTeleport = now + 100L;
      fight.nextRift = now + 80L;
      fight.nextVolley = now + 140L;
      fight.nextStop = now + 240L;
      fight.nextAura = now + 40L;
      fight.nextBomb = now + 100L;
      // He leads with the beam, and it is his headline move, so it cannot be the one
      // move a fast party never sees: at 220 ticks the first ray was eleven seconds
      // after he rose, which is longer than an endgame party needs to take him from
      // full to the finale - and the finale outranks the arena ladder, so the beam
      // never happened at all. Four seconds after the rift, it is his opening.
      fight.nextRay = now + 80L;
      FIGHTS.put(boss.getUUID(), fight);

      announce(level, "\u00a7d\u00a7l\u2726 \u00a7dThe air splits like old paper. \u00a7fThe Time Lord \u00a7dsteps out, already checking his watch. \u00a77\"\u00a7fYou're early. I hate early.\u00a77\"");
      level.playSound(null, x, y, z, ModSounds.BOSS_SPAWN, SoundSource.HOSTILE, 1.2F, 1.6F);
      level.playSound(null, x, y, z, SoundEvents.END_PORTAL_SPAWN, SoundSource.HOSTILE, 1.0F, 0.7F);
      level.playSound(null, x, y, z, SoundEvents.ENDERMAN_SCREAM, SoundSource.HOSTILE, 1.0F, 0.5F);
      Advancements.grant(summoner, "summon_timelord");
      return null;
   }

   // ------------------------------------------------------------------- tick

   public static void tick(MinecraftServer server) {
      // Boot: any rate still written into the world is put back before anything else can
      // look at it. The watchdog below can only see a clock that is *moving* the sky, and
      // a world saved mid-sprint comes back with the rate already in its saved data - the
      // one case sampling can be slow to notice and the one a player means by "the world
      // still has the bugged time speed". No sprint can exist at boot, so the ordinary
      // rate is the only correct value and writing it unconditionally cannot be wrong.
      if (!clockResetThisBoot) {
         clockResetThisBoot = true;
         sprintsRunning = 0;
         clockAccelerated = false;
         applyClockRate(server, CLOCK_RATE_IDLE);
      }

      // Our own clock: it keeps counting while the world is frozen, which is what
      // lets a stop script its own strikes and release its own pins.
      scriptTick++;
      Safe.run("time lord pins", () -> tickPins(server));
      Safe.run("time lord bombs", () -> tickBombs(server));
      Safe.run("time lord launch fall guard", () -> tickNoFall(server));
      Safe.run("pocket watch stops", () -> tickPocketStops(server));
      Safe.run("chrono ambience", () -> tickChronoAmbience(server));
      Safe.run("aging countdown", () -> tickAgingCountdown(server));
      Safe.run("time lord clock watchdog", () -> watchClock(server));
      // Before anything can return early: whether or not a fight is still being scripted,
      // a sky with no sprint left to own it goes back to its own rate this tick.
      Safe.run("time lord clock ownership", () -> forceClockIdle(server));

      if (FIGHTS.isEmpty() && VOLLEYS.isEmpty()) {
         tracePlayers(server);
         return;
      }

      long now = ServerClock.clock(server.overworld());
      for (Fight fight : new ArrayList<>(FIGHTS.values())) {
         Safe.run("time lord tick", () -> tickFight(server, fight, now));
      }

      for (Iterator<Volley> it = VOLLEYS.iterator(); it.hasNext();) {
         Volley v = it.next();
         if (v.remaining <= 0 || v.boss == null || !v.boss.isAlive() || now >= v.next) {
            if (v.remaining <= 0 || v.boss == null || !v.boss.isAlive()) {
               it.remove();
               continue;
            }
            fireVolleyArrow(v);
            v.remaining--;
            v.next = now + 4L;
            if (v.remaining <= 0) {
               it.remove();
            }
         }
      }

      tracePlayers(server);
   }

   private static void tickFight(MinecraftServer server, Fight fight, long now) {
      Entity raw = server.overworld().getServer() == null ? null : findEntity(server, fight.bossId);
      if (!(raw instanceof Mob boss) || !boss.isAlive()) {
         // A body that is gone is not a fight that ended quietly. Whatever removed it -
         // the void, an operator's cleanup, a second lethal blow taken during the
         // ceremony, a chunk unload - the player did the work, and an exit that pays
         // nothing is indistinguishable from a boss who died on his own. Pay first, then
         // release: the flag makes the other exit a no-op if the ceremony got there.
         payOut(server, fight, "the moment takes him");
         release(server, fight);
         return;
      }
      ServerLevel level = (ServerLevel) boss.level();
      now = ServerClock.clock(level);

      // 0) The release watchdog runs before anything else, so no exception in
      //    the fight script can leave the world frozen behind it.
      if (fight.frozeServer && (now >= fight.freezeUntil || System.currentTimeMillis() >= fight.freezeUntilMillis)) {
         unfreeze(server, fight);
      }
      refreshParticipants(server, level, boss, fight);
      recordTrails(server, boss, fight);

      // 1) Death ceremony - he is already "dead" as far as damage is concerned,
      //    we are just playing it out.
      if (fight.dying) {
         // The sprint dies with him. He is the one who stopped the clock; nothing of
         // his is allowed to outlive him, least of all a sky still running at five
         // hundred times its own rate over an empty arena.
         stopClockSprint(server, fight);
         // His health is at zero for the whole ceremony, and the server removes a body
         // at zero health on its own next tick - a fall, a stray arrow, the shulker of
         // damage a passing player lands - which ends the script mid-line and reads in
         // game as "he just dies", with nothing said and nothing dropped. Floored for
         // the length of it, the same way the Puppeteer's is.
         if (boss.getHealth() <= 0.5F) {
            boss.setHealth(0.5F);
         }
         tickDeath(level, boss, fight, now);
         return;
      }

      // 2) Arrival: the rift finishes opening and he rises out of it.
      if (fight.riseTicks > 0) {
         fight.riseTicks--;
         com.fortuneandfavors.net.FfVfx.enter();
         try {
            drawRift(level, boss.getX(), boss.getY() + 1.0, boss.getZ(), 1.0, 34);
         } finally {
            com.fortuneandfavors.net.FfVfx.exit();
         }
         boss.setPos(boss.getX(), boss.getY() + 0.055, boss.getZ());
         if (fight.riseTicks % 6 == 0) {
            level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.ENDERMAN_AMBIENT, SoundSource.HOSTILE, 0.7F, 0.6F);
         }
         return;
      }

      // 3) Nobody left? The fight ends and he leaves. Not mid-stop, though: a
      //    stopped world is exactly when a stray empty participant list must not
      //    be allowed to make him vanish out from under the fight.
      if (fight.participants.isEmpty() && !fight.frozeServer) {
         despawn(server, level, boss, fight, "Nobody is left to fight - time moves on.");
         return;
      }

      // 4) Phase 2 at half health.
      if (fight.phase == 1 && boss.getHealth() <= boss.getMaxHealth() * 0.5F) {
         fight.phase = 2;
         fight.phaseTwoAnnounced = true;
         if (fight.bar != null) {
            fight.bar.setColor(BossBarColor.RED);
         }
         announce(level, SAY + "\"\u00a7fHalf. \u00a7dFine. I'm done being polite with the clock.\u00a7f\"");
         level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.ENDER_DRAGON_GROWL, SoundSource.HOSTILE, 1.0F, 1.5F);
         riftBurst(level, boss.getX(), boss.getY() + 1.0, boss.getZ(), 60);
         applyOldAge(level, boss, fight, 6);
      }

      // 5) While frozen he is NOT frozen with the rest of the world. The server
      //    clock has stopped, but this script has not, so he walks through the
      //    frozen moment and takes his time with whoever he likes. Players are
      //    pinned mid-step by tickPins, so all they can do is watch.
      if (fight.frozeServer) {
         drawRift(level, boss.getX(), boss.getY() + 1.2, boss.getZ(), 0.7, 10);
         stopActionBar(server, fight);
         ServerPlayer victim = nearestTarget(level, boss, fight);
         if (victim != null && scriptTick >= fight.nextStopStrike) {
            fight.nextStopStrike = scriptTick + STOP_STRIKE_INTERVAL;
            stopStrike(level, boss, fight, victim);
         }
         return;
      }

      // 5.5) A sprint already running keeps running: it is watched for on every tick,
      // not only on the tick some other ability happens to fall through to it.
      tickClockSprint(server, level, boss, fight);

      // 5.6) A rewind that is already winding keeps winding, on every tick,
      //      whatever else is ready to fire.
      if (fight.loopTicks > 0) {
         tickLoop(server, level, boss, fight);
         return;
      }

      // 6) Ambient clock aura, always on, so he reads as a time entity.
      if (now >= fight.nextAura) {
         fight.nextAura = now + 8L;
         drawClockHands(level, boss);
      }

      // 7) Abilities, in priority order. Each is independently gated so a
      //    missing target can never stall the rotation.
      ServerPlayer target = nearestTarget(level, boss, fight);
      if (target == null) {
         return;
      }

      // The finale outranks everything, including the grand stop: at FINALE_AT he stops
      // trading blows and starts judging - and it is a share of his health a raid can
      // actually reach with the fight still going, rather than the last half-heart.
      if (fight.finale) {
         tickFinale(server, level, boss, fight);
         return;
      }
      if (!fight.finaleDone && fight.phase >= 2 && boss.getHealth() <= boss.getMaxHealth() * FINALE_AT) {
         startFinale(server, level, boss, fight, target);
         return;
      }
      if (fight.phase == 2 && !fight.grandStopDone && boss.getHealth() <= boss.getMaxHealth() * 0.35F) {
         grandTimeStop(level, boss, fight, target);
         return;
      }
      if (!fight.rewound && boss.getHealth() <= boss.getMaxHealth() * 0.45F) {
         fight.rewound = true;
         rewindSelf(level, boss, fight, target);
         return;
      }
      if (now >= fight.nextStop && boss.distanceToSqr(target) < 900.0) {
         timeStop(level, boss, fight, STOP_TICKS);
         return;
      }
      // The sprint outranks the bombs and the rays: it is the move that changes the
      // arena's lighting for a minute, and it is the one he should never be sitting on
      // while trading blows.
      if (clockSprintCanStart(now, fight.nextClockSprint, fight.clockSprintTicks)) {
         // Counted from the end of the sprint, not the start: see clockSprintCanStart.
         fight.nextClockSprint = now + CLOCK_SPRINT_TICKS + CLOCK_SPRINT_COOLDOWN;
         startClockSprint(level, boss, fight);
         return;
      }
      // "The same second, again." From the last phase onwards he stops trading
      // blows for two seconds and winds the last eight seconds back over
      // everybody's head - see {@link #startLoop}. It outranks the bombs, the
      // rays and the volleys because it is the one move that erases work rather
      // than dealing damage, and sitting on it while shooting at people is what
      // he was doing before.
      if (fight.phase >= 2 && now >= fight.nextLoop) {
         fight.nextLoop = now + LOOP_COOLDOWN;
         startLoop(server, level, boss, fight);
         return;
      }
      // "The hour that never was": the tell is the ring itself, and the answer is distance.
      // It outranks the bombs and the rays for the same reason the rewind does - it is the
      // move that costs the room progress rather than hearts, and it must not be sitting
      // behind a cooldown while he trades blows.
      if (fight.novaUntil > 0L) {
         if (now >= fight.novaUntil) {
            detonateNova(level, boss, fight);
         } else {
            drawNovaTell(level, boss, fight, now);
         }
         return;
      }
      if (fight.phase >= 3 && now >= fight.nextNova && boss.distanceToSqr(target) < NOVA_RANGE * NOVA_RANGE * 4.0) {
         fight.nextNova = now + NOVA_COOLDOWN;
         fight.novaUntil = now + NOVA_TELL;
         startNova(level, boss, fight);
         return;
      }
      if (now >= fight.nextBomb) {
         fight.nextBomb = now + BOMB_COOLDOWN;
         chronoBombs(level, boss, fight, target);
         return;
      }
      if (now >= fight.nextRay) {
         fight.nextRay = now + RAY_COOLDOWN;
         agingRay(level, boss, fight, target);
         return;
      }
      if (now >= fight.nextVolley) {
         fight.nextVolley = now + VOLLEY_COOLDOWN;
         arrowHalo(level, boss, fight, target);
         return;
      }
      if (now >= fight.nextRift) {
         fight.nextRift = now + RIFT_COOLDOWN;
         riftVolley(level, boss, fight, target);
         return;
      }
      if (now >= fight.nextTeleport && fight.teleports < MAX_TELEPORTS) {
         fight.nextTeleport = now + TELEPORT_COOLDOWN;
         if (safeTeleport(level, boss, fight, target)) {
            fight.teleports++;
         }
         return;
      }

      // 8) Baseline pressure: he hovers to a comfortable range and closes when
      //    the player runs, so the fight never becomes a staring contest.
      //
      // He is moved by hand, so "closing" is his own step and nothing stops it: a
      // hill between him and the player was something he walked straight through.
      // The step is taken only where the ground under it is real.
      double dist = Math.sqrt(boss.distanceToSqr(target));
      if (dist > 26.0) {
         Vec3 toward = target.position().subtract(boss.position()).normalize().scale(0.28);
         boss.setDeltaMovement(toward.x, 0.0, toward.z);
         stepClearOfBlocks(level, boss, toward.x, toward.z);
      }

      // And if anything has already put him inside the world - his blink, his own
      // strike step, an older save - he comes back up to the surface rather than
      // staying buried for the rest of the fight.
      BossGrounding.resurface(level, boss, 0.6);
      boss.setYRot(faceYaw(boss, target));
      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.PORTAL, boss.getX(), boss.getY() + 1.0, boss.getZ(), 3, 0.35, 0.6, 0.35, 0.02);
   }

   /**
    * Steps a hand-moved puppet horizontally, refusing to put it inside terrain.
    *
    * <p>A {@code setNoAi(true)} boss has no walking and no collision resolution of
    * its own, so "move toward the player" is a move *through* whatever is in the
    * way. This is the short version of what a walking mob does: take the step, or
    * the step along one axis, or stay where you are.
    */
   private static void stepClearOfBlocks(ServerLevel level, Mob boss, double dx, double dz) {
      double x = boss.getX();
      double y = boss.getY();
      double z = boss.getZ();
      if (BossGrounding.standable(level, x + dx, y, z + dz)) {
         boss.setPos(x + dx, y, z + dz);
      } else if (BossGrounding.standable(level, x + dx, y, z)) {
         boss.setPos(x + dx, y, z);
      } else if (BossGrounding.standable(level, x, y, z + dz)) {
         boss.setPos(x, y, z + dz);
      }
   }

   // --------------------------------------------------------------- abilities

   /**
    * The signature move. Freezes the entire world (server tick rate), so mobs and
    * projectiles hang where they are, then pins every participant in place -
    * including mid-air - and lets him cross the frozen moment and strike at his
    * leisure. He is not frozen with it: only entity ticks stop, and this script
    * is driven by the server tick, not by his own AI. When the stop releases,
    * each pinned player is shoved away, scaled by the damage they took and hard
    * capped so it can never become a launch.
    */
   /**
    * Truth for "this sky is running far faster than any sun should".
    *
    * <p>Ten times its own rate, against a plain twenty ticks a second. The threshold is
    * deliberately far above anything a day-length or season setting would ask for: this
    * watchdog exists to undo <i>our own</i> sprint after a crash, not to police how fast
    * anybody else wants their world to turn. See {@link #watchClock}.
    */
   public static boolean clockLooksLeftSprinting(double worldTicksPerSecond) {
      return worldTicksPerSecond > 200.0;
   }

   /**
    * Puts back a clock that a crash left running.
    *
    * <p>{@link #onServerStopping} covers a clean stop, but a crash or a kill never runs
    * it, and the clock's rate is saved data: a world that went down mid-sprint comes back
    * at five hundred times its own speed with no fight left to end, which is the one
    * version of "the sprint does not stop" no teardown hook can reach. Nothing owns the
    * clock at that point, so the module watches it: with no fight and no stop live, a sky
    * moving more than ten times its own rate is a sprint of ours that never ended, and it
    * is put back. Sampled once a second, and only when the world is healthy enough for the
    * reading to mean anything.
    */
   private static void watchClock(MinecraftServer server) {
      if (server == null || server.overworld() == null) {
         return;
      }

      Holder<WorldClock> clock = overworldClock(server);
      if (clock == null) {
         return;
      }

      long total = server.overworld().clockManager().getTotalTicks(clock);
      long wall = System.currentTimeMillis();
      long elapsed = wall - clockProbeAtMillis;
      long advanced = total - clockProbeTicks;
      boolean primed = clockProbePrimed;
      clockProbeTicks = total;
      clockProbeAtMillis = wall;
      clockProbePrimed = true;

      if (!primed || elapsed < 800L || elapsed > 4000L || advanced < 0L) {
         return;
      }

      // A live sprint is *supposed* to be fast, and so is a stopped world in its own
      // strange way: neither is evidence of anything.
      if (!FIGHTS.isEmpty() || !POCKET_STOPS.isEmpty()) {
         return;
      }

      if (!clockLooksLeftSprinting(advanced * 1000.0 / elapsed)) {
         return;
      }

      applyClockRate(server, CLOCK_RATE_IDLE);
      announce(
         server.overworld(),
         "\u00a7dThe clock settles \u00a7r\u00b7 \u00a77a time sprint was still running when the world was last saved - it is over now."
      );
   }

   /**
    * Whether the rotation may start another sprint.
    *
    * <p>The first version could not tell a sprint that had ended from one that had
    * not, and its cooldown (900 ticks) was shorter than the sprint itself (1200).
    * So three quarters of the way through the first sprint the rotation decided the
    * next one was due, started it, and reset the countdown to a full 1200 ticks -
    * and then did it again, and again, for as long as the fight lasted. The sky
    * therefore never came back to its own rate, which is exactly "the time sprint
    * does not stop": a one-minute ability that was permanent in practice, and that
    * a finished fight could not switch off because the restarts had moved the
    * clock's restoration into a branch the death path never reached.
    *
    * <p>A sprint may only begin when the previous one has ended, and the cooldown is
    * counted from that end. Pinned by {@code timelord.the-sprint-can-finish}.
    */
   /**
    * The health share at which the final judgment starts. Test hook.
    *
    * <p>Pinned because this one number decides whether the fight's last stand happens at all.
    * At one percent a raid could pass straight through it inside a single critical hit, which
    * is how the aging beam became a mechanic most players never met.
    */
   public static float finaleThresholdForTest() {
      return FINALE_AT;
   }

   /** The ring's reach, so the self-test can pin that it is a real distance. Test hook. */
   public static double novaRangeForTest() {
      return NOVA_RANGE;
   }

   public static boolean clockSprintCanStart(long now, long nextSprint, int sprintTicksLeft) {
      return sprintTicksLeft <= 0 && now >= nextSprint;
   }

   /** Ends a running sprint now, and puts the world's clock back to its own rate. */
   private static void stopClockSprint(MinecraftServer server, Fight fight) {
      if (fight.clockSprintTicks > 0) {
         fight.clockSprintTicks = 0;
         sprintsRunning = Math.max(0, sprintsRunning - 1);
      }
      // Deliberately not conditional on this fight having owned anything. Whoever ends a
      // fight ends with the sky at its ordinary rate, however the sky came to be moving:
      // a rate that outlived its counter - a fight object replaced by a fresh summon, a
      // save taken mid-sprint and loaded back, a boss removed by an operator mid-move -
      // used to be left accelerating with nothing alive that would ever put it back.
      forceClockIdle(server);
   }

   /**
    * Puts the clock back if no live sprint still owns it. Safe to call every tick.
    *
    * <p>This is the answer to "the sprint does not stop": the restoration is an
    * invariant the module enforces rather than a branch a script has to reach. Every tick
    * the module is alive it asks whether anything still wants the sky accelerated, and if
    * nothing does, the ordinary rate goes back - so no death path, no removal and no
    * interrupted teardown can leave a world running at five hundred times its own speed.
    * The crash watchdog in {@link #watchClock} still exists for the one case this cannot
    * see: a rate that survived a restart has no owner and no memory of ever being ours.
    */
   public static void forceClockIdle(MinecraftServer server) {
      if (server == null || sprintsRunning > 0 || !clockAccelerated) {
         return;
      }
      clockAccelerated = false;
      applyClockRate(server, CLOCK_RATE_IDLE);
   }

   /**
    * Whether a fight's sprint should still be running at all.
    *
    * <p>"He is dying" ends the sky's acceleration there and then: nothing of his is
    * allowed to outlive him, and a death ceremony is several seconds long, which is
    * several seconds of a world still running at five hundred times its own rate if the
    * only thing that would have stopped it is a branch the ceremony never reaches.
    * Written as a function of the two states rather than checked inline so the self-test
    * can pin it. See {@code timelord.the-sprint-dies-with-him}.
    */
   public static boolean clockSprintStillRuns(boolean dying, int sprintTicksLeft) {
      return !dying && sprintTicksLeft > 0;
   }

   /** Whether any live sprint still claims the clock. For the self-test. */
   public static boolean clockIsOwnedByLiveSprint() {
      return sprintsRunning > 0;
   }

   /** Winds the clock: the sky is about to run through five days in one minute. */
   private static void startClockSprint(ServerLevel level, Mob boss, Fight fight) {
      fight.clockSprintTicks = CLOCK_SPRINT_TICKS;
      sprintsRunning++;
      clockAccelerated = true;
      announce(level, SAY + "\"\u00a7fWhy stop the clock when I can just \u00a7drun it down\u00a7f?\"");
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.BEACON_ACTIVATE, SoundSource.HOSTILE, 1.2F, 1.9F);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.HOSTILE, 1.0F, 0.6F);
      ServerPlayer caster = nearestTarget(level, boss, fight);
      if (caster != null) {
         ChronoFx.banner(caster, "\u00a7d\u00a7lTIME SPRINTS \u00a78| \u00a77five days, one minute");
      }
      for (ServerPlayer p : level.getPlayers(pl -> pl.isAlive() && pl.distanceToSqr(boss) < 2500.0)) {
         ChronoFx.stopRelease(level, p.position(), 3.0);
      }
   }

   /**
    * One tick of a running sprint: the world's daylight cycle is advanced by a ramped
    * multiple, and the sky keeps up because nothing about it is being skipped.
    */
   private static void tickClockSprint(MinecraftServer server, ServerLevel level, Mob boss, Fight fight) {
      if (!clockSprintStillRuns(fight.dying, fight.clockSprintTicks)) {
         stopClockSprint(server, fight);
         return;
      }
      int elapsed = CLOCK_SPRINT_TICKS - fight.clockSprintTicks;
      fight.clockSprintTicks--;
      applyClockRate(server, clockSprintRate(elapsed, fight.clockSprintTicks));
      // A tell that stays readable at any hour: he keeps a lit clock spinning over the
      // arena, so the phase is visible even against noon.
      drawClockHands(level, boss);
      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.END_ROD, boss.getX(), boss.getY() + 1.6, boss.getZ(), 4, 0.8, 0.8, 0.8, 0.05);
      if (fight.clockSprintTicks % 20 == 0) {
         level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.HOSTILE, 0.7F, 1.4F);
      }
      if (fight.clockSprintTicks == 0) {
         // Through the same release as every other ending, so the ownership count comes
         // back down with the rate: a sprint that finished on its own used to reset the
         // clock but keep its claim on it.
         stopClockSprint(server, fight);
         announce(level, SAY + "\"\u00a7fThere go five days. \u00a7dYours, not mine.\"");
      }
   }

   private static void timeStop(ServerLevel level, Mob boss, Fight fight, int ticks) {
      int duration = Math.min(ticks, MAX_FREEZE_TICKS);
      fight.nextStop = ServerClock.clock(level) + STOP_COOLDOWN + duration;
      fight.freezeUntil = ServerClock.clock(level) + duration;
      fight.freezeUntilMillis = System.currentTimeMillis() + (long) duration * 50L + 250L;
      fight.stopTotalTicks = duration;
      fight.stopCenter = boss.position();
      announce(level, SAY + "\"\u00a7fStop.\u00a77\" \u00a78Everything does.");
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.BEACON_DEACTIVATE, SoundSource.HOSTILE, 1.4F, 0.4F);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.HOSTILE, 1.2F, 0.5F);
      riftBurst(level, boss.getX(), boss.getY() + 1.0, boss.getZ(), 70);
      fight.stopEndsAtScript = scriptTick + duration;
      fight.nextStopStrike = scriptTick + 10;
      applyStopToParticipants(level, boss, fight, duration);
      freezeWorld(level.getServer(), fight);
      scheduleRelease(level.getServer(), fight, duration);
      ChronoFx.stopOnset(level, boss.position(), 18.0);
      for (UUID id : fight.participants) {
         ServerPlayer p = level.getServer().getPlayerList().getPlayer(id);
         if (p != null && p.isAlive()) {
            ChronoFx.banner(p, "\u00a7f\u00a7lTIME STOP \u00a78| \u00a77nothing moves but him");
         }
      }
   }

   /** Phase 2 finale: one long stop while he rings the player with arrows, then
    *  it resumes and the whole ring fires at once. */
   private static void grandTimeStop(ServerLevel level, Mob boss, Fight fight, ServerPlayer target) {
      fight.grandStopDone = true;
      fight.freezeUntil = ServerClock.clock(level) + GRAND_STOP_TICKS;
      fight.freezeUntilMillis = System.currentTimeMillis() + (long) GRAND_STOP_TICKS * 50L + 250L;
      fight.stopTotalTicks = GRAND_STOP_TICKS;
      fight.stopCenter = target.position();
      fight.nextStop = ServerClock.clock(level) + STOP_COOLDOWN * 2L;
      announce(level, SAY + "\"\u00a7fHold still. \u00a7dThis one I'm keeping.\u00a7f\"");
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.ENDER_DRAGON_GROWL, SoundSource.HOSTILE, 1.4F, 0.5F);
      level.playSound(null, target.getX(), target.getY(), target.getZ(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.HOSTILE, 1.6F, 0.4F);
      riftBurst(level, target.getX(), target.getY() + 1.0, target.getZ(), 90);
      fight.stopEndsAtScript = scriptTick + GRAND_STOP_TICKS;
      fight.nextStopStrike = scriptTick + 10;
      applyStopToParticipants(level, boss, fight, GRAND_STOP_TICKS);
      freezeWorld(level.getServer(), fight);
      ChronoFx.stopOnset(level, boss.position(), 22.0);
      ChronoFx.banner(target, "\u00a7d\u00a7lTIME STOP \u00a78| \u00a77held until he is done with you");

      // The ring is drawn now (particles, which are client-side and keep
      // rendering while the server is frozen) and fired when time resumes.
      ServerLevel targetLevel = (ServerLevel) target.level();
      double ring = 2.6;
      for (int i = 0; i < 24; i++) {
         double a = (Math.PI * 2.0 / 24.0) * i;
         double px = target.getX() + Math.cos(a) * ring;
         double py = target.getY() + 1.1;
         double pz = target.getZ() + Math.sin(a) * ring;
         com.fortuneandfavors.net.FfVfx.particles(targetLevel, ParticleTypes.END_ROD, px, py, pz, 3, 0.02, 0.02, 0.02, 0.0);
         com.fortuneandfavors.net.FfVfx.particles(targetLevel, new DustParticleOptions(-4456443, 1.0F), px, py, pz, 1, 0.0, 0.0, 0.0, 0.0);
      }

      final UUID targetId = target.getUUID();
      final Mob bossRef = boss;
      int delay = GRAND_STOP_TICKS + 2;
      RELEASE.schedule(() -> {
         MinecraftServer server = level.getServer();
         if (server != null) {
            server.execute(() -> Safe.run("time lord grand stop finale", () -> {
               ServerPlayer tp = server.getPlayerList().getPlayer(targetId);
               if (tp != null && bossRef.isAlive()) {
                  ringOfArrows((ServerLevel) tp.level(), bossRef, tp, 16, 4.5F);
                  announce((ServerLevel) tp.level(), SAY + "\"\u00a7fAnd... \u00a7dgo.\u00a7f\"");
               }
            }));
         }
      }, delay * 50L, TimeUnit.MILLISECONDS);
   }

   /** Safe blink: only into clear, solidly-supported ground, never into blocks,
    *  never into lava, never into the void, and never more than {@link #MAX_TELEPORTS}
    *  times per fight. */
   private static boolean safeTeleport(ServerLevel level, Mob boss, Fight fight, ServerPlayer target) {
      boolean toPlayer = RANDOM.nextBoolean();
      for (int attempt = 0; attempt < 10; attempt++) {
         double angle = toPlayer ? RANDOM.nextDouble() * Math.PI * 2.0 : 0.0;
         // Short blinks only. The far branch used to reach 18 blocks, which let
         // him leave the arena entirely and out-range every melee weapon in the
         // mod - a teleport the player cannot answer is not a dodge, it is a
         // disengage.
         double radius = toPlayer ? 3.0 + RANDOM.nextDouble() * 2.0 : 5.0 + RANDOM.nextDouble() * 4.0;
         double bx = toPlayer ? target.getX() + Math.cos(angle) * radius : boss.getX() + (RANDOM.nextDouble() - 0.5) * 2.0 * radius;
         double bz = toPlayer ? target.getZ() + Math.sin(angle) * radius : boss.getZ() + (RANDOM.nextDouble() - 0.5) * 2.0 * radius;
         BlockPos probe = BlockPos.containing(bx, target.getY(), bz);
         BlockPos dest = null;
         for (int dy = 3; dy >= -4; dy--) {
            BlockPos p = probe.offset(0, dy, 0);
            if (level.getBlockState(p).isAir() && level.getBlockState(p.above()).isAir() && !level.getBlockState(p.below()).isAir()) {
               dest = p;
               break;
            }
         }
         if (dest == null) {
            continue;
         }
         Vec3 from = new Vec3(boss.getX(), boss.getY() + 1.0, boss.getZ());
         Vec3 to = new Vec3(dest.getX() + 0.5, dest.getY(), dest.getZ() + 0.5);
         // Wall check: a blink through terrain would let him escape a sealed
         // arena or land inside a wall, so a blocked line of sight is rejected.
         HitResult hit = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, boss));
         if (hit.getType() != HitResult.Type.MISS) {
            continue;
         }
         if (dest.getY() < level.getMinY() + 2 || !level.getFluidState(dest).isEmpty()) {
            continue;
         }

         com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.PORTAL, boss.getX(), boss.getY() + 1.0, boss.getZ(), 30, 0.4, 0.8, 0.4, 0.3);
         level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE, 1.0F, 0.6F);
         boss.teleportTo(to.x, to.y, to.z);
         boss.setDeltaMovement(Vec3.ZERO);
         com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.REVERSE_PORTAL, to.x, to.y + 1.0, to.z, 30, 0.4, 0.8, 0.4, 0.1);
         level.playSound(null, to.x, to.y, to.z, SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE, 1.0F, 1.6F);
         return true;
      }
      return false;
   }

   /** "Five arrows appear around you, then all snap inward." */
   private static void arrowHalo(ServerLevel level, Mob boss, Fight fight, ServerPlayer target) {
      announce(level, SAY + "\"\u00a7fA small paradox.\u00a7f\"");
      level.playSound(null, target.getX(), target.getY(), target.getZ(), SoundEvents.ARROW_SHOOT, SoundSource.HOSTILE, 1.2F, 0.6F);
      double ring = 3.2;
      int count = 5;
      for (int i = 0; i < count; i++) {
         double a = (Math.PI * 2.0 / count) * i + RANDOM.nextDouble() * 0.6;
         double px = target.getX() + Math.cos(a) * ring;
         double py = target.getY() + 1.2;
         double pz = target.getZ() + Math.sin(a) * ring;
         drawRift(level, px, py, pz, 0.35, 8);
      }
      Volley v = new Volley(target.getUUID(), level, boss, count, ServerClock.clock(level) + 12L, fight.phase == 2 ? 4.0F : 3.5F);
      VOLLEYS.add(v);
   }

   private static void fireVolleyArrow(Volley v) {
      ServerPlayer tp = v.level.getServer().getPlayerList().getPlayer(v.target);
      if (tp == null || !tp.isAlive()) {
         v.remaining = 0;
         return;
      }
      Vec3 eye = tp.position().add(0.0, 1.0, 0.0);
      double a = RANDOM.nextDouble() * Math.PI * 2.0;
      double ring = 2.6;
      Vec3 spawn = new Vec3(eye.x + Math.cos(a) * ring, eye.y + 0.6, eye.z + Math.sin(a) * ring);
      spawnArrow(v.level, v.boss, spawn, eye, v.damage, false);
   }

   /** Rifts open around the target and spit randomly chosen junk at them. */
   private static void riftVolley(ServerLevel level, Mob boss, Fight fight, ServerPlayer target) {
      announce(level, SAY + "\"\u00a7fLet's see what falls out of the cracks.\u00a7f\"");
      level.playSound(null, target.getX(), target.getY(), target.getZ(), SoundEvents.PORTAL_TRIGGER, SoundSource.HOSTILE, 1.0F, 0.6F);
      int rifts = 3 + (fight.phase == 2 ? 2 : 0);
      for (int i = 0; i < rifts; i++) {
         double a = RANDOM.nextDouble() * Math.PI * 2.0;
         double radius = 5.0 + RANDOM.nextDouble() * 4.0;
         double px = target.getX() + Math.cos(a) * radius;
         double py = target.getY() + 1.4 + RANDOM.nextDouble();
         double pz = target.getZ() + Math.sin(a) * radius;
         drawRift(level, px, py, pz, 0.5, 16);
         Vec3 from = new Vec3(px, py, pz);
         Vec3 to = target.position().add(0.0, 1.1, 0.0);
         spawnArrow(level, boss, from, to, fight.phase == 2 ? 4.5F : 3.5F, true);
      }
   }

   /** A ring of arrows that all converge on the target at once. */
   private static void ringOfArrows(ServerLevel level, Mob boss, ServerPlayer target, int count, float damage) {
      Vec3 eye = target.position().add(0.0, 1.0, 0.0);
      double radius = 6.0;
      level.playSound(null, target.getX(), target.getY(), target.getZ(), SoundEvents.ARROW_SHOOT, SoundSource.HOSTILE, 2.0F, 0.5F);
      for (int i = 0; i < count; i++) {
         double a = (Math.PI * 2.0 / count) * i;
         Vec3 from = new Vec3(eye.x + Math.cos(a) * radius, eye.y + 0.8, eye.z + Math.sin(a) * radius);
         drawRift(level, from.x, from.y, from.z, 0.3, 4);
         spawnArrow(level, boss, from, eye, damage, false);
      }
   }

   private static void spawnArrow(ServerLevel level, Mob boss, Vec3 from, Vec3 to, float damage, boolean junkPayload) {
      // The fourth constructor argument is the pickup item: passing junk there
      // is what makes a survivable volley actually leave random objects behind.
      ItemStack payload = junkPayload ? JUNK[RANDOM.nextInt(JUNK.length)].copy() : new ItemStack(Items.ARROW);
      Arrow arrow = new Arrow(level, boss, payload, new ItemStack(Items.BOW));
      arrow.setPos(from.x, from.y, from.z);
      Vec3 dir = to.subtract(from);
      if (dir.lengthSqr() < 1.0E-4) {
         dir = new Vec3(0.0, -1.0, 0.0);
      }
      arrow.setDeltaMovement(dir.normalize().scale(1.9));
      // Damage scales with arrow speed on top of this base, and a crit adds
      // more again - so the base is capped and crits are off. A whole volley
      // arriving together still reads as a barrage without one-shotting.
      arrow.setBaseDamage(Math.min(damage, MAX_ARROW_DAMAGE));
      arrow.setCritArrow(false);
      arrow.pickup = AbstractArrow.Pickup.DISALLOWED;
      level.addFreshEntity(arrow);
   }

   // ------------------------------------------------------- time-stop plumbing

   private static void freezeWorld(MinecraftServer server, Fight fight) {
      if (server == null || fight == null) {
         return;
      }
      fight.freezeOwner = "boss:" + fight.bossId;
      fight.frozeServer = acquireFreeze(server, fight.freezeOwner, Math.max(1, fight.stopEndsAtScript - scriptTick));
   }

   private static void unfreeze(MinecraftServer server, Fight fight) {
      fight.freezeUntil = 0L;
      fight.freezeUntilMillis = 0L;
      if (fight.frozeServer && server != null) {
         releaseFreeze(server, fight.freezeOwner);
         // Only a stop that actually froze the world gets the shatter, so the
         // sound always matches something the player saw happen.
         Mob alive = bossOf(server, fight);
         if (alive != null && alive.level() instanceof ServerLevel stopLevel) {
            ChronoFx.stopRelease(stopLevel, fight.stopCenter, 16.0);
         }
      }
      fight.freezeOwner = null;
      fight.frozeServer = false;
      if (server != null) {
         ServerLevel level = server.overworld();
         // The pins are not released here on purpose: each holds for the full
         // length of the stop and then fires its own capped knockback, so the
         // world thawing early can never rob the stop of its punch.
         announce(level, SAY + "\"\u00a7f...and on we go.\u00a7f\"");
         for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.BEACON_ACTIVATE, SoundSource.HOSTILE, 0.8F, 1.4F);
         }
      }
   }

   /** Released through the daemon thread so a world can never stay frozen if the
    *  tick handler stops running. */
   private static void scheduleRelease(MinecraftServer server, Fight fight, int durationTicks) {
      if (server == null) {
         return;
      }
      RELEASE.schedule(() -> server.execute(() -> Safe.run("time lord release", () -> {
         if (fight.frozeServer) {
            unfreeze(server, fight);
         }
      })), durationTicks * 50L + 250L, TimeUnit.MILLISECONDS);
   }

   /** Locks the participants in place, floating if they were airborne. */
   private static void applyStopToParticipants(ServerLevel level, Mob boss, Fight fight, int ticks) {
      Vec3 from = boss.position();
      for (UUID id : fight.participants) {
         ServerPlayer p = level.getServer().getPlayerList().getPlayer(id);
         if (p == null || !p.isAlive()) {
            continue;
         }
         pinPlayer(p, ticks, from);
      }
   }

   /**
    * Pins a player where they stand for {@code ticks}. A stopped player keeps
    * full client-side control, so a stop cannot rely on effects alone (slowness
    * still lets you move, and Jump Boost 128 used to launch people above the
    * clouds). Instead we remember exactly where they were, hold them there, and
    * float them if the stop caught them mid-air.
    */
   private static void pinPlayer(ServerPlayer p, int ticks, Vec3 knockFrom) {
      boolean airborne = !p.onGround();
      Pin pin = new Pin(p.position(), p.getYRot(), p.getXRot(), airborne, ticks, knockFrom == null ? p.position() : knockFrom);
      PINS.put(p.getUUID(), pin);
      p.setSprinting(false);
      p.setDeltaMovement(Vec3.ZERO);
      p.hurtMarked = true;
   }

   private static void tickPins(MinecraftServer server) {
      if (PINS.isEmpty()) {
         return;
      }
      for (Iterator<Map.Entry<UUID, Pin>> it = PINS.entrySet().iterator(); it.hasNext();) {
         Map.Entry<UUID, Pin> entry = it.next();
         Pin pin = entry.getValue();
         ServerPlayer p = server.getPlayerList().getPlayer(entry.getKey());
         if (p == null || !p.isAlive()) {
            it.remove();
            continue;
         }
         if (--pin.ticksLeft <= 0) {
            releasePin(p, pin);
            it.remove();
            continue;
         }
         hold(p, pin);
      }
   }

   /** Live countdown in the action bar for everyone caught in the stop, so a
    *  frozen player knows how long the world is holding still. */
   private static void stopActionBar(MinecraftServer server, Fight fight) {
      if (server == null || fight == null || fight.participants.isEmpty()) {
         return;
      }
      int leftTicks = Math.max(0, fight.stopEndsAtScript - scriptTick);
      int totalTicks = Math.max(1, fight.stopTotalTicks);
      String bar = "\u00a7d\u00a7l\u23f1 TIME STOP \u00a78| \u00a7e" + ChronoFx.bar(totalTicks - leftTicks, totalTicks)
         + " \u00a78| \u00a7f" + String.format(java.util.Locale.ROOT, "%.1f", leftTicks / 20.0) + "s \u00a78| \u00a77nothing moves but him";
      for (UUID id : fight.participants) {
         ServerPlayer p = server.getPlayerList().getPlayer(id);
         if (p != null && p.isAlive()) {
            p.sendOverlayMessage(Component.literal(bar));
         }
      }
   }

   /**
    * Draws every moment that is currently being held - the Time Lord's stops and
    * the Pocket-Watch's bubbles alike.
    *
    * <p>This is deliberately driven by the server tick rather than by vanilla
    * particles on an entity: during a stop there is no entity motion to hang
    * particles off, so the whole visual layer has to be painted by hand. It runs
    * at half rate because these are circles and spheres, not sparks - 10 Hz is
    * smooth for that and costs a fraction of the per-tick version.
    */
   private static void tickChronoAmbience(MinecraftServer server) {
      if (server == null || scriptTick % 2 != 0) {
         return;
      }

      for (Fight fight : new ArrayList<>(FIGHTS.values())) {
         if (!fight.frozeServer || fight.stopCenter == null) {
            continue;
         }
         Mob boss = bossOf(server, fight);
         if (boss != null && boss.level() instanceof ServerLevel level) {
            int elapsed = Math.max(0, fight.stopTotalTicks - Math.max(0, fight.stopEndsAtScript - scriptTick));
            ChronoFx.stopAmbient(level, fight.stopCenter, 18.0, elapsed, fight.stopTotalTicks);
         }
      }

      for (PocketStop stop : POCKET_STOPS) {
         // A bubble that only stilled mobs did not stop the world, so it gets no
         // clock face: the visuals track exactly what actually happened.
         if (!stop.worldFrozen) {
            continue;
         }
         int elapsed = Math.max(0, stop.lengthTicks - Math.max(0, stop.endsAtScript - scriptTick));
         ChronoFx.stopAmbient(stop.level, stop.center, stop.radius * 0.75, elapsed, stop.lengthTicks);
      }
   }

   // ------------------------------------------------------- pocket-watch stops

   private static void tickPocketStops(MinecraftServer server) {
      if (POCKET_STOPS.isEmpty()) {
         return;
      }
      long now = System.currentTimeMillis();
      FROZEN_SHOTS.entrySet().removeIf(e -> e.getValue() <= now);
      for (Iterator<PocketStop> it = POCKET_STOPS.iterator(); it.hasNext();) {
         PocketStop stop = it.next();
         if (scriptTick >= stop.endsAtScript) {
            it.remove();
            Safe.run("pocket watch end", () -> endPocketStop(server, stop));
            continue;
         }
         // Everything hanging in the bubble is the wielder's ammunition now.
         // Only a stop that froze the world is holding projectiles - tagging
         // them for a watch the Time Lord walked through would silently hand the
         // challenger an i-frame bypass for arrows that never stopped moving.
         if (stop.worldFrozen && scriptTick % 2 == 0) {
            Safe.run("pocket watch shots", () -> tagFrozenShots(stop));
         }
         // The wielder's own countdown, with the bar. Only for a real stop: a
         // watch the Time Lord walked through has nothing to count down.
         if (stop.worldFrozen && scriptTick % 4 == 0 && server != null) {
            ServerPlayer caster = server.getPlayerList().getPlayer(stop.caster);
            if (caster != null && caster.isAlive()) {
               int left = Math.max(0, stop.endsAtScript - scriptTick);
               caster.sendOverlayMessage(
                  Component.literal(
                     "\u00a7d\u00a7l\u23f1 TIME STOP \u00a78| \u00a7e"
                        + ChronoFx.bar(stop.lengthTicks - left, stop.lengthTicks)
                        + " \u00a78| \u00a7f"
                        + String.format(java.util.Locale.ROOT, "%.1f", left / 20.0)
                        + "s"
                  )
               );
            }
         }
      }
   }

   /**
    * Marks every projectile held by a stop so it lands through i-frames.
    *
    * <p>Scans a generous box around the bubble rather than the bubble alone:
    * an arrow loosed at a fleeing target is already 30 blocks out when the watch
    * comes down, and missing it would silently reintroduce the "only the first
    * arrow counts" problem this exists to fix.
    */
   private static void tagFrozenShots(PocketStop stop) {
      long until = System.currentTimeMillis() + SHOT_GRACE_MILLIS;
      double reach = stop.radius + 48.0;
      AABB box = new AABB(stop.center, stop.center).inflate(reach);
      for (Entity e : stop.level.getEntities((Entity) null, box, en -> en instanceof Projectile)) {
         FROZEN_SHOTS.put(e.getUUID(), until);
      }
   }

   /**
    * True when this hit came from a projectile a stop was holding. The damage
    * gate clears the victim's invulnerability window on the way through, which is
    * what makes a frozen volley land as a volley.
    */
   public static boolean bypassesIframes(DamageSource source) {
      if (source == null || FROZEN_SHOTS.isEmpty()) {
         return false;
      }
      Entity direct = source.getDirectEntity();
      if (direct == null) {
         return false;
      }
      Long expiry = FROZEN_SHOTS.get(direct.getUUID());
      return expiry != null && expiry > System.currentTimeMillis();
   }

   /** Lifts a Pocket-Watch stop: time resumes, and everything the stop swallowed
    *  lands at once. */
   private static void endPocketStop(MinecraftServer server, PocketStop stop) {
      if (stop.worldFrozen) {
         releaseFreeze(server, stop.owner);
      }
      for (Entity e : stop.frozen) {
         if (e instanceof LivingEntity le && le.isAlive() && le.entityTags().contains(STASIS_TAG)) {
            le.removeTag(STASIS_TAG);
            if (le instanceof Mob mob) {
               mob.setNoAi(false);
            }
         }
      }

      // Banked damage is delivered now - all of it, in one blow per victim.
      for (Iterator<Map.Entry<UUID, Deferred>> it = DEFERRED.entrySet().iterator(); it.hasNext();) {
         Map.Entry<UUID, Deferred> entry = it.next();
         Deferred deferred = entry.getValue();
         if (deferred.stop != stop) {
            continue;
         }
         it.remove();
         LivingEntity victim = null;
         ServerPlayer p = server == null ? null : server.getPlayerList().getPlayer(entry.getKey());
         if (p != null && p.isAlive()) {
            victim = p;
         } else {
            Entity raw = stop.level.getEntity(entry.getKey());
            if (raw instanceof LivingEntity le && le.isAlive()) {
               victim = le;
            }
         }
         if (victim == null) {
            continue;
         }
         DamageSource source = deferred.source != null ? deferred.source : stop.level.damageSources().magic();
         com.fortuneandfavors.net.FfVfx.particles(stop.level, ParticleTypes.DAMAGE_INDICATOR, victim.getX(), victim.getY() + 1.0, victim.getZ(), 14, 0.4, 0.5, 0.4, 0.1);
         stop.level.playSound(null, victim.getX(), victim.getY(), victim.getZ(), SoundEvents.PLAYER_HURT, SoundSource.PLAYERS, 1.0F, 1.0F);
         // One hit at a time, with the i-frames cleared between them. The i-frames are the reason
         // this is a loop rather than a sum: vanilla ignores a second hit inside twenty ticks, so
         // without this the bank paid out one hit and dropped the rest. Cleared only between the
         // banked hits of this one payout - a stop that lifts and lands four blows in the same
         // tick is the stop's business, not a licence to ignore i-frames for anything that
         // follows, which is why the field the game reads is left as vanilla set it afterwards.
         for (float hit : deferred.hits) {
            if (!victim.isAlive()) {
               break;
            }
            victim.invulnerableTime = 0;
            victim.hurtServer(stop.level, source, hit);
         }
         if (victim instanceof ServerPlayer sp) {
            sp.sendOverlayMessage(Component.literal("\u00a7dTime resumes \u00a78| \u00a7c" + String.format(java.util.Locale.ROOT, "%.1f", deferred.amount) + " damage lands at once"));
         }
      }

      // The "time resumes" stinger belongs to a stop that actually stopped time.
      // A refused watch ends quietly instead - his own stop is still running.
      if (stop.worldFrozen) {
         ChronoFx.stopRelease(stop.level, stop.center, stop.radius * 0.8);
         ServerPlayer caster = server == null ? null : server.getPlayerList().getPlayer(stop.caster);
         if (caster != null && caster.isAlive()) {
            ChronoFx.banner(caster, "\u00a7d\u00a7lTIME RESUMES \u00a78| \u00a77every hit you took lands now");
            Chat.msg(caster, "&dTime resumes - &cand every hit you took lands at once.");
         }
      }
   }

   /** Claims the server tick freeze for an owner. Claims are counted, so a
    *  Pocket-Watch lifting can never thaw a boss stop that is still running. */
   private static boolean acquireFreeze(MinecraftServer server, String owner, int durationTicks) {
      if (server == null || owner == null) {
         return false;
      }
      if (SelfTestRunning()) {
         // A frozen server would hang the headless self-test.
         return false;
      }
      try {
         FREEZE_OWNERS.put(owner, System.currentTimeMillis() + (long) durationTicks * 50L + 250L);
         if (!server.tickRateManager().isFrozen()) {
            server.tickRateManager().setFrozen(true);
         }
         return true;
      } catch (Throwable ignored) {
         FREEZE_OWNERS.remove(owner);
         return false;
      }
   }

   /** Drops one owner's claim; only the last owner out actually thaws the world. */
   private static void releaseFreeze(MinecraftServer server, String owner) {
      if (owner != null) {
         FREEZE_OWNERS.remove(owner);
      }
      if (server == null || !FREEZE_OWNERS.isEmpty()) {
         return;
      }
      try {
         if (server.tickRateManager().isFrozen()) {
            server.tickRateManager().setFrozen(false);
         }
      } catch (Throwable ignored) {
      }
   }

   /** One tick of holding a pinned player exactly where time stopped. */
   private static void hold(ServerPlayer p, Pin pin) {
      // A mid-air player is corrected every tick, so the stop genuinely holds
      // them in the air; a grounded one only when they drift. No gravity flag is
      // used: a flag would survive a crash or a disconnect and leave a player
      // floating forever, while a position hold cannot outlive the pin.
      if (pin.airborne || p.position().distanceToSqr(pin.anchor) > PIN_DRIFT * PIN_DRIFT) {
         p.connection.teleport(pin.anchor.x, pin.anchor.y, pin.anchor.z, pin.yaw, pin.pitch);
      }
      p.setDeltaMovement(Vec3.ZERO);
      p.setSprinting(false);
      p.fallDistance = 0.0F;
      p.hurtMarked = true;
      // Refreshed every tick: the effects alone cannot stop a client-driven
      // player, but they stop them from fighting back through the burst.
      p.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 6, 6, false, false, false));
      p.addEffect(new MobEffectInstance(MobEffects.MINING_FATIGUE, 6, 4, false, false, false));
      p.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 6, 3, false, false, false));
      if (scriptTick % 4 == 0 && p.level() instanceof ServerLevel level) {
         com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.END_ROD, p.getX(), p.getY() + 1.0, p.getZ(), 2, 0.25, 0.45, 0.25, 0.0);
      }
   }

   /** Ends a pin: gravity back, then a shove scaled by the damage taken during
    *  the stop and hard-capped so nothing can turn it into a launch. */
   private static void releasePin(ServerPlayer p, Pin pin) {
      p.fallDistance = 0.0F;
      Vec3 velocity = Vec3.ZERO;
      if (pin.damage > 0.1F) {
         Vec3 away = pin.knockFrom == null ? Vec3.ZERO : p.position().subtract(pin.knockFrom);
         away = new Vec3(away.x, 0.0, away.z);
         if (away.lengthSqr() < 1.0E-4) {
            away = new Vec3(0.0, 0.0, 1.0);
         }
         double power = Math.min(pin.damage, MAX_KNOCKBACK_DAMAGE) * KNOCKBACK_PER_DAMAGE;
         power = Math.min(power, MAX_KNOCKBACK);
         velocity = away.normalize().scale(power).add(0.0, Math.min(0.45, power * 0.18), 0.0);
      }
      p.setDeltaMovement(velocity);
      p.hurtMarked = true;
      // The release is one of "his attacks that launch you": it must not also
      // cost you the fall.
      guardFall(p);
   }

   /**
    * Marks a player as thrown by him, so the landing is free.
    *
    * <p>His abilities pick people up (the pin release, the chrono bombs, the
    * finale's beams) and vanilla then charges them for the drop. Being launched
    * by a boss script must never be a second hit, so every launch marks the
    * player and their fall damage is zeroed until they touch the ground.
    */
   private static void guardFall(ServerPlayer p) {
      if (p != null) {
         NO_FALL.put(p.getUUID(), 240);
      }
   }

   private static void tickNoFall(MinecraftServer server) {
      if (NO_FALL.isEmpty() || server == null) {
         return;
      }
      for (Iterator<Map.Entry<UUID, Integer>> it = NO_FALL.entrySet().iterator(); it.hasNext();) {
         Map.Entry<UUID, Integer> entry = it.next();
         ServerPlayer p = server.getPlayerList().getPlayer(entry.getKey());
         if (p == null || !p.isAlive()) {
            it.remove();
            continue;
         }
         p.fallDistance = 0.0F;
         if (p.onGround()) {
            it.remove();
            continue;
         }
         int left = entry.getValue() - 1;
         if (left <= 0) {
            it.remove();
         } else {
            entry.setValue(left);
         }
      }
   }

   /**
    * True while this player's fall damage is being held off by a chrono effect.
    *
    * <p>Public so the anticheat can stand down: a player guarded like this really
    * does descend a long way and take nothing from it, and flagging the mod's own
    * effect as a no-fall hack would make the whole check untrustworthy.
    */
   public static boolean isFallGuarded(ServerPlayer p) {
      return p != null && NO_FALL.containsKey(p.getUUID());
   }

   /** Records damage taken during a stop so its release has some weight. */
   private static void addPinDamage(ServerPlayer p, float amount, Vec3 from) {
      Pin pin = PINS.get(p.getUUID());
      if (pin == null) {
         return;
      }
      pin.damage += amount;
      if (from != null) {
         pin.knockFrom = from;
      }
   }

   /** Drops the pins belonging to a fight that is ending early. */
   private static void releasePins(MinecraftServer server, Fight fight) {
      for (UUID id : new ArrayList<>(fight.participants)) {
         Pin pin = PINS.remove(id);
         if (pin == null) {
            continue;
         }
         ServerPlayer p = server == null ? null : server.getPlayerList().getPlayer(id);
         if (p != null) {
            p.setDeltaMovement(Vec3.ZERO);
            p.hurtMarked = true;
         }
      }
   }

   private static void releaseAllPins(MinecraftServer server) {
      if (server != null) {
         for (UUID id : new ArrayList<>(PINS.keySet())) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p != null) {
               p.setDeltaMovement(Vec3.ZERO);
               p.hurtMarked = true;
            }
         }
      }
      PINS.clear();
   }

   /** While the world is frozen, he crosses the gap and strikes at his leisure. */
   private static void stopStrike(ServerLevel level, Mob boss, Fight fight, ServerPlayer target) {
      Vec3 away = target.position().subtract(boss.position());
      away = new Vec3(away.x, 0.0, away.z);
      if (away.lengthSqr() < 1.0E-4) {
         away = new Vec3(1.0, 0.0, 0.0);
      }
      Vec3 dest = target.position().subtract(away.normalize().scale(1.6));
      // On the ground, not at the player's own height. This step moves him by hand
      // with no collision check, so `target.getY()` used to drop him *into* the
      // hillside whenever the arena was not flat - he was beside you and inside a
      // wall at the same time. The column is searched from the top down, and if there
      // is nowhere to stand within a step of the target he strikes from where he
      // already is rather than burying himself.
      double floor = BossGrounding.surfaceY(level, dest.x, dest.z, target.getY());
      if (Math.abs(floor - target.getY()) <= 3.0 && BossGrounding.standable(level, dest.x, floor, dest.z)) {
         boss.teleportTo(dest.x, floor, dest.z);
         boss.setDeltaMovement(Vec3.ZERO);
      }
      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.REVERSE_PORTAL, boss.getX(), boss.getY() + 1.0, boss.getZ(), 20, 0.3, 0.6, 0.3, 0.1);
      level.playSound(null, target.getX(), target.getY(), target.getZ(), SoundEvents.PLAYER_ATTACK_CRIT, SoundSource.HOSTILE, 1.0F, 0.6F);
      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.DAMAGE_INDICATOR, target.getX(), target.getY() + 1.2, target.getZ(), 6, 0.3, 0.3, 0.3, 0.05);
      float damage = fight.phase == 2 ? 5.0F : 4.0F;
      target.hurtServer(level, level.damageSources().mobAttack(boss), damage);
      addPinDamage(target, damage, boss.position());
   }

   /** A countdown underfoot: three to five spots tick, then go off together. */
   private static void chronoBombs(ServerLevel level, Mob boss, Fight fight, ServerPlayer target) {
      announce(level, SAY + "\"§fTick. §dTock.§f\"");
      level.playSound(null, target.getX(), target.getY(), target.getZ(), SoundEvents.LEVER_CLICK, SoundSource.HOSTILE, 1.2F, 0.6F);
      List<ServerPlayer> victims = new ArrayList<>(
         level.getPlayers(q -> q.isAlive() && q.gameMode.isSurvival() && q.distanceToSqr(boss) < ARENA_RADIUS * ARENA_RADIUS)
      );
      if (victims.isEmpty()) {
         victims.add(target);
      }
      int count = fight.phase == 2 ? 5 : 3;
      for (int i = 0; i < count; i++) {
         ServerPlayer victim = victims.get(i % victims.size());
         BOMBS.add(new Bomb(level, victim.position(), fight.phase == 2 ? 7.0F : 5.0F, 45));
      }
      for (Bomb bomb : BOMBS) {
         drawBomb(level, bomb.at);
      }
   }

   private static void drawBomb(ServerLevel level, Vec3 at) {
      for (int i = 0; i < 8; i++) {
         double a = Math.PI * 2.0 * i / 8.0;
         com.fortuneandfavors.net.FfVfx.particles(level, new DustParticleOptions(-4456443, 1.0F), at.x + Math.cos(a) * 1.1, at.y + 0.2, at.z + Math.sin(a) * 1.1, 1, 0.0, 0.0, 0.0, 0.0);
      }
      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.END_ROD, at.x, at.y + 2.3, at.z, 1, 0.0, 0.0, 0.0, 0.0);
   }

   private static void tickBombs(MinecraftServer server) {
      if (BOMBS.isEmpty()) {
         return;
      }
      for (Iterator<Bomb> it = BOMBS.iterator(); it.hasNext();) {
         Bomb bomb = it.next();
         if (bomb.ticks-- > 0) {
            if (bomb.ticks % 5 == 0) {
               drawBomb(bomb.level, bomb.at);
            }
            continue;
         }
         it.remove();
         detonate(bomb);
      }
   }

   private static void detonate(Bomb bomb) {
      ServerLevel level = bomb.level;
      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.EXPLOSION, bomb.at.x, bomb.at.y + 0.3, bomb.at.z, 3, 0.2, 0.1, 0.2, 0.0);
      com.fortuneandfavors.net.FfVfx.particles(level, new DustParticleOptions(-4456443, 1.8F), bomb.at.x, bomb.at.y + 0.4, bomb.at.z, 40, 0.8, 0.5, 0.8, 0.1);
      level.playSound(null, bomb.at.x, bomb.at.y, bomb.at.z, SoundEvents.GENERIC_EXPLODE, SoundSource.HOSTILE, 1.0F, 1.4F);
      for (ServerPlayer p : level.getPlayers(q -> q.isAlive() && q.distanceToSqr(bomb.at) < 9.0)) {
         p.hurtServer(level, level.damageSources().magic(), bomb.damage);
         p.setDeltaMovement(p.getDeltaMovement().add(0.0, 0.4, 0.0));
         p.hurtMarked = true;
         guardFall(p);
      }
   }

   /**
    * The ring goes down: the announcement, the sound, and the first second of the tell.
    *
    * <p>Three things at once, because the move is only fair if the room knows it is happening:
    * he says so, the sky says so, and every participant gets the one-line instruction that
    * matters - <b>walk out</b>. Nothing about the tell is subtle; a move that punishes standing
    * still has to be legible from the far side of the arena.
    */
   private static void startNova(ServerLevel level, Mob boss, Fight fight) {
      announce(level, SAY + "\"\u00a7fYou've got all the time in the world. \u00a7dI'll take it.\"");
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.BEACON_ACTIVATE, SoundSource.HOSTILE, 1.8F, 0.5F);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.HOSTILE, 1.6F, 0.4F);
      drawNovaTell(level, boss, fight, ServerClock.clock(level));

      MinecraftServer server = level.getServer();
      if (server == null) {
         return;
      }
      for (UUID id : fight.participants) {
         ServerPlayer p = server.getPlayerList().getPlayer(id);
         if (p != null && p.isAlive()) {
            p.sendOverlayMessage(Component.literal("\u00a7d\u00a7lTHE HOUR THAT NEVER WAS \u00a78| \u00a7fget out of the ring"));
         }
      }
   }

   /** The closing ring, drawn on the floor he is standing on. */
   private static void drawNovaTell(ServerLevel level, Mob boss, Fight fight, long now) {
      long left = Math.max(0L, fight.novaUntil - now);
      double t = 1.0 - (double)left / NOVA_TELL;
      double radius = NOVA_RANGE * (1.0 - t * 0.6);
      int points = 72;

      for (int i = 0; i < points; i++) {
         double angle = i / (double)points * Math.PI * 2.0 + t * 2.0;
         double x = boss.getX() + Math.cos(angle) * radius;
         double z = boss.getZ() + Math.sin(angle) * radius;
         double y = BossGrounding.groundY(level, x, z, boss.getY()) + 0.2;
         com.fortuneandfavors.net.FfVfx.particles(level, new DustParticleOptions(-4456443, 1.2F), x, y, z, 1, 0.0, 0.0, 0.0, 0.0);
         if (i % 6 == 0) {
            com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.END_ROD, x, y + 0.5, z, 1, 0.02, 0.02, 0.02, 0.0);
         }
      }

      // The hands: a slow sweep above him that counts the three seconds out.
      double sweep = t * Math.PI * 2.0;
      for (double d = 0.0; d < 5.0; d += 0.4) {
         com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.END_ROD,
            boss.getX() + Math.cos(sweep) * d, boss.getY() + 3.2, boss.getZ() + Math.sin(sweep) * d,
            1, 0.0, 0.0, 0.0, 0.0
         );
      }

      if (scriptTick % 6 == 0) {
         level.playSound(
            null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.HOSTILE,
            1.2F, 0.4F + (float)t * 0.6F
         );
      }
   }

   /**
    * The ring closes: everything inside it is spent at once.
    *
    * <p>Whoever is left inside takes the hit, two more years off the clock, and a shove out of
    * the middle - and the whole room hears it, so the players who did walk out get to know
    * they chose right. The damage is his (so the kill is his), and the fall that follows is
    * guarded, because being launched through a roof is a bug rather than a punishment.
    */
   private static void detonateNova(ServerLevel level, Mob boss, Fight fight) {
      fight.novaUntil = 0L;
      double x = boss.getX();
      double y = boss.getY();
      double z = boss.getZ();

      level.playSound(null, x, y, z, SoundEvents.GENERIC_EXPLODE, SoundSource.HOSTILE, 2.0F, 0.4F);
      level.playSound(null, x, y, z, SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.HOSTILE, 2.0F, 0.3F);
      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.EXPLOSION_EMITTER, x, y + 1.0, z, 3, 1.2, 0.6, 1.2, 0.0);

      for (int ring = 0; ring < 4; ring++) {
         double radius = 3.0 + ring * 4.0;
         for (int i = 0; i < 72; i++) {
            double angle = i / 72.0 * Math.PI * 2.0;
            double px = x + Math.cos(angle) * radius;
            double pz = z + Math.sin(angle) * radius;
            double py = BossGrounding.groundY(level, px, pz, y) + 0.3;
            com.fortuneandfavors.net.FfVfx.particles(level, new DustParticleOptions(-4456443, 1.4F), px, py, pz, 1, 0.05, 0.05, 0.05, 0.0);
         }
      }

      for (int i = 0; i < 20; i++) {
         com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.END_ROD, x, y + i * 0.7, z, 3, 0.4, 0.2, 0.4, 0.02);
      }

      int caught = 0;

      for (ServerPlayer p : level.getPlayers(q -> q.isAlive() && !q.isSpectator() && q.distanceToSqr(x, y, z) <= NOVA_RANGE * NOVA_RANGE)) {
         p.hurtServer(level, level.damageSources().mobAttack(boss), NOVA_DAMAGE);
         agedToDeath(p);

         if (!p.isAlive()) {
            continue;
         }

         takeYears(level, p, NOVA_YEARS);
         p.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 100, 1, false, true, true));
         p.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 100, 0, false, true, true));

         Vec3 away = p.position().subtract(boss.position());
         Vec3 flat = new Vec3(away.x, 0.0, away.z);
         if (flat.lengthSqr() > 1.0E-4) {
            flat = flat.normalize().scale(1.4);
            p.setDeltaMovement(p.getDeltaMovement().add(flat.x, 0.7, flat.z));
            p.hurtMarked = true;
         }
         guardFall(p);
         caught++;
      }

      if (caught > 0) {
         announce(level, SAY + "\"\u00a7fThat second's gone. \u00a7dNo refunds.\"");
      }
   }

   /** A beam of stolen years: hitscan, ages the target and wrings them out. */
   private static void agingRay(ServerLevel level, Mob boss, Fight fight, ServerPlayer target) {
      fight.rays++;
      announce(level, SAY + "\"§fYou have such a §dpast§f ahead of you.§f\"");
      Vec3 from = boss.position().add(0.0, 1.2, 0.0);
      Vec3 to = target.position().add(0.0, 1.0, 0.0);
      Vec3 dir = to.subtract(from);
      double length = dir.length();
      if (length < 0.001) {
         return;
      }
      dir = dir.normalize();
      for (double d = 0.0; d < length; d += 0.35) {
         Vec3 point = from.add(dir.scale(d));
         com.fortuneandfavors.net.FfVfx.particles(level, new DustParticleOptions(-4456443, 1.0F), point.x, point.y, point.z, 2, 0.05, 0.05, 0.05, 0.0);
         // A second, brighter thread on the same line: the beam is the one move a player is
         // supposed to *see* coming at them from across the arena.
         if (d % 1.4 < 0.35) {
            com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.END_ROD, point.x, point.y, point.z, 1, 0.03, 0.03, 0.03, 0.0);
         }
      }
      // The impact bloom on the body it lands on, so the hit reads from the side as well as
      // from the target's own screen.
      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.SCULK_SOUL, target.getX(), target.getY() + 1.0, target.getZ(), 22, 0.5, 0.7, 0.5, 0.06);
      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.REVERSE_PORTAL, target.getX(), target.getY() + 1.0, target.getZ(), 30, 0.6, 0.8, 0.6, 0.2);
      level.playSound(null, target.getX(), target.getY(), target.getZ(), SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.HOSTILE, 1.2F, 0.6F);
      target.hurtServer(level, level.damageSources().mobAttack(boss), fight.phase == 2 ? 8.0F : 6.0F);
      agedToDeath(target);
      takeYears(level, target, RAY_YEARS);
      target.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 120, 1, false, true, true));
      target.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 120, 0, false, true, true));
      if (PINS.containsKey(target.getUUID())) {
         addPinDamage(target, fight.phase == 2 ? 8.0F : 6.0F, boss.position());
      }
   }

   /** Below half health he steps back through his own timeline: a heal and a
    *  blink, once per fight. */
   private static void rewindSelf(ServerLevel level, Mob boss, Fight fight, ServerPlayer target) {
      float heal = boss.getMaxHealth() * 0.08F;
      boss.setHealth(Math.min(boss.getMaxHealth(), boss.getHealth() + heal));
      riftBurst(level, boss.getX(), boss.getY() + 1.0, boss.getZ(), 60);
      announce(level, SAY + "\"§fNo. §dLet's undo that.§f\"");
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.HOSTILE, 1.4F, 0.6F);
      if (target != null) {
         safeTeleport(level, boss, fight, target);
      }
   }

   // --------------------------------------------------------------- the finale

   /**
    * His last stand. At {@link #FINALE_AT} of his health he stops trading blows, floats clear of the
    * melee and wraps himself in a halo of stolen years, then walks a beam of
    * pure aging across everyone still standing. Age stacks faster than it can be
    * healed: anyone who does not land one clean hit on him before the judgment
    * ends is reduced to bone. Landing a hit spares that player immediately.
    *
    * <p>The finale always ends the fight - he is spent either way - so there is
    * no window where the boss is unkillable and the raid cannot finish.
    */
   /**
    * "The same second, again" - the phase-three signature.
    *
    * <p>He does not attack you with it. He winds the last eight seconds of the
    * fight back over everybody's head: every participant is put back exactly where
    * they stood eight seconds ago, and he takes his own injuries from that moment
    * back with him, capped at a third of his health. It is the only move in the
    * fight that costs you progress instead of hearts, and it is the only one whose
    * answer is to <b>stand still</b>: a body that spent the tell planted on the
    * spot has nothing to be wound back to, so nothing happens to it. That is the
    * whole joke - he has spent the fight punishing players for holding ground, and
    * the way to keep your ground here is to hold it.
    */
   private static void startLoop(MinecraftServer server, ServerLevel level, Mob boss, Fight fight) {
      fight.loopTicks = LOOP_WINDUP;
      fight.loopWatch.clear();

      for (UUID id : fight.participants) {
         ServerPlayer p = server.getPlayerList().getPlayer(id);
         if (p != null && p.isAlive()) {
            fight.loopWatch.put(id, p.position());
            p.sendOverlayMessage(Component.literal("\u00a7d\u00a7lHOLD STILL \u00a78| \u00a7fthe clock is winding back"));
            p.sendSystemMessage(Component.literal("\u00a7d\u2726 \u00a7fThe Time Lord winds the second back - \u00a7dstand still\u00a7f and there is nothing for him to take."));
         }
      }

      announce(level, SAY + "\"\u00a7fNope. \u00a7dBack three moves. Again.\u00a7f\"");
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.BEACON_DEACTIVATE, SoundSource.HOSTILE, 1.2F, 0.4F);
   }

   private static void tickLoop(MinecraftServer server, ServerLevel level, Mob boss, Fight fight) {
      fight.loopTicks--;
      double t = (double)(LOOP_WINDUP - fight.loopTicks) / LOOP_WINDUP;

      // A dial winding backwards: the hands shrink inward toward him as it runs.
      int hands = 22;

      for (int i = 0; i < hands; i++) {
         double a = i / (double)hands * Math.PI * 2.0 - scriptTick * 0.28;
         double r = 7.0 * (1.0 - t) + 1.2;
         com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.END_ROD, boss.getX() + Math.cos(a) * r, boss.getY() + 0.5, boss.getZ() + Math.sin(a) * r, 1, 0.0, 0.0, 0.0, 0.0);
      }

      com.fortuneandfavors.net.FfVfx.particles(level, new DustParticleOptions(-8257537, 1.0F), boss.getX(), boss.getY() + 1.2, boss.getZ(), 4, 0.6, 0.5, 0.6, 0.0);

      if (fight.loopTicks % 10 == 0) {
         for (UUID id : fight.participants) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p != null && p.isAlive()) {
               Vec3 mark = fight.loopWatch.get(id);
               boolean still = !rewindWouldMove(p.position(), mark);
               p.sendOverlayMessage(
                  Component.literal(
                     (still ? "\u00a7a\u00a7lHELD \u00a78| \u00a7fnothing to take" : "\u00a7d\u00a7lREWIND IN \u00a78| \u00a7f") + Math.max(1, (fight.loopTicks + 19) / 20) + "s\u00a7f"
                  )
               );
            }
         }

         level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.HOSTILE, 0.8F, 0.5F + (float)t);
      }

      if (fight.loopTicks <= 0) {
         applyLoop(server, level, boss, fight);
      }
   }

   private static void applyLoop(MinecraftServer server, ServerLevel level, Mob boss, Fight fight) {
      int pulled = 0;

      for (UUID id : new ArrayList<>(fight.participants)) {
         ServerPlayer p = server.getPlayerList().getPlayer(id);
         if (p == null || !p.isAlive()) {
            continue;
         }

         ArrayDeque<Vec3> trail = fight.trails.get(id);
         if (trail == null || trail.isEmpty()) {
            continue;
         }

         Vec3 then = trail.peekFirst();
         if (!rewindWouldMove(p.position(), then)) {
            p.sendOverlayMessage(Component.literal("\u00a7a\u00a7lHELD \u00a78| \u00a7fyou did not move - there was nothing to take"));
            continue;
         }

         double x = then.x;
         double z = then.z;
         double y = then.y;
         if (!BossGrounding.standable(level, x, y, z)) {
            y = BossGrounding.surfaceY(level, x, z, y);
         }

         if (!BossGrounding.standable(level, x, y, z)) {
            continue;
         }

         com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.REVERSE_PORTAL, p.getX(), p.getY() + 1.0, p.getZ(), 30, 0.5, 0.7, 0.5, 0.06);
         p.teleportTo(level, x, y, z, Set.of(), p.getYRot(), p.getXRot(), true);
         com.fortuneandfavors.anticheat.AntiCheat.onServerTeleport(p, "the Time Lord winding the second back");
         p.setDeltaMovement(Vec3.ZERO);
         p.hurtMarked = true;
         com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.REVERSE_PORTAL, x, y + 1.0, z, 30, 0.5, 0.7, 0.5, 0.06);
         com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.END_ROD, x, y + 1.0, z, 16, 0.5, 0.6, 0.5, 0.05);
         pulled++;
      }

      // And his own last eight seconds come back with him: the wounds he was
      // carrying then, never more than a third of his health, so a rewind is a
      // setback rather than a reset.
      Moment past = fight.bossTrail.peekFirst();
      if (past != null) {
         float lost = Math.max(0.0F, past.health() - boss.getHealth());
         float heal = Math.min(lost, (float)(boss.getMaxHealth() * LOOP_HEAL_CAP));
         if (heal > 0.0F) {
            boss.heal(heal);
            com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.HEART, boss.getX(), boss.getY() + 1.7, boss.getZ(), 12, 0.6, 0.6, 0.6, 0.02);
         }
      }

      announce(
         level,
         SAY
            + (pulled > 0
               ? "\"\u00a7fEight seconds of your work, gone. \u00a7dDo it again.\u00a7f\""
               : "\"\u00a7fNot one of you moved. \u00a7dHow very disappointing.\u00a7f\"")
      );
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.ENDER_DRAGON_GROWL, SoundSource.HOSTILE, 1.0F, 1.8F);
      riftBurst(level, boss.getX(), boss.getY() + 1.0, boss.getZ(), 70);
   }

   /**
    * True when the second has somewhere to put a body back: it has actually been
    * somewhere in the last eight seconds. Also the answer to the move - see
    * {@link #LOOP_STILL_BLOCKS}.
    */
   public static boolean rewindWouldMove(Vec3 here, Vec3 then) {
      return here != null && then != null && here.distanceToSqr(then) >= LOOP_STILL_BLOCKS * LOOP_STILL_BLOCKS;
   }

   /**
    * Samples the last eight seconds of the fight, twice a second.
    *
    * <p>Ring buffers, capped by construction: the boss keeps where he stood and
    * how much health he had, every participant keeps where they stood. Nothing is
    * kept past the window, so a long fight retains a fixed amount.
    */
   private static void recordTrails(MinecraftServer server, Mob boss, Fight fight) {
      long now = ServerClock.clock(boss.level());
      if (now < fight.nextLoopTrail) {
         return;
      }

      fight.nextLoopTrail = now + LOOP_SAMPLE_EVERY;
      int keep = LOOP_TICKS / LOOP_SAMPLE_EVERY;
      fight.bossTrail.addLast(new Moment(boss.position(), boss.getHealth()));

      while (fight.bossTrail.size() > keep) {
         fight.bossTrail.removeFirst();
      }

      for (UUID id : fight.participants) {
         ServerPlayer p = server.getPlayerList().getPlayer(id);
         if (p == null || !p.isAlive()) {
            continue;
         }

         ArrayDeque<Vec3> trail = fight.trails.computeIfAbsent(id, k -> new ArrayDeque<>());
         trail.addLast(p.position());

         while (trail.size() > keep) {
            trail.removeFirst();
         }
      }
   }

   private static void startFinale(MinecraftServer server, ServerLevel level, Mob boss, Fight fight, ServerPlayer target) {
      fight.finale = true;
      fight.finaleDone = true;
      fight.finaleTicks = FINALE_TICKS;
      fight.finaleHits.clear();
      // Phase three: the rewind is available from the first beat of the judgment
      // rather than whenever its cooldown happened to land.
      fight.nextLoop = 0L;
      fight.nextStop = ServerClock.clock(level) + 400L;
      announce(level, SAY + "\"\u00a7fEnough. \u00a7dI'll just take the years you've got left.\u00a7f\"");
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.ENDER_DRAGON_GROWL, SoundSource.HOSTILE, 1.6F, 0.4F);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.BEACON_ACTIVATE, SoundSource.HOSTILE, 1.4F, 0.5F);
      riftBurst(level, boss.getX(), boss.getY() + 1.0, boss.getZ(), 120);

      // He steps out of reach, and out of the melee: you have to come to him.
      if (target != null) {
         Vec3 away = boss.position().subtract(target.position());
         away = new Vec3(away.x, 0.0, away.z);
         if (away.lengthSqr() < 1.0E-4) {
            away = new Vec3(1.0, 0.0, 0.0);
         }
         Vec3 dest = target.position().add(away.normalize().scale(FINALE_RANGE * 0.8));
         // On the ground, not three blocks above it. He used to step out of the
         // melee and *up*, which left him hanging in the sky for the whole
         // judgment - and a boss you cannot reach is a fight you cannot win.
         //
         // Searched from the top of the column, and only taken when the spot is
         // really standable: asking for "the floor near where I already am" answers
         // with the block his own feet are level with the moment the destination is
         // uphill from him, which is how he stepped into the hillside and disappeared.
         double floor = BossGrounding.surfaceY(level, dest.x, dest.z, boss.getY());
         if (BossGrounding.standable(level, dest.x, floor, dest.z)) {
            boss.teleportTo(dest.x, floor, dest.z);
            boss.setDeltaMovement(Vec3.ZERO);
         } else {
            // Nowhere to stand out there: hold the ground he has instead of walking
            // into the terrain. He is glowing and findable either way.
            BossGrounding.resurface(level, boss, 0.6);
         }

         riftBurst(level, boss.getX(), boss.getY() + 1.0, boss.getZ(), 90);
      }

      for (UUID id : fight.participants) {
         ServerPlayer p = server.getPlayerList().getPlayer(id);
         if (p != null && p.isAlive()) {
            p.sendOverlayMessage(Component.literal("\u00a7d\u00a7lJUDGMENT\u00a78 | \u00a7fhit him once to live"));
            // He is on the ground now, glowing so he can be found - the line used
            // to promise he was hanging in the air, which was both the bug and
            // the instruction not to go looking for him down there.
            p.sendSystemMessage(Component.literal("\u00a7d\u2726 \u00a7fThe Time Lord stands his ground, \u00a7dglowing\u00a7f. One hit on him ends the aging."));
         }
      }
   }

   private static void tickFinale(MinecraftServer server, ServerLevel level, Mob boss, Fight fight) {
      fight.finaleTicks--;
      if (fight.bar != null) {
         fight.bar.setName(Component.literal("\u00a7dThe Time Lord \u00a78| \u00a7f\u00a7lJUDGMENT \u00a78- \u00a77hit him once to break it"));
      }
      // Everyone still standing has landed their hit: the judgment is broken and he goes now,
      // rather than soaking blows for the rest of the timer - which read as a boss with no end.
      boolean anyoneUnsaved = false;
      for (UUID id : fight.participants) {
         ServerPlayer p = server.getPlayerList().getPlayer(id);
         if (p != null && p.isAlive() && !fight.finaleHits.contains(id)) {
            anyoneUnsaved = true;
            break;
         }
      }
      if (!anyoneUnsaved && !fight.finaleHits.isEmpty()) {
         finishFinale(server, level, boss, fight);
         return;
      }
      ServerPlayer target = nearestTarget(level, boss, fight);

      // He stands there, glowing, so he can be found through terrain: the whole
      // point of the judgment is that you have to reach him.
      boss.addEffect(new MobEffectInstance(MobEffects.GLOWING, 20, 0, false, false, false));
      boss.setNoGravity(true);
      // On the surface of the column he is in, not merely clamped down to a floor:
      // a boss standing inside a hillside is one no downward clamp can see, and
      // being inside terrain is the whole of "he teleports underground".
      BossGrounding.resurface(level, boss, 0.6);
      if (target != null) {
         // He holds his ground for the judgment.
         //
         // He used to back away whenever the player got inside FINALE_RANGE - a
         // scripted retreat with no block check, so walking him up to him pushed him
         // through the terrain behind him and left him drifting off the far side of a
         // hill. Chasing a boss you cannot reach is not a fight, and the whole
         // instruction is that one hit on him ends it.
         boss.setYRot(faceYaw(boss, target));
      }

      drawFinaleHalo(level, boss);
      if (scriptTick % 5 == 0) {
         level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.HOSTILE, 1.1F, 0.35F + (FINALE_TICKS - fight.finaleTicks) * 0.004F);
      }

      // A beam at every player who has not yet earned their escape, in rounds.
      if (fight.finaleTicks % FINALE_AGING_EVERY == 0) {
         for (UUID id : new ArrayList<>(fight.participants)) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p == null || !p.isAlive() || fight.finaleHits.contains(id)) {
               continue;
            }
            finaleBeam(level, boss, p);
         }
      }

      if (fight.finaleTicks <= 0) {
         finishFinale(server, level, boss, fight);
      }
   }

   /** One aging beam: a thick ray of stolen years, plus a stack of age. */
   private static void finaleBeam(ServerLevel level, Mob boss, ServerPlayer target) {
      Vec3 from = boss.position().add(0.0, 1.4, 0.0);
      Vec3 to = target.position().add(0.0, 1.0, 0.0);
      Vec3 dir = to.subtract(from);
      double length = dir.length();
      if (length < 0.001) {
         return;
      }
      Vec3 unit = dir.normalize();
      for (double d = 0.0; d < length; d += 0.2) {
         Vec3 point = from.add(unit.scale(d));
         com.fortuneandfavors.net.FfVfx.particles(level, new DustParticleOptions(-4456443, 1.7F), point.x, point.y, point.z, 2, 0.06, 0.06, 0.06, 0.0);
         com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.END_ROD, point.x, point.y, point.z, 1, 0.05, 0.05, 0.05, 0.0);
      }
      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.SCULK_SOUL, target.getX(), target.getY() + 1.0, target.getZ(), 18, 0.4, 0.6, 0.4, 0.05);
      level.playSound(null, target.getX(), target.getY(), target.getZ(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.HOSTILE, 1.3F, 0.45F);
      target.hurtServer(level, level.damageSources().magic(), 2.0F);
      agedToDeath(target);
      if (!target.isAlive()) {
         return;
      }
      target.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 60, 1, false, false, false));
      target.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 60, 1, false, false, false));
      takeYears(level, target, BEAM_YEARS);
      if (Aging.stacks(target) > 0) {
         target.sendOverlayMessage(
            Component.literal("\u00a75Aging \u00a78|\u00a75 " + Aging.stacks(target) + "/" + DUST_AT + " \u00a78- \u00a7fhit him once to stop it")
         );
      }
   }

   /**
    * The aging ledger's own arithmetic, in one place.
    *
    * <p>A blast takes {@code years} off the clock and <b>never</b> takes the last
    * one: five years is a whole life, so a single hitscan beam would be an
    * unavoidable death sentence, and this boss is built to be dangerous rather
    * than unfair. The beam takes a player to their last year and the counter says
    * so out loud; the year after that is theirs to lose.
    */
   private static void tickAgingCountdown(MinecraftServer server) {
      if (scriptTick % 20L != 0L) {
         return;
      }
      for (ServerPlayer p : server.getPlayerList().getPlayers()) {
         int aged = Aging.stacks(p);
         if (aged > 0) {
            p.sendOverlayMessage(Component.literal(
               "\u00a75Aged \u00a7f" + (DUST_AT - aged) + "/" + DUST_AT + " years left \u00a78| \u00a77one back in "
                  + Math.max(1L, (Aging.nextBackIn(p) + 19L) / 20L) + "s"
            ));
         }
      }
   }

   private static void takeYears(ServerLevel level, ServerPlayer target, int years) {
      int left = Math.min(DUST_AT - 1, Aging.stacks(target) + years);
      Aging.set(target, left);
      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.SCULK_SOUL, target.getX(), target.getY() + 1.0, target.getZ(), 14, 0.4, 0.6, 0.4, 0.05);
      level.playSound(null, target.getX(), target.getY(), target.getZ(), SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.HOSTILE, 1.0F, 0.5F);
      target.sendOverlayMessage(
         Component.literal(
            "\u00a75" + years + " years taken \u00a78| \u00a7f" + (DUST_AT - left) + (DUST_AT - left == 1 ? " year left" : " years left")
         )
      );
      target.sendSystemMessage(
         Component.literal(
            "\u00a7d\u2726 \u00a7fHe takes \u00a7d" + years + (years == 1 ? " year" : " years") + "\u00a7f from you \u00a78- \u00a7f" + (DUST_AT - left) + " of " + DUST_AT + " still yours."
         )
      );
   }

   /** Time catches up all at once: the victim falls where they stand and a
    *  skeleton wearing their name takes their place in the arena. */
   private static void reduceToSkeleton(ServerLevel level, ServerPlayer player) {
      Aging.clear(player);
      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.SCULK_SOUL, player.getX(), player.getY() + 1.0, player.getZ(), 60, 0.5, 0.9, 0.5, 0.12);
      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.END_ROD, player.getX(), player.getY() + 1.0, player.getZ(), 40, 0.5, 0.9, 0.5, 0.1);
      level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.WITHER_DEATH, SoundSource.HOSTILE, 1.0F, 1.4F);
      level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.SKELETON_DEATH, SoundSource.HOSTILE, 1.4F, 0.8F);
      try {
         Skeleton skeleton = EntityTypes.SKELETON.create(level, EntitySpawnReason.MOB_SUMMONED);
         if (skeleton != null) {
            skeleton.setPos(player.getX(), player.getY(), player.getZ());
            skeleton.setCustomName(Component.literal("\u00a77Reduced: \u00a7f" + player.getName().getString()));
            skeleton.setCustomNameVisible(true);
            skeleton.setPersistenceRequired();
            level.addFreshEntity(skeleton);
         }
      } catch (Throwable ignored) {
      }
      player.hurtServer(level, level.damageSources().magic(), player.getMaxHealth() * 4.0F + 100.0F);
      // Reduced to bone by his own age: the beam's death, whichever way it lands.
      Advancements.grant(player, "aged_to_death");
   }

   /**
    * The aging beam's kill, booked where the beam lands.
    *
    * <p>Four stacks of age and a beam can kill you outright - the beam is damage
    * like any other - so the death is not always the fifth stack and the skeleton
    * that goes with it. Both are "died to the aging beam", and this is the half
    * that has to be caught at the damage itself.
    */
   private static void agedToDeath(ServerPlayer target) {
      if (target != null && !target.isAlive()) {
         Advancements.grant(target, "aged_to_death");
      }
   }

   private static void finishFinale(MinecraftServer server, ServerLevel level, Mob boss, Fight fight) {
      for (UUID id : new ArrayList<>(fight.participants)) {
         ServerPlayer p = server.getPlayerList().getPlayer(id);
         if (p != null && p.isAlive() && !fight.finaleHits.contains(id)) {
            reduceToSkeleton(level, p);
         }
      }
      for (UUID id : fight.finaleHits) {
         ServerPlayer p = server.getPlayerList().getPlayer(id);
         if (p != null && p.isAlive()) {
            p.sendOverlayMessage(Component.literal("\u00a7dYou kept pace with him. \u00a7fHe lets you go."));
         }
      }
      fight.finale = false;
      fight.finaleDone = true;
      boss.setNoGravity(false);
      announce(level, SAY + "\"\u00a7f...Fine. \u00a7dYou kept up. Nobody keeps up.\u00a7f\"");
      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.TOTEM_OF_UNDYING, boss.getX(), boss.getY() + 1.0, boss.getZ(), 80, 1.2, 1.4, 1.2, 0.3);
      // He is spent either way: the paradox lets go and the normal death
      // ceremony - with its loot - plays out from here.
      fight.dying = true;
      fight.deathTicks = DEATH_TICKS;
      riftDeathCue(level, boss);
   }

   /** The halo of stolen years that marks him during the judgment. */
   private static void drawFinaleHalo(ServerLevel level, Mob boss) {
      long t = ServerClock.clock(level);
      double x = boss.getX();
      double y = boss.getY() + 1.4;
      double z = boss.getZ();
      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.ELECTRIC_SPARK, x, y, z, 14, 1.3, 1.3, 1.3, 0.06);
      com.fortuneandfavors.net.FfVfx.particles(level, new DustParticleOptions(-4456443, 1.5F), x, y, z, 10, 1.2, 1.2, 1.2, 0.02);
      for (int i = 0; i < 4; i++) {
         double a = t * 0.15 + i * (Math.PI / 2.0);
         com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.END_ROD, x + Math.cos(a) * 1.6, y + Math.sin(t * 0.2 + i) * 0.4, z + Math.sin(a) * 1.6, 1, 0.0, 0.0, 0.0, 0.0);
      }
   }

   /** Any hit landed on him during the judgment spares that player: the aging
    *  stops, the age stacks clear, and they are told why. */
   public static void onJudgmentHit(Entity boss, ServerPlayer hitter) {
      if (boss == null || hitter == null) {
         return;
      }
      Fight fight = FIGHTS.get(boss.getUUID());
      if (fight == null || !fight.finale || fight.finaleHits.contains(hitter.getUUID())) {
         return;
      }
      fight.finaleHits.add(hitter.getUUID());
      Aging.clear(hitter);
      hitter.sendOverlayMessage(Component.literal("\u00a7d\u2726 SAVED \u00a78| \u00a7ftime releases you"));
      hitter.sendSystemMessage(Component.literal("\u00a7d\u2726 \u00a7fYour blow lands on him - \u00a7dthe years flow back into you\u00a7f."));
      if (hitter.level() instanceof ServerLevel level) {
         com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.TOTEM_OF_UNDYING, hitter.getX(), hitter.getY() + 1.0, hitter.getZ(), 30, 0.5, 0.8, 0.5, 0.2);
         level.playSound(null, hitter.getX(), hitter.getY(), hitter.getZ(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1.4F, 1.6F);
      }
   }

   /** Clears any leftover stasis tag from every entity in every level. */
   private static void clearAllStasis(MinecraftServer server) {
      for (ServerLevel level : server.getAllLevels()) {
         for (Entity e : level.getAllEntities()) {
            if (e.entityTags().contains(STASIS_TAG)) {
               e.setNoGravity(false);
               e.removeTag(STASIS_TAG);
            }
         }
      }
   }

   // ------------------------------------------------------------ death & loot

   /**
    * Intercepts the killing blow so the death can be *played*: he tries to stop
    * time one last time, and the attempt tears him apart.
    *
    * @return {@code null} when this is not our boss (let vanilla decide),
    *         otherwise {@code false} to cancel the lethal damage.
    */
   public static Boolean onLethalDamage(Entity entity, float amount) {
      if (!(entity instanceof Mob boss) || !isTimeLord(boss)) {
         return null;
      }
      if (amount < boss.getHealth()) {
         return null;
      }
      Fight fight = FIGHTS.get(boss.getUUID());
      if (fight == null) {
         // A tagged Time Lord with no fight behind him is not a protected boss, it is a
         // stuck one: cancelling here is what made a body nothing could kill - and it is
         // the opposite of the bug this was written for. Vanilla decides.
         return null;
      }
      if (fight.dying) {
         // Mid-ceremony: absorbed, and floored by the tick that plays it out.
         return Boolean.FALSE;
      }
      if (fight.finale) {
         // His final moment is a paradox he is holding: the blow that would end
         // him is absorbed, and counts as the "hit him once" that saves whoever
         // landed it.
         if (entity instanceof Mob mob && mob.getLastHurtByMob() instanceof ServerPlayer hitter) {
            fight.finaleHits.add(hitter.getUUID());
         }
         return Boolean.FALSE;
      }
      fight.dying = true;
      fight.deathTicks = DEATH_TICKS;
      ServerLevel level = (ServerLevel) boss.level();
      riftDeathCue(level, boss);
      announce(level, SAY + "\"\u00a7fN\u2014no. \u00a7dI will not\u2014 \u00a7fSTOP!\u00a7f\"");
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.BEACON_DEACTIVATE, SoundSource.HOSTILE, 1.4F, 0.5F);
      return Boolean.FALSE;
   }

   /** How long his death takes: he cracks for 40% of it, bursts, and the rift spends the rest swallowing him. */
   private static final int DEATH_TICKS = 70;
   private static final int DEATH_BURST = DEATH_TICKS - (int)(DEATH_TICKS * 0.4);

   /** Modded clients play the whole death as one effect: the rift opens behind him and takes him back. */
   private static void riftDeathCue(ServerLevel level, Mob boss) {
      double yaw = Math.toRadians(boss.getYRot());
      com.fortuneandfavors.net.FfVfx.shape(level, com.fortuneandfavors.net.FfVfx.RIFT_DEATH, ParticleTypes.REVERSE_PORTAL, boss.position().add(0.0, 1.2, 0.0),
         new Vec3(Math.cos(yaw), 0.0, Math.sin(yaw)), DEATH_TICKS, 0.0, 0xB06BFF);
   }

   private static void tickDeath(ServerLevel level, Mob boss, Fight fight, long now) {
      fight.deathTicks--;
      // The rift opens behind him and starts to take him back: he is lifted toward it.
      if (fight.deathTicks > DEATH_BURST) {
         // Cracking: lifted toward the rift, shaking harder as it builds.
         double shake = 0.08 * (1.0 - (fight.deathTicks - DEATH_BURST) / (double)(DEATH_TICKS - DEATH_BURST));
         boss.setPos(boss.getX() + (RANDOM.nextDouble() - 0.5) * shake, boss.getY() + 0.03, boss.getZ() + (RANDOM.nextDouble() - 0.5) * shake);
      } else if (fight.deathTicks == DEATH_BURST) {
         // He bursts - nothing of him is left to see; the rift spends the rest of the death taking the pieces.
         boss.setInvisible(true);
         level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 1.6F, 0.6F);
         level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.GLASS_BREAK, SoundSource.HOSTILE, 1.4F, 0.5F);
         com.fortuneandfavors.net.FfVfx.enter();
         try {
            riftBurst(level, boss.getX(), boss.getY() + 1.0, boss.getZ(), 80);
         } finally {
            com.fortuneandfavors.net.FfVfx.exit();
         }
      }
      double progress = 1.0 - Math.min(1.0, fight.deathTicks / (double)DEATH_TICKS);
      double radius = 0.4 + progress * 1.6;
      com.fortuneandfavors.net.FfVfx.enter();
      try {
         drawRift(level, boss.getX(), boss.getY() + 1.0, boss.getZ(), radius * 0.5, 12 + (int) (progress * 10.0));
      } finally {
         com.fortuneandfavors.net.FfVfx.exit();
      }
      // Exactly two lines, once each.
      if (fight.deathTicks == DEATH_TICKS - 8 || fight.deathTicks == DEATH_BURST + 6) {
         announce(level, SAY + (fight.deathTicks > DEATH_BURST + 6 ? "\"\u00a7fStop\u2014 \u00a7dstop, I command it\u2014\u00a7f\"" : "\"\u00a7fMy power\u2026 it is not\u2026 \u00a7dheld\u2026\u00a7f\""));
         level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.ENDERMAN_HURT, SoundSource.HOSTILE, 1.0F, 0.5F);
      }
      if (fight.deathTicks > 0) {
         return;
      }

      // The final collapse: the rift detonates and takes him (modded clients already have it in the cue).
      com.fortuneandfavors.net.FfVfx.enter();
      try {
         riftBurst(level, boss.getX(), boss.getY() + 1.0, boss.getZ(), 120);
      } finally {
         com.fortuneandfavors.net.FfVfx.exit();
      }
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.END_PORTAL_SPAWN, SoundSource.HOSTILE, 1.4F, 0.5F);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), ModSounds.BOSS_DEATH, SoundSource.HOSTILE, 1.4F, 0.7F);
      announce(level, "\u00a7d\u00a7lThe Time Lord \u00a7rfalters - \u00a77the moment finally takes him.");

      dropLoot(level, boss, fight);
      boss.discard();
      // The full release, not a hand-rolled half of it. The death path used to
      // remove the fight itself and leave everything else to chance - most
      // importantly the world's clock, which stayed at whatever multiple the last
      // sprint had reached. Killed mid-sprint, he took the sky with him.
      release(level.getServer(), fight);
   }

   private static void dropLoot(ServerLevel level, Mob boss, Fight fight) {
      if (fight.paid) {
         return;
      }
      fight.paid = true;
      // A boss drops what its loot box does not. His box already holds the Pocket-
      // Watch, the Chrono Shard and the Hourglass, so the echo shards, amethyst,
      // diamonds and netherite that used to fall here were the same reward paid
      // twice - in raw materials instead of in the interesting item.
      drop(level, boss, new ItemStack(Items.ECHO_SHARD, 4 + RANDOM.nextInt(4)));
      if (RANDOM.nextFloat() < 0.15F) {
         drop(level, boss, CustomEnchantments.tome("ff_aging", 1 + RANDOM.nextInt(3)));
      }
      // One rolling chance at one of his three legendaries, straight off the boss.
      if (RANDOM.nextFloat() < 0.2F) {
         drop(level, boss, switch (RANDOM.nextInt(3)) {
            case 0 -> ModItems.pocketWatch(1);
            case 1 -> ModItems.chronoShard();
            default -> ModItems.hourglassOfHaste();
         });
      }
      // Three boxes, guaranteed, straight into the inventory of everyone who
      // fought. A box on the floor goes to whoever is standing closest when the
      // fight ends, and the box is worth most to the people who did the work.
      BossPayout.payBoxes(level, fight.participants, ModItems::timeLordLootBox, BossPayout.BOXES_PER_KILL, "\u00a7dTime Lord Loot Box");
      for (UUID id : fight.participants) {
         ServerPlayer p = level.getServer().getPlayerList().getPlayer(id);
         if (p != null && p.isAlive()) {
            Advancements.grant(p, "kill_timelord");
         }
      }
   }

   /**
    * Pays a fight out exactly once, whichever exit reaches it first: the death
    * ceremony, or the tick that finds the body already gone.
    *
    * <p>The drops that land in the world need a body to land at, so a fight whose
    * body has vanished pays the half that does not need one - the line that says he
    * is gone, the loot boxes straight into the inventories of everyone who fought,
    * and the kill. Paying nothing is the one outcome that is indistinguishable from
    * a boss that died on its own.
    */
   private static void payOut(MinecraftServer server, Fight fight, String reason) {
      if (fight.paid) {
         return;
      }
      fight.paid = true;
      ServerLevel level = levelFor(server, fight);
      if (level == null) {
         return;
      }
      // No body left to stand the sound at, so this is the payout of a fight that
      // ended off-screen: the line and the loot, without pretending to a location.
      announce(level, "§d§lThe Time Lord §rfalters - §7" + reason + ".");
      BossPayout.payBoxes(level, fight.participants, ModItems::timeLordLootBox, BossPayout.BOXES_PER_KILL, "§dTime Lord Loot Box");
      for (UUID id : fight.participants) {
         ServerPlayer p = server.getPlayerList().getPlayer(id);
         if (p != null && p.isAlive()) {
            Advancements.grant(p, "kill_timelord");
         }
      }
   }

   /** The level a fight is happening in, for a payout with no body left to ask. */
   private static ServerLevel levelFor(MinecraftServer server, Fight fight) {
      ServerPlayer summoner = server.getPlayerList().getPlayer(fight.summoner);
      if (summoner != null && summoner.level() instanceof ServerLevel level) {
         return level;
      }
      for (UUID id : fight.participants) {
         ServerPlayer p = server.getPlayerList().getPlayer(id);
         if (p != null && p.level() instanceof ServerLevel level) {
            return level;
         }
      }
      return server.overworld();
   }

   private static void drop(ServerLevel level, Mob boss, ItemStack stack) {
      if (stack != null && !stack.isEmpty()) {
         level.addFreshEntity(new ItemEntity(level, boss.getX(), boss.getY() + 0.6, boss.getZ(), stack));
      }
   }

   private static void despawn(MinecraftServer server, ServerLevel level, Mob boss, Fight fight, String reason) {
      if (boss != null && boss.isAlive()) {
         riftBurst(level, boss.getX(), boss.getY() + 1.0, boss.getZ(), 60);
         level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE, 1.0F, 0.5F);
         boss.discard();
      }
      announce(level, "\u00a7dThe Time Lord \u00a7r-\u00a77 " + reason);
      release(server, fight);
   }

   private static void release(MinecraftServer server, Fight fight) {
      releasePins(server, fight);
      // The clock is put back however the fight ended. A sprint that outlives its boss
      // would leave the world permanently accelerated, and the clock is saved data - it
      // would survive a restart as exactly that.
      stopClockSprint(server, fight);
      if (fight.frozeServer) {
         unfreeze(server, fight);
      }
      if (fight.bar != null) {
         fight.bar.removeAllPlayers();
         fight.bar.setVisible(false);
      }
      FIGHTS.remove(fight.bossId);
   }

   // --------------------------------------------------- aging (shared with gear)

   /** Ages the victim by one stack; at five the stacks detonate. Called from the
    *  on-hit enchantment path. */
   public static void age(LivingEntity victim, ServerPlayer attacker) {
      if (victim == null || attacker == null || victim == attacker) {
         return;
      }
      int stacks = Aging.stacks(victim) + 1;
      boolean boss = victim.getMaxHealth() >= 100.0F;
      // Bosses cap at 4 so an infinite stack loop can never cheese them.
      int cap = boss ? 4 : 5;
      if (stacks >= 5 && !boss) {
         Aging.clear(victim);
         float burst = 9.0F;
         victim.hurtServer((ServerLevel) victim.level(), victim.damageSources().magic(), burst);
         victim.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 100, 1));
         if (victim.level() instanceof ServerLevel sl) {
            com.fortuneandfavors.net.FfVfx.particles(sl, ParticleTypes.SCULK_SOUL, victim.getX(), victim.getY() + 1.0, victim.getZ(), 24, 0.5, 0.7, 0.5, 0.06);
            sl.playSound(null, victim.getX(), victim.getY(), victim.getZ(), SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.PLAYERS, 1.0F, 0.5F);
            CombatGear.procPopupPublic(sl, victim, "\u00a75Aged to dust!");
         }
         return;
      }
      if (stacks > cap) {
         stacks = cap;
      }
      Aging.set(victim, stacks);
      // Every stack has to be visible, or the enchant reads as doing nothing at
      // all: it only *did* anything at the fifth hit, so four hits of silent
      // counting looked like four hits of nothing. The counter is the tell - it
      // is what lets a player see the clock running and back off.
      if (victim.level() instanceof ServerLevel sl) {
         String roman = switch (stacks) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            default -> String.valueOf(stacks);
         };
         CombatGear.procPopupPublic(sl, victim, "\u00a75Aging " + roman + (boss ? "" : " \u00a78(" + (5 - stacks) + " to dust)"));
         com.fortuneandfavors.net.FfVfx.particles(sl, ParticleTypes.SCULK_SOUL, victim.getX(), victim.getY() + victim.getBbHeight() * 0.8, victim.getZ(), 6, 0.3, 0.4, 0.3, 0.02);
      }
   }

   private static void applyOldAge(ServerLevel level, Mob boss, Fight fight, int stacks) {
      fight.agingStacks = Math.min(4, fight.agingStacks + stacks);
      Aging.set(boss, fight.agingStacks);
   }

   // ------------------------------------------------------------- particle art

   /** Draws the tear: a vertical seam of portal sparks with a bright core. */
   private static void drawRift(ServerLevel level, double x, double y, double z, double radius, int count) {
      double side = RANDOM.nextDouble() * Math.PI;
      com.fortuneandfavors.net.FfVfx.shape(level, com.fortuneandfavors.net.FfVfx.TEAR, ParticleTypes.REVERSE_PORTAL, new Vec3(x, y, z),
         new Vec3(Math.cos(side), 0.0, Math.sin(side)), Math.max(0.6, radius * 2.0), 18, 0xB06BFF);
      com.fortuneandfavors.net.FfVfx.enter();
      try {
         com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.REVERSE_PORTAL, x, y, z, count, radius * 0.35, radius, radius * 0.35, 0.02);
         com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.END_ROD, x, y, z, Math.max(1, count / 6), 0.06, radius, 0.06, 0.005);
         com.fortuneandfavors.net.FfVfx.particles(level, new DustParticleOptions(-4456443, 1.0F), x, y, z, Math.max(1, count / 5), radius * 0.3, radius, radius * 0.3, 0.01);
      } finally {
         com.fortuneandfavors.net.FfVfx.exit();
      }
   }

   private static void riftBurst(ServerLevel level, double x, double y, double z, int count) {
      com.fortuneandfavors.net.FfVfx.shape(level, com.fortuneandfavors.net.FfVfx.NOVA, ParticleTypes.REVERSE_PORTAL, new Vec3(x, y - 1.0, z), Vec3.ZERO, 3.0 + count / 30.0, 0.0, 0xB06BFF);
      com.fortuneandfavors.net.FfVfx.enter();
      try {
         com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.REVERSE_PORTAL, x, y, z, count, 1.2, 1.6, 1.2, 0.25);
         com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.PORTAL, x, y, z, count / 2, 1.0, 1.2, 1.0, 0.35);
         com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.END_ROD, x, y, z, count / 3, 0.8, 1.0, 0.8, 0.12);
         com.fortuneandfavors.net.FfVfx.particles(level, new DustParticleOptions(-4456443, 1.4F), x, y, z, count / 2, 1.0, 1.2, 1.0, 0.08);
      } finally {
         com.fortuneandfavors.net.FfVfx.exit();
      }
   }

   /** A slow ring of clock-hand sparks, so he always reads as a time entity. */
   private static void drawClockHands(ServerLevel level, Mob boss) {
      long t = ServerClock.clock(level);
      double x = boss.getX();
      double y = boss.getY() + 1.6;
      double z = boss.getZ();
      double angle = t * 0.08;
      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.END_ROD, x + Math.cos(angle) * 1.0, y, z + Math.sin(angle) * 1.0, 1, 0.0, 0.0, 0.0, 0.0);
      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.END_ROD, x, y, z + 0.9, 1, 0.0, 0.0, 0.0, 0.0);
      com.fortuneandfavors.net.FfVfx.particles(level, new DustParticleOptions(-4456443, 0.8F), x, y, z, 2, 0.5, 0.1, 0.5, 0.0);
   }

   // ---------------------------------------------------------- item behaviours

   /** Space-Time Rift right-click: consumes the clock and summons him. */
   public static String useSpaceTimeRift(ServerPlayer player, ItemStack held) {
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
    * Pocket-Watch right-click. Sneak-right-click while holding a Netherite Ingot
    * in the off hand upgrades Version I into Version II.
    */
   public static String usePocketWatch(ServerPlayer player, ItemStack held) {
      int version = ModItems.pocketWatchVersion(held);
      if (version <= 0) {
         version = 1;
         ModItems.setPocketWatchVersion(held, 1);
      }

      if (player.isShiftKeyDown()) {
         ItemStack off = player.getOffhandItem();
         if (!off.isEmpty() && off.is(Items.NETHERITE_INGOT)) {
            if (version >= 2) {
               return "This Pocket-Watch is already Version II.";
            }
            if (!player.getAbilities().instabuild) {
               off.shrink(1);
            }
            ModItems.setPocketWatchVersion(held, 2);
            player.level().playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.SMITHING_TABLE_USE, SoundSource.PLAYERS, 1.0F, 1.2F);
            Chat.msg(player, "&dThe Pocket-Watch is reforged - &fVersion II&d: 10 seconds, longer cooldown.");
            return null;
         }
         return "Hold a Netherite Ingot in your off hand to upgrade this to Version II.";
      }

      // The Wither owns the moment.
      //
      // A stopped world is a fair answer to a boss built out of scheduled beats, and no answer at
      // all to one whose entire kit is the wither itself: freezing time does not stop the drain.
      // So the watch is refused while an Ascended Wither is live and near - and, unlike the End's
      // refusal below, it is the boss that says so, in its own voice: your stopwatch withers. The
      // rule lives in the wither manager (see WitherReworkManager.witherRefusesTheWatch) so the
      // two halves cannot drift apart.
      if (!player.level().isClientSide()
         && com.fortuneandfavors.economy.WitherReworkManager.isEnabled()
         && com.fortuneandfavors.economy.WitherReworkManager.witherRefusesTheWatch(player)) {
         player.level().playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ITEM_BREAK, SoundSource.PLAYERS, 0.9F, 1.6F);
         return "The Pocket-Watch withers in your hand.";
      }

      // Not in the End while the dragon is up.
      //
      // The watch stops the world for the wielder, which is a fair answer to a boss made of
      // scheduled beats in a room it can walk out of - and an unfair one to a fight whose whole
      // shape is phases, a last stand and a death ceremony: ten seconds of stopped clock is ten
      // seconds of "skip the part I am losing". So the item refuses, loudly and in its own voice,
      // and it refuses <i>there</i> rather than being banned from the fight: everywhere else in the
      // mod, including every other boss, the watch still works exactly as it always has.
      if (player.level().dimension().equals(net.minecraft.world.level.Level.END)
         && com.fortuneandfavors.economy.EnderDragonManager.isFightActive()) {
         player.level().playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ITEM_BREAK, SoundSource.PLAYERS, 0.8F, 1.4F);
         Chat.msg(player, "&8The Pocket-Watch refuses to work here - &7the End is not done with you yet.");
         return "The Pocket-Watch refuses to work here.";
      }

      long now = ServerClock.clock(player.level());
      if (ModItems.cooldownSecondsLeft(held, now) > 0L) {
         return "The Pocket-Watch is still winding down (" + ModItems.cooldownSecondsLeft(held, now) + "s).";
      }

      int seconds = ModItems.pocketWatchSeconds(version);
      // The long stop costs more: 60s for Version I's 5s stop, 80s for
      // Version II's 10s stop.
      long cooldownTicks = version >= 2 ? 80L * 20L : 60L * 20L;
      ModItems.setCooldownUntil(held, now + cooldownTicks);
      player.getCooldowns().addCooldown(held, (int) Math.min(Integer.MAX_VALUE, cooldownTicks));
      freezeOthersAround(player, seconds);
      return null;
   }

   /**
    * Pocket-Watch effect: every living thing around the wielder is suspended for
    * the duration while the wielder keeps acting.
    *
    * <p>Deliberately a server tick freeze <b>plus</b> per-entity pinning. The tick
    * freeze is what holds arrows, thrown items and falling blocks in the air; the
    * pins exist because the freeze cannot touch a client-driven player, so anyone
    * caught in the bubble is also held where they stood. The wielder is exempt
    * from both, which is the whole point of the item.
    */
   private static void freezeOthersAround(ServerPlayer player, int seconds) {
      ServerLevel level = (ServerLevel) player.level();
      int ticks = seconds * 20;
      double radius = 24.0;
      List<Entity> caught = level.getEntities(player, player.getBoundingBox().inflate(radius), e -> e != player && e instanceof LivingEntity);

      // A Time Lord is not a mob, and a trinket does not stop him. Against him the
      // watch degrades to exactly what it is made of: a bubble that stills the
      // mobs around you while he walks through it. See {@link #counterWatch}.
      Fight countered = fightNear(player, radius);

      for (Entity e : caught) {
         if (!(e instanceof LivingEntity le)) {
            continue;
         }
         if (countered != null && le.getUUID().equals(countered.bossId)) {
            // Exempt, and deliberately so: this is the moment he proves it.
            continue;
         }
         if (le instanceof ServerPlayer other) {
            // Players are pinned, not potion-locked: the client keeps moving
            // them through any effect, and Jump Boost 128 used to fire them
            // above the clouds the moment the stop landed. Against a Time Lord
            // nobody is pinned at all - the world is still running.
            if (countered == null && other.isAlive()) {
               pinPlayer(other, ticks + 10, player.position());
            }
            continue;
         }
         le.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, ticks + 10, 200, false, false, false));
         le.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, ticks + 10, 4, false, false, false));
         le.setDeltaMovement(Vec3.ZERO);
         le.hurtMarked = true;
         if (le instanceof Mob mob) {
            mob.setNoAi(true);
            mob.addTag(STASIS_TAG);
         }
      }

      String owner = countered == null ? "watch:" + player.getUUID() : null;

      if (countered != null) {
         // No tick freeze, no player pins: the mobs are stilled and nothing else.
         //
         // The bubble deliberately outlasts his retort. His own stop freezes the
         // world a beat later, and if this one expired first its cleanup would
         // strip the stasis off the mobs while his stop was still holding them.
         int retortTicks = Math.min(seconds * 20 + 20, MAX_FREEZE_TICKS) + 30;
         PocketStop stop = new PocketStop(
            level, player.position(), radius, player.getUUID(), scriptTick + Math.max(ticks, retortTicks), owner, new ArrayList<>(caught), false, Math.max(ticks, retortTicks)
         );
         POCKET_STOPS.add(stop);
         counterWatch(level, player, countered, seconds);
         return;
      }

      // The *world* stops, not just the mobs. The server tick freeze is what
      // holds arrows, thrown items and falling blocks in the air, and it still
      // runs the tick handler, so this script keeps working and the wielder
      // keeps firing. The wielder is deliberately never pinned; everyone else
      // in the bubble is.
      acquireFreeze(level.getServer(), owner, ticks);
      PocketStop stop = new PocketStop(level, player.position(), radius, player.getUUID(), scriptTick + ticks, owner, new ArrayList<>(caught), true, ticks);
      POCKET_STOPS.add(stop);

      // Wall-clock backstop: if the tick handler ever stops running, the world
      // must still thaw.
      final String ownerRef = owner;
      RELEASE.schedule(() -> level.getServer().execute(() -> Safe.run("pocket watch thaw", () -> {
         boolean live = false;
         for (PocketStop s : POCKET_STOPS) {
            if (ownerRef.equals(s.owner)) {
               live = true;
               break;
            }
         }
         if (!live) {
            releaseFreeze(level.getServer(), ownerRef);
         }
      })), ticks * 50L + 800L, TimeUnit.MILLISECONDS);

      ChronoFx.stopOnset(level, player.position(), radius * 0.8);
      ChronoFx.banner(player, "\u00a7d\u00a7lTIME STOP \u00a78| \u00a77for everyone but you \u00a78- \u00a7f" + seconds + "s");
      // Everyone else in the bubble gets told what just happened to them, so the
      // stop is never mistaken for lag.
      for (Entity e : caught) {
         if (e instanceof ServerPlayer other) {
            ChronoFx.banner(other, "\u00a77\u00a7lTIME HAS STOPPED \u00a78| \u00a7f" + player.getName().getString() + "\u00a77 holds the moment");
         }
      }
      Chat.msg(player, "&dTime stops for everything but you &7(" + seconds + "s)&d.");
   }

   /** The live fight whose boss is standing inside a bubble around {@code player}. */
   private static Fight fightNear(ServerPlayer player, double radius) {
      MinecraftServer server = player.level().getServer();
      if (server == null) {
         return null;
      }
      for (Fight fight : FIGHTS.values()) {
         Entity boss = bossOf(server, fight);
         if (!(boss instanceof Mob mob) || !mob.isAlive() || mob.level() != player.level()) {
            continue;
         }
         if (mob.distanceToSqr(player) <= radius * radius) {
            return fight;
         }
      }
      return null;
   }

   private static Mob bossOf(MinecraftServer server, Fight fight) {
      if (server == null || fight == null) {
         return null;
      }
      for (ServerLevel level : server.getAllLevels()) {
         if (level.getEntity(fight.bossId) instanceof Mob mob) {
            return mob;
         }
      }
      return null;
   }

   /**
    * What the Time Lord does when someone points a Pocket-Watch at him: he laughs,
    * then holds the moment himself - properly, at no cost to him, and long enough
    * to make the point.
    *
    * <p>He is deliberately exempt from the watch's stasis. His own stop runs on
    * the script (not on his AI), so being in a stop never stops him fighting -
    * which is the whole argument he is making.
    */
   private static void counterWatch(ServerLevel level, ServerPlayer challenger, Fight fight, int seconds) {
      Mob boss = bossOf(level.getServer(), fight);
      if (boss == null || !boss.isAlive()) {
         return;
      }
      // He was never really stopped; make sure nothing left a mark on him.
      if (boss.entityTags().contains(STASIS_TAG)) {
         boss.removeTag(STASIS_TAG);
         boss.setNoAi(false);
      }

      announce(level, SAY + "\"\u00a7fHa. \u00a77You stopped \u00a7dtime\u00a77 with a \u00a7ftrinket\u00a77?\"");
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.WITCH_CELEBRATE, SoundSource.HOSTILE, 1.4F, 0.6F);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.GHAST_AMBIENT, SoundSource.HOSTILE, 0.5F, 1.6F);
      riftBurst(level, boss.getX(), boss.getY() + 1.0, boss.getZ(), 60);
      if (challenger.isAlive()) {
         challenger.sendOverlayMessage(Component.literal("\u00a7dThe Time Lord is not impressed"));
      }

      int showTicks = Math.min(seconds * 20 + 20, MAX_FREEZE_TICKS);
      final UUID bossId = fight.bossId;
      RELEASE.schedule(() -> level.getServer().execute(() -> Safe.run("time lord watch retort", () -> {
         Mob alive = bossOf(level.getServer(), fight);
         if (alive == null || !alive.isAlive() || !alive.getUUID().equals(bossId)) {
            return;
         }
         announce(level, SAY + "\"\u00a7fHere. \u00a7dLet me show you how it's done.\"");
         timeStop(level, alive, fight, showTicks);
      })), 1100L, TimeUnit.MILLISECONDS);
   }

   /**
    * Chrono Shard: rewind to where you stood ~5 seconds ago, and to the health you
    * had then. The trace is sampled every half second for every online player, so
    * the rewind is accurate without a per-item tick.
    */
   public static String useChronoShard(ServerPlayer player) {
      List<Vec3> positions = TRACE.get(player.getUUID());
      List<Float> health = TRACE_HP.get(player.getUUID());
      if (positions == null || positions.size() < 4 || health == null || health.size() != positions.size()) {
         return "The Chrono Shard has not seen enough of you yet - wait a few seconds.";
      }
      // Samples are 10 ticks apart; ~5s back is 10 samples.
      int back = Math.min(positions.size() - 1, 10);
      Vec3 dest = positions.get(positions.size() - 1 - back);
      float hp = health.get(health.size() - 1 - back);
      ServerLevel level = (ServerLevel) player.level();

      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.REVERSE_PORTAL, player.getX(), player.getY() + 1.0, player.getZ(), 60, 0.6, 1.0, 0.6, 0.15);
      // The rewind, seen: a gold rift where you were, a thread back along the path you walked, and a
      // clock snapping shut where you land.
      com.fortuneandfavors.net.FfVfx.shape(level, com.fortuneandfavors.net.FfVfx.RIFT, ParticleTypes.REVERSE_PORTAL, player.position(), Vec3.ZERO, 0.8, 0.0, 0xE2B042);
      for (int i = positions.size() - 1; i > positions.size() - 1 - back; i -= 2) {
         Vec3 a = positions.get(i);
         Vec3 b = positions.get(Math.max(positions.size() - 1 - back, i - 2));
         com.fortuneandfavors.net.FfVfx.shape(level, com.fortuneandfavors.net.FfVfx.BEAM, ParticleTypes.END_ROD, a.add(0.0, 1.0, 0.0), b.add(0.0, 1.0, 0.0), 0.0, 0.0, 0xE2B042);
      }
      player.teleportTo(dest.x, dest.y, dest.z);
      com.fortuneandfavors.net.FfVfx.shape(level, com.fortuneandfavors.net.FfVfx.CLOCK_BURST, ParticleTypes.END_ROD, dest.add(0.0, 1.0, 0.0), Vec3.ZERO, 3.0, 0.0, 0xE2B042);
      player.setDeltaMovement(Vec3.ZERO);
      player.fallDistance = 0.0F;
      player.hurtMarked = true;
      float max = player.getMaxHealth();
      player.setHealth(Math.max(1.0F, Math.min(max, hp)));
      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.REVERSE_PORTAL, dest.x, dest.y + 1.0, dest.z, 60, 0.6, 1.0, 0.6, 0.15);
      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.HEART, dest.x, dest.y + 1.6, dest.z, 6, 0.4, 0.4, 0.4, 0.0);
      level.playSound(null, dest.x, dest.y, dest.z, SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 1.0F, 1.4F);
      level.playSound(null, dest.x, dest.y, dest.z, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1.2F, 1.6F);
      return null;
   }

   /** Hourglass of Haste: 8s of Speed II / Haste II / Jump Boost I, then 3s of
    *  Slowness II as the bill comes due. */
   public static String useHourglass(ServerPlayer player) {
      player.addEffect(new MobEffectInstance(MobEffects.SPEED, 160, 1, false, true, true));
      player.addEffect(new MobEffectInstance(MobEffects.HASTE, 160, 1, false, true, true));
      player.addEffect(new MobEffectInstance(MobEffects.JUMP_BOOST, 160, 0, false, true, true));
      if (player.level() instanceof ServerLevel level) {
         com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.END_ROD, player.getX(), player.getY() + 1.0, player.getZ(), 30, 0.5, 0.8, 0.5, 0.08);
         level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1.2F, 1.4F);
      }
      final UUID id = player.getUUID();
      RELEASE.schedule(() -> {
         MinecraftServer server = player.level().getServer();
         if (server != null) {
            server.execute(() -> Safe.run("hourglass backfire", () -> {
               ServerPlayer p = server.getPlayerList().getPlayer(id);
               if (p != null && p.isAlive()) {
                  p.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 60, 1, false, true, true));
                  if (p.level() instanceof ServerLevel sl) {
                     com.fortuneandfavors.net.FfVfx.particles(sl, new DustParticleOptions(-1, 1.2F), p.getX(), p.getY() + 1.0, p.getZ(), 20, 0.5, 0.6, 0.5, 0.03);
                  }
               }
            }));
         }
      }, 160L * 50L, TimeUnit.MILLISECONDS);
      return null;
   }

   // ------------------------------------------------------------ player trace

   private static int traceTick = 0;

   /** Samples every player's position and health every 10 ticks, so the Chrono
    *  Shard has five seconds of history to rewind to. Bounded to 12 samples. */
   private static void tracePlayers(MinecraftServer server) {
      if (++traceTick % 10 != 0) {
         return;
      }
      for (ServerPlayer p : server.getPlayerList().getPlayers()) {
         UUID id = p.getUUID();
         List<Vec3> positions = TRACE.computeIfAbsent(id, k -> new ArrayList<>());
         List<Float> health = TRACE_HP.computeIfAbsent(id, k -> new ArrayList<>());
         positions.add(p.position());
         health.add(p.getHealth());
         while (positions.size() > 12) {
            positions.remove(0);
         }
         while (health.size() > 12) {
            health.remove(0);
         }
      }
      TRACE.keySet().removeIf(id -> server.getPlayerList().getPlayer(id) == null);
      TRACE_HP.keySet().removeIf(id -> server.getPlayerList().getPlayer(id) == null);
   }

   // ------------------------------------------------------------------ helpers

   private static void refreshParticipants(MinecraftServer server, ServerLevel level, Mob boss, Fight fight) {
      Set<UUID> found = new HashSet<>();
      for (ServerPlayer p : level.getPlayers(q -> q.isAlive() && q.distanceToSqr(boss) < ARENA_RADIUS * ARENA_RADIUS)) {
         found.add(p.getUUID());
      }
      if (found.isEmpty() || boss.getHealth() <= 0.0F) {
         fight.participants.clear();
         return;
      }
      fight.participants.clear();
      fight.participants.addAll(found);
      if (fight.bar != null) {
         fight.bar.setProgress(Math.max(0.0F, Math.min(1.0F, boss.getHealth() / boss.getMaxHealth())));
         for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (found.contains(p.getUUID())) {
               fight.bar.addPlayer(p);
            } else if (!fight.dying) {
               fight.bar.removePlayer(p);
            }
         }
      }
   }

   private static ServerPlayer nearestTarget(ServerLevel level, Mob boss, Fight fight) {
      ServerPlayer best = null;
      double bestDist = Double.MAX_VALUE;
      for (ServerPlayer p : level.getPlayers(q -> q.isAlive() && q.gameMode.isSurvival())) {
         double d = p.distanceToSqr(boss);
         if (d < bestDist && d < ARENA_RADIUS * ARENA_RADIUS) {
            bestDist = d;
            best = p;
         }
      }
      return best;
   }

   private static float faceYaw(Entity from, Entity to) {
      double dx = to.getX() - from.getX();
      double dz = to.getZ() - from.getZ();
      return (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
   }

   private static Entity findEntity(MinecraftServer server, UUID id) {
      for (ServerLevel level : server.getAllLevels()) {
         Entity e = level.getEntity(id);
         if (e != null) {
            return e;
         }
      }
      return null;
   }

   private static void announce(ServerLevel level, String message) {
      // His voice is the fight's rhythm, so an identical line inside a short
      // window is dropped rather than repeated - one ability rotation used to
      // read as the same line three times over. The shared gate also honours the
      // server's boss-dialogue setting on top of that.
      if (!BossChat.allowed("timelord", message)) {
         return;
      }
      Integer last = RECENT_LINES.get(message);
      if (last != null && scriptTick - last < LINE_DEDUPE_TICKS) {
         return;
      }
      RECENT_LINES.put(message, scriptTick);
      if (RECENT_LINES.size() > 96) {
         RECENT_LINES.clear();
      }
      for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
         Chat.raw(p, message);
      }
   }

   private static boolean SelfTestRunning() {
      return System.getProperty("ff.selftest") != null || "1".equals(System.getenv("FF_SELFTEST"));
   }

   /**
    * The Aging enchantment's stack ledger. Stored on the victim as an entity tag
    * count so it survives a chunk reload without touching NBT serialisation, and
    * cleared the moment it detonates.
    */
   /** Years of aging a body can carry before the clock catches up with it. */
   public static final int DUST_AT = 5;

   public static final class Aging {
      private static final Map<UUID, Integer> STACKS = new HashMap<>();
      private static final Map<UUID, Long> LAST = new HashMap<>();

      private Aging() {
      }

      /** One year comes back every this many ticks after the last one was taken. */
      public static final long RECOVER_TICKS = 60L;

      public static int stacks(LivingEntity victim) {
         Long last = LAST.get(victim.getUUID());
         int held = STACKS.getOrDefault(victim.getUUID(), 0);
         if (last == null || held <= 0) {
            return 0;
         }
         // Years come back one at a time, so the counter visibly counts down (it used to drop
         // every stack at once after six seconds, silently).
         int back = (int)((ServerClock.clock(victim.level()) - last) / RECOVER_TICKS);
         if (back >= held) {
            clear(victim);
            return 0;
         }
         return held - back;
      }

      /** Ticks until the next year comes back, or 0. */
      public static long nextBackIn(LivingEntity victim) {
         Long last = LAST.get(victim.getUUID());
         return last == null ? 0L : RECOVER_TICKS - Math.floorMod(ServerClock.clock(victim.level()) - last, RECOVER_TICKS);
      }

      public static void set(LivingEntity victim, int stacks) {
         if (stacks <= 0) {
            clear(victim);
            return;
         }
         STACKS.put(victim.getUUID(), stacks);
         LAST.put(victim.getUUID(), ServerClock.clock(victim.level()));
      }

      public static void clear(LivingEntity victim) {
         STACKS.remove(victim.getUUID());
         LAST.remove(victim.getUUID());
      }
   }
}
