package com.fortuneandfavors.menu;

import com.fortuneandfavors.ModSounds;
import com.fortuneandfavors.economy.BankManager;
import com.fortuneandfavors.economy.EconomyManager;
import com.fortuneandfavors.economy.MarketManager;
import com.fortuneandfavors.economy.MarketManager.Position;
import com.fortuneandfavors.economy.MarketManager.Stock;
import com.fortuneandfavors.util.Chat;
import com.fortuneandfavors.util.GuiUtil;
import com.fortuneandfavors.util.InventoryHelper;
import com.fortuneandfavors.util.SoundUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The Exchange's board: every listing, what the market as a whole is doing, and what the player is
 * holding.
 *
 * <p>Row 1 is the whole market in one glance - the index, the player's own numbers, the biggest
 * movers and how the thing works, and the Market Scale tile answers the one question a market has
 * to answer immediately - "is this board priced for me?". Rows 2 and 3 are the listings, each one
 * a quote with its own sparkline and its own order flow. Clicking a listing opens its chart;
 * nothing here trades by accident.
 */
public class StockMarketMenu extends ChestMenu implements StockMenus.MarketView {
   private static final int INDEX = 1;
   private static final int SCALE = 2;
   private static final int CLOCK = 3;
   private static final int SUMMARY = 4;
   private static final int HOW = 5;
   private static final int MOVERS = 7;
   /** Where the listings sit: up to six in the second row, five in the third. */
   private static final int[] LISTINGS = new int[]{11, 12, 13, 14, 15, 16, 20, 21, 22, 23, 24};
   private static final int PORTFOLIO = 30;
   private static final int BANK = 45;
   private static final int CLOSE = 49;

   private final SimpleContainer container;
   private final ServerPlayer owner;

   public StockMarketMenu(int syncId, Inventory playerInventory) {
      this(syncId, playerInventory, new SimpleContainer(54));
   }

   private StockMarketMenu(int syncId, Inventory playerInventory, SimpleContainer container) {
      super(MenuType.GENERIC_9x6, syncId, playerInventory, container, 6);
      this.container = container;
      this.owner = (ServerPlayer)playerInventory.player;
      this.rebuild();
   }

   public static void open(ServerPlayer player) {
      player.openMenu(
         new SimpleMenuProvider(
            (syncId, inv, p) -> new StockMarketMenu(syncId, inv),
            Component.literal("§6§lThe Exchange §8· §7a new quote every 5 min")
         )
      );
   }

   /** Test seam: the slots this window built, so a check can look at the board without a client. */
   public net.minecraft.world.Container slotsForTest() {
      return this.container;
   }

   /** Test seam: build the board without opening it on anybody - a headless player cannot take a
    *  menu, so what a check can look at is the slots the window built. */
   public static StockMarketMenu forTest(int syncId, Inventory inventory) {
      return new StockMarketMenu(syncId, inventory, new SimpleContainer(54));
   }

   @Override
   public void refresh() {
      this.rebuild();
   }

   private void rebuild() {
      this.container.clearContent();
      GuiUtil.frames(this.container, 6, (net.minecraft.world.item.Item)Items.STAINED_GLASS_PANE.yellow());
      long tick = MarketManager.currentTick();
      long wallet = EconomyManager.balance(this.owner.getUUID());
      long vault = BankManager.accountOf(this.owner).cash();
      long worth = MarketManager.value(this.owner.getUUID(), tick);
      long basis = MarketManager.basis(this.owner.getUUID());

      this.container.setItem(INDEX, this.indexTile(tick));
      this.container.setItem(SCALE, this.scaleTile(tick));
      this.container.setItem(CLOCK, this.clockTile());
      this.container.setItem(SUMMARY, this.summaryTile(wallet, vault, worth, basis));
      this.container.setItem(HOW, this.howTile());
      this.container.setItem(MOVERS, this.moversTile(tick));
      for (int i = 0; i < MarketManager.STOCKS.size() && i < LISTINGS.length; i++) {
         this.container.setItem(LISTINGS[i], this.listingTile(MarketManager.STOCKS.get(i), tick));
      }
      this.container.setItem(PORTFOLIO, this.portfolioTile(tick));
      this.container
         .setItem(BANK, StockMenus.tile(Items.GOLD_BLOCK, "§6§lBank", "§7Back to your vaults.", "§8Cash and XP, safe from death.", "", "§8Click to open"));
      this.container.setItem(CLOSE, StockMenus.tile(Items.BARRIER, "§cClose", "§7Leave the exchange.", "", "§8Click to close"));
      this.broadcastChanges();
   }

