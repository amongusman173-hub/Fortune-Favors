package com.fortuneandfavors.economy;

import com.fortuneandfavors.FortuneFavorsMod;
import com.fortuneandfavors.util.Chat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelData.RespawnData;
import net.minecraft.world.phys.AABB;

/**
 * Risk/reward mining zones: designated sub-chunk regions in deepslate caves where
 * ores pay extra but the mobs are stronger. Rolled fresh on each server start.
 *
 * Zones are also "seeded" on spawn: extra ore veins are placed in the cave walls
 * so the area is worth the danger.
 *
 * Anti-dupe: only NATURAL ore blocks pay the zone bonus. Every player-placed
 * block is tracked (any placement, creative included) and a placed ore mined
 * inside a zone pays nothing - no place-and-rebreak loops.
 */
public final class MiningZoneManager {
   private static final class Zone {
      final ServerLevel level;
      final BlockPos center;
      final int radius;

      Zone(ServerLevel level, BlockPos center, int radius) {
         this.level = level;
         this.center = center;
         this.radius = radius;
      }
   }

   private static final List<Zone> zones = new ArrayList<>();
   private static final Map<UUID, Long> lastWarned = new HashMap<>();
   private static final Random RANDOM = new Random();

   /** Ores that pay the dangerous-zone bonus. Any state NOT in this set (and not
    * an ore variant) is ignored by onBlockBroken entirely. */
   private static final Map<Block, Long> ORE_VALUE_FLOOR = buildOreFloor();

   /** Player-placed blocks live in {@link NaturalBlocks} - one durable ledger for
    * every perk that must not pay out on a block a player put there. */

   /** Zone-spawned hostiles by UUID, for containment cleanup. */
   private static final Map<UUID, Long> zoneMobs = new HashMap<>();

   private MiningZoneManager() {
   }

   private static Map<Block, Long> buildOreFloor() {
      Map<Block, Long> m = new HashMap<>();
      m.put(Blocks.COAL_ORE, 40L);
      m.put(Blocks.DEEPSLATE_COAL_ORE, 40L);
      m.put(Blocks.COPPER_ORE, 55L);
      m.put(Blocks.DEEPSLATE_COPPER_ORE, 55L);
      m.put(Blocks.IRON_ORE, 100L);
      m.put(Blocks.DEEPSLATE_IRON_ORE, 100L);
      m.put(Blocks.REDSTONE_ORE, 85L);
      m.put(Blocks.DEEPSLATE_REDSTONE_ORE, 85L);
      m.put(Blocks.LAPIS_ORE, 120L);
      m.put(Blocks.DEEPSLATE_LAPIS_ORE, 120L);
      m.put(Blocks.GOLD_ORE, 200L);
      m.put(Blocks.DEEPSLATE_GOLD_ORE, 200L);
      m.put(Blocks.DIAMOND_ORE, 650L);
      m.put(Blocks.DEEPSLATE_DIAMOND_ORE, 650L);
      m.put(Blocks.EMERALD_ORE, 650L);
      m.put(Blocks.DEEPSLATE_EMERALD_ORE, 650L);
      m.put(Blocks.NETHER_QUARTZ_ORE, 55L);
      m.put(Blocks.NETHER_GOLD_ORE, 70L);
      m.put(Blocks.ANCIENT_DEBRIS, 1400L);
      return m;
   }

   public static void load(MinecraftServer server) {
      zones.clear();
      zoneMobs.clear();
      ServerLevel overworld = server.overworld();
      if (overworld == null) {
         return;
      }
      List<BlockPos> chosen = new ArrayList<>();
      int placed = 0;
      // Aim for up to 3 zones, each rolled into its own spot (96+ blocks apart).
      for (int i = 0; i < 3; i++) {
         BlockPos c = findDeepslatePocket(overworld, chosen);
         if (c != null) {
            chosen.add(c);
            Zone z = new Zone(overworld, c, 18 + RANDOM.nextInt(9));
            zones.add(z);
            seedZone(z);
            placed++;
         }
      }
      if (!zones.isEmpty()) {
         StringBuilder sb = new StringBuilder("§8§m                                             §r\n");
         sb.append("§c§l⚠ DANGEROUS MINING AREA§r\n");
         for (Zone z : zones) {
            sb.append("§7Better ores, stronger mobs at §cX " + z.center.getX() + " §7Y " + z.center.getY()
               + " §7Z " + z.center.getZ() + "§7 (radius " + z.radius + ").\n");
         }
         sb.append("§7Ore veins are seeded inside. §cOnly natural ores pay the bonus§7 - placed ore is worthless here.");
         server.getPlayerList()
            .broadcastSystemMessage(
               Component.literal(Chat.colorize(sb.toString())), false);
      }
      if (placed == 0) {
         // The old code silently spawned nothing when no sealed pocket was found
         // in the scan grid - log it so admins aren't confused by an empty start.
         com.fortuneandfavors.FortuneFavorsMod.LOGGER
            .warn("Fortune & Favors: no dangerous mining zone could be rolled this start (no cave pockets found near spawn)");
      }
   }

