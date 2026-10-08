package com.fortuneandfavors.menu;

import com.fortuneandfavors.ModSounds;
import com.fortuneandfavors.guild.GuildManager;
import com.fortuneandfavors.guild.GuildManager.Guild;
import com.fortuneandfavors.util.Chat;
import com.fortuneandfavors.util.GuiUtil;
import com.fortuneandfavors.util.InventoryHelper;
import com.fortuneandfavors.util.SoundUtil;
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

public class GuildMineMenu extends ChestMenu {
   private static final int INFO = 4;
   private static final int TIER_START = 10;
   private static final int DEPOSIT_1K = 19;
   private static final int DEPOSIT_10K = 20;
   private static final int DEPOSIT_100K = 21;
   private static final int DEPOSIT_CUSTOM = 22;
   private static final int NOTIFY = 24;
   private static final int CLOSE = 35;
   private final SimpleContainer container;
   private final ServerPlayer owner;

   public GuildMineMenu(int syncId, Inventory playerInventory) {
      this(syncId, playerInventory, new SimpleContainer(36));
   }

   private GuildMineMenu(int syncId, Inventory playerInventory, SimpleContainer container) {
      super(MenuType.GENERIC_9x4, syncId, playerInventory, container, 4);
      this.container = container;
      this.owner = (ServerPlayer)playerInventory.player;
      this.rebuild();
   }

   public static void open(ServerPlayer player) {
      player.openMenu(new SimpleMenuProvider((syncId, inv, p) -> new GuildMineMenu(syncId, inv), Component.literal("§6§lGuild Mine")));
   }

