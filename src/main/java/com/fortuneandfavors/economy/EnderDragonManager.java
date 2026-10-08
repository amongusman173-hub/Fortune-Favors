package com.fortuneandfavors.economy;

import com.fortuneandfavors.util.Chat;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.PowerParticleOption;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhase;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Endermite;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.end.EnderDragonFight;
import net.minecraft.world.phys.Vec3;

/**
 * The Ender Dragon, reworked: a held spawn, a rift, three phases of moves, and a last stand at
 * one heart.
 *
 * <h2>The spawn is a rite now, not a side effect of walking through a portal</h2>
 * Vanilla hands you the dragon the moment the arena loads - walking into the End <i>is</i> the
 * fight starting, whether or not you wanted to fight, and it is over before the island has
 * finished loading on your screen. Here the fight is <b>held</b>: {@code EnderDragonFight} asks
 * to create its dragon and is refused until somebody actually walks to the middle of the island.
 * Then the rift opens - five seconds of a hole in the world at 0, 64, 0 - and only when it tears
 * does the dragon come through, with an arrival show of its own.
 *
 * <p>Held means held, not prevented: {@link #shouldHoldTheDragon} refuses only while nothing has
 * walked to the middle and the fight has never been won. A rematch (four crystals on the portal)
 * is vanilla's, a dragon that already exists is left alone, and a server that restarts mid-fight
 * finds its dragon again by uuid. The one thing holding can never do is delete a dragon.
 *
 * <h2>Three phases, and each of them is a rotation rather than a stat boost</h2>
 * Phase one is the island's own kit - wing gusts, barrages, crystal resonance, shrieks. Phase two
 * adds the void: rifts that drag, summons, a breath nova and a blink. Phase three adds the sky -
 * rift storms, ender rain, a withering gaze that drinks. Every move is <b>told</b> before it is
 * thrown (a name on the action bar, a sound, and the particles that show where it will land), it
 * is thrown once, and then the rotation moves on.
 *
 * <h2>The last stand</h2>
 * A blow that would take a phase-three dragon to its floor does not land. The bar stops at one
 * heart, the dragon is untouchable, and it spends eleven seconds doing the worst thing it knows
 * how to do - and when that is over it <b>lands</b>, resumes its phase-one rotation, and is
 * killable with the next hit. One hit. That is the shape the fight was asked for.
 *
 * <h2>And the death is a ceremony</h2>
 * The killing blow is swallowed, the dragon is pinned at one heart, and five seconds of light
 * come out of it - and then the real blow lands, so everything that has ever hung off a dragon
 * death still happens: the vanilla death animation, the experience, the portal, the egg, the
 * fight's own bookkeeping. This class adds the shot, never replaces it.
 */
public final class EnderDragonManager {
   private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("fortuneandfavors-dragon");
   private static final Random RANDOM = new Random();
   /**
    * The dragon's own breath particle, as a value rather than a bare type.
    *
    * <p>{@code DRAGON_BREATH} is not a plain particle: it carries a power, so the bare registry
    * entry cannot be handed to anything that takes a {@link ParticleOptions} - which is why every
    * breath visual in this file reads from one wrapped constant instead of the raw type.
    */
   private static final ParticleOptions BREATH = PowerParticleOption.create(ParticleTypes.DRAGON_BREATH, 1.0F);

   // ------------------------------------------------------------------ constants

   /** How close to the island's centre a player has to be for the rite to start. */
   public static final double RITE_RADIUS = 48.0;
   /** The rift is open this long before the dragon comes through. */
   public static final int RITE_OPEN_TICKS = 100;
   /** The arrival show, after the dragon actually exists. */
   private static final int RITE_CEREMONY_TICKS = 80;
   /**
    * Phase two starts once the dragon has lost its first third, and phase three once it has lost
    * its second.
    *
    * <p>Three equal thirds of the bar, one per phase: the health is split evenly rather than the
    * old 60/25 ladder, so each form is worth exactly the same amount of fighting and "a third of
    * the bar" is the same sentence to the room as "one phase". {@link #DRAGON_HEALTH} is chosen so
    * a third is a whole-ish number of hearts as well.
    */
   public static final float PHASE_TWO_AT = 2.0F / 3.0F;
   /** And the last third is phase three. */
   public static final float PHASE_THREE_AT = 1.0F / 3.0F;
   /** One third of the dragon's bar, the health one phase is worth. */
   public static final float PHASE_HEALTH = 350.0F / 3.0F;
   /** Ticks between moves while the dragon is free in phase one. */
   private static final int MOVE_GAP_P1 = 90;
   private static final int MOVE_GAP_P2 = 70;
   private static final int MOVE_GAP_P3 = 55;
   /**
    * Ticks between a move being chosen and the move landing: none.
    *
    * <p>This dragon does not wind up. It used to - a name on the action bar and a growing ring of
    * particles for most of a second before every attack - and the fight read as a lecture with
    * damage in it: the warning was doing the work the fight should have done, and by the time
    * anything happened the room had already answered a question it had not been asked yet. Now a
    * move lands on the tick it is chosen, and the counterplay is the fight itself: the moves are
    * shaped (a line, a ring, a radius, a target) and <i>that</i> shape is the tell.
    *
    * <p>Zero rather than a missing field, because it is the number a check reads: "no move is
    * thrown with a wind-up" is one comparison, and it is the difference between this design and
    * the one it replaced.
    */
   public static final int WIND_UP_TICKS = 0;
   /**
    * The most particles any one tick of this fight is allowed to ask for.
    *
    * <p>The rework's whole point is that the fight is loud, and "loud" with no ceiling is how a
    * server drops frames in the one fight that everybody on the server is watching. Every particle
    * send in this file goes through {@link #spend}, which clamps against this number, so the
    * ceiling is real rather than aspirational - and {@link #peakParticlesForTest()} lets a check
    * read what a real fight actually spent instead of trusting a review of every call site.
    */
   public static final int PARTICLE_CEILING = 900;
   /**
    * The share of a request a vanilla client is actually sent. The fight was written loud, and at
    * full density the shapes blur into each other; thinned, each one reads. Modded clients ignore
    * this - they draw their own versions (net.FfVfx).
    */
   private static final double VANILLA_DENSITY = 0.45;
   /** How many protectors a call may leave standing at once. */
   public static final int PROTECTOR_MAX = 4;
   /** How long the aura of the End drains the room, in ticks. */
   public static final int AURA_FIELD_TICKS = 160;
   /**
    * How close the dragon's own body reaches, and how hard, per rotation.
    *
    * <p>The passive half of the aura, and the reason a fight that is entirely ranged is not the
    * whole answer: the End comes off the body, so standing inside this radius costs health on a
    * clock whether or not a move is running. It is the one piece of pressure that is always on,
    * which is what makes the space around the dragon a place rather than an inconvenience.
    */
   public static final double AURA_REACH = 6.0;
   private static final int AURA_INTERVAL = 20;
   private static final float[] AURA_BITE = {3.0F, 4.0F, 6.0F};
   /**
    * The crystals: the fight's turrets, and the dragon's own supply line.
    *
    * <p>They used to be scenery that healed the dragon only when a phase-one move pointed at them.
    * Now every crystal standing is a gun that fires on a staggered clock and a trickle of health
    * going back into the body - so the room's job (shoot them) is the same job it always was, and
    * it is finally worth doing in every phase rather than one.
    */
   public static final int CRYSTAL_FIRE_TICKS = 90;
   private static final float CRYSTAL_SHOT_DAMAGE = 5.0F;
   private static final double CRYSTAL_REACH = 60.0;
   private static final int CRYSTAL_HEAL_INTERVAL = 40;
   public static final float CRYSTAL_HEAL_AMOUNT = 0.5F;
   /**
    * How much harder every one of the dragon's own moves hits than it used to.
    *
    * <p>The report was half a heart through full Protection IV: the moves were shaped well and
    * cost almost nothing, so a fight made of reading a shape was a fight you could stand in. The
    * fix is one multiplier rather than forty new numbers - every wound this class deals to a
    * player goes through {@link #wound}, which is where this is applied, and the ring damage in
    * {@link #hurtNear} is scaled by the same figure. One number, one place, and a fight that
    * hurts through the armour the room is actually wearing.
    */
   public static final float MOVE_DAMAGE_SCALE = 1.8F;
   /**
    * How long the dragon may stay out of melee reach before the fight brings it back down.
    *
    * <p>The guarantee, as opposed to the rhythm: {@link #PERCH_INTERVAL_P23} is the descent's own
    * clock, and a clock can be missed - a long lasting move holds the rotation, the ascension holds
    * it again, and a room can spend a minute and a half in the air without a single window. This is
    * the ceiling on that: past this many ticks out of reach the dragon is put on the fountain
    * whatever else is going on, long enough that nobody ever has to ask "is melee even possible in
    * this fight".
    */
   public static final int AIRBORNE_LIMIT_TICKS = 45 * 20;
   /**
    * The dragon's health, raised from vanilla's two hundred.
    *
    * <p>Three hundred and fifty because the kit grew, the fight got three phases, and the phases
    * are now <b>equal thirds of this bar</b> (see {@link #PHASE_TWO_AT}): the rotations are deep, the
    * crystals shoot and feed, and a bar that empties in one committed push cannot carry a fight that
    * is meant to be three fights. It is one number, applied as an attribute when a dragon is adopted,
    * so the vanilla boss bar, the phase arithmetic and the arrival's count-up all read the same
    * maximum.
    */
   public static final float DRAGON_HEALTH = 350.0F;
   /**
    * The crystal roster's ladder: what may be standing, by how far the fight has got.
    *
    * <p>Borrowed wholesale in spirit from Dragonkind Evolved, where a crystal's type is gated on the
    * dragon's own difficulty - fledgeling, powerful, cruel, ferocious, ominous, and the ten-thousand
    * fight at the top. This fight's ladder is its phases: the island's own kit at one, the void at
    * two, the sky at three, and the last stand is this fight's version of the hardest tier there is
    * - which is where a crystal that makes the others untouchable belongs.
    */
   public static final int CRYSTAL_TIER_MAX = 4;
   /** Laser: it charges on a visible clock, then fires along a band that jumping clears. */
   private static final int LASER_CHARGE_TICKS = 60;
   private static final int LASER_FIRE_TICKS = 80;
   private static final int LASER_CHARGE_LIFE = 200;
   private static final float LASER_DAMAGE = 13.0F;
   private static final double LASER_BAND = 1.6;
   /** Fiery: it burns whatever stands within a few blocks of it. */
   private static final double FIERY_REACH = 3.5;
   private static final int FIERY_PERIOD = 110;
   /** Witch: a random effect on a random player who has none. */
   private static final int WITCH_PERIOD = 340;
   /**
    * Anti-grav: the pillar it stands over takes the room's weight away.
    *
    * <p>The one crystal effect that could pick a player up and throw them off the island, which is
    * why it is both rarer and gentler than it was: levitation <b>one</b> for a beat and a half is a
    * lift, and levitation two for three seconds was a launch. A turret that removes the floor from
    * under somebody is already the strongest thing on the ring; it does not also need altitude.
    */
   private static final double ANTI_GRAV_REACH = 9.0;
   private static final int ANTI_GRAV_PERIOD = 110;
   private static final int ANTI_GRAV_LIFT_TICKS = 30;
   /** Portal: players are moved to the top of a random tower. */
   private static final int PORTAL_PERIOD = 400;
   private static final double END_TOWER_RADIUS = 43.0;
   private static final int PORTAL_MAX_MOVED = 3;
   /** Launcher: it charges, then hunts anything in the air until it discharges. */
   private static final int LAUNCHER_CHARGE_TICKS = 160;
   private static final int LAUNCHER_LIFE_TICKS = 360;
   private static final int LAUNCHER_FIRE_TICKS = 70;
   private static final double LAUNCHER_REACH = 32.0;
   private static final float LAUNCHER_DAMAGE = 11.0F;
   /** Cursed: no particles at all, and destroying one is the most expensive thing in the room. */
   private static final float CURSED_BLAST = 15.0F;
   private static final double CURSED_BLAST_REACH = 60.0;
   /**
    * Caged: the cage eats every other blow.
    *
    * <p>Half of everything, expressed as a rule a check can read rather than as a number no event
    * can apply - vanilla's damage event answers yes or no, so "tougher" has to be said as "refused".
    */
   public static final int CAGED_BLOWS_PER_OPENING = 2;
   /** The slam: how long the body is on its way down, and what the landing costs. */
   private static final int SLAM_TICKS = 40;
   private static final float SLAM_DAMAGE = 26.0F;
   private static final double SLAM_RADIUS = 14.0;
   /**
    * How long the dragon stays pinned on the floor after a slam lands.
    *
    * <p>The slam was the one move that put the body inside reach and then left immediately: it hit,
    * bounced and was in the air again before anybody could swing. The landing is the window, so the
    * landing is what lasts - three and a half seconds of a dragon on the ground, no rotation, no
    * new move, and a melee opening that is actually worth walking into.
    */
   public static final int SLAM_DOWN_TICKS = 70;
   /** Weightless, then dropped: two beats, and the second one is the damage. */
   private static final int ZERO_G_LIFT_TICKS = 50;
   private static final int ZERO_G_DROP_TICKS = 16;
   private static final float ZERO_G_DAMAGE = 22.0F;
   /** A ring of bullets, and the spikes the move falls back to when the air is already full. */
   private static final int SHULKER_BULLETS = 12;
   private static final int SPIKE_ROWS = 6;
   private static final float SPIKE_DAMAGE = 9.0F;
   /** The charge: how long its corridor is, how wide, and what standing in it costs. */
   private static final double CHARGE_LENGTH = 26.0;
   private static final double CHARGE_WIDTH = 4.0;
   private static final float CHARGE_DAMAGE = 14.0F;
   /** The shower, and the supernova's two radii: the safe eye, and the edge of the band. */
   private static final int METEOR_COUNT = 10;
   private static final double SUPERNOVA_EYE = 8.0;
   private static final double SUPERNOVA_BAND = 32.0;
   /**
    * From phase two on, how often the dragon comes down to the fountain on its own.
    *
    * <p>Melee is the fight's main line of damage and it was the one line the rework had closed:
    * the body spent the last two phases in the air, so a sword was decoration. The descent is now
    * part of the rotation's own rhythm rather than only a phase-one accident - the dragon lands,
    * fights from the fountain for a few beats, and leaves, on a clock the room can learn.
    */
   public static final int PERCH_INTERVAL_P23 = 40 * 20;
   /** How often a phase-two or phase-three fight tops its guards back up to strength. */
   private static final int PROTECTOR_TOPUP_TICKS = 500;
   /** The theme's length, read off the file: 225.47s, rounded down so a re-send is never early. */
   public static final int ENDER_THEME_TICKS = 4490;
   /**
    * The End's own advancement, taken whole rather than as a path under this mod.
    *
    * <p>{@code minecraft:end/kill_dragon} - "Free the End" - is overridden in the datapack
    * ({@code data/minecraft/advancement/end/kill_dragon.json}) to be a <b>challenge</b> rather
    * than a task, so the fight ends on a grand achievement. Granting it by hand is the other half
    * of that: vanilla hands it to whoever the killing blow named, and a group that fought the
    * dragon together deserves it as a group.
    */
   private static final Identifier FREE_THE_END = Identifier.withDefaultNamespace("end/kill_dragon");
   /**
    * The root of the End's advancement branch, which Free the End hangs off.
    *
    * <p>An advancement whose parent is not complete is <b>not visible</b>: the achievements screen
    * hides the whole branch and the challenge shows no toast, so a grant that only writes the child
    * is, from the player's seat, a grant that did nothing. Vanilla's ordinary kill path completes
    * the root on the way in (entering the End fires it); a hand-grant has to complete it too, which
    * is what makes "I killed it and got nothing" stop happening even for a player who was carried
    * into the dimension by somebody else's portal.
    */
   private static final Identifier END_ROOT = Identifier.withDefaultNamespace("end/root");
   /**
    * What every player in the End walks out of the fight with, unconditionally.
    *
    * <p>One Heart of the End and this many Dragon Scales, each, straight into the inventory. The
    * Heart is the thing every recipe for the End's three weapons needs, so "guaranteed" has to
    * mean guaranteed: this is handed out by {@link #grantEndLoot} off the dragon's death, before
    * any of the ceremony's cleanup can fail, and it is one per player rather than one for the
    * group - a helper who spent the fight on the crystals is in the same fight as the last hit.
    */
   public static final int DRAGON_SCALE_DROP = 5;
   /**
    * The players who have already been given the End's loot for the fight in progress.
    *
    * <p>The grant has two callers (the death ceremony and the server's own death event), so it
    * must be idempotent: without this, both firing on one death would hand out two hearts. Keyed
    * on the player rather than on a counter, so a player who arrives at the corpse a moment late
    * - after the first caller ran - still gets theirs when the second caller looks.
    */
   private static final java.util.Set<UUID> END_LOOT_GIVEN = new java.util.HashSet<>();
   /**
    * The players who have landed a blow on the dragon during the fight in progress.
    *
    * <p>"Every player who killed the dragon" is a thing players <b>did</b>, not a place they were
    * standing, so it is written down as they do it. The grant reads this ledger rather than the
    * room; an empty ledger (a scripted kill, a `/kill`, a body lost to the void) falls back to
    * paying everybody present, so the fight can never end on a grant that paid nobody.
    */
   private static final java.util.Set<UUID> DRAGON_ATTACKERS = new java.util.HashSet<>();
   /** The last stand. */
   public static final int FINALE_TICKS = 220;
   /** The floor the dragon is pinned at for the last stand, and for its death. */
   public static final float LAST_STAND_HEALTH = 1.0F;
   /** The death ceremony before the vanilla death animation is allowed to run. */
   public static final int DEATH_STARTUP_TICKS = 110;
   /** How long the extra VFX keep going after the vanilla death animation starts. */
   private static final int DEATH_AFTERGLOW_TICKS = 120;
   /** How long the end gateway's own opening effect runs, where the dragon fell. */
   private static final int GATEWAY_TICKS = 80;
   /** Bolts in flight at once. A cap, so a bad random streak cannot flood a server. */
   private static final int MAX_BOLTS = 10;
   /**
    * The arrival: the bar counts up from nothing while the dragon comes through the rift.
    *
    * <p>Three seconds, and it is a <b>health</b> animation rather than a scale trick, because the
    * boss bar is drawn from the entity's own health - so a dragon that arrives at zero and fills
    * in is the vanilla bar filling in, with nothing new for the client to know about. Nothing may
    * touch it while it runs (a blow is refused rather than absorbed), because a bar being filled
    * by a script and lowered by a sword at the same time is a bar that reads as a bug.
    */
   public static final int RISE_TICKS = 60;
   /** The floor the bar starts on: zero health is a corpse, so the count starts just above it. */
   private static final float RISE_FLOOR = 0.5F;
   /**
    * The rematch ceremony: how long the island spends bringing the dragon back.
    *
    * <p>Three beats in one clock - the island reels, the crystals are relit one by one, and the
    * body climbs out of the portal. It is a separate clock from the arrival's count-up because it
    * is a different event: the arrival is a bar filling on a dragon that is already here, and this
    * is the island putting one back. Vanilla spawns the rematch dragon in the sky at full health
    * with no announcement at all, which is the thing this answers.
    */
   public static final int RESPAWN_TICKS = 160;
   /** The first beat of the ceremony, where nothing is lit yet and the island is moving. */
   public static final int RESPAWN_REEL_TICKS = 60;
   /** Where the body starts and finishes its climb, as an altitude above the exit portal. */
   public static final double RESPAWN_RISE_FROM = 64.0;
   public static final double RESPAWN_RISE_TO = 100.0;
   /**
    * Where the End stops being a place and becomes the void.
    *
    * <p>Well under the island's own underside, so nothing that is merely standing on a low bit of
    * rock is caught by it - only an actual fall off the world. Vanilla's own out-of-world kill sits
    * at the level's minimum height minus sixty-four, so this fires long before it and the void is a
    * rescue rather than a death sentence.
    */
   private static final double VOID_RESCUE_Y = 8.0;
   /**
    * What the tear leaves you with.
    *
    * <p>Four fifths of the bar: enough that falling in during a boss fight is a cost and not a
    * lost run, which is the whole point of the mechanic. Read by the self-test as one number.
    */
   public static final float VOID_RESCUE_HEALTH = 0.8F;
   /** One fall in this many is caught gently, and lands with slow falling. */
   public static final int VOID_RESCUE_GENTLE_ONE_IN = 10;
   /**
    * How much more of the bar the void takes on every fall after the first.
    *
    * <p>The rescue is a cost rather than a reset, and a cost that never grows is a cost a player
    * can pay forever: dive in, come out at four fifths, dive in again. The bill therefore climbs
    * with the count - four fifths, then three, then two, then one - so the third fall in a row is
    * a decision rather than a shortcut. The floor is one heart: the void wounds, it never kills.
    */
   public static final float VOID_RESCUE_STEP = 0.2F;
   /** How many falls each body is carrying, keyed per player so an alt is nobody's credit. */
   private static final java.util.Map<UUID, Integer> VOID_FALLS = new java.util.concurrent.ConcurrentHashMap<>();
   /** When each body last fell, read for the forgiveness below. */
   private static final java.util.Map<UUID, Long> VOID_FALL_TICK = new java.util.concurrent.ConcurrentHashMap<>();
   /** How long above the island it takes for the run of falls to be forgotten. */
   public static final int VOID_FALLS_FORGIVEN_TICKS = 2400;

   /** The breath lance: how long the beam runs, how far it reaches, and how far it sweeps. */
   public static final int BEAM_TICKS = 80;
   private static final double BEAM_RANGE = 46.0;
   private static final double BEAM_SWEEP_DEGREES = 34.0;
   private static final double BEAM_HIT_RADIUS = 2.1;
   private static final float BEAM_DAMAGE_PER_TICK = 0.7F;
   /** Ticks between the pools the beam leaves where it touches, and how many may stand at once. */
   private static final int BEAM_POOL_TICKS = 9;
   private static final int BEAM_POOLS_MAX = 10;
   /**
    * What the beam leaves behind: vanilla's own dragon-fireball cloud.
    *
    * <p>Radius, duration and the harm effect are the values vanilla uses, because this is
    * <i>the</i> dragon's breath puddle and players already know how it behaves - it is the pool
    * a dragon fireball makes. It is capped and swept so a long beam cannot leave thirty of them
    * ticking in one chunk.
    */
   private static final int BEAM_POOL_DURATION = 300;
   private static final float BEAM_POOL_RADIUS = 2.5F;

   // ------------------------------------------------------------------ state

   /** True once the rift has opened: the hold is off for the rest of the server's life. */
   private static boolean riteOpened = false;
   /** Counts down while the rift is tearing open. */
   private static int riteTicks = 0;
   private static int ceremonyTicks = 0;
   private static EnderDragon dragon;
   /**
    * The uuid of the body the current fight belongs to. Adoption is keyed on this rather than on the
    * {@link #dragon} field's own identity, because that field can be cleared while the fight goes
    * on (a cache miss in {@link #findDragon}, a reload, a check asking what is out there) and the
    * same live body must not then be handed the fresh-fight reset a second time - that reset wipes
    * the ledger of who has been paid, re-registers the boss layer and rewrites the bar, once per
    * second of a fight that never changed.
    */
   private static UUID lastFightUuid;
   /**
    * How many times a body has been adopted as a brand-new fight, and how many times one has been
    * re-attached to the fight it already belonged to. Two counters rather than one because the
    * difference between them <i>is</i> the bug this pins: a live dragon re-adopted as a fresh fight
    * loses the ledger of who has been paid and re-registers its boss layer, once per re-adoption.
    */
   private static int freshAdoptions;
   private static int reattachments;
   private static long nextDragonScan = 0L;
   private static int phase = 1;
   /** The current move. Named on the action bar as it lands, never before it. */
   private static Move move = null;
   private static int moveCooldown = MOVE_GAP_P1;
   private static Move lastMove = null;
   /**
    * True while the dragon is being held on the floor by its own landing.
    *
    * <p>A second half to the slam and to the stomp: the body has hit the ground and is staying
    * there, which is the fight's only guaranteed melee window. Read by {@link #tickSlam} on the way
    * in, so the one state decides both what the body does and what the rotation may not do.
    */
   private static boolean slamGrounded = false;
   /**
    * Where a landing pinned the dragon - the melee window's own coordinates.
    *
    * <p>Held rather than re-derived, because vanilla's landing AI drives the body at the fountain
    * the whole time it is down: without a recorded spot, "the dragon stays where it landed" would
    * be a sentence about a body that is already on its way back to the middle of the island.
    */
   private static Vec3 slamHoldSpot = Vec3.ZERO;
   /** When the dragon last came down to the fountain by itself, for the phase-two-and-three cadence. */
   private static long lastPerchAt = 0L;
   /** A clock for the guards being topped back up mid-phase. */
   private static int protectorClock = 0;
   /** A clock for the "it shrugs your arrow off" line, so being deflected is told once, not per arrow. */
   private static int deflectClock = 0;
   /**
    * Ticks the fight has spent with the body out of melee reach.
    *
    * <p>Reset the moment the dragon is on the floor or perching, which makes it a counter of the
    * one thing the melee design cannot tolerate: a fight that never comes down. Read by the
    * watchdog in {@link #fightTick} and by the self-test.
    */
   private static int airborneTicks = 0;
   /** > 0 while the last-stand finale is running. */
   private static int finaleTicks = 0;
   /** True once the finale has been survived: one heart, killable, phase-one rotation. */
   private static boolean lastStand = false;
   /** > 0 while the death ceremony is running; the dragon is untouchable until it ends. */
   private static int deathTicks = 0;
   /** > 0 after the real death, for the afterglow. */
   private static int afterglowTicks = 0;
   /** > 0 while the gateway is opening on the spot the dragon died. */
   private static int gatewayTicks = 0;
   private static Vec3 deathSpot = Vec3.ZERO;
   /** The boss bar's own displayed fraction, drained toward the dragon's real health (see dressBar). */
   private static float barDisplayed = 1.0F;
   /**
    * The clock behind the standing action-bar line.
    *
    * <p>The health readout used to be sent only on the branch that waits between moves - so the
    * moment the dragon threw a lasting attack (a beam, a rift, a gaze) the line that was on the
    * screen belonged to the attack, faded, and left the bar blank until the next move was chosen.
    * The numbers of a boss fight are not an event, they are <b>standing information</b>: the line
    * is now sent on its own clock, from the top of the fight tick, so it is on screen between
    * moves, through every lasting move, and everywhere in between.
    */
   private static int bossLineClock = 0;
   /** How often the standing line is refreshed. Three times a second reads as "always there". */
   public static final int BOSS_LINE_INTERVAL = 6;
   /** Bolts in the air: scripted projectiles, because they are the only ones we can draw. */
   private static final List<Bolt> BOLTS = new ArrayList<>();
   /** > 0 while the breath lance is running. The beam is a move that lasts, not one that lands. */
   private static int beamTicks = 0;
   /** Which way the sweep starts: read once when the beam begins. */
   private static int beamSweepSign = 1;
   private static int beamPoolClock = 0;
   /** The pools the beam has left standing, so they can be capped and swept up. */
   private static final List<AreaEffectCloud> POOLS = new ArrayList<>();
   /** > 0 while the arrival's health count-up is running. */
   private static int riseTicks = 0;
   /**
    * The rifts that are still open - see {@link #tickEndRifts}.
    *
    * <p>A list with a hard cap rather than one rift at a time, because the whole point of the
    * mechanic is that several stand at once and the arena becomes a floor with holes in it. The
    * cap is the difference between a mechanic and an accident: without it, a long fight in a
    * phase-three rotation would eventually cover the island.
    */
   private static final List<EndRift> END_RIFTS = new ArrayList<>();
   /** > 0 while the gaze is on somebody. A lasting move, ticked like the beam. */
   private static EnderGaze gaze = null;
   /** The bodies carrying a Void Mark and the ticks left on each. */
   private static final java.util.Map<UUID, VoidMark> VOID_MARKS = new java.util.HashMap<>();
   /** The cracks, while they are open. */
   private static Rupture rupture = null;
   /** The section of the island that has temporarily stopped existing. */
   private static Tear tear = null;
   /** The nova's one charge, while it is winding up. */
   private static int novaChargeTicks = 0;
   /**
    * Where the nova is standing.
    *
    * <p>Captured on the ground rather than read off the dragon. A warning ring drawn at the dragon's
    * altitude is a ring drawn in the sky: the room fights on the floor, so the nova has to be a
    * <i>place on the floor</i> - the circle closes on the island, and stepping out of it is a thing
    * a player can actually do.
    */
   private static Vec3 novaAt = null;
   /**
    * True once the once-a-fight nova has been spent.
    *
    * <p>Per fight, reset when a body is adopted, because the ask was explicit: this is a move the
    * dragon gets to spend once. A rotation that could roll it twice is not a major attack, it is a
    * coin flip that decides the fight.
    */
   private static boolean novaSpent = false;
   /** > 0 while the phase-one perch sequence is running. */
   private static int perchTicks = 0;
   private static int perchBeat = 0;
   /**
    * Particles this tick, and the worst tick this server has seen.
    *
    * <p>A budget rather than a running total: the fight is allowed to be enormous, but a single
    * tick of it is not allowed to be unbounded. See {@link #spend}.
    */
   private static int spentParticles = 0;
   private static int peakParticles = 0;
   /**
    * The arriving dragon's name, assembling itself one character at a time.
    *
    * <p>The count-up became a title that spells the fight's name out letter by letter, because
    * the ask was for a spawn worth watching: a single line that appears and disappears is a
    * loading screen, and a name that is being written is an announcement. Zero means nothing is
    * being written.
    */
   private static int nameTicks = 0;
   /**
    * The aura of the End: while this is above zero the dragon's own body is a hazard.
    *
    * <p>A move that lasts rather than a move that lands, like the breath lance - the difference is
    * that this one is a place rather than a line, and the answer is leaving it rather than getting
    * off it.
    */
   private static int auraFieldTicks = 0;
   /**
    * The Lost Dragon Protectors: phantoms raised by the fight, by uuid so nothing is held.
    *
    * <p>They are the island's own dead, and the one rule they live under is the one that keeps
    * them from becoming the fight's bug: <b>they only ever hunt players.</b> A protector that
    * could target the dragon would be a boss that kills itself, so the rule is enforced on the
    * way in and re-asked every tick - see {@link #isAnAllowedProtectorTarget}.
    */
   private static final List<UUID> PROTECTORS = new ArrayList<>();
   /**
    * The End Island Protectors: charged creepers raised on the ground, by uuid, for the same
    * reason the phantoms are - nothing is held, and a fight that ends leaves nothing behind.
    */
   private static final List<UUID> CREEPER_PROTECTORS = new ArrayList<>();
   /** How many ground protectors the island may leave standing at once. */
   public static final int CREEPER_PROTECTOR_MAX = 3;
   /**
    * What kind of crystal each one is, and the clocks the variants run on.
    *
    * <p>Keyed by uuid rather than by entity: a crystal is an entity that can be unloaded and
    * reloaded, and a roster that forgot what it rolled would re-roll every time a chunk came back -
    * which would read as crystals changing their minds mid-fight.
    */
   private static final java.util.Map<UUID, CrystalKind> CRYSTAL_KINDS = new java.util.HashMap<>();
   /** When each variant's own clock started, for the ones with a charge and a discharge. */
   private static final java.util.Map<UUID, Long> CRYSTAL_CLOCKS = new java.util.HashMap<>();
   /** How many blows each caged crystal has been offered, so the cage can eat every other one. */
   private static final java.util.Map<UUID, Integer> CRYSTAL_BLOWS = new java.util.HashMap<>();
   /**
    * Where each crystal was the last time the fight saw it.
    *
    * <p>Only the cursed type needs this, and it needs it urgently: its whole effect happens at the
    * moment it stops existing, when there is no entity left to ask for a position.
    */
   private static final java.util.Map<UUID, Vec3> CRYSTAL_SPOTS = new java.util.HashMap<>();
   /**
    * What a witch crystal may hand out - neutral and negative only, as the pack it is borrowed from
    * has it: nothing that would help, and nothing that would kill.
    */
   private static final net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect>[] WITCH_EFFECTS = new net.minecraft.core.Holder[] {
      net.minecraft.world.effect.MobEffects.SLOWNESS,
      net.minecraft.world.effect.MobEffects.WEAKNESS,
      net.minecraft.world.effect.MobEffects.HUNGER,
      net.minecraft.world.effect.MobEffects.MINING_FATIGUE,
      net.minecraft.world.effect.MobEffects.NAUSEA,
      net.minecraft.world.effect.MobEffects.BLINDNESS,
      net.minecraft.world.effect.MobEffects.DARKNESS,
      net.minecraft.world.effect.MobEffects.UNLUCK
   };
   /**
    * The sky ring: the circle of lightning, how many bands close it in, and how far apart.
    *
    * <p>Three bands of sixteen strikes, the outermost fifteen blocks out and each band five blocks
    * closer, so the last one stands five blocks off the point the whole ring is centred on. That is
    * the move: the rim is fatal and the middle is not, and the bands say which is which by walking
    * in. Sixteen bolts is also the ceiling a single tick can carry without the particle budget
    * being spent on one attack.
    */
   private static final int SKY_RING_WAVES = 3;
   private static final int SKY_RING_GAP = 14;
   private static final double SKY_RING_SPAN = 15.0;
   private static final double SKY_RING_STEP = 5.0;
   private static final int SKY_RING_BOLTS = 16;
   private static final float SKY_RING_DAMAGE = 7.0F;
   /**
    * The ascendant blink: a chain whose gaps collapse, and a slam where it stops.
    *
    * <p>Eight teleports, the first eleven ticks apart and each one a tick closer than the last, so
    * the chain accelerates into the landing. The last blink is not an arrival - it is the slam's
    * opening, which is what makes the move answerable: a chain nobody can hit is fine to run from
    * as long as it is tellable when it ends.
    */
   private static final int BLINK_STEPS = 8;
   private static final int BLINK_FIRST_GAP = 11;
   private static final int BLINK_LAST_GAP = 4;
   private static final float BLINK_LANDING_DAMAGE = 5.0F;
   /**
    * The ascension: the beat between two phases, and the colonnade that marks it.
    *
    * <p>Three seconds during which the dragon is out of the room's reach and the island grows
    * eighteen columns of light around it, one more every third tick. It only ever starts at a
    * border and it is on a clock, so the window is a breath between two fights rather than a way
    * to outlast the room.
    */
   private static final int ASCENSION_TICKS = 60;
   private static final int ASCENSION_PILLARS = 18;
   private static final double ASCENSION_RADIUS = 24.0;
   private static final double ASCENSION_HEIGHT = 14.0;
   /** > 0 while the dragon is driving its own body into the ground. */
   private static int slamTicks = 0;
   /** > 0 while the room is weightless, and then while it is being dropped. */
   private static int zeroGTicks = 0;
   /** > 0 while the sky is closing in around the middle of the island, and which band is next. */
   private static int skyRingTicks = 0;
   private static int skyRingWave = 0;
   /** Teleports left in the blink chain, and the ticks until the next one. */
   private static int blinkTicks = 0;
   private static int blinkGap = 0;
   /** > 0 while the dragon is between forms and out of reach. */
   private static int ascensionTicks = 0;

   /**
    * The fight's theme: how long until the track has to be handed out again, and who has it.
    *
    * <p>A "sound" of this length cannot be one packet: the client plays it once and stops. So the
    * theme is a clock - everybody in the End is served, the clock runs the track's own length, and
    * then everybody is served again, until the dragon dies. Who has been served is kept per player
    * so a player who arrives mid-fight (or who has just reconnected) hears the music from the next
    * loop rather than from nothing.
    */
   private static int themeTicks = 0;
   private static boolean themePlaying = false;
   private static final List<UUID> THEME_SERVED = new ArrayList<>();
   /** Ticks until the now-playing toast is taken back out of the advancement screen. */
   private static int toastTicks = 0;
   /** True once a dragon has had its arrival - so a restart does not re-run it on a live fight. */
   private static boolean spawnShowDone = false;
   /**
    * True between the rift tearing and the arrival finding its dragon.
    *
    * <p>This is the piece that says <b>whose</b> arrival it is. The dragon that comes through our
    * rift gets the count-up; a body adopted from a fight that was already running (a restart, a
    * reload, somebody else's respawn) does not, because zeroing a bar that is sixty percent full
    * to replay an arrival is the single worst thing this class could do to a live fight.
    */
   private static boolean awaitingArrival = false;
   /** Ticks left in the rematch ceremony, or 0 when no dragon is being brought back. */
   private static int respawnTicks = 0;
   /** How many crystals have been relit so far this ceremony, so each one is lit exactly once. */
   private static int respawnLit = 0;
   /**
    * True between one dragon's death and the rematch body being adopted.
    *
    * <p>The ceremony belongs to a <b>rematch</b>, not to a first arrival and not to a body picked
    * up off a reload, and this is the one bit that tells them apart: it is set when the fight's own
    * death finishes and cleared the moment a new body is adopted, so the next dragon to appear is
    * the one the island is bringing back.
    */
   private static boolean awaitingRematch = false;
   /**
    * How many pillars the ceremony relights.
    *
    * <p>Vanilla's ring is ten; the ceremony lights them one at a time across the reel, so "the
    * crystals reignite one by one" is a count rather than a sentence.
    */
   public static final int RESPAWN_CRYSTALS = 10;
   /**
    * True while the self-test is driving this machine against its own dragon.
    *
    * <p>Not a feature and not reachable from a command: the harness drives a real body through
    * the real finale, the real landing and the real death, and a real player standing in the End
    * while a test runs must not be hit by the test's rings. Only the blows against bystanders are
    * suppressed - every state change, every packet to nobody, and the dragon's own death still
    * happen exactly as they do in a fight.
    */
   private static boolean harnessMode = false;

   private EnderDragonManager() {
   }

   // ------------------------------------------------------------------ the arithmetic, pinned

   /**
    * Which phase a health figure belongs to.
    *
    * <p>A pure function rather than a field, because "phase three" has to mean the same thing to
    * the rotation, to the action bar and to the last-stand trigger, and the only way three readers
    * agree is if there is one calculation.
    */
   public static int phaseFor(float health, float max) {
      if (max <= 0.0F) {
         return 1;
      }
      float fraction = health / max;
      if (fraction <= PHASE_THREE_AT) {
         return 3;
      }
      if (fraction <= PHASE_TWO_AT) {
         return 2;
      }
      return 1;
   }

   /**
    * True when a blow would take a phase-three dragon to its floor.
    *
    * <p>The one rule that makes "at one heart it gets i-frames and does the big thing" true: the
    * blow is refused, the health is pinned, and the finale starts. It happens <b>once</b>, and
    * never once the dragon is already in its last stand - a second trigger would make the fight
    * unkillable, which is the failure mode this whole design is one line away from.
    */
   public static boolean reachesLastStand(int phase, float health, float amount, boolean alreadyLastStand, boolean finaleDone) {
      if (phase != 3 || alreadyLastStand || finaleDone) {
         return false;
      }
      return health - amount <= LAST_STAND_HEALTH;
   }

   /**
    * True when a blow is the killing one.
    *
    * <p>Before the last stand that means a blow that would empty the bar; in the last stand it
    * means <b>any</b> landed blow at all, because the dragon is on one heart and one hit is the
    * whole point of having left it there.
    */
   public static boolean reachesDeath(float health, float amount, boolean inLastStand) {
      if (amount <= 0.0F) {
         return false;
      }
      return inLastStand || health - amount <= 0.0F;
   }

   /** How many ticks between moves in a rotation - faster as the fight gets worse. */
   public static int moveGapFor(int rotationPhase) {
      return switch (rotationPhase) {
         case 2 -> MOVE_GAP_P2;
         case 3 -> MOVE_GAP_P3;
         default -> MOVE_GAP_P1;
      };
   }

   // ------------------------------------------------------------------ the hold

   /**
    * Whether the vanilla fight may create its dragon.
    *
    * <p>Asked from {@code EnderDragonFight.findOrCreateDragon}, which is the fight saying "I want
    * a dragon" - so saying no here is precisely "not yet", and the fight keeps ticking normally
    * in every other respect. The refusal is narrow on purpose: a fight that has been won is
    * vanilla's to restart, and a fight that already has a dragon is never touched.
    */
   public static boolean shouldHoldTheDragon(ServerLevel end) {
      if (end == null || riteOpened) {
         return false;
      }
      // A fight that has just been won is not waiting for a rift. The island owes a rematch, and
      // that rematch is vanilla's - four crystals on the portal, and then a body - so the hold
      // stands down until the body arrives (see {@link #awaitingRematch}, which the adopt of that
      // body clears). Without this the rite re-armed every time a dragon died: the corpse was gone
      // within a few seconds, the room was still standing at the middle of the island where the
      // kill happened, and the rift tore open again - a brand new dragon through a brand new rift,
      // on a loop, with the theme starting over on every round of it. A fight that cannot end is
      // not a fight, and a kill that is undone is not a kill.
      if (awaitingRematch) {
         return false;
      }
      try {
         EnderDragonFight fight = end.getDragonFight();
         if (fight == null) {
            return false;
         }
         // The question is not "what does the fight remember", it is "is there a dragon out
         // there". Asking the level for its own dragons - rather than the fight's stored uuid,
         // which a dead body leaves behind, or `hasPreviouslyKilledDragon`, which used to end the
         // hold for good after the first win - is the difference between the rite running for
         // every dragon and the rite running exactly once. A rematch used to skip the hold
         // entirely and drop out of the sky vanilla-style, which is the report this answers:
         // "sometimes the rework does not apply and it just spawns normally".
         if (!end.getDragons().isEmpty()) {
            return false;
         }
         EnderDragon mine = dragon;
         if (mine != null && !mine.isRemoved() && mine.isAlive()) {
            return false;
         }
         // And a fight that has already been **won** is not waiting for anything. This is the one
         // piece of this question that is written into the level rather than kept in a field,
         // which is exactly why it is the right one: leaving the End, leaving the world and
         // walking back in used to replay the whole summoning - and its spawn show - over a
         // corpse, because every field this class remembers had just been emptied. The fight's own
         // flag cannot be emptied. Vanilla clears it when a real rematch begins (four crystals on
         // the portal, which is when `respawnStage` is set), so the normal way to get a second
         // dragon still works exactly as it always did - and the rework still holds *that* dragon
         // until somebody walks to the middle.
         if (fight instanceof com.fortuneandfavors.mixin.DragonFightAccessor acc
            && acc.fortuneandfavors$dragonKilled()
            && acc.fortuneandfavors$respawnStage() == null) {
            return false;
         }
         return true;
      } catch (Throwable t) {
         // A failure here means "let vanilla do what it was going to do": an unheld spawn is a
         // worse fight, and a fight that can never start is a broken End.
         return false;
      }
   }

   /**
    * The rift has torn: let the fight make its dragon, and clear the way if a dead body left its
    * uuid behind.
    *
    * <p>Vanilla's {@code setDragonKilled} does not null the fight's {@code dragonUUID} - it flips
    * {@code dragonKilled} and nothing else - and while that uuid is there the fight counts ticks
    * against it and refuses to ask for a new dragon for a full minute. A rematch we hold (so it
    * comes through the rift rather than dropping out of the sky) therefore has to clear it once
    * the rift is ready, or the released rift looks like it did nothing at all. A respawn
    * animation that is still running is deliberately left alone: that animation creates the
    * dragon itself at its own last stage.
    */
   private static void releaseTheFight(ServerLevel end) {
      try {
         EnderDragonFight fight = end.getDragonFight();
         if (fight instanceof com.fortuneandfavors.mixin.DragonFightAccessor acc
            && acc.fortuneandfavors$respawnStage() == null
            && acc.fortuneandfavors$dragonUUID() != null) {
            acc.fortuneandfavors$setDragonUUID(null);
            acc.fortuneandfavors$setDragonKilled(false);
         }
      } catch (Throwable ignored) {
      }
   }

   /**
    * Should the arrival be running right now?
    *
    * <p>One arithmetic, read by the tick and by a check, because the interesting failures here are
    * all about <b>which</b> dragon the arrival is for and they are invisible from the outside: a
    * count-up that never starts (the show is skipped and the dragon simply appears), one that
    * starts on a body adopted mid-fight (a live bar dropped to nothing), or one that runs twice.
    */
   public static boolean arrivalDue(boolean awaiting, boolean alreadyDone, int running, float health, float max) {
      if (running > 0) {
         return true;
      }
      return awaiting && !alreadyDone && health >= Math.max(1.0F, max) - 0.01F;
   }

   /**
    * What the boss bar reads during the arrival, as a function of the clock.
    *
    * <p>One arithmetic rather than a line inside the show, because the two claims worth pinning
    * about an arrival that fills the bar are arithmetic: it starts at nothing (and never at zero,
    * which is a corpse) and it ends at exactly the dragon's maximum. The check reads this, and so
    * does {@link #tickRise}.
    */
   public static float riseHealth(int ticksLeft, float maxHealth) {
      float max = Math.max(1.0F, maxHealth);
      float fraction = 1.0F - (float)Math.max(0, ticksLeft) / (float)RISE_TICKS;
      return Math.max(RISE_FLOOR, Math.min(max, max * fraction));
   }

   /** Has the rift already opened (or is it tearing)? Read by a command, a check, and the hold. */
   public static boolean isRiteOpen() {
      return riteOpened || riteTicks > 0;
   }

   /** Ticks left in the rift, or 0. */
   public static int riteTicksLeft() {
      return Math.max(0, riteTicks);
   }

   // ------------------------------------------------------------------ the tick

   public static void tick(MinecraftServer server) {
      if (server == null) {
         return;
      }
      ServerLevel end = server.getLevel(Level.END);
      if (end == null) {
         return;
      }
      long now = ServerClock.clock(end);
      // The budget is per tick and is opened before anything draws: everything this tick asks for,
      // from the aura to the death ceremony, is spent against the same ceiling.
      beginParticleTick();
      // Before anything else: a fall through the End is a rescue, not a death, and it has to be
      // caught on the tick it happens rather than after the fight's own work is done.
      tickVoidRescue(end);
      tickBolts(end);
      tickCrystalRoster(end);
      tickAfterglow(end);
      // The way out of the End is always lit: the portal runs on its own clock rather than as part
      // of any fight, because it is true before the dragon and after it.
      tickExitPortal(end, now);
      tickName(end);
      tickProtectors(end);
      tickCreeperProtectors(end);
      tickTheme(end);
      // The credits are the one piece of this class's music that runs with no dragon at all: they
      // start on a death and are still playing over the corpse long after the body is gone.
      EnderCreditsMusic.tick(end);
      tickAuraField(end);

      EnderDragon found = findDragon(end, now);
      if (found != null) {
         // Adoption is the one place a body becomes this class's fight, and it refuses anything
         // dead or gone - so what comes back is the body the fight actually owns, or nothing at
         // all. A body that was refused here must not be fought anyway: a corpse that the rotation
         // was allowed to drive is a dragon throwing moves while it is dying.
         adopt(found);
         found = dragon;
      }
      // The End's opening theme, which is a cue for the *quiet* island. A body that has never been
      // here hears it on arrival, plays it once, and has it taken away - faded - on the first tick a
      // dragon exists, which is the whole of "fades out early when the ender dragon spawns so the
      // ender dragon music plays correctly": the fight's own theme is eight seconds behind this call
      // (the ceremony, then the arrival's count-up), and it comes in over a claim this cue took and
      // did not give back. See EndIntroMusic.
      EndIntroMusic.tick(end, found != null);
      if (found == null) {
         // No dragon at all: the fight is over, or the body was taken out of the world without
         // dying. Either way nothing this class started may be left running - the music, the
         // aura and the protectors all belong to a fight that no longer exists.
         if (themePlaying) {
            stopTheme(end);
         }
         if (!PROTECTORS.isEmpty()) {
            clearProtectors(end);
         }
         if (!CREEPER_PROTECTORS.isEmpty()) {
            clearCreeperProtectors(end);
         }
         // The crystal roster goes with the fight: next time these crystals are somebody else's.
         clearCrystalRoster();
         auraFieldTicks = 0;
         stopLastingMoves();
         // No dragon yet: either we are still holding it, or the rift is open.
         if (riteTicks > 0) {
            tickRite(end, now);
         } else {
            watchForTheMiddle(end, now);
         }
         return;
      }

      // Belt and braces on the bar. The attribute is applied when a body is adopted, but a dragon
      // whose maximum was written by vanilla before this class ever saw it - an arrival restored
      // mid-save, a body a datapack nudged - is normalised here every tick. Idempotent, and it is
      // the difference between "the dragon has 350 health" and "the dragon has 350 health if every
      // path happens to run".
      applyDragonHealth(found);
      ensureTheme(end, found);
      // The bar comes before every branch below, because it is the one thing that has to be right
      // in all of them - the arrival's count-up, the last stand's pin at one heart, the finale and
      // the death ceremony all run through their own return, and every one of them is a bar the
      // room is watching.
      dressBar(end, found, phase);
      if (deathTicks > 0) {
         tickDeathCeremony(end, now);
         return;
      }
      if (finaleTicks > 0) {
         tickFinale(end, found, now);
         return;
      }
      if (ceremonyTicks > 0) {
         tickCeremony(end, found, now);
      }
      // The rematch owns the body before the arrival can: a ceremony in progress is the island
      // putting a dragon back, and it is not a fight yet - no move, no aura, no phase arithmetic.
      if (respawnTicks > 0) {
         tickRespawn(end, found);
         return;
      }
      // The arrival owns the body until the bar has finished filling. Nothing else may run
      // against it: a move started here would be a phase read off a health bar that is still
      // being written, which is how a dragon arrives already in phase three.
      if (arrivalDue(awaitingArrival, spawnShowDone, riseTicks, found.getHealth(), found.getMaxHealth())) {
         tickRise(end, found);
         return;
      }
      fightTick(end, found, now);
   }

   // ------------------------------------------------------------------ spawn: held, then the rift

   /**
    * Waits for somebody to walk to the middle, then opens the rift.
    *
    * <p>The whole reason this exists is the difference between <i>entering the End</i> and
    * <i>arriving at the fight</i>: a player who flies in to mine chorus fruit, grab an elytra or
    * look at the sky is not asking to be met by a dragon, and vanilla answers that question with
    * a dragon anyway.
    */
   private static void watchForTheMiddle(ServerLevel end, long now) {
      // The rift is not "somebody is standing in the middle": it is "the dragon this fight is
      // still waiting for may come through". Vanilla is asked the same question the hold asks,
      // and it is asked here rather than trusting this class's own memory of having opened a
      // rift - because that memory is a field, and a field is empty again after every reload.
      // That is exactly the bug this answers: re-entering the End (or rejoining a world) used to
      // replay the whole summoning over a fight that was already won or already running, because
      // the only thing remembered - "a rift has opened this session" - had just been forgotten.
      // A fight that already has a dragon is never greeted twice, and a fight that has already
      // been won is not greeted at all; the rematch is vanilla's, as it always was.
      if (!shouldHoldTheDragon(end)) {
         return;
      }
      for (ServerPlayer p : end.getPlayers(pl -> !pl.isSpectator() && pl.isAlive())) {
         if (Math.abs(p.getX()) <= RITE_RADIUS && Math.abs(p.getZ()) <= RITE_RADIUS) {
            openRite(end, p);
            return;
         }
      }
   }

   /** The rift tears open over the island. Five seconds, then the dragon is released. */
   private static void openRite(ServerLevel end, ServerPlayer trigger) {
      riteTicks = RITE_OPEN_TICKS;
      Vec3 centre = new Vec3(0.5, 64.0, 0.5);
      for (ServerPlayer p : end.getPlayers(pl -> !pl.isSpectator())) {
         // No title here. The one title this event is allowed to spend is the dragon's own name,
         // and it is spent when the dragon is here - so the arrival speaks on the action bar and
         // saves the screen for the thing the whole rite is about.
         p.sendOverlayMessage(Component.literal("\u00a75\u00a7lSOMETHING IS AT THE MIDDLE OF THE ISLAND \u00a78| \u00a7f" + (RITE_OPEN_TICKS / 20) + "s"));
      }
      end.playSound(null, centre.x, centre.y, centre.z, SoundEvents.END_PORTAL_SPAWN, SoundSource.AMBIENT, 4.0F, 0.6F);
      end.playSound(null, centre.x, centre.y, centre.z, SoundEvents.WITHER_SPAWN, SoundSource.AMBIENT, 2.5F, 0.5F);
      LOGGER.info("Ender dragon rework: the rift is open ({} triggered it)", trigger.getName().getString());
   }

   /** One tick of the rift: a hole in the world, growing. */
   private static void tickRite(ServerLevel end, long now) {
      riteTicks--;
      double progress = 1.0 - (double)riteTicks / (double)RITE_OPEN_TICKS;
      int step = RITE_OPEN_TICKS - riteTicks;
      Vec3 centre = new Vec3(0.5, 64.0, 0.5);

      // A vertical slash of void at the island's centre, opening wider every tick.
      double height = 6.0 + progress * 30.0;
      for (int i = 0; i < 90; i++) {
         double y = 64.0 - height * 0.5 + RANDOM.nextDouble() * height;
         double spread = 0.6 + progress * 3.2;
         spend(end, 
            ParticleTypes.PORTAL,
            centre.x + (RANDOM.nextDouble() - 0.5) * spread,
            y,
            centre.z + (RANDOM.nextDouble() - 0.5) * spread,
            1, 0.0, 0.0, 0.0, 0.45
         );
      }
      // Something is being pulled in as well as pushed out: the same light, backwards.
      spend(end, ParticleTypes.REVERSE_PORTAL, centre.x, centre.y, centre.z, 60, 2.5 * progress + 0.5, 6.0, 2.5 * progress + 0.5, -0.3);
      spend(end, ParticleTypes.SCULK_SOUL, centre.x, centre.y, centre.z, 18, 1.5, 6.0, 1.5, 0.05);
      spend(end, ParticleTypes.GUST_EMITTER_LARGE, centre.x, centre.y, centre.z, 1, 0.0, 0.0, 0.0, 0.0);
      // The island's own dead are already turning over: ash and souls rising to the hole before
      // anything has come through it, which is what makes the middle read as *occupied*.
      spend(end, ParticleTypes.WHITE_ASH, centre.x, centre.y + 2.0, centre.z, 26, 3.0 + progress * 8.0, 8.0, 3.0 + progress * 8.0, -0.02);
      spend(end, ParticleTypes.FIREFLY, centre.x, centre.y + 2.0, centre.z, 14, 3.0 + progress * 7.0, 7.0, 3.0 + progress * 7.0, 0.0);
      // A column of light standing in the middle from the first tick, so the island can see where
      // to look from anywhere in its render distance rather than being told to look up.
      pillar(end, centre.add(0.0, -2.0, 0.0), 10.0 + progress * 30.0, ParticleTypes.END_ROD);

      // Shockwave rings walking out of the rift, twice a second, each bigger than the last.
      if (step % 10 == 0) {
         ring(end, centre, 4.0 + progress * 30.0, 44, ParticleTypes.CLOUD, 1.2, 0.02);
         ring(end, centre, 2.0 + progress * 18.0, 32, ParticleTypes.REVERSE_PORTAL, -0.9, 0.02);
         nova(end, centre, 6.0 + progress * 24.0, ParticleTypes.PORTAL, 0xAA66FF);
         spend(end, ParticleTypes.EXPLOSION_EMITTER, centre.x, 64.0, centre.z, 2, 2.0, 2.0, 2.0, 0.0);
         end.playSound(null, centre.x, centre.y, centre.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.AMBIENT, 3.0F, 0.5F + (float)progress);
      }
      // The sky answers long before the dragon does. Visual-only lightning: the flash is the
      // point, and a bolt that starts fires on the island is a different bug.
      if (step % 12 == 0) {
         for (int i = 0; i < 3; i++) {
            visualLightning(end, centre.x + (RANDOM.nextDouble() - 0.5) * 60.0, 70.0, centre.z + (RANDOM.nextDouble() - 0.5) * 60.0);
         }
      }
      if (step % 25 == 0) {
         end.playSound(null, centre.x, centre.y, centre.z, SoundEvents.ENDER_DRAGON_GROWL, SoundSource.AMBIENT, 3.0F, 0.4F + (float)progress * 0.4F);
      }
      if (step % 10 == 0) {
         for (ServerPlayer p : end.getPlayers(pl -> !pl.isSpectator())) {
            p.sendOverlayMessage(Component.literal(
               "\u00a75\u00a7lSOMETHING IS HAPPENING AT THE MIDDLE \u00a78| \u00a7f" + Math.max(0, riteTicks / 20) + "s"
            ));
         }
      }

      if (riteTicks <= 0) {
         // The last blast of the rift, and then the hold is off: the fight creates its dragon on
         // its own next tick, which is exactly the moment the two halves have to line up.
         spend(end, ParticleTypes.EXPLOSION_EMITTER, centre.x, 64.0, centre.z, 12, 1.5, 3.0, 1.5, 0.0);
         spend(end, ParticleTypes.PORTAL, centre.x, 65.0, centre.z, 600, 3.0, 8.0, 3.0, 0.8);
         spend(end, ParticleTypes.GUST, centre.x, 65.0, centre.z, 80, 4.0, 4.0, 4.0, 0.0);
         end.playSound(null, centre.x, centre.y, centre.z, SoundEvents.ENDER_DRAGON_GROWL, SoundSource.AMBIENT, 5.0F, 0.7F);
         end.playSound(null, centre.x, centre.y, centre.z, SoundEvents.WARDEN_SONIC_BOOM, SoundSource.AMBIENT, 4.0F, 0.5F);
         end.playSound(null, centre.x, centre.y, centre.z, SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.WEATHER, 4.0F, 0.6F);
         riteOpened = true;
         releaseTheFight(end);
         ceremonyTicks = RITE_CEREMONY_TICKS;
         // The arrival belongs to the dragon this rift is about to release, and to nothing else.
         awaitingArrival = true;
         spawnShowDone = false;
         LOGGER.info("Ender dragon rework: the rift tore - the fight is released");
      }
   }

   /**
    * The dragon is here: the arrival, and then the fight is on.
    *
    * <p>No title is sent from here. The one title this event is allowed is sent once, at the top
    * of the count-up, because a title repeated every second is the same line as a loading screen:
    * the arrival has to say its name exactly once and then be a fight.
    */
   private static void tickCeremony(ServerLevel end, EnderDragon dragon, long now) {
      ceremonyTicks--;
      Vec3 at = dragon.position();
      spend(end, ParticleTypes.PORTAL, at.x, at.y + 3.0, at.z, 120, 5.0, 4.0, 5.0, 0.6);
      spend(end, ParticleTypes.END_ROD, at.x, at.y + 3.0, at.z, 40, 4.0, 3.0, 4.0, 0.2);
      spend(end, ParticleTypes.GUST_EMITTER_LARGE, at.x, at.y + 2.0, at.z, 2, 2.0, 2.0, 2.0, 0.0);
      if (ceremonyTicks % 20 == 0) {
         end.playSound(null, at.x, at.y, at.z, SoundEvents.ENDER_DRAGON_AMBIENT, SoundSource.HOSTILE, 4.0F, 0.6F);
      }
      if (ceremonyTicks <= 0) {
         for (ServerPlayer p : end.getPlayers(pl -> !pl.isSpectator())) {
            Chat.raw(p, "\u00a75\u00a7lTHE ENDER DRAGON\u00a77 - the island was never empty, it was \u00a7fwaiting\u00a77.");
         }
      }
   }

   /**
    * The rematch, opening: the body is pulled down into the portal and the bar is set to nothing.
    *
    * <p>Three things are turned off here and all of them matter: the arrival's own count-up (a
    * second clock over this one would fight it), the phase arithmetic (a bar of nothing reads as
    * phase three), and the last stand (the previous fight may have ended there).
    */
   private static void beginRespawnCeremony(EnderDragon body) {
      respawnTicks = RESPAWN_TICKS;
      respawnLit = 0;
      awaitingArrival = false;
      spawnShowDone = true;
      riseTicks = 0;
      phase = 1;
      lastStand = false;
      finaleTicks = 0;
      deathTicks = 0;
      body.setHealth(RISE_FLOOR);
      body.setPos(0.5, RESPAWN_RISE_FROM, 0.5);
      LOGGER.info("Ender dragon rework: the rematch ceremony has begun");
   }

   /**
    * The rematch ceremony: the island reels, the crystals are relit one by one, the body climbs
    * out of the portal.
    *
    * <p>One clock with three beats in it, and the body's own health is the bar: it climbs with the
    * body, so the vanilla boss bar fills as the dragon rises rather than jumping to full the tick
    * it appears - which is the whole difference between a ceremony and a spawn.
    */
   private static void tickRespawn(ServerLevel end, EnderDragon dragon) {
      respawnTicks--;
      int elapsed = RESPAWN_TICKS - respawnTicks;
      if (elapsed <= RESPAWN_REEL_TICKS) {
         // Beat one: the island itself moves, and nothing is lit yet.
         if (elapsed == 1) {
            for (ServerPlayer p : end.getPlayers(pl -> !pl.isSpectator())) {
               Chat.raw(p, "\u00a75\u00a7lTHE ISLAND REMEMBERS \u00a77- something is coming back.");
            }
            end.playSound(null, 0.5, 64.0, 0.5, SoundEvents.WARDEN_HEARTBEAT, SoundSource.AMBIENT, 5.0F, 0.5F);
         }
         if (elapsed % 4 == 0) {
            spend(end, ParticleTypes.SCULK_SOUL, 0.5, 66.0, 0.5, 80, 14.0, 5.0, 14.0, 0.2);
            end.playSound(null, 0.5, 64.0, 0.5, SoundEvents.RESPAWN_ANCHOR_CHARGE, SoundSource.AMBIENT, 3.0F, 0.6F);
         }
         // The island reeling, drawn as a ring that walks outward from the middle over the whole
         // reel: the ground itself is the thing answering, so the read has to be on the ground.
         double reel = (double)elapsed / (double)Math.max(1, RESPAWN_REEL_TICKS);
         double walk = 6.0 + reel * 44.0;
         ring(end, new Vec3(0.5, 64.2, 0.5), walk, (int)(walk * 1.6), ParticleTypes.SCULK_SOUL, 0.0, 0.05);
         spend(end, ParticleTypes.REVERSE_PORTAL, 0.5, 64.4, 0.5, 30, 3.0 + reel * 8.0, 1.2, 3.0 + reel * 8.0, -0.08);
         if (elapsed % 12 == 0) {
            // A heartbeat under it all: the island is not merely shaking, it is alive.
            end.playSound(null, 0.5, 64.0, 0.5, SoundEvents.WARDEN_HEARTBEAT, SoundSource.AMBIENT, 2.5F + (float)reel * 2.0F, 0.6F);
            flash(end, new Vec3(0.5, 66.0, 0.5), 0x66BBFF);
         }
         // Beat two: the crystals come back one at a time across the reel, so the ring is visibly
         // refilling rather than snapping on all at once.
         int lit = Math.max(0, Math.min(RESPAWN_CRYSTALS, elapsed * RESPAWN_CRYSTALS / RESPAWN_REEL_TICKS));
         if (lit > respawnLit) {
            respawnLit = lit;
            relightCrystal(end, lit - 1);
         }
         dragon.setHealth(RISE_FLOOR);
         dragon.setPos(0.5, RESPAWN_RISE_FROM, 0.5);
         return;
      }
      // Beat three: the body climbs out of the portal and up, with a beam of the End under it.
      double progress = (double)(elapsed - RESPAWN_REEL_TICKS)
         / (double)Math.max(1, RESPAWN_TICKS - RESPAWN_REEL_TICKS);
      double climb = Math.min(1.0, progress);
      dragon.setPos(0.5, RESPAWN_RISE_FROM + (RESPAWN_RISE_TO - RESPAWN_RISE_FROM) * climb, 0.5);
      dragon.setHealth(Math.max(RISE_FLOOR, DRAGON_HEALTH * (float)climb));
      Vec3 at = dragon.position();
      spend(end, ParticleTypes.PORTAL, at.x, at.y + 3.0, at.z, 90, 6.0, 6.0, 6.0, 0.5);
      spend(end, ParticleTypes.END_ROD, at.x, at.y + 3.0, at.z, 40, 5.0, 5.0, 5.0, 0.3);
      beam(end, new Vec3(0.5, 64.0, 0.5), at, ParticleTypes.REVERSE_PORTAL, 0.8);
      // The body is being pulled up <b>through</b> something: a sleeve of the End that tightens as
      // it climbs, plus a column of souls under it, so the rise reads as a thing being extracted
      // rather than a model sliding upward.
      ring(end, at, 10.0 - climb * 4.5, 48, ParticleTypes.REVERSE_PORTAL, -1.4, 0.0);
      ring(end, at, 4.0 + climb * 3.0, 36, ParticleTypes.SCULK_SOUL, 0.0, 0.03);
      pillar(end, new Vec3(0.5, 64.0, 0.5), RESPAWN_RISE_FROM + (RESPAWN_RISE_TO - RESPAWN_RISE_FROM) * climb, ParticleTypes.END_ROD);
      if (elapsed % 6 == 0) {
         spend(end, ParticleTypes.FIREWORK, at.x, at.y + 1.0, at.z, 26, 4.0, 2.0, 4.0, 0.15);
      }
      if (elapsed % 10 == 0) {
         end.playSound(null, at.x, at.y, at.z, SoundEvents.ENDER_DRAGON_AMBIENT, SoundSource.HOSTILE, 4.0F, 0.5F + (float)climb * 0.4F);
      }
      if (respawnTicks <= 0) {
         dragon.setHealth(dragon.getMaxHealth());
         phase = 1;
         lastStand = false;
         moveCooldown = 40;
         spend(end, ParticleTypes.EXPLOSION_EMITTER, at.x, at.y, at.z, 8, 4.0, 4.0, 4.0, 0.0);
         nova(end, at, 22.0, ParticleTypes.WHITE_ASH, 0xCCBBFF);
         end.playSound(null, at.x, at.y, at.z, SoundEvents.ENDER_DRAGON_GROWL, SoundSource.HOSTILE, 5.0F, 1.0F);
         for (ServerPlayer pr : end.getPlayers(pl -> !pl.isSpectator())) {
            Chat.raw(pr, "\u00a75\u00a7lTHE ENDER DRAGON \u00a77is back - and it remembers.");
         }
         startNameShow(end);
         startTheme(end);
         LOGGER.info("Ender dragon rework: the rematch ceremony is over - full health, phase one");
      }
   }

   /**
    * One pillar's crystal coming back on, as a beam and a flash.
    *
    * <p>A light and a sound rather than a new End Crystal entity, on purpose: the ring is the
    * fight's business and spawning real crystals here would hand vanilla a ring it did not build,
    * which is how a rematch ends up with an uncountable, unbreakable crystal. What the room sees is
    * the same thing either way - a pillar lighting back up, one per beat.
    */
   private static void relightCrystal(ServerLevel end, int index) {
      double angle = (Math.PI * 2.0 / RESPAWN_CRYSTALS) * index;
      double x = Math.cos(angle) * END_TOWER_RADIUS;
      double z = Math.sin(angle) * END_TOWER_RADIUS;
      double y = 76.0;
      spend(end, ParticleTypes.END_ROD, x, y, z, 60, 1.2, 2.0, 1.2, 0.2);
      spend(end, ColorParticleOption.create(ParticleTypes.FLASH, 0xFFE9FF), x, y, z, 1, 0.0, 0.0, 0.0, 0.0);
      beam(end, new Vec3(x, y, z), new Vec3(0.5, 66.0, 0.5), ParticleTypes.END_ROD, 0.5);
      end.playSound(null, x, y, z, SoundEvents.END_PORTAL_FRAME_FILL, SoundSource.AMBIENT, 3.0F, 0.8F + (float)index * 0.05F);
   }

   /**
    * The exit portal, breathing.
    *
    * <p>The way out of the End is the one landmark in the dimension that never stops mattering -
    * the fight's corpse, the egg, the way home - and it used to be a still block of purple. This
    * keeps a slow ring of the End's own light turning around it so the way out reads as a place
    * from across the island, and it runs on its own clock rather than as part of any fight, because
    * the portal is the one thing in the End that is true before the dragon and after it.
    */
   private static void tickExitPortal(ServerLevel end, long now) {
      if (now % 8L != 0L) {
         return;
      }
      try {
         net.minecraft.core.BlockPos centre = new net.minecraft.core.BlockPos(0, 64, 0);
         boolean found = false;
         for (int dx = -4; dx <= 4 && !found; dx++) {
            for (int dz = -4; dz <= 4 && !found; dz++) {
               var state = end.getBlockState(centre.offset(dx, 0, dz));
               if (state.is(net.minecraft.world.level.block.Blocks.END_PORTAL)
                  || state.is(net.minecraft.world.level.block.Blocks.END_GATEWAY)) {
                  found = true;
               }
            }
         }
         if (!found) {
            return;
         }
         double phase = (now % 160L) / 160.0 * Math.PI * 2.0;
         double radius = 3.4;
         int points = 20;
         for (int i = 0; i < points; i++) {
            double a = phase + (Math.PI * 2.0) * i / points;
            spend(end, ParticleTypes.PORTAL, 0.5 + Math.cos(a) * radius, 64.6, 0.5 + Math.sin(a) * radius, 2, 0.12, 0.12, 0.12, 0.02);
         }
         spend(end, ParticleTypes.REVERSE_PORTAL, 0.5, 66.0, 0.5, 4, 3.0, 1.0, 3.0, -0.05);
         if (now % 40L == 0L) {
            spend(end, ParticleTypes.END_ROD, 0.5, 66.0, 0.5, 8, 2.0, 1.5, 2.0, 0.05);
         }
      } catch (Throwable ignored) {
         // Decoration: a failure here must never take the tick with it.
      }
   }

   /**
    * The arrival's bar: health from nothing to full, over three seconds, out loud.
    *
    * <p>Started the first tick after the dragon exists, and only ever for a dragon that is
    * <b>full</b> - a body found at sixty percent after a restart is a fight in progress, and
    * emptying its bar to re-run an arrival would be the single worst thing this class could do to
    * a live fight.
    */
   private static void tickRise(ServerLevel end, EnderDragon dragon) {
      if (!spawnShowDone && dragon.getHealth() < dragon.getMaxHealth() - 0.01F) {
         // A bar with a fight behind it is not an arrival: the rift released a dragon that was
         // already hurt, and the show stands down rather than emptying it.
         riseTicks = 0;
         spawnShowDone = true;
         awaitingArrival = false;
         return;
      }
      if (!spawnShowDone) {
         spawnShowDone = true;
         awaitingArrival = false;
         riseTicks = RISE_TICKS;
         dragon.setHealth(RISE_FLOOR);
         Vec3 at = dragon.position();
         // Nothing on the screen yet: the name is written at the *end* of the arrival, because the
         // arrival is the thing worth watching and a title over it would be a caption over it.
         flash(end, at, 0xAA66FF);
         nova(end, at, 26.0, ParticleTypes.PORTAL, 0xAA66FF);
         nova(end, at, 14.0, ParticleTypes.END_ROD, 0xFF66FF);
         pillar(end, at.add(0.0, -3.0, 0.0), 26.0, ParticleTypes.END_ROD);
         ring(end, new Vec3(0.5, 64.0, 0.5), 12.0, 48, ParticleTypes.CLOUD, 1.4, 0.06);
         ring(end, new Vec3(0.5, 64.0, 0.5), 24.0, 64, ParticleTypes.REVERSE_PORTAL, -1.1, 0.04);
         for (ServerPlayer p : end.getPlayers(pl -> !pl.isSpectator())) {
            p.sendOverlayMessage(Component.literal("\u00a75\u00a7lTHE RIFT HAS OPENED \u00a78| \u00a7fand it is coming through"));
            Chat.raw(p, "\u00a75\u00a7lTHE ENDER DRAGON\u00a77 has come through the rift.");
         }
         end.playSound(null, at.x, at.y, at.z, SoundEvents.ENDER_DRAGON_GROWL, SoundSource.HOSTILE, 6.0F, 0.5F);
         end.playSound(null, at.x, at.y, at.z, SoundEvents.END_PORTAL_SPAWN, SoundSource.AMBIENT, 4.0F, 0.6F);
         end.playSound(null, at.x, at.y, at.z, SoundEvents.WITHER_SPAWN, SoundSource.AMBIENT, 2.5F, 0.4F);
      }

      riseTicks--;
      dragon.setHealth(riseHealth(riseTicks, dragon.getMaxHealth()));

      Vec3 at = dragon.position();
      double progress = 1.0 - (double)riseTicks / (double)RISE_TICKS;
      spend(end, ParticleTypes.END_ROD, at.x, at.y + 3.0, at.z, 50, 3.0 + progress * 5.0, 4.0, 3.0 + progress * 5.0, 0.35);
      spend(end, ParticleTypes.REVERSE_PORTAL, at.x, at.y + 3.0, at.z, 80, 4.0 + progress * 3.0, 4.0, 4.0 + progress * 3.0, -0.3);
      spend(end, ParticleTypes.PORTAL, at.x, at.y + 2.0, at.z, 70, 4.0, 3.0, 4.0, 0.5);
      // The rift is still holding the body: a sleeve of void around a dragon that is being pushed
      // through it, which is what makes the count-up read as an arrival rather than as a bar.
      ring(end, at, 6.0 + progress * 10.0, 40, ParticleTypes.REVERSE_PORTAL, -1.2, 0.0);
      if (riseTicks % 5 == 0) {
         beam(end, new Vec3(0.5, 96.0, 0.5), at, ParticleTypes.PORTAL, 0.6);
         spend(end, ParticleTypes.FIREWORK, at.x, at.y + 2.0, at.z, 24, 4.0, 3.0, 4.0, 0.2);
      }
      if (riseTicks % 10 == 0) {
         // The bar filling is the announcement, and the action bar says the same number as the
         // vanilla bar so a player watching either one reads the same thing.
         int shown = Math.round(100.0F * dragon.getHealth() / Math.max(1.0F, dragon.getMaxHealth()));
         for (ServerPlayer p : end.getPlayers(pl -> !pl.isSpectator())) {
            p.sendOverlayMessage(Component.literal("\u00a75\u00a7lTHE DRAGON ARRIVES \u00a78| \u00a7f" + shown + "%"));
         }
         end.playSound(null, at.x, at.y, at.z, SoundEvents.ENDER_DRAGON_AMBIENT, SoundSource.HOSTILE, 3.0F, 0.5F + (float)progress * 0.4F);
      }

      if (riseTicks <= 0) {
         dragon.setHealth(dragon.getMaxHealth());
         phase = 1;
         lastStand = false;
         moveCooldown = 40;
         spend(end, ParticleTypes.EXPLOSION_EMITTER, at.x, at.y, at.z, 10, 4.0, 4.0, 4.0, 0.0);
         spend(end, ParticleTypes.GUST_EMITTER_LARGE, at.x, at.y, at.z, 5, 3.0, 3.0, 3.0, 0.0);
         spend(end, ParticleTypes.GUST, at.x, at.y, at.z, 160, 10.0, 5.0, 10.0, 0.05);
         nova(end, at, 22.0, ParticleTypes.WHITE_ASH, 0xCCBBFF);
         end.playSound(null, at.x, at.y, at.z, SoundEvents.ENDER_DRAGON_GROWL, SoundSource.HOSTILE, 5.0F, 1.1F);
         // And now the name, one character at a time, and the music under it.
         startNameShow(end);
         startTheme(end);
         LOGGER.info("Ender dragon rework: the arrival is over - full health, phase one");
      }
   }

   // ------------------------------------------------------------------ the name, the theme

   /**
    * The one title this event spends: the dragon's name, written out loud.
    *
    * <p>Sixty ticks of a name assembling itself. Each frame carries the same animation times, so
    * the client is never asked to finish one title before the next arrives, and each frame is also
    * one click of sound - which is the whole reason a title that spells itself is worth doing:
    * the room hears the count. The subtitle and the final flourish land on the last frame with the
    * growl, so the fight's opening title is the last thing that happens in the arrival rather than
    * the first thing that happens in the fight.
    */
   private static void startNameShow(ServerLevel end) {
      nameTicks = 2 * (THE_DRAGON_TITLE.length() + 6);
   }

   /** The name the arrival writes, in one place, because two copies would drift. */
   private static final String THE_DRAGON_TITLE = "THE ENDER DRAGON";

   private static void tickName(ServerLevel end) {
      if (nameTicks <= 0) {
         return;
      }
      int total = THE_DRAGON_TITLE.length();
      int wrote = total - (nameTicks / 2);
      nameTicks--;
      int shown = Math.max(0, Math.min(total, wrote));
      String soFar = THE_DRAGON_TITLE.substring(0, shown);

      for (ServerPlayer p : end.getPlayers(pl -> !pl.isSpectator())) {
         try {
            p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket(0, 14, 8));
            p.connection.send(new ClientboundSetTitleTextPacket(Component.literal("\u00a75\u00a7l" + soFar)));
            if (shown >= total) {
               p.connection.send(new ClientboundSetSubtitleTextPacket(
                  Component.literal("\u00a77the island was never empty - it was \u00a7fwaiting")
               ));
            }
         } catch (Throwable ignored) {
         }
      }

      Vec3 at = dragon == null ? new Vec3(0.5, 70.0, 0.5) : dragon.position();
      if (shown > 0 && shown < total) {
         end.playSound(null, at.x, at.y, at.z, SoundEvents.NOTE_BLOCK_PLING.value(), SoundSource.MASTER, 1.4F, 0.7F + shown * 0.05F);
      }
      if (shown >= total) {
         flash(end, at, 0xAA66FF);
         nova(end, at, 20.0, ParticleTypes.END_ROD, 0xFF66FF);
         end.playSound(null, at.x, at.y, at.z, SoundEvents.ENDER_DRAGON_GROWL, SoundSource.HOSTILE, 6.0F, 0.7F);
         end.playSound(null, at.x, at.y, at.z, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.MASTER, 1.0F, 1.0F);
      }
   }

   /**
    * The theme, handed to every player in the End and then handed to them again.
    *
    * <p>The track is 225 seconds long, which is longer than most fights - but not all of them, and
    * a boss theme that stops mid-fight is worse than one that never started. So the clock runs the
    * track's own length and then serves it again, and anybody who was not there for the last round
    * (a player who just arrived, a player who just reconnected) is served as soon as they are in
    * the End. Every packet is positioned on the player it is for, so no one is listening to the
    * boss's music from a hundred blocks away at half volume.
    */
   private static void startTheme(ServerLevel end) {
      themePlaying = true;
      themeTicks = ENDER_THEME_TICKS;
      THEME_SERVED.clear();
      toastTicks = 70;
      for (ServerPlayer p : end.getPlayers(pl -> !pl.isSpectator())) {
         serveTheme(p);
         Chat.raw(p, "\u266a \u00a77Now playing: \u00a7dEnder Dragon Theme\u00a77 - J. Rivers");
      }
   }

   private static void tickTheme(ServerLevel end) {
      if (toastTicks > 0 && --toastTicks == 0) {
         revokeNowPlaying(end);
      }
      if (!themePlaying) {
         return;
      }
      // Anybody who has left the End loses their copy: a sound is not bound to a dimension, so a
      // player who walks back through the portal would otherwise carry the dragon's music into the
      // overworld with them and hear it from nowhere.
      MinecraftServer server = end.getServer();
      if (server != null) {
         for (ServerPlayer away : server.getPlayerList().getPlayers()) {
            if (!THEME_SERVED.contains(away.getUUID()) || away.level() == end) {
               continue;
            }
            THEME_SERVED.remove(away.getUUID());
            // They lose the theme, and with it the claim it holds on their music: a player who
            // walks back through the portal must be able to hear their own music again, or the
            // soundtrack of a fight they have left would follow them home as silence.
            BossMusic.release(away);
            try {
               away.connection.send(new net.minecraft.network.protocol.game.ClientboundStopSoundPacket(
                  com.fortuneandfavors.FortuneFavorsMod.id("ender_dragon_theme"), SoundSource.RECORDS
               ));
            } catch (Throwable ignored) {
            }
         }
      }
      serveLateArrivals(end);
      if (--themeTicks <= 0) {
         themeTicks = ENDER_THEME_TICKS;
         THEME_SERVED.clear();
      }
   }

   /**
    * The music belongs to the fight, not to the moment the dragon arrived.
    *
    * <p>A player who walks into a fight that is already running, and a server that restarts and
    * adopts a dragon mid-flight, are the same case: the theme used to be started only by the
    * <i>arrival</i>, and both of those happen after it - so a save that had music could be
    * reloaded into a fight with none, and a friend who joined late heard the boss in silence.
    * The tick therefore asks whether a fight that should have music has any, and starts it if it
    * does not. The arrival's own call stays, so the theme still comes in on the beat it always
    * did; this is the door for everything that arrives after it.
    */
   private static void ensureTheme(ServerLevel end, EnderDragon body) {
      if (harnessMode || themePlaying) {
         return;
      }
      boolean alive = body != null && !body.isRemoved() && body.isAlive();
      // The two shows own the body, and both of them start the theme themselves on the last tick of
      // their own clock: the arrival at the end of its count-up, the rematch at the top of the body
      // it has finished raising. This door used to walk in before either of them: it saw a live
      // dragon with music switched off and started the track, so an arrival was announced three
      // seconds early and then announced again, and the rematch ceremony played the theme from its
      // first beat and then restarted it when the body was up. The room heard the line twice and the
      // track once from the top in the middle of it - which is the music half of "the fight keeps
      // starting itself", and the reason the rule below names the script and not only the dragon.
      boolean scriptOwnsTheBody = ceremonyTicks > 0 || riseTicks > 0 || respawnTicks > 0;
      if (!themeShouldRun(alive, deathTicks > 0 || afterglowTicks > 0, scriptOwnsTheBody)) {
         return;
      }
      startTheme(end);
   }

   /**
    * The rule the music runs on: a live dragon has a theme, and a finished one does not.
    *
    * <p>Deliberately <b>not</b> keyed off the rift: the rift is this server session's memory of
    * having opened, and it is false on the first tick after a restart - so a save reloaded into a
    * live dragon would have been a fight with no music for the rest of its life, which is the same
    * bug as a late arrival wearing a different hat. A dragon being managed and not yet finished is
    * the whole condition, and both of those things are facts about the body in front of us.
    *
    * <p>One function, read by the tick and by the self-test, because the two halves of it fail
    * differently: music that outlives its fight follows players out of the End, and music that
    * never starts is the silence this exists to answer.
    *
    * <p>The third question is the one a script answers rather than the fight: an arrival or a
    * rematch ceremony has a live dragon and no music, and it is still not a fight yet - it is the
    * show that ends with the theme coming in. Asking only the first two questions is how the track
    * came in early and then again when the show handed it over, so the rule is stated here rather
    * than left to the call site.
    */
   public static boolean themeShouldRun(boolean dragonAlive, boolean fightOver, boolean scriptOwnsTheBody) {
      return dragonAlive && !fightOver && !scriptOwnsTheBody;
   }

   /**
    * The rule one player's copy of the theme runs on - asked per player, every tick.
    *
    * <p>"Has this player been handed the track this loop" is a fact about one player, so it is a
    * question about one player: a spectator is never served, and nobody is served twice before
    * the track comes round again.
    */
   public static boolean shouldServeTheme(boolean playing, boolean inTheEnd, boolean spectator, boolean alreadyServed) {
      return playing && inTheEnd && !spectator && !alreadyServed;
   }

   /**
    * Hands the theme to anybody standing in the End who has not got it this loop.
    *
    * <p>The late arrival's door, and a method rather than three lines inside the tick because it
    * is the answer to a report: a player who walked into a running fight had nothing to hear. It
    * is called every tick for as long as the fight has music, so no ordering change elsewhere can
    * quietly starve it.
    *
    * @return how many players were served, for a command or a check
    */
   public static int serveLateArrivals(ServerLevel end) {
      if (!themePlaying || end == null) {
         return 0;
      }
      int served = 0;
      for (ServerPlayer p : end.getPlayers(pl -> true)) {
         if (!shouldServeTheme(themePlaying, p.level() == end, p.isSpectator(), THEME_SERVED.contains(p.getUUID()))) {
            continue;
         }
         serveTheme(p);
         served++;
      }
      if (served > 0 && toastTicks <= 0) {
         // A late arrival gets the same "now playing" as everybody else, and the same promise made
         // about it: the toast is taken back out of their advancement screen a few seconds later,
         // exactly as it is for the players who were there when the fight started.
         toastTicks = 70;
      }
      return served;
   }

   /**
    * One player, one copy of the theme.
    *
    * <p>Positioned on the listener rather than on the dragon, because the dragon flies and the
    * music should not get quieter as it does. The toast that says what is playing is a vanilla
    * advancement granted and then taken back - see {@link #revokeNowPlaying}: an advancement that
    * announced a song would otherwise sit in the player's advancement screen forever, which is the
    * one thing the ask said it must not do.
    */
   private static void serveTheme(ServerPlayer p) {
      try {
         if (!THEME_SERVED.contains(p.getUUID())) {
            THEME_SERVED.add(p.getUUID());
         }
         // The theme claims the room's music *before* it is started, so the dragon's soundtrack is
         // heard over silence rather than over the End's own score or a record somebody left
         // running. Held for the whole track and re-claimed on every hand-out, which is what keeps
         // the claim alive across a loop - and ordering it first is what keeps the claim from
         // silencing the very theme that made it.
         BossMusic.takeOver(p, ENDER_THEME_TICKS + 100);
         long seed = p.level().getRandom().nextLong();
         p.connection.send(new net.minecraft.network.protocol.game.ClientboundSoundPacket(
            // Bedrock clients are handed the disc this theme stands in for, because Geyser only
            // translates vanilla ids; the Bedrock half of the pack points that disc at this track.
            // See BedrockMusic. Java clients get the theme itself.
            net.minecraft.core.Holder.direct(BedrockMusic.forListener(p, com.fortuneandfavors.ModSounds.ENDER_DRAGON_THEME)),
            SoundSource.RECORDS,
            p.getX(), p.getY(), p.getZ(), 1.0F, 1.0F, seed
         ));
         com.fortuneandfavors.economy.Advancements.grant(p, "technical/ender_theme");
      } catch (Throwable t) {
         LOGGER.warn("Ender dragon rework: could not hand the theme to {}", p.getName().getString(), t);
      }
   }

   /** Takes the now-playing toast back out of the advancement screen, so it never accumulates. */
   private static void revokeNowPlaying(ServerLevel end) {
      try {
         MinecraftServer server = end.getServer();
         if (server == null) {
            return;
         }
         var holder = server.getAdvancements().get(com.fortuneandfavors.FortuneFavorsMod.id("technical/ender_theme"));
         if (holder == null) {
            return;
         }
         for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            for (String criterion : holder.value().criteria().keySet()) {
               p.getAdvancements().revoke(holder, criterion);
            }
         }
      } catch (Throwable ignored) {
      }
   }

   /** The music stops with the fight - and it stops for players who left the End, too. */
   private static void stopTheme(ServerLevel end) {
      if (!themePlaying) {
         return;
      }
      themePlaying = false;
      themeTicks = 0;
      MinecraftServer server = end == null ? null : end.getServer();
      if (server == null) {
         THEME_SERVED.clear();
         return;
      }
      for (ServerPlayer p : server.getPlayerList().getPlayers()) {
         try {
            p.connection.send(new net.minecraft.network.protocol.game.ClientboundStopSoundPacket(
               com.fortuneandfavors.FortuneFavorsMod.id("ender_dragon_theme"), SoundSource.RECORDS
            ));
         } catch (Throwable ignored) {
         }
         // The fight is over, so its claim on the room's music is over too.
         if (THEME_SERVED.contains(p.getUUID())) {
            BossMusic.release(p);
         }
      }
      THEME_SERVED.clear();
   }

   // ------------------------------------------------------------------ the void

   /**
    * The void is an answer, not a death sentence.
    *
    * <p>Falling off the island used to end the run, which in a fight built out of knockback, pushes,
    * a weightless beat and a body that flies *into* you meant the dragon's own moves could kill
    * players by accident. Now the fall is caught: a rift tears where the player went through, and
    * they come out on top of the island with four fifths of their bar and no fall to pay for it.
    * One fall in ten is gentle and leaves slow falling, which is a gift rather than a rule.
    */
   private static void tickVoidRescue(ServerLevel end) {
      long now = ServerClock.clock(end);
      for (ServerPlayer p : end.getPlayers(pl -> !pl.isSpectator() && pl.isAlive())) {
         if (p.getY() <= VOID_RESCUE_Y) {
            rescueFromTheVoid(end, p);
            continue;
         }
         // Back up on the island, the run of falls is allowed to expire: a player who has stood on
         // rock for two minutes is not still carrying the last dive. Forgiveness is on the clock
         // rather than on the landing, because what is being priced is a *run* of falls.
         if (p.getY() > VOID_RESCUE_Y + 24.0 && VOID_FALLS.containsKey(p.getUUID())) {
            Long last = VOID_FALL_TICK.get(p.getUUID());
            if (last == null || now - last >= VOID_FALLS_FORGIVEN_TICKS) {
               VOID_FALLS.remove(p.getUUID());
               VOID_FALL_TICK.remove(p.getUUID());
            }
         }
      }
   }

   /** The rule one fall runs on, as arithmetic a check can read rather than a pile of particles. */
   public static boolean isVoidRescue(float y) {
      return y <= VOID_RESCUE_Y;
   }

   /**
    * What the void leaves you with after a run of falls.
    *
    * <p>The first fall costs a fifth of the bar and every fall after it costs another fifth, so the
    * fourth is the floor: one heart, which is what makes a player who keeps diving stop diving. No
    * fall is ever lethal - the void is an answer, not a death sentence - it is only expensive.
    * Read by {@link #rescueFromTheVoid} and by the self-test, so "each fall is twenty percent" is
    * a number a build can fail on rather than a promise in a comment.
    */
   public static float voidRescueHealth(float maxHealth, int falls) {
      float max = Math.max(1.0F, maxHealth);
      float fraction = Math.max(0.0F, VOID_RESCUE_HEALTH - VOID_RESCUE_STEP * Math.max(0, falls));
      return Math.max(1.0F, max * fraction);
   }

   /** How many falls a player is currently carrying - read by the self-test, cleared by time. */
   public static int voidFallsFor(UUID player) {
      return VOID_FALLS.getOrDefault(player, 0);
   }

   /** Every fall starts this class's record of the run over. */
   private static void forgetVoidFalls() {
      VOID_FALLS.clear();
      VOID_FALL_TICK.clear();
   }

   /**
    * One fall, caught.
    *
    * <p>Two tears and one arrival: a hole in the world where the player went through it, the floor
    * they are put back on, and the rift they come out of. The health is set rather than healed so a
    * player who fell at one heart comes back at four fifths, which is what makes the rescue worth
    * the loss of the fall rather than a free reset.
    */
   private static void rescueFromTheVoid(ServerLevel end, ServerPlayer p) {
      Vec3 fell = p.position();
      double top = 75.0;
      try {
         top = end.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, 0, 0) + 3.0;
      } catch (Throwable ignored) {
      }
      // The tear, where they went through: something was taken out of the world and it is closing.
      spend(end, ParticleTypes.REVERSE_PORTAL, fell.x, fell.y, fell.z, 240, 2.0, 2.0, 2.0, -0.8);
      spend(end, ParticleTypes.PORTAL, fell.x, fell.y, fell.z, 140, 2.0, 2.0, 2.0, 0.6);
      spend(end, ParticleTypes.SCULK_SOUL, fell.x, fell.y, fell.z, 40, 1.8, 1.8, 1.8, 0.05);
      ring(end, fell, 8.0, 48, ParticleTypes.REVERSE_PORTAL, -1.4, 0.0);
      nova(end, fell, 14.0, ParticleTypes.PORTAL, 0xAA66FF);
      end.playSound(null, fell.x, fell.y, fell.z, SoundEvents.END_PORTAL_SPAWN, SoundSource.AMBIENT, 4.0F, 0.6F);
      end.playSound(null, fell.x, fell.y, fell.z, SoundEvents.ENDERMAN_TELEPORT, SoundSource.AMBIENT, 3.0F, 0.5F);

      try {
         p.teleportTo(0.5, top, 0.5);
      } catch (Throwable ignored) {
      }
      p.setDeltaMovement(Vec3.ZERO);
      p.fallDistance = 0.0F;
      p.hurtMarked = true;
      // The bill climbs with the run: four fifths, three, two, one. Never lower than one heart, so
      // a player who keeps falling gets cheaper to heal and never dies of it.
      int falls = VOID_FALLS.merge(p.getUUID(), 1, Integer::sum) - 1;
      VOID_FALL_TICK.put(p.getUUID(), ServerClock.clock(end));
      p.setHealth(voidRescueHealth(p.getMaxHealth(), falls));
      if (RANDOM.nextInt(VOID_RESCUE_GENTLE_ONE_IN) == 0) {
         p.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 200, 0));
      }

      // And the rift they come out of, standing on the island for a beat after they land.
      Vec3 out = p.position();
      spend(end, ParticleTypes.PORTAL, out.x, out.y, out.z, 200, 2.0, 2.0, 2.0, 0.7);
      spend(end, ParticleTypes.END_ROD, out.x, out.y, out.z, 70, 2.0, 2.0, 2.0, 0.2);
      spend(end, ParticleTypes.WHITE_ASH, out.x, out.y, out.z, 50, 2.0, 2.0, 2.0, 0.1);
      ring(end, out, 6.0, 40, ParticleTypes.PORTAL, 0.8, 0.0);
      nova(end, out, 10.0, ParticleTypes.PORTAL, 0xCCBBFF);
      p.sendOverlayMessage(Component.literal(
         "\u00a75\u00a7lTHE VOID TEARS \u00a78| \u00a7fand spits you back out \u00a78| \u00a7f"
            + Math.round(100.0F * p.getHealth() / Math.max(1.0F, p.getMaxHealth())) + "% \u00a78| \u00a7ffall " + (falls + 1)
      ));
      end.playSound(null, out.x, out.y, out.z, SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 3.0F, 1.2F);
      end.playSound(null, out.x, out.y, out.z, SoundEvents.END_PORTAL_SPAWN, SoundSource.PLAYERS, 2.5F, 1.1F);
   }

   // ------------------------------------------------------------------ the fight

   private static void fightTick(ServerLevel end, EnderDragon dragon, long now) {
      int want = wantedPhase(dragon);
      if (want != phase) {
         onPhaseChange(end, dragon, want);
      }
      // The ambient layer runs under everything - the beam, the moves, the last stand - because
      // the dragon is the scenery of this fight as well as its author.
      aura(end, dragon, rotationPhase());
      // The crystals, then the body. Both are pressure that is on all fight rather than a move
      // that lands: the crystals shoot on their own staggered clock and feed the dragon while they
      // stand, and the space inside six blocks of the body is a hazard in its own right.
      crystalAura(end, dragon);
      dragonAura(end, dragon, rotationPhase());
      // The standing line: the fight's health and phase, refreshed on their own clock from the top
      // of the tick rather than from the branch that waits between moves. This is the one thing on
      // the action bar that must never blink out, so it is sent before any lasting move can claim
      // the bar with its own line - and the line it sends is still overridden, for the few seconds
      // it matters, by anything with something actionable to say.
      if (++bossLineClock % BOSS_LINE_INTERVAL == 0) {
         String standing = bossLine(dragon, rotationPhase());
         for (ServerPlayer p : end.getPlayers(pl -> !pl.isSpectator())) {
            p.sendOverlayMessage(Component.literal(standing));
         }
      }
      // The last stand is pinned at one heart: a crystal is the one thing that could walk the
      // dragon back off the floor it was just put on, and "killable with one hit" has to survive
      // a crystal being alive.
      if (lastStand && dragon.getHealth() > LAST_STAND_HEALTH) {
         dragon.setHealth(LAST_STAND_HEALTH);
      }
      // The name is part of the arrival, not part of the fight: while it is still being written
      // out, the dragon is holding still. Without this the first move of the fight can land on top
      // of a title that is still spelling itself, which reads as two things happening at once
      // rather than as an entrance.
      if (nameTicks > 0) {
         return;
      }

      // The breath lance is a move that <b>lasts</b>: the rotation is held while it burns, and the
      // beam is ticked here rather than in the move loop, because a telegraph-then-impact move
      // cannot express "and now it sweeps for four seconds".
      if (beamTicks > 0) {
         tickBeam(end, dragon);
         return;
      }
      // Two more moves that last rather than land: the slam, which is the body on its way into the
      // floor, and the weightless beat, which is the room on its way up and then down.
      if (slamTicks > 0) {
         tickSlam(end, dragon);
         return;
      }
      if (zeroGTicks > 0) {
         tickZeroG(end, dragon);
         return;
      }
      // And three more states, all of them from the pack whose transitions this fight keeps: the
      // ring that closes in, the chain of teleports that ends in the body, and the ascension the
      // borders are made of. Each holds the rotation while it runs, for the reason the slam does -
      // a lasting move that lets the next one be chosen on top of it is two moves at once.
      if (skyRingTicks > 0) {
         tickSkyRing(end, dragon);
         return;
      }
      if (blinkTicks > 0) {
         tickBlinkChain(end, dragon);
         return;
      }
      if (ascensionTicks > 0) {
         tickAscension(end, dragon);
         return;
      }

      // The lasting moves. Three of them hold the rotation while they run - the cracks, the tear,
      // the nova's wind-up and the perch - because a move that is still happening while the next
      // one is chosen is two moves at once. The rest are pressure that runs underneath whatever
      // else is going on: a rift keeps pulling, a gaze keeps tracking, a mark keeps counting.
      tickEndRifts(end);
      tickGaze(end, dragon);
      tickVoidMarks(end, dragon);
      if (rupture != null) {
         tickRupture(end, dragon);
         return;
      }
      if (tear != null && !tear.done) {
         tickTear(end, dragon);
         return;
      }
      if (novaChargeTicks > 0) {
         tickNovaCharge(end, dragon);
         return;
      }
      // The perch is vanilla's own phase machine rather than one of our moves, so it is noticed
      // rather than thrown: a dragon that has settled on the fountain fights from it until its
      // beats are up. Noticed in every rotation rather than only phase one, because a body that
      // never comes down is a fight with no melee in it.
      // How long the body has been out of reach - the one number the melee design may not leave to
      // chance. Reset the moment it is down or perching, counted otherwise.
      boolean perching = dragonIsPerching(dragon);
      if (meleeWindowOpen(slamGrounded, perchTicks > 0 || perching)) {
         airborneTicks = 0;
      } else {
         airborneTicks++;
      }
      if (perchTicks <= 0 && perching) {
         startPerch(end, dragon);
      } else if (perchTicks <= 0
         && (airborneTicks >= AIRBORNE_LIMIT_TICKS
            || (rotationPhase() >= 2 && now - lastPerchAt >= PERCH_INTERVAL_P23))
         && slamTicks <= 0 && zeroGTicks <= 0 && ascensionTicks <= 0) {
         // The descent's own clock from phase two on, and the watchdog at every phase: the dragon
         // takes itself down to the fountain and holds there for the perch's few beats. Started
         // through the same path as vanilla's landing, so the body flies down on vanilla's own AI
         // rather than being puppeted - and the watchdog is why "melee is possible" is a fact about
         // the fight rather than about the rotation happening to roll the right move.
         lastPerchAt = now;
         airborneTicks = 0;
         startPerch(end, dragon);
         hover(dragon, false);
      }
      if (perchTicks > 0) {
         tickPerch(end, dragon);
         return;
      }
      // The guards are a standing part of the last two phases rather than only a move: leaving
      // either phase without protectors above the room was the thing the fight kept doing, and a
      // summon that is only ever a dice roll is a summon half the room never sees.
      if (rotationPhase() >= 2 && ++protectorClock % PROTECTOR_TOPUP_TICKS == 0) {
         if (protectorsAlive(end) < 2) {
            summonProtectors(end, dragon);
         }
         if (creeperProtectorsAlive(end) < 2) {
            summonCreeperProtectors(end, dragon);
         }
      }

      int rotation = rotationPhase();
      if (--moveCooldown > 0) {
         if (moveCooldown % 10 == 0) {
            for (ServerPlayer p : end.getPlayers(pl -> !pl.isSpectator())) {
               p.sendOverlayMessage(Component.literal(bossLine(dragon, rotation)));
            }
         }
         return;
      }
      startMove(end, dragon, nextMove(rotation));
   }

   /** The rotation a dragon is running: the last stand runs phase one's kit, on purpose. */
   private static int rotationPhase() {
      return lastStand ? 1 : phase;
   }

   /**
    * Which phase the fight should be running right now.
    *
    * <p>Read from the health bar, except when the bar is not describing a fight: during the
    * arrival it is being written by the show, during the last stand it is deliberately pinned at
    * one heart, and during the finale and the death ceremony it is <b>the script's</b> number and
    * not the dragon's. All four of those are cases where the health says "phase three" and the
    * truth is "the phase it is already in" - and the report was exactly that: the fight kept
    * announcing phase three while the dragon was dying, because a bar pinned at one heart reads as
    * a phase-three bar to every piece of arithmetic that only asks about health.
    */
   private static int wantedPhase(EnderDragon dragon) {
      if (riseTicks > 0 || lastStand || finaleTicks > 0 || deathTicks > 0) {
         return phase;
      }
      return ratchetPhase(phase, phaseFor(dragon.getHealth(), dragon.getMaxHealth()));
   }

   /**
    * The ratchet: a phase the fight has reached is never left behind.
    *
    * <p>The phase used to be read straight off the health bar, which made a phase a <b>band</b> -
    * and the crystals feed the dragon a trickle of health for the whole fight, so a phase two that
    * got healed back over two thirds of its bar fell into phase one, threw phase one's rotation and
    * played phase one's banner. That is not a phase transition, it is a phase with a back door: the
    * fight is supposed to escalate and never wind down, so the border is now crossed exactly once
    * and never crossed back.
    *
    * <p>One function, read by the tick and by the self-test, because both halves of it matter: a
    * phase that can be healed out of makes the later rotations optional, and a phase that is
    * <i>skipped</i> would make the middle of the fight unreachable. The second half is handled by
    * the ceiling - see {@link #phaseCeiling}, which is what stops the healing in the first place.
    */
   public static int ratchetPhase(int current, int read) {
      return Math.max(current, read);
   }

   /**
    * How high the health bar may climb while the fight is in a given phase.
    *
    * <p>The other half of the ratchet, and the half that does the work: refusing to <i>read</i> a
    * lower phase is not enough if the bar can still walk back up into it, because the crystals'
    * feed would leave the room fighting a phase-two dragon that reads as a full phase-one bar. So
    * every heal the fight performs - the crystal trickle, the resonance, the gaze's drink - is
    * clamped to the ceiling of the phase the fight is standing in, and the border becomes a real
    * border in both directions.
    */
   static float phaseCeiling(EnderDragon dragon, int standingPhase) {
      float max = dragon.getMaxHealth();
      if (standingPhase <= 1) {
         return max;
      }
      return standingPhase == 2 ? max * PHASE_TWO_AT : max * PHASE_THREE_AT;
   }

   /** Heal the dragon, but never over the ceiling of the phase it is standing in. */
   private static void healWithinPhase(EnderDragon dragon, float amount) {
      if (dragon == null || amount <= 0.0F) {
         return;
      }
      if (lastStand) {
         // The last stand is pinned at one heart on purpose: a heal here would walk it off the
         // floor the finale put it on, which is the one thing the fight must not allow.
         return;
      }
      float ceiling = phaseCeiling(dragon, ratchetPhase(phase, phaseFor(dragon.getHealth(), dragon.getMaxHealth())));
      float missing = ceiling - dragon.getHealth();
      if (missing <= 0.0F) {
         return;
      }
      dragon.heal(Math.min(amount, missing));
   }

   private static void onPhaseChange(ServerLevel end, EnderDragon dragon, int next) {
      // Belt and braces: the caller already refuses to ask during the last stand and the death
      // ceremony, and a phase change that could still fire there would be a title over a fight
      // that has stopped having phases.
      if (lastStand || deathTicks > 0 || finaleTicks > 0) {
         return;
      }
      phase = next;
      moveCooldown = 20;
      // And the border is an ascension rather than an instant: the dragon climbs out of reach for
      // three seconds while the island grows a colonnade of light around it. Started before the
      // flash and the nova below so the border is one moment rather than three things in a row.
      beginAscension(end, dragon);
      Vec3 at = dragon.position();
      // The border is loud on purpose: a phase change that is only a caption is a phase change half
      // the room does not notice, so it flashes, novas and shakes the island as well as saying so.
      // The colour is the phase's own: two is red, three is the corrupted sky.
      int phaseColour = next >= 3 ? 0xC700FF : 0xFF2233;
      flash(end, at, phaseColour);
      nova(end, at, next >= 3 ? 26.0 : 18.0, next >= 3 ? ParticleTypes.SCULK_SOUL : ParticleTypes.SOUL_FIRE_FLAME, phaseColour);
      // The phase is said on the action bar - the one place a player is already looking during a
      // fight - and nowhere else. Phase one says nothing at all, because phase one IS the fight as
      // it always was; the border is what tells the room a phase began, and the colour is what
      // tells it which one. Chat carries none of it: a phase is a state, and a state that scrolls
      // away is a state nobody read.
      String banner = phaseBanner(next);
      if (!banner.isEmpty()) {
         for (ServerPlayer p : end.getPlayers(pl -> !pl.isSpectator())) {
            p.sendOverlayMessage(Component.literal(banner));
         }
      }
      spend(end, ParticleTypes.EXPLOSION_EMITTER, at.x, at.y, at.z, 6, 3.0, 3.0, 3.0, 0.0);
      spend(end, ParticleTypes.REVERSE_PORTAL, at.x, at.y, at.z, 200, 5.0, 4.0, 5.0, -0.4);
      spend(end, ParticleTypes.SCULK_SOUL, at.x, at.y, at.z, 60, 4.0, 3.0, 4.0, 0.1);
      end.playSound(null, at.x, at.y, at.z, SoundEvents.ENDER_DRAGON_GROWL, SoundSource.HOSTILE, 5.0F, 0.5F);
      end.playSound(null, at.x, at.y, at.z, SoundEvents.WARDEN_SONIC_BOOM, SoundSource.HOSTILE, 3.0F, 0.6F);
      // And the border is where the court arrives: the void and the island's dead join the fight
      // the moment it reaches them, rather than waiting for a summon to be rolled. A phase that
      // says "the void joins in" and then spawns nothing has announced a change it did not make.
      if (next >= 2) {
         summonProtectors(end, dragon);
         summonCreeperProtectors(end, dragon);
      }
   }

   /**
    * The phase banner: what the action bar says when a border is crossed.
    *
    * <p>Phase one is deliberately empty. A fight that opens by announcing "phase one" has turned
    * its first movement into a scoreboard; the later borders are the ones worth naming, and they are
    * named in the phase's own colour - phase two red, phase three the corrupted violet of the sky
    * coming apart. Read by {@link #onPhaseChange} and by the self-test, so "phase one is silent" is
    * one function rather than a promise.
    */
   public static String phaseBanner(int phase) {
      return switch (phase) {
         case 2 -> "\u00a7c\u00a7lPHASE II \u00a78- \u00a7cthe dragon starts taking";
         case 3 -> "\u00a75\u00a7lPHASE III \u00a78- \u00a7dthe sky is corrupted";
         default -> "";
      };
   }

   /** Picks the next move: never the same one twice in a row, weighted equally otherwise. */
   private static Move nextMove(int rotationPhase) {
      List<Move> pool = Move.pool(rotationPhase);
      if (pool.isEmpty()) {
         return Move.WING_GUST;
      }
      Move pick = pool.get(RANDOM.nextInt(pool.size()));
      if (pool.size() > 1 && pick == lastMove) {
         pick = pool.get((pool.indexOf(pick) + 1) % pool.size());
      }
      return pick;
   }

   /**
    * A move, chosen and thrown.
    *
    * <p>There is no gap between those two things any more: the move lands on the tick it is
    * picked, and the action bar names it <i>as</i> it lands. The opening frame of the move - the
    * ring, the wall, the line of rifts, whatever the move is shaped like at full size - is drawn
    * by the same code that used to draw it over a second of warning, at the size it is at the end
    * of that warning, so the shape arrives with the damage instead of before it.
    */
   private static void startMove(ServerLevel end, EnderDragon dragon, Move chosen) {
      move = chosen;
      lastMove = chosen;
      moveCooldown = moveGapFor(rotationPhase()) + WIND_UP_TICKS;
      Vec3 at = dragon.position();
      // No action bar line naming the move. Every attack used to announce itself here, and the
      // result was a fight narrated rather than fought - the name arrived with the damage, so it
      // was never a warning, only a caption. What tells a player what happened is the shape, the
      // sound and the wound; the moves that have something *actionable* to say (a count of
      // crystals, a beam to get off) still say it, in their own impact.
      end.playSound(null, at.x, at.y, at.z, chosen.call(), SoundSource.HOSTILE, 4.0F, 0.7F);
      openFrame(end, dragon, chosen);
      impact(end, dragon, chosen);
      move = null;
   }

   /**
    * The move's opening frame, drawn at the instant the move lands.
    *
    * <p>This is the old telegraph, kept because the shapes in it are good ones and because both
    * ends of it are already written: it used to be the last frame of a warning, and it is now the
    * first frame of the blow. The progress argument is therefore always one - the finished shape -
    * and the whole point of the call is that a room which learns a move by watching it still gets
    * the read, without the second of standing around that read used to cost.
    */
   private static void openFrame(ServerLevel end, EnderDragon dragon, Move current) {
      shapeOf(end, dragon, current);
   }

   /**
    * A one-line readout of the fight, on the action bar for the whole fight.
    *
    * <p>The health the room watches is the phase's own: the island's kit reads the End's violet,
    * the void reads red, and the corrupted sky reads the deep purple of the border - so a glance at
    * the action bar says which phase the fight is in without the fight ever naming one. The glyph
    * beside it carries the same three colours, and the last stand keeps its own line because that
    * one is the only number in the whole fight worth shouting.
    *
    * <p>The <b>percentage</b> rides beside the raw numbers, because the phases are equal thirds of
    * the bar ({@link #PHASE_TWO_AT}) and a percentage makes that legible at a glance: 67% is the
    * first border, 33% the second. Read by the standing-line clock in {@link #fightTick} and by the
    * self-test, so "the percentage is on the bar" is one function rather than a promise.
    */
   public static String bossLine(EnderDragon dragon, int rotation) {
      float health = Math.max(0.0F, dragon.getHealth());
      int max = Math.max(1, (int)Math.ceil(dragon.getMaxHealth()));
      int hp = Math.max(0, (int)Math.ceil(health));
      int pct = Math.max(0, Math.min(100, Math.round(100.0F * health / (float)max)));
      if (lastStand) {
         return "\u00a7c\u00a7lONE HEART \u00a78| \u00a7f" + hp + "\u00a77/\u00a7f" + max
            + " \u00a78| \u00a7c\u00a7l" + pct + "%\u00a77 - finish it";
      }
      return switch (rotation) {
         case 2 -> "\u00a7c" + hp + "\u00a78/\u00a7c" + max + " \u00a78| \u00a7c\u00a7l" + pct + "% \u00a7c\u25cf";
         case 3 -> "\u00a75" + hp + "\u00a78/\u00a75" + max + " \u00a78| \u00a75\u00a7l" + pct + "% \u00a7d\u2726";
         default -> "\u00a7d" + hp + "\u00a78/\u00a7d" + max + " \u00a78| \u00a7d\u00a7l" + pct + "% \u00a7d\u25cb";
      };
   }

   // ------------------------------------------------------------------ the damage rule

   /**
    * The dragon's damage gate - the mod's one ordered place to decide what a blow does.
    *
    * <p>Three jobs, in order: the death ceremony owns the body; the last-stand finale owns the
    * body; and a blow that would take the dragon to its floor in phase three does not land at
    * all, it <i>starts the finale</i>. Everything else is vanilla's.
    */
   public static boolean onDragonDamage(Entity entity, DamageSource source, float amount) {
      if (!(entity instanceof EnderDragon dragonHit)) {
         return true;
      }
      if (dragonHit.level() == null || dragonHit.level().isClientSide()) {
         return true;
      }
      if (dragonHit != dragon) {
         // The one after a respawn, or the first one after a restart: adopted on the spot rather
         // than scripted against a stale phase. Nothing about this fight is written down twice.
         adopt(dragonHit);
      }
      if (amount <= 0.0F) {
         return true;
      }
      // Who fought is written down as they fight, so the loot at the end can name the players who
      // actually landed blows on the dragon rather than everyone standing in the dimension.
      if (source.getEntity() instanceof ServerPlayer attacker) {
         DRAGON_ATTACKERS.add(attacker.getUUID());
      }
      try {
         // The arrival is not a fight yet: the bar is being written by the arrival show, and a
         // sword that writes it back down would leave the count-up describing nothing.
         if (riseTicks > 0) {
            return false;
         }
         // Nor is the rematch: the body is being brought back by the island, and a blow that landed
         // inside that would be the room writing the ceremony's ending instead of the script.
         if (respawnTicks > 0) {
            return false;
         }
         // The ascension is not a fight either, for the same reason: it is the moment the dragon
         // leaves one form for the next, and a blow that lands inside it is a form change that the
         // room's damage wrote instead of the script. It is on a clock, so the window is bounded.
         if (ascensionTicks > 0) {
            return false;
         }
         if (deathTicks > 0) {
            return false;
         }
         if (finaleTicks > 0) {
            return false;
         }
         float health = dragonHit.getHealth();
         int current = ratchetPhase(phase, phaseFor(health, dragonHit.getMaxHealth()));

         // Phase two and three are projectile-proof, which is the other half of "melee is the main
         // line of damage". The fight used to be answerable from a hundred blocks away with a bow
         // and a stack of arrows - every move could be read and never answered - and the hide hardens
         // exactly where the fight stops being about the island's own kit. The blade, the trident in
         // hand and the axe are untouched: what is refused is the shot that costs nothing.
         if (refusesProjectiles(current) && source.is(net.minecraft.tags.DamageTypeTags.IS_PROJECTILE)) {
            if (dragonHit.level() instanceof ServerLevel end) {
               deflect(end, dragonHit);
            }
            return false;
         }

         if (lastStand) {
            // Any landed hit at all ends it - that is what being left on one heart means.
            if (reachesDeath(health, amount, true)) {
               beginDeathCeremony(dragonHit, source);
               return false;
            }
            return true;
         }
         // Melee plays for exactly what it always did. The descent, the hold and the watchdog are
         // what make a blade possible; the damage itself is left alone, so a swing is worth the same
         // on the floor as in the air and nothing has to be read off the bar by hand.
         if (reachesLastStand(current, health, amount, lastStand, finaleTicks > 0)) {
            beginFinale(dragonHit);
            return false;
         }
         // And the floor is absolute: before the last stand has been survived, **no** blow kills
         // the dragon outright - however big. A lethal swing from any phase is the last stand
         // being triggered rather than the fight being skipped, so one enormous hit (a mace, a
         // smite, a Strength 255 blade) cannot walk past the one road to a dead dragon. Nothing is
         // made unkillable by this: the finale still ends on one heart and one more landed hit
         // still finishes it, so the order is always "last stand first, death second".
         if (reachesDeath(health, amount, false)) {
            beginFinale(dragonHit);
            return false;
         }
         return true;
      } catch (Throwable t) {
         LOGGER.error("Ender dragon rework: damage gate failed - the blow lands as vanilla's", t);
         return true;
      }
   }

   /**
    * True when a phase turns arrows away - from phase two on.
    *
    * <p>Asked as a function rather than baked into the damage gate so the rule is one thing a check
    * can read: phase one is the island's kit and is played at range, and the two later forms are the
    * fight the room has to walk into. A body that is immune at every phase would be a body nobody
    * could practice against, and one that is immune at none is a fight with a ranged answer to
    * everything in it.
    */
   public static boolean refusesProjectiles(int standingPhase) {
      return standingPhase >= 2;
   }

   /**
    * True while the dragon is in the room's reach - the window the fight gives the body to.
    *
    * <p>Three ways for a dragon to be down and all of them are the same thing to a player looking
    * at it: it is being held on the floor after a landing, it is in the perch's beats, or vanilla
    * has it sitting on the fountain. Asked as a function so the aura, the watchdog and the checks
    * all read one rule instead of three that drift.
    */
   public static boolean meleeWindowOpen(boolean groundedHold, boolean perching) {
      return groundedHold || perching;
   }

   /**
    * What being turned away looks like: sparks off the hide, and the fight saying so.
    *
    * <p>Silence would read as a bug - a bow that stops working with no explosion of light is the
    * player assuming they are lagging, or that the arrow passed through. The line is throttled to
    * once a second so a machine-gun of arrows is one sentence rather than forty, and the wound that
    * the shot would have made is the only thing the phase-change rule ever refuses.
    */
   private static void deflect(ServerLevel end, EnderDragon dragon) {
      Vec3 at = dragon.position();
      spend(end, ParticleTypes.ENCHANTED_HIT, at.x, at.y + 2.0, at.z, 24, 3.0, 2.0, 3.0, 0.1);
      spend(end, ParticleTypes.END_ROD, at.x, at.y + 2.0, at.z, 12, 2.0, 1.5, 2.0, 0.4);
      if (++deflectClock % 20 != 0) {
         return;
      }
      end.playSound(null, at.x, at.y, at.z, SoundEvents.SHIELD_BLOCK, SoundSource.HOSTILE, 3.0F, 0.6F);
      for (ServerPlayer p : playersWithin(end, at, 60.0)) {
         p.sendOverlayMessage(
            Component.literal("\u00a75\u00a7lTHE HIDE TURNS IT \u00a78| \u00a7farrows are useless now - get in close")
         );
      }
   }

   // ------------------------------------------------------------------ the last stand

   /** The blow stops at one heart, and the dragon spends eleven seconds proving it can. */
   private static void beginFinale(EnderDragon dragonHit) {
      lastStand = false;
      finaleTicks = FINALE_TICKS;
      // A lasting move running into the finale would be a body being driven somewhere by a script
      // that is about to be driven somewhere else - and an ascension carried into it would make
      // the one-heart floor untouchable.
      stopLastingMoves();
      dragonHit.setHealth(LAST_STAND_HEALTH);
      move = null;
      if (dragonHit.level() instanceof ServerLevel end) {
         com.fortuneandfavors.net.FfVfx.shape(end, com.fortuneandfavors.net.FfVfx.ULTIMATE, ParticleTypes.END_ROD, dragonHit.position(), new Vec3(0.5, 64.0, 0.5), FINALE_TICKS, 0.0, 0xFF3355);
      }
      com.fortuneandfavors.net.FfVfx.enter();
      try {
      if (dragonHit.level() instanceof ServerLevel end) {
         Vec3 at = dragonHit.position();
         spend(end, ParticleTypes.EXPLOSION_EMITTER, at.x, at.y, at.z, 20, 4.0, 4.0, 4.0, 0.0);
         spend(end, ParticleTypes.REVERSE_PORTAL, at.x, at.y, at.z, 500, 6.0, 5.0, 6.0, -0.5);
         spend(end, ParticleTypes.SCULK_SOUL, at.x, at.y, at.z, 120, 5.0, 4.0, 5.0, 0.15);
         end.playSound(null, at.x, at.y, at.z, SoundEvents.ENDER_DRAGON_GROWL, SoundSource.HOSTILE, 7.0F, 0.4F);
         end.playSound(null, at.x, at.y, at.z, SoundEvents.WITHER_SPAWN, SoundSource.HOSTILE, 4.0F, 0.5F);
      }
      hover(dragonHit, true);
      if (dragonHit.level() instanceof ServerLevel end) {
         flash(end, dragonHit.position(), 0xFF3355);
         nova(end, dragonHit.position(), 26.0, ParticleTypes.SOUL_FIRE_FLAME, 0xFF3355);
      }
      } finally {
         com.fortuneandfavors.net.FfVfx.exit();
      }
      LOGGER.info("Ender dragon rework: the last stand begins");
   }

   /**
    * Eleven seconds of the worst thing the dragon knows how to do.
    *
    * <p>Six beats, each telegraphed by its own particles and sound, and each one actually
    * hurting: three rings walking out across the island, a storm of bolts, a pull to the centre,
    * a lightning sweep, a withering gaze on everybody at once, and then the collapse. The damage
    * is staged rather than continuous so that being <i>somewhere else</i> is the answer, which is
    * the only thing that makes a big scripted attack a fight instead of a cutscene.
    */
   private static void tickFinale(ServerLevel end, EnderDragon dragon, long now) {
      // What can hurt you gets its own cue, so a modded client's telegraph is exactly the
      // server's: the ring every two seconds and the lance at the halfway mark.
      int cueStep = FINALE_TICKS - finaleTicks + 1;
      if (cueStep % 40 == 0) {
         com.fortuneandfavors.net.FfVfx.shape(end, com.fortuneandfavors.net.FfVfx.NOVA, ParticleTypes.SOUL_FIRE_FLAME, new Vec3(0.5, 64.0, 0.5), Vec3.ZERO, 6.0 + cueStep * 0.35, 0.0, 0xFF3355);
      }
      if (cueStep == FINALE_TICKS / 2) {
         for (ServerPlayer p : end.getPlayers(pl -> !pl.isSpectator())) {
            com.fortuneandfavors.net.FfVfx.shape(end, com.fortuneandfavors.net.FfVfx.BEAM, BREATH, dragon.position(), p.position().add(0.0, 1.0, 0.0), 0.0, 0.0, 0xFF3355);
         }
      }
      com.fortuneandfavors.net.FfVfx.enter();
      try {
         finaleTicks--;
         int step = FINALE_TICKS - finaleTicks;
         Vec3 at = dragon.position();
         double progress = (double)step / (double)FINALE_TICKS;

         // The body of the storm: a column of everything the End has, standing on the dragon.
         spend(end, ParticleTypes.PORTAL, at.x, at.y + 6.0, at.z, 120, 8.0, 10.0, 8.0, 0.4);
         spend(end, ParticleTypes.REVERSE_PORTAL, at.x, at.y + 6.0, at.z, 90, 9.0, 10.0, 9.0, -0.35);
         spend(end, ParticleTypes.SCULK_SOUL, at.x, at.y + 4.0, at.z, 30, 6.0, 8.0, 6.0, 0.12);
         spend(end, ParticleTypes.END_ROD, at.x, at.y + 8.0, at.z, 20, 7.0, 12.0, 7.0, 0.3);

         // Beat 1: three rings walking out, one a second. Standing still is the mistake.
         if (step % 40 == 0 && step > 0) {
            double radius = 6.0 + step * 0.35;
            ring(end, new Vec3(0.5, 64.0, 0.5), radius, 64, ParticleTypes.SOUL_FIRE_FLAME, 1.5, 0.05);
            ring(end, new Vec3(0.5, 64.0, 0.5), radius, 64, ParticleTypes.CLOUD, 1.5, 0.06);
            nova(end, new Vec3(0.5, 65.0, 0.5), radius * 0.6, ParticleTypes.SOUL_FIRE_FLAME, 0xFF3355);
            end.playSound(null, at.x, at.y, at.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 5.0F, 0.4F);
            hurtNear(end, new Vec3(0.5, 64.0, 0.5), radius, radius + 3.0, dragon, 13.0F, "the ring");
         }
         // Beat 2: bolts, in volleys, at wherever the players are.
         if (step % 20 == 10) {
            volley(end, dragon, 4, 9.0F);
            for (int i = 0; i < 4; i++) {
               visualLightning(end, at.x + (RANDOM.nextDouble() - 0.5) * 90.0, 70.0, at.z + (RANDOM.nextDouble() - 0.5) * 90.0);
            }
         }
         // Beat 3: everything is dragged to the middle.
         if (step % 30 == 0 && step > 0) {
            for (ServerPlayer p : end.getPlayers(pl -> !pl.isSpectator())) {
               Vec3 pull = new Vec3(0.5 - p.getX(), 0.0, 0.5 - p.getZ());
               if (pull.lengthSqr() > 1.0) {
                  Vec3 unit = pull.normalize().scale(0.55);
                  p.push(unit.x, 0.12, unit.z);
                  p.hurtMarked = true;
               }
            }
            end.playSound(null, 0.5, 64.0, 0.5, SoundEvents.ENDERMAN_SCREAM, SoundSource.HOSTILE, 4.0F, 0.5F);
         }
         // Beat 4: the gaze - everybody, at once, and it drinks.
         if (step == FINALE_TICKS / 2) {
            for (ServerPlayer p : end.getPlayers(pl -> !pl.isSpectator())) {
               beam(end, at, p.position().add(0.0, 1.0, 0.0), BREATH, 0.6);
               if (harnessMode) {
                  continue;
               }
               p.hurtServer(end, end.damageSources().mobAttack(dragon), wound(12.0F));
            }
            end.playSound(null, at.x, at.y, at.z, SoundEvents.WARDEN_SONIC_BOOM, SoundSource.HOSTILE, 5.0F, 0.5F);
         }
         // Beat 5: the sky comes down.
         //
         // No title for it. The last stand stopped naming its own beats - a title that reads the move
         // out loud during the one part of the fight that is a spectacle turns the spectacle into a
         // caption, and the blast on the action bar and the ring of explosions already say it louder.
         if (step == FINALE_TICKS - 40) {
            for (int i = 0; i < 10; i++) {
               spend(end, 
                  ParticleTypes.EXPLOSION_EMITTER,
                  0.5 + (RANDOM.nextDouble() - 0.5) * 70.0, 66.0 + RANDOM.nextDouble() * 10.0, 0.5 + (RANDOM.nextDouble() - 0.5) * 70.0,
                  1, 0.0, 0.0, 0.0, 0.0
               );
            }
         }
         // Beat 6: the last second is nothing but light.
         if (step >= FINALE_TICKS - 20) {
            spend(end, ParticleTypes.END_ROD, 0.5, 70.0, 0.5, 120, 12.0, 12.0, 12.0, 0.6);
            spend(end, ParticleTypes.WHITE_ASH, 0.5, 70.0, 0.5, 120, 14.0, 14.0, 14.0, 0.02);
         }

         if (step % 10 == 0) {
            for (ServerPlayer p : end.getPlayers(pl -> !pl.isSpectator())) {
               p.sendOverlayMessage(Component.literal("\u00a74\u00a7lTHE LAST STAND \u00a78| \u00a7f" + Math.max(0, finaleTicks / 20) + "s"));
            }
         }

         if (finaleTicks <= 0) {
            endFinale(end, dragon);
         }
      } finally {
         com.fortuneandfavors.net.FfVfx.exit();
      }
   }

   /**
    * The finale is survived: the dragon lands, keeps its one heart, and goes back to phase one.
    *
    * <p>The landing is vanilla's own `LANDING` phase, which is the phase that flies to the portal
    * and sits - so the perch is the same perch the vanilla fight uses, not a puppet on a string,
    * and the dragon keeps every behaviour that comes with it. The health is pinned on the way
    * down, because a crystal that heals it here would undo the entire point of the last stand.
    */
   private static void endFinale(ServerLevel end, EnderDragon dragon) {
      lastStand = true;
      dragon.setHealth(LAST_STAND_HEALTH);
      phase = 1;
      moveCooldown = 40;
      hover(dragon, false);
      Vec3 at = dragon.position();
      spend(end, ParticleTypes.EXPLOSION_EMITTER, at.x, at.y, at.z, 24, 5.0, 5.0, 5.0, 0.0);
      spend(end, ParticleTypes.GUST_EMITTER_LARGE, at.x, at.y, at.z, 6, 3.0, 3.0, 3.0, 0.0);
      spend(end, ParticleTypes.GUST, at.x, at.y, at.z, 150, 12.0, 8.0, 12.0, 0.1);
      spend(end, ParticleTypes.SCULK_SOUL, at.x, at.y, at.z, 100, 5.0, 4.0, 5.0, 0.2);
      spend(end, ParticleTypes.CLOUD, at.x, at.y, at.z, 200, 14.0, 6.0, 14.0, 0.25);
      // Not a death sound: the dragon has not died, it has landed. The one thing the room should
      // not hear here is the noise a dragon makes when its health is gone, because there is a
      // whole ceremony between this moment and that one.
      end.playSound(null, at.x, at.y, at.z, SoundEvents.RESPAWN_ANCHOR_DEPLETE.value(), SoundSource.HOSTILE, 3.0F, 0.6F);
      end.playSound(null, at.x, at.y, at.z, SoundEvents.BEACON_DEACTIVATE, SoundSource.HOSTILE, 3.0F, 0.5F);
      flash(end, at, 0xAA66FF);
      nova(end, at, 24.0, ParticleTypes.CLOUD, 0xAA66FF);
      LOGGER.info("Ender dragon rework: the last stand is over - one heart, killable, phase one rotation");
   }

   // ------------------------------------------------------------------ the death ceremony

   /**
    * The killing blow stops, and the dragon takes five seconds to die.
    *
    * <p>Then the real blow lands and vanilla does the rest - which is the whole design: the
    * custom part is the <b>startup</b> (light coming out of a body that has already lost), and the
    * vanilla death animation, the experience, the portal, the egg and the fight's bookkeeping all
    * still run afterwards, once, exactly as they always did.
    */
   private static void beginDeathCeremony(EnderDragon dragonHit, DamageSource source) {
      deathTicks = DEATH_STARTUP_TICKS;
      stopLastingMoves();
      deathSpot = dragonHit.position();
      if (dragonHit.level() instanceof ServerLevel end) {
         com.fortuneandfavors.net.FfVfx.shape(end, com.fortuneandfavors.net.FfVfx.DEATH, ParticleTypes.END_ROD, deathSpot, Vec3.ZERO, DEATH_STARTUP_TICKS, 0.0, 0xCC66FF);
      }
      com.fortuneandfavors.net.FfVfx.enter();
      try {
      if (dragonHit.level() instanceof ServerLevel end) {
         spend(end, ParticleTypes.EXPLOSION_EMITTER, deathSpot.x, deathSpot.y, deathSpot.z, 16, 4.0, 4.0, 4.0, 0.0);
         spend(end, ParticleTypes.END_ROD, deathSpot.x, deathSpot.y, deathSpot.z, 300, 6.0, 6.0, 6.0, 0.4);
         spend(end, ParticleTypes.SCULK_SOUL, deathSpot.x, deathSpot.y, deathSpot.z, 160, 6.0, 5.0, 6.0, 0.2);
         end.playSound(null, deathSpot.x, deathSpot.y, deathSpot.z, SoundEvents.ENDER_DRAGON_HURT, SoundSource.HOSTILE, 6.0F, 0.4F);
         end.playSound(null, deathSpot.x, deathSpot.y, deathSpot.z, SoundEvents.WARDEN_SONIC_BOOM, SoundSource.HOSTILE, 5.0F, 0.4F);
      }
      hover(dragonHit, true);
      if (dragonHit.level() instanceof ServerLevel end) {
         flash(end, dragonHit.position(), 0xFF66FF);
         nova(end, dragonHit.position(), 22.0, ParticleTypes.END_ROD, 0xFF66FF);
      }
      // No title, no action bar and no chat: the death is the one moment in the fight that should
      // need no words at all, and the light coming out of the body already says it in the loudest
      // way the game has. The room is not warned that the dragon is dying - it watches it happen.
      } finally {
         com.fortuneandfavors.net.FfVfx.exit();
      }
      LOGGER.info("Ender dragon rework: the death ceremony begins");
   }

   private static void tickDeathCeremony(ServerLevel end, long now) {
      com.fortuneandfavors.net.FfVfx.enter();
      try {
         deathTicks--;
         int step = DEATH_STARTUP_TICKS - deathTicks;
         Vec3 at = deathSpot;

         // Cracks of light: beams out of the body in every direction, more of them every second.
         int beams = 8 + step / 4;
         for (int i = 0; i < beams; i++) {
            double angle = RANDOM.nextDouble() * Math.PI * 2.0;
            double reach = 4.0 + step * 0.12;
            beam(end, at, at.add(Math.cos(angle) * reach, (RANDOM.nextDouble() - 0.5) * 6.0, Math.sin(angle) * reach), ParticleTypes.END_ROD, 1.0);
         }
         spend(end, ParticleTypes.PORTAL, at.x, at.y, at.z, 60, 4.0, 4.0, 4.0, 0.3);
         spend(end, ParticleTypes.WHITE_ASH, at.x, at.y + 4.0, at.z, 40, 6.0, 5.0, 6.0, 0.02);
         // The island itself starts to come apart - visual only, at the arena's edge.
         if (step % 6 == 0) {
            spend(end, 
               ParticleTypes.EXPLOSION_EMITTER,
               (RANDOM.nextDouble() - 0.5) * 80.0, 62.0 + RANDOM.nextDouble() * 10.0, (RANDOM.nextDouble() - 0.5) * 80.0,
               1, 0.0, 0.0, 0.0, 0.0
            );
            end.playSound(null, at.x, at.y, at.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.AMBIENT, 2.0F, 0.5F + RANDOM.nextFloat() * 0.5F);
         }
         // The whole map answers the body, not just the ground under it: bolts walking the island in
         // widening rings as the light comes out of the corpse, each one a flash a client cannot look
         // away from. Visual-only (nothing is set alight and nobody is damaged) and drawn around the
         // dragon rather than on it, so the death reads from every corner of the arena.
         if (step % 8 == 0) {
            int bolts = 2 + step / 24;
            for (int i = 0; i < bolts; i++) {
               double a = RANDOM.nextDouble() * Math.PI * 2.0;
               double r = 12.0 + RANDOM.nextDouble() * 58.0;
               visualLightning(end, at.x + Math.cos(a) * r, 70.0, at.z + Math.sin(a) * r);
            }
            flash(end, at.add((RANDOM.nextDouble() - 0.5) * 24.0, 6.0, (RANDOM.nextDouble() - 0.5) * 24.0), 0xCC66FF);
         }
         if (step % 15 == 0) {
            end.playSound(null, at.x, at.y, at.z, SoundEvents.ENDER_DRAGON_HURT, SoundSource.HOSTILE, 4.0F, 0.5F);
            end.playSound(null, at.x, at.y, at.z, SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.WEATHER, 4.0F, 0.7F);
         }
         if (deathTicks <= 0) {
            finishDeath(end);
         }
      } finally {
         com.fortuneandfavors.net.FfVfx.exit();
      }
   }

   /**
    * The real death, and the afterglow that goes around it.
    *
    * <p>{@code genericKill} rather than the blow that started this, because the wound has been
    * held open for five seconds and the dragon has to die of <i>something</i> - and because
    * vanilla's own death handling (the DYING phase, the fight's {@code setDragonKilled}, the
    * experience, the portal, the egg) is keyed off an ordinary death and not off a magic number.
    */
   private static void finishDeath(ServerLevel end) {
      EnderDragon body = dragon;
      deathTicks = 0;
      // The loot, for the same reason the achievement is granted here: every line below this is
      // cleanup, and a player who fought the dragon must not be able to walk out with nothing
      // because a later line threw.
      grantEndLoot(end);
      // First, before any teardown that could throw. The achievement is granted here rather than at
      // the end of the method because every line below is cleanup, and a cleanup that throws must
      // not be able to swallow the one thing a player walks out of the fight with - which is
      // exactly the shape of "I killed it and got nothing": a grant sitting after the risky work.
      grantFreeTheEnd(end);
      // The lance's puddles go with the body: pools that outlived the fight that made them would
      // be damage on the ground with nothing to attribute it to.
      clearPools();
      // And so does everything else the fight put in the world: the protectors, the aura, and the
      // music. A boss that leaves its summons, its field and its soundtrack running past its own
      // death is a boss that never actually ends.
      clearProtectors(end);
      clearCreeperProtectors(end);
      // And the lasting moves go with the body: a rift that outlived the fight would be damage
      // with nothing to attribute it to, and a tear that outlived it would leave the island
      // looking eaten.
      clearLastingMoves(end);
      clearCrystalRoster();
      auraFieldTicks = 0;
      stopTheme(end);
      // The gateway: the End's own door, opening where the dragon fell. It is the one piece of
      // vanilla's dragon death that is pure effect rather than bookkeeping, and it is the right
      // note to end a ceremony on - a new way in, rather than a body going out.
      gatewayTicks = GATEWAY_TICKS;
      com.fortuneandfavors.net.FfVfx.shape(end, com.fortuneandfavors.net.FfVfx.GATEWAY, ParticleTypes.END_ROD, deathSpot, Vec3.ZERO, GATEWAY_TICKS, 0.0, 0xAA66FF);
      afterglowTicks = DEATH_AFTERGLOW_TICKS;
      // And the next body this fight spawns is a rematch: the island owes it a ceremony, because
      // vanilla would simply drop a full-health dragon out of the sky with no announcement at all.
      awaitingRematch = true;
      try {
         if (body != null && !body.isRemoved()) {
            body.hurtServer(end, end.damageSources().genericKill(), Float.MAX_VALUE);
            if (!body.isDeadOrDying()) {
               body.setHealth(0.0F);
               body.die(end.damageSources().genericKill());
            }
         }
      } catch (Throwable t) {
         LOGGER.error("Ender dragon rework: the dragon could not be finished - it is left at one heart", t);
      }
      // Free the End was already handed out at the top of this method - see grantFreeTheEnd, which
      // is where the reasoning for a whole-dimension grant lives.
      Vec3 at = deathSpot;
      spend(end, ParticleTypes.EXPLOSION_EMITTER, at.x, at.y, at.z, 30, 6.0, 6.0, 6.0, 0.0);
      spend(end, ParticleTypes.PORTAL, at.x, at.y, at.z, 900, 8.0, 8.0, 8.0, 0.8);
      spend(end, ParticleTypes.REVERSE_PORTAL, at.x, at.y, at.z, 400, 8.0, 8.0, 8.0, -0.4);         spend(end, ParticleTypes.END_ROD, at.x, at.y, at.z, 250, 8.0, 8.0, 8.0, 0.4);
         spend(end, ParticleTypes.WHITE_ASH, at.x, at.y + 8.0, at.z, 200, 10.0, 10.0, 10.0, 0.05);
      // No dragon death roar from us. Vanilla plays the dragon's own death sound as part of the
      // death it is about to have, and a second one stacked on top of it is the sound the ask was
      // about - so the ceremony is silent on that frequency and says the same thing with the
      // gateway instead: the crack of a new door into the End, which is the sound of the fight
      // ending rather than of the animal dying.
      end.playSound(null, at.x, at.y, at.z, SoundEvents.END_GATEWAY_SPAWN, SoundSource.HOSTILE, 5.0F, 0.9F);
      end.playSound(null, at.x, at.y, at.z, SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.WEATHER, 4.0F, 0.5F);
      // The death is the biggest single event in the End, so it gets the full kit: a flash, a nova
      // on the body, and one more above it, layered over vanilla's own animation rather than
      // instead of it.
      flash(end, at, 0xFF66FF);
      nova(end, at, 30.0, ParticleTypes.END_ROD, 0xFF66FF);
      nova(end, at.add(0.0, 6.0, 0.0), 16.0, ParticleTypes.SOUL_FIRE_FLAME, 0xFF3355);
      LOGGER.info("Ender dragon rework: the dragon died for real - vanilla death and loot follow");
   }

   /**
    * Hands "Free the End" ({@code minecraft:end/kill_dragon}) to every player who was in the End
    * when the dragon died.
    *
    * <p>Vanilla grants this to the player the killing blow named, and this fight cannot use that
    * trigger: its killing blow is a scripted {@code genericKill} with no attacker at all, so
    * {@code player_killed_entity} never fires and the achievement is simply never obtained. This
    * hand-grant is therefore the <b>only</b> way it is given out, which is why it is one method
    * with two callers rather than a line inside the ceremony - the reworked death calls it, and so
    * does the ordinary death listener (see {@code ModEvents}), so a dragon that dies without the
    * ceremony - a rematch, a fight with the rework switched off, a {@code /kill}, a body that fell
    * into the void - still ends on the achievement the fight is about.
    *
    * <p>The grant reaches the whole dimension rather than a radius, and it names every figure:
    * a helper on the far tower, a player running crystals at the rim, and the player who landed
    * the last hit are the same fight. Idempotent per player (see {@code Advancements.award}), so
    * both callers firing on one death is a no-op rather than a double toast. Returns how many
    * players this call was the one to complete it for, which is what the log line reports.
    */
   public static int grantFreeTheEnd(ServerLevel end) {
      if (end == null) {
         return 0;
      }
      grantEndLoot(end);
      int granted = 0;
      try {
         for (ServerPlayer helper : end.getPlayers(p -> !p.isSpectator() && p.isAlive())) {
            // The branch first, so the challenge has somewhere to hang and the toast is allowed to
            // fire - see END_ROOT. Idempotent, so a player who earned it by entering normally is
            // untouched.
            com.fortuneandfavors.economy.Advancements.grant(helper, END_ROOT);
            if (com.fortuneandfavors.economy.Advancements.grant(helper, FREE_THE_END)) {
               granted++;
            }
         }
         LOGGER.info(
            "Ender dragon rework: Free the End granted to {} player(s) (of {} present in the End)",
            granted, end.getPlayers(p -> !p.isSpectator()).size()
         );
      } catch (Throwable t) {
         // A failed grant must not take a death tick with it - and it must be loud, because a
         // silent one is indistinguishable from "the fight gave me nothing".
         LOGGER.error("Ender dragon rework: Free the End could not be granted", t);
      }
      return granted;
   }

   /**
    * A dragon died anywhere, by any path: hand out the achievement its fight is about.
    *
    * <p>Called from the server's own death event rather than from the ceremony, so the grant does
    * not depend on the rework being the thing that killed the body. Only dragons in the End are
    * answered (that is where the advancement and the audience both live); every other death is
    * none of this method's business.
    */
   public static void onDragonDeath(net.minecraft.world.entity.Entity body) {
      if (!(body instanceof EnderDragon) || !(body.level() instanceof ServerLevel end)
         || end.dimension() != Level.END) {
         return;
      }
      // The fight this body belonged to is over, so the next body - a vanilla respawn, a rift of
      // ours, a harness dragon - is a new fight even if a call arrives with nothing else having
      // changed. New uuid or not, this is the line that says the old one is spent.
      lastFightUuid = null;
      grantFreeTheEnd(end);
      // And the ending gets its own music. Every path that kills a dragon comes through here - the
      // rework's ceremony, a vanilla kill, a /kill, a body that fell out of the world - so the
      // credits are hung off the death rather than off the script, and a fight whose script was cut
      // short still ends the way the game ends fights. Idempotent, because two callers notice, and
      // silent under the harness, whose dragon dies on a schedule rather than in a fight.
      if (!harnessMode) {
         EnderCreditsMusic.play(end);
      }
   }

   /**
    * Hands the End's loot to every player in the dimension: one Heart of the End and
    * {@link #DRAGON_SCALE_DROP} Dragon Scales each.
    *
    * <p>Given by hand rather than through vanilla's loot table on purpose. A loot table would make
    * this one drop for whoever landed the killing blow, and this fight's killing blow is a
    * scripted {@code genericKill} with no attacker at all - so a table is not merely unfair here,
    * it is a table that would pay nobody. The whole dimension is named rather than a radius, for
    * the same reason the achievement is: the fight is the group, and a player running crystals at
    * the rim fought it just as much as the one under the wings.
    *
    * <p>Idempotent per player per fight (see {@link #END_LOOT_GIVEN}), so the two callers can both
    * fire on one death without doubling anything. Returns how many players this call was the one
    * to pay, which is what the log line reports.
    */
   public static int grantEndLoot(ServerLevel end) {
      if (end == null) {
         return 0;
      }
      int paid = 0;
      try {
         // The fight's own ledger decides who is paid, because killing the dragon is a thing
         // players did rather than a place they were standing. A dragon that fell to something
         // with no attacker at all - a scripted kill, a `/kill` - leaves the ledger empty, and an
         // empty ledger pays the room rather than paying nobody.
         boolean payTheRoom = DRAGON_ATTACKERS.isEmpty();
         for (ServerPlayer helper : end.getPlayers(p -> !p.isSpectator() && p.isAlive())) {
            if (!payTheRoom && !DRAGON_ATTACKERS.contains(helper.getUUID())) {
               continue;
            }
            if (!END_LOOT_GIVEN.add(helper.getUUID())) {
               continue;
            }
            com.fortuneandfavors.util.InventoryHelper.giveOrDrop(helper, com.fortuneandfavors.ModItems.heartOfTheEnd());
            com.fortuneandfavors.util.InventoryHelper.giveOrDrop(helper, com.fortuneandfavors.ModItems.dragonScale().copyWithCount(DRAGON_SCALE_DROP));
            paid++;
         }
         LOGGER.info(
            "Ender dragon rework: the End's loot paid to {} player(s) (of {} present in the End)",
            paid, end.getPlayers(p -> !p.isSpectator()).size()
         );
      } catch (Throwable t) {
         // Loud, for the same reason the achievement's failure is loud: a silent one is
         // indistinguishable from "the fight gave me nothing".
         LOGGER.error("Ender dragon rework: the End's loot could not be handed out", t);
      }
      return paid;
   }

   /** A new body means a new fight: the loot ledger starts empty again. */
   private static void forgetEndLoot() {
      END_LOOT_GIVEN.clear();
      DRAGON_ATTACKERS.clear();
      forgetVoidFalls();
   }

   /**
    * The afterglow: VFX around the vanilla death animation, which is running underneath.
    *
    * <p>Vanilla's dragon takes a while to die on screen - it rises, it turns, it comes apart with
    * light pouring off it - and that is the animation the ask was to keep. This only adds to it,
    * never gates it: nothing here can stop the body from finishing.
    */
   private static void tickAfterglow(ServerLevel end) {
      tickGateway(end);
      if (afterglowTicks <= 0) {
         return;
      }
      afterglowTicks--;
      Vec3 at = deathSpot;
      spend(end, ParticleTypes.END_ROD, at.x, at.y + 6.0, at.z, 30, 8.0, 10.0, 8.0, 0.3);
      spend(end, ParticleTypes.PORTAL, at.x, at.y + 4.0, at.z, 60, 10.0, 10.0, 10.0, 0.4);
      spend(end, ParticleTypes.SCULK_SOUL, at.x, at.y + 2.0, at.z, 20, 8.0, 6.0, 8.0, 0.1);
      if (afterglowTicks % 12 == 0) {
         spend(end, 
            ParticleTypes.EXPLOSION_EMITTER,
            at.x + (RANDOM.nextDouble() - 0.5) * 30.0, at.y + RANDOM.nextDouble() * 14.0, at.z + (RANDOM.nextDouble() - 0.5) * 30.0,
            1, 0.0, 0.0, 0.0, 0.0
         );
         end.playSound(null, at.x, at.y, at.z, SoundEvents.DRAGON_FIREBALL_EXPLODE, SoundSource.AMBIENT, 2.5F, 0.6F);
      }
   }

   // ------------------------------------------------------------------ the moves

   /**
    * The rotation.
    *
    * <p>An enum rather than a list of lambdas so that each move is one name, one telegraph length
    * and one call sound, and so the self-test can count the rotation per phase - "phase three has
    * more moves than phase one" is a thing that should fail a build rather than be rediscovered in
    * a fight.
    */
   public enum Move {
      // Phase one: the island's own kit. Ranged and close, and one that is simply a hole in the
      // sky: the island learned to fight before the void did.
      WING_GUST("Wing Gust", SoundEvents.ENDER_DRAGON_FLAP),
      ENDER_BARRAGE("Ender Barrage", SoundEvents.ENDER_DRAGON_SHOOT),
      CRYSTAL_RESONANCE("Crystal Resonance", SoundEvents.BEACON_ACTIVATE, 1, 2),
      TAIL_SWEEP("Tail Sweep", SoundEvents.ENDER_DRAGON_FLAP),
      SHRIEK("Island Shriek", SoundEvents.WARDEN_SONIC_BOOM),
      END_WINGS("Wing Wall", SoundEvents.ENDER_DRAGON_FLAP),
      ENDER_LASH("Ender Lash", SoundEvents.ENDER_DRAGON_FLAP),
      DRAGON_FIREBALL("Dragon Fireball", SoundEvents.DRAGON_FIREBALL_EXPLODE),
      DRAGON_CHARGE("Dragon Charge", SoundEvents.ENDER_DRAGON_GROWL),
      DRAGON_RAM("Dragon Ram", SoundEvents.ENDER_DRAGON_GROWL),
      SLAM("Slam", SoundEvents.ENDER_DRAGON_GROWL),
      ENDER_FANGS("Fangs of the End", SoundEvents.EVOKER_FANGS_ATTACK, 1),
      ENDER_TEMPEST("Ender Tempest", SoundEvents.ENDER_DRAGON_FLAP, 1),
      // The two the island's kit was missing: a landing the room has to answer with its feet, and a
      // pulse that is read by standing between the rings rather than inside one.
      END_STOMP("End Stomp", SoundEvents.ENDER_DRAGON_FLAP, 1),
      ENDER_PULSE("Ender Pulse", SoundEvents.BEACON_ACTIVATE, 1),
      // Phase two: the void joins in, and so does the court that never got to leave.
      VOID_RIFT("Void Rift", SoundEvents.END_PORTAL_SPAWN, 2, 3),
      SUMMON_SWARM("Swarm of the End", SoundEvents.EVOKER_CAST_SPELL, 2),
      BREATH_NOVA("Breath Nova", SoundEvents.ENDER_DRAGON_SHOOT, 2, 3),
      PHASE_BLINK("Blink", SoundEvents.ENDERMAN_TELEPORT, 2),
      VOID_CHAINS("Void Chains", SoundEvents.ENDERMAN_SCREAM, 2),
      BREATH_LANCE("Breath Lance", SoundEvents.ENDER_DRAGON_SHOOT, 2, 3),
      VOID_COLLAPSE("Void Collapse", SoundEvents.END_PORTAL_SPAWN, 2),
      CRYSTAL_BLOOM("Crystal Bloom", SoundEvents.BEACON_ACTIVATE, 2),
      END_SHOCKWAVE("End Shockwave", SoundEvents.GENERIC_EXPLODE.value(), 2, 3),
      LOST_PROTECTORS("Lost Protectors", SoundEvents.PHANTOM_AMBIENT, 2, 3),
      // The ground half of the island's guard: charged creepers, the fight's only summon answered
      // by distance rather than by a shape on the floor.
      END_ISLAND_PROTECTOR("End Island Protectors", SoundEvents.CREEPER_PRIMED, 2, 3),
      AURA_OF_THE_END("Aura of the End", SoundEvents.WARDEN_HEARTBEAT, 2, 3),
      SOUL_VORTEX("Soul Vortex", SoundEvents.SCULK_SHRIEKER_SHRIEK, 2, 3),
      // The void's own two additions: a rain that comes from above the fog, and the floor answering
      // under whoever is standing on it.
      VOID_HAIL("Void Hail", SoundEvents.ENDER_DRAGON_SHOOT, 2, 3),
      SOUL_ERUPTION("Soul Eruption", SoundEvents.SCULK_SHRIEKER_SHRIEK, 2, 3),
      VOID_GRASP("Void Grasp", SoundEvents.EVOKER_CAST_SPELL, 2, 3),
      END_ROD_SPIKES("End Rod Spikes", SoundEvents.EVOKER_FANGS_ATTACK, 2, 3),
      SHULKER_BULLETS("Shulker Bullets", SoundEvents.SHULKER_SHOOT, 2, 3),
      SKY_RING("Sky Ring", SoundEvents.LIGHTNING_BOLT_THUNDER, 2, 3),
      ASCENDANT_BLINK("Ascendant Blink", SoundEvents.ENDERMAN_TELEPORT, 2, 3),
      VOID_TENDRILS("Void Tendrils", SoundEvents.ENDERMAN_SCREAM, 2),
      // Phase three: the sky, and the island's memory of itself.
      RIFT_STORM("Rift Storm", SoundEvents.END_PORTAL_SPAWN, 3),
      ENDER_RAIN("Ender Rain", SoundEvents.LIGHTNING_BOLT_THUNDER, 3),
      WITHERING_GAZE("Withering Gaze", SoundEvents.WITHER_SHOOT, 3),
      DRAGON_DIVE("Dragon Dive", SoundEvents.ENDER_DRAGON_GROWL, 3),
      VOID_DIVE("Void Dive", SoundEvents.ENDER_DRAGON_GROWL, 3),
      STARFALL("Starfall", SoundEvents.LIGHTNING_BOLT_THUNDER, 3),
      END_STORM("Storm of the End", SoundEvents.LIGHTNING_BOLT_THUNDER, 3),
      END_ECLIPSE("Eclipse of the End", SoundEvents.WITHER_SPAWN, 3),
      ABYSSAL_ROAR("Abyssal Roar", SoundEvents.ENDER_DRAGON_GROWL, 3),
      DRAGON_ROAR("Roar of the End", SoundEvents.ENDER_DRAGON_GROWL, 3),
      METEOR_SHOWER("Meteor Shower", SoundEvents.LIGHTNING_BOLT_THUNDER, 3),
      ZERO_GRAVITY("Zero Gravity", SoundEvents.END_PORTAL_SPAWN, 3),
      SUPERNOVA("Supernova", SoundEvents.WITHER_SPAWN, 3),
      GALAXY_COLLAPSE("Galaxy Collapse", SoundEvents.WITHER_SPAWN, 3),
      // Phase one's own perch: the dragon does not merely sit on the fountain and wait to be hit,
      // it fights from it. Phase one only, because the perch is the fight teaching its first
      // lesson - down at the fountain, the head is the only safe place to stand.
      FOUNTAIN_PERCH("Fountain Perch", SoundEvents.ENDER_DRAGON_FLAP, 1),
      // The mechanics that last. Each of these opens something the arena has to be read for a
      // while rather than for a frame: a rift that stays open and keeps pulling, a gaze that
      // tracks one player, a mark that counts down on a body, cracks that erupt, a section of the
      // island that stops existing for a moment, and the one nova the dragon gets to spend.
      END_RIFT("End Rift", SoundEvents.END_PORTAL_SPAWN, 2, 3),
      ENDER_GAZE("Ender Gaze", SoundEvents.ENDERMAN_SCREAM, 2, 3),
      VOID_MARK("Void Mark", SoundEvents.SCULK_SHRIEKER_SHRIEK, 2, 3),
      END_RUPTURE("End Rupture", SoundEvents.GENERIC_EXPLODE.value(), 2, 3),
      TEMPORAL_TEAR("Temporal Tear", SoundEvents.END_PORTAL_SPAWN, 3),
      END_NOVA("End Nova", SoundEvents.WITHER_SPAWN, 3);

      private final String label;
      private final net.minecraft.sounds.SoundEvent call;
      private final int from;
      private final int to;

      Move(String label, net.minecraft.sounds.SoundEvent call) {
         this(label, call, 1, 3);
      }

      Move(String label, net.minecraft.sounds.SoundEvent call, int from) {
         this(label, call, from, from);
      }

      Move(String label, net.minecraft.sounds.SoundEvent call, int from, int to) {
         this.label = label;
         this.call = call;
         this.from = from;
         this.to = to;
      }

      public String label() {
         return label;
      }

      public net.minecraft.sounds.SoundEvent call() {
         return call;
      }

      /** Every move in a rotation, which is what the phase's "more moves" claim is made of. */
      public static List<Move> pool(int rotationPhase) {
         List<Move> out = new ArrayList<>();
         for (Move m : values()) {
            if (rotationPhase >= m.from && rotationPhase <= m.to) {
               out.add(m);
            }
         }
         return out;
      }
   }

   /** How many moves a rotation has - read by the self-test. */
   public static int movesIn(int rotationPhase) {
      return Move.pool(rotationPhase).size();
   }

   /**
    * How long any move in the fight takes to land once it has been chosen.
    *
    * <p>An attack with no warning at all is not a fight, it is a cutscene with damage in it - and
    * the ask here was the opposite of a warning: telegraphs are gone, so the answer is that the
    * <i>moves themselves</i> are the read. What this returns is therefore zero, on purpose, and it
    * is a function rather than a removed line so that the claim "this dragon does not wind up" is
    * one thing a check can read instead of a thousand words about intent.
    */
   public static int windUpTicks() {
      return WIND_UP_TICKS;
   }

   /** The names of a rotation, for a command or a check. */
   public static List<String> moveNames(int rotationPhase) {
      List<String> out = new ArrayList<>();
      for (Move m : Move.pool(rotationPhase)) {
         out.add(m.label());
      }
      return List.copyOf(out);
   }

   // ------------------------------------------------------------------ the VFX kit

   /**
    * A flash a client cannot look away from: vanilla's own lightning flash, given a colour.
    *
    * <p>The one particle in the game that lights the whole screen instead of adding a shape to it,
    * which is what makes an impact read as an event rather than as a puff. It carries a colour in
    * 26.2 ({@code ColorParticleOption}), so the dragon's flashes are the End's violet and its last
    * stand's are red.
    */
   private static void flash(ServerLevel end, Vec3 at, int colour) {
      try {
         spend(end, ColorParticleOption.create(ParticleTypes.FLASH, colour), at.x, at.y, at.z, 1, 0.0, 0.0, 0.0, 0.0);
      } catch (Throwable ignored) {
      }
   }

   /**
    * A nova: the four layers every big blow in this fight is made of.
    *
    * <p>A core that bursts, a shell that walks out of it, sparks that say what colour the blow is,
    * and wind that reads as a shockwave rather than as smoke. It exists so that "every impact gets
    * more VFX" is one call site instead of eleven copies that drift apart - and so that adding a
    * move means choosing a colour, not rebuilding a light show.
    */
   private static void nova(ServerLevel end, Vec3 at, double radius, ParticleOptions shell, int colour) {
      com.fortuneandfavors.net.FfVfx.shape(end, com.fortuneandfavors.net.FfVfx.NOVA, shell, at, Vec3.ZERO, radius, 0.0, colour);
      com.fortuneandfavors.net.FfVfx.enter();
      try {
         novaVanilla(end, at, radius, shell, colour);
      } finally {
         com.fortuneandfavors.net.FfVfx.exit();
      }
   }

   private static void novaVanilla(ServerLevel end, Vec3 at, double radius, ParticleOptions shell, int colour) {
      spend(end, 
         ParticleTypes.EXPLOSION_EMITTER, at.x, at.y, at.z, 1 + (int)(radius / 6.0), radius * 0.25, radius * 0.25, radius * 0.25, 0.0
      );
      spend(end, ParticleTypes.FIREWORK, at.x, at.y, at.z, 30 + (int)(radius * 2.0), radius * 0.3, radius * 0.3, radius * 0.3, 0.12);
      ring(end, at, radius * 0.5, 40, shell, 1.3, 0.05);
      ring(end, at, radius, 56, ParticleTypes.WHITE_ASH, 1.5, 0.06);
      spend(end, ParticleTypes.GUST, at.x, at.y, at.z, 50 + (int)(radius * 2.0), radius * 0.35, radius * 0.12, radius * 0.35, 0.0);
      spend(end, ParticleTypes.ELECTRIC_SPARK, at.x, at.y, at.z, 30, radius * 0.3, radius * 0.18, radius * 0.3, 0.5);
      spend(end, ParticleTypes.ENCHANT, at.x, at.y + 1.0, at.z, 40, radius * 0.3, 1.2, radius * 0.3, 0.4);
      flash(end, at, colour);
   }

   /**
    * A pillar of light standing on a point - the arrival of something, said vertically.
    *
    * <p>Used for the crystals the bloom raises and for the mouth of the lance. Cheap by shape: a
    * dozen sends up a line, never a volume.
    */
   private static void pillar(ServerLevel end, Vec3 at, double height, ParticleOptions core) {
      com.fortuneandfavors.net.FfVfx.shape(end, com.fortuneandfavors.net.FfVfx.PILLAR, core, at, Vec3.ZERO, height, 0.0, 0xD9B8FF);
      com.fortuneandfavors.net.FfVfx.enter();
      try {
         for (double y = 0.0; y <= height; y += 1.2) {
            spend(end, core, at.x, at.y + y, at.z, 2, 0.3, 0.15, 0.3, 0.02);
         }
      } finally {
         com.fortuneandfavors.net.FfVfx.exit();
      }
   }

   /**
    * Between its moves, the dragon is still an event.
    *
    * <p>This is the ambient layer, ticked every tick for as long as a fight is running, and its
    * shape changes with the phase because the phase is supposed to be visible from a distance:
    * phase one trails light off its wings like an animal shedding sparks, phase two opens veins of
    * the void along its body, and phase three is a body the sky is leaking through - ash falling
    * off it, cracks of end-light, and the occasional flash above the island. At one heart it bleeds
    * light, so the last stand reads even from behind a pillar.
    */
   private static void aura(ServerLevel end, EnderDragon dragon, int rotationPhase) {
      Vec3 at = dragon.position();
      Vec3 back = flatLook(dragon).scale(-3.0);
      boolean low = dragon.getHealth() <= dragon.getMaxHealth() * 0.08F;

      // The wings, always: a trail that says the body is moving even when it is only circling.
      spend(end, ParticleTypes.END_ROD, at.x + back.x, at.y + 2.2, at.z + back.z, rotationPhase >= 3 ? 3 : 1, 1.6, 0.8, 1.6, 0.02);

      if (rotationPhase >= 2) {
         spend(end, ParticleTypes.PORTAL, at.x, at.y + 2.0, at.z, 10, 2.4, 1.6, 2.4, 0.25);
         spend(end, ParticleTypes.REVERSE_PORTAL, at.x, at.y + 2.0, at.z, 6, 2.6, 1.8, 2.6, -0.2);
      }
      if (rotationPhase >= 3) {
         spend(end, ParticleTypes.END_ROD, at.x, at.y + 2.4, at.z, 5, 2.8, 2.0, 2.8, 0.05);
         spend(end, ParticleTypes.PORTAL, at.x, at.y + 1.6, at.z, 6, 3.0, 1.6, 3.0, 0.05);
         if (RANDOM.nextInt(40) == 0) {
            visualLightning(end, at.x + (RANDOM.nextDouble() - 0.5) * 70.0, 78.0, at.z + (RANDOM.nextDouble() - 0.5) * 70.0);
         }
      }
      if (low) {
         // One heart, and it is coming apart: light pouring off a body that has already lost.
         spend(end, ParticleTypes.FIREWORK, at.x, at.y + 2.4, at.z, 10, 2.2, 1.6, 2.2, 0.12);
         spend(end, ParticleTypes.SOUL_FIRE_FLAME, at.x, at.y + 1.4, at.z, 6, 2.0, 1.2, 2.0, 0.05);
      }
      if (lastStand && RANDOM.nextInt(30) == 0) {
         end.playSound(null, at.x, at.y, at.z, SoundEvents.ENDER_DRAGON_AMBIENT, SoundSource.HOSTILE, 2.4F, 0.55F);
      }
   }

   /**
    * The dragon's own presence: this close, the End comes off the body.
    *
    * <p>The passive half of "the aura of the End", and the piece of the fight that is on at every
    * instant rather than on a clock of its own. The collar is drawn <b>every tick</b> and at exactly
    * the radius it hurts, because a hazard with an invisible edge is a hazard players learn from
    * the death screen; the bite lands once a second, phase for phase, so the answer to melee is
    * "hit it and leave" rather than "stand in it and trade".
    */
   private static void dragonAura(ServerLevel end, EnderDragon dragon, int rotationPhase) {
      float bite = dragonAuraDamage(rotationPhase);
      if (bite <= 0.0F) {
         return;
      }
      // The window is not also a hazard. Standing beside a downed dragon is the fight's one paid
      // opening, and paying the room to walk into the field that hurts would cancel itself out - so
      // the field is the price of being near the body <b>in the air</b>, and the beats are the price
      // of being near it on the ground.
      if (meleeWindowOpen(slamGrounded, perchTicks > 0 || dragonIsPerching(dragon))) {
         return;
      }
      Vec3 at = dragon.position();
      // The collar is the End's own light, not soul-fire: this is the island's field, and it should
      // read as the dimension the fight is in. Violet end-light at exactly the reach that hurts,
      // walked every tick, with a second ring of the void coming up through the floor once a second
      // when the bite actually lands.
      ring(end, at, AURA_REACH, 28, ParticleTypes.END_ROD, 0.05, 0.5);
      if (ServerClock.clock(end) % AURA_INTERVAL != 0L) {
         return;
      }
      ring(end, at, AURA_REACH * 0.62, 20, ParticleTypes.REVERSE_PORTAL, -0.3, 0.7);
      spend(end, ParticleTypes.PORTAL, at.x, at.y + 1.6, at.z, 8, AURA_REACH * 0.5, 1.4, AURA_REACH * 0.5, 0.05);
      if (harnessMode) {
         return;
      }
      for (ServerPlayer p : playersWithin(end, at, AURA_REACH)) {
         p.hurtServer(end, end.damageSources().indirectMagic(dragon, dragon), bite);
      }
      end.playSound(null, at.x, at.y, at.z, SoundEvents.WARDEN_HEARTBEAT, SoundSource.HOSTILE, 1.8F, 1.25F);
   }

   /**
    * How hard the dragon's own presence bites, per rotation phase.
    *
    * <p>One function rather than three numbers spelled out where they are used, because "the fight
    * gets more dangerous as it goes" is a claim that should be one comparison in a check.
    */
   public static float dragonAuraDamage(int rotationPhase) {
      int index = Math.max(1, Math.min(AURA_BITE.length, rotationPhase)) - 1;
      return AURA_BITE[index];
   }

   /**
    * The truce the End's own two natives live under: the dragon and the endermen never target each
    * other.
    *
    * <p>The dragon is the island's tyrant and the endermen are its people, and a fight where the
    * boss spends its rotation diving at the locals - or where a hundred of them mob the dragon
    * while the room watches - is neither the fight nor the island. It is enforced at one place,
    * the moment a target is adopted ({@code Mob.setTarget}), rather than by trying to clean up
    * after an AI that has already turned around. Returns true when the pairing is allowed.
    */
   public static boolean endNativeTruce(net.minecraft.world.entity.Entity self, net.minecraft.world.entity.Entity target) {
      if (self == null || target == null) {
         return true;
      }
      return !((self instanceof EnderDragon && target instanceof EnderMan)
         || (self instanceof EnderMan && target instanceof EnderDragon));
   }

   /**
    * The bar the room watches, dressed and smoothed.
    *
    * <p>The dragon's health bar is the fight's own vanilla {@code ServerBossEvent}, which vanilla
    * rewrites every tick straight from the dragon's health - so a blow moves it by whatever the blow
    * was worth, in one frame, and a big hit reads as a bar that teleported. The rework keeps its own
    * displayed fraction and <b>drains</b> toward the real one: damage pulls the bar down over about
    * a second, healing and the arrival's count-up snap it straight to the truth (so the bar never
    * lies about being healthier than it is, and never lags a scripted fill). The name and colour are
    * the phase's, so the border is visible on the bar as well as on the dragon.
    */
   private static void dressBar(ServerLevel end, EnderDragon dragon, int phaseWanted) {
      try {
         if (end.getDragonFight() == null) {
            return;
         }
         net.minecraft.server.level.ServerBossEvent bar =
            ((com.fortuneandfavors.mixin.DragonFightAccessor)end.getDragonFight()).fortuneandfavors$dragonEvent();
         if (bar == null) {
            return;
         }
         float actual = Mth.clamp(dragon.getHealth() / Math.max(1.0F, dragon.getMaxHealth()), 0.0F, 1.0F);
         if (riseTicks > 0 || actual >= barDisplayed) {
            // The bar is rising (an arrival, a crystal's feed): follow it, do not smooth it.
            barDisplayed = actual;
         } else {
            // Draining: close most of the gap every tick, but never overshoot below the real bar.
            barDisplayed = Math.max(actual, barDisplayed - Math.max(0.004F, (barDisplayed - actual) * 0.12F));
         }
         bar.setProgress(barDisplayed);
         // The last stand gets a bar of its own: modded clients draw it shattered.
         if (finaleTicks > 0 || lastStand) {
            bar.setName(Component.literal("\u00a74The Ender Dragon \u00a78| \u00a7c\u00a7lLAST STAND"));
            bar.setColor(net.minecraft.world.BossEvent.BossBarColor.RED);
            return;
         }
         int shown = phaseWanted;
         switch (shown) {
            case 2 -> {
               bar.setName(Component.literal("\u00a7cThe Ender Dragon \u00a78| \u00a7cPHASE II"));
               bar.setColor(net.minecraft.world.BossEvent.BossBarColor.RED);
            }
            case 3 -> {
               bar.setName(Component.literal("\u00a75The Ender Dragon \u00a78| \u00a7d\u2726 CORRUPTED"));
               bar.setColor(net.minecraft.world.BossEvent.BossBarColor.PURPLE);
            }
            default -> {
               bar.setName(Component.literal("\u00a75The Ender Dragon"));
               bar.setColor(net.minecraft.world.BossEvent.BossBarColor.PINK);
            }
         }
      } catch (Throwable ignored) {
      }
   }

   /**
    * The crystals, as live things rather than scenery.
    *
    * <p>Two jobs, both on their own staggered clock: every crystal standing shoots the nearest
    * player with a beam of the End's light, and every crystal standing feeds a trickle of health
    * back into the dragon. The stagger is keyed off the entity's own id so ten crystals do not
    * fire on the same tick - a volley is impressive once and unreadable after that. The feed is
    * refused wherever the fight has scripted the health bar (the finale, the last stand and the
    * death ceremony), because a heal that walks the dragon off a floor a script put it on is the
    * one thing that can make a scripted ending unwinnable.
    */
   private static void crystalAura(ServerLevel end, EnderDragon dragon) {
      List<Entity> standing = crystals(end);
      if (standing.isEmpty()) {
         return;
      }
      long now = ServerClock.clock(end);
      int tier = crystalTierCeiling(rotationPhase(), lastStand, finaleTicks);
      boolean feeding = crystalHealAllowed(lastStand, finaleTicks, deathTicks)
         && Math.floorMod(now, (long)CRYSTAL_HEAL_INTERVAL) == 0L;
      float heal = 0.0F;
      for (Entity crystal : standing) {
         Vec3 at = crystal.position();
         CRYSTAL_SPOTS.put(crystal.getUUID(), at);
         CrystalKind kind = kindOf(crystal, tier);
         double spin = (double)((now + crystal.getId() * 7L) % 60L) / 60.0 * (Math.PI * 2.0);
         // The aura every crystal has: a slowly turning collar of the End's own light, so a crystal
         // reads as a live thing from across the island rather than as a block that happens to be
         // there. Cursed is the one type that adds nothing to it, and that is the point of it.
         for (int i = 0; i < 3; i++) {
            double angle = spin + (Math.PI * 2.0) * i / 3.0;
            spend(end, ParticleTypes.END_ROD, at.x + Math.cos(angle) * 2.2, at.y + 0.4, at.z + Math.sin(angle) * 2.2, 2, 0.1, 0.7, 0.1, 0.02);
         }
         spend(end, ParticleTypes.FIREWORK, at.x, at.y + 0.9, at.z, 3, 0.7, 1.0, 0.7, 0.03);
         pillar(end, at, 4.0, ParticleTypes.END_ROD);
         crystalOrnament(end, crystal, at, kind, now);
         crystalBehaviour(end, crystal, dragon, kind, now);
         if (feeding) {
            heal += CRYSTAL_HEAL_AMOUNT;
         }
         if (!crystalDue(crystal, now, CRYSTAL_FIRE_TICKS)) {
            continue;
         }
         ServerPlayer target = nearest(end, at, CRYSTAL_REACH);
         if (target == null) {
            continue;
         }
         Vec3 aim = target.position().add(0.0, 1.0, 0.0);
         beam(end, at, aim, ParticleTypes.END_ROD, 1.1);
         ring(end, aim, 1.6, 20, ParticleTypes.FIREWORK, 0.3, 0.0);
         spend(end, ParticleTypes.EXPLOSION_EMITTER, aim.x, aim.y, aim.z, 1, 0.0, 0.0, 0.0, 0.0);
         if (!harnessMode) {
            target.hurtServer(end, end.damageSources().indirectMagic(crystal, dragon), CRYSTAL_SHOT_DAMAGE);
         }
         end.playSound(null, at.x, at.y, at.z, SoundEvents.BEACON_POWER_SELECT, SoundSource.HOSTILE, 1.8F, 1.6F);
      }
      if (heal > 0.0F) {
         // Fed into the phase's own ceiling rather than into the bar: the crystals keep the fight
         // topped up, they do not walk it back over a border it has already crossed.
         healWithinPhase(dragon, heal);
      }
   }

   // ------------------------------------------------------------------ the crystal roster

   /**
    * What kind of crystal a crystal is.
    *
    * <p>Borrowed in spirit from Dragonkind Evolved, which gives an End Crystal a type gated on the
    * dragon's difficulty - a caged one, a forcefield that eats arrows, one that burns you for
    * standing near it, one that fires a laser you can jump, one that empties your hands, one that
    * gives you an effect, one that takes your weight away, one that is silently worth fifteen hearts
    * of somebody's health when it breaks, one that shoots at anything in the air, one that moves you
    * to a tower, and the ten-thousand crystal that makes every other crystal untouchable.
    *
    * <p>The ladder here is the fight's own phases, because that is this fight's difficulty: the
    * island's kit at one, the void at two, the sky at three, and the last stand at the top - which is
    * where an untouchable crown belongs, and where the room finally has a second job besides the
    * dragon.
    */
   public enum CrystalKind {
      PLAIN("Plain", 1, false),
      LASER("Laser", 1, false),
      FIERY("Fiery", 1, false),
      CAGED("Caged", 2, false),
      FORCEFIELD("Forcefield", 2, false),
      WITCH("Witch", 2, false),
      ANTI_GRAV("Anti-Grav", 2, false),
      CURSED("Cursed", 3, true),
      LAUNCHER("Launcher", 3, false),
      PORTAL("Portal", 3, true),
      TEN_THOUSAND("10,000", 4, true);

      private final String label;
      private final int tier;
      private final boolean unique;

      CrystalKind(String label, int tier, boolean unique) {
         this.label = label;
         this.tier = tier;
         this.unique = unique;
      }

      public String label() {
         return label;
      }

      /** How far into the fight this type is allowed to be standing. */
      public int tier() {
         return tier;
      }

      /** True for the types only one of which may be standing at a time. */
      public boolean unique() {
         return unique;
      }

      /** Everything that may be rolled at this tier or below. */
      public static List<CrystalKind> availableAt(int tier) {
         List<CrystalKind> out = new ArrayList<>();
         for (CrystalKind kind : values()) {
            if (kind.tier <= tier) {
               out.add(kind);
            }
         }
         return out;
      }
   }

   /**
    * The roster's ladder, read off the fight rather than off a setting.
    *
    * <p>One function, because "the crystals get worse as the fight does" is a claim that should be a
    * comparison in a check rather than a sentence in a comment: the ceiling only ever rises, and the
    * last stand - this fight's hardest tier - is the only place the ten-thousand crystal exists.
    */
   public static int crystalTierCeiling(int rotationPhase, boolean lastStand, int finaleTicks) {
      if (lastStand || finaleTicks > 0) {
         return CRYSTAL_TIER_MAX;
      }
      if (rotationPhase >= 3) {
         return 3;
      }
      if (rotationPhase >= 2) {
         return 2;
      }
      return 1;
   }

   /**
    * What kind this crystal is - rolled once, the first time the fight sees it.
    *
    * <p>Keyed by uuid, and only ever rolled once: a crystal that changed its mind when a chunk
    * reloaded would be a fight that cannot be learned. Types that are unique are skipped while
    * another one of their kind is standing, and the log says which kinds were up, because "what were
    * the crystals" is the first question anybody asks about a fight that went badly.
    */
   private static CrystalKind kindOf(Entity crystal, int tier) {
      CrystalKind known = CRYSTAL_KINDS.get(crystal.getUUID());
      if (known != null) {
         return known;
      }
      List<CrystalKind> pool = new ArrayList<>();
      for (CrystalKind kind : CrystalKind.availableAt(tier)) {
         if (kind.unique() && kindIsStanding(kind)) {
            continue;
         }
         pool.add(kind);
      }
      if (pool.isEmpty()) {
         pool.add(CrystalKind.PLAIN);
      }
      CrystalKind pick = pool.get(RANDOM.nextInt(pool.size()));
      CRYSTAL_KINDS.put(crystal.getUUID(), pick);
      LOGGER.info("Ender dragon rework: a {} crystal is standing", pick.label());
      return pick;
   }

   /** True while a crystal of this kind is standing - the rule behind "only one at a time". */
   private static boolean kindIsStanding(CrystalKind kind) {
      for (CrystalKind other : CRYSTAL_KINDS.values()) {
         if (other == kind) {
            return true;
         }
      }
      return false;
   }

   /**
    * Notices crystals that are gone, and settles what their disappearance owes the room.
    *
    * <p>Run from the tick rather than from the fight, because a crystal can be destroyed during the
    * finale or the death ceremony and the blast it leaves behind is not conditional on the fight
    * still wanting to run. The only type that owes anything is the cursed one, and what it owes is
    * the largest single wound in the room - which is why it looks like nothing at all while it lives.
    */
   private static void tickCrystalRoster(ServerLevel end) {
      if (CRYSTAL_KINDS.isEmpty()) {
         return;
      }
      java.util.Set<UUID> alive = new java.util.HashSet<>();
      for (Entity crystal : crystals(end)) {
         alive.add(crystal.getUUID());
      }
      for (UUID id : List.copyOf(CRYSTAL_KINDS.keySet())) {
         if (alive.contains(id)) {
            continue;
         }
         CrystalKind kind = CRYSTAL_KINDS.remove(id);
         CRYSTAL_CLOCKS.remove(id);
         CRYSTAL_BLOWS.remove(id);
         Vec3 spot = CRYSTAL_SPOTS.remove(id);
         if (kind == CrystalKind.CURSED) {
            cursedBlast(end, spot);
         }
      }
   }

   /** Wipes the roster - the fight is over, and next time the crystals roll again. */
   private static void clearCrystalRoster() {
      CRYSTAL_KINDS.clear();
      CRYSTAL_CLOCKS.clear();
      CRYSTAL_BLOWS.clear();
      CRYSTAL_SPOTS.clear();
   }

   /** The cursed crystal's last act: the nearest body takes it personally. */
   private static void cursedBlast(ServerLevel end, Vec3 spot) {
      Vec3 at = spot == null ? new Vec3(0.5, 70.0, 0.5) : spot;
      spend(end, ParticleTypes.SOUL_FIRE_FLAME, at.x, at.y, at.z, 90, 4.0, 4.0, 4.0, 0.25);
      spend(end, ParticleTypes.SCULK_SOUL, at.x, at.y, at.z, 60, 3.0, 3.0, 3.0, 0.15);
      nova(end, at, 18.0, ParticleTypes.SOUL_FIRE_FLAME, 0x66FFEE);
      end.playSound(null, at.x, at.y, at.z, SoundEvents.ELDER_GUARDIAN_CURSE, SoundSource.HOSTILE, 4.0F, 0.8F);
      if (harnessMode) {
         return;
      }
      ServerPlayer victim = nearest(end, at, CURSED_BLAST_REACH);
      if (victim != null) {
         victim.hurtServer(end, end.damageSources().indirectMagic(victim, victim), CURSED_BLAST);
      }
   }

   /**
    * The End Crystal damage gate.
    *
    * <p>Two types are defined by what they refuse rather than by what they do: the forcefield eats
    * projectiles, and the caged crystal eats every other blow. Both are answers the damage event can
    * give (it is yes or no), which is why "tougher" is written as "refused" - and both say so on the
    * action bar, because a blow that vanishes with no explanation is the same ghost-block bug the
    * rest of the mod learned to fix.
    */
   public static boolean onCrystalDamage(Entity entity, DamageSource source, float amount) {
      if (!(entity instanceof net.minecraft.world.entity.boss.enderdragon.EndCrystal crystal)) {
         return true;
      }
      if (crystal.level() == null || crystal.level().isClientSide()) {
         return true;
      }
      try {
         CrystalKind kind = CRYSTAL_KINDS.get(crystal.getUUID());
         if (kind == null || amount <= 0.0F) {
            return true;
         }
         ServerPlayer hitter = source.getEntity() instanceof ServerPlayer p ? p : null;
         // The crown. While a ten-thousand crystal stands, nothing else in the ring can be hurt -
         // which turns the last stand into the one thing it was missing: a job with an order to it.
         if (kind != CrystalKind.TEN_THOUSAND && crystal.level() instanceof ServerLevel end
            && tenThousandStanding(standingForCrown(end))) {
            refuse(hitter, "\u00a76\u00a7lTHE CROWN HOLDS \u00a78| \u00a7fbreak the golden one first");
            return false;
         }
         if (kind == CrystalKind.FORCEFIELD && source.getDirectEntity() instanceof net.minecraft.world.entity.projectile.Projectile) {
            refusalRing(end(crystal), crystal.position(), 0xFF66FF);
            refuse(hitter, "\u00a7d\u00a7lTHE FORCEFIELD TURNS IT ASIDE");
            return false;
         }
         if (kind == CrystalKind.CAGED) {
            int blows = CRYSTAL_BLOWS.getOrDefault(crystal.getUUID(), 0) + 1;
            CRYSTAL_BLOWS.put(crystal.getUUID(), blows);
            if (cagedRefusesBlow(blows)) {
               refusalRing(end(crystal), crystal.position(), 0xBFBFBF);
               refuse(hitter, "\u00a77\u00a7lTHE CAGE HOLDS \u00a78| \u00a7fhit it again");
               return false;
            }
         }
      } catch (Throwable t) {
         LOGGER.error("Ender dragon rework: crystal damage gate failed - the blow lands", t);
      }
      return true;
   }

   /** The caged crystal's arithmetic: every second blow is the one that lands. */
   public static boolean cagedRefusesBlow(int blowsTaken) {
      return blowsTaken <= 0 || blowsTaken % CAGED_BLOWS_PER_OPENING != 0;
   }

   /** One line on the action bar, for a player whose blow was refused. */
   private static void refuse(ServerPlayer p, String line) {
      if (p == null || harnessMode) {
         return;
      }
      p.sendOverlayMessage(Component.literal(line));
   }

   /** The visible answer to a refusal: the shield itself, not the absence of damage. */
   private static void refusalRing(ServerLevel end, Vec3 at, int colour) {
      if (end == null) {
         return;
      }
      ring(end, at.add(0.0, 0.6, 0.0), 2.6, 28, new DustParticleOptions(colour, 1.6F), 0.6, 0.0);
      spend(end, ColorParticleOption.create(ParticleTypes.FLASH, colour), at.x, at.y + 0.6, at.z, 1, 0.0, 0.0, 0.0, 0.0);
   }

   private static ServerLevel end(Entity crystal) {
      return crystal.level() instanceof ServerLevel level ? level : null;
   }

   /**
    * Which crystals the crown rule counts as standing: the level's own list.
    *
    * <p>Asked of the level rather than of the roster map, because the map can hold a crystal that
    * has since been destroyed, and a crown that died still holding the ring would be a ring nobody
    * could ever open. A level, on the other hand, does not hand back a body that was added outside a
    * tick - and a check runs inside one - so the list can be handed in. In a fight this is always
    * null and the answer is the level's.
    */
   private static List<Entity> standingOverrideForTest = null;

   private static List<Entity> standingForCrown(ServerLevel end) {
      return standingOverrideForTest != null ? standingOverrideForTest : crystals(end);
   }

   /**
    * Which crystals the crown rule sees, for the one check that needs a crown standing.
    *
    * <p>A test seam rather than an API: in a fight the ring the crown holds is whatever the level
    * actually has standing, and the one caller is the crystal gate's own check.
    */
   public static void setStandingCrystalsForTest(List<Entity> standing) {
      standingOverrideForTest = standing == null ? null : List.copyOf(standing);
   }

   /** True while a ten-thousand crystal is standing - the rule the whole ring answers to. */
   public static boolean tenThousandStanding(List<Entity> standing) {
      for (Entity crystal : standing) {
         if (CRYSTAL_KINDS.get(crystal.getUUID()) == CrystalKind.TEN_THOUSAND) {
            return true;
         }
      }
      return false;
   }

   /**
    * A per-crystal clock.
    *
    * <p>Staggered off the entity's own id, because ten crystals sharing one clock fire as one volley:
    * impressive exactly once, and unreadable after that. Every variant's period goes through here, so
    * adding one cannot introduce a synchronised wall of beams by accident.
    */
   private static boolean crystalDue(Entity crystal, long now, int period) {
      return period > 0 && Math.floorMod(now + crystal.getId(), (long)period) == 0L;
   }

   /**
    * The ornament each type wears, drawn so a player can name the crystal from across the island.
    *
    * <p>Item displays are not on the table for a fight this size, so every "hat" is particles: a
    * magenta bubble for the forcefield, a witch's hat for the witch, a golden crown for the
    * ten-thousand, a portal standing behind the portal crystal, a turning eye for the launcher, an
    * iron cage for the caged, two orbiting blaze rods, and nothing at all for the cursed one - its
    * read is that it looks exactly like every other crystal until it breaks.
    */
   private static void crystalOrnament(ServerLevel end, Entity crystal, Vec3 at, CrystalKind kind, long now) {
      switch (kind) {
         case PLAIN -> {
         }
         case LASER -> laserCharge(end, at, (now % (LASER_CHARGE_TICKS + LASER_FIRE_TICKS)) / (double)LASER_CHARGE_TICKS);
         case FIERY -> blazeRods(end, at, now);
         case CAGED -> cage(end, at);
         case FORCEFIELD -> bubble(end, at, 2.4, 0xFF44FF, now);
         case WITCH -> witchHat(end, at, now);
         case ANTI_GRAV -> {
            spend(end, ParticleTypes.CLOUD, at.x, at.y - 0.5, at.z, 14, ANTI_GRAV_REACH * 0.5, 0.4, ANTI_GRAV_REACH * 0.5, 0.02);
            pillar(end, at.add(0.0, -10.0, 0.0), 10.0, ParticleTypes.END_ROD);
         }
         case CURSED -> {
         }
         case LAUNCHER -> launcherEye(end, crystal, at, now);
         case PORTAL -> portalRing(end, at, now);
         case TEN_THOUSAND -> crown(end, at, now);
      }
   }

   /** A sphere of dust on a turning spiral - the forcefield's bubble, and the launcher's charge. */
   private static void bubble(ServerLevel end, Vec3 at, double radius, int colour, long now) {
      double spin = (now % 80L) / 80.0 * (Math.PI * 2.0);
      int points = 40;
      for (int i = 0; i < points; i++) {
         double t = (double)i / points;
         double y = 1.0 - 2.0 * t;
         double r = Math.sqrt(Math.max(0.0, 1.0 - y * y));
         double angle = spin + t * 12.0;
         spend(end, 
            new DustParticleOptions(colour, 1.3F),
            at.x + Math.cos(angle) * r * radius, at.y + 1.0 + y * radius, at.z + Math.sin(angle) * r * radius,
            1, 0.0, 0.0, 0.0, 0.02
         );
      }
   }

   /** A witch's hat floating over the crystal: a brim, and a cone leaning the way hats do. */
   private static void witchHat(ServerLevel end, Vec3 at, long now) {
      ParticleOptions purple = new DustParticleOptions(0x8A2BE2, 1.1F);
      double lean = Math.sin(now * 0.05) * 0.5;
      ringOffset(end, at.add(lean, 3.3, 0.0), 1.7, 18, purple, now * 0.03, 0.0, 0.0);
      for (double h = 0.0; h <= 1.8; h += 0.3) {
         double r = 1.2 * (1.0 - h / 1.8);
         ringOffset(end, at.add(lean * (1.0 + h), 3.4 + h, 0.0), Math.max(0.2, r), 10, purple, now * 0.03, 0.0, 0.0);
      }
   }

   /** A portal standing behind the crystal, drawn as the ring of one. */
   private static void portalRing(ServerLevel end, Vec3 at, long now) {
      Vec3 centre = at.add(0.0, 1.6, 2.2);
      double spin = (now % 100L) / 100.0 * (Math.PI * 2.0);
      for (int i = 0; i < 22; i++) {
         double a = spin + (Math.PI * 2.0) * i / 22.0;
         spend(end, ParticleTypes.PORTAL, centre.x + Math.cos(a) * 1.7, centre.y + Math.sin(a) * 1.7, centre.z, 2, 0.05, 0.05, 0.05, 0.05);
      }
      spend(end, new DustParticleOptions(0x8A2BE2, 1.4F), centre.x, centre.y, centre.z, 8, 1.4, 1.4, 0.1, 0.02);
   }

   /** The crown: a turning band of gold with six points, and every other crystal bows to it. */
   private static void crown(ServerLevel end, Vec3 at, long now) {
      double spin = (now % 120L) / 120.0 * (Math.PI * 2.0);
      ringOffset(end, at.add(0.0, 2.6, 0.0), 1.9, 24, new DustParticleOptions(0xFFD24A, 1.6F), spin, 0.0, 0.0);
      for (int i = 0; i < 6; i++) {
         double a = spin + (Math.PI * 2.0) * i / 6.0;
         spend(end, new DustParticleOptions(0xFFAA00, 1.4F), at.x + Math.cos(a) * 1.9, at.y + 3.2, at.z + Math.sin(a) * 1.9, 2, 0.1, 0.5, 0.1, 0.02);
      }
      spend(end, ParticleTypes.END_ROD, at.x, at.y + 2.6, at.z, 4, 1.8, 0.2, 1.8, 0.05);
   }

   /** Two glowing blaze rods, turning. */
   private static void blazeRods(ServerLevel end, Vec3 at, long now) {
      for (int i = 0; i < 2; i++) {
         double a = now * 0.12 + i * Math.PI;
         double x = at.x + Math.cos(a) * 1.9;
         double z = at.z + Math.sin(a) * 1.9;
         for (double y = 0.6; y <= 1.6; y += 0.5) {
            spend(end, ParticleTypes.FLAME, x, at.y + y, z, 2, 0.06, 0.06, 0.06, 0.01);
         }
         spend(end, ParticleTypes.LAVA, x, at.y + 0.9, z, 1, 0.05, 0.05, 0.05, 0.0);
      }
   }

   /** The cage: bars at the four corners, and a rim on top. */
   private static void cage(ServerLevel end, Vec3 at) {
      ParticleOptions bars = new DustParticleOptions(0x9A9A9A, 1.1F);
      for (int cx = -1; cx <= 1; cx += 2) {
         for (int cz = -1; cz <= 1; cz += 2) {
            for (double y = -0.5; y <= 2.5; y += 0.5) {
               spend(end, bars, at.x + cx * 1.4, at.y + y, at.z + cz * 1.4, 1, 0.0, 0.0, 0.0, 0.0);
            }
         }
      }
      ringOffset(end, at.add(0.0, 2.5, 0.0), 1.7, 16, bars, 0.0, 0.0, 0.0);
   }

   /** The laser's charge: a red line standing up out of the crystal, brighter as it fills. */
   private static void laserCharge(ServerLevel end, Vec3 at, double progress) {
      double filled = Math.min(1.0, Math.max(0.0, progress));
      spend(end, new DustParticleOptions(0xFF0000, 1.4F + (float)filled), at.x, at.y + 1.0, at.z, 4 + (int)(filled * 12), 0.25, 0.5, 0.25, 0.0);
      ringOffset(end, at.add(0.0, 1.1, 0.0), 0.7 + filled * 1.3, 16, new DustParticleOptions(0xFF3355, 1.1F + (float)filled), filled * 6.0, 0.0, 0.0);
   }

   /** The launcher's eye: it watches the nearest body, and it glows when it is armed. */
   private static void launcherEye(ServerLevel end, Entity crystal, Vec3 at, long now) {
      long started = CRYSTAL_CLOCKS.getOrDefault(crystal.getUUID(), now);
      long elapsed = now - started;
      boolean charged = elapsed >= LAUNCHER_CHARGE_TICKS && elapsed < LAUNCHER_LIFE_TICKS;
      ServerPlayer watched = nearest(end, at, LAUNCHER_REACH);
      spend(end, ParticleTypes.END_ROD, at.x, at.y + 3.0, at.z, charged ? 8 : 3, 0.3, 0.3, 0.3, 0.02);
      spend(end, new DustParticleOptions(charged ? 0xFF3355 : 0x33FF99, 1.2F), at.x, at.y + 3.0, at.z, 5, 0.3, 0.3, 0.3, 0.02);
      if (watched != null) {
         beam(end, at.add(0.0, 3.0, 0.0), watched.position().add(0.0, 1.6, 0.0), ParticleTypes.END_ROD, charged ? 0.4 : 0.0);
      }
   }

   /**
    * What a variant does on its own clock.
    *
    * <p>Every control effect is refused while the fight is scripting its own ending, for the reason
    * the crystal feed is: levitation, a teleport to a tower or a hand full of nothing all make a
    * scripted finale unplayable, and a finale that cannot be played is a boss that cannot be killed.
    */
   private static void crystalBehaviour(ServerLevel end, Entity crystal, EnderDragon dragon, CrystalKind kind, long now) {
      Vec3 at = crystal.position();
      boolean controls = crystalControlsAllowed(finaleTicks, deathTicks);
      switch (kind) {
         case PLAIN, CAGED, FORCEFIELD, TEN_THOUSAND -> {
            // Their whole job is what they refuse (see onCrystalDamage) or what they change about
            // the ring (the crown), so there is nothing for them to do on a clock.
         }
         case LASER -> laserFire(end, crystal, dragon, at, now);
         case FIERY -> {
            if (controls && crystalDue(crystal, now, FIERY_PERIOD)) {
               burnNear(end, crystal, dragon, at);
            }
         }
         case WITCH -> {
            if (controls && crystalDue(crystal, now, WITCH_PERIOD)) {
               witchHex(end, crystal, at);
            }
         }
         case ANTI_GRAV -> {
            if (controls && crystalDue(crystal, now, ANTI_GRAV_PERIOD)) {
               antiGrav(end, at);
            }
         }
         case CURSED -> {
            // Nothing at all while it lives. Its read is that it looks like every other crystal.
         }
         case LAUNCHER -> launcher(end, crystal, dragon, at, now);
         case PORTAL -> {
            if (controls && crystalDue(crystal, now, PORTAL_PERIOD)) {
               portalShift(end, crystal, at);
            }
         }
      }
   }

   /** True when the crystal roster may still push players around - not while an ending is running. */
   public static boolean crystalControlsAllowed(int finaleTicks, int deathTicks) {
      return finaleTicks <= 0 && deathTicks <= 0;
   }

   /** The laser: charge, then a red line along a band that a jump clears. */
   private static void laserFire(ServerLevel end, Entity crystal, EnderDragon dragon, Vec3 at, long now) {
      long started = CRYSTAL_CLOCKS.computeIfAbsent(crystal.getUUID(), id -> now);
      int cycle = LASER_CHARGE_TICKS + LASER_FIRE_TICKS;
      int phase = (int)Math.floorMod(now - started, (long)cycle);
      if (phase != LASER_CHARGE_TICKS) {
         return;
      }
      ServerPlayer target = nearest(end, at, CRYSTAL_REACH);
      if (target == null) {
         return;
      }
      Vec3 aim = new Vec3(target.getX(), at.y + 0.6, target.getZ());
      beam(end, at, aim, new DustParticleOptions(0xFF2200, 1.8F), 0.0);
      beam(end, at, aim, ParticleTypes.FLAME, 0.1);
      spend(end, ColorParticleOption.create(ParticleTypes.FLASH, 0xFF2200), at.x, at.y, at.z, 1, 0.0, 0.0, 0.0, 0.0);
      for (ServerPlayer p : playersWithin(end, at, CRYSTAL_REACH)) {
         // A jump clears it: the band is drawn at the crystal's own height, and anything with its
         // feet above that is out of the line.
         if (p.getY() > at.y + LASER_BAND) {
            continue;
         }
         if (distanceToSegment(p.position(), at, aim) > 1.4) {
            continue;
         }
         if (harnessMode) {
            continue;
         }
         p.hurtServer(end, end.damageSources().indirectMagic(crystal, dragon), LASER_DAMAGE);
      }
      end.playSound(null, at.x, at.y, at.z, SoundEvents.BEACON_POWER_SELECT, SoundSource.HOSTILE, 3.0F, 0.8F);
   }

   /** The fiery crystal: everything within a few blocks of it is on fire. */
   private static void burnNear(ServerLevel end, Entity crystal, EnderDragon dragon, Vec3 at) {
      spend(end, ParticleTypes.FLAME, at.x, at.y + 0.8, at.z, 26, 1.6, 1.0, 1.6, 0.04);
      spend(end, ParticleTypes.LAVA, at.x, at.y + 0.4, at.z, 6, 1.0, 0.4, 1.0, 0.0);
      end.playSound(null, at.x, at.y, at.z, SoundEvents.FIRECHARGE_USE, SoundSource.HOSTILE, 2.4F, 1.1F);
      if (harnessMode) {
         return;
      }
      for (ServerPlayer p : playersWithin(end, at, FIERY_REACH)) {
         p.setRemainingFireTicks(60);
         p.hurtServer(end, end.damageSources().inFire(), 3.0F);
      }
   }

   /** The witch: a random effect on a random player who has none. */
   private static void witchHex(ServerLevel end, Entity crystal, Vec3 at) {
      List<ServerPlayer> clear = new ArrayList<>();
      for (ServerPlayer p : end.getPlayers(pl -> !pl.isSpectator() && pl.isAlive())) {
         if (p.getActiveEffects().isEmpty()) {
            clear.add(p);
         }
      }
      if (clear.isEmpty()) {
         return;
      }
      ServerPlayer victim = clear.get(RANDOM.nextInt(clear.size()));
      net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect> effect = WITCH_EFFECTS[RANDOM.nextInt(WITCH_EFFECTS.length)];
      victim.addEffect(new net.minecraft.world.effect.MobEffectInstance(effect, 200, 0));
      spend(end, ParticleTypes.WITCH, victim.getX(), victim.getY() + 1.0, victim.getZ(), 30, 0.6, 1.0, 0.6, 0.05);
      spend(end, ParticleTypes.WITCH, at.x, at.y + 1.0, at.z, 20, 0.6, 0.6, 0.6, 0.05);
      end.playSound(null, victim.getX(), victim.getY(), victim.getZ(), SoundEvents.WITCH_DRINK, SoundSource.HOSTILE, 2.0F, 1.0F);
   }

   /** The anti-grav crystal: the room's weight is taken away near it. */
   private static void antiGrav(ServerLevel end, Vec3 at) {
      ring(end, at.add(0.0, -0.5, 0.0), ANTI_GRAV_REACH * 0.6, 40, ParticleTypes.CLOUD, 0.5, 0.0);
      spend(end, ParticleTypes.END_ROD, at.x, at.y, at.z, 10, 1.4, 3.0, 1.4, 0.4);
      for (ServerPlayer p : playersWithin(end, at, ANTI_GRAV_REACH)) {
         // A lift, not a launch: one level of levitation for a beat and a half, and the crystal's
         // own clock is long enough that being caught twice in a row is not a thing that happens.
         p.addEffect(new net.minecraft.world.effect.MobEffectInstance(
            net.minecraft.world.effect.MobEffects.LEVITATION, ANTI_GRAV_LIFT_TICKS, 0
         ));
      }
   }

   /** The launcher: charge, then hunt anything in the air until it discharges. */
   private static void launcher(ServerLevel end, Entity crystal, EnderDragon dragon, Vec3 at, long now) {
      long started = CRYSTAL_CLOCKS.computeIfAbsent(crystal.getUUID(), id -> now);
      long elapsed = now - started;
      if (elapsed >= LAUNCHER_LIFE_TICKS) {
         // Discharged: the eye goes dark and the clock rolls over, so the crystal is a clock with a
         // window in it rather than a permanent gun.
         CRYSTAL_CLOCKS.put(crystal.getUUID(), now);
         spend(end, ParticleTypes.SMOKE, at.x, at.y + 3.0, at.z, 20, 0.5, 0.5, 0.5, 0.05);
         end.playSound(null, at.x, at.y, at.z, SoundEvents.RESPAWN_ANCHOR_DEPLETE.value(), SoundSource.HOSTILE, 2.0F, 1.4F);
         return;
      }
      if (elapsed < LAUNCHER_CHARGE_TICKS || !crystalDue(crystal, now, LAUNCHER_FIRE_TICKS)) {
         return;
      }
      ServerPlayer target = null;
      double best = LAUNCHER_REACH;
      for (ServerPlayer p : playersWithin(end, at, LAUNCHER_REACH)) {
         if (p.onGround()) {
            continue;
         }
         double distance = p.position().distanceTo(at);
         if (distance < best) {
            best = distance;
            target = p;
         }
      }
      if (target == null) {
         return;
      }
      Vec3 from = at.add(0.0, 3.0, 0.0);
      Vec3 velocity = target.position().add(0.0, 1.0, 0.0).subtract(from).normalize().scale(1.4);
      bolt(end, dragon, from, velocity, 120, LAUNCHER_DAMAGE, 2.2, target.getUUID());
      end.playSound(null, at.x, at.y, at.z, SoundEvents.SHULKER_SHOOT, SoundSource.HOSTILE, 3.0F, 1.2F);
   }

   /** The portal crystal: up to three players are moved to the top of a tower. */
   private static void portalShift(ServerLevel end, Entity crystal, Vec3 at) {
      List<ServerPlayer> players = new ArrayList<>(end.getPlayers(pl -> !pl.isSpectator() && pl.isAlive()));
      if (players.isEmpty() || harnessMode) {
         return;
      }
      spend(end, ParticleTypes.REVERSE_PORTAL, at.x, at.y + 1.6, at.z, 120, 1.6, 1.6, 1.6, -0.2);
      end.playSound(null, at.x, at.y, at.z, SoundEvents.END_PORTAL_SPAWN, SoundSource.HOSTILE, 3.0F, 1.2F);
      java.util.Collections.shuffle(players, new java.util.Random(RANDOM.nextLong()));
      int moved = Math.min(PORTAL_MAX_MOVED, players.size());
      for (int i = 0; i < moved; i++) {
         towerTop(end, players.get(i));
      }
   }

   /** The top of whatever tower stands at a random point on the island's ring. */
   private static void towerTop(ServerLevel end, ServerPlayer p) {
      double angle = RANDOM.nextDouble() * Math.PI * 2.0;
      int x = (int)Math.round(Math.cos(angle) * END_TOWER_RADIUS);
      int z = (int)Math.round(Math.sin(angle) * END_TOWER_RADIUS);
      int y = end.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, x, z) + 1;
      Vec3 from = p.position();
      spend(end, ParticleTypes.REVERSE_PORTAL, from.x, from.y + 1.0, from.z, 60, 0.8, 0.8, 0.8, -0.3);
      p.teleportTo(x + 0.5, y, z + 0.5);
      spend(end, ParticleTypes.PORTAL, x + 0.5, y + 1.0, z + 0.5, 60, 0.8, 0.8, 0.8, 0.3);
      p.resetFallDistance();
      end.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE, 2.0F, 1.0F);
   }

   /** How far a point sits from a line segment, in blocks. */
   private static double distanceToSegment(Vec3 point, Vec3 a, Vec3 b) {
      Vec3 ab = b.subtract(a);
      double lengthSqr = ab.lengthSqr();
      if (lengthSqr < 0.0001) {
         return point.distanceTo(a);
      }
      double t = Math.max(0.0, Math.min(1.0, point.subtract(a).dot(ab) / lengthSqr));
      return point.distanceTo(a.add(ab.scale(t)));
   }

   /**
    * Whether the crystals may feed the dragon right now.
    *
    * <p>Three states answer no, and all three are the same rule: a script owns the health bar -
    * the finale is pinning it, the last stand is holding it at one heart, and the death ceremony
    * is walking it to zero. A crystal that healed through any of those would either make the
    * ending unwinnable or make a death that never happens.
    */
   public static boolean crystalHealAllowed(boolean lastStand, int finaleTicks, int deathTicks) {
      return !lastStand && finaleTicks <= 0 && deathTicks <= 0;
   }

   /**
    * The end gateway opening where the dragon fell.
    *
    * <p>Vanilla's gateway effect, in three beats: a beam drawn out of the ground, growing, a
    * ring of the End's own light thrown off it, and then the crack of a door. Nothing here
    * creates a real gateway block - the fight's own death already handles that, and a second one
    * placed by hand would be a door to nowhere with our name on it.
    */
   private static void tickGateway(ServerLevel end) {
      com.fortuneandfavors.net.FfVfx.enter();
      try {
         if (gatewayTicks <= 0) {
            return;
         }
         gatewayTicks--;
         double progress = 1.0 - (double)gatewayTicks / (double)GATEWAY_TICKS;
         Vec3 at = deathSpot;
         double height = 2.0 + progress * 26.0;

         // The beam: what a gateway does before it exists, drawn from the floor up.
         for (double y = -0.5; y <= height; y += 0.8) {
            spend(end, ParticleTypes.REVERSE_PORTAL, at.x, at.y + y, at.z, 2, 0.35, 0.25, 0.35, -0.3);
         }
         spend(end, ParticleTypes.PORTAL, at.x, at.y + 1.0, at.z, 40, 1.4, 1.2, 1.4, 0.5);
         if (gatewayTicks % 10 == 0) {
            ring(end, at.add(0.0, 0.4, 0.0), 4.0 + progress * 18.0, 48, ParticleTypes.CLOUD, 1.2, 0.02);
            ring(end, at.add(0.0, 0.4, 0.0), 2.0 + progress * 10.0, 36, ParticleTypes.END_ROD, 0.4, 0.04);
            nova(end, at, 6.0 + progress * 14.0, ParticleTypes.PORTAL, 0xAA66FF);
         }
         if (gatewayTicks <= 0) {
            spend(end, ParticleTypes.EXPLOSION_EMITTER, at.x, at.y + 1.0, at.z, 8, 2.0, 2.0, 2.0, 0.0);
            spend(end, ParticleTypes.GUST_EMITTER_LARGE, at.x, at.y + 1.0, at.z, 4, 2.0, 1.0, 2.0, 0.0);
            end.playSound(null, at.x, at.y, at.z, SoundEvents.END_GATEWAY_SPAWN, SoundSource.HOSTILE, 6.0F, 1.0F);
         }
      } finally {
         com.fortuneandfavors.net.FfVfx.exit();
      }
   }

   /**
    * True while the dragon's fight is running - the rule the pocket watch has to respect.
    *
    * <p>Asked by an item rather than by this class, so it is one question with one answer: the
    * rift has opened and a dragon that has not died is standing in the End. A fight that was won
    * long ago is not a fight (the hold is off and vanilla owns the rematch), and a dragon in its
    * death ceremony is finished, so a time-stop there is not skipping anything.
    */
   public static boolean isFightActive() {
      EnderDragon body = dragon;
      // Deliberately not keyed off the rift: the rift flag is cleared the moment a dragon is
      // adopted (so the next one needs its own rite), and a live fight is a live fight whether or
      // not a rift is currently open. The body is the question.
      if (body == null || body.isRemoved() || !body.isAlive()) {
         return false;
      }
      if (deathTicks > 0 || afterglowTicks > 0) {
         return false;
      }
      return true;
   }

   // ------------------------------------------------------------------ the budget

   /**
    * Opens the tick's particle budget.
    *
    * <p>Called before anything in the tick draws, so that every send from the aura to the death
    * ceremony is spent against the same ceiling - which is the only way a ceiling means anything:
    * a limit per call site is a limit that a new call site does not know about.
    */
   private static void beginParticleTick() {
      peakParticles = Math.max(peakParticles, spentParticles);
      spentParticles = 0;
   }

   /**
    * The one place a particle leaves this fight.
    *
    * <p>Every visual in the file goes through here, and every one of them is clamped against
    * {@link #PARTICLE_CEILING}: a request that would take the tick past the ceiling is cut down to
    * what is left, and a request that arrives when the tick is already full is dropped. Nothing is
    * ever refused in a way that breaks a move - the damage, the state and the sounds are all
    * unaffected - which is the point: this is the difference between a fight that is loud and a
    * fight that takes the server down with it, expressed as one subtraction.
    */
   private static void spend(
      ServerLevel level, ParticleOptions particle, double x, double y, double z,
      int count, double dx, double dy, double dz, double speed
   ) {
      if (level == null || particle == null || count <= 0) {
         return;
      }
      int room = PARTICLE_CEILING - spentParticles;
      if (room <= 0) {
         return;
      }
      int send = Math.min(count, room);
      spentParticles += send;
      // Stochastic rounding, so a run of one-particle sends thins out instead of all surviving.
      double want = send * VANILLA_DENSITY;
      int thinned = (int)want + (RANDOM.nextDouble() < want - (int)want ? 1 : 0);
      if (thinned <= 0) {
         return;
      }
      try {
         com.fortuneandfavors.net.FfVfx.particles(level, particle, x, y, z, thinned, dx, dy, dz, speed);
      } catch (Throwable ignored) {
      }
   }

   /** The worst single tick of drawing this server has seen - read by a check. */
   public static int peakParticlesForTest() {
      return peakParticles;
   }

   /** How much of the tick's budget is left, for a command that wants to see it. */
   public static int particleRoomForTest() {
      return Math.max(0, PARTICLE_CEILING - spentParticles);
   }

   /**
    * Asks the fight to spend a number of particles and reports what it was allowed.
    *
    * <p>A check's own probe, and the reason it exists as a method rather than as a comment: a
    * ceiling nobody can observe biting is a ceiling that will be quietly removed by the next person
    * who finds it inconvenient. This opens a fresh tick, spends through the real spender, and
    * reports the difference - so "the clamp works" is something a build can fail.
    */
   public static int spendProbeForTest(ServerLevel end, int requested) {
      beginParticleTick();
      spend(end, ParticleTypes.END_ROD, 0.5, 64.0, 0.5, requested, 0.0, 0.0, 0.0, 0.0);
      int got = spentParticles;
      beginParticleTick();
      return got;
   }

   /** Starts a fresh measurement, so a check reads the fight it just ran and not the server's day. */
   public static void resetParticlePeak() {
      peakParticles = Math.max(peakParticles, spentParticles);
      spentParticles = 0;
   }

   // ------------------------------------------------------------------ the protectors

   /**
    * The Lost Dragon Protectors: the island's dead, raised to hunt the living.
    *
    * <p>Phantoms, because that is what the End already raises when it is angry and because they
    * come from above, which is the one direction a fight in a bowl of floating island does not
    * cover. They are told whose side they are on exactly once, so the dragon is never a target -
    * and the rule is re-asked every tick rather than trusted, because a boss that can be killed by
    * its own summons is a fight that ends by itself.
    */
   private static void summonProtectors(ServerLevel end, EnderDragon dragon) {
      int standing = protectorsAlive(end);
      int raise = Math.max(0, PROTECTOR_MAX - standing);
      if (raise <= 0) {
         // Already at strength: the move is a call, not a crowd, so the room gets the aura and a
         // refresh of their targets instead of a fifth and sixth phantom.
         for (UUID id : List.copyOf(PROTECTORS)) {
            if (end.getEntity(id) instanceof Mob mob) {
               mob.setTarget(freshTarget(end, mob));
            }
         }
         end.playSound(null, dragon.getX(), dragon.getY(), dragon.getZ(), SoundEvents.PHANTOM_AMBIENT, SoundSource.HOSTILE, 5.0F, 0.6F);
         return;
      }

      Vec3 at = dragon.position();
      for (int i = 0; i < raise; i++) {
         try {
            net.minecraft.world.entity.monster.Phantom protector =
               EntityTypes.PHANTOM.create(end, EntitySpawnReason.EVENT);
            if (protector == null) {
               continue;
            }
            double angle = (Math.PI * 2.0) * i / Math.max(1, raise);
            Vec3 spot = at.add(Math.cos(angle) * 16.0, 12.0, Math.sin(angle) * 16.0);
            protector.setPos(spot.x, spot.y, spot.z);
            protector.setPhantomSize(4);
            protector.setPersistenceRequired();
            protector.setCustomName(Component.literal("\u00a75Lost Dragon Protector"));
            protector.setTarget(freshTarget(end, protector));
            end.addFreshEntity(protector);
            PROTECTORS.add(protector.getUUID());
            pillar(end, spot, 10.0, ParticleTypes.SOUL_FIRE_FLAME);
            nova(end, spot, 8.0, ParticleTypes.PORTAL, 0xAA66FF);
         } catch (Throwable t) {
            LOGGER.warn("Ender dragon rework: a protector could not be raised", t);
         }
      }

      end.playSound(null, at.x, at.y, at.z, SoundEvents.PHANTOM_AMBIENT, SoundSource.HOSTILE, 6.0F, 0.5F);
      for (ServerPlayer p : playersWithin(end, at, 80.0)) {
         p.sendOverlayMessage(Component.literal("\u00a75\u00a7lTHE ISLAND REMEMBERS \u00a78| \u00a7f" + protectorsAlive(end) + " protectors above you"));
      }
   }

   /** The only thing a protector is ever allowed to hunt. */
   public static boolean isAnAllowedProtectorTarget(Entity target) {
      return target instanceof ServerPlayer;
   }

   /** Somebody to hunt, or null when the room is empty. Never the dragon, by construction. */
   private static ServerPlayer freshTarget(ServerLevel end, Mob protector) {
      ServerPlayer pick = nearest(end, protector.position(), 120.0);
      if (pick != null && pick.isAlive() && !pick.isSpectator()) {
         return pick;
      }
      return null;
   }

   /**
    * The protectors, every tick: their aura, and the one rule.
    *
    * <p>The aura is the reason they are worth looking at - a phantom in the End with nothing
    * around it is a mob, and a phantom with soul-fire and void pouring off it is the island's
    * memory. The rule is the reason they are safe: anything they are targeting that is not a
    * player is dropped on the spot, which covers the vanilla AI picking a fight with a crystal, a
    * stray mob, or the boss.
    */
   private static void tickProtectors(ServerLevel end) {
      if (PROTECTORS.isEmpty()) {
         return;
      }

      java.util.Iterator<UUID> it = PROTECTORS.iterator();
      while (it.hasNext()) {
         UUID id = it.next();
         Entity body = null;
         try {
            body = end.getEntity(id);
         } catch (Throwable ignored) {
         }
         if (!(body instanceof Mob protector) || protector.isRemoved() || !protector.isAlive()) {
            it.remove();
            continue;
         }

         if (!isAnAllowedProtectorTarget(protector.getTarget())) {
            protector.setTarget(freshTarget(end, protector));
         }

         double x = protector.getX();
         double y = protector.getY();
         double z = protector.getZ();
         spend(end, ParticleTypes.SOUL_FIRE_FLAME, x, y + 0.4, z, 6, 1.4, 0.8, 1.4, 0.02);
         spend(end, ParticleTypes.SOUL, x, y + 0.2, z, 4, 1.6, 1.0, 1.6, 0.01);
         spend(end, ParticleTypes.REVERSE_PORTAL, x, y + 0.6, z, 3, 1.8, 1.0, 1.8, -0.15);
         if (RANDOM.nextInt(60) == 0) {
            end.playSound(null, x, y, z, SoundEvents.PHANTOM_AMBIENT, SoundSource.HOSTILE, 3.0F, 0.6F);
         }
      }
   }

   /** How many protectors are standing. */
   public static int protectorsAlive(ServerLevel end) {
      if (end == null) {
         return 0;
      }
      int alive = 0;
      for (UUID id : PROTECTORS) {
         try {
            if (end.getEntity(id) instanceof Mob mob && mob.isAlive() && !mob.isRemoved()) {
               alive++;
            }
         } catch (Throwable ignored) {
         }
      }
      return alive;
   }

   /** The protectors go when the fight does: they are a move, not a population. */
   private static void clearProtectors(ServerLevel end) {
      for (UUID id : List.copyOf(PROTECTORS)) {
         try {
            if (end != null && end.getEntity(id) instanceof Entity body) {
               spend(end, ParticleTypes.POOF, body.getX(), body.getY() + 0.6, body.getZ(), 20, 0.6, 0.6, 0.6, 0.05);
               body.discard();
            }
         } catch (Throwable ignored) {
         }
      }
      PROTECTORS.clear();
   }

   /**
    * The End Island Protectors: charged creepers raised on the ground, the island's own guard.
    *
    * <p>Where the Lost Protectors come from above, these walk in at ground level, and they are the
    * only summon in the fight that is answered by <b>distance</b> rather than by a place on the
    * floor: a charged creeper has to be killed before it reaches you, and killing it where it
    * stands is the whole read. They are charged because the End's own charge is what raises them -
    * and because a plain creeper in a fight with a three-hundred-health dragon is scenery. Like the
    * phantoms, they only ever hunt players: the rule is asked on the way in and re-asked every
    * tick, because a summon that can reach its own boss is a boss that kills itself.
    */
   private static void summonCreeperProtectors(ServerLevel end, EnderDragon dragon) {
      int standing = creeperProtectorsAlive(end);
      int raise = Math.max(0, CREEPER_PROTECTOR_MAX - standing);
      Vec3 at = dragon.position();
      for (int i = 0; i < raise; i++) {
         try {
            net.minecraft.world.entity.monster.Creeper protector = EntityTypes.CREEPER.create(end, EntitySpawnReason.EVENT);
            if (protector == null) {
               continue;
            }
            double angle = (Math.PI * 2.0) * i / Math.max(1, raise) + RANDOM.nextDouble() * 0.6;
            double radius = 10.0 + RANDOM.nextDouble() * 6.0;
            double px = at.x + Math.cos(angle) * radius;
            double pz = at.z + Math.sin(angle) * radius;
            double gy = end.getHeight(
               net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, (int)Math.floor(px), (int)Math.floor(pz)
            ) + 1;
            protector.setPos(px, gy, pz);
            // Charged through the game's own power flag, so the explosion, the aura and the death
            // are all vanilla's - the mod only turns it on.
            protector
               .getEntityData()
               .set(com.fortuneandfavors.mixin.CreeperAccessor.fortuneandfavors$powered(), true);
            protector.setPersistenceRequired();
            protector.setCustomName(Component.literal("\u00a75End Island Protector"));
            protector.setTarget(freshTarget(end, protector));
            end.addFreshEntity(protector);
            CREEPER_PROTECTORS.add(protector.getUUID());
            Vec3 spot = new Vec3(px, gy, pz);
            pillar(end, spot, 8.0, ParticleTypes.SCULK_SOUL);
            nova(end, spot, 6.0, ParticleTypes.SOUL_FIRE_FLAME, 0x66FF88);
         } catch (Throwable t) {
            LOGGER.warn("Ender dragon rework: an island protector could not be raised", t);
         }
      }

      end.playSound(null, at.x, at.y, at.z, SoundEvents.CREEPER_PRIMED, SoundSource.HOSTILE, 6.0F, 0.5F);
      for (ServerPlayer p : playersWithin(end, at, 80.0)) {
         p.sendOverlayMessage(
            Component.literal("\u00a7a\u00a7lEND ISLAND PROTECTORS \u00a78| \u00a7f" + creeperProtectorsAlive(end) + " charged creepers on the ground")
         );
      }
   }

   /** How many island protectors are standing. */
   public static int creeperProtectorsAlive(ServerLevel end) {
      if (end == null) {
         return 0;
      }
      int alive = 0;
      for (UUID id : CREEPER_PROTECTORS) {
         try {
            if (end.getEntity(id) instanceof net.minecraft.world.entity.monster.Creeper c && c.isAlive() && !c.isRemoved()) {
               alive++;
            }
         } catch (Throwable ignored) {
         }
      }
      return alive;
   }

   /** The island protectors, every tick: the same one rule the phantoms live under. */
   private static void tickCreeperProtectors(ServerLevel end) {
      if (CREEPER_PROTECTORS.isEmpty()) {
         return;
      }
      java.util.Iterator<UUID> it = CREEPER_PROTECTORS.iterator();
      while (it.hasNext()) {
         UUID id = it.next();
         Entity body = null;
         try {
            body = end.getEntity(id);
         } catch (Throwable ignored) {
         }
         if (!(body instanceof net.minecraft.world.entity.monster.Creeper protector) || protector.isRemoved() || !protector.isAlive()) {
            it.remove();
            continue;
         }
         if (!isAnAllowedProtectorTarget(protector.getTarget())) {
            protector.setTarget(freshTarget(end, protector));
         }
         double x = protector.getX();
         double y = protector.getY();
         double z = protector.getZ();
         spend(end, ParticleTypes.SOUL_FIRE_FLAME, x, y + 0.6, z, 5, 0.8, 0.9, 0.8, 0.01);
         spend(end, ParticleTypes.ELECTRIC_SPARK, x, y + 1.0, z, 3, 0.7, 0.8, 0.7, 0.05);
      }
   }

   /** The island protectors go when the fight does: they are a move, not a population. */
   private static void clearCreeperProtectors(ServerLevel end) {
      for (UUID id : List.copyOf(CREEPER_PROTECTORS)) {
         try {
            if (end != null && end.getEntity(id) instanceof Entity body) {
               spend(end, ParticleTypes.POOF, body.getX(), body.getY() + 0.6, body.getZ(), 20, 0.6, 0.6, 0.6, 0.05);
               body.discard();
            }
         } catch (Throwable ignored) {
         }
      }
      CREEPER_PROTECTORS.clear();
   }

   // ------------------------------------------------------------------ the aura of the End

   /**
    * The aura of the End, while it lasts.
    *
    * <p>A place rather than a blow, ticked here instead of in the move loop for the same reason
    * the lance is: it cannot be expressed as "one impact", because it has to be somewhere for
    * eight seconds and the answer to it is leaving. The damage is staged every half-second so that
    * being caught in it once is a wound and standing in it is a decision.
    */
   private static void tickAuraField(ServerLevel end) {
      if (auraFieldTicks <= 0) {
         return;
      }
      auraFieldTicks--;
      EnderDragon body = dragon;
      if (body == null || body.isRemoved() || !body.isAlive()) {
         auraFieldTicks = 0;
         return;
      }

      Vec3 at = body.position();
      double radius = 13.0;
      double spin = (auraFieldTicks % 40) / 40.0 * (Math.PI * 2.0);
      for (int i = 0; i < 3; i++) {
         double angle = spin + (Math.PI * 2.0) * i / 3.0;
         double x = at.x + Math.cos(angle) * radius;
         double z = at.z + Math.sin(angle) * radius;
         spend(end, ParticleTypes.SOUL_FIRE_FLAME, x, at.y + 1.0, z, 4, 0.4, 2.0, 0.4, 0.03);
         spend(end, ParticleTypes.REVERSE_PORTAL, x, at.y + 1.0, z, 4, 0.4, 2.0, 0.4, -0.2);
      }
      ring(end, at, radius, 48, ParticleTypes.SCULK_SOUL, 0.15, 0.0);
      if (auraFieldTicks % 20 == 0) {
         spend(end, ParticleTypes.PORTAL, at.x, at.y + 1.5, at.z, 60, radius * 0.6, 3.0, radius * 0.6, 0.3);
         end.playSound(null, at.x, at.y, at.z, SoundEvents.WARDEN_HEARTBEAT, SoundSource.HOSTILE, 3.0F, 0.6F);
      }

      if (auraFieldTicks % 10 == 0) {
         for (ServerPlayer p : playersWithin(end, at, radius)) {
            if (harnessMode) {
               break;
            }
            p.hurtServer(end, end.damageSources().magic(), 4.0F);
            p.sendOverlayMessage(Component.literal("\u00a75\u00a7lTHE AURA IS DRINKING \u00a78| \u00a7fget out"));
         }
      }
   }

   /** Ticks left in the aura of the End, for a command or a check. */
   public static int auraFieldTicksLeft() {
      return Math.max(0, auraFieldTicks);
   }

   // ------------------------------------------------------------------ the shapes

   /**
    * The shape of a move.
    *
    * <p>Every move is a geometry before it is a number, and this is that geometry drawn at full
    * size: a ring, a wall, a line of rifts, a wedge. It used to be the last frame of a wind-up -
    * it was drawn growing over most of a second and then the move landed. Now it is drawn at the
    * same instant the move lands, which is why {@code progress} here is a constant one: there is no
    * build-up left to animate, and the shape is a read rather than a warning.
    *
    * <p>Kept as its own method rather than folded into {@link #impact} for the same reason it was
    * written as one: the shape is what a player answers, the numbers are what the server applies,
    * and they are allowed to be different code - the self-test and the source audit both read this
    * switch to prove that every move in a rotation has a shape and an impact behind it.
    */
   private static void shapeOf(ServerLevel end, EnderDragon dragon, Move current) {
      Vec3 at = dragon.position();
      double progress = 1.0;
      switch (current) {
         case WING_GUST -> ring(end, at, 4.0 + progress * 16.0, 40, ParticleTypes.CLOUD, 1.0, 0.02);
         case TAIL_SWEEP -> ring(end, at, 3.0 + progress * 10.0, 28, ParticleTypes.SWEEP_ATTACK, 0.5, 0.0);
         case SHRIEK -> ring(end, at, 2.0 + progress * 22.0, 48, ParticleTypes.SCULK_SOUL, 1.0, 0.05);
         // The four the drills added: a landing on the floor, a pulse that walks out of the body,
         // and the void's two - a rain from above the fog and the ground answering underneath.
         case END_STOMP -> {
            double ground = groundBelow(end, at);
            ring(end, new Vec3(at.x, ground + 0.4, at.z), 3.0 + progress * 12.0, 40, ParticleTypes.GUST, 0.0, 0.06);
         }
         case ENDER_PULSE -> {
            ring(end, at, 3.0 + progress * 12.0, 40, ParticleTypes.END_ROD, 1.0, 0.05);
            ring(end, at, 8.0 + progress * 20.0, 48, ParticleTypes.REVERSE_PORTAL, 1.0, 0.06);
         }
         case VOID_HAIL -> {
            for (ServerPlayer p : end.getPlayers(pl -> !pl.isSpectator())) {
               beam(end, new Vec3(p.getX(), 90.0, p.getZ()), p.position().add(0.0, 1.0, 0.0), ParticleTypes.REVERSE_PORTAL, 1.4);
            }
         }
         case SOUL_ERUPTION -> {
            for (ServerPlayer p : end.getPlayers(pl -> !pl.isSpectator())) {
               ring(
                  end,
                  new Vec3(p.getX(), groundBelow(end, p.position()) + 0.3, p.getZ()),
                  2.0 + progress * 4.0, 32, ParticleTypes.SOUL_FIRE_FLAME, 1.2, 0.0
               );
            }
         }
         case ENDER_BARRAGE -> {
            for (ServerPlayer p : end.getPlayers(pl -> !pl.isSpectator())) {
               beam(end, at, p.position().add(0.0, 1.0, 0.0), BREATH, 1.2);
            }
         }
         case CRYSTAL_RESONANCE -> beamToCrystals(end, at);
         case VOID_RIFT, RIFT_STORM -> {
            for (ServerPlayer p : end.getPlayers(pl -> !pl.isSpectator())) {
               rift(end, p.position(), 0.4 + progress * 0.8);
            }
         }
         case SUMMON_SWARM -> {
            for (int i = 0; i < 6; i++) {
               double angle = (Math.PI * 2.0) * i / 6.0;
               rift(end, new Vec3(Math.cos(angle) * 22.0, 64.0, Math.sin(angle) * 22.0), 0.5);
            }
         }
         case BREATH_NOVA -> ring(end, at, 3.0 + progress * 18.0, 44, BREATH, 1.0, 0.06);
         case PHASE_BLINK -> spend(end, ParticleTypes.REVERSE_PORTAL, at.x, at.y, at.z, 40, 3.0, 3.0, 3.0, -0.4);
         case SKY_RING -> spend(end, ParticleTypes.END_ROD, at.x, at.y, at.z, 90, SKY_RING_SPAN, 1.0, SKY_RING_SPAN, 0.08);
         case ASCENDANT_BLINK -> spend(end, ParticleTypes.REVERSE_PORTAL, at.x, at.y, at.z, 70, 3.0, 2.5, 3.0, -0.5);
         case ENDER_RAIN -> {
            for (ServerPlayer p : end.getPlayers(pl -> !pl.isSpectator())) {
               for (int i = 0; i < 4; i++) {
                  beam(
                     end,
                     new Vec3(p.getX() + (RANDOM.nextDouble() - 0.5) * 8.0, 90.0, p.getZ() + (RANDOM.nextDouble() - 0.5) * 8.0),
                     p.position(),
                     ParticleTypes.END_ROD,
                     1.6
                  );
               }
            }
         }
         case WITHERING_GAZE -> {
            for (ServerPlayer p : end.getPlayers(pl -> !pl.isSpectator())) {
               beam(end, at, p.position().add(0.0, 1.0, 0.0), ParticleTypes.SOUL_FIRE_FLAME, 0.0);
            }
         }
         case DRAGON_ROAR -> {
            spend(end, ParticleTypes.GUST_EMITTER_LARGE, at.x, at.y, at.z, 3, 4.0, 4.0, 4.0, 0.0);
            ring(end, at, 2.0 + progress * 30.0, 64, ParticleTypes.WHITE_ASH, 1.0, 0.04);
         }
         case END_WINGS -> {
            // The two walls of wind it is about to throw, drawn where they will land: to the left
            // and right of the body, so the answer is to be in front of it or behind it.
            Vec3 look = flatLook(dragon);
            Vec3 side = new Vec3(-look.z, 0.0, look.x);
            for (int s = -1; s <= 1; s += 2) {
               for (double d = 2.5; d <= 14.0; d += 1.1) {
                  for (double y = 0.4; y <= 3.2; y += 0.9) {
                     spend(end, 
                        ParticleTypes.CLOUD, at.x + side.x * d * s, at.y + y, at.z + side.z * d * s, 1, 0.1, 0.1, 0.1, 0.06
                     );
                  }
               }
            }
         }
         case VOID_CHAINS -> {
            for (ServerPlayer p : end.getPlayers(pl -> !pl.isSpectator())) {
               beam(end, at, p.position().add(0.0, 1.0, 0.0), ParticleTypes.SCULK_SOUL, 0.9);
               ring(end, p.position(), 1.4 + progress * 1.8, 20, ParticleTypes.SOUL, 0.4, 0.0);
            }
         }
         case BREATH_LANCE -> {
            // The mouth, charging. The line is where it is aimed right now, and the beam that
            // follows sweeps off it, so a player who reads only the line still has somewhere to go.
            Vec3 mouth = mouthOf(dragon);
            ServerPlayer aimed = nearest(end, at, 90.0);
            Vec3 aimAt = aimed == null ? at.add(flatLook(dragon).scale(20.0)) : aimed.position().add(0.0, 1.0, 0.0);
            spend(end, BREATH, mouth.x, mouth.y, mouth.z, 12, 0.35, 0.35, 0.35, 0.0);
            for (double d = 1.0; d <= 24.0; d += 0.7) {
               Vec3 point = mouth.lerp(aimAt, d / 24.0);
               spend(end, BREATH, point.x, point.y, point.z, 1, 0.05, 0.05, 0.05, 0.0);
            }
            if (aimed != null) {
               ring(end, aimed.position(), 1.6 + progress * 2.0, 24, ParticleTypes.SOUL_FIRE_FLAME, 0.3, 0.02);
            }
         }
         case DRAGON_DIVE -> {
            Vec3 mouth = mouthOf(dragon);
            ServerPlayer aimed = nearest(end, at, 90.0);
            if (aimed != null) {
               for (double d = 1.0; d <= 20.0; d += 0.8) {
                  Vec3 point = mouth.lerp(aimed.position().add(0.0, 1.0, 0.0), d / 20.0);
                  spend(end, ParticleTypes.SOUL_FIRE_FLAME, point.x, point.y, point.z, 2, 0.2, 0.2, 0.2, 0.02);
               }
               ring(end, aimed.position(), 2.0 + progress * 3.0, 28, ParticleTypes.SMOKE, 0.4, 0.02);
            }
         }
         case END_STORM -> {
            for (int i = 0; i < 8; i++) {
               double angle = (Math.PI * 2.0) * i / 8.0;
               Vec3 spot = new Vec3(Math.cos(angle) * 26.0, 64.0, Math.sin(angle) * 26.0);
               rift(end, spot, 0.5 + progress * 0.8);
               visualLightning(end, spot.x, 72.0, spot.z);
            }
            end.playSound(null, at.x, at.y, at.z, SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.WEATHER, 2.4F, 0.6F);
         }
         case ENDER_LASH -> {
            // The arc, drawn close to the body: a full turn of sweep particles at its own reach,
            // because this is the move answered by stepping sideways or behind it.
            for (double a = 0.0; a < 360.0; a += 8.0) {
               double rad = Math.toRadians(a);
               for (double r = 3.5; r <= 11.0; r += 1.4) {
                  spend(end, 
                     ParticleTypes.SWEEP_ATTACK, at.x + Math.cos(rad) * r, at.y + 0.8 + r * 0.06, at.z + Math.sin(rad) * r,
                     1, 0.05, 0.05, 0.05, 0.0
                  );
               }
            }
            ring(end, at, 11.0 * (0.5 + progress * 0.5), 48, ParticleTypes.END_ROD, 1.2, 0.04);
            ring(end, at, 8.0, 36, ParticleTypes.GUST, 0.0, 0.1);
            nova(end, at, 14.0, ParticleTypes.SWEEP_ATTACK, 0xAA66FF);
         }
         case VOID_COLLAPSE -> {
            // Rings falling inward, one after another: the whole shape of the move is a hole
            // getting smaller, and the answer is to be outside the last one.
            for (int i = 0; i < 4; i++) {
               double r = 26.0 - progress * 18.0 - i * 4.0;
               if (r <= 2.0) {
                  continue;
               }
               ring(end, new Vec3(0.5, 65.0, 0.5), r, 40, ParticleTypes.REVERSE_PORTAL, -0.4 - i * 0.1, 0.02);
            }
            pillar(end, new Vec3(0.5, 65.0, 0.5), 10.0, ParticleTypes.PORTAL);
         }
         case CRYSTAL_BLOOM -> {
            // Four points in the air, each one lit where a bloom will stand: the room can see
            // exactly where it is about to have to look up.
            for (int i = 0; i < 4; i++) {
               double angle = (Math.PI * 2.0) * i / 4.0;
               Vec3 spot = new Vec3(Math.cos(angle) * 22.0, 78.0, Math.sin(angle) * 22.0);
               pillar(end, spot, 20.0, ParticleTypes.END_ROD);
               ring(end, spot, 1.5 + progress * 2.5, 20, ParticleTypes.FIREWORK, 0.3, 0.0);
            }
         }
         case END_ECLIPSE -> {
            // The light going out of the room from the edges in, drawn as a shrinking ring of
            // nothing at head height, while the dragon's own light is still the only one left.
            ring(end, at, 40.0 - progress * 14.0, 64, ParticleTypes.SMOKE, 2.0, 0.05);
            ring(end, at, 34.0 - progress * 12.0, 56, ParticleTypes.LARGE_SMOKE, 1.6, 0.03);
            for (double h = 0.0; h <= 8.0; h += 1.0) {
               spend(end, ParticleTypes.SCULK_SOUL, at.x, at.y + h, at.z, 3, 1.2, 0.4, 1.2, 0.02);
            }
         }
         case ABYSSAL_ROAR -> {
            // A shout, drawn as pressure: rings standing in the air at chest height, walking out
            // and up, which is where the answer (leave the ground, or leave the ring) comes from.
            for (int i = 0; i < 3; i++) {
               ring(end, at.add(0.0, i * 1.6, 0.0), 4.0 + progress * (18.0 + i * 6.0), 56, ParticleTypes.SONIC_BOOM, 1.4, 0.02);
            }
            spend(end, ParticleTypes.ELECTRIC_SPARK, at.x, at.y + 2.0, at.z, 40, 6.0, 3.0, 6.0, 0.4);
         }
         case DRAGON_CHARGE -> {
            // A line rather than a ring: the one shape in the island's kit whose answer is being
            // *off the corridor* rather than far from the body. Drawn from the same two helpers the
            // impact uses, so the corridor that is promised and the corridor that lands are one line.
            Vec3 aim = chargeAim(end, dragon);
            Vec3 unit = chargeUnit(at, aim);
            Vec3 from = chargeStart(aim, unit);
            beam(end, from, aim, ParticleTypes.GUST, 0.0);
            beam(end, from, aim, ParticleTypes.END_ROD, 0.9);
            for (double d = 0.0; d <= CHARGE_LENGTH; d += 2.0) {
               Vec3 point = from.add(unit.scale(d));
               spend(end, ParticleTypes.CLOUD, point.x, point.y + 1.0, point.z, 6, 1.7, 0.9, 1.7, 0.06);
            }
            nova(end, aim, 18.0, ParticleTypes.CLOUD, 0xAA66FF);
         }
         case DRAGON_RAM -> {
            // A straight line into a body: the corridor is drawn from the dragon to the player it
            // picked, and the only answer is not being at the end of it. The difference between this
            // and the dive is what the line ends in - the dive lands, the ram goes *through*.
            ServerPlayer target = nearest(end, at, 80.0);
            Vec3 aim = target == null ? at.add(flatLook(dragon).scale(20.0)) : target.position().add(0.0, 1.0, 0.0);
            for (double d = 0.0; d <= 1.0; d += 0.05) {
               Vec3 point = at.lerp(aim, d);
               spend(end, ParticleTypes.REVERSE_PORTAL, point.x, point.y, point.z, 8, 1.1, 1.1, 1.1, 0.05);
               spend(end, ParticleTypes.CLOUD, point.x, point.y, point.z, 5, 1.3, 1.3, 1.3, 0.02);
            }
            ring(end, aim, 6.0, 40, ParticleTypes.SOUL_FIRE_FLAME, 1.2, 0.0);
            nova(end, aim, 12.0, ParticleTypes.PORTAL, 0xCC66FF);
         }
         case ENDER_FANGS -> {
            // A wedge of teeth out of the mouth. Nothing else in the kit is cone-shaped, and the
            // answer to a cone is the one place it cannot reach: behind the dragon.
            Vec3 mouth = mouthOf(dragon);
            Vec3 look = flatLook(dragon);
            Vec3 side = new Vec3(-look.z, 0.0, look.x);
            for (double d = 2.5; d <= 17.0; d += 1.3) {
               double half = d * 0.55;
               double step = Math.max(1.0, half / 3.0);
               for (double off = -half; off <= half; off += step) {
                  Vec3 point = mouth.add(look.scale(d)).add(side.scale(off));
                  spend(end, ParticleTypes.SCULK_SOUL, point.x, point.y, point.z, 2, 0.2, 0.8, 0.2, 0.02);
                  spend(end, ParticleTypes.SOUL_FIRE_FLAME, point.x, point.y - 0.6, point.z, 1, 0.1, 0.3, 0.1, 0.0);
               }
            }
            ring(end, mouth, 4.0 + progress * 3.0, 24, ParticleTypes.SCULK_SOUL, 0.6, 0.0);
         }
         case SOUL_VORTEX -> {
            // The spin, drawn as what it is: two rings per band turning opposite ways. The eye is
            // left empty on purpose - the safe place inside a spin is the middle of it.
            double spin = (ServerClock.clock(end) % 40L) / 40.0 * (Math.PI * 2.0);
            for (int i = 0; i < 4; i++) {
               double radius = 4.5 + i * 2.6;
               double y = at.y + 0.5 + i * 0.6;
               ringOffset(end, new Vec3(at.x, y, at.z), radius, 44, ParticleTypes.SOUL, spin + i * 0.4, 0.5, 0.0);
               ringOffset(end, new Vec3(at.x, y + 0.7, at.z), radius, 32, ParticleTypes.REVERSE_PORTAL, -spin - i * 0.4, -0.3, 0.0);
            }
            pillar(end, at, 8.0, ParticleTypes.PORTAL);
         }
         case VOID_GRASP -> {
            // A thread from above onto every body, with a collar at the bottom. The shape says what
            // the move is before its first tick of damage: something has hold of you, and the answer
            // is where you are standing when it lets go.
            for (ServerPlayer p : end.getPlayers(pl -> !pl.isSpectator())) {
               Vec3 feet = p.position();
               beam(end, feet.add(0.0, 24.0, 0.0), feet, ParticleTypes.SOUL_FIRE_FLAME, 0.6);
               ring(end, feet, 1.4 + progress * 1.2, 20, ParticleTypes.SCULK_SOUL, 0.4, 0.4);
            }
            spend(end, ParticleTypes.SOUL, at.x, at.y + 3.0, at.z, 40, 6.0, 3.0, 6.0, 0.2);
         }
         case METEOR_SHOWER -> {
            // The sky entering: streaks drawn in the air across the whole island, ending where they
            // are about to land. The read is "nowhere in particular is safe", which is the whole
            // difference between this and every aimed move in the kit.
            for (int i = 0; i < 22; i++) {
               double x = (RANDOM.nextDouble() - 0.5) * 88.0;
               double z = (RANDOM.nextDouble() - 0.5) * 88.0;
               beam(end, new Vec3(x, 104.0, z), new Vec3(x, 78.0, z), ParticleTypes.SOUL_FIRE_FLAME, 1.4);
               spend(end, ParticleTypes.END_ROD, x, 76.0, z, 2, 0.4, 0.6, 0.4, 0.2);
            }
            ringOffset(end, new Vec3(0.5, 78.0, 0.5), 44.0, 64, ParticleTypes.SMOKE, 0.0, 1.6, 0.0);
         }
         case SLAM -> {
            // The body's own line to the floor, drawn where it is about to arrive: a column of dust
            // under the dragon and the ring it lands in. The whole move is the dragon coming down,
            // so the shape is the only warning there can be - and the answer is the middle ring,
            // not the far side of the island.
            double ground = groundBelow(end, at);
            for (double y = at.y; y > ground; y -= 1.2) {
               spend(end, ParticleTypes.CLOUD, at.x, y, at.z, 3, 0.8, 0.3, 0.8, 0.06);
            }
            ring(end, new Vec3(at.x, ground + 0.4, at.z), SLAM_RADIUS * 0.5, 44, ParticleTypes.CLOUD, 1.0, 0.0);
            ring(end, new Vec3(at.x, ground + 0.4, at.z), SLAM_RADIUS, 56, ParticleTypes.WHITE_ASH, 1.4, 0.05);
         }
         case END_ROD_SPIKES -> {
            // Capped rods standing out of the ground under every body - the pack's spikes, and the
            // shape is a field of them rather than a single line, so the answer is a place to stand
            // rather than a direction to run.
            for (ServerPlayer p : end.getPlayers(pl -> !pl.isSpectator())) {
               spikeField(end, p.position(), 0.0);
            }
            if (dragonIsPerching(dragon)) {
               spikeField(end, at, 0.0);
            }
         }
         case SHULKER_BULLETS -> {
            // Twelve points around the body, and twelve lines out of them: the ring is the tell, and
            // what leaves it is vanilla's own bullet rather than a copy of one.
            for (int i = 0; i < SHULKER_BULLETS; i++) {
               double angle = (Math.PI * 2.0) * i / SHULKER_BULLETS;
               Vec3 spot = at.add(Math.cos(angle) * 6.0, 1.0, Math.sin(angle) * 6.0);
               spend(end, ParticleTypes.END_ROD, spot.x, spot.y, spot.z, 6, 0.3, 0.3, 0.3, 0.02);
               spend(end, new DustParticleOptions(0xCC66FF, 1.4F), spot.x, spot.y, spot.z, 4, 0.2, 0.2, 0.2, 0.02);
               beam(end, spot, at, ParticleTypes.END_ROD, 0.4);
            }
         }
         case ZERO_GRAVITY -> {
            // Everything drawn going *up*, then a ring on the floor where it is going to come back
            // down. The one move in the kit whose first half is a gift and whose second half is the
            // bill, and the shape says both.
            spend(end, ParticleTypes.GUST_EMITTER_LARGE, at.x, at.y - 2.0, at.z, 4, 12.0, 2.0, 12.0, 0.0);
            for (double y = 0.0; y <= 18.0; y += 1.4) {
               spend(end, ParticleTypes.END_ROD, at.x, at.y - 4.0 + y, at.z, 3, 6.0, 0.4, 6.0, 0.6);
            }
            ring(end, new Vec3(at.x, groundBelow(end, at) + 0.4, at.z), 20.0, 60, ParticleTypes.CLOUD, 1.2, 0.0);
            nova(end, at, 22.0, ParticleTypes.END_ROD, 0xCCBBFF);
         }
         case SUPERNOVA -> {
            // Two rings at the two radii that matter: the eye that is safe and the band that is not.
            // Every other move in the kit is answered by distance; this one is answered by *closing*
            // to the body, and the only way to say that without a caption is to draw both edges.
            ring(end, at, SUPERNOVA_EYE, 40, ParticleTypes.END_ROD, 0.2, 0.4);
            ring(end, at, SUPERNOVA_BAND, 64, ParticleTypes.SOUL_FIRE_FLAME, 1.4, 0.4);
            beam(end, at, at.add(0.0, 30.0, 0.0), ParticleTypes.REVERSE_PORTAL, -0.6);
            pillar(end, at, 16.0, ParticleTypes.END_ROD);
         }
      }
   }

   // ================================================================== the lasting moves

   /**
    * The End Rift: a tear in the air that stands for a while.
    *
    * <p>The fight has had a *rift* since its first draft, and it has always been the same thing -
    * a burst of particles and a hurt call, over in a frame. This is the mechanic the ask was
    * about: something the arena has to be played around for fifteen seconds. It pulls, it spits
    * shapes, it shoots, and then it closes on its own - and it is capped, so a bad rotation
    * cannot turn the island into a sieve.
    */
   private static final int END_RIFT_TICKS = 300;
   /** How many may stand at once. The ask's "hard limit", in one number. */
   public static final int END_RIFT_MAX = 3;
   private static final double END_RIFT_PULL = 0.055;
   private static final double END_RIFT_PULL_REACH = 12.0;
   private static final int END_RIFT_BOLT_TICKS = 34;
   private static final float END_RIFT_BOLT_DAMAGE = 5.0F;
   /** Endermen one rift may leak, ever - so a rift is pressure, not a farm. */
   private static final int END_RIFT_ENDERMEN = 2;

   private static final class EndRift {
      final ServerLevel level;
      final Vec3 at;
      int ticksLeft = END_RIFT_TICKS;
      int bolts = 0;
      int endermen = 0;

      EndRift(ServerLevel level, Vec3 at) {
         this.level = level;
         this.at = at;
      }
   }

   /**
    * The Ender Gaze: the dragon locks on and a beam follows one player.
    *
    * <p>The answer is written into how it is measured - it is not distance, and it is not cover.
    * It is movement: the gaze builds while its target stands still and discharges when it has built
    * all the way. Ten blocks of running per second is all it asks for, which is the same thing the
    * attack is really saying: do not stop.
    */
   private static final int GAZE_TICKS = 110;
   private static final int GAZE_BUILD_TICKS = 34;
   private static final double GAZE_STILL_STEP = 1.25;
   private static final double GAZE_RANGE = 64.0;
   private static final float GAZE_DAMAGE = 18.0F;

   private static final class EnderGaze {
      final ServerLevel level;
      final UUID target;
      final int[] lostTicks = {0};
      Vec3 lastPos;
      int ticksLeft = GAZE_TICKS;
      int build = 0;

      EnderGaze(ServerLevel level, ServerPlayer target) {
         this.level = level;
         this.target = target.getUUID();
         this.lastPos = target.position();
      }
   }

   /**
    * The Void Mark: a countdown on a body, and a blast where it was standing.
    *
    * <p>The mark is announced in <b>chat</b>, not the action bar, on purpose: it is the one thing
    * in the fight a player has to act on while still fighting, and the action bar is already
    * carrying the boss line. The blast falls off with distance rather than being a flat hit, which
    * is what makes spreading out the correct answer instead of clustering.
    */
   private static final int VOID_MARK_TICKS = 60;
   public static final int VOID_MARK_COUNT = 3;
   private static final double VOID_MARK_BLAST = 6.5;
   private static final float VOID_MARK_DAMAGE = 22.0F;

   private static final class VoidMark {
      final ServerLevel level;
      int ticksLeft = VOID_MARK_TICKS;

      VoidMark(ServerLevel level) {
         this.level = level;
      }
   }

   /**
    * The End Rupture: cracks run across the floor and then the floor answers.
    *
    * <p>No telegraph, like every other phase-two and phase-three move. The cracks and the eruption
    * arrive on the same tick; what tells the room where not to stand is the shape drawn as the floor
    * answers, and the only warning a player gets is the one this fight gives everywhere else -
    * that the dragon is somewhere above them and the ground is never safe for long.
    */
   private static final int RUPTURE_TELEGRAPH_TICKS = 0;
   private static final int RUPTURE_ERUPT_TICKS = 18;
   private static final int RUPTURE_LINES = 4;
   private static final int RUPTURE_SAMPLES = 22;
   private static final float RUPTURE_DAMAGE = 13.0F;

   private static final class Rupture {
      final ServerLevel level;
      final List<Vec3> cracks;
      int ticksLeft = RUPTURE_TELEGRAPH_TICKS;
      boolean erupted = false;

      Rupture(ServerLevel level, List<Vec3> cracks) {
         this.level = level;
         this.cracks = cracks;
      }
   }

   /**
    * The Temporal Tear: a section of the island simply stops being there for a moment.
    *
    * <p>The removal is <b>visual only</b>, and that is the whole safety story: the server's blocks
    * are never changed. Clients are sent a block update that draws air, the real states are written
    * straight back afterwards, and the harm is done by the fight's own damage rather than by the
    * hundred players who would otherwise fall out of a hole that was never really dug. The island
    * is the one thing in this fight that must survive it.
    */
   private static final int TEAR_CHARGE_TICKS = 55;
   private static final int TEAR_OPEN_TICKS = 130;
   private static final int TEAR_RADIUS = 9;
   private static final int TEAR_BLOCKS_PER_TICK = 22;
   private static final float TEAR_DAMAGE = 9.0F;

   private static final class Tear {
      final ServerLevel level;
      final Vec3 at;
      /** Position -> the state that is really there, so the restore is exact. */
      final java.util.Map<net.minecraft.core.BlockPos, net.minecraft.world.level.block.state.BlockState> hidden = new java.util.LinkedHashMap<>();
      final List<net.minecraft.core.BlockPos> columns = new ArrayList<>();
      int intro = TEAR_CHARGE_TICKS;
      int ticksLeft = TEAR_OPEN_TICKS;
      int cursor = 0;
      boolean restoring = false;
      boolean done = false;

      Tear(ServerLevel level, Vec3 at) {
         this.level = level;
         this.at = at;
      }
   }

   /** The one nova: the whole arena is the warning, and the whole arena is the blast. */
   private static final int NOVA_CHARGE_TICKS = 70;
   private static final double NOVA_WARNING_RADIUS = 30.0;
   private static final double NOVA_SAFE_RADIUS = 30.0;
   private static final float NOVA_DAMAGE = 26.0F;

   /** The phase-one perch: the dragon fights from the fountain instead of being a piñata on it. */
   private static final int PERCH_TICKS = 220;
   private static final int PERCH_BEAT_TICKS = 34;

   // ---------------------------------------------------------------- starting them

   /** Opens one End Rift, unless the arena is already holding as many as it is allowed. */
   private static boolean openEndRift(ServerLevel end, Vec3 at) {
      if (END_RIFTS.size() >= END_RIFT_MAX) {
         return false;
      }
      END_RIFTS.add(new EndRift(end, at));
      rift(end, at, 1.9);
      spend(end, ParticleTypes.END_ROD, at.x, at.y + 1.0, at.z, 40, 1.4, 2.0, 1.4, 0.15);
      return true;
   }

   private static void startGaze(ServerLevel end, EnderDragon dragon) {
      ServerPlayer target = nearest(end, dragon.position(), GAZE_RANGE);
      if (target == null) {
         return;
      }
      gaze = new EnderGaze(end, target);
      Vec3 from = dragon.getEyePosition();
      beam(end, from, target.position().add(0.0, 1.0, 0.0), ParticleTypes.REVERSE_PORTAL, 1.2);
      end.playSound(null, target.getX(), target.getY(), target.getZ(), SoundEvents.ENDERMAN_SCREAM, SoundSource.HOSTILE, 4.0F, 0.6F);
      target.sendOverlayMessage(Component.literal("\u00a75\u00a7lENDER GAZE \u00a78| \u00a7fKEEP MOVING"));
   }

   private static void startVoidMarks(ServerLevel end) {
      List<ServerPlayer> cast = new ArrayList<>(end.getPlayers(p -> !p.isSpectator() && p.isAlive()));
      for (int i = 0; i < VOID_MARK_COUNT && !cast.isEmpty(); i++) {
         ServerPlayer picked = cast.remove(RANDOM.nextInt(cast.size()));
         VOID_MARKS.put(picked.getUUID(), new VoidMark(end));
         picked.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.GLOWING, VOID_MARK_TICKS + 20, 0, false, false));
         // No countdown in chat and none on the action bar: phase two and phase three do not
         // telegraph their moves. The mark is read off the body - the glow and the particles - and
         // the answer is the one a fight with no warnings always asks for: keep moving.
      }
   }

   /**
    * Reads a set of cracks across the floor around the fight, so the eruption is drawn where it
    * is about to happen rather than being discovered by standing in it.
    */
   private static void startRupture(ServerLevel end, EnderDragon dragon) {
      List<Vec3> cracks = new ArrayList<>();
      for (int line = 0; line < RUPTURE_LINES; line++) {
         double angle = RANDOM.nextDouble() * Math.PI * 2.0;
         double span = 48.0;
         Vec3 centre = dragon.position();
         Vec3 dir = new Vec3(Math.cos(angle), 0.0, Math.sin(angle));
         Vec3 perp = new Vec3(-dir.z, 0.0, dir.x);
         double offset = (RANDOM.nextDouble() - 0.5) * span * 0.6;
         Vec3 start = centre.add(perp.scale(offset)).add(dir.scale(-span * 0.5));
         for (int i = 0; i <= RUPTURE_SAMPLES; i++) {
            double t = i / (double)RUPTURE_SAMPLES;
            Vec3 p = start.add(dir.scale(t * span));
            double ground = groundBelow(end, p);
            double jag = Math.sin(t * Math.PI * 3.0 + line) * 1.6;
            cracks.add(new Vec3(p.x + perp.x * jag, ground + 0.3, p.z + perp.z * jag));
         }
      }
      rupture = new Rupture(end, cracks);
      for (Vec3 p : cracks) {
         spend(end, ParticleTypes.SCULK_SOUL, p.x, p.y, p.z, 2, 0.25, 0.1, 0.25, 0.0);
      }
      end.playSound(null, dragon.getX(), dragon.getY(), dragon.getZ(), SoundEvents.WARDEN_HEARTBEAT, SoundSource.HOSTILE, 4.0F, 0.6F);
   }

   private static void startTear(ServerLevel end, EnderDragon dragon) {
      if (tear != null && !tear.done) {
         return;
      }
      ServerPlayer target = nearest(end, dragon.position(), 90.0);
      Vec3 at = target != null ? target.position() : dragon.position();
      // The centre is snapped down to real ground so the tear is a hole in the island, not a hole
      // in the air next to it.
      at = new Vec3(at.x, groundBelow(end, at), at.z);
      Tear t = new Tear(end, at);
      net.minecraft.core.BlockPos centre = net.minecraft.core.BlockPos.containing(at);
      for (int dx = -TEAR_RADIUS; dx <= TEAR_RADIUS; dx++) {
         for (int dz = -TEAR_RADIUS; dz <= TEAR_RADIUS; dz++) {
            if (dx * dx + dz * dz > TEAR_RADIUS * TEAR_RADIUS) {
               continue;
            }
            net.minecraft.core.BlockPos top = end.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, centre.offset(dx, 0, dz));
            t.columns.add(top.below());
         }
      }
      tear = t;
      end.playSound(null, at.x, at.y, at.z, SoundEvents.END_PORTAL_SPAWN, SoundSource.HOSTILE, 5.0F, 0.5F);
   }

   private static void startNova(ServerLevel end, EnderDragon dragon) {
      novaChargeTicks = NOVA_CHARGE_TICKS;
      novaSpent = true;
      // The nova is anchored to the floor under the dragon the moment it is called, so the warning
      // and the blast are the same circle on the island rather than a ring in the air.
      novaAt = new Vec3(dragon.getX(), groundBelow(end, dragon.position()) + 0.4, dragon.getZ());
      end.playSound(null, dragon.getX(), dragon.getY(), dragon.getZ(), SoundEvents.WITHER_SPAWN, SoundSource.HOSTILE, 6.0F, 0.5F);
      for (ServerPlayer p : end.getPlayers(pl -> !pl.isSpectator())) {
         p.sendOverlayMessage(Component.literal("\u00a7c\u00a7lEND NOVA "));
      }
   }

   /** Starts the phase-one perch sequence if the dragon has just settled on the fountain. */
   private static void startPerch(ServerLevel end, EnderDragon dragon) {
      perchTicks = PERCH_TICKS;
      perchBeat = 0;
      end.playSound(null, dragon.getX(), dragon.getY(), dragon.getZ(), SoundEvents.ENDER_DRAGON_FLAP, SoundSource.HOSTILE, 6.0F, 0.6F);
      ring(end, dragon.position(), 14.0, 48, ParticleTypes.CLOUD, 1.0, 0.05);
   }

   // ---------------------------------------------------------------- ticking them

   /** One tick of every lasting move, from wherever they are ticked from. */
   private static void tickEndRifts(ServerLevel end) {
      java.util.Iterator<EndRift> it = END_RIFTS.iterator();
      while (it.hasNext()) {
         EndRift r = it.next();
         // A rift belongs to the fight that opened it, and the fight can end while one stands.
         if (r.level != end || --r.ticksLeft <= 0) {
            it.remove();
            if (r.level == end) {
               spend(end, ParticleTypes.PORTAL, r.at.x, r.at.y + 1.0, r.at.z, 40, 1.2, 1.6, 1.2, 0.4);
               end.playSound(null, r.at.x, r.at.y, r.at.z, SoundEvents.END_PORTAL_SPAWN, SoundSource.HOSTILE, 2.0F, 0.6F);
            }
            continue;
         }
         spend(end, ParticleTypes.REVERSE_PORTAL, r.at.x, r.at.y + 1.0, r.at.z, 6, 0.9, 1.4, 0.9, -0.2);
         spend(end, ParticleTypes.PORTAL, r.at.x, r.at.y + 1.0, r.at.z, 8, 0.9, 1.4, 0.9, 0.35);
         // The pull: a slight, constant drag toward the tear. Slight on purpose - it is a tilt of
         // the floor, not a grasp, and it is answered by walking.
         for (ServerPlayer p : playersWithin(end, r.at, END_RIFT_PULL_REACH)) {
            Vec3 to = r.at.subtract(p.position());
            Vec3 flat = new Vec3(to.x, 0.0, to.z);
            if (flat.lengthSqr() > 1.0E-4) {
               Vec3 pull = flat.normalize().scale(END_RIFT_PULL);
               p.push(pull.x, 0.0, pull.z);
               p.hurtMarked = true;
            }
         }
         // A leak every second and a half or so, and at most the cap of shapes - a rift that keeps
         // producing bodies forever is a rift that outlives the fight that made it.
         if (r.ticksLeft % END_RIFT_BOLT_TICKS == 0) {
            ServerPlayer victim = nearest(end, r.at, END_RIFT_PULL_REACH * 2.0);
            if (victim != null) {
               Vec3 from = r.at.add(0.0, 1.2, 0.0);
               Vec3 aim = victim.position().add(0.0, 1.0, 0.0).subtract(from).normalize().scale(1.1);
               bolt(end, dragon, from, aim, 90, END_RIFT_BOLT_DAMAGE, 1.6, victim.getUUID());
               r.bolts++;
            }
         }
         if (r.endermen < END_RIFT_ENDERMEN && r.ticksLeft % 90 == 0) {
            EnderMan man = EntityTypes.ENDERMAN.create(end, EntitySpawnReason.EVENT);
            if (man != null) {
               man.setPos(r.at.x, r.at.y + 1.0, r.at.z);
               man.setPersistenceRequired();
               end.addFreshEntity(man);
               r.endermen++;
            }
         }
      }
   }

   private static void tickGaze(ServerLevel end, EnderDragon dragon) {
      EnderGaze g = gaze;
      if (g == null) {
         return;
      }
      ServerPlayer target = end.getServer().getPlayerList().getPlayer(g.target);
      if (target == null || !target.isAlive() || target.level() != end || --g.ticksLeft <= 0) {
         gaze = null;
         return;
      }
      Vec3 from = dragon.getEyePosition();
      Vec3 to = target.position().add(0.0, 1.0, 0.0);
      beam(end, from, to, ParticleTypes.REVERSE_PORTAL, 1.0);
      spend(end, ParticleTypes.PORTAL, to.x, to.y, to.z, 6, 0.4, 0.6, 0.4, 0.2);
      // Still means still: less than a block and a quarter of ground covered in a tick. Anything
      // more is a player running, and running is the answer.
      double moved = target.position().distanceTo(g.lastPos);
      g.lastPos = target.position();
      if (moved < GAZE_STILL_STEP) {
         g.build++;
      } else {
         g.build = Math.max(0, g.build - 2);
      }
      if (g.build >= GAZE_BUILD_TICKS) {
         gaze = null;
         flash(end, to, 0xFF55FF);
         nova(end, to, 9.0, ParticleTypes.SOUL_FIRE_FLAME, 0xFF55FF);
         spend(end, ParticleTypes.EXPLOSION_EMITTER, to.x, to.y, to.z, 2, 0.0, 0.0, 0.0, 0.0);
         end.playSound(null, to.x, to.y, to.z, SoundEvents.GENERIC_EXPLODE, SoundSource.HOSTILE, 4.0F, 0.8F);
         for (ServerPlayer p : playersWithin(end, to, 7.0)) {
            p.hurtServer(end, end.damageSources().mobAttack(dragon), GAZE_DAMAGE * (p == target ? 1.0F : 0.5F));
            Vec3 push = p.position().subtract(to);
            Vec3 flat = push.lengthSqr() < 1.0 ? new Vec3(0.0, 0.0, 1.0) : new Vec3(push.x, 0.0, push.z).normalize();
            p.push(flat.x * 2.0, 0.5, flat.z * 2.0);
            p.hurtMarked = true;
         }
      }
   }

   private static void tickVoidMarks(ServerLevel end, EnderDragon dragon) {
      if (VOID_MARKS.isEmpty()) {
         return;
      }
      java.util.Iterator<java.util.Map.Entry<UUID, VoidMark>> it = VOID_MARKS.entrySet().iterator();
      while (it.hasNext()) {
         java.util.Map.Entry<UUID, VoidMark> entry = it.next();
         ServerPlayer p = end.getServer().getPlayerList().getPlayer(entry.getKey());
         if (p == null || !p.isAlive() || p.level() != end) {
            it.remove();
            continue;
         }
         VoidMark m = entry.getValue();
         spend(end, ParticleTypes.SCULK_SOUL, p.getX(), p.getY() + p.getBbHeight() + 0.5, p.getZ(), 4, 0.3, 0.2, 0.3, 0.01);
         int left = m.ticksLeft--;
         // The countdown, in chat and on the second, because this is the one warning the fight
         // still gives. Two and one only - the first line was said when the mark landed.
         if ((left == 40 || left == 20) && left % 20 == 0) {
            p.sendSystemMessage(Component.literal("\u00a75\u00a7lVOID MARK \u00a78- \u00a7f" + (left / 20) + "..."));
         }
         if (m.ticksLeft > 0) {
            continue;
         }
         it.remove();
         Vec3 at = p.position();
         flash(end, at, 0xFF55FF);
         spend(end, ParticleTypes.EXPLOSION_EMITTER, at.x, at.y + 0.4, at.z, 3, 1.0, 0.4, 1.0, 0.0);
         spend(end, ParticleTypes.PORTAL, at.x, at.y + 0.4, at.z, 90, 2.0, 0.8, 2.0, 0.6);
         end.playSound(null, at.x, at.y, at.z, SoundEvents.GENERIC_EXPLODE, SoundSource.HOSTILE, 4.0F, 0.7F);
         // Falls off with distance rather than being flat: spreading out is the answer, and a
         // player who ran is meant to feel that it worked.
         for (ServerPlayer other : playersWithin(end, at, VOID_MARK_BLAST * 2.0)) {
            double d = other.position().distanceTo(at);
            float scale = (float)Math.max(0.0, 1.0 - d / (VOID_MARK_BLAST * 2.0));
            if (scale <= 0.0F) {
               continue;
            }
            other.hurtServer(end, end.damageSources().mobAttack(dragon), VOID_MARK_DAMAGE * scale);
         }
      }
   }

   private static void tickRupture(ServerLevel end, EnderDragon dragon) {
      Rupture r = rupture;
      if (r == null) {
         return;
      }
      if (!r.erupted) {
         for (Vec3 p : r.cracks) {
            spend(end, ParticleTypes.SCULK_SOUL, p.x, p.y, p.z, 2, 0.3, 0.1, 0.3, 0.0);
            spend(end, ParticleTypes.LARGE_SMOKE, p.x, p.y + 0.2, p.z, 1, 0.2, 0.1, 0.2, 0.01);
         }
         if (--r.ticksLeft > 0) {
            return;
         }
         r.erupted = true;
         r.ticksLeft = RUPTURE_ERUPT_TICKS;
         end.playSound(null, dragon.getX(), dragon.getY(), dragon.getZ(), SoundEvents.GENERIC_EXPLODE, SoundSource.HOSTILE, 6.0F, 0.5F);
      }
      // The eruption: void energy straight up out of every crack, once.
      for (Vec3 p : r.cracks) {
         for (double y = 0.0; y <= 7.0; y += 0.9) {
            spend(end, ParticleTypes.SOUL_FIRE_FLAME, p.x, p.y + y, p.z, 2, 0.25, 0.2, 0.25, 0.06);
         }
         spend(end, ParticleTypes.REVERSE_PORTAL, p.x, p.y + 3.0, p.z, 8, 0.4, 3.0, 0.4, -0.1);
         hurtNear(end, p, 0.0, 3.4, dragon, RUPTURE_DAMAGE, "the rupture");
      }
      if (--r.ticksLeft <= 0) {
         rupture = null;
      }
   }

   private static void tickTear(ServerLevel end, EnderDragon dragon) {
      Tear t = tear;
      if (t == null || t.done) {
         return;
      }
      if (t.intro > 0) {
         // The closing circle: the whole read of the attack, drawn on the floor.
         double shrink = (double)t.intro / TEAR_CHARGE_TICKS;
         double radius = TEAR_RADIUS * (0.35 + shrink * 0.65);
         ring(end, t.at.add(0.0, 0.4, 0.0), radius, (int)(radius * 12.0), ParticleTypes.REVERSE_PORTAL, 0.0, 0.0);
         spend(end, ParticleTypes.PORTAL, t.at.x, t.at.y + 0.5, t.at.z, 20, radius, 0.3, radius, 0.05);
         t.intro--;
         return;
      }
      if (!t.restoring) {
         // Take the island away, a slice at a time, so one tick never carries the whole section.
         int end1 = Math.min(t.columns.size(), t.cursor + TEAR_BLOCKS_PER_TICK);
         for (; t.cursor < end1; t.cursor++) {
            net.minecraft.core.BlockPos pos = t.columns.get(t.cursor);
            net.minecraft.world.level.block.state.BlockState real = end.getBlockState(pos);
            if (real.isAir()) {
               continue;
            }
            t.hidden.put(pos, real);
            // Visual only: the server keeps its block, the clients are told it is air. Nothing
            // here can leave a hole in the island, which is the only reason this version of the
            // mechanic is in the fight at all.
            end.sendBlockUpdated(pos, real, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
         }
         if (t.cursor >= t.columns.size() && --t.ticksLeft <= 0) {
            t.restoring = true;
         }
         // The floor that is not there is dangerous: the void under it bites.
         for (ServerPlayer p : playersWithin(end, t.at.add(0.0, 1.0, 0.0), TEAR_RADIUS + 1.0)) {
            if (p.onGround()) {
               p.hurtServer(end, end.damageSources().magic(), TEAR_DAMAGE / 20.0F);
            }
         }
         spend(end, ParticleTypes.REVERSE_PORTAL, t.at.x, t.at.y + 1.2, t.at.z, 30, TEAR_RADIUS * 0.8, 0.6, TEAR_RADIUS * 0.8, -0.15);
         spend(end, ParticleTypes.SCULK_SOUL, t.at.x, t.at.y + 0.6, t.at.z, 12, TEAR_RADIUS * 0.7, 0.3, TEAR_RADIUS * 0.7, 0.02);
         return;
      }
      // Put it back. The restore walks the same list and writes the real states back out, so the
      // island is exactly what it was even if the fight ends mid-tear (see clearLastingMoves).
      int end2 = Math.min(t.columns.size(), t.cursor + TEAR_BLOCKS_PER_TICK);
      for (; t.cursor < end2; t.cursor++) {
         net.minecraft.core.BlockPos pos = t.columns.get(t.cursor);
         net.minecraft.world.level.block.state.BlockState real = t.hidden.get(pos);
         if (real != null) {
            end.sendBlockUpdated(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), real, 3);
         }
      }
      if (t.cursor >= t.columns.size()) {
         t.done = true;
         tear = null;
      }
   }

   private static void tickNovaCharge(ServerLevel end, EnderDragon dragon) {
      double left = (double)novaChargeTicks / NOVA_CHARGE_TICKS;
      double radius = NOVA_WARNING_RADIUS * (0.25 + (1.0 - left) * 0.75);
      Vec3 at = novaAt != null ? novaAt : new Vec3(dragon.getX(), groundBelow(end, dragon.position()) + 0.4, dragon.getZ());
      ring(end, at, radius, (int)(radius * 10.0), ParticleTypes.SOUL_FIRE_FLAME, 0.0, 0.0);
      ring(end, at, NOVA_WARNING_RADIUS, 90, ParticleTypes.END_ROD, 0.0, 0.0);
      spend(end, ParticleTypes.REVERSE_PORTAL, at.x, at.y, at.z, 40, 8.0, 6.0, 8.0, -0.3);
      if (--novaChargeTicks > 0) {
         return;
      }
      // The nova itself: everything outside the safe ring eats it, everything inside is untouched.
      flash(end, at, 0xFF2288);
      nova(end, at, 46.0, ParticleTypes.SOUL_FIRE_FLAME, 0xFF2288);
      nova(end, at, 60.0, ParticleTypes.END_ROD, 0xFF88FF);
      ring(end, at, NOVA_SAFE_RADIUS, 90, ParticleTypes.CLOUD, 2.4, 0.1);
      spend(end, ParticleTypes.EXPLOSION_EMITTER, at.x, at.y, at.z, 30, 26.0, 6.0, 26.0, 0.0);
      end.playSound(null, at.x, at.y, at.z, SoundEvents.GENERIC_EXPLODE, SoundSource.HOSTILE, 10.0F, 0.4F);
      end.playSound(null, at.x, at.y, at.z, SoundEvents.WARDEN_SONIC_BOOM, SoundSource.HOSTILE, 8.0F, 0.5F);
      for (ServerPlayer p : end.getPlayers(pl -> !pl.isSpectator() && pl.isAlive())) {
         double d = Math.hypot(p.getX() - at.x, p.getZ() - at.z);
         if (d <= NOVA_SAFE_RADIUS) {
            p.sendOverlayMessage(Component.literal("\u00a7aINSIDE THE EYE \u00a78- \u00a7fnothing touched you"));
            continue;
         }
         p.hurtServer(end, end.damageSources().mobAttack(dragon), NOVA_DAMAGE);
         Vec3 push = p.position().subtract(at);
         Vec3 flat = push.lengthSqr() < 1.0 ? new Vec3(0.0, 0.0, 1.0) : new Vec3(push.x, 0.0, push.z).normalize();
         p.push(flat.x * 4.0, 1.2, flat.z * 4.0);
         p.hurtMarked = true;
      }
   }

   /**
    * The phase-one perch, scripted: the dragon is on the fountain and it is fighting from it.
    *
    * <p>Vanilla's perch is a piñata - the head comes down and a room hits it. This is the ask's
    * version: the head snaps at whoever is close, the tail clears the ring, and the wings beat a
    * shockwave out across the floor between the two, so the openings exist and have to be taken.
    * After its beats it takes off on its own, which is what keeps the fountain a phase rather than
    * a place to stand.
    */
   private static void tickPerch(ServerLevel end, EnderDragon dragon) {
      if (perchTicks <= 0) {
         return;
      }
      if (--perchTicks <= 0) {
         // Time to go: the dragon is pushed back into the air, and the fountain is a fountain again.
         dragon.getPhaseManager().setPhase(EnderDragonPhase.HOVERING);
         dragon.setDeltaMovement(0.0, 0.9, 0.0);
         dragon.hurtMarked = true;
         // And the exit costs: standing under it when it leaves is a mistake, not a bonus round.
         takeOffShockwave(end, dragon);
         return;
      }
      if (--perchBeat > 0) {
         // Between the beats the head is the opening: a small, honest window.
         spend(end, ParticleTypes.END_ROD, dragon.getX(), dragon.getY() + 3.0, dragon.getZ(), 6, 1.4, 1.0, 1.4, 0.05);
         return;
      }
      perchBeat = PERCH_BEAT_TICKS;
      Vec3 at = dragon.position();
      int beat = perchTicks / PERCH_BEAT_TICKS;
      switch (beat % 3) {
         case 0 -> {
            // The head: a short, brutal snap at whoever is on the fountain with it.
            Vec3 front = at.add(flatLook(dragon).scale(6.0));
            spend(end, ParticleTypes.SWEEP_ATTACK, front.x, front.y + 2.0, front.z, 12, 1.6, 1.0, 1.6, 0.0);
            end.playSound(null, front.x, front.y, front.z, SoundEvents.ENDER_DRAGON_GROWL, SoundSource.HOSTILE, 5.0F, 1.2F);
            hurtNear(end, front, 0.0, 4.5, dragon, 18.0F, "the dragon's head");
         }
         case 1 -> {
            // The tail: the ring around the fountain, swept clear.
            ring(end, at, 12.0, 56, ParticleTypes.SWEEP_ATTACK, 0.6, 0.0);
            ring(end, at, 13.0, 64, ParticleTypes.END_ROD, 1.2, 0.1);
            end.playSound(null, at.x, at.y, at.z, SoundEvents.ENDER_DRAGON_FLAP, SoundSource.HOSTILE, 5.0F, 1.3F);
            for (ServerPlayer p : playersWithin(end, at, 13.0)) {
               Vec3 push = p.position().subtract(at);
               Vec3 flat = push.lengthSqr() < 1.0 ? new Vec3(0.0, 0.0, 1.0) : new Vec3(push.x, 0.0, push.z).normalize();
               p.push(flat.x * 3.0, 0.7, flat.z * 3.0);
               p.hurtMarked = true;
               p.hurtServer(end, end.damageSources().mobAttack(dragon), wound(14.0F));
            }
         }
         default -> {
            // The wings: a shockwave that runs out along the floor of the whole island.
            Vec3 hit = new Vec3(at.x, groundBelow(end, at) + 0.4, at.z);
            spend(end, ParticleTypes.GUST_EMITTER_LARGE, hit.x, hit.y, hit.z, 5, 12.0, 1.0, 12.0, 0.0);
            ring(end, hit, 14.0, 64, ParticleTypes.GUST, 0.0, 0.06);
            ring(end, hit, 28.0, 80, ParticleTypes.CLOUD, 1.6, 0.05);
            flash(end, hit, 0xCCBBFF);
            end.playSound(null, hit.x, hit.y, hit.z, SoundEvents.GENERIC_EXPLODE, SoundSource.HOSTILE, 6.0F, 0.45F);
            for (ServerPlayer p : playersWithin(end, at, 30.0)) {
               Vec3 push = p.position().subtract(hit);
               Vec3 flat = push.lengthSqr() < 1.0 ? flatLook(dragon) : new Vec3(push.x, 0.0, push.z).normalize();
               p.push(flat.x * 3.2, 0.6, flat.z * 3.2);
               p.hurtMarked = true;
               p.hurtServer(end, end.damageSources().mobAttack(dragon), wound(10.0F));
            }
         }
      }
   }

   /**
    * Ends every lasting move at once: called when the fight stops for any reason.
    *
    * <p>The tear is the reason this exists as one method rather than a line at each call site - a
    * section of the island that is only hidden while a state object lives has to be put back even
    * if the fight ends in the middle of it, or the island is left looking eaten.
    */
   private static void clearLastingMoves(ServerLevel end) {
      END_RIFTS.removeIf(r -> r.level == end);
      gaze = null;
      VOID_MARKS.clear();
      rupture = null;
      novaChargeTicks = 0;
      novaAt = null;
      perchTicks = 0;
      perchBeat = 0;
      if (end == null) {
         tear = null;
         return;
      }
      if (tear != null && tear.level == end) {
         Tear t = tear;
         for (java.util.Map.Entry<net.minecraft.core.BlockPos, net.minecraft.world.level.block.state.BlockState> e : t.hidden.entrySet()) {
            end.sendBlockUpdated(e.getKey(), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), e.getValue(), 3);
         }
         t.done = true;
         tear = null;
      }
   }

   /** The rifts standing right now, for the self-test and for nothing else. */
   public static int riftsStanding() {
      return END_RIFTS.size();
   }

   /** True once the once-a-fight nova has been spent. */
   public static boolean novaUsed() {
      return novaSpent;
   }

   // ------------------------------------------------------------------ impacts

   /** What a move does when its telegraph runs out. */
   private static void impact(ServerLevel end, EnderDragon dragon, Move current) {
      Vec3 at = dragon.position();
      switch (current) {
         case WING_GUST -> {
            spend(end, ParticleTypes.GUST_EMITTER_LARGE, at.x, at.y, at.z, 4, 6.0, 4.0, 6.0, 0.0);
            spend(end, ParticleTypes.CLOUD, at.x, at.y, at.z, 160, 10.0, 5.0, 10.0, 0.25);
            spend(end, ParticleTypes.GUST, at.x, at.y, at.z, 130, 12.0, 4.0, 12.0, 0.0);
            ring(end, at, 20.0, 64, ParticleTypes.CLOUD, 1.6, 0.05);
            end.playSound(null, at.x, at.y, at.z, SoundEvents.ENDER_DRAGON_FLAP, SoundSource.HOSTILE, 5.0F, 0.6F);
            for (ServerPlayer p : playersWithin(end, at, 20.0)) {
               Vec3 push = p.position().subtract(at);
               Vec3 flat = push.lengthSqr() < 1.0 ? new Vec3(0.0, 0.0, 1.0) : new Vec3(push.x, 0.0, push.z).normalize();
               p.push(flat.x * 3.0, 0.8, flat.z * 3.0);
               p.hurtMarked = true;
               p.hurtServer(end, end.damageSources().mobAttack(dragon), wound(9.0F));
            }
            // Close to the floor the gust has nowhere to go but out: the wings press the air into
            // the ground and the shockwave runs along it, throwing the room backwards. This is the
            // answer to standing under the dragon and swinging - at altitude the same move is a
            // shove, and down here it is a wall.
            double ground = groundBelow(end, at);
            if (dragonIsPerching(dragon) || at.y - ground < 9.0) {
               Vec3 hit = new Vec3(at.x, ground + 0.4, at.z);
               spend(end, ParticleTypes.GUST_EMITTER_LARGE, hit.x, hit.y, hit.z, 6, 11.0, 1.0, 11.0, 0.0);
               spend(end, ParticleTypes.CLOUD, hit.x, hit.y, hit.z, 140, 15.0, 1.5, 15.0, 0.1);
               ring(end, hit, 12.0, 56, ParticleTypes.GUST, 0.0, 0.06);
               ring(end, hit, 24.0, 72, ParticleTypes.CLOUD, 1.5, 0.05);
               flash(end, hit, 0xCCBBFF);
               end.playSound(null, hit.x, hit.y, hit.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 6.0F, 0.4F);
               for (ServerPlayer p : playersWithin(end, at, 26.0)) {
                  Vec3 push = p.position().subtract(hit);
                  Vec3 flat = push.lengthSqr() < 1.0 ? flatLook(dragon) : new Vec3(push.x, 0.0, push.z).normalize();
                  p.push(flat.x * 3.4, 0.6, flat.z * 3.4);
                  p.hurtMarked = true;
                  p.hurtServer(end, end.damageSources().mobAttack(dragon), wound(9.0F));
               }
            }
         }
         case TAIL_SWEEP -> {
            ring(end, at, 8.0, 32, ParticleTypes.SWEEP_ATTACK, 0.4, 0.0);
            ring(end, at, 9.0, 40, ParticleTypes.END_ROD, 1.1, 0.1);
            flash(end, at, 0xAA66FF);
            end.playSound(null, at.x, at.y, at.z, SoundEvents.ENDER_DRAGON_FLAP, SoundSource.HOSTILE, 4.0F, 1.4F);
            for (ServerPlayer p : playersWithin(end, at, 9.0)) {
               Vec3 push = p.position().subtract(at);
               Vec3 flat = push.lengthSqr() < 1.0 ? new Vec3(0.0, 0.0, 1.0) : new Vec3(push.x, 0.0, push.z).normalize();
               p.push(flat.x * 2.8, 0.7, flat.z * 2.8);
               p.hurtMarked = true;
               p.hurtServer(end, end.damageSources().mobAttack(dragon), wound(12.0F));
            }
         }
         case ENDER_BARRAGE -> volley(end, dragon, 4, 10.0F);
         case END_STOMP -> stompTheGround(end, dragon);
         case ENDER_PULSE -> enderPulse(end, dragon);
         case VOID_HAIL -> voidHail(end, dragon);
         case SOUL_ERUPTION -> soulEruption(end, dragon);
         case CRYSTAL_RESONANCE -> {
            int healed = 0;
            for (Entity crystal : crystals(end)) {
               // The crystals fire outward and pour themselves back into the body - the vanilla
               // relationship, made visible, and the reason breaking them still matters.
               beam(end, crystal.position(), at, ParticleTypes.END_ROD, 1.0);
               volleyFrom(end, dragon, crystal.position(), 2, 9.0F);
               spend(end, ParticleTypes.EXPLOSION_EMITTER, crystal.getX(), crystal.getY(), crystal.getZ(), 1, 0.0, 0.0, 0.0, 0.0);
               healed++;
            }
            if (healed > 0 && !lastStand) {
               healWithinPhase(dragon, 6.0F);
            }
            end.playSound(null, at.x, at.y, at.z, SoundEvents.BEACON_POWER_SELECT, SoundSource.HOSTILE, 4.0F, 0.7F);
         }
         case SHRIEK -> {
            spend(end, ParticleTypes.SONIC_BOOM, at.x, at.y, at.z, 6, 4.0, 2.0, 4.0, 0.0);
            end.playSound(null, at.x, at.y, at.z, SoundEvents.WARDEN_SONIC_BOOM, SoundSource.HOSTILE, 6.0F, 0.7F);
            nova(end, at, 26.0, ParticleTypes.SONIC_BOOM, 0x66FFEE);
            ring(end, at, 26.0, 72, ParticleTypes.SCULK_SOUL, 1.5, 0.08);
            for (ServerPlayer p : playersWithin(end, at, 30.0)) {
               p.hurtServer(end, end.damageSources().mobAttack(dragon), wound(11.0F));
               p.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.DARKNESS, 100, 0));
            }
         }
         case VOID_RIFT -> {
            for (ServerPlayer p : end.getPlayers(pl -> !pl.isSpectator())) {
               Vec3 spot = p.position().add((RANDOM.nextDouble() - 0.5) * 6.0, 0.0, (RANDOM.nextDouble() - 0.5) * 6.0);
               rift(end, spot, 1.6);
               p.hurtServer(end, end.damageSources().magic(), wound(10.0F));
            }
            end.playSound(null, at.x, at.y, at.z, SoundEvents.END_PORTAL_SPAWN, SoundSource.HOSTILE, 4.0F, 0.7F);
         }
         case END_RIFT -> {
            // Two at once, at the feet of two different players: the mechanic is about the floor
            // becoming unreliable, so the rifts open where the fight actually is.
            int opened = 0;
            for (ServerPlayer p : end.getPlayers(pl -> !pl.isSpectator() && pl.isAlive())) {
               if (opened >= 2) {
                  break;
               }
               Vec3 spot = p.position().add((RANDOM.nextDouble() - 0.5) * 4.0, 0.0, (RANDOM.nextDouble() - 0.5) * 4.0);
               Vec3 onGround = new Vec3(spot.x, groundBelow(end, spot) + 0.2, spot.z);
               if (openEndRift(end, onGround)) {
                  opened++;
               }
            }
            for (ServerPlayer p : playersWithin(end, at, 40.0)) {
               p.sendOverlayMessage(Component.literal("\u00a75\u00a7lEND RIFT \u00a78| \u00a7fthe floor is " + END_RIFTS.size() + " tears"));
            }
         }
         case ENDER_GAZE -> startGaze(end, dragon);
         case VOID_MARK -> startVoidMarks(end);
         case END_RUPTURE -> startRupture(end, dragon);
         case TEMPORAL_TEAR -> startTear(end, dragon);
         case END_NOVA -> {
            // Once a fight, asked for by name: a second roll would make the fight a coin flip.
            if (!novaSpent) {
               startNova(end, dragon);
            }
         }
         case FOUNTAIN_PERCH -> startPerch(end, dragon);
         case RIFT_STORM -> {
            for (int i = 0; i < 12; i++) {
               double angle = (Math.PI * 2.0) * i / 12.0;
               Vec3 spot = new Vec3(Math.cos(angle) * 30.0, 64.0, Math.sin(angle) * 30.0);
               rift(end, spot, 1.4);
               visualLightning(end, spot.x, 70.0, spot.z);
               hurtNear(end, spot, 0.0, 6.0, dragon, 7.0F, "a rift");
            }
            end.playSound(null, at.x, at.y, at.z, SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.WEATHER, 4.0F, 0.7F);
         }
         case SUMMON_SWARM -> {
            int raised = 0;
            for (int i = 0; i < 8; i++) {
               double angle = (Math.PI * 2.0) * i / 8.0;
               Vec3 spot = new Vec3(Math.cos(angle) * 18.0, 64.0, Math.sin(angle) * 18.0);
               rift(end, spot, 1.2);
               if (i % 2 == 0) {
                  EnderMan man = EntityTypes.ENDERMAN.create(end, EntitySpawnReason.EVENT);
                  if (man != null) {
                     man.setPos(spot.x, spot.y, spot.z);
                     man.setPersistenceRequired();
                     end.addFreshEntity(man);
                     raised++;
                  }
               } else {
                  Endermite mite = EntityTypes.ENDERMITE.create(end, EntitySpawnReason.EVENT);
                  if (mite != null) {
                     mite.setPos(spot.x, spot.y, spot.z);
                     end.addFreshEntity(mite);
                     raised++;
                  }
               }
            }
            for (ServerPlayer p : playersWithin(end, at, 60.0)) {
               p.sendOverlayMessage(Component.literal("\u00a75\u00a7lTHE ISLAND ANSWERS \u00a78| \u00a7f" + raised + " shapes in the dark"));
            }
         }
         case BREATH_NOVA -> {
            ring(end, at, 20.0, 96, BREATH, 1.0, 0.09);
            end.playSound(null, at.x, at.y, at.z, SoundEvents.DRAGON_FIREBALL_EXPLODE, SoundSource.HOSTILE, 5.0F, 0.6F);
            nova(end, at, 22.0, BREATH, 0x8844CC);
            for (ServerPlayer p : playersWithin(end, at, 22.0)) {
               p.hurtServer(end, end.damageSources().dragonBreath(), wound(12.0F));
               p.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.POISON, 120, 0));
            }
         }
         case PHASE_BLINK -> {
            ServerPlayer target = nearest(end, at, 80.0);
            if (target != null) {
               Vec3 behind = target.position().add(target.getLookAngle().scale(-8.0));
               spend(end, ParticleTypes.REVERSE_PORTAL, at.x, at.y, at.z, 200, 3.0, 3.0, 3.0, -0.5);
               dragon.teleportTo(behind.x, Math.max(60.0, behind.y + 6.0), behind.z);
               dragon.setDeltaMovement(Vec3.ZERO);
               dragon.hurtMarked = true;
               spend(end, ParticleTypes.PORTAL, behind.x, behind.y + 2.0, behind.z, 200, 3.0, 3.0, 3.0, 0.6);
               end.playSound(null, behind.x, behind.y, behind.z, SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE, 3.0F, 0.6F);
            }
         }
         case ENDER_RAIN -> {
            for (ServerPlayer p : end.getPlayers(pl -> !pl.isSpectator())) {
               for (int i = 0; i < 5; i++) {
                  double x = p.getX() + (RANDOM.nextDouble() - 0.5) * 10.0;
                  double z = p.getZ() + (RANDOM.nextDouble() - 0.5) * 10.0;
                  bolt(end, dragon, new Vec3(x, 92.0, z), new Vec3(0.0, -1.35, 0.0), 60, 9.0F, 3.0);
                  beam(end, new Vec3(x, 92.0, z), new Vec3(x, 64.0, z), ParticleTypes.END_ROD, 1.4);
               }
            }
            end.playSound(null, at.x, at.y, at.z, SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.WEATHER, 5.0F, 0.6F);
         }
         case WITHERING_GAZE -> {
            for (ServerPlayer p : end.getPlayers(pl -> !pl.isSpectator())) {                beam(end, at, p.position().add(0.0, 1.0, 0.0), ParticleTypes.SOUL_FIRE_FLAME, 0.15);
               p.hurtServer(end, end.damageSources().wither(), wound(10.0F));
               p.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.WITHER, 120, 1));
               if (!lastStand && dragon.getHealth() < dragon.getMaxHealth() * 0.5F) {
                  healWithinPhase(dragon, 3.0F);
               }
            }
            end.playSound(null, at.x, at.y, at.z, SoundEvents.WITHER_SHOOT, SoundSource.HOSTILE, 4.0F, 0.7F);
         }
         case DRAGON_ROAR -> {
            nova(end, at, 34.0, ParticleTypes.WHITE_ASH, 0xFF3355);
            ring(end, at, 34.0, 80, ParticleTypes.WHITE_ASH, 1.2, 0.05);
            ring(end, at, 34.0, 80, ParticleTypes.SCULK_SOUL, 1.2, 0.06);
            spend(end, ParticleTypes.GUST_EMITTER_LARGE, at.x, at.y, at.z, 8, 8.0, 6.0, 8.0, 0.0);
            end.playSound(null, at.x, at.y, at.z, SoundEvents.ENDER_DRAGON_GROWL, SoundSource.HOSTILE, 8.0F, 0.5F);
            for (ServerPlayer p : playersWithin(end, at, 40.0)) {
               p.hurtServer(end, end.damageSources().magic(), wound(9.0F));
               p.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.DARKNESS, 160, 0));
            }
            for (int i = 0; i < 12; i++) {
               double angle = (Math.PI * 2.0) * i / 12.0;
               spend(end, 
                  ParticleTypes.EXPLOSION_EMITTER,
                  Math.cos(angle) * 34.0, 64.0, Math.sin(angle) * 34.0, 1, 0.0, 0.0, 0.0, 0.0
               );
            }
         }
         case END_WINGS -> {
            // Two walls thrown sideways: the answer is to be on the axis rather than beside it.
            Vec3 look = flatLook(dragon);
            Vec3 side = new Vec3(-look.z, 0.0, look.x);
            for (int s = -1; s <= 1; s += 2) {
               spend(end, 
                  ParticleTypes.GUST_EMITTER_LARGE, at.x + side.x * 10.0 * s, at.y + 1.0, at.z + side.z * 10.0 * s,
                  2, 2.0, 2.0, 2.0, 0.0
               );
               spend(end, 
                  ParticleTypes.GUST, at.x + side.x * 8.0 * s, at.y + 1.5, at.z + side.z * 8.0 * s,
                  80, 8.0, 2.5, 8.0, 0.0
               );
            }
            spend(end, ParticleTypes.CLOUD, at.x, at.y + 1.5, at.z, 200, 14.0, 3.0, 14.0, 0.25);
            end.playSound(null, at.x, at.y, at.z, SoundEvents.ENDER_DRAGON_FLAP, SoundSource.HOSTILE, 6.0F, 0.5F);
            for (ServerPlayer p : playersWithin(end, at, 18.0)) {
               Vec3 rel = new Vec3(p.getX() - at.x, 0.0, p.getZ() - at.z);
               double lean = rel.dot(side);
               double dir = lean >= 0.0 ? 1.0 : -1.0;
               p.push(side.x * dir * 2.6, 0.7, side.z * dir * 2.6);
               p.hurtMarked = true;
               p.hurtServer(end, end.damageSources().mobAttack(dragon), wound(12.0F));
            }
         }
         case VOID_CHAINS -> {
            // Chains out of the dark: everybody is rooted, dragged a step toward the body and
            // then struck. The counter is that the pull is small - a player already moving keeps
            // moving, and the wound is only for standing still in them.
            for (ServerPlayer p : playersWithin(end, at, 44.0)) {
               Vec3 toDragon = at.subtract(p.position());
               if (toDragon.lengthSqr() > 1.0) {
                  Vec3 unit = new Vec3(toDragon.x, 0.0, toDragon.z).normalize().scale(0.75);
                  p.push(unit.x, 0.1, unit.z);
                  p.hurtMarked = true;
               }
               p.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 70, 2));
               p.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 90, 0));
               p.hurtServer(end, end.damageSources().magic(), wound(9.0F));
            }
            for (ServerPlayer p : end.getPlayers(pl -> !pl.isSpectator())) {
               beam(end, at, p.position().add(0.0, 1.0, 0.0), ParticleTypes.SCULK_SOUL, 1.1);
               spend(end, ParticleTypes.SOUL, p.getX(), p.getY() + 1.0, p.getZ(), 30, 1.0, 1.4, 1.0, 0.05);
            }
            spend(end, ParticleTypes.SCULK_SOUL, at.x, at.y, at.z, 160, 6.0, 4.0, 6.0, 0.12);
            end.playSound(null, at.x, at.y, at.z, SoundEvents.ENDERMAN_SCREAM, SoundSource.HOSTILE, 4.0F, 0.5F);
         }
         case BREATH_LANCE -> beginBeam(end, dragon);
         case DRAGON_DIVE -> {
            // The body actually moves: a line of ruin is drawn from where it was to a point past
            // the target, everything standing along that line is struck, and then the dragon is
            // there. A dive that only made particles would be a lie about where the body is.
            Vec3 aim = at;
            ServerPlayer target = nearest(end, at, 80.0);
            Vec3 to = target == null
               ? at.add(flatLook(dragon).scale(22.0)).add(0.0, -4.0, 0.0)
               : target.position().add(flatLook(dragon).scale(6.0)).add(0.0, 6.0, 0.0);
            for (double d = 0.0; d <= 1.0; d += 0.08) {
               Vec3 point = aim.lerp(to, d);
               spend(end, ParticleTypes.SOUL_FIRE_FLAME, point.x, point.y, point.z, 10, 1.2, 1.2, 1.2, 0.08);
               spend(end, ParticleTypes.LARGE_SMOKE, point.x, point.y, point.z, 6, 1.4, 1.4, 1.4, 0.04);
               hurtNear(end, point, 0.0, 4.5, dragon, 11.0F, "the dive");
            }
            dragon.teleportTo(to.x, Math.max(58.0, to.y), to.z);
            dragon.setDeltaMovement(Vec3.ZERO);
            dragon.hurtMarked = true;
            spend(end, ParticleTypes.EXPLOSION_EMITTER, to.x, to.y, to.z, 14, 4.0, 3.0, 4.0, 0.0);
            spend(end, ParticleTypes.GUST_EMITTER_LARGE, to.x, to.y, to.z, 5, 4.0, 3.0, 4.0, 0.0);
            spend(end, ParticleTypes.GUST, to.x, to.y, to.z, 120, 7.0, 4.0, 7.0, 0.0);
            // The landing gets the full kit: the dive is the move players will remember from the
            // fight above, and a body arriving from the ceiling should not read as a puff of smoke.
            ring(end, to, 12.0, 56, ParticleTypes.SOUL_FIRE_FLAME, 1.5, 0.05);
            spend(end, ParticleTypes.WHITE_ASH, to.x, to.y + 2.0, to.z, 90, 10.0, 3.0, 10.0, 0.05);
            flash(end, to, 0x8844CC);
            nova(end, to, 24.0, ParticleTypes.SOUL_FIRE_FLAME, 0x8844CC);
            end.playSound(null, to.x, to.y, to.z, SoundEvents.ENDER_DRAGON_GROWL, SoundSource.HOSTILE, 7.0F, 0.6F);
            end.playSound(null, to.x, to.y, to.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 3.5F, 0.5F);
         }
         case DRAGON_RAM -> {
            // The dragon flies *into* the player: the body is put on top of the target and the room
            // standing there is thrown backwards off the floor. Unlike the dive this is not a landing
            // - it is a hit-and-run through a nominated body, so the pay is knockback rather than a
            // crater, and the answer is a sideways step rather than distance.
            ServerPlayer target = nearest(end, at, 80.0);
            Vec3 to = target == null ? at : target.position().add(0.0, 2.0, 0.0);
            for (double d = 0.0; d <= 1.0; d += 0.04) {
               Vec3 point = at.lerp(to, d);
               spend(end, ParticleTypes.SOUL_FIRE_FLAME, point.x, point.y, point.z, 10, 1.4, 1.4, 1.4, 0.06);
               spend(end, ParticleTypes.REVERSE_PORTAL, point.x, point.y, point.z, 8, 1.2, 1.2, 1.2, 0.04);
            }
            dragon.teleportTo(to.x, Math.max(60.0, to.y), to.z);
            dragon.setDeltaMovement(Vec3.ZERO);
            dragon.hurtMarked = true;
            Vec3 hit = dragon.position();
            flash(end, hit, 0xCC66FF);
            nova(end, hit, 20.0, ParticleTypes.SOUL_FIRE_FLAME, 0xCC66FF);
            ring(end, hit, 10.0, 48, ParticleTypes.GUST, 0.0, 0.08);
            spend(end, ParticleTypes.GUST, hit.x, hit.y, hit.z, 200, 11.0, 3.0, 11.0, 0.0);
            spend(end, ParticleTypes.WHITE_ASH, hit.x, hit.y, hit.z, 80, 9.0, 3.0, 9.0, 0.05);
            for (ServerPlayer p : playersWithin(end, hit, 14.0)) {
               Vec3 away = p.position().subtract(hit);
               Vec3 flat = away.lengthSqr() < 1.0 ? flatLook(dragon) : new Vec3(away.x, 0.0, away.z).normalize();
               p.push(flat.x * 3.0, 0.55, flat.z * 3.0);
               p.hurtMarked = true;
               p.hurtServer(end, end.damageSources().mobAttack(dragon), wound(26.0F));
               p.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 40, 0));
            }
            end.playSound(null, hit.x, hit.y, hit.z, SoundEvents.ENDER_DRAGON_GROWL, SoundSource.HOSTILE, 9.0F, 0.45F);
            end.playSound(null, hit.x, hit.y, hit.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 6.0F, 0.5F);
         }
         case END_STORM -> {
            for (int i = 0; i < 8; i++) {
               double angle = (Math.PI * 2.0) * i / 8.0;
               Vec3 spot = new Vec3(Math.cos(angle) * 26.0, 64.0, Math.sin(angle) * 26.0);
               rift(end, spot, 1.8);
               visualLightning(end, spot.x, 74.0, spot.z);
               bolt(end, dragon, spot.add(0.0, 22.0, 0.0), new Vec3(0.0, -1.25, 0.0), 70, 7.0F, 3.0);
               hurtNear(end, spot, 0.0, 5.5, dragon, 8.0F, "the storm");
            }
            ring(end, new Vec3(0.5, 65.0, 0.5), 34.0, 72, ParticleTypes.WHITE_ASH, 1.4, 0.04);
            levelThunder(end, at);
            nova(end, at, 30.0, ParticleTypes.WHITE_ASH, 0xCCBBFF);
         }
         case ENDER_LASH -> {
            // Everything close, thrown outward and up - the one move in phase one that can be
            // dodged by nothing at all if you are standing in the wrong place when it starts.
            for (ServerPlayer p : playersWithin(end, at, 11.5)) {
               double dist = Math.sqrt(p.distanceToSqr(at));
               if (dist < 3.0) {
                  continue;
               }
               Vec3 out = new Vec3(p.getX() - at.x, 0.0, p.getZ() - at.z).normalize().scale(1.8);
               p.push(out.x, 0.65, out.z);
               p.hurtMarked = true;
               p.hurtServer(end, end.damageSources().mobAttack(dragon), wound(12.0F));
            }
            for (double a = 0.0; a < 360.0; a += 6.0) {
               double rad = Math.toRadians(a);
               for (double r = 3.5; r <= 12.0; r += 1.2) {
                  spend(end, 
                     ParticleTypes.SWEEP_ATTACK, at.x + Math.cos(rad) * r, at.y + 0.9 + r * 0.05, at.z + Math.sin(rad) * r,
                     2, 0.08, 0.08, 0.08, 0.02
                  );
               }
            }
            end.playSound(null, at.x, at.y, at.z, SoundEvents.ENDER_DRAGON_FLAP, SoundSource.HOSTILE, 6.0F, 0.45F);
            nova(end, at, 14.0, ParticleTypes.END_ROD, 0xAA66FF);
         }
         case VOID_COLLAPSE -> {
            // The hole closes: everybody inside it is dragged in first, then the middle goes.
            Vec3 centre = new Vec3(0.5, 65.0, 0.5);
            for (ServerPlayer p : end.getPlayers(pl -> !pl.isSpectator())) {
               Vec3 pull = centre.subtract(p.position());
               if (pull.lengthSqr() > 1.0) {
                  Vec3 unit = new Vec3(pull.x, 0.0, pull.z).normalize().scale(1.15);
                  p.push(unit.x, 0.18, unit.z);
                  p.hurtMarked = true;
               }
            }
            for (int i = 0; i < 4; i++) {
               double r = 8.0 + i * 2.0;
               ring(end, new Vec3(0.5, 65.0, 0.5), r, 48, ParticleTypes.REVERSE_PORTAL, -0.8, 0.0);
            }
            spend(end, ParticleTypes.PORTAL, 0.5, 65.0, 0.5, 700, 6.0, 5.0, 6.0, 0.9);
            spend(end, ParticleTypes.EXPLOSION_EMITTER, 0.5, 65.0, 0.5, 10, 3.0, 4.0, 3.0, 0.0);
            spend(end, ParticleTypes.SCULK_SOUL, 0.5, 65.0, 0.5, 200, 7.0, 6.0, 7.0, 0.15);
            hurtNear(end, centre, 0.0, 9.0, dragon, 11.0F, "the collapse");
            end.playSound(null, 0.5, 65.0, 0.5, SoundEvents.END_PORTAL_SPAWN, SoundSource.HOSTILE, 6.0F, 0.5F);
            end.playSound(null, 0.5, 65.0, 0.5, SoundEvents.WARDEN_SONIC_BOOM, SoundSource.HOSTILE, 4.0F, 0.5F);
            nova(end, centre, 20.0, ParticleTypes.REVERSE_PORTAL, 0x8844CC);
            for (ServerPlayer p : playersWithin(end, at, 60.0)) {
               p.sendOverlayMessage(Component.literal("\u00a75\u00a7lTHE VOID CLOSES \u00a78| \u00a7fout of the middle"));
            }
         }
         case CRYSTAL_BLOOM -> {
            // The bloom is a real thing on the field: vanilla end crystals, raised around the
            // arena, which fire at whoever is standing nearest and heal the dragon while they
            // live. Capped by counting what is already standing, so the room cannot be buried.
            int alive = crystals(end).size();
            int raise = Math.max(0, Math.min(4, 6 - alive));
            int raised = 0;
            for (int i = 0; i < raise; i++) {
               double angle = (Math.PI * 2.0) * i / Math.max(1, raise);
               double x = Math.cos(angle) * 22.0;
               double z = Math.sin(angle) * 22.0;
               try {
                  net.minecraft.world.entity.boss.enderdragon.EndCrystal bloom =
                     EntityTypes.END_CRYSTAL.create(end, EntitySpawnReason.EVENT);
                  if (bloom != null) {
                     bloom.setPos(x, 78.0, z);
                     bloom.setInvulnerable(false);
                     end.addFreshEntity(bloom);
                     raised++;
                     Vec3 spot = new Vec3(x, 78.0, z);
                     pillar(end, spot, 22.0, ParticleTypes.END_ROD);
                     nova(end, spot, 8.0, ParticleTypes.FIREWORK, 0xFF66FF);
                  }
               } catch (Throwable t) {
                  LOGGER.error("Ender dragon rework: a crystal bloom could not be raised", t);
               }
            }
            spend(end, ParticleTypes.FIREWORK, at.x, at.y, at.z, 120, 8.0, 4.0, 8.0, 0.2);
            end.playSound(null, at.x, at.y, at.z, SoundEvents.BEACON_ACTIVATE, SoundSource.HOSTILE, 5.0F, 0.6F);
            for (ServerPlayer p : playersWithin(end, at, 60.0)) {
               p.sendOverlayMessage(Component.literal("\u00a7d\u00a7lCRYSTAL BLOOM \u00a78| \u00a7f" + raised + " new crystals - shoot them"));
            }
         }
         case END_ECLIPSE -> {
            // The room goes dark, and then the dark has edges: converging rifts that strike in a
            // shrinking band, which is a move about keeping track of where the light is.
            for (ServerPlayer p : playersWithin(end, at, 70.0)) {
               p.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 160, 0));
               p.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 80, 0));
               p.sendOverlayMessage(Component.literal("\u00a75\u00a7lTHE LIGHT IS GOING OUT"));
            }
            for (int i = 0; i < 14; i++) {
               double angle = (Math.PI * 2.0) * i / 14.0;
               double r = 30.0 - i * 1.6;
               Vec3 spot = new Vec3(Math.cos(angle) * r, 65.0, Math.sin(angle) * r);
               rift(end, spot, 1.2);
               hurtNear(end, spot, 0.0, 4.0, dragon, 7.0F, "the eclipse");
            }
            spend(end, ParticleTypes.SMOKE, 0.5, 70.0, 0.5, 400, 22.0, 10.0, 22.0, 0.1);
            spend(end, ParticleTypes.SCULK_SOUL, 0.5, 68.0, 0.5, 300, 20.0, 10.0, 20.0, 0.12);
            spend(end, ParticleTypes.REVERSE_PORTAL, 0.5, 66.0, 0.5, 500, 18.0, 8.0, 18.0, -0.5);
            end.playSound(null, 0.5, 68.0, 0.5, SoundEvents.WITHER_SPAWN, SoundSource.HOSTILE, 5.0F, 0.4F);
            nova(end, new Vec3(0.5, 66.0, 0.5), 26.0, ParticleTypes.SCULK_SOUL, 0x220033);
         }
         case ABYSSAL_ROAR -> {
            // A shout that takes the floor away from under the room: everyone in range is thrown
            // upward as well as hurt, so the answer is the ring rather than the distance.
            for (ServerPlayer p : playersWithin(end, at, 30.0)) {
               p.push(0.0, 1.45, 0.0);
               p.hurtMarked = true;
               p.hurtServer(end, end.damageSources().mobAttack(dragon), 7.0F);
               p.addEffect(new MobEffectInstance(MobEffects.WITHER, 100, 1));
               p.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 40, 0));
            }
            for (int i = 0; i < 3; i++) {
               ring(end, at.add(0.0, i * 1.2, 0.0), 8.0 + i * 8.0, 64, ParticleTypes.SONIC_BOOM, 1.8, 0.03);
            }
            spend(end, ParticleTypes.GUST_EMITTER_LARGE, at.x, at.y, at.z, 8, 8.0, 4.0, 8.0, 0.0);
            spend(end, ParticleTypes.GUST, at.x, at.y + 1.0, at.z, 200, 18.0, 4.0, 18.0, 0.0);
            spend(end, ParticleTypes.WHITE_ASH, at.x, at.y + 1.0, at.z, 200, 20.0, 3.0, 20.0, 0.05);
            end.playSound(null, at.x, at.y, at.z, SoundEvents.ENDER_DRAGON_GROWL, SoundSource.HOSTILE, 9.0F, 0.4F);
            end.playSound(null, at.x, at.y, at.z, SoundEvents.WARDEN_SONIC_BOOM, SoundSource.HOSTILE, 5.0F, 0.4F);
            nova(end, at, 32.0, ParticleTypes.SONIC_BOOM, 0x66FFEE);
         }
         case DRAGON_FIREBALL -> {
            // Vanilla's own projectile, thrown from the mouth: a real body that flies across the
            // island, leaves vanilla's own breath puddle where it lands, and can be seen coming
            // from the far side of the arena. The counter is movement and cover, which is why a
            // ranged move is worth having at all in a fight built out of areas.
            int thrown = 0;
            for (ServerPlayer p : playersWithin(end, at, 80.0)) {
               if (thrown >= 4) {
                  break;
               }
               if (!throwFireball(end, dragon, p)) {
                  continue;
               }
               thrown++;
            }
            if (thrown == 0) {
               // Nobody to aim at: the fireball is still thrown, so the move is a move rather than
               // a silent no-op that leaves the room waiting for something that never comes.
               throwFireball(end, dragon, null);
            }
            nova(end, at, 16.0, BREATH, 0x8844CC);
            end.playSound(null, at.x, at.y, at.z, SoundEvents.ENDER_DRAGON_SHOOT, SoundSource.HOSTILE, 5.0F, 0.6F);
            for (ServerPlayer p : playersWithin(end, at, 60.0)) {
               p.sendOverlayMessage(Component.literal("\u00a75\u00a7lDRAGON FIREBALL \u00a78| \u00a7f" + thrown + " in the air - keep moving"));
            }
         }
         case END_SHOCKWAVE -> {
            // The floor, taken away radially: three rings walking out of the body, each one
            // thicker and further than the last, and the answer is the space between them.
            for (int i = 0; i < 3; i++) {
               double radius = 10.0 + i * 9.0;
               ring(end, at.add(0.0, i * 0.8, 0.0), radius, 72, ParticleTypes.SONIC_BOOM, 2.0, 0.03);
               ring(end, at.add(0.0, i * 0.8, 0.0), radius, 72, ParticleTypes.CLOUD, 1.6, 0.05);
               spend(end, ParticleTypes.GUST_EMITTER_LARGE, at.x, at.y, at.z, 2, radius * 0.4, 1.5, radius * 0.4, 0.0);
               hurtNear(end, at, radius - 4.0, radius + 3.0, dragon, 9.0F, "the shockwave");
            }
            spend(end, ParticleTypes.WHITE_ASH, at.x, at.y + 1.0, at.z, 180, 26.0, 3.0, 26.0, 0.06);
            end.playSound(null, at.x, at.y, at.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 6.0F, 0.5F);
            end.playSound(null, at.x, at.y, at.z, SoundEvents.WARDEN_SONIC_BOOM, SoundSource.HOSTILE, 4.0F, 0.6F);
            nova(end, at, 30.0, ParticleTypes.CLOUD, 0x66FFEE);
         }
         case LOST_PROTECTORS -> summonProtectors(end, dragon);
         case END_ISLAND_PROTECTOR -> summonCreeperProtectors(end, dragon);
         case AURA_OF_THE_END -> {
            // A place rather than a blow: for eight seconds the space around the body is the
            // attack, and being near the dragon is the mistake. Nothing here needs a target, which
            // is what makes it the move that punishes standing under it.
            auraFieldTicks = AURA_FIELD_TICKS;
            Vec3 around = dragon.position();
            ring(end, around, 12.0, 56, ParticleTypes.SOUL, 0.6, 0.0);
            spend(end, ParticleTypes.PORTAL, around.x, around.y + 2.0, around.z, 260, 13.0, 4.0, 13.0, 0.2);
            spend(end, ParticleTypes.SCULK_SOUL, around.x, around.y + 2.0, around.z, 90, 12.0, 4.0, 12.0, 0.05);
            pillar(end, around.add(0.0, -6.0, 0.0), 26.0, ParticleTypes.REVERSE_PORTAL);
            nova(end, around, 24.0, ParticleTypes.REVERSE_PORTAL, 0x8844CC);
            end.playSound(null, around.x, around.y, around.z, SoundEvents.END_PORTAL_SPAWN, SoundSource.HOSTILE, 5.0F, 0.6F);
            for (ServerPlayer p : playersWithin(end, around, 60.0)) {
               p.sendOverlayMessage(Component.literal("\u00a75\u00a7lAURA OF THE END \u00a78| \u00a7fget out from under it"));
            }
         }
         case VOID_DIVE -> {
            // A dive with the whole body behind it: the dragon leaves, the island is cut on the
            // way down, and the landing is a shockwave rather than a puff. Two dives in the kit,
            // deliberately: this one is aimed at the room and the other one at a person.
            Vec3 aim = at;
            ServerPlayer target = nearest(end, at, 90.0);
            Vec3 to = target == null
               ? new Vec3(0.5, 64.0, 0.5)
               : target.position().add(flatLook(dragon).scale(4.0));
            Vec3 from = aim.add(0.0, 26.0, 0.0);
            for (double d = 0.0; d <= 1.0; d += 0.05) {
               Vec3 point = from.lerp(to, d);
               spend(end, ParticleTypes.PORTAL, point.x, point.y, point.z, 12, 1.6, 1.6, 1.6, 0.2);
               spend(end, ParticleTypes.SOUL_FIRE_FLAME, point.x, point.y, point.z, 8, 1.4, 1.4, 1.4, 0.06);
            }
            dragon.teleportTo(to.x, Math.max(60.0, to.y + 8.0), to.z);
            dragon.setDeltaMovement(Vec3.ZERO);
            dragon.hurtMarked = true;
            for (int i = 0; i < 3; i++) {
               double radius = 6.0 + i * 7.0;
               ring(end, to, radius, 64, ParticleTypes.SOUL_FIRE_FLAME, 1.8, 0.04);
               hurtNear(end, to, radius - 3.5, radius + 2.5, dragon, 12.0F, "the dive");
            }
            spend(end, ParticleTypes.EXPLOSION_EMITTER, to.x, to.y, to.z, 18, 4.0, 3.0, 4.0, 0.0);
            spend(end, ParticleTypes.GUST, to.x, to.y, to.z, 180, 12.0, 4.0, 12.0, 0.0);
            end.playSound(null, to.x, to.y, to.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 5.0F, 0.45F);
            end.playSound(null, to.x, to.y, to.z, SoundEvents.ENDER_DRAGON_GROWL, SoundSource.HOSTILE, 8.0F, 0.5F);
            nova(end, to, 30.0, ParticleTypes.SOUL_FIRE_FLAME, 0x8844CC);
         }
         case STARFALL -> {
            // Ranged, from above and everywhere: a curtain of falling fire, one column per player,
            // which is the answer to a room that has learned to spread out.
            for (ServerPlayer p : playersWithin(end, at, 90.0)) {
               for (int i = 0; i < 3; i++) {
                  double x = p.getX() + (RANDOM.nextDouble() - 0.5) * 14.0;
                  double z = p.getZ() + (RANDOM.nextDouble() - 0.5) * 14.0;
                  bolt(end, dragon, new Vec3(x, 100.0, z), new Vec3(0.0, -1.6, 0.0), 90, 8.0F, 3.2);
                  beam(end, new Vec3(x, 100.0, z), new Vec3(x, 64.0, z), ParticleTypes.SOUL_FIRE_FLAME, 1.6);
                  beam(end, new Vec3(x, 100.0, z), new Vec3(x, 64.0, z), ParticleTypes.END_ROD, 1.2);
               }
               rift(end, p.position(), 1.0);
            }
            spend(end, ParticleTypes.WHITE_ASH, 0.5, 80.0, 0.5, 260, 40.0, 20.0, 40.0, 0.05);
            spend(end, ParticleTypes.END_ROD, 0.5, 82.0, 0.5, 160, 36.0, 18.0, 36.0, 0.4);
            end.playSound(null, at.x, at.y, at.z, SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.WEATHER, 5.0F, 0.55F);
            nova(end, at, 24.0, ParticleTypes.END_ROD, 0xCCBBFF);
         }
         case DRAGON_CHARGE -> {
            // Vanilla's own ram, given a body and a corridor. The dragon leaves, re-enters on the
            // line and arrives as a shockwave - and everything along the line is hit, because there
            // is nothing between the body and the landing point to hide behind.
            Vec3 aim = chargeAim(end, dragon);
            Vec3 unit = chargeUnit(at, aim);
            Vec3 from = chargeStart(aim, unit);
            for (double d = 0.0; d <= CHARGE_LENGTH; d += 1.0) {
               Vec3 point = from.add(unit.scale(d));
               spend(end, ParticleTypes.CLOUD, point.x, point.y + 0.8, point.z, 5, 1.2, 0.8, 1.2, 0.1);
               spend(end, ParticleTypes.GUST, point.x, point.y + 0.8, point.z, 3, 0.8, 0.5, 0.8, 0.0);
            }
            dragon.teleportTo(from.x, Math.max(60.0, from.y + 1.0), from.z);
            dragon.setDeltaMovement(unit.scale(3.2));
            dragon.hurtMarked = true;
            for (ServerPlayer p : playersWithin(end, aim, CHARGE_LENGTH + 6.0)) {
               Vec3 rel = p.position().subtract(from);
               double along = rel.dot(unit);
               if (along < 0.0 || along > CHARGE_LENGTH) {
                  continue;
               }
               if (p.position().distanceTo(from.add(unit.scale(along))) > CHARGE_WIDTH) {
                  continue;
               }
               Vec3 shove = unit.scale(2.6).add(0.0, 0.7, 0.0);
               p.push(shove.x, shove.y, shove.z);
               p.hurtMarked = true;
               if (harnessMode) {
                  continue;
               }
               p.hurtServer(end, end.damageSources().mobAttack(dragon), CHARGE_DAMAGE);
            }
            nova(end, aim, 26.0, ParticleTypes.CLOUD, 0xAA66FF);
            end.playSound(null, aim.x, aim.y, aim.z, SoundEvents.ENDER_DRAGON_GROWL, SoundSource.HOSTILE, 8.0F, 0.5F);
            end.playSound(null, aim.x, aim.y, aim.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 5.0F, 0.5F);
         }
         case ENDER_FANGS -> {
            // Teeth, not a wave: the cone in front of the mouth erupts, and the answer is behind the
            // dragon. It is the one move in the island's kit that punishes standing *in front of* it
            // rather than standing still, which is what a room that has learned to spread out needs.
            Vec3 mouth = mouthOf(dragon);
            Vec3 look = flatLook(dragon);
            Vec3 side = new Vec3(-look.z, 0.0, look.x);
            for (ServerPlayer p : playersWithin(end, at, 30.0)) {
               Vec3 to = p.position().subtract(mouth);
               double forward = to.dot(look);
               if (forward <= 0.0 || forward > 18.0) {
                  continue;
               }
               if (Math.abs(to.dot(side)) > forward * 0.62 + 1.6) {
                  continue;
               }
               if (harnessMode) {
                  continue;
               }
               p.hurtServer(end, end.damageSources().mobAttack(dragon), 10.0F);
            }
            nova(end, mouth, 14.0, ParticleTypes.SOUL_FIRE_FLAME, 0x8844CC);
            end.playSound(null, mouth.x, mouth.y, mouth.z, SoundEvents.EVOKER_FANGS_ATTACK, SoundSource.HOSTILE, 5.0F, 0.8F);
         }
         case SOUL_VORTEX -> {
            // The spin, applied. Players in the band are thrown *around* the body rather than into
            // it, so the move is answered by the eye (get underneath) or by leaving - the two
            // options the shape drew, and the reverse of every other crowd move in the kit.
            double spin = (ServerClock.clock(end) % 40L) >= 20L ? 1.0 : -1.0;
            for (ServerPlayer p : playersWithin(end, at, 15.0)) {
               Vec3 flat = new Vec3(p.getX() - at.x, 0.0, p.getZ() - at.z);
               if (flat.lengthSqr() < 4.0) {
                  continue;
               }
               Vec3 unit = flat.normalize();
               Vec3 tangent = new Vec3(-unit.z * spin, 0.0, unit.x * spin);
               p.push(tangent.x * 2.4 + unit.x * 0.35, 0.45, tangent.z * 2.4 + unit.z * 0.35);
               p.hurtMarked = true;
               if (harnessMode) {
                  continue;
               }
               p.hurtServer(end, end.damageSources().magic(), 9.0F);
            }
            nova(end, at, 22.0, ParticleTypes.SOUL, 0x8844CC);
            end.playSound(null, at.x, at.y, at.z, SoundEvents.SCULK_SHRIEKER_SHRIEK, SoundSource.HOSTILE, 4.0F, 0.6F);
         }
         case VOID_GRASP -> {
            // Taken up and let go. The damage is small because the *fall* is the move: levitation
            // without slow falling, so where the thread finds you standing is the whole question.
            for (ServerPlayer p : playersWithin(end, at, 90.0)) {
               if (harnessMode) {
                  continue;
               }
               p.addEffect(new net.minecraft.world.effect.MobEffectInstance(
                  net.minecraft.world.effect.MobEffects.LEVITATION, 40, 2
               ));
               p.hurtServer(end, end.damageSources().indirectMagic(dragon, dragon), 3.0F);
            }
            spend(end, ParticleTypes.SOUL_FIRE_FLAME, at.x, at.y + 2.0, at.z, 120, 10.0, 6.0, 10.0, 0.5);
            nova(end, at, 18.0, ParticleTypes.SCULK_SOUL, 0x66FFEE);
            end.playSound(null, at.x, at.y, at.z, SoundEvents.EVOKER_CAST_SPELL, SoundSource.HOSTILE, 5.0F, 0.5F);
         }
         case METEOR_SHOWER -> {
            // A shower rather than a strike: impacts scattered across the whole island, each with
            // its own light, so nowhere is simply safe. Deliberately not aimed at players - the
            // answer to "everywhere" is timing, and there is already a move that answers
            // "standing still" by landing on the player.
            for (int i = 0; i < METEOR_COUNT; i++) {
               double x = (RANDOM.nextDouble() - 0.5) * 84.0;
               double z = (RANDOM.nextDouble() - 0.5) * 84.0;
               double y = Math.max(63.0, end.getHeight(
                  net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, (int)x, (int)z
               ) + 1.0);
               Vec3 hit = new Vec3(x, y, z);
               spend(end, ParticleTypes.EXPLOSION_EMITTER, x, y, z, 1, 0.0, 0.0, 0.0, 0.0);
               ring(end, hit, 5.0, 28, ParticleTypes.SOUL_FIRE_FLAME, 0.9, 0.05);
               ring(end, hit, 3.0, 20, ParticleTypes.GUST, 0.0, 0.05);
               spend(end, ParticleTypes.FIREWORK, x, y + 1.0, z, 18, 1.4, 1.4, 1.4, 0.12);
               spend(end, ParticleTypes.WHITE_ASH, x, y + 3.0, z, 12, 2.5, 1.5, 2.5, 0.02);
               hurtNear(end, hit, 0.0, 5.0, dragon, 9.0F, "a meteor");
            }
            spend(end, ParticleTypes.WHITE_ASH, 0.5, 96.0, 0.5, 140, 44.0, 12.0, 44.0, 0.02);
            end.playSound(null, at.x, at.y, at.z, SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.WEATHER, 6.0F, 0.5F);
            nova(end, at, 26.0, ParticleTypes.SOUL_FIRE_FLAME, 0xFF8844);
         }
         case ENDER_TEMPEST -> {
            // The body throws a storm off itself in every direction at once. The answer is distance
            // rather than a direction: there is no side of this to stand on, only outside it.
            for (int i = 0; i < 10; i++) {
               double angle = i * (Math.PI / 5.0);
               beam(end, at, at.add(Math.cos(angle) * 15.0, (RANDOM.nextDouble() - 0.5) * 3.0, Math.sin(angle) * 15.0), ParticleTypes.END_ROD, 1.4);
            }
            ring(end, at, 12.0, 56, ParticleTypes.END_ROD, 1.6, 0.05);
            ring(end, at, 8.0, 44, ParticleTypes.REVERSE_PORTAL, -1.1, 0.0);
            ring(end, at, 15.0, 64, ParticleTypes.GUST, 0.0, 0.02);
            spend(end, ParticleTypes.GUST_EMITTER_LARGE, at.x, at.y, at.z, 4, 4.0, 2.0, 4.0, 0.0);
            spend(end, ParticleTypes.ELECTRIC_SPARK, at.x, at.y + 1.5, at.z, 60, 9.0, 3.0, 9.0, 0.9);
            nova(end, at, 16.0, ParticleTypes.END_ROD, 0xAA66FF);
            hurtNear(end, at, 3.0, 12.0, dragon, 10.0F, "the tempest");
            end.playSound(null, at.x, at.y, at.z, SoundEvents.ENDER_DRAGON_FLAP, SoundSource.HOSTILE, 5.0F, 0.55F);
         }
         case VOID_TENDRILS -> {
            // A thread onto every body in the room, all of them pulling at once: the grasp takes you
            // up, and this one takes the health, and the shape is a line you can see you are on.
            for (ServerPlayer p : playersWithin(end, at, 70.0)) {
               Vec3 feet = p.position();
               beam(end, at, feet.add(0.0, 1.0, 0.0), ParticleTypes.PORTAL, 0.6);
               beam(end, feet.add(0.0, 22.0, 0.0), feet, ParticleTypes.REVERSE_PORTAL, 0.8);
               ring(end, feet, 2.0, 18, ParticleTypes.SOUL_FIRE_FLAME, 0.4, 0.3);
               if (harnessMode || p.isCreative() || p.isSpectator()) {
                  continue;
               }
               p.hurtServer(end, end.damageSources().indirectMagic(dragon, dragon), 7.0F);
               p.sendOverlayMessage(Component.literal("\u00a75A tendril has hold of you"));
            }
            spend(end, ParticleTypes.PORTAL, at.x, at.y + 2.0, at.z, 90, 6.0, 3.0, 6.0, 0.5);
            nova(end, at, 12.0, ParticleTypes.REVERSE_PORTAL, 0xCC66FF);
            end.playSound(null, at.x, at.y, at.z, SoundEvents.ENDERMAN_SCREAM, SoundSource.HOSTILE, 4.5F, 0.6F);
         }
         case GALAXY_COLLAPSE -> {
            // The End's own weight turned inside out: everything within thirty blocks is dragged at
            // the body, and then the body answers. The pull is the read and the nova is the bill.
            for (ServerPlayer p : playersWithin(end, at, 30.0)) {
               Vec3 pull = at.subtract(p.position());
               if (pull.lengthSqr() > 0.01) {
                  pull = pull.normalize().scale(0.9);
                  p.push(pull.x, 0.25, pull.z);
                  p.hurtMarked = true;
               }
            }
            for (int i = 0; i < 44; i++) {
               double angle = RANDOM.nextDouble() * Math.PI * 2.0;
               double r = 30.0 - RANDOM.nextDouble() * 12.0;
               Vec3 from = at.add(Math.cos(angle) * r, (RANDOM.nextDouble() - 0.5) * 12.0, Math.sin(angle) * r);
               beam(end, from, at, ParticleTypes.REVERSE_PORTAL, 1.1);
            }
            ring(end, at, 30.0, 72, ParticleTypes.REVERSE_PORTAL, -1.2, 0.0);
            spend(end, ParticleTypes.END_ROD, at.x, at.y + 1.0, at.z, 220, 3.0, 3.0, 3.0, 0.7);
            pillar(end, at, 22.0, ParticleTypes.END_ROD);
            nova(end, at, 30.0, ParticleTypes.END_ROD, 0xC700FF);
            nova(end, at.add(0.0, 4.0, 0.0), 18.0, ParticleTypes.PORTAL, 0xAA66FF);
            hurtNear(end, at, 0.0, 13.0, dragon, 14.0F, "the collapse");
            end.playSound(null, at.x, at.y, at.z, SoundEvents.WITHER_SPAWN, SoundSource.HOSTILE, 5.0F, 0.5F);
         }
         case SLAM -> beginSlam(end, dragon);
         case SKY_RING -> beginSkyRing(end, dragon);
         case ASCENDANT_BLINK -> beginBlinkChain(end, dragon);
         case END_ROD_SPIKES -> {
            // Spikes under every body, and under the body itself when it is perching - which is the
            // moment the pack reaches for this move, because a dragon on the ground has lost the one
            // thing that normally keeps players away from it: altitude.
            for (ServerPlayer p : playersWithin(end, at, 90.0)) {
               spikeField(end, p.position(), 0.0);
               if (harnessMode) {
                  continue;
               }
               p.hurtServer(end, end.damageSources().mobAttack(dragon), SPIKE_DAMAGE);
            }
            if (dragonIsPerching(dragon)) {
               spikeField(end, at, 0.0);
               hurtNear(end, at, 2.0, 12.0, dragon, SPIKE_DAMAGE, "the spikes");
            } else {
               nova(end, at, 16.0, ParticleTypes.END_ROD, 0xCCBBFF);
            }
            end.playSound(null, at.x, at.y, at.z, SoundEvents.EVOKER_FANGS_ATTACK, SoundSource.HOSTILE, 6.0F, 0.8F);
         }
         case SHULKER_BULLETS -> {
            // Twelve of vanilla's own bullets, or - if the air is already full of them - the spikes.
            // A fallback rather than a second volley, because a wall of homing bullets is not a
            // harder fight, it is a fight with no answer in it.
            if (!summonShulkerBullets(end, dragon)) {
               for (ServerPlayer p : playersWithin(end, at, 90.0)) {
                  spikeField(end, p.position(), 0.0);
                  if (harnessMode) {
                     continue;
                  }
                  p.hurtServer(end, end.damageSources().mobAttack(dragon), SPIKE_DAMAGE);
               }
               end.playSound(null, at.x, at.y, at.z, SoundEvents.EVOKER_FANGS_ATTACK, SoundSource.HOSTILE, 6.0F, 0.8F);
            }
         }
         case ZERO_GRAVITY -> {
            // Up, and then down. Two beats in one move, on the same tick-tock the lance uses: the
            // first half takes the room's weight away, the second half gives it back all at once.
            zeroGTicks = ZERO_G_LIFT_TICKS + ZERO_G_DROP_TICKS;
            spend(end, ParticleTypes.GUST, at.x, at.y, at.z, 200, 14.0, 6.0, 14.0, 0.0);
            end.playSound(null, at.x, at.y, at.z, SoundEvents.END_PORTAL_SPAWN, SoundSource.HOSTILE, 5.0F, 0.5F);
            end.playSound(null, at.x, at.y, at.z, SoundEvents.WARDEN_SONIC_CHARGE, SoundSource.HOSTILE, 4.0F, 0.6F);
         }
         case SUPERNOVA -> {
            // The ring is the move: the middle survives and the band does not, which is the one
            // arrangement no other attack in the kit uses. It also means the biggest blow in the
            // fight has a counter that is not "run away", which is what makes it a set piece
            // rather than a wall of particle spam.
            for (int i = 0; i < 4; i++) {
               double radius = SUPERNOVA_EYE + (SUPERNOVA_BAND - SUPERNOVA_EYE) * (i + 1) / 4.0;
               ring(end, at, radius, 72, ParticleTypes.SOUL_FIRE_FLAME, 1.6, 0.06);
               ring(end, at, radius, 72, ParticleTypes.GUST, 0.0, 0.1);
            }
            hurtNear(end, at, SUPERNOVA_EYE, SUPERNOVA_BAND + 6.0, dragon, 18.0F, "the supernova");
            spend(end, ParticleTypes.EXPLOSION_EMITTER, at.x, at.y, at.z, 20, 6.0, 5.0, 6.0, 0.0);
            spend(end, ParticleTypes.GUST, at.x, at.y, at.z, 220, 26.0, 8.0, 26.0, 0.0);
            spend(end, ParticleTypes.END_ROD, at.x, at.y, at.z, 240, 24.0, 8.0, 24.0, 0.6);
            end.playSound(null, at.x, at.y, at.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 8.0F, 0.4F);
            end.playSound(null, at.x, at.y, at.z, SoundEvents.WITHER_SPAWN, SoundSource.HOSTILE, 5.0F, 0.5F);
            nova(end, at, 34.0, ParticleTypes.END_ROD, 0xFF66FF);
         }
      }
   }

   // ------------------------------------------------------------------ the lasting moves

   /**
    * Every lasting move stopped at once.
    *
    * <p>Five of them now, and every one is a state a fight can be left standing inside: a body on
    * its way into the floor, a room on its way up, a ring of lightning with nobody left to strike,
    * a chain of teleports that would carry on over an ending, and an ascension that would leave the
    * dragon untouchable with no fight to be untouchable in. A loop stop, a finale and a death
    * ceremony each want all five gone, and they want it in one place - five fields cleared at three
    * call sites is exactly how the fourth site is forgotten.
    */
   private static void stopLastingMoves() {
      slamTicks = 0;
      slamGrounded = false;
      zeroGTicks = 0;
      skyRingTicks = 0;
      skyRingWave = 0;
      blinkTicks = 0;
      blinkGap = 0;
      ascensionTicks = 0;
   }

   /**
    * The stomp: the dragon puts itself on the floor and hits it.
    *
    * <p>The one phase-one move that is <b>about</b> the ground rather than about the air. It flies
    * over whoever it picked, drops onto them, and the landing is a shockwave and a body you can
    * hit - the same bargain the slam strikes in the later phases, offered early so the room learns
    * it while the fight is still survivable. It ends in the same grounded hold, because a landing
    * that does not last is a teleport with particles.
    */
   private static void stompTheGround(ServerLevel end, EnderDragon dragon) {
      Vec3 at = dragon.position();
      ServerPlayer target = nearest(end, at, 80.0);
      if (target != null) {
         // Straight down onto the body it picked: one move of the body, not two - the body only has
         // to arrive, and a hop into the air first would be a second teleport to sync for nothing.
         at = new Vec3(target.getX(), Math.max(76.0, at.y), target.getZ());
      }
      double ground = groundBelow(end, at);
      Vec3 hit = new Vec3(at.x, ground + 0.4, at.z);
      spend(end, ParticleTypes.GUST_EMITTER_LARGE, hit.x, hit.y, hit.z, 7, 9.0, 1.0, 9.0, 0.0);
      spend(end, ParticleTypes.CLOUD, hit.x, hit.y, hit.z, 200, 13.0, 2.0, 13.0, 0.2);
      spend(end, ParticleTypes.WHITE_ASH, hit.x, hit.y, hit.z, 120, 13.0, 2.5, 13.0, 0.05);
      ring(end, hit, 11.0, 64, ParticleTypes.GUST, 0.0, 0.06);
      ring(end, hit, 22.0, 80, ParticleTypes.CLOUD, 1.6, 0.05);
      flash(end, hit, 0xCCBBFF);
      nova(end, hit, 22.0, ParticleTypes.CLOUD, 0xCCBBFF);
      hurtNear(end, hit, 0.0, 12.0, dragon, 15.0F, "the stomp");
      end.playSound(null, hit.x, hit.y, hit.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 8.0F, 0.4F);
      end.playSound(null, hit.x, hit.y, hit.z, SoundEvents.ENDER_DRAGON_GROWL, SoundSource.HOSTILE, 7.0F, 0.7F);
      holdTheBodyDown(end, dragon, hit);
   }

   /**
    * The pulse: three rings of the End's light walking out of the body, one at a time.
    *
    * <p>A move whose answer is a position rather than a distance: the bands are the gaps between
    * the rings, so the safe places are between two of them and they move. Nothing about it is
    * aimed, which is what makes it the phase's own kit - the island does not need to see you to
    * hit the floor you are standing on.
    */
   private static void enderPulse(ServerLevel end, EnderDragon dragon) {
      Vec3 at = dragon.position();
      end.playSound(null, at.x, at.y, at.z, SoundEvents.BEACON_POWER_SELECT, SoundSource.HOSTILE, 6.0F, 0.6F);
      flash(end, at, 0xCC66FF);
      double[][] bands = {{7.0, 12.0}, {15.0, 20.0}, {23.0, 28.0}};
      for (int i = 0; i < bands.length; i++) {
         double mid = (bands[i][0] + bands[i][1]) * 0.5;
         ring(end, at, mid, 56, i == 0 ? ParticleTypes.END_ROD : ParticleTypes.REVERSE_PORTAL, 1.7, 0.05);
         ring(end, at, mid, 56, ParticleTypes.WHITE_ASH, 1.7, 0.08);
         hurtNear(end, at, bands[i][0], bands[i][1], dragon, 11.0F, "the pulse");
      }
      nova(end, at, 30.0, ParticleTypes.END_ROD, 0xCC66FF);
   }

   /**
    * Void hail: the hurt comes from above the fog, all at once, onto wherever the room is standing.
    *
    * <p>The counter is movement rather than cover: the volley is aimed at the position each player
    * holds when it is thrown, so a body that is already moving when the light starts falling is
    * already out of it. Straight down, drawn as a line of void light, so the arrival is readable
    * from underneath - which is the only direction this fight has never asked the room to watch.
    */
   private static void voidHail(ServerLevel end, EnderDragon dragon) {
      Vec3 at = dragon.position();
      List<ServerPlayer> room = end.getPlayers(pl -> !pl.isSpectator() && pl.isAlive());
      // Sized against the fight's own bolt cap rather than thrown and dropped: six rounds per head
      // is eighteen rounds in a room of three, and {@link #MAX_BOLTS} would silently eat eight of
      // them - which reads as a move that did not happen for whoever the cap ran out on. Sharing
      // the cap out means the whole room is under the same rain, just thinner.
      int per = room.isEmpty() ? 0 : Math.max(1, Math.min(6, MAX_BOLTS / room.size()));
      for (ServerPlayer p : room) {
         for (int i = 0; i < per; i++) {
            double x = p.getX() + (RANDOM.nextDouble() - 0.5) * 9.0;
            double z = p.getZ() + (RANDOM.nextDouble() - 0.5) * 9.0;
            // No beam drawn down the fall: the bolt is a projectile with a trail of its own, and a
            // second line of particles along the same path was fifty sends a round of the budget
            // this fight spends on everything else for nothing.
            bolt(end, dragon, new Vec3(x, 94.0, z), new Vec3(0.0, -1.45, 0.0), 60, 10.0F, 3.0);
            spend(end, ParticleTypes.REVERSE_PORTAL, x, 90.0, z, 6, 0.6, 1.5, 0.6, -0.4);
         }
      }
      ring(end, new Vec3(at.x, 92.0, at.z), 30.0, 64, ParticleTypes.PORTAL, 1.2, 0.0);
      end.playSound(null, at.x, at.y, at.z, SoundEvents.ENDER_DRAGON_SHOOT, SoundSource.HOSTILE, 6.0F, 0.5F);
      levelThunder(end, at);
   }

   /**
    * Soul eruption: the floor under each player answers, one after another.
    *
    * <p>The move that punishes a plan made in advance - the ground you are standing on is the thing
    * that opens, and it opens where you were when the move was thrown. Being on the move is the
    * answer, and being airborne is the other one, which is the first time this fight has cared
    * where your feet are for a reason other than the void below the island.
    */
   private static void soulEruption(ServerLevel end, EnderDragon dragon) {
      Vec3 at = dragon.position();
      for (ServerPlayer p : end.getPlayers(pl -> !pl.isSpectator() && pl.isAlive())) {
         Vec3 spot = p.position();
         Vec3 onGround = new Vec3(spot.x, groundBelow(end, spot) + 0.3, spot.z);
         // One ring and one burst per head rather than the full nova kit: this is the one move in
         // the fight whose cost scales with the size of the room, and it is the room's own tick
         // budget it would be spending.
         ring(end, onGround, 4.5, 44, ParticleTypes.SOUL_FIRE_FLAME, 1.5, 0.0);
         spend(end, ParticleTypes.SOUL_FIRE_FLAME, onGround.x, onGround.y + 1.0, onGround.z, 90, 2.6, 1.6, 2.6, 0.18);
         spend(end, ParticleTypes.SCULK_SOUL, onGround.x, onGround.y + 0.8, onGround.z, 40, 2.2, 1.2, 2.2, 0.1);
         flash(end, onGround, 0xFF3355);
         hurtNear(end, onGround, 0.0, 5.5, dragon, 16.0F, "the eruption");
         if (!harnessMode) {
            p.setRemainingFireTicks(90);
         }
      }
      end.playSound(null, at.x, at.y, at.z, SoundEvents.SCULK_SHRIEKER_SHRIEK, SoundSource.HOSTILE, 6.0F, 0.5F);
      end.playSound(null, at.x, at.y, at.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 5.0F, 0.6F);
   }

   /**
    * What a dragon leaves behind when it takes off again: a shove and a wound for anyone under it.
    *
    * <p>The other half of "hard": if the window ends silently, the correct play is to stand in it
    * and swing until the clock runs out, and the melee phase becomes a free damage phase with a
    * timer on it. Taking off costs - the wings press the air down and everyone in the ring is
    * thrown off the body and hurt - so the last second of the window is a decision rather than a
    * bonus. Both of the fight's exits use it (the slam's hold and the perch's takeoff), because one
    * window that punishes and one that does not is a rule the room has to learn twice.
    */
   private static void takeOffShockwave(ServerLevel end, EnderDragon dragon) {
      Vec3 at = dragon.position();
      spend(end, ParticleTypes.GUST_EMITTER_LARGE, at.x, at.y, at.z, 4, 3.0, 1.0, 3.0, 0.0);
      spend(end, ParticleTypes.CLOUD, at.x, at.y, at.z, 90, 6.0, 1.5, 6.0, 0.3);
      spend(end, ParticleTypes.GUST, at.x, at.y, at.z, 60, 7.0, 1.2, 7.0, 0.0);
      end.playSound(null, at.x, at.y, at.z, SoundEvents.ENDER_DRAGON_FLAP, SoundSource.HOSTILE, 6.0F, 0.6F);
      if (harnessMode) {
         return;
      }
      for (ServerPlayer p : playersWithin(end, at, 9.0)) {
         Vec3 away = new Vec3(p.getX() - at.x, 0.0, p.getZ() - at.z);
         Vec3 flat = away.lengthSqr() < 1.0 ? flatLook(dragon) : away.normalize();
         p.push(flat.x * 2.2, 0.85, flat.z * 2.2);
         p.hurtMarked = true;
         p.hurtServer(end, end.damageSources().mobAttack(dragon), wound(9.0F));
      }
   }

   /**
    * Put the body on the floor and keep it there for the hold - the fight's melee window.
    *
    * <p>Shared by the slam and the stomp, because "the dragon is down and staying down" is one
    * state with one clock rather than two moves that each invented their own. The body is left to
    * vanilla's own landing so it settles on the fountain the way a dragon does.
    */
   private static void holdTheBodyDown(ServerLevel end, EnderDragon dragon, Vec3 hit) {
      slamHoldSpot = new Vec3(hit.x, Math.max(62.0, hit.y + 2.0), hit.z);
      dragon.teleportTo(slamHoldSpot.x, slamHoldSpot.y, slamHoldSpot.z);
      dragon.setDeltaMovement(Vec3.ZERO);
      dragon.hurtMarked = true;
      hover(dragon, false);
      slamGrounded = true;
      slamTicks = SLAM_DOWN_TICKS;
   }

   /**
    * The slam: the dragon drives its own body into the floor.
    *
    * <p>Borrowed from the pack this fight takes its shared abilities from, where the dragon sets its
    * own downward motion and then reverses it on contact. It is the one move in the kit that is a
    * *body* rather than a shape - the answer is not a ring or a line but the fact that a dragon on
    * the ground is a smaller problem than a dragon in the air, and it cannot reach what is behind it.
    */
   private static void beginSlam(ServerLevel end, EnderDragon dragon) {
      slamTicks = SLAM_TICKS;
      Vec3 at = dragon.position();
      hover(dragon, false);
      // The slam is aimed: the dragon moves over whoever it picked before it drops, so the move is
      // about that player rather than about the patch of floor the dragon happened to be above.
      // Without this it was a move that punished standing still and rewarded standing *anywhere*
      // else, which is the same as having no answer.
      ServerPlayer target = nearest(end, at, 80.0);
      if (target != null) {
         Vec3 over = new Vec3(target.getX(), Math.max(at.y, 76.0), target.getZ());
         dragon.teleportTo(over.x, over.y, over.z);
         dragon.hurtMarked = true;
         at = dragon.position();
         flash(end, at, 0xAA66FF);
         spend(end, ParticleTypes.REVERSE_PORTAL, at.x, at.y, at.z, 70, 2.5, 2.0, 2.5, -0.35);
         spend(end, ParticleTypes.END_ROD, at.x, at.y, at.z, 30, 2.0, 1.5, 2.0, 0.15);
      }
      dragon.setDeltaMovement(0.0, -1.6, 0.0);
      dragon.hurtMarked = true;
      spend(end, ParticleTypes.EXPLOSION_EMITTER, at.x, at.y, at.z, 4, 2.0, 1.0, 2.0, 0.0);
      spend(end, ParticleTypes.CLOUD, at.x, at.y, at.z, 80, 3.0, 1.5, 3.0, 0.2);
      end.playSound(null, at.x, at.y, at.z, SoundEvents.ENDER_DRAGON_GROWL, SoundSource.HOSTILE, 8.0F, 0.5F);
   }

   /** One tick of a slam: falling, then the floor, then the long moment on it. */
   private static void tickSlam(ServerLevel end, EnderDragon dragon) {
      if (slamGrounded) {
         tickSlamDown(end, dragon);
         return;
      }
      slamTicks--;
      Vec3 at = dragon.position();
      double ground = groundBelow(end, at);
      dragon.setDeltaMovement(dragon.getDeltaMovement().x, -1.6, dragon.getDeltaMovement().z);
      dragon.hurtMarked = true;
      spend(end, ParticleTypes.CLOUD, at.x, at.y, at.z, 8, 1.6, 0.8, 1.6, 0.1);
      spend(end, ParticleTypes.WHITE_ASH, at.x, at.y + 1.0, at.z, 6, 2.0, 1.0, 2.0, 0.05);
      if (at.y - ground > 4.0 && slamTicks > 0) {
         return;
      }
      // The floor, whether it got there by falling or by running out of clock - a slam that never
      // landed would leave the dragon in a dive state with the fight's rotation held behind it.
      slamTicks = 0;
      Vec3 hit = new Vec3(at.x, ground + 0.5, at.z);
      spend(end, ParticleTypes.EXPLOSION_EMITTER, hit.x, hit.y, hit.z, 24, 3.0, 1.0, 3.0, 0.0);
      spend(end, ParticleTypes.GUST, hit.x, hit.y, hit.z, 200, 14.0, 2.0, 14.0, 0.0);
      spend(end, ParticleTypes.WHITE_ASH, hit.x, hit.y, hit.z, 160, 14.0, 3.0, 14.0, 0.06);
      spend(end, ParticleTypes.CLOUD, hit.x, hit.y, hit.z, 120, 12.0, 2.0, 12.0, 0.2);
      ring(end, hit, 6.0, 40, ParticleTypes.CLOUD, 1.6, 0.0);
      ring(end, hit, SLAM_RADIUS, 72, ParticleTypes.SOUL_FIRE_FLAME, 1.8, 0.05);
      flash(end, hit, 0xAA66FF);
      nova(end, hit, 26.0, ParticleTypes.CLOUD, 0xAA66FF);
      hurtNear(end, hit, 0.0, SLAM_RADIUS + 4.0, dragon, SLAM_DAMAGE, "the slam");
      end.playSound(null, hit.x, hit.y, hit.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 8.0F, 0.4F);
      end.playSound(null, hit.x, hit.y, hit.z, SoundEvents.DRAGON_FIREBALL_EXPLODE, SoundSource.HOSTILE, 6.0F, 0.5F);
      // And then it <b>stays</b>. The bounce back into the air was the whole problem with the one
      // move that brings the body into reach: the opening arrived and left in the same second. Now
      // the landing is its own beat - no rotation, no new move, the body on the floor - and it is
      // the fight's most reliable melee window.
      slamGrounded = true;
      slamTicks = SLAM_DOWN_TICKS;
      slamHoldSpot = new Vec3(hit.x, Math.max(62.0, hit.y + 2.0), hit.z);
      end.playSound(null, hit.x, hit.y, hit.z, SoundEvents.ENDER_DRAGON_GROWL, SoundSource.HOSTILE, 7.0F, 0.8F);
   }

   /**
    * The dragon on the floor: held there, out of the rotation, and open for the room.
    *
    * <p>The body is left to vanilla's own landing rather than pinned by hand, so it settles on the
    * fountain the way a dragon does; what this clock does is refuse to let the fight move on until
    * it is up. Dust, slow breath and the occasional shudder, because a dragon standing still with no
    * particles reads as a frozen one.
    */
   private static void tickSlamDown(ServerLevel end, EnderDragon dragon) {
      slamTicks--;
      // Held on the spot it landed on. Vanilla's landing AI is still flying the body at the portal
      // every tick underneath this, so the pin is the difference between a dragon that is down and
      // a dragon that is drifting back toward the middle of the island while it is down.
      Vec3 held = slamHoldSpot;
      dragon.setPos(held.x, held.y, held.z);
      dragon.setDeltaMovement(Vec3.ZERO);
      dragon.hurtMarked = true;
      Vec3 at = dragon.position();
      double ground = groundBelow(end, at);
      spend(end, ParticleTypes.CLOUD, at.x, at.y + 0.6, at.z, 6, 2.4, 0.5, 2.4, 0.05);
      spend(end, ParticleTypes.WHITE_ASH, at.x, at.y + 1.4, at.z, 4, 2.6, 1.0, 2.6, 0.03);
      if (slamTicks % 12 == 0) {
         spend(end, ParticleTypes.SOUL_FIRE_FLAME, at.x, at.y + 1.0, at.z, 14, 3.0, 0.8, 3.0, 0.06);
         if (at.y - ground < 6.0) {
            end.playSound(null, at.x, at.y, at.z, SoundEvents.ENDER_DRAGON_AMBIENT, SoundSource.HOSTILE, 3.5F, 0.7F);
         }
      }
      if (slamTicks > 0) {
         return;
      }
      slamGrounded = false;
      dragon.setDeltaMovement(0.0, 1.1, 0.0);
      dragon.hurtMarked = true;
      takeOffShockwave(end, dragon);
      hover(dragon, true);
   }

   /**
    * The weightless beat: the room loses its gravity, and then the floor arrives.
    *
    * <p>Two halves, one clock. The lift is levitation rather than a push, because a push is answered
    * by walking and levitation is answered by *where you were standing*; the drop is a hard downward
    * shove and a wound, so the bill for the free half arrives all at once. The pack does this as
    * "zero gravity, then slam into the ground", and this is the same two beats with the same answer.
    */
   private static void tickZeroG(ServerLevel end, EnderDragon dragon) {
      zeroGTicks--;
      Vec3 at = dragon.position();
      double ground = groundBelow(end, at);
      if (zeroGTicks > ZERO_G_DROP_TICKS) {
         // Still going up. Everybody in the End floats, and the room is drawn as a room that is
         // being poured upward past them.
         spend(end, ParticleTypes.END_ROD, at.x, at.y + 2.0, at.z, 40, 20.0, 8.0, 20.0, 0.6);
         spend(end, ParticleTypes.GUST, at.x, at.y, at.z, 60, 18.0, 6.0, 18.0, 0.0);
         for (ServerPlayer p : end.getPlayers(pl -> !pl.isSpectator() && pl.isAlive())) {
            if (harnessMode) {
               continue;
            }
            p.addEffect(new net.minecraft.world.effect.MobEffectInstance(
               net.minecraft.world.effect.MobEffects.LEVITATION, 12, 3
            ));
         }
         return;
      }
      if (zeroGTicks == ZERO_G_DROP_TICKS) {
         // The first tick of the drop: the whole room is driven down at once.
         for (ServerPlayer p : end.getPlayers(pl -> !pl.isSpectator() && pl.isAlive())) {
            p.push(0.0, -3.4, 0.0);
            p.hurtMarked = true;
            spend(end, ParticleTypes.CLOUD, p.getX(), p.getY(), p.getZ(), 12, 1.2, 1.2, 1.2, 0.2);
         }
         end.playSound(null, at.x, at.y, at.z, SoundEvents.WARDEN_SONIC_BOOM, SoundSource.HOSTILE, 6.0F, 0.5F);
         flash(end, at, 0xCCBBFF);
      }
      if (zeroGTicks <= ZERO_G_DROP_TICKS - 6) {
         // The bill. Anyone who did not find a floor they liked takes the landing here.
         for (ServerPlayer p : playersWithin(end, at, 120.0)) {
            double hitGround = groundBelow(end, p.position());
            if (p.getY() - hitGround > 2.0) {
               continue;
            }
            if (harnessMode) {
               continue;
            }
            p.hurtServer(end, end.damageSources().fall(), ZERO_G_DAMAGE);
            spend(end, ParticleTypes.CLOUD, p.getX(), p.getY() + 0.2, p.getZ(), 30, 2.0, 0.4, 2.0, 0.3);
            ring(end, new Vec3(p.getX(), p.getY() + 0.2, p.getZ()), 4.0, 24, ParticleTypes.WHITE_ASH, 1.0, 0.0);
         }
         spend(end, ParticleTypes.WHITE_ASH, 0.5, ground + 1.0, 0.5, 80, 24.0, 2.0, 24.0, 0.05);
      }
      if (zeroGTicks <= 0) {
         zeroGTicks = 0;
         nova(end, new Vec3(0.5, ground + 1.0, 0.5), 26.0, ParticleTypes.CLOUD, 0xCCBBFF);
         end.playSound(null, at.x, at.y, at.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 6.0F, 0.5F);
      }
   }

   /**
    * The sky ring: the whole circle at once, then the circle again, closer.
    *
    * <p>Borrowed from the pack whose transitions this fight's ascension comes from, which rings the
    * island with a strike every twelve degrees. The counter is the middle: the bands walk inward,
    * so the one place the last band does not stand is the point they are all centred on, and the
    * one place that is fatal to be is the rim. Nothing here winds up - the first band lands with the
    * shape - and the bands closing in are the only thing that says which way the move is going.
    */
   private static void beginSkyRing(ServerLevel end, EnderDragon dragon) {
      skyRingTicks = SKY_RING_WAVES * SKY_RING_GAP;
      skyRingWave = 0;
      Vec3 at = dragon.position();
      flash(end, at, 0xCCBBFF);
      nova(end, at, 22.0, ParticleTypes.END_ROD, 0xCCBBFF);
      end.playSound(null, at.x, at.y, at.z, SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.WEATHER, 9.0F, 0.5F);
   }

   /** One tick of the sky ring: a band of strikes every {@link #SKY_RING_GAP} ticks, each closer in. */
   private static void tickSkyRing(ServerLevel end, EnderDragon dragon) {
      skyRingTicks--;
      Vec3 at = dragon.position();
      spend(end, ParticleTypes.END_ROD, at.x, at.y, at.z, 16, SKY_RING_SPAN, 0.5, SKY_RING_SPAN, 0.05);
      if (skyRingTicks <= 0) {
         skyRingTicks = 0;
         skyRingWave = 0;
         nova(end, new Vec3(0.5, at.y, 0.5), 18.0, ParticleTypes.END_ROD, 0xCCBBFF);
         return;
      }
      if (skyRingTicks % SKY_RING_GAP != 0 || skyRingWave >= SKY_RING_WAVES) {
         return;
      }
      double radius = SKY_RING_SPAN - skyRingWave * SKY_RING_STEP;
      skyRingWave++;
      for (int i = 0; i < SKY_RING_BOLTS; i++) {
         double angle = (Math.PI * 2.0) * i / SKY_RING_BOLTS + skyRingWave * 0.19;
         double x = 0.5 + Math.cos(angle) * radius;
         double z = 0.5 + Math.sin(angle) * radius;
         double ground = end.getHeight(
            net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, (int)x, (int)z
         );
         Vec3 hit = new Vec3(x, Math.max(at.y, ground + 1.0), z);
         strike(end, hit);
         hurtNear(end, hit, 0.0, 2.4, dragon, SKY_RING_DAMAGE, "the sky ring");
      }
      end.playSound(null, at.x, at.y, at.z, SoundEvents.LIGHTNING_BOLT_IMPACT, SoundSource.WEATHER, 7.0F, 0.7F);
   }

   /** One strike of the sky ring: vanilla's own bolt, drawn out of the ground rather than spawned. */
   private static void strike(ServerLevel end, Vec3 at) {
      pillar(end, new Vec3(at.x, at.y - 1.0, at.z), 9.0, ParticleTypes.ELECTRIC_SPARK);
      pillar(end, new Vec3(at.x, at.y - 1.0, at.z), 7.0, ParticleTypes.END_ROD);
      flash(end, at, 0xCCBBFF);
      nova(end, at, 7.0, ParticleTypes.ELECTRIC_SPARK, 0xCCBBFF);
      ring(end, at.add(0.0, 0.4, 0.0), 3.0, 20, ParticleTypes.END_ROD, 1.2, 0.0);
   }

   /**
    * The ascendant blink: a chain of teleports whose gaps collapse, and a slam where it stops.
    *
    * <p>Borrowed from the same pack, which moves the dragon on a schedule that shortens from eighty
    * ticks to one. Read as a single move rather than eight: the arrivals accelerate, each one lands
    * with its own shockwave, and the last one is not an arrival at all - it is the slam, so the
    * answer to a chain nobody can hit is not to chase it but to be somewhere else when it stops.
    */
   private static void beginBlinkChain(ServerLevel end, EnderDragon dragon) {
      blinkTicks = BLINK_STEPS;
      blinkGap = BLINK_FIRST_GAP;
      Vec3 at = dragon.position();
      flash(end, at, 0xAA66FF);
      spend(end, ParticleTypes.REVERSE_PORTAL, at.x, at.y, at.z, 80, 3.0, 3.0, 3.0, -0.5);
      end.playSound(null, at.x, at.y, at.z, SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE, 5.0F, 0.6F);
   }

   /** One tick of the blink chain: nothing until the gap is up, then the next arrival. */
   private static void tickBlinkChain(ServerLevel end, EnderDragon dragon) {
      if (--blinkGap > 0) {
         return;
      }
      blinkTicks--;
      if (blinkTicks <= 0) {
         // The chain ends where it lands: the last blink is the slam's own opening, which is the
         // one part of this move the room can answer - it knows where the landing is now.
         blinkTicks = 0;
         beginSlam(end, dragon);
         return;
      }
      Vec3 from = dragon.position();
      ServerPlayer target = nearest(end, from, 90.0);
      double bearing = RANDOM.nextDouble() * Math.PI * 2.0;
      double reach = 9.0 + RANDOM.nextDouble() * 5.0;
      double x = (target == null ? 0.5 : target.getX()) + Math.cos(bearing) * reach;
      double z = (target == null ? 0.5 : target.getZ()) + Math.sin(bearing) * reach;
      double y = Math.max(66.0, from.y + RANDOM.nextDouble() * 6.0 - 2.0);
      Vec3 to = new Vec3(x, y, z);
      spend(end, ParticleTypes.REVERSE_PORTAL, from.x, from.y, from.z, 60, 2.5, 2.5, 2.5, -0.6);
      nova(end, from, 12.0, ParticleTypes.REVERSE_PORTAL, 0xAA66FF);
      try {
         dragon.teleportTo(to.x, to.y, to.z);
         dragon.setDeltaMovement(Vec3.ZERO);
         dragon.hurtMarked = true;
      } catch (Throwable ignored) {
      }
      nova(end, to, 14.0, ParticleTypes.END_ROD, 0xCCBBFF);
      hurtNear(end, to, 0.0, 3.4, dragon, BLINK_LANDING_DAMAGE, "the blink");
      end.playSound(
         null, to.x, to.y, to.z, SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE,
         4.0F, 0.7F + 0.04F * (BLINK_STEPS - blinkTicks)
      );
      blinkGap = Math.max(BLINK_LAST_GAP, blinkGap - 1);
   }

   /**
    * The ascension: the beat between two phases, when the dragon leaves the room's reach.
    *
    * <p>The borders were already loud, but they were instantaneous - the flash fired and the next
    * phase began on the same tick, which reads as a phase number changing rather than as something
    * happening to the dragon. This is the pack's own transition: the dragon climbs, the island
    * grows a colonnade of light around it, and the blows aimed at it arrive at nothing. It is on a
    * clock and it only ever starts at a border, so the window is a breath between two fights rather
    * than a way to outlast the room.
    */
   private static void beginAscension(ServerLevel end, EnderDragon dragon) {
      ascensionTicks = ASCENSION_TICKS;
      Vec3 at = dragon.position();
      hover(dragon, true);
      flash(end, at, 0xAA66FF);
      spend(end, ParticleTypes.END_ROD, at.x, at.y, at.z, 120, 18.0, 12.0, 18.0, 0.2);
      end.playSound(null, at.x, at.y, at.z, SoundEvents.END_PORTAL_SPAWN, SoundSource.HOSTILE, 6.0F, 0.6F);
   }

   /** One tick of the ascension: the columns rise, and the dragon climbs with them. */
   private static void tickAscension(ServerLevel end, EnderDragon dragon) {
      ascensionTicks--;
      int done = ASCENSION_TICKS - Math.max(0, ascensionTicks);
      int columns = Math.min(ASCENSION_PILLARS, 1 + done / 3);
      double height = Math.min(ASCENSION_HEIGHT, 1.0 + done * 0.3);
      for (int i = 0; i < columns; i++) {
         double angle = (Math.PI * 2.0) * i / ASCENSION_PILLARS;
         double x = 0.5 + Math.cos(angle) * ASCENSION_RADIUS;
         double z = 0.5 + Math.sin(angle) * ASCENSION_RADIUS;
         double ground = end.getHeight(
            net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, (int)x, (int)z
         );
         pillar(end, new Vec3(x, ground, z), height, ParticleTypes.END_ROD);
         if (i % 3 == 0) {
            pillar(end, new Vec3(x, ground, z), height * 0.7, ParticleTypes.SOUL_FIRE_FLAME);
         }
      }
      Vec3 at = dragon.position();
      spend(end, ParticleTypes.REVERSE_PORTAL, at.x, at.y, at.z, 30, 4.0, 3.0, 4.0, -0.3);
      spend(end, ParticleTypes.END_ROD, at.x, at.y + 2.0, at.z, 24, 3.0, 2.0, 3.0, 0.3);
      try {
         dragon.setDeltaMovement(0.0, 0.06, 0.0);
         dragon.hurtMarked = true;
      } catch (Throwable ignored) {
      }
      if (ascensionTicks <= 0) {
         ascensionTicks = 0;
         nova(end, at, 26.0, ParticleTypes.END_ROD, 0xAA66FF);
         flash(end, at, 0xCCBBFF);
         end.playSound(null, at.x, at.y, at.z, SoundEvents.ENDER_DRAGON_GROWL, SoundSource.HOSTILE, 6.0F, 0.5F);
      }
   }

   /**
    * A field of end rod spikes around a point: capped rods standing out of the ground, marching out
    * in rings. Vanilla's evoker fangs are the shape being borrowed, and the sound with them.
    */
   private static void spikeField(ServerLevel end, Vec3 at, double radius) {
      double ground = groundBelow(end, at);
      for (int row = 0; row < SPIKE_ROWS; row++) {
         double r = 1.0 + row * 1.5;
         ParticleOptions shaft = row % 2 == 0 ? ParticleTypes.END_ROD : new DustParticleOptions(0xCCBBFF, 1.3F);
         ringOffset(end, new Vec3(at.x, ground + 0.4, at.z), r, 12, shaft, row * 0.5, 0.15, 0.0);
         for (double y = 0.2; y <= 1.3; y += 0.4) {
            spend(end, ParticleTypes.END_ROD, at.x, ground + y, at.z, 1, 0.05, 0.05, 0.05, 0.02);
         }
      }
      spend(end, ParticleTypes.EXPLOSION, at.x, ground + 0.4, at.z, 1, 0.0, 0.0, 0.0, 0.0);
   }

   /**
    * A ring of twelve of vanilla's own bullets around the body, each handed a player to hate.
    *
    * <p>Real {@code ShulkerBullet} entities rather than scripted projectiles, because the whole point
    * of the move is that they are the thing players already know how to fight - they curve, they hurt,
    * and they are dodgeable. Each one is given a random body to chase rather than the nearest, which
    * is what keeps a twelve-bullet ring from being a single-target execution.
    *
    * @return false when there was nothing to summon with, or when the air is already full of them
    */
   private static boolean summonShulkerBullets(ServerLevel end, EnderDragon dragon) {
      if (harnessMode || !shulkerBullets(end).isEmpty()) {
         return false;
      }
      List<ServerPlayer> players = end.getPlayers(pl -> !pl.isSpectator() && pl.isAlive());
      if (players.isEmpty()) {
         return false;
      }
      Vec3 at = dragon.position();
      int made = 0;
      for (int i = 0; i < SHULKER_BULLETS; i++) {
         double angle = (Math.PI * 2.0) * i / SHULKER_BULLETS;
         try {
            net.minecraft.world.entity.projectile.ShulkerBullet bullet =
               new net.minecraft.world.entity.projectile.ShulkerBullet(
                  end, dragon, players.get(RANDOM.nextInt(players.size())), net.minecraft.core.Direction.Axis.Y
               );
            bullet.setPos(at.x + Math.cos(angle) * 6.0, at.y + 1.0, at.z + Math.sin(angle) * 6.0);
            end.addFreshEntity(bullet);
            made++;
         } catch (Throwable t) {
            LOGGER.warn("Ender dragon rework: a shulker bullet could not be summoned", t);
            break;
         }
      }
      if (made == 0) {
         return false;
      }
      spend(end, ParticleTypes.EXPLOSION_EMITTER, at.x, at.y, at.z, 4, 4.0, 2.0, 4.0, 0.0);
      end.playSound(null, at.x, at.y, at.z, SoundEvents.SHULKER_SHOOT, SoundSource.HOSTILE, 6.0F, 0.9F);
      return true;
   }

   /** The bullets already in the air, so a second ring is never summoned onto a first one. */
   private static List<Entity> shulkerBullets(ServerLevel end) {
      List<Entity> out = new ArrayList<>();
      for (Entity e : end.getAllEntities()) {
         if (e instanceof net.minecraft.world.entity.projectile.ShulkerBullet bullet && bullet.isAlive()) {
            out.add(bullet);
         }
      }
      return out;
   }

   /** True while the dragon is on the ground: landing, sitting or breathing from the portal. */
   private static boolean dragonIsPerching(EnderDragon dragon) {
      try {
         var phase = dragon.getPhaseManager().getCurrentPhase();
         return phase == net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhase.LANDING
            || phase == net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhase.SITTING_FLAMING
            || phase == net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhase.SITTING_SCANNING
            || phase == net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhase.SITTING_ATTACKING
            || phase == net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhase.TAKEOFF;
      } catch (Throwable ignored) {
         return false;
      }
   }

   /** The floor under a point, as a y level - the one number every ground-hugging move needs. */
   private static double groundBelow(ServerLevel end, Vec3 at) {
      try {
         return end.getHeight(
            net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING,
            (int)Math.floor(at.x), (int)Math.floor(at.z)
         );
      } catch (Throwable ignored) {
         return 64.0;
      }
   }

   /**
    * A dragon fireball, in the air, aimed at a body.
    *
    * <p>Vanilla's own projectile rather than a scripted bolt, and the difference is the point: it
    * is a body that crosses the arena, it leaves vanilla's breath puddle where it lands, and it is
    * the one attack in this fight a player can watch coming. A null target throws it at the island
    * instead, so the move still happens when everybody is dead or hiding.
    *
    * @return true if a fireball was actually added to the world
    */
   private static boolean throwFireball(ServerLevel end, EnderDragon dragon, ServerPlayer target) {
      if (harnessMode) {
         return false;
      }

      try {
         Vec3 from = mouthOf(dragon);
         Vec3 aim = target == null
            ? flatLook(dragon).scale(0.9)
            : target.position().add(0.0, 1.0, 0.0).subtract(from).normalize().scale(0.9);
         net.minecraft.world.entity.projectile.hurtingprojectile.DragonFireball ball =
            new net.minecraft.world.entity.projectile.hurtingprojectile.DragonFireball(end, dragon, aim);
         ball.setPos(from.x, from.y, from.z);
         end.addFreshEntity(ball);
         return true;
      } catch (Throwable t) {
         LOGGER.warn("Ender dragon rework: a fireball could not be thrown", t);
         return false;
      }
   }

   // ------------------------------------------------------------------ the breath lance

   /**
    * The dragon's mouth, which is where a breath weapon comes out of.
    *
    * <p>Not the body's centre: a beam that starts in the middle of a 16-block animal reads as a
    * column through its chest. The head is reached by stepping along its facing and lifting, and
    * when the facing is degenerate (a body looking straight down) the step is skipped rather than
    * producing a NaN that would put the whole beam at the origin.
    */
   private static Vec3 mouthOf(EnderDragon dragon) {
      Vec3 forward = dragon.getViewVector(1.0F);
      Vec3 flat = new Vec3(forward.x, 0.0, forward.z);
      if (flat.lengthSqr() < 1.0E-4) {
         flat = new Vec3(0.0, 0.0, 1.0);
      }
      Vec3 step = flat.normalize().scale(3.4);
      return dragon.position().add(step.x, 2.6, step.z);
   }

   /** The facing on the horizontal plane - the axis every sideways wall is thrown along. */
   /**
    * The line a charge takes: the nearest player, or the island's middle when there is nobody.
    *
    * <p>The shape and the impact both call these three, which is the only way the corridor a player
    * is shown and the corridor the server hits can be the same line. They are separate functions
    * rather than one because the aim is a body and the rest is arithmetic about it.
    */
   private static Vec3 chargeAim(ServerLevel end, EnderDragon dragon) {
      ServerPlayer target = nearest(end, dragon.position(), 90.0);
      return target == null ? new Vec3(0.5, 64.0, 0.5) : target.position();
   }

   /** The direction of a charge, flattened, with a fallback for the degenerate case. */
   private static Vec3 chargeUnit(Vec3 at, Vec3 aim) {
      Vec3 flat = new Vec3(aim.x - at.x, 0.0, aim.z - at.z);
      return flat.lengthSqr() < 1.0 ? new Vec3(1.0, 0.0, 0.0) : flat.normalize();
   }

   /** Where the body starts a charge: {@link #CHARGE_LENGTH} blocks back along its own line. */
   private static Vec3 chargeStart(Vec3 aim, Vec3 unit) {
      return aim.subtract(unit.scale(CHARGE_LENGTH)).add(0.0, 1.0, 0.0);
   }

   private static Vec3 flatLook(EnderDragon dragon) {
      Vec3 forward = dragon.getViewVector(1.0F);
      Vec3 flat = new Vec3(forward.x, 0.0, forward.z);
      return flat.lengthSqr() < 1.0E-4 ? new Vec3(0.0, 0.0, 1.0) : flat.normalize();
   }

   /** A thunderclap with no lightning bolt: the bolt that struck would light fires on the island. */
   private static void levelThunder(ServerLevel end, Vec3 at) {
      end.playSound(null, at.x, at.y, at.z, SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.WEATHER, 5.0F, 0.55F);
   }

   /** The lance, starting: the rotation is held while it burns. */
   private static void beginBeam(ServerLevel end, EnderDragon dragon) {
      beamTicks = BEAM_TICKS;
      beamSweepSign = RANDOM.nextBoolean() ? 1 : -1;
      beamPoolClock = 0;
      Vec3 mouth = mouthOf(dragon);
      spend(end, BREATH, mouth.x, mouth.y, mouth.z, 40, 0.6, 0.6, 0.6, 0.05);
      spend(end, ParticleTypes.EXPLOSION_EMITTER, mouth.x, mouth.y, mouth.z, 2, 1.0, 1.0, 1.0, 0.0);
      end.playSound(null, mouth.x, mouth.y, mouth.z, SoundEvents.ENDER_DRAGON_SHOOT, SoundSource.HOSTILE, 6.0F, 0.6F);
      end.playSound(null, mouth.x, mouth.y, mouth.z, SoundEvents.WARDEN_SONIC_CHARGE, SoundSource.HOSTILE, 4.0F, 0.7F);
      for (ServerPlayer p : end.getPlayers(pl -> !pl.isSpectator())) {
         p.sendOverlayMessage(Component.literal("\u00a7c\u00a7lBREATH LANCE \u00a78| \u00a7fget off the line"));
      }
   }

   /**
    * One tick of the lance: the mouth, the sweep, the line of damage, and the pool where it lands.
    *
    * <p>The beam is swept rather than aimed, so it cannot be answered by standing behind one
    * block: the tell says where it starts and the sweep takes it off that line, which is the
    * difference between a laser and a hose. Damage is per tick and small - a player caught in it
    * for a second is hurt, a player who walks out of it is not - and it sets them alight, so the
    * breath is breath rather than a beam of arithmetic.
    *
    * <p>Where it touches ground it leaves <b>vanilla's own dragon-breath cloud</b> (see
    * {@link #breathPool}), which is the puddle players already know from dragon fireballs, capped
    * and swept so a long beam cannot carpet a chunk.
    */
   private static void tickBeam(ServerLevel end, EnderDragon dragon) {
      beamTicks--;
      double progress = 1.0 - (double)beamTicks / (double)BEAM_TICKS;
      Vec3 mouth = mouthOf(dragon);
      float yaw = dragon.getYRot() + (float)(beamSweepSign * BEAM_SWEEP_DEGREES * (progress * 2.0 - 1.0));
      Vec3 dir = Vec3.directionFromRotation(0.0F, yaw);
      Vec3 tip = mouth.add(dir.scale(BEAM_RANGE));
      boolean touched = false;

      for (double d = 1.0; d <= BEAM_RANGE; d += 1.0) {
         Vec3 point = mouth.add(dir.scale(d));
         spend(end, BREATH, point.x, point.y, point.z, 3, 0.16, 0.16, 0.16, 0.02);
         if (d % 4.0 < 1.0) {
            spend(end, ParticleTypes.SOUL_FIRE_FLAME, point.x, point.y, point.z, 1, 0.12, 0.12, 0.12, 0.01);
         }
         if (!harnessMode) {
            for (ServerPlayer p : playersWithin(end, point, BEAM_HIT_RADIUS)) {
               p.hurtServer(end, end.damageSources().mobAttack(dragon), BEAM_DAMAGE_PER_TICK);
               p.setRemainingFireTicks(Math.max(p.getRemainingFireTicks(), 40));
            }
         }
         BlockPos pos = BlockPos.containing(point);
         if (!end.isLoaded(pos)) {
            tip = point;
            touched = true;
            break;
         }
         if (end.getBlockState(pos).blocksMotion()) {
            tip = point;
            touched = true;
            break;
         }
      }

      if (touched && --beamPoolClock <= 0) {
         beamPoolClock = BEAM_POOL_TICKS;
         breathPool(end, dragon, tip);
      }

      if (beamTicks % 20 == 0) {
         end.playSound(null, mouth.x, mouth.y, mouth.z, SoundEvents.ENDER_DRAGON_SHOOT, SoundSource.HOSTILE, 2.6F, 1.5F);
      }
      if (beamTicks % 10 == 0) {
         for (ServerPlayer p : end.getPlayers(pl -> !pl.isSpectator())) {
            p.sendOverlayMessage(Component.literal("\u00a7c\u00a7lBREATH LANCE \u00a78| \u00a7f" + (beamTicks / 20 + 1) + "s"));
         }
      }

      if (beamTicks <= 0) {
         spend(end, ParticleTypes.GUST, mouth.x, mouth.y, mouth.z, 60, 3.0, 2.0, 3.0, 0.0);
         move = null;
         moveCooldown = moveGapFor(rotationPhase());
         LOGGER.info("Ender dragon rework: the breath lance is over ({} pools standing)", POOLS.size());
      }
   }

   /**
    * The puddle, which is vanilla's own: a dragon-fireball cloud, values and all.
    *
    * <p>Radius, growth, duration and the instant-damage effect are copied from vanilla's
    * {@code DragonFireball} so a player who knows how dragon breath behaves already knows how this
    * behaves - it is the thing that makes the beam leave a hazard rather than a scorch mark. The
    * list is capped: the eldest is swept up when an eleventh is placed, and the death sweeps them
    * all, so nothing here can outlive the fight that made it.
    */
   private static void breathPool(ServerLevel end, EnderDragon dragon, Vec3 at) {
      try {
         AreaEffectCloud cloud = new AreaEffectCloud(end, at.x, at.y + 0.25, at.z);
         cloud.setOwner(dragon);
         cloud.setRadius(BEAM_POOL_RADIUS);
         cloud.setDuration(BEAM_POOL_DURATION);
         cloud.setRadiusPerTick((7.0F - cloud.getRadius()) / (float)cloud.getDuration());
         cloud.addEffect(new MobEffectInstance(MobEffects.INSTANT_DAMAGE, 1, 1));
         cloud.setCustomParticle(PowerParticleOption.create(ParticleTypes.DRAGON_BREATH, 1.0F));
         end.addFreshEntity(cloud);
         POOLS.add(cloud);
         while (POOLS.size() > BEAM_POOLS_MAX) {
            AreaEffectCloud eldest = POOLS.remove(0);
            if (eldest != null && !eldest.isRemoved()) {
               eldest.discard();
            }
         }
      } catch (Throwable t) {
         LOGGER.error("Ender dragon rework: could not leave a breath pool", t);
      }
   }

   /** Every pool goes when the fight does - and the beam stops with it. */
   private static void clearPools() {
      for (AreaEffectCloud cloud : POOLS) {
         try {
            if (cloud != null && !cloud.isRemoved()) {
               cloud.discard();
            }
         } catch (Throwable ignored) {
         }
      }
      POOLS.clear();
      beamTicks = 0;
   }

   // ------------------------------------------------------------------ bolts

   /** A scripted projectile: position, velocity, life, damage. Damped until they leave the body. */
   private static final class Bolt {
      Vec3 pos;
      Vec3 vel;
      int life;
      float damage;
      double radius;
      EnderDragon owner;
      /** Whose fault this round is, when it hunts, or null for a straight shot. */
      UUID target;

      Bolt(Vec3 pos, Vec3 vel, int life, float damage, double radius, EnderDragon owner, UUID target) {
         this.pos = pos;
         this.vel = vel;
         this.life = life;
         this.damage = damage;
         this.radius = radius;
         this.owner = owner;
         this.target = target;
      }
   }

   private static void bolt(ServerLevel end, EnderDragon owner, Vec3 from, Vec3 velocity, int life, float damage, double radius) {
      bolt(end, owner, from, velocity, life, damage, radius, null);
   }

   /**
    * A bolt that can be told whose fault it is - the launcher's homing round.
    *
    * <p>The target is a uuid rather than a body, because a bolt outlives the thing it was aimed at
    * about as often as not (a player dies, logs out or walks through a portal mid-flight), and a held
    * reference to an entity that has left the world is exactly how a boss fight drops a server's
    * memory on the floor. The steering itself is one line in {@link #tickBolts}.
    */
   private static void bolt(
      ServerLevel end, EnderDragon owner, Vec3 from, Vec3 velocity, int life, float damage, double radius, UUID target
   ) {
      if (BOLTS.size() >= MAX_BOLTS) {
         return;
      }
      BOLTS.add(new Bolt(from, velocity, life, damage, radius, owner, target));
      spend(end, BREATH, from.x, from.y, from.z, 20, 0.6, 0.6, 0.6, 0.05);
   }

   private static void tickBolts(ServerLevel end) {
      if (harnessMode) {
         BOLTS.clear();
         return;
      }
      if (BOLTS.isEmpty()) {
         return;
      }
      java.util.Iterator<Bolt> it = BOLTS.iterator();
      while (it.hasNext()) {
         Bolt b = it.next();
         // A hunting round turns toward the body it was aimed at rather than flying at where that
         // body used to be - the one difference between "a projectile" and "a missile".
         if (b.target != null) {
            ServerPlayer chased = end.getServer() == null ? null : end.getServer().getPlayerList().getPlayer(b.target);
            if (chased == null || chased.level() != end || !chased.isAlive()) {
               b.target = null;
            } else {
               Vec3 want = chased.position().add(0.0, 1.0, 0.0).subtract(b.pos);
               if (want.lengthSqr() > 0.01) {
                  b.vel = b.vel.scale(0.8).add(want.normalize().scale(b.vel.length() * 0.2));
               }
            }
         }
         b.pos = b.pos.add(b.vel);
         b.life--;
         spend(end, BREATH, b.pos.x, b.pos.y, b.pos.z, 8, 0.35, 0.35, 0.35, 0.02);
         spend(end, ParticleTypes.END_ROD, b.pos.x, b.pos.y, b.pos.z, 3, 0.2, 0.2, 0.2, 0.02);
         if (b.life <= 0 || b.pos.y < 55.0) {
            it.remove();
            spend(end, ParticleTypes.EXPLOSION, b.pos.x, b.pos.y, b.pos.z, 3, 0.6, 0.6, 0.6, 0.0);
            end.playSound(null, b.pos.x, b.pos.y, b.pos.z, SoundEvents.DRAGON_FIREBALL_EXPLODE, SoundSource.HOSTILE, 2.0F, 0.9F);
            hurtNear(end, b.pos, 0.0, b.radius + 2.0, b.owner, b.damage, "the dragon's breath");
            continue;
         }
         for (ServerPlayer p : playersWithin(end, b.pos, b.radius)) {
            // Scaled here rather than where the bolt was made, and that is the whole reason bolt
            // damage is carried raw: a bolt that expires on the ground goes through {@link #hurtNear}
            // (which scales), and a bolt that meets a body comes through here - two paths, one
            // scaling each, so a round cannot be paid for twice.
            p.hurtServer(
               end, b.owner == null ? end.damageSources().magic() : end.damageSources().mobAttack(b.owner),
               wound(b.damage)
            );
            it.remove();
            spend(end, ParticleTypes.EXPLOSION_EMITTER, b.pos.x, b.pos.y, b.pos.z, 1, 0.0, 0.0, 0.0, 0.0);
            break;
         }
      }
   }

   // ------------------------------------------------------------------ VFX helpers

   /** A ring of particles, flat in the world, at a radius. */
   private static void ring(ServerLevel end, Vec3 centre, double radius, int points, ParticleOptions particle, double speed, double dy) {
      ringOffset(end, centre, radius, points, particle, 0.0, speed, dy);
   }

   /** A ring with a phase offset, so a shape that *turns* does not look like one that flickers. */
   private static void ringOffset(
      ServerLevel end, Vec3 centre, double radius, int points, ParticleOptions particle, double phase, double speed, double dy
   ) {
      com.fortuneandfavors.net.FfVfx.shape(
         end, com.fortuneandfavors.net.FfVfx.RING, particle, centre.add(0.0, dy, 0.0), Vec3.ZERO, radius, speed, 0
      );
      com.fortuneandfavors.net.FfVfx.enter();
      try {
         for (int i = 0; i < points; i++) {
            double angle = phase + (Math.PI * 2.0) * i / points;
            spend(end,
               particle,
               centre.x + Math.cos(angle) * radius,
               centre.y + dy,
               centre.z + Math.sin(angle) * radius,
               1, 0.0, 0.0, 0.0, speed
            );
         }
      } finally {
         com.fortuneandfavors.net.FfVfx.exit();
      }
   }

   /** A line of particles from one point to another - a beam, drawn rather than simulated. */
   private static void beam(ServerLevel end, Vec3 from, Vec3 to, ParticleOptions particle, double speed) {
      Vec3 delta = to.subtract(from);
      double length = delta.length();
      if (length < 0.01) {
         return;
      }
      com.fortuneandfavors.net.FfVfx.shape(end, com.fortuneandfavors.net.FfVfx.BEAM, particle, from, to, length, speed, 0xB45CFF);
      com.fortuneandfavors.net.FfVfx.enter();
      try {
         int steps = (int)Math.min(48.0, Math.max(4.0, length / 1.1));
         for (int i = 0; i <= steps; i++) {
            double t = (double)i / (double)steps;
            Vec3 at = from.add(delta.scale(t));
            spend(end, particle, at.x, at.y, at.z, 1, 0.0, 0.0, 0.0, speed);
         }
      } finally {
         com.fortuneandfavors.net.FfVfx.exit();
      }
   }

   /** A rift: the visual the whole rework is built around. */
   private static void rift(ServerLevel end, Vec3 at, double power) {
      double size = Math.max(0.4, power);
      com.fortuneandfavors.net.FfVfx.shape(end, com.fortuneandfavors.net.FfVfx.RIFT, ParticleTypes.PORTAL, at, Vec3.ZERO, size, 0.0, 0x7A2BD9);
      com.fortuneandfavors.net.FfVfx.enter();
      try {
         spend(end, ParticleTypes.REVERSE_PORTAL, at.x, at.y + 1.0, at.z, (int)(30 * size), size, size * 1.6, size, -0.4);
         spend(end, ParticleTypes.PORTAL, at.x, at.y + 1.0, at.z, (int)(50 * size), size, size * 1.6, size, 0.5);
         spend(end, ParticleTypes.SCULK_SOUL, at.x, at.y + 1.0, at.z, (int)(8 * size), size, size, size, 0.04);
         spend(end, ParticleTypes.GUST_EMITTER_LARGE, at.x, at.y + 1.0, at.z, 1, 0.0, 0.0, 0.0, 0.0);
      } finally {
         com.fortuneandfavors.net.FfVfx.exit();
      }
      end.playSound(null, at.x, at.y, at.z, SoundEvents.END_PORTAL_SPAWN, SoundSource.HOSTILE, 1.6F, 1.4F);
   }

   /** A flash of lightning with no bolt: the light and the sound, none of the consequences. */
   private static void visualLightning(ServerLevel end, double x, double y, double z) {
      LightningBolt bolt = EntityTypes.LIGHTNING_BOLT.create(end, EntitySpawnReason.COMMAND);
      if (bolt == null) {
         return;
      }
      bolt.setPos(x, y, z);
      bolt.setVisualOnly(true);
      end.addFreshEntity(bolt);
   }

   private static void beamToCrystals(ServerLevel end, Vec3 at) {
      for (Entity crystal : crystals(end)) {
         beam(end, crystal.position(), at, ParticleTypes.END_ROD, 0.8);
         spend(end, ParticleTypes.FIREWORK, crystal.getX(), crystal.getY() + 1.0, crystal.getZ(), 12, 0.4, 0.6, 0.4, 0.05);
      }
   }

   /**
    * The fight's own crystals, untouched, including their positions.
    *
    * <p>Read every time rather than cached: crystals are destroyed one at a time and caches of
    * them are how a "the crystals still fire" bug survives a fight where every crystal is gone.
    */
   private static List<Entity> crystals(ServerLevel end) {
      List<Entity> out = new ArrayList<>();
      for (Entity e : end.getAllEntities()) {
         if (e instanceof net.minecraft.world.entity.boss.enderdragon.EndCrystal crystal && crystal.isAlive()) {
            out.add(crystal);
         }
      }
      return out;
   }

   // ------------------------------------------------------------------ small helpers

   private static List<ServerPlayer> playersWithin(ServerLevel end, Vec3 at, double radius) {
      return end.getPlayers(p -> !p.isSpectator() && p.isAlive() && p.position().distanceTo(at) <= radius);
   }

   private static ServerPlayer nearest(ServerLevel end, Vec3 at, double radius) {
      ServerPlayer best = null;
      double bestDistance = radius;
      for (ServerPlayer p : end.getPlayers(pl -> !pl.isSpectator() && pl.isAlive())) {
         double distance = p.position().distanceTo(at);
         if (distance <= bestDistance) {
            best = p;
            bestDistance = distance;
         }
      }
      return best;
   }

   private static void forEachNearby(Entity anchor, double radius, java.util.function.Consumer<ServerPlayer> action) {
      if (!(anchor.level() instanceof ServerLevel end)) {
         return;
      }
      for (ServerPlayer p : playersWithin(end, anchor.position(), radius)) {
         try {
            action.accept(p);
         } catch (Throwable ignored) {
         }
      }
   }

   /**
    * One move's wound, at the scale every move in this fight is dealt at.
    *
    * <p>Exists so that "the moves hurt more" is one number rather than forty edits that drift:
    * the ring damage in {@link #hurtNear} goes through this, and so does every direct hit the
    * moves below land. See {@link #MOVE_DAMAGE_SCALE}.
    */
   private static float wound(float base) {
      return base * MOVE_DAMAGE_SCALE;
   }

   /** Damage inside an annulus - used by the rings, so that being inside or outside both matter. */
   private static void hurtNear(
      ServerLevel end, Vec3 centre, double innerRadius, double outerRadius, EnderDragon owner, float damage, String what
   ) {
      if (harnessMode) {
         return;
      }
      for (ServerPlayer p : playersWithin(end, centre, outerRadius)) {
         double distance = p.position().distanceTo(centre);
         if (distance < innerRadius) {
            continue;
         }
         p.hurtServer(end, owner == null ? end.damageSources().magic() : end.damageSources().mobAttack(owner), wound(damage));
         p.sendOverlayMessage(Component.literal("\u00a7c" + what + " catches you"));
      }
   }

   private static void volley(ServerLevel end, EnderDragon dragon, int count, float damage) {
      volleyFrom(end, dragon, dragon.position(), count, damage);
   }

   private static void volleyFrom(ServerLevel end, EnderDragon dragon, Vec3 from, int count, float damage) {
      if (harnessMode) {
         return;
      }
      for (int i = 0; i < count; i++) {
         ServerPlayer target = nearest(end, from, 120.0);
         Vec3 aim = target == null
            ? new Vec3(RANDOM.nextDouble() - 0.5, 0.2, RANDOM.nextDouble() - 0.5)
            : target.position().add(0.0, 1.0, 0.0).subtract(from).normalize();
         Vec3 spread = aim.add((RANDOM.nextDouble() - 0.5) * 0.12, (RANDOM.nextDouble() - 0.5) * 0.12, (RANDOM.nextDouble() - 0.5) * 0.12);
         bolt(end, dragon, from, spread.normalize().scale(0.95), 80, damage, 2.0);
      }
      end.playSound(null, from.x, from.y, from.z, SoundEvents.ENDER_DRAGON_SHOOT, SoundSource.HOSTILE, 4.0F, 0.7F);
   }

   /** The one place the fight's own body is taken off its vanilla AI and put back. */
   private static void hover(EnderDragon dragon, boolean over) {
      try {
         if (over) {
            dragon.setDeltaMovement(Vec3.ZERO);
            dragon.getPhaseManager().setPhase(EnderDragonPhase.HOVERING);
         } else {
            dragon.getPhaseManager().setPhase(EnderDragonPhase.LANDING);
         }
      } catch (Throwable t) {
         // A phase change that fails is a dragon that keeps flying: the scripted beats still run,
         // they just happen against a moving body.
         LOGGER.warn("Ender dragon rework: could not move the dragon between phases", t);
      }
   }

   private static void adopt(EnderDragon found) {
      // A body that is already dead or gone is never adopted: adopting one hands the corpse the
      // rematch ceremony and revives it on the spot (see findDragon, where the same rule is applied
      // to the search). Nothing here is a shape a live fight can reach - the callers all have a body
      // in their hands - so this is the belt to findDragon's braces rather than a policy of its own.
      if (found == null || found.isRemoved() || !found.isAlive()) {
         return;
      }
      if (dragon == found) {
         return;
      }
      // The same body, re-attached rather than adopted again. This is the difference between "the
      // field was cleared while the fight went on" and "a different dragon": the body's uuid, not
      // the field, is what a fight is. Handing a live fight the fresh-fight reset a second time is
      // how the ledger of who has been paid got emptied underneath a fight that was still running.
      if (found.getUUID().equals(lastFightUuid)) {
         dragon = found;
         reattachments++;
         return;
      }
      lastFightUuid = found.getUUID();
      freshAdoptions++;
      dragon = found;
      // The dragon is the same kind of body as every other fight in the mod, and this is the one
      // place it becomes this class's - so it wears the shared boss layer (aura, surge) from here,
      // and its blows get the shared multiplier in the damage pipeline.
      BossEmpowerment.register(found);
      // A different body is a different fight, including the respawn after a kill: the ledger of
      // who has been paid belongs to the fight that just ended, not to the next one.
      forgetEndLoot();
      // And the rite is spent: the next dragon - a rematch as much as a first arrival - has to
      // come through a rift of its own rather than inherit this one's release.
      riteOpened = false;
      // The bar starts where the body is, not where the last dragon left it: a fresh adopt resets
      // the smoothing so a reload cannot show a half-drained bar for a full dragon (or the reverse).
      barDisplayed = Mth.clamp(found.getHealth() / Math.max(1.0F, found.getMaxHealth()), 0.0F, 1.0F);
      // The bar it fights with, before anything reads it: the phase below, the boss bar, and the
      // arrival's own count-up all take their maximum from the one attribute.
      applyDragonHealth(found);
      // Part of the world until it dies, not part of the room: the dragon is marked persistent so it
      // is never culled when the last player leaves the End, which is what keeps the fight - and its
      // bar, and its music - alive in a dimension nobody is standing in.
      found.setPersistenceRequired();
      // Adopted mid-flight (a restart, a reload, a fight we were not alive for): take the phase
      // from the health bar, which is the only thing about this fight that is written down.
      phase = phaseFor(found.getHealth(), found.getMaxHealth());
      // Both directions, and this is the line that matters after a respawn: a fight that was in
      // its last stand is over, and the next dragon starts at the beginning.
      lastStand = found.getHealth() <= LAST_STAND_HEALTH;
      if (lastStand) {
         phase = 1;
      }
      finaleTicks = 0;
      deathTicks = 0;
      // The melee watchdog starts a new fight at zero too: a body adopted after a long aerial fight
      // (a reload, a restart) must not spend its first tick being hauled to the fountain by a
      // counter that was already full from the last one.
      airborneTicks = 0;
      // A different body is a different fight, so the nova's one charge is handed back and no rift
      // from the last fight is left standing in this one.
      novaSpent = false;
      // And if the fight just died, this body is the rematch: the island brings it back rather than
      // letting vanilla drop it out of the sky. Started here, in one place, so the ceremony cannot
      // run for a body picked up off a reload (awaitingRematch is false then) or for the first
      // arrival (that has its own show).
      if (awaitingRematch) {
         awaitingRematch = false;
         beginRespawnCeremony(found);
      }
      LOGGER.info(
         "Ender dragon rework: adopted the dragon at {} HP - phase {}, last stand {}",
         (int)found.getHealth(), phase, lastStand
      );
   }

   /**
    * The dragon's health, applied as an attribute.
    *
    * <p>Raised from vanilla's two hundred to {@link #DRAGON_HEALTH}, and a dragon that is adopted
    * mid-fight has the health it already had scaled with it, so a save reloaded at half a bar comes
    * back at half of the new bar rather than at a third of it - the phase arithmetic, the boss bar
    * and the fight itself all read the same maximum, and one of them changing without the others is
    * exactly how a boss ends up "dying immediately" or "never dropping its loot".
    */
   private static void applyDragonHealth(EnderDragon body) {
      try {
         net.minecraft.world.entity.ai.attributes.AttributeInstance max =
            body.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH);
         if (max == null || Math.abs(max.getBaseValue() - DRAGON_HEALTH) < 0.001F) {
            return;
         }
         float before = body.getHealth();
         float wasMax = Math.max(1.0F, (float)max.getBaseValue());
         max.setBaseValue(DRAGON_HEALTH);
         body.setHealth(Math.max(0.5F, Math.min(DRAGON_HEALTH, before * (DRAGON_HEALTH / wasMax))));
      } catch (Throwable t) {
         LOGGER.warn("Ender dragon rework: the dragon's health could not be raised", t);
      }
   }

   private static EnderDragon findDragon(ServerLevel end, long now) {
      EnderDragon cached = dragon;
      // Same dimension, compared by key rather than by the level object's identity. The identity
      // check this replaces was the whole of a repeated-adoption bug: when it failed even though the
      // body was the fight's, the field was cleared, the rescan was throttled, and every other scan
      // re-adopted the same dragon - a fresh-fight reset (loot ledger wiped, boss layer re-registered,
      // bar smoothing reset, the nova's charge handed back) once a second, on a body whose health bar
      // never moved. A dimension is a dimension; a level instance that wraps it is not the question.
      if (cached != null && !cached.isRemoved() && cached.isAlive()
         && cached.level() != null && cached.level().dimension() == end.dimension()) {
         return cached;
      }
      dragon = null;
      if (now < nextDragonScan) {
         return null;
      }
      nextDragonScan = now + 20L;
      try {
         for (Entity e : end.getAllEntities()) {
            // Alive, not merely present. A body that has just been killed is still an entity in the
            // world for the whole of its death animation, and this search is what decides whether
            // the fight has a dragon - so a corpse that answered yes here was adopted, handed the
            // rematch ceremony, and brought back to half a heart two seconds after being killed:
            // the fight restarted itself, the music started over, and the dragon "kept respawning".
            // A dead dragon is not a dragon this class manages; the corpse is vanilla's to finish.
            if (e instanceof EnderDragon d && !d.isRemoved() && d.isAlive()) {
               return d;
            }
         }
      } catch (Throwable t) {
         LOGGER.error("Ender dragon rework: could not look for the dragon", t);
      }
      return null;
   }

   // There is no title helper here any more, on purpose. The fight's only title is the one the
   // arrival writes character by character (see tickName), and a general-purpose "send a title"
   // helper left lying around is how the phase captions and the move captions came back the first
   // time. A new title has to be written where it is used, which is a decision rather than a call.

   /** Test hooks: what the manager believes right now, without needing a dragon. */
   /** Ticks left in the breath lance, or 0 - for a command, a check and the action bar. */
   public static int beamTicksLeft() {
      return Math.max(0, beamTicks);
   }

   /** How many of the lance's pools are standing - read by a check on the cap. */
   public static int poolsStanding() {
      return POOLS.size();
   }

   /** Ticks left in the arrival's count-up, or 0. */
   public static int riseTicksLeft() {
      return Math.max(0, riseTicks);
   }

   /**
    * The largest pool count the lance may leave standing.
    *
    * <p>Exposed rather than duplicated in the check: "the beam cannot carpet a chunk" is one
    * number, and a test that quoted its own copy of it would pass after the cap was raised.
    */
   public static int poolCap() {
      return BEAM_POOLS_MAX;
   }

   /**
    * Drops the memory of the search, so a check can ask what it would find *right now*.
    *
    * <p>{@link #findDragon} is throttled to one sweep a second - which is invisible in a real fight,
    * because game time moves, and fatal to a check, because the self-test drives {@link #tick} by
    * hand on a clock that does not advance: every manual tick inside the same second is answered out
    * of the throttle, and the one tick that matters - the first tick after a death, where the search
    * must decide whether the body it is looking at is a fight - never runs at all. That is how a
    * corpse being adopted as a live dragon hid here for a whole version: the check that would have
    * caught it was reading "no dragon" from a search that had not been allowed to look.
    */
   public static void forgetDragonScanForTest() {
      dragon = null;
      nextDragonScan = 0L;
   }

   /** How many bodies have been adopted as brand-new fights since the harness last took a body. */
   public static int freshAdoptionsForTest() {
      return freshAdoptions;
   }

   /** How many times a body already owned by the current fight has been re-attached to it. */
   public static int reattachmentsForTest() {
      return reattachments;
   }

   /**
    * Test seam: adopt a body the check is already holding, so a check can see the re-attach without
    * depending on the End's entity sweep finding it. Used by the check that pins "the same dragon is
    * not adopted twice"; not a path any production caller takes.
    */
   public static void adoptForTest(EnderDragon body) {
      adopt(body);
   }

   // ------------------------------------------------------------------ the harness

   /**
    * A real dragon, in the real End, owned by this machine - for the self-test.
    *
    * <p>The fight's own machines are driven against a body that actually exists, because every
    * question worth asking about the finale is about the body: does the bar stop on one heart,
    * does the one blow end it, does vanilla's death still run underneath the ceremony. A body
    * created this way has no {@code EnderDragonFight} attached, so the vanilla fight's own
    * bookkeeping (the portal, the egg, the fight's state) is never touched by a test.
    *
    * <p>{@code harnessMode} is set for the duration, which suppresses <b>damage to bystanders
    * only</b> - every state change, every pool, every VFX and the dragon's own death happen
    * exactly as they do in a real fight. {@link #harnessRelease} undoes the rest.
    */
   public static EnderDragon spawnHarnessDragon(ServerLevel end, double x, double y, double z) {
      EnderDragon body = EntityTypes.ENDER_DRAGON.create(end, EntitySpawnReason.COMMAND);
      if (body == null) {
         return null;
      }
      body.setPos(x, y, z);
      body.setPersistenceRequired();
      end.addFreshEntity(body);
      harnessMode = true;
      dragon = body;
      // The harness owns this body from the moment it makes it, so a later search that drops the
      // field finds the fight rather than adopting the body a second time.
      lastFightUuid = body.getUUID();
      freshAdoptions = 0;
      reattachments = 0;
      spawnShowDone = true;
      awaitingArrival = false;
      // A test body is nobody's rematch: the flag that a death leaves behind belongs to the fight
      // that ended, and the harness must not hand it to the End it is borrowing.
      awaitingRematch = false;
      riseTicks = 0;
      ceremonyTicks = 0;
      finaleTicks = 0;
      deathTicks = 0;
      afterglowTicks = 0;
      move = null;
      moveCooldown = 20;
      lastStand = false;
      // The harness body fights the same bar a real one does, because the checks that drive it are
      // about the fight's own arithmetic - a hundred-health test body would prove nothing about a
      // three-hundred-health boss.
      applyDragonHealth(body);
      phase = phaseFor(body.getHealth(), body.getMaxHealth());
      LOGGER.info("Ender dragon rework: the harness dragon is in the End at {} HP", (int)body.getHealth());
      return body;
   }

   /**
    * Undoes the harness: the body, the pools, and every machine it was driving.
    *
    * <p>The body is removed only if it is still there - the whole point of the death leg of the
    * test is that vanilla's own death removes it. The arrivals are reset so that the next real
    * dragon still gets its show, and the claim on {@code dragon} is dropped so the manager goes
    * back to looking for whatever the End actually has.
    */
   public static void harnessRelease(EnderDragon body) {
      harnessMode = false;
      lastFightUuid = null;
      clearPools();
      if (body != null && !body.isRemoved()) {
         body.discard();
      }
      if (dragon != null && dragon.isRemoved()) {
         dragon = null;
      }
      if (dragon == body) {
         dragon = null;
      }
      spawnShowDone = false;
      awaitingArrival = false;
      // The death leg of the test runs the real death, which records that a rematch is owed - and
      // that flag turns the hold off for the rest of the session (see shouldHoldTheDragon). It is
      // the End's the test borrowed, so the End gets it back clean; the next real dragon to appear
      // is the first arrival of its own fight.
      awaitingRematch = false;
      riseTicks = 0;
      ceremonyTicks = 0;
      finaleTicks = 0;
      deathTicks = 0;
      afterglowTicks = 0;
      lastStand = false;
      phase = 1;
      move = null;
      moveCooldown = MOVE_GAP_P1;
   }

   // ------------------------------------------------------------------ staff control

   /**
    * The state of the fight in one line, for {@code /ff dragon} and for a bug report.
    *
    * <p>Written to name the things a report cannot otherwise distinguish: a count-up in flight, a
    * beam that is still burning, how many pools are standing, and whether the bar's phase and the
    * rotation's phase have come apart (they can, briefly, and only here does that show).
    */
   public static String statusLine() {
      EnderDragon body = dragon;
      if (body == null || body.isRemoved()) {
         return "no dragon - rite " + (riteOpened ? "open" : "closed")
            + ", rift " + riteTicksLeft() + "t, arrival " + (awaitingArrival ? "due" : (spawnShowDone ? "done" : "not ours"));
      }
      return "dragon " + (int)body.getHealth() + "/" + (int)body.getMaxHealth() + " hp"
         + ", rotation phase " + rotationPhase()
         + " (bar says " + phaseFor(body.getHealth(), body.getMaxHealth()) + ")"
         + ", move " + (move == null ? "-" : move.label())
         + ", beam " + beamTicksLeft() + "t, pools " + POOLS.size()
         + ", arrival " + riseTicksLeft() + "t"
         + ", finale " + Math.max(0, finaleTicks) + "t, death " + Math.max(0, deathTicks) + "t"
         + (lastStand ? ", LAST STAND" : "")
         // The pieces a fight report actually needs when something is wrong: whether the music is
         // running, how many of the island's dead are up, whether the aura is still on the floor,
         // and what the busiest tick of drawing so far was asked for.
         + ", theme " + (themePlaying ? (themeTicks / 20) + "s" : "off")
         + ", protectors " + PROTECTORS.size()
         + ", aura " + auraFieldTicksLeft() + "t"
         + ", peak particles " + peakParticles + "/" + PARTICLE_CEILING;
   }

   /** {@code /ff dragon rift} - tears the rift open now, wherever the fight has got to. */
   public static boolean forceRift(ServerLevel end, ServerPlayer staff) {
      if (end == null) {
         return false;
      }
      riteOpened = false;
      riteTicks = RITE_OPEN_TICKS;
      ceremonyTicks = 0;
      // A rift a staff member tore is a fresh arrival, not the island's owed rematch: clearing this
      // is what keeps the body that comes through it on the arrival's show instead of being handed
      // the ceremony a death left waiting (see adopt).
      awaitingRematch = false;
      openRite(end, staff);
      return true;
   }

   /**
    * {@code /ff dragon phase <n>} - moves the bar, which is the only thing a phase is read from.
    *
    * <p>Deliberately the health rather than a phase field: if this ever sets a phase the rotation
    * reads from somewhere else, the command would be showing staff a fight that no player can
    * reach. Twenty health past the border, so the bar is unambiguously in the phase asked for.
    */
   public static boolean forcePhase(ServerLevel end, int want) {
      EnderDragon body = dragon;
      if (body == null || body.isRemoved()) {
         body = findDragon(end, end == null ? 0L : ServerClock.clock(end));
         if (body == null) {
            return false;
         }
         adopt(body);
      }
      float fraction = switch (want) {
         case 2 -> PHASE_TWO_AT - 0.02F;
         case 3 -> PHASE_THREE_AT - 0.02F;
         default -> 1.0F;
      };
      lastStand = false;
      finaleTicks = 0;
      deathTicks = 0;
      riseTicks = 0;
      spawnShowDone = true;
      awaitingArrival = false;
      move = null;
      moveCooldown = 20;
      body.setHealth(Math.max(LAST_STAND_HEALTH + 1.0F, body.getMaxHealth() * fraction));
      phase = phaseFor(body.getHealth(), body.getMaxHealth());
      return true;
   }

   /** {@code /ff dragon lance} - fires the breath lance now, at whatever is in front of it. */
   public static boolean forceBreathLance(ServerLevel end) {
      EnderDragon body = dragon;
      if (end == null || body == null || body.isRemoved()) {
         return false;
      }
      beginBeam(end, body);
      return true;
   }

   /** {@code /ff dragon finale} - the last stand, on the spot, from whatever health it has. */
   public static boolean forceFinale() {
      EnderDragon body = dragon;
      if (body == null || body.isRemoved() || lastStand || finaleTicks > 0 || deathTicks > 0) {
         return false;
      }
      body.setHealth(LAST_STAND_HEALTH + 1.0F);
      beginFinale(body);
      return true;
   }

   /**
    * {@code /ff dragon reset} - the machine back to the top, for another look at it.
    *
    * <p>The arrival is re-armed as well, so the one part of this rework that only happens once per
    * server life can be watched again on demand: full health, no timers, no pools, and the bar
    * counting up from nothing the way it does the first time.
    */
   public static boolean resetFight() {
      clearPools();
      BOLTS.clear();
      move = null;
      moveCooldown = MOVE_GAP_P1;
      // A wiped fight is not a fight that was won, so the rematch a death left owing is dropped with
      // the rest of it: otherwise the flag that stands the hold down would outlive the fight it
      // belonged to and the End would have no way back to an arrival.
      awaitingRematch = false;
      finaleTicks = 0;
      deathTicks = 0;
      afterglowTicks = 0;
      lastStand = false;
      phase = 1;
      riseTicks = 0;
      spawnShowDone = false;
      awaitingArrival = true;
      // Wiping the fight wipes its lasting moves too - and puts back anything the tear had drawn
      // air over, which is the one thing here that has to happen even on a wipe.
      clearLastingMoves(dragon != null && dragon.level() instanceof ServerLevel wipeLevel ? wipeLevel : null);
      novaSpent = false;
      EnderDragon body = dragon;
      if (body == null || body.isRemoved()) {
         return false;
      }
      body.setHealth(body.getMaxHealth());
      return true;
   }

   /** True while a harness run is in progress - the self-test's own switch. */
   public static boolean harnessRunning() {
      return harnessMode;
   }

   public static int currentPhase() {
      return phase;
   }

   public static boolean inLastStand() {
      return lastStand;
   }

   public static int finaleTicksLeft() {
      return Math.max(0, finaleTicks);
   }

   public static int deathTicksLeft() {
      return Math.max(0, deathTicks);
   }

   public static boolean isManaging(EnderDragon dragon) {
      return dragon != null && dragon == EnderDragonManager.dragon;
   }

   /** True while the fight has music - a check reads this rather than trusting the arrival path. */
   public static boolean themePlayingForTest() {
      return themePlaying;
   }

   /** Whether the rift has opened, which is half of "is there a fight to have music for". */
   public static boolean riteOpenedForTest() {
      return riteOpened;
   }

   /** Ticks left in a slam, or 0 - the state that must not outlive its fight. */
   public static int slamTicksLeft() {
      return Math.max(0, slamTicks);
   }

   /** Ticks left in the weightless beat, or 0. */
   public static int zeroGTicksLeft() {
      return Math.max(0, zeroGTicks);
   }

   /** Ticks left in the sky ring, or 0 - a ring that outlived its fight would keep striking. */
   public static int skyRingTicksLeft() {
      return Math.max(0, skyRingTicks);
   }

   /** Teleports left in the blink chain, or 0. */
   public static int blinkChainTicksLeft() {
      return Math.max(0, blinkTicks);
   }

   /** Ticks left in the ascension, or 0 - the window nothing can be done to the dragon in. */
   public static int ascensionTicksLeft() {
      return Math.max(0, ascensionTicks);
   }

   /** How far out the sky ring's first band stands. */
   public static double skyRingSpanForTest() {
      return SKY_RING_SPAN;
   }

   /** How much closer each band is than the one before it: the fact that the ring closes in. */
   public static double skyRingStepForTest() {
      return SKY_RING_STEP;
   }

   /** How many teleports the blink chain makes before the slam. */
   public static int blinkStepsForTest() {
      return BLINK_STEPS;
   }

   /** How long the ascension holds the body: the length of the window nothing lands in. */
   public static int ascensionBeatForTest() {
      return ASCENSION_TICKS;
   }

   /** How far a slam reaches, for a command or a check. */
   public static double slamReachForTest() {
      return SLAM_RADIUS;
   }

   /** How long the room is weightless before it is dropped. */
   public static int zeroGLiftForTest() {
      return ZERO_G_LIFT_TICKS;
   }

   /** How long the drop takes. */
   public static int zeroGDropForTest() {
      return ZERO_G_DROP_TICKS;
   }

   /**
    * Rolls a crystal's type by hand, for the one check that needs a crown and a plain crystal.
    *
    * <p>A test hook rather than a public API: the roll is random by design, and a check that wanted a
    * particular pair of crystals would otherwise have to spawn them and hope.
    */
   public static void setCrystalKindForTest(UUID id, CrystalKind kind) {
      if (kind == null) {
         CRYSTAL_KINDS.remove(id);
      } else {
         CRYSTAL_KINDS.put(id, kind);
      }
   }

   /** Forgets every crystal the roster knows about - what a check uses to clean up after itself. */
   public static void forgetCrystalsForTest() {
      clearCrystalRoster();
   }
}
