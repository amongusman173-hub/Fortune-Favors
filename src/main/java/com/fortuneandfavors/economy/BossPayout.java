package com.fortuneandfavors.economy;

import java.util.Collection;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * How a boss pays its fighters.
 *
 * <p>Two rules, shared so no boss can drift from the others.
 *
 * <p><b>Loot boxes go to the people who fought, not to the floor.</b> A box
 * dropped as an item goes to whoever happens to be standing closest when the dust
 * settles, which is a reward for proximity rather than for fighting. Every boss
 * hands its boxes straight into the inventory of everyone who took part.
 *
 * <p><b>Nothing a boss hands out may be destroyed by a full inventory.</b>
 * {@code placeItemBackInInventory} - what several of these fights used to call -
 * drops the overflow on the ground where a full-inventory player often never sees
 * it. Everything here routes through {@link #giveOrCollect}, which spills into the
 * player's collection box ({@code /claim loot}) instead.
 */
public final class BossPayout {
   /**
    * Loot boxes every boss pays each fighter, on every kill.
    *
    * <p>This is the number the Slime King and the King Wither Skeleton have always
    * used, and the newer fights now match it instead of paying one box for a full
    * clear and two for a phase-three rush. A raid boss's box is the reason people
    * turn up, so the reward should not depend on how efficiently they killed it.
    */
   public static final int BOXES_PER_KILL = 3;

   private BossPayout() {
   }

   /** Hands {@code stack} to the player, putting any overflow into
    *  {@code /claim loot} rather than on the floor. */
   public static void giveOrCollect(ServerPlayer player, ItemStack stack) {
      if (player == null || stack == null || stack.isEmpty()) {
         return;
      }
      ItemStack give = stack.copy();
      player.getInventory().add(give);
      if (!give.isEmpty()) {
         EconomyManager.giveItem(player.getUUID(), give);
      }
   }

   /**
    * Pays every participant {@code boxes} loot boxes, straight into their
    * inventory with overflow collected. Returns how many players were paid.
    */
   public static int payBoxes(
      ServerLevel level, Collection<UUID> participants, Supplier<ItemStack> box, int boxes, String label
   ) {
      if (level == null || level.getServer() == null || participants == null || box == null) {
         return 0;
      }
      int paid = 0;
      for (UUID id : participants) {
         ServerPlayer p = level.getServer().getPlayerList().getPlayer(id);
         if (p == null || !p.isAlive()) {
            continue;
         }
         for (int i = 0; i < boxes; i++) {
            giveOrCollect(p, box.get());
         }
         p.sendOverlayMessage(Component.literal(label + " \u00a78| \u00a7f" + boxes + " delivered to your inventory"));
         paid++;
      }
      return paid;
   }
}