   private void rebuild() {
      this.container.clearContent();
      GuiUtil.frames(this.container, 4, Items.STAINED_GLASS_PANE.yellow());
      Guild g = GuildManager.getGuild(this.owner.getUUID());
      if (g == null) {
         this.container
            .setItem(
               4,
               this.named(new ItemStack(Items.BARRIER), "§cYou're not in a guild", "§7Join or found a guild first - the", "§7mine is a guild-wide investment.")
            );
         this.container.setItem(CLOSE, this.named(new ItemStack(Items.BARRIER), "§cClose"));
         this.broadcastChanges();
         return;
      }

      boolean founder = g.owner.equals(this.owner.getUUID());
      int level = g.mineLevel;
      long threshold = GuildManager.minePayoutThreshold();
      long balance = com.fortuneandfavors.economy.EconomyManager.balance(this.owner.getUUID());

      ItemStack info = new ItemStack(level > 0 ? Items.DIAMOND_PICKAXE : Items.STONE_PICKAXE);
      info.set(DataComponents.CUSTOM_NAME, Component.literal(level > 0 ? "§6§lGuild Mine · §e" + GuildManager.mineTierName(level) + " §6(Lv " + level + ")" : "§7Guild Mine · §cNot built"));
      List<Component> lore = new ArrayList<>();
      if (level <= 0) {
         lore.add(Component.literal("§7Pick a mine below to build it."));
         lore.add(Component.literal("§7Mines produce cash automatically -"));
         lore.add(Component.literal("§7bigger mines produce faster."));
      } else {
         lore.add(Component.literal("§7Stock: §f" + Chat.moneyStr(g.mineStock) + "§7/§f" + Chat.moneyStr(threshold)));
         lore.add(Component.literal(progressBar(GuildManager.mineProgressPercent(g))));
         lore.add(Component.literal("§7Produces §f$" + GuildManager.minePerTick(level) + "§7 every §f" + (GuildManager.mineIntervalTicks(level) / 20L) + "s§7."));
         long eta = GuildManager.mineEtaSeconds(g);
         if (eta >= 0L) {
            lore.add(Component.literal("§7Next payout in ~§f" + eta + "s§7."));
         }
         lore.add(Component.literal("§7Payouts of " + Chat.moneyStr(threshold) + " - §efounder§7 takes §f70%§7,"));
         lore.add(Component.literal("§7online members split the §f30%§7 equally."));
         lore.add(Component.literal("§7This mine pays §f" + perHour(level) + "§7 an hour."));
         long missing = Math.max(0L, threshold - g.mineStock);
         lore.add(
            Component.literal(
               missing <= 0L
                  ? "§aThe stock is over the line - a payout is on its way."
                  : "§7To the next payout: §f" + Chat.moneyStr(missing) + "§7 more in the stock."
            )
         );
         lore.add(Component.literal("§8The mine fills by itself - deposits are optional,"));
         lore.add(Component.literal("§8they just make it pay out sooner."));
      }
      lore.add(Component.literal("§7Your balance: §f" + Chat.moneyStr(balance)));
      lore.add(Component.literal("§8Click a Lv card below to build that tier."));
      info.set(DataComponents.LORE, new ItemLore(lore));
      this.container.setItem(INFO, info);

      // Six mine tiers, cheapest → most expensive.
      for (int tier = 1; tier <= 6; tier++) {
         long cost = GuildManager.mineUpgradeCost(tier - 1);
         ItemStack card = new ItemStack(
            switch (tier) {
               case 1 -> Items.COAL;
               case 2 -> Items.IRON_INGOT;
               case 3 -> Items.GOLD_INGOT;
               case 4 -> Items.DIAMOND;
               case 5 -> Items.NETHERITE_INGOT;
               default -> Items.NETHER_STAR;
            }
         );
         boolean owned = level >= tier;
         boolean next = level == tier - 1;
         card.set(
            DataComponents.CUSTOM_NAME,
            Component.literal(
               (owned ? "§a§l" : next ? "§e§l" : "§8") + "Lv " + tier + " · " + GuildManager.mineTierName(tier)
            )
         );
         boolean canAfford = balance >= cost;
         List<Component> cl = new ArrayList<>();
         cl.add(Component.literal("§7Cost: §f" + Chat.moneyStr(cost)));
         cl.add(
            Component.literal(
               "§7Pays §f" + Chat.moneyStr(GuildManager.minePerTick(tier)) + "§7 every §f"
                  + (GuildManager.mineIntervalTicks(tier) / 20L) + "s §8(" + perHour(tier) + "/h)"
            )
         );
         if (owned) {
            cl.add(Component.literal("§a✓ Built"));
            cl.add(Component.literal(tier == level ? "§7This is your current mine." : "§7Already paid for."));
         } else if (next) {
            if (level > 0) {
               cl.add(Component.literal("§7Upgrade from Lv " + level + " §8(" + perHour(level) + "/h) §7-> §f" + perHour(tier) + "/h"));
            }
            cl.add(
               Component.literal(
                  founder
                     ? (canAfford
                        ? "§a§lClick to build - you can afford it!"
                        : "§e§lClick to build §7- you need " + Chat.moneyStr(cost - balance) + " more")
                     : "§8Only the founder can build it."
               )
            );
         } else {
            cl.add(Component.literal("§7Unlocks once Lv " + (tier - 1) + " is built."));
         }
         cl.add(
            Component.literal(
               canAfford
                  ? "§7Your balance: §a" + Chat.moneyStr(balance) + " ✔"
                  : "§7Your balance: §c" + Chat.moneyStr(balance) + " ✖ (need " + Chat.moneyStr(cost - balance) + ")"
            )
         );
         card.set(DataComponents.LORE, new ItemLore(cl));
         this.container.setItem(TIER_START + tier - 1, card);
      }

      if (level > 0) {
         this.container.setItem(DEPOSIT_1K, this.depositButton(1000L));
         this.container.setItem(DEPOSIT_10K, this.depositButton(10000L));
         this.container.setItem(DEPOSIT_100K, this.depositButton(100000L));
         ItemStack custom = new ItemStack(Items.WRITABLE_BOOK);
         custom.set(DataComponents.CUSTOM_NAME, Component.literal("§e§lCustom deposit"));
         custom.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("§7Type any amount with /mine <amount>,"), Component.literal("§7or click for a type-in prompt."))));
         this.container.setItem(DEPOSIT_CUSTOM, custom);
      }
      boolean notifyOff = GuildManager.mineNotifyOff(g, this.owner.getUUID());
      ItemStack bell = new ItemStack(Items.BELL);
      bell.set(
         DataComponents.CUSTOM_NAME,
         Component.literal(notifyOff ? "§c§lMine notifications: OFF" : "§a§lMine notifications: ON")
      );
      bell.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Click to " + (notifyOff ? "enable" : "mute") + " payout chat messages."),
               Component.literal("§8This is per member - the mine itself is unaffected.")
            )
         )
      );
      this.container.setItem(NOTIFY, bell);
      this.container.setItem(CLOSE, this.named(new ItemStack(Items.BARRIER), "§cClose"));
      this.broadcastChanges();
   }

   private ItemStack depositButton(long amount) {
      ItemStack stack = new ItemStack(amount >= 100000L ? Items.GOLD_BLOCK : (amount >= 10000L ? Items.GOLD_INGOT : Items.GOLD_NUGGET));
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§6§lDeposit " + Chat.moneyStr(amount)));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Add " + Chat.moneyStr(amount) + " to the mine stock"),
               Component.literal("§7from your balance to speed up the payout.")
            )
         )
      );
      return stack;
   }

   /** What a tier actually earns per hour, so every card states its payoff. */
   private static String perHour(int level) {
      long ticks = Math.max(1L, GuildManager.mineIntervalTicks(level));
      return Chat.moneyStr(GuildManager.minePerTick(level) * (72_000L / ticks));
   }

   private static String progressBar(int percent) {
      int filled = Math.min(10, percent / 10);
      return "§8[§6" + "|".repeat(filled) + "§7" + "|".repeat(10 - filled) + "§8] §f" + percent + "%";
   }

   private ItemStack named(ItemStack stack, String name, String... loreLines) {
      stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
      if (loreLines.length > 0) {
         List<Component> lore = new ArrayList<>();
         for (String l : loreLines) {
            lore.add(Component.literal(l));
         }
         stack.set(DataComponents.LORE, new ItemLore(lore));
      }
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
         Guild g = GuildManager.getGuild(sp.getUUID());
         if (slotId == CLOSE) {
            this.returnCarried(sp);
            sp.closeContainer();
            return;
         }
         if (g != null && slotId >= TIER_START && slotId < TIER_START + 6) {
            int tier = slotId - TIER_START + 1;
            if (g.mineLevel == tier - 1) {
               String err = GuildManager.mineUpgrade(sp);
               if (err != null) {
                  Chat.msg(sp, "&c" + err);
                  SoundUtil.play(sp, ModSounds.DENY);
               } else {
                  Chat.msg(sp, "&aBuilt the Lv " + tier + " " + GuildManager.mineTierName(tier) + " mine - it now pays " + perHour(tier) + " an hour.");
                  SoundUtil.play(sp, ModSounds.JOB_COMPLETE);
               }
               this.rebuild();
               this.broadcastChanges();
            } else if (g.mineLevel >= tier) {
               Chat.msg(sp, "&7Lv " + tier + " " + GuildManager.mineTierName(tier) + " is already built. Your next one is Lv " + (g.mineLevel + 1) + ".");
               SoundUtil.play(sp, ModSounds.DENY);
            } else {
               Chat.msg(sp, "&cBuild Lv " + (g.mineLevel + 1) + " first - the mine ladder has to be climbed in order.");
               SoundUtil.play(sp, ModSounds.DENY);
            }
            this.returnCarried(sp);
            return;
         }
         if (g != null && slotId == NOTIFY) {
            String msg = GuildManager.mineNotifyToggle(sp);
            Chat.msg(sp, msg.startsWith("Mine") ? "&a" + msg : "&c" + msg);
            SoundUtil.play(sp, ModSounds.TRANSFER);
            this.rebuild();
            this.broadcastChanges();
            this.returnCarried(sp);
            return;
         }
         if (g != null && g.mineLevel > 0) {
            long amount = slotId == DEPOSIT_1K ? 1000L : slotId == DEPOSIT_10K ? 10000L : slotId == DEPOSIT_100K ? 100000L : -1L;
            if (amount > 0L) {
               String err = GuildManager.mineDeposit(sp, amount);
               if (err != null) {
                  Chat.msg(sp, "&c" + err);
                  SoundUtil.play(sp, ModSounds.DENY);
               } else {
                  SoundUtil.play(sp, ModSounds.TRANSFER);
               }
               this.rebuild();
               this.broadcastChanges();
               this.returnCarried(sp);
               return;
            }
            if (slotId == DEPOSIT_CUSTOM) {
               MineDepositPromptMenu.open(sp);
               return;
            }
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
