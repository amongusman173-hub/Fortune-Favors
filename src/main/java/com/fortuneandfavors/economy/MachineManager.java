package com.fortuneandfavors.economy;

import com.fortuneandfavors.FortuneFavorsMod;
import com.fortuneandfavors.ModItems;
import com.fortuneandfavors.ModSounds;
import com.fortuneandfavors.economy.MachineManager.Machine;
import com.fortuneandfavors.util.Chat;
import com.fortuneandfavors.util.InventoryHelper;
import com.fortuneandfavors.util.JsonUtil;
import com.fortuneandfavors.util.SoundUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Map.Entry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.phys.AABB;

public final class MachineManager {
   public static final String TYPE_AUTO_SELL = "auto_sell_hopper";
   public static final String TYPE_UPWARDS = "upwards_hopper";
   public static final String TYPE_ELEVATOR = "elevator";
   public static final String TYPE_REDEEMER = "token_redeemer";
   public static final String TYPE_INFUSER = "spawner_infuser";
   public static final String TYPE_ITEM_FORGE = "item_forge";
   public static final String TYPE_CHAIR = "chair";
   /** The eight late-game machines: a kept-chunk anchor, a paid repair bench, a filter hopper, two
    *  kitchens, and the three blocks that farm for you. */
   public static final String TYPE_CHUNK_ANCHOR = "chunk_anchor";
   public static final String TYPE_REPAIR_STATION = "repair_station";
   public static final String TYPE_ITEM_SORTER = "item_sorter";
   public static final String TYPE_PORTABLE_FURNACE = "portable_furnace";
   public static final String TYPE_PORTABLE_CAMPFIRE = "portable_campfire";
   public static final String TYPE_AUTO_PLANTER = "auto_planter";
   public static final String TYPE_AUTO_HARVESTER = "auto_harvester";
   public static final String TYPE_IRRIGATION_SPRINKLER = "irrigation_sprinkler";
   // The heavy hoppers and the fast furnace, all sold on the Exclusive shelf. Three of them move
   // items the way the sorter moves them, so they share its rules and its persistence; only what
   // they *choose* to move (everything / only junk / half each way) differs.
   public static final String TYPE_SUPER_HOPPER = "super_hopper";
   public static final String TYPE_TRANSFER_HOPPER = "transfer_hopper";
   /**
    * The Transfer Hopper's second half: it fills its ordinary route first, and only what will not
    * fit there goes to its tagged output.
    *
    * <p>It is a separate type rather than a switch on the Transfer Hopper because the two hoppers
    * disagree about what the output tag <i>means</i> - one always sends there, the other only sends
    * there when everything else is full - and a single machine with two meanings for one setting is
    * a machine a player has to read the tooltip of every time. Same engine, same window, same
    * chaining; the tag is simply the overflow rather than the destination. See
    * {@code ItemSorter.tickOverflow}.
    */
   public static final String TYPE_OVERFLOW_HOPPER = "overflow_hopper";
   public static final String TYPE_CHECKER_HOPPER = "checker_hopper";
   public static final String TYPE_TWO_WAY_SPLITTER = "two_way_splitter";
   public static final String TYPE_SUPER_SMELTER = "super_smelter";
   public static final int REDEEMER_VERSIONS = 4;
   private static final long[] DEFAULT_PAYOUTS = new long[]{1000L, 5000L, 10000L, 25000L};
   private static final Map<String, Machine> machines = new HashMap<>();
   private static Path dataFile;
   private static final Map<String, Long> lastSellSound = new HashMap<>();
   private static boolean loadHealthy = true;

   private MachineManager() {
   }

   public static void load(MinecraftServer server) {
      machines.clear();
      loadHealthy = true;
      dataFile = EconomyManager.getDataDir(server).resolve("machines.json");
      boolean fileExisted = Files.exists(dataFile);
      JsonObject root = new JsonObject();

      try {
         if (fileExisted) {
            String content = Files.readString(dataFile);
            if (!content.isBlank()) {
               root = (JsonObject)JsonUtil.gson().fromJson(content, JsonObject.class);
               if (root == null) {
                  throw new IllegalStateException("machines.json did not parse to a JSON object");
               }
            }
         } else {
            JsonUtil.write(dataFile, new JsonObject());
         }
      } catch (Exception e) {
         loadHealthy = false;
         FortuneFavorsMod.LOGGER.error("Machines load failed - writes are disabled to protect the on-disk machines.json", e);
         return;
      }

      if (root.has("machines") && root.get("machines").isJsonObject()) {
         for (Entry<String, JsonElement> e : root.getAsJsonObject("machines").entrySet()) {
            Machine m = parseMachine(e.getValue());
            if (m != null) {
               machines.put(e.getKey(), m);
            }
         }
      }

      // An empty table in a world that has already been played is the signature of
      // a wipe (a crash mid-save, or a sync client evicting the file). Merge every
      // backup generation back in before anything can overwrite what is left.
      if (machines.isEmpty() && fileExisted) {
         int recovered = recoverFromBackups(server);
         if (recovered > 0) {
            save(server);
            FortuneFavorsMod.LOGGER.warn("machines.json was empty - {} machine(s) were restored from backups", recovered);
         }
      }

      FortuneFavorsMod.LOGGER.info("Loaded {} machine(s) from {}", machines.size(), dataFile);
   }

   private static Machine parseMachine(JsonElement el) {
      try {
         if (el == null || !el.isJsonObject()) {
            return null;
         }

         JsonObject obj = el.getAsJsonObject();
         String type = JsonUtil.jsonString(obj, "type", "");
         String ownerStr = JsonUtil.jsonString(obj, "owner", "");
         if (type.isEmpty() || ownerStr.isEmpty()) {
            return null;
         }

         long[] payouts = new long[4];
         JsonArray arr = obj.has("payouts") && obj.get("payouts").isJsonArray() ? obj.getAsJsonArray("payouts") : new JsonArray();

         for (int i = 0; i < 4; i++) {
            payouts[i] = i < arr.size() ? arr.get(i).getAsLong() : DEFAULT_PAYOUTS[i];
         }

         return new Machine(
            type,
            UUID.fromString(ownerStr),
            JsonUtil.jsonString(obj, "owner_name", ""),
            JsonUtil.jsonLong(obj, "cash", 0L),
            payouts,
            JsonUtil.jsonBool(obj, "effects", true)
         );
      } catch (Exception e) {
         return null;
      }
   }

