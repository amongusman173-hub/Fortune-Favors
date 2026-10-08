package com.fortuneandfavors.menu;

import com.fortuneandfavors.ModItems;
import com.fortuneandfavors.ModSounds;
import com.fortuneandfavors.economy.BackpackJukebox;
import com.fortuneandfavors.util.Chat;
import com.fortuneandfavors.util.InventoryHelper;
import com.fortuneandfavors.util.SoundUtil;
import com.mojang.serialization.DataResult;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.HashedStack;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Blocks;

public class BackpackMenu extends ChestMenu {
   public static final String CONTENTS_KEY = "ff_bp";
   private final SimpleContainer container = (SimpleContainer)this.getContainer();
   private final ServerPlayer owner;
   private final int tier;
   private final ItemStack backpackStack;
   private static final Map<BlockPos, ItemStack> placedBackpacks = new HashMap<>();
   private static final Map<UUID, ItemStack> pendingPlaced = new HashMap<>();

   public BackpackMenu(int syncId, Inventory playerInventory, ItemStack backpack, int tier) {
      super(tierMenuType(tier), syncId, playerInventory, new SimpleContainer(tierSlots(tier)), tierRows(tier));
      this.owner = (ServerPlayer)playerInventory.player;
      this.tier = tier;
      this.backpackStack = backpack;
      this.loadContents();
      this.buildToolbar();
      // If a disc is loaded in the jukebox, the music keeps going (or comes
      // back) - reopening the pack never restarts a song that is spinning.
      BackpackJukebox.ensurePlaying(this.owner, this.backpackStack);
      this.ready = true;
   }

   /** True once the constructor has finished, so the change listener that
    *  persists contents cannot fire while the menu is still being built. */
   private boolean ready;

   /**
    * Persist on <em>every</em> content change, not only on close.
    *
    * <p>Contents used to be written back to the item in {@link #removed}, which
    * means the item in the inventory was stale for as long as the menu was open.
    * Dying with the pack open - where the death path copies (or drops) the item
    * before the menu is closed - therefore saved a backpack that was missing
    * everything put into it since it was opened. Writing through on each change
    * makes the item itself the single source of truth, so a death, a crash, a
    * disconnect or a dimension change can no longer lose the contents.
    */
   @Override
   public void slotsChanged(net.minecraft.world.Container container) {
      super.slotsChanged(container);
      if (this.ready && this.owner != null && this.backpackStack != null && !this.backpackStack.isEmpty()) {
         this.saveContents();
      }
   }

   /** The live backpack stack this menu is editing - the jukebox needs it to
    *  keep the music running for a placed (or off-hand) pack that isn't in the
    *  player's inventory while the menu is open. */
   public ItemStack jukeboxStack() {
      return this.backpackStack;
   }

   public static void open(ServerPlayer player, ItemStack backpack) {
      int tier = ModItems.backpackTier(backpack);
      player.openMenu(
         new SimpleMenuProvider((syncId, inv, p) -> new BackpackMenu(syncId, inv, backpack, tier), Component.literal("§6§lBackpack §7[" + tierName(tier) + "]"))
      );
   }

   /** Tiers 1-4 grow by one row so the workbench buttons get their own row;
    *  the 6-row max tier keeps full height and the buttons live in the last
    *  three slots of its bottom row instead. */
   private static MenuType<?> tierMenuType(int tier) {
      return switch (tier) {
         case 1 -> MenuType.GENERIC_9x2;
         case 2 -> MenuType.GENERIC_9x3;
         case 3 -> MenuType.GENERIC_9x4;
         case 4 -> MenuType.GENERIC_9x5;
         default -> MenuType.GENERIC_9x6;
      };
   }

   private static int tierSlots(int tier) {
      return tier == 5 ? 54 : (tier + 1) * 9;
   }

   private static int tierRows(int tier) {
      return tier == 5 ? 6 : tier + 1;
   }

