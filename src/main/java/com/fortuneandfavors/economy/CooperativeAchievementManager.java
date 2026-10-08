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

public final class CooperativeAchievementManager {
   public static final String UNITED = "united";
   public static final String WOLFPACK = "wolfpack";
   public static final String FLAWLESS = "flawless";
   public static final String SPEED_DEMON = "speed_demon";
   public static final String UNBREAKABLE = "unbreakable";
   public static final String SERVER_LEGEND = "server_legend";
   public static final String FIRST_MILLION = "first_million";
   public static final String BOUNTY_HUNTER = "bounty_hunter";
   private static final Map<UUID, Set<String>> completed = new HashMap<>();
   private static final Map<String, Integer> serverCounters = new HashMap<>();
   private static Path dataFile;

   private CooperativeAchievementManager() {
   }

   public static void load(MinecraftServer server) {
      completed.clear();
      serverCounters.clear();
      dataFile = EconomyManager.getDataDir(server).resolve("achievements.json");
      JsonObject root = JsonUtil.readOrCreate(dataFile, new JsonObject());
      if (root.has("players") && root.get("players").isJsonObject()) {
         for (Entry<String, JsonElement> e : root.getAsJsonObject("players").entrySet()) {
            try {
               UUID uuid = UUID.fromString(e.getKey());
               Set<String> set = new HashSet<>();
               for (JsonElement a : e.getValue().getAsJsonArray()) {
                  set.add(a.getAsString());
               }
               completed.put(uuid, set);
            } catch (Exception ignored) {
            }
         }
      }
      if (root.has("server") && root.get("server").isJsonObject()) {
         for (Entry<String, JsonElement> e : root.getAsJsonObject("server").entrySet()) {
            serverCounters.put(e.getKey(), e.getValue().getAsInt());
         }
      }
   }

   public static void save(MinecraftServer server) {
      if (dataFile == null) {
         dataFile = EconomyManager.getDataDir(server).resolve("achievements.json");
      }
      JsonObject root = new JsonObject();
      JsonObject players = new JsonObject();
      for (Entry<UUID, Set<String>> e : completed.entrySet()) {
         JsonArray arr = new JsonArray();
         for (String a : e.getValue()) {
            arr.add(a);
         }
         players.add(e.getKey().toString(), arr);
      }
      root.add("players", players);
      JsonObject counters = new JsonObject();
      for (Entry<String, Integer> e : serverCounters.entrySet()) {
         counters.addProperty(e.getKey(), e.getValue());
      }
      root.add("server", counters);
      JsonUtil.write(dataFile, root);
   }

   public static void onBossKilled(MinecraftServer server, String bossKey, ServerPlayer killer, int participants, boolean anyParticipantDied, long fightMs) {
      if (killer == null) {
         return;
      }
      if (participants >= 3) {
         complete(server, killer, UNITED, "United", "Kill a raid boss with 2+ other players", 10000L, 1, "United");
      }
      if (participants >= 3) {
         serverCounters.merge("wolfpack", 1, Integer::sum);
         if (serverCounters.get("wolfpack") >= 3) {
            complete(server, killer, WOLFPACK, "Wolfpack", "Kill 3 raid bosses as a group of 3+", 30000L, 2, "Wolfpack");
         }
      }
      if (!anyParticipantDied) {
         complete(server, killer, FLAWLESS, "Flawless", "Kill a raid boss with no participants dying", 15000L, 1, "Flawless");
      }
      if (fightMs > 0L && fightMs < 120_000L) {
         complete(server, killer, SPEED_DEMON, "Speed Demon", "Kill a raid boss within 2 minutes", 20000L, 1, "Speed Demon");
      }
      serverCounters.merge("boss_kills", 1, Integer::sum);
      if (serverCounters.get("boss_kills") >= 100) {
         complete(server, killer, UNBREAKABLE, "Unbreakable", "The server defeats 100 raid bosses", 50000L, 3, "Unbreakable");
      }
      if (bossKey.equals("king")
         && has(killer, UNITED)
         && has(killer, WOLFPACK)
         && has(killer, FLAWLESS)
         && has(killer, SPEED_DEMON)
         && has(killer, UNBREAKABLE)) {
         complete(server, killer, SERVER_LEGEND, "Server Legend", "Achieve every boss achievement", 100000L, 5, "Server Legend");
      }
      save(server);
   }

   public static void onBountyCompleted(MinecraftServer server, ServerPlayer player) {
      serverCounters.merge("bounties", 1, Integer::sum);
      if (serverCounters.get("bounties") >= 10) {
         complete(server, player, BOUNTY_HUNTER, "Bounty Hunter", "The server completes 10 bounties", 25000L, 2, "Bounty Hunter");
         save(server);
      }
   }

   public static void checkMillionaire(MinecraftServer server, ServerPlayer player) {
      if (EconomyManager.balance(player.getUUID()) >= 1_000_000L) {
         complete(server, player, FIRST_MILLION, "First Million", "Hold a $1,000,000 balance", 100000L, 5, "Millionaire");
         FirstEverRecordManager.onMillionaire(server, player);
      }
   }

   public static boolean has(ServerPlayer player, String id) {
      Set<String> set = completed.get(player.getUUID());
      return set != null && set.contains(id);
   }

   public static Set<String> completedOf(UUID uuid) {
      return completed.getOrDefault(uuid, Set.of());
   }

   private static void complete(MinecraftServer server, ServerPlayer player, String id, String name, String desc, long cash, int tokens, String tagText) {
      Set<String> set = completed.computeIfAbsent(player.getUUID(), u -> new HashSet<>());
      if (set.add(id)) {
         EconomyManager.addCash(player.getUUID(), cash);
         if (tokens > 0) {             TokenManager.giveGems(player, 1);
         }
         if (TagManager.getTag(player.getUUID()) == null) {
            TagManager.setTag(player.getUUID(), tagText, 16755200);
         }
         Chat.raw(player, "§5§lACHIEVEMENT: §d" + name + "§r§5! §7" + desc);          Chat.raw(player, "§5Reward: §a$" + cash + "§5 + §5" + tokens + " gem(s)");
         for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (!p.getUUID().equals(player.getUUID())) {
               Chat.raw(p, "§5★ " + player.getName().getString() + " earned the achievement §d" + name + "§5!");
            }
         }

         // Persist immediately. This used to rely on some *later* code path
         // calling save(), so an achievement earned in a session that never had a
         // boss kill or bounty (First Million, for one) was announced in chat and
         // then silently gone after the next restart.
         save(server);
      }
   }

   public static String displayName(String id) {
      return switch (id) {
         case UNITED -> "United";
         case WOLFPACK -> "Wolfpack";
         case FLAWLESS -> "Flawless";
         case SPEED_DEMON -> "Speed Demon";
         case UNBREAKABLE -> "Unbreakable";
         case SERVER_LEGEND -> "Server Legend";
         case FIRST_MILLION -> "First Million";
         case BOUNTY_HUNTER -> "Bounty Hunter";
         default -> id;
      };
   }
}
