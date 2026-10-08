package com.fortuneandfavors.economy;

import com.fortuneandfavors.FortuneFavorsMod;
import com.fortuneandfavors.ModSounds;
import com.fortuneandfavors.economy.ClaimManager.Claim;
import com.fortuneandfavors.economy.ClaimManager.ClaimResult;
import com.fortuneandfavors.guild.GuildManager;
import com.fortuneandfavors.guild.GuildManager.Guild;
import com.fortuneandfavors.util.Chat;
import com.fortuneandfavors.util.JsonUtil;
import com.fortuneandfavors.util.SoundUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.Map.Entry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap.Types;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Set;

public final class ClaimManager {
   public static final long CHUNK_CLAIMER_PRICE = 2560L;
   /** Half of {@link #CHUNK_CLAIMER_PRICE}: refunded when a claim is abandoned. */
   public static final long ABANDON_REFUND = CHUNK_CLAIMER_PRICE / 2L;
   public static final int BASE_CLAIMS_PER_PLAYER = 6;
   public static final int MAX_CLAIMS_HARD_CAP = 24;
   public static final long SLOT_UPGRADE_COST = 25000L;
   public static final long SLOT_UPGRADE_STEP = 15000L;
   public static final int BORDER_FULL = 0;
   public static final int BORDER_SMALL = 1;
   public static final int BORDER_OFF = 2;
   private static final Map<String, List<Claim>> claimsByDim = new HashMap<>();
   private static final Map<UUID, Long> lastWarn = new HashMap<>();
   private static final Map<UUID, String> lastClaimSeen = new HashMap<>();
   private static final Map<UUID, Integer> borderModes = new HashMap<>();
   private static final Map<UUID, Integer> extraSlots = new HashMap<>();
   /** Claim the player asked to abandon with /claim abandon, waiting for the
    *  /claim abandon confirm. Kept server-side (not re-derived from where the
    *  player is standing) so walking out of the chunk between the two commands
    *  can no longer make the confirmation fail with "you're not in a claim". */
   private static final Map<UUID, Claim> pendingAbandon = new HashMap<>();
   private static Path dataFile;
   private static boolean loadHealthy = true;
   private static int loadedClaims = 0;
   private static final int BORDER_RANGE = 128;
   private static final int MAX_CLAIMS_PER_PASS = 16;

   private ClaimManager() {
   }

