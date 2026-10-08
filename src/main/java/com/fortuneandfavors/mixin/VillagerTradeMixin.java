package com.fortuneandfavors.mixin;

import com.fortuneandfavors.economy.CustomEnchantments;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 26.2 turned villager trades into datapack registry entries, so the old
 *  TradeOfferHelper is gone from Fabric API. Instead we append one custom
 *  enchant tome offer on every librarian restock (level 3+), mirroring the
 *  vanilla librarian's enchanted-book slot. The offer is skipped if one is
 *  already in the villager's trade list so it never stacks up. */
@Mixin(Villager.class)
public abstract class VillagerTradeMixin {
   @Inject(method = "updateTrades", at = @At("TAIL"))
   private void fortuneandfavors$addTomeTrade(ServerLevel level, CallbackInfo ci) {
      Villager self = (Villager)(Object)this;
      if (self.level().isClientSide()) {
         return;
      }
      if (self.getVillagerData().level() < 3) {
         return;
      }
      if (!self.getVillagerData().profession().is(VillagerProfession.LIBRARIAN)) {
         return;
      }

      MerchantOffers offers = self.getOffers();
      for (MerchantOffer existing : offers) {
         if (CustomEnchantments.isTome(existing.getResult())) {
            return; // already selling a tome this restock - don't stack up
         }
      }

      String key = CustomEnchantments.ALL_KEYS[self.getRandom().nextInt(CustomEnchantments.ALL_KEYS.length)];
      int lvl = 1 + self.getRandom().nextInt(Math.min(5, CustomEnchantments.maxLevelOf(key)));
      int price = 24 + self.getRandom().nextInt(17);
      offers.add(
         new MerchantOffer(
            new ItemCost(Items.EMERALD, price),
            CustomEnchantments.tome(key, lvl),
            3,
            20,
            0.2F
         )
      );
   }
}