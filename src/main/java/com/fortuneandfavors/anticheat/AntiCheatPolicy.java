package com.fortuneandfavors.anticheat;

import com.fortuneandfavors.economy.EconomyManager;
import com.fortuneandfavors.util.JsonUtil;
import com.fortuneandfavors.util.Safe;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.server.MinecraftServer;

/**
 * The anticheat's <b>policy</b> dials - how loudly it reacts - separated from its
 * <b>physics</b> constants, which stay compiled in.
 *
 * <p>That split is the whole point of this class, and it is a deliberate answer to
 * the specification's own priority order. Two very different kinds of number live in
 * this module:
 *
 * <ul>
 *   <li><b>Physics.</b> How far a hit may reach, how much faster than friction a
 *       player may move, how close to their own feet a block has to land to count as
 *       scaffolding. These decide whether an event is <i>possible</i>. Set one of them
 *       too tight and legitimate players get flagged - which the specification ranks
 *       as the single worst outcome the module can produce. They are pinned by the
 *       self-test, they are not exposed to commands, and a server owner cannot move
 *       them from inside the game.</li>
 *   <li><b>Policy.</b> How much evidence is enough to alert staff, how much to act,
 *       how fast a violation level decays, how long the cooldowns are. These decide
 *       what to <i>do</i> about something already judged impossible, and they are
 *       genuinely server taste: a survival server and a PvP server should not share
 *       them.</li>
 * </ul>
 *
 * <p>So this file is everything that is safe to tune, and nothing that is not. Every
 * value is clamped to a range wide enough to be useful and narrow enough that no
 * setting can make the module punish somebody it would otherwise have cleared: the
 * lowest an enforcement threshold can go is still far above the level a single
 * ambiguous event produces, and the decay can be slowed but never stopped.
 *
 * <p>Persistence is its own file rather than a corner of the main config, because
 * the main config is hand-edited and a mistyped policy is not a syntax error - it is
 * a server that quietly stopped enforcing. Keeping them apart means the tuning file
 * can be deleted to reset, and a bad value is clamped on load rather than trusted.
 */
public final class AntiCheatPolicy {
   // --------------------------------------------------------------------- keys

   /** Confidence a finding needs before it alerts staff, 0..1. */
   public static final String ALERT_CONFIDENCE = "alert-confidence";
   /** Violation level a check needs before it alerts staff. */
   public static final String ALERT_VL = "alert-vl";
   /** Violation level a check needs before the failsafe acts on it. */
   public static final String ENFORCE_VL = "enforce-vl";
   /** The ceiling a violation level can climb to. */
   public static final String VL_MAX = "vl-max";
   /** Violation level lost per tick, so an old finding stops mattering. */
   public static final String VL_DECAY = "vl-decay";
   /** Ticks between alerts about the same player. */
   public static final String ALERT_COOLDOWN = "alert-cooldown";
   /** Ticks between automatic enforcement actions on the same player. */
   public static final String ENFORCE_COOLDOWN = "enforce-cooldown";
   /** Ticks between position setbacks for the same player. */
   public static final String SETBACK_COOLDOWN = "setback-cooldown";
   /**
    * Violation level a <i>pattern</i> check needs before it may move a body at all.
    *
    * <p>Deliberately a separate dial from {@link #ENFORCE_VL}, and deliberately a
    * higher bar than the alert one. Alerts are read by a person; corrections happen to
    * a player who is playing. That asymmetry is the whole reason this exists: a finding
    * worth telling staff about is not automatically a finding worth teleporting someone
    * over, and conflating the two is what makes an anticheat feel broken from the
    * inside - which is exactly the complaint this dial was added to answer.
    *
    * <p>The floor is 18, which is more than two of the loudest ambiguous events can
    * produce together, so no setting of this or any other dial can turn one bad second
    * into a rubber-band. Raising it is always allowed; there is no value of it that
    * makes the module less careful than it already is.
    */
   public static final String CORRECT_VL = "correct-vl";

   /** Every dial, in the order the command lists them. */
   public static final List<String> KEYS = List.of(
      ALERT_CONFIDENCE,
      ALERT_VL,
      ENFORCE_VL,
      CORRECT_VL,
      VL_MAX,
      VL_DECAY,
      ALERT_COOLDOWN,
      ENFORCE_COOLDOWN,
      SETBACK_COOLDOWN
   );

