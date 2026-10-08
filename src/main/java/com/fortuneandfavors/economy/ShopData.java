package com.fortuneandfavors.economy;

import com.fortuneandfavors.ModItems;
import com.fortuneandfavors.economy.ShopData.Category;
import com.fortuneandfavors.economy.ShopData.ShopEntry;
import com.fortuneandfavors.util.JsonUtil;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Map.Entry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

public final class ShopData {
   private static final Map<Category, List<ShopEntry>> catalog = new EnumMap<>(Category.class);
   private static final Map<String, Long> priceOverrides = new HashMap<>();
   public static final double BASE_MARKUP = 2.0;
   private static double priceMultiplier = 1.0;
   private static Path dataFile;

   private ShopData() {
   }

   public static void load(MinecraftServer server) {
      dataFile = EconomyManager.getDataDir(server).resolve("shop.json");
      JsonObject root = JsonUtil.readOrCreate(dataFile, defaultShopJson());
      priceOverrides.clear();
      double loaded = Math.max(0.1, JsonUtil.jsonDouble(root, "price_multiplier", 1.0));
      long version = JsonUtil.jsonLong(root, "_version", 0L);
      if (version < 2L) {
         loaded = Math.max(0.1, loaded / 2.0);
      }

      priceMultiplier = loaded;
      if (root.has("price_override") && root.get("price_override").isJsonObject()) {
         for (Entry<String, JsonElement> e : root.getAsJsonObject("price_override").entrySet()) {
            try {
               priceOverrides.put(e.getKey(), e.getValue().getAsLong());
            } catch (Exception var9) {
            }
         }
      }

      buildDefaultCatalog();
   }

   public static void save(MinecraftServer server) {
      if (dataFile == null) {
         dataFile = EconomyManager.getDataDir(server).resolve("shop.json");
      }

      JsonObject root = new JsonObject();
      root.addProperty(
         "_comment",
         "Optional buy-price overrides per item id. The price multiplier applies on top of the normal (1x) price - use /economy price multiplier to adjust it. 1.0 = normal, 2.0 = double."
      );
      root.addProperty("_version", 2);
      root.addProperty("price_multiplier", priceMultiplier);
      JsonObject overrides = new JsonObject();

      for (Entry<String, Long> e : priceOverrides.entrySet()) {
         overrides.addProperty(e.getKey(), e.getValue());
      }

      root.add("price_override", overrides);
      JsonUtil.write(dataFile, root);
   }

   public static double priceMultiplier() {
      return priceMultiplier;
   }

   public static void setPriceMultiplier(double multiplier) {
      priceMultiplier = Math.max(0.1, multiplier);
   }

   public static void setPriceOverride(Item item, long price) {
      Identifier id = BuiltInRegistries.ITEM.getKey(item);
      if (id != null) {
         priceOverrides.put(id.toString(), Math.max(1L, price));
         buildDefaultCatalog();
      }
   }

   private static JsonObject defaultShopJson() {
      JsonObject obj = new JsonObject();
      obj.addProperty(
         "_comment",
         "Optional buy-price overrides per item id. The price multiplier (default 1.0) applies on top of the normal price. 1.0 = normal, 2.0 = double, 0.5 = half."
      );
      obj.addProperty("_version", 2);
      obj.addProperty("price_multiplier", 1.0);
      obj.add("price_override", new JsonObject());
      return obj;
   }

   private static void add(Category category, Item item, long price) {
      ItemStack stack = new ItemStack(item);
      long p = priceOverrides.getOrDefault(BuiltInRegistries.ITEM.getKey(item).toString(), price);
      catalog.computeIfAbsent(category, k -> new ArrayList<>()).add(new ShopEntry(stack, p));
   }

   private static void addIf(Category category, String id, long price) {
      Item item = (Item)BuiltInRegistries.ITEM.getValue(Identifier.parse(id));
      if (item != Items.AIR) {
         add(category, item, price);
      }
   }

   private static void buildDefaultCatalog() {
      catalog.clear();
      buildBuildingCatalog();
      buildFoodCatalog();
      buildToolsCatalog();
      buildExclusiveCatalog();
      buildRedstoneCatalog();
   }

