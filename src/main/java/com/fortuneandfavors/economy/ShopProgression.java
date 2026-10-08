package com.fortuneandfavors.economy;

import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

public final class ShopProgression {
   private static final String DISCOVERED_IRON = "shop_disc_iron";
   private static final String DISCOVERED_DIAMOND = "shop_disc_diamond";
   private static final String DISCOVERED_OBSIDIAN = "shop_disc_obsidian";
   private static final String DISCOVERED_LAPIS = "shop_disc_lapis";
   private static final String DISCOVERED_BOOK = "shop_disc_book";

   private ShopProgression() {
   }

   private static void ensureDiscovered(ServerPlayer player, String key) {
      if (!ModConfig.playerPref(player.getUUID(), key)) {
         ModConfig.togglePlayerPref(player.getUUID(), key);
      }
   }

   public static void backfillDiscoveries(ServerPlayer player) {
      try {
         for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            trackItemDiscovery(player, player.getInventory().getItem(i));
         }

         if (player.getEnderChestInventory() != null) {
            for (int i = 0; i < player.getEnderChestInventory().getContainerSize(); i++) {
               trackItemDiscovery(player, player.getEnderChestInventory().getItem(i));
            }
         }
      } catch (Exception var2) {
      }
   }

   public static void trackItemDiscovery(ServerPlayer player, ItemStack stack) {
      if (player != null && stack != null && !stack.isEmpty()) {
         try {
            String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            if (id.contains("diamond") || id.contains("netherite")) {
               ensureDiscovered(player, "shop_disc_diamond");
            }

            if (id.contains("iron_")) {
               ensureDiscovered(player, "shop_disc_iron");
            }

            if (id.equals("minecraft:obsidian") || id.equals("minecraft:crying_obsidian")) {
               ensureDiscovered(player, "shop_disc_obsidian");
            }

            if (id.contains("lapis")) {
               ensureDiscovered(player, "shop_disc_lapis");
            }

            if (id.equals("minecraft:book")
               || id.equals("minecraft:writable_book")
               || id.equals("minecraft:written_book")
               || id.equals("minecraft:enchanted_book")) {
               ensureDiscovered(player, "shop_disc_book");
            }
         } catch (Exception var3) {
         }
      }
   }

   public static void trackOreDiscovery(ServerPlayer player, BlockState state) {
      String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
      if (id.contains("iron_ore") || id.equals("minecraft:raw_iron_block")) {
         ensureDiscovered(player, "shop_disc_iron");
      }

      if (id.contains("diamond_ore") || id.equals("minecraft:raw_diamond_block")) {
         ensureDiscovered(player, "shop_disc_diamond");
      }

      if (id.equals("minecraft:obsidian") || id.equals("minecraft:crying_obsidian")) {
         ensureDiscovered(player, "shop_disc_obsidian");
      }

      if (id.contains("lapis_ore")) {
         ensureDiscovered(player, "shop_disc_lapis");
      }

      if (id.equals("minecraft:bookshelf") || id.equals("minecraft:chiseled_bookshelf")) {
         ensureDiscovered(player, "shop_disc_book");
      }
   }

   public static boolean hasDiscoveredIron(UUID uuid) {
      return ModConfig.playerPref(uuid, "shop_disc_iron");
   }

   public static boolean hasDiscoveredDiamond(UUID uuid) {
      return ModConfig.playerPref(uuid, "shop_disc_diamond");
   }

   public static boolean hasDiscoveredObsidian(UUID uuid) {
      return ModConfig.playerPref(uuid, "shop_disc_obsidian");
   }

   public static boolean hasDiscoveredLapis(UUID uuid) {
      return ModConfig.playerPref(uuid, "shop_disc_lapis");
   }

   public static boolean hasDiscoveredBook(UUID uuid) {
      return ModConfig.playerPref(uuid, "shop_disc_book");
   }

   public static String canBuy(UUID uuid, Item item) {
      String id = BuiltInRegistries.ITEM.getKey(item).toString();
      if (id.equals("minecraft:enchanting_table")) {
         if (!hasDiscoveredObsidian(uuid)) {
            return "&cYou haven't discovered obsidian yet! Mine some obsidian first.";
         }

         if (!hasDiscoveredDiamond(uuid)) {
            return "&cYou haven't discovered diamond yet! Mine some diamond ore first.";
         }

         if (!hasDiscoveredBook(uuid)) {
            return "&cYou haven't discovered books yet! Break a bookshelf first.";
         }

         if (!hasDiscoveredLapis(uuid)) {
            return "&cYou haven't discovered lapis yet! Mine some lapis ore first.";
         }
      }

      if (id.contains("diamond_") && !hasDiscoveredDiamond(uuid)) {
         return "&cYou haven't discovered diamond yet! Mine some diamond ore first.";
      } else if ((id.equals("minecraft:lapis_block") || id.equals("minecraft:lapis_lazuli")) && !hasDiscoveredLapis(uuid)) {
         return "&cYou haven't discovered lapis yet! Mine some lapis ore first.";
      } else {
         return id.contains("iron_") && !id.contains("diamond") && !id.contains("netherite") && !hasDiscoveredIron(uuid)
            ? "&cYou haven't discovered iron yet! Mine some iron ore first."
            : null;
      }
   }
}
