package com.fortuneandfavors.mixin;

import com.fortuneandfavors.economy.VanishManager;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.selector.EntitySelector;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Makes vanished players invisible to every player-targeting and lookup
 *  system that goes through the entity selector: name arguments (@e
 *  / @p / @a / @s), /tp, /execute, all EntityArgument.player()-based commands
 *  (/duel, /balance, /bounty, /trade, /guild invite, ...) and
 *  boss/raid target resolution.
 *
 *  Rule: a vanished player is only selectable by the command's own executor
 *  (so their own commands still work, and staff can't be spoof-targeted). */
@Mixin(EntitySelector.class)
public abstract class EntitySelectorMixin {
   private static final SimpleCommandExceptionType HIDDEN_PLAYER = new SimpleCommandExceptionType(Component.literal("Unknown player"));

   @Inject(method = "findPlayers", at = @At("RETURN"), cancellable = true)
   private void fortuneandfavors$hideVanishedFromPlayers(CommandSourceStack source, CallbackInfoReturnable<List<ServerPlayer>> cir) {
      List<ServerPlayer> list = cir.getReturnValue();
      if (list == null || list.isEmpty()) {
         return;
      }
      ServerPlayer self = source.getPlayer();
      List<ServerPlayer> filtered = new ArrayList<>();
      for (ServerPlayer p : list) {
         if (VanishManager.isVanished(p) && p != self) {
            continue;
         }
         filtered.add(p);
      }
      cir.setReturnValue(filtered);
   }

   @Inject(method = "findEntities", at = @At("RETURN"), cancellable = true)
   private void fortuneandfavors$hideVanishedFromEntities(CommandSourceStack source, CallbackInfoReturnable<List<? extends Entity>> cir) {
      List<? extends Entity> list = cir.getReturnValue();
      if (list == null || list.isEmpty()) {
         return;
      }
      ServerPlayer self = source.getPlayer();
      List<Entity> filtered = new ArrayList<>();
      for (Entity e : list) {
         if (e instanceof ServerPlayer p && VanishManager.isVanished(p) && p != self) {
            continue;
         }
         filtered.add(e);
      }
      cir.setReturnValue(filtered);
   }

   @Inject(method = "findSinglePlayer", at = @At("RETURN"), cancellable = true)
   private void fortuneandfavors$hideVanishedSingle(CommandSourceStack source, CallbackInfoReturnable<ServerPlayer> cir) throws CommandSyntaxException {
      ServerPlayer p = cir.getReturnValue();
      if (p == null) {
         return;
      }
      ServerPlayer self = source.getPlayer();
      if (VanishManager.isVanished(p) && p != self) {
         throw HIDDEN_PLAYER.create();
      }
   }
}