   // --- the tiles -----------------------------------------------------------------------------

   /** One listing: the quote, the moves, the player's own stake, and a day of shape. */
   private ItemStack listingTile(Stock s, long tick) {
      double now = MarketManager.quoteOf(s, tick);
      double levelMove = MarketManager.levelAdjustment(s, tick);
      double flow = MarketManager.flowAdjustment(s, tick);
      double five = MarketManager.change(s, tick, 1);
      double hour = MarketManager.change(s, tick, 12);
      double day = MarketManager.change(s, tick, 288);
      Position held = MarketManager.position(this.owner.getUUID(), s);
      List<Component> lore = new ArrayList<>();
      lore.add(Component.literal("§7" + s.blurb()));
      lore.add(Component.literal(""));
      lore.add(
         Component.literal(
            "§8" + s.tier().label + " shelf §8· §7risk §f" + s.risk() + " §8· aimed at §7"
               + StockMenus.usd(s.tier().targetPrice(MarketManager.level()))
         )
      );
      lore.add(Component.literal("§7Price: §f" + StockMenus.usd(now) + " §8a share"));
      if (levelMove > 0.005) {
         lore.add(
            Component.literal(
               "§7Market scale: §a-" + Math.round(levelMove * 100.0) + "% §8against the clock's "
                  + StockMenus.usd(MarketManager.priceOf(s, tick) * MarketManager.level())
            )
         );
      } else if (levelMove < -0.005) {
         lore.add(
            Component.literal(
               "§7Market scale: §c+" + Math.round(-levelMove * 100.0) + "% §8over the clock's "
                  + StockMenus.usd(MarketManager.priceOf(s, tick) * MarketManager.level())
            )
         );
      }
      if (flow > 0.005) {
         lore.add(Component.literal("§7Order flow: §c+" + String.format(java.util.Locale.ROOT, "%.1f", flow * 100.0) + "% §8buyers are in it"));
      } else if (flow < -0.005) {
         lore.add(Component.literal("§7Order flow: §a" + String.format(java.util.Locale.ROOT, "%.1f", flow * 100.0) + "% §8sellers are in it"));
      }
      lore.add(Component.literal("§7Last 5 min: " + StockMenus.colour(five) + StockMenus.pct(five) + " §8("
         + cash(MarketManager.changeCash(s, tick, 1)) + "§8)"));
      lore.add(Component.literal("§7Last hour: " + StockMenus.colour(hour) + StockMenus.pct(hour) + " §8("
         + cash(MarketManager.changeCash(s, tick, 12)) + "§8)"));
      lore.add(Component.literal("§7Last 24h: " + StockMenus.colour(day) + StockMenus.pct(day) + " §8("
         + cash(MarketManager.changeCash(s, tick, 288)) + "§8)"));
      lore.add(Component.literal(""));
      lore.add(Component.literal("§8" + StockMenus.spark(MarketManager.series(s, tick, 24, 12), 24) + " §7a day"));
      lore.add(Component.literal(""));
      if (held.shares() > 0L) {
         long value = Math.round(now * (double)held.shares());
         long profit = value - held.basis();
         lore.add(Component.literal("§7You hold: §f" + held.shares() + " shares"));
         lore.add(Component.literal("§7Worth: §f" + StockMenus.usd(value) + " §8(cost " + StockMenus.usd(held.basis()) + "§8)"));
         lore.add(
            Component.literal(
               "§7Profit: " + StockMenus.colour(profit) + (profit >= 0 ? "+" : "-") + StockMenus.usd(Math.abs(profit))
            )
         );
      } else {
         lore.add(Component.literal("§8You hold none of this."));
      }      lore.add(Component.literal(""));
      lore.add(Component.literal("§8§lVIEW §8· click for the chart, the"));
      lore.add(Component.literal("§8history and the buy and sell buttons"));
      ItemStack stack = StockMenus.lore(s.icon(), "§e§l" + s.name() + " §8(" + s.id() + ")", lore);
      if (held.shares() > 0L) {
         // The stack size is the number of shares, so a book reads at a glance.
         stack.setCount((int)Math.max(1L, Math.min(64L, held.shares())));
      }
      return stack;
   }

