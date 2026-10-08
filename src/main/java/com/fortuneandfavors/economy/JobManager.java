package com.fortuneandfavors.economy;

import com.fortuneandfavors.ModSounds;
import com.fortuneandfavors.economy.JobManager.Job;
import com.fortuneandfavors.economy.JobManager.Template;
import com.fortuneandfavors.util.Chat;
import com.fortuneandfavors.util.JsonUtil;
import com.fortuneandfavors.util.SoundUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.Map.Entry;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.level.block.state.BlockState;

public final class JobManager {
   private static final List<Template> MINE_JOBS = List.of(
      new Template("MINE", "minecraft:stone", 64, 200L),
      new Template("MINE", "minecraft:cobblestone", 64, 175L),
      new Template("MINE", "minecraft:coal_ore", 24, 500L),
      new Template("MINE", "minecraft:iron_ore", 24, 800L),
      new Template("MINE", "minecraft:gold_ore", 12, 750L),
      new Template("MINE", "minecraft:redstone_ore", 24, 575L),
      new Template("MINE", "minecraft:lapis_ore", 12, 525L),
      new Template("MINE", "minecraft:diamond_ore", 6, 1500L),
      new Template("MINE", "minecraft:emerald_ore", 6, 1500L),
      new Template("MINE", "minecraft:ancient_debris", 2, 2500L),
      new Template("MINE", "minecraft:deepslate", 64, 225L),
      new Template("MINE", "minecraft:gravel", 32, 150L),
      new Template("MINE", "minecraft:copper_ore", 24, 450L),
      new Template("MINE", "minecraft:nether_quartz_ore", 24, 550L),
      new Template("MINE", "minecraft:obsidian", 8, 1200L),
      new Template("MINE", "minecraft:netherrack", 64, 125L),
      new Template("MINE", "minecraft:sandstone", 48, 175L),
      new Template("MINE", "minecraft:dripstone_block", 32, 225L),
      new Template("MINE", "minecraft:tuff", 48, 200L),
      new Template("MINE", "minecraft:basalt", 32, 200L),
      new Template("MINE", "minecraft:granite", 48, 175L),
      new Template("MINE", "minecraft:diorite", 48, 175L),
      new Template("MINE", "minecraft:andesite", 48, 175L),
      new Template("MINE", "minecraft:calcite", 24, 350L),
      new Template("MINE", "minecraft:amethyst_block", 12, 600L),
      new Template("MINE", "minecraft:glowstone", 16, 650L),
      new Template("MINE", "minecraft:prismarine", 32, 450L),
      new Template("MINE", "minecraft:end_stone", 48, 225L),
      new Template("MINE", "minecraft:soul_sand", 32, 200L),
      new Template("MINE", "minecraft:blackstone", 48, 200L)
   );
   private static final List<Template> KILL_JOBS = List.of(
      new Template("KILL", "minecraft:zombie", 15, 575L),
      new Template("KILL", "minecraft:skeleton", 10, 525L),
      new Template("KILL", "minecraft:creeper", 12, 700L),
      new Template("KILL", "minecraft:spider", 20, 625L),
      new Template("KILL", "minecraft:enderman", 5, 1000L),
      new Template("KILL", "minecraft:cow", 10, 325L),
      new Template("KILL", "minecraft:sheep", 10, 300L),
      new Template("KILL", "minecraft:slime", 20, 750L),
      new Template("KILL", "minecraft:witch", 5, 800L),
      new Template("KILL", "minecraft:blaze", 10, 900L),
      new Template("KILL", "minecraft:piglin", 12, 650L),
      new Template("KILL", "minecraft:drowned", 15, 600L),
      new Template("KILL", "minecraft:husk", 10, 575L),
      new Template("KILL", "minecraft:phantom", 6, 800L),
      new Template("KILL", "minecraft:stray", 10, 600L),
      new Template("KILL", "minecraft:cave_spider", 15, 550L),
      new Template("KILL", "minecraft:magma_cube", 12, 700L),
      new Template("KILL", "minecraft:ghast", 4, 1100L),
      new Template("KILL", "minecraft:guardian", 8, 950L),
      new Template("KILL", "minecraft:zombified_piglin", 15, 625L),
      new Template("KILL", "minecraft:pillager", 12, 750L),
      new Template("KILL", "minecraft:vindicator", 6, 875L),
      new Template("KILL", "minecraft:ravager", 2, 2000L),
      new Template("KILL", "minecraft:hoglin", 8, 925L),
      new Template("KILL", "minecraft:zoglin", 8, 975L),
      new Template("KILL", "minecraft:shulker", 5, 950L),
      new Template("KILL", "minecraft:silverfish", 20, 350L),
      new Template("KILL", "minecraft:endermite", 10, 600L),
      new Template("KILL", "minecraft:vex", 8, 825L),
      new Template("KILL", "minecraft:evoker", 3, 1300L),
      new Template("KILL", "minecraft:elder_guardian", 1, 3000L),
      new Template("KILL", "minecraft:iron_golem", 3, 1400L),
      new Template("KILL", "minecraft:wolf", 8, 450L),
      new Template("KILL", "minecraft:cat", 8, 300L)
   );
   private static final List<Template> CHOP_JOBS = List.of(
      new Template("CHOP", "minecraft:oak_log", 48, 275L),
      new Template("CHOP", "minecraft:birch_log", 48, 275L),
      new Template("CHOP", "minecraft:spruce_log", 40, 300L),
      new Template("CHOP", "minecraft:jungle_log", 40, 325L),
      new Template("CHOP", "minecraft:acacia_log", 40, 325L),
      new Template("CHOP", "minecraft:dark_oak_log", 40, 350L),
      new Template("CHOP", "minecraft:mangrove_log", 40, 300L),
      new Template("CHOP", "minecraft:cherry_log", 40, 325L),
      new Template("CHOP", "minecraft:crimson_stem", 32, 375L),
      new Template("CHOP", "minecraft:warped_stem", 32, 375L),
      new Template("CHOP", "minecraft:oak_wood", 24, 300L),
      new Template("CHOP", "minecraft:cherry_wood", 24, 350L)
   );
   private static final List<Template> FARM_JOBS = List.of(
      new Template("FARM", "minecraft:wheat", 64, 300L),
      new Template("FARM", "minecraft:carrots", 48, 350L),
      new Template("FARM", "minecraft:potatoes", 48, 350L),
      new Template("FARM", "minecraft:beetroots", 48, 325L),
      new Template("FARM", "minecraft:melon", 24, 450L),
      new Template("FARM", "minecraft:pumpkin", 24, 425L),
      new Template("FARM", "minecraft:sugar_cane", 48, 300L),
      new Template("FARM", "minecraft:cactus", 32, 325L),
      new Template("FARM", "minecraft:cocoa", 24, 375L),
      new Template("FARM", "minecraft:nether_wart", 48, 350L),
      new Template("FARM", "minecraft:sweet_berry_bush", 32, 425L),
      new Template("FARM", "minecraft:glow_lichen", 32, 275L),
      new Template("FARM", "minecraft:bamboo", 32, 300L),
      new Template("FARM", "minecraft:kelp", 48, 275L),
      new Template("FARM", "minecraft:sea_pickle", 24, 350L),
      new Template("FARM", "minecraft:big_dripleaf", 16, 450L)
   );
   private static final Template TRADE_JOB = new Template("TRADE", "trade", 1, 500L);
   private static final Template TRADE_JOB_3 = new Template("TRADE", "trade", 3, 1300L);
   private static final Map<UUID, List<Job>> data = new HashMap<>();
   private static final Map<UUID, Long> periods = new HashMap<>();
   private static Path dataFile;
   private static boolean dirty = false;

