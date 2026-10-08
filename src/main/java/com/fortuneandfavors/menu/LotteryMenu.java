package com.fortuneandfavors.menu;

import com.fortuneandfavors.economy.EconomyManager;
import com.fortuneandfavors.economy.LotteryManager;
import com.fortuneandfavors.util.InventoryHelper;
import java.util.Arrays;
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

/** The weekly lottery window - buy tickets, watch the pot, see when the draw hits. */
public class LotteryMenu extends ChestMenu {
   private static final int INFO = 4;
   private static final int ONE = 11;
   private static final int FIVE = 13;
   private static final int TEN = 15;
   private static final int TWENTY_FIVE = 17;
   private static final int CLOSE = 22;
   private final SimpleContainer container = (SimpleContainer)this.getContainer();
   private final ServerPlayer player;

   public LotteryMenu(int syncId, Inventory playerInventory) {
      super(MenuType.GENERIC_9x3, syncId, playerInventory, new SimpleContainer(27), 3);
      this.player = (ServerPlayer)playerInventory.player;
      this.rebuild();
   }

   public static void open(ServerPlayer player) {
      player.openMenu(new SimpleMenuProvider((syncId, inv, p) -> new LotteryMenu(syncId, inv), Component.literal("§6§l✧ Weekly Lottery ✧")));
   }

   private void rebuild() {
      this.container.clearContent();

      for (int slot : new int[]{0, 1, 2, 3, 5, 6, 7, 8, 9, 10, 12, 14, 16, 18, 19, 20, 21, 23, 24, 25, 26}) {
         this.container.setItem(slot, this.frame(slot % 2 == 0 ? (Item)Items.STAINED_GLASS_PANE.orange() : (Item)Items.STAINED_GLASS_PANE.yellow()));
      }

      int days = LotteryManager.daysUntilDraw();
      ItemStack info = this.named(
         Items.BOOK,
         "§6§lWeekly Lottery",
         "§7The pot: §f$" + LotteryManager.pool(),
         "§7Jackpot if drawn now: §f$" + LotteryManager.jackpotEstimate(),
         "§7  §8(pot share + §f$" + LotteryManager.participationBonus() + "§8 crowd bonus)",
         "§7Your tickets: §f" + LotteryManager.ticketsOf(this.player),
         "§7Players in: §f" + LotteryManager.buyers() + "§7 · tickets sold: §f" + LotteryManager.totalTickets(),
         "§7Draw: " + (days == 0 ? "§6tonight at midnight" : "§fin " + days + " day" + (days == 1 ? "" : "s")),
         "§8Winner takes 75% · 25% rolls over",
         "§8More players buying = a bigger bonus pot"
      );
      this.container.setItem(INFO, info);
      this.container.setItem(ONE, this.ticketButton(1));
      this.container.setItem(FIVE, this.ticketButton(5));
      this.container.setItem(TEN, this.ticketButton(10));
      this.container.setItem(TWENTY_FIVE, this.ticketButton(25));
      this.container.setItem(CLOSE, this.named(Items.BARRIER, "§cClose", "§7Keep your cash"));
   }

   private ItemStack ticketButton(int count) {
      long nextJackpot = LotteryManager.jackpotEstimate() + count * LotteryManager.TICKET_PRICE * 3L / 4L;
      ItemStack stack = this.named(
         Items.GOLD_INGOT,
         "§6Buy " + count + " Ticket" + (count == 1 ? "" : "s") + " §7($" + count * LotteryManager.TICKET_PRICE + ")",
         "§7Your tickets: §f" + LotteryManager.ticketsOf(this.player),
         "§7Cost: §a$" + count * LotteryManager.TICKET_PRICE + " §8(each ticket adds to the pot)",
         "§7Jackpot after buying: §f$" + nextJackpot,
         "§7Players in: §f" + LotteryManager.buyers(),
         "§8One ticket = one chance at the pot",
         "§8Max 100 tickets per week",
         "§8More players buying = an even bigger pot"
      );
      stack.setCount(Math.min(count, 64));
      return stack;
   }

   private ItemStack frame(Item item) {
      ItemStack stack = new ItemStack(item);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal(" "));
      return stack;
   }

   private ItemStack named(Item item, String name, String... lore) {
      ItemStack stack = new ItemStack(item);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
      if (lore.length > 0) {
         stack.set(DataComponents.LORE, new ItemLore(Arrays.stream(lore).<Component>map(s -> Component.literal(s)).toList()));
      }
      return stack;
   }

   public void clicked(int slotId, int button, ContainerInput input, Player player) {
      if (player instanceof ServerPlayer sp && slotId < 27) {
         this.returnCarried(sp);
         switch (slotId) {
            case ONE:
               LotteryManager.buy(sp, 1);
               this.rebuild();
               this.broadcastChanges();
               break;
            case FIVE:
               LotteryManager.buy(sp, 5);
               this.rebuild();
               this.broadcastChanges();
               break;
            case TEN:
               LotteryManager.buy(sp, 10);
               this.rebuild();
               this.broadcastChanges();
               break;
            case TWENTY_FIVE:
               LotteryManager.buy(sp, 25);
               this.rebuild();
               this.broadcastChanges();
               break;
            case CLOSE:
               sp.closeContainer();
               break;
            default:
               break;
         }
      } else {
         super.clicked(slotId, button, input, player);
      }
   }

   public ItemStack quickMoveStack(Player player, int index) {
      return ItemStack.EMPTY;
   }

   private void returnCarried(ServerPlayer player) {
      ItemStack carried = this.getCarried();
      if (!carried.isEmpty()) {
         this.setCarried(ItemStack.EMPTY);
         this.setRemoteCarried(HashedStack.EMPTY);
         InventoryHelper.giveOrDrop(player, carried);
      }
   }
}