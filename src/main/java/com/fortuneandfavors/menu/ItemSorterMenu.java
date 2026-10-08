package com.fortuneandfavors.menu;

import com.fortuneandfavors.economy.ItemSorter;
import com.fortuneandfavors.economy.MachineManager;
import com.fortuneandfavors.economy.SorterLinks;
import com.fortuneandfavors.util.Chat;
import com.fortuneandfavors.util.InventoryHelper;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
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
import net.minecraft.world.level.block.Block;

/**
 * The Item Sorter's window: the list, as the items themselves, and both halves of the question the
 * block answers - what it keeps and reads from, and where each thing goes.
 *
 * <p>The window is laid out as three bands under one row of controls. The top row is the settings.
 * The middle two rows are the list: every item the sorter was told to keep, drawn as itself. The
 * bottom row is the containers it can reach, drawn as themselves - chests, barrels, hoppers. Click
 * an item to choose it, then click a container in the bottom row to send that item there, and the
 * sorter's own output setting is what everything else follows. That is the whole idea of the custom
 * output: one machine, several destinations, chosen per item instead of per machine.
 *
 * <p>The window is a vanilla chest menu, which means the client answers clicks with its own copy of
 * vanilla's rules before the server sees them. That is why the top of {@link #clicked} reads the
 * stack out of the slot the player actually clicked and then lets vanilla finish the click: any
 * other answer would leave the window and the server holding different ideas about where the item
 * is. The slot that is read is the slot itself and never a number worked out from the slot id - the
 * player's own inventory is not in the same order as the grid it is drawn in, so a subtraction is a
 * way to filter the wrong item and never notice.
 */
public class ItemSorterMenu extends ChestMenu {
   private static final int INFO = 4;
   /** What the sorter keeps: hoppers, any container that is not a sorter, or both. */
   private static final int READING = 0;
   /** Where everything that has no destination of its own goes. */
   private static final int OUTPUT = 1;
   /** That same default output, pinned to one container. */
   private static final int TARGET = 2;
   /** Takes the chosen item out of the list. */
   private static final int REMOVE = 3;
   private static final int CLEAR = 7;
   private static final int CLOSE = 8;
   /** Transfer Hopper only: the tag everything it holds is sent to. */
   private static final int OUT_TAG = 5;
   /**
    * Rows 1 and 2 hold the list: eighteen slots, which is exactly {@link ItemSorter#FILTER_MAX}.
    *
    * <p>The controls all live in the top row. That is not a style choice: an earlier window put its
    * close button at 22, which is the fourteenth list slot, so a sorter holding fourteen or more
    * item types silently lost its close button - and clicking the entry that replaced it closed the
    * window instead of removing anything.
    */
   private static final int FILTER_FIRST = 9;
   private static final int FILTER_LAST = 26;
   /** The fourth row: the containers in reach, then the "follow the default" slot. */
   private static final int DEST_FIRST = 27;
   private static final int DEST_MAX = 8;
   private static final int DEST_DEFAULT = DEST_FIRST + DEST_MAX;
   /**
    * The fifth row: the tagged containers this sorter can be pointed at, then the "drop them all"
    * slot. This is the remote input - a chest across the base, tagged with a Sorter Tag, named by a
    * sorter.
    */
   private static final int LINK_FIRST = 36;
   private static final int LINK_MAX = 8;
   private static final int LINK_CLEAR = LINK_FIRST + LINK_MAX;
   /** Where the player's own pack starts in a five-row chest window. */
   private static final int PACK_FIRST = 45;
   private final SimpleContainer container;
   private final ServerPlayer owner;
   private final BlockPos pos;
   /** The item whose destination the next container click will set. Chosen in the window only. */
   private String chosen;

   public ItemSorterMenu(int syncId, Inventory playerInventory, BlockPos pos) {
      this(syncId, playerInventory, pos, new SimpleContainer(45));
   }

   private ItemSorterMenu(int syncId, Inventory playerInventory, BlockPos pos, SimpleContainer container) {
      super(MenuType.GENERIC_9x5, syncId, playerInventory, container, 5);
      this.container = container;
      this.owner = (ServerPlayer)playerInventory.player;
      this.pos = pos;
      this.rebuild();
   }

