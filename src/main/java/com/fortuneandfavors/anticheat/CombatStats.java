package com.fortuneandfavors.anticheat;

import java.util.Locale;

/**
 * The statistical half of the anticheat: what a player's <i>pattern</i> looks
 * like, rather than what any one action looks like.
 *
 * <p>Every other check in this package can point at a single event. Reach has a
 * distance, fast-mine has a stopwatch, scaffold has a block. Section 8 of the
 * specification asks for the other kind of evidence - the shape of a stream of
 * events - because the hacks that are hardest to see are the ones that never do
 * anything impossible. An autoclicker that clicks at a rate a hand can reach, and
 * an aimbot that only ever corrects as much as a good player would, are both
 * invisible to any threshold on a single event and both perfectly visible in a
 * distribution.
 *
 * <p>This class is deliberately pure. It takes numbers and returns numbers, with
 * no Minecraft, no server and no clock of its own, so the self-test can hand it a
 * human's clicking and a machine's and assert the difference is decided by the
 * statistics rather than by a constant someone picked. That is the whole reason
 * it is a separate file rather than a few lines inside {@link AntiCheat}.
 *
 * <p>What it will never do is decide on one signal. Each reading carries a
 * {@code corroborated} flag, and the callers treat an uncorroborated reading as
 * evidence at best - see the "no single-check auto-bans" rule, which this file is
 * built to obey rather than to be exempted from.
 */
public final class CombatStats {
   // ----------------------------------------------------------------- clicking

   /**
    * Intervals kept. Forty taps is eight seconds of continuous fighting at a
    * realistic rate, which is far longer than the burst an honest player produces
    * by accident and short enough that a player who stops clicking is judged on
    * their last seconds rather than their whole session.
    */
   public static final int CLICK_WINDOW = 40;
   /**
    * Intervals a window needs before any of it means anything. A short sample
    * cannot distinguish "very regular" from "coincidence": three taps at 100ms is
    * something a hand does routinely, twenty-four is not.
    */
   public static final int CLICK_MIN_SAMPLES = 24;
   /**
    * Milliseconds of spread across the whole window that no hand can hold. This
    * is the primary test and it is a physical one rather than a statistical one:
    * a wrist tapping a mouse has variability of twenty to forty milliseconds, so a
    * window where every gap is within eight of every other gap is not a fast
    * player, it is a program - and it stays true whether the program clicks at a
    * constant rate or adds jitter to hide it, because the jitter is smaller than
    * the spread it would need to be.
    */
   public static final double CLICK_BAND_MS = 8.0;
   /**
    * Taps per second that a hand cannot hold. Butterfly and drag clicking reach
    * this in a burst; nothing sustains it for a twenty-four tap window while also
    * landing inside {@link #CLICK_BAND_MS}, which is why it is corroboration
    * rather than a trigger.
    */
   public static final double CLICK_CPS_LIMIT = 16.0;
   /**
    * A gap longer than this is a pause, not a click. Recording it would pollute
    * the window with the seconds a player spent running to the fight, and would
    * hand every honest player a free lap of low variance.
    */
   public static final double CLICK_PAUSE_MS = 1200.0;
   /**
    * Fraction of the window that must be distinct values for the spread to be a
    * hand's. A human's gaps are continuous and are almost never exactly equal; a
    * program's are drawn from a small set, and even a humanized one repeats itself
    * because the jitter has a step size. Half is deliberately lenient - it is
    * corroboration, not a trigger.
    */
   public static final double CLICK_DISTINCT_RATIO = 0.5;
   /**
    * A weapon whose own interval is shorter than this has no cooldown to be
    * faster than, and the check stands down for it entirely.
    *
    * <p>This is the honest limit of the check rather than a threshold anyone
    * tuned. Holding the attack key with an instant-swing weapon - this mod's
    * Legacy 1.8 enchantment adds +1024 attack speed, so a sword's interval is
    * about a millisecond - attacks as fast as the client can, which is twenty
    * times a second. An autoclicker on the same weapon does exactly the same
    * thing. There is no advantage to take and therefore nothing to detect, so the
    * only thing this check could do here is flag every player who holds down
    * attack, and section 44 is explicit that a missed cheater beats that.
    *
    * <p>150ms is 6.7 attacks a second: faster than a hand can tap one at a time,
    * slower than anything a player can hold and still aim. Every ordinary weapon
    * is measured - a sword is 625ms, an axe a second - and only the instant ones
    * are stood down.
    */
   public static final double CLICK_COOLDOWN_FLOOR_MS = 150.0;
   /**
    * Seconds between two findings from the same statistic. This is what keeps a
    * violation level meaningful: without it a saturated rolling window would
    * report on every single click, and the level would be counting packets rather
    * than evidence - which section 26 of the specification rules out by name.
    * One report per window, and one window per ten seconds, means a level of 12
    * is twelve distinct findings, which is a player who has been at it for two
    * minutes.
    */
   public static final long REPORT_GAP_NANOS = 10_000_000_000L;

