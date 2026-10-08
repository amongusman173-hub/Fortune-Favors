package com.fortuneandfavors.util;

import com.fortuneandfavors.FortuneFavorsMod;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.fortuneandfavors.util.JsonUtil;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.HolderLookup.Provider;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * Stores a recent snapshot of every player's inventory - taken at death (the
 * pre-death state) and, since the periodic autosave was added, also every few
 * minutes for every online player. Admins can use /ff restore <player> to bring
 * that state back in case the inventory got wiped (e.g. a bug, a clear, or a
 * crash rollback). Snapshots keep their slot layout so restore puts armor and
 * off-hand items back where they belong.
 */
public final class LastInventoryHolder {
   /**
    * How many *older* snapshots are kept per player, on top of the live one.
    *
    * <p>One snapshot is not enough to undo a bad day. A player who dies twice in
    * quick succession - once to a boss and then again on the way back - used to
    * have the second, poorer state overwrite the first, so /ff restore could only
    * ever hand back what they had when they last died rather than what they had
    * before the run went wrong. Keeping a short history means the original kit is
    * still on file after a follow-up death empties a mostly-looted inventory.
    */
   private static final int ARCHIVE_PER_PLAYER = 3;

   /** A snapshot that has been superseded by a newer one. */
   public record Archived(long at, String reason, List<ItemStack> items) {
   }

   private static final Map<UUID, List<ItemStack>> snapshots = new HashMap<>();
   /** Older snapshots, most recently archived first. */
   private static final Map<UUID, List<Archived>> archived = new HashMap<>();
   /** When and why the live snapshot was taken, so the restore screen can date it. */
   private static final Map<UUID, Long> snapshotAt = new HashMap<>();
   private static final Map<UUID, String> snapshotReason = new HashMap<>();
   /** The dimension each player was last seen in, for the pre-teleport snapshot. */
   private static final Map<UUID, ResourceKey<Level>> lastDim = new HashMap<>();
   private static Path dataFile;

   private LastInventoryHolder() {
   }

