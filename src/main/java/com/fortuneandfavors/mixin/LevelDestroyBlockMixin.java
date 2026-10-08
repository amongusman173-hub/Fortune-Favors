package com.fortuneandfavors.mixin;

import com.fortuneandfavors.economy.ClaimManager;
import com.fortuneandfavors.economy.MirageCastleManager;
import com.fortuneandfavors.economy.PrisonCellblock;
import com.fortuneandfavors.util.Safe;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mobs cannot take a claim apart.
 *
 * <p>Everything except this one was already refused inside a claim: nobody can break,
 * place, use, open, drop, push, flow, ignite or blow anything up in somebody else's
 * land. Mobs were the hole. An enderman carries the wall away block by block, a ravager
 * flattens a crop field, silverfish eat their own infested stone out of a floor, and the
 * wither carves a tunnel through a base - all of it block destruction inside a claim,
 * none of it a player, so none of it ever asked the question.
 *
 * <p>{@code Level.destroyBlock} is the single funnel every one of those paths goes
 * through, and it is the only block-breaking entry point that is handed the entity that
 * caused it. So the rules live here in one place:
 *
 * <ul>
 *   <li>a non-player entity may not destroy a block inside a claim. A player's own
 *       destruction is left as it was - players are policed at the interaction layer,
 *       where the reach, the tool and the target are all known, and refusing them here as
 *       well would mean two copies of the same rule to keep in step;</li>
 *   <li>nobody at all may destroy a block of a standing Mirage Castle. That one is
 *       deliberately unbreakable while it exists, or it is a free quarry instead of an
 *       event - and the pieces have to survive intact for the undo list to be exact;</li>
 *   <li>and nobody at all may destroy a live break-out's gate. A prisoner holds an iron
 *       pickaxe and iron bars are iron bars: without this, the locked gate at the end of
 *       a prison tunnel is a gate anybody with the tool they were issued can take apart,
 *       which is the same as having no gate at all.</li>
 * </ul>
 *
 * <p>A {@code null} cause (world generation, a command, a script) is left alone: that is
 * the server itself, not a mob.
 */
@Mixin(Level.class)
public abstract class LevelDestroyBlockMixin {
   @Inject(method = "destroyBlock(Lnet/minecraft/core/BlockPos;ZLnet/minecraft/world/entity/Entity;I)Z", at = @At("HEAD"), cancellable = true)
   private void fortuneandfavors$mobsRespectClaims(
      BlockPos pos, boolean dropBlock, Entity entity, int recursionLeft, CallbackInfoReturnable<Boolean> cir
   ) {
      if (entity == null || entity.level().isClientSide()) {
         return;
      }

      // The mirage comes first, because it applies to everybody: the castle is not
      // breakable by the fighters it appeared for either.
      Safe.run("mirage claim check", () -> {
         if (MirageCastleManager.isProtected(entity.level(), pos)) {
            this.fortuneandfavors$deny(entity, pos, cir);
         }
      });

      // Then the prison gate, for the same reason: the man who is standing at it has the tool
      // that would otherwise open it, and the key is supposed to be the only way through.
      Safe.run("prison gate check", () -> {
         if (PrisonCellblock.isEscapeGate(pos)) {
            this.fortuneandfavors$deny(entity, pos, cir);
         }
      });

      if (cir.isCancelled() || entity instanceof Player) {
         return;
      }

      // Say nothing to anybody: this is not an event a player caused or needs to be told
      // about, and a mob cannot read chat. The block simply stays where it is.
      Safe.run("mob claim check", () -> {
         if (!ClaimManager.mobMayDestroy(entity.level(), pos)) {
            this.fortuneandfavors$deny(entity, pos, cir);
         }
      });
   }

   /**
    * Refuses the break and tells a player's client the block is still there.
    *
    * <p>This layer sits <i>inside</i> the call that a player's own break goes through
    * ({@code ServerPlayerGameMode.destroyBlock} calls {@code Level.destroyBlock}), so it is
    * behind the two refusal layers that already resync - and it is the layer that owns the
    * one refusal a player will meet most often in a Mirage Castle: every wall of it. The
    * mob case never needs this (a mob has no client), which is exactly why it used to be
    * easy to leave out.
    */
   private void fortuneandfavors$deny(Entity entity, BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
      if (entity instanceof ServerPlayer player) {
         com.fortuneandfavors.util.ClientResync.block(player, pos);
      }
      cir.setReturnValue(false);
   }
}