   // --------------------------------------------------------------------- aim

   /**
    * Hits a window needs before an error distribution says anything.
    *
    * <p>Was twenty, and twenty consecutive hits is a duel, not a sample. A player who
    * fights a lot produces twenty hits inside a round without trying, and the smaller the
    * window the wider the confidence interval on every number read out of it - which is
    * exactly the shape of a check that "detects on me without cheats". Thirty is a real
    * sample, and it is still a fraction of one fight.
    */
   public static final int AIM_MIN_SAMPLES = 30;
   /**
    * Degrees of average error to the target's centre across the window. A hit
    * lands anywhere on a body that is about eleven degrees wide at three blocks,
    * so a player aiming at the body has several degrees of honest scatter even
    * when every swing connects.
    *
    * <p>Was three degrees over twenty hits, which a good player with a mouse reaches
    * while fighting normally: held aim on a strafing body, a crit that landed while the
    * crosshair was still travelling, and a bow shot all read as "placed rather than
    * pointed". An aimbot lands on the centre to within a degree and a half, sustained,
    * and that is the figure this now asks for.
    */
   public static final double AIM_MEAN_DEGREES = 1.6;
   /** ...and the spread of those errors, for the same reason. */
   public static final double AIM_SD_DEGREES = 0.8;
   /** An error this small is on the centre, not near it. */
   public static final double AIM_PERFECT_DEGREES = 0.6;
   /** Fraction of a window that must be that precise for it to corroborate. */
   public static final double AIM_PERFECT_RATIO = 0.85;
   /**
    * Hits that were easy do not count. A target that is standing still, or that
    * drifted less than this over the second before the hit, can be hit on the
    * centre by anyone who can hold a mouse steady - so those samples are dropped
    * rather than allowed to lower the average of the ones that were hard.
    *
    * <p>Raised, because the band it dropped was narrow enough to keep hits a fight had
    * already made easy: a body drifting a tenth of a block a second is a body standing
    * still in every sense that matters, and a knockback mid-fight can leave a target under
    * this figure for a whole window.
    */
   public static final double AIM_TARGET_SPEED_MIN = 0.15;

   private CombatStats() {
   }

   /**
    * One statistic's verdict. {@code check} is null when there is nothing to
    * report; {@code corroborated} says whether a second, independent signal
    * agreed, which is what separates "worth writing down" from "worth acting on".
    */
   public record Reading(String check, String detail, double weight, boolean corroborated) {
      public static final Reading NONE = new Reading(null, null, 0.0, false);
   }

   // ------------------------------------------------------------- click window

