package com.fortuneandfavors.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type;
import net.minecraft.resources.Identifier;

/**
 * Server -&gt; client: play the End's opening theme, then let it go.
 *
 * <p>The same shape of command as the credits (see {@link FfCreditsPayload}) and for the same
 * reason: a {@code ClientboundSoundPacket} starts a track at one volume and stops it dead, and this
 * track has to <b>fade</b> - out early, the moment the dragon is on the field, so the dragon's own
 * theme can come in over a seam instead of a hole. A sound packet cannot do that; a sound the
 * client's own engine ticks can.
 *
 * <p>The two numbers travel with the command rather than being hardcoded on both sides, so how long
 * the track is held and how long it takes to go away are a server-side decision.
 *
 * <p>A vanilla, Bedrock or otherwise unmodded client cannot decode this, and never sees it: every
 * send goes through {@link FfNet}, and a refusal falls back to a plain sound packet with no fade -
 * see {@code economy.EndIntroMusic}. Music that arrives and is cut beats music nobody hears.
 */
public record FfEndIntroPayload(int action, int lengthTicks, int fadeTicks) implements CustomPacketPayload {
   public static final int ACTION_STOP = 0;
   public static final int ACTION_PLAY = 1;

   public static final Type<FfEndIntroPayload> TYPE =
      new Type(Identifier.fromNamespaceAndPath("fortuneandfavors", "end_intro_music"));
   public static final StreamCodec<RegistryFriendlyByteBuf, FfEndIntroPayload> CODEC = StreamCodec.composite(
      ByteBufCodecs.VAR_INT, FfEndIntroPayload::action,
      ByteBufCodecs.VAR_INT, FfEndIntroPayload::lengthTicks,
      ByteBufCodecs.VAR_INT, FfEndIntroPayload::fadeTicks,
      FfEndIntroPayload::new
   );

   public Type<? extends CustomPacketPayload> type() {
      return TYPE;
   }

   /** Hold the track for {@code lengthTicks}, then fade out over {@code fadeTicks}. */
   public static FfEndIntroPayload play(int lengthTicks, int fadeTicks) {
      return new FfEndIntroPayload(ACTION_PLAY, Math.max(1, lengthTicks), Math.max(1, fadeTicks));
   }

   /** Take it away now - the dragon is here, and the theme is coming in behind it. */
   public static FfEndIntroPayload stop() {
      return new FfEndIntroPayload(ACTION_STOP, 0, 0);
   }
}
