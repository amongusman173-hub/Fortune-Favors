package com.fortuneandfavors.economy;

import com.fortuneandfavors.ModItems;
import com.fortuneandfavors.ModSounds;
import com.fortuneandfavors.economy.SkillManager.RandomHolder;
import com.fortuneandfavors.economy.SkillManager.Upgrade;
import com.fortuneandfavors.guild.GuildManager;
import com.fortuneandfavors.util.Chat;
import com.fortuneandfavors.util.JsonUtil;
import com.fortuneandfavors.util.SoundUtil;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Map.Entry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.resources.Identifier;
import java.util.Random;

public final class SkillManager {
   public static final String MINING = "mining";
   public static final String COMBAT = "combat";
   public static final String FARMING = "farming";
   public static final String TRADING = "trading";
   public static final String FORAGING = "foraging";
   public static final String ENCHANTING = "enchanting";
   /**
    * The seventh tree, and the first one added since the skills screen was written.
    *
    * <p>Fishing is the odd one out of the original six in that it has no block to break and no mob
    * to kill: the whole tree hangs off one event, the reel-in. That is also what makes it worth
    * adding - there was no way to be rewarded for the one quiet activity in the game - and it is
    * why {@link #onFishCaught} is the single hook every upgrade in it reads from.
    */
   public static final String FISHING = "fishing";
   public static final String[] SKILLS = new String[]{"mining", "combat", "farming", "trading", "foraging", "enchanting", "fishing"};
   public static final int MAX_LEVEL = 10;
   /**
    * Extra points handed out for finishing a tree - level 10 is the capstone, not just the
    * eleventh level-up.
    *
    * <p>A tree used to end in silence: the tenth point bought exactly what the ninth did, and
    * the skill kept quietly climbing past ten while the others lagged, banking points off a
    * finished tree. The capstone makes the end of a tree the best single level in it, and the
    * level cap means a finished skill stops paying instead.
    */
   public static final int CAPSTONE_BONUS = 1;
   public static final String UP_HASTE = "haste";
   public static final String UP_DURABILITY = "durability";
   public static final String UP_FORTUNE = "fortune";
   public static final String UP_AUTOSMELT = "autosmelt";
   public static final String UP_DAMAGE = "damage";
   public static final String UP_LIFESTEAL = "lifesteal";
   public static final String UP_TANK = "tank";
   public static final String UP_GROWTH = "growth";
   public static final String UP_REPLANT = "replant";
   public static final String UP_HARVEST = "harvest";
   public static final String UP_COMMISSION = "commission";
   public static final String UP_BARTER = "barter";
   public static final String UP_SILVER = "silver";
   public static final String UP_PROSPECTOR = "prospector";
   public static final String UP_EXCAVATOR = "excavator";
   public static final String UP_CAVE_SIGHT = "cavesight";
   public static final String UP_EXECUTE = "execute";
   public static final String UP_BLOODTHIRST = "bloodthirst";
   public static final String UP_ABSORB = "absorb";
   public static final String UP_BLOODIRON = "bloodiron";
   public static final String UP_REAP = "reap";
   public static final String UP_SEED_BANK = "seedbank";
   public static final String UP_VIGOR = "vigor";
   public static final String UP_SALESMAN = "salesman";
   public static final String UP_TIP = "tip";
   public static final String UP_SCHMOOZER = "schmoozer";
   public static final String UP_TIMBER = "timber";
   public static final String UP_LUMBERJACK = "lumberjack";
   public static final String UP_BARK = "bark";
   public static final String UP_LEAFY = "leafy";
   public static final String UP_ARBOREAL = "arboreal";
   public static final String UP_GROVE = "grove";
   public static final String UP_ARCANE = "arcane";
   public static final String UP_INSIGHT = "insight";
   public static final String UP_SOULBIND = "soulbind";
   public static final String UP_RUNED = "runed";
   public static final String UP_RECLAIM = "reclaim";
   public static final String UP_OWL = "owl";
   public static final String UP_LUCKY_ENCHANT = "lucky_enchant";
   public static final String UP_TOME_SAVER = "tome_saver";
   public static final String UP_ENCHANTER_AURA = "enchanter_aura";
   // --- the second wave: one new upgrade per original tree, plus the whole fishing tree ---------
   /** Mining: any ore can also shed a gem. */
   public static final String UP_GEMSMITH = "gemsmith";
   /** Combat: a kill rattles everything standing near it. */
   public static final String UP_WARCRY = "warcry";
   /** Farming: crops come up with bonemeal. */
   public static final String UP_COMPOST = "compost";
   /** Foraging: a felled log leaves charcoal behind. */
   public static final String UP_CHARCOAL = "charcoal";
   /** Trading: a second, smaller discount on top of Silver Tongue. */
   public static final String UP_REPUTATION = "reputation";
   /** Enchanting: the table's work mends the item it is working on. */
   public static final String UP_MENDER = "mender";
   public static final String UP_ANGLER = "angler";
   public static final String UP_BIG_CATCH = "big_catch";
   public static final String UP_TREASURE = "treasure";
   public static final String UP_BAIT_SAVER = "bait_saver";
   public static final String UP_DEEP_SEA = "deep_sea";
   private static final Upgrade[] MINING_UPGRADES = new Upgrade[]{
      new Upgrade("haste", "Mining Speed", 1, 2, "§7Haste I/II while holding a pickaxe."),
      new Upgrade("durability", "Durability", 1, 3, "§725/50/75% chance your pickaxe loses no durability."),
      new Upgrade("fortune", "Fortune", 1, 3, "§7+10/20/30% chance of bonus ore drops."),
      new Upgrade("autosmelt", "Auto-Smelt", 3, 1, "§7The ore you mine drops smelted - the raw drop becomes the ingot with a forge flash."),
      new Upgrade("prospector", "Prospector", 1, 3, "§7+15/30/45% Mining XP."),
      new Upgrade("excavator", "Excavator", 1, 3, "§75/10/15% chance to double a block's full drop."),
      new Upgrade("cavesight", "Cave Sight", 2, 1, "§7Night Vision while mining underground."),
      new Upgrade("gemsmith", "Gemsmith", 2, 3, "§78/16/24% chance any ore also sheds a gem - diamond, emerald, amethyst or lapis.")
   };
   private static final Upgrade[] COMBAT_UPGRADES = new Upgrade[]{
      new Upgrade("damage", "Damage", 1, 3, "§7+10/20/30% damage against monsters (never players)."),
      new Upgrade("lifesteal", "Lifesteal", 1, 3, "§7Heal 1/2/3 HP whenever you kill a mob."),
      new Upgrade("tank", "Toughness", 1, 3, "§7Take 8/16/24% less damage from monsters."),
      new Upgrade("execute", "Executioner", 1, 3, "§7+10/20/30% damage to mobs below 25% HP."),
      new Upgrade("bloodthirst", "Bloodthirst", 1, 1, "§7Speed II for 5s after every kill."),
      new Upgrade("absorb", "Second Wind", 2, 2, "§720/40% chance of Absorption on a kill."),
      new Upgrade(
         "bloodiron",
         "Bloodiron Spirit",
         3,
         2,
         "§7L1: each kill forges your worn armor back together (10 dur/piece). §8L2: also renews your held tool & offhand (8 dur each)."
      ),
      new Upgrade("warcry", "War Cry", 2, 2, "§7Every kill rattles monsters within 6 blocks - Weakness for 3/6s.")
   };
   private static final Upgrade[] FARMING_UPGRADES = new Upgrade[]{
      new Upgrade("growth", "Growth", 1, 3, "§7+15/30/45% chance of double crop drops."),
      new Upgrade("replant", "Auto-Replant", 3, 1, "§7Harvested crops instantly regrow."),
      new Upgrade("harvest", "Green Thumb", 2, 2, "§7+1/+2 bonus crop from every harvest."),
      new Upgrade("reap", "Sow & Reap", 1, 3, "§7+15/30/45% Farming XP."),
      new Upgrade("seedbank", "Seed Bank", 1, 3, "§720/40/60% chance of bonus seeds on harvest."),
      new Upgrade("vigor", "Farmer's Vigor", 2, 1, "§7Speed while holding a hoe."),
      new Upgrade("compost", "Compost", 1, 3, "§720/40/60% chance a harvested crop also drops bonemeal.")
   };
   private static final Upgrade[] FORAGING_UPGRADES = new Upgrade[]{
      new Upgrade("lumberjack", "Lumberjack", 1, 3, "§7+15/30/45% chance of a bonus log drop."),
      new Upgrade("timber", "Timber", 1, 3, "§7+15/30/45% Foraging XP."),
      new Upgrade("bark", "Barksplitter", 1, 1, "§7Haste while holding an axe."),
      new Upgrade("leafy", "Leaf Harvester", 2, 2, "§7+1/+2 bonus saplings from every leaf."),
      new Upgrade("arboreal", "Arboreal", 1, 1, "§7Night Vision while chopping in the dark."),
      new Upgrade("grove", "Grovekeeper", 3, 1, "§7Auto-replant saplings from your hand as you chop."),
      new Upgrade("charcoal", "Charcoal Burner", 1, 3, "§715/30/45% chance a felled log also leaves charcoal.")
   };
   /**
    * Fishing: the tree that only fires when the bobber goes under.
    *
    * <p>Every upgrade here reads from one event, and the tree is built around the shape of a
    * fishing session rather than around a damage number: the cast, the wait, the catch, and the
    * rod that has to survive all three. Bait Saver and Deep Sea are the quality-of-life pair, Big
    * Catch and Treasure Hunter are the pair that makes the wait worth it, and Angler's Instinct is
    * the one that makes the tree finish at all.
    */
   private static final Upgrade[] FISHING_UPGRADES = new Upgrade[]{
      new Upgrade("angler", "Angler's Instinct", 1, 3, "§7+15/30/45% Fishing XP."),
      new Upgrade("big_catch", "Big Catch", 2, 3, "§715/30/45% chance the catch lands twice."),
      new Upgrade("treasure", "Treasure Hunter", 2, 3, "§78/16/24% chance of a treasure alongside the catch."),
      new Upgrade("bait_saver", "Bait Saver", 2, 3, "§725/50/75% chance the rod takes no wear from a catch."),
      new Upgrade("deep_sea", "Deep Sea", 3, 1, "§7Water Breathing and Dolphin's Grace while your line is in the water.")
   };
   private static final Upgrade[] ENCHANTING_UPGRADES = new Upgrade[]{
      new Upgrade("arcane", "Arcane Focus", 1, 3, "§7+15/30/45% Enchanting XP."),
      new Upgrade("insight", "Insight", 1, 3, "§7+10/20/30% chance for a bonus level on forge enchants."),
      new Upgrade("soulbind", "Soulbind", 2, 1, "§7Forge & enchanting table never consume your XP."),
      new Upgrade("runed", "Runed Hands", 1, 2, "§7+5/10% chance your forge craft costs no material."),
      new Upgrade("reclaim", "Reclaim", 1, 1, "§7Recycling a legendary returns one extra material."),
      new Upgrade("owl", "Night Owl", 1, 1, "§7+1 bonus level from the enchanting table at night."),
      new Upgrade("lucky_enchant", "Lucky Enchant", 1, 3, "§710/20/30% chance for a bonus random enchantment when enchanting."),
      new Upgrade("tome_saver", "Tome Saver", 1, 3, "§715/30/45% chance for enchantment tomes to not be consumed in forge."),
      new Upgrade("enchanter_aura", "Enchanter's Aura", 1, 3, "§7+10/20/30% better enchantment odds from the table."),
      new Upgrade("mender", "Mender", 2, 3, "§7Every visit to the enchanting table mends 25/50/75 durability on the item.")
   };
   private static final Upgrade[] TRADING_UPGRADES = new Upgrade[]{
      new Upgrade("commission", "Commission", 1, 3, "§7+$15/30/45 cash bonus per completed trade."),
      new Upgrade("barter", "Barter", 1, 3, "§7+40/80/120 trading XP per completed trade."),
      new Upgrade("silver", "Silver Tongue", 3, 1, "§715% discount on every shop purchase."),
      new Upgrade("salesman", "Salesman", 1, 3, "§7+10/20/30% cash when selling items."),
      new Upgrade("tip", "Tip Jar", 1, 3, "§7+$25/50/75 bonus per completed trade."),
      new Upgrade("schmoozer", "Schmoozer", 1, 3, "§7+15/30/45% Trading XP."),
      new Upgrade("reputation", "Reputation", 2, 3, "§7A further 5/10/15% off every shop price, stacked on Silver Tongue.")
   };
   private static final Map<UUID, Map<String, Long>> xp = new HashMap<>();
   private static final Map<UUID, Map<String, Integer>> points = new HashMap<>();
   private static final Map<UUID, Map<String, Map<String, Integer>>> spent = new HashMap<>();
   private static Path dataFile;
   private static boolean dirty = false;
   private static final Set<UUID> refundedPlayers = new HashSet<>();
   private static final Map<String, Integer> OLD_COSTS;
   private static final RandomHolder RANDOM = new RandomHolder();

