package com.fortuneandfavors.menu;

import com.fortuneandfavors.ModSounds;
import com.fortuneandfavors.economy.ChestShopManager;
import com.fortuneandfavors.economy.ChestShopManager.ChestShop;
import com.fortuneandfavors.economy.ChestShopManager.ItemTotal;
import com.fortuneandfavors.economy.ChestShopManager.LedgerEntry;
import com.fortuneandfavors.util.Chat;
import com.fortuneandfavors.util.GuiUtil;
import com.fortuneandfavors.util.InventoryHelper;
import com.fortuneandfavors.util.SoundUtil;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.HashedStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
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
import net.minecraft.world.level.block.entity.ChestBlockEntity;

/**
 * The owner-facing sales ledger for a chest shop.
 *
 * <p>It answers the two questions a shop owner actually has: <i>what sells?</i>
 * (the Top Sellers tab) and <i>what sold, when and for how much?</i> (the Recent
 * tab). The restock-alert toggle lives here too, so turning notifications on and
 * off is one click from where the owner is already looking.
 *
 * <p>Read-only apart from the toggle and the (two-step) clear; the shop's stock
 * is managed from the chest itself.
 */
public class ShopLedgerMenu extends ChestMenu {
   private static final int SUMMARY = 4;
   private static final int TAB_RECENT = 2;
   private static final int TAB_TOP = 3;
   private static final int ALERTS = 6;
   private static final int PREV = 0;
   private static final int NEXT = 8;
   private static final int CLEAR = 53;
   /** The unframed interior of a 6-row chest: rows 1-4, columns 1-7. */
   private static final int[] LIST_SLOTS = listSlots();
   private static final int PER_PAGE = LIST_SLOTS.length;

   private static int[] listSlots() {
      List<Integer> slots = new ArrayList<>();
      for (int row = 1; row <= 4; row++) {
         for (int col = 1; col <= 7; col++) {
            slots.add(row * 9 + col);
         }
      }

      int[] out = new int[slots.size()];
      for (int i = 0; i < out.length; i++) {
         out[i] = slots.get(i);
      }

      return out;
   }

   private final SimpleContainer container;
   private final ServerPlayer owner;
   private final ChestBlockEntity chest;
   private final ChestShop shop;
   private boolean topMode;
   private int page;
   /** Two-step clear: the first click arms, the second one clears. Any other
    *  click disarms, so a stray click can never wipe the history. */
   private boolean clearArmed;

   public ShopLedgerMenu(int syncId, Inventory playerInventory, ChestBlockEntity chest, ChestShop shop) {
      this(syncId, playerInventory, chest, shop, new SimpleContainer(54));
   }

   private ShopLedgerMenu(
      int syncId, Inventory playerInventory, ChestBlockEntity chest, ChestShop shop, SimpleContainer container
   ) {
      super(MenuType.GENERIC_9x6, syncId, playerInventory, container, 6);
      this.container = container;
      this.owner = (ServerPlayer)playerInventory.player;
      this.chest = chest;
      this.shop = shop;
      this.rebuild();
   }

   public static void open(ServerPlayer player, ChestBlockEntity chest, ChestShop shop) {
      player.openMenu(
         new SimpleMenuProvider(
            (syncId, inv, p) -> new ShopLedgerMenu(syncId, inv, chest, shop), Component.literal("§6§lSales Ledger")
         )
      );
   }

