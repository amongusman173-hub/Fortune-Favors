package com.fortuneandfavors.block;

import com.fortuneandfavors.ModSounds;
import com.fortuneandfavors.economy.MachineManager;
import com.fortuneandfavors.economy.ModConfig;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.state.BlockState;

public final class ElevatorBlock {
   private static final long HELD_SNEAK_COOLDOWN = 15L;
   private static final long JUMP_GUARD = 4L;
   private static final Map<UUID, Long> lastUse = new HashMap<>();
   private static final Map<UUID, Boolean> wasSneaking = new HashMap<>();
   private static final Map<UUID, Boolean> wasOnGround = new HashMap<>();
   private static final Map<UUID, Boolean> wasJumping = new HashMap<>();

   private ElevatorBlock() {
   }

   public static void tickPlayer(ServerPlayer player) {
      if (ModConfig.is("elevator")) {
         ServerLevel level = player.level();
         BlockPos below = player.getBlockPosBelowThatAffectsMyMovement();
         BlockPos eff = below;
         BlockState belowState = level.getBlockState(below);
         if (!MachineManager.isElevator(level, eff) && isSoftTop(belowState) && MachineManager.isElevator(level, below.below())) {
            eff = below.below();
         }

         UUID id = player.getUUID();
         boolean sneaking = player.isShiftKeyDown();
         boolean wasSneak = wasSneaking.getOrDefault(id, false);
         wasSneaking.put(id, sneaking);
         // The jump KEY (synced from the client) is tracked too, so elevators
         // still work when a low ceiling blocks the actual jump - the key press
         // is the trigger, not the physics.
         boolean jumpKey = player.getLastClientInput() != null && player.getLastClientInput().jump();
         boolean wasJump = wasJumping.getOrDefault(id, false);
         wasJumping.put(id, jumpKey);
         boolean freshJumpKey = jumpKey && !wasJump;
         long now = level.getGameTime();
         if (lastUse.size() > 64) {
            lastUse.entrySet().removeIf(e -> now - e.getValue() > 80L);
         }

         if (!MachineManager.isElevator(level, eff)) {
            wasOnGround.put(id, player.onGround());
         } else {
            Long last = lastUse.get(id);
            boolean freshSneakPress = sneaking && !wasSneak;
            boolean wasGrounded = wasOnGround.getOrDefault(id, true);
            wasOnGround.put(id, player.onGround());
            if (sneaking) {
               if (freshSneakPress || last == null || now - last >= 15L) {
                  BlockPos target = findElevator(level, eff, false);
                  if (target != null) {
                     teleport(player, level, target, hiddenByCarpet(level, eff) || hiddenByCarpet(level, target));
                     lastUse.put(id, now);
                  }
               }
            } else {
               boolean freshJump = freshJumpKey || wasGrounded && !player.onGround() && player.getDeltaMovement().y > 0.05;
               if (freshJump) {
                  if (last != null && now - last < 4L) {
                     return;
                  }

                  BlockPos target = findElevator(level, eff, true);
                  if (target != null) {
                     teleport(player, level, target, hiddenByCarpet(level, eff) || hiddenByCarpet(level, target));
                     lastUse.put(id, now);
                  }
               }
            }
         }
      }
   }

   private static BlockPos findElevator(ServerLevel level, BlockPos from, boolean up) {
      int x = from.getX();
      int z = from.getZ();
      int start = up ? from.getY() + 1 : from.getY() - 1;
      int min = level.getMinY();
      int max = level.getHeight() - 1;
      int step = up ? 1 : -1;

      for (int y = start; up ? y <= max : y >= min; y += step) {
         BlockPos pos = new BlockPos(x, y, z);
         if (MachineManager.isElevator(level, pos)) {
            BlockState above = level.getBlockState(new BlockPos(x, y + 1, z));
            if (isSoftTop(above)) {
               return pos;
            }
         }
      }

      return null;
   }

   /** Air, replaceable blocks (grass, water...) or carpets still leave room to stand on the elevator. */
   private static boolean isSoftTop(BlockState state) {
      return state.isAir() || state.canBeReplaced() || state.getBlock() instanceof net.minecraft.world.level.block.CarpetBlock;
   }

   private static void teleport(ServerPlayer player, ServerLevel level, BlockPos target, boolean quiet) {
      double x = target.getX() + 0.5;
      double y = target.getY() + 1.0;
      double z = target.getZ() + 0.5;
      player.teleportTo(x, y, z);
      player.fallDistance = 0.0;
      if (!quiet) {
         level.sendParticles(ParticleTypes.PORTAL, x, y + 0.2, z, 24, 0.3, 0.3, 0.3, 0.1);
         level.playSound(null, x, y, z, ModSounds.ELEVATOR, SoundSource.BLOCKS, 1.0F, 1.0F);
      }
   }

   /** True when a carpet sits on top of an elevator, hiding it as a secret floor. */
   private static boolean hiddenByCarpet(ServerLevel level, BlockPos elevatorPos) {
      return level.getBlockState(elevatorPos.above()).getBlock() instanceof net.minecraft.world.level.block.CarpetBlock;
   }
}