   private JobManager() {
   }

   public static void load(MinecraftServer server) {
      data.clear();
      periods.clear();
      completed.clear();
      dataFile = EconomyManager.getDataDir(server).resolve("jobs.json");
      JsonObject root = JsonUtil.readOrCreate(dataFile, new JsonObject());
      if (root.has("players") && root.get("players").isJsonObject()) {
         for (Entry<String, JsonElement> e : root.getAsJsonObject("players").entrySet()) {
            try {
               UUID uuid = UUID.fromString(e.getKey());
               JsonObject obj = e.getValue().getAsJsonObject();
               long period = JsonUtil.jsonLong(obj, "period", -1L);
               if (period < 0L) {
                  period = JsonUtil.jsonLong(obj, "day", 0L);
               }

               periods.put(uuid, period);
               int done = (int)JsonUtil.jsonLong(obj, "finished", 0L);
               if (done > 0) {
                  completed.put(uuid, done);
               }

               List<Job> jobs = new ArrayList<>();
               if (obj.has("jobs") && obj.get("jobs").isJsonArray()) {
                  for (JsonElement je : obj.getAsJsonArray("jobs")) {
                     JsonObject jo = je.getAsJsonObject();
                     Job j = new Job();
                     j.task = JsonUtil.jsonString(jo, "task", "MINE");
                     j.target = JsonUtil.jsonString(jo, "target", "minecraft:stone");
                     j.goal = (int)JsonUtil.jsonLong(jo, "goal", 1L);
                     j.progress = (int)JsonUtil.jsonLong(jo, "progress", 0L);
                     j.reward = JsonUtil.jsonLong(jo, "reward", 100L);
                     j.claimed = JsonUtil.jsonLong(jo, "claimed", 0L) == 1L;
                     jobs.add(j);
                  }
               }

               if (!jobs.isEmpty()) {
                  data.put(uuid, jobs);
               }
            } catch (Exception var13) {
            }
         }
      }

      dirty = false;
   }

