package com.fortuneandfavors.menu;

import com.fortuneandfavors.economy.DailyLoginStreakManager;
import com.fortuneandfavors.util.GuiUtil;
import com.fortuneandfavors.util.InventoryHelper;
import java.util.ArrayList;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

/**
 * What a player sees the first time they join.
 *
 * <p>It used to be six buttons in a single row and a paragraph of prose, which answered "what is
 * here" and never "what do I do first": the six were a shop, a job board, the daily, the bank, a
 * wiki link and a hub, all drawn the same size, in an order that meant nothing. A player who has
 * just arrived has one question, so this screen is arranged as an answer to it - a numbered path
 * along the middle row that starts at the sell chest and ends at the hub, with a row underneath
 * that is only there to say what the server *contains*, so nothing is discovered by accident a
 * week later.
 *
 * <p>The daily step is the one that changes: it says on the face of the card whether a reward is
 * already waiting, because the first thing a new player is told to do should not be a dead end.
 */
public class FirstJoinMenu extends ChestMenu {
   /** The banner, over the frame at the top of the screen. */
   private static final int WELCOME = 4;

   // Row 2 - the path, in the order it should be walked.
   private static final int STEP_SHOP = 10;
   private static final int STEP_JOBS = 11;
   private static final int STEP_REWARDS = 12;
   private static final int STEP_BANK = 13;
   private static final int STEP_TAGS = 14;
   private static final int STEP_HUB = 15;
   private static final int HELP = 16;

   // Row 3 - what else is on this server.
   private static final int SEE_BOSSES = 19;
   private static final int SEE_DUELS = 20;
   private static final int SEE_GUILDS = 21;
   private static final int SEE_EXPEDITIONS = 22;
   private static final int SEE_PRISON = 23;
   private static final int SEE_ECONOMY = 24;
   private static final int CLOSE = 25;

   private final SimpleContainer container;
   private final ServerPlayer owner;

   public FirstJoinMenu(int syncId, Inventory playerInventory) {
      this(syncId, playerInventory, new SimpleContainer(36));
   }

   private FirstJoinMenu(int syncId, Inventory playerInventory, SimpleContainer container) {
      super(MenuType.GENERIC_9x4, syncId, playerInventory, container, 4);
      this.container = container;
      this.owner = (ServerPlayer)playerInventory.player;
      this.rebuild();
   }

   public static void open(ServerPlayer player) {
      player.openMenu(new SimpleMenuProvider((syncId, inv, p) -> new FirstJoinMenu(syncId, inv), Component.literal("§6§lWelcome to Fortune & Favors!")));
   }

