package com.fortuneandfavors.mixin;

import com.fortuneandfavors.ModItems;
import com.fortuneandfavors.economy.CCEnchantments;
import com.fortuneandfavors.economy.CustomEnchantments;
import com.fortuneandfavors.economy.ForgeOps;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.core.Holder;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AnvilMenu.class)
public abstract class AnvilMixin {
   // NOTE: only fields DECLARED in AnvilMenu itself can be @Shadow-ed here (this
   // mod runs without a mixin refmap, so inherited fields don't resolve). The
   // input/result slots live on the ItemCombinerMenu superclass, so they are
   // reached through the public Slot API instead (getSlot(0/1/2)).
   @Shadow
   private int repairItemCountCost;
   @Shadow
   private String itemName;
   @Shadow
   private DataSlot cost;

   // Cap the anvil's computed level cost at 39 (the vanilla hard cap) so combining items is
   // always possible as long as the player has the XP - the "Too Expensive" wall is removed.
   @WrapOperation(method = "createResult", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Mth;clamp(JJJ)J"))
   private long fortuneandfavors$capAnvilCost(long value, long min, long max, Operation<Long> original) {
      return original.call(value, min, Math.min(max, 39L));
   }

   // Custom enchantment tomes (the original five + the CCEnchantments combat
   // enchants) work in the anvil like vanilla enchanted books: put the tome in
   // either slot with an enchantable weapon in the other and the enchant fuses
   // for a small level cost. The tome is consumed on take (vanilla onTake clears
   // both slots since repairItemCountCost stays 0).
   @Inject(method = "createResult", at = @At("HEAD"), cancellable = true)
   private void fortuneandfavors$applyCustomTome(CallbackInfo ci) {
      AnvilMenu menu = (AnvilMenu)(Object)this;
      ItemStack a = menu.getSlot(AnvilMenu.INPUT_SLOT).getItem();
      ItemStack b = menu.getSlot(AnvilMenu.ADDITIONAL_SLOT).getItem();
      boolean aTome = isCustomTome(a);
      boolean bTome = isCustomTome(b);
      if (!aTome && !bTome) {
         if (vanillaBookNeedsHelp(b, a)) {
            ItemStack result = mergeVanillaBook(b, a);
            if (result == null) {
               clearResult(menu);
            } else {
               menu.getSlot(AnvilMenu.RESULT_SLOT).set(result);
               this.cost.set(1);
               this.repairItemCountCost = 0;
            }
            ci.cancel();
         } else if (vanillaBookNeedsHelp(a, b)) {
            ItemStack result = mergeVanillaBook(a, b);
            if (result == null) {
               clearResult(menu);
            } else {
               menu.getSlot(AnvilMenu.RESULT_SLOT).set(result);
               this.cost.set(1);
               this.repairItemCountCost = 0;
            }
            ci.cancel();
         }
         return; // no custom tome - let vanilla handle ordinary combinations
      }
      ItemStack target;
      ItemStack tome;
      if (aTome && isTargetForTome(b, a)) {
         target = b;
         tome = a;
      } else if (bTome && isTargetForTome(a, b)) {
         target = a;
         tome = b;
      } else if (vanillaBookNeedsHelp(b, a)) {
         target = b;
         tome = a;
         ItemStack result = mergeVanillaBook(target, tome);
         if (result == null) {
            clearResult(menu);
            ci.cancel();
            return;
         }
         menu.getSlot(AnvilMenu.RESULT_SLOT).set(result);
         this.cost.set(1);
         this.repairItemCountCost = 0;
         ci.cancel();
         return;
      } else if (isVanillaBook(b) && needsCustomAnvilSupport(a)) {
         target = a;
         tome = b;
         ItemStack result = mergeVanillaBook(target, tome);
         if (result == null) {
            clearResult(menu);
            ci.cancel();
            return;
         }
         menu.getSlot(AnvilMenu.RESULT_SLOT).set(result);
         this.cost.set(1);
         this.repairItemCountCost = 0;
         ci.cancel();
         return;
      } else {
         // Custom tome with no enchantable target - let vanilla handle it
         // (rename/repair of the base item, or no result).
         return;
      }
      ItemStack result = ForgeOps.applyTomeToCopy(target, tome);
      if (result == null) {
         clearResult(menu);
         ci.cancel();
         return;
      }
      if (this.itemName != null && !this.itemName.isEmpty()) {
         result.set(DataComponents.CUSTOM_NAME, Component.literal(this.itemName));
      }
      menu.getSlot(AnvilMenu.RESULT_SLOT).set(result);
      this.cost.set(tomeLevelOf(tome));
      this.repairItemCountCost = 0;
      ci.cancel();
   }

   private static boolean isCustomTome(ItemStack stack) {
      return stack != null && !stack.isEmpty() && (CustomEnchantments.isTome(stack) || CCEnchantments.isCCTome(stack));
   }

   private static boolean isVanillaBook(ItemStack stack) {
      return stack != null && !stack.isEmpty() && stack.is(Items.ENCHANTED_BOOK)
         && stack.get(DataComponents.STORED_ENCHANTMENTS) != null
         && !stack.get(DataComponents.STORED_ENCHANTMENTS).isEmpty();
   }

   private static boolean needsCustomAnvilSupport(ItemStack stack) {
      return isEnchantableTarget(stack)
         && (ModItems.isForgeLegendary(stack) || hasCustomEnchant(stack));
   }

   /**
    * A vanilla enchanted book this mod wants to merge itself rather than hand to vanilla.
    *
    * <p>Two reasons, and the second is the one that matters: the target is one of the mod's own
    * (a forge legendary, or something already carrying a custom enchantment, which vanilla's
    * anvil will not touch) - or the <b>book</b> carries a level above the enchantment's own
    * maximum. Vanilla clamps that, so an Efficiency VII book slid into an ordinary anvil came out
    * the far side as Efficiency V with nothing to say for itself, and the book would have been a
    * rare drop that quietly did nothing. The merge below writes the level it was handed.
    *
    * <p>The enchantable check stays on every path: a book is not a licence to enchant an anvil.
    */
   private static boolean vanillaBookNeedsHelp(ItemStack target, ItemStack book) {
      return isVanillaBook(book)
         && isEnchantableTarget(target)
         && (needsCustomAnvilSupport(target) || ModItems.overMaxLevelBook(book));
   }

   private static boolean hasCustomEnchant(ItemStack stack) {
      for (String key : CustomEnchantments.ALL_KEYS) {
         if (CustomEnchantments.has(stack, key)) {
            return true;
         }
      }
      for (String key : CCEnchantments.ALL_KEYS) {
         if (CCEnchantments.has(stack, key)) {
            return true;
         }
      }
      return false;
   }

   private static ItemStack mergeVanillaBook(ItemStack target, ItemStack book) {
      if (com.fortuneandfavors.economy.AdvancedEnchantments.conflictsWithVanillaBook(target, book)) {
         return null;
      }
      ItemEnchantments additions = book.get(DataComponents.STORED_ENCHANTMENTS);
      if (additions == null || additions.isEmpty()) {
         return null;
      }
      ItemEnchantments existing = target.get(DataComponents.ENCHANTMENTS);
      ItemEnchantments.Mutable merged = new ItemEnchantments.Mutable(existing == null ? ItemEnchantments.EMPTY : existing);
      for (Holder<Enchantment> enchantment : additions.keySet()) {
         int incoming = additions.getLevel(enchantment);
         int current = merged.getLevel(enchantment);
         if (incoming > current) {
            merged.set(enchantment, incoming);
         }
      }
      ItemStack result = target.copy();
      result.set(DataComponents.ENCHANTMENTS, merged.toImmutable());
      result.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      if (CustomEnchantments.has(target, CustomEnchantments.HANDYMAN)) {
         result.set(DataComponents.REPAIR_COST, 0);
      }
      return result;
   }

   private void clearResult(AnvilMenu menu) {
      menu.getSlot(AnvilMenu.RESULT_SLOT).set(ItemStack.EMPTY);
      this.cost.set(0);
      this.repairItemCountCost = 0;
   }

   // Handyman keeps the vanilla repair-cost component from compounding. Apply
   // this at RETURN as well as in the custom merge paths so later repairs and
   // vanilla enchanted-book combinations retain the benefit.
   @Inject(method = "createResult", at = @At("RETURN"))
   private void fortuneandfavors$keepHandymanRepairCostLow(CallbackInfo ci) {
      AnvilMenu menu = (AnvilMenu)(Object)this;
      ItemStack result = menu.getSlot(AnvilMenu.RESULT_SLOT).getItem();
      if (CustomEnchantments.has(result, CustomEnchantments.HANDYMAN)) {
         result.set(DataComponents.REPAIR_COST, 0);
      }
   }

   private static boolean isEnchantableTarget(ItemStack stack) {
      return ForgeOps.isEnchantableTarget(stack);
   }

   private static boolean isTargetForTome(ItemStack target, ItemStack tome) {
      return ForgeOps.isTargetForTome(target, tome);
   }

   private static int tomeLevelOf(ItemStack tome) {
      if (CCEnchantments.isCCTome(tome)) {
         String key = CCEnchantments.ccTomeKey(tome);
         return key == null ? 1 : CCEnchantments.levelOf(tome, key);
      }
      return CustomEnchantments.tomeLevel(tome);
   }
}