   /**
    * A rolling window of click intervals for one player.
    *
    * <p>The gate that matters is {@link #click}'s cooldown argument, and it is the
    * difference between this check being useful and this check banning everyone.
    * In modern combat a player who simply holds the attack key attacks at exactly
    * their weapon's cadence, which is a <i>perfectly regular</i> interval stream -
    * the most machine-like pattern in the game, produced without touching a
    * macro. So any interval at or above the weapon's own cadence is discarded
    * here, unjudged: it is either a held key or a player clicking as fast as the
    * weapon can use, and neither is a cheat. What is left is taps that came
    * <i>faster</i> than the game could consume them, which is the only place an
    * autoclicker exists at all.
    */
   public static final class Clicks {
      private final long[] intervals = new long[CLICK_WINDOW];
      private int count;
      private int head;
      private long lastNanos;
      private long nextReportNanos;

      /**
       * One attack packet, timestamped by {@code System.nanoTime()}.
       *
       * @param cooldownMs the weapon's own interval between attacks, in
       *        milliseconds, from the player's attack-speed attribute.
       * @return a reading, or {@link Reading#NONE} when there is nothing to say.
       */
      public Reading click(long nowNanos, double cooldownMs) {
         long previous = this.lastNanos;
         this.lastNanos = nowNanos;
         if (previous == 0L) {
            return Reading.NONE;
         }
         double interval = (nowNanos - previous) / 1.0E6;
         if (!(interval > 0.0) || interval > CLICK_PAUSE_MS) {
            return Reading.NONE;
         }
         // The held-key gate. See the class comment: at or above the weapon's own
         // cadence this is not a click, and judging it would make holding the
         // attack key a violation.
         if (cooldownMs > 0.0 && interval >= cooldownMs * 0.9) {
            return Reading.NONE;
         }
         // ...and the weapons with no cadence at all to be held against, where
         // holding and macroing are the same thing. See the constant.
         if (cooldownMs > 0.0 && cooldownMs < CLICK_COOLDOWN_FLOOR_MS) {
            this.clear();
            return Reading.NONE;
         }
         this.intervals[this.head] = Math.round(interval);
         this.head = (this.head + 1) % CLICK_WINDOW;
         if (this.count < CLICK_WINDOW) {
            this.count++;
         }
         if (this.count < CLICK_MIN_SAMPLES) {
            return Reading.NONE;
         }
         Reading reading = judge();
         if (reading == null) {
            return Reading.NONE;
         }
         // One report per window, and the window is spent by it: the next finding
         // needs another twenty-four taps of fresh evidence.
         if (nowNanos < this.nextReportNanos) {
            return Reading.NONE;
         }
         this.nextReportNanos = nowNanos + REPORT_GAP_NANOS;
         Reading answer = reading;
         this.clear();
         return answer;
      }

      private Reading judge() {
         long min = Long.MAX_VALUE;
         long max = Long.MIN_VALUE;
         double total = 0.0;
         for (int i = 0; i < this.count; i++) {
            long value = this.intervals[i];
            min = Math.min(min, value);
            max = Math.max(max, value);
            total += value;
         }
         double mean = total / this.count;
         double band = max - min;
         if (band > CLICK_BAND_MS) {
            return null;
         }
         double cps = mean <= 0.0 ? 0.0 : 1000.0 / mean;
         double distinct = distinctRatio();
         boolean fast = cps >= CLICK_CPS_LIMIT;
         boolean repetitive = distinct <= CLICK_DISTINCT_RATIO;
         if (!fast && !repetitive) {
            return null;
         }
         String detail = this.count + " taps of " + round(mean) + "ms apart, every one inside "
            + round(band) + "ms of the others (" + round(cps) + "/s, "
            + round(distinct * 100.0) + "% of them different"
            + (fast ? ", past what a hand holds" : "") + ")";
         return new Reading(AntiCheat.AUTOCLICKER, detail, 1.0, true);
      }

      /** Distinct interval values as a fraction of the window. */
      private double distinctRatio() {
         int distinct = 0;
         for (int i = 0; i < this.count; i++) {
            boolean first = true;
            for (int j = 0; j < i; j++) {
               if (this.intervals[j] == this.intervals[i]) {
                  first = false;
                  break;
               }
            }
            if (first) {
               distinct++;
            }
         }
         return this.count == 0 ? 1.0 : distinct * 1.0 / this.count;
      }

