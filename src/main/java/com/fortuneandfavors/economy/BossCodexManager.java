package com.fortuneandfavors.economy;

import com.fortuneandfavors.util.Chat;
import com.fortuneandfavors.util.JsonUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Map.Entry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

public final class BossCodexManager {
   private static final Map<String, BossEntry> codex = new HashMap<>();
   private static final Set<UUID> completedPlayers = new HashSet<>();
   private static Path dataFile;

   /** Every boss key that must be slain for the "Codex Complete" milestone. */
   private static final String[] ALL_BOSS_KEYS = new String[] {"king", "slime", "golem", "mind", "snow", "warden"};
   /** One-time cash reward for completing the codex. */
   private static final long COMPLETION_REWARD = 100000L;

   private BossCodexManager() {
   }

   public static void load(MinecraftServer server) {
      codex.clear();
      dataFile = EconomyManager.getDataDir(server).resolve("boss_codex.json");
      JsonObject root = JsonUtil.readOrCreate(dataFile, new JsonObject());
      if (root.has("completed") && root.get("completed").isJsonArray()) {
         for (JsonElement el : root.getAsJsonArray("completed")) {
            try {
               completedPlayers.add(UUID.fromString(el.getAsString()));
            } catch (Exception ignored) {
            }
         }
      }
      if (root.has("bosses") && root.get("bosses").isJsonObject()) {
         for (Entry<String, JsonElement> e : root.getAsJsonObject("bosses").entrySet()) {
            try {
               JsonObject obj = e.getValue().getAsJsonObject();
               BossEntry entry = new BossEntry();
               entry.firstDiscoveredBy = JsonUtil.jsonString(obj, "first_discovered_by", "");
               entry.firstDefeatedBy = JsonUtil.jsonString(obj, "first_defeated_by", "");
               entry.kills = JsonUtil.jsonInt(obj, "kills", 0);
               entry.deathsCaused = JsonUtil.jsonInt(obj, "deaths_caused", 0);
               entry.fastestMs = JsonUtil.jsonLong(obj, "fastest_ms", 0L);
               entry.fastestBy = JsonUtil.jsonString(obj, "fastest_by", "");
               if (obj.has("killers") && obj.get("killers").isJsonArray()) {
                  for (JsonElement kEl : obj.getAsJsonArray("killers")) {
                     try {
                        entry.killers.add(UUID.fromString(kEl.getAsString()));
                     } catch (Exception ignored) {
                     }
                  }
               }
               codex.put(e.getKey(), entry);
            } catch (Exception ignored) {
            }
         }
      }
   }

   public static void save(MinecraftServer server) {
      if (dataFile == null) {
         dataFile = EconomyManager.getDataDir(server).resolve("boss_codex.json");
      }
      JsonObject root = new JsonObject();
      JsonArray completed = new JsonArray();
      for (UUID u : completedPlayers) {
         completed.add(u.toString());
      }
      root.add("completed", completed);
      JsonObject bosses = new JsonObject();
      for (Entry<String, BossEntry> e : codex.entrySet()) {
         JsonObject obj = new JsonObject();
         obj.addProperty("first_discovered_by", e.getValue().firstDiscoveredBy);
         obj.addProperty("first_defeated_by", e.getValue().firstDefeatedBy);
         obj.addProperty("kills", e.getValue().kills);
         obj.addProperty("deaths_caused", e.getValue().deathsCaused);
         obj.addProperty("fastest_ms", e.getValue().fastestMs);
         obj.addProperty("fastest_by", e.getValue().fastestBy);
         JsonArray killers = new JsonArray();
         for (UUID k : e.getValue().killers) {
            killers.add(k.toString());
         }
         obj.add("killers", killers);
         bosses.add(e.getKey(), obj);
      }
      root.add("bosses", bosses);
      JsonUtil.write(dataFile, root);
   }

