package com.fortuneandfavors.util;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.world.item.ItemStack;

public final class ItemOriginals {
   private final Map<Integer, ItemStack> originals = new HashMap<>();

   public void remember(int slot, ItemStack real) {
      if (real != null && !real.isEmpty() && !this.originals.containsKey(slot)) {
         this.originals.put(slot, real.copy());
      }
   }

   public ItemStack take(int slot, ItemStack fallback) {
      ItemStack o = this.originals.remove(slot);
      return o == null ? fallback : o;
   }

   public ItemStack get(int slot, ItemStack fallback) {
      ItemStack o = this.originals.get(slot);
      return o == null ? fallback : o;
   }

   public void forget(int slot) {
      this.originals.remove(slot);
   }
}
