package com.fortuneandfavors.economy;

import com.fortuneandfavors.FortuneFavorsMod;
import com.fortuneandfavors.ModItems;
import com.fortuneandfavors.guild.GuildManager;
import com.fortuneandfavors.util.JsonUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Map.Entry;
import net.minecraft.core.HolderLookup.Provider;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;

public final class EconomyManager {
   private static final Map<UUID, Long> balances = new HashMap<>();
   private static final Map<UUID, Long> gemBalances = new HashMap<>();
   private static final Map<UUID, List<ItemStack>> collection = new HashMap<>();
   private static Path dataFile;
   private static boolean dirty = false;
   private static boolean transferring = false;

   private EconomyManager() {
   }

   public static Path getDataDir(MinecraftServer server) {
      return server.getWorldPath(LevelResource.ROOT).resolve("fortuneandfavors");
   }

   public static void migrateLegacyData(MinecraftServer server) {
      Path root = server.getWorldPath(LevelResource.ROOT);
      Path old = root.resolve("mceconomy");
      Path fresh = root.resolve("fortuneandfavors");
      if (Files.isDirectory(old) && !Files.isDirectory(fresh)) {
         try {
            Files.move(old, fresh);
         } catch (Exception e) {
            FortuneFavorsMod.LOGGER.warn("Could not migrate the old mceconomy data folder", e);
         }
      }
   }

   public static void load(MinecraftServer server) {
      balances.clear();
      gemBalances.clear();
      collection.clear();
      Provider access = server.registryAccess();
      dataFile = getDataDir(server).resolve("economy.json");
      JsonObject root = JsonUtil.readOrCreate(dataFile, new JsonObject());
      if (root.has("balances") && root.get("balances").isJsonArray()) {
         for (JsonElement el : root.getAsJsonArray("balances")) {
            JsonObject obj = el.getAsJsonObject();

            try {
               UUID uuid = UUID.fromString(obj.get("uuid").getAsString());
               long bal = obj.has("balance") ? obj.get("balance").getAsLong() : 0L;
               balances.put(uuid, bal);
            } catch (Exception var11) {
            }
         }
      }

      if (root.has("gems") && root.get("gems").isJsonArray()) {
         for (JsonElement el : root.getAsJsonArray("gems")) {
            JsonObject obj = el.getAsJsonObject();
            try {
               UUID uuid = UUID.fromString(obj.get("uuid").getAsString());
               long g = obj.has("gems") ? obj.get("gems").getAsLong() : 0L;
               gemBalances.put(uuid, g);
            } catch (Exception ignored) {
            }
         }
      }

      if (root.has("collection") && root.get("collection").isJsonArray()) {
         for (JsonElement el : root.getAsJsonArray("collection")) {
            JsonObject obj = el.getAsJsonObject();

            try {
               UUID uuid = UUID.fromString(obj.get("uuid").getAsString());
               List<ItemStack> items = new ArrayList<>();
               if (obj.has("items") && obj.get("items").isJsonArray()) {
                  for (JsonElement itemEl : obj.getAsJsonArray("items")) {
                     ItemStack stack = JsonUtil.jsonToItem(itemEl, access);
                     if (!stack.isEmpty()) {
                        items.add(stack);
                     }
                  }
               }

               collection.put(uuid, items);
            } catch (Exception var12) {
            }
         }
      }

      dirty = false;
   }

   public static void save(MinecraftServer server) {
      if (dataFile == null) {
         dataFile = getDataDir(server).resolve("economy.json");
      }

      Provider access = server.registryAccess();
      JsonObject root = new JsonObject();
      JsonArray balancesArr = new JsonArray();

      for (Entry<UUID, Long> e : balances.entrySet()) {
         if (e.getKey() == null) {
            continue; // guard against phantom balances from older bugs
         }
         JsonObject obj = new JsonObject();
         obj.addProperty("uuid", e.getKey().toString());
         obj.addProperty("balance", e.getValue());
         balancesArr.add(obj);
      }

      root.add("balances", balancesArr);
      JsonArray gemsArr = new JsonArray();
      for (Entry<UUID, Long> e : gemBalances.entrySet()) {
         if (e.getKey() == null || e.getValue() == 0L) continue;
         JsonObject obj = new JsonObject();
         obj.addProperty("uuid", e.getKey().toString());
         obj.addProperty("gems", e.getValue());
         gemsArr.add(obj);
      }
      root.add("gems", gemsArr);
      JsonArray collectionArr = new JsonArray();

      for (Entry<UUID, List<ItemStack>> e : collection.entrySet()) {
         JsonObject obj = new JsonObject();
         obj.addProperty("uuid", e.getKey().toString());
         JsonArray items = new JsonArray();

         for (ItemStack stack : e.getValue()) {
            if (!stack.isEmpty()) {
               JsonElement el = JsonUtil.itemToJson(stack, access);
               if (el != null) {
                  items.add(el);
               }
            }
         }

         obj.add("items", items);
         collectionArr.add(obj);
      }

      root.add("collection", collectionArr);
      JsonUtil.write(dataFile, root);
      dirty = false;
   }

   public static void markDirty() {
      dirty = true;
   }

   public static boolean isDirty() {
      return dirty;
   }

   public static long balance(UUID uuid) {
      return balances.getOrDefault(uuid, 0L);
   }

   public static Map<UUID, Long> allBalances() {
      return balances;
   }

