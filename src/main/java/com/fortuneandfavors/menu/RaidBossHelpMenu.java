package com.fortuneandfavors.menu;

import com.fortuneandfavors.ModItems;
import com.fortuneandfavors.util.InventoryHelper;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.HashedStack;
import net.minecraft.network.chat.Component;
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

public class RaidBossHelpMenu extends ChestMenu {
   /**
    * Every page of this window, by key - one list.
    *
    * <p>The keys are the strings {@link #buildRecipe} switches on, and the list is read by
    * the audit that insists a page only ever draws a recipe that actually ships. A row of a
    * help window is not a thing the build can see, which is how the Puppeteer's page came to
    * describe a craft - eight string around a carved pumpkin - that had no recipe behind it.
    */
   public static final List<String> PAGES = List.of(
      "king", "slime", "golem", "mindbinder", "snow", "sculk",
      "timelord", "scarlet", "clockwork", "magister", "voidshaper", "sovereign", "puppeteer"
   );
   private static final int INFO = 18;
   private static final int CLOSE = 26;
   private static final int BACK = 0;
   private static final int ARROW = 15;
   private static final int RESULT = 16;

   /**
    * The overview's buttons: one row per page, and the only place either half of a button is
    * written - the cell it is drawn in, and the page a click on that cell opens.
    *
    * <p>These used to be two lists that had to be kept in step by hand, and they were not: the
    * five newest boss pages were drawn in the second row and only the first eight cells had a
    * click route, so Clockwork, Magister, Void Shaper, Sovereign and Puppeteer showed a token
    * that answered nothing - "the new recipes are not in /raidboss" - and the Puppeteer's own
    * cell was then painted over by the padding loop, so even the token was invisible. A button
    * is one row here, so a page without a place to click it, or a cell drawn over by the
    * padding, is not something this window can express.
    */
   private record Button(int slot, String page, java.util.function.Supplier<ItemStack> token, List<String> lore) {
   }

