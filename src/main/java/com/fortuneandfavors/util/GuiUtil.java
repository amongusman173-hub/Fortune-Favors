package com.fortuneandfavors.util;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public final class GuiUtil {
   private GuiUtil() {
   }

   /** Fills the border slots of a 9-wide container with blank glass panes. Call before placing content. */
   public static void frames(SimpleContainer container, int rows, Item glass) {
      ItemStack pane = new ItemStack(glass);
      pane.set(DataComponents.CUSTOM_NAME, Component.literal(" "));
      int size = container.getContainerSize();
      for (int i = 0; i < size; i++) {
         int row = i / 9;
         int col = i % 9;
         if (row == 0 || row == rows - 1 || col == 0 || col == 8) {
            container.setItem(i, pane.copy());
         }
      }
   }

   /** Fills the four corners of a 9-wide container. */
   public static void corners(SimpleContainer container, int rows, Item glass) {
      ItemStack pane = new ItemStack(glass);
      pane.set(DataComponents.CUSTOM_NAME, Component.literal(" "));
      int size = container.getContainerSize();
      for (int i = 0; i < size; i++) {
         int row = i / 9;
         int col = i % 9;
         if ((row == 0 || row == rows - 1) && (col == 0 || col == 8)) {
            container.setItem(i, pane.copy());
         }
      }
   }
}