   /** Full inventory size snapshot covers: main 36 + armor 4 + offhand 1. */
   private static List<ItemStack> capture(ServerPlayer player) {
      List<ItemStack> items = new ArrayList<>();
      for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
         ItemStack s = player.getInventory().getItem(i);
         items.add(s.isEmpty() ? ItemStack.EMPTY : s.copy());
      }
      return items;
   }

   /** A duel loadout is temporary gear handed out by the duel system, not the
    *  player's real inventory. Snapshotting it would let /ff restore hand back a
    *  PvP kit (and its golden apples) as if it were lost survival gear, so no
    *  snapshot is ever taken while a duel is running - not on death, not on
    *  logout, and not by the periodic sweep. The snapshot left on file is the
    *  real inventory the player walked into the duel with. */
   private static boolean isDuel(ServerPlayer player) {
      return com.fortuneandfavors.duel.DuelManager.isInDuel(player.getUUID())
         || com.fortuneandfavors.duel.DuelManager.isDuelRealm(player.level());
   }

   /**
    * True while the player's inventory is <b>temporary</b> - a duel kit or prison uniform.
    *
    * <p>Neither is the player's own gear, and a snapshot taken while they are wearing one is not
    * just useless, it is actively harmful: it becomes the live record, and the next NKI death or
    * {@code /ff restore} hands the prison's own pickaxe back instead of the loot the player walked
    * in with. That is the "I left prison and came back with prison gear, not my loot" report, and
    * the fix is to never let a temporary state on file.
    */
   private static boolean isTemporaryState(ServerPlayer player) {
      return isDuel(player) || com.fortuneandfavors.economy.PrisonManager.isInPrison(player);
   }

   /**
    * Snapshot a player's entire inventory, unconditionally.
    *
    * <p>Only safe to call while the inventory is still whole - it was previously
    * called from the {@code AFTER_DEATH} handler, which runs <i>after</i> vanilla
    * has emptied every slot, so every death overwrote a good snapshot with an
    * empty one. That is why {@code /ff restore} appeared to remember nothing: the
    * recorded state was always the state after the loss, never before it. The call
    * now lives in the death-drop hook, which fires while the slots still exist.
    */
   public static void snapshot(ServerPlayer player) {
      snapshot(player, "death");
   }

   /** As {@link #snapshot(ServerPlayer)}, but records why the state was saved. */
   public static void snapshot(ServerPlayer player, String reason) {
      try {
         if (isTemporaryState(player)) {
            return;
         }
         // Park the outgoing state in the archive first: the call comes from a
         // death hook, and the state being replaced is the one from before this
         // death, which is the more valuable of the two.
         archive(player.getUUID());
         snapshots.put(player.getUUID(), capture(player));
         snapshotAt.put(player.getUUID(), System.currentTimeMillis());
         snapshotReason.put(player.getUUID(), reason);
      } catch (Exception e) {
         FortuneFavorsMod.LOGGER.warn("Failed to snapshot inventory for {}", player.getName().getString(), e);
      }
   }

   /** Moves the live snapshot onto the front of this player's archive. */
   private static void archive(UUID id) {
      List<ItemStack> current = snapshots.get(id);
      if (current == null || itemCount(current) == 0) {
         return;
      }
      List<Archived> list = archived.computeIfAbsent(id, k -> new ArrayList<>());
      list.add(0, new Archived(snapshotAt.getOrDefault(id, System.currentTimeMillis()), snapshotReason.getOrDefault(id, "earlier"), current));
      while (list.size() > ARCHIVE_PER_PLAYER) {
         list.remove(list.size() - 1);
      }
   }

   /** Snapshot every online player whose inventory changed since the last
    *  sweep - called by the periodic autosave so /ff restore always has a
    *  recent state, not just the last death. Idle players (unchanged since the
    *  previous snapshot) are skipped entirely, so the 2-minute sweep does no
    *  allocation work for them. */
   public static void snapshotAll(MinecraftServer server) {
      for (ServerPlayer player : server.getPlayerList().getPlayers()) {
         snapshotIfChanged(player);
      }
   }

   /**
    * Refreshes a player's snapshot, but never downgrades it.
    *
    * <p>Writes only when the inventory actually changed <i>and</i> the new state
    * is not poorer than the stored one. The monotonic rule is the whole point:
    * {@code /ff restore} exists to give back an inventory a player lost, so a sweep
    * that fires while they are standing over their own grave must not overwrite the
    * one record that could still save them. A richer state always wins; an equal or
    * poorer one is ignored until something genuinely better comes along.
    */
   public static void snapshotIfChanged(ServerPlayer player) {
      try {
         if (isTemporaryState(player)) {
            return;
         }
         List<ItemStack> last = snapshots.get(player.getUUID());
         if (last != null && sameInventory(player, last)) {
            return;
         }
         List<ItemStack> now = capture(player);
         if (!mayReplace(last, now)) {
            return;
         }
         archive(player.getUUID());
         snapshots.put(player.getUUID(), now);
         snapshotAt.put(player.getUUID(), System.currentTimeMillis());
         snapshotReason.put(player.getUUID(), "autosave");
      } catch (Exception e) {
         FortuneFavorsMod.LOGGER.warn("Failed to snapshot inventory for {}", player.getName().getString(), e);
      }
   }

   /**
    * Whether {@code candidate} is allowed to replace {@code current}.
    *
    * <p>The whole rule in one place so it can be asserted without a server: a
    * missing snapshot is always replaced, and a candidate must never be poorer
    * than what is already on file. Without that second clause the periodic sweep
    * quietly demolishes the only record that could give a player their kit back.
    */
   public static boolean mayReplace(List<ItemStack> current, List<ItemStack> candidate) {
      return current == null || itemCount(candidate) >= itemCount(current);
   }

   /** Total number of items a snapshot holds, used to decide which of two
    *  captured states is worth keeping. Public so the self-test can assert the
    *  monotonic rule directly. */
   public static int itemCount(List<ItemStack> items) {
      if (items == null) {
         return 0;
      }
      int total = 0;
      for (ItemStack s : items) {
         if (s != null && !s.isEmpty()) {
            total += s.getCount();
         }
      }
      return total;
   }

   /** Slot-by-slot equality between a player's live inventory and a snapshot. */
   private static boolean sameInventory(ServerPlayer player, List<ItemStack> last) {
      int size = player.getInventory().getContainerSize();
      if (last.size() != size) {
         return false;
      }
      for (int i = 0; i < size; i++) {
         ItemStack a = player.getInventory().getItem(i);
         ItemStack b = last.get(i);
         if (a == null || b == null) {
            return false;
         }
         if (a.isEmpty() != b.isEmpty()) {
            return false;
         }
         if (!a.isEmpty() && (!ItemStack.isSameItemSameComponents(a, b) || a.getCount() != b.getCount())) {
            return false;
         }
      }
      return true;
   }

   /**
    * Snapshots the inventory on purpose, moments before something replaces it.
    *
    * <p>{@link #snapshot(ServerPlayer, String)} and {@link #snapshotIfChanged} both
    * refuse to run while a duel or a realm is in progress, and that guard is right
    * about what it protects: the gear a duel hands out is not the player's, and
    * handing a PvP kit back through {@code /ff restore} would be a free kit. But the
    * guard was also swallowing the one capture that matters most - the state taken
    * <i>before</i> the swap. A duel takes your whole inventory and returns it from a
    * field held in memory; if the server dies, or the exit path is missed, the real
    * kit is gone with it. This is the explicit "I am about to replace this, remember
    * it first" call, wired to the two moments that do replace an inventory: the
    * start of a duel, and a trip into another dimension.
    *
    * <p>The outgoing snapshot is archived rather than dropped, so even an event that
    * fires while the live record is richer cannot destroy it - the richer state is
    * still on the player's history, one entry down.
    */
   public static void snapshotBeforeEvent(ServerPlayer player, String reason) {
      try {
         // Prison is excluded here too, even though this is the "capture before a swap" path a duel
         // start needs: entering and leaving the block is itself a dimension change, so without the
         // guard the live record becomes prison gear the moment a prisoner arrives. His real kit is
         // held by the block's own stash, so nothing is lost by skipping it.
         if (com.fortuneandfavors.economy.PrisonManager.isInPrison(player)) {
            return;
         }
         archive(player.getUUID());
         snapshots.put(player.getUUID(), capture(player));
         snapshotAt.put(player.getUUID(), System.currentTimeMillis());
         snapshotReason.put(player.getUUID(), reason);
      } catch (Exception e) {
         FortuneFavorsMod.LOGGER.warn("Failed to snapshot inventory for {}", player.getName().getString(), e);
      }
   }

   /** Records the dimension a player is in, snapshotting when it changes.
    *
    * <p>Moving between dimensions is the other way a mod can quietly eat an
    * inventory: a realm that is torn down while its visitor is inside it takes
    * whatever is in their slots with it. The live dimension per player is compared
    * by {@code ResourceKey} identity, so the common case - standing still - costs
    * one map lookup and one reference compare, and never allocates.
    */
   public static void noteDimension(ServerPlayer player, ResourceKey<Level> dim) {
      ResourceKey<Level> prev = lastDim.put(player.getUUID(), dim);
      if (prev != null && prev != dim) {
         snapshotBeforeEvent(player, "before dimension change");
      }
   }

   /** Forgets a player's tracked dimension, so a rejoin snapshots rather than
    *  comparing against a stale key. */
   public static void forgetDimension(UUID id) {
      lastDim.remove(id);
   }

   /**
    * Every snapshot on file for a player, newest first, each slot-indexed and
    * copied: index 0 is the live one, then the archived ones most recent first.
    *
    * <p>This is what the restore screen lists, so an admin can see that the kit
    * from before last night's run is still recoverable even after a second death
    * has overwritten the live state with an empty inventory.
    */
   public static List<SnapshotEntry> history(ServerPlayer target) {
      if (target == null) {
         return List.of();
      }
      List<SnapshotEntry> out = new ArrayList<>();
      List<ItemStack> live = snapshots.get(target.getUUID());
      if (live != null && itemCount(live) > 0) {
         out.add(
            new SnapshotEntry(
               snapshotAt.getOrDefault(target.getUUID(), 0L),
               snapshotReason.getOrDefault(target.getUUID(), "saved"),
               itemCount(live),
               copyOf(live)
            )
         );
      }
      List<Archived> older = archived.get(target.getUUID());
      if (older != null) {
         for (Archived a : older) {
            if (a != null && itemCount(a.items()) > 0) {
               out.add(new SnapshotEntry(a.at(), a.reason(), itemCount(a.items()), copyOf(a.items())));
            }
         }
      }
      return out;
   }

   /** One row of {@link #history(ServerPlayer)}: when it was taken, why, and how
    *  many items it holds. */
   public record SnapshotEntry(long at, String reason, int count, List<ItemStack> items) {
      /** A short human label: how long ago it was taken and why. */
      public String label() {
         return ageLabel(at) + " · " + reason + " · " + count + " item(s)";
      }
   }

   private static String ageLabel(long at) {
      if (at <= 0L) {
         return "unknown";
      }
      long mins = Math.max(0L, (System.currentTimeMillis() - at) / 60000L);
      if (mins < 1L) {
         return "just now";
      }
      if (mins < 60L) {
         return mins + "m ago";
      }
      long hours = mins / 60L;
      return hours < 24L ? hours + "h ago" : (hours / 24L) + "d ago";
   }

   private static List<ItemStack> copyOf(List<ItemStack> items) {
      List<ItemStack> out = new ArrayList<>(items.size());
      for (ItemStack s : items) {
         out.add(s == null ? ItemStack.EMPTY : s.copy());
      }
      return out;
   }

   /** Non-destructive preview: how many non-empty slots the stored snapshot
    *  holds for this player, or -1 if there is no snapshot at all. */
   public static int previewCount(ServerPlayer target) {
      List<ItemStack> items = snapshots.get(target.getUUID());
      if (items == null) {
         return -1;
      }
      int count = 0;
      for (ItemStack s : items) {
         if (s != null && !s.isEmpty()) {
            count += s.getCount();
         }
      }
      return count;
   }

   /**
    * The stored snapshot as slot-indexed copies, or null when there is none.
    *
    * <p>Named for the "what did this player look like a moment ago" question - it
    * is how a gear servant dresses itself in a copy of a dead player's kit - but
    * it is the same store that backs the /ff restore preview GUI, so the admin
    * view and the cosmetic one can never disagree.
    */
   public static List<ItemStack> lastKnown(ServerPlayer target) {
      return target == null ? null : previewItems(target);
   }

   /** The stored snapshot as slot-indexed copies, or null when there is none.
    *  Backs the /ff restore preview GUI so an admin can SEE what is about to
    *  come back before committing to it. */
   public static List<ItemStack> previewItems(ServerPlayer target) {
      if (target == null) {
         return null;
      }
      List<ItemStack> items = snapshots.get(target.getUUID());
      if (items == null) {
         return null;
      }
      List<ItemStack> out = new ArrayList<>(items.size());
      for (ItemStack s : items) {
         out.add(s == null ? ItemStack.EMPTY : s.copy());
      }
      return out;
   }

   /** Restore a player's last-known inventory WITHOUT wiping what they already
    *  hold. Each snapshot item goes back to its original slot when that slot is
    *  empty or already holds the same item (counts are topped up); otherwise it
    *  lands in the first free slot. Current items are never removed, so a stale
    *  snapshot (or items restored from graves a moment earlier) can never be
    *  destroyed by /ff restore. Returns total items restored, or -1 if no
    *  snapshot exists. */
   public static int restore(ServerPlayer target) {
      return restore(target, 0);
   }

   /**
    * Puts one snapshot back exactly, replacing whatever the player is holding.
    *
    * <p>{@link #restore(ServerPlayer, int)} is deliberately additive: it never
    * removes a slot, because it is the escape hatch for gear lost to a bug or a
    * grave and destroying the leftovers would be the worst possible outcome. That is
    * the wrong default when the point is to put a player back to a known state: a
    * half-restored inventory with the broken remainder still in it is worse than
    * either end state, and it is exactly what "restore me, my inventory is ruined"
    * asks to be rid of. This clears the slots first, then writes the snapshot back
    * slot for slot, so the result is the snapshot and nothing else.
    *
    * @return the number of items written, or -1 when there is no snapshot at that index
    */
   public static int restoreOverwrite(ServerPlayer target, int index) {
      if (target == null) {
         return -1;
      }
      java.util.List<SnapshotEntry> entries = history(target);
      if (index < 0 || index >= entries.size()) {
         return -1;
      }
      java.util.List<ItemStack> items = entries.get(index).items();
      if (items == null) {
         return -1;
      }
      net.minecraft.world.entity.player.Inventory inv = target.getInventory();
      inv.clearContent();
      int given = 0;
      int size = Math.min(items.size(), inv.getContainerSize());
      for (int i = 0; i < size; i++) {
         ItemStack s = items.get(i);
         if (s == null || s.isEmpty()) {
            inv.setItem(i, ItemStack.EMPTY);
         } else {
            inv.setItem(i, s.copy());
            given += s.getCount();
         }
      }
      // A snapshot wider than the inventory (an older, larger layout) still keeps
      // every item: the overflow is stacked into the free slots rather than lost.
      for (int i = size; i < items.size(); i++) {
         ItemStack s = items.get(i);
         if (s != null && !s.isEmpty()) {
            given += giveIntoFree(inv, s.copy());
         }
      }
      inv.setChanged();
      return given;
   }

   /**
    * Restores one snapshot from this player's history: 0 is the newest, 1 the one
    * before it, and so on. Returns the number of items given back, or -1 when
    * there is no snapshot at that index.
    */
   public static int restore(ServerPlayer target, int index) {
      List<SnapshotEntry> entries = history(target);
      if (index < 0 || index >= entries.size()) {
         return -1;
      }
      List<ItemStack> items = entries.get(index).items();
      if (items == null) {
         return -1;
      }
      net.minecraft.world.entity.player.Inventory inv = target.getInventory();
      int given = 0;
      int size = Math.min(items.size(), inv.getContainerSize());
      for (int i = 0; i < size; i++) {
         ItemStack s = items.get(i);
         if (s == null || s.isEmpty()) {
            continue;
         }
         ItemStack want = s.copy();
         ItemStack current = inv.getItem(i);
         if (current.isEmpty()) {
            inv.setItem(i, want);
            given += want.getCount();
         } else if (ItemStack.isSameItemSameComponents(current, want)) {
            int add = Math.min(current.getMaxStackSize() - current.getCount(), want.getCount());
            if (add > 0) {
               current.grow(add);
               given += add;
               want.shrink(add);
            }
            if (!want.isEmpty()) {
               given += giveIntoFree(inv, want);
            }
         } else {
            given += giveIntoFree(inv, want);
         }
      }
      inv.setChanged();
      return given;
   }

   /** Stacks {@code stack} into matching stacks or the first free slots of the
    *  inventory. Never clears anything. Returns how many items were placed. */
   private static int giveIntoFree(net.minecraft.world.entity.player.Inventory inv, ItemStack stack) {
      int given = 0;
      for (int i = 0; i < inv.getContainerSize() && !stack.isEmpty(); i++) {
         ItemStack cur = inv.getItem(i);
         if (cur.isEmpty()) {
            inv.setItem(i, stack.copy());
            given += stack.getCount();
            stack.setCount(0);
         } else if (ItemStack.isSameItemSameComponents(cur, stack)) {
            int add = Math.min(cur.getMaxStackSize() - cur.getCount(), stack.getCount());
            if (add > 0) {
               cur.grow(add);
               given += add;
               stack.shrink(add);
            }
         }
      }
      return given;
   }

   public static void load(MinecraftServer server) {
      snapshots.clear();
      dataFile = com.fortuneandfavors.economy.EconomyManager.getDataDir(server).resolve("last_inventories.json");
      JsonObject root = JsonUtil.readOrCreate(dataFile, new JsonObject());
      Provider access = server.registryAccess();
      if (root.has("players") && root.get("players").isJsonObject()) {
         for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("players").entrySet()) {
            try {
               UUID uuid = UUID.fromString(e.getKey());
               // Rebuild a full slot-indexed list. New format entries are
               // {slot, item} objects; the old format was a plain item array.
               List<ItemStack> items = new ArrayList<>();
               if (e.getValue().isJsonArray()) {
                  for (JsonElement el : e.getValue().getAsJsonArray()) {
                     if (el.isJsonObject() && el.getAsJsonObject().has("slot") && el.getAsJsonObject().has("item")) {
                        int slot = el.getAsJsonObject().get("slot").getAsInt();
                        ItemStack s = JsonUtil.jsonToItem(el.getAsJsonObject().get("item"), access);
                        while (items.size() <= slot) {
                           items.add(ItemStack.EMPTY);
                        }
                        items.set(slot, s.isEmpty() ? ItemStack.EMPTY : s);
                     } else {
                        ItemStack s = JsonUtil.jsonToItem(el, access);
                        items.add(s.isEmpty() ? ItemStack.EMPTY : s);
                     }
                  }
               }
               if (!items.isEmpty()) {
                  snapshots.put(uuid, items);
               }
            } catch (Exception ignored) {
            }
         }
      }

      // The archive of older snapshots. Absent in files written before the
      // history existed, which is fine: an older file simply has no history to
      // offer yet and will grow one from the next death.
      if (root.has("archive") && root.get("archive").isJsonObject()) {
         for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("archive").entrySet()) {
            try {
               UUID uuid = UUID.fromString(e.getKey());
               List<Archived> list = new ArrayList<>();
               if (e.getValue().isJsonArray()) {
                  for (JsonElement el : e.getValue().getAsJsonArray()) {
                     if (!el.isJsonObject()) {
                        continue;
                     }
                     JsonObject entry = el.getAsJsonObject();
                     long at = entry.has("at") ? entry.get("at").getAsLong() : 0L;
                     String reason = entry.has("reason") ? entry.get("reason").getAsString() : "earlier";
                     List<ItemStack> items = new ArrayList<>();
                     if (entry.has("items") && entry.get("items").isJsonArray()) {
                        for (JsonElement it : entry.getAsJsonArray("items")) {
                           if (it.isJsonObject() && it.getAsJsonObject().has("slot")) {
                              int slot = it.getAsJsonObject().get("slot").getAsInt();
                              ItemStack s = JsonUtil.jsonToItem(it.getAsJsonObject().get("item"), access);
                              while (items.size() <= slot) {
                                 items.add(ItemStack.EMPTY);
                              }
                              items.set(slot, s.isEmpty() ? ItemStack.EMPTY : s);
                           }
                        }
                     }
                     if (itemCount(items) > 0) {
                        list.add(new Archived(at, reason, items));
                     }
                  }
               }
               if (!list.isEmpty()) {
                  archived.put(uuid, list);
               }
            } catch (Exception ignored) {
            }
         }
      }
   }

   public static void save(MinecraftServer server) {
      if (dataFile == null) {
         dataFile = com.fortuneandfavors.economy.EconomyManager.getDataDir(server).resolve("last_inventories.json");
      }
      JsonObject root = new JsonObject();
      JsonObject players = new JsonObject();
      Provider access = server.registryAccess();
      for (Map.Entry<UUID, List<ItemStack>> e : snapshots.entrySet()) {
         JsonArray arr = new JsonArray();
         List<ItemStack> items = e.getValue();
         for (int i = 0; i < items.size(); i++) {
            ItemStack s = items.get(i);
            if (s != null && !s.isEmpty()) {
               JsonObject entry = new JsonObject();
               entry.addProperty("slot", i);
               JsonElement item = JsonUtil.itemToJson(s, access);
               if (item != null) {
                  entry.add("item", item);
                  arr.add(entry);
               }
            }
         }
         if (!arr.isEmpty()) {
            players.add(e.getKey().toString(), arr);
         }
      }
      root.add("players", players);

      // Older snapshots, so a second death cannot wipe out the only record of a
      // player's real kit.
      JsonObject arch = new JsonObject();
      for (Map.Entry<UUID, List<Archived>> e : archived.entrySet()) {
         JsonArray list = new JsonArray();
         for (Archived a : e.getValue()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("at", a.at());
            entry.addProperty("reason", a.reason());
            JsonArray items = new JsonArray();
            List<ItemStack> its = a.items();
            if (its != null) {
               for (int i = 0; i < its.size(); i++) {
                  ItemStack s = its.get(i);
                  if (s == null || s.isEmpty()) {
                     continue;
                  }
                  JsonElement item = JsonUtil.itemToJson(s, access);
                  if (item != null) {
                     JsonObject slotEntry = new JsonObject();
                     slotEntry.addProperty("slot", i);
                     slotEntry.add("item", item);
                     items.add(slotEntry);
                  }
               }
            }
            if (!items.isEmpty()) {
               entry.add("items", items);
               list.add(entry);
            }
         }
         if (!list.isEmpty()) {
            arch.add(e.getKey().toString(), list);
         }
      }
      root.add("archive", arch);
      JsonUtil.write(dataFile, root);
   }
}
