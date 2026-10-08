package com.fortuneandfavors.economy;

import java.util.Random;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public final class MiningPity {
   private static final Random RANDOM = new Random();

   private MiningPity() {
   }

   public static void onBlockBroken(ServerLevel level, ServerPlayer player, BlockPos pos, BlockState state) {
      try {
         // The prison's own economy handles mining there - no pity drops in prison.
         if (level.dimension().equals(PrisonManager.PRISON_DIM)) {
            return;
         }

         // Player-placed blocks never pay. This used to be its own map with its own
         // thirty-minute expiry, which is the same hole the excavator had: wait it
         // out and a block you placed reads as world generation. Placements are one
         // durable ledger now (NaturalBlocks), shared by every perk that asks.
         if (!NaturalBlocks.isNatural(level, pos, state)) {
            return;
         }

         // Creative players break blocks instantly and for free - pity drops
         // would be an unlimited resource faucet. Survival/adventure only.
         if (player != null && player.getAbilities().instabuild) {
            return;
         }

         Item drop = null;
         int chance = 0;
         if (state.is(Blocks.STONE)) {
            drop = switch (RANDOM.nextInt(4)) {
               case 0 -> Items.RAW_IRON;
               case 1 -> Items.RAW_COPPER;
               case 2 -> Items.REDSTONE;
               default -> Items.LAPIS_LAZULI;
            };
            chance = 2;
         } else if (state.is(Blocks.DEEPSLATE)) {
            drop = switch (RANDOM.nextInt(4)) {
               case 0 -> Items.RAW_IRON;
               case 1 -> Items.RAW_GOLD;
               case 2 -> Items.DIAMOND;
               default -> Items.EMERALD;
            };
            chance = 2;
         } else if (state.is(Blocks.TUFF)) {
            drop = Items.RAW_IRON;
            chance = 1;
         } else if (state.is(Blocks.NETHERRACK)) {
            drop = switch (RANDOM.nextInt(3)) {
               case 0 -> Items.GOLD_NUGGET;
               case 1 -> Items.QUARTZ;
               default -> Items.NETHERITE_SCRAP;
            };
            chance = 1;
         }

         // Gold Rush doubles both the strike chance and the size of each find, so
         // the event's "ore blocks drop double items" promise is real and not
         // just a particle show.
         int oreMultiplier = ServerDisasterManager.oreDropMultiplier();
         if (drop == null || RANDOM.nextInt(100) >= chance * oreMultiplier) {
            return;
         }

         int count = 1;
         if (drop == Items.REDSTONE || drop == Items.LAPIS_LAZULI || drop == Items.GOLD_NUGGET || drop == Items.QUARTZ) {
            count = 2 + RANDOM.nextInt(2);
         }
         count *= oreMultiplier;

         ItemStack stack = new ItemStack(drop, count);
         ItemEntity entity = new ItemEntity(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, stack);
         entity.setPickUpDelay(10);
         entity.setDeltaMovement(0.0, 0.12, 0.0);
         level.addFreshEntity(entity);

         // A rarer, special find deserves a small celebration: a puff of dust in
         // the item's own color + a handful of gold sparks + a soft chime. Two
         // compact bursts beat three overlapping showers - easier on the eyes
         // and on TPS.
         double x = pos.getX() + 0.5;
         double y = pos.getY() + 0.6;
         double z = pos.getZ() + 0.5;
         com.fortuneandfavors.net.FfVfx.particles(level, new net.minecraft.core.particles.DustParticleOptions(itemColor(drop), 1.0F), x, y, z, 10, 0.3, 0.15, 0.3, 0.02);
         com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.END_ROD, x, y, z, 8, 0.45, 0.2, 0.45, 0.05);
         level.playSound(null, pos, net.minecraft.sounds.SoundEvents.PLAYER_LEVELUP, net.minecraft.sounds.SoundSource.PLAYERS, 0.5F, 1.4F);
         if (player != null) {
            player.sendSystemMessage(
               net.minecraft.network.chat.Component.literal("§6✦ §7Pity strike! §f" + stack.getHoverName().getString() + " §7x" + count + " §7sparkles out of the stone."),
               true
            );
         }
      } catch (Exception ignored) {
      }
   }

   /** Dust color matching the dropped item, so the pop reads as "that ore" at
    *  a glance instead of a generic sparkle. */
   private static int itemColor(Item item) {
      if (item == Items.RAW_IRON) {
         return 0xD8D8D8;
      }
      if (item == Items.RAW_COPPER) {
         return 0xC07A4A;
      }
      if (item == Items.RAW_GOLD || item == Items.GOLD_NUGGET) {
         return 0xF5D442;
      }
      if (item == Items.DIAMOND) {
         return 0x4EEDE0;
      }
      if (item == Items.EMERALD) {
         return 0x3FCE4E;
      }
      if (item == Items.REDSTONE) {
         return 0xD63D3D;
      }
      if (item == Items.LAPIS_LAZULI) {
         return 0x3F5BD9;
      }
      if (item == Items.QUARTZ) {
         return 0xEAEAEA;
      }
      if (item == Items.NETHERITE_SCRAP) {
         return 0x5A524A;
      }
      return 0xFFE98A;
   }
}
