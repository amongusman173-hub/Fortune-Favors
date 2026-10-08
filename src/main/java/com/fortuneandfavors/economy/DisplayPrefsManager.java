package com.fortuneandfavors.economy;

import com.fortuneandfavors.util.JsonUtil;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.Map.Entry;
import net.minecraft.server.MinecraftServer;

/**
 * Per-player display preferences: where a player's title and tag render
 * relative to their name in chat and the tab list, the Death Compass FX
 * toggle, and whether their tag/title show at all.
 */
public final class DisplayPrefsManager {
   /** Default: title before the name, tag after it - [Title] Name [Tag]. */
   public static final String POS_DEFAULT = "split";
   /** Everything before the name - [Title] [Tag] Name. */
   public static final String POS_BEFORE = "before";
   /** Everything after the name - Name [Title] [Tag]. */
   public static final String POS_AFTER = "after";

   private static final Map<UUID, String> positions = new HashMap<>();
   private static final Map<UUID, Boolean> compassFx = new HashMap<>();
   private static final Map<UUID, Boolean> hiddenTags = new HashMap<>();
   private static Path dataFile;

   private DisplayPrefsManager() {
   }

   public static void load(MinecraftServer server) {
      positions.clear();
      dataFile = EconomyManager.getDataDir(server).resolve("display_prefs.json");
      JsonObject root = JsonUtil.readOrCreate(dataFile, new JsonObject());
      if (root.has("players") && root.get("players").isJsonObject()) {
         for (Entry<String, JsonElement> e : root.getAsJsonObject("players").entrySet()) {
            try {
               UUID uuid = UUID.fromString(e.getKey());
               JsonElement val = e.getValue();
               if (val.isJsonPrimitive()) {
                  String pos = val.getAsString();
                  if (pos.equals(POS_BEFORE) || pos.equals(POS_AFTER)) {
                     positions.put(uuid, pos);
                  }
               } else if (val.isJsonObject()) {
                  // Newer per-player object: { "pos": ..., "compass_fx": false }
                  JsonObject obj = val.getAsJsonObject();
                  String pos = com.fortuneandfavors.util.JsonUtil.jsonString(obj, "pos", "");
                  if (pos.equals(POS_BEFORE) || pos.equals(POS_AFTER)) {
                     positions.put(uuid, pos);
                  }
                  if (!com.fortuneandfavors.util.JsonUtil.jsonBool(obj, "compass_fx", true)) {
                     compassFx.put(uuid, false);
                  }
                  if (com.fortuneandfavors.util.JsonUtil.jsonBool(obj, "hide_tags", false)) {
                     hiddenTags.put(uuid, true);
                  }
               }
            } catch (Exception ignored) {
            }
         }
      }
   }

   public static void save(MinecraftServer server) {
      if (dataFile == null) {
         dataFile = EconomyManager.getDataDir(server).resolve("display_prefs.json");
      }
      java.util.Set<UUID> ids = new java.util.LinkedHashSet<>();
      ids.addAll(positions.keySet());
      ids.addAll(compassFx.keySet());
      ids.addAll(hiddenTags.keySet());
      JsonObject root = new JsonObject();
      JsonObject players = new JsonObject();
      for (UUID id : ids) {
         JsonObject obj = new JsonObject();
         String pos = positions.get(id);
         obj.addProperty("pos", pos == null ? POS_DEFAULT : pos);
         if (Boolean.FALSE.equals(compassFx.get(id))) {
            obj.addProperty("compass_fx", false);
         }
         if (Boolean.TRUE.equals(hiddenTags.get(id))) {
            obj.addProperty("hide_tags", true);
         }
         players.add(id.toString(), obj);
      }
      root.add("players", players);
      JsonUtil.write(dataFile, root);
   }

   /** The current position mode for a player; defaults to {@link #POS_DEFAULT}. */
   public static String positionOf(UUID uuid) {
      return positions.getOrDefault(uuid, POS_DEFAULT);
   }

   public static void setPosition(UUID uuid, String pos) {
      if (pos.equals(POS_BEFORE) || pos.equals(POS_AFTER)) {
         positions.put(uuid, pos);
      } else {
         positions.remove(uuid);
      }
   }

   /** Cycles default -> before -> after -> default and returns the new mode. */
   public static String cyclePosition(UUID uuid) {
      String next = switch (positionOf(uuid)) {
         case POS_DEFAULT -> POS_BEFORE;
         case POS_BEFORE -> POS_AFTER;
         default -> POS_DEFAULT;
      };
      setPosition(uuid, next);
      return next;
   }

   /** Whether the Death Compass's grave particles (tracking beam, grave pulse,
    *  sonar beacon) are shown for this player. The action-bar distance always
    *  shows; only the visual effects can be silenced. */
   public static boolean compassFx(UUID uuid) {
      return !Boolean.FALSE.equals(compassFx.get(uuid));
   }

   public static void setCompassFx(UUID uuid, boolean on) {
      if (on) {
         compassFx.remove(uuid);
      } else {
         compassFx.put(uuid, false);
      }
   }

   /** Whether this player's tag & title render next to their name (default
    *  true). Turning it off hides them in chat and the tab list for everyone.
    *  The server-issued WANTED marker stays - it's bounty gameplay info. */
   public static boolean showTags(UUID uuid) {
      return !Boolean.TRUE.equals(hiddenTags.get(uuid));
   }

   public static void setShowTags(UUID uuid, boolean on) {
      if (on) {
         hiddenTags.remove(uuid);
      } else {
         hiddenTags.put(uuid, true);
      }
   }
}
