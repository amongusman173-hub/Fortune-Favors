package com.fortuneandfavors.mixin;

import com.fortuneandfavors.economy.EnderDragonManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.level.dimension.end.EnderDragonFight;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Holds the Ender Dragon until the rift opens.
 *
 * <p>The one hook the whole rework needs, and it is deliberately the narrowest one available:
 * {@code findOrCreateDragon} is the fight saying <b>"I want a dragon"</b> - not the fight
 * starting, not the arena loading, not a player arriving. Saying no there is precisely "not yet",
 * and every other thing the fight does (scanning state, counting crystals, tracking players,
 * ticking the respawn) keeps running normally around it.
 *
 * <p>Nothing is prevented permanently. {@link EnderDragonManager#shouldHoldTheDragon} refuses
 * only when nothing has walked to the middle of the island, the fight has never been won, and the
 * fight has no dragon of its own - so a rematch is vanilla's, a dragon that already exists is
 * never touched, and the one thing this hook can never do is delete a dragon that is out there.
 *
 * <p>Registered as {@code DragonFightMixin} in fortuneandfavors.mixins.json, and the audit fails
 * the build if it stops being registered *or* stops asking the manager - because a mixin that
 * loads and does nothing is an End where the dragon spawns the moment you walk in, silently.
 */
@Mixin(EnderDragonFight.class)
public abstract class DragonFightMixin {
   @Shadow
   private ServerLevel level;

   @Inject(method = "findOrCreateDragon", at = @At("HEAD"), cancellable = true)
   private void fortuneandfavors$holdTheDragon(CallbackInfo ci) {
      try {
         if (EnderDragonManager.shouldHoldTheDragon(this.level)) {
            ci.cancel();
         }
      } catch (Throwable ignored) {
         // A failure here lets the fight do what it was going to do: a dragon that spawns early
         // is a worse fight, and a fight that can never start is a broken dimension.
      }
   }

   /**
    * Holds the dragon at the **creation**, not only at the request.
    *
    * <p>{@code findOrCreateDragon} is the fight's first path to a dragon, but it is not its only
    * one: a rematch runs the vanilla respawn animation, whose last stage calls
    * {@code createNewDragon} directly, and that path never touches {@code findOrCreateDragon} at
    * all. That is the whole of "sometimes the rework does not apply and it just spawns normally" -
    * the first dragon came through the rite and every one after it dropped out of the sky. Hooking
    * the creation itself makes the two paths one, and the manager releases this by asking the same
    * {@link EnderDragonManager#shouldHoldTheDragon} question once its rift has torn.
    */
   @Inject(method = "createNewDragon", at = @At("HEAD"), cancellable = true)
   private void fortuneandfavors$holdTheCreation(CallbackInfoReturnable<EnderDragon> cir) {
      try {
         if (EnderDragonManager.shouldHoldTheDragon(this.level)) {
            cir.setReturnValue(null);
         }
      } catch (Throwable ignored) {
         // Same bargain as the hold above: a failure here is a dragon, never a broken End.
      }
   }
}
