package com.fortuneandfavors.menu;

import com.fortuneandfavors.duel.DuelManager;
import com.fortuneandfavors.util.Chat;
import com.fortuneandfavors.util.InventoryHelper;
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
import net.minecraft.world.level.ItemLike;

public class BedwarsShopMenu extends ChestMenu {
   private static final int ROWS = 6;
   private static final int SLOTS = 54;
   private static final int CLOSE = 53;
   private final SimpleContainer container;
   private final ServerPlayer owner;

   private BedwarsShopMenu(int syncId, Inventory playerInventory, SimpleContainer container) {
      super(MenuType.GENERIC_9x6, syncId, playerInventory, container, 6);
      this.container = container;
      this.owner = (ServerPlayer)playerInventory.player;
      this.rebuild();
   }

   public static void open(ServerPlayer player) {
      player.openMenu(
         new SimpleMenuProvider((syncId, inv, p) -> new BedwarsShopMenu(syncId, inv, new SimpleContainer(54)), Component.literal("§6§lBed Wars Shop"))
      );
   }

   private void rebuild() {
      int size = DuelManager.bwShopSize();

      for (int i = 0; i < 54; i++) {
         if (i < size) {
            this.container.setItem(i, DuelManager.bwShopDisplay(this.owner, i));
         } else if (i == 53) {
            this.container.setItem(i, this.closeStack());
         } else {
            this.container.setItem(i, this.filler());
         }
      }

      this.broadcastChanges();
   }

   private ItemStack closeStack() {
      ItemStack stack = new ItemStack(Items.BARRIER);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§c§lClose"));
      return stack;
   }

   private ItemStack filler() {
      ItemStack stack = new ItemStack(Items.STAINED_GLASS_PANE.gray());
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§8§l "));
      return stack;
   }

   public void clicked(int slotId, int button, ContainerInput input, Player player) {
      if (!(player instanceof ServerPlayer sp)) {
         super.clicked(slotId, button, input, player);
      } else if (slotId >= 0 && slotId < 54) {
         if (slotId < DuelManager.bwShopSize() && (input == ContainerInput.PICKUP || input == ContainerInput.QUICK_MOVE)) {
            int amount = input != ContainerInput.QUICK_MOVE && !sp.isShiftKeyDown() ? 1 : 8;
            String err = DuelManager.bwShopBuy(sp, slotId, amount);
            if (err != null) {
               Chat.msg(sp, err);
            }

            this.rebuild();
         } else if (slotId == 53) {
            sp.closeContainer();
         }

         this.returnCarried(sp);
      } else if (input != ContainerInput.QUICK_MOVE && input != ContainerInput.CLONE) {
         super.clicked(slotId, button, input, player);
      } else {
         this.returnCarried(sp);
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
      if (index >= 0 && index < 54) {
         this.clicked(index, 0, ContainerInput.QUICK_MOVE, player);
      }

      return ItemStack.EMPTY;
   }
}
