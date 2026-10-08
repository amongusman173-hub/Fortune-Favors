package com.fortuneandfavors.menu;

import com.fortuneandfavors.ModSounds;
import com.fortuneandfavors.economy.BankManager;
import com.fortuneandfavors.economy.BankManager.Account;
import com.fortuneandfavors.economy.EconomyManager;
import com.fortuneandfavors.util.Chat;
import com.fortuneandfavors.util.GuiUtil;
import com.fortuneandfavors.util.InventoryHelper;
import com.fortuneandfavors.util.SoundUtil;
import java.time.Instant;
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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

/** The /bank window, kept deliberately simple:
 *
 *  Row 1 - MONEY:  deposit half/all · meter · withdraw half/all
 *  Row 2 - XP:     deposit 10 levels/all · meter · withdraw 10 levels/all
 *  Row 3 - BANK:   ONE upgrade button (levels 1-5 raise money cap, XP cap and
 *                  interest together) · interest info · help · statement · close */
public class BankMenu extends ChestMenu {
   private static final int INFO = 4;
   // row 1: money
   private static final int M_DEP_ALL = 10;
   private static final int M_DEP_HALF = 11;
   private static final int M_METER = 12;
   private static final int M_WD_HALF = 13;
   private static final int M_WD_ALL = 14;
   // row 2: xp
   private static final int X_DEP_ALL = 19;
   private static final int X_DEP_10 = 20;
   private static final int X_METER = 21;
   private static final int X_WD_10 = 22;
   private static final int X_WD_ALL = 23;
   // row 3: the bank itself
   private static final int STOCKS = 29;
   private static final int UPGRADE = 28;
   private static final int INTEREST = 30;
   private static final int HELP = 32;
   private static final int STATEMENT = 34;
   private static final int CLOSE = 40;

   private final SimpleContainer container;
   private final ServerPlayer owner;

   public BankMenu(int syncId, Inventory playerInventory) {
      this(syncId, playerInventory, new SimpleContainer(45));
   }

   private BankMenu(int syncId, Inventory playerInventory, SimpleContainer container) {
      super(MenuType.GENERIC_9x5, syncId, playerInventory, container, 5);
      this.container = container;
      this.owner = (ServerPlayer)playerInventory.player;
      this.rebuild();
   }

   public static void open(ServerPlayer player) {
      player.openMenu(new SimpleMenuProvider((syncId, inv, p) -> new BankMenu(syncId, inv), Component.literal("§6§lBank · Level " + BankManager.level(BankManager.accountOf(player)))));
   }