      public int samples() {
         return this.count;
      }

      public double meanMs() {
         if (this.count == 0) {
            return 0.0;
         }
         double total = 0.0;
         for (int i = 0; i < this.count; i++) {
            total += this.intervals[i];
         }
         return total / this.count;
      }

      public void clear() {
         this.count = 0;
         this.head = 0;
         this.lastNanos = 0L;
      }
   }

   // --------------------------------------------------------------- aim window

   /**
    * A rolling window of how far off centre a player's connecting hits were.
    *
    * <p>The error is measured at the moment of the hit, from the attacker's eyes
    * to the target's centre - the number {@code onAttack} already computes for the
    * killaura check. A person who is genuinely good averages several degrees
    * against a moving target, because they aim at the body and hit whatever part
    * of it their crosshair reached. An aimbot lands the ray through the centre,
    * which is why the signal is the distribution rather than any one hit: one
    * perfect hit is skill, twenty in a row against a strafing opponent is a
    * matrix.
    */
   public static final class Aim {
      private final double[] errors = new double[AIM_MIN_SAMPLES * 3];
      private final boolean[] onCentre = new boolean[AIM_MIN_SAMPLES * 3];
      private int count;
      private int head;
      private long nextReportNanos;
      private int sampled;

      /**
       * One connecting hit.
       *
       * @param nowNanos the wall clock the hit landed on, for the report gap.
       * @param errorDegrees how far the look ray was from the target's centre.
       * @param targetSpeed blocks per tick the target was travelling, so that
       *        standing-still samples - which even an honest player lands on the
       *        centre - are dropped rather than averaged in.
       * @return a reading, or {@link Reading#NONE}.
       */
      public Reading hit(long nowNanos, double errorDegrees, double targetSpeed, boolean swungFromBehindCover) {
         if (!(errorDegrees >= 0.0) || Double.isNaN(errorDegrees)) {
            return Reading.NONE;
         }
         if (targetSpeed < AIM_TARGET_SPEED_MIN) {
            return Reading.NONE;
         }
         // A hit the wall check can already explain is not evidence about aim; the
         // killaura check owns that case and scores it on its own.
         if (swungFromBehindCover) {
            return Reading.NONE;
         }
         this.errors[this.head] = errorDegrees;
         this.onCentre[this.head] = errorDegrees <= AIM_PERFECT_DEGREES;
         this.head = (this.head + 1) % this.errors.length;
         if (this.count < this.errors.length) {
            this.count++;
         }
         this.sampled++;
         if (this.sampled < AIM_MIN_SAMPLES) {
            return Reading.NONE;
         }
         Reading reading = judge();
         if (reading == null) {
            return Reading.NONE;
         }
         if (nowNanos <= this.nextReportNanos) {
            return Reading.NONE;
         }
         this.nextReportNanos = nowNanos + REPORT_GAP_NANOS;
         Reading answer = reading;
         this.clear();
         return answer;
      }

      private Reading judge() {
         double total = 0.0;
         for (int i = 0; i < this.count; i++) {
            total += this.errors[i];
         }
         double mean = total / this.count;
         double variance = 0.0;
         for (int i = 0; i < this.count; i++) {
            double d = this.errors[i] - mean;
            variance += d * d;
         }
         double sd = Math.sqrt(variance / this.count);
         if (mean > AIM_MEAN_DEGREES || sd > AIM_SD_DEGREES) {
            return null;
         }
         int perfect = 0;
         for (int i = 0; i < this.count; i++) {
            if (this.onCentre[i]) {
               perfect++;
            }
         }
         double perfectRatio = perfect * 1.0 / this.count;
         if (perfectRatio < AIM_PERFECT_RATIO) {
            return null;
         }
         String detail = this.count + " hits on moving targets averaged " + round(mean)
            + "deg off centre, spread " + round(sd) + "deg, "
            + round(perfectRatio * 100.0) + "% of them within " + round(AIM_PERFECT_DEGREES) + "deg";
         return new Reading(AntiCheat.AIM, detail, 1.0, true);
      }

