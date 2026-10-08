package com.fortuneandfavors.util;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

public final class SoundUtil {
   private SoundUtil() {
   }

   public static void play(ServerPlayer player, SoundEvent event) {
      play(player, event, 1.0F);
   }

   public static void play(ServerPlayer player, SoundEvent event, float pitch) {
      play(player, event, pitch, 1.0F);
   }

   public static void play(ServerPlayer player, SoundEvent event, float pitch, float volume) {
      BlockPos pos = player.blockPosition();
      player.level().playSound(null, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, event, SoundSource.PLAYERS, volume, pitch);
   }
}