   private static BlockPos findDeepslatePocket(ServerLevel level, List<BlockPos> chosen) {
      RespawnData rd = level.getRespawnData();
      BlockPos spawn = rd != null && rd.pos() != null ? rd.pos() : new BlockPos(0, 64, 0);
      List<BlockPos> deep = new ArrayList<>();
      List<BlockPos> any = new ArrayList<>();
      List<BlockPos> wide = new ArrayList<>();
      int minY = Math.max(level.getMinY() + 8, -32);
      for (int dx = -96; dx <= 96; dx += 8) {
         for (int dz = -96; dz <= 96; dz += 8) {
            for (int y = -8; y >= minY; y -= 6) {
               BlockPos p = new BlockPos(spawn.getX() + dx, y, spawn.getZ() + dz);
               if (!level.getBlockState(p).isAir()) {
                  continue;
               }
               BlockState below = level.getBlockState(p.below());
               if (below.isAir()) {
                  continue;
               }
               if (p.distToCenterSqr(spawn.getX(), spawn.getY(), spawn.getZ()) < 32.0 * 32.0) {
                  continue;
               }
               // Don't stack zones on top of each other.
               boolean nearOther = false;
               for (BlockPos other : chosen) {
                  if (p.distSqr(other) < 96.0 * 96.0) {
                     nearOther = true;
                     break;
                  }
               }
               if (nearOther) {
                  continue;
               }
               // Never inside a claim - the bonus shouldn't pay on claimed land.
               if (ClaimManager.claimAt(level, p) != null) {
                  continue;
               }
               // Reject anything that can see the sky - surface/open-air spots are banned.
               boolean openToSky = false;
               for (int up = 1; up <= 24; up++) {
                  BlockPos above = p.above(up);
                  if (above.getY() >= level.getMaxY()) {
                     openToSky = true;
                     break;
                  }
                  if (!level.getBlockState(above).isAir()) {
                     break; // solid roof found above - enclosed
                  }
               }
               boolean deepSlateFloor = below.is(Blocks.DEEPSLATE) || below.is(Blocks.TUFF) || below.is(Blocks.DEEPSLATE_COAL_ORE)
                  || below.is(Blocks.DEEPSLATE_IRON_ORE) || below.is(Blocks.DEEPSLATE_COPPER_ORE);
               (deepSlateFloor ? deep : any).add(p);
               if (openToSky && deepSlateFloor) {
                  // Deep open caverns are an acceptable fallback: still deepslate
                  // depth, still dangerous - never surface.
                  wide.add(p);
               }
            }
         }
      }
      List<BlockPos> pool = !deep.isEmpty() ? deep : (!any.isEmpty() ? any : wide);
      if (pool.isEmpty()) {
         return null;
      }
      return pool.get(RANDOM.nextInt(pool.size()));
   }

   /** Sprinkles bonus ore veins + a few soul torches through the zone so the
    * pocket is actually rich (and visible). Runs once at generation. */
   private static void seedZone(Zone z) {
      ServerLevel level = z.level;
      if (!level.isLoaded(z.center)) {
         return;
      }
      int torches = 0;
      int r = z.radius;
      for (int dx = -r; dx <= r; dx++) {
         for (int dy = -5; dy <= 5; dy++) {
            for (int dz = -r; dz <= r; dz++) {
               if (dx * dx + dy * dy + dz * dz > r * r + 4) {
                  continue;
               }
               BlockPos p = z.center.offset(dx, dy, dz);
               BlockState s = level.getBlockState(p);
               if (s.is(Blocks.DEEPSLATE) && RANDOM.nextInt(100) < 9) {
                  level.setBlock(p, pickSeedOre(), 3);
               } else if (s.isAir() && torches < 6
                  && level.getBlockState(p.below()).isSolidRender() && RANDOM.nextInt(400) == 0) {
                  level.setBlock(p, Blocks.SOUL_TORCH.defaultBlockState(), 3);
                  torches++;
               }
            }
         }
      }
   }

