package com.fortuneandfavors.economy;

import com.fortuneandfavors.FortuneFavorsMod;
import com.fortuneandfavors.util.JsonUtil;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.server.MinecraftServer;

/**
 * The dials a player can turn on a machine they already own: a Super Hopper's throughput, a Super
 * Smelter's speed and its fuel, and whether a Checker Hopper voids what nobody wants.
 *
 * <p>Every other machine in this mod is bought at one size and stays that size. These two are the
 * ones whose whole point is rate, and rate is the one thing a player who has outgrown their first
 * farm wants to buy more of - so instead of a second block for every speed, one machine goes up in
 * tiers and the tier is what the price curve is for. A tier is a *number on a placed block*, not a
 * property of the item in the shop: it belongs to the machine, it is paid for where the machine
 * stands, and it survives a restart. See {@link com.fortuneandfavors.menu.MachineUpgradeMenu} for
 * where it is bought and {@link HeavyHoppers}/{@code AbstractFurnaceBlockEntityMixin} for where it
 * is spent.
 *
 * <p>Kept in its own file rather than on {@code MachineManager.Machine}, deliberately. The machine
 * ledger is what tells the server a hopper is a machine at all, and a machine without a tuning
 * record is simply tier one - so a wipe of this file costs players their upgrades and nothing else,
 * and a machine is never lost to a tuning write that went wrong.
 */
public final class MachineTuning {
   /** How many tiers a machine can reach. Tier one is what the shop sells. */
   public static final int MAX_TIER = 5;

   /**
    * What each upgrade costs, indexed by the tier being bought.
    *
    * <p>The first step is deliberately the cheap one: a player who just placed the machine should be
    * able to buy their way past "this is slower than I expected" without a second decision. The last
    * two steps are the ones that price the machine out of being bought casually, because a tier five
    * smelter is roughly a stack of ingots a minute out of one furnace.
    */
   private static final long[] UPGRADE_COST = new long[]{0L, 25_000L, 80_000L, 220_000L, 600_000L};

   /** A Super Hopper tier moves this many stacks a tick. Tier one is one stack, as it always was. */
   private static final int[] HOPPER_STACKS = new int[]{1, 2, 3, 4, 5};

   /**
    * The fraction of a plain cook time a Super Smelter takes, per tier.
    *
    * <p>Tier one is 0.40 - 200 ticks becomes 80, which is 2.5x a furnace and a little better than a
    * blast furnace's flat 2x. The last tier is 0.15, just under seven furnaces in one block.
    */
   private static final double[] SMELTER_SPEED = new double[]{0.4, 0.32, 0.25, 0.2, 0.15};

   /**
    * Extra burn ticks a lit Super Smelter spends per tick, per tier.
    *
    * <p>This is the half that makes speed a choice rather than a gift: a faster smelter also eats the
    * same coal sooner, so what a player buys is throughput, not a discounted fuel bill.
    */
   private static final int[] SMELTER_EXTRA_FUEL = new int[]{2, 3, 4, 6, 8};

   private static final Map<String, Record> records = new LinkedHashMap<>();
   private static Path dataFile;
   private static boolean loadHealthy = true;

   /** One machine's settings. Absent means "tier one, keep everything". */
   public record Record(int tier, boolean voiding) {
   }

   private MachineTuning() {
   }

