package com.fortuneandfavors.menu;

import com.fortuneandfavors.economy.BlockValues;
import com.fortuneandfavors.economy.TradeManager;
import com.fortuneandfavors.economy.TradeManager.Trade;
import com.fortuneandfavors.menu.TradeMenu.CashInputMenu;
import com.fortuneandfavors.util.Chat;
import com.fortuneandfavors.util.InventoryHelper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.ItemLike;
import java.util.Set;
import com.fortuneandfavors.util.PriceUtil;

public class TradeMenu extends ChestMenu {
   public static final int OFFER_COUNT = 17;
   public static final int MY_LABEL = 4;
   public static final int THEIR_BASE = 27;
   public static final int THEIR_LABEL = 31;
   private static final int MY_CASH = 45;
   private static final int THEIR_CASH = 46;
   private static final int ACCEPT = 47;
   private static final int STATUS = 48;
   private static final int CLOSE = 53;
   private static final Map<UUID, TradeMenu> openMenus = new HashMap<>();
   private final SimpleContainer container;
   private final Trade trade;
   private final boolean sideIsA;
   private final ServerPlayer owner;

   public TradeMenu(int syncId, Inventory playerInventory, Trade trade, boolean sideIsA) {
      this(syncId, playerInventory, trade, sideIsA, new SimpleContainer(54));
   }

   private TradeMenu(int syncId, Inventory playerInventory, Trade trade, boolean sideIsA, SimpleContainer container) {
      super(MenuType.GENERIC_9x6, syncId, playerInventory, container, 6);
      this.container = container;
      this.trade = trade;
      this.sideIsA = sideIsA;
      this.owner = (ServerPlayer)playerInventory.player;
      openMenus.put(this.owner.getUUID(), this);
      this.rebuildFromSession();
   }

   public static TradeMenu menuOf(ServerPlayer player) {
      return openMenus.get(player.getUUID());
   }

   public ItemStack containerItem(int index) {
      return this.container.getItem(index);
   }

   public static int slotForOfferIndex(int offer) {
      return offer >= 4 ? offer + 1 : offer;
   }