   private void rebuild() {
      this.container.clearContent();
      GuiUtil.frames(this.container, 6, Items.STAINED_GLASS_PANE.gray());

      int count = this.shop.ledger.size();
      int pages = Math.max(1, (int)Math.ceil(count / (double)PER_PAGE));
      this.page = Math.max(0, Math.min(this.page, pages - 1));

      this.container.setItem(SUMMARY, this.summary(pages));

      ItemStack recent = this.button(
         Items.CLOCK,
         (this.topMode ? "§7" : "§a§l") + "Recent Sales",
         "§7The newest trades first.",
         this.topMode ? "§8Click to view" : "§7Showing now"
      );
      this.container.setItem(TAB_RECENT, recent);

      ItemStack top = this.button(
         Items.HOPPER,
         (this.topMode ? "§a§l" : "§7") + "Top Sellers",
         "§7What actually moves: totals per item.",
         this.topMode ? "§7Showing now" : "§8Click to view"
      );
      this.container.setItem(TAB_TOP, top);

      this.container.setItem(ALERTS, this.alertsButton());

      if (this.page > 0) {
         this.container.setItem(PREV, this.button(Items.ARROW, "§e§lPrevious Page", "§7Page " + this.page + " of " + pages));
      }

      if (this.page < pages - 1) {
         this.container.setItem(
            NEXT, this.button(Items.ARROW, "§e§lNext Page", "§7Page " + (this.page + 2) + " of " + pages)
         );
      }

      List<ItemStack> lines = this.topMode ? this.topStacks() : this.recentStacks();
      int start = this.page * PER_PAGE;

      for (int i = 0; i < PER_PAGE && start + i < lines.size(); i++) {
         this.container.setItem(LIST_SLOTS[i], lines.get(start + i));
      }

      this.container.setItem(
         CLEAR,
         this.clearArmed
            ? this.button(
               Items.LAVA_BUCKET,
               "§4§lCONFIRM CLEAR?",
               "§cThis erases this shop's whole sales ledger.",
               "§7Your prices, stock and mode are untouched.",
               "§8Click again to confirm, or click anywhere else to cancel."
            )
            : this.button(
               Items.BARRIER,
               "§cClear Ledger",
               "§7Forget every recorded sale for this shop.",
               "§8Click twice to confirm."
            )
      );
   }

   private ItemStack summary(int pages) {
      List<String> lore = new ArrayList<>();
      lore.add("§7Trades recorded: §f" + this.shop.ledger.size() + " §8(cap " + ChestShopManager.LEDGER_LIMIT + ")");
      if (this.shop.isItemCurrency()) {
         // An item-currency shop's two directions are different units; show them
         // apart rather than adding unlike numbers together.
         lore.add("§7Sold: §f" + this.amount(ChestShopManager.LEDGER_SOLD, ChestShopManager.ledgerTotal(this.shop, ChestShopManager.LEDGER_SOLD)));
         lore.add("§7Bought goods worth: §a" + Chat.moneyStr(ChestShopManager.ledgerTotal(this.shop, ChestShopManager.LEDGER_BOUGHT)));
      } else {
         lore.add("§7Total moved: §a" + Chat.moneyStr(ChestShopManager.ledgerTotal(this.shop)));
      }
      List<ItemTotal> top = ChestShopManager.topItems(this.shop, 1);
      if (top.isEmpty()) {
         lore.add("§8No sales yet - open for business!");
      } else {
         ItemTotal best = top.get(0);
         lore.add("§7Best seller: §f" + best.name() + " §8(" + best.qty() + " sold)");
      }

      lore.add("§8Page " + (this.page + 1) + " of " + pages);
      return this.button(Items.BOOK, "§6§lSales Ledger", lore.toArray(new String[0]));
   }

   private ItemStack alertsButton() {
      boolean on = this.shop.restockAlerts;
      return this.button(
         on ? Items.BELL : Items.STICK,
         on ? "§a§lRestock Alerts: ON" : "§c§lRestock Alerts: OFF",
         "§7When a buy shop sells its last item of a line,",
         "§7you get a chat warning so you can restock.",
         "§8Click to turn " + (on ? "off" : "on") + ".",
         "§8Also: §f/chestshop alerts"
      );
   }

   private List<ItemStack> recentStacks() {
      List<ItemStack> out = new ArrayList<>();

      for (LedgerEntry entry : ChestShopManager.recent(this.shop, ChestShopManager.LEDGER_LIMIT)) {
         ItemStack icon = ChestShopManager.ledgerIcon(entry);
         boolean sold = ChestShopManager.LEDGER_SOLD.equals(entry.kind);
         String headline = sold
            ? "§aSold §f" + entry.qty + "x §r" + entry.name
            : "§eBought §f" + entry.qty + "x §r" + entry.name;
         List<String> lore = new ArrayList<>();
         lore.add(sold ? "§7A customer bought from your shop." : "§7A customer sold into your shop.");
         lore.add("§7" + (sold ? "Buyer" : "Seller") + ": §f" + entry.who);
         lore.add(sold ? "§7Paid: §a" + this.amount(entry.kind, entry.total) : "§7Goods worth: §a" + this.amount(entry.kind, entry.total));
         lore.add("§8" + ChestShopManager.ago(entry.time));
         icon.set(DataComponents.CUSTOM_NAME, Component.literal(headline));
         icon.set(DataComponents.LORE, new ItemLore(this.literal(lore)));
         out.add(icon);
      }

      return out;
   }

