package com.fortuneandfavors.economy;

import com.fortuneandfavors.util.JsonUtil;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Map.Entry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * The Fortune &amp; Favors Exchange: eight mining-flavoured listings whose prices move every five
 * real minutes.
 *
 * <p><b>The market runs on the clock, not on the server.</b> A price is a pure function of
 * {@code (salt, listing, tick index)}, where a tick index is the wall-clock epoch divided by five
 * minutes - there is no running state, no accumulator to advance and nothing to miss. That is what
 * makes "the market keeps moving while nobody is logged in" true by construction rather than by a
 * catch-up loop: stop the server for a week and every price waiting when it comes back is the
 * price the same five-minute grid printed all along, and a chart drawn over the last two days is
 * the chart those days actually had.
 *
 * <p><b>The salt belongs to the world.</b> It is generated once and kept in {@code market.json},
 * so the series is stable for a world but its path cannot be computed by reading this file. A
 * predictable market is a market with a guaranteed strategy, and this is the cheapest way to not
 * have one.
 *
 * <p><b>This is a market, not eight independent dice.</b> Every quote moves with one shared market
 * factor that each listing feels through its own beta, plus its own idiosyncratic noise. So there
 * are rally days when everything is up and ugly days when nothing is, the expensive listings drift
 * and the cheap ones swing, and one bug in a slow listing is not hidden by another being quiet.
 *
 * <p><b>Nothing here ever speaks on a clock.</b> No announcements, no alerts, no "EMRLD is up
 * 4%!" - the price of a share is something a player goes and looks at, and the only chat this
 * class produces is the receipt for a trade the player pressed.
 */
public final class MarketManager {
   /** A quote every five real minutes. Everything in the market is expressed in these. */
   public static final long PERIOD_SECONDS = 300L;
   /** Brokerage, in basis points, charged on each side of a trade (100 = 1%). */
   public static final int FEE_BPS = 100;
   /** Most shares one click will ever move - a click, not a day's work. */
   public static final long MAX_SHARES_PER_TRADE = 10_000L;
   /** The state file, and the key the world's salt is kept under. */
   private static final String FILE = "market.json";

   /**
    * A listing's tier: the share of the benchmark a share of it is aimed at, and the band that
    * target is allowed to swing in.
    *
    * <p>The board used to be eleven prices with nothing but their own base to sit on, which works
    * until the server gets rich: a $900 share is a real position for a player with $10,000 and a
    * rounding error for one with $10,000,000, and a market that does not notice either fact stops
    * being a market. The fix is not a fixed band - a fixed band goes stale on exactly the servers
    * that need it most - and it is not the richest wallet either, because one jackpot is not an
    * economy. It is an AFFORDABILITY TARGET: a share of this shelf should cost about this much of
    * what the server's players actually hold. Everything else follows from that one number.
    *
    * <p>So the shelf carries a target rather than two constants, and its floor and ceiling are
    * that target times {@link #BAND_LO} and {@link #BAND_HI}. At a $250,000 benchmark - this
    * economy's working definition of an established server - a penny share is aimed at $1,250 and
    * an elite one at $62,500; double everyone's wallet and both numbers double with it.
    *
    * @param label       the shelf's name, as the window prints it
    * @param affordable  the share of the benchmark a share of this shelf is aimed at
    */
   public enum Tier {
      PENNY("Penny", 0.005),
      STANDARD("Standard", 0.02),
      PREMIUM("Premium", 0.10),
      ELITE("Elite", 0.25);

      public final String label;
      /** The share of the benchmark this shelf is aimed at - 0.02 is "a share for 2% of it". */
      public final double affordable;

      Tier(String label, double affordable) {
         this.label = label;
         this.affordable = affordable;
      }

      /**
       * The price a share of this shelf is aimed at, at one level.
       *
       * <p>The benchmark the target is taken from is {@code REFERENCE_BENCHMARK x level} rather
       * than a live wealth reading: the level already IS the measurement, so reading the wallets
       * again here would price the same share two different ways in the same window.
       */
      public double targetPrice(double level) {
         return this.affordable * (double)REFERENCE_BENCHMARK * level;
      }

      /** The lowest a share of this shelf may ever be quoted, at one level. */
      public double floorPrice(double level) {
         return targetPrice(level) * BAND_LO;
      }

      /** The highest a share of this shelf may ever be quoted, at one level. */
      public double ceilingPrice(double level) {
         return targetPrice(level) * BAND_HI;
      }

      /** The target as the window reads it: "0.5% of the benchmark". */
      public String affordableLabel() {
         double pct = this.affordable * 100.0;
         String text = pct < 1.0
            ? String.format(java.util.Locale.ROOT, "%.1f", pct)
            : String.format(java.util.Locale.ROOT, "%.0f", pct);
         return text + "% of the benchmark";
      }
   }

   /**
    * One listing.
    *
    * @param id     ticker, also the storage key - short, uppercase, never renamed
    * @param name   what the tile says
    * @param icon   the item the tile is drawn with
    * @param tier   which shelf it sits on - its band and its affordability target
    * @param base   the price the listing orbits at market scale 1, in whole dollars
    * @param beta   how much of the market's mood this listing carries (1.0 = average)
    * @param swing  how far it wanders on its own, as a log-scale amplitude
    * @param blurb  one line of flavour for the tile
    */
   public record Stock(String id, String name, Item icon, Tier tier, long base, double beta, double swing, String blurb) {

      /** How violent this listing is, as a word - the tile's risk indicator, in one place. */
      public String risk() {
         double wild = swing * beta;
         if (wild >= 0.45) {
            return "extreme";
         }
         if (wild >= 0.25) {
            return "high";
         }
         return wild >= 0.12 ? "medium" : "low";
      }
   }