   private void rebuild() {
      this.container.clearContent();
      GuiUtil.frames(this.container, 4, Items.STAINED_GLASS_PANE.yellow());

      boolean claimable = DailyLoginStreakManager.canClaim(this.owner.getUUID());
      int day = DailyLoginStreakManager.dayOf(this.owner.getUUID());

      // ---- The banner: what this server is, in one card, and the tip most players need early.
      this.container.setItem(
         WELCOME,
         this.button(
            new ItemStack(Items.WRITTEN_BOOK),
            "§6§lWelcome to Fortune & Favors!",
            "§7Everything below this line opens with a click.",
            "§7Nothing here costs anything to look at.",
            "",
            "§7The short version: §fsell what you mine§7,",
            "§7§ftake jobs and dailies§7 for steady money, then",
            "§7spend it on bosses, duels, expeditions and guilds.",
            "",
            "§8Tip: craft a §fChair§8 from 2 planks to sit anywhere.",
            "§8Tip: §f/ff help§8 lists every feature with examples."
         )
      );

      // ---- Row 2: the path. Numbered, because "do this first" is the whole point of the screen.
      this.container.setItem(
         STEP_SHOP,
         this.button(
            new ItemStack(Items.EMERALD),
            "§a§l1. Visit the Shop",
            "§7Sell what you gather and buy building",
            "§7blocks, tools and exclusive machines.",
            "§8Opens The Market."
         )
      );
      this.container.setItem(
         STEP_JOBS,
         this.button(
            new ItemStack(Items.PAPER),
            "§6§l2. Take a Job",
            "§7Five fresh jobs an hour - mining, killing,",
            "§7chopping, farming and trading.",
            "§7Finish them for cash, gems and rank.",
            "§8Opens the Job Board."
         )
      );
      this.container.setItem(
         STEP_REWARDS,
         this.button(
            claimable ? new ItemStack(Items.DYE.lime()) : new ItemStack(Items.CLOCK),
            claimable ? "§a§l3. Claim Day " + day + " §a§l- READY!" : "§e§l3. Claim Daily Rewards",
            claimable
               ? "§aA reward is waiting right now."
               : day > 0 ? "§7Streak: §fday " + day + "§7 - already claimed." : "§7Log in daily for cash, keys, gems,",
            claimable ? "§7Cash, keys, gems and tags, every day." : "§7tags and titles.",
            "§7Every day of the week pays something;",
            "§7milestones pay a lot more.",
            "§8Opens Daily Rewards."
         )
      );
      this.container.setItem(
         STEP_BANK,
         this.button(
            new ItemStack(Items.GOLD_BLOCK),
            "§e§l4. Open Your Bank",
            "§7Safe vaults for cash (they earn interest)",
            "§7and for XP. Death cannot touch either.",
            "§8Opens Your Bank."
         )
      );
      this.container.setItem(
         STEP_TAGS,
         this.button(
            new ItemStack(Items.NAME_TAG),
            "§b§l5. Pick a Tag & Title",
            "§7Wear a §b[tag]§7 and a §6[title]§7 next to your",
            "§7name in chat and the tab list.",
            "§7Most are unlocked by milestones and streaks.",
            "§8Opens Tags & Titles."
         )
      );
      this.container.setItem(
         STEP_HUB,
         this.button(
            new ItemStack(Items.CHEST),
            "§6§l6. Open the Menu Hub",
            "§7Everything in one place: shops, jobs,",
            "§7claims, bosses, guilds, market, records.",
            "§8Worth a look once - it is the map.",
            "§8Aliases: /menu · /ff menu"
         )
      );
      this.container.setItem(
         HELP,
         this.button(
            new ItemStack(Items.BOOK),
            "§b§lRead the Wiki",
            "§7Type §f/ff help§7 in chat for the topic list,",
            "§7or §f/ff help <topic>§7 for one of them.",
            "§8The hub's §fGuide§8 tile does the same."
         )
      );

      // ---- Row 3: what exists. Nothing to click, so nothing discovered by accident.
      this.container.setItem(
         SEE_BOSSES,
         this.sight(
            Items.NETHER_STAR,
            "§cBosses",
            "§7Server bosses drop forged gear and",
            "§7tokens. Summon them from the hub."
         )
      );
      this.container.setItem(
         SEE_DUELS,
         this.sight(
            Items.IRON_SWORD,
            "§bDuels",
            "§7Fight players in kit battles and Bed Wars,",
            "§7with a leaderboard and a totem mechanic."
         )
      );
      this.container.setItem(
         SEE_GUILDS,
         this.sight(
            Items.SHIELD,
            "§dGuilds",
            "§7Team up for shared perks, guild skills,",
            "§7a guild bank and a guild level."
         )
      );
      this.container.setItem(
         SEE_EXPEDITIONS,
         this.sight(
            Items.COMPASS,
            "§5Expeditions",
            "§7Timed runs through generated dungeons.",
            "§7Go deep, loot it, walk back to the pad."
         )
      );
      this.container.setItem(
         SEE_PRISON,
         this.sight(
            Items.IRON_PICKAXE,
            "§4The Cell Block",
            "§7A prison mode with its own cash, ranks",
            "§7and sectors. Mine the seam, mind the Heat."
         )
      );
      this.container.setItem(
         SEE_ECONOMY,
         this.sight(
            Items.GOLD_INGOT,
            "§6Market & Claims",
            "§7Player chest shops, an auction house,",
            "§7and land claims to build on in peace."
         )
      );
      this.container.setItem(
         CLOSE,
         this.button(
            new ItemStack(Items.BARRIER),
            "§cClose",
            "§8You only see this screen once - the",
            "§8Menu Hub (§f/menu§8) has all of it after this."
         )
      );
      this.broadcastChanges();
   }

   /** A card that opens something. */
   private ItemStack button(ItemStack item, String name, String... lines) {
      return this.labelled(item, name, lines);
   }

   /**
    * A card that is only there to be read.
    *
    * <p>A separate method rather than a flag on {@link #button} so the two rows cannot drift: the
    * middle row is the path and every card on it does something, the bottom row is what the server
    * contains and none of it does. A reader who clicks a bottom-row card and gets nothing has been
    * told the truth; a reader who clicks a path card and gets nothing has found a bug.
    */
   private ItemStack sight(net.minecraft.world.item.Item item, String name, String... lines) {
      return this.labelled(new ItemStack(item), name, lines);
   }

   private ItemStack labelled(ItemStack item, String name, String... lines) {
      ItemStack stack = item.copy();
      stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
      List<Component> lore = new ArrayList<>();
      for (String l : lines) {
         lore.add(Component.literal(l));
      }
      stack.set(DataComponents.LORE, new ItemLore(lore));
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
         switch (slotId) {
            case STEP_SHOP:
               this.returnCarried(sp);
               ShopMenu.open(sp, com.fortuneandfavors.economy.ShopData.Category.BUILDING, 0);
               return;
            case STEP_JOBS:
               this.returnCarried(sp);
               JobMenu.open(sp);
               return;
            case STEP_REWARDS:
               this.returnCarried(sp);
               DailyRewardsMenu.open(sp);
               return;
            case STEP_BANK:
               this.returnCarried(sp);
               BankMenu.open(sp);
               return;
            case STEP_TAGS:
               this.returnCarried(sp);
               TagsMenu.open(sp);
               return;
            case STEP_HUB:
               this.returnCarried(sp);
               MenuHubMenu.open(sp);
               return;
            case HELP:
               this.returnCarried(sp);
               sp.closeContainer();
               com.fortuneandfavors.util.Chat.raw(sp, "&6&lFortune & Favors wiki&r &7- run &e/ff help <topic>&7, or &e/ff help&7 for the list.");
               return;
            case CLOSE:
               this.returnCarried(sp);
               sp.closeContainer();
               return;
            default:
               this.returnCarried(sp);
               return;
         }
      } else {
         super.clicked(slotId, button, input, player);
      }
   }

   public ItemStack quickMoveStack(Player player, int index) {
      return ItemStack.EMPTY;
   }
}
