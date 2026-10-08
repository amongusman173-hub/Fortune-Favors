package com.fortuneandfavors.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type;
import net.minecraft.resources.Identifier;

/**
 * Server -&gt; client: drive the reworked Wither's theme.
 *
 * <p>This exists because a {@code ClientboundSoundPacket} cannot do what the fight's music needs.
 * A sound packet starts a track once, at one volume, and stops it dead - there is no fade and no
 * loop. The fight wants both: the intro has to melt into the loop, the loop has to come round
 * without a seam, and a cut to the death theme has to overlap the music it replaces instead of
 * replacing it mid-note. All three are the client's job, so the server's whole role here is to
 * say <b>which</b> cut should be playing and let the client's own player do the fades.
 *
 * <p>Fields: action (0 stop / 1 play), the track (see {@link #TRACK_INTRO} and friends) and the
 * track's length in ticks. The length travels with the command rather than being hardcoded on both
 * sides, so re-cutting the audio only has to change one number.
 *
 * <p>A vanilla, Bedrock or otherwise unmodded client cannot decode this, and never sees it: every
 * send goes through {@link FfNet}, and the ones that are refused fall back to a plain sound packet
 * with no fade (see {@code economy.WitherMusic}). Music with a seam beats silence.
 */
public record FfMusicPayload(int action, int track, int lengthTicks) implements CustomPacketPayload {
   public static final int ACTION_STOP = 0;
   public static final int ACTION_PLAY = 1;

   /** The charge-up cut: the summon, before the fight is on. */
   public static final int TRACK_INTRO = 1;
   /** The body of the fight: looped by the client until it is told otherwise. */
   public static final int TRACK_LOOP = 2;
   /** The ceremony: played once, over the top of the looping theme, when the Wither dies. */
   public static final int TRACK_DEATH = 3;

   public static final Type<FfMusicPayload> TYPE = new Type(Identifier.fromNamespaceAndPath("fortuneandfavors", "wither_music"));
   public static final StreamCodec<RegistryFriendlyByteBuf, FfMusicPayload> CODEC = StreamCodec.composite(
      ByteBufCodecs.VAR_INT, FfMusicPayload::action,
      ByteBufCodecs.VAR_INT, FfMusicPayload::track,
      ByteBufCodecs.VAR_INT, FfMusicPayload::lengthTicks,
      FfMusicPayload::new
   );

   public Type<? extends CustomPacketPayload> type() {
      return TYPE;
   }

   public static FfMusicPayload play(int track, int lengthTicks) {
      return new FfMusicPayload(ACTION_PLAY, track, Math.max(1, lengthTicks));
   }

   public static FfMusicPayload stop() {
      return new FfMusicPayload(ACTION_STOP, 0, 0);
   }
}
