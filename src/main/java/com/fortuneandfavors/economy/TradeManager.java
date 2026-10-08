package com.fortuneandfavors.economy;

import com.fortuneandfavors.ModItems;
import com.fortuneandfavors.ModSounds;
import com.fortuneandfavors.economy.TradeManager.Trade;
import com.fortuneandfavors.menu.TradeMenu;
import com.fortuneandfavors.util.Chat;
import com.fortuneandfavors.util.InventoryHelper;
import com.fortuneandfavors.util.JsonUtil;
import com.fortuneandfavors.util.SoundUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ClickEvent.RunCommand;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import java.util.HashSet;
import java.util.Set;

/**
 * Player-to-player trading, and the exchange's own memory of it.
 *
 * <p>The window itself is two offers and an accept button, and that part was always fine. What was
 * missing was everything that makes a trade a market rather than two players guessing at each other:
 * what this server actually pays for a thing, and what the person opposite you has done before.
 * <b>Supply and demand</b> is measured rather than invented - every item and every dollar that moves
 * through a completed trade is tallied, so the window can say "nine stacks of this have changed
 * hands this week at about this much" - and every player keeps a <b>history</b> of their own last
 * trades. Neither is a price feed: the numbers are this server's own players, which is the only
 * market that exists here.
 */
public final class TradeManager {
   private static final Map<UUID, Trade> activeTrades = new HashMap<>();
   private static final Map<UUID, UUID> requests = new HashMap<>();
   /** How many trades of one's own the window remembers. */
   public static final int HISTORY = 8;
   /** How far back the market tallies run, in ticks - one week of real time. */
   public static final long MARKET_WINDOW_TICKS = 20L * 60L * 60L * 24L * 7L;

   /**
    * One item's half of the market: what moved, and what it fetched.
    *
    * @param item  the registry id
    * @param units how many of it traded inside the window
    * @param cash  what the trades carrying it were worth, for an implied rate
    */
   public record Flow(String item, long units, long cash) {
      /** The observed price per unit this server's players are actually paying, or 0 with no data. */
      public long rate() {
         return units <= 0L ? 0L : cash / units;
      }
   }

   /**
    * One line of a player's own history.
    *
    * @param partner  who it was with
    * @param at       the server tick it settled on
    * @param gave     what this player's side was worth
    * @param got      what the other side was worth
    */
   public record Entry(String partner, long at, long gave, long got) {
      /** Positive when this player came out ahead on the server's own valuation. */
      public long edge() {
         return got - gave;
      }
   }

   private static final Map<String, long[]> market = new HashMap<>();
   private static final Map<UUID, List<Entry>> history = new HashMap<>();
   private static Path dataFile;
   private static boolean dirty = false;
   /** Test seam: the tick the tallies are aged against, or 0 to use the live one. */
   private static long clockForTest = 0L;

   private TradeManager() {
   }

   // ------------------------------------------------------------------ the market's memory

   /**
    * Records a settled trade into the market tallies and both players' histories.
    *
    * <p>Called once, from the completion path, with what actually moved. A trade with cash on both
    * sides is recorded as one line per player rather than netted, because "I sold a stack for
    * twenty thousand" and "I bought a stack for twenty thousand" are two different facts about the
    * server even when they are the same event.
    */
   static void recordTrade(MinecraftServer server, Trade trade, long aValue, long bValue) {
      if (server == null || trade == null) {
         return;
      }
      long now = server.getTickCount();
      long cashMoved = trade.aCash + trade.bCash;
      Set<String> seen = new HashSet<>();
      tally(trade.aItems, now, cashMoved, seen);
      tally(trade.bItems, now, cashMoved, seen);
      // Each player's own line: what their side was worth against what they received.
      if (trade.a != null) {
         remember(trade.a, trade.b == null ? "a player" : trade.b.getName().getString(), now, aValue, bValue);
      }
      if (trade.b != null) {
         remember(trade.b, trade.a == null ? "a player" : trade.a.getName().getString(), now, bValue, aValue);
      }
      dirty = true;
      save(server);
   }

