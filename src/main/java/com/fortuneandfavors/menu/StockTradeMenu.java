package com.fortuneandfavors.menu;

import com.fortuneandfavors.ModSounds;
import com.fortuneandfavors.economy.BankManager;
import com.fortuneandfavors.economy.EconomyManager;
import com.fortuneandfavors.economy.MarketManager;
import com.fortuneandfavors.economy.MarketManager.Position;
import com.fortuneandfavors.economy.MarketManager.Stock;
import com.fortuneandfavors.util.Chat;
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

/**
 * One listing's chart window: four rows of drawn price, and the buttons that trade against it.
 *
 * <p>The chart is not a picture - it is the top four rows of the grid, nine columns of glass panes
 * whose height is the price, oldest on the left and newest against the right-hand edge with a
 * marker on its top cell. Each column is green if that quote is at or above the one before it and
 * red if it is not, so the shape of the window reads first and the numbers second. A window can be
 * 45 minutes, three hours, a day or a week, and the figures beside the chart always say which
 * scale the picture is on.
 */
public class StockTradeMenu extends ChestMenu implements StockMenus.MarketView {
   /** The five-minute steps one column of the chart covers. 9 columns x these = the window. */
   private static final int[] STEPS = new int[]{1, 4, 32, 224};
   private static final String[] WINDOWS = new String[]{"45 min", "3 hours", "24 hours", "7 days"};

   /** The quantity ladder: every order size a player would actually place. */
   private static final long[] QTY_STEPS = new long[]{1L, 2L, 5L, 10L, 25L, 50L, 100L, 250L, 500L, 1_000L, 2_500L, 5_000L, 10_000L};

   private static final int QTY_DOWN = 36;
   private static final int QTY = 37;
   private static final int QTY_UP = 38;
   private static final int BUY = 39;
   private static final int CARD = 40;
   private static final int HOLDINGS = 41;
   private static final int SELL = 42;
   private static final int WINDOW = 43;
   private static final int SELL_ALL = 44;
   private static final int BACK = 45;
   private static final int BANK = 47;
   private static final int FEES = 49;
   private static final int CLOSE = 53;

   private final SimpleContainer container;
   private final ServerPlayer owner;
   private final Stock stock;
   private int zoom;
   /** Where the quantity selector is standing on {@link #QTY_STEPS}. */
   private int qtyStep;

   public StockTradeMenu(int syncId, Inventory playerInventory) {
      this(syncId, playerInventory, new SimpleContainer(54), MarketManager.STOCKS.get(0), 0);
   }

   private StockTradeMenu(int syncId, Inventory playerInventory, SimpleContainer container, Stock stock, int zoom) {
      super(MenuType.GENERIC_9x6, syncId, playerInventory, container, 6);
      this.container = container;
      this.owner = (ServerPlayer)playerInventory.player;
      this.stock = stock;
      this.zoom = zoom;
      this.qtyStep = 0;
      this.rebuild();
   }

   /** The order size the buttons will place right now. */
   private long qty() {
      return QTY_STEPS[Math.max(0, Math.min(QTY_STEPS.length - 1, this.qtyStep))];
   }

   /** Steps the selector along the ladder. */
   private void nudgeQty(int direction) {
      this.qtyStep = Math.max(0, Math.min(QTY_STEPS.length - 1, this.qtyStep + direction));
   }

   /** Jumps the selector to the largest rung that will fit {@code fits} - the "all of it" click. */
   private void snapQty(long fits) {
      if (fits <= 0L) {
         this.qtyStep = 0;
         return;
      }
      int best = 0;
      for (int i = 0; i < QTY_STEPS.length; i++) {
         if (QTY_STEPS[i] <= fits) {
            best = i;
         }
      }
      this.qtyStep = best;
   }

   public static void open(ServerPlayer player, Stock stock) {
      player.openMenu(
         new SimpleMenuProvider(
            (syncId, inv, p) -> new StockTradeMenu(syncId, inv, new SimpleContainer(54), stock, 0),
            Component.literal("§6§l" + stock.name() + " §8· §7" + stock.id())
         )
      );
   }