      public int samples() {
         return this.sampled;
      }

      public double meanDegrees() {
         if (this.count == 0) {
            return 0.0;
         }
         double total = 0.0;
         for (int i = 0; i < this.count; i++) {
            total += this.errors[i];
         }
         return total / this.count;
      }

      public void clear() {
         this.count = 0;
         this.head = 0;
         this.sampled = 0;
      }
   }

   // ------------------------------------------------------------- aim rotation

   /**
    * How small a difference between two rotation deltas counts as no difference.
    *
    * <p>Degrees, and it is a tenth of a register because that is what it has to be:
    * the client reports its facing as a float, and two consecutive deltas produced by
    * a mouse are never bit-identical while two produced by a constant-rate turn are
    * bit-identical by construction. The epsilon exists only so a client that rounds on
    * the way out is not called a machine for it.
    */
   public static final double ROTATION_EPSILON = 0.01;
   /**
    * Degrees of turn in one sample below which the sample is not a turn.
    *
    * <p>A player who is not moving their mouse produces a stream of zero deltas, and
    * zero deltas are - trivially - perfectly constant. Every statistic here would fire
    * on somebody standing still reading a sign, so a turn has to be a turn before any
    * of it is measured: three tenths of a degree per packet is a slow deliberate pan,
    * and is the floor the whole file sits on.
    */
   public static final double ROTATION_MIN_DEGREES = 0.3;
   /** Equal deltas in a row before a constant-rate turn is a machine. Six packets is
    *  a third of a second of perfectly uniform rotation, which a wrist cannot do.
    *  Four would be a coincidence; six is a rate. */
   public static final int ROTATION_CONSTANT_RUN = 6;
   /** Packets in the module-360 window. A full second of sustained rotation. */
   public static final int ROTATION_WINDOW = 20;
   /**
    * Coefficient of variation allowed across that window. One percent. This is the
    * statistic an aim module cannot beat while it is <i>working</i>: a human tracking a
    * target has to accelerate and decelerate, cross and correct, and their speed varies
    * by tens of percent even when they are good. A module turning to a target at a
    * fixed rate through a whole second has a spread under a percent, and jittering it
    * to hide that would make it stop hitting. So the number is small on purpose and the
    * cost of being wrong is an alert, never a correction.
    */
   public static final double ROTATION_CV_LIMIT = 0.01;
   /** Samples in a geometric run before an eased turn is a machine. */
   public static final int ROTATION_LINEAR_RUN = 5;
   /** How close the ratio between successive deltas has to be to hold, as a fraction. */
   public static final double ROTATION_LINEAR_TOLERANCE = 0.02;

   /**
    * The rotation stream: how a player's aim <i>moved</i>, which is the one thing a
    * crosshair statistic can see that a hit statistic cannot.
    *
    * <p>The three readings here are deliberately three different shapes of the same
    * observation, because "the aim moved mechanically" has more than one mechanical
    * shape and lumping them together would tell an admin the wrong thing:
    *
    * <ul>
    *   <li><b>Constant</b> - successive deltas that are exactly equal. This is a turn
    *       at a fixed rate, and it is what a client does when it drives the aim along a
    *       line.</li>
    *   <li><b>Linear</b> - successive deltas in a fixed <i>ratio</i>. This is a turn
    *       that eases, accelerating or decaying by the same factor every packet, which
    *       is what an interpolation between two points looks like sample by sample. A
    *       hand decelerating into a target does it once; a module does it every time.</li>
    *   <li><b>Module 360</b> - a full second of rotation whose <i>speed</i> never
    *       varies. Not the direction and not the deltas: the variance of the turn rate
    *       itself, which is the thing that cannot be held constant by a person for a
    *       second while they are actually aiming at something.</li>
    * </ul>
    *
    * <p>The gate that keeps all three off honest players is the same one the click
    * window uses: <b>a swing has to be recent</b>. Rotation with no fighting around it
    * is a player looking at the scenery, panning a camera, or spinning on a boss's
    * mechanic, and none of those are evidence of anything. Only aim that was
    * <i>being used</i> is judged, and every reading is corroborated by construction -
    * which is to say it needs a run, not a sample.
    */
   public static final class Rotation {
      private final double[] deltas = new double[ROTATION_WINDOW * 2];
      private int count;
      private int head;
      private float lastYaw;
      private float lastPitch;
      private boolean hasLast;
      private long nextReportNanos;
      private int sampled;

