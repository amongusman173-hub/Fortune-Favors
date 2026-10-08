package com.fortuneandfavors.mixin;

import com.fortuneandfavors.economy.VanishManager;
import net.minecraft.core.BlockPos;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A vanished staff member must leave <i>nothing</i> behind - not a name tag, not
 * armour, not a held weapon, and not the plume of dust their sprint throws up or
 * the thud of their boots.
 *
 * <p>The armour and held items are handled by {@code VanishManager} (they are
 * stashed off the body), but step sounds and the sprint's terrain particles are
 * emitted by the entity itself, so they have to be cut at the source. This runs
 * on both sides from the main mixin set: the server copy stops the sound and
 * particle packets going out to everyone else, and the client copy stops the
 * vanished player's own client rendering them for themselves.
 *
 * <p>The guard used to be "invisible with no invisibility effect on them", which was a fair
 * description of a vanished body until this mod started handing out short invisibility effects of
 * its own: the moment a vanished moderator picked one up, the guess stopped matching, the mutes
 * stopped firing, and they were audible and visible again while still believing they were hidden.
 * The question is now asked of {@link VanishManager#isHiddenBody} - the server's ledger on a
 * server, the synced marker team on a client - and the old narrow shape is kept underneath it
 * for a body that some other plugin has vanished the old way. An ordinary invisibility potion
 * is still ordinary gameplay and still keeps its footsteps.
 */
@Mixin(Entity.class)
public abstract class VanishStepMixin {
   @Unique
   private boolean fortuneandfavors$vanishedFootsteps() {
      if (!((Object)this instanceof LivingEntity living) || !(living instanceof Player)) {
         return false;
      }

      if (VanishManager.isHiddenBody(living)) {
         return true;
      }
      return living.isInvisible() && !living.hasEffect(MobEffects.INVISIBILITY);
   }

   /** No boot-step sound for a vanished player - theirs or anyone else's. */
   @Inject(method = "playStepSound", at = @At("HEAD"), cancellable = true)
   private void fortuneandfavors$muteVanishedStep(BlockPos pos, BlockState state, CallbackInfo ci) {
      if (fortuneandfavors$vanishedFootsteps()) {
         ci.cancel();
      }
   }

   /** No sprint dust kicked up by a vanished player. */
   @Inject(method = "spawnSprintParticle", at = @At("HEAD"), cancellable = true)
   private void fortuneandfavors$hideVanishedStepParticles(CallbackInfo ci) {
      if (fortuneandfavors$vanishedFootsteps()) {
         ci.cancel();
      }
   }
}