   /** Test seam: the slots this window built, so a check can look at the chart without a client. */
   public net.minecraft.world.Container slotsForTest() {
      return this.container;
   }

   /** Test seam: build one listing's window without opening it on anybody. */
   public static StockTradeMenu forTest(int syncId, Inventory inventory, String ticker) {
      Stock s = MarketManager.byId(ticker);
      return new StockTradeMenu(syncId, inventory, new SimpleContainer(54), s == null ? MarketManager.STOCKS.get(0) : s, 0);
   }

   @Override
   public void refresh() {
      this.rebuild();
   }

   private void rebuild() {
      this.container.clearContent();
      long tick = MarketManager.currentTick();
      int step = STEPS[Math.max(0, Math.min(STEPS.length - 1, this.zoom))];
      double[] series = MarketManager.series(this.stock, tick, 9, step);
      // The chart is the whole top half of the window: no frame, because the chart is the frame.
      StockMenus.chart(this.container, series, 4, 27);
      for (int slot = 45; slot < 54; slot++) {
         this.container.setItem(slot, StockMenus.blank((Item)Items.STAINED_GLASS_PANE.yellow()));
      }

      double clockNow = MarketManager.priceOf(this.stock, tick) * MarketManager.level();
      double now = MarketManager.quoteOf(this.stock, tick);
      double levelMove = MarketManager.levelAdjustment(this.stock, tick);
      double flow = MarketManager.flowAdjustment(this.stock, tick);
      Position held = MarketManager.position(this.owner.getUUID(), this.stock);
      long affordable = MarketManager.maxAffordable(this.owner, this.stock, tick);
      long qty = this.qty();
      long cost = MarketManager.costFor(this.stock, tick, qty);
      long proceeds = MarketManager.proceedsFor(this.stock, tick, Math.min(qty, held.shares()));
      boolean canBuy = affordable >= qty;
      boolean canSell = held.shares() >= qty;
      double windowMove = series.length < 2 || series[0] <= 0.0 ? 0.0 : clockNow / series[0] - 1.0;
      double windowCash = series.length < 2 ? 0.0 : clockNow - series[0];
      double dayCash = MarketManager.changeCash(this.stock, tick, 288);

      this.container
         .setItem(
            QTY_DOWN,
            StockMenus.tile(
               (Item)Items.STAINED_GLASS_PANE.red(),
               "§c§lFewer shares",
               "§7Stepping down 1, 2, 5, 10, 25...",
               "§8Right-click to go back to one share"
            )
         );
      this.container.setItem(QTY, this.quantityTile(qty, cost, proceeds, affordable, held.shares(), canBuy, canSell));
      this.container
         .setItem(
            QTY_UP,
            StockMenus.tile(
               (Item)Items.STAINED_GLASS_PANE.lime(),
               "§a§lMore shares",
               "§7Stepping up 1, 2, 5, 10, 25...",
               "§8Right-click for the most that fits"
            )
         );
      this.container
         .setItem(
            BUY,
            StockMenus.tile(
               canBuy ? Items.EMERALD_BLOCK : Items.COAL_BLOCK,
               (canBuy ? "§a§l" : "§c§l") + "Buy " + qty + " share" + (qty == 1L ? "" : "s"),
               "§7Estimated total: " + (canBuy ? "§a" : "§c") + StockMenus.usd((double)cost),
               "§7Quote: §f" + StockMenus.usd(now) + " §8a share",
               "§8Brokerage included, rounded up.",
               "",
               canBuy
                  ? "§8Your funds: §7" + Chat.moneyStr(MarketManager.spendable(this.owner)) + "§8, wallet first"
                  : "§cYou can afford " + affordable + " of those right now.",
               "§8Click to buy at market"
            )
         );
      this.container
         .setItem(
            CARD,
            StockMenus.tile(
               this.stock.icon(),
               "§e§l" + this.stock.name() + " §8(" + this.stock.id() + ")",
               "§7" + this.stock.blurb(),
               "",
               "§7Price: §f" + StockMenus.usd(now) + " §8a share",
               "§7Shelf: §f" + this.stock.tier().label + "§8, aimed at "
                  + StockMenus.usd(this.stock.tier().targetPrice(MarketManager.level())) + " ("
                  + this.stock.tier().affordableLabel() + ")",
               "§8  the shelf's own band is " + StockMenus.usd(this.stock.tier().floorPrice(MarketManager.level()))
                  + "-§8" + StockMenus.usd(this.stock.tier().ceilingPrice(MarketManager.level())),
               levelMove > 0.005
                  ? "§7Market scale: §a-" + Math.round(levelMove * 100.0) + "% §8(clock " + StockMenus.usd(clockNow) + "§8)"
                  : levelMove < -0.005
                     ? "§7Market scale: §c+" + Math.round(-levelMove * 100.0) + "% §8(clock " + StockMenus.usd(clockNow) + "§8)"
                     : "§8Priced at the market's level exactly.",
               flow > 0.005
                  ? "§7Order flow: §c+" + String.format(java.util.Locale.ROOT, "%.1f", flow * 100.0)
                     + "% §8buyers have pushed this up"
                  : flow < -0.005
                     ? "§7Order flow: §a" + String.format(java.util.Locale.ROOT, "%.1f", flow * 100.0)
                        + "% §8sellers have pushed this down"
                     : "§8No order flow standing on this.",
               "§7Risk: §f" + this.stock.risk() + " §8(swing " + String.format(java.util.Locale.ROOT, "%.2f", this.stock.swing())
                  + "§8, beta " + String.format(java.util.Locale.ROOT, "%.2f", this.stock.beta()) + "§8)",
               "§7Last 24h: " + StockMenus.colour(dayCash) + StockMenus.pct(MarketManager.change(this.stock, tick, 288))
                  + " §8( " + (dayCash >= 0.0 ? "+" : "-") + StockMenus.usd(Math.abs(dayCash)) + ")",
               "§7This " + WINDOWS[this.zoom].replace(" ", "") + ": " + StockMenus.colour(windowMove) + StockMenus.pct(windowMove)
                  + " §8( " + (windowCash >= 0.0 ? "+" : "-") + StockMenus.usd(Math.abs(windowCash)) + ")",
               "§7Base price: §f" + StockMenus.usd((double)this.stock.base()) + " §8at scale 1"
            )
         );
      this.container
         .setItem(
            WINDOW,
            StockMenus.tile(
               Items.COMPASS,
               "§e§lWindow: §f" + WINDOWS[this.zoom],
               "§7Nine columns, one quote each.",
               "§7Height is scaled to this window:",
               "§7low §f" + StockMenus.usd(low(series)) + " §7high §f" + StockMenus.usd(high(series)),
               "§8" + StockMenus.spark(series, 27),
               "",
               "§8Click to change the window"
            )
         );
      this.container.setItem(HOLDINGS, this.holdingsTile(held, now, tick));
      this.container
         .setItem(
            SELL,
            StockMenus.tile(
               canSell ? Items.GOLD_BLOCK : Items.COAL_BLOCK,
               (canSell ? "§6§l" : "§c§l") + "Sell " + qty + " share" + (qty == 1L ? "" : "s"),
               "§7Estimated proceeds: " + (canSell ? "§a" : "§c") + StockMenus.usd((double)proceeds),
               "§7Quote: §f" + StockMenus.usd(now) + " §8a share",
               "§8Brokerage already taken off.",
               "",
               canSell
                  ? "§7You hold: §f" + held.shares() + " shares"
                  : "§cYou only hold " + held.shares() + " shares.",
               "§8Click to sell at market"
            )
         );
      this.container
         .setItem(
            SELL_ALL,
            StockMenus.tile(
               Items.GOLD_NUGGET,
               "§6§lSell everything",
               "§7You hold: §f" + held.shares() + " shares",
               "§7Proceeds: §a" + Chat.moneyStr(MarketManager.proceedsFor(this.stock, tick, held.shares())),
               held.shares() > 0L ? "§8Closes the position" : "§cNothing to sell",
               "",
               "§8Click to sell at market"
            )
         );
      this.container
         .setItem(
            BACK,
            StockMenus.tile(
               Items.ARROW,
               "§e§lBack to the board",
               "§7All " + MarketManager.STOCKS.size() + " listings.",
               "",
               "§8Click to go back"
            )
         );
      this.container
         .setItem(BANK, StockMenus.tile(Items.GOLD_BLOCK, "§6§lBank", "§7Wallet and vaults.", "", "§8Click to open"));
      this.container.setItem(FEES, this.feesTile(tick));
      this.container.setItem(CLOSE, StockMenus.tile(Items.BARRIER, "§cClose", "", "§8Click to close"));
      this.broadcastChanges();
   }