   private static void buildBuildingCatalog() {
      addAll(
         Category.BUILDING,
         e(Items.STONE, 10L),
         e(Items.COBBLESTONE, 8L),
         e(Items.OAK_PLANKS, 8L),
         e(Items.SPRUCE_PLANKS, 8L),
         e(Items.BIRCH_PLANKS, 8L),
         e(Items.JUNGLE_PLANKS, 8L),
         e(Items.ACACIA_PLANKS, 8L),
         e(Items.DARK_OAK_PLANKS, 10L),
         e(Items.MANGROVE_PLANKS, 8L),
         e(Items.CHERRY_PLANKS, 10L),
         e(Items.OAK_LOG, 16L),
         e(Items.SPRUCE_LOG, 16L),
         e(Items.GLASS, 14L),
         e(Items.GLASS_PANE, 8L),
         e(Items.BRICK, 20L),
         e(Items.STONE_BRICKS, 12L),
         e(Items.SANDSTONE, 10L),
         e(Items.QUARTZ_BLOCK, 80L),
         e(Items.SMOOTH_STONE, 10L),
         e(Items.TERRACOTTA, 18L),
         e((Item)Items.DYED_TERRACOTTA.white(), 18L),
         e(Items.DIRT, 4L),
         e(Items.GRASS_BLOCK, 6L),
         e(Items.COBBLED_DEEPSLATE, 10L),
         e(Items.DEEPSLATE_BRICKS, 16L),
         e(Items.POLISHED_DEEPSLATE, 18L),
         e(Items.END_STONE, 30L),
         e(Items.NETHER_BRICKS, 20L),
         e(Items.BLACKSTONE, 16L),
         e(Items.OBSIDIAN, 140L),
         e(Items.GLOWSTONE, 80L),
         e(Items.SEA_LANTERN, 100L),
         e(Items.HAY_BLOCK, 50L),
         e(Items.BOOKSHELF, 80L),
         e(Items.LANTERN, 60L),
         e(Items.TORCH, 10L),
         e(Items.CAMPFIRE, 50L),
         e(Items.ANVIL, 600L),
         e(Items.ENDER_CHEST, 900L),
         e(Items.CHEST, 50L),
         e(Items.BARREL, 50L),
         e(Items.FURNACE, 50L),
         e(Items.BLAST_FURNACE, 180L),
         e(Items.SMOKER, 180L),
         e(Items.BREWING_STAND, 350L),
         e(Items.ENCHANTING_TABLE, 1200L),
         e(Items.DIAMOND_BLOCK, 3400L),
         e(Items.EMERALD_BLOCK, 3100L),
         e(Items.GOLD_BLOCK, 1000L),
         e(Items.IRON_BLOCK, 420L),
         e(Items.REDSTONE_BLOCK, 320L),
         e(Items.LAPIS_BLOCK, 420L),
         e(Items.COAL_BLOCK, 160L)
      );

      for (String w : new String[]{"oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry", "bamboo", "crimson", "warped"}) {
         String logId = !"crimson".equals(w) && !"warped".equals(w) ? w + "_log" : w + "_stem";
         addIf(Category.BUILDING, "minecraft:" + w + "_planks", 8L);
         addIf(Category.BUILDING, "minecraft:" + logId, 16L);
         addIf(Category.BUILDING, "minecraft:" + w + "_stairs", 12L);
         addIf(Category.BUILDING, "minecraft:" + w + "_slab", 6L);
         addIf(Category.BUILDING, "minecraft:" + w + "_fence", 8L);
         addIf(Category.BUILDING, "minecraft:" + w + "_fence_gate", 16L);
         addIf(Category.BUILDING, "minecraft:" + w + "_door", 14L);
         addIf(Category.BUILDING, "minecraft:" + w + "_trapdoor", 14L);
         addIf(Category.BUILDING, "minecraft:" + w + "_button", 4L);
         addIf(Category.BUILDING, "minecraft:" + w + "_pressure_plate", 6L);
         addIf(Category.BUILDING, "minecraft:" + w + "_sign", 12L);
      }

      for (DyeColor c : DyeColor.values()) {
         String n = c.getName();
         addIf(Category.BUILDING, "minecraft:" + n + "_concrete", 10L);
         addIf(Category.BUILDING, "minecraft:" + n + "_concrete_powder", 8L);
         addIf(Category.BUILDING, "minecraft:" + n + "_wool", 12L);
         addIf(Category.BUILDING, "minecraft:" + n + "_stained_glass", 12L);
         addIf(Category.BUILDING, "minecraft:" + n + "_stained_glass_pane", 7L);
         addIf(Category.BUILDING, "minecraft:" + n + "_glazed_terracotta", 22L);
         addIf(Category.BUILDING, "minecraft:" + n + "_terracotta", 16L);
      }

      addAll(
         Category.BUILDING,
         e(Items.POLISHED_ANDESITE, 12L),
         e(Items.POLISHED_GRANITE, 12L),
         e(Items.POLISHED_DIORITE, 12L),
         e(Items.MOSSY_COBBLESTONE, 8L),
         e(Items.MOSSY_STONE_BRICKS, 12L),
         e(Items.CHISELED_STONE_BRICKS, 14L),
         e(Items.CRACKED_STONE_BRICKS, 10L),
         e(Items.DEEPSLATE, 10L),
         e(Items.DEEPSLATE_TILES, 18L),
         e(Items.CHISELED_DEEPSLATE, 18L),
         e(Items.TUFF, 8L),
         e(Items.TUFF_BRICKS, 12L),
         e(Items.CHISELED_TUFF, 12L),
         e(Items.POLISHED_TUFF, 14L),
         e(Items.DRIPSTONE_BLOCK, 12L),
         e(Items.POINTED_DRIPSTONE, 10L),
         e(Items.CALCITE, 10L),
         e(Items.PACKED_MUD, 8L),
         e(Items.MUD_BRICKS, 10L),
         e(Items.CLAY, 6L),
         e(Items.SMOOTH_SANDSTONE, 12L),
         e(Items.CHISELED_SANDSTONE, 12L),
         e(Items.CUT_SANDSTONE, 12L),
         e(Items.RED_SANDSTONE, 10L),
         e(Items.SMOOTH_RED_SANDSTONE, 12L),
         e(Items.CHISELED_RED_SANDSTONE, 14L),
         e(Items.CUT_RED_SANDSTONE, 12L),
         e(Items.PURPUR_BLOCK, 12L),
         e(Items.PURPUR_PILLAR, 14L),
         e(Items.END_STONE_BRICKS, 16L),
         e(Items.RED_NETHER_BRICKS, 22L),
         e(Items.CHISELED_NETHER_BRICKS, 22L),
         e(Items.CRACKED_NETHER_BRICKS, 20L),
         e(Items.POLISHED_BLACKSTONE, 18L),
         e(Items.POLISHED_BLACKSTONE_BRICKS, 20L),
         e(Items.GILDED_BLACKSTONE, 90L),
         e(Items.BASALT, 12L),
         e(Items.POLISHED_BASALT, 16L),
         e(Items.SMOOTH_BASALT, 14L),
         e(Items.PRISMARINE, 12L),
         e(Items.PRISMARINE_BRICKS, 16L),
         e(Items.DARK_PRISMARINE, 16L),
         e(Items.CRYING_OBSIDIAN, 220L),
         e(Items.END_ROD, 40L),
         e(Items.AMETHYST_BLOCK, 30L)
      );
      addIf(Category.BUILDING, "minecraft:copper_block", 60L);
      addIf(Category.BUILDING, "minecraft:exposed_copper", 90L);
      addIf(Category.BUILDING, "minecraft:weathered_copper", 120L);
      addIf(Category.BUILDING, "minecraft:oxidized_copper", 150L);
      addIf(Category.BUILDING, "minecraft:chiseled_copper", 70L);
      addIf(Category.BUILDING, "minecraft:cut_copper", 70L);
      addIf(Category.BUILDING, "minecraft:exposed_cut_copper", 100L);
      addIf(Category.BUILDING, "minecraft:weathered_cut_copper", 130L);
      addIf(Category.BUILDING, "minecraft:oxidized_cut_copper", 160L);
      addIf(Category.BUILDING, "minecraft:cut_copper_stairs", 105L);
      addIf(Category.BUILDING, "minecraft:exposed_cut_copper_stairs", 150L);
      addIf(Category.BUILDING, "minecraft:weathered_cut_copper_stairs", 195L);
      addIf(Category.BUILDING, "minecraft:oxidized_cut_copper_stairs", 240L);
      addIf(Category.BUILDING, "minecraft:cut_copper_slab", 40L);
      addIf(Category.BUILDING, "minecraft:exposed_cut_copper_slab", 55L);
      addIf(Category.BUILDING, "minecraft:weathered_cut_copper_slab", 70L);
      addIf(Category.BUILDING, "minecraft:oxidized_cut_copper_slab", 90L);
      addIf(Category.BUILDING, "minecraft:copper_door", 80L);
      addIf(Category.BUILDING, "minecraft:copper_trapdoor", 80L);
      addIf(Category.BUILDING, "minecraft:copper_grate", 80L);
      addIf(Category.BUILDING, "minecraft:copper_bulb", 100L);
      addIf(Category.BUILDING, "minecraft:waxed_copper_block", 60L);
      addIf(Category.BUILDING, "minecraft:waxed_exposed_copper", 90L);
      addIf(Category.BUILDING, "minecraft:waxed_weathered_copper", 120L);
      addIf(Category.BUILDING, "minecraft:waxed_oxidized_copper", 150L);
      addIf(Category.BUILDING, "minecraft:waxed_cut_copper", 70L);
      addIf(Category.BUILDING, "minecraft:waxed_exposed_cut_copper", 100L);
      addIf(Category.BUILDING, "minecraft:waxed_weathered_cut_copper", 130L);
      addIf(Category.BUILDING, "minecraft:waxed_oxidized_cut_copper", 160L);
      addIf(Category.BUILDING, "minecraft:waxed_copper_door", 80L);
      addIf(Category.BUILDING, "minecraft:waxed_copper_trapdoor", 80L);
      addIf(Category.BUILDING, "minecraft:waxed_copper_grate", 80L);
      addIf(Category.BUILDING, "minecraft:waxed_chiseled_copper", 70L);
      addAll(
         Category.BUILDING,
         e(Items.IRON_BARS, 20L),
         e(Items.IRON_DOOR, 90L),
         e(Items.IRON_TRAPDOOR, 60L),
         e(Items.IRON_CHAIN, 40L),
         e(Items.SOUL_LANTERN, 70L),
         e(Items.SOUL_TORCH, 12L),
         e(Items.JACK_O_LANTERN, 60L),
         e(Items.LECTERN, 100L),
         e(Items.STONECUTTER, 60L),
         e(Items.SMITHING_TABLE, 200L),
         e(Items.CARTOGRAPHY_TABLE, 100L),
         e(Items.FLETCHING_TABLE, 100L),
         e(Items.LOOM, 80L),
         e(Items.COMPOSTER, 40L),
         e(Items.GRINDSTONE, 80L),
         e(Items.CAULDRON, 120L),
         e(Items.BELL, 400L),
         e(Items.LADDER, 10L),
         e(Items.SCAFFOLDING, 20L),
         e(Items.ITEM_FRAME, 30L),
         e(Items.PAINTING, 25L),
         e(Items.FLOWER_POT, 15L),
         e(Items.ARMOR_STAND, 60L),
         e(Items.LEAD, 40L),
         e(Items.SLIME_BLOCK, 120L),
         e(Items.HONEY_BLOCK, 120L)
      );
      addAll(
         Category.BUILDING,
         e(Items.ANDESITE, 10L),
         e(Items.GRANITE, 10L),
         e(Items.DIORITE, 10L),
         e(Items.SAND, 2L),
         e(Items.RED_SAND, 2L),
         e(Items.GRAVEL, 2L),
         e(Items.MOSS_BLOCK, 6L),
         e(Items.MUD, 4L),
         e(Items.TINTED_GLASS, 30L),
         e(Items.SOUL_SAND, 20L),
         e(Items.SOUL_SOIL, 20L),
         e(Items.NETHER_WART_BLOCK, 40L),
         e(Items.WARPED_WART_BLOCK, 40L),
         e(Items.SHROOMLIGHT, 60L),
         e(Items.MAGMA_BLOCK, 30L),
         e(Items.BONE_BLOCK, 60L),
         e(Items.SNOW_BLOCK, 10L),
         e(Items.ICE, 8L),
         e(Items.PACKED_ICE, 25L),
         e(Items.BLUE_ICE, 60L),
         e(Items.QUARTZ_PILLAR, 80L),
         e(Items.CHISELED_QUARTZ_BLOCK, 80L),
         e(Items.QUARTZ_BRICKS, 80L),
         e(Items.SMOOTH_QUARTZ, 80L),
         e(Items.RAW_IRON_BLOCK, 300L),
         e(Items.RAW_GOLD_BLOCK, 700L),
         e(Items.RAW_COPPER_BLOCK, 140L),
         e(Items.NETHERITE_BLOCK, 12000L),
         e(Items.CHISELED_BOOKSHELF, 120L)
      );
      addStoneShapes(Category.BUILDING, 10L, "stone", "stone_bricks", "cobblestone", "mossy_cobblestone", "mossy_stone_bricks", "sandstone", "red_sandstone");
      addStoneShapes(Category.BUILDING, 12L, "andesite", "granite", "diorite", "cobbled_deepslate", "polished_deepslate");
      addStoneShapes(Category.BUILDING, 14L, "prismarine", "purpur", "tuff", "tuff_bricks", "mud_bricks", "polished_blackstone", "polished_blackstone_bricks");
      addStoneShapes(Category.BUILDING, 16L, "deepslate_bricks", "deepslate_tiles", "blackstone", "end_stone_bricks");
      addStoneShapes(Category.BUILDING, 24L, "brick", "nether_bricks", "red_nether_bricks");
      addStoneShapes(Category.BUILDING, 80L, "quartz");
      addIf(Category.BUILDING, "minecraft:cut_copper", 60L);
      addIf(Category.BUILDING, "minecraft:cut_copper_stairs", 90L);
      addIf(Category.BUILDING, "minecraft:cut_copper_slab", 48L);

      for (String ox : new String[]{"exposed", "weathered", "oxidized"}) {
         addIf(Category.BUILDING, "minecraft:" + ox + "_cut_copper", 70L);
         addIf(Category.BUILDING, "minecraft:" + ox + "_cut_copper_stairs", 105L);
         addIf(Category.BUILDING, "minecraft:" + ox + "_cut_copper_slab", 56L);
      }

      addIf(Category.BUILDING, "minecraft:bamboo_block", 16L);
      addIf(Category.BUILDING, "minecraft:bamboo_mosaic", 16L);
      addIf(Category.BUILDING, "minecraft:ochre_froglight", 60L);
      addIf(Category.BUILDING, "minecraft:verdant_froglight", 60L);
      addIf(Category.BUILDING, "minecraft:pearlescent_froglight", 60L);
      addIf(Category.BUILDING, "minecraft:chain", 40L);
      addIf(Category.BUILDING, "minecraft:lantern", 60L);
      addIf(Category.BUILDING, "minecraft:soul_lantern", 70L);
      addIf(Category.BUILDING, "minecraft:candle", 20L);
      addIf(Category.BUILDING, "minecraft:torchflower", 30L);
      addIf(Category.BUILDING, "minecraft:pitcher_plant", 40L);
      addIf(Category.BUILDING, "minecraft:spore_blossom", 50L);
      addIf(Category.BUILDING, "minecraft:big_dripleaf", 30L);
      addIf(Category.BUILDING, "minecraft:hanging_roots", 15L);
      addIf(Category.BUILDING, "minecraft:decorated_pot", 60L);
      addIf(Category.BUILDING, "minecraft:pink_petals", 20L);
      addIf(Category.BUILDING, "minecraft:mangrove_roots", 20L);
      addIf(Category.BUILDING, "minecraft:mud_brick_bricks", 10L);
      addIf(Category.BUILDING, "minecraft:packed_mud", 16L);
      addIf(Category.BUILDING, "minecraft:rooted_dirt", 6L);
      addIf(Category.BUILDING, "minecraft:moss_carpet", 12L);
      addIf(Category.BUILDING, "minecraft:sea_pickle", 14L);
      addIf(Category.BUILDING, "minecraft:lightning_rod", 60L);
      addIf(Category.BUILDING, "minecraft:pointed_dripstone", 10L);
      addIf(Category.BUILDING, "minecraft:smooth_basalt", 14L);

      for (DyeColor c : DyeColor.values()) {
         addIf(Category.BUILDING, "minecraft:" + c.getName() + "_carpet", 8L);
      }

      addIf(Category.BUILDING, "minecraft:oak_hanging_sign", 30L);
      addIf(Category.BUILDING, "minecraft:spruce_hanging_sign", 30L);
      addIf(Category.BUILDING, "minecraft:bamboo_hanging_sign", 30L);
      addIf(Category.BUILDING, "minecraft:chiseled_bookshelf", 140L);
      addIf(Category.BUILDING, "minecraft:sign", 14L);
      addIf(Category.BUILDING, "minecraft:hanging_sign", 30L);
      addIf(Category.BUILDING, "minecraft:mangrove_propagule", 14L);
      addIf(Category.BUILDING, "minecraft:azalea", 40L);
      addIf(Category.BUILDING, "minecraft:azalea_leaves", 10L);
      addIf(Category.BUILDING, "minecraft:flowering_azalea", 60L);
      addIf(Category.BUILDING, "minecraft:torchflower", 40L);
      addIf(Category.BUILDING, "minecraft:pitcher_plant", 50L);
      addIf(Category.BUILDING, "minecraft:pink_petals", 20L);
      addIf(Category.BUILDING, "minecraft:white_banner", 40L);
      addIf(Category.BUILDING, "minecraft:blue_banner", 40L);
      addIf(Category.BUILDING, "minecraft:glow_lichen", 20L);
      addIf(Category.BUILDING, "minecraft:moss_carpet", 12L);
      addIf(Category.BUILDING, "minecraft:pointed_dripstone", 10L);
      addIf(Category.BUILDING, "minecraft:bamboo_mosaic", 16L);
      addIf(Category.BUILDING, "minecraft:bamboo_planks", 8L);
      addIf(Category.BUILDING, "minecraft:bamboo_block", 16L);
   }

