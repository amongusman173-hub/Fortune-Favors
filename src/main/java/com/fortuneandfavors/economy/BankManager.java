package com.fortuneandfavors.economy;

import com.fortuneandfavors.util.Chat;
import com.fortuneandfavors.util.JsonUtil;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.Map.Entry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** The player bank: a protected money vault and an XP vault, both stored
 *  server-side so death and raids can't touch them.
 *
 *  One bank LEVEL (1-5) governs everything, so upgrades are simple to
 *  understand: each level raises the money capacity, the XP capacity, AND the
 *  daily interest (both the rate and its cap). Level 1 is deliberately small.
 *  Interest pays every ~20 real hours (even offline) as RATE% of what's
 *  banked, up to the level's daily cap - so even a maxed vault grows slowly
 *  and predictably instead of snowballing. */
public final class BankManager {
   // --- per-level tuning (index 0 = level 1 ... index 4 = level 5) ---
   public static final long[] MONEY_CAPS = new long[]{50_000L, 250_000L, 1_000_000L, 5_000_000L, 25_000_000L};
   public static final int[] XP_CAPS = new int[]{350, 1_000, 3_000, 9_000, 27_000}; // ≈ level 16 / 27 / 46 / 80 / 137
   public static final double[] INTEREST_RATES = new double[]{0.005, 0.0075, 0.010, 0.015, 0.020}; // 0.5% → 2%/day
   public static final long[] INTEREST_CAPS = new long[]{500L, 2_500L, 10_000L, 50_000L, 200_000L}; // max cash/day per level
   public static final long[] UPGRADE_COSTS = new long[]{10_000L, 50_000L, 250_000L, 1_000_000L}; // Lv1→2, 2→3, 3→4, 4→5

   /**
    * One interest day, in real seconds.
    *
    * <p>The vault pays on this clock rather than on the server's uptime, which is the difference
    * between a bank that rewards banking and one that rewards leaving the server running. It is a
    * named constant because three places now depend on the same figure: the payout, the countdown
    * on the tile, and the catch-up.
    */
   public static final long INTEREST_PERIOD_SECONDS = 20L * 3600L;

   /**
    * The most days a single absence is paid for.
    *
    * <p>A vault that was away for a year is paid for a month. Interest is a reason to keep cash
    * banked rather than carried, not a substitute for playing, and an uncapped catch-up would be
    * the second kind: park everything in a level-5 vault, leave, come back to a fortune.
    */
   public static final int MAX_CATCHUP_DAYS = 30;

   private static final Map<UUID, Account> accounts = new HashMap<>();

   /**
    * How many movements a player's statement keeps.
    *
    * <p>Bounded on purpose: a statement is a window onto what happened, not a ledger, and a log
    * that grows forever is a file that grows forever - the thing a player wants to see is the last
    * handful of movements, and the last handful is all this keeps.
    */
   public static final int STATEMENT_LIMIT = 12;

   /** One thing that happened to an account, newest last in storage and first in {@link #statement}. */
   public record Movement(long at, String kind, long amount, String note) {
   }

   /**
    * The statement is kept beside the accounts rather than inside them.
    *
    * <p>{@link Account} is a value that every deposit, withdrawal and upgrade rebuilds from its own
    * fields, so a log carried inside it would have to be threaded through every one of those call
    * sites - twelve chances for one of them to drop the player's own history on the floor. Beside
    * it, the only way to lose a movement is to not record it.
    */
   private static final Map<UUID, java.util.Deque<Movement>> statements = new HashMap<>();
   private static Path dataFile;
   private static boolean dirty = false;

   private BankManager() {
   }

