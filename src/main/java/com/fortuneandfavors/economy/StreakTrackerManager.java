package com.fortuneandfavors.economy;

import com.fortuneandfavors.util.Chat;
import com.fortuneandfavors.util.JsonUtil;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.Map.Entry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

public final class StreakTrackerManager {
   public static final int DUEL_TITLE_10 = 10;
   public static final int DUEL_TITLE_25 = 25;
   public static final int DUEL_TITLE_50 = 50;
   public static final int BOSS_STREAK_TITLE = 4;
   public static final int BOUNTY_LEVEL_TITLE = 20;
   public static final int BOUNTIES_PER_LEVEL = 5;
   private static final Map<UUID, Integer> duelWins = new HashMap<>();
   private static final Map<UUID, Integer> bossStreak = new HashMap<>();
   private static final Map<UUID, Integer> bountyCount = new HashMap<>();
   private static Path dataFile;

   private StreakTrackerManager() {
   }

   public static void load(MinecraftServer server) {
      duelWins.clear();
      bossStreak.clear();
      bountyCount.clear();
      dataFile = EconomyManager.getDataDir(server).resolve("streaks.json");
      JsonObject root = JsonUtil.readOrCreate(dataFile, new JsonObject());
      readIntMap(root, "duel_wins", duelWins);
      readIntMap(root, "boss_streak", bossStreak);
      readIntMap(root, "bounties", bountyCount);
   }

   public static void save(MinecraftServer server) {
      if (dataFile == null) {
         dataFile = EconomyManager.getDataDir(server).resolve("streaks.json");
      }
      JsonObject root = new JsonObject();
      writeIntMap(root, "duel_wins", duelWins);
      writeIntMap(root, "boss_streak", bossStreak);
      writeIntMap(root, "bounties", bountyCount);
      JsonUtil.write(dataFile, root);
   }

   private static void readIntMap(JsonObject root, String key, Map<UUID, Integer> into) {
      if (root.has(key) && root.get(key).isJsonObject()) {
         for (Entry<String, JsonElement> e : root.getAsJsonObject(key).entrySet()) {
            try {
               into.put(UUID.fromString(e.getKey()), e.getValue().getAsInt());
            } catch (Exception ignored) {
            }
         }
      }
   }

   private static void writeIntMap(JsonObject root, String key, Map<UUID, Integer> map) {
      JsonObject obj = new JsonObject();
      for (Entry<UUID, Integer> e : map.entrySet()) {
         obj.addProperty(e.getKey().toString(), e.getValue());
      }
      root.add(key, obj);
   }

   public static void onDuelWin(ServerPlayer player) {
      int wins = duelWins.merge(player.getUUID(), 1, Integer::sum);
      com.fortuneandfavors.economy.ServerNewspaperManager.logEvent(
         player.level().getServer(), player.getName().getString() + " triumphed in a duel (" + wins + " wins total)."
      );
      String title = null;
      if (wins >= DUEL_TITLE_50) {
         title = "Champion";
      } else if (wins >= DUEL_TITLE_25) {
         title = "Swordsman";
      } else if (wins >= DUEL_TITLE_10) {
         title = "Duelist";
      }
      if (title != null && !TitleManager.has(player.getUUID(), title)) {
         TitleManager.unlock(player, title);
         for (ServerPlayer p : player.level().getServer().getPlayerList().getPlayers()) {
            Chat.raw(p, "§6§l" + player.getName().getString() + "§r§6 reached " + wins + " duel wins and earned the title §e§l" + title + "§6§l!");
         }
      }
   }

   public static void onBossKill(ServerPlayer player) {
      int streak = bossStreak.merge(player.getUUID(), 1, Integer::sum);
      if (streak >= BOSS_STREAK_TITLE && !TitleManager.has(player.getUUID(), "Boss Slayer")) {
         TitleManager.unlock(player, "Boss Slayer");
         for (ServerPlayer p : player.level().getServer().getPlayerList().getPlayers()) {
            Chat.raw(p, "§c§l" + player.getName().getString() + "§r§c has slain " + streak + " bosses in a row - the title §e§lBoss Slayer§r§c is theirs!");
         }
      }
   }

   public static void onPlayerDeath(ServerPlayer player) {
      bossStreak.remove(player.getUUID());
   }

   public static void onBountyCompleted(ServerPlayer player) {
      int total = bountyCount.merge(player.getUUID(), 1, Integer::sum);
      com.fortuneandfavors.economy.ServerNewspaperManager.logEvent(
         player.level().getServer(), "Bounty hunter " + player.getName().getString() + " collected their " + total + "."
      );
      int level = total / BOUNTIES_PER_LEVEL;
      if (level >= BOUNTY_LEVEL_TITLE && !TitleManager.has(player.getUUID(), "Bounty Hunter")) {
         TitleManager.unlock(player, "Bounty Hunter");
         for (ServerPlayer p : player.level().getServer().getPlayerList().getPlayers()) {
            Chat.raw(p, "§c§l" + player.getName().getString() + "§r§c reached Bounty Hunter level " + level + " and earned the title §e§lBounty Hunter§r§c!");
         }
      }
   }

   public static int duelWins(UUID uuid) {
      return duelWins.getOrDefault(uuid, 0);
   }

   public static int bossStreak(UUID uuid) {
      return bossStreak.getOrDefault(uuid, 0);
   }

   public static int bountyLevel(UUID uuid) {
      return bountyCount.getOrDefault(uuid, 0) / BOUNTIES_PER_LEVEL;
   }
}
