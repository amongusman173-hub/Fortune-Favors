package com.fortuneandfavors.anticheat;

import com.fortuneandfavors.util.JsonUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;

/**
 * Section 22: what a client <i>says</i> it is running, kept strictly as context.
 *
 * <p>The specification spends a whole section on not becoming "mod police", and this class
 * is where that instruction is turned into code rather than a comment. The whole of it is
 * three sentences long:
 *
 * <ul>
 *   <li><b>Nothing here can create a violation.</b> There is no call to {@link AntiCheat}
 *       in this file, no violation level, no alert, no enforcement. It has no access to any
 *       of them, so "a mod name is never evidence" is not a rule somebody has to remember
 *       while editing this - it is the only thing the code is able to do.</li>
 *   <li><b>Absent is not suspicious.</b> A client that reports nothing is {@code unknown},
 *       and unknown is never treated as a signal, positively or negatively. Most players
 *       will never send this at all, because most players are not running this mod.</li>
 *   <li><b>A claim is not a fact.</b> A cheat client can call itself anything, including
 *       the name of a quality-of-life mod, so the value here is entirely in explaining a
 *       finding somebody else already made. What a moderator gets is a line that says "the
 *       client reported Accurate Block Placement", which is a useful thing to read next to
 *       a scaffold finding and a useless thing to act on alone.</li>
 * </ul>
 *
 * <p>Where it is read, therefore, is exactly two places: the staff readout
 * ({@code /ff anticheat info} and {@code /ff anticheat simulate}), which is a human
 * deciding something, and {@link #noteFor}, which appends to the evidence of a violation
 * that has already been raised on independent grounds.
 */
public final class ClientContext {
   /** Cap on how many mod ids are kept, so a hostile client cannot grow a file with one packet. */
   private static final int MAX_MODS = 128;
   /** Cap on the length of any single id, for the same reason. */
   private static final int MAX_ID = 96;
   /** How many distinct players' reports are kept before the oldest are dropped. */
   private static final int MAX_REPORTS = 512;

   /**
    * Quality-of-life mods that correspond to a compatibility mode this package already has.
    *
    * <p>Matched loosely, because nobody's mod id is what they think it is. This mapping is
    * used to point a moderator at the RIGHT {@link QolCompat} entry when they are looking at
    * a finding, and for nothing else - it cannot switch a mode on, because modes are a server
    * decision and a client must not be able to make one.
    */
   private static final Map<String, String> QOL_MODS = Map.of(
      "accurateblockplacement",
      QolCompat.ABP,
      "accurateblockplacement_reborn",
      QolCompat.ABP,
      "mousetweaks",
      QolCompat.QUICK_INVENTORY,
      "inventorywalk",
      QolCompat.INVENTORY_WALK
   );

   /**
    * Names of well-known cheat clients, used for one thing only: the wording of the readout.
    *
    * <p>This list is <b>not</b> evidence and must never be made into any. It exists so a
    * moderator reading {@code /ff anticheat info} sees the client's own words rather than
    * having to recognise them, and the readout says out loud that a renamed client defeats
    * it. A list like this being load-bearing is how an anticheat becomes a name-matching
    * exercise that catches nothing and bans people who kept a friend's config.
    */
   private static final List<String> CHEAT_HINT_MODS = List.of(
      "wurst", "meteor", "meteorclient", "impact", "aristois", "future", "sigma", "lambda", "bleachhack", "baritone"
   );

   private static final Map<UUID, Report> REPORTS = new LinkedHashMap<>();

   /**
    * One client's self-description.
    *
    * @param protocol the client's own protocol number, so a stale build is visible
    * @param mods the ids the client reported, lowercased and trimmed
    * @param at the wall-clock time it was reported, so staleness is visible too
    */
   public record Report(int protocol, List<String> mods, long at) {
   }

   private ClientContext() {
   }

   /** Records what a client said. Silently ignores anything that is not shaped like an answer. */
   public static void record(ServerPlayer player, int protocol, String json) {
      if (player == null || json == null) {
         return;
      }
      List<String> mods = parse(json);
      REPORTS.remove(player.getUUID());
      REPORTS.put(player.getUUID(), new Report(protocol, mods, System.currentTimeMillis()));
      while (REPORTS.size() > MAX_REPORTS) {
         UUID oldest = REPORTS.keySet().iterator().next();
         REPORTS.remove(oldest);
      }
   }

   /** Drops one player's report - called on logout, because it is not worth persisting. */
   public static void forget(UUID id) {
      if (id != null) {
         REPORTS.remove(id);
      }
   }

   public static void clear() {
      REPORTS.clear();
   }

