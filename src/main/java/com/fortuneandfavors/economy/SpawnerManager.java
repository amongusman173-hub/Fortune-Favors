package com.fortuneandfavors.economy;

import com.fortuneandfavors.FortuneFavorsMod;
import com.fortuneandfavors.ModItems;
import com.fortuneandfavors.economy.SpawnerManager.SpawnerRec;
import com.fortuneandfavors.mixin.BaseSpawnerMixin;
import com.fortuneandfavors.util.JsonUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Map.Entry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup.Provider;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;

public final class SpawnerManager {
   public static final int MODE_NORMAL = 0;
   public static final int MODE_ITEMS = 1;
   private static final long GAIN_INTERVAL = 200L;
   private static final int ITEM_CAP = 512;
   private static final int TOTAL_CAP = 4096;
   private static final Map<String, Item[]> DROP_TABLE = new HashMap<>();
   private static final Map<String, SpawnerRec> spawners = new HashMap<>();
   private static Path dataFile;
   private static long lastAccumulate = 0L;
   private static boolean dirty = false;
   private static boolean loadHealthy = true;
   private static int loadedCount = 0;

   private SpawnerManager() {
   }

   private static void buildDropTable() {
      Item[] rotten = new Item[]{Items.ROTTEN_FLESH};
      DROP_TABLE.put("minecraft:zombie", rotten);
      DROP_TABLE.put("minecraft:husk", rotten);
      DROP_TABLE.put("minecraft:drowned", rotten);
      DROP_TABLE.put("minecraft:skeleton", new Item[]{Items.BONE, Items.ARROW});
      DROP_TABLE.put("minecraft:stray", new Item[]{Items.BONE, Items.ARROW});
      DROP_TABLE.put("minecraft:bogged", new Item[]{Items.BONE, Items.ARROW});
      DROP_TABLE.put("minecraft:creeper", new Item[]{Items.GUNPOWDER});
      DROP_TABLE.put("minecraft:spider", new Item[]{Items.STRING, Items.SPIDER_EYE});
      DROP_TABLE.put("minecraft:cave_spider", new Item[]{Items.STRING, Items.SPIDER_EYE});
      DROP_TABLE.put("minecraft:blaze", new Item[]{Items.BLAZE_ROD});
      DROP_TABLE.put("minecraft:magma_cube", new Item[]{Items.MAGMA_CREAM});
      DROP_TABLE.put("minecraft:slime", new Item[]{Items.SLIME_BALL});
      DROP_TABLE.put("minecraft:witch", new Item[]{Items.GLOWSTONE_DUST, Items.REDSTONE, Items.SUGAR, Items.SPIDER_EYE});
      DROP_TABLE.put("minecraft:enderman", new Item[]{Items.ENDER_PEARL});
      DROP_TABLE.put("minecraft:ghast", new Item[]{Items.GHAST_TEAR, Items.GUNPOWDER});
      DROP_TABLE.put("minecraft:piglin", new Item[]{Items.GOLD_NUGGET});
      DROP_TABLE.put("minecraft:zombified_piglin", new Item[]{Items.GOLD_NUGGET, Items.ROTTEN_FLESH});
      DROP_TABLE.put("minecraft:hoglin", new Item[]{Items.PORKCHOP});
      DROP_TABLE.put("minecraft:zoglin", new Item[]{Items.ROTTEN_FLESH});
      DROP_TABLE.put("minecraft:shulker", new Item[]{Items.SHULKER_SHELL});
      DROP_TABLE.put("minecraft:chicken", new Item[]{Items.CHICKEN, Items.FEATHER});
      DROP_TABLE.put("minecraft:cow", new Item[]{Items.BEEF, Items.LEATHER});
      DROP_TABLE.put("minecraft:pig", new Item[]{Items.PORKCHOP});
      DROP_TABLE.put("minecraft:sheep", new Item[]{Items.MUTTON});
      DROP_TABLE.put("minecraft:rabbit", new Item[]{Items.RABBIT, Items.RABBIT_HIDE});
      DROP_TABLE.put("minecraft:guardian", new Item[]{Items.PRISMARINE_SHARD, Items.COD});
      DROP_TABLE.put("minecraft:elder_guardian", new Item[]{Items.PRISMARINE_SHARD, Items.COD});
      DROP_TABLE.put("minecraft:skeleton_horse", new Item[]{Items.BONE});
      DROP_TABLE.put("minecraft:iron_golem", new Item[]{Items.IRON_INGOT});
      DROP_TABLE.put("minecraft:silverfish", new Item[0]);
   }