   private static void addStoneShapes(Category category, long base, String... families) {
      for (String f : families) {
         addIf(category, "minecraft:" + f + "_stairs", Math.max(1L, base * 3L / 2L));
         addIf(category, "minecraft:" + f + "_slab", Math.max(1L, base * 4L / 5L));
         addIf(category, "minecraft:" + f + "_wall", base);
      }
   }

   private static void buildFoodCatalog() {
      addAll(
         Category.FOOD,
         food(Items.BREAD, 14L),
         food(Items.APPLE, 13L),
         food(Items.GOLDEN_APPLE, 880L),
         food(Items.GOLDEN_CARROT, 280L),
         food(Items.COOKED_BEEF, 26L),
         food(Items.COOKED_PORKCHOP, 26L),
         food(Items.COOKED_CHICKEN, 18L),
         food(Items.COOKED_MUTTON, 18L),
         food(Items.COOKED_RABBIT, 18L),
         food(Items.COOKED_COD, 18L),
         food(Items.COOKED_SALMON, 22L),
         food(Items.CAKE, 72L),
         food(Items.COOKIE, 10L),
         food(Items.MELON_SLICE, 8L),
         food(Items.PUMPKIN_PIE, 29L),
         food(Items.POTATO, 6L),
         food(Items.BAKED_POTATO, 18L),
         food(Items.CARROT, 8L),
         food(Items.SWEET_BERRIES, 10L),
         food(Items.GLOW_BERRIES, 16L),
         food(Items.HONEY_BOTTLE, 32L),
         food(Items.SUSPICIOUS_STEW, 32L),
         food(Items.MUSHROOM_STEW, 29L),
         food(Items.RABBIT_STEW, 56L),
         food(Items.BEETROOT_SOUP, 22L),
         food(Items.DRIED_KELP, 13L),
         food(Items.CHORUS_FRUIT, 72L),
         food(Items.WATER_BUCKET, 120L),
         food(Items.MILK_BUCKET, 32L)
      );
   }

