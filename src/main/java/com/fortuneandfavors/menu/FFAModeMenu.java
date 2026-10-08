package com.fortuneandfavors.menu;

import com.fortuneandfavors.duel.DuelManager;
import com.fortuneandfavors.duel.DuelManager.DuelMode;
import com.fortuneandfavors.util.Chat;
import com.fortuneandfavors.util.InventoryHelper;
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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.ItemLike;

public class FFAModeMenu extends ChestMenu {
   private static final int ROWS = 1;
   private static final int SLOTS = 9;
   private static final int CLOSE = 8;
   private final SimpleContainer container;
   private final ServerPlayer owner;

   private FFAModeMenu(int syncId, Inventory playerInventory, SimpleContainer container) {
      super(MenuType.GENERIC_9x1, syncId, playerInventory, container, 1);
      this.container = container;
      this.owner = (ServerPlayer)playerInventory.player;
      this.rebuild();
   }

   public static void open(ServerPlayer player) {
      player.openMenu(
         new SimpleMenuProvider((syncId, inv, p) -> new FFAModeMenu(syncId, inv, new SimpleContainer(9)), Component.literal("§e§lFFA - Pick a Mode"))
      );
   }

   private void rebuild() {
      this.mode(0, Items.IRON_SWORD, "§f§lModern", "§7Modern PvP FFA - 1.9+ combat");
      this.mode(1, Items.DIAMOND_SWORD, "§c§lLegacy", "§7Legacy 1.8 FFA - no cooldown, rod knockback");
      this.mode(2, Items.EMERALD, "§d§lLucky PvP", "§73 random items - weapon, armor, wildcard");
      this.mode(3, Items.TNT, "§c§lTNT Run", "§7Floor crumbles - last one standing!");
      this.mode(4, Items.LEATHER_CHESTPLATE, "§d§lKits", "§7Random fun kits - Pyro, Ninja, Frog, Creeper...");

      // Slot 5 is deliberately empty: "Legacy 1.8 extras" opened exactly the same
      // submenu as the Legacy icon in slot 1, so it was a second door into one room.
      for (int i = 5; i < 8; i++) {
         this.container.setItem(i, this.filler());
      }

      this.container.setItem(8, this.closeStack());
      this.broadcastChanges();
   }

   private void mode(int slot, Item item, String name, String lore) {
      ItemStack stack = new ItemStack(item);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
      stack.set(DataComponents.LORE, new ItemLore(List.of(Component.literal(lore))));
      this.container.setItem(slot, stack);
   }

   private ItemStack closeStack() {
      ItemStack stack = new ItemStack(Items.BARRIER);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§c§lCancel"));
      return stack;
   }

   private ItemStack filler() {
      ItemStack stack = new ItemStack(Items.STAINED_GLASS_PANE.gray());
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§8§l "));
      return stack;
   }

   public void clicked(int slotId, int button, ContainerInput input, Player player) {
      if (player instanceof ServerPlayer sp) {
         if (slotId >= 0 && slotId < 9) {
            DuelMode chosen = null;
            if (slotId == 0) {
               chosen = DuelMode.MODERN;
            } else if (slotId == 1) {
               chosen = DuelMode.LEGACY;
            } else if (slotId == 2) {
               chosen = DuelMode.LUCKYPvP;
            } else if (slotId == 3) {
               chosen = DuelMode.TNTRUN;
            } else if (slotId == 4) {
               chosen = DuelMode.KITS;
            }

            if (chosen != null) {
               if (chosen != DuelMode.MODERN && chosen != DuelMode.LEGACY) {
                  sp.closeContainer();
                  String err = DuelManager.requestFFA(sp, chosen);
                  if (err != null) {
                     Chat.msg(sp, "&c" + err);
                  }
               } else {
                  sp.closeContainer();
                  FFASubmodeMenu.open(sp, chosen);
               }
            } else {
               if (slotId == 8) {
                  sp.closeContainer();
               }

               this.returnCarried(sp);
            }
         } else if (input != ContainerInput.QUICK_MOVE && input != ContainerInput.CLONE) {
            super.clicked(slotId, button, input, player);
         } else {
            this.returnCarried(sp);
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

   public ItemStack quickMoveStack(Player player, int index) {
      if (index >= 0 && index < 9) {
         this.clicked(index, 0, ContainerInput.QUICK_MOVE, player);
      }

      return ItemStack.EMPTY;
   }
}