   public static void load(MinecraftServer server) {
      spawners.clear();
      buildDropTable();
      dataFile = EconomyManager.getDataDir(server).resolve("spawners.json");

      try {
         boolean fileExisted = Files.exists(dataFile);
         loadHealthy = true;
         loadedCount = 0;
         healedNames = 0;
         Provider access = server.registryAccess();
         JsonObject root = JsonUtil.readOrCreate(dataFile, new JsonObject());
         int rawCount = 0;
         if (root.has("spawners") && root.get("spawners").isJsonObject()) {
            JsonObject spawnersObj = root.getAsJsonObject("spawners");
            rawCount = spawnersObj.size();

            for (Entry<String, JsonElement> e : spawnersObj.entrySet()) {
               SpawnerRec rec = parseRec(e.getKey(), e.getValue(), access);
               if (rec != null) {
                  // Self-heal records that have an owner but no name, so a spawner
                  // is never displayed (or re-saved) as owned by "unbound".
                  if (rec.owner() != null && (rec.ownerName() == null || rec.ownerName().isBlank() || "unbound".equals(rec.ownerName()))) {
                     String resolved = resolveOwnerName(server, rec.owner());
                     if (!resolved.isBlank()) {
                        rec = new SpawnerRec(rec.owner(), resolved, rec.type(), rec.mode(), rec.level(), rec.buffer());
                        dirty = true;
                        healedNames++;
                     }
                  }
                  spawners.put(e.getKey(), rec);
                  loadedCount++;
               } else {
                  FortuneFavorsMod.LOGGER.warn("Skipped an unreadable spawner record {} in spawners.json", e.getKey());
               }
            }
         }

         if (root.has("names") && root.get("names").isJsonObject()) {
            for (Entry<String, JsonElement> nameEntry : root.getAsJsonObject("names").entrySet()) {
               try {
                  knownNames.put(UUID.fromString(nameEntry.getKey()), nameEntry.getValue().getAsString());
               } catch (Exception ignored) {
               }
            }
         }

         if (fileExisted && !root.has("spawners")) {
            FortuneFavorsMod.LOGGER.warn("{} exists but could not be read - recovering spawners from backups", dataFile);
            int recovered = recoverFromBackups(server);
            if (recovered > 0) {
               save(server);
            } else {
               loadHealthy = false;
               FortuneFavorsMod.LOGGER
                  .error(
                     "NO spawners could be loaded from {} or any backup! Writes are DISABLED until the data is fixed - run /spawners recover or restore a backup manually.",
                     dataFile
                  );
            }
         } else if (rawCount > 0 && loadedCount == 0) {
            FortuneFavorsMod.LOGGER.warn("spawners.json held {} record(s) but none could be read - recovering from backups", rawCount);
            int recovered = recoverFromBackups(server);
            if (recovered > 0) {
               loadHealthy = true;
               save(server);
            } else {
               loadHealthy = false;
               FortuneFavorsMod.LOGGER
                  .error(
                     "NO spawners could be read from {} or any backup! Writes are DISABLED until the data is fixed - run /spawners recover or restore a backup manually.",
                     dataFile
                  );
            }
         } else if (loadedCount > 0 && loadedCount < rawCount) {
            FortuneFavorsMod.LOGGER
               .warn("Loaded {}/{} spawner(s) from {} - merging missing entries from backups", new Object[]{loadedCount, rawCount, dataFile});
            int recovered = recoverFromBackups(server);
            if (recovered > 0) {
               loadHealthy = true;
               save(server);
            }
         } else if (fileExisted && rawCount == 0 && loadedCount == 0 && root.has("spawners")) {
            int recovered = recoverFromBackups(server);
            if (recovered > 0) {
               loadHealthy = true;
               save(server);
               FortuneFavorsMod.LOGGER.warn("spawners.json was empty - {} spawner(s) were restored from backups", recovered);
            }
         }

         if (healedNames > 0) {
            FortuneFavorsMod.LOGGER.info("Repaired the owner name on {} spawner(s) that had an owner but no name (they read as 'unbound')", healedNames);
            saveIfDirty(server);
         }

         FortuneFavorsMod.LOGGER.info("Loaded {} spawner(s) from {}", spawners.size(), dataFile);
      } catch (Exception e) {
         loadHealthy = false;
         FortuneFavorsMod.LOGGER.error("Spawners load failed - writes are disabled to protect the on-disk spawners.json", e);
      }
   }