   public static void load(MinecraftServer server) {
      accounts.clear();
      statements.clear();
      dataFile = EconomyManager.getDataDir(server).resolve("bank.json");
      JsonObject root = JsonUtil.readOrCreate(dataFile, new JsonObject());
      if (root.has("players") && root.get("players").isJsonObject()) {
         for (Entry<String, JsonElement> e : root.getAsJsonObject("players").entrySet()) {
            try {
               UUID id = UUID.fromString(e.getKey());
               JsonObject o = e.getValue().getAsJsonObject();
               // New format: one "tier". Old format had money/interest/xp tiers
               // separately - merge them (take the best) so nobody loses an
               // upgrade they already paid for, then clamp to the new range.
               int tier = JsonUtil.jsonInt(o, "tier", -1);
               if (tier < 0) {
                  int moneyTier = JsonUtil.jsonInt(o, "money_tier", 0);
                  int interestTier = JsonUtil.jsonInt(o, "interest_tier", 0);
                  int xpTier = JsonUtil.jsonInt(o, "xp_tier", 0);
                  tier = Math.max(moneyTier, Math.max(interestTier, xpTier));
               }
               tier = Math.max(0, Math.min(MONEY_CAPS.length - 1, tier));
               long cash = Math.max(0L, JsonUtil.jsonLong(o, "cash", 0L));
               int xp = Math.max(0, JsonUtil.jsonInt(o, "xp", 0));
               long last = JsonUtil.jsonLong(o, "last_interest", 0L);
               // Cash/XP are NOT clamped down to the new caps - that would
               // destroy money a player already banked. Over-cap balances are
               // kept intact; deposits just stay blocked until they're under
               // the cap again (withdrawals always work).
               accounts.put(id, new Account(cash, tier, xp, last));
               if (o.has("log") && o.get("log").isJsonArray()) {
                  java.util.Deque<Movement> log = new java.util.ArrayDeque<>();
                  for (JsonElement el : o.getAsJsonArray("log")) {
                     JsonObject m = el.getAsJsonObject();
                     log.addLast(
                        new Movement(
                           JsonUtil.jsonLong(m, "at", 0L),
                           JsonUtil.jsonString(m, "kind", ""),
                           JsonUtil.jsonLong(m, "amount", 0L),
                           JsonUtil.jsonString(m, "note", "")
                        )
                     );
                  }
                  if (!log.isEmpty()) {
                     statements.put(id, log);
                  }
               }
            } catch (Exception ignored) {
            }
         }
      }
   }

   public static void save(MinecraftServer server) {
      if (dataFile == null) {
         dataFile = EconomyManager.getDataDir(server).resolve("bank.json");
      }
      JsonObject root = new JsonObject();
      JsonObject players = new JsonObject();
      for (Entry<UUID, Account> e : accounts.entrySet()) {
         Account a = e.getValue();
         JsonObject o = new JsonObject();
         o.addProperty("cash", a.cash());
         o.addProperty("tier", a.tier());
         o.addProperty("xp", a.xp());
         o.addProperty("last_interest", a.lastInterest());
         java.util.Deque<Movement> log = statements.get(e.getKey());
         if (log != null && !log.isEmpty()) {
            com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
            for (Movement m : log) {
               JsonObject mo = new JsonObject();
               mo.addProperty("at", m.at());
               mo.addProperty("kind", m.kind());
               mo.addProperty("amount", m.amount());
               mo.addProperty("note", m.note());
               arr.add(mo);
            }
            o.add("log", arr);
         }
         players.add(e.getKey().toString(), o);
      }
      root.add("players", players);
      JsonUtil.write(dataFile, root);
      dirty = false;
   }

   /**
    * One catch-up: the days a vault is owed, and where its stamp lands afterwards.
    *
    * <p>This is the whole of "interest has to keep advancing while nobody is online". The old
    * payout paid <em>one</em> day whenever the account was overdue, so a world that sat for a week
    * paid a single day's interest and quietly swallowed the other six - and a stamp that never
    * moved forward for an empty vault meant the day was lost rather than deferred. The arithmetic
    * is a pure function so it can be driven in both directions by a test instead of by waiting.
    *
    * @param lastInterest the epoch second of the account's last payout (0 = never paid)
    * @param now          the epoch second to settle up to
    */
   public record CatchUp(int days, long stamp) {
   }

