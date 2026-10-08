package com.fortuneandfavors.economy;

import com.fortuneandfavors.util.Chat;
import com.fortuneandfavors.util.JsonUtil;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.WeekFields;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.Map.Entry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** Weekly lottery. Players buy tickets with cash (/lottery), the pot accumulates,
 *  and every Monday at midnight EST one weighted winner takes 75% of the pool -
 *  25% rolls over so the jackpot keeps growing. The result is announced in chat
 *  and reported in the next paper (the LOTTERY section of ServerNewspaperManager).
 *
 *  <p>On top of the pot, the house pays a &bparticipation bonus&r that scales
 *  with how many tickets were sold AND how many different players bought them:
 *  the more of the server plays, the bigger the jackpot - a crowd is worth far
 *  more than the same number of tickets from one whale. */
public final class LotteryManager {
   public static final long TICKET_PRICE = 100L;
   public static final double WINNER_SHARE = 0.75;
   public static final int MAX_TICKETS_PER_PLAYER = 100;
   /** The server-funded seed: a fresh pot starts at this much, and every new
    *  week's pot never begins below it (the server tops the rollover up). */
   public static final long STARTING_POT = 50_000L;
   /** Participation bonus, paid by the server on top of the 75% cut: the more
    *  players who buy in during the week, the bigger the jackpot grows, so a
    *  busy week is worth far more than the raw ticket money suggests. Tickets
    *  alone would only ever add ticket price x count. */
   public static final long BONUS_PER_TICKET = 25L;
   public static final long BONUS_PER_BUYER = 2_500L;
   /**
    * The share of the server's own wealth spread the house adds to a jackpot.
    *
    * <p>A pot fed only by tickets is a pot that is the same size on every server, which makes a
    * jackpot worth a fortune on a young world and pocket money on a late one. So the prize is also
    * read off the people standing on the server: the distance between the tenth-percentile wallet
    * and the ninetieth one - what the poorest regular carries against what the richest one does -
    * is the one number that says how much money this place has in play. Five percent of that spread
    * is the house's contribution, and it is multiplied by how much of the server actually bought in,
    * so a jackpot still needs a crowd to be big. A server with one player has no spread at all and
    * therefore no bonus, which is deliberate: the house pays for a shared game, not for a solo one.
    */
   public static final double SPREAD_SHARE = 0.05;
   /** How many buyers count as a full house for the spread bonus. */
   public static final int SPREAD_FULL_CROWD = 4;
   private static final Map<UUID, Integer> tickets = new HashMap<>();
   private static final Map<UUID, String> names = new HashMap<>();
   private static long pool = 0L;
   private static long weekTopBuyAmount = 0L;
   private static String weekTopBuyName = "";
   private static String lastDrawWeek = "";
   private static String lastResult = "";
   private static boolean reported = true;
   private static Path dataFile;

   private LotteryManager() {
   }

   public static void load(MinecraftServer server) {
      tickets.clear();
      names.clear();
      dataFile = EconomyManager.getDataDir(server).resolve("lottery.json");
      JsonObject root = JsonUtil.readOrCreate(dataFile, new JsonObject());
      pool = Math.max(0L, JsonUtil.jsonLong(root, "pool", 0L));
      lastDrawWeek = JsonUtil.jsonString(root, "last_draw_week", "");
      lastResult = JsonUtil.jsonString(root, "last_result", "");
      reported = JsonUtil.jsonBool(root, "reported", true);
      // Fresh server: the lottery opens with a seeded pot instead of $0.
      if (pool <= 0L && lastDrawWeek.isEmpty()) {
         pool = STARTING_POT;
      }
      if (root.has("players") && root.get("players").isJsonObject()) {
         for (Entry<String, JsonElement> e : root.getAsJsonObject("players").entrySet()) {
            try {
               UUID uuid = UUID.fromString(e.getKey());
               int count = Math.max(0, e.getValue().getAsInt());
               if (count > 0) {
                  tickets.put(uuid, Math.min(MAX_TICKETS_PER_PLAYER, count));
               }
            } catch (Exception ignored) {
            }
         }
      }
      if (root.has("names") && root.get("names").isJsonObject()) {
         for (Entry<String, JsonElement> e : root.getAsJsonObject("names").entrySet()) {
            try {
               names.put(UUID.fromString(e.getKey()), e.getValue().getAsString());
            } catch (Exception ignored) {
            }
         }
      }
   }

