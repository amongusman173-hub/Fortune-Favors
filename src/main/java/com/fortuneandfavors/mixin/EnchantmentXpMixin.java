package com.fortuneandfavors.mixin;

import com.fortuneandfavors.economy.SkillManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Player.class)
public abstract class EnchantmentXpMixin {
   @Unique
   private int fortuneandfavors$enchantXpBefore;

   @Inject(method = "onEnchantmentPerformed", at = @At("HEAD"))
   private void fortuneandfavors$beforeEnchant(ItemStack stack, int levelCost, CallbackInfo ci) {
      try {
         if (((Object)this) instanceof ServerPlayer sp) {
            this.fortuneandfavors$enchantXpBefore = sp.experienceLevel;
         }
      } catch (Exception var6) {
      }
   }

   @Inject(method = "onEnchantmentPerformed", at = @At("RETURN"))
   private void fortuneandfavors$afterEnchant(ItemStack stack, int levelCost, CallbackInfo ci) {
      try {
         if (!(((Object)this) instanceof ServerPlayer sp)) {
            return;
         }

         SkillManager.addEnchantingXp(sp, 8L);
         // Mender: the table's own work mends what it worked on. Applied after the enchant rather
         // than instead of it, so the item leaves the table repaired and enchanted - and only when
         // it has something to mend, so a pristine tool is not handed free durability.
         float mend = SkillManager.menderAmount(sp.getUUID());
         if (mend > 0.0F && !stack.isEmpty() && stack.isDamageableItem() && stack.getDamageValue() > 0) {
            stack.setDamageValue(Math.max(0, stack.getDamageValue() - Math.round(mend)));
         }

         int lost = this.fortuneandfavors$enchantXpBefore - sp.experienceLevel;
         this.fortuneandfavors$enchantXpBefore = 0;
         if (lost > 0 && SkillManager.hasUpgrade(sp.getUUID(), "enchanting", "soulbind")) {
            sp.giveExperienceLevels(lost / 2);
         }
      } catch (Exception var6) {
      }
   }
}
