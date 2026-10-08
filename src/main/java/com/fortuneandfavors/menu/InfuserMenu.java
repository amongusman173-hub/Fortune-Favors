package com.fortuneandfavors.menu;

import com.fortuneandfavors.ModItems;
import com.fortuneandfavors.ModSounds;
import com.fortuneandfavors.economy.SpawnerManager;
import com.fortuneandfavors.util.Chat;
import com.fortuneandfavors.util.InventoryHelper;
import com.fortuneandfavors.util.SoundUtil;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

public class InfuserMenu extends ChestMenu {
   private static final int[] GRID = new int[]{3, 4, 5, 12, 13, 14, 21, 22, 23};
   private static final int MIDDLE = 14;
   private static final int FUSE_BUTTON = 0;
   private static final int CONVERT_BUTTON = 8;
   private static final int INFO = 18;
   private static final int CLOSE = 26;
   private static final int MAX_LEVEL = 10;
   private static final Map<Item, String> CONVERSIONS = new HashMap<>();
   private final SimpleContainer container;
   private final ServerPlayer owner;

   public InfuserMenu(int syncId, Inventory playerInventory) {
      this(syncId, playerInventory, new SimpleContainer(27));
   }

   private InfuserMenu(int syncId, Inventory playerInventory, SimpleContainer container) {
      super(MenuType.GENERIC_9x3, syncId, playerInventory, container, 3);
      this.container = container;
      this.owner = (ServerPlayer)playerInventory.player;
      this.rebuild();
   }

   public static void open(ServerPlayer player) {
      player.openMenu(new SimpleMenuProvider((syncId, inv, p) -> new InfuserMenu(syncId, inv), Component.literal("§5§lSpawner Infuser")));
   }

   private void rebuild() {
      this.container.clearContent();
      this.container
         .setItem(
            0,
            this.button(
               Items.NETHERITE_INGOT,
               "§6§lFUSE",
               List.of(
                  "§7Put a spawner anywhere in the workbench.",
                  "§7Add other spawner items - each one is",
                  "§7consumed and adds +1 level.",
                  "§8Faster · more mobs · wider range",
                  "§8Max level 10"
               )
            )
         );
      this.container
         .setItem(
            8,
            this.button(
               Items.ENDER_EYE,
               "§d§lCONVERT",
               List.of(
                  "§7Put a spawner anywhere in the workbench.",
                  "§7Add the same item around it to change its",
                  "§7mob type (all added items must match):",
                  "§8Iron block = golem · blaze rod = blaze",
                  "§8Gunpowder = creeper · bone = skeleton",
                  "§8Rotten flesh = zombie · mutton = sheep",
                  "§8Beef = cow · porkchop = pig"
               )
            )
         );
      this.container
         .setItem(
            18,
            this.button(
               Items.SPAWNER,
               "§fHow to use",
               List.of(
                  "§7The 3x3 grid in the CENTER is the workbench.",
                  "§7Put your spawner in the middle (or anywhere -",
                  "§7it's detected automatically), then add items",
                  "§7around it. FUSE upgrades it with other spawners;",
                  "§7CONVERT changes its type with one matching item.",
                  "§8Shift-click moves items in and back out."
               )
            )
         );
      this.container.setItem(26, this.button(Items.BARRIER, "§cClose", List.of("")));
      this.broadcastChanges();
   }

