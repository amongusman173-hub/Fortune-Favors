package com.fortuneandfavors.menu;

import com.fortuneandfavors.ModSounds;
import com.fortuneandfavors.economy.AuctionManager;
import com.fortuneandfavors.economy.AuctionManager.Auction;
import com.fortuneandfavors.economy.AuctionManager.Category;
import com.fortuneandfavors.economy.EconomyManager;
import com.fortuneandfavors.economy.ModConfig;
import com.fortuneandfavors.util.Chat;
import com.fortuneandfavors.util.InventoryHelper;
import com.fortuneandfavors.util.SoundUtil;
import java.util.ArrayList;
import java.util.Comparator;
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

/**
 * The Auction House's window.
 *
 * <p>The house used to be one list: every live listing, newest first, paged. That is a fine market
 * for twenty listings and an unusable one for two hundred - a player hunting iron and a player
 * hunting a sword read the same page, and the only instrument either of them had was the next-page
 * arrow. So the window is now a market with shelves and a counter:
 *
 * <ul>
 *   <li><b>Three views</b> - the whole market, your own listings, and your own bids. The bid view is
 *       the one that was genuinely missing: a player who bids and walks away had no way at all to
 *       see what their money was committed to.</li>
 *   <li><b>A shelf</b> - gear, blocks, food, materials, machines - and a <b>search</b> that matches an
 *       item's name, a seller's name or a listing's number.</li>
 *   <li><b>Listings that say which of them is about to go</b>: a ten-cell time bar coloured by how
 *       much is left, how many people are in the bidding, the seller's name, and - the line that
 *       matters most - whether it is <i>yours</i>, whether you are <i>winning</i>, or whether you have
 *       been quietly <i>outbid</i>.</li>
 *   <li><b>Bid buttons that are worth pressing</b>: the minimum with a left click on BID, ten percent
 *       over it with a left click on the button beside it, and an amount of your own typed into the
 *       custom-bid prompt a right click on BID opens.</li>
 * </ul>
 */
public class AuctionMenu extends ChestMenu {
   private static final int ITEMS_START = 9;
   private static final int ITEMS_PER_PAGE = 36;
   private static final int SORT = 0;
   private static final int CATEGORY = 1;
   private static final int SEARCH = 2;
   private static final int TITLE = 4;
   private static final int CLEAR = 5;
   private static final int HELP = 7;
   private static final int VIEW = 8;
   private static final int UI_BALANCE = 45;
   private static final int UI_PRIMARY = 46;
   private static final int UI_SECONDARY = 47;
   private static final int UI_REFRESH = 48;
   private static final int UI_SELECTED = 49;
   private static final int UI_SELL_HELD = 50;
   private static final int PAGE_PREV = 51;
   private static final int PAGE_NEXT = 52;
   private static final int UI_CLOSE = 53;
   private final SimpleContainer container;
   private final ServerPlayer owner;
   private final List<Auction> auctions = new ArrayList<>();
   private int selectedId = -1;
   private SortMode sort = SortMode.NEWEST;
   private Category category = Category.ALL;
   private String query = "";
   private View view = View.BROWSE;
   private int page = 0;
   private int pageCount = 1;

   public AuctionMenu(int syncId, Inventory playerInventory) {
      this(syncId, playerInventory, new SimpleContainer(54));
   }

   private AuctionMenu(int syncId, Inventory playerInventory, SimpleContainer container) {
      super(MenuType.GENERIC_9x6, syncId, playerInventory, container, 6);
      this.container = container;
      this.owner = (ServerPlayer)playerInventory.player;
      this.refresh();
   }

   public static void open(ServerPlayer player) {
      open(player, "", View.BROWSE);
   }

   /** Opens the window on a search and a view, so a command can land a player where it says it will. */
   public static void open(ServerPlayer player, String query, View view) {
      String q = query == null ? "" : query.trim();
      View v = view == null ? View.BROWSE : view;
      player.openMenu(new SimpleMenuProvider(
         (syncId, inv, p) -> {
            AuctionMenu menu = new AuctionMenu(syncId, inv);
            menu.query = q;
            menu.view = v;
            menu.refresh();
            return menu;
         },
         Component.literal("§d§lAuction House")
      ));
   }

   private Auction selected() {
      if (this.selectedId < 0) {
         return null;
      }

      Auction a = AuctionManager.get(this.selectedId);
      return a != null && !a.finished ? a : null;
   }

