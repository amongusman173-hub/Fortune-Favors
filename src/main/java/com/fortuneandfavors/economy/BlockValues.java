package com.fortuneandfavors.economy;

import com.fortuneandfavors.FortuneFavorsMod;
import com.fortuneandfavors.ModItems;
import com.fortuneandfavors.util.JsonUtil;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionContents;

public final class BlockValues {
   private static final Map<String, Long> values = new HashMap<>();
   private static long defaultBlockValue = 3L;
   private static long defaultItemValue = 1L;
   public static final long SPAWNER_VALUE = 20000L;
   public static final long KING_BONE_VALUE = 2500L;
   public static final long SLIME_CORE_VALUE = 5000L;
   public static final long GOLEM_CORE_VALUE = 3000L;
   public static final long TOME_BASE_VALUE = 1500L;
   public static final long SLIME_TROPHY_VALUE = 5000L;
   public static final long GOLEM_TROPHY_VALUE = 5000L;
   public static final long SCULK_ESSENCE_VALUE = 4000L;

   /**
    * Sugar cane, which used to be worth one.
    *
    * <p>One was the same price as a bamboo stalk and a kelp leaf, and a sugar-cane farm is the
    * most productive field in the game - growable on sand beside water, harvestable every few
    * minutes at full height, and worth nothing for it. Twenty-five is deliberately below an iron
    * ingot (40) and above cooked beef (14): worth building the farm, not worth replacing mining.
    */
   public static final long SUGAR_CANE_VALUE = 25L;
   /** What sugar cane was worth before 1.1.6, and the key of the one-time migration below. */
   private static final long SUGAR_CANE_OLD_VALUE = 1L;
   private static final String SUGAR_CANE = "minecraft:sugar_cane";
   private static final String SUGAR_CANE_MIGRATION = "sugar_cane_1_1_6";
   /** Which value migrations have already run, so a deliberate edit is never stomped. */
   private static final String VALUE_MIGRATIONS = "_value_migrations";

   private BlockValues() {
   }

