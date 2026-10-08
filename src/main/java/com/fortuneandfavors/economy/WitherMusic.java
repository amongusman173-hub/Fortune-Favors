package com.fortuneandfavors.economy;

import com.fortuneandfavors.ModSounds;
import com.fortuneandfavors.net.FfMusicPayload;
import com.fortuneandfavors.net.FfNet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.Holder;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundStopSoundPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

/**
 * The reworked Wither's soundtrack, from the server's side.
 *
 * <p>The theme is one track cut into three: the charge-up ({@link #INTRO_TICKS}), the body of the
 * fight ({@link #LOOP_TICKS}, looped) and the ceremony ({@link #DEATH_TICKS}, played once). The
 * server's whole job is to say which cut belongs to the fight right now; the fading, the loops and
 * the crossfades live on the client, because a {@code ClientboundSoundPacket} can neither fade nor
 * loop. The payload that carries the instruction is {@link FfMusicPayload}.
 *
 * <p>A client that cannot decode the payload - a vanilla one, a Bedrock one behind Geyser - is not
 * left in silence: it is served the same cut as a plain sound packet, and the loop is re-issued a
 * track-length at a time the way the dragon theme's repeat works. It hears the music without the
 * fades; everyone on the mod's own client hears it with them.
 *
 * <p>State is per fight (keyed by the Wither's UUID), because two Withers are two soundtracks, and
 * everything is cleared the moment the body it belongs to leaves the world.
 */
public final class WitherMusic {
   private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("fortuneandfavors-wither-music");

   private WitherMusic() {
   }

   /** The charge-up cut: 0:00-0:21 of the track. */
   public static final int INTRO_TICKS = 21 * 20;
   /** The body of the fight: 0:21-2:33, looped. */
   public static final int LOOP_TICKS = 132 * 20;
   /** The ceremony: 2:33 to the end, played once over the top of the loop. */
   public static final int DEATH_TICKS = 287;
   /** How far the music carries - the fight's own audience radius, not the whole dimension. */
   private static final double RANGE = 96.0;
   /**
    * How long a listener's music stays claimed beyond the cut they were served.
    *
    * <p>The claim is refreshed every tick of a live fight, so this only has to cover the gap
    * between two refreshes - plus, for a death, the tail of a cut that plays on after its fight
    * has finished. A couple of seconds is both.
    */
   private static final int HOLD_MARGIN = 40;

   private static final Map<UUID, Session> sessions = new HashMap<>();

   /** What one fight's music is doing: which cut, who has been handed it, and the fallback clock. */
   private static final class Session {
      int track;
      final Set<UUID> served = new HashSet<>();
      final Map<UUID, Long> nativeDue = new HashMap<>();
   }

   /** The track's length in ticks, which is also how often a native listener's loop repeats. */
   public static int lengthOf(int track) {
      return switch (track) {
         case FfMusicPayload.TRACK_INTRO -> INTRO_TICKS;
         case FfMusicPayload.TRACK_DEATH -> DEATH_TICKS;
         default -> LOOP_TICKS;
      };
   }

   /** The sound event behind a cut - the same three events the client's player plays. */
   public static SoundEvent soundFor(int track) {
      return switch (track) {
         case FfMusicPayload.TRACK_INTRO -> ModSounds.WITHERED_INTRO;
         case FfMusicPayload.TRACK_DEATH -> ModSounds.WITHERED_DEATH;
         default -> ModSounds.WITHERED_LOOP;
      };
   }

   /** Which cut this fight is on, or 0 when it has none. Read by a command or a check. */
   public static int currentTrack(UUID fightId) {
      Session s = sessions.get(fightId);
      return s == null ? 0 : s.track;
   }

   /**
    * Switch a fight to a cut.
    *
    * <p>A track change is a crossfade, not a cut: modded clients are simply told the new cut and
    * overlap it over the old one themselves, while a native listener's old copy is stopped before
    * the new one starts (a sound packet cannot overlap, so it gets the cleanest seam it can have).
    */
   public static void start(ServerLevel level, UUID fightId, Vec3 pos, int track) {
      Session s = sessions.computeIfAbsent(fightId, k -> new Session());
      if (s.track == track) {
         return;
      }
      if (s.track != 0) {
         for (UUID id : s.nativeDue.keySet()) {
            ServerPlayer p = playerOf(level, id);
            if (p != null) {
               stopNative(p, s.track);
            }
         }
      }
      s.track = track;
      s.nativeDue.clear();
      s.served.clear();
      int served = tick(level, fightId, pos);
      LOGGER.info("Wither music: {} -> cut {} ({} listener(s) in range)", fightId, track, served);
   }

   /**
    * Per-tick: hand the current cut to anyone in earshot who has not got it, keep the native
    * copies of the loop coming round, and take the music off anyone who has left the fight.
    */
   public static int tick(ServerLevel level, UUID fightId, Vec3 pos) {
      Session s = sessions.get(fightId);
      if (s == null || s.track == 0) {
         return 0;
      }
      long now = level.getGameTime();
      Set<UUID> still = new HashSet<>();
      int served = 0;

      for (ServerPlayer p : playersNear(level, pos)) {
         still.add(p.getUUID());
         // The theme claims this listener's music before the cut is served to them, so the fight is
         // heard over silence rather than over a record or the backpack's boombox - and the claim
         // is refreshed on every tick of the fight, so a loop that runs for minutes never lapses.
         // Ordering it before the serve is what keeps the claim from silencing its own theme.
         BossMusic.takeOver(p, lengthOf(s.track) + HOLD_MARGIN);
         if (!s.served.contains(p.getUUID())) {
            serve(p, s, now);
            served++;
            continue;
         }
         Long due = s.nativeDue.get(p.getUUID());
         if (due != null && now >= due) {
            nativePlay(p, s.track);
            s.nativeDue.put(p.getUUID(), now + lengthOf(s.track));
         }
      }

      for (UUID id : new ArrayList<>(s.served)) {
         if (still.contains(id)) {
            continue;
         }
         s.served.remove(id);
         s.nativeDue.remove(id);
         ServerPlayer p = playerOf(level, id);
         if (p != null) {
            FfNet.send(p, FfMusicPayload.stop());
            stopNative(p, s.track);
            // They are out of earshot, so the fight no longer owns their music.
            BossMusic.release(p);
         }
      }
      return served;
   }

