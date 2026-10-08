package com.fortuneandfavors.economy;

import com.fortuneandfavors.util.JsonUtil;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;

/**
 * The clock every timer in this mod runs on when it is not standing in the overworld.
 *
 * <p>The bug this exists to answer is invisible and quiet: {@code level.getGameTime()} does not
 * advance outside the overworld. {@code ServerLevel.tickTime()} is the only writer of a level's
 * game time and it returns immediately unless the level was created with {@code tickTime} true -
 * and the only level created that way is the overworld. Every dimension this mod makes its own
 * (the expedition realm, the duel realm, the prison) and every other dimension besides (the End,
 * the Nether) therefore has a game time frozen at the value it was created with. Any deadline
 * measured against it is a number that never catches up to itself: a collapse that never falls, a
 * downed body that is never taken, a guardian that never moves, a dragon that never wakes. The
 * timers did not crash; they simply never fired, which is the failure that is hardest to report.
 *
 * <p>The answer is one clock, read through one call, so no two timers can drift apart from each
 * other again. It is the server's own tick counter - which advances once per tick for the whole
 * server, whatever dimension a caller is standing in - plus a base that carries the clock across
 * restarts. {@code getTickCount()} counts from zero every boot, so a deadline written last session
 * as "tick 40,000" would otherwise read, on the next boot, as forty thousand ticks away. The base
 * is restored from {@link #load} and written back by {@link #save}; a wait therefore pauses while
 * the server is down, which is what a wait should do.
 *
 * <p>What it is <i>not</i> is a second, competing clock for the overworld. A caller that was
 * already reading {@code getGameTime()} in a dimension where it really does advance can keep doing
 * so; what must never happen is one timer that sets a deadline on one clock and reads it on
 * another. Anything that has been moved onto this clock stays on it end to end.
 */
public final class ServerClock {
   /** Ticks that belong to server processes earlier than this one; the part of the clock that
    * survives a restart. Zero until {@link #load} has read the file back. */
   private static long base = 0L;
   private static Path file;

   private ServerClock() {
   }

   /** The clock, read off whichever level a caller has to hand. */
   public static long clock(Level level) {
      return clock(level == null ? null : level.getServer());
   }

   /**
    * The clock, read off a server. Null-safe: a caller with no server gets the last known base,
    * which is at worst a stale reading rather than an exception in a tick loop.
    */
   public static long clock(MinecraftServer server) {
      return server == null ? base : base + server.getTickCount();
   }

   /** The part of the clock that belongs to earlier sessions - the harness's seam for a reboot. */
   public static long base() {
      return base;
   }

   /** Test seam: move the clock's base, so a check can prove a deadline survives a restart. */
   public static void setBaseForTest(long value) {
      base = Math.max(0L, value);
   }

   /**
    * Reads the clock's base back from disk. The file stores the clock's <i>absolute</i> reading at
    * the last save, so the next boot's counter simply resumes from there.
    */
   public static void load(MinecraftServer server) {
      base = 0L;
      file = EconomyManager.getDataDir(server).resolve("server_clock.json");
      JsonObject root = JsonUtil.readOrCreate(file, new JsonObject());
      base = Math.max(0L, JsonUtil.jsonLong(root, "epoch", 0L));
   }

   /** Writes the clock's absolute reading, so a restart resumes where this process stopped. */
   public static void save(MinecraftServer server) {
      if (server == null) {
         return;
      }
      if (file == null) {
         file = EconomyManager.getDataDir(server).resolve("server_clock.json");
      }
      JsonObject root = new JsonObject();
      root.addProperty("epoch", base + server.getTickCount());
      JsonUtil.write(file, root);
   }
}
