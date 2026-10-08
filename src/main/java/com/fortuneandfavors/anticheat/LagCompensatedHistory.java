package com.fortuneandfavors.anticheat;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;

/**
 * A small, server-thread-owned lag-compensation history.
 *
 * <p>This is the safe subset of the world-replication idea: it does not read the
 * world from Netty threads and it does not invent client blocks. It retains the
 * last 300 ms of authoritative player positions, then uses the sample that could
 * have been visible when a delayed attack, placement, or break arrived. That is
 * enough to stop latency from looking like reach or scaffold while keeping every
 * collision and visibility decision server authoritative.
 *
 * <p>The history is intentionally bounded and lifecycle-managed. A full per-player
 * chunk replica is a separate storage system; this class is the position/history
 * foundation that can be extended to chunk snapshots without changing check APIs.
 */
public final class LagCompensatedHistory {
   public static final int MAX_SAMPLES = 8;
   public static final int MAX_REWIND_TICKS = 6;
   private static final Map<UUID, Deque<Sample>> PLAYERS = new HashMap<>();

   private LagCompensatedHistory() {
   }

   public record Sample(long tick, double x, double y, double z, float yaw, float pitch) {
      public Vec3 eye(double eyeHeight) {
         return new Vec3(x, y + eyeHeight, z);
      }
   }

   /** Records one authoritative server tick. Must be called on the server thread. */
   public static void record(ServerPlayer player, long tick) {
      if (player == null) {
         return;
      }
      Deque<Sample> samples = PLAYERS.computeIfAbsent(player.getUUID(), ignored -> new ArrayDeque<>());
      samples.addLast(new Sample(tick, player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot()));
      while (samples.size() > MAX_SAMPLES) {
         samples.removeFirst();
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

   /** Returns samples no older than the requested rewind, newest first. */
   private static Iterable<Sample> recent(UUID id, long now, int rewindTicks) {
      Deque<Sample> samples = PLAYERS.get(id);
      if (samples == null) {
         return java.util.List.of();
      }
      long oldest = now - Math.min(MAX_REWIND_TICKS, Math.max(0, rewindTicks));
      java.util.List<Sample> out = new java.util.ArrayList<>();
      java.util.Iterator<Sample> iterator = samples.descendingIterator();
      while (iterator.hasNext()) {
         Sample sample = iterator.next();
         if (sample.tick() >= oldest) {
            out.add(sample);
         }
      }
      return out;
   }

   /**
    * True if the target's current or recently authoritative box was in range.
    *
    * <p>The current box is checked by the caller first; the historical pass is only
    * for a delayed attack packet and is limited to the attacker's latency. A stale
    * position cannot grant reach because the entity interaction range is still the
    * attacker's real range plus the check margin.
    */
   public static boolean entityWasInRange(
      ServerPlayer attacker,
      Entity target,
      double margin,
      long now,
      int latencyTicks
   ) {
      if (!(target instanceof ServerPlayer historicalTarget)) {
         return false;
      }
      double allowed = attacker.entityInteractionRange() + margin;
      Vec3 eye = attacker.getEyePosition();
      for (Sample sample : recent(historicalTarget.getUUID(), now, latencyTicks)) {
         AABB box = historicalTarget.getBoundingBox().move(
            sample.x() - historicalTarget.getX(),
            sample.y() - historicalTarget.getY(),
            sample.z() - historicalTarget.getZ()
         );
         if (box.distanceToSqr(eye) < allowed * allowed) {
            return true;
         }
      }
      return false;
   }

   /** True when a recently-authoritative target box intersects the attacker's aim ray. */
   public static boolean rayMetHistoricalBox(
      ServerPlayer attacker,
      Entity target,
      double range,
      long now,
      int latencyTicks
   ) {
      if (!(target instanceof ServerPlayer historicalTarget)) {
         return false;
      }
      Vec3 eye = attacker.getEyePosition();
      Vec3 look = attacker.getLookAngle();
      Vec3 end = eye.add(look.scale(Math.max(1.0, range)));
      for (Sample sample : recent(historicalTarget.getUUID(), now, latencyTicks)) {
         double dx = sample.x() - historicalTarget.getX();
         double dy = sample.y() - historicalTarget.getY();
         double dz = sample.z() - historicalTarget.getZ();
         AABB box = historicalTarget.getBoundingBox().move(dx, dy, dz).inflate(0.15);
         if (box.clip(eye, end).isPresent()) {
            return true;
         }
      }
      return false;
   }

   /**
    * True when a delayed placement could have been under the player's authoritative
    * feet at the time the client sent it.
    */
   public static boolean wasUnder(
      ServerPlayer player,
      BlockPos placed,
      long now,
      int latencyTicks
   ) {
      double px = placed.getX() + 0.5;
      double pz = placed.getZ() + 0.5;
      for (Sample sample : recent(player.getUUID(), now, latencyTicks)) {
         double dx = px - sample.x();
         double dz = pz - sample.z();
         if (placed.getY() <= sample.y() - 1.0 && dx * dx + dz * dz <= 0.64) {
            return true;
         }
      }
      return false;
   }

   /**
    * Checks whether an ore was visible from the player's current or recent eye
    * positions. The target must still be a real block in the server world; history
    * only compensates for where the player was when the packet was sent.
    */
   public static boolean oreWasVisible(ServerPlayer player, ServerLevel level, BlockPos ore, long now) {
      Vec3 target = Vec3.atCenterOf(ore);
      int latencyTicks = latencyTicks(player);
      for (Sample sample : recent(player.getUUID(), now, latencyTicks)) {
         Vec3 eye = sample.eye(player.getEyeHeight());
         BlockHitResultCompat result = clip(level, eye, target, player);
         if (result.visible) {
            return true;
         }
      }
      return false;
   }

   private record BlockHitResultCompat(boolean visible) {
   }

   private static BlockHitResultCompat clip(ServerLevel level, Vec3 from, Vec3 to, ServerPlayer player) {
      try {
         var hit = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
         return new BlockHitResultCompat(hit.getType() != HitResult.Type.BLOCK || hit.getLocation().distanceTo(to) < 0.8);
      } catch (Throwable ignored) {
         // History is an allowance, never a reason to flag. If the world cannot be
         // queried, the normal current-position heuristic remains the only evidence.
         return new BlockHitResultCompat(false);
      }
   }

   private static int latencyTicks(ServerPlayer player) {
      try {
         return Math.min(MAX_REWIND_TICKS, Math.max(0, (player.connection.latency() + 49) / 50));
      } catch (Throwable ignored) {
         return 0;
      }
   }
}
