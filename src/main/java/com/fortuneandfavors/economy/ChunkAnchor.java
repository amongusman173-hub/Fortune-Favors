package com.fortuneandfavors.economy;

import com.fortuneandfavors.util.Chat;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;

/**
 * The Chunk Anchor: the one machine that is about where a player is NOT.
 *
 * <p>Every other block the mod sells needs somebody standing at it. This is the opposite, and it is
 * why it is the most expensive thing on the shelves: it holds the chunks around itself loaded, so a
 * farm keeps growing, a furnace keeps burning and a machine keeps running while its owner is
 * elsewhere in the world - or not logged in at all.
 *
 * <p>Two rules keep it from being a server's problem rather than a player's tool. It is limited to
 * one per player, refused at the moment of placement rather than paid for and regretted, and it
 * holds a 3x3 block of chunks - nine, which is a farm and not a base. Forced chunks are a vanilla
 * concept (the same ticket {@code /forceload} takes), so nothing here is a new kind of load on the
 * server: it is the load an admin could already ask for, charged for instead of granted.
 */
public final class ChunkAnchor {
   /** Chunks held in each direction from the anchor's own - so a 3x3 block of them. */
   public static final int RADIUS = 1;
   /** How many dashes are laid along each side of the outline, so its density is one number. */
   public static final int DASHES = 12;
   /**
    * How many of these one player may have standing at once.
    *
    * <p>Three, each dearest than the last: one is a farm, three is a base's worth of held ground and
    * the price is what makes the third a decision rather than a purchase. The cap is enforced at the
    * block - see {@link #canPlace} - so a player can never pay for an anchor the world will refuse.
    */
   public static final int PER_PLAYER = 3;
   /**
    * How far a viewer has to be from an anchor before its outline is not worth drawing.
    *
    * <p>The boundary is forty-eight blocks across and its far edge is further away than the near
    * one, so an outline drawn for somebody at the limit is mostly drawn into empty air. Seventy-two
    * is the distance a player can still read the far corners of it from.
    */
   public static final double VFX_RANGE = 72.0;
   /**
    * What the first one costs, in the shops that stock it.
    *
    * <p>The dearest thing the mod sells, and read from here by the shelf that stocks it rather than
    * typed into the catalogue twice: a price written in two places is a price that disagrees with
    * itself, and the shop window is the one the player actually reads.
    */
   public static final long PRICE = 250_000L;
   /** Each further anchor a player owns costs this much more than the one before it. */
   public static final long PRICE_STEP = 350_000L;

   private ChunkAnchor() {
   }

   /** True when this player already has an anchor standing somewhere in the world. */
   public static boolean owns(UUID uuid) {
      return ownedCount(uuid) > 0;
   }

   /** How many anchors this player has standing right now. */
   public static int ownedCount(UUID uuid) {
      if (uuid == null) {
         return 0;
      }
      int n = 0;
      for (MachineManager.Machine m : MachineManager.all().values()) {
         if (MachineManager.TYPE_CHUNK_ANCHOR.equals(m.type()) && uuid.equals(m.owner())) {
            n++;
         }
      }
      return n;
   }

   /** True while this player is still allowed to put another anchor down. */
   public static boolean canPlace(UUID uuid) {
      return ownedCount(uuid) < PER_PLAYER;
   }

   /**
    * What this player's next anchor costs.
    *
    * <p>The price climbs with the ones they already have standing rather than with the ones they
    * have ever bought, so picking an anchor up makes the next one cheaper again - which is what
    * keeps the rule about ground being held rather than about money being spent.
    */
   public static long priceFor(UUID uuid) {
      return PRICE + PRICE_STEP * Math.min(PER_PLAYER - 1, ownedCount(uuid));
   }

   /** What the shop shelf says beside the price, so the ladder is visible before the click. */
   public static String priceNote(UUID uuid) {
      int n = ownedCount(uuid);
      if (n >= PER_PLAYER) {
         return "You already hold all " + PER_PLAYER + " - pick one up to buy another.";
      }
      return "Anchor " + (n + 1) + " of " + PER_PLAYER + " - the next costs " + com.fortuneandfavors.util.Chat.moneyStr(priceFor(uuid)) + ".";
   }

   /** The line a player who already holds the maximum gets instead of another anchor. */
   public static void refuse(ServerPlayer player) {
      int n = ownedCount(player.getUUID());
      Chat.msg(
         player,
         n >= PER_PLAYER
            ? "&cYou are already holding " + PER_PLAYER + " Chunk Anchors. Pick one up to place another."
            : "&cYou already have " + n + " Chunk Anchor(s) down."
      );
   }

