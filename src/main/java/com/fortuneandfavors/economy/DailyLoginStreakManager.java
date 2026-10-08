package com.fortuneandfavors.economy;

import com.fortuneandfavors.util.Chat;
import com.fortuneandfavors.util.InventoryHelper;
import com.fortuneandfavors.util.JsonUtil;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Map.Entry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

public final class DailyLoginStreakManager {
   private static final Map<UUID, PlayerStreak> streaks = new HashMap<>();
   private static Path dataFile;
   public static final int MAX_FREEZES = 3;
   private static final int FREEZE_EVERY = 5;
   /**
    * What a streak freeze costs, in gems.
    *
    * <p>Gems rather than favor tokens: the two currencies are earned in completely
    * different places (tokens from the token shop's paper, gems from the gem shop and
    * boss loot), and a streak freeze is a convenience for a player who is about to miss a
    * day rather than something bought with the currency that mostly exists to be traded.
    */
   public static final int FREEZE_COST_GEMS = 5;

   private DailyLoginStreakManager() {
   }

   public static String today() {
      return LocalDate.now(ZoneId.of("America/New_York")).toString();
   }

   public static String yesterday() {
      return LocalDate.now(ZoneId.of("America/New_York")).minusDays(1).toString();
   }

   public static void load(MinecraftServer server) {
      streaks.clear();
      dataFile = EconomyManager.getDataDir(server).resolve("login_streaks.json");
      JsonObject root = JsonUtil.readOrCreate(dataFile, new JsonObject());
      if (root.has("players") && root.get("players").isJsonObject()) {
         for (Entry<String, JsonElement> e : root.getAsJsonObject("players").entrySet()) {
            try {
               UUID uuid = UUID.fromString(e.getKey());
               JsonObject obj = e.getValue().getAsJsonObject();
               int day = JsonUtil.jsonInt(obj, "day", 0);
               String last = JsonUtil.jsonString(obj, "last", "");
               int freezes = JsonUtil.jsonInt(obj, "freezes", 0);
               String claimed = JsonUtil.jsonString(obj, "claimed", "");
               streaks.put(uuid, new PlayerStreak(day, last, freezes, claimed));
            } catch (Exception ignored) {
            }
         }
      }
   }

   public static void save(MinecraftServer server) {
      if (dataFile == null) {
         dataFile = EconomyManager.getDataDir(server).resolve("login_streaks.json");
      }
      JsonObject root = new JsonObject();
      JsonObject players = new JsonObject();
      for (Entry<UUID, PlayerStreak> e : streaks.entrySet()) {
         JsonObject obj = new JsonObject();
         obj.addProperty("day", e.getValue().day);
         obj.addProperty("last", e.getValue().lastSeen);
         obj.addProperty("freezes", e.getValue().freezes);
         obj.addProperty("claimed", e.getValue().claimedDay);
         players.add(e.getKey().toString(), obj);
      }
      root.add("players", players);
      JsonUtil.write(dataFile, root);
   }