   /**
    * One dial: what it defaults to, the range it may be set to, and what it does.
    *
    * <p>The ranges are the guarantee. {@link #ENFORCE_VL} bottoms out at 14, which is
    * more than a single ambiguous event can produce, so no setting of any dial can
    * turn one wobbly second into a kick on its own.
    */
   public record Dial(String key, double fallback, double min, double max, boolean whole, String label, String note) {
      /** Formats a value the way the command and the readout want it. */
      public String show(double value) {
         return this.whole ? Long.toString(Math.round(value)) : String.format("%.3f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
      }
   }

   private static final Map<String, Dial> DIALS = new LinkedHashMap<>();

   static {
      add(new Dial(ALERT_CONFIDENCE, 0.55, 0.05, 0.99, false,
         "alert confidence", "how sure a finding must be before staff hear about it"));
      add(new Dial(ALERT_VL, 12.0, 2.0, 40.0, false,
         "alert level", "violation level that alerts staff"));
      add(new Dial(ENFORCE_VL, 30.0, 14.0, 120.0, false,
         "enforce level", "violation level the failsafe acts on"));
      add(new Dial(CORRECT_VL, 18.0, 18.0, 200.0, false,
         "correct level", "violation level a pattern check needs before it may move a body at all"));
      add(new Dial(VL_MAX, 40.0, 20.0, 200.0, false,
         "level ceiling", "highest a violation level may climb"));
      add(new Dial(VL_DECAY, 0.02, 0.002, 0.25, false,
         "level decay", "level lost per tick, so old findings fade"));
      add(new Dial(ALERT_COOLDOWN, 400.0, 40.0, 24000.0, true,
         "alert cooldown", "ticks between alerts about one player"));
      add(new Dial(ENFORCE_COOLDOWN, 1200.0, 200.0, 72000.0, true,
         "enforce cooldown", "ticks between automatic actions on one player"));
      add(new Dial(SETBACK_COOLDOWN, 40.0, 10.0, 600.0, true,
         "setback cooldown", "ticks between position setbacks"));
   }

   private AntiCheatPolicy() {
   }

   private static void add(Dial dial) {
      DIALS.put(dial.key(), dial);
   }

   // ------------------------------------------------------------------- state

   private static final Map<String, Double> VALUES = new LinkedHashMap<>();
   private static Path dataFile;
   private static boolean dirty;

   /** Loaded, clamped, and a bad value is never trusted - see {@link #set}. */
   public static void load(MinecraftServer server) {
      VALUES.clear();
      dirty = false;
      dataFile = EconomyManager.getDataDir(server).resolve("anticheat_policy.json");
      try {
         JsonObject root = JsonUtil.readOrCreate(dataFile, defaults());
         for (Dial dial : DIALS.values()) {
            VALUES.put(dial.key(), clamp(dial, JsonUtil.jsonDouble(root, dial.key(), dial.fallback())));
         }
      } catch (Throwable t) {
         // A policy file that cannot be read is not a reason to stop enforcing - it is
         // a reason to enforce the way the build was tested. Defaults, not zero.
         for (Dial dial : DIALS.values()) {
            VALUES.put(dial.key(), dial.fallback());
         }
      }
   }

   public static void save(MinecraftServer server) {
      if (!dirty || dataFile == null) {
         return;
      }
      Safe.run("anticheat policy save", () -> {
         JsonObject root = new JsonObject();
         for (Map.Entry<String, Double> entry : VALUES.entrySet()) {
            root.addProperty(entry.getKey(), entry.getValue());
         }
         if (JsonUtil.write(dataFile, root)) {
            dirty = false;
         }
      });
   }

   private static JsonObject defaults() {
      JsonObject root = new JsonObject();
      for (Dial dial : DIALS.values()) {
         root.addProperty(dial.key(), dial.fallback());
      }
      return root;
   }

   /** The current values, for a command or a readout that wants all of them. */
   public static JsonObject snapshot() {
      JsonObject root = new JsonObject();
      for (String key : KEYS) {
         root.addProperty(key, get(key));
      }
      return root;
   }

   // ------------------------------------------------------------------ access