   /** Storage slots only - the toolbar slots after these are buttons. */
   private static int storageSlots(int tier) {
      return tier == 5 ? 51 : tier * 9;
   }

   private static String tierName(int tier) {
      return ModItems.backpackTierName(tier);
   }

   private void loadContents() {
      CustomData custom = (CustomData)this.backpackStack.get(DataComponents.CUSTOM_DATA);
      if (custom != null) {
         CompoundTag tag = custom.copyTag();
         ListTag items = tag.getList("ff_bp").orElse(new ListTag());

         for (int i = 0; i < Math.min(items.size(), storageSlots(this.tier)); i++) {
            CompoundTag itemTag = items.getCompound(i).orElse(new CompoundTag());
            ItemStack loaded = ItemStack.OPTIONAL_CODEC
               .parse(RegistryOps.create(NbtOps.INSTANCE, this.owner.registryAccess()), itemTag)
               .result()
               .orElse(ItemStack.EMPTY);
            this.container.setItem(i, loaded);
         }

         // Overflow safety: an older max-tier backpack could hold items in slots
         // that are now toolbar buttons (51-53). Hand those back instead of
         // letting them silently vanish on the next save.
         for (int i = storageSlots(this.tier); i < Math.min(items.size(), this.container.getContainerSize()); i++) {
            CompoundTag itemTag = items.getCompound(i).orElse(new CompoundTag());
            if (itemTag.isEmpty()) {
               continue;
            }
            ItemStack extra = ItemStack.OPTIONAL_CODEC
               .parse(RegistryOps.create(NbtOps.INSTANCE, this.owner.registryAccess()), itemTag)
               .result()
               .orElse(ItemStack.EMPTY);
            if (!extra.isEmpty()) {
               InventoryHelper.giveOrDrop(this.owner, extra);
            }
         }
      }
   }

   private void saveContents() {
      CompoundTag tag = new CompoundTag();
      CustomData existing = (CustomData)this.backpackStack.get(DataComponents.CUSTOM_DATA);
      if (existing != null) {
         tag = existing.copyTag();
      }

      ListTag items = new ListTag();

      for (int i = 0; i < storageSlots(this.tier); i++) {
         ItemStack stack = this.container.getItem(i);
         if (!stack.isEmpty()) {
            DataResult<Tag> res = ItemStack.OPTIONAL_CODEC.encodeStart(RegistryOps.create(NbtOps.INSTANCE, this.owner.registryAccess()), stack);
            res.result().ifPresent(items::add);
         }
      }

      tag.put("ff_bp", items);
      this.backpackStack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
   }

   public void removed(Player player) {
      super.removed(player);
      if (player instanceof ServerPlayer sp) {
         this.saveContents();
         SoundUtil.play(sp, ModSounds.BACKPACK_CLOSE);
      }
   }

