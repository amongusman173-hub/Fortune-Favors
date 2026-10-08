package com.fortuneandfavors.anticheat;

/**
 * The movement model the speed and knockback checks judge against.
 *
 * <p>What was here before was one number: {@code horizontal > 0.55} blocks in a
 * tick, plus a handful of flat bonuses per effect. That is not a model of
 * anything - it is a guess that happens to sit above sprinting, and it is wrong
 * in both directions at once. It is too loose on the ground, where a hack only
 * has to stay under 0.55 to be invisible, and too tight on ice, where an honest
 * player who has been sliding legitimately exceeds it.
 *
 * <p>This is the arithmetic vanilla actually runs, in the order it runs it. Each
 * tick a moving body takes the step it had, decays it by the friction of the
 * surface under it, and adds what its input was worth on that surface:
 *
 * <pre>
 *   friction   = slipperiness * 0.91          (0.6 * 0.91 on ordinary ground)
 *   input      = speed * 0.216 / friction^3   (vanilla's own formula)
 *   step(t)    = step(t-1) * friction + input
 *   steady     = input / (1 - friction)       (what that settles at)
 * </pre>
 *
 * <p>The numbers are anchored rather than invented: {@link #WALK_STEP} is
 * vanilla's own walking ceiling (4.317 blocks a second) and {@link #JUMP_STEP} is
 * its measured sprint-jump speed, so the model cannot drift away from the game
 * while still being derived from the same friction and effect multipliers the
 * game uses. Everything else follows from those two and the block tables.
 *
 * <p>The other half of the design is that nothing here is compared tick by tick.
 * A client that lagged out delivers several ticks inside one server tick, and a
 * per-tick comparison calls that a speed hack - which is exactly how an anticheat
 * gets a reputation for punishing bad connections. The checks measure over a
 * window and compare blocks per <i>second</i>, which a burst cannot inflate: the
 * distance is real, only its delivery was late.
 */
public final class MovementPhysics {
   /**
    * Vanilla's walking ceiling: 4.317 blocks a second, or 0.21585 of a block in
    * one tick. Every other number here is calibrated against this one.
    */
   public static final double WALK_STEP = 0.21585;
   /** The slipperiness vanilla's block table gives ordinary solid ground. */
   public static final double GROUND_SLIPPERINESS = 0.6;
   /** Ice, which is where an honest player is fastest and a fixed threshold lies. */
   public static final double ICE_SLIPPERINESS = 0.98;
   /** Slime, the other surface that keeps momentum for a while. */
   public static final double SLIME_SLIPPERINESS = 0.8;
   /** The factor vanilla multiplies ground slipperiness by, and uses on its own
    *  in the air. */
   public static final double AIR_FRICTION = 0.91;
   /** Sprinting multiplies the movement attribute, not the friction. */
   public static final double SPRINT_FACTOR = 1.3;
   /**
    * Vanilla's measured sprint-jump speed, 7.13 blocks a second, over the sprint
    * factor so that a potion and the jump compose the way they do in the game:
    * jumping loses the ground friction and keeps the momentum it had, which is
    * why a bunny-hop outruns a run.
    */
   public static final double JUMP_STEP = 0.356 / SPRINT_FACTOR;
   /**
    * How far over the model an average may sit and still be called the same
    * movement. This can be tight because the measurement is a window average: a
    * client whose packets arrived late has the same average as one whose packets
    * arrived on time, so there is no lag to leave room for beyond the ordinary
    * shape of a jump arc.
    */
   public static final double TOLERANCE = 1.12;
   /** ...plus this, so a very slow walk is never judged against a fraction of a
    *  block. */
   public static final double SLACK = 0.5;
   /** The share of an applied knockback a player must actually travel for it to
    *  count as having been knocked back.
    *
    *  <p>Was a half, and a half was too much of the shove to require. The travel this is
    *  compared against is a model - an impulse projected through the surface's friction
    *  for eight ticks - and a player who is sprinting, fighting uphill, standing on a slab
    *  edge, in a fight's own shove, or simply holding W into the hit keeps far less of the
    *  model than half of it without doing anything to the knockback at all. A quarter is
    *  the line at which the shortfall stops being movement and starts being a client that
    *  suppressed the packet: a knockback-delay module keeps almost none of it, which is
    *  what the check is for. "It is way too sensitive on knockback" was this number. */
   public static final double KNOCKBACK_FLOOR = 0.25;
   /**
    * The one thing the knockback check needs: a shove worth measuring.
    *
    * <p>The travel this is compared against is the distance remaining after the
    * body's own friction, the surface under it and one tick of server lag have had
    * their turn, and a nudge worth a fraction of a block is inside all of that - a
    * step, a slab, a berry bush and a stair all move a body further than the shove
    * being asked about. Below this the check would be reading noise, so it does not
    * read anything: "low knockback stuff" is the report that came of it doing so.
    */
   public static final double KNOCKBACK_MEASURABLE = 1.5;
   /**
    * The smallest change in a body's motion that counts as the server having shoved it.
    *
    * <p>This is the number that separates a hit from a shove. The impulse the knockback
    * check measures is the <i>change</i> the hit made to the victim's velocity along the
    * direction away from the attacker - not the velocity itself, which is mostly the
    * player's own feet. A body accelerating on its own changes by about a twentieth of a
    * block per tick; the weakest real shove there is (an unenchanted fist) is 0.4. Low
    * knockback and no knockback therefore live together, far below this line, and the
    * report that made them live here was a hit that dealt none: the old measurement read
    * the victim's own sprint as the shove, and a player who then stopped (or stood to drop
    * something) "kept zero of it".
    */
   public static final double KNOCKBACK_MIN_IMPULSE = 0.2;
   /** How long a knockback is expected to take to play out. */
   public static final int KNOCKBACK_TICKS = 8;
   /**
    * The tick rate every number above is quoted at, and the one a tick is worth 50ms at.
    *
    * <p>It is named rather than assumed because a server tick is not always fifty milliseconds,
    * and every check in this module compares a <i>tick</i> count against a model quoted in
    * ticks. On a server running at half speed one tick carries two ticks of a client's
    * movement, and a check that keeps comparing tick for tick reads an honest player as one
    * moving at double speed - which is what {@link #tickScale} corrects.
    */
   public static final double FULL_TPS = 20.0;
   /**
    * The most of a client's own ticks one server tick is taken to be carrying.
    *
    * <p>Two, so the correction covers a server down to ten ticks a second, and stops there
    * rather than scaling without limit. Below that the sample is not a measurement of movement
    * with a scale factor attached - it is a server that cannot deliver the game, and the checks
    * that read these numbers stand down instead (see the speed check's own floor). A cap is also
    * what stops the correction from being a hole: without one, a server that collapsed to two
    * ticks a second would allow fifty times the model.
    */
   public static final double MAX_TICK_SCALE = 2.0;