   private long currentPrice(Auction a) {
      return AuctionManager.askingPrice(a);
   }

   private void refresh() {
      this.auctions.clear();
      List<Auction> pool = switch (this.view) {
         case MINE -> AuctionManager.ownAuctions(this.owner.getUUID());
         case BIDS -> AuctionManager.auctionsBiddingOn(this.owner.getUUID());
         default -> AuctionManager.search(this.query);
      };
      // A search and a shelf are two filters and they compose: searching "iron" on the Materials
      // shelf is the one query a player makes most often.
      for (Auction a : pool) {
         if (this.category == Category.ALL || AuctionManager.categoryOf(a.item) == this.category) {
            this.auctions.add(a);
         }
      }

      this.sortAuctions();
      if (this.selected() == null) {
         this.selectedId = -1;
      }

      this.pageCount = Math.max(1, (int)Math.ceil(this.auctions.size() / (double)ITEMS_PER_PAGE));
      if (this.page >= this.pageCount) {
         this.page = this.pageCount - 1;
      }
      if (this.page < 0) {
         this.page = 0;
      }

      this.rebuild();
   }

   private void sortAuctions() {
      switch (this.sort) {
         case NEWEST -> this.auctions.sort(Comparator.comparingInt(a -> -a.id));
         case PRICE_LOW -> this.auctions.sort(Comparator.comparingLong(AuctionManager::askingPrice));
         case PRICE_HIGH -> this.auctions.sort(Comparator.comparingLong((Auction a) -> -AuctionManager.askingPrice(a)));
         case ENDING_SOON -> this.auctions.sort(Comparator.comparingLong(AuctionManager::timeLeftTicks));
         case MOST_BIDS -> this.auctions.sort(
            Comparator.comparingInt((Auction a) -> -AuctionManager.bidderCount(a)).thenComparingInt(a -> -a.id)
         );
      }
   }

   private void buildHeader() {
      Item dark = Items.STAINED_GLASS_PANE.black();
      Item magenta = Items.STAINED_GLASS_PANE.magenta();
      int[] slots = new int[]{3, 6};
      Item[] pane = new Item[]{magenta, dark};
      for (int i = 0; i < slots.length; i++) {
         this.container.setItem(slots[i], this.frame(pane[i]));
      }

      this.container.setItem(SORT, this.sortButton());
      this.container.setItem(CATEGORY, this.categoryButton());
      this.container.setItem(SEARCH, this.searchButton());
      this.container.setItem(VIEW, this.viewButton());
      this.container.setItem(HELP, this.helpButton());
      if (this.hasFilter()) {
         this.container.setItem(CLEAR, this.clearButton());
      }
      this.container.setItem(TITLE, this.titleButton());
   }

   private boolean hasFilter() {
      return !this.query.isEmpty() || this.category != Category.ALL;
   }

   private ItemStack titleButton() {
      int active = AuctionManager.activeAuctions().size();
      int mine = AuctionManager.ownAuctions(this.owner.getUUID()).size();
      int bids = AuctionManager.auctionsBiddingOn(this.owner.getUUID()).size();
      ItemStack title = new ItemStack(Items.END_CRYSTAL);
      title.set(DataComponents.CUSTOM_NAME, Component.literal("§d§lAuction House"));
      List<Component> lore = new ArrayList<>();
      lore.add(Component.literal("§7Showing: §f" + this.auctions.size() + "§7 listing(s)"
         + (this.pageCount > 1 ? " §8(page " + (this.page + 1) + "/" + this.pageCount + ")" : "")));
      if (!this.query.isEmpty()) {
         lore.add(Component.literal("§7Search: §f" + this.query));
      }
      if (this.category != Category.ALL) {
         lore.add(Component.literal("§7Shelf: " + this.category.label));
      }
      lore.add(Component.literal(""));
      lore.add(Component.literal("§7Live on the market: §f" + active));
      lore.add(Component.literal("§7Your listings: §f" + mine));
      lore.add(Component.literal("§7You are bidding on: §f" + bids));
      lore.add(Component.literal("§8Click an item below to select it."));
      title.set(DataComponents.LORE, new ItemLore(lore));
      return title;
   }

