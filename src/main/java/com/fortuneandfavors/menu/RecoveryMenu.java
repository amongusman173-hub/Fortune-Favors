package com.fortuneandfavors.menu;

import com.fortuneandfavors.ModCommands;
import com.fortuneandfavors.ModSounds;
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
 * Preview-and-confirm screen shared by every recovery path: claims, spawners and
 * machines. It shows exactly which records would come back and from where, and
 * only merges them when the green Confirm button is pressed. Nothing is ever
 * deleted - a live record is never counted or overwritten.
 *
 * <p>All three kinds run through {@link ModCommands#performRecovery}, the same
 * code the slash commands use, so the GUI and the command can never drift apart.
 */
public class RecoveryMenu extends ChestMenu {
   /** Which persisted table this screen restores. */
   public enum Kind {
      CLAIMS("Claims", Items.FILLED_MAP, "claim"),
      SPAWNERS("Spawners", Items.SPAWNER, "spawner"),
      MACHINES("Machines", Items.REDSTONE, "machine");

      private final String label;
      private final net.minecraft.world.item.Item icon;
      private final String noun;

      Kind(String label, net.minecraft.world.item.Item icon, String noun) {
         this.label = label;
         this.icon = icon;
         this.noun = noun;
      }

      public String label() {
         return this.label;
      }

      public String noun() {
         return this.noun;
      }

      ItemStack icon() {
         return new ItemStack(this.icon);
      }
   }

   /** Slots 0-44 preview the records that would be restored. */
   private static final int PREVIEW_SLOTS = 45;
   private static final int CANCEL = 45;
   private static final int CONFIRM = 47;
   private static final int SUMMARY = 49;
   private static final int CLOSE = 53;

   private final SimpleContainer container;
   private final ServerPlayer viewer;
   private final Kind kind;
   private final List<String> entries;
   private final int recoverable;
   private final int loaded;
   private final boolean writable;
   private final String sourceLine;

   private RecoveryMenu(
      int syncId, Inventory playerInventory, ServerPlayer viewer, Kind kind, SimpleContainer container
   ) {
      super(MenuType.GENERIC_9x6, syncId, playerInventory, container, 6);
      this.container = container;
      this.viewer = viewer;
      this.kind = kind;
      this.entries = preview(kind);
      this.recoverable = count(kind);
      this.loaded = loadedCount(kind);
      this.writable = writable(kind);
      this.sourceLine = source(kind);
      this.rebuild();
   }

   public static void open(ServerPlayer viewer, Kind kind) {
      viewer.openMenu(
         new SimpleMenuProvider(
            (syncId, inv, p) -> new RecoveryMenu(syncId, inv, viewer, kind, new SimpleContainer(54)),
            Component.literal("§d§lRecover §f" + kind.label() + " §8- preview")
         )
      );
   }

   private static int count(Kind kind) {
      try {
         return switch (kind) {
            case CLAIMS -> com.fortuneandfavors.economy.ClaimManager.countRecoverable();
            case SPAWNERS -> com.fortuneandfavors.economy.SpawnerManager.countRecoverable();
            case MACHINES -> com.fortuneandfavors.economy.MachineManager.countRecoverable();
         };
      } catch (Throwable ignored) {
         return 0;
      }
   }

   private static int loadedCount(Kind kind) {
      try {
         return switch (kind) {
            case CLAIMS -> com.fortuneandfavors.economy.ClaimManager.claimCount();
            case SPAWNERS -> com.fortuneandfavors.economy.SpawnerManager.count();
            case MACHINES -> com.fortuneandfavors.economy.MachineManager.count();
         };
      } catch (Throwable ignored) {
         return 0;
      }
   }

   private static boolean writable(Kind kind) {
      try {
         return switch (kind) {
            case CLAIMS -> com.fortuneandfavors.economy.ClaimManager.writable();
            case SPAWNERS -> com.fortuneandfavors.economy.SpawnerManager.writable();
            case MACHINES -> com.fortuneandfavors.economy.MachineManager.writable();
         };
      } catch (Throwable ignored) {
         return false;
      }
   }

   /** The real file path plus how many of the scanned generations exist. */
   private static String source(Kind kind) {
      try {
         java.nio.file.Path file = switch (kind) {
            case CLAIMS -> com.fortuneandfavors.economy.ClaimManager.dataFile();
            case SPAWNERS -> com.fortuneandfavors.economy.SpawnerManager.dataFile();
            case MACHINES -> com.fortuneandfavors.economy.MachineManager.dataFile();
         };
         if (file == null) {
            return "not resolved yet - has the world finished loading?";
         }

         java.util.List<java.nio.file.Path> scanned = new ArrayList<>();
         scanned.add(file);
         scanned.addAll(com.fortuneandfavors.util.JsonUtil.backups(file));
         int present = 0;
         for (java.nio.file.Path p : scanned) {
            try {
               if (p != null && java.nio.file.Files.exists(p)) {
                  present++;
               }
            } catch (Exception ignored) {
            }
         }

         return present + " of " + scanned.size() + " file(s) in " + file.getParent() + "  (" + file.getFileName() + ")";
      } catch (Throwable ignored) {
         return "unknown";
      }
   }

   private static List<String> preview(Kind kind) {
      try {
         return switch (kind) {
            case CLAIMS -> com.fortuneandfavors.economy.ClaimManager.previewRecoverable(PREVIEW_SLOTS);
            case SPAWNERS -> com.fortuneandfavors.economy.SpawnerManager.previewRecoverable(PREVIEW_SLOTS);
            case MACHINES -> com.fortuneandfavors.economy.MachineManager.previewRecoverable(PREVIEW_SLOTS);
         };
      } catch (Throwable ignored) {
         return List.of();
      }
   }

   private void rebuild() {
      for (int i = 0; i < PREVIEW_SLOTS; i++) {
         if (i < this.entries.size()) {
            ItemStack stack = this.kind.icon();
            stack.set(DataComponents.CUSTOM_NAME, Component.literal(this.entries.get(i)));
            stack.set(
               DataComponents.LORE,
               new ItemLore(
                  List.of(
                     Component.literal("§7This " + this.kind.noun() + " comes back when you confirm."),
                     Component.literal("§8Nothing that is already live is touched.")
                  )
               )
            );
            this.container.setItem(i, stack);
         } else {
            this.container.setItem(i, i == 0 && this.entries.isEmpty() ? this.emptyNote() : this.filler());
         }
      }

      ItemStack summary = new ItemStack(Items.BOOK);
      summary.set(DataComponents.CUSTOM_NAME, Component.literal("§e§lRecovery summary"));
      List<Component> lines = new ArrayList<>();
      lines.add(Component.literal("§7Recoverable now: §f" + this.recoverable + " §7" + this.kind.noun() + "(s)"));
      lines.add(Component.literal("§7Already loaded: §f" + this.loaded));
      lines.add(
         Component.literal(
            "§7Writes: " + (this.writable ? "§aenabled" : "§cDISABLED §7- fix the file (see /claim status)")
         )
      );
      lines.add(Component.literal("§8" + this.sourceLine));
      if (this.entries.size() < this.recoverable) {
         lines.add(Component.literal("§8Showing " + this.entries.size() + " of " + this.recoverable + " - the rest are listed in chat."));
      }
      lines.add(Component.literal("§8Merges into memory first, then saves."));
      summary.set(DataComponents.LORE, new ItemLore(lines));
      this.container.setItem(SUMMARY, summary);

      boolean canConfirm = this.recoverable > 0 && this.writable;
      ItemStack confirm = new ItemStack(canConfirm ? Items.EMERALD_BLOCK : Items.COAL_BLOCK);
      confirm.set(
         DataComponents.CUSTOM_NAME,
         Component.literal(canConfirm ? "§a§lCONFIRM RECOVERY" : "§c§lCANNOT RECOVER")
      );
      List<Component> confirmLore = new ArrayList<>();
      if (this.recoverable <= 0) {
         confirmLore.add(Component.literal("§7Every record in the file and its backups is already loaded."));
      } else if (!this.writable) {
         confirmLore.add(Component.literal("§7Writes are disabled, so recovery cannot be persisted."));
         confirmLore.add(Component.literal("§8Fix the data file, restart, then try again."));
      } else {
         confirmLore.add(Component.literal("§7Merge §f" + this.recoverable + "§7 " + this.kind.noun() + "(s) and save."));
         confirmLore.add(Component.literal("§8Never deletes anything already live."));
      }
      confirm.set(DataComponents.LORE, new ItemLore(confirmLore));
      this.container.setItem(CONFIRM, confirm);

      ItemStack cancel = new ItemStack(Items.BARRIER);
      cancel.set(DataComponents.CUSTOM_NAME, Component.literal("§c§lCANCEL"));
      cancel.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("§7Close without changing anything."))));
      this.container.setItem(CANCEL, cancel);

      for (int i = PREVIEW_SLOTS; i < 54; i++) {
         if (i != SUMMARY && i != CONFIRM && i != CANCEL && i != CLOSE) {
            this.container.setItem(i, this.filler());
         }
      }

      ItemStack close = new ItemStack(Items.STAINED_GLASS_PANE.red());
      close.set(DataComponents.CUSTOM_NAME, Component.literal("§c§lClose"));
      this.container.setItem(CLOSE, close);
   }

   private ItemStack emptyNote() {
      ItemStack stack = new ItemStack(Items.BARRIER);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§7Nothing recoverable"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7The file and every backup hold either no " + this.kind.noun() + "(s),"),
               Component.literal("§7or only ones that are already loaded.")
            )
         )
      );
      return stack;
   }

   private ItemStack filler() {
      ItemStack stack = new ItemStack(Items.STAINED_GLASS_PANE.gray());
      stack.set(DataComponents.CUSTOM_NAME, Component.literal(""));
      return stack;
   }

   public void clicked(int slotId, int button, ContainerInput input, Player player) {
      if (!(player instanceof ServerPlayer sp)) {
         super.clicked(slotId, button, input, player);
         return;
      }

      if (slotId == CONFIRM) {
         if (this.recoverable <= 0 || !this.writable) {
            SoundUtil.play(sp, ModSounds.DENY);
            return;
         }

         SoundUtil.play(sp, ModSounds.TRANSFER);
         sp.closeContainer();
         ModCommands.performRecovery(sp, this.kind);
         return;
      }

      if (slotId == CANCEL || slotId == CLOSE) {
         sp.closeContainer();
         return;
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

   /** The viewer, so the command layer can print the chat summary after a GUI confirm. */
   public ServerPlayer viewer() {
      return this.viewer;
   }
}