   private static ShopEntry food(Item item, long price) {
      return new ShopEntry(new ItemStack(item), Math.max(1L, price * 3L / 4L));
   }

   private static void buildToolsCatalog() {
      addAll(
         Category.TOOLS,
         e(Items.WOODEN_PICKAXE, 25L),
         e(Items.STONE_PICKAXE, 90L),
         e(Items.IRON_PICKAXE, 280L),
         e(Items.GOLDEN_PICKAXE, 140L),
         e(Items.DIAMOND_PICKAXE, 1000L),
         e(Items.WOODEN_SWORD, 25L),
         e(Items.STONE_SWORD, 90L),
         e(Items.IRON_SWORD, 250L),
         e(Items.DIAMOND_SWORD, 900L),
         e(Items.IRON_AXE, 280L),
         e(Items.DIAMOND_AXE, 1000L),
         e(Items.IRON_SHOVEL, 180L),
         e(Items.IRON_HOE, 200L),
         e(Items.BOW, 400L),
         e(Items.CROSSBOW, 550L),
         e(Items.ARROW, 12L),
         e(Items.SHIELD, 180L),
         e(Items.FISHING_ROD, 140L),
         e(Items.FLINT_AND_STEEL, 120L),
         e(Items.SHEARS, 120L),
         e(Items.BUCKET, 140L),
         e(Items.WATER_BUCKET, 150L),
         e(Items.LAVA_BUCKET, 200L),
         e(Items.BRUSH, 120L),
         e(Items.SPYGLASS, 280L),
         e(Items.LEATHER_HELMET, 140L),
         e(Items.LEATHER_CHESTPLATE, 220L),
         e(Items.LEATHER_LEGGINGS, 190L),
         e(Items.LEATHER_BOOTS, 140L),
         e(Items.CHAINMAIL_HELMET, 300L),
         e(Items.CHAINMAIL_CHESTPLATE, 520L),
         e(Items.CHAINMAIL_LEGGINGS, 450L),
         e(Items.CHAINMAIL_BOOTS, 300L),
         e(Items.IRON_HELMET, 400L),
         e(Items.IRON_CHESTPLATE, 700L),
         e(Items.IRON_LEGGINGS, 620L),
         e(Items.IRON_BOOTS, 400L),
         e(Items.DIAMOND_HELMET, 1300L),
         e(Items.DIAMOND_CHESTPLATE, 2400L),
         e(Items.DIAMOND_LEGGINGS, 2100L),
         e(Items.DIAMOND_BOOTS, 1300L),
         e(Items.TURTLE_HELMET, 350L),
         e(Items.EXPERIENCE_BOTTLE, 40L),
         e(Items.ENDER_PEARL, 600L),
         e(Items.FIREWORK_ROCKET, 50L),
         e(Items.WIND_CHARGE, 100L)
      );
   }

