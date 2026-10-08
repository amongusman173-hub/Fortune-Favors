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
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.ItemLike;

public class DuelModeMenu extends ChestMenu {
   private static final int ROWS = 3;
   private static final int SLOTS = 27;
   private static final int CLOSE = 26;
   private final SimpleContainer container;
   private final ServerPlayer owner;

   private DuelModeMenu(int syncId, Inventory playerInventory, SimpleContainer container) {
      super(MenuType.GENERIC_9x3, syncId, playerInventory, container, 3);
      this.container = container;
      this.owner = (ServerPlayer)playerInventory.player;
      this.rebuild();
   }

   public static void open(ServerPlayer player) {
      player.openMenu(
         new SimpleMenuProvider((syncId, inv, p) -> new DuelModeMenu(syncId, inv, new SimpleContainer(27)), Component.literal("§6§lPick a Duel Mode"))
      );
   }

   private void rebuild() {
      this.mode(0, Items.IRON_SWORD, "§f§lModern Arena", "§7Vanilla 1.9+ combat, shield ready");
      this.mode(1, Items.DIAMOND_SWORD, "§c§lLegacy 1.8", "§7No cooldown, rod knockback, sword block");
      this.mode(2, (Item)Items.BED.pick(DyeColor.RED), "§e§lBed Wars", "§7Break their bed, defend yours");
      this.mode(3, Items.ENDER_PEARL, "§b§lSky Wars", "§7Kits + a refilling middle");
      this.mode(4, Items.EMERALD, "§d§lLucky PvP", "§73 random items - weapon, armor, wildcard");
      this.mode(5, Items.TNT, "§c§lTNT Run", "§7Floor crumbles beneath you - last one standing!");
      this.mode(6, Items.COMPASS, "§e§lRandomizer Duel", "§7Your weapon re-rolls every 10s - you can't drop it!");
      this.mode(7, Items.BOOK, "§b§lDraft Duel", "§7Take turns picking your kit from a shared pool");
      this.mode(8, Items.SHIELD, "§c§lLast Stand", "§74 lives - every death permanently strips something");
      this.mode(9, Items.LEATHER_CHESTPLATE, "§d§lKits", "§7Random fun kits - Pyro, Ninja, Frog, Creeper...");
      this.mode(11, Items.BOW, "§2§lArchery Duel", "§7Bows only - melee does nothing at all");
      this.mode(12, com.fortuneandfavors.duel.DuelManager.gladiatorIcon(), "§6§lGladiator", "§7Empty-handed in a huge world - mine, loot, then hunt");

      // Slots 10 and 13 are deliberately empty. "Legacy 1.8 extras" duplicated the
      // Legacy 1.8 icon two slots to its left - both opened the same submenu - and
      // Boxing used to sit in the main list while belonging to the 1.8 games, which are
      // reached through the Legacy option. Everything 1.8 lives there now.
      for (int i = 10; i < 26; i++) {
         this.container.setItem(i, this.filler());
      }

      this.container.setItem(26, this.closeStack());
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
         if (slotId >= 0 && slotId < 27) {
            DuelMode chosen = null;
            if (slotId == 0) {
               chosen = DuelMode.MODERN;
            } else if (slotId == 1) {
               chosen = DuelMode.LEGACY;
            } else if (slotId == 2) {
               chosen = DuelMode.BEDWARS;
            } else if (slotId == 3) {
               chosen = DuelMode.SKYWARS;
            } else if (slotId == 4) {
               chosen = DuelMode.LUCKYPvP;
            } else if (slotId == 5) {
               chosen = DuelMode.TNTRUN;
            } else if (slotId == 6) {
               chosen = DuelMode.RANDOMIZER;
            } else if (slotId == 7) {
               chosen = DuelMode.DRAFT;
            } else if (slotId == 8) {
               chosen = DuelMode.LASTSTAND;
            } else if (slotId == 9) {
               chosen = DuelMode.KITS;
            } else if (slotId == 11) {
               chosen = DuelMode.ARCHERY;
            } else if (slotId == 12) {
               chosen = DuelMode.GLADIATOR;
            }

            if (chosen != null) {
               if (chosen != DuelMode.MODERN && chosen != DuelMode.LEGACY) {
                  String err = DuelManager.confirmChallenge(sp, chosen);
                  sp.closeContainer();
                  if (err != null) {
                     Chat.msg(sp, "&c" + err);
                  }
               } else {
                  sp.closeContainer();
                  DuelSubmodeMenu.open(sp, chosen);
               }
            } else {
               if (slotId == 26) {
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
      if (index >= 0 && index < 27) {
         this.clicked(index, 0, ContainerInput.QUICK_MOVE, player);
      }

      return ItemStack.EMPTY;
   }
}
