package com.fortuneandfavors.menu;

import com.fortuneandfavors.ModCommands;
import com.fortuneandfavors.ModSounds;
import com.fortuneandfavors.util.InventoryHelper;
import com.fortuneandfavors.util.SoundUtil;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.HashedStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.ItemLike;

public class ItemListMenu extends ChestMenu {
   private static final int ITEM_SLOTS = 45;
   private static final int PAGE_PREV = 45;
   private static final int PAGE_NEXT = 46;
   private static final int INFO = 47;
   private static final int CLOSE = 53;
   private final SimpleContainer container;
   private final ServerPlayer owner;
   private int page;
   private final int pageCount;

   private ItemListMenu(int syncId, Inventory playerInventory, int page, SimpleContainer container) {
      super(MenuType.GENERIC_9x6, syncId, playerInventory, container, 6);
      this.container = container;
      this.owner = (ServerPlayer)playerInventory.player;
      this.page = Math.max(0, page);
      int count = ModCommands.allModItems(this.owner.level().registryAccess()).length;
      this.pageCount = Math.max(1, (int)Math.ceil(count / 45.0));
      if (this.page >= this.pageCount) {
         this.page = this.pageCount - 1;
      }

      this.rebuild();
   }

   public static void open(ServerPlayer player) {
      player.openMenu(
         new SimpleMenuProvider(
            (syncId, inv, p) -> new ItemListMenu(syncId, inv, 0, new SimpleContainer(54)), Component.literal("§b§lFortune & Favors - every item")
         )
      );
   }

   private void rebuild() {
      ItemStack[] items = ModCommands.allModItems(this.owner.level().registryAccess());
      int start = this.page * 45;

      for (int i = 0; i < 45; i++) {
         int idx = start + i;
         if (idx < items.length) {
            try {
               ItemStack s = items[idx].copy();
               String alias = ModCommands.aliasFor(items[idx]);
               if (alias == null || alias.isEmpty()) {
                  alias = "?";
               }
               s.set(
                  DataComponents.LORE,
                  new ItemLore(List.of(Component.literal("§7Click to take a copy"), Component.literal("§8Alias: /ff give " + alias)))
               );
               this.container.setItem(i, s);
            } catch (Throwable t) {
               // A single broken item must never crash the whole catalog.
               this.container.setItem(i, ItemStack.EMPTY);
            }
         } else {
            this.container.setItem(i, ItemStack.EMPTY);
         }
      }

      this.container.setItem(45, this.pageButton("§e§l◀ Prev", this.page > 0));
      this.container.setItem(46, this.pageButton("§e§lNext ▶", this.page < this.pageCount - 1));
      this.container.setItem(47, this.infoStack());

      for (int i = 48; i <= 52; i++) {
         this.container.setItem(i, this.filler());
      }

      this.container.setItem(53, this.closeStack());
   }

   private ItemStack pageButton(String label, boolean enabled) {
      ItemStack stack = new ItemStack(Items.PAPER);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal(label));
      stack.set(DataComponents.LORE, new ItemLore(List.of(Component.literal(enabled ? "§7Click to flip the page" : "§8No more pages"))));
      return stack;
   }

   private ItemStack infoStack() {
      ItemStack stack = new ItemStack(Items.EMERALD);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§a§lItem catalog"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Page " + (this.page + 1) + "/" + this.pageCount),
               Component.literal("§7" + ModCommands.allModItems(this.owner.level().registryAccess()).length + " items total"),
               Component.literal("§8Click an item to take a copy")
            )
         )
      );
      return stack;
   }

   private ItemStack filler() {
      ItemStack stack = new ItemStack(Items.STAINED_GLASS_PANE.gray());
      stack.set(DataComponents.CUSTOM_NAME, Component.literal(""));
      return stack;
   }

   private ItemStack closeStack() {
      ItemStack stack = new ItemStack(Items.STAINED_GLASS_PANE.red());
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§c§lClose"));
      return stack;
   }

   public void clicked(int slotId, int button, ContainerInput input, Player player) {
      if (player instanceof ServerPlayer sp) {
         if (slotId >= 0 && slotId < 54) {
            this.handleSlot(sp, slotId);
            this.returnCarried(sp);
         } else {
            super.clicked(slotId, button, input, player);
         }
      } else {
         super.clicked(slotId, button, input, player);
      }
   }

   private void returnCarried(ServerPlayer player) {
      ItemStack carried = this.getCarried();
      if (!carried.isEmpty()) {
         this.setCarried(ItemStack.EMPTY);
         this.setRemoteCarried(HashedStack.EMPTY);
         InventoryHelper.giveOrDrop(player, carried);
      }
   }

   private void handleSlot(ServerPlayer player, int slotId) {
      if (slotId >= 0 && slotId < 45) {
         ItemStack[] items = ModCommands.allModItems(this.owner.level().registryAccess());
         int idx = this.page * 45 + slotId;
         if (idx < items.length) {
            try {
               ModCommands.giveCatalogItem(player, items[idx]);
            } catch (Throwable t) {
            }
            SoundUtil.play(player, ModSounds.BUY, 1.3F);
         }
      } else {
         switch (slotId) {
            case 45:
               if (this.page > 0) {
                  this.page--;
                  this.rebuild();
                  this.broadcastChanges();
                  this.sendAllDataToRemote();
                  SoundUtil.play(player, ModSounds.PAGE_FLIP);
               }
               break;
            case 46:
               if (this.page < this.pageCount - 1) {
                  this.page++;
                  this.rebuild();
                  this.broadcastChanges();
                  this.sendAllDataToRemote();
                  SoundUtil.play(player, ModSounds.PAGE_FLIP);
               }
               break;
            case 53:
               player.closeContainer();
         }
      }
   }
}