   public static Dial dial(String key) {
      return DIALS.get(key);
   }

   /** The live value, falling back to the tested default in every failure mode. */
   public static double get(String key) {
      Dial dial = DIALS.get(key);
      if (dial == null) {
         return 0.0;
      }
      Double live = VALUES.get(key);
      return live == null ? dial.fallback() : live;
   }

   /**
    * Sets a dial, clamped into its safe range.
    *
    * @return the value actually stored - never the one asked for, if the two differ,
    *         so a caller cannot report a change the module did not make
    */
   public static double set(String key, double value) {
      Dial dial = DIALS.get(key);
      if (dial == null) {
         return 0.0;
      }
      double clamped = clamp(dial, value);
      VALUES.put(key, clamped);
      dirty = true;
      return clamped;
   }

   /** Resets one dial, or every dial when the key is unknown or {@code "all"}. */
   public static boolean reset(String key) {
      if (key == null || key.equals("all") || !DIALS.containsKey(key)) {
         for (Dial dial : DIALS.values()) {
            VALUES.put(dial.key(), dial.fallback());
         }
         dirty = true;
         return true;
      }
      VALUES.put(key, DIALS.get(key).fallback());
      dirty = true;
      return true;
   }

   private static double clamp(Dial dial, double value) {
      if (Double.isNaN(value) || Double.isInfinite(value)) {
         return dial.fallback();
      }
      double bounded = Math.max(dial.min(), Math.min(dial.max(), value));
      return dial.whole() ? Math.rint(bounded) : bounded;
   }

   // ----------------------------------------------------------- typed readers

   public static double alertConfidence() {
      return get(ALERT_CONFIDENCE);
   }

   public static double alertLevel() {
      return get(ALERT_VL);
   }

   public static double enforceLevel() {
      return get(ENFORCE_VL);
   }

   /**
    * The level a pattern check needs before it may move a body.
    *
    * <p>Separate from {@link #enforceLevel} because the two answer different questions
    * - "is this worth a kick" and "is this worth touching the player mid-stride" - and
    * the second one has to be the more careful of the two.
    */
   public static double correctLevel() {
      return get(CORRECT_VL);
   }

   public static double levelCeiling() {
      return get(VL_MAX);
   }

   public static double levelDecay() {
      return get(VL_DECAY);
   }

   public static long alertCooldown() {
      return Math.round(get(ALERT_COOLDOWN));
   }

   public static long enforceCooldown() {
      return Math.round(get(ENFORCE_COOLDOWN));
   }

   public static long setbackCooldown() {
      return Math.round(get(SETBACK_COOLDOWN));
   }

   /**
    * The dials as {@code key = value} lines, each with what it does.
    *
    * <p>The note matters as much as the number: a dial whose effect is not written
    * down next to it is a dial somebody will move the wrong way during an incident.
    */
   public static String describe() {
      StringBuilder out = new StringBuilder();
      for (Dial dial : DIALS.values()) {
         out.append("&7").append(dial.key()).append(" &8= &f").append(dial.show(get(dial.key())));
         out.append(" &8(&7").append(dial.show(dial.min())).append("&8-&7").append(dial.show(dial.max())).append("&8)");
         out.append(" &8- &7").append(dial.note()).append("\n");
      }
      return out.toString();
   }

   /** True when a dial was moved off the value this build was tested with. */
   public static boolean isCustom() {
      for (Dial dial : DIALS.values()) {
         if (Math.abs(get(dial.key()) - dial.fallback()) > 1.0E-9) {
            return true;
         }
      }
      return false;
   }

   /** Any key that is not a dial, so a caller can say so rather than silently ignore. */
   public static boolean knows(String key) {
      return DIALS.containsKey(key);
   }

   /** Every key, for a test that checks the exposed surface against the spec's split. */
   public static List<String> keys() {
      return KEYS;
   }

   /** Reads one value from a JSON object -- used by the self-test's round trip. */
   public static double readFrom(JsonObject root, String key) {
      Dial dial = DIALS.get(key);
      if (dial == null) {
         return 0.0;
      }
      JsonElement element = root.get(key);
      return element == null ? dial.fallback() : JsonUtil.jsonDouble(root, key, dial.fallback());
   }
}