   public static void open(ServerPlayer player, BlockPos pos) {
      String title = MachineManager.isOverflowHopper(player.level(), pos)
         ? "§c§lOverflow Hopper"
         : MachineManager.isTransferHopper(player.level(), pos) ? "§d§lTransfer Hopper" : "§3§lItem Sorter";
      player.openMenu(new SimpleMenuProvider(
         (syncId, inv, p) -> new ItemSorterMenu(syncId, inv, pos), Component.literal(title)
      ));
   }

   /**
    * True when this window is standing on either transfer hopper rather than a sorter.
    *
    * <p>Both halves of the family get the same window because they are the same machine: the filter
    * is gone, the tags are at both ends, and the only thing that differs is which end of the
    * destination list the output tag sits on. That difference is what {@link #isOverflow} is for.
    */
   private boolean isTransfer() {
      return MachineManager.isTransferFamily(this.owner.level(), this.pos);
   }

   /** True when the tag is a spillway rather than a destination. */
   private boolean isOverflow() {
      return MachineManager.isOverflowHopper(this.owner.level(), this.pos);
   }

   /** "x,y,z", for printing where the chest being filled is. */
   private static String at(BlockPos pos) {
      return pos.getX() + "," + pos.getY() + "," + pos.getZ();
   }

   private ItemStack named(Item item, String name, List<Component> lore) {
      ItemStack stack = new ItemStack(item);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
      if (!lore.isEmpty()) {
         stack.set(DataComponents.LORE, new ItemLore(lore));
      }
      return stack;
   }

   /** Adds the one visual that says "this is the one you chose": the enchanted shimmer. */
   private ItemStack glint(ItemStack stack) {
      stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      return stack;
   }

   /** The live list, in the order it was built up. */
   private List<String> filter() {
      return new ArrayList<>(ItemSorter.entry(this.owner.level(), this.pos).items());
   }

   private List<BlockPos> destinations(ItemSorter.Entry entry) {
      List<BlockPos> candidates = ItemSorter.outputCandidates(this.owner.level(), this.pos);
      return candidates.size() > DEST_MAX ? candidates.subList(0, DEST_MAX) : candidates;
   }

   /** The marks this sorter may be pointed at: nearest first, capped at one row. */
   private List<SorterLinks.Link> tags() {
      List<SorterLinks.Link> reachable = SorterLinks.reachable(
         this.owner.level(), this.pos, MachineManager.ownerAt(this.owner.level(), this.pos)
      );
      return reachable.size() > LINK_MAX ? reachable.subList(0, LINK_MAX) : reachable;
   }

