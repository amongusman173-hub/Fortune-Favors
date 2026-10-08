package com.fortuneandfavors.menu;

import com.fortuneandfavors.ModSounds;
import com.fortuneandfavors.NiceKeepInventoryManager;
import com.fortuneandfavors.util.LastInventoryHolder;
import com.fortuneandfavors.util.SoundUtil;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.component.DataComponents;
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
 * The /ff restore confirmation screen. It shows the admin exactly what is about
 * to come back - every item waiting in the target's unclaimed graves plus a
 * stored inventory snapshot - and only reverts anything when the green Confirm
 * button is pressed.
 *
 * <p>Since snapshots are now kept as a short history rather than a single state,
 * the screen also lets the admin pick *which* snapshot to restore. That matters
 * because the newest snapshot is not always the best one: a player who dies, runs
 * back with an empty inventory, and dies again has a live snapshot that is far
 * poorer than the state from before the first death - and the archive is where
 * the real kit survived.
 */
public class RestoreMenu extends ChestMenu {
   /** Slots 0-44 preview the items that would be restored. */
   private static final int PREVIEW_SLOTS = 45;
   private static final int CANCEL = 45;
   /** Up to three "pick a snapshot" buttons, one per older snapshot on file. */
   private static final int[] SNAPSHOT_SLOTS = {46, 48, 50};
   private static final int SUMMARY = 49;
   private static final int CONFIRM = 47;
   private static final int CLOSE = 53;

   private final SimpleContainer container;
   private final ServerPlayer viewer;
   private final ServerPlayer target;
   private final List<LastInventoryHolder.SnapshotEntry> history;
   private int selected = 0;
   private List<ItemStack> preview;

   private RestoreMenu(int syncId, Inventory playerInventory, ServerPlayer target, SimpleContainer container) {
      super(MenuType.GENERIC_9x6, syncId, playerInventory, container, 6);
      this.container = container;
      this.viewer = (ServerPlayer)playerInventory.player;
      this.target = target;
      this.history = LastInventoryHolder.history(target);
      this.preview = collect(target, 0);
      this.rebuild();
   }

   /** Everything /ff restore would bring back for the chosen snapshot: graves
    *  first, then that snapshot's slots. */
   private static List<ItemStack> collect(ServerPlayer target, int snapshotIndex) {
      List<ItemStack> out = new ArrayList<>();
      try {
         out.addAll(NiceKeepInventoryManager.previewGraveItems(target));
      } catch (Throwable ignored) {
      }
      List<LastInventoryHolder.SnapshotEntry> entries = LastInventoryHolder.history(target);
      if (snapshotIndex >= 0 && snapshotIndex < entries.size()) {
         for (ItemStack s : entries.get(snapshotIndex).items()) {
            if (s != null && !s.isEmpty()) {
               out.add(s);
            }
         }
      }
      return out;
   }

   public static void open(ServerPlayer viewer, ServerPlayer target) {
      viewer.openMenu(
         new SimpleMenuProvider(
            (syncId, inv, p) -> new RestoreMenu(syncId, inv, target, new SimpleContainer(54)),
            Component.literal("§d§lRestore §f" + target.getName().getString())
         )
      );
   }

