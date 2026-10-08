package com.fortuneandfavors.mixin;

import com.fortuneandfavors.FortuneFavorsMod;
import com.fortuneandfavors.economy.VanishManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A vanished body does not pull experience out of the air.
 *
 * <p>Everything else about a vanish is about what other players can <i>see</i>. This one is about
 * what the world does to them, and it is the reason a staff member walking around invisible used to
 * leave a dotted line of green: an experience orb is not attracted to whoever is nearest, it is
 * attracted to whoever the level says is nearest, and no part of that question asks whether the
 * answer can be seen. The orb drifts in, the pickup fires, and the moderator's position is written
 * across the terrain in floating sparks - one after another, all the way to the point of contact.
 *
 * <p>So the two halves are cut here. Orbs never <i>home</i> on a vanished body (the attraction
 * itself, which is the trail), and orbs are never <i>collected</i> by one (which is what makes the
 * trail end in a disappearance right next to an invisible player). The same rule is in
 * {@code ItemEntityMixin} for dropped items, for exactly the same reason. A vanish that vacuums up
 * loot is not a vanish: it is a vacuum cleaner with an alibi, and the debris it leaves behind is
 * the tell.
 */
@Mixin(ExperienceOrb.class)
public abstract class VanishOrbMixin {
   /**
    * How far away an orb notices a body. Vanilla's own range, mirrored rather than read, because
    * this is the one number that decides whether an orb is allowed to care about somebody.
    */
   private static final double FOLLOW_RANGE = 8.0;

   /**
    * No homing on a hidden body - this is the trail, cut at its source.
    *
    * <p>Asked of the nearest player rather than of the orb's current target, because the target is
    * only refreshed inside this very method: cancelling while the orb has no target yet is what
    * stops one ever being taken. A body that was already being followed stops being followed on the
    * next tick of this same path, which is as soon as the vanish lands.
    */
   @Inject(method = "followNearbyPlayer", at = @At("HEAD"), cancellable = true)
   private void fortuneandfavors$noVanishedOrbMagnet(CallbackInfo ci) {
      try {
         ExperienceOrb self = (ExperienceOrb)(Object)this;
         Player nearest = self.level().getNearestPlayer(self, FOLLOW_RANGE);
         if (nearest != null && VanishManager.isHiddenBody(nearest)) {
            ci.cancel();
         }
      } catch (VirtualMachineError e) {
         throw e;
      } catch (Throwable t) {
         // Swallowed on purpose and never in silence: this runs on the entity tick of every orb on
         // the server, so a failure here is a failure in the hot path and has to say so once.
         FortuneFavorsMod.LOGGER.error("Fortune & Favors: experience-orb vanish guard failed", t);
      }
   }

   /**
    * And no collecting the orbs that are already lying around - by a hidden body, or by one that
    * belongs to nobody.
    *
    * <p>The second half is the puppet rule: a mind-controlled copy is a fake ServerPlayer, and an
    * orb does not care whose body is near it, so a fight against a copy was paying the copy. A body
    * that exists to be fought carries a costume and no pockets.
    */
   @Inject(method = "playerTouch", at = @At("HEAD"), cancellable = true)
   private void fortuneandfavors$noVanishedOrbPickup(Player player, CallbackInfo ci) {
      try {
         if (VanishManager.isHiddenBody(player) || com.fortuneandfavors.economy.BossManager.isFakePlayer(player)) {
            ci.cancel();
         }
      } catch (VirtualMachineError e) {
         throw e;
      } catch (Throwable t) {
         FortuneFavorsMod.LOGGER.error("Fortune & Favors: experience-orb pickup guard failed", t);
      }
   }
}
