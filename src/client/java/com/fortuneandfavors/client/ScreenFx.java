package com.fortuneandfavors.client;

public final class ScreenFx {
   public static final int FX_DEVOUR = 1;
   public static final int FX_CORRUPTION = 2;
   public static final int FX_LINKED = 3;
   public static final int FX_SWORD_BLOCK = 4;
   public static final int FX_GOLDEN_APPLE = 5;
   public static final int FX_DEADEYE = 6;
   public static final int FX_TIMESTOP = 7;
   public static final int FX_BEAT = 8;
   private static boolean beat;
   /** The loot box effect playing: 9 spinning, 10..13 a reveal of that band; and when it began. */
   private static int lootFx;
   private static long fractureStart;
   private static long puppetUntil;
   private static long lootStart;
   /** Longest a stop is ever shown for, in case the "time resumes" packet never arrives. */
   private static final long TIMESTOP_MAX_MS = 30000L;
   private static boolean timeStop;
   private static long timeStopStart;
   private static long timeStopRelease;
   private static long timeStopPendingRelease;
   private static final long OVERLAY_LIFETIME_MS = 8000L;
   private static final long SWORD_BLOCK_LIFETIME_MS = 800L;
   private static final long GOLDEN_APPLE_LIFETIME_MS = 1500L;
   private static final long DEADEYE_LIFETIME_MS = 700L;
   private static boolean devourEnabled = true;
   private static boolean corruptEnabled = true;
   private static boolean linkedEnabled = true;
   private static boolean swordBlockEnabled = true;
   private static boolean goldenAppleEnabled = true;
   private static boolean deadeyeEnabled = true;
   private static boolean devour;
   private static long devourExpires;
   private static boolean corrupt;
   private static long corruptExpires;
   private static boolean linked;
   private static long linkedExpires;
   private static boolean swordBlock;
   private static long swordBlockExpires;
   private static boolean goldenApple;
   private static long goldenAppleExpires;
   private static boolean deadeye;
   private static long deadeyeExpires;

   private ScreenFx() {
   }

   public static void set(int fx, boolean active) {
      if (fx == 1) {
         devour = active;
         devourExpires = active ? System.currentTimeMillis() + 8000L : 0L;
      } else if (fx == 2) {
         corrupt = active;
         corruptExpires = active ? System.currentTimeMillis() + 8000L : 0L;
      } else if (fx == 3) {
         linked = active;
         linkedExpires = active ? System.currentTimeMillis() + 8000L : 0L;
      } else if (fx == 4) {
         swordBlock = active;
         swordBlockExpires = active ? System.currentTimeMillis() + 800L : 0L;
      } else if (fx == 5) {
         goldenApple = active;
         goldenAppleExpires = active ? System.currentTimeMillis() + 1500L : 0L;
      } else if (fx == 6) {
         deadeye = active;
         deadeyeExpires = active ? System.currentTimeMillis() + 700L : 0L;
      } else if (fx == 15) {
         puppetUntil = System.currentTimeMillis() + 650L;
      } else if (fx == 14) {
         fractureStart = System.currentTimeMillis();
      } else if (fx >= 9 && fx <= 13) {
         lootFx = fx;
         lootStart = System.currentTimeMillis();
      } else if (fx == FX_BEAT) {
         beat = active;
      } else if (fx == FX_TIMESTOP) {
         // One continuous stop on screen: a second "stopped" while one is showing does not
         // replay the intro, and a "resumed" waits a quarter second so a stop that is
         // immediately followed by another flows straight on instead of blinking out.
         if (active) {
            timeStopPendingRelease = 0L;
            if (!timeStop) {
               timeStop = true;
               timeStopStart = System.currentTimeMillis();
            }
         } else if (timeStop) {
            timeStopPendingRelease = System.currentTimeMillis();
         }
      }
   }

   /** The loot effect playing (9 spin, 10..13 reveal by band), or 0. */
   public static int lootFx() {
      long age = System.currentTimeMillis() - lootStart;
      if (lootFx == 9 && age > 6000L || lootFx >= 10 && age > 2600L) {
         lootFx = 0;
      }
      return lootFx;
   }

   /** The box screen closed: its effect goes with it, so the next screen opened is clean. */
   public static void clearLoot() {
      lootFx = 0;
   }

   public static long lootAgeMs() {
      return System.currentTimeMillis() - lootStart;
   }

   /** Milliseconds since a Mindbinder fracture landed, or -1 once it has passed (2.5s). */
   public static long fractureAge() {
      long age = System.currentTimeMillis() - fractureStart;
      return fractureStart == 0L || age > 2500L ? -1L : age;
   }

   /** How strongly the seize overlay shows (fades out over its last 650ms), 0 when off. */
   public static float puppetStrength() {
      long left = puppetUntil - System.currentTimeMillis();
      return left <= 0L ? 0.0F : Math.min(1.0F, left / 400.0F);
   }

   public static boolean beatActive() {
      return beat;
   }

   /** Milliseconds since time stopped, or -1. Real time, because the game's own clock is frozen. */
   public static long timeStopElapsed() {
      if (timeStop && timeStopPendingRelease != 0L && System.currentTimeMillis() - timeStopPendingRelease > 250L) {
         timeStop = false;
         timeStopRelease = timeStopPendingRelease + 250L;
         timeStopPendingRelease = 0L;
      }
      if (!timeStop) {
         return -1L;
      }
      long e = System.currentTimeMillis() - timeStopStart;
      if (e > TIMESTOP_MAX_MS) {
         timeStop = false;
         return -1L;
      }
      return e;
   }

   /** Milliseconds since time resumed, or -1. */
   public static long timeResumeElapsed() {
      return timeStopRelease == 0L ? -1L : System.currentTimeMillis() - timeStopRelease;
   }

   public static boolean devourActive() {
      return devourEnabled && devour && System.currentTimeMillis() < devourExpires;
   }

   public static boolean corruptionActive() {
      return corruptEnabled && corrupt && System.currentTimeMillis() < corruptExpires;
   }

   public static boolean linkedActive() {
      return linkedEnabled && linked && System.currentTimeMillis() < linkedExpires;
   }

   public static boolean swordBlockActive() {
      return swordBlockEnabled && swordBlock;
   }

   public static void setEffectEnabled(int fx, boolean enabled) {
      if (fx == 1) {
         devourEnabled = enabled;
      } else if (fx == 2) {
         corruptEnabled = enabled;
      } else if (fx == 3) {
         linkedEnabled = enabled;
      } else if (fx == 4) {
         swordBlockEnabled = enabled;
      } else if (fx == 5) {
         goldenAppleEnabled = enabled;
      } else if (fx == 6) {
         deadeyeEnabled = enabled;
      }
   }

   public static boolean goldenAppleActive() {
      return goldenApple && System.currentTimeMillis() < goldenAppleExpires;
   }

   public static float goldenAppleProgress() {
      if (goldenApple && System.currentTimeMillis() < goldenAppleExpires) {
         long elapsed = System.currentTimeMillis() - (goldenAppleExpires - 1500L);
         return Math.min(1.0F, (float)elapsed / 1500.0F);
      } else {
         return 0.0F;
      }
   }

   public static boolean deadeyeActive() {
      return deadeyeEnabled && deadeye && System.currentTimeMillis() < deadeyeExpires;
   }

   public static float deadeyeProgress() {
      if (deadeye && System.currentTimeMillis() < deadeyeExpires) {
         long elapsed = System.currentTimeMillis() - (deadeyeExpires - 700L);
         return Math.min(1.0F, (float)elapsed / 700.0F);
      } else {
         return 0.0F;
      }
   }
}