   public static void onJoin(ServerPlayer player) {
      String now = today();
      PlayerStreak s = streaks.computeIfAbsent(player.getUUID(), u -> new PlayerStreak(0, "", 0, ""));
      int nextDay;
      boolean missed = false;
      // What was thrown away, so the reset is explained rather than simply observed. A streak that
      // silently goes back to one is a bug as far as the player is concerned - and the two numbers
      // a player needs are how much they lost and how to stop it happening again.
      int lost = 0;
      if (s.lastSeen.equals(now)) {
         nextDay = s.day; // already logged in today
      } else if (s.lastSeen.equals(yesterday())) {
         nextDay = s.day + 1;
      } else if (!s.lastSeen.isEmpty()) {
         // Missed at least one full day
         if (s.freezes > 0 && s.day > 0) {
            s.freezes--;
            nextDay = s.day + 1;
            missed = true;
         } else {
            lost = s.day;
            nextDay = 1;
         }
      } else {
         nextDay = 1;
      }
      s.lastSeen = now;
      s.day = nextDay;
      if (nextDay > 0 && nextDay % FREEZE_EVERY == 0 && s.freezes < MAX_FREEZES) {
         s.freezes++;
      }
      if (missed) {
         Chat.raw(player, "§eA streak freeze saved your §f" + (nextDay - 1) + "-day§e login streak! You're on day §f" + nextDay + "§e now.");
         Chat.raw(player, "§7Freezes left: §f" + s.freezes + "§7/" + MAX_FREEZES + "§8 - buy more in §f/rewards§8.");
      }
      if (lost > 0) {
         Chat.raw(player, "§c§lSTREAK LOST§r §7- you were on §fday " + lost + "§7 and a missed day has put you back to §fday 1§7.");
         Chat.raw(player, "§7A §eStreak Freeze§7 would have carried it: §f" + FREEZE_COST_GEMS + " gems§7, kept automatically. See §f/rewards§7.");
      }
      Chat.raw(player, "§6Daily login streak: §fDay " + nextDay + "§6! §7Freezes: §f" + s.freezes + "§7/" + MAX_FREEZES);
      // Today's shift, then what it is working toward: the two things the old join message did not
      // say, and the two things that make a player come back tomorrow.
      Shift shift = shiftFor(nextDay);
      Chat.raw(player, "§7Today's shift: §f" + shift.name() + "§7 - " + shift.blurb() + ". " + rewardSummary(nextDay));
      int next = nextMilestone(nextDay);
      if (next > 0) {
         Chat.raw(player, "§7Next milestone: §fDay " + next + "§7, " + (next - nextDay) + " day(s) away.");
      }
      if (nextDay > 0 && !s.claimedDay.equals(now)) {
         Chat.raw(player, "§aA daily reward is ready! §7Claim it in §f/menu → Daily Streak§7 or §f/rewards§7.");
      }
   }

   public static int dayOf(UUID uuid) {
      PlayerStreak s = streaks.get(uuid);
      return s == null ? 0 : s.day;
   }

   public static int freezesOf(UUID uuid) {
      PlayerStreak s = streaks.get(uuid);
      return s == null ? 0 : s.freezes;
   }

   /** True if this player has a claimable reward right now. */
   public static boolean canClaim(UUID uuid) {
      PlayerStreak s = streaks.get(uuid);
      return s != null && s.day > 0 && !s.claimedDay.equals(today());
   }

   /** Attempts to buy a streak freeze with gems. Returns null on success, or an error message. */
   public static String buyFreeze(ServerPlayer player) {
      PlayerStreak s = streaks.get(player.getUUID());
      if (s == null || s.day <= 0) {
         return "Your streak starts the first day you log in - come back tomorrow!";
      }
      if (s.freezes >= MAX_FREEZES) {
         return "You already have the max of " + MAX_FREEZES + " freezes.";
      }
      long gems = EconomyManager.gemBalance(player.getUUID());
      if (gems < FREEZE_COST_GEMS) {
         return "A streak freeze costs " + FREEZE_COST_GEMS + " gems - you only have " + gems + ".";
      }
      if (!EconomyManager.takeGems(player.getUUID(), FREEZE_COST_GEMS)) {
         // Lost to another window spending the same gems between the read above and this
         // line. Reported rather than swallowed: the balance is the truth, and a player
         // who is told "purchased" while their gems are gone is a support ticket.
         return "You do not have " + FREEZE_COST_GEMS + " gems any more - your balance is "
            + EconomyManager.gemBalance(player.getUUID()) + ".";
      }
      s.freezes++;
      save(player.level().getServer());
      Chat.raw(player, "§aPurchased a streak freeze! §7You now have §f" + s.freezes + "§7/3 - miss a day and it's used automatically.");
      return null;
   }