   private static double low(double[] series) {
      double min = Double.MAX_VALUE;
      for (double v : series) {
         min = Math.min(min, v);
      }
      return min;
   }

   private static double high(double[] series) {
      double max = -Double.MAX_VALUE;
      for (double v : series) {
         max = Math.max(max, v);
      }
      return max;
   }

   private ItemStack holdingsTile(Position held, double now, long tick) {
      List<Component> lore = new ArrayList<>();
      lore.add(Component.literal("§7Shares: §f" + held.shares()));
      if (held.shares() > 0L) {
         long value = Math.round(now * (double)held.shares());
         long profit = value - held.basis();
         lore.add(Component.literal("§7Worth: §f" + StockMenus.usd(value)));
         lore.add(Component.literal("§7Average cost: §f" + StockMenus.usd((double)held.basis() / (double)held.shares())));
         lore.add(
            Component.literal(
               "§7Profit: " + StockMenus.colour(profit) + (profit >= 0 ? "+" : "-") + StockMenus.usd(Math.abs(profit))
            )
         );
      } else {
         lore.add(Component.literal("§8You hold none of this listing."));
      }
      lore.add(Component.literal(""));
      lore.add(Component.literal("§7Wallet: §a" + Chat.moneyStr(EconomyManager.balance(this.owner.getUUID()))));
      lore.add(Component.literal("§7Vault: §f" + Chat.moneyStr(BankManager.accountOf(this.owner).cash())));
      return StockMenus.lore(Items.CHEST, "§6§lYour holding", lore);
   }