   private static SpawnerRec parseRec(String key, JsonElement el, Provider access) {
      try {
         if (el != null && el.isJsonObject()) {
            JsonObject obj = el.getAsJsonObject();
            String ownerStr = JsonUtil.jsonString(obj, "owner", "");
            if (ownerStr.isEmpty()) {
               return null;
            }

            UUID owner = UUID.fromString(ownerStr);
            List<ItemStack> buffer = new ArrayList<>();
            if (obj.has("buffer") && obj.get("buffer").isJsonArray()) {
               for (JsonElement iEl : obj.getAsJsonArray("buffer")) {
                  ItemStack s = JsonUtil.jsonToItem(iEl, access);
                  if (!s.isEmpty()) {
                     buffer.add(s);
                  }
               }
            }

            return new SpawnerRec(
               owner,
               JsonUtil.jsonString(obj, "owner_name", ""),
               JsonUtil.jsonString(obj, "type", ""),
               (int)JsonUtil.jsonLong(obj, "mode", 0L),
               (int)JsonUtil.jsonLong(obj, "level", 1L),
               buffer
            );
         } else {
            return null;
         }
      } catch (Exception e) {
         return null;
      }
   }

   /** Records fixed up on load because they had an owner but no usable name. */
   private static int healedNames;
   /** Last tick the owner-name cache was refreshed from the online players. */
   private static long lastNameSnapshot;

   /**
    * UUID to name, remembered from players we have actually seen and saved beside
    * the spawners.
    *
    * <p>There is no reliable offline name lookup on this platform, so instead of
    * guessing we keep our own: every tick pass snapshots the online players, and
    * any owner we do not have a name for is filled in the moment they log on. That
    * is what makes the "unbound" repair permanent rather than a one-time patch.
    */
   private static final Map<UUID, String> knownNames = new HashMap<>();

   /** Remember a player's name for future owner-name repairs. */
   public static void rememberName(UUID id, String name) {
      if (id != null && name != null && !name.isBlank()) {
         knownNames.put(id, name);
      }
   }

   /**
    * Best-effort player name for an owner UUID: the online player first (and the
    * name is remembered), then our own cache. Returns {@code ""} when the name
    * genuinely cannot be resolved, which callers must treat as "unknown" - never
    * as a name to store.
    */
   public static String resolveOwnerName(MinecraftServer server, UUID owner) {
      if (owner == null) {
         return "";
      }

      if (server != null) {
         try {
            ServerPlayer online = server.getPlayerList().getPlayer(owner);
            if (online != null) {
               String name = online.getName().getString();
               rememberName(owner, name);
               return name;
            }
         } catch (Throwable ignored) {
         }
      }

      return knownNames.getOrDefault(owner, "");
   }