   public static CatchUp catchUp(long lastInterest, long now) {
      if (lastInterest <= 0L) {
         // Never paid: the clock starts now. Treating "never" as "a very long time ago" is what
         // paid a full day the instant somebody made their first deposit.
         return new CatchUp(0, now);
      }
      long owed = (now - lastInterest) / INTEREST_PERIOD_SECONDS;
      if (owed <= 0L) {
         return new CatchUp(0, lastInterest);
      }
      int days = (int)Math.min((long)MAX_CATCHUP_DAYS, owed);
      // Within the cap the stamp keeps its phase, so a vault that has been paying at six in the
      // evening keeps paying at six in the evening. Past the cap the excess is forgiven and the
      // stamp is set to now - otherwise the next tick would find the account ninety days overdue
      // again and pay the cap a second time.
      long stamp = owed > (long)MAX_CATCHUP_DAYS ? now : lastInterest + (long)days * INTEREST_PERIOD_SECONDS;
      return new CatchUp(days, stamp);
   }

   /** What {@code days} of payouts add, compounded exactly as the tick does. */
   public record Growth(long paid, long cash) {
   }

   public static Growth compound(long cash, int tier, int days) {
      long paid = 0L;
      long running = cash;
      for (int day = 0; day < days; day++) {
         long payout = Math.min(INTEREST_CAPS[tier], Math.max(0L, (long)Math.floor((double)running * INTEREST_RATES[tier])));
         if (payout <= 0L) {
            break;
         }
         paid += payout;
         running = Math.min(MONEY_CAPS[tier], running + payout);
      }
      return new Growth(paid, running);
   }

   /** Interest tick: pays every day an account has waited for, including while the server was
    *  off. Called once a second from ModEvents. */
   public static void tick(MinecraftServer server) {
      long now = Instant.now().getEpochSecond();
      boolean changed = false;
      UUID topPayoutId = null;
      long topPayout = 0L;
      int topDays = 0;
      for (Entry<UUID, Account> e : accounts.entrySet()) {
         Account a = e.getValue();
         CatchUp plan = catchUp(a.lastInterest(), now);
         if (plan.days() <= 0) {
            if (plan.stamp() != a.lastInterest()) {
               accounts.put(e.getKey(), new Account(a.cash(), a.tier(), a.xp(), plan.stamp()));
               changed = true;
            }
            continue;
         }
         Growth growth = compound(a.cash(), a.tier(), plan.days());
         accounts.put(e.getKey(), new Account(growth.cash(), a.tier(), a.xp(), plan.stamp()));
         changed = true;
         if (growth.paid() > 0L) {
            if (growth.paid() > topPayout) {
               topPayout = growth.paid();
               topPayoutId = e.getKey();
               topDays = plan.days();
            }
            record(e.getKey(), "interest", growth.paid(), plan.days() > 1 ? plan.days() + " days caught up" : "daily payout");
            ServerPlayer online = server.getPlayerList().getPlayer(e.getKey());
            if (online != null) {
               Chat.raw(
                  online,
                  "§6§lBANK §r§7Interest paid: §a+" + Chat.moneyStr(growth.paid())
                     + (plan.days() > 1 ? "§7 (" + plan.days() + " days)" : "")
                     + "§7. Vault: §f" + Chat.moneyStr(growth.cash()) + "§7."
               );
            }
         }
      }
      if (changed) {
         markDirty();
         if (topPayoutId != null && topPayout > 0L) {
            String name = server.getPlayerList().getPlayer(topPayoutId) != null
               ? server.getPlayerList().getPlayer(topPayoutId).getName().getString()
               : "an account holder";
            ServerNewspaperManager.logEvent(
               server,
               "Bank interest day: biggest payout was " + Chat.moneyStr(topPayout) + " to " + name
                  + " (" + pct(INTEREST_RATES[accounts.get(topPayoutId).tier()]) + " daily rate"
                  + (topDays > 1 ? ", " + topDays + " days caught up" : "") + ")."
            );
         }
      }
   }

