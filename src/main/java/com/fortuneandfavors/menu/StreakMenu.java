package com.fortuneandfavors.menu;

import com.fortuneandfavors.economy.DailyLoginStreakManager;
import com.fortuneandfavors.economy.StreakTrackerManager;
import com.fortuneandfavors.economy.TitleManager;
import com.fortuneandfavors.util.GuiUtil;
import com.fortuneandfavors.util.InventoryHelper;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
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

public class StreakMenu extends ChestMenu {
   private final SimpleContainer container;
   private final ServerPlayer owner;

   public StreakMenu(int syncId, Inventory playerInventory) {
      this(syncId, playerInventory, new SimpleContainer(27));
   }

   private StreakMenu(int syncId, Inventory playerInventory, SimpleContainer container) {
      super(MenuType.GENERIC_9x3, syncId, playerInventory, container, 3);
      this.container = container;
      this.owner = (ServerPlayer)playerInventory.player;
      this.rebuild();
   }

   public static void open(ServerPlayer player) {
      player.openMenu(new SimpleMenuProvider((syncId, inv, p) -> new StreakMenu(syncId, inv), Component.literal("§6§lDaily Streak")));
   }

   private void rebuild() {
      this.container.clearContent();
      GuiUtil.frames(this.container, 3, Items.STAINED_GLASS_PANE.yellow());
      UUID uuid = this.owner.getUUID();
      int day = DailyLoginStreakManager.dayOf(uuid);
      int freezes = DailyLoginStreakManager.freezesOf(uuid);

      ItemStack login = new ItemStack(Items.CLOCK);
      login.set(DataComponents.CUSTOM_NAME, Component.literal("§6§lDaily Login Streak"));
      login.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Current streak: §fDay " + day),
               Component.literal("§7Freezes: §f" + freezes + "§7/3"),
               Component.literal("§8Every day pays cash that grows with your streak."),
               Component.literal("§8Day 7: §bCommon Key§8 · Day 14: §bRare Key + [Veteran]"),
               Component.literal("§8Day 21: §5Epic Key + tokens§8 · Day 30: §6Legendary Key + [Dedicated]"),
               Component.literal("§8Day 60: §6Legendary Key + [Centurion]§8 · Day 100: §e[Immortal] title"),
               Component.literal("§7Log in every day to keep it alive!"),
               Component.literal("§e§lClick to open the Daily Rewards GUI!")
            )
         )
      );
      this.container.setItem(10, login);

      boolean claimable = com.fortuneandfavors.economy.DailyLoginStreakManager.canClaim(uuid);
      ItemStack claim = new ItemStack(claimable ? Items.DYE.lime() : Items.DYE.gray());
      claim.set(DataComponents.CUSTOM_NAME, Component.literal(claimable ? "§a§lCLAIM DAY " + day + " REWARD" : "§8Daily reward claimed"));
      claim.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal(claimable ? "§7A reward is waiting - click to collect!" : "§7Come back after midnight EST for the next one."),
               Component.literal("§8Opens the Daily Rewards calendar.")
            )
         )
      );
      this.container.setItem(8, claim);

      ItemStack duel = new ItemStack(Items.IRON_SWORD);
      int duelWins = StreakTrackerManager.duelWins(uuid);
      duel.set(DataComponents.CUSTOM_NAME, Component.literal("§b§lDuel Wins"));
      duel.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7" + duelWins + " duel win" + (duelWins == 1 ? "" : "s")),
               Component.literal("§8Milestones: 10 §bDuelist§8 · 25 §bSwordsman§8 · 50 §bChampion")
            )
         )
      );
      this.container.setItem(12, duel);

      ItemStack boss = new ItemStack(Items.NETHER_STAR);
      int bossStreak = StreakTrackerManager.bossStreak(uuid);
      boss.set(DataComponents.CUSTOM_NAME, Component.literal("§c§lBoss Streak"));
      boss.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7" + bossStreak + " boss" + (bossStreak == 1 ? "" : "es") + " slain without dying"),
               Component.literal("§8Reach 4 in a row for the §cBoss Slayer§8 title.")
            )
         )
      );
      this.container.setItem(14, boss);

      ItemStack bounty = new ItemStack(Items.PLAYER_HEAD);
      int bountyLevel = StreakTrackerManager.bountyLevel(uuid);
      bounty.set(DataComponents.CUSTOM_NAME, Component.literal("§a§lBounty Hunter"));
      bounty.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Level " + bountyLevel + " bounty hunter"),
               Component.literal("§8Every 5 bounties is a level;"),
               Component.literal("§8level 20 earns the §aBounty Hunter§8 title.")
            )
         )
      );
      this.container.setItem(16, bounty);

      Set<String> titles = TitleManager.unlockedTitles(uuid);
      ItemStack titleStack = new ItemStack(Items.NAME_TAG);
      titleStack.set(DataComponents.CUSTOM_NAME, Component.literal("§e§lYour Titles"));
      List<Component> lore = new ArrayList<>();
      if (titles.isEmpty()) {
         lore.add(Component.literal("§7No titles yet - keep dueling,"));
         lore.add(Component.literal("§7slaying bosses and logging in!"));
      } else {
         for (String t : titles) {
            lore.add(Component.literal(" §e[" + t + "§e]"));
         }
         lore.add(Component.literal("§7Equip one with §f/ff title <name>§7."));
      }
      titleStack.set(DataComponents.LORE, new ItemLore(lore));
      this.container.setItem(21, titleStack);

      ItemStack playtime = new ItemStack(Items.CLOCK);
      playtime.set(DataComponents.CUSTOM_NAME, Component.literal("§b§lPlaytime"));
      String joined = com.fortuneandfavors.economy.PlaytimeManager.firstJoin(uuid);
      playtime.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Total time on this server: §f" + com.fortuneandfavors.economy.PlaytimeManager.format(com.fortuneandfavors.economy.PlaytimeManager.totalSeconds(uuid))),
               Component.literal(joined.isEmpty() ? "§7First joined: §funknown" : "§7First joined: §f" + joined),
               Component.literal("§8Playtime keeps counting even if you crash -"),
               Component.literal("§8it's banked every few minutes and on logout.")
            )
         )
      );
      this.container.setItem(22, playtime);

      ItemStack info = new ItemStack(Items.BOOK);
      info.set(DataComponents.CUSTOM_NAME, Component.literal("§6§lStreaks & Titles"));
      info.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Your daily login streak is the only"),
               Component.literal("§7one that resets - the rest are lifelong"),
               Component.literal("§7bragging rights with titles attached."),
               Component.literal("§8Also see §f/ff streak§8 for a chat view.")
            )
         )
      );
      this.container.setItem(4, info);
      this.container.setItem(26, this.named(new ItemStack(Items.BARRIER), "§cClose"));
      this.broadcastChanges();
   }

   private ItemStack named(ItemStack stack, String name) {
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
         if (slotId == 8 || slotId == 10) {
            com.fortuneandfavors.menu.DailyRewardsMenu.open(sp);
         } else if (slotId == 26) {
            this.returnCarried(sp);
            sp.closeContainer();
         } else {
            this.returnCarried(sp);
         }
      } else {
         super.clicked(slotId, button, input, player);
      }
   }

   public ItemStack quickMoveStack(Player player, int index) {
      return ItemStack.EMPTY;
   }
}
