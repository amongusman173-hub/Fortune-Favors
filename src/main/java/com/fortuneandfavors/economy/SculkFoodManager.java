package com.fortuneandfavors.economy;

import com.fortuneandfavors.ModSounds;
import com.fortuneandfavors.util.SoundUtil;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;

/** The Sculk Fruit - a snack harvested from the Elder Warden. Eating restores\n *  hunger and grants a short burst of sculk sight (Night Vision) plus a little\n *  regeneration. Server-side eat handling so the item works everywhere. */
public final class SculkFoodManager {
   private SculkFoodManager() {
   }

   public static void eat(ServerPlayer player, ItemStack stack) {
      if (stack == null || stack.isEmpty()) {
         return;
      }
      if (!player.getAbilities().instabuild) {
         stack.shrink(1);
      }
      player.getFoodData().eat(6, 0.7F);
      player.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, 200, 0, false, true));
      player.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 100, 0, false, true));
      if (player.level() instanceof ServerLevel sl) {
         sl.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.GENERIC_EAT, SoundSource.PLAYERS, 0.8F, 1.1F);
         sl.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.SCULK_CATALYST_BLOOM, SoundSource.PLAYERS, 0.5F, 1.4F);
         sl.sendParticles(ParticleTypes.SCULK_SOUL, player.getX(), player.getY() + 1.0, player.getZ(), 12, 0.4, 0.5, 0.4, 0.03);
         sl.sendParticles(ParticleTypes.ENCHANT, player.getX(), player.getY() + 0.8, player.getZ(), 10, 0.4, 0.4, 0.4, 0.02);
      }
      SoundUtil.play(player, ModSounds.BUY);
   }
}