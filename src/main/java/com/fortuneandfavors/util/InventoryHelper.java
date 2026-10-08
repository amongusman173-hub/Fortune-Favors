package com.fortuneandfavors.util;

import com.fortuneandfavors.ModItems;
import com.fortuneandfavors.economy.ShopProgression;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public final class InventoryHelper {
   private InventoryHelper() {
   }

   public static int countItems(Player player, Item item) {
      int count = 0;

      for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
         ItemStack stack = player.getInventory().getItem(i);
         if (stack.is(item)) {
            count += stack.getCount();
         }
      }

      return count;
   }

   public static int removeItems(Player player, Item item, int amount) {
      int removed = 0;

      for (int i = 0; i < player.getInventory().getContainerSize() && removed < amount; i++) {
         ItemStack stack = player.getInventory().getItem(i);
         if (stack.is(item)) {
            int take = Math.min(stack.getCount(), amount - removed);
            stack.shrink(take);
            removed += take;
         }
      }

      player.getInventory().setChanged();
      return removed;
   }

   public static int giveOrDrop(Player player, ItemStack stack) {
      if (stack.isEmpty()) {
         return 0;
      }

      int before = stack.getCount();
      boolean added = player.getInventory().add(stack);
      if (player instanceof ServerPlayer sp) {
         Safe.run("giveOrDrop discovery", () -> ShopProgression.trackItemDiscovery(sp, stack));
      }

      if (added) {
         return before - stack.getCount();
      } else if (!stack.isEmpty()) {
         player.drop(stack, false);
         return stack.getCount();
      } else {
         return before;
      }
   }

   public static int countTokens(Player player) {
      int count = 0;

      for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
         ItemStack stack = player.getInventory().getItem(i);
         if (ModItems.isToken(stack)) {
            count += stack.getCount();
         }
      }

      return count;
   }

   public static int removeTokens(Player player, int amount) {
      int removed = 0;

      for (int i = 0; i < player.getInventory().getContainerSize() && removed < amount; i++) {
         ItemStack stack = player.getInventory().getItem(i);
         if (ModItems.isToken(stack)) {
            int take = Math.min(stack.getCount(), amount - removed);
            stack.shrink(take);
            removed += take;
         }
      }

      player.getInventory().setChanged();
      return removed;
   }

   public static List<ItemStack> removeTokenStacks(Player player, int amount) {
      List<ItemStack> removed = new ArrayList<>();
      int remaining = amount;

      for (int i = 0; i < player.getInventory().getContainerSize() && remaining > 0; i++) {
         ItemStack stack = player.getInventory().getItem(i);
         if (ModItems.isToken(stack)) {
            int take = Math.min(stack.getCount(), remaining);
            stack.shrink(take);
            remaining -= take;
            removed.add(stack.copyWithCount(take));
         }
      }

      player.getInventory().setChanged();
      return removed;
   }
}