   private ItemStack button(Item item, String name, List<String> lines) {
      ItemStack stack = new ItemStack(item);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
      stack.set(DataComponents.LORE, new ItemLore(lines.stream().<Component>map(s -> Component.literal(s)).toList()));
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

   private boolean isWorkbenchSlot(int slotId) {
      return slotId >= 0 && slotId < 27 && slotId != 0 && slotId != 8 && slotId != 18 && slotId != 26;
   }

   private int locateSpawner() {
      if (this.isSpawnerItem(this.container.getItem(14))) {
         return 14;
      }

      for (int g = 0; g < 27; g++) {
         if (this.isWorkbenchSlot(g) && this.isSpawnerItem(this.container.getItem(g))) {
            return g;
         }
      }

      return -1;
   }

   public void clicked(int slotId, int button, ContainerInput input, Player player) {
      if (player instanceof ServerPlayer sp) {
         if (slotId == 26) {
            this.returnCarried(sp);
            sp.closeContainer();
         } else if (slotId == 0) {
            if (input == ContainerInput.PICKUP || input == ContainerInput.QUICK_MOVE) {
               this.fuse(sp);
            }

            this.returnCarried(sp);
         } else if (slotId != 8) {
            if (this.isWorkbenchSlot(slotId)) {
               super.clicked(slotId, button, input, player);
            } else if (slotId >= 0 && slotId < 27) {
               this.returnCarried(sp);
            } else {
               super.clicked(slotId, button, input, player);
            }
         } else {
            if (input == ContainerInput.PICKUP || input == ContainerInput.QUICK_MOVE) {
               this.convert(sp);
            }

            this.returnCarried(sp);
         }
      } else {
         super.clicked(slotId, button, input, player);
      }
   }

   private boolean isSpawnerItem(ItemStack s) {
      return s != null && !s.isEmpty() && ModItems.isSpawnerItem(s);
   }

   private void fuse(ServerPlayer player) {
      int mid = this.locateSpawner();
      if (mid < 0) {
         Chat.msg(player, "&cPut a spawner anywhere in the workbench to fuse it.");
      } else {
         ItemStack middle = this.container.getItem(mid);
         if (middle.getCount() > 1) {
            Chat.msg(player, "&cSplit the stack - the spawner you're fusing must be a single item.");
         } else {
            int count = 0;

            for (int g = 0; g < 27; g++) {
               if (this.isWorkbenchSlot(g) && g != mid) {
                  ItemStack s = this.container.getItem(g);
                  if (!s.isEmpty()) {
                     if (!this.isSpawnerItem(s)) {
                        Chat.msg(player, "&cEverything else in the workbench must be spawner items to fuse.");
                        return;
                     }

                     count += s.getCount();
                  }
               }
            }

            if (count <= 0) {
               Chat.msg(player, "&cAdd at least one other spawner to the workbench to fuse.");
            } else {
               UUID boundOwner = ModItems.spawnerOwner(middle);
               if (boundOwner != null && !boundOwner.equals(player.getUUID())) {
                  Chat.msg(player, "&cThat spawner is bound to " + ModItems.spawnerOwnerName(middle) + ".");
               } else {
                  int level = Math.min(10, ModItems.spawnerLevel(middle) + count);
                  String type = ModItems.spawnerTypeId(middle);
                  ItemStack result = ModItems.spawnerItem(type.isEmpty() ? null : type, boundOwner == null ? player.getUUID() : boundOwner);
                  ModItems.setSpawnerLevel(result, level);
                  this.consumeAll();
                  InventoryHelper.giveOrDrop(player, result);
                  Chat.raw(player, "&aFused " + count + " spawner" + (count == 1 ? "" : "s") + " - your spawner is now &6level " + level + "&a!");
                  SoundUtil.play(player, ModSounds.TRANSFER);
               }
            }
         }
      }
   }

   private void convert(ServerPlayer player) {
      int mid = this.locateSpawner();
      if (mid < 0) {
         Chat.msg(player, "&cPut a spawner anywhere in the workbench to convert it.");
      } else {
         ItemStack middle = this.container.getItem(mid);
         if (middle.getCount() > 1) {
            Chat.msg(player, "&cSplit the stack - the spawner you're converting must be a single item.");
         } else {
            for (int g = 0; g < 27; g++) {
               if (this.isWorkbenchSlot(g) && g != mid && this.isSpawnerItem(this.container.getItem(g))) {
                  Chat.msg(player, "&cThat's two spawners - take the extra one out, then convert.");
                  return;
               }
            }

            Item ingredient = null;
            int filled = 0;

            for (int g = 0; g < 27; g++) {
               if (this.isWorkbenchSlot(g) && g != mid) {
                  ItemStack s = this.container.getItem(g);
                  if (!s.isEmpty()) {
                     filled++;
                     if (ingredient == null) {
                        ingredient = s.getItem();
                     } else if (ingredient != s.getItem()) {
                        Chat.msg(player, "&cAll items in the workbench must be the same to convert.");
                        return;
                     }
                  }
               }
            }

            if (filled <= 0) {
               Chat.msg(player, "&cAdd at least one ingredient around the spawner to convert it.");
            } else {
               String newType = CONVERSIONS.get(ingredient);
               if (newType == null) {
                  Chat.msg(player, "&cThat item can't convert a spawner. Try iron blocks, blaze rods, gunpowder,");
                  Chat.msg(player, "&cbones, rotten flesh, mutton, beef or porkchop.");
               } else {
                  UUID boundOwner = ModItems.spawnerOwner(middle);
                  if (boundOwner != null && !boundOwner.equals(player.getUUID())) {
                     Chat.msg(player, "&cThat spawner is bound to " + ModItems.spawnerOwnerName(middle) + ".");
                  } else {
                     ItemStack result = ModItems.spawnerItem(newType, boundOwner == null ? player.getUUID() : boundOwner);
                     this.consumeAll();
                     InventoryHelper.giveOrDrop(player, result);
                     Chat.raw(player, "&dConverted! &7Your spawner now spawns &f" + SpawnerManager.mobName(newType) + "&7.");
                     SoundUtil.play(player, ModSounds.TRANSFER);
                  }
               }
            }
         }
      }
   }

   private void consumeAll() {
      for (int g = 0; g < 27; g++) {
         if (this.isWorkbenchSlot(g)) {
            this.container.setItem(g, ItemStack.EMPTY);
         }
      }

      this.broadcastChanges();
   }

   public ItemStack quickMoveStack(Player player, int index) {
      if (player instanceof ServerPlayer sp) {
         if (index < 27) {
            if (this.isWorkbenchSlot(index)) {
               ItemStack stack = this.container.getItem(index);
               if (!stack.isEmpty()) {
                  this.container.setItem(index, ItemStack.EMPTY);
                  InventoryHelper.giveOrDrop(sp, stack);
                  this.broadcastChanges();
               }

               return ItemStack.EMPTY;
            } else {
               return ItemStack.EMPTY;
            }
         } else {
            Slot slot = this.getSlot(index);
            ItemStack stack = slot.getItem();
            if (!stack.isEmpty()) {
               int[] order = new int[27];
               int n = 0;
               order[n++] = 14;

               for (int g = 0; g < 27; g++) {
                  if (g != 14 && this.isWorkbenchSlot(g)) {
                     order[n++] = g;
                  }
               }

               for (int i = 0; i < n; i++) {
                  int g = order[i];
                  if (this.container.getItem(g).isEmpty()) {
                     int move = Math.min(stack.getCount(), stack.getMaxStackSize());
                     this.container.setItem(g, stack.copyWithCount(move));
                     stack.shrink(move);
                     slot.set(stack);
                     break;
                  }
               }

               this.broadcastChanges();
            }

            return ItemStack.EMPTY;
         }
      } else {
         return ItemStack.EMPTY;
      }
   }

   public void removed(Player player) {
      if (player instanceof ServerPlayer sp) {
         for (int g = 0; g < 27; g++) {
            if (this.isWorkbenchSlot(g)) {
               ItemStack s = this.container.getItem(g);
               if (!s.isEmpty()) {
                  this.container.setItem(g, ItemStack.EMPTY);
                  InventoryHelper.giveOrDrop(sp, s);
               }
            }
         }

         ItemStack carried = this.getCarried();
         if (!carried.isEmpty()) {
            this.setCarried(ItemStack.EMPTY);
            this.setRemoteCarried(HashedStack.EMPTY);
            InventoryHelper.giveOrDrop(sp, carried);
         }
      }

      super.removed(player);
   }

   static {
      CONVERSIONS.put(Items.IRON_BLOCK, "minecraft:iron_golem");
      CONVERSIONS.put(Items.BLAZE_ROD, "minecraft:blaze");
      CONVERSIONS.put(Items.GUNPOWDER, "minecraft:creeper");
      CONVERSIONS.put(Items.BONE, "minecraft:skeleton");
      CONVERSIONS.put(Items.ROTTEN_FLESH, "minecraft:zombie");
      CONVERSIONS.put(Items.MUTTON, "minecraft:sheep");
      CONVERSIONS.put(Items.BEEF, "minecraft:cow");
      CONVERSIONS.put(Items.PORKCHOP, "minecraft:pig");
   }
}