   public static void load(MinecraftServer server) {
      records.clear();
      loadHealthy = true;
      dataFile = EconomyManager.getDataDir(server).resolve("machine_tuning.json");
      if (dataFile == null || !Files.exists(dataFile)) {
         return;
      }
      try {
         String content = Files.readString(dataFile);
         if (content.isBlank()) {
            return;
         }
         JsonObject root = (JsonObject)JsonUtil.gson().fromJson(content, JsonObject.class);
         if (root == null || !root.has("machines") || !root.get("machines").isJsonObject()) {
            return;
         }
         for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("machines").entrySet()) {
            JsonElement el = e.getValue();
            if (el == null || !el.isJsonObject()) {
               continue;
            }
            JsonObject obj = el.getAsJsonObject();
            int tier = clampTier(JsonUtil.jsonLong(obj, "tier", 1L));
            boolean voiding = JsonUtil.jsonBool(obj, "void", false);
            if (tier > 1 || voiding) {
               records.put(e.getKey(), new Record(tier, voiding));
            }
         }
      } catch (Exception e) {
         loadHealthy = false;
         FortuneFavorsMod.LOGGER.error("Machine tuning load failed - writes are disabled to protect machine_tuning.json", e);
      }
   }

   public static boolean save(MinecraftServer server) {
      if (!loadHealthy) {
         return false;
      }
      if (dataFile == null && server != null) {
         dataFile = EconomyManager.getDataDir(server).resolve("machine_tuning.json");
      }
      if (dataFile == null) {
         return false;
      }
      JsonObject root = new JsonObject();
      JsonObject machines = new JsonObject();
      for (Map.Entry<String, Record> e : records.entrySet()) {
         JsonObject obj = new JsonObject();
         obj.addProperty("tier", e.getValue().tier());
         obj.addProperty("void", e.getValue().voiding());
         machines.add(e.getKey(), obj);
      }
      root.add("machines", machines);
      return JsonUtil.write(dataFile, root);
   }

   private static int clampTier(long value) {
      return (int)Math.max(1L, Math.min((long)MAX_TIER, value));
   }

   /** Forgets a machine's dials - called when the machine itself is picked up. */
   public static void forget(String key) {
      if (key != null && records.remove(key) != null) {
         save(null);
      }
   }

   public static int tier(String key) {
      Record r = records.get(key);
      return r == null ? 1 : r.tier();
   }

   public static boolean voiding(String key) {
      Record r = records.get(key);
      return r != null && r.voiding();
   }

   public static void setVoiding(String key, boolean on) {
      if (key == null) {
         return;
      }
      Record r = records.getOrDefault(key, new Record(1, false));
      if (on) {
         records.put(key, new Record(r.tier(), true));
      } else if (r.tier() > 1) {
         records.put(key, new Record(r.tier(), false));
      } else {
         records.remove(key);
      }
      save(null);
   }

   /** Records a tier already paid for. Does not charge - see {@link #costTo}. */
   public static void setTier(String key, int tier) {
      if (key == null) {
         return;
      }
      int next = clampTier(tier);
      Record r = records.getOrDefault(key, new Record(1, false));
      if (next <= 1 && !r.voiding()) {
         records.remove(key);
      } else {
         records.put(key, new Record(next, r.voiding()));
      }
      save(null);
   }

   /** What moving from tier {@code from} up to tier {@code to} costs. Free when going down. */
   public static long costTo(int from, int to) {
      long total = 0L;
      for (int t = Math.max(2, from + 1); t <= Math.min(MAX_TIER, to); t++) {
         total += UPGRADE_COST[t - 1];
      }
      return total;
   }

   /** The price of the step from {@code tier} to the next one, or -1 when already at the top. */
   public static long nextStepCost(int tier) {
      return tier >= MAX_TIER ? -1L : UPGRADE_COST[tier];
   }

   public static String tierName(int tier) {
      return switch (tier) {
         case 1 -> "§fI";
         case 2 -> "§aII";
         case 3 -> "§bIII";
         case 4 -> "§dIV";
         default -> "§6V";
      };
   }

   // ------------------------------------------------------------------ Super Hopper

   public static int hopperStacks(int tier) {
      return HOPPER_STACKS[clampIndex(tier)];
   }

   /** How many items one Super Hopper pulse moves each way at this tier. */
   public static int hopperBudget(String key) {
      return hopperStacks(tier(key)) * 64;
   }

   /** A sentence for the shop lore and the upgrade window. */
   public static String hopperSpeedLine(int tier) {
      int stacks = hopperStacks(tier);
      return stacks == 1 ? "§7Moves §f1 stack§7 a tick." : "§7Moves §f" + stacks + " stacks§7 a tick.";
   }

   // ------------------------------------------------------------------ Super Smelter

   public static double smelterSpeed(int tier) {
      return SMELTER_SPEED[clampIndex(tier)];
   }

   public static int smelterExtraFuel(int tier) {
      return SMELTER_EXTRA_FUEL[clampIndex(tier)];
   }

   public static String smelterSpeedLine(int tier) {
      double multiplier = Math.round((1.0 / smelterSpeed(tier)) * 10.0) / 10.0;
      return "§7Cooks §f" + multiplier + "x§7 a furnace.";
   }

   public static String smelterFuelLine(int tier) {
      // The burn clock drains once per tick for vanilla, plus the tier's extra - so the multiple a
      // player cares about is 1 + extra, and saying it that way is what keeps the lore honest.
      return "§7Burns fuel §f" + (1 + smelterExtraFuel(tier)) + "x§7 as fast.";
   }

   private static int clampIndex(int tier) {
      return Math.max(1, Math.min(MAX_TIER, tier)) - 1;
   }
}
