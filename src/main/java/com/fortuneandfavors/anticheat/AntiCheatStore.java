package com.fortuneandfavors.anticheat;

import com.fortuneandfavors.FortuneFavorsMod;
import com.fortuneandfavors.economy.EconomyManager;
import com.fortuneandfavors.util.JsonUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Everything the anticheat remembers about a player between sessions, and the
 * enforcement ladder that acts on it.
 *
 * <p>Three separate things live here, and they are worth keeping apart:
 *
 * <ul>
 *   <li><b>History.</b> How many times each check has failed and what it said,
 *       per player, surviving restarts. Nothing acts on this on its own - it is
 *       what staff read when they open the moderation screen, and it is the
 *       reason "flagged twice a week apart" is visible as two events rather than
 *       as nothing.
 *   <li><b>The daily ladder.</b> A trigger count and an enforcement stage that
 *       reset at <b>noon in New York</b> - {@link #RESET_ZONE} by id, not a fixed
 *       offset, because Eastern time observes daylight saving and a server that
 *       assumes UTC-5 punishes in the wrong hours half the year. Only the
 *       automatic half resets; history, punishments and reviews never do.
 *   <li><b>Exemptions.</b> Time-boxed permission to do something that would
 *       otherwise be flagged - a boss dash, a scripted launch, a staff member
 *       testing. Deliberately awkward to make permanent: they expire, they are
 *       logged with who granted them and why, they are visible on the moderation
 *       screen, and they are checked every time rather than latched.
 * </ul>
 *
 * <p><b>Nothing here punishes anyone by itself.</b> The ladder is only climbed
 * from {@link AntiCheat}, only at high confidence, and only when the failsafe
 * switch is on and no moderator is online - see {@link #stage(ServerPlayer)}.
 */
public final class AntiCheatStore {
   /**
    * The zone the day boundary is read from. Eastern time observes daylight
    * saving, so this is the id and never an offset.
    */
   public static final ZoneId RESET_ZONE = ZoneId.of("America/New_York");
   /** The hour the automatic ladder resets, local time. */
   public static final int RESET_HOUR = 12;
   /** How long a timeout costs. */
   public static final long TIMEOUT_MILLIS = 5L * 60_000L;
   /**
    * The trip that costs five minutes, and every trip after it.
    *
    * <p>Three kicks, then a timeout: a player gets the benefit of the doubt twice
    * more than once, because a server with custom movement, dashes, launches and
    * bosses is exactly the sort of place where the anticheat can be wrong - and
    * "the anticheat is too dumb" is a thing the first two kick lines admit out
    * loud. Every trip after the fourth costs the same five minutes, and the whole
    * ladder resets at noon New York; nothing here ever bans.
    */
   public static final int TIMEOUT_STAGE = 4;

   public static final String KICK_FIRST = "attempted hacking OR the anticheat is too dumb";
   public static final String KICK_SECOND =
      "stop hacking or youll be timed out, if this is a persistant issue and your NOT hacking then check with an admin before rejoining";
   public static final String KICK_THIRD =
      "last warning - attempted hacking OR the anticheat is too dumb, the next one is a 5 minute timeout";
   public static final String KICK_TIMEOUT = "Attempted Hacking, message a admin if this was a mistake";

   /**
    * What a trip costs, in words. Stage 1, 2 and 3 are kicks with their own line;
    * stage 4 and beyond are the timeout, which says how long is left.
    */
   public static String kickText(int stage) {
      return kickText(stage, null);
   }

   /**
    * The kick line, and the finding that produced it.
    *
    * <p>"Attempted hacking OR the anticheat is too dumb" is an honest sentence and a useless
    * one: a player removed from the game by a threshold they cannot see has no way to tell a
    * false positive from a real one, and neither does the admin they complain to - "I do not
    * know what I am getting kicked for" is what that costs, and it is the whole reason this
    * overload exists. The check and what it measured travel with the kick now, so the
    * disconnect screen names the finding instead of only that there was one.
    */
   public static String kickText(int stage, String evidence) {
      String base = switch (stage) {
         case 1 -> KICK_FIRST;
         case 2 -> KICK_SECOND;
         default -> KICK_THIRD;
      };
      return withEvidence(base, evidence);
   }

   /** The timeout line, plus the finding, plus how long is left - see {@link #kickText(int, String)}. */
   public static String timeoutText(String evidence, long until) {
      long minutes = Math.max(1L, (until - System.currentTimeMillis() + 59_999L) / 60_000L);
      return withEvidence(KICK_TIMEOUT + " (" + minutes + " more minute" + (minutes == 1L ? "" : "s") + ")", evidence);
   }

   private static String withEvidence(String base, String evidence) {
      if (evidence == null || evidence.isBlank()) {
         return base;
      }
      return base + "\n\nFlagged: " + evidence;
   }

   /** One entry in a player's punishment history. */
   public record Punishment(String type, String reason, String by, long at, long until) {
      public boolean active(long now) {
         return this.until <= 0L || this.until > now;
      }
   }

   /** A moderator's verdict on a check firing. Evidence is never erased by one. */
   public record Review(String check, boolean falsePositive, String by, long at, String note) {
   }

   /** Permission to do something the checks would otherwise judge. */
   public record Exemption(String check, long until, String reason, String by) {
      public boolean active(long now) {
         return this.until > now;
      }
   }

   /**
    * Where a moderator was before they started spectating a player.
    *
    * <p>Kept in the file rather than in memory on purpose: a restart is the one
    * moment staff cannot fix a stranded moderator by hand, so the snapshot has to
    * outlive the process that made it.
    */
   public record Spectate(String name, String dimension, double x, double y, double z, float yaw, float pitch, String mode) {
   }

   /** One player's record. */
   public static final class Record {
      public final UUID id;
      public String name = "?";
      public int dailyTriggers;
      public int stage;
      public long timeoutUntil;
      public long lastSeen;
      /** Every failure ever counted, per check. */
      public final Map<String, Integer> counts = new LinkedHashMap<>();
      /** The last violation level each check reached, for the moderation screen. */
      public final Map<String, Double> levels = new LinkedHashMap<>();
      /** The last thing each check said, so the screen can show it after a restart. */
      public final Map<String, String> lastDetail = new LinkedHashMap<>();
      public final List<Punishment> punishments = new ArrayList<>();
      public final List<Review> reviews = new ArrayList<>();
      public final List<Exemption> exemptions = new ArrayList<>();

      private Record(UUID id) {
         this.id = id;
      }

      public boolean timedOut(long now) {
         return this.timeoutUntil > now;
      }

      /** The most recent punishment that is still in force, or null. */
      public Punishment activePunishment(long now) {
         Punishment found = null;
         for (Punishment p : this.punishments) {
            if (p.active(now) && (found == null || p.at() > found.at())) {
               found = p;
            }
         }
         return found;
      }

      public int total() {
         int sum = 0;
         for (int count : this.counts.values()) {
            sum += count;
         }
         return sum;
      }

      /** The worst violation level on the record, which is how the list sorts. */
      public double worstLevel() {
         double worst = 0.0;
         for (double level : this.levels.values()) {
            worst = Math.max(worst, level);
         }
         return worst;
      }
   }

   private static final Map<UUID, Record> records = new HashMap<>();
   private static final Map<UUID, Spectate> spectating = new HashMap<>();
   private static Path dataFile;
   private static boolean loadHealthy = true;
   private static boolean dirty;
   private static boolean alerts = true;
   /** The local date the trigger count belongs to. */
   private static String day = "";

   private AntiCheatStore() {
   }

   // -------------------------------------------------------------- day + zone

   /**
    * The day a moment belongs to, where a day begins at
    * {@link #RESET_HOUR} local time rather than at midnight. Subtracting the
    * reset hour from the wall clock and taking the date is the whole trick: at
    * 11:00 it still reads yesterday, and at 13:00 it reads today.
    */
   public static LocalDate dayOf(long millis) {
      return Instant.ofEpochMilli(millis).atZone(RESET_ZONE).minusHours(RESET_HOUR).toLocalDate();
   }

   /** Milliseconds until the next automatic reset, for the moderation screen. */
   public static long millisUntilReset(long now) {
      Instant next = dayOf(now).plusDays(1).atStartOfDay(RESET_ZONE).plusHours(RESET_HOUR).toInstant();
      return Math.max(0L, next.toEpochMilli() - now);
   }

   // ------------------------------------------------------------------ config

   /** Whether high-confidence findings are broadcast to staff at all. */
   public static boolean alerts() {
      return alerts;
   }

   public static void setAlerts(boolean on) {
      alerts = on;
      dirty = true;
   }

   public static boolean isDirty() {
      return dirty;
   }

   // ------------------------------------------------------------------- load

   public static void load(MinecraftServer server) {
      records.clear();
      dataFile = EconomyManager.getDataDir(server).resolve("anticheat.json");
      loadHealthy = true;
      day = dayOf(System.currentTimeMillis()).toString();

      try {
         JsonObject root = JsonUtil.readOrCreate(dataFile, new JsonObject());
         alerts = JsonUtil.jsonBool(root, "alerts", true);
         day = JsonUtil.jsonString(root, "day", day);
         if (root.has("players") && root.get("players").isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("players").entrySet()) {
               try {
                  UUID id = UUID.fromString(entry.getKey());
                  Record record = new Record(id);
                  read(record, entry.getValue().getAsJsonObject());
                  records.put(id, record);
               } catch (Exception e) {
                  FortuneFavorsMod.LOGGER.warn("Skipped an unreadable anticheat record for {}", entry.getKey());
               }
            }
         }
         spectating.clear();
         if (root.has("spectating") && root.get("spectating").isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("spectating").entrySet()) {
               try {
                  readSpectate(UUID.fromString(entry.getKey()), entry.getValue().getAsJsonObject());
               } catch (Exception e) {
                  FortuneFavorsMod.LOGGER.warn("Skipped an unreadable spectate snapshot for {}", entry.getKey());
               }
            }
         }
         // A restart across the reset hour has to start the day over, not carry
         // yesterday's strikes into today.
         rollDay(System.currentTimeMillis());
      } catch (Throwable t) {
         loadHealthy = false;
         FortuneFavorsMod.LOGGER.error("Fortune & Favors: could not read anticheat.json - it will not be overwritten", t);
      }
   }

   private static void read(Record record, JsonObject obj) {
      record.name = JsonUtil.jsonString(obj, "name", "?");
      record.dailyTriggers = (int)JsonUtil.jsonLong(obj, "daily_triggers", 0L);
      record.stage = (int)JsonUtil.jsonLong(obj, "stage", 0L);
      record.timeoutUntil = JsonUtil.jsonLong(obj, "timeout_until", 0L);
      record.lastSeen = JsonUtil.jsonLong(obj, "last_seen", 0L);
      readCounts(record.counts, obj, "counts");
      readLevels(record.levels, obj, "levels");
      readDetails(record.lastDetail, obj, "last_detail");

      if (obj.has("punishments") && obj.get("punishments").isJsonArray()) {
         for (JsonElement el : obj.getAsJsonArray("punishments")) {
            JsonObject p = el.getAsJsonObject();
            record.punishments.add(
               new Punishment(
                  JsonUtil.jsonString(p, "type", "timeout"),
                  JsonUtil.jsonString(p, "reason", ""),
                  JsonUtil.jsonString(p, "by", "?"),
                  JsonUtil.jsonLong(p, "at", 0L),
                  JsonUtil.jsonLong(p, "until", 0L)
               )
            );
         }
      }

      if (obj.has("reviews") && obj.get("reviews").isJsonArray()) {
         for (JsonElement el : obj.getAsJsonArray("reviews")) {
            JsonObject r = el.getAsJsonObject();
            record.reviews.add(
               new Review(
                  JsonUtil.jsonString(r, "check", "?"),
                  JsonUtil.jsonBool(r, "false_positive", false),
                  JsonUtil.jsonString(r, "by", "?"),
                  JsonUtil.jsonLong(r, "at", 0L),
                  JsonUtil.jsonString(r, "note", "")
               )
            );
         }
      }

      if (obj.has("exemptions") && obj.get("exemptions").isJsonArray()) {
         for (JsonElement el : obj.getAsJsonArray("exemptions")) {
            JsonObject x = el.getAsJsonObject();
            record.exemptions.add(
               new Exemption(
                  JsonUtil.jsonString(x, "check", "*"),
                  JsonUtil.jsonLong(x, "until", 0L),
                  JsonUtil.jsonString(x, "reason", ""),
                  JsonUtil.jsonString(x, "by", "?")
               )
            );
         }
      }
   }

   /** Reads one spectate snapshot, dropping it if it is malformed. */
   private static void readSpectate(UUID id, JsonObject obj) {
      spectating.put(
         id,
         new Spectate(
            JsonUtil.jsonString(obj, "name", "?"),
            JsonUtil.jsonString(obj, "dimension", "minecraft:overworld"),
            obj.has("x") ? obj.get("x").getAsDouble() : 0.0,
            obj.has("y") ? obj.get("y").getAsDouble() : 64.0,
            obj.has("z") ? obj.get("z").getAsDouble() : 0.0,
            obj.has("yaw") ? obj.get("yaw").getAsFloat() : 0.0F,
            obj.has("pitch") ? obj.get("pitch").getAsFloat() : 0.0F,
            JsonUtil.jsonString(obj, "mode", "survival")
         )
      );
   }

   private static void readCounts(Map<String, Integer> into, JsonObject obj, String key) {
      if (obj.has(key) && obj.get(key).isJsonObject()) {
         for (Map.Entry<String, JsonElement> e : obj.getAsJsonObject(key).entrySet()) {
            try {
               into.put(e.getKey(), e.getValue().getAsInt());
            } catch (Exception ignored) {
            }
         }
      }
   }

   private static void readLevels(Map<String, Double> into, JsonObject obj, String key) {
      if (obj.has(key) && obj.get(key).isJsonObject()) {
         for (Map.Entry<String, JsonElement> e : obj.getAsJsonObject(key).entrySet()) {
            try {
               into.put(e.getKey(), e.getValue().getAsDouble());
            } catch (Exception ignored) {
            }
         }
      }
   }

   private static void readDetails(Map<String, String> into, JsonObject obj, String key) {
      if (obj.has(key) && obj.get(key).isJsonObject()) {
         for (Map.Entry<String, JsonElement> e : obj.getAsJsonObject(key).entrySet()) {
            try {
               into.put(e.getKey(), e.getValue().getAsString());
            } catch (Exception ignored) {
            }
         }
      }
   }

   // ------------------------------------------------------------------- save

   public static boolean save(MinecraftServer server) {
      if (!loadHealthy) {
         FortuneFavorsMod.LOGGER.warn("Not saving anticheat history: the last load failed, so the on-disk file was left alone.");
         return false;
      }
      if (dataFile == null) {
         dataFile = EconomyManager.getDataDir(server).resolve("anticheat.json");
      }
      JsonObject root = new JsonObject();
      root.addProperty("alerts", alerts);
      root.addProperty("day", day);
      JsonObject players = new JsonObject();

      for (Record record : records.values()) {
         // A player with nothing on the record is not worth a file entry; their
         // exemptions still are, because an unpunished player can hold one.
         boolean interesting = record.total() > 0
            || !record.punishments.isEmpty()
            || !record.reviews.isEmpty()
            || !record.exemptions.isEmpty()
            || record.timedOut(System.currentTimeMillis());
         if (!interesting) {
            continue;
         }
         JsonObject obj = new JsonObject();
         obj.addProperty("name", record.name);
         obj.addProperty("daily_triggers", record.dailyTriggers);
         obj.addProperty("stage", record.stage);
         obj.addProperty("timeout_until", record.timeoutUntil);
         obj.addProperty("last_seen", record.lastSeen);
         obj.add("counts", toObject(record.counts));
         obj.add("levels", toObject(record.levels));
         JsonObject details = new JsonObject();

         for (Map.Entry<String, String> e : record.lastDetail.entrySet()) {
            details.addProperty(e.getKey(), e.getValue());
         }

         obj.add("last_detail", details);
         write(obj, record);
         players.add(record.id.toString(), obj);
      }

      root.add("players", players);
      JsonObject watching = new JsonObject();

      for (Map.Entry<UUID, Spectate> entry : spectating.entrySet()) {
         Spectate s = entry.getValue();
         JsonObject obj = new JsonObject();
         obj.addProperty("name", s.name());
         obj.addProperty("dimension", s.dimension());
         obj.addProperty("x", s.x());
         obj.addProperty("y", s.y());
         obj.addProperty("z", s.z());
         obj.addProperty("yaw", s.yaw());
         obj.addProperty("pitch", s.pitch());
         obj.addProperty("mode", s.mode());
         watching.add(entry.getKey().toString(), obj);
      }

      root.add("spectating", watching);
      boolean written = JsonUtil.write(dataFile, root);
      if (written) {
         dirty = false;
      } else {
         FortuneFavorsMod.LOGGER.error("Anticheat history was NOT saved to {}", dataFile);
      }
      return written;
   }

   private static <T extends Number> JsonObject toObject(Map<String, T> map) {
      JsonObject out = new JsonObject();

      for (Map.Entry<String, T> e : map.entrySet()) {
         out.addProperty(e.getKey(), e.getValue());
      }

      return out;
   }

   private static void writeList(JsonObject obj, String key, java.util.function.Consumer<JsonArray> filler) {
      JsonArray arr = new JsonArray();
      filler.accept(arr);
      obj.add(key, arr);
   }

   public static void clear() {
      records.clear();
      spectating.clear();
      day = dayOf(System.currentTimeMillis()).toString();
   }

   // -------------------------------------------------------------- spectating

   public static void setSpectate(UUID id, Spectate snapshot) {
      spectating.put(id, snapshot);
      dirty = true;
   }

   public static Spectate spectate(UUID id) {
      return spectating.get(id);
   }

   public static void clearSpectate(UUID id) {
      if (spectating.remove(id) != null) {
         dirty = true;
      }
   }

   /** A copy, so the caller cannot clear the map while walking it. */
   public static Map<UUID, Spectate> spectates() {
      return new HashMap<>(spectating);
   }

   // ------------------------------------------------------------- the ladder

   public static Record record(ServerPlayer player) {
      return record(player.getUUID(), player.getName().getString());
   }

   public static Record record(UUID id, String name) {
      Record record = records.computeIfAbsent(id, Record::new);
      record.name = name;
      record.lastSeen = System.currentTimeMillis();
      return record;
   }

   /** Resets the automatic ladder if the local day has turned over. */
   private static boolean rollDay(long now) {
      String today = dayOf(now).toString();
      if (today.equals(day)) {
         return false;
      }
      day = today;
      // The history stays. Only the automatic half starts over - that is the
      // difference between "what this player has done" and "what we are doing
      // about it today".
      for (Record record : records.values()) {
         record.dailyTriggers = 0;
         record.stage = 0;
      }
      dirty = true;
      return true;
   }

   /**
    * Counts one high-confidence finding and returns the enforcement stage it
    * leaves the player on.
    *
    * @return the stage reached: 1 for the first trip, 2 for the second, and
    *         {@link #TIMEOUT_STAGE} from the third on.
    */
   public static int trip(UUID id, String name) {
      long now = System.currentTimeMillis();
      rollDay(now);
      Record record = record(id, name);
      record.dailyTriggers++;
      record.stage = Math.min(TIMEOUT_STAGE, record.stage + 1);
      dirty = true;
      return record.stage;
   }

   /** Today's trigger count, which is what the ladder thresholds read. */
   public static int dailyTriggers(ServerPlayer player) {
      rollDay(System.currentTimeMillis());
      Record record = records.get(player.getUUID());
      return record == null ? 0 : record.dailyTriggers;
   }

   public static int stage(ServerPlayer player) {
      rollDay(System.currentTimeMillis());
      Record record = records.get(player.getUUID());
      return record == null ? 0 : record.stage;
   }

   public static void note(ServerPlayer player, String check, String detail, double level) {
      Record record = record(player);
      record.counts.merge(check, 1, Integer::sum);
      record.levels.put(check, level);
      record.lastDetail.put(check, detail);
      dirty = true;
   }

   // ------------------------------------------------------------ punishments

   public static void punish(ServerPlayer player, String type, String reason, String by, long until) {
      punish(player.getUUID(), player.getName().getString(), type, reason, by, until);
   }

   /**
    * The offline path. A banned player is almost never online - that is what
    * being banned means - so the moderation screen and {@code /ff anticheat}
    * have to be able to file a punishment against a record with only a uuid and
    * the name it was last seen under.
    */
   public static void punish(UUID id, String name, String type, String reason, String by, long until) {
      Record record = record(id, name);
      record.punishments.add(new Punishment(type, reason, by, System.currentTimeMillis(), until));
      if (until > System.currentTimeMillis()) {
         record.timeoutUntil = until;
      }
      dirty = true;
   }

   /** Lifts every punishment a player has and clears the automatic ladder. */
   public static boolean unban(ServerPlayer player, String by) {
      return unban(player.getUUID(), by);
   }

   /** The offline path for {@link #unban(ServerPlayer, String)}. */
   public static boolean unban(UUID id, String by) {
      Record record = records.get(id);
      if (record == null) {
         return false;
      }
      if (record.punishments.isEmpty() && record.timeoutUntil == 0L && record.stage == 0) {
         return false;
      }
      long now = System.currentTimeMillis();
      for (int i = 0; i < record.punishments.size(); i++) {
         Punishment p = record.punishments.get(i);
         if (p.active(now)) {
            record.punishments.set(i, new Punishment(p.type(), p.reason(), p.by(), p.at(), now));
         }
      }
      record.timeoutUntil = 0L;
      record.stage = 0;
      record.dailyTriggers = 0;
      record.punishments.add(new Punishment("unban", "lifted by " + by, by, now, now));
      dirty = true;
      return true;
   }

   /**
    * Lifts a kick's re-entry window, a live timeout, and the automatic ladder for one
    * player - everything that is "waiting" rather than "punished".
    *
    * <p>Deliberately <b>not</b> an unban: a ban is a decision a person made and stays on
    * the record. This is the opposite question - "stop making them wait" - and it is the
    * one an owner asks when the failsafe has kicked a player they know is clean, or when
    * they want to test the ladder from the start again without waiting a minute. The
    * ladder stage and today's trigger count are reset with it, because letting somebody
    * back in and then punishing them for the finding they already served is not clearing
    * anything.
    *
    * @return true when there was actually a hold or a ladder to clear
    */
   public static boolean clearHold(UUID id) {
      Record record = records.get(id);
      if (record == null) {
         return false;
      }
      long now = System.currentTimeMillis();
      boolean changed = false;
      for (int i = 0; i < record.punishments.size(); i++) {
         Punishment p = record.punishments.get(i);
         if (kickHolds(p.type(), p.until(), now)) {
            // Either kind of kick: a moderator's or the ladder's own rung. Both are a hold on the
            // join path and neither is a punishment, which is exactly what this command lifts.
            record.punishments.set(i, new Punishment(p.type(), p.reason(), p.by(), p.at(), now));
            changed = true;
         }
      }
      if (record.timeoutUntil > now) {
         record.timeoutUntil = 0L;
         changed = true;
      }
      if (record.stage != 0 || record.dailyTriggers != 0) {
         record.stage = 0;
         record.dailyTriggers = 0;
         changed = true;
      }
      if (changed) {
         dirty = true;
      }
      return changed;
   }

   public static void review(ServerPlayer player, String check, boolean falsePositive, String by, String note) {
      review(player.getUUID(), player.getName().getString(), check, falsePositive, by, note);
   }

   /** The offline path for {@link #review(ServerPlayer, String, boolean, String, String)}. */
   public static void review(UUID id, String name, String check, boolean falsePositive, String by, String note) {
      Record record = record(id, name);
      record.reviews.add(new Review(check, falsePositive, by, System.currentTimeMillis(), note));
      // A false positive is worth more than a reset: it is the only signal the
      // server owner has that a check is wrong, so it is kept next to the
      // evidence rather than replacing it.
      if (falsePositive) {
         record.counts.remove(check);
         record.levels.remove(check);
      }
      dirty = true;
   }

   // ------------------------------------------------------------- exemptions

   /**
    * Grants a check-scoped exemption for a while.
    *
    * <p>{@code check} may be {@code "*"} for every check. The reason is required
    * because the moderation screen shows it, and a blank one reads as "somebody
    * turned this off and did not say why".
    */
   public static Exemption exempt(UUID id, String name, String check, long seconds, String reason, String by) {
      Record record = record(id, name);
      long until = System.currentTimeMillis() + Math.max(1L, seconds) * 1000L;
      record.exemptions.add(new Exemption(check, until, reason, by));
      dirty = true;
      return record.exemptions.get(record.exemptions.size() - 1);
   }

   /** Removes every exemption matching a check, and returns how many went. */
   public static int clearExemption(UUID id, String check) {
      Record record = records.get(id);
      if (record == null) {
         return 0;
      }
      int before = record.exemptions.size();
      record.exemptions.removeIf(e -> check == null || check.equals(e.check()));
      int removed = before - record.exemptions.size();
      if (removed > 0) {
         dirty = true;
      }
      return removed;
   }

   /**
    * True when this player currently holds an exemption covering this check.
    * Expiry is evaluated on the spot rather than latched, so an exemption that
    * has run out stops at once even if nothing has swept it away yet.
    */
   public static boolean exempt(UUID id, String check) {
      Record record = records.get(id);
      if (record == null || record.exemptions.isEmpty()) {
         return false;
      }
      long now = System.currentTimeMillis();
      for (Exemption exemption : record.exemptions) {
         if (exemption.active(now) && covers(exemption.check(), check, isCombatCheck(check))) {
            return true;
         }
      }
      return false;
   }

   /**
    * The checks a blanket exemption may never cover.
    *
    * <p>An exemption exists to stop one measurement being taken: a boss ability that launches a
    * body is a reason to stand the flight check down for three seconds and no reason at all to
    * stop watching a fight. A {@code *} exemption used to cover every check in the file, so
    * granting one for a movement reason - the "antifall" case - silently turned off the kill
    * aura, aim, reach, hitbox and criticals checks for that player: a cheater with a flight
    * exemption could not be caught hitting through walls.
    *
    * <p>So a blanket exemption covers the mechanic family and only that. Anything on this list
    * has to be named to be stood down, which makes the consequence of the switch something the
    * person granting it can see.
    */
   public static final List<String> COMBAT_CHECKS = List.of(
      AntiCheat.KILLAURA,
      AntiCheat.KILLAURA_6H,
      AntiCheat.AIM,
      AntiCheat.AIM_CONSTANT,
      AntiCheat.AIM_LINEAR,
      AntiCheat.AIM_MODULE_360,
      AntiCheat.REACH,
      AntiCheat.BLOCK_REACH,
      AntiCheat.HITBOX,
      AntiCheat.AUTOCLICKER,
      AntiCheat.CRITICAL_GROUND,
      AntiCheat.KNOCKBACK,
      AntiCheat.FAST_SWING
   );

   /** Whether a check judges a fight rather than a body's mechanics. */
   public static boolean isCombatCheck(String check) {
      return check != null && COMBAT_CHECKS.contains(check);
   }

   /**
    * Whether one exemption entry covers one check.
    *
    * <p>The rule, as arithmetic over names, so both halves can be pinned without a fake
    * offender in the store: an entry covers the check it names, and a {@code *} covers
    * everything that is not a fight. Pinned by
    * {@code anticheat.a-blanket-exemption-cannot-blind-a-fight}.
    */
   public static boolean covers(String exemptionCheck, String check, boolean combat) {
      if (exemptionCheck == null || check == null) {
         return false;
      }
      if (exemptionCheck.equals(check)) {
         return true;
      }
      return "*".equals(exemptionCheck) && !combat;
   }

   /** Whether this player holds any live exemption at all - for the readouts. */
   public static boolean hasAnyExemption(UUID id) {
      Record record = records.get(id);
      if (record == null || record.exemptions.isEmpty()) {
         return false;
      }
      long now = System.currentTimeMillis();
      for (Exemption exemption : record.exemptions) {
         if (exemption.active(now)) {
            return true;
         }
      }
      return false;
   }

   /** Every live exemption on a player, for the moderation screen and /ff anticheat info. */
   public static List<Exemption> exemptions(UUID id) {
      Record record = records.get(id);
      if (record == null) {
         return List.of();
      }
      long now = System.currentTimeMillis();
      return record.exemptions.stream().filter(e -> e.active(now)).toList();
   }

   /** Drops the exemptions that have run out, so the file does not grow forever. */
   public static void sweep(long now) {
      for (Record record : records.values()) {
         if (record.exemptions.removeIf(e -> !e.active(now))) {
            dirty = true;
         }
      }
   }

   // ----------------------------------------------------------------- lookups

   public static Record get(UUID id) {
      return records.get(id);
   }

   /**
    * The record for a name, online or not.
    *
    * <p>Needed because the console is not a player: {@code /ff anticheat unban <name>} is the
    * command an owner runs from a terminal, at a point where the offender is by definition not
    * on the server, and every other entry point here resolves a player first. Matched
    * case-insensitively because that is how a name is typed, and the most recently seen record
    * wins if two accounts have ever shared one.
    */
   public static Record byName(String name) {
      if (name == null || name.isBlank()) {
         return null;
      }
      Record found = null;
      for (Record record : records.values()) {
         if (record.name != null && record.name.equalsIgnoreCase(name)) {
            if (found == null || record.lastSeen > found.lastSeen) {
               found = record;
            }
         }
      }
      return found;
   }

   /** Everybody with anything on the record, worst first. */
   public static List<Record> flagged() {
      List<Record> out = new ArrayList<>();

      for (Record record : records.values()) {
         if (record.total() > 0 || record.timedOut(System.currentTimeMillis())) {
            out.add(record);
         }
      }

      out.sort(Comparator.comparingDouble(Record::worstLevel).reversed().thenComparing(r -> r.name));
      return out;
   }

   /**
    * Everybody who is actually serving something, most recent first.
    *
    * <p>A plain warning kick is not a punishment in the sense a list needs. It is a 0-duration
    * record with the word "kick" on it, and the automatic ladder hands one out before it hands
    * out anything else - so listing "every record with a punishment on it" filled the banned
    * screen with players who had been kicked once an hour ago and were playing normally now,
    * which is worse than useless to the admin opening it: the one player who is genuinely timed
    * out is somewhere in a list of everybody the checks have ever fired on.
    *
    * <p>A record belongs on this screen if it has an active timeout, or a punishment that is
    * not one of the automatic ladder's kicks - a permanent ban recorded by a person, a mute, or
    * anything else a moderator decides to file. The kicks stay on the record and on the case
    * file, where they are evidence rather than a status.
    */
   public static List<Record> punished() {
      long now = System.currentTimeMillis();
      List<Record> out = new ArrayList<>();

      for (Record record : records.values()) {
         if (record.timedOut(now) || hasStandingPunishment(record, now)) {
            out.add(record);
         }
      }

      out.sort(Comparator.comparingLong((Record r) -> r.lastSeen).reversed());
      return out;
   }

   /** Whether this record is serving something now - see {@link #punished()}. */
   public static boolean hasStandingPunishment(Record record, long now) {
      if (record == null) {
         return false;
      }
      if (record.timedOut(now)) {
         return true;
      }
      for (Punishment p : record.punishments) {
         if (standingPunishment(p.type(), p.until(), now)) {
            return true;
         }
      }
      return false;
   }

   /**
    * The rule above, as arithmetic over one punishment's own fields.
    *
    * <p>Extracted so the decision can be pinned without building a record, which means without
    * writing a fake offender into the live store to test it. Three cases and they are the whole
    * rule: a duration still in the future is being served; a punishment with no duration is
    * permanent unless it is one of the automatic ladder's warning kicks, which are not;
    * anything else has been served and is history.
    */
   public static boolean standingPunishment(String type, long until, long now) {
      // A warning kick is never a status, whatever window it is carrying. It is the automatic
      // ladder's rung, not a decision a person made, so it must stay off the banned screen and
      // out of every "who is serving something" readout - the window it now carries (see
      // WARN_KICK_REENTRY_MILLIS) is a hold on the join path, which is a different thing.
      if (WARN_KICK.equals(type)) {
         return false;
      }
      if (until > now) {
         return true;
      }
      if (until > 0L) {
         return false;
      }
      // Two different things are spelled "kick" and only one of them is a punishment. A kick a
      // person filed is a real action with a real duration (see KICK_REENTRY_MILLIS) and is
      // handled by the branch above; the ladder's rung was answered above.
      return type != null && !KICK.equals(type);
   }

   /** The automatic ladder's first rung: a kick that is a warning, not a punishment. */
   public static final String WARN_KICK = "warn-kick";
   /** A kick a moderator filed, with a reason and a name attached. */
   public static final String KICK = "kick";
   /**
    * How long a moderator's kick keeps them out.
    *
    * <p>A kick has to DO something or it is a chat message with a disconnect in it: without a
    * window the kicked player rejoins the same second and the moderation action is invisible.
    * A minute is long enough to be felt and short enough that it is not a punishment - if the
    * intent is to keep somebody out, that is what the timeout is for.
    */
   public static final long KICK_REENTRY_MILLIS = 60_000L;
   /**
    * How long the automatic ladder's warning kick keeps them out.
    *
    * <p>Half a minute, and the reason it is not zero is the whole of the report this answers:
    * the warn rung disconnects and files a record with no window on it, so the kicked player is
    * back in the same second and the ladder reads from the outside as though the anticheat
    * stopped kicking people - *"warn kick still does not kick"*. Thirty seconds is long enough
    * that being removed is felt and short enough that it is still a warning rather than the
    * punishment the fourth rung is for. The window lives on the join path exactly like a
    * moderator's does, and the rung is still not a ban - see {@link #standingPunishment}.
    */
   public static final long WARN_KICK_REENTRY_MILLIS = 30_000L;

   /**
    * Whether a kick - either kind - is still holding somebody out, as arithmetic over its own
    * fields, so both sides of both windows can be pinned without a record in the store.
    */
   public static boolean kickHolds(String type, long until, long now) {
      return (KICK.equals(type) || WARN_KICK.equals(type)) && until > now;
   }

   /** Milliseconds left on a kick of either kind, or 0 when there is none. */
   public static long kickHoldsFor(Record record, long now) {
      return kickHoldsFor(record, now, null);
   }

   /**
    * The same, naming which kick is holding them: {@code "kick"}, {@code "warn-kick"} or null.
    *
    * <p>Needed because the join path has to say *who* removed them: "kicked by a moderator" and
    * "kicked by the anticheat" are different sentences, and a ladder rung reported as a
    * moderator's action is a sentence about a person who did nothing.
    */
   public static String kickHoldType(Record record, long now) {
      String[] out = new String[1];
      if (kickHoldsFor(record, now, out) <= 0L) {
         return null;
      }
      return out[0];
   }

   /** The duration and, through {@code typeOut}, the kind of the hold, or 0 when there is none. */
   private static long kickHoldsFor(Record record, long now, String[] typeOut) {
      if (record == null) {
         return 0L;
      }
      for (Punishment p : record.punishments) {
         if (kickHolds(p.type(), p.until(), now)) {
            if (typeOut != null) {
               typeOut[0] = p.type();
            }
            return p.until() - now;
         }
      }
      return 0L;
   }

   /**
    * Files one rung of the automatic ladder: a kick with a re-entry window and no other effect.
    *
    * <p>Deliberately not {@link #punish(java.util.UUID, String, String, String, String, long)},
    * which writes any future {@code until} into {@code timeoutUntil} - the field that means "this
    * body is serving a timeout". A warning kick is not a timeout: routing it through that method
    * would have made the ladder's first rung a live five-minute sentence and put the player on
    * the banned screen, which is the exact shape of bug the type was invented to avoid.
    */
   public static void warnKick(UUID id, String name, String reason) {
      Record record = record(id, name);
      long now = System.currentTimeMillis();
      record.punishments.add(
         new Punishment(WARN_KICK, reason, "anticheat", now, now + WARN_KICK_REENTRY_MILLIS)
      );
      dirty = true;
   }

   public static int size() {
      return records.size();
   }

   // ------------------------------------------------------------ reviews as signal

   /**
    * How many times staff have said this check was wrong.
    *
    * <p>A verdict is stored per player and per check, and this reads the check across every
    * record on the server: the question a bar like this answers is about the <i>check</i>, not
    * about the player it happened to. A verdict recorded against {@code "*"} counts for every
    * check, which is what the moderation kit's "false positive" button files.
    */
   public static int falsePositives(String check) {
      if (check == null) {
         return 0;
      }
      int total = 0;
      for (Record record : records.values()) {
         for (Review review : record.reviews) {
            if (review.falsePositive() && ("*".equals(review.check()) || check.equals(review.check()))) {
               total++;
            }
         }
      }
      return total;
   }

   /** How many verdicts it takes before the bar has been raised as far as it goes. */
   public static final int REVIEW_CAUTION_LIMIT = 4;
   /** How much each verdict adds, as a fraction of the bar. */
   public static final double REVIEW_CAUTION_PER_VERDICT = 0.35;

   /**
    * How much harder a check has to shout before staff hear it, from their own verdicts.
    *
    * <p>This is the difference between recording a false positive and doing something about it.
    * A verdict was already worth a lot - it deletes the counts for that check on that player and
    * sits next to the evidence - but the check itself kept firing at the same level, so a check
    * that is wrong on a quarter of the server got quieter for exactly one player and stayed
    * exactly as loud for everyone else. Now every verdict raises the bar that check has to clear
    * to reach chat, by {@value #REVIEW_CAUTION_PER_VERDICT} of it at a time and no more than
    * {@link #REVIEW_CAUTION_LIMIT} verdicts' worth.
    *
    * <p><b>It raises the alert bar and nothing else.</b> Findings are still recorded at the same
    * level, the ledger still shows them, the case file still has the evidence, and enforcement is
    * untouched - it is an allowlist of checks with a physical model behind them and this cannot
    * add to it. The point is to stop a wrong check talking, not to stop it looking.
    *
    * <p>Written as arithmetic over a count so it can be pinned without a server: see
    * {@code anticheat.reviews-quiet-the-check-that-was-wrong}.
    */
   public static double reviewCaution(String check) {
      int wrong = Math.min(REVIEW_CAUTION_LIMIT, falsePositives(check));
      return 1.0 + REVIEW_CAUTION_PER_VERDICT * wrong;
   }

   /** The same rule with the count handed in, so the shape can be read without a store. */
   public static double reviewCaution(int falsePositiveVerdicts) {
      int wrong = Math.max(0, Math.min(REVIEW_CAUTION_LIMIT, falsePositiveVerdicts));
      return 1.0 + REVIEW_CAUTION_PER_VERDICT * wrong;
   }

   /** Every check some moderator has called wrong, with the count - for /ff anticheat. */
   public static List<String> reviewedChecks() {
      List<String> out = new ArrayList<>();
      if (records.isEmpty()) {
         return out;
      }
      java.util.Map<String, Integer> tally = new java.util.LinkedHashMap<>();
      for (Record record : records.values()) {
         for (Review review : record.reviews) {
            if (review.falsePositive()) {
               tally.merge(review.check(), 1, Integer::sum);
            }
         }
      }
      tally.forEach((check, count) -> out.add("&f" + check + " &8x" + count));
      return out;
   }

   /** One line per punishment, for /ff anticheat banned without the GUI. */
   public static List<String> describe() {
      List<String> out = new ArrayList<>();
      long now = System.currentTimeMillis();

      for (Record record : punished()) {
         Punishment active = record.activePunishment(now);
         String state = record.timedOut(now)
            ? "&ctimed out for another " + ((record.timeoutUntil - now) / 1000L) + "s"
            : (active != null ? "&e" + active.type() : "&7no active punishment");
         out.add("&7- &f" + record.name + " &8(" + record.id.toString().substring(0, 8) + ")&7: " + state + " &8- " + record.punishments.size() + " record(s)");
      }

      return out;
   }

   /** Writes a punishment list into a JSON object. Kept for the next save cycle. */
   static void write(JsonObject obj, Record record) {
      JsonArray arr = new JsonArray();
      for (Punishment p : record.punishments) {
         JsonObject o = new JsonObject();
         o.addProperty("type", p.type());
         o.addProperty("reason", p.reason());
         o.addProperty("by", p.by());
         o.addProperty("at", p.at());
         o.addProperty("until", p.until());
         arr.add(o);
      }
      obj.add("punishments", arr);
      writeList(obj, "reviews", list -> {
         for (Review r : record.reviews) {
            JsonObject o = new JsonObject();
            o.addProperty("check", r.check());
            o.addProperty("false_positive", r.falsePositive());
            o.addProperty("by", r.by());
            o.addProperty("at", r.at());
            o.addProperty("note", r.note());
            list.add(o);
         }
      });
      writeList(obj, "exemptions", list -> {
         for (Exemption x : record.exemptions) {
            JsonObject o = new JsonObject();
            o.addProperty("check", x.check());
            o.addProperty("until", x.until());
            o.addProperty("reason", x.reason());
            o.addProperty("by", x.by());
            list.add(o);
         }
      });
   }
}
