package com.fortuneandfavors.menu;

import com.fortuneandfavors.ModItems;
import com.fortuneandfavors.ModSounds;
import com.fortuneandfavors.VfxManager;
import com.fortuneandfavors.economy.Advancements;
import com.fortuneandfavors.economy.ForgeOps;
import com.fortuneandfavors.menu.LootBoxMenu.Prize;
import com.fortuneandfavors.util.Chat;
import com.fortuneandfavors.util.InventoryHelper;
import com.fortuneandfavors.util.JsonUtil;
import com.fortuneandfavors.util.SoundUtil;
import com.google.gson.JsonObject;
import java.awt.Color;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.HashedStack;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import com.fortuneandfavors.economy.EconomyManager;

public class LootBoxMenu extends ChestMenu {
   private static final int TOGGLE = 4;
   private static final int SPIN = 13;
   private static final int SKIP = 22;
   private static final int SPIN_TICKS = 70;
   /**
    * The tile the box itself is drawn on, and the three cards around the wheel.
    *
    * <p>The window used to be three slots on an otherwise empty wall: a spin in the middle, a skip
    * under it, and a book that was the only way to find out what was in the box at all. Everything
    * else - what the bands are, how warm the box is, how many draws this open will make, what you
    * got last time - existed in the code and nowhere a player could look. These four slots are
    * that information, placed so the eye finds it along the edges of the panel rather than under
    * the wheel it belongs to.
    */
   private static final int BOX = 0;
   private static final int ODDS = 8;
   private static final int RECENT = 18;
   private static final int DRAWS = 26;
   /** The row the contents page shows the legendaries themselves on, skipping the header. */
   private static final int[] SHOWCASE_SLOTS = {9, 10, 11, 12, 14, 15, 16, 17};
   private static final Set<LootBoxMenu> OPEN = ConcurrentHashMap.newKeySet();
   /**
    * The last few prizes each player has taken from each box family, newest first.
    *
    * <p>
    * In memory on purpose, and never saved: a session's worth of receipts. The question it answers
    * is "did I just get rubbish twice in a row, or is this box cold?", which is a question about
    * the last five minutes, and persisting it would only mean a wall of ancient commons in the way
    * of that answer after a restart.
    */
   private static final Map<String, java.util.ArrayDeque<String>> RECENT_WINS = new ConcurrentHashMap<>();
   private static final int RECENT_LIMIT = 6;
   private static final int PITY_THRESHOLD = 10;
   private static final Map<String, Integer> pityProgress = new ConcurrentHashMap<>();
   private static final Map<UUID, Integer> pityReopen = new ConcurrentHashMap<>();
   private static final List<ItemStack> KING_LEGENDS = List.of(ModItems.witherStaff(), ModItems.witherBlade(), ModItems.witherCrown());
   private static final List<ItemStack> SLIME_LEGENDS = List.of(ModItems.slimeLauncher(), ModItems.slimeShield(), ModItems.slimeBoots());
   private static final List<ItemStack> GOLEM_LEGENDS = List.of(ModItems.stoneStaff(), ModItems.golemFist(), ModItems.stoneHeart());
   private static final List<ItemStack> MIND_LEGENDS = List.of(ModItems.mindbinderStaff(), ModItems.possessedMask(), ModItems.mindbinderShroud());
   private static final List<ItemStack> SNOW_LEGENDS = List.of(ModItems.iceStaff(), ModItems.frostboundCrown(), ModItems.glacierCloak());
   private static final List<ItemStack> SCULK_LEGENDS = List.of(ModItems.sculkMageStaff(), ModItems.sculkSensorLeggings(), ModItems.wardensCall());
   private static final List<ItemStack> TIME_LEGENDS = List.of(
      ModItems.pocketWatch(1), ModItems.chronoShard(), ModItems.hourglassOfHaste()
   );
   private static final List<ItemStack> TIME_PREVIEWS = List.of(
      preview(Items.CLOCK, "§dPocket-Watch"),
      preview(Items.AMETHYST_SHARD, "§bChrono Shard"),
      preview(Items.HONEYCOMB, "§6Hourglass of Haste"),
      preview(Items.DIAMOND, "§bDiamonds"),
      preview(Items.AMETHYST_SHARD, "§bAmethyst Shards"),
      preview(Items.ECHO_SHARD, "§5Echo Shards"),
      preview(Items.DIAMOND, "§bEnchanted Diamond Sword"),
      preview(Items.ENCHANTED_BOOK, "§5Aging Tome"),
      preview(Items.ENCHANTED_BOOK, "§bEnchanted Book"),
      preview(Items.EXPERIENCE_BOTTLE, "§aExperience"),
      preview(Items.GOLDEN_APPLE, "§6Golden Apples"),
      preview(Items.EMERALD, "§eEmeralds")
   );
   /** Her three legendary WEAPONS - the only legendary drops her box can roll.
    *  The Trophy is a trophy and the Core is the forge material, so neither
    *  belongs in the pool: mixing them in is what made the set read as "one
    *  legendary and two bits of junk". */
   private static final List<ItemStack> SCARLET_LEGENDS = List.of(
      ModItems.scarletFang(), ModItems.scarletGrimoire(), ModItems.bloodPrism()
   );
   private static final List<ItemStack> SCARLET_PREVIEWS = List.of(
      preview(Items.DIAMOND_SPEAR, "§4Scarlet Fang"),
      preview(Items.BOOK, "§4Scarlet Grimoire"),
      preview(Items.PRISMARINE_SHARD, "§4Blood Prism"),
      preview(Items.GOLD_BLOCK, "§4Scarlet Devil's Trophy"),
      preview(Items.PRISMARINE_CRYSTALS, "§4Bloodsoaked Core"),
      preview(Items.DIAMOND, "§bDiamonds"),
      preview(Items.NETHERITE_SCRAP, "§8Netherite Scrap"),
      preview(Items.DIAMOND_SWORD, "§bEnchanted Diamond Sword"),
      preview(Items.ENCHANTED_BOOK, "§cLifesteal Tome"),
      preview(Items.ENCHANTED_BOOK, "§bEnchanted Book"),
      preview(Items.EXPERIENCE_BOTTLE, "§aExperience"),
      preview(Items.GOLDEN_APPLE, "§6Golden Apples"),
      preview(Items.REDSTONE, "§cRedstone")
   );
   private static final List<ItemStack> CLOCKWORK_LEGENDS = List.of(
      ModItems.clockworkGauntlet(),
      ModItems.mechanicalHeart(),
      ModItems.automatonArmor()
   );
   private static final List<ItemStack> MAGISTER_LEGENDS = List.of(
      ModItems.starpiercer(), ModItems.astralMantle(), ModItems.magistersCodex()
   );
   private static final List<ItemStack> MAGISTER_PREVIEWS = List.of(
      preview(Items.NETHERITE_SWORD, "§bStarpiercer"),
      preview(Items.NETHERITE_CHESTPLATE, "§bAstral Mantle"),
      preview(Items.BOOK, "§bMagister's Codex"),
      preview(Items.NETHER_STAR, "§bStarbound Trophy"),
      preview(Items.AMETHYST_SHARD, "§bMagical Essence"),
      preview(Items.AMETHYST_SHARD, "§bAmethyst Shards"),
      preview(Items.LAPIS_LAZULI, "§9Lapis Lazuli"),
      preview(Items.DIAMOND, "§bDiamonds"),
      preview(Items.DIAMOND_SWORD, "§bEnchanted Diamond Sword"),
      preview(Items.ENCHANTED_BOOK, "§bEnchanted Book"),
      preview(Items.EXPERIENCE_BOTTLE, "§aExperience"),
      preview(Items.GOLDEN_APPLE, "§6Golden Apples")
   );
   private static final List<ItemStack> VOIDSHAPER_LEGENDS = List.of(
      ModItems.voidReaver(), ModItems.colossusPlate(), ModItems.shapingSigil()
   );
   private static final List<ItemStack> VOIDSHAPER_PREVIEWS = List.of(
      preview(Items.NETHERITE_SWORD, "§5Void Reaver"),
      preview(Items.NETHERITE_CHESTPLATE, "§5Colossus Plate"),
      preview(Items.ECHO_SHARD, "§5Shaping Sigil"),
      preview(Items.END_CRYSTAL, "§5Void Shaper's Trophy"),
      preview(Items.NETHERITE_SCRAP, "§5Voidsteel Scrap"),
      preview(Items.OBSIDIAN, "§8Obsidian"),
      preview(Items.DEEPSLATE, "§7Deepslate"),
      preview(Items.DIAMOND, "§bDiamonds"),
      preview(Items.DIAMOND_SWORD, "§bEnchanted Diamond Sword"),
      preview(Items.ENCHANTED_BOOK, "§bEnchanted Book"),
      preview(Items.EXPERIENCE_BOTTLE, "§aExperience"),
      preview(Items.GOLDEN_APPLE, "§6Golden Apples")
   );
   private static final List<ItemStack> SOVEREIGN_LEGENDS = List.of(
      ModItems.royalContract(), ModItems.sovereignsBell(), ModItems.emeraldSeal()
   );
   private static final List<ItemStack> PUPPETEER_LEGENDS = List.of(
      ModItems.puppeteersMask(), ModItems.marionetteStrings(), ModItems.emptyMask()
   );
   private static final List<ItemStack> DROWNED_LEGENDS = List.of(
      ModItems.leviathansGrasp(), ModItems.tidecaller(), ModItems.abyssalChain()
   );
   private static final List<ItemStack> DROWNED_PREVIEWS = List.of(
      preview(Items.MACE, "§3Leviathan's Grasp"),
      preview(Items.TRIDENT, "§3Tidecaller"),
      preview(Items.TRIPWIRE_HOOK, "§3Abyssal Chain"),
      preview(Items.HEART_OF_THE_SEA, "§3Heart of the Deep"),
      preview(Items.NAUTILUS_SHELL, "§3Abyssal Pearls"),
      preview(Items.PRISMARINE_SHARD, "§bPrismarine Shards"),
      preview(Items.PRISMARINE_CRYSTALS, "§bPrismarine Crystals"),
      preview(Items.NAUTILUS_SHELL, "§bNautilus Shells"),
      preview(Items.DIAMOND, "§bDiamonds"),
      preview(Items.DIAMOND_SWORD, "§bEnchanted Diamond Sword"),
      preview(Items.ENCHANTED_BOOK, "§bEnchanted Book"),
      preview(Items.EXPERIENCE_BOTTLE, "§aExperience"),
      preview(Items.GOLDEN_APPLE, "§6Golden Apples")
   );
   private static final List<ItemStack> GALE_LEGENDS = List.of(
      ModItems.skybreaker(), ModItems.galeChakram(), ModItems.wardensMantle()
   );
   private static final List<ItemStack> GALE_PREVIEWS = List.of(
      preview(Items.NETHERITE_SWORD, "§fSkybreaker"),
      preview(Items.TRIDENT, "§fGale Chakram"),
      preview(Items.NETHERITE_CHESTPLATE, "§fWarden's Mantle"),
      preview(Items.NAUTILUS_SHELL, "§fGale Sigil"),
      preview(Items.BREEZE_ROD, "§fGale Cores"),
      preview(Items.PHANTOM_MEMBRANE, "§fPhantom Membranes"),
      preview(Items.FEATHER, "§fFeathers"),
      preview(Items.DIAMOND, "§bDiamonds"),
      preview(Items.DIAMOND_SWORD, "§bEnchanted Diamond Sword"),
      preview(Items.ENCHANTED_BOOK, "§bEnchanted Book"),
      preview(Items.EXPERIENCE_BOTTLE, "§aExperience"),
      preview(Items.GOLDEN_APPLE, "§6Golden Apples")
   );
   private static final List<ItemStack> PUPPETEER_PREVIEWS = List.of(
      preview(Items.CARVED_PUMPKIN, "§5Puppeteer's Mask"),
      preview(Items.STRING, "§5Marionette Strings"),
      preview(Items.SKELETON_SKULL, "§5The Empty Mask"),
      preview(Items.STRING, "§fString"),
      preview(Items.EMERALD, "§aEmeralds"),
      preview(Items.DIAMOND, "§bDiamonds"),
      preview(Items.EXPERIENCE_BOTTLE, "§aExperience"),
      preview(Items.GOLDEN_APPLE, "§6Golden Apples")
   );
   private static final List<ItemStack> SOVEREIGN_PREVIEWS = List.of(
      preview(Items.PAPER, "§aRoyal Contract"),
      preview(Items.BELL, "§aSovereign's Bell"),
      preview(Items.FIREWORK_STAR, "§aEmerald Seal"),
      preview(Items.EMERALD_BLOCK, "§aSovereign's Trophy"),
      preview(Items.EMERALD, "§aRoyal Tribute"),
      preview(Items.EMERALD, "§aEmeralds"),
      preview(Items.GOLD_INGOT, "§6Gold"),
      preview(Items.DIAMOND, "§bDiamonds"),
      preview(Items.DIAMOND_SWORD, "§bEnchanted Diamond Sword"),
      preview(Items.ENCHANTED_BOOK, "§bEnchanted Book"),
      preview(Items.EXPERIENCE_BOTTLE, "§aExperience"),
      preview(Items.GOLDEN_APPLE, "§6Golden Apples")
   );
   private static final List<ItemStack> CLOCKWORK_PREVIEWS = List.of(
      preview(Items.NETHERITE_SWORD, "§6Clockwork Gauntlet"),
      preview(Items.HEART_OF_THE_SEA, "§6Mechanical Heart"),
      preview(Items.NETHERITE_LEGGINGS, "§6Automaton Armor"),
      preview(Items.GOLD_BLOCK, "§6Clockwork King's Trophy"),
      preview(Items.PRISMARINE_CRYSTALS, "§6Mech-Scrap"),
      preview(Items.IRON_INGOT, "§7Iron"),
      preview(Items.REDSTONE, "§cRedstone"),
      preview(Items.COPPER_INGOT, "§6Copper"),
      preview(Items.DIAMOND_SWORD, "§bEnchanted Diamond Sword"),
      preview(Items.ENCHANTED_BOOK, "§bEnchanted Book"),
      preview(Items.EXPERIENCE_BOTTLE, "§aExperience"),
      preview(Items.GOLDEN_APPLE, "§6Golden Apples")
   );
   private static final List<ItemStack> RAID_LEGENDS = List.of(
      ModItems.warlordAxe(),
      ModItems.evokerSpellbook(),
      ModItems.captainHorn(),
      ModItems.warlordCloak(),
      ModItems.evokerCloak(),
      ModItems.illusionerSpellbook(),
      ModItems.illusionerCloak()
   );
   private static final List<ItemStack> RAID_PREVIEWS = List.of(
      preview(Items.NETHERITE_AXE, "§cWarlord's Axe"),
      preview(Items.BOOK, "§5Evoker's Spellbook"),
      preview(Items.GOAT_HORN, "§6Raid Captain's Horn"),
      preview(Items.NETHERITE_CHESTPLATE, "§cWarlord's Cloak"),
      preview(Items.ENCHANTED_BOOK, "§5Evoker's Cloak"),
      preview(Items.BOOK, "§9Illusioner's Spellbook"),
      preview(Items.ELYTRA, "§9Illusioner's Cloak"),
      preview(Items.AMETHYST_SHARD, "§6Raiders Item Upgrader"),
      preview(Items.GOLD_BLOCK, "§cWarlord's Trophy"),
      preview(Items.DIAMOND_SWORD, "§bEnchanted Diamond Sword"),
      preview(Items.DIAMOND, "§bDiamonds"),
      preview(Items.ENDER_PEARL, "§bEnder Pearls"),
      preview(Items.ENCHANTED_BOOK, "§bEnchanted Book"),
      preview(Items.EXPERIENCE_BOTTLE, "§aExperience"),
      preview(Items.GOLDEN_APPLE, "§6Golden Apples"),
      preview(Items.GOLD_INGOT, "§eGold")
   );
   private static final List<ItemStack> KING_PREVIEWS = List.of(
      preview(Items.BONE, "§5Wither Skeleton Staff"),
      preview(Items.NETHERITE_SWORD, "§5Wither Skeleton Blade"),
      preview(Items.NETHERITE_HELMET, "§5Wither Skeleton Crown"),
      preview(Items.DIAMOND, "§b24 Diamonds"),
      preview(Items.DIAMOND_SWORD, "§bEnchanted Diamond Sword"),
      preview(Items.DIAMOND, "§bDiamonds"),
      preview(Items.BOW, "§6Enchanted Bow"),
      preview(Items.WITHER_SKELETON_SKULL, "§7Wither Skull"),
      preview(Items.SENTRY_ARMOR_TRIM_SMITHING_TEMPLATE, "§7Armor Trim"),
      preview(Items.ENCHANTED_BOOK, "§bEnchanted Book"),
      preview(Items.EXPERIENCE_BOTTLE, "§aExperience"),
      preview(Items.GOLDEN_APPLE, "§6Golden Apples"),
      preview(Items.GOLD_INGOT, "§eGold")
   );
   private static final List<ItemStack> SLIME_PREVIEWS = List.of(
      preview(Items.SLIME_BALL, "§5Slime Launcher"),
      preview(Items.SHIELD, "§5Slime Shield"),
      preview(Items.LEATHER_BOOTS, "§5Slime Boots"),
      preview(Items.IRON_SWORD, "§bEnchanted Iron Sword"),
      preview(Items.DIAMOND, "§bDiamonds"),
      preview(Items.SLIME_BLOCK, "§aSlime Blocks"),
      preview(Items.GOLD_INGOT, "§dGold"),
      preview(Items.IRON_INGOT, "§7Iron"),
      preview(Items.ENCHANTED_BOOK, "§bEnchanted Book"),
      preview(Items.EXPERIENCE_BOTTLE, "§aExperience"),
      preview(Items.GOLDEN_APPLE, "§6Golden Apples"),
      preview(Items.ENDER_PEARL, "§aEnder Pearls"),
      preview(Items.EMERALD, "§eEmeralds")
   );
   private static final List<ItemStack> GOLEM_PREVIEWS = List.of(
      preview(Items.STICK, "§5Stone Staff"),
      preview(Items.STONE_AXE, "§5Golem's Fist"),
      preview(Items.IRON_CHESTPLATE, "§5Stoneheart"),
      preview(Items.IRON_SWORD, "§bEnchanted Iron Sword"),
      preview(Items.DIAMOND, "§bDiamonds"),
      preview(Items.IRON_BLOCK, "§7Iron Blocks"),
      preview(Items.STONE_BRICKS, "§7Stone Bricks"),
      preview(Items.COBBLESTONE, "§7Cobblestone"),
      preview(Items.ENCHANTED_BOOK, "§bEnchanted Book"),
      preview(Items.EXPERIENCE_BOTTLE, "§aExperience"),
      preview(Items.GOLDEN_APPLE, "§6Golden Apples"),
      preview(Items.ENDER_PEARL, "§aEnder Pearls"),
      preview(Items.EMERALD, "§eEmeralds")
   );
   private static final List<ItemStack> MIND_PREVIEWS = List.of(
      preview(Items.BLAZE_ROD, "§5Staff of the Mindbinder"),
      preview(Items.IRON_HELMET, "§5Possessed Mask"),
      preview(Items.NETHERITE_CHESTPLATE, "§5Mindbinder's Shroud"),
      preview(Items.PHANTOM_MEMBRANE, "§5Shattered Minds"),
      preview(Items.DIAMOND_SWORD, "§bEnchanted Diamond Sword"),
      preview(Items.DIAMOND, "§bDiamonds"),
      preview(Items.ENDER_EYE, "§5Ender Eyes"),
      preview(Items.ENCHANTED_BOOK, "§bEnchanted Book"),
      preview(Items.EXPERIENCE_BOTTLE, "§aExperience"),
      preview(Items.ENDER_EYE, "§5Ender Eyes"),
      preview(Items.PHANTOM_MEMBRANE, "§7Phantom Membranes"),
      preview(Items.GOLDEN_APPLE, "§6Golden Apples"),
      preview(Items.EMERALD, "§eEmeralds"),
      preview(Items.GOLD_INGOT, "§eGold")
   );
   private static final List<ItemStack> SNOW_PREVIEWS = List.of(
      preview(Items.BLAZE_ROD, "§bIce Staff"),
      preview(Items.BOW, "§bFrostbound Bow"),
      preview(Items.IRON_CHESTPLATE, "§bGlacier Cloak"),
      preview(Items.PRISMARINE_CRYSTALS, "§bFrozen Hearts"),
      preview(Items.DIAMOND_SWORD, "§bEnchanted Diamond Sword"),
      preview(Items.DIAMOND, "§bDiamonds"),
      preview(Items.PACKED_ICE, "§bPacked Ice"),
      preview(Items.SNOW_BLOCK, "§fSnow Blocks"),
      preview(Items.ENCHANTED_BOOK, "§bEnchanted Book"),
      preview(Items.EXPERIENCE_BOTTLE, "§aExperience"),
      preview(Items.SNOWBALL, "§fSnowballs"),
      preview(Items.GOLDEN_APPLE, "§6Golden Apples"),
      preview(Items.EMERALD, "§eEmeralds"),
      preview(Items.GOLD_INGOT, "§eGold")
   );
   private static final List<ItemStack> SCULK_PREVIEWS = List.of(
      preview(Items.BLAZE_ROD, "§3Sculk Mage Staff"),
      preview(Items.IRON_LEGGINGS, "§3Sculk Sensor Leggings"),
      preview(Items.GOAT_HORN, "§3Warden's Call"),
      preview(Items.ECHO_SHARD, "§3Sculk Essence"),
      preview(Items.DIAMOND_SWORD, "§bEnchanted Diamond Sword"),
      preview(Items.DIAMOND, "§bDiamonds"),
      preview(Items.SCULK_CATALYST, "§3Sculk Catalysts"),
      preview(Items.ECHO_SHARD, "§3Echo Shards"),
      preview(Items.DISC_FRAGMENT_5, "§5Disc Fragments"),
      preview(Items.ENCHANTED_BOOK, "§bEnchanted Book"),
      preview(Items.EXPERIENCE_BOTTLE, "§aExperience"),
      preview(Items.GOLDEN_APPLE, "§6Golden Apples"),
      preview(Items.EMERALD, "§eEmeralds"),
      preview(Items.GOLD_INGOT, "§eGold")
   );
   private final SimpleContainer container;
   private final ServerPlayer owner;
   private final int family;
   private boolean contents;
   private boolean choosing = false;
   private boolean stopped = false;
   /** Index into {@link #CHOOSE_SLOTS} for a clicked slot, or -1 if it is not one
    *  of the picker's slots. */
   private static int chooseIndex(int slotId) {
      for (int i = 0; i < CHOOSE_SLOTS.length; i++) {
         if (CHOOSE_SLOTS[i] == slotId) {
            return i;
         }
      }
      return -1;
   }
   private int spinIndex = 0;
   private int spinTicks = 0;
   private int closeAfter = 0;
   private static final Random RANDOM = new Random();

