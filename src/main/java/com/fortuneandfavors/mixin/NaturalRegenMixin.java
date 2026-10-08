package com.fortuneandfavors.mixin;

import com.fortuneandfavors.duel.DuelManager;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * UHC duels disable natural health regeneration, but game rules are
 * server-global in MC 26.2, so flipping the gamerule used to disable regen for
 * the whole server. Instead, cancel vanilla regen only for players currently
 * fighting in an active UHC duel - everyone else regenerates normally.
 *
 * This handles {@link ServerPlayer#tickRegeneration()} (the peaceful-difficulty
 * passive regen); {@link FoodDataMixin} handles the saturation/food-based regen
 * in {@link net.minecraft.world.food.FoodData#tick(net.minecraft.server.level.ServerPlayer)},
 * which is the real regen path on normal+ difficulties.
 */
@Mixin(ServerPlayer.class)
public abstract class NaturalRegenMixin {
   @Inject(method = "tickRegeneration", at = @At("HEAD"), cancellable = true)
   private void fortuneandfavors$blockUhcRegen(CallbackInfo ci) {
      if (DuelManager.isUhcNoRegen(((ServerPlayer)(Object)this).getUUID())) {
         ci.cancel();
      }
   }
}