   /**
    * XP needed for the next level: a real curve, not a line.
    *
    * <p>{@code 25 + 45*level} was flat in shape - level 10 asked for less than seven times what
    * level 1 did, so a tree opened fast, stalled, and then ended without ever feeling like it
    * was climbing. The quadratic term is what gives the tail its weight: the first levels are
    * still quick enough to see, and the last ones are the ones a player decides to finish.
    * The input is clamped, so the function stays total for callers that probe past the cap.
    */
   private static long xpForNext(int level) {
      int l = Math.max(1, Math.min(MAX_LEVEL, level));
      return 25L + 45L * l + 9L * l * l;
   }

   public static boolean hadRefund(UUID uuid) {
      return refundedPlayers.remove(uuid);
   }

   private SkillManager() {
   }

   public static void load(MinecraftServer server) {
      xp.clear();
      points.clear();
      spent.clear();
      dataFile = EconomyManager.getDataDir(server).resolve("skills.json");
      JsonObject root = JsonUtil.readOrCreate(dataFile, new JsonObject());
      if (root.has("players") && root.get("players").isJsonObject()) {
         for (Entry<String, JsonElement> e : root.getAsJsonObject("players").entrySet()) {
            try {
               UUID uuid = UUID.fromString(e.getKey());
               JsonObject obj = e.getValue().getAsJsonObject();
               Map<String, Long> skills = new HashMap<>();

               for (String s : SKILLS) {
                  skills.put(s, JsonUtil.jsonLong(obj, s, 0L));
               }

               xp.put(uuid, skills);
               Map<String, Integer> pt = new HashMap<>();
               if (obj.has("points") && obj.get("points").isJsonObject()) {
                  JsonObject po = obj.getAsJsonObject("points");

                  for (String s : SKILLS) {
                     pt.put(s, (int)JsonUtil.jsonLong(po, s, 0L));
                  }
               } else {
                  int legacy = (int)JsonUtil.jsonLong(obj, "points", 0L);

                  for (String s : SKILLS) {
                     pt.put(s, legacy / SKILLS.length);
                  }

                  for (int i = 0; i < legacy % SKILLS.length; i++) {
                     pt.merge(SKILLS[i], 1, Integer::sum);
                  }
               }

               points.put(uuid, pt);
               Map<String, Map<String, Integer>> p = new HashMap<>();
               if (obj.has("upgrades") && obj.get("upgrades").isJsonObject()) {
                  for (Entry<String, JsonElement> se : obj.getAsJsonObject("upgrades").entrySet()) {
                     Map<String, Integer> ups = new HashMap<>();
                     if (se.getValue().isJsonObject()) {
                        for (Entry<String, JsonElement> ue : se.getValue().getAsJsonObject().entrySet()) {
                           if (ue.getValue().isJsonPrimitive() && ue.getValue().getAsJsonPrimitive().isNumber()) {
                              ups.put(ue.getKey(), ue.getValue().getAsInt());
                           }
                        }
                     }

                     p.put(se.getKey(), ups);
                  }
               }

               spent.put(uuid, p);
            } catch (Exception var14) {
            }
         }
      }

      refundCostDecreases();
      dirty = false;
   }

   private static void refundCostDecreases() {
      int totalRefunds = 0;

      for (Entry<UUID, Map<String, Map<String, Integer>>> pe : spent.entrySet()) {
         UUID uuid = pe.getKey();
         Map<String, Integer> pts = points.computeIfAbsent(uuid, k -> new HashMap<>());

         for (Entry<String, Map<String, Integer>> se : pe.getValue().entrySet()) {
            String skill = se.getKey();
            Map<String, Integer> ups = se.getValue();
            Upgrade[] tree = upgradesOf(skill);
            Map<String, Upgrade> byId = new HashMap<>();

            for (Upgrade u : tree) {
               byId.put(u.id(), u);
            }

            for (Entry<String, Integer> ue : ups.entrySet()) {
               String upId = ue.getKey();
               int level = ue.getValue();
               if (level > 0) {
                  Upgrade u = byId.get(upId);
                  if (u != null) {
                     Integer oldCost = OLD_COSTS.get(upId);
                     if (oldCost != null && oldCost > u.cost()) {
                        int refund = (oldCost - u.cost()) * level;
                        if (refund > 0) {
                           pts.merge(skill, refund, Integer::sum);
                           totalRefunds += refund;
                           refundedPlayers.add(uuid);
                        }
                     }
                  }
               }
            }
         }
      }

      if (totalRefunds > 0) {
         dirty = true;
      }

      OLD_COSTS.clear();
   }

   public static void save(MinecraftServer server) {
      if (dataFile == null) {
         dataFile = EconomyManager.getDataDir(server).resolve("skills.json");
      }

      JsonObject root = new JsonObject();
      JsonObject players = new JsonObject();

      for (Entry<UUID, Map<String, Long>> e : xp.entrySet()) {
         JsonObject obj = new JsonObject();

         for (String s : SKILLS) {
            obj.addProperty(s, e.getValue().getOrDefault(s, 0L));
         }

         JsonObject pt = new JsonObject();
         Map<String, Integer> pm = points.getOrDefault(e.getKey(), Map.of());

         for (String s : SKILLS) {
            pt.addProperty(s, pm.getOrDefault(s, 0));
         }

         obj.add("points", pt);
         JsonObject ups = new JsonObject();
         Map<String, Map<String, Integer>> p = spent.getOrDefault(e.getKey(), Map.of());

         for (String s : SKILLS) {
            JsonObject so = new JsonObject();
            Map<String, Integer> us = p.getOrDefault(s, Map.of());

            for (Upgrade u : upgradesOf(s)) {
               so.addProperty(u.id(), us.getOrDefault(u.id(), 0));
            }

            ups.add(s, so);
         }

         obj.add("upgrades", ups);
         players.add(e.getKey().toString(), obj);
      }

      root.add("players", players);
      JsonUtil.write(dataFile, root);
      dirty = false;
   }

   public static boolean isDirty() {
      return dirty;
   }

   /**
    * The XP one level costs, exposed so a screen can state the real number instead of keeping a
    * copy of it - the copy is the thing that goes stale the next time the curve moves.
    */
   public static long xpForLevel(int level) {
      return xpForNext(level);
   }

   public static long xpIn(UUID uuid, String skill) {
      return xp.getOrDefault(uuid, Map.of()).getOrDefault(skill, 0L);
   }

   /**
    * The level a skill's XP has actually bought, capped at {@link #MAX_LEVEL}.
    *
    * <p>The cap is load-bearing. Every consumer of a skill level reads the sum of the six
    * ({@code JobManager.MAX_TOTAL_LEVEL} is {@code 6 * MAX_LEVEL}) and the job board prices
    * itself on that sum, so a skill that kept counting past ten silently shifted every number
    * downstream that assumed sixty was the ceiling - and, worse, kept handing out a point per
    * level out of a tree that was already finished.
    */
   private static int rawLevel(UUID uuid, String skill) {
      long remaining = xpIn(uuid, skill);
      int level = 0;

      while (level < MAX_LEVEL) {
         long need = xpForNext(level + 1);
         if (remaining < need) {
            return level;
         }

         remaining -= need;
         level++;
      }

      return MAX_LEVEL;
   }