   public static void save(MinecraftServer server) {
      if (dataFile == null) {
         dataFile = EconomyManager.getDataDir(server).resolve("jobs.json");
      }

      JsonObject root = new JsonObject();
      JsonObject players = new JsonObject();

      for (Entry<UUID, List<Job>> e : data.entrySet()) {
         JsonObject obj = new JsonObject();
         obj.addProperty("period", periods.getOrDefault(e.getKey(), period()));
         obj.addProperty("finished", completed.getOrDefault(e.getKey(), 0));
         JsonArray arr = new JsonArray();

         for (Job j : e.getValue()) {
            JsonObject jo = new JsonObject();
            jo.addProperty("task", j.task);
            jo.addProperty("target", j.target);
            jo.addProperty("goal", j.goal);
            jo.addProperty("progress", j.progress);
            jo.addProperty("reward", j.reward);
            jo.addProperty("claimed", j.claimed ? 1 : 0);
            arr.add(jo);
         }

         obj.add("jobs", arr);
         players.add(e.getKey().toString(), obj);
      }

      root.add("players", players);
      JsonUtil.write(dataFile, root);
      dirty = false;
   }

   private static long period() {
      return Instant.now().getEpochSecond() / 3600L;
   }

   public static List<Job> jobs(UUID uuid) {
      if (periods.getOrDefault(uuid, -1L) != period() || !data.containsKey(uuid)) {
         data.put(uuid, generate(uuid));
         periods.put(uuid, period());
         dirty = true;
      }

      return data.get(uuid);
   }

   private static List<Job> generate(UUID who) {
      Random random = new Random();
      List<Job> out = new ArrayList<>();
      for (int slot = 0; slot < SLOTS; slot++) {
         out.add(rollSlot(who, slot, random));
      }
      return out;
   }

   /**
    * One slot's job, drawn from the pool that slot has always drawn from.
    *
    * <p>The five slots are five <i>kinds</i> of work - a mining slot, a hunting slot, and so on -
    * and that has not changed. What changed is that a slot is rolled on its own now, because a
    * claimed job is replaced immediately (see {@link #claim}) and the replacement has to be a job
    * of the same sort, or the board slowly turns into five mining jobs.
    */
   private static Job rollSlot(UUID who, int slot, Random random) {
      return switch (slot) {
         case 0 -> pick(MINE_JOBS, random, who);
         case 1 -> pick(KILL_JOBS, random, who);
         case 2 -> random.nextInt(3) == 0 ? fromTemplate(TRADE_JOB, who)
            : (random.nextBoolean() ? pick(CHOP_JOBS, random, who) : pick(FARM_JOBS, random, who));
         case 3 -> random.nextInt(4) == 0 ? fromTemplate(TRADE_JOB_3, who)
            : (random.nextBoolean() ? pick(MINE_JOBS, random, who) : pick(KILL_JOBS, random, who));
         default -> random.nextBoolean() ? pick(CHOP_JOBS, random, who) : pick(FARM_JOBS, random, who);
      };
   }

