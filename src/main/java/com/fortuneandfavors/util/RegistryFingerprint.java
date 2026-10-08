package com.fortuneandfavors.util;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;

/**
 * A cheap fingerprint of this end's block registry.
 *
 * <p>Block states are transmitted in a chunk packet as <em>numeric ids</em>, and
 * those ids are assigned by registration order. So if the server has a block the
 * client does not, every id after that point shifts and the client hits
 * {@code MissingPaletteEntryException: Missing Palette entry for index N} while
 * decoding the chunk - which is what a player sees as "one chunk is unloaded and
 * everything inside it is missing", or as items and blocks that silently do not
 * exist. That is a mod-set mismatch, not a bug in any single mod: vanilla has no
 * way to tell the two ends apart before it starts shipping chunk data.
 *
 * <p>Comparing these two numbers during the config handshake turns that silent
 * corruption into a named, actionable warning at join time.
 *
 * <p>Registries are read-only here and are frozen well before any handshake, so
 * iterating them is safe.
 */
public final class RegistryFingerprint {
   private RegistryFingerprint() {
   }

   /** Number of registered blocks on this end. */
   public static long blockCount() {
      try {
         return BuiltInRegistries.BLOCK.size();
      } catch (Throwable t) {
         return -1L;
      }
   }

   /** Order-sensitive hash over every block id, so a mod that adds OR reorders
    *  blocks produces a different number. */
   public static long blockHash() {
      try {
         long h = 1125899906842597L;
         for (Block b : BuiltInRegistries.BLOCK) {
            Identifier id = BuiltInRegistries.BLOCK.getKey(b);
            h = 31L * h + id.toString().hashCode();
         }
         return h;
      } catch (Throwable t) {
         return -1L;
      }
   }

   /** Total number of block states, when the registry can be walked. This is the
    *  number that actually has to line up for chunk palettes to decode. -1 when
    *  unavailable, in which case callers should fall back to the block count. */
   public static long blockStates() {
      try {
         long n = 0L;
         for (Block b : BuiltInRegistries.BLOCK) {
            n += b.getStateDefinition().getPossibleStates().size();
         }
         return n;
      } catch (Throwable t) {
         return -1L;
      }
   }
}
