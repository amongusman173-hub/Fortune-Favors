package com.fortuneandfavors.economy;

import com.fortuneandfavors.ModItems;
import com.fortuneandfavors.ModSounds;
import com.fortuneandfavors.net.FfVfx;
import com.fortuneandfavors.util.Chat;
import com.fortuneandfavors.util.Safe;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.BossEvent.BossBarColor;
import net.minecraft.world.BossEvent.BossBarOverlay;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.phys.Vec3;

/**
 * <b>The Puppeteer</b> - the raid boss summoned by the Wooden Marionette, and the
 * three legendaries his loot box spins (Puppeteer's Mask, Marionette Strings, The
 * Empty Mask).
 *
 * <p>He is a puppet himself: a {@code setNoAi(true)} body whose every move the
 * server tick performs, exactly like the Time Lord. What he does is <b>strings</b>.
 *
 * <h2>Threaded</h2>
 * He ties strings to players. A string is visible, it pulls, and it eventually
 * snaps. The rule the whole mechanic is built around is that it <b>never takes
 * control away</b>: a pull is an impulse added on top of whatever the player is
 * already doing, so the answer to being threaded is always to keep walking and
 * keep hitting him - every landed blow on him cuts that player's string
 * immediately. Three levels:
 * <ul>
 *   <li><b>I</b> - a pull now and then, toward him.</li>
 *   <li><b>II</b> - the pulls come twice as often and harder.</li>
 *   <li><b>III</b> - a pull, plus a brief forced step: his hand moves your legs
 *       once, and then you have your body back.</li>
 * </ul>
 * A string also snaps on its own if the player gets far enough away from him, so
 * running is a real answer and not just a delay.
 *
 * <h2>Too many strings: the marionette hold</h2>
 * One string is a pull. He does not stop at one. Every time he reaches for the same
 * player again the string <b>stacks</b>, and each one pulls harder and comes round
 * faster, so the count on your screen is the whole state of the mechanic. Phase one
 * will only ever tie one to you, phase two two, phase three three - and at
 * <b>three</b> the hold stops being a pull and becomes a hand.
 *
 * <p>That is the part that mirrors the Mindbinder. He puts your arm on his strings
 * and swings it for you, at whoever is standing next to you - your own weapon, your
 * own damage, your friend's health bar. He walks your legs into range when they are
 * not close enough to reach. The damage is real because the arm is real.
 *
 * <p>Two things keep it from being a stun, and both are deliberate. You keep every
 * input you had: you can still walk, still swing, still run - this is an extra hand
 * laid over yours, not a takeover of the body. You are the one who has to be at the
 * keyboard; the Mindbinder is the one who takes the keyboard away. And it ends the
 * way a string always ends: <b>one string comes off per blow that lands on him</b>,
 * so the third hit gives you your hands back, and getting 34 blocks away drops all
 * three at once.
 *
 * <h2>The tell</h2>
 * The third string is never tied in silence. When he reaches for it he <b>winds it</b>
 * first: the target gets a boss bar counting it down, strings visibly gathering in on
 * them in a ring that closes as the timer runs out, and a system message naming both
 * ways out. Getting 18 blocks from his hands breaks it, and so does landing a blow -
 * which takes a string off and therefore leaves him with no third string to land. It
 * is the only thing in the fight that takes away something the player cannot get back
 * by moving, so it is the one thing that has to be seen coming.
 *
 * <h2>Puppet Rebirth</h2>
 * A threaded player who dies while he lives does not simply drop. Their puppet
 * stands up where they fell wearing their face, their gear and a copy of their
 * weapon, and it fights the people still standing - melee if they were a melee,
 * shooting if they carried a bow, guarding him if they were armoured. Everyone has
 * to fight their friend a second time.
 *
 * <h2>Phases</h2>
 * 100%: strings and a lash, plus a <b>volley</b> of string-tipped arrows, so a string can
 * be tied from across the arena and distance stops being a full answer to him. 60%:
 * multiple strings at once, the whole cast strung the moment the phase turns, he starts
 * dragging mobs around by their strings, and he gains the <b>reel</b> - every string he
 * already holds pulled in the same breath - and the <b>snare</b>, a knot of strings left
 * lying in the floor where somebody is about to stand. 30%: every puppet the fight has
 * created comes back at once, he gains <b>Puppet Swap</b> (he trades places with one of
 * them, so the body being hit is not always the one holding the strings), and
 * <b>Take It Back</b>: every string he is holding is reeled in and wound onto him, which
 * is what makes the count on your screen his health as well as your problem.
 *
 * <p>Every one of those is the same trick wearing a new face - a string tied further, a
 * string pulled harder, a string left behind, a string taken home - and none of them has
 * counterplay of its own, because the counterplay of strings was settled at the top of
 * this comment: hit him, or get away from his hands.
 *
 * <p>His death is the point of him: he does not vanish. Every string he owns snaps
 * one at a time, each snap audible, and the puppets stop where they stand.
 */
public final class PuppeteerManager {
   /** Entity tag marking a Puppeteer. Kept in sync with {@link #isPuppeteer(Entity)}. */
   private static final String TAG = "ff_puppeteer";
   /** Tag on the puppets this fight raised, so orphans can be swept up. */
   private static final String PUPPET_TAG = "ff_puppeteer_puppet";
   private static final String BOSS_NAME = "\u00a75\u00a7lThe Puppeteer";
   private static final String SAY = "\u00a75The Puppeteer\u00a7r\u00a77 \u203a \u00a7f";

   /** Fight tuning. Every number here is a balance knob; the caps are not. */
   private static final double MAX_HEALTH = 520.0;
   private static final double ATTACK_DAMAGE = 8.0;
   private static final int RISE_TICKS = 50;
   /** How far the fight reaches before the participants count as gone. */
   private static final double ARENA_RADIUS = 72.0;
   /** Where the phases land. */
   private static final double PHASE_TWO_AT = 0.6;
   private static final double PHASE_THREE_AT = 0.3;

   // --- threading -----------------------------------------------------------

   /** How long a string holds before it slackens by itself, per level. */
   /**
    * How long a string lasts before it goes slack on its own, per level.
    *
    * <p>Every string he ties is temporary - that is the rule that keeps the mechanic a
    * pull rather than a leash - but the top level used to hold for thirty seconds, and
    * thirty seconds of visible rope on one player is long enough that it stops reading
    * as a timer and starts reading as a state the player is stuck in. The escalation is
    * kept (each level lasts a third longer than the one before it) and the ceiling is
    * brought down to twenty seconds: long enough to chase with, short enough that a
    * fighter who is doing nothing about it still gets their body back.
    */
   private static final int THREAD_TICKS_1 = 240;
   private static final int THREAD_TICKS_2 = 320;
   private static final int THREAD_TICKS_3 = 400;
   /** Ticks between pulls, per level. Level II and III pull twice as often. */
   private static final int PULL_EVERY_1 = 30;
   private static final int PULL_EVERY_2 = 15;
   private static final int PULL_EVERY_3 = 15;
   /** The impulse a pull adds, per level. Never a teleport, never a launch. */
   private static final double PULL_POWER_1 = 0.22;
   private static final double PULL_POWER_2 = 0.34;
   private static final double PULL_POWER_3 = 0.44;
   /** A forced step at level III, and how often it may happen. */
   private static final double FORCED_STEP = 0.55;
   private static final int FORCED_STEP_EVERY = 120;
   /**
    * Distance at which a string snaps on its own. He is a ranged boss with a long
    * reach, so this is generous - but it is finite, which is what makes running a
    * real answer rather than a delay.
    */
   private static final double THREAD_SNAP_DISTANCE = 34.0;
   /** Threads he may hold at once, per phase. */
   private static final int THREADS_PHASE_1 = 1;
   private static final int THREADS_PHASE_2 = 3;
   private static final int THREADS_PHASE_3 = 4;
   /**
    * Strings he may tie to one <i>player</i>, per phase. This is the escalation: a
    * wider cast is a better show, so he widens first, and then he starts tightening.
    */
   private static final int STRINGS_PHASE_1 = 1;
   private static final int STRINGS_PHASE_2 = 2;
   private static final int STRINGS_PHASE_3 = 3;
   /**
    * The stack count at which the hold becomes <b>full</b> - his hand on your arm and
    * your legs. Three is the ceiling phase three allows, so full control is something
    * the fight has to be nearly over for, and something you can watch coming: the
    * overlay counts the strings up one at a time before it lands.
    */
   /**
    * The band a forced swing's interval is clamped to.
    *
    * <p>It used to be a single number, {@link #MARIONETTE_SWING_EVERY}, which is now the
    * fallback for a body whose attack speed cannot be read. The floor keeps a fast weapon
    * from turning his hand into a shredder; the ceiling keeps a deliberately slow one from
    * being invisible.
    */
   private static final int MARIONETTE_SWING_MIN = 14;
   private static final int MARIONETTE_SWING_MAX = 44;
   private static final int MARIONETTE_STACKS = 3;
   /** How often the strings swing your arm for you once they own it. */
   private static final int MARIONETTE_SWING_EVERY = 22;
   /** How often the strings remind you whose hands these are. */
   private static final int MARIONETTE_NUDGE_EVERY = 90;
   /** How far the strings will hunt a target for you to hit. */
   private static final double MARIONETTE_REACH = 14.0;
   /** The step they walk you at. Slower than you walk - this is a struggle. */
   private static final double MARIONETTE_STEP = 0.22;
   /** What each string after the first adds to the pull of the one before it. */
   private static final double STACK_BONUS = 0.35;
   /**
    * How long the string that is going to own you is visibly wound the <b>first</b>
    * time it happens to you in a fight.
    *
    * <p>This is the difference between a mechanic and a gotcha. Three seconds is long
    * enough to read the bar, decide, and do something about it, and short enough that
    * standing in it is a choice rather than an accident.
    */
   private static final int MARIONETTE_WINDUP_TICKS = 60;
   /**
    * How much shorter the tell gets each time it is survived.
    *
    * <p>Without this, "run or hit him" is a lesson a player only has to learn once:
    * the first windup teaches the answer, and every one after it is a free 60 ticks of
    * knowing exactly what to do. So the tell contracts - 60, then 45, then 30 - and a
    * player who has been strung twice has to already be moving when it starts. It is
    * per fight and per player: a fresh fight hands the full three seconds back, so this
    * escalates inside an engagement rather than compounding into an ambush eventually.
    */
   private static final int MARIONETTE_WINDUP_STEP = 15;
   /** ...and the floor it contracts to. Never shorter than this: a tell that cannot be
    *  reacted to is not a tell, it is a gotcha with a bar drawn over it. */
   private static final int MARIONETTE_WINDUP_MIN = 30;
   /**
    * How close a teammate has to be to the winding-up player to break it by hitting
    * him.
    *
    * <p>Generous on purpose: the point of shared counterplay is that helping should not
    * require standing in the same spot as the person being strung.
    */
   private static final double HELP_RADIUS = 24.0;
   /**
    * How far from his hands a player has to get to break the windup.
    *
    * <p>Deliberately <b>less</b> than {@link #THREAD_SNAP_DISTANCE}, and the test pins
    * that: escaping one string that is about to land must always be easier than
    * shedding every string you are carrying, or the only answer to a windup would be
    * an answer that takes longer than the windup itself.
    */
   private static final double MARIONETTE_WINDUP_ESCAPE = 18.0;
   /** How often the windup tells the player where it is up to. */
   private static final int WINDUP_NUDGE_EVERY = 20;
   /** The bar a player sees while the third string is closing. */
   private static final String WINDUP_BAR = "\u00a75\u00a7lSTRINGS CLOSING \u00a77- \u00a7frun, or hit him";

   // --- puppets -------------------------------------------------------------

   /** Health a reborn puppet has - a reduced copy, never a second life. */
   private static final double PUPPET_HEALTH = 40.0;
   /** How long a puppet raised by the fight lasts. */
   private static final int PUPPET_TICKS = 1200;
   /** Melee puppets swing this often. */
   private static final int PUPPET_SWING_EVERY = 24;
   /** Ranged puppets loose an arrow this often. */
   private static final int PUPPET_SHOT_EVERY = 34;
   /** How far a puppet will walk for a target before standing down. */
   private static final double PUPPET_CHASE = 22.0;
   /** Hard ceiling on live puppets, so a wipe cannot become a lag spike. */
   private static final int MAX_PUPPETS = 10;

   // --- abilities -----------------------------------------------------------

   private static final int LASH_COOLDOWN = 90;
   private static final int SUMMON_COOLDOWN = 200;
   private static final int SWAP_COOLDOWN = 160;
   /** Arrows in a "handful of strings" volley. */
   private static final int THREAD_SHOT_COUNT = 4;

   // --- phase rotations: the moves he gains as the fight goes on -------------
   /**
    * The four moves he gains over the course of the fight, in the order he learns them.
    *
    * <p>Each is a different <i>use</i> of the one thing he does - strings - rather than a
    * new system beside it, which is what keeps the fight readable: a volley is a string
    * tied at a distance, a reel is every string pulled at once, a snare is a string laid
    * and left, and the rewind is every string called home at a price. The counterplay is
    * unchanged in all four, because it is the counterplay of strings: hit him, or get
    * away from his hands.
    */
   /** Marks a string-tipped arrow, so a landed volley ties a string and nothing else can. */
   private static final String THREAD_ARROW_TAG = "ff_puppeteer_string_arrow";
   /** How often he may throw one. */
   private static final int VOLLEY_COOLDOWN = 220;
   /** What one string-tipped arrow is worth before it ties its string. */
   private static final float VOLLEY_ARROW_DAMAGE = 2.0F;
   /** A volley flies faster than a puppet's arrow: it is thrown, not drawn. */
   private static final double VOLLEY_ARROW_SPEED = 2.2;
   /** How often he may reel in everyone he already holds. */
   private static final int REEL_COOLDOWN = 280;
   /** The reel's impulse before the per-string bonus - far more than an ordinary pull. */
   private static final double REEL_POWER = 0.9;
   /** How often he may lay a snare. */
   private static final int SNARE_COOLDOWN = 240;
   /** How long a snare waits before its strings go slack. */
   private static final int SNARE_TICKS = 120;
   /** How close somebody has to step to a snare to be tied by it. */
   private static final double SNARE_RADIUS = 3.0;
   /** How often he may call his strings home, and what each one is worth to him. */
   private static final int REWIND_COOLDOWN = 400;
   private static final float REWIND_HEAL = 10.0F;

   // -------------------------------------------------- the final knot (phase III)
   /**
    * The one move that takes a player off the board entirely.
    *
    * <p>Everything else he does uses a body; this one <b>wears</b> it. The strings do not
    * pull an arm or walk a pair of legs, they go all the way in, and what is left standing
    * there is his to spend. It is the end of the fight's escalation - the hold is the arms,
    * the possession is the driver.
    *
    * <p>It is a <b>contest</b>, not a countdown, and the room can see it: the victim is
    * walked at his hands while their own legs fight them, and every blow landed on him by
    * <i>anyone</i> pulls one of his hands back out. Three of them and he loses the body.
    * The victim's own blow counts, a teammate's counts, and a friend who hits the victim
    * rather than the boss shakes a hand loose the same way - the same shared counterplay the
    * third string already has, because a mechanic this expensive to lose has to be
    * interruptible by the people standing next to you.
    *
    * <p>Losing it is the loudest thing in the fight: he takes <b>ten percent of his own
    * maximum health</b> off the win, and the player's body is his until he is dead. Not
    * until the timer runs out, not until they cut a string - until his body is on the floor.
    */
   private static final int KNOT_BREAKS = 3;
   /**
    * The contest, and how it contracts on a player it has already lost to once.
    *
    * <p>Five seconds, and it was fifteen. Fifteen was long enough to stop being a reaction
    * and start being a second fight: with the drag walking the victim toward his hands the
    * whole time, the loser's fate was decided in the first two seconds and the other
    * thirteen were the group watching it happen. At five the tell, the drag and the breaks
    * all still fit - the first break lands inside the first second - and the answer has to be
    * immediate, which is what a tell is for. The contraction still applies per body taken, so
    * the second one is tighter than the first.
    */
   private static final int KNOT_TICKS_1 = 100;
   private static final int KNOT_TICKS_2 = 90;
   private static final int KNOT_TICKS_3 = 80;
   /** How often the contest speaks, so it cannot nag every tick. */
   private static final long KNOT_CUE_EVERY = 40L;
   /** How often his hands walk the victim toward him, and how far each step is. */
   private static final long KNOT_DRAG_EVERY = 6L;
   private static final double KNOT_DRAG_STEP = 0.42;
   /**
    * How far clear of his hands breaks the knot.
    *
    * <p>Deliberately well inside {@link #THREAD_SNAP_DISTANCE}: the string would snap
    * anyway at a distance they cannot see, and an escape has to be something a player can
    * aim for with the information on their screen.
    */
   private static final double KNOT_ESCAPE = 20.0;
   /** How long he waits before reaching for a body this way again. */
   private static final int KNOT_COOLDOWN = 700;
   /** The share of his own maximum health that taking a body is worth to him. */
   private static final float POSSESSION_HEAL_SHARE = 0.10F;
   /** How far a worn body is turned loose on the room. */
   private static final double POSSESSION_RANGE = 40.0;
   /**
    * How far the strings let a body drift off the line they are walking it along.
    *
    * <p>A worn player is the one place in this fight where the player genuinely is not
    * driving: their velocity is <i>set</i> by the strings each tick rather than nudged, and
    * a client that has walked off that line anyway is put back on it. The slack is what
    * keeps this from being a teleport-per-tick: nothing a body can do under its own input
    * moves it a whole block inside one tick, so a block is the line between "the client
    * added a little" and "the client is somewhere else". It is also why a worn body cannot
    * be shoved off him by its friends: the strings hold against a knockback, which is the
    * point of the mechanic - the answer is his health, not theirs.
    */
   private static final double POSSESSION_PIN_SLACK = 1.0;
   /**
    * The longest a body may be worn before the strings give out on their own.
    *
    * <p>Not a mechanic - a valve. The rule is "until he is dead", and every ordinary way
    * that stops being true is handled where it happens (his death, the arena emptying, the
    * player dying, the player leaving the world). This is the guard for the case nobody
    * thought of, so that a bug in one of those paths cannot leave somebody unable to move
    * in a fight that ended an hour ago. Forty-five minutes is longer than any fight this
    * boss has ever lasted.
    */
   private static final long POSSESSION_LONGSTOP = 20L * 60L * 45L;
   /**
    * The contest's own bar, which is separate from the third string's.
    *
    * <p>Named <b>The Last String</b> on screen - the name players know the move by, and the
    * one that pays off at his death ("THE LAST STRING IS CUT"). The code keeps calling it the
    * knot.
    */
   private static final String KNOT_BAR = "\u00a75\u00a7lTHE LAST STRING";
   /**
    * How long after the third phase turns he first reaches for a body.
    *
    * <p>Short on purpose. The phase is the last thirty percent of his health, and a group that
    * is doing well burns that in well under half a minute - a move that waits ten seconds and
    * then needs a string to already be on somebody simply never showed up in most fights.
    */
   private static final long KNOT_OPENS_AFTER = 100L;
   /**
    * The least time between two hands being pulled out of one contest.
    *
    * <p>Every blow on him by anyone in the room counts, which is the point - but four people
    * swinging at once used to land all three breaks inside the same second, so the contest
    * ended before the victim had read the bar. A hand comes out at most every half second, so
    * three of them take a real (if short) effort and the escape by distance stays worth it.
    */
   private static final long KNOT_BREAK_GAP = 10L;
   /** How often the strings are seen dropping onto the contested body again. */
   private static final long KNOT_THREADS_EVERY = 20L;
   /** How long the death ceremony plays: one string cut per two ticks. */
   private static final int DEATH_TICKS = 90;

   // ------------------------------------------------------------------ the staged attacks
   /** Violet thread, his colour. */
   private static final int THREAD_VIOLET = 0x9A6CFF;
   /** Stage light. */
   private static final int LIMELIGHT = 0xFFF4D8;
   /** The paint on his mask. */
   private static final int MASK_PAINT = 0xC3283F;
   /** Spotlight: how long the light holds a spot before the doll comes down on it. */
   private static final int SPOTLIGHT_WARN = 30;
   private static final int SPOTLIGHT_COOLDOWN = 220;
   private static final double SPOTLIGHT_RADIUS = 2.5;
   private static final float SPOTLIGHT_DAMAGE = 12.0F;
   /** Marionette Rain: dolls dropped across the stage, one after another. Phase two on. */
   private static final int RAIN_COOLDOWN = 320;
   private static final int RAIN_DOLLS = 6;
   private static final double RAIN_RADIUS = 2.0;
   private static final float RAIN_DAMAGE = 8.0F;
   /** Curtain Call: anyone off his stage when the curtain falls is hauled back onto it. Phase three. */
   private static final int CURTAIN_WARN = 40;
   private static final int CURTAIN_COOLDOWN = 420;
   private static final double CURTAIN_STAGE = 6.0;
   private static final float CURTAIN_DAMAGE = 6.0F;
   /** Identical boss lines inside this window are spoken once, not three times. */
   private static final int LINE_DEDUPE_TICKS = 80;
   /**
    * How often he is allowed to say anything at all.
    *
    * <p>Two different windows, because they are two different complaints. {@code LINE_DEDUPE}
    * stops him repeating one line; this stops him <b>talking</b>, which is what a fight with
    * him actually suffered from - phase change, a cast, a knot, a puppet and a mirror all
    * have something to say, and a room trying to hear six mechanical readouts still needs the
    * channel for the ones that matter. Six seconds of quiet between taunts, and everything
    * that is <i>not</i> a taunt - a bar, a phase banner, a mechanic's own explanation - is
    * exempt, because that is the part players have to read.
    */
   private static final int TAUNT_GAP_TICKS = 120;
   private static int lastTauntTick = -1000;
   /**
    * The inherited bow's numbers. A bow's arrow is the ordinary one a full draw looses; a
    * crossbow's is flatter and harder, which is what makes a crossbow puppet read as a
    * crossbow puppet rather than as the same archer with a different model.
    */
   private static final float BOW_ARROW_DAMAGE = 2.5F;
   private static final float CROSSBOW_ARROW_DAMAGE = 4.0F;
   private static final double BOW_ARROW_SPEED = 1.9;
   private static final double CROSSBOW_ARROW_SPEED = 2.6;

   private static final Random RANDOM = new Random();
   private static final Map<UUID, Fight> FIGHTS = new HashMap<>();
   /** Live strings, keyed by the player they are tied to. One per player. */
   private static final Map<UUID, Tether> THREADS = new HashMap<>();
   /**
    * Bodies he is wearing, keyed by the player inside them.
    *
    * <p>Keyed by <b>UUID</b> and not by the body, which is what makes leaving the game not
    * break it: a player who disconnects mid-possession has no entity for a while, and the
    * mark simply waits for them. Nothing about a possession is written to disk, and it does
    * not need to be: a restart takes the fight with it, and a possession whose Puppeteer no
    * longer exists is — by the first rule of the mechanic — over. The player who comes back
    * after a restart is free, which is the only outcome that cannot soft-lock anybody.
    */
   private static final Map<UUID, Possession> POSSESSED = new LinkedHashMap<>();
   /** How many times the knot has landed on a player, for the contracting tell. */
   private static final Map<UUID, Integer> KNOTS_LANDED = new HashMap<>();
   /** Mobs he has on strings: phase two's "let us move the furniture". */
   private static final Map<UUID, MobTether> MOB_THREADS = new HashMap<>();
   private static final int MOB_THREADS_MAX = 3;
   private static final int MOB_THREAD_TICKS = 600;
   private static final int MOB_DRAG_COOLDOWN = 120;
   /**
    * Phase two's other half: the mobs he has on strings are not scenery, they are weapons.
    *
    * <p>Before this he reeled the local wildlife in and it walked into people, which is a
    * hazard rather than a mechanic - nobody has to do anything about it and nothing about
    * it is his. Now he spins one up and <b>hurls the body at somebody</b>: a zombie is not a
    * projectile in vanilla, and a zombie thrown by a puppet is unmistakably the puppet
    * talking. It is the loudest possible answer to "what are the strings FOR", and it costs
    * one new entity of nothing because the ammunition was already standing in the room.
    */
   /** Ticks he winds a tied mob up first, and the mob visibly circles him while he does. */
   private static final int MOB_HURL_WINDUP = 30;
   /** How long a thrown body stays in the air under his hand before the string reels back. */
   private static final int MOB_HURL_TICKS = 20;
   /** How fast it leaves. Fast enough to hurt, slow enough to be stepped out of. */
   private static final double MOB_HURL_SPEED = 1.25;
   /** How often he may throw one. Long, because this is a punctuation and not a damage source. */
   private static final int MOB_HURL_COOLDOWN = 160;
   /** How close a thrown body has to pass somebody to count as landing on them. */
   private static final double MOB_HURL_HIT = 2.0;
   /** ...and what that is worth. Under his own hand (8.0): the body is the delivery, he is the threat. */
   private static final float MOB_HURL_DAMAGE = 6.0F;
   /** How hard a body that lands on somebody shoves them. */
   private static final double MOB_HURL_KNOCKBACK = 0.6;
   /** Live puppets, keyed by the puppet entity. */
   /** Snares he has laid in the floor: live until they catch somebody or go slack. */
   private static final List<Snare> SNARES = new ArrayList<>();
   private static final Map<UUID, Puppet> PUPPETS = new LinkedHashMap<>();
   /** Recently spoken lines, so a repeated announce cannot spam the chat. */
   private static final Map<String, Integer> RECENT_LINES = new HashMap<>();
   /**
    * The countdown bar each player sees while the third string is closing.
    *
    * <p>One bar per person, keyed by them, for the same reason the Mindbinder keeps
    * its struggle bars that way: only the person it is about needs to see it, and it
    * has to be removable on every path out of the hold - escaped, cut, snapped,
    * expired, died, fight over.
    */
   private static final Map<UUID, ServerBossEvent> WINDUP_BARS = new HashMap<>();
   /**
    * How many times the full hold has landed on each player in this fight, which is
    * what contracts the tell - see {@link #windupTicksFor(int)}.
    *
    * <p>Counted when the hold <b>lands</b>, not when a windup is broken: the point of the
    * contraction is that a player who has already been strung knows the answer, so it
    * measures how many times they have had it happen rather than how often they escaped.
    * Cleared when the fight ends, so it escalates inside an engagement instead of
    * compounding across sessions.
    */
   private static final Map<UUID, Integer> MARIONETTE_LANDED = new HashMap<>();
   /** Our own tick counter, so fight scripts do not ride on the level clock. */
   private static int scriptTick = 0;

   private PuppeteerManager() {
   }

   // ------------------------------------------------------------------ state