   // --- the statement ---

   /**
    * Records one movement on an account. Newest first, and the oldest falls off the end.
    *
    * <p>The bank is the one part of the economy that moves money while nobody is watching, which
    * is exactly the situation a statement is for: "it said interest was paid, where did it go" is
    * a question a player asks about a number they never saw change.
    *
    * @param kind  a short word for the line - deposit, withdraw, interest, upgrade
    * @param note  the human half of the line, e.g. "XP vault" or "level 3"
    */
   public static void record(UUID id, String kind, long amount, String note) {
      if (id == null) {
         return;
      }
      java.util.Deque<Movement> log = statements.computeIfAbsent(id, k -> new java.util.ArrayDeque<>());
      log.addFirst(new Movement(Instant.now().getEpochSecond(), kind, amount, note));
      while (log.size() > STATEMENT_LIMIT) {
         log.removeLast();
      }
      markDirty();
   }

   /** This account's movements, newest first. Empty for an account that has never moved. */
   public static java.util.List<Movement> statement(UUID id) {
      java.util.Deque<Movement> log = statements.get(id);
      return log == null ? java.util.List.of() : java.util.List.copyOf(log);
   }

   /** Test hook: forget a statement, so a check can watch one fill up from empty. */
   public static void clearStatement(UUID id) {
      statements.remove(id);
   }

   /**
    * What a vault would earn over {@code days} days if nothing else moved.
    *
    * <p>Compound and capped, because that is what the tick actually does: each payout is added to
    * the cash, so tomorrow's interest is charged on today's interest - except where the level's own
    * daily cap or the vault's own ceiling steps in first, both of which are applied here. A
    * projection that ignored either would promise a number the bank will never pay.
    */
   public static long projectedInterest(Account a, int days) {
      if (a == null || a.cash() <= 0L || days <= 0) {
         return 0L;
      }
      return compound(a.cash(), a.tier(), days).paid();
   }

   /** Interest is RATE% of the banked cash per day, capped at the bank
    *  level's daily cap. Upgrades raise both, so interest always gets better
    *  with every level - but the cap keeps even maxed vaults slow-growing. */
   public static long interestFor(Account a) {
      if (a.cash() <= 0L) {
         return 0L;
      }
      long amount = (long)Math.floor((double)a.cash() * INTEREST_RATES[a.tier()]);
      return Math.min(INTEREST_CAPS[a.tier()], Math.max(0L, amount));
   }

   // --- accessors ---
   public static Account account(UUID id) {
      return accounts.get(id);
   }

   public static Account accountOf(ServerPlayer player) {
      return accounts.computeIfAbsent(player.getUUID(), k -> new Account(0L, 0, 0, 0L));
   }

   public static long moneyCap(Account a) {
      return MONEY_CAPS[a.tier()];
   }

   public static int xpCap(Account a) {
      return XP_CAPS[a.tier()];
   }

   public static long interestCap(Account a) {
      return INTEREST_CAPS[a.tier()];
   }

   /** What the player's shares are worth right now - the bank shows this beside the vaults, so a
    *  player can see their whole worth in the one window they already trust. */
   public static long portfolioValue(ServerPlayer player) {
      return MarketManager.value(player.getUUID());
   }

   /** The bank level's daily interest rate, e.g. 0.005 = 0.5%/day. */
   public static double interestRate(Account a) {
      return INTEREST_RATES[a.tier()];
   }

   /** 1-based bank level for display. */
   public static int level(Account a) {
      return a.tier() + 1;
   }

