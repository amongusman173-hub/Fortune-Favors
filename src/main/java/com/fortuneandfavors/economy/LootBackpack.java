package com.fortuneandfavors.economy;

import com.fortuneandfavors.util.Chat;
import com.fortuneandfavors.util.InventoryHelper;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.component.ItemLore;

/**
 * The Loot Backpack: the one thing an expedition asks you to carry.
 *
 * <p>A chest no longer turns into money the moment it is opened. It hands you its pieces one at a
 * time, and the only place they can go is here - nine pieces, no more. That single constraint is
 * the whole design: every chest is now a question about what you are willing to carry home, a rich
 * piece you cannot fit is a piece you must throw something away for, and the run's payout is the
 * sum of what is actually in the pack when you stand on the pad.
 *
 * <p><b>Nine is a starting number, not a law.</b> The richest chambers of the site occasionally
 * hold a {@link #upgrader() pack upgrader}, and a pack that has been patched holds a whole extra row
 * of loot. The capacity lives on the pack itself rather than in the run's state, which is what makes
 * the patch a find rather than a flag: it travels with the pack, it is visible on the tooltip, and
 * it is lost with the pack when the site takes you.
 *
 * <p>The pack is a renamed bundle with its contents in the ordinary container component, so the
 * pieces are real items that can be read off the tooltip and dropped back into the world; the flag
 * in its custom data is what tells this class (and the join, the exit and the death sweep) which
 * bundle is ours.
 */
public final class LootBackpack {
   /** What a fresh pack holds. Nine, so it is one hotbar row of decisions. */
   public static final int BASE_CAPACITY = 9;
   /** What one upgrader adds. A whole row, so the window grows a row per patch. */
   public static final int UPGRADE_STEP = 9;
   /** The most a pack can ever hold: three patches, and five rows of window. */
   public static final int MAX_CAPACITY = BASE_CAPACITY + 3 * UPGRADE_STEP;
   /** The pack's display name. */
   public static final String NAME = "§6§lLoot Backpack";
   /** The custom-data flag that marks one bundle as a Loot Backpack. */
   public static final String TAG = "ff_loot_backpack";
   /** The custom-data key a pack's own capacity is kept under. */
   public static final String SIZE_TAG = "ff_loot_backpack_size";
   /**
    * The custom-data key a pack's run is stamped under.
    *
    * <p>A pack is not a container, it is a claim: the pieces inside it are paid for when the
    * explorer carries them out, and the pack itself is taken back as they leave. That only holds
    * while the pack and the run are the same thing, and a pack is an ordinary bundle in an ordinary
    * inventory - it can be dropped, chested, or carried through a logout. Stamping the run on it is
    * what makes a pack that has outlived its run worthless: there is no run left to answer for it,
    * so it is destroyed the moment anybody looks at it. See {@link #belongsTo(ItemStack, long)}.
    */
   public static final String RUN_TAG = "ff_loot_backpack_run";
   /** The upgrader's display name, and the flag that identifies one. */
   public static final String UPGRADER_NAME = "§6§lPack Upgrader";
   public static final String UPGRADER_TAG = "ff_pack_upgrader";

   private LootBackpack() {
   }

   /** A fresh, empty pack, at its starting size. */
   public static ItemStack create() {
      ItemStack pack = new ItemStack(Items.BUNDLE);
      pack.set(DataComponents.CUSTOM_NAME, Component.literal(NAME));
      pack.set(DataComponents.CONTAINER, ItemContainerContents.EMPTY);
      CustomData.update(DataComponents.CUSTOM_DATA, pack, tag -> tag.putBoolean(TAG, true));
      size(pack, BASE_CAPACITY);
      refresh(pack);
      return pack;
   }

   /**
    * A fresh, empty pack at a chosen size.
    *
    * <p>The Deep Pockets upgrade's end of {@link #create()}: the entrance hands over a pack that
    * already has the pockets the player has paid for, rather than a base pack they then have to
    * patch. Everything over {@link #BASE_CAPACITY} is still clamped by {@link #MAX_CAPACITY}, so an
    * upgrade can never mint a pack bigger than a pack is allowed to be.
    */
   public static ItemStack create(int capacity) {
      ItemStack pack = create();
      size(pack, capacity);
      refresh(pack);
      return pack;
   }

