package com.fortuneandfavors.mixin;

import com.fortuneandfavors.economy.PlayerRaidManager;
import com.llamalad7.mixinextras.injector.WrapWithCondition;
import net.minecraft.advancements.triggers.PlayerTrigger;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.entity.raid.Raider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * For player-started raids (bell + Bad Omen or Raid Banner) the boss phase IS the
 * victory: the vanilla raid winning its waves only means the five champions spawn.
 * So the vanilla "You defeated a raid!" celebration - Hero of the Village, the
 * raid_win stat and its advancement trigger, and the victory boss-bar rename - is
 * suppressed for tracked raids. The mod announces the true victory in
 * {@link PlayerRaidManager#settleVictory} once every boss is dead.
 */
@Mixin(Raid.class)
public abstract class RaidMixin {
   @WrapWithCondition(
      method = "tick",
      at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;addEffect(Lnet/minecraft/world/effect/MobEffectInstance;)Z")
   )
   private boolean fortuneandfavors$noVanillaHeroGrant(LivingEntity entity, MobEffectInstance effect) {
      return !PlayerRaidManager.isTrackedRaid((Raid)(Object)this);
   }

   @WrapWithCondition(
      method = "tick",
      at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;awardStat(Lnet/minecraft/resources/Identifier;)V")
   )
   private boolean fortuneandfavors$noVanillaRaidWinStat(ServerPlayer player, Identifier stat) {
      return !PlayerRaidManager.isTrackedRaid((Raid)(Object)this);
   }

   @WrapWithCondition(
      method = "tick",
      at = @At(value = "INVOKE", target = "Lnet/minecraft/advancements/triggers/PlayerTrigger;trigger(Lnet/minecraft/server/level/ServerPlayer;)V")
   )
   private boolean fortuneandfavors$noVanillaRaidWinTrigger(PlayerTrigger trigger, ServerPlayer player) {
      return !PlayerRaidManager.isTrackedRaid((Raid)(Object)this);
   }

   // The celebration branch re-shows the vanilla raid bar as "Victory" every 20
   // ticks; our boss bar replaces it, so keep it hidden for tracked raids.
   @WrapWithCondition(
      method = "tick",
      at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerBossEvent;setVisible(Z)V", ordinal = 1)
   )
   private boolean fortuneandfavors$noVanillaVictoryBar(ServerBossEvent bar, boolean visible) {
      return !PlayerRaidManager.isTrackedRaid((Raid)(Object)this);
   }

   // --- Dynamic wave raider scaling ------------------------------------------

   // More players near the village = more waves of raiders (same multiplier the
   // boss phase uses). Vanilla villager raids are untouched (scale is 1.0).
   @Inject(method = "getNumGroups", at = @At("RETURN"), cancellable = true)
   private void fortuneandfavors$scaleRaidWaveCount(Difficulty difficulty, CallbackInfoReturnable<Integer> cir) {
      double scale = PlayerRaidManager.raidScale((Raid)(Object)this);
      if (scale > 1.0) {
         int base = cir.getReturnValue();
         cir.setReturnValue(Math.max(base, (int)Math.round(base * scale)));
      }
   }

   // Every raider that joins the raid also gets its HP scaled by the same
   // multiplier, so bigger parties face tankier wave raiders too.
   @Inject(method = "joinRaid", at = @At("TAIL"))
   private void fortuneandfavors$scaleJoinedRaider(
      ServerLevel level, int wave, Raider raider, BlockPos pos, boolean leader, CallbackInfo ci
   ) {
      double scale = PlayerRaidManager.raidScale((Raid)(Object)this);
      if (scale <= 1.0) {
         return;
      }
      AttributeInstance hp = raider.getAttribute(Attributes.MAX_HEALTH);
      if (hp != null) {
         hp.setBaseValue(hp.getBaseValue() * scale);
         raider.setHealth(raider.getMaxHealth());
      }
   }
}
