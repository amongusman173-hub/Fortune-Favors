package com.fortuneandfavors.util;

import com.fortuneandfavors.FortuneFavorsMod;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/** Ultra-light per-tick profiler for the mod's own systems.
 *  <p>Safe.run routes every tick handler through here, so the whole mod's
 *  per-system cost is visible in /ff perf without touching any system code.
 *  Within one server tick the cost of repeated calls with the same name
 *  (per-player handlers) is summed, and the total is recorded once per tick -
 *  so averages are real per-tick costs, not per-invocation.
 *  <p>Zero allocation when disabled; one boxed Long merge per call when on.
 *  Also samples the server's own rolling MSPT so the report can show how much
 *  of the tick budget the mod is actually eating. */
public final class PerfMonitor {
   private static final int MAX_SYSTEMS = 256;
   /** How many ticks of samples the rolling window keeps per system. */
   private static final int WINDOW = 100;
   /** Ticks between MSPT samples (1s). */
   private static final int MSPT_INTERVAL = 20;

   private static final class Stats {
      long totalNanos;
      long samples;
      long maxNanos;
   }

   private static final Map<String, Stats> stats = new HashMap<>();
   private static final Map<String, double[]> window = new HashMap<>();
   private static final Map<String, Integer> windowIdx = new HashMap<>();
   private static final Map<String, Boolean> windowFull = new HashMap<>();
   /**
    * Nanos accumulated per system during the current tick, flushed by endTick().
    *
    * <p>Concurrent, because this map is not written only by the tick thread. The scheduled
    * data backup runs on a thread of its own and reaches this through {@code Safe.run}, so a
    * backup that starts while the tick thread is flushing its timings put an entry into a
    * {@code HashMap} mid-iteration - which is a {@code ConcurrentModificationException} in
    * the END_SERVER_TICK handler, and therefore a server crash whose cause is a backup that
    * happened to begin in the wrong tick. A slow tick is exactly when a backup is likely to
    * start, which is why this was reachable at all and why it was found by a self-test that
    * makes the server run two seconds behind on purpose.
    */
   private static final Map<String, Long> pending = new java.util.concurrent.ConcurrentHashMap<>();
   private static boolean enabled = true;
   /**
    * Particles the server was asked to send during the tick in progress.
    *
    * <p>Fed from the mixin on {@code ServerLevel.sendParticles}, which is the single door every
    * particle in the world goes through - the mod's own effects and vanilla's alike - so the
    * number does not depend on any system remembering to report itself. A {@code LongAdder}
    * rather than a plain field because that door is not only hit from the tick thread (a scripted
    * effect driven off a packet handler lands here too), and a particle count is not worth a lost
    * update race to save.
    */
   private static final java.util.concurrent.atomic.LongAdder particlesThisTick =
      new java.util.concurrent.atomic.LongAdder();
   private static long particlesLastTick = 0L;
   private static long particlesPeakTick = 0L;
   private static long particlesTotal = 0L;
   private static boolean msptAvailable = false;
   private static double mspt = 0.0D;
   private static double tps = 20.0D;
   private static long lastMsptSample = 0L;

   private PerfMonitor() {
   }

   /** Runs one handler, records how long it took (aggregated per tick), and
    *  keeps Safe.run's catch-everything guarantee for the instrumented path. */
   public static void run(String what, Runnable action) {
      long start = System.nanoTime();
      try {
         action.run();
      } catch (VirtualMachineError e) {
         throw e;
      } catch (Throwable t) {
         FortuneFavorsMod.LOGGER.error("Fortune & Favors: error during " + what + " - skipped to keep the server alive", t);
      } finally {
         pending.put(what, pending.getOrDefault(what, 0L) + (System.nanoTime() - start));
      }
   }