   private void rebuild() {
      this.container.clearContent();
      List<String> items = this.filter();
      ItemSorter.Entry entry = ItemSorter.entry(this.owner.level(), this.pos);
      List<BlockPos> dests = this.destinations(entry);
      if (this.chosen != null && !entry.contains(this.chosen)) {
         this.chosen = null;
      }
      String chosenName = this.chosen == null ? null : shortName(this.chosen);
      boolean transfer = this.isTransfer();
      boolean overflow = this.isOverflow();
      List<Component> info = new ArrayList<>();
      if (overflow) {
         BlockPos filling = ItemSorter.fillingTarget(this.owner.level(), this.pos);
         boolean full = ItemSorter.fillingChestFull(this.owner.level(), this.pos);
         info.add(Component.literal("§7Fills first, spills second - a Transfer Hopper"));
         info.add(Component.literal("§7whose output is where the surplus goes, not everything."));
         info.add(Component.literal("§7Filling: §f" + (filling == null
            ? "nothing - no container on its route"
            : blockName(filling) + " at " + at(filling))));
         info.add(Component.literal(filling == null
            ? "§8Nothing to fill, so there is nothing to run over."
            : full
               ? "§c§lFULL§7 - everything else is spilling over."
               : "§a§lHAS ROOM§7 - nothing is spilling over yet."));
         info.add(Component.literal("§7Spilling to: §f"
            + (entry.outTag() == null ? "nowhere - the surplus stays here" : "#" + entry.outTag())));
         info.add(Component.literal("§8Full = no empty slot and no stack with room in it."));
         info.add(Component.literal(""));
         info.add(Component.literal("§eStep 1: click a tagged container below to pull from it."));
         info.add(Component.literal("§eStep 2: shift-click one to make it the spillway."));
         info.add(Component.literal("§8Or click §fSpill to§8 to step through the tags."));
         info.add(Component.literal("§8Tag a second Transfer Hopper to chain the surplus."));
      } else if (transfer) {
         info.add(Component.literal("§7Moves §feverything§7 it can reach."));
         info.add(Component.literal("§7Inputs: the containers around it and the tags below."));
         info.add(Component.literal("§7Output: §f" + (entry.outTag() == null ? "the way it faces" : "#" + entry.outTag())));
         info.add(Component.literal("§8The output is never read from as well."));
         info.add(Component.literal(""));
         info.add(Component.literal("§eStep 1: click a tagged container below to pull from it."));
         info.add(Component.literal("§eStep 2: shift-click one to send everything to it."));
         info.add(Component.literal("§8Or click §fSend to§8 to step through the tags."));
         info.add(Component.literal("§8Tag a second Transfer Hopper to chain them."));
      } else {
         info.add(Component.literal("§7Keeping §f" + items.size() + "§7/§f" + ItemSorter.FILTER_MAX + "§7 item types."));
         info.add(Component.literal("§7Reads from: §f" + modeName(entry.mode())));
         info.add(Component.literal("§7Everything else goes: §f" + ItemSorter.outputName(entry)));
         info.add(Component.literal(""));
         info.add(Component.literal("§eStep 1: shift-click an item in your pack to keep it."));
         info.add(Component.literal("§eStep 2: click a container below to send it there."));
         info.add(Component.literal("§8A plain click keeps the item too."));
         info.add(Component.literal("§7Remote inputs: §f" + (entry.links().isEmpty() ? "none" : String.join("§7, §f", entry.links()))));
         info.add(Component.literal(chosenName == null
            ? "§eClick a keep-item below, then a container."
            : "§eNow choose where §f" + chosenName + "§e goes."));
      }
      this.container.setItem(INFO, this.named(
         Items.HOPPER,
         overflow ? "§c§lOverflow Hopper" : transfer ? "§d§lTransfer Hopper" : "§3§lItem Sorter",
         info
      ));
      if (overflow) {
         this.container.setItem(READING, this.named(
            Items.HOPPER,
            "§c§lFills first, spills second",
            List.of(
               Component.literal("§7It fills the container it points at exactly"),
               Component.literal("§7as a Transfer Hopper does - and the pulse that"),
               Component.literal("§7container will not take a stack, that stack goes"),
               Component.literal("§7to the spillway instead of sitting here."),
               Component.literal("§8With no spillway named an overflow simply stops"),
               Component.literal("§8where it is, the way a full hopper does."),
               Component.literal("§8Use the bottom row to add remote inputs.")
            )
         ));
      } else if (transfer) {
         this.container.setItem(READING, this.named(
            Items.HOPPER,
            "§3§lMoves everything",
            List.of(
               Component.literal("§7A transfer hopper keeps no filter - it takes"),
               Component.literal("§7whatever it can reach and passes it on."),
               Component.literal("§8Use the bottom row to add remote inputs.")
            )
         ));
      } else {
         this.container.setItem(READING, this.named(
            Items.COMPARATOR,
            "§3§lStep 1 - Read from: §f" + modeName(entry.mode()),
            List.of(
               Component.literal("§7Where it pulls from:"),
               Component.literal("§7hoppers only §8- §7any container §8- §7both."),
               Component.literal("§8A sorter never pulls out of a sorter."),
               Component.literal(""),
               Component.literal("§eClick to cycle.")
            )
         ));
      }
      this.container.setItem(OUTPUT, this.named(
         outputIcon(entry),
         "§3§lStep 2 - Send to: §f" + ItemSorter.outputName(entry),
         List.of(
            Component.literal("§7Where unclaimed, unbound items go:"),
            Component.literal("§8the way it points, then up, then around §7(auto),"),
            Component.literal("§8the nearest container in reach§7, or"),
            Component.literal("§8one container you pin below§7."),
            Component.literal(""),
            Component.literal("§eClick to cycle.")
         )
      ));
      this.container.setItem(TARGET, this.named(
         Items.TARGET,
         entry.target() == null ? "§6§lPin that output" : "§a§lPinned output",
         List.of(
            Component.literal(dests.isEmpty()
               ? "§8No chest, barrel or hopper within reach."
               : "§7" + dests.size() + " container(s) in reach."),
            Component.literal(entry.target() == null ? "§7Nothing is pinned yet." : "§7Now: §f" + entry.target()),
            Component.literal(""),
            Component.literal("§eClick a container in the bottom row instead"),
            Component.literal("§eto pin it to one item, or use this to"),
            Component.literal("§estep the default output through them.")
         )
      ));
      this.container.setItem(REMOVE, this.named(
         Items.SHEARS,
         chosenName == null ? "§8Nothing chosen" : "§c§lRemove " + chosenName,
         List.of(
            Component.literal(chosenName == null
               ? "§7Click an item in the list first."
               : "§7Takes it out of the list - the sorter"),
            Component.literal(chosenName == null ? "" : "§7stops keeping it entirely."),
            Component.literal(""),
            Component.literal("§8Shift-clicking a list item does the same.")
         )
      ));
      this.container.setItem(CLEAR, this.named(
         items.isEmpty() ? Items.DYE.gray() : Items.LAVA_BUCKET,
         items.isEmpty() ? "§8List already empty" : "§c§lEmpty the list",
         List.of(Component.literal("§7Removes all " + items.size() + " item type(s)."))
      ));
      this.container.setItem(CLOSE, this.named(Items.BARRIER, "§cClose", List.of()));
      if (transfer) {
         String outTag = entry.outTag();
         String outTitle = outTag == null
            ? (overflow ? "§6§lSpill to: §fnothing" : "§6§lSend to: §fthe way it faces")
            : (overflow ? "§a§lSpill to: §f#" + outTag : "§a§lSend to: §f#" + outTag);
         List<Component> outLore = overflow
            ? List.of(
               Component.literal("§7Only what will not fit in front of it goes here."),
               Component.literal("§8With none chosen the surplus stays in the hopper."),
               Component.literal(""),
               Component.literal("§8A chest named here is a §fsink§8: the hopper"),
               Component.literal("§8never pulls back out of it, not even when it"),
               Component.literal("§8stands right beside the hopper."),
               Component.literal("§8Name another Transfer Hopper here to chain the"),
               Component.literal("§8surplus on down the line."),
               Component.literal(""),
               Component.literal("§eClick to step through the tags in reach,"),
               Component.literal("§eand past the last one to clear it.")
            )
            : List.of(
               Component.literal("§7Everything this hopper holds goes here."),
               Component.literal("§8With none chosen it hands on the way it faces."),
               Component.literal(""),
               Component.literal("§8A chest named here is a §fsink§8: the hopper"),
               Component.literal("§8never pulls back out of it, not even when it"),
               Component.literal("§8stands right beside the hopper."),
               Component.literal("§8Name another Transfer Hopper here to chain them."),
               Component.literal(""),
               Component.literal("§eClick to step through the tags in reach,"),
               Component.literal("§eand past the last one to clear it.")
            );
         this.container.setItem(OUT_TAG, this.named(
            outTag == null ? Items.COMPASS : Items.OAK_SIGN, outTitle, outLore
         ));
      }

      for (int i = 0; i < items.size() && i < ItemSorter.FILTER_MAX; i++) {
         String id = items.get(i);
         String destination = entry.destination(id);
         boolean bound = ItemSorter.positionOf(destination) != null;
         ItemStack icon = this.named(
            iconFor(id),
            (id.equals(this.chosen) ? "§a§l> " : "§f") + shortName(id),
            List.of(
               Component.literal(bound ? "§7Goes to §f" + destination : "§7Goes the sorter's own way."),
               Component.literal(bound && !this.isReachable(dests, destination) ? "§cThat container is gone or out of reach." : ""),
               Component.literal("§8" + id),
               Component.literal(""),
               Component.literal(id.equals(this.chosen) ? "§aChosen - click a container below." : "§eClick to choose it."),
               Component.literal("§8Shift-click to take it out of the list.")
            )
         );
         this.container.setItem(FILTER_FIRST + i, id.equals(this.chosen) ? this.glint(icon) : icon);
      }

      for (int i = 0; i < dests.size() && i < DEST_MAX; i++) {
         BlockPos at = dests.get(i);
         String key = at.getX() + "," + at.getY() + "," + at.getZ();
         boolean isDefault = key.equals(entry.target());
         this.container.setItem(DEST_FIRST + i, this.named(
            blockIcon(at),
            (isDefault ? "§6§l" : "§e§l") + blockName(at),
            List.of(
               Component.literal("§7At §f" + key),
               Component.literal(""),
               Component.literal(this.chosen == null
                  ? "§eClick to pin the default output here."
                  : "§eClick to send §f" + chosenName + "§e here."),
               Component.literal(isDefault ? "§6The default output is already pinned here." : "")
            )
         ));
      }
      this.container.setItem(DEST_DEFAULT, this.named(
         Items.COMPASS,
         "§7§lFollow the default",
         List.of(
            Component.literal(this.chosen == null
               ? "§7The sorter's own output is §f" + ItemSorter.outputName(entry)
               : "§7Sends §f" + chosenName + "§7 back to the sorter's own output."),
            Component.literal(""),
            Component.literal(this.chosen == null ? "§eClick to unpin the default output." : "§eClick to uncouple it.")
         )
      ));

      List<SorterLinks.Link> tags = this.tags();
      for (int i = 0; i < tags.size() && i < LINK_MAX; i++) {
         SorterLinks.Link link = tags.get(i);
         boolean linked = entry.isLinked(link.id());
         boolean sentHere = transfer && link.id().equals(entry.outTag());
         ItemStack icon = this.named(
            blockIcon(link.pos()),
            (sentHere ? "§d§l>> " : linked ? "§a§l" : "§7§l") + "#" + link.id(),
            List.of(
               Component.literal("§7A tagged " + blockName(link.pos()) + " at §f"
                  + link.pos().getX() + ", " + link.pos().getY() + ", " + link.pos().getZ()),
               Component.literal(link.ownerName().isBlank() ? "" : "§7Tagged by §f" + link.ownerName()),
               Component.literal(""),
               Component.literal(linked
                  ? (transfer ? "§aThis hopper pulls from here." : "§aThe sorter pulls from here when nothing beside it matches.")
                  : "§eClick to pull from this container too."),
               Component.literal(sentHere
                  ? (overflow ? "§dThe overflow goes here" : "§dEverything it holds goes here")
                  : ""),
               Component.literal(sentHere ? "§d- and it is never read from, even beside the hopper." : ""),
               // The transfer hopper takes a tag at both ends, and this is the second one: the
               // click that is already about tags, held down, is how a chest is named as the
               // destination (or for an Overflow Hopper, the spillway) rather than another source.
               Component.literal(transfer
                  ? (overflow
                     ? "§eShift-click to make it the §fspillway§e instead."
                     : "§eShift-click to make it the §foutput§e instead.")
                  : ""),
               Component.literal(transfer && sentHere ? "§8Shift-click again to take it off." : ""),
               Component.literal(transfer ? "§8A tag is an input or the output, never both." : ""),
               Component.literal(transfer ? "§8A tagged Transfer Hopper makes a chain." : ""),
               Component.literal("§8Out of reach or in an unloaded chunk, it finds nothing.")
            )
         );
         this.container.setItem(LINK_FIRST + i, sentHere ? this.glint(icon) : linked ? this.glint(icon) : icon);
      }
      this.container.setItem(LINK_CLEAR, this.named(
         entry.links().isEmpty() ? Items.DYE.gray() : Items.OAK_SIGN,
         entry.links().isEmpty() ? "§8No remote inputs" : "§c§lDrop all " + entry.links().size() + " remote input(s)",
         List.of(
            Component.literal("§7A remote input is a container you tagged"),
            Component.literal("§7with a §fSorter Tag§7 - right-click a chest with it,"),
            Component.literal("§7or rename it first to name the tag yourself."),
            Component.literal("§8The sorter only reaches 32 blocks, and only a"),
            Component.literal("§8loaded chunk has a container in it."),
            Component.literal(""),
            Component.literal(entry.links().isEmpty() ? "§8Nothing to drop." : "§eClick to stop following them all.")
         )
      ));
   }

