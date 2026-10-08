package com.fortuneandfavors.economy;

import com.fortuneandfavors.net.FfEndIntroPayload;
import com.fortuneandfavors.net.FfNet;
import com.fortuneandfavors.util.JsonUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.Holder;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundStopSoundPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

/**
 * The End's opening theme: the track a body hears the first time it steps through the portal, and the
 * moment it is taken away.
 *
 * <p>The island is the one place in the mod that has a soundtrack waiting for it and nothing to play
 * on the way in. The dragon's theme belongs to the fight and the credits belong to the corpse, so
 * everything before the rift is silence - which is fine the twentieth time and a wasted moment the
 * first. This is that moment: a track that starts when a body that has never been here arrives, plays
 * once, never loops, and <b>fades out the instant the dragon is on the field</b>, so the fight's own
 * theme comes in over a seam rather than a hole.
 *
 * <p>Three facts decide everything here and each of them is somewhere: the <i>track</i> is a file in
 * the art pack ({@code ModSounds.ARIA_MATH_EPIC}), the <i>fade</i> is the client's
 * ({@code client.EndIntroMusic} over {@code client.OneShotTrack} - a sound packet can neither hold nor
 * fade), and the <i>first time</i> is a per-player ledger written beside the mod's other data. Per
 * player rather than per server: on a server with four friends, three of them have never been to the
 * End either, and a cue that only the first one ever hears is not a cue.
 *
 * <p>One session at a time, like the credits: the track is a length, not a state, so everybody who
 * arrives while it is playing joins the same performance (late arrivals hear it from wherever it has
 * got to, which for a fifty-second track is the honest thing rather than a restart).
 *
 * <p>It never claims the room's music back. The claim it took is left to expire on its own, because by
 * the time this track is over the dragon's theme has usually taken the same claim over and extended it
 * - and a release here would hand a fight's music back to the backpack boombox mid-fight, which is
 * exactly the bug {@code BossMusic} exists to prevent.
 */
public final class EndIntroMusic {
   private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("fortuneandfavors-end-intro");

   private EndIntroMusic() {
   }

   /**
    * The track's own length, in ticks: 50.81 seconds of audio, measured rather than guessed.
    *
    * <p>The hold is the file, so a session that is never interrupted ends exactly when the music does.
    * A hold shorter than the file is a track that fades while it still has something to say.
    */
   public static final int TRACK_TICKS = 1016;
   /** How long the track takes to go away - on its own at the end, or early when the dragon arrives. */
   public static final int FADE_TICKS = 80;
   /** What is held at full volume before it starts letting go by itself. */
   public static final int HOLD_TICKS = TRACK_TICKS - FADE_TICKS;
   /** How far the track carries - the whole End, because the whole End is what it is announcing. */
   private static final double RANGE = 160.0;
   /**
    * How long a listener's music stays claimed past the last tick of the track.
    *
    * <p>The claim is what keeps the mod's own music systems quiet under this cue; it has to outlive the
    * music by the length of the fade, or a backpack jukebox would start up in the last two seconds of
    * it.
    */
   private static final int HOLD_MARGIN = 60;

   /** True while the track is running. */
   private static boolean playing = false;
   /** Ticks left before the session is over. */
   private static int ticksLeft = 0;
   /** Ticks left before a native listener's copy has to be stopped, per player. */
   private static final Map<UUID, Integer> NATIVE_DUE = new HashMap<>();
   /** Players already served this session. */
   private static final Set<UUID> SERVED = new HashSet<>();
   /**
    * Players who have heard it, ever - the whole of "for the first time".
    *
    * <p>Persisted, because a first visit is not a fact about a session: without this the cue would fire
    * again after every restart, which turns a moment into a habit.
    */
   private static final Set<UUID> HEARD = new HashSet<>();
   private static Path file;

   /** Is the track running? Read by a check, and by the tick that decides whether to start one. */
   public static boolean playing() {
      return playing;
   }

   /** What the session is worth in ticks - the hold plus the fade, which is the whole file. */
   public static int lengthTicks() {
      return TRACK_TICKS;
   }

