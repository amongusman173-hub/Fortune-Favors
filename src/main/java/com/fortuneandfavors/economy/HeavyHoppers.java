package com.fortuneandfavors.economy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The three hoppers that move items differently from a vanilla hopper.
 *
 * <p>All three are ordinary hoppers with a tag, and all three have their vanilla push/pull cancelled
 * by the hopper mixin and replaced here - the same shape the Item Sorter uses. What separates them
 * is one decision each: the Super Hopper asks nothing and moves a stack a tick, the Splitter asks
 * "left or right?" and alternates, and the Checker Hopper asks "does anybody want this?" and only
 * takes the no. The movement itself - insert into a container, skip a chest shop, respect a claim -
 * is the one {@code insert}/{@code usable} pair at the bottom, so the three cannot drift apart.
 */
public final class HeavyHoppers {
   /** The round-robin cursor per splitter, keyed like every other machine. */
   private static final Map<String, Integer> splitCursor = new LinkedHashMap<>();

   private HeavyHoppers() {
   }

   // ------------------------------------------------------------------ Super Hopper

   /**
    * A hopper with the throttle removed: it pushes what it holds onward and pulls more in, up to a
    * full stack each way, every tick. Its reach is a hopper's reach - the container above and the
    * ones beside it - so it is a drop-in replacement and not a new machine to learn.
    */
   public static void tickSuperHopper(Level level, BlockPos pos) {
      if (level.isClientSide() || !(level.getBlockEntity(pos) instanceof HopperBlockEntity hopper)) {
         return;
      }
      UUID owner = MachineManager.ownerAt(level, pos);
      // The tier is the whole of the Super Hopper's difference from the shop: tier one is the
      // original one-stack-a-tick machine, and every step up moves another stack each way per tick.
      int budget = MachineTuning.hopperBudget(MachineManager.keyFor(level, pos));
      int remaining = budget;

      for (int i = 0; i < hopper.getContainerSize() && budget > 0; i++) {
         ItemStack held = hopper.getItem(i);
         if (held.isEmpty()) {
            continue;
         }
         for (BlockPos dst : outputOrder(level, pos)) {
            Container target = usable(level, dst, owner, hopper);
            if (target == null) {
               continue;
            }
            int sent = insertInto(level, pos, dst, target, held, budget);
            if (sent > 0) {
               budget -= sent;
               if (held.isEmpty()) {
                  hopper.setItem(i, ItemStack.EMPTY);
               }
               target.setChanged();
               hopper.setChanged();
            }
            break;
         }
      }

      int pull = budget;
      for (BlockPos src : sourceOrder(level, pos)) {
         if (pull <= 0) {
            break;
         }
         Container source = usable(level, src, owner, hopper);
         if (source == null || isMachineSource(level, src)) {
            // Never drain a sorter or transfer hopper - they hold items they are still deciding
            // about, and a second machine emptying their buffer is two machines arguing.
            continue;
         }
         for (int i = 0; i < source.getContainerSize() && pull > 0; i++) {
            ItemStack s = source.getItem(i);
            if (s.isEmpty()) {
               continue;
            }
            ItemStack take = s.copyWithCount(Math.min(s.getCount(), pull));
            int moved = insert(hopper, take, pull);
            if (moved > 0) {
               s.shrink(moved);
               pull -= moved;
               source.setChanged();
               hopper.setChanged();
            }
         }
      }

      if (remaining > budget || pull < budget) {
         MachineVfx.pulse(level, pos, MachineManager.TYPE_SUPER_HOPPER);
      }
   }

   // ------------------------------------------------------------------ 2-Way Splitter