   public static int maxLevel() {
      return MONEY_CAPS.length;
   }

   // --- money bank ---
   /** Deposits from the player's wallet. Returns the amount actually deposited. */
   public static long depositMoney(ServerPlayer player, long amount) {
      if (amount <= 0L) {
         return 0L;
      }
      Account a = accountOf(player);
      long cap = MONEY_CAPS[a.tier()];
      long space = cap - a.cash();
      long put = Math.min(space, amount);
      if (put <= 0L) {
         Chat.msg(player, "&cYour bank vault is full (" + Chat.moneyStr(cap) + "). Upgrade the bank to level " + (a.tier() + 2) + "!");
         return 0L;
      }
      if (!EconomyManager.takeCash(player.getUUID(), put)) {
         Chat.msg(player, "&cYou don't have that much cash on hand.");
         return 0L;
      }
      accounts.put(player.getUUID(), new Account(a.cash() + put, a.tier(), a.xp(), a.lastInterest()));
      record(player.getUUID(), "deposit", put, "cash vault");
      markDirty();
      return put;
   }

   public static long withdrawMoney(ServerPlayer player, long amount) {
      if (amount <= 0L) {
         return 0L;
      }
      Account a = accountOf(player);
      long take = Math.min(a.cash(), amount);
      if (take <= 0L) {
         return 0L;
      }
      EconomyManager.addCash(player.getUUID(), take);
      accounts.put(player.getUUID(), new Account(a.cash() - take, a.tier(), a.xp(), a.lastInterest()));
      record(player.getUUID(), "withdraw", take, "cash vault");
      markDirty();
      return take;
   }

   // --- xp bank ---
   /** Points worth of XP: depositing `levels` levels banks exactly what the
    *  next `levels` level-ups would have cost, so bar-to-vault is lossless. */
   public static int pointsForLevelsUp(ServerPlayer p, int levels) {
      int need = 0;
      for (int i = 0; i < levels; i++) {
         need += xpToNext(p.experienceLevel + i);
      }
      return need;
   }

   /** Deposits the next `levels` levels of XP (capped by vault space and what
    *  the player actually has). Returns points banked. */
   public static int depositXp(ServerPlayer player, int levels) {
      if (levels <= 0) {
         return 0;
      }
      Account a = accountOf(player);
      int cap = XP_CAPS[a.tier()];
      int space = cap - a.xp();
      if (space <= 0) {
         Chat.msg(player, "&cYour XP vault is full (" + cap + " XP). Upgrade the bank to level " + (a.tier() + 2) + "!");
         return 0;
      }
      int want = pointsForLevelsUp(player, levels);
      int have = totalXpPoints(player);
      int put = Math.min(space, Math.min(want, have));
      if (put <= 0) {
         Chat.msg(player, "&cYou have no XP to deposit.");
         return 0;
      }
      if (!player.getAbilities().instabuild) {
         player.totalExperience = Math.max(0, player.totalExperience - put);
         player.experienceLevel = levelForPoints(player.totalExperience);
         player.experienceProgress = progressForPoints(player.totalExperience);
      }
      accounts.put(player.getUUID(), new Account(a.cash(), a.tier(), a.xp() + put, a.lastInterest()));
      record(player.getUUID(), "deposit", put, "XP vault");
      markDirty();
      return put;
   }

   /** Withdraws enough points to gain `levels` levels (or the whole vault). */
   public static int withdrawXpLevels(ServerPlayer player, int levels) {
      if (levels <= 0) {
         return 0;
      }
      Account a = accountOf(player);
      if (a.xp() <= 0) {
         return 0;
      }
      int want = pointsForLevelsUp(player, levels);
      return withdrawXp(player, Math.min(a.xp(), want));
   }

