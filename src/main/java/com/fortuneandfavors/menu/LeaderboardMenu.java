package com.fortuneandfavors.menu;

import com.fortuneandfavors.economy.LeaderboardManager;
import com.fortuneandfavors.economy.LeaderboardManager.Category;
import com.fortuneandfavors.util.GuiUtil;
import com.fortuneandfavors.util.InventoryHelper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.HashedStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
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

public class LeaderboardMenu extends ChestMenu {
   private static final int INFO = 4;
   private static final int[] CAT_SLOTS = {10, 12, 14, 16};
   private static final int REWARDS = 22;
   private static final int CLOSE = 35;
   private final SimpleContainer container;
   private final ServerPlayer owner;

   public LeaderboardMenu(int syncId, Inventory playerInventory) {
      this(syncId, playerInventory, new SimpleContainer(36));
   }

   private LeaderboardMenu(int syncId, Inventory playerInventory, SimpleContainer container) {
      super(MenuType.GENERIC_9x4, syncId, playerInventory, container, 4);
      this.container = container;
      this.owner = (ServerPlayer)playerInventory.player;
      this.rebuild();
   }

   public static void open(ServerPlayer player) {
      player.openMenu(new SimpleMenuProvider((syncId, inv, p) -> new LeaderboardMenu(syncId, inv), Component.literal("§6§lWeekly Leaderboard")));
   }

   private void rebuild() {
      this.container.clearContent();
      GuiUtil.frames(this.container, 4, Items.STAINED_GLASS_PANE.gray());
      MinecraftServer server = this.owner.level().getServer();
      long leftMs = LeaderboardManager.timeUntilResetMs();
      long leftS = leftMs / 1000L;
      ItemStack info = new ItemStack(Items.NETHER_STAR);
      info.set(DataComponents.CUSTOM_NAME, Component.literal("§6§lWeekly Leaderboard"));
      info.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Resets every Monday at midnight EST."),
               Component.literal("§7Top 3 of each category get paid:"),
               Component.literal("§61st §7$50k  §72nd §7$25k  §73rd §7$10k"),
               Component.literal("§8Time until reset: §f" + (leftS / 3600L) + "h " + ((leftS % 3600L) / 60L) + "m")
            )
         )
      );
      this.container.setItem(INFO, info);
      Category[] cats = Category.values();
      for (int i = 0; i < cats.length; i++) {
         this.container.setItem(CAT_SLOTS[i], this.categoryCard(cats[i], server));
      }
      ItemStack rewards = new ItemStack(Items.GOLD_INGOT);
      rewards.set(DataComponents.CUSTOM_NAME, Component.literal("§6§lPrizes"));
      rewards.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Each category pays out weekly:"),
               Component.literal("§6#1 §7- $50,000"),
               Component.literal("§7#2 §7- $25,000"),
               Component.literal("§7#3 §7- $10,000"),
               Component.literal("§8Payouts land Monday 00:00 EST.")
            )
         )
      );
      this.container.setItem(REWARDS, rewards);
      this.container.setItem(CLOSE, this.named(new ItemStack(Items.BARRIER), "§cClose"));
      this.broadcastChanges();
   }

   private ItemStack categoryCard(Category cat, MinecraftServer server) {
      ItemStack stack = new ItemStack(switch (cat) {
         case MONEY -> Items.EMERALD_BLOCK;
         case BOSSES -> Items.WITHER_SKELETON_SKULL;
         case BOUNTIES -> Items.PLAYER_HEAD;
         default -> Items.IRON_PICKAXE;
      });
      stack.set(DataComponents.CUSTOM_NAME, Component.literal(cat.color + "§l" + cat.name));
      List<Map.Entry<UUID, Long>> top = LeaderboardManager.ranking(cat, 3);
      java.util.ArrayList<Component> lore = new java.util.ArrayList<>();
      if (top.isEmpty()) {
         lore.add(Component.literal("§7No entries yet this week."));
      } else {
         for (int i = 0; i < top.size(); i++) {
            Map.Entry<UUID, Long> e = top.get(i);
            ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
            String name = p != null ? p.getName().getString() : "Player";
            String medal = i == 0 ? "§6" : i == 1 ? "§7" : "§c";
            lore.add(Component.literal(medal + (i + 1) + ". §f" + name + " §7- " + cat.color + fmt(e.getValue()) + " " + cat.unit));
         }
      }
      int myRank = LeaderboardManager.playerRank(this.owner.getUUID(), cat);
      lore.add(Component.literal(
         myRank > 0 ? "§8You: §f#" + myRank + " §7(" + cat.color + fmt(LeaderboardManager.value(this.owner.getUUID(), cat)) + " " + cat.unit + "§7)"
            : "§8You're not ranked yet this week."
      ));
      stack.set(DataComponents.LORE, new ItemLore(lore));
      return stack;
   }

   private static String fmt(long v) {
      if (v >= 1_000_000L) {
         return String.format("%.1fM", v / 1_000_000.0);
      }
      if (v >= 1_000L) {
         return String.format("%.1fk", v / 1_000.0);
      }
      return String.valueOf(v);
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
         if (slotId == CLOSE) {
            this.returnCarried(sp);
            sp.closeContainer();
            return;
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