   private List<ItemStack> topStacks() {
      List<ItemStack> out = new ArrayList<>();

      for (ItemTotal total : ChestShopManager.topItems(this.shop, ChestShopManager.LEDGER_LIMIT)) {
         ItemStack icon = ChestShopManager.ledgerIconRaw(total.itemId());
         List<String> lore = new ArrayList<>();
         lore.add("§7Units moved: §f" + total.qty());
         lore.add("§7Value moved: §a" + this.amount(this.dealKind(), total.total()));
         lore.add("§8Totals across the whole ledger.");
         icon.set(DataComponents.CUSTOM_NAME, Component.literal("§f" + total.name()));
         icon.set(DataComponents.LORE, new ItemLore(this.literal(lore)));
         out.add(icon);
      }

      return out;
   }

   private List<Component> literal(List<String> lines) {
      List<Component> out = new ArrayList<>();
      for (String line : lines) {
         out.add(Component.literal(line));
      }
      return out;
   }

   /**
    * Formats a ledger amount. A sale's figure is what the customer paid, so in an
    * item-currency shop it is a count of currency items; a purchase's figure is the
    * cash value of the goods taken in, which reads as money either way.
    */
   private String amount(String kind, long value) {
      if (ChestShopManager.LEDGER_SOLD.equals(kind) && this.shop.isItemCurrency()) {
         return value + "x " + this.currencyName();
      }
      return Chat.moneyStr(value);
   }

   /** The direction this shop currently trades in, used to label Top Sellers. */
   private String dealKind() {
      return ChestShopManager.TYPE_SELL.equals(this.shop.type)
         ? ChestShopManager.LEDGER_BOUGHT
         : ChestShopManager.LEDGER_SOLD;
   }

   private String currencyName() {
      if (this.shop.isTokenCurrency()) {
         return "Token";
      }
      Item item = this.shop.currencyItem();
      return item != null && item != Items.AIR ? new ItemStack(item).getHoverName().getString() : "?";
   }

   private ItemStack button(Item item, String name, String... lore) {
      ItemStack stack = new ItemStack(item);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
      List<String> lines = new ArrayList<>();
      for (String line : lore) {
         if (line != null && !line.isEmpty()) {
            lines.add(line);
         }
      }
      if (!lines.isEmpty()) {
         stack.set(DataComponents.LORE, new ItemLore(this.literal(lines)));
      }
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
      if (!(player instanceof ServerPlayer sp)) {
         super.clicked(slotId, button, input, player);
         return;
      }

      // The player's own inventory slots keep working normally - the ledger is
      // read-only, not a trap.
      if (slotId >= 54) {
         super.clicked(slotId, button, input, player);
         return;
      }

      // Any click that is not the clear button cancels an armed clear.
      if (slotId != CLEAR && this.clearArmed) {
         this.clearArmed = false;
         this.rebuild();
         this.broadcastChanges();
      }

      this.returnCarried(sp);

      switch (slotId) {
         case TAB_RECENT:
            this.topMode = false;
            this.page = 0;
            this.refresh();
            return;
         case TAB_TOP:
            this.topMode = true;
            this.page = 0;
            this.refresh();
            return;
         case ALERTS:
            this.shop.restockAlerts = !this.shop.restockAlerts;
            this.save();
            Chat.msg(
               sp,
               this.shop.restockAlerts
                  ? "&aRestock alerts on. &7You'll be warned when a line sells out."
                  : "&cRestock alerts off. &7The ledger still records every sale."
            );
            this.refresh();
            return;
         case PREV:
            if (this.page > 0) {
               this.page--;
               this.refresh();
            }
            return;
         case NEXT:
            this.page++;
            this.refresh();
            return;
         case CLEAR:
            if (!this.clearArmed) {
               this.clearArmed = true;
               this.refresh();
               return;
            }

            this.shop.ledger.clear();
            this.clearArmed = false;
            this.page = 0;
            this.save();
            Chat.msg(sp, "&cLedger cleared. &7New sales will be recorded from here.");
            SoundUtil.play(sp, ModSounds.PAGE_FLIP);
            this.refresh();
            return;
         default:
            // The ledger is read-only: nothing else here is clickable, so a
            // stray click simply does nothing rather than moving items around.
      }
   }

   private void refresh() {
      this.rebuild();
      this.broadcastChanges();
   }

   private void save() {
      MinecraftServer server = this.owner.level().getServer();
      ChestShopManager.save(server);
   }

   public ItemStack quickMoveStack(Player player, int index) {
      return ItemStack.EMPTY;
   }
}