   public static void load(MinecraftServer server) {
      values.clear();
      Path file = EconomyManager.getDataDir(server).resolve("block_values.json");
      JsonObject defaults = new JsonObject();
      defaults.addProperty(
         "_comment",
         "Sell values for every block/item. Edit freely - the shop buy price is derived from the sell value (with the standard markup) unless listed in shop.json. 'default_block_value' applies to any block not listed, 'default_item_value' to any item not listed. Wither Essence ($2,500), Mythical Gelatin ($5,000) and enchantment tomes ($1,500 per level) are special loot with fixed values in code."
      );
      defaults.addProperty("default_block_value", 3);
      defaults.addProperty("default_item_value", 1);
      add(defaults, "minecraft:dirt", 1L);
      add(defaults, "minecraft:grass_block", 2L);
      add(defaults, "minecraft:mud", 1L);
      add(defaults, "minecraft:clay", 4L);
      add(defaults, "minecraft:gravel", 2L);
      add(defaults, "minecraft:sand", 2L);
      add(defaults, "minecraft:sandstone", 4L);
      add(defaults, "minecraft:red_sandstone", 4L);
      add(defaults, "minecraft:calcite", 4L);
      add(defaults, "minecraft:stone", 2L);
      add(defaults, "minecraft:stone_bricks", 4L);
      add(defaults, "minecraft:cobblestone", 2L);
      add(defaults, "minecraft:deepslate", 3L);
      add(defaults, "minecraft:cobbled_deepslate", 3L);
      add(defaults, "minecraft:deepslate_bricks", 5L);
      add(defaults, "minecraft:tuff", 3L);
      add(defaults, "minecraft:andesite", 3L);
      add(defaults, "minecraft:granite", 3L);
      add(defaults, "minecraft:diorite", 3L);
      add(defaults, "minecraft:basalt", 4L);
      add(defaults, "minecraft:blackstone", 5L);
      add(defaults, "minecraft:netherrack", 2L);
      add(defaults, "minecraft:nether_bricks", 6L);
      add(defaults, "minecraft:end_stone", 8L);
      add(defaults, "minecraft:obsidian", 50L);
      add(defaults, "minecraft:crying_obsidian", 60L);
      add(defaults, "minecraft:oak_log", 4L);
      add(defaults, "minecraft:spruce_log", 4L);
      add(defaults, "minecraft:birch_log", 4L);
      add(defaults, "minecraft:jungle_log", 4L);
      add(defaults, "minecraft:acacia_log", 4L);
      add(defaults, "minecraft:dark_oak_log", 5L);
      add(defaults, "minecraft:mangrove_log", 4L);
      add(defaults, "minecraft:cherry_log", 5L);
      add(defaults, "minecraft:bamboo_block", 3L);
      add(defaults, "minecraft:oak_planks", 2L);
      add(defaults, "minecraft:spruce_planks", 2L);
      add(defaults, "minecraft:birch_planks", 2L);
      add(defaults, "minecraft:jungle_planks", 2L);
      add(defaults, "minecraft:acacia_planks", 2L);
      add(defaults, "minecraft:dark_oak_planks", 3L);
      add(defaults, "minecraft:mangrove_planks", 2L);
      add(defaults, "minecraft:cherry_planks", 3L);
      add(defaults, "minecraft:glass", 3L);
      add(defaults, "minecraft:glass_pane", 2L);
      add(defaults, "minecraft:bricks", 8L);
      add(defaults, "minecraft:terracotta", 6L);
      add(defaults, "minecraft:white_terracotta", 6L);
      add(defaults, "minecraft:quartz_block", 25L);
      add(defaults, "minecraft:glowstone", 30L);
      add(defaults, "minecraft:sea_lantern", 40L);
      add(defaults, "minecraft:amethyst_block", 120L);
      add(defaults, "minecraft:small_amethyst_bud", 15L);
      add(defaults, "minecraft:medium_amethyst_bud", 30L);
      add(defaults, "minecraft:large_amethyst_bud", 50L);
      add(defaults, "minecraft:raw_iron_block", 135L);
      add(defaults, "minecraft:raw_gold_block", 360L);
      add(defaults, "minecraft:raw_copper_block", 60L);
      add(defaults, "minecraft:hay_block", 18L);
      add(defaults, "minecraft:moss_block", 6L);
      add(defaults, "minecraft:magma_block", 12L);
      add(defaults, "minecraft:slime_block", 30L);
      add(defaults, "minecraft:honey_block", 30L);
      add(defaults, "minecraft:tnt", 60L);
      add(defaults, "minecraft:bookshelf", 30L);
      add(defaults, "minecraft:prismarine", 12L);
      add(defaults, "minecraft:dark_prismarine", 16L);
      add(defaults, "minecraft:purpur_block", 8L);
      add(defaults, "minecraft:coal", 8L);
      add(defaults, "minecraft:coal_block", 72L);
      add(defaults, "minecraft:charcoal", 6L);
      add(defaults, "minecraft:raw_iron", 15L);
      add(defaults, "minecraft:iron_ingot", 40L);
      add(defaults, "minecraft:iron_block", 360L);
      add(defaults, "minecraft:raw_copper", 7L);
      add(defaults, "minecraft:copper_ingot", 16L);
      add(defaults, "minecraft:copper_block", 150L);
      add(defaults, "minecraft:raw_gold", 40L);
      add(defaults, "minecraft:gold_ingot", 80L);
      add(defaults, "minecraft:gold_block", 720L);
      add(defaults, "minecraft:exposed_copper", 168L);
      add(defaults, "minecraft:weathered_copper", 189L);
      add(defaults, "minecraft:oxidized_copper", 210L);
      add(defaults, "minecraft:cut_copper", 150L);
      add(defaults, "minecraft:exposed_cut_copper", 168L);
      add(defaults, "minecraft:weathered_cut_copper", 189L);
      add(defaults, "minecraft:oxidized_cut_copper", 210L);
      add(defaults, "minecraft:cut_copper_stairs", 225L);
      add(defaults, "minecraft:exposed_cut_copper_stairs", 252L);
      add(defaults, "minecraft:weathered_cut_copper_stairs", 284L);
      add(defaults, "minecraft:oxidized_cut_copper_stairs", 315L);
      add(defaults, "minecraft:cut_copper_slab", 120L);
      add(defaults, "minecraft:exposed_cut_copper_slab", 134L);
      add(defaults, "minecraft:weathered_cut_copper_slab", 151L);
      add(defaults, "minecraft:oxidized_cut_copper_slab", 168L);
      add(defaults, "minecraft:waxed_copper_block", 150L);
      add(defaults, "minecraft:waxed_exposed_copper", 168L);
      add(defaults, "minecraft:waxed_weathered_copper", 189L);
      add(defaults, "minecraft:waxed_oxidized_copper", 210L);
      add(defaults, "minecraft:waxed_cut_copper", 150L);
      add(defaults, "minecraft:waxed_exposed_cut_copper", 168L);
      add(defaults, "minecraft:waxed_weathered_cut_copper", 189L);
      add(defaults, "minecraft:waxed_oxidized_cut_copper", 210L);
      add(defaults, "minecraft:waxed_cut_copper_stairs", 225L);
      add(defaults, "minecraft:waxed_exposed_cut_copper_stairs", 252L);
      add(defaults, "minecraft:waxed_weathered_cut_copper_stairs", 284L);
      add(defaults, "minecraft:waxed_oxidized_cut_copper_stairs", 315L);
      add(defaults, "minecraft:waxed_cut_copper_slab", 120L);
      add(defaults, "minecraft:waxed_exposed_cut_copper_slab", 134L);
      add(defaults, "minecraft:waxed_weathered_cut_copper_slab", 151L);
      add(defaults, "minecraft:waxed_oxidized_cut_copper_slab", 168L);
      add(defaults, "minecraft:copper_door", 150L);
      add(defaults, "minecraft:copper_trapdoor", 150L);
      add(defaults, "minecraft:copper_grate", 150L);
      add(defaults, "minecraft:chiseled_copper", 150L);
      add(defaults, "minecraft:copper_bulb", 182L);
      add(defaults, "minecraft:waxed_copper_door", 150L);
      add(defaults, "minecraft:waxed_copper_trapdoor", 150L);
      add(defaults, "minecraft:waxed_copper_grate", 150L);
      add(defaults, "minecraft:waxed_chiseled_copper", 216L);
      add(defaults, "minecraft:waxed_copper_bulb", 260L);
      add(defaults, "minecraft:copper_ingot", 24L);
      add(defaults, "minecraft:raw_gold", 40L);
      add(defaults, "minecraft:gold_ingot", 80L);
      add(defaults, "minecraft:gold_block", 720L);
      add(defaults, "minecraft:iron_ingot", 40L);
      add(defaults, "minecraft:redstone", 15L);
      add(defaults, "minecraft:redstone_block", 135L);
      add(defaults, "minecraft:lapis_lazuli", 20L);
      add(defaults, "minecraft:lapis_block", 180L);
      add(defaults, "minecraft:emerald", 150L);
      add(defaults, "minecraft:emerald_block", 1350L);
      add(defaults, "minecraft:diamond", 150L);
      add(defaults, "minecraft:diamond_block", 1500L);
      add(defaults, "minecraft:netherite_scrap", 300L);
      add(defaults, "minecraft:netherite_ingot", 500L);
      add(defaults, "minecraft:netherite_block", 4500L);
      add(defaults, "minecraft:quartz", 10L);
      add(defaults, "minecraft:flint", 3L);
      add(defaults, "minecraft:amethyst_shard", 48L);
      add(defaults, "minecraft:echo_shard", 80L);
      add(defaults, "minecraft:sculk", 15L);
      add(defaults, "minecraft:wheat", 2L);
      add(defaults, "minecraft:wheat_seeds", 1L);
      add(defaults, "minecraft:beetroot", 1L);
      add(defaults, "minecraft:beetroot_seeds", 1L);
      add(defaults, "minecraft:carrot", 1L);
      add(defaults, "minecraft:potato", 1L);
      add(defaults, "minecraft:apple", 2L);
      add(defaults, "minecraft:sweet_berries", 1L);
      add(defaults, "minecraft:glow_berries", 2L);
      add(defaults, "minecraft:melon_slice", 1L);
      add(defaults, "minecraft:pumpkin", 2L);
      add(defaults, "minecraft:melon", 3L);
      add(defaults, "minecraft:sugar_cane", SUGAR_CANE_VALUE);
      add(defaults, "minecraft:bamboo", 1L);
      add(defaults, "minecraft:kelp", 1L);
      add(defaults, "minecraft:cactus", 1L);
      add(defaults, "minecraft:egg", 1L);
      add(defaults, "minecraft:bread", 8L);
      add(defaults, "minecraft:beef", 8L);
      add(defaults, "minecraft:cooked_beef", 14L);
      add(defaults, "minecraft:porkchop", 8L);
      add(defaults, "minecraft:cooked_porkchop", 14L);
      add(defaults, "minecraft:chicken", 6L);
      add(defaults, "minecraft:cooked_chicken", 10L);
      add(defaults, "minecraft:mutton", 6L);
      add(defaults, "minecraft:cooked_mutton", 10L);
      add(defaults, "minecraft:rabbit", 6L);
      add(defaults, "minecraft:cooked_rabbit", 10L);
      add(defaults, "minecraft:cod", 6L);
      add(defaults, "minecraft:cooked_cod", 10L);
      add(defaults, "minecraft:salmon", 8L);
      add(defaults, "minecraft:cooked_salmon", 12L);
      add(defaults, "minecraft:egg", 3L);
      add(defaults, "minecraft:milk_bucket", 12L);
      add(defaults, "minecraft:honey_bottle", 14L);
      add(defaults, "minecraft:rotten_flesh", 1L);
      add(defaults, "minecraft:bone", 2L);
      add(defaults, "minecraft:bone_meal", 1L);
      add(defaults, "minecraft:string", 3L);
      add(defaults, "minecraft:leather", 8L);
      add(defaults, "minecraft:rabbit_hide", 2L);
      add(defaults, "minecraft:feather", 2L);
      add(defaults, "minecraft:slime_ball", 15L);
      add(defaults, "minecraft:ender_pearl", 60L);
      add(defaults, "minecraft:blaze_rod", 40L);
      add(defaults, "minecraft:ghast_tear", 100L);
      add(defaults, "minecraft:phantom_membrane", 80L);
      add(defaults, "minecraft:shulker_shell", 150L);
      add(defaults, "minecraft:nautilus_shell", 50L);
      add(defaults, "minecraft:heart_of_the_sea", 300L);
      add(defaults, "minecraft:prismarine_shard", 6L);
      add(defaults, "minecraft:prismarine_crystals", 25L);
      add(defaults, "minecraft:ink_sac", 3L);
      add(defaults, "minecraft:glow_ink_sac", 12L);
      add(defaults, "minecraft:spider_eye", 10L);
      add(defaults, "minecraft:gunpowder", 10L);
      add(defaults, "minecraft:arrow", 2L);
      add(defaults, "minecraft:experience_bottle", 40L);
      add(defaults, "minecraft:rabbit_foot", 5L);
      add(defaults, "minecraft:turtle_scute", 8L);
      add(defaults, "minecraft:honeycomb", 4L);
      add(defaults, "minecraft:goat_horn", 30L);
      add(defaults, "minecraft:chorus_fruit", 8L);
      add(defaults, "minecraft:popped_chorus_fruit", 10L);
      add(defaults, "minecraft:dragon_breath", 200L);
      add(defaults, "minecraft:saddle", 40L);
      add(defaults, "minecraft:name_tag", 20L);
      add(defaults, "minecraft:lead", 15L);
      add(defaults, "minecraft:pufferfish", 8L);
      add(defaults, "minecraft:tropical_fish", 10L);
      add(defaults, "minecraft:disc_fragment_5", 20L);
      add(defaults, "minecraft:creeper_head", 500L);
      add(defaults, "minecraft:zombie_head", 300L);
      add(defaults, "minecraft:skeleton_skull", 300L);
      add(defaults, "minecraft:wither_skeleton_skull", 3000L);
      add(defaults, "minecraft:dragon_head", 5000L);
      add(defaults, "minecraft:stripped_oak_log", 5L);
      add(defaults, "minecraft:stripped_spruce_log", 5L);
      add(defaults, "minecraft:stripped_birch_log", 5L);
      add(defaults, "minecraft:stripped_jungle_log", 5L);
      add(defaults, "minecraft:stripped_acacia_log", 5L);
      add(defaults, "minecraft:stripped_dark_oak_log", 6L);
      add(defaults, "minecraft:stripped_mangrove_log", 5L);
      add(defaults, "minecraft:stripped_cherry_log", 6L);
      add(defaults, "minecraft:stripped_crimson_stem", 5L);
      add(defaults, "minecraft:stripped_warped_stem", 5L);
      add(defaults, "minecraft:oak_wood", 5L);
      add(defaults, "minecraft:spruce_wood", 5L);
      add(defaults, "minecraft:birch_wood", 5L);
      add(defaults, "minecraft:jungle_wood", 5L);
      add(defaults, "minecraft:acacia_wood", 5L);
      add(defaults, "minecraft:dark_oak_wood", 6L);
      add(defaults, "minecraft:mangrove_wood", 5L);
      add(defaults, "minecraft:cherry_wood", 6L);
      add(defaults, "minecraft:crimson_hyphae", 5L);
      add(defaults, "minecraft:warped_hyphae", 5L);
      add(defaults, "minecraft:crimson_stem", 5L);
      add(defaults, "minecraft:warped_stem", 5L);
      add(defaults, "minecraft:bamboo", 1L);
      add(defaults, "minecraft:bamboo_planks", 2L);
      add(defaults, "minecraft:red_mushroom_block", 5L);
      add(defaults, "minecraft:brown_mushroom_block", 5L);
      add(defaults, "minecraft:mushroom_stem", 5L);
      add(defaults, "minecraft:wither_rose", 120L);
      add(defaults, "minecraft:dragon_breath", 200L);
      add(defaults, "minecraft:end_crystal", 500L);
      add(defaults, "minecraft:dragon_egg", 10000L);
      add(defaults, "minecraft:waxed_rose", 40L);
      add(defaults, "minecraft:bookshelf", 30L);
      add(defaults, "minecraft:enchanted_bookshelf", 30L);
      add(defaults, "minecraft:chain", 20L);
      add(defaults, "minecraft:lantern", 30L);
      add(defaults, "minecraft:soul_lantern", 35L);
      add(defaults, "minecraft:hanging_roots", 3L);
      add(defaults, "minecraft:big_dripleaf", 8L);
      add(defaults, "minecraft:small_dripleaf", 6L);
      add(defaults, "minecraft:spore_blossom", 25L);
      add(defaults, "minecraft:glow_lichen", 3L);
      add(defaults, "minecraft:hanging_sign", 12L);
      add(defaults, "minecraft:sign", 12L);
      add(defaults, "minecraft:pink_petals", 4L);
      add(defaults, "minecraft:decorated_pot", 15L);
      add(defaults, "minecraft:bamboo_mosaic", 4L);
      add(defaults, "minecraft:bamboo_mosaic_stairs", 6L);
      add(defaults, "minecraft:bamboo_mosaic_slab", 3L);
      add(defaults, "minecraft:bamboo_planks", 2L);
      add(defaults, "minecraft:candle", 2L);
      add(defaults, "minecraft:white_candle", 2L);
      add(defaults, "minecraft:blue_candle", 2L);
      add(defaults, "minecraft:sea_pickle", 2L);
      add(defaults, "minecraft:wooden_sword", 15L);
      add(defaults, "minecraft:stone_sword", 40L);
      add(defaults, "minecraft:iron_sword", 100L);
      add(defaults, "minecraft:golden_sword", 80L);
      add(defaults, "minecraft:diamond_sword", 400L);
      add(defaults, "minecraft:netherite_sword", 900L);
      add(defaults, "minecraft:wooden_pickaxe", 10L);
      add(defaults, "minecraft:stone_pickaxe", 30L);
      add(defaults, "minecraft:iron_pickaxe", 80L);
      add(defaults, "minecraft:golden_pickaxe", 60L);
      add(defaults, "minecraft:diamond_pickaxe", 300L);
      add(defaults, "minecraft:netherite_pickaxe", 700L);
      add(defaults, "minecraft:wooden_axe", 10L);
      add(defaults, "minecraft:stone_axe", 30L);
      add(defaults, "minecraft:iron_axe", 80L);
      add(defaults, "minecraft:golden_axe", 60L);
      add(defaults, "minecraft:diamond_axe", 300L);
      add(defaults, "minecraft:netherite_axe", 700L);
      add(defaults, "minecraft:wooden_shovel", 10L);
      add(defaults, "minecraft:stone_shovel", 30L);
      add(defaults, "minecraft:iron_shovel", 60L);
      add(defaults, "minecraft:golden_shovel", 50L);
      add(defaults, "minecraft:diamond_shovel", 220L);
      add(defaults, "minecraft:netherite_shovel", 500L);
      add(defaults, "minecraft:wooden_hoe", 10L);
      add(defaults, "minecraft:stone_hoe", 30L);
      add(defaults, "minecraft:iron_hoe", 60L);
      add(defaults, "minecraft:golden_hoe", 50L);
      add(defaults, "minecraft:diamond_hoe", 220L);
      add(defaults, "minecraft:netherite_hoe", 500L);
      add(defaults, "minecraft:flint_and_steel", 60L);
      add(defaults, "minecraft:shears", 60L);
      add(defaults, "minecraft:bow", 120L);
      add(defaults, "minecraft:crossbow", 160L);
      add(defaults, "minecraft:fishing_rod", 50L);
      add(defaults, "minecraft:shield", 60L);
      add(defaults, "minecraft:brush", 50L);
      add(defaults, "minecraft:spyglass", 60L);
      add(defaults, "minecraft:compass", 40L);
      add(defaults, "minecraft:recovery_compass", 200L);
      add(defaults, "minecraft:clock", 40L);
      add(defaults, "minecraft:bucket", 60L);
      add(defaults, "minecraft:leather_helmet", 30L);
      add(defaults, "minecraft:leather_chestplate", 60L);
      add(defaults, "minecraft:leather_leggings", 45L);
      add(defaults, "minecraft:leather_boots", 30L);
      add(defaults, "minecraft:chainmail_helmet", 60L);
      add(defaults, "minecraft:chainmail_chestplate", 120L);
      add(defaults, "minecraft:chainmail_leggings", 90L);
      add(defaults, "minecraft:chainmail_boots", 60L);
      add(defaults, "minecraft:iron_helmet", 100L);
      add(defaults, "minecraft:iron_chestplate", 220L);
      add(defaults, "minecraft:iron_leggings", 160L);
      add(defaults, "minecraft:iron_boots", 100L);
      add(defaults, "minecraft:golden_helmet", 80L);
      add(defaults, "minecraft:golden_chestplate", 180L);
      add(defaults, "minecraft:golden_leggings", 130L);
      add(defaults, "minecraft:golden_boots", 80L);
      add(defaults, "minecraft:diamond_helmet", 350L);
      add(defaults, "minecraft:diamond_chestplate", 700L);
      add(defaults, "minecraft:diamond_leggings", 550L);
      add(defaults, "minecraft:diamond_boots", 350L);
      add(defaults, "minecraft:netherite_helmet", 800L);
      add(defaults, "minecraft:netherite_chestplate", 1600L);
      add(defaults, "minecraft:netherite_leggings", 1300L);
      add(defaults, "minecraft:netherite_boots", 800L);
      add(defaults, "minecraft:turtle_helmet", 90L);
      add(defaults, "minecraft:wolf_armor", 60L);
      add(defaults, "minecraft:horse_armor", 150L);
      add(defaults, "minecraft:white_wool", 6L);
      add(defaults, "minecraft:blue_wool", 6L);
      add(defaults, "minecraft:white_carpet", 4L);
      add(defaults, "minecraft:blue_carpet", 4L);
      add(defaults, "minecraft:white_concrete", 5L);
      add(defaults, "minecraft:white_concrete_powder", 4L);
      add(defaults, "minecraft:white_terracotta", 6L);
      add(defaults, "minecraft:white_stained_glass", 6L);
      add(defaults, "minecraft:white_stained_glass_pane", 3L);
      add(defaults, "minecraft:emerald_ore", 650L);
      add(defaults, "minecraft:diamond_ore", 650L);
      add(defaults, "minecraft:deepslate_diamond_ore", 650L);
      add(defaults, "minecraft:gold_ore", 200L);
      add(defaults, "minecraft:deepslate_gold_ore", 200L);
      add(defaults, "minecraft:iron_ore", 100L);
      add(defaults, "minecraft:deepslate_iron_ore", 100L);
      add(defaults, "minecraft:copper_ore", 55L);
      add(defaults, "minecraft:deepslate_copper_ore", 55L);
      add(defaults, "minecraft:coal_ore", 40L);
      add(defaults, "minecraft:deepslate_coal_ore", 40L);
      add(defaults, "minecraft:redstone_ore", 85L);
      add(defaults, "minecraft:deepslate_redstone_ore", 85L);
      add(defaults, "minecraft:lapis_ore", 120L);
      add(defaults, "minecraft:deepslate_lapis_ore", 120L);
      add(defaults, "minecraft:nether_quartz_ore", 55L);
      add(defaults, "minecraft:nether_gold_ore", 70L);
      add(defaults, "minecraft:ancient_debris", 1400L);
      add(defaults, "minecraft:netherite_ore", 1400L);
      add(defaults, "minecraft:sponge", 80L);
      add(defaults, "minecraft:wet_sponge", 90L);
      add(defaults, "minecraft:elytra", 4000L);
      add(defaults, "minecraft:totem_of_undying", 2000L);
      add(defaults, "minecraft:trident", 1200L);
      add(defaults, "minecraft:nether_star", 6000L);
      add(defaults, "minecraft:ender_eye", 90L);
      add(defaults, "minecraft:shulker_box", 200L);
      add(defaults, "minecraft:white_shulker_box", 200L);
      add(defaults, "minecraft:golden_apple", 500L);
      add(defaults, "minecraft:enchanted_golden_apple", 2500L);
      JsonObject root = JsonUtil.readOrCreate(file, defaults);
      defaultItemValue = JsonUtil.jsonLong(root, "default_item_value", 1L);
      defaultBlockValue = JsonUtil.jsonLong(root, "default_block_value", 3L);

      for (Entry<String, JsonElement> e : root.entrySet()) {
         String key = e.getKey();
         if (!key.startsWith("_")) {
            try {
               values.put(key, e.getValue().getAsLong());
            } catch (Exception var9) {
            }
         }
      }

      for (Entry<String, JsonElement> e : defaults.entrySet()) {
         String key = e.getKey();
         if (!key.startsWith("_") && !values.containsKey(key)) {
            try {
               values.put(key, e.getValue().getAsLong());
            } catch (Exception var8) {
            }
         }
      }

      migrateStaleDefaults(server, file, root);
   }