   private static final class Fight {
      final UUID bossId;
      final UUID summoner;
      final ServerBossEvent bar;
      /**
       * The level he was summoned into.
       *
       * <p>Kept because a fight can outlive the body that started it: a body at zero
       * health is removed by the server, and "pay this fight out" then has nowhere to
       * drop the loot unless the arena is remembered here rather than read off the
       * corpse.
       */
      ServerLevel world;
      /**
       * Whether the payout has run. One flag for every route that can end the fight -
       * the ceremony, the ordinary death hook, and the tick that finds the body already
       * gone - so exactly one of them pays and the rest are no-ops.
       */
      boolean paid;
      final Set<UUID> participants = new HashSet<>();
      int riseTicks = RISE_TICKS;
      int phase = 1;
      boolean phaseTwoAnnounced;
      boolean phaseThreeAnnounced;
      boolean swapped;
      /** Puppets raised during this fight, in the order they stood up. */
      final List<UUID> puppets = new ArrayList<>();
      /** Threads this fight has handed out, so its death can cut them all. */
      final Set<UUID> threaded = new HashSet<>();
      long nextThread;
      long nextLash;
      long nextSummon;
      long nextSwap;
      long nextAura;
      long nextMobDrag;
      /** The four phase-rotation moves, each on its own clock. */
      long nextVolley;
      long nextReel;
      long nextSnare;
      long nextRewind;
      /** When he may reach all the way in and try to take somebody. Phase three only. */
      long nextKnot;
      boolean dying;
      int deathTicks;
      /** Strings still to be cut during the ceremony. */
      int stringsLeft;
      /** The staged attacks' clocks, and the blows they have already marked on the floor. */
      long nextSpotlight;
      long nextRain;
      long nextCurtain;
      final List<Strike> strikes = new ArrayList<>();

      Fight(UUID bossId, UUID summoner, ServerBossEvent bar) {
         this.bossId = bossId;
         this.summoner = summoner;
         this.bar = bar;
      }
   }

   /**
    * A blow marked on the floor that has not landed yet: a spotlit spot, a doll on its way down,
    * a curtain about to fall. Marked first and landed later, so every one of them is a warning
    * before it is a hit.
    */
   private static final class Strike {
      static final int SPOTLIGHT = 0;
      static final int DOLL = 1;
      static final int CURTAIN = 2;
      final int kind;
      final Vec3 at;
      final long landAt;
      final double radius;
      final float damage;
      boolean falling;

      Strike(int kind, Vec3 at, long landAt, double radius, float damage) {
         this.kind = kind;
         this.at = at;
         this.landAt = landAt;
         this.radius = radius;
         this.damage = damage;
      }
   }

   /** A string tied to a player. Never a lock: see the class comment. */
   private static final class Tether {
      final UUID bossId;
      final ServerLevel world;
      final UUID player;
      /** I, II or III - how hard it pulls and how often. */
      final int power;
      /**
       * When this string goes slack on its own.
       *
       * <p>Not final: a possession contest outlasts the string's own clock, and a string
       * that expired in the middle of one would silently cancel it - the contest would be
       * over with nobody having won it. The knot pushes it out for as long as it runs.
       */
      long expires;
      /**
       * How many strings are on this player. Starts at one and only goes up, until a
       * blow on him takes one off. At {@link #MARIONETTE_STACKS} the hand arrives.
       */
      int stacks;
      long nextPull;
      long lastForcedStep;
      /** When his hand may swing your arm again, and next tell you about it. */
      long nextForcedSwing;
      long nextNudge;
      /**
       * Who his hand is currently pointing this body at, so the name is said once when
       * it changes rather than on every forced swing.
       */
      UUID aimedAt;
      /**
       * The tick the string that owns them lands, or 0 when none is coming.
       *
       * <p>Non-zero is a <b>promise</b>, not a delay: while it is set the player can
       * see it, and two things break it - getting {@link #MARIONETTE_WINDUP_ESCAPE}
       * blocks from his hands, and landing a blow that takes a string off.
       */
      long windupUntil;
      /** How long this particular windup is, for the bar's scale. The tell contracts,
       *  so the denominator cannot be the constant. */
      int windupTotal;
      /** Next time the windup is allowed to speak, so it cannot nag every tick. */
      long nextWindupCue;
      /**
       * The final knot: the tick his hands land, or 0 when no possession is being
       * contested on this player.
       *
       * <p>Non-zero is the same kind of promise the windup is - visible for its whole life,
       * with {@link #knotsLeft} hands still to be pulled back out - and the same two things
       * break it: distance, and blows on him.
       */
      long knotUntil;
      /** How long this contest is, for its bar's scale. */
      int knotTotal;
      /** How many of his hands are still in this player. At zero he has lost the body. */
      int knotsLeft;
      long nextKnotCue;
      long nextKnotDrag;
      /** When the strings are next drawn dropping onto the contested body. */
      long nextKnotThreads;
      /** The tick the last hand was pulled out, for {@link #KNOT_BREAK_GAP}. */
      long lastKnotBreak;
      /**
       * Set when the knot lands and he takes the body outright: the string is spent, and
       * the loop that owns this map removes it rather than the call that consumed it.
       */
      boolean consumed;


      Tether(UUID bossId, ServerLevel world, UUID player, int power, int stacks, long expires, long nextPull) {
         this.bossId = bossId;
         this.world = world;
         this.player = player;
         this.power = power;
         this.stacks = stacks;
         this.expires = expires;
         this.nextPull = nextPull;
      }
   }

   /** A mob he is holding by a string. Same rule as a player's: it pulls, it ends. */
   private static final class MobTether {
      final UUID bossId;
      final ServerLevel world;
      final long expires;
      /** When he may hurl this one at somebody next. */
      long nextHurl;
      /** The tick the windup ends and the throw begins; 0 when none is coming. */
      long windupUntil;
      /** Ticks left of the flight; 0 when it is not in the air. */
      long hurlUntil;
      /** The no-AI flag the body had before the throw, so it can be put back. */
      boolean hadNoAi;

      MobTether(UUID bossId, ServerLevel world, long expires) {
         this.bossId = bossId;
         this.world = world;
         this.expires = expires;
      }
   }

   /**
    * A knot of strings laid in the floor.
    *
    * <p>Where a tied string follows somebody, a snare waits for them - the one piece of
    * his kit that is placed rather than aimed. It is visible for its whole life, and the
    * answer is the ordinary answer to everything else he does: do not stand in it.
    */
   private static final class Snare {
      final UUID bossId;
      final ServerLevel world;
      final double x;
      final double y;
      final double z;
      final long expires;
      /** When its rune ring is next sent to modded clients (a short cue, re-sent while it waits). */
      long nextCue;

      Snare(UUID bossId, ServerLevel world, double x, double y, double z, long expires) {
         this.bossId = bossId;
         this.world = world;
         this.x = x;
         this.y = y;
         this.z = z;
         this.expires = expires;
      }
   }

   /**
    * A player's body with him inside it.
    *
    * <p>The difference between this and a {@link Puppet} is whose body it is. A puppet is a
    * copy he made and can throw away; a possession is the real player, walked around by his
    * hands until his own body stops working. The world it records is the one it is being
    * worn in, so a body dragged into another dimension is let go rather than driven
    * somewhere it cannot be driven.
    */
   private static final class Possession {
      final UUID bossId;
      final ServerLevel world;
      final UUID player;
      /** The tick he took the body, for the long-stop and nothing else. */
      final long started;
      /** When the strings may swing the arm again, at the body's own pace. */
      long nextSwing;
      /** Next time the action bar is rewritten, so it is a readout and not a flood. */
      long nextCue;
      /** The point on the line his hands are walking this body along. */
      double pinX;
      double pinZ;
      boolean hasPin;

      Possession(UUID bossId, ServerLevel world, UUID player, long started) {
         this.bossId = bossId;
         this.world = world;
         this.player = player;
         this.started = started;
      }
   }

   /** A puppet that fights on his side. */
   private static final class Puppet {
      final UUID id;
      final UUID of;
      final UUID bossId;
      final String name;
      final Role role;
      /** The one trick it kept from the player it was copied from. */
      final Ability ability;
      long until;
      long nextAttack;
      long nextShot;
      /** Set when the boss dies: every puppet stops moving where it stands. */
      boolean frozen;
      /** Set when the inherited totem is spent, so it only ever revives once. */
      boolean totemSpent;

      Puppet(UUID id, UUID of, UUID bossId, String name, Role role, Ability ability, long until) {
         this.id = id;
         this.of = of;
         this.bossId = bossId;
         this.name = name;
         this.role = role;
         this.ability = ability;
         this.until = until;
      }
   }

   /** The three simplified behaviours, chosen from what the player was carrying. */
   private enum Role {
      /** Rushes whoever is nearest and swings. */
      MELEE,
      /** Keeps its distance and shoots. */
      RANGED,
      /** Protects the Puppeteer, and hits anything that walks into him. */
      TANK
   }

   /**
    * The <b>one</b> thing a puppet keeps from the player it was copied from.
    *
    * <p>A puppet is deliberately not a copy of a player's kit - a real player's skill does
    * not transfer to a body on strings, and a fight against a second version of somebody's
    * full build is a fight nobody wins. So the inheritance is exactly one trick, picked
    * from what they were actually carrying, and it is the thing that makes the copy read as
    * <i>them</i> rather than as a generic mob wearing their name:
    *
    * <ul>
    *   <li>{@link #ARROWS} - they had a bow, so it shoots. The damage and the Punch, Flame
    *       and Power come off the copied item, because the numbers should be their bow's.
    *   </li>
    *   <li>{@link #SHIELD} - they carried a shield, so it raises one while it closes, and
    *       the game's own shield pipeline does the rest. Nothing here invents a damage
    *       reduction.</li>
    *   <li>{@link #TOTEM} - they carried a totem, so it comes back once. Vanilla's own
    *       totem protection performs that revive too; this only narrates it, which keeps a
    *       puppet's second life looking exactly like a player's.</li>
    * </ul>
    *
    * <p>The order matters and is fixed: a bow beats a shield beats a totem. One trick each,
    * so "what did that thing keep?" always has a single answer.
    */
   private enum Ability {
      ARROWS,
      SHIELD,
      TOTEM,
      NONE
   }

   // ------------------------------------------------------------- public API

   public static boolean isPuppeteer(Entity entity) {
      return entity != null && entity.entityTags().contains(TAG);
   }

   /**
    * Test hook: his bar's progress, or -1 when he has no fight.
    *
    * <p>Asserted from the self-test because the failure this replaced was invisible from
    * every other angle: the entity's health was wrong in no way at all, and the only
    * broken thing was a number nobody was writing.
    */
   public static float barProgressForTest(Entity boss) {
      Fight fight = boss == null ? null : FIGHTS.get(boss.getUUID());
      return fight == null || fight.bar == null ? -1.0F : fight.bar.getProgress();
   }

   /** Test hook: the name on his bar, or an empty string when he has no fight. */
   public static String barNameForTest(Entity boss) {
      Fight fight = boss == null ? null : FIGHTS.get(boss.getUUID());
      return fight == null || fight.bar == null ? "" : fight.bar.getName().getString();
   }

   /**
    * Test hook: end every live fight and take the bodies out of the world.
    *
    * <p>A check that stages a boss has to be able to hand the world back exactly as it
    * found it - a Puppeteer left standing for the next check to trip over is how a
    * passing suite turns into a failing one the moment the order changes.
    */
   public static void clearForTest(MinecraftServer server) {
      for (Fight fight : new ArrayList<>(FIGHTS.values())) {
         Entity raw = findEntity(server, fight.bossId);
         if (raw != null) {
            raw.discard();
         }
         release(server, fight);
      }
      FIGHTS.clear();
      THREADS.clear();
      SNARES.clear();
      MARIONETTE_LANDED.clear();
      // Every fight above was released, which freed its bodies; these are the leftovers of a
      // fight whose state was already gone, and a test hands the world back with nobody worn.
      POSSESSED.clear();
      KNOTS_LANDED.clear();
      clearAllWindupBars();
      if (server != null) {
         // Every tagged body, fight or no fight. A released fight leaves its boss
         // standing - a released fight is a fight nobody is in, not a corpse - so a
         // body that outlives its fight is an immovable, AI-less Evoker in the arena,
         // and the *next* summon in that arena finds a Puppeteer with no fight behind
         // him and reads it as its own. Collected before anything is discarded, because
         // the live view does not survive being written to while it is being walked.
         for (ServerLevel level : server.getAllLevels()) {
            List<Entity> strays = new ArrayList<>();
            for (Entity entity : level.getAllEntities()) {
               if (entity instanceof Mob mob && isPuppeteer(mob)) {
                  strays.add(mob);
               }
            }
            for (Entity stray : strays) {
               stray.discard();
            }
         }
         clearPuppets(server, false);
      }
   }

   /** How many Puppeteers are alive right now (the {@code /ff boss} surface). */
   public static int activeCount() {
      return FIGHTS.size();
   }

   /** True while this player has a string on them. Used by the damage hooks. */
   public static boolean isThreaded(ServerPlayer player) {
      return player != null && THREADS.containsKey(player.getUUID());
   }

   /** Every live puppet's entity id - the sweep the self-test and teardown use. */
   public static int livePuppets() {
      return PUPPETS.size();
   }

   public static void onServerStopping(MinecraftServer server) {
      for (Fight fight : new ArrayList<>(FIGHTS.values())) {
         Safe.run("puppeteer teardown", () -> release(server, fight));
      }
      FIGHTS.clear();
      THREADS.clear();
      MOB_THREADS.clear();
      SNARES.clear();
      clearPuppets(server, false);
      clearAllWindupBars();
      MARIONETTE_LANDED.clear();
      RECENT_LINES.clear();
      // Released fights freed their bodies already; nothing about a possession may survive into
      // the next boot of this process (an integrated server reopening a world).
      POSSESSED.clear();
      KNOTS_LANDED.clear();
   }

