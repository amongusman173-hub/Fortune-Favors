package com.fortuneandfavors.mixin;

import com.fortuneandfavors.economy.BossManager;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
public class LauncherClickMixin {
   @Shadow
   public ServerPlayer player;

   @Inject(method = "handlePlayerAction", at = @At("HEAD"), cancellable = true)
   private void ffLauncherLeftClick(ServerboundPlayerActionPacket packet, CallbackInfo ci) {
      if (packet.getAction() == Action.START_DESTROY_BLOCK) {
         // The shared clock rather than the level's own: BossManager reads this stamp back
         // against the same clock, and a realm's game time does not advance (see ServerClock).
         BossManager.registerTakeoverClick(
            this.player, com.fortuneandfavors.economy.ServerClock.clock(this.player.level())
         );
         if (BossManager.tryLauncherLeftClick(this.player)) {
            ci.cancel();
         }
      }
   }
}