   public static boolean save(MinecraftServer server) {
      if (!loadHealthy) {
         FortuneFavorsMod.LOGGER
            .warn("Not saving machines: the last load failed and nothing could be recovered. The on-disk machines.json was left untouched.");
         return false;
      }

      if (dataFile == null) {
         dataFile = EconomyManager.getDataDir(server).resolve("machines.json");
      }

      JsonObject root = new JsonObject();
      JsonObject machinesObj = new JsonObject();

      for (Entry<String, Machine> e : machines.entrySet()) {
         JsonObject obj = new JsonObject();
         obj.addProperty("type", e.getValue().type());
         obj.addProperty("owner", e.getValue().owner().toString());
         obj.addProperty("owner_name", e.getValue().ownerName());
         obj.addProperty("cash", e.getValue().cash());
         obj.addProperty("effects", e.getValue().effects());
         JsonArray payouts = new JsonArray();

         for (long p : e.getValue().payouts()) {
            payouts.add(p);
         }

         obj.add("payouts", payouts);
         machinesObj.add(e.getKey(), obj);
      }

      root.add("machines", machinesObj);
      return JsonUtil.write(dataFile, root);
   }

   /** False once a load has failed, in which case save() refuses to touch disk. */
   public static boolean writable() {
      return loadHealthy;
   }

   public static int count() {
      return machines.size();
   }

   /** The file machines are saved to, or null before the world's data dir is known. */
   public static Path dataFile() {
      return dataFile;
   }

   /** How many machine records could be brought back right now, from the save file
    *  and every backup generation, without changing anything. A record appearing
    *  in several generations is counted once, and one already loaded never is. */
   public static int countRecoverable() {
      return recoverableEntries().size();
   }

   /** Human-readable lines for up to {@code limit} recoverable records, so the
    *  recovery GUI can show the actual machines before merging any of them. */
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
            if (root == null || !root.has("machines") || !root.get("machines").isJsonObject()) {
               continue;
            }