   private MovementPhysics() {
   }

   /**
    * How many of a client's own ticks one server tick is carrying, given the server's tick rate.
    *
    * <p>The correction this whole class exists to make possible. Every measurement in the speed
    * and mine checks is <i>one sample per server tick</i>, and the model each is compared against
    * is quoted per client tick - fifty milliseconds of movement. Those are the same thing only at
    * twenty ticks a second. A server running at ten delivers two client ticks inside every one of
    * its own, so a body that is moving perfectly honestly puts twice the model's distance in one
    * sample, and a mine that honestly takes thirty client ticks takes fifteen server ticks. Undo
    * that and an honest player on a struggling server is a speed hacker and a nuker at the same
    * time, on both checks, for as long as the server is behind - which is precisely when a
    * server has the least attention to spare for it.
    *
    * <p>Twenty ticks a second answers 1.0, and the scale never goes below it: a server that is
    * <i>faster</i> than twenty ticks a second does not exist, and a sample that covered less than
    * a client tick would only make the checks tighter than the game they model.
    */
   public static double tickScale(double tps) {
      if (!(tps > 0.0) || !Double.isFinite(tps)) {
         return 1.0;
      }
      return Math.max(1.0, Math.min(MAX_TICK_SCALE, FULL_TPS / tps));
   }

   /** The friction a body standing on this surface decays its step by. */
   public static double friction(double slipperiness) {
      double slip = slipperiness;
      if (!(slip >= 0.0) || slip > 1.0) {
         slip = GROUND_SLIPPERINESS;
      }
      return slip * AIR_FRICTION;
   }

   /**
    * What one tick of held input is worth on a surface with this friction.
    *
    * <p>Vanilla's own formula is {@code speed * 0.216 / friction^3}, which is
    * almost impossible to read off a decompiler and easy to get wrong, so this
    * states it as a ratio against ordinary ground: the same curve, anchored so
    * that ground friction produces exactly the walking ceiling the game has.
    * Slippery surfaces accelerate less but hold what they have, which is the
    * behaviour ice actually has.
    */
   public static double acceleration(double speedFactor, double friction) {
      double anchor = friction(GROUND_SLIPPERINESS);
      double cube = anchor * anchor * anchor / (friction * friction * friction);
      return WALK_STEP * Math.max(0.0, speedFactor) * (1.0 - anchor) * cube;
   }

   /**
    * The step a body settles at on this surface, in blocks per tick.
    *
    * @param jumping any tick of the window was airborne. A player who is
    *        bunny-hopping is judged against the jump ceiling, which is higher
    *        than the run and lower than anything a hack needs.
    */
   public static double steadyStep(double slipperiness, double speedFactor, boolean jumping) {
      double factor = Math.max(0.0, speedFactor);
      if (jumping) {
         return JUMP_STEP * factor;
      }
      double friction = friction(slipperiness);
      return acceleration(factor, friction) / (1.0 - friction);
   }

