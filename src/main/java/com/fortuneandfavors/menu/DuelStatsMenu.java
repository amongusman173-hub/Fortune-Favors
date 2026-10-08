package com.fortuneandfavors.menu;

import com.fortuneandfavors.duel.DuelManager;
import com.fortuneandfavors.duel.DuelManager.DuelMode;
import com.fortuneandfavors.duel.DuelManager.LeaderboardRow;
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

public class DuelStatsMenu extends ChestMenu {
   private static final int ROWS = 6;
   private static final int SLOTS = 54;
   private static final int CONTENT_START = 9;
   private static final int CLOSE = 53;
   private static final int YOUR_STATS = 0;
   private static final int OVERALL = 1;
   private static final int MODERN = 2;
   private static final int LEGACY = 3;
   private static final int BEDWARS = 4;
   private static final int SKYWARS = 5;
   private static final int VIEW_STATS = -1;
   private static final int VIEW_OVERALL = 0;
   private final SimpleContainer container;
   private final ServerPlayer owner;
   private int view = -1;

   private DuelStatsMenu(int syncId, Inventory playerInventory, SimpleContainer container) {
      super(MenuType.GENERIC_9x6, syncId, playerInventory, container, 6);
      this.container = container;
      this.owner = (ServerPlayer)playerInventory.player;
      this.rebuild();
   }

   public static void open(ServerPlayer player) {
      player.openMenu(
         new SimpleMenuProvider((syncId, inv, p) -> new DuelStatsMenu(syncId, inv, new SimpleContainer(54)), Component.literal("§6§lDuel Records"))
      );
   }

   private void rebuild() {
      this.button(0, Items.PAPER, "§e§lYour Stats", "§7Your per-mode record");
      this.button(1, Items.GOLDEN_APPLE, "§a§lOverall", "§7Top duelists, all modes");
      this.button(2, Items.IRON_SWORD, "§f§lModern", "§7Modern Arena leaderboard");
      this.button(3, Items.DIAMOND_SWORD, "§c§lLegacy 1.8", "§7Legacy 1.8 leaderboard");
      this.button(4, (Item)Items.BED.pick(DyeColor.RED), "§e§lBed Wars", "§7Bed Wars leaderboard");
      this.button(5, Items.ENDER_PEARL, "§b§lSky Wars", "§7Sky Wars leaderboard");
      this.container.setItem(7, this.filler());
      this.container.setItem(53, this.closeStack());

      for (int i = 9; i < 53; i++) {
         this.container.setItem(i, this.filler());
      }

      if (this.view == -1) {
         this.fillStats();
      } else if (this.view == 0) {
         this.fillLeaderboard(null);
      } else {
         for (DuelMode m : DuelMode.values()) {
            if (m.ordinal() + 1 == this.view) {
               this.fillLeaderboard(m);
               break;
            }
         }
      }

      this.broadcastChanges();
   }

   private void button(int slot, Item item, String name, String lore) {
      ItemStack stack = new ItemStack(item);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
      stack.set(DataComponents.LORE, new ItemLore(List.of(Component.literal(lore))));
      this.container.setItem(slot, stack);
   }

   private void fillStats() {
      int slot = 9;
      this.entry(slot++, Items.GOLDEN_APPLE, "§a§lOverall", this.recordOf(null));

      for (DuelMode m : DuelMode.values()) {
         this.entry(slot++, this.iconFor(m), "§f§l" + m.display, this.recordOf(m));
      }
   }

   private void fillLeaderboard(DuelMode mode) {
      List<LeaderboardRow> rows = DuelManager.leaderboardRows(mode);
      int slot = 9;
      if (rows.isEmpty()) {
         this.entry(slot++, Items.PAPER, "§7No duels recorded yet", "§8Go fight - /duel <player>!");
      } else {
         for (LeaderboardRow r : rows) {
            if (slot >= 53) {
               break;
            }

            int pct = (int)Math.round(100.0 * r.wins() / (r.wins() + r.losses()));
            this.entry(slot++, Items.PAPER, "§e#" + (slot - 9) + " §f" + r.name(), "§a" + r.wins() + "W §c" + r.losses() + "L §7(" + pct + "%)");
         }
      }
   }

   private void entry(int slot, Item item, String name, String lore) {
      ItemStack stack = new ItemStack(item);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
      stack.set(DataComponents.LORE, new ItemLore(List.of(Component.literal(lore))));
      this.container.setItem(slot, stack);
   }

   private String recordOf(DuelMode mode) {
      long[] rec = DuelManager.statsOf(this.owner.getUUID(), mode);
      long total = rec[0] + rec[1];
      if (total == 0L) {
         return "§7Never played - go fight!";
      }

      int pct = (int)Math.round(100.0 * rec[0] / total);
      return "§a" + rec[0] + "W §c" + rec[1] + "L §7(" + pct + "%)";
   }

         private Item iconFor(DuelMode m) {
      return switch (m) {
         case MODERN -> Items.IRON_SWORD;
         case MACEPVP -> Items.DIAMOND_SWORD;
         case CRYSTALPVP -> (Item)Items.BED.pick(DyeColor.RED);
         case UHCDUEL -> Items.ENDER_PEARL;
         case LEGACY -> Items.EMERALD;
         case AXEDUEL -> Items.LEATHER_CHESTPLATE;
         case COMBODUEL -> Items.MACE;
         case BEDWARS -> Items.END_CRYSTAL;
         case SKYWARS -> Items.DIAMOND_AXE;
         case LUCKYPvP -> Items.GOLDEN_APPLE;
         case KITS -> Items.IRON_SWORD;
         case RANDOMIZER -> Items.COMPASS;
         case DRAFT -> Items.BOOK;
         case LASTSTAND -> Items.SHIELD;
         case TNTRUN -> Items.TNT;
         // These three were missing, and the default threw - so a stats screen that listed
         // a Gladiator, Sumo or Archery record crashed the menu instead of drawing a row.
         // The Gladiator one comes from the same place the other two menus take it from, so
         // the mode cannot end up with two icons again.
         case GLADIATOR -> com.fortuneandfavors.duel.DuelManager.gladiatorIcon();
         case SUMO -> Items.SLIME_BALL;
         case ARCHERY -> Items.BOW;
      };
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
      if (player instanceof ServerPlayer sp) {
         if (slotId >= 0 && slotId < 54) {
            if (slotId == 0) {
               this.view = -1;
               this.rebuild();
            } else if (slotId == 1) {
               this.view = 0;
               this.rebuild();
            } else if (slotId == 2) {
               this.view = DuelMode.MODERN.ordinal() + 1;
               this.rebuild();
            } else if (slotId == 3) {
               this.view = DuelMode.LEGACY.ordinal() + 1;
               this.rebuild();
            } else if (slotId == 4) {
               this.view = DuelMode.BEDWARS.ordinal() + 1;
               this.rebuild();
            } else if (slotId == 5) {
               this.view = DuelMode.SKYWARS.ordinal() + 1;
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
      if (index >= 0 && index < 54) {
         this.clicked(index, 0, ContainerInput.QUICK_MOVE, player);
      }

      return ItemStack.EMPTY;
   }
}