   /**
    * The quantity selector: what the buy and sell buttons either side of it are about to order.
    *
    * <p>A market needs a size to trade in - "buy a share" is not an order - and the number has to
    * be visible while it is being chosen, because the estimate beside it is the only honest answer
    * to "what will this cost me".
    */
   private ItemStack quantityTile(long qty, long cost, long proceeds, long affordable, long heldShares,
                                  boolean canBuy, boolean canSell) {
      List<Component> lore = new ArrayList<>();
      lore.add(Component.literal("§7Order size: §f" + qty + " share" + (qty == 1L ? "" : "s")));
      lore.add(Component.literal(""));
      lore.add(Component.literal("§7Estimated total cost: " + (canBuy ? "§a" : "§c") + StockMenus.usd((double)cost)));
      lore.add(Component.literal("§7Estimated proceeds: " + (canSell ? "§a" : "§c") + StockMenus.usd((double)proceeds)));
      lore.add(Component.literal(""));
      lore.add(Component.literal("§7You can afford: §f" + affordable + " shares"));
      lore.add(Component.literal("§7You hold: §f" + heldShares + " shares"));
      lore.add(Component.literal(""));
      lore.add(Component.literal("§8Left-click to step up or down by"));
      lore.add(Component.literal("§81, 2, 5, 10, 25, 50, 100 ... 10,000"));
      lore.add(Component.literal("§8Right-click to snap to what you can buy."));
      lore.add(Component.literal(""));
      lore.add(Component.literal("§8A big order moves the price: buying lifts"));
      lore.add(Component.literal("§8it, selling drops it, and the mark fades"));
      lore.add(Component.literal("§8over the next few quotes."));
      return StockMenus.lore(Items.PAPER, "§e§lQuantity §8· §f" + qty + " shares", lore);
   }