   private boolean isReachable(List<BlockPos> dests, String key) {
      for (BlockPos at : dests) {
         if (key.equals(at.getX() + "," + at.getY() + "," + at.getZ())) {
            return true;
         }
      }
      return false;
   }

   private static Item outputIcon(ItemSorter.Entry entry) {
      return switch (entry.outMode()) {
         case ItemSorter.OUT_NEARBY -> Items.BARREL;
         case ItemSorter.OUT_PINNED -> Items.CHEST;
         default -> Items.DROPPER;
      };
   }

   /** The container's own item, so the bottom row is a picture of the base and not of the settings. */
   private Item blockIcon(BlockPos at) {
      try {
         Block block = this.owner.level().getBlockState(at).getBlock();
         Item item = block.asItem();
         if (item != Items.AIR) {
            return item;
         }
      } catch (Throwable ignored) {
      }
      return Items.CHEST;
   }

   private String blockName(BlockPos at) {
      try {
         return BuiltInRegistries.BLOCK.getKey(this.owner.level().getBlockState(at).getBlock()).getPath().replace('_', ' ');
      } catch (Throwable ignored) {
         return "container";
      }
   }

   private static String modeName(String mode) {
      return switch (mode) {
         case ItemSorter.MODE_CONTAINERS -> "§fany container beside it";
         case ItemSorter.MODE_BOTH -> "§fhoppers and other containers";
         default -> "§fhoppers only";
      };
   }

