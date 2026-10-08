package com.fortuneandfavors.economy;

import com.fortuneandfavors.util.Chat;
import com.fortuneandfavors.util.JsonUtil;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Map.Entry;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Weekly leaderboard: richest, boss slayers, bounty hunters and job workers.
 * Every Monday 00:00 EST the top three of each category are announced and paid.
 */
public final class LeaderboardManager {
   public enum Category {
      MONEY("Richest Players", "§6", "$"),
      BOSSES("Boss Slayers", "§c", "kills"),
      BOUNTIES("Bounty Hunters", "§e", "bounties"),
      JOBS("Hard Workers", "§b", "jobs");

      public final String name;
      public final String color;
      public final String unit;

      Category(String name, String color, String unit) {
         this.name = name;
         this.color = color;
         this.unit = unit;
      }
   }

   private static final class Stats {
      int bosses;
      int bounties;
      int jobs;
   }

   private static final long WEEK_MS = 7L * 24L * 60L * 60L * 1000L;
   private static final Map<UUID, Stats> stats = new HashMap<>();
   private static long weekStartMs;
   private static Path dataFile;

   private LeaderboardManager() {
   }

   public static void load(MinecraftServer server) {
      stats.clear();
      dataFile = EconomyManager.getDataDir(server).resolve("leaderboard.json");
      JsonObject root = JsonUtil.readOrCreate(dataFile, new JsonObject());
      weekStartMs = root.has("weekStart") ? root.get("weekStart").getAsLong() : 0L;
      if (root.has("stats") && root.get("stats").isJsonObject()) {
         for (Entry<String, JsonElement> e : root.getAsJsonObject("stats").entrySet()) {
            try {
               UUID uuid = UUID.fromString(e.getKey());
               JsonObject obj = e.getValue().getAsJsonObject();
               Stats s = new Stats();
               s.bosses = obj.has("bosses") ? obj.get("bosses").getAsInt() : 0;
               s.bounties = obj.has("bounties") ? obj.get("bounties").getAsInt() : 0;
               s.jobs = obj.has("jobs") ? obj.get("jobs").getAsInt() : 0;
               stats.put(uuid, s);
            } catch (Exception ignored) {
            }
         }
      }
      long monday = currentMondayMs();
      if (weekStartMs == 0L) {
         weekStartMs = monday;
         save(server);
      } else if (weekStartMs < monday) {
         finalizeWeek(server);
         weekStartMs = monday;
         stats.clear();
         save(server);
      }
   }

   public static void save(MinecraftServer server) {
      if (dataFile == null) {
         dataFile = EconomyManager.getDataDir(server).resolve("leaderboard.json");
      }
      JsonObject root = new JsonObject();
      root.addProperty("weekStart", weekStartMs);
      JsonObject statsObj = new JsonObject();
      for (Entry<UUID, Stats> e : stats.entrySet()) {
         JsonObject obj = new JsonObject();
         obj.addProperty("bosses", e.getValue().bosses);
         obj.addProperty("bounties", e.getValue().bounties);
         obj.addProperty("jobs", e.getValue().jobs);
         statsObj.add(e.getKey().toString(), obj);
      }
      root.add("stats", statsObj);
      JsonUtil.write(dataFile, root);
   }

   public static void onBossKill(ServerPlayer p) {
      stats.computeIfAbsent(p.getUUID(), u -> new Stats()).bosses++;
      markDirty();
   }

   public static void onBountyClaimed(ServerPlayer p) {
      stats.computeIfAbsent(p.getUUID(), u -> new Stats()).bounties++;
      markDirty();
   }

   public static void onJobClaimed(ServerPlayer p) {
      stats.computeIfAbsent(p.getUUID(), u -> new Stats()).jobs++;
      markDirty();
   }

   private static boolean dirty = false;

   private static void markDirty() {
      dirty = true;
   }

   public static boolean isDirty() {
      return dirty;
   }

   public static void clearDirty() {
      dirty = false;
   }

