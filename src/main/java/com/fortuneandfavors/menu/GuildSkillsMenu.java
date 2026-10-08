package com.fortuneandfavors.menu;

import com.fortuneandfavors.guild.GuildManager;
import com.fortuneandfavors.guild.GuildManager.Guild;
import com.fortuneandfavors.util.Chat;
import com.fortuneandfavors.util.InventoryHelper;
import java.util.ArrayList;
import java.util.Arrays;
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

public class GuildSkillsMenu extends ChestMenu {
   private static final int INFO = 4;
   /** Slots 10..14 hold one guild skill each, in {@link GuildManager#PERKS} order. */
   private static final int PERK_BASE = 10;
   private static final int MILESTONE = 16;
   private static final int PVE_MILESTONE = 17;
   private static final int CLOSE = 22;
   private final SimpleContainer container;
   private final ServerPlayer owner;

   public GuildSkillsMenu(int syncId, Inventory playerInventory) {
      this(syncId, playerInventory, new SimpleContainer(27));
   }

   private GuildSkillsMenu(int syncId, Inventory playerInventory, SimpleContainer container) {
      super(MenuType.GENERIC_9x3, syncId, playerInventory, container, 3);
      this.container = container;
      this.owner = (ServerPlayer)playerInventory.player;
      this.rebuild();
   }

   public static void open(ServerPlayer player) {
      player.openMenu(new SimpleMenuProvider((syncId, inv, p) -> new GuildSkillsMenu(syncId, inv), Component.literal("§6§lGuild Skills")));
   }

   private ItemStack button(Item item, String name, String... lines) {
      ItemStack stack = new ItemStack(item);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
      stack.set(DataComponents.LORE, new ItemLore(Arrays.stream(lines).<Component>map(s -> Component.literal(s)).toList()));
      return stack;
   }