   public void rebuildFromSession() {
      boolean isA = this.sideIsA;
      this.container.clearContent();

      for (int i = 0; i < 17; i++) {
         int s = slotForOfferIndex(i);
         ItemStack mine = (isA ? this.trade.aItems : this.trade.bItems).getItem(i);
         ItemStack theirs = (isA ? this.trade.bItems : this.trade.aItems).getItem(i);
         this.container.setItem(s, mine.copy());
         this.container.setItem(27 + s, this.markTheirs(theirs));
      }

      ServerPlayer me = isA ? this.trade.a : this.trade.b;
      ServerPlayer them = isA ? this.trade.b : this.trade.a;
      String otherName = them.getName().getString();
      this.container.setItem(4, this.head(me, "§2§lYOUR OFFERS", "§7" + me.getName().getString(), "§7Put the items you want to give here."));
      this.container.setItem(31, this.head(them, "§c§lTHEIR OFFERS", "§7" + otherName, "§7What they're offering - read-only."));

      long myCash = isA ? this.trade.aCash : this.trade.bCash;
      long theirCash = isA ? this.trade.bCash : this.trade.aCash;
      long myItems = this.offerValue(isA);
      long theirItems = this.offerValue(!isA);
      long myTotal = myItems + myCash;
      long theirTotal = theirItems + theirCash;

      this.container.setItem(18, this.sideLabel((Item)Items.STAINED_GLASS_PANE.lime(), "§2§lYOU"));
      this.container.setItem(19, this.marketPanel(isA));
      this.container.setItem(20, this.historyPanel());
      this.container.setItem(22, this.valueSummary(myTotal, theirTotal, myItems, myCash, theirItems, theirCash));
      this.container.setItem(24, this.filler(Items.STAINED_GLASS_PANE.red()));
      this.container.setItem(25, this.filler(Items.STAINED_GLASS_PANE.red()));
      this.container.setItem(26, this.sideLabel((Item)Items.STAINED_GLASS_PANE.red(), "§c§lTHEM"));

      this.container.setItem(45, this.cashButton("§e§lADD CASH", myCash, "§7Click to add cash to your offer", them));
      this.container.setItem(46, this.info(new ItemStack(Items.EMERALD), "§6Their cash", "§7" + Chat.moneyStr(theirCash)));
      boolean iAccept = isA ? this.trade.aAccepted : this.trade.bAccepted;
      boolean theyAccept = isA ? this.trade.bAccepted : this.trade.aAccepted;
      if (iAccept && theyAccept) {
         this.container.setItem(47, this.info(new ItemStack(Items.EMERALD_BLOCK), "§a§l✔ TRADE COMPLETE", "§7Both players accepted - trading now!"));
      } else if (iAccept) {
         this.container
            .setItem(47, this.info(new ItemStack(Items.DYE.lime()), "§a§l✔ ACCEPTED", "§7Waiting for §f" + otherName + "§7...", "§8Click to un-accept"));
      } else {
         this.container
            .setItem(
               47,
               this.info(
                  new ItemStack(Items.DYE.gray()),
                  "§7§lACCEPT TRADE",
                  "§7Click to lock in your offer",
                  theyAccept ? "§a" + otherName + " has already accepted!" : "§8" + otherName + " hasn't accepted yet",
                  "§8You're offering §f" + Chat.moneyStr(myTotal)
               )
            );
      }

      this.container
         .setItem(
            48,
            this.info(
               new ItemStack(Items.BOOK),
               "§7Trade summary",
               "§2You: §f" + Chat.moneyStr(myTotal) + "§7 (" + Chat.moneyStr(myItems) + " items + " + Chat.moneyStr(myCash) + " cash)",
               "§c" + otherName + ": §f" + Chat.moneyStr(theirTotal) + "§7 (" + Chat.moneyStr(theirItems) + " items + " + Chat.moneyStr(theirCash) + " cash)",
               "",
               "§7Offers are returned if the trade is cancelled."
            )
         );
      this.container.setItem(53, this.info(new ItemStack(Items.BARRIER), "§cCancel trade", "§7Closes the trade and returns offers"));
      this.broadcastChanges();
   }

   /**
    * The server's own market, in the window where the trade is being made.
    *
    * <p>Two halves, and both are measured rather than invented. The top is the items this server's
    * players have actually traded with each other this week, busiest first, with the price those
    * trades settled at - which is the only real answer to "what is this worth here", because it is
    * what somebody paid. The bottom is the same question asked about everything in this offer, so a
    * player can see whether the thing they are about to hand over is something the server is hungry
    * for or something nobody wants.
    */
   private ItemStack marketPanel(boolean isA) {
      List<Component> lore = new ArrayList<>();
      lore.add(Component.literal("§7What players on this server have"));
      lore.add(Component.literal("§7actually traded, over the last week."));
      lore.add(Component.literal(""));
      List<TradeManager.Flow> hot = TradeManager.hottest(4);
      if (hot.isEmpty()) {
         lore.add(Component.literal("§8Nothing has changed hands yet."));
      } else {
         lore.add(Component.literal("§6§lIN DEMAND"));
         for (TradeManager.Flow flow : hot) {
            lore.add(
               Component.literal(
                  "§7" + itemName(flow.item()) + " §8· §f" + flow.units() + "§8 at §f" + Chat.moneyStr(flow.rate()) + "§8 each"
               )
            );
         }
      }
      // And the offer in front of them, priced against what people pay for it.
      lore.add(Component.literal(""));
      lore.add(Component.literal("§6§lIN YOUR OFFER"));
      SimpleContainer mine = isA ? this.trade.aItems : this.trade.bItems;
      int shown = 0;
      Set<String> already = new HashSet<>();
      for (int i = 0; i < mine.getContainerSize() && shown < 3; i++) {
         ItemStack stack = mine.getItem(i);
         if (stack.isEmpty()) {
            continue;
         }
         String id = itemName(stack.getItem().toString().isEmpty() ? "?" : registryId(stack));
         if (!already.add(id)) {
            continue;
         }
         TradeManager.Flow flow = TradeManager.flowOf(registryId(stack));
         lore.add(
            Component.literal(
               "§7" + stack.getCount() + "x " + id + (flow.units() > 0L
                  ? " §8· §f" + flow.units() + "§8 traded at §f" + Chat.moneyStr(flow.rate())
                  : " §8· never traded here")
            )
         );
         shown++;
      }
      if (shown == 0) {
         lore.add(Component.literal("§8Nothing on the table yet."));
      }
      return this.panel(new ItemStack(Items.COMPARATOR), "§6§lTHE MARKET", lore);
   }

