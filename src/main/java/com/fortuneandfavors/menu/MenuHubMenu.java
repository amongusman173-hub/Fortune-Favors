package com.fortuneandfavors.menu;

import com.fortuneandfavors.ModItems;
import com.fortuneandfavors.ModSounds;
import com.fortuneandfavors.economy.EconomyManager;
import com.fortuneandfavors.economy.ModConfig;
import com.fortuneandfavors.economy.ShopData.Category;
import com.fortuneandfavors.menu.LotteryMenu;
import com.fortuneandfavors.util.Chat;
import com.fortuneandfavors.util.GuiUtil;
import com.fortuneandfavors.util.InventoryHelper;
import com.fortuneandfavors.util.SoundUtil;
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

public class MenuHubMenu extends ChestMenu {
   private static final int INFO = 4;
   /** The player's own sidebar. Nothing about it is an admin setting, so it sits in the first row
    *  of the hub beside the shop rather than anywhere near the staff windows. */
   private static final int SCOREBOARD = 9;
   private static final int SHOP = 10;
   private static final int SELL = 11;
   private static final int AUCTION = 12;
   private static final int JOBS = 13;
   private static final int TOKEN = 14;
   private static final int SKILLS = 15;
   private static final int CLAIM = 16;
   private static final int PRISON = 17;
   private static final int RAID = 18;
   private static final int DUELS = 19;
   private static final int BOUNTY = 20;
   private static final int GUILD = 21;
   private static final int DELIVER = 22;
   private static final int MYSTERY = 23;
   private static final int NEWS = 24;
   private static final int RECIPES = 25;
   private static final int STREAK = 27;
   private static final int CODEX = 28;
   private static final int CHALLENGES = 29;
   private static final int RECORDS = 30;
   private static final int TAGS = 31;
   private static final int TRADE = 32;
   private static final int EXPEDITION = 33;
   private static final int COSMETICS = 34;
   private static final int LEADERBOARD = 35;
   private static final int GEMSHOP = 36;
   private static final int LOTTERY = 37;
   private static final int BANK = 26;
   private static final int STOCKS = 38;
   private static final int CLOSE = 44;
   private final SimpleContainer container;
   private final ServerPlayer owner;

   public MenuHubMenu(int syncId, Inventory playerInventory) {
      this(syncId, playerInventory, new SimpleContainer(45));
   }

   private MenuHubMenu(int syncId, Inventory playerInventory, SimpleContainer container) {
      super(MenuType.GENERIC_9x5, syncId, playerInventory, container, 5);
      this.container = container;
      this.owner = (ServerPlayer)playerInventory.player;
      this.rebuild();
   }

   public static void open(ServerPlayer player) {
      player.openMenu(new SimpleMenuProvider((syncId, inv, p) -> new MenuHubMenu(syncId, inv), Component.literal("§6§lFortune & Favors menu")));
   }

