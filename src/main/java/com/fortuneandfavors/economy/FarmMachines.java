package com.fortuneandfavors.economy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.AABB;

/**
 * The three blocks that make a field run itself: the Auto Planter, the Auto Harvester and the
 * Irrigation Sprinkler.
 *
 * <p>All three are the same shape of machine - a block beside a field, a radius, and one thing it
 * does every second - and all three deliberately share the vanilla farm rather than replacing it:
 * they work on ordinary farmland, ordinary crops and ordinary water, so a farm built for these is
 * still a farm if the blocks are picked up. What they remove is the walking: the sowing, the
 * reaping, and the bucket.
 *
 * <p>Nothing here plants or reaps outside the vanilla rules. The planter needs real farmland under
 * an empty block and a real seed in a real container; the harvester only ever touches a crop that
 * is already mature; the sprinkler does what standing water does, from further away.
 */
public final class FarmMachines {
   /**
    * How far the planter looks for bare farmland, in blocks.
    *
    * <p>Seven is a working field rather than a flowerbed: the first cut of these machines reached
    * four and five blocks, which is a garden the player has to stand in the middle of. A machine
    * that has to be walked around is a machine that has not removed the walking.
    */
   public static final int PLANT_RADIUS = 7;
   /** How far the harvester reaches for ripe crops. */
   public static final int HARVEST_RADIUS = 9;
   /** How far the sprinkler keeps the ground wet. */
   public static final int SPRINKLER_RADIUS = 10;
   /** How far a farm machine looks for the chest it was built to work out of. */
   public static final int CHEST_RADIUS = 3;
   /** One chance in this many, each second, that a watered crop advances a single stage. */
   public static final int GROWTH_ODDS = 3;
   /** How many plantings one second of the Auto Planter is allowed to make. */
   private static final int PLANT_PER_TICK = 6;

   private FarmMachines() {
   }

   /**
    * The integer property a block state actually carries under this name, or null.
    *
    * <p>Every crop and every field in the game names its own age and moisture property, and two
    * properties with the same name and range are still not the same object - a freshly built
    * {@code IntegerProperty.create("age", 0, 7)} answers false to {@code state.hasProperty(...)}
    * and the write that follows it goes nowhere. So the property is read back off the state it is
    * about to be written to, which cannot miss: whatever the block is, this is the one it uses.
    */
   private static IntegerProperty propertyOf(BlockState state, String name) {
      for (net.minecraft.world.level.block.state.properties.Property<?> p : state.getProperties()) {
         if (p instanceof IntegerProperty ip && ip.getName().equals(name)) {
            return ip;
         }
      }
      return null;
   }

   /**
    * One second of the Auto Planter.
    *
    * <p>It looks for farmland with nothing above it, then for a seed it is allowed to use in the
    * container it was built beside, and puts them together. Nether wart is included, on soul sand -
    * a nether farm is still a farm.
    */
   public static void tickPlanter(ServerLevel level, BlockPos pos, UUID owner) {
      List<Container> sources = nearbyContainers(level, pos, owner);
      if (sources.isEmpty()) {
         return;
      }
      int planted = 0;
      for (int dx = -PLANT_RADIUS; dx <= PLANT_RADIUS && planted < PLANT_PER_TICK; dx++) {
         for (int dz = -PLANT_RADIUS; dz <= PLANT_RADIUS && planted < PLANT_PER_TICK; dz++) {
            for (int dy = -1; dy <= 1; dy++) {
               BlockPos soil = pos.offset(dx, dy, dz);
               BlockPos above = soil.above();
               BlockState ground = level.getBlockState(soil);
               if (!level.getBlockState(above).isAir()) {
                  continue;
               }
               if (!ground.is(Blocks.FARMLAND) && !ground.is(Blocks.SOUL_SAND)) {
                  continue;
               }
               // Water in the same cell as a crop would drown it; farmland next to water is fine.
               if (level.getBlockState(above).is(Blocks.WATER)) {
                  continue;
               }
               Block crop = cropFor(ground.is(Blocks.SOUL_SAND));
               if (crop == null) {
                  continue;
               }
               // Land somebody else claimed is theirs to sow: a machine that plants across a border
               // is a machine that griefs with a chest, not a thumb.
               if (!ClaimManager.canInteract(owner, level, above)) {
                  continue;
               }
               if (plant(level, above, crop, sources)) {
                  planted++;
               }
            }
         }
      }
      if (planted > 0) {
         level.sendParticles(ParticleTypes.HAPPY_VILLAGER, pos.getX() + 0.5, pos.getY() + 1.2, pos.getZ() + 0.5, 8, 0.5, 0.3, 0.5, 0.01);
         level.playSound(null, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, SoundEvents.ITEM_PICKUP, SoundSource.BLOCKS, 0.5F, 0.8F);
      }
   }