   /**
    * The board: eleven listings across four shelves.
    *
    * <p>Deliberately a spread, per shelf as well as per beta. Two penny stocks a player with a few
    * hundred dollars can actually trade, four standard ones that are the body of the market, three
    * premium listings for the middle of a server's life, and two elite ones for when there is
    * serious money around.
    *
    * <p>Each base is set so the listing sits inside its shelf's band with room on both sides -
    * spread down the shelf from the cheapest to the dearest, so the four shelves are four rungs of
    * one ladder rather than four separate boards. A base is never at a rail, because a listing
    * pinned to its floor has stopped being a market and become a constant.
    */
   public static final List<Stock> STOCKS = List.of(
      new Stock("BLAZE", "Blaze Rod Energy", Items.BLAZE_ROD, Tier.PENNY, 1_150L, 1.6, 0.32, "Cheap, loud, and never flat twice in an hour."),
      new Stock("CRROT", "Gilded Carrot Farms", Items.GOLDEN_CARROT, Tier.PENNY, 1_450L, 1.5, 0.30, "A farming fad with a loyal following."),
      new Stock("QRTZ", "Quartz Optics", Items.QUARTZ, Tier.STANDARD, 3_600L, 1.1, 0.22, "Nether quartz for the builders' boom."),
      new Stock("RDSTN", "Redstone Foundry", Items.REDSTONE, Tier.STANDARD, 5_000L, 1.2, 0.18, "Machines, rails and every farm that hums."),
      new Stock("EMRLD", "Emerald Exchange", Items.EMERALD, Tier.STANDARD, 6_600L, 0.9, 0.10, "The old money: villagers, trade, steady."),
      new Stock("PRL", "Ender Pearl Freight", Items.ENDER_PEARL, Tier.STANDARD, 8_600L, 1.3, 0.20, "Movement, logistics and the odd mis-ship."),
      new Stock("DMND", "Diamond Consortium", Items.DIAMOND, Tier.PREMIUM, 19_000L, 0.8, 0.09, "Deep, boring, and everyone's hedge."),
      new Stock("DPCORE", "Deepcore Mining", Items.DEEPSLATE_DIAMOND_ORE, Tier.PREMIUM, 27_000L, 1.0, 0.14, "Everything the deep expeditions bring back, dug at scale."),
      new Stock("DBRS", "Ancient Debris Ltd.", Items.ANCIENT_DEBRIS, Tier.PREMIUM, 38_000L, 0.6, 0.16, "Scarce, nether-deep and slow to move."),
      new Stock("ABYSS", "Abyssal Shipping", Items.NAUTILUS_SHELL, Tier.ELITE, 80_000L, 1.1, 0.18, "Hauling sunken temple cargo no one else will touch."),
      new Stock("ENDER", "Ender Industries", Items.ENDER_EYE, Tier.ELITE, 120_000L, 0.9, 0.12, "Pearls, portals and the only truly quiet logistics in the game.")
   );

   public static Stock byId(String id) {
      for (Stock s : STOCKS) {
         if (s.id().equals(id)) {
            return s;
         }
      }
      return null;
   }

   // --- the price, as a function of the clock -------------------------------------------------

   /** Octave periods, in five-minute ticks: two hours, a day, a week, six weeks. */
   private static final long[] PERIODS = new long[]{12L, 288L, 2016L, 12096L};
   /** How much of the walk each octave carries, fast to slow. Sums to 1. */
   private static final double[] AMPS = new double[]{0.34, 0.28, 0.22, 0.16};
   /** The shared market factor's weight - this is what makes every listing move together. */
   private static final double MARKET_SCALE = 0.16;
   /** The furthest a log price may stretch, so a share cannot run away from its base. */
   private static final double MAX_LOG = 0.9;
   private static final long MIX = 0x9E3779B97F4A7C15L;

   // --- the market scale: what the server's wallets say the board is worth ---------------------
   //
   // The clock says what a share is worth; the wallets say what a share will sell for. A board
   // priced purely off the clock stops meaning anything as a server lives: the same $8,000 share is
   // a real position on a young server and pocket change on a late one, and neither fact is visible
   // in the clock. So the whole board is carried by one measured number - how much money this
   // server's players actually hold - and everything else is unchanged. The clock still sets the
   // shape (which listing is up, which is down, how violent each one is); the scale sets the level
   // the shape is drawn at, and so does the tier's own floor and ceiling.
   //
   // Four rules keep that from being a lever somebody can yank:
   //   * the benchmark is a PERCENTILE, not a peak - one player who finds a dupe or wins a jackpot
   //     cannot move the board, because the 90th percentile does not care about the top few wallets;
   //   * recalibration is GRADUAL - a quarter of the gap per update, and never more than a fifth of
   //     the level in one update, so a fortune earned this morning is priced in over an afternoon;
   //   * the scale is CLAMPED at both ends, so neither a server of paupers nor one of billionaires
   //     can price the board into nonsense;
   //   * and the cheapest shelf is protected - a penny share is also capped against the MEDIAN
   //     wallet, so a new player always has something on the board they can buy.

   /** How often the board's level is recalculated, in real seconds. */
   public static final long SCALE_PERIOD_SECONDS = 900L;
   /** How much of the gap to the target each recalculation closes. */
   public static final double SCALE_STEP = 0.25;
   /** The most the level itself may move in one recalculation, as a fraction of its own size. */
   public static final double SCALE_MAX_STEP = 0.20;
   /** The clamps. Below the floor the board is meaningless, above the ceiling it is unreadable. */
   public static final double SCALE_MIN = 0.15;
   public static final double SCALE_MAX = 25.0;
   /**
    * How far a shelf's quote may wander from its affordability target, as a multiplier.
    *
    * <p>Log-symmetrical about the target - the floor is the ceiling's own reciprocal - because a
    * share is worth a ratio, not a difference: halving and doubling a price are the same sized
    * event and the band should say so. The span is deliberately wider than the clock's own
    * {@link #MAX_LOG} walk on a middling listing and narrower than it on a wild one, so a quiet
    * listing never touches a rail and a penny stock can.
    */
   public static final double BAND_LO = 0.40;
   public static final double BAND_HI = 2.50;
   /**
    * The 90th-percentile wallet at which the board trades at exactly its designed prices.
    *
    * <p>A quarter of a million is "an established server" in this economy's own units: roughly
    * what a player who has done some real work carries. So the early-server case is matched by the
    * arithmetic (a $50,000 benchmark prints a standard listing around $2,000) rather than by a
    * magic first value: scale 0.2, and 0.2 x 8,000 is 1,600.
    */
   public static final long REFERENCE_BENCHMARK = 250_000L;
   /** A penny share is also capped at this share of the median wallet - new-player protection. */
   public static final double PENNY_MEDIAN_CAP = 0.01;
   /** And never priced below this, whatever the wallets say. */
   public static final long PENNY_FLOOR_PRICE = 50L;
   /** How long a wealth reading is reused, in seconds - a quote must not move between two clicks. */
   private static final long WEALTH_CACHE_SECONDS = 20L;

