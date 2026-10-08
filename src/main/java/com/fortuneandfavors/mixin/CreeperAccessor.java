package com.fortuneandfavors.mixin;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.monster.Creeper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The Creeper's own power flag, reached from the outside.
 *
 * <p>A charged creeper has no public setter in this version - {@code isPowered()} exists but the
 * only way to turn it on is to set the synced data value directly - and the End Island Protectors
 * are charged by design. Reaching the accessor rather than re-implementing the flag means the
 * explosion, the aura and the death are all still the game's.
 *
 * <p>Registered as {@code CreeperAccessor} in {@code fortuneandfavors.mixins.json}; an accessor
 * that is not listed loads as nothing at all, and the summon silently stops being charged.
 */
@Mixin(Creeper.class)
public interface CreeperAccessor {
   @Accessor("DATA_IS_POWERED")
   static EntityDataAccessor<Boolean> fortuneandfavors$powered() {
      throw new AssertionError();
   }
}
