package com.fortuneandfavors.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type;
import net.minecraft.resources.Identifier;

public record FfScreenFxPayload(int fx, boolean active) implements CustomPacketPayload {
   public static final int FX_DEVOUR = 1;
   public static final int FX_CORRUPTION = 2;
   public static final int FX_LINKED = 3;
   public static final int FX_SWORD_BLOCK = 4;
   public static final int FX_GOLDEN_APPLE = 5;
   public static final int FX_DEADEYE = 6;
   /** Time has stopped around you (on), or started again (off). */
   public static final int FX_TIMESTOP = 7;
   /** The Wither King's theme beat pulsing round the screen (on below half health). */
   public static final int FX_BEAT = 8;
   /** A loot box has started spinning. */
   public static final int FX_LOOT_SPIN = 9;
   /** A loot box prize was revealed: FX_LOOT_REVEAL + the rarity band's ordinal (common 0 .. legendary 3). */
   public static final int FX_LOOT_REVEAL = 10;
   /** The Mindbinder cracked your mind: two and a half seconds of psychic static. */
   public static final int FX_FRACTURE = 14;
   /** You are working a Mind Control Staff seize: strings and a closing tunnel (kept alive by re-sending). */
   public static final int FX_PUPPET = 15;
   /** Corruption's own overlay, one per stage: FX_CORRUPTION_STAGE + stage - 1 (16, 17, 18). Kept alive by re-sending. */
   public static final int FX_CORRUPTION_STAGE = 16;
   public static final Type<FfScreenFxPayload> TYPE = new Type(Identifier.fromNamespaceAndPath("fortuneandfavors", "screen_fx"));
   public static final StreamCodec<RegistryFriendlyByteBuf, FfScreenFxPayload> CODEC = StreamCodec.composite(
      ByteBufCodecs.VAR_INT, FfScreenFxPayload::fx, ByteBufCodecs.BOOL, FfScreenFxPayload::active, FfScreenFxPayload::new
   );

   public Type<? extends CustomPacketPayload> type() {
      return TYPE;
   }
}
