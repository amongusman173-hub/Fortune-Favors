package com.fortuneandfavors.menu;

import com.fortuneandfavors.duel.DuelManager;
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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

/**
 * The training dummy's control screen, opened by the Nether Star in the ninth hotbar slot.
 *
 * <p>A plain vanilla chest grid on purpose - Geyser translates the vanilla screen types and
 * nothing else, so a Bedrock client can be served this screen like any other in the mod. Every
 * button is a dummy control rather than a fight action: heal it, dress it, strip it, walk it back
 * to the middle. The dummy itself is a live body in the arena; this is only the panel that runs it.
 */
public class TrainingDummyMenu extends ChestMenu {
   private static final int ROWS = 3;
   private static final int SLOTS = 27;
   private static final int CLOSE = 22;
   private final SimpleContainer container;
   private final ServerPlayer owner;

   private TrainingDummyMenu(int syncId, Inventory playerInventory, SimpleContainer container) {
      super(MenuType.GENERIC_9x3, syncId, playerInventory, container, 3);
      this.container = container;
      this.owner = (ServerPlayer)playerInventory.player;
      this.rebuild();
   }

   public static void open(ServerPlayer player) {
      player.openMenu(
         new SimpleMenuProvider((syncId, inv, p) -> new TrainingDummyMenu(syncId, inv, new SimpleContainer(27)), Component.literal("§6§lTraining Dummy"))
      );
   }

   private void rebuild() {
      this.button(10, Items.GOLDEN_APPLE, "§a§lHeal Dummy", "§7Restore the dummy to full health");
      this.button(12, Items.DIAMOND_CHESTPLATE, "§b§lDress Dummy", "§7Give it diamond armour to test against");
      this.button(14, Items.SHEARS, "§e§lStrip Dummy", "§7Take its armour back off");
      this.button(16, Items.ENDER_PEARL, "§d§lRecentre", "§7Bring the dummy back to the arena middle");

      for (int i = 0; i < SLOTS; i++) {
         if (this.container.getItem(i).isEmpty() && i != CLOSE) {
            this.container.setItem(i, this.filler());
         }
      }

      ItemStack close = new ItemStack(Items.BARRIER);
      close.set(DataComponents.CUSTOM_NAME, Component.literal("§c§lClose"));
      this.container.setItem(CLOSE, close);
      this.broadcastChanges();
   }

   private void button(int slot, Item item, String name, String lore) {
      ItemStack stack = new ItemStack(item);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
      stack.set(DataComponents.LORE, new ItemLore(List.of(Component.literal(lore))));
      this.container.setItem(slot, stack);
   }

   private ItemStack filler() {
      ItemStack stack = new ItemStack(Items.STAINED_GLASS_PANE.gray());
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§8§l "));
      return stack;
   }

   public void clicked(int slotId, int button, ContainerInput input, Player player) {
      if (!(player instanceof ServerPlayer sp)) {
         super.clicked(slotId, button, input, player);
         return;
      }

      if (slotId >= 0 && slotId < SLOTS) {
         if (slotId == 10) {
            DuelManager.trainingControl(sp, "heal");
         } else if (slotId == 12) {
            DuelManager.trainingControl(sp, "armour");
         } else if (slotId == 14) {
            DuelManager.trainingControl(sp, "strip");
         } else if (slotId == 16) {
            DuelManager.trainingControl(sp, "recentre");
         } else if (slotId == CLOSE) {
            sp.closeContainer();
         }

         if (input != ContainerInput.QUICK_MOVE && input != ContainerInput.CLONE) {
            this.returnCarried(sp);
         } else {
            this.returnCarried(sp);
         }
      } else if (input != ContainerInput.QUICK_MOVE && input != ContainerInput.CLONE) {
         super.clicked(slotId, button, input, player);
      } else {
         this.returnCarried(sp);
      }
   }

   private void returnCarried(ServerPlayer player) {
      ItemStack carried = this.getCarried();
      if (!carried.isEmpty()) {
         this.setCarried(ItemStack.EMPTY);
         this.setRemoteCarried(HashedStack.EMPTY);
         InventoryHelper.giveOrDrop(player, carried);
      }
   }

   public ItemStack quickMoveStack(Player player, int index) {
      if (index >= 0 && index < SLOTS) {
         this.clicked(index, 0, ContainerInput.QUICK_MOVE, player);
      }

      return ItemStack.EMPTY;
   }
}
