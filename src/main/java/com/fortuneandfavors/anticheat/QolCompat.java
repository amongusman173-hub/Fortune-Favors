package com.fortuneandfavors.anticheat;

import com.fortuneandfavors.economy.ModConfig;
import java.util.ArrayList;
import java.util.List;

/**
 * The client mods this server expects players to be running, and exactly how much
 * of the anticheat each one is allowed to move.
 *
 * <p>Sections 16, 21 and 22 of the specification are three statements of the same
 * problem: a detection layer that was written against a vanilla client will flag
 * people who are not cheating, because most players run things. Section 22 names
 * why this is not solved by finding out which mods a client has: an anticheat that
 * bans on a mod name is an anticheat that bans on a mod name, and the specification
 * says in as many words that client-side detection is supporting evidence and
 * nothing else. So there is no mod fingerprinting anywhere in this package. What
 * there is, instead, is this: a short list of <i>behaviours</i> that real quality
 * of life mods legitimately produce, and for each one, the specific place where a
 * check would otherwise have read that behaviour as cheating.
 *
 * <p>Two rules shape everything below.
 *
 * <p><b>A compatibility mode may not switch a check off.</b> Section 16 is explicit
 * about the shape it wants: precise placement is allowed, but precise placement
 * plus an impossible reach, or plus an impossible placement sequence, is still
 * investigated. So every entry here names a <i>signal</i> it removes and a set of
 * checks it leaves alone, and no entry may leave reach, the packet budget or
 * anything in combat standing down.
 *
 * <p><b>Where the honest answer is "nothing", it says nothing.</b> Inventory Walk -
 * walking with a container open - needs no allowance at all, because no check in
 * this module reads whether a container is open; movement is judged on the physics
 * whether the inventory is up or not. The correct compatibility support for it is
 * an entry that grants no relaxation and a test that keeps it that way, rather than
 * an invented exception that would eventually be the thing a cheater hid behind.
 */
public final class QolCompat {
   // ------------------------------------------------------------------- keys
   /** Accurate Block Placement Reborn, and everything shaped like it. */
   public static final String ABP = "abp";
   /** Moving while a container is open. */
   public static final String INVENTORY_WALK = "inventory_walk";
   /** Mouse Tweaks, inventory sorting, and the burst clicking they exist to do. */
   public static final String QUICK_INVENTORY = "quick_inventory";
   /**
    * Auto-jump - a vanilla client option rather than a mod, and the same class of thing.
    *
    * <p>It is here because "my jumps look regular" is the first thing anybody accuses a
    * bunny-hop detector of, and the honest answer deserves to be written down where the
    * next person can find it.
    */
   public static final String AUTO_JUMP = "auto_jump";
   /** Step-assist mods, and the raised step height they are all really just asking for. */
   public static final String STEP_ASSIST = "step_assist";

   /**
    * One behaviour, what it costs, and what it cannot buy.
    *
    * @param key the config toggle suffix - {@code anticheat_<key>}
    * @param name what to call it in a readout
    * @param relaxes the specific signal a check has to stop using, in plain words
    * @param keeps what is explicitly <i>not</i> relaxed, so that "we support this
    *        mod" can never be read as "this mod is a free pass"
    */
   public record Mode(String key, String name, String relaxes, String keeps) {
   }

