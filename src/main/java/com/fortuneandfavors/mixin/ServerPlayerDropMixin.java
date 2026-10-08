package com.fortuneandfavors.mixin;

import com.fortuneandfavors.ModItems;
import com.fortuneandfavors.NiceKeepInventoryManager;
import com.fortuneandfavors.duel.DuelManager;
import com.fortuneandfavors.economy.AdvancedEnchantments;
import com.fortuneandfavors.economy.BossManager;
import com.fortuneandfavors.economy.ClaimManager;
import com.fortuneandfavors.economy.ModConfig;
import com.fortuneandfavors.economy.ReactiveZombie;
import com.fortuneandfavors.economy.ScarletGear;
import com.fortuneandfavors.util.Chat;
import com.fortuneandfavors.util.LastInventoryHolder;
import com.fortuneandfavors.util.NkiSnapshotHolder;
import com.fortuneandfavors.util.Safe;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayer.class)
public abstract class ServerPlayerDropMixin {
   private static boolean fortuneandfavors$suppressingDrop = false;
   private static final Map<UUID, Long> lastWarn = new HashMap<>();

   @Inject(method = "drop", at = @At("HEAD"), cancellable = true)
   private void fortuneandfavors$checkClaimDrop(ItemStack stack, boolean throwRandomly, boolean retainOwnership, CallbackInfoReturnable<ItemEntity> cir) {
      Safe.run(
         "claim drop check",
         () -> {
            ServerPlayer self = ((ServerPlayer)(Object)this);
            if (DuelManager.shouldBlockRandomizerDrop(self, stack)) {
               long now2 = self.level().getGameTime();
               Long last2 = lastWarn.get(self.getUUID());
               if (last2 == null || now2 - last2 > 60L) {
                  lastWarn.put(self.getUUID(), now2);
                  Chat.msg(self, "&dYou can't drop your weapon in a Randomizer Duel!");
               }

               cir.setReturnValue(null);
            } else if (!self.isAlive()) {
               // Snapshot the kit for a blood servant BEFORE anything else runs.
               // Vanilla clears each slot the instant it has called drop() for it,
               // and this hook fires on the first stack of the death drop, so this
               // is the only moment the whole inventory still exists. The servant
               // wears copies of what is captured here and never touches the real
               // stacks, so the capture is cosmetic and side-effect free.
               BossManager.captureRevenantLook(self);
               // And the world's own reflex takes its kit here too, for exactly the same reason:
               // this is the only moment the player's whole inventory still exists. A player who
               // dies to a zombie rises again in the hands of one, wearing what they had on.
               ReactiveZombie.capture(self);
               // And the same moment is the only correct time to record the player's
               // last-known inventory for /ff restore. Recording it in the death
               // handler instead (which runs after every slot has been emptied) meant
               // the saved state was always the state AFTER the loss, so the command
               // had nothing real to give back.
               LastInventoryHolder.snapshot(self);
               boolean nkiActive = NiceKeepInventoryManager.isEnabled()
                  && !DuelManager.isInDuel(self.getUUID())
                  && !DuelManager.isDuelRealm(self.level());
               if (AdvancedEnchantments.isSoulbound(stack) && !nkiActive) {
                  AdvancedEnchantments.captureSoulbound(self, stack);
                  cir.setReturnValue(null);
                  return;
               }
               if (BossManager.wardenFeastingNear(self)) {
                  boolean nki = NiceKeepInventoryManager.isEnabled() && !DuelManager.isInDuel(self.getUUID()) && !DuelManager.isDuelRealm(self.level());
                  boolean skip = nki ? NiceKeepInventoryManager.isImportantItem(stack) : NiceKeepInventoryManager.isArmorItem(stack);
                  if (!skip) {
                     // The Warden still grows from what it "devours", but it devours
                     // a COPY: the stack is counted and then falls as it normally
                     // would. Suppressing the drop here is what made it a theft, and
                     // the Sculk Orb that gave the item back is gone with it -
                     // there is nothing to give back any more.
                     BossManager.countWardenFeastCopy(self, stack);
                  }
               }

               if (BossManager.ritualHoldsDrops(self) || ScarletGear.holdsSafeDeath(self) || BossManager.corruptionHoldsDrops(self)) {
                  cir.setReturnValue(null);
               } else if (DuelManager.suppressBedwarsDeathDrop(self, stack)) {
                  cir.setReturnValue(null);
               } else {
                  if (NiceKeepInventoryManager.isEnabled()
                     && !DuelManager.isInDuel(self.getUUID())
                     && !DuelManager.isDuelRealm(self.level())
                     && !NkiSnapshotHolder.isFor(self.getUUID())) {
                     List<ItemStack> snap = new ArrayList<>();

                     for (int i = 0; i < self.getInventory().getContainerSize(); i++) {
                        snap.add(self.getInventory().getItem(i).copy());
                     }

                     NkiSnapshotHolder.set(self.getUUID(), snap);
                     BossManager.scrubWardenHoldFromSnapshot();
                  }

                  if (NkiSnapshotHolder.isFor(self.getUUID())) {
                     cir.setReturnValue(null);
                  } else {
                     if (ModItems.isSpawnerItem(stack) && ModConfig.dropSpawnersOnDeath()) {
                        ModItems.unbindSpawner(stack);
                     }
                  }
               }
            } else if (!fortuneandfavors$suppressingDrop) {
               if (ClaimManager.canDrop(self, self.blockPosition())) {
                  if (ModItems.isSpawnerItem(stack)) {
                     ModItems.unbindSpawner(stack);
                  }
               } else {
                  fortuneandfavors$suppressingDrop = true;

                  try {
                     self.getInventory().placeItemBackInInventory(stack);
                  } finally {
                     fortuneandfavors$suppressingDrop = false;
                  }

                  long now = self.level().getGameTime();
                  Long last = lastWarn.get(self.getUUID());
                  if (last == null || now - last > 60L) {
                     lastWarn.put(self.getUUID(), now);
                     Chat.msg(self, "&cYou can't drop items in this claimed area.");
                  }

                  cir.setReturnValue(null);
               }
            }
         }
      );
   }
}