   /**
    * The most distance a window of this many seconds may contain, in blocks.
    *
    * <p>A window rather than a tick, which is the whole lag story: several ticks
    * arriving inside one server tick carries the same distance, so measuring the
    * distance over real time and not over deliveries is what makes a bad
    * connection indistinguishable from a good one here.
    */
   public static double windowAllowance(double steadyStep, double seconds) {
      return steadyStep * 20.0 * Math.max(0.0, seconds) * TOLERANCE + SLACK;
   }

   /**
    * Whether this surface keeps momentum the way the air does.
    *
    * <p>Ice, packed ice, blue ice, slime and any modded block with the same friction, and the test
    * is the game's own friction number rather than a block list, so a mod that adds a slick floor
    * is treated as one without anybody having to remember to add it here.
    */
   public static boolean keepsMomentum(double slipperiness) {
      return slipperiness > GROUND_SLIPPERINESS + 1.0E-4;
   }

   /**
    * The step a body may be holding <i>on the ground</i> of this surface.
    *
    * <p>The steady speed for ordinary ground, and for a surface that keeps momentum it is the
    * speed a jump holds instead - because on those surfaces the steady speed is not what an
    * honest player is doing. Ice barely slows a body at all (its friction, 0.89, is almost the
    * air's own 0.91), which is the whole point of ice: a player who arrives having just
    * sprint-jumped keeps nearly all of that speed for several ticks, and jumps again to renew it.
    * Their honest speed on the ground of that surface is therefore the <i>jump's</i> speed, not
    * the number a body grinding at held input eventually decays to - and that steady number is
    * the <i>lower</i> of the two on a slippery surface, because slippery surfaces accelerate
    * badly. Judging them against it is what made sprint-jumping across ice look like a speed
    * hack, and the ceiling being handed out here is one the check already grants the same body
    * one tick earlier, in the air. Nothing is given away on ordinary ground: the friction test
    * is exact, so a body on grass is judged exactly as it was.
    */
   public static double groundedCeiling(double slipperiness, double speedFactor) {
      double steady = steadyStep(slipperiness, speedFactor, false);
      if (!keepsMomentum(slipperiness)) {
         return steady;
      }
      return Math.max(steady, steadyStep(slipperiness, speedFactor, true));
   }

   /**
    * The most distance a window may contain when its ticks were not all on the same
    * footing, in blocks.
    *
    * <p>The window above is one ceiling applied to a whole second, and it is the wrong
    * shape for the commonest way a player moves. A body that runs and jumps now and then
    * is <i>on the ground</i> for most of the window and <i>in the air</i> for the rest,
    * and those two states have different ceilings: a jump keeps the momentum it left the
    * ground with and adds its own impulse, so the airborne tick is the fastest tick the
    * game has. Judging the whole second by the ground ceiling means every one of those
    * jumps is measured against a limit its own take-off already exceeded, and a player
    * who is simply running and jumping is flagged for moving at a speed the game defines.
    *
    * <p>Handing the whole window the jump ceiling instead - which is what a majority test
    * on the airborne flag amounts to - is the opposite error: a body that pokes one tick
    * into the air buys the higher ceiling for the other nineteen, and that is free room
    * for exactly the module this check exists to catch. So the ceiling is summed per
    * tick, over the ticks the window actually watched: each grounded sample is worth the
    * step a grounded body may hold on that surface, each airborne one the step a jumping
    * body may hold, and the total is the most distance those ticks could honestly
    * contain. Nothing is given away - a body on the ground the whole second still gets
    * the ground ceiling for the whole second - and nothing honest is refused.
    *
    * @param groundedStep the step a grounded body settles at on the window's surface
    * @param airborneStep the step a jumping body settles at on the window's surface
    * @param groundedSamples samples in the window whose tick ended on the ground
    * @param airborneSamples samples in the window whose tick ended in the air
    */
   public static double windowAllowance(
      double groundedStep, double airborneStep, int groundedSamples, int airborneSamples
   ) {
      return windowAllowance(groundedStep, airborneStep, groundedSamples, airborneSamples, 1.0);
   }