   /**
    * The server's wealth distribution, and what it says the board's level should be.
    *
    * <p>p10, p50 and p90 are reported because they are the shape of the server - the spread is what
    * tells a player whether this board is for them - and the level is built from p90, because the
    * top of the distribution is where an expensive listing has to be affordable and the peak is not
    * a number any market should be priced off. The median is what protects the cheap shelf.
    *
    * @param players how many wallets were counted
    * @param p10     the tenth-percentile wallet
    * @param p50     the median wallet
    * @param p90     the ninetieth-percentile wallet - the benchmark the level is built on
    * @param peak    the richest wallet, for the window to print
    * @param floor   the poorest wallet that still holds something
    * @param mean    the average wallet
    * @param scale   the level the board is being carried at right now
    * @param target  the level the benchmark is asking for
    */
   public record Wealth(
      int players, long p10, long p50, long p90, long peak, long floor, long mean, double scale, double target
   ) {
   }

   /** The level the board is carried at, and when it was last recalculated (epoch seconds). */
   private static double level = 1.0;
   private static long levelAt = 0L;
   /** The cached wealth reading, and when it was taken, so a quote does not recompute it. */
   private static Wealth wealthCache;
   private static long wealthAt = 0L;
   /** Test seam: a pinned level, so a check can price a trade against a known board. */
   private static Double pinnedLevel;

   /** Pins the board's level, or unpins it with null. */
   public static void pinLevelForTest(Double value) {
      pinnedLevel = value;
      wealthCache = null;
      wealthAt = 0L;
   }

   /** Forgets the cached wealth reading and the recalibration clock. */
   public static void resetScaleForTest() {
      wealthCache = null;
      wealthAt = 0L;
   }

   private static double clamp(double lo, double hi, double v) {
      return Math.max(lo, Math.min(hi, v));
   }

   /**
    * Reads the server's wallets, as a distribution rather than as a pair of extremes.
    *
    * <p>On a live server the reading is of the players who are actually online - a board is priced
    * for the people standing in front of it - and it falls back to every wallet the economy has ever
    * recorded when nobody is playing, so the board still has a level while it waits.
    */
   public static Wealth wealth() {
      long now = Instant.now().getEpochSecond();
      if (wealthCache != null && Math.abs(now - wealthAt) < WEALTH_CACHE_SECONDS) {
         return wealthCache;
      }
      List<Long> wallets = new ArrayList<>();
      MinecraftServer live = serverRef;
      if (live != null && !live.getPlayerList().getPlayers().isEmpty()) {
         for (ServerPlayer p : live.getPlayerList().getPlayers()) {
            wallets.add(Math.max(0L, EconomyManager.balance(p.getUUID())));
         }
      } else {
         for (long balance : EconomyManager.allBalances().values()) {
            wallets.add(Math.max(0L, balance));
         }
      }
      wallets.sort(null);
      int players = wallets.size();
      long peak = 0L;
      long floor = Long.MAX_VALUE;
      long total = 0L;
      for (long w : wallets) {
         peak = Math.max(peak, w);
         if (w > 0L) {
            floor = Math.min(floor, w);
         }
         // A saturated total would flip negative: clamp per add rather than once at the end.
         total = total > Long.MAX_VALUE - w ? Long.MAX_VALUE / 2L : total + w;
      }
      if (floor == Long.MAX_VALUE) {
         floor = 0L;
      }
      long p10 = percentile(wallets, 0.10);
      long p50 = percentile(wallets, 0.50);
      long p90 = percentile(wallets, 0.90);
      long mean = players == 0 ? 0L : total / (long)players;
      double target = targetScale(p90);
      wealthCache = new Wealth(players, p10, p50, p90, peak, floor, mean, pendingLevel(), target);
      wealthAt = now;
      return wealthCache;
   }

   /** The nearest-rank percentile of a sorted wallet list. Empty answers zero. */
   private static long percentile(List<Long> sorted, double fraction) {
      if (sorted.isEmpty()) {
         return 0L;
      }
      int index = (int)Math.round(fraction * (double)(sorted.size() - 1));
      return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
   }

   /**
    * The level a benchmark is asking for.
    *
    * <p>{@code S = clamp(p90 / REFERENCE_BENCHMARK, SCALE_MIN, SCALE_MAX)} - the formula the whole
    * system is: one measured number, divided by what an established server looks like, clamped so
    * neither end of the economy can price the board into nonsense. A benchmark of zero (a server
    * with no wallets at all) asks for nothing and the board keeps the level it has.
    */
   public static double targetScale(long p90) {
      if (p90 <= 0L) {
         return pendingLevel();
      }
      return clamp(SCALE_MIN, SCALE_MAX, (double)p90 / (double)REFERENCE_BENCHMARK);
   }

   /** The level a price is actually computed at: the pinned test value, or the live one. */
   private static double pendingLevel() {
      return pinnedLevel != null ? pinnedLevel : level;
   }

