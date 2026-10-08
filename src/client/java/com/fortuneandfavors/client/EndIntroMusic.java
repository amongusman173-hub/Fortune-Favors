package com.fortuneandfavors.client;

import com.fortuneandfavors.ModSounds;
import com.fortuneandfavors.net.FfEndIntroPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/**
 * The End's opening theme, as the client hears them.
 *
 * <p>A body's first walk into the End is the loudest quiet in the game - the island is there, the
 * portal is behind you, and nothing is playing. The server decides who hears the track and when it
 * leaves (see {@code economy.EndIntroMusic}); this is the client's two lines: start it when told, and
 * fade it when the dragon arrives.
 *
 * <p>It is played once and it never loops, because it is a cue rather than a soundtrack - the track's
 * own length is the hold, and anything longer than the file is a silence with a fade on it. The
 * mechanics are {@link OneShotTrack}'s; all that is decided here is which file.
 */
public final class EndIntroMusic {
   private EndIntroMusic() {
   }

   public static void init() {
      ClientPlayNetworking.registerGlobalReceiver(
         FfEndIntroPayload.TYPE,
         (payload, ctx) -> ctx.client().execute(() -> handle(payload))
      );
   }

   private static void handle(FfEndIntroPayload payload) {
      if (payload.action() == FfEndIntroPayload.ACTION_STOP) {
         OneShotTrack.stop();
         return;
      }
      if (payload.action() != FfEndIntroPayload.ACTION_PLAY) {
         return;
      }
      OneShotTrack.play(ModSounds.ARIA_MATH_EPIC, payload.lengthTicks(), payload.fadeTicks());
   }
}