   /**
    * The same allowance, for a server whose ticks are longer than fifty milliseconds.
    *
    * <p>{@code ticksPerSample} is {@link #tickScale}: what one sample of the window is worth in
    * client ticks. It multiplies the per-sample ceilings, because the window's samples are the
    * server's own ticks and the model is quoted per client tick - a sample that carried two ticks
    * of movement may honestly contain twice the step. {@code SLACK} is scaled with it for the same
    * reason the ceiling is: it exists so a very slow walk is never judged against a fraction of a
    * block, and a fraction of a block is not a fraction of a tick.
    *
    * <p>The caller has to scale the <i>time</i> it divides by to match - see the speed check, which
    * reports blocks a <i>second</i> off the same number - because otherwise the correction cancels
    * itself out and the reported rate stops meaning blocks a second at all.
    */
   public static double windowAllowance(
      double groundedStep,
      double airborneStep,
      int groundedSamples,
      int airborneSamples,
      double ticksPerSample
   ) {
      int grounded = Math.max(0, groundedSamples);
      int airborne = Math.max(0, airborneSamples);
      double scale = Math.max(1.0, Math.min(MAX_TICK_SCALE, ticksPerSample));
      if (grounded + airborne <= 0) {
         return SLACK * scale;
      }
      double total = (groundedStep * grounded + airborneStep * airborne) * scale;
      return total * TOLERANCE + SLACK * scale;
   }

   /**
    * The most distance one airborne arc of this many ticks may contain, in blocks.
    *
    * <p>Deliberately the same window allowance the ground speed check uses, applied to a
    * jump. A jump is a window like any other, and giving the airborne case a private
    * tolerance of its own is how the two checks drift apart and start disagreeing about
    * what a fast player looks like - which is precisely the class of bug this file was
    * written to end.
    *
    * <p>What the caller has to get right is the {@code steadyStep} handed in: it should be
    * the model for a jumping body <i>or the step the body actually left the ground with,
    * whichever is larger</i>. An ice take-off, a Speed potion, a sprint off a ledge and a
    * knockback the player walked out of all carry more speed into the air than an
    * ordinary sprint-jump does, and each of them raises its own ceiling rather than
    * tripping it.
    *
    * <p>{@code ticks} is the arc's length in <i>client</i> ticks, which is not the same thing
    * as the number of samples the caller counted it in: on a server behind by half, a
    * sprint-jump's twelve ticks arrive as six samples, and the count has to be scaled by
    * {@link #tickScale} before it is handed in here - the same correction the speed window
    * and the per-tick gain model make. What a hack cannot do is cross more blocks than the
    * ticks it was given allow, at any tick rate.
    */
   public static double airborneAllowance(double steadyStep, int ticks) {
      return windowAllowance(steadyStep, Math.max(0, ticks) / 20.0);
   }

   /**
    * The movement multiplier a player's own movement-speed attribute is worth,
    * with 1.0 being an unmodified player. Sprint status is separate because it is
    * not an attribute.
    */
   public static double speedFactor(double movementSpeedAttribute, boolean sprinting) {
      double ratio = movementSpeedAttribute / 0.1;
      if (!(ratio > 0.0) || !Double.isFinite(ratio)) {
         ratio = 1.0;
      }
      return ratio * (sprinting ? SPRINT_FACTOR : 1.0);
   }

   /**
    * The distance a knockback impulse carries a body over its flight, given the
    * friction in the way.
    *
    * <p>The impulse is measured, not assumed: the check reads the velocity the
    * hit actually put on the player and projects it along the direction away from
    * the attacker. What must not be assumed is the distance, because a knockback
    * decays by friction rather than persisting, so the same hit moves a body
    * twice as far in the air as on the ground - which is why the old fixed
    * expectation was wrong in both directions at once.
    */
   public static double knockbackTravel(double impulse, double friction, int ticks) {
      if (impulse <= 0.0 || ticks <= 0) {
         return 0.0;
      }
      double decay = 1.0 - Math.pow(friction, ticks);
      return impulse * decay / Math.max(1.0E-4, 1.0 - friction);
   }

   /** The distance that counts as the knockback having landed at all. */
   public static double knockbackFloor(double expected) {
      return expected * KNOCKBACK_FLOOR;
   }

   /** Whether a shove is big enough for the knockback check to judge it at all. */
   public static boolean knockbackMeasurable(double expected) {
      return expected >= KNOCKBACK_MEASURABLE;
   }

   /**
    * Whether a hit actually shoved the body it landed on.
    *
    * <p>False means the hit applied no knockback worth the name - a zero-knockback
    * weapon, a parry, a scripted ability - and there is nothing for the knockback check
    * to have been deprived of. Stated as its own function so the self-test pins the
    * decision rather than a copy of it.
    */
   public static boolean knockbackApplied(double impulse) {
      return impulse >= KNOCKBACK_MIN_IMPULSE;
   }

   /**
    * True when the observed travel is too small to be the knockback that was
    * applied. Stated as its own function so the self-test can pin the decision
    * rather than a copy of it.
    */
   public static boolean knockbackCancelled(double travelled, double expected) {
      return expected > 0.0 && travelled < knockbackFloor(expected);
   }
}
