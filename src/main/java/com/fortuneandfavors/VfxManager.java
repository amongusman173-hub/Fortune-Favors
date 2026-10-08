package com.fortuneandfavors;

import com.fortuneandfavors.economy.ChestShopManager;
import com.fortuneandfavors.economy.ClaimManager;
import com.fortuneandfavors.economy.MachineManager;
import com.fortuneandfavors.economy.ModConfig;
import com.fortuneandfavors.economy.SpawnerManager;
import com.fortuneandfavors.economy.ChestShopManager.ChestShop;
import com.fortuneandfavors.economy.MachineManager.Machine;
import com.fortuneandfavors.economy.SpawnerManager.SpawnerRec;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.Map.Entry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;

public final class VfxManager {
   private static final int SELECTION_INTERVAL = 10;
   private static final int SHOP_INTERVAL = 20;
   private static final int CLAIM_INTERVAL = 40;
   private static final int REDEEMER_INTERVAL = 20;
   private static final int SPAWNER_INTERVAL = 30;
   private static final int HELD_ITEM_INTERVAL = 3;
   private static final int SHOP_RANGE = 48;
   private static final int REDEEMER_RANGE = 48;
   private static final int SPAWNER_RANGE = 40;
   private static final int MAX_SHOPS_PER_PASS = 60;
   private static final int MAX_SPAWNERS_PER_PASS = 4;
   private static final int CELEBRATION_TICKS = 45;
   private static final Map<UUID, Integer> celebrations = new HashMap<>();
   private static final java.util.Random RANDOM = new java.util.Random();
   private static final Map<UUID, Integer> lastMemoryDurability = new HashMap<>();
   private static int tick = 0;

   private VfxManager() {
   }

   /** Trigger a golden particle celebration around a player (job claims, milestones). */
   public static void celebrate(ServerPlayer player) {
      celebrations.put(player.getUUID(), CELEBRATION_TICKS);
   }

