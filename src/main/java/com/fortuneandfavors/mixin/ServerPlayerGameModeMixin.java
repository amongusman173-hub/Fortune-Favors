package com.fortuneandfavors.mixin;

import com.fortuneandfavors.FortuneFavorsMod;
import com.fortuneandfavors.economy.ChestShopManager;
import com.fortuneandfavors.economy.ClaimManager;
import com.fortuneandfavors.util.Chat;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Direct, fail-closed claim enforcement on ServerPlayerGameMode.
 *
 * <p>Why this exists: claim checks also run through the Fabric event layer in
 * ModEvents, but that path is wrapped in Safe, which ALLOWS vanilla behavior when
 * a handler throws, and it depends on the event layer firing at all. This mixin
 * re-checks the claim rules at the vanilla entry points themselves, so a claimed
 * area stays protected even if the event layer is skipped, broken, or swallows an
 * exception. On any unexpected error we DENY the action (fail closed) instead of
 * silently allowing it.
 *
 * <p>Priority 1100 (applied after fabric-api's default 1000) so fabric's
 * ServerPlayerGameModeMixin handlers run first at shared injection points; if the
 * event layer already cancelled the call, we bail out instead of throwing.
 */
@Mixin(value = ServerPlayerGameMode.class, priority = 1100)
public abstract class ServerPlayerGameModeMixin {
   @Shadow
   private ServerPlayer player;

   @Inject(method = "destroyBlock", at = @At("HEAD"), cancellable = true)
   private void fortuneandfavors$claimBreak(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
      try {
         // Anticheat first, and only when an admin has switched it on: a break
         // that finished faster than the tool allows - or an ore with no open face
         // to have been seen through - is refused here, before any of the claim
         // logic below gets to have an opinion about it.
         if (!com.fortuneandfavors.anticheat.AntiCheat.allowBreak(this.player, pos)) {
            this.fortuneandfavors$denyBreak(pos, cir);
            return;
         }

         // ...and neither does a body the Puppeteer is wearing. The hands are his: the one
         // thing a worn player could otherwise still do with them is quietly dig the fight
         // down around everybody's feet.
         if (com.fortuneandfavors.economy.PuppeteerManager.isPossessed(this.player)) {
            this.player.sendOverlayMessage(
               net.minecraft.network.chat.Component.literal(
                  "\u00a75\u2726 \u00a7fYour hand is not your own \u00a78- \u00a7dkill him to get it back."
               )
            );
            this.fortuneandfavors$denyBreak(pos, cir);
            return;
         }

         // Shop chests are unbreakable. Deleting the shop (owner GUI, or
         // /chestshop delete) turns the chest back into an ordinary chest, and
         // *that* can be broken normally - so the shop record is the thing being
         // protected, and there is always a way out for its owner.
         if (ChestShopManager.keyAt(this.player.level(), pos) != null) {
            Chat.msg(this.player, "&cShop chests can't be broken. &7Open its settings and choose &fDELETE SHOP&7 first.");
            this.fortuneandfavors$denyBreak(pos, cir);
            return;
         }

         if (!ClaimManager.canBuild(this.player, pos)) {
            ClaimManager.warnClaimed(this.player, "&cThis land is claimed - you can't break blocks here.");
            this.fortuneandfavors$denyBreak(pos, cir);
         }
      } catch (VirtualMachineError e) {
         throw e;
      } catch (Throwable t) {
         FortuneFavorsMod.LOGGER.error("Fortune & Favors: claim break check failed - denying the break", t);
         this.fortuneandfavors$denyBreak(pos, cir);
      }
   }

   /**
    * Refuses a break and puts the block back on the client that already removed it.
    *
    * <p>The resync is not politeness, it is the whole difference between a refusal and a ghost
    * block. A client predicts a break the moment it swings, so its copy of the block is gone;
    * vanilla is what tells it otherwise, inside {@code destroyBlock}, and this mixin refuses in
    * front of that - so without this line the miner watches the block disappear, the server
    * still holds it, and nothing is ever sent to correct either of them. Vanilla's own two
    * refusals (a block this tool cannot destroy, a spawn-protected one) both send the block
    * back for exactly this reason. Every refusal in the method above goes through here, and a
    * refusal added later should too.
    */
   private void fortuneandfavors$denyBreak(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
      com.fortuneandfavors.util.ClientResync.block(this.player, pos);
      cir.setReturnValue(false);
   }

   /**
    * Mining is measured from the packet that starts it, which is the only place
    * the server is told when a break began: by the time a block is destroyed the
    * duration is already history. Nothing is decided here - the timing is handed
    * to the anticheat, which compares it with the block's own estimate when the
    * break lands.
    */
   @Inject(method = "handleBlockBreakAction", at = @At("HEAD"))
   private void fortuneandfavors$anticheatBreakPacket(
      BlockPos pos,
      net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action action,
      Direction direction,
      int maxBuildHeight,
      int sequence,
      CallbackInfo ci
   ) {
      try {
         // The server's own tick counter, read at packet time and used at both ends of the
         // measurement. Not the level's game time - that is frozen outside the overworld, so in
         // the prison, the realms and the arenas every break measured zero ticks - and not the
         // tick loop's clock, which is sampled at the tick boundary and lags this handler.
         long tick = com.fortuneandfavors.anticheat.AntiCheat.breakClock(this.player);
         switch (action) {
            case START_DESTROY_BLOCK -> com.fortuneandfavors.anticheat.AntiCheat.noteBreakStart(this.player, pos, tick);
            // The stop is where the measurement is taken: the client's own claim about how long
            // the break took. It is not the end of the break on the server, which destroys the
            // block from inside this same handler a few lines below or, if the client's claim
            // outran the server's progress clock, several ticks later.
            case STOP_DESTROY_BLOCK -> com.fortuneandfavors.anticheat.AntiCheat.noteBreakStop(this.player, pos);
            case ABORT_DESTROY_BLOCK -> com.fortuneandfavors.anticheat.AntiCheat.noteBreakEnd(this.player, pos);
            default -> {
            }
         }
      } catch (VirtualMachineError e) {
         throw e;
      } catch (Throwable t) {
         // A missing measurement is not worth a crash; the check simply has
         // nothing to compare against for this break.
         FortuneFavorsMod.LOGGER.error("Fortune & Favors: break timing probe failed", t);
      }
   }

   @Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true)
   private void fortuneandfavors$claimUseItemOn(
      ServerPlayer sp, Level level, ItemStack stack, InteractionHand hand, BlockHitResult hit, CallbackInfoReturnable<InteractionResult> cir
   ) {
      if (cir.isCancelled()) {
         return;
      }

      try {
         BlockPos hitPos = hit.getBlockPos();

         // Items that only *look* like blocks. Every loot box and the Mystery Box
         // is a real vanilla chest wearing custom art, so vanilla happily places a
         // block for it. The event layer already refuses to place those, but it can
         // be skipped or swallow an exception - and the failure mode is a "ghost
         // block": a plain chest standing in the world that has silently eaten a
         // player's loot box. This is the fail-closed backstop.
         //
         // Deliberately narrowed to BlockItems: only those can place anything. A
         // non-block mod item (the Pocket-Watch, a rune, a pouch) is already
         // handled by the event layer, and denying here would swallow the
         // right-click that opens the chest you are pointing at.
         if (stack.getItem() instanceof BlockItem && com.fortuneandfavors.ModItems.isNonPlaceable(stack)) {
            this.fortuneandfavors$denyUse(sp, hit, cir);
            return;
         }

         if (stack.getItem() instanceof BlockItem) {
            BlockPos placePos = hitPos.relative(hit.getDirection());
            if (!ClaimManager.canBuild(sp, placePos)) {
               ClaimManager.warnClaimed(sp, "&cThis land is claimed - you can't build here.");
               this.fortuneandfavors$denyUse(sp, hit, cir);
            }
         } else if (stack.getItem() instanceof BucketItem bucket && !bucket.getContent().isSame(Fluids.EMPTY)) {
            BlockPos placePos = hitPos.relative(hit.getDirection());
            if (!ClaimManager.canBuild(sp, hitPos) || !ClaimManager.canBuild(sp, placePos)) {
               ClaimManager.warnClaimed(sp, "&cThis land is claimed - you can't place fluids here.");
               this.fortuneandfavors$denyUse(sp, hit, cir);
            }
         } else if (!ChestShopManager.isTradingShop(level, hitPos) && level.getBlockEntity(hitPos) instanceof Container
               && !ClaimManager.canOpenChest(sp, hitPos)) {
            // Non-shop chests, barrels, hoppers, etc. in claimed land. *Trading*
            // shops are exempt so customers can always browse the owner's shop
            // chest; a closed shop is deliberately not, since it is just a chest
            // again and must behave like one.
            ClaimManager.warnClaimed(sp, "&cThis land is claimed - you can't open containers here.");
            this.fortuneandfavors$denyUse(sp, hit, cir);
         } else if (
            // Generic right-click on a claimed block: doors, buttons, levers,
            // trapdoors, crafting tables, anvils, etc. The event layer checks
            // these too, but it can be skipped or swallow an exception (Safe
            // falls back to vanilla = allow), so this mixin is the fail-closed
            // backstop. Exemptions mirror the event layer: shop chests (customers
            // must be able to browse), mod machines (public utilities), and mod
            // items with their own right-click behavior.
            com.fortuneandfavors.ModItems.typeOf(stack) == null
               && !ChestShopManager.isTradingShop(level, hitPos)
               && !com.fortuneandfavors.economy.MachineManager.isAnyMachine(level, hitPos)
               && !ClaimManager.canBuild(sp, hitPos)
         ) {
            ClaimManager.warnClaimed(sp, "&cThis land is claimed - you can't use that here.");
            this.fortuneandfavors$denyUse(sp, hit, cir);
         }
      } catch (VirtualMachineError e) {
         throw e;
      } catch (Throwable t) {
         FortuneFavorsMod.LOGGER.error("Fortune & Favors: claim use check failed - denying the interaction", t);
         this.fortuneandfavors$denyUse(sp, hit, cir);
      }
   }

   /**
    * Refuses a right-click and tells the client the truth about it.
    *
    * <p>A refused interaction is not only a message: the client predicted the placement the
    * moment the button went down, so the block it drew at the face of what it clicked has to
    * be taken back off its screen, along with the item it expected to spend. Vanilla's own
    * refusals in the middle of the same call (a block protected by spawn protection, an item
    * this player cannot use) answer by sending nothing, because they decide <i>after</i> the
    * prediction has already been reconciled - this mixin decides in front of all of it.
    *
    * <p>Every refusal in {@code useItemOn} goes through here, and a refusal added later
    * should too.
    */
   private void fortuneandfavors$denyUse(ServerPlayer sp, BlockHitResult hit, CallbackInfoReturnable<InteractionResult> cir) {
      com.fortuneandfavors.util.ClientResync.refusedUse(sp, hit);
      cir.setReturnValue(InteractionResult.FAIL);
   }
}