   /**
    * A fresh, empty pack at a chosen size, stamped with the run it is allowed to be filled by.
    *
    * <p>The stamp ties the pack to the descent rather than to the body: a party shares one id across
    * its members, so four packs answer to one run, and none of them answers to the run before it.
    */
   public static ItemStack create(int capacity, long runId) {
      ItemStack pack = create(capacity);
      stamp(pack, runId);
      return pack;
   }

   /** Writes a run's id onto a pack. Zero is "belongs to no run", which is the same as spent. */
   public static void stamp(ItemStack pack, long runId) {
      if (!is(pack)) {
         return;
      }
      CustomData.update(DataComponents.CUSTOM_DATA, pack, tag -> tag.putLong(RUN_TAG, runId));
   }

   /**
    * Which run a pack was handed out by, or 0 for a pack from before runs were stamped on one.
    *
    * <p>The zero is the deliberate, safe answer: an unstamped pack belongs to no run, so it cannot
    * be filled, opened or paid out for, and the sweep takes it back like any other spent one.
    */
   public static long runOf(ItemStack pack) {
      if (!is(pack)) {
         return 0L;
      }
      CustomData data = pack.get(DataComponents.CUSTOM_DATA);
      return data == null ? 0L : data.copyTag().getLongOr(RUN_TAG, 0L);
   }

   /**
    * Does this pack answer for the run in front of it?
    *
    * <p>Both halves of the question matter. A pack stamped with a run that has ended belongs to
    * nobody, and a body with no live run - the hub, a duel, somebody else's site - has no run for a
    * pack to belong to. Either way the answer is no, and the pack is spent.
    */
   public static boolean belongsTo(ItemStack pack, long liveRunId) {
      return liveRunId != 0L && is(pack) && runOf(pack) == liveRunId;
   }

   /** Is this stack one of ours? */
   public static boolean is(ItemStack stack) {
      if (stack == null || stack.isEmpty() || !stack.is(Items.BUNDLE)) {
         return false;
      }
      CustomData data = stack.get(DataComponents.CUSTOM_DATA);
      return data != null && data.copyTag().getBooleanOr(TAG, false);
   }

   /**
    * How many pieces this pack holds.
    *
    * <p>Read off the pack so that a found patch is a property of the thing you are carrying. An
    * unpatched pack - or one from a build that had no size on it - answers the base capacity, and
    * nothing can be written outside {@link #BASE_CAPACITY}..{@link #MAX_CAPACITY} whatever the tag
    * says.
    */
   public static int capacity(ItemStack pack) {
      if (!is(pack)) {
         return BASE_CAPACITY;
      }
      CustomData data = pack.get(DataComponents.CUSTOM_DATA);
      int stored = data == null ? BASE_CAPACITY : data.copyTag().getIntOr(SIZE_TAG, BASE_CAPACITY);
      return Math.max(BASE_CAPACITY, Math.min(MAX_CAPACITY, stored));
   }

   /**
    * Writes a pack's capacity from outside the pack.
    *
    * <p>The one caller that is not this class is a party: a shared pack's size is a property of the
    * party rather than of whoever happens to be holding it, so every member's pack is resized to the
    * same number whenever the bag changes hands. See {@link PartyManager}.
    */
   public static void setCapacity(ItemStack pack, int capacity) {
      if (!is(pack)) {
         return;
      }
      size(pack, capacity);
      refresh(pack);
   }

   /** Writes a pack's capacity, clamped to what a pack is allowed to be. */
   private static void size(ItemStack pack, int capacity) {
      int wanted = Math.max(BASE_CAPACITY, Math.min(MAX_CAPACITY, capacity));
      CustomData.update(DataComponents.CUSTOM_DATA, pack, tag -> tag.putInt(SIZE_TAG, wanted));
   }

   /**
    * Patches a pack with one more row of room.
    *
    * @return the capacity it now has, or -1 when it is already as big as a pack gets
    */
   public static int upgrade(ItemStack pack) {
      if (!is(pack)) {
         return -1;
      }
      int now = capacity(pack);
      if (now >= MAX_CAPACITY) {
         return -1;
      }
      int next = Math.min(MAX_CAPACITY, now + UPGRADE_STEP);
      size(pack, next);
      refresh(pack);
      return next;
   }