   public static boolean allMaxed(UUID uuid) {
      for (String s : SKILLS) {
         if (!isMaxed(uuid, s)) {
            return false;
         }
      }

      return true;
   }

   /** Whether this one skill has reached the cap - a finished tree, not a finished player. */
   public static boolean isMaxed(UUID uuid, String skill) {
      return rawLevel(uuid, skill) >= MAX_LEVEL;
   }

   /** How many of the six trees are finished, for the skill screen's own summary line. */
   public static int maxedTrees(UUID uuid) {
      int done = 0;

      for (String s : SKILLS) {
         if (isMaxed(uuid, s)) {
            done++;
         }
      }

      return done;
   }

   /** The sum of the six levels: the number the job board and the scoreboard both price on. */
   public static int totalLevel(UUID uuid) {
      int total = 0;

      for (String s : SKILLS) {
         total += rawLevel(uuid, s);
      }

      return total;
   }

   public static int level(UUID uuid, String skill) {
      return rawLevel(uuid, skill);
   }

   /** XP banked towards this skill's next level; zero once the tree itself is finished. */
   public static long xpIntoLevel(UUID uuid, String skill) {
      if (isMaxed(uuid, skill)) {
         return 0L;
      }

      long remaining = xpIn(uuid, skill);
      int level = 0;

      while (level < MAX_LEVEL) {
         long need = xpForNext(level + 1);
         if (remaining < need) {
            return remaining;
         }

         remaining -= need;
         level++;
      }

      return 0L;
   }

   /** XP needed for this skill's next level; zero once the tree is finished. */
   public static long xpNeeded(UUID uuid, String skill) {
      return isMaxed(uuid, skill) ? 0L : xpForNext(level(uuid, skill) + 1);
   }

   /** How far into the current level this skill is, 0-100. 100 when the tree is finished. */
   public static int xpPercent(UUID uuid, String skill) {
      if (isMaxed(uuid, skill)) {
         return 100;
      }

      long into = xpIntoLevel(uuid, skill);
      long needed = xpNeeded(uuid, skill);
      if (needed <= 0L) {
         return 100;
      }

      return (int)Math.max(0L, Math.min(100L, into * 100L / needed));
   }

   public static int points(UUID uuid, String skill) {
      return points.getOrDefault(uuid, Map.of()).getOrDefault(skill, 0);
   }

   public static int points(UUID uuid) {
      int total = 0;

      for (String s : SKILLS) {
         total += points(uuid, s);
      }

      return total;
   }

   public static int unspentPoints(UUID uuid, String skill) {
      int used = 0;

      for (Upgrade u : upgradesOf(skill)) {
         used += upgradeLevel(uuid, skill, u.id()) * u.cost();
      }

      return Math.max(0, points(uuid, skill) - used);
   }

   public static int unspentPoints(UUID uuid) {
      int total = 0;

      for (String s : SKILLS) {
         total += unspentPoints(uuid, s);
      }

      return total;
   }

   public static Upgrade[] upgradesOf(String skill) {
      if (skill == null) {
         return new Upgrade[0];
      }

      return switch (skill) {
         case "mining" -> MINING_UPGRADES;
         case "combat" -> COMBAT_UPGRADES;
         case "farming" -> FARMING_UPGRADES;
         case "trading" -> TRADING_UPGRADES;
         case "foraging" -> FORAGING_UPGRADES;
         case "enchanting" -> ENCHANTING_UPGRADES;
         case "fishing" -> FISHING_UPGRADES;
         default -> new Upgrade[0];
      };
   }

   public static int upgradeLevel(UUID uuid, String skill, String upgradeId) {
      return spent.getOrDefault(uuid, Map.of()).getOrDefault(skill, Map.of()).getOrDefault(upgradeId, 0);
   }

   public static boolean hasUpgrade(UUID uuid, String skill, String upgradeId) {
      return upgradeLevel(uuid, skill, upgradeId) > 0;
   }

   public static String spend(ServerPlayer player, String skill, String upgradeId) {
      Upgrade target = findUpgrade(skill, upgradeId);
      String err = spendCore(player.getUUID(), skill, upgradeId);
      if (err != null) {
         return err;
      }
      SoundUtil.play(player, ModSounds.SKILL_UP);
      Chat.raw(
         player,
         "&b&l"
            + target.name()
            + "&r &7upgraded to level "
            + upgradeLevel(player.getUUID(), skill, upgradeId)
            + ". &8("
            + unspentPoints(player.getUUID(), skill)
            + " "
            + displayName(skill)
            + " points left)"
      );
      return null;
   }

   /**
    * The buying itself, with no player behind it: validate, charge the tree's pool, mark dirty.
    * Split out so the rules can be driven by a check without a sound and a chat line in the way.
    */
   private static String spendCore(UUID uuid, String skill, String upgradeId) {
      Upgrade target = findUpgrade(skill, upgradeId);
      if (target == null) {
         return "That upgrade doesn't exist.";
      }

      if (upgradeLevel(uuid, skill, upgradeId) >= target.maxLevel()) {
         return "That upgrade is already maxed.";
      }

      int unspent = unspentPoints(uuid, skill);
      if (unspent < target.cost()) {
         return "You need " + target.cost() + " " + displayName(skill) + " skill point(s) - you have " + unspent + ".";
      }

      spent.computeIfAbsent(uuid, k -> new HashMap<>()).computeIfAbsent(skill, k -> new HashMap<>()).merge(upgradeId, 1, Integer::sum);
      dirty = true;
      return null;
   }

   /**
    * Take a tree's investment back out of it.
    *
    * <p>This is the whole of a refund's book-keeping, and it is on purpose that it does not touch
    * {@code points}: an unspent point is what a player has earned minus what their trees hold, so
    * clearing the investment is what returns the points. Handing them back a second time is the
    * bug this method's single job prevents.
    */
   private static int freeTree(UUID uuid, String skill) {
      int freed = spentPoints(uuid, skill);
      Map<String, Map<String, Integer>> p = spent.get(uuid);
      if (p != null) {
         p.remove(skill);
      }
      if (freed > 0) {
         dirty = true;
      }
      return freed;
   }

   /** Test hook: buy one upgrade level for an id with no player behind it. Error message, or null. */
   public static String spendForTest(UUID uuid, String skill, String upgradeId) {
      return spendCore(uuid, skill, upgradeId);
   }

   /** Test hook: refund one tree for an id, with no player and no fee. The points freed. */
   public static int refundSkillForTest(UUID uuid, String skill) {
      return freeTree(uuid, skill);
   }

   /** Test hook: bank XP against an id with no player, running the real level-up reward path. */
   public static void addXpForTest(UUID uuid, String skill, long amount) {
      if (amount <= 0L || !skillKnown(skill) || isMaxed(uuid, skill)) {
         return;
      }
      int before = level(uuid, skill);
      xp.computeIfAbsent(uuid, k -> new HashMap<>()).merge(skill, amount, Long::sum);
      dirty = true;
      int after = level(uuid, skill);

      for (int lvl = before + 1; lvl <= after; lvl++) {
         grantLevelReward(uuid, skill, lvl);
      }
   }

   /** Test hook: drop an id's whole skill record, so a check leaves nothing behind. */
   public static void forgetForTest(UUID uuid) {
      xp.remove(uuid);
      points.remove(uuid);
      spent.remove(uuid);
   }

   public static void addXp(ServerPlayer player, String skill, long amount) {
      if (amount > 0L && ModConfig.is("skills") && skillKnown(skill)) {
         UUID uuid = player.getUUID();
         // A finished tree banks nothing. Before the cap this was the doorway to unlimited
         // points: with five skills at 10 and one at 9, every further ore still levelled
         // Mining past 10 and paid a point for it, so the fastest way to finish the sixth
         // tree was to stop feeding it. The capstone is the reward now.
         if (isMaxed(uuid, skill)) {
            return;
         }
         // Fast-start bonus fades out: x1.5 on day one, x1.0 by level 4. It used
         // to be a permanent x1.5, which made mid/late levels far too fast.
         float earlyBoost = 1.0F + Math.max(0.0F, 0.5F - 0.15F * level(uuid, skill));
         amount = Math.round((float)amount * earlyBoost);
         amount = Math.round((float)amount * GuildManager.xpMultiplier(player.getUUID()));
         amount = Math.round((float)amount * (float) ServerDisasterManager.xpMultiplier());
         int before = level(uuid, skill);
         xp.computeIfAbsent(uuid, k -> new HashMap<>()).merge(skill, amount, Long::sum);
         dirty = true;
         int after = level(uuid, skill);

         for (int lvl = before + 1; lvl <= after; lvl++) {
            onLevelUp(player, skill, lvl);
         }
      }
   }

   private static boolean skillKnown(String skill) {
      for (String s : SKILLS) {
         if (s.equals(skill)) {
            return true;
         }
      }

      return false;
   }

   private static void onLevelUp(ServerPlayer player, String skill, int newLevel) {
      SoundUtil.play(player, ModSounds.SKILL_UP);
      grantLevelReward(player.getUUID(), skill, newLevel);
      Chat.raw(player, "&a&l" + displayName(skill) + " reached level " + newLevel + "!");
      Chat.msg(player, "&aYou earned &b&l1 " + displayName(skill) + " skill point&a! Open &f/skills&a to spend it on " + displayName(skill) + " upgrades.");
      if (newLevel >= MAX_LEVEL) {
         Chat.raw(
            player,
            "&6&l" + displayName(skill) + " complete!&r &e+" + CAPSTONE_BONUS + " capstone point" + (CAPSTONE_BONUS == 1 ? "" : "s")
               + "&7 - this tree is finished."
         );
         Advancements.grant(player, "skill_master");
         Advancements.grant(player, "skill_master_" + skill);
         if (allMaxed(player.getUUID())) {
            Advancements.grant(player, "master_of_all");
         }
      }
   }

