package com.fortuneandfavors.menu;

import com.fortuneandfavors.economy.MarketManager;
import com.fortuneandfavors.util.Safe;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

/**
 * The parts every stock window shares: the quote tiles, the chart, and the clock.
 *
 * <p>The clock is the interesting one. A price only changes when the five-minute grid rolls over,
 * so a chart left open through the rollover would show a stale last column until the player
 * happened to click something - which is exactly the sort of small wrongness that makes a live
 * market feel dead. {@link #tick} compares the market's tick index once a second and redraws every
 * window that is open when it moves, so the chart updates itself on the quote and stays current
 * whether or not its owner is touching anything.
 */
public final class StockMenus {
   /**
    * A window that can redraw itself when a new quote prints.
    *
    * <p>Vanilla will not redraw a merchant's inventory for us - a chest menu is only re-sent when
    * its contents change - so both stock windows implement this and answer the market's clock.
    */
   interface MarketView {
      void refresh();
   }

   private static long lastTick = Long.MIN_VALUE;

   private StockMenus() {
   }

   /** Called once a second: when the quote grid rolls over, redraw whatever is open. */
   public static void tick(MinecraftServer server) {
      long tick = MarketManager.currentTick();
      if (tick == lastTick) {
         return;
      }
      lastTick = tick;
      for (ServerPlayer player : List.copyOf(server.getPlayerList().getPlayers())) {
         if (player.containerMenu instanceof MarketView view) {
            Safe.run("market chart refresh", view::refresh);
         }
      }
   }

   /** Test seam: forget which quote is current, so a check can watch the rollover fire. */
   public static void resetClockForTest() {
      lastTick = Long.MIN_VALUE;
   }

   // --- drawing ------------------------------------------------------------------------------

   private static final String SPARK = "▁▂▃▄▅▆▇█";

   /** A blank pane, so the chart's empty cells read as grid rather than as holes. */
   public static ItemStack blank(Item item) {
      ItemStack stack = new ItemStack(item);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal(" "));
      return stack;
   }

   public static ItemStack tile(Item item, String name, String... lines) {
      ItemStack stack = new ItemStack(item);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
      List<Component> lore = new ArrayList<>();
      for (String line : lines) {
         lore.add(Component.literal(line));
      }
      stack.set(DataComponents.LORE, new ItemLore(lore));
      return stack;
   }

   public static ItemStack lore(Item item, String name, List<Component> lines) {
      ItemStack stack = new ItemStack(item);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
      stack.set(DataComponents.LORE, new ItemLore(lines));
      return stack;
   }

   /**
    * Draws the price chart: nine columns, oldest on the left, each one a stack of panes whose
    * height is the price.
    *
    * <p>The scale is the window's own min and max, so the shape fills the grid and a quiet week is
    * as readable as a violent hour - the axis the numbers are on is written on the tile beside it.
    * A flat series is given a floor span rather than being amplified into a mountain range.
    *
    * @param series     nine prices, oldest first
    * @param rows       how tall the grid is
    * @param bottomLeft the slot of the oldest column's bottom cell
    */
   public static void chart(SimpleContainer container, double[] series, int rows, int bottomLeft) {
      double min = Double.MAX_VALUE;
      double max = -Double.MAX_VALUE;
      for (double v : series) {
         min = Math.min(min, v);
         max = Math.max(max, v);
      }
      double span = Math.max(max - min, Math.max(1.0, max * 0.005));
      double lo = min - span * 0.06;
      double hi = max + span * 0.06;
      double range = Math.max(1.0E-9, hi - lo);
      ItemStack empty = blank((Item)Items.STAINED_GLASS_PANE.black());
      ItemStack upPane = blank((Item)Items.STAINED_GLASS_PANE.lime());
      ItemStack downPane = blank((Item)Items.STAINED_GLASS_PANE.red());
      for (int c = 0; c < 9; c++) {
         double v = series[Math.min(c, series.length - 1)];
         // The oldest column has nothing before it to compare with, so it is coloured by the move
         // that follows it - otherwise a chart that fell all week starts with one green column and
         // reads as a bounce that never happened.
         boolean up = c == 0
            ? (series.length < 2 || series[0] <= series[1])
            : v >= series[Math.min(c - 1, series.length - 1)];
         int height = 1 + (int)Math.round((v - lo) / range * (double)(rows - 1));
         height = Math.max(1, Math.min(rows, height));
         for (int level = 0; level < rows; level++) {
            int slot = bottomLeft + c - level * 9;
            container.setItem(slot, level < height ? (up ? upPane : downPane).copy() : empty.copy());
         }
         if (c == 8) {
            // The newest column carries a marker on its top cell: the last quote, and the one the
            // buy and sell buttons are about to use.
            int top = bottomLeft + c - (height - 1) * 9;
            container.setItem(top, new ItemStack(up ? (Item)Items.EMERALD : (Item)Items.REDSTONE));
         }
      }
   }

   /** A one-line chart: the same series in eight steps of block character. */
   public static String spark(double[] series, int width) {
      if (series == null || series.length == 0) {
         return "";
      }
      double min = Double.MAX_VALUE;
      double max = -Double.MAX_VALUE;
      for (double v : series) {
         min = Math.min(min, v);
         max = Math.max(max, v);
      }
      double span = Math.max(max - min, Math.max(1.0E-9, max * 0.002));
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < width; i++) {
         double v = series[(int)Math.min(series.length - 1L, (long)i * series.length / width)];
         int step = (int)Math.round((v - min) / span * (SPARK.length() - 1));
         sb.append(SPARK.charAt(Math.max(0, Math.min(SPARK.length() - 1, step))));
      }
      return sb.toString();
   }

   /** "+3.42%" / "-0.80%" - a signed percentage, the way a quote is read. */
   public static String pct(double fraction) {
      return String.format(Locale.ROOT, "%+.2f%%", fraction * 100.0);
   }

   /** Green when it is up, red when it is down: one colour for a whole line. */
   public static String colour(double fraction) {
      return fraction >= 0.0 ? "§a" : "§c";
   }

   public static String usd(double amount) {
      return "$" + MarketManager.round2(amount);
   }

   /** "2m 41s" until the next quote - the countdown both windows show. */
   public static String untilNextQuote() {
      return duration(MarketManager.secondsToNextTick());
   }

   /** Seconds as "12m 41s" / "41s" - the clock readout every countdown uses. */
   public static String duration(long seconds) {
      long left = Math.max(0L, seconds);
      long minutes = left / 60L;
      return minutes > 0L ? minutes + "m " + (left % 60L) + "s" : left + "s";
   }
}