   private ItemStack frame(Item item) {
      ItemStack stack = new ItemStack(item);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal(" "));
      return stack;
   }

   private void rebuild() {
      this.container.clearContent();
      Guild g = GuildManager.getGuild(this.owner.getUUID());
      if (g == null) {
         this.container.setItem(4, this.button(Items.BOOK, "§6§lNo guild", "§7Join or found a guild to", "§7unlock guild skills."));
         this.container.setItem(22, this.button(Items.BARRIER, "§cClose"));
         this.broadcastChanges();
      } else {
         boolean founder = g.owner.equals(this.owner.getUUID());
         int level = GuildManager.levelOf(g);
         int points = GuildManager.availablePerkPoints(g);
         this.container
            .setItem(
               4,
               this.button(
                  Items.BOOK,
                  "§6§l" + g.name + " - Level " + level,
                  "§7" + GuildManager.totalScore(g) + " total score §8(PvP + PvE)",
                  "§8" + GuildManager.progressToNext(g) + "/25 to level " + (level + 1),
                  "§e" + points + " unspent perk point" + (points == 1 ? "" : "s"),
                  founder ? "§8Left-click: spend a point · Right-click: refund" : "§8Only the founder can spend points."
               )
            );
         for (int i = 0; i < GuildManager.PERKS.length; i++) {
            String key = GuildManager.PERKS[i];
            this.container.setItem(PERK_BASE + i, this.perkButton(key, GuildManager.perkRank(g, key), founder));
         }

         this.container.setItem(MILESTONE, this.milestoneCard(g, founder));
         this.container.setItem(PVE_MILESTONE, this.pveMilestoneCard(g));
         this.container.setItem(22, this.button(Items.BARRIER, "§cClose", "§7Leave this window"));
         this.container.setItem(0, this.frame((Item)Items.STAINED_GLASS_PANE.black()));
         this.container.setItem(8, this.frame((Item)Items.STAINED_GLASS_PANE.black()));
         this.container.setItem(18, this.frame((Item)Items.STAINED_GLASS_PANE.black()));
         this.container.setItem(26, this.frame((Item)Items.STAINED_GLASS_PANE.black()));
         this.broadcastChanges();
      }
   }

   private ItemStack milestoneCard(Guild g, boolean founder) {
      boolean done = g.pvpMilestoneRewarded;
      long pvp = g.pvp;
      long left = Math.max(0L, 30L - pvp);
      return this.button(
         Items.FISHING_ROD,
         done ? "§5§lStab Shot §8[PvP EARNED]" : "§5§lStab Shot PvP Milestone",
         "§7Reach §e30§7 guild PvP kills and",
         "§7the §5founder§7 earns §5one Stab Shot§7.",
         done ? "§a§lReward claimed - one Stab Shot was granted." : "§e" + pvp + "§7/30 PvP kills" + (left > 0L ? " §8(" + left + " to go)" : "§a §lREADY!"),
         founder ? "§8Granted once, never twice - it can't be duped." : "§8The founder gets this one."
      );
   }

   /** Recurring PvE reward card: one Stab Shot per 30 PvE score, to the earner. */
   private ItemStack pveMilestoneCard(Guild g) {
      long earned = g.pve;
      long intoStep = earned % GuildManager.PVE_MILESTONE_STEP;
      long left = GuildManager.PVE_MILESTONE_STEP - intoStep;
      return this.button(
         Items.NETHER_STAR,
         "§5§lStab Shot PvE Milestone",
         "§7EVERY §e" + GuildManager.PVE_MILESTONE_STEP + "§7 points of PvE score a member",
         "§7earns grants §5them§7 (not the founder) one §5Stab Shot§7.",
         "§7Guild total: §b" + earned + "§7 PvE score",
         "§7Next reward in §e" + left + "§7 more PvE score.",
         "§8Slain bosses stack up - rewards repeat forever."
      );
   }

   private static Item perkIcon(String key) {
      return switch (key) {
         case GuildManager.PERK_WISDOM -> Items.EXPERIENCE_BOTTLE;
         case GuildManager.PERK_MIGHT -> Items.IRON_SWORD;
         case GuildManager.PERK_RALLY -> Items.WITHER_SKELETON_SKULL;
         case GuildManager.PERK_WARD -> Items.SHIELD;
         case GuildManager.PERK_FORTUNE -> Items.GOLD_INGOT;
         default -> Items.PAPER;
      };
   }

   private static String perkColor(String key) {
      return switch (key) {
         case GuildManager.PERK_WISDOM -> "§b§l";
         case GuildManager.PERK_MIGHT -> "§c§l";
         case GuildManager.PERK_RALLY -> "§4§l";
         case GuildManager.PERK_WARD -> "§9§l";
         case GuildManager.PERK_FORTUNE -> "§a§l";
         default -> "§f§l";
      };
   }

   private ItemStack perkButton(String key, int rank, boolean founder) {
      String name = perkColor(key) + GuildManager.perkName(key);
      List<String> lines = new ArrayList<>(
         List.of(
            "§7" + GuildManager.perkEffect(key, Math.max(1, rank)) + ".",
            rank >= GuildManager.MAX_PERK ? "§a§lMAXED" : "§8Next rank: §7" + GuildManager.perkEffect(key, rank + 1),
            "§8Rank " + rank + "/" + GuildManager.MAX_PERK
         )
      );
      if (founder && rank > 0) {
         lines.add("§8Right-click: refund one rank for §e" + Chat.moneyStr(GuildManager.PERK_REFUND_FEE) + "§8.");
      }

      return this.button(perkIcon(key), name + (rank > 0 ? " §8[Rank " + rank + "]" : ""), lines.toArray(new String[0]));
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
         if (slotId == 22) {
            this.returnCarried(sp);
            sp.closeContainer();
         } else if (slotId >= PERK_BASE && slotId < PERK_BASE + GuildManager.PERKS.length) {
            String key = GuildManager.PERKS[slotId - PERK_BASE];
            String err = button == 1 ? GuildManager.refundPerk(sp, key) : GuildManager.rankUpPerk(sp, key);
            if (err != null) {
               Chat.msg(sp, "&c" + err);
            } else {
               GuildManager.save(sp.level().getServer());
               this.rebuild();
               this.broadcastChanges();
            }

            this.returnCarried(sp);
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
      if (index >= 0 && index < 27) {
         this.clicked(index, 0, ContainerInput.QUICK_MOVE, player);
      }

      return ItemStack.EMPTY;
   }

   public void removed(Player player) {
      if (player instanceof ServerPlayer sp) {
         this.returnCarried(sp);
      }

      super.removed(player);
   }
}
