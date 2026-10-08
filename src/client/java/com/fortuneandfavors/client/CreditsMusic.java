package com.fortuneandfavors.client;

import com.fortuneandfavors.net.FfCreditsPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.sounds.SoundEvents;

/**
 * The credits, as the client hears them.
 *
 * <p>The server says how much of the track the fight is worth and how long it has to go away; this is
 * the half that turns that into sound. Everything about <i>how</i> a one-shot track holds and fades
 * lives in {@link OneShotTrack} - this class is the credits' two lines of wiring and nothing else, so
 * the same machinery can carry the End's opening theme ({@link EndIntroMusic}) without a second copy
 * of it.
 *
 * <p>There is no loop and no second cut, because the credits are played exactly once and then they
 * are over.
 */
public final class CreditsMusic {
   private CreditsMusic() {
   }

   public static void init() {
      ClientPlayNetworking.registerGlobalReceiver(
         FfCreditsPayload.TYPE,
         (payload, ctx) -> ctx.client().execute(() -> handle(payload))
      );
   }

   private static void handle(FfCreditsPayload payload) {
      if (payload.action() == FfCreditsPayload.ACTION_STOP) {
         OneShotTrack.stop();
         return;
      }
      if (payload.action() != FfCreditsPayload.ACTION_PLAY) {
         return;
      }
      OneShotTrack.play(SoundEvents.MUSIC_CREDITS.value(), payload.lengthTicks(), payload.fadeTicks());
   }
}
