package com.fortuneandfavors.util;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Telling a client about a world it has already changed its mind about.
 *
 * <p>A refusal that happens <i>before</i> vanilla's own check is a refusal vanilla does not
 * know about, and vanilla is the thing that talks to the client. Breaking a block is the
 * case that was found first: the client predicts the break the moment it swings, so its
 * copy of the block is already gone, and the server's answer is either "the block is
 * broken" - in which case nothing needs to be said - or "no", in which case vanilla says so
 * by sending the block back. {@code ServerPlayerGameMode.destroyBlock} does exactly that
 * for the two refusals it owns (a block you cannot destroy, a spawn-protected one): it calls
 * {@code level.sendBlockUpdated(pos, state, state, 3)} and returns false.
 *
 * <p>This mod refuses in front of that, from several layers at once - the claim backstop in
 * the game-mode mixin, the Fabric event that wraps the same call, and the mob/mirage rule on
 * {@code Level.destroyBlock} - and none of them used to say anything to the client.
 *
 * <h2>It is not only blocks</h2>
 * The same shape is in every interaction a client predicts locally, and the answer is the
 * same each time: <b>send the truth about what this decision changed</b>. A client predicts
 * far more than a break:
 *
 * <ul>
 *   <li>a <b>placement</b> - the block appearing where you right-clicked. That is the block
 *       at {@code hit.getBlockPos().relative(face)}, and the clicked block underneath it;</li>
 *   <li>an <b>item use</b> - the swing, the stack, the cursor, the whole menu state. Anything
 *       the server refuses has to be answered with the inventory it actually holds, or the
 *       client keeps the count it predicted;</li>
 *   <li>an <b>attack</b> - the client runs {@code player.attack(target)} itself, so its own
 *       hand is the thing that goes wrong: a refused swing leaves the attacker's client with
 *       a weapon it thinks it damaged and a swing it thinks landed. The victim's health is
 *       not sent for other entities, so the hand is the whole of the truth worth sending;</li>
 *   <li>an <b>open</b> - a refused container is answered by saying nothing, because vanilla
 *       answers that one too (the screen only opens when the server says so).</li>
 * </ul>
 *
 * <p>Every method here is called at the site of a refusal rather than by a sweep, because a
 * refusal is the only moment the two copies are known to disagree - and it is always the
 * same moment: this server said no to something the client had already drawn. Every one of
 * them is best-effort and swallows its own failure: a resync that cannot be sent is a
 * cosmetic problem, and a rare refusal that crashed the tick it happened on would not be.
 */
public final class ClientResync {

   private ClientResync() {
   }

   /**
    * Tells a player's client what their world actually holds at one position.
    *
    * <p>The refusal that started this: a break this mod declined to allow.
    */
   public static void block(ServerPlayer player, BlockPos pos) {
      if (player == null || pos == null) {
         return;
      }
      try {
         if (player.level() instanceof ServerLevel level) {
            player.connection.send(new ClientboundBlockUpdatePacket(level, pos));
         }
      } catch (Throwable ignored) {
         // A resync that cannot be sent is a cosmetic problem; a throw here would be a
         // refused break that crashed the tick it happened on.
      }
   }

   /**
    * The truth about a refused right-click on a block.
    *
    * <p>Two positions, because two can be wrong at once: the face the player was pointing
    * at (a lever, a door, a button - anything whose state their client may have toggled) and
    * the position in front of it, which is where a placement would have appeared. The
    * inventory goes with them: a refused use can also have been a bucket, a flint and steel
    * or a stack the client expected to shrink.
    */
   public static void refusedUse(ServerPlayer player, BlockHitResult hit) {
      if (player == null) {
         return;
      }
      if (hit != null) {
         block(player, hit.getBlockPos());
         block(player, hit.getBlockPos().relative(hit.getDirection()));
      }
      inventory(player);
   }

   /**
    * The truth about a refused item use with nothing under the cursor.
    *
    * <p>There is no block to correct here: an item use that never happened is a hand that
    * still holds what it held, so the whole answer is the menu.
    */
   public static void hand(ServerPlayer player) {
      inventory(player);
   }

   /**
    * The truth about a refused swing.
    *
    * <p>The attacker's own hand and nothing else. The client runs its own {@code attack} on
    * the target before the server has agreed to anything, so what it can be wrong about
    * afterwards is the weapon it thinks it spent - and the target's health is not something
    * a client is told about for other entities in the first place.
    */
   public static void refusedAttack(ServerPlayer attacker) {
      inventory(attacker);
   }

   /**
    * Sends this player the player-inventory state the server actually holds.
    *
    * <p>Full state rather than a diff on purpose: {@code broadcastChanges} compares against
    * what the server last <i>sent</i>, which already matches, so it says nothing about a
    * client that has predicted something of its own since. Only the full list corrects that.
    */
   public static void inventory(ServerPlayer player) {
      if (player == null) {
         return;
      }
      try {
         List<Slot> slots = player.inventoryMenu.slots;
         List<ItemStack> items = new ArrayList<>(slots.size());

         for (Slot slot : slots) {
            items.add(slot.getItem().copy());
         }

         player.connection.send(
            new ClientboundContainerSetContentPacket(
               player.inventoryMenu.containerId,
               player.inventoryMenu.getStateId(),
               items,
               player.inventoryMenu.getCarried().copy()
            )
         );
      } catch (Throwable ignored) {
         // Same rule as above: the refusal already happened and already stands.
      }
   }
}