   /**
    * The board's level, recalculating it if the recalibration clock is due.
    *
    * <p>Lazy on purpose: there is no ticking job to forget to register and no catch-up loop to get
    * wrong. The clock is read when somebody looks at a price, and a recalculation happens at most
    * once per {@link #SCALE_PERIOD_SECONDS}. The step is a quarter of the gap, capped at a fifth of
    * the level, which is what makes a fortune earned this afternoon get priced in gradually instead
    * of repricing the whole board between two clicks.
    */
   public static double level() {
      if (pinnedLevel != null) {
         return pinnedLevel;
      }
      recalibrateIfDue();
      return level;
   }

   /** Steps the level toward its target if the recalibration clock has come round. */
   private static void recalibrateIfDue() {
      long now = Instant.now().getEpochSecond();
      if (levelAt == 0L) {
         // First look at a fresh world: take the target in one step, because there is no history to
         // be gradual about - graduating from nothing is how a new install ends up with a board
         // nobody can buy for the first two hours.
         levelAt = now;
         level = clamp(SCALE_MIN, SCALE_MAX, targetScale(wealth().p90()));
         dirty = true;
         return;
      }
      if (now - levelAt < SCALE_PERIOD_SECONDS) {
         return;
      }
      levelAt = now;
      double target = clamp(SCALE_MIN, SCALE_MAX, targetScale(wealth().p90()));
      level = clamp(SCALE_MIN, SCALE_MAX, level + recalibrationStep(level, target));
      dirty = true;
   }

   /**
    * One recalculation's move, from a level to a target.
    *
    * <p>A quarter of the gap, held inside the per-update cap - and the cap is the whole reason this
    * is a named function rather than two lines inside the recalibration. It used to be a fifth of
    * <b>the level</b> in both directions, which reads as a symmetric speed limit and is not one:
    * the cap shrinks as the level falls, so a board that has dropped to the floor can only claw its
    * way back at a fifth of that floor, while the drop that put it there ran at a fifth of a much
    * bigger number. Falling was fast, climbing was glacial, and what a player sees of that is a
    * board where "everything only ever goes down". It is not the clock - the walk is symmetric by
    * construction - and it is not the shelves, which most listings never even touch. It is the
    * ratchet underneath them.
    *
    * <p>So the cap is a fifth of the level on the way <b>down</b>, where the damping is doing real
    * work - a windfall earned this morning must not reprice the board between two clicks - and a
    * fifth of whichever of the level and the target is larger on the way <b>up</b>. Recovery is
    * then at least as quick as the fall that made it necessary, and never slower: a board at the
    * floor with a rich server standing around it comes back in a handful of recalibrations instead
    * of a day of them.
    */
   public static double recalibrationStep(double level, double target) {
      double gap = target - level;
      double step = gap * SCALE_STEP;
      double cap = Math.abs(level) * SCALE_MAX_STEP;
      if (step > 0.0) {
         cap = Math.max(Math.abs(level), Math.abs(target)) * SCALE_MAX_STEP;
      }
      return clamp(-cap, cap, step);
   }

   /** Seconds until the board is recalibrated again - the window's own countdown. */
   public static long secondsToNextScale() {
      if (levelAt == 0L) {
         return 0L;
      }
      long now = Instant.now().getEpochSecond();
      return Math.max(0L, SCALE_PERIOD_SECONDS - (now - levelAt));
   }

   /** Test seam: pretend the last recalculation just happened, and set the level it landed on. */
   public static void levelForTest(double value, long atEpochSeconds) {
      level = value;
      levelAt = atEpochSeconds;
      wealthCache = null;
      wealthAt = 0L;
   }

   private static long salt = 0L;
   private static boolean dirty = false;
   private static Path dataFile;
   /** The live server, while there is one - what the wealth reading asks for online players. */
   private static MinecraftServer serverRef;

   /** Holdings: player -> ticker -> position. */
   private static final Map<UUID, Map<String, Position>> positions = new HashMap<>();

   /** Shares held and what they cost to acquire (fees included), for an honest profit figure. */
   public record Position(long shares, long basis) {
   }

   private MarketManager() {
   }

   // --- time ---------------------------------------------------------------------------------

   /** The five-minute grid index that holds {@code epochSeconds}. */
   public static long tickAt(long epochSeconds) {
      return Math.floorDiv(epochSeconds, PERIOD_SECONDS);
   }

   public static long currentTick() {
      return pinnedTick != null ? pinnedTick : tickAt(Instant.now().getEpochSecond());
   }

   /**
    * Test seam: pin the quote grid, so a check can price a trade and settle it against the same
    * tick. A check that reads the clock three times is a check that fails once every three hundred
    * runs, when the grid happens to roll over in the middle of it.
    */
   private static Long pinnedTick = null;

   public static void pinTickForTest(Long tick) {
      pinnedTick = tick;
   }

   /** Real seconds until the next quote. Drives the countdown on the tiles. */
   public static long secondsToNextTick() {
      long now = Instant.now().getEpochSecond();
      return PERIOD_SECONDS - Math.floorMod(now, PERIOD_SECONDS);
   }

   /** Start of a tick index, in epoch seconds - what a chart label needs. */
   public static long startOf(long tick) {
      return tick * PERIOD_SECONDS;
   }

   // --- the series ---------------------------------------------------------------------------

   /** A smooth, bounded, repeatable value in [-1, 1] that only depends on the tick. */
   private static double noise(long seed, long tick, long period) {
      long cell = Math.floorDiv(tick, period);
      double t = (double)Math.floorMod(tick, period) / (double)period;
      double a = hash01(seed, cell);
      double b = hash01(seed, cell + 1L);
      double s = t * t * (3.0 - 2.0 * t);
      return (a + (b - a) * s) * 2.0 - 1.0;
   }