   private ItemStack feesTile(long tick) {
      return StockMenus.tile(
         Items.PAPER,
         "§e§lThe exchange",
         "§7Brokerage: §f1%§7 each way.",
         "§7Buying needs that much more than the",
         "§7quote; selling pays that much less, so a",
         "§7round trip has to beat 2% to profit.",
         "",
         "§7Next quote in §f" + StockMenus.untilNextQuote() + "§7.",
         "§7Selling pays into your wallet and goes on",
         "§7your bank statement.",
         "",
         "§7The quote is the clock's price carried to the",
         "§7server's §6Market Scale§7 - the level its own",
         "§7wealth asks for - and held inside its shelf's",
         "§7floor and ceiling, so the board is never",
         "§7priced for a player who does not exist.",
         "",
         "§8Prices are never announced in chat."
      );
   }

   // --- trading -------------------------------------------------------------------------------

   private void buy(ServerPlayer sp, long shares) {
      if (shares <= 0L) {
         Chat.msg(sp, "&cYou cannot afford a single share of that at the current quote.");
         SoundUtil.play(sp, ModSounds.DENY);
         return;
      }
      MarketManager.Receipt receipt = MarketManager.buy(sp, this.stock, shares);
      if (receipt.ok()) {
         Chat.msg(sp, "&7Bought &f" + receipt.shares() + " " + this.stock.id() + "&7 for &a" + Chat.moneyStr(receipt.cash()) + "&7.");
         SoundUtil.play(sp, ModSounds.BUY);
      } else {
         Chat.msg(sp, "&c" + receipt.reason());
         SoundUtil.play(sp, ModSounds.DENY);
      }
   }

   private void sell(ServerPlayer sp, long shares) {
      if (shares <= 0L) {
         Chat.msg(sp, "&cYou do not own any " + this.stock.id() + ".");
         SoundUtil.play(sp, ModSounds.DENY);
         return;
      }
      MarketManager.Receipt receipt = MarketManager.sell(sp, this.stock, shares);
      if (receipt.ok()) {
         Chat.msg(sp, "&7Sold &f" + receipt.shares() + " " + this.stock.id() + "&7 for &a" + Chat.moneyStr(receipt.cash()) + "&7.");
         SoundUtil.play(sp, ModSounds.SELL);
      } else {
         Chat.msg(sp, "&c" + receipt.reason());
         SoundUtil.play(sp, ModSounds.DENY);
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

   public void clicked(int slotId, int button, ContainerInput input, Player player) {
      if (!(player instanceof ServerPlayer sp)) {
         super.clicked(slotId, button, input, player);
         return;
      }
      long tick = MarketManager.currentTick();
      switch (slotId) {
         case QTY_DOWN -> {
            this.nudgeQty(button == 1 ? -QTY_STEPS.length : -1);
            SoundUtil.play(sp, ModSounds.PAGE_FLIP);
         }
         case QTY -> {
            if (button == 1) {
               this.snapQty(MarketManager.maxAffordable(sp, this.stock, tick));
            } else {
               this.nudgeQty(1);
            }
            SoundUtil.play(sp, ModSounds.PAGE_FLIP);
         }
         case QTY_UP -> {
            if (button == 1) {
               this.snapQty(MarketManager.maxAffordable(sp, this.stock, tick));
            } else {
               this.nudgeQty(1);
            }
            SoundUtil.play(sp, ModSounds.PAGE_FLIP);
         }
         case BUY -> this.buy(sp, this.qty());
         case SELL -> this.sell(sp, Math.min(this.qty(), MarketManager.shares(sp.getUUID(), this.stock)));
         case SELL_ALL -> this.sell(sp, MarketManager.shares(sp.getUUID(), this.stock));
         case WINDOW -> {
            this.zoom = (this.zoom + 1) % STEPS.length;
            SoundUtil.play(sp, ModSounds.PAGE_FLIP);
         }
         case BACK -> {
            this.returnCarried(sp);
            StockMarketMenu.open(sp);
            return;
         }
         case BANK -> {
            this.returnCarried(sp);
            BankMenu.open(sp);
            return;
         }
         case CLOSE -> {
            this.returnCarried(sp);
            sp.closeContainer();
            return;
         }
         default -> {
         }
      }
      this.returnCarried(sp);
      this.rebuild();
   }

   public ItemStack quickMoveStack(Player player, int index) {
      return ItemStack.EMPTY;
   }
}
