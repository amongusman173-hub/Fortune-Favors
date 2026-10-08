package com.fortuneandfavors.menu;

import com.fortuneandfavors.guild.GuildManager;
import com.fortuneandfavors.util.Chat;
import com.fortuneandfavors.util.PriceUtil;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import java.util.List;

public class MineDepositPromptMenu extends AnvilPromptMenu {
   public MineDepositPromptMenu(int syncId, Inventory playerInventory) {
      super(
         syncId,
         playerInventory,
         new ItemStack(Items.GOLD_INGOT),
         helper(
            "§6§lDeposit into the guild mine",
            "§7Type an amount, e.g.",
            "§a5k  §7=  5,000",
            "§a250k  §7=  250,000",
            "§a1.2m  §7=  1,200,000"
         )
      );
   }

   public static void open(ServerPlayer player) {
      player.openMenu(new SimpleMenuProvider((syncId, inv, p) -> new MineDepositPromptMenu(syncId, inv), Component.literal("§6§lDeposit")));
   }

   @Override
   protected boolean canAccept(String text) {
      return PriceUtil.parse(text) > 0L;
   }

   @Override
   protected void renderResult(ItemStack result, String text) {
      long amount = PriceUtil.parse(text);
      if (amount > 0L) {
         result.set(DataComponents.CUSTOM_NAME, Component.literal("§a§lDeposit " + Chat.moneyStr(amount) + "§r"));
         result.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("§7Click to confirm"))));
      } else {
         result.set(DataComponents.CUSTOM_NAME, Component.literal("§c§lType an amount"));
         result.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("§7Examples: §a5k§7, §a250k§7, §a1.2m"))));
      }
   }

   @Override
   protected void accept(ServerPlayer player, String text) {
      long amount = PriceUtil.parse(text);
      if (amount <= 0L) {
         return;
      }
      String err = GuildManager.mineDeposit(player, amount);
      if (err != null) {
         Chat.msg(player, "&c" + err);
      } else {
         Chat.msg(player, "&aDeposited " + Chat.moneyStr(amount) + " into the guild mine.");
      }
   }

   @Override
   protected void reopen(ServerPlayer player) {
      GuildMineMenu.open(player);
   }
}