   private static String shortName(String id) {
      int colon = id.indexOf(58);
      String path = colon < 0 ? id : id.substring(colon + 1);
      return path.replace('_', ' ');
   }

   /** The real item behind a list entry, so the window shows the actual thing. */
   private static Item iconFor(String id) {
      try {
         var key = net.minecraft.resources.Identifier.tryParse(id);
         Item item = key == null ? null : BuiltInRegistries.ITEM.getValue(key);
         if (item != null && item != Items.AIR) {
            return item;
         }
      } catch (Throwable ignored) {
      }
      return Items.PAPER;
   }

   /** Removes one entry by its own id - the list is a map, so this cannot remove the wrong thing. */
   private void remove(ServerPlayer sp, String id) {
      ItemSorter.Entry entry = ItemSorter.entry(sp.level(), this.pos);
      if (entry.remove(id)) {
         if (id.equals(this.chosen)) {
            this.chosen = null;
         }
         ItemSorter.save(sp.level().getServer());
         Chat.msg(sp, "&7Removed &f" + shortName(id) + "&7 from the sorter's list (" + entry.size() + " left).");
      }
   }

   private void clear(ServerPlayer sp) {
      ItemSorter.Entry entry = ItemSorter.entry(sp.level(), this.pos);
      int had = entry.size();
      entry.clear();
      this.chosen = null;
      ItemSorter.save(sp.level().getServer());
      Chat.msg(sp, had == 0
         ? "&7The list was already empty."
         : "&cCleared &f" + had + "&c item type(s) - the sorter will move nothing until it is given a list again.");
   }