   /** The four octaves of one seed: the walk, in [-1, 1]. */
   private static double octaves(long seed, long tick) {
      double out = 0.0;
      for (int k = 0; k < PERIODS.length; k++) {
         out += AMPS[k] * noise(seed + k * MIX, tick, PERIODS[k]);
      }
      return out;
   }

   private static double hash01(long seed, long cell) {
      long z = seed ^ cell * MIX;
      z = (z ^ z >>> 30) * 0xBF58476D1CE4E5B9L;
      z = (z ^ z >>> 27) * 0x94D049BB133111EBL;
      z ^= z >>> 31;
      return (double)(z >>> 11) / (double)(1L << 53);
   }

   private static long stockSeed(Stock s) {
      return salt ^ ((long)s.id().hashCode() * MIX);
   }

   /**
    * The price of one share at one tick.
    *
    * <p>The market factor is shared and the rest is not, so this is a market with a mood: on a
    * good day the betas add up and everything is green, and DBRS drifts while BLAZE swings.
    */
   public static double priceOf(Stock s, long tick) {
      double common = octaves(salt, tick) * MARKET_SCALE * s.beta();
      double own = octaves(stockSeed(s), tick) * s.swing();
      double log = Math.max(-MAX_LOG, Math.min(MAX_LOG, common + own));
      return Math.max(1.0, (double)s.base() * Math.exp(log));
   }

   public static double priceOf(Stock s) {
      return priceOf(s, currentTick());
   }

   /** The mean of the listings' bases: the price of "an average share" at market scale 1. */
   public static double meanBasePrice() {
      double sum = 0.0;
      for (Stock s : STOCKS) {
         sum += (double)s.base();
      }
      return sum / (double)STOCKS.size();
   }

   // --- the retired demand anchor ------------------------------------------------
   //
   // The old board was eased from its clock price toward an anchor built from the AVERAGE wallet,
   // and only ever downward. It was a real improvement on a fixed board, and it had two faults this
   // window's owner put plainly: one outlier at the top of the distribution dragged the mean up and
   // so quietly repriced the whole market, and "only ever downward" meant the board drifted cheap
   // and stayed there as a server got richer, which is the opposite of irrelevance but not better.
   // The percentile scale above replaces it outright. What remains here is the shape of the old
   // arithmetic so the reasoning is not lost.

   /**
    * Cuts a raw price down to what a shelf will actually allow - the shelf's band, plus the cheap
    * shelf's promise to a new player.
    */
   private static double shelved(Stock s, double lvl, double raw) {
      double q = clamp(s.tier().floorPrice(lvl), s.tier().ceilingPrice(lvl), raw);
      if (s.tier() == Tier.PENNY) {
         long median = wealth().p50();
         double cap = median > 0L ? (double)median * PENNY_MEDIAN_CAP : Double.MAX_VALUE;
         q = Math.max((double)PENNY_FLOOR_PRICE, Math.min(q, cap));
      }
      return q;
   }

   /**
    * The price a trade actually settles at: the clock's price, carried to the server's level, moved
    * by the order flow standing on it, and then held inside its shelf.
    *
    * <p>{@link #priceOf} is the market's opinion of value and stays a pure function of the clock, so
    * a chart of last week is still the chart last week had. This is that opinion turned into money
    * on THIS server: multiplied by the measured level, tilted by whatever this listing's own buyers
    * and sellers have done to it, and finally clamped into its shelf - a penny listing cannot be
    * quoted at a premium price however hot its clock runs, and an elite one cannot fall into the
    * penny shelf however cold it goes.
    *
    * <p>The shelf band is built from the tier's affordability TARGET rather than from two fixed
    * numbers, which is what keeps the board relevant as a server lives: the band is a share of the
    * benchmark, so it rises with the server's wallets by construction.
    *
    * <p>The cheap shelf carries one extra rule, and it is the one that keeps a new player in the
    * market: a penny share is also capped at {@link #PENNY_MEDIAN_CAP} of the MEDIAN wallet, so on a
    * server where the middle player is rich the two cheap listings still cost pocket money.
    */
   public static double quoteOf(Stock s, long tick) {
      double lvl = level();
      double clock = priceOf(s, tick) * lvl;
      return shelved(s, lvl, clock * Math.exp(flowImpact(s, tick)));
   }

   // --- order flow: what the players themselves do to a price ----------------------------------
   //
   // The clock says what a share is worth and the wallets say what it sells for; neither notices
   // the thing a player does in the window. So a trade leaves a mark. Buying pushes the quote up
   // and selling pushes it down, by an amount that depends on how big the order is AGAINST THE
   // LISTING rather than on how many shares it is - a thousand shares of a penny listing and a
   // thousand of an elite one are different events - and the mark fades over the next few quotes, so
   // an afternoon of buying is a trend and not a new permanent price.
   //
   // Two properties keep this honest. The chart is still drawn from the clock, because the chart is
   // a record and a record of what players did to each other is not a market history; and the mark
   // is capped and kept in the state file, so a whale can lean on a price for a while and cannot
   // carry it away.

   /** How large an order has to be, in shares, before it moves the quote at all. */
   public static final double FLOW_DEPTH = 2_500.0;
   /** How hard an order of {@link #FLOW_DEPTH} shares pushes the quote, in log terms. */
   public static final double FLOW_STRENGTH = 0.25;
   /** The furthest order flow alone may carry a quote off the clock's price, in log terms. */
   public static final double FLOW_CAP = 0.35;
   /** What is left of the order flow after one five-minute quote has gone by. */
   public static final double FLOW_DECAY = 0.75;

   /** Order flow: ticker -> log tilt, and ticker -> the quote it was last noted at. */
   private static final Map<String, Double> flow = new HashMap<>();
   private static final Map<String, Long> flowAt = new HashMap<>();

   private static double flowImpact(Stock s, long tick) {
      Double noted = flow.get(s.id());
      if (noted == null || noted == 0.0) {
         return 0.0;
      }
      Long at = flowAt.get(s.id());
      long since = at == null ? 0L : Math.max(0L, tick - at);
      return clamp(-FLOW_CAP, FLOW_CAP, noted * Math.pow(FLOW_DECAY, (double)since));
   }