   /**
    * Pulls from above and hands one item at a time, alternately, to the two containers beside it -
    * an even split down to the item. The cursor is kept per machine so the alternation survives a
    * tick that had nowhere to put anything (the item is not lost; the next side gets the turn).
    */
   public static void tickSplitter(Level level, BlockPos pos) {
      if (level.isClientSide() || !(level.getBlockEntity(pos) instanceof HopperBlockEntity hopper)) {
         return;
      }
      UUID owner = MachineManager.ownerAt(level, pos);
      Container above = usable(level, pos.above(), owner, hopper);
      if (above != null && !isMachineSource(level, pos.above())) {
         for (int i = 0; i < above.getContainerSize(); i++) {
            ItemStack s = above.getItem(i);
            if (s.isEmpty()) {
               continue;
            }
            ItemStack take = s.copyWithCount(Math.min(s.getCount(), 8));
            int moved = insert(hopper, take, 8);
            if (moved > 0) {
               s.shrink(moved);
               above.setChanged();
               hopper.setChanged();
            }
            break;
         }
      }

      List<BlockPos> sides = splitSides(level, pos, owner, hopper);
      if (sides.isEmpty()) {
         return;
      }
      String key = MachineManager.keyFor(level, pos);
      int cursor = splitCursor.getOrDefault(key, 0);
      for (int i = 0; i < hopper.getContainerSize(); i++) {
         ItemStack s = hopper.getItem(i);
         if (s.isEmpty()) {
            continue;
         }
         BlockPos dst = sides.get(Math.floorMod(cursor, sides.size()));
         Container target = usable(level, dst, owner, hopper);
         if (target == null) {
            cursor++;
            splitCursor.put(key, cursor);
            break;
         }
         ItemStack one = s.copyWithCount(1);
         if (insertInto(level, pos, dst, target, one, 1) > 0) {
            s.shrink(1);
            if (s.isEmpty()) {
               hopper.setItem(i, ItemStack.EMPTY);
            }
            target.setChanged();
            hopper.setChanged();
         }
         cursor++;
         splitCursor.put(key, cursor);
         MachineVfx.pulse(level, pos, MachineManager.TYPE_TWO_WAY_SPLITTER);
         break;
      }
   }

   /** The two containers beside a splitter, in a stable order so the alternation is predictable. */
   private static List<BlockPos> splitSides(Level level, BlockPos pos, UUID owner, HopperBlockEntity self) {
      List<BlockPos> sides = new ArrayList<>();
      for (Direction d : Direction.Plane.HORIZONTAL) {
         BlockPos at = pos.relative(d);
         if (usable(level, at, owner, self) != null) {
            sides.add(at);
            if (sides.size() == 2) {
               break;
            }
         }
      }
      return sides;
   }

   // ------------------------------------------------------------------ Item Checker Hopper

   /**
    * Keeps only the junk: an item goes in only when no placed Item Sorter anywhere names it, so a
    * farm's sorted output is left exactly where it is and the leftovers travel. What it holds it
    * passes on; facing another Checker Hopper, it hands the whole stack over, which is how a chain
    * of them reaches a chest - or a void - across the base without a belt of hoppers between.
    */
   public static void tickCheckerHopper(Level level, BlockPos pos) {
      if (level.isClientSide() || !(level.getBlockEntity(pos) instanceof HopperBlockEntity hopper)) {
         return;
      }
      UUID owner = MachineManager.ownerAt(level, pos);
      // The bin switch: with it on, what nobody wants is destroyed here rather than handed on, so
      // one hopper in a corner is a trash can. See ModEvents for the click that flips it.
      boolean voiding = MachineTuning.voiding(MachineManager.keyFor(level, pos));
      boolean worked = false;

      for (int i = 0; i < hopper.getContainerSize(); i++) {
         ItemStack held = hopper.getItem(i);
         if (held.isEmpty()) {
            continue;
         }
         if (voiding && !ItemSorter.acceptsAnywhere(idOf(held))) {
            hopper.setItem(i, ItemStack.EMPTY);
            hopper.setChanged();
            worked = true;
            continue;
         }
         for (BlockPos dst : outputOrder(level, pos)) {
            Container target = usable(level, dst, owner, hopper);
            if (target == null) {
               continue;
            }
            // Handing to another Checker Hopper is a hand-over, not a drift: the whole stack moves
            // at once, which is what makes a chain of them behave like a teleport rather than a
            // line of slow hoppers.
            int cap = MachineManager.isCheckerHopper(level, dst) ? held.getCount() : 8;
            int sent = insertInto(level, pos, dst, target, held, cap);
            if (sent > 0) {
               if (held.isEmpty()) {
                  hopper.setItem(i, ItemStack.EMPTY);
               }
               target.setChanged();
               hopper.setChanged();
               worked = true;
            }
            break;
         }
      }

      for (BlockPos src : sourceOrder(level, pos)) {
         Container source = usable(level, src, owner, hopper);
         if (source == null || isMachineSource(level, src)) {
            continue;
         }
         for (int i = 0; i < source.getContainerSize(); i++) {
            ItemStack s = source.getItem(i);
            if (s.isEmpty() || ItemSorter.acceptsAnywhere(idOf(s))) {
               continue;
            }
            if (voiding) {
               // Destroyed where it stands: a bin pointed at a chest is how a player empties a
               // chest of everything their sorters ignore without a belt of hoppers to a hole.
               s.shrink(Math.min(s.getCount(), 8));
               source.setChanged();
               hopper.setChanged();
               worked = true;
               break;
            }
            ItemStack take = s.copyWithCount(Math.min(s.getCount(), 8));
            int moved = insert(hopper, take, 8);
            if (moved > 0) {
               s.shrink(moved);
               source.setChanged();
               hopper.setChanged();
               worked = true;
            }
            break;
         }
      }

      if (worked) {
         MachineVfx.pulse(level, pos, MachineManager.TYPE_CHECKER_HOPPER);
      }
   }

