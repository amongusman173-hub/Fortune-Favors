package com.fortuneandfavors.economy;

import com.fortuneandfavors.economy.ExplosionRebuildManager.Pending;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity.RemovalReason;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

public final class ExplosionRebuildManager {
   private static final Random RANDOM = new Random();
   private static final int REBUILD_DELAY_TICKS = 100;
   private static final int REBUILD_SPREAD_TICKS = 20;
   /** Blocks restored per tick. 8192 was a tick-killer: each entry is a real
    *  setBlock plus a full block-entity restore (light updates, chunk dirtying,
    *  hopper/chest contents), so one forced tick could stall the server for
    *  hundreds of milliseconds. 512 keeps the same rebuild, spread over more
    *  ticks, without the spike. */
   private static final int MAX_REBUILDS_PER_TICK = 512;
   /** Cap on captured blocks awaiting rebuild. The memory that actually hurts is a
    *  pending block that carries a block entity - a chest's entire contents pinned
    *  as NBT - not a bare BlockState, which is a pointer to a shared instance. So
    *  the two are counted separately: a plain block is cheap and the queue may hold
    *  a lot of them, while the chests/hoppers/beacons are the ones that need a
    *  ceiling. The old single 100k cap counted both the same and was hit by any
    *  large blast, at which point {@link #capture} silently stopped queueing - the
    *  exact "the explosion is too big and it just stops rebuilding". */
   private static final int MAX_PENDING = 400000;
   /** How many of those entries may pin block-entity NBT. Past this, the block is
    *  still queued (and comes back) but without its contents, which is a whole lot
    *  better than the block never returning at all. */
   private static final int MAX_PENDING_BLOCK_ENTITIES = 20000;
   /** How long a queued block may wait for its chunk to load before it is given
    *  up on. A blast that spans chunks can queue blocks in a chunk the server then
    *  unloads; those used to be removed from the queue on the very first tick and
    *  restored never. Ten seconds is long enough for a chunk to come back, and
    *  short enough that a genuinely gone chunk does not hold the queue open. */
   private static final int LOAD_WAIT_TICKS = 200;
   private static int pendingBlockEntities = 0;
   private static boolean capacityLogged = false;
   private static final Set<ServerLevel> rescueCheck = new HashSet<>();
   /** A LinkedList on purpose: the rebuild drains with {@code Iterator.remove()},
    *  and on an ArrayList every one of those is an O(n) shift of the whole queue.
    *  At the 512 rebuilds a tick this manager does, a large queue meant tens of
    *  millions of element moves per tick - a stalled server, and one more way a
    *  huge blast looked like "the rebuild stopped". */
   private static final List<Pending> pending = new java.util.LinkedList<>();
   /** Columns (X/Z) that must not have blocks fall through them while a rebuild is
    *  queued in that column. Keyed by X/Z on purpose - see {@link #packColumn}. */
   private static final LongSet frozenColumns = new LongOpenHashSet();
   /** How many queued blocks each column (X/Z) still holds. This is the counter
    *  that replaced a linear {@code pending} scan per restored block: several
    *  overlapping blasts could queue tens of thousands of entries, and every one
    *  of the 512 rebuilds a tick walked the whole list to decide whether a column
    *  could thaw - O(n^2) work that stalled the server exactly when it was already
    *  under load. */
   private static final Map<Long, Integer> columnCounts = new HashMap<>();
   /** Exact block positions waiting on the rebuild queue (full X/Y/Z, so a
    *  pending block at y=64 never hides an item drop at y=20 in the same column). */
   private static final LongSet pendingPositions = new LongOpenHashSet();
   private static final Set<ServerLevel> launchDone = Collections.newSetFromMap(new IdentityHashMap<>());

   private ExplosionRebuildManager() {
   }

   public static void capture(ServerLevel level, List<BlockPos> positions) {
      capture(level, positions, false);
   }

