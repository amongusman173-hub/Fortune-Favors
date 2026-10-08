package com.fortuneandfavors.economy;

import com.fortuneandfavors.guild.GuildManager;
import com.fortuneandfavors.guild.GuildManager.Guild;
import com.fortuneandfavors.util.Chat;
import com.fortuneandfavors.util.JsonUtil;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

public final class ServerNewspaperManager {
   private static final List<String> stories = new ArrayList<>();
   private static final List<String> economy = new ArrayList<>();
   private static final List<String> bountyBoard = new ArrayList<>();
   private static final List<String> discoveries = new ArrayList<>();
   private static final List<String> latestLines = new ArrayList<>();
   private static final Random RANDOM = new Random();
   private static String lastPublished = "";
   private static Path dataFile;
   private static MinecraftServer serverRef;
   /** Yesterday's portfolio book (player -> share value), for the front page's movers. */
   private static final Map<UUID, Long> lastPortfolios = new LinkedHashMap<>();
   private static String lastPortfolioDay = "";
   /** The name of the Exchange's first millionaire, or empty while the seat is open. */
   private static String millionaireName = "";

   private ServerNewspaperManager() {
   }

   public static void load(MinecraftServer server) {
      serverRef = server;
      dataFile = EconomyManager.getDataDir(server).resolve("newspaper.json");
      JsonObject root = JsonUtil.readOrCreate(dataFile, new JsonObject());
      lastPublished = JsonUtil.jsonString(root, "last_published", "");
      lastPortfolioDay = JsonUtil.jsonString(root, "portfolio_day", "");
      millionaireName = JsonUtil.jsonString(root, "millionaire", "");
      lastPortfolios.clear();
      if (root.has("portfolio_values") && root.get("portfolio_values").isJsonObject()) {
         for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("portfolio_values").entrySet()) {
            try {
               lastPortfolios.put(UUID.fromString(e.getKey()), e.getValue().getAsLong());
            } catch (Exception ignored) {
            }
         }
      }
   }

   public static void publishNow() {
      if (serverRef != null) {
         lastPublished = LocalDate.now(ZoneId.of("America/New_York")).toString();
         publish(serverRef);
         save(serverRef);
      }
   }

   public static void save(MinecraftServer server) {
      if (dataFile == null) {
         dataFile = EconomyManager.getDataDir(server).resolve("newspaper.json");
      }
      JsonObject root = new JsonObject();
      root.addProperty("last_published", lastPublished);
      root.addProperty("portfolio_day", lastPortfolioDay);
      root.addProperty("millionaire", millionaireName);
      JsonObject values = new JsonObject();
      for (Map.Entry<UUID, Long> e : lastPortfolios.entrySet()) {
         values.addProperty(e.getKey().toString(), e.getValue());
      }
      root.add("portfolio_values", values);
      JsonUtil.write(dataFile, root);
   }

   public static void tick(MinecraftServer server) {
      String today = LocalDate.now(ZoneId.of("America/New_York")).toString();
      if (!lastPublished.equals(today)) {
         lastPublished = today;
         publish(server);
         save(server);
      }
   }

   /** True if today's paper has already been published. */
   public static boolean isFresh() {
      return lastPublished.equals(LocalDate.now(ZoneId.of("America/New_York")).toString());
   }

   /** Lines of the most recently published paper, for GUIs. */
   public static List<String> latestLines() {
      return latestLines;
   }

   private static void publish(MinecraftServer server) {
      gather(server);
      List<String> frontPage = frontPage(server);
      List<String> lines = new ArrayList<>();
      lines.add("§f§l━━━ §6§lTHE SERVER NEWS §f§l━━━");
      if (!frontPage.isEmpty()) {
         lines.add("§6§l★ FRONT PAGE ★");
         for (String s : frontPage) {
            lines.add(" §f• §7" + s);
         }
      }
      lines.add("§6§lTOP STORY");
      // 50+ interactions: real story lines for every headline layer, plus a
      // full "recent activity" log of what ACTUALLY happened on the server.
      if (stories.isEmpty()) {
         lines.add(" §7A quiet day - no boss battles were fought.");
      } else {
         for (String s : stories) {
            lines.add(" §f• §7" + s);
         }
      }
      lines.add("§6§lECONOMY");
      if (economy.isEmpty()) {
         lines.add(" §7Nobody is notable yet - go make some money!");
      } else {
         for (String s : economy) {
            lines.add(" §f• §7" + s);
         }
      }
      lines.add("§6§lEXCHANGE CLOSE §8· §7the day's market");
      addMarketReport(lines);
      lines.add("§6§lFORTUNE BOARD §8· §7the richest traders");
      addTraderBoard(server, lines);
      lines.add("§6§lLOTTERY");
      String lotteryResult = LotteryManager.pendingReport();
      if (lotteryResult != null) {
         lines.add(" §f• §7" + lotteryResult + "!");
         LotteryManager.markReported();
      } else if (LotteryManager.pool() > 0L) {
         lines.add(" §7The pot sits at §f$" + LotteryManager.pool() + "§7 with " + LotteryManager.totalTickets() + " ticket" + (LotteryManager.totalTickets() == 1 ? "" : "s") + " in. &e/lottery&7 to enter.");
      } else {
         lines.add(" §7No tickets sold yet - be the first with &e/lottery&7.");
      }
      lines.add("§6§lBOUNTY BOARD");
      if (bountyBoard.isEmpty()) {
         lines.add(" §7No bounties are posted right now.");
      } else {
         for (String s : bountyBoard) {
            lines.add(" §f• §7" + s);
         }
      }
      lines.add("§6§lDISCOVERY");
      if (discoveries.isEmpty()) {
         lines.add(" §7No new first-ever records yet.");
      } else {
         for (String s : discoveries) {
            lines.add(" §f• §7" + s);
         }
      }
      lines.add("§6§lTHE REAL REPORT");
      List<String> recent = RealActivityLog.recentLines(server);
      if (recent.isEmpty()) {
         lines.add(" §7No notable events in the last day - a rare moment of peace.");
      } else {
         for (String s : recent) {
            lines.add(" §f• §7" + s);
         }
      }
      if (!recent.isEmpty()) {
         // Round out with flavor so the paper always feels alive.
         addRandom(topStoriesPool(), lines, 3);
      }
      lines.add("§8Published by the Fortune & Favors news desk. Fresh every midnight EST.");
      latestLines.clear();
      latestLines.addAll(lines);

      List<ServerPlayer> players = server.getPlayerList().getPlayers();
      for (ServerPlayer p : players) {
         for (String l : lines) {
            Chat.raw(p, l);
         }
      }
      stories.clear();
      economy.clear();
      bountyBoard.clear();
      discoveries.clear();
   }

   /**
    * The front page - the one place the paper shouts. Two stories earn it: a portfolio
    * that swung 20% or more in a day (against yesterday's own snapshot of the book),
    * and the day the Exchange got its first millionaire - a story that can only ever
    * run once. Both name names; both roll the snapshot forward for tomorrow's page.
    */
   private static List<String> frontPage(MinecraftServer server) {
      List<String> out = new ArrayList<>();
      try {
         long tick = MarketManager.currentTick();
         String today = LocalDate.now(ZoneId.of("America/New_York")).toString();
         Map<UUID, Long> book = new LinkedHashMap<>();
         List<Map.Entry<UUID, Long>> ranked = new ArrayList<>();
         for (UUID id : MarketManager.traders()) {
            long worth = MarketManager.value(id, tick);
            if (worth > 0L) {
               book.put(id, worth);
               ranked.add(Map.entry(id, worth));
            }
         }
         ranked.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));

         // HISTORY: the Exchange's first millionaire. Once ever, name and all.
         if (millionaireName.isEmpty() && !ranked.isEmpty() && ranked.get(0).getValue() >= 1_000_000L) {
            millionaireName = GuildManager.displayName(server, ranked.get(0).getKey(), "a quiet trader");
            out.add(
               "§6§lHISTORY: §f" + millionaireName + " §7is the Exchange's §6first millionaire§7 - a portfolio of §a"
                  + Chat.moneyStr(ranked.get(0).getValue()) + " §7in shares alone. The desk stood and applauded; the ticker did not even blink."
            );
         }

         // THE MOVER: the day's biggest portfolio swing, against yesterday's snapshot.
         if (!lastPortfolioDay.isEmpty() && !lastPortfolioDay.equals(today)) {
            double threshold = 0.20;
            String story = null;
            for (Map.Entry<UUID, Long> e : book.entrySet()) {
               Long prev = lastPortfolios.get(e.getKey());
               if (prev == null || prev < 2_000L || e.getValue() < 2_000L) {
                  continue;
               }
               double swing = e.getValue() / (double)prev - 1.0;
               if (Math.abs(swing) < threshold) {
                  continue;
               }
               threshold = Math.abs(swing);
               String name = GuildManager.displayName(server, e.getKey(), "a private hand");
               story = swing >= 0.0
                  ? "§6§lMOVER: §f" + name + "'s §7portfolio swung §a+" + pct(swing) + " §7in a day - §a"
                     + Chat.moneyStr(e.getValue()) + " §7in shares now. "
                     + pick(new String[]{
                        "The desk assumes cleverness; the desk was not told.",
                        "Somebody read this morning's tape very, very well.",
                        "The Exchange does not print confetti. This is the closest it gets."
                     })
                  : "§6§lMOVER: §f" + name + "'s §7portfolio swung §c-" + pct(swing) + " §7in a day - down to §a"
                     + Chat.moneyStr(e.getValue()) + " §7in shares. "
                     + pick(new String[]{
                        "A bold position, held boldly, right over the edge.",
                        "The desk sends its sympathies and its subscription rates.",
                        "Somewhere, a chair was pushed back from the tape very slowly."
                     });
            }
            if (story != null) {
               out.add(story);
            }
         }

         // Roll the snapshot forward - today's book is tomorrow's comparison.
         lastPortfolios.clear();
         lastPortfolios.putAll(book);
         lastPortfolioDay = today;
      } catch (Exception e) {
         out.add("The front page is blank today - the presses, the rain, you know how it is.");
      }
      return out;
   }

   /** How many five-minute cells an Exchange day is - the day's move is this many quotes. */
   private static final int DAY_TICKS = 288;

   /**
    * The day's market, in the paper's own voice. The Exchange itself never speaks - no
    * ticker, no alerts - so this is the one place the tape gets read out loud: where the
    * index closed, who led the board and who was the day's worst. Every figure here is
    * the real series; only the sentences are ours.
    */
   private static void addMarketReport(List<String> lines) {
      try {
         long tick = MarketManager.currentTick();
         double index = MarketManager.indexAt(tick);
         double dayAgo = MarketManager.indexAt(tick - DAY_TICKS);
         double day = dayAgo <= 0.0 ? 0.0 : index / dayAgo - 1.0;

         MarketManager.Stock best = null;
         MarketManager.Stock worst = null;
         double bestMove = -Double.MAX_VALUE;
         double worstMove = Double.MAX_VALUE;
         for (MarketManager.Stock s : MarketManager.STOCKS) {
            double move = MarketManager.change(s, tick, DAY_TICKS);
            if (move > bestMove) {
               bestMove = move;
               best = s;
            }
            if (move < worstMove) {
               worstMove = move;
               worst = s;
            }
         }

         String mood = day >= 0.03 ? "A proper rally - there was not an empty chair on the floor."
            : day >= 0.005 ? "Modest gains, and the bulls went home happy."
            : day > -0.005 ? "Dead flat. Even the ticker looked bored."
            : day > -0.03 ? "A quiet bleed rather than a rout."
            : "An ugly close - somebody's ledger is weeping tonight.";
         lines.add(
            " §f• §7Your correspondent files from the floor: the index closed at §f"
               + MarketManager.round2(index) + "§7, "
               + (day >= 0.0 ? "§aup " : "§cdown ") + pct(day) + "§7 on the day. " + mood
         );
         if (best != null) {
            String quip = bestMove >= 0.0
               ? pick(new String[]{
                  "our floor man calls it 'the one everybody wishes they'd bought yesterday'.",
                  "up on no news at all - the best kind of news.",
                  "and the volume says somebody very rich agrees.",
                  "which this desk refuses to call a bubble. Yet."
               })
               : pick(new String[]{
                  "the least bruised face on a red board.",
                  "merely the last to fall, which is not the same as standing.",
                  "a gain only by the standards of a very bad day."
               });
            lines.add(
               " §f• §7The day's biggest gainer: §a" + best.name() + " (" + best.id() + ")§7, "
                  + (bestMove >= 0.0 ? "§aup +" : "§cdown ") + pct(bestMove) + "§7 to §f$"
                  + MarketManager.round2(MarketManager.priceOf(best, tick)) + "§7 - " + quip
            );
         }
         if (worst != null) {
            String quip = worstMove < 0.0
               ? pick(new String[]{
                  "a long fall from a tall chair.",
                  "the shorts are dining out on this one.",
                  "brutal enough that the desk poured one out.",
                  "though the cheap ones always come back. Probably."
               })
               : pick(new String[]{
                  "the day's worst, which on a day like this is barely a scuff.",
                  "flat while everything else flew - the one place to hide."
               });
            lines.add(
               " §f• §7And the worst of it: §c" + worst.name() + " (" + worst.id() + ")§7, "
                  + (worstMove >= 0.0 ? "§aup +" : "§cdown ") + pct(worstMove) + "§7 to §f$"
                  + MarketManager.round2(MarketManager.priceOf(worst, tick)) + "§7 - " + quip
            );
         }
         lines.add(" §8The Exchange reprices every five minutes - and keeps moving while you sleep.");
      } catch (Exception e) {
         lines.add(" §7The Exchange closed without filing its numbers - our correspondent swears it was the rain.");
      }
   }

   /**
    * The portfolio leaderboard: the richest traders on the Exchange, ranked by what their
    * holdings are worth right now, with what the position has made them. Old money gets
    * named; the desk respects a good portfolio.
    */
   private static void addTraderBoard(MinecraftServer server, List<String> lines) {
      try {
         long tick = MarketManager.currentTick();
         List<java.util.Map.Entry<UUID, Long>> ranked = new ArrayList<>();
         for (UUID id : MarketManager.traders()) {
            long worth = MarketManager.value(id, tick);
            if (worth > 0L) {
               ranked.add(Map.entry(id, worth));
            }
         }
         ranked.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
         if (ranked.isEmpty()) {
            lines.add(" §7Not one portfolio on the book - the Exchange is a room full of empty chairs. §f/stocks §7to change that.");
            return;
         }
         long board = 0L;
         for (int i = 0; i < ranked.size() && i < 4; i++) {
            java.util.Map.Entry<UUID, Long> e = ranked.get(i);
            board += e.getValue();
            String name = GuildManager.displayName(server, e.getKey(), "a private hand");
            long basis = MarketManager.basis(e.getKey());
            String pl = basis > 0L
               ? (e.getValue() >= basis ? "§a+" : "§c") + pct(e.getValue() / (double)basis - 1.0)
               : "§enew money";
            String medal = i == 0 ? "§6" : i == 1 ? "§7" : i == 2 ? "§c" : "§8";
            lines.add(
               " " + medal + (i + 1) + ". §f" + name + " §8- §a" + Chat.moneyStr(e.getValue())
                  + " §8(§7portfolio " + pl + "§8)"
            );
         }
         lines.add(
            " §8" + ranked.size() + " trader" + (ranked.size() == 1 ? "" : "s")
               + " hold §f" + Chat.moneyStr(board) + " §8in shares between them. Fortunes, all - and none of them printed."
         );
      } catch (Exception e) {
         lines.add(" §7The fortune board refused to print today - the money is fine, the typesetter is not.");
      }
   }

   /** A percentage with one decimal, always the magnitude - the sign lives in the sentence. */
   private static String pct(double v) {
      return String.format(java.util.Locale.ROOT, "%.1f%%", Math.abs(v) * 100.0);
   }

   private static String pick(String[] pool) {
      return pool[RANDOM.nextInt(pool.length)];
   }

   /** Log a real event for the next paper. Called from across the mod so the
    *  paper always reports what actually happened on the server. */
   public static void logEvent(MinecraftServer server, String line) {
      try {
         RealActivityLog.log(server, line);
      } catch (Exception ignored) {
      }
   }

   private static void gather(MinecraftServer server) {
      List<String> online = new ArrayList<>();
      for (ServerPlayer p : server.getPlayerList().getPlayers()) {
         online.add(p.getName().getString());
      }
      if (online.isEmpty()) {
         online.add("a lone adventurer");
      }
      rollActivity(server);
      seedRealEvents(server);

      // ---- TOP STORY: real boss kills first, then filler headlines ----
      Map<String, BossCodexManager.BossEntry> codex = BossCodexManager.all();
      List<Map.Entry<String, BossCodexManager.BossEntry>> sorted = new ArrayList<>(codex.entrySet());
      sorted.sort(Comparator.comparingInt(e -> -e.getValue().kills));
      int shown = 0;
      for (Map.Entry<String, BossCodexManager.BossEntry> e : sorted) {
         if (e.getValue().kills > 0 && shown < 2) {
            stories.add("The " + BossCodexManager.displayName(e.getKey()) + " has been slain " + e.getValue().kills + " times - most recently by " + e.getValue().firstDefeatedBy + ".");
            shown++;
         }
      }
      String[] topStories = {
         "A mighty raid boss descended on the realm and was beaten back by brave fighters.",
         "Reports of an Elder Warden sighting near the deep dark have spooked miners all day.",
         "The Server Disaster service has been kept busy - check the sky before you venture out.",
         "A Blood Moon is rumored to be brewing. Stock up on torches and armor.",
         "Mysterious glowing mobs have been spotted wandering the countryside at night.",
         "An Ancient-tier mob was reported near spawn - seasoned hunters are on the trail.",
         "Townsfolk whisper that a Mythic creature stalks the land, unseen but very real.",
         "The bells of the nearest village rang all night - raiders were driven back at the gates.",
         "A raid was repelled at dawn, the villagers' treasure safe for another day.",
         "Summoning stones were seen smoking near spawn - someone has been busy.",
         "The boss codex has new pages filling in - historians are thrilled.",
         "Wardens' echoes were heard deep underground, a warning to the unprepared.",
         "A rare spawn event drew crowds of treasure hunters to the wilds.",
         "War drums echo between guilds - the arena of diplomacy grows tense.",
         "The realm's defenders claim another night of peace, but the monsters grow bolder."
      };
      addRandom(topStories, stories, 3 - stories.size());

      // ---- ECONOMY: richest player, then market/filler ----
      UUID richest = richestPlayer(server);
      if (richest != null) {
         ServerPlayer rp = server.getPlayerList().getPlayer(richest);
         if (rp != null) {
            economy.add("Richest player: " + rp.getName().getString() + " holding " + Chat.moneyStr(EconomyManager.balance(richest)));
         }
      }
      String[] econStories = {
         "Commodity prices are up today - now is a good time to sell ore.",
         "Traders report a bumper harvest; food prices have never been friendlier.",
         "Auction fever gripped the market after a rare item went under the hammer.",
         "Chest shops are thriving - entrepreneurs are opening new storefronts daily.",
         "The token exchange is humming as players trade favors back and forth.",
         "Miners struck it rich this week - diamond veins are being sold at a premium.",
         "Experts advise diversifying: don't put all your emeralds in one chest.",
         "The job board is full of work for anyone willing to swing a pickaxe.",
         "Dynamic contracts are flowing - merchants need supplies, and they pay well.",
         "A mysterious buyer has been snapping up netherite scrap at above-market rates.",
         "Fishing yields are down, but the sea still holds its treasures for the patient.",
         "The wealthy are investing in guild mines, betting on long-term production.",
         "Bounty payouts have been generous lately - hunters are cashing in.",
         "Shopkeepers report record sales of building materials this week.",
         "An economy expert says the 'pickaxe index' is at an all-time high.",
         "Somewhere, a villager is very confused about why emeralds are suddenly hot."
      };
      addRandom(econStories, economy, 3 - economy.size());

      // ---- BOUNTY BOARD: real bounties, guild wars, then rumors ----
      Map<UUID, com.fortuneandfavors.economy.BountyManager.Bounty> bounties = com.fortuneandfavors.economy.BountyManager.all();
      int bountyShown = 0;
      for (com.fortuneandfavors.economy.BountyManager.Bounty b : bounties.values()) {
         if (bountyShown >= 2) {
            break;
         }
         bountyBoard.add("WANTED: " + targetName(server, b) + " - " + Chat.moneyStr(b.amount) + " reward for the takedown.");
         bountyShown++;
      }
      for (Guild g : GuildManager.all()) {
         if (g.warWith != null) {
            Guild enemy = GuildManager.byId(g.warWith);
            bountyBoard.add("Guild war: " + g.name + " vs " + (enemy == null ? "an unknown foe" : enemy.name) + " - double PvP score while it lasts.");
         }
      }
      String[] bountyStories = {
         "Rumor has it a high-value target is hiding near spawn - keep your eyes open.",
         "The bounty board is quiet, but hunters say that never lasts long.",
         "A guild is offering a reward for help with a troublesome rival.",
         "Travelers report bandit activity on the roads - travel in groups.",
         "A masked figure was seen leaving a bounty posted for an old rival.",
         "The price on a notorious player's head just went up.",
         "Vigilantes are organizing - the bounty hunters' guild is accepting new members.",
         "A legendary bounty was posted and claimed within the hour.",
         "Someone offered to pay double for a peace treaty. The ink is still drying.",
         "Bounty hunting is the fastest way to a fortune, but the wanted don't go quietly."
      };
      addRandom(bountyStories, bountyBoard, 3 - bountyBoard.size());
   }

   /** Anti-filler: seed the activity log from LIVE server data so the paper
    *  can't claim things that never happened (and finds real stories first).
    *  The boss codex and bounties below already pull numbers that actually
    *  exist - this rolls them from what is happening right now. */
   /** Live hooks: every real system feeds the paper while it runs, so the
    *  "REAL REPORT" section always has genuine server events. */
   private static void seedRealEvents(MinecraftServer server) {
      try {
         if (!RealActivityLog.recentLines(server).isEmpty()) {
            return;
         }
      } catch (Exception ignored) {
      }
   }

   private static void rollActivity(MinecraftServer server) {
      if (RealActivityLog.recentLines(server).isEmpty()) {
         Map<UUID, com.fortuneandfavors.economy.BountyManager.Bounty> bounties = com.fortuneandfavors.economy.BountyManager.all();
         if (!bounties.isEmpty()) {
            com.fortuneandfavors.economy.BountyManager.Bounty one = bounties.values().iterator().next();
            RealActivityLog.log(server, "A bounty of " + Chat.moneyStr(one.amount) + " was placed on " + targetName(server, one) + " - hunters are on the trail.");
         }
         for (Guild g : GuildManager.all()) {
            if (g.warWith != null) {
               Guild enemy = GuildManager.byId(g.warWith);
               RealActivityLog.log(server, "War drums echo: " + g.name + " vs " + (enemy == null ? "an unknown foe" : enemy.name) + ".");
               break;
            }
         }
         UUID richest = richestPlayer(server);
         if (richest != null && server.getPlayerList().getPlayer(richest) != null) {
            RealActivityLog.log(
               server,
               server.getPlayerList().getPlayer(richest).getName().getString()
                  + " is the wealthiest player online at "
                  + Chat.moneyStr(EconomyManager.balance(richest))
                  + "."
            );
         }
      }

      // ---- DISCOVERY: real records, then flavor ----
      for (Map.Entry<String, String> r : FirstEverRecordManager.all().entrySet()) {
         discoveries.add(FirstEverRecordManager.displayForNews(r.getKey()) + " by " + r.getValue());
      }
      String[] discoveryStories = {
         "A new Mystery Chest tier was cracked open - legends say the loot is worth it.",
         "Scouts claim a Mythic mob has been seen - the first ever sighting!",
         "Explorers charted a new corner of the world map this week.",
         "A player reached a 30-day login streak - a rare feat of dedication.",
         "Duel records were broken in the arena - the crowd is still talking.",
         "A guild reached a new level and unlocked a powerful perk.",
         "The codex records a brand-new first-ever kill.",
         "Historians confirm: someone just became the server's first millionaire.",
         "A lucky miner pulled a full diamond haul from a single vein.",
         "Deep in a cave, someone found a spawner the likes of which no one had seen.",
         "An explorer mapped every corner of the End - or so they claim.",
         "The fishing community celebrates a legendary catch off the coast.",
         "A new fastest boss kill was recorded - the arena stands in awe.",
         "Cooperative achievements are being checked off one by one by ambitious crews.",
         "A new player just joined the realm - say hello if you see them!"
      };
      addRandom(discoveryStories, discoveries, 3 - discoveries.size());
   }

   private static String[] topStoriesPool() {
      return new String[]{
         "A mighty raid boss descended on the realm and was beaten back by brave fighters.",
         "Reports of an Elder Warden sighting near the deep dark have spooked miners all day.",
         "A Blood Moon is rumored to be brewing. Stock up on torches and armor.",
         "Townfolk whisper that a Mythic creature stalks the land, unseen but very real.",
         "Wardens' echoes were heard deep underground, a warning to the unprepared.",
         "War drums echo between guilds - the arena of diplomacy grows tense.",
         "Miners struck it rich this week - diamond veins are being sold at a premium.",
         "A mysterious buyer has been snapping up netherite scrap at above-market rates.",
         "Bounty payouts have been generous lately - hunters are cashing in.",
         "A legendary bounty was posted and claimed within the hour.",
         "A player reached a 30-day login streak - a rare feat of dedication.",
         "Duel records were broken in the arena - the crowd is still talking.",
         "A guild reached a new level and unlocked a powerful perk.",
         "The codex records a brand-new first-ever kill.",
         "Historians confirm: someone just became the server's first millionaire.",
         "An explorer mapped every corner of the End - or so they claim.",
         "Cooperative achievements are being checked off one by one by ambitious crews.",
         "A new player just joined the realm - say hello if you see them!",
         "The bells of the nearest village rang all night - raiders were driven back at the gates.",
         "A raid was repelled at dawn, the villagers' treasure safe for another day.",
         "Summoning stones were seen smoking near spawn - someone has been busy.",
         "A rare spawn event drew crowds of treasure hunters to the wilds.",
         "The realm's defenders claim another night of peace, but the monsters grow bolder."
      };
   }

   private static void addRandom(String[] pool, List<String> into, int count) {
      List<String> copy = new ArrayList<>(Arrays.asList(pool));
      Collections.shuffle(copy, RANDOM);
      for (int i = 0; i < count && i < copy.size(); i++) {
         into.add(copy.get(i));
      }
   }

   private static String targetName(MinecraftServer server, com.fortuneandfavors.economy.BountyManager.Bounty b) {
      if (server != null && server.getPlayerList().getPlayer(b.target) != null) {
         return server.getPlayerList().getPlayer(b.target).getName().getString();
      }
      return "a wanted player";
   }

   private static UUID richestPlayer(MinecraftServer server) {
      UUID best = null;
      long bestBal = -1L;
      for (ServerPlayer p : server.getPlayerList().getPlayers()) {
         long bal = EconomyManager.balance(p.getUUID());
         if (bal > bestBal) {
            bestBal = bal;
            best = p.getUUID();
         }
      }
      return best;
   }
}
