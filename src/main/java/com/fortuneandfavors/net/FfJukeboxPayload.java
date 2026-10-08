package com.fortuneandfavors.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type;
import net.minecraft.resources.Identifier;

/** Server -> client: start or stop the backpack's portable jukebox music.
 *
 *  Sent to the OWNER (who carries the boombox - they hear it at full volume
 *  wherever they go) and to every player NEAR the owner, who get the same song
 *  as a positional sound tracking the owner with volume falloff, like a real
 *  boombox. The client loops the song seamlessly - it never breaks or restarts
 *  until the song ends. Vanilla clients simply ignore it.
 *
 *  Fields: action (0 stop / 1 play / 2 stop-everything), the jukebox song id,
 *  the owner's UUID (empty when the receiver IS the owner), and the owner's
 *  position so the boombox starts at the right spot even before the owner's
 *  entity resolves. Action 2 carries nothing but the action itself: it is the
 *  one instruction that is not about a particular boombox - it is a boss theme
 *  saying the client's music is not its own until further notice. */
public record FfJukeboxPayload(int action, String song, String owner, float x, float y, float z) implements CustomPacketPayload {
   public static final int ACTION_STOP = 0;
   public static final int ACTION_PLAY = 1;
   /**
    * Stop everything this client is playing, whatever it belongs to.
    *
    * <p>Its own action rather than a stop with a special owner, because it means something a
    * per-song stop cannot: <b>every</b> boombox this client holds, including the one it is keeping
    * alive for itself. A boss theme that has taken the music needs exactly that - a stop for one
    * song would be looped back on the next tick by the client's own self-healing, which is the
    * design working as intended everywhere except under a boss theme.
    */
   public static final int ACTION_STOP_ALL = 2;
   public static final Type<FfJukeboxPayload> TYPE = new Type(Identifier.fromNamespaceAndPath("fortuneandfavors", "backpack_jukebox"));
   public static final StreamCodec<RegistryFriendlyByteBuf, FfJukeboxPayload> CODEC = StreamCodec.composite(
      ByteBufCodecs.VAR_INT, FfJukeboxPayload::action,
      ByteBufCodecs.STRING_UTF8, FfJukeboxPayload::song,
      ByteBufCodecs.STRING_UTF8, FfJukeboxPayload::owner,
      ByteBufCodecs.FLOAT, FfJukeboxPayload::x,
      ByteBufCodecs.FLOAT, FfJukeboxPayload::y,
      ByteBufCodecs.FLOAT, FfJukeboxPayload::z,
      FfJukeboxPayload::new
   );

   public Type<? extends CustomPacketPayload> type() {
      return TYPE;
   }

   public static FfJukeboxPayload play(String songId, String ownerUuid, float x, float y, float z) {
      return new FfJukeboxPayload(ACTION_PLAY, songId, ownerUuid == null ? "" : ownerUuid, x, y, z);
   }

   public static FfJukeboxPayload stop(String ownerKey) {
      return new FfJukeboxPayload(ACTION_STOP, "", ownerKey == null ? "" : ownerKey, 0.0F, 0.0F, 0.0F);
   }

   /** Silence every boombox this client is holding - see {@link #ACTION_STOP_ALL}. */
   public static FfJukeboxPayload stopAll() {
      return new FfJukeboxPayload(ACTION_STOP_ALL, "", "", 0.0F, 0.0F, 0.0F);
   }

   public boolean isOwnerReceiver() {
      return this.owner.isEmpty();
   }
}
