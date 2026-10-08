package com.fortuneandfavors.mixin;

import com.fortuneandfavors.economy.ClaimManager;
import com.fortuneandfavors.economy.ClaimManager.Claim;
import com.fortuneandfavors.util.Safe;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A claim's "Fire spread" switch, asked at both ends of the flame.
 *
 * <p>The switch was only ever read at the <b>source</b>: {@code checkBurnOut} was cancelled
 * while the burning block stood in a claim that had it off. That looks like a fix and is
 * half of one. A flame is a thing that moves - the one that matters is the one the lava
 * next door lights <i>outside</i> the claim, because its own position is on open ground, so
 * it is allowed to do anything, and the block it reaches into is inside the claim where
 * nothing was asking a question. Turning the switch off therefore read in game as "my fire
 * is frozen and their fire walks in".
 *
 * <p>So the rule is now the fluid rule: the <b>destination</b> decides. Both hooks below
 * refuse by handing vanilla back the state that is already at the position - a no-op
 * {@code setBlock} - rather than air, because vanilla replaces a flammable block with the
 * flame it builds: returning air would delete the plank instead of saving it.
 *
 * <p>Which hook covers what:
 * <ul>
 *   <li>{@code getStateWithAge} is where vanilla builds the flame a spread would place, and
 *       it is worth cancelling at the source as well, so fire already inside a claim cannot
 *       creep outward across the border it is allowed to exist on.</li>
 *   <li>Its own aging call passes the flame's own position, where the state already at that
 *       position <i>is</i> fire - that branch is left alone, so an existing fire still ages,
 *       still lights the room and still burns out.</li>
 * </ul>
 */
@Mixin(FireBlock.class)
public abstract class FireBlockMixin {
   @Inject(method = "checkBurnOut", at = @At("HEAD"), cancellable = true)
   private void fortuneandfavors$noFireSpread(Level level, BlockPos pos, int chance, RandomSource random, int age, CallbackInfo ci) {
      if (!level.isClientSide()) {
         Safe.run("fire spread claim check", () -> {
            Claim c = ClaimManager.claimAt(level, pos);
            if (c != null && !c.allowFireSpread) {
               ci.cancel();
            }
         });
      }
   }

   /**
    * The destination's answer: fire may not start at a position whose claim has the switch
    * off. Refusing with the state that is already there makes the spread a no-op - the air
    * stays air, the plank stays a plank.
    */
   @Inject(method = "getStateWithAge", at = @At("RETURN"), cancellable = true)
   private void fortuneandfavors$noFireIgnition(LevelReader level, BlockPos pos, int age, CallbackInfoReturnable<BlockState> cir) {
      BlockState made = cir.getReturnValue();
      if (made == null || !made.is(Blocks.FIRE)) {
         // Nothing is being lit here, or nothing that is fire: not our question.
         return;
      }

      Safe.run("fire ignition claim check", () -> {
         BlockState here = level.getBlockState(pos);
         if (here.is(Blocks.FIRE)) {
            // The flame's own aging call - an existing fire, not a new one.
            return;
         }

         if (!ClaimManager.fireMayIgniteAt(level, pos)) {
            cir.setReturnValue(here);
         }
      });
   }
}