   public static int recoverFromBackups(MinecraftServer server) {
      int merged = 0;
      Provider access = server.registryAccess();
      List<Path> sources = new ArrayList<>();
      sources.add(dataFile);
      sources.addAll(JsonUtil.backups(dataFile));
      Iterator var4 = sources.iterator();

      while (true) {
         Path p;
         JsonObject root;
         while (true) {
            if (!var4.hasNext()) {
               if (merged > 0) {
                  loadHealthy = true;
               }

               return merged;
            }

            p = (Path)var4.next();
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

         if (root != null && root.has("spawners") && root.get("spawners").isJsonObject()) {
            int added = mergeSpawners(root.getAsJsonObject("spawners"), access);
            if (added > 0) {
               FortuneFavorsMod.LOGGER.warn("Recovered {} spawner(s) from {}", added, p.getFileName());
               merged += added;
            }
         }
      }
   }

   private static int mergeSpawners(JsonObject spawnersObj, Provider access) {
      int added = 0;

      for (Entry<String, JsonElement> e : spawnersObj.entrySet()) {
         if (!spawners.containsKey(e.getKey())) {
            SpawnerRec rec = parseRec(e.getKey(), e.getValue(), access);
            if (rec != null) {
               spawners.put(e.getKey(), rec);
               added++;
            }
         }
      }

      return added;
   }

   public static int count() {
      return spawners.size();
   }

   /** False once a load has failed, in which case save() refuses to touch disk. */
   public static boolean writable() {
      return loadHealthy;
   }

   /** The file spawners are saved to, or null before the world's data dir is known. */
   public static Path dataFile() {
      return dataFile;
   }

   /** How many spawner records could be brought back right now, from the save
    *  file and every backup generation, without changing anything. A record that
    *  appears in several generations is counted once, and one already loaded is
    *  never counted - so the number the recovery GUI shows is honest. */
   public static int countRecoverable() {
      return recoverableEntries().size();
   }

   /** Human-readable lines for up to {@code limit} recoverable records, so the
    *  recovery GUI can show the actual spawners before merging any of them. */
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

   private static Map<String, String> recoverableEntries() {
      Map<String, String> found = new LinkedHashMap<>();
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
            if (root == null || !root.has("spawners") || !root.get("spawners").isJsonObject()) {
               continue;
            }

            for (Entry<String, JsonElement> e : root.getAsJsonObject("spawners").entrySet()) {
               if (spawners.containsKey(e.getKey()) || found.containsKey(e.getKey()) || e.getValue() == null || !e.getValue().isJsonObject()) {
                  continue;
               }

               JsonObject obj = e.getValue().getAsJsonObject();
               found.put(e.getKey(), describeKey(e.getKey(), JsonUtil.jsonString(obj, "type", "?"), JsonUtil.jsonString(obj, "owner_name", "")));
            }
         } catch (Exception ignored) {
         }
      }

