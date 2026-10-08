package com.fortuneandfavors.economy;

import com.fortuneandfavors.util.JsonUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;

public final class PermissionManager {
   private static final Set<UUID> economyAdmins = new HashSet<>();
   private static Path dataFile;

   private PermissionManager() {
   }

   public static void load(MinecraftServer server) {
      economyAdmins.clear();
      dataFile = EconomyManager.getDataDir(server).resolve("perms.json");
      JsonObject root = JsonUtil.readOrCreate(dataFile, new JsonObject());
      if (root.has("economy_admins") && root.get("economy_admins").isJsonArray()) {
         for (JsonElement el : root.getAsJsonArray("economy_admins")) {
            try {
               economyAdmins.add(UUID.fromString(el.getAsString()));
            } catch (Exception var5) {
            }
         }
      }
   }

   public static void save(MinecraftServer server) {
      if (dataFile == null) {
         dataFile = EconomyManager.getDataDir(server).resolve("perms.json");
      }

      JsonObject root = new JsonObject();
      JsonArray arr = new JsonArray();

      for (UUID uuid : economyAdmins) {
         arr.add(uuid.toString());
      }

      root.add("economy_admins", arr);
      JsonUtil.write(dataFile, root);
   }

   public static boolean isEconomyAdmin(UUID uuid) {
      return economyAdmins.contains(uuid);
   }

   public static boolean grantEconomyAdmin(UUID uuid) {
      return economyAdmins.add(uuid);
   }

   public static boolean revokeEconomyAdmin(UUID uuid) {
      return economyAdmins.remove(uuid);
   }

   public static Set<UUID> economyAdmins() {
      return economyAdmins;
   }
}