   /**
    * Captures blocks destroyed by the Wither itself, ignoring the Explosion
    * Rebuild setting.
    *
    * <p>Boss wreckage is not player-caused destruction. Leaving the arena a
    * permanent crater after every wither fight is not a choice anyone makes on
    * purpose - it is what happens when a global "explosions do not rebuild"
    * switch is read as "nothing rebuilds". The setting still governs TNT,
    * creepers and everything players do; the wither's own attacks always heal.
    */
   public static void captureAlways(ServerLevel level, List<BlockPos> positions) {
      capture(level, positions, true);
   }

   private static void capture(ServerLevel level, List<BlockPos> positions, boolean force) {
      if ((force || ModConfig.explosionRebuild()) && positions != null && !positions.isEmpty()) {
         long base = level.getGameTime() + 100L;

         for (BlockPos pos : positions) {
            if (pending.size() >= MAX_PENDING) {
               // Do not stop quietly: a capture that is dropped leaves a hole that
               // never heals, and the only trace of why was nothing at all. Warn
               // once (until the queue drains) and keep the rest of the volley out
               // of the queue deliberately rather than silently.
               if (!capacityLogged) {
                  capacityLogged = true;
                  com.fortuneandfavors.FortuneFavorsMod.LOGGER.warn(
                     "Fortune & Favors: explosion rebuild queue is at its {} block cap - {} block(s) of this blast were not queued.",
                     MAX_PENDING,
                     positions.size()
                  );
               }
               break;
            }

            // Already queued by an earlier blast in the same volley. Capturing it
            // twice would pin a second copy of a chest's contents and, worse, the
            // later duplicate could restore *after* the player had already mined
            // the block again - a ghost block that reappears from nowhere.
            if (pendingPositions.contains(pos.asLong())) {
               continue;
            }

            BlockState state = level.getBlockState(pos);
            if (!state.isAir() && !state.is(Blocks.TNT)) {
               CompoundTag be = null;
               boolean carriesBe = false;

               try {
                  BlockEntity te = level.getBlockEntity(pos);
                  if (te != null) {
                     // Past the block-entity ceiling the block still rebuilds; only
                     // its contents are left behind. Refusing the whole block would
                     // punch a permanent hole in the world, which is worse.
                     if (pendingBlockEntities < MAX_PENDING_BLOCK_ENTITIES) {
                        be = te.saveWithFullMetadata(level.registryAccess());
                        carriesBe = be != null;
                     }
                  }
               } catch (Exception ignored) {
                  be = null;
               }

               long at = base + RANDOM.nextInt(20);
               pending.add(new Pending(level, pos, state, be, at));
               if (carriesBe) {
                  pendingBlockEntities++;
               }
               pendingPositions.add(pos.asLong());
               long column = packColumn(pos);
               columnCounts.merge(column, 1, Integer::sum);
               frozenColumns.add(column);
            }
         }
      }
   }

   public static void tick(MinecraftServer server) {
      if (pending.isEmpty()) {
         launchDone.clear();
      } else {
         if (server.getTickCount() % 10L == 0L) {
            Set<ServerLevel> levels = new HashSet<>();

            for (Pending p0 : pending) {
               levels.add(p0.level);
            }

            for (ServerLevel lvl : levels) {
               if (lvl != null) {
                  launchCraterDwellers(lvl);
               }
            }
         }

         int rebuilt = 0;
         Iterator<Pending> it = pending.iterator();

         while (it.hasNext() && rebuilt < MAX_REBUILDS_PER_TICK) {
            Pending p = it.next();
            ServerLevel level = p.level;
            if (level == null) {
               it.remove();
               releasePosition(p);
               continue;
            }
            if (level.getGameTime() < p.rebuildAt) {
               continue;
            }
            // A block whose chunk is not loaded cannot be put back yet - but it is
            // not gone either. This used to drop the entry on the first tick it was
            // due, so any blast whose blocks were spread across chunks the server
            // had since unloaded left part of the crater unhealed, forever.
            if (!level.isLoaded(p.pos)) {
               if (++p.loadWaits <= LOAD_WAIT_TICKS) {
                  continue;
               }
            }
            it.remove();
            releasePosition(p);
            if (restore(p)) {
               rebuilt++;
            }
         }

         if (pending.isEmpty()) {
            drainRescue();
         }
      }
   }