      /**
       * One rotation sample, from a movement packet.
       *
       * @param swinging whether a swing happened within the last
       *        {@code ROTATION_ATTACK_WINDOW} nanoseconds. Passed in rather than
       *        remembered here because the click stream owns that stamp.
       */
      public Reading sample(long nowNanos, float yaw, float pitch, boolean swinging) {
         if (!this.hasLast) {
            this.lastYaw = yaw;
            this.lastPitch = pitch;
            this.hasLast = true;
            return Reading.NONE;
         }
         float dyaw = wrap(yaw - this.lastYaw);
         float dpitch = pitch - this.lastPitch;
         this.lastYaw = yaw;
         this.lastPitch = pitch;
         double delta = Math.sqrt(dyaw * dyaw + dpitch * dpitch);
         if (!(delta >= 0.0) || Double.isNaN(delta)) {
            return Reading.NONE;
         }
         this.deltas[this.head] = delta;
         this.head = (this.head + 1) % this.deltas.length;
         if (this.count < this.deltas.length) {
            this.count++;
         }
         this.sampled++;
         if (!swinging) {
            // Not being used, so not evidence. The window is kept - a player who swings
            // after a long turn should be judged on the turn they made during it - but
            // nothing is reported while there is no fight.
            return Reading.NONE;
         }
         if (this.count < ROTATION_CONSTANT_RUN) {
            return Reading.NONE;
         }
         Reading reading = judge();
         if (reading == null || nowNanos <= this.nextReportNanos) {
            return Reading.NONE;
         }
         this.nextReportNanos = nowNanos + REPORT_GAP_NANOS;
         return reading;
      }

      private Reading judge() {
         Reading constant = constantRun();
         if (constant != null) {
            return constant;
         }
         Reading linear = linearRun();
         if (linear != null) {
            return linear;
         }
         return module360();
      }

      /** Successive deltas that are exactly equal, above the turn floor. */
      private Reading constantRun() {
         int run = 0;
         double value = 0.0;
         for (int i = this.count - 1; i >= 0; i--) {
            double delta = this.deltas[this.physical(i)];
            if (delta < ROTATION_MIN_DEGREES) {
               return null;
            }
            if (run == 0) {
               value = delta;
            } else if (Math.abs(delta - value) > ROTATION_EPSILON) {
               if (run >= ROTATION_CONSTANT_RUN) {
                  return new Reading(
                     AntiCheat.AIM_CONSTANT,
                     run + " turns of exactly " + round(value) + "deg in a row, to within "
                        + ROTATION_EPSILON + "deg",
                     1.0,
                     true
                  );
               }
               return null;
            }
            run++;
            if (run >= ROTATION_CONSTANT_RUN) {
               return new Reading(
                  AntiCheat.AIM_CONSTANT,
                  run + " turns of exactly " + round(value) + "deg in a row, to within "
                     + ROTATION_EPSILON + "deg",
                  1.0,
                  true
               );
            }
         }
         return null;
      }

