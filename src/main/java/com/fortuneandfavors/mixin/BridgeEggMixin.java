package com.fortuneandfavors.mixin;

import com.fortuneandfavors.duel.DuelManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEgg;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Projectile.class)
public class BridgeEggMixin {
   private static final int MAX_BRIDGE = 32;

   @Inject(method = "tick", at = @At("HEAD"))
   private void fortuneandfavors$bridgeEggTick(CallbackInfo ci) {
      if (((Object)this) instanceof ThrownEgg egg) {
         if (!egg.level().isClientSide() && egg.level() instanceof ServerLevel sl) {
            if (DuelManager.isDuelRealm(sl)) {
               CompoundTag tag = fortuneandfavors$bridgeTag(egg);
               if (tag != null && tag.getInt("ff_bridge_egg").orElse(0) == 1) {
                  int laid = tag.getInt("ff_bridge_laid").orElse(0);
                  if (laid >= 32) {
                     return;
                  }

                  // The bridge is laid flat, at the level the egg left the hand.
                  //
                  // Two things were wrong with the old version and between them they
                  // made the item do nothing at all. The tag was read off the <b>egg
                  // entity</b>, but a thrown egg carries the stack it was made from
                  // and its own component map is empty - so {@code ff_bridge_egg} was
                  // never seen and the block loop never ran. And the spot was pinned
                  // to y=100, the deck the spawn islands are built on, then placed
                  // only where that spot was already air: on your own island (where
                  // you actually throw it) that is the ground you are standing on, so
                  // nothing appeared, and over the void it laid a staircase one block
                  // below the egg instead of a bridge under your feet.
                  int y = tag.getInt("ff_bridge_y").orElse(Integer.MIN_VALUE);
                  if (y == Integer.MIN_VALUE) {
                     Entity owner = egg.getOwner();
                     y = (owner != null ? Mth.floor(owner.getY()) : egg.blockPosition().getY()) - 1;
                     tag.putInt("ff_bridge_y", y);
                  }

                  BlockPos floor = new BlockPos(egg.blockPosition().getX(), y, egg.blockPosition().getZ());
                  if (sl.getBlockState(floor).isAir()) {
                     sl.setBlock(floor, ((Block)Blocks.WOOL.white()).defaultBlockState(), 3);
                     com.fortuneandfavors.net.FfVfx.particles(sl, ParticleTypes.CLOUD, floor.getX() + 0.5, floor.getY() + 0.9, floor.getZ() + 0.5, 4, 0.3, 0.05, 0.3, 0.01);
                     if (laid % 6 == 0) {
                        sl.playSound(
                           null, floor.getX() + 0.5, floor.getY() + 0.5, floor.getZ() + 0.5, SoundEvents.WOOL_PLACE, SoundSource.BLOCKS, 0.7F, 1.0F
                        );
                     }

                     tag.putInt("ff_bridge_laid", ++laid);
                     fortuneandfavors$storeBridgeTag(egg, tag);
                  }
               }
            }
         }
      }
   }

   /**
    * The bridge tag, read from the stack the egg carries.
    *
    * <p>Falls back to the entity's own components so a bridge egg spawned by
    * anything that does copy component data onto the projectile still works.
    */
   private static CompoundTag fortuneandfavors$bridgeTag(ThrownEgg egg) {
      ItemStack stack = egg.getItem();
      if (DuelManager.isBridgeEgg(stack)) {
         CustomData data = stack.get(DataComponents.CUSTOM_DATA);
         return data != null ? data.copyTag() : new CompoundTag();
      }

      CustomData own = egg.get(DataComponents.CUSTOM_DATA);
      if (own != null) {
         CompoundTag tag = own.copyTag();
         if (tag.getInt("ff_bridge_egg").orElse(0) == 1) {
            return tag;
         }
      }

      return null;
   }

   private static void fortuneandfavors$storeBridgeTag(ThrownEgg egg, CompoundTag tag) {
      ItemStack stack = egg.getItem().copy();
      stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
      egg.setItem(stack);
   }
}
