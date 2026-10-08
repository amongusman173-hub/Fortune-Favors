package com.fortuneandfavors.mixin;

import com.fortuneandfavors.economy.BossManager;
import com.fortuneandfavors.economy.TagManager;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayer.class)
public abstract class ServerPlayerTabListMixin {
   private static final Identifier DUEL_ARENA = Identifier.fromNamespaceAndPath("fortuneandfavors", "duel_arena");
   private static final Identifier PRISON = Identifier.fromNamespaceAndPath("fortuneandfavors", "prison");
   private static final Identifier EXPEDITION = Identifier.fromNamespaceAndPath("fortuneandfavors", "expedition");

   @Inject(method = "getTabListDisplayName", at = @At("RETURN"), cancellable = true)
   private void fortuneandfavors$appendTag(CallbackInfoReturnable<Component> cir) {
      ServerPlayer self = ((ServerPlayer)(Object)this);
      if (com.fortuneandfavors.economy.VanishManager.isVanished(self.getUUID())) {
         cir.setReturnValue(null);
         return;
      }
      Component original = (Component)cir.getReturnValue();
      if (original == null) {
         original = Component.literal(self.getName().getString());
      }

      Component result = original;
      if (com.fortuneandfavors.economy.AfkManager.isAfk(self.getUUID())) {
         result = Component.literal("§7[AFK] §r").append(result);
      }
      // Which site this body is down, if any. A run owns the body rather than the level, so this
      // is read from the run: an explorer mid-extraction is a tick away from the overworld and
      // one on the escape pad is already on their way home, and neither should blink out of the
      // tab list while they are still down there.
      String expeditionSite = com.fortuneandfavors.economy.ExpeditionManager.activeExpeditionName(self.getUUID());
      if (self.level() != null && self.level().dimension() != null) {
         Identifier dim = self.level().dimension().identifier();
         Component dimPrefix = null;
         if (dim.equals(DUEL_ARENA)) {
            dimPrefix = Component.literal("⚔ ").setStyle(Style.EMPTY.withColor(5635925));
         } else if (dim.equals(PRISON)) {
            dimPrefix = Component.literal("§d[Prison] §f").setStyle(Style.EMPTY.withColor(16733525));
         } else if (dim.equals(EXPEDITION) || expeditionSite != null) {
            // The site's own name while a run is live - "[Deep Mine]" rather than a flat
            // "[Expedition]" - and the plain word for anybody in the realm without one.
            dimPrefix = Component.literal(com.fortuneandfavors.economy.ExpeditionManager.tabMarker(expeditionSite, true))
               .setStyle(Style.EMPTY.withColor(5635925));
         } else if (dim.equals(Level.NETHER.identifier())) {
            dimPrefix = Component.literal("● ").setStyle(Style.EMPTY.withColor(16733525));
         } else if (dim.equals(Level.END.identifier())) {
            dimPrefix = Component.literal("● ").setStyle(Style.EMPTY.withColor(11158783));
         }

         if (dimPrefix != null) {
            result = dimPrefix.copy().append(result);
         }
      }

      result = TagManager.appendTag(result, self);
      int corr = BossManager.corruptionOf(self.getUUID());
      if (corr >= 1) {
         Component marker = corr >= 3
            ? Component.literal(" Ⅲ").setStyle(Style.EMPTY.withColor(16733525))
            : Component.literal(corr == 1 ? " Ⅰ" : " Ⅱ").setStyle(Style.EMPTY.withColor(11141290));
         result = result.copy().append(marker);
      }

      if (result != original) {
         cir.setReturnValue(result);
      }
   }
}