   /** How many jobs the board carries. */
   public static final int SLOTS = 5;

   // ------------------------------------------------------------------ the worker's own standing
   //
   // The board scaled with a player's skills, which meant the board scaled with everything except
   // doing jobs. An errand paid the same whether it was the first or the five hundredth, so the
   // only progression the job board offered was the rest of the server's: skills levelled by mining
   // and fighting, and the board simply read the number afterwards. A worker now has a standing of
   // their own - how many jobs they have actually finished - and it pays, permanently.

   /** Jobs finished, the count the rank is read off. Kept per player and persisted. */
   private static final Map<UUID, Integer> completed = new HashMap<>();
   /**
    * How many finished jobs each rank costs.
    *
    * <p>Six ranks and a ladder that starts close and then stretches: the first promotion is three
    * jobs, so a new player sees the number move inside one evening, and the last is a hundred and
    * twenty, so the top of the board is a season's work rather than a fortnight's.
    */
   public static final int[] RANK_AT = {3, 12, 30, 60, 110, 200};

   /** How much a finished job is worth before anything else - see {@link #PAY_BONUS}. */
   public static final double PAY_BONUS = 6.0;

   /** The highest rank there is. */
   public static int maxRank() {
      return RANK_AT.length;
   }

   /** A worker's rank, from the number of jobs they have finished. */
   public static int rankFor(int finished) {
      int rank = 0;
      while (rank < RANK_AT.length && finished >= RANK_AT[rank]) {
         rank++;
      }
      return rank;
   }

   /** This worker's rank right now. */
   public static int rank(UUID who) {
      return who == null ? 0 : rankFor(completed.getOrDefault(who, 0));
   }

   /** Jobs this worker has finished, ever. */
   public static int finished(UUID who) {
      return who == null ? 0 : completed.getOrDefault(who, 0);
   }

   /**
    * The pay multiplier a rank buys: 1.0 for a new hand, 2.5 at the top.
    *
    * <p>Deliberately a live multiplier on every future job rather than a one-off bonus, because the
    * thing that was missing was a reason to keep working: a rank that paid once would be a bonus, and
    * a rank that pays every time is a standing.
    */
   public static double rankScale(int rank) {
      return 1.0 + 0.3 * Math.max(0, Math.min(RANK_AT.length, rank));
   }

   /** What a rank is called on the board. */
   public static String rankName(int rank) {
      return switch (Math.max(0, Math.min(RANK_AT.length, rank))) {
         case 0 -> "Hand";
         case 1 -> "Working Hand";
         case 2 -> "Journeyman";
         case 3 -> "Foreman";
         case 4 -> "Master";
         default -> "Guildsman";
      };
   }

   /** How many more finished jobs the next rank wants, or -1 at the top. */
   public static int jobsToNextRank(UUID who) {
      int rank = rank(who);
      if (rank >= RANK_AT.length) {
         return -1;
      }
      return Math.max(0, RANK_AT[rank] - finished(who));
   }

   /** Test seam: put a worker at a given standing without running that many errands. */
   public static void setFinishedForTest(UUID who, int count) {
      if (count <= 0) {
         completed.remove(who);
      } else {
         completed.put(who, count);
      }
   }

