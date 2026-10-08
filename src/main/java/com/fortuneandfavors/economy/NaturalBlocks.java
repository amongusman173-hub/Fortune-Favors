package com.fortuneandfavors.economy;

import com.fortuneandfavors.util.JsonUtil;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Remembers which blocks were placed by a player, so gameplay bonuses that are
 * meant to reward real mining (Excavator double-drops, auto-smelt, ...) can be
 * restricted to NATURALLY GENERATED blocks. Otherwise a player could place a
 * block, break it, and farm perk procs for free.
 *
 * <p>Fed from {@code Block.setPlacedBy}. A record is retired by the break that
 * consumes it - the moment the placed block is gone, the position stops being
 * interesting - so the map holds roughly "player-placed blocks still standing",
 * not every block ever placed.
 *
 * <p>The record is <b>on disk</b>, and that is the difference between a rule and
 * a suggestion. The first version of this class remembered placements in memory
 * and expired them after thirty minutes of game time, which is an excavator dupe
 * with a waiting period: mine a natural diamond ore, place the ore, wait half an
 * hour (or simply grow the map past its cap, or stop the server, since stopping
 * cleared the map), break it again, and the perk paid as if the world had grown
 * it. Placement is now durable across restarts and is checked against the block
 * that is actually being broken:
 *
 * <ul>
 *   <li>a record whose block id <b>is</b> the block being broken means a player
 *       put that block there, so it is not natural - no matter how much time has
 *       passed, and no matter how many restarts happened in between;
 *   <li>a record whose block id is <b>not</b> the block being broken means the
 *       remembered block is gone (an explosion, a piston, an operator) and the
 *       position has moved on, so the record is stale and is dropped;
 *   <li>no record at all means world generation, which is the safe default for a
 *       perk that pays out drops.
 * </ul>
 */
public final class NaturalBlocks {
   private NaturalBlocks() {
   }

   /**
    * The hard bound on the record, and it is deliberately far larger than a
    * server can plausibly reach (records are retired as their blocks are broken).
    * It exists so a pathological build session cannot grow the file forever, not
    * as a rule: at the cap the oldest placements are forgotten, which gives the
    * perk back for blocks nobody has touched in a very long time.
    */
   private static final int PRUNE_AT = 200_000;

   private static final String FILE = "natural_blocks.json";

   /** "dimension:x,y,z" -> the registry id of the block a player placed there. */
   private static final Map<String, String> PLACED = new HashMap<>();

   /** Same keys, in the order they were recorded, so the cap can drop the oldest. */
   private static final Map<String, Long> PLACED_AT = new HashMap<>();

   private static Path dataFile;
   private static boolean dirty = false;

   /** Called from Block.setPlacedBy for every block a player places. */
   public static void onBlockPlaced(ServerLevel level, BlockPos pos) {
      try {
         String key = key(level, pos);
         PLACED.put(key, id(level.getBlockState(pos)));
         PLACED_AT.put(key, level.getGameTime());
         dirty = true;
         if (PLACED.size() > PRUNE_AT) {
            prune();
         }
      } catch (Throwable ignored) {
      }
   }

   /**
    * True when this block was NOT placed by a player.
    *
    * <p>Asked at the moment the block is broken, with the state that was broken,
    * so the answer can be checked against the record instead of merely trusting
    * that a record exists. It is a <b>read</b>: several bonuses answer the same
    * question about the same break (the excavator, mining pity, the mining zone),
    * and a query that consumed the record would let the second asker pay out for a
    * placed block. The record is retired once, by {@link #onBlockBroken}.
    */
   public static boolean isNatural(ServerLevel level, BlockPos pos, BlockState broken) {
      try {
         String recorded = PLACED.get(key(level, pos));
         return recorded == null || !recorded.equals(id(broken));
      } catch (Throwable t) {
         return true;
      }
   }

   /**
    * The end of a player's break at this position: whatever was remembered here
    * is gone with the block, so the position starts again from nothing.
    */
   public static void onBlockBroken(ServerLevel level, BlockPos pos) {
      try {
         String key = key(level, pos);
         if (PLACED.remove(key) != null) {
            PLACED_AT.remove(key);
            dirty = true;
         }
      } catch (Throwable ignored) {
      }
   }

   /** Server stopped / world changed: forget the memory, the file remembers. */
   public static void clear() {
      PLACED.clear();
      PLACED_AT.clear();
      dirty = false;
   }

   /** Test hook: how many placements are remembered right now. */
   public static int remembered() {
      return PLACED.size();
   }

   public static void load(MinecraftServer server) {
      try {
         dataFile = EconomyManager.getDataDir(server).resolve(FILE);
         PLACED.clear();
         PLACED_AT.clear();
         dirty = false;
         JsonObject root = JsonUtil.readOrCreate(dataFile, new JsonObject());
         for (String key : root.keySet()) {
            try {
               PLACED.put(key, root.get(key).getAsString());
               PLACED_AT.put(key, 0L);
            } catch (Throwable ignored) {
            }
         }
      } catch (Throwable ignored) {
      }
   }

   public static void save(MinecraftServer server) {
      try {
         if (dataFile == null) {
            dataFile = EconomyManager.getDataDir(server).resolve(FILE);
         }
         JsonObject root = new JsonObject();
         for (Map.Entry<String, String> e : PLACED.entrySet()) {
            root.addProperty(e.getKey(), e.getValue());
         }
         JsonUtil.write(dataFile, root);
         dirty = false;
      } catch (Throwable ignored) {
      }
   }

   /**
    * The version that is registered on the server's own save hook.
    *
    * <p>Same shape as every other manager in the mod, so a future reader does not
    * have to wonder why this one is the exception.
    */
   public static void saveIfDirty(MinecraftServer server) {
      if (dirty) {
         save(server);
      }
   }

   private static void prune() {
      // Oldest first. Records are retired by their own break, so reaching this
      // at all means a session placed a couple of hundred thousand blocks that
      // are all still standing.
      int drop = PLACED.size() - PRUNE_AT / 2;
      if (drop <= 0) {
         return;
      }
      for (String key : PLACED_AT.entrySet().stream()
         .sorted(Map.Entry.comparingByValue())
         .limit(drop)
         .map(Map.Entry::getKey)
         .toList()) {
         PLACED.remove(key);
         PLACED_AT.remove(key);
      }
   }

   private static String id(BlockState state) {
      return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
   }

   private static String key(ServerLevel level, BlockPos pos) {
      return level.dimension().identifier() + ":" + pos.asLong();
   }
}
