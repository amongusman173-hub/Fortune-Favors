package com.fortuneandfavors.menu;

import com.fortuneandfavors.ModItems;
import com.fortuneandfavors.economy.BlockValues;
import com.fortuneandfavors.economy.ChestShopManager;
import com.fortuneandfavors.economy.EconomyManager;
import com.fortuneandfavors.economy.ChestShopManager.ChestShop;
import com.fortuneandfavors.util.Chat;
import com.fortuneandfavors.util.ChatCoalescer;
import com.fortuneandfavors.util.InventoryHelper;
import com.fortuneandfavors.util.ItemOriginals;
import java.util.ArrayList;
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
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

public class ChestShopMenu extends ChestMenu {
   private static final int SELL_AREA_START = 0;
   private static final int SELL_AREA_SIZE = 9;
   private static final int SELL_BUTTON = 45;
   private static final int CLOSE = 53;
   private final SimpleContainer container;
   private final ChestBlockEntity chest;
   private final ChestShop shop;
   private final boolean sellMode;
   private final ItemOriginals originals = new ItemOriginals();

   public ChestShopMenu(int syncId, Inventory playerInventory, ChestBlockEntity chest, ChestShop shop, boolean sellMode) {
      this(syncId, playerInventory, chest, shop, sellMode, new SimpleContainer(54));
   }

   private ChestShopMenu(int syncId, Inventory playerInventory, ChestBlockEntity chest, ChestShop shop, boolean sellMode, SimpleContainer container) {
      super(MenuType.GENERIC_9x6, syncId, playerInventory, container, 6);
      this.container = container;
      this.chest = chest;
      this.shop = shop;
      this.sellMode = sellMode;
      if (sellMode) {
         this.rebuildSell();
      } else {
         this.rebuildBuy();
      }
   }

   public static void open(ServerPlayer player, ChestBlockEntity chest, ChestShop shop) {
      // A closed shop stays a shop (prices, stock and protection are kept) but
      // stops trading, so customers are told why rather than shown a menu whose
      // buttons would all refuse them.
      if (!ChestShopManager.isTrading(shop)) {
         Chat.msg(player, "&cThis shop is closed right now.");
         return;
      }

      boolean sellMode = ChestShopManager.TYPE_SELL.equals(shop.type);
      player.openMenu(
         new SimpleMenuProvider(
            (syncId, inv, p) -> new ChestShopMenu(syncId, inv, chest, shop, sellMode), Component.literal(sellMode ? "§e§lSell Shop" : "§a§lBuy Shop")
         )
      );
   }

   private void rebuildBuy() {
      this.container.clearContent();
      List<ItemStack> stock = new ArrayList<>();

      for (int i = 0; i < this.chest.getContainerSize(); i++) {
         ItemStack s = this.chest.getItem(i);
         if (!s.isEmpty()) {
            stock.add(s);
         }
      }

      for (int i = 0; i < Math.min(45, stock.size()); i++) {
         ItemStack s = stock.get(i).copy();
         s.setCount(1);
         s.set(
            DataComponents.LORE,
            new ItemLore(
               List.of(
                  Component.literal("§7Price: §a$" + this.shop.priceFor(s.getItem()) + "§7 each"),
                  Component.literal("§7Stock: §f" + stock.get(i).getCount()),
                  Component.literal("§8Click to buy 1 · Shift-click for a stack")
               )
            )
         );
         this.container.setItem(i, s);
      }

      this.container.setItem(45, this.infoStack(Items.GOLD_INGOT, "§aYour balance", "§7" + Chat.moneyStr(EconomyManager.balance(this.shop.owner))));
      this.container.setItem(46, this.infoStack(Items.EMERALD, "§ePayment", "§7" + this.currencyName()));
      this.container.setItem(53, this.infoStack(Items.BARRIER, "§cClose", ""));
   }

