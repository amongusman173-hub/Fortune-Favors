package com.fortuneandfavors.mixin;

import com.fortuneandfavors.economy.MachineManager;
import com.fortuneandfavors.economy.MachineTuning;
import com.fortuneandfavors.economy.MachineVfx;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.objectweb.asm.Opcodes;

/**
 * The Super Smelter: a furnace that cooks faster than a blast furnace, and pays for it in fuel.
 *
 * <p>It is an ordinary furnace under the record, so it already runs every smelting recipe there is -
 * food, glass, stone, clay and metal alike, which a blast furnace cannot do. Two edits are the whole
 * difference, and each one has a twin, because a furnace decides how long a cook takes in two
 * different places:
 *
 * <ol>
 *   <li>{@code getTotalCookTime} is what {@code setItem} asks when the input slot changes - the
 *       moment a player drops an iron ore in. Scaling its answer is what makes the <i>first</i> item
 *       fast.
 *   <li>{@code serverTick} writes the cook time a second time, straight from the recipe, on the tick
 *       a cook finishes. That write is why a Super Smelter used to smelt exactly one item quickly
 *       and then behave like an ordinary furnace: the first item took the scaled number from (1),
 *       and the moment it popped out, (2) replaced the scaled number with the recipe's own. The
 *       redirect below scales that write the same way, so every item after the first is fast too.
 * </ol>
 *
 * <p>The fuel is the other half and it is a tier as well: a faster smelter also spends its burn clock
 * faster, so what a player buys is throughput and not a cheaper ingot. Nothing else about a furnace
 * changes, so a Super Smelter hoppers, opens and lights exactly like the block it is built on.
 */
@Mixin(AbstractFurnaceBlockEntity.class)
public abstract class AbstractFurnaceBlockEntityMixin {
   /**
    * The tier a smelter is asked for, or tier one when it is not a machine (or not loaded).
    *
    * <p>The number itself lives with every other tier in {@link MachineTuning}: tier one is 0.4, so
    * 200 ticks becomes 80 - 2.5x a furnace, a little better than a blast furnace's flat 2x - and
    * the shop's later tiers take it down from there.
    */
   private static int fortuneandfavors$tier(ServerLevel level, BlockPos pos) {
      if (level == null || pos == null) {
         return 1;
      }
      return MachineTuning.tier(MachineManager.keyFor(level, pos));
   }

   @Inject(method = "getTotalCookTime", at = @At("RETURN"), cancellable = true)
   private static void fortuneandfavors_superSmelterSpeed(
      ServerLevel level, AbstractFurnaceBlockEntity blockEntity, CallbackInfoReturnable<Integer> cir
   ) {
      if (level == null || blockEntity == null) {
         return;
      }
      if (MachineManager.isSuperSmelter(level, blockEntity.getBlockPos())) {
         double speed = MachineTuning.smelterSpeed(fortuneandfavors$tier(level, blockEntity.getBlockPos()));
         cir.setReturnValue(Math.max(1, (int)Math.round(cir.getReturnValue() * speed)));
      }
   }

   /**
    * The second write: {@code serverTick} sets the cook time straight from the recipe when a cook
    * completes. Redirecting the field store (rather than injecting at a bytecode offset) is what
    * keeps this from depending on the shape of vanilla's method, and it is the fix for the "one item
    * fast, then normal" report.
    */
   @Redirect(
      method = "serverTick",
      at = @At(
         value = "FIELD",
         target = "Lnet/minecraft/world/level/block/entity/AbstractFurnaceBlockEntity;cookingTotalTime:I",
         opcode = Opcodes.PUTFIELD
      )
   )
   private static void fortuneandfavors_superSmelterCookTimeWrite(
      AbstractFurnaceBlockEntity blockEntity, int value
   ) {
      int next = value;
      net.minecraft.world.level.Level level = blockEntity.getLevel();
      if (level instanceof ServerLevel server && MachineManager.isSuperSmelter(server, blockEntity.getBlockPos())) {
         double speed = MachineTuning.smelterSpeed(fortuneandfavors$tier(server, blockEntity.getBlockPos()));
         next = Math.max(1, (int)Math.round(value * speed));
      }
      ((AbstractFurnaceBlockEntityAccessor)(Object)blockEntity).fortuneandfavors$setCookingTotalTime(next);
   }

   @Inject(method = "serverTick", at = @At("TAIL"))
   private static void fortuneandfavors_superSmelterFuel(
      ServerLevel level, BlockPos pos, BlockState state, AbstractFurnaceBlockEntity blockEntity, CallbackInfo ci
   ) {
      if (level == null || level.isClientSide() || blockEntity == null || pos == null) {
         return;
      }
      if (!MachineManager.isSuperSmelter(level, pos)) {
         return;
      }
      AbstractFurnaceBlockEntityAccessor burn = (AbstractFurnaceBlockEntityAccessor)(Object)blockEntity;
      int lit = burn.fortuneandfavors$litTimeRemaining();
      if (lit > 0) {
         int extra = MachineTuning.smelterExtraFuel(fortuneandfavors$tier(level, pos));
         if (extra > 0) {
            burn.fortuneandfavors$setLitTimeRemaining(Math.max(0, lit - extra));
         }
         // Only a smelter that is actually burning is worth a flame; an idle one stays quiet, and
         // the particle still has to win the server-wide budget in MachineVfx.
         MachineVfx.pulse(level, pos, MachineManager.TYPE_SUPER_SMELTER);
      }
   }
}