   /**
    * The Exclusive shelf, and the nine machines on it.
    *
    * <p>Everything the mod sells that is not an ordinary block goes here, which includes the
    * late-game machines: a machine that is defined, placeable and functional but is not on any
    * shelf is a machine nobody can own, and "I could not find it in the shop" is exactly how that
    * reads from the other side of the screen. The prices climb with what the machine takes off a
    * player's hands - the two kitchens are a convenience, the farm trio is a wage, and the anchor
    * is the dearest thing here on purpose: it is the one that keeps earning while its owner is
    * asleep, so it is meant to be saved for.
    */
   private static void buildExclusiveCatalog() {
      addAll(
         Category.EXCLUSIVE,
         e(ModItems.autoSellHopper(), 2500L),
         e(ModItems.upwardsHopper(), 7500L),
         e(ModItems.elevator(), 1500L),
         e(ModItems.buySign(), 800L),
         e(ModItems.sellSign(), 800L),
         e(ModItems.tokenRedeemer(), 5000L),
         e(ModItems.spawnerInfuser(), 6000L),
         e(ModItems.itemForge(), 5000L),
         e(ModItems.wormholePotion(), 1000L),
         e(ModItems.backpack(1), 5000L),
         e(ModItems.enderPouch(), 3000L),
         e(ModItems.chair(), 150L),
         // The eight late-game machines, and the belt that goes with them.
         e(ModItems.portableFurnace(), 12_000L),
         e(ModItems.portableCampfire(), 12_000L),
         e(ModItems.repairStation(), 15_000L),
         e(ModItems.irrigationSprinkler(), 20_000L),
         e(ModItems.itemSorter(), 25_000L),
         // The tool that names a container for a sorter, sold beside it - of no use to a player who
         // owns no sorter, and something no sorter owner should have to ask where to find.
         e(ModItems.sorterTag(), 3_000L),
         e(ModItems.potionBelt(), 30_000L),
         e(ModItems.autoPlanter(), 40_000L),
         e(ModItems.autoHarvester(), 40_000L),
         e(ModItems.chunkAnchor(), ChunkAnchor.PRICE),
         // The heavy hoppers and the fast furnace, priced above the machines they replace: the
         // Super Hopper is the sorter's throughput without the thinking, the Transfer Hopper is a
         // sorter with the deciding removed, the Checker Hopper is the sorter's other half (it
         // takes what they refuse), the Splitter is one line becoming two, and the Super Smelter is
         // the blast furnace that finally keeps up with a farm.
         e(ModItems.superHopper(), 18_000L),
         e(ModItems.twoWaySplitter(), 20_000L),
         e(ModItems.checkerHopper(), 30_000L),
         e(ModItems.superSmelter(), 35_000L),
         e(ModItems.transferHopper(), 45_000L),
         // Priced above the Transfer Hopper it is built on: it is the same machine, and the only
         // thing it adds is the answer to the question a full chest asks every farm sooner or later.
         e(ModItems.overflowHopper(), 55_000L)
      );
   }

