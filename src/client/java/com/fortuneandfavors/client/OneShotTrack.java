package com.fortuneandfavors.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;

/**
 * One track, played once: held for as long as the server asked, then faded out.
 *
 * <p>The one thing a server cannot do to music is a fade. A sound packet starts a track at a volume
 * and stops it, so a piece of music that is meant to arrive and then leave under something else - the
 * credits over a dragon's corpse, the End's opening theme under the dragon's own - is either played
 * to its end or cut off mid-bar. This is the half that makes "hold it, then let it go" a thing that
 * actually happens: one listener-glued sound whose own tick re-reads its volume every frame
 * ({@code SoundEngine.tickInGameSound}), holding at full and then walking down to nothing.
 *
 * <p>Shared by both of the mod's one-shot cues rather than written twice, because the two are the
 * same piece of machinery with a different file behind them - see {@link CreditsMusic} and
 * {@link EndIntroMusic}. (The Wither's theme is a different animal and keeps its own player: it
 * loops, it crossfades between three cuts, and none of that belongs here.)
 *
 * <p>No loop is ever set, and no cut here is longer than its own audio: a one-shot that loops is a
 * cue that never ends, and a hold longer than the file is a silence with a fade on it.
 */
public final class OneShotTrack {
   private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("fortuneandfavors-one-shot-music");

   /** How long a stop takes when the server changes its mind - quick, but not a cut. */
   public static final int STOP_FADE_TICKS = 30;
   /** How long a track takes to come up at the start, so it arrives rather than lands. */
   private static final int FADE_IN_TICKS = 40;

   private static Cut current;

   private OneShotTrack() {
   }

   /** The client's half of the wiring: drive the cut that is currently playing. */
   public static void init() {
      ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
   }

   /** Client tick: drop a cut the engine has already dropped, so nothing is held on to. */
   public static void tick() {
      if (current != null && current.isStopped()) {
         current = null;
      }
   }

   /** Start a track, replacing whatever one-shot was playing. */
   public static void play(SoundEvent event, int holdTicks, int fadeOutTicks) {
      if (event == null) {
         return;
      }
      if (current != null) {
         current.kill();
      }
      Cut cut = new Cut(event, Math.max(1, holdTicks), Math.max(1, fadeOutTicks));
      current = cut;
      try {
         Minecraft.getInstance().getSoundManager().play(cut);
      } catch (Throwable t) {
         LOGGER.warn("One-shot music: the client could not start {}", event.location(), t);
      }
   }

   /** Take the track away early: the same fade, on the stop's own short clock. */
   public static void stop() {
      if (current != null) {
         current.fadeOut(STOP_FADE_TICKS);
      }
   }

   /**
    * The track itself, glued to the listener.
    *
    * <p>Relative and unattenuated, so it plays dead centre wherever the player walks - the middle of
    * the island is at 0,64,0 and the music is not a thing at a place. {@code relative} means the
    * coordinates must stay at zero: with a relative sound, x/y/z become a listener-relative offset,
    * and a real coordinate would pan the track hard into one ear.
    *
    * <p>Born at an inaudible-but-nonzero volume on purpose: {@code SoundEngine.play} refuses a sound
    * whose volume computes to zero, and a refused sound is never registered as tickable - so its
    * {@code tick()} never runs and its volume never climbs. The MUSIC source is explicitly allowed to
    * start silently, which is what makes that first frame of the fade-in audible as an arrival.
    */
   private static final class Cut extends AbstractTickableSoundInstance {
      private static final float SILENT_FLOOR = 0.0001F;

      private final int holdTicks;
      private final int fadeOutTicks;
      private int played;
      private boolean fadingOut;
      private boolean stopping;
      private float fadeFrom;
      private float fadeTo;
      private int fadeTicks;
      private int fadeCounter;

      Cut(SoundEvent event, int holdTicks, int fadeOutTicks) {
         super(event, SoundSource.MUSIC, RandomSource.create());
         this.holdTicks = holdTicks;
         this.fadeOutTicks = fadeOutTicks;
         this.looping = false;
         this.relative = true;
         this.attenuation = SoundInstance.Attenuation.NONE;
         this.x = 0.0;
         this.y = 0.0;
         this.z = 0.0;
         this.pitch = 1.0F;
         this.volume = SILENT_FLOOR;
         // And up to full over the first couple of seconds, which is the arrival.
         this.fadeTo(1.0F, FADE_IN_TICKS);
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

      /** Drop the cut outright - used when a fresh play replaces one that is already running. */
      void kill() {
         stop();
      }

      /** Take the track away early: the same fade, on the stop's own short clock. */
      void fadeOut(int ticks) {
         if (this.stopping) {
            return;
         }
         this.fadingOut = true;
         this.fadeTo(0.0F, ticks);
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
         if (this.stopping) {
            if (this.fadeCounter >= this.fadeTicks) {
               stop();
            }
            return;
         }
         // The hold is over: let it go on its own, without needing the server to say so.
         if (!this.fadingOut && this.played >= this.holdTicks) {
            this.fadeOut(this.fadeOutTicks);
         }
      }
   }
}
