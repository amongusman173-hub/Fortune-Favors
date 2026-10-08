package com.fortuneandfavors.mixin;

import com.fortuneandfavors.economy.MirageCastleManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A Royal Guard leaves dust and nothing else.
 *
 * <p>The guards of a Mirage Castle are part of the mirage: a castle that was never really
 * there cannot have a body on its floor, and it cannot hand out loot for defending
 * something that does not exist. {@code dropAllDeathLoot} is the one call vanilla makes for
 * everything a death leaves behind - the loot table, the equipment, the experience - so
 * cancelling it is the whole rule, and it is the same hook the snow realm uses to hold a
 * player's items.
 *
 * <p>The dust is drawn here rather than in the entity's own removal because this is the
 * last moment the guard is standing where it died, which is where the puff belongs.
 */
@Mixin(LivingEntity.class)
public class MirageGuardMixin {
   @Inject(method = "dropAllDeathLoot", at = @At("HEAD"), cancellable = true)
   private void fortuneandfavors$guardsTurnToDust(ServerLevel level, DamageSource source, CallbackInfo ci) {
      if (((Object)this) instanceof LivingEntity self && MirageCastleManager.isGuard(self)) {
         MirageCastleManager.dust(self);
         ci.cancel();
      }
   }
}