   /**
    * How much work a job asks for, and what it pays, at a given total skill level.
    *
    * <p>The board used to be the same five errands for everyone: a veteran and a first-hour
    * player were told to mine the same 64 stone for the same 200 dollars, which means the board
    * is a beginner's board forever. Both numbers scale with the player's own standing now - the
    * goal so the work is actually work for somebody who has levelled, and the pay so doing it is
    * worth the walk - and the pay scales <i>twice as fast</i> as the goal on purpose: a job cannot
    * be levelled into worthlessness.
    *
    * <p>The level is the sum of the six skills (0-60), which is the number a player already sees
    * on their own skill board, rather than a fourth hidden progression.
    */
   public static int levelOf(UUID who) {
      int total = 0;
      for (String skill : SkillManager.SKILLS) {
         total += SkillManager.level(who, skill);
      }
      return Math.max(0, Math.min(MAX_TOTAL_LEVEL, total));
   }

   /** The most a player's total skill level can be - six skills, ten levels each. */
   public static final int MAX_TOTAL_LEVEL = SkillManager.SKILLS.length * SkillManager.MAX_LEVEL;

   /** The goal multiplier for a player at this level: 1.0 fresh, 1.5 with every skill maxed. */
   public static double goalScale(int level) {
      return 1.0 + 0.5 * clampLevel(level) / MAX_TOTAL_LEVEL;
   }

   /** The pay multiplier: 1.0 fresh, 2.0 with every skill maxed - twice the goal's climb. */
   public static double payScale(int level) {
      return 1.0 + 1.0 * clampLevel(level) / MAX_TOTAL_LEVEL;
   }

   private static int clampLevel(int level) {
      return Math.max(0, Math.min(MAX_TOTAL_LEVEL, level));
   }

   /**
    * Test-only: rolls one real board slot for a player with a seeded die.
    *
    * <p>Seeded so two players draw the <i>same</i> job: the level scaling is the kind of rule that
    * is easy to write and easy to lose - a {@code fromTemplate(t)} call left somewhere still
    * compiles and silently stops scaling - and the way to catch that is to hand the same roll to a
    * fresh player and a maxed one and require the second job to ask for more and pay more. Without
    * the seed the two rolls are different pool entries and the comparison proves nothing.
    */
   public static Job rollForTest(UUID who, int slot, long seed) {
      return rollSlot(who, slot, new Random(seed));
   }

   /** Test-only: forget a throwaway probe's board so it is not written to jobs.json. */
   public static void forgetForTest(UUID who) {
      if (data.remove(who) != null) {
         dirty = true;
      }
      periods.remove(who);
      completed.remove(who);
   }

   private static Job pick(List<Template> pool, Random random, UUID who) {
      return fromTemplate(pool.get(random.nextInt(pool.size())), who);
   }

   private static Job fromTemplate(Template t, UUID who) {
      Job j = new Job();
      j.task = t.task();
      j.target = t.target();
      int level = levelOf(who);
      j.goal = Math.max(1, (int)Math.round(t.goal() * goalScale(level)));
      // Pay climbs with the worker's skills, with the worker's rank, and with a flat bonus on top of
      // the template. The flat part is the answer to "jobs are not worth doing": the templates were
      // written as pocket money when the board was the only income in the game, and a run of five of
      // them has to be worth an hour of the player's evening rather than a rounding error against
      // what the same hour of expeditions pays.
      j.reward = Math.max(1L, Math.round(t.reward() * payScale(level) * rankScale(rank(who)) * PAY_BONUS));
      return j;
   }

   public static void onBlockBroken(ServerPlayer player, BlockState state) {
      ItemStack held = player.getMainHandItem();
      String task;
      if (SkillManager.isPickaxe(held)) {
         task = "MINE";
      } else if (SkillManager.isAxe(held)) {
         task = "CHOP";
      } else if (SkillManager.isHoe(held)) {
         task = "FARM";
      } else {
         return;
      }
      // Match against the block's registry id so crops (which have no item) track correctly.
      String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
      boolean completed = false;
      for (Job j : jobs(player.getUUID())) {
         if (!j.claimed && j.task.equals(task) && matchesItem(j.target, blockId) && j.progress < j.goal) {
            j.progress++;
            dirty = true;
            if (j.progress >= j.goal) {
               completed = true;
            }
         }
      }
      if (completed) {
         notifyComplete(player);
      }
   }

