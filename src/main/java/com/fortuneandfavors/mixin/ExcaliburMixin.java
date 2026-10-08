package com.fortuneandfavors.mixin;

import com.fortuneandfavors.economy.BossManager;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(LivingEntity.class)
public class ExcaliburMixin {
   @ModifyVariable(method = "hurtServer", at = @At("HEAD"), argsOnly = true, ordinal = 0)
   private float fortuneandfavors$excaliburDamage(float amount, ServerLevel level, DamageSource source) {
      if (source.getEntity() instanceof ServerPlayer attacker) {
         ItemStack weapon = attacker.getMainHandItem();
         if (!weapon.is(Items.NETHERITE_SWORD)) {
            return amount;
         } else {
            Component name = (Component)weapon.get(DataComponents.CUSTOM_NAME);
            if (name != null && name.getString().contains("Excalibur")) {
               LivingEntity self = ((LivingEntity)(Object)this);
               return BossManager.isBoss(self) ? amount : 99999.0F;
            } else {
               return amount;
            }
         }
      } else {
         return amount;
      }
   }
}
