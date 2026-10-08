package com.fortuneandfavors.menu;

import com.fortuneandfavors.economy.MachineManager;
import com.fortuneandfavors.economy.MachineManager.Machine;
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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.ItemLike;

public class AutoSellMenu extends ChestMenu {
   private static final int TITLE = 0;
   private static final int TOGGLE = 4;
   private static final int INFO = 13;
   private static final int CLOSE = 26;
   private final SimpleContainer container;
   private final ServerPlayer owner;
   private final BlockPos pos;

   public AutoSellMenu(int syncId, Inventory playerInventory, BlockPos pos) {
      this(syncId, playerInventory, pos, new SimpleContainer(27));
   }

   private AutoSellMenu(int syncId, Inventory playerInventory, BlockPos pos, SimpleContainer container) {
      super(MenuType.GENERIC_9x3, syncId, playerInventory, container, 3);
      this.container = container;
      this.owner = (ServerPlayer)playerInventory.player;
      this.pos = pos;
      this.rebuild();
   }

   public static void open(ServerPlayer player, BlockPos pos) {
      player.openMenu(new SimpleMenuProvider((syncId, inv, p) -> new AutoSellMenu(syncId, inv, pos), Component.literal("§6§lAuto-Sell Hopper")));
   }

   private Machine machine() {
      Machine m = MachineManager.get(this.owner.level(), this.pos);
      return m != null && "auto_sell_hopper".equals(m.type()) ? m : null;
   }

   private void rebuild() {
      this.container.clearContent();
      Machine m = this.machine();
      if (m == null) {
         this.container.setItem(0, this.named(Items.BARRIER, "§cHopper missing"));
      } else {
         boolean isOwner = m.owner() != null && m.owner().equals(this.owner.getUUID());
         ItemStack title = new ItemStack(Items.HOPPER);
         title.set(DataComponents.CUSTOM_NAME, Component.literal("§6§lAuto-Sell Hopper"));
         title.set(
            DataComponents.LORE,
            new ItemLore(
               List.of(
                  Component.literal("§7Owner: §f" + (m.ownerName().isEmpty() ? "unknown" : m.ownerName())),
                  Component.literal("§7Sells everything it picks up automatically")
               )
            )
         );
         this.container.setItem(0, title);
         ItemStack toggle = new ItemStack(m.effects() ? Items.DYE.green() : Items.DYE.gray());
         toggle.set(DataComponents.CUSTOM_NAME, Component.literal(m.effects() ? "§a§lSale effects: ON" : "§8Sale effects: OFF"));
         toggle.set(
            DataComponents.LORE,
            new ItemLore(
               List.of(
                  Component.literal(
                     "§7" + (m.effects() ? "Each sale fires a fountain of gold, a coin sound and a" : "Sales are silent - no particles, no sound, no readout.")
                  ),
                  Component.literal("§7" + (m.effects() ? "money readout on your action bar." : "Money still lands in your balance.")),
                  Component.literal(isOwner ? "§eClick to toggle" : "§8Only the owner can change this")
               )
            )
         );
         this.container.setItem(4, toggle);
         ItemStack info = new ItemStack(Items.BOOK);
         info.set(DataComponents.CUSTOM_NAME, Component.literal("§e§lHow it works"));
         info.set(
            DataComponents.LORE,
            new ItemLore(
               List.of(
                  Component.literal("§7Sells items fed into its own inventory,"),
                  Component.literal("§7the container directly above it, and"),
                  Component.literal("§7sellable drops within reach. The money"),
                  Component.literal("§7goes straight to the owner's balance."),
                  Component.literal("§8Sneak-right-click to pick it up.")
               )
            )
         );
         this.container.setItem(13, info);
         this.container.setItem(26, this.named(Items.BARRIER, "§cClose"));
      }
   }

   private ItemStack named(Item item, String name) {
      ItemStack stack = new ItemStack(item);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
      return stack;
   }

   private void returnCarried(ServerPlayer player) {
      ItemStack carried = this.getCarried();
      if (!carried.isEmpty()) {
         this.setCarried(ItemStack.EMPTY);
         InventoryHelper.giveOrDrop(player, carried);
      }
   }

   public void clicked(int slotId, int button, ContainerInput input, Player player) {
      if (!(player instanceof ServerPlayer sp)) {
         super.clicked(slotId, button, input, player);
      } else if (slotId >= 27) {
         super.clicked(slotId, button, input, player);
      } else if (slotId == 26) {
         this.returnCarried(sp);
         sp.closeContainer();
      } else {
         Machine m = this.machine();
         if (m == null) {
            this.returnCarried(sp);
            Chat.msg(sp, "&cThe hopper is gone.");
            sp.closeContainer();
         } else if (slotId != 4) {
            this.returnCarried(sp);
         } else {
            if (m.owner() != null && m.owner().equals(sp.getUUID())) {
               Machine updated = MachineManager.toggleEffects(sp.level(), this.pos);
               Chat.msg(
                  sp,
                  updated.effects() ? "&aSale effects turned on - every sale shows its gold fountain." : "&7Sale effects turned off - sales are now silent."
               );
            } else {
               Chat.msg(sp, "&cOnly the owner can change the hopper's settings.");
            }

            this.rebuild();
            this.broadcastChanges();
            this.returnCarried(sp);
         }
      }
   }

   public ItemStack quickMoveStack(Player player, int index) {
      return ItemStack.EMPTY;
   }
}
