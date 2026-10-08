package com.fortuneandfavors.anticheat;

/**
 * The per-tick half of the movement model: what one tick of movement is allowed
 * to be, given what the body did on the tick before it.
 *
 * <p>{@link MovementPhysics} answers "how fast can this player be" as a
 * <i>level</i> - a steady-state ceiling for a surface, a speed attribute and a
 * jump. That is the right question for a window average, and it is structurally
 * blind to the thing a speed hack actually does, which is to <b>change speed</b>.
 * A body that gains more momentum in one tick than its own input could have given
 * it is not going too fast; it is being pushed by something that is not the
 * player, and no ceiling on the level can see that, because a client can gain
 * three block-seconds of momentum across six ticks and still finish the second
 * under any level you like.
 *
 * <p>So this is the other half: the same friction arithmetic, applied once per
 * tick, as an <i>inequality</i> rather than a threshold.
 *
 * <pre>
 *   step(t)  <=  step(t-1) * friction + input(friction, speed)
 * </pre>
 *
 * <p>Both terms come from the game rather than from a table in here -
 * {@code friction} is the friction of the block actually under the body and
 * {@code input} is vanilla's own {@code 0.216 / friction^3} applied to the
 * player's own movement-speed attribute - so a Speed potion, a modded speed
 * attribute and a modded slippery block all raise their own ceiling instead of
 * needing an exemption. What is left is a body that gained speed from nowhere.
 *
 * <p>The inequality only holds on the ground, and that restriction is the whole
 * reason this check is safe. In the air vanilla's horizontal handling is not this
 * arithmetic at all: the jump impulse and the flying-speed term make an honest
 * sprint-jump gain more horizontal momentum than the ground model allows, and a
 * check that judged it would flag every bunny-hopper in the game. So the caller
 * only ever feeds this a tick where the body was grounded on <b>both</b>
 * ticks - see {@link #evaluate} - and every airborne tick is nobody's business.
 *
 * <p>Nothing here decides anything. It returns a ratio and a plain-language
 * reason; whether a ratio is worth reporting is the caller's pattern, and whether
 * it is worth <i>acting</i> on is a decision this file deliberately has no vote
 * in.
 */
public final class MotionModel {
   // ------------------------------------------------------------ ground motion
   /**
    * How far over the per-tick ceiling a single tick may sit before it is worth
    * counting.
    *
    * <p>Deliberately not 1.0. The comparison is between numbers read from two
    * different ticks of a body whose packets can arrive a tick late, and a late
    * packet legitimately reorders a step against the surface it was taken on -
    * the block under the body when the server reads it may not be the block under
    * it when the client sent it. Fifteen percent absorbs that without absorbing a
    * hack: the cheapest speed hacks worth running are multiplicative (1.3x, 1.5x)
    * precisely because the server's own tolerance is so wide.
    */
   public static final double GAIN_MARGIN = 1.15;
   /**
    * Ticks over the ceiling, inside {@link #GAIN_WINDOW}, before it is a pattern.
    *
    * <p>This is what separates a gain hack from the honest things that look like
    * one for a tick: walking into a piston, being nudged by another entity, a
    * block placed into the body's own space, a slope the client resolved
    * differently. Every one of those is one tick. Eight is a player who has
    * gained momentum on eight separate ticks inside one second, which none of
    * them produce.
    */
   public static final int GAIN_TICKS = 8;
   /** The window the gain pattern is counted over, in ticks. */
   public static final int GAIN_WINDOW = 20;

   /** The most speed one tick of held input can add on this surface. */
   public static double inputCeiling(double slipperiness, double speedFactor) {
      return MovementPhysics.acceleration(speedFactor, MovementPhysics.friction(slipperiness));
   }

   /**
    * The fastest this body's next step could legally be.
    *
    * @param previousStep what the body actually moved last tick, in blocks. Taken
    *        from the body rather than from the model on purpose: this is a check
    *        about <i>change</i>, so the baseline has to be what really happened,
    *        not what should have.
    */
   public static double stepCeiling(double previousStep, double slipperiness, double speedFactor) {
      return stepCeiling(previousStep, slipperiness, speedFactor, 1.0);
   }

