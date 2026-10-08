package com.fortuneandfavors.client;

import com.fortuneandfavors.ModSounds;
import com.fortuneandfavors.net.FfMusicPayload;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;

/**
 * The reworked Wither's theme, as the client hears it.
 *
 * <p>The record is one track cut into three (a summon, a looped body, a death). This is the half of
 * the feature a sound packet cannot do: it plays each cut as a listener-glued sound of its own and
 * <b>fades between them</b> - the intro melts into the loop, the loop comes round as a crossfade
 * rather than a seam, and a cut to the death theme overlaps the music it replaces (the old cut
 * fades out under the new one rather than stopping dead). Every transition is the same operation,
 * which is why a skip (the server changing its mind mid-cut) is not a special case.
 *
 * <p>The loop is the client's own: once the server says "the loop", this keeps handing itself the
 * next copy a fade before the current one ends, so a fight of any length is one continuous track
 * with no packets on the wire. The server only speaks when the cut actually changes.
 *
 * <p>Volume is what does the work, and it works because a tickable sound's volume is re-read every
 * tick ({@code SoundEngine.tickInGameSound}). Nothing else about the audio is faked.
 */
public final class WitherMusic {
   private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("fortuneandfavors-wither-music");

   private WitherMusic() {
   }

   /** The crossfade length, in ticks: long enough to be a blend, short enough to feel deliberate. */
   private static final int FADE_TICKS = 30;
   /**
    * How long the loop cut runs, for the one case the client has to advance on its own: the intro
    * ending before the server has said "the loop". The payload normally carries this number, so
    * this is only the safety net and never the source of truth.
    */
   private static final int LOOP_TICKS_FALLBACK = 132 * 20;

   /** The cut being heard right now. */
   private static Segment current;
   /** Cuts on their way out - kept so they are not garbage-collected mid-fade. */
   private static final List<Segment> fading = new ArrayList<>();

   public static void init() {
      ClientPlayNetworking.registerGlobalReceiver(
         FfMusicPayload.TYPE,
         (payload, ctx) -> ctx.client().execute(() -> handle(payload))
      );
   }

   /** Client tick: advance the state machine - self-loop, auto-advance, and reap finished fades. */
   public static void tick() {
      if (Minecraft.getInstance().level == null) {
         return;
      }
      fading.removeIf(Segment::isStopped);
      if (current == null) {
         return;
      }
      if (current.isStopped()) {
         // The engine dropped it (a sound reload, a level change). Do not fight the engine: the
         // next server command will start the cut again.
         current = null;
         return;
      }
      // A fade before the end is where every transition is scheduled, so the overlap is always a
      // full crossfade rather than a note and a half of silence.
      if (current.played < current.totalTicks - FADE_TICKS) {
         return;
      }
      switch (current.track) {
         case FfMusicPayload.TRACK_LOOP ->
            // The seam: hand ourselves the next copy while this one fades, so the loop is a blend.
            crossfade(new Segment(ModSounds.WITHERED_LOOP, FfMusicPayload.TRACK_LOOP, current.totalTicks));
         case FfMusicPayload.TRACK_INTRO ->
            // Safety net for a server that never said "the loop": the intro becomes it by itself.
            start(FfMusicPayload.TRACK_LOOP, LOOP_TICKS_FALLBACK);
         default -> {
            // The death cut is played once: fade it out as it ends and let the fight go quiet.
            if (!current.stopping) {
               current.fadeTo(0.0F, FADE_TICKS);
            }
         }
      }
   }

   private static void handle(FfMusicPayload payload) {
      if (payload.action() == FfMusicPayload.ACTION_STOP) {
         // A teardown or a body taken out of the world: fade out rather than cut.
         if (current != null) {
            current.fadeTo(0.0F, FADE_TICKS);
            fading.add(current);
            current = null;
         }
         return;
      }
      if (payload.action() != FfMusicPayload.ACTION_PLAY) {
         return;
      }
      start(payload.track(), payload.lengthTicks());
   }

   private static void start(int track, int lengthTicks) {
      // A repeat of the cut already looping must not restart it - the server re-issuing "the loop"
      // for a native listener's benefit, or a tick race, would otherwise reset the music mid-bar.
      if (current != null && current.track == track && track == FfMusicPayload.TRACK_LOOP && !current.stopping) {
         return;
      }
      crossfade(new Segment(soundFor(track), track, Math.max(1, lengthTicks)));
   }

   /** Fade the current cut out and the next one in, overlapping them. */
   /** When the current copy of the loop started playing, for the beat overlay. 0 = not playing. */
   private static long loopStartMillis;
   /** The theme's tempo, measured from withered_loop.ogg (99.95 BPM, first beat at the start). */
   private static final double BEAT_MS = 60000.0 / 100.0;

