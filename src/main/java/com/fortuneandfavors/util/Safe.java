package com.fortuneandfavors.util;

import com.fortuneandfavors.FortuneFavorsMod;
import java.util.function.Supplier;
import net.minecraft.world.InteractionResult;

public final class Safe {
   private Safe() {
   }

   /** Safe.run keeps its catch-everything guarantee whether profiling is on or
    *  off; the profiler adds per-system timing on top when enabled. */
   public static void run(String what, Runnable action) {
      try {
         if (PerfMonitor.isEnabled()) {
            PerfMonitor.run(what, action);
         } else {
            action.run();
         }
      } catch (VirtualMachineError e) {
         throw e;
      } catch (Throwable t) {
         FortuneFavorsMod.LOGGER.error("Fortune & Favors: error during " + what + " - skipped to keep the server alive", t);
      }
   }

   public static boolean allow(String what, Supplier<Boolean> action) {
      try {
         return action.get();
      } catch (VirtualMachineError e) {
         throw e;
      } catch (Throwable t) {
         FortuneFavorsMod.LOGGER.error("Fortune & Favors: error during " + what + " - allowing vanilla behavior", t);
         return true;
      }
   }

   /**
    * A gate that fails <b>open</b>: the action returns true to deny something,
    * and an exception returns false so a broken check lets the player through
    * rather than shutting them out. Used for the anticheat's join gate, where
    * the safe answer to "did the check crash" is "let them in".
    */
   public static boolean veto(String what, Supplier<Boolean> action) {
      try {
         return action.get();
      } catch (VirtualMachineError e) {
         throw e;
      } catch (Throwable t) {
         FortuneFavorsMod.LOGGER.error("Fortune & Favors: error during " + what + " - not blocking", t);
         return false;
      }
   }

   public static InteractionResult result(String what, Supplier<InteractionResult> action) {
      try {
         return action.get();
      } catch (VirtualMachineError e) {
         throw e;
      } catch (Throwable t) {
         FortuneFavorsMod.LOGGER.error("Fortune & Favors: error during " + what + " - falling back to vanilla", t);
         return InteractionResult.PASS;
      }
   }
}