   /** Has this body already been greeted by the End? Read by a check. */
   public static boolean hasHeard(UUID uuid) {
      return uuid != null && HEARD.contains(uuid);
   }

   /**
    * Per tick: greet anybody who has never been here, keep the session's claims alive, and stop a
    * native listener's copy when its length is up.
    *
    * <p>The dragon on the field is an argument rather than something read from here on purpose. This
    * class does not own the fight and must not learn how to; the one thing it needs to know is whether
    * the theme it is making room for is about to exist, and that question has exactly one owner
    * ({@code EnderDragonManager}).
    *
    * <p>A dragon being on the field does not merely end the session - it also means <b>nobody is
    * greeted</b> while it is there. A body whose first visit is to a live fight should hear the fight's
    * music, and it stays ungreeted, so the cue is still waiting for it the next time it walks into a
    * quiet island.
    */
   public static void tick(ServerLevel end, boolean dragonOnTheField) {
      if (end == null) {
         return;
      }
      if (dragonOnTheField) {
         stop(end);
         return;
      }
      if (!playing) {
         // Nothing running: somebody may have just arrived. The credits own the room's music while they
         // run, so a body that walks in on a finished fight waits for the next visit.
         if (EnderCreditsMusic.playing()) {
            return;
         }
         start(end);
         if (!playing) {
            return;
         }
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
         SERVED.clear();
         NATIVE_DUE.clear();
         LOGGER.info("End intro: done - the island is the players' silence again");
      }
   }