   /** Ends every active fight immediately - boss gone, bar gone, puppets gone. */
   public static int abandonAll(MinecraftServer server) {
      int ended = 0;
      for (Fight fight : new ArrayList<>(FIGHTS.values())) {
         Safe.run("puppeteer abandon", () -> {
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
      THREADS.clear();
      SNARES.clear();
      clearAllWindupBars();
      MARIONETTE_LANDED.clear();
      POSSESSED.clear();
      KNOTS_LANDED.clear();
      clearPuppets(server, false);
      return ended;
   }

   // -------------------------------------------------------------- summoning

   /** Wooden Marionette right-click: hang it up and he comes to collect it. */
   public static String useWoodenMarionette(ServerPlayer player, ItemStack held) {
      String err = summon(player);
      if (err == null) {
         held.shrink(1);
      }
      return err;
   }

   public static String summon(ServerPlayer summoner) {
      if (!ModConfig.is("boss")) {
         return "Bosses are disabled on this server.";
      }
      if (summoner == null) {
         return "The strings go slack.";
      }
      for (Fight f : FIGHTS.values()) {
         if (summoner.getUUID().equals(f.summoner)) {
            return "You already have a Puppeteer - finish him first!";
         }
      }

      ServerLevel level = summoner.level();
      Mob boss = (Mob)EntityTypes.EVOKER.create(level, EntitySpawnReason.COMMAND);
      if (boss == null) {
         return "The strings go slack before anything climbs down them.";
      }

      AttributeInstance maxHp = boss.getAttribute(Attributes.MAX_HEALTH);
      if (maxHp != null) {
         maxHp.setBaseValue(MAX_HEALTH);
      }
      boss.setHealth((float)MAX_HEALTH);
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
      // He is not a fight until he has finished being lowered into one. The descent is
      // fifty ticks of a boss who cannot move, cannot answer a swing and has not said a
      // word yet, and every other hand-driven boss in the mod is untouchable for exactly
      // that window - he was the one that was not, so an arriving Puppeteer could be
      // burst down before his own arrival announcement finished.
      boss.setInvulnerable(true);
      boss.addTag(TAG);
      BossManager.markBoss(boss);
      // He is lowered: he arrives hanging from a string above the arena, and the
      // string is let out until he is standing in it.
      double x = summoner.getX();
      double y = summoner.getY();
      double z = summoner.getZ();
      boss.setPos(x, y + 6.5, z);
      boss.setYRot(summoner.getYRot());
      boss.setXRot(0.0F);
      level.addFreshEntity(boss);
      // A body is not guaranteed by the line above: `addFreshEntity` answers with a
      // boolean, and dropping it is how a summon reports success with nothing in the
      // world. The server refuses a body into a chunk that is not ticking yet with no
      // error at all - the arena reads as loaded from the outside - and what is left
      // behind is a health bar over an empty floor: he never swings, because there is
      // nothing there to swing with, and the fight pays out nothing when it ends.
      // So the body is looked for, the chunk is brought in and the add is tried once
      // more; a second refusal is reported instead of building a fight around it.
      if (level.getEntity(boss.getUUID()) == null) {
         level.getChunk(boss.blockPosition().getX() >> 4, boss.blockPosition().getZ() >> 4);
         level.addFreshEntity(boss);
      }
      if (level.getEntity(boss.getUUID()) == null) {
         return "The strings go slack before anything climbs down them.";
      }

      ServerBossEvent bar = new ServerBossEvent(
         UUID.randomUUID(), Component.literal(BOSS_NAME), BossBarColor.PURPLE, BossBarOverlay.PROGRESS
      );
      bar.setVisible(true);
      if (level.getServer() != null) {
         for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
            bar.addPlayer(p);
         }
      }

      Fight fight = new Fight(boss.getUUID(), summoner.getUUID(), bar);
      fight.world = level;
      fight.participants.add(summoner.getUUID());
      long now = ServerClock.clock(level);
      fight.nextThread = now + 100L;
      fight.nextLash = now + 60L;
      fight.nextSummon = now + 400L;
      fight.nextSwap = now + 600L;
      fight.nextAura = now + 20L;
      // The first of the phase-rotation moves comes soon enough to be part of the first
      // phase; the rest are gated on where his health is, not on this clock alone.
      fight.nextVolley = now + 200L;
      fight.nextReel = now + 360L;
      fight.nextSnare = now + 480L;
      fight.nextRewind = now + 700L;
      fight.nextSpotlight = now + 140L;
      fight.nextRain = now + 300L;
      fight.nextCurtain = now + 400L;
      FIGHTS.put(boss.getUUID(), fight);

      announce(level, "\u00a75\u00a7l\u2726 \u00a75Strings drop out of nowhere. \u00a7fThe Puppeteer \u00a75comes down on them.");
      // The stage is set before he reaches it: a ring of sigils where he will land, a shaft of
      // limelight from above, and motes drifting round the spot like dust in a footlight.
      Vec3 mark = new Vec3(x, y, z);
      Fx.runeCircle(level, ParticleTypes.END_ROD, mark.add(0.0, 0.05, 0.0), 3.5, RISE_TICKS + 10, THREAD_VIOLET);
      Fx.pillar(level, ParticleTypes.END_ROD, mark, 14.0, LIMELIGHT);
      Fx.petals(level, ParticleTypes.END_ROD, mark.add(0.0, 0.5, 0.0), 2.5, RISE_TICKS, LIMELIGHT);
      // The strings he comes down on: they drop out of the dark onto the mark, hang taut for the
      // whole descent, and snap the moment his feet touch the floor. One cue, timed to the rise.
      Fx.threads(level, ParticleTypes.END_ROD, mark, 9.0, RISE_TICKS + 6, THREAD_VIOLET);
      level.playSound(null, x, y, z, ModSounds.BOSS_SPAWN, SoundSource.HOSTILE, 1.2F, 1.5F);
      level.playSound(null, x, y, z, SoundEvents.VEX_CHARGE, SoundSource.HOSTILE, 1.0F, 0.6F);
      Advancements.grant(summoner, "summon_puppeteer");
      return null;
   }

   // ------------------------------------------------------------------- tick

   public static void tick(MinecraftServer server) {
      scriptTick++;
      Safe.run("puppeteer threads", () -> tickThreads(server));
      Safe.run("puppeteer possessions", () -> tickPossessed(server));
      Safe.run("puppeteer mob strings", () -> tickMobThreads(server));
      Safe.run("puppeteer snares", () -> tickSnares(server));
      Safe.run("puppeteer puppets", () -> tickPuppets(server));
      // A tagged body with no fight behind him is not a boss, it is a leftover: a fight
      // that was released without a death - everyone walked away, an operator cleaned up,
      // a reload dropped the state - leaves an immovable, AI-less Evoker standing in the
      // arena that cannot be commanded and does not answer anything. Swept on a slow
      // clock, because this is a repair rather than a mechanic, and swept *before* the
      // early return below, because a leftover is exactly the case with no fights.
      if (scriptTick % 100 == 0) {
         Safe.run("puppeteer stray sweep", () -> sweepStrayBosses(server));
      }

      if (FIGHTS.isEmpty()) {
         return;
      }
      long now = ServerClock.clock(server.overworld());
      for (Fight fight : new ArrayList<>(FIGHTS.values())) {
         Safe.run("puppeteer tick", () -> tickFight(server, fight, now));
      }
   }

   /** Reaps tagged Puppeteers with no fight behind them. See the call site in {@link #tick}. */
   private static void sweepStrayBosses(MinecraftServer server) {
      if (server == null) {
         return;
      }
      for (ServerLevel level : server.getAllLevels()) {
         List<Entity> strays = new ArrayList<>();
         for (Entity entity : level.getAllEntities()) {
            if (entity instanceof Mob mob && isPuppeteer(mob) && !FIGHTS.containsKey(mob.getUUID())) {
               strays.add(mob);
            }
         }
         for (Entity stray : strays) {
            stray.discard();
         }
      }
   }

   private static void tickFight(MinecraftServer server, Fight fight, long now) {
      Entity raw = findEntity(server, fight.bossId);
      if (!(raw instanceof Mob boss) || !boss.isAlive()) {
         // His body is gone without the ceremony having run, which means something other
         // than a player's last blow ended him: an operator's /kill, a command, a plugin,
         // the void, or the server's own cleanup. Releasing here is what "the boss just
         // dies, without attacking or dropping anything" looked like from inside the
         // arena - the fight was torn down and the payout went with it. The fight is paid
         // out first now (once, guarded by `paid`), then released.
         if (raw instanceof Mob deadBoss) {
            Safe.run("puppeteer found-dead payout", () -> onBossDeath(deadBoss));
         }
         release(server, fight);
         return;
      }
      ServerLevel level = (ServerLevel)boss.level();
      fight.world = level;
      // A rise that was cut short - a reloaded fight, a body that never finished being
      // lowered - must not leave an invulnerable boss standing in the arena swallowing
      // every blow. One comparison a tick cannot be skipped, the same rule the Elder
      // Warden's own arrival follows.
      if (fight.riseTicks <= 0 && boss.isInvulnerable() && !fight.dying) {
         boss.setInvulnerable(false);
      }
      now = ServerClock.clock(level);
      refreshParticipants(level, boss, fight);
      syncBar(fight, boss);

      // 1) Death ceremony - he is finished, we are just cutting the strings.
      if (fight.dying) {
         tickDeath(level, boss, fight);
         return;
      }

      // 2) Arrival: he is lowered on his own strings.
      if (fight.riseTicks > 0) {
         fight.riseTicks--;
         // He is on a string: the line runs up out of the arena and he comes down it. Modded
         // clients already see it as the threads cue sent at the summon, so the per-tick dotted
         // line is the vanilla version only, and only every other tick.
         if (fight.riseTicks % 2 == 0) {
            vanillaLine(level, ParticleTypes.END_ROD, new Vec3(boss.getX(), boss.getY() + 1.6, boss.getZ()), new Vec3(boss.getX(), boss.getY() + 12.0, boss.getZ()), 10);
         }
         boss.setPos(boss.getX(), boss.getY() - 0.13, boss.getZ());
         if (fight.riseTicks % 8 == 0) {
            level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.TRIPWIRE_ATTACH, SoundSource.HOSTILE, 0.8F, 0.6F);
         }
         if (fight.riseTicks == 0) {
            // He is standing in the arena now, so he can be hit.
            boss.setInvulnerable(false);
            level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.VEX_AMBIENT, SoundSource.HOSTILE, 1.2F, 0.5F);
            // He lands, the light flares, and the floor rings out from his feet.
            Fx.flare(level, ParticleTypes.END_ROD, boss.position().add(0.0, 1.6, 0.0), 2.2, LIMELIGHT);
            Fx.shockwave(level, ParticleTypes.SOUL, boss.position(), 6.0, THREAD_VIOLET);
            Fx.starburst(level, ParticleTypes.END_ROD, boss.position().add(0.0, 1.4, 0.0), 4.0, THREAD_VIOLET);
            announce(level, SAY + "\"\u00a7fPlaces, everyone.\"");
         }
         return;
      }

      // 3) Nobody left? The show closes.
      if (fight.participants.isEmpty()) {
         despawn(server, level, boss, fight, "Nobody is left to watch - the strings go slack.");
         return;
      }

      // 3b) Everybody left is somebody he is already wearing. There is nobody to fight and
      // nobody to watch, so he takes his leave - and the leaving is what frees the body,
      // because the rule holding it is his presence. See onlyDollsLeft.
      if (onlyDollsLeft(server, fight)) {
         despawn(server, level, boss, fight, "the room is his, and a room with nobody in it is not worth staying in.");
         return;
      }

      // 4) Phase changes.
      float fraction = boss.getMaxHealth() <= 0.0F ? 1.0F : boss.getHealth() / boss.getMaxHealth();
      if (fight.phase == 1 && fraction <= PHASE_TWO_AT) {
         fight.phase = 2;
         fight.phaseTwoAnnounced = true;
         if (fight.bar != null) {
            fight.bar.setColor(BossBarColor.RED);
         }
         announce(level, SAY + "\"\u00a7fAct two. \u00a7dMore strings.\"");
         level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.EVOKER_PREPARE_SUMMON, SoundSource.HOSTILE, 1.2F, 0.6F);
         stringBurst(level, boss, 60);
         // Act two: a ring of mask paint opens under him and the stage light turns red.
         Fx.runeCircle(level, ParticleTypes.END_ROD, boss.position().add(0.0, 0.05, 0.0), 5.0, 40, MASK_PAINT);
         Fx.heartbeat(level, ParticleTypes.SOUL, boss.position(), 6.0, 36, MASK_PAINT);
         // He threads everyone at once the moment the phase turns: the rule of the fight
         // has to change in a way the room can see, and a cast that suddenly covers
         // everybody is that change. One string each, because the tightening is what
         // stacking is for and it should still have to be earned.
         threadEveryoneNew(level, boss, fight);
      }
      if (fight.phase == 2 && fraction <= PHASE_THREE_AT) {
         fight.phase = 3;
         fight.phaseThreeAnnounced = true;
         if (fight.bar != null) {
            fight.bar.setColor(BossBarColor.BLUE);
         }
         announce(level, SAY + "\"\u00a7fFinal act. \u00a7dEveryone back on stage.\"");
         level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.EVOKER_CAST_SPELL, SoundSource.HOSTILE, 1.4F, 0.5F);
         stringBurst(level, boss, 90);
         // The final act: every string he owns spirals up off him into the flies.
         Fx.spiral(level, ParticleTypes.END_ROD, boss.position(), 9.0, 40, THREAD_VIOLET);
         Fx.voidCollapse(level, ParticleTypes.REVERSE_PORTAL, boss.position().add(0.0, 1.4, 0.0), 6.0, 20, THREAD_VIOLET);
         summonAllPuppets(level, boss, fight);
         // The Last String is the last thing he has, and it is not the first thing he does the
         // moment the phase turns: the phase turn is already a cast, a summon and a colour
         // change, and a fight should never open a phase with its most expensive move. Five
         // seconds, though, not ten - the phase is short (see KNOT_OPENS_AFTER).
         fight.nextKnot = now + KNOT_OPENS_AFTER;
         // Take It Back's clock started at the summon, so by the time the phase turned it had
         // long since expired - and it sat ahead of the knot in the rotation. The very first
         // thing he did in phase three was therefore reel in and cut every string in the room,
         // and the knot, which needs a string to pull on, found nobody to reach. That is the
         // main reason the Last String stopped appearing. The rewind now waits its turn.
         fight.nextRewind = Math.max(fight.nextRewind, now + KNOT_OPENS_AFTER + 200L);
      }

      // 4b) Blows already marked on the floor land on their own clock, target or no target.
      tickStrikes(level, boss, fight, now);

      // 5) Ambient: he is always trailing strings, so he reads as a thing on wires.
      if (now >= fight.nextAura) {
         fight.nextAura = now + 8L;
         drawIdleStrings(level, boss);
      }

      ServerPlayer target = nearestTarget(level, boss, fight);
      if (target == null) {
         return;
      }

      // 6) Abilities, in priority order, each independently gated.
      // 6-) The Last String: phase three's signature, checked before everything else - even the thread cast, which used to
      // return first and starve it - so a
      // busy rotation can never starve it. It picks its own target (see knotTarget) and ties
      // the string it pulls on if nobody is holding one, so it no longer depends on a string
      // happening to be on somebody at the moment its clock comes round - which, with every
      // blow on him cutting one, was almost never.
      if (fight.phase >= 3 && now >= fight.nextKnot && beginKnot(level, boss, fight, knotTarget(level, boss, fight))) {
         fight.nextKnot = now + KNOT_COOLDOWN;
         return;
      }

      int wantThreads = fight.phase == 1 ? THREADS_PHASE_1 : (fight.phase == 2 ? THREADS_PHASE_2 : THREADS_PHASE_3);
      if (now >= fight.nextThread) {
         // A wider cast first - it is the better show, and it is what the phase is
         // for. Only once everyone he can reach is holding one does he start
         // tightening the strings on somebody he already has.
         if (threadsHeld(fight) < wantThreads && threadSomeoneNew(level, boss, fight, target)) {
            fight.nextThread = now + (fight.phase == 1 ? 160L : 100L);
            return;
         }
         if (stackThread(level, boss, fight)) {
            fight.nextThread = now + (fight.phase == 1 ? 160L : 100L);
            return;
         }
         fight.nextThread = now + 40L;
      }

      // 6a) The staged attacks: every one is marked on the floor before it lands.
      if (now >= fight.nextSpotlight) {
         fight.nextSpotlight = now + SPOTLIGHT_COOLDOWN;
         if (spotlight(level, boss, fight, target, now)) {
            return;
         }
      }
      if (fight.phase >= 2 && now >= fight.nextRain) {
         fight.nextRain = now + RAIN_COOLDOWN;
         marionetteRain(level, boss, fight, now);
         return;
      }
      if (fight.phase >= 3 && now >= fight.nextCurtain) {
         fight.nextCurtain = now + CURTAIN_COOLDOWN;
         curtainCall(level, boss, fight, now);
         return;
      }

      // 6b) The phase-rotation moves, gated on how far the fight has come. Each is
      // independently clocked, so a fight whose rotation is interrupted by a summon or a
      // lash simply reaches for it again on the next opportunity.
      if (now >= fight.nextVolley) {
         fight.nextVolley = now + VOLLEY_COOLDOWN;
         threadVolley(level, boss);
         return;
      }
      if (fight.phase >= 2 && now >= fight.nextSnare) {
         fight.nextSnare = now + SNARE_COOLDOWN;
         if (laySnare(level, boss, target)) {
            return;
         }
      }
      // The reel and the rewind are both conditional on him actually holding something, so
      // their clocks only start when the move lands - a boss with empty hands reaches for
      // them again next tick rather than burning the cooldown on nothing.
      if (fight.phase >= 2 && now >= fight.nextReel && reelThreads(level, boss, fight)) {
         fight.nextReel = now + REEL_COOLDOWN;
         return;
      }
      if (fight.phase >= 3 && now >= fight.nextRewind && rewindStrings(level, boss, fight)) {
         fight.nextRewind = now + REWIND_COOLDOWN;
         return;
      }
      if (fight.phase >= 3 && !fight.swapped && now >= fight.nextSwap) {
         fight.nextSwap = now + SWAP_COOLDOWN;
         if (puppetSwap(level, boss, fight)) {
            return;
         }
      }
      if (fight.phase >= 2 && now >= fight.nextMobDrag) {
         fight.nextMobDrag = now + MOB_DRAG_COOLDOWN;
         dragMobs(level, boss, fight);
      }
      if (fight.phase >= 2 && now >= fight.nextSummon) {
         fight.nextSummon = now + SUMMON_COOLDOWN;
         summonAllPuppets(level, boss, fight);
         return;
      }
      if (now >= fight.nextLash) {
         fight.nextLash = now + LASH_COOLDOWN;
         stringLash(level, boss, fight, target);
         return;
      }

      // 7) Baseline pressure: he closes to about three blocks and holds there.
      double dist = Math.sqrt(boss.distanceToSqr(target));
      if (dist > 3.2) {
         Vec3 toward = target.position().subtract(boss.position()).normalize().scale(0.24);
         boss.setDeltaMovement(toward.x, 0.0, toward.z);
         stepClearOfBlocks(level, boss, toward.x, toward.z);
      }
      BossGrounding.resurface(level, boss, 0.6);
      boss.setYRot(faceYaw(boss, target));
      // A mote off his crown for vanilla clients; modded ones see the idle string cues.
      Fx.vanilla(level, ParticleTypes.END_ROD, boss.getX(), boss.getY() + 2.1, boss.getZ(), 1, 0.5, 0.3, 0.5, 0.01);

      // A slow melee under the strings, so standing on top of him is not free.
      if (dist < 3.4 && now % 30L == 0L) {
         target.hurtServer(level, level.damageSources().mobAttack(boss), 5.0F);
         level.playSound(null, target.getX(), target.getY(), target.getZ(), SoundEvents.PLAYER_ATTACK_CRIT, SoundSource.HOSTILE, 0.7F, 0.8F);
      }
   }

   // ------------------------------------------------------------------ the staged attacks

   /**
    * Spotlight: a circle of light settles on the player and holds there, and a doll comes down on
    * it. One spot in phase one, two in phase two, three in phase three - on different players
    * where there are enough of them.
    */
   private static boolean spotlight(ServerLevel level, Mob boss, Fight fight, ServerPlayer target, long now) {
      int spots = Math.min(fight.phase, 3);
      List<ServerPlayer> marks = new ArrayList<>();
      marks.add(target);
      for (ServerPlayer p : level.getPlayers(pl -> pl.isAlive() && !pl.isSpectator() && fight.participants.contains(pl.getUUID()))) {
         if (marks.size() >= spots) {
            break;
         }
         if (!marks.contains(p) && !isPossessed(p)) {
            marks.add(p);
         }
      }
      for (ServerPlayer p : marks) {
         Vec3 at = p.position();
         fight.strikes.add(new Strike(Strike.SPOTLIGHT, at, now + SPOTLIGHT_WARN, SPOTLIGHT_RADIUS, SPOTLIGHT_DAMAGE));
         Fx.runeCircle(level, ParticleTypes.END_ROD, at.add(0.0, 0.05, 0.0), SPOTLIGHT_RADIUS, SPOTLIGHT_WARN, LIMELIGHT);
         Fx.pillar(level, ParticleTypes.END_ROD, at, 12.0, LIMELIGHT);
         p.sendOverlayMessage(Component.literal("\u00a7e\u2726 \u00a7fYou're in the spotlight \u00a78- \u00a7fmove."));
      }
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.BEACON_POWER_SELECT, SoundSource.HOSTILE, 1.2F, 1.6F);
      announce(level, SAY + "\"\u00a7fLights.\"");
      return true;
   }

   /** Marionette Rain: dolls dropped one after another on marked spots across the stage. */
   private static void marionetteRain(ServerLevel level, Mob boss, Fight fight, long now) {
      int dolls = RAIN_DOLLS + (fight.phase >= 3 ? 2 : 0);
      for (int i = 0; i < dolls; i++) {
         // Half the dolls are aimed at players, half are scattered; nobody is safe standing still.
         Vec3 at;
         List<ServerPlayer> room = level.getPlayers(pl -> pl.isAlive() && !pl.isSpectator() && fight.participants.contains(pl.getUUID()) && !isPossessed(pl));
         if (i % 2 == 0 && !room.isEmpty()) {
            ServerPlayer p = room.get(RANDOM.nextInt(room.size()));
            at = p.position().add((RANDOM.nextDouble() - 0.5) * 2.0, 0.0, (RANDOM.nextDouble() - 0.5) * 2.0);
         } else {
            double a = RANDOM.nextDouble() * Math.PI * 2.0;
            double r = 3.0 + RANDOM.nextDouble() * 8.0;
            at = boss.position().add(Math.cos(a) * r, 0.0, Math.sin(a) * r);
         }
         at = new Vec3(at.x, groundAt(level, at), at.z);
         long land = now + 25L + i * 5L;
         fight.strikes.add(new Strike(Strike.DOLL, at, land, RAIN_RADIUS, RAIN_DAMAGE));
         Fx.runeCircle(level, ParticleTypes.END_ROD, at.add(0.0, 0.05, 0.0), RAIN_RADIUS, (int)(land - now), MASK_PAINT);
      }
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.EVOKER_PREPARE_SUMMON, SoundSource.HOSTILE, 1.2F, 1.3F);
      announce(level, SAY + "\"\u00a7fHeads up.\"");
   }

   /**
    * Curtain Call: the stage is marked round him, and when the curtain falls everybody standing
    * off it is hauled back on by the strings and hurt for the trouble. The answer is to come in.
    */
   private static void curtainCall(ServerLevel level, Mob boss, Fight fight, long now) {
      Vec3 stage = boss.position();
      fight.strikes.add(new Strike(Strike.CURTAIN, stage, now + CURTAIN_WARN, CURTAIN_STAGE, CURTAIN_DAMAGE));
      Fx.dome(level, ParticleTypes.END_ROD, stage, CURTAIN_STAGE, CURTAIN_WARN, THREAD_VIOLET);
      Fx.runeCircle(level, ParticleTypes.END_ROD, stage.add(0.0, 0.05, 0.0), CURTAIN_STAGE, CURTAIN_WARN, LIMELIGHT);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.BELL_RESONATE, SoundSource.HOSTILE, 1.4F, 0.6F);
      announce(level, SAY + "\"\u00a7fCurtain call. \u00a7dOn stage, all of you.\"");
   }

   private static void tickStrikes(ServerLevel level, Mob boss, Fight fight, long now) {
      if (fight.strikes.isEmpty()) {
         return;
      }
      for (Iterator<Strike> it = fight.strikes.iterator(); it.hasNext();) {
         Strike strike = it.next();
         if (strike.kind != Strike.CURTAIN && !strike.falling && now >= strike.landAt - 6L) {
            // The doll is seen falling for the last few ticks, so the landing is never a surprise.
            strike.falling = true;
            Fx.comet(level, ParticleTypes.END_ROD, strike.at.add(0.0, 14.0, 0.0), strike.at.add(0.0, 0.6, 0.0), 6,
               strike.kind == Strike.SPOTLIGHT ? LIMELIGHT : MASK_PAINT);
         }
         if (now < strike.landAt) {
            continue;
         }
         it.remove();
         if (strike.kind == Strike.CURTAIN) {
            fallCurtain(level, boss, fight, strike);
         } else {
            landDoll(level, boss, fight, strike);
         }
      }
   }

   private static void landDoll(ServerLevel level, Mob boss, Fight fight, Strike strike) {
      Fx.shockwave(level, ParticleTypes.CLOUD, strike.at, strike.radius + 1.0, strike.kind == Strike.SPOTLIGHT ? LIMELIGHT : MASK_PAINT);
      Fx.shatter(level, new net.minecraft.core.particles.BlockParticleOption(ParticleTypes.BLOCK, net.minecraft.world.level.block.Blocks.OAK_PLANKS.defaultBlockState()),
         strike.at.add(0.0, 0.6, 0.0), 1.4, 0x9A6A3C);
      level.playSound(null, strike.at.x, strike.at.y, strike.at.z, SoundEvents.ARMOR_STAND_BREAK, SoundSource.HOSTILE, 1.4F, 0.7F);
      level.playSound(null, strike.at.x, strike.at.y, strike.at.z, SoundEvents.WOOD_BREAK, SoundSource.HOSTILE, 1.2F, 0.6F);
      double r2 = strike.radius * strike.radius;
      for (ServerPlayer p : level.getPlayers(pl -> pl.isAlive() && !pl.isSpectator() && !BossManager.isFakePlayer(pl) && !isPossessed(pl))) {
         if (p.distanceToSqr(strike.at) > r2) {
            continue;
         }
         p.hurtServer(level, level.damageSources().mobAttack(boss), strike.damage);
         p.setDeltaMovement(p.getDeltaMovement().add(0.0, 0.45, 0.0));
         p.hurtMarked = true;
      }
   }

   private static void fallCurtain(ServerLevel level, Mob boss, Fight fight, Strike strike) {
      Vec3 stage = boss.position();
      Fx.shockwave(level, ParticleTypes.END_ROD, stage, strike.radius, THREAD_VIOLET);
      level.playSound(null, stage.x, stage.y, stage.z, SoundEvents.TRIPWIRE_CLICK_ON, SoundSource.HOSTILE, 1.6F, 0.5F);
      int hauled = 0;
      for (ServerPlayer p : level.getPlayers(pl -> pl.isAlive() && !pl.isSpectator() && !BossManager.isFakePlayer(pl) && !isPossessed(pl)
            && fight.participants.contains(pl.getUUID()))) {
         double d = Math.sqrt(p.distanceToSqr(stage));
         if (d <= strike.radius || d > ARENA_RADIUS) {
            continue;
         }
         Vec3 pull = stage.subtract(p.position());
         Vec3 flat = new Vec3(pull.x, 0.0, pull.z);
         if (flat.lengthSqr() > 1.0E-4) {
            flat = flat.normalize().scale(Math.min(2.4, 0.8 + d * 0.08));
            p.setDeltaMovement(flat.x, 0.5, flat.z);
            p.hurtMarked = true;
         }
         p.hurtServer(level, level.damageSources().mobAttack(boss), strike.damage);
         Fx.chains(level, ParticleTypes.END_ROD, boss.position().add(0.0, 1.6, 0.0), p.position().add(0.0, 1.0, 0.0), THREAD_VIOLET);
         hauled++;
      }
      if (hauled == 0) {
         announce(level, SAY + "\"\u00a7fGood. \u00a7dEveryone's where I want them.\"");
      }
   }

   /** The floor under a point: the first block with a solid top, searched a few blocks either way. */
   private static double groundAt(ServerLevel level, Vec3 at) {
      net.minecraft.core.BlockPos pos = net.minecraft.core.BlockPos.containing(at.x, at.y + 3.0, at.z);
      for (int i = 0; i < 9; i++) {
         net.minecraft.core.BlockPos below = pos.below();
         if (level.getBlockState(below).isFaceSturdy(level, below, net.minecraft.core.Direction.UP) && level.getBlockState(pos).isAir()) {
            return pos.getY();
         }
         pos = below;
      }
      return at.y;
   }

   /**
    * Steps a hand-moved puppet horizontally without putting it inside terrain.
    *
    * <p>A {@code setNoAi(true)} boss has no walking of its own, so "step toward the
    * player" is a step <i>through</i> whatever is in the way. Same shape as the Time
    * Lord's: full step, one axis, or nothing.
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

   // --------------------------------------------------------------- threading

   private static int threadsHeld(Fight fight) {
      int held = 0;
      for (UUID id : fight.threaded) {
         if (THREADS.containsKey(id)) {
            held++;
         }
      }
      return held;
   }

   /**
    * Ties a string to one player.
    *
    * <p>The level is read from the fight's phase, and the string is announced twice:
    * once on the action bar so the player cannot miss that something is on them, and
    * once in chat naming the counterplay. A mechanic that takes movement away from
    * somebody and does not tell them how to get it back is the mechanic people quit
    * over.
    */
   private static boolean threadPlayer(ServerLevel level, Mob boss, Fight fight, ServerPlayer target) {
      if (target == null || THREADS.containsKey(target.getUUID())) {
         return false;
      }
      int threadLevel = Math.max(1, Math.min(3, fight.phase));
      long now = ServerClock.clock(level);
      int length = threadLevel == 1 ? THREAD_TICKS_1 : (threadLevel == 2 ? THREAD_TICKS_2 : THREAD_TICKS_3);
      int every = threadLevel == 1 ? PULL_EVERY_1 : (threadLevel == 2 ? PULL_EVERY_2 : PULL_EVERY_3);
      THREADS.put(
         target.getUUID(),
         new Tether(fight.bossId, level, target.getUUID(), threadLevel, 1, now + length, now + every)
      );
      fight.threaded.add(target.getUUID());
      announce(level, SAY + "\"\u00a7fHold still.\"");
      level.playSound(null, target.getX(), target.getY(), target.getZ(), SoundEvents.TRIPWIRE_ATTACH, SoundSource.HOSTILE, 1.1F, 0.7F);
      // The string drops onto them from the flies, then runs back to his hand.
      Fx.threads(level, ParticleTypes.END_ROD, target.position(), 6.0, 24, THREAD_VIOLET);
      Fx.vanilla(level, ParticleTypes.END_ROD, target.getX(), target.getY() + 1.2, target.getZ(), 12, 0.4, 0.6, 0.4, 0.05);
      target.sendOverlayMessage(Component.literal(threadLine(1)));
      target.sendSystemMessage(
         Component.literal(
            "\u00a75\u2726 \u00a7fA string is tied to you \u00a78- \u00a7fit pulls, it does not hold, and \u00a7flanding a blow on him cuts it."
         )
      );
      return true;
   }

   /**
    * Somebody new to tie. The nearest unstrung player first, because that is who the
    * fight is about, and never somebody who already has one - that is a stack.
    *
    * <p>Widening comes before tightening, so this is the only path that adds a fresh
    * player to the cast; {@link #stackThread} is what thickens an existing hold.
    */
   private static boolean threadSomeoneNew(ServerLevel level, Mob boss, Fight fight, ServerPlayer preferred) {
      if (preferred != null && !THREADS.containsKey(preferred.getUUID())) {
         return threadPlayer(level, boss, fight, preferred);
      }
      ServerPlayer best = null;
      double bestDistance = Double.MAX_VALUE;
      for (ServerPlayer p : level.getPlayers(pl -> isRealTarget(pl) && pl.distanceToSqr(boss) < ARENA_RADIUS * ARENA_RADIUS)) {
         if (THREADS.containsKey(p.getUUID())) {
            continue;
         }
         double distance = p.distanceToSqr(boss);
         if (distance < bestDistance) {
            bestDistance = distance;
            best = p;
         }
      }
      return best != null && threadPlayer(level, boss, fight, best);
   }

   /**
    * Another string on somebody he already holds.
    *
    * <p>He never stands idle with strings in his hand. Once the cast is as wide as the
    * phase allows he starts on the ones he has, and because the count is what makes
    * the hold a hand, this is the ability that eventually takes a player's arm. The
    * target is chosen deliberately: fewest strings first (so nobody is rushed past the
    * others into full control), and nearest him to break ties.
    */
   private static boolean stackThread(ServerLevel level, Mob boss, Fight fight) {
      int ceiling = stringsPerPlayer(fight.phase);
      ServerPlayer best = null;
      int fewest = Integer.MAX_VALUE;
      double nearest = Double.MAX_VALUE;
      for (UUID id : new ArrayList<>(fight.threaded)) {
         Tether thread = THREADS.get(id);
         if (thread == null || thread.stacks >= ceiling) {
            continue;
         }
         ServerPlayer p = level.getServer() == null ? null : level.getServer().getPlayerList().getPlayer(id);
         if (p == null || !p.isAlive() || p.level() != level) {
            continue;
         }
         double distance = p.distanceToSqr(boss);
         if (thread.stacks < fewest || (thread.stacks == fewest && distance < nearest)) {
            fewest = thread.stacks;
            nearest = distance;
            best = p;
         }
      }
      if (best == null) {
         return false;
      }
      Tether thread = THREADS.get(best.getUUID());
      // The string that takes the body is never tied in silence. He starts it, the
      // player watches it come, and it can be broken before it lands - which is the
      // whole difference between this and being switched off from behind.
      if (thread.stacks + 1 >= MARIONETTE_STACKS) {
         return beginWindup(level, best, thread);
      }
      thread.stacks++;
      // The next pull arrives on the new cadence, not the old one, or the extra string
      // would visibly do nothing for half a minute.
      thread.nextPull = Math.min(thread.nextPull, ServerClock.clock(level) + stackedPullTicks(thread.power, thread.stacks));
      level.playSound(null, best.getX(), best.getY(), best.getZ(), SoundEvents.TRIPWIRE_ATTACH, SoundSource.HOSTILE, 1.0F, 0.6F + thread.stacks * 0.15F);
      // Another string comes down on them, and the knot at their feet tightens.
      Fx.threads(level, ParticleTypes.END_ROD, best.position(), 5.0, 20, THREAD_VIOLET);
      Fx.ring(level, ParticleTypes.END_ROD, best.position().add(0.0, 0.1, 0.0), 0.9 + thread.stacks * 0.3, THREAD_VIOLET);
      best.sendOverlayMessage(Component.literal(threadLine(thread.stacks)));
      return true;
   }

   // ------------------------------------------------------- the windup: the tell

   /**
    * Starts the visible windup on the string that would own the player.
    *
    * <p>This exists because the third string is the only thing in the fight that takes
    * something from the player they cannot get back by moving, and a mechanic like that
    * has to be <b>seen</b> before it lands or it is just an ambush. So the third string
    * is not tied on the tick he reaches for it - he winds it, the target gets a bar and
    * a countdown and strings visibly closing on them, and there are two ways out, both
    * of which are the existing answers to strings generally:
    *
    * <ul>
    *   <li><b>Get distance.</b> {@link #MARIONETTE_WINDUP_ESCAPE} blocks from his hands
    *       breaks it - less than the distance that snaps a string, so this is always
    *       the cheaper escape of the two.</li>
    *   <li><b>Hit him.</b> A landed blow takes a string off, and with it the windup,
    *       because there is no longer a third string to land.</li>
    * </ul>
    *
    * @return whether a windup is now running - false when one already was, so a repeat
    *         reach cannot restart the clock on a player who is already watching it
    */
   private static boolean beginWindup(ServerLevel level, ServerPlayer player, Tether thread) {
      if (thread.windupUntil > 0L) {
         return false;
      }
      long now = ServerClock.clock(level);
      // The tell contracts with how many times it has already landed on this player, so
      // it is not a lesson that only has to be learned once.
      int ticks = windupTicksFor(marionettesLanded(player));
      thread.windupUntil = now + ticks;
      thread.windupTotal = ticks;
      thread.nextWindupCue = now;
      showWindupBar(player, ticks, ticks);
      // The third string is seen coming the whole way: strings drop onto the spot and hang
      // there, taut, for exactly as long as the windup runs. The closing ring is drawn per beat
      // in tickWindup, because the player is moving and the ring has to follow them.
      Fx.threads(level, ParticleTypes.END_ROD, player.position(), 7.0, ticks, MASK_PAINT);
      announce(level, SAY + "\"\u00a7fOne more. \u00a7dHold still.\"");
      player.sendOverlayMessage(Component.literal("\u00a75\u00a7lSTRINGS CLOSING \u00a78| \u00a7frun, or hit him"));
      player.sendSystemMessage(
         Component.literal(
            "\u00a75\u2726 \u00a7dHe is winding a third string \u00a78- \u00a7fthis is the one that moves you. \u00a7dGet "
               + (int)MARIONETTE_WINDUP_ESCAPE + " blocks away, or land a blow on him to take a string off.\""
         )
      );
      level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.TRIPWIRE_ATTACH, SoundSource.HOSTILE, 1.0F, 1.4F);
      level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.VEX_CHARGE, SoundSource.HOSTILE, 0.7F, 1.2F);
      return true;
   }

   /**
    * One tick of the windup: cue, countdown, and the two ways it can break.
    *
    * <p>The cancellation checks run <b>before</b> the completion check on purpose, so a
    * player who breaks it on the exact tick it would have landed gets the escape rather
    * than the hold. Reactions that arrive on the last frame should count.
    */
   private static void tickWindup(ServerLevel level, ServerPlayer player, Tether thread, long now, double distance) {
      if (distance > MARIONETTE_WINDUP_ESCAPE) {
         cancelWindup(level, player, thread, "\u00a7aYou pull clear of the closing strings - the third one never lands.");
         return;
      }
      if (thread.stacks < MARIONETTE_STACKS - 1) {
         cancelWindup(level, player, thread, "\u00a7aYou cut the strings back - he starts the third one over.");
         return;
      }
      if (now >= thread.windupUntil) {
         thread.windupUntil = 0L;
         thread.stacks++;
         thread.nextPull = Math.min(thread.nextPull, now + stackedPullTicks(thread.power, thread.stacks));
         clearWindupBar(player.getUUID());
         landMarionette(level, player);
         return;
      }

      long left = thread.windupUntil - now;
      // The strings are gathering on them, and the nearer the landing the thicker they
      // get, so the visual is a countdown and not just decoration.
      double share = 1.0 - (double)left / Math.max(1, thread.windupTotal);
      int ring = 2 + (int)(share * 7.0);
      drawWindup(level, player, ring, share);
      updateWindupBar(player, left, thread.windupTotal);

      if (now >= thread.nextWindupCue) {
         thread.nextWindupCue = now + WINDUP_NUDGE_EVERY;
         player.sendOverlayMessage(
            Component.literal(
               "\u00a75\u00a7lSTRINGS CLOSING \u00a78| \u00a7f" + (left / 20L + 1L) + "s \u00a77- \u00a7frun, or hit him"
            )
         );
         level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.TRIPWIRE_CLICK_ON, SoundSource.HOSTILE, 0.6F, 1.1F + (float)share * 0.9F);
      }
   }

   /** Breaks the windup and tells the player which of the two answers they found. */
   private static void cancelWindup(ServerLevel level, ServerPlayer player, Tether thread, String why) {
      thread.windupUntil = 0L;
      clearWindupBar(player.getUUID());
      // Broken windups are announced to everyone in earshot: a teammate who saved
      // somebody needs to see that it worked, and the person who did it themselves needs
      // the feedback more than anyone.
      player.sendOverlayMessage(Component.literal(threadLine(thread.stacks)));
      player.sendSystemMessage(Component.literal("\u00a75\u2726 \u00a7fThe windup slackens \u00a78- \u00a7f" + why));
      level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.TRIPWIRE_DETACH, SoundSource.HOSTILE, 1.0F, 0.7F);
      Fx.starburst(level, ParticleTypes.END_ROD, player.position().add(0.0, 1.2, 0.0), 2.2, THREAD_VIOLET);
   }

   /** The moment the hand arrives - the loudest thing he does in the whole fight. */
   private static void landMarionette(ServerLevel level, ServerPlayer player) {
      // Counted here, at the landing, because this is the moment the player has learned
      // the tell. Next time it will be shorter.
      MARIONETTE_LANDED.merge(player.getUUID(), 1, Integer::sum);
      announce(level, SAY + "\"\u00a7fThere. \u00a7dDance.\"");
      player.sendOverlayMessage(Component.literal(threadLine(MARIONETTE_STACKS)));
      player.sendSystemMessage(
         Component.literal(
            "\u00a75\u2726 \u00a7fThree strings \u00a78- \u00a7dhe has your arm and your legs now, and it is not him he is aiming at."
         )
      );
      player.sendSystemMessage(Component.literal("\u00a7fLand a blow on him to cut one string off. \u00a7dThree blows and you are free."));
      level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.VEX_CHARGE, SoundSource.HOSTILE, 1.3F, 0.6F);
      // The hand arrives: a flash of mask paint at the chest and the strings pulled tight.
      Fx.flare(level, ParticleTypes.SOUL, player.position().add(0.0, 1.2, 0.0), 1.6, MASK_PAINT);
      Fx.threads(level, ParticleTypes.SOUL, player.position(), 6.0, 30, THREAD_VIOLET);
   }

   /**
    * Strings gathering on the target, inwards.
    *
    * <p>Spawned on an expanding <i>ring</i> that closes as the timer runs down, so the
    * distance a player sees the strings start from is itself the countdown. Anyone
    * standing nearby sees it too, which is what makes it a call to help rather than a
    * private warning.
    */
   private static void drawWindup(ServerLevel level, ServerPlayer player, int points, double share) {
      double radius = 3.0 - share * 2.0;
      long now = ServerClock.clock(level);
      long step = now / 2L;
      // Modded clients: the closing ring as a cue every four ticks (each one shorter than the
      // last, so it visibly tightens), following the player wherever they run.
      if (now % 4L == 0L) {
         cue(level, FfVfx.RING, ParticleTypes.END_ROD, player.position().add(0.0, 0.1, 0.0), Vec3.ZERO, Math.max(0.6, radius), 0.0,
            share > 0.75 ? MASK_PAINT : THREAD_VIOLET);
      }
      Fx.vanillaOnly(() -> {
         for (int i = 0; i < points; i++) {
            double angle = step * 0.35 + i * ((Math.PI * 2.0) / points);
            FfVfx.particles(
               level,
               ParticleTypes.END_ROD,
               player.getX() + Math.cos(angle) * radius,
               player.getY() + 1.0 + Math.sin(step * 0.2 + i) * 0.35,
               player.getZ() + Math.sin(angle) * radius,
               1,
               0.0,
               0.0,
               0.0,
               0.0
            );
         }
         if (share > 0.75) {
            FfVfx.particles(level, ParticleTypes.CRIT, player.getX(), player.getY() + 1.1, player.getZ(), 4, 0.3, 0.4, 0.3, 0.02);
         }
      });
   }

   private static void showWindupBar(ServerPlayer player, long remainingTicks, int totalTicks) {
      try {
         ServerBossEvent bar = WINDUP_BARS.get(player.getUUID());
         if (bar == null) {
            bar = new ServerBossEvent(UUID.randomUUID(), Component.literal(WINDUP_BAR), BossBarColor.PURPLE, BossBarOverlay.PROGRESS);
            bar.setVisible(true);
            bar.addPlayer(player);
            WINDUP_BARS.put(player.getUUID(), bar);
         }
         bar.setProgress(fraction(remainingTicks, totalTicks));
      } catch (Throwable ignored) {
      }
   }

   private static void updateWindupBar(ServerPlayer player, long remainingTicks, int totalTicks) {
      ServerBossEvent bar = WINDUP_BARS.get(player.getUUID());
      if (bar == null) {
         showWindupBar(player, remainingTicks, totalTicks);
         return;
      }
      try {
         bar.setProgress(fraction(remainingTicks, totalTicks));
      } catch (Throwable ignored) {
      }
   }

   /** Shared by both bar writes, so a contracted tell cannot be drawn off-scale. */
   private static float fraction(long remainingTicks, int totalTicks) {
      return Math.max(0.0F, Math.min(1.0F, (float)remainingTicks / Math.max(1, totalTicks)));
   }

   private static void clearWindupBar(UUID player) {
      ServerBossEvent bar = WINDUP_BARS.remove(player);
      if (bar != null) {
         bar.removeAllPlayers();
         bar.setVisible(false);
      }
   }

   private static void clearAllWindupBars() {
      for (ServerBossEvent bar : new ArrayList<>(WINDUP_BARS.values())) {
         try {
            bar.removeAllPlayers();
            bar.setVisible(false);
         } catch (Throwable ignored) {
         }
      }
      WINDUP_BARS.clear();
   }

   /** The action-bar line, which doubles as the mechanic's whole readout. */
   private static String threadLine(int stacks) {
      if (isMarionetteHold(stacks)) {
         return "\u00a75\u00a7lSTRINGS " + roman(stacks) + " \u00a78| \u00a7fthey are moving you - \u00a7dhit him to cut one";
      }
      return "\u00a75\u00a7lTHREADED " + roman(stacks) + " \u00a78| \u00a7fhit him to cut it";
   }

   private static void tickThreads(MinecraftServer server) {
      if (THREADS.isEmpty()) {
         return;
      }
      for (Iterator<Map.Entry<UUID, Tether>> it = THREADS.entrySet().iterator(); it.hasNext();) {
         Map.Entry<UUID, Tether> entry = it.next();
         Tether thread = entry.getValue();
         ServerPlayer player = server.getPlayerList().getPlayer(thread.player);
         Entity boss = findEntity(server, thread.bossId);
         long now = ServerClock.clock(thread.world);

         if (player == null || !player.isAlive() || boss == null || !boss.isAlive()) {
            it.remove();
            clearWindupBar(thread.player);
            continue;
         }
         // A string does not reach between dimensions. The distance below would be measured
         // across two worlds' coordinates, and a pull would shove somebody in the Nether
         // toward a point in the overworld - so a portal is simply the longest way of running.
         if (player.level() != boss.level()) {
            it.remove();
            clearWindupBar(thread.player);
            player.sendOverlayMessage(Component.literal("\u00a77The string snaps - \u00a7fhis hands do not reach this far."));
            continue;
         }
         double distance = player.distanceTo(boss);
         if (distance > THREAD_SNAP_DISTANCE) {
            it.remove();
            clearWindupBar(thread.player);
            player.sendOverlayMessage(Component.literal("\u00a77The string snaps - \u00a7fyou are too far from his hands."));
            thread.world.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.TRIPWIRE_DETACH, SoundSource.HOSTILE, 1.0F, 1.2F);
            continue;
         }
         if (now >= thread.expires) {
            it.remove();
            clearWindupBar(thread.player);
            player.sendOverlayMessage(Component.literal("\u00a77The string goes slack on its own."));
            thread.world.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.TRIPWIRE_DETACH, SoundSource.HOSTILE, 0.8F, 1.4F);
            continue;
         }

         // Always visible: a line from your chest to his hands. Every third tick - the beam a
         // modded client draws lingers between cues, and the vanilla dots linger longer still.
         if (now % 3L == 0L) {
            drawThread(thread.world, boss, player);
         }

         // The final knot, if one is running on this player, is the thing happening to
         // them: everything else the strings do to them pauses while it is contested.
         if (thread.knotUntil > 0L) {
            tickKnot(thread.world, player, thread, now, distance);
            if (thread.consumed) {
               // He took the body: the string is spent. Removed through the iterator, not
               // from the map inside the call that consumed it, because a map changed
               // underneath this loop while it is walking itself is a concurrent
               // modification and would take the rest of the tick's strings with it.
               it.remove();
            }
            continue;
         }

         // The third string is a promise before it is a fact: it winds up in view, and
         // either of the two answers to strings breaks it before it lands. This runs
         // first because while it is set it is the thing happening to this player.
         if (thread.windupUntil > 0L) {
            tickWindup(thread.world, player, thread, now, distance);
         }

         // Three strings and his hand is on their arm, which is a different mechanic
         // from a pull and owns its own cadence.
         if (isMarionetteHold(thread.stacks)) {
            marionetteHold(thread.world, player, thread, now);
         }

         if (now < thread.nextPull) {
            continue;
         }
         thread.nextPull = now + stackedPullTicks(thread.power, thread.stacks);

         Vec3 toBoss = boss.position().subtract(player.position());
         Vec3 flat = new Vec3(toBoss.x, 0.0, toBoss.z);
         if (flat.lengthSqr() < 1.0E-4) {
            continue;
         }
         // A pull is an impulse, never a lock: it is added to whatever the player is
         // already doing, so holding the key that runs away still works - it is just
         // slower. This is the line between a mechanic and a stun. Every string after
         // the first makes the same pull stronger and more frequent.
         Vec3 toward = flat.normalize().scale(stackedPullPower(thread.power, thread.stacks));
         player.setDeltaMovement(player.getDeltaMovement().add(toward.x, 0.0, toward.z));
         player.hurtMarked = true;
         // The tug lands as a snap of thread at the chest, thrown the way he is pulling.
         Fx.clash(thread.world, ParticleTypes.CRIT, player.position().add(0.0, 1.0, 0.0), flat.normalize(), THREAD_VIOLET);
         thread.world.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.TRIPWIRE_CLICK_ON, SoundSource.HOSTILE, 0.7F, 1.5F);

         // Level III: once in a while his hand moves your legs, and then lets go.
         // Not once he has all three strings - at that point the legs are his anyway
         // and marionetteHold is walking them, so this would be a second hand on the
         // same limb.
         if (!isMarionetteHold(thread.stacks) && thread.power >= 3 && now - thread.lastForcedStep >= FORCED_STEP_EVERY) {
            thread.lastForcedStep = now;
            double angle = RANDOM.nextDouble() * Math.PI * 2.0;
            player.setDeltaMovement(
               player.getDeltaMovement().add(Math.cos(angle) * FORCED_STEP, 0.18, Math.sin(angle) * FORCED_STEP)
            );
            player.hurtMarked = true;
            player.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 20, 0, false, false, false));
            player.sendOverlayMessage(Component.literal("\u00a75Your legs move without you."));
            thread.world.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.VEX_CHARGE, SoundSource.HOSTILE, 0.7F, 1.4F);
            Fx.ring(thread.world, ParticleTypes.SOUL, player.position().add(0.0, 0.1, 0.0), 0.9, THREAD_VIOLET);
         }
      }
   }

   /**
    * The marionette hold: your arm and your legs, on his strings.
    *
    * <p>This is the escalation the whole stacking rule exists for, and it is the same
    * idea as the Mindbinder's full control with one important difference - he does not
    * take the keyboard. The player keeps every input they had; what arrives is an
    * extra hand laid over theirs. So the counterplay is not "struggle free before it
    * kills you", it is the string counterplay it always was, just three times: land a
    * blow on him, and one string comes off.
    *
    * <p>Who he aims at is the point. Not the Puppeteer - the person standing next to
    * the player. The damage is real ({@code weaponDamage} of their own held item),
    * because the arm doing the swinging is theirs, and a friend taking a sword from
    * their ally needs to see a name on the death message that makes sense.
    */
   private static void marionetteHold(ServerLevel level, ServerPlayer player, Tether thread, long now) {
      // The Empty Mask's own escape is a decoy, so the strings close on the decoy.
      // Cheap to honour, and it is the one item in the fight built to shrug a hold off.
      if (PuppeteerGear.isEscaping(player)) {
         return;
      }
      if (now >= thread.nextNudge) {
         thread.nextNudge = now + MARIONETTE_NUDGE_EVERY;
         player.sendOverlayMessage(Component.literal(threadLine(thread.stacks)));
      }

      LivingEntity victim = stringsTarget(level, player);
      if (victim == null) {
         // Nobody to use them on. The strings simply pull tighter, which the ordinary
         // pull above is already doing.
         return;
      }
      // He aims your head as well as your hands, and he says whose name it is pointed at:
      // being turned on a friend is the part a player has to understand in time to shout
      // about it, and a hold that only moves the body reads as being shoved rather than
      // as being *used*.
      if (!victim.getUUID().equals(thread.aimedAt)) {
         thread.aimedAt = victim.getUUID();
         player.sendSystemMessage(
            Component.literal("\u00a75\u2726 \u00a7dHis hand turns your head \u00a78- \u00a7fyou are aiming at " + victim.getName().getString() + "\u00a7d.")
         );
      }
      double distance = player.distanceTo(victim);
      if (distance > 2.6) {
         // Legs: walked into range. A step, not a haul - it is slower than walking so
         // a player leaning on the movement keys is still the one in charge of where
         // they end up, which is what keeps this a struggle instead of a leash. What it
         // is *not* is a fixed crawl: he walks the legs he was given, at the pace they
         // were built for, so a player who drank a Speed potion is walked faster by the
         // same hand that a plain player has to out-walk by holding the key.
         Vec3 direction = victim.position().subtract(player.position());
         Vec3 flat = new Vec3(direction.x, 0.0, direction.z);
         if (flat.lengthSqr() > 1.0E-4) {
            Vec3 step = flat.normalize().scale(MARIONETTE_STEP * bodyPace(player));
            player.setDeltaMovement(player.getDeltaMovement().add(step.x, 0.0, step.z));
            player.hurtMarked = true;
         }
      }
      if (distance <= 3.6 && now >= thread.nextForcedSwing) {
         // The interval is the weapon's own, so his hand is only ever as fast as the arm
         // it is on (see swingEveryFor).
         thread.nextForcedSwing = now + swingEveryFor(player);
         forcedSwing(level, player, victim);
      }
   }

   /**
    * How fast this body's own legs are, as a multiplier on one forced step.
    *
    * <p>Vanilla's movement-speed attribute is 0.1 for an unmodified player, and every
    * Speed effect, sprint and modifier is expressed against it - so dividing by that is
    * the same arithmetic the game uses, and the hand inherits the body it is on. It is
    * floored so a player who has been slowed to a standstill is not walked at a
    * fraction of a step (which would read as the strings being broken rather than as a
    * slow body being moved slowly).
    */
   private static double bodyPace(ServerPlayer player) {
      double speed = player.getAttributeValue(Attributes.MOVEMENT_SPEED);
      if (!(speed > 0.0) || !Double.isFinite(speed)) {
         return 1.0;
      }
      return Math.max(0.5, Math.min(2.0, speed / 0.1));
   }

   /**
    * How often his hand may swing on a body holding the weapon it currently holds.
    *
    * <p>He uses your arm, so he swings it at your arm's speed: the interval is vanilla's
    * own attack cooldown, {@code 20 / attack_speed}, read off the player's live attribute
    * (which includes whatever the held item contributes). The old number was a flat 22
    * ticks whatever they carried, which is why "he is making you hit your friends" felt
    * like a light show: a sword that would swing for its owner every thirteen ticks swung
    * for him every twenty-two, and a bow or a hoe swung at exactly the same rate as a
    * blade.
    *
    * <p>Clamped to the band the fight is balanced around, so no held item can turn the arm
    * into a shredder and a deliberately slow weapon is still visibly slow.
    */
   private static int swingEveryFor(ServerPlayer player) {
      return marionetteSwingEveryFor(player.getAttributeValue(Attributes.ATTACK_SPEED));
   }

   /**
    * Who his hands point them at.
    *
    * <p>Another player first, because a friend is the only thing worth making somebody
    * hit - and only where PvP is allowed between the two of them, so the hold can never
    * be used to launder a kill through somebody who never consented to the fight. If
    * nobody is eligible, the nearest ordinary mob will do; never a marked boss and
    * never a Puppeteer.
    */
   private static LivingEntity stringsTarget(ServerLevel level, ServerPlayer player) {
      LivingEntity best = null;
      double bestDistance = MARIONETTE_REACH * MARIONETTE_REACH;
      // Real people only: his own puppets are fake players standing in this list too, and a
      // hand that spent the hold swinging at his own dolls would be no threat at all.
      for (ServerPlayer other : level.getPlayers(
         pl -> isRealTarget(pl) && !pl.getUUID().equals(player.getUUID())
      )) {
         double distance = other.distanceToSqr(player);
         if (distance < bestDistance && ClaimManager.pvpAllowed(player, other)) {
            bestDistance = distance;
            best = other;
         }
      }
      if (best != null) {
         return best;
      }
      for (Mob mob : level.getEntitiesOfClass(
         Mob.class,
         player.getBoundingBox().inflate(MARIONETTE_REACH),
         m -> m.isAlive() && !BossManager.isMarkedBoss(m) && !isPuppeteer(m)
      )) {
         double distance = mob.distanceToSqr(player);
         if (distance < bestDistance) {
            bestDistance = distance;
            best = mob;
         }
      }
      return best;
   }

   /**
    * His hand on your arm, swinging your own weapon.
    *
    * <p>The swing is broadcast as an animation packet because the client never sent
    * one - without it the hit lands out of nowhere on the other player's screen, which
    * reads as a bug rather than as a boss. Local difficulty is taken from the held item
    * rather than a number of his own, so the damage is exactly what that player's own
    * sword would have done.
    */
   private static void forcedSwing(ServerLevel level, ServerPlayer player, LivingEntity victim) {
      try {
         if (level.getServer() != null) {
            level.getServer().getPlayerList().broadcastAll(new ClientboundAnimatePacket(player, 0));
         }
         float damage = BossManager.weaponDamage(level, player, player.getMainHandItem(), victim);
         victim.hurtServer(level, level.damageSources().playerAttack(player), damage);
         // His swing, drawn in his colours: an arc of mask paint off the arm he is using.
         Fx.slash(level, ParticleTypes.CRIT, player.position(), victim.position().subtract(player.position()), 2.6, MASK_PAINT);
         level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.TRIPWIRE_CLICK_ON, SoundSource.HOSTILE, 0.6F, 1.6F);
      } catch (Throwable ignored) {
      }
   }

   /**
    * Cuts one string off this player, and returns how many are left on them.
    *
    * <p>Called when they land a blow on him. One at a time is what makes the stack
    * count matter: a player who has been fully strung has to earn each hand back, and
    * the number on their action bar is always the honest state of the hold.
    */
   public static int cutThread(ServerPlayer player) {
      if (player == null) {
         return 0;
      }
      Tether thread = THREADS.get(player.getUUID());
      if (thread == null) {
         return 0;
      }
      // A blow landed, so the third string can no longer land either - that is the
      // pre-emptive answer to the windup, and it has to be honoured on the same tick
      // the hit is, not on the next one, or a player who reacts in time gets owned by a
      // string that arrived a tick after they stopped it.
      boolean stoppedWindup = thread.windupUntil > 0L;
      if (stoppedWindup) {
         thread.windupUntil = 0L;
         clearWindupBar(player.getUUID());
      }
      thread.stacks--;
      if (stoppedWindup) {
         player.sendSystemMessage(
            Component.literal("\u00a7a\u2726 \u00a7fYour blow lands in time \u00a78- \u00a7fthe third string was still winding, and it slackens.")
         );
      }
      if (thread.stacks > 0) {
         player.sendOverlayMessage(Component.literal(threadLine(thread.stacks)));
         player.sendSystemMessage(
            Component.literal(
               "\u00a75\u2726 \u00a7fYour blow lands \u00a78- \u00a7da string is cut, and \u00a7f" + thread.stacks + " \u00a7dstill have you."
            )
         );
      } else {
         THREADS.remove(player.getUUID());
         // Any bar this string was carrying goes with it. The windup's is cleared above; a
         // contest's bar used to stay on screen forever when its string was cut from under it.
         clearWindupBar(player.getUUID());
         player.sendOverlayMessage(Component.literal("\u00a75\u2726 The last string parts \u00a78| \u00a7fyou have your body back"));
         player.sendSystemMessage(Component.literal("\u00a75\u2726 \u00a7fYour blow lands \u00a78- \u00a7dthe strings tied to you are cut."));
      }
      if (player.level() instanceof ServerLevel level) {
         // The cut thread flashes where it parts - brighter, in stage light, when it was the last.
         Fx.starburst(level, ParticleTypes.END_ROD, player.position().add(0.0, 1.2, 0.0), 1.8, thread.stacks > 0 ? THREAD_VIOLET : LIMELIGHT);
         level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.TRIPWIRE_DETACH, SoundSource.PLAYERS, 1.2F, 1.5F);
      }
      return thread.stacks;
   }

   /**
    * Cuts every string on everyone, and returns the count.
    *
    * <p>The death ceremony needs the same cut as a landed blow, but with nobody to
    * congratulate - and with the boss's strings, not the player's, as the subject.
    */
   private static int cutAllThreads(ServerLevel level, Fight fight) {
      int cut = 0;
      for (UUID id : new ArrayList<>(fight.threaded)) {
         if (THREADS.remove(id) == null) {
            continue;
         }
         cut++;
         clearWindupBar(id);
         ServerPlayer player = level.getServer() == null ? null : level.getServer().getPlayerList().getPlayer(id);
         if (player != null && player.isAlive()) {
            player.sendOverlayMessage(Component.literal("\u00a75The string is cut."));
            if (player.level() == level) {
               Fx.starburst(level, ParticleTypes.END_ROD, player.position().add(0.0, 1.2, 0.0), 1.6, THREAD_VIOLET);
            }
         }
      }
      return cut;
   }

   // ---------------------------------------------------------------- puppets

   /**
    * Phase two's other half: he has strings on the mobs, too.
    *
    * <p>Anything standing near him gets tied and pulled in - a wandering zombie, a
    * cow, whatever the arena happened to have. It is the loudest possible way to
    * say what his strings are for, and it turns the local wildlife into a hazard
    * without adding a single new entity.
    */
   private static void dragMobs(ServerLevel level, Mob boss, Fight fight) {
      int held = 0;
      for (Mob mob : level.getEntitiesOfClass(
         Mob.class,
         boss.getBoundingBox().inflate(24.0),
         m -> m.isAlive() && !BossManager.isMarkedBoss(m)
      )) {
         if (MOB_THREADS.containsKey(mob.getUUID())) {
            held++;
            continue;
         }
         if (held >= MOB_THREADS_MAX) {
            break;
         }
         long attached = ServerClock.clock(level);
         MobTether fresh = new MobTether(fight.bossId, level, attached + MOB_THREAD_TICKS);
         // Not thrown the instant it is tied: the string has to be visibly on it first, or a
         // mob that walks into range and is immediately airborne reads as a bug.
         fresh.nextHurl = attached + MOB_HURL_COOLDOWN;
         MOB_THREADS.put(mob.getUUID(), fresh);
         held++;
         drawString(
            level,
            mob.getX(),
            mob.getY() + mob.getBbHeight() * 0.8,
            mob.getZ(),
            boss.getX(),
            boss.getY() + 2.0,
            boss.getZ(),
            10
         );
         level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.TRIPWIRE_ATTACH, SoundSource.HOSTILE, 0.8F, 1.2F);
      }
      if (held > 0) {
         announce(level, SAY + "\"\u00a7fEverything here is on a string." + "\"");
      }
   }

   /** Reels the tied mobs in, keeps their strings drawn, and lets them go on time. */
   private static void tickMobThreads(MinecraftServer server) {
      if (MOB_THREADS.isEmpty()) {
         return;
      }
      for (Iterator<Map.Entry<UUID, MobTether>> it = MOB_THREADS.entrySet().iterator(); it.hasNext();) {
         Map.Entry<UUID, MobTether> entry = it.next();
         MobTether tether = entry.getValue();
         Entity raw = findEntity(server, entry.getKey());
         Entity rawBoss = findEntity(server, tether.bossId);
         if (!(raw instanceof Mob mob) || !mob.isAlive() || !(rawBoss instanceof Mob boss) || !boss.isAlive()) {
            it.remove();
            continue;
         }
         long now = ServerClock.clock(tether.world);
         // Through a portal, or he went through one: the string does not follow across worlds.
         if (now >= tether.expires || mob.level() != boss.level()) {
            it.remove();
            releaseThrownMob(tether, mob);
            tether.world.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.TRIPWIRE_DETACH, SoundSource.HOSTILE, 0.7F, 1.4F);
            continue;
         }

         // The string is drawn from his hand either way: while it is flown at somebody that
         // line IS the mechanic, and it is what makes the body read as his rather than as a
         // mob that happens to be moving fast. Every third tick; the beam lingers between cues.
         if (now % 3L == 0L) {
            drawString(
               tether.world,
               mob.getX(),
               mob.getY() + mob.getBbHeight() * 0.8,
               mob.getZ(),
               boss.getX(),
               boss.getY() + 2.0,
               boss.getZ(),
               8
            );
         }

         // In the air, on its way to somebody.
         if (tether.hurlUntil > now) {
            flyThrownMob(tether, mob, boss);
            continue;
         }
         if (tether.hurlUntil != 0L) {
            // The flight is over: the string takes the body back and it walks in again.
            releaseThrownMob(tether, mob);
            tether.hurlUntil = 0L;
            tether.nextHurl = now + MOB_HURL_COOLDOWN;
            tether.world.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.TRIPWIRE_CLICK_OFF, SoundSource.HOSTILE, 1.0F, 0.8F);
         }

         // Winding one up to throw. Visible, and cancellable by the simple act of not being
         // there when it lands - the throw is aimed once, at the windup, not steered.
         if (tether.windupUntil > 0L) {
            if (now >= tether.windupUntil) {
               ServerPlayer target = nearestTarget(tether.world, boss, null);
               tether.windupUntil = 0L;
               if (target != null) {
                  hurlMob(tether, mob, boss, target);
               } else {
                  releaseThrownMob(tether, mob);
               }
            } else {
               spinMobInWindup(tether, mob, boss, now);
            }
            continue;
         }

         // Not in the air and not winding: is it time to pick somebody?
         if (now >= tether.nextHurl && mob.distanceTo(boss) < 12.0) {
            ServerPlayer victim = nearestTarget(tether.world, boss, null);
            if (victim != null && mob.distanceTo(victim) < ARENA_RADIUS) {
               tether.windupUntil = now + MOB_HURL_WINDUP;
               // Read once, here, and never again during the spin: asking a silenced body
               // whether it is silenced would answer yes and silence it forever.
               tether.hadNoAi = mob.isNoAi();
               // The swing, as one timed cue: a funnel of thread round him for the whole windup.
               Fx.vortex(tether.world, ParticleTypes.END_ROD, boss.position(), 2.8, MOB_HURL_WINDUP, THREAD_VIOLET);
               tether.world.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.TRIPWIRE_ATTACH, SoundSource.HOSTILE, 1.1F, 1.3F);
               announce(tether.world, SAY + "\"\u00a7fLook up.\"");
               continue;
            }
         }

         Vec3 toward = boss.position().subtract(mob.position());
         Vec3 flat = new Vec3(toward.x, 0.0, toward.z);
         if (flat.lengthSqr() < 1.0E-4 || flat.length() < 3.0) {
            continue;
         }
         Vec3 pull = flat.normalize().scale(0.18);
         mob.setDeltaMovement(mob.getDeltaMovement().add(pull.x, 0.04, pull.z));
         mob.hurtMarked = true;
      }
   }

   /**
    * The windup: the tied body is swung around him.
    *
    * <p>This is the tell, and it is deliberately as readable as the player one - a body
    * orbiting the boss under a string is either about to be thrown at you or about to be
    * thrown at you, so the answer is always the same: do not stand where it is pointing.
    * The throw is aimed <b>once</b>, at the end of the windup, and never steered, which is
    * what keeps dodging it a real answer rather than a formality.
    */
   private static void spinMobInWindup(MobTether tether, Mob mob, Entity boss, long now) {
      double angle = (now % MOB_HURL_WINDUP) * 0.42;
      double radius = 2.6;
      double x = boss.getX() + Math.cos(angle) * radius;
      double z = boss.getZ() + Math.sin(angle) * radius;
      mob.setPos(x, boss.getY() + mob.getBbHeight() * 0.5, z);
      mob.setDeltaMovement(Vec3.ZERO);
      mob.hurtMarked = true;
      // Silenced while it spins: a zombie with working legs fights the swing, and a body
      // being used as a flail should not also be walking. The flag to restore was read when
      // the windup started - see above.
      mob.setNoAi(true);
      if (now % 4L == 0L) {
         Fx.vanilla(tether.world, ParticleTypes.END_ROD, x, mob.getY() + mob.getBbHeight() * 0.5, z, 2, 0.1, 0.1, 0.1, 0.0);
      }
      if (now % 10L == 0L) {
         tether.world.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.TRIPWIRE_CLICK_ON, SoundSource.HOSTILE, 0.9F, 0.7F + (now % MOB_HURL_WINDUP) * 0.02F);
      }
   }

   /**
    * Lets go: the body leaves his hand at whoever he picked.
    *
    * <p>The no-AI flag is set here and put back when the flight ends, because a mob whose
    * own brain is still running steers itself straight out of the throw - which is the
    * difference between a weapon and a mob that happened to be nearby.
    */
   private static void hurlMob(MobTether tether, Mob mob, Entity boss, ServerPlayer target) {
      Vec3 from = mob.position();
      Vec3 aim = target.position().add(0.0, target.getBbHeight() * 0.5, 0.0).subtract(from);
      if (aim.lengthSqr() < 1.0E-4) {
         return;
      }
      tether.hadNoAi = mob.isNoAi();
      mob.setNoAi(true);
      mob.setDeltaMovement(aim.normalize().scale(MOB_HURL_SPEED).add(0.0, 0.18, 0.0));
      mob.hurtMarked = true;
      tether.hurlUntil = ServerClock.clock(tether.world) + MOB_HURL_TICKS;
      // The throw is drawn as a comet along the line it was aimed, timed to the body's speed,
      // so the flight reads before it lands.
      Vec3 aimAt = target.position().add(0.0, target.getBbHeight() * 0.5, 0.0);
      int flight = (int)Math.max(4, Math.min(MOB_HURL_TICKS, from.distanceTo(aimAt) / MOB_HURL_SPEED));
      Fx.comet(tether.world, ParticleTypes.END_ROD, from.add(0.0, mob.getBbHeight() * 0.5, 0.0), aimAt, flight, MASK_PAINT);
      tether.world.playSound(null, from.x, from.y, from.z, SoundEvents.VEX_CHARGE, SoundSource.HOSTILE, 1.2F, 0.8F);
      announce(
         tether.world,
         SAY + "\"\u00a7fCatch.\""
      );
   }

   /**
    * One tick of flight: keeps it flying, and checks whether it landed on anybody.
    *
    * <p>The hit is a distance test rather than a projectile collision because this is not a
    * projectile - it is a zombie - and the damage is dealt as the body's own attack, so the
    * death message names the thing that hit you rather than the puppet holding the string.
    */
   private static void flyThrownMob(MobTether tether, Mob mob, Entity boss) {
      mob.setNoAi(true);
      Vec3 step = mob.getDeltaMovement();
      // Gravity, but gently: he is holding it up on the string the whole way, which is what
      // lets the arc be aimed at somebody standing on the ground.
      mob.setDeltaMovement(step.x, step.y - 0.035, step.z);
      mob.hurtMarked = true;
      Fx.vanilla(tether.world, ParticleTypes.END_ROD, mob.getX(), mob.getY() + mob.getBbHeight() * 0.5, mob.getZ(), 2, 0.15, 0.15, 0.15, 0.02);

      for (ServerPlayer p : tether.world.getPlayers(pl -> isRealTarget(pl) && !isPossessed(pl))) {
         if (p.distanceTo(mob) > MOB_HURL_HIT) {
            continue;
         }
         // The body lands on *them*. This used to hurt the thrown mob instead of the player it
         // hit, so a body flung across the arena did no damage to anybody but itself.
         p.hurtServer(tether.world, tether.world.damageSources().mobAttack(mob), MOB_HURL_DAMAGE);
         Vec3 away = p.position().subtract(mob.position());
         Vec3 flat = new Vec3(away.x, 0.0, away.z);
         if (flat.lengthSqr() > 1.0E-4) {
            Vec3 shove = flat.normalize().scale(MOB_HURL_KNOCKBACK);
            p.setDeltaMovement(p.getDeltaMovement().add(shove.x, 0.32, shove.z));
            p.hurtMarked = true;
         }
         Fx.clash(tether.world, ParticleTypes.CRIT, p.position().add(0.0, 1.0, 0.0), flat.lengthSqr() > 1.0E-4 ? flat.normalize() : Vec3.ZERO, MASK_PAINT);
         Fx.shockwave(tether.world, ParticleTypes.CLOUD, p.position(), 2.0, THREAD_VIOLET);
         tether.world.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.PLAYER_ATTACK_CRIT, SoundSource.HOSTILE, 1.0F, 0.7F);
         // One body, one landing: it does not keep hurting everybody it passes through.
         // -1 is "landed": never greater than the clock, so the tick above ends the flight.
         tether.hurlUntil = -1L;
         return;
      }
   }

   /** Puts the thrown body's own brain back, wherever the flight ended. */
   private static void releaseThrownMob(MobTether tether, Mob mob) {
      try {
         mob.setNoAi(tether.hadNoAi);
      } catch (Throwable ignored) {
      }
   }

   /**
    * A threaded player died while he is alive: their puppet stands up.
    *
    * <p>This is the mechanic the whole boss is built around, so it is generous on
    * purpose - the copy wears their face (the profile is announced, which is what
    * makes the skin render at all), carries a copy of their gear, and fights the
    * people still standing. Nothing is read out of the dead player's inventory
    * destructively; the copy is a photograph, not a theft.
    */
   public static void onPlayerDeath(MinecraftServer server, ServerPlayer player) {
      if (server == null || player == null) {
         return;
      }
      // A body he was wearing that then died is free the moment it is a corpse: there is
      // nothing left to drive, and the strings do not follow somebody through a respawn.
      Possession worn = POSSESSED.remove(player.getUUID());
      if (worn != null) {
         player.sendSystemMessage(
            Component.literal("\u00a75\u2726 \u00a7fThe strings come off with the body \u00a78- \u00a7ayou are free of him.")
         );
      }
      Fight fight = fightForPlayer(server, player);
      if (fight == null) {
         return;
      }
      Entity raw = findEntity(server, fight.bossId);
      if (!(raw instanceof Mob boss) || !boss.isAlive() || fight.dying) {
         return;
      }
      boolean threaded = THREADS.remove(player.getUUID()) != null;
      // Dead is dead, windup or not - a bar left behind would sit on the corpse's
      // screen for as long as the fight lasted.
      clearWindupBar(player.getUUID());
      if (!threaded) {
         return;
      }
      if (!(player.level() instanceof ServerLevel level)) {
         return;
      }
      if (PUPPETS.size() >= MAX_PUPPETS) {
         return;
      }

      String name = "\u00a75Puppet of \u00a7f" + player.getName().getString();
      ServerPlayer puppet = BossManager.spawnPuppetCopy(level, player, name, PUPPET_HEALTH, true);
      if (puppet == null) {
         return;
      }
      puppet.addTag(PUPPET_TAG);
      Role role = roleFor(player);
      // Read off the body it was copied from, before anything is replaced, so the trick it
      // keeps is genuinely what the player was carrying.
      Ability ability = puppetAbility(player);
      PUPPETS.put(
         puppet.getUUID(),
         new Puppet(puppet.getUUID(), player.getUUID(), fight.bossId, name, role, ability, ServerClock.clock(level) + PUPPET_TICKS)
      );
      fight.puppets.add(puppet.getUUID());
      fight.threaded.remove(player.getUUID());
      equipInheritance(puppet, ability);

      announce(
         level,
         "\u00a75\u00a7l\u2726 THE PUPPET AWAKENS \u00a7r\u00a75\u2726 \u00a7f" + name + " \u00a77stands back up, and it is not on your side."
      );
      String inherited = inheritLine(ability);
      if (!inherited.isEmpty()) {
         announce(level, "\u00a75\u2726 \u00a77It kept " + inherited);
      }
      level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.EVOKER_CAST_SPELL, SoundSource.HOSTILE, 1.3F, 0.6F);
      level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.VEX_CHARGE, SoundSource.HOSTILE, 1.2F, 0.7F);
      // The body is hauled back up: strings drop onto it, a ring of mask paint opens under it,
      // and the soul it is wearing is drawn up out of the floor.
      Fx.threads(level, ParticleTypes.SOUL, puppet.position(), 9.0, 40, THREAD_VIOLET);
      Fx.runeCircle(level, ParticleTypes.END_ROD, puppet.position().add(0.0, 0.05, 0.0), 1.8, 40, MASK_PAINT);
      Fx.spiral(level, ParticleTypes.SOUL, puppet.position(), 2.6, 30, THREAD_VIOLET);
      Fx.vanilla(level, ParticleTypes.SOUL, puppet.getX(), puppet.getY() + 1.0, puppet.getZ(), 20, 0.8, 1.0, 0.8, 0.08);
      for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
         if (!p.getUUID().equals(player.getUUID()) && fight.participants.contains(p.getUUID())) {
            p.sendSystemMessage(Component.literal("\u00a75\u2726 \u00a7f" + player.getName().getString() + " is on strings now."));
         }
      }
   }

   /**
    * Which single trick a kit reads as, stated as a pure decision.
    *
    * <p>Pure so the priority is pinned by the self-test rather than trusted: the answer has
    * to be exactly one of four, and a player carrying all three kits has to read as the
    * same one every time.
    */
   public static String abilityOf(boolean carriesBow, boolean carriesShield, boolean carriesTotem) {
      if (carriesBow) {
         return "arrows";
      }
      if (carriesShield) {
         return "shield";
      }
      if (carriesTotem) {
         return "totem";
      }
      return "none";
   }

   /**
    * What this player's body is actually carrying, and therefore what their puppet keeps.
    *
    * <p>The shield and the totem are searched for in the whole inventory, not just the
    * hands: a player who owns a totem keeps it in a hotbar slot, not held, and a puppet
    * that only inherited from the hands would almost never inherit the two things most
    * worth inheriting.
    */
   private static Ability puppetAbility(ServerPlayer player) {
      boolean bow = player.getMainHandItem().is(Items.BOW) || player.getMainHandItem().is(Items.CROSSBOW);
      boolean shield = player.getOffhandItem().is(Items.SHIELD);
      boolean totem = player.getOffhandItem().is(Items.TOTEM_OF_UNDYING);
      try {
         for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.is(Items.SHIELD)) {
               shield = true;
            } else if (stack.is(Items.TOTEM_OF_UNDYING)) {
               totem = true;
            }
         }
      } catch (Throwable ignored) {
      }
      return switch (abilityOf(bow, shield, totem)) {
         case "arrows" -> Ability.ARROWS;
         case "shield" -> Ability.SHIELD;
         case "totem" -> Ability.TOTEM;
         default -> Ability.NONE;
      };
   }

   /**
    * Puts the inherited item where the game's own rules will find it.
    *
    * <p>This is the whole implementation of two of the three abilities, and that is the
    * point: a shield blocks and a totem revives because a shield and a totem do. Handing
    * the fake body the item and letting vanilla's own pipeline handle it means a puppet
    * blocks with the same rules a player blocks with - the angle, the bypass tag, the
    * enchantment-driven reduction - instead of a damage number invented in this file.
    */
   private static void equipInheritance(ServerPlayer puppet, Ability ability) {
      try {
         switch (ability) {
            case SHIELD -> puppet.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
            case TOTEM -> puppet.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.TOTEM_OF_UNDYING));
            default -> {
            }
         }
      } catch (Throwable ignored) {
      }
   }

   /** How the inheritance reads in chat, so nobody has to guess what it kept. */
   private static String inheritLine(Ability ability) {
      return switch (ability) {
         case ARROWS -> "\u00a7fyour bow\u00a77.";
         case SHIELD -> "\u00a7fyour shield\u00a77.";
         case TOTEM -> "\u00a7fyour totem\u00a77 - it will get back up once.";
         case NONE -> "";
      };
   }

   /**
    * Reports the moment a puppet spends its inherited totem, and lets the damage through.
    *
    * <p>It deliberately does <b>not</b> perform the revive. Vanilla's own totem protection
    * runs inside the damage pipeline and brings the body back on one heart with the totem
    * animation, and an anticheat-style order of operations applies: a puppet that revives
    * the way a player does cannot behave differently from a player in any way anyone later
    * has to remember.
    *
    * @return always {@code true} - this is narration, not a damage cancellation
    */
   public static boolean onPuppetLethal(ServerPlayer victim, float amount) {
      Puppet state = victim == null ? null : PUPPETS.get(victim.getUUID());
      if (state == null || state.ability != Ability.TOTEM || state.totemSpent) {
         return true;
      }
      if (amount < victim.getHealth()) {
         return true;
      }
      state.totemSpent = true;
      if (victim.level() instanceof ServerLevel level) {
         announce(
            level,
            "\u00a75\u2726 \u00a77" + state.name + " \u00a7frushes back up with your totem in its grip \u00a78- \u00a7fone heart, and no more."
         );
         level.playSound(null, victim.getX(), victim.getY(), victim.getZ(), SoundEvents.TOTEM_USE, SoundSource.HOSTILE, 1.2F, 1.0F);
         Fx.flare(level, ParticleTypes.TOTEM_OF_UNDYING, victim.position().add(0.0, 1.0, 0.0), 1.8, 0xF2D94E);
         Fx.vanilla(level, ParticleTypes.TOTEM_OF_UNDYING, victim.getX(), victim.getY() + 1.0, victim.getZ(), 20, 0.6, 0.9, 0.6, 0.15);
      }
      return true;
   }

   /** The simplified behaviour this player's kit reads as. */
   private static Role roleFor(ServerPlayer player) {
      ItemStack hand = player.getMainHandItem();
      if (!hand.isEmpty() && (hand.is(Items.BOW) || hand.is(Items.CROSSBOW) || hand.is(Items.TRIDENT))) {
         return Role.RANGED;
      }
      double armour = 0.0;
      for (net.minecraft.world.entity.EquipmentSlot slot : new net.minecraft.world.entity.EquipmentSlot[]{
         net.minecraft.world.entity.EquipmentSlot.HEAD,
         net.minecraft.world.entity.EquipmentSlot.CHEST,
         net.minecraft.world.entity.EquipmentSlot.LEGS,
         net.minecraft.world.entity.EquipmentSlot.FEET
      }) {
         ItemStack piece = player.getItemBySlot(slot);
         if (!piece.isEmpty()) {
            armour += 3.0;
         }
      }
      if (armour >= 9.0 || player.getOffhandItem().is(Items.SHIELD)) {
         return Role.TANK;
      }
      return Role.MELEE;
   }

   /** Every puppet this fight has raised comes back at once, up to the cap. */
   private static void summonAllPuppets(ServerLevel level, Mob boss, Fight fight) {
      int raised = 0;
      for (UUID id : new ArrayList<>(fight.puppets)) {
         Puppet puppet = PUPPETS.get(id);
         if (puppet == null) {
            continue;
         }
         Entity entity = findEntity(level.getServer(), id);
         if (!(entity instanceof ServerPlayer body) || !body.isAlive()) {
            continue;
         }
         puppet.frozen = false;
         puppet.until = ServerClock.clock(level) + PUPPET_TICKS;
         puppet.nextAttack = 0L;
         raised++;
         // They are pulled back in on their strings, from wherever they stopped.
         stringPullTo(level, body, boss);
      }
      if (raised > 0) {
         announce(level, SAY + "\"\u00a7fFrom the top. \u00a7dAll of you.\"");
         level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.EVOKER_PREPARE_SUMMON, SoundSource.HOSTILE, 1.3F, 0.5F);
      }
   }

   /**
    * Puppet Swap: he and one of his puppets trade places.
    *
    * <p>The point of the move is the moment of doubt it buys - you line a swing up
    * on the mask and the mask is suddenly somewhere else. It is deliberately
    * generous about which puppet: the nearest one the fight still owns.
    */
   private static boolean puppetSwap(ServerLevel level, Mob boss, Fight fight) {
      ServerPlayer candidate = null;
      double best = Double.MAX_VALUE;
      for (Map.Entry<UUID, Puppet> entry : new LinkedHashMap<>(PUPPETS).entrySet()) {
         Puppet puppet = entry.getValue();
         if (puppet.frozen) {
            continue;
         }
         Entity entity = findEntity(level.getServer(), entry.getKey());
         if (!(entity instanceof ServerPlayer body) || !body.isAlive() || body.level() != level) {
            continue;
         }
         double distance = body.distanceToSqr(boss);
         if (distance < best) {
            best = distance;
            candidate = body;
         }
      }
      if (candidate == null) {
         return false;
      }
      Vec3 mine = boss.position();
      Vec3 theirs = candidate.position();
      // Two mirrors tear open where they stand, and the strings run between them as they trade.
      Vec3 across = theirs.subtract(mine);
      Vec3 axis = new Vec3(-across.z, 0.0, across.x);
      if (axis.lengthSqr() < 1.0E-4) {
         axis = new Vec3(1.0, 0.0, 0.0);
      }
      Fx.tear(level, ParticleTypes.PORTAL, mine.add(0.0, 1.0, 0.0), axis.normalize(), 1.6, 16, THREAD_VIOLET);
      Fx.tear(level, ParticleTypes.REVERSE_PORTAL, theirs.add(0.0, 1.0, 0.0), axis.normalize(), 1.6, 16, MASK_PAINT);
      Fx.soulStream(level, ParticleTypes.SOUL, mine.add(0.0, 1.2, 0.0), theirs.add(0.0, 1.2, 0.0), 0.8, 16, THREAD_VIOLET);
      boss.teleportTo(theirs.x, theirs.y, theirs.z);
      boss.setDeltaMovement(Vec3.ZERO);
      candidate.teleportTo(mine.x, mine.y, mine.z);
      candidate.setDeltaMovement(Vec3.ZERO);
      candidate.hurtMarked = true;
      boss.hurtMarked = true;
      level.playSound(null, mine.x, mine.y, mine.z, SoundEvents.ILLUSIONER_MIRROR_MOVE, SoundSource.HOSTILE, 1.2F, 1.0F);
      level.playSound(null, theirs.x, theirs.y, theirs.z, SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE, 1.0F, 1.4F);
      announce(level, SAY + "\"\u00a7fWrong one.\"");
      // Once per fight at phase three, then it becomes an ordinary cooldown move.
      fight.swapped = true;
      return true;
   }

   private static void stringPullTo(ServerLevel level, ServerPlayer body, Mob boss) {
      body.teleportTo(boss.getX() + (RANDOM.nextDouble() - 0.5) * 4.0, boss.getY(), boss.getZ() + (RANDOM.nextDouble() - 0.5) * 4.0);
      body.setDeltaMovement(Vec3.ZERO);
      body.hurtMarked = true;
      drawString(level, body.getX(), body.getY() + 1.2, body.getZ(), boss.getX(), boss.getY() + 1.4, boss.getZ(), 14);
   }

   private static void tickPuppets(MinecraftServer server) {
      if (PUPPETS.isEmpty()) {
         return;
      }
      long now = ServerClock.clock(server.overworld());
      for (Iterator<Map.Entry<UUID, Puppet>> it = PUPPETS.entrySet().iterator(); it.hasNext();) {
         Map.Entry<UUID, Puppet> entry = it.next();
         Puppet state = entry.getValue();
         Entity raw = findEntity(server, entry.getKey());
         if (!(raw instanceof ServerPlayer puppet) || !puppet.isAlive()) {
            it.remove();
            if (raw != null) {
               BossManager.removeFakePlayer(server, raw);
            }
            continue;
         }
         if (!(puppet.level() instanceof ServerLevel level)) {
            it.remove();
            BossManager.removeFakePlayer(server, puppet);
            continue;
         }
         final long tick = ServerClock.clock(level);
         now = tick;
         if (tick >= state.until) {
            it.remove();
            // Its strings are cut and it drops.
            Fx.ring(level, ParticleTypes.SOUL, puppet.position().add(0.0, 0.1, 0.0), 1.2, THREAD_VIOLET);
            level.playSound(null, puppet.getX(), puppet.getY(), puppet.getZ(), SoundEvents.TRIPWIRE_DETACH, SoundSource.HOSTILE, 0.9F, 1.2F);
            BossManager.removeFakePlayer(server, puppet);
            continue;
         }
         // The doll's own light, drawn here rather than inside its brain: a frozen puppet
         // and a puppet with nobody left to hit are still puppets, and the strings are the
         // only thing on the battlefield that says so.
         Safe.run("puppeteer puppet strings", () -> drawDollStrings(level, puppet, tick));
         Safe.run("puppeteer puppet", () -> drivePuppet(level, puppet, state, tick));
      }
   }

   /**
    * The whole of a puppet's brain: one target, three behaviours.
    *
    * <p>Deliberately not a copy of the player it came from - a real player's kit and
    * skill do not transfer to a body on strings. What it keeps is their face, their
    * gear and one idea: rush, shoot, or guard.
    */
   private static void drivePuppet(ServerLevel level, ServerPlayer puppet, Puppet state, long now) {
      if (state.frozen) {
         puppet.setDeltaMovement(0.0, puppet.getDeltaMovement().y, 0.0);
         puppet.hurtMarked = true;
         return;
      }
      Entity raw = findEntity(level.getServer(), state.bossId);
      Mob boss = raw instanceof Mob m && m.isAlive() ? m : null;

      ServerPlayer target = nearestPlayerTo(level, puppet, PUPPET_CHASE);
      if (target == null) {
         return;
      }
      face(puppet, target);

      double distance = puppet.distanceTo(target);
      // Your friend's shield, in your friend's hands: up while it is closing, down when it
      // swings. The game's own block handling does the rest - this only works the arm.
      boolean shieldUp = manageShield(puppet, state, distance);

      double distanceNow = distance;
      switch (state.role) {
         case MELEE -> {
            if (distanceNow > 2.6) {
               walkToward(puppet, target, 0.24);
            }
            if (distanceNow <= 3.4 && now >= state.nextAttack) {
               state.nextAttack = now + PUPPET_SWING_EVERY;
               lowerShield(puppet, state, shieldUp);
               swing(puppet, target);
            }
         }
         case RANGED -> {
            if (distanceNow < 8.0) {
               walkAway(puppet, target, 0.2);
            } else if (distanceNow > 14.0) {
               walkToward(puppet, target, 0.22);
            }
            if (distanceNow <= 20.0 && now >= state.nextShot) {
               state.nextShot = now + PUPPET_SHOT_EVERY;
               shoot(puppet, target);
            }
         }
         case TANK -> {
            // It guards him: it holds a ring around the boss and hits whatever comes
            // inside it, which is what makes the phase-three pile-up dangerous.
            if (boss != null) {
               double toBoss = puppet.distanceTo(boss);
               if (toBoss > 5.0) {
                  walkToward(puppet, boss, 0.22);
               } else if (distanceNow <= 3.6 && now >= state.nextAttack) {
                  state.nextAttack = now + PUPPET_SWING_EVERY;
                  lowerShield(puppet, state, shieldUp);
                  swing(puppet, target);
               }
            } else if (distanceNow <= 3.6 && now >= state.nextAttack) {
               state.nextAttack = now + PUPPET_SWING_EVERY;
               lowerShield(puppet, state, shieldUp);
               swing(puppet, target);
            }
         }
      }
   }

   /**
    * The inherited shield, kept up while the puppet closes and dropped when it swings.
    *
    * <p>This is the one ability that needs no damage code at all: raising the shield is
    * {@code startUsingItem} on the offhand, which is the same call a client makes when a
    * player right-clicks one. Every rule that follows - the facing cone, the bypass tag,
    * the enchantment-driven reduction - is then the game's, applied to the fake body
    * exactly as it would be to a real one.
    *
    * @return whether the shield is currently up, so the swing below knows to drop it
    */
   private static boolean manageShield(ServerPlayer puppet, Puppet state, double distance) {
      if (state.ability != Ability.SHIELD) {
         return false;
      }
      try {
         boolean up = puppet.isBlocking();
         boolean wantUp = distance > 2.6;
         if (wantUp && !up) {
            puppet.startUsingItem(InteractionHand.OFF_HAND);
            return true;
         }
         if (!wantUp && up) {
            puppet.stopUsingItem();
         }
         return up && wantUp;
      } catch (Throwable t) {
         return false;
      }
   }

   /** Drops the shield for the tick it swings, so a block is never also an attack. */
   private static void lowerShield(ServerPlayer puppet, Puppet state, boolean up) {
      if (!up || state.ability != Ability.SHIELD) {
         return;
      }
      try {
         puppet.stopUsingItem();
      } catch (Throwable ignored) {
      }
   }

   private static void walkToward(LivingEntity self, Entity target, double speed) {
      Vec3 direction = target.position().subtract(self.position());
      Vec3 flat = new Vec3(direction.x, 0.0, direction.z);
      if (flat.lengthSqr() < 1.0E-4) {
         return;
      }
      Vec3 step = flat.normalize().scale(speed);
      self.setDeltaMovement(step.x, self.getDeltaMovement().y, step.z);
      self.hurtMarked = true;
      if (self.horizontalCollision) {
         self.jumpFromGround();
         self.setDeltaMovement(self.getDeltaMovement().add(step.x, 0.42, step.z));
      }
   }

   private static void walkAway(LivingEntity self, Entity target, double speed) {
      Vec3 direction = self.position().subtract(target.position());
      Vec3 flat = new Vec3(direction.x, 0.0, direction.z);
      if (flat.lengthSqr() < 1.0E-4) {
         return;
      }
      Vec3 step = flat.normalize().scale(speed);
      self.setDeltaMovement(step.x, self.getDeltaMovement().y, step.z);
      self.hurtMarked = true;
   }

   private static void swing(ServerPlayer puppet, LivingEntity target) {
      try {
         puppet.attack(target);
         if (puppet.level() instanceof ServerLevel level) {
            level.playSound(null, puppet.getX(), puppet.getY(), puppet.getZ(), SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.HOSTILE, 0.6F, 0.9F);
         }
      } catch (Throwable ignored) {
      }
   }

   /**
    * The inherited bow, loosed with the numbers off the item it was copied from.
    *
    * <p>The arrow is built with the player's <b>actual</b> bow as its weapon, which is what
    * gives a Punch II bow its knockback and a Flame bow its fire - those are read from the
    * weapon item by vanilla's own arrow code, so they arrive for free and cannot disagree
    * with what the bow really does. What has to be stated here is only the base damage,
    * because a puppet has no draw time: Power is applied by hand at the same rate vanilla
    * applies it, and a crossbow shoots the flatter, harder bolt it is supposed to.
    */
   private static void shoot(ServerPlayer puppet, LivingEntity target) {
      try {
         if (!(puppet.level() instanceof ServerLevel level)) {
            return;
         }
         ItemStack weapon = puppet.getMainHandItem();
         boolean crossbow = weapon.is(Items.CROSSBOW);
         // Fall back to a bow when the copy's hands are empty, so a ranged puppet is never
         // silently disarmed by an inventory that changed after the copy was made.
         ItemStack model = weapon.is(Items.BOW) || crossbow ? weapon : new ItemStack(Items.BOW);
         Arrow arrow = new Arrow(level, puppet, new ItemStack(Items.ARROW), model);
         Vec3 from = puppet.getEyePosition();
         Vec3 to = target.getEyePosition();
         Vec3 dir = to.subtract(from);
         if (dir.lengthSqr() < 1.0E-4) {
            return;
         }
         int power = enchantLevel(level, model, Enchantments.POWER);
         float base = crossbow ? CROSSBOW_ARROW_DAMAGE : BOW_ARROW_DAMAGE;
         if (power > 0) {
            base += (power + 1) * 0.5F;
         }
         arrow.setPos(from.x, from.y - 0.1, from.z);
         arrow.setDeltaMovement(dir.normalize().scale(crossbow ? CROSSBOW_ARROW_SPEED : BOW_ARROW_SPEED));
         arrow.setBaseDamage(base);
         arrow.setCritArrow(false);
         arrow.pickup = AbstractArrow.Pickup.DISALLOWED;
         level.addFreshEntity(arrow);
         level.playSound(null, puppet.getX(), puppet.getY(), puppet.getZ(), SoundEvents.ARROW_SHOOT, SoundSource.HOSTILE, 0.8F, 1.1F);
      } catch (Throwable ignored) {
      }
   }

   /**
    * The level of one vanilla enchantment on a stack, or 0 when it is not on it.
    *
    * <p>Read through the level's enchantment registry rather than a table, so a datapack or
    * another mod's enchanted bow is read the same way the player's own would be.
    */
   private static int enchantLevel(ServerLevel level, ItemStack stack, net.minecraft.resources.ResourceKey<net.minecraft.world.item.enchantment.Enchantment> key) {
      if (stack.isEmpty()) {
         return 0;
      }
      try {
         var registry = level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT);
         return net.minecraft.world.item.enchantment.EnchantmentHelper.getItemEnchantmentLevel(registry.getOrThrow(key), stack);
      } catch (Throwable t) {
         return 0;
      }
   }

   private static void clearPuppets(MinecraftServer server, boolean poof) {
      for (UUID id : new ArrayList<>(PUPPETS.keySet())) {
         Entity entity = findEntity(server, id);
         if (entity != null) {
            if (poof && entity.level() instanceof ServerLevel level) {
               // removeFakePlayer puffs the body away for vanilla eyes; this is the strings going.
               Fx.ring(level, ParticleTypes.END_ROD, entity.position().add(0.0, 0.1, 0.0), 1.0, THREAD_VIOLET);
            }
            BossManager.removeFakePlayer(server, entity);
         }
      }
      PUPPETS.clear();
   }

   // -------------------------------------------------------------- abilities

   /** A lash of every string he owns, dragged across whoever is in front of him. */
   private static void stringLash(ServerLevel level, Mob boss, Fight fight, ServerPlayer target) {
      announce(level, SAY + "\"\u00a7fMind the wires.\"");
      Vec3 from = boss.position().add(0.0, 1.5, 0.0);
      Vec3 to = target.position().add(0.0, 1.0, 0.0);
      // The lash is a length of chain-heavy thread cracked across them.
      Fx.chains(level, ParticleTypes.END_ROD, from, to, THREAD_VIOLET);
      level.playSound(null, target.getX(), target.getY(), target.getZ(), SoundEvents.TRIPWIRE_CLICK_ON, SoundSource.HOSTILE, 1.4F, 0.6F);
      target.hurtServer(level, level.damageSources().mobAttack(boss), fight.phase >= 2 ? 7.0F : 5.0F);
      // The lash pulls as well as cuts - same impulse rule as a string: a shove
      // toward him, never a hold.
      Vec3 toward = boss.position().subtract(target.position());
      Vec3 flat = new Vec3(toward.x, 0.0, toward.z);
      if (flat.lengthSqr() > 1.0E-4) {
         Vec3 pull = flat.normalize().scale(0.45);
         target.setDeltaMovement(target.getDeltaMovement().add(pull.x, 0.12, pull.z));
         target.hurtMarked = true;
      }
      Fx.crescent(level, ParticleTypes.CRIT, target.position(), flat.lengthSqr() > 1.0E-4 ? flat.normalize().scale(-1.0) : Vec3.ZERO, 1.6, MASK_PAINT);
      // And a handful of strings thrown at everyone else, so the room stays busy. Real players
      // only - not his own puppets, not spectators, not a body he is already wearing.
      for (ServerPlayer other : level.getPlayers(p -> isRealTarget(p) && !isPossessed(p) && p != target && p.distanceToSqr(boss) < 900.0)) {
         drawString(level, from.x, from.y, from.z, other.getX(), other.getY() + 1.0, other.getZ(), 10);
         other.hurtServer(level, level.damageSources().mobAttack(boss), 2.5F);
      }
   }

   // ------------------------------------------- phase rotations: his newer moves

   /**
    * A volley of string-tipped arrows.
    *
    * <p>Every other way he ties a string to somebody needs him to be close enough to
    * reach them - so the one thing his kit could not do was reach across the arena, which
    * made distance a full answer to the first phase and a half-answer forever after. This
    * is that answer spent: a handful of arrows, one string each, loosed at whoever is
    * nearest and tying a string to whoever they land on.
    *
    * <p>The arrows are real arrows with a tag on them rather than a bespoke projectile, so
    * the flight, the collision, the shield facing and the damage are the game's - and the
    * string is tied from the landed hit by {@link #onStringArrowHit}, never from a guess
    * about where the shot was aimed.
    */
   private static void threadVolley(ServerLevel level, Mob boss) {
      List<ServerPlayer> cast = new ArrayList<>(
         level.getPlayers(pl -> isRealTarget(pl) && !isPossessed(pl) && pl.distanceToSqr(boss) < ARENA_RADIUS * ARENA_RADIUS)
      );
      if (cast.isEmpty()) {
         return;
      }
      // Nearest first: the volley is aimed at the people actually in the fight, not at
      // whoever happens to be standing furthest away in the arena.
      cast.sort((a, b) -> Double.compare(a.distanceToSqr(boss), b.distanceToSqr(boss)));
      Vec3 from = boss.getEyePosition();
      int shots = Math.min(THREAD_SHOT_COUNT, cast.size());
      for (int i = 0; i < shots; i++) {
         ServerPlayer p = cast.get(i);
         Vec3 aim = p.getEyePosition().subtract(from);
         if (aim.lengthSqr() < 1.0E-4) {
            continue;
         }
         Arrow arrow = new Arrow(level, boss, new ItemStack(Items.ARROW), new ItemStack(Items.BOW));
         arrow.setPos(from.x, from.y - 0.1, from.z);
         arrow.setDeltaMovement(aim.normalize().scale(VOLLEY_ARROW_SPEED));
         arrow.setBaseDamage(VOLLEY_ARROW_DAMAGE);
         arrow.setCritArrow(false);
         arrow.pickup = AbstractArrow.Pickup.DISALLOWED;
         arrow.addTag(THREAD_ARROW_TAG);
         level.addFreshEntity(arrow);
         drawString(level, from.x, from.y, from.z, p.getX(), p.getY() + 1.0, p.getZ(), 14);
      }
      Fx.muzzle(level, ParticleTypes.END_ROD, from, boss.getLookAngle(), THREAD_VIOLET);
      announce(level, SAY + "\"\u00a7fStrings. \u00a7dEverywhere.\"");
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.ARROW_SHOOT, SoundSource.HOSTILE, 1.2F, 0.7F);
   }

   /**
    * A string-tipped arrow landed on somebody: tie the string.
    *
    * <p>Called from the same damage hook that already watches every hit in the world, so
    * there is no second projectile system to keep in step. A volley <b>ties</b> a string;
    * it never stacks one, because the stacking rule is the fight's whole escalation and a
    * stray arrow is not allowed to skip straight past it.
    */
   public static void onStringArrowHit(DamageSource source, LivingEntity victim) {
      if (source == null || !(victim instanceof ServerPlayer player)) {
         return;
      }
      if (!(source.getDirectEntity() instanceof AbstractArrow arrow) || !arrow.entityTags().contains(THREAD_ARROW_TAG)) {
         return;
      }
      if (!(source.getEntity() instanceof Mob boss) || !isPuppeteer(boss)) {
         return;
      }
      Fight fight = FIGHTS.get(boss.getUUID());
      if (fight == null || fight.dying || !(boss.level() instanceof ServerLevel level)) {
         return;
      }
      threadPlayer(level, boss, fight, player);
   }

   /**
    * Every string he already holds, pulled at once.
    *
    * <p>The reel is what the count on a player's screen has been promising: alone, one
    * string is a nudge, but he does not pull one at a time forever - when the whole room is
    * threaded he takes them all in the same breath. It is still an impulse and never a
    * leash, and it only touches the people he is already holding, so a player who has cut
    * their strings is untouched by it. That is the point: it rewards the fight's own
    * counterplay with immunity to its loudest move.
    *
    * @return whether anything was reeled in, so a fight with nobody threaded can save it
    */
   private static boolean reelThreads(ServerLevel level, Mob boss, Fight fight) {
      long now = ServerClock.clock(level);
      int caught = 0;
      for (UUID id : new ArrayList<>(fight.threaded)) {
         Tether thread = THREADS.get(id);
         if (thread == null) {
            continue;
         }
         ServerPlayer p = level.getServer() == null ? null : level.getServer().getPlayerList().getPlayer(id);
         if (p == null || !p.isAlive() || p.level() != level) {
            continue;
         }
         Vec3 toward = boss.position().subtract(p.position());
         Vec3 flat = new Vec3(toward.x, 0.0, toward.z);
         if (flat.lengthSqr() < 1.0E-4) {
            continue;
         }
         double power = REEL_POWER * (1.0 + STACK_BONUS * Math.max(0, thread.stacks - 1));
         Vec3 pull = flat.normalize().scale(power);
         p.setDeltaMovement(p.getDeltaMovement().add(pull.x, 0.22, pull.z));
         p.hurtMarked = true;
         // A reel is also what tightens: the next ordinary pull on this string arrives on
         // the stacked cadence rather than waiting out the old one.
         thread.nextPull = Math.min(thread.nextPull, now + stackedPullTicks(thread.power, thread.stacks));
         p.sendOverlayMessage(Component.literal("\u00a75Every string pulls at once."));
         // Every string he holds drawn taut at once, as chains between his hands and them.
         Fx.chains(level, ParticleTypes.CRIT, boss.position().add(0.0, 1.8, 0.0), p.position().add(0.0, 1.0, 0.0), THREAD_VIOLET);
         level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.TRIPWIRE_CLICK_ON, SoundSource.HOSTILE, 1.0F, 0.6F);
         caught++;
      }
      if (caught == 0) {
         return false;
      }
      announce(level, SAY + "\"\u00a7fCloser.\"");
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.VEX_CHARGE, SoundSource.HOSTILE, 1.1F, 0.7F);
      return true;
   }

   /**
    * A snare laid just ahead of somebody.
    *
    * <p>Placed rather than aimed, and in front of the target rather than under them: a
    * trap is only a mechanic if it can be seen and walked around, so it lands where they
    * are about to be and sits there glowing until somebody steps in or it goes slack.
    */
   private static boolean laySnare(ServerLevel level, Mob boss, ServerPlayer target) {
      if (target == null) {
         return false;
      }
      double angle = Math.atan2(target.getZ() - boss.getZ(), target.getX() - boss.getX());
      double x = target.getX() + Math.cos(angle) * 2.5;
      double z = target.getZ() + Math.sin(angle) * 2.5;
      double y = target.getY();
      SNARES.add(new Snare(boss.getUUID(), level, x, y, z, ServerClock.clock(level) + SNARE_TICKS));
      announce(level, SAY + "\"\u00a7fCareful where you step.\"");
      level.playSound(null, x, y, z, SoundEvents.TRIPWIRE_ATTACH, SoundSource.HOSTILE, 0.9F, 1.4F);
      Fx.vanilla(level, ParticleTypes.END_ROD, x, y + 0.2, z, 18, 1.2, 0.2, 1.2, 0.02);
      return true;
   }

   /** Live snares: drawn while they wait, and sprung by whoever walks in. */
   private static void tickSnares(MinecraftServer server) {
      if (SNARES.isEmpty()) {
         return;
      }
      long now = ServerClock.clock(server.overworld());
      for (Iterator<Snare> it = SNARES.iterator(); it.hasNext();) {
         Snare snare = it.next();
         Entity raw = findEntity(server, snare.bossId);
         if (!(raw instanceof Mob boss) || !boss.isAlive()) {
            it.remove();
            continue;
         }
         if (now >= snare.expires) {
            it.remove();
            Fx.vanilla(snare.world, ParticleTypes.END_ROD, snare.x, snare.y + 0.2, snare.z, 12, 0.8, 0.2, 0.8, 0.02);
            continue;
         }
         // The knot, as a slowly turning ring - the tell that this patch of floor is his.
         long step = now / 3L;
         for (int i = 0; i < 8; i++) {
            double a = step * 0.25 + i * (Math.PI / 4.0);
            Fx.vanilla(snare.world, 
               ParticleTypes.END_ROD,
               snare.x + Math.cos(a) * 1.4,
               snare.y + 0.15,
               snare.z + Math.sin(a) * 1.4,
               1,
               0.0,
               0.0,
               0.0,
               0.0
            );
         }
         for (ServerPlayer p : snare.world.getPlayers(pl -> pl.isAlive() && !pl.isSpectator())) {
            if (p.distanceToSqr(snare.x, snare.y, snare.z) > SNARE_RADIUS * SNARE_RADIUS) {
               continue;
            }
            Fight fight = FIGHTS.get(snare.bossId);
            if (fight == null || fight.dying) {
               it.remove();
               break;
            }
            if (threadPlayer(snare.world, boss, fight, p)) {
               Fx.vanilla(snare.world, ParticleTypes.CRIT, p.getX(), p.getY() + 1.0, p.getZ(), 20, 0.5, 0.6, 0.5, 0.08);
               snare.world.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.TRIPWIRE_ATTACH, SoundSource.HOSTILE, 1.2F, 0.8F);
               // One knot, one catch: it is spent the moment it ties somebody.
               it.remove();
               break;
            }
         }
      }
   }

   /**
    * Take It Back: every string he is holding is reeled in and wound onto him.
    *
    * <p>The move that gives the thread count a second meaning. Up to now a string on you
    * was only a threat to you; this is the one ability that converts his own cast back into
    * health, which makes every string the room leaves on itself cost the fight twice. It
    * cuts the strings as it takes them, so the trade is honest in both directions: he heals
    * for what he held, and whoever was being held gets their body back for free.
    *
    * @return whether any string was recalled, so an empty-handed boss does not spend it
    */
   private static boolean rewindStrings(ServerLevel level, Mob boss, Fight fight) {
      int recalled = 0;
      for (UUID id : new ArrayList<>(fight.threaded)) {
         if (THREADS.remove(id) == null) {
            continue;
         }
         recalled++;
         clearWindupBar(id);
         ServerPlayer p = level.getServer() == null ? null : level.getServer().getPlayerList().getPlayer(id);
         if (p != null && p.isAlive()) {
            p.sendOverlayMessage(Component.literal("\u00a75The string is drawn back in."));
            Fx.vanilla(level, ParticleTypes.END_ROD, p.getX(), p.getY() + 1.2, p.getZ(), 16, 0.4, 0.6, 0.4, 0.05);
         }
      }
      if (recalled == 0) {
         return false;
      }
      boss.setHealth(Math.min(boss.getMaxHealth(), boss.getHealth() + recalled * REWIND_HEAL));
      announce(level, SAY + "\"\u00a7fCut them? \u00a7dI have more.\"");
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.EVOKER_CAST_SPELL, SoundSource.HOSTILE, 1.2F, 0.6F);
      stringBurst(level, boss, 40);
      return true;
   }

   /**
    * One string each for whoever has none: the phase turn's own announcement.
    *
    * <p>Wider rather than tighter, and capped at the phase's own thread count, so the room
    * sees the escalation without the stacking rule being skipped.
    */
   private static void threadEveryoneNew(ServerLevel level, Mob boss, Fight fight) {
      for (ServerPlayer p : level.getPlayers(
         pl -> pl.isAlive() && !pl.isSpectator() && pl.distanceToSqr(boss) < ARENA_RADIUS * ARENA_RADIUS
      )) {
         if (threadsHeld(fight) >= THREADS_PHASE_2) {
            return;
         }
         threadPlayer(level, boss, fight, p);
      }
   }

   // ------------------------------------------------------------ death & loot

   /**
    * Intercepts the killing blow so the death can be played out.
    *
    * <p>He does not explode into loot: his strings are cut one at a time, his
    * puppets stop where they stand, and only then does anything drop. Cancelling the
    * damage is what buys the ceremony its time.
    *
    * @return {@code null} when this is not our boss, otherwise {@code false} to
    *         cancel the lethal damage.
    */
   // --------------------------------------------------------------- possession

   /**
    * Whether this player's body is currently his.
    *
    * <p>Read from outside the fight by everything that has to stop working for a body
    * somebody else is wearing: their own blows, their own pickaxe, their own hands.
    */
   public static boolean isPossessed(ServerPlayer player) {
      return player != null && POSSESSED.containsKey(player.getUUID());
   }

   /** How many bodies he is wearing. A test hook, and the number staff want. */
   public static int possessedCount() {
      return POSSESSED.size();
   }

   /** The action-bar readout for a worn body: the state, and the one way out of it. */
   private static String possessionLine() {
      return "\u00a75\u00a7lWORN \u00a78| \u00a7fhis hands are driving - \u00a7dkill him to get yourself back";
   }

   /** The contest's length on a player it has already taken, which contracts each time. */
   private static int knotTicksFor(int landed) {
      return landed <= 0 ? KNOT_TICKS_1 : (landed == 1 ? KNOT_TICKS_2 : KNOT_TICKS_3);
   }

   /**
    * Who he reaches into, or null when nobody he is holding is close enough to reach.
    *
    * <p>The knot needs strings on the body already: going all the way in is the end of the
    * line that starts with one string and a pull, and skipping to it would make the whole
    * stacking rule decorative. The most-strung player is the one he has already gone to the
    * most trouble for.
    */
   private static ServerPlayer knotTarget(ServerLevel level, Mob boss, Fight fight) {
      ServerPlayer best = null;
      int bestStacks = 0;
      for (UUID id : new ArrayList<>(fight.threaded)) {
         Tether thread = THREADS.get(id);
         if (thread == null || thread.knotUntil > 0L || POSSESSED.containsKey(id)) {
            continue;
         }
         ServerPlayer player = level.getServer() == null ? null : level.getServer().getPlayerList().getPlayer(id);
         if (player == null || !player.isAlive() || player.level() != level || player.isSpectator()) {
            continue;
         }
         if (player.distanceTo(boss) > THREAD_SNAP_DISTANCE) {
            continue;
         }
         if (thread.stacks > bestStacks) {
            bestStacks = thread.stacks;
            best = player;
         }
      }
      return best;
   }

   /**
    * The Final Knot begins: his hands go past the strings and into the body.
    *
    * @return whether the contest is now running, so a call that found nothing to reach into
    *         leaves the move's clock alone and he reaches again next tick
    */
   private static boolean beginKnot(ServerLevel level, Mob boss, Fight fight, ServerPlayer target) {
      if (target == null) {
         return false;
      }
      Tether thread = THREADS.get(target.getUUID());
      if (thread == null || thread.knotUntil > 0L || POSSESSED.containsKey(target.getUUID())) {
         return false;
      }
      long now = ServerClock.clock(level);
      int ticks = knotTicksFor(KNOTS_LANDED.getOrDefault(target.getUUID(), 0));
      thread.knotUntil = now + ticks;
      thread.knotTotal = ticks;
      thread.knotsLeft = KNOT_BREAKS;
      thread.nextKnotCue = now;
      thread.nextKnotDrag = now;
      // The string would go slack on its own in the middle of the contest (see the field),
      // and a contest that cancels itself is a contest nobody won.
      thread.expires = Math.max(thread.expires, now + ticks + 60L);
      // His hold on the arms is spent by the attempt: it is the same two hands, and the
      // whole point of the move is that he stops playing with the arms and goes for the
      // driver. Anything else would be two full mechanics at once.
      thread.windupUntil = 0L;
      showKnotBar(target, ticks, ticks, KNOT_BREAKS);
      announce(level, SAY + "\"\u00a7fI'm done with your arms. \u00a7dI want the rest.\"");
      target.sendOverlayMessage(Component.literal(knotLine(KNOT_BREAKS)));
      target.sendSystemMessage(
         Component.literal(
            "\u00a75\u2726 \u00a7dHis hands go in \u00a78- \u00a7fthis one does not take an arm, \u00a7d it takes the driver."
         )
      );
      target.sendSystemMessage(
         Component.literal(
            "\u00a7fLand \u00a7d" + KNOT_BREAKS + " \u00a7fblows on him, or get \u00a7d" + (int)KNOT_ESCAPE
               + " \u00a7fblocks clear. \u00a7dA friend's blow counts, and so does a friend hitting you."
         )
      );
      target.sendSystemMessage(
         Component.literal("\u00a7cLose this and he keeps your body until he is dead.")
      );
      level.playSound(null, target.getX(), target.getY(), target.getZ(), SoundEvents.EVOKER_CAST_SPELL, SoundSource.HOSTILE, 1.4F, 0.6F);
      level.playSound(null, target.getX(), target.getY(), target.getZ(), SoundEvents.VEX_CHARGE, SoundSource.HOSTILE, 1.2F, 0.8F);
      Fx.vanilla(level, ParticleTypes.SOUL_FIRE_FLAME, target.getX(), target.getY() + 1.0, target.getZ(), 40, 0.6, 0.9, 0.6, 0.05);
      return true;
   }

   /** The action-bar line for the contest, which is the whole readout of it. */
   private static String knotLine(int hands) {
      return KNOT_BAR + " \u00a78| \u00a7f" + hands + " hand" + (hands == 1 ? "" : "s") + " still in you \u00a78| \u00a7fhit him";
   }

   /**
    * One tick of the contest: the walk, the cue, and the two ways it can be won.
    *
    * <p>The escapes are checked before the landing on the same tick for the same reason the
    * third string's are: a reaction that arrives on the last frame is a reaction.
    */
   private static void tickKnot(ServerLevel level, ServerPlayer player, Tether thread, long now, double distance) {
      Entity raw = findEntity(level.getServer(), thread.bossId);
      if (!(raw instanceof Mob boss) || !boss.isAlive()) {
         cancelKnot(level, player, thread, "his hands come out of you with the rest of him.");
         return;
      }
      // The Empty Mask is the one item in this fight built to shrug a hold off, and going
      // all the way in is still a hold. The decoy it makes is what his hands close on.
      if (PuppeteerGear.isEscaping(player)) {
         cancelKnot(level, player, thread, "\u00a7ahe closes his hands on the decoy, and there is nobody in it.");
         return;
      }
      if (distance > KNOT_ESCAPE) {
         cancelKnot(level, player, thread, "\u00a7aYou put the room between you and him - his hands are not that long.");
         return;
      }
      if (thread.knotsLeft <= 0) {
         cancelKnot(level, player, thread, "\u00a7aEvery hand pulled back out \u00a78- \u00a7fhe never got past the strings.");
         return;
      }
      if (now >= thread.knotUntil) {
         thread.knotUntil = 0L;
         clearWindupBar(player.getUUID());
         wearBody(level, boss, player, thread, now);
         return;
      }

      long left = thread.knotUntil - now;
      showKnotBar(player, left, thread.knotTotal, thread.knotsLeft);
      // The strings are already on the legs at this point: he is not asking them to stand
      // still, he is asking them to walk away from hands that are walking them in.
      if (now >= thread.nextKnotDrag) {
         thread.nextKnotDrag = now + KNOT_DRAG_EVERY;
         walkIntoHands(level, boss, player, thread);
      }
      if (now >= thread.nextKnotCue) {
         thread.nextKnotCue = now + KNOT_CUE_EVERY;
         player.sendOverlayMessage(
            Component.literal(
               KNOT_BAR + " \u00a78| \u00a7f" + thread.knotsLeft + " \u00a7fhand" + (thread.knotsLeft == 1 ? "" : "s")
                  + " \u00a78| \u00a7f" + (left / 20L + 1L) + "s \u00a78| \u00a7fhit him, or run"
            )
         );
         level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.TRIPWIRE_CLICK_ON, SoundSource.HOSTILE, 0.7F, 1.0F + (float)(1.0 - (double)left / Math.max(1, thread.knotTotal)) * 0.8F);
      }
      if (player.getY() < boss.getY() - 4.0) {
         // Held over a drop is not a way to lose the fight, it is a way to lose the player.
         player.teleportTo(boss.getX(), boss.getY(), boss.getZ());
      }
   }

   /**
    * His hands on the victim's legs during the contest: a step toward him, taken for them.
    *
    * <p>This is the part of the knot that makes it a contest rather than a timer. The
    * distance they need to reach is theirs to lose, and every step further in is one his
    * hands did not have to take.
    */
   private static void walkIntoHands(ServerLevel level, Mob boss, ServerPlayer player, Tether thread) {
      Vec3 toBoss = boss.position().subtract(player.position());
      Vec3 flat = new Vec3(toBoss.x, 0.0, toBoss.z);
      if (flat.lengthSqr() < 1.0E-4) {
         return;
      }
      Vec3 step = flat.normalize().scale(KNOT_DRAG_STEP * bodyPace(player));
      player.setDeltaMovement(player.getDeltaMovement().add(step.x, 0.0, step.z));
      player.hurtMarked = true;
      drawString(level, player.getX(), player.getY() + 1.2, player.getZ(), boss.getX(), boss.getY() + 1.4, boss.getZ(), 10);
      // Both hands, not one: what has the player by the legs is a pair of strings, and the
      // second strand is what makes the walk read as being walked. The collar is the strand
      // at the neck, which is the one they are actually losing the argument with.
      drawString(level, player.getX() + 0.35, player.getY() + 1.5, player.getZ(), boss.getX() - 0.4, boss.getY() + 2.1, boss.getZ(), 8);
      drawString(level, player.getX() - 0.35, player.getY() + 1.5, player.getZ(), boss.getX() + 0.4, boss.getY() + 2.1, boss.getZ(), 8);
      drawBodyStrings(level, player, ServerClock.clock(level));
      Fx.vanilla(level, ParticleTypes.SOUL, player.getX(), player.getY() + 0.3, player.getZ(), 8, 0.3, 0.2, 0.3, 0.02);
      Fx.vanilla(level, ParticleTypes.SOUL_FIRE_FLAME, player.getX(), player.getY() + 1.1, player.getZ(), 6, 0.3, 0.5, 0.3, 0.02);
      // He is not only walking the legs: he is swinging the arm they are attached to while
      // they try to leave, so the act of running away is itself paid for.
      LivingEntity victim = stringsTarget(level, player);
      if (victim != null && player.distanceTo(victim) <= 3.6 && ServerClock.clock(level) >= thread.nextForcedSwing) {
         thread.nextForcedSwing = ServerClock.clock(level) + swingEveryFor(player);
         forcedSwing(level, player, victim);
      }
   }

   /**
    * One of his hands comes back out: a blow landed on him, or a teammate shook one loose.
    *
    * <p>Counted in hands rather than in blows because the readout is what the player has to
    * act on, and "two hands still in you" is a sentence a group can shout at each other.
    */
   private static void breakKnot(ServerLevel level, ServerPlayer player, Tether thread, String how) {
      if (thread.knotUntil <= 0L) {
         return;
      }
      thread.knotsLeft--;
      if (thread.knotsLeft > 0) {
         player.sendOverlayMessage(Component.literal(knotLine(thread.knotsLeft)));
         player.sendSystemMessage(
            Component.literal("\u00a75\u2726 \u00a7f" + how + " \u00a78- \u00a7d" + thread.knotsLeft + " of his hands are still in you.")
         );
         Fx.vanilla(level, ParticleTypes.END_ROD, player.getX(), player.getY() + 1.2, player.getZ(), 20, 0.5, 0.6, 0.5, 0.08);
         level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.TRIPWIRE_DETACH, SoundSource.PLAYERS, 1.1F, 1.4F);
         return;
      }
      cancelKnot(level, player, thread, "\u00a7a" + how + " \u00a78- \u00a7fthe last hand comes out, and he does not have the body.");
   }

   /** The contest is over and he lost it. */
   private static void cancelKnot(ServerLevel level, ServerPlayer player, Tether thread, String why) {
      thread.knotUntil = 0L;
      thread.knotsLeft = 0;
      clearWindupBar(player.getUUID());
      player.sendOverlayMessage(Component.literal("\u00a75\u2726 THE FINAL KNOT \u00a78| \u00a7ayou kept your body"));
      player.sendSystemMessage(Component.literal("\u00a75\u2726 \u00a7fThe strings let go \u00a78- \u00a7f" + why));
      Fx.vanilla(level, ParticleTypes.END_ROD, player.getX(), player.getY() + 1.2, player.getZ(), 30, 0.6, 0.7, 0.6, 0.1);
      level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.TRIPWIRE_DETACH, SoundSource.HOSTILE, 1.2F, 0.8F);
   }

   /**
    * He wins the contest: the body is his, and so is a tenth of the fight.
    *
    * <p>The heal is a share of his <b>maximum</b>, taken off what the room has already done
    * to him, which is what makes losing the contest cost the group rather than the player: a
    * fight that was nearly over is twenty percent longer, and the group has to spend that
    * time fighting one of their own.
    */
   private static void wearBody(ServerLevel level, Mob boss, ServerPlayer player, Tether thread, long now) {
      thread.consumed = true;
      KNOTS_LANDED.merge(player.getUUID(), 1, Integer::sum);
      float heal = boss.getMaxHealth() * POSSESSION_HEAL_SHARE;
      boss.setHealth(Math.min(boss.getMaxHealth(), boss.getHealth() + heal));
      POSSESSED.put(player.getUUID(), new Possession(thread.bossId, level, player.getUUID(), now));
      clearWindupBar(player.getUUID());
      // The one line that has to be unmistakable, because it is the one that changes who is
      // playing: it names the body he is in, and it tells everyone still standing that the
      // person next to them is now the fight.
      announce(level, SAY + "\"\u00a7fSit still. \u00a7dI'll drive. \u00a7fSay hello to " + player.getName().getString() + ".\"");
      announce(level, "\u00a75\u00a7lTHE FINAL KNOT CLOSES \u00a78- \u00a7fhe is wearing " + player.getName().getString() + "\u00a7f.");
      player.sendOverlayMessage(Component.literal(possessionLine()));
      player.sendSystemMessage(
         Component.literal("\u00a75\u2726 \u00a7dYour body is his \u00a78- \u00a7fyou cannot move it, and you cannot use it, until he is dead.")
      );
      player.sendSystemMessage(Component.literal("\u00a7fHe is using it to kill the people next to you. \u00a7dThe only way out is his health bar."));
      level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.VEX_CHARGE, SoundSource.HOSTILE, 1.6F, 0.5F);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.WITHER_SPAWN, SoundSource.HOSTILE, 0.8F, 0.8F);
      Fx.vanilla(level, ParticleTypes.SOUL_FIRE_FLAME, player.getX(), player.getY() + 1.0, player.getZ(), 60, 0.7, 1.0, 0.7, 0.08);
      Fx.vanilla(level, ParticleTypes.SOUL, boss.getX(), boss.getY() + 1.5, boss.getZ(), 40, 0.8, 1.0, 0.8, 0.05);
   }

   /**
    * One tick of every body he is wearing.
    *
    * <p>Every way a possession can end is here, in the order the reasons matter: the boss
    * gone (the rule), the player gone (offline - the mark waits), the player dead (a corpse
    * cannot be driven), the long-stop (nobody thought of it), another dimension (it cannot be
    * driven from here), and finally the ordinary case.
    */
   private static void tickPossessed(MinecraftServer server) {
      if (POSSESSED.isEmpty()) {
         return;
      }
      for (Iterator<Map.Entry<UUID, Possession>> it = POSSESSED.entrySet().iterator(); it.hasNext();) {
         Map.Entry<UUID, Possession> entry = it.next();
         Possession state = entry.getValue();
         Entity raw = findEntity(server, state.bossId);
         long now = ServerClock.clock(state.world);
         if (!(raw instanceof Mob boss) || !boss.isAlive()) {
            // The mechanic's own rule: he keeps the body until he is dead, so this is the
            // tick the rule is satisfied - by his absence, which covers the death ceremony,
            // an operator's /kill, the arena emptying and a server that came back without
            // him. Nothing else has to remember to free anybody.
            freePossessed(server, state, "\u00a7ahe is gone, and nothing is holding your body but you.");
            it.remove();
            continue;
         }
         ServerPlayer player = server.getPlayerList().getPlayer(state.player);
         if (player == null) {
            // Disconnected. The mark waits for them rather than dropping the body loose in
            // the arena: they are still wearing it, they are simply not here to see it.
            continue;
         }
         if (!player.isAlive() || player.isDeadOrDying()) {
            freePossessed(server, state, "\u00a7ayour body gave out before he was done with it.");
            it.remove();
            continue;
         }
         if (now - state.started > POSSESSION_LONGSTOP) {
            freePossessed(server, state, "\u00a7aeven he has to let go eventually.");
            it.remove();
            continue;
         }
         if (player.level() != state.world) {
            freePossessed(server, state, "\u00a7ayou were pulled out of his reach.");
            it.remove();
            continue;
         }
         Safe.run("puppeteer possession", () -> drivePossessed(state.world, boss, player, state, now));
      }
   }

   /**
    * The whole of a worn body's behaviour: it is turned on whoever is still standing.
    *
    * <p>He does not need to be clever with it. The body has its own gear, its own enchants
    * and its own health, it fights the people it was standing with a moment ago, and it does
    * not stop until either it or they do. What it is <b>not</b> is the player: their velocity
    * is set rather than nudged, their own input cannot move them off the line his hands have
    * them on, and their blows are refused where they are made (see the attack gate), so the
    * only thing left in the fight that they control is whether they are still alive when he
    * dies.
    */
   private static void drivePossessed(ServerLevel level, Mob boss, ServerPlayer player, Possession state, long now) {
      if (now >= state.nextCue) {
         state.nextCue = now + 60L;
         player.sendOverlayMessage(Component.literal(possessionLine()));
      }
      // A worn body is the loudest thing on the field and has to look it: the hands above it,
      // the strings on its arms, and a halo that says the driver is somewhere else.
      if (now % 4L == 0L) {
         drawBodyStrings(level, player, now);
         drawString(level, player.getX(), player.getY() + 1.6, player.getZ(), boss.getX() - 0.5, boss.getY() + 2.2, boss.getZ(), 9);
         drawString(level, player.getX(), player.getY() + 1.6, player.getZ(), boss.getX() + 0.5, boss.getY() + 2.2, boss.getZ(), 9);
         Fx.vanilla(level, ParticleTypes.PORTAL, player.getX(), player.getY() + 1.2, player.getZ(), 6, 0.4, 0.6, 0.4, 0.05);
         Fx.vanilla(level, ParticleTypes.WITCH, player.getX(), player.getY() + 0.9, player.getZ(), 4, 0.35, 0.6, 0.35, 0.02);
      }
      ServerPlayer target = nearestFreePlayer(level, player, POSSESSION_RANGE);
      if (target == null) {
         // Nobody left to be turned on. He holds the body where it stands - it is not going
         // anywhere, and the leaving is handled by the fight itself (see onlyDollsLeft).
         state.hasPin = false;
         player.setDeltaMovement(0.0, player.getDeltaMovement().y, 0.0);
         player.hurtMarked = true;
         face(player, boss);
         return;
      }
      face(player, target);
      double distance = player.distanceTo(target);
      if (distance > 2.6) {
         Vec3 direction = target.position().subtract(player.position());
         Vec3 flat = new Vec3(direction.x, 0.0, direction.z);
         if (flat.lengthSqr() > 1.0E-4) {
            Vec3 step = flat.normalize().scale(MARIONETTE_STEP * 1.4 * bodyPace(player));
            // Written, not added: this is the one place in the fight where a player's own
            // movement keys have nothing to add, because the velocity they would add to is
            // replaced rather than pushed on.
            player.setDeltaMovement(step.x, player.getDeltaMovement().y, step.z);
            player.hurtMarked = true;
            double wantX = player.getX() + step.x;
            double wantZ = player.getZ() + step.z;
            if (state.hasPin) {
               double drift = Math.sqrt(
                  (player.getX() - state.pinX) * (player.getX() - state.pinX)
                     + (player.getZ() - state.pinZ) * (player.getZ() - state.pinZ)
               );
               if (drift > POSSESSION_PIN_SLACK) {
                  // The client is somewhere the strings did not put it. A worn body is put
                  // back on the line, which is also why a shove cannot peel one off him.
                  player.teleportTo(state.pinX, player.getY(), state.pinZ);
                  player.setDeltaMovement(step.x, player.getDeltaMovement().y, step.z);
                  player.hurtMarked = true;
               }
            }
            state.pinX = wantX;
            state.pinZ = wantZ;
            state.hasPin = true;
         }
      } else {
         state.hasPin = false;
      }
      if (distance <= 3.6 && now >= state.nextSwing) {
         state.nextSwing = now + swingEveryFor(player);
         forcedSwing(level, player, target);
      }
      // A body on his strings does not tire, and the person inside it does not get to
      // decide how much of its own life it spends.
      Fx.vanilla(level, ParticleTypes.SOUL, player.getX(), player.getY() + 1.6, player.getZ(), 1, 0.3, 0.3, 0.3, 0.01);
   }

   /** The nearest player he is not already wearing, which is who a worn body is aimed at. */
   private static ServerPlayer nearestFreePlayer(ServerLevel level, Entity self, double range) {
      ServerPlayer best = null;
      double bestDistance = range * range;
      for (ServerPlayer p : level.getPlayers(pl -> pl.isAlive() && !pl.isSpectator() && !POSSESSED.containsKey(pl.getUUID()))) {
         if (p == self) {
            continue;
         }
         double distance = p.distanceToSqr(self);
         if (distance < bestDistance) {
            bestDistance = distance;
            best = p;
         }
      }
      return best;
   }

   /** Lets a body go, once, with the reason said to the person who was inside it. */
   private static void freePossessed(MinecraftServer server, Possession state, String reason) {
      if (state == null) {
         return;
      }
      POSSESSED.remove(state.player);
      ServerPlayer player = server == null ? null : server.getPlayerList().getPlayer(state.player);
      if (player == null) {
         return;
      }
      player.sendOverlayMessage(Component.literal("\u00a75\u2726 WORN \u00a78| \u00a7fyour body is yours again"));
      player.sendSystemMessage(Component.literal("\u00a75\u2726 \u00a7fHe lets go \u00a78- \u00a7f" + reason));
      if (player.level() instanceof ServerLevel level) {
         Fx.vanilla(level, ParticleTypes.END_ROD, player.getX(), player.getY() + 1.2, player.getZ(), 40, 0.7, 0.9, 0.7, 0.1);
         level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.TRIPWIRE_DETACH, SoundSource.PLAYERS, 1.4F, 1.2F);
      }
   }

   /**
    * Frees every body this fight was wearing.
    *
    * <p>Called from {@link #release}, which is the single door every way a fight can end
    * goes through - the ceremony, the ordinary death hook, a boss found dead, the arena
    * emptying, an operator cleaning up, and a server stopping. Putting it here rather than
    * in the death path is what makes "until he is dead" true for ends that are not his
    * death, and what makes a soft-lock impossible by construction.
    */
   private static void freePossessions(MinecraftServer server, UUID bossId, String reason) {
      if (POSSESSED.isEmpty()) {
         return;
      }
      for (Possession state : new ArrayList<>(POSSESSED.values())) {
         if (state.bossId.equals(bossId)) {
            freePossessed(server, state, reason);
         }
      }
   }

   /**
    * True when the only people left in the fight are bodies he is already wearing.
    *
    * <p>The one end of this fight that is not about his health: a room with nobody in it to
    * fight is a room he leaves, taking his strings with him - and taking the strings off the
    * body he is wearing, because the rule that holds it is his presence. Without this, the
    * last player standing could be the one he is driving, and the fight would have nothing
    * left to do forever: the exact soft-lock this move has to not create.
    */
   private static boolean onlyDollsLeft(MinecraftServer server, Fight fight) {
      boolean wearing = false;
      for (Possession state : POSSESSED.values()) {
         if (state.bossId.equals(fight.bossId)) {
            wearing = true;
            break;
         }
      }
      if (!wearing) {
         return false;
      }
      for (UUID id : new ArrayList<>(fight.participants)) {
         if (POSSESSED.containsKey(id)) {
            continue;
         }
         ServerPlayer player = server == null ? null : server.getPlayerList().getPlayer(id);
         if (player != null && player.isAlive() && !player.isSpectator()) {
            return false;
         }
      }
      return true;
   }

   private static void showKnotBar(ServerPlayer player, long remaining, int total, int hands) {
      try {
         ServerBossEvent bar = WINDUP_BARS.get(player.getUUID());
         if (bar == null) {
            bar = new ServerBossEvent(UUID.randomUUID(), Component.literal(KNOT_BAR), BossBarColor.PURPLE, BossBarOverlay.PROGRESS);
            bar.setVisible(true);
            bar.addPlayer(player);
            WINDUP_BARS.put(player.getUUID(), bar);
         }
         bar.setName(Component.literal(KNOT_BAR + " \u00a78| \u00a7f" + hands + " hand" + (hands == 1 ? "" : "s") + " in you"));
         bar.setProgress(fraction(remaining, total));
      } catch (Throwable ignored) {
      }
   }

   public static Boolean onLethalDamage(Entity entity, float amount) {
      if (!(entity instanceof Mob boss) || !isPuppeteer(boss)) {
         return null;
      }
      if (amount < boss.getHealth()) {
         return null;
      }
      Fight fight = FIGHTS.get(boss.getUUID());
      if (fight == null) {
         // A tagged Puppeteer with no fight behind him - a body left by a crash, or one
         // whose fight was torn down. Cancelling here made him immortal, which is the
         // opposite of the bug it was written for: a boss nothing can kill is not a
         // protected boss, it is a stuck one.
         return null;
      }
      if (fight.dying) {
         return Boolean.FALSE;
      }
      fight.dying = true;
      fight.deathTicks = DEATH_TICKS;
      ServerLevel level = (ServerLevel)boss.level();
      int threads = cutAllThreads(level, fight);
      int strings = 0;
      for (UUID id : fight.puppets) {
         if (PUPPETS.containsKey(id)) {
            strings++;
         }
      }
      fight.stringsLeft = 1 + threads + strings;
      announce(level, SAY + "\"\u00a7fNo- \u00a7dI'm still holding-\"");
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.VEX_DEATH, SoundSource.HOSTILE, 1.4F, 0.7F);
      // The blow lands and the mask cracks: a flare, and every string he holds goes taut at once.
      Fx.flare(level, ParticleTypes.END_ROD, boss.position().add(0.0, 1.8, 0.0), 2.0, MASK_PAINT);
      Fx.starburst(level, ParticleTypes.END_ROD, boss.position().add(0.0, 1.6, 0.0), 5.0, THREAD_VIOLET);
      fight.strikes.clear();
      return Boolean.FALSE;
   }

   /**
    * The ceremony: every string he owns is cut, visibly and audibly, one at a time.
    *
    * <p>The puppets are told to stop on the first tick of this, because "then every
    * puppet it created stops moving" is the part of the death that has to be seen -
    * the fight is not over when the bar empties, it is over when the hands are empty.
    */
   private static void tickDeath(ServerLevel level, Mob boss, Fight fight) {
      // Held on his feet for the length of the ceremony. The state that ends this fight is
      // `dying`, so nothing is allowed to end it early: whatever the last blow was, a
      // second one, a fall, the void or an operator's cleanup can all still take his health
      // to zero, and a body at zero health is a body the server kills - which from inside
      // the arena is "the Puppeteer just dies", with no strings cut and nothing said. The
      // floor is what makes the four and a half seconds of string-cutting the only way out.
      if (boss.getHealth() <= 0.0F) {
         boss.setHealth(1.0F);
      }

      fight.deathTicks--;
      // Strings snapping, one at a time, all the way through.
      if (fight.deathTicks % 6 == 0) {
         fight.stringsLeft = Math.max(0, fight.stringsLeft - 1);
         level.playSound(null, boss.getX(), boss.getY() + 1.4, boss.getZ(), SoundEvents.TRIPWIRE_DETACH, SoundSource.HOSTILE, 1.1F, 0.9F + RANDOM.nextFloat() * 0.6F);
         double angle = RANDOM.nextDouble() * Math.PI * 2.0;
         double x = boss.getX() + Math.cos(angle) * 1.2;
         double z = boss.getZ() + Math.sin(angle) * 1.2;
         drawString(level, x, boss.getY() + 3.0, z, boss.getX(), boss.getY() + 1.2, boss.getZ(), 12);
         // Each cut is a snap of light where the thread parts.
         Fx.clash(level, ParticleTypes.CRIT, new Vec3(x, boss.getY() + 2.2, z), new Vec3(Math.cos(angle), 0.0, Math.sin(angle)), THREAD_VIOLET);
         for (ServerPlayer p : level.getPlayers(pl -> pl.isAlive() && pl.distanceToSqr(boss) < 2500.0)) {
            p.sendOverlayMessage(
               Component.literal("\u00a75a string is cut \u00a78| \u00a7f" + fight.stringsLeft + " left")
            );
         }
      }
      // The puppets stop where they stand, and then fall over one by one.
      for (UUID id : new ArrayList<>(fight.puppets)) {
         Puppet puppet = PUPPETS.get(id);
         if (puppet == null) {
            continue;
         }
         puppet.frozen = true;
      }
      if (fight.deathTicks == 45) {
         announce(level, "\u00a77Every puppet stops dead.");
         for (UUID id : new ArrayList<>(fight.puppets)) {
            Entity entity = findEntity(level.getServer(), id);
            if (entity != null) {
               Fx.vanilla(level, ParticleTypes.SMOKE, entity.getX(), entity.getY() + 1.0, entity.getZ(), 12, 0.4, 0.7, 0.4, 0.02);
               Fx.ring(level, ParticleTypes.END_ROD, entity.position().add(0.0, 0.1, 0.0), 1.0, THREAD_VIOLET);
            }
         }
      }
      if (fight.deathTicks > 0) {
         boss.setPos(boss.getX(), boss.getY() - 0.002, boss.getZ());
         return;
      }

      announce(level, "\u00a75\u00a7lTHE LAST STRING IS CUT.");
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), ModSounds.BOSS_DEATH, SoundSource.HOSTILE, 1.4F, 0.6F);
      // The mask comes apart in a flare, the body breaks into splinters, the soul goes up the
      // string it came down on, and the stage light goes out in a ring across the floor.
      Vec3 heart = boss.position().add(0.0, 1.4, 0.0);
      Fx.flare(level, ParticleTypes.END_ROD, heart.add(0.0, 0.4, 0.0), 2.8, LIMELIGHT);
      Fx.shatter(level, new net.minecraft.core.particles.BlockParticleOption(ParticleTypes.BLOCK, net.minecraft.world.level.block.Blocks.OAK_PLANKS.defaultBlockState()),
         heart, 2.0, 0x9A6A3C);
      Fx.spiral(level, ParticleTypes.SOUL, boss.position(), 12.0, 50, THREAD_VIOLET);
      Fx.shockwave(level, ParticleTypes.SOUL, boss.position(), 9.0, THREAD_VIOLET);
      Fx.vanilla(level, ParticleTypes.SOUL_FIRE_FLAME, boss.getX(), boss.getY() + 1.0, boss.getZ(), 40, 1.6, 1.2, 1.6, 0.12);

      // Paid, and marked as paid before the drops exist: the ordinary death hook fires off
      // the same removed body, and the safety net has to see this as already done.
      fight.paid = true;
      dropLoot(level, boss, fight);
      if (fight.bar != null) {
         fight.bar.removeAllPlayers();
         fight.bar.setVisible(false);
      }
      boss.discard();
      release(level.getServer(), fight);
   }

   /**
    * The safety net: pay a fight that ended through a route the ceremony never saw.
    *
    * <p>His death is a script - the killing blow is absorbed and four and a half seconds
    * of string-cutting play out - which is the only way the fight is *meant* to end. What
    * can still end it is everything else: an operator's cleanup, a command, the void, a
    * plugin, or a body that reached zero health by a route the damage hook never saw. The
    * server removes such a body without asking, and the fight tick then read the missing
    * boss as "the fight is over", tore the fight down and dropped nothing - which from
    * inside the arena is *the Puppeteer just dies*, with no strings cut, nothing said and
    * no loot. This is the second way out, and {@code paid} makes it a no-op when the
    * ceremony already ran.
    *
    * <p>Called from the ordinary entity-death hook (so a {@code /kill} still pays), and
    * from the fight tick when it finds the body already gone.
    */
   public static void onBossDeath(Mob boss) {
      if (boss == null) {
         return;
      }
      Fight fight = FIGHTS.get(boss.getUUID());
      if (fight == null || fight.paid) {
         return;
      }
      ServerLevel level = boss.level() instanceof ServerLevel sl ? sl : fight.world;
      if (level == null) {
         return;
      }
      fight.paid = true;

      if (!fight.dying) {
         // The ceremony never ran, so the two things it owes the room are said here in one
         // tick: what was holding them lets go, and the fight is over.
         cutAllThreads(level, fight);
         announce(level, SAY + "\"\u00a7f...slack.\"");
         announce(level, "\u00a75\u00a7lTHE LAST STRING IS CUT.");
         for (UUID id : new ArrayList<>(fight.puppets)) {
            Entity puppet = findEntity(level.getServer(), id);
            if (puppet != null) {
               Fx.vanilla(level, ParticleTypes.SMOKE, puppet.getX(), puppet.getY() + 1.0, puppet.getZ(), 12, 0.4, 0.7, 0.4, 0.02);
            }
         }
      }

      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), ModSounds.BOSS_DEATH, SoundSource.HOSTILE, 1.2F, 0.6F);
      Fx.vanilla(level, ParticleTypes.SOUL_FIRE_FLAME, boss.getX(), boss.getY() + 1.0, boss.getZ(), 50, 1.5, 1.2, 1.5, 0.12);
      dropLoot(level, boss, fight);
      release(level.getServer(), fight);
   }

   /**
    * His bar, moved by hand every tick.
    *
    * <p>He carries an ordinary evoker's entity, and vanilla only writes bar progress
    * for its own boss mobs (the dragon and the wither). Nothing else was moving this
    * bar, so it sat full from the summon to the string-cutting while the health behind
    * it drained - which is exactly what a health bar that "does not work" looks like
    * from inside the arena. The Clockwork King's comment records the same bug being
    * fixed for him; this is the other fight that had it.
    *
    * <p>The name carries the two things a bar cannot: which phase he is in, and how
    * many hands are still holding strings. A bar that only empties tells a group
    * nothing about whether the room is getting safer.
    */
   private static void syncBar(Fight fight, Mob boss) {
      if (fight.bar == null) {
         return;
      }
      // During the ceremony he is logically out of hit points, so the bar reads empty
      // rather than showing the one point the body is being held at.
      float share = fight.dying || boss.getMaxHealth() <= 0.0F ? 0.0F : boss.getHealth() / boss.getMaxHealth();
      fight.bar.setProgress(Math.max(0.0F, Math.min(1.0F, share)));
      int hands = fight.puppets.size();
      fight.bar.setName(
         Component.literal(
            BOSS_NAME
               + " \u00a78| \u00a7f"
               + (fight.phase == 1 ? "Phase I" : fight.phase == 2 ? "Phase II" : "Phase III")
               + " \u00a78| \u00a7d"
               + hands
               + " puppet"
               + (hands == 1 ? "" : "s")
         )
      );
   }

   private static void dropLoot(ServerLevel level, Mob boss, Fight fight) {
      drop(level, boss, new ItemStack(Items.STRING, 6 + RANDOM.nextInt(6)));
      drop(level, boss, new ItemStack(Items.EMERALD, 3 + RANDOM.nextInt(4)));
      // One rolling chance at one of his three legendaries, straight off the boss.
      if (RANDOM.nextFloat() < 0.2F) {
         drop(level, boss, switch (RANDOM.nextInt(3)) {
            case 0 -> ModItems.puppeteersMask();
            case 1 -> ModItems.marionetteStrings();
            default -> ModItems.emptyMask();
         });
      }
      BossPayout.payBoxes(
         level, fight.participants, ModItems::puppeteerLootBox, BossPayout.BOXES_PER_KILL, "\u00a75Puppeteer Loot Box"
      );
      for (UUID id : fight.participants) {
         ServerPlayer p = level.getServer() == null ? null : level.getServer().getPlayerList().getPlayer(id);
         if (p != null && p.isAlive()) {
            Advancements.grant(p, "kill_puppeteer");
         }
      }
   }

   private static void drop(ServerLevel level, Mob boss, ItemStack stack) {
      if (stack != null && !stack.isEmpty()) {
         level.addFreshEntity(new ItemEntity(level, boss.getX(), boss.getY() + 0.6, boss.getZ(), stack));
      }
   }

   private static void despawn(MinecraftServer server, ServerLevel level, Mob boss, Fight fight, String reason) {
      if (boss != null && boss.isAlive()) {
         stringBurst(level, boss, 40);
         level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.VEX_DEATH, SoundSource.HOSTILE, 1.0F, 0.6F);
         boss.discard();
      }
      announce(level, "\u00a75The Puppeteer \u00a7r-\u00a77 " + reason);
      release(server, fight);
   }

   /** Ends a fight: strings cut, puppets gone, bar gone. */
   private static void release(MinecraftServer server, Fight fight) {
      if (fight == null) {
         return;
      }
      // Bodies he was wearing are let go here rather than in the death path, because this
      // is the one door every end of a fight goes through - his death, an operator's /kill,
      // the arena emptying, a stray he left behind being swept, and the server stopping.
      // "Until he is dead" is therefore true for every end that is not his death too, and a
      // soft-lock stops being something anybody has to remember to prevent.
      freePossessions(server, fight.bossId, "\u00a7athe fight is over.");
      // The contracting tell resets between fights, like the third string's.
      for (UUID id : new ArrayList<>(fight.participants)) {
         KNOTS_LANDED.remove(id);
      }
      // His snares come off the floor with him: a knot left behind by a fight that has
      // ended is a trap nothing can ever spring.
      SNARES.removeIf(snare -> snare.bossId.equals(fight.bossId));
      // His mobs come off their strings with him, and anything mid-throw gets its own brain
      // back - a body left silenced by a fight that ended is a mob that stands still forever.
      for (Map.Entry<UUID, MobTether> entry : new ArrayList<>(MOB_THREADS.entrySet())) {
         if (!entry.getValue().bossId.equals(fight.bossId)) {
            continue;
         }
         Entity loose = findEntity(server, entry.getKey());
         if (loose instanceof Mob looseMob) {
            releaseThrownMob(entry.getValue(), looseMob);
         }
         MOB_THREADS.remove(entry.getKey());
      }
      ServerLevel level = null;
      if (server != null) {
         Entity boss = findEntity(server, fight.bossId);
         if (boss != null && boss.level() instanceof ServerLevel l) {
            level = l;
            cutAllThreads(l, fight);
         }
      }
      // The tell resets between fights: the contraction is an escalation inside one
      // engagement, not a permanent tax on a player who has fought him before.
      for (UUID id : new ArrayList<>(fight.participants)) {
         MARIONETTE_LANDED.remove(id);
      }
      if (level == null) {
         for (UUID id : new ArrayList<>(fight.threaded)) {
            THREADS.remove(id);
            clearWindupBar(id);
         }
      }
      // Its puppets go with it: a puppet that outlives its puppeteer is just a
      // stranger standing in the dark.
      for (UUID id : new ArrayList<>(fight.puppets)) {
         Puppet state = PUPPETS.remove(id);
         if (state == null) {
            continue;
         }
         Entity entity = findEntity(server, id);
         if (entity != null) {
            BossManager.removeFakePlayer(server, entity);
         }
      }
      if (fight.bar != null) {
         fight.bar.removeAllPlayers();
         fight.bar.setVisible(false);
      }
      FIGHTS.remove(fight.bossId);
   }

   /**
    * Called when a blow lands on him - a landed hit cuts that player's string.
    *
    * <p>And a blow on him does a second thing, which is the fight's only piece of
    * <b>shared</b> counterplay: it breaks the windup of whoever is winding up next to
    * the person who swung. The answer to the tell was always "hit him", but it read as
    * advice to the person being strung - and that person is often the one who cannot
    * turn around, because his hands are the problem. Making the same blow save a friend
    * means a group has something to do for each other rather than each handling their
    * own strings in isolation.
    */
   public static void onBossHit(Entity boss, ServerPlayer hitter) {
      if (!isPuppeteer(boss) || hitter == null) {
         return;
      }
      if (THREADS.containsKey(hitter.getUUID())) {
         Tether thread = THREADS.get(hitter.getUUID());
         // A blow on him pulls a hand back out of a contested body, which is the same
         // answer the third string has: hit him, and the thing he is doing stops.
         if (thread != null && thread.knotUntil > 0L && boss.level() instanceof ServerLevel knotLevel) {
            breakKnot(knotLevel, hitter, thread, "your own blow lands on him");
         }
         cutThread(hitter);
      }
      ServerPlayer saved = windingTeammate(hitter);
      if (saved == null) {
         return;
      }
      Tether thread = THREADS.get(saved.getUUID());
      if (thread == null || thread.windupUntil <= 0L) {
         return;
      }
      thread.windupUntil = 0L;
      clearWindupBar(saved.getUUID());
      helperMessage(hitter, saved);
      if (saved.level() instanceof ServerLevel level) {
         cancelWindup(level, saved, thread, "a teammate's blow on him broke the third string before it landed.");
      }
   }

   /**
    * The other half of shared counterplay: hitting the person being strung breaks it.
    *
    * <p>This is the Mindbinder's own "a friend shakes you free" hook, kept in the same
    * shape on purpose - a hold that a group can interrupt is a hold the group can talk
    * about, and a player being walked at their friend has a reason to shout for help
    * rather than a reason to log out. Only the windup breaks this way. Once the three
    * strings have landed, the answer is still the strings: hit him.
    *
    * @return whether the blow was spent breaking a windup (the damage itself is always
    *         allowed through - interrupting and surviving are not alternatives)
    */
   public static boolean onTeammateHit(ServerPlayer victim, Entity attacker) {
      if (!(attacker instanceof ServerPlayer helper) || helper == victim) {
         return false;
      }
      Tether thread = THREADS.get(victim.getUUID());
      if (thread == null) {
         return false;
      }
      // A friend's blow shakes a hand out of a body he is trying to take, which is the
      // same shared counterplay the third string has and it matters more here: losing this
      // one does not cost the victim their arms for thirty seconds, it costs them the
      // fight. The blow is not "wasted" - the damage still lands, and this only ever adds.
      if (thread.knotUntil > 0L && victim.level() instanceof ServerLevel knotLevel) {
         breakKnot(knotLevel, victim, thread, "a friend's blow lands on you and shakes one of his hands out");
         helperMessage(helper, victim);
         return true;
      }
      if (thread.windupUntil <= 0L) {
         return false;
      }
      thread.windupUntil = 0L;
      clearWindupBar(victim.getUUID());
      helperMessage(helper, victim);
      if (victim.level() instanceof ServerLevel level) {
         cancelWindup(level, victim, thread, "a teammate shook you free of it before it landed.");
      }
      return true;
   }

   /**
    * The winding-up player nearest whoever just took a swing at the boss.
    *
    * <p>Nearest to the <i>hitter</i>, not to the boss, because the fiction is a friend
    * standing beside you and the mechanic should reward being in the fight together
    * rather than converging on the boss's feet.
    */
   private static ServerPlayer windingTeammate(ServerPlayer hitter) {
      if (!(hitter.level() instanceof ServerLevel level)) {
         return null;
      }
      ServerPlayer best = null;
      double bestDistance = HELP_RADIUS * HELP_RADIUS;
      for (ServerPlayer other : level.getPlayers(
         pl -> pl.isAlive() && !pl.isSpectator() && !pl.getUUID().equals(hitter.getUUID())
      )) {
         Tether thread = THREADS.get(other.getUUID());
         if (thread == null || thread.windupUntil <= 0L) {
            continue;
         }
         double distance = other.distanceToSqr(hitter);
         if (distance < bestDistance) {
            bestDistance = distance;
            best = other;
         }
      }
      return best;
   }

   /** Tells both halves of a break what happened, because it takes two people. */
   private static void helperMessage(ServerPlayer helper, ServerPlayer saved) {
      helper.sendSystemMessage(
         Component.literal("\u00a7a\u2726 \u00a7fYour blow breaks the strings on \u00a7a" + saved.getName().getString() + "\u00a7f.")
      );
      saved.sendSystemMessage(
         Component.literal("\u00a7a\u2726 \u00a7f" + helper.getName().getString() + " \u00a7fbroke the strings closing on you.")
      );
   }

   /** How many times the full hold has landed on this player this fight. */
   private static int marionettesLanded(ServerPlayer player) {
      return player == null ? 0 : MARIONETTE_LANDED.getOrDefault(player.getUUID(), 0);
   }

   // --------------------------------------------------------------- particle art

   /** The line between two points - a string, drawn as dust and sparks. */
   private static void drawString(ServerLevel level, double x1, double y1, double z1, double x2, double y2, double z2, int count) {
      Vec3 from = new Vec3(x1, y1, z1);
      Vec3 to = new Vec3(x2, y2, z2);
      Vec3 dir = to.subtract(from);
      double length = dir.length();
      if (length < 0.001) {
         return;
      }
      Vec3 unit = dir.normalize();
      double stepSize = Math.max(0.25, length / Math.max(1, count));
      for (double d = 0.0; d < length; d += stepSize) {
         Vec3 point = from.add(unit.scale(d));
         Fx.vanilla(level, ParticleTypes.END_ROD, point.x, point.y, point.z, 1, 0.0, 0.0, 0.0, 0.0);
      }
   }

   private static void drawThread(ServerLevel level, Entity boss, ServerPlayer player) {
      drawString(
         level,
         player.getX(),
         player.getY() + 1.1,
         player.getZ(),
         boss.getX(),
         boss.getY() + 2.0,
         boss.getZ(),
         12
      );
   }

   /**
    * A body on his strings, drawn as a body on strings.
    *
    * <p>Every puppet and every worn player wears this, so the two read the same way: a strand
    * standing off each shoulder and a strand off each leg, all four running up and out, plus
    * the spark that runs down them. A doll nobody can see is a doll nobody remembers is his,
    * and a room that cannot tell the copy from the player it copied is a room that hesitates.
    */
   private static void drawBodyStrings(ServerLevel level, LivingEntity body, long tick) {
      for (int i = 0; i < 4; i++) {
         double angle = tick * 0.06 + i * (Math.PI / 2.0);
         double x = body.getX() + Math.cos(angle) * 0.45;
         double z = body.getZ() + Math.sin(angle) * 0.45;

         for (double up = 0.2; up < 2.3; up += 0.45) {
            Fx.vanilla(level, 
               new DustParticleOptions(-1770000, 0.9F), x, body.getY() + up, z, 1, 0.02, 0.02, 0.02, 0.0
            );
         }

         Fx.vanilla(level, ParticleTypes.END_ROD, x, body.getY() + 2.3, z, 1, 0.02, 0.02, 0.02, 0.0);
      }

      // The crown of strings over the head, and one ring of sparks around the body: the two
      // shapes that say "held" rather than "standing here".
      Fx.vanilla(level, ParticleTypes.ELECTRIC_SPARK, body.getX(), body.getY() + 2.35, body.getZ(), 3, 0.25, 0.05, 0.25, 0.01);

      if (tick % 5L == 0L) {
         Fx.vanilla(level, ParticleTypes.SOUL_FIRE_FLAME, body.getX(), body.getY() + 1.2, body.getZ(), 2, 0.3, 0.5, 0.3, 0.01);
      }
   }

   /** The strings on a puppet: a shorter body line, plus the spark of a body being worked. */
   private static void drawDollStrings(ServerLevel level, LivingEntity puppet, long tick) {
      if (tick % 3L != 0L) {
         return;
      }
      drawBodyStrings(level, puppet, tick);
      Fx.vanilla(level, ParticleTypes.WITCH, puppet.getX(), puppet.getY() + 1.0, puppet.getZ(), 2, 0.3, 0.5, 0.3, 0.01);
   }

   /** The strings always hanging off his hands, so he reads as a marionette. */
   private static void drawIdleStrings(ServerLevel level, Mob boss) {
      long tick = ServerClock.clock(level);
      for (int hand = 0; hand < 2; hand++) {
         double sway = Math.sin((tick + hand * 20) * 0.12) * 1.1;
         double x = boss.getX() + (hand == 0 ? -0.45 : 0.45);
         double z = boss.getZ() + sway * 0.4;
         for (double up = 0.6; up < 4.2; up += 0.5) {
            Fx.vanilla(level, 
               new DustParticleOptions(-1770000, 0.9F),
               x + sway * 0.1 * (up / 4.0),
               boss.getY() + up,
               z,
               1,
               0.02,
               0.02,
               0.02,
               0.0
            );
         }
         Fx.vanilla(level, ParticleTypes.END_ROD, x, boss.getY() + 4.2, z, 1, 0.05, 0.05, 0.05, 0.0);
      }
   }

   private static void stringBurst(ServerLevel level, Mob boss, int count) {
      Fx.vanilla(level, ParticleTypes.REVERSE_PORTAL, boss.getX(), boss.getY() + 1.4, boss.getZ(), count, 1.2, 1.4, 1.2, 0.05);
      Fx.vanilla(level, ParticleTypes.END_ROD, boss.getX(), boss.getY() + 1.4, boss.getZ(), Math.max(4, count / 4), 1.0, 1.2, 1.0, 0.02);
      Fx.vanilla(level, ParticleTypes.SOUL, boss.getX(), boss.getY() + 1.0, boss.getZ(), Math.max(4, count / 5), 1.0, 1.0, 1.0, 0.04);
   }

   // ------------------------------------------------------------------ helpers

   /** The fight this player belongs to, if any. */
   /**
    * Test hook: puts a given number of strings on this player right now.
    *
    * <p>Summons him if no fight is running, drops him in instead of lowering him on his
    * own strings (a fifty-tick descent is not what a debug command is for), and then
    * either ties the strings directly or - for the full count - starts the <b>real</b>
    * windup, so one command reaches the tell and the hold rather than bypassing the tell
    * to reach the hold.
    *
    * @return {@code null} on success, otherwise why it did nothing
    */
   public static String testStrings(ServerPlayer player, int strings) {
      if (player == null) {
         return "This command must be run by a player.";
      }
      int wanted = Math.max(1, Math.min(MARIONETTE_STACKS, strings));
      MinecraftServer server = player.level().getServer();
      Fight fight = fightForPlayer(server, player);
      if (fight == null) {
         String err = summon(player);
         if (err != null) {
            return err;
         }
         fight = fightForPlayer(server, player);
      }
      if (fight == null) {
         return "The strings go slack.";
      }
      Entity raw = findEntity(server, fight.bossId);
      if (!(raw instanceof Mob boss) || !(player.level() instanceof ServerLevel level)) {
         return "The strings go slack.";
      }
      // Landed and standing next to the caller: within reach of the hold, and not
      // descending for the next two and a half seconds.
      fight.riseTicks = 0;
      boss.setPos(player.getX() + 1.5, player.getY(), player.getZ() + 1.5);
      boss.setDeltaMovement(Vec3.ZERO);
      long now = ServerClock.clock(level);
      Tether existing = THREADS.get(player.getUUID());
      if (existing == null) {
         Tether fresh = new Tether(fight.bossId, level, player.getUUID(), 3, wanted, now + THREAD_TICKS_3, now + 4L);
         THREADS.put(player.getUUID(), fresh);
         fight.threaded.add(player.getUUID());
         existing = fresh;
      }
      if (wanted >= MARIONETTE_STACKS) {
         // Two tied, third winding - the real path, so the tell is exercised.
         existing.stacks = MARIONETTE_STACKS - 1;
         existing.windupUntil = 0L;
         beginWindup(level, player, existing);
         return null;
      }
      existing.stacks = wanted;
      existing.windupUntil = 0L;
      clearWindupBar(player.getUUID());
      player.sendOverlayMessage(Component.literal(threadLine(wanted)));
      announce(level, SAY + "\"\u00a7fHold still.\"");
      level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.TRIPWIRE_ATTACH, SoundSource.HOSTILE, 1.1F, 0.7F);
      return null;
   }

   /**
    * How long the tell lasts for a player who has survived it this many times already.
    *
    * <p>Pure, so the contraction is pinned by the self-test rather than trusted: it has
    * to shrink every time, and it has to stop shrinking at a floor that can still be
    * reacted to.
    */
   public static int windupTicksFor(int survived) {
      return Math.max(MARIONETTE_WINDUP_MIN, MARIONETTE_WINDUP_TICKS - Math.max(0, survived) * MARIONETTE_WINDUP_STEP);
   }

   private static Fight fightForPlayer(MinecraftServer server, ServerPlayer player) {
      for (Fight fight : FIGHTS.values()) {
         if (fight.participants.contains(player.getUUID())) {
            return fight;
         }
      }
      return null;
   }

   private static void refreshParticipants(ServerLevel level, Mob boss, Fight fight) {
      for (ServerPlayer p : level.getPlayers(pl -> pl.isAlive() && !pl.isSpectator() && pl.distanceToSqr(boss) < ARENA_RADIUS * ARENA_RADIUS)) {
         fight.participants.add(p.getUUID());
         if (fight.bar != null) {
            fight.bar.addPlayer(p);
         }
      }
      fight.participants.removeIf(id -> {
         ServerPlayer p = level.getServer() == null ? null : level.getServer().getPlayerList().getPlayer(id);
         return p == null || !p.isAlive() || p.level() != level || p.distanceToSqr(boss) > (ARENA_RADIUS + 24.0) * (ARENA_RADIUS + 24.0);
      });
   }

   private static ServerPlayer nearestTarget(ServerLevel level, Mob boss, Fight fight) {
      ServerPlayer best = null;
      double bestDistance = Double.MAX_VALUE;
      for (ServerPlayer p : level.getPlayers(pl -> pl.isAlive() && !pl.isSpectator() && pl.distanceToSqr(boss) < ARENA_RADIUS * ARENA_RADIUS)) {
         double distance = p.distanceToSqr(boss);
         if (distance < bestDistance) {
            bestDistance = distance;
            best = p;
         }
      }
      return best;
   }

   private static ServerPlayer nearestPlayerTo(ServerLevel level, Entity self, double range) {
      ServerPlayer best = null;
      double bestDistance = range * range;
      for (ServerPlayer p : level.getPlayers(pl -> pl.isAlive() && !pl.isSpectator())) {
         double distance = p.distanceToSqr(self);
         if (distance < bestDistance) {
            bestDistance = distance;
            best = p;
         }
      }
      return best;
   }

   private static float faceYaw(Entity from, Entity to) {
      double dx = to.getX() - from.getX();
      double dz = to.getZ() - from.getZ();
      return (float)(Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
   }

   private static void face(LivingEntity self, Entity target) {
      self.setYRot(faceYaw(self, target));
      self.setYHeadRot(self.getYRot());
      double dy = target.getEyeY() - self.getEyeY();
      double flat = Math.sqrt(target.distanceToSqr(self.getX(), self.getY(), self.getZ()));
      if (flat > 0.001) {
         self.setXRot((float)(-Math.toDegrees(Math.atan2(dy, flat))));
      }
   }

   private static Entity findEntity(MinecraftServer server, UUID id) {
      if (server == null) {
         return null;
      }
      for (ServerLevel level : server.getAllLevels()) {
         Entity entity = level.getEntity(id);
         if (entity != null) {
            return entity;
         }
      }
      return null;
   }

   private static String roman(int value) {
      return switch (value) {
         case 1 -> "I";
         case 2 -> "II";
         default -> "III";
      };
   }

   private static void announce(ServerLevel level, String message) {
      if (level == null || level.getServer() == null) {
         return;
      }
      // One identical line per window: a boss on a timer can otherwise repeat the
      // same taunt every tick, and a taunt naming a player is never identical.
      Integer last = RECENT_LINES.get(message);
      if (last != null && scriptTick - last < LINE_DEDUPE_TICKS) {
         return;
      }
      // ...and one *spoken* line per window, whatever it says. A fight with him has a taunt
      // on the phase change, on the cast, on the knot, on the summon and on the mirror, and
      // the room cannot read five readouts at once. Only his own dialogue is gated: a bar, a
      // phase banner and a mechanic's explanation are the things a player acts on.
      boolean taunt = message.startsWith(SAY);
      if (taunt && scriptTick - lastTauntTick < TAUNT_GAP_TICKS) {
         return;
      }
      if (taunt) {
         lastTauntTick = scriptTick;
      }
      RECENT_LINES.put(message, scriptTick);
      if (RECENT_LINES.size() > 64) {
         RECENT_LINES.clear();
      }
      for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
         Chat.raw(p, message);
      }
   }

   /** Test hooks: the constants the self-test pins so they cannot drift silently. */
   public static int threadLevels() {
      return 3;
   }

   /** How many blows it takes to pull every hand of a possession back out. */
   public static int knotBreaks() {
      return KNOT_BREAKS;
   }

   /** How long the possession contest runs on a player it has landed on this often. */
   public static int knotTicks(int landed) {
      return knotTicksFor(landed);
   }

   /** How far clear of his hands breaks the knot. */
   public static double knotEscape() {
      return KNOT_ESCAPE;
   }

   /** How long he waits before reaching for a body this way again. */
   public static int knotCooldown() {
      return KNOT_COOLDOWN;
   }

   /** The share of his own maximum health that taking a body is worth to him. */
   public static float possessionHealShare() {
      return POSSESSION_HEAL_SHARE;
   }

   /** The longest a body may be worn before the strings give out. Not a mechanic - a valve. */
   public static long possessionLongstopTicks() {
      return POSSESSION_LONGSTOP;
   }

   /** How many strings one player may be holding, at this phase. */
   public static int stringsPerPlayer(int phase) {
      return phase <= 1 ? STRINGS_PHASE_1 : (phase == 2 ? STRINGS_PHASE_2 : STRINGS_PHASE_3);
   }

   /** The stack count that turns a pull into a hand. */
   public static int marionetteStacks() {
      return MARIONETTE_STACKS;
   }

   /** True when this many strings is no longer a pull - his hand is on the player. */
   public static boolean isMarionetteHold(int stacks) {
      return stacks >= MARIONETTE_STACKS;
   }

   /** How often the strings swing a marionetted player's arm, when the body cannot be read. */
   public static int marionetteSwingEvery() {
      return MARIONETTE_SWING_EVERY;
   }

   /**
    * The production cadence: the weapon's own attack cooldown, clamped. Public so the
    * self-test pins the rule the fight actually uses rather than a constant beside it.
    */
   public static int marionetteSwingEveryFor(double attackSpeed) {
      if (!(attackSpeed > 0.0) || !Double.isFinite(attackSpeed)) {
         return MARIONETTE_SWING_EVERY;
      }
      return Math.max(MARIONETTE_SWING_MIN, Math.min(MARIONETTE_SWING_MAX, (int)Math.round(20.0 / attackSpeed)));
   }

   /** The floor a forced swing's interval can be clamped to. */
   public static int marionetteSwingMin() {
      return MARIONETTE_SWING_MIN;
   }

   /** The ceiling a forced swing's interval can be clamped to. */
   public static int marionetteSwingMax() {
      return MARIONETTE_SWING_MAX;
   }

   /** How long the third string is visibly wound before it lands. */
   public static int marionetteWindupTicks() {
      return MARIONETTE_WINDUP_TICKS;
   }

   /** How far from his hands a player has to get to break the windup. */
   public static double marionetteWindupEscape() {
      return MARIONETTE_WINDUP_ESCAPE;
   }

   /** The floor the contracting tell stops at. */
   public static int marionetteWindupMin() {
      return MARIONETTE_WINDUP_MIN;
   }

   /** How close a teammate has to be to break a windup for somebody. */
   public static double helpRadius() {
      return HELP_RADIUS;
   }

   /** How long he winds a tied mob up before throwing it. */
   public static int mobHurlWindup() {
      return MOB_HURL_WINDUP;
   }

   /** How long a thrown body stays in the air. */
   public static int mobHurlTicks() {
      return MOB_HURL_TICKS;
   }

   /** How often he may throw one. */
   public static int mobHurlCooldown() {
      return MOB_HURL_COOLDOWN;
   }

   /** What a thrown body is worth when it lands on somebody. */
   public static float mobHurlDamage() {
      return MOB_HURL_DAMAGE;
   }

   /** The base damage of a puppet's inherited shot, before its bow's own enchantments. */
   public static float inheritedArrowDamage(boolean crossbow) {
      return crossbow ? CROSSBOW_ARROW_DAMAGE : BOW_ARROW_DAMAGE;
   }

   /** How fast that shot flies. A crossbow's is flatter, which is the whole of its read. */
   public static double inheritedArrowSpeed(boolean crossbow) {
      return crossbow ? CROSSBOW_ARROW_SPEED : BOW_ARROW_SPEED;
   }

   /** The pull each further string adds, on top of the one before it. */
   public static double stackedPullPower(int level, int stacks) {
      return threadPullPower(level) * (1.0 + STACK_BONUS * Math.max(0, stacks - 1));
   }

   /** The pull interval at this stack count: more strings, more often. */
   public static int stackedPullTicks(int level, int stacks) {
      return Math.max(4, (int)(threadPullTicks(level) / (1.0 + STACK_BONUS * Math.max(0, stacks - 1))));
   }

   public static double threadSnapDistance() {
      return THREAD_SNAP_DISTANCE;
   }

   public static double threadPullPower(int level) {
      return level <= 1 ? PULL_POWER_1 : (level == 2 ? PULL_POWER_2 : PULL_POWER_3);
   }

   /** Test hook: how long a string of this level lasts before it goes slack. */
   public static int threadTicks(int level) {
      return level <= 1 ? THREAD_TICKS_1 : (level == 2 ? THREAD_TICKS_2 : THREAD_TICKS_3);
   }

   public static int threadPullTicks(int level) {
      return level <= 1 ? PULL_EVERY_1 : (level == 2 ? PULL_EVERY_2 : PULL_EVERY_3);
   }

   public static double puppetHealth() {
      return PUPPET_HEALTH;
   }

   public static int maxPuppets() {
      return MAX_PUPPETS;
   }

   public static int deathTicks() {
      return DEATH_TICKS;
   }

   public static double phaseTwoAt() {
      return PHASE_TWO_AT;
   }

   public static double phaseThreeAt() {
      return PHASE_THREE_AT;
   }

   /** True when a puppet of the given role is what this kit would raise. */
   public static String roleName(ServerPlayer player) {
      return switch (roleFor(player)) {
         case MELEE -> "melee";
         case RANGED -> "ranged";
         case TANK -> "tank";
      };
   }

   /** Arrows in one volley of strings. */
   public static int volleyShots() {
      return THREAD_SHOT_COUNT;
   }

   /** How often he may throw a volley. */
   public static int volleyCooldown() {
      return VOLLEY_COOLDOWN;
   }

   /** How often he may reel in every string at once. */
   public static int reelCooldown() {
      return REEL_COOLDOWN;
   }

   /** How hard the reel pulls, before the per-string bonus. */
   public static double reelPower() {
      return REEL_POWER;
   }

   /** How often he may lay a snare. */
   public static int snareCooldown() {
      return SNARE_COOLDOWN;
   }

   /** How long a snare waits to be sprung. */
   public static int snareTicks() {
      return SNARE_TICKS;
   }

   /** How close somebody has to step to be caught by a snare. */
   public static double snareRadius() {
      return SNARE_RADIUS;
   }

   /** How often he may take his strings back. */
   public static int rewindCooldown() {
      return REWIND_COOLDOWN;
   }

   /** What each recalled string heals him for. */
   public static float rewindHeal() {
      return REWIND_HEAL;
   }

   /** True when this entity is one of his string-tipped arrows. */
   public static boolean isStringArrow(Entity entity) {
      return entity != null && entity.entityTags().contains(THREAD_ARROW_TAG);
   }

   /** True when this damage came from the Puppeteer's own hands or strings. */
   public static boolean isPuppeteerDamage(DamageSource source, Entity boss) {
      return source != null && boss != null && source.getEntity() == boss;
   }

   /** Someone the Puppeteer can actually go after: alive and not spectating. */
   private static boolean isRealTarget(ServerPlayer p) {
      return p.isAlive() && !p.isSpectator();
   }

   /** A shape cue for modded clients (see Fx.shape). */
   private static void cue(ServerLevel level, int kind, ParticleOptions particle, Vec3 at, Vec3 aux, double a, double b, int color) {
      Fx.shape(level, kind, particle, at, aux, a, b, color);
   }

   /** A dotted line of vanilla particles from a to b, for vanilla clients only. */
   private static void vanillaLine(ServerLevel level, ParticleOptions particle, Vec3 from, Vec3 to, int points) {
      Fx.vanillaOnly(() -> {
         for (int i = 0; i <= points; i++) {
            Vec3 q = from.lerp(to, i / (double)Math.max(1, points));
            Fx.vanilla(level, particle, q.x, q.y, q.z, 1, 0.0, 0.0, 0.0, 0.0);
         }
      });
   }
}
