package com.fortuneandfavors.menu;

import com.fortuneandfavors.ModSounds;
import com.fortuneandfavors.economy.ExpeditionManager;
import com.fortuneandfavors.economy.LootBackpack;
import com.fortuneandfavors.util.Chat;
import com.fortuneandfavors.util.GuiUtil;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

/**
 * The Loot Backpack, opened.
 *
 * <p>One verb: click a piece to throw it away. That is deliberate. A pack you can only fill is a
 * pack that fills with the first nine things you meet; a pack you can prune is a running argument
 * about what this run is actually worth, and the money thrown away is shown on the piece as it goes.
 *
 * <p>The window is as big as the pack is: nine pieces is two rows, a patched pack is three, four or
 * five, and the label and the close button move to whatever the last row happens to be. A window
 * that could not show a patched pack would be a find the player is told about and cannot use.
 */
public class LootBackpackMenu extends ChestMenu {
   /** How many rows of loot the window is showing, and where its chrome sits because of it. */
   private final int rows;
   private final int lootSlots;
   private final int info;
   private final int close;

   private final SimpleContainer container;
   private final ServerPlayer owner;

   public LootBackpackMenu(int syncId, Inventory playerInventory) {
      this(syncId, playerInventory, rowsFor(playerInventory));
   }

   private LootBackpackMenu(int syncId, Inventory playerInventory, int rows) {
      this(syncId, playerInventory, rows, new SimpleContainer(9 * rows));
   }

   private LootBackpackMenu(int syncId, Inventory playerInventory, int rows, SimpleContainer container) {
      super(typeFor(rows), syncId, playerInventory, container, rows);
      this.rows = rows;
      this.lootSlots = 9 * (rows - 1);
      this.info = this.lootSlots + 4;
      this.close = this.lootSlots + 8;
      this.container = container;
      this.owner = (ServerPlayer)playerInventory.player;
      this.rebuild();
   }

   /** The pack's own size decides the window's: a row of loot per nine pieces, and one for chrome. */
   private static int rowsFor(Inventory inventory) {
      int capacity = inventory.player instanceof ServerPlayer server
         ? LootBackpack.capacity(LootBackpack.held(server))
         : LootBackpack.BASE_CAPACITY;
      return Math.max(1, capacity / 9) + 1;
   }

   private static MenuType<?> typeFor(int rows) {
      return switch (rows) {
         case 2 -> MenuType.GENERIC_9x2;
         case 3 -> MenuType.GENERIC_9x3;
         case 4 -> MenuType.GENERIC_9x4;
         default -> MenuType.GENERIC_9x5;
      };
   }

   public static void open(ServerPlayer player) {
      player.openMenu(new SimpleMenuProvider(
         (syncId, inv, p) -> new LootBackpackMenu(syncId, inv),
         Component.literal(LootBackpack.NAME)
      ));
   }

   /** Test seam: build the window without opening it on anybody. */
   public static LootBackpackMenu forTest(int syncId, Inventory inventory) {
      int rows = rowsFor(inventory);
      return new LootBackpackMenu(syncId, inventory, rows, new SimpleContainer(9 * rows));
   }

   /** Test seam: the slots this window built, so a check can read the pack back. */
   public net.minecraft.world.Container slotsForTest() {
      return this.container;
   }

   /** How many loot slots this window is showing - what a patched pack has to move. */
   public int lootSlotsForTest() {
      return this.lootSlots;
   }

   private static ItemStack named(ItemStack stack, String name) {
      stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
      return stack;
   }

   private void rebuild() {
      ItemStack pack = LootBackpack.held(this.owner);
      List<ItemStack> carried = LootBackpack.entries(pack);
      this.container.clearContent();
      GuiUtil.frames(this.container, this.rows, Items.STAINED_GLASS_PANE.brown());
      for (int i = 0; i < carried.size() && i < this.lootSlots; i++) {
         ItemStack piece = carried.get(i).copy();
         long value = LootBackpack.valueOf(piece);
         piece.set(DataComponents.LORE, new ItemLore(List.of(
            Component.literal("§7Worth §a" + Chat.moneyStr(value) + "§7 if you carry it home."),
            Component.literal("§cClick to throw it away.")
         )));
         this.container.setItem(i, piece);
      }
      this.container.setItem(this.close, named(new ItemStack(Items.BARRIER), "§cClose"));
      this.refreshInfo(carried, pack);
      this.broadcastChanges();
   }

   private void refreshInfo(List<ItemStack> carried, ItemStack pack) {
      int capacity = LootBackpack.capacity(pack);
      int patches = (capacity - LootBackpack.BASE_CAPACITY) / LootBackpack.UPGRADE_STEP;
      ItemStack info = new ItemStack(Items.BUNDLE);
      info.set(DataComponents.CUSTOM_NAME, Component.literal(LootBackpack.NAME));
      List<Component> lore = new java.util.ArrayList<>();
      lore.add(Component.literal("§7Pieces: §f" + carried.size() + "§7/" + capacity));
      lore.add(Component.literal("§7Carried out: §a" + Chat.moneyStr(pack.isEmpty() ? 0L : LootBackpack.totalValue(pack))));
      if (patches > 0) {
         lore.add(
            Component.literal(
               "§6Patched §8x" + patches + "§6 › §7holds §f" + capacity + "§7 pieces (§f+" 
                  + (patches * LootBackpack.UPGRADE_STEP) + "§7)."
            )
         );
      } else {
         lore.add(Component.literal("§8A patch found in a rich chest adds"));
         lore.add(Component.literal("§8" + LootBackpack.UPGRADE_STEP + " more pieces, up to " + LootBackpack.MAX_CAPACITY + "."));
      }
      lore.add(Component.literal("§8Everything here leaves with you - or is lost"));
      lore.add(Component.literal("§8with the run if the site takes you."));
      lore.add(Component.literal("§eClick a piece §7to throw it away and free a space."));
      info.set(DataComponents.LORE, new ItemLore(lore));
      this.container.setItem(this.info, info);
   }

   private void returnCarried(ServerPlayer player) {
      ItemStack carried = this.getCarried();
      if (!carried.isEmpty()) {
         this.setCarried(ItemStack.EMPTY);
         this.setRemoteCarried(HashedStack.EMPTY);
         com.fortuneandfavors.util.InventoryHelper.giveOrDrop(player, carried);
      }
   }

   public void clicked(int slotId, int button, ContainerInput input, Player player) {
      if (!(player instanceof ServerPlayer sp)) {
         super.clicked(slotId, button, input, player);
         return;
      }
      if (slotId == this.close) {
         this.returnCarried(sp);
         sp.closeContainer();
         return;
      }
      if (slotId >= 0 && slotId < this.lootSlots) {
         this.returnCarried(sp);
         String problem = ExpeditionManager.discardPackPiece(sp, slotId);
         if (problem != null) {
            Chat.msg(sp, "&c" + problem);
            SoundUtil.play(sp, ModSounds.DENY);
         } else {
            SoundUtil.play(sp, ModSounds.DENY);
            this.rebuild();
         }
         return;
      }
      this.returnCarried(sp);
      this.broadcastChanges();
   }

   public ItemStack quickMoveStack(Player player, int index) {
      return ItemStack.EMPTY;
   }
}
