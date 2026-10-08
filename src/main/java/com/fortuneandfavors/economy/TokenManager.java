package com.fortuneandfavors.economy;

import com.fortuneandfavors.ModItems;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;

public final class TokenManager {
   public static final long TOKEN_PRICE = 1000L;
   public static final int TOKEN_VERSIONS = 4;

   private TokenManager() {
   }

   public static Item versionItem(int version) {
      return Items.PAPER;
   }

   public static String versionName(int version) {
      return switch (version) {
         case 2 -> "II";
         case 3 -> "III";
         case 4 -> "IV";
         default -> "I";
      };
   }

   /** Give gems to a player (separate currency from favor tokens). */
   public static void giveGems(ServerPlayer player, int amount) {
      EconomyManager.addGems(player.getUUID(), amount);
   }

   /** Check if a player has enough gems. */
   public static boolean hasGems(ServerPlayer player, int amount) {
      return EconomyManager.hasGems(player.getUUID(), amount);
   }

   /** Take gems from a player's balance. */
   public static boolean takeGems(ServerPlayer player, int amount) {
      return EconomyManager.takeGems(player.getUUID(), amount);
   }

   /** Get a player's gem balance. */
   public static long gemBalance(ServerPlayer player) {
      return EconomyManager.gemBalance(player.getUUID());
   }

   // --- Legacy physical token support (for Token Redeemer) ---

   public static ItemStack createToken(ServerPlayer player, int version) {
      return ModItems.createToken(player, version);
   }

   public static boolean isToken(ItemStack stack) {
      return ModItems.isToken(stack);
   }

   public static int versionOf(ItemStack stack) {
      CustomData data = (CustomData)stack.get(DataComponents.CUSTOM_DATA);
      if (data == null) {
         return 1;
      }

      int v = data.copyTag().getInt("version").orElse(1);
      return Math.max(1, Math.min(v, 4));
   }

   public static UUID creatorUuid(ItemStack stack) {
      CustomData data = (CustomData)stack.get(DataComponents.CUSTOM_DATA);
      if (data == null) {
         return null;
      }

      try {
         String s = data.copyTag().getString("creator_uuid").orElse("");
         return s.isEmpty() ? null : UUID.fromString(s);
      } catch (Exception e) {
         return null;
      }
   }

   public static String creatorName(ItemStack stack) {
      CustomData data = (CustomData)stack.get(DataComponents.CUSTOM_DATA);
      return data == null ? "" : data.copyTag().getString("creator_name").orElse("");
   }

   public static String customName(ItemStack stack) {
      CustomData data = (CustomData)stack.get(DataComponents.CUSTOM_DATA);
      return data == null ? "" : data.copyTag().getString("custom_name").orElse("");
   }

   public static void applyCustomName(ItemStack stack, String name) {
      String clean = name == null ? "" : name.trim();
      if (!clean.isEmpty()) {
         CustomData data = (CustomData)stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
         CompoundTag tag = data.copyTag();
         tag.putString("custom_name", clean);
         stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
         String creator = creatorName(stack);
         stack.set(DataComponents.CUSTOM_NAME, Component.literal("§6§l" + clean));
         stack.set(
            DataComponents.LORE,
            new ItemLore(
               List.of(
                  Component.literal("§7" + (creator.isEmpty() ? "someone" : creator) + "'s favor token · special currency"),
                  Component.literal("§6Custom brand: §f" + clean),
                  Component.literal("§8Redeem it at a Token Redeemer - payouts are set by the redeemer's owner"),
                  Component.literal("§8Can't be crafted or replicated - only /token makes these")
               )
            )
         );
      }
   }
}