   public void clicked(int slotId, int button, ContainerInput input, Player player) {
      if (player instanceof ServerPlayer sp) {
         int start = storageSlots(this.tier);
         if (slotId == start) {
            // Workbench: only packs with a Crafting Table fused in at the
            // Item Forge have a built-in 3x3 crafting table.
            if (!ModItems.backpackHasWorkbench(this.backpackStack)) {
               SoundUtil.play(sp, ModSounds.DENY);
               Chat.msg(sp, "&7Fuse this backpack with a &fCrafting Table&7 in the &6Item Forge&7 to build in a Workbench.");
               return;
            }
            this.returnCarried(sp);
            sp.closeContainer();
            SoundUtil.play(sp, ModSounds.BACKPACK);
            sp.openMenu(
               new SimpleMenuProvider(
                  (syncId, inv, p) -> new BackpackMenu.PortableCraftingMenu(syncId, inv, sp.level(), sp.blockPosition()),
                  Component.literal("§6§lWorkbench")
               )
            );
            return;
         }
         if (slotId == start + 3) {
            // Kitchen: the manual cook, plus the answer when there is nothing to cook.
            String kind = com.fortuneandfavors.economy.BackpackKitchen.kindOf(this.backpackStack);
            if (kind == null) {
               SoundUtil.play(sp, ModSounds.DENY);
               Chat.msg(sp, "&7Fuse this backpack with a &fPortable Furnace&7 (or a &fPortable Campfire&7) in the &6Item Forge&7 to cook with it.");
               return;
            }
            this.saveContents();
            Chat.msg(sp, com.fortuneandfavors.economy.BackpackKitchen.cookBurst(sp));
            SoundUtil.play(sp, ModSounds.BACKPACK);
            this.buildToolbar();
            this.broadcastChanges();
            return;
         }
         if (slotId == start + 1) {
            // Ender chest: save the pack, close it, open the shared ender chest.
            // Ender chest: only packs fused with an Ender Pouch have the
            // built-in ender chest button.
            if (!ModItems.isEnderBackpack(this.backpackStack)) {
               SoundUtil.play(sp, ModSounds.DENY);
               Chat.msg(sp, "&7Fuse this backpack with an &5Ender Pouch&7 in the &6Item Forge&7 to build in an Ender Chest.");
               return;
            }
            this.returnCarried(sp);
            sp.closeContainer();
            SoundUtil.play(sp, ModSounds.BACKPACK);
            sp.openMenu(
               new SimpleMenuProvider(
                  (syncId, inv, p) -> ChestMenu.threeRows(syncId, inv, sp.getEnderChestInventory()),
                  Component.literal("§5§lEnder Chest")
               )
            );
            return;
         }
         if (slotId == start + 2) {
            // Jukebox: click with a music disc on the cursor to load it (the
            // disc disappears into the pack and the music follows you);
            // click empty-handed to get the disc back and stop the music.
            if (!ModItems.backpackHasJukebox(this.backpackStack)) {
               SoundUtil.play(sp, ModSounds.DENY);
               Chat.msg(sp, "&7Fuse this backpack with a &bJukebox&7 in the &6Item Forge&7 to build one in.");
               return;
            }
            ItemStack carried = this.getCarried();
            ItemStack stored = BackpackJukebox.storedDisc(this.backpackStack, sp.registryAccess());
            if (!stored.isEmpty()) {
               if (!carried.isEmpty()) {
                  SoundUtil.play(sp, ModSounds.DENY);
                  Chat.msg(sp, "&7The jukebox is playing &r" + stored.getHoverName().getString() + "&7. Click it empty-handed to eject the disc.");
                  return;
               }
               ItemStack disc = BackpackJukebox.eject(sp, this.backpackStack);
               if (!disc.isEmpty()) {
                  InventoryHelper.giveOrDrop(sp, disc);
                  Chat.raw(sp, "&b♪ &7Disc ejected - " + disc.getHoverName().getString() + "&7.");
               }
               this.buildToolbar();
               this.broadcastChanges();
               return;
            }
            if (carried.isEmpty()) {
               SoundUtil.play(sp, ModSounds.DENY);
               Chat.msg(sp, "&7Hold a &bmusic disc&7 on your cursor and click the jukebox to play it on the go.");
               return;
            }
            if (!BackpackJukebox.isDisc(carried)) {
               SoundUtil.play(sp, ModSounds.DENY);
               Chat.msg(sp, "&cThat's not a music disc.");
               return;
            }
            BackpackJukebox.insert(sp, this.backpackStack, carried);
            if (carried.isEmpty()) {
               this.setCarried(ItemStack.EMPTY);
               this.setRemoteCarried(HashedStack.EMPTY);
            }
            this.buildToolbar();
            this.broadcastChanges();
            return;
         }
         super.clicked(slotId, button, input, player);
      } else {
         super.clicked(slotId, button, input, player);
      }
   }