      /** Successive deltas in a fixed ratio: an interpolation between two points. */
      private Reading linearRun() {
         if (this.count < ROTATION_LINEAR_RUN + 1) {
            return null;
         }
         double ratio = 0.0;
         int run = 0;
         double previous = 0.0;
         for (int i = this.count - 1; i >= 0; i--) {
            double delta = this.deltas[this.physical(i)];
            if (delta < ROTATION_MIN_DEGREES) {
               return null;
            }
            if (run == 0) {
               previous = delta;
               run++;
               continue;
            }
            double step = previous <= 0.0 ? 1.0 : delta / previous;
            if (run == 1) {
               ratio = step;
            } else if (Math.abs(step - ratio) > ROTATION_LINEAR_TOLERANCE) {
               return null;
            }
            previous = delta;
            run++;
            if (run >= ROTATION_LINEAR_RUN) {
               return new Reading(
                  AntiCheat.AIM_LINEAR,
                  run + " turns each exactly " + round(ratio) + "x the one before - a turn that "
                     + "eases by the same factor every packet",
                  1.0,
                  true
               );
            }
         }
         return null;
      }

      /** A full window of rotation whose speed never varies. */
      private Reading module360() {
         if (this.count < ROTATION_WINDOW) {
            return null;
         }
         double total = 0.0;
         for (int i = 0; i < ROTATION_WINDOW; i++) {
            double delta = this.deltas[this.physical(i)];
            if (delta < ROTATION_MIN_DEGREES) {
               return null;
            }
            total += delta;
         }
         double mean = total / ROTATION_WINDOW;
         double variance = 0.0;
         for (int i = 0; i < ROTATION_WINDOW; i++) {
            double d = this.deltas[this.physical(i)] - mean;
            variance += d * d;
         }
         double cv = mean <= 0.0 ? 1.0 : Math.sqrt(variance / ROTATION_WINDOW) / mean;
         if (cv > ROTATION_CV_LIMIT) {
            return null;
         }
         return new Reading(
            AntiCheat.AIM_MODULE_360,
            ROTATION_WINDOW + " packets of rotation at " + round(mean) + "deg each, spread "
               + round(cv * 100.0) + "% - a second of turning at a rate that never changed",
            1.0,
            true
         );
      }

      /** Index of the i-th oldest live sample in the ring. */
      private int physical(int i) {
         int start = this.count == this.deltas.length ? this.head : 0;
         return (start + i) % this.deltas.length;
      }

      public int samples() {
         return this.sampled;
      }

      public void clear() {
         this.count = 0;
         this.head = 0;
         this.hasLast = false;
         this.sampled = 0;
      }
   }

   /** The shortest way round the circle between two angles. */
   public static float wrap(float degrees) {
      float wrapped = degrees % 360.0F;
      if (wrapped >= 180.0F) {
         wrapped -= 360.0F;
      }
      if (wrapped < -180.0F) {
         wrapped += 360.0F;
      }
      return wrapped;
   }

   // ------------------------------------------------------------------ helpers

   /**
    * Mean of a sample, for the self-test and for the readouts. Kept here with the
    * rest of the arithmetic so the numbers a check compares against and the
    * numbers {@code /ff anticheat simulate} prints cannot drift apart.
    */
   public static double mean(double[] values) {
      if (values == null || values.length == 0) {
         return 0.0;
      }
      double total = 0.0;
      for (double value : values) {
         total += value;
      }
      return total / values.length;
   }

   /** Population standard deviation of a sample. */
   public static double stddev(double[] values) {
      if (values == null || values.length == 0) {
         return 0.0;
      }
      double mean = mean(values);
      double sum = 0.0;
      for (double value : values) {
         double d = value - mean;
         sum += d * d;
      }
      return Math.sqrt(sum / values.length);
   }

   /** Coefficient of variation: the spread as a fraction of the mean. */
   public static double variation(double[] values) {
      double mean = mean(values);
      return mean <= 0.0 ? 0.0 : stddev(values) / mean;
   }

   private static String round(double value) {
      return String.format(Locale.ROOT, "%.2f", value);
   }
}