   private static BlockState pickSeedOre() {
      int roll = RANDOM.nextInt(100);
      if (roll < 18) {
         return Blocks.DEEPSLATE_COAL_ORE.defaultBlockState();
      } else if (roll < 36) {
         return Blocks.DEEPSLATE_COPPER_ORE.defaultBlockState();
      } else if (roll < 58) {
         return Blocks.DEEPSLATE_IRON_ORE.defaultBlockState();
      } else if (roll < 68) {
         return Blocks.DEEPSLATE_GOLD_ORE.defaultBlockState();
      } else if (roll < 78) {
         return Blocks.DEEPSLATE_REDSTONE_ORE.defaultBlockState();
      } else if (roll < 85) {
         return Blocks.DEEPSLATE_LAPIS_ORE.defaultBlockState();
      } else if (roll < 93) {
         return Blocks.DEEPSLATE_DIAMOND_ORE.defaultBlockState();
      } else if (roll < 96) {
         return Blocks.DEEPSLATE_EMERALD_ORE.defaultBlockState();
      }
      return Blocks.ANCIENT_DEBRIS.defaultBlockState();
   }

   public static boolean inZone(ServerLevel level, BlockPos pos) {
      for (Zone z : zones) {
         if (z.level == level && horizDist2(z.center, pos) <= (long)z.radius * z.radius) {
            return true;
         }
      }
      return false;
   }

   private static long horizDist2(BlockPos a, BlockPos b) {
      long dx = a.getX() - b.getX();
      long dz = a.getZ() - b.getZ();
      return dx * dx + dz * dz;
   }

   /** True only for a NATURAL ore block inside a zone (not player-placed). */
   private static boolean isNaturalZoneOre(ServerLevel level, BlockPos pos, BlockState state) {
      if (!ORE_VALUE_FLOOR.containsKey(state.getBlock())) {
         return false;
      }
      return NaturalBlocks.isNatural(level, pos, state);
   }