   /** Nothing in this window is a container, so anything on the cursor comes back to the player. */
   private void returnCarried(ServerPlayer sp) {
      ItemStack carried = this.getCarried();
      if (!carried.isEmpty()) {
         this.setCarried(ItemStack.EMPTY);
         InventoryHelper.giveOrDrop(sp, carried);
      }
   }

   @Override
   public void clicked(int slotId, int button, ContainerInput input, Player player) {
      if (!(player instanceof ServerPlayer sp)) {
         super.clicked(slotId, button, input, player);
         return;
      }
      boolean transfer = this.isTransfer();
      if (!MachineManager.isItemSorter(sp.level(), this.pos) && !transfer) {
         // The block went out from under the window. Asking for the list now would create a settings
         // entry for a sorter that is not there, so the window closes instead.
         this.returnCarried(sp);
         Chat.msg(sp, "&cThat machine is gone.");
         sp.closeContainer();
         return;
      }
      if (slotId >= PACK_FIRST) {
         // The player's own inventory - this is how an item is put into the list. The stack is read
         // from the slot that was clicked, before vanilla moves it: shift-click adds without picking
         // anything up, and a plain click adds while vanilla lifts the stack on to the cursor, which
         // is what the client already drew.
         ItemStack clicked = this.getSlot(slotId).getItem();
         if (!transfer && !clicked.isEmpty() && (input == ContainerInput.QUICK_MOVE || this.getCarried().isEmpty())) {
            ItemSorter.toggleItem(sp, sp.level(), this.pos, clicked);
         }
         super.clicked(slotId, button, input, player);
         this.rebuild();
         this.broadcastChanges();
         return;
      }
      if (slotId == CLOSE) {
         this.returnCarried(sp);
         sp.closeContainer();
         return;
      }
      if (slotId >= LINK_FIRST && slotId < LINK_FIRST + LINK_MAX) {
         List<SorterLinks.Link> tags = this.tags();
         int index = slotId - LINK_FIRST;
         if (index < tags.size()) {
            String id = tags.get(index).id();
            if (transfer && input == ContainerInput.QUICK_MOVE) {
               ItemSorter.setOutTag(sp, sp.level(), this.pos, id);
            } else {
               ItemSorter.toggleLink(sp, sp.level(), this.pos, id);
            }
         }
      } else if (slotId == LINK_CLEAR) {
         ItemSorter.clearLinks(sp, sp.level(), this.pos);
      } else if (slotId == OUT_TAG && transfer) {
         ItemSorter.cycleOutTag(sp, sp.level(), this.pos);
      } else if (slotId >= DEST_FIRST && slotId < DEST_FIRST + DEST_MAX) {
         List<BlockPos> dests = this.destinations(ItemSorter.entry(sp.level(), this.pos));
         int index = slotId - DEST_FIRST;
         if (index < dests.size()) {
            if (this.chosen == null) {
               ItemSorter.pinDefault(sp, sp.level(), this.pos, dests.get(index));
            } else {
               ItemSorter.bindItem(sp, sp.level(), this.pos, this.chosen, dests.get(index));
            }
         }
      } else if (slotId == DEST_DEFAULT) {
         if (this.chosen == null) {
            ItemSorter.unpinDefault(sp, sp.level(), this.pos);
         } else {
            ItemSorter.unbindItem(sp, sp.level(), this.pos, this.chosen);
         }
      } else if (slotId >= FILTER_FIRST && slotId <= FILTER_LAST) {
         List<String> items = this.filter();
         int index = slotId - FILTER_FIRST;
         if (index < items.size()) {
            String id = items.get(index);
            if (input == ContainerInput.QUICK_MOVE) {
               this.remove(sp, id);
            } else {
               // Choose it, or unchoose it: the next container click is what acts on it.
               this.chosen = id.equals(this.chosen) ? null : id;
            }
         }
      } else if (slotId == INFO) {
         // The header only has room for a line or two; the whole list goes to chat.
         ItemSorter.describe(sp, sp.level(), this.pos);
      } else if (slotId == READING && !transfer) {
         ItemSorter.cycleMode(sp, sp.level(), this.pos);
      } else if (slotId == OUTPUT) {
         ItemSorter.cycleOutput(sp, sp.level(), this.pos);
      } else if (slotId == TARGET) {
         ItemSorter.cycleTarget(sp, sp.level(), this.pos);
      } else if (slotId == REMOVE) {
         if (this.chosen != null) {
            this.remove(sp, this.chosen);
         }
      } else if (slotId == CLEAR) {
         this.clear(sp);
      }
      this.returnCarried(sp);
      this.rebuild();
      this.broadcastChanges();
   }

   /** Nothing here is a container - shift-clicking into or out of this window is refused. */
   @Override
   public ItemStack quickMoveStack(Player player, int index) {
      return ItemStack.EMPTY;
   }
}