   /**
    * End a fight's music: the loop or the ceremony fades out on the client, and any native copy is
    * stopped outright. Called by every exit - a finished death, a body removed by a command, a
    * teardown - so no soundtracks outlive the fights they belong to.
    */
   public static void stop(MinecraftServer server, UUID fightId) {
      Session s = sessions.remove(fightId);
      if (s == null || s.track == 0 || server == null) {
         return;
      }
      for (ServerPlayer p : server.getPlayerList().getPlayers()) {
         if (!s.served.contains(p.getUUID())) {
            continue;
         }
         FfNet.send(p, FfMusicPayload.stop());
         stopNative(p, s.track);
         // The soundtrack is being taken off them, so its claim on their music goes with it.
         BossMusic.release(p);
      }
   }

   /**
    * Let a fight's music end on its own.
    *
    * <p>Used by a real death: the ceremony cut is played once and runs to its own end, so stopping
    * it here would cut the death theme off halfway through the animation it is scoring. The session
    * is dropped without a packet; the client's copy fades out when the cut runs out.
    */
   public static void forget(UUID fightId) {
      sessions.remove(fightId);
      // No release here, on purpose: the death cut is still playing, and its claim on the listener's
      // music is held for the length of that cut (see HOLD_MARGIN). Dropping it now would let a
      // boombox start up under the ceremony - and the claim the tick refresh left behind is
      // already timed to run out exactly when the music does.
   }

   /** Server stopping or the rework being switched off: drop every soundtrack with no packets. */
   public static void clearAll() {
      sessions.clear();
      // These soundtracks are gone but their claims are not: cap every one of them at a moment
      // rather than at the length of a cut that will never be heard, so nobody is left hushed by
      // music that has stopped.
      BossMusic.fadeOutAll();
   }

   private static void serve(ServerPlayer p, Session s, long now) {
      s.served.add(p.getUUID());
      boolean sent = FfNet.send(p, FfMusicPayload.play(s.track, lengthOf(s.track)));
      if (!sent) {
         // No Fortune & Favors channel on that client: give it the cut as a real sound instead.
         LOGGER.info("Wither music: {} has no music channel - serving cut {} as a plain sound", p.getName().getString(), s.track);
         nativePlay(p, s.track);
         if (s.track == FfMusicPayload.TRACK_LOOP) {
            s.nativeDue.put(p.getUUID(), now + lengthOf(s.track));
         }
      }
   }

   /**
    * Plays one cut to a client that cannot take the payload - no fade, but no silence either.
    *
    * <p>{@link SoundSource#MUSIC}, matching the modded client's own player: the theme is music, so
    * it answers to the music slider rather than the jukebox one, and both halves of the feature sit
    * on the same channel (a fallback on one slider and a packet on another would mean two players
    * hearing two different mixes of the same fight).
    */
   private static void nativePlay(ServerPlayer p, int track) {
      try {
         long seed = p.level().getRandom().nextLong();
         p.connection.send(new ClientboundSoundPacket(
            // Through the stand-in table: a Bedrock client is handed a vanilla disc name the Bedrock
            // half of the pack redefines, because Geyser translates vanilla ids only and would drop
            // ours. See BedrockMusic - and the stop below has to name the same event.
            Holder.direct(BedrockMusic.forListener(p, soundFor(track))), SoundSource.MUSIC, p.getX(), p.getY(), p.getZ(), 1.0F, 1.0F, seed
         ));
      } catch (Throwable t) {
         LOGGER.warn("Wither music: could not play the native fallback for {}", p.getName().getString(), t);
      }
   }

   private static void stopNative(ServerPlayer p, int track) {
      try {
         // The same answer nativePlay gave this listener: a stop names the event it is stopping, so
         // asking again with the mod's own id would stop a sound a Bedrock client was never sent.
         p.connection.send(new ClientboundStopSoundPacket(BedrockMusic.forListener(p, soundFor(track)).location(), SoundSource.MUSIC));
      } catch (Throwable ignored) {
      }
   }

   private static List<ServerPlayer> playersNear(ServerLevel level, Vec3 pos) {
      double r2 = RANGE * RANGE;
      List<ServerPlayer> out = new ArrayList<>();
      for (ServerPlayer p : level.getPlayers(pl -> pl.isAlive() && !pl.isSpectator() && pl.distanceToSqr(pos.x, pos.y, pos.z) <= r2)) {
         out.add(p);
      }
      return out;
   }

   private static ServerPlayer playerOf(ServerLevel level, UUID id) {
      MinecraftServer server = level == null ? null : level.getServer();
      return server == null ? null : server.getPlayerList().getPlayer(id);
   }
}
