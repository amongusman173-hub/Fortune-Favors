package com.fortuneandfavors.menu;

import com.fortuneandfavors.ModSounds;
import com.fortuneandfavors.economy.ExpeditionManager;
import com.fortuneandfavors.economy.LootBackpack;
import com.fortuneandfavors.util.Chat;
import com.fortuneandfavors.util.GuiUtil;
import com.fortuneandfavors.util.SoundUtil;
import java.util.List;
import net.minecraft.core.BlockPos;
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
 * A dungeon chest, opened as a choice rather than a payout.
 *
 * <p>The chest's pieces sit in the middle of the window and the player's own pack sits along the
 * bottom, so the decision the run is made of is one screen: take this piece, and see exactly what
 * you are already carrying that it might be worth less than. Clicking a piece moves it into the
 * pack; when the pack is full the click is refused and says so, and the way out of that is the
 * pack's own window - throw something away.
 */
public class LootChestMenu extends ChestMenu {
   /** Slots 9..35 hold the chest's pieces; 36..44 mirror the pack; 49 is the label, 53 the close. */
   private static final int OFFER_FIRST = 9;
   private static final int OFFER_LAST = 35;
   private static final int PACK_FIRST = 36;
   private static final int PACK_LAST = 44;
   private static final int INFO = 49;
   private static final int CLOSE = 53;
   /** How many carried pieces the label spells out before it says "and more". */
   private static final int CARRY_LINES = 12;

   private final SimpleContainer container;
   private final ServerPlayer owner;
   private final BlockPos chest;

   public LootChestMenu(int syncId, Inventory playerInventory) {
      this(syncId, playerInventory, new SimpleContainer(54), BlockPos.ZERO);
   }

   private LootChestMenu(int syncId, Inventory playerInventory, SimpleContainer container, BlockPos chest) {
      super(MenuType.GENERIC_9x6, syncId, playerInventory, container, 6);
      this.container = container;
      this.owner = (ServerPlayer)playerInventory.player;
      this.chest = chest;
      this.rebuild();
   }

   public static void open(ServerPlayer player, BlockPos chest) {
      player.openMenu(new SimpleMenuProvider(
         (syncId, inv, p) -> new LootChestMenu(syncId, inv, new SimpleContainer(54), chest),
         Component.literal("§6§lLoot Chest")
      ));
   }

   private static ItemStack named(ItemStack stack, String name) {
      stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
      return stack;
   }

   /** Redraws the whole window from the run's own ledger - no state lives in the menu. */
   private void rebuild() {
      List<ItemStack> offers = ExpeditionManager.chestOffers(this.owner.getUUID(), this.chest);
      this.container.clearContent();
      GuiUtil.frames(this.container, 6, Items.STAINED_GLASS_PANE.gray());
      for (int i = 0; i < offers.size() && OFFER_FIRST + i <= OFFER_LAST; i++) {
         ItemStack piece = offers.get(i).copy();
         // A patch is not loot - it is a bigger pack, and its own item already says so. Giving it
         // a price tag as well would read as "this is worth money", which is the one thing it is
         // not.
         if (!LootBackpack.isUpgrader(piece)) {
            long value = LootBackpack.valueOf(piece);
            piece.set(DataComponents.LORE, new ItemLore(List.of(
               Component.literal("§7Worth §a" + Chat.moneyStr(value) + "§7 carried out."),
               Component.literal("§eClick §7to put it in your Loot Backpack.")
            )));
         }
         this.container.setItem(OFFER_FIRST + i, piece);
      }
      if (offers.isEmpty()) {
         this.container.setItem(OFFER_FIRST + 13, named(new ItemStack(Items.BARRIER), "§8Nothing left in this chest"));
      }
      // The pack, mirrored where the player can see it while they choose.
      ItemStack pack = LootBackpack.held(this.owner);
      List<ItemStack> carried = LootBackpack.entries(pack);
      int capacity = LootBackpack.capacity(pack);
      for (int i = 0; i < carried.size() && PACK_FIRST + i <= PACK_LAST; i++) {
         this.container.setItem(PACK_FIRST + i, carried.get(i).copy());
      }
      this.container.setItem(CLOSE, named(new ItemStack(Items.BARRIER), "§cClose"));
      this.refreshInfo(offers, carried, pack);
      this.broadcastChanges();
   }

   private void refreshInfo(List<ItemStack> offers, List<ItemStack> carried, ItemStack pack) {
      int capacity = LootBackpack.capacity(pack);
      List<Component> lore = new java.util.ArrayList<>();
      lore.add(Component.literal("§7Pieces left in the chest: §f" + offers.size()));
      lore.add(Component.literal("§7Your pack: §6" + carried.size() + "§7/" + capacity + " pieces"));
      if (capacity > LootBackpack.BASE_CAPACITY) {
         lore.add(Component.literal("§6Patched §8x" + ((capacity - LootBackpack.BASE_CAPACITY) / LootBackpack.UPGRADE_STEP)));
      }
      lore.add(Component.literal("§7Carried out: §a" + Chat.moneyStr(pack.isEmpty() ? 0L : LootBackpack.totalValue(pack))));
      lore.add(Component.literal("§8The bottom row is your pack."));
      lore.add(Component.literal("§8A full pack refuses new pieces until you drop one."));
      if (offers.stream().anyMatch(LootBackpack::isUpgrader)) {
         lore.add(Component.literal(""));
         lore.add(Component.literal("§6A pack upgrader is in this chest - §7it adds"));
         lore.add(Component.literal("§7" + LootBackpack.UPGRADE_STEP + " pieces to your pack right now."));
      }
      // What the player is already carrying, spelled out: the whole point of the window is seeing
      // what a new piece would have to beat, and a bottom row stops being enough to compare
      // against once the pack has been patched.
      if (!carried.isEmpty()) {
         lore.add(Component.literal(""));
         lore.add(Component.literal("§8You are carrying:"));
         int listed = Math.min(carried.size(), CARRY_LINES);
         for (int i = 0; i < listed; i++) {
            ItemStack piece = carried.get(i);
            lore.add(Component.literal(
               "§8· §f" + piece.getCount() + "x " + piece.getHoverName().getString()
                  + " §8(§a" + Chat.moneyStr(LootBackpack.valueOf(piece)) + "§8)"
            ));
         }
         if (carried.size() > listed) {
            lore.add(Component.literal("§8  ...and " + (carried.size() - listed) + " more in the pack."));
         }
      }
      ItemStack info = new ItemStack(Items.CHEST);
      info.set(DataComponents.CUSTOM_NAME, Component.literal("§6§lChest Loot"));
      info.set(DataComponents.LORE, new ItemLore(lore));
      this.container.setItem(INFO, info);
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
      if (slotId == CLOSE) {
         this.returnCarried(sp);
         sp.closeContainer();
         return;
      }
      if (slotId >= OFFER_FIRST && slotId <= OFFER_LAST) {
         this.returnCarried(sp);
         String problem = ExpeditionManager.takeChestPiece(sp, this.chest, slotId - OFFER_FIRST);
         if (problem != null) {
            Chat.msg(sp, "&c" + problem);
            SoundUtil.play(sp, ModSounds.DENY);
         } else {
            SoundUtil.play(sp, ModSounds.TRANSFER);
            this.rebuild();
         }
         return;
      }
      // Everything else in this window is a display: the click is swallowed rather than passed on,
      // because the alternative is a player lifting a piece of somebody's chest into the cursor.
      this.returnCarried(sp);
      this.broadcastChanges();
   }

   public ItemStack quickMoveStack(Player player, int index) {
      return ItemStack.EMPTY;
   }
}
