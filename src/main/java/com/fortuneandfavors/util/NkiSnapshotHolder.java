package com.fortuneandfavors.util;

import java.util.List;
import java.util.UUID;
import net.minecraft.world.item.ItemStack;

public final class NkiSnapshotHolder {
   private static UUID owner = null;
   private static List<ItemStack> snapshot = null;

   private NkiSnapshotHolder() {
   }

   public static void set(UUID playerId, List<ItemStack> items) {
      owner = playerId;
      snapshot = items;
   }

   public static boolean isFor(UUID playerId) {
      return playerId != null && playerId.equals(owner) && snapshot != null;
   }

   public static List<ItemStack> peek() {
      return snapshot;
   }

   public static List<ItemStack> take(UUID playerId) {
      if (!isFor(playerId)) {
         return null;
      }

      List<ItemStack> s = snapshot;
      snapshot = null;
      owner = null;
      return s;
   }

   public static void clear() {
      snapshot = null;
      owner = null;
   }
}