   /**
    * Raises values that a later version decided were wrong, exactly once.
    *
    * <p>A changed default is not enough on its own: this file is written to disk the first time a
    * world boots and the loader only fills in keys that are <b>missing</b>, so a world that
    * already exists keeps whatever it was born with. Sugar cane would have gone on being worth
    * one forever on every server older than the change, and the report would have been "you said
    * you raised it and nothing happened".
    *
    * <p>Each migration is recorded in the file, so a server owner who deliberately sets sugar
    * cane back to one keeps it: the marker is the difference between correcting a stale default
    * and overruling a decision.
    */
   private static void migrateStaleDefaults(MinecraftServer server, Path file, JsonObject root) {
      try {
         JsonObject ran = root.has(VALUE_MIGRATIONS) && root.get(VALUE_MIGRATIONS).isJsonObject()
            ? root.getAsJsonObject(VALUE_MIGRATIONS)
            : new JsonObject();
         if (ran.has(SUGAR_CANE_MIGRATION)) {
            return;
         }

         if (values.getOrDefault(SUGAR_CANE, SUGAR_CANE_VALUE) == SUGAR_CANE_OLD_VALUE) {
            values.put(SUGAR_CANE, SUGAR_CANE_VALUE);
            root.addProperty(SUGAR_CANE, SUGAR_CANE_VALUE);
            FortuneFavorsMod.LOGGER.info(
               "Fortune & Favors: sugar cane raised from {} to {} - the old price was a farm that paid nothing",
               SUGAR_CANE_OLD_VALUE,
               SUGAR_CANE_VALUE
            );
         }

         ran.addProperty(SUGAR_CANE_MIGRATION, true);
         root.add(VALUE_MIGRATIONS, ran);
         JsonUtil.write(file, root);
      } catch (Throwable t) {
         // A failed migration leaves the file alone and the value in memory, which is the same
         // price this boot and another chance next boot.
         FortuneFavorsMod.LOGGER.warn("Fortune & Favors: could not record the value migrations", t);
      }
   }