   private void rebuild() {
      this.container.clearContent();
      GuiUtil.frames(this.container, 5, Items.STAINED_GLASS_PANE.yellow());
      Account a = BankManager.accountOf(this.owner);
      int level = BankManager.level(a);
      long wallet = EconomyManager.balance(this.owner.getUUID());
      long cap = BankManager.moneyCap(a);
      long cash = Math.min(a.cash(), cap);
      int xpCap = BankManager.xpCap(a);
      int xpStored = Math.min(a.xp(), xpCap);
      long now = Instant.now().getEpochSecond();
      long hoursToInterest = Math.max(0L, 20L - (now - a.lastInterest()) / 3600L);
      long portfolio = BankManager.portfolioValue(this.owner);

      // --- header: one card that says everything ---
      ItemStack info = new ItemStack(Items.GOLD_BLOCK);
      info.set(DataComponents.CUSTOM_NAME, Component.literal("§6§lBank · Level " + level + "§7/§f" + BankManager.maxLevel()));
      info.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Banked cash & XP are §fsafe§7 from death and raids."),
               Component.literal(""),
               Component.literal("§7Wallet: §a" + Chat.moneyStr(wallet)),
               Component.literal("§7Cash vault: §f" + Chat.moneyStr(cash) + " §8/ " + Chat.moneyStr(cap)),
               Component.literal("§7XP vault: §b" + xpStored + " XP §8/ " + xpCap),
               Component.literal("§7Interest: §a" + pct(BankManager.interestRate(a)) + "/day §8(max " + Chat.moneyStr(BankManager.interestCap(a)) + ")"),
               Component.literal(""),
               Component.literal("§7Stock portfolio: §f" + Chat.moneyStr(portfolio)),
               Component.literal("§8The Exchange lives in this vault too."),
               Component.literal("§8Upgrade once in the bottom row - it raises")
            )
         )
      );
      this.container.setItem(INFO, info);

      // --- row 1: money ---
      this.container.setItem(M_DEP_ALL, this.button(Items.EMERALD, "§a§lDeposit ALL", "§7Wallet → vault (stops at capacity).", "", "§7Wallet: §a" + Chat.moneyStr(wallet)));
      this.container.setItem(M_DEP_HALF, this.button(Items.EMERALD, "§a§lDeposit HALF", "§7Half your wallet → vault.", "", "§7Wallet: §a" + Chat.moneyStr(wallet / 2L)));
      this.container.setItem(M_METER, this.moneyMeter(cash, cap, level));
      this.container.setItem(M_WD_HALF, this.button(Items.GOLD_INGOT, "§e§lWithdraw HALF", "§7Half the vault → wallet.", "", "§7Vault: §f" + Chat.moneyStr(cash)));
      this.container.setItem(M_WD_ALL, this.button(Items.GOLD_BLOCK, "§e§lWithdraw ALL", "§7Vault → wallet.", "", "§7Vault: §f" + Chat.moneyStr(cash)));

      // --- row 2: xp ---
      this.container.setItem(X_DEP_ALL, this.button(Items.EXPERIENCE_BOTTLE, "§b§lDeposit ALL XP", "§7Your whole XP bar → vault", "§7(stops at capacity).", "", "§7XP bar: §b" + this.owner.experienceLevel + " levels"));
      this.container.setItem(X_DEP_10, this.button(Items.SLIME_BALL, "§b§lDeposit 10 levels", "§7Banks 10 levels of XP,", "§7exactly as they are."));
      this.container.setItem(X_METER, this.xpMeter(xpStored, xpCap, level));
      this.container.setItem(X_WD_10, this.button(Items.GLASS_BOTTLE, "§d§lWithdraw 10 levels", "§7Takes 10 levels of XP out."));
      this.container.setItem(X_WD_ALL, this.button((Item)Items.DYE.lime(), "§d§lWithdraw ALL XP", "§7Vault → your XP bar."));

      // --- row 3: the bank ---
      boolean max = level >= BankManager.maxLevel();
      long cost = max ? 0L : BankManager.UPGRADE_COSTS[a.tier()];
      this.container
         .setItem(
            UPGRADE,
            max
               ? this.button(Items.EMERALD_BLOCK, "§a§lBank Level " + level + " §8(MAX)", "§7Money cap: §f" + Chat.moneyStr(cap), "§7XP cap: §f" + xpCap, "§7Interest: §a" + pct(BankManager.interestRate(a)) + "/day", "§8Fully upgraded - nice.")
               : this.button(
                  Items.ANVIL,
                  "§e§lUpgrade Bank → Level " + (level + 1),
                  "§7Money cap: §f" + Chat.moneyStr(cap) + " §7→ §f" + Chat.moneyStr(BankManager.MONEY_CAPS[a.tier() + 1]),
                  "§7XP cap: §f" + xpCap + " §7→ §f" + BankManager.XP_CAPS[a.tier() + 1],
                  "§7Interest: §a" + pct(BankManager.interestRate(a)) + " §7→ §a" + pct(BankManager.INTEREST_RATES[a.tier() + 1]) + "/day",
                  "",
                  "§7Cost: §a" + Chat.moneyStr(cost),
                  "§8Click to upgrade"
               )
         );
      this.container
         .setItem(
            STOCKS,
            this.button(
               Items.NETHER_STAR,
               "§6§lStocks §8· The Exchange",
               "§7Buy and sell shares in eight mining",
               "§7listings - a new quote every 5 minutes.",
               "§7Prices move whether or not you log in.",
               "",
               "§7Your shares: §f" + Chat.moneyStr(portfolio),
               "§7Net worth: §6" + Chat.moneyStr(wallet + cash + portfolio)
            )
         );
      this.container
         .setItem(
            INTEREST,
            this.button(
               Items.GOLD_NUGGET,
               "§6§lInterest",
               "§7Next payout: §a+" + Chat.moneyStr(BankManager.interestFor(a)),
               "§7Pays every ~20 real hours (even offline)",
               "§7while you keep cash banked.",
               "§7Missed days are paid on the next visit",
               "§7(down to " + BankManager.MAX_CATCHUP_DAYS + " days).",
               "",
               "§8Next payout in ~" + hoursToInterest + "h"
            )
         );
      this.container
         .setItem(
            HELP,
            this.button(
               Items.BOOK,
               "§e§lBank Levels",
               this.levelLine(1),
               this.levelLine(2),
               this.levelLine(3),
               this.levelLine(4),
               this.levelLine(5),
               "",
               "§8One upgrade raises all three at once."
            )
         );
      // --- the statement: what actually moved, and what the vault is worth over time ---
      this.container.setItem(STATEMENT, this.statement(BankManager.accountOf(this.owner)));
      this.container.setItem(CLOSE, this.button(Items.BARRIER, "§cClose"));
      this.broadcastChanges();
   }

   /**
    * The statement tile: the last movements on this account, then what the vault earns if it is
    * left alone.
    *
    * <p>The interest tile already says what the next payout is going to be; this says what the
    * payouts already were, which is the half a player cannot see anywhere. The projection is
    * compound and capped exactly as the tick is (see {@code BankManager.projectedInterest}), so the
    * number here is one the bank will actually pay rather than a rate multiplied by seven.
    */
   private ItemStack statement(Account a) {
      ItemStack stack = new ItemStack(Items.WRITTEN_BOOK);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§6§lStatement"));
      List<Component> lore = new ArrayList<>();
      List<BankManager.Movement> log = BankManager.statement(this.owner.getUUID());
      if (log.isEmpty()) {
         lore.add(Component.literal("§8Nothing has moved yet."));
         lore.add(Component.literal("§8Deposit something and it shows up here."));
      } else {
         lore.add(Component.literal("§8Newest first - the last " + BankManager.STATEMENT_LIMIT + " movements:"));
         for (BankManager.Movement m : log) {
            boolean inward = "deposit".equals(m.kind()) || "interest".equals(m.kind()) || "sell".equals(m.kind());
            String colour = inward ? "§a+" : "§c-";
            String what = switch (m.kind()) {
               case "deposit" -> "Deposit";
               case "withdraw" -> "Withdraw";
               case "interest" -> "Interest";
               case "upgrade" -> "Upgrade";
               case "buy" -> "Market buy";
               case "sell" -> "Market sale";
               default -> m.kind();
            };
            lore.add(
               Component.literal(
                  "§7" + what + " §r" + colour + Chat.moneyStr(m.amount()) + "§8 · " + m.note() + "§8 · " + ago(m.at())
               )
            );
         }
      }
      lore.add(Component.literal(""));
      lore.add(Component.literal("§7If you leave the vault alone:"));
      lore.add(Component.literal("§8 · 7 days: §a+" + Chat.moneyStr(BankManager.projectedInterest(a, 7))));
      lore.add(Component.literal("§8 · 30 days: §a+" + Chat.moneyStr(BankManager.projectedInterest(a, 30))));
      lore.add(Component.literal("§8Compound and capped at this level's daily limit."));
      stack.set(DataComponents.LORE, new ItemLore(lore));
      return stack;
   }

   /** How long ago a movement happened, in words a sidebar-sized line can hold. */
   private static String ago(long epochSeconds) {
      if (epochSeconds <= 0L) {
         return "unknown";
      }
      long seconds = Math.max(0L, Instant.now().getEpochSecond() - epochSeconds);
      if (seconds < 60L) {
         return "just now";
      }
      long minutes = seconds / 60L;
      if (minutes < 60L) {
         return minutes + "m ago";
      }
      long hours = minutes / 60L;
      if (hours < 48L) {
         return hours + "h ago";
      }
      return hours / 24L + "d ago";
   }

   private String levelLine(int lvl) {
      String mark = lvl == BankManager.level(BankManager.accountOf(this.owner)) ? "§6▶ " : "§8";
      return mark + "Lv " + lvl + "§8: §7$" + Chat.moneyStr(BankManager.MONEY_CAPS[lvl - 1]) + " §8· §b" + BankManager.XP_CAPS[lvl - 1]
         + " XP §8· §a" + pct(BankManager.INTEREST_RATES[lvl - 1]) + "/day";
   }

   private ItemStack moneyMeter(long cash, long cap, int level) {
      ItemStack stack = new ItemStack(Items.CHEST_MINECART);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§a§lMoney Vault §8(Lv " + level + ")"));
      List<Component> lore = new ArrayList<>();
      lore.add(Component.literal("§8" + bar(cash, cap, 'a', '8')));
      lore.add(Component.literal("§7Stored: §f" + Chat.moneyStr(cash) + " §8/ §f" + Chat.moneyStr(cap)));
      lore.add(Component.literal("§7Free: §f" + Chat.moneyStr(Math.max(0L, cap - cash))));
      lore.add(Component.literal(""));
      lore.add(Component.literal("§8Buttons either side deposit / withdraw."));
      stack.set(DataComponents.LORE, new ItemLore(lore));
      return stack;
   }

   private ItemStack xpMeter(int xpStored, int xpCap, int level) {
      ItemStack stack = new ItemStack(Items.ENCHANTING_TABLE);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§b§lXP Vault §8(Lv " + level + ")"));
      List<Component> lore = new ArrayList<>();
      lore.add(Component.literal("§8" + bar(xpStored, xpCap, 'b', '8')));
      lore.add(Component.literal("§7Stored: §f" + xpStored + " XP §8(≈ lv §b" + BankManager.levelForPoints(xpStored) + "§8)"));
      lore.add(Component.literal("§7Free: §f" + Math.max(0, xpCap - xpStored) + " XP"));
      lore.add(Component.literal(""));
      lore.add(Component.literal("§8Buttons either side deposit / withdraw."));
      stack.set(DataComponents.LORE, new ItemLore(lore));
      return stack;
   }

   /** 20-slot progress bar: 40% full -> ████████░░░░░░░░░░░░ */
   private static String bar(long value, long max, char fill, char empty) {
      int slots = 20;
      int full = max <= 0 ? slots : (int)Math.min((long)slots, value * (long)slots / max);
      return "§" + fill + "█".repeat(full) + "§" + empty + "█".repeat(slots - full);
   }

   private static String pct(double rate) {
      double p = rate * 100.0;
      return (p == Math.floor(p) ? String.valueOf((long)p) : String.valueOf(p)) + "%";
   }

   private ItemStack button(Item item, String name, String... lines) {
      ItemStack stack = new ItemStack(item);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
      List<Component> lore = new ArrayList<>();
      for (String l : lines) {
         lore.add(Component.literal(l));
      }
      if (!lore.isEmpty()) {
         lore.add(Component.literal(""));
         lore.add(Component.literal("§8Click to use"));
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
            case M_DEP_ALL -> this.doMoney(sp, Long.MAX_VALUE, false);
            case M_DEP_HALF -> this.doMoney(sp, EconomyManager.balance(sp.getUUID()) / 2L, false);
            case M_WD_HALF -> this.doMoney(sp, Math.max(0L, BankManager.accountOf(sp).cash() / 2L), true);
            case M_WD_ALL -> this.doMoney(sp, Long.MAX_VALUE, true);
            case M_METER -> this.rebuild();
            case STOCKS -> {
               this.returnCarried(sp);
               com.fortuneandfavors.menu.StockMarketMenu.open(sp);
               return;
            }
            case X_DEP_ALL -> this.doXp(sp, Integer.MAX_VALUE, false);
            case X_DEP_10 -> this.doXp(sp, 10, false);
            case X_WD_10 -> this.doXp(sp, 10, true);
            case X_WD_ALL -> this.doXp(sp, Integer.MAX_VALUE, true);
            case X_METER -> this.rebuild();
            case UPGRADE -> {
               if (BankManager.upgrade(sp)) {
                  SoundUtil.play(sp, ModSounds.FORGE_SUCCESS);
               } else {
                  SoundUtil.play(sp, ModSounds.DENY);
               }
               this.rebuild();
            }
            case INTEREST, HELP, STATEMENT -> this.rebuild();
            case CLOSE -> {
               this.returnCarried(sp);
               sp.closeContainer();
               return;
            }
            default -> {
            }
         }

         this.returnCarried(sp);
         this.rebuild();
      } else {
         super.clicked(slotId, button, input, player);
      }
   }

   private void doMoney(ServerPlayer sp, long amount, boolean withdraw) {
      if (withdraw) {
         long got = BankManager.withdrawMoney(sp, amount);
         if (got > 0L) {
            Chat.msg(sp, "&7Withdrew &a" + Chat.moneyStr(got) + "&7 from the vault.");
            SoundUtil.play(sp, ModSounds.TRANSFER);
         } else {
            SoundUtil.play(sp, ModSounds.DENY);
         }
      } else {
         long put = BankManager.depositMoney(sp, amount);
         if (put > 0L) {
            Chat.msg(sp, "&7Deposited &a" + Chat.moneyStr(put) + "&7 into the vault. It now earns daily interest.");
            SoundUtil.play(sp, ModSounds.TRANSFER);
         } else {
            SoundUtil.play(sp, ModSounds.DENY);
         }
      }
   }

   private void doXp(ServerPlayer sp, int amount, boolean withdraw) {
      if (withdraw) {
         int got = amount == Integer.MAX_VALUE ? BankManager.withdrawXp(sp, Integer.MAX_VALUE) : BankManager.withdrawXpLevels(sp, amount);
         if (got > 0) {
            Chat.msg(sp, "&7Withdrew &b" + got + " XP&7 from the vault.");
            SoundUtil.play(sp, ModSounds.JOB_COMPLETE);
         } else {
            SoundUtil.play(sp, ModSounds.DENY);
         }
      } else {
         int put = BankManager.depositXp(sp, amount);
         if (put > 0) {
            Chat.msg(sp, "&7Banked &b" + put + " XP&7 - safe from death now.");
            SoundUtil.play(sp, ModSounds.JOB_COMPLETE);
         } else {
            SoundUtil.play(sp, ModSounds.DENY);
         }
      }
   }

   public ItemStack quickMoveStack(Player player, int index) {
      return ItemStack.EMPTY;
   }
}
