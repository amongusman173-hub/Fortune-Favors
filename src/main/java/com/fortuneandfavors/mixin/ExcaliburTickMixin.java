package com.fortuneandfavors.mixin;

import com.fortuneandfavors.ModItems;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments.Mutable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Player.class)
public abstract class ExcaliburTickMixin {
   @Inject(method = "tick", at = @At("HEAD"))
   private void fortuneandfavors$excaliburTick(CallbackInfo ci) {
      Player self = ((Player)(Object)this);
      if (!self.level().isClientSide()) {
         ItemStack held = self.getMainHandItem();
         if (held.is(Items.NETHERITE_SWORD)) {
            Component name = (Component)held.get(DataComponents.CUSTOM_NAME);
            if (name != null && name.getString().contains("Excalibur")) {
               self.addEffect(new MobEffectInstance(MobEffects.STRENGTH, 2, 254, false, false, true));
               ItemEnchantments existing = (ItemEnchantments)held.get(DataComponents.ENCHANTMENTS);
               Holder<Enchantment> sharpness = self.level().registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SHARPNESS);
               int current = existing != null ? existing.getLevel(sharpness) : 0;
               if (current < 255) {
                  Mutable mut = existing != null ? new Mutable(existing) : new Mutable(ItemEnchantments.EMPTY);
                  mut.set(sharpness, 255);
                  held.set(DataComponents.ENCHANTMENTS, mut.toImmutable());
                  held.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, false);
               }
            }
         }

         // Warlord's Axe always carries its advertised Sharpness VI / Smite VI.
         if (ModItems.isWarlordAxe(held)) {
            ItemEnchantments existing = (ItemEnchantments)held.get(DataComponents.ENCHANTMENTS);
            Holder<Enchantment> sharpness = self.level().registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SHARPNESS);
            Holder<Enchantment> smite = self.level().registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SMITE);
            int curSharp = existing != null ? existing.getLevel(sharpness) : 0;
            int curSmite = existing != null ? existing.getLevel(smite) : 0;
            if (curSharp < 6 || curSmite < 6) {
               Mutable mut = existing != null ? new Mutable(existing) : new Mutable(ItemEnchantments.EMPTY);
               if (curSharp < 6) {
                  mut.set(sharpness, 6);
               }
               if (curSmite < 6) {
                  mut.set(smite, 6);
               }
               held.set(DataComponents.ENCHANTMENTS, mut.toImmutable());
            }
         }
      }
   }
}