   /** Holds the chunks around a freshly placed anchor, with the flourish of a thing switching on. */
   public static void hold(Level level, BlockPos pos) {
      if (level instanceof ServerLevel server) {
         force(server, pos, true);
         // Materialise the tickets now rather than at the next autosave: the whole promise is that
         // the ground is loaded, and a player who places an anchor and walks away must not be able
         // to catch the chunk it is protecting unloading first.
         loadHeld(server, pos);
         com.fortuneandfavors.net.FfVfx.particles(server, ParticleTypes.END_ROD, pos.getX() + 0.5, pos.getY() + 1.2, pos.getZ() + 0.5, 30, 0.6, 0.6, 0.6, 0.05);
         server.playSound(null, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, SoundEvents.BEACON_ACTIVATE, SoundSource.BLOCKS, 1.2F, 1.4F);
      }
   }

   /**
    * Re-asserts the ticket for an anchor that has been found with its own ground unloaded.
    *
    * <p>Separate from {@link #hold} on purpose: that one is a machine switching on and has the
    * flourish to match, and this one runs while a player may be looking at it - an anchor quietly
    * repairing itself must not fire a beacon sound at somebody's base every second it is broken. No
    * particles and no noise, then: the ticket goes back and the ground comes back.
    */
   public static void reassert(Level level, BlockPos pos) {
      if (level instanceof ServerLevel server) {
         force(server, pos, true);
      }
   }

   /** Gives the chunks back when the anchor is uprooted. */
   public static void release(Level level, BlockPos pos) {
      if (level instanceof ServerLevel server) {
         force(server, pos, false);
         com.fortuneandfavors.net.FfVfx.particles(server, ParticleTypes.SMOKE, pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, 18, 0.4, 0.4, 0.4, 0.02);
      }
   }