   /**
    * The same ceiling for a server whose ticks are longer than fifty milliseconds.
    *
    * <p>Every step in this file is measured once per server tick, and the input term is quoted
    * per client tick: {@code 0.216 / friction^3} is what one tick of held input is worth. At ten
    * ticks a second one server tick carries two of the client's, so a body holding W honestly puts
    * two ticks of input into one sample - and a ceiling that allows one says the honest runner
    * gained speed from nowhere, on exactly the servers that are already struggling. The ceiling is
    * therefore scaled by what a sample is worth, the same correction the window check makes; the
    * margin above it is unchanged, so a multiplicative hack is still over the line at any tick
    * rate. See {@link MovementPhysics#tickScale}.
    */
   public static double stepCeiling(
      double previousStep, double slipperiness, double speedFactor, double ticksPerSample
   ) {
      double scale = Math.max(1.0, Math.min(MovementPhysics.MAX_TICK_SCALE, ticksPerSample));
      if (!(previousStep >= 0.0) || !Double.isFinite(previousStep)) {
         return Double.MAX_VALUE;
      }
      double friction = MovementPhysics.friction(slipperiness);
      return (previousStep * friction + inputCeiling(slipperiness, speedFactor)) * scale;
   }

   /** How far over the ceiling this step is, as a multiple. 1.0 is legal. */
   public static double gainRatio(double observedStep, double ceiling) {
      if (!(ceiling > 1.0E-4) || !Double.isFinite(ceiling)) {
         return 1.0;
      }
      return observedStep / ceiling;
   }

   /**
    * The judgement for one tick, as both halves of what a caller needs.
    *
    * <p>{@code judged} is false whenever the tick cannot be judged at all, which
    * is the common case and the reason this check does not fire on honest play:
    * an airborne tick, a tick with no previous tick, a tick that lagged, a tick
    * below the margin. The caller's only job is to count {@code overCeiling}.
    */
   public record Tick(boolean judged, boolean overCeiling, double ratio, double ceiling, double observed) {
   }

   private static final Tick UNJUDGED = new Tick(false, false, 1.0, 0.0, 0.0);

   /**
    * Judges one tick of ground movement.
    *
    * @param groundedHere the body was on the ground on this tick
    * @param groundedBefore the body was on the ground on the previous tick, which
    *        is what makes the friction term the right one - see the class comment
    * @param previousStep the body's own last horizontal step, in blocks
    * @param observedStep this tick's horizontal step, in blocks
    */
   public static Tick evaluate(
      boolean groundedHere,
      boolean groundedBefore,
      double previousStep,
      double observedStep,
      double slipperiness,
      double speedFactor
   ) {
      return evaluate(groundedHere, groundedBefore, previousStep, observedStep, slipperiness, speedFactor, 1.0);
   }

   /**
    * The same judgement, with the tick-rate correction applied to the ceiling.
    *
    * <p>See {@link #stepCeiling(double, double, double, double)}: a sample that carried two of a
    * client's ticks may honestly contain two ticks of input, and reading it against a one-tick
    * ceiling is how an honest player on a struggling server was reported as gaining speed - and,
    * since this check's action is a clamp, corrected for it.
    */
   public static Tick evaluate(
      boolean groundedHere,
      boolean groundedBefore,
      double previousStep,
      double observedStep,
      double slipperiness,
      double speedFactor,
      double ticksPerSample
   ) {
      if (!groundedHere || !groundedBefore) {
         return UNJUDGED;
      }
      if (!(observedStep >= 0.0) || !Double.isFinite(observedStep)) {
         return UNJUDGED;
      }
      double ceiling = stepCeiling(previousStep, slipperiness, speedFactor, ticksPerSample);
      if (ceiling == Double.MAX_VALUE) {
         return UNJUDGED;
      }
      double ratio = gainRatio(observedStep, ceiling);
      return new Tick(true, ratio > GAIN_MARGIN, ratio, ceiling, observedStep);
   }

   // ----------------------------------------------------------- vertical motion
   /**
    * Vanilla's gravity per tick. A body that is not on the ground and is not
    * supported loses at least this much of its vertical velocity every tick, and
    * nothing may <i>add</i> to it except a jump.
    */
   public static final double GRAVITY = 0.08;
   /** Vanilla's air drag on the vertical component. */
   public static final double VERTICAL_DRAG = 0.98;
   /** A jump's impulse. */
   public static final double JUMP_IMPULSE = 0.42;
   /**
    * How far past the jump impulse a rise may go before nothing legal explains it.
    * A jump is 0.42 and the drag takes a hair off it, so anything meaningfully
    * above it is a second, larger impulse: a levitation, a launch, a hack. The
    * margin covers a client reporting in a different order than it moved.
    */
   public static final double RISE_MARGIN = 0.03;
   /**
    * A rise this small is a step, a slab, a stair - not a refusal to fall. Below
    * it the vertical check says nothing, because the world is full of things that
    * lift a body by a few centimetres and every one of them is honest.
    */
   public static final double RISE_FLOOR = 0.05;