   /**
    * What one level is worth, with no player and no ceremony attached.
    *
    * <p>The capstone is paid here rather than in the branch that announces it, because the reward
    * and the message are two different things: a check that drove the reward path by hand once had
    * to re-implement this arithmetic, and a re-implementation is where a bonus goes missing.
    */
   private static void grantLevelReward(UUID uuid, String skill, int newLevel) {
      points.computeIfAbsent(uuid, k -> new HashMap<>()).merge(skill, 1, Integer::sum);
      if (newLevel >= MAX_LEVEL) {
         // The capstone: the level that ends the tree pays for it. One point said "another level",
         // and a tree has none of those left.
         points.get(uuid).merge(skill, CAPSTONE_BONUS, Integer::sum);
      }
      dirty = true;
   }

   public static void onBlockBroken(ServerPlayer player, BlockState state, ServerLevel level) {
      // Legacy call path: no block position was supplied, so fall back to the
      // player's own position for the natural-block gate below.
      onBlockBroken(player, state, level, player.blockPosition());
   }

   public static void onBlockBroken(ServerPlayer player, BlockState state, ServerLevel level, BlockPos brokenPos) {
      if (ModConfig.is("skills")) {
         String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
         if (isCrop(blockId)) {
            addXp(player, "farming", Math.round(8.0F * farmingXpMultiplier(player.getUUID())));
            farmingPerkDrop(player, state, level, brokenPos);
         } else if (isAxe(player.getMainHandItem()) && isForageable(blockId)) {
            long fXp = foragingXp(blockId);
            if (fXp > 0L) {
               addXp(player, "foraging", Math.round((float)fXp * foragingXpMultiplier(player.getUUID())));
               foragingPerkDrop(player, state, level, blockId);
            }
         } else if (isPickaxe(player.getMainHandItem())) {
            long mineXp = miningXp(blockId);
            if (mineXp > 0L) {
               addXp(player, "mining", Math.round((float)mineXp * miningXpMultiplier(player.getUUID())));
               // Inside a site the ore pays the run in coin and never in items, so this tree's
               // perks do not run at all there: every one of them hands over a stack, and a stack is
               // exactly what an expedition ore may not become. See OreDropGuardMixin, which cancels
               // the drop itself - this is the half of the same rule that spawns its own.
               boolean paysInCoin = com.fortuneandfavors.economy.ExpeditionManager.isInExpedition(player.getUUID());
               if (!paysInCoin) {
                  miningPerkDrop(player, state, level);
               }

               // Excavator / auto-smelt only pay out on NATURALLY GENERATED
               // blocks: a placed block can never be farmed for free perk procs.
               //
               // A creative player is the other half of the same rule. They break
               // world generation for free and in unlimited quantity, so a perk
               // that pays drops would be an item faucet with no cost at all -
               // which is why mining pity and the mining zones already refuse
               // them. The natural check is a <i>read</i>; the record is retired
               // once, after every consumer of this break has had its answer.
               boolean natural = NaturalBlocks.isNatural(level, brokenPos, state) && !player.getAbilities().instabuild;

               if (natural && !paysInCoin && hasUpgrade(player.getUUID(), "mining", "autosmelt")) {
                  autoSmeltDrop(state, level, player, brokenPos);
               }

               float excavator = natural && !paysInCoin ? excavatorChance(player.getUUID()) : 0.0F;
               if (excavator > 0.0F && RANDOM.nextFloat() < excavator) {
                  excavatorDrop(state, level, player);
               }
            }
         }
      }
   }

   public static void addEnchantingXp(ServerPlayer player, long base) {
      if (base > 0L) {
         long gain = Math.round(base * (1.0 + 0.25 * upgradeLevel(player.getUUID(), "enchanting", "arcane")));
         addXp(player, "enchanting", gain);
      }
   }

   public static void onKill(ServerPlayer player, EntityType<?> type) {
      if (isCombatWeapon(player.getMainHandItem())) {
         String id = BuiltInRegistries.ENTITY_TYPE.getKey(type).toString();
         addXp(player, "combat", combatXp(id));
         UUID uuid = player.getUUID();
         int heal = lifestealHeal(uuid);
         if (heal > 0) {
            player.heal(heal);
         }

         int speed = bloodthirstSeconds(uuid);
         if (speed > 0) {
            player.addEffect(new MobEffectInstance(MobEffects.SPEED, speed * 20, 1, false, false));
         }

         float absorb = absorbChance(uuid);
         if (absorb > 0.0F && RANDOM.nextFloat() < absorb) {
            int amp = upgradeLevel(uuid, "combat", "absorb") - 1;
            player.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 200, Math.max(0, amp), false, false));
         }

