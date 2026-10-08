package com.fortuneandfavors.mixin;

import com.fortuneandfavors.economy.ClaimManager;
import com.fortuneandfavors.util.Safe;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Fluid stops at a claim's border.
 *
 * <p>A claim protects the blocks inside it from players, and fluid was the one way in
 * that needed no permission at all: water or lava poured one block outside a claim
 * flowed in and washed away crops, torches, redstone and the floor of a build, and
 * nothing in the mod ever asked a question about it. The bucket is the whole exploit.
 *
 * <p>The hook is {@code getSpread}, which is where a fluid decides where it wants to go
 * this tick: it returns the direction-to-state map the spread then executes, so
 * dropping the entries that would cross a border stops both the flow and the flowing
 * block, in water and lava alike, however the fluid got there. Refusing <i>every</i>
 * block change in a claimed chunk would have been simpler and wrong - it would freeze
 * the owner's own farms - so the rule is only about crossing out of one owner's land
 * into another's. See {@code ClaimManager.fluidMaySpreadInto}.
 */
@Mixin(FlowingFluid.class)
public abstract class FlowingFluidMixin {
   @Inject(method = "getSpread", at = @At("RETURN"), cancellable = true)
   private void fortuneandfavors$stopAtClaimBorders(
      ServerLevel level, BlockPos pos, BlockState state, CallbackInfoReturnable<Map<Direction, FluidState>> cir
   ) {
      Map<Direction, FluidState> spread = cir.getReturnValue();
      if (spread == null || spread.isEmpty()) {
         return;
      }

      Safe.run("fluid claim check", () -> {
         Map<Direction, FluidState> kept = new LinkedHashMap<>(spread.size());

         for (Map.Entry<Direction, FluidState> e : spread.entrySet()) {
            if (ClaimManager.fluidMaySpreadInto(level, pos, pos.relative(e.getKey()))) {
               kept.put(e.getKey(), e.getValue());
            }
         }

         if (kept.size() != spread.size()) {
            cir.setReturnValue(kept);
         }
      });
   }
}
