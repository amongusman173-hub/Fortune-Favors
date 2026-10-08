package com.fortuneandfavors.mixin;

import java.util.List;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * The only way to empty a body's synced effect-particle list, asked for by name at compile time.
 *
 * <p>This exists because the reflective version of this call <b>could not work, ever</b>, and the
 * failure was invisible: {@link com.fortuneandfavors.economy.VanishManager} did
 * {@code p.getClass().getMethod("removeEffectParticles")}, and {@code Class.getMethod} only ever
 * returns <i>public</i> members. {@code LivingEntity.removeEffectParticles()} is {@code protected},
 * so the lookup threw {@code NoSuchMethodException} on every tick of every vanish - into a
 * {@code catch (Throwable ignored)}, which is why the cloud stayed on a vanished moderator for
 * every release up to this one. The same string lookup would also have failed under production
 * (intermediary) mappings, where the method is not called that, so the dev environment failing
 * quietly was masking a second way for it to fail quietly.
 *
 * <p>An {@code @Invoker} is the answer to both: the mapping is resolved by the mixin processor at
 * build time, the access is patched into the target, and a rename upstream becomes a compile error
 * rather than a tell that survives a release. The second invoker and the accessor are here for the
 * same reason one step further out - they let the self-test fill the cloud and read it back, so
 * "the cloud is emptied" is an assertion about what a client would be told rather than an
 * assertion that a line of code was reached. See {@code VanishManager.effectParticleCount}.
 *
 * <p>A second mixin - one that also cancelled vanilla's <i>writer</i> for this list on a hidden
 * body - was written and deliberately not shipped: it could not be shown to apply, and the sweep
 * below already runs every tick, so the honest thing was to keep the half that is measurable.
 */
@Mixin(LivingEntity.class)
public interface VanishEffectParticleInvoker {
   /** Empties the synced list. The call the old reflection never managed to make. */
   @Invoker("removeEffectParticles")
   void fortuneandfavors$removeEffectParticles();

   /**
    * Vanilla's own writer for that list.
    *
    * <p>Private, so nothing outside a mixin can reach it - which is the point: the self-test needs
    * to be able to <i>fill</i> the cloud to prove the emptying works, and a check that can only
    * observe an empty list proves nothing about anything.
    */
   @Invoker("updateSynchronizedMobEffectParticles")
   void fortuneandfavors$updateSynchronizedMobEffectParticles();

   /** The synced list itself, so a test can count what the clients are being told. */
   @Accessor("DATA_EFFECT_PARTICLES")
   static EntityDataAccessor<List<ParticleOptions>> fortuneandfavors$effectParticles() {
      throw new AssertionError("mixin accessor");
   }
}