   private static final List<Button> BUTTONS = List.of(
      new Button(1, "scarlet", ModItems::scarletBlood, List.of(
         "§4The §4Scarlet Devil§7 - endgame.",
         "§7She drinks your blood to heal, and when",
         "§7you die her bone servant wears your gear.",
         "§7Phase two brings the §4blood rain§7.",
         "§7Drops her Trophy, Bloodsoaked Cores",
         "§7and her three legendary weapons:",
         "§7the Fang, the Grimoire and the Blood Prism.",
         "§8Click for the crafting recipe"
      )),
      new Button(2, "king", ModItems::raidBossToken, List.of(
         "§7The §cKing Wither Skeleton§7 - endgame.",
         "§7Drops King Loot Boxes, Wither Essence,",
         "§7wither staff / blade / crown.",
         "§8Click for the crafting recipe"
      )),
      new Button(3, "slime", ModItems::slimeBossToken, List.of(
         "§7The §aSlime King§7 - early-mid game.",
         "§7Drops Slime Loot Boxes, Mythical Gelatin,",
         "§7launcher / shield / boots.",
         "§8Click for the crafting recipe"
      )),
      new Button(4, "golem", ModItems::stoneGolemToken, List.of(
         "§7The §8Stone Golem§7 - early game.",
         "§7Drops Golem Loot Boxes, Golem Cores,",
         "§7stone staff / fist / stoneheart.",
         "§8Click for the crafting recipe"
      )),
      new Button(5, "mindbinder", ModItems::mindbinderEye, List.of(
         "§7The §5Mindbinder§7 - mid game.",
         "§7Fight it in diamond / netherite.",
         "§7Fighters earn Mindbinder Loot Boxes; it",
         "§7drops Shattered Minds (forge material).",
         "§8Click for the crafting recipe"
      )),
      new Button(6, "snow", ModItems::snowQueenToken, List.of(
         "§bThe §bSnow Queen§7 - endgame.",
         "§7She freezes you, drags you into her realm,",
         "§7and her frozen servants hunt you there.",
         "§7Drops Frozen Hearts, the Ice Staff,",
         "§7Frostbound Bow and Glacier Cloak.",
         "§8Click for the crafting recipe"
      )),
      new Button(7, "sculk", ModItems::sculkMedallion, List.of(
         "§8The §8Elder Warden§7 - endgame.",
         "§7A dark medallion pulsing with sculk energy.",
         "§7Summons a terrifying sculk boss.",
         "§8Click for the crafting recipe"
      )),
      new Button(8, "timelord", ModItems::spaceTimeRift, List.of(
         "§dThe §dTime Lord§7 - endgame.",
         "§7He stops time: while it is stopped you",
         "§7cannot move or attack - only watch.",
         "§7Drops his Loot Box (Pocket-Watch,",
         "§7Chrono Shard, Hourglass of Haste).",
         "§8Click for the crafting recipe"
      )),
      // The five newest fights get the second row, so a player who has just run out of the
      // early bosses can see what is left to summon.
      new Button(9, "clockwork", ModItems::clockworkCore, List.of(
         "§6The §6Clockwork King§7 - endgame.",
         "§7His armour IS his machinery: every live",
         "§7turret, blade, piston and drone is a tier",
         "§7of Resistance. Tear them off him.",
         "§8Click for the crafting recipe"
      )),
      new Button(10, "magister", ModItems::astralCompass, List.of(
         "§bThe §bStarbound Magister§7 - endgame.",
         "§7A wandering fight with no arena at all.",
         "§7Silence her Astral Energy before it",
         "§7overloads, or she spends all six at once.",
         "§8Click for the crafting recipe"
      )),
      new Button(11, "voidshaper", ModItems::voidAnchor, List.of(
         "§5The §5Void Shaper§7 - endgame.",
         "§7He does not shoot fireballs. He rips",
         "§7blocks out of the world and throws them -",
         "§7and the block decides what the blow does.",
         "§8Click for the crafting recipe"
      )),
      new Button(12, "sovereign", ModItems::sovereignsCrown, List.of(
         "§aThe §aEmerald Sovereign§7 - endgame.",
         "§7He fights with subjects: a bell that",
         "§7staggers you, and Royal Guards who fight",
         "§7for him. Break the court to break him.",
         "§8Click for the crafting recipe"
      )),
      new Button(13, "puppeteer", ModItems::woodenMarionette, List.of(
         "§5The §5Puppeteer§7 - endgame.",
         "§7He ties strings to players. A string pulls,",
         "§7and hitting him cuts one off.",
         "§dThree strings is not a pull: he owns your arm",
         "§dand swings it at whoever is next to you.",
         "§7The third string winds up in view - get away",
         "§7from him, or hit him, to stop it landing.",
         "§7From the second phase he also drags the local",
         "§7mobs in on strings and §dhurls them at you§7 -",
         "§7don't be standing where it lands.",
         "§7Die on a string and your own puppet joins him -",
         "§7and it keeps one thing of yours: a bow, a shield,",
         "§7or your totem, and it gets up once more.",
         "§8Click for the crafting recipe"
      ))
   );

   /** The page a click in this cell opens, or null when the cell is not a boss button. */
   public static String pageAt(int slot) {
      for (Button button : BUTTONS) {
         if (button.slot() == slot) {
            return button.page();
         }
      }
      return null;
   }

   /** Every cell the overview draws a boss token in - read by the audit and the self-test. */
   public static List<Integer> buttonSlots() {
      List<Integer> slots = new java.util.ArrayList<>();
      for (Button button : BUTTONS) {
         slots.add(button.slot());
      }
      return slots;
   }

   private final SimpleContainer container;
   private final ServerPlayer owner;
   private String page;

   public RaidBossHelpMenu(int syncId, Inventory playerInventory) {
      this(syncId, playerInventory, new SimpleContainer(27));
   }

   private RaidBossHelpMenu(int syncId, Inventory playerInventory, SimpleContainer container) {
      super(MenuType.GENERIC_9x3, syncId, playerInventory, container, 3);
      this.container = container;
      this.owner = (ServerPlayer)playerInventory.player;
      this.page = null;
      this.rebuild();
   }

   public static void open(ServerPlayer player) {
      player.openMenu(new SimpleMenuProvider((syncId, inv, p) -> new RaidBossHelpMenu(syncId, inv), Component.literal("§6§lRaid Bosses - how to summon them")));
   }