   /**
    * Counts one {@code sendParticles} call. Called by the {@code ServerLevel} mixin.
    *
    * <p>A no-op when the profiler is off, so the mixin costs one static field read and a branch -
    * which is the whole reason the particle count is attached here rather than to the report: the
    * report only runs when a human asks for it, and the number has to have been accumulating the
    * whole time for the answer to mean anything.
    */
   public static void countParticles(int count) {
      if (enabled && count > 0) {
         particlesThisTick.add(count);
      }
   }

   /** Flushes the current tick's timings into the rolling stats. Call once at
    *  the end of the END_SERVER_TICK handler. */
   public static void endTick() {
      // The particle count is flushed first and unconditionally: a tick that sent particles but
      // ran no instrumented system is still a tick that spent time on particles, and the report
      // would otherwise show the previous tick's number as if it were this one's.
      long sent = particlesThisTick.sumThenReset();
      particlesLastTick = sent;
      if (sent > particlesPeakTick) {
         particlesPeakTick = sent;
      }
      particlesTotal += sent;
      if (pending.isEmpty()) {
         return;
      }
      // Iterating a copy as well as holding a concurrent map: work that arrives from another
      // thread while this flush runs is simply counted on the next tick, which is the right
      // answer for a timer - and it cannot abort the tick it interrupted.
      for (Map.Entry<String, Long> e : Map.copyOf(pending).entrySet()) {
         record(e.getKey(), e.getValue());
      }
      pending.clear();
   }

   /** Feeds an externally-measured duration in (used to keep skipped systems
    *  visible in the report as zero-cost rows). Public: ModEvents records the
    *  gated per-player systems directly. */
   public static void record(String what, long nanos) {
      Stats s = stats.get(what);
      if (s == null) {
         if (stats.size() >= MAX_SYSTEMS) {
            return;
         }
         s = new Stats();
         stats.put(what, s);
      }
      s.totalNanos += nanos;
      s.samples++;
      if (nanos > s.maxNanos) {
         s.maxNanos = nanos;
      }

      // Rolling window: worst-of-the-last-WINDOW-ticks view.
      double[] buf = window.get(what);
      if (buf == null) {
         buf = new double[WINDOW];
         window.put(what, buf);
         windowIdx.put(what, 0);
         windowFull.put(what, false);
      }
      int idx = windowIdx.get(what);
      buf[idx] = nanos / 1_000_000.0D;
      idx++;
      if (idx >= WINDOW) {
         idx = 0;
         windowFull.put(what, true);
      }
      windowIdx.put(what, idx);
   }

   /** Samples the server's own MSPT/TPS roughly once a second from the vanilla
    *  rolling tick-time buffer. Silently marks itself unavailable if the field
    *  ever moves out from under us on a future version. */
   public static void tickServer(MinecraftServer server) {
      long now = System.nanoTime();
      if (lastMsptSample != 0L && now - lastMsptSample < MSPT_INTERVAL * 50_000_000L) {
         return;
      }
      lastMsptSample = now;
      try {
         long[] times = server.tickTimesNanos;
         if (times != null && times.length > 0) {
            long sum = 0L;
            int n = 0;
            for (long t : times) {
               if (t > 0L) {
                  sum += t;
                  n++;
               }
            }
            if (n > 0) {
               mspt = (sum / (double)n) / 1_000_000.0D;
               tps = Math.min(20.0D, 1000.0D / Math.max(1.0D, mspt));
               msptAvailable = true;
            }
         }
      } catch (Throwable ignored) {
         msptAvailable = false;
      }
   }

   public static boolean isEnabled() {
      return enabled;
   }

   public static void setEnabled(boolean value) {
      enabled = value;
      if (!value) {
         clear();
      }
   }

   public static void clear() {
      stats.clear();
      window.clear();
      windowIdx.clear();
      windowFull.clear();
      pending.clear();
      particlesThisTick.reset();
      particlesLastTick = 0L;
      particlesPeakTick = 0L;
      particlesTotal = 0L;
      lastMsptSample = 0L;
   }

   /** Particles the server was asked to send on the last completed tick. */
   public static long particlesLastTick() {
      return particlesLastTick;
   }