   public static void load(MinecraftServer server) {
      claimsByDim.clear();
      lastClaimSeen.clear();
      extraSlots.clear();
      pendingAbandon.clear();
      dataFile = EconomyManager.getDataDir(server).resolve("claims.json");

      try {
         boolean fileExisted = Files.exists(dataFile);
         loadHealthy = true;
         loadedClaims = 0;
         JsonObject root = JsonUtil.readOrCreate(dataFile, new JsonObject());
         int rawCount = 0;
         if (root.has("claims") && root.get("claims").isJsonArray()) {
            JsonArray arr = root.getAsJsonArray("claims");
            rawCount = arr.size();

            for (JsonElement el : arr) {
               Claim c = parseClaim(el);
               if (c != null) {
                  claimsByDim.computeIfAbsent(c.dimension, k -> new ArrayList<>()).add(c);
                  loadedClaims++;
               } else {
                  FortuneFavorsMod.LOGGER.warn("Skipped an unreadable claim in claims.json");
               }
            }
         }

         borderModes.clear();
         if (root.has("vfx") && root.get("vfx").isJsonObject()) {
            for (Entry<String, JsonElement> e : root.getAsJsonObject("vfx").entrySet()) {
               try {
                  JsonElement v = e.getValue();
                  if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber()) {
                     borderModes.put(UUID.fromString(e.getKey()), Math.floorMod(v.getAsInt(), 3));
                  } else {
                     borderModes.put(UUID.fromString(e.getKey()), v.getAsBoolean() ? 0 : 1);
                  }
               } catch (Exception var8) {
               }
            }
         }

         readUpgrades(root, "slots", extraSlots);
         int repaired = repairOverlaps();
         if (repaired > 0) {
            FortuneFavorsMod.LOGGER.warn("Repaired {} overlapping claims in claims.json", repaired);
            save(server);
         }

         if (fileExisted && !root.has("claims")) {
            FortuneFavorsMod.LOGGER.warn("{} exists but could not be read - recovering claims from backups", dataFile);
            int recovered = recoverFromBackups();
            if (recovered > 0) {
               repairOverlaps();
               save(server);
            } else {
               loadHealthy = false;
               FortuneFavorsMod.LOGGER
                  .error(
                     "NO claims could be loaded from {} or any backup! Writes are DISABLED until the data is fixed - run /claim recover or restore a backup manually.",
                     dataFile
                  );
            }
         } else if (rawCount > 0 && loadedClaims == 0) {
            FortuneFavorsMod.LOGGER.warn("claims.json held {} claims but none could be read - recovering from backups", rawCount);
            int recovered = recoverFromBackups();
            if (recovered > 0) {
               loadHealthy = true;
               repairOverlaps();
               save(server);
            } else {
               loadHealthy = false;
               FortuneFavorsMod.LOGGER
                  .error(
                     "NO claims could be read from {} or any backup! Writes are DISABLED until the data is fixed - run /claim recover or restore a backup manually.",
                     dataFile
                  );
            }
         } else if (loadedClaims > 0 && loadedClaims < rawCount) {
            FortuneFavorsMod.LOGGER.warn("Loaded {}/{} claims from {} - merging missing entries from backups", new Object[]{loadedClaims, rawCount, dataFile});
            int recovered = recoverFromBackups();
            if (recovered > 0) {
               loadHealthy = true;
               repairOverlaps();
               save(server);
            }
         } else if (fileExisted && rawCount == 0 && loadedClaims == 0 && root.has("claims")) {
            int recovered = recoverFromBackups();
            if (recovered > 0) {
               loadHealthy = true;
               repairOverlaps();
               save(server);
               FortuneFavorsMod.LOGGER.warn("claims.json was empty - {} claim(s) were restored from backups", recovered);
            }
         }

         if (loadHealthy) {
            FortuneFavorsMod.LOGGER.info("Loaded {} claims from {} (writes enabled)", totalClaims(), dataFile);
         } else {
            FortuneFavorsMod.LOGGER.error(
               "Loaded {} claims from {} but CLAIM WRITES ARE DISABLED: nothing could be recovered from the file or its backups, "
                  + "so new claims are refused instead of being silently lost. Run /claim recover or restore {} by hand.",
               totalClaims(), dataFile, dataFile
            );
         }
      } catch (Exception e) {
         loadHealthy = false;
         FortuneFavorsMod.LOGGER.error("Claims load failed - writes are disabled to protect the on-disk claims.json", e);
      }
   }

   /** False when the last load could not read (or recover) claims.json, in which
    *  case {@link #save(MinecraftServer)} refuses to touch the file. Claims that
    *  cannot be written are never sold, so a player is never charged for land
    *  that would vanish on the next restart. */
   public static boolean writable() {
      return loadHealthy;
   }

   private static int totalClaims() {
      int n = 0;

      for (List<Claim> list : claimsByDim.values()) {
         n += list.size();
      }

      return n;
   }

   private static int repairOverlaps() {
      List<Claim> all = new ArrayList<>();

      for (List<Claim> list : claimsByDim.values()) {
         all.addAll(list);
      }

      int fixed = 0;

      for (int i = 0; i < all.size(); i++) {
         Claim a = all.get(i);
         if (claimsContain(a)) {
            for (int j = i + 1; j < all.size(); j++) {
               Claim b = all.get(j);
               if (claimsContain(b) && a.dimension.equals(b.dimension) && rectOverlaps(a.minX, a.minZ, a.maxX, a.maxZ, b.minX, b.minZ, b.maxX, b.maxZ)) {
                  if (a.owner.equals(b.owner)) {
                     a.minX = Math.min(a.minX, b.minX);
                     a.maxX = Math.max(a.maxX, b.maxX);
                     a.minZ = Math.min(a.minZ, b.minZ);
                     a.maxZ = Math.max(a.maxZ, b.maxZ);
                     removeClaim(b);
                  } else {
                     removeClaim(b);
                  }

                  fixed++;
               }
            }
         }
      }

      return fixed;
   }

   private static boolean claimsContain(Claim c) {
      List<Claim> list = claimsByDim.get(c.dimension);
      return list != null && list.contains(c);
   }

   private static void removeClaim(Claim c) {
      List<Claim> list = claimsByDim.get(c.dimension);
      if (list != null) {
         list.remove(c);
         if (list.isEmpty()) {
            claimsByDim.remove(c.dimension);
         }
      }
   }

   private static boolean jsonFlag(JsonObject obj, String key, boolean def) {
      if (!obj.has(key)) {
         return def;
      }

      JsonElement el = obj.get(key);
      if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isBoolean()) {
         return el.getAsBoolean();
      }

      try {
         return el.getAsLong() != 0L;
      } catch (Exception e) {
         return def;
      }
   }

   private static void readUpgrades(JsonObject root, String key, Map<UUID, Integer> into) {
      if (root.has(key) && root.get(key).isJsonObject()) {
         for (Entry<String, JsonElement> e : root.getAsJsonObject(key).entrySet()) {
            try {
               into.put(UUID.fromString(e.getKey()), e.getValue().getAsInt());
            } catch (Exception var6) {
            }
         }
      }
   }

   private static Claim parseClaim(JsonElement el) {
      try {
         if (!el.isJsonObject()) {
            return null;
         }

         JsonObject obj = el.getAsJsonObject();
         String dimension = JsonUtil.jsonString(obj, "dimension", null);
         if (dimension != null && !dimension.isBlank()) {
            dimension = normalizeDimension(dimension);
            int minX = jsonInt(obj, "min_x", Integer.MIN_VALUE);
            int minZ = jsonInt(obj, "min_z", Integer.MIN_VALUE);
            int maxX = jsonInt(obj, "max_x", Integer.MIN_VALUE);
            int maxZ = jsonInt(obj, "max_z", Integer.MIN_VALUE);
            if (minX != Integer.MIN_VALUE && minZ != Integer.MIN_VALUE && maxX != Integer.MIN_VALUE && maxZ != Integer.MIN_VALUE) {
               String ownerStr = JsonUtil.jsonString(obj, "owner", null);
               if (ownerStr == null) {
                  return null;
               }

               UUID owner = UUID.fromString(ownerStr);
               Claim c = new Claim();
               c.dimension = dimension;
               c.minX = minX;
               c.minZ = minZ;
               c.maxX = maxX;
               c.maxZ = maxZ;
               c.owner = owner;
               c.ownerName = JsonUtil.jsonString(obj, "owner_name", "");
               c.allowChests = jsonFlag(obj, "allow_chests", false);
               c.allowDrop = jsonFlag(obj, "allow_drop", false);
               c.allowBuild = jsonFlag(obj, "allow_build", false);
               c.allowPvp = jsonFlag(obj, "allow_pvp", true);
               c.allowExplosions = jsonFlag(obj, "allow_explosions", true);
               c.allowFireSpread = jsonFlag(obj, "allow_fire_spread", true);
               c.allowMobSpawns = jsonFlag(obj, "allow_mob_spawns", true);
               if (obj.has("admins") && obj.get("admins").isJsonArray()) {
                  for (JsonElement aEl : obj.getAsJsonArray("admins")) {
                     try {
                        if (aEl.isJsonPrimitive() && aEl.getAsJsonPrimitive().isString()) {
                           UUID id = UUID.fromString(aEl.getAsString());
                           c.admins.add(id);
                           c.adminNames.put(id, "?");
                        } else if (aEl.isJsonObject()) {
                           JsonObject ao = aEl.getAsJsonObject();
                           UUID id = UUID.fromString(JsonUtil.jsonString(ao, "uuid", ""));
                           c.admins.add(id);
                           c.adminNames.put(id, JsonUtil.jsonString(ao, "name", "?"));
                        }
                     } catch (Exception var14) {
                     }
                  }
               }

               return c;
            } else {
               return null;
            }
         } else {
            return null;
         }
      } catch (Exception e) {
         return null;
      }
   }

   private static int jsonInt(JsonObject obj, String key, int def) {
      if (!obj.has(key)) {
         return def;
      }

      JsonElement el = obj.get(key);

      try {
         if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isNumber()) {
            return el.getAsInt();
         }

         if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) {
            return Integer.parseInt(el.getAsString());
         }
      } catch (Exception var5) {
      }

      return def;
   }

   private static String normalizeDimension(String dim) {
      return dim.indexOf(58) < 0 ? "minecraft:" + dim : dim;
   }

   /** How many claims could be brought back right now from the save file and its
    *  backups, WITHOUT changing anything. Each claim is counted once even when it
    *  appears in several backup generations, and one that is already loaded is
    *  never counted. Backs the /ff restore claims preview, so an admin can see
    *  what recovery would do before it does it. */
   public static int countRecoverable() {
      return recoverableEntries().size();
   }

   /** Human-readable lines for up to {@code limit} recoverable claims, so the
    *  recovery GUI can show the actual land before merging any of it. */
   public static List<String> previewRecoverable(int limit) {
      List<String> out = new ArrayList<>();

      for (String line : recoverableEntries().values()) {
         if (out.size() >= limit) {
            break;
         }

         out.add(line);
      }

      return out;
   }

   /** Every claim that could be restored right now, keyed so one appearing in
    *  several backup generations is counted once and a loaded one never is. */
   private static java.util.Map<String, String> recoverableEntries() {
      java.util.Map<String, String> found = new java.util.LinkedHashMap<>();
      if (dataFile == null) {
         return found;
      }

      List<Path> sources = new ArrayList<>();
      sources.add(dataFile);
      sources.addAll(JsonUtil.backups(dataFile));

      for (Path p : sources) {
         if (p == null || !Files.exists(p)) {
            continue;
         }

         try {
            String content = Files.readString(p);
            if (content.isBlank()) {
               continue;
            }

            JsonObject root = (JsonObject)JsonUtil.gson().fromJson(content, JsonObject.class);
            if (root == null || !root.has("claims") || !root.get("claims").isJsonArray()) {
               continue;
            }

            for (JsonElement el : root.getAsJsonArray("claims")) {
               Claim c = parseClaim(el);
               if (c == null || alreadyHave(c)) {
                  continue;
               }

               String key = c.dimension + "|" + c.owner + "|" + c.minX + "|" + c.maxX + "|" + c.minZ + "|" + c.maxZ;
               if (found.containsKey(key)) {
                  continue;
               }

               String dim = c.dimension.startsWith("minecraft:") ? c.dimension.substring("minecraft:".length()) : c.dimension;
               String owner = c.ownerName == null || c.ownerName.isEmpty() ? "?" : c.ownerName;
               found.put(
                  key,
                  "&f"
                     + c.minX
                     + ","
                     + c.minZ
                     + " &7to &f"
                     + c.maxX
                     + ","
                     + c.maxZ
                     + " &8("
                     + dim
                     + ") &7- "
                     + owner
               );
            }
         } catch (Exception ignored) {
         }
      }

      return found;
   }

   public static int recoverFromBackups() {
      int merged = 0;
      List<Path> sources = new ArrayList<>();
      sources.add(dataFile);
      sources.addAll(JsonUtil.backups(dataFile));
      Iterator var2 = sources.iterator();

      while (true) {
         Path p;
         JsonObject root;
         while (true) {
            if (!var2.hasNext()) {
               if (merged > 0) {
                  loadHealthy = true;
               }

               return merged;
            }

            p = (Path)var2.next();
            if (p != null && Files.exists(p)) {
               try {
                  String content = Files.readString(p);
                  if (!content.isBlank()) {
                     root = (JsonObject)JsonUtil.gson().fromJson(content, JsonObject.class);
                     break;
                  }
               } catch (Exception e) {
               }
            }
         }

         if (root != null && root.has("claims") && root.get("claims").isJsonArray()) {
            int added = mergeClaims(root.getAsJsonArray("claims"));
            if (added > 0) {
               FortuneFavorsMod.LOGGER.warn("Recovered {} claim(s) from {}", added, p.getFileName());
               merged += added;
            }
         }
      }
   }

   private static int mergeClaims(JsonArray arr) {
      int added = 0;

      for (JsonElement el : arr) {
         Claim c = parseClaim(el);
         if (c != null && !alreadyHave(c)) {
            claimsByDim.computeIfAbsent(c.dimension, k -> new ArrayList<>()).add(c);
            added++;
         }
      }

      return added;
   }

   private static boolean alreadyHave(Claim c) {
      List<Claim> list = claimsByDim.get(c.dimension);
      if (list == null) {
         return false;
      }

      for (Claim o : list) {
         if (o.owner.equals(c.owner) && o.minX == c.minX && o.maxX == c.maxX && o.minZ == c.minZ && o.maxZ == c.maxZ) {
            return true;
         }
      }

      return false;
   }

   public static int claimCount() {
      return totalClaims();
   }

   /** Where claims.json lives (null before the first load). Used by the
    *  /claim status diagnostic so players and admins can see the real file. */
   public static Path dataFile() {
      return dataFile;
   }

   /** Pending "/claim abandon" confirmation for this player, if any. */
   public static boolean hasPendingAbandon(UUID uuid) {
      return pendingAbandon.containsKey(uuid);
   }

   /** Persists the claim table. Returns false when the write did not reach the
    *  disk (load failed earlier, full disk, read-only folder) - callers that
    *  charged money for the change must roll it back when this is false. */
   public static boolean save(MinecraftServer server) {
      if (!loadHealthy) {
         FortuneFavorsMod.LOGGER.warn("Not saving claims: the last load failed and nothing could be recovered. The on-disk claims.json was left untouched.");
         return false;
      } else {
         if (dataFile == null) {
            dataFile = EconomyManager.getDataDir(server).resolve("claims.json");
         }

         JsonObject root = new JsonObject();
         JsonArray arr = new JsonArray();

         for (List<Claim> list : claimsByDim.values()) {
            for (Claim c : list) {
               JsonObject obj = new JsonObject();
               obj.addProperty("dimension", c.dimension);
               obj.addProperty("min_x", c.minX);
               obj.addProperty("min_z", c.minZ);
               obj.addProperty("max_x", c.maxX);
               obj.addProperty("max_z", c.maxZ);
               obj.addProperty("owner", c.owner.toString());
               obj.addProperty("owner_name", c.ownerName);
               obj.addProperty("allow_chests", c.allowChests ? 1 : 0);
               obj.addProperty("allow_drop", c.allowDrop ? 1 : 0);
               obj.addProperty("allow_build", c.allowBuild ? 1 : 0);
               obj.addProperty("allow_pvp", c.allowPvp ? 1 : 0);
               obj.addProperty("allow_explosions", c.allowExplosions ? 1 : 0);
               obj.addProperty("allow_fire_spread", c.allowFireSpread ? 1 : 0);
               obj.addProperty("allow_mob_spawns", c.allowMobSpawns ? 1 : 0);
               JsonArray admins = new JsonArray();

               for (Entry<UUID, String> e : c.adminNames.entrySet()) {
                  JsonObject ao = new JsonObject();
                  ao.addProperty("uuid", e.getKey().toString());
                  ao.addProperty("name", e.getValue());
                  admins.add(ao);
               }

               obj.add("admins", admins);
               arr.add(obj);
            }
         }

         root.add("claims", arr);
         JsonObject vfx = new JsonObject();

         for (Entry<UUID, Integer> e : borderModes.entrySet()) {
            vfx.addProperty(e.getKey().toString(), e.getValue());
         }

         root.add("vfx", vfx);
         root.add("slots", writeUpgrades(extraSlots));
         boolean written = JsonUtil.write(dataFile, root);
         if (written) {
            FortuneFavorsMod.LOGGER.info("Saved {} claims to {}", totalClaims(), dataFile);
         } else {
            FortuneFavorsMod.LOGGER.error("Claims were NOT saved to {} - the on-disk file may be stale", dataFile);
         }

         return written;
      }
   }

   private static JsonObject writeUpgrades(Map<UUID, Integer> map) {
      JsonObject obj = new JsonObject();

      for (Entry<UUID, Integer> e : map.entrySet()) {
         obj.addProperty(e.getKey().toString(), e.getValue());
      }

      return obj;
   }

   public static Claim claimAt(Level level, BlockPos pos) {
      String dim = level.dimension().identifier().toString();
      List<Claim> list = claimsByDim.get(dim);
      if (list == null) {
         return null;
      }

      for (Claim c : list) {
         if (c.contains(pos.getX(), pos.getZ())) {
            return c;
         }
      }

      return null;
   }

   public static List<Claim> claimsOf(UUID owner) {
      List<Claim> out = new ArrayList<>();

      for (List<Claim> list : claimsByDim.values()) {
         for (Claim c : list) {
            if (c.owner.equals(owner)) {
               out.add(c);
            }
         }
      }

      return out;
   }

   public static boolean isOwnerOrAdmin(Claim c, UUID uuid) {
      return c.owner.equals(uuid) || c.admins.contains(uuid);
   }

   public static boolean guildShares(Claim c, UUID uuid) {
      if (uuid != null && !c.owner.equals(uuid)) {
         Guild g = GuildManager.getGuild(c.owner);
         return g != null && g.members.contains(uuid);
      } else {
         return false;
      }
   }

   public static void stripFromGuildClaims(Guild g, UUID leaving) {
      for (List<Claim> list : claimsByDim.values()) {
         for (Claim c : list) {
            if (g.members.contains(c.owner) && !c.owner.equals(leaving)) {
               c.admins.remove(leaving);
               c.adminNames.remove(leaving);
            }
         }
      }
   }

   public static boolean isAdminOp(ServerPlayer player) {
      return player.permissions() != null && player.permissions().hasPermission(Permissions.COMMANDS_ADMIN);
   }

   /** Sends a claim denial message at most once per 2 seconds per player, so the
    *  mixin and the event layer can both enforce the same rule without spamming. */
   public static void warnClaimed(ServerPlayer player, String msg) {
      long now = player.level().getGameTime();
      Long last = lastWarn.get(player.getUUID());
      if (last == null || now - last > 40L) {
         lastWarn.put(player.getUUID(), now);
         Chat.msg(player, msg);
      }
   }

   public static boolean canBuild(ServerPlayer player, BlockPos pos) {
      if (!ModConfig.is("claims")) {
         return true;
      }

      if (isAdminOp(player)) {
         return true;
      }

      Claim c = claimAt(player.level(), pos);
      return c == null || isOwnerOrAdmin(c, player.getUUID()) || guildShares(c, player.getUUID()) || c.allowBuild;
   }

   /**
    * Whether a non-player entity may destroy the block at this position.
    *
    * <p>The last way anything could still rearrange somebody's build without asking:
    * a mob. Endermen carry blocks away, ravagers flatten leaves and crops, silverfish
    * eat their own infested stone, and the wither carves through walls - none of which
    * is a player, so none of it passed a single claim check. A claim means the build
    * inside it stays as its owner left it, and that has to include what the mobs do.
    *
    * <p>Called from the one place all of it funnels through, {@code Level.destroyBlock},
    * where the causing entity is known. A player-caused destruction is left alone here:
    * players are already policed at the interaction layer, and refusing them twice would
    * mean two places to keep in step.
    */
   public static boolean mobMayDestroy(Level level, BlockPos pos) {
      // A missing level or position is not evidence of a claim. This is read from a mixin
      // on the block-destruction path, where throwing would turn one odd call into a
      // broken block break for the whole server.
      if (level == null || pos == null || !ModConfig.is("claims")) {
         return true;
      }

      return claimAt(level, pos) == null;
   }

   /**
    * Whether a dispenser may act on the position in front of it.
    *
    * <p>"Dispensers" is the last item on the list of ways into a claim, and it is the
    * awkward one: nothing about a dispenser is a player, so flint-and-steel, a water
    * bucket, bone meal, shears or an arrow fired from a neighbour's wall all arrived
    * inside a claim that nobody could have placed a torch in. TNT is already covered
    * by the explosion filter; this covers everything a dispenser does that is not an
    * explosion.
    *
    * <p>The rule is about the target, not the dispenser: a dispenser may act on the
    * block in front of it when that block is inside a claim the dispenser also stands
    * in (the owner's own farm), and not otherwise. The caller supplies the two claims
    * so the rule itself stays a pure function. See
    * {@link #dispenserMayAct(boolean, boolean, boolean)}.
    */
   public static boolean dispenserMayAct(Level level, BlockPos dispenser, BlockPos target) {
      if (level == null || dispenser == null || target == null) {
         return true;
      }

      Claim at = claimAt(level, target);
      if (at == null) {
         return true;
      }

      Claim here = claimAt(level, dispenser);
      return dispenserMayAct(ModConfig.is("claims"), true, here != null && at.owner.equals(here.owner));
   }

   /**
    * The rule behind {@link #dispenserMayAct(Level, BlockPos, BlockPos)}: a dispenser
    * may act into a claim only from that same claim.
    */
   public static boolean dispenserMayAct(boolean claimsEnabled, boolean targetClaimed, boolean sameOwner) {
      if (!claimsEnabled || !targetClaimed) {
         return true;
      }

      return sameOwner;
   }

   /**
    * Whether fluid may flow out of {@code from} and into {@code to}.
    *
    * <p>Every player path already asks {@link #canBuild}, but fluid is not a player: a
    * source placed on the border of a claim, or a bucket poured one block outside it,
    * used to run straight through and wash away the crops, torches and redstone inside
    * a claim that nobody was allowed to touch. That is the quietest way a claim can be
    * griefed, because it needs no permission at all - only a bucket and a wall.
    *
    * <p>The rule is deliberately this narrow. Fluid <i>inside</i> a claim still flows,
    * and fluid crossing between two positions the same owner claims is the owner's own
    * water moving - anything blunter would freeze every farm in the world. See
    * {@link #fluidMaySpreadInto(boolean, boolean, boolean)} for the rule itself.
    */
   public static boolean fluidMaySpreadInto(Level level, BlockPos from, BlockPos to) {
      if (level == null || from == null || to == null) {
         return true;
      }

      Claim target = claimAt(level, to);
      if (target == null) {
         return true;
      }

      Claim source = claimAt(level, from);
      return fluidMaySpreadInto(
         ModConfig.is("claims"), true, source != null && java.util.Objects.equals(source.owner, target.owner)
      );
   }

   /**
    * The rule behind {@link #fluidMaySpreadInto(Level, BlockPos, BlockPos)}, as a function
    * of the three things it actually depends on.
    *
    * <p>Written out rather than inlined so the self-test can pin every case without a
    * claim in the world to pour water at: fluid is refused only when it would cross into
    * somebody else's claim, and is untouched when claims are off, when the destination is
    * nobody's land, or when both ends are the same owner's. Pinned by
    * {@code claims.fluid-cannot-cross-a-border}.
    */
   public static boolean fluidMaySpreadInto(boolean claimsEnabled, boolean targetClaimed, boolean sameOwner) {
      if (!claimsEnabled || !targetClaimed) {
         return true;
      }

      return sameOwner;
   }

   /**
    * May fire light a <i>new</i> flame at this position?
    *
    * <p>The claim screen has offered "Fire spread" - allow fire to spread and ignite new
    * blocks in this claim - since claims existed, and the flag was only ever read at the
    * <b>source</b>: a flame sitting inside the claim was held still, while the flame the
    * neighbour's lava lit one block outside it was nobody's business. So the answer to off
    * was "my own fire is frozen and their fire walks in", which is the opposite of what the
    * switch says. This asks the question at the destination, exactly as the fluid rule does,
    * and {@code FireBlockMixin} asks it every time fire is about to appear somewhere.
    *
    * <p>{@code LevelReader} because vanilla builds a flame's state from a reader in some of
    * the paths that spread it; anything that is not a real level is left alone.
    */
   public static boolean fireMayIgniteAt(net.minecraft.world.level.LevelReader level, BlockPos pos) {
      return !(level instanceof Level real) || fireMayIgniteAt(real, pos);
   }

   /** The position form: the destination's own claim decides. */
   public static boolean fireMayIgniteAt(Level level, BlockPos pos) {
      if (level == null || pos == null) {
         return true;
      }

      Claim c = claimAt(level, pos);
      return fireMayIgniteAt(ModConfig.is("claims"), c != null, c != null && c.allowFireSpread);
   }

   /**
    * The rule behind {@link #fireMayIgniteAt(Level, BlockPos)}, as a function of the three
    * things it depends on - so every case can be pinned without a claim in the world to
    * light on fire. Pinned by {@code claims.fire-cannot-ignite-a-protected-claim}.
    */
   public static boolean fireMayIgniteAt(boolean claimsEnabled, boolean targetClaimed, boolean claimAllowsFire) {
      if (!claimsEnabled || !targetClaimed) {
         return true;
      }

      return claimAllowsFire;
   }

   public static boolean canOpenChest(ServerPlayer player, BlockPos pos) {
      if (!ModConfig.is("claims")) {
         return true;
      }

      if (isAdminOp(player)) {
         return true;
      }

      Claim c = claimAt(player.level(), pos);
      return c == null || isOwnerOrAdmin(c, player.getUUID()) || guildShares(c, player.getUUID()) || c.allowChests;
   }

   public static boolean canDrop(ServerPlayer player, BlockPos pos) {
      if (!ModConfig.is("claims")) {
         return true;
      }

      if (isAdminOp(player)) {
         return true;
      }

      Claim c = claimAt(player.level(), pos);
      return c == null || isOwnerOrAdmin(c, player.getUUID()) || guildShares(c, player.getUUID()) || c.allowDrop;
   }

   public static boolean pvpAllowed(ServerPlayer attacker, ServerPlayer victim) {
      if (!ModConfig.is("claims")) {
         return true;
      }

      if (isAdminOp(attacker)) {
         return true;
      }

      Claim c = claimAt(victim.level(), victim.blockPosition());
      return c != null && !c.allowPvp ? isOwnerOrAdmin(c, attacker.getUUID()) : true;
   }

   public static boolean canInteract(UUID uuid, Level level, BlockPos pos) {
      if (!ModConfig.is("claims")) {
         return true;
      } else {
         Claim c = claimAt(level, pos);
         if (c == null) {
            return true;
         } else {
            return isOwnerOrAdmin(c, uuid) || guildShares(c, uuid) || c.allowBuild || c.allowChests;
         }
      }
   }

         public static void handleChunkClaim(ServerPlayer player, BlockPos pos) {
      if (!ModConfig.is("claims")) {
         Chat.msg(player, "&cLand claims are disabled on this server.");
         return;
      }

      int cx = Math.floorDiv(pos.getX(), 16);
      int cz = Math.floorDiv(pos.getZ(), 16);
      int minX = cx * 16;
      int maxX = cx * 16 + 15;
      int minZ = cz * 16;
      int maxZ = cz * 16 + 15;
      switch (tryClaimChunk(player, minX, minZ, maxX, maxZ)) {
         case OK ->
            Chat.raw(player, "&aChunk claimed! (&f" + minX + ", " + minZ + "&a) for " + Chat.moneyStr(CHUNK_CLAIMER_PRICE) + "&7. Border shown with &fwhite sparkles&7.");
         case OVERLAP -> Chat.msg(player, "&cThat chunk is already claimed!");
         case LIMIT ->
            Chat.msg(
               player,
               "&cYou already have &f"
                  + maxClaims(player.getUUID())
                  + "&c claims. Abandon one (&f/claim abandon&c) or buy another slot with &f/claim slots buy&c."
            );
         case CANT_AFFORD -> Chat.msg(player, "&cYou can't afford that! A chunk costs " + Chat.moneyStr(CHUNK_CLAIMER_PRICE));
         case WRITE_DISABLED -> Chat.msg(
            player,
            "&cClaims can't be saved on this server right now, so nothing was charged. &7An admin should run &f/claim status&7 (or &f/claim recover&7)."
         );
         case INVALID -> Chat.msg(player, "&cCould not claim that chunk.");
      }
   }

   private static ClaimResult tryClaimChunk(ServerPlayer player, int minX, int minZ, int maxX, int maxZ) {
      Level level = player.level();
      if (!(level instanceof ServerLevel)) {
         return ClaimResult.INVALID;
      }

      String dim = level.dimension().identifier().toString();
      List<Claim> list = claimsByDim.get(dim);
      if (list != null) {
         for (Claim c : list) {
            if (rectOverlaps(c.minX, c.minZ, c.maxX, c.maxZ, minX, minZ, maxX, maxZ)) {
               return ClaimResult.OVERLAP;
            }
         }
      }

      int owned = 0;

      for (List<Claim> all : claimsByDim.values()) {
         for (Claim c : all) {
            if (c.owner.equals(player.getUUID())) {
               owned++;
            }
         }
      }

      if (owned >= maxClaims(player.getUUID())) {
         return ClaimResult.LIMIT;
      }

      // Never sell land the server cannot persist: an unsaved claim used to
      // take the player's money, "work" until the next restart and then quietly
      // disappear. Check affordability first, write the claim, and only charge
      // once it is on disk.
      if (!loadHealthy) {
         return ClaimResult.WRITE_DISABLED;
      }

      if (!EconomyManager.hasCash(player.getUUID(), CHUNK_CLAIMER_PRICE)) {
         return ClaimResult.CANT_AFFORD;
      }

      Claim claim = new Claim();
      claim.dimension = dim;
      claim.minX = minX;
      claim.maxX = maxX;
      claim.minZ = minZ;
      claim.maxZ = maxZ;
      claim.owner = player.getUUID();
      claim.ownerName = player.getName().getString();
      claimsByDim.computeIfAbsent(dim, k -> new ArrayList<>()).add(claim);
      if (!save(((ServerLevel)level).getServer())) {
         removeClaim(claim);
         return ClaimResult.WRITE_DISABLED;
      }

      if (!EconomyManager.takeCash(player.getUUID(), CHUNK_CLAIMER_PRICE)) {
         // Balance moved between the check and now (a shop purchase in another
         // menu). Undo the claim rather than handing out free land.
         removeClaim(claim);
         save(((ServerLevel)level).getServer());
         return ClaimResult.CANT_AFFORD;
      }

      SoundUtil.play(player, ModSounds.CLAIM);
      Chat.msg(player, "&7Sneak-right-click a chest in your claim with the claimer to open its permissions.");
      return ClaimResult.OK;
   }

   private static boolean rectOverlaps(int aMinX, int aMinZ, int aMaxX, int aMaxZ, int bMinX, int bMinZ, int bMaxX, int bMaxZ) {
      return aMinX <= bMaxX && bMinX <= aMaxX && aMinZ <= bMaxZ && bMinZ <= aMaxZ;
   }

   public static boolean abandon(ServerPlayer player) {
      Claim c = claimAt(player.level(), player.blockPosition());
      if (c == null) {
         Chat.msg(player, "&cYou're not standing in a claimed area.");
         return false;
      } else if (!c.owner.equals(player.getUUID())) {
         Chat.msg(player, "&cOnly the owner can abandon this claim.");
         return false;
      } else if (!loadHealthy) {
         Chat.msg(player, "&cClaims can't be saved on this server right now, so abandoning is disabled - nothing was changed.");
         return false;
      } else {
         pendingAbandon.put(player.getUUID(), c);
         long refund = ABANDON_REFUND;
         Chat.raw(player, "&eAbandon this claim? You'll get back &a" + Chat.moneyStr(refund) + "&e (50%). Type &f/claim abandon confirm&e to confirm.");
         return false;
      }
   }

   public static boolean abandonConfirm(ServerPlayer player) {
      // Prefer the claim the player explicitly asked to abandon, so this works
      // even if they walked out of it (or into a different one) before typing
      // confirm. Falling back to "where you're standing" keeps the old flow
      // working after a restart cleared the pending entry.
      Claim c = pendingAbandon.remove(player.getUUID());
      if (c == null || !claimsContain(c)) {
         c = claimAt(player.level(), player.blockPosition());
      }

      if (c == null) {
         Chat.msg(player, "&cThere's no claim to abandon - run /claim abandon while standing in one.");
         return false;
      }

      if (!c.owner.equals(player.getUUID())) {
         Chat.msg(player, "&cOnly the owner can abandon this claim.");
         return false;
      }

      removeClaim(c);
      if (!save(player.level().getServer())) {
         // Put it back rather than deleting land we couldn't write a refund for.
         claimsByDim.computeIfAbsent(c.dimension, k -> new ArrayList<>()).add(c);
         Chat.msg(player, "&cCouldn't save the claim change - the claim was left alone. Try again in a moment.");
         return false;
      }

      long refund = ABANDON_REFUND;
      EconomyManager.addCash(player.getUUID(), refund);
      SoundUtil.play(player, ModSounds.TRANSFER);
      Chat.raw(player, "&aClaim abandoned - " + Chat.moneyStr(refund) + "&a refunded (50%).");
      return true;
   }

   public static Claim claimAt(ServerPlayer player) {
      return claimAt(player.level(), player.blockPosition());
   }

   public static int maxClaims(UUID uuid) {
      return Math.min(6 + extraSlots.getOrDefault(uuid, 0), 24);
   }

   public static int usedClaims(UUID uuid) {
      int n = 0;

      for (List<Claim> all : claimsByDim.values()) {
         for (Claim c : all) {
            if (c.owner.equals(uuid)) {
               n++;
            }
         }
      }

      return n;
   }

   public static long slotUpgradeCost(UUID uuid) {
      return 25000L + 15000L * extraSlots.getOrDefault(uuid, 0).intValue();
   }

   public static boolean buySlot(ServerPlayer player) {
      UUID uuid = player.getUUID();
      if (maxClaims(uuid) >= MAX_CLAIMS_HARD_CAP) {
         Chat.msg(player, "&cYou already have the maximum " + MAX_CLAIMS_HARD_CAP + " claim slots.");
         return false;
      } else if (!loadHealthy) {
         Chat.msg(player, "&cClaims can't be saved on this server right now, so nothing was charged.");
         return false;
      } else {
         long cost = slotUpgradeCost(uuid);
         if (!EconomyManager.hasCash(uuid, cost)) {
            Chat.msg(player, "&cYou can't afford that! An extra claim slot costs " + Chat.moneyStr(cost) + ".");
            return false;
         }

         extraSlots.merge(uuid, 1, Integer::sum);
         if (!save(player.level().getServer())) {
            extraSlots.merge(uuid, -1, Integer::sum);
            Chat.msg(player, "&cCouldn't save the purchase - nothing was charged. Try again in a moment.");
            return false;
         }

         EconomyManager.takeCash(uuid, cost);
         Chat.raw(
            player,
            "&a&lClaim slot purchased!&r &7You can now hold up to &f"
               + maxClaims(uuid)
               + "&7 claims. Next slot costs "
               + Chat.moneyStr(slotUpgradeCost(uuid))
               + "."
         );
         return true;
      }
   }

   public static int borderMode(UUID uuid) {
      return borderModes.getOrDefault(uuid, 0);
   }

   public static boolean showsBorders(UUID uuid) {
      return borderMode(uuid) != 2;
   }

   public static int toggleBorders(ServerPlayer player) {
      int now = (borderMode(player.getUUID()) + 1) % 3;
      borderModes.put(player.getUUID(), now);
      save(player.level().getServer());
      return now;
   }

   public static void tickEnterExit(MinecraftServer server) {
      if (ModConfig.is("claims")) {
         for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ServerLevel level = player.level();
            String dim = level.dimension().identifier().toString();
            Claim claim = claimAt(level, player.blockPosition());
            String key = null;
            if (claim != null && !claim.owner.equals(player.getUUID())) {
               key = claim.owner + ":" + claim.ownerName + ":" + dim;
            }

            String prev = lastClaimSeen.get(player.getUUID());
            if (!Objects.equals(key, prev)) {
               lastClaimSeen.put(player.getUUID(), key);
               if (key == null || prev != null && sameOwner(key, prev)) {
                  if (key == null && prev != null) {
                     Chat.raw(player, "&7You left &f" + ownerNameOf(prev) + "&7's claim.");
                  }
               } else {
                  Chat.raw(player, "&7You are now in &f" + claim.ownerName + "&7's claim.");
               }
            }
         }
      }
   }

   public static void tickBorders(MinecraftServer server) {
      if (ModConfig.is("claims")) {
         for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ServerLevel level = player.level();
            String dim = level.dimension().identifier().toString();
            int mode = borderMode(player.getUUID());
            if (mode != 2) {
               List<Claim> list = claimsByDim.get(dim);
               if (list != null) {
                  List<Claim> inRange = new ArrayList<>();

                  for (Claim c : list) {
                     if (near(player, c)) {
                        inRange.add(c);
                     }
                  }

                  inRange.sort(Comparator.comparingLong(cx -> distSq(player, cx)));
                  int drawn = 0;

                  for (Claim c : inRange) {
                     if (drawn >= 16) {
                        break;
                     }

                     drawBorder(level, c, distSq(player, c), mode);
                     drawn++;
                  }
               }
            }
         }
      }
   }

   private static boolean sameOwner(String a, String b) {
      int ca = a.indexOf(58);
      int cb = b.indexOf(58);
      return ca > 0 && cb > 0 && a.substring(0, ca).equals(b.substring(0, cb));
   }

   private static String ownerNameOf(String key) {
      int ca = key.indexOf(58);
      if (ca < 0) {
         return "another player";
      }

      int cb = key.indexOf(58, ca + 1);
      return cb < 0 ? key.substring(ca + 1) : key.substring(ca + 1, cb);
   }

   private static boolean near(ServerPlayer player, Claim c) {
      int px = player.getBlockX();
      int pz = player.getBlockZ();
      return px >= c.minX - 128 && px <= c.maxX + 128 && pz >= c.minZ - 128 && pz <= c.maxZ + 128;
   }

   private static long distSq(ServerPlayer player, Claim c) {
      int px = player.getBlockX();
      int pz = player.getBlockZ();
      long dx = Math.max(0, Math.max(c.minX - px, px - c.maxX));
      long dz = Math.max(0, Math.max(c.minZ - pz, pz - c.maxZ));
      return dx * dx + dz * dz;
   }

   private static void drawBorder(ServerLevel level, Claim c, long d2, int mode) {
      if (mode == 1) {
         if ((level.getGameTime() / 40L & 1L) != 1L) {
            boolean north = !sameOwnerAt(level, c, c.minZ - 1);
            boolean south = !sameOwnerAt(level, c, c.maxZ + 1);
            boolean west = !sameOwnerAtX(level, c, c.minX - 1);
            boolean east = !sameOwnerAtX(level, c, c.maxX + 1);

            for (int x = c.minX; x <= c.maxX; x += 6) {
               if (north) {
                  spawnBorder(level, x, c.minZ, true, 2);
               }

               if (south) {
                  spawnBorder(level, x, c.maxZ, true, 2);
               }
            }

            for (int z = c.minZ; z <= c.maxZ; z += 6) {
               if (west) {
                  spawnBorder(level, c.minX, z, false, 2);
               }

               if (east) {
                  spawnBorder(level, c.maxX, z, false, 2);
               }
            }
         }
      } else {
         int detail = d2 < 1024L ? 0 : (d2 < 6400L ? 1 : 2);
         if (detail != 2 || (level.getGameTime() / 40L & 1L) != 1L) {
            int edgeLen = (c.maxX - c.minX + c.maxZ - c.minZ) * 2;
            int spacing = edgeLen > 400 ? 4 : (detail == 2 ? 4 : (detail == 1 ? 3 : 2));
            boolean north = !sameOwnerAt(level, c, c.minZ - 1);
            boolean south = !sameOwnerAt(level, c, c.maxZ + 1);
            boolean west = !sameOwnerAtX(level, c, c.minX - 1);
            boolean east = !sameOwnerAtX(level, c, c.maxX + 1);

            for (int x = c.minX; x <= c.maxX; x += spacing) {
               if (north) {
                  spawnBorder(level, x, c.minZ, true, detail);
               }

               if (south) {
                  spawnBorder(level, x, c.maxZ, true, detail);
               }
            }

            for (int z = c.minZ; z <= c.maxZ; z += spacing) {
               if (west) {
                  spawnBorder(level, c.minX, z, false, detail);
               }

               if (east) {
                  spawnBorder(level, c.maxX, z, false, detail);
               }
            }

            if (detail == 0) {
               if (north || west) {
                  corner(level, c.minX, c.minZ);
               }

               if (north || east) {
                  corner(level, c.maxX, c.minZ);
               }

               if (south || west) {
                  corner(level, c.minX, c.maxZ);
               }

               if (south || east) {
                  corner(level, c.maxX, c.maxZ);
               }
            }
         }
      }
   }

   private static boolean sameOwnerAt(ServerLevel level, Claim c, int z) {
      Claim other = claimAt(level, new BlockPos(c.minX, 0, z));
      return other != null && other.owner.equals(c.owner);
   }

   private static boolean sameOwnerAtX(ServerLevel level, Claim c, int x) {
      Claim other = claimAt(level, new BlockPos(x, 0, c.minZ));
      return other != null && other.owner.equals(c.owner);
   }

   private static void spawnBorder(ServerLevel level, int x, int z, boolean horizontal, int detail) {
      int y = level.getHeight(Types.MOTION_BLOCKING, x, z) + 1;
      int count = detail == 0 ? 1 + (int)(level.getGameTime() / 4L % 3L) : (detail == 1 ? 1 + (int)(level.getGameTime() / 8L % 2L) : 1);
      double along = horizontal ? 0.4 : 0.06;
      double across = horizontal ? 0.06 : 0.4;
      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.END_ROD, x + 0.5, y + 0.15, z + 0.5, count, along, 0.12, across, 0.01);
      if (detail == 0 && (x + z) % 6 == 0) {
         com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.PORTAL, x + 0.5, y + 0.3, z + 0.5, 1, along * 0.5, 0.18, across * 0.5, 0.005);
      }
   }

   private static void corner(ServerLevel level, int x, int z) {
      int y = level.getHeight(Types.MOTION_BLOCKING, x, z) + 1;
      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.END_ROD, x + 0.5, y, z + 0.5, 6, 0.12, 0.4, 0.12, 0.02);
      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.END_ROD, x + 0.5, y + 0.8, z + 0.5, 5, 0.1, 0.3, 0.1, 0.02);
      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.END_ROD, x + 0.5, y + 1.6, z + 0.5, 4, 0.08, 0.2, 0.08, 0.01);
      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.PORTAL, x + 0.5, y + 1.2, z + 0.5, 2, 0.1, 0.3, 0.1, 0.02);
   }


    static public class Claim {
       public String dimension;
       public int minX;
       public int minZ;
       public int maxX;
       public int maxZ;
       public UUID owner;
       public String ownerName = "";
       public final Set<UUID> admins = new HashSet<>();
       public final Map<UUID, String> adminNames = new LinkedHashMap<>();
       public boolean allowChests = false;
       public boolean allowDrop = false;
       public boolean allowBuild = false;
       public boolean allowPvp = true;
       public boolean allowExplosions = true;
       public boolean allowFireSpread = true;
       public boolean allowMobSpawns = true;
    
       public boolean contains(int x, int z) {
          return x >= this.minX && x <= this.maxX && z >= this.minZ && z <= this.maxZ;
       }
    
       public int blocks() {
          return (this.maxX - this.minX + 1) * (this.maxZ - this.minZ + 1);
       }
    }   public enum ClaimResult {
       OK,
       OVERLAP,
       LIMIT,
       CANT_AFFORD,
       INVALID,
       /** The claim could not be written to disk, so the player was not charged. */
       WRITE_DISABLED;
   }
}
