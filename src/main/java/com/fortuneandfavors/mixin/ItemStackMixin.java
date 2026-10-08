package com.fortuneandfavors.mixin;

import com.fortuneandfavors.economy.CCEnchantments;
import com.fortuneandfavors.economy.SkillManager;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.Random;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ItemStack.class)
public abstract class ItemStackMixin {
   private static final Random RANDOM = new Random();

   @WrapOperation(
      method = "hurtAndBreak(ILnet/minecraft/server/level/ServerLevel;Lnet/minecraft/server/level/ServerPlayer;Ljava/util/function/Consumer;)V",
      at = @At(value = "INVOKE", target = "processDurabilityChange(ILnet/minecraft/server/level/ServerLevel;Lnet/minecraft/server/level/ServerPlayer;)I")
   )
   private static int fortuneandfavors$durabilityProtection(
      ItemStack instance, int amount, ServerLevel level, ServerPlayer player, Operation<Integer> operation
   ) {
      int dmg = (Integer)operation.call(new Object[]{instance, amount, level, player});
      if (dmg > 0 && player != null) {
         // Curse of Fragility: extra durability loss (2/3/4x total, min +1).
         int curse = CCEnchantments.levelOf(instance, CCEnchantments.CURSE_FRAGILITY);
         if (curse > 0) {
            dmg = dmg * (1 + curse); // II = 2x, III = 3x (I = 2x? use 1+level)
            if (curse >= 2) {
               dmg += curse; // extra flat bump so higher levels feel worse
            }
         }
         String id = BuiltInRegistries.ITEM.getKey(instance.getItem()).toString();
         if (!id.endsWith("_pickaxe")) {
            return dmg;
         }

         float chance = SkillManager.durabilityChance(player.getUUID());
         return chance > 0.0F && RANDOM.nextFloat() < chance ? 0 : dmg;
      } else {
         return dmg;
      }
   }
}
