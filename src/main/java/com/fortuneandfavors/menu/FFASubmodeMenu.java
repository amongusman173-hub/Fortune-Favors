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

public class FFASubmodeMenu extends ChestMenu {
   private static final int SLOTS = 9;
   private static final int CLOSE = 8;
   private final SimpleContainer container;
   private final ServerPlayer owner;
   private final DuelMode baseMode;

   private FFASubmodeMenu(int syncId, Inventory playerInventory, SimpleContainer container, DuelMode baseMode) {
      super(MenuType.GENERIC_9x1, syncId, playerInventory, container, 1);
      this.container = container;
      this.owner = (ServerPlayer)playerInventory.player;
      this.baseMode = baseMode;
      this.rebuild();
   }

   public static void open(ServerPlayer player, DuelMode baseMode) {
      String title = baseMode == DuelMode.MODERN ? "§f§lModern PvP FFA Options" : "§c§lLegacy PvP FFA Options";
      player.openMenu(new SimpleMenuProvider((syncId, inv, p) -> new FFASubmodeMenu(syncId, inv, new SimpleContainer(9), baseMode), Component.literal(title)));
   }

   private void rebuild() {
      DuelMode[] modes = DuelMode.subModesOf(this.baseMode);
      int i = 0;

      for (DuelMode mode : modes) {
         if (mode.supportsFfa()) {
            this.submode(i, mode);
            i++;
         }
      }

      while (i < 8) {
         this.container.setItem(i, this.filler());
         i++;
      }

      this.container.setItem(8, this.closeStack());
      this.broadcastChanges();
   }

   private void submode(int slot, DuelMode mode) {
      Item icon;
      String name;
      String lore;
      if (mode == DuelMode.MODERN) {
         icon = Items.IRON_SWORD;
         name = "§f§lClassic";
         lore = "§7Vanilla 1.9+ combat";
      } else if (mode == DuelMode.LEGACY) {
         icon = Items.DIAMOND_SWORD;
         name = "§c§lClassic";
         lore = "§7No cooldown, rod knockback, sword block";
      } else if (mode == DuelMode.MACEPVP) {
         icon = Items.MACE;
         name = "§b§lMace PvP";
         lore = "§7Mace + armor - crash from above!";
      } else if (mode == DuelMode.CRYSTALPVP) {
         icon = Items.END_CRYSTAL;
         name = "§5§lCrystal PvP";
         lore = "§7Pickaxe, obsidian, end crystals";
      } else if (mode == DuelMode.AXEDUEL) {
         icon = Items.DIAMOND_AXE;
         name = "§a§lAxe Duel";
         lore = "§7Diamond axe + protection armor + gapples";
      } else if (mode == DuelMode.COMBODUEL) {
         icon = Items.IRON_SWORD;
         name = "§c§lCombo Duel";
         lore = "§7No knockback, Speed II - pure 1.8 combos";
      } else if (mode == DuelMode.SUMO) {
         icon = Items.SLIME_BALL;
         name = "§a§lSumo";
         lore = "§7Everyone on one platform - last body standing";
      } else if (mode == DuelMode.GLADIATOR) {
         icon = com.fortuneandfavors.duel.DuelManager.gladiatorIcon();
         name = "§6§lGladiator";
         lore = "§7Empty-handed in a huge world - mine, loot, then hunt";
      } else if (mode == DuelMode.UHCDUEL) {
         icon = Items.GOLDEN_APPLE;
         name = "§6§lUHC Duel";
         lore = "§7No natural regen, limited healing";
      } else {
         icon = Items.PAPER;
         name = mode.display;
         lore = mode.display;
      }

      this.mode(slot, icon, name, lore);
   }

   private void mode(int slot, Item item, String name, String lore) {
      ItemStack stack = new ItemStack(item);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
      stack.set(DataComponents.LORE, new ItemLore(List.of(Component.literal(lore))));
      this.container.setItem(slot, stack);
   }

   private ItemStack closeStack() {
      ItemStack stack = new ItemStack(Items.BARRIER);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§c§lBack"));
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
            DuelMode[] modes = DuelMode.subModesOf(this.baseMode);
            int ffaIdx = 0;

            for (DuelMode mode : modes) {
               if (mode.supportsFfa()) {
                  if (ffaIdx == slotId) {
                     DuelMode chosen = mode;
                     sp.closeContainer();
                     String err = DuelManager.requestFFA(sp, chosen);
                     if (err != null) {
                        Chat.msg(sp, "&c" + err);
                     }

                     return;
                  }

                  ffaIdx++;
               }
            }

            if (slotId == 8) {
               sp.closeContainer();
               FFAModeMenu.open(sp);
               this.returnCarried(sp);
            } else {
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
