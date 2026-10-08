package com.fortuneandfavors.menu;

import com.fortuneandfavors.ModItems;
import com.fortuneandfavors.ModSounds;
import com.fortuneandfavors.util.SoundUtil;
import com.mojang.serialization.DataResult;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/** Tiered bundle storage - right-click a Bundle to open it. Contents live in the
 *  bundle's CustomData (key {@value #CONTENTS_KEY}) so the item carries its own
 *  slots around like the backpack does. Tiers: Leather 9, Iron 27, Netherite 54. */
public class BundleMenu extends ChestMenu {
   public static final String CONTENTS_KEY = "ff_bdl";
   private final SimpleContainer container = (SimpleContainer)this.getContainer();
   private final ServerPlayer owner;
   private final int tier;
   private final ItemStack bundleStack;

   public BundleMenu(int syncId, Inventory playerInventory, ItemStack bundle, int tier) {
      super(tierMenuType(tier), syncId, playerInventory, new SimpleContainer(tierSlots(tier)), tierRows(tier));
      this.owner = (ServerPlayer)playerInventory.player;
      this.tier = tier;
      this.bundleStack = bundle;
      this.loadContents();
      this.ready = true;
   }

   /** Guard for the change listener while the menu is still being constructed. */
   private boolean ready;

   /**
    * Persist on every content change rather than only on close, for the same
    * reason as {@code BackpackMenu}: the item in the inventory must always be the
    * truth, so a death, crash or disconnect cannot save a stale (or empty) bundle.
    */
   @Override
   public void slotsChanged(net.minecraft.world.Container container) {
      super.slotsChanged(container);
      if (this.ready && this.owner != null && this.bundleStack != null && !this.bundleStack.isEmpty()) {
         this.saveContents();
      }
   }

   public static void open(ServerPlayer player, ItemStack bundle) {
      int tier = ModItems.bundleTier(bundle);
      player.openMenu(
         new SimpleMenuProvider(
            (syncId, inv, p) -> new BundleMenu(syncId, inv, bundle, tier),
            Component.literal("§b§lBundle §7[" + ModItems.bundleTierName(tier) + "]")
         )
      );
   }

   private static MenuType<?> tierMenuType(int tier) {
      return switch (tier) {
         case 1 -> MenuType.GENERIC_9x1;
         case 2 -> MenuType.GENERIC_9x3;
         default -> MenuType.GENERIC_9x6;
      };
   }

   private static int tierSlots(int tier) {
      return switch (tier) {
         case 1 -> 9;
         case 2 -> 27;
         default -> 54;
      };
   }

   private static int tierRows(int tier) {
      return switch (tier) {
         case 1 -> 1;
         case 2 -> 3;
         default -> 6;
      };
   }

   private void loadContents() {
      CustomData custom = (CustomData)this.bundleStack.get(DataComponents.CUSTOM_DATA);
      if (custom != null) {
         CompoundTag tag = custom.copyTag();
         ListTag items = tag.getList(CONTENTS_KEY).orElse(new ListTag());

         for (int i = 0; i < Math.min(items.size(), this.container.getContainerSize()); i++) {
            CompoundTag itemTag = items.getCompound(i).orElse(new CompoundTag());
            ItemStack loaded = ItemStack.OPTIONAL_CODEC
               .parse(RegistryOps.create(NbtOps.INSTANCE, this.owner.registryAccess()), itemTag)
               .result()
               .orElse(ItemStack.EMPTY);
            this.container.setItem(i, loaded);
         }
      }
   }

   private void saveContents() {
      CompoundTag tag = new CompoundTag();
      CustomData existing = (CustomData)this.bundleStack.get(DataComponents.CUSTOM_DATA);
      if (existing != null) {
         tag = existing.copyTag();
      }

      ListTag items = new ListTag();

      for (int i = 0; i < this.container.getContainerSize(); i++) {
         ItemStack stack = this.container.getItem(i);
         if (!stack.isEmpty()) {
            DataResult<Tag> res = ItemStack.OPTIONAL_CODEC.encodeStart(RegistryOps.create(NbtOps.INSTANCE, this.owner.registryAccess()), stack);
            res.result().ifPresent(items::add);
         }
      }

      tag.put(CONTENTS_KEY, items);
      this.bundleStack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
   }

   public void removed(Player player) {
      super.removed(player);
      if (player instanceof ServerPlayer sp) {
         this.saveContents();
         SoundUtil.play(sp, ModSounds.BACKPACK_CLOSE);
      }
   }

   public void clicked(int slotId, int button, ContainerInput input, Player player) {
      super.clicked(slotId, button, input, player);
   }

   public ItemStack quickMoveStack(Player player, int index) {
      return super.quickMoveStack(player, index);
   }
}