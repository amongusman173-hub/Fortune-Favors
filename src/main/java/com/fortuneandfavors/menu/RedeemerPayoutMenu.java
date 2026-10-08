package com.fortuneandfavors.menu;

import com.fortuneandfavors.ModSounds;
import com.fortuneandfavors.economy.MachineManager;
import com.fortuneandfavors.economy.TokenManager;
import com.fortuneandfavors.economy.MachineManager.Machine;
import com.fortuneandfavors.util.Chat;
import com.fortuneandfavors.util.PriceUtil;
import com.fortuneandfavors.util.SoundUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public class RedeemerPayoutMenu extends AnvilPromptMenu {
   private final BlockPos pos;
   private final int version;

   public RedeemerPayoutMenu(int syncId, Inventory playerInventory, BlockPos pos, int version) {
      super(
         syncId,
         playerInventory,
         new ItemStack(Items.PAPER),
         helper(
            "§e§lPayout for Token " + TokenManager.versionName(version),
            "§7Type how much redeeming this token pays, e.g.",
            "§a1k  §7=  1,000",
            "§a5k  §7=  5,000",
            "§7or just a number: §a2500",
            "",
            "§7The money comes out of the",
            "§7redeemer's funded pool."
         )
      );
      this.pos = pos;
      this.version = version;
      this.returnInputOnCancel = false;
   }

   public static void open(ServerPlayer player, BlockPos pos, int version) {
      player.openMenu(new SimpleMenuProvider((syncId, inv, p) -> new RedeemerPayoutMenu(syncId, inv, pos, version), Component.literal("§6§lSet Payout")));
   }

   @Override
   protected boolean canAccept(String text) {
      return PriceUtil.parse(text) > 0L;
   }

   @Override
   protected void renderResult(ItemStack result, String text) {
      long amount = PriceUtil.parse(text);
      if (amount > 0L) {
         result.set(DataComponents.CUSTOM_NAME, Component.literal("§a§lSet payout §f" + Chat.moneyStr(amount) + "§r"));
      } else {
         result.set(DataComponents.CUSTOM_NAME, Component.literal("§c§lType an amount (10k, 5m, 2500...)"));
      }
   }

   @Override
   protected void accept(ServerPlayer player, String text) {
      long amount = PriceUtil.parse(text);
      if (amount > 0L) {
         Machine m = MachineManager.get(player.level(), this.pos);
         if (m != null && "token_redeemer".equals(m.type())) {
            if (!m.owner().equals(player.getUUID())) {
               Chat.msg(player, "&cOnly the owner can change payouts.");
            } else {
               MachineManager.setPayout(player.level(), this.pos, this.version, amount);
               Chat.raw(player, "&aToken " + TokenManager.versionName(this.version) + " now pays " + Chat.moneyStr(amount) + " at this redeemer.");
               SoundUtil.play(player, ModSounds.BUY);
            }
         } else {
            Chat.msg(player, "&cThe redeemer is gone.");
         }
      }
   }

   @Override
   protected void reopen(ServerPlayer player) {
      RedeemerMenu.open(player, this.pos);
   }
}