   private static void buildRedstoneCatalog() {
      addAll(
         Category.REDSTONE,
         e(Items.HOPPER, 120L),
         e(Items.PISTON, 80L),
         e(Items.STICKY_PISTON, 100L),
         e(Items.OBSERVER, 90L),
         e(Items.REPEATER, 70L),
         e(Items.COMPARATOR, 90L),
         e(Items.REDSTONE_LAMP, 90L),
         e(Items.DISPENSER, 110L),
         e(Items.DROPPER, 100L),
         e(Items.REDSTONE_TORCH, 20L),
         e(Items.LEVER, 15L),
         e(Items.REDSTONE, 15L),
         e(Items.TARGET, 60L),
         e(Items.NOTE_BLOCK, 80L),
         e(Items.DAYLIGHT_DETECTOR, 80L),
         e(Items.TRAPPED_CHEST, 90L),
         e(Items.TRIPWIRE_HOOK, 20L),
         e(Items.SCULK_SENSOR, 160L),
         e(Items.CALIBRATED_SCULK_SENSOR, 220L),
         e(Items.CRAFTER, 200L),
         e(Items.TNT, 100L),
         e(Items.RAIL, 20L),
         e(Items.POWERED_RAIL, 40L),
         e(Items.DETECTOR_RAIL, 45L),
         e(Items.ACTIVATOR_RAIL, 45L),
         e(Items.MINECART, 90L),
         e(Items.CHEST_MINECART, 140L),
         e(Items.FURNACE_MINECART, 120L),
         e(Items.TNT_MINECART, 160L),
         e(Items.HOPPER_MINECART, 180L)
      );
   }