   /**
    * Shift-click, in both directions, without ever touching the toolbar.
    *
    * <p>Two bugs lived in the three lines this replaces. The guard read "any index at or
    * past the storage slots is a button", and the player's own inventory slots are also
    * past them - so shift-clicking an item INTO the backpack did nothing at all, which is
    * the report. And the direction that did work went through vanilla's own {@code ChestMenu},
    * which moves things into the container's whole slot range: the toolbar row included, so
    * a shift-clicked stack could land on top of the Workbench button and be replaced when
    * the toolbar was drawn.
    *
    * <p>So: the toolbar is refused in both directions, items shift-clicked out of the
    * player's inventory go into storage and nowhere else, and taking something out of
    * storage still hands it back to the player the way it always did.
    */
   @Override
   public ItemStack quickMoveStack(Player player, int index) {
      int storage = storageSlots(this.tier);
      int containerSize = this.container.getContainerSize();

      // A toolbar button is not a shelf, and a loaded jukebox must never hand its disc
      // over as a real item.
      if (index >= storage && index < containerSize) {
         return ItemStack.EMPTY;
      }

      if (index >= containerSize) {
         ItemStack moving = this.slots.get(index).getItem();
         if (moving.isEmpty()) {
            return ItemStack.EMPTY;
         }

         ItemStack before = moving.copy();
         if (!this.moveItemStackTo(moving, 0, storage, false)) {
            return ItemStack.EMPTY;
         }

         this.slots.get(index).setChanged();
         return before;
      }

      return super.quickMoveStack(player, index);
   }

   private void returnCarried(ServerPlayer player) {
      ItemStack carried = this.getCarried();
      if (!carried.isEmpty()) {
         this.setCarried(ItemStack.EMPTY);
         this.setRemoteCarried(HashedStack.EMPTY);
         InventoryHelper.giveOrDrop(player, carried);
      }
   }

   /** The toolbar row. Every feature is built in via the Item Forge - packs
    *  without a fusion show a locked placeholder in its slot instead. */
   private void buildToolbar() {
      int start = storageSlots(this.tier);
      if (ModItems.backpackHasWorkbench(this.backpackStack)) {
         this.container.setItem(start, toolbarButton(Items.CRAFTING_TABLE, "§6§lWorkbench", "§7Open the built-in 3x3 crafting table.", "§8Works anywhere - no table needed."));
      } else {
         this.container.setItem(start, this.lockedButton(Items.CRAFTING_TABLE, "Workbench", "a Crafting Table"));
      }
      if (ModItems.isEnderBackpack(this.backpackStack)) {
         this.container.setItem(start + 1, toolbarButton(Items.ENDER_CHEST, "§5§lEnder Chest", "§7Open your ender chest.", "§8One shared inventory, wherever you are."));
      } else {
         this.container.setItem(start + 1, this.lockedButton(Items.ENDER_CHEST, "Ender Chest", "an Ender Pouch"));
      }

      if (!ModItems.backpackHasJukebox(this.backpackStack)) {
         this.container.setItem(start + 2, this.lockedButton(Items.JUKEBOX, "Jukebox", "a Jukebox"));
      } else {
         ItemStack loadedDisc = BackpackJukebox.storedDisc(this.backpackStack, this.owner.registryAccess());
         if (loadedDisc.isEmpty()) {
            this.container.setItem(start + 2, toolbarButton(Items.JUKEBOX, "§b§lJukebox", "§7Click with a music disc on your cursor", "§7to load it - the music plays on the go,", "§8following you and looping forever."));
         } else {
            ItemStack playing = loadedDisc.copy();
            playing.set(DataComponents.CUSTOM_NAME, Component.literal("§b§lJukebox §7- " + loadedDisc.getHoverName().getString()));
            playing.set(
               DataComponents.LORE,
               new net.minecraft.world.item.component.ItemLore(
                  java.util.List.of(
                     Component.literal("§b♪ Playing on repeat - the music follows you."),
                     Component.literal("§7Click to eject the disc and stop the music.")
                  )
               )
            );
            this.container.setItem(start + 2, playing);
         }
      }

      // The kitchen: a Portable Furnace or Campfire fused into the pack cooks out of it, on its own
      // clock, wherever the player is. The button is the manual version and the status report.
      String kitchen = com.fortuneandfavors.economy.BackpackKitchen.kindOf(this.backpackStack);
      if (kitchen == null) {
         this.container.setItem(start + 3, this.lockedButton(
            Items.BLAST_FURNACE, "Kitchen", "a Portable Furnace or Campfire"
         ));
      } else {
         boolean campfire = com.fortuneandfavors.economy.BackpackKitchen.KIND_CAMPFIRE.equals(kitchen);
         java.util.List<Component> lore = new java.util.ArrayList<>(
            com.fortuneandfavors.economy.BackpackKitchen.status(this.owner, this.backpackStack)
         );
         lore.add(Component.literal("§eClick §7to cook what it can out of the pack now."));
         ItemStack button = new ItemStack(campfire ? Items.SMOKER : Items.BLAST_FURNACE);
         button.set(
            DataComponents.CUSTOM_NAME,
            Component.literal("§6§l" + com.fortuneandfavors.economy.BackpackKitchen.label(kitchen))
         );
         button.set(DataComponents.LORE, new net.minecraft.world.item.component.ItemLore(lore));
         this.container.setItem(start + 3, button);
      }

      for (int i = start + 4; i < this.container.getContainerSize(); i++) {
         ItemStack pane = new ItemStack(Items.STAINED_GLASS_PANE.gray());
         pane.set(DataComponents.CUSTOM_NAME, Component.literal(""));
         this.container.setItem(i, pane);
      }
   }

