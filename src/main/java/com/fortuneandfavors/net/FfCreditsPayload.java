package com.fortuneandfavors.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type;
import net.minecraft.resources.Identifier;

/**
 * Server -&gt; client: play the credits, then let them go.
 *
 * <p>The End's own ending is a piece of music the game already ships - the credits track that plays
 * after the dragon and the portal - and it is a piece of music with a <b>fade</b> in it, which is
 * the one thing a {@code ClientboundSoundPacket} cannot do. A sound packet starts a track at one
 * volume and stops it dead, so the choice for a dragon's death would be a credits theme that runs
 * for its full ten minutes or one that is cut off mid-bar.
 *
 * <p>So the shape is the same as the Wither's theme (see {@link FfMusicPayload}): the server says
 * <b>how long to hold it and how long to let it go for</b>, and the client's own sound does the
 * ramp. The two numbers travel with the command rather than being hardcoded on both sides, so
 * re-tuning how much of the track the fight gets is a server-side change.
 *
 * <p>A vanilla client cannot decode this and never sees it: every send goes through {@link FfNet},
 * and a refusal falls back to a plain sound packet with no fade - see
 * {@code economy.EnderCreditsMusic}. Music without a fade beats no music at all.
 */
public record FfCreditsPayload(int action, int lengthTicks, int fadeTicks) implements CustomPacketPayload {
   public static final int ACTION_STOP = 0;
   public static final int ACTION_PLAY = 1;

   public static final Type<FfCreditsPayload> TYPE =
      new Type(Identifier.fromNamespaceAndPath("fortuneandfavors", "credits_music"));
   public static final StreamCodec<RegistryFriendlyByteBuf, FfCreditsPayload> CODEC = StreamCodec.composite(
      ByteBufCodecs.VAR_INT, FfCreditsPayload::action,
      ByteBufCodecs.VAR_INT, FfCreditsPayload::lengthTicks,
      ByteBufCodecs.VAR_INT, FfCreditsPayload::fadeTicks,
      FfCreditsPayload::new
   );

   public Type<? extends CustomPacketPayload> type() {
      return TYPE;
   }

   /** Play for {@code lengthTicks}, then fade out over {@code fadeTicks}. */
   public static FfCreditsPayload play(int lengthTicks, int fadeTicks) {
      return new FfCreditsPayload(ACTION_PLAY, Math.max(1, lengthTicks), Math.max(1, fadeTicks));
   }

   /** Take it away now, with a short fade rather than a cut. */
   public static FfCreditsPayload stop() {
      return new FfCreditsPayload(ACTION_STOP, 0, 0);
   }
}
