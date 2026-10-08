package com.fortuneandfavors.economy;

import com.fortuneandfavors.FortuneFavorsMod;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * The small coloured pulse a working machine gives off, and the ceiling on how many of them the
 * server will ever spend.
 *
 * <p>A machine park is a lag farm waiting to happen. Ten sorters, five hoppers and four smelters in
 * one room is not unusual, and a pulse per machine per tick is forty particles a tick from a corner
 * of a base nobody is standing in - which is how a feature that was supposed to say "this works"
 * becomes the reason the server runs late. So the particles are here, behind two limits and one
 * switch:
 *
 * <ul>
 *   <li><b>Five a tick, server-wide.</b> However many machines are running, at most
 *       {@link #MAX_PER_TICK} machine particles appear in any one tick. When the budget is gone, the
 *       rest of the machines simply do not sparkle this tick. It refills every tick, so a quiet
 *       world still looks alive.
 *   <li><b>A pause per machine.</b> A machine that has just pulsed waits {@link #SPACING} ticks
 *       before it may pulse again, so one busy smelter cannot spend the whole budget on itself and
 *       starve the sorter beside it.
 *   <li><b>{@code /ff config machine_particles}.</b> Off means the mod spawns none of these at all.
 *       This is the machine particles only - nothing here touches vanilla's own particles, a boss's
 *       telegraphs, or any other effect in the mod, so switching it off quiets the machines and
 *       nothing else.
 * </ul>
 *
 * <p>The budget is counted per <i>ticks observed</i> rather than per {@code Level.tick}, because the
 * mod's own dimensions run on {@link ServerClock} while vanilla's overworld does not - the same
 * frozen-clock trap that made a once-a-second sound play every pulse elsewhere in this mod.
 */
public final class MachineVfx {
   /** The most machine particles the whole server may spawn in one tick. */
   public static final int MAX_PER_TICK = 5;
   /** How long a machine must wait after pulsing before it may pulse again. */
   public static final int SPACING = 8;
   /** The `/ff` switch that turns every one of these off. */
   public static final String TOGGLE = "machine_particles";

   private static long budgetTick = Long.MIN_VALUE;
   private static int budgetLeft = MAX_PER_TICK;
   private static final Map<String, Long> nextPulse = new HashMap<>();
   /** How many particles this class has actually asked for since the last reset. Test-only. */
   private static long spawned = 0L;

   private MachineVfx() {
   }

   /** The particle a machine type gives off, or {@code null} for a type that does not pulse. */
   public static ParticleOptions particleFor(String type) {
      if (type == null) {
         return null;
      }
      return switch (type) {
         case MachineManager.TYPE_SUPER_HOPPER -> ParticleTypes.END_ROD;
         case MachineManager.TYPE_TRANSFER_HOPPER -> ParticleTypes.PORTAL;
         // The Overflow Hopper is the same machine, and it drips: the particle is the spillway, so a
         // player watching a bank of hoppers can see which one is the one that has started to run
         // over rather than having to open every window.
         case MachineManager.TYPE_OVERFLOW_HOPPER -> ParticleTypes.DRIPPING_HONEY;
         case MachineManager.TYPE_CHECKER_HOPPER -> ParticleTypes.SMOKE;
         case MachineManager.TYPE_TWO_WAY_SPLITTER -> ParticleTypes.ELECTRIC_SPARK;
         case MachineManager.TYPE_SUPER_SMELTER -> ParticleTypes.FLAME;
         case MachineManager.TYPE_ITEM_SORTER -> ParticleTypes.HAPPY_VILLAGER;
         case MachineManager.TYPE_AUTO_SELL -> ParticleTypes.COMPOSTER;
         case MachineManager.TYPE_UPWARDS -> ParticleTypes.CLOUD;
         case MachineManager.TYPE_AUTO_PLANTER, MachineManager.TYPE_AUTO_HARVESTER -> ParticleTypes.HAPPY_VILLAGER;
         case MachineManager.TYPE_IRRIGATION_SPRINKLER -> ParticleTypes.SNOWFLAKE;
         case MachineManager.TYPE_CHUNK_ANCHOR -> ParticleTypes.ENCHANT;
         case MachineManager.TYPE_REPAIR_STATION -> ParticleTypes.CRIT;
         case MachineManager.TYPE_PORTABLE_FURNACE, MachineManager.TYPE_PORTABLE_CAMPFIRE -> ParticleTypes.FLAME;
         default -> null;
      };
   }

   /**
    * Asks for one pulse above a machine. Silently does nothing when the budget is spent, when this
    * machine pulsed too recently, when the switch is off, or when the caller is a client.
    */
   public static void pulse(Level level, BlockPos pos, String type) {
      if (!(level instanceof ServerLevel server) || pos == null || type == null) {
         return;
      }
      if (!ModConfig.machineParticles()) {
         return;
      }
      ParticleOptions particle = particleFor(type);
      if (particle == null) {
         return;
      }
      long now = ServerClock.clock(level);
      if (now != budgetTick) {
         budgetTick = now;
         budgetLeft = MAX_PER_TICK;
      }
      if (budgetLeft <= 0) {
         return;
      }
      String key = MachineManager.keyFor(level, pos);
      if (nextPulse.getOrDefault(key, Long.MIN_VALUE) > now) {
         return;
      }
      nextPulse.put(key, now + SPACING);
      budgetLeft--;
      spawned++;
      try {
         net.minecraft.util.RandomSource rng = level.getRandom();
         com.fortuneandfavors.net.FfVfx.particles(server, 
            particle,
            pos.getX() + 0.5 + (rng.nextDouble() - 0.5) * 0.6,
            pos.getY() + 1.02,
            pos.getZ() + 0.5 + (rng.nextDouble() - 0.5) * 0.6,
            1,
            0.0,
            0.0,
            0.0,
            0.0
         );
      } catch (Throwable e) {
         FortuneFavorsMod.LOGGER.debug("Machine particle skipped at {}: {}", pos, e.toString());
      }
   }

   /** Forgets the pulse clock of a machine that is gone, so the map does not grow forever. */
   public static void forget(String key) {
      if (key != null) {
         nextPulse.remove(key);
      }
   }

   /** How many machines are currently on the pulse clock - used by the self test. */
   public static int tracked() {
      return nextPulse.size();
   }

   /** Clears the per-tick budget and the counter so a test can pulse a known number of times. */
   public static void resetForTest() {
      budgetTick = Long.MIN_VALUE;
      budgetLeft = MAX_PER_TICK;
      spawned = 0L;
      nextPulse.clear();
   }

   /** How many particles were actually asked for since the last {@link #resetForTest()}. */
   public static long spawnedForTest() {
      return spawned;
   }
}