   /**
    * The player's own last trades, newest first.
    *
    * <p>The other half of a real exchange: a trader who cannot remember what they paid last time is
    * a trader making it up. Each line is the partner, the server's own valuation of both sides, and
    * which way the deal went for them - so the window answers "am I being reasonable" as well as
    * "is this item worth anything".
    */
   private ItemStack historyPanel() {
      List<Component> lore = new ArrayList<>();
      lore.add(Component.literal("§7Your last " + TradeManager.HISTORY + " trades"));
      lore.add(Component.literal(""));
      List<TradeManager.Entry> mine = TradeManager.history(this.owner.getUUID());
      if (mine.isEmpty()) {
         lore.add(Component.literal("§8You have not traded with anyone yet."));
      } else {
         for (TradeManager.Entry line : mine) {
            long edge = line.edge();
            lore.add(
               Component.literal(
                  "§7with §f" + line.partner() + " §8· §7gave §f" + Chat.moneyStr(line.gave())
                     + "§8, got §f" + Chat.moneyStr(line.got())
               )
            );
            lore.add(
               Component.literal(
                  "   " + (edge >= 0L ? "§a+" : "§c-") + Chat.moneyStr(Math.abs(edge))
                     + "§8 on the server's books"
               )
            );
         }
      }
      return this.panel(new ItemStack(Items.WRITTEN_BOOK), "§e§lYOUR TRADES", lore);
   }

   /** The registry id of a stack's item, or "unknown". */
   private static String registryId(ItemStack stack) {
      try {
         return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
      } catch (Throwable t) {
         return "unknown";
      }
   }

   /** A readable name for a registry id, so a market line is not a namespace path. */
   private static String itemName(String id) {
      try {
         Item item = net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(net.minecraft.resources.Identifier.parse(id));
         if (item != null && item != Items.AIR) {
            return new ItemStack(item).getHoverName().getString();
         }
      } catch (Throwable ignored) {
      }
      String tail = id == null ? "?" : id;
      int cut = tail.indexOf(':');
      if (cut >= 0) {
         tail = tail.substring(cut + 1);
      }
      return tail.replace('_', ' ');
   }

   /** A tile built from already-made lore lines. */
   private ItemStack panel(ItemStack stack, String name, List<Component> lore) {
      stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
      stack.set(DataComponents.LORE, new ItemLore(lore));
      return stack;
   }

   private long offerValue(boolean sideA) {
      long total = 0L;

      for (int i = 0; i < 17; i++) {
         ItemStack s = (sideA ? this.trade.aItems : this.trade.bItems).getItem(i);
         if (!s.isEmpty()) {
            total += BlockValues.valueOf(s) * s.getCount();
         }
      }

      return total;
   }

   private ItemStack head(ServerPlayer player, String name, String... lines) {
      ItemStack stack = new ItemStack(Items.PLAYER_HEAD);
      stack.set(DataComponents.PROFILE, ResolvableProfile.createResolved(player.getGameProfile()));
      stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
      java.util.List<Component> lore = new java.util.ArrayList<>();

      for (String line : lines) {
         lore.add(Component.literal(line));
      }

      stack.set(DataComponents.LORE, new ItemLore(lore));
      return stack;
   }