   private static boolean matchesItem(String target, String brokenId) {
      if (target.equals(brokenId)) {
         return true;
      } else if (target.startsWith("minecraft:") && target.endsWith("_ore")) {
         String name = target.substring("minecraft:".length());
         return brokenId.equals("minecraft:deepslate_" + name);
      } else if (target.endsWith("_log")) {
         return brokenId.equals(target.replace("_log", "_wood"));
      } else {
         return false;
      }
   }

   public static void onKill(ServerPlayer player, Entity killed) {
      if (!SkillManager.isCombatWeapon(player.getMainHandItem())) {
         return;
      }
      EntityType<?> type = killed.getType();
      String id = BuiltInRegistries.ENTITY_TYPE.getKey(type).toString();
      boolean completed = false;
      for (Job j : jobs(player.getUUID())) {
         if (!j.claimed && "KILL".equals(j.task) && j.target.equals(id) && j.progress < j.goal) {
            j.progress++;
            dirty = true;
            if (j.progress >= j.goal) {
               completed = true;
            }
         }
      }
      if (completed) {
         notifyComplete(player);
      }
   }

   public static void onTrade(ServerPlayer player) {
      boolean completed = false;
      for (Job j : jobs(player.getUUID())) {
         if (!j.claimed && "TRADE".equals(j.task) && j.progress < j.goal) {
            j.progress++;
            dirty = true;
            if (j.progress >= j.goal) {
               completed = true;
            }
         }
      }
      if (completed) {
         notifyComplete(player);
      }
   }

   private static void notifyComplete(ServerPlayer player) {
      player.sendSystemMessage(Component.literal("§a§lJob complete!§r §7Open §f/jobs§7 to claim your reward."), true);
   }

   /**
    * Claims a finished job for a player, with everything a finished job is worth.
    *
    * <p>Three things above the cash, and they are what make the board part of the rest of the game
    * rather than a side errand. A job pays <b>tokens</b>, which is the currency the vaults, cosmetics
    * and the pit take. It advances any <b>dynamic contract</b> of the same shape by a quarter of the
    * job's own size, so a contract on the board and a job on the board are two ways of pushing the
    * same bar instead of two unrelated chores. And it counts towards the worker's <b>rank</b>, which
    * is the one progression in the game that only working pays for.
    */
   public static long claim(ServerPlayer player, int index) {
      if (player == null) {
         return 0L;
      }
      UUID uuid = player.getUUID();
      List<Job> board = jobs(uuid);
      if (index < 0 || index >= board.size() || board.get(index).claimed || board.get(index).progress < board.get(index).goal) {
         return claim(uuid, index);
      }
      Job done = board.get(index);
      int before = rank(uuid);
      long reward = claim(uuid, index);
      if (reward <= 0L) {
         return 0L;
      }
      TokenManager.giveGems(player, 1 + rank(uuid) / 2);
      feedContracts(player, done, reward);
      int after = rank(uuid);
      if (after > before) {
         SoundUtil.play(player, ModSounds.TRANSFER);
         Chat.raw(player, "&6&lPROMOTION &r&7- you are a &f" + rankName(after) + "&7 now.");
         Chat.raw(
            player,
            "&7Every job you take from here pays &f" + Math.round((rankScale(after) - 1.0) * 100.0)
               + "%&7 more, and the board pays tokens on every claim."
         );
      }
      return reward;
   }

   /**
    * Pushes a finished job's own work into the dynamic board.
    *
    * <p>By shape rather than by target: a mining job carries mining, a hunt carries kills, and so
    * on. The nudge is a quarter of the job's size, so a job is a real push on a contract and never a
    * free completion - a contract is meant to be a day's work and a job is meant to be an errand.
    */
   private static void feedContracts(ServerPlayer player, Job job, long reward) {
      try {
         String kind = switch (job.task) {
            case "KILL" -> "kill_mobs";
            case "TRADE" -> "sell_earn";
            default -> "mine_block";
         };
         int amount = job.task.equals("TRADE")
            ? Math.max(1, (int)Math.min(64L, reward / 5_000L))
            : Math.max(1, job.goal / 4);
         DynamicContractsManager.onProgress(player, kind, amount);
      } catch (Throwable ignored) {
      }
   }

