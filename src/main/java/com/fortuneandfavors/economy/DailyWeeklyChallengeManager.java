package com.fortuneandfavors.economy;

import com.fortuneandfavors.util.Chat;
import com.fortuneandfavors.util.InventoryHelper;
import com.fortuneandfavors.util.JsonUtil;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.Map.Entry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public final class DailyWeeklyChallengeManager {
   public static final String DAILY = "daily";
   public static final String WEEKLY = "weekly";
   private static final List<Challenge> DAILY_POOL = new ArrayList<>();
   private static final List<Challenge> WEEKLY_POOL = new ArrayList<>();
   private static final Map<UUID, Map<String, Integer>> progress = new HashMap<>();
   private static String rotationDay = "";
   private static String rotationWeek = "";
   private static Path dataFile;

   private DailyWeeklyChallengeManager() {
   }

   private static void initPool() {
      if (!DAILY_POOL.isEmpty()) {
         return;
      }
      DAILY_POOL.add(new Challenge("mine_stone", "Miner's Day", "Mine 100 stone or deepslate", "mine", 100, 5000L));
      DAILY_POOL.add(new Challenge("kill_mobs", "Hunter's Day", "Kill 20 hostile mobs", "mob", 20, 5000L));
      DAILY_POOL.add(new Challenge("kill_players", "Predator", "Kill 3 players", "pvp", 3, 8000L));
      DAILY_POOL.add(new Challenge("kill_boss", "Boss Hunter", "Defeat a raid boss", "boss", 1, 15000L));
      DAILY_POOL.add(new Challenge("duel_wins", "Duelist's Day", "Win 2 duels", "duel", 2, 6000L));
      DAILY_POOL.add(new Challenge("deliver", "Courier", "Fulfil a dynamic contract", "deliver", 1, 8000L));
      DAILY_POOL.add(new Challenge("bounties", "Wanted", "Complete a bounty", "bounty", 1, 8000L));
      DAILY_POOL.add(new Challenge("trade", "Merchant's Day", "Complete 2 trades", "trade", 2, 4000L));
      DAILY_POOL.add(new Challenge("sell", "Profiteer", "Earn $5,000 from selling", "sell", 5000, 6000L));
      WEEKLY_POOL.add(new Challenge("w_bosses", "Legend Slayer", "Defeat 5 raid bosses", "boss", 5, 50000L));
      WEEKLY_POOL.add(new Challenge("w_earn", "Tycoon", "Earn $100,000 this week", "earn", 100000, 50000L));
      WEEKLY_POOL.add(new Challenge("w_duels", "Arena King", "Win 5 duels", "duel", 5, 20000L));
      WEEKLY_POOL.add(new Challenge("w_pvp", "War Machine", "Kill 10 players", "pvp", 10, 30000L));
      WEEKLY_POOL.add(new Challenge("w_ores", "Deep Miner", "Mine 500 ores", "ore", 500, 25000L));
   }

   public static void load(MinecraftServer server) {
      progress.clear();
      initPool();
      dataFile = EconomyManager.getDataDir(server).resolve("challenges.json");
      JsonObject root = JsonUtil.readOrCreate(dataFile, new JsonObject());
      rotationDay = JsonUtil.jsonString(root, "rotation_day", "");
      rotationWeek = JsonUtil.jsonString(root, "rotation_week", "");
      if (root.has("players") && root.get("players").isJsonObject()) {
         for (Entry<String, JsonElement> e : root.getAsJsonObject("players").entrySet()) {
            try {
               UUID uuid = UUID.fromString(e.getKey());
               Map<String, Integer> map = new HashMap<>();
               for (Entry<String, JsonElement> p : e.getValue().getAsJsonObject().entrySet()) {
                  map.put(p.getKey(), p.getValue().getAsInt());
               }
               progress.put(uuid, map);
            } catch (Exception ignored) {
            }
         }
      }
      if (!rotationDay.equals(today())) {
         rotationDay = today();
         progress.clear();
      }
      String week = currentWeek();
      if (!rotationWeek.equals(week)) {
         rotationWeek = week;
         progress.clear();
      }
   }

   public static void save(MinecraftServer server) {
      if (dataFile == null) {
         dataFile = EconomyManager.getDataDir(server).resolve("challenges.json");
      }
      JsonObject root = new JsonObject();
      root.addProperty("rotation_day", rotationDay);
      root.addProperty("rotation_week", rotationWeek);
      JsonObject players = new JsonObject();
      for (Entry<UUID, Map<String, Integer>> e : progress.entrySet()) {
         JsonObject map = new JsonObject();
         for (Entry<String, Integer> p : e.getValue().entrySet()) {
            map.addProperty(p.getKey(), p.getValue());
         }
         players.add(e.getKey().toString(), map);
      }
      root.add("players", players);
      JsonUtil.write(dataFile, root);
   }

   public static void tick(MinecraftServer server) {
      boolean changed = false;
      if (!rotationDay.equals(today())) {
         rotationDay = today();
         progress.clear();
         changed = true;
      }
      String week = currentWeek();
      if (!rotationWeek.equals(week)) {
         rotationWeek = week;
         progress.clear();
         changed = true;
      }
      if (changed) {
         save(server);
         // No global chat broadcast - challenges rotate silently so the chat
         // stays clean. Players see their progress in /ff challenges.
         for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            p.sendSystemMessage(
               net.minecraft.network.chat.Component.literal("§8[§6Challenges§8] §7New daily/weekly challenges are ready - §f/ff challenges§7."),
               true
            );
         }
      }
   }

   public static List<Challenge> daily() {
      return DAILY_POOL;
   }

   public static List<Challenge> weekly() {
      return WEEKLY_POOL;
   }

   public static int progressOf(ServerPlayer player, String id) {
      return progress.getOrDefault(player.getUUID(), Map.of()).getOrDefault(id, 0);
   }

   public static void onBlockBroken(ServerPlayer player, BlockState state) {
      if (state.is(Blocks.STONE) || state.is(Blocks.DEEPSLATE)) {
         add(player, "mine_stone", 1);
      }
      String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
      if (id.contains("ore") || id.contains("debris")) {
         add(player, "w_ores", 1);
      }
   }

   public static void onMobKill(ServerPlayer player, boolean boss) {
      if (boss) {
         add(player, "kill_boss", 1);
         add(player, "w_bosses", 1);
      } else {
         add(player, "kill_mobs", 1);
      }
   }

   public static void onPlayerKill(ServerPlayer player) {
      add(player, "kill_players", 1);
      add(player, "w_pvp", 1);
   }

   public static void onDuelWin(ServerPlayer player) {
      add(player, "duel_wins", 1);
      add(player, "w_duels", 1);
   }

   public static void onDeliver(ServerPlayer player) {
      add(player, "deliver", 1);
   }

   public static void onBounty(ServerPlayer player) {
      add(player, "bounties", 1);
   }

   public static void onTrade(ServerPlayer player) {
      add(player, "trade", 1);
   }

   public static void onSell(ServerPlayer player, long amount) {
      add(player, "sell", (int)amount);
      add(player, "w_earn", (int)amount);
   }

   private static void add(ServerPlayer player, String id, int amount) {
      Map<String, Integer> map = progress.computeIfAbsent(player.getUUID(), u -> new HashMap<>());
      Challenge c = find(id);
      if (c == null) {
         return;
      }
      int current = map.getOrDefault(id, 0);
      // Already completed today/this week - never award again (anti-spam).
      if (current < 0 || current >= c.target) {
         return;
      }
      int now = Math.min(c.target, current + amount);
      map.put(id, now);
      if (now >= c.target) {
         EconomyManager.addCash(player.getUUID(), c.reward);
         TokenManager.giveGems(player, 1);
         Chat.raw(player, "§6§lCHALLENGE COMPLETE: §e" + c.name + "§r §7- " + c.desc + "! Reward: §a$" + c.reward + "§7 + §51 gem§7.");
         map.put(id, -1); // mark done
      }
   }

   private static Challenge find(String id) {
      for (Challenge c : DAILY_POOL) {
         if (c.id.equals(id)) {
            return c;
         }
      }
      for (Challenge c : WEEKLY_POOL) {
         if (c.id.equals(id)) {
            return c;
         }
      }
      return null;
   }

   private static String today() {
      return LocalDate.now(ZoneId.of("America/New_York")).toString();
   }

   private static String currentWeek() {
      return LocalDate.now(ZoneId.of("America/New_York")).get(WeekFields.of(Locale.US).weekBasedYear())
         + "-W"
         + LocalDate.now(ZoneId.of("America/New_York")).get(WeekFields.of(Locale.US).weekOfWeekBasedYear());
   }

   public static class Challenge {
      public final String id;
      public final String name;
      public final String desc;
      public final String category;
      public final int target;
      public final long reward;

      Challenge(String id, String name, String desc, String category, int target, long reward) {
         this.id = id;
         this.name = name;
         this.desc = desc;
         this.category = category;
         this.target = target;
         this.reward = reward;
      }
   }
}