   /** A move in money, signed and formatted for a lore line. */
   private static String cash(double amount) {
      return (amount >= 0.0 ? "§a+" : "§c-") + StockMenus.usd(Math.abs(amount));
   }

   /**
    * The Market Scale: how much money this server's players actually hold, and the level the board
    * is being carried at because of it.
    *
    * <p>This is the window's headline, and it is the answer to the one question a market has to
    * answer immediately - "is this board priced for me?". The scale is a level, the benchmark is a
    * percentile rather than the richest wallet, the spread is on show, and the countdown says when
    * it will move next. Gradual recalculation is a promise about behaviour, so it is printed as one.
    */
   private ItemStack scaleTile(long tick) {
      MarketManager.Wealth w = MarketManager.wealth();
      double lvl = MarketManager.level();
      double live = MarketManager.liveIndexAt(tick);
      double clock = MarketManager.indexAt(tick);
      List<Component> lore = new ArrayList<>();
      lore.add(Component.literal("§7Market scale: §6x" + String.format(java.util.Locale.ROOT, "%.2f", lvl)));
      lore.add(Component.literal("§8Based on active-player wealth"));
      lore.add(Component.literal(""));
      if (w.players() == 0) {
         lore.add(Component.literal("§8No wallets on the server yet."));
         lore.add(Component.literal("§8The board trades at its designed prices."));
      } else {
         lore.add(Component.literal("§7Wallets counted: §f" + w.players()));
         lore.add(Component.literal("§7Benchmark §8(p90)§7: §a" + StockMenus.usd((double)w.p90())));
         lore.add(Component.literal("§7Median §8(p50)§7: §f" + StockMenus.usd((double)w.p50())));
         lore.add(Component.literal("§7Bottom §8(p10)§7: §c" + StockMenus.usd((double)w.p10())));
         lore.add(Component.literal("§7Richest: §a" + StockMenus.usd((double)w.peak())));
         lore.add(Component.literal("§7Poorest: §c" + StockMenus.usd((double)w.floor())));
      }
      lore.add(Component.literal(""));
      lore.add(Component.literal("§7Each shelf is aimed at a share of that"));
      lore.add(Component.literal("§7benchmark, and may not leave its band:"));
      for (MarketManager.Tier tier : MarketManager.Tier.values()) {
         lore.add(
            Component.literal(
               "§8 · " + shelfColour(tier) + tier.label + " §8aimed at §f"
                  + StockMenus.usd(tier.targetPrice(lvl)) + " §8(" + tier.affordableLabel() + ")"
            )
         );
         lore.add(
            Component.literal(
               "§8    band " + StockMenus.usd(tier.floorPrice(lvl)) + "-§8" + StockMenus.usd(tier.ceilingPrice(lvl))
            )
         );
      }
      lore.add(Component.literal(""));
      lore.add(Component.literal("§7Board at the clock: §f" + String.format(java.util.Locale.ROOT, "%.1f", clock)));
      lore.add(Component.literal("§7Board at the till: §f" + String.format(java.util.Locale.ROOT, "%.1f", live)));
      lore.add(Component.literal("§7Next update in §f" + StockMenus.duration(MarketManager.secondsToNextScale())));
      lore.add(Component.literal("§8Gradual recalculation: a quarter of"));
      lore.add(Component.literal("§8the gap per update, never more than 20%."));
      lore.add(Component.literal(""));
      lore.add(Component.literal("§8The clock sets the shape; the wallets"));
      lore.add(Component.literal("§8set the level it is drawn at; the players"));
      lore.add(Component.literal("§8themselves tilt a listing for a while"));
      lore.add(Component.literal("§8when they buy or sell into it."));
      return StockMenus.lore(Items.GOLD_INGOT, "§6§lMarket Scale §8· §f" + StockMenus.usd(MarketManager.benchmark()), lore);
   }

   /** One colour per shelf, so the table reads as shelves rather than as four identical lines. */
   private static String shelfColour(MarketManager.Tier tier) {
      return switch (tier) {
         case PENNY -> "§7";
         case STANDARD -> "§a";
         case PREMIUM -> "§e";
         case ELITE -> "§6";
      };
   }