   /**
    * One second of the Auto Harvester.
    *
    * <p>Every mature crop in its radius is cut and dropped, and the drops are swept into the
    * container the harvester was built beside - which is what makes it pair with the Auto-Sell
    * Hopper rather than with a player's inventory. Left standing they stay as items on the field,
    * which is the honest failure: the machine can only hand over what it can reach.
    */
   public static void tickHarvester(ServerLevel level, BlockPos pos, UUID owner) {
      int cut = 0;
      for (int dx = -HARVEST_RADIUS; dx <= HARVEST_RADIUS; dx++) {
         for (int dz = -HARVEST_RADIUS; dz <= HARVEST_RADIUS; dz++) {
            for (int dy = -1; dy <= 2; dy++) {
               BlockPos p = pos.offset(dx, dy, dz);
               if (!isRipe(level.getBlockState(p)) || !ClaimManager.canInteract(owner, level, p)) {
                  continue;
               }
               // Break it with its own drops, so the vanilla rules (and any mod that watches a
               // harvest) still apply - then collect what landed.
               level.destroyBlock(p, true);
               cut++;
            }
         }
      }
      if (cut > 0) {
         level.sendParticles(ParticleTypes.CRIT, pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, 10, 0.5, 0.4, 0.5, 0.05);
         collect(level, pos, owner);
      }
   }