   /** Reads the {@code mods} array out of the payload, bounded and normalised. */
   public static List<String> parse(String json) {
      Set<String> out = new LinkedHashSet<>();
      try {
         JsonObject root = (JsonObject)JsonUtil.gson().fromJson(json, JsonObject.class);
         if (root == null || !root.has("mods") || !root.get("mods").isJsonArray()) {
            return List.of();
         }
         JsonArray array = root.getAsJsonArray("mods");
         for (JsonElement element : array) {
            if (out.size() >= MAX_MODS) {
               break;
            }
            if (!element.isJsonPrimitive()) {
               continue;
            }
            String id = element.getAsString();
            if (id == null) {
               continue;
            }
            id = id.trim().toLowerCase(java.util.Locale.ROOT);
            if (!id.isEmpty() && id.length() <= MAX_ID) {
               out.add(id);
            }
         }
      } catch (Throwable ignored) {
      }
      return new ArrayList<>(out);
   }

   /** The ids this player's client reported, or an empty list when it said nothing. */
   public static List<String> declaredMods(UUID id) {
      Report report = id == null ? null : REPORTS.get(id);
      return report == null ? List.of() : report.mods();
   }

   /** True when the client told us anything at all. Unknown and clean are different answers. */
   public static boolean known(UUID id) {
      return id != null && REPORTS.containsKey(id);
   }

   /**
    * The compatibility entries this client's own words point at.
    *
    * <p>Returned so a moderator can be told which allowance is <i>already</i> in force while
    * they read a finding. It does not turn anything on: whether the allowance is on is the
    * server's {@link QolCompat#on(String)}, and always will be.
    */
   public static List<String> compatHints(UUID id) {
      Set<String> out = new LinkedHashSet<>();
      for (String mod : declaredMods(id)) {
         for (Map.Entry<String, String> entry : QOL_MODS.entrySet()) {
            if (mod.contains(entry.getKey())) {
               out.add(entry.getValue());
            }
         }
      }
      return new ArrayList<>(out);
   }

   /** The ids that read as a known cheat client's name. Wording only - see the field. */
   public static List<String> cheatHints(UUID id) {
      List<String> out = new ArrayList<>();
      for (String mod : declaredMods(id)) {
         for (String hint : CHEAT_HINT_MODS) {
            if (mod.contains(hint)) {
               out.add(mod);
               break;
            }
         }
      }
      return out;
   }

   /**
    * A note to hang on a violation that other checks have already raised.
    *
    * <p>Returns an empty string when there is nothing worth saying, which is the common case:
    * most players never send this, and a finding should not be padded out with "the client
    * said nothing", because that reads as suspicious to a moderator and is not.
    */
   public static String noteFor(UUID id) {
      if (!known(id)) {
         return "";
      }
      List<String> mods = declaredMods(id);
      if (mods.isEmpty()) {
         return "client reported no mods";
      }
      List<String> hints = cheatHints(id);
      if (hints.isEmpty()) {
         return "client mods: " + mods.size() + " reported";
      }
      // Naming a client's alleged purpose is context for a human, and is labelled as exactly
      // that. A misspelled or renamed client defeats it, which is why it decides nothing.
      return "client mods: " + mods.size() + " reported, one of which claims to be " + hints.get(0);
   }

   /** The lines {@code /ff anticheat info} and {@code /ff anticheat simulate} print. */
   public static List<String> describe(ServerPlayer player) {
      List<String> out = new ArrayList<>();
      if (player == null) {
         return out;
      }
      UUID id = player.getUUID();
      if (!known(id)) {
         out.add("&7Client context: &8unknown &7(no self-report; not a signal either way)");
         return out;
      }
      Report report = REPORTS.get(id);
      List<String> mods = report.mods();
      out.add("&7Client context: &f" + mods.size() + " mod(s) reported, protocol &f" + report.protocol());
      if (!mods.isEmpty()) {
         out.add("&8    " + String.join("&7, &8", mods.size() > 12 ? mods.subList(0, 12) : mods) + (mods.size() > 12 ? "&7, ..." : ""));
      }
      List<String> hints = compatHints(id);
      if (!hints.isEmpty()) {
         out.add(
            "&8    points at allowances: &7"
               + String.join("&8, &7", hints)
               + " &8(&7each is "
               + (hints.stream().allMatch(QolCompat::on) ? "&aon" : "&cpartly off")
               + "&8 on this server)"
         );
      }
      List<String> cheat = cheatHints(id);
      if (!cheat.isEmpty()) {
         out.add("&8    &creturns a name associated with a cheat client: &7" + cheat.get(0));
      }
      out.add(
         "&8    &7Context only. This cannot raise a violation, and a client can rename itself - "
            + "&8what a moderator does with this is a human decision."
      );
      return out;
   }

   /** True when the client's report is older than the player's own session, i.e. stale. */
   public static boolean stale(UUID id, long nowMillis) {
      Report report = id == null ? null : REPORTS.get(id);
      return report != null && nowMillis - report.at() > 5L * 60L * 1000L;
   }
}
