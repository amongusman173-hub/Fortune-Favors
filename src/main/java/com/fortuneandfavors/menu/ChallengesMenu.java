package com.fortuneandfavors.menu;

import com.fortuneandfavors.economy.DailyWeeklyChallengeManager;
import com.fortuneandfavors.economy.DailyWeeklyChallengeManager.Challenge;
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

public class ChallengesMenu extends ChestMenu {
   private final SimpleContainer container;
   private final ServerPlayer owner;

   public ChallengesMenu(int syncId, Inventory playerInventory) {
      this(syncId, playerInventory, new SimpleContainer(27));
   }

   private ChallengesMenu(int syncId, Inventory playerInventory, SimpleContainer container) {
      super(MenuType.GENERIC_9x3, syncId, playerInventory, container, 3);
      this.container = container;
      this.owner = (ServerPlayer)playerInventory.player;
      this.rebuild();
   }

   public static void open(ServerPlayer player) {
      // Do NOT reload here - load() resets progress when the day rolls over and
      // was wiping in-progress challenges every time the menu was opened.
      player.openMenu(new SimpleMenuProvider((syncId, inv, p) -> new ChallengesMenu(syncId, inv), Component.literal("§b§lDaily & Weekly Challenges")));
   }

   private void rebuild() {
      this.container.clearContent();
      GuiUtil.frames(this.container, 3, Items.STAINED_GLASS_PANE.cyan());
      ItemStack header = new ItemStack(Items.EXPERIENCE_BOTTLE);
      header.set(DataComponents.CUSTOM_NAME, Component.literal("§b§lDaily & Weekly Challenges"));
      header.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Complete challenges for cash and"),                Component.literal("§7gems. Daily reset at midnight,"),
               Component.literal("§7weekly reset every Monday.")
            )
         )
      );
      this.container.setItem(0, header);

      List<Challenge> daily = DailyWeeklyChallengeManager.daily();
      for (int i = 0; i < daily.size() && i < 5; i++) {
         this.container.setItem(9 + i, this.card(daily.get(i), "§6"));
      }
      List<Challenge> weekly = DailyWeeklyChallengeManager.weekly();
      for (int i = 0; i < weekly.size() && i < 5; i++) {
         this.container.setItem(18 + i, this.card(weekly.get(i), "§5"));
      }
      this.container.setItem(26, this.named(new ItemStack(Items.BARRIER), "§cClose"));
      this.broadcastChanges();
   }

   private ItemStack card(Challenge c, String color) {
      int progress = DailyWeeklyChallengeManager.progressOf(this.owner, c.id);
      boolean done = progress < 0 || progress >= c.target; // -1 = already claimed
      ItemStack card = new ItemStack(
         switch (c.category) {
            case "mob" -> Items.IRON_SWORD;
            case "pvp" -> Items.DIAMOND_SWORD;
            case "boss" -> Items.WITHER_SKELETON_SKULL;
            case "duel" -> Items.SHIELD;
            case "deliver" -> Items.HOPPER;
            case "bounty" -> Items.PAPER;
            case "trade" -> Items.EMERALD;
            case "sell" -> Items.GOLD_INGOT;
            case "earn" -> Items.GOLD_BLOCK;
            case "ore" -> Items.DIAMOND;
            default -> Items.STONE;
         }
      );
      card.set(DataComponents.CUSTOM_NAME, Component.literal((done ? "§a§l" : color + "§l") + c.name));
      List<Component> lore = new ArrayList<>();
      lore.add(Component.literal("§7" + c.desc));
      lore.add(Component.literal(progressBar(progress, c.target)));
      lore.add(Component.literal(done ? "§a§lComplete! Reward: §a$" + c.reward + "§r §7+ §51 gem" : "§7Reward: §a$" + c.reward + "§7 + §51 gem§7"));
      card.set(DataComponents.LORE, new ItemLore(lore));
      return card;
   }

   private static String progressBar(int progress, int target) {
      if (progress < 0) {
         return "§8[§a||||||||||§8] §aComplete!";
      }
      int filled = Math.min(10, (int)Math.round(10.0 * Math.min(progress, target) / target));
      return "§8[§a" + "|".repeat(filled) + "§7" + "|".repeat(10 - filled) + "§8] §f" + Math.min(progress, target) + "§7/§f" + target;
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
         if (slotId == 26) {
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
