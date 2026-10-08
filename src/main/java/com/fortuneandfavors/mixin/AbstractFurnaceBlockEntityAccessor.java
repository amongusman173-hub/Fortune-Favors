package com.fortuneandfavors.mixin;

import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reads and writes a furnace's remaining burn time.
 *
 * <p>The Super Smelter burns fuel faster than the furnace it is built on, and the burn clock is the
 * one number that decision lives in - it is private, so it is reached through an accessor rather
 * than by touching vanilla's own tick.
 */
@Mixin(AbstractFurnaceBlockEntity.class)
public interface AbstractFurnaceBlockEntityAccessor {
   @Accessor("litTimeRemaining")
   int fortuneandfavors$litTimeRemaining();

   @Accessor("litTimeRemaining")
   void fortuneandfavors$setLitTimeRemaining(int value);

   /**
    * How long the current cook takes. Written by vanilla from two places - the input slot's own
    * change and the tick a cook finishes - and scaled by both, or a Super Smelter would be fast for
    * exactly one item. See {@code AbstractFurnaceBlockEntityMixin} for why this is an accessor.
    */
   @Accessor("cookingTotalTime")
   void fortuneandfavors$setCookingTotalTime(int value);

   @Accessor("cookingTotalTime")
   int fortuneandfavors$cookingTotalTime();
}