   // ------------------------------------------------------------------ shared movement

   /** The containers a hopper hands to: the way it points, then above, then around. */
   private static List<BlockPos> outputOrder(Level level, BlockPos pos) {
      List<BlockPos> order = new ArrayList<>();
      net.minecraft.world.level.block.state.BlockState state = level.getBlockState(pos);
      Direction facing = state.getBlock() instanceof HopperBlock ? state.getValue(HopperBlock.FACING) : Direction.DOWN;
      order.add(pos.relative(facing));
      order.add(pos.above());
      for (Direction d : Direction.Plane.HORIZONTAL) {
         order.add(pos.relative(d));
      }
      return order;
   }

   /**
    * The containers a hopper reads from: above first, then around - but never the block it pushes
    * into.
    *
    * <p>A hopper that fills a container and drains the same container has a loop, not a route, and
    * it is at its worst when the neighbour is a furnace or a Super Smelter: the machine is beside
    * the hopper (not above it, where vanilla only ever reads), so the Super Hopper used to hand the
    * coal into the fuel slot and then pull it straight back out on the very same tick - four coal
    * in, four coal out, forever, and a furnace that never burns and never smelts. The block it
    * faces is the block it fills, so it is the one neighbour that is never a source.
    */
   private static List<BlockPos> sourceOrder(Level level, BlockPos pos) {
      BlockState state = level.getBlockState(pos);
      Direction facing = state.getBlock() instanceof HopperBlock ? state.getValue(HopperBlock.FACING) : Direction.DOWN;
      BlockPos out = pos.relative(facing);
      List<BlockPos> order = new ArrayList<>();
      if (!pos.above().equals(out)) {
         order.add(pos.above());
      }
      for (Direction d : Direction.Plane.HORIZONTAL) {
         BlockPos at = pos.relative(d);
         if (!at.equals(out)) {
            order.add(at);
         }
      }
      return order;
   }

   /** A container a machine may touch, or null - the one place shop and claim rules are asked. */
   private static Container usable(Level level, BlockPos at, UUID owner, HopperBlockEntity self) {
      Container c = ItemSorter.containerAt(level, at);
      if (c == null || c == self) {
         return null;
      }
      if (ChestShopManager.get(level, at) != null || !ClaimManager.canInteract(owner, level, at)) {
         return null;
      }
      return c;
   }

   /** True when a position is a machine that holds items it is still deciding about. */
   private static boolean isMachineSource(Level level, BlockPos at) {
      return MachineManager.isItemSorter(level, at) || MachineManager.isTransferFamily(level, at);
   }