   /** Grayed "not installed" placeholder for a toolbar slot whose feature has
    *  not been fused in at the Item Forge yet. */
   private ItemStack lockedButton(net.minecraft.world.level.ItemLike item, String name, String fuseItem) {
      return toolbarButton(item, "§8§l" + name, "§7Not installed.", "§7Fuse this backpack with " + fuseItem, "§7in the §6Item Forge§7 to add it.");
   }

   /** A crafting table menu that works anywhere: the vanilla CraftingMenu
    *  closes itself unless it was opened next to a real crafting table, which
    *  is why the old workbench button never pulled up the crafting GUI. */
   public static class PortableCraftingMenu extends CraftingMenu {
      public PortableCraftingMenu(int syncId, Inventory playerInventory, net.minecraft.world.level.Level level, BlockPos pos) {
         super(syncId, playerInventory, ContainerLevelAccess.create(level, pos));
      }

      @Override
      public boolean stillValid(Player player) {
         return true;
      }
   }

   private ItemStack toolbarButton(net.minecraft.world.level.ItemLike item, String name, String... lines) {
      ItemStack stack = new ItemStack(item);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
      java.util.List<Component> lore = new java.util.ArrayList<>();
      for (String l : lines) {
         lore.add(Component.literal(l));
      }
      if (!lore.isEmpty()) {
         lore.add(Component.literal(""));
         lore.add(Component.literal("§8Click to use"));
      }
      stack.set(DataComponents.LORE, new net.minecraft.world.item.component.ItemLore(lore));
      return stack;
   }

   public static void rememberPlaced(ServerPlayer player, ItemStack backpack) {
      pendingPlaced.put(player.getUUID(), backpack.copy());
   }

   public static void confirmPlaced(ServerPlayer player, BlockPos pos) {
      ItemStack pending = pendingPlaced.remove(player.getUUID());
      if (pending != null && !pending.isEmpty()) {
         placedBackpacks.put(pos.immutable(), pending);
      }
   }

   public static ItemStack pickupPlaced(ServerLevel level, BlockPos pos) {
      ItemStack backpack = placedBackpacks.remove(pos);
      if (backpack != null) {
         level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
      }

      return backpack;
   }

   public static boolean hasPlacedBackpack(BlockPos pos) {
      return placedBackpacks.containsKey(pos);
   }

   public static ItemStack drainPending(ServerPlayer player) {
      return pendingPlaced.remove(player.getUUID());
   }
}
