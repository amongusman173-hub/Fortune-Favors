package com.fortuneandfavors.net;

import com.fortuneandfavors.FortuneFavorsMod;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

/** Guarded server -> client sends.
 *
 *  <p>Fortune &amp; Favors is a server-side mod and promises that clients which
 *  never installed it can still join. Every payload the server pushes - the
 *  config snapshot, screen FX, the boombox song - therefore has to be checked
 *  against the channels the connecting client actually declared. Sending one to
 *  a client that cannot decode it is what a player experiences as a
 *  <em>"Network Protocol Error"</em>, so the check lives in one place and every
 *  send goes through it.
 */
public final class FfNet {
   /** Bumped whenever a payload's shape changes. The server stamps this onto
    *  every config snapshot so a client/server build mismatch is reported as
    *  what it is instead of surfacing as a decoder error mid-session. */
   public static final int PROTOCOL = 1;

   /** Payload id used for the version stamp on the config snapshot. */
   public static final String KEY_PROTOCOL = "__protocol";
   public static final String KEY_VERSION = "__version";

   /** Block-registry fingerprint stamps. These catch the failure the version
    *  stamp cannot: a client whose *other* mods register different blocks. Ids
    *  then shift and chunk packets fail to decode, which shows up in game as
    *  chunks that never load and blocks that are missing. */
   public static final String KEY_BLOCK_STATES = "__block_states";
   public static final String KEY_BLOCK_HASH = "__block_hash";
   public static final String KEY_BLOCK_COUNT = "__block_count";

   private static final Set<UUID> warned = ConcurrentHashMap.newKeySet();

   private FfNet() {
   }

   /** Sends a payload only when the connected client declared it can receive it,
    *  and never lets a connection problem escape into a fight, a tick or the
    *  join path. */
   public static boolean send(ServerPlayer player, CustomPacketPayload payload) {
      if (player == null || payload == null) {
         return false;
      }
      try {
         if (!ServerPlayNetworking.canSend(player, payload.type())) {
            if (warned.add(player.getUUID())) {
               FortuneFavorsMod.LOGGER.info(
                  "Skipping {} for {} - their client has not registered the Fortune & Favors channel (sent nothing rather than an undecodable packet)",
                  payload.type(), player.getName().getString());
            }
            return false;
         }
         ServerPlayNetworking.send(player, payload);
         return true;
      } catch (Exception e) {
         FortuneFavorsMod.LOGGER.warn("Could not send {} to {}", payload.type(), player.getName().getString(), e);
         return false;
      }
   }

   /** Forgets the once-per-player "client has no channel" note, so a client that
    *  later installs the mod is reported again if it still cannot receive. */
   public static void forget(UUID player) {
      if (player != null) {
         warned.remove(player);
      }
   }
}
