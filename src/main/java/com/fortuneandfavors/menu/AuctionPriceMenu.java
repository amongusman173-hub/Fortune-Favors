package com.fortuneandfavors.menu;

import com.fortuneandfavors.ModItems;
import com.fortuneandfavors.ModSounds;
import com.fortuneandfavors.economy.AuctionManager;
import com.fortuneandfavors.util.Chat;
import com.fortuneandfavors.util.InventoryHelper;
import com.fortuneandfavors.util.PriceUtil;
import com.fortuneandfavors.util.SoundUtil;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

public class AuctionPriceMenu extends AnvilPromptMenu {
   private final ItemStack auctionItem;
   private final int relistId;
   private long pendingPrice = -1L;
   private long pendingMinIncrement = 1L;

   public AuctionPriceMenu(int syncId, Inventory playerInventory, ItemStack auctionItem) {
      this(syncId, playerInventory, auctionItem, -1);
   }

   public static void openForRelist(ServerPlayer player, ItemStack auctionItem, int auctionId) {
      player.openMenu(
         new SimpleMenuProvider((syncId, inv, p) -> new AuctionPriceMenu(syncId, inv, auctionItem, auctionId), Component.literal("§e§lSet a new price"))
      );
   }

   private AuctionPriceMenu(int syncId, Inventory playerInventory, ItemStack auctionItem, int relistId) {
      super(
         syncId,
         playerInventory,
         auctionItem,
         relistId >= 0
            ? helper(
               "§e§lSet a new price",
               "§7Relist your auction at a new price.",
               "§7Only works while nobody has bid yet.",
               "§a10k  §7=  10,000",
               "§a1.5m  §7=  1,500,000"
            )
            : helper(
               "§e§lSet your price",
               "§7Type a starting price, e.g.",
               "§a10k  §7=  10,000",
               "§a1.5m  §7=  1,500,000",
               "§7or just a number: §a2500",
               "",
               "§7After confirming you'll pick",
               "§7how long the auction runs."
            )
      );
      this.auctionItem = auctionItem.copy();
      this.relistId = relistId;
   }

   @Override
   protected boolean canAccept(String text) {
      return PriceUtil.parse(text) > 0L;
   }

   @Override
   protected void renderResult(ItemStack result, String text) {
      long price = PriceUtil.parse(text);
      if (price > 0L) {
         result.set(DataComponents.CUSTOM_NAME, Component.literal("§a§lList auction §f" + Chat.moneyStr(price) + "§r"));
         result.set(
            DataComponents.LORE, new ItemLore(List.of(Component.literal("§7Starting bid " + Chat.moneyStr(price)), Component.literal("§8Click to confirm")))
         );
      } else {
         result.set(DataComponents.CUSTOM_NAME, Component.literal("§c§lType a price"));
         result.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("§7Examples: §a10k§7, §a1.5m§7, §a2500"))));
      }
   }

   @Override
   protected void accept(ServerPlayer player, String text) {
      long price = PriceUtil.parse(text);
      if (price > 0L) {
         if (this.auctionItem.isEmpty()) {
            Chat.msg(player, "&cNothing to auction - the item is gone.");
         } else {
            if (ModItems.isSpawnerItem(this.auctionItem)) {
               ModItems.unbindSpawner(this.auctionItem);
            }

            this.pendingPrice = price;
            this.pendingMinIncrement = Math.max(1L, price / 20L);
         }
      }
   }

   @Override
   protected void reopen(ServerPlayer player) {
      if (this.relistId >= 0) {
         if (this.pendingPrice <= 0L) {
            AuctionMenu.open(player);
         } else {
            UUID uuid = player.getUUID();
            long name = this.pendingPrice;
            long inc = this.pendingMinIncrement;
            int newId = AuctionManager.relistAuction(player.level().getServer(), player, this.relistId, name, inc, 0L);
            if (newId < 0) {
               Chat.msg(player, "&cCouldn't reprice that auction - it may already have a bid.");
            } else {
               SoundUtil.play(player, ModSounds.AUCTION_BID);
               Chat.raw(player, "§aRelisted auction as §d#" + newId + "§a at " + Chat.moneyStr(name) + "§a.");
            }

            AuctionMenu.open(player);
         }
      } else if (this.pendingPrice > 0L && !this.auctionItem.isEmpty()) {
         AuctionDurationMenu.open(player, this.auctionItem, this.pendingPrice, this.pendingMinIncrement);
      } else {
         if (!this.auctionItem.isEmpty()) {
            InventoryHelper.giveOrDrop(player, this.auctionItem);
         }

         AuctionMenu.open(player);
      }
   }
}
