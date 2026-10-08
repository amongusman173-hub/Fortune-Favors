package com.fortuneandfavors.mixin;

import com.fortuneandfavors.FortuneFavorsMod;
import com.fortuneandfavors.ModItems;
import com.fortuneandfavors.economy.ShopProgression;
import com.fortuneandfavors.economy.TokenManager;
import com.fortuneandfavors.economy.VanishManager;
import com.fortuneandfavors.util.Chat;
import com.fortuneandfavors.util.Safe;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ItemEntity.class)
public abstract class ItemEntityMixin {
   @Unique
   private boolean fortuneandfavors$protected = false;

   @Inject(method = "tick", at = @At("HEAD"))
   private void fortuneandfavors$noDespawn(CallbackInfo ci) {
      try {
         if (this.fortuneandfavors$protected) {
            return;
         }

         ItemEntity self = ((ItemEntity)(Object)this);
         ItemStack stack = self.getItem();
         if (ModItems.isMachineItem(stack) || ModItems.isToken(stack) || ModItems.isSpawnerItem(stack)) {
            self.setUnlimitedLifetime();
            this.fortuneandfavors$protected = true;
         }
      } catch (VirtualMachineError e) {
         throw e;
      } catch (Throwable t) {
         FortuneFavorsMod.LOGGER.error("Fortune & Favors: item-entity tick guard failed", t);
      }
   }

   @Inject(method = "fireImmune", at = @At("HEAD"), cancellable = true)
   private void fortuneandfavors$noBurn(CallbackInfoReturnable<Boolean> cir) {
      try {
         ItemEntity self = ((ItemEntity)(Object)this);
         ItemStack stack = self.getItem();
         if (ModItems.isMachineItem(stack) || ModItems.isToken(stack) || ModItems.isSpawnerItem(stack)) {
            cir.setReturnValue(true);
         }
      } catch (VirtualMachineError e) {
         throw e;
      } catch (Throwable t) {
         FortuneFavorsMod.LOGGER.error("Fortune & Favors: item-entity fire guard failed", t);
      }
   }

   @Inject(method = "playerTouch", at = @At("HEAD"), cancellable = true)
   private void fortuneandfavors$protectPickup(Player player, CallbackInfo ci) {
      try {
         ItemEntity self = ((ItemEntity)(Object)this);
         ItemStack stack = self.getItem();
         if (!(player instanceof ServerPlayer sp)) {
            return;
         }

         // A vanished body does not pick things up. The drop stays where it fell rather than
         // hopping into an invisible pocket, which is both what "vanish" has to mean and the only
         // version of it that leaves no trail: an item that flies to a spot nobody is standing at
         // is a position report drawn in items.
         if (VanishManager.isHiddenBody(sp)) {
            ci.cancel();
            return;
         }

         // And a puppet body picks nothing up either. The mind-controlled copies and the
         // Illusioner's decoys are fake ServerPlayers, and a ServerPlayer collects anything it
         // walks over - which meant a body that exists to be fought was quietly hoovering up the
         // loot of the fight it was in, and walking off with it. It carries a costume and nothing
         // else; see BossManager.isFakePlayer and the sweep that retires it.
         if (com.fortuneandfavors.economy.BossManager.isFakePlayer(sp)) {
            ci.cancel();
            return;
         }

         Safe.run("shop discovery pickup", () -> ShopProgression.trackItemDiscovery(sp, stack));
         if (player.getAbilities().instabuild) {
            return;
         }

         if (ModItems.isToken(stack)) {
            UUID creator = TokenManager.creatorUuid(stack);
            if (creator != null && !creator.equals(sp.getUUID())) {
               Chat.msg(sp, "&7That's " + TokenManager.creatorName(stack) + "'s token - only they can pick it up.");
               ci.cancel();
            }

            return;
         }

         if (ModItems.isSpawnerItem(stack)) {
            UUID owner = ModItems.spawnerOwner(stack);
            if (owner == null) {
               if (sp.getInventory().getFreeSlot() >= 0) {
                  ModItems.bindSpawner(stack, sp.getUUID(), sp.getName().getString());
               }

               return;
            }

            if (!owner.equals(sp.getUUID())) {
               Chat.msg(sp, "&7That spawner is bound to " + ModItems.spawnerOwnerName(stack) + " - only they can pick it up.");
               ci.cancel();
            }
         }
      } catch (VirtualMachineError e) {
         throw e;
      } catch (Throwable t) {
         FortuneFavorsMod.LOGGER.error("Fortune & Favors: item-entity pickup guard failed", t);
      }
   }
}
