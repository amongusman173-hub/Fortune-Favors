package com.fortuneandfavors.menu;

import com.fortuneandfavors.economy.ExpeditionManager;
import com.fortuneandfavors.economy.ExpeditionManager.Chamber;
import com.fortuneandfavors.economy.ExpeditionManager.ChamberPage;
import com.fortuneandfavors.economy.ExpeditionManager.DungeonPage;
import com.fortuneandfavors.economy.ExpeditionManager.Type;
import com.fortuneandfavors.util.Chat;
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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

/**
 * The expedition codex: what this world's explorers have walked, cleared and carried
 * home. Every dungeon's page holds its runs, escapes, deepest chamber and best payout;
 * every chamber's page holds how often it was seen and how often it was beaten. It is
 * world history - it survives every run, good or bad.
 */
public class ExpeditionCodexMenu extends ChestMenu {
   private static final int HEADER = 4;
   /** Eight dungeons, four over four, centred. */
   private static final int[] DUNGEON_SLOTS = {11, 12, 13, 14, 20, 21, 22, 23};
   /** Fourteen chamber pages - every chamber but the threshold itself. */
   private static final int[] CHAMBER_SLOTS = {28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};
   private static final int CLOSE = 53;
   private final SimpleContainer container;
   private final ServerPlayer owner;

   public ExpeditionCodexMenu(int syncId, Inventory playerInventory) {
      this(syncId, playerInventory, new SimpleContainer(54));
   }

   private ExpeditionCodexMenu(int syncId, Inventory playerInventory, SimpleContainer container) {
      super(MenuType.GENERIC_9x6, syncId, playerInventory, container, 6);
      this.container = container;
      this.owner = (ServerPlayer)playerInventory.player;
      this.rebuild();
   }

   public static void open(ServerPlayer player) {
      player.openMenu(
         new SimpleMenuProvider((syncId, inv, p) -> new ExpeditionCodexMenu(syncId, inv), Component.literal("§b§lExpedition Codex"))
      );
   }

   private void rebuild() {
      this.container.clearContent();
      GuiUtil.frames(this.container, 6, Items.STAINED_GLASS_PANE.blue());

      ItemStack header = new ItemStack(Items.WRITABLE_BOOK);
      header.set(DataComponents.CUSTOM_NAME, Component.literal("§b§lExpedition Codex"));
      header.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Runs begun: §f" + ExpeditionManager.codexRuns()),
               Component.literal("§7Escaped alive: §a" + ExpeditionManager.codexEscapes() + "  §7Lost to the dark: §c" + ExpeditionManager.codexFalls()),
               Component.literal("§7Chambers cleared: §f" + ExpeditionManager.codexChambersCleared()),
               Component.literal("§7Floor guardians felled: §4" + ExpeditionManager.codexGuardians() + "  §7Descents used: §6" + ExpeditionManager.codexDescents()),
               Component.literal("§7Carried home in total: §a" + Chat.moneyStr(ExpeditionManager.codexTotalEarned())),
               Component.literal("§8Every page below is a place somebody has stood.")
            )
         )
      );
      this.container.setItem(HEADER, header);

      Type[] types = Type.values();
      for (int i = 0; i < types.length && i < DUNGEON_SLOTS.length; i++) {
         this.container.setItem(DUNGEON_SLOTS[i], this.dungeonPage(types[i]));
      }

      int slot = 0;
      for (Chamber c : Chamber.values()) {
         if (c == Chamber.ENTRANCE || slot >= CHAMBER_SLOTS.length) {
            continue;
         }
         this.container.setItem(CHAMBER_SLOTS[slot++], this.chamberPage(c));
      }

      this.container.setItem(CLOSE, this.named(new ItemStack(Items.BARRIER), "§cClose"));
      this.broadcastChanges();
   }

   private ItemStack dungeonPage(Type t) {
      DungeonPage p = ExpeditionManager.dungeonPage(t);
      ItemStack card = new ItemStack(t.icon);
      card.set(DataComponents.CUSTOM_NAME, Component.literal((p.runs() > 0 ? t.color : "§8") + "§l" + t.name));
      List<Component> lore = new ArrayList<>();
      if (p.runs() <= 0) {
         lore.add(Component.literal("§7No expedition has set out for this dungeon yet."));
         lore.add(Component.literal("§8Its first page is yours to write."));
      } else {
         lore.add(Component.literal("§7Runs: §f" + p.runs() + "  §7Escaped: §a" + p.escapes()));
         lore.add(Component.literal("§7Deepest chamber: §b" + p.deepest()));
         lore.add(Component.literal("§7Best payout: §a" + Chat.moneyStr(p.bestPayout())));
         lore.add(Component.literal("§7Carried home: §a" + Chat.moneyStr(p.totalEarned())));
      }
      card.set(DataComponents.LORE, new ItemLore(lore));
      return card;
   }

   private ItemStack chamberPage(Chamber c) {
      ChamberPage p = ExpeditionManager.chamberPage(c);
      ItemStack card = new ItemStack(chamberIcon(c));
      card.set(DataComponents.CUSTOM_NAME, Component.literal((p.seen() > 0 ? c.colour : "§8") + "§l" + c.name));
      List<Component> lore = new ArrayList<>();
      if (p.seen() <= 0) {
         lore.add(Component.literal("§7Never seen. Is it real? Somebody should check."));
      } else {
         lore.add(Component.literal("§7Walked into: §f" + p.seen() + " §7time" + (p.seen() == 1 ? "" : "s")));
         lore.add(Component.literal("§7Cleared: §a" + p.cleared() + " §7time" + (p.cleared() == 1 ? "" : "s")));
      }
      card.set(DataComponents.LORE, new ItemLore(lore));
      return card;
   }

   private Item chamberIcon(Chamber c) {
      return switch (c) {
         case FLOOR_BOSS -> Items.WITHER_SKELETON_SKULL;
         case ARENA -> Items.IRON_SWORD;
         case GRAND_HALL -> Items.GOLD_BLOCK;
         case TREASURE_VAULT -> Items.EMERALD_BLOCK;
         case TRAP_FLOOR -> Items.TNT;
         case ORE_VAULT -> Items.DIAMOND;
         case SANCTUARY -> Items.GLOWSTONE;
         case CALM_CAMP -> Items.CAMPFIRE;
         case ALCHEMY -> Items.BREWING_STAND;
         case FOUNTAIN -> Items.WATER_BUCKET;
         case MOB_DEN -> Items.ZOMBIE_HEAD;
         case PILLAR_HALL -> Items.STONE_BRICKS;
         case CACHE -> Items.GOLD_INGOT;
         case SHRINE -> Items.BEACON;
         case DROWNED_HALL -> Items.HEART_OF_THE_SEA;
         default -> Items.STRING;
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
         this.returnCarried(sp);
         if (slotId == CLOSE) {
            sp.closeContainer();
         }
      } else {
         super.clicked(slotId, button, input, player);
      }
   }

   public ItemStack quickMoveStack(Player player, int index) {
      return ItemStack.EMPTY;
   }
}
