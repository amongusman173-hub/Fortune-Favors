package com.fortuneandfavors.economy;

import java.util.HashMap;
import java.util.Map;

/**
 * One gate in front of every line a boss broadcasts.
 *
 * <p>Two problems, one fix. The first is configuration: every boss manager had
 * its own {@code announce} helper with no way to turn the noise off, so a server
 * that found the taunting tiresome had no recourse at all. {@link #allowed}
 * checks {@link ModConfig#bossDialogue()} first.
 *
 * <p>The second is repetition. Several fights re-enter the same code path from
 * more than one place (a phase transition checked both on damage and on tick, a
 * move retried after a channel), and the same line went out two or three times in
 * a row - which is exactly what makes flavour text feel like spam rather than
 * personality. The same line inside a short window is now dropped, so a boss
 * repeats itself only when it genuinely means to.
 *
 * <p>The third is volume, and it is the one that actually fills a chat box. A
 * line that names the player, or counts a number, is different every time the
 * boss says it, so identical-line dedupe never fires on it: a taunt on a timer
 * could go out every second and every one of those was "new". A per-<em>channel</em>
 * minimum gap fixes that - one speaker cannot say two things inside the same
 * second, whatever it is saying - while the identical-line window still swallows
 * an accidental double-fire. Channels are per boss, so the King going quiet for
 * a second does not gag the Mindbinder.
 *
 * <p>The window is deliberately short: long enough to swallow an accidental
 * double-fire in the same second, short enough that a boss who says the same
 * thing twice in a real fight still says it twice.
 */
public final class BossChat {
   /** How long an identical line is suppressed, in milliseconds. */
   private static final long WINDOW_MS = 2500L;
   /** How long a single speaker must stay quiet between two lines. */
   private static final long CHANNEL_GAP_MS = 1200L;
   /** Cap on remembered lines, so a long uptime cannot grow this map forever. */
   private static final int MEMORY = 512;

   private static final Map<String, Long> LAST_SEEN = new HashMap<>();
   private static final Map<String, Long> CHANNEL_LAST = new HashMap<>();

   private BossChat() {
   }

   /**
    * Whether this line should be sent at all: boss dialogue is enabled, this
    * speaker has been quiet for the channel gap, and the same line has not just
    * been sent.
    */
   public static boolean allowed(String message) {
      return allowed("boss", message);
   }

   /**
    * The same decision for one named speaker. Two lines from different channels
    * are independent; two from the same channel inside {@link #CHANNEL_GAP_MS}
    * are not both sent.
    */
   public static boolean allowed(String channel, String message) {
      if (message == null || message.isEmpty()) {
         return false;
      }
      if (!ModConfig.bossDialogue()) {
         return false;
      }
      long now = System.currentTimeMillis();
      String ch = channel == null ? "boss" : channel;
      Long lastOnChannel = CHANNEL_LAST.get(ch);
      if (lastOnChannel != null && now - lastOnChannel < CHANNEL_GAP_MS) {
         return false;
      }
      Long last = LAST_SEEN.get(message);
      if (last != null && now - last < WINDOW_MS) {
         return false;
      }
      if (LAST_SEEN.size() >= MEMORY) {
         LAST_SEEN.clear();
      }
      LAST_SEEN.put(message, now);
      CHANNEL_LAST.put(ch, now);
      return true;
   }

   /** Forgets the dedupe history - used when a fight ends so the next summon
    *  starts with a clean slate and its opening line is not swallowed. */
   public static void reset() {
      LAST_SEEN.clear();
      CHANNEL_LAST.clear();
   }
}
