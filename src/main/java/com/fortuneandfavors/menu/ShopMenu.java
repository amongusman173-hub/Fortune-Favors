package com.fortuneandfavors.menu;

import com.fortuneandfavors.ModSounds;
import com.fortuneandfavors.economy.EconomyManager;
import com.fortuneandfavors.economy.ShopData;
import com.fortuneandfavors.economy.ShopProgression;
import com.fortuneandfavors.economy.ShopData.Category;
import com.fortuneandfavors.economy.ShopData.ShopEntry;
import com.fortuneandfavors.util.Chat;
import com.fortuneandfavors.util.ChatCoalescer;
import com.fortuneandfavors.util.InventoryHelper;
import com.fortuneandfavors.util.SoundUtil;
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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.ItemLike;

public class ShopMenu extends ChestMenu {
   public static final int SHOP_SLOTS = 54;
   private static final int ITEMS_START = 0;
   private static final int ITEMS_PER_PAGE = 45;
   private static final int CAT_BUILDING = 45;
   private static final int CAT_FOOD = 46;
   private static final int CAT_TOOLS = 47;
   private static final int CAT_EXCLUSIVE = 48;
   private static final int CAT_REDSTONE = 49;
   private static final int PAGE_PREV = 50;
   private static final int BALANCE = 51;
   private static final int PAGE_NEXT = 52;
   private static final int CLOSE = 53;
   private final SimpleContainer container;
   private final ServerPlayer owner;
   private Category category;
   private int page;
   private final int pageCount;

   public ShopMenu(int syncId, Inventory playerInventory, Category category, int page) {
      this(syncId, playerInventory, category, page, new SimpleContainer(54));
   }

   private ShopMenu(int syncId, Inventory playerInventory, Category category, int page, SimpleContainer container) {
      super(MenuType.GENERIC_9x6, syncId, playerInventory, container, 6);
      this.container = container;
      this.owner = (ServerPlayer)playerInventory.player;
      this.category = category;
      this.page = Math.max(0, page);
      int count = ShopData.entryCount(category);
      this.pageCount = Math.max(1, (int)Math.ceil(count / 45.0));
      if (this.page >= this.pageCount) {
         this.page = this.pageCount - 1;
      }

      this.rebuild();
   }

   public static void open(ServerPlayer player, Category category, int page) {
      player.openMenu(new SimpleMenuProvider((syncId, inv, p) -> new ShopMenu(syncId, inv, category, page), Component.literal("§6§lFortune & Favors Shop")));
   }

   private void buildHeader() {
   }

   private boolean isLocked(ShopEntry entry) {
      return ShopProgression.canBuy(this.owner.getUUID(), entry.stack().getItem()) != null;
   }