   /**
    * Claims today's daily reward. Returns null on success (with a chat summary),
    * or an error message if nothing can be claimed.
    */
   public static String claimReward(ServerPlayer player) {
      PlayerStreak s = streaks.get(player.getUUID());
      if (s == null || s.day <= 0) {
         return "Your streak starts the first day you log in - come back tomorrow!";
      }
      if (s.claimedDay.equals(today())) {
         return "You already claimed today's reward.";
      }
      s.claimedDay = today();
      List<String> rewards = grantMilestone(player, s.day);
      if (rewards.isEmpty()) {
         Chat.raw(player, "§6Day " + s.day + " reward claimed! §7No milestone today - the next one is " + nextMilestoneName(s.day) + ".");
      } else {
         Chat.raw(player, "§6§lDay " + s.day + " reward claimed!§r");
         for (String r : rewards) {
            Chat.raw(player, " §a✓ §7" + r);
         }
      }
      save(player.level().getServer());
      return null;
   }

   /** The milestone days, in order, so menus can render the ladder. */
   public static final int[] MILESTONE_DAYS = {1, 3, 7, 14, 21, 30, 60, 100};

   /**
    * The seven-day shift: what a day of the cycle pays <i>on top of</i> the cash.
    *
    * <p>Before this, eight days out of the ladder paid anything at all - 1, 3, 7, 14, 21, 30, 60
    * and 100 - and every other day paid a scaling amount of cash and nothing else. So the second
    * through sixth days of a fresh streak, which are the days a new player is deciding whether the
    * daily is worth opening at all, paid a few thousand coins and a screen that said "no milestone
    * today". A seven-day cycle means no day is ever bare: the shift names what today's day of the
    * week pays, the milestones still land on top of it, and the calendar can say what tomorrow is
    * going to be worth.
    */
   public record Shift(String name, String blurb, int gems, int keyTier, int cashMult) {
   }

   private static final Shift[] SHIFTS = {
      new Shift("Payday", "a doubled cash drop", 0, -1, 2),
      new Shift("Old Scores", "a gem on the side", 1, -1, 1),
      new Shift("Supply Run", "a Common Mystery Key", 0, MysteryChestManager.COMMON, 1),
      new Shift("Payday", "a doubled cash drop", 0, -1, 2),
      new Shift("Old Scores", "two gems on the side", 2, -1, 1),
      new Shift("Supply Run", "a Common Mystery Key", 0, MysteryChestManager.COMMON, 1),
      new Shift("Week's End", "a Rare Mystery Key and a gem", 1, MysteryChestManager.RARE, 1)
   };

   public static final int SHIFT_DAYS = SHIFTS.length;

   /** What the given day of a streak pays on top of the cash. */
   public static Shift shiftFor(int day) {
      return SHIFTS[day <= 0 ? 0 : (day - 1) % SHIFTS.length];
   }

   /**
    * The next milestone at or after this day, or -1 once the ladder is finished.
    *
    * <p>Public because both the claim message and the calendar need to answer "what am I working
    * toward", and two answers computed in two places is how a ladder ends up telling a player to
    * aim at day 14 on one screen and day 21 on the next.
    */
   public static int nextMilestone(int day) {
      for (int m : MILESTONE_DAYS) {
         if (m > day) {
            return m;
         }
      }
      return -1;
   }

   private static String nextMilestoneName(int day) {
      int next = nextMilestone(day);
      return next < 0 ? "the maxed-out streak (keep going for bragging rights!)" : "Day " + next;
   }

   /** Base cash paid out every day - scales with the streak, capped.
    *  Made 4x more generous so the daily reward is actually worth claiming. */
   private static long baseCash(int day) {
      return Math.min(2000L * day, 100000L);
   }