   public static void onBossKilled(MinecraftServer server, String key, ServerPlayer killer, long fightStartMs) {
      BossEntry entry = codex.computeIfAbsent(key, k -> new BossEntry());
      if (entry.firstDiscoveredBy.isEmpty()) {
         entry.firstDiscoveredBy = killer.getName().getString();
         broadcast(server, "§d📖 The " + displayName(key) + " has been discovered by " + killer.getName().getString() + "!");
      }
      if (entry.firstDefeatedBy.isEmpty()) {
         entry.firstDefeatedBy = killer.getName().getString();
         broadcast(server, "§d📖 The " + displayName(key) + " was defeated for the first time by " + killer.getName().getString() + "!");
      }
      entry.kills++;
      entry.killers.add(killer.getUUID());
      long elapsed = fightStartMs > 0L ? Math.max(1L, System.currentTimeMillis() - fightStartMs) : 0L;
      if (elapsed > 0L && (entry.fastestMs == 0L || elapsed < entry.fastestMs)) {
         entry.fastestMs = elapsed;
         entry.fastestBy = killer.getName().getString();
         broadcast(server, "§d⚡ New fastest " + displayName(key) + " kill: " + killer.getName().getString() + " in " + (elapsed / 1000L) + "s!");
      }
      if (isCodexComplete(killer.getUUID()) && completedPlayers.add(killer.getUUID())) {
         grantCompletionReward(server, killer);
      }
      save(server);
   }

   public static void onPlayerKilledByBoss(MinecraftServer server, String key, ServerPlayer victim) {
      BossEntry entry = codex.computeIfAbsent(key, k -> new BossEntry());
      entry.deathsCaused++;
      save(server);
   }

   public static Map<String, BossEntry> all() {
      return codex;
   }

   public static String displayName(String key) {
      return switch (key) {
         case "king" -> "King Wither Skeleton";
         case "slime" -> "Slime King";
         case "golem" -> "Stone Golem";
         case "mind" -> "Mindbinder";
         case "snow" -> "Snow Queen";
         case "warden" -> "Elder Warden";
         case "scarlet" -> "Scarlet Devil";
         case "timelord" -> "Time Lord";
         case "clockwork" -> "Clockwork King";
         case "magister" -> "Starbound Magister";
         case "voidshaper" -> "Void Shaper";
         case "sovereign" -> "Emerald Sovereign";
         default -> key;
      };
   }

   /** True once the player has personally slain every boss at least once. */
   public static boolean isCodexComplete(UUID uuid) {
      for (String key : ALL_BOSS_KEYS) {
         BossEntry entry = codex.get(key);
         if (entry == null || !entry.killers.contains(uuid)) {
            return false;
         }
      }
      return true;
   }

   /** How many bosses this player has personally slain (0-6), for UI displays. */
   public static int personalKillCount(UUID uuid) {
      int count = 0;
      for (String key : ALL_BOSS_KEYS) {
         BossEntry entry = codex.get(key);
         if (entry != null && entry.killers.contains(uuid)) {
            count++;
         }
      }
      return count;
   }

   private static void grantCompletionReward(MinecraftServer server, ServerPlayer player) {
      EconomyManager.addCash(player.getUUID(), COMPLETION_REWARD);
      broadcast(server, "§8§m═══════════════════════════════§r");
      broadcast(server, "  §d§l📖 BOSS CODEX COMPLETE§r §7- §f" + player.getName().getString() + "§7 has slain every boss!");
      broadcast(server, "  §7Reward: §a$" + Chat.moneyStr(COMPLETION_REWARD) + "§7. §8(one-time)");
      broadcast(server, "§8§m═══════════════════════════════§r");
      Chat.raw(player, "§d§l📖 Boss Codex complete! §7You've slain every boss - §a$" + Chat.moneyStr(COMPLETION_REWARD) + " §7added to your balance.");
      Advancements.grant(player, "boss_codex_complete");
   }

   private static void broadcast(MinecraftServer server, String message) {
      if (server == null) {
         return;
      }
      for (ServerPlayer p : server.getPlayerList().getPlayers()) {
         Chat.raw(p, message);
      }
   }

   public static class BossEntry {
      public String firstDiscoveredBy = "";
      public String firstDefeatedBy = "";
      public int kills = 0;
      public int deathsCaused = 0;
      public long fastestMs = 0L;
      public String fastestBy = "";
      public final Set<UUID> killers = new HashSet<>();
   }
}