   /** One pack upgrader, as it appears in a rich chest. */
   public static ItemStack upgrader() {
      ItemStack patch = new ItemStack(Items.SHULKER_SHELL);
      patch.set(DataComponents.CUSTOM_NAME, Component.literal(UPGRADER_NAME));
      patch.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      patch.set(
         DataComponents.LORE,
         new ItemLore(List.of(
            Component.literal("§7Stitched into the pack you are carrying:"),
            Component.literal("§7it holds §f" + UPGRADE_STEP + " more pieces§7."),
            Component.literal(""),
            Component.literal("§eClick §7it in the chest to patch the pack."),
            Component.literal("§8A pack can take three patches (" + MAX_CAPACITY + " pieces).")
         ))
      );
      CustomData.update(DataComponents.CUSTOM_DATA, patch, tag -> tag.putBoolean(UPGRADER_TAG, true));
      return patch;
   }

   /** Is this stack a pack upgrader? */
   public static boolean isUpgrader(ItemStack stack) {
      if (stack == null || stack.isEmpty() || !stack.is(Items.SHULKER_SHELL)) {
         return false;
      }
      CustomData data = stack.get(DataComponents.CUSTOM_DATA);
      return data != null && data.copyTag().getBooleanOr(UPGRADER_TAG, false);
   }

   /** What the pack is carrying, in the order it was taken. Copies, never the live stacks. */
   public static List<ItemStack> entries(ItemStack pack) {
      List<ItemStack> out = new ArrayList<>();
      if (pack == null || pack.isEmpty()) {
         return out;
      }
      ItemContainerContents contents = pack.get(DataComponents.CONTAINER);
      if (contents == null) {
         return out;
      }
      for (ItemStack piece : contents.nonEmptyItemCopyStream().toList()) {
         if (!piece.isEmpty()) {
            out.add(piece);
         }
      }
      return out;
   }

   /** Replaces the pack's contents, compacts them, and rewrites the tooltip to match. */
   public static void setEntries(ItemStack pack, List<ItemStack> pieces) {
      List<ItemStack> clean = new ArrayList<>();
      for (ItemStack piece : pieces) {
         if (piece != null && !piece.isEmpty()) {
            clean.add(piece.copy());
         }
      }
      int room = capacity(pack);
      while (clean.size() > room) {
         clean.remove(clean.size() - 1);
      }
      pack.set(
         DataComponents.CONTAINER,
         clean.isEmpty() ? ItemContainerContents.EMPTY : ItemContainerContents.fromItems(clean)
      );
      refresh(pack);
   }

   /** What one piece is worth when it makes it home. */
   public static long valueOf(ItemStack piece) {
      return piece == null || piece.isEmpty() ? 0L : BlockValues.valueOf(piece) * piece.getCount();
   }

   /** What the whole pack is worth. */
   public static long totalValue(ItemStack pack) {
      long total = 0L;
      for (ItemStack piece : entries(pack)) {
         total += valueOf(piece);
      }
      return total;
   }

   /** Whether there is room for one more piece. */
   public static boolean hasRoom(ItemStack pack) {
      return entries(pack).size() < capacity(pack);
   }

   /** Puts one piece in, if it fits. False means the pack is full and something must go. */
   public static boolean add(ItemStack pack, ItemStack piece) {
      if (piece == null || piece.isEmpty()) {
         return false;
      }
      List<ItemStack> current = entries(pack);
      if (current.size() >= capacity(pack)) {
         return false;
      }
      current.add(piece.copy());
      setEntries(pack, current);
      return true;
   }

   /** Takes one piece back out. The empty stack means nothing was there to take. */
   public static ItemStack removeAt(ItemStack pack, int index) {
      List<ItemStack> current = entries(pack);
      if (index < 0 || index >= current.size()) {
         return ItemStack.EMPTY;
      }
      ItemStack taken = current.remove(index);
      setEntries(pack, current);
      return taken;
   }