   private static RewardPlan planFor(int day) {
      RewardPlan p = new RewardPlan();
      // The day's shift first, so a milestone can improve on it rather than replace it.
      Shift shift = shiftFor(day);
      p.cashMult = shift.cashMult();
      p.tokens = shift.gems();
      p.keyTier = shift.keyTier();
      p.shift = shift.name();
      p.bonus = switch (day) {
         case 3 -> 2000L;
         case 7 -> 5000L;
         case 14 -> 10000L;
         case 21 -> 25000L;
         case 30 -> 50000L;
         case 60 -> 100000L;
         case 100 -> 250000L;
         default -> 0L;
      };
      p.tokens += switch (day) {
         case 1, 3 -> 1;
         case 21 -> 2;
         case 30 -> 3;
         case 60 -> 5;
         case 100 -> 10;
         default -> 0;
      };
      // A milestone key is never worse than the day's shift key: the better of the two.
      int milestoneKey = switch (day) {
         case 7 -> MysteryChestManager.COMMON;
         case 14 -> MysteryChestManager.RARE;
         case 21 -> MysteryChestManager.EPIC;
         case 30, 60, 100 -> MysteryChestManager.LEGENDARY;
         default -> -1;
      };
      p.keyTier = Math.max(p.keyTier, milestoneKey);
      if (day == 14) {
         p.tag = "Veteran";
      } else if (day == 60) {
         p.tag = "Centurion";
      }
      if (day == 30) {
         p.title = "Dedicated";
      } else if (day == 100) {
         p.title = "Immortal";
      }
      return p;
   }

   /** Human-readable summary of everything a given streak day grants (menus + chat). */
   public static String rewardSummary(int day) {
      if (day <= 0) {
         return "";
      }
      RewardPlan p = planFor(day);
      StringBuilder sb = new StringBuilder("§a" + Chat.moneyStr(baseCash(day) * p.cashMult + p.bonus) + " cash");
      if (p.shift != null) {
         sb.append("§8 (").append(p.shift).append(")");
      }
      if (p.keyTier >= 0) {
         sb.append("§7, §b").append(MysteryChestManager.tierName(p.keyTier)).append(" Mystery Key");
      }
      if (p.tokens > 0) {
         sb.append("§7, §5").append(p.tokens).append(" gem").append(p.tokens == 1 ? "" : "s");
      }
      if (p.tag != null) {
         sb.append("§7, §b[").append(p.tag).append("§b] tag");
      }
      if (p.title != null) {
         sb.append("§7, §e[").append(p.title).append("§e] title");
      }
      return sb.toString();
   }

   /** Grants the reward for a day and returns a human-readable list of what was given. */
   private static List<String> grantMilestone(ServerPlayer player, int day) {
      List<String> out = new ArrayList<>();
      RewardPlan p = planFor(day);
      long cash = baseCash(day) * p.cashMult + p.bonus;
      EconomyManager.addCash(player.getUUID(), cash);
      out.add("§a" + Chat.moneyStr(cash) + " cash");
      if (p.keyTier >= 0) {
         com.fortuneandfavors.util.InventoryHelper.giveOrDrop(player, MysteryChestManager.mysteryKey(p.keyTier));
         out.add("§b" + MysteryChestManager.tierName(p.keyTier) + " Mystery Key");
      }
      if (p.tokens > 0) {
         TokenManager.giveGems(player, p.tokens);
         out.add("§5" + p.tokens + " gem" + (p.tokens == 1 ? "" : "s"));
      }
      if (p.tag != null) {
         TagManager.addOwned(player.getUUID(), p.tag);
         if (TagManager.getTag(player.getUUID()) == null) {
            TagManager.setTag(player.getUUID(), p.tag, 5635925);
            out.add("tag §b[" + p.tag + "]§r§7 (equipped)");
         } else {
            out.add("§7unlocked tag §b[" + p.tag + "]§r§7 - equip it in §f/menu → Tags & Titles");
         }
      }
      if (p.title != null) {
         TitleManager.unlock(player, p.title);
         out.add("title §e" + p.title);
      }
      return out;
   }

   private static final class RewardPlan {
      long bonus;
      int tokens;
      int keyTier = -1;
      int cashMult = 1;
      /** The name of the day's shift, for the summary line. */
      String shift;
      String tag;
      String title;
   }

   private static final class PlayerStreak {
      int day;
      String lastSeen;
      int freezes;
      String claimedDay;

      PlayerStreak(int day, String lastSeen, int freezes, String claimedDay) {
         this.day = day;
         this.lastSeen = lastSeen;
         this.freezes = freezes;
         this.claimedDay = claimedDay;
      }
   }
}