   /**
    * True when the body rose this tick by more than any jump can explain.
    *
    * <p>Only the <i>rise</i> is judged, never the failure to fall, and that is the
    * deliberate half of this. "Not descending" is what a body standing on
    * something looks like, what a lily pad looks like, what a ladder looks like,
    * what every cheap way of holding a player up looks like - which is why the
    * hover checks need forty ticks of pattern to say anything at all. "Rising by
    * more than a jump" has exactly one honest cause (a jump) and one honest
    * modifier (a lower gravity attribute, a levitation, both of which the caller
    * excuses before it gets here).
    */
   public static boolean impossibleRise(double dy, boolean grounded, boolean jumped, boolean scripted) {
      return impossibleRise(dy, grounded, jumped, scripted, 1.0);
   }

   /**
    * The same question, for a server whose ticks are longer than fifty milliseconds.
    *
    * <p>{@code dy} is one server tick's change in altitude, and the impulse it is compared against
    * is one <i>client</i> tick's worth. At ten ticks a second a single sample can contain two
    * ticks of a jump - the impulse and then most of the next tick's rise on top of it - so a
    * player who sprint-jumps onto a lower block reports a rise above a jump's worth through no
    * fault of their own, and only while the server is behind. The bar moves with the sample, which
    * is the same correction every other measurement in this module makes: what a hack cannot do is
    * rise further than the ticks it was given allow, at any tick rate.
    */
   public static boolean impossibleRise(
      double dy, boolean grounded, boolean jumped, boolean scripted, double ticksPerSample
   ) {
      if (scripted || grounded || jumped) {
         return false;
      }
      double scale = Math.max(1.0, Math.min(MovementPhysics.MAX_TICK_SCALE, ticksPerSample));
      return dy > (JUMP_IMPULSE + RISE_MARGIN) * scale;
   }

   /**
    * The same question, for a body whose jump is not the stock one and whose last tick may already
    * have been carrying it upward.
    *
    * <p>Two honest things rise past a plain jump's impulse, and the four-argument form flagged both.
    * Jump Boost raises the impulse itself - by a tenth of a block per level, which is what the
    * Frog kit's Jump III and the slime boots hand out - so the second and third ticks of a boosted
    * jump are still above 0.45 while airborne. And a slime block or a bed throws a falling body back
    * up with most of the speed it landed with, so a bounce off a long fall rises a block a tick for
    * several ticks with no jump anywhere in it. Neither is an impulse the body was given mid-air:
    * both are momentum it already had, decaying under gravity exactly as vanilla says it should.
    *
    * <p>So the ceiling is the larger of the (boosted) jump and the ballistic continuation of the
    * previous tick's rise. A body coasting upward on momentum passes; a body that holds or gains
    * altitude speed - which is what every fly and high-jump module does - does not, because
    * gravity takes {@link #GRAVITY} a tick off any honest rise and the module has to put it back.
    */
   public static boolean impossibleRise(
      double dy,
      boolean grounded,
      boolean jumped,
      boolean scripted,
      double ticksPerSample,
      double extraImpulse,
      double previousRise
   ) {
      if (scripted || grounded || jumped) {
         return false;
      }
      double scale = Math.max(1.0, Math.min(MovementPhysics.MAX_TICK_SCALE, ticksPerSample));
      double jumpCeiling = (JUMP_IMPULSE + Math.max(0.0, extraImpulse) + RISE_MARGIN) * scale;
      double coastCeiling = previousRise > 0.0
         ? ((previousRise - GRAVITY) * VERTICAL_DRAG + RISE_MARGIN) * scale
         : 0.0;
      return dy > Math.max(jumpCeiling, coastCeiling);
   }

   /**
    * What a Jump Boost of this amplifier adds to a jump's impulse: a tenth of a block per level,
    * which is vanilla's own number. {@code -1} means no effect at all.
    */
   public static double jumpBoostImpulse(int amplifier) {
      return amplifier < 0 ? 0.0 : 0.1 * (amplifier + 1);
   }