            for (Entry<String, JsonElement> e : root.getAsJsonObject("machines").entrySet()) {
               if (machines.containsKey(e.getKey()) || found.containsKey(e.getKey())) {
                  continue;
               }

               Machine m = parseMachine(e.getValue());
               if (m != null) {
                  found.put(e.getKey(), describeKey(e.getKey(), m));
               }
            }
         } catch (Exception ignored) {
         }
      }

      return found;
   }

   /** Merge every machine recorded in the save file and its backups that is not
    *  already loaded. Returns how many were added; never removes a live record. */
   public static int recoverFromBackups(MinecraftServer server) {
      int merged = 0;
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
            if (root == null || !root.has("machines") || !root.get("machines").isJsonObject()) {
               continue;
            }

            for (Entry<String, JsonElement> e : root.getAsJsonObject("machines").entrySet()) {
               if (machines.containsKey(e.getKey())) {
                  continue;
               }

               Machine m = parseMachine(e.getValue());
               if (m != null) {
                  machines.put(e.getKey(), m);
                  merged++;
               }
            }
         } catch (Exception ignored) {
         }
      }

      if (merged > 0) {
         loadHealthy = true;
         FortuneFavorsMod.LOGGER.warn("Recovered {} machine(s) from machines.json and its backups", merged);
      }

      return merged;
   }

   /** Human line for one record: what it is, where it is, whose it is. */
   private static String describeKey(String key, Machine m) {
      int lastColon = key.lastIndexOf(58);
      String dim = lastColon < 0 ? "" : key.substring(0, lastColon);
      String shortDim = dim.startsWith("minecraft:") ? dim.substring("minecraft:".length()) : dim;
      return "&f" + m.type() + " &7at &f" + (lastColon < 0 ? key : key.substring(lastColon + 1)) + " &8(" + shortDim + ")"
         + (m.ownerName().isEmpty() ? "" : " &7- " + m.ownerName());
   }

   public static String keyFor(Level level, BlockPos pos) {
      return level.dimension().identifier().toString() + ":" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
   }

   public static Machine get(Level level, BlockPos pos) {
      return machines.get(keyFor(level, pos));
   }

   public static boolean isElevator(Level level, BlockPos pos) {
      Machine m = get(level, pos);
      return m != null && "elevator".equals(m.type()) && level.getBlockState(pos).is(Blocks.IRON_BLOCK);
   }

   /** True when this exact position is a tracked mod chair (any vanilla stairs). */
   public static boolean isChair(Level level, BlockPos pos) {
      Machine m = get(level, pos);
      return m != null && TYPE_CHAIR.equals(m.type()) && level.getBlockState(pos).getBlock() instanceof net.minecraft.world.level.block.StairBlock;
   }

   /** Removes the chair record without giving anything back (break/pickup paths own the refund). */
   public static void removeChair(Level level, BlockPos pos) {
      if (machines.remove(keyFor(level, pos)) != null) {
         save(level.getServer());
      }
   }

   public static boolean isAnyMachine(Level level, BlockPos pos) {
      Machine m = get(level, pos);
      return m != null && standsIn(level, pos, m.type());
   }

   /**
    * The vanilla block each machine type stands in, in one place.
    *
    * <p>A machine is a record plus an ordinary block, and the record is only good while the block is
    * still there - a player who breaks the hopper out from under the record must not leave a ghost
    * that answers clicks forever. Every "is this still that" question in this class asks this
    * method, so a new machine has exactly one place to say what it is made of.
    */
   public static boolean standsIn(Level level, BlockPos pos, String type) {
      if (level == null || pos == null || type == null) {
         return false;
      }
      net.minecraft.world.level.block.state.BlockState state = level.getBlockState(pos);
      return switch (type) {
         case "auto_sell_hopper", "upwards_hopper", "item_sorter",
              "super_hopper", "transfer_hopper", "overflow_hopper", "checker_hopper",
              "two_way_splitter" -> state.is(Blocks.HOPPER);
         case "super_smelter" -> state.is(Blocks.FURNACE);
         case "elevator" -> state.is(Blocks.IRON_BLOCK);
         case "token_redeemer" -> state.is(Blocks.GOLD_BLOCK);
         case "spawner_infuser" -> state.is(Blocks.CRAFTING_TABLE);
         case "item_forge" -> state.is(Blocks.SMITHING_TABLE);
         case "chair" -> state.getBlock() instanceof net.minecraft.world.level.block.StairBlock;
         case "chunk_anchor" -> state.is(Blocks.LODESTONE);
         case "repair_station" -> state.is(Blocks.GRINDSTONE);
         case "portable_furnace" -> state.is(Blocks.BLAST_FURNACE);
         case "portable_campfire" -> state.is(Blocks.SMOKER);
         case "auto_planter" -> state.is(Blocks.COMPOSTER);
         case "auto_harvester" -> state.is(Blocks.OBSERVER);
         // A sprinkler is an empty cauldron, and the world can fill it: rain and flowing water make
         // it a water cauldron (the same block, so it survives), but lava poured in or snow landing
         // on it makes it a lava or powder-snow cauldron, which are different blocks. Those are
         // still the machine standing there, so they still count - the sprinkler empties itself back
         // out on its next tick. See FarmMachines.tickSprinkler.
         case "irrigation_sprinkler" -> state.is(Blocks.CAULDRON)
            || state.is(Blocks.WATER_CAULDRON)
            || state.is(Blocks.LAVA_CAULDRON)
            || state.is(Blocks.POWDER_SNOW_CAULDRON);
         default -> false;
      };
   }

   public static boolean isChunkAnchor(Level level, BlockPos pos) {
      Machine m = get(level, pos);
      return m != null && TYPE_CHUNK_ANCHOR.equals(m.type()) && standsIn(level, pos, TYPE_CHUNK_ANCHOR);
   }

   public static boolean isRepairStation(Level level, BlockPos pos) {
      Machine m = get(level, pos);
      return m != null && TYPE_REPAIR_STATION.equals(m.type()) && standsIn(level, pos, TYPE_REPAIR_STATION);
   }

   public static boolean isItemSorter(Level level, BlockPos pos) {
      Machine m = get(level, pos);
      return m != null && TYPE_ITEM_SORTER.equals(m.type()) && standsIn(level, pos, TYPE_ITEM_SORTER);
   }

   public static boolean isPortableFurnace(Level level, BlockPos pos) {
      Machine m = get(level, pos);
      return m != null && TYPE_PORTABLE_FURNACE.equals(m.type()) && standsIn(level, pos, TYPE_PORTABLE_FURNACE);
   }

   public static boolean isPortableCampfire(Level level, BlockPos pos) {
      Machine m = get(level, pos);
      return m != null && TYPE_PORTABLE_CAMPFIRE.equals(m.type()) && standsIn(level, pos, TYPE_PORTABLE_CAMPFIRE);
   }

   public static boolean isAutoPlanter(Level level, BlockPos pos) {
      Machine m = get(level, pos);
      return m != null && TYPE_AUTO_PLANTER.equals(m.type()) && standsIn(level, pos, TYPE_AUTO_PLANTER);
   }

   public static boolean isAutoHarvester(Level level, BlockPos pos) {
      Machine m = get(level, pos);
      return m != null && TYPE_AUTO_HARVESTER.equals(m.type()) && standsIn(level, pos, TYPE_AUTO_HARVESTER);
   }

   public static boolean isIrrigationSprinkler(Level level, BlockPos pos) {
      Machine m = get(level, pos);
      return m != null && TYPE_IRRIGATION_SPRINKLER.equals(m.type()) && standsIn(level, pos, TYPE_IRRIGATION_SPRINKLER);
   }

   public static boolean isSuperHopper(Level level, BlockPos pos) {
      Machine m = get(level, pos);
      return m != null && TYPE_SUPER_HOPPER.equals(m.type()) && standsIn(level, pos, TYPE_SUPER_HOPPER);
   }

   public static boolean isTransferHopper(Level level, BlockPos pos) {
      Machine m = get(level, pos);
      return m != null && TYPE_TRANSFER_HOPPER.equals(m.type()) && standsIn(level, pos, TYPE_TRANSFER_HOPPER);
   }

   public static boolean isOverflowHopper(Level level, BlockPos pos) {
      Machine m = get(level, pos);
      return m != null && TYPE_OVERFLOW_HOPPER.equals(m.type()) && standsIn(level, pos, TYPE_OVERFLOW_HOPPER);
   }

   /**
    * True for both halves of the transfer family: the Transfer Hopper and the Overflow Hopper.
    *
    * <p>Everything that used to ask {@link #isTransferHopper} to mean "that is one of the machines
    * that moves items it is still deciding about" asks this instead - they are one engine with two
    * rules, and a machine that treats only one of them as a peer would happily drain the other's
    * buffer. What stays on {@code isTransferHopper} is the question about that one block: which
    * window it opens, and which machine a picked-up item turns back into.
    */
   public static boolean isTransferFamily(Level level, BlockPos pos) {
      return isTransferHopper(level, pos) || isOverflowHopper(level, pos);
   }

   public static boolean isCheckerHopper(Level level, BlockPos pos) {
      Machine m = get(level, pos);
      return m != null && TYPE_CHECKER_HOPPER.equals(m.type()) && standsIn(level, pos, TYPE_CHECKER_HOPPER);
   }

   public static boolean isTwoWaySplitter(Level level, BlockPos pos) {
      Machine m = get(level, pos);
      return m != null && TYPE_TWO_WAY_SPLITTER.equals(m.type()) && standsIn(level, pos, TYPE_TWO_WAY_SPLITTER);
   }

   public static boolean isSuperSmelter(Level level, BlockPos pos) {
      Machine m = get(level, pos);
      return m != null && TYPE_SUPER_SMELTER.equals(m.type()) && standsIn(level, pos, TYPE_SUPER_SMELTER);
   }

   /** The owner of the machine at this spot, or null when there is none. */
   public static UUID ownerAt(Level level, BlockPos pos) {
      Machine m = get(level, pos);
      return m == null ? null : m.owner();
   }

   /** The level a saved machine key lives in - the ledger is keyed by dimension and position. */
   public static ServerLevel levelOf(MinecraftServer server, String key) {
      if (server == null || key == null) {
         return null;
      }
      int lastColon = key.lastIndexOf(58);
      if (lastColon < 0) {
         return null;
      }
      try {
         return server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(key.substring(0, lastColon))));
      } catch (Exception e) {
         return null;
      }
   }

   /** The position a saved machine key stands at, or null when it does not parse. */
   public static BlockPos posOf(String key) {
      if (key == null) {
         return null;
      }
      int lastColon = key.lastIndexOf(58);
      if (lastColon < 0) {
         return null;
      }
      String[] parts = key.substring(lastColon + 1).split(",");
      if (parts.length != 3) {
         return null;
      }
      try {
         return new BlockPos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
      } catch (Exception e) {
         return null;
      }
   }

   public static boolean isItemForge(Level level, BlockPos pos) {
      Machine m = get(level, pos);
      return m != null && "item_forge".equals(m.type()) && level.getBlockState(pos).is(Blocks.SMITHING_TABLE);
   }

   public static boolean isInfuser(Level level, BlockPos pos) {
      Machine m = get(level, pos);
      return m != null && "spawner_infuser".equals(m.type()) && level.getBlockState(pos).is(Blocks.CRAFTING_TABLE);
   }

   public static boolean isAutoSellHopper(Level level, BlockPos pos) {
      Machine m = get(level, pos);
      return m != null && "auto_sell_hopper".equals(m.type()) && level.getBlockState(pos).is(Blocks.HOPPER);
   }

   public static boolean isUpwardsHopper(Level level, BlockPos pos) {
      Machine m = get(level, pos);
      return m != null && "upwards_hopper".equals(m.type()) && level.getBlockState(pos).is(Blocks.HOPPER);
   }

   public static boolean isRedeemer(Level level, BlockPos pos) {
      Machine m = get(level, pos);
      return m != null && "token_redeemer".equals(m.type()) && level.getBlockState(pos).is(Blocks.GOLD_BLOCK);
   }

   public static Map<String, Machine> all() {
      return machines;
   }

   public static void cleanupStale(MinecraftServer server) {
      if (!machines.isEmpty()) {
         List<String> stale = new ArrayList<>();

         for (Entry<String, Machine> e : machines.entrySet()) {
            String key = e.getKey();

            try {
               int lastColon = key.lastIndexOf(58);
               if (lastColon >= 0) {
                  String dim = key.substring(0, lastColon);
                  String[] parts = key.substring(lastColon + 1).split(",");
                  if (parts.length == 3) {
                     BlockPos pos = new BlockPos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
                     ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(dim)));
                     if (level != null && level.isLoaded(pos)) {
                        boolean stillThere = standsIn(level, pos, e.getValue().type());
                        if (!stillThere) {
                           if ("token_redeemer".equals(e.getValue().type()) && e.getValue().cash() > 0L && e.getValue().owner() != null) {
                              EconomyManager.addCash(e.getValue().owner(), e.getValue().cash());
                           }

                           stale.add(key);
                        }
                     }
                  }
               }
            } catch (Exception var13) {
            }
         }

         if (!stale.isEmpty()) {
            for (String key : stale) {
               machines.remove(key);
            }

            save(server);
         }
      }
   }   public static void onBlockPlaced(Level level, BlockPos pos, LivingEntity placer, ItemStack stack) {
      if (!level.isClientSide() && placer instanceof Player player) {
         String type = ModItems.typeOf(stack);
         if (type != null) {
            if (TYPE_CHAIR.equals(type)) {
               machines.put(keyFor(level, pos), new Machine(TYPE_CHAIR, player.getUUID(), player.getName().getString(), 0L, DEFAULT_PAYOUTS, true));
               save(level.getServer());
               return;
            }

            if (("auto_sell_hopper".equals(type) && ModConfig.is("autosell"))
               || ("upwards_hopper".equals(type) && ModConfig.is("exclusive"))
               || ("elevator".equals(type) && ModConfig.is("elevator"))
               || ("token_redeemer".equals(type) && ModConfig.is("token"))
               || ("spawner_infuser".equals(type) && ModConfig.is("exclusive"))
               || ("item_forge".equals(type) && ModConfig.is("boss"))
               || ("chunk_anchor".equals(type) && ModConfig.is("exclusive"))
               || ("repair_station".equals(type) && ModConfig.is("exclusive"))
               || ("item_sorter".equals(type) && ModConfig.is("exclusive"))
               || ("portable_furnace".equals(type) && ModConfig.is("exclusive"))
               || ("portable_campfire".equals(type) && ModConfig.is("exclusive"))
               || ("auto_planter".equals(type) && ModConfig.is("autosell"))
               || ("auto_harvester".equals(type) && ModConfig.is("autosell"))
               || ("irrigation_sprinkler".equals(type) && ModConfig.is("autosell"))
               || ("super_hopper".equals(type) && ModConfig.is("exclusive"))
               || ("transfer_hopper".equals(type) && ModConfig.is("exclusive"))
               || ("overflow_hopper".equals(type) && ModConfig.is("exclusive"))
               || ("checker_hopper".equals(type) && ModConfig.is("exclusive"))
               || ("two_way_splitter".equals(type) && ModConfig.is("exclusive"))
               || ("super_smelter".equals(type) && ModConfig.is("exclusive"))) {
               UUID owner = ModItems.machineOwner(stack);
               String ownerName = ModItems.machineOwnerName(stack);
               if (owner == null) {
                  owner = player.getUUID();
                  ownerName = player.getName().getString();
               }

               long[] payouts = ModItems.machinePayouts(stack);
               if (payouts == null || payouts.length != 4) {
                  payouts = new long[4];
                  System.arraycopy(DEFAULT_PAYOUTS, 0, payouts, 0, 4);
               }

               machines.put(keyFor(level, pos), new Machine(type, owner, ownerName, ModItems.machineCash(stack), payouts, true));
               save(level.getServer());
               // A Chunk Anchor starts holding its chunks the moment it is placed, and a sorter gets
               // its (empty) filter entry so that its first click is a configuration and not a
               // special case.
               if (TYPE_CHUNK_ANCHOR.equals(type)) {
                  ChunkAnchor.hold(level, pos);
               } else if (TYPE_ITEM_SORTER.equals(type)) {
                  ItemSorter.entry(level, pos);
               } else if (TYPE_TRANSFER_HOPPER.equals(type) || TYPE_OVERFLOW_HOPPER.equals(type)) {
                  ItemSorter.transferEntry(level, pos);
               } else if (TYPE_SUPER_HOPPER.equals(type) || TYPE_SUPER_SMELTER.equals(type)) {
                  // A tier is carried on the item, so a machine put down again comes back at the
                  // tier it was bought to rather than quietly resetting to one.
                  MachineTuning.setTier(keyFor(level, pos), ModItems.machineTier(stack));
               } else if (TYPE_CHECKER_HOPPER.equals(type)) {
                  MachineTuning.setVoiding(keyFor(level, pos), ModItems.machineVoiding(stack));
               }
            }
         }
      }
   }

   public static boolean pickUp(ServerPlayer player, Level level, BlockPos pos) {
      Machine m = get(level, pos);
      if (m == null) {
         return false;
      }

      // Chairs route through ChairBlock.pickUp so any seated player is ejected first.
      if (TYPE_CHAIR.equals(m.type())) {
         return com.fortuneandfavors.block.ChairBlock.pickUp(player, level, pos);
      }

      if (m.owner() != null && m.owner().equals(player.getUUID())) {
         ItemStack item = switch (m.type()) {
            case "auto_sell_hopper" -> ModItems.autoSellHopper();
            case "upwards_hopper" -> ModItems.upwardsHopper();
            case "elevator" -> ModItems.elevator();
            case "token_redeemer" -> ModItems.tokenRedeemer();
            case "spawner_infuser" -> ModItems.spawnerInfuser();
            case "item_forge" -> ModItems.itemForge();
            case "chunk_anchor" -> ModItems.chunkAnchor();
            case "repair_station" -> ModItems.repairStation();
            case "item_sorter" -> ModItems.itemSorter();
            case "super_hopper" -> ModItems.superHopper();
            case "transfer_hopper" -> ModItems.transferHopper();
            case "overflow_hopper" -> ModItems.overflowHopper();
            case "checker_hopper" -> ModItems.checkerHopper();
            case "two_way_splitter" -> ModItems.twoWaySplitter();
            case "super_smelter" -> ModItems.superSmelter();
            case "portable_furnace" -> ModItems.portableFurnace();
            case "portable_campfire" -> ModItems.portableCampfire();
            case "auto_planter" -> ModItems.autoPlanter();
            case "auto_harvester" -> ModItems.autoHarvester();
            case "irrigation_sprinkler" -> ModItems.irrigationSprinkler();
            default -> null;
         };
         if (item == null) {
            return false;
         }

         // A machine with a container in it gives its contents back before the block goes: a
         // portable furnace picked up with a stack of ore in it must not eat the ore, and neither
         // must a sorter picked up mid-flow.
         spillContents(level, pos, player);
         // ...and an anchor gives its chunks back, or the world would keep them loaded forever with
         // nothing in it to explain why.
         if (TYPE_CHUNK_ANCHOR.equals(m.type())) {
            ChunkAnchor.release(level, pos);
         }

         ModItems.setMachineData(item, m.owner(), m.ownerName(), m.cash(), m.payouts());
         // The dials travel with the machine: a tier the player paid for is not something a pickaxe
         // should be able to lose. See MachineTuning.
         if (TYPE_SUPER_HOPPER.equals(m.type()) || TYPE_SUPER_SMELTER.equals(m.type())) {
            ModItems.setMachineTier(item, MachineTuning.tier(keyFor(level, pos)));
         } else if (TYPE_CHECKER_HOPPER.equals(m.type())) {
            ModItems.setMachineVoiding(item, MachineTuning.voiding(keyFor(level, pos)));
         }
         ModItems.appendMachineDataLore(item);
         MachineTuning.forget(keyFor(level, pos));
         MachineVfx.forget(keyFor(level, pos));
         machines.remove(keyFor(level, pos));
         save(level.getServer());
         if (level instanceof ServerLevel serverLevel) {
            com.fortuneandfavors.net.FfVfx.particles(serverLevel, ParticleTypes.END_ROD, pos.getX() + 0.5, pos.getY() + 0.6, pos.getZ() + 0.5, 18, 0.4, 0.4, 0.4, 0.05);
         }

         level.playSound(null, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, SoundEvents.ITEM_PICKUP, SoundSource.BLOCKS, 1.0F, 1.4F);
         level.destroyBlock(pos, false);
         InventoryHelper.giveOrDrop(player, item);
         Chat.raw(player, "&aPicked up your " + label(m) + " - place it again to put it back down.");
         return true;
      } else {
         Chat.msg(
            player, "&cThis " + label(m) + " belongs to " + (m.ownerName().isEmpty() ? "another player" : m.ownerName()) + "! Only the owner can pick it up."
         );
         return false;
      }
   }

   public static String label(Machine m) {
      return switch (m.type()) {
         case "auto_sell_hopper" -> "auto-sell hopper";
         case "upwards_hopper" -> "Upwards Hopper";
         case "elevator" -> "elevator";
         case "token_redeemer" -> "Token Redeemer";
         case "spawner_infuser" -> "Spawner Infuser";
         case "item_forge" -> "Item Forge";
         case "chair" -> "Chair";
         case "chunk_anchor" -> "Chunk Anchor";
         case "repair_station" -> "Repair Station";
         case "item_sorter" -> "Item Sorter";
         case "super_hopper" -> "Super Hopper";
         case "transfer_hopper" -> "Transfer Hopper";
         case "overflow_hopper" -> "Overflow Hopper";
         case "checker_hopper" -> "Item Checker Hopper";
         case "two_way_splitter" -> "2-Way Splitter";
         case "super_smelter" -> "Super Smelter";
         case "portable_furnace" -> "Portable Furnace";
         case "portable_campfire" -> "Portable Campfire";
         case "auto_planter" -> "Auto Planter";
         case "auto_harvester" -> "Auto Harvester";
         case "irrigation_sprinkler" -> "Irrigation Sprinkler";
         default -> "machine";
      };
   }

   /**
    * Hands whatever was stored inside a machine back to the player taking it up.
    *
    * <p>The two kitchens are ordinary furnaces and smokers under the record, so they have real
    * inventories - and a picked-up furnace that silently swallowed a stack of raw ore would be the
    * one machine in the mod that loses items. The contents go to the owner; anything that will not
    * fit goes to the ground, which is what breaking any other chest does.
    */
   private static void spillContents(Level level, BlockPos pos, ServerPlayer player) {
      if (!(level.getBlockEntity(pos) instanceof Container c)) {
         return;
      }
      for (int i = 0; i < c.getContainerSize(); i++) {
         ItemStack stack = c.getItem(i);
         if (!stack.isEmpty()) {
            c.setItem(i, ItemStack.EMPTY);
            InventoryHelper.giveOrDrop(player, stack);
         }
      }
      c.setChanged();
   }

   /**
    * One second of every machine that runs on its own clock.
    *
    * <p>Called once a second from the server tick. Only the machines that actually do something
    * between clicks are here - the ones that answer a right-click are driven by the click, and the
    * two hoppers are driven by the hopper tick. Everything is chunk-gated, so a machine in a part
    * of the world nobody is standing in costs one map lookup and nothing else.
    */
   public static void tickAll(MinecraftServer server) {
      if (server == null || machines.isEmpty()) {
         return;
      }
      long tick = server.getTickCount();
      // The anchors are the expensive ones, and they are the ones whose tickets must survive being
      // dropped by anything else: re-asserted every twenty seconds. A minute was the old cadence and
      // it is the wrong one for a promise that reads "this ground is always loaded" - a minute is
      // long enough for a farm to stall and for a player to walk over and find the chunks gone. The
      // loop below now also heals a dark anchor on sight, so this is the backstop rather than the
      // whole mechanism.
      if (tick % 400L == 0L) {
         ChunkAnchor.reaffirm(server);
      }
      for (Entry<String, Machine> e : new ArrayList<>(machines.entrySet())) {
         String type = e.getValue().type();
         boolean farms = "auto_planter".equals(type) || "auto_harvester".equals(type) || "irrigation_sprinkler".equals(type);
         // The anchor is the other machine with a clock of its own, and the only one whose whole
         // job is invisible: it holds ground loaded, so the only thing it can show anybody is where
         // that ground is. See ChunkAnchor.vfx.
         boolean anchors = TYPE_CHUNK_ANCHOR.equals(type);
         if (!farms && !anchors) {
            continue;
         }
         ServerLevel level = levelOf(server, e.getKey());
         BlockPos pos = posOf(e.getKey());
         if (level == null || pos == null) {
            continue;
         }
         // The anchor is re-asserted BEFORE anything is asked about its ground, and that order is the
         // whole fix for an anchor that stopped holding its chunks. This loop used to skip any machine
         // whose own chunk was not loaded - and the one machine whose chunk being unloaded IS the bug
         // is the one whose entire job is to keep it loaded. Its ticket was only ever re-asserted once
         // a minute by reaffirm, so an anchor found dark sat dark until then. Now it heals itself the
         // moment it is found dark, and is only then asked where its own boundary is.
         if (anchors) {
            if (!level.isLoaded(pos)) {
               ChunkAnchor.reassert(level, pos);
               continue;
            }
            ChunkAnchor.vfx(level, pos);
            continue;
         }
         if (!level.isLoaded(pos) || !standsIn(level, pos, type)) {
            continue;
         }
         if ("auto_planter".equals(type)) {
            FarmMachines.tickPlanter(level, pos, e.getValue().owner());
         } else if ("auto_harvester".equals(type)) {
            FarmMachines.tickHarvester(level, pos, e.getValue().owner());
         } else {
            FarmMachines.tickSprinkler(level, pos, e.getValue().owner());
         }
      }
   }

   /**
    * Upwards Hopper behaviour. Runs every 8 ticks from the hopper mixin (which
    * cancels vanilla hopper logic for this block). Item flow:
    * <ul>
    *   <li>anything in the hopper is pushed UP into the container above; if the
    *       above container is full/blocked, items fall back to side-push like a
    *       vanilla hopper (side connections still work both ways);</li>
    *   <li>item entities lying on top are vacuumed up into the above container
    *       (or held in the hopper if it's blocked);</li>
    *   <li>it still sucks items in from the container above like a normal hopper
    *       face, so two stacked upwards hoppers circulate instead of jamming.</li>
    * </ul>
    */
   public static void tickUpwardsHopper(Level level, BlockPos pos) {
      if (level.isClientSide() || !(level.getBlockEntity(pos) instanceof HopperBlockEntity hopper)) {
         return;
      }

      BlockPos above = pos.above();
      Container aboveContainer = level.getBlockEntity(above) instanceof Container c && ChestShopManager.get(level, above) == null ? c : null;

      // 1. Push held items up.
      if (aboveContainer != null && ClaimManager.canInteract(ownerOf(level, pos), level, above)) {
         for (int i = 0; i < hopper.getContainerSize(); i++) {
            ItemStack s = hopper.getItem(i);
            if (!s.isEmpty() && !tryPushUp(hopper, i, s, aboveContainer)) {
               break;
            }
         }
      }

      // 2. Vacuum item entities resting on the hopper's funnel (they used to sit
      // on top doing nothing since a hopper pushes down, not up).
      AABB area = new AABB(pos).inflate(0.4, 0.1, 0.4);
      for (ItemEntity entity : level.getEntitiesOfClass(ItemEntity.class, area)) {
         if (entity.isAlive() && !entity.getItem().isEmpty() && entity.getY() >= pos.getY() + 0.9) {
            ItemStack stack = entity.getItem();
            boolean stored = aboveContainer != null && ClaimManager.canInteract(ownerOf(level, pos), level, entity.blockPosition())
               ? tryInsert(aboveContainer, stack)
               : false;
            if (!stored) {
               stored = tryInsert(hopper, stack);
            }

            if (stack.isEmpty()) {
               entity.discard();
            } else {
               entity.setItem(stack);
            }
         }
      }

      // 3. Side connections: behave exactly like a vanilla hopper - push into a
      // container facing sideways, and pull from the container above. Step 1
      // prefers the up-push, so sideways neighbours only receive overflow.
      int facingUp = aboveContainer != null ? 1 : 0;
      int held = 0;
      for (int i = 0; i < hopper.getContainerSize(); i++) {
         if (!hopper.getItem(i).isEmpty()) {
            held++;
         }
      }

      if (held > facingUp && aboveContainer == null) {
         // Nothing above to push into: run a single vanilla-style side push.
         Direction side = Direction.from2DDataValue(level.getRandom().nextInt(4));
         BlockPos sidePos = pos.relative(side);
         if (level.getBlockEntity(sidePos) instanceof Container c2
            && ChestShopManager.get(level, sidePos) == null
            && ClaimManager.canInteract(ownerOf(level, pos), level, sidePos)) {
            for (int i = 0; i < hopper.getContainerSize(); i++) {
               ItemStack s = hopper.getItem(i);
               if (!s.isEmpty() && tryInsert(c2, s)) {
                  break;
               }
            }
         }
      }

      hopper.setChanged();
   }

   private static UUID ownerOf(Level level, BlockPos pos) {
      Machine m = machines.get(keyFor(level, pos));
      return m == null ? null : m.owner();
   }

   /** Moves the whole (or partial) stack at slot up into the target; false when full. */
   private static boolean tryPushUp(HopperBlockEntity hopper, int slot, ItemStack stack, Container target) {
      for (int t = 0; t < target.getContainerSize(); t++) {
         ItemStack cur = target.getItem(t);
         if (cur.isEmpty()) {
            target.setItem(t, stack.copy());
            hopper.setItem(slot, ItemStack.EMPTY);
            target.setChanged();
            return true;
         }

         if (ItemStack.isSameItemSameComponents(cur, stack) && cur.getCount() < cur.getMaxStackSize()) {
            int move = Math.min(cur.getMaxStackSize() - cur.getCount(), stack.getCount());
            cur.grow(move);
            stack.shrink(move);
            target.setChanged();
            if (stack.isEmpty()) {
               hopper.setItem(slot, ItemStack.EMPTY);
               return true;
            }
         }
      }

      return false;
   }

   /** Generic single-stack insert helper (leaves leftovers in the source stack). */
   private static boolean tryInsert(Container target, ItemStack stack) {
      boolean moved = false;

      for (int t = 0; t < target.getContainerSize() && !stack.isEmpty(); t++) {
         ItemStack cur = target.getItem(t);
         if (cur.isEmpty()) {
            target.setItem(t, stack.copy());
            stack.setCount(0);
            moved = true;
            target.setChanged();
         } else if (ItemStack.isSameItemSameComponents(cur, stack) && cur.getCount() < cur.getMaxStackSize()) {
            int move = Math.min(cur.getMaxStackSize() - cur.getCount(), stack.getCount());
            cur.grow(move);
            stack.shrink(move);
            if (move > 0) {
               moved = true;
            }

            target.setChanged();
         }
      }

      return moved;
   }

   public static void tickAutoSell(Level level, BlockPos pos) {
      if (!level.isClientSide() && ModConfig.is("autosell")) {
         if (level.getGameTime() % 8L == 0L) {
            Machine m = machines.get(keyFor(level, pos));
            if (m != null && "auto_sell_hopper".equals(m.type()) && m.owner() != null) {
               long total = 0L;
               if (level.getBlockEntity(pos) instanceof HopperBlockEntity h) {
                  for (int i = 0; i < h.getContainerSize(); i++) {
                     ItemStack s = h.getItem(i);
                     if (!s.isEmpty()) {
                        if (ModItems.isSpawnerItem(s)) {
                           spawnDrop(level, pos, s);
                           h.setItem(i, ItemStack.EMPTY);
                        } else {
                           long value = BlockValues.valueOf(s);
                           if (value > 0L) {
                              total += value;
                           } else {
                              spawnDrop(level, pos, s);
                           }

                           h.setItem(i, ItemStack.EMPTY);
                        }
                     }
                  }

                  h.setChanged();
               }

               BlockPos above = pos.above();
               if (level.getBlockEntity(above) instanceof Container c
                  && ChestShopManager.get(level, above) == null
                  && ClaimManager.canInteract(m.owner(), level, above)) {
                  for (int i = 0; i < c.getContainerSize(); i++) {
                     ItemStack s = c.getItem(i);
                     if (!s.isEmpty() && BlockValues.valueOf(s) > 0L && !ModItems.isSpawnerItem(s)) {
                        ItemStack removed = c.removeItem(i, s.getCount());
                        if (!removed.isEmpty()) {
                           total += BlockValues.valueOf(removed);
                           c.setChanged();
                        }
                     }
                  }
               }

               AABB area = new AABB(pos).inflate(2.0, 1.0, 2.0);

               for (ItemEntity entity : level.getEntitiesOfClass(ItemEntity.class, area)) {
                  ItemStack s = entity.getItem();
                  if (!s.isEmpty()
                     && BlockValues.valueOf(s) > 0L
                     && !ModItems.isSpawnerItem(s)
                     && ClaimManager.canInteract(m.owner(), level, entity.blockPosition())) {
                     total += BlockValues.valueOf(s);
                     entity.discard();
                  }
               }

               if (total > 0L) {
                  total = Math.round((float)total * SkillManager.sellMultiplier(m.owner()));
                  EconomyManager.addCash(m.owner(), total);
                  if (level instanceof ServerLevel serverLevel && m.effects()) {
                     double x = pos.getX() + 0.5;
                     double y = pos.getY() + 0.7;
                     double z = pos.getZ() + 0.5;
                     com.fortuneandfavors.net.FfVfx.particles(serverLevel, ParticleTypes.HAPPY_VILLAGER, x, y, z, 5, 0.35, 0.4, 0.35, 0.02);
                     com.fortuneandfavors.net.FfVfx.particles(serverLevel, ParticleTypes.END_ROD, x, y, z, 6, 0.3, 0.3, 0.3, 0.05);
                     com.fortuneandfavors.net.FfVfx.particles(serverLevel, ParticleTypes.ITEM_SLIME, x, y + 0.3, z, 3, 0.2, 0.3, 0.2, 0.02);
                     String key = keyFor(level, pos);
                     long now = level.getGameTime();
                     Long last = lastSellSound.get(key);
                     if (last == null || now - last >= 20L) {
                        lastSellSound.put(key, now);
                        level.playSound(null, x, y, z, ModSounds.SELL, SoundSource.BLOCKS, 0.5F, 1.5F);
                        if (m.owner() != null && level.getServer() != null) {
                           ServerPlayer owner = level.getServer().getPlayerList().getPlayer(m.owner());
                           if (owner != null) {
                              owner.sendSystemMessage(Component.literal("§aAuto-sold for §f$" + total + "§a!"), true);
                           }
                        }
                     }
                  }
               }
            }
         }
      }
   }

   private static void spawnDrop(Level level, BlockPos pos, ItemStack stack) {
      if (level != null && !level.isClientSide()) {
         ItemEntity item = new ItemEntity(level, pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, stack);
         item.setDeltaMovement(0.0, 0.1, 0.0);
         level.addFreshEntity(item);
      }
   }

   public static long payout(Machine m, int version) {
      return m == null ? 0L : m.payout(version);
   }

   public static Machine withPayout(Machine m, int version, long amount) {
      if (m != null && m.payouts() != null) {
         long[] payouts = (long[])m.payouts().clone();
         payouts[version - 1] = Math.max(0L, amount);
         return new Machine(m.type(), m.owner(), m.ownerName(), m.cash(), payouts, m.effects());
      } else {
         return m;
      }
   }

   public static Machine withCash(Machine m, long cash) {
      return new Machine(m.type(), m.owner(), m.ownerName(), Math.max(0L, cash), m.payouts(), m.effects());
   }

   public static Machine withEffects(Machine m, boolean effects) {
      return new Machine(m.type(), m.owner(), m.ownerName(), m.cash(), m.payouts(), effects);
   }

   public static Machine toggleEffects(Level level, BlockPos pos) {
      Machine m = get(level, pos);
      if (m != null && "auto_sell_hopper".equals(m.type())) {
         Machine updated = withEffects(m, !m.effects());
         machines.put(keyFor(level, pos), updated);
         save(level.getServer());
         return updated;
      } else {
         return null;
      }
   }

   public static boolean deposit(ServerPlayer player, Level level, BlockPos pos, long amount) {
      if (amount <= 0L) {
         return false;
      }

      Machine m = get(level, pos);
      if (m != null && "token_redeemer".equals(m.type())) {
         if (m.owner() == null || !m.owner().equals(player.getUUID())) {
            Chat.msg(player, "&cOnly the owner can fund this redeemer.");
            return false;
         } else if (!EconomyManager.takeCash(player.getUUID(), amount)) {
            Chat.msg(player, "&cYou can't afford that deposit!");
            return false;
         } else {
            long cash = m.cash() + amount;
            machines.put(keyFor(level, pos), withCash(m, cash));
            save(level.getServer());
            Chat.raw(player, "&aDeposited " + Chat.moneyStr(amount) + " into the redeemer. Pool: " + Chat.moneyStr(cash) + ".");
            SoundUtil.play(player, ModSounds.TRANSFER);
            return true;
         }
      } else {
         return false;
      }
   }

   public static boolean withdraw(ServerPlayer player, Level level, BlockPos pos) {
      Machine m = get(level, pos);
      if (m != null && "token_redeemer".equals(m.type())) {
         if (m.owner() == null || !m.owner().equals(player.getUUID())) {
            Chat.msg(player, "&cOnly the owner can withdraw from this redeemer.");
            return false;
         } else if (m.cash() <= 0L) {
            Chat.msg(player, "&cThe pool is empty.");
            return false;
         } else {
            EconomyManager.addCash(player.getUUID(), m.cash());
            Chat.raw(player, "&aWithdrew " + Chat.moneyStr(m.cash()) + " from the redeemer pool.");
            machines.put(keyFor(level, pos), withCash(m, 0L));
            save(level.getServer());
            SoundUtil.play(player, ModSounds.TRANSFER);
            return true;
         }
      } else {
         return false;
      }
   }

   public static boolean redeem(ServerPlayer player, Level level, BlockPos pos, ItemStack token) {
      if (level.isClientSide()) {
         return false;
      }

      Machine m = get(level, pos);
      if (m != null && "token_redeemer".equals(m.type())) {
         int version = TokenManager.versionOf(token);
         long payout = payout(m, version);
         if (payout <= 0L) {
            Chat.msg(player, "&cThat token version has no payout set on this redeemer yet.");
            return false;
         }

         if (m.owner() == null) {
            Chat.msg(player, "&cThis redeemer has no owner to pay out from.");
            return false;
         }

         long fromPool = Math.min(m.cash(), payout);
         long fromOwner = payout - fromPool;
         if (fromOwner > 0L && !EconomyManager.hasCash(m.owner(), fromOwner)) {
            Chat.msg(
               player, "&cThe pool has " + Chat.moneyStr(m.cash()) + " and the owner can't cover the remaining " + Chat.moneyStr(fromOwner) + " right now."
            );
            return false;
         }

         String brand = TokenManager.customName(token);
         machines.put(keyFor(level, pos), withCash(m, m.cash() - fromPool));
         if (fromOwner > 0L) {
            EconomyManager.takeCash(m.owner(), fromOwner);
         }

         EconomyManager.addCash(player.getUUID(), payout);
         token.shrink(1);
         save(level.getServer());
         String what = brand.isEmpty() ? "Token " + TokenManager.versionName(version) : "\"" + brand + "\" Token " + TokenManager.versionName(version);
         Chat.raw(
            player,
            "&a&lRedeemed!&r &7"
               + what
               + " for "
               + Chat.moneyStr(payout)
               + "&7. Balance: "
               + Chat.moneyStr(EconomyManager.balance(player.getUUID()))
               + "&7. Pool left: "
               + Chat.moneyStr(m.cash() - fromPool)
               + "."
         );
         SoundUtil.play(player, ModSounds.SELL);
         return true;
      } else {
         return false;
      }
   }

   public static void setPayout(Level level, BlockPos pos, int version, long amount) {
      Machine m = get(level, pos);
      if (m != null && "token_redeemer".equals(m.type()) && version >= 1 && version <= 4) {
         machines.put(keyFor(level, pos), withPayout(m, version, amount));
         save(level.getServer());
      }
   }


    public record Machine(String type, UUID owner, String ownerName, long cash, long[] payouts, boolean effects) {
       public long payout(int version) {
          return this.payouts != null && version >= 1 && version <= this.payouts.length ? this.payouts[version - 1] : 0L;
       }
    }
}
