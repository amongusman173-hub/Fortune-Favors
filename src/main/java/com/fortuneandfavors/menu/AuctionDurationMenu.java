package com.fortuneandfavors.menu;

import com.fortuneandfavors.ModSounds;
import com.fortuneandfavors.economy.AuctionManager;
import com.fortuneandfavors.util.Chat;
import com.fortuneandfavors.util.InventoryHelper;
import com.fortuneandfavors.util.SoundUtil;
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

public class AuctionDurationMenu extends ChestMenu {
   private static final int HOUR = 9;
   private static final int MIN30 = 10;
   private static final int MIN15 = 11;
   private static final int MIN5 = 12;
   private static final int CLOSE = 17;
   private final SimpleContainer container;
   private final ServerPlayer owner;
   private final ItemStack auctionItem;
   private final long price;
   private final long minIncrement;
   private boolean created = false;

   public AuctionDurationMenu(int syncId, Inventory playerInventory, ItemStack auctionItem, long price, long minIncrement) {
      this(syncId, playerInventory, auctionItem, price, minIncrement, new SimpleContainer(18));
   }

   private AuctionDurationMenu(int syncId, Inventory playerInventory, ItemStack auctionItem, long price, long minIncrement, SimpleContainer container) {
      super(MenuType.GENERIC_9x2, syncId, playerInventory, container, 2);
      this.container = container;
      this.owner = (ServerPlayer)playerInventory.player;
      this.auctionItem = auctionItem.copy();
      this.price = price;
      this.minIncrement = minIncrement;
      this.rebuild();
   }

   public static void open(ServerPlayer player, ItemStack auctionItem, long price, long minIncrement) {
      player.openMenu(
         new SimpleMenuProvider(
            (syncId, inv, p) -> new AuctionDurationMenu(syncId, inv, auctionItem, price, minIncrement),
            Component.literal("§d§lHow long should the auction run?")
         )
      );
   }

   private void rebuild() {
      this.container.clearContent();
      ItemStack item = this.auctionItem.copy();
      item.setCount(1);
      item.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7" + this.auctionItem.getHoverName().getString() + " x" + this.auctionItem.getCount()),
               Component.literal("§7Starting bid " + Chat.moneyStr(this.price))
            )
         )
      );
      this.container.setItem(4, item);
      this.container.setItem(9, this.duration(Items.CLOCK, "§a§l1 hour", 60));
      this.container.setItem(10, this.duration(Items.CLOCK, "§a§l30 minutes", 30));
      this.container.setItem(11, this.duration(Items.CLOCK, "§a§l15 minutes", 15));
      this.container.setItem(12, this.duration(Items.CLOCK, "§a§l5 minutes", 5));
      this.container.setItem(17, this.named(Items.BARRIER, "§cCancel", "§7Keep the item"));
   }

   private ItemStack duration(Item item, String name, int minutes) {
      ItemStack stack = new ItemStack(item);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
      stack.set(
         DataComponents.LORE,
         new ItemLore(List.of(Component.literal("§7List the auction for §f" + minutes + "§7 minutes"), Component.literal("§8Click to list")))
      );
      return stack;
   }

   private ItemStack named(Item item, String name, String lore) {
      ItemStack stack = new ItemStack(item);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
      stack.set(DataComponents.LORE, new ItemLore(List.of(Component.literal(lore))));
      return stack;
   }

   private void list(ServerPlayer player, int minutes) {
      if (!this.created) {
         if (this.auctionItem.isEmpty()) {
            Chat.msg(player, "&cNothing to auction - the item is gone.");
            this.close();
         } else {
            int id = AuctionManager.createAuction(
               player.getUUID(), player.getName().getString(), this.auctionItem, "bid", this.price, this.minIncrement, minutes * 1200L, "cash"
            );
            AuctionManager.save(player.level().getServer());
            this.created = true;
            SoundUtil.play(player, ModSounds.AUCTION_CREATE);
            Chat.raw(
               player,
               "§aListed §f"
                  + this.auctionItem.getHoverName().getString()
                  + " x"
                  + this.auctionItem.getCount()
                  + "§a as auction §d#"
                  + id
                  + "§a (§f"
                  + minutes
                  + "§a min, starting bid "
                  + Chat.moneyStr(this.price)
                  + ")"
            );
            this.close();
         }
      }
   }

   private void close() {
      this.owner.closeContainer();
      AuctionMenu.open(this.owner);
   }

   public void clicked(int slotId, int button, ContainerInput input, Player player) {
      if (player instanceof ServerPlayer sp && slotId < 18) {
         this.returnCarried(sp);
         switch (slotId) {
            case 9:
               this.list(sp, 60);
               break;
            case 10:
               this.list(sp, 30);
               break;
            case 11:
               this.list(sp, 15);
               break;
            case 12:
               this.list(sp, 5);
            case 13:
            case 14:
            case 15:
            case 16:
            default:
               break;
            case 17:
               this.close();
         }
      } else {
         super.clicked(slotId, button, input, player);
      }
   }

   public void removed(Player player) {
      super.removed(player);
      if (!this.created && !this.auctionItem.isEmpty() && player instanceof ServerPlayer sp) {
         InventoryHelper.giveOrDrop(sp, this.auctionItem);
      }
   }

   public ItemStack quickMoveStack(Player player, int index) {
      return ItemStack.EMPTY;
   }

   private void returnCarried(ServerPlayer player) {
      ItemStack carried = this.getCarried();
      if (!carried.isEmpty()) {
         this.setCarried(ItemStack.EMPTY);
         this.setRemoteCarried(HashedStack.EMPTY);
         InventoryHelper.giveOrDrop(player, carried);
      }
   }
}