   private static void add(JsonObject obj, String id, long value) {
      obj.addProperty(id, value);
   }

   private static long specialLootValue(ItemStack stack) {
      if (ModItems.isLastRemembrance(stack)) {
         // One per castle, off the one boss in the mod who has to be found and spoken to first.
         return 25000L * Math.max(0, stack.getCount());
      } else if (ModItems.isKingBone(stack)) {
         return 2500L * Math.max(0, stack.getCount());
      } else if (ModItems.isSlimeCore(stack)) {
         return 5000L * Math.max(0, stack.getCount());
      } else if (ModItems.isGolemCore(stack)) {
         return 3000L * Math.max(0, stack.getCount());
      } else if (CustomEnchantments.isTome(stack)) {
         return 1500L * CustomEnchantments.tomeLevel(stack) * Math.max(0, stack.getCount());
      } else if (CCEnchantments.isCCTome(stack)) {
         String ccKey = CCEnchantments.ccTomeKey(stack);
         return 1500L * (ccKey == null ? 1 : CCEnchantments.levelOf(stack, ccKey)) * Math.max(0, stack.getCount());
      } else if (ModItems.isSlimeTrophy(stack)) {
         return 5000L * Math.max(0, stack.getCount());
      } else if (ModItems.isGolemTrophy(stack)) {
         return 5000L * Math.max(0, stack.getCount());
      } else if (ModItems.isSculkEssence(stack)) {
         return 4000L * Math.max(0, stack.getCount());
      } else if (ModItems.isSpawnerLoot(stack)) {
         int lvl = ModItems.spawnerLootLevel(stack);
         double mult = 1.3 + 0.15 * (lvl - 1);
         return Math.round(valueOf(stack.getItem()) * Math.max(0, stack.getCount()) * mult);
      } else {
         return 0L;
      }
   }

