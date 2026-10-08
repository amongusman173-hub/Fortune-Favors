package com.fortuneandfavors.menu;

import com.fortuneandfavors.ModSounds;
import com.fortuneandfavors.VfxManager;
import com.fortuneandfavors.economy.EconomyManager;
import com.fortuneandfavors.economy.JobManager;
import com.fortuneandfavors.economy.JobManager.Job;
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

public class JobMenu extends ChestMenu {
   private static final int CLOSE = 35;
   private final SimpleContainer container;
   private final ServerPlayer owner;

   public JobMenu(int syncId, Inventory playerInventory) {
      this(syncId, playerInventory, new SimpleContainer(36));
   }

   private JobMenu(int syncId, Inventory playerInventory, SimpleContainer container) {
      super(MenuType.GENERIC_9x4, syncId, playerInventory, container, 4);
      this.container = container;
      this.owner = (ServerPlayer)playerInventory.player;
      this.rebuild();
   }

   public static void open(ServerPlayer player) {
      player.openMenu(new SimpleMenuProvider((syncId, inv, p) -> new JobMenu(syncId, inv), Component.literal("§6§lJob Board")));
   }

   private void rebuild() {
      this.container.clearContent();
      GuiUtil.frames(this.container, 4, Items.STAINED_GLASS_PANE.green());
      List<Job> jobs = JobManager.jobs(this.owner.getUUID());

      for (int i = 0; i < jobs.size() && i < 5; i++) {
         this.container.setItem(i, this.card(jobs.get(i)));
      }

      ItemStack info = new ItemStack(Items.BOOK);
      info.set(DataComponents.CUSTOM_NAME, Component.literal("§e§lHow jobs work"));
      info.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Five jobs, and a fresh one is rolled"),
               Component.literal("§7into a slot the moment you claim it."),
               Component.literal("§7Do the task, then come back and"),
               Component.literal("§7click the card to claim the cash."),
               Component.literal("§7Both the work and the pay grow with"),
               Component.literal("§7your skill levels - pay grows twice as fast."),
               Component.literal("§8Pickaxe = mine · axe = chop · hoe = farm"),
               Component.literal("§8Dynamic contracts below pay out on delivery.")
            )
         )
      );
      this.container.setItem(9, info);

      // Dynamic contracts - live server jobs from the market.
      List<com.fortuneandfavors.economy.DynamicContractsManager.Contract> contracts =
         com.fortuneandfavors.economy.DynamicContractsManager.activeContracts();
      for (int i = 0; i < Math.min(5, contracts.size()); i++) {
         this.container.setItem(10 + i, com.fortuneandfavors.economy.DynamicContractsManager.contractIcon(contracts.get(i)));
      }
      if (contracts.isEmpty()) {
         this.container
            .setItem(
               12,
               this.named(
                  Items.PAPER, "§7No dynamic contracts", "§7Shortages and urgent contracts", "§7appear here - deliver for cash!"
               )
            );
      }
      ItemStack deliver = this.named(Items.HOPPER, "§e§lDeliver contract items");
      deliver.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Open the delivery window to hand in"),
               Component.literal("§7items and collect the rewards."),
               Component.literal("§8" + contracts.size() + "/8 contracts live")
            )
         )
      );
      this.container.setItem(16, deliver);
      this.container.setItem(CLOSE, this.named(Items.BARRIER, "§cClose"));
   }

   private ItemStack card(Job job) {
      ItemStack stack = JobManager.cardItem(job);
      boolean done = job.progress >= job.goal;
      String title;
      String tag;
      String color;
      if (job.claimed) {
         title = "§8§m" + JobManager.describe(job);
         tag = "§8Claimed";
         color = "§8";
      } else if (done) {
         title = "§a§l" + JobManager.describe(job);
         tag = "§a§lCLICK TO CLAIM!";
         color = "§a";
      } else {
         title = "§f§l" + JobManager.describe(job);
         tag = "§8Not finished yet";
         color = "§f";
      }
      stack.set(DataComponents.CUSTOM_NAME, Component.literal(title));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal(progressBar(job.progress, job.goal)),
               Component.literal(color + "Progress: §f" + Math.min(job.progress, job.goal) + "§7/§f" + job.goal),
               Component.literal("§7Reward: §a$" + job.reward),
               Component.literal(tag)
            )
         )
      );
      return stack;
   }

   private static String progressBar(int progress, int goal) {
      int filled = Math.min(10, (int)Math.round(10.0 * Math.min(progress, goal) / goal));
      return "§8[§a" + "|".repeat(filled) + "§7" + "|".repeat(10 - filled) + "§8]";
   }

   private ItemStack named(Item item, String name, String... loreLines) {
      ItemStack stack = new ItemStack(item);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
      if (loreLines.length > 0) {
         List<Component> lore = new java.util.ArrayList<>();
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
         if (slotId >= 0 && slotId <= 4) {
            long reward = JobManager.claim(sp, slotId);
            if (reward > 0L) {
               SoundUtil.play(sp, ModSounds.JOB_COMPLETE);
               VfxManager.celebrate(sp);
               Chat.raw(
                  sp,
                  "&a&lJob complete!&r &7You earned " + Chat.moneyStr(reward) + "&7. Balance: " + Chat.moneyStr(EconomyManager.balance(sp.getUUID()))
                     + "&7. &fA fresh job took its place."
               );
               com.fortuneandfavors.economy.LeaderboardManager.onJobClaimed(sp);
            } else {
               SoundUtil.play(sp, ModSounds.DENY);
            }

            this.rebuild();
            this.broadcastChanges();
            this.returnCarried(sp);
         } else if (slotId >= 10 && slotId <= 15) {
            // A contract card is a shortcut to the window that hands the contract in. It used to be
            // a picture: the board told a player what the market wanted, and then made them remember
            // that delivering it happens somewhere else entirely. The body of the window already
            // said "open the delivery window"; now the card does it.
            this.returnCarried(sp);
            DeliverMenu.open(sp);
         } else if (slotId == 16) {
            this.returnCarried(sp);
            DeliverMenu.open(sp);
         } else if (slotId == CLOSE) {
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