   /** Beats since the current loop copy began (fractional), or -1 while the loop is not playing. */
   public static double beatsIntoLoop() {
      if (loopStartMillis == 0L || current == null || current.track != FfMusicPayload.TRACK_LOOP || current.stopping) {
         return -1.0;
      }
      return (System.currentTimeMillis() - loopStartMillis) / BEAT_MS;
   }

   private static void crossfade(Segment next) {
      if (next.track == FfMusicPayload.TRACK_LOOP) {
         loopStartMillis = System.currentTimeMillis();
      }
      if (current != null) {
         current.fadeTo(0.0F, FADE_TICKS);
         fading.add(current);
      }
      next.fadeTo(1.0F, FADE_TICKS);
      current = next;
      try {
         var result = Minecraft.getInstance().getSoundManager().play(next);
         if (result != net.minecraft.client.sounds.SoundEngine.PlayResult.STARTED
            && result != net.minecraft.client.sounds.SoundEngine.PlayResult.STARTED_SILENTLY) {
            // NOT_STARTED here means the sound event did not resolve (the pack's sounds.json is not
            // where the client can read it) or the volume was still zero. Until this fix that
            // second case was the theme's whole life: play() refuses a zero-volume cut and never
            // registers it as tickable, so it could not tick its own volume up. Named, because a
            // silent soundtrack and a missing one are the same thing from the player's chair.
            LOGGER.warn("Wither music: the client could not start cut {} ({} - the pack's sounds.json did not resolve, or the cut started at zero volume)", next.track, result);
         }
      } catch (Throwable t) {
         LOGGER.warn("Wither music: could not start the theme", t);
      }
   }

   private static SoundEvent soundFor(int track) {
      return switch (track) {
         case FfMusicPayload.TRACK_INTRO -> ModSounds.WITHERED_INTRO;
         case FfMusicPayload.TRACK_DEATH -> ModSounds.WITHERED_DEATH;
         default -> ModSounds.WITHERED_LOOP;
      };
   }

   /**
    * The volume a cut is born at.
    *
    * <p>Not zero, and this is the whole fix for "the music does not play":
    * {@code SoundEngine.play} refuses a sound whose computed volume is zero - unless the source is
    * {@code MUSIC} or the instance opts into {@link SoundInstance#canStartSilent()} - and a refused
    * sound is never registered as tickable, so its {@code tick()} never runs and its volume never
    * climbs off the floor. A cut that starts at exactly zero is a cut that never starts at all.
    * An inaudible-but-nonzero floor is what lets the fade-in actually happen; the first frame of
    * the fade is what the player hears as "it comes in".
    */
   private static final float SILENT_FLOOR = 0.0001F;

   /**
    * One cut of the theme, glued to the listener.
    *
    * <p>Relative and unattenuated so it plays dead centre in your headphones wherever you go -
    * positioning on the boss would make the music fade as it flies, which is the one thing a boss
    * theme must not do. {@code relative} means world coordinates must stay at zero: with relative
    * sound, x/y/z become a listener-relative offset, and a real coordinate would pan the music hard
    * to one side.
    */
   private static final class Segment extends AbstractTickableSoundInstance {
      final int track;
      final int totalTicks;
      int played;
      boolean stopping;
      private float fadeFrom;
      private float fadeTo;
      private int fadeTicks;
      private int fadeCounter;

      Segment(SoundEvent sound, int track, int totalTicks) {
         // MUSIC and not RECORDS, for two reasons. It is music, so it belongs on the music slider
         // rather than the jukebox one - and the engine explicitly lets a MUSIC-source sound start
         // inaudibly (see SoundEngine.play), which is the other half of the SILENT_FLOOR fix: this
         // cut is allowed to begin at a whisper and fade in, where the same call on RECORDS is
         // refused outright.
         super(sound, SoundSource.MUSIC, RandomSource.create());
         this.track = track;
         this.totalTicks = totalTicks;
         this.looping = false;
         this.relative = true;
         this.attenuation = SoundInstance.Attenuation.NONE;
         this.x = 0.0;
         this.y = 0.0;
         this.z = 0.0;
         this.pitch = 1.0F;
         this.volume = SILENT_FLOOR;
      }

      /** Aim the volume at a target over the given ticks; a target of zero schedules a stop. */
      void fadeTo(float target, int ticks) {
         this.fadeFrom = this.volume;
         this.fadeTo = target;
         this.fadeTicks = Math.max(1, ticks);
         this.fadeCounter = 0;
         if (target <= 0.0F) {
            this.stopping = true;
         }
      }

      @Override
      public void tick() {
         if (Minecraft.getInstance().level == null) {
            stop();
            return;
         }
         this.played++;
         if (this.fadeCounter < this.fadeTicks) {
            this.fadeCounter++;
            this.volume = Mth.lerp((float)this.fadeCounter / (float)this.fadeTicks, this.fadeFrom, this.fadeTo);
         } else {
            this.volume = this.fadeTo;
         }
         if (this.stopping && this.fadeCounter >= this.fadeTicks) {
            stop();
         }
      }
   }
}
