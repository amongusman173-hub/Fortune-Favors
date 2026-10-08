package com.fortuneandfavors.menu;

import com.fortuneandfavors.economy.TokenManager;
import com.fortuneandfavors.util.Chat;
import com.fortuneandfavors.util.InventoryHelper;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

public class TokenRenameMenu extends AnvilPromptMenu {
   private final ItemStack token;

   public TokenRenameMenu(int syncId, Inventory playerInventory, ItemStack token) {
      super(
         syncId,
         playerInventory,
         token,
         helper(
            "§e§lRename your token",
            "§7Type the brand name, e.g.",
            "§6§lWheel of Fortune",
            "§7It's stamped on the token and shown",
            "§7when someone redeems it."
         )
      );
      this.token = token.copy();
   }

   public static void open(ServerPlayer player, ItemStack token) {
      player.openMenu(new SimpleMenuProvider((syncId, inv, p) -> new TokenRenameMenu(syncId, inv, token), Component.literal("§6§lRename Token")));
   }

   @Override
   protected boolean canAccept(String text) {
      String s = text == null ? "" : text.trim();
      return !s.isEmpty() && s.length() <= 32;
   }

   @Override
   protected void renderResult(ItemStack result, String text) {
      String s = text == null ? "" : text.trim();
      if (this.canAccept(s)) {
         result.set(DataComponents.CUSTOM_NAME, Component.literal("§6§l" + s));
         result.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("§7Click to confirm the brand name"))));
      } else {
         result.set(DataComponents.CUSTOM_NAME, Component.literal("§c§lType a name (1-32 chars)"));
      }
   }

   @Override
   protected void accept(ServerPlayer player, String text) {
      String s = text == null ? "" : text.trim();
      if (!this.canAccept(s)) {
         Chat.msg(player, "&cThat name isn't valid (1-32 characters).");
         InventoryHelper.giveOrDrop(player, this.token);
      } else {
         TokenManager.applyCustomName(this.token, s);
         InventoryHelper.giveOrDrop(player, this.token);
         Chat.raw(player, "&6Your token is now branded &f\"" + s + "&f\"&6! Hand it out, or redeem it at a &eToken Redeemer&6.");
      }
   }

   @Override
   protected void reopen(ServerPlayer player) {
   }
}