   public LootBoxMenu(int syncId, Inventory playerInventory) {
      this(syncId, playerInventory, new SimpleContainer(27), false, 0);
   }

   private LootBoxMenu(int syncId, Inventory playerInventory, SimpleContainer container, boolean contents, int family) {
      super(MenuType.GENERIC_9x3, syncId, playerInventory, container, 3);
      this.container = container;
      this.owner = (ServerPlayer)playerInventory.player;
      this.contents = contents;
      this.family = family;
      OPEN.add(this);
      this.rebuild();
   }

   private static String boxTitle(int family) {
      return switch (family) {
         case 14 -> "§3§l🌊 Drowned Loot Box";
         case 15 -> "§f§l🌪 Gale Loot Box";
         case 1 -> "§a§lSlime King Loot Box";
         case 2 -> "§7§lStone Golem Loot Box";
         case 3 -> "§5§lMindbinder Loot Box";
         case 4 -> "§b§lSnow Queen Loot Box";
         case 5 -> "§3§lElder Warden Loot Box";
         case 6 -> "§c§l⚔ Raid Loot Box";
         case 7 -> "§d§l⏳ Time Lord Loot Box";
         case 8 -> "§4§l☾ Scarlet Devil Loot Box";
         case 9 -> "§6§l⚙ Clockwork Loot Box";
         case 10 -> "§b§l✨ Starbound Loot Box";
         case 11 -> "§5§l🟯 Void Shaper Loot Box";
         case 12 -> "§a§l👑 Sovereign Loot Box";
         case 13 -> "§5§l🎭 Puppeteer Loot Box";
         default -> "§c§lKing Wither Skeleton Loot Box";
      };
   }

   public static void open(ServerPlayer player, boolean contents, int family) {
      open(player, contents, family, boxTitle(family));
   }

   /** Same, but with an explicit menu title - used by boxes that share a prize
    *  family yet deserve their own name (the Wither Loot Box spins the wither
    *  legendaries but is not the King Wither Skeleton's box). */
   public static void open(ServerPlayer player, boolean contents, int family, String title) {
      if (!contents) {
         Advancements.grant(player, "loot_boxer");
      }

      player.openMenu(
         new SimpleMenuProvider(
            (syncId, inv, p) -> new LootBoxMenu(syncId, inv, new SimpleContainer(27), contents, family), Component.literal(title)
         )
      );
   }

   public static void openPity(ServerPlayer player, int family) {
      player.openMenu(new SimpleMenuProvider((syncId, inv, p) -> {
         LootBoxMenu m = new LootBoxMenu(syncId, inv, new SimpleContainer(27), false, family);
         m.choosing = true;
         m.rebuild();
         return m;
      }, Component.literal(boxTitle(family))));
   }

   private List<ItemStack> previews() {
      return switch (this.family) {
         case 14 -> DROWNED_PREVIEWS;
         case 15 -> GALE_PREVIEWS;
         case 1 -> SLIME_PREVIEWS;
         case 2 -> GOLEM_PREVIEWS;
         case 3 -> MIND_PREVIEWS;
         case 4 -> SNOW_PREVIEWS;
         case 5 -> SCULK_PREVIEWS;
         case 6 -> RAID_PREVIEWS;
         case 7 -> TIME_PREVIEWS;
         case 8 -> SCARLET_PREVIEWS;
         case 9 -> CLOCKWORK_PREVIEWS;
         case 10 -> MAGISTER_PREVIEWS;
         case 11 -> VOIDSHAPER_PREVIEWS;
         case 12 -> SOVEREIGN_PREVIEWS;
         case 13 -> PUPPETEER_PREVIEWS;
         default -> KING_PREVIEWS;
      };
   }

   private List<ItemStack> legendaryPool() {
      return legendaryPoolFor(this.family);
   }

   /**
    * Where the guaranteed-legendary picker draws its choices. Nine slots across
    * the middle row, which is every family's full pool - the raid box has seven
    * legendaries, and the picker used to show them three at a time behind a
    * "next page" arrow, so most players never saw the item they actually wanted.
    */
   private static final int[] CHOOSE_SLOTS = {9, 10, 11, 12, 13, 14, 15, 16, 17};

