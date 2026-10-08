package com.fortuneandfavors.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type;
import net.minecraft.resources.Identifier;

/** Serverbound: the client double-tapped the jump key while gliding and wants an
 *  Elytra Lunge boost. Sent as a Bedrock/console-friendly alternative to the
 *  empty-hand right-click trigger (which still works everywhere). */
public record FfLungePayload() implements CustomPacketPayload {
   public static final Type<FfLungePayload> TYPE = new Type(Identifier.fromNamespaceAndPath("fortuneandfavors", "lunge"));
   public static final StreamCodec<RegistryFriendlyByteBuf, FfLungePayload> CODEC = StreamCodec.unit(new FfLungePayload());

   public Type<? extends CustomPacketPayload> type() {
      return TYPE;
   }
}