      return found;
   }

   /** Human line for one record: what it is, where it is, whose it is. */
   private static String describeKey(String key, String type, String owner) {
      BlockPos pos = parseKey(key);
      String at = pos == null ? key : pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
      String dim = dimensionOf(key);
      String shortDim = dim.startsWith("minecraft:") ? dim.substring("minecraft:".length()) : dim;
      String mob = type.isEmpty() ? "?" : type;
      return "&f" + mob + " &7at &f" + at + " &8(" + shortDim + ")" + (owner.isEmpty() ? "" : " &7- " + owner);
   }

   public static boolean save(MinecraftServer server) {
      if (!loadHealthy) {
         FortuneFavorsMod.LOGGER
            .warn("Not saving spawners: the last load failed and nothing could be recovered. The on-disk spawners.json was left untouched.");
         return false;
      } else {
         if (dataFile == null) {
            dataFile = EconomyManager.getDataDir(server).resolve("spawners.json");
         }

         Provider access = server.registryAccess();
         JsonObject root = new JsonObject();
         JsonObject spawnersObj = new JsonObject();

         for (Entry<String, SpawnerRec> e : spawners.entrySet()) {
            JsonObject obj = new JsonObject();
            obj.addProperty("owner", e.getValue().owner().toString());
            obj.addProperty("owner_name", e.getValue().ownerName());
            obj.addProperty("type", e.getValue().type());
            obj.addProperty("mode", e.getValue().mode());
            obj.addProperty("level", e.getValue().level());
            JsonArray buffer = new JsonArray();

            for (ItemStack s : e.getValue().buffer()) {
               buffer.add(JsonUtil.itemToJson(s, access));
            }

            obj.add("buffer", buffer);
            spawnersObj.add(e.getKey(), obj);
         }

         root.add("spawners", spawnersObj);
         // Owner names travel with the spawners, so a repair survives a restart
         // even for owners who are offline.
         JsonObject namesObj = new JsonObject();
         for (Entry<UUID, String> nameEntry : knownNames.entrySet()) {
            namesObj.addProperty(nameEntry.getKey().toString(), nameEntry.getValue());
         }
         root.add("names", namesObj);
         dirty = false;
         return JsonUtil.write(dataFile, root);
      }
   }

   public static String keyFor(Level level, BlockPos pos) {
      return level.dimension().identifier().toString() + ":" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
   }

   public static SpawnerRec get(Level level, BlockPos pos) {
      return spawners.get(keyFor(level, pos));
   }

   public static boolean isRegistered(Level level, BlockPos pos) {
      return get(level, pos) != null;
   }

   public static Map<String, SpawnerRec> all() {
      return spawners;
   }

   public static String detectType(Level level, BlockPos pos) {
      return detectType(level, pos, level.getBlockEntity(pos));
   }

   private static String detectType(Level level, BlockPos pos, BlockEntity be) {
      if (!level.isClientSide() && be instanceof SpawnerBlockEntity sb) {
         try {
            Entity display = sb.getSpawner().getOrCreateDisplayEntity(level, pos);
            if (display != null) {
               Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(display.getType());
               return id == null ? null : id.toString();
            }
         } catch (Exception var6) {
         }

         return null;
      } else {
         return null;
      }
   }

   public static void onMined(Level level, BlockPos pos, ServerPlayer player, BlockEntity be) {
      if (!level.isClientSide()) {
         SpawnerRec rec = get(level, pos);
         ItemStack item;
         if (rec != null) {
            spawners.remove(keyFor(level, pos));
            item = ModItems.spawnerItem(rec.type(), rec.owner());
            // spawnerItem only writes the owner UUID; without this the picked-up
            // spawner loses the owner's name and shows "unbound". Resolve a real
            // name when the record's own name is missing - never write the
            // display placeholder back into the item.
            if (rec.owner() != null) {
               String pickedName = rec.ownerName();
               if (pickedName == null || pickedName.isBlank() || "unbound".equals(pickedName)) {
                  pickedName = resolveOwnerName(level.getServer(), rec.owner());
               }
               ModItems.bindSpawner(item, rec.owner(), pickedName == null ? "" : pickedName);
            }
            ModItems.setSpawnerMode(item, rec.mode());
            ModItems.setSpawnerLevel(item, rec.level());

            for (ItemStack s : rec.buffer()) {
               dropStack(level, pos, s);
            }

            dirty = true;
         } else {
            String type = detectType(level, pos, be);
            item = ModItems.spawnerItem(type, null);
         }

         dropStack(level, pos, item);
         saveIfDirty(level.getServer());
      }
   }

   public static void onPlaced(Level level, BlockPos pos, LivingEntity placer, ItemStack stack) {
      if (!level.isClientSide() && stack != null && !stack.isEmpty() && stack.is(Items.SPAWNER)) {
         boolean modItem = ModItems.isSpawnerItem(stack);
         UUID owner = ModItems.spawnerOwner(stack);
         String ownerName = ModItems.spawnerOwnerNameRaw(stack);
         int fuseLevel = 1;
         int mode = 0;
         String type;
         if (modItem) {
            type = ModItems.spawnerTypeId(stack);
            if (type == null || type.isEmpty()) {
               type = "minecraft:zombie";
            }

            EntityType<?> entityType = null;

            try {
               entityType = (EntityType<?>)BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse(type));
            } catch (Exception var15) {
            }

            if (entityType == null) {
               type = "minecraft:zombie";
               entityType = (EntityType<?>)BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse(type));
            }

            if (entityType != null && level.getBlockEntity(pos) instanceof SpawnerBlockEntity sb) {
               try {
                  sb.getSpawner().setEntityId(entityType, level, level.getRandom(), pos);
               } catch (Exception var14) {
               }
            }

            fuseLevel = ModItems.spawnerLevel(stack);
            mode = ModItems.spawnerMode(stack);
         } else if (stack.get(DataComponents.BLOCK_ENTITY_DATA) == null) {
            type = "minecraft:zombie";

            try {
               EntityType<?> zombie = (EntityType<?>)BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse(type));
               if (zombie != null && level.getBlockEntity(pos) instanceof SpawnerBlockEntity sb) {
                  sb.getSpawner().setEntityId(zombie, level, level.getRandom(), pos);
               }
            } catch (Exception var13) {
            }
         } else {
            type = detectType(level, pos, level.getBlockEntity(pos));
            if (type == null || type.isEmpty()) {
               type = "minecraft:zombie";
            }
         }

         if (owner == null && placer instanceof ServerPlayer sp) {
            owner = sp.getUUID();
            ownerName = sp.getName().getString();
         }

         // A spawner can carry an owner UUID without a name (an older file, a
         // recovered record, an item that predates names). Resolve it now rather
         // than storing a blank - or worse, the literal "unbound".
         if ((ownerName == null || ownerName.isBlank()) && owner != null) {
            ownerName = resolveOwnerName(level.getServer(), owner);
         }

         spawners.put(keyFor(level, pos), new SpawnerRec(owner, ownerName, type, mode, fuseLevel, new ArrayList<>()));
         dirty = true;
         saveIfDirty(level.getServer());
         if (fuseLevel > 1 && level.getBlockEntity(pos) instanceof SpawnerBlockEntity) {
            bakeLevel(level, pos, fuseLevel);
         }
      }
   }

   private static void dropStack(Level level, BlockPos pos, ItemStack stack) {
      if (level != null && !level.isClientSide() && !stack.isEmpty() && pos != null) {
         ItemEntity item = new ItemEntity(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, stack);
         item.setDeltaMovement(0.0, 0.1, 0.0);
         level.addFreshEntity(item);
      }
   }

   public static boolean canBreak(Level level, BlockPos pos, ServerPlayer player) {
      SpawnerRec rec = get(level, pos);
      return rec != null && rec.owner() != null ? rec.owner().equals(player.getUUID()) || ClaimManager.isAdminOp(player) : true;
   }

   public static void setMode(Level level, BlockPos pos, int mode) {
      SpawnerRec rec = get(level, pos);
      if (rec != null) {
         spawners.put(keyFor(level, pos), new SpawnerRec(rec.owner(), rec.ownerName(), rec.type(), mode == 1 ? 1 : 0, rec.level(), rec.buffer()));
         dirty = true;
         saveIfDirty(level.getServer());
      }
   }

   public static boolean isItemGain(Level level, BlockPos pos) {
      SpawnerRec rec = get(level, pos);
      return rec != null && rec.mode() == 1;
   }

   public static void tick(MinecraftServer server) {
      long now = server.getTickCount();

      // Keep the owner-name cache fresh: this is what lets a spawner whose name
      // was lost be repaired the next time its owner logs on.
      if (now - lastNameSnapshot >= 200L) {
         lastNameSnapshot = now;
         try {
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
               rememberName(p.getUUID(), p.getName().getString());
            }
         } catch (Throwable ignored) {
         }
      }

      if (now - lastAccumulate >= 200L && !spawners.isEmpty()) {
         lastAccumulate = now;

         for (Entry<String, SpawnerRec> e : new ArrayList<>(spawners.entrySet())) {
            SpawnerRec rec = e.getValue();
            if (rec.mode() == 1 && !rec.type().isEmpty()) {
               Item[] pool = DROP_TABLE.get(rec.type());
               if (pool != null && pool.length != 0) {
                  BlockPos pos = parseKey(e.getKey());
                  if (pos != null) {
                     ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(dimensionOf(e.getKey()))));
                     if (level != null && level.isLoaded(pos) && isItemGain(level, pos)) {
                        int total = 0;

                        for (ItemStack s : rec.buffer()) {
                           total += s.getCount();
                        }

                        if (total < 4096) {
                           Item item = pool[level.getRandom().nextInt(pool.length)];
                           int lvl = Math.max(1, rec.level());
                           int count = lvl + level.getRandom().nextInt(2);
                           addToBuffer(level, pos, new ItemStack(item, count));
                        }
                     }
                  }
               }
            }
         }

         if (dirty && now % 600L == 0L) {
            save(server);
         }
      }
   }

   private static void addToBuffer(Level level, BlockPos pos, ItemStack stack) {
      SpawnerRec rec = get(level, pos);
      if (rec != null && !stack.isEmpty()) {
         ModItems.setSpawnerLoot(stack, rec.level());
         List<ItemStack> buffer = new ArrayList<>(rec.buffer());

         for (ItemStack s : buffer) {
            if (ItemStack.isSameItemSameComponents(s, stack) && s.getCount() < 512) {
               int move = Math.min(512 - s.getCount(), stack.getCount());
               s.grow(move);
               stack.shrink(move);
               if (stack.isEmpty()) {
                  break;
               }
            }
         }

         if (!stack.isEmpty() && buffer.size() < 27) {
            buffer.add(stack.copy());
         }

         spawners.put(keyFor(level, pos), new SpawnerRec(rec.owner(), rec.ownerName(), rec.type(), rec.mode(), rec.level(), buffer));
         dirty = true;
      }
   }

   /**
    * Sells every buffered item in all ITEMS-mode spawners owned by the given player.
    * Returns { totalValue, itemCount, spawnerCount }. Pure in-memory work over the
    * spawner registry - no chunk loads, no world access - so it stays instant even
    * with hundreds of spawners. The value already includes the per-item level
    * sell bonus (see BlockValues.specialLootValue).
    */
   public static long[] sellAllOwned(ServerPlayer player) {
      long total = 0L;
      long itemCount = 0L;
      long spawnerCount = 0L;
      boolean changed = false;

      for (Entry<String, SpawnerRec> e : new ArrayList<>(spawners.entrySet())) {
         SpawnerRec rec = e.getValue();
         if (rec.mode() != MODE_ITEMS || rec.buffer().isEmpty()) {
            continue;
         }
         if (rec.owner() == null || !rec.owner().equals(player.getUUID())) {
            continue;
         }
         for (ItemStack s : rec.buffer()) {
            total += BlockValues.valueOf(s);
            itemCount += s.getCount();
         }
         spawners.put(e.getKey(), new SpawnerRec(rec.owner(), rec.ownerName(), rec.type(), rec.mode(), rec.level(), new ArrayList<>()));
         spawnerCount++;
         changed = true;
      }

      if (changed) {
         dirty = true;
         saveIfDirty(player.level().getServer());
      }
      return new long[]{total, itemCount, spawnerCount};
   }

   public static List<ItemStack> drainBuffer(Level level, BlockPos pos) {
      SpawnerRec rec = get(level, pos);
      if (rec != null && !rec.buffer().isEmpty()) {
         List<ItemStack> items = new ArrayList<>(rec.buffer());
         spawners.put(keyFor(level, pos), new SpawnerRec(rec.owner(), rec.ownerName(), rec.type(), rec.mode(), rec.level(), new ArrayList<>()));
         dirty = true;
         saveIfDirty(level.getServer());
         return items;
      } else {
         return List.of();
      }
   }

   public static ItemStack takeDrop(Level level, BlockPos pos, int slotIndex) {
      SpawnerRec rec = get(level, pos);
      if (rec != null && slotIndex >= 0 && slotIndex < rec.buffer().size()) {
         ItemStack taken = (ItemStack)rec.buffer().get(slotIndex);
         List<ItemStack> buffer = new ArrayList<>(rec.buffer());
         buffer.remove(slotIndex);
         spawners.put(keyFor(level, pos), new SpawnerRec(rec.owner(), rec.ownerName(), rec.type(), rec.mode(), rec.level(), buffer));
         dirty = true;
         saveIfDirty(level.getServer());
         return taken;
      } else {
         return ItemStack.EMPTY;
      }
   }

   public static List<ItemStack> bufferOf(Level level, BlockPos pos) {
      SpawnerRec rec = get(level, pos);
      return rec == null ? List.of() : rec.buffer();
   }

   public static String mobName(String type) {
      if (type != null && !type.isEmpty()) {
         EntityType<?> entityType = (EntityType<?>)BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse(type));
         return entityType == null ? type : Component.translatable(entityType.getDescriptionId()).getString();
      } else {
         return "Unknown";
      }
   }

   public static void bakeLevel(Level level, BlockPos pos, int fuseLevel) {
      if (!level.isClientSide() && fuseLevel > 1 && level.getBlockEntity(pos) instanceof SpawnerBlockEntity sb) {
         try {
            ((BaseSpawnerMixin)(Object)sb.getSpawner()).fortuneandfavors$boost(fuseLevel);
            sb.setChanged();
         } catch (Exception var5) {
         }
      }
   }

   private static void saveIfDirty(MinecraftServer server) {
      if (dirty && server != null) {
         save(server);
      }
   }

   private static String dimensionOf(String key) {
      int lastColon = key.lastIndexOf(58);
      return lastColon < 0 ? "" : key.substring(0, lastColon);
   }

   private static BlockPos parseKey(String key) {
      try {
         int lastColon = key.lastIndexOf(58);
         if (lastColon < 0) {
            return null;
         }

         String[] parts = key.substring(lastColon + 1).split(",");
         return parts.length != 3 ? null : new BlockPos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
      } catch (Exception e) {
         return null;
      }
   }

   public static void cleanupStale(MinecraftServer server) {
      if (!spawners.isEmpty()) {
         List<String> stale = new ArrayList<>();

         for (Entry<String, SpawnerRec> e : spawners.entrySet()) {
            BlockPos pos = parseKey(e.getKey());
            if (pos != null) {
               ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(dimensionOf(e.getKey()))));
               if (level != null && level.isLoaded(pos) && !level.getBlockState(pos).is(Blocks.SPAWNER)) {
                  stale.add(e.getKey());
               }
            }
         }

         if (!stale.isEmpty()) {
            for (String key : stale) {
               SpawnerRec rec = spawners.remove(key);
               if (rec != null) {
                  ServerLevel lvl = server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(dimensionOf(key))));
                  BlockPos p = parseKey(key);

                  for (ItemStack s : rec.buffer()) {
                     dropStack(lvl, p, s);
                  }

                  ItemStack item = ModItems.spawnerItem(rec.type(), rec.owner());
                  if (rec.owner() != null) {
                     ModItems.bindSpawner(item, rec.owner(), rec.ownerName());
                  }
                  ModItems.setSpawnerMode(item, rec.mode());
                  ModItems.setSpawnerLevel(item, rec.level());
                  dropStack(lvl, p, item);
               }
            }

            save(server);
         }
      }
   }


    public record SpawnerRec(UUID owner, String ownerName, String type, int mode, int level, List<ItemStack> buffer) {
    }
}