   /** Puts one queued block back. Returns true only when it really landed. */
   private static boolean restore(Pending p) {
      ServerLevel level = p.level;
      if (level == null || !level.isLoaded(p.pos)) {
         return false;
      }

      if (!level.getBlockState(p.pos).isAir()) {
         clearFallingAt(level, p.pos);
         if (!level.getBlockState(p.pos).isAir()) {
            return false;
         }
      }

      try {
         level.setBlock(p.pos, p.state, 3);
         if (p.blockEntity != null) {
            BlockEntity restored = BlockEntity.loadStatic(p.pos, p.state, p.blockEntity, level.registryAccess());
            if (restored != null) {
               level.setBlockEntity(restored);
            }
         }

         rewindFx(level, p.pos);
         rescueCheck.add(level);
         return true;
      } catch (Exception ignored) {
         return false;
      }
   }

   private static void releasePosition(Pending p) {
      if (p.blockEntity != null && pendingBlockEntities > 0) {
         pendingBlockEntities--;
      }
      pendingPositions.remove(p.pos.asLong());
      long column = packColumn(p.pos);
      Integer remaining = columnCounts.get(column);
      if (remaining == null || remaining <= 1) {
         columnCounts.remove(column);
         frozenColumns.remove(column);
      } else {
         columnCounts.put(column, remaining - 1);
      }
   }

   /** Once nothing is queued, un-freeze and lift anyone the restored blocks
    *  would have buried. */
   private static void drainRescue() {
      frozenColumns.clear();
      pendingPositions.clear();
      columnCounts.clear();
      pendingBlockEntities = 0;
      capacityLogged = false;

      for (ServerLevel sl : rescueCheck) {
         if (sl != null) {
            rescueTrappedPlayers(sl);
         }
      }

      rescueCheck.clear();
   }

   // ------------------------------------------------------------------ test hooks

   /** How many blocks are queued for rebuild right now (headless proof). */
   public static int pendingCount() {
      return pending.size();
   }

   /** Test hook: pretend this many ticks have passed, so a headless run can
    *  drive the REAL {@link #tick(MinecraftServer)} rebuild path (delay included)
    *  without waiting five in-game seconds for it. Only the self-test calls it. */
   public static void ageQueueForTest(long ticks) {
      for (Pending p : pending) {
         p.rebuildAt -= ticks;
      }
   }

   /** Rebuild everything queued right now, ignoring the rebuild delay, so a
    *  headless run can assert the restored state inside a single tick. Only
    *  the self-test calls this. Returns how many blocks were put back. */
   public static int flushForTest() {
      int rebuilt = 0;
      Iterator<Pending> it = pending.iterator();

      while (it.hasNext()) {
         Pending p = it.next();
         it.remove();
         releasePosition(p);
         if (restore(p)) {
            rebuilt++;
         }
      }

      if (pending.isEmpty()) {
         drainRescue();
      }

      return rebuilt;
   }

   public static boolean isRebuildFrozen(BlockPos pos) {
      return !frozenColumns.isEmpty() && frozenColumns.contains(packColumn(pos));
   }

   /** Returns true if a block at this position is scheduled for rebuild (dupe prevention). */
   public static boolean isPendingRebuild(BlockPos pos) {
      return !pendingPositions.isEmpty() && pendingPositions.contains(pos.asLong());
   }

   /** Removes item entities at positions pending rebuild to prevent duplication. */
   public static void suppressItemDrops(ServerLevel level, List<BlockPos> positions) {
      suppressItemDrops(level, positions, false);
   }