   /** Adds one side's items into the tallies, once per item id. */
   private static void tally(SimpleContainer items, long now, long cashMoved, Set<String> seen) {
      for (int i = 0; i < items.getContainerSize(); i++) {
         ItemStack stack = items.getItem(i);
         if (stack == null || stack.isEmpty()) {
            continue;
         }
         String id = itemId(stack);
         if (id == null || !seen.add(id)) {
            continue;
         }
         long[] row = market.computeIfAbsent(id, k -> new long[]{0L, 0L, now});
         row[0] += stack.getCount();
         row[1] += cashMoved;
         row[2] = now;
      }
   }

   private static void remember(ServerPlayer player, String partner, long at, long gave, long got) {
      List<Entry> mine = history.computeIfAbsent(player.getUUID(), k -> new ArrayList<>());
      mine.add(0, new Entry(partner, at, gave, got));
      while (mine.size() > HISTORY) {
         mine.remove(mine.size() - 1);
      }
   }

   private static String itemId(ItemStack stack) {
      try {
         return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
      } catch (Throwable t) {
         return null;
      }
   }

   /** The market's view of one item, with anything that has aged out of the window dropped. */
   public static Flow flowOf(String item) {
      long[] row = market.get(item);
      if (row == null) {
         return new Flow(item, 0L, 0L);
      }
      if (isStale(row[2])) {
         return new Flow(item, 0L, 0L);
      }
      return new Flow(item, row[0], row[1]);
   }

   private static boolean isStale(long at) {
      long now = clockForTest > 0L ? clockForTest : liveTick();
      return now - at > MARKET_WINDOW_TICKS;
   }

   /**
    * The items this server is actually moving, busiest first.
    *
    * <p>The whole of "supply and demand" here, and deliberately measured rather than modelled: it
    * is a tally of what players chose to trade with each other, not a price the server has decided
    * on. An item nobody has ever traded is not on the list at all, which is the honest answer.
    */
   public static List<Flow> hottest(int limit) {
      List<Flow> out = new ArrayList<>();
      for (String id : market.keySet()) {
         Flow flow = flowOf(id);
         if (flow.units() > 0L) {
            out.add(flow);
         }
      }
      out.sort(Comparator.comparingLong(Flow::units).reversed());
      return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
   }

   /** A player's own last trades, newest first. */
   public static List<Entry> history(UUID player) {
      List<Entry> mine = history.get(player);
      return mine == null ? List.of() : List.copyOf(mine);
   }

   /** Test seam: pin the clock the windows are aged against. */
   public static void clockForTest(long tick) {
      clockForTest = Math.max(0L, tick);
   }

   /** Test seam: forget every tally and every history. */
   public static void forgetMarketForTest() {
      market.clear();
      history.clear();
   }

   /** Test seam: write one item's tally directly, so a check need not run a trade. */
   public static void noteFlowForTest(String item, long units, long cash, long at) {
      long[] row = market.computeIfAbsent(item, k -> new long[]{0L, 0L, at});
      row[0] += units;
      row[1] += cash;
      row[2] = at;
   }

   private static long liveTick() {
      return System.currentTimeMillis() / 50L;
   }

   // ------------------------------------------------------------------ persistence