   private ItemStack indexTile(long tick) {
      double index = MarketManager.indexAt(tick);
      double hour = MarketManager.indexAt(tick - 12L) == 0.0 ? 0.0 : index / MarketManager.indexAt(tick - 12L) - 1.0;
      double day = MarketManager.indexAt(tick - 288L) == 0.0 ? 0.0 : index / MarketManager.indexAt(tick - 288L) - 1.0;
      return StockMenus.tile(
         Items.NETHER_STAR,
         "§6§lThe Fortune Index §8" + String.format(java.util.Locale.ROOT, "%.1f", index),
         "§71000 = every listing exactly at its base.",
         "§7At the till: §f" + String.format(java.util.Locale.ROOT, "%.1f", MarketManager.liveIndexAt(tick))
            + " §8(after the market scale)",
         "",
         "§7Last hour: " + StockMenus.colour(hour) + StockMenus.pct(hour),
         "§7Last 24h: " + StockMenus.colour(day) + StockMenus.pct(day),
         "",
         "§8" + StockMenus.spark(MarketManager.indexSeries(tick, 24, 12), 24) + " §7a day, at the clock",
         "",
         "§8All " + MarketManager.STOCKS.size() + " listings move with one market."
      );
   }

   private ItemStack clockTile() {
      return StockMenus.tile(
         Items.CLOCK,
         "§e§lQuote clock",
         "§7A new price every §f5 minutes§7.",
         "§7Next quote in §f" + StockMenus.untilNextQuote() + "§7.",
         "§7Next scale update in §f" + StockMenus.duration(MarketManager.secondsToNextScale()) + "§7.",
         "",
         "§7The market runs on the world clock, not on",
         "§7who is logged in - it moves while you are",
         "§7offline, and the chart of a week you were",
         "§7away for is the week that actually happened.",
         "",
         "§8No announcements: a price is something you",
         "§8come here and look at."
      );
   }

   private ItemStack summaryTile(long wallet, long vault, long worth, long basis) {
      long profit = worth - basis;
      List<Component> lore = new ArrayList<>();
      lore.add(Component.literal("§7Wallet: §a" + Chat.moneyStr(wallet)));
      lore.add(Component.literal("§7Bank vault: §f" + Chat.moneyStr(vault)));
      lore.add(Component.literal("§7Shares: §f" + StockMenus.usd(worth)));
      lore.add(Component.literal("§7What they cost: §f" + StockMenus.usd(basis)));
      lore.add(
         Component.literal("§7Unrealised: " + StockMenus.colour(profit) + (profit >= 0 ? "+" : "-") + StockMenus.usd(Math.abs(profit)))
      );
      lore.add(Component.literal(""));
      lore.add(Component.literal("§7Net worth: §6" + StockMenus.usd(wallet + vault + worth)));
      lore.add(Component.literal(""));
      lore.add(Component.literal("§8Trades are funded from your wallet, then"));
      lore.add(Component.literal("§8your vault, and proceeds land in your wallet."));
      return StockMenus.lore(Items.GOLD_BLOCK, "§6§lYour position", lore);
   }

   private ItemStack howTile() {
      return StockMenus.tile(
         Items.BOOK,
         "§e§lHow the exchange works",
         "§7· Buy and sell at the current quote, from",
         "§7  the chart window of any listing.",
         "§7· Brokerage is §f1%§7 each way, so a round",
         "§7  trip needs a real move to pay for itself.",
         "§7· Every listing has a §fbase§7 it orbits and a",
         "§7  §fshelf§7 it cannot leave. A shelf is aimed",
         "§7  at a share of the benchmark - penny 0.5%,",
         "§7  standard 2%, premium 10%, elite 25% - so",
         "§7  the board rises with the server's wallets.",
         "§7· Trading moves the price. A big buy lifts a",
         "§7  listing, a big sell drops it, and the mark",
         "§7  fades over the next few quotes.",
         "§7· Eleven listings share one market mood: on",
         "§7  a bad day, most of the board is red.",
         "§7· The whole board is carried at the §6Market",
         "§6  Scale§7 - the level the server's own wealth",
         "§7  asks for - so the board is never priced",
         "§7  for a player who does not exist.",
         "§7· One outlier cannot move it: the level is",
         "§7  built from the §f90th percentile§7, not the",
         "§7  richest wallet, and it moves gradually.",
         "",
         "§8Nothing here is announced in chat."
      );
   }