   /** The same drop cleanup, for a forced (wither) capture. */
   public static void suppressItemDropsAlways(ServerLevel level, List<BlockPos> positions) {
      suppressItemDrops(level, positions, true);
   }

   private static void suppressItemDrops(ServerLevel level, List<BlockPos> positions, boolean force) {
      if ((!force && !ModConfig.explosionRebuild()) || positions == null || positions.isEmpty()) return;
      for (BlockPos pos : positions) {
         if (!pendingPositions.contains(pos.asLong())) continue;
         try {
            AABB box = new AABB(pos).inflate(0.5);
            for (ItemEntity ie : level.getEntitiesOfClass(ItemEntity.class, box)) {
               ie.remove(RemovalReason.DISCARDED);
            }
         } catch (Exception ignored) {
         }
      }
   }

   /** Packs only X and Z: the column-level identity used by the falling-block
    *  freeze. Position-exact bookkeeping uses {@link BlockPos#asLong()} instead. */
   private static long packColumn(BlockPos pos) {
      return (long)pos.getX() << 32 | pos.getZ() & 4294967295L;
   }

   private static void launchCraterDwellers(ServerLevel level) {
      try {
         // Build one column -> highest queued block map for this level up front,
         // instead of rescanning every pending entry for every player. A player's
         // column is "being rebuilt under them" exactly when its highest queued
         // block sits at or above their feet, so the maximum Y is all we need.
         Map<Long, Integer> colTopByColumn = new HashMap<>();

         for (Pending p0 : pending) {
            if (p0.level == level) {
               long column = packColumn(p0.pos);
               Integer top = colTopByColumn.get(column);
               if (top == null || p0.pos.getY() > top) {
                  colTopByColumn.put(column, p0.pos.getY());
               }
            }
         }

         if (colTopByColumn.isEmpty()) {
            return;
         }

         for (ServerPlayer p : level.getPlayers(pl -> pl != null && pl.isAlive() && !pl.isSpectator())) {
            BlockPos bp = p.blockPosition();
            Integer highest = colTopByColumn.get(packColumn(bp));
            boolean underRebuild = highest != null && highest >= bp.getY() - 1;
            int colTop = highest != null ? Math.max(bp.getY(), highest) : bp.getY();

            if (underRebuild) {
               if (p.isPassenger()) {
                  p.stopRiding();
               }

               int depth;
               for (depth = 0; depth < 32; depth++) {
                  BlockPos below = bp.below(depth + 1);
                  if (below.getY() < level.getMinY() || !level.getBlockState(below).isAir()) {
                     break;
                  }
               }

               double lift = Math.max(2.5, colTop + 1 - p.getY());
               double power = Math.min(2.0, 0.6 + depth * 0.1);
               double tx = p.getX();
               double ty = Math.min(level.getMaxY() - 2, p.getY() + lift);
               double tz = p.getZ();
               if (!isSafeStand(level, tx, ty, tz)) {
                  double[] safe = nearestSafeStand(level, tx, ty, tz, p.getY());
                  if (safe != null) {
                     tx = safe[0];
                     ty = safe[1];
                     tz = safe[2];
                  }
               }

               p.setDeltaMovement(0.0, power, 0.0);
               // A body placed by the server, told to the anticheat by name: this is a
               // scripted launch, not a player who flew here.
               com.fortuneandfavors.anticheat.AntiCheat.onServerTeleport(p, "a scripted blast lift");
               p.setPos(tx, ty, tz);
               p.hurtMarked = true;
               p.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 160, 0, false, false));
               com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.CLOUD, p.getX(), p.getY() + 0.5, p.getZ(), 14, 0.5, 0.3, 0.5, 0.06);
            }
         }
      } catch (Exception var20) {
      }
   }

   private static boolean isSafeStand(ServerLevel level, double x, double y, double z) {
      if (!(y < level.getMinY()) && !(y + 2.0 > level.getMaxY())) {
         BlockPos feet = BlockPos.containing(x, y, z);
         BlockPos head = feet.above();
         return level.getBlockState(feet).getCollisionShape(level, feet).isEmpty() && level.getBlockState(head).getCollisionShape(level, head).isEmpty();
      } else {
         return false;
      }
   }

   private static double[] nearestSafeStand(ServerLevel level, double x, double y, double z, double minY) {
      double yTop = Math.max(y, minY);
      double yBottom = Math.min(y, minY);

      for (int step = 0; step <= (int)Math.ceil(yTop - yBottom); step++) {
         double tryY = yTop - step;

         for (int r = 0; r <= 8; r++) {
            for (int dx = -r; dx <= r; dx++) {
               for (int dz = -r; dz <= r; dz++) {
                  if (Math.max(Math.abs(dx), Math.abs(dz)) == r && isSafeStand(level, x + dx, tryY, z + dz)) {
                     return new double[]{x + dx, tryY, z + dz};
                  }
               }
            }
         }
      }

      return null;
   }

   private static void clearFallingAt(ServerLevel level, BlockPos pos) {
      try {
         for (FallingBlockEntity f : level.getEntitiesOfClass(
            FallingBlockEntity.class, new AABB(pos).inflate(0.5), fx -> fx.isAlive() && fx.blockPosition().equals(pos)
         )) {
            f.remove(RemovalReason.DISCARDED);
         }
      } catch (Exception var4) {
      }
   }

   private static void rewindFx(ServerLevel level, BlockPos pos) {
      double x = pos.getX() + 0.5;
      double y = pos.getY() + 0.5;
      double z = pos.getZ() + 0.5;
      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.END_ROD, x, y, z, 1, 0.3, 0.3, 0.3, 0.04);
      if (RANDOM.nextInt(15) == 0) {
         level.playSound(null, pos, SoundEvents.ENCHANTMENT_TABLE_USE, SoundSource.BLOCKS, 0.2F, 1.4F);
      }
   }

   private static void rescueTrappedPlayers(ServerLevel level) {
      try {
         for (ServerPlayer p : level.getPlayers(pl -> pl != null && pl.isAlive() && !pl.isSpectator())) {
            BlockPos head = BlockPos.containing(p.getX(), p.getEyeY(), p.getZ());
            if (!level.getBlockState(head).getCollisionShape(level, head).isEmpty()
               && !level.getBlockState(head.below()).getCollisionShape(level, head.below()).isEmpty()) {
               int surfaceY = head.getY();

               while (surfaceY < level.getMaxY() - 2) {
                  BlockPos check = new BlockPos(head.getX(), ++surfaceY, head.getZ());
                  BlockPos checkHead = new BlockPos(head.getX(), surfaceY + 1, head.getZ());
                  if (level.getBlockState(check).getCollisionShape(level, check).isEmpty()
                     && level.getBlockState(checkHead).getCollisionShape(level, checkHead).isEmpty()) {
                     p.teleportTo(p.getX(), surfaceY, p.getZ());
                     p.setDeltaMovement(0.0, 0.5, 0.0);
                     p.hurtMarked = true;
                     com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.CLOUD, p.getX(), p.getY() + 0.5, p.getZ(), 10, 0.5, 0.3, 0.5, 0.06);
                     break;
                  }
               }
            }
         }
      } catch (Exception var7) {
      }
   }


    static final class Pending {
       final ServerLevel level;
       final BlockPos pos;
      final BlockState state;
      final CompoundTag blockEntity;
      long rebuildAt;
      /** Ticks this block has been due but waiting for its chunk to load. */
      int loadWaits;
    
       Pending(ServerLevel level, BlockPos pos, BlockState state, CompoundTag blockEntity, long rebuildAt) {
          this.level = level;
          this.pos = pos;
          this.state = state;
          this.blockEntity = blockEntity;
          this.rebuildAt = rebuildAt;
       }
    }
}
