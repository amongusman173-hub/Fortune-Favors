package com.fortuneandfavors.client;

import com.fortuneandfavors.net.FfArenaProbePayload;
import java.util.HashSet;
import java.util.Set;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Block;

/**
 * The client's half of an arena floor probe.
 *
 * <p>When the server asks what this client actually holds for the layer it built,
 * this reads the answer straight out of the loaded world and sends it back
 * block for block. That is the only way to tell "the arena was never built"
 * apart from "the arena was built and this client never received it" - a
 * question the server physically cannot answer on its own, because from its side
 * the two look identical.
 *
 * <p>Nothing here trusts the server's bounds: a footprint bigger than
 * {@link FfArenaProbePayload#MAX_FLOOR} is refused with an explanation rather
 * than walked. A client that wandered outside the footprint would otherwise
 * force 40,000 unloaded chunk lookups on the render thread for every bad request.
 */
public final class DuelArenaProbe {
   private DuelArenaProbe() {
   }

   /** Answers a dump. Runs on the client thread (the caller hops there). */
   public static void onDump(FfArenaProbePayload p, Minecraft client) {
      ClientLevel level = client.level;
      int cells = p.cells();

      if (level == null || cells <= 0 || cells > FfArenaProbePayload.MAX_FLOOR) {
         String why = level == null
            ? "not in a world"
            : "the server asked for " + cells + " blocks, past the " + FfArenaProbePayload.MAX_FLOOR + " limit";
         ClientPlayNetworking.send(reply(p, new byte[0], "client could not answer: " + why));
         return;
      }

      int[] ids = new int[cells];
      Set<Long> seenChunks = new HashSet<>();
      Set<Long> missingChunks = new HashSet<>();
      BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
      int solid = 0;

      for (int x = p.x0(); x <= p.x1(); x++) {
         for (int z = p.z0(); z <= p.z1(); z++) {
            int at = p.indexOf(x, z);
            long chunkKey = chunkKey(x >> 4, z >> 4);

            if (missingChunks.contains(chunkKey)) {
               ids[at] = FfArenaProbePayload.ID_UNLOADED;
               continue;
            }

            if (seenChunks.add(chunkKey) && !level.hasChunk(x >> 4, z >> 4)) {
               missingChunks.add(chunkKey);
               ids[at] = FfArenaProbePayload.ID_UNLOADED;
               continue;
            }

            pos.set(x, p.y(), z);
            int id = Block.getId(level.getBlockState(pos));
            ids[at] = id;
            if (id != 0) {
               solid++;
            }
         }
      }

      int totalChunks = seenChunks.size();
      String note = "dimension="
         + level.dimension().identifier()
         + " chunks="
         + (totalChunks - missingChunks.size())
         + '/'
         + totalChunks
         + " registryBlockStates="
         + Block.BLOCK_STATE_REGISTRY.size()
         + " registryBlocks="
         + BuiltInRegistries.BLOCK.size()
         + " solid="
         + solid
         + " cells="
         + cells;

      ClientPlayNetworking.send(reply(p, FfArenaProbePayload.packIds(ids), note));

      if (client.player != null) {
         client.player.sendSystemMessage(
            Component.literal(
               "§7Arena probe §f"
                  + p.spanX()
                  + 'x'
                  + p.spanZ()
                  + "§7 at y=§f"
                  + p.y()
                  + "§7 - sent §f"
                  + cells
                  + "§7 blocks, §f"
                  + (totalChunks - missingChunks.size())
                  + '/'
                  + totalChunks
                  + "§7 chunks loaded"
            )
         );
      }
   }

   private static FfArenaProbePayload reply(FfArenaProbePayload from, byte[] ids, String note) {
      return new FfArenaProbePayload(
         FfArenaProbePayload.ACTION_REPLY,
         from.probe(),
         from.x0(),
         from.z0(),
         from.x1(),
         from.z1(),
         from.y(),
         ids,
         note
      );
   }

   private static long chunkKey(int cx, int cz) {
      return (long)cx << 32 ^ (long)cz & 4294967295L;
   }
}
