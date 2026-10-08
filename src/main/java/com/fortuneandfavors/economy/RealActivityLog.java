package com.fortuneandfavors.economy;

import com.fortuneandfavors.util.Chat;
import com.fortuneandfavors.util.JsonUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * A rolling log of REAL server events (boss kills, duels, bounties, auctions,
 * guild wars, big sales, mystery jackpots) used by the daily newspaper so the
 * paper reports what actually happened instead of generic filler lines.
 */
public final class RealActivityLog {
   private static final Deque<String> events = new ArrayDeque<>();
   private static final int MAX_EVENTS = 48;
   private static Path dataFile;

   private RealActivityLog() {
   }

   public static void log(MinecraftServer server, String line) {
      events.addLast(line);
      while (events.size() > MAX_EVENTS) {
         events.removeFirst();
      }
      save(server);
   }

   public static void bossSlain(MinecraftServer server, String bossName, ServerPlayer killer) {
      log(server, bossName + " was slain by " + killer.getName().getString() + "!");
   }

   public static void cash(MinecraftServer server, String playerName, long amount, String what) {
      log(server, playerName + " earned " + Chat.moneyStr(amount) + " from " + what + ".");
   }

   public static void milestone(MinecraftServer server, String playerName, String what) {
      log(server, playerName + " " + what + ".");
   }

   public static List<String> recentLines(MinecraftServer server) {
      java.util.List<String> out = new java.util.ArrayList<>();
      int i = 0;
      for (String e : events) {
         if (i >= 24) {
            break;
         }
         out.add(e);
         i++;
      }
      return out;
   }

   public static void load(MinecraftServer server) {
      events.clear();
      dataFile = com.fortuneandfavors.economy.EconomyManager.getDataDir(server).resolve("activity_log.json");
      JsonObject root = JsonUtil.readOrCreate(dataFile, new JsonObject());
      if (root.has("events") && root.get("events").isJsonArray()) {
         for (JsonElement el : root.getAsJsonArray("events")) {
            if (el.isJsonPrimitive()) {
               events.add(el.getAsString());
            }
         }
      }
   }

   public static void save(MinecraftServer server) {
      if (dataFile == null) {
         dataFile = com.fortuneandfavors.economy.EconomyManager.getDataDir(server).resolve("activity_log.json");
      }
      try {
         JsonObject root = new JsonObject();
         JsonArray arr = new JsonArray();
         for (String e : events) {
            arr.add(e);
         }
         root.add("events", arr);
         JsonUtil.write(dataFile, root);
      } catch (Exception ignored) {
      }
   }
}