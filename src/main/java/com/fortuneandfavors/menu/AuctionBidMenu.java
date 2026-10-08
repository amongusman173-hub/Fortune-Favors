package com.fortuneandfavors.menu;

import com.fortuneandfavors.ModSounds;
import com.fortuneandfavors.economy.AuctionManager;
import com.fortuneandfavors.economy.AuctionManager.Auction;
import com.fortuneandfavors.util.Chat;
import com.fortuneandfavors.util.PriceUtil;
import com.fortuneandfavors.util.SoundUtil;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

/**
 * Bidding a number of your own.
 *
 * <p>The window's buttons cover the two bids almost everybody wants - the minimum, and ten percent
 * over it - and this is the third: whatever the player types. It is also the only place the rules of
 * a bid are said out loud before the money moves, because a rejected bid that only says "failed" is
 * a bid a player cannot learn from: this one names the minimum, the balance, and whether they are
 * already the one winning it.
 */
public class AuctionBidMenu extends AnvilPromptMenu {
   private final int auctionId;
   private long pending = -1L;

   public AuctionBidMenu(int syncId, Inventory playerInventory, int auctionId) {
      super(syncId, playerInventory, iconFor(auctionId), helperFor(auctionId));
      this.auctionId = auctionId;
   }

   private static ItemStack iconFor(int auctionId) {
      Auction a = AuctionManager.get(auctionId);
      ItemStack icon = a == null || a.item.isEmpty() ? new ItemStack(Items.PAPER) : a.item.copy();
      icon.setCount(1);
      return icon;
   }

   private static ItemStack helperFor(int auctionId) {
      Auction a = AuctionManager.get(auctionId);
      ItemStack book = new ItemStack(Items.BOOK);
      book.set(DataComponents.CUSTOM_NAME, Component.literal("§d§lPlace a bid"));
      List<Component> lore = new java.util.ArrayList<>();
      lore.add(Component.literal("§7Type the amount you want to bid."));
      lore.add(Component.literal("§a10k  §7=  10,000   §a1.5m  §7=  1,500,000"));
      if (a != null) {
         lore.add(Component.literal(""));
         lore.add(Component.literal("§7Minimum right now: §f" + Chat.moneyStr(AuctionManager.minimumBid(a))));
         lore.add(Component.literal("§7Winning bid: §f" + (a.topBidder == null ? "none yet" : Chat.moneyStr(a.currentBid))));
         lore.add(Component.literal("§7Ends in: §f" + AuctionManager.timeLeftString(a)));
      }
      lore.add(Component.literal(""));
      lore.add(Component.literal("§7A bid is held in escrow until somebody"));
      lore.add(Component.literal("§7outbids you - then it comes straight back."));
      book.set(DataComponents.LORE, new ItemLore(lore));
      return book;
   }

   public static void open(ServerPlayer player, int auctionId) {
      player.openMenu(
         new SimpleMenuProvider(
            (syncId, inv, p) -> new AuctionBidMenu(syncId, inv, auctionId),
            Component.literal("§d§lPlace a bid")
         )
      );
   }

   private Auction auction() {
      Auction a = AuctionManager.get(this.auctionId);
      return a == null || a.finished ? null : a;
   }

   @Override
   protected boolean canAccept(String text) {
      return PriceUtil.parse(text) > 0L;
   }

   @Override
   protected void renderResult(ItemStack result, String text) {
      Auction a = this.auction();
      long amount = PriceUtil.parse(text);
      if (a == null) {
         result.set(DataComponents.CUSTOM_NAME, Component.literal("§c§lThat auction has ended"));
         result.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("§7Nothing to bid on any more."))));
         return;
      }
      long minimum = AuctionManager.minimumBid(a);
      if (amount <= 0L) {
         result.set(DataComponents.CUSTOM_NAME, Component.literal("§c§lType an amount"));
         result.set(
            DataComponents.LORE,
            new ItemLore(List.of(
               Component.literal("§7Examples: §a10k§7, §a1.5m§7, §a2500"),
               Component.literal("§7Minimum for this one: §f" + Chat.moneyStr(minimum))
            ))
         );
      } else if (amount < minimum) {
         result.set(DataComponents.CUSTOM_NAME, Component.literal("§c§lToo low: §f" + Chat.moneyStr(amount)));
         result.set(
            DataComponents.LORE,
            new ItemLore(List.of(
               Component.literal("§7This auction wants at least §f" + Chat.moneyStr(minimum) + "§7."),
               Component.literal("§8Type a bigger number.")
            ))
         );
      } else {
         result.set(DataComponents.CUSTOM_NAME, Component.literal("§a§lBid §f" + Chat.moneyStr(amount)));
         result.set(
            DataComponents.LORE,
            new ItemLore(List.of(
               Component.literal("§7For §f" + a.item.getHoverName().getString() + "§7 by §f" + a.sellerName + "§7."),
               Component.literal("§7Ends in §f" + AuctionManager.timeLeftString(a) + "§7."),
               Component.literal("§8Click to place the bid.")
            ))
         );
      }
   }

   @Override
   protected void accept(ServerPlayer player, String text) {
      this.pending = PriceUtil.parse(text);
   }

   @Override
   protected void reopen(ServerPlayer player) {
      Auction a = this.auction();
      if (a != null && this.pending > 0L) {
         if (a.seller.equals(player.getUUID())) {
            Chat.msg(player, "&cYou can't bid on your own auction.");
         } else if (AuctionManager.isWinning(a, player.getUUID())) {
            Chat.msg(player, "&7You are already the top bidder on &f#" + a.id + "&7.");
         } else if (!AuctionManager.bid(player.level().getServer(), player, a, this.pending)) {
            Chat.msg(
               player,
               "&cThat bid did not go through. Minimum is &f" + Chat.moneyStr(AuctionManager.minimumBid(a))
                  + "&c and you have &f" + Chat.moneyStr(com.fortuneandfavors.economy.EconomyManager.balance(player.getUUID())) + "&c."
            );
         } else {
            SoundUtil.play(player, ModSounds.AUCTION_BID);
            Chat.raw(
               player,
               "§aBid §f" + Chat.moneyStr(this.pending) + "§a on §d#" + a.id + "§a. Balance: §f"
                  + Chat.moneyStr(com.fortuneandfavors.economy.EconomyManager.balance(player.getUUID())) + "§a."
            );
         }
      }
      AuctionMenu.open(player);
   }
}