   private ItemStack moversTile(long tick) {
      Stock bestDay = null;
      Stock worstDay = null;
      Stock bestHour = null;
      Stock worstHour = null;
      double bestDayPct = -Double.MAX_VALUE;
      double worstDayPct = Double.MAX_VALUE;
      double bestHourPct = -Double.MAX_VALUE;
      double worstHourPct = Double.MAX_VALUE;
      for (Stock s : MarketManager.STOCKS) {
         double day = MarketManager.change(s, tick, 288);
         double hour = MarketManager.change(s, tick, 12);
         if (day > bestDayPct) {
            bestDayPct = day;
            bestDay = s;
         }
         if (day < worstDayPct) {
            worstDayPct = day;
            worstDay = s;
         }
         if (hour > bestHourPct) {
            bestHourPct = hour;
            bestHour = s;
         }
         if (hour < worstHourPct) {
            worstHourPct = hour;
            worstHour = s;
         }
      }
      return StockMenus.tile(
         Items.COMPASS,
         "§e§lMovers",
         "§8Over the last 24 hours",
         "§7Up: §a" + StockMenus.pct(bestDayPct) + " §f" + (bestDay == null ? "-" : bestDay.id()),
         "§7Down: §c" + StockMenus.pct(worstDayPct) + " §f" + (worstDay == null ? "-" : worstDay.id()),
         "",
         "§8Over the last hour",
         "§7Up: §a" + StockMenus.pct(bestHourPct) + " §f" + (bestHour == null ? "-" : bestHour.id()),
         "§7Down: §c" + StockMenus.pct(worstHourPct) + " §f" + (worstHour == null ? "-" : worstHour.id()),
         "",
         "§8Past moves, not advice."
      );
   }

   /** Every holding, with what it cost and what it is worth - the whole book on one tile. */
   private ItemStack portfolioTile(long tick) {
      Map<String, Position> held = MarketManager.holdings(this.owner.getUUID());
      List<Component> lore = new ArrayList<>();
      if (held.isEmpty()) {
         lore.add(Component.literal("§8You hold nothing yet."));
         lore.add(Component.literal("§8Open a listing and buy a share."));
      } else {
         long total = 0L;
         long cost = 0L;
         for (Map.Entry<String, Position> e : held.entrySet()) {
            Stock s = MarketManager.byId(e.getKey());
            if (s == null) {
               continue;
            }
            long value = Math.round(MarketManager.quoteOf(s, tick) * (double)e.getValue().shares());
            long profit = value - e.getValue().basis();
            total += value;
            cost += e.getValue().basis();
            lore.add(
               Component.literal(
                  "§7" + s.id() + " §8x§f" + e.getValue().shares() + " §7" + StockMenus.usd(value) + " "
                     + StockMenus.colour(profit) + StockMenus.pct(e.getValue().basis() <= 0L ? 0.0 : (double)profit / (double)e.getValue().basis())
               )
            );
         }
         long profit = total - cost;
         lore.add(Component.literal(""));
         lore.add(Component.literal("§7Worth: §f" + StockMenus.usd(total) + " §8(cost " + StockMenus.usd(cost) + "§8)"));
         lore.add(
            Component.literal("§7Unrealised: " + StockMenus.colour(profit) + (profit >= 0 ? "+" : "-") + StockMenus.usd(Math.abs(profit)))
         );
         lore.add(Component.literal("§8Sell from a listing's chart window."));
      }
      return StockMenus.lore(Items.CHEST, "§6§lPortfolio", lore);
   }

   // --- clicks --------------------------------------------------------------------------------

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
      for (int i = 0; i < LISTINGS.length; i++) {
         if (slotId == LISTINGS[i] && i < MarketManager.STOCKS.size()) {
            SoundUtil.play(sp, ModSounds.PAGE_FLIP);
            this.returnCarried(sp);
            StockTradeMenu.open(sp, MarketManager.STOCKS.get(i));
            return;
         }
      }
      switch (slotId) {
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
         default -> this.rebuild();
      }
      this.returnCarried(sp);
      this.rebuild();
   }

   public ItemStack quickMoveStack(Player player, int index) {
      return ItemStack.EMPTY;
   }
}
