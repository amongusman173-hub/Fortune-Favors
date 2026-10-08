package com.fortuneandfavors.mixin;

import com.fortuneandfavors.economy.VanishManager;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The server's cached online-name list (used by /list and name tab-completion)
 *  no longer includes vanished players, so they don't show up in any online
 *  player name/count surface beyond their already-hidden tab entry. */
@Mixin(MinecraftServer.class)
public abstract class VanishServerNameMixin {
   @Inject(method = "getPlayerNames", at = @At("RETURN"), cancellable = true)
   private void fortuneandfavors$hideVanishedFromNames(CallbackInfoReturnable<String[]> cir) {
      String[] names = cir.getReturnValue();
      if (names == null || names.length == 0) {
         return;
      }
      MinecraftServer self = (MinecraftServer) (Object) this;
      PlayerList list = self.getPlayerList();
      List<String> out = new ArrayList<>();
      for (String name : names) {
         ServerPlayer p = list.getPlayerByName(name);
         if (p != null && VanishManager.isVanished(p)) {
            continue;
         }
         out.add(name);
      }
      cir.setReturnValue(out.toArray(new String[0]));
   }
}