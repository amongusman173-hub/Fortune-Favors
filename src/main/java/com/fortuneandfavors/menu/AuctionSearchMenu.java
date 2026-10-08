package com.fortuneandfavors.menu;

import com.fortuneandfavors.economy.AuctionManager;
import com.fortuneandfavors.util.Chat;
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
 * The auction house's search box.
 *
 * <p>There is no text input in a chest window, so the search is typed where every other typed thing
 * in this mod is typed: into an anvil's name field, which is the one place a player can already put
 * letters in front of them. The result slot shows what the search will do before it is taken, so the
 * query is read back before it is run - and an empty field clears the search instead of hiding the
 * whole market behind a filter nobody meant to set.
 */
public class AuctionSearchMenu extends AnvilPromptMenu {
   private final String previous;

   public AuctionSearchMenu(int syncId, Inventory playerInventory, String previous) {
      super(syncId, playerInventory, searchPaper(), helper());
      this.previous = previous == null ? "" : previous;
   }

   private static ItemStack searchPaper() {
      ItemStack paper = new ItemStack(Items.PAPER);
      paper.set(DataComponents.CUSTOM_NAME, Component.literal("§7Search"));
      return paper;
   }

   private static ItemStack helper() {
      ItemStack book = new ItemStack(Items.BOOK);
      book.set(DataComponents.CUSTOM_NAME, Component.literal("§d§lSearch the Auction House"));
      book.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Type part of an item's name - the"),
               Component.literal("§7search ignores capitals."),
               Component.literal(""),
               Component.literal("§7Also matches §fseller names§7 and"),
               Component.literal("§7listing ids: §f#42§7."),
               Component.literal(""),
               Component.literal("§7Leave the field empty to clear it."),
               Component.literal("§8or run §f/ah search <text>§8.")
            )
         )
      );
      return book;
   }

   public static void open(ServerPlayer player, String previous) {
      player.openMenu(
         new SimpleMenuProvider(
            (syncId, inv, p) -> new AuctionSearchMenu(syncId, inv, previous),
            Component.literal("§d§lSearch the Auction House")
         )
      );
   }

   /** Any text at all is an acceptable search - including none, which clears it. */
   @Override
   protected boolean canAccept(String text) {
      return true;
   }

   @Override
   protected void renderResult(ItemStack result, String text) {
      String query = text == null ? "" : text.trim();
      int hits = AuctionManager.search(query).size();
      result.set(DataComponents.CUSTOM_NAME, Component.literal(
         query.isEmpty() ? "§c§lClear the search" : "§d§lSearch: §f" + query
      ));
      result.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal(query.isEmpty()
                  ? "§7Show the whole market again."
                  : "§7" + hits + " listing(s) match."),
               Component.literal("§8Click to open the results.")
            )
         )
      );
   }

   @Override
   protected void accept(ServerPlayer player, String text) {
      // Nothing to do here: reopening is where the search actually happens, and accept() must not
      // have a side effect that runs twice if a client ever double-takes the result slot.
   }

   @Override
   protected void reopen(ServerPlayer player) {
      String query = this.typedText() == null ? "" : this.typedText().trim();
      if (query.isEmpty()) {
         Chat.msg(player, "&7Search cleared - showing the whole market.");
      } else {
         Chat.msg(player, "&7Searching for &f" + query + "&7 - " + AuctionManager.search(query).size() + " listing(s).");
      }
      AuctionMenu.open(player, query, AuctionMenu.View.BROWSE);
   }
}