   public static long valueOf(ItemStack stack) {
      if (stack.isEmpty()) {
         return 0L;
      } else if (stack.is(Items.ELYTRA) || stack.is(Items.MACE)) {
         return 0L;
      } else if (ModItems.isSpawnerItem(stack)) {
         return 20000L * Math.max(0, stack.getCount());
      } else {
         long loot = specialLootValue(stack);
         if (loot > 0L) {
            return loot;
         } else {
            long potion = potionValue(stack);
            if (potion > 0L) {
               return potion;
            } else {
               return !ModItems.isToken(stack) && !ModItems.isSpecialItem(stack) ? valueOf(stack.getItem()) * Math.max(0, stack.getCount()) : 0L;
            }
         }
      }
   }

   private static long potionValue(ItemStack stack) {
      PotionContents contents = (PotionContents)stack.get(DataComponents.POTION_CONTENTS);
      if (contents != null && !contents.potion().isEmpty()) {
         Holder<Potion> holder = (Holder<Potion>)contents.potion().get();
         Identifier key = BuiltInRegistries.POTION.getKey((Potion)holder.value());
         String name = key == null ? "" : key.getPath();

         long base = switch (name) {
            case "water", "mundane", "thick", "awkward" -> 20L;
            case "night_vision", "long_night_vision" -> 150L;
            case "invisibility", "long_invisibility" -> 300L;
            case "leaping", "long_leaping", "strong_leaping" -> 150L;
            case "fire_resistance", "long_fire_resistance" -> 180L;
            case "swiftness", "long_swiftness", "strong_swiftness" -> 220L;
            case "slowness", "long_slowness", "strong_slowness" -> 140L;
            case "turtle_master", "long_turtle_master", "strong_turtle_master" -> 250L;
            case "water_breathing", "long_water_breathing" -> 160L;
            case "healing", "strong_healing" -> 260L;
            case "harming", "strong_harming" -> 200L;
            case "poison", "long_poison", "strong_poison" -> 170L;
            case "regeneration", "long_regeneration", "strong_regeneration" -> 320L;
            case "strength", "long_strength", "strong_strength" -> 280L;
            case "weakness", "long_weakness" -> 120L;
            case "slow_falling", "long_slow_falling" -> 180L;
            case "luck" -> 400L;
            case "wind_charged", "long_wind_charged" -> 250L;
            case "weaving", "long_weaving" -> 200L;
            case "oozing", "long_oozing" -> 200L;
            case "infested", "long_infested" -> 200L;
            case "saturation" -> 300L;
            default -> 100L;
         };
         if (stack.is(Items.SPLASH_POTION)) {
            base = Math.round((float)base * 1.4F);
         } else if (stack.is(Items.LINGERING_POTION)) {
            base = Math.round((float)base * 1.8F);
         }

         return base;
      } else {
         return 0L;
      }
   }

   public static long valueOf(Item item) {
      if (item == Items.SPAWNER) {
         return 20000L;
      }

      if (item != Items.ELYTRA && item != Items.MACE) {
         Identifier id = BuiltInRegistries.ITEM.getKey(item);
         if (id == null) {
            return defaultItemValue;
         } else {
            Long v = values.get(id.toString());
            if (v != null) {
               return v;
            } else {
               return item instanceof BlockItem ? defaultBlockValue : defaultItemValue;
            }
         }
      } else {
         return 0L;
      }
   }

   public static long buyPrice(Item item) {
      return Math.max(1L, Math.round(valueOf(item) * 2L * 2.0 * ShopData.priceMultiplier()));
   }

   public static boolean canSell(ItemStack stack) {
      return valueOf(stack) > 0L;
   }

   public static Registry<Item> itemRegistry() {
      return BuiltInRegistries.ITEM;
   }
}