   /**
    * Particles counted so far in the tick that is still running.
    *
    * <p>Not flushed, and that is the point: a check that wants to prove the {@code ServerLevel} mixin
    * is actually wired cannot wait for a tick boundary it is not allowed to cross, and reading the
    * flushed value would only ever tell it about the previous tick. This is the seam the self-test
    * uses to see a real {@code sendParticles} call arrive here.
    */
   public static long particlesThisTick() {
      return particlesThisTick.sum();
   }

   /** The worst single tick's particle count this session - the spike, not the average. */
   public static long particlesPeakTick() {
      return particlesPeakTick;
   }

   /** Every particle this session has asked for, summed. */
   public static long particlesTotal() {
      return particlesTotal;
   }

   public static double mspt() {
      return mspt;
   }

   /**
    * A tick rate a test has pinned, or {@link Double#NaN} when the sampled one is the truth.
    *
    * <p>Exists because of a real trap in this suite: the anticheat discounts a finding's
    * confidence by the server's measured tick health, which is right - it is what keeps an
    * honest player on a struggling server from being moved - and the self-test *is* a struggling
    * server, because every check in the harness is a burst of work inside one tick. So a check
    * that has to run as if the server were healthy could measure the discount instead of the rule
    * it exists to pin, and whether it did came down to whether the once-a-second sample happened
    * to land on a heavy moment, which made the whole gate flaky on a loaded machine.
    */
   private static double pinnedTps = Double.NaN;

   /**
    * Pins the tick rate the anticheat reads, and answers with the one that was pinned before -
    * which is {@link Double#NaN} when nothing was, so a caller restores it by handing the answer
    * straight back in its {@code finally}.
    */
   public static double pinTpsForTest(double value) {
      double previous = pinnedTps;
      pinnedTps = value;
      return previous;
   }

   public static double tps() {
      return Double.isNaN(pinnedTps) ? tps : pinnedTps;
   }

   public static boolean msptAvailable() {
      return msptAvailable;
   }

   private static final class Row {
      final String name;
      final long avgNanos;
      final double worstWindowMs;

      Row(String name, long avgNanos, double worstWindowMs) {
         this.name = name;
         this.avgNanos = avgNanos;
         this.worstWindowMs = worstWindowMs;
      }
   }

   private static double worstWindowMs(String name) {
      double[] buf = window.get(name);
      if (buf == null) {
         return 0.0D;
      }
      int usable = Boolean.TRUE.equals(windowFull.get(name)) ? buf.length : windowIdx.get(name);
      double worst = 0.0D;
      for (int i = 0; i < usable; i++) {
         if (buf[i] > worst) {
            worst = buf[i];
         }
      }
      return worst;
   }

