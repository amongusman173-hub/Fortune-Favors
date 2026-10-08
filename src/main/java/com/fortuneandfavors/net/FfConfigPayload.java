package com.fortuneandfavors.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type;
import net.minecraft.resources.Identifier;

public record FfConfigPayload(int action, String json) implements CustomPacketPayload {
   public static final int ACTION_REQUEST = 0;
   public static final int ACTION_APPLY = 1;
   public static final int ACTION_SNAPSHOT = 2;
   public static final Type<FfConfigPayload> TYPE = new Type(Identifier.fromNamespaceAndPath("fortuneandfavors", "config_sync"));
   public static final StreamCodec<RegistryFriendlyByteBuf, FfConfigPayload> CODEC = StreamCodec.composite(
      ByteBufCodecs.VAR_INT, FfConfigPayload::action, ByteBufCodecs.STRING_UTF8, FfConfigPayload::json, FfConfigPayload::new
   );

   public Type<? extends CustomPacketPayload> type() {
      return TYPE;
   }
}