   private ItemStack lockedPlaceholder() {
      ItemStack stack = new ItemStack(Items.STAINED_GLASS_PANE.gray());
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§8???"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Not yet unlocked."),
               Component.literal("§7Discover the right materials in the"),
               Component.literal("§7world and this slot will reveal itself.")
            )
         )
      );
      return stack;
   }

   private void rebuild() {
      this.container.clearContent();
      this.buildHeader();
      List<ShopEntry> entries = ShopData.entries(this.category);
      int start = this.page * 45;

      for (int i = 0; i < 45; i++) {
         int idx = start + i;
         if (idx >= entries.size()) {
            this.container.setItem(0 + i, ItemStack.EMPTY);
         } else if (this.isLocked(entries.get(idx))) {
            this.container.setItem(0 + i, this.lockedPlaceholder());
         } else {
            this.container.setItem(0 + i, ShopData.makeDisplayStack(entries.get(idx)));
         }
      }

      this.container.setItem(45, this.categoryButton(Category.BUILDING));
      this.container.setItem(46, this.categoryButton(Category.FOOD));
      this.container.setItem(47, this.categoryButton(Category.TOOLS));
      this.container.setItem(48, this.categoryButton(Category.EXCLUSIVE));
      this.container.setItem(49, this.categoryButton(Category.REDSTONE));
      this.container.setItem(50, this.pageButton(false));
      this.container.setItem(52, this.pageButton(true));
      this.refreshBalance();
      this.container.setItem(53, this.infoStack(Items.BARRIER, "§c§lClose", "§7Leave the shop"));
   }

   private void refreshBalance() {
      ItemStack stack = new ItemStack(Items.EMERALD);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§a§lYour balance"));
      List<Component> lore = new ArrayList<>();
      lore.add(Component.literal("§7Balance: " + Chat.moneyStr(EconomyManager.balance(this.owner.getUUID()))));
      int pending = EconomyManager.collectionCount(this.owner.getUUID());
      if (pending > 0) {
         lore.add(Component.literal("§7Pending: §f" + pending + " items"));
      }

      lore.add(Component.literal("§8Page " + (this.page + 1) + "/" + this.pageCount));
      stack.set(DataComponents.LORE, new ItemLore(lore));
      this.container.setItem(51, stack);
   }

   private ItemStack categoryButton(Category c) {
      ItemStack stack = ShopData.categoryIcon(c);
      boolean current = c == this.category;
      List<Component> lore = new ArrayList<>();
      lore.add(Component.literal(current ? "§a§l● Currently open" : "§7Click to browse"));
      lore.add(Component.literal("§8" + ShopData.entryCount(c) + " items"));
      stack.set(DataComponents.LORE, new ItemLore(lore));
      if (current) {
         stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      }

      return stack;
   }

   private ItemStack pageButton(boolean next) {
      boolean enabled = next ? this.page + 1 < this.pageCount : this.page > 0;
      if (!enabled) {
         ItemStack stack = new ItemStack(Items.STAINED_GLASS_PANE.gray());
         stack.set(DataComponents.CUSTOM_NAME, Component.literal(next ? "§8Next page" : "§8Previous page"));
         stack.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("§7You're on the " + (next ? "last" : "first") + " page"))));
         return stack;
      } else {
         ItemStack stack = new ItemStack(Items.ARROW);
         stack.set(DataComponents.CUSTOM_NAME, Component.literal(next ? "§e§lNext >" : "§e§l< Prev"));
         stack.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("§7Go to page §f" + (this.page + (next ? 2 : 0)) + "§7/§f" + this.pageCount))));
         return stack;
      }
   }

   private ItemStack frame(Item item) {
      ItemStack stack = new ItemStack(item);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal(" "));
      return stack;
   }

   private ItemStack infoStack(Item item, String name, String lore) {
      ItemStack stack = new ItemStack(item);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
      stack.set(DataComponents.LORE, new ItemLore(List.of(Component.literal(lore))));
      return stack;
   }

   private void setCategory(Category category) {
      if (this.category != category) {
         this.category = category;
         this.page = 0;
         this.rebuild();
         this.broadcastChanges();
         SoundUtil.play(this.owner, ModSounds.PAGE_FLIP);
      }
   }

   public void clicked(int slotId, int button, ContainerInput input, Player player) {
      if (!(player instanceof ServerPlayer sp)) {
         super.clicked(slotId, button, input, player);
      } else if (slotId >= 0 && slotId < 54) {
         if (input == ContainerInput.PICKUP || input == ContainerInput.QUICK_MOVE) {
            this.handleSlot(sp, slotId, input == ContainerInput.QUICK_MOVE || sp.isShiftKeyDown());
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

   private void handleSlot(ServerPlayer player, int slotId, boolean shift) {
      switch (slotId) {
         case 45:
            this.setCategory(Category.BUILDING);
            return;
         case 46:
            this.setCategory(Category.FOOD);
            return;
         case 47:
            this.setCategory(Category.TOOLS);
            return;
         case 48:
            this.setCategory(Category.EXCLUSIVE);
            return;
         case 49:
            this.setCategory(Category.REDSTONE);
            return;
         case 50:
            if (this.page > 0) {
               this.page--;
               this.rebuild();
               this.broadcastChanges();
               SoundUtil.play(player, ModSounds.PAGE_FLIP);
            }

            return;
         case 51:
         default:
            if (slotId >= 0 && slotId < 45) {
               List<ShopEntry> entries = ShopData.entries(this.category);
               int idx = this.page * 45 + (slotId - 0);
               if (idx < entries.size()) {
                  ShopEntry entry = entries.get(idx);
                  if (this.isLocked(entry)) {
                     Chat.msg(player, "&7That item is still locked - discover its materials to unlock it.");
                     return;
                  }

                  this.buy(player, entry, shift ? 64 : 1);
               }
            }

            return;
         case 52:
            if (this.page + 1 < this.pageCount) {
               this.page++;
               this.rebuild();
               this.broadcastChanges();
               SoundUtil.play(player, ModSounds.PAGE_FLIP);
            }

            return;
         case 53:
            player.closeContainer();
      }
   }

   private void buy(ServerPlayer player, ShopEntry entry, int requested) {
      if (ShopData.shopBanned(entry.stack().getItem())) {
         Chat.msg(player, "&cThe shop doesn't sell that.");
      } else {
         String denial = ShopProgression.canBuy(player.getUUID(), entry.stack().getItem());
         if (denial != null) {
            Chat.msg(player, denial);
         } else {
            int amount = Math.max(1, Math.min(requested, entry.stack().getMaxStackSize()));
            long price = ShopData.purchaseTotal(player.getUUID(), entry, amount);
            if (!EconomyManager.hasCash(player.getUUID(), price)) {
               Chat.msg(player, "&cNot enough money! Need " + Chat.moneyStr(price));
            } else {
               EconomyManager.takeCash(player.getUUID(), price);
               ItemStack give = entry.stack().copy();
               give.setCount(amount);
               int given = InventoryHelper.giveOrDrop(player, give);
               this.refreshBalance();
               SoundUtil.play(player, ModSounds.BUY);
               ChatCoalescer.buyMessage(player, entry.stack(), given, price);
            }
         }
      }
   }

   public ItemStack quickMoveStack(Player player, int index) {
      if (index >= 0 && index < 54) {
         this.clicked(index, 0, ContainerInput.QUICK_MOVE, player);
      }

      return ItemStack.EMPTY;
   }
}
