package com.fortuneandfavors.economy;

import com.fortuneandfavors.util.JsonUtil;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * What a player keeps from the expeditions they have survived: Expedition EXP, and the eight things
 * the permanent shop sells.
 *
 * <p>A run pays money, and money is spent the moment it lands. This is the part of a run that is
 * kept: <b>EXP is earned from what a run pays out</b> - one point per {@link #MONEY_PER_EXP} of
 * secured loot - and it buys permanent upgrades at the Expedition Shop, which is open whether or not
 * the player is in a site. That is the whole design: money is the run's own economy (supplies, the
 * auction house, gear), and EXP is the run's <i>practice</i>, paid out over many runs rather than
 * cashed in one.
 *
 * <p>Deliberately small. A strong dungeon pays a couple of hundred thousand a run, and at one point
 * per five thousand that is a few dozen EXP - so a single good run buys at most one level of one
 * line, and the full tree is something like thirty runs of work. A currency that a good night maxes
 * out is a currency that has nothing left to say.
 *
 * <p>The survivability branch is the one edge in the shop: {@link Upgrade#FIELD_MEDICINE} is the
 * root a body buys when the site's health budget is what is killing it, and {@link Upgrade#FIELD_KIT}
 * hangs off it, answering the site's other leaks - venom, falls and the clock. A prerequisite rather
 * than a price, and the same field the menu draws the branch from, so the picture and the gate are
 * one thing.
 *
 * <p>Everything here is per player and persisted beside the codex, and every number the rest of the
 * mod reads is read through a named seam ({@link Upgrade#value}) rather than off a field, so what a
 * level is worth is one place and the harness can ask about it.
 */
public final class ExpeditionProgression {
   /**
    * Money that buys one point of Expedition EXP.
    *
    * <p>Five thousand, which is the number that makes a run pay tens rather than thousands: the
    * report that set it was "I make about two hundred thousand a run", and at this rate that run is
    * forty EXP. <b>Raising this makes the tree cheaper; lowering it makes it a grind.</b>
    */
   public static final long MONEY_PER_EXP = 5_000L;

   /**
    * The three answers the Field Kit branch buys, one level at a time.
    *
    * <p>Named here rather than written where they are felt, so what a level of the branch is worth
    * is one number each and the harness can read the same number the run does. Level one shortens
    * site venom to half; level two halves a fall inside a site; level three pays a quarter-minute
    * back onto the clock for every chamber cleared.
    */
   public static final float KIT_VENOM_SCALE = 0.5F;
   public static final float KIT_FALL_SCALE = 0.5F;
   public static final long KIT_CHAMBER_REBATE_TICKS = 300L;

   /**
    * The eight lines the permanent shop sells, six of them roots and one branch off Field Medicine.
    *
    * <p>Each carries what it does and how far it goes, and the two are one thing: {@link #maxLevel}
    * is how many times it may be bought, {@link #base} is what the first level costs, and
    * {@link #value} answers what one level is worth to the run. A line whose value is not read from
    * here would be a line the shop sells and the dungeon ignores, and {@link #prerequisite} is the
    * edge that makes the survivability lines a tree instead of two names in a row.
    */
   public enum Upgrade {
      /** Movement speed, refreshed for the length of a run. */
      SWIFTNESS("Windstep", "§b", Items.FEATHER, 5, 25L,
         "§7Speed while you are inside a site.",
         "§8Speed I, I, II, II, III.", null),
      /** Melee damage, refreshed for the length of a run. */
      MIGHT("Ironblood", "§c", Items.IRON_SWORD, 5, 30L,
         "§7Strength while you are inside a site.",
         "§8Strength I, I, II, II, III.", null),
      /** One fatal blow a run is survived instead of ended. Single level by design. */
      SECOND_WIND("Second Wind", "§d", Items.TOTEM_OF_UNDYING, 1, 150L,
         "§7The first fatal blow of every run is survived.",
         "§8Once a run, you are put back on your feet.", null),
      /** More time on the clock, which is the one thing a deep run always runs out of. */
      LONGER_ROPE("Longer Rope", "§e", Items.CLOCK, 5, 25L,
         "§7More time on the clock, from the first tick.",
         "§8One minute per level.", null),
      /** A bigger loot backpack from the moment the entrance hands one over. */
      DEEP_POCKETS("Deep Pockets", "§6", Items.BUNDLE, 4, 35L,
         "§7The pack you are handed starts with more room.",
         "§8Three pieces per level.", null),
      /** The chance a chamber of any run holds the Expedition Broker. */
      BROKER("The Broker", "§a", Items.EMERALD, 5, 30L,
         "§7Chance an Expedition Broker is standing in a chamber.",
         "§8Four per cent per level.", null),
      /**
       * The site's own healing penalty, bought back.
       *
       * <p>A site is a trip with a health budget, and the budget is enforced by handing back only
       * part of what a mend and a full stomach would give in the overworld - see
       * {@link ExpeditionManager#MEND_SCALE} and {@link ExpeditionManager#SITE_REGEN_EFFICIENCY}.
       * This is the line that pays that back: half again as much of every heal, added straight onto
       * the fraction of it a site keeps rather than multiplied into it, so the penalty the ask
       * complained about shrinks by fifty points at the top level and the two reductions read as
       * the numbers the request named.
       *
       * <p>Single level on purpose, like {@link #SECOND_WIND}: it is a change to how a run breathes
       * rather than a ladder, and a body that has bought it should stop thinking about health and
       * start thinking about depth.
       */
      FIELD_MEDICINE("Field Medicine", "§d", Items.GOLDEN_APPLE, 1, 120L,
         "§7A site's healing penalty is fifty points smaller for you.",
         "§8Mends and food regen: +50% inside a site.", null),
      /**
       * The second line of the survivability tree, and everything the healing line does not answer.
       *
       * <p>{@link #FIELD_MEDICINE} is the root: it buys the health budget back, and a body that has
       * it has already decided that surviving the trip is a line worth owning. This is the branch
       * that hangs off it, and it answers the site's <i>other</i> leaks - the ones a clamp on healing
       * never touches. Venom is the damage that arrives whether or not the explorer earned it; a
       * fall is the one mistake the maze punishes with the whole of a budget at once; and the clock
       * is the leak every run eventually loses to, because the maze is infinite and the timer is
       * not. One line, three levels, each answering one of them, so a maxed kit has shortened the
       * venom, softened the landing and made clearing ground buy back the time to clear more.
       *
       * <p>It is a ladder rather than a single purchase because the three answers arrive one at a
       * time - a level is a decision about which leak is costing the explorer most right now - and
       * because the branch should read as the tree the shop now draws it as: Field Medicine is the
       * gate, and this is what the gate opens onto.
       */
      FIELD_KIT("Field Kit", "§2", Items.SHIELD, 3, 60L,
         "§7Venom, falls and the clock - the rest of the trip's bill.",
         "§8Venom ×½, falls ×½, chambers pay §a+15s§8 back.", FIELD_MEDICINE);

      public final String name;
      public final String colour;
      public final Item icon;
      public final int maxLevel;
      public final long base;
      public final String blurb;
      public final String detail;
      /**
       * The line that has to be bought before this one can be, or null when it stands on its own - the
       * edge that turns the shelf into a tree.
       *
       * <p>A prerequisite rather than a price, because the point of the survivability branch is that
       * it is what a healer learns <i>next</i>: a body that has bought the health budget back is the
       * only body the rest of the kit makes sense to.
       */
      public final Upgrade prerequisite;

      Upgrade(String name, String colour, Item icon, int maxLevel, long base, String blurb, String detail, Upgrade prerequisite) {
         this.name = name;
         this.colour = colour;
         this.icon = icon;
         this.maxLevel = maxLevel;
         this.base = base;
         this.blurb = blurb;
         this.detail = detail;
         this.prerequisite = prerequisite;
      }

      /** What the next level of this line costs at {@code nextLevel} (1-based), or -1 when maxed. */
      public long costAt(int nextLevel) {
         if (nextLevel < 1 || nextLevel > maxLevel) {
            return -1L;
         }
         // The ladder is linear and deliberately shallow: level N costs N times the base, so the
         // whole line costs base * (maxLevel * (maxLevel + 1) / 2).
         return base * nextLevel;
      }

      /** The whole line's price, for the shop's footer and the harness. */
      public long totalCost() {
         long sum = 0L;
         for (int lvl = 1; lvl <= maxLevel; lvl++) {
            sum += costAt(lvl);
         }
         return sum;
      }

      /**
       * What one level of this line is worth, as the number the dungeon reads.
       *
       * <p>The unit is per line rather than shared, because the lines are not the same kind of thing:
       * the speed and strength lines answer an effect amplifier, the rope answers ticks, the pockets
       * answer inventory slots, the broker answers a probability and the second wind answers how many
       * times a run may be saved. Every reader goes through this method, so a level is one number to
       * change rather than four.
       */
      public double value(int level) {
         int lvl = Math.max(0, Math.min(maxLevel, level));
         return switch (this) {
            // Speed and strength climb every other level so the ladder reads as a real power curve
            // rather than a straight line: I, I, II, II, III.
            case SWIFTNESS, MIGHT -> lvl <= 0 ? -1 : (lvl - 1) / 2;
            case SECOND_WIND -> lvl <= 0 ? 0 : 1;
            case LONGER_ROPE -> lvl * 60.0 * 20.0;
            case DEEP_POCKETS -> lvl * 3.0;
            case BROKER -> lvl * 0.04;
            // Half of a site's healing penalty handed back, as a fraction: 0.5 at the one level.
            case FIELD_MEDICINE -> lvl * 0.5;
            // The kit's three answers are read off the level the way a checklist is read: level one
            // shortens venom, two softens falls, three buys the clock back. The value is just the
            // count, and each seam below asks the count it needs - so level three carries levels one
            // and two with it, which is what a branch of a tree is meant to do.
            case FIELD_KIT -> lvl;
         };
      }

      /** How many rungs stand between this line and the root of its branch; 0 for a root. */
      public int depth() {
         return this.prerequisite == null ? 0 : 1 + this.prerequisite.depth();
      }
   }

   /** One player's ledger: everything ever earned, everything spent, and what it bought. */
   private static final class Progress {
      long earned;
      long spent;
      final EnumMap<Upgrade, Integer> levels = new EnumMap<>(Upgrade.class);

      int level(Upgrade u) {
         return levels.getOrDefault(u, 0);
      }

      long available() {
         return Math.max(0L, earned - spent);
      }
   }

   private static final Map<UUID, Progress> progress = new HashMap<>();
   private static Path file;

   private ExpeditionProgression() {
   }

   // ------------------------------------------------------------------ reading a player's ledger

   private static Progress of(UUID uuid) {
      return progress.computeIfAbsent(uuid, k -> new Progress());
   }

   /** Every point of EXP this player has ever banked - the shop's headline number. */
   public static long earned(UUID uuid) {
      return uuid == null ? 0L : of(uuid).earned;
   }

   /** EXP this player still has to spend. */
   public static long available(UUID uuid) {
      return uuid == null ? 0L : of(uuid).available();
   }

   /** How many levels of one line this player owns. */
   public static int level(UUID uuid, Upgrade upgrade) {
      return uuid == null ? 0 : of(uuid).level(upgrade);
   }

   /** The next level's price for this player, or -1 when the line is finished. */
   public static long nextCost(UUID uuid, Upgrade upgrade) {
      return upgrade.costAt(level(uuid, upgrade) + 1);
   }

   /**
    * What one level is worth to a run, for this player.
    *
    * <p>The one call the dungeon makes, so nothing in a run reads a level count directly and every
    * reader agrees about what a level buys.
    */
   public static double value(UUID uuid, Upgrade upgrade) {
      return upgrade.value(level(uuid, upgrade));
   }

   // ------------------------------------------------------------------ the seams a run reads

   /** Mob-effect amplifier for the speed line, or -1 when the line is unbought. */
   public static int speedAmplifier(UUID uuid) {
      return (int)value(uuid, Upgrade.SWIFTNESS);
   }

   /** Mob-effect amplifier for the strength line, or -1 when the line is unbought. */
   public static int strengthAmplifier(UUID uuid) {
      return (int)value(uuid, Upgrade.MIGHT);
   }

   /** Extra ticks on a run's clock before a single second of it is earned. */
   public static long timeBonusTicks(UUID uuid) {
      return (long)value(uuid, Upgrade.LONGER_ROPE);
   }

   /** How many pieces the backpack the entrance hands over starts with. */
   public static int backpackStartCapacity(UUID uuid) {
      return LootBackpack.BASE_CAPACITY + (int)value(uuid, Upgrade.DEEP_POCKETS);
   }

   /** The chance, per carved chamber, that an Expedition Broker is standing in it. */
   public static double brokerChance(UUID uuid) {
      return value(uuid, Upgrade.BROKER);
   }

   /** How many times a run may be saved from a fatal blow. */
   public static int reviveCharges(UUID uuid) {
      return (int)value(uuid, Upgrade.SECOND_WIND);
   }

   /**
    * How much more of a site's healing this explorer keeps, as a fraction of the whole: 0 for a
    * body that has never bought the line, 0.5 for one that has.
    *
    * <p>Read by the site's own arithmetic rather than by a caller, which is the point: the two
    * places a site thins healing - the mends and the food the body does not eat - both ask this, so
    * one line moves both and neither can be forgotten.
    */
   public static float healBonus(UUID uuid) {
      return (float)value(uuid, Upgrade.FIELD_MEDICINE);
   }

   /**
    * What a site's venom still costs this explorer, as a fraction of the site's own cap: 1 with no
    * Field Kit, half from the branch's first level on.
    *
    * <p>Read by the site's venom trim rather than at the source of the poison, for the same reason
    * the site caps venom in one place: every spider, every trap and everything the maze grows later
    * lands on the same body, so the explorer's own line is one gate all of them pass through.
    */
   public static float venomScale(UUID uuid) {
      return value(uuid, Upgrade.FIELD_KIT) >= 1.0 ? KIT_VENOM_SCALE : 1.0F;
   }

   /**
    * What a fall inside a site costs this explorer, as a fraction of the fall: 1 with no Field Kit,
    * half from the branch's second level on.
    */
   public static float fallScale(UUID uuid) {
      return value(uuid, Upgrade.FIELD_KIT) >= 2.0 ? KIT_FALL_SCALE : 1.0F;
   }

   /**
    * The ticks a cleared chamber hands back onto the run's clock, or 0 until the branch's last
    * level is bought.
    *
    * <p>The clock's leak is the one a site can never stop: the maze is infinite and the timer is
    * not, so every deep push is paid for with the walk in and the walk home. A rebate is a different
    * answer to that than {@link Upgrade#LONGER_ROPE}'s longer rope - a longer rope is more time up
    * front, a rebate is time paid for the ground actually taken - and together they are the two ways
    * a run can stop being a countdown.
    */
   public static long chamberRebateTicks(UUID uuid) {
      return value(uuid, Upgrade.FIELD_KIT) >= 3.0 ? KIT_CHAMBER_REBATE_TICKS : 0L;
   }

   // ------------------------------------------------------------------ earning

   /**
    * What a payout is worth in EXP.
    *
    * <p>Purely a function of the money, which is the request in one line: "based on how much money
    * you get you can get expedition EXP". A run that secures nothing earns nothing, and a run that
    * secures a fortune earns a fraction of it - never a windfall, because a single run must not be
    * able to buy the tree.
    */
   public static long expFor(long payout) {
      return payout <= 0L ? 0L : payout / MONEY_PER_EXP;
   }

   /**
    * Banks a run's payout as EXP.
    *
    * @return the EXP it was worth
    */
   public static long award(UUID uuid, long payout) {
      long gained = expFor(payout);
      if (uuid == null || gained <= 0L) {
         return 0L;
      }
      of(uuid).earned += gained;
      return gained;
   }

   // ------------------------------------------------------------------ spending

   /**
    * What it costs in EXP to unlock one descent.
    *
    * <p>EXP used to only ever be spent on the six lines, which made it a shop currency and not a
    * schedule: once a tree was bought, the next run was as free as the last one and the only thing
    * standing between a player and the richest site in the mod was the walk. A site now has a price
    * in the same currency it pays in, so deeper ground has to be afforded rather than merely walked
    * to, and each line the explorer has already bought competes with the next descent for the same
    * points. That is the whole tension of the mode in one number.
    *
    * <p>Built from what the site pays rather than from its name: the Deep Mine is the cheapest site
    * and is deliberately FREE, because it is where the first EXP comes from and a price on it would
    * lock a new explorer out of the only thing that pays. Everything past it costs, and the cost
    * climbs with the cash multiplier - an expensive site is expensive to open.
    */
   public static long entryCost(ExpeditionManager.Type type) {
      if (type == null) {
         return 0L;
      }
      return switch (type) {
         case DEEP_MINE -> 0L;
         case SUNKEN_TEMPLE, FROZEN_CRYPT -> 45L;
         case CRYSTAL_CAVERN -> 70L;
         case MONSTER_CAVE, VOID -> 120L;
         case MAGMA_FORGE -> 180L;
      };
   }

   /**
    * Charges a descent's entry fee against what this explorer has banked.
    *
    * @return true when the fee was really paid
    */
   public static boolean payEntry(UUID uuid, long cost) {
      if (cost <= 0L) {
         return true;
      }
      if (uuid == null || available(uuid) < cost) {
         return false;
      }
      of(uuid).spent += cost;
      return true;
   }

   /**
    * Buys the next level of one line.
    *
    * @return an error message for the player, or null when the level was really bought
    */
   public static String buy(UUID uuid, Upgrade upgrade) {
      if (uuid == null || upgrade == null) {
         return "There is nothing to buy.";
      }
      Progress p = of(uuid);
      // The branch's gate: a line the tree hangs off cannot be bought before the line above it, so
      // the shop's tree is a rule and not only a drawing.
      if (upgrade.prerequisite != null && p.level(upgrade.prerequisite) <= 0) {
         return "§d" + upgrade.name + "§7 hangs off §f" + upgrade.prerequisite.name
            + "§7 - buy that line first.";
      }
      int now = p.level(upgrade);
      long cost = upgrade.costAt(now + 1);
      if (cost < 0L) {
         return "§d" + upgrade.name + "§7 is already at its last level.";
      }
      if (p.available() < cost) {
         return "§d" + upgrade.name + "§7 costs §f" + cost + " EXP§7 and you have §f" + p.available()
            + "§7 - run some expeditions.";
      }
      p.spent += cost;
      p.levels.put(upgrade, now + 1);
      return null;
   }

   /** Test seam: put a player's ledger wherever a check needs it, without arranging runs. */
   public static void setForTest(UUID uuid, long earned, long spent) {
      Progress p = of(uuid);
      p.earned = Math.max(0L, earned);
      p.spent = Math.max(0L, spent);
   }

   /** Test seam: clear one player's ledger and everything it bought. */
   public static void forgetForTest(UUID uuid) {
      progress.remove(uuid);
   }

   /** Wipes every ledger - the harness's own reset, so checks do not depend on each other's order. */
   public static void forgetAllForTest() {
      progress.clear();
   }

   // ------------------------------------------------------------------ persistence

   public static void load(MinecraftServer server) {
      progress.clear();
      file = EconomyManager.getDataDir(server).resolve("expedition_progress.json");
      JsonObject root = JsonUtil.readOrCreate(file, new JsonObject());
      if (!root.has("players") || !root.get("players").isJsonObject()) {
         return;
      }
      for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("players").entrySet()) {
         try {
            UUID id = UUID.fromString(entry.getKey());
            JsonObject o = entry.getValue().getAsJsonObject();
            Progress p = new Progress();
            p.earned = JsonUtil.jsonLong(o, "earned", 0L);
            p.spent = JsonUtil.jsonLong(o, "spent", 0L);
            if (o.has("levels") && o.get("levels").isJsonObject()) {
               for (Map.Entry<String, JsonElement> lvl : o.getAsJsonObject("levels").entrySet()) {
                  try {
                     p.levels.put(Upgrade.valueOf(lvl.getKey()), lvl.getValue().getAsInt());
                  } catch (Exception ignored) {
                     // A line this build no longer sells is dropped rather than carried forever.
                  }
               }
            }
            progress.put(id, p);
         } catch (Exception ignored) {
         }
      }
   }

   public static void save(MinecraftServer server) {
      if (file == null) {
         file = EconomyManager.getDataDir(server).resolve("expedition_progress.json");
      }
      JsonObject root = new JsonObject();
      JsonObject players = new JsonObject();
      for (Map.Entry<UUID, Progress> e : progress.entrySet()) {
         JsonObject o = new JsonObject();
         o.addProperty("earned", e.getValue().earned);
         o.addProperty("spent", e.getValue().spent);
         JsonObject levels = new JsonObject();
         for (Map.Entry<Upgrade, Integer> lvl : e.getValue().levels.entrySet()) {
            levels.addProperty(lvl.getKey().name(), lvl.getValue());
         }
         o.add("levels", levels);
         players.add(e.getKey().toString(), o);
      }
      root.add("players", players);
      JsonUtil.write(file, root);
   }
}
