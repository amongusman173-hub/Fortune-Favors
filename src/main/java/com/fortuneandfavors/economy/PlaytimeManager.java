package com.fortuneandfavors.economy;

import com.fortuneandfavors.util.JsonUtil;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.Map.Entry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** Tracks how long each player has been on THIS server and when they first\n *  joined. Sessions accumulate from join to disconnect (and on periodic\n *  flushes), so playtime survives crashes and restarts. */
public final class PlaytimeManager {
   private static final Map<UUID, PlayerTime> times = new HashMap<>();
   private static Path dataFile;
   private static final ZoneId TZ = ZoneId.of("America/New_York");

   private PlaytimeManager() {
   }

   public static void load(MinecraftServer server) {
      times.clear();
      dataFile = EconomyManager.getDataDir(server).resolve("playtime.json");
      JsonObject root = JsonUtil.readOrCreate(dataFile, new JsonObject());
      if (root.has("players") && root.get("players").isJsonObject()) {
         for (Entry<String, JsonElement> e : root.getAsJsonObject("players").entrySet()) {
            try {
               UUID uuid = UUID.fromString(e.getKey());
               JsonObject obj = e.getValue().getAsJsonObject();
               long secs = JsonUtil.jsonLong(obj, "seconds", 0L);
               String first = JsonUtil.jsonString(obj, "first_join", "");
               if (secs > 0L || !first.isEmpty()) {
                  times.put(uuid, new PlayerTime(secs, first, 0L));
               }
            } catch (Exception ignored) {
            }
         }
      }
   }

   public static void save(MinecraftServer server) {
      if (dataFile == null) {
         dataFile = EconomyManager.getDataDir(server).resolve("playtime.json");
      }
      JsonObject root = new JsonObject();
      JsonObject players = new JsonObject();
      for (Entry<UUID, PlayerTime> e : times.entrySet()) {
         JsonObject obj = new JsonObject();
         obj.addProperty("seconds", e.getValue().totalSeconds + Math.max(0L, (System.currentTimeMillis() / 1000L) - e.getValue().sessionStart));
         obj.addProperty("first_join", e.getValue().firstJoin);
         players.add(e.getKey().toString(), obj);
      }
      root.add("players", players);
      JsonUtil.write(dataFile, root);
   }

   /** Starts (or continues) a session. First join also stamps the join date. */
   public static void onJoin(ServerPlayer player) {
      if (player == null) {
         return;
      }
      PlayerTime t = times.computeIfAbsent(player.getUUID(), u -> new PlayerTime(0L, "", 0L));
      if (t.firstJoin.isEmpty()) {
         t.firstJoin = LocalDate.now(TZ).toString();
      }
      t.sessionStart = System.currentTimeMillis() / 1000L;
   }

   /** Ends a session and banks the elapsed time. */
   public static void onDisconnect(ServerPlayer player) {
      if (player == null) {
         return;
      }
      PlayerTime t = times.get(player.getUUID());
      if (t == null) {
         return;
      }
      long now = System.currentTimeMillis() / 1000L;
      if (t.sessionStart > 0L) {
         t.totalSeconds += Math.max(0L, now - t.sessionStart);
      }
      t.sessionStart = 0L;
   }

   /** Called every tick - periodically flushes so a crash never loses much. */
   public static void tick(ServerPlayer player) {
      if (player == null || player.level().getGameTime() % 6000L != 0L) {
         return;
      }
      PlayerTime t = times.get(player.getUUID());
      if (t != null && t.sessionStart > 0L) {
         long now = System.currentTimeMillis() / 1000L;
         t.totalSeconds += Math.max(0L, now - t.sessionStart);
         t.sessionStart = now;
      }
   }

   /** Total seconds spent on this server (including the live session). */
   public static long totalSeconds(UUID uuid) {
      PlayerTime t = times.get(uuid);
      if (t == null) {
         return 0L;
      }
      long live = t.sessionStart > 0L ? Math.max(0L, (System.currentTimeMillis() / 1000L) - t.sessionStart) : 0L;
      return t.totalSeconds + live;
   }

   /** The date this player first joined, as ISO yyyy-MM-dd, or \"\" if unknown. */
   public static String firstJoin(UUID uuid) {
      PlayerTime t = times.get(uuid);
      return t == null ? "" : t.firstJoin;
   }

   /** \"3h 42m\" / \"12m\" style formatting. */
   public static String format(long seconds) {
      long h = seconds / 3600L;
      long m = (seconds % 3600L) / 60L;
      if (h > 0L) {
         return h + "h " + m + "m";
      }
      if (m > 0L) {
         return m + "m";
      }
      return seconds + "s";
   }

   private static final class PlayerTime {
      long totalSeconds;
      String firstJoin;
      long sessionStart;

      PlayerTime(long totalSeconds, String firstJoin, long sessionStart) {
         this.totalSeconds = totalSeconds;
         this.firstJoin = firstJoin;
         this.sessionStart = sessionStart;
      }
   }
}