   public static final List<Mode> MODES = List.of(
      new Mode(
         ABP,
         "Accurate Block Placement Reborn",
         "the crosshair. Precise-placement mods raycast the block face themselves, so where a player is "
            + "looking no longer has to match where a block legitimately lands - which is the one thing the "
            + "scaffold check was using pitch as a proxy for",
         "reach and the physical half of the scaffold check - climbing on blocks you placed under "
            + "yourself, and catching a fall with them, are still refused"
      ),
      new Mode(
         INVENTORY_WALK,
         "Inventory Walk",
         "nothing. No check in this module reads whether a container is open, so movement with the inventory "
            + "up is measured against the same physics as any other movement. The entry exists so that the "
            + "next person to add a check knows not to make an open container into evidence",
         "every movement check, unchanged"
      ),
      new Mode(
         QUICK_INVENTORY,
         "Mouse Tweaks / inventory sorting",
         "the size of the inventory packet bank, because a shift-drag across a chest or a sort of a full "
            + "inventory is one action that arrives as dozens of clicks inside a tick or two",
         "the flood limit, the impossible-slot check, and every combat and movement check"
      ),
      new Mode(
         AUTO_JUMP,
         "Auto-jump (vanilla client option)",
         "nothing, and the reason is worth reading. Auto-jump holds the jump key for you, so it produces "
            + "regularly spaced jumps - but the bunny-hop check does not look at jump spacing at all. It "
            + "looks for a body that DESCENDS and then RISES without touching anything, and auto-jump cannot "
            + "produce that, because it can only jump from the ground like everybody else",
         "bunny-hop, long-jump, jesus, air-walk and flight are all unchanged, and no combat check moves"
      ),
      new Mode(
         STEP_ASSIST,
         "Step-assist / auto-step mods",
         "nothing - and this entry replaced a real false positive rather than papering over one. A body "
            + "climbing a block ends up above it, and falling down a stairwell looks like descend-rise-descend, "
            + "which is the shape bunny-hop looks for. The check no longer assumes the vanilla 0.6 step: it "
            + "asks the world whether anything beside or under the player's feet is within their own "
            + "maxUpStep, and excuses the rise when it is. So the allowance comes from the game's own number "
            + "and covers a raised step height without a client having to declare anything",
         "long-jump, jesus, air-walk and flight are unchanged, and nothing about reach, placement or combat "
            + "moves at all"
      )
   );

   /**
    * The mods that need no entry, stated rather than omitted.
    *
    * <p>Section 21 lists performance, HUD, cosmetic and animation mods alongside
    * the three above, and the honest answer for them is that there is nothing to
    * do: nothing in this package reads a frame rate, a HUD, a skin or an animation,
    * so a client running all four produces exactly the traffic an unmodded one does.
    * The reason that is written down instead of left implicit is that "not handled"
    * and "nothing to handle" look identical from the outside, and the next person to
    * add a check should be able to tell which one they are looking at.
    */
   public static final String NEEDS_NO_ENTRY =
      "Performance, HUD, cosmetic and animation mods need no entry: nothing here reads a frame rate, "
         + "a HUD, a skin or an animation, so a client running them sends exactly the traffic an unmodded "
         + "one does. Sprint toggles, camera and freecam mods, minimaps, zoom and shader packs are the same "
         + "answer for the same reason - they are all client-side rendering or a rebinding of a key, and the "
         + "server sees an identical packet stream either way. Section 22 applies to every entry above - "
         + "none of this fingerprints a mod, and none of it treats a mod name as evidence of anything. The "
         + "closest this package comes to naming a mod is the client's own self-report, which is a note on a "
         + "readout for a human and cannot raise a violation; see /ff anticheat info.";

   private QolCompat() {
   }

   /** True when this behaviour's allowance is switched on. */
   public static boolean on(String key) {
      return switch (key) {
         case ABP -> ModConfig.anticheatAbp();
         case INVENTORY_WALK -> ModConfig.anticheatInventoryWalk();
         case QUICK_INVENTORY -> ModConfig.anticheatQuickInventory();
         case AUTO_JUMP -> ModConfig.anticheatAutoJump();
         case STEP_ASSIST -> ModConfig.anticheatStepAssist();
         default -> false;
      };
   }

   /**
    * True while placement precision cannot be inferred from the crosshair. The
    * scaffold check asks this before it uses pitch as evidence.
    */
   public static boolean crosshairIsNotEvidence() {
      return on(ABP);
   }

   /** The config toggle name that turns one of these on and off. */
   public static String toggle(String key) {
      return "anticheat_" + key;
   }

   /** One line per behaviour, for {@code /ff anticheat compat}. */
   public static List<String> describe() {
      List<String> out = new ArrayList<>();
      for (Mode mode : MODES) {
         out.add(
            "&7- &f" + mode.name() + " &8[" + (on(mode.key()) ? "&aON&8" : "&cOFF&8") + "] &7"
               + toggle(mode.key())
         );
         out.add("&8    relaxes: &7" + mode.relaxes());
         out.add("&8    keeps: &7" + mode.keeps());
      }
      return out;
   }

   /** The keys, for the self-test and for a readout that has to be exhaustive. */
   public static List<String> keys() {
      List<String> out = new ArrayList<>();
      for (Mode mode : MODES) {
         out.add(mode.key());
      }
      return out;
   }

   /** Finds one entry by key, or null. */
   public static Mode mode(String key) {
      for (Mode mode : MODES) {
         if (mode.key().equals(key)) {
            return mode;
         }
      }
      return null;
   }
}