   private ItemStack chooseHeader(int options) {
      ItemStack stack = new ItemStack(Items.ENDER_EYE);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§d§lPITY BONUS - choose your legendary"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Pick any §d" + options + "§7 legendary from this box."),
               Component.literal("§8Click the one you want.")
            )
         )
      );
      return stack;
   }

   /** True when the rolled stack is one of this box family's legendaries - used
    *  for the pity reset and the "legendaries don't spend the box" rule. (The
    *  raid legendaries use several different name colours, so matching on the
    *  label prefix would miss most of them.) */
   private boolean isFamilyLegendary(ItemStack stack) {
      return switch (this.family) {
         case 14 -> ModItems.isSeaLegendary(stack);
         case 15 -> ModItems.isGaleLegendary(stack);
         case 1 -> ModItems.isSlimeLegendary(stack);
         case 2 -> ModItems.isGolemLegendary(stack);
         case 3 -> ModItems.isMindLegendary(stack);
         case 4 -> ModItems.isSnowLegendary(stack);
         case 5 -> ModItems.isSculkLegendary(stack);
         case 6 -> ModItems.isRaidLegendary(stack);
         case 7 -> ModItems.isTimeLordLegendary(stack);
         case 8 -> ModItems.isScarletLegendary(stack);
         case 9 -> ModItems.isClockworkLegendary(stack);
         case 10 -> ModItems.isMagisterLegendary(stack);
         case 11 -> ModItems.isVoidshaperLegendary(stack);
         case 12 -> ModItems.isSovereignLegendary(stack);
         case 13 -> ModItems.isPuppeteerLegendary(stack);
         default -> ModItems.isWitherLegendary(stack);
      };
   }

   /** Per-player pity counter key for this box family - persisted to disk so a
    *  restart never wipes progress toward the guaranteed legendary. */
   private String pityKeyFor(ServerPlayer player) {
      return switch (this.family) {
         case 14 -> "d4";
         case 15 -> "g4";
         case 1 -> "s";
         case 2 -> "g";
         case 3 -> "m";
         case 4 -> "w";
         case 5 -> "c";
         case 6 -> "r";
         case 7 -> "t";
         case 8 -> "v";
         case 9 -> "q";
         case 10 -> "m2";
         case 11 -> "v2";
         case 12 -> "e2";
         case 13 -> "p3";
         default -> "k";
      } + "/" + player.getUUID();
   }

   private int pityOpens(ServerPlayer player) {
      return Math.min(PITY_THRESHOLD, pityProgress.getOrDefault(this.pityKeyFor(player), 0));
   }

   /** A ten-cell bar for the pity counter, so the number is something a player can
    *  see at a glance instead of a fraction they have to read. */
   private static String pityBar(int opens) {
      StringBuilder sb = new StringBuilder();
      int filled = Math.max(0, Math.min(PITY_THRESHOLD, opens));
      for (int i = 0; i < PITY_THRESHOLD; i++) {
         sb.append(i < filled ? "§d▇" : "§8▇");
      }
      return sb.toString();
   }

   /** One line describing how warm the box is (the soft-pity draw count). */
   private static String pityWarmth(int opens) {
      int draws = drawsFor(opens);
      return switch (draws) {
         case 4 -> "§d§l★ The box is practically glowing - four draws per open.";
         case 3 -> "§dThe box is very warm - three draws per open.";
         case 2 -> "§eThe box is warm - two draws per open.";
         default -> "§8Boxes are cold - one draw per open.";
      };
   }

   /** The main-screen book that opens the info screen - shows pity progress so
    *  players can see how close they are to the guaranteed legendary. */
   /** The box itself: what it is, what it can pay, and the two ways to open it. */
   private ItemStack boxTile() {
      List<Component> lore = new ArrayList<>();
      lore.add(Component.literal("§7" + this.boxName()));
      lore.add(Component.literal(""));
      lore.add(Component.literal("§7Legendaries in this box: §d" + this.legendaryPool().size()));
      for (ItemStack legendary : this.legendaryPool()) {
         lore.add(Component.literal("§8· §7" + legendary.getHoverName().getString()));
      }
      lore.add(Component.literal(""));
      lore.add(Component.literal("§7Right-click the box to spin it."));
      lore.add(Component.literal("§7Shift-right-click it to read this page"));
      lore.add(Component.literal("§7without spinning anything."));
      lore.add(Component.literal(""));
      lore.add(Component.literal("§8Legendaries are one of a kind, and one"));
      lore.add(Component.literal("§8of them never spends the box it came in."));
      return StockMenus.lore(Items.ENDER_CHEST, "§6§l" + this.boxName(), lore);
   }

   /**
    * The band ladder, on the wall where it can be read before spending anything.
    *
    * <p>Four bands, in order, with what each one actually means. The boxes have always had them -
    * the reveal colours itself by band and the prize carries it in its lore - but the only place a
    * player could learn the ladder was by completing it, one expensive disappointment at a time.
    */
   private ItemStack oddsTile() {
      List<Component> lore = new ArrayList<>();
      lore.add(Component.literal("§7Every prize lands in one of four bands."));
      lore.add(Component.literal("§8The bands describe a prize; they are not"));
      lore.add(Component.literal("§8a second table, and no odds moved for them."));
      lore.add(Component.literal(""));
      lore.add(Component.literal(Band.COMMON.colour + "§lCOMMON §8· " + Band.COMMON.colour + "the usual"));
      lore.add(Component.literal(Band.RARE.colour + "§lRARE §8· " + Band.RARE.colour + "materials worth keeping"));
      lore.add(Component.literal(Band.EPIC.colour + "§lEPIC §8· " + Band.EPIC.colour + "books, forge material, big cash"));
      lore.add(Component.literal(Band.LEGENDARY.colour + "§lLEGENDARY §8· " + Band.LEGENDARY.colour + "the box's own set"));
      lore.add(Component.literal(""));
      lore.add(Component.literal("§7A legendary never consumes the box."));
      lore.add(Component.literal("§7Ten boxes without one lets you §dchoose§7 it."));
      return StockMenus.lore(Items.SPYGLASS, "§e§lWhat the box pays", lore);
   }

   /** How many draws this open will make - soft pity, said in numbers. */
   private ItemStack drawsTile() {
      int opens = this.pityOpens(this.owner);
      int draws = drawsFor(opens);
      List<Component> lore = new ArrayList<>();
      lore.add(Component.literal("§7This open draws the table §f" + draws + " time" + (draws == 1 ? "" : "s") + "§7."));
      lore.add(Component.literal("§8The best of those draws is what you get."));
      lore.add(Component.literal(""));
      lore.add(Component.literal("§7Draws are 1, then 2 at four boxes, 3 at"));
      lore.add(Component.literal("§7seven, and 4 from nine upward - so the"));
      lore.add(Component.literal("§7last box is nothing like the first."));
      lore.add(Component.literal(""));
      if (opens >= PITY_THRESHOLD) {
         lore.add(Component.literal("§d§l★ Your next open is the choice itself."));
      } else {
         int left = PITY_THRESHOLD - opens;
         lore.add(Component.literal("§7Boxes until you may choose: §f" + left));
      }
      return StockMenus.lore(Items.FIREWORK_ROCKET, "§a§lThe draw", lore);
   }

   /** The pity counter as its own tile - the bar, the warmth, and what it is counting to. */
   private ItemStack pityTile() {
      int opens = this.pityOpens(this.owner);
      List<Component> lore = new ArrayList<>();
      lore.add(Component.literal(pityBar(opens) + " §8" + opens + "/" + PITY_THRESHOLD));
      lore.add(Component.literal(""));
      if (opens >= PITY_THRESHOLD) {
         lore.add(Component.literal("§d§l★ Your next open is a guaranteed legendary."));
         lore.add(Component.literal("§7...and you get to §dchoose§7 which one."));
      } else {
         lore.add(Component.literal("§ePity: §f" + opens + "§7/§f" + PITY_THRESHOLD));
         lore.add(Component.literal(pityWarmth(opens)));
         lore.add(Component.literal("§8Winning any legendary resets the bar."));
      }
      return StockMenus.lore(Items.NETHER_STAR, "§d§lPity", lore);
   }

   /** The last few things this player has taken from this box. */
   private ItemStack recentTile() {
      List<Component> lore = new ArrayList<>();
      java.util.ArrayDeque<String> wins = RECENT_WINS.get(this.family + "/" + this.owner.getUUID());
      if (wins == null || wins.isEmpty()) {
         lore.add(Component.literal("§8Nothing yet - spin the box."));
         lore.add(Component.literal(""));
         lore.add(Component.literal("§8This list is for the session only."));
      } else {
         lore.add(Component.literal("§8Newest first, this session"));
         for (String win : wins) {
            lore.add(Component.literal("§8· " + win));
         }
         lore.add(Component.literal(""));
         lore.add(Component.literal("§8A cold streak you can see is easier"));
         lore.add(Component.literal("§8to walk away from."));
      }
      return StockMenus.lore(Items.WRITABLE_BOOK, "§7§lYour last prizes", lore);
   }

   /** Records one win in this player's session list, trimming it to its limit. */
   private static void recordWin(ServerPlayer player, int family, Band band, String label) {
      java.util.ArrayDeque<String> wins = RECENT_WINS.computeIfAbsent(
         family + "/" + player.getUUID(), k -> new java.util.ArrayDeque<>()
      );
      synchronized (wins) {
         wins.addFirst(band.colour + label + " §8[" + band.colour + band.label + "§8]");
         while (wins.size() > RECENT_LIMIT) {
            wins.removeLast();
         }
      }
   }

   /**
    * The epic band on its own tile: what sits in the middle and is worth more than it looks.
    *
    * <p>Epic is the band players ask about, because it is the one that most often turns out to be
    * the thing they actually needed - a forge material, a good book, a serious amount of cash. It
    * was the only band with no card of its own.
    */
   private ItemStack epicInfo() {
      List<ItemStack> pool = this.legendaryPool();
      ItemStack material = pool.isEmpty() ? ItemStack.EMPTY : ForgeOps.upgradeMaterialFor(pool.get(0));
      List<Component> lore = new ArrayList<>();
      lore.add(Component.literal(Band.EPIC.colour + "§lEPIC"));
      lore.add(Component.literal("§7The band between rare and legendary: the"));
      lore.add(Component.literal("§7things that take a set somewhere."));
      lore.add(Component.literal(""));
      if (!material.isEmpty()) {
         lore.add(Component.literal("§7Forge material: §f" + material.getHoverName().getString()));
      }
      lore.add(Component.literal("§7Enchanted gear and books."));
      lore.add(Component.literal("§7Big cash prizes."));
      lore.add(Component.literal(""));
      lore.add(Component.literal("§8A warm box upgrades the band it pays"));
      lore.add(Component.literal("§8before it upgrades the jackpot."));
      return StockMenus.lore(material.isEmpty() ? Items.ANCIENT_DEBRIS : material.getItem(), "§5§lEpic", lore);
   }

   private ItemStack pityBook() {
      ItemStack stack = new ItemStack(Items.BOOK);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§7What can I get?"));
      List<Component> lore = new ArrayList<>();
      lore.add(Component.literal("§7Click to see every prize in this box."));
      int opens = this.pityOpens(this.owner);
      lore.add(Component.literal(pityBar(opens) + " §8" + opens + "/" + PITY_THRESHOLD));
      if (opens >= PITY_THRESHOLD) {
         lore.add(Component.literal("§d§l★ Your next open is a guaranteed legendary!"));
      } else {
         lore.add(Component.literal("§ePity: §f" + opens + "§7/§f" + PITY_THRESHOLD + "§7 - guaranteed legendary at " + PITY_THRESHOLD + "."));
         lore.add(Component.literal(pityWarmth(opens)));
      }
      stack.set(DataComponents.LORE, new ItemLore(lore));
      return stack;
   }

   public static void tickAll(MinecraftServer server) {
      if (!pityReopen.isEmpty()) {
         for (Entry<UUID, Integer> e : pityReopen.entrySet()) {
            ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
            if (p != null) {
               boolean hasOpen = false;

               for (LootBoxMenu m : OPEN) {
                  if (m.owner.getUUID().equals(e.getKey())) {
                     hasOpen = true;
                     break;
                  }
               }

               if (!hasOpen) {
                  pityReopen.remove(e.getKey());
                  openPity(p, e.getValue());
               }
            }
         }
      }

      for (LootBoxMenu m : OPEN) {
         if (m.closeAfter > 0) {
            m.closeAfter--;
            if (m.closeAfter <= 0) {
               m.owner.closeContainer();
            }
         } else if (!m.contents && !m.stopped && m.owner.isAlive()) {
            m.spinTicks++;
            if (m.spinTicks == 1) {
               com.fortuneandfavors.net.FfNet.send(m.owner, new com.fortuneandfavors.net.FfScreenFxPayload(com.fortuneandfavors.net.FfScreenFxPayload.FX_LOOT_SPIN, true));
            }
            int speed = m.spinTicks < 46 ? 1 : (m.spinTicks < 58 ? 2 : 3);
            if (m.spinTicks % speed == 0) {
               m.spinIndex++;
               List<ItemStack> pv = m.previews();
               m.container.setItem(13, pv.get(m.spinIndex % pv.size()).copy());
               m.broadcastChanges();
               float t = m.spinTicks / 70.0F;
               float tickPitch = 0.7F + t * 1.1F;
               SoundUtil.play(m.owner, ModSounds.LOOT_TICK, tickPitch);
               if (m.spinTicks % 4 == 0) {
                  float whooshPitch = 0.9F + 1.0F / speed * 0.55F + t * 0.45F;
                  SoundUtil.play(m.owner, ModSounds.LOOT_WHOOSH, whooshPitch, 0.55F);
               }

               if (m.owner.level() instanceof ServerLevel sl) {
                  double x = m.owner.getX();
                  double y = m.owner.getY() + 1.0;
                  double z = m.owner.getZ();

                  for (int i = 0; i < 12; i++) {
                     double a = i / 12.0 * Math.PI * 2.0 + m.spinTicks * 0.16;
                     float hue = (i / 12.0F + m.spinTicks * 0.004F) % 1.0F;
                     int color = Color.HSBtoRGB(hue, 0.9F, 1.0F) & 16777215;
                     com.fortuneandfavors.net.FfVfx.particles(sl, 
                        new DustParticleOptions(color, 0.9F),
                        x + Math.cos(a) * 1.1,
                        y + Math.sin(m.spinTicks * 0.12) * 0.4,
                        z + Math.sin(a) * 1.1,
                        1,
                        0.0,
                        0.0,
                        0.0,
                        0.0
                     );
                  }

                  com.fortuneandfavors.net.FfVfx.particles(sl, ParticleTypes.ENCHANT, x, y + 0.4, z, 3, 0.5, 0.7, 0.5, 0.06);
                  com.fortuneandfavors.net.FfVfx.particles(sl, ParticleTypes.END_ROD, x, y + 0.2, z, 1, 0.3, 0.4, 0.3, 0.02);
                  if (m.spinTicks % 4 == 0) {
                     com.fortuneandfavors.net.FfVfx.particles(sl, ParticleTypes.SOUL_FIRE_FLAME, x, m.owner.getY() + 1.2, z, 1, 0.4, 0.5, 0.4, 0.02);
                  }
               }
            }

            if (m.spinTicks >= 70) {
               m.finishSpin(m.owner);
            }
         }
      }
   }

   /** Load per-player pity progress so a server restart never wipes it. */
   public static void loadPity(MinecraftServer server) {
      try {
         Path f = EconomyManager.getDataDir(server).resolve("lootbox_pity.json");
         JsonObject root = JsonUtil.readOrCreate(f, new JsonObject());
         pityProgress.clear();
         for (String key : root.keySet()) {
            int v = JsonUtil.jsonInt(root, key, 0);
            if (v > 0) {
               pityProgress.put(key, v);
            }
         }
      } catch (Exception e) {
         pityProgress.clear();
      }
   }

   /** Persist per-player pity progress (called on world save and shutdown). */
   public static void savePity(MinecraftServer server) {
      try {
         Path f = EconomyManager.getDataDir(server).resolve("lootbox_pity.json");
         JsonObject root = new JsonObject();
         for (Entry<String, Integer> e : pityProgress.entrySet()) {
            if (e.getValue() != null && e.getValue() > 0) {
               root.addProperty(e.getKey(), e.getValue());
            }
         }
         JsonUtil.write(f, root);
      } catch (Exception e) {
      }
   }

   private void rebuild() {
      this.container.clearContent();
      if (this.choosing) {
         List<ItemStack> pool = this.legendaryPool();
         this.container.setItem(TOGGLE, this.chooseHeader(pool.size()));
         for (int i = 0; i < pool.size() && i < CHOOSE_SLOTS.length; i++) {
            this.container.setItem(CHOOSE_SLOTS[i], pool.get(i).copy());
         }
         this.container.setItem(SKIP, this.named(Items.BARRIER, "§7Close"));
      } else if (!this.contents) {
         this.container.setItem(TOGGLE, this.pityBook());
         List<ItemStack> pv = this.previews();
         ItemStack spin = this.stopped ? this.container.getItem(SPIN) : pv.get(this.spinIndex % pv.size()).copy();
         this.container.setItem(SPIN, spin);
         this.container.setItem(SKIP, this.named(this.stopped ? Items.BARRIER : Items.FIREWORK_ROCKET, this.stopped ? "§7Opened" : "§a§lSKIP"));
         this.container.setItem(RECENT, this.recentTile());
         this.container.setItem(DRAWS, this.drawsTile());
      } else {
         this.container.setItem(TOGGLE, this.named(Items.ARROW, "§7Back to spin"));
         this.container.setItem(SPIN, this.contentsInfo());
         this.container.setItem(SKIP, this.named(Items.BARRIER, "§cClose"));
         this.container.setItem(3, this.pityTile());
         this.container.setItem(DRAWS, this.legendaryInfo());
         // The legendaries themselves, one tile each, rather than one card that names them in a
         // list: a player deciding whether to open a box is deciding whether a specific item is
         // worth it, and the item is the thing that has the tooltip.
         List<ItemStack> pool = this.legendaryPool();
         for (int i = 0; i < pool.size() && i < SHOWCASE_SLOTS.length; i++) {
            this.container.setItem(SHOWCASE_SLOTS[i], pool.get(i).copy());
         }
         this.container.setItem(19, this.epicInfo());
         this.container.setItem(20, this.rareInfo());
         this.container.setItem(21, this.commonInfo());
      }
      this.container.setItem(BOX, this.boxTile());
      this.container.setItem(ODDS, this.oddsTile());
      this.panel();
   }

   /**
    * Fills every slot this window did not use with a dark pane.
    *
    * <p>Cosmetic, and the whole reason the panel reads as a panel: an empty chest row is a row of
    * holes, and a window whose buttons sit in a grid of holes looks unfinished no matter what the
    * buttons say. The pane carries a blank name rather than none, so it does not show "Air" on
    * hover.
    */
   private void panel() {
      Item pane = Items.STAINED_GLASS_PANE.black();
      for (int slot = 0; slot < this.container.getContainerSize(); slot++) {
         if (this.container.getItem(slot).isEmpty()) {
            this.container.setItem(slot, StockMenus.blank(pane));
         }
      }
   }

   private ItemStack contentsInfo() {
      ItemStack stack = new ItemStack(Items.CHEST);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal(boxTitle(this.family)));
      List<Component> lore = new ArrayList<>();
      lore.add(Component.literal("§7Everything you can win from this box."));
      lore.add(Component.literal("§7Legendary items are one-of-a-kind."));
      lore.add(Component.literal(""));
      int opens = this.pityOpens(this.owner);
      lore.add(Component.literal(pityBar(opens) + " §8" + opens + "/" + PITY_THRESHOLD));
      if (opens >= PITY_THRESHOLD) {
         lore.add(Component.literal("§d§l★ Your next open is a guaranteed legendary!"));
      } else {
         lore.add(Component.literal("§ePity progress: §f" + opens + "§7/§f" + PITY_THRESHOLD + "§7 boxes"));
         lore.add(Component.literal("§7until you may §dchoose§7 your legendary."));
         lore.add(Component.literal(pityWarmth(opens)));
      }
      lore.add(Component.literal("§8Winning any legendary resets the counter."));
      stack.set(DataComponents.LORE, new ItemLore(lore));
      return stack;
   }

   private ItemStack legendaryInfo() {
      return switch (this.family) {
         case 1 -> this.tierCard(
            Items.SLIME_BALL,
            "§5§lLegendary Drops",
            List.of(
               "§5Slime Launcher - bursts of slimeballs",
               "§5Slime Shield - auto-blocks 3 hits, or raise a wall of slime",
               "§5Slime Boots - no fall damage, bounce + jump boost"
            )
         );
         case 2 -> this.tierCard(
            Items.STONE_AXE,
            "§5§lLegendary Drops",
            List.of(
               "§5Stone Staff - calls a giant stone down for AOE",
               "§5Golem's Fist - huge knockback + Groundbreaker slam",
               "§5Stoneheart - stand still to be immovable"
            )
         );
         case 3 -> this.tierCard(
            Items.BLAZE_ROD,
            "§5§lLegendary Drops",
            List.of(
               "§5Staff of the Mindbinder - seize a mob's mind",
               "§5Possessed Mask - corruption drains while worn",
               "§53x Shattered Mind - forge the mind legendaries"
            )
         );
         case 4 -> this.tierCard(
            Items.BLAZE_ROD,
            "§b§lLegendary Drops",
            List.of(
               "§bIce Staff - mist that freezes mobs solid",
               "§bFrostbound Bow - draw it, release to loose ice shards",
               "§bGlacier Cloak - a slow aura around you"
            )
         );
         case 5 -> this.tierCard(
            Items.BLAZE_ROD,
            "§3§lLegendary Drops",
            List.of(
               "§3Sculk Mage Staff - sonic blasts, or sculk that drains life to you",
               "§3Sculk Sensor Leggings - moving mobs glow through walls",
               "§3Warden's Call - darkness for everyone, mobs panic and flee"
            )
         );
         case 7 -> this.tierCard(
            Items.CLOCK,
            "§d§lLegendary Drops",
            List.of(
               "§dPocket-Watch - stop time around you for 5s (upgrade to 10s)",
               "§bChrono Shard - rewind 5s and undo the damage you took",
               "§6Hourglass of Haste - 8s of speed and haste, then 3s of slowness"
            )
         );
         case 6 -> this.tierCard(
            Items.NETHERITE_AXE,
            "§c§lLegendary Drops",
            List.of(
               "§cWarlord's Axe - unbreakable, Sharpness VI, Smite VI",
               "§5Evoker's Spellbook - cast fangs, or sneak to summon a vex",
               "§6Raid Captain's Horn - summon 3 raiders to fight for you",
               "§cWarlord's Cloak - axes deal 15% more damage while worn",
               "§5Evoker's Cloak - spellbook deals more, unlocks 2 extra spells",
               "§9Illusioner's Spellbook - blind everyone, then illusions mimic you",
               "§9Illusioner's Cloak - stronger, longer blind + more illusions"
            )
         );
         case 8 -> this.tierCard(
            Items.DIAMOND_SPEAR,
            "§4§lLegendary Weapons",
            List.of(
               "§4Scarlet Fang - a spear whose every hit drinks a share of the damage",
               "§4Scarlet Grimoire - cast her own move set; sneak to cycle spells",
               "§4Blood Prism - bleed a mob or player into a servant of yours"
            )
         );
         case 9 -> this.tierCard(
            Items.NETHERITE_SWORD,
            "§6§lLegendary Drops",
            List.of(
               "§6Clockwork Gauntlet - his stamping arm, worn as a fist",
               "§6Mechanical Heart - it ticks whether or not you do",
               "§6Automaton Armor - his own plating, cut down to fit you"
            )
         );
         case 10 -> this.tierCard(
            Items.NETHER_STAR,
            "§b§lLegendary Drops",
            List.of(
               "§bStarpiercer - every third hit fires a piercing star",
               "§bAstral Mantle - slow falling, and double-tap sneak to step",
               "§bMagister's Codex - cast Star Bolt, Gravity and Meteor"
            )
         );
         case 11 -> this.tierCard(
            Items.END_CRYSTAL,
            "§5§lLegendary Drops",
            List.of(
               "§5Void Reaver - every third hit hurls a block from the world",
               "§5Colossus Plate - block plating, and it does not move for anyone",
               "§5Shaping Sigil - right-click the ground to rip a block up"
            )
         );
         case 12 -> this.tierCard(
            Items.EMERALD_BLOCK,
            "§a§lLegendary Drops",
            List.of(
               "§aRoyal Contract - a Royal Guard answers and fights for you",
               "§aSovereign's Bell - stagger enemies, empower nearby villagers",
               "§aEmerald Seal - cheaper trades, emeralds from kills, Royal Tribute"
            )
         );
         case 13 -> this.tierCard(
            Items.CARVED_PUMPKIN,
            "§5§lLegendary Drops",
            List.of(
               "§5Puppeteer's Mask - kills raise a puppet that fights for you",
               "§5Marionette Strings - tie a target, then pull it to you",
               "§8The Empty Mask - a decoy takes your death instead"
            )
         );
         default -> this.tierCard(
            Items.NETHERITE_SWORD,
            "§5§lLegendary Drops",
            List.of(
               "§5Wither Skeleton Staff - summons a friendly wither skeleton",
               "§5Wither Skeleton Blade - stone-sword damage, 1-in-4 withers",
               "§5Wither Skeleton Crown - wither becomes regen, attackers wither"
            )
         );
      };
   }

   private ItemStack rareInfo() {
      return switch (this.family) {
         case 1 -> this.tierCard(
            Items.IRON_SWORD,
            "§d§lRare Drops",
            List.of(
               "§bEnchanted Iron Sword", "§b8 Diamonds", "§d24 Gold Ingots", "§748 Iron Ingots", "§a16 Slime Blocks", "§63 Golden Apples", "§bEnchanted Book"
            )
         );
         case 2 -> this.tierCard(
            Items.DIAMOND,
            "§d§lRare Drops",
            List.of(
               "§bEnchanted Iron Sword", "§b8 Diamonds", "§b8 Iron Blocks", "§748 Iron Ingots", "§a24 Stone Bricks", "§63 Golden Apples", "§bEnchanted Book"
            )
         );
         case 3 -> this.tierCard(
            Items.DIAMOND_SWORD,
            "§d§lRare Drops",
            List.of(
               "§bEnchanted Diamond Sword",
               "§b8 Diamonds",
               "§516 Ender Eyes",
               "§a24 Experience Bottles",
               "§bEnchanted Book",
               "§63 Golden Apples",
               "§b4 Ender Eyes"
            )
         );
         case 4 -> this.tierCard(
            Items.PACKED_ICE,
            "§d§lRare Drops",
            List.of(
               "§bEnchanted Diamond Sword",
               "§b8 Diamonds",
               "§bPacked Ice & Snow",
               "§a24 Experience Bottles",
               "§bEnchanted Book",
               "§63 Golden Apples",
               "§fSnowballs"
            )
         );
         case 5 -> this.tierCard(
            Items.ECHO_SHARD,
            "§d§lRare Drops",
            List.of(
               "§bEnchanted Diamond Sword",
               "§b8 Diamonds",
               "§3Sculk Catalysts",
               "§a24 Experience Bottles",
               "§bEnchanted Book",
               "§63 Golden Apples",
               "§3Echo Shards"
            )
         );
         case 7 -> this.tierCard(
            Items.AMETHYST_SHARD,
            "§d§lRare Drops",
            List.of(
               "§5Aging Tome - at 5 stacks the target's years catch up at once",
               "§bEnchanted Diamond Sword",
               "§b12 Diamonds",
               "§b16 Amethyst Shards",
               "§5Echo Shards",
               "§bEnchanted Book",
               "§63 Golden Apples"
            )
         );
         case 6 -> this.tierCard(
            Items.SMITHING_TABLE,
            "§6§lRare Drops",
            List.of(
               "§6Raiders Item Upgrader - upgrade a raid legendary to Tier II/III",
               "§cWarlord's Trophy - proof you slew the Raid Warlord",
               "§bEnchanted Diamond Sword",
               "§b12 Diamonds",
               "§b16 Ender Pearls",
               "§bEnchanted Book",
               "§63 Golden Apples"
            )
         );
         case 9 -> this.tierCard(
            Items.PRISMARINE_CRYSTALS,
            "§6§lRare Drops",
            List.of(
               "§63 Mech-Scrap - his forge material",
               "§6Clockwork King's Trophy - proof you scrapped him",
               "§bEnchanted Diamond Sword",
               "§b12 Diamonds",
               "§c24 Redstone",
               "§bEnchanted Book",
               "§63 Golden Apples"
            )
         );
         case 10 -> this.tierCard(
            Items.AMETHYST_SHARD,
            "§b§lRare Drops",
            List.of(
               "§b3 Magical Essence - her forge material",
               "§bStarbound Trophy - proof you silenced her",
               "§bEnchanted Diamond Sword",
               "§b12 Diamonds",
               "§924 Lapis Lazuli",
               "§bEnchanted Book",
               "§63 Golden Apples"
            )
         );
         case 11 -> this.tierCard(
            Items.NETHERITE_SCRAP,
            "§5§lRare Drops",
            List.of(
               "§53 Voidsteel Scrap - his forge material",
               "§5Void Shaper's Trophy - proof you unmade him",
               "§bEnchanted Diamond Sword",
               "§b12 Diamonds",
               "§832 Obsidian",
               "§bEnchanted Book",
               "§63 Golden Apples"
            )
         );
         case 13 -> this.tierCard(
            Items.STRING,
            "§5§lRare Drops",
            List.of(
               "§f24 String",
               "§bEnchanted Diamond Sword",
               "§b12 Diamonds",
               "§a32 Emeralds",
               "§bEnchanted Book",
               "§63 Golden Apples"
            )
         );
         case 12 -> this.tierCard(
            Items.EMERALD,
            "§a§lRare Drops",
            List.of(
               "§a3 Royal Tribute - his forge material",
               "§aSovereign's Trophy - proof you took his throne",
               "§bEnchanted Diamond Sword",
               "§b12 Diamonds",
               "§a32 Emeralds",
               "§bEnchanted Book",
               "§63 Golden Apples"
            )
         );
         case 8 -> this.tierCard(
            Items.PRISMARINE_CRYSTALS,
            "§4§lRare Drops",
            List.of(
               "§42 Bloodsoaked Cores - her forge material",
               "§4Scarlet Devil's Trophy - proof you slew her",
               "§bEnchanted Diamond Sword",
               "§b12 Diamonds",
               "§b16 Ender Pearls",
               "§bEnchanted Book",
               "§63 Golden Apples"
            )
         );
         default -> this.tierCard(
            Items.DIAMOND,
            "§d§lRare Drops",
            List.of(
               "§b24 Diamonds",
               "§bEnchanted Diamond Sword",
               "§b16 Diamonds",
               "§6Enchanted Bow",
               "§bEnchanted Book",
               "§b24 Ender Pearls",
               "§bEnchanted Diamond Pickaxe"
            )
         );
      };
   }

   private ItemStack commonInfo() {
      return switch (this.family) {
         case 1 -> this.tierCard(
            Items.EXPERIENCE_BOTTLE,
            "§7§lCommon Drops",
            List.of("§aExperience Bottles", "§eEmeralds", "§dGold", "§7Iron", "§aSlime Balls", "§aEnder Pearls", "§6Golden Apples", "§6Cash")
         );
         case 2 -> this.tierCard(
            Items.EXPERIENCE_BOTTLE,
            "§7§lCommon Drops",
            List.of("§aExperience Bottles", "§eEmeralds", "§7Cobblestone", "§7Iron", "§7Stone Bricks", "§aEnder Pearls", "§6Golden Apples", "§6Cash")
         );
         case 3 -> this.tierCard(
            Items.EXPERIENCE_BOTTLE,
            "§7§lCommon Drops",
            List.of("§aExperience Bottles", "§eEmeralds", "§dGold", "§7Phantom Membranes", "§aEnder Eyes", "§6Golden Apples", "§7Slowness & Poison", "§6Cash")
         );
         case 4 -> this.tierCard(
            Items.EXPERIENCE_BOTTLE,
            "§7§lCommon Drops",
            List.of("§aExperience Bottles", "§eEmeralds", "§fSnowballs", "§bPacked Ice", "§fSnow Blocks", "§aEnder Pearls", "§6Golden Apples", "§6Cash")
         );
         case 5 -> this.tierCard(
            Items.EXPERIENCE_BOTTLE,
            "§7§lCommon Drops",
            List.of("§aExperience Bottles", "§eEmeralds", "§dGold", "§5Disc Fragments", "§3Echo Shards", "§aEnder Pearls", "§6Golden Apples", "§6Cash")
         );
         case 7 -> this.tierCard(
            Items.EXPERIENCE_BOTTLE,
            "§7§lCommon Drops",
            List.of("§aExperience Bottles", "§eEmeralds", "§bAmethyst Shards", "§5Echo Shards", "§bEnchanted Book", "§aEnder Pearls", "§6Golden Apples", "§6Cash")
         );
         case 6 -> this.tierCard(
            Items.EXPERIENCE_BOTTLE,
            "§7§lCommon Drops",
            List.of("§aExperience Bottles", "§eEmeralds", "§dGold", "§7Iron",               "§bEnchanted Book",
               "§aEnder Pearls",
               "§6Golden Apples",
               "§6Cash")
         );
         case 8 -> this.tierCard(
            Items.EXPERIENCE_BOTTLE,
            "§7§lCommon Drops",
            List.of("§aExperience Bottles", "§eEmeralds", "§dGold", "§7Iron", "§aEnder Pearls", "§6Golden Apples", "§6Cash")
         );
         case 9 -> this.tierCard(
            Items.EXPERIENCE_BOTTLE,
            "§7§lCommon Drops",
            List.of("§aExperience Bottles", "§eEmeralds", "§6Copper Ingots", "§7Iron", "§cRedstone", "§6Golden Apples", "§6Cash")
         );
         case 10 -> this.tierCard(
            Items.EXPERIENCE_BOTTLE,
            "§7§lCommon Drops",
            List.of("§aExperience Bottles", "§eEmeralds", "§9Lapis Lazuli", "§7Iron", "§bAmethyst Shards", "§6Golden Apples", "§6Cash")
         );
         case 11 -> this.tierCard(
            Items.EXPERIENCE_BOTTLE,
            "§7§lCommon Drops",
            List.of("§aExperience Bottles", "§eEmeralds", "§8Obsidian", "§7Deepslate", "§aEnder Pearls", "§6Golden Apples", "§6Cash")
         );
         case 12 -> this.tierCard(
            Items.EXPERIENCE_BOTTLE,
            "§7§lCommon Drops",
            List.of("§aExperience Bottles", "§eEmeralds", "§6Gold Ingots", "§7Bread", "§6Golden Apples", "§6Cash")
         );
         case 13 -> this.tierCard(
            Items.EXPERIENCE_BOTTLE,
            "§7§lCommon Drops",
            List.of("§aExperience Bottles", "§eEmeralds", "§6Gold Ingots", "§fString", "§6Golden Apples", "§6Cash")
         );
         default -> this.tierCard(
            Items.EXPERIENCE_BOTTLE,
            "§7§lCommon Drops",
            List.of(
               "§aExperience Bottles",
               "§eGold & Iron Ingots",
               "§aEmeralds",
               "§6Golden Apples",
               "§aEnder Pearls",
               "§7Armor Trims",
               "§7Wither Skeleton Skulls",
               "§6Cash"
            )
         );
      };
   }

   private ItemStack tierCard(Item icon, String title, List<String> lines) {
      ItemStack stack = new ItemStack(icon);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal(title));
      List<Component> comps = new ArrayList<>();

      for (String line : lines) {
         comps.add(Component.literal(line));
      }

      stack.set(DataComponents.LORE, new ItemLore(comps));
      return stack;
   }

   /**
    * The four bands every box in this mod pays in, and what each one is worth seeing.
    *
    * <p>A loot box used to have exactly two outcomes: a legendary, or "something else". Every
    * non-legendary prize was drawn from one flat ladder - junk, forge material, enchanted gear and
    * the boss's own money all sat at the same weight - so a box that paid a stack of golden apples
    * and a box that paid the material that takes a legendary to Tier III felt identical to open,
    * and the only number a player could read off the reveal was whether they had been lucky that
    * once. The four bands give the middle of that ladder a shape: what you got is stated on the
    * prize, the reveal is drawn at the size the band deserves, and - because the soft-pity draws
    * compare bands rather than hunting only for a legendary - a warm box upgrades the *band* it
    * pays, not just the one draw in ten that happens to hit the jackpot.
    *
    * <p>The bands are a description of what a prize is, never a second table: no odds moved when
    * this was added, so the boxes pay exactly what they always paid. What changed is that the
    * middle is legible.
    */
   public enum Band {
      COMMON("§7", "Common", 11184810, 1.0F, 22),
      RARE("§b", "Rare", 4186879, 1.15F, 30),
      EPIC("§5", "Epic", 11816191, 1.35F, 42),
      LEGENDARY("§d", "Legendary", 16765514, 1.7F, 56);

      /** The colour of the band, ready to put in front of its name. */
      public final String colour;
      /** The name a player reads on the prize. */
      public final String label;
      /** The colour of the reveal's flash, as packed rgb. */
      public final int dust;
      /** How much bigger every shape of the reveal is drawn at this band. */
      public final float scale;
      /** How many points the reveal's ring has. */
      public final int ring;

      Band(String colour, String label, int dust, float scale, int ring) {
         this.colour = colour;
         this.label = label;
         this.dust = dust;
         this.scale = scale;
         this.ring = ring;
      }

      /** The band a player would get if they opened this box right now, in one line. */
      String header() {
         return this.colour + "§l" + this.label.toUpperCase();
      }

      boolean betterThan(Band other) {
         return other == null || this.ordinal() > other.ordinal();
      }
   }

   /** A cash prize at or above this is worth calling Epic; below it, Rare. */
   private static final long CASH_EPIC = 1500L;

   /**
    * What is genuinely worth calling Rare, when it is not a legendary, a material or enchanted.
    *
    * <p>A named set rather than a rule about price, because the question the band answers is "is
    * this a thing a player remembers getting", and a shop price does not know that.
    */
   private static final Set<Item> RARE_ITEMS = Set.of(
      Items.DIAMOND,
      Items.EMERALD,
      Items.NETHERITE_INGOT,
      Items.NETHERITE_SCRAP,
      Items.ANCIENT_DEBRIS,
      Items.ENCHANTED_GOLDEN_APPLE,
      Items.ENDER_PEARL,
      Items.SHULKER_SHELL,
      Items.EXPERIENCE_BOTTLE,
      Items.NAUTILUS_SHELL,
      Items.ECHO_SHARD,
      Items.NETHER_STAR,
      Items.TOTEM_OF_UNDYING
   );

   /**
    * Which band a prize belongs to. The one place that decides, so the reveal, the tooltip and the
    * soft-pity draw can never disagree about what a player just got.
    *
    * <p>The family's own forge material counts as Epic and not Rare: it is the thing that takes
    * that box's set from Tier I to Tier III, which is a bigger deal than the diamonds beside it.
    * It is asked for through the forge's own mapping rather than a second list here, so a set that
    * gains a material gains a band on the same line.
    */
   static Band bandOf(int family, Prize prize) {
      if (prize == null) {
         return Band.COMMON;
      }
      ItemStack stack = prize.stack();
      if (isLegendaryStack(family, stack)) {
         return Band.LEGENDARY;
      }
      if (prize.cash() > 0L) {
         return prize.cash() >= CASH_EPIC ? Band.EPIC : Band.RARE;
      }
      if (stack.isEmpty()) {
         return Band.COMMON;
      }
      for (ItemStack legendary : legendaryPoolFor(family)) {
         ItemStack material = ForgeOps.upgradeMaterialFor(legendary);
         if (!material.isEmpty() && stack.is(material.getItem())) {
            return Band.EPIC;
         }
      }
      if (stack.isEnchanted() || stack.has(DataComponents.STORED_ENCHANTMENTS)) {
         return Band.EPIC;
      }
      return RARE_ITEMS.contains(stack.getItem()) ? Band.RARE : Band.COMMON;
   }

   /**
    * The band a raw prize would be given, for the self-test: the classifier, without a box.
    *
    * <p>Public rather than package-private because the checks that pin the bands live in
    * {@code SelfTest}, and a band rule nobody outside this file can read is a band rule nobody can
    * show is right.
    */
   public static Band bandForItem(int family, ItemStack stack, long cash) {
      return bandOf(family, new Prize("", stack == null ? ItemStack.EMPTY : stack, cash));
   }

   /** The legendaries a box family rolls at, by family. Shared by the picker and the bands. */
   public static List<ItemStack> legendaryPoolFor(int family) {
      return switch (family) {
         case 14 -> DROWNED_LEGENDS;
         case 15 -> GALE_LEGENDS;
         case 1 -> SLIME_LEGENDS;
         case 2 -> GOLEM_LEGENDS;
         case 3 -> MIND_LEGENDS;
         case 4 -> SNOW_LEGENDS;
         case 5 -> SCULK_LEGENDS;
         case 6 -> RAID_LEGENDS;
         case 7 -> TIME_LEGENDS;
         case 8 -> SCARLET_LEGENDS;
         case 9 -> CLOCKWORK_LEGENDS;
         case 10 -> MAGISTER_LEGENDS;
         case 11 -> VOIDSHAPER_LEGENDS;
         case 12 -> SOVEREIGN_LEGENDS;
         case 13 -> PUPPETEER_LEGENDS;
         default -> KING_LEGENDS;
      };
   }

   /**
    * How many times the table is drawn at this pity level.
    *
    * <p>The pity bar promises "guaranteed legendary at ten", and it delivers -
    * but for the nine boxes before it, the odds were flat, so the counter was an
    * invisible number that only mattered on the very last open. Taking the draw
    * more than once and keeping the better result is soft pity: the further the
    * counter climbs, the more likely a legendary is, and the promise the bar makes
    * is one the player can feel before they reach the end of it.
    */
   private static int drawsFor(int opens) {
      if (opens >= 9) {
         return 4;
      }
      if (opens >= 7) {
         return 3;
      }
      return opens >= 4 ? 2 : 1;
   }

   /** Whether a rolled stack is one of the legendaries of this box family. */
   private static boolean isLegendaryStack(int family, ItemStack stack) {
      if (stack == null || stack.isEmpty()) {
         return false;
      }
      return switch (family) {
         case 14 -> ModItems.isSeaLegendary(stack);
         case 15 -> ModItems.isGaleLegendary(stack);
         case 1 -> ModItems.isSlimeLegendary(stack);
         case 2 -> ModItems.isGolemLegendary(stack);
         case 3 -> ModItems.isMindLegendary(stack);
         case 4 -> ModItems.isSnowLegendary(stack);
         case 5 -> ModItems.isSculkLegendary(stack);
         case 6 -> ModItems.isRaidLegendary(stack);
         case 7 -> ModItems.isTimeLordLegendary(stack);
         case 8 -> ModItems.isScarletLegendary(stack);
         case 9 -> ModItems.isClockworkLegendary(stack);
         case 10 -> ModItems.isMagisterLegendary(stack);
         case 11 -> ModItems.isVoidshaperLegendary(stack);
         case 12 -> ModItems.isSovereignLegendary(stack);
         case 13 -> ModItems.isPuppeteerLegendary(stack);
         default -> ModItems.isWitherLegendary(stack);
      };
   }

   /**
    * Draws the box's table with soft pity applied: the table is rolled {@link #drawsFor} times and
    * the best result is kept, so a warm box is a genuinely better box rather than a
    * differently-worded one.
    *
    * <p>"The best result" is read off {@link Band}, not off a legendary test: the draws used to be
    * spent only when a legendary was not in hand, so a warm box that rolled diamonds four times
    * paid exactly what a cold one paid. Every draw now compares bands and any step up is kept -
    * Common to Rare counts, and Rare to Epic counts, which is what makes the pity bar a promise a
    * player can feel before its last open.
    */
   private static Prize roll(ServerPlayer player, int family, int opens) {
      Prize best = rollTable(player, family);
      Band bestBand = bandOf(family, best);
      int draws = drawsFor(opens);
      for (int i = 1; i < draws && bestBand != Band.LEGENDARY; i++) {
         Prize next = rollTable(player, family);
         Band band = bandOf(family, next);
         // Any step up counts, not only the jackpot. A warm box that keeps drawing junk and pays
         // out the same junk was a counter a player could not feel; a warm box that turns a Common
         // into a Rare, and a Rare into an Epic, is the promise the pity bar makes.
         if (band.betterThan(bestBand)) {
            best = next;
            bestBand = band;
         }
      }
      return best;
   }

   private static Prize rollTable(ServerPlayer player, int family) {
      return switch (family) {
         case 14 -> rollDrowned(player);
         case 15 -> rollGale(player);
         case 1 -> rollSlime(player);
         case 2 -> rollGolem(player);
         case 3 -> rollMind(player);
         case 4 -> rollSnow(player);
         case 5 -> rollSculk(player);
         case 6 -> rollRaid(player);
         case 7 -> rollTime(player);
         case 8 -> rollScarlet(player);
         case 9 -> rollClockwork(player);
         case 10 -> rollMagister(player);
         case 11 -> rollVoidshaper(player);
         case 12 -> rollSovereign(player);
         case 13 -> rollPuppeteer(player);
         default -> rollKing(player);
      };
   }

   /**
    * The Drowned Sovereign's table: ocean junk, his forging material, and his three.
    *
    * <p>Prismarine rather than stone because the box has to feel like the fight it came from -
    * the sea boss's arena is a seabed, and a box that pays out deepslate would be the Void Shaper's.
    */
   private static Prize rollDrowned(ServerPlayer player) {
      int r = RANDOM.nextInt(100);
      if (r < 2) {
         return new Prize("§3§l🌊 Leviathan's Grasp", ModItems.leviathansGrasp(), 0L);
      } else if (r < 4) {
         return new Prize("§3§l🌊 Tidecaller", ModItems.tidecaller(), 0L);
      } else if (r < 6) {
         return new Prize("§3§l🌊 Abyssal Chain", ModItems.abyssalChain(), 0L);
      } else if (r < 8) {
         return new Prize("§3§l🌊 Heart of the Deep", ModItems.sovereignsHeart(), 0L);
      } else if (r < 15) {
         // His own material rather than the shared one: this is the second source of the thing
         // that takes his set from Tier I to Tier III, and a box that paid Magical Essence would
         // be paying a material no item in the box can be upgraded with.
         ItemStack pearls = ModItems.abyssalPearl();
         pearls.setCount(3);
         return new Prize("§33 Abyssal Pearls", pearls, 0L);
      } else if (r < 25) {
         ItemStack s = stack(Items.DIAMOND_SWORD, 1);
         enchant(player, s, Enchantments.SHARPNESS, 4);
         enchant(player, s, Enchantments.UNBREAKING, 3);
         return new Prize("§bEnchanted Diamond Sword", s, 0L);
      } else if (r < 35) {
         return new Prize("§b12 Diamonds", stack(Items.DIAMOND, 12), 0L);
      } else if (r < 45) {
         return new Prize("§b24 Prismarine Shards", stack(Items.PRISMARINE_SHARD, 24), 0L);
      } else if (r < 53) {
         return new Prize("§b16 Prismarine Crystals", stack(Items.PRISMARINE_CRYSTALS, 16), 0L);
      } else if (r < 60) {
         return new Prize("§b8 Nautilus Shells", stack(Items.NAUTILUS_SHELL, 8), 0L);
      } else if (r < 67) {
         ItemStack s = stack(Items.ENCHANTED_BOOK, 1);
         enchant(player, s, RANDOM.nextBoolean() ? Enchantments.SHARPNESS : Enchantments.PROTECTION, 4);
         return new Prize("§bEnchanted Book", s, 0L);
      } else if (r < 76) {
         return new Prize("§a24 Experience Bottles", stack(Items.EXPERIENCE_BOTTLE, 24), 0L);
      } else if (r < 84) {
         return new Prize("§38 Sponge", stack(Items.SPONGE, 8), 0L);
      } else if (r < 91) {
         return new Prize("§63 Golden Apples", stack(Items.GOLDEN_APPLE, 3), 0L);
      } else {
         int cash = 900 + RANDOM.nextInt(2101);
         return new Prize("§6$" + cash, ItemStack.EMPTY, cash);
      }
   }

   /** The Gale Warden's table: sky junk, his material, and his three. */
   private static Prize rollGale(ServerPlayer player) {
      int r = RANDOM.nextInt(100);
      if (r < 2) {
         return new Prize("§f§l🌪 Skybreaker", ModItems.skybreaker(), 0L);
      } else if (r < 4) {
         return new Prize("§f§l🌪 Gale Chakram", ModItems.galeChakram(), 0L);
      } else if (r < 6) {
         return new Prize("§f§l🌪 Warden's Mantle", ModItems.wardensMantle(), 0L);
      } else if (r < 8) {
         return new Prize("§f§l🌪 Gale Sigil", ModItems.galeSigil(), 0L);
      } else if (r < 15) {
         // The sky set's own material, for the same reason his boss pays it: a Warden's
         // legendary cannot leave Tier I without Gale Cores.
         ItemStack cores = ModItems.galeCore();
         cores.setCount(3);
         return new Prize("§f3 Gale Cores", cores, 0L);
      } else if (r < 25) {
         ItemStack s = stack(Items.DIAMOND_SWORD, 1);
         enchant(player, s, Enchantments.SHARPNESS, 4);
         enchant(player, s, Enchantments.UNBREAKING, 3);
         return new Prize("§bEnchanted Diamond Sword", s, 0L);
      } else if (r < 35) {
         return new Prize("§b12 Diamonds", stack(Items.DIAMOND, 12), 0L);
      } else if (r < 45) {
         // Feather Falling is the box's nod to what the boss is: nothing here is heavy.
         ItemStack boots = stack(Items.DIAMOND_BOOTS, 1);
         enchant(player, boots, Enchantments.FEATHER_FALLING, 4);
         return new Prize("§fDiamond Boots (Feather Falling IV)", boots, 0L);
      } else if (r < 53) {
         return new Prize("§f24 Phantom Membranes", stack(Items.PHANTOM_MEMBRANE, 24), 0L);
      } else if (r < 60) {
         return new Prize("§f16 Feathers", stack(Items.FEATHER, 16), 0L);
      } else if (r < 67) {
         ItemStack s = stack(Items.ENCHANTED_BOOK, 1);
         enchant(player, s, RANDOM.nextBoolean() ? Enchantments.SHARPNESS : Enchantments.PROTECTION, 4);
         return new Prize("§bEnchanted Book", s, 0L);
      } else if (r < 76) {
         return new Prize("§a24 Experience Bottles", stack(Items.EXPERIENCE_BOTTLE, 24), 0L);
      } else if (r < 84) {
         // What the Warden's Mantle's own gimmick is worth: slow falling, in a bottle.
         ItemStack potion = new ItemStack(Items.POTION);
         potion.set(
            net.minecraft.core.component.DataComponents.POTION_CONTENTS,
            net.minecraft.world.item.alchemy.PotionContents.EMPTY.withPotion(net.minecraft.world.item.alchemy.Potions.SLOW_FALLING)
         );
         return new Prize("§fSlow Falling Potion", potion, 0L);
      } else if (r < 91) {
         return new Prize("§63 Golden Apples", stack(Items.GOLDEN_APPLE, 3), 0L);
      } else {
         int cash = 900 + RANDOM.nextInt(2101);
         return new Prize("§6$" + cash, ItemStack.EMPTY, cash);
      }
   }

   private static Prize rollPuppeteer(ServerPlayer player) {
      int r = RANDOM.nextInt(100);
      if (r < 2) {
         return new Prize("§5§l🎭 Puppeteer's Mask", ModItems.puppeteersMask(), 0L);
      } else if (r < 4) {
         return new Prize("§5§l🎭 Marionette Strings", ModItems.marionetteStrings(), 0L);
      } else if (r < 6) {
         return new Prize("§8§l🎭 The Empty Mask", ModItems.emptyMask(), 0L);
      } else if (r < 14) {
         return new Prize("§f24 String", stack(Items.STRING, 24), 0L);
      } else if (r < 24) {
         ItemStack s = stack(Items.DIAMOND_SWORD, 1);
         enchant(player, s, Enchantments.SHARPNESS, 4);
         enchant(player, s, Enchantments.UNBREAKING, 3);
         return new Prize("§bEnchanted Diamond Sword", s, 0L);
      } else if (r < 34) {
         return new Prize("§b12 Diamonds", stack(Items.DIAMOND, 12), 0L);
      } else if (r < 44) {
         return new Prize("§a32 Emeralds", stack(Items.EMERALD, 32), 0L);
      } else if (r < 54) {
         ItemStack s = stack(Items.ENCHANTED_BOOK, 1);
         enchant(player, s, RANDOM.nextBoolean() ? Enchantments.SHARPNESS : Enchantments.PROTECTION, 4);
         return new Prize("§bEnchanted Book", s, 0L);
      } else if (r < 64) {
         return new Prize("§a24 Experience Bottles", stack(Items.EXPERIENCE_BOTTLE, 24), 0L);
      } else if (r < 74) {
         return new Prize("§7Bread", stack(Items.BREAD, 16), 0L);
      } else if (r < 84) {
         return new Prize("§63 Golden Apples", stack(Items.GOLDEN_APPLE, 3), 0L);
      } else {
         int cash = 900 + RANDOM.nextInt(2101);
         return new Prize("§6$" + cash, ItemStack.EMPTY, cash);
      }
   }

   private static Prize rollRaid(ServerPlayer player) {
      // 200-point table: legendaries are 0.5% each (feel rare), while the
      // Raiders Item Upgrader (6%) and Warlord's Trophy (4%) stay common-ish.
      int r = RANDOM.nextInt(200);
      if (r < 1) {
         return new Prize("§c§lWarlord's Axe", ModItems.warlordAxe(), 0L);
      } else if (r < 2) {
         return new Prize("§5§lEvoker's Spellbook", ModItems.evokerSpellbook(), 0L);
      } else if (r < 3) {
         return new Prize("§6§lRaid Captain's Horn", ModItems.captainHorn(), 0L);
      } else if (r < 4) {
         return new Prize("§c§lWarlord's Cloak", ModItems.warlordCloak(), 0L);
      } else if (r < 5) {
         return new Prize("§5§lEvoker's Cloak", ModItems.evokerCloak(), 0L);
      } else if (r < 6) {
         return new Prize("§9§lIllusioner's Spellbook", ModItems.illusionerSpellbook(), 0L);
      } else if (r < 7) {
         return new Prize("§9§lIllusioner's Cloak", ModItems.illusionerCloak(), 0L);
      } else if (r < 19) {
         return new Prize("§6§lRaiders Item Upgrader", ModItems.raidersItemUpgrader(), 0L);
      } else if (r < 27) {
         return new Prize("§c§lWarlord's Trophy", ModItems.warlordTrophy(), 0L);
      } else if (r < 39) {
         ItemStack s = stack(Items.DIAMOND_SWORD, 1);
         enchant(player, s, Enchantments.SHARPNESS, 4);
         enchant(player, s, Enchantments.UNBREAKING, 3);
         return new Prize("§bEnchanted Diamond Sword", s, 0L);
      } else if (r < 51) {
         return new Prize("§b12 Diamonds", stack(Items.DIAMOND, 12), 0L);
      } else if (r < 65) {
         ItemStack s = stack(Items.ENCHANTED_BOOK, 1);
         enchant(player, s, RANDOM.nextBoolean() ? Enchantments.SHARPNESS : Enchantments.PROTECTION, 4);
         return new Prize("§bEnchanted Book", s, 0L);
      } else if (r < 81) {
         return new Prize("§a24 Experience Bottles", stack(Items.EXPERIENCE_BOTTLE, 24), 0L);
      } else if (r < 97) {
         return new Prize("§e32 Emeralds", stack(Items.EMERALD, 32), 0L);
      } else if (r < 113) {
         return new Prize("§63 Golden Apples", stack(Items.GOLDEN_APPLE, 3), 0L);
      } else if (r < 143) {
         return new Prize("§d24 Gold Ingots", stack(Items.GOLD_INGOT, 24), 0L);
      } else if (r < 173) {
         return new Prize("§f32 Iron Ingots", stack(Items.IRON_INGOT, 32), 0L);
      } else {
         return new Prize("§6$" + (900 + RANDOM.nextInt(2101)), ItemStack.EMPTY, 900 + RANDOM.nextInt(2101));
      }
   }

   /** The Time Lord's table. The three legendaries are 2% each; the Aging Tome
    *  is the signature consolation prize, and the rest is time-flavoured junk. */
   private static Prize rollTime(ServerPlayer player) {
      int r = RANDOM.nextInt(100);
      if (r < 2) {
         return new Prize("§d§lPocket-Watch", ModItems.pocketWatch(1), 0L);
      } else if (r < 4) {
         return new Prize("§b§lChrono Shard", ModItems.chronoShard(), 0L);
      } else if (r < 6) {
         return new Prize("§6§lHourglass of Haste", ModItems.hourglassOfHaste(), 0L);
      } else if (r < 16) {
         int agingLevel = 1 + RANDOM.nextInt(3);
         return new Prize(
            "§5Aging Tome " + com.fortuneandfavors.economy.CustomEnchantments.roman(agingLevel),
            com.fortuneandfavors.economy.CustomEnchantments.tome(com.fortuneandfavors.economy.CustomEnchantments.AGING, agingLevel),
            0L
         );
      } else if (r < 24) {
         ItemStack s = stack(Items.DIAMOND_SWORD, 1);
         enchant(player, s, Enchantments.SHARPNESS, 4);
         enchant(player, s, Enchantments.UNBREAKING, 3);
         return new Prize("§bEnchanted Diamond Sword", s, 0L);
      } else if (r < 32) {
         return new Prize("§b12 Diamonds", stack(Items.DIAMOND, 12), 0L);
      } else if (r < 42) {
         return new Prize("§b16 Amethyst Shards", stack(Items.AMETHYST_SHARD, 16), 0L);
      } else if (r < 50) {
         return new Prize("§5Echo Shards", stack(Items.ECHO_SHARD, 4), 0L);
      } else if (r < 58) {
         ItemStack s = stack(Items.ENCHANTED_BOOK, 1);
         enchant(player, s, RANDOM.nextBoolean() ? Enchantments.SHARPNESS : Enchantments.PROTECTION, 4);
         return new Prize("§bEnchanted Book", s, 0L);
      } else if (r < 68) {
         return new Prize("§a24 Experience Bottles", stack(Items.EXPERIENCE_BOTTLE, 24), 0L);
      } else if (r < 78) {
         return new Prize("§e32 Emeralds", stack(Items.EMERALD, 32), 0L);
      } else if (r < 88) {
         return new Prize("§63 Golden Apples", stack(Items.GOLDEN_APPLE, 3), 0L);
      } else if (r < 95) {
         return new Prize("§d24 Gold Ingots", stack(Items.GOLD_INGOT, 24), 0L);
      } else {
         return new Prize("§6$" + (900 + RANDOM.nextInt(2101)), ItemStack.EMPTY, 900 + RANDOM.nextInt(2101));
      }
   }

   /** The Scarlet Devil's table. Her three legendary weapons are the headline
    *  drops (2% each), the Trophy and her forge material follow, and the rest is
    *  blood-flavoured junk. */
   /**
    * The Clockwork King's table. Sweep odds instead of the usual 2%: his three
    * legendaries are weapons, armour and a trinket, so a box that only ever gave
    * one of them would read as a broken set.
    */
   private static Prize rollClockwork(ServerPlayer player) {
      int r = RANDOM.nextInt(100);
      if (r < 2) {
         return new Prize("§6§l⚙ Clockwork Gauntlet", ModItems.clockworkGauntlet(), 0L);
      } else if (r < 4) {
         return new Prize("§6§l⚙ Mechanical Heart", ModItems.mechanicalHeart(), 0L);
      } else if (r < 6) {
         return new Prize("§6§l⚙ Automaton Armor", ModItems.automatonArmor(), 0L);
      } else if (r < 8) {
         return new Prize("§6§l⚙ Clockwork King's Trophy", ModItems.clockworkTrophy(), 0L);
      } else if (r < 15) {
         ItemStack scrap = ModItems.mechScrap();
         scrap.setCount(3);
         return new Prize("§63 Mech-Scrap", scrap, 0L);
      } else if (r < 25) {
         ItemStack s = stack(Items.DIAMOND_SWORD, 1);
         enchant(player, s, Enchantments.SHARPNESS, 4);
         enchant(player, s, Enchantments.UNBREAKING, 3);
         return new Prize("§bEnchanted Diamond Sword", s, 0L);
      } else if (r < 35) {
         return new Prize("§b12 Diamonds", stack(Items.DIAMOND, 12), 0L);
      } else if (r < 45) {
         return new Prize("§c24 Redstone", stack(Items.REDSTONE, 24), 0L);
      } else if (r < 55) {
         return new Prize("§616 Copper Ingots", stack(Items.COPPER_INGOT, 16), 0L);
      } else if (r < 62) {
         ItemStack s = stack(Items.ENCHANTED_BOOK, 1);
         enchant(player, s, RANDOM.nextBoolean() ? Enchantments.SHARPNESS : Enchantments.PROTECTION, 4);
         return new Prize("§bEnchanted Book", s, 0L);
      } else if (r < 72) {
         return new Prize("§a24 Experience Bottles", stack(Items.EXPERIENCE_BOTTLE, 24), 0L);
      } else if (r < 82) {
         return new Prize("§7Iron Ore", stack(Items.IRON_INGOT, 24), 0L);
      } else if (r < 90) {
         return new Prize("§63 Golden Apples", stack(Items.GOLDEN_APPLE, 3), 0L);
      } else {
         int cash = 900 + RANDOM.nextInt(2101);
         return new Prize("§6$" + cash, ItemStack.EMPTY, cash);
      }
   }

   /**
    * The Magister's table. Her three legendaries, then her material and trophy,
    * then star-flavoured junk - the same shape as the Clockwork box, so every
    * boss box reads consistently.
    */
   private static Prize rollMagister(ServerPlayer player) {
      int r = RANDOM.nextInt(100);
      if (r < 2) {
         return new Prize("§b§l✨ Starpiercer", ModItems.starpiercer(), 0L);
      } else if (r < 4) {
         return new Prize("§b§l✨ Astral Mantle", ModItems.astralMantle(), 0L);
      } else if (r < 6) {
         return new Prize("§b§l✨ Magister's Codex", ModItems.magistersCodex(), 0L);
      } else if (r < 8) {
         return new Prize("§b§l✨ Starbound Trophy", ModItems.starboundTrophy(), 0L);
      } else if (r < 15) {
         ItemStack essence = ModItems.magicalEssence();
         essence.setCount(3);
         return new Prize("§b3 Magical Essence", essence, 0L);
      } else if (r < 25) {
         ItemStack s = stack(Items.DIAMOND_SWORD, 1);
         enchant(player, s, Enchantments.SHARPNESS, 4);
         enchant(player, s, Enchantments.UNBREAKING, 3);
         return new Prize("§bEnchanted Diamond Sword", s, 0L);
      } else if (r < 35) {
         return new Prize("§b12 Diamonds", stack(Items.DIAMOND, 12), 0L);
      } else if (r < 41) {
         return new Prize("§b16 Amethyst Shards", stack(Items.AMETHYST_SHARD, 16), 0L);
      } else if (r < 45) {
         // Astral: rare on purpose, and the only enchant this mod has that a PvP fight
         // cannot use at all - it is worth nothing against a player.
         int astralLevel = 1 + RANDOM.nextInt(3);
         return new Prize(
            "§d§lAstral Tome " + com.fortuneandfavors.economy.CustomEnchantments.roman(astralLevel),
            com.fortuneandfavors.economy.CustomEnchantments.tome(com.fortuneandfavors.economy.CustomEnchantments.ASTRAL, astralLevel),
            0L
         );
      } else if (r < 55) {
         return new Prize("§924 Lapis Lazuli", stack(Items.LAPIS_LAZULI, 24), 0L);
      } else if (r < 62) {
         ItemStack s = stack(Items.ENCHANTED_BOOK, 1);
         enchant(player, s, RANDOM.nextBoolean() ? Enchantments.SHARPNESS : Enchantments.PROTECTION, 4);
         return new Prize("§bEnchanted Book", s, 0L);
      } else if (r < 72) {
         return new Prize("§a24 Experience Bottles", stack(Items.EXPERIENCE_BOTTLE, 24), 0L);
      } else if (r < 82) {
         return new Prize("§78 Ender Pearls", stack(Items.ENDER_PEARL, 8), 0L);
      } else if (r < 90) {
         return new Prize("§63 Golden Apples", stack(Items.GOLDEN_APPLE, 3), 0L);
      } else {
         int cash = 900 + RANDOM.nextInt(2101);
         return new Prize("§6$" + cash, ItemStack.EMPTY, cash);
      }
   }

   /** The Void Shaper's table: blocks, ore and his three legendaries. */
   private static Prize rollVoidshaper(ServerPlayer player) {
      int r = RANDOM.nextInt(100);
      if (r < 2) {
         return new Prize("§5§l🟯 Void Reaver", ModItems.voidReaver(), 0L);
      } else if (r < 4) {
         return new Prize("§5§l🟯 Colossus Plate", ModItems.colossusPlate(), 0L);
      } else if (r < 6) {
         return new Prize("§5§l🟯 Shaping Sigil", ModItems.shapingSigil(), 0L);
      } else if (r < 8) {
         return new Prize("§5§l🟯 Void Shaper's Trophy", ModItems.colossusTrophy(), 0L);
      } else if (r < 15) {
         ItemStack scrap = ModItems.voidsteelScrap();
         scrap.setCount(3);
         return new Prize("§53 Voidsteel Scrap", scrap, 0L);
      } else if (r < 25) {
         ItemStack s = stack(Items.DIAMOND_SWORD, 1);
         enchant(player, s, Enchantments.SHARPNESS, 4);
         enchant(player, s, Enchantments.UNBREAKING, 3);
         return new Prize("§bEnchanted Diamond Sword", s, 0L);
      } else if (r < 35) {
         return new Prize("§b12 Diamonds", stack(Items.DIAMOND, 12), 0L);
      } else if (r < 45) {
         return new Prize("§832 Obsidian", stack(Items.OBSIDIAN, 32), 0L);
      } else if (r < 55) {
         return new Prize("§77 Deepslate", stack(Items.DEEPSLATE, 7), 0L);
      } else if (r < 62) {
         ItemStack s = stack(Items.ENCHANTED_BOOK, 1);
         enchant(player, s, RANDOM.nextBoolean() ? Enchantments.SHARPNESS : Enchantments.PROTECTION, 4);
         return new Prize("§bEnchanted Book", s, 0L);
      } else if (r < 72) {
         return new Prize("§a24 Experience Bottles", stack(Items.EXPERIENCE_BOTTLE, 24), 0L);
      } else if (r < 82) {
         return new Prize("§a12 Ender Pearls", stack(Items.ENDER_PEARL, 12), 0L);
      } else if (r < 90) {
         return new Prize("§63 Golden Apples", stack(Items.GOLDEN_APPLE, 3), 0L);
      } else {
         int cash = 900 + RANDOM.nextInt(2101);
         return new Prize("§6$" + cash, ItemStack.EMPTY, cash);
      }
   }

   /** The Sovereign's table: emeralds, tribute and his three court items. */
   private static Prize rollSovereign(ServerPlayer player) {
      int r = RANDOM.nextInt(100);
      if (r < 2) {
         return new Prize("§a§l👑 Royal Contract", ModItems.royalContract(), 0L);
      } else if (r < 4) {
         return new Prize("§a§l👑 Sovereign's Bell", ModItems.sovereignsBell(), 0L);
      } else if (r < 6) {
         return new Prize("§a§l👑 Emerald Seal", ModItems.emeraldSeal(), 0L);
      } else if (r < 8) {
         return new Prize("§a§l👑 Sovereign's Trophy", ModItems.sovereignTrophy(), 0L);
      } else if (r < 15) {
         ItemStack tribute = ModItems.royalTribute();
         tribute.setCount(3);
         return new Prize("§a3 Royal Tribute", tribute, 0L);
      } else if (r < 25) {
         ItemStack s = stack(Items.DIAMOND_SWORD, 1);
         enchant(player, s, Enchantments.SHARPNESS, 4);
         enchant(player, s, Enchantments.UNBREAKING, 3);
         return new Prize("§bEnchanted Diamond Sword", s, 0L);
      } else if (r < 35) {
         return new Prize("§b12 Diamonds", stack(Items.DIAMOND, 12), 0L);
      } else if (r < 45) {
         return new Prize("§a32 Emeralds", stack(Items.EMERALD, 32), 0L);
      } else if (r < 55) {
         return new Prize("§616 Gold Ingots", stack(Items.GOLD_INGOT, 16), 0L);
      } else if (r < 62) {
         ItemStack s = stack(Items.ENCHANTED_BOOK, 1);
         enchant(player, s, RANDOM.nextBoolean() ? Enchantments.SHARPNESS : Enchantments.PROTECTION, 4);
         return new Prize("§bEnchanted Book", s, 0L);
      } else if (r < 72) {
         return new Prize("§a24 Experience Bottles", stack(Items.EXPERIENCE_BOTTLE, 24), 0L);
      } else if (r < 82) {
         return new Prize("§7Bread", stack(Items.BREAD, 16), 0L);
      } else if (r < 90) {
         return new Prize("§63 Golden Apples", stack(Items.GOLDEN_APPLE, 3), 0L);
      } else {
         int cash = 900 + RANDOM.nextInt(2101);
         return new Prize("§6$" + cash, ItemStack.EMPTY, cash);
      }
   }

   private static Prize rollScarlet(ServerPlayer player) {
      int r = RANDOM.nextInt(100);
      if (r < 2) {
         return new Prize("§4§lScarlet Fang", ModItems.scarletFang(), 0L);
      } else if (r < 4) {
         return new Prize("§4§lScarlet Grimoire", ModItems.scarletGrimoire(), 0L);
      } else if (r < 6) {
         return new Prize("§4§lBlood Prism", ModItems.bloodPrism(), 0L);
      } else if (r < 8) {
         return new Prize("§4§lScarlet Devil's Trophy", ModItems.scarletTrophy(), 0L);
      } else if (r < 14) {
         ItemStack cores = ModItems.bloodsoakedCore();
         cores.setCount(2);
         return new Prize("§42 Bloodsoaked Cores", cores, 0L);
      } else if (r < 24) {
         ItemStack s = stack(Items.DIAMOND_SWORD, 1);
         enchant(player, s, Enchantments.SHARPNESS, 4);
         enchant(player, s, Enchantments.UNBREAKING, 3);
         return new Prize("§bEnchanted Diamond Sword", s, 0L);
      } else if (r < 34) {
         return new Prize("§b12 Diamonds", stack(Items.DIAMOND, 12), 0L);
      } else if (r < 44) {
         return new Prize("§b16 Ender Pearls", stack(Items.ENDER_PEARL, 16), 0L);
      } else if (r < 52) {
         ItemStack s = stack(Items.ENCHANTED_BOOK, 1);
         enchant(player, s, RANDOM.nextBoolean() ? Enchantments.SHARPNESS : Enchantments.PROTECTION, 4);
         return new Prize("§bEnchanted Book", s, 0L);
      } else if (r < 62) {
         return new Prize("§a24 Experience Bottles", stack(Items.EXPERIENCE_BOTTLE, 24), 0L);
      } else if (r < 72) {
         return new Prize("§e32 Emeralds", stack(Items.EMERALD, 32), 0L);
      } else if (r < 82) {
         return new Prize("§63 Golden Apples", stack(Items.GOLDEN_APPLE, 3), 0L);
      } else if (r < 90) {
         return new Prize("§d24 Gold Ingots", stack(Items.GOLD_INGOT, 24), 0L);
      } else {
         return new Prize("§6$" + (900 + RANDOM.nextInt(2101)), ItemStack.EMPTY, 900 + RANDOM.nextInt(2101));
      }
   }

   private static Prize rollSnow(ServerPlayer player) {
      int r = RANDOM.nextInt(100);
      if (r < 2) {
         return new Prize("§b§lIce Staff", ModItems.iceStaff(), 0L);
      } else if (r < 4) {
         return new Prize("§b§lFrostbound Bow", ModItems.frostboundCrown(), 0L);
      } else if (r < 6) {
         return new Prize("§b§lGlacier Cloak", ModItems.glacierCloak(), 0L);
      } else if (r < 11) {
         ItemStack s = stack(Items.DIAMOND_SWORD, 1);
         enchant(player, s, Enchantments.SHARPNESS, 4);
         enchant(player, s, Enchantments.UNBREAKING, 3);
         return new Prize("§bEnchanted Diamond Sword", s, 0L);
      } else if (r < 17) {
         return new Prize("§b8 Diamonds", stack(Items.DIAMOND, 8), 0L);
      } else if (r < 23) {
         return new Prize("§b2 Frozen Hearts", stack(Items.PRISMARINE_CRYSTALS, 2), 0L);
      } else if (r < 29) {
         return new Prize("§bPacked Ice", stack(Items.PACKED_ICE, 16), 0L);
      } else if (r < 35) {
         return new Prize("§fSnow Blocks", stack(Items.SNOW_BLOCK, 16), 0L);
      } else if (r < 42) {
         ItemStack s = stack(Items.ENCHANTED_BOOK, 1);
         enchant(player, s, RANDOM.nextBoolean() ? Enchantments.SHARPNESS : Enchantments.PROTECTION, 4);
         return new Prize("§bEnchanted Book", s, 0L);
      } else if (r < 50) {
         return new Prize("§a24 Experience Bottles", stack(Items.EXPERIENCE_BOTTLE, 24), 0L);
      } else if (r < 58) {
         return new Prize("§f32 Snowballs", stack(Items.SNOWBALL, 32), 0L);
      } else if (r < 66) {
         return new Prize("§e32 Emeralds", stack(Items.EMERALD, 32), 0L);
      } else if (r < 74) {
         return new Prize("§63 Golden Apples", stack(Items.GOLDEN_APPLE, 3), 0L);
      } else if (r < 84) {
         return new Prize("§d24 Gold Ingots", stack(Items.GOLD_INGOT, 24), 0L);
      } else {
         return r < 92
            ? new Prize("§f32 Iron Ingots", stack(Items.IRON_INGOT, 32), 0L)
            : new Prize("§6$" + (700 + RANDOM.nextInt(1901)), ItemStack.EMPTY, 700 + RANDOM.nextInt(1901));
      }
   }

   private static Prize rollSculk(ServerPlayer player) {
      int r = RANDOM.nextInt(100);
      if (r < 1) {
         return new Prize("§d§lThe Echoing Shard", ModItems.distantMemoryShard(), 0L);
      } else if (r < 3) {
         return new Prize("§3§lSculk Mage Staff", ModItems.sculkMageStaff(), 0L);
      } else if (r < 5) {
         return new Prize("§3§lSculk Sensor Leggings", ModItems.sculkSensorLeggings(), 0L);
      } else if (r < 7) {
         return new Prize("§3§lWarden's Call", ModItems.wardensCall(), 0L);
      } else if (r < 12) {
         ItemStack s = stack(Items.DIAMOND_SWORD, 1);
         enchant(player, s, Enchantments.SHARPNESS, 4);
         enchant(player, s, Enchantments.UNBREAKING, 3);
         return new Prize("§bEnchanted Diamond Sword", s, 0L);
      } else if (r < 18) {
         return new Prize("§b8 Diamonds", stack(Items.DIAMOND, 8), 0L);
      } else if (r < 24) {
         return new Prize("§3Sculk Catalyst", stack(Items.SCULK_CATALYST, 2), 0L);
      } else if (r < 30) {
         return new Prize("§3Sculk Essence", stack(Items.ECHO_SHARD, 2), 0L);
      } else if (r < 36) {
         return new Prize("§5Disc Fragments", stack(Items.DISC_FRAGMENT_5, 4), 0L);
      } else if (r < 43) {
         ItemStack s = stack(Items.ENCHANTED_BOOK, 1);
         enchant(player, s, RANDOM.nextBoolean() ? Enchantments.SHARPNESS : Enchantments.PROTECTION, 4);
         return new Prize("§bEnchanted Book", s, 0L);
      } else if (r < 51) {
         return new Prize("§a24 Experience Bottles", stack(Items.EXPERIENCE_BOTTLE, 24), 0L);
      } else if (r < 59) {
         return new Prize("§316 Echo Shards", stack(Items.ECHO_SHARD, 16), 0L);
      } else if (r < 68) {
         return new Prize("§e32 Emeralds", stack(Items.EMERALD, 32), 0L);
      } else if (r < 75) {
         return new Prize("§63 Golden Apples", stack(Items.GOLDEN_APPLE, 3), 0L);
      } else if (r < 85) {
         return new Prize("§d24 Gold Ingots", stack(Items.GOLD_INGOT, 24), 0L);
      } else {
         return r < 93
            ? new Prize("§f32 Iron Ingots", stack(Items.IRON_INGOT, 32), 0L)
            : new Prize("§6$" + (900 + RANDOM.nextInt(2101)), ItemStack.EMPTY, 900 + RANDOM.nextInt(2101));
      }
   }

   private static Prize rollMind(ServerPlayer player) {
      int r = RANDOM.nextInt(100);
      if (r < 1) {
         return new Prize("§5§lStaff of the Mindbinder", ModItems.mindbinderStaff(), 0L);
      } else if (r < 2) {
         return new Prize("§5§lPossessed Mask", ModItems.possessedMask(), 0L);
      } else if (r < 5) {
         return new Prize("§5§lMindbinder's Shroud", ModItems.mindbinderShroud(), 0L);
      } else if (r < 11) {
         ItemStack s = stack(Items.DIAMOND_SWORD, 1);
         enchant(player, s, Enchantments.SHARPNESS, 4);
         enchant(player, s, Enchantments.UNBREAKING, 3);
         return new Prize("§bEnchanted Diamond Sword", s, 0L);
      } else if (r < 17) {
         return new Prize("§b8 Diamonds", stack(Items.DIAMOND, 8), 0L);
      } else if (r < 23) {
         return new Prize("§516 Ender Eyes", stack(Items.ENDER_EYE, 16), 0L);
      } else if (r < 30) {
         return new Prize("§a24 Experience Bottles", stack(Items.EXPERIENCE_BOTTLE, 24), 0L);
      } else if (r < 36) {
         ItemStack s = stack(Items.ENCHANTED_BOOK, 1);
         enchant(player, s, RANDOM.nextBoolean() ? Enchantments.SHARPNESS : Enchantments.PROTECTION, 4);
         return new Prize("§bEnchanted Book", s, 0L);
      } else if (r < 42) {
         return new Prize("§a16 Experience Bottles", stack(Items.EXPERIENCE_BOTTLE, 16), 0L);
      } else if (r < 48) {
         return new Prize("§54 Ender Eyes", stack(Items.ENDER_EYE, 4), 0L);
      } else if (r < 56) {
         return new Prize("§e32 Emeralds", stack(Items.EMERALD, 32), 0L);
      } else if (r < 64) {
         return new Prize("§716 Phantom Membranes", stack(Items.PHANTOM_MEMBRANE, 16), 0L);
      } else if (r < 72) {
         return new Prize("§63 Golden Apples", stack(Items.GOLDEN_APPLE, 3), 0L);
      } else if (r < 82) {
         return new Prize("§d24 Gold Ingots", stack(Items.GOLD_INGOT, 24), 0L);
      } else {
         return r < 90
            ? new Prize("§f32 Iron Ingots", stack(Items.IRON_INGOT, 32), 0L)
            : new Prize("§6$" + (600 + RANDOM.nextInt(1801)), ItemStack.EMPTY, 600 + RANDOM.nextInt(1801));
      }
   }

   private static Prize rollGolem(ServerPlayer player) {
      int r = RANDOM.nextInt(100);
      if (r < 2) {
         return new Prize("§5§lStone Staff", ModItems.stoneStaff(), 0L);
      } else if (r < 4) {
         return new Prize("§5§lGolem's Fist", ModItems.golemFist(), 0L);
      } else if (r < 6) {
         return new Prize("§5§lStoneheart", ModItems.stoneHeart(), 0L);
      } else if (r < 11) {
         ItemStack s = stack(Items.IRON_SWORD, 1);
         enchant(player, s, Enchantments.SHARPNESS, 3);
         enchant(player, s, Enchantments.UNBREAKING, 2);
         return new Prize("§bEnchanted Iron Sword", s, 0L);
      } else if (r < 17) {
         return new Prize("§b8 Diamonds", stack(Items.DIAMOND, 8), 0L);
      } else if (r < 23) {
         return new Prize("§78 Iron Blocks", stack(Items.IRON_BLOCK, 8), 0L);
      } else if (r < 29) {
         return new Prize("§748 Iron Ingots", stack(Items.IRON_INGOT, 48), 0L);
      } else if (r < 35) {
         return new Prize("§724 Stone Bricks", stack(Items.STONE_BRICKS, 24), 0L);
      } else if (r < 41) {
         return new Prize("§a32 Cobblestone", stack(Items.COBBLESTONE, 32), 0L);
      } else if (r < 48) {
         ItemStack s = stack(Items.ENCHANTED_BOOK, 1);
         enchant(player, s, RANDOM.nextBoolean() ? Enchantments.SHARPNESS : Enchantments.PROTECTION, 3);
         return new Prize("§bEnchanted Book", s, 0L);
      } else if (r < 56) {
         return new Prize("§a8 Experience Bottles", stack(Items.EXPERIENCE_BOTTLE, 8), 0L);
      } else if (r < 64) {
         return new Prize("§e32 Emeralds", stack(Items.EMERALD, 32), 0L);
      } else if (r < 72) {
         return new Prize("§a16 Ender Pearls", stack(Items.ENDER_PEARL, 16), 0L);
      } else if (r < 82) {
         return new Prize("§63 Golden Apples", stack(Items.GOLDEN_APPLE, 3), 0L);
      } else {
         return r < 90
            ? new Prize("§f32 Iron Ingots", stack(Items.IRON_INGOT, 32), 0L)
            : new Prize("§6$" + (400 + RANDOM.nextInt(1201)), ItemStack.EMPTY, 400 + RANDOM.nextInt(1201));
      }
   }

   private static Prize rollSlime(ServerPlayer player) {
      int r = RANDOM.nextInt(100);
      if (r < 1) {
         return new Prize("§5§lSlime Launcher", ModItems.slimeLauncher(), 0L);
      } else if (r < 2) {
         return new Prize("§5§lSlime Shield", ModItems.slimeShield(), 0L);
      } else if (r < 3) {
         return new Prize("§5§lSlime Boots", ModItems.slimeBoots(), 0L);
      } else if (r < 8) {
         ItemStack s = stack(Items.IRON_SWORD, 1);
         enchant(player, s, Enchantments.SHARPNESS, 3);
         enchant(player, s, Enchantments.UNBREAKING, 2);
         return new Prize("§bEnchanted Iron Sword", s, 0L);
      } else if (r < 13) {
         return new Prize("§b8 Diamonds", stack(Items.DIAMOND, 8), 0L);
      } else if (r < 18) {
         return new Prize("§d24 Gold Ingots", stack(Items.GOLD_INGOT, 24), 0L);
      } else if (r < 23) {
         return new Prize("§748 Iron Ingots", stack(Items.IRON_INGOT, 48), 0L);
      } else if (r < 28) {
         return new Prize("§a16 Slime Blocks", stack(Items.SLIME_BLOCK, 16), 0L);
      } else if (r < 34) {
         return new Prize("§63 Golden Apples", stack(Items.GOLDEN_APPLE, 3), 0L);
      } else if (r < 40) {
         ItemStack s = stack(Items.ENCHANTED_BOOK, 1);
         enchant(player, s, RANDOM.nextBoolean() ? Enchantments.SHARPNESS : Enchantments.PROTECTION, 3);
         return new Prize("§bEnchanted Book", s, 0L);
      } else if (r < 48) {
         return new Prize("§a8 Experience Bottles", stack(Items.EXPERIENCE_BOTTLE, 8), 0L);
      } else if (r < 56) {
         return new Prize("§e32 Emeralds", stack(Items.EMERALD, 32), 0L);
      } else if (r < 64) {
         return new Prize("§a32 Ender Pearls", stack(Items.ENDER_PEARL, 32), 0L);
      } else if (r < 72) {
         return new Prize("§a64 Slime Balls", stack(Items.SLIME_BALL, 64), 0L);
      } else if (r < 82) {
         return new Prize("§e16 Gold Ingots", stack(Items.GOLD_INGOT, 16), 0L);
      } else {
         return r < 90
            ? new Prize("§f32 Iron Ingots", stack(Items.IRON_INGOT, 32), 0L)
            : new Prize("§6$" + (500 + RANDOM.nextInt(1501)), ItemStack.EMPTY, 500 + RANDOM.nextInt(1501));
      }
   }

   private static Prize rollKing(ServerPlayer player) {
      int r = RANDOM.nextInt(100);
      if (r < 1) {
         return new Prize("§5§lWither Skeleton Staff", ModItems.witherStaff(), 0L);
      } else if (r < 2) {
         return new Prize("§5§lWither Skeleton Blade", ModItems.witherBlade(), 0L);
      } else if (r < 3) {
         return new Prize("§5§lWither Skeleton Crown", ModItems.witherCrown(), 0L);
      } else if (r < 8) {
         return new Prize("§b§l24 Diamonds", stack(Items.DIAMOND, 24), 0L);
      } else if (r < 13) {
         ItemStack s = stack(Items.DIAMOND_SWORD, 1);
         enchant(player, s, Enchantments.SHARPNESS, 4);
         enchant(player, s, Enchantments.UNBREAKING, 3);
         return new Prize("§bEnchanted Diamond Sword", s, 0L);
      } else if (r < 18) {
         return new Prize("§b16 Diamonds", stack(Items.DIAMOND, 16), 0L);
      } else if (r < 23) {
         ItemStack s = stack(Items.BOW, 1);
         enchant(player, s, Enchantments.POWER, 4);
         enchant(player, s, Enchantments.INFINITY, 1);
         return new Prize("§6Enchanted Bow", s, 0L);
      } else if (r < 28) {
         return new Prize("§72 Wither Skeleton Skulls", stack(Items.WITHER_SKELETON_SKULL, 2), 0L);
      } else if (r < 33) {
         return new Prize("§7Sentry Armor Trim", stack(Items.SENTRY_ARMOR_TRIM_SMITHING_TEMPLATE, 1), 0L);
      } else if (r < 38) {
         ItemStack s = stack(Items.ENCHANTED_BOOK, 1);
         enchant(player, s, RANDOM.nextBoolean() ? Enchantments.SHARPNESS : Enchantments.PROTECTION, 5);
         return new Prize("§bEnchanted Book", s, 0L);
      } else if (r < 44) {
         return new Prize("§a8 Emeralds & 16 Gold", stack(Items.EMERALD, 8), 0L);
      } else if (r < 50) {
         return new Prize("§a8 Experience Bottles", stack(Items.EXPERIENCE_BOTTLE, 8), 0L);
      } else if (r < 56) {
         return new Prize("§a24 Ender Pearls", stack(Items.ENDER_PEARL, 24), 0L);
      } else if (r < 62) {
         return new Prize("§6Enchanted Golden Apple", stack(Items.ENCHANTED_GOLDEN_APPLE, 1), 0L);
      } else if (r < 68) {
         ItemStack s = stack(Items.DIAMOND_PICKAXE, 1);
         enchant(player, s, Enchantments.EFFICIENCY, 4);
         enchant(player, s, Enchantments.UNBREAKING, 3);
         return new Prize("§bEnchanted Diamond Pickaxe", s, 0L);
      } else if (r < 74) {
         return new Prize("§63 Golden Apples", stack(Items.GOLDEN_APPLE, 3), 0L);
      } else if (r < 82) {
         return new Prize("§e32 Gold Ingots", stack(Items.GOLD_INGOT, 32), 0L);
      } else {
         return r < 90
            ? new Prize("§f32 Iron Ingots", stack(Items.IRON_INGOT, 32), 0L)
            : new Prize("§6$" + (1000 + RANDOM.nextInt(2001)), ItemStack.EMPTY, 1000 + RANDOM.nextInt(2001));
      }
   }

   private static ItemStack stack(Item item, int count) {
      return new ItemStack(item, count);
   }

   private static ItemStack stack3(ItemStack modItem) {
      ItemStack s = modItem.copy();
      s.setCount(3);
      return s;
   }

   private static ItemStack preview(Item item, String name) {
      ItemStack s = new ItemStack(item);
      s.set(DataComponents.CUSTOM_NAME, Component.literal(name));
      return s;
   }

   private static void enchant(ServerPlayer player, ItemStack stack, ResourceKey<Enchantment> key, int level) {
      try {
         Registry<Enchantment> reg = player.level().registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
         stack.enchant(reg.getOrThrow(key), level);
      } catch (Exception var5) {
      }
   }

   private ItemStack named(Item item, String name) {
      ItemStack stack = new ItemStack(item);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
      return stack;
   }

   private void returnCarried(ServerPlayer player) {
      ItemStack carried = this.getCarried();
      if (!carried.isEmpty()) {
         this.setCarried(ItemStack.EMPTY);
         this.setRemoteCarried(HashedStack.EMPTY);
         InventoryHelper.giveOrDrop(player, carried);
      }
   }

   public void clicked(int slotId, int button, ContainerInput input, Player player) {
      if (player instanceof ServerPlayer sp) {
         if (this.choosing) {
            int idx = chooseIndex(slotId);
            List<ItemStack> pool = this.legendaryPool();
            if (idx < 0 || idx >= pool.size()) {
               if (slotId == 22) {
                  this.returnCarried(sp);
                  sp.closeContainer();
               } else {
                  this.returnCarried(sp);
               }
               return;
            }
            ItemStack give = pool.get(idx).copy();
            InventoryHelper.giveOrDrop(sp, give);
            this.container.setItem(4, this.named(Items.NETHER_STAR, "§6§lYour chosen legendary"));
            this.container.setItem(13, give.copy());
            this.choosing = false;
            this.stopped = true;
            SoundUtil.play(sp, ModSounds.LOOT_REVEAL);
            Chat.raw(sp, "§d§lYou chose:§r §f" + give.getHoverName().getString());
            recordWin(sp, this.family, Band.LEGENDARY, give.getHoverName().getString());
            this.rebuild();
            this.broadcastChanges();
            this.returnCarried(sp);
         } else if (slotId == 4) {
            this.contents = !this.contents;
            SoundUtil.play(sp, ModSounds.PAGE_FLIP);
            this.rebuild();
            this.broadcastChanges();
            this.returnCarried(sp);
         } else if (this.contents) {
            if (slotId == 22) {
               this.returnCarried(sp);
               sp.closeContainer();
            }

            this.returnCarried(sp);
         } else if (slotId == 22) {
            this.finishSpin(sp);
            if (this.stopped && !this.choosing) {
               this.closeAfter = 20;
            }
         } else {
            this.returnCarried(sp);
         }
      } else {
         super.clicked(slotId, button, input, player);
      }
   }

   private String boxName() {
      return switch (this.family) {
         case 14 -> "Drowned Loot Box";
         case 15 -> "Gale Loot Box";
         case 1 -> "Slime King Loot Box";
         case 2 -> "Stone Golem Loot Box";
         case 3 -> "Mindbinder Loot Box";
         case 4 -> "Snow Queen Loot Box";
         case 5 -> "Elder Warden Loot Box";
         case 6 -> "Raid Loot Box";
         case 7 -> "Time Lord Loot Box";
         case 8 -> "Scarlet Devil Loot Box";
         case 9 -> "Clockwork Loot Box";
         case 10 -> "Starbound Loot Box";
         case 11 -> "Void Shaper Loot Box";
         case 12 -> "Sovereign Loot Box";
         case 13 -> "Puppeteer Loot Box";
         default -> "King Wither Skeleton Loot Box";
      };
   }

   private boolean consumeBox(ServerPlayer player) {
      for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
         ItemStack stack = player.getInventory().getItem(i);
         if (this.family == 14 && ModItems.isDrownedLootBox(stack)
            || this.family == 15 && ModItems.isGaleLootBox(stack)
            || this.family == 1 && ModItems.isSlimeLootBox(stack)
            || this.family == 2 && ModItems.isGolemLootBox(stack)
            || this.family == 3 && ModItems.isMindLootBox(stack)
            || this.family == 4 && ModItems.isSnowLootBox(stack)
            || this.family == 5 && ModItems.isSculkLootBox(stack)
            || this.family == 6 && ModItems.isRaidLootBox(stack)
            || this.family == 7 && ModItems.isTimeLordLootBox(stack)
            || this.family == 8 && ModItems.isScarletLootBox(stack)
            || this.family == 9 && ModItems.isClockworkLootBox(stack)
            || this.family == 10 && ModItems.isStarboundLootBox(stack)
            || this.family == 11 && ModItems.isVoidshaperLootBox(stack)
            || this.family == 12 && ModItems.isSovereignLootBox(stack)
            || this.family == 13 && ModItems.isPuppeteerLootBox(stack)
            || this.family == 0 && ModItems.isKingLootBox(stack)
            || this.family == 0 && ModItems.isWitherLootBox(stack)) {
            stack.shrink(1);
            return true;
         }
      }

      return false;
   }

   private void finishSpin(ServerPlayer player) {
      if (this.stopped) {
         SoundUtil.play(player, ModSounds.DENY);
      } else {
         this.stopped = true;

         String pityKey = this.pityKeyFor(player);
         int opens = pityProgress.getOrDefault(pityKey, 0);
         if (opens >= PITY_THRESHOLD) {
            if (!this.consumeBox(player)) {
               SoundUtil.play(player, ModSounds.DENY);
               Chat.msg(player, "&cYou don't have a " + this.boxName() + " anymore - close this window.");
            } else {
               pityProgress.put(pityKey, 0);
               this.choosing = true;
               this.rebuild();
               this.broadcastChanges();
               Chat.raw(player, "§d§lPITY BONUS!§r §7After 10 boxes you may §dchoose§7 your legendary.");
               this.returnCarried(player);
            }
         } else {
            Prize prize = roll(player, this.family, opens);
            Band band = bandOf(this.family, prize);
            boolean legendary = band == Band.LEGENDARY;
            if (!legendary && !this.consumeBox(player)) {
               SoundUtil.play(player, ModSounds.DENY);
               Chat.msg(player, "&cYou don't have a " + this.boxName() + " anymore - close this window.");
            } else {
               if (legendary) {
                  Chat.raw(player, "§d§lJACKPOT!§r §7Your " + this.boxName() + " is kept - a legendary doesn't spend it!");
               }

               int newOpens = legendary ? 0 : opens + 1;
               pityProgress.put(pityKey, newOpens);
               if (!legendary) {
                  // Show the counter moving, and say when the box has warmed up -
                  // the soft-pity draws are real, so they should be visible.
                  player.sendOverlayMessage(
                     Component.literal(pityBar(Math.min(PITY_THRESHOLD, newOpens)) + " §8" + Math.min(PITY_THRESHOLD, newOpens) + "/" + PITY_THRESHOLD + " " + pityWarmth(newOpens))
                  );
               }
               ItemStack display = prize.grant(player);
               display.set(DataComponents.CUSTOM_NAME, Component.literal("§6§l" + prize.label()));
               ItemLore lore = (ItemLore)display.get(DataComponents.LORE);
               List<Component> lines = lore != null ? new ArrayList<>(lore.lines()) : new ArrayList<>();
               // The band goes on the prize itself, at the top, where a screenshot of the reveal
               // keeps it. Before this a player could open two boxes, be handed an abyssal pearl and
               // a stack of sponge, and have nothing on the item to say which of the two mattered.
               lines.add(0, Component.literal(band.header()));
               lines.add(Component.literal("§8Your prize from the Loot Box."));
               display.set(DataComponents.LORE, new ItemLore(lines));
               this.container.setItem(13, display);
               com.fortuneandfavors.net.FfNet.send(player, new com.fortuneandfavors.net.FfScreenFxPayload(com.fortuneandfavors.net.FfScreenFxPayload.FX_LOOT_REVEAL + band.ordinal(), true));
               if (legendary) {
                  SoundUtil.play(player, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.3F, 1.0F);
                  SoundUtil.play(player, SoundEvents.PLAYER_LEVELUP, 1.5F, 1.4F);
                  float[] legChord = new float[]{0.6F, 0.75F, 0.9F, 1.2F, 1.5F, 1.8F, 2.0F};
                  for (float p : legChord) {
                     SoundUtil.play(player, ModSounds.LOOT_TICK, p);
                  }
               } else {
                  SoundUtil.play(player, ModSounds.LOOT_REVEAL);
                  if (band.ordinal() >= Band.EPIC.ordinal()) {
                     float[] chord = new float[]{0.4F, 0.5F, 0.63F, 0.75F, 1.0F, 1.26F, 1.6F};

                     for (float p : chord) {
                        SoundUtil.play(player, ModSounds.LOOT_TICK, p);
                     }

                     SoundUtil.play(player, ModSounds.LOOT_REVEAL, 1.2F + 0.3F * band.ordinal(), 1.2F);
                  }
               }

               if (player.level() instanceof ServerLevel sl) {
                  double x = player.getX();
                  double y = player.getY() + 1.5;
                  double z = player.getZ();
                  com.fortuneandfavors.net.FfVfx.particles(sl, ColorParticleOption.create(ParticleTypes.FLASH, band.dust), x, y, z, 1, 0.0, 0.0, 0.0, 0.0);
                  // Modded clients: a shockwave in the band's colour, a light pillar from Epic up.
                  com.fortuneandfavors.net.FfVfx.shape(sl, com.fortuneandfavors.net.FfVfx.NOVA, ParticleTypes.END_ROD, player.position(), net.minecraft.world.phys.Vec3.ZERO, 2.0 + band.ordinal(), 0.0, band.dust & 0xFFFFFF);
                  if (band.ordinal() >= Band.EPIC.ordinal()) {
                     com.fortuneandfavors.net.FfVfx.shape(sl, com.fortuneandfavors.net.FfVfx.PILLAR, ParticleTypes.END_ROD, player.position(), net.minecraft.world.phys.Vec3.ZERO, legendary ? 8.0 : 5.0, 0.0, band.dust & 0xFFFFFF);
                  }
                  int ring = band.ring;

                  for (int i = 0; i < ring; i++) {
                     double a = (double)i / ring * Math.PI * 2.0;
                     float hue = (float)i / ring;
                     int color = Color.HSBtoRGB(hue, 0.9F, 1.0F) & 16777215;
                     com.fortuneandfavors.net.FfVfx.particles(sl, 
                        new DustParticleOptions(color, 1.0F + 0.5F * band.scale),
                        x + Math.cos(a) * (1.3 * band.scale),
                        y + Math.sin(a * 2.0) * 0.5,
                        z + Math.sin(a) * (1.3 * band.scale),
                        1,
                        0.12,
                        0.12,
                        0.12,
                        0.0
                     );
                     if (band.ordinal() >= Band.EPIC.ordinal()) {
                        com.fortuneandfavors.net.FfVfx.particles(sl, 
                           new DustParticleOptions(color, 1.1F),
                           x + Math.cos(a) * 2.8,
                           y + 0.2 + Math.sin(a * 3.0) * 0.5,
                           z + Math.sin(a) * 2.8,
                           1,
                           0.1,
                           0.1,
                           0.1,
                           0.0
                        );
                     }
                  }

                  com.fortuneandfavors.net.FfVfx.particles(sl, ParticleTypes.ENCHANT, x, y, z, Math.round(55 * band.scale), 1.2, 1.2, 1.2, 0.12);
                  com.fortuneandfavors.net.FfVfx.particles(sl, ParticleTypes.SOUL_FIRE_FLAME, x, y, z, Math.round(25 * band.scale), 0.8, 0.8, 0.8, 0.04);
                  com.fortuneandfavors.net.FfVfx.particles(sl, ParticleTypes.END_ROD, x, y, z, Math.round(22 * band.scale), 0.7, 1.0, 0.7, 0.06);
                  com.fortuneandfavors.net.FfVfx.particles(sl, ParticleTypes.FIREWORK, x, y, z, Math.round(10 * band.scale), 0.5, 0.8, 0.5, 0.08);
                  if (band.ordinal() >= Band.EPIC.ordinal()) {
                     VfxManager.fireworkBurst(sl, x, y, z, Math.round(20 * band.scale));
                  }
               }

               recordWin(player, this.family, band, prize.label());
               Chat.raw(player, "§5&l" + this.boxName() + ":&r §7You got &f" + prize.label() + "§7! §8[" + band.header() + "§8]");
               this.rebuild();
               this.broadcastChanges();
               this.returnCarried(player);
            }
         }
      }
   }

   public void removed(Player player) {
      OPEN.remove(this);
      if (this.choosing && player instanceof ServerPlayer sp) {
         pityReopen.put(sp.getUUID(), this.family);
      }

      super.removed(player);
   }

   public ItemStack quickMoveStack(Player player, int index) {
      return ItemStack.EMPTY;
   }


    record Prize(String label, ItemStack stack, long cash) {
       ItemStack grant(ServerPlayer player) {
          if (this.cash > 0L) {
             EconomyManager.addCash(player.getUUID(), this.cash);
             ItemStack show = new ItemStack(Items.GOLD_INGOT);
             show.set(DataComponents.CUSTOM_NAME, Component.literal("§6§l$" + this.cash));
             show.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("§7Added to your balance."))));
             return show;
          } else {
             ItemStack give = this.stack.copy();
             InventoryHelper.giveOrDrop(player, give);
             return this.stack.copy();
          }
       }
    }
}