   /** Rewrites the display name and lore so the pack always says what it is carrying. */
   public static void refresh(ItemStack pack) {
      if (!is(pack)) {
         return;
      }
      List<ItemStack> pieces = entries(pack);
      long worth = totalValue(pack);
      int room = capacity(pack);
      List<Component> lore = new ArrayList<>();
      lore.add(Component.literal("§7Holds §f" + pieces.size() + "§7/" + room + " pieces of loot."));
      if (room > BASE_CAPACITY) {
         int patches = (room - BASE_CAPACITY) / UPGRADE_STEP;
         lore.add(
            Component.literal(
               "§6Patched §8x" + patches + "§6 › §7holds §f" + room + "§7 pieces instead of " + BASE_CAPACITY + "."
            )
         );
      }
      if (pieces.isEmpty()) {
         lore.add(Component.literal("§8Nothing in it yet - loot the chests."));
      } else {
         for (ItemStack piece : pieces) {
            lore.add(Component.literal(
               "§8· §f" + piece.getCount() + "x " + piece.getHoverName().getString()
                  + " §8(§a" + Chat.moneyStr(valueOf(piece)) + "§8)"
            ));
         }
      }
      lore.add(Component.literal("§7Carried out: §a" + Chat.moneyStr(worth)));
      lore.add(Component.literal("§8Lost entirely if the site takes you."));
      lore.add(Component.literal("§8Right-click to open it and throw pieces away."));
      pack.set(DataComponents.LORE, new ItemLore(lore));
   }

   /** The pack this player is carrying, or an empty stack when they have none. */
   public static ItemStack held(ServerPlayer sp) {
      if (sp == null) {
         return ItemStack.EMPTY;
      }
      for (int i = 0; i < sp.getInventory().getContainerSize(); i++) {
         ItemStack stack = sp.getInventory().getItem(i);
         if (is(stack)) {
            return stack;
         }
      }
      return ItemStack.EMPTY;
   }

   /**
    * Gives the player a pack unless they already carry one. Idempotent on purpose: the entrance
    * hands one over on every arrival, and a second arrival in the same life must not mint a second
    * pack - the capacity limit is only a limit if there is exactly one.
    */
   public static void give(ServerPlayer sp) {
      give(sp, BASE_CAPACITY);
   }

   /** The same hand-off, at a starting size - what the entrance actually calls. */
   public static void give(ServerPlayer sp, int capacity) {
      give(sp, capacity, 0L);
   }

   /**
    * The hand-off the entrance makes now: a pack at a chosen size, stamped with the run.
    *
    * <p>A pack already in the explorer's hands is claimed by this run rather than left as a second
    * one beside it - the capacity limit is only a limit if there is exactly one pack, and a pack
    * that was handed out before packs carried run ids has to be adoptable rather than a corpse in
    * the inventory that the sweep would take off them on their way in.
    */
   public static void give(ServerPlayer sp, int capacity, long runId) {
      if (sp == null) {
         return;
      }
      ItemStack existing = held(sp);
      if (!existing.isEmpty()) {
         if (runId != 0L) {
            stamp(existing, runId);
            size(existing, capacity);
            refresh(existing);
         }
         return;
      }
      InventoryHelper.giveOrDrop(sp, create(capacity, runId));
   }

   /**
    * Destroys every pack the player carries that no live run answers for.
    *
    * <p>This is the half of the run binding that cannot be gated on an event: a pack does not have
    * to be opened to be useful, it only has to be *kept*, and the thing that used to reward keeping
    * one was extracting while it sat in a chest. So the inventory is swept instead - four times a
    * second for everybody online, which is a walk over thirty-six slots - and any pack whose run is
    * not the run the body is standing in is taken back where it is found. A stashed pack can still
    * be stashed; it just cannot be spent, and the moment it is looked at it is gone.
    *
    * @return true when something was taken back
    */
   public static boolean reclaim(ServerPlayer sp, long liveRunId) {
      if (sp == null) {
         return false;
      }
      boolean taken = false;
      try {
         for (int i = 0; i < sp.getInventory().getContainerSize(); i++) {
            ItemStack stack = sp.getInventory().getItem(i);
            if (is(stack) && !belongsTo(stack, liveRunId)) {
               sp.getInventory().setItem(i, ItemStack.EMPTY);
               taken = true;
            }
         }
      } catch (Throwable ignored) {
         // A sweep that cannot read an inventory must not stop the run it is sweeping for.
      }
      return taken;
   }

   /** Takes every pack the player carries, wherever it sits. */
   public static void withdraw(ServerPlayer sp) {
      if (sp == null) {
         return;
      }
      try {
         for (int i = 0; i < sp.getInventory().getContainerSize(); i++) {
            if (is(sp.getInventory().getItem(i))) {
               sp.getInventory().setItem(i, ItemStack.EMPTY);
            }
         }
      } catch (Throwable ignored) {
      }
   }
}
