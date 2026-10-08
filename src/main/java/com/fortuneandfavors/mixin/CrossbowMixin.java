package com.fortuneandfavors.mixin;

import com.fortuneandfavors.economy.CCEnchantments;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(CrossbowItem.class)
public abstract class CrossbowMixin {
   // ---------------------------------------------------------------- chainfire
   // When shooting a chainfire crossbow, fire 4 extra arrows alongside the
   // normal shot by re-running the vanilla shoot() with the same loaded
   // projectiles. Ammo is not consumed extra: chainfire is balanced by
   // overriding infinity-style ammo (the item keeps the loaded stack).
   //
   // Also the single choke point for the revolver: every bolt that leaves a
   // crossbow passes through here, so we spend a cylinder shot and re-arm the
   // next bolt (fan-fire) or let the cylinder run dry so the next trigger pull
   // starts the slow reload.
   @WrapOperation(
      method = "performShooting",
      at = @At(
         value = "INVOKE",
         target = "Lnet/minecraft/world/item/CrossbowItem;shoot(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/InteractionHand;Lnet/minecraft/world/item/ItemStack;Ljava/util/List;FFZLnet/minecraft/world/entity/LivingEntity;)V"
      )
   )
   private void fortuneandfavors$chainfireShoot(
      CrossbowItem instance,
      ServerLevel level,
      LivingEntity shooter,
      InteractionHand hand,
      ItemStack weapon,
      List<ItemStack> projectiles,
      float velocity,
      float inaccuracy,
      boolean crit,
      LivingEntity target,
      Operation<Void> original
   ) {
      original.call(instance, level, shooter, hand, weapon, projectiles, velocity, inaccuracy, crit, target);
      // Revolver: spend a cylinder shot and re-arm the next bolt.
      if (shooter instanceof ServerPlayer sp && CCEnchantments.isRevolverCrossbow(weapon)) {
         CCEnchantments.revolverOnShotFired(sp, weapon, level);
      }
      // Chainfire: queue four extra arrows so each one leaves on its own tick.
      // The callback is captured here because the mixin operation is only valid
      // during the original performShooting call.
      if (CCEnchantments.isChainfireCrossbow(weapon) && projectiles != null && !projectiles.isEmpty()) {
         CCEnchantments.queueChainfireShots(
            level,
            shooter,
            hand,
            weapon,
            projectiles,
            velocity,
            inaccuracy,
            crit,
            target,
            CCEnchantments.extraChainArrows(weapon),
            (queuedLevel, queuedShooter, queuedHand, queuedWeapon, queuedProjectiles, queuedVelocity, queuedInaccuracy, queuedCrit, queuedTarget) ->
               original.call(instance, queuedLevel, queuedShooter, queuedHand, queuedWeapon, queuedProjectiles, queuedVelocity, queuedInaccuracy, queuedCrit, queuedTarget)
         );
      }
      // Recoil: a stronger backward push (level 1-3).
      int recoil = CCEnchantments.levelOf(weapon, CCEnchantments.RECOIL);
      if (recoil > 0 && shooter instanceof Player) {
         Vec3 look = shooter.getViewVector(1.0F).normalize();
         double horizontal = 0.22 + recoil * 0.18;
         double vertical = 0.08 + recoil * 0.08;
         shooter.setDeltaMovement(shooter.getDeltaMovement().add(-look.x * horizontal, vertical, -look.z * horizontal));
         shooter.hurtMarked = true;
      }
   }
}