   /** A colourful multicolor firework-style burst at a position - cheap, one send. */
   public static void fireworkBurst(ServerLevel level, double x, double y, double z, int rings) {
      for (int i = 0; i < rings; i++) {
         double a = (double)i / rings * Math.PI * 2.0;
         int color = java.awt.Color.HSBtoRGB((float)i / rings, 0.9F, 1.0F) & 16777215;
         com.fortuneandfavors.net.FfVfx.particles(level, 
            new net.minecraft.core.particles.DustParticleOptions(color, 1.0F),
            x + Math.cos(a) * 1.8,
            y + 0.25 + Math.sin(a * 2.0) * 0.4,
            z + Math.sin(a) * 1.8,
            1,
            0.05,
            0.05,
            0.05,
            0.0
         );
         com.fortuneandfavors.net.FfVfx.particles(level, 
            new net.minecraft.core.particles.DustParticleOptions(color, 0.8F),
            x + Math.cos(a) * 2.6,
            y + 0.4 + Math.sin(a * 3.0) * 0.5,
            z + Math.sin(a) * 2.6,
            1,
            0.03,
            0.03,
            0.03,
            0.0
         );
      }

      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.END_ROD, x, y + 0.3, z, 14, 0.5, 0.7, 0.5, 0.05);
      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.FIREWORK, x, y + 0.5, z, 8, 0.4, 0.6, 0.4, 0.08);
   }

   public static void tick(MinecraftServer server) {
      tick++;
      // Every effect keeps its natural cadence, but the phases are offset so
      // shops, machines, spawners, claims and held-item effects never all fire
      // in the same tick - spreads the per-tick load and avoids TPS hitches.
      if (tick % 10 == 0) {
         ClaimManager.tickEnterExit(server);
      }

      tickCelebrations(server);

      if (tick % 40 == 0) {
         ClaimManager.tickBorders(server);
      }

      // Shops: every 20 ticks (1s).
      if (tick % 20 == 0 && ModConfig.is("exclusive")) {
         shopVfx(server);
      }

      // Machines: every 20 ticks, offset 4 from shops so they never collide.
      if (tick % 20 == 4 && (ModConfig.is("token") || ModConfig.is("elevator") || ModConfig.is("exclusive"))) {
         machineVfx(server);
      }

      // Spawners: every 30 ticks, offset so they don't collide with the 20s.
      if (tick % 30 == 7) {
         spawnerVfx(server);
      }

      // Held legendary items: every 3 ticks (cheap), offset off the heavy ones.
      if (tick % 3 == 2) {
         heldItemVfx(server);
      }
   }

   private static void tickCelebrations(MinecraftServer server) {
      Iterator<Entry<UUID, Integer>> it = celebrations.entrySet().iterator();
      while (it.hasNext()) {
         Entry<UUID, Integer> e = it.next();
         ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
         if (p == null || !p.isAlive()) {
            it.remove();
            continue;
         }
         int left = e.getValue() - 1;
         if (left <= 0) {
            it.remove();
            continue;
         }
         e.setValue(left);
         ServerLevel level = p.level();
         double x = p.getX();
         double y = p.getY() + 1.0;
         double z = p.getZ();
         double a = left * 0.5;
         // Golden sparkle orbit.
         com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.END_ROD, x + Math.cos(a) * 0.9, y + 0.4 + Math.sin(a * 0.7) * 0.25, z + Math.sin(a) * 0.9, 2, 0.1, 0.1, 0.1, 0.02);
         com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.HAPPY_VILLAGER, x, y + 0.2, z, 1, 0.4, 0.3, 0.4, 0.0);
         // Expanding shockwave ring on the first ticks.
         if (left > CELEBRATION_TICKS - 10) {
            double r = (CELEBRATION_TICKS - left) * 0.35 + 0.5;
            for (int i = 0; i < 12; i++) {
               double ang = i / 12.0 * Math.PI * 2.0;
               com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.END_ROD, x + Math.cos(ang) * r, y + 0.2, z + Math.sin(ang) * r, 1, 0.02, 0.3, 0.02, 0.0);
            }
         }
         if (left % 6 == 0) {
            com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.NOTE, x, y + 1.3, z, 4, 0.35, 0.35, 0.35, 1.0);
            com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.ENCHANT, x, y + 1.5, z, 8, 0.5, 0.6, 0.5, 0.08);
            com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.FIREWORK, x, y + 1.2, z, 1, 0.4, 0.5, 0.4, 0.05);
         }
         if (left == CELEBRATION_TICKS / 2) {
            // Mid-burst: golden rain.
            for (int i = 0; i < 20; i++) {
               com.fortuneandfavors.net.FfVfx.particles(level, 
                  ParticleTypes.END_ROD, x + (RANDOM.nextDouble() - 0.5) * 2.2, y + 1.2 + RANDOM.nextDouble() * 1.2, z + (RANDOM.nextDouble() - 0.5) * 2.2, 1, 0.0, -0.06, 0.0, 0.02
               );
            }
         }
      }
   }

   private static void machineVfx(MinecraftServer server) {
      if (!MachineManager.all().isEmpty()) {
         for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ServerLevel level = player.level();
            String dim = level.dimension().identifier().toString();

            for (Entry<String, Machine> e : MachineManager.all().entrySet()) {
               String type = e.getValue().type();
               boolean redeemer = "token_redeemer".equals(type);
               boolean elevator = "elevator".equals(type);
               boolean infuser = "spawner_infuser".equals(type);
               boolean forge = "item_forge".equals(type);
               if ((redeemer || elevator || infuser || forge)
                  && (!redeemer || ModConfig.is("token"))
                  && (!elevator || ModConfig.is("elevator"))
                  && (!infuser || ModConfig.is("exclusive"))
                  && (!forge || ModConfig.is("boss"))) {
                  BlockPos pos = parseKey(e.getKey());
                  if (pos != null && e.getKey().startsWith(dim + ":")) {
                     int dx = Math.abs(player.getBlockX() - pos.getX());
                     int dz = Math.abs(player.getBlockZ() - pos.getZ());
                     if (dx <= 48 && dz <= 48 && level.isLoaded(pos)) {
                        if (forge) {
                           com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.SMALL_FLAME, pos.getX() + 0.5, pos.getY() + 1.1, pos.getZ() + 0.5, 2, 0.25, 0.15, 0.25, 0.008);
                           com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.SMOKE, pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, 1, 0.15, 0.2, 0.15, 0.005);
                        } else {
                           com.fortuneandfavors.net.FfVfx.particles(level, 
                              redeemer ? ParticleTypes.END_ROD : (infuser ? ParticleTypes.ENCHANT : ParticleTypes.ELECTRIC_SPARK),
                              pos.getX() + 0.5,
                              pos.getY() + 1.15,
                              pos.getZ() + 0.5,
                              infuser ? 3 : 1,
                              0.2,
                              0.25,
                              0.2,
                              0.04
                           );
                        }
                     }
                  }
               }
            }
         }
      }
   }

   private static void spawnerVfx(MinecraftServer server) {
      Map<String, SpawnerRec> all = SpawnerManager.all();
      if (!all.isEmpty()) {
         for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ServerLevel level = player.level();
            String dim = level.dimension().identifier().toString();
            int drawn = 0;

            for (Entry<String, SpawnerRec> e : all.entrySet()) {
               if (drawn >= 4) {
                  break;
               }

               BlockPos pos = parseKey(e.getKey());
               if (pos != null && e.getKey().startsWith(dim + ":")) {
                  int dx = Math.abs(player.getBlockX() - pos.getX());
                  int dz = Math.abs(player.getBlockZ() - pos.getZ());
                  if (dx <= 40 && dz <= 40 && level.isLoaded(pos) && level.getBlockState(pos).is(Blocks.SPAWNER)) {
                     drawn++;
                     int levelNo = Math.max(1, e.getValue().level());
                     if (levelNo > 1) {
                        com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.PORTAL, pos.getX() + 0.5, pos.getY() + 0.6, pos.getZ() + 0.5, 3, 0.3, 0.3, 0.3, 0.02);
                        com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.ENCHANT, pos.getX() + 0.5, pos.getY() + 1.2, pos.getZ() + 0.5, 2, 0.25, 0.25, 0.25, 0.05);
                     } else {
                        com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.END_ROD, pos.getX() + 0.5, pos.getY() + 0.8, pos.getZ() + 0.5, 1, 0.2, 0.3, 0.2, 0.03);
                     }
                  }
               }
            }
         }
      }
   }

   private static void shopVfx(MinecraftServer server) {
      Map<String, ChestShop> shops = ChestShopManager.allShops();
      if (!shops.isEmpty()) {
         int pass = shops.size() > 60 ? shops.size() / 60 : 1;
         int i = 0;

         for (Entry<String, ChestShop> e : shops.entrySet()) {
            if (i++ % pass == 0) {
               BlockPos pos = parseKey(e.getKey());
               if (pos != null) {
                  String dim = dimensionOf(e.getKey());
                  boolean closed = !ChestShopManager.isTrading(e.getValue());
                  boolean buy = "buy".equals(e.getValue().type);
                  boolean anyNear = false;

                  for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                     if (player.level().dimension().identifier().toString().equals(dim)
                        && player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) < 2304.0) {
                        anyNear = true;
                        break;
                     }
                  }

                  if (anyNear && !closed) {
                     ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(dim)));
                     if (level != null) {
                        com.fortuneandfavors.net.FfVfx.particles(level, 
                           buy ? ParticleTypes.HAPPY_VILLAGER : ParticleTypes.FLAME,
                           pos.getX() + 0.5,
                           pos.getY() + 1.1,
                           pos.getZ() + 0.5,
                           buy ? 3 : 1,
                           0.25,
                           0.25,
                           0.25,
                           0.0
                        );
                     }
                  }
               }
            }
         }
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
      } catch (NumberFormatException e) {
         return null;
      }
   }

   private static void heldItemVfx(MinecraftServer server) {
      for (ServerPlayer player : server.getPlayerList().getPlayers()) {
         if (player.level() instanceof ServerLevel sl) {
            ItemStack main = player.getMainHandItem();
            ItemStack off = player.getOffhandItem();
            if (ModItems.isDistantMemoryShard(main)) {
               com.fortuneandfavors.net.FfVfx.particles(sl, ParticleTypes.PORTAL, particleX(player, 0.0), player.getY() + 0.9, particleZ(player, 0.0), 1, 0.12, 0.15, 0.12, 0.015);
               com.fortuneandfavors.net.FfVfx.particles(sl, ParticleTypes.ENCHANT, particleX(player, 0.0), player.getY() + 1.1, particleZ(player, 0.0), 2, 0.18, 0.22, 0.18, 0.04);
               com.fortuneandfavors.net.FfVfx.particles(sl, ParticleTypes.END_ROD, particleX(player, 0.0), player.getY() + 0.8, particleZ(player, 0.0), 1, 0.08, 0.1, 0.08, 0.02);
            }

            if (ModItems.isDistantMemorySword(main)) {
               com.fortuneandfavors.net.FfVfx.particles(sl, ParticleTypes.ELECTRIC_SPARK, particleX(player, 0.0), player.getY() + 0.85, particleZ(player, 0.0), 2, 0.15, 0.12, 0.15, 0.025);
               com.fortuneandfavors.net.FfVfx.particles(sl, ParticleTypes.PORTAL, particleX(player, 0.0), player.getY() + 1.0, particleZ(player, 0.0), 1, 0.1, 0.15, 0.1, 0.01);
               // Only rewrite the lore when durability actually changed - the lore
               // embeds current durability, and rebroadcasting the whole slot
               // every few ticks is needless client churn.
               int dmg = main.getDamageValue();
               Integer last = lastMemoryDurability.get(player.getUUID());
               if (last == null || last != dmg) {
                  lastMemoryDurability.put(player.getUUID(), dmg);
                  ModItems.refreshDistantMemoryLore(main);
               }
            } else if (lastMemoryDurability.remove(player.getUUID()) != null) {
               // Sword no longer held - drop the cached value.
            }
         }
      }
   }

   private static double particleX(ServerPlayer p, double offset) {
      return p.getX() + Math.cos(Math.toRadians(-p.getYRot())) * 0.55;
   }

   private static double particleZ(ServerPlayer p, double offset) {
      return p.getZ() + Math.sin(Math.toRadians(-p.getYRot())) * 0.55;
   }
}
