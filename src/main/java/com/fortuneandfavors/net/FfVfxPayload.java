package com.fortuneandfavors.net;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type;
import net.minecraft.resources.Identifier;

/**
 * Server -&gt; client: one tick's worth of visual cues.
 *
 * <p>A client with the mod draws these itself (see {@code client.FfVfxClient}) instead of being
 * sent vanilla particle packets, so a ring arrives as "a ring, here, this wide" and is drawn as a
 * clean expanding shockwave rather than as fifty particles flying in random directions. Vanilla
 * clients never see this payload; {@link FfVfx} sends them ordinary particles instead.
 */
public record FfVfxPayload(List<Cue> cues) implements CustomPacketPayload {
   public static final int MAX_CUES = 512;

   /**
    * One visual. What the fields mean depends on {@code kind} - see the constants in {@link FfVfx}.
    * {@code (x,y,z)} is always the anchor, {@code (ax,ay,az)} the second point or spread.
    */
   public record Cue(
      int kind, ParticleOptions particle, float x, float y, float z, float ax, float ay, float az, float a, float b, int count, int color
   ) {
   }

   private static final StreamCodec<RegistryFriendlyByteBuf, Cue> CUE_CODEC = StreamCodec.of((buf, c) -> {
      buf.writeVarInt(c.kind());
      ParticleTypes.STREAM_CODEC.encode(buf, c.particle());
      buf.writeFloat(c.x());
      buf.writeFloat(c.y());
      buf.writeFloat(c.z());
      buf.writeFloat(c.ax());
      buf.writeFloat(c.ay());
      buf.writeFloat(c.az());
      buf.writeFloat(c.a());
      buf.writeFloat(c.b());
      buf.writeVarInt(c.count());
      buf.writeInt(c.color());
   }, buf -> new Cue(
      buf.readVarInt(), ParticleTypes.STREAM_CODEC.decode(buf),
      buf.readFloat(), buf.readFloat(), buf.readFloat(),
      buf.readFloat(), buf.readFloat(), buf.readFloat(),
      buf.readFloat(), buf.readFloat(), buf.readVarInt(), buf.readInt()
   ));

   public static final Type<FfVfxPayload> TYPE = new Type(Identifier.fromNamespaceAndPath("fortuneandfavors", "vfx"));
   public static final StreamCodec<RegistryFriendlyByteBuf, FfVfxPayload> CODEC = StreamCodec.of((buf, p) -> {
      int n = Math.min(p.cues().size(), MAX_CUES);
      buf.writeVarInt(n);
      for (int i = 0; i < n; i++) {
         CUE_CODEC.encode(buf, p.cues().get(i));
      }
   }, buf -> {
      int n = Math.min(buf.readVarInt(), MAX_CUES);
      List<Cue> cues = new ArrayList<>(n);
      for (int i = 0; i < n; i++) {
         cues.add(CUE_CODEC.decode(buf));
      }
      return new FfVfxPayload(cues);
   });

   public Type<? extends CustomPacketPayload> type() {
      return TYPE;
   }
}
