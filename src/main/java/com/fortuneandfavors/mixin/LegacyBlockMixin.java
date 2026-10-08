package com.fortuneandfavors.mixin;

import com.fortuneandfavors.duel.DuelManager;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(Player.class)
public abstract class LegacyBlockMixin {
   // Sword blocking: reduces melee damage by 50% when the player is in the
   // blocking state AND the attack comes from the front (1.8 directional block,
   // ~100 degrees like the vanilla shield). Does NOT block projectiles. Still
   // takes knockback and hurt cam - just 50% less damage.
   @ModifyVariable(method = "actuallyHurt", at = @At("HEAD"), argsOnly = true, index = 3)
   private float fortuneandfavors$swordBlock(float amount, net.minecraft.server.level.ServerLevel level, DamageSource source) {
      Player self = ((Player)(Object)this);
      if (amount <= 0.0F) {
         return amount; // Don't reduce zero damage
      }
      // Only block melee-ish attacks (not projectiles, not fall, not fire, not drowning)
      if (!source.is(net.minecraft.tags.DamageTypeTags.IS_PROJECTILE)
         && !source.is(net.minecraft.tags.DamageTypeTags.IS_FALL)
         && !source.is(net.minecraft.tags.DamageTypeTags.IS_FIRE)
         && !source.is(net.minecraft.tags.DamageTypeTags.IS_DROWNING)
         && DuelManager.isLegacySwordBlocking(self)
         && isInFront(self, source)) {
         return amount * 0.5F;
      }
      return amount;
   }

   /** True when the damage comes from roughly in front of the player (horizontal
    *  angle <= ~50 degrees either side, like 1.8 blocking / the vanilla shield). */
   private static boolean isInFront(Player player, DamageSource source) {
      Vec3 srcPos = source.getSourcePosition();
      if (srcPos == null) {
         return true; // No positional info - don't block the reduction
      }
      Vec3 look = player.getLookAngle();
      Vec3 to = srcPos.subtract(player.position());
      double lookLen = Math.sqrt(look.x * look.x + look.z * look.z);
      double toLen = Math.sqrt(to.x * to.x + to.z * to.z);
      if (lookLen < 1.0E-4 || toLen < 1.0E-4) {
         return true; // Dead-on vertical or zero distance - treat as frontal
      }
      return (look.x * to.x + look.z * to.z) / (lookLen * toLen) >= 0.64;
   }
}