   /**
    * One second of the Irrigation Sprinkler.
    *
    * <p>It waters the ground it can see - farmland within its radius is set to full moisture every
    * second, which is exactly what a water block in the middle of the field would have done from
    * further away, and is what stops the field drying out when the nearest water is outside the
    * four blocks vanilla checks. Then the water it throws is a shove on whatever is growing: a crop
    * in its radius advances a stage now and then, without ever skipping one.
    *
    * <p>Both halves used to be silent no-ops, because both wrote through freshly built property
    * objects rather than the ones the blocks actually use - see {@link #propertyOf}. The sprinkler
    * looked like a fountain and did nothing; now the moisture write is the one vanilla reads, and
    * the growth write is the crop's own age.
    */
   public static void tickSprinkler(ServerLevel level, BlockPos pos, UUID owner) {
      // A sprinkler is a cauldron with a job, and the world can fill that cauldron: lava poured in
      // turns it into a lava cauldron and snow into a powder-snow one - both different blocks, both
      // of which used to stop the machine being a machine. The sprinkler puts itself back to an
      // empty cauldron instead, because the one thing it is not allowed to be is a casualty of the
      // weather.
      BlockState own = level.getBlockState(pos);
      if (!own.is(Blocks.CAULDRON)) {
         if (own.is(Blocks.LAVA_CAULDRON) || own.is(Blocks.POWDER_SNOW_CAULDRON) || own.is(Blocks.WATER_CAULDRON)) {
            level.setBlock(pos, Blocks.CAULDRON.defaultBlockState()
               .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.LEVEL_CAULDRON, 0), 3);
         }
      }
      boolean any = false;
      for (int dx = -SPRINKLER_RADIUS; dx <= SPRINKLER_RADIUS; dx++) {
         for (int dz = -SPRINKLER_RADIUS; dz <= SPRINKLER_RADIUS; dz++) {
            for (int dy = -2; dy <= 1; dy++) {
               BlockPos p = pos.offset(dx, dy, dz);
               BlockState state = level.getBlockState(p);
               if (state.is(Blocks.FARMLAND)) {
                  IntegerProperty moisture = propertyOf(state, "moisture");
                  if (moisture != null && (Integer)state.getValue(moisture) < 7
                     && ClaimManager.canInteract(owner, level, p)) {
                     level.setBlock(p, state.setValue(moisture, 7), 2);
                     any = true;
                  }
                  if (grow(level, p.above(), owner)) {
                     any = true;
                  }
                  continue;
               }
               if (grow(level, p, owner)) {
                  any = true;
               }
            }
         }
      }
      // The sprinkler's own rain: a fine spray and a drip, on its own clock so a field of these
      // does not turn into a fountain.
      double x = pos.getX() + 0.5;
      double y = pos.getY() + 1.1;
      double z = pos.getZ() + 0.5;
      for (int i = 0; i < 4; i++) {
         level.sendParticles(
            ParticleTypes.SPLASH,
            x + (level.getRandom().nextDouble() - 0.5) * SPRINKLER_RADIUS,
            pos.getY() + 1.6,
            z + (level.getRandom().nextDouble() - 0.5) * SPRINKLER_RADIUS,
            1, 0.2, 0.1, 0.2, 0.0
         );
      }
      level.sendParticles(ParticleTypes.DRIPPING_WATER, x, y, z, 2, 0.25, 0.1, 0.25, 0.0);
      if (any && level.getGameTime() % 60L == 0L) {
         level.playSound(null, x, pos.getY() + 0.5, z, SoundEvents.WATER_AMBIENT, SoundSource.BLOCKS, 0.4F, 1.6F);
      }
   }

   /** Advances one crop by a single stage, with a chance - the sprinkler's growth boost. */
   public static boolean grow(ServerLevel level, BlockPos p, UUID owner) {
      if (!ClaimManager.canInteract(owner, level, p)) {
         return false;
      }
      BlockState state = level.getBlockState(p);
      if (state.getBlock() instanceof CropBlock crop) {
         if (crop.isMaxAge(state) || level.getRandom().nextInt(GROWTH_ODDS) != 0) {
            return false;
         }
         // The crop's own age property, read back off the state: that is the instance the block is
         // holding, so the write lands. (The class's own accessor is protected, and a rebuilt
         // property with the same name and range is not the same object - see propertyOf.)
         IntegerProperty age = propertyOf(state, "age");
         if (age == null) {
            return false;
         }
         int now = (Integer)state.getValue(age);
         if (now < crop.getMaxAge()) {
            level.setBlock(p, state.setValue(age, now + 1), 2);
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, p.getX() + 0.5, p.getY() + 0.4, p.getZ() + 0.5, 2, 0.2, 0.2, 0.2, 0.01);
            return true;
         }
         return false;
      }
      if (state.is(Blocks.NETHER_WART)) {
         IntegerProperty age = propertyOf(state, "age");
         if (age != null && (Integer)state.getValue(age) < 3 && level.getRandom().nextInt(GROWTH_ODDS) == 0) {
            level.setBlock(p, state.setValue(age, (Integer)state.getValue(age) + 1), 2);
            return true;
         }
      }
      return false;
   }

   /** True for a crop standing ready to be cut: a ripe CropBlock, or nether wart at full age. */
   public static boolean isRipe(BlockState state) {
      if (state.getBlock() instanceof CropBlock crop) {
         return crop.isMaxAge(state);
      }
      if (state.is(Blocks.NETHER_WART)) {
         IntegerProperty age = propertyOf(state, "age");
         return age != null && (Integer)state.getValue(age) >= 3;
      }
      return false;
   }

   /** The seed a bare patch of soil should be given - and null when the ground does not match. */
   private static Block cropFor(boolean soulSand) {
      return soulSand ? Blocks.NETHER_WART : null;
   }

   /** Finds a seed in the containers beside the planter and sows it; false when there is none. */
   private static boolean plant(ServerLevel level, BlockPos target, Block crop, List<Container> sources) {
      for (Container c : sources) {
         for (int i = 0; i < c.getContainerSize(); i++) {
            ItemStack stack = c.getItem(i);
            if (stack.isEmpty()) {
               continue;
            }
            BlockState sow = stateFor(stack, crop);
            if (sow == null) {
               continue;
            }
            level.setBlock(target, sow, 2);
            stack.shrink(1);
            if (stack.isEmpty()) {
               c.setItem(i, ItemStack.EMPTY);
            }
            c.setChanged();
            return true;
         }
      }
      return false;
   }

   /**
    * The seed-to-crop table, spelled out rather than inferred.
    *
    * <p>Five crops is a short enough list to write down, and writing it down is what keeps the
    * planter from ever planting something a player did not put in the chest: an item it does not
    * recognise is simply not a seed.
    */
   private static BlockState stateFor(ItemStack stack, Block requested) {
      // Nether wart is the one crop that grows on soul sand instead of farmland.
      if (requested == Blocks.NETHER_WART) {
         return stack.is(Items.NETHER_WART) ? Blocks.NETHER_WART.defaultBlockState() : null;
      }
      if (stack.is(Items.WHEAT_SEEDS)) {
         return Blocks.WHEAT.defaultBlockState();
      }
      if (stack.is(Items.CARROT)) {
         return Blocks.CARROTS.defaultBlockState();
      }
      if (stack.is(Items.POTATO)) {
         return Blocks.POTATOES.defaultBlockState();
      }
      if (stack.is(Items.BEETROOT_SEEDS)) {
         return Blocks.BEETROOTS.defaultBlockState();
      }
      return null;
   }

   /** The containers a machine works out of, for the machine's own window to list. */
   public static List<Container> containersFor(ServerLevel level, BlockPos pos, UUID owner) {
      return nearbyContainers(level, pos, owner);
   }

   /**
    * Every container near the machine that its owner is allowed to touch.
    *
    * <p>It used to be the five positions touching the machine - above and the four sides - which is
    * a chest you have to build against the block, and a chest one block further out, or one level
    * down on the floor, was invisible to it. A player who has put a double chest beside the field
    * and seen the harvester ignore it is not going to move the chest; they are going to say the
    * machine does not work. So it looks in a cube of {@link #CHEST_RADIUS} around itself, nearest
    * first, and takes every container in it that its owner is allowed to touch.
    */
   private static List<Container> nearbyContainers(ServerLevel level, BlockPos pos, UUID owner) {
      List<Container> out = new ArrayList<>();
      List<BlockPos> spots = new ArrayList<>();
      spots.add(pos.above());
      for (Direction d : Direction.Plane.HORIZONTAL) {
         spots.add(pos.relative(d));
      }
      for (int dx = -CHEST_RADIUS; dx <= CHEST_RADIUS; dx++) {
         for (int dy = -CHEST_RADIUS; dy <= CHEST_RADIUS; dy++) {
            for (int dz = -CHEST_RADIUS; dz <= CHEST_RADIUS; dz++) {
               if (dx == 0 && dy == 0 && dz == 0) {
                  continue;
               }
               BlockPos p = pos.offset(dx, dy, dz);
               if (!spots.contains(p)) {
                  spots.add(p);
               }
            }
         }
      }
      // Nearest first, so the chest a player built against the machine is always the one that gets
      // the seed and the harvest, and the cube around it is only ever the overflow.
      spots.sort(java.util.Comparator.comparingDouble(p -> p.distSqr(pos)));
      for (BlockPos p : spots) {
         if (!(level.getBlockEntity(p) instanceof Container c) || ChestShopManager.get(level, p) != null) {
            continue;
         }
         if (!ClaimManager.canInteract(owner, level, p)) {
            continue;
         }
         out.add(c);
      }
      return out;
   }

   /** Sweeps the items the harvester just dropped into the container beside it. */
   private static void collect(ServerLevel level, BlockPos pos, UUID owner) {
      List<Container> targets = nearbyContainers(level, pos, owner);
      if (targets.isEmpty()) {
         return;
      }
      AABB area = new AABB(pos).inflate(HARVEST_RADIUS, 3.0, HARVEST_RADIUS);
      for (ItemEntity entity : level.getEntitiesOfClass(ItemEntity.class, area)) {
         ItemStack stack = entity.getItem();
         if (stack.isEmpty()) {
            continue;
         }
         for (Container c : targets) {
            if (insert(c, stack) && stack.isEmpty()) {
               entity.discard();
               break;
            }
         }
      }
   }

   /** Moves one stack into a container; true when anything at all landed. */
   private static boolean insert(Container target, ItemStack stack) {
      boolean moved = false;
      for (int t = 0; t < target.getContainerSize() && !stack.isEmpty(); t++) {
         ItemStack cur = target.getItem(t);
         if (cur.isEmpty()) {
            target.setItem(t, stack.copy());
            stack.setCount(0);
            moved = true;
         } else if (ItemStack.isSameItemSameComponents(cur, stack) && cur.getCount() < cur.getMaxStackSize()) {
            int room = Math.min(cur.getMaxStackSize() - cur.getCount(), stack.getCount());
            if (room <= 0) {
               continue;
            }
            cur.grow(room);
            stack.shrink(room);
            moved = true;
         }
         target.setChanged();
      }
      return moved;
   }
}
