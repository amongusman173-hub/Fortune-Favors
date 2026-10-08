package com.fortuneandfavors.mixin;

import com.fortuneandfavors.FortuneFavorsMod;
import com.fortuneandfavors.economy.CCEnchantments;
import com.fortuneandfavors.economy.ClaimManager;
import com.fortuneandfavors.economy.PlayerRaidManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fail-closed PvP claim enforcement on Player.attack, mirroring the block-level
 * ServerPlayerGameModeMixin. Runs after fabric-api's Player.attack handler
 * (priority 1100 > 1000) so the event layer decides first and we only step in
 * when it didn't (or couldn't) deny the attack.
 */
@Mixin(value = Player.class, priority = 1100)
public abstract class PlayerAttackMixin {
   @Inject(method = "attack", at = @At("HEAD"), cancellable = true)
   private void fortuneandfavors$claimAttack(Entity target, CallbackInfo ci) {
      if (ci.isCancelled()) {
         return;
      }
      Player self = ((Player)(Object)this);
      if (!(self instanceof ServerPlayer attacker) || !(target instanceof ServerPlayer victim)) {
         return;
      }
      try {
         if (!ClaimManager.pvpAllowed(attacker, victim) && !PlayerRaidManager.isActiveBetrayer(victim.getUUID())) {
            ClaimManager.warnClaimed(attacker, "&cThis land has PvP disabled - you can't fight here.");
            this.fortuneandfavors$denyAttack(attacker, ci);
         }
      } catch (VirtualMachineError e) {
         throw e;
      } catch (Throwable t) {
         FortuneFavorsMod.LOGGER.error("Fortune & Favors: claim pvp check failed - denying the attack", t);
         this.fortuneandfavors$denyAttack(attacker, ci);
      }
   }

   /**
    * Refuses the swing and hands the attacker's client their own hand back.
    *
    * <p>A swing is the one refusal with no block to put back, because a client does not
    * predict the world for an attack - it predicts <i>itself</i>: {@code MultiPlayerGameMode}
    * runs the local {@code player.attack(target)} before the server has agreed to anything,
    * which is where the weapon's durability the attacker can see comes from. The server's
    * weapon is untouched, so a refusal that says nothing leaves a sword that looks spent and
    * a swing that looked landed. One packet of inventory state is the whole correction.
    */
   private void fortuneandfavors$denyAttack(ServerPlayer attacker, CallbackInfo ci) {
      com.fortuneandfavors.util.ClientResync.refusedAttack(attacker);
      ci.cancel();
   }

   // Combo I-III: consecutive hits against the SAME target build damage. Each hit
   // bumps the combo; hitting a different target, missing, or getting hit resets
   // it. Max 3 hits in the window, so the total bonus is +12%, +24%, or +36%.
   // (All damage buffs reported to the damage pipeline via actualDamage.)
   @Inject(method = "attack", at = @At("HEAD"))
   private void fortuneandfavors$comboOnAttack(Entity target, CallbackInfo ci) {
      Player self = ((Player)(Object)this);
      if (self.level().isClientSide()) {
         return;
      }
      if (self.getLastHurtMobTimestamp() >= self.tickCount && self.getLastHurtMob() != target) {
         CCEnchantments.resetCombo(self);
      } else {
         CCEnchantments.bumpCombo(self);
      }
   }
}