   /**
    * The order flow standing on one listing right now, as a fraction - the tile's "the till is
    * above the clock because people have been buying" line.
    */
   public static double flowAdjustment(Stock s, long tick) {
      return Math.exp(flowImpact(s, tick)) - 1.0;
   }

   /** Writes a trade's mark onto a listing. Positive shares are a buy. */
   private static void noteFlow(Stock s, long tick, long shares, int sign) {
      if (shares <= 0L) {
         return;
      }
      double now = flowImpact(s, tick);
      double push = FLOW_STRENGTH * Math.log1p((double)shares / FLOW_DEPTH) * (double)sign;
      double next = clamp(-FLOW_CAP, FLOW_CAP, now + push);
      if (Math.abs(next) < 1.0E-6) {
         flow.remove(s.id());
         flowAt.remove(s.id());
         return;
      }
      flow.put(s.id(), next);
      flowAt.put(s.id(), tick);
      markDirty();
   }

   /** Test seam: forget every mark the players have left on the board. */
   public static void clearFlowForTest() {
      flow.clear();
      flowAt.clear();
   }

   /**
    * How far the SHELF moves the quote off the clock's price, as a fraction.
    *
    * <p>Reported apart from {@link #flowAdjustment} so a window can say "the level is carrying this
    * 12% above the clock" and "buyers have added another 8%" as two separate facts rather than one
    * conflated one.
    */
   public static double levelAdjustment(Stock s, long tick) {
      double lvl = level();
      double clock = priceOf(s, tick) * lvl;
      if (clock <= 0.0) {
         return 0.0;
      }
      return 1.0 - shelved(s, lvl, clock) / clock;
   }

   public static double quoteOf(Stock s) {
      return quoteOf(s, currentTick());
   }

   /** The whole board's level, 1000 = every listing exactly at its base. */
   public static double indexAt(long tick) {
      double sum = 0.0;
      for (Stock s : STOCKS) {
         sum += priceOf(s, tick) / (double)s.base();
      }
      return 1000.0 * (sum / (double)STOCKS.size());
   }

   /**
    * Prices for a chart: {@code points} samples ending at {@code endTick}, each one {@code step}
    * ticks after the last. Oldest first, so it reads left to right.
    */
   public static double[] series(Stock s, long endTick, int points, int step) {
      double[] out = new double[Math.max(1, points)];
      int stride = Math.max(1, step);
      long first = endTick - (long)stride * (long)(out.length - 1);
      for (int i = 0; i < out.length; i++) {
         out[i] = priceOf(s, first + (long)stride * i);
      }
      return out;
   }

   /** A board-wide series, for the index chart. */
   public static double[] indexSeries(long endTick, int points, int step) {
      double[] out = new double[Math.max(1, points)];
      int stride = Math.max(1, step);
      long first = endTick - (long)stride * (long)(out.length - 1);
      for (int i = 0; i < out.length; i++) {
         out[i] = indexAt(first + (long)stride * i);
      }
      return out;
   }

   /** How much a share has moved over {@code ticks} five-minute periods, as a fraction. */
   public static double change(Stock s, long tick, int ticks) {
      double then = priceOf(s, tick - ticks);
      double now = priceOf(s, tick);
      return then <= 0.0 ? 0.0 : now / then - 1.0;
   }

   // --- trades -------------------------------------------------------------------------------

   /** The answer to a buy or sell: what happened, or why it didn't. */
   public record Receipt(boolean ok, String reason, long shares, long cash) {
      public static Receipt no(String reason) {
         return new Receipt(false, reason, 0L, 0L);
      }

      public static Receipt yes(long shares, long cash) {
         return new Receipt(true, "", shares, cash);
      }
   }

   /** Buying one share costs the quote plus the brokerage - rounded up, never down. */
   public static long costFor(Stock s, long tick, long shares) {
      double gross = quoteOf(s, tick) * (double)shares;
      return (long)Math.ceil(gross * (1.0 + FEE_BPS / 10_000.0));
   }

   /** Selling one pays the quote less the brokerage - rounded down, never up. */
   public static long proceedsFor(Stock s, long tick, long shares) {
      double gross = quoteOf(s, tick) * (double)shares;
      return (long)Math.floor(gross * (1.0 - FEE_BPS / 10_000.0));
   }

   /** How much one share has moved in MONEY over {@code ticks} quotes - the tile's second line. */
   public static double changeCash(Stock s, long tick, int ticks) {
      return priceOf(s, tick) - priceOf(s, tick - ticks);
   }

   /** Wallet plus vault: what a player could spend on a trade without leaving the window. */
   public static long spendable(ServerPlayer player) {
      return Math.max(0L, EconomyManager.balance(player.getUUID())) + Math.max(0L, BankManager.accountOf(player).cash());
   }

   /** The most shares of this listing the player could buy right now. */
   public static long maxAffordable(ServerPlayer player, Stock s, long tick) {
      double unit = quoteOf(s, tick) * (1.0 + FEE_BPS / 10_000.0);
      if (unit <= 0.0) {
         return 0L;
      }
      long n = (long)((double)spendable(player) / unit);
      return Math.max(0L, Math.min(MAX_SHARES_PER_TRADE, n));
   }