   /**
    * How many ticks of descent the body should have accumulated by now, given the
    * flight it has had. Used as the expectation the observed altitude is compared
    * against, so the check is about the <i>shape</i> of the fall and not about any
    * one number.
    */
   public static double fallSpeed(int airborneTicks) {
      if (airborneTicks <= 0) {
         return 0.0;
      }
      double velocity = 0.0;
      for (int i = 0; i < airborneTicks; i++) {
         velocity = (velocity - GRAVITY) * VERTICAL_DRAG;
      }
      return velocity;
   }

   // ------------------------------------------------------------ item use
   /**
    * The movement multiplier vanilla uses for an item that declares no
    * {@code use_effects} of its own.
    *
    * <p>This is the game's number, not a guess: {@code UseEffects.DEFAULT} is
    * {@code (canSprint=false, speedMultiplier=0.2)}, so a bow, a shield, a potion,
    * a piece of food and a spyglass all scale the body holding them to a fifth of
    * its input - which is the entire cost of using them.
    */
   public static final double DEFAULT_USE_SPEED = 0.2;
   /**
    * How much of an item's own speed penalty has to exist before removing it is
    * worth reporting.
    *
    * <p>A no-slow module removes a <i>penalty</i>, so an item with almost no
    * penalty to remove is not judgeable: at a multiplier of 0.9 the honest body and
    * the module's body are a tenth of walk speed apart, which is inside the noise of
    * a single tick. A quarter is the smallest gap that can be called an advantage
    * rather than a rounding error.
    */
   public static final double MIN_USE_PENALTY = 0.25;
   /**
    * How much of the removed penalty a body may still be showing before it is a
    * module.
    *
    * <p>Expressed as a share of the gap between the item's honest figure and full
    * speed, rather than as a flat fraction of full speed, because those are the
    * same threshold only for the default 0.2 item. A body is judged against the
    * item it is actually holding: for a 0.2 bow the bar lands at 0.48 of full speed
    * (a hair under the old flat half, which is the allowance a client needs for the
    * tick or two of momentum it keeps when a bow is raised mid-sprint), and for an
    * item that declares a milder penalty the bar moves with it.
    */
   public static final double NO_SLOW_KEEP = 0.35;

   /**
    * Whether using this item costs the body anything worth protecting.
    *
    * <p>The authority is the item's own declared speed multiplier - the same number
    * the game's own client multiplies its movement input by - and never the use
    * animation it happens to carry.
    *
    * <p>That correction matters, and it is the fix for a real false positive: this
    * mod gives the Distant Memory sword {@code use_effects} at <i>full</i> speed, so
    * that blocking with it while running is deliberate design rather than a hack.
    * The check used to infer the penalty from the animation name, which is a
    * property of the pose and not of the price: a {@code BLOCK} item was assumed to
    * cost four fifths of the player's speed, so a legitimate full-speed block read
    * as a no-slow module for as long as the sword was raised.
    */
   public static boolean slowsWhileUsing(double speedMultiplier) {
      return Double.isFinite(speedMultiplier) && 1.0 - speedMultiplier >= MIN_USE_PENALTY;
   }

   /**
    * The speed a body may keep while using an item before it has removed the item's
    * cost, in blocks a tick.
    *
    * <p>Halfway between the two honest answers - the item's own figure and no
    * penalty at all - so that a body is only ever called a module for the speed the
    * item was supposed to take away. See {@link #NO_SLOW_KEEP}.
    */
   public static double noSlowThreshold(double speedMultiplier, double fullSpeed) {
      double multiplier = Math.max(0.0, Math.min(1.0, speedMultiplier));
      return fullSpeed * (multiplier + (1.0 - multiplier) * NO_SLOW_KEEP);
   }

   // ------------------------------------------------------------ hidden sprint
   /**
    * How far above the walk ceiling a body's pace has to sit before it is sprinting
    * whether or not the server was told so.
    *
    * <p>Fifteen percent, the same margin the per-tick gain model uses, and for the
    * same reason: the comparison is between a step and a model of that step, and the
    * block under the body when the server reads it may not be the block it was on
    * when the client sent the packet.
    */
   public static final double SPRINT_PACE_MARGIN = 1.15;