   public static void save(MinecraftServer server) {
      if (dataFile == null) {
         dataFile = EconomyManager.getDataDir(server).resolve("lottery.json");
      }
      JsonObject root = new JsonObject();
      root.addProperty("pool", pool);
      root.addProperty("last_draw_week", lastDrawWeek);
      root.addProperty("last_result", lastResult);
      root.addProperty("reported", reported);
      JsonObject players = new JsonObject();
      for (Entry<UUID, Integer> e : tickets.entrySet()) {
         if (e.getValue() != null && e.getValue() > 0) {
            players.addProperty(e.getKey().toString(), e.getValue());
         }
      }
      root.add("players", players);
      JsonObject playerNames = new JsonObject();
      for (Entry<UUID, String> e : names.entrySet()) {
         playerNames.addProperty(e.getKey().toString(), e.getValue());
      }
      root.add("names", playerNames);
      JsonUtil.write(dataFile, root);
   }

   /** Runs every server tick: when the ISO week rolls over (Monday midnight EST)
    *  the jackpot is drawn BEFORE the newspaper tick, so the paper can report it. */
   public static void tick(MinecraftServer server) {
      String week = currentWeek();
      if (!week.equals(lastDrawWeek)) {
         lastDrawWeek = week;
      draw(server);
      save(server);
      }
   }

   private static void draw(MinecraftServer server) {
      long total = 0L;
      for (int c : tickets.values()) {
         total += c;
      }
      if (total <= 0L) {
         tickets.clear();
         names.clear();
         lastResult = "";
         reported = true;
         pool = Math.max(STARTING_POT, pool);
         broadcast(server, "&7No tickets were sold this week - the lottery pot of &f$" + pool + "&7 rolls over. Buy in with &e/lottery&7!");
         return;
      }

      long roll = new Random().nextLong(total);
      UUID winner = null;
      long acc = 0L;
      for (Entry<UUID, Integer> e : tickets.entrySet()) {
         acc += e.getValue();
         if (roll < acc) {
            winner = e.getKey();
            break;
         }
      }
      if (winner == null) {
         winner = tickets.keySet().iterator().next();
      }

      long cut = (long)(pool * WINNER_SHARE);
      int buyerCount = tickets.size();
      long bonus = participationBonus(total, buyerCount);
      long spread = spreadBonus(buyerCount);
      long jackpot = cut + bonus + spread;
      long rollover = pool - cut;
      String name = names.getOrDefault(winner, "a mystery player");
      if (jackpot > 0L) {
         EconomyManager.addCash(winner, jackpot);
         ServerPlayer winnerP = server.getPlayerList().getPlayer(winner);
         if (winnerP != null) {
            Advancements.grant(winnerP, "lottery_winner");
         }
      }

      lastResult = name + " won $" + jackpot + " from a pool of $" + (jackpot + rollover);
      reported = false;
      // Hand this week's buy-in story to the paper before wiping the board.
      if (weekTopBuyAmount > 0L) {
         ServerNewspaperManager.logEvent(
            server,
            "Lottery ticket sales: " + total + " ticket" + (total == 1 ? "" : "s") + " sold for a $" + (jackpot + rollover) + " pot"
               + (weekTopBuyName.isEmpty() ? "." : " - biggest buy-in by " + weekTopBuyName + " ($" + weekTopBuyAmount + ").")
         );
      }
      weekTopBuyAmount = 0L;
      weekTopBuyName = "";
      tickets.clear();
      names.clear();
      // Next week's pot: 25% rolls over, and the server tops it up so the
      // jackpot never starts below the seed.
      pool = Math.max(STARTING_POT, rollover);
      String crowdLine = crowdLine(total, buyerCount, bonus, spread);
      broadcast(
         server,
         "&6&lLOTTERY! §f" + name + "§7 wins the &6$" + jackpot + "&7 jackpot from " + total + " ticket" + (total == 1 ? "" : "s") + "! &825% rolls into next week's pot."
      );
      if (!crowdLine.isEmpty()) {
         broadcast(server, crowdLine);
      }
      ServerNewspaperManager.logEvent(server, name + " won the weekly lottery jackpot of $" + jackpot + ".");
   }

   /**
    * The second line of a draw: what the jackpot was made of, and who was behind it.
    *
    * <p>Built here rather than inline, because the count it needs has to be taken BEFORE the board
    * is wiped and the inline version did not do that: it read {@code tickets.size()} straight after
    * {@code tickets.clear()}, so every draw in the game's history announced its jackpot as coming
    * "from 0 players". A named function is a place a check can stand.
    */
   static String crowdLine(long total, int buyerCount, long bonus, long spread) {
      if (bonus + spread <= 0L) {
         return "";
      }
      return "&7(" + total + " ticket" + (total == 1 ? "" : "s") + " from " + buyerCount + " player"
         + (buyerCount == 1 ? "" : "s") + " - &6$" + bonus + "&7 of that was the crowd bonus"
         + (spread > 0L ? " and &6$" + spread + "&7 the house's cut of this server's spread" : "") + ")";
   }

   /** Test seam: the draw's own second line, for a check that does not want to run a week. */
   public static String crowdLineForTest(long total, int buyerCount, long bonus, long spread) {
      return crowdLine(total, buyerCount, bonus, spread);
   }