   private ItemStack filler(ItemLike base) {
      return this.sideLabel(base, "§0 ");
   }

   private ItemStack valueSummary(long mine, long theirs, long myItems, long myCash, long theirItems, long theirCash) {
      ItemStack stack = mine >= theirs ? new ItemStack(Items.EMERALD) : new ItemStack(Items.REDSTONE);
      String diff = mine >= theirs ? "§a+" + Chat.moneyStr(mine - theirs) : "§c-" + Chat.moneyStr(theirs - mine);
      stack.set(
         DataComponents.CUSTOM_NAME,
         Component.literal(mine >= theirs ? "§2§lYOU'RE AHEAD" : "§c§lYOU'RE BEHIND")
      );
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§2Your total: §f" + Chat.moneyStr(mine)),
               Component.literal("   §7" + Chat.moneyStr(myItems) + " items + " + Chat.moneyStr(myCash) + " cash"),
               Component.literal("§cTheir total: §f" + Chat.moneyStr(theirs)),
               Component.literal("   §7" + Chat.moneyStr(theirItems) + " items + " + Chat.moneyStr(theirCash) + " cash"),
               Component.literal(mine == theirs ? "§eEven trade" : diff)
            )
         )
      );
      return stack;
   }

   private ItemStack sideLabel(ItemLike base, String name, String... lines) {
      ItemStack stack = new ItemStack(base);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
      stack.set(DataComponents.LORE, new ItemLore(List.of(Component.literal(String.join("\n", lines)))));
      return stack;
   }

   private ItemStack markTheirs(ItemStack stack) {
      ItemStack s = stack.copy();
      if (!s.isEmpty()) {
         s.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("§7Their offer"))));
      }

      return s;
   }

   private ItemStack cashButton(String name, long cash, String lore, ServerPlayer other) {
      ItemStack stack = new ItemStack(Items.GOLD_INGOT);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
      stack.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("§7In offer: §f" + cash), Component.literal(lore))));
      return stack;
   }

   private ItemStack info(ItemStack base, String name, String... lines) {
      ItemStack stack = base.copy();
      stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
      stack.set(DataComponents.LORE, new ItemLore(List.of(Component.literal(String.join("\n", lines)))));
      return stack;
   }

   private void returnCarried(ServerPlayer player) {
      ItemStack carried = this.getCarried();
      if (!carried.isEmpty()) {
         this.setCarried(ItemStack.EMPTY);
         this.setRemoteCarried(HashedStack.EMPTY);
         InventoryHelper.giveOrDrop(player, carried);
      }
   }

   public void clicked(int slotId, int button, ContainerInput input, Player player) {
      if (player instanceof ServerPlayer sp) {
         if (!this.trade.done) {
            if (slotId >= 54) {
               super.clicked(slotId, button, input, player);
            } else if (slotId >= 0 && slotId < 18) {
               if (slotId == 4) {
                  this.returnCarried(sp);
               } else {
                  super.clicked(slotId, button, input, player);
                  TradeManager.sideEdited(this.trade, sp);
               }
            } else if (slotId == 45) {
               this.returnCarried(sp);
               this.openCashInput(sp);
            } else if (slotId == 47) {
               this.returnCarried(sp);
               TradeManager.toggleAccept(this.trade, sp);
            } else if (slotId == 53) {
               TradeManager.cancel(this.trade, sp);
            } else {
               this.returnCarried(sp);
            }
         }
      } else {
         super.clicked(slotId, button, input, player);
      }
   }

   private void openCashInput(ServerPlayer player) {
      this.trade.cashInputOpen.add(player.getUUID());
      player.openMenu(new SimpleMenuProvider((syncId, inv, p) -> new CashInputMenu(syncId, inv, this.trade, this.sideIsA), Component.literal("§e§lAdd cash")));
   }

   public ItemStack quickMoveStack(Player player, int index) {
      if (this.trade.done) {
         return ItemStack.EMPTY;
      }

      if (index >= 0 && index < 18) {
         if (index == 4) {
            return ItemStack.EMPTY;
         }

         ItemStack result = super.quickMoveStack(player, index);
         if (player instanceof ServerPlayer sp) {
            TradeManager.sideEdited(this.trade, sp);
         }

         return result;
      } else {
         if (index >= 54 && player instanceof ServerPlayer sp) {
            Slot slot = this.getSlot(index);
            ItemStack stack = slot.getItem();
            if (!stack.isEmpty()) {
               for (int i = 0; i < 17; i++) {
                  int s = slotForOfferIndex(i);
                  ItemStack target = this.container.getItem(s);
                  if (target.isEmpty()) {
                     this.container.setItem(s, stack.copy());
                     stack.setCount(0);
                     slot.set(ItemStack.EMPTY);
                     break;
                  }

                  if (ItemStack.isSameItemSameComponents(target, stack) && target.getCount() < target.getMaxStackSize()) {
                     int move = Math.min(target.getMaxStackSize() - target.getCount(), stack.getCount());
                     target.grow(move);
                     stack.shrink(move);
                     slot.set(stack);
                     if (stack.isEmpty()) {
                        break;
                     }
                  }
               }

               TradeManager.sideEdited(this.trade, sp);
               this.broadcastChanges();
            }
         }

         return ItemStack.EMPTY;
      }
   }

   public void removed(Player player) {
      openMenus.remove(player.getUUID());
      if (player instanceof ServerPlayer sp && !this.trade.done && !this.trade.cashInputOpen.contains(sp.getUUID())) {
         TradeManager.cancel(this.trade, sp);
      }

      super.removed(player);
   }


    static class CashInputMenu extends AnvilPromptMenu {
       private final Trade trade;
       private final boolean sideIsA;
       private boolean didReopen = false;
    
       CashInputMenu(int syncId, Inventory playerInventory, Trade trade, boolean sideIsA) {
          super(
             syncId,
             playerInventory,
             new ItemStack(Items.GOLD_INGOT),
             helper(
                "§eAdd cash to the trade",
                new String[]{"§7Type an amount, e.g. §a1000§7, §a10k§7, §a1m§7.", "", "§7The money is only moved when", "§7both players accept."}
             )
          );
          this.trade = trade;
          this.sideIsA = sideIsA;
          this.returnInputOnCancel = false;
       }
    
       protected boolean canAccept(String text) {
          return PriceUtil.parse(text) > 0L;
       }
    
       protected void renderResult(ItemStack result, String text) {
          long amount = PriceUtil.parse(text);
          result.set(DataComponents.CUSTOM_NAME, Component.literal(amount > 0L ? "§aAdd " + Chat.moneyStr(amount) + " to the trade" : "§cType an amount"));
       }
    
       protected void accept(ServerPlayer player, String text) {
          long amount = PriceUtil.parse(text);
          if (amount > 0L) {
             TradeManager.setCash(this.trade, player, amount);
          }
    
          this.didReopen = true;
          this.trade.cashInputOpen.remove(player.getUUID());
       }
    
       protected void reopen(ServerPlayer player) {
          if (this.didReopen) {
             this.reopenTradeWindow(player);
          }
       }
    
       public void removed(Player player) {
          super.removed(player);
          if (player instanceof ServerPlayer sp && !this.trade.done && !this.didReopen) {
             this.trade.cashInputOpen.remove(sp.getUUID());
             this.reopenTradeWindow(sp);
          }
       }
    
       private void reopenTradeWindow(ServerPlayer player) {
          if (!this.trade.done && TradeManager.isTrading(player) && player.connection != null) {
             player.openMenu(
                new SimpleMenuProvider(
                   (syncId, inv, p) -> new TradeMenu(syncId, inv, this.trade, this.sideIsA),
                   Component.literal("§d§lTrading with §f" + (this.sideIsA ? this.trade.b.getName().getString() : this.trade.a.getName().getString()))
                )
             );
          }
       }
    }
}
