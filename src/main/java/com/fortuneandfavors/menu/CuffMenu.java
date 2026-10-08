package com.fortuneandfavors.menu;

import com.fortuneandfavors.economy.PrisonCellblock;
import com.fortuneandfavors.util.GuiUtil;
import com.fortuneandfavors.util.InventoryHelper;
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
 * The struggle, while it lasts.
 *
 * <p>A guard's first landed hit puts this in front of the prisoner: a row of seven locks, one lit at
 * a time, five in a row to be opened before the clock runs out. It is deliberately a thing a person
 * is good at and a script is not - it is a reaction to something that changes, not a pattern to be
 * solved - and it is deliberately short, because what it is standing between the prisoner and is a
 * hole in the ground with their bag emptied beside it.
 *
 * <p>Everything about the state lives in {@link PrisonCellblock}: this is the hands, not the memory.
 * Closing the menu does not end the struggle and does not save anybody - the clock in the cell block
 * keeps running, and the next hit re-opens the locks exactly where they were.
 */
public class CuffMenu extends ChestMenu {
   private static final int INFO = 4;
   private static final int CLOSE = 22;

   private final SimpleContainer container;
   private final ServerPlayer owner;

   public CuffMenu(int syncId, Inventory playerInventory) {
      this(syncId, playerInventory, new SimpleContainer(27));
   }

   private CuffMenu(int syncId, Inventory playerInventory, SimpleContainer container) {
      super(MenuType.GENERIC_9x3, syncId, playerInventory, container, 3);
      this.container = container;
      this.owner = (ServerPlayer)playerInventory.player;
      this.rebuild();
   }

   public static void open(ServerPlayer player) {
      player.openMenu(new SimpleMenuProvider((syncId, inv, p) -> new CuffMenu(syncId, inv), Component.literal("§4§lCuffs")));
   }

   /** Where the locks are, for the self-test. */
   public static int[] layoutForTest() {
      return new int[]{PrisonCellblock.CUFF_FIRST_SLOT, PrisonCellblock.CUFF_LOCKS, INFO, CLOSE};
   }

   /**
    * The board, as one row with three readings.
    *
    * <p>"Readable at a glance" is a rule about the row rather than about a tooltip, so the three
    * states are three different things on the screen rather than three lines of text: the lock to
    * click is a §eglowing yellow§r pane, the locks already worked are green and ticked, and the rest
    * are dark and say so. A prisoner who has never seen the screen before should be able to answer
    * "which one do I hit" in a glance and "how far through am I" in a second one.
    */
   private void rebuild() {
      this.container.clearContent();
      GuiUtil.frames(this.container, 3, Items.STAINED_GLASS_PANE.red());

      List<Integer> locks = PrisonCellblock.cuffLocks(this.owner.getUUID());
      int step = PrisonCellblock.cuffStep(this.owner.getUUID());
      this.container.setItem(INFO, this.infoCard(locks, step));

      for (int i = 0; i < PrisonCellblock.CUFF_LOCKS; i++) {
         PrisonCellblock.Lock state = PrisonCellblock.cuffLockState(this.owner.getUUID(), i);
         ItemStack lock;
         if (state == PrisonCellblock.Lock.LIT) {
            lock = new ItemStack(Items.STAINED_GLASS_PANE.yellow());
            lock.set(DataComponents.CUSTOM_NAME, Component.literal("§e§l▶ CLICK THIS ONE"));
            lock.set(DataComponents.LORE, new ItemLore(List.of(
               Component.literal("§7Lock §f" + (locks == null ? i + 1 : Math.min(step + 1, locks.size()))
                  + "§7/§f" + (locks == null ? PrisonCellblock.CUFF_LOCKS : locks.size())),
               Component.literal("§6Click it before the clock runs out.")
            )));
            lock.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
         } else if (state == PrisonCellblock.Lock.OPENED) {
            lock = new ItemStack(Items.STAINED_GLASS_PANE.lime());
            lock.set(DataComponents.CUSTOM_NAME, Component.literal("§a✔ opened"));
            lock.set(DataComponents.LORE, new ItemLore(List.of(
               Component.literal("§7Worked. §8One wrong click resets the lot.")
            )));
         } else {
            lock = new ItemStack(Items.STAINED_GLASS_PANE.gray());
            Integer ahead = locks == null || step < 0 || step >= locks.size() ? null : locks.get(step);
            String hint = ahead != null && ahead < PrisonCellblock.CUFF_FIRST_SLOT + i ? "§8Wait your turn." : "§8Not this one. §7It is a slip.";
            lock.set(DataComponents.CUSTOM_NAME, Component.literal("§8· not this one"));
            lock.set(DataComponents.LORE, new ItemLore(List.of(Component.literal(hint))));
         }
         this.container.setItem(PrisonCellblock.CUFF_FIRST_SLOT + i, lock);
      }

      this.container.setItem(CLOSE, this.named(new ItemStack(Items.BARRIER), "§cGive up (the clock keeps running)"));
      this.broadcastChanges();
   }

   private ItemStack infoCard(List<Integer> locks, int step) {
      ItemStack info = new ItemStack(Items.IRON_BARS);
      List<Component> lore = new java.util.ArrayList<>();
      if (locks == null || step < 0) {
         info.set(DataComponents.CUSTOM_NAME, Component.literal("§a§lHANDS FREE"));
         lore.add(Component.literal("§7Your hands are free."));
      } else {
         int size = locks.size();
         boolean hard = PrisonCellblock.cuffIsHard(this.owner.getUUID());
         info.set(
            DataComponents.CUSTOM_NAME,
            Component.literal((hard ? "§4§lTHE NETHERITE SHACKLE" : "§4§lTHE CUFFS") + " §8| §f" + Math.min(step, size) + "§7/§f" + size)
         );
         lore.add(Component.literal("§e▶ Click the §e§lYELLOW§e lock. §7Then the next one."));
         lore.add(Component.literal("§a✔ Green §7locks are done. §f" + Math.min(step, size) + "§7 of §f" + size + "§7 worked."));
         lore.add(Component.literal("§8A wrong click puts you back to the first."));
         if (hard) {
            lore.add(Component.literal("§4The Warden's own shackle: one second a lock, and a miss costs you the hole."));
         }
         lore.add(Component.literal("§c§l" + PrisonCellblock.cuffSecondsLeft(this.owner.getUUID()) + "s left§r §8- a miss and you are put down and emptied."));
         lore.add(Component.literal("§8Closing this screen will not save you - it comes straight back."));
      }
      lore.add(Component.literal(""));
      lore.add(Component.literal("§8The guards are still holding you."));
      info.set(DataComponents.LORE, new ItemLore(lore));
      return info;
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
         if (slotId >= PrisonCellblock.CUFF_FIRST_SLOT
            && slotId < PrisonCellblock.CUFF_FIRST_SLOT + PrisonCellblock.CUFF_LOCKS) {
            PrisonCellblock.cuffStrike(sp, slotId);
            // The client's own prediction moved its cursor onto the lock it just clicked; the
            // carried slot is cleared and the whole board is re-sent so the next lit lock is what
            // both sides agree is on the screen.
            this.setCarried(ItemStack.EMPTY);
            this.setRemoteCarried(HashedStack.EMPTY);
            this.rebuild();
            return;
         }
         if (slotId == CLOSE) {
            this.returnCarried(sp);
            sp.closeContainer();
            return;
         }
         this.returnCarried(sp);
      } else {
         super.clicked(slotId, button, input, player);
      }
   }

   public ItemStack quickMoveStack(Player player, int index) {
      return ItemStack.EMPTY;
   }
}