   /**
    * Takes money for a purchase: the wallet first, then the vault.
    *
    * <p>Banked cash is where the money actually is - a player with $2M in the vault should not have
    * to go and withdraw before every trade - so the vault is an accepted funding source. The
    * affordability test happens once, up front, and only then is anything taken, so a trade can
    * never leave half the money spent.
    */
   private static boolean spend(ServerPlayer player, long amount) {
      if (amount <= 0L) {
         return true;
      }
      long wallet = Math.max(0L, EconomyManager.balance(player.getUUID()));
      long vault = Math.max(0L, BankManager.accountOf(player).cash());
      if (wallet + vault < amount) {
         return false;
      }
      long fromVault = Math.min(vault, amount);
      if (fromVault > 0L && BankManager.withdrawMoney(player, fromVault) < fromVault) {
         return false;
      }
      long fromWallet = amount - fromVault;
      if (fromWallet > 0L && !EconomyManager.takeCash(player.getUUID(), fromWallet)) {
         // Cannot happen while the server thread is the only writer, and if it ever did the
         // vault half would simply be handed back rather than kept.
         EconomyManager.setBalance(player.getUUID(), EconomyManager.balance(player.getUUID()) + fromVault);
         return false;
      }
      return true;
   }

   /** Buys {@code shares} at the current quote, at market. */
   public static Receipt buy(ServerPlayer player, Stock s, long shares) {
      if (shares <= 0L) {
         return Receipt.no("Choose how many shares first.");
      }
      long qty = Math.min(MAX_SHARES_PER_TRADE, shares);
      long tick = currentTick();
      long cost = costFor(s, tick, qty);
      if (spendable(player) < cost) {
         return Receipt.no("That costs $" + cost + " and you have $" + spendable(player) + ".");
      }
      if (!spend(player, cost)) {
         return Receipt.no("The order could not be funded.");
      }
      Position held = position(player.getUUID(), s);
      long held8 = held.shares() + qty;
      long basis = held.basis() + cost;
      put(player.getUUID(), s, new Position(held8, basis));
      // The mark goes on AFTER the cost is taken: a trade pays the price it saw, and moves the
      // one the next player sees.
      noteFlow(s, tick, qty, 1);
      BankManager.record(player.getUUID(), "buy", cost, qty + " " + s.id() + " @ $" + round2(quoteOf(s, tick)));
      markDirty();
      return Receipt.yes(qty, cost);
   }

   /** Sells {@code shares} at the current quote, at market. */
   public static Receipt sell(ServerPlayer player, Stock s, long shares) {
      if (shares <= 0L) {
         return Receipt.no("Choose how many shares first.");
      }
      Position held = position(player.getUUID(), s);
      if (held.shares() <= 0L) {
         return Receipt.no("You do not own any " + s.id() + ".");
      }
      long qty = Math.min(Math.min(MAX_SHARES_PER_TRADE, shares), held.shares());
      long tick = currentTick();
      long proceeds = proceedsFor(s, tick, qty);
      // Average cost: the basis leaves with the shares that carried it, so the profit figure on
      // what is left stays honest after a partial sale.
      long basisOut = held.shares() <= qty ? held.basis() : (long)((double)held.basis() * ((double)qty / (double)held.shares()));
      put(player.getUUID(), s, new Position(held.shares() - qty, Math.max(0L, held.basis() - basisOut)));
      EconomyManager.addCash(player.getUUID(), proceeds);
      noteFlow(s, tick, qty, -1);
      BankManager.record(player.getUUID(), "sell", proceeds, qty + " " + s.id() + " @ $" + round2(quoteOf(s, tick)));
      markDirty();
      return Receipt.yes(qty, proceeds);
   }

   // --- holdings -----------------------------------------------------------------------------

   public static Position position(UUID id, Stock s) {
      Map<String, Position> mine = positions.get(id);
      if (mine == null) {
         return new Position(0L, 0L);
      }
      Position p = mine.get(s.id());
      return p == null ? new Position(0L, 0L) : p;
   }

   public static long shares(UUID id, Stock s) {
      return position(id, s).shares();
   }

   private static void put(UUID id, Stock s, Position p) {
      Map<String, Position> mine = positions.computeIfAbsent(id, k -> new LinkedHashMap<>());
      if (p.shares() <= 0L && p.basis() <= 0L) {
         mine.remove(s.id());
      } else {
         mine.put(s.id(), p);
      }
      if (mine.isEmpty()) {
         positions.remove(id);
      }
   }

   /** Everything held, ticker -> position, in board order. */
   public static Map<String, Position> holdings(UUID id) {
      Map<String, Position> mine = positions.get(id);
      Map<String, Position> out = new LinkedHashMap<>();
      if (mine == null) {
         return out;
      }
      for (Stock s : STOCKS) {
         Position p = mine.get(s.id());
         if (p != null && p.shares() > 0L) {
            out.put(s.id(), p);
         }
      }
      return out;
   }

   /** Every player holding at least one share - the Exchange's book of customers,
    *  for anything that wants to rank or report on the traders themselves. */
   public static java.util.Set<UUID> traders() {
      return new java.util.LinkedHashSet<>(positions.keySet());
   }

   /** What the holdings are worth at {@code tick}, at the price they would actually fetch. */
   public static long value(UUID id, long tick) {
      long total = 0L;
      for (Entry<String, Position> e : holdings(id).entrySet()) {
         Stock s = byId(e.getKey());
         if (s != null) {
            total += Math.round(quoteOf(s, tick) * (double)e.getValue().shares());
         }
      }
      return total;
   }

   public static long value(UUID id) {
      return value(id, currentTick());
   }

   /** What the holdings cost. Value minus this is the unrealised profit. */
   public static long basis(UUID id) {
      long total = 0L;
      for (Position p : holdings(id).values()) {
         total += p.basis();
      }
      return total;
   }

   /** Test seam: wipe a player's book so a check can watch one fill from empty. */
   public static void clearHoldings(UUID id) {
      positions.remove(id);
   }

   /** The market scale, as the window prints it: a level and a one-line reason. */
   public static String scaleLine(Stock s) {
      return s.tier().label + " shelf §8· §7level §f" + String.format(java.util.Locale.ROOT, "%.2f", level())
         + "§8 (p90 benchmark)";
   }