   public static boolean buy(ServerPlayer player, int count) {
      if (count <= 0) {
         Chat.msg(player, "&cBuy at least one ticket.");
         return false;
      }
      int have = tickets.getOrDefault(player.getUUID(), 0);
      if (have + count > MAX_TICKETS_PER_PLAYER) {
         Chat.msg(player, "&cYou can hold at most " + MAX_TICKETS_PER_PLAYER + " tickets a week - you already have " + have + ".");
         return false;
      }
      long cost = count * TICKET_PRICE;
      if (!EconomyManager.takeCash(player.getUUID(), cost)) {
         Chat.msg(player, "&cYou need &a$" + cost + "&c for " + count + " ticket" + (count == 1 ? "" : "s") + " (you have $" + EconomyManager.balance(player.getUUID()) + ").");
         return false;
      }
      tickets.put(player.getUUID(), have + count);
      names.put(player.getUUID(), player.getName().getString());
      pool += cost;
      // Track this week's biggest single buy-in for the paper's lottery section.
      if (cost > weekTopBuyAmount) {
         weekTopBuyAmount = cost;
         weekTopBuyName = player.getName().getString();
      }
      save(player.level().getServer());
      Chat.msg(player, "&7Bought &f" + count + "&7 lottery ticket" + (count == 1 ? "" : "s") + " for &a$" + cost + "&7. The pot is now &f$" + pool + "&7.");
      return true;
   }

   // --- read-only info for menus / commands / newspaper ---
   public static long pool() {
      return pool;
   }

   public static int ticketsOf(ServerPlayer player) {
      return tickets.getOrDefault(player.getUUID(), 0);
   }

   public static int totalTickets() {
      int t = 0;
      for (int c : tickets.values()) {
         t += c;
      }
      return t;
   }

   public static long jackpotEstimate() {
      return (long)(pool * WINNER_SHARE) + participationBonus(totalTickets(), tickets.size()) + spreadBonus(tickets.size());
   }

   /**
    * The house's share of the server's wealth spread, scaled by how many players bought in.
    *
    * <p>Read from the live wallets - p10 against p90 - so the number a winner is promised on the
    * ticket counter is the number the draw will actually consider, rather than a second estimate
    * that disagrees with it. Deliberately built from percentiles and not from the richest wallet:
    * one dupe or one lucky gravestone is not an economy, and paying the lottery off the peak would
    * make it one.
    */
   public static long spreadBonus(int buyerCount) {
      if (buyerCount <= 0) {
         return 0L;
      }
      MarketManager.Wealth w = MarketManager.wealth();
      if (w.players() < 2) {
         return 0L;
      }
      long spread = Math.max(0L, w.p90() - w.p10());
      if (spread <= 0L) {
         return 0L;
      }
      double crowd = Math.min(1.0, (double)buyerCount / (double)SPREAD_FULL_CROWD);
      return (long)((double)spread * SPREAD_SHARE * crowd);
   }

   /** How many different players hold a ticket this week. */
   public static int buyers() {
      return tickets.size();
   }

   /** The server-funded hype bonus for a draw with this many tickets sold to
    *  this many players. More ticket-holders = a much bigger jackpot. */
   public static long participationBonus() {
      return participationBonus(totalTickets(), tickets.size());
   }

   private static long participationBonus(long ticketCount, int buyerCount) {
      if (ticketCount <= 0L) {
         return 0L;
      }
      long byTickets = ticketCount * BONUS_PER_TICKET;
      long byBuyers = (long)buyerCount * BONUS_PER_BUYER;
      // A crowd is worth far more than the same tickets bought by one whale.
      long crowd = buyerCount >= 2 ? byBuyers * byBuyers / 2_500L : 0L;
      return byTickets + byBuyers + crowd;
   }

   /** Days until the next Monday midnight draw (0 = tonight). */
   public static int daysUntilDraw() {
      int dow = LocalDate.now(ZoneId.of("America/New_York")).getDayOfWeek().getValue();
      return (8 - dow) % 7;
   }

   /** The most recent draw result, shown in the paper exactly once. */
   public static String pendingReport() {
      return reported ? null : lastResult;
   }

   public static void markReported() {
      reported = true;
   }

   private static String currentWeek() {
      LocalDate d = LocalDate.now(ZoneId.of("America/New_York"));
      return d.get(WeekFields.of(Locale.US).weekBasedYear()) + "-W" + d.get(WeekFields.of(Locale.US).weekOfWeekBasedYear());
   }

   private static void broadcast(MinecraftServer server, String line) {
      for (ServerPlayer p : server.getPlayerList().getPlayers()) {
         Chat.raw(p, "§6§l✧ LOTTERY §r" + line);
      }
   }
}