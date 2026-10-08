package com.fortuneandfavors.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type;
import net.minecraft.resources.Identifier;

/**
 * What a client says it is - serverbound only, and optional on purpose.
 *
 * <p>A client that never sends this is not suspicious, it is <b>unknown</b>, and the two
 * are different states all the way through the anticheat. Every other payload in this
 * package is a request for the server to do something; this one asks for nothing and is
 * answered by nothing. Its entire life is a note on the staff readout and a line in the
 * evidence of a violation that some <i>other</i> check had already found for its own
 * reasons - see {@link com.fortuneandfavors.anticheat.ClientContext} for why that
 * limitation is structural rather than a promise.
 */
public record FfClientContextPayload(int protocol, String json) implements CustomPacketPayload {
   public static final Type<FfClientContextPayload> TYPE = new Type(
      Identifier.fromNamespaceAndPath("fortuneandfavors", "client_context")
   );
   public static final StreamCodec<RegistryFriendlyByteBuf, FfClientContextPayload> CODEC = StreamCodec.composite(
      ByteBufCodecs.VAR_INT,
      FfClientContextPayload::protocol,
      ByteBufCodecs.STRING_UTF8,
      FfClientContextPayload::json,
      FfClientContextPayload::new
   );

   public Type<? extends CustomPacketPayload> type() {
      return TYPE;
   }
}