   /**
    * Claims a finished job and immediately rolls a fresh one into the slot it leaves.
    *
    * <p>The board is still an hourly board - a job a player never finishes is still replaced when
    * the hour turns - but a job that <i>is</i> finished does not wait for that hour to be replaced.
    * It used to: a player who cleared all five jobs in ten minutes spent the next fifty looking at
    * five struck-through cards, which reads as a board that has stopped working rather than as one
    * that resets on the hour. The replacement is rolled the moment the reward is paid, so the board
    * is always a board of jobs that can still be done.
    *
    * @return the cash paid, or 0 when there was nothing to claim in that slot
    */
   public static long claim(UUID uuid, int index) {
      List<Job> jobs = jobs(uuid);
      if (index < 0 || index >= jobs.size()) {
         return 0L;
      }
      Job j = jobs.get(index);
      if (j.claimed || j.progress < j.goal) {
         return 0L;
      }
      long reward = j.reward;
      EconomyManager.addCash(uuid, reward);
      completed.merge(uuid, 1, Integer::sum);
      // Rolled in place, into the same slot: the board is five kinds of work and stays five kinds
      // of work, so the replacement is drawn from the pool this slot has always drawn from.
      jobs.set(index, rollSlot(uuid, index, new Random()));
      dirty = true;
      return reward;
   }

   public static String describe(Job j) {
      return switch (j.task) {
         case "KILL" -> "Kill " + j.goal + "x " + entityName(j.target);
         case "TRADE" -> "Complete " + (j.goal > 1 ? j.goal + " trades" : "a trade") + " with another player";
         case "CHOP" -> "Chop " + j.goal + "x " + itemName(j.target);
         case "FARM" -> "Harvest " + j.goal + "x " + itemName(j.target);
         default -> "Mine " + j.goal + "x " + itemName(j.target);
      };
   }

   public static ItemStack cardItem(Job j) {
      return switch (j.task) {
         case "KILL" -> {
            EntityType<?> type = (EntityType<?>)BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.tryParse(j.target));
            Item egg = null;
            if (type != null) {
               Optional<Holder<Item>> opt = SpawnEggItem.byId(type);
               if (opt.isPresent()) {
                  egg = (Item)opt.get().value();
               }
            }

            yield new ItemStack(egg != null ? egg : Items.ZOMBIE_SPAWN_EGG);
         }
         case "TRADE" -> new ItemStack(Items.EMERALD);
         case "CHOP" -> new ItemStack(Items.OAK_LOG);
         case "FARM" -> new ItemStack(Items.WHEAT);
         default -> {
            Item item = (Item)BuiltInRegistries.ITEM.getValue(Identifier.tryParse(j.target));
            yield new ItemStack(item != Items.AIR ? item : Items.STONE);
         }
      };
   }

   private static String itemName(String id) {
      Item item = (Item)BuiltInRegistries.ITEM.getValue(Identifier.tryParse(id));
      if (item != Items.AIR) {
         String name = new ItemStack(item).getHoverName().getString();
         return name.endsWith(" Ore") ? name.substring(0, name.length() - 4) : name;
      } else {
         return id.substring(id.lastIndexOf(58) + 1);
      }
   }

   private static String entityName(String id) {
      EntityType<?> type = (EntityType<?>)BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.tryParse(id));
      if (type == null) {
         return id.substring(id.lastIndexOf(58) + 1);
      }

      String name = type.getDescription().getString();
      return !name.endsWith("s") && !name.endsWith("x") && !name.endsWith("h") ? name + "s" : name;
   }


    static public final class Job {
       public String task = "MINE";
       public String target = "minecraft:stone";
       public int goal = 1;
       public int progress = 0;
       public long reward = 100L;
       public boolean claimed = false;
    }

    record Template(String task, String target, int goal, long reward) {
    }
}
