package com.fortuneandfavors.menu;

import com.fortuneandfavors.ModSounds;
import com.fortuneandfavors.economy.DynamicContractsManager;
import com.fortuneandfavors.economy.DynamicContractsManager.Contract;
import com.fortuneandfavors.util.Chat;
import com.fortuneandfavors.util.GuiUtil;
import com.fortuneandfavors.util.InventoryHelper;
import com.fortuneandfavors.util.SoundUtil;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;

public class DeliverMenu extends ChestMenu {
   private static final int HOPPER = 18;
   private static final int DELIVER = 26;
   private static final int CLOSE = 8;
   private static final int FILL = 17;
   /**
    * The key the intake's empty-state icon is marked with.
    *
    * <p>The hopper sitting in the intake slot is a picture of a hopper, not a hopper - and it was
    * a real, takeable item like any other. A player could click it into their pack, shift-click it
    * into their inventory, or walk out with it when the window closed and be handed it by the same
    * code that returns their delivered items, so the Deliveries window paid out free hoppers. The
    * mark is what lets every path that touches this slot tell the sign from the post: a marked
    * stack is the furniture, it is never delivered, never returned and never taken.
    */
   private static final String HINT_KEY = "ff_deliver_hint";
   private final SimpleContainer container;
   private final ServerPlayer owner;

   public DeliverMenu(int syncId, Inventory playerInventory) {
      this(syncId, playerInventory, new SimpleContainer(36));
   }

   private DeliverMenu(int syncId, Inventory playerInventory, SimpleContainer container) {
      super(MenuType.GENERIC_9x4, syncId, playerInventory, container, 4);
      this.container = container;
      this.owner = (ServerPlayer)playerInventory.player;
      this.rebuild();
   }

   public static void open(ServerPlayer player) {
      player.openMenu(new SimpleMenuProvider((syncId, inv, p) -> new DeliverMenu(syncId, inv), Component.literal("§e§lDeliveries")));
   }

