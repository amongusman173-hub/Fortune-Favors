package com.fortuneandfavors.economy;

import com.fortuneandfavors.net.FfCreditsPayload;
import com.fortuneandfavors.net.FfNet;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.Holder;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundStopSoundPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

/**
 * The music after the dragon: the game's own credits track, played for a while and then let go.
 *
 * <p>The fight has a theme of its own and the theme stops with the fight - which is correct, and
 * which is also the reason the room used to be left in silence on the one moment in the End that
 * has an ending. What happens now is the order the game itself uses: the boss music goes, the
 * dragon dies, and the credits come up over the corpse.
 *
 * <p>Not the whole ten minutes, because this is a corpse and not a title screen: {@link #PLAY_TICKS}
 * of the track, then {@link #FADE_TICKS} of it going away. The fade is the whole reason this is a
 * payload rather than one sound packet - see {@link FfCreditsPayload} - and a client that cannot
 * take the payload is served the same track as a plain sound and stopped at the same moment, which
 * is a cut instead of a fade and infinitely better than nothing.
 *
 * <p>State is a single session rather than one per dragon, because there is exactly one of these at
 * a time: the credits belong to the end of the pile of a fight, not to a body, and a second dragon
 * dying on top of a running credits theme is a death that happened while the room was still reading
 * the last one.
 */
public final class EnderCreditsMusic {
   private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("fortuneandfavors-end-credits");

   private EnderCreditsMusic() {
   }

   /** How much of the credits track a death is worth. Two and a half minutes of it. */
   public static final int PLAY_TICKS = 150 * 20;
   /** And how long it takes to go away, so the room is not dropped out of a song. */
   public static final int FADE_TICKS = 80;
   /** How far the music carries - the whole End, because the whole End fought it. */
   private static final double RANGE = 160.0;
   /**
    * How long a listener's music stays claimed past the last tick of the track.
    *
    * <p>The claim is what keeps the mod's own music systems quiet under the credits; it has to
    * outlive the music by the length of the fade, or a backpack jukebox would start up in the last
    * two seconds of the credits.
    */
   private static final int HOLD_MARGIN = 60;

   /** True while a credits track is running. */
   private static boolean playing = false;
   /** Ticks left before the credits are handed back. */
   private static int ticksLeft = 0;
   /** Ticks left before a native listener's copy has to be stopped, per player. */
   private static final java.util.Map<UUID, Integer> NATIVE_DUE = new java.util.HashMap<>();
   /** Players already served this session. */
   private static final Set<UUID> SERVED = new HashSet<>();

   /** Is a credits track running? Read by a command or a check. */
   public static boolean playing() {
      return playing;
   }

   /** The length of a credits session, in ticks - the hold plus the fade. */
   public static int lengthTicks() {
      return PLAY_TICKS + FADE_TICKS;
   }

   /**
    * The dragon is dead: bring up the credits.
    *
    * <p>Idempotent, because two things notice a dragon's death - the reworked ceremony's own kill
    * and the server's ordinary death event - and a second call must not restart the track.
    */
   public static void play(ServerLevel end) {
      if (end == null || playing) {
         return;
      }
      playing = true;
      ticksLeft = lengthTicks() + 40;
      SERVED.clear();
      NATIVE_DUE.clear();
      serve(end);
      LOGGER.info("Ender credits: rolling {}s of the credits over the dragon's death", PLAY_TICKS / 20);
   }

   /**
    * Per-tick: hand the track to anyone in the End who has not got it, keep the claims alive, and
    * stop a native listener's copy when its length is up.
    */
   public static void tick(ServerLevel end) {
      if (!playing || end == null) {
         return;
      }
      serve(end);
      for (UUID id : new java.util.ArrayList<>(NATIVE_DUE.keySet())) {
         int left = NATIVE_DUE.get(id) - 1;
         if (left > 0) {
            NATIVE_DUE.put(id, left);
            continue;
         }
         NATIVE_DUE.remove(id);
         ServerPlayer p = playerOf(end, id);
         if (p != null) {
            stopNative(p);
         }
      }
      if (--ticksLeft <= 0) {
         playing = false;
         ticksLeft = 0;
         release(end);
         LOGGER.info("Ender credits: done - the music is the players' again");
      }
   }

   /** Take the credits away early - a teardown, a fight reset. */
   public static void stop(ServerLevel end) {
      if (!playing) {
         return;
      }
      playing = false;
      ticksLeft = 0;
      if (end != null) {
         for (UUID id : new HashSet<>(SERVED)) {
            ServerPlayer p = playerOf(end, id);
            if (p == null) {
               continue;
            }
            FfNet.send(p, FfCreditsPayload.stop());
            stopNative(p);
         }
      }
      SERVED.clear();
      NATIVE_DUE.clear();
      if (end != null) {
         release(end);
      }
   }

   /** Hand out the track, once per player per session. */
   private static int serve(ServerLevel end) {
      int served = 0;
      Vec3 pos = new Vec3(0.5, 64.0, 0.5);
      for (ServerPlayer p : end.getPlayers(pl -> pl.isAlive() && pl.distanceToSqr(pos.x, pos.y, pos.z) <= RANGE * RANGE)) {
         if (SERVED.contains(p.getUUID())) {
            continue;
         }
         SERVED.add(p.getUUID());
         served++;
         // The claim goes in first, so the credits are heard over silence rather than over the
         // game's own soundtrack starting up under them - and it is held past the end of the fade.
         BossMusic.takeOver(p, lengthTicks() + HOLD_MARGIN);
         boolean sent = FfNet.send(p, FfCreditsPayload.play(PLAY_TICKS, FADE_TICKS));
         if (!sent) {
            nativePlay(p);
            NATIVE_DUE.put(p.getUUID(), PLAY_TICKS);
         }
      }
      return served;
   }

   /** Hand the music back and let the mod's own systems carry on. */
   private static void release(ServerLevel end) {
      MinecraftServer server = end == null ? null : end.getServer();
      if (server == null) {
         return;
      }
      for (UUID id : new HashSet<>(SERVED)) {
         ServerPlayer p = server.getPlayerList().getPlayer(id);
         if (p != null) {
            BossMusic.release(p);
         }
      }
      SERVED.clear();
      NATIVE_DUE.clear();
   }

   /**
    * The fallback: the credits track as a real sound, positioned on the listener.
    *
    * <p>{@link SoundSource#MUSIC}, matching the modded client's own copy, so both halves of this
    * feature sit on the same slider. No fade - a sound packet has none - which is exactly why the
    * payload exists.
    */
   private static void nativePlay(ServerPlayer p) {
      try {
         long seed = p.level().getRandom().nextLong();
         p.connection.send(new ClientboundSoundPacket(
            Holder.direct(SoundEvents.MUSIC_CREDITS.value()), SoundSource.MUSIC,
            p.getX(), p.getY(), p.getZ(), 1.0F, 1.0F, seed
         ));
      } catch (Throwable t) {
         LOGGER.warn("Ender credits: could not serve the native fallback to {}", p.getName().getString(), t);
      }
   }

   private static void stopNative(ServerPlayer p) {
      try {
         p.connection.send(new ClientboundStopSoundPacket(SoundEvents.MUSIC_CREDITS.value().location(), SoundSource.MUSIC));
      } catch (Throwable ignored) {
      }
   }

   private static ServerPlayer playerOf(ServerLevel end, UUID id) {
      MinecraftServer server = end == null ? null : end.getServer();
      return server == null ? null : server.getPlayerList().getPlayer(id);
   }
}