   private void rebuildSell() {
      this.container.clearContent();

      for (int i = 0; i < 9; i++) {
         ItemStack glass = new ItemStack(Items.STAINED_GLASS_PANE.green());
         glass.set(DataComponents.CUSTOM_NAME, Component.literal("§aPut items here to sell"));
         this.container.setItem(i, glass);
      }

      for (int i = 9; i < 45; i++) {
         this.container.setItem(i, new ItemStack(Items.AIR));
      }

      ItemStack sell = new ItemStack(Items.GOLD_INGOT);
      sell.set(DataComponents.CUSTOM_NAME, Component.literal("§a§lSELL"));
      sell.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("§7Sell all items above for their value"))));
      this.container.setItem(45, sell);
      this.container.setItem(46, this.infoStack(Items.EMERALD, "§eYou receive", "§7" + this.currencyName()));
      this.container.setItem(47, this.infoStack(Items.PAPER, "§fPrices", "§7Sell value from block_values.json"));
      this.container.setItem(53, this.infoStack(Items.BARRIER, "§cClose", ""));
   }

   private ItemStack infoStack(Item item, String name, String lore) {
      ItemStack stack = new ItemStack(item);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
      stack.set(DataComponents.LORE, new ItemLore(List.of(Component.literal(lore))));
      return stack;
   }

   private String currencyName() {
      if (this.shop.isItemCurrency()) {
         if (this.shop.isTokenCurrency()) {
            return "Token";
         }

         Item item = this.shop.currencyItem();
         return item != null && item != Items.AIR ? new ItemStack(item).getHoverName().getString() : "unknown item";
      } else {
         return "Cash";
      }
   }

   private long itemCurrencyValue(ChestShop shop) {
      Item item = shop.currencyItem();
      return item != null && item != Items.AIR ? BlockValues.valueOf(item) : 0L;
   }

   private void clearCarried() {
      this.setCarried(ItemStack.EMPTY);
      this.setRemoteCarried(HashedStack.EMPTY);
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
         if (this.sellMode) {
            this.handleSellClick(sp, slotId, button, input);
         } else {
            this.handleBuyClick(sp, slotId, input);
         }
      } else {
         super.clicked(slotId, button, input, player);
      }
   }

   private void handleBuyClick(ServerPlayer player, int slotId, ContainerInput input) {
      if (slotId >= 0 && slotId < 45) {
         if (input == ContainerInput.PICKUP || input == ContainerInput.QUICK_MOVE) {
            this.buy(player, slotId, input == ContainerInput.QUICK_MOVE);
         }

         this.returnCarried(player);
      } else if (slotId == 53) {
         this.returnCarried(player);
         player.closeContainer();
      } else if (slotId >= 0 && slotId < 54) {
         this.returnCarried(player);
      } else if (input != ContainerInput.QUICK_MOVE && input != ContainerInput.CLONE) {
         super.clicked(slotId, input.id(), input, player);
      } else {
         this.returnCarried(player);
      }
   }

   private void buy(ServerPlayer player, int displaySlot, boolean shift) {
      List<ItemStack> stock = new ArrayList<>();

      for (int i = 0; i < this.chest.getContainerSize(); i++) {
         ItemStack s = this.chest.getItem(i);
         if (!s.isEmpty()) {
            stock.add(s);
         }
      }

      if (displaySlot >= stock.size()) {
         this.returnCarried(player);
      } else {
         ItemStack target = stock.get(displaySlot);
         if (ModItems.isSpawnerItem(target)) {
            this.buySpawner(player, displaySlot, this.priceSpawner(target));
            this.rebuildBuy();
            this.broadcastChanges();
         } else {
            Item item = target.getItem();
            long price = this.shop.priceFor(item);
            int want = shift ? Math.min(target.getCount(), target.getMaxStackSize()) : 1;
            int buyCount = Math.min(want, this.countInChest(item));
            if (buyCount <= 0) {
               Chat.msg(player, "&cThat item is out of stock.");
               this.returnCarried(player);
            } else {
               long total = price * buyCount;
               if (!ChestShopManager.payFrom(player, this.shop, total)) {
                  this.returnCarried(player);
               } else {
                  int removed = this.removeFromChest(item, buyCount);
                  if (removed > 0) {
                     ChestShopManager.payTo(player, this.shop, total, this.shop.owner);
                     ItemStack give = new ItemStack(item, removed);
                     InventoryHelper.giveOrDrop(player, give);
                     ChatCoalescer.buyMessage(player, give, removed, total);
                     ChestShopManager.record(
                        this.shop, ChestShopManager.LEDGER_SOLD, give, removed, total, player.getName().getString()
                     );
                     // The sale that emptied this line is the moment the owner
                     // needs to know: tell them now, not next time they look.
                     if (this.countInChest(item) <= 0) {
                        ChestShopManager.alertOutOfStock(
                           this.shop, give.getHoverName().getString(), ChestShopManager.itemIdOf(give), player.level().getServer()
                        );
                     }
                     ChestShopManager.saveThrottled(player.level().getServer(), 5000L);
                  } else {
                     ChestShopManager.payOut(player, this.shop, total);
                  }

                  this.rebuildBuy();
                  this.broadcastChanges();
               }
            }
         }
      }
   }

   private long priceSpawner(ItemStack spawner) {
      return this.shop.priceFor(spawner.getItem());
   }

   private void buySpawner(ServerPlayer player, int displaySlot, long price) {
      if (!ChestShopManager.payFrom(player, this.shop, price)) {
         this.returnCarried(player);
      } else {
         int chestSlot = -1;
         int nonEmpty = 0;

         for (int i = 0; i < this.chest.getContainerSize(); i++) {
            if (!this.chest.getItem(i).isEmpty()) {
               if (nonEmpty == displaySlot) {
                  chestSlot = i;
                  break;
               }

               nonEmpty++;
            }
         }

         if (chestSlot < 0) {
            ChestShopManager.payOut(player, this.shop, price);
            this.returnCarried(player);
         } else {
            ItemStack inChest = this.chest.getItem(chestSlot);
            if (!inChest.isEmpty() && ModItems.isSpawnerItem(inChest)) {
               ItemStack give = inChest.copy();
               inChest.shrink(1);
               this.chest.setChanged();
               ModItems.bindSpawner(give, player.getUUID(), player.getName().getString());
               ChestShopManager.payTo(player, this.shop, price, this.shop.owner);
               InventoryHelper.giveOrDrop(player, give);
               ChatCoalescer.buyMessage(player, give, 1, price);
               ChestShopManager.record(
                  this.shop, ChestShopManager.LEDGER_SOLD, give, 1, price, player.getName().getString()
               );
               if (!this.chestHasSpawner()) {
                  ChestShopManager.alertOutOfStock(
                     this.shop, give.getHoverName().getString(), ChestShopManager.itemIdOf(give), player.level().getServer()
                  );
               }
               ChestShopManager.saveThrottled(player.level().getServer(), 5000L);
            } else {
               ChestShopManager.payOut(player, this.shop, price);
               this.returnCarried(player);
            }
         }
      }
   }

   private int countInChest(Item item) {
      int count = 0;

      for (int i = 0; i < this.chest.getContainerSize(); i++) {
         ItemStack s = this.chest.getItem(i);
         if (s.is(item)) {
            count += s.getCount();
         }
      }

      return count;
   }

   /** True while any spawner token is still in the shop's chest. */
   private boolean chestHasSpawner() {
      for (int i = 0; i < this.chest.getContainerSize(); i++) {
         if (ModItems.isSpawnerItem(this.chest.getItem(i))) {
            return true;
         }
      }

      return false;
   }

   private int removeFromChest(Item item, int count) {
      int removed = 0;

      for (int i = 0; i < this.chest.getContainerSize() && removed < count; i++) {
         ItemStack s = this.chest.getItem(i);
         if (s.is(item)) {
            int take = Math.min(s.getCount(), count - removed);
            s.shrink(take);
            removed += take;
         }
      }

      this.chest.setChanged();
      return removed;
   }

   private void handleSellClick(ServerPlayer player, int slotId, int button, ContainerInput input) {
      if (slotId != 45) {
         if (slotId >= 0 && slotId < 9) {
            if (this.container.getItem(slotId).is((Item)Items.STAINED_GLASS_PANE.green())) {
               this.returnCarried(player);
            } else if (input == ContainerInput.QUICK_MOVE) {
               ItemStack original = this.originals.take(slotId, this.container.getItem(slotId));
               this.container.setItem(slotId, ItemStack.EMPTY);
               if (!original.isEmpty()) {
                  InventoryHelper.giveOrDrop(player, original);
               }

               this.rebuildSellArea();
               this.broadcastChanges();
            } else if (input != ContainerInput.PICKUP && input != ContainerInput.SWAP) {
               this.returnCarried(player);
            } else {
               ItemStack original = this.originals.take(slotId, this.container.getItem(slotId));
               super.clicked(slotId, input.id(), input, player);
               if (!original.isEmpty()) {
                  if (input == ContainerInput.PICKUP) {
                     this.setCarried(ItemStack.EMPTY);
                     this.setRemoteCarried(HashedStack.EMPTY);
                     InventoryHelper.giveOrDrop(player, original);
                  } else if (button >= 0 && button < 9) {
                     player.getInventory().setItem(button, original);
                  }
               }

               this.rebuildSellArea();
               this.broadcastChanges();
            }
         } else if (slotId == 53) {
            this.returnCarried(player);
            player.closeContainer();
         } else if (slotId >= 0 && slotId < 54) {
            this.returnCarried(player);
         } else if (input != ContainerInput.QUICK_MOVE && input != ContainerInput.CLONE) {
            super.clicked(slotId, input.id(), input, player);
         } else {
            this.returnCarried(player);
         }
      } else {
         if (input == ContainerInput.PICKUP || input == ContainerInput.QUICK_MOVE) {
            this.processSell(player);
         }

         this.returnCarried(player);
      }
   }

   private void rebuildSellArea() {
      long runningTotal = 0L;
      boolean unsellable = false;

      for (int i = 0; i < 9; i++) {
         ItemStack s = this.container.getItem(i);
         if (!s.isEmpty() && !s.is((Item)Items.STAINED_GLASS_PANE.green())) {
            ItemStack real = this.originals.get(i, s);
            this.originals.remember(i, real);
            long value = BlockValues.valueOf(real);
            if (value <= 0L) {
               unsellable = true;
            } else {
               runningTotal += value;
            }

            ItemStack display = real.copy();
            display.set(
               DataComponents.LORE,
               new ItemLore(
                  List.of(
                     Component.literal(value > 0L ? "§7Value: §a$" + value : "§cNo sell value"),
                     Component.literal("§8Shift-click to take back · items sell on §a§lSELL")
                  )
               )
            );
            this.container.setItem(i, display);
         } else {
            this.originals.forget(i);
            if (s.is((Item)Items.STAINED_GLASS_PANE.green())) {
               ItemStack glass = new ItemStack(Items.STAINED_GLASS_PANE.green());
               glass.set(DataComponents.CUSTOM_NAME, Component.literal("§aPut items here to sell"));
               this.container.setItem(i, glass);
            }
         }
      }

      // Live total on the SELL button so the deal is obvious before clicking.
      ItemStack sell = new ItemStack(Items.GOLD_INGOT);
      sell.set(DataComponents.CUSTOM_NAME, Component.literal(runningTotal > 0L ? "§a§lSELL §f§l" + Chat.moneyStr(runningTotal) : "§a§lSELL"));
      List<Component> sellLore = new ArrayList<>();
      sellLore.add(Component.literal("§7Sell everything in the green slots"));
      sellLore.add(Component.literal("§7for " + this.currencyName() + "."));
      sellLore.add(Component.literal(runningTotal > 0L ? "§7Current offer: §a" + this.payoutPreview(runningTotal) : "§8Drop items above to see the offer"));
      if (unsellable) {
         sellLore.add(Component.literal("§cSome items have no value here"));
      }

      sell.set(DataComponents.LORE, new ItemLore(sellLore));
      this.container.setItem(45, sell);
   }

   /** Short human summary of what the seller would receive for the given cash total. */
   private String payoutPreview(long cashTotal) {
      if (!this.shop.isItemCurrency()) {
         return Chat.moneyStr(cashTotal);
      }

      long unitValue = this.shop.isTokenCurrency() ? 1000L : this.itemCurrencyValue(this.shop);
      if (unitValue <= 0L) {
         return "?";
      }

      long count = cashTotal / unitValue;
      return count + "x " + this.currencyName();
   }

   private void processSell(ServerPlayer player) {
      long total = 0L;
      boolean any = false;

      for (int i = 0; i < 9; i++) {
         ItemStack s = this.container.getItem(i);
         if (!s.isEmpty() && !s.is((Item)Items.STAINED_GLASS_PANE.green())) {
            long value = BlockValues.valueOf(s);
            if (value <= 0L) {
               Chat.msg(player, "&c" + s.getHoverName().getString() + " has no value.");
            } else {
               total += value;
               any = true;
            }
         }
      }

      if (!any) {
         Chat.msg(player, "&cPut items in the green slots first!");
         this.clearCarried();
      } else {
         if (this.shop.isItemCurrency()) {
            long unitValue = this.shop.isTokenCurrency() ? 1000L : this.itemCurrencyValue(this.shop);
            long payCount = unitValue > 0L ? Math.max(1L, total / unitValue) : 0L;
            if (payCount <= 0L) {
               Chat.msg(player, "&cThis shop can't pay out in " + this.currencyName() + ".");
               this.returnCarried(player);
               return;
            }

            int totalItems = (int)Math.min(2147483647L, payCount);
            if (this.shop.isTokenCurrency()) {
               List<ItemStack> tokens = EconomyManager.removeTokensFromCollection(this.shop.owner, totalItems);
               int got = 0;

               for (ItemStack t : tokens) {
                  got += t.getCount();
               }

               if (got < totalItems) {
                  for (ItemStack t : tokens) {
                     EconomyManager.giveItem(this.shop.owner, t);
                  }

                  Chat.msg(player, "&cThe shop owner doesn't have enough Tokens to pay!");
                  this.returnCarried(player);
                  return;
               }

               for (ItemStack token : tokens) {
                  InventoryHelper.giveOrDrop(player, token);
               }

               Chat.raw(player, "§aSold items for §f" + totalItems + "x Token");
            } else {
               Item item = this.shop.currencyItem();
               if (item == null || item == Items.AIR) {
                  Chat.msg(player, "&cThis shop's payment item is invalid.");
                  this.returnCarried(player);
                  return;
               }

               if (!EconomyManager.removeFromCollection(this.shop.owner, item, totalItems)) {
                  Chat.msg(player, "&cThe shop owner doesn't have enough " + new ItemStack(item).getHoverName().getString() + " to pay!");
                  this.returnCarried(player);
                  return;
               }

               InventoryHelper.giveOrDrop(player, new ItemStack(item, totalItems));
               Chat.raw(player, "§aSold items for §f" + totalItems + "x " + new ItemStack(item).getHoverName().getString());
            }
         } else {
            if (!EconomyManager.pay(this.shop.owner, player.getUUID(), total)) {
               Chat.msg(player, "&cThe shop owner doesn't have enough money to pay you!");
               this.returnCarried(player);
               return;
            }

            Chat.raw(player, "§aSold items for " + Chat.moneyStr(total));
         }

         for (int i = 0; i < 9; i++) {
            ItemStack s = this.container.getItem(i);
            if (!s.isEmpty() && !s.is((Item)Items.STAINED_GLASS_PANE.green())) {
               ItemStack real = this.originals.get(i, s);
               this.originals.forget(i);
               this.container.setItem(i, ItemStack.EMPTY);
               // One ledger line per item type sold into the shop, so the owner
               // can see exactly what they bought and for how much. valueOf
               // already multiplies by the stack's count - do not do it twice.
               ChestShopManager.record(
                  this.shop,
                  ChestShopManager.LEDGER_BOUGHT,
                  real,
                  real.getCount(),
                  BlockValues.valueOf(real),
                  player.getName().getString()
               );
               boolean stored = this.storeInChest(real);
               if (!stored) {
                  player.drop(real, false);
               }
            }
         }

         ChestShopManager.saveThrottled(player.level().getServer(), 5000L);
         this.rebuildSell();
         this.broadcastChanges();
      }
   }

   private boolean storeInChest(ItemStack stack) {
      for (int i = 0; i < this.chest.getContainerSize(); i++) {
         ItemStack s = this.chest.getItem(i);
         if (s.isEmpty()) {
            this.chest.setItem(i, stack.copy());
            this.chest.setChanged();
            return true;
         }

         if (ItemStack.isSameItemSameComponents(s, stack) && s.getCount() < s.getMaxStackSize()) {
            int space = s.getMaxStackSize() - s.getCount();
            int move = Math.min(space, stack.getCount());
            s.grow(move);
            stack.shrink(move);
            this.chest.setChanged();
            if (stack.isEmpty()) {
               return true;
            }
         }
      }

      return stack.isEmpty();
   }

   public ItemStack quickMoveStack(Player player, int index) {
      if (this.sellMode) {
         if (index >= 0 && index < 9) {
            if (this.container.getItem(index).is((Item)Items.STAINED_GLASS_PANE.green())) {
               return ItemStack.EMPTY;
            }

            ItemStack original = this.originals.take(index, this.container.getItem(index));
            this.container.setItem(index, ItemStack.EMPTY);
            if (!original.isEmpty() && player instanceof ServerPlayer sp) {
               InventoryHelper.giveOrDrop(sp, original);
            }

            this.rebuildSellArea();
            this.broadcastChanges();
            return ItemStack.EMPTY;
         } else {
            if (index >= 54 && player instanceof ServerPlayer sp) {
               Slot slot = this.getSlot(index);
               ItemStack stack = slot.getItem();
               if (!stack.isEmpty()) {
                  for (int i = 0; i < 9; i++) {
                     ItemStack target = this.container.getItem(i);
                     if (target.isEmpty()) {
                        int move = Math.min(stack.getCount(), stack.getMaxStackSize());
                        this.container.setItem(i, stack.copyWithCount(move));
                        stack.shrink(move);
                        slot.set(stack);
                        break;
                     }

                     ItemStack real = this.originals.get(i, target);
                     if (ItemStack.isSameItemSameComponents(real, stack) && real.getCount() < real.getMaxStackSize()) {
                        int move = Math.min(real.getMaxStackSize() - real.getCount(), stack.getCount());
                        real.grow(move);
                        stack.shrink(move);
                        slot.set(stack);
                        if (stack.isEmpty()) {
                           break;
                        }
                     }
                  }

                  this.rebuildSellArea();
                  this.broadcastChanges();
               }
            }

            return ItemStack.EMPTY;
         }
      } else {
         if (index >= 0 && index < 45) {
            this.clicked(index, 0, ContainerInput.QUICK_MOVE, player);
         }

         return ItemStack.EMPTY;
      }
   }

   public void removed(Player player) {
      if (this.sellMode && player instanceof ServerPlayer sp) {
         for (int i = 0; i < 54; i++) {
            if (i != 45 && i != 46 && i != 47 && i != 53) {
               ItemStack s = this.container.getItem(i);
               if (!s.isEmpty() && !s.is((Item)Items.STAINED_GLASS_PANE.green())) {
                  this.container.setItem(i, ItemStack.EMPTY);
                  InventoryHelper.giveOrDrop(sp, this.originals.get(i, s));
               }
            }
         }
      }

      super.removed(player);
   }
}