   public static long value(UUID uuid, Category cat) {
      if (cat == Category.MONEY) {
         return EconomyManager.balance(uuid);
      }
      Stats s = stats.get(uuid);
      if (s == null) {
         return 0L;
      }
      return switch (cat) {
         case BOSSES -> s.bosses;
         case BOUNTIES -> s.bounties;
         default -> s.jobs;
      };
   }

   public static List<Entry<UUID, Long>> ranking(Category cat, int limit) {
      List<Entry<UUID, Long>> out = new ArrayList<>();
      if (cat == Category.MONEY) {
         for (Entry<UUID, Long> e : EconomyManager.allBalances().entrySet()) {
            if (e.getValue() > 0L) {
               out.add(e);
            }
         }
      } else {
         for (Entry<UUID, Stats> e : stats.entrySet()) {
            long v = value(e.getKey(), cat);
            if (v > 0L) {
               out.add(Map.entry(e.getKey(), v));
            }
         }
      }
      out.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
      return out.size() > limit ? out.subList(0, limit) : out;
   }

   public static int playerRank(UUID uuid, Category cat) {
      List<Entry<UUID, Long>> all = new ArrayList<>();
      if (cat == Category.MONEY) {
         for (Entry<UUID, Long> e : EconomyManager.allBalances().entrySet()) {
            all.add(e);
         }
      } else {
         for (Entry<UUID, Stats> e : stats.entrySet()) {
            all.add(Map.entry(e.getKey(), value(e.getKey(), cat)));
         }
      }
      all.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
      for (int i = 0; i < all.size(); i++) {
         if (all.get(i).getKey().equals(uuid)) {
            return i + 1;
         }
      }
      return -1;
   }

   public static long timeUntilResetMs() {
      return Math.max(0L, weekStartMs + WEEK_MS - System.currentTimeMillis());
   }

   public static long weekStartMs() {
      return weekStartMs;
   }

   public static void tick(MinecraftServer server) {
      if (System.currentTimeMillis() >= weekStartMs + WEEK_MS) {
         finalizeWeek(server);
         weekStartMs = currentMondayMs();
         stats.clear();
         save(server);
      }
   }

   private static void finalizeWeek(MinecraftServer server) {
      long[] payouts = {50000L, 25000L, 10000L};
      for (Category cat : Category.values()) {
         List<Entry<UUID, Long>> top = ranking(cat, 3);
         if (top.isEmpty()) {
            continue;
         }
         server.getPlayerList()
            .broadcastSystemMessage(
               Component.literal(
                  Chat.colorize("§8§m                                                      §r\n§6§l WEEKLY LEADERBOARD - " + cat.color + cat.name + "§r")
               ),
               false
            );
         for (int i = 0; i < top.size(); i++) {
            Entry<UUID, Long> e = top.get(i);
            ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
            String name = p != null ? p.getName().getString() : "Someone";
            if (i < payouts.length) {
               EconomyManager.addCash(e.getKey(), payouts[i]);
            }
            String medal = i == 0 ? "§6" : i == 1 ? "§7" : "§c";
            server.getPlayerList()
               .broadcastSystemMessage(
                  Component.literal(
                     Chat.colorize(
                        medal + (i + 1) + ". §f" + name + " §7- " + cat.color + format(e.getValue()) + " " + cat.unit
                           + (i < payouts.length ? " §7→ §a$" + payouts[i] : "")
                     )
                  ),
                  false
               );
         }
      }
      server.getPlayerList()
         .broadcastSystemMessage(
            Component.literal(Chat.colorize("§8§m                                                      §r\n§7A new week begins - go set some records!")),
            false
         );
   }

   private static String format(long v) {
      if (v >= 1_000_000L) {
         return String.format("%.1fM", v / 1_000_000.0);
      }
      if (v >= 1_000L) {
         return String.format("%.1fk", v / 1_000.0);
      }
      return String.valueOf(v);
   }

   private static long currentMondayMs() {
      ZonedDateTime monday = ZonedDateTime.now(ZoneId.of("America/New_York")).with(DayOfWeek.MONDAY).with(LocalTime.MIDNIGHT);
      return monday.toInstant().toEpochMilli();
   }
}