   /**
    * Whether this step is a sprint, whatever the server's own sprint flag says.
    *
    * <p>The flag is the whole basis of the anti-hunger check and it is exactly what
    * the module it targets hides: vanilla charges hunger from the server's sprint
    * state, so a client that keeps running at sprint speed while never telling the
    * server it is sprinting pays nothing to run - and the food arithmetic the check
    * was built on can never fire, because there is no sprint for it to measure.
    *
    * <p>So the pace is read from the body instead. A grounded step above the walk
    * ceiling and no faster than the sprint ceiling is a body running: it is above
    * what walking can produce and inside what sprinting produces, and the only
    * honest way to be there without the flag set is a surface that keeps momentum,
    * a vehicle or a push - all of which the caller excludes before asking.
    */
   public static boolean sprintPace(double observedStep, double walkStep, double sprintStep) {
      if (!(observedStep >= 0.0) || !Double.isFinite(observedStep)) {
         return false;
      }
      if (!(walkStep > 0.0) || !(sprintStep >= walkStep)) {
         return false;
      }
      return observedStep > walkStep * SPRINT_PACE_MARGIN
         && observedStep <= sprintStep * (1.0 + (SPRINT_PACE_MARGIN - 1.0));
   }

   // -------------------------------------------------------------- arrow dodge
   /**
    * How sharp a turn has to be before it is a dodge rather than steering.
    *
   * <p>Ninety degrees, measured between the two steps that share the tick. A player
    * crossing a projectile's line moves roughly along it; the dodge that gets a body out of
    * one has to leave the line entirely, and ninety degrees is the smallest angle that does
    * that for every approach direction rather than only the head-on one.
    */
   public static final double DODGE_TURN = 90.0;
   /**
    * Ticks between the projectile becoming unavoidable and the body moving, for that to be a
    * hand rather than a module.
    *
    * <p>One tick is fifty milliseconds. A person reacts to something they have seen in
    * something like two hundred, and the game's own round trip is a tick or two on top of
    * that - so a body that has already moved by the tick a projectile within a couple of
    * blocks of it would land is not reacting to it at all. It was told.
    */
   public static final int DODGE_REACTION_TICKS = 1;

   /**
    * How sharply a body turned between two steps, in degrees: 0 is straight on, 180 is a
    * reversal, and a step with no length in either sample is not a turn at all.
    */
   public static double turnDegrees(double fromX, double fromZ, double toX, double toZ) {
      double from = Math.hypot(fromX, fromZ);
      double to = Math.hypot(toX, toZ);
      if (!(from > 1.0E-4) || !(to > 1.0E-4)) {
         return 0.0;
      }
      double dot = (fromX * toX + fromZ * toZ) / (from * to);
      return Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, dot))));
   }

   /**
    * Whether this turn is a dodge no hand could have made.
    *
    * <p>Both halves are required, and each of them alone is ordinary play: plenty of turns
    * are sharper than ninety degrees, and every player has been shot at. The claim is the
    * coincidence - the body left the projectile's line on the very tick the projectile became
    * unavoidable - and it is the coincidence that repeats when a module is doing it, which is
    * why the caller counts three of them in a window rather than acting on one.
    */
   public static boolean superhumanDodge(double turn, long sinceThreat) {
      return sinceThreat >= 0L
         && sinceThreat <= DODGE_REACTION_TICKS
         && turn >= DODGE_TURN;
   }

   // ----------------------------------------------------------------- hunger
   /**
    * Blocks of sprinting before a body that has not paid for any of it is worth
    * reporting.
    *
    * <p>Vanilla charges about one point of food or saturation per forty blocks
    * sprinted, so a hundred and twenty blocks is three points of a twenty point
    * bar - an amount no account can sprint away without noticing, on any server
    * that has hunger switched on at all. Below it the measurement is noise: food
    * regenerates, saturation refills on eating, and a player who ate two seconds
    * ago is not evidence of anything.
    */
   public static final double HUNGER_SPRINT_BLOCKS = 120.0;
   /** Whether an observed food drop is enough to call the fatigue real. */
   public static boolean hungerPaid(double before, double after) {
      return after < before - 1.0E-4;
   }

   // ------------------------------------------------------------- consume use
   /**
    * Fraction of an item's own use duration a completed use may take before the
    * completion is suspicious.
    *
    * <p>Half. A potion is thirty-two ticks of drinking and a piece of most food is
    * sixteen to thirty-two ticks of eating; a client that finishes one in under
    * half that time has skipped ticks the server counted. The margin is wide
    * because the measurement is the client's packet timing, and a client whose
    * packets arrive in bursts must not be read as a fast-use module - which is why
    * the caller also requires that something was actually consumed.
    */
   public static final double FAST_USE_RATIO = 0.5;

   private MotionModel() {
   }
}