   public static void load(MinecraftServer server) {
      market.clear();
      history.clear();
      dataFile = EconomyManager.getDataDir(server).resolve("trade_market.json");
      JsonObject root = JsonUtil.readOrCreate(dataFile, new JsonObject());
      if (root.has("items") && root.get("items").isJsonObject()) {
         for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("items").entrySet()) {
            try {
               JsonObject one = e.getValue().getAsJsonObject();
               long units = Math.max(0L, JsonUtil.jsonLong(one, "units", 0L));
               long cash = Math.max(0L, JsonUtil.jsonLong(one, "cash", 0L));
               long at = JsonUtil.jsonLong(one, "at", liveTick());
               if (units > 0L) {
                  market.put(e.getKey(), new long[]{units, cash, at});
               }
            } catch (Exception ignored) {
            }
         }
      }
      if (root.has("history") && root.get("history").isJsonObject()) {
         for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("history").entrySet()) {
            try {
               UUID id = UUID.fromString(e.getKey());
               List<Entry> mine = new ArrayList<>();
               for (JsonElement je : e.getValue().getAsJsonArray()) {
                  JsonObject one = je.getAsJsonObject();
                  mine.add(
                     new Entry(
                        JsonUtil.jsonString(one, "partner", "a player"),
                        JsonUtil.jsonLong(one, "at", 0L),
                        JsonUtil.jsonLong(one, "gave", 0L),
                        JsonUtil.jsonLong(one, "got", 0L)
                     )
                  );
               }
               if (!mine.isEmpty()) {
                  history.put(id, mine.subList(0, Math.min(HISTORY, mine.size())));
               }
            } catch (Exception ignored) {
            }
         }
      }
      dirty = false;
   }

   public static void save(MinecraftServer server) {
      if (server == null) {
         return;
      }
      if (dataFile == null) {
         dataFile = EconomyManager.getDataDir(server).resolve("trade_market.json");
      }
      JsonObject root = new JsonObject();
      JsonObject items = new JsonObject();
      for (Map.Entry<String, long[]> e : market.entrySet()) {
         JsonObject one = new JsonObject();
         one.addProperty("units", e.getValue()[0]);
         one.addProperty("cash", e.getValue()[1]);
         one.addProperty("at", e.getValue()[2]);
         items.add(e.getKey(), one);
      }
      root.add("items", items);
      JsonObject people = new JsonObject();
      for (Map.Entry<UUID, List<Entry>> e : history.entrySet()) {
         JsonArray arr = new JsonArray();
         for (Entry line : e.getValue()) {
            JsonObject one = new JsonObject();
            one.addProperty("partner", line.partner());
            one.addProperty("at", line.at());
            one.addProperty("gave", line.gave());
            one.addProperty("got", line.got());
            arr.add(one);
         }
         people.add(e.getKey().toString(), arr);
      }
      root.add("history", people);
      JsonUtil.write(dataFile, root);
      dirty = false;
   }

   public static boolean isDirty() {
      return dirty;
   }

   public static boolean isTrading(ServerPlayer player) {
      return activeTrades.containsKey(player.getUUID());
   }

   public static Trade tradeOf(ServerPlayer player) {
      return activeTrades.get(player.getUUID());
   }

   public static boolean requestTrade(ServerPlayer requester, ServerPlayer target) {
      if (requester.getUUID().equals(target.getUUID())) {
         Chat.msg(requester, "&cYou can't trade with yourself.");
         return false;
      }

      if (!isTrading(requester) && !isTrading(target)) {
         UUID existing = requests.get(requester.getUUID());
         if (target.getUUID().equals(existing)) {
            requests.remove(target.getUUID());
            openTrade(requester, target);
            return true;
         } else {
            requests.put(target.getUUID(), requester.getUUID());
            Chat.raw(requester, "&aTrade request sent to &f" + target.getName().getString() + "&a.");
            Component accept = Component.literal("§a[ACCEPT]").withStyle(s -> s.withClickEvent(new RunCommand("/tradeaccept")));
            target.sendSystemMessage(
               Component.literal("§e" + requester.getName().getString() + "§7 wants to trade with you! Type §f/tradeaccept§7 to accept, or click: ")
                  .append(accept)
            );
            return true;
         }
      } else {
         Chat.msg(requester, "&cOne of you is already in a trade.");
         return false;
      }
   }

   public static boolean acceptRequest(ServerPlayer player) {
      UUID requesterId = requests.remove(player.getUUID());
      if (requesterId == null) {
         Chat.msg(player, "&cYou have no pending trade requests.");
         return false;
      } else {
         ServerPlayer requester = player.level().getServer().getPlayerList().getPlayer(requesterId);
         if (requester == null) {
            Chat.msg(player, "&cThat player is no longer online.");
            return false;
         } else {
            openTrade(requester, player);
            return true;
         }
      }
   }

   private static void openTrade(ServerPlayer a, ServerPlayer b) {
      Trade trade = new Trade(a, b);
      activeTrades.put(a.getUUID(), trade);
      activeTrades.put(b.getUUID(), trade);
      a.openMenu(
         new SimpleMenuProvider((syncId, inv, p) -> new TradeMenu(syncId, inv, trade, true), Component.literal("§d§lTrading with §f" + b.getName().getString()))
      );
      b.openMenu(
         new SimpleMenuProvider(
            (syncId, inv, p) -> new TradeMenu(syncId, inv, trade, false), Component.literal("§d§lTrading with §f" + a.getName().getString())
         )
      );
   }

   public static void sync(Trade trade) {
      TradeMenu menuA = TradeMenu.menuOf(trade.a);
      TradeMenu menuB = TradeMenu.menuOf(trade.b);
      if (menuA != null) {
         menuA.rebuildFromSession();
      }

      if (menuB != null) {
         menuB.rebuildFromSession();
      }
   }

   public static void sideEdited(Trade trade, ServerPlayer player) {
      TradeMenu menu = TradeMenu.menuOf(player);
      if (menu != null) {
         SimpleContainer items = trade.itemsOf(player);

         for (int i = 0; i < 17; i++) {
            items.setItem(i, menu.containerItem(TradeMenu.slotForOfferIndex(i)));
         }

         for (int i = 17; i < 27; i++) {
            items.setItem(i, ItemStack.EMPTY);
         }

         trade.aAccepted = false;
         trade.bAccepted = false;
         sync(trade);
      }
   }

   public static void setCash(Trade trade, ServerPlayer player, long amount) {
      if (amount > 0L && !trade.done) {
         long balance = EconomyManager.balance(player.getUUID());
         long current = player == trade.a ? trade.aCash : trade.bCash;
         if (current + amount > balance) {
            Chat.msg(player, "&cYou don't have that much money! Balance: " + Chat.moneyStr(balance));
         } else {
            if (player == trade.a) {
               trade.aCash += amount;
            } else {
               trade.bCash += amount;
            }

            trade.aAccepted = false;
            trade.bAccepted = false;
            sync(trade);
         }
      }
   }

   public static void toggleAccept(Trade trade, ServerPlayer player) {
      if (player == trade.a) {
         trade.aAccepted = !trade.aAccepted;
      } else {
         trade.bAccepted = !trade.bAccepted;
      }

      sync(trade);
      if (trade.aAccepted && trade.bAccepted) {
         execute(trade);
      }
   }

   private static void execute(Trade trade) {
      if (!trade.done) {
         ServerPlayer a = trade.a;
         ServerPlayer b = trade.b;

         for (int i = 0; i < 27; i++) {
            if (ModItems.isMachineItem(trade.aItems.getItem(i)) || ModItems.isMachineItem(trade.bItems.getItem(i))) {
               Chat.msg(a, "&cTrade failed - machines can't be traded.");
               Chat.msg(b, "&cTrade failed - machines can't be traded.");
               cancel(trade, a);
               return;
            }
         }

         if (EconomyManager.hasCash(a.getUUID(), trade.aCash) && EconomyManager.hasCash(b.getUUID(), trade.bCash)) {
            trade.executed = true;
            trade.done = true;
            // The exchange's own memory, taken before the offers are emptied: what moved, and what
            // it was worth. See recordTrade - this is the only write to the market's tallies.
            recordTrade(
               a == null ? null : a.level().getServer(), trade, offerValue(trade.aItems) + trade.aCash,
               offerValue(trade.bItems) + trade.bCash
            );

            for (int i = 0; i < 27; i++) {
               ItemStack sa = trade.aItems.getItem(i);
               if (!sa.isEmpty()) {
                  ItemStack give = sa.copy();
                  if (ModItems.isSpawnerItem(give)) {
                     ModItems.bindSpawner(give, b.getUUID(), b.getName().getString());
                  }

                  InventoryHelper.giveOrDrop(b, give);
               }

               ItemStack sb = trade.bItems.getItem(i);
               if (!sb.isEmpty()) {
                  ItemStack give = sb.copy();
                  if (ModItems.isSpawnerItem(give)) {
                     ModItems.bindSpawner(give, a.getUUID(), a.getName().getString());
                  }

                  InventoryHelper.giveOrDrop(a, give);
               }

               trade.aItems.setItem(i, ItemStack.EMPTY);
               trade.bItems.setItem(i, ItemStack.EMPTY);
            }

            if (trade.aCash > 0L) {
               EconomyManager.pay(a.getUUID(), b.getUUID(), trade.aCash);
            }

            if (trade.bCash > 0L) {
               EconomyManager.pay(b.getUUID(), a.getUUID(), trade.bCash);
            }

            SoundUtil.play(a, ModSounds.TRANSFER);
            SoundUtil.play(b, ModSounds.TRANSFER);
            Chat.raw(
               a,
               "&a&lTrade complete!&r &7You "
                  + (trade.bCash > 0L ? "received " + Chat.moneyStr(trade.bCash) : "swapped")
                  + " with &f"
                  + b.getName().getString()
                  + "&7."
            );
            Chat.raw(
               b,
               "&a&lTrade complete!&r &7You "
                  + (trade.aCash > 0L ? "received " + Chat.moneyStr(trade.aCash) : "swapped")
                  + " with &f"
                  + a.getName().getString()
                  + "&7."
            );
            JobManager.onTrade(a);
            JobManager.onTrade(b);
            SkillManager.onTrade(a);
            SkillManager.onTrade(b);
            DailyWeeklyChallengeManager.onTrade(a);
            DailyWeeklyChallengeManager.onTrade(b);
            closeBoth(trade);
         } else {
            Chat.msg(a, "&cTrade failed - one of you doesn't have enough cash.");
            Chat.msg(b, "&cTrade failed - one of you doesn't have enough cash.");
            cancel(trade, a);
         }
      }
   }

   /** What one side's items are worth on the server's own books. */
   private static long offerValue(SimpleContainer items) {
      long total = 0L;
      if (items == null) {
         return 0L;
      }
      for (int i = 0; i < items.getContainerSize(); i++) {
         ItemStack stack = items.getItem(i);
         if (stack != null && !stack.isEmpty()) {
            total += BlockValues.valueOf(stack) * stack.getCount();
         }
      }
      return total;
   }

   public static void cancel(Trade trade, ServerPlayer byPlayer) {
      if (!trade.done) {
         trade.done = true;

         for (int i = 0; i < 27; i++) {
            ItemStack sa = trade.aItems.getItem(i);
            if (!sa.isEmpty()) {
               InventoryHelper.giveOrDrop(trade.a, sa);
               trade.aItems.setItem(i, ItemStack.EMPTY);
            }

            ItemStack sb = trade.bItems.getItem(i);
            if (!sb.isEmpty()) {
               InventoryHelper.giveOrDrop(trade.b, sb);
               trade.bItems.setItem(i, ItemStack.EMPTY);
            }
         }

         Chat.raw(trade.other(byPlayer), "&c" + byPlayer.getName().getString() + "&c cancelled the trade.");
         closeBoth(trade);
      }
   }

   private static void closeBoth(Trade trade) {
      activeTrades.remove(trade.a.getUUID());
      activeTrades.remove(trade.b.getUUID());
      TradeMenu menuA = TradeMenu.menuOf(trade.a);
      TradeMenu menuB = TradeMenu.menuOf(trade.b);
      if (menuA != null && trade.a.containerMenu == menuA) {
         trade.a.closeContainer();
      }

      if (menuB != null && trade.b.containerMenu == menuB) {
         trade.b.closeContainer();
      }
   }


    static public class Trade {
       public final ServerPlayer a;
       public final ServerPlayer b;
       public final SimpleContainer aItems = new SimpleContainer(27);
       public final SimpleContainer bItems = new SimpleContainer(27);
       public long aCash = 0L;
       public long bCash = 0L;
       public boolean aAccepted = false;
       public boolean bAccepted = false;
       public boolean done = false;
       public boolean executed = false;
       public final Set<UUID> cashInputOpen = new HashSet<>();
    
       Trade(ServerPlayer a, ServerPlayer b) {
          this.a = a;
          this.b = b;
       }
    
       public ServerPlayer other(ServerPlayer self) {
          return self == this.a ? this.b : this.a;
       }
    
       public SimpleContainer itemsOf(ServerPlayer self) {
          return self == this.a ? this.aItems : this.bItems;
       }
    
       public SimpleContainer itemsOfOther(ServerPlayer self) {
          return self == this.a ? this.bItems : this.aItems;
       }
    }
}