   /**
    * Take the track away early - the dragon is on the field, or a command wants it gone.
    *
    * <p>Idempotent, and deliberately quiet when there is nothing to stop: it is called from the tick
    * that watches a dragon, so it runs on every tick of every fight whether or not there is music here
    * to answer.
    */
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
            FfNet.send(p, FfEndIntroPayload.stop());
            stopNative(p);
         }
      }
      SERVED.clear();
      NATIVE_DUE.clear();
   }

   /** Open a session when somebody the island has never met is standing in it. */
   private static void start(ServerLevel end) {
      boolean anyone = false;
      for (ServerPlayer p : end.getPlayers(pl -> pl.isAlive() && !pl.isSpectator())) {
         if (!HEARD.contains(p.getUUID())) {
            anyone = true;
            break;
         }
      }
      if (!anyone) {
         return;
      }
      playing = true;
      ticksLeft = lengthTicks() + 40;
      SERVED.clear();
      NATIVE_DUE.clear();
      LOGGER.info("End intro: rolling {}s for a body that has never been here", TRACK_TICKS / 20);
   }

   /**
    * Hand out the track, once per player per session.
    *
    * <p>Spectators are skipped <b>and are not written down</b>: the ledger is what makes this a first
    * visit, and a moderator watching somebody else's first walk into the End has not had one - marking
    * them greeted would spend their cue on a fly-through they were not part of.
    */
   private static void serve(ServerLevel end) {
      Vec3 pos = new Vec3(0.5, 64.0, 0.5);
      for (ServerPlayer p : end.getPlayers(
         pl -> pl.isAlive() && !pl.isSpectator() && pl.distanceToSqr(pos.x, pos.y, pos.z) <= RANGE * RANGE
      )) {
         if (SERVED.contains(p.getUUID())) {
            continue;
         }
         SERVED.add(p.getUUID());
         // The ledger is written the moment the track is handed over rather than when it ends: a body
         // that logs out mid-track has still heard it, and a cue that plays twice because the music
         // was interrupted is worse than one that never plays again.
         if (HEARD.add(p.getUUID())) {
            save(end.getServer());
         }
         // The claim goes in first, so the track is heard over silence rather than over the End's own
         // score - and it is held past the end of the fade.
         BossMusic.takeOver(p, lengthTicks() + HOLD_MARGIN);
         boolean sent = FfNet.send(p, FfEndIntroPayload.play(HOLD_TICKS, FADE_TICKS));
         if (!sent) {
            nativePlay(p);
            NATIVE_DUE.put(p.getUUID(), TRACK_TICKS);
         }
      }
   }

   /**
    * The fallback: the track as a real sound, positioned on the listener.
    *
    * <p>{@link SoundSource#MUSIC}, matching the modded client's own copy, so both halves of the feature
    * sit on the same slider. No fade - a sound packet has none, and no early exit either, which is why
    * a vanilla client gets the cut rather than the fade (see {@link #stop}).
    *
    * <p>A client without the art pack cannot resolve the id and hears nothing. That is the whole of the
    * loss: the theme is a piece of this mod's music, and music nobody has the file for is music nobody
    * can be sent.
    */
   private static void nativePlay(ServerPlayer p) {
      try {
         long seed = p.level().getRandom().nextLong();
         p.connection.send(new ClientboundSoundPacket(
            Holder.direct(nativeCut(p)), SoundSource.MUSIC,
            p.getX(), p.getY(), p.getZ(), 1.0F, 1.0F, seed
         ));
      } catch (Throwable t) {
         LOGGER.warn("End intro: could not serve the native fallback to {}", p.getName().getString(), t);
      }
   }

   private static void stopNative(ServerPlayer p) {
      try {
         p.connection.send(new ClientboundStopSoundPacket(nativeCut(p).location(), SoundSource.MUSIC));
      } catch (Throwable ignored) {
      }
   }

   /**
    * The sound a native listener is actually sent: the track, or the disc a Bedrock client can hear.
    *
    * <p>Geyser translates vanilla sound ids and drops the ones it has never heard of, so the mod's
    * own id reaches a Bedrock client as nothing at all. {@link BedrockMusic#forListener} swaps in a
    * music disc the Bedrock half of the pack redefines to this same file - which is the whole of
    * "the Bedrock side can be served it", and the reason the End intro takes a fifth disc in that
    * table. Recomputing it for the stop matters as much as for the play: a client sent one id and
    * stopped on another keeps playing.
    */
   private static net.minecraft.sounds.SoundEvent nativeCut(ServerPlayer p) {
      return BedrockMusic.forListener(p, com.fortuneandfavors.ModSounds.ARIA_MATH_EPIC);
   }

   private static ServerPlayer playerOf(ServerLevel end, UUID id) {
      MinecraftServer server = end == null ? null : end.getServer();
      return server == null ? null : server.getPlayerList().getPlayer(id);
   }

   // ------------------------------------------------------------------ the ledger

   public static void load(MinecraftServer server) {
      HEARD.clear();
      SERVED.clear();
      NATIVE_DUE.clear();
      playing = false;
      ticksLeft = 0;
      if (server == null) {
         return;
      }
      file = EconomyManager.getDataDir(server).resolve("end_intro.json");
      JsonObject root = JsonUtil.readOrCreate(file, new JsonObject());
      if (!root.has("heard") || !root.get("heard").isJsonArray()) {
         return;
      }
      for (JsonElement entry : root.getAsJsonArray("heard")) {
         try {
            HEARD.add(UUID.fromString(entry.getAsString()));
         } catch (Exception ignored) {
            // A line that is not an id is dropped rather than carried: the worst it can cost is one
            // body hearing the island arrive twice.
         }
      }
   }

   public static void save(MinecraftServer server) {
      if (server == null) {
         return;
      }
      if (file == null) {
         file = EconomyManager.getDataDir(server).resolve("end_intro.json");
      }
      JsonObject root = new JsonObject();
      JsonArray heard = new JsonArray();
      for (UUID id : HEARD) {
         heard.add(id.toString());
      }
      root.add("heard", heard);
      JsonUtil.write(file, root);
   }

   // ------------------------------------------------------------------ the harness's seams

   /** Test seam: put a body's ledger wherever a check needs it, without arranging a walk to the End. */
   public static void markHeardForTest(UUID uuid) {
      if (uuid != null) {
         HEARD.add(uuid);
      }
   }

   /** Wipes every ledger - the harness's own reset, so checks cannot depend on each other's order. */
   public static void forgetAllForTest() {
      HEARD.clear();
      playing = false;
      ticksLeft = 0;
      SERVED.clear();
      NATIVE_DUE.clear();
   }
}
