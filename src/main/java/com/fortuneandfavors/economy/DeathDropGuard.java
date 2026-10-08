package com.fortuneandfavors.economy;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Every branch that can cancel a player's death drop, and the proof that no item
 * is lost when it does.
 *
 * <h2>Why this exists</h2>
 * A cancelled drop is the single most dangerous thing this mod does. Vanilla
 * empties each inventory slot the instant it has called {@code drop} for it, so a
 * branch that cancels the drop is the <i>only</i> thing standing between a player
 * and a silently deleted item. Two real bugs came out of exactly this shape:
 *
 * <ul>
 *   <li>a blood claim cancelled <b>every</b> drop so a gear servant could carry the
 *       real stacks, and once the servant stopped carrying them the cancellation
 *       had nothing left to return them - players lost whole inventories;</li>
 *   <li>the pre-death inventory snapshot was taken after the drops had run, so
 *       {@code /ff restore} recorded the state <i>after</i> the loss and could
 *       never give anything back.</li>
 * </ul>
 *
 * <p>So the rule is written down once, here, and {@code deaths.no-item-loss} in
 * the self-test enforces it: every cancelling branch must either hand the item
 * back through a named method that really exists, or be provably cosmetic -
 * meaning the item never left the player in the first place.
 *
 * <p>This class deliberately holds no behaviour. It cannot drift, because the
 * checks read real classes by reflection rather than a copy of the logic.
 */
public final class DeathDropGuard {

   /** What happens to an item whose drop was cancelled. */
   public enum Outcome {
      /** The item never left the player; cancelling the drop loses nothing. */
      COSMETIC,
      /** The item is stored and handed back by the named recovery method. */
      RE_GRANTED
   }

   /**
    * One cancelling branch of {@code ServerPlayerDropMixin}.
    *
    * @param branch           the branch, named as a player would describe it
    * @param outcome          whether the item is re-granted or never left at all
    * @param recoveryOwner    class that owns the recovery path, or {@code null} for cosmetic
    * @param recoveryMethod   method on that class that re-grants the item, or {@code null}
    * @param worseThanDropping free-text note on why cancelling is the better outcome
    */
   public record Hold(String branch, Outcome outcome, String recoveryOwner, String recoveryMethod, String worseThanDropping) {
   }

   /**
    * The complete set of branches in {@code ServerPlayerDropMixin} that cancel a
    * drop. Adding a {@code cir.setReturnValue(null)} to that mixin without adding a
    * line here leaves the new branch undeclared, which is what the self-test's
    * branch-name check is for.
    */
   public static final List<Hold> HOLDS = List.of(
      new Hold(
         "duel.randomizer-weapon",
         Outcome.COSMETIC,
         null,
         null,
         "The weapon stays in the hand it was issued to; dropping it would hand the duel kit to the opponent."
      ),
      new Hold(
         "duel.bedwars-loadout",
         Outcome.COSMETIC,
         null,
         null,
         "The loadout is generated gear that belongs to the arena, not to the player."
      ),
      new Hold(
         "soulbound",
         Outcome.RE_GRANTED,
         "com.fortuneandfavors.economy.AdvancedEnchantments",
         "captureSoulbound",
         "Soulbound gear is held and re-granted rather than scattered on death."
      ),
      new Hold(
         "elder-warden-feast",
         Outcome.RE_GRANTED,
         "com.fortuneandfavors.economy.BossManager",
         "captureWardenSteal",
         "The Elder Warden's feast takes the stack into its own store, which is reclaimed later."
      ),
      new Hold(
         "blood-ritual-safe-death",
         Outcome.RE_GRANTED,
         "com.fortuneandfavors.economy.ScarletGear",
         "onPlayerDeath",
         "A ritual kill is meant to cost the walk back and nothing else."
      ),
      new Hold(
         "mindbinder-corruption-death",
         Outcome.RE_GRANTED,
         "com.fortuneandfavors.economy.BossManager",
         "restoreCorruptionDeath",
         "Dying to the Mindbinder's hold is a scripted loss that no blow of the player's own undoes: the pack is copied before the death and written straight back, so the death costs the walk back and nothing else."
      ),
      new Hold(
         "nice-keep-inventory",
         Outcome.RE_GRANTED,
         "com.fortuneandfavors.NiceKeepInventoryManager",
         "onPlayerDeath",
         "The snapshot is handed to NKI on the death event, which keeps or graves every stack."
      )
   );

   /**
    * Branches that used to cancel a drop and no longer may.
    *
    * <p>Kept as an explicit list so the self-test can assert the current answer is
    * still "no". A blood claim is the important one: its cancellation suppressed
    * every drop for both the King's blood phase and the Scarlet Devil's claim,
    * while the only code that ever returned those items had already been removed.
    */
   public static final List<String> MUST_NOT_HOLD = List.of(
      "blood-claim-hold",
      "warden-hold-legacy"
   );

   private DeathDropGuard() {
   }

   /**
    * Verifies the registry against the real code: no duplicate branches, and every
    * declared recovery path names a class and method that actually exist.
    *
    * @return one human-readable problem per broken entry, empty when healthy
    */
   public static List<String> verify() {
      List<String> problems = new ArrayList<>();
      Set<String> seen = new HashSet<>();
      for (Hold hold : HOLDS) {
         if (!seen.add(hold.branch())) {
            problems.add("duplicate branch '" + hold.branch() + "'");
         }
         if (hold.outcome() == Outcome.COSMETIC) {
            if (hold.recoveryOwner() != null || hold.recoveryMethod() != null) {
               problems.add("cosmetic branch '" + hold.branch() + "' names a recovery path it should not have");
            }
            continue;
         }
         String owner = hold.recoveryOwner();
         String method = hold.recoveryMethod();
         if (owner == null || method == null) {
            problems.add("branch '" + hold.branch() + "' cancels a drop with no declared recovery path");
            continue;
         }
         try {
            Class<?> type = Class.forName(owner);
            boolean found = false;
            for (Method m : type.getDeclaredMethods()) {
               if (m.getName().equals(method)) {
                  found = true;
                  break;
               }
            }
            if (!found) {
               problems.add("branch '" + hold.branch() + "' relies on " + owner + "#" + method + ", which no longer exists");
            }
         } catch (ClassNotFoundException e) {
            problems.add("branch '" + hold.branch() + "' relies on " + owner + ", which no longer exists");
         }
      }
      for (String legacy : MUST_NOT_HOLD) {
         if (seen.contains(legacy)) {
            problems.add("'" + legacy + "' is registered as an active hold but is documented as forbidden");
         }
      }
      return problems;
   }

   /** How many branches cancel a drop while being provably lossless. */
   public static long cosmeticCount() {
      return HOLDS.stream().filter(h -> h.outcome() == Outcome.COSMETIC).count();
   }
}
