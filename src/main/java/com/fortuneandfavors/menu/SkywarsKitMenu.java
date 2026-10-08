package com.fortuneandfavors.menu;

import com.fortuneandfavors.duel.DuelManager;
import com.fortuneandfavors.util.Chat;
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
import net.minecraft.world.level.ItemLike;

public class SkywarsKitMenu extends ChestMenu {
   private static final int SLOTS = 18;
   private static final int CLOSE = 17;
   private static final String[] KITS = new String[]{"knight", "archer", "tank", "rush", "builder", "berserker", "mage"};
   private final SimpleContainer container;
   private final ServerPlayer owner;

   private SkywarsKitMenu(int syncId, Inventory playerInventory, SimpleContainer container) {
      super(MenuType.GENERIC_9x2, syncId, playerInventory, container, 2);
      this.container = container;
      this.owner = (ServerPlayer)playerInventory.player;
      this.rebuild();
   }

   public static void open(ServerPlayer player) {
      player.openMenu(
         new SimpleMenuProvider(
            (syncId, inv, p) -> new SkywarsKitMenu(syncId, inv, new SimpleContainer(18)), Component.literal("§b§lSky Wars §r§7- Choose Class")
         )
      );
   }

   private void rebuild() {
      this.kit(0, Items.IRON_SWORD, "§f§lKnight", "§7Iron sword, chainmail + leather armor");
      this.kit(1, Items.BOW, "§b§lArcher", "§7Stone sword, bow + 32 arrows");
      this.kit(2, Items.IRON_CHESTPLATE, "§e§lTank", "§7Stone sword, iron armor");
      this.kit(3, Items.ENDER_PEARL, "§a§lRush", "§7Stone sword, 96 wool, ender pearl");
      this.kit(4, Items.OAK_PLANKS, "§6§lBuilder", "§7Stone sword, 192 blocks, lava + water");
      this.kit(5, Items.IRON_AXE, "§c§lBerserker", "§7Iron axe + stone sword, glass cannon");
      this.kit(6, Items.FIRE_CHARGE, "§d§lMage", "§7Bow, fire charges, full leather armor");

      for (int i = 7; i < 17; i++) {
         this.container.setItem(i, this.filler());
      }

      this.container.setItem(17, this.closeStack());
      this.broadcastChanges();
   }

   private void kit(int slot, Item item, String name, String lore) {
      ItemStack stack = new ItemStack(item);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
      stack.set(DataComponents.LORE, new ItemLore(List.of(Component.literal(lore))));
      this.container.setItem(slot, stack);
   }

   private ItemStack closeStack() {
      ItemStack stack = new ItemStack(Items.BARRIER);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§c§lClose"));
      return stack;
   }

   private ItemStack filler() {
      ItemStack stack = new ItemStack(Items.STAINED_GLASS_PANE.gray());
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§8§l "));
      return stack;
   }

   public void clicked(int slotId, int button, ContainerInput input, Player player) {
      if (player instanceof ServerPlayer sp) {
         if (slotId >= 0 && slotId < 18) {
            if (slotId >= 0 && slotId < KITS.length) {
               String err = DuelManager.setKit(sp, KITS[slotId]);
               sp.closeContainer();
               if (err != null) {
                  Chat.msg(sp, err);
               }
            } else {
               if (slotId == 17) {
                  sp.closeContainer();
               }

               this.returnCarried(sp);
            }
         } else if (input != ContainerInput.QUICK_MOVE && input != ContainerInput.CLONE) {
            super.clicked(slotId, button, input, player);
         } else {
            this.returnCarried(sp);
         }
      } else {
         super.clicked(slotId, button, input, player);
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
      if (index >= 0 && index < 18) {
         this.clicked(index, 0, ContainerInput.QUICK_MOVE, player);
      }

      return ItemStack.EMPTY;
   }
}