   private void rebuild() {
      // Keep the hopper contents safe across rebuilds.
      ItemStack kept = this.container.getItem(HOPPER);
      this.container.clearContent();
      GuiUtil.frames(this.container, 4, Items.STAINED_GLASS_PANE.yellow());
      ItemStack title = new ItemStack(Items.HOPPER);
      title.set(DataComponents.CUSTOM_NAME, Component.literal("§e§lContract Delivery"));
      title.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Drop matching items into the hopper"),
               Component.literal("§7and hit §eDeliver!§7 to fulfil contracts."),
               Component.literal("§7Rewards are paid instantly to your balance."),
               Component.literal("§8Up to 8 contracts can be live at once.")
            )
         )
      );
      this.container.setItem(0, title);
      this.container.setItem(CLOSE, this.named(new ItemStack(Items.BARRIER), "§cClose"));

      List<Contract> active = DynamicContractsManager.activeContracts();
      for (int i = 0; i < Math.min(8, active.size()); i++) {
         Contract c = active.get(i);
         ItemStack card = DynamicContractsManager.contractIcon(c);
         this.container.setItem(9 + i, card);
      }
      if (active.isEmpty()) {
         this.container.setItem(13, this.named(new ItemStack(Items.PAPER), "§7No active contracts", "§7Shortages and urgent contracts", "§7appear here automatically."));
      }

      this.container.setItem(HOPPER, isHint(kept) || kept.isEmpty() ? intakeHint() : kept);
      this.container.setItem(
         FILL,
         this.named(
            new ItemStack(Items.HOPPER_MINECART),
            "§e§lSWEEP YOUR PACK",
            "§7Pulls anything in your inventory that an",
            "§7active contract is asking for into the intake.",
            "",
            "§8One click instead of a stack of clicking."
         )
      );
      ItemStack deliver = new ItemStack(Items.DYE.lime());
      deliver.set(DataComponents.CUSTOM_NAME, Component.literal("§a§lDELIVER!"));
      deliver.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("§7Deliver everything in the hopper"), Component.literal("§7to the matching contract."))));
      this.container.setItem(DELIVER, deliver);
      this.broadcastChanges();
   }

   /** The icon the empty intake wears - marked, so no path can mistake it for cargo. */
   private static ItemStack intakeHint() {
      ItemStack hint = new ItemStack(Items.HOPPER);
      CompoundTag tag = new CompoundTag();
      tag.putBoolean(HINT_KEY, true);
      hint.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
      return hint;
   }

   /** Test seam: the intake's own empty-state sign, built fresh. */
   public static ItemStack intakeSignForTest() {
      return intakeHint();
   }

   /** Test seam: is this stack the intake's sign rather than cargo? */
   public static boolean isIntakeSign(ItemStack stack) {
      return isHint(stack);
   }

   /** True when the stack in hand is the intake's own picture rather than something to deliver. */
   private static boolean isHint(ItemStack stack) {
      if (stack == null || stack.isEmpty()) {
         return false;
      }
      CustomData data = (CustomData)stack.get(DataComponents.CUSTOM_DATA);
      return data != null && data.copyTag().getBooleanOr(HINT_KEY, false);
   }

   /** What is actually waiting to be delivered: the intake, with its empty-state sign read as empty. */
   private ItemStack cargo() {
      ItemStack stack = this.container.getItem(HOPPER);
      return isHint(stack) ? ItemStack.EMPTY : stack;
   }

   /**
    * Sweeps the player's pack for anything an active contract is asking for and moves it into the
    * intake. The window already told them what to do and then made them hunt for it; this is the
    * half of a contract that should never have been work.
    *
    * @return how many items were moved
    */
   private int sweep(ServerPlayer sp) {
      ItemStack into = cargo();
      int moved = 0;
      for (int i = 0; i < sp.getInventory().getContainerSize(); i++) {
         ItemStack slot = sp.getInventory().getItem(i);
         if (slot.isEmpty()) {
            continue;
         }
         if (!DynamicContractsManager.wantedByActiveContract(slot)) {
            continue;
         }
         if (into.isEmpty()) {
            into = slot.copy();
            moved += into.getCount();
            sp.getInventory().setItem(i, ItemStack.EMPTY);
            continue;
         }
         if (ItemStack.isSameItemSameComponents(into, slot)) {
            int room = into.getMaxStackSize() - into.getCount();
            if (room <= 0) {
               break;
            }
            int take = Math.min(room, slot.getCount());
            into.grow(take);
            slot.shrink(take);
            sp.getInventory().setItem(i, slot.isEmpty() ? ItemStack.EMPTY : slot);
            moved += take;
         }
      }
      this.container.setItem(HOPPER, into.isEmpty() ? intakeHint() : into);
      sp.getInventory().setChanged();
      return moved;
   }

   private ItemStack named(ItemStack stack, String name, String... loreLines) {
      stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
      if (loreLines.length > 0) {
         List<Component> lore = new java.util.ArrayList<>();
         for (String l : loreLines) {
            lore.add(Component.literal(l));
         }
         stack.set(DataComponents.LORE, new ItemLore(lore));
      }
      return stack;
   }

   private void returnHopper(ServerPlayer player) {
      ItemStack stack = cargo();
      if (!stack.isEmpty()) {
         this.container.setItem(HOPPER, ItemStack.EMPTY);
         InventoryHelper.giveOrDrop(player, stack);
      }
      this.returnCarried(player);
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
         if (slotId >= 0 && slotId < 36) {
            if (slotId == CLOSE) {
               // Return hopper items to the inventory; never drop them on the floor. The intake's
               // own sign is not cargo and is simply discarded - see HINT_KEY.
               ItemStack stack = cargo();
               this.container.setItem(HOPPER, ItemStack.EMPTY);
               if (!stack.isEmpty()) {
                  int before = stack.getCount();
                  boolean added = sp.getInventory().add(stack);
                  if (!added && !stack.isEmpty()) {
                     // Inventory is full - keep the window open instead of dropping.
                     this.container.setItem(HOPPER, stack);
                     Chat.msg(sp, "&cYour inventory is full - free up space, then close again. Nothing was dropped.");
                     SoundUtil.play(sp, ModSounds.DENY);
                     this.returnCarried(sp);
                     return;
                  }
               }
               this.returnCarried(sp);
               sp.closeContainer();
               return;
            }
            if (slotId == FILL) {
               int moved = this.sweep(sp);
               if (moved > 0) {
                  SoundUtil.play(sp, ModSounds.TRANSFER);
                  Chat.msg(sp, "&aSwept &f" + moved + "&a item(s) into the intake.");
               } else {
                  Chat.msg(sp, "&7Nothing in your pack is on an active contract right now.");
               }
               this.returnCarried(sp);
               this.rebuild();
               return;
            }
            if (slotId == DELIVER) {
               ItemStack hopperStack = cargo();
               if (!hopperStack.isEmpty()) {
                  int n = DynamicContractsManager.deliver(sp, hopperStack);
                  if (n > 0) {
                     SoundUtil.play(sp, ModSounds.TRANSFER);
                     Chat.msg(sp, "&aDelivered " + n + " item(s) to active contracts.");
                     this.container.setItem(HOPPER, hopperStack.isEmpty() ? intakeHint() : hopperStack);
                  } else {
                     Chat.msg(sp, "&cNothing in the hopper matches an active contract.");
                  }
               } else {
                  Chat.msg(sp, "&cPut the items you want to deliver into the hopper slot first.");
               }
               this.returnCarried(sp);
               this.rebuild();
               return;
            }
            if (slotId >= 9 && slotId < FILL) {
               this.returnCarried(sp);
               this.rebuild();
               return;
            }
            if (slotId == HOPPER) {
               // The empty-state sign is not an item to take: a click that would pick it up is
               // dropped, and a click that would put something down clears the sign first so the
               // cargo lands in a genuinely free slot.
               if (isHint(this.container.getItem(HOPPER))) {
                  if (this.getCarried().isEmpty()) {
                     this.returnCarried(sp);
                     return;
                  }
                  this.container.setItem(HOPPER, ItemStack.EMPTY);
               }
               super.clicked(slotId, button, input, player);
               if (this.container.getItem(HOPPER).isEmpty()) {
                  this.container.setItem(HOPPER, intakeHint());
               }
               this.broadcastChanges();
               return;
            }
            this.returnCarried(sp);
            return;
         }
         super.clicked(slotId, button, input, player);
      } else {
         super.clicked(slotId, button, input, player);
      }
   }

   public ItemStack quickMoveStack(Player player, int index) {
      if (index >= 36) {
         Slot slot = this.getSlot(index);
         ItemStack stack = slot.getItem();
         if (!stack.isEmpty() && player instanceof ServerPlayer sp) {
            ItemStack target = this.container.getItem(HOPPER);
            if (isHint(target)) {
               target = ItemStack.EMPTY;
            }
            if (target.isEmpty()) {
               this.container.setItem(HOPPER, stack.copy());
               slot.set(ItemStack.EMPTY);
            } else if (ItemStack.isSameItemSameComponents(target, stack) && target.getCount() < target.getMaxStackSize()) {
               int move = Math.min(target.getMaxStackSize() - target.getCount(), stack.getCount());
               target.grow(move);
               stack.shrink(move);
               slot.set(stack);
            }
            this.broadcastChanges();
         }
      }
      return ItemStack.EMPTY;
   }

   public void removed(Player player) {
      if (player instanceof ServerPlayer sp) {
         ItemStack stack = cargo();
         this.container.setItem(HOPPER, ItemStack.EMPTY);
         if (!stack.isEmpty()) {
            // Try to add to inventory first, only drop if full
            if (!sp.getInventory().add(stack)) {
               // Inventory full - keep the menu open so they can make room
               this.container.setItem(HOPPER, stack);
               sp.sendSystemMessage(Component.literal("§cYour inventory is full! Make room and close again to collect your items."), true);
               return;
            }
            sp.getInventory().setChanged();
            sp.containerMenu = this; // prevent double-removal race
         }
      }
      super.removed(player);
   }
}
