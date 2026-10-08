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

public final class TitleManager {
   /** The mod creator's exclusive title. Once equipped it stays on until the
    *  player explicitly unequips it - equipping any other title won't replace
    *  it while it's active. */
   public static final String CREATOR_TITLE = "Creator of Mod";
   private static final String CREATOR_NAME = "Poorboy7890";
   private static final Map<UUID, Set<String>> unlocked = new HashMap<>();
   private static final Map<UUID, String> active = new HashMap<>();
   private static Path dataFile;

   private TitleManager() {
   }

   public static void load(MinecraftServer server) {
      unlocked.clear();
      active.clear();
      dataFile = EconomyManager.getDataDir(server).resolve("titles.json");
      JsonObject root = JsonUtil.readOrCreate(dataFile, new JsonObject());
      if (root.has("players") && root.get("players").isJsonObject()) {
         for (Entry<String, JsonElement> e : root.getAsJsonObject("players").entrySet()) {
            try {
               UUID uuid = UUID.fromString(e.getKey());
               JsonObject obj = e.getValue().getAsJsonObject();
               Set<String> set = new HashSet<>();
               if (obj.has("unlocked") && obj.get("unlocked").isJsonArray()) {
                  for (JsonElement t : obj.getAsJsonArray("unlocked")) {
                     set.add(t.getAsString());
                  }
               }
               unlocked.put(uuid, set);
               if (obj.has("active")) {
                  String a = obj.get("active").getAsString();
                  if (set.contains(a)) {
                     active.put(uuid, a);
                  }
               }
            } catch (Exception ignored) {
            }
         }
      }
   }

   public static void save(MinecraftServer server) {
      if (dataFile == null) {
         dataFile = EconomyManager.getDataDir(server).resolve("titles.json");
      }
      JsonObject root = new JsonObject();
      JsonObject players = new JsonObject();
      for (Entry<UUID, Set<String>> e : unlocked.entrySet()) {
         JsonObject obj = new JsonObject();
         JsonArray arr = new JsonArray();
         for (String t : e.getValue()) {
            arr.add(t);
         }
         obj.add("unlocked", arr);
         String act = active.get(e.getKey());
         if (act != null) {
            obj.addProperty("active", act);
         }
         players.add(e.getKey().toString(), obj);
      }
      root.add("players", players);
      JsonUtil.write(dataFile, root);
   }

   /**
    * Forgets everything this player has unlocked or is wearing.
    *
    * <p>For the self-test: titles are persisted, so a test that unlocks one and then asserts which
    * badge is worn would pass on a clean server and fail on the second run - which is not a test of
    * the rule, it is a test of the order the tests ran in.
    */
   public static void forgetForTest(UUID uuid) {
      unlocked.remove(uuid);
      active.remove(uuid);
   }

   public static boolean has(UUID uuid, String title) {
      Set<String> set = unlocked.get(uuid);
      return set != null && set.contains(title);
   }

   public static void unlock(ServerPlayer player, String title) {
      if (player == null || title == null || title.isBlank()) {
         return;
      }
      Set<String> set = unlocked.computeIfAbsent(player.getUUID(), u -> new HashSet<>());
      if (set.add(title)) {
         if (!active.containsKey(player.getUUID())) {
            active.put(player.getUUID(), title);
         }
         Chat.raw(player, "§6§lNew title unlocked: §e" + title + "§6§l!");
         Chat.raw(player, "§7Switch titles with §f/ff title <name>§7. See all with §f/ff title list§7.");
      }
   }

   public static boolean setActive(ServerPlayer player, String title) {
      Set<String> set = unlocked.get(player.getUUID());
      if (set == null || !set.contains(title)) {
         return false;
      }
      String current = active.get(player.getUUID());
      // The Creator of Mod title is sticky: it stays on until explicitly
      // unequipped, so switching titles can't quietly replace it.
      if (CREATOR_TITLE.equals(current) && !CREATOR_TITLE.equals(title)) {
         Chat.raw(player, "§cYour §eCreator of Mod §ctitle stays on until you unequip it first (§f/ff title off§c).");
         return false;
      }
      active.put(player.getUUID(), title);
      Chat.raw(player, "§6Active title set to: §e" + title);
      return true;
   }

   /** Explicitly removes the active title - the only way to drop the sticky
    *  Creator of Mod title once it has been equipped. */
   public static void clearActive(ServerPlayer player) {
      String was = active.remove(player.getUUID());
      if (was != null) {
         Chat.raw(player, "§7Title unequipped" + (CREATOR_TITLE.equals(was) ? " - §eCreator of Mod §7is off." : "."));
      }
   }

   /** True for the mod creator (matched by name, same as the Creator tag). */
   public static boolean isCreator(ServerPlayer player) {
      return player != null && CREATOR_NAME.equalsIgnoreCase(player.getName().getString());
   }

   /**
    * Unlocks the exclusive creator title for the mod creator, and wears it (no-op for anybody
    * else).
    *
    * <p>It is equipped rather than merely unlocked, which is a change and the point of one: the
    * creator's badge used to arrive as an automatic <i>tag</i> in {@link TagManager} with the same
    * name as this title, so the mod author had two of the same badge and no way to tell which one
    * they were looking at. With the tag gone, the title has to be worn by default or the creator
    * would have no badge at all - and every other title in this class is auto-equipped the first
    * time it is unlocked, so this is the same rule rather than a special case.
    */
   public static void unlockCreator(ServerPlayer player) {
      if (!isCreator(player)) {
         return;
      }
      Set<String> set = unlocked.computeIfAbsent(player.getUUID(), u -> new HashSet<>());
      if (set.add(CREATOR_TITLE)) {
         active.putIfAbsent(player.getUUID(), CREATOR_TITLE);
         Chat.raw(player, "§6§lExclusive title unlocked: §e" + CREATOR_TITLE + "§6§l!");
         Chat.raw(player, "§7It is on now. Swap or remove it in §b/menu → Tags & Titles§7.");
      }
   }

   public static String activeTitle(UUID uuid) {
      return active.get(uuid);
   }

   public static Set<String> unlockedTitles(UUID uuid) {
      return unlocked.getOrDefault(uuid, Set.of());
   }

   public static String activeTitleText(UUID uuid) {
      String t = active.get(uuid);
      return t == null ? null : t;
   }
}