   private ItemStack sortButton() {
      ItemStack stack = new ItemStack(Items.HOPPER);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§f§lSort"));
      List<Component> lore = new ArrayList<>();
      for (SortMode mode : SortMode.values()) {
         lore.add(Component.literal((mode == this.sort ? "§a▸ " : "§8  ") + mode.label));
      }
      lore.add(Component.literal("§8Click to cycle"));
      stack.set(DataComponents.LORE, new ItemLore(lore));
      return stack;
   }

   private ItemStack categoryButton() {
      ItemStack stack = new ItemStack(Items.BOOKSHELF);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§f§lShelf: " + this.category.label));
      List<Component> lore = new ArrayList<>();
      for (Category c : Category.values()) {
         lore.add(Component.literal((c == this.category ? "§a▸ " : "§8  ") + c.label));
      }
      lore.add(Component.literal("§8Click to cycle · §7right-click to go back"));
      stack.set(DataComponents.LORE, new ItemLore(lore));
      return stack;
   }

   private ItemStack searchButton() {
      ItemStack stack = new ItemStack(this.query.isEmpty() ? Items.NAME_TAG : Items.WRITABLE_BOOK);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal(
         this.query.isEmpty() ? "§f§lSearch" : "§f§lSearch: §d" + this.query
      ));
      stack.set(
         DataComponents.LORE,
         new ItemLore(List.of(
            Component.literal("§7Type part of an item's name, a seller's"),
            Component.literal("§7name, or a listing number like §f#42§7."),
            Component.literal(""),
            Component.literal(this.query.isEmpty()
               ? "§7Showing everything on this shelf."
               : "§7" + AuctionManager.search(this.query).size() + " listing(s) match."),
            Component.literal("§8Click to type · §7empty clears it")
         ))
      );
      if (!this.query.isEmpty()) {
         stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      }
      return stack;
   }

   private ItemStack viewButton() {
      ItemStack stack = new ItemStack(switch (this.view) {
         case MINE -> Items.CHEST;
         case BIDS -> Items.GOLDEN_HELMET;
         default -> Items.COMPASS;
      });
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§f§lView: " + this.view.label));
      List<Component> lore = new ArrayList<>();
      for (View v : View.values()) {
         lore.add(Component.literal((v == this.view ? "§a▸ " : "§8  ") + v.label + "§8 - " + v.note));
      }
      lore.add(Component.literal("§8Click to cycle through the three views."));
      stack.set(DataComponents.LORE, new ItemLore(lore));
      return stack;
   }

   private ItemStack clearButton() {
      ItemStack stack = new ItemStack(Items.MILK_BUCKET);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§c§lClear filters"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(List.of(
            Component.literal("§7Drops the search and goes back to"),
            Component.literal("§7the §fEverything§7 shelf.")
         ))
      );
      return stack;
   }

   private ItemStack helpButton() {
      ItemStack stack = new ItemStack(Items.KNOWLEDGE_BOOK);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§b§lHow the Auction House works"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(List.of(
            Component.literal("§fSelling§7 - hold the item you want to list,"),
            Component.literal("§7click §eSell held item§7 at the bottom, type a starting"),
            Component.literal("§7price, then pick how long it runs."),
            Component.literal(""),
            Component.literal("§fBidding§7 - select a listing, then:"),
            Component.literal("§7  left-click §aBID§7 for the minimum bid,"),
            Component.literal("§7  left-click the §b+10%§7 button beside it to jump the queue,"),
            Component.literal("§7  right-click §aBID§7 to type an amount of your own."),
            Component.literal("§7A bid is held until somebody outbids you, then refunded."),
            Component.literal(""),
            Component.literal("§fBuying§7 - a fixed-price listing is bought at once."),
            Component.literal("§fPayouts§7 - everything you win or earn lands in §f/claim§7."),
            Component.literal(""),
            Component.literal("§8Commands: §f/ah§8, §f/ah search <text>§8, §f/ah mine§8,"),
            Component.literal("§8§f/ah bids§8, §f/ah bid <id> <amount>§8, §f/ah buy <id>§8,"),
            Component.literal("§8§f/ah sell bid|fixed <price>§8, §f/claim§8.")
         ))
      );
      return stack;
   }

   private void rebuild() {
      this.container.clearContent();
      this.buildHeader();
      Auction sel = this.selected();
      int start = this.page * ITEMS_PER_PAGE;

      for (int i = 0; i < ITEMS_PER_PAGE; i++) {
         int idx = start + i;
         if (idx < this.auctions.size()) {
            this.container.setItem(ITEMS_START + i, this.auctionStack(this.auctions.get(idx)));
         }
      }

      if (this.auctions.isEmpty()) {
         this.container.setItem(
            ITEMS_START + 13,
            this.infoStack(
               new ItemStack(Items.COBWEB),
               "§8Nothing here",
               this.emptyLine()
            )
         );
      }

      this.container.setItem(UI_BALANCE, this.balanceStack());
      this.buildActions(sel);
      this.container.setItem(UI_REFRESH, this.infoStack(new ItemStack(Items.CLOCK), "§fRefresh", "§7Re-read prices, bids and timers"));

      if (sel != null) {
         ItemStack selStack = sel.item.copy();
         selStack.setCount(1);
         List<Component> lore = new ArrayList<>();
         lore.add(Component.literal("§7#" + sel.id + " · " + ("fixed".equals(sel.mode) ? "fixed price" : "bid") + " · " + AuctionManager.currencyString(sel)));
         lore.add(Component.literal("§7" + AuctionManager.statusLine(sel, this.owner.getUUID())));
         lore.add(Component.literal("§7" + AuctionManager.timeBar(sel) + " §7" + AuctionManager.timeLeftString(sel) + " left"));
         lore.add(Component.literal("§7Seller: §f" + sel.sellerName));
         lore.add(Component.literal("§7Bidders: §f" + AuctionManager.bidderCount(sel)));
         if (AuctionManager.isWinning(sel, this.owner.getUUID()) && "bid".equals(sel.mode)) {
            lore.add(Component.literal("§7You hold §f" + Chat.moneyStr(sel.currentBid) + "§7 in this."));
         }
         selStack.set(DataComponents.LORE, new ItemLore(lore));
         this.container.setItem(UI_SELECTED, selStack);
      } else {
         this.container.setItem(UI_SELECTED, this.infoStack(new ItemStack(Items.ITEM_FRAME), "§8Selected", "§7Click a listing above"));
      }

      ItemStack held = this.owner.getMainHandItem();
      if (held.isEmpty()) {
         this.container.setItem(UI_SELL_HELD, this.infoStack(new ItemStack(Items.BARRIER), "§8SELL HELD ITEM", "§7Hold an item to auction it here"));
      } else {
         ItemStack heldIcon = held.copy();
         heldIcon.setCount(1);
         this.container.setItem(
            UI_SELL_HELD,
            this.infoStack(
               heldIcon,
               "§e§lSELL HELD ITEM",
               "§7" + held.getHoverName().getString() + " x" + held.getCount()
                  + "\n§7Sets your own price (10k, 1.5m...)\n§7then how long it runs.\n§7Shelf: " + AuctionManager.categoryOf(held).label
            )
         );
      }

      this.container.setItem(PAGE_PREV, this.pageButton(false));
      this.container.setItem(PAGE_NEXT, this.pageButton(true));
      this.container.setItem(UI_CLOSE, this.infoStack(new ItemStack(Items.BARRIER), "§cClose", "§7Browsing is free"));
   }

   private String emptyLine() {
      if (this.view == View.MINE) {
         return "§7You have nothing listed right now.§8\n§7Hold an item and press §fSell held item§8.";
      }
      if (this.view == View.BIDS) {
         return "§7You are not bidding on anything.§8\n§7Pick a listing and place a bid - it shows up here.";
      }
      if (!this.query.isEmpty()) {
         return "§7Nothing matches §f" + this.query + "§7.§8\n§7Try a shorter word, or clear the filters.";
      }
      if (this.category != Category.ALL) {
         return "§7This shelf is empty.§8\n§7Click the shelf button to walk to another one.";
      }
      return "§7The market is empty.§8\n§7Be the first: list something you do not need.";
   }

   private ItemStack balanceStack() {
      ItemStack stack = new ItemStack(Items.GOLD_INGOT);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§a§lYour money"));
      List<Component> lore = new ArrayList<>();
      lore.add(Component.literal("§7Balance: §f" + Chat.moneyStr(EconomyManager.balance(this.owner.getUUID()))));
      lore.add(Component.literal("§7Pending in §f/claim§7: §f" + EconomyManager.collectionCount(this.owner.getUUID()) + "§7 item(s)"));
      lore.add(Component.literal("§7Committed to bids: §f" + this.committed()));
      if (this.committed() > 0L) {
         lore.add(Component.literal("§8Returned in full if you are outbid."));
      }
      return stack;
   }

   /** How much of this player's money is standing in live auctions right now. */
   private long committed() {
      long total = 0L;
      for (Auction a : AuctionManager.auctionsBiddingOn(this.owner.getUUID())) {
         if (AuctionManager.isWinning(a, this.owner.getUUID())) {
            total += a.currentBid;
         }
      }
      return total;
   }

   private void buildActions(Auction sel) {
      if (sel == null) {
         this.container.setItem(UI_PRIMARY, this.infoStack(new ItemStack(Items.BARRIER), "§8BID", "§7Select a listing first"));
         this.container.setItem(UI_SECONDARY, this.infoStack(new ItemStack(Items.BARRIER), "§8BUY", "§7Select a listing first"));
         return;
      }
      boolean fixed = "fixed".equals(sel.mode);
      boolean mine = sel.seller.equals(this.owner.getUUID());
      if (mine) {
         boolean hasBids = !sel.escrow.isEmpty() || sel.topBidder != null;
         ItemStack cancel = this.infoStack(
            new ItemStack(Items.TNT),
            "§c§lCANCEL LISTING",
            "§7Take §f" + sel.item.getHoverName().getString() + "§7 back.\n"
               + (hasBids ? "§7Bidders are refunded immediately." : "§7Nobody has bid yet.")
         );
         cancel.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
         this.container.setItem(UI_PRIMARY, cancel);
         if (hasBids) {
            this.container.setItem(
               UI_SECONDARY,
               this.infoStack(new ItemStack(Items.BARRIER), "§8REPRICE", "§7Can't reprice once someone has bid.\n§7Cancel it instead.")
            );
         } else {
            ItemStack reprice = this.infoStack(
               new ItemStack(Items.ANVIL), "§e§lREPRICE", "§7Set a new starting price and length.\n§7Only possible while nobody has bid."
            );
            reprice.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
            this.container.setItem(UI_SECONDARY, reprice);
         }
         return;
      }
      if (fixed) {
         this.container.setItem(
            UI_PRIMARY,
            this.infoStack(new ItemStack(Items.BARRIER), "§8BID", "§7Fixed price - use §fBUY NOW§7.")
         );
         if (!ModConfig.buyNow()) {
            this.container.setItem(UI_SECONDARY, this.infoStack(new ItemStack(Items.BARRIER), "§8BUY NOW (OFF)", "§7Buy-now is disabled on this server"));
         } else {
            ItemStack buy = this.infoStack(
               new ItemStack(Items.EMERALD),
               "§a§lBUY NOW §f" + Chat.moneyStr(sel.price),
               "§7" + sel.item.getHoverName().getString() + " x" + sel.item.getCount()
                  + "\n§7Sold by §f" + sel.sellerName
                  + "\n§8It lands in /claim the moment you buy it."
            );
            buy.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
            this.container.setItem(UI_SECONDARY, buy);
         }
         return;
      }

      long minimum = AuctionManager.minimumBid(sel);
      boolean winning = AuctionManager.isWinning(sel, this.owner.getUUID());
      ItemStack bid = this.infoStack(
         new ItemStack(Items.ANVIL),
         winning ? "§8§lBID §7(you are winning)" : "§a§lBID §f" + Chat.moneyStr(minimum),
         "§7" + sel.item.getHoverName().getString() + " x" + sel.item.getCount()
            + "\n§7Seller: §f" + sel.sellerName
            + "\n§7Ends in §f" + AuctionManager.timeLeftString(sel)
            + (winning ? "\n§8You already hold the top bid." : "\n§8Left-click to bid the minimum.")
      );
      bid.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      this.container.setItem(UI_PRIMARY, bid);

      long up = Math.max(minimum + 1L, (long)Math.ceil(minimum * 1.10));
      ItemStack raise = this.infoStack(
         new ItemStack(Items.DIAMOND),
         "§a§lBID §f" + Chat.moneyStr(up) + " §7(+10%)",
         "§7Right-click the button above to type your own."
            + "\n§7You have §f" + Chat.moneyStr(EconomyManager.balance(this.owner.getUUID())) + "§7."
      );
      raise.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      this.container.setItem(UI_SECONDARY, raise);
   }

   private ItemStack auctionStack(Auction a) {
      ItemStack stack = a.item.copy();
      stack.setCount(1);
      boolean fixed = "fixed".equals(a.mode);
      boolean mine = a.seller.equals(this.owner.getUUID());
      boolean winning = AuctionManager.isWinning(a, this.owner.getUUID());
      boolean outbid = !winning && a.escrow.containsKey(this.owner.getUUID());
      boolean soon = AuctionManager.isEndingSoon(a);

      List<Component> lore = new ArrayList<>();
      lore.add(Component.literal("§7#" + a.id + " §f" + a.item.getHoverName().getString() + " §7x" + a.item.getCount()));
      lore.add(Component.literal(
         (fixed ? "§aPrice: §f" : (a.topBidder == null ? "§eStarting bid: §f" : "§eTop bid: §f")) + Chat.moneyStr(AuctionManager.askingPrice(a))
      ));
      if (!fixed) {
         lore.add(Component.literal("§7Next bid: §f" + Chat.moneyStr(AuctionManager.minimumBid(a))
            + " §8(+" + Chat.moneyStr(Math.max(1L, a.minIncrement)) + ")"));
         lore.add(Component.literal("§7Bidders: §f" + AuctionManager.bidderCount(a)
            + (a.topBidder != null ? " §7· leading: §f" + a.topBidderName : "")));
      }
      lore.add(Component.literal("§7Seller: §f" + a.sellerName + (mine ? " §8(you)" : "")));
      lore.add(Component.literal(AuctionManager.timeBar(a) + " §7" + AuctionManager.timeLeftString(a)
         + (AuctionManager.isOpenEnded(a) ? "" : " left") + (soon ? " §c§lENDING" : "")));
      lore.add(Component.literal(AuctionManager.statusLine(a, this.owner.getUUID())));
      lore.add(Component.literal(""));
      lore.add(Component.literal(mine
         ? "§8Click to manage this listing."
         : (outbid ? "§8Someone has passed you - click to bid again."
            : (winning ? "§8You are taking this home." : (fixed ? "§8Click to select, then buy it." : "§8Click to select, then bid.")))));
      stack.set(DataComponents.LORE, new ItemLore(lore));
      // The glint is the highlight, and it is spent on three things a player scans for rather than
      // on everything: what they are winning, what has been taken off them, and what is about to go.
      if (a.id == this.selectedId) {
         stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      } else if (winning || outbid || soon) {
         stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      }

      return stack;
   }

   private ItemStack pageButton(boolean next) {
      boolean enabled = next ? this.page + 1 < this.pageCount : this.page > 0;
      if (!enabled) {
         ItemStack stack = new ItemStack(Items.STAINED_GLASS_PANE.gray());
         stack.set(DataComponents.CUSTOM_NAME, Component.literal(next ? "§8Next page" : "§8Previous page"));
         stack.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("§7Page " + (this.page + 1) + "/" + this.pageCount))));
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

   private ItemStack infoStack(ItemStack base, String name, String lore) {
      ItemStack stack = base.copy();
      stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
      ItemStack out = stack;
      if (lore != null && !lore.isEmpty()) {
         out.set(DataComponents.LORE, new ItemLore(plainLines(lore)));
      }
      return out;
   }

   /** Lore lines written with an embedded newline, split here rather than at every call site. */
   private static List<Component> plainLines(String lore) {
      List<Component> out = new ArrayList<>();
      for (String line : lore.split("\n")) {
         if (!line.isEmpty()) {
            out.add(Component.literal(line));
         }
      }
      return out;
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
      if (slotId >= 54) {
         super.clicked(slotId, button, input, player);
         return;
      }
      boolean rightClick = button == 1;
      if (slotId == UI_CLOSE) {
         this.returnCarried(sp);
         sp.closeContainer();
         return;
      }
      if (slotId == SORT) {
         this.sort = this.sort.next();
         this.page = 0;
         this.refresh();
      } else if (slotId == CATEGORY) {
         this.category = rightClick ? this.category.previous() : this.category.next();
         this.page = 0;
         this.refresh();
      } else if (slotId == SEARCH) {
         this.returnCarried(sp);
         AuctionSearchMenu.open(sp, this.query);
         return;
      } else if (slotId == VIEW) {
         this.view = rightClick ? this.view.previous() : this.view.next();
         this.page = 0;
         this.refresh();
      } else if (slotId == CLEAR) {
         this.query = "";
         this.category = Category.ALL;
         this.page = 0;
         this.refresh();
      } else if (slotId == PAGE_PREV) {
         if (this.page > 0) {
            this.page--;
            this.rebuild();
         }
      } else if (slotId == PAGE_NEXT) {
         if (this.page + 1 < this.pageCount) {
            this.page++;
            this.rebuild();
         }
      } else if (slotId >= ITEMS_START && slotId < ITEMS_START + ITEMS_PER_PAGE) {
         int idx = this.page * ITEMS_PER_PAGE + (slotId - ITEMS_START);
         if (idx < this.auctions.size()) {
            this.selectedId = this.auctions.get(idx).id;
         }
         this.rebuild();
      } else if (slotId == UI_REFRESH) {
         this.refresh();
      } else if (slotId == UI_SELL_HELD) {
         this.returnCarried(sp);
         this.doSellHeld(sp);
      } else if (slotId == UI_PRIMARY) {
         this.returnCarried(sp);
         Auction sel = this.selected();
         if (sel == null) {
            Chat.msg(sp, "&cSelect a listing first.");
         } else if (sel.seller.equals(sp.getUUID())) {
            this.doCancel(sp);
         } else if (rightClick && "bid".equals(sel.mode) && !AuctionManager.isWinning(sel, sp.getUUID())) {
            AuctionBidMenu.open(sp, sel.id);
            return;
         } else {
            this.doBid(sp);
         }
      } else if (slotId == UI_SECONDARY) {
         this.returnCarried(sp);
         Auction sel = this.selected();
         if (sel == null) {
            Chat.msg(sp, "&cSelect a listing first.");
         } else if (sel.seller.equals(sp.getUUID())) {
            this.doReprice(sp);
         } else if ("fixed".equals(sel.mode)) {
            this.doBuy(sp);
         } else {
            this.doRaise(sp);
         }
      } else {
         this.returnCarried(sp);
      }
      this.broadcastChanges();
   }

   public ItemStack quickMoveStack(Player player, int index) {
      return ItemStack.EMPTY;
   }

   private void doCancel(ServerPlayer player) {
      Auction a = this.selected();
      if (a == null) {
         Chat.msg(player, "&cSelect one of your auctions first.");
      } else if (!a.seller.equals(player.getUUID())) {
         Chat.msg(player, "&cThat isn't your auction.");
      } else if (!AuctionManager.cancelAuction(this.serverOf(player), player, a)) {
         Chat.msg(player, "&cCouldn't cancel that auction.");
      } else {
         SoundUtil.play(player, ModSounds.DENY);
         Chat.raw(player, "§aCancelled auction §d#" + a.id + "§a. The item is back in your §f/claim§a.");
         this.selectedId = -1;
         this.refresh();
      }
   }

   private void doReprice(ServerPlayer player) {
      Auction a = this.selected();
      if (a == null) {
         Chat.msg(player, "&cSelect one of your auctions first.");
      } else if (!a.seller.equals(player.getUUID())) {
         Chat.msg(player, "&cThat isn't your auction.");
      } else if (a.escrow.isEmpty() && a.topBidder == null) {
         this.returnCarried(player);
         ItemStack icon = a.item.copy();
         icon.setCount(1);
         AuctionPriceMenu.openForRelist(player, icon, a.id);
      } else {
         Chat.msg(player, "&cCan't reprice once someone has bid - cancel it instead.");
      }
   }

   private void doSellHeld(ServerPlayer player) {
      ItemStack held = player.getMainHandItem();
      if (held.isEmpty()) {
         Chat.msg(player, "&cHold the item you want to auction first.");
      } else if (AuctionManager.isUnauctionable(held)) {
         Chat.msg(player, "&cThat item can't be auctioned.");
      } else {
         ItemStack auctioned = held.copy();
         held.setCount(0);
         player.openMenu(new SimpleMenuProvider(
            (syncId, inv, p) -> new AuctionPriceMenu(syncId, inv, auctioned),
            Component.literal("§d§lSet Auction Price")
         ));
      }
   }

   private void doBid(ServerPlayer player) {
      Auction a = this.selected();
      if (a == null) {
         Chat.msg(player, "&cSelect an auction first.");
      } else if (!"bid".equals(a.mode)) {
         Chat.msg(player, "&cThat auction is fixed-price - use BUY NOW.");
      } else if (AuctionManager.isWinning(a, player.getUUID())) {
         Chat.msg(player, "&7You are already the top bidder on &f#" + a.id + "&7.");
      } else {
         long amount = AuctionManager.minimumBid(a);
         if (!AuctionManager.bid(this.serverOf(player), player, a, amount)) {
            Chat.msg(
               player,
               "&cBid failed. Minimum is &f" + Chat.moneyStr(amount) + "&c, you have &f"
                  + Chat.moneyStr(EconomyManager.balance(player.getUUID())) + "&c."
            );
         } else {
            this.afterBid(player, a, amount);
         }
      }
   }

   private void doRaise(ServerPlayer player) {
      Auction a = this.selected();
      if (a == null) {
         Chat.msg(player, "&cSelect an auction first.");
      } else if (!"bid".equals(a.mode)) {
         Chat.msg(player, "&cThat auction is fixed-price - use BUY NOW.");
      } else if (AuctionManager.isWinning(a, player.getUUID())) {
         Chat.msg(player, "&7You are already the top bidder - right-click BID to type an amount.");
      } else {
         long minimum = AuctionManager.minimumBid(a);
         long amount = Math.max(minimum + 1L, (long)Math.ceil(minimum * 1.10));
         if (!AuctionManager.bid(this.serverOf(player), player, a, amount)) {
            Chat.msg(
               player,
               "&cBid failed. Minimum is &f" + Chat.moneyStr(minimum) + "&c, you have &f"
                  + Chat.moneyStr(EconomyManager.balance(player.getUUID())) + "&c."
            );
         } else {
            this.afterBid(player, a, amount);
         }
      }
   }

   private void afterBid(ServerPlayer player, Auction a, long amount) {
      SoundUtil.play(player, ModSounds.AUCTION_BID);
      Chat.raw(
         player,
         "§aBid §f" + Chat.moneyStr(amount) + "§a on §d#" + a.id + "§a. Held until you are outbid. Balance: §f"
            + Chat.moneyStr(EconomyManager.balance(player.getUUID())) + "§a."
      );
      this.refresh();
   }

   private void doBuy(ServerPlayer player) {
      if (!ModConfig.buyNow()) {
         Chat.msg(player, "&cBuy-now is disabled on this server.");
         return;
      }
      Auction a = this.selected();
      if (a == null) {
         Chat.msg(player, "&cSelect an auction first.");
      } else if (!"fixed".equals(a.mode)) {
         Chat.msg(player, "&cThat auction is bid-based - use BID.");
      } else if (!AuctionManager.buyFixed(this.serverOf(player), player, a)) {
         Chat.msg(
            player,
            "&cCould not buy this one. It costs &f" + Chat.moneyStr(a.price) + "&c and you have &f"
               + Chat.moneyStr(EconomyManager.balance(player.getUUID())) + "&c."
         );
      } else {
         SoundUtil.play(player, ModSounds.AUCTION_BID);
         Chat.raw(player, "§aBought §d#" + a.id + "§a for " + Chat.moneyStr(a.price) + "§a. Claim it with §f/claim§a.");
         this.selectedId = -1;
         this.refresh();
      }
   }

   private MinecraftServer serverOf(ServerPlayer player) {
      return player.level().getServer();
   }

   /** The three things a player can be looking at in this window. */
   public enum View {
      BROWSE("§fWhole market", "every live listing"),
      MINE("§bMy listings", "what you have for sale"),
      BIDS("§6My bids", "what your money is in");

      public final String label;
      public final String note;

      View(String label, String note) {
         this.label = label;
         this.note = note;
      }

      public View next() {
         View[] all = values();
         return all[(this.ordinal() + 1) % all.length];
      }

      public View previous() {
         View[] all = values();
         return all[(this.ordinal() + all.length - 1) % all.length];
      }
   }

   /** The orderings the sort button walks, in the order a player meets them. */
   public enum SortMode {
      NEWEST("Newest first"),
      PRICE_LOW("Cheapest first"),
      PRICE_HIGH("Dearest first"),
      ENDING_SOON("Ending soon"),
      MOST_BIDS("Most contested");

      public final String label;

      SortMode(String label) {
         this.label = label;
      }

      public SortMode next() {
         SortMode[] all = values();
         return all[(this.ordinal() + 1) % all.length];
      }
   }
}