   public static int withdrawXp(ServerPlayer player, int points) {
      if (points <= 0) {
         return 0;
      }
      Account a = accountOf(player);
      int take = Math.min(a.xp(), points);
      if (take <= 0) {
         return 0;
      }
      player.totalExperience = player.totalExperience + take;
      player.experienceLevel = levelForPoints(player.totalExperience);
      player.experienceProgress = progressForPoints(player.totalExperience);
      accounts.put(player.getUUID(), new Account(a.cash(), a.tier(), a.xp() - take, a.lastInterest()));
      record(player.getUUID(), "withdraw", take, "XP vault");
      markDirty();
      return take;
   }

   // --- the single upgrade path ---
   /** Upgrades the whole bank one level (money cap + XP cap + interest). */
   public static boolean upgrade(ServerPlayer player) {
      Account a = accountOf(player);
      if (a.tier() >= UPGRADE_COSTS.length) {
         Chat.msg(player, "&cYour bank is already max level (" + maxLevel() + ")!");
         return false;
      }
      long cost = UPGRADE_COSTS[a.tier()];
      if (!EconomyManager.takeCash(player.getUUID(), cost)) {
         Chat.msg(player, "&cUpgrading to bank level " + (a.tier() + 2) + " costs &a" + Chat.moneyStr(cost) + "&c - you can't afford it.");
         return false;
      }
      accounts.put(player.getUUID(), new Account(a.cash(), a.tier() + 1, a.xp(), a.lastInterest()));
      record(player.getUUID(), "upgrade", cost, "to level " + (a.tier() + 2));
      markDirty();
      Chat.raw(player, "&6&lBANK &r&aUpgraded to level " + (a.tier() + 2) + "&a! Vault &f" + Chat.moneyStr(MONEY_CAPS[a.tier() + 1])
         + "&a · XP &f" + XP_CAPS[a.tier() + 1] + "&a · interest &f" + pct(INTEREST_RATES[a.tier() + 1]) + "/day&a (max &f"
         + Chat.moneyStr(INTEREST_CAPS[a.tier() + 1]) + "&a).");
      Advancements.grant(player, "bank_vault");
      if (a.tier() + 1 >= maxLevel() - 1) {
         Advancements.grant(player, "bank_max");
      }
      ServerNewspaperManager.logEvent(
         player.level().getServer(),
         player.getName().getString() + " upgraded their bank to level " + (a.tier() + 2) + "."
      );
      return true;
   }

   // --- xp math (mirrors vanilla's level curve exactly) ---
   /** Vanilla total XP -> level: 0-16 needs 2L+7 per level, 17-31 needs
    *  5L-38, 32+ needs 9L-158. */
   public static int levelForPoints(int points) {
      int level = 0;
      while (points > 0 && level < 24791) { // sanity bound well past level 500
         int need = xpToNext(level);
         if (points < need) {
            break;
         }
         points -= need;
         level++;
      }
      return level;
   }

   public static float progressForPoints(int points) {
      int level = levelForPoints(points);
      int consumed = 0;
      for (int l = 0; l < level; l++) {
         consumed += xpToNext(l);
      }
      int into = Math.max(0, points - consumed);
      return (float)into / (float)Math.max(1, xpToNext(level));
   }

   /** XP needed to go from `level` to `level + 1` (vanilla curve). */
   public static int xpToNext(int level) {
      if (level >= 32) {
         return 9 * level - 158;
      }
      if (level >= 17) {
         return 5 * level - 38;
      }
      return 2 * level + 7;
   }

   public static int totalXpPoints(ServerPlayer p) {
      return Math.max(0, p.totalExperience);
   }

   /** 0.005 -> "0.5%" - used in messages. */
   private static String pct(double rate) {
      double p = rate * 100.0;
      return (p == Math.floor(p) ? String.valueOf((long)p) : String.valueOf(p)) + "%";
   }

   public static boolean isDirty() {
      return dirty;
   }

   public static void markDirty() {
      dirty = true;
   }

   public record Account(long cash, int tier, int xp, long lastInterest) {
   }
}