   public static void open(ServerPlayer player, String boss) {
      player.openMenu(new SimpleMenuProvider((syncId, inv, p) -> {
         RaidBossHelpMenu m = new RaidBossHelpMenu(syncId, inv);
         m.page = boss;
         m.rebuild();
         return m;
      }, Component.literal("§6§lRaid Bosses - how to summon them")));
   }

   private void rebuild() {
      this.container.clearContent();
      if (this.page == null) {
         this.buildOverview();
      } else {
         this.buildRecipe(this.page);
      }

      this.broadcastChanges();
   }

   private void buildOverview() {
      ItemStack info = new ItemStack(Items.BOOK);
      info.set(DataComponents.CUSTOM_NAME, Component.literal("§e§lHow to summon"));
      info.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Each raid boss is summoned by right-clicking"),
               Component.literal("§7its token. Click a token here to see the"),
               Component.literal("§7exact 3x3 crafting recipe that makes it."),
               Component.literal("§8The token is consumed on use."),
               Component.literal("§7Gear up before summoning - these fights"),
               Component.literal("§7are the real endgame. Admins can force one"),
               Component.literal("§7with §f/ff event mythic§7 or §f/economy boss spawn§7.")
            )
         )
      );
      this.container.setItem(INFO, info);

      // Every button from the one table, and the padding everywhere the table does not draw:
      // the cell the last button used to sit in was painted over by hand, which is how the
      // Puppeteer's token came to be invisible on a window that claimed to list him.
      for (Button button : BUTTONS) {
         this.container.setItem(button.slot(), this.tokenButton(button.token().get(), button.lore()));
      }
      for (int slot = 0; slot < 27; slot++) {
         if (slot == INFO || slot == CLOSE || pageAt(slot) != null) {
            continue;
         }
         this.container.setItem(slot, this.blackPane());
      }
      this.container.setItem(CLOSE, this.close());
   }

   private ItemStack blackPane() {
      ItemStack stack = new ItemStack(Items.STAINED_GLASS_PANE.black());
      stack.set(DataComponents.CUSTOM_NAME, Component.literal(" "));
      return stack;
   }

   private ItemStack tokenButton(ItemStack token, List<String> lines) {
      ItemStack stack = token.copy();
      stack.set(DataComponents.LORE, new ItemLore(lines.stream().<Component>map(s -> Component.literal(s)).toList()));
      return stack;
   }

   private void buildRecipe(String boss) {
      String name = switch (boss) {
         case "slime" -> "§a§lSlime King";
         case "golem" -> "§8§lStone Golem";
         case "mindbinder" -> "§5§lMindbinder";
         case "snow" -> "§b§lSnow Queen";
         case "sculk" -> "§8§lElder Warden";
         case "timelord" -> "§d§lThe Time Lord";
         case "scarlet" -> "§4§lThe Scarlet Devil";
         case "clockwork" -> "§6§lThe Clockwork King";
         case "magister" -> "§b§lThe Starbound Magister";
         case "voidshaper" -> "§5§lThe Void Shaper";
         case "sovereign" -> "§a§lThe Emerald Sovereign";
         case "puppeteer" -> "§5§lThe Puppeteer";
         default -> "§c§lKing Wither Skeleton";
      };
      ItemStack title = new ItemStack(Items.PAPER);
      title.set(DataComponents.CUSTOM_NAME, Component.literal(name));
      title.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Craft the token in a crafting table,"),
               Component.literal("§7then right-click it anywhere to summon."),
               Component.literal("§8The token is consumed on use.")
            )
         )
      );
      this.container.setItem(15, title);
      int[] cells = new int[]{3, 4, 5, 12, 13, 14, 21, 22, 23};
      List<ItemStack> recipe = recipeOf(boss);

      for (int i = 0; i < cells.length && i < recipe.size(); i++) {
         this.container.setItem(cells[i], recipe.get(i));
      }

      ItemStack result = resultOf(boss);
      this.container.setItem(16, result);
      ItemStack loot = new ItemStack(Items.CHEST);
      loot.set(DataComponents.CUSTOM_NAME, Component.literal("§6§lWhat it drops"));
      loot.set(DataComponents.LORE, new ItemLore(this.dropsOf(boss).stream().<Component>map(s -> Component.literal(s)).toList()));
      this.container.setItem(18, loot);
      this.container.setItem(0, this.back());
      this.container.setItem(26, this.close());
   }

   /**
    * The nine cells this page tells players to build, in crafting-table order.
    *
    * <p>Exposed, and static, for one reason: the audit that insists a page only draws a
    * recipe that actually ships. The Wooden Marionette was documented here as eight string
    * around a carved pumpkin while no recipe for it existed at all, so the page described a
    * craft that did nothing - a bug that is invisible from inside this class and obvious
    * from the datapack beside it.
    */
   public static List<ItemStack> recipeOf(String boss) {
      return switch (boss) {
         case "clockwork" -> List.of(
            grid(Items.IRON_BLOCK, "§fIron Block"),
            grid(Items.REDSTONE_BLOCK, "§cRedstone Block"),
            grid(Items.IRON_BLOCK, "§fIron Block"),
            grid(Items.REDSTONE_BLOCK, "§cRedstone Block"),
            grid(Items.CLOCK, "§eClock"),
            grid(Items.REDSTONE_BLOCK, "§cRedstone Block"),
            grid(Items.IRON_BLOCK, "§fIron Block"),
            grid(Items.REDSTONE_BLOCK, "§cRedstone Block"),
            grid(Items.IRON_BLOCK, "§fIron Block")
         );
         case "magister" -> List.of(
            grid(Items.AMETHYST_SHARD, "§bAmethyst Shard"),
            grid(Items.LAPIS_LAZULI, "§9Lapis Lazuli"),
            grid(Items.AMETHYST_SHARD, "§bAmethyst Shard"),
            grid(Items.LAPIS_LAZULI, "§9Lapis Lazuli"),
            grid(Items.COMPASS, "§7Compass"),
            grid(Items.LAPIS_LAZULI, "§9Lapis Lazuli"),
            grid(Items.AMETHYST_SHARD, "§bAmethyst Shard"),
            grid(Items.LAPIS_LAZULI, "§9Lapis Lazuli"),
            grid(Items.AMETHYST_SHARD, "§bAmethyst Shard")
         );
         case "voidshaper" -> List.of(
            grid(Items.OBSIDIAN, "§8Obsidian"),
            grid(Items.ENDER_PEARL, "§5Ender Pearl"),
            grid(Items.OBSIDIAN, "§8Obsidian"),
            grid(Items.ENDER_PEARL, "§5Ender Pearl"),
            grid(Items.ENDER_EYE, "§5Eye of Ender"),
            grid(Items.ENDER_PEARL, "§5Ender Pearl"),
            grid(Items.OBSIDIAN, "§8Obsidian"),
            grid(Items.ENDER_PEARL, "§5Ender Pearl"),
            grid(Items.OBSIDIAN, "§8Obsidian")
         );
         case "sovereign" -> List.of(
            grid(Items.GOLD_BLOCK, "§6Gold Block"),
            grid(Items.EMERALD_BLOCK, "§aEmerald Block"),
            grid(Items.GOLD_BLOCK, "§6Gold Block"),
            grid(Items.EMERALD_BLOCK, "§aEmerald Block"),
            grid(Items.GOLD_BLOCK, "§6Gold Block"),
            grid(Items.EMERALD_BLOCK, "§aEmerald Block"),
            grid(Items.GOLD_BLOCK, "§6Gold Block"),
            grid(Items.EMERALD_BLOCK, "§aEmerald Block"),
            grid(Items.GOLD_BLOCK, "§6Gold Block")
         );
         case "slime" -> List.of(
            grid(Items.SLIME_BLOCK, "§aSlime Block"),
            grid(Items.SLIME_BLOCK, "§aSlime Block"),
            grid(Items.SLIME_BLOCK, "§aSlime Block"),
            grid(Items.SLIME_BLOCK, "§aSlime Block"),
            grid(Items.GOLDEN_HELMET, "§6Golden Helmet"),
            grid(Items.SLIME_BLOCK, "§aSlime Block"),
            grid(Items.SLIME_BLOCK, "§aSlime Block"),
            grid(Items.SLIME_BLOCK, "§aSlime Block"),
            grid(Items.SLIME_BLOCK, "§aSlime Block")
         );
         case "golem" -> List.of(
            grid(Items.STONE_BRICKS, "§7Stone Bricks"),
            grid(Items.STONE_BRICKS, "§7Stone Bricks"),
            grid(Items.STONE_BRICKS, "§7Stone Bricks"),
            grid(Items.STONE_BRICKS, "§7Stone Bricks"),
            grid(Items.IRON_BLOCK, "§fIron Block"),
            grid(Items.STONE_BRICKS, "§7Stone Bricks"),
            grid(Items.STONE_BRICKS, "§7Stone Bricks"),
            grid(Items.STONE_BRICKS, "§7Stone Bricks"),
            grid(Items.STONE_BRICKS, "§7Stone Bricks")
         );
         case "mindbinder" -> List.of(
            grid(Items.ENDER_EYE, "§5Eye of Ender"),
            ItemStack.EMPTY,
            grid(Items.ENDER_EYE, "§5Eye of Ender"),
            ItemStack.EMPTY,
            grid(Items.ENDER_PEARL, "§aEnder Pearl"),
            ItemStack.EMPTY,
            grid(Items.ENDER_EYE, "§5Eye of Ender"),
            ItemStack.EMPTY,
            grid(Items.ENDER_EYE, "§5Eye of Ender")
         );
         case "snow" -> List.of(
            grid(Items.SNOW_BLOCK, "§fSnow Block"),
            grid(Items.SNOW_BLOCK, "§fSnow Block"),
            grid(Items.SNOW_BLOCK, "§fSnow Block"),
            grid(Items.SNOW_BLOCK, "§fSnow Block"),
            grid(Items.DIAMOND, "§bDiamond"),
            grid(Items.SNOW_BLOCK, "§fSnow Block"),
            grid(Items.SNOW_BLOCK, "§fSnow Block"),
            grid(Items.SNOW_BLOCK, "§fSnow Block"),
            grid(Items.SNOW_BLOCK, "§fSnow Block")
         );
         case "sculk" -> List.of(
            grid(Items.ECHO_SHARD, "§8Echo Shard"),
            grid(Items.GOLD_BLOCK, "§6Gold Block"),
            grid(Items.ECHO_SHARD, "§8Echo Shard"),
            grid(Items.GOLD_BLOCK, "§6Gold Block"),
            grid(Items.SCULK_SENSOR, "§8Sculk Sensor"),
            grid(Items.GOLD_BLOCK, "§6Gold Block"),
            grid(Items.ECHO_SHARD, "§8Echo Shard"),
            grid(Items.GOLD_BLOCK, "§6Gold Block"),
            grid(Items.ECHO_SHARD, "§8Echo Shard")
         );
         case "timelord" -> List.of(
            grid(Items.ECHO_SHARD, "§dEcho Shard"),
            grid(Items.CLOCK, "§fClock"),
            grid(Items.ECHO_SHARD, "§dEcho Shard"),
            grid(Items.CLOCK, "§fClock"),
            grid(Items.AMETHYST_SHARD, "§5Amethyst Shard"),
            grid(Items.CLOCK, "§fClock"),
            grid(Items.ECHO_SHARD, "§dEcho Shard"),
            grid(Items.CLOCK, "§fClock"),
            grid(Items.ECHO_SHARD, "§dEcho Shard")
         );
         case "puppeteer" -> List.of(
            grid(Items.STRING, "§fString"),
            grid(Items.STRING, "§fString"),
            grid(Items.STRING, "§fString"),
            grid(Items.STRING, "§fString"),
            grid(Items.CARVED_PUMPKIN, "§6Carved Pumpkin"),
            grid(Items.STRING, "§fString"),
            grid(Items.STRING, "§fString"),
            grid(Items.STRING, "§fString"),
            grid(Items.STRING, "§fString")
         );
         // The page used to draw eight redstone blocks around a ghast tear, which is not the
         // recipe that ships and not the one the console prints either: the craft is four
         // ghast tears at the corners, four redstone blocks at the edges and the bottle in
         // the middle. A page that draws a craft nobody can build is worse than no page.
         case "scarlet" -> List.of(
            grid(Items.GHAST_TEAR, "§fGhast Tear"),
            grid(Items.REDSTONE_BLOCK, "§cRedstone Block"),
            grid(Items.GHAST_TEAR, "§fGhast Tear"),
            grid(Items.REDSTONE_BLOCK, "§cRedstone Block"),
            grid(Items.GLASS_BOTTLE, "§fGlass Bottle"),
            grid(Items.REDSTONE_BLOCK, "§cRedstone Block"),
            grid(Items.GHAST_TEAR, "§fGhast Tear"),
            grid(Items.REDSTONE_BLOCK, "§cRedstone Block"),
            grid(Items.GHAST_TEAR, "§fGhast Tear")
         );
         default -> List.of(
            grid(Items.COAL, "§8Coal"),
            grid(Items.BONE, "§fBone"),
            grid(Items.COAL, "§8Coal"),
            grid(Items.BONE, "§fBone"),
            grid(Items.WITHER_SKELETON_SKULL, "§8Wither Skeleton Skull"),
            grid(Items.BONE, "§fBone"),
            grid(Items.COAL, "§8Coal"),
            grid(Items.BONE, "§fBone"),
            grid(Items.COAL, "§8Coal")
         );
      };
   }

   private static ItemStack grid(Item item, String name) {
      ItemStack stack = new ItemStack(item);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
      return stack;
   }

   /** What this page says the craft produces - the boss summon item itself. */
   public static ItemStack resultOf(String boss) {
      ItemStack result = switch (boss) {
         case "slime" -> ModItems.slimeBossToken();
         case "golem" -> ModItems.stoneGolemToken();
         case "mindbinder" -> ModItems.mindbinderEye();
         case "snow" -> ModItems.snowQueenToken();
         case "sculk" -> ModItems.sculkMedallion();
         case "timelord" -> ModItems.spaceTimeRift();
         case "scarlet" -> ModItems.scarletBlood();
         case "clockwork" -> ModItems.clockworkCore();
         case "magister" -> ModItems.astralCompass();
         case "voidshaper" -> ModItems.voidAnchor();
         case "sovereign" -> ModItems.sovereignsCrown();
         case "puppeteer" -> ModItems.woodenMarionette();
         default -> ModItems.raidBossToken();
      };
      result.setCount(1);
      return result;
   }

   private List<String> dropsOf(String boss) {
      return switch (boss) {
         case "slime" -> List.of(
            "§7Slime King Loot Boxes",
            "§dMythical Gelatin§7 (forge material)",
            "§aSlime Launcher / Shield / Boots",
            "§8Bounces you around - watch the shockwave."
         );
         case "golem" -> List.of(
            "§7Stone Golem Loot Boxes",
            "§6Golem Cores§7 (forge material)",
            "§7Stone Staff / Golem's Fist / Stoneheart",
            "§8Nigh indestructible while armored - make it",
            "§8stagger itself (Stone Slam, wall charges)."
         );
         case "mindbinder" -> List.of(
            "§5Mindbinder Loot Boxes",
            "§5Shattered Mind§7 (forge material)",
            "§5Staff / Mask / Mindbinder's Shroud",
            "§8Mind games: QTE sneaks, mirror clones,",
            "§8void hands and corruption. Do not stare."
         );
         case "snow" -> List.of(
            "§bSnow Queen Loot Boxes",
            "§bFrozen Heart§7 (forge material)",
            "§bIce Staff / Frostbound Bow / Glacier Cloak",
            "§8Freeze buildup seals you in ice - mine out,",
            "§8or die in her realm and dig your gear out",
            "§8of a tomb of ice. Bring a pickaxe."
         );
         case "sculk" -> List.of(
            "§8Sculk Loot Boxes",
            "§8Sculk Essence§7 (forge material)",
            "§8Sculk Mage Staff / Sculk Sensor Leggings",
            "§8The Elder Warden hunts you in the deep.",
            "§8Beware the sonic boom."
         );
         case "clockwork" -> List.of(
            "§6Clockwork Loot Boxes",
            "§6Mech-Scrap§7 (forge material)",
            "§6Gauntlet / Mechanical Heart / Automaton Armor",
            "§8Kill the machines first: his armour is his",
            "§8arsenal, and his bar only really moves",
            "§8once you have torn it off him."
         );
         case "magister" -> List.of(
            "§bStarbound Loot Boxes",
            "§bMagical Essence§7 (forge material)",
            "§bStarpiercer / Astral Mantle / Magister's Codex",
            "§8No arena: she keeps her distance and blinks",
            "§8when you close. Interrupt her charging or",
            "§8she overloads in your face."
         );
         case "voidshaper" -> List.of(
            "§5Void Shaper Loot Boxes",
            "§5Voidsteel Scrap§7 (forge material)",
            "§5Void Reaver / Colossus Plate / Shaping Sigil",
            "§8Watch what he is holding: ore flies fast,",
            "§8logs throw you far, magma burns. At 40% he",
            "§8stops aiming entirely."
         );
         case "sovereign" -> List.of(
            "§aSovereign Loot Boxes",
            "§aRoyal Tribute§7 (forge material)",
            "§aRoyal Contract / Sovereign's Bell / Emerald Seal",
            "§8His court is his armour. He never takes",
            "§8anything from your inventory - only ground."
         );
         case "puppeteer" -> List.of(
            "§5Puppeteer Loot Boxes",
            "§5Puppeteer's Mask§7 (kills raise a puppet for you)",
            "§5Marionette Strings§7 (tie a target, then pull)",
            "§8The Empty Mask§7 (a decoy takes your death)",
            "§7String, Emeralds and his three legendaries.",
            "§8Strings pull but never hold: keep walking and",
            "§8hit him, and they come off. Run far, it snaps."
         );
         case "timelord" -> List.of(
            "§dTime Lord Loot Boxes",
            "§dPocket-Watch§7 (stop time, 5s → 10s)",
            "§dChrono Shard§7 (rewind position + health)",
            "§dHourglass of Haste§7 (haste, then slowness)",
            "§7Echo Shards, Amethyst, Diamonds,",
            "§7Netherite Scrap and §dAging§7 tomes.",
            "§8He stops time, teleports and throws rifts",
            "§8at you - and time is frozen while he does."
         );
         case "scarlet" -> List.of(
            "§4Scarlet Devil Loot Boxes",
            "§4Scarlet Devil's Trophy§7 (bragging rights)",
            "§4Bloodsoaked Cores§7 (her ONE forge material)",
            "§4Scarlet Fang§7 (a spear: bloodsuck on every hit)",
            "§4Scarlet Grimoire§7 (casts her own move set)",
            "§4Blood Prism§7 (grows you a blood servant)",
            "§7Diamonds, Netherite Scrap and",
            "§cLifesteal§7 tomes.",
            "§8She drinks your blood to heal, and dying in",
            "§8her arena leaves your gear on a bone servant",
            "§8that fights for her until you break it."
         );
         default -> List.of(
            "§cKing Loot Boxes",
            "§5Wither Essence§7 (forge material)",
            "§5Wither Staff / Blade / Crown",
            "§8Phase 2 gets a devour shield - break it with",
            "§83 fully-charged swings or block (it hurts)."
         );
      };
   }

   private ItemStack back() {
      ItemStack stack = new ItemStack(Items.ARROW);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§e§lBack to the boss list"));
      stack.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("§8Click to go back"))));
      return stack;
   }

   private ItemStack close() {
      ItemStack stack = new ItemStack(Items.BARRIER);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§cClose"));
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
         if (this.page == null) {
            // One lookup, from the same table the window draws from: a button that is drawn is
            // always a button that opens its page.
            String opened = pageAt(slotId);
            if (opened != null) {
               this.page = opened;
               this.rebuild();
               return;
            }
         } else if (slotId == BACK) {
            this.page = null;
            this.rebuild();
            return;
         }

         if (slotId == CLOSE) {
            this.returnCarried(sp);
            sp.closeContainer();
         } else if (slotId >= 0 && slotId < 27) {
            this.returnCarried(sp);
         } else {
            super.clicked(slotId, button, input, player);
         }
      } else {
         super.clicked(slotId, button, input, player);
      }
   }

   public ItemStack quickMoveStack(Player player, int index) {
      return ItemStack.EMPTY;
   }
}