   private void rebuild() {
      this.container.clearContent();
      GuiUtil.frames(this.container, 5, Items.STAINED_GLASS_PANE.yellow());
      ItemStack info = new ItemStack(Items.BOOK);
      info.set(DataComponents.CUSTOM_NAME, Component.literal("§e§lWelcome!"));
      info.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Everything in one place."),
               Component.literal("§7Pick a window - each one also has"),
               Component.literal("§7a command if you prefer typing."),
               Component.literal("§8Balance: §f" + EconomyManager.balance(this.owner.getUUID()))
            )
         )
      );
      this.container.setItem(INFO, info);
      this.container.setItem(
         SCOREBOARD,
         this.featureButton(
            Items.ITEM_FRAME,
            "§e§lYour Sidebar",
            "scoreboard",
            "§7Your own scoreboard: what is on it,",
            "§7in what order, and what it says.",
            "§8Only you ever see your board."
         )
      );
      this.container.setItem(SHOP, this.featureButton(Items.EMERALD, "§aShop", "shop", "§7Browse and buy anything for sale."));
      this.container.setItem(SELL, this.featureButton(Items.GOLD_INGOT, "§eSell", "shop", "§7Throw items in, close to get paid."));
      this.container.setItem(AUCTION, this.featureButton(Items.GOLD_BLOCK, "§dAuction House", "auction", "§7Bid, buy, or list your own loot."));
      this.container.setItem(JOBS, this.featureButton(Items.PAPER, "§6Job Board", "jobs", "§7Daily tasks with cash rewards."));
      this.container.setItem(TOKEN, this.featureButton(Items.WRITABLE_BOOK, "§6Favor Tokens", "token", "§7Mint your own special currency."));
      this.container.setItem(SKILLS, this.featureButton(Items.EXPERIENCE_BOTTLE, "§bSkills", "skills", "§7Level up, earn mystery boxes."));
      this.container.setItem(CLAIM, this.featureButton(Items.GOLDEN_SHOVEL, "§dClaims & Land", "claims", "§7Claim chunks, manage permissions and protect your base."));
      this.container.setItem(PRISON, this.featureButton(Items.IRON_BARS, "§cThe Cell Block", null, "§7Prison: rank A to Z, launder your Heat before the guards find you,", "§7work contracts, and break out of the mine for a jackpot."));
      this.container
         .setItem(RAID, this.featureButton(Items.WITHER_SKELETON_SKULL, "§cRaid Bosses", "boss", "§7How to summon each raid boss, and what they drop."));
      this.container.setItem(DUELS, this.featureButton(Items.IRON_SWORD, "§bDuel Records", "duels", "§7Your duel stats and the leaderboards."));
      this.container.setItem(BOUNTY, this.featureButton(Items.PLAYER_HEAD, "§cBounty Board", "bounty", "§7Who's wanted, and how to place a bounty."));
      this.container.setItem(GUILD, this.featureButton(Items.SHIELD, "§6Guild", "guild", "§7Your crew, scores, mine, and settings."));
      this.container.setItem(DELIVER, this.featureButton(Items.HOPPER, "§eDeliveries", null, "§7Fulfil dynamic contracts for cash."));
      this.container.setItem(MYSTERY, this.featureButton(Items.ENDER_CHEST, "§dMystery Chests", null, "§7Open chests with keys for epic loot."));
      this.container.setItem(NEWS, this.featureButton(Items.WRITTEN_BOOK, "§6Server News", null, "§7Read today's paper, fresh at midnight."));
      this.container.setItem(RECIPES, this.featureButton(Items.CRAFTING_TABLE, "§eRecipes", null, "§7Every custom crafting & forging recipe in one place."));
      this.container.setItem(STREAK, this.featureButton(Items.CLOCK, "§6Daily Streak", null, "§7Login streaks, duel wins and titles."));
      this.container.setItem(CODEX, this.featureButton(Items.BOOK, "§dBoss Codex", null, "§7Every boss ever slain, and who did it."));
      this.container
         .setItem(CHALLENGES, this.featureButton(Items.EXPERIENCE_BOTTLE, "§bChallenges", null, "§7Daily & weekly goals with token rewards."));
      this.container.setItem(RECORDS, this.featureButton(Items.DRAGON_HEAD, "§5First Ever Records", null, "§7The permanent hall of firsts."));
      this.container.setItem(TAGS, this.featureButton(Items.NAME_TAG, "§bTags & Titles", "tags", "§7Your tag, titles, and server records."));
      this.container.setItem(TRADE, this.featureButton(Items.EMERALD_BLOCK, "§aTrading", "trade", "§7Safely trade items and cash: §f/trade <player>§7."));
      this.container.setItem(EXPEDITION, this.featureButton(Items.COMPASS, "§bExpeditions", null, "§7Descend, loot, escape alive - or lose it all."));
      this.container.setItem(COSMETICS, this.featureButton(Items.NAME_TAG, "§dCosmetics", null, "§7Titles, name colors and trails from chests."));
      this.container.setItem(LEADERBOARD, this.featureButton(Items.NETHER_STAR, "§6Leaderboard", null, "§7Weekly rankings with cash payouts."));
      long gems = EconomyManager.gemBalance(this.owner.getUUID());
      ItemStack gemShop = new ItemStack(Items.EMERALD);
      gemShop.set(DataComponents.CUSTOM_NAME, Component.literal("§5§lGem Shop"));
      gemShop.set(DataComponents.LORE, new ItemLore(List.of(
         Component.literal("§7Spend gems on exclusive items."),
         Component.literal("§7Your gems: §5" + gems),
         Component.literal("§8Click to open")
      )));
      this.container.setItem(GEMSHOP, gemShop);
      this.container.setItem(LOTTERY, this.featureButton(Items.GOLD_NUGGET, "§6Lottery", null,
         "§7Weekly jackpot - buy tickets, win the pot.",
         "§7Pot: §f$" + com.fortuneandfavors.economy.LotteryManager.pool()
            + "§7 · jackpot: §f$" + com.fortuneandfavors.economy.LotteryManager.jackpotEstimate(),
         "§7Players in: §f" + com.fortuneandfavors.economy.LotteryManager.buyers()
            + "§7 · your tickets: §f" + com.fortuneandfavors.economy.LotteryManager.ticketsOf(this.owner),
         "§8More players buying = an even bigger pot"));
      this.container.setItem(BANK, this.featureButton(Items.GOLD_BLOCK, "§e§lBank", null, "§7Safe vaults for cash (with interest) §7and XP."));
      this.container
         .setItem(
            STOCKS,
            this.featureButton(
               Items.NETHER_STAR,
               "§6§lStocks §8· The Exchange",
               null,
               "§7Buy and sell shares in eight mining listings.",
               "§7A new quote every 5 minutes, with a chart",
               "§7for every listing - and the market keeps",
               "§7moving while you are offline."
            )
         );
      this.container.setItem(CLOSE, this.named(Items.BARRIER, "§cClose"));
   }

   private ItemStack featureButton(Item item, String name, String feature, String... loreLines) {
      boolean on = feature == null || ModConfig.is(feature);
      ItemStack stack = new ItemStack(on ? item : Items.DYE.gray());
      stack.set(DataComponents.CUSTOM_NAME, Component.literal(on ? name : "§8" + ModConfig.displayName(feature)));
      List<Component> lines = new java.util.ArrayList<>();
      if (on) {
         for (String line : loreLines) {
            lines.add(Component.literal(line));
         }
         lines.add(Component.literal("§8Click to open"));
      } else {
         lines.add(Component.literal("§7Disabled on this server."));
         lines.add(Component.literal("§8(enable it in /fortuneandfavors config)"));
      }
      stack.set(DataComponents.LORE, new ItemLore(lines));
      return stack;
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

   private static boolean gate(ServerPlayer sp, String feature) {
      if (ModConfig.is(feature)) {
         return true;
      } else {
         SoundUtil.play(sp, ModSounds.DENY);
         return false;
      }
   }

   public void clicked(int slotId, int button, ContainerInput input, Player player) {
      if (player instanceof ServerPlayer sp) {
         switch (slotId) {
            case SCOREBOARD:
               if (gate(sp, "scoreboard")) {
                  ScoreboardEditMenu.open(sp);
               }
               break;
            case SHOP:
               if (gate(sp, "shop")) {
                  ShopMenu.open(sp, Category.BUILDING, 0);
               }
               break;
            case SELL:
               if (gate(sp, "shop")) {
                  SellMenu.open(sp);
               }
               break;
            case AUCTION:
               if (gate(sp, "auction")) {
                  AuctionMenu.open(sp);
               }
               break;
            case JOBS:
               if (gate(sp, "jobs")) {
                  JobMenu.open(sp);
               }
               break;
            case TOKEN:
               if (gate(sp, "token")) {
                  TokenMenu.open(sp);
               }
               break;
            case SKILLS:
               if (gate(sp, "skills")) {
                  SkillMenu.open(sp);
               }
               break;
            case CLAIM:
               if (gate(sp, "claims")) {
                  ClaimMenu.open(sp);
               }
               break;
            case PRISON:
               com.fortuneandfavors.economy.PrisonManager.enter(sp);
               break;
            case RAID:
               if (gate(sp, "boss")) {
                  RaidBossHelpMenu.open(sp);
               }
               break;
            case DUELS:
               if (gate(sp, "duels")) {
                  DuelStatsMenu.open(sp);
               }
               break;
            case BOUNTY:
               if (gate(sp, "bounty")) {
                  BountyMenu.open(sp);
               }
               break;
            case GUILD:
               GuildMenu.open(sp);
               break;
            case DELIVER:
               DeliverMenu.open(sp);
               break;
            case MYSTERY:
               MysteryChestMenu.open(sp);
               break;
            case NEWS:
               NewsMenu.open(sp);
               break;
            case RECIPES:
               RecipeMenu.open(sp);
               break;
            case STREAK:
               StreakMenu.open(sp);
               break;
            case CODEX:
               CodexMenu.open(sp);
               break;
            case CHALLENGES:
               ChallengesMenu.open(sp);
               break;
            case RECORDS:
               RecordsMenu.open(sp);
               break;
            case TAGS:
               if (gate(sp, "tags")) {
                  TagsMenu.open(sp);
               }
               break;
            case TRADE:
               if (gate(sp, "trade")) {
                  Chat.msg(sp, "&eType &f/trade <player>&e to start a safe trade with someone nearby.");
               }
               break;
            case EXPEDITION:
               ExpeditionMenu.open(sp);
               break;
            case COSMETICS:
               CosmeticsMenu.open(sp);
               break;
            case LEADERBOARD:
               LeaderboardMenu.open(sp);
               break;
            case GEMSHOP:
               GemShopMenu.open(sp);
               break;
            case LOTTERY:
               LotteryMenu.open(sp);
               break;
            case BANK:
               BankMenu.open(sp);
               break;
            case STOCKS:
               StockMarketMenu.open(sp);
               break;
            case CLOSE:
               this.returnCarried(sp);
               sp.closeContainer();
               return;
            default:
               this.returnCarried(sp);
               break;
         }

         this.returnCarried(sp);
      } else {
         super.clicked(slotId, button, input, player);
      }
   }

   public ItemStack quickMoveStack(Player player, int index) {
      return ItemStack.EMPTY;
   }
}
