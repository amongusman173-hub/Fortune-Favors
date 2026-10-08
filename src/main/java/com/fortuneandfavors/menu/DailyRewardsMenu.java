package com.fortuneandfavors.menu;

import com.fortuneandfavors.ModSounds;
import com.fortuneandfavors.VfxManager;
import com.fortuneandfavors.economy.DailyLoginStreakManager;
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

public class DailyRewardsMenu extends ChestMenu {
   private static final int INFO = 4;
   // The calendar is seven cards across the middle row, so the bottom row is where the buttons go.
   //
   // The claim button used to sit at 16 - which is the seventh calendar card. The last day of the
   // window was drawn and then immediately painted over by the button, so a player could never see
   // the day they were about to reach, and it looked like the calendar was one card short.
   private static final int CALENDAR_START = 10;
   private static final int NEXT_MILESTONE = 19;
   private static final int CLAIM = 21;
   private static final int BUY_FREEZE = 23;
   private static final int CLOSE = 25;
   private final SimpleContainer container;
   private final ServerPlayer owner;

   public DailyRewardsMenu(int syncId, Inventory playerInventory) {
      this(syncId, playerInventory, new SimpleContainer(27));
   }

   private DailyRewardsMenu(int syncId, Inventory playerInventory, SimpleContainer container) {
      super(MenuType.GENERIC_9x3, syncId, playerInventory, container, 3);
      this.container = container;
      this.owner = (ServerPlayer)playerInventory.player;
      this.rebuild();
   }

   public static void open(ServerPlayer player) {
      player.openMenu(new SimpleMenuProvider((syncId, inv, p) -> new DailyRewardsMenu(syncId, inv), Component.literal("§6§lDaily Rewards")));
   }