   private static ShopEntry e(Item item, long price) {
      return new ShopEntry(new ItemStack(item), price);
   }

   private static ShopEntry e(ItemStack stack, long price) {
      return new ShopEntry(stack, price);
   }

   private static void addAll(Category category, ShopEntry... entries) {
      for (ShopEntry entry : entries) {
         catalog.computeIfAbsent(category, k -> new ArrayList<>()).add(entry);
      }
   }

   public static List<ShopEntry> entries(Category category) {
      return catalog.getOrDefault(category, List.of());
   }

   public static int entryCount(Category category) {
      return entries(category).size();
   }

   public static boolean shopBanned(Item item) {
      return item == Items.ENDER_EYE || item == Items.ELYTRA || item == Items.MACE;
   }

   public static ShopEntry findEntry(Item item) {
      for (List<ShopEntry> list : catalog.values()) {
         for (ShopEntry entry : list) {
            if (!ModItems.isMachineItem(entry.stack()) && !ModItems.isBuySign(entry.stack()) && !ModItems.isSellSign(entry.stack()) && entry.stack().is(item)) {
               return entry;
            }
         }
      }

      return null;
   }

   public static long effectivePrice(ShopEntry entry) {
      return Math.max(1L, Math.round(entry.price() * 2.0 * priceMultiplier));
   }

