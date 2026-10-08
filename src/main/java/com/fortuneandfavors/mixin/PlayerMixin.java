package com.fortuneandfavors.mixin;

import com.fortuneandfavors.duel.DuelBot;
import com.fortuneandfavors.duel.DuelManager;
import com.fortuneandfavors.economy.CombatGear;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Player.class)
public abstract class PlayerMixin {
   @WrapOperation(method = "actuallyHurt", at = @At(value = "INVOKE", target = "getDamageAfterArmorAbsorb(Lnet/minecraft/world/damagesource/DamageSource;F)F"))
   private float fortuneandfavors$playerCombat(Player instance, DamageSource source, float amount, Operation<Float> operation) {
      return CombatGear.apply(instance, source, amount, operation);
   }

   // Bot damage is intercepted at hurtServer HEAD - BEFORE vanilla's
   // invulnerability-frame gate - so every damage type (arrows included)
   // reaches the bot's own damage handler instead of being swallowed while
   // its melee spam keeps the vanilla hurt cooldown pinned.
   @Inject(method = "hurtServer", at = @At("HEAD"), cancellable = true)
   private void fortuneandfavors$botDamageIntercept(ServerLevel level, DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
      Player self = ((Player)(Object)this);
      if (self instanceof DuelBot bot) {
         if (!bot.level().isClientSide()) {
            DuelManager.BotBlow blow = DuelManager.handleBotDamage(bot, source, amount);
            if (blow.taken()) {
               // The duel applied the damage itself, so vanilla must not apply it
               // twice - but this return value is also what `Player.attack` reads to
               // decide whether the swing landed, and a blow that really connected
               // has to say so or the attacker's own post-hit work is skipped. A
               // mace smash needs that half: the slam and its shockwave live in
               // `MaceItem.hurtEnemy`, which only runs on a landed hit.
               cir.setReturnValue(blow.landed());
            }
         }
      }
   }
}
