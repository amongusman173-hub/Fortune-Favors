package com.fortuneandfavors.economy;

import com.fortuneandfavors.FortuneFavorsMod;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

public final class Advancements {
   /**
    * The advancement ids whose completion has already been reported as impossible.
    *
    * <p>The failure is a property of the advancement, not of the player - every grant of a
    * malformed one fails the same way - so the line is worth saying once, and saying it once per
    * player is a log full of the same sentence.
    */
   private static final java.util.Set<Identifier> UNCOMPLETABLE = java.util.concurrent.ConcurrentHashMap.newKeySet();

   private Advancements() {
   }

   /** Grants one of this mod's own advancements, named by its path under the mod's namespace. */
   public static boolean grant(ServerPlayer player, String path) {
      return award(player, FortuneFavorsMod.id(path));
   }

   /**
    * Grants an advancement named by a full id - the game's, or another datapack's.
    *
    * <p>The reworked End hands {@code minecraft:end/kill_dragon} ("Free the End") to everybody who
    * helped with the fight, and that id does not live under this mod's namespace, so it has to be
    * taken whole rather than prefixed. Everything else about the grant - the idempotence, the
    * toast, the fanfare - is the same ritual as the path form, which is why both walk through
    * {@link #award}.
    */
   public static boolean grant(ServerPlayer player, Identifier advancementId) {
      return award(player, advancementId);
   }

   /**
    * The one grant.
    *
    * <p>Returns whether this call is what actually completed the advancement, so a caller that
    * wants to say "this landed" can tell a fresh grant from a no-op. A null holder (the id is not
    * in the datapack) is reported rather than swallowed: "I killed it and got nothing" is exactly
    * this line silently returning, and silence is the one answer that cannot be debugged.
    */
   private static boolean award(ServerPlayer player, Identifier advancementId) {
      try {
         if (player == null || advancementId == null) {
            return false;
         }

         MinecraftServer server = player.level().getServer();
         if (server == null) {
            return false;
         }

         AdvancementHolder holder = server.getAdvancements().get(advancementId);
         if (holder == null) {
            FortuneFavorsMod.LOGGER.warn("Advancement {} is not in the datapack - it cannot be granted", advancementId);
            return false;
         }

         // One tracker, captured once and used for every step. The grant used to fetch
         // {@code player.getAdvancements()} afresh for the check and again for each criterion, and
         // then declare success no matter what the engine answered - so a grant that wrote its
         // criteria to one tracker and read completion back from another returned true while
         // recording nothing, which from the outside is exactly "I killed it and got nothing".
         PlayerAdvancements tracker = player.getAdvancements();
         if (tracker.getOrStartProgress(holder).isDone()) {
            return false;
         }

         for (String criterion : holder.value().criteria().keySet()) {
            tracker.award(holder, criterion);
         }

         // And the answer is the engine's, not ours: the advancement is granted only if it is
         // actually complete on the player afterwards. A criterion the engine refused (a mismatch
         // between the advancement's criteria and its requirements is the way this breaks) is now
         // a false return and a log line, rather than a fanfare over an empty toast.
         if (!tracker.getOrStartProgress(holder).isDone()) {
            if (UNCOMPLETABLE.add(advancementId)) {
               FortuneFavorsMod.LOGGER.warn(
                  "Advancement {} could not be completed - criteria {} offered, none accepted (the advancement's criteria and its requirements disagree)",
                  advancementId, holder.value().criteria().keySet()
               );
            }
            return false;
         }

         try {
            if (player.level() instanceof ServerLevel sl) {
               double px = player.getX();
               double py = player.getY() + 1.2;
               double pz = player.getZ();
               sl.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, px, py, pz, 24, 0.5, 0.8, 0.5, 0.15);
               sl.sendParticles(ParticleTypes.HAPPY_VILLAGER, px, py + 0.4, pz, 12, 0.4, 0.5, 0.4, 0.08);
               sl.sendParticles(ParticleTypes.ENCHANT, px, py + 0.5, pz, 18, 0.5, 0.6, 0.5, 0.1);
               sl.sendParticles(ParticleTypes.GLOW, px, py, pz, 10, 0.6, 0.6, 0.6, 0.04);
               sl.playSound(null, px, py, pz, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.PLAYERS, 1.0F, 1.2F);
               sl.playSound(null, px, py, pz, SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.5F, 1.6F);
            }
         } catch (Exception var11) {
         }
         return true;
      } catch (Exception var12) {
         return false;
      }
   }
}
