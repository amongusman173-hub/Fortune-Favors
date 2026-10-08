package com.fortuneandfavors.economy;

import com.fortuneandfavors.util.Chat;
import com.fortuneandfavors.util.JsonUtil;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;

public final class FirstEverRecordManager {
   private static final Map<String, String> records = new HashMap<>();
   private static Path dataFile;

   private FirstEverRecordManager() {
   }

   public static void load(MinecraftServer server) {
      records.clear();
      dataFile = EconomyManager.getDataDir(server).resolve("first_records.json");
      JsonObject root = JsonUtil.readOrCreate(dataFile, new JsonObject());
      if (root.has("records") && root.get("records").isJsonObject()) {
         for (Entry<String, JsonElement> e : root.getAsJsonObject("records").entrySet()) {
            records.put(e.getKey(), e.getValue().getAsString());
         }
      }
   }

   public static void save(MinecraftServer server) {
      if (dataFile == null) {
         dataFile = EconomyManager.getDataDir(server).resolve("first_records.json");
      }
      JsonObject root = new JsonObject();
      JsonObject rec = new JsonObject();
      for (Entry<String, String> e : records.entrySet()) {
         rec.addProperty(e.getKey(), e.getValue());
      }
      root.add("records", rec);
      JsonUtil.write(dataFile, root);
   }

   public static String get(String key) {
      return records.get(key);
   }

   public static void record(MinecraftServer server, String key, String name) {
      if (!records.containsKey(key)) {
         records.put(key, name);
         broadcast(server, "§5★ FIRST EVER: §f" + display(key) + "§r §7- achieved by " + name + "!");
         save(server);
      }
   }

   public static void onBossKilled(MinecraftServer server, String bossKey, ServerPlayer killer) {
      record(server, "first_boss_" + bossKey, killer.getName().getString());
   }

   public static void onMillionaire(MinecraftServer server, ServerPlayer player) {
      record(server, "first_millionaire", player.getName().getString());
   }

   public static void onMythicSpawn(MinecraftServer server, Mob mob) {
      String name = "a wandering adventurer";
      if (mob.level() instanceof net.minecraft.server.level.ServerLevel sl) {
         ServerPlayer nearest = null;
         double best = Double.MAX_VALUE;

         for (ServerPlayer p : sl.getPlayers(pl -> true)) {
            double d = p.distanceToSqr(mob);
            if (d < best) {
               best = d;
               nearest = p;
            }
         }

         if (nearest != null && best < 64.0 * 64.0) {
            name = nearest.getName().getString();
         }
      }
      record(server, "first_mythic", name);
   }

   public static Map<String, String> all() {
      return records;
   }

   public static String displayForNews(String key) {
      return display(key);
   }

   private static String display(String key) {
      return switch (key) {
         case "first_millionaire" -> "First Millionaire (§a$1,000,000 balance§r§5)";
         case "first_mythic" -> "First Mythic Mob Spawn";
         default -> key.startsWith("first_boss_") ? "First " + BossCodexManager.displayName(key.substring("first_boss_".length())) + " Kill" : key;
      };
   }

   private static void broadcast(MinecraftServer server, String message) {
      if (server == null) {
         return;
      }
      for (ServerPlayer p : server.getPlayerList().getPlayers()) {
         Chat.raw(p, message);
      }
   }
}