   public static void setBalance(UUID uuid, long amount) {
      balances.put(uuid, Math.max(0L, amount));
      markDirty();
   }

   public static long addCash(UUID uuid, long amount) {
      long bal = balance(uuid);
      long add = Math.max(0L, amount);
      if (add > 0L && uuid != null && !transferring) {
         add = GuildManager.applyCoinPerk(uuid, add);
      }

      long next = bal > Long.MAX_VALUE - add ? Long.MAX_VALUE : bal + add;
      balances.put(uuid, next);
      if (add > 0L && uuid != null) {
         GuildManager.addWealth(uuid, add);
      }

      markDirty();
      return next;
   }

   public static boolean hasCash(UUID uuid, long amount) {
      return balance(uuid) >= amount;
   }

   public static boolean takeCash(UUID uuid, long amount) {
      if (amount < 0L) {
         return false;
      }

      long bal = balance(uuid);
      if (bal < amount) {
         return false;
      }

      balances.put(uuid, bal - amount);
      markDirty();
      return true;
   }

   public static boolean pay(UUID from, UUID to, long amount) {
      if (amount >= 0L && hasCash(from, amount)) {
         takeCash(from, amount);
         transferring = true;

         try {
            addCash(to, amount);
         } finally {
            transferring = false;
         }

         return true;
      } else {
         return false;
      }
   }

   // ---------- Gem balance (separate currency from favor tokens) ----------

   public static long gemBalance(UUID uuid) {
      return gemBalances.getOrDefault(uuid, 0L);
   }

   public static void addGems(UUID uuid, long amount) {
      if (amount <= 0L || uuid == null) return;
      long bal = gemBalance(uuid);
      long next = bal > Long.MAX_VALUE - amount ? Long.MAX_VALUE : bal + amount;
      gemBalances.put(uuid, next);
      markDirty();
   }

   public static boolean takeGems(UUID uuid, long amount) {
      if (amount < 0L) return false;
      long bal = gemBalance(uuid);
      if (bal < amount) return false;
      gemBalances.put(uuid, bal - amount);
      markDirty();
      return true;
   }

   public static boolean hasGems(UUID uuid, long amount) {
      return gemBalance(uuid) >= amount;
   }

   public static void giveItem(UUID uuid, ItemStack stack) {
      if (!stack.isEmpty()) {
         collection.computeIfAbsent(uuid, k -> new ArrayList<>()).add(stack.copy());
         markDirty();
      }
   }

   public static int collectionCount(UUID uuid) {
      List<ItemStack> list = collection.get(uuid);
      if (list == null) {
         return 0;
      }

      int count = 0;

      for (ItemStack s : list) {
         count += s.getCount();
      }

      return count;
   }

   public static boolean hasPending(UUID uuid) {
      List<ItemStack> list = collection.get(uuid);
      return list != null && !list.isEmpty();
   }

   public static boolean removeFromCollection(UUID uuid, Item item, int amount) {
      List<ItemStack> list = collection.get(uuid);
      if (list != null && amount > 0) {
         int available = 0;

         for (ItemStack s : list) {
            if (s.is(item)) {
               available += s.getCount();
            }
         }

         if (available < amount) {
            return false;
         }

         int remaining = amount;
         List<ItemStack> leftovers = new ArrayList<>();

         for (ItemStack s : list) {
            if (s.is(item) && remaining > 0) {
               int take = Math.min(s.getCount(), remaining);
               s.shrink(take);
               remaining -= take;
            }

            if (!s.isEmpty()) {
               leftovers.add(s);
            }
         }

         if (leftovers.isEmpty()) {
            collection.remove(uuid);
         } else {
            collection.put(uuid, leftovers);
         }

         markDirty();
         return true;
      } else {
         return false;
      }
   }

   public static List<ItemStack> removeTokensFromCollection(UUID uuid, int amount) {
      List<ItemStack> list = collection.get(uuid);
      if (list != null && amount > 0) {
         int remaining = amount;
         List<ItemStack> removed = new ArrayList<>();
         List<ItemStack> leftovers = new ArrayList<>();

         for (ItemStack s : list) {
            if (ModItems.isToken(s) && remaining > 0) {
               int take = Math.min(s.getCount(), remaining);
               s.shrink(take);
               remaining -= take;
               removed.add(s.copyWithCount(take));
            }

            if (!s.isEmpty()) {
               leftovers.add(s);
            }
         }

         if (leftovers.isEmpty()) {
            collection.remove(uuid);
         } else {
            collection.put(uuid, leftovers);
         }

         markDirty();
         return removed;
      } else {
         return List.of();
      }
   }

   public static int claimItems(ServerPlayer player) {
      UUID uuid = player.getUUID();
      List<ItemStack> list = collection.remove(uuid);
      if (list == null) {
         return 0;
      }

      int claimed = 0;
      List<ItemStack> leftovers = new ArrayList<>();

      for (ItemStack stack : list) {
         if (!stack.isEmpty()) {
            int before = stack.getCount();
            if (player.getInventory().add(stack)) {
               claimed += before - stack.getCount();
               if (!stack.isEmpty()) {
                  leftovers.add(stack);
               }
            } else {
               leftovers.add(stack);
            }
         }
      }

      if (!leftovers.isEmpty()) {
         collection.put(uuid, leftovers);
      }

      markDirty();
      return claimed;
   }
}
