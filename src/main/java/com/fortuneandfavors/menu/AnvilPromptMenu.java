package com.fortuneandfavors.menu;

import com.fortuneandfavors.util.InventoryHelper;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.HashedStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

public abstract class AnvilPromptMenu extends AnvilMenu {
   protected final ServerPlayer owner;
   protected boolean returnInputOnCancel = true;
   private String typed = "";

   public AnvilPromptMenu(int syncId, Inventory playerInventory, ItemStack inputItem, ItemStack helperItem) {
      super(syncId, playerInventory);
      this.owner = (ServerPlayer)playerInventory.player;
      this.inputSlots.setItem(0, inputItem.copy());
      this.inputSlots.setItem(1, helperItem.copy());
      this.createResult();
   }

   protected final String typedText() {
      return this.typed;
   }

   protected abstract boolean canAccept(String var1);

   protected abstract void renderResult(ItemStack var1, String var2);

   protected abstract void accept(ServerPlayer var1, String var2);

   protected abstract void reopen(ServerPlayer var1);

   public boolean setItemName(String name) {
      boolean result = super.setItemName(name);
      this.typed = name == null ? "" : name.trim();
      this.createResult();
      return result;
   }

   public void createResult() {
      ItemStack input = this.inputSlots.getItem(0);
      if (input.isEmpty()) {
         this.resultSlots.setItem(0, ItemStack.EMPTY);
         this.broadcastChanges();
      } else {
         ItemStack result = input.copy();
         this.renderResult(result, this.typed);
         this.resultSlots.setItem(0, result);
         this.broadcastChanges();
      }
   }

   protected boolean mayPickup(Player player, boolean hasInfiniteMaterials) {
      return !this.inputSlots.getItem(0).isEmpty() && this.canAccept(this.typed);
   }

   protected void onTake(Player player, ItemStack stack) {
      if (this.canAccept(this.typed)) {
         this.accept(this.owner, this.typed);
      }

      this.inputSlots.setItem(0, ItemStack.EMPTY);
      this.inputSlots.setItem(1, ItemStack.EMPTY);
      this.setCarried(ItemStack.EMPTY);
      this.setRemoteCarried(HashedStack.EMPTY);
      this.owner.closeContainer();
      this.reopen(this.owner);
   }

   public void removed(Player player) {
      ItemStack stack = this.inputSlots.getItem(0);
      ItemStack carried = this.getCarried();
      this.inputSlots.setItem(0, ItemStack.EMPTY);
      this.inputSlots.setItem(1, ItemStack.EMPTY);
      this.setCarried(ItemStack.EMPTY);
      this.setRemoteCarried(HashedStack.EMPTY);
      super.removed(player);
      if (this.returnInputOnCancel && player instanceof ServerPlayer sp) {
         if (!stack.isEmpty()) {
            InventoryHelper.giveOrDrop(sp, stack);
         } else if (!carried.isEmpty()) {
            InventoryHelper.giveOrDrop(sp, carried);
         }
      }
   }

   public ItemStack quickMoveStack(Player player, int index) {
      return ItemStack.EMPTY;
   }

   protected static ItemStack helper(String title, String... lines) {
      ItemStack stack = new ItemStack(Items.PAPER);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal(title));
      stack.set(DataComponents.LORE, new ItemLore(List.of(Component.literal(String.join("\n", lines)))));
      return stack;
   }
}
