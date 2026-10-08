package com.fortuneandfavors.mixin;

import com.fortuneandfavors.economy.ClaimManager;
import com.fortuneandfavors.util.Safe;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A dispenser may not fire into somebody else's claim.
 *
 * <p>The last unguarded way in. Nothing about a dispenser is a player, so a wall of them
 * one block outside a border could put a fire on your roof, a water source in your
 * basement, bone meal through your farm or a shears through your sheep - none of which
 * asked a question, because the question was only ever asked of the person clicking.
 * TNT from a dispenser was already covered by the explosion filter; this is everything
 * a dispenser does that is not an explosion.
 *
 * <p>The rule is about where it acts, not where it stands: a dispenser may act on the
 * block it faces when that block is in a claim the dispenser is also inside - the
 * owner's own farm, still working - and not otherwise. That is deliberately narrow,
 * because the alternative (refusing every dispenser near a claim) breaks the machines
 * people build in their own land. See {@code ClaimManager.dispenserMayAct}.
 */
@Mixin(DispenserBlock.class)
public abstract class DispenserBlockMixin {
   @Inject(method = "dispenseFrom", at = @At("HEAD"), cancellable = true)
   private void fortuneandfavors$dispensersRespectClaims(ServerLevel level, BlockState state, BlockPos pos, CallbackInfo ci) {
      Safe.run("dispenser claim check", () -> {
         Direction facing = state.getValue(DispenserBlock.FACING);
         if (!ClaimManager.dispenserMayAct(level, pos, pos.relative(facing))) {
            ci.cancel();
         }
      });
   }
}
