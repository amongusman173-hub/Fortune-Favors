package com.fortuneandfavors.mixin;

import com.fortuneandfavors.duel.DuelManager;
import com.fortuneandfavors.economy.ExpeditionManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.food.FoodData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The real natural-regen path on normal+ difficulties: {@link FoodData#tick}
 * converts food/saturation into health (UHC duel fighters start with full food
 * and saturation, so this would otherwise heal them constantly). Cancelling it
 * while a player is an active UHC duel participant disables regen for exactly
 * that player - the old approach flipped the global {@code naturalRegeneration}
 * gamerule, which killed regen for the entire server.
 *
 * <p>It is also where an expedition's health budget is enforced. Vanilla heals a full,
 * saturated body up to a point of health every ten ticks - six a second at its best, which is
 * more than any camp in the maze mends and more than most of the maze's damage does - and it
 * arrives whether or not the explorer earned it. Food carried into a site still feeds them and
 * no longer carries them: see {@link ExpeditionManager#siteRegen}, which is the number, and
 * {@link ExpeditionManager#SITE_REGEN_EFFICIENCY}, which is where it was decided.
 *
 * <p>Both of the two heal calls inside {@code tick} are wrapped - the saturation beat and the
 * plain-food beat - and they are told apart by ordinal because they are otherwise identical
 * instructions. That is the whole of the coupling to vanilla here, and it is deliberate: the
 * scaling lives in the economy class where it can be read and asserted, and this file only says
 * <i>where</i> to apply it.
 */
@Mixin(FoodData.class)
public abstract class FoodDataMixin {
   @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
   private void fortuneandfavors$blockUhcFoodRegen(ServerPlayer player, CallbackInfo ci) {
      if (DuelManager.isUhcNoRegen(player.getUUID())) {
         ci.cancel();
      }
   }

   /** The saturation beat: a point of health every ten ticks while a body is full and fed. */
   @Redirect(
      method = "tick",
      at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;heal(F)V", ordinal = 0)
   )
   private void fortuneandfavors$thinSiteSaturationRegen(ServerPlayer player, float amount) {
      player.heal(ExpeditionManager.siteRegen(player.getUUID(), amount));
   }

   /** ...and the slow beat: a point of health every eighty ticks on food alone. */
   @Redirect(
      method = "tick",
      at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;heal(F)V", ordinal = 1)
   )
   private void fortuneandfavors$thinSiteFoodRegen(ServerPlayer player, float amount) {
      player.heal(ExpeditionManager.siteRegen(player.getUUID(), amount));
   }
}