   /**
    * Moves at most {@code cap} of {@code stack} into a target, through the face a hopper pushes from.
    *
    * <p>{@link #insert} fills the first slot with room, which is right for a chest and wrong for
    * everything that is not one. A furnace has three slots with three jobs, and a slot-blind fill
    * pours the coal into the input and the ore into the fuel - or, once the first two are full, into
    * the result slot, where it is never smelted at all. Vanilla hoppers never do that because they
    * ask the container two questions - which slots this face exposes ({@code getSlotsForFace}) and
    * whether it will take the item there ({@code canPlaceItemThroughFace}) - and this is that same
    * pair. So a Super Hopper feeds a furnace the way a plain hopper does: from above into the input,
    * from the side into the fuel, and never into the output.
    */
   private static int insertInto(Level level, BlockPos from, BlockPos to, Container target, ItemStack stack, int cap) {
      // The fallback is never reached: a hopper only ever pushes into a block that shares a face
      // with it, so the delta is always a single step on one axis.
      Direction face = Direction.getNearest(
         from.getX() - to.getX(), from.getY() - to.getY(), from.getZ() - to.getZ(), Direction.UP
      );
      int moved = 0;
      for (int t : slotsFor(target, face)) {
         if (moved >= cap || stack.isEmpty()) {
            break;
         }
         if (!accepts(target, t, stack, face)) {
            continue;
         }
         ItemStack cur = target.getItem(t);
         if (cur.isEmpty()) {
            int take = Math.min(stack.getCount(), cap - moved);
            target.setItem(t, stack.copyWithCount(take));
            moved += take;
            stack.shrink(take);
         } else if (ItemStack.isSameItemSameComponents(cur, stack) && cur.getCount() < cur.getMaxStackSize()) {
            int room = Math.min(cur.getMaxStackSize() - cur.getCount(), Math.min(stack.getCount(), cap - moved));
            if (room <= 0) {
               continue;
            }
            cur.grow(room);
            stack.shrink(room);
            moved += room;
         } else {
            continue;
         }
         target.setChanged();
      }
      return moved;
   }

   /** The slots a container exposes to a body pushing from {@code face}, or all of them. */
   private static int[] slotsFor(Container target, Direction face) {
      if (target instanceof net.minecraft.world.WorldlyContainer wc) {
         int[] slots = wc.getSlotsForFace(face);
         if (slots != null) {
            return slots;
         }
      }
      int n = target.getContainerSize();
      int[] all = new int[n];
      for (int i = 0; i < n; i++) {
         all[i] = i;
      }
      return all;
   }

   /** Whether this container takes this item in this slot, asked the way a hopper asks. */
   private static boolean accepts(Container target, int slot, ItemStack stack, Direction face) {
      if (target instanceof net.minecraft.world.WorldlyContainer wc && !wc.canPlaceItemThroughFace(slot, stack, face)) {
         return false;
      }
      return target.canPlaceItem(slot, stack);
   }

   /** Moves at most {@code cap} of {@code stack} into a container; returns how many moved. */
   private static int insert(Container target, ItemStack stack, int cap) {
      int moved = 0;
      for (int t = 0; t < target.getContainerSize() && moved < cap && !stack.isEmpty(); t++) {
         ItemStack cur = target.getItem(t);
         if (cur.isEmpty()) {
            int take = Math.min(stack.getCount(), cap - moved);
            target.setItem(t, stack.copyWithCount(take));
            moved += take;
            stack.shrink(take);
         } else if (ItemStack.isSameItemSameComponents(cur, stack) && cur.getCount() < cur.getMaxStackSize()) {
            int room = Math.min(cur.getMaxStackSize() - cur.getCount(), Math.min(stack.getCount(), cap - moved));
            if (room <= 0) {
               continue;
            }
            cur.grow(room);
            stack.shrink(room);
            moved += room;
         }
         target.setChanged();
      }
      return moved;
   }

   /** The item id the checker compares against every sorter's filter. */
   private static String idOf(ItemStack stack) {
      return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
   }
}