   private void rebuild() {
      this.preview = collect(this.target, this.selected);

      for (int i = 0; i < PREVIEW_SLOTS; i++) {
         this.container.setItem(i, i < this.preview.size() ? this.preview.get(i).copy() : ItemStack.EMPTY);
      }

      int graves = 0;
      try {
         graves = NiceKeepInventoryManager.previewGraveCount(this.target);
      } catch (Throwable ignored) {
      }

      int total = 0;
      for (ItemStack s : this.preview) {
         total += s.getCount();
      }
      List<LastInventoryHolder.SnapshotEntry> entries = LastInventoryHolder.history(this.target);
      int snapshotItems = entries.isEmpty() ? -1 : entries.get(Math.min(this.selected, entries.size() - 1)).count();

      ItemStack summary = new ItemStack(Items.BOOK);
      summary.set(DataComponents.CUSTOM_NAME, Component.literal("§e§lAbout to restore"));
      List<Component> lines = new ArrayList<>();
      lines.add(Component.literal("§7Player: §f" + this.target.getName().getString()));
      lines.add(Component.literal("§7Items: §f" + total + " §7in §f" + this.preview.size() + " §7stack(s)"));
      lines.add(Component.literal("§7Graves: §f" + graves + " §7item(s)"));
      lines.add(
         Component.literal(
            snapshotItems >= 0
               ? "§7Snapshot §f" + (this.selected + 1) + "§7 of §f" + entries.size() + "§7: §f" + snapshotItems + " §7item(s)"
               : "§7Snapshot: §cnone on file"
         )
      );
      lines.add(Component.literal("§8Items they already carry are never removed."));
      summary.set(DataComponents.LORE, new ItemLore(lines));
      this.container.setItem(SUMMARY, summary);

      // One button per snapshot on file, newest first, with its age and size so
      // the admin can tell the good one from the empty one.
      for (int i = 0; i < SNAPSHOT_SLOTS.length; i++) {
         int slot = SNAPSHOT_SLOTS[i];
         if (i < entries.size()) {
            LastInventoryHolder.SnapshotEntry entry = entries.get(i);
            boolean chosen = i == this.selected;
            ItemStack stack = new ItemStack(Items.PAPER);
            stack.set(
               DataComponents.CUSTOM_NAME,
               Component.literal((chosen ? "§a§l▶ " : "§7§l") + "Snapshot " + (i + 1) + (chosen ? " (selected)" : ""))
            );
            stack.set(
               DataComponents.LORE,
               new ItemLore(
                  List.of(
                     Component.literal("§7" + entry.label()),
                     Component.literal(chosen ? "§8Currently shown in this preview" : "§8Click to preview this one")
                  )
               )
            );
            this.container.setItem(slot, stack);
         } else {
            this.container.setItem(slot, this.filler());
         }
      }

      ItemStack confirm = new ItemStack(Items.EMERALD_BLOCK);
      confirm.set(DataComponents.CUSTOM_NAME, Component.literal("§a§lCONFIRM RESTORE"));
      confirm.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Click to hand these §f" + total + "§7 item(s) back."),
               Component.literal(entries.isEmpty() ? "§8Graves are consumed; nothing is taken." : "§8Restores snapshot §f" + (this.selected + 1) + "§8 of " + entries.size() + "."),
               Component.literal("§8Nothing is ever taken from the player.")
            )
         )
      );
      this.container.setItem(CONFIRM, confirm);

      ItemStack cancel = new ItemStack(Items.BARRIER);
      cancel.set(DataComponents.CUSTOM_NAME, Component.literal("§c§lCANCEL"));
      cancel.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("§7Close without restoring anything."))));
      this.container.setItem(CANCEL, cancel);

      for (int i = PREVIEW_SLOTS; i < 54; i++) {
         if (i != SUMMARY && i != CONFIRM && i != CANCEL && i != CLOSE && !isSnapshotSlot(i)) {
            this.container.setItem(i, this.filler());
         }
      }

      ItemStack close = new ItemStack(Items.STAINED_GLASS_PANE.red());
      close.set(DataComponents.CUSTOM_NAME, Component.literal("§c§lClose"));
      this.container.setItem(CLOSE, close);
   }

   private static boolean isSnapshotSlot(int slot) {
      for (int s : SNAPSHOT_SLOTS) {
         if (s == slot) {
            return true;
         }
      }
      return false;
   }

   private ItemStack filler() {
      ItemStack stack = new ItemStack(Items.STAINED_GLASS_PANE.gray());
      stack.set(DataComponents.CUSTOM_NAME, Component.literal(" "));
      return stack;
   }

   public void clicked(int slotId, int button, ContainerInput input, Player player) {
      if (!(player instanceof ServerPlayer sp)) {
         super.clicked(slotId, button, input, player);
         return;
      }
      if (slotId == CONFIRM) {
         SoundUtil.play(sp, ModSounds.TRANSFER);
         sp.closeContainer();
         ModCommandsBridge.performRestore(sp, this.target, this.selected);
         return;
      }
      if (slotId == CANCEL || slotId == CLOSE) {
         sp.closeContainer();
         return;
      }
      for (int i = 0; i < SNAPSHOT_SLOTS.length; i++) {
         if (slotId == SNAPSHOT_SLOTS[i]) {
            if (i < this.history.size()) {
               this.selected = i;
               SoundUtil.play(sp, ModSounds.PAGE_FLIP);
               this.rebuild();
               this.broadcastChanges();
            }
            return;
         }
      }
      if (slotId >= 0 && slotId < 54) {
         // The preview is read-only - clicking it just acknowledges the row.
         SoundUtil.play(sp, ModSounds.PAGE_FLIP);
         return;
      }
      super.clicked(slotId, button, input, player);
   }

   public ItemStack quickMoveStack(Player player, int index) {
      return ItemStack.EMPTY;
   }

   /** Tiny indirection so the menu can reach the command's restore implementation
    *  without the two classes needing to import each other's whole surface. */
   static final class ModCommandsBridge {
      private ModCommandsBridge() {
      }

      static void performRestore(ServerPlayer viewer, ServerPlayer target, int snapshotIndex) {
         com.fortuneandfavors.ModCommands.performRestore(viewer, target, snapshotIndex);
      }
   }
}