   /**
    * The benchmark the whole board is aimed at: {@code p90} as the level reads it.
    *
    * <p>The level is the measurement, so the benchmark is read back off it rather than taken from
    * the wallets a second time - which is what makes the shelf table on the Market Scale tile agree
    * with the prices on the tiles beside it to the dollar.
    */
   public static double benchmark() {
      return (double)REFERENCE_BENCHMARK * level();
   }

   /**
    * The board's level at the live quote rather than at the clock - what the listings on the tiles
    * actually add up to, and the number the demand anchor is measured against.
    */
   public static double liveIndexAt(long tick) {
      double sum = 0.0;
      for (Stock s : STOCKS) {
         sum += quoteOf(s, tick) / (double)s.base();
      }
      return 1000.0 * (sum / (double)STOCKS.size());
   }

   // --- persistence --------------------------------------------------------------------------

   public static void load(MinecraftServer server) {
      positions.clear();
      serverRef = server;
      dataFile = EconomyManager.getDataDir(server).resolve(FILE);
      JsonObject root = JsonUtil.readOrCreate(dataFile, new JsonObject());
      // The board's level and the clock that moves it. Kept in the file rather than recomputed from
      // the wallets on boot: the whole point of gradual recalibration is that the level has a
      // history, and a market that jumps to its target every restart has no history at all.
      level = root.has("level") ? JsonUtil.jsonDouble(root, "level", 1.0) : 0.0;
      levelAt = JsonUtil.jsonLong(root, "levelAt", 0L);
      wealthCache = null;
      wealthAt = 0L;
      // The marks the players have left on individual listings. Kept, because a price somebody
      // pushed up is a fact about the market and a restart is not an apology.
      flow.clear();
      flowAt.clear();
      if (root.has("flow") && root.get("flow").isJsonObject()) {
         for (Entry<String, JsonElement> e : root.getAsJsonObject("flow").entrySet()) {
            try {
               if (!e.getValue().isJsonObject()) {
                  continue;
               }
               JsonObject one = e.getValue().getAsJsonObject();
               double v = JsonUtil.jsonDouble(one, "v", 0.0);
               long at = JsonUtil.jsonLong(one, "at", 0L);
               if (Math.abs(v) > 1.0E-6) {
                  flow.put(e.getKey(), clamp(-FLOW_CAP, FLOW_CAP, v));
                  flowAt.put(e.getKey(), at);
               }
            } catch (Exception ignored) {
            }
         }
      }
      // The salt is minted once per world and never rotates: rotating it would rewrite every
      // history a player has been watching.
      salt = JsonUtil.jsonLong(root, "salt", 0L);
      if (salt == 0L) {
         salt = java.util.concurrent.ThreadLocalRandom.current().nextLong();
         if (salt == 0L) {
            salt = 0x5DEECE66DL;
         }
         root.addProperty("salt", salt);
         JsonUtil.write(dataFile, root);
      }
      if (root.has("players") && root.get("players").isJsonObject()) {
         for (Entry<String, JsonElement> e : root.getAsJsonObject("players").entrySet()) {
            try {
               UUID id = UUID.fromString(e.getKey());
               JsonObject book = e.getValue().getAsJsonObject();
               Map<String, Position> mine = new LinkedHashMap<>();
               for (Stock s : STOCKS) {
                  if (!book.has(s.id()) || !book.get(s.id()).isJsonObject()) {
                     continue;
                  }
                  JsonObject pos = book.getAsJsonObject(s.id());
                  long shares = Math.max(0L, JsonUtil.jsonLong(pos, "shares", 0L));
                  long basis = Math.max(0L, JsonUtil.jsonLong(pos, "basis", 0L));
                  if (shares > 0L) {
                     mine.put(s.id(), new Position(shares, basis));
                  }
               }
               if (!mine.isEmpty()) {
                  positions.put(id, mine);
               }
            } catch (Exception ignored) {
            }
         }
      }
   }

   public static void save(MinecraftServer server) {
      if (dataFile == null) {
         dataFile = EconomyManager.getDataDir(server).resolve(FILE);
      }
      JsonObject root = new JsonObject();
      root.addProperty("salt", salt);
      root.addProperty("level", level);
      root.addProperty("levelAt", levelAt);
      JsonObject flows = new JsonObject();
      long now = currentTick();
      for (Stock s : STOCKS) {
         if (flow.get(s.id()) == null) {
            continue;
         }
         // Decay it to this quote and re-anchor it there, so the file holds the tilt as of now
         // rather than one that would go on fading while the server was off.
         double tilted = flowImpact(s, now);
         if (Math.abs(tilted) < 1.0E-6) {
            flow.remove(s.id());
            flowAt.remove(s.id());
            continue;
         }
         flow.put(s.id(), tilted);
         flowAt.put(s.id(), now);
         JsonObject one = new JsonObject();
         one.addProperty("v", tilted);
         one.addProperty("at", now);
         flows.add(s.id(), one);
      }
      root.add("flow", flows);
      JsonObject players = new JsonObject();
      for (Entry<UUID, Map<String, Position>> e : positions.entrySet()) {
         if (e.getValue().isEmpty()) {
            continue;
         }
         JsonObject book = new JsonObject();
         for (Entry<String, Position> p : e.getValue().entrySet()) {
            JsonObject one = new JsonObject();
            one.addProperty("shares", p.getValue().shares());
            one.addProperty("basis", p.getValue().basis());
            book.add(p.getKey(), one);
         }
         players.add(e.getKey().toString(), book);
      }
      root.add("players", players);
      JsonUtil.write(dataFile, root);
      dirty = false;
   }

   public static boolean isDirty() {
      return dirty;
   }

   public static void markDirty() {
      dirty = true;
   }

   /** Test seam: force the world's salt, so a check can prove the series depends on it. The world's
    *  own salt comes back from the file on the next {@link #load}. */
   public static void saltForTest(long value) {
      salt = value;
   }

   /** Two decimals, with a thousands separator - the look a quote should have. */
   public static String round2(double amount) {
      return String.format(java.util.Locale.ROOT, "%,.2f", amount);
   }
}
