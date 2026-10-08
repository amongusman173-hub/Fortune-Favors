package com.fortuneandfavors.economy;

import com.fortuneandfavors.util.Chat;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * The block's clock: one timed event at a time, on a rotation, for the whole prison.
 *
 * <p>A prison that is the same every time you log in is a job, not a place.
 * The mine, the pond and the Pit each had a fixed payout and no reason to ever be walked into at one
 * moment rather than another, so a prisoner optimised once and then repeated it forever. An event
 * gives the block a <i>now</i>: for four minutes the till pays double, or the Pit pays double, or
 * the guards are everywhere - and because the whole block is on the same clock, two prisoners in
 * different wings are having the same four minutes. That is what makes it a place and not a
 * spreadsheet.
 *
 * <p>Nothing here is stored. The active event is a pure function of the world's game time - the
 * period number picks the event and the phase within the period says whether it is running - so a
 * server that restarts mid-event resumes the same event, every player sees the same one, and there
 * is no save file that can disagree with the clock. See {@link #activeEvent(long)}.
 *
 * <p>This class is deliberately only <i>multipliers and a schedule</i>. The systems that pay out -
 * the Pit's round machine, the fishing catch, the till, the guard spawner - ask it for a number and
 * apply it themselves, so an event can never become a second, parallel payout path that forgets a
 * rule the real one enforces (the contraband scan, the run-abandon rule, the guard reward).
 */
public final class PrisonEvents {
   /** How long one event runs for: four minutes. */
   public static final long EVENT_TICKS = 20L * 60L * 4L;
   /** The quiet between events: three minutes, so an event feels like weather rather than a state. */
   public static final long GAP_TICKS = 20L * 60L * 3L;
   /** One event plus its following quiet. */
   public static final long PERIOD = EVENT_TICKS + GAP_TICKS;

   /**
    * The six things the block does, in the order it does them.
    *
    * <p>Ordered so no two payout events sit next to each other and the two that make the block
    * <i>harder</i> are spaced apart: Pit Night, then the mine, then the pond, then a shift of extra
    * guards, then a lockdown, then the Warden's purge, and back to the Pit.
    */
   private static final Event[] ORDER = {
      Event.PIT_NIGHT,
      Event.ORE_RUSH,
      Event.FISHING_FRENZY,
      Event.GUARD_SHIFT,
      Event.LOCKDOWN,
      Event.WARDEN_PURGE
   };

   /** Which event is running right now, or null in the quiet between two of them. */
   private static Event lastRunning = null;

   private PrisonEvents() {
   }

   /**
    * One timed event.
    *
    * <p>Each carries its own colour and its own one-line blurb, and the blurb is the whole of what a
    * prisoner is told: "the Pit pays double" is an instruction, and the multipliers that implement
    * it live in the named methods below rather than in a table nobody can read.
    */
   public enum Event {
      PIT_NIGHT("Pit Night", "§d", "the Pit pays half again in cash and double in tokens"),
      ORE_RUSH("Ore Rush", "§6", "the till pays double for everything it is allowed to buy"),
      FISHING_FRENZY("Fishing Frenzy", "§b", "every catch at the pond pays triple"),
      GUARD_SHIFT("Guard Shift", "§e", "the block is doubling the patrols - guards come twice as fast"),
      LOCKDOWN("Lockdown", "§c", "the mine is being watched - everything you dig builds Heat faster"),
      WARDEN_PURGE("Warden Purge", "§4", "the Warden comes twice as often, and he is not here to talk");

      public final String label;
      public final String colour;
      public final String blurb;

      Event(String label, String colour, String blurb) {
         this.label = label;
         this.colour = colour;
         this.blurb = blurb;
      }
   }

   // ------------------------------------------------------------------
   // The schedule
   // ------------------------------------------------------------------

   /** The event running at this game tick, or null if the block is between events. */
   public static Event activeEvent(long tick) {
      long phase = Math.floorMod(tick, PERIOD);
      if (phase >= EVENT_TICKS) {
         return null;
      }
      long period = Math.floorDiv(tick, PERIOD);
      return ORDER[(int)Math.floorMod(period, ORDER.length)];
   }

   /** Whether a given event is the one running. */
   public static boolean active(Event event, long tick) {
      return event != null && activeEvent(tick) == event;
   }

   /** The event that follows the one running (or the quiet) at this tick. */
   public static Event nextEvent(long tick) {
      long period = Math.floorDiv(tick, PERIOD);
      return ORDER[(int)Math.floorMod(period + 1L, ORDER.length)];
   }

   /** How many ticks until the current event ends, or the next one begins if the block is quiet. */
   public static long ticksLeft(long tick) {
      long phase = Math.floorMod(tick, PERIOD);
      return phase < EVENT_TICKS ? EVENT_TICKS - phase : PERIOD - phase;
   }

   /** A mm:ss clock, for the chat line and the menus. */
   public static String clockText(long ticks) {
      long seconds = Math.max(0L, ticks) / 20L;
      return String.format("%d:%02d", seconds / 60L, seconds % 60L);
   }

   // ------------------------------------------------------------------
   // The multipliers the systems ask for
   // ------------------------------------------------------------------

   /** What the Pit pays on a cleared round, in cash: half again on Pit Night. */
   public static double pitCashMult(long tick) {
      return active(Event.PIT_NIGHT, tick) ? 1.5 : 1.0;
   }

   /** What the Pit pays on a cleared round, in tokens: double on Pit Night. */
   public static double pitTokenMult(long tick) {
      return active(Event.PIT_NIGHT, tick) ? 2.0 : 1.0;
   }

   /** What the till pays for a legal haul: double during an Ore Rush. */
   public static double tillMult(long tick) {
      return active(Event.ORE_RUSH, tick) ? 2.0 : 1.0;
   }

   /** What a fish is worth: triple during a Fishing Frenzy. */
   public static double fishMult(long tick) {
      return active(Event.FISHING_FRENZY, tick) ? 3.0 : 1.0;
   }

   /** What a mined block adds to Heat: half again during a Lockdown. */
   public static double heatGainMult(long tick) {
      return active(Event.LOCKDOWN, tick) ? 1.5 : 1.0;
   }

   /** The guard spawn interval: halved - they come twice as fast - on a Guard Shift. */
   public static double guardIntervalMult(long tick) {
      return active(Event.GUARD_SHIFT, tick) ? 0.5 : 1.0;
   }

   /** The Warden's cooldown: halved during a Warden Purge. */
   public static double wardenCooldownMult(long tick) {
      return active(Event.WARDEN_PURGE, tick) ? 0.5 : 1.0;
   }

   // ------------------------------------------------------------------
   // The announcement
   // ------------------------------------------------------------------

   /**
    * Drives the clock: announces a start when one begins and an end when it ends.
    *
    * <p>Called once a server tick from the prison tick. The only state is the event last spoken
    * about, so a restart mid-event announces it once and then leaves it alone.
    */
   public static void tick(MinecraftServer server) {
      if (server == null) {
         return;
      }
      ServerLevel level = server.getLevel(PrisonManager.PRISON_DIM);
      if (level == null) {
         return;
      }
      // The block's clock, not the dimension's own game time: a dimension that is not the overworld
      // never advances its game time, so an event schedule measured from it stands still - which is
      // why the block was always in the same event (and, in the same way, why the Pit round never
      // ended and the mine never refilled). The multipliers the systems ask for are read off the
      // same clock, so the phase a prisoner is in is the phase the payout answers for.
      long tick = PrisonCellblock.clock(level);
      Event running = activeEvent(tick);
      if (running == lastRunning) {
         return;
      }
      lastRunning = running;
      if (running != null) {
         announceStart(server, running, tick);
      } else {
         announceEnd(server, tick);
      }
   }

   private static void announceStart(MinecraftServer server, Event event, long tick) {
      for (ServerPlayer p : server.getPlayerList().getPlayers()) {
         if (!PrisonManager.isInPrison(p)) {
            continue;
         }
         Chat.raw(p, "");
         Chat.raw(p, event.colour + "§l" + event.label.toUpperCase() + "§r §7- " + event.blurb + ".");
         Chat.raw(p, "§8" + clockText(ticksLeft(tick)) + " on the clock. The whole block is on it.");
         p.sendSystemMessage(
            Component.literal(event.colour + "§l" + event.label.toUpperCase() + " §r§7· " + clockText(ticksLeft(tick))),
            true
         );
      }
   }

   private static void announceEnd(MinecraftServer server, long tick) {
      Event next = nextEvent(tick);
      for (ServerPlayer p : server.getPlayerList().getPlayers()) {
         if (!PrisonManager.isInPrison(p)) {
            continue;
         }
         Chat.raw(p, "§7The block returns to normal. §8Next: " + next.colour + next.label + "§8 in " + clockText(ticksLeft(tick)) + ".");
      }
   }

   /**
    * The one line a menu draws: what is running and for how long, or what is coming and when.
    *
    * <p>Pure, so the self-test can read the same string the prisoner does.
    */
   public static String statusLine(long tick) {
      Event running = activeEvent(tick);
      if (running != null) {
         return running.colour + "§l" + running.label.toUpperCase() + "§r §7- " + running.blurb + " §8(" + clockText(ticksLeft(tick)) + " left)";
      }
      Event next = nextEvent(tick);
      return "§8Quiet - next: " + next.colour + next.label + "§8 in " + clockText(ticksLeft(tick)) + ".";
   }

   /** Test hook: forget the last thing announced, as a fresh server would. */
   public static void forgetForTest() {
      lastRunning = null;
   }
}