         int warcry = warcrySeconds(uuid);
         if (warcry > 0) {
            // War Cry: monsters only, never pets, villagers or other players - a perk that debuffs
            // whoever happens to be standing next to a kill is a perk that griefs.
            for (net.minecraft.world.entity.monster.Monster mob : player
               .level()
               .getEntitiesOfClass(net.minecraft.world.entity.monster.Monster.class, player.getBoundingBox().inflate(6.0))) {
               if (mob.isAlive()) {
                  mob.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, warcry * 20, 0, false, true));
               }
            }
         }

         int bloodiron = upgradeLevel(uuid, "combat", "bloodiron");
         if (bloodiron > 0) {
            for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
               ItemStack armor = player.getItemBySlot(slot);
               if (!armor.isEmpty() && armor.isDamageableItem() && !armor.has(DataComponents.UNBREAKABLE)) {
                  int dmg = armor.getDamageValue();
                  if (dmg > 0) {
                     armor.setDamageValue(Math.max(0, dmg - 10));
                  }
               }
            }

            if (bloodiron >= 2) {
               int repairBudget = 8;
               ItemStack mostDamaged = ItemStack.EMPTY;
               int maxDamage = 0;

               for (int i = 0; i < 9; i++) {
                  ItemStack stack = player.getInventory().getItem(i);
                  if (!stack.isEmpty() && stack.isDamageableItem() && !stack.has(DataComponents.UNBREAKABLE) && !ModItems.isCannonRod(stack)) {
                     int dmg = stack.getDamageValue();
                     if (dmg > maxDamage) {
                        maxDamage = dmg;
                        mostDamaged = stack;
                     }
                  }
               }

               if (!mostDamaged.isEmpty() && maxDamage > 0) {
                  int repair = Math.min(repairBudget, maxDamage);
                  mostDamaged.setDamageValue(maxDamage - repair);
                  repairBudget -= repair;
               }

               if (repairBudget > 0) {
                  ItemStack offhand = player.getItemBySlot(EquipmentSlot.OFFHAND);
                  if (!offhand.isEmpty() && offhand.isDamageableItem() && !offhand.has(DataComponents.UNBREAKABLE) && !ModItems.isCannonRod(offhand)) {
                     int dmg = offhand.getDamageValue();
                     if (dmg > 0) {
                        offhand.setDamageValue(Math.max(0, dmg - repairBudget));
                     }
                  }
               }
            }
         }
      }
   }

   public static void onTrade(ServerPlayer player) {
      if (ModConfig.is("skills")) {
         UUID uuid = player.getUUID();
         long baseXp = 60L + upgradeLevel(uuid, "trading", "barter") * 40L;
         long xpGain = Math.round((float)baseXp * tradingXpMultiplier(uuid));
         addXp(player, "trading", xpGain);
         long bonus = upgradeLevel(uuid, "trading", "commission") * 15L;
         if (bonus > 0L) {
            EconomyManager.addCash(uuid, bonus);
            Chat.raw(player, "&7Commission: &a+" + bonus + "&7 from your Commission upgrade.");
         }

         long tip = tipBonus(uuid);
         if (tip > 0L) {
            EconomyManager.addCash(uuid, tip);
            Chat.raw(player, "&7Tip Jar: &a+" + tip + "&7 from your Tip Jar upgrade.");
         }
      }
   }

   public static long refundCost(UUID uuid) {
      int used = 0;

      for (String s : SKILLS) {
         for (Upgrade u : upgradesOf(s)) {
            used += upgradeLevel(uuid, s, u.id()) * u.cost();
         }
      }

      return used * 1000L;
   }

   public static String refundAll(ServerPlayer player) {
      long cost = refundCost(player.getUUID());
      if (cost <= 0L) {
         return "You don't have any upgrades to refund.";
      }

      if (!EconomyManager.takeCash(player.getUUID(), cost)) {
         return "You need " + Chat.moneyStr(cost) + " to respec.";
      }

      // Same single job as a one-tree refund, six times over: clearing the investment is what
      // gives the points back.
      for (String s : SKILLS) {
         freeTree(player.getUUID(), s);
      }

      dirty = true;
      Chat.raw(
         player,
         "&b&lRespec!&r &7All upgrades refunded for "
            + Chat.moneyStr(cost)
            + "&7. Each tree's points went back to that tree - you have &b"
            + unspentPoints(player.getUUID())
            + "&7 points to spend."
      );
      return null;
   }

   /**
    * What this tree has actually invested, in points.
    *
    * <p>Cost-weighted, not a count of levels. A level of Cost 3 bought three points' worth of
    * tree, and a refund that counted it as one gave back a third of what was spent - which is
    * why the two refund paths disagreed: {@link #refundCost} has always priced the whole tree
    * by cost, and this - the per-tree figure the screen and the refund both read - did not.
    */
   public static int spentPoints(UUID uuid, String skill) {
      Map<String, Map<String, Integer>> p = spent.get(uuid);
      if (p == null) {
         return 0;
      }

      Map<String, Integer> tree = p.get(skill);
      if (tree == null) {
         return 0;
      }

      int total = 0;

      for (Entry<String, Integer> e : tree.entrySet()) {
         int levels = e.getValue();
         if (levels <= 0) {
            continue;
         }
         Upgrade u = findUpgrade(skill, e.getKey());
         total += levels * (u != null ? u.cost() : 1);
      }

      return total;
   }

   /** One upgrade by id, or null when the stored id no longer names anything. */
   private static Upgrade findUpgrade(String skill, String upgradeId) {
      for (Upgrade u : upgradesOf(skill)) {
         if (u.id().equals(upgradeId)) {
            return u;
         }
      }
      return null;
   }

   /**
    * Refund one tree for cash.
    *
    * <p>The points come back by <b>dropping what was spent</b>, which is the only thing that
    * returns them: an unspent point is the gap between what a player has earned and what their
    * trees have invested, so clearing the investment frees every point in it. The old code
    * cleared the tree <i>and</i> then handed the levels back as points again - a refund that
    * paid twice, so the fastest way to fund a tree was to refund a different one.
    */
   public static String refundSkill(ServerPlayer player, String skill) {
      int freed = spentPoints(player.getUUID(), skill);
      if (freed <= 0) {
         return "No upgrades to refund in " + displayName(skill) + ".";
      }

      long cost = freed * 500L;
      if (!EconomyManager.takeCash(player.getUUID(), cost)) {
         return "You need " + Chat.moneyStr(cost) + " to refund " + displayName(skill) + ".";
      }

      freeTree(player.getUUID(), skill);
      Chat.raw(
         player,
         "&e&lRefund!&r &7All "
            + displayName(skill)
            + " upgrades refunded for "
            + Chat.moneyStr(cost)
            + "&7. "
            + freed
            + " point"
            + (freed == 1 ? "" : "s")
            + " back in the "
            + displayName(skill)
            + " pool."
      );
      return null;
   }

   public static float combatDamageMultiplier(UUID uuid) {
      return 1.0F + 0.1F * upgradeLevel(uuid, "combat", "damage");
   }

   public static float tankMultiplier(UUID uuid) {
      float reduction = 0.08F * upgradeLevel(uuid, "combat", "tank");
      return Math.max(0.5F, 1.0F - reduction);
   }

   public static float durabilityChance(UUID uuid) {
      return 0.25F * upgradeLevel(uuid, "mining", "durability");
   }

   public static int miningHasteLevel(UUID uuid) {
      return upgradeLevel(uuid, "mining", "haste");
   }

   /**
    * The discount a shop charges this player, from both trading upgrades that give one.
    *
    * <p>Silver Tongue is the big single step; Reputation is the small one that stacks on top of it,
    * so a trader who has finished the tree pays a third less rather than never being able to
    * improve on the one upgrade that happened to have a discount in it. Capped, because two
    * multiplicative sources of a free lunch is how a shop stops being a shop.
    */
   public static float buyDiscount(UUID uuid) {
      float silver = hasUpgrade(uuid, TRADING, UP_SILVER) ? 0.15F : 0.0F;
      float reputation = 0.05F * upgradeLevel(uuid, TRADING, UP_REPUTATION);
      return Math.min(0.35F, silver + reputation);
   }

   public static int lifestealHeal(UUID uuid) {
      return upgradeLevel(uuid, "combat", "lifesteal");
   }

   public static float miningXpMultiplier(UUID uuid) {
      return 1.0F + 0.15F * upgradeLevel(uuid, "mining", "prospector");
   }

   public static float farmingXpMultiplier(UUID uuid) {
      return 1.0F + 0.15F * upgradeLevel(uuid, "farming", "reap");
   }

   public static float tradingXpMultiplier(UUID uuid) {
      return 1.0F + 0.15F * upgradeLevel(uuid, "trading", "schmoozer");
   }

   public static float executeMultiplier(UUID uuid) {
      return 1.0F + 0.1F * upgradeLevel(uuid, "combat", "execute");
   }

   public static float sellMultiplier(UUID uuid) {
      return 1.0F + 0.1F * upgradeLevel(uuid, "trading", "salesman");
   }

   public static String activeBonuses(UUID uuid, String skill) {
      StringBuilder sb = new StringBuilder();
      int level = upgradeLevel(uuid, skill, "haste");
      if (level > 0) {
         sb.append("§aHaste ").append(level).append(" §8| ");
      }

      level = upgradeLevel(uuid, skill, "durability");
      if (level > 0) {
         sb.append("§aDurability +").append(level * 25).append("% §8| ");
      }

      level = upgradeLevel(uuid, skill, "fortune");
      if (level > 0) {
         sb.append("§aFortune +").append(level * 10).append("% §8| ");
      }

      level = upgradeLevel(uuid, skill, "autosmelt");
      if (level > 0) {
         sb.append("§aAuto-Smelt §8| ");
      }

      level = upgradeLevel(uuid, skill, "prospector");
      if (level > 0) {          sb.append("§aMining XP +").append(level * 15).append("% §8| ");
      }

      level = upgradeLevel(uuid, skill, "excavator");
      if (level > 0) {
         sb.append("§aExcavator +").append(level * 5).append("% §8| ");
      }

      level = upgradeLevel(uuid, skill, "cavesight");
      if (level > 0) {
         sb.append("§aCave Sight §8| ");
      }

      level = upgradeLevel(uuid, skill, "damage");
      if (level > 0) {
         sb.append("§aDamage +").append(level * 10).append("% §8| ");
      }

      level = upgradeLevel(uuid, skill, "lifesteal");
      if (level > 0) {
         sb.append("§aLifesteal +").append(level).append("HP §8| ");
      }

      level = upgradeLevel(uuid, skill, "tank");
      if (level > 0) {
         sb.append("§aToughness +").append(level * 8).append("% §8| ");
      }

      level = upgradeLevel(uuid, skill, "execute");
      if (level > 0) {
         sb.append("§aExecute +").append(level * 10).append("% §8| ");
      }

      level = upgradeLevel(uuid, skill, "bloodthirst");
      if (level > 0) {
         sb.append("§aBloodthirst §8| ");
      }

      level = upgradeLevel(uuid, skill, "absorb");
      if (level > 0) {
         sb.append("§aSecond Wind §8| ");
      }

      level = upgradeLevel(uuid, skill, "bloodiron");
      if (level > 0) {
         sb.append("§aBloodiron §8| ");
      }

      level = upgradeLevel(uuid, skill, "growth");
      if (level > 0) {
         sb.append("§aGrowth +").append(level * 15).append("% §8| ");
      }

      level = upgradeLevel(uuid, skill, "replant");
      if (level > 0) {
         sb.append("§aAuto-Replant §8| ");
      }

      level = upgradeLevel(uuid, skill, "harvest");
      if (level > 0) {
         sb.append("§aGreen Thumb +").append(level).append(" §8| ");
      }

      level = upgradeLevel(uuid, skill, "reap");
      if (level > 0) {          sb.append("§aFarming XP +").append(level * 15).append("% §8| ");
      }

      level = upgradeLevel(uuid, skill, "seedbank");
      if (level > 0) {
         sb.append("§aSeed Bank +").append(level * 20).append("% §8| ");
      }

      level = upgradeLevel(uuid, skill, "vigor");
      if (level > 0) {
         sb.append("§aFarmer's Vigor §8| ");
      }

      level = upgradeLevel(uuid, skill, "commission");
      if (level > 0) {
         sb.append("§aCommission +$" + level * 15 + " §8| ");
      }

      level = upgradeLevel(uuid, skill, "barter");
      if (level > 0) {
         sb.append("§aBarter +").append(level * 40).append("XP §8| ");
      }

      level = upgradeLevel(uuid, skill, "silver");
      if (level > 0) {
         sb.append("§aSilver Tongue -15% §8| ");
      }

      level = upgradeLevel(uuid, skill, "salesman");
      if (level > 0) {
         sb.append("§aSell Rate x" + String.format("%.1f", sellMultiplier(uuid)) + " §8| ");
      }

      level = upgradeLevel(uuid, skill, "tip");
      if (level > 0) {
         sb.append("§aTip Jar +$" + level * 25 + " §8| ");
      }

      level = upgradeLevel(uuid, skill, "schmoozer");
      if (level > 0) {          sb.append("§aTrading XP +").append(level * 15).append("% §8| ");
      }

      level = upgradeLevel(uuid, skill, "lumberjack");
      if (level > 0) {
         sb.append("§aLumberjack +").append(level * 15).append("% §8| ");
      }

      level = upgradeLevel(uuid, skill, "timber");
      if (level > 0) {          sb.append("§aForaging XP +").append(level * 15).append("% §8| ");
      }

      level = upgradeLevel(uuid, skill, "bark");
      if (level > 0) {
         sb.append("§aBarksplitter §8| ");
      }

      level = upgradeLevel(uuid, skill, "leafy");
      if (level > 0) {
         sb.append("§aLeaf Harvester +").append(level).append(" §8| ");
      }

      level = upgradeLevel(uuid, skill, "arboreal");
      if (level > 0) {
         sb.append("§aArboreal §8| ");
      }

      level = upgradeLevel(uuid, skill, "grove");
      if (level > 0) {
         sb.append("§aGrovekeeper §8| ");
      }

      level = upgradeLevel(uuid, skill, "arcane");
      if (level > 0) {          sb.append("§aArcane XP +").append(level * 15).append("% §8| ");
      }

      level = upgradeLevel(uuid, skill, "insight");
      if (level > 0) {
         sb.append("§aInsight +").append(level * 10).append("% §8| ");
      }

      level = upgradeLevel(uuid, skill, "soulbind");
      if (level > 0) {
         sb.append("§aSoulbind §8| ");
      }

      level = upgradeLevel(uuid, skill, "runed");
      if (level > 0) {
         sb.append("§aRuned Hands §8| ");
      }

      level = upgradeLevel(uuid, skill, "reclaim");
      if (level > 0) {
         sb.append("§aReclaim §8| ");
      }

      level = upgradeLevel(uuid, skill, "owl");
      if (level > 0) {
         sb.append("§aNight Owl §8| ");
      }

      level = upgradeLevel(uuid, skill, "lucky_enchant");
      if (level > 0) {
         sb.append("§aLucky Enchant +").append(level * 10).append("% §8| ");
      }

      level = upgradeLevel(uuid, skill, "tome_saver");
      if (level > 0) {
         sb.append("§aTome Saver +").append(level * 15).append("% §8| ");
      }

      level = upgradeLevel(uuid, skill, "enchanter_aura");
      if (level > 0) {
         sb.append("§aEnchanter's Aura +").append(level * 10).append("% §8| ");
      }

      level = upgradeLevel(uuid, skill, "gemsmith");
      if (level > 0) {
         sb.append("§aGemsmith +").append(level * 8).append("% §8| ");
      }

      level = upgradeLevel(uuid, skill, "warcry");
      if (level > 0) {
         sb.append("§aWar Cry ").append(level * 3).append("s §8| ");
      }

      level = upgradeLevel(uuid, skill, "compost");
      if (level > 0) {
         sb.append("§aCompost +").append(level * 20).append("% §8| ");
      }

      level = upgradeLevel(uuid, skill, "charcoal");
      if (level > 0) {
         sb.append("§aCharcoal +").append(level * 15).append("% §8| ");
      }

      level = upgradeLevel(uuid, skill, "reputation");
      if (level > 0) {
         sb.append("§aReputation -").append(level * 5).append("% §8| ");
      }

      level = upgradeLevel(uuid, skill, "mender");
      if (level > 0) {
         sb.append("§aMender ").append(level * 25).append(" dur §8| ");
      }

      level = upgradeLevel(uuid, skill, "angler");
      if (level > 0) {
         sb.append("§aFishing XP +").append(level * 15).append("% §8| ");
      }

      level = upgradeLevel(uuid, skill, "big_catch");
      if (level > 0) {
         sb.append("§aBig Catch +").append(level * 15).append("% §8| ");
      }

      level = upgradeLevel(uuid, skill, "treasure");
      if (level > 0) {
         sb.append("§aTreasure +").append(level * 8).append("% §8| ");
      }

      level = upgradeLevel(uuid, skill, "bait_saver");
      if (level > 0) {
         sb.append("§aBait Saver +").append(level * 25).append("% §8| ");
      }

      level = upgradeLevel(uuid, skill, "deep_sea");
      if (level > 0) {
         sb.append("§aDeep Sea §8| ");
      }

      String result = sb.toString();
      if (result.endsWith(" §8| ")) {
         result = result.substring(0, result.length() - 5);
      }

      return result;
   }

   public static float excavatorChance(UUID uuid) {
      return 0.05F * upgradeLevel(uuid, "mining", "excavator");
   }

   public static float luckyEnchantChance(UUID uuid) {
      return 0.1F * upgradeLevel(uuid, "enchanting", "lucky_enchant");
   }

   public static float tomeSaverChance(UUID uuid) {
      return 0.15F * upgradeLevel(uuid, "enchanting", "tome_saver");
   }

   public static float enchanterAuraBonus(UUID uuid) {
      return 0.1F * upgradeLevel(uuid, "enchanting", "enchanter_aura");
   }

   public static float seedBankChance(UUID uuid) {
      return 0.2F * upgradeLevel(uuid, "farming", "seedbank");
   }

   /** Gemsmith: the chance any mined ore also sheds a gem. */
   public static float gemsmithChance(UUID uuid) {
      return 0.08F * upgradeLevel(uuid, MINING, UP_GEMSMITH);
   }

   /** War Cry: how many seconds the monsters around a kill are left weak. */
   public static int warcrySeconds(UUID uuid) {
      return 3 * upgradeLevel(uuid, COMBAT, UP_WARCRY);
   }

   public static float compostChance(UUID uuid) {
      return 0.2F * upgradeLevel(uuid, FARMING, UP_COMPOST);
   }

   public static float charcoalChance(UUID uuid) {
      return 0.15F * upgradeLevel(uuid, FORAGING, UP_CHARCOAL);
   }

   /** Mender: how much durability the enchanting table puts back when it is done. */
   public static float menderAmount(UUID uuid) {
      return 25.0F * upgradeLevel(uuid, ENCHANTING, UP_MENDER);
   }

   public static float fishingXpMultiplier(UUID uuid) {
      return 1.0F + 0.15F * upgradeLevel(uuid, FISHING, UP_ANGLER);
   }

   public static float bigCatchChance(UUID uuid) {
      return 0.15F * upgradeLevel(uuid, FISHING, UP_BIG_CATCH);
   }

   public static float treasureChance(UUID uuid) {
      return 0.08F * upgradeLevel(uuid, FISHING, UP_TREASURE);
   }

   public static float baitSaverChance(UUID uuid) {
      return 0.25F * upgradeLevel(uuid, FISHING, UP_BAIT_SAVER);
   }

   /** Deep Sea: water breathing while the line is out. */
   public static boolean deepSea(UUID uuid) {
      return hasUpgrade(uuid, FISHING, UP_DEEP_SEA);
   }

   /**
    * The fishing tree's single hook: one reel-in, everything the tree pays.
    *
    * <p>Called from the fishing-rod mixin, which means it runs on the server, once per catch, with
    * the stack the loot table produced. That stack is the important part: the tree never rolls its
    * own fish, so a player's luck, rod enchantments and the loot table itself all still decide what
    * a catch is worth - this only ever adds to it, or takes the wear off the rod that made it.
    *
    * <p>The creative guard is on the extras and not on the XP, for the reason the mining perks
    * share: a creative rod is an unlimited item faucet, and a perk that copies its catches would be
    * one too. Experience is not an item, so it is paid either way.
    */
   public static void onFishCaught(ServerPlayer player, ItemStack caught) {
      if (player == null || !ModConfig.is("skills")) {
         return;
      }
      UUID uuid = player.getUUID();
      addXp(player, FISHING, Math.round(12.0F * fishingXpMultiplier(uuid)));
      if (player.getAbilities().instabuild || caught == null || caught.isEmpty()) {
         return;
      }
      ServerLevel level = player.level() instanceof ServerLevel sl ? sl : null;
      if (level == null) {
         return;
      }
      float big = bigCatchChance(uuid);
      if (big > 0.0F && RANDOM.nextFloat() < big) {
         player.drop(caught.copy(), false);
         com.fortuneandfavors.net.FfVfx.particles(level, 
            ParticleTypes.BUBBLE_POP, player.getX(), player.getY() + 1.2, player.getZ(), 10, 0.4, 0.4, 0.4, 0.05
         );
      }
      float treasure = treasureChance(uuid);
      if (treasure > 0.0F && RANDOM.nextFloat() < treasure) {
         player.drop(treasureRoll(), false);
         com.fortuneandfavors.net.FfVfx.particles(level, 
            ParticleTypes.END_ROD, player.getX(), player.getY() + 1.2, player.getZ(), 12, 0.4, 0.4, 0.4, 0.04
         );
         Chat.raw(player, "&bTreasure Hunter:&r &7something else came up with it.");
      }
      // The rod is the one tool in the game that wears out by being used correctly, so the saver
      // gives the point back rather than preventing a write: +1 durability, never past pristine.
      float saver = baitSaverChance(uuid);
      if (saver > 0.0F && RANDOM.nextFloat() < saver) {
         ItemStack rod = player.getMainHandItem();
         if (isFishingRod(rod) && rod.isDamageableItem() && rod.getDamageValue() > 0) {
            rod.setDamageValue(Math.max(0, rod.getDamageValue() - 1));
         }
      }
   }

   /** A fishing rod, vanilla or modded - the same suffix rule the tool checks use. */
   public static boolean isFishingRod(ItemStack stack) {
      return stack != null && !stack.isEmpty() && BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().endsWith("fishing_rod");
   }

   /**
    * What Treasure Hunter can pull up. Deliberately not the vanilla treasure table: that table is
    * gated behind open water and a five-minute wait, and this perk sits on top of whatever the
    * player already fished up, so it pays in small, useful, non-scaling things instead of a second
    * lottery. A book, a shell, a gem and a coin.
    */
   private static ItemStack treasureRoll() {
      return switch (RANDOM.nextInt(7)) {
         case 0 -> new ItemStack(Items.NAUTILUS_SHELL);
         case 1 -> new ItemStack(Items.ENCHANTED_BOOK);
         case 2 -> new ItemStack(Items.DIAMOND, 1 + RANDOM.nextInt(2));
         case 3 -> new ItemStack(Items.EMERALD, 2 + RANDOM.nextInt(3));
         case 4 -> new ItemStack(Items.LAPIS_LAZULI, 4 + RANDOM.nextInt(5));
         case 5 -> new ItemStack(Items.GOLD_INGOT, 2 + RANDOM.nextInt(3));
         default -> new ItemStack(Items.HEART_OF_THE_SEA);
      };
   }

   public static float absorbChance(UUID uuid) {
      return 0.2F * upgradeLevel(uuid, "combat", "absorb");
   }

   public static int bloodthirstSeconds(UUID uuid) {
      return hasUpgrade(uuid, "combat", "bloodthirst") ? 5 : 0;
   }

   public static boolean caveSight(UUID uuid) {
      return hasUpgrade(uuid, "mining", "cavesight");
   }

   public static boolean farmerVigor(UUID uuid) {
      return hasUpgrade(uuid, "farming", "vigor");
   }

   public static long tipBonus(UUID uuid) {
      return 25L * upgradeLevel(uuid, "trading", "tip");
   }

   public static boolean isPickaxe(ItemStack stack) {
      if (stack != null && !stack.isEmpty()) {
         String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
         return id.endsWith("_pickaxe");
      } else {
         return false;
      }
   }

   public static boolean isCombatWeapon(ItemStack stack) {
      if (stack != null && !stack.isEmpty()) {
         String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
         return id.endsWith("_sword") || id.endsWith("_axe") || id.equals("minecraft:mace") || id.equals("minecraft:trident");
      } else {
         return false;
      }
   }

   public static boolean isHoe(ItemStack stack) {
      return stack != null && !stack.isEmpty() ? BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().endsWith("_hoe") : false;
   }

   public static boolean isAxe(ItemStack stack) {
      return stack != null && !stack.isEmpty() ? BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().endsWith("_axe") : false;
   }

   public static int foragingHasteLevel(UUID uuid) {
      return upgradeLevel(uuid, "foraging", "bark");
   }

   public static float foragingXpMultiplier(UUID uuid) {
      return 1.0F + 0.15F * upgradeLevel(uuid, "foraging", "timber");
   }

   public static boolean arboreal(UUID uuid) {
      return hasUpgrade(uuid, "foraging", "arboreal");
   }

   private static void miningPerkDrop(ServerPlayer player, BlockState state, ServerLevel level) {
      int lvl = upgradeLevel(player.getUUID(), MINING, UP_FORTUNE);
      if (lvl > 0) {
         float chance = 0.1F * lvl;
         if (!(RANDOM.nextFloat() >= chance)) {
            spawnExtraDrop(state, level, player);
         }
      }

      // Gemsmith: the second tier of the mining tree, and a different kind of reward from Fortune -
      // Fortune repeats what the ore already is, and this pays a gem out of stone, copper and coal
      // as well. That is the upgrade that makes the tree worth finishing rather than worth starting.
      float gems = gemsmithChance(player.getUUID());
      if (gems > 0.0F && RANDOM.nextFloat() < gems) {
         spawnGem(level, player);
      }
   }

   /** Drops one random gem at the player's feet, with the sparkle that says a perk fired. */
   private static void spawnGem(ServerLevel level, ServerPlayer player) {
      try {
         ItemStack gem = switch (RANDOM.nextInt(4)) {
            case 0 -> new ItemStack(Items.DIAMOND);
            case 1 -> new ItemStack(Items.EMERALD);
            case 2 -> new ItemStack(Items.AMETHYST_SHARD, 1 + RANDOM.nextInt(3));
            default -> new ItemStack(Items.LAPIS_LAZULI, 2 + RANDOM.nextInt(4));
         };
         BlockPos pos = player.blockPosition();
         level.addFreshEntity(new ItemEntity(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, gem));
         com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.HAPPY_VILLAGER, pos.getX() + 0.5, pos.getY() + 0.8, pos.getZ() + 0.5, 8, 0.3, 0.3, 0.3, 0.05);
      } catch (Exception var4) {
      }
   }

   private static void farmingPerkDrop(ServerPlayer player, BlockState state, ServerLevel level, BlockPos brokenPos) {
      int growth = upgradeLevel(player.getUUID(), "farming", "growth");
      float chance = 0.15F * growth;
      if (growth > 0 && RANDOM.nextFloat() < chance) {
         spawnExtraDrop(state, level, player);
      }

      int bonus = upgradeLevel(player.getUUID(), "farming", "harvest");

      for (int i = 0; i < bonus; i++) {
         spawnExtraDrop(state, level, player);
      }

      if (hasUpgrade(player.getUUID(), "farming", "replant")) {
         replant(state, level, player, brokenPos);
      }

      float seedChance = seedBankChance(player.getUUID());
      if (seedChance > 0.0F && RANDOM.nextFloat() < seedChance) {
         seedDrop(state, level, player);
      }

      // Compost: the farming tree's cheap first purchase, and the one that pays in the resource
      // every farm is always short of rather than in more of the crop the player already has.
      float compost = compostChance(player.getUUID());
      if (compost > 0.0F && RANDOM.nextFloat() < compost) {
         dropAt(level, player, new ItemStack(Items.BONE_MEAL, 1 + RANDOM.nextInt(2)));
      }
   }

   /** Drops one stack at the player's feet - the shared tail of the small perk drops. */
   private static void dropAt(ServerLevel level, ServerPlayer player, ItemStack stack) {
      try {
         BlockPos pos = player.blockPosition();
         level.addFreshEntity(new ItemEntity(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, stack));
      } catch (Exception var4) {
      }
   }

   /**
    * Auto-Replant: puts a fresh crop back on the spot the harvested one stood on.
    *
    * <p>The position is the one that was <b>broken</b>, handed down from the break event, and not
    * the player's own feet. It used to read {@code player.blockPosition()}, which is the cell a
    * standing body occupies - that cell is never air while somebody is standing there, so the
    * perk's own guard refused on every single harvest and the upgrade did nothing at all. A perk
    * that silently never fires is worse than one that is missing, because the player paid for it.
    *
    * <p>Three rules keep it from being a seed faucet: only a fully grown crop replants (an
    * immature one was not harvested, it was trampled), the ground under it must still be farmland,
    * and the spot itself must be free - so a crop is replaced where it stood and nothing is ever
    * overwritten.
    */
   private static void replant(BlockState state, ServerLevel level, ServerPlayer player, BlockPos brokenPos) {
      try {
         if (!(state.getBlock() instanceof CropBlock crop) || crop.getAge(state) != crop.getMaxAge()) {
            return;
         }

         BlockPos pos = brokenPos == null ? player.blockPosition() : brokenPos;
         if (!level.isLoaded(pos) || !level.getBlockState(pos).isAir()) {
            return;
         }

         if (!level.getBlockState(pos.below()).is(Blocks.FARMLAND)) {
            return;
         }

         level.setBlock(pos, crop.defaultBlockState(), 3);
         com.fortuneandfavors.net.FfVfx.particles(level, 
            ParticleTypes.HAPPY_VILLAGER, pos.getX() + 0.5, pos.getY() + 0.4, pos.getZ() + 0.5, 6, 0.3, 0.2, 0.3, 0.02
         );
      } catch (Exception var6) {
      }
   }

   /**
    * Test seam: the cell a replant would put a crop back on.
    *
    * <p>Exists because the bug this replaced was a wrong position and nothing else - the perk read
    * the player's own feet instead of the block that was broken, and a standing body's own cell is
    * never air, so the guard refused every harvest. A check can drive this without a world.
    */
   public static BlockPos replantSpotForTest(BlockPos brokenPos, BlockPos playerPos) {
      return brokenPos == null ? playerPos : brokenPos;
   }

   private static void spawnExtraDrop(BlockState state, ServerLevel level, ServerPlayer player) {
      try {
         List<ItemStack> drops = Block.getDrops(state, level, player.blockPosition(), null);
         if (drops.isEmpty()) {
            return;
         }

         ItemStack extra = drops.get(RANDOM.nextInt(drops.size())).copy();
         if (extra.isEmpty()) {
            return;
         }

         extra.setCount(1);
         BlockPos pos = player.blockPosition();
         level.addFreshEntity(new ItemEntity(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, extra));
      } catch (Exception var6) {
      }
   }

   private static void excavatorDrop(BlockState state, ServerLevel level, ServerPlayer player) {
      try {
         List<ItemStack> drops = Block.getDrops(state, level, player.blockPosition(), null);
         if (drops.isEmpty()) {
            return;
         }

         BlockPos pos = player.blockPosition();

         for (ItemStack d : drops) {
            if (!d.isEmpty()) {
               level.addFreshEntity(new ItemEntity(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, d.copy()));
            }
         }
      } catch (Exception var7) {
      }
   }

   private static void seedDrop(BlockState state, ServerLevel level, ServerPlayer player) {
      try {
         String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();

         Item seed = switch (blockId) {
            case "minecraft:wheat" -> Items.WHEAT_SEEDS;
            case "minecraft:carrots" -> Items.CARROT;
            case "minecraft:potatoes" -> Items.POTATO;
            case "minecraft:beetroots" -> Items.BEETROOT_SEEDS;
            case "minecraft:nether_wart" -> Items.NETHER_WART;
            case "minecraft:sweet_berry_bush" -> Items.SWEET_BERRIES;
            default -> Items.WHEAT_SEEDS;
         };
         BlockPos pos = player.blockPosition();
         level.addFreshEntity(new ItemEntity(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, new ItemStack(seed, 1)));
      } catch (Exception var7) {
      }
   }

   /**
    * Auto-smelt, as a <b>conversion</b> rather than a duplicate.
    *
    * <p>The perk used to hand a second ingot to the ground beside the raw ore vanilla had already
    * dropped, so a miner walked away holding both halves of the recipe and a bonus nobody could
    * see. What the upgrade actually promises is thinner and better than that: the drop <b>is</b>
    * the smelted thing. So the raw item already lying at the broken block is rewritten in place,
    * and a small forge lights up where it landed - raw ore never reaches a furnace, and nothing is
    * handed straight into the inventory where a player would not see the conversion happen.
    *
    * <p>Silk Touch is the one gap: the block itself drops instead of the raw item, so there is
    * nothing to convert and the smelted item is dropped at the block instead. The same fallback
    * covers a drop that has somehow already been collected - the perk is a promise, and a promise
    * that silently does nothing is worse than a stack the player has to walk over.
    */
   private static void autoSmeltDrop(BlockState state, ServerLevel level, ServerPlayer player, BlockPos brokenPos) {
      try {
         String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();

         Item smelted = switch (blockId) {
            case "minecraft:iron_ore", "minecraft:deepslate_iron_ore" -> Items.IRON_INGOT;
            case "minecraft:gold_ore", "minecraft:deepslate_gold_ore" -> Items.GOLD_INGOT;
            case "minecraft:copper_ore", "minecraft:deepslate_copper_ore" -> Items.COPPER_INGOT;
            case "minecraft:ancient_debris" -> Items.NETHERITE_SCRAP;
            default -> null;
         };
         if (smelted == null) {
            return;
         }
         Item raw = switch (blockId) {
            case "minecraft:iron_ore", "minecraft:deepslate_iron_ore" -> Items.RAW_IRON;
            case "minecraft:gold_ore", "minecraft:deepslate_gold_ore" -> Items.RAW_GOLD;
            case "minecraft:copper_ore", "minecraft:deepslate_copper_ore" -> Items.RAW_COPPER;
            case "minecraft:ancient_debris" -> Items.ANCIENT_DEBRIS;
            default -> null;
         };

         boolean converted = false;
         if (raw != null) {
            net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(brokenPos).inflate(2.5);
            for (ItemEntity drop : level.getEntitiesOfClass(ItemEntity.class, box)) {
               ItemStack stack = drop.getItem();
               if (stack.isEmpty() || stack.getItem() != raw) {
                  continue;
               }
               drop.setItem(new ItemStack(smelted, stack.getCount()));
               forgeBurst(level, drop.getX(), drop.getY() + 0.15, drop.getZ());
               converted = true;
            }
         }
         if (!converted) {
            level.addFreshEntity(
               new ItemEntity(level, brokenPos.getX() + 0.5, brokenPos.getY() + 0.5, brokenPos.getZ() + 0.5, new ItemStack(smelted))
            );
            forgeBurst(level, brokenPos.getX() + 0.5, brokenPos.getY() + 0.6, brokenPos.getZ() + 0.5);
         }
      } catch (Exception var7) {
      }
   }

   /**
    * The little forge a converted drop sits in for a heartbeat.
    *
    * <p>Its whole job is to say <i>this changed</i> at the exact spot it changed, so the miner
    * reads the conversion off the world instead of noticing an ingot in a slot later: heat under
    * it, sparks off it, a fleck of white flash, and one soft crackle.
    */
   private static void forgeBurst(ServerLevel level, double x, double y, double z) {
      com.fortuneandfavors.net.FfVfx.particles(level, net.minecraft.core.particles.ParticleTypes.LAVA, x, y, z, 3, 0.18, 0.1, 0.18, 0.0);
      com.fortuneandfavors.net.FfVfx.particles(level, net.minecraft.core.particles.ParticleTypes.FLAME, x, y + 0.1, z, 6, 0.2, 0.15, 0.2, 0.01);
      com.fortuneandfavors.net.FfVfx.particles(level, net.minecraft.core.particles.ParticleTypes.SMOKE, x, y + 0.15, z, 5, 0.2, 0.15, 0.2, 0.01);
      com.fortuneandfavors.net.FfVfx.particles(level, 
         net.minecraft.core.particles.ColorParticleOption.create(net.minecraft.core.particles.ParticleTypes.FLASH, 0xFFB347),
         x, y + 0.1, z, 1, 0.0, 0.0, 0.0, 0.0
      );
      com.fortuneandfavors.net.FfVfx.particles(level, net.minecraft.core.particles.ParticleTypes.ELECTRIC_SPARK, x, y + 0.2, z, 4, 0.25, 0.2, 0.25, 0.02);
      level.playSound(null, x, y, z, net.minecraft.sounds.SoundEvents.FURNACE_FIRE_CRACKLE, net.minecraft.sounds.SoundSource.BLOCKS, 0.6F, 1.35F);
   }

   private static long miningXp(String blockId) {
      if (blockId.endsWith("_ore")) {
         String name = blockId.substring(blockId.indexOf(58) + 1);
         if (name.startsWith("deepslate_")) {
            name = name.substring("deepslate_".length());
         }
         return switch (name) {
            case "coal_ore" -> 12L;
            case "copper_ore" -> 16L;
            case "iron_ore" -> 20L;
            case "redstone_ore" -> 16L;
            case "gold_ore" -> 24L;
            case "lapis_ore" -> 24L;
            case "diamond_ore" -> 50L;
            case "emerald_ore" -> 50L;
            case "ancient_debris" -> 120L;
            default -> 20L;
         };
      } else {
         return switch (blockId) {
            case "minecraft:stone", "minecraft:deepslate", "minecraft:cobblestone" -> 2L;
            case "minecraft:netherrack", "minecraft:end_stone" -> 2L;
            case "minecraft:obsidian", "minecraft:crying_obsidian" -> 6L;
            case "minecraft:bedrock" -> 0L;
            default -> !blockId.contains("_log") && !blockId.contains("_planks") ? 1L : 3L;
         };
      }
   }

   private static long combatXp(String entityId) {
      return switch (entityId) {
         case "minecraft:zombie", "minecraft:husk", "minecraft:drowned", "minecraft:skeleton", "minecraft:stray", "minecraft:spider", "minecraft:piglin" -> 12L;
         case "minecraft:cave_spider", "minecraft:creeper", "minecraft:slime", "minecraft:magma_cube" -> 16L;
         case "minecraft:phantom", "minecraft:vex", "minecraft:pillager", "minecraft:vindicator" -> 24L;
         case "minecraft:enderman", "minecraft:blaze", "minecraft:witch", "minecraft:shulker", "minecraft:guardian" -> 30L;
         case "minecraft:wither_skeleton", "minecraft:piglin_brute", "minecraft:ravager" -> 40L;
         case "minecraft:evoker", "minecraft:iron_golem" -> 50L;
         case "minecraft:elder_guardian" -> 120L;
         default -> 8L;
      };
   }

   private static boolean isCrop(String blockId) {
      return switch (blockId) {
         case "minecraft:wheat", "minecraft:carrots", "minecraft:potatoes", "minecraft:beetroots", "minecraft:nether_wart", "minecraft:cocoa", "minecraft:sweet_berry_bush", "minecraft:sugar_cane", "minecraft:bamboo", "minecraft:cactus", "minecraft:melon", "minecraft:pumpkin", "minecraft:kelp", "minecraft:kelp_plant" -> true;
         default -> false;
      };
   }

   private static boolean isForageable(String blockId) {
      return blockId.contains("_log")
         || blockId.endsWith("_stem")
         || blockId.endsWith("_hyphae")
         || blockId.endsWith("_leaves")
         || blockId.endsWith("_wood")
         || blockId.equals("minecraft:bamboo")
         || blockId.equals("minecraft:mushroom_stem");
   }

   private static long foragingXp(String blockId) {
      if (blockId.endsWith("_leaves")) {
         return 2L;
      } else {
         return blockId.equals("minecraft:bamboo") ? 1L : 5L;
      }
   }

   private static void foragingPerkDrop(ServerPlayer player, BlockState state, ServerLevel level, String blockId) {
      UUID uuid = player.getUUID();
      int lumberjack = upgradeLevel(uuid, "foraging", "lumberjack");
      if (lumberjack > 0 && !blockId.endsWith("_leaves") && RANDOM.nextFloat() < 0.15F * lumberjack) {
         spawnExtraDrop(state, level, player);
      }

      int leafy = upgradeLevel(uuid, "foraging", "leafy");
      if (blockId.endsWith("_leaves") && leafy > 0) {
         for (int i = 0; i < leafy; i++) {
            spawnExtraDrop(state, level, player);
         }
      }

      // Charcoal Burner: a log that has been felled has already been half-burned, so sometimes it
      // comes back as the fuel it would have become. Only logs, never leaves.
      float charcoal = charcoalChance(uuid);
      if (charcoal > 0.0F && !blockId.endsWith("_leaves") && RANDOM.nextFloat() < charcoal) {
         dropAt(level, player, new ItemStack(Items.CHARCOAL));
      }

      if (hasUpgrade(uuid, "foraging", "grove") && !blockId.endsWith("_leaves")) {
         try {
            ItemStack hand = player.getMainHandItem();
            if (hand.is(Items.WHEAT_SEEDS) || hand.is(Items.BEETROOT_SEEDS) || BuiltInRegistries.ITEM.getKey(hand.getItem()).toString().endsWith("_sapling")) {
               BlockPos pos = player.blockPosition();
               if (state.getBlock() instanceof SaplingBlock sapling) {
                  level.setBlock(pos, sapling.defaultBlockState(), 3);
               }
            }
         } catch (Exception var11) {
         }
      }
   }

   public static String displayName(String skill) {
      return switch (skill) {
         case "mining" -> "Mining";
         case "combat" -> "Combat";
         case "farming" -> "Farming";
         case "trading" -> "Trading";
         case "foraging" -> "Foraging";
         case "enchanting" -> "Enchanting";
         case "fishing" -> "Fishing";
         default -> skill;
      };
   }

   public static Item cardItem(String skill) {
      return switch (skill) {
         case "mining" -> Items.IRON_PICKAXE;
         case "combat" -> Items.IRON_SWORD;
         case "farming" -> Items.IRON_HOE;
         case "trading" -> Items.EMERALD;
         case "foraging" -> Items.IRON_AXE;
         case "enchanting" -> Items.ENCHANTED_BOOK;
         case "fishing" -> Items.FISHING_ROD;
         default -> Items.BOOK;
      };
   }

   /**
    * A filled-pip readout of one upgrade's level, so a tree reads as a ladder at a glance.
    * Kept beside {@link #bar} because both answers are about the same thing - how far along -
    * and a screen that draws one of them by hand is a screen where the two disagree.
    */
   public static String pips(int level, int max) {
      int m = Math.max(1, max);
      int l = Math.max(0, Math.min(m, level));
      StringBuilder sb = new StringBuilder("§a");

      for (int i = 0; i < l; i++) {
         sb.append('●').append(' ');
      }

      sb.append("§8");

      for (int i = l; i < m; i++) {
         sb.append('●').append(' ');
      }

      return sb.toString().trim();
   }

   public static String bar(UUID uuid, String skill) {
      long into = xpIntoLevel(uuid, skill);
      long needed = xpNeeded(uuid, skill);
      int filled = needed <= 0L ? 10 : (int)Math.min(10L, into * 10L / needed);
      StringBuilder sb = new StringBuilder("§a");

      for (int i = 0; i < filled; i++) {
         sb.append('|');
      }

      sb.append("§8");

      for (int i = filled; i < 10; i++) {
         sb.append('|');
      }

      return sb.toString();
   }

   static {
      HashMap<String, Integer> m = new HashMap<>();
      m.put("haste", 2);
      m.put("fortune", 2);
      m.put("autosmelt", 5);
      m.put("excavator", 2);
      m.put("cavesight", 3);
      m.put("lifesteal", 2);
      m.put("tank", 2);
      m.put("execute", 2);
      m.put("bloodthirst", 2);
      m.put("absorb", 3);
      m.put("growth", 2);
      m.put("replant", 5);
      m.put("harvest", 3);
      m.put("seedbank", 2);
      m.put("vigor", 3);
      m.put("lumberjack", 2);
      m.put("bark", 2);
      m.put("leafy", 3);
      m.put("arboreal", 2);
      m.put("grove", 5);
      m.put("insight", 2);
      m.put("soulbind", 3);
      m.put("runed", 2);
      m.put("reclaim", 2);
      m.put("tome_saver", 2);
      m.put("enchanter_aura", 2);
      m.put("barter", 2);
      m.put("silver", 5);
      m.put("salesman", 2);
      OLD_COSTS = m;
   }


    static final class RandomHolder {
       private final Random random = new Random();
    
       private RandomHolder() {
       }
    
       float nextFloat() {
          return this.random.nextFloat();
       }
    
       int nextInt(int bound) {
          return this.random.nextInt(bound);
       }
    }

    public record Upgrade(String id, String name, int cost, int maxLevel, String desc) {
    }
}