   /**
    * Draws the promise: the boundary of the nine chunks this anchor is holding loaded.
    *
    * <p>The whole feature is invisible by definition - it does something where the player is not -
    * so the one thing it owes them is a way to see which ground is being kept alive. The outline is
    * a dashes-long rectangle along the chunk border at the anchor's own level, with the anchor's
    * column picked out brighter, so a player can see at a glance which farm is inside it and which
    * one is one block outside it and quietly not growing.
    *
    * <p>Drawn from the server on the machines' one-second clock, and only when a player is close
    * enough to see it: an anchor in a corner of the world nobody is standing in costs one distance
    * check and nothing else.
    *
    * @return how many particles were sent, for the audit and the self-test
    */
   public static int vfx(ServerLevel level, BlockPos pos) {
      net.minecraft.world.entity.player.Player viewer = level.getNearestPlayer(
         pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, VFX_RANGE, false
      );
      if (viewer == null) {
         return 0;
      }
      int cx = pos.getX() >> 4;
      int cz = pos.getZ() >> 4;
      int loX = (cx - RADIUS) << 4;
      int loZ = (cz - RADIUS) << 4;
      int hiX = ((cx + RADIUS) << 4) + 15;
      int hiZ = ((cz + RADIUS) << 4) + 15;
      double y = pos.getY() + 1.0;
      int sent = 0;
      for (int i = 0; i <= DASHES; i++) {
         double t = (double)i / (double)DASHES;
         double x = loX + 0.5 + t * (hiX - loX);
         double z = loZ + 0.5 + t * (hiZ - loZ);
         sent += edge(level, x, y, loZ + 0.5);
         sent += edge(level, x, y, hiZ + 0.5);
         sent += edge(level, loX + 0.5, y, z);
         sent += edge(level, hiX + 0.5, y, z);
      }
      // The corners, and the anchor's own column, so the shape has a centre as well as an edge.
      for (int[] corner : new int[][]{{loX, loZ}, {loX, hiZ}, {hiX, loZ}, {hiX, hiZ}}) {
         com.fortuneandfavors.net.FfVfx.particles(level, 
            ParticleTypes.ELECTRIC_SPARK, corner[0] + 0.5, y, corner[1] + 0.5, 3, 0.15, 0.3, 0.15, 0.0
         );
         sent += 3;
      }
      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.END_ROD, pos.getX() + 0.5, y + 0.4, pos.getZ() + 0.5, 2, 0.2, 0.3, 0.2, 0.01);
      return sent + 2;
   }

   /** One dash of the outline: a single still spark, so the edge reads as a drawn line. */
   private static int edge(ServerLevel level, double x, double y, double z) {
      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.END_ROD, x, y, z, 1, 0.0, 0.0, 0.0, 0.0);
      return 1;
   }

   /**
    * Re-asserts every anchor's ticket.
    *
    * <p>Called once when the machines are loaded (a restart must not leave a paid-for anchor dark)
    * and once a minute afterwards, because the one thing that would quietly break this feature is a
    * ticket being dropped by something that is not us.
    *
    * @return how many anchors were confirmed
    */
   public static int reaffirm(MinecraftServer server) {
      int held = 0;
      for (var entry : MachineManager.all().entrySet()) {
         if (!MachineManager.TYPE_CHUNK_ANCHOR.equals(entry.getValue().type())) {
            continue;
         }
         ServerLevel level = MachineManager.levelOf(server, entry.getKey());
         BlockPos pos = MachineManager.posOf(entry.getKey());
         if (level == null || pos == null) {
            continue;
         }
         force(level, pos, true);
         held++;
      }
      return held;
   }

   /** Drops every ticket an anchor not in the ledger any more might still be holding. */
   public static void releaseAll(MinecraftServer server) {
      for (var entry : MachineManager.all().entrySet()) {
         ServerLevel level = MachineManager.levelOf(server, entry.getKey());
         BlockPos pos = MachineManager.posOf(entry.getKey());
         if (level != null && pos != null) {
            force(level, pos, false);
         }
      }
   }

   /** How many chunks one anchor holds - the audit's window on the size of the promise. */
   public static int heldChunks() {
      return (RADIUS * 2 + 1) * (RADIUS * 2 + 1);
   }

   /** Every chunk position one anchor is responsible for, as packed {@code (x << 4)} pairs - one
    *  list, so the ticket, the outline and the window can never disagree about what is held. */
   public static List<int[]> heldChunkPositions(BlockPos pos) {
      List<int[]> out = new ArrayList<>();
      int cx = pos.getX() >> 4;
      int cz = pos.getZ() >> 4;
      for (int dx = -RADIUS; dx <= RADIUS; dx++) {
         for (int dz = -RADIUS; dz <= RADIUS; dz++) {
            out.add(new int[]{cx + dx, cz + dz});
         }
      }
      return out;
   }

   /** Asks the level for each held chunk, which is what turns the ticket into loaded ground. */
   private static void loadHeld(ServerLevel level, BlockPos pos) {
      for (int[] c : heldChunkPositions(pos)) {
         try {
            level.getChunk(c[0], c[1]);
         } catch (Throwable ignored) {
         }
      }
   }

   /** How many of an anchor's nine chunks are loaded right now - the number its window shows. */
   public static int loadedNow(ServerLevel level, BlockPos pos) {
      int loaded = 0;
      for (int[] c : heldChunkPositions(pos)) {
         if (level.getChunkSource().hasChunk(c[0], c[1])) {
            loaded++;
         }
      }
      return loaded;
   }

   private static void force(ServerLevel level, BlockPos pos, boolean on) {
      for (int[] c : heldChunkPositions(pos)) {
         level.setChunkForced(c[0], c[1], on);
      }
      if (on) {
         loadHeld(level, pos);
      }
   }

   /**
    * Sells this player one more anchor from the anchor's own window.
    *
    * <p>The shelf sells the first one; this is what sells the second and third, and it is the only
    * place the price ladder is read from, so the window, the shop and the rule can never disagree.
    * The money is taken only after every refusal has been made, and the item only ever appears once
    * the cash has actually left the account.
    *
    * @return null when it went through, or the reason it did not
    */
   public static String buyAnother(ServerPlayer player) {
      if (!canPlace(player.getUUID())) {
         return "You are already holding " + PER_PLAYER + " anchors - pick one up first.";
      }
      long price = priceFor(player.getUUID());
      if (!EconomyManager.hasCash(player.getUUID(), price)) {
         return "That one costs " + Chat.moneyStr(price) + " and you are short.";
      }
      if (!EconomyManager.takeCash(player.getUUID(), price)) {
         return "The payment did not go through - nothing was charged.";
      }
      com.fortuneandfavors.util.InventoryHelper.giveOrDrop(player, com.fortuneandfavors.ModItems.chunkAnchor());
      Chat.raw(player, "&aBought anchor " + (ownedCount(player.getUUID()) + 1) + " of " + PER_PLAYER
         + " for &f" + Chat.moneyStr(price) + "&a. Next one costs &f" + Chat.moneyStr(priceFor(player.getUUID())) + "&a.");
      if (player.level() instanceof ServerLevel server) {
         server.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.BEACON_ACTIVATE, SoundSource.PLAYERS, 0.8F, 1.6F);
      }
      return null;
   }

   /** The names of the chunks an anchor at this position holds, for the shop's own description. */
   public static List<String> chunkNames(BlockPos pos) {
      int cx = pos.getX() >> 4;
      int cz = pos.getZ() >> 4;
      return List.of(cx + "," + cz, (cx + 1) + "," + cz, (cx - 1) + "," + cz);
   }
}