   /**
    * Builds the {@code /ff perf report}: server MSPT/TPS first, then the world's live counts, then
    * every system the mod runs - sorted by average cost and colored by share of the tick budget.
    *
    * <p>The shape is deliberate. Cost without a denominator is not a finding, so the server's own
    * tick time comes first; then what the world is carrying, because the two failure modes this
    * report exists to tell apart (a system that is slow, and a world that is simply enormous) are
    * indistinguishable from the table alone; and only then the table itself, which is the half that
    * says <i>which</i> system. */
   public static List<Component> report(MinecraftServer server) {
      List<Component> out = new ArrayList<>();
      String modMspt = String.format("%.3f", totalAverageMs());
      if (msptAvailable) {
         out.add(Component.literal("§8§m                                          "));
         out.add(Component.literal("§6§lFF Performance§7 - server §f" + String.format("%.1f", mspt) + " MSPT"
            + " §7(§f" + String.format("%.1f", tps) + " TPS§7)"));
         out.add(Component.literal("§7Fortune & Favors costs §f" + modMspt + "ms §7of that, on average."));
      } else {
         out.add(Component.literal("§6§lFF Performance§7 - Fortune & Favors costs §f" + modMspt + "ms/tick §7on average."));
      }
      out.add(Component.literal("§7avg§8 = §7mean per tick this session, §7spike§8 = §7worst of the last 5s."));
      out.addAll(liveLines(server));
      out.add(Component.literal(""));

      List<Row> rows = new ArrayList<>();
      for (Map.Entry<String, Stats> e : stats.entrySet()) {
         Stats s = e.getValue();
         if (s.samples == 0) {
            continue;
         }
         rows.add(new Row(e.getKey(), s.totalNanos / s.samples, worstWindowMs(e.getKey())));
      }
      if (rows.isEmpty()) {
         out.add(Component.literal("§7No samples yet - wait a few seconds."));
         return out;
      }
      rows.sort(Comparator.comparingLong((Row r) -> r.avgNanos).reversed());

      double budget = Math.max(50.0D, msptAvailable ? mspt : 50.0D);
      for (Row r : rows) {
         double avgMs = r.avgNanos / 1_000_000.0D;
         double share = avgMs / budget * 100.0D;
         String color = share > 5.0D ? "§c" : share > 1.0D ? "§e" : "§7";
         out.add(Component.literal(
            color + String.format("%-32s", r.name)
               + " §f" + String.format("%.3f", avgMs) + "ms §7avg"
               + " §8| §f" + String.format("%.2f", r.worstWindowMs) + "ms §7spike"
         ));
      }
      out.add(Component.literal("§8§m                                          "));
      out.add(Component.literal(
         "§7/ff perf report §8- §7this, §f/ff perf reset §8- §7drop every sample, "
            + "§f/ff perf off §8- §7zero overhead, §f/ff perf on §8- §7resume."
      ));
      return out;
   }

   /**
    * The live half of the report: what the server is carrying <i>right now</i>, rather than what it
    * has been spending.
    *
    * <p>Read on demand and never cached, because a regression read off a command is read while it
    * is happening: a TPS drop that is "a hundred thousand entities" and one that is "half a
    * million particles a tick" look identical in the per-system table, and this is the half that
    * tells them apart. Both are counted the honest way - entities by walking the loaded entity
    * list of each dimension (there is no cheaper accessor that does not lie about chunk unloading),
    * particles from the per-tick counter the {@code ServerLevel} mixin feeds.
    */
   private static List<Component> liveLines(MinecraftServer server) {
      List<Component> out = new ArrayList<>();
      if (server == null) {
         return out;
      }
      int entities = 0;
      int levels = 0;
      List<Component> perLevel = new ArrayList<>();
      for (ServerLevel level : server.getAllLevels()) {
         int inLevel = 0;
         for (Entity ignored : level.getAllEntities()) {
            inLevel++;
         }
         int players = 0;
         for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.level() == level) {
               players++;
            }
         }
         entities += inLevel;
         levels++;
         perLevel.add(Component.literal(
            "§7  " + String.format("%-22s", level.dimension().identifier())
               + " §f" + inLevel + " §7entit" + (inLevel == 1 ? "y" : "ies")
               + "  §8| §f" + players + " §7player" + (players == 1 ? "" : "s")
         ));
      }
      int online = server.getPlayerList().getPlayers().size();
      out.add(Component.literal(
         "§6§lFF Live§7 - §f" + entities + " §7entit" + (entities == 1 ? "y" : "ies")
            + " in §f" + levels + " §7dimension" + (levels == 1 ? "" : "s")
            + ", §f" + online + " §7online."
      ));
      out.addAll(perLevel);
      out.add(Component.literal(
         "§7particles: §f" + particlesLastTick + " §7last tick §8| §f" + particlesPeakTick
            + " §7peak §8| §f" + particlesTotal + " §7this session"
      ));
      return out;
   }

   /** Total average milliseconds per tick the mod's own systems consume. */
   public static double totalAverageMs() {
      double total = 0.0D;
      for (Stats s : stats.values()) {
         if (s.samples > 0) {
            total += s.totalNanos / (double)s.samples;
         }
      }
      return total;
   }
}
