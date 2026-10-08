package com.fortuneandfavors.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type;
import net.minecraft.resources.Identifier;

/**
 * One layer of an arena floor, sent whole so the two machines can be compared
 * block by block.
 *
 * <p>This exists because "the arena does not render" has two completely
 * different causes that look identical in game. Either the server never built
 * the blocks, or it built them and the client never received them - and the
 * server, by construction, cannot see the second one. The only way to tell them
 * apart is to ask the client what it is actually holding, which is what this
 * payload is for.
 *
 * <p>{@link #ACTION_DUMP} is the server's half: the identifiers it wrote at
 * build time. The client reads its own copy of the same layer and answers with
 * {@link #ACTION_REPLY}, carrying its own identifiers ({@link #ID_UNLOADED}
 * where it has no chunk) plus a short human-readable note. The server diffs the
 * two and writes a three-panel report.
 *
 * <p>Identifiers are packed as varints rather than sent as {@code int}s: nearly
 * every block state in the registry is under 16384, so a typical 26,000 block
 * gladiator floor travels in about 40 KB instead of 104 KB. {@link #packIds}
 * and {@link #unpackIds} are the only place that encoding lives, and both sides
 * use them, so a mistake there would be caught by the round-trip self-test.
 */
public record FfArenaProbePayload(
   int action, int probe, int x0, int z0, int x1, int z1, int y, byte[] ids, String note
) implements CustomPacketPayload {
   /** Server to client: the layer as it was built. */
   public static final int ACTION_DUMP = 0;
   /** Client to server: the same layer as the client actually holds. */
   public static final int ACTION_REPLY = 1;

   /** Stands in for a block in a chunk the client has never received. It is
    *  deliberately not a legal block state id, so it can never be confused with
    *  a real answer. */
   public static final int ID_UNLOADED = -1;

   /** A floor bigger than this is not probed. The widest arena here is the
    *  gladiator world at 168x156, which is comfortably inside it; the cap is
    *  what keeps a hand-edited plot or a corrupt plot index from building a
    *  payload the client cannot decode. */
   public static final int MAX_FLOOR = 40000;

   public static final Type<FfArenaProbePayload> TYPE = new Type(
      Identifier.fromNamespaceAndPath("fortuneandfavors", "arena_probe")
   );

   public static final StreamCodec<RegistryFriendlyByteBuf, FfArenaProbePayload> CODEC = StreamCodec.composite(
      ByteBufCodecs.VAR_INT,
      FfArenaProbePayload::action,
      ByteBufCodecs.VAR_INT,
      FfArenaProbePayload::probe,
      ByteBufCodecs.VAR_INT,
      FfArenaProbePayload::x0,
      ByteBufCodecs.VAR_INT,
      FfArenaProbePayload::z0,
      ByteBufCodecs.VAR_INT,
      FfArenaProbePayload::x1,
      ByteBufCodecs.VAR_INT,
      FfArenaProbePayload::z1,
      ByteBufCodecs.VAR_INT,
      FfArenaProbePayload::y,
      ByteBufCodecs.BYTE_ARRAY,
      FfArenaProbePayload::ids,
      ByteBufCodecs.STRING_UTF8,
      FfArenaProbePayload::note,
      FfArenaProbePayload::new
   );

   /** The number of blocks a probe of this footprint will carry. */
   public int spanX() {
      return this.x1 - this.x0 + 1;
   }

   public int spanZ() {
      return this.z1 - this.z0 + 1;
   }

   public int cells() {
      return this.spanX() * this.spanZ();
   }

   /** Row-major index of a column inside the dump. Rows are x, columns are z, so
    *  the printed grid reads the way a map does. */
   public int indexOf(int x, int z) {
      return (x - this.x0) * this.spanZ() + (z - this.z0);
   }

   public Type<? extends CustomPacketPayload> type() {
      return TYPE;
   }

   /** Packs block state ids as varints, offset by one.
    *
    *  <p>The offset is the whole trick: {@code AIR} is block state id <b>zero</b>,
    *  which is also the most common answer on an arena floor, so a raw varint
    *  stream has no spare value to mark {@link #ID_UNLOADED} with. Storing
    *  {@code id + 1} leaves zero free to mean "no chunk here", and the round
    *  trip through {@link #unpackIds} puts the offset back. */
   public static byte[] packIds(int[] ids) {
      if (ids == null || ids.length == 0) {
         return new byte[0];
      }

      byte[] out = new byte[ids.length * 3 + 1];
      int at = 0;

      for (int id : ids) {
         int v = id < 0 ? 0 : id + 1;

         while ((v & -128) != 0) {
            out[at++] = (byte)(v & 127 | 128);
            v >>>= 7;
         }

         out[at++] = (byte)v;
      }

      byte[] exact = new byte[at];
      System.arraycopy(out, 0, exact, 0, at);
      return exact;
   }

   /** Unpacks varints back into ids. Returns null when the stream is truncated,
    *  which is the caller's signal to report a corrupt probe rather than to
    *  compare half a floor. */
   public static int[] unpackIds(byte[] packed, int count) {
      if (count < 0 || packed == null) {
         return null;
      }
      if (count == 0) {
         return new int[0];
      }

      int[] out = new int[count];
      int at = 0;

      for (int i = 0; i < count; i++) {
         int shift = 0;
         int v = 0;
         boolean terminated = false;

         while (at < packed.length && shift <= 28) {
            int b = packed[at++] & 255;
            v |= (b & 127) << shift;
            if ((b & 128) == 0) {
               terminated = true;
               break;
            }

            shift += 7;
         }

         if (!terminated) {
            return null;
         }

         out[i] = v == 0 ? ID_UNLOADED : v - 1;
      }

      return out;
   }
}