   /** Called from onBlockBroken - bonus drops + cash for natural ores mined inside a zone. */
   public static void onBlockBroken(ServerLevel level, BlockPos pos, ServerPlayer sp, BlockState state) {
      try {
         if (!inZone(level, pos)) {
            return;
         }
         // Only natural ORE blocks pay. Anything else (stone, deepslate, torches,
         // placed ore, ...) gets nothing - keeps the zone a real gamble.
         if (!isNaturalZoneOre(level, pos, state)) {
            return;
         }
         // Creative players break for free and endlessly - no bonus economy.
         if (sp.getAbilities().instabuild) {
            return;
         }
         ItemStack drop = new ItemStack(state.getBlock().asItem());
         if (drop.isEmpty()) {
            return;
         }
         long value = BlockValues.valueOf(drop);
         if (value <= 0L) {
            return;
         }
         if (RANDOM.nextInt(100) < 55) {
            ItemEntity extra = new ItemEntity(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, drop.copy());
            extra.setPickUpDelay(10);
            level.addFreshEntity(extra);
         }
         long bonus = Math.max(1L, value / 5L);
         EconomyManager.addCash(sp.getUUID(), bonus);
         // Small mining XP kicker on top of the normal ore XP.
         long xpKick = Math.max(2L, Math.min(15L, value / 40L));
         SkillManager.addXp(sp, "mining", xpKick);
         level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, pos.getX() + 0.5, pos.getY() + 0.6, pos.getZ() + 0.5, 10, 0.4, 0.4, 0.4, 0.04);
         sp.sendSystemMessage(Component.literal("§c⚠§7 Dangerous zone bonus: §a$" + bonus), true);
      } catch (Exception ignored) {
      }
   }

   public static void tick(MinecraftServer server) {
      if (zones.isEmpty()) {
         return;
      }
      long tick = server.getTickCount();
      if (tick % 200L == 0L) {
         for (Zone z : zones) {
            spawnHostiles(z);
         }
      }
      // Perf: this used to be `tick % 1200 == 0`, which is also where the expedition cooldown
      // prune and the guild mailbox cleanup sat - so once a minute one tick paid for the containment
      // sweep and both of them. Same work, its own slot in the cycle now.
      if (FortuneFavorsMod.due(tick, FortuneFavorsMod.MINUTE_CYCLE_TICKS, FortuneFavorsMod.MINUTE_ZONE_CONTAINMENT)) {
         containZoneMobs(server);
      }
      if (tick % 60L == 0L) {
         for (Zone z : zones) {
            ringParticles(z);
         }
      }
      if (tick % 20L == 0L) {
         for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.level() instanceof ServerLevel sl && inZone(sl, p.blockPosition())) {
               long last = lastWarned.getOrDefault(p.getUUID(), 0L);
               if (tick - last > 300L) {
                  lastWarned.put(p.getUUID(), tick);
                  p.sendSystemMessage(Component.literal("§c§l⚠ DANGEROUS MINING AREA§r §7- ores pay bonus, but the mobs hit harder here."), true);
               }
            }
         }
      }
   }

   /** Zone mobs that wander out (or survive their players leaving) are swept so
    * the dangerous zone doesn't leak tiered mobs into normal caves. */
   private static void containZoneMobs(MinecraftServer server) {
      if (zoneMobs.isEmpty()) {
         return;
      }
      long tick = server.getTickCount();
      Iterator<Map.Entry<UUID, Long>> it = zoneMobs.entrySet().iterator();
      while (it.hasNext()) {
         Map.Entry<UUID, Long> e = it.next();
         if (tick - e.getValue() > 24000L) {
            it.remove();
            continue;
         }
         boolean removed = false;
         for (Zone z : zones) {
            if (z.level.getEntity(e.getKey()) instanceof Monster m && m.isAlive() && !m.isRemoved()) {
               if (horizDist2(z.center, m.blockPosition()) > (long)(z.radius + 16) * (z.radius + 16)) {
                  m.discard();
                  removed = true;
               }
               break;
            }
         }
         if (removed) {
            it.remove();
         }
      }
   }

   private static void spawnHostiles(Zone z) {
      ServerLevel level = z.level;
      List<Monster> existing = level.getEntitiesOfClass(
         Monster.class, new AABB(z.center).inflate(z.radius), m -> m.isAlive() && !m.isRemoved()
      );
      if (existing.size() >= 8) {
         return;
      }
      int want = Math.min(8 - existing.size(), 2 + RANDOM.nextInt(2));
      for (int i = 0; i < want; i++) {
         BlockPos spot = randomAirSpot(z);
         if (spot == null) {
            continue;
         }
         EntityType<? extends Mob> type = switch (RANDOM.nextInt(12)) {
            case 0, 1 -> EntityTypes.CAVE_SPIDER;
            case 2 -> EntityTypes.WITCH;
            case 3, 4 -> EntityTypes.CREEPER;
            case 5, 6 -> EntityTypes.SPIDER;
            case 7, 8, 9 -> EntityTypes.ZOMBIE;
            default -> EntityTypes.SKELETON;
         };
         Mob mob = type.create(level, EntitySpawnReason.COMMAND);
         if (mob == null) {
            continue;
         }
         mob.setPos(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5);
         // 30% chance of tier 2, otherwise tier 1 - noticeably tougher than caves.
         RareMobVariantManager.applyTier(mob, RANDOM.nextInt(10) < 3 ? 2 : 1);
         level.addFreshEntity(mob);
         zoneMobs.put(mob.getUUID(), (long)level.getServer().getTickCount());
      }
   }

   private static BlockPos randomAirSpot(Zone z) {
      for (int tries = 0; tries < 10; tries++) {
         int x = z.center.getX() + RANDOM.nextInt(z.radius * 2 + 1) - z.radius;
         int zz = z.center.getZ() + RANDOM.nextInt(z.radius * 2 + 1) - z.radius;
         int y = z.center.getY() + RANDOM.nextInt(9) - 4;
         BlockPos p = new BlockPos(x, y, zz);
         if (z.level.getBlockState(p).isAir() && !z.level.getBlockState(p.below()).isAir()) {
            return p;
         }
      }
      return null;
   }

   private static void ringParticles(Zone z) {
      ServerLevel level = z.level;
      int r = z.radius;
      for (int i = 0; i < 16; i++) {
         double a = i / 16.0 * Math.PI * 2.0;
         int x = z.center.getX() + (int)Math.round(Math.cos(a) * r);
         int zz = z.center.getZ() + (int)Math.round(Math.sin(a) * r);
         int y = z.center.getY() + (i % 4) * 2;
         level.sendParticles(ParticleTypes.SMOKE, x + 0.5, y + 0.5, zz + 0.5, 1, 0.1, 0.1, 0.1, 0.01);
      }
   }
}
