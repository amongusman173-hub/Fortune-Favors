package com.fortuneandfavors.menu;

import com.fortuneandfavors.economy.RepairStation;
import com.fortuneandfavors.util.Chat;
import com.fortuneandfavors.util.InventoryHelper;
import java.util.List;
import net.minecraft.core.BlockPos;
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
 * The Repair Station's window.
 *
 * <p>One slot for the tool, one button for the price, and a way out. The tool is a real slot - it
 * comes in from the player's own inventory and goes back to it - because the alternative was asking
 * a player to hold the thing they want mended *while* clicking at a block, which is the anvil's
 * whole usability problem in the first place.
 *
 * <p>The price on the button is the live one. It is recalculated off the tool actually in the slot
 * every time the window is drawn, so the number a player is agreeing to is the number they are
 * looking at - which matters, because this is the one machine in the mod whose price moves.
 */
public class RepairStationMenu extends ChestMenu {
   private static final int WORK = 13;
   private static final int BUTTON = 22;
   private static final int CLOSE = 26;
   private static final int INFO = 4;
   private final SimpleContainer container;
   private final ServerPlayer owner;
   private final BlockPos pos;

   public RepairStationMenu(int syncId, Inventory playerInventory, BlockPos pos) {
      this(syncId, playerInventory, pos, new SimpleContainer(27));
   }

   private RepairStationMenu(int syncId, Inventory playerInventory, BlockPos pos, SimpleContainer container) {
      super(MenuType.GENERIC_9x3, syncId, playerInventory, container, 3);
      this.container = container;
      this.owner = (ServerPlayer)playerInventory.player;
      this.pos = pos;
      this.rebuild();
   }

   public static void open(ServerPlayer player, BlockPos pos) {
      player.openMenu(new SimpleMenuProvider((syncId, inv, p) -> new RepairStationMenu(syncId, inv, pos), Component.literal("§a§lRepair Station")));
   }

   private ItemStack named(net.minecraft.world.item.Item item, String name, List<Component> lore) {
      ItemStack stack = new ItemStack(item);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
      if (!lore.isEmpty()) {
         stack.set(DataComponents.LORE, new ItemLore(lore));
      }
      return stack;
   }

   private void rebuild() {
      ItemStack tool = this.container.getItem(WORK);
      long price = RepairStation.priceFor(tool, this.owner.getUUID());
      this.container.setItem(
         INFO,
         this.named(
            Items.SMITHING_TABLE,
            "§a§lRepair Station",
            List.of(
               Component.literal("§7Put a damaged tool in the middle slot."),
               Component.literal("§7No levels, no XP and no materials -"),
               Component.literal("§7you pay in §fmoney§7."),
               Component.literal("§8The price follows the tool, and rises"),
               Component.literal("§8with every repair you buy.")
            )
         )
      );
      this.container.setItem(
         BUTTON,
         this.named(
            price > 0L ? Items.ANVIL : Items.DYE.gray(),
            price > 0L ? "§a§lMend it - §f" + Chat.moneyStr(price) : "§8Nothing to mend",
            price > 0L
               ? List.of(
                  Component.literal("§7Click to repair the tool above."),
                  Component.literal(RepairStation.whyLine(tool, this.owner.getUUID())),
                  Component.literal("§8Balance: §f" + Chat.moneyStr(com.fortuneandfavors.economy.EconomyManager.balance(this.owner.getUUID())))
               )
               : List.of(Component.literal("§7The slot above is empty or whole."))
         )
      );
      this.container.setItem(CLOSE, this.named(Items.BARRIER, "§cClose", List.of()));
   }

   private void returnWork(ServerPlayer player) {
      ItemStack tool = this.container.getItem(WORK);
      if (!tool.isEmpty()) {
         this.container.setItem(WORK, ItemStack.EMPTY);
         InventoryHelper.giveOrDrop(player, tool);
      }
      ItemStack carried = this.getCarried();
      if (!carried.isEmpty()) {
         this.setCarried(ItemStack.EMPTY);
         InventoryHelper.giveOrDrop(player, carried);
      }
   }

   public void clicked(int slotId, int button, ContainerInput input, Player player) {
      if (!(player instanceof ServerPlayer sp)) {
         super.clicked(slotId, button, input, player);
         return;
      }
      if (slotId >= 27) {
         super.clicked(slotId, button, input, player);
         return;
      }
      if (slotId == WORK) {
         // The working slot is a real slot: the tool goes in and comes out through ordinary clicks.
         super.clicked(slotId, button, input, player);
         this.rebuild();
         this.broadcastChanges();
         return;
      }
      if (slotId == CLOSE) {
         this.returnWork(sp);
         sp.closeContainer();
         return;
      }
      if (slotId == BUTTON) {
         ItemStack tool = this.container.getItem(WORK);
         if (tool.isEmpty()) {
            Chat.msg(sp, "&cPut a damaged tool in the middle slot first.");
         } else {
            long price = RepairStation.priceFor(tool, sp.getUUID());
            String error = RepairStation.repair(sp, tool);
            if (error != null) {
               Chat.msg(sp, "&c" + error);
            } else {
               Chat.raw(
                  sp,
                  "&aMended for &f" + Chat.moneyStr(price) + "&a. Balance: "
                     + Chat.moneyStr(com.fortuneandfavors.economy.EconomyManager.balance(sp.getUUID())) + "."
               );
               Chat.raw(sp, "&7Your next repair here will cost a little more.");
            }
         }
         this.rebuild();
         this.broadcastChanges();
         return;
      }
      this.returnWork(sp);
   }

   /** Nothing here is a real inventory: shift-clicking into or out of it is refused outright. */
   public ItemStack quickMoveStack(Player player, int index) {
      return ItemStack.EMPTY;
   }

   /** Anything left in the window goes back to the player - a closed menu must not eat a tool. */
   @Override
   public void removed(Player player) {
      if (player instanceof ServerPlayer sp) {
         this.returnWork(sp);
      }
      super.removed(player);
   }
}
