package com.fortuneandfavors.anticheat;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Bounded per-player authoritative world history.
 *
 * <p>This is intentionally a local replica, not a claim that the whole Minecraft
 * world has been copied. Each player retains the small collision/visibility window
 * around their authoritative position for the last few ticks. It is enough for the
 * checks that are most sensitive to packet delay: a placement under feet, and an ore
 * whose face was open when the player could actually see it. The live world remains
 * the authority; snapshots only answer what was true when a delayed packet could
 * have been sent.
 *
 * <p>Capture is server-thread-only. The data is immutable after capture, bounded to
 * eight samples and a 3x5x3 local window, so it cannot become an unbounded per-player
 * cache or introduce the async world-read race that a premature Netty implementation
 * would create.
 */
public final class WorldSnapshotHistory {
   public static final int MAX_SNAPSHOTS = 8;
   private static final int RADIUS = 1;
   private static final int BELOW = 1;
   private static final int ABOVE = 3;
   private static final Map<UUID, Deque<Snapshot>> PLAYERS = new HashMap<>();

   private WorldSnapshotHistory() {
   }

   public record Snapshot(long tick, BlockPos feet, Map<Long, BlockState> blocks) {
   }

   /** Captures the local authoritative collision/visibility window. */
   public static void capture(ServerPlayer player, long tick) {
      if (player == null || !(player.level() instanceof ServerLevel level)) {
         return;
      }
      BlockPos feet = player.blockPosition();
      Map<Long, BlockState> blocks = new HashMap<>();
      for (int x = -RADIUS; x <= RADIUS; x++) {
         for (int y = -BELOW; y <= ABOVE; y++) {
            for (int z = -RADIUS; z <= RADIUS; z++) {
               BlockPos pos = feet.offset(x, y, z);
               blocks.put(pos.asLong(), level.getBlockState(pos));
            }
         }
      }
      Deque<Snapshot> snapshots = PLAYERS.computeIfAbsent(player.getUUID(), ignored -> new ArrayDeque<>());
      snapshots.addLast(new Snapshot(tick, feet, Map.copyOf(blocks)));
      while (snapshots.size() > MAX_SNAPSHOTS) {
         snapshots.removeFirst();
      }
   }

   public static void forget(UUID id) {
      if (id != null) {
         PLAYERS.remove(id);
      }
   }

   public static void clear() {
      PLAYERS.clear();
   }

   public static int tracked() {
      return PLAYERS.size();
   }

   /** Whether any recent snapshot places the player over the placed block. */
   public static boolean wasUnder(ServerPlayer player, BlockPos placed, long now, int latencyTicks) {
      Deque<Snapshot> snapshots = PLAYERS.get(player.getUUID());
      if (snapshots == null) {
         return false;
      }
      long oldest = now - Math.min(LagCompensatedHistory.MAX_REWIND_TICKS, Math.max(0, latencyTicks));
      for (Snapshot snapshot : snapshots) {
         if (snapshot.tick() < oldest) {
            continue;
         }
         double dx = placed.getX() + 0.5 - (snapshot.feet().getX() + 0.5);
         double dz = placed.getZ() + 0.5 - (snapshot.feet().getZ() + 0.5);
         if (placed.getY() <= snapshot.feet().getY() - 1 && dx * dx + dz * dz <= 0.64) {
            return true;
         }
      }
      return false;
   }

   /**
    * True when the ore was present in a recent local snapshot and one of its faces
    * was open there. This is a historical visibility allowance, never a detector by
    * itself: callers still use the current ore block and statistical sequence checks.
    */
   public static boolean oreHadOpenFace(ServerPlayer player, BlockPos ore, long now, int latencyTicks) {
      Deque<Snapshot> snapshots = PLAYERS.get(player.getUUID());
      if (snapshots == null) {
         return false;
      }
      long oldest = now - Math.min(LagCompensatedHistory.MAX_REWIND_TICKS, Math.max(0, latencyTicks));
      for (Snapshot snapshot : snapshots) {
         if (snapshot.tick() < oldest || !snapshot.blocks().containsKey(ore.asLong())) {
            continue;
         }
         for (Direction direction : Direction.values()) {
            BlockState neighbor = snapshot.blocks().get(ore.relative(direction).asLong());
            if (neighbor != null && (neighbor.isAir() || neighbor.canBeReplaced() || !neighbor.getFluidState().isEmpty())) {
               return true;
            }
         }
      }
      return false;
   }
}