   public static long effectivePriceFor(UUID uuid, ShopEntry entry) {
      long base = effectivePrice(entry);
      float discount = SkillManager.buyDiscount(uuid);
      return discount > 0.0F ? Math.max(1L, (long)Math.floor(base * (1.0 - discount))) : base;
   }

   public static boolean hasBulkDiscount(Item item) {
      return new ItemStack(item).getMaxStackSize() >= 64;
   }

   public static long purchaseTotal(UUID uuid, Item item, int amount) {
      long unit = buyPriceFor(uuid, item);
      return hasBulkDiscount(item) && amount >= 64 ? Math.max(1L, Math.round(unit * amount * 0.7)) : unit * amount;
   }

   public static long purchaseTotal(UUID uuid, ShopEntry entry, int amount) {
      long unit = effectivePriceFor(uuid, entry);
      return hasBulkDiscount(entry.stack().getItem()) && amount >= 64 ? Math.max(1L, Math.round(unit * amount * 0.7)) : unit * amount;
   }

   public static long bulkStackPrice(Item item) {
      return !hasBulkDiscount(item) ? -1L : Math.max(1L, Math.round(buyPrice(item) * 64L * 0.7));
   }

   public static long buyPrice(Item item) {
      ShopEntry entry = findEntry(item);
      return entry != null ? effectivePrice(entry) : BlockValues.buyPrice(item);
   }

   public static long buyPriceFor(UUID uuid, Item item) {
      ShopEntry entry = findEntry(item);
      if (entry != null) {
         return effectivePriceFor(uuid, entry);
      }

      long base = BlockValues.buyPrice(item);
      float discount = SkillManager.buyDiscount(uuid);
      return discount > 0.0F ? Math.max(1L, (long)Math.floor(base * (1.0 - discount))) : base;
   }

   public static ItemStack makeDisplayStack(ShopEntry entry) {
      ItemStack stack = entry.stack().copy();
      stack.setCount(1);
      List<Component> lines = new ArrayList<>();
      lines.add(Component.literal("§7Price: §a$" + effectivePrice(entry)));
      lines.add(Component.literal("§8Click to buy 1 · Crouch-click for a stack"));
      long bulk = bulkStackPrice(entry.stack().getItem());
      if (bulk > 0L) {
         lines.add(Component.literal("§8Stack (64): §a$" + bulk + "§8 · 30% off"));
      }

      stack.set(DataComponents.LORE, new ItemLore(lines));
      return stack;
   }

         public static ItemStack categoryIcon(Category category) {
      ItemStack icon = switch (category.ordinal()) {
         case 0 -> new ItemStack(Items.BRICKS);
         case 1 -> new ItemStack(Items.BREAD);
         case 2 -> new ItemStack(Items.IRON_PICKAXE);
         case 3 -> ModItems.autoSellHopper();
         case 4 -> new ItemStack(Items.COMPARATOR);
         default -> throw new MatchException(null, null);
      };
      icon.setCount(1);
      icon.set(DataComponents.CUSTOM_NAME, Component.literal("§6§l" + category.label()));
      return icon;
   }


    public enum Category {
       BUILDING("Building"),
       FOOD("Food"),
       TOOLS("Tools"),
       EXCLUSIVE("Exclusive"),
       REDSTONE("Redstone");
    
       private final String label;
    
       Category(String label) {
          this.label = label;
       }
    
       public String label() {
          return this.label;
       }
    }

    public record ShopEntry(ItemStack stack, long price) {
    }
}
