package com.fortuneandfavors.economy;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Ground-keeping for the scripted bosses.
 *
 * <p>The raid bosses are all {@code setNoAi(true)} puppets: the server moves them
 * by hand every tick, so vanilla never applies gravity to them. That was fine for
 * the ones that were <i>meant</i> to hover, but a caster who is spawned three
 * blocks above the summoner and never comes down reads as a bug - she hovers out
 * of melee reach forever, and her own teleports strand her in the sky. Several of
 * them were also built on flying mobs (the Magister on an evoker, the Scarlet
 * Devil on a phantom) with {@code setNoGravity(true)}, which is exactly the
 * "bosses float mid-air" complaint in one line.
 *
 * <p>This class is the single answer to "where is the floor under this boss", so
 * every manager clamps with the same rule and no two of them can disagree about
 * what counts as standable.
 */
public final class BossGrounding {
   private BossGrounding() {
   }

   /**
    * The Y a mob's feet should sit at on the column {@code (x, z)}: one above the
    * highest standable block, searched downward from a little above {@code fromY}.
    *
    * <p>Falls back to {@code fromY} when the column has nothing to stand on - open
    * sky, over the void, or the middle of a ravine - because dropping a boss to
    * an imaginary floor is worse than letting it hover where it is.
    */
   public static double groundY(ServerLevel level, double x, double z, double fromY) {
      if (level == null) {
         return fromY;
      }
      BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
      int fx = (int)Math.floor(x);
      int fz = (int)Math.floor(z);
      int start = Math.min(level.getMaxY() - 1, (int)Math.floor(fromY) + 6);
      int floor = level.getMinY() + 1;
      for (int y = start; y > floor; y--) {
         probe.set(fx, y, fz);
         BlockState state = level.getBlockState(probe);
         // Standable = a full collision cube, not a slab, fence or carpet.
         if (state.isSolidRender()) {
            return y + 1.0;
         }
      }
      return fromY;
   }

   /**
    * The topmost standable surface of a column, searched from the build limit
    * down rather than from where the boss already is.
    *
    * <p>{@link #groundY} answers "is there a floor near me", which is the wrong
    * question for a scripted move that has to *put* a boss somewhere: a boss that
    * is standing lower than the destination - at the foot of a hill, or in a
    * ravine - gets the hill's floor searched from below it, finds the block its
    * own feet are level with, and lands inside the terrain. Searching the column
    * from the top down is the question the move is actually asking.
    *
    * <p>Falls back to {@code fallback} when the column has nothing to stand on, so
    * a caller can tell "no surface" from "the surface is here" and refuse the move.
    */
   public static double surfaceY(ServerLevel level, double x, double z, double fallback) {
      if (level == null) {
         return fallback;
      }
      BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
      int fx = (int)Math.floor(x);
      int fz = (int)Math.floor(z);
      for (int y = level.getMaxY() - 1; y > level.getMinY() + 1; y--) {
         probe.set(fx, y, fz);
         if (level.getBlockState(probe).isSolidRender()) {
            return y + 1.0;
         }
      }
      return fallback;
   }

   /**
    * True when a boss could be put at this exact spot: the two cells its body
    * needs are free and there is something to stand on.
    *
    * <p>Nothing here teleports a boss by itself - it is the check a caller makes
    * before one, so a scripted move can decline rather than bury him.
    */
   public static boolean standable(ServerLevel level, double x, double y, double z) {
      if (level == null) {
         return false;
      }
      BlockPos feet = BlockPos.containing(x, y + 0.1, z);
      BlockPos head = BlockPos.containing(x, y + 1.1, z);
      return level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
         && level.getBlockState(head).getCollisionShape(level, head).isEmpty()
         && !level.getBlockState(feet.below()).getCollisionShape(level, feet.below()).isEmpty();
   }

   /**
    * Puts a puppet boss back on the surface of its own column, whether it has
    * drifted up or been moved into the world.
    *
    * <p>{@link #clampToGround} only ever lowers a boss, which is right for one that
    * is hovering and useless for one that has been teleported into a hillside: the
    * floor above its head is not something a downward clamp can see. Both of the
    * Time Lord's hand-moved steps - his strike step and his blink - move him with no
    * collision check at all, so a hill between him and his target is something he
    * stands *in*, and "the Time Lord teleports underground" is exactly that.
    */
   public static boolean resurface(ServerLevel level, Mob boss, double tolerance) {
      if (level == null || boss == null) {
         return false;
      }
      double surface = surfaceY(level, boss.getX(), boss.getZ(), boss.getY());
      if (surface > boss.getY() + 0.6) {
         boss.setPos(boss.getX(), surface, boss.getZ());
         boss.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
         boss.hurtMarked = true;
         return true;
      }
      return clampToGround(level, boss, tolerance);
   }

   /**
    * Sets a grounded puppet boss back down when it has drifted above the floor
    * under it. Returns true when it was moved.
    *
    * <p>{@code tolerance} is how far off the floor the boss is allowed to be
    * before it is corrected - a small value keeps it planted, a larger one lets a
    * scripted hop or slam keep its arc.
    */
   public static boolean clampToGround(ServerLevel level, Mob boss, double tolerance) {
      if (level == null || boss == null) {
         return false;
      }
      double floor = groundY(level, boss.getX(), boss.getZ(), boss.getY());
      if (boss.getY() > floor + tolerance) {
         boss.setPos(boss.getX(), floor, boss.getZ());
         boss.setDeltaMovement(boss.getDeltaMovement().x, 0.0, boss.getDeltaMovement().z);
         boss.hurtMarked = true;
         return true;
      }
      return false;
   }
}