   private void rebuild() {
      this.container.clearContent();
      GuiUtil.frames(this.container, 3, Items.STAINED_GLASS_PANE.yellow());
      int day = DailyLoginStreakManager.dayOf(this.owner.getUUID());
      int freezes = DailyLoginStreakManager.freezesOf(this.owner.getUUID());
      boolean claimable = DailyLoginStreakManager.canClaim(this.owner.getUUID());

      ItemStack info = new ItemStack(Items.CLOCK);
      info.set(DataComponents.CUSTOM_NAME, Component.literal("§6§lDaily Rewards"));
      info.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Current streak: §fDay " + day),
               Component.literal("§7Freezes: §f" + freezes + "§7/" + DailyLoginStreakManager.MAX_FREEZES),
               Component.literal(""),
               Component.literal("§7Cash every day, growing with the streak - and"),
               Component.literal("§7a §fseven-day shift§7 on top so no day is bare:"),
               Component.literal("§8  Payday §7doubles the day's cash."),
               Component.literal("§8  Old Scores §7pays gems."),
               Component.literal("§8  Supply Run §7pays a Common Mystery Key."),
               Component.literal("§8  Week's End §7pays a Rare Key and a gem."),
               Component.literal("§7Milestones (§f" + milestones() + "§7) land on top of that."),
               Component.literal("§7Miss a day and a freeze keeps it alive."),
               Component.literal(claimable ? "§aA reward is waiting - claim it below!" : "§8Claimed today - come back tomorrow.")
            )
         )
      );
      this.container.setItem(INFO, info);

      // 7-day rolling calendar (Day max(1, day-2) .. +6), highlighting today and milestones.
      int start = Math.max(1, day - 2);
      for (int i = 0; i < DailyLoginStreakManager.SHIFT_DAYS; i++) {
         int d = start + i;
         ItemStack card;
         boolean isToday = d == day && day > 0;
         boolean isMilestone = false;
         for (int m : DailyLoginStreakManager.MILESTONE_DAYS) {
            if (m == d) {
               isMilestone = true;
               break;
            }
         }
         if (isToday) {
            card = new ItemStack(Items.DYE.lime());
         } else if (isMilestone) {
            card = new ItemStack(Items.GOLD_INGOT);
         } else if (day > 0 && d < day) {
            card = new ItemStack(Items.STONE);
         } else {
            card = new ItemStack(Items.STAINED_GLASS_PANE.gray());
         }
         card.set(DataComponents.CUSTOM_NAME, Component.literal(
            (isToday ? "§a§l" : isMilestone ? "§e§l" : day > 0 && d < day ? "§8" : "§7") + "Day " + d + (isToday ? " §f← today" : "")
         ));
         List<Component> lore = new ArrayList<>();
         if (isToday) {
            lore.add(Component.literal("§7Reward: " + DailyLoginStreakManager.rewardSummary(d)));
            lore.add(Component.literal("§8" + (isMilestone ? "✓ milestone - click to claim!" : "claimable today!")));
         } else if (isMilestone) {
            lore.add(Component.literal("§7Milestone: " + DailyLoginStreakManager.rewardSummary(d)));
            lore.add(Component.literal("§8" + (day > 0 && d <= day ? "✓ unlocked" : "keep logging in")));
         } else if (day > 0 && d < day) {
            lore.add(Component.literal("§7Shift: §f" + DailyLoginStreakManager.shiftFor(d).name()));
            lore.add(Component.literal("§7Paid: " + DailyLoginStreakManager.rewardSummary(d)));
            lore.add(Component.literal("§8✓ claimed"));
         } else {
            lore.add(Component.literal("§7Shift: §f" + DailyLoginStreakManager.shiftFor(d).name()
               + "§7 - " + DailyLoginStreakManager.shiftFor(d).blurb() + "."));
            lore.add(Component.literal("§7Pays: " + DailyLoginStreakManager.rewardSummary(d)));
            lore.add(Component.literal("§8Upcoming day."));
         }
         card.set(DataComponents.LORE, new ItemLore(lore));
         this.container.setItem(CALENDAR_START + i, card);
      }

      // What the streak is working toward, so the seven-day window is not the whole horizon.
      int next = DailyLoginStreakManager.nextMilestone(day);
      ItemStack milestone = new ItemStack(next > 0 ? Items.GOLD_BLOCK : Items.NETHER_STAR);
      milestone.set(DataComponents.CUSTOM_NAME, Component.literal(next > 0 ? "§6§lNext Milestone: Day " + next : "§e§lLadder Complete"));
      milestone.set(
         DataComponents.LORE,
         new ItemLore(
            next > 0
               ? List.of(
                  Component.literal("§7" + Math.max(1, next - day) + " day(s) away."),
                  Component.literal("§7Pays on top of the day's shift:"),
                  Component.literal("§8" + DailyLoginStreakManager.rewardSummary(next)),
                  Component.literal("§8Milestones: §f" + milestones())
               )
               : List.of(
                  Component.literal("§7Every milestone has been reached."),
                  Component.literal("§8The streak keeps going - day " + day + " and counting."),
                  Component.literal("§8Cash scales with it to day 50.")
               )
         )
      );
      this.container.setItem(NEXT_MILESTONE, milestone);

      ItemStack claim = new ItemStack(claimable ? Items.DYE.lime() : Items.DYE.gray());
      if (claimable) {
         claim.set(DataComponents.CUSTOM_NAME, Component.literal("§a§lCLAIM DAY " + day + " REWARD"));
         claim.set(
            DataComponents.LORE,
            new ItemLore(
               List.of(
                  Component.literal("§7Click to collect today's reward."),
                  Component.literal("§7It goes straight to your balance,"),
                  Component.literal("§7inventory, tag or titles.")
               )
            )
         );
      } else {
         claim.set(DataComponents.CUSTOM_NAME, Component.literal(day <= 0 ? "§7No reward yet" : "§8Claimed for today"));
         claim.set(
            DataComponents.LORE,
            new ItemLore(
               List.of(
                  Component.literal(day <= 0 ? "§7Log in once to start your streak." : "§7Come back after midnight EST"),
                  Component.literal("§7for the next one.")
               )
            )
         );
      }
      this.container.setItem(CLAIM, claim);

      ItemStack freeze = new ItemStack(Items.PAPER);
      freeze.set(DataComponents.CUSTOM_NAME, Component.literal(freezes >= 3 ? "§8Freezes maxed" : "§6§lBuy a Streak Freeze"));
      freeze.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(                Component.literal(freezes >= 3 ? "§7You already hold the max of 3." : "§7" + DailyLoginStreakManager.FREEZE_COST_GEMS + " gems → +1 freeze."),
               Component.literal(freezes >= 3 ? "§7Use them by missing a day." : "§7A freeze saves your streak if you miss a day."),
               Component.literal("§8You have §f" + freezes + "§8/3 freezes")
            )
         )
      );
      this.container.setItem(BUY_FREEZE, freeze);
      this.container.setItem(CLOSE, this.named(new ItemStack(Items.BARRIER), "§cClose"));
      this.broadcastChanges();
   }

   /** Every milestone day as a readable list, for the info card. */
   private static String milestones() {
      StringBuilder sb = new StringBuilder();
      for (int m : DailyLoginStreakManager.MILESTONE_DAYS) {
         if (sb.length() > 0) {
            sb.append(", ");
         }
         sb.append(m);
      }
      return sb.toString();
   }

   /**
    * Where the calendar and the buttons are, for the self-test.
    *
    * <p>The claim button once sat on the seventh calendar card and painted over it, so the last day
    * of the window could never be read. That is a collision between two numbers in this file and
    * nothing else, so it is pinned as a fact about the two numbers.
    *
    * @return {calendar start, calendar length, next milestone, claim, buy freeze, close}
    */
   public static int[] layoutForTest() {
      return new int[]{
         CALENDAR_START, DailyLoginStreakManager.SHIFT_DAYS, NEXT_MILESTONE, CLAIM, BUY_FREEZE, CLOSE
      };
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
         if (slotId == CLAIM) {
            String err = DailyLoginStreakManager.claimReward(sp);
            if (err != null) {
               Chat.msg(sp, "&c" + err);
               SoundUtil.play(sp, ModSounds.DENY);
            } else {
               SoundUtil.play(sp, ModSounds.JOB_COMPLETE);
               VfxManager.celebrate(sp);
            }
            this.rebuild();
            this.broadcastChanges();
            this.returnCarried(sp);
         } else if (slotId == BUY_FREEZE) {
            String err = DailyLoginStreakManager.buyFreeze(sp);
            if (err != null) {
               Chat.msg(sp, "&c" + err);
               SoundUtil.play(sp, ModSounds.DENY);
            } else {
               SoundUtil.play(sp, ModSounds.CLAIM);
            }
            this.rebuild();
            this.broadcastChanges();
            this.returnCarried(sp);
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
