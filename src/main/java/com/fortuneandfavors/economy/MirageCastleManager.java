package com.fortuneandfavors.economy;

import com.fortuneandfavors.ModItems;
import com.fortuneandfavors.duel.DuelManager;
import com.fortuneandfavors.util.Chat;
import com.fortuneandfavors.util.JsonUtil;
import com.fortuneandfavors.util.Safe;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Ravager;
import net.minecraft.world.entity.monster.illager.Pillager;
import net.minecraft.world.entity.monster.illager.Vindicator;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The Mirage Castle: a great keep that is not really there, and that takes itself away.
 *
 * <h2>Why this is its own class</h2>
 * Every other event changes a number - damage, spawn rate, sell price, weather. This one
 * <i>puts blocks into somebody's world</i>, which makes it the only event that can leave a
 * mark on a save file. So the two halves that matter are not the building: they are the
 * undo list, and the fact that the undo list is written down <b>before</b> the first block
 * is placed.
 *
 * <p>Everything it places is recorded with the block that was there before, in memory and
 * on disk. That is what makes all four of these the same operation:
 *
 * <ul>
 *   <li>the event ending normally;</li>
 *   <li>an operator calling it off, or starting a different event on top of it;</li>
 *   <li>the server being stopped or killed mid-event - the next boot finds the file and
 *       finishes the job before anybody can see the leftovers;</li>
 *   <li>a single-player world being closed and reopened - the same file, the same sweep.</li>
 * </ul>
 *
 * <p>What it cannot restore is a block a player changed <i>inside</i> the castle after it
 * rose: the undo puts back what was there when the castle appeared, which is the honest
 * meaning of "it was never here". Blocks that cannot be broken at all (bedrock, barriers)
 * are never overwritten and never claimed, so the undo list only ever names blocks it is
 * allowed to put back.
 *
 * <h2>It rises, one tick at a time</h2>
 * A hundred blocks corner to corner is around fifty thousand blocks, and a single
 * {@code setBlock} is not free: it lights the change, dirties the chunk and tells every
 * client. Placing that in one tick would stall the server for seconds and be visible as a
 * freeze rather than as a castle. So the plan is computed in full, written to disk, and
 * then <b>risen</b> at {@link #RISE_PER_TICK} blocks a tick - which is the same figure the
 * explosion rebuild queue proved it can sustain, and which reads as the thing appearing
 * out of the air rather than as a truck arriving.
 *
 * <h2>What it is when it is up</h2>
 * A curtain wall with four towers and a gatehouse, a three-storey keep of twenty-seven
 * rooms, two wings of eight more, a courtyard of gardens and fountains, and loot in every
 * room that had one. Two rules are enforced rather than hinted at:
 *
 * <ul>
 *   <li><b>Nothing here can be broken</b> while it stands ({@link #isProtected}), because a
 *       castle anybody can mine is a free quarry instead of an event, and the pieces have to
 *       still be there for the undo to be exact. Explosions are refused the same way;</li>
 *   <li><b>The guards are mirage too.</b> A Royal Guard that dies turns to dust where it
 *       stood: no drops, no experience, no body. They are the castle's furniture, and the
 *       castle is not real.</li>
 * </ul>
 *
 * <p>And the one rule that makes the place survivable: a <b>fatal blow inside the castle does
 * not kill you</b>. It puts you back at your own spawn point instead (see
 * {@link #onLethalDamage}), because an unbreakable box full of armed guards with no way out
 * but dying is not an event, it is a trap.
 */
public final class MirageCastleManager {

   private static final Random RANDOM = new Random();

   /** Footprint, corner to corner. The old one was 13. */
   private static final int SIZE = 101;
   private static final int WALL_HEIGHT = 14;
   private static final int TOWER_SIZE = 7;
   private static final int TOWER_HEIGHT = 30;
   /** The keep's footprint, corner to corner. */
   private static final int KEEP_SIZE = 41;
   private static final int KEEP_HEIGHT = 22;
   /** A storey is this tall, and the keep has three of them. */
   private static final int STOREY = 7;
   /** How many blocks rise per tick while it appears. */
   private static final int RISE_PER_TICK = 600;
   private static final String PLAN_FILE = "mirage_castle.json";
   /** Every guard this castle raised carries it, so dust and no drops are one lookup. */
   public static final String GUARD_TAG = "ff_mirage_guard";
   /** The elite that only walks when the whole Royal Guard is on the floor. */
   public static final String CAPTAIN_TAG = "ff_mirage_captain";
   /** How many extra guards each player beyond the first brings in. */
   private static final int GUARDS_PER_EXTRA_PLAYER = 3;
   /** The elite captain, once, after the guard has been cleared. */
   private static UUID captain = null;
   private static boolean captainSpent = false;

   /** What the castle is standing in, and where its corner is. */
   private static ServerLevel standing = null;
   private static BlockPos origin = null;
   /** Everything the castle will touch, in the order it touches it. */
   private static final List<Place> PLAN = new ArrayList<>();
   /** The same positions, for the protection lookup and for dedupe while designing. */
   private static final Map<BlockPos, Integer> INDEX = new HashMap<>();
   private static int risen = 0;
   private static boolean settled = false;
   private static final List<UUID> GUARDS = new ArrayList<>();
   private static final List<Spot> LOOT = new ArrayList<>();
   /**
    * What the castle will not pay out, in one list.
    *
    * <p>The elytra is the one item that answers both of this structure's rules at once: it can
    * leave a hall nobody can break out of, and it can carry the loot home from a fight that was
    * meant to be walked back out of. It was never in a chest here by design - but "by design" was
    * a comment, and a comment cannot take one back out of a chest, because a chest's contents are
    * part of the save. So the rule is enforced twice: every stack is filtered as the loot is built,
    * and every chest the design recorded is swept again once the castle is standing, so a chest
    * stocked by an older rule (or by a player) does not keep it.
    */
   private static final List<Item> CASTLE_WITHHOLDS = List.of(Items.ELYTRA);
   /** Every stair tread the design laid out, and every column it opened through a floor. */
   private static final List<BlockPos> TREADS = new ArrayList<>();
   private static final List<BlockPos> PUNCHES = new ArrayList<>();

   /**
    * The throne hall's state. The King's hall lives in the castle now, which is what makes him
    * findable: an expedition could put one in any chunk of a maze nobody had walked yet, and the
    * hall - and the King in it - went with the run. A castle is one place, and it is announced
    * when it rises.
    */
   private static BlockPos kingSeat = null;
   private static final List<BlockPos> THRONE_POSTS = new ArrayList<>();
   private static UUID kingId = null;
   private static final List<UUID> THRONE_GUARDS = new ArrayList<>();
   private static boolean kingAwake = false;
   private static boolean kingPaid = false;
   private static boolean throneWoke = false;
   private static int kingLine = 0;
   private static long kingNextLine = 0L;
   private static final List<BlockPos> GUARD_SPOTS = new ArrayList<>();
   private static long lastAmbience = 0L;

   // ------------------------------------------------------------------ the collapse

   /**
    * How long everybody has to be outside the walls once the monarch falls.
    *
    * <p>Sixty seconds, and it is a deadline rather than an ambience because the castle is a
    * mirage: it was never really here, and the moment the thing it was holding up - the King -
    * is gone there is nothing left holding it. The countdown is on everybody's action bar from
    * the first tick, the walls come down around them the whole time, and the field it leaves is
    * exactly the field that was there before it rose.
    */
   public static final int COLLAPSE_ESCAPE_TICKS = 1200;
   /**
    * How fast the castle actually comes down, as a multiple of the even pace.
    *
    * <p>The clock is the deadline and stays sixty seconds, but a fall that takes exactly those
    * sixty seconds to remove the last block is a fall that spends its whole second half as a hole
    * with particles in it. The wave is paced at {@link #COLLAPSE_PACE} times the even rate, so the
    * last of the stonework is gone with about a fifth of the window left and what remains of the
    * escape is the part that cannot be walked away from anyway: the light coming out of the seams,
    * the rumbling, and then the blast. The report was "the fade is too slow", and this is the
    * number that decides that, in one place.
    */
   public static final double COLLAPSE_PACE = 1.45;
   /** The slowest the wave is ever allowed to be: blocks per tick, before the intensity. */
   private static final int COLLAPSE_BREAKS_PER_TICK = 14;
   /** And the fastest, so a big plan cannot turn one tick into a two-second stall. */
   private static final int COLLAPSE_BREAKS_MAX = 220;
   /** Ticks between the blasts that walk the halls while it falls. */
   private static final int COLLAPSE_BLAST_TICKS = 20;
   /** Ticks between action-bar updates. */
   private static final int COLLAPSE_BAR_TICKS = 10;
   /** Ticks between the shockwave rings that walk out of the walls. */
   private static final int COLLAPSE_RING_TICKS = 12;
   /** Ticks between the sweeps that take back anything a chest spilled. */
   private static final int COLLAPSE_SWEEP_TICKS = 10;
   /**
    * The fall has three intensities rather than one, so the last seconds are unmistakably the
    * last seconds: a wall giving way here and there, then the walls coming down in earnest, then
    * everything at once with light coming out of the seams. Each one is a number of ticks left
    * on the clock, so the phase and the deadline can never disagree.
    */
   private static final int COLLAPSE_HEAVY_AT = 1000;
   private static final int COLLAPSE_FINAL_AT = 420;
   /** The seconds the clock announces in chat as well as on the bar. */
   private static final int[] COLLAPSE_MARKS = {30, 10, 5, 3, 2, 1};
   /**
    * The mood of a hall that is coming down: slowness, hunger and mining fatigue.
    *
    * <p>Re-applied on a clock rather than set once at the start, and with a duration longer than
    * the gap between writes. Both halves are load-bearing: a duration shorter than the gap makes
    * the effect flicker, and a single write means a milk bucket, a death or a dimension change is
    * an answer to being buried in a collapsing castle. The duration laps the gap by nearly a
    * minute, so even a lagging tick or a stalled server cannot let it fall off.
    */
   private static final int COLLAPSE_MOOD_TICKS = 60;
   private static final int COLLAPSE_MOOD_DURATION = 140;
   /** Ticks between the flashes that answer the walls from above. */
   private static final int COLLAPSE_FLASH_TICKS = 50;
   /** Ticks between the deep rumbles under the floor. */
   private static final int COLLAPSE_RUMBLE_TICKS = 30;
   /** How far ahead of the wave's cursor a block may be taken: what makes the edge ragged. */
   private static final int WAVE_SCATTER = 12;
   /**
    * The bodies the fall is about to take, so the castle's own rescue stands aside for them.
    *
    * <p>Falling in the fight and being let go by the mirage is the castle's rule and it stays
    * one: a fatal blow teleports you home. This is the single exception, and it is the opposite
    * promise - staying inside something that is being taken away is how you go with it - so the
    * rule is stood down for exactly the bodies in this set, for exactly the tick of the fall.
    */
   private static final java.util.Set<UUID> FATED = new java.util.HashSet<>();
   private static boolean collapsing = false;
   private static int collapseTicks = 0;
   private static int collapseBarClock = 0;
   private static int collapseMarked = 0;
   /** The castle's blocks in the order the fall takes them: the middle first, the top first. */
   private static final List<Place> WAVE = new ArrayList<>();
   private static int waveCursor = 0;

   /** The effects the fall puts on everybody in the hall - read by a check and a command. */
   public static List<String> collapseMoodNames() {
      return List.of("slowness", "hunger", "mining_fatigue");
   }

   /** Ticks between mood re-applications, and how long each one lasts. */
   public static int moodReapplyTicks() {
      return COLLAPSE_MOOD_TICKS;
   }

   public static int moodDurationTicks() {
      return COLLAPSE_MOOD_DURATION;
   }

   /**
    * The order the fall takes the castle apart in.
    *
    * <p>The break itself used to be a random sample of the plan, which looks like the castle
    * dissolving evenly - the same grey noise everywhere, which is not what a structure coming down
    * looks like. This is the other reading: a band of the plan at a time, <b>from the middle
    * outwards</b> (the hall the King died in goes first, and the ring of walls follows it), and
    * within a band <b>from the top down</b>, so each floor drops onto the one below instead of the
    * whole castle thinning at once. The scatter in {@link #nextToFall} keeps the edge ragged.
    */
   private static void buildWave() {
      WAVE.clear();
      waveCursor = 0;
      double cx = origin.getX() + SIZE / 2.0;
      double cz = origin.getZ() + SIZE / 2.0;
      List<Place> order = new ArrayList<>(PLAN);
      order.sort((a, b) -> {
         int bandA = bandOf(a.pos, cx, cz);
         int bandB = bandOf(b.pos, cx, cz);
         if (bandA != bandB) {
            return Integer.compare(bandA, bandB);
         }
         return Integer.compare(b.pos.getY(), a.pos.getY());
      });
      WAVE.addAll(order);
   }

   /** Which ring of the fall a block belongs to: six blocks wide, counted from the middle. */
   private static int bandOf(BlockPos pos, double cx, double cz) {
      double dx = pos.getX() + 0.5 - cx;
      double dz = pos.getZ() + 0.5 - cz;
      return (int)(Math.sqrt(dx * dx + dz * dz) / 5.0);
   }

   /**
    * The next block the wave takes.
    *
    * <p>The cursor, plus up to {@link #WAVE_SCATTER} blocks of scatter so the boundary is ragged
    * rather than a perfect ring. Blocks the scatter jumped over are not lost: once the cursor is
    * past the end of the wave they are taken at random, because a castle that leaves a wall
    * standing because a cursor ran out is a bug with a schedule. That fallback is also what keeps
    * the plan's leftovers (a chunk that was unloaded when its turn came) from surviving the fall.
    */
   private static Place nextToFall() {
      if (WAVE.isEmpty() || waveCursor >= WAVE.size()) {
         return PLAN.isEmpty() ? null : PLAN.get(RANDOM.nextInt(PLAN.size()));
      }
      int index = Math.min(WAVE.size() - 1, waveCursor + RANDOM.nextInt(WAVE_SCATTER));
      waveCursor++;
      return WAVE.get(index);
   }

   /** True while the castle is coming down - the escape window. */
   public static boolean isCollapsing() {
      return collapsing;
   }

   /**
    * Seconds left in the escape window, for the action bar and for a test.
    *
    * <p>Gated on the collapse actually running rather than on the tick counter: the counter is
    * kept between falls so the next one does not start from a stale number, and a clock that
    * reads it without asking whether anything is falling is a clock that shows a countdown to
    * nobody. The check above caught exactly that.
    */
   public static int collapseSecondsLeft() {
      return collapsing ? Math.max(0, (collapseTicks + 19) / 20) : 0;
   }

   public static int collapseTicksForTest() {
      return collapseTicks;
   }

   /**
    * How many blocks the fall takes this tick.
    *
    * <p>Paced off the clock rather than set at a fixed number per tick, because the two things a
    * fall has to get right are opposites: it has to be <i>dense</i> (a wall coming down is many
    * blocks at once, not a trickle of them) and it has to <i>end</i> (a plan that still holds a
    * tower when the blast goes off is the castle winning an argument with its own deadline).
    *
    * <p>So the cursor has a schedule: the plan's blocks spread over the window at
    * {@link #COLLAPSE_PACE} times the even rate. The budget is the distance between where the
    * cursor is and where it should be, floored at {@link #COLLAPSE_BREAKS_PER_TICK} so the fall
    * never looks like it is thinking about it, capped at {@link #COLLAPSE_BREAKS_MAX} so a very
    * large castle cannot turn one tick into a two-second stall, and multiplied by the intensity
    * (heavy, then last light) which is also what makes the last stretch read as a different
    * event rather than as the same one for longer.
    *
    * <p>One arithmetic, read by the tick and by a check - the failure it exists to prevent is a
    * fall that is 90 percent over in ten seconds and then takes a minute to finish.
    */
   public static int collapseBudget(int ticksLeft, boolean heavy, boolean lastLight) {
      int left = Math.max(1, ticksLeft);
      int elapsed = Math.max(0, COLLAPSE_ESCAPE_TICKS - left);
      double due = (double)PLAN.size() * COLLAPSE_PACE * (double)elapsed / (double)COLLAPSE_ESCAPE_TICKS;
      int budget = Math.max(COLLAPSE_BREAKS_PER_TICK, (int)Math.ceil(due) - waveCursor);
      if (lastLight) {
         budget = budget * 2;
      } else if (heavy) {
         budget = budget * 3 / 2;
      }
      return Math.min(COLLAPSE_BREAKS_MAX, Math.max(1, budget));
   }

   /**
    * Empties a container the fall is about to take, before it takes it.
    *
    * <p>A block taken out of the world with {@code setBlock} never drops itself, and that used to
    * look like the whole story - which is why the fall was written to take chests with the same
    * call as stone. It is not: <b>the container's contents still spill</b>. A chest's items are
    * not the block, so the mirage taking the chest and the items it was holding landing on the
    * grass are two separate things, and the report was exactly that - loot on the floor of a
    * castle that is supposed to be withdrawing, in a structure whose entire contract is that it
    * leaves the field the way it found it.
    *
    * <p>So the fall empties the container the instant before the block goes. This is not
    * confiscation with a wink: the mirage takes the chest, its shelf and what is on the shelf,
    * and the sweep in {@link #discardDroppedLoot} is the backstop for anything a container hands
    * back by some other route (a hopper, another mod, a block entity that outlives its block).
    *
    * @return how many slots actually held something, so a check can ask whether it happened
    */
   public static int takeWhatIsInside(ServerLevel level, BlockPos pos) {
      if (level == null || pos == null) {
         return 0;
      }

      int had = 0;

      try {
         if (level.getBlockEntity(pos) instanceof Container container) {
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
               if (!container.getItem(slot).isEmpty()) {
                  container.setItem(slot, ItemStack.EMPTY);
                  had++;
               }
            }
            if (had > 0) {
               container.setChanged();
            }
         }
      } catch (Throwable ignored) {
         // A container that refuses to open on the tick it is being taken is not a reason to stop
         // the fall: the sweep catches whatever comes back out.
      }

      return had;
   }

   /** How far the wave has walked - read by a check that the order actually advances. */
   public static int waveCursorForTest() {
      return waveCursor;
   }

   private MirageCastleManager() {
   }

   /** One block of the castle: where it is, what was there, and what will be there. */
   private static final class Place {
      final BlockPos pos;
      final BlockState before;
      BlockState after;

      Place(BlockPos pos, BlockState before, BlockState after) {
         this.pos = pos;
         this.before = before;
         this.after = after;
      }
   }

   /**
    * A chest the castle will stock once it is standing, how good what is in it is, and which room
    * it belongs to - the room is what makes a chest worth opening beyond its tier.
    */
   private record Spot(BlockPos pos, int tier, String roomPlan) {
   }

   /**
    * A rectangle of the design that owns its own ground.
    *
    * <p>The courtyard is paved last, because it is the ground *between* the buildings - but it
    * used to be paved across the whole footprint, which quietly repaved the ground floor of the
    * keep and of both wings a moment after those rooms had laid their own floors. Every room of
    * the castle's bottom storey was the courtyard's stone with a lantern and a chest standing on
    * it, and nothing could tell, because the room's own floor had been planned and laid.
    */
   private record Footprint(int x0, int z0, int x1, int z1) {
      boolean holds(int dx, int dz) {
         return dx >= this.x0 && dx <= this.x1 && dz >= this.z0 && dz <= this.z1;
      }
   }

   private static final List<Footprint> BUILDINGS = new ArrayList<>();

   /** True when this design cell belongs to a building rather than to the courtyard. */
   private static boolean insideBuilding(int dx, int dz) {
      if (dx < 0 || dz < 0 || dx >= SIZE || dz >= SIZE) {
         return true;
      }

      for (Footprint building : BUILDINGS) {
         if (building.holds(dx, dz)) {
            return true;
         }
      }

      return false;
   }

   // ------------------------------------------------------------------ the throne hall

   /** What the seated King says when somebody walks into his hall. He does not move. */
   private static final String[] KING_LINES = new String[] {
      "Do not kneel. Whatever you would kneel to left this hall a very long time ago.",
      "I have forgotten the name of the kingdom. I remember the weight of the crown.",
      "You are the first thing to walk in here that still has a pulse.",
      "This hall remembers me. That is more than most kings are left with.",
      "Speak, or leave. I am not getting up for either."
   };

   /** On top of {@link #GUARD_TAG}, so a guard of the throne is still dust when it dies. */
   public static final String THRONE_GUARD_TAG = "ff_throne_guard";

   /**
    * The keep's throne hall: the whole ground-floor centre cell, which was a vault with a chest
    * in it and is now the one room in the castle that is a court.
    *
    * <p>Dais at the far end behind a gold lip, a carpet runner from the door to the foot of it,
    * braziers on the corners, banners over his shoulders, two tribute chests on the step, and
    * four Royal Guards standing at attention around the runner. He is invulnerable and still
    * until somebody speaks to him (see {@code BossManager.awakenThroneKing}); nothing in the
    * hall answers an arrow.
    */
   private static void throneHall(ServerLevel level, BlockPos o) {
      int k0 = (SIZE - KEEP_SIZE) / 2;
      int inner0 = k0 + 1;
      int cell = (KEEP_SIZE - 2) / 3;
      int rx = inner0 + cell;
      int rz = inner0 + cell;
      int x0 = rx + 1;
      int x1 = rx + cell - 1;
      int z0 = rz + 1;
      int mid = rx + cell / 2;
      int shelf = rz + cell - 1;
      int step = shelf - 1;
      int walk = 1;
      Block carpet = Blocks.CARPET.pick(net.minecraft.world.item.DyeColor.RED);
      Block banner = Blocks.WALL_BANNER.pick(net.minecraft.world.item.DyeColor.RED);

      // The dais: a step across the end of the hall, a shelf behind it, and the gold lip along
      // the front of the shelf - the same shape a court raised for itself everywhere else.
      for (int x = x0; x <= x1; x++) {
         plan(level, o.offset(x, walk, step), Blocks.POLISHED_DEEPSLATE);
         plan(level, o.offset(x, walk, shelf), Blocks.POLISHED_DEEPSLATE);
         plan(level, o.offset(x, walk + 1, shelf), Blocks.POLISHED_DEEPSLATE);
         plan(level, o.offset(x, walk + 1, step), Blocks.GOLD_BLOCK);
      }

      // The runner, two wide, from the doorway to the foot of the dais - and never over the
      // room's own furniture. The hall is built *over* a room that was designed first (the
      // vault at the middle of the ground floor), so its runner used to be laid straight across
      // the chest in it: a chest buried under a carpet is a chest nobody can open, and nothing
      // in the design could tell, because both halves were planned and both halves existed.
      for (int z = z0; z <= step - 1; z++) {
         if (!alreadyPlanned(o.offset(mid, walk, z))) {
            plan(level, o.offset(mid, walk, z), carpet);
         }
         if (!alreadyPlanned(o.offset(mid + 1, walk, z))) {
            plan(level, o.offset(mid + 1, walk, z), carpet);
         }
      }

      // The throne: a seat against the end wall, armrests, a gold canopy and two banners.
      for (int x = mid; x <= mid + 1; x++) {
         plan(level, o.offset(x, walk + 2, shelf), seat(Direction.SOUTH));
         plan(level, o.offset(x, walk + 5, shelf), Blocks.GOLD_BLOCK);
      }
      plan(level, o.offset(mid - 1, walk + 2, shelf), seat(Direction.WEST));
      plan(level, o.offset(mid + 2, walk + 2, shelf), seat(Direction.EAST));
      for (int x : new int[]{mid - 1, mid + 2}) {
         plan(level, o.offset(x, walk + 4, shelf), bannerState(banner));
      }

      // Braziers on the corners of the dais, lanterns down the hall, and the cobwebs of a court
      // that stopped sweeping.
      plan(level, o.offset(x0, walk + 1, step), Blocks.SOUL_CAMPFIRE);
      plan(level, o.offset(x1, walk + 1, step), Blocks.SOUL_CAMPFIRE);
      for (int x : new int[]{x0 + 1, x1 - 1}) {
         for (int z : new int[]{z0 + 1, z0 + 5}) {
            plan(level, o.offset(x, walk + 4, z), Blocks.GOLD_BLOCK);
            plan(level, o.offset(x, walk + 3, z), Blocks.SOUL_LANTERN);
         }
      }
      plan(level, o.offset(x0, walk + 2, z0), Blocks.COBWEB);
      plan(level, o.offset(x1, walk + 2, z0), Blocks.COBWEB);

      // Two tribute chests on the step, and the hall's own vault behind the throne.
      LOOT.add(new Spot(o.offset(x0 + 1, walk + 1, step), 3, null));
      LOOT.add(new Spot(o.offset(x1 - 1, walk + 1, step), 3, null));
      plan(level, o.offset(x0 + 1, walk + 1, step), Blocks.CHEST);
      plan(level, o.offset(x1 - 1, walk + 1, step), Blocks.CHEST);

      kingSeat = o.offset(mid, walk + 2, shelf);
      THRONE_POSTS.clear();
      THRONE_POSTS.add(o.offset(mid - 3, walk, step - 3));
      THRONE_POSTS.add(o.offset(mid + 4, walk, step - 3));
      THRONE_POSTS.add(o.offset(mid - 3, walk, step - 7));
      THRONE_POSTS.add(o.offset(mid + 4, walk, step - 7));
   }

   /** One step of the castle's own furniture: a seat rather than a stairway. */
   private static BlockState seat(Direction facing) {
      return Blocks.DEEPSLATE_TILE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, facing);
   }

   /** A red wall banner hanging on the wall behind it, facing down the hall. */
   private static BlockState bannerState(Block banner) {
      return banner.defaultBlockState().setValue(net.minecraft.world.level.block.WallBannerBlock.FACING, Direction.SOUTH);
   }

   /**
    * Puts the King on the throne and his four guards at their posts, once the hall exists.
    *
    * <p>Deliberately inert: he cannot be damaged, cannot move and cannot be dragged into a fight
    * by an arrow, and his guard stands with its AI off until the hall answers to him. The King
    * is a boss who has to be spoken to.
    */
   private static void seatTheKing(MinecraftServer server) {
      if (standing == null || kingSeat == null || kingId != null) {
         return;
      }

      try {
         net.minecraft.world.entity.Mob king = EntityTypes.WITHER_SKELETON.create(standing, EntitySpawnReason.COMMAND);
         if (king == null) {
            return;
         }

         Stand seat = standSpot(standing, king, kingSeat);
         king.setPos(seat.x(), seat.y(), seat.z());
         king.setYRot(180.0F);
         king.setYHeadRot(180.0F);
         king.setPersistenceRequired();
         king.setNoAi(true);
         king.setInvulnerable(true);
         king.setCanPickUpLoot(false);
         king.setCustomName(net.minecraft.network.chat.Component.literal("\u00a77\u00a7l" + BossManager.THRONE_KING_NAME));
         king.setCustomNameVisible(true);
         // His regalia is the fight's own business: the crown, the netherite under it and the
         // sword he swaps for the greatsword when he stands up all live in BossManager beside
         // the rotation that swings them.
         BossManager.dressThroneKing(king);

         king.addTag(BossManager.THRONE_KING_TAG);
         standing.addFreshEntity(king);
         kingId = king.getUUID();
         kingAwake = false;
         kingPaid = false;
         throneWoke = false;
         kingLine = 0;
         kingNextLine = 0L;
      } catch (Throwable t) {
         kingId = null;
         return;
      }

      THRONE_GUARDS.clear();

      for (BlockPos post : THRONE_POSTS) {
         try {
            net.minecraft.world.entity.Mob guard = EntityTypes.WITHER_SKELETON.create(standing, EntitySpawnReason.COMMAND);
            if (guard == null) {
               continue;
            }

            Stand spot = standSpot(standing, guard, post);
            guard.setPos(spot.x(), spot.y(), spot.z());
            guard.setYRot(0.0F);
            guard.setYHeadRot(0.0F);
            guard.setPersistenceRequired();
            guard.setNoAi(true);
            guard.setCanPickUpLoot(false);
            guard.setCustomName(net.minecraft.network.chat.Component.literal("\u00a76\u00a7lRoyal Guard"));
            guard.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.GOLDEN_HELMET));
            guard.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.STONE_AXE));

            for (EquipmentSlot slot : EquipmentSlot.values()) {
               guard.setDropChance(slot, 0.0F);
            }

            guard.addTag(GUARD_TAG);
            guard.addTag(THRONE_GUARD_TAG);
            standing.addFreshEntity(guard);
            THRONE_GUARDS.add(guard.getUUID());
         } catch (Throwable ignored) {
         }
      }
   }

   /** A place a body was actually put: the middle of a cell, standing on whatever is under it. */
   private record Stand(double x, double y, double z) {
   }

   /**
    * The first spot in a bay where this body fits, found rather than assumed.
    *
    * <p>The seated King used to be placed one block above {@link #kingSeat} and left there, and
    * that is how he came to stand *inside* the throne's own gold canopy: he is a wither skeleton,
    * which is 2.4 blocks tall, and the bay over the chair offered two blocks of air with the
    * canopy directly above it. Nothing noticed, because a body that is invulnerable and has no AI
    * never suffocates and never moves - it just stands in the furniture. So the placement is a
    * search now, against the body's own box and the real collision shapes of the hall: the bay it
    * was asked for, then the dais in front of it, at every level that has something to stand on,
    * and the first one that fits wins. The canopy can move, the seat can change, and a taller
    * body can be put on the throne, without this being wrong in silence again.
    */
   private static Stand standSpot(ServerLevel level, Mob mob, BlockPos bay) {
      for (BlockPos column : new BlockPos[]{bay, bay.offset(0, 0, -1), bay.offset(0, 0, -2), bay.offset(0, 1, -1)}) {
         for (int up = 0; up <= 3; up++) {
            BlockPos feet = column.above(up);
            double surface = standSurface(level, feet);
            if (Double.isNaN(surface)) {
               continue;
            }

            Stand stand = new Stand(feet.getX() + 0.5, surface, feet.getZ() + 0.5);
            if (fits(level, mob, stand)) {
               return stand;
            }
         }
      }

      // Nothing with a floor under it fits, which means this bay is not furniture this body was
      // designed for. The answer is still not to stand it inside the furniture: it goes in the
      // open above the bay and falls onto whatever is there.
      for (int up = 1; up <= 8; up++) {
         BlockPos feet = bay.above(up);
         Stand stand = new Stand(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5);
         if (fits(level, mob, stand)) {
            return stand;
         }
      }

      return new Stand(bay.getX() + 0.5, bay.getY() + 1.0, bay.getZ() + 0.5);
   }

   /**
    * The top of whatever is under this cell - the block's own top for a full block, half way up a
    * slab, the upper tread of a stair - or NaN when there is nothing there to stand on at all.
    */
   private static double standSurface(ServerLevel level, BlockPos feet) {
      BlockPos below = feet.below();
      VoxelShape support = level.getBlockState(below).getCollisionShape(level, below);
      if (support.isEmpty()) {
         return Double.NaN;
      }

      double surface = below.getY() + support.max(Direction.Axis.Y);
      return surface < feet.getY() - 0.25 ? Double.NaN : surface;
   }

   /** True when this body's own box is clear of the world at that spot. */
   private static boolean fits(ServerLevel level, Mob mob, Stand stand) {
      double half = mob.getBbWidth() / 2.0 + 0.02;
      AABB box = new AABB(
         stand.x() - half,
         stand.y(),
         stand.z() - half,
         stand.x() + half,
         stand.y() + mob.getBbHeight() + 0.02,
         stand.z() + half
      );
      return level.noCollision(box);
   }

   /**
    * The King's own clock: nothing while he is seated except the odd line of dialogue, then the
    * hall waking up with him, then what his fall is worth.
    *
    * <p>A seated King who is gone was unloaded rather than killed (the castle's chunks are the
    * ones the castle is standing in), so he is simply seated again.
    */
   private static void tickKing(MinecraftServer server) {
      if (standing == null || kingId == null) {
         return;
      }

      Entity entity = standing.getEntity(kingId);
      net.minecraft.world.entity.Mob king = entity instanceof net.minecraft.world.entity.Mob mob ? mob : null;

      if (king == null) {
         if (!kingAwake) {
            kingId = null;
            seatTheKing(server);
            return;
         }

         payForTheKing(false);
         // The hall has no monarch in it any more, whichever way he left, and the mirage has
         // nothing left holding it up.
         beginCollapse(server);
         return;
      }

      if (!king.isAlive()) {
         payForTheKing(true);
         beginCollapse(server);
         return;
      }

      if (!kingAwake) {
         ServerPlayer near = visitorNear(king, 12.0);
         long now = standing.getGameTime();

         if (near != null && now >= kingNextLine) {
            kingNextLine = now + 240L;
            String line = KING_LINES[kingLine++ % KING_LINES.length];
            Chat.raw(near, "\u00a78\u00a7l" + BossManager.THRONE_KING_NAME + "\u00a78: \u00a77\"" + line + "\"");
            mirageParticles(ParticleTypes.SOUL, king.getX(), king.getY() + 2.1, king.getZ(), 6, 0.4, 0.3, 0.4, 0.01);
         }
         return;
      }

      if (!throneWoke) {
         throneWoke = true;
         ServerPlayer near = visitorNear(king, 64.0);

         for (UUID id : THRONE_GUARDS) {
            if (standing.getEntity(id) instanceof net.minecraft.world.entity.Mob guard && guard.isAlive()) {
               guard.setNoAi(false);
               if (near != null) {
                  guard.setTarget(near);
               }
            }
         }
      }
   }

   /** The nearest player who is inside the hall's own walls. */
   private static ServerPlayer visitorNear(net.minecraft.world.entity.Entity at, double range) {
      ServerPlayer best = null;
      double bestDist = range * range;

      for (ServerPlayer p : standing.getPlayers(pl -> pl.isAlive() && !pl.isSpectator())) {
         double d = p.distanceToSqr(at);
         if (d < bestDist) {
            bestDist = d;
            best = p;
         }
      }

      return best;
   }

   /**
    * What his fall is worth, once.
    *
    * <p>He pays a royal drop rather than a chest, because the room he died in is already full of
    * them, and the guard he brought with him stands down with him - a court with no king in it
    * left standing at attention forever is a bug people report as ghosts. And he pays the blade,
    * which is the only thing here that was never a mirage - see {@link #monarchsBlade}.
    *
    * <p>The fight is put down with the same call, so his health bar leaves the screen at the
    * moment the hall stops having a monarch rather than staying there until the castle ends.
    *
    * @param atBody false when the body was gone before this ran, in which case the drop lands
    *               where the throne is
    */
   private static void payForTheKing(boolean atBody) {
      if (kingPaid) {
         return;
      }

      kingPaid = true;
      Entity entity = standing == null || kingId == null ? null : standing.getEntity(kingId);
      double x = entity != null ? entity.getX() : kingSeat == null ? 0.0 : kingSeat.getX() + 0.5;
      double y = entity != null ? entity.getY() : kingSeat == null ? 0.0 : kingSeat.getY();
      double z = entity != null ? entity.getZ() : kingSeat == null ? 0.0 : kingSeat.getZ() + 0.5;

      if (standing != null) {
         captainReward(standing, x, y, z);
         monarchsBlade(standing, x, y, z);
         monarchsKeepings(standing, x, y, z);
         standing.playSound(null, x, y, z, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.PLAYERS, 2.0F, 0.6F);
         mirageParticles(ParticleTypes.SOUL_FIRE_FLAME, x, y + 1.0, z, 60, 1.5, 1.0, 1.5, 0.06);

         for (ServerPlayer p : standing.getPlayers(pl -> pl.isAlive())) {
            Chat.raw(p, "\u00a77\u00a7lTHE DISTANT MEMORY FALLS\u00a7r \u00a77- the hall is only a hall again.");
            Chat.raw(p, "\u00a78\"...so the kingdom was real. Somebody should write that down.\"");
            Chat.raw(p, "\u00a77His greatsword comes away with him: \u00a7f\u00a7lLast Remembrance\u00a77 is on the floor, and it does not go back with the castle.");
         }

         for (UUID id : THRONE_GUARDS) {
            if (standing.getEntity(id) instanceof net.minecraft.world.entity.Mob guard && guard.isAlive()) {
               guard.hurtServer(standing, standing.damageSources().genericKill(), 1000.0F);
            }
         }
      }

      // Only when there is no body to reach. A King who is lying there still has an ordinary
      // death coming, and `onKilled` is what pays the kill - taking the fight out from under it
      // would swallow his loot boxes, his bounty and the advancement. A body that was gone
      // first will never reach `onKilled` at all, so the hall has to put its own fight down.
      if (!atBody) {
         BossManager.dismissBoss(entity != null ? entity.getUUID() : kingId);
      }

      kingId = null;
      kingAwake = false;
      THRONE_GUARDS.clear();
   }

   /**
    * The blade off his body: the one thing the castle pays that was never the castle's.
    *
    * <p>Everything else in here is somebody else's room, held in the world for the length of the
    * event and handed back block for block when it ends - including the treasure, which is why
    * the loot leaves with the walls. His sword is the exception the whole fiction is built on:
    * the crown was remembered into being, but the hand that held it and the blade in it were
    * really there, so it drops once, from him, and the mirage cannot take it back.
    *
    * <p>Built by {@link ModItems#lastRemembrance} - the *same* call the fourth beat of his
    * announcement arms him with - so what falls is literally what he was swinging.
    */
   private static void monarchsBlade(ServerLevel level, double x, double y, double z) {
      try {
         ItemStack blade = ModItems.lastRemembrance(level.registryAccess());
         ItemEntity drop = new ItemEntity(level, x, y + 0.6, z, blade);
         drop.setDefaultPickUpDelay();
         level.addFreshEntity(drop);

         level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, x, y + 1.0, z, 90, 1.1, 1.2, 1.1, 0.07);
         level.sendParticles(ParticleTypes.END_ROD, x, y + 1.2, z, 30, 0.4, 0.8, 0.4, 0.05);
         level.playSound(null, x, y, z, SoundEvents.ANVIL_LAND, SoundSource.PLAYERS, 1.2F, 0.5F);
      } catch (Throwable ignored) {
      }
   }

   /** How often each of the monarch's own two keepings falls, out of 100.
    *
    *  <p>Deliberately low, and deliberately two separate rolls rather than one ladder: the hall
    *  pays its treasure every time he falls, so these are what make him worth coming back for
    *  rather than what a kill is worth. A run that finds one must not promise the other. */
   private static final int KEEPING_EFFICIENCY_CHANCE = 12;
   private static final int KEEPING_ASTRAL_CHANCE = 8;

   /**
    * What a roll pays: the two keepings the monarch's own death can leave on the floor.
    *
    * <p>Asked as a question rather than rolled inline, so the odds and the answers to them live
    * in one place and the self-test can walk the whole band - every ticket that pays nothing,
    * every ticket that pays one, and the window each one starts at.
    *
    * @param roll 0-99, the ticket the drop rolled
    */
   public static List<ItemStack> keepingsForRoll(int roll, net.minecraft.core.HolderLookup.Provider access) {
      List<ItemStack> keepings = new ArrayList<>();

      if (roll < KEEPING_EFFICIENCY_CHANCE) {
         keepings.add(ModItems.monarchsEfficiencyBook(access));
      }
      if (roll >= 100 - KEEPING_ASTRAL_CHANCE) {
         ItemStack tome = CustomEnchantments.tome(CustomEnchantments.ASTRAL, 1);
         // Marked here rather than at the drop, because the mark is what says "this came off his
         // body" - a tome that missed it would be swept up with the walls like any other book.
         ModItems.markMonarchKeeping(tome);
         keepings.add(tome);
      }

      return keepings;
   }

   /**
    * The keepings themselves, off his body: an Efficiency VII book and an Astral tome.
    *
    * <p>These are the same class of thing as {@link #monarchsBlade} and are marked as such -
    * {@link ModItems#isMonarchKeeping} - for the same reason the blade is: they came off the
    * body that was really there, not out of the room the castle was holding, so the mirage may
    * not take them back. That marker is what spares them in {@link #discardDroppedLoot}, which is
    * why they are marked the moment they are minted rather than at the chest.
    */
   private static void monarchsKeepings(ServerLevel level, double x, double y, double z) {
      try {
         List<ItemStack> keepings = keepingsForRoll(RANDOM.nextInt(100), level.registryAccess());
         if (keepings.isEmpty()) {
            return;
         }

         List<String> named = new ArrayList<>();
         for (ItemStack stack : keepings) {
            ItemEntity drop = new ItemEntity(level, x, y + 0.6, z, stack);
            drop.setDefaultPickUpDelay();
            level.addFreshEntity(drop);
            named.add(stack.getHoverName().getString());
         }

         level.sendParticles(ParticleTypes.ENCHANT, x, y + 1.2, z, 70, 0.7, 0.9, 0.7, 0.5);
         level.playSound(null, x, y, z, SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.9F, 1.3F);

         String what = String.join("§7 and §f", named);
         for (ServerPlayer p : level.getPlayers(pl -> pl.isAlive())) {
            Chat.raw(p, "\u00a7d\u00a7lHe kept something back\u00a7r \u00a77- \u00a7f" + what + "\u00a77 falls with him.");
         }
      } catch (Throwable ignored) {
      }
   }

   /** Takes the hall's King and his guard out of the world with the castle that built them. */
   private static void retireThroneHall() {
      // The fight goes with the castle, and its health bar with it. A discarded body never reaches
      // BossManager's killed-handler - that walks live entities - so a castle that ended while its
      // monarch was awake used to leave his bar sitting over an empty field for the rest of the
      // session with nothing left alive to take it down. Asked by id as well as by body, because
      // the body may already be unloaded while the fight it belongs to is not.
      Entity kingBody = standing != null && kingId != null ? standing.getEntity(kingId) : null;
      if (kingBody != null) {
         BossManager.dismissBoss(kingBody);
      } else {
         BossManager.dismissBoss(kingId);
      }

      if (standing != null) {
         if (kingBody instanceof Entity king) {
            king.discard();
         }

         for (UUID id : THRONE_GUARDS) {
            if (standing.getEntity(id) instanceof Entity guard) {
               guard.discard();
            }
         }
      }

      kingId = null;
      kingAwake = false;
      kingPaid = true;
      throneWoke = false;
      THRONE_GUARDS.clear();
   }

   /** True if this body is the castle's King, seated or woken. */
   public static boolean isThroneKing(Entity entity) {
      if (entity == null) {
         return false;
      }

      try {
         return entity.entityTags().contains(BossManager.THRONE_KING_TAG);
      } catch (Throwable t) {
         return false;
      }
   }

   /**
    * A player speaking to the seated King: the fight starts, and the hall remembers that it did,
    * so his fall pays out rather than quietly removing him.
    *
    * @return true when this was the audience that started the fight
    */
   public static boolean wakeKing(ServerPlayer sp, net.minecraft.world.entity.Mob king) {
      if (sp == null || king == null || !BossManager.awakenThroneKing(sp, king)) {
         return false;
      }

      if (kingId != null && king.getUUID().equals(kingId)) {
         kingAwake = true;
         kingNextLine = 0L;
      }

      Chat.raw(
         sp,
         "\u00a78\u00a7l"
            + BossManager.THRONE_KING_NAME
            + "\u00a78: \u00a77\"...so that is what a visitor sounds like. Stand back, then - a crown is a heavy thing to lift.\""
      );

      return true;
   }

   /** Where the throne is, for the self-test: null when no castle has been designed. */
   public static BlockPos throneSeatForTest() {
      return kingSeat;
   }

   /** The King of the standing hall, for the self-test. */
   public static UUID kingIdForTest() {
      return kingId;
   }

   /** How many guards the hall seated, for the self-test. */
   public static int throneGuardsForTest() {
      return THRONE_GUARDS.size();
   }

   /** The bodies of the court - the King and his guards - for the self-test. */
   public static List<UUID> throneCourtForTest() {
      List<UUID> out = new ArrayList<>();
      if (kingId != null) {
         out.add(kingId);
      }
      out.addAll(THRONE_GUARDS);
      return out;
   }

   // ------------------------------------------------------------------ queries

   /** True while this position is part of the standing castle, so it cannot be broken. */
   public static boolean isProtected(Level level, BlockPos pos) {
      return standing != null && level != null && pos != null && level == standing && INDEX.containsKey(pos);
   }

   /** True while a castle is standing at all. */
   public static boolean isStanding() {
      return standing != null && !PLAN.isEmpty();
   }

   /** True while the castle is still rising - the part of it that exists is only part of it. */
   public static boolean isRising() {
      return isStanding() && !settled;
   }

   /** How many blocks it is holding. Test hook, and the number the announcement quotes. */
   public static int placedCount() {
      return INDEX.size();
   }

   /** Its footprint. Test hook. */
   public static int width() {
      return SIZE;
   }

   /**
    * How many rooms the design lays out: three storeys of nine in the keep, and two storeys of
    * four in each wing. Fixed arithmetic rather than a counter, so the number can be asked for
    * before anything has been built - which is what the self-test and the announcement need.
    */
   private static final int ROOMS = 3 * 9 + 2 * 4 * 2;
   private static int roomsBuilt = 0;

   /** How many rooms it has. */
   public static int rooms() {
      return ROOMS;
   }

   /** How many of the plan's blocks have really been placed. Test hook. */
   public static int risenForTest() {
      return risen;
   }

   /** How many the last design actually laid out. Test hook. */
   public static int roomsBuiltForTest() {
      return roomsBuilt;
   }

   /** Every stair tread the last design laid out. Test hook. */
   public static List<BlockPos> treadsForTest() {
      return List.copyOf(TREADS);
   }

   /** Every column the last design opened through a floor for a flight. Test hook. */
   public static List<BlockPos> punchesForTest() {
      return List.copyOf(PUNCHES);
   }

   /**
    * Whether a body is inside the mirage - the one question that decides whether a fatal
    * blow is a death or a ride home.
    *
    * <p>Deliberately a box and not the undo list: a player standing in a doorway or on the
    * courtyard has no castle block at their own feet, and the rule is about being in the
    * place, not about standing on it.
    */
   public static boolean inside(Entity entity) {
      if (standing == null || origin == null || entity == null || entity.level() != standing) {
         return false;
      }
      BlockPos p = entity.blockPosition();
      int dx = p.getX() - origin.getX();
      int dz = p.getZ() - origin.getZ();
      int dy = p.getY() - origin.getY();
      return dx >= 0 && dx < SIZE && dz >= 0 && dz < SIZE && dy >= -2 && dy <= TOWER_HEIGHT + 6;
   }

   // ------------------------------------------------------------------- raising

   /**
    * Raises a castle near a random player.
    *
    * <p>Refused on claimed ground and inside a duel realm, for the same reason the meteors
    * are: an event may not put a structure in somebody's base, and it may not put one in an
    * arena whose whole design is the blocks already in it.
    */
   public static void raise(MinecraftServer server) {
      if (server == null || isStanding()) {
         return;
      }

      List<ServerPlayer> online = server.getPlayerList().getPlayers();
      if (online.isEmpty()) {
         return;
      }

      ServerPlayer around = online.get(RANDOM.nextInt(online.size()));
      ServerLevel level = around.level();

      for (int attempt = 0; attempt < 16; attempt++) {
         double angle = RANDOM.nextDouble() * Math.PI * 2.0;
         double dist = 70.0 + RANDOM.nextDouble() * 60.0;
         int cx = (int)Math.floor(around.getX() + Math.cos(angle) * dist);
         int cz = (int)Math.floor(around.getZ() + Math.sin(angle) * dist);
         int cy = level.getHeight(Heightmap.Types.MOTION_BLOCKING, cx, cz);
         BlockPos corner = new BlockPos(cx - SIZE / 2, cy, cz - SIZE / 2);

         if (cy <= level.getMinY() + TOWER_HEIGHT + 4 || !level.isLoaded(corner)) {
            continue;
         }
         if (DuelManager.isDuelRealm(level) || ClaimManager.claimAt(level, corner) != null) {
            continue;
         }
         // The whole footprint is checked: a castle whose far tower lands on somebody's
         // doorstep is still their land, however far its centre is.
         if (anyClaimed(level, corner)) {
            continue;
         }

         design(server, level, corner);
         return;
      }
   }

   private static boolean anyClaimed(ServerLevel level, BlockPos corner) {
      for (int dx = 0; dx <= SIZE; dx += SIZE / 4) {
         for (int dz = 0; dz <= SIZE; dz += SIZE / 4) {
            if (ClaimManager.claimAt(level, corner.offset(dx, 0, dz)) != null) {
               return true;
            }
         }
      }

      return false;
   }

   /**
    * Works out the whole castle, writes the way back to disk, and starts it rising.
    *
    * <p>The order matters and is the point: the plan is complete <i>before</i> the first
    * block is placed, and it is on disk before that. A crash between here and the last block
    * therefore leaves a file that names every block to put back, including the ones that
    * never got there.
    */
   private static void design(MinecraftServer server, ServerLevel level, BlockPos corner) {
      standing = level;
      origin = corner;
      PLAN.clear();
      TREADS.clear();
      PUNCHES.clear();
      INDEX.clear();
      LOOT.clear();
      DESIGN_ROOMS.clear();
      BUILDINGS.clear();
      GUARD_SPOTS.clear();
      GUARDS.clear();
      risen = 0;
      settled = false;
      roomsBuilt = 0;
      grandLibraries = 0;
      grandLibraryShelves = 0;
      grandLibraries = 0;
      grandLibraryShelves = 0;

      buildDesign(level, corner);
      savePlan(server);

      level.playSound(null, corner, SoundEvents.END_PORTAL_SPAWN, SoundSource.WEATHER, 1.6F, 1.2F);
      level.playSound(null, corner, SoundEvents.BEACON_ACTIVATE, SoundSource.WEATHER, 1.4F, 0.7F);

      BlockPos middle = corner.offset(SIZE / 2, 0, SIZE / 2);
      int half = SIZE / 2;

      for (ServerPlayer p : level.getPlayers(pl -> pl.isAlive())) {
         long away = (long)Math.sqrt(p.blockPosition().distToCenterSqr(middle.getX() + 0.5, middle.getY(), middle.getZ() + 0.5));
         Chat.raw(p, "§d§lA Mirage Castle§r§7 is rising " + away + " blocks away §8(" + SIZE + "×" + SIZE + ", "
            + INDEX.size() + " blocks)§7 - it is not really there, and it will not be here for long.");
         Chat.raw(p, "§7Its walls cannot be broken. §dA fatal blow inside it takes you home instead of killing you.");
      }

      for (ServerPlayer p : level.getPlayers(pl -> pl.isAlive() && pl.blockPosition().distToCenterSqr(middle.getX() + 0.5, middle.getY(), middle.getZ() + 0.5) < (double)((half + 40) * (half + 40)))) {
         level.sendParticles(ParticleTypes.PORTAL, p.getX(), p.getY() + 1.0, p.getZ(), 20, 0.6, 0.8, 0.6, 0.08);
      }
   }

   // ------------------------------------------------------------------- design

   /** The whole castle, in the order it rises: plinth, curtain wall, keep, wings, garden. */
   private static void buildDesign(ServerLevel level, BlockPos o) {
      RandomSource deco = RandomSource.create(o.asLong() * 31L + 7L);

      plinth(level, o);
      curtainWall(level, o);
      towers(level, o);
      gatehouse(level, o);
      keep(level, o);
      wing(level, o, 1, 43);
      wing(level, o, 71, 43);
      courtyard(level, o);
      garden(level, o, deco);
   }

   /** The raised ground: a foundation course and a flat walk level across the footprint. */
   private static void plinth(ServerLevel level, BlockPos o) {
      for (int dx = 0; dx < SIZE; dx++) {
         for (int dz = 0; dz < SIZE; dz++) {
            plan(level, o.offset(dx, -1, dz), (dx + dz) % 2 == 0 ? Blocks.STONE_BRICKS : Blocks.CRACKED_STONE_BRICKS);
            plan(level, o.offset(dx, 0, dz), (dx + dz) % 4 == 0 ? Blocks.POLISHED_ANDESITE : Blocks.STONE_BRICKS);
         }
      }
   }

   /** The curtain wall: a battlemented ring with a gate on the south face. */
   private static void curtainWall(ServerLevel level, BlockPos o) {
      for (int i = 0; i < SIZE; i++) {
         for (int side = 0; side < 4; side++) {
            int dx = side == 0 || side == 1 ? i : (side == 2 ? 0 : SIZE - 1);
            int dz = side == 0 ? 0 : (side == 1 ? SIZE - 1 : i);
            boolean gate = dz == 0 && dx >= SIZE / 2 - 4 && dx <= SIZE / 2 + 4;

            for (int h = 0; h < WALL_HEIGHT; h++) {
               if (gate && h <= 3) {
                  plan(level, o.offset(dx, h, dz), Blocks.AIR);
                  continue;
               }
               boolean battlements = h == WALL_HEIGHT - 1;
               Block block = battlements
                  ? ((dx + dz) % 2 == 0 ? Blocks.STONE_BRICK_WALL : Blocks.DEEPSLATE_TILES)
                  : Blocks.STONE_BRICKS;
               plan(level, o.offset(dx, h, dz), block);
               // Arrow slits along the wall, every eighth block: something to shoot through
               // rather than a solid bar, without being a way in.
               if (!battlements && h >= 5 && h <= 7 && i % 8 == 3 && dz != 0 && dz != SIZE - 1) {
                  plan(level, o.offset(dx, h, dz), Blocks.GLASS);
               }
            }
            if (gate && dx == SIZE / 2) {
               plan(level, o.offset(dx, WALL_HEIGHT, dz), Blocks.SOUL_LANTERN);
            }
         }
      }

      // The gate's own arch, so the opening reads as a gate rather than a hole.
      for (int dx = SIZE / 2 - 4; dx <= SIZE / 2 + 4; dx++) {
         plan(level, o.offset(dx, 4, 0), Blocks.CHISELED_STONE_BRICKS);
         plan(level, o.offset(dx, 5, 0), (dx + 5) % 2 == 0 ? Blocks.STONE_BRICK_WALL : Blocks.CHISELED_STONE_BRICKS);
      }
   }

   private static void towers(ServerLevel level, BlockPos o) {
      for (int tx = 0; tx <= 1; tx++) {
         for (int tz = 0; tz <= 1; tz++) {
            int x0 = tx == 0 ? 0 : SIZE - TOWER_SIZE;
            int z0 = tz == 0 ? 0 : SIZE - TOWER_SIZE;

            for (int dx = 0; dx < TOWER_SIZE; dx++) {
               for (int dz = 0; dz < TOWER_SIZE; dz++) {
                  boolean edge = dx == 0 || dz == 0 || dx == TOWER_SIZE - 1 || dz == TOWER_SIZE - 1;
                  for (int h = WALL_HEIGHT; h < TOWER_HEIGHT; h++) {
                     if (edge) {
                        boolean slit = (dx + dz) % 4 == 1 && h % 5 == 2;
                        plan(level, o.offset(x0 + dx, h, z0 + dz), slit ? Blocks.GLASS : Blocks.DEEPSLATE_BRICKS);
                     } else if ((h - WALL_HEIGHT) % 8 == 0) {
                        // Floors inside the tower, so its rooms are rooms.
                        plan(level, o.offset(x0 + dx, h, z0 + dz), Blocks.DEEPSLATE_TILES);
                     }
                  }
               }
            }

            // A stepped roof and a lantern on the point: the silhouette is the whole of
            // "there is a castle there" from a distance.
            for (int step = 0; step < 3; step++) {
               int from = step;
               int to = TOWER_SIZE - 1 - step;
               int h = TOWER_HEIGHT + step;
               for (int dx = from; dx <= to; dx++) {
                  for (int dz = from; dz <= to; dz++) {
                     plan(level, o.offset(x0 + dx, h, z0 + dz), Blocks.DEEPSLATE_TILES);
                  }
               }
            }
            plan(level, o.offset(x0 + TOWER_SIZE / 2, TOWER_HEIGHT + 3, z0 + TOWER_SIZE / 2), Blocks.LANTERN);
            if (tx == 1 && tz == 1) {
               GUARD_SPOTS.add(o.offset(x0 + TOWER_SIZE / 2, TOWER_HEIGHT - 1, z0 + TOWER_SIZE / 2));
            }
         }
      }
   }

   /** Two square towers flanking the gate, and the walkway over it. */
   private static void gatehouse(ServerLevel level, BlockPos o) {
      for (int side = 0; side < 2; side++) {
         int x0 = side == 0 ? SIZE / 2 - 9 : SIZE / 2 + 5;

         for (int dx = 0; dx < 5; dx++) {
            for (int dz = 0; dz < 5; dz++) {
               boolean edge = dx == 0 || dz == 0 || dx == 4 || dz == 4;
               for (int h = 0; h < WALL_HEIGHT + 6; h++) {
                  if (!edge && h % 5 != 0) {
                     continue;
                  }
                  plan(level, o.offset(x0 + dx, h, dz), edge ? Blocks.STONE_BRICKS : Blocks.DEEPSLATE_TILES);
               }
            }
         }
         plan(level, o.offset(x0 + 2, WALL_HEIGHT + 6, 2), Blocks.LANTERN);
         GUARD_SPOTS.add(o.offset(x0 + 2, 1, 4));
      }
   }

   /**
    * The keep: three storeys of nine rooms, a throne room, and a way up.
    *
    * <p>Nineteen-by-nineteen of interior per storey, split into a three-by-three of cells by
    * one-block walls with a doorway through each, which is how a castle gets twenty-seven
    * rooms without twenty-seven hand-written descriptions.
    */
   private static void keep(ServerLevel level, BlockPos o) {
      int k0 = (SIZE - KEEP_SIZE) / 2;
      int inner0 = k0 + 1;
      int innerSize = KEEP_SIZE - 2;
      int cell = innerSize / 3;
      Block carpet = Blocks.CARPET.pick(net.minecraft.world.item.DyeColor.RED);

      for (int storey = 0; storey < 3; storey++) {
         int floorY = storey * STOREY;
         int topY = floorY + STOREY;

         // The wall: solid, with a band of glass at eye height for each storey.
         for (int i = 0; i < KEEP_SIZE; i++) {
            for (int side = 0; side < 4; side++) {
               int dx = side == 0 || side == 1 ? k0 + i : (side == 2 ? k0 : k0 + KEEP_SIZE - 1);
               int dz = side == 0 ? k0 : (side == 1 ? k0 + KEEP_SIZE - 1 : k0 + i);
               boolean entrance = dz == k0 && storey == 0 && dx >= SIZE / 2 - 3 && dx <= SIZE / 2 + 3;

               for (int h = floorY + 1; h <= topY; h++) {
                  if (entrance && h <= floorY + 3) {
                     plan(level, o.offset(dx, h, dz), Blocks.AIR);
                     continue;
                  }
                  boolean window = i % 5 == 2 && (h == floorY + 2 || h == floorY + 3);
                  if (h == topY) {
                     // The floor of the storey above - or, on the top storey, the roof.
                     plan(level, o.offset(dx, h, dz), Blocks.DEEPSLATE_TILES);
                  } else {
                     plan(level, o.offset(dx, h, dz), window ? Blocks.GLASS : Blocks.STONE_BRICKS);
                  }
               }
            }
         }

         // The floor of this storey, and the cell walls over it.
         for (int dx = k0; dx < k0 + KEEP_SIZE; dx++) {
            for (int dz = k0; dz < k0 + KEEP_SIZE; dz++) {
               plan(level, o.offset(dx, floorY, dz), (dx + dz) % 3 == 0 ? Blocks.POLISHED_DEEPSLATE : Blocks.DEEPSLATE_TILES);
            }
         }

         // The floor over the stairway is opened by the flight itself (see flight), which is
         // the only thing that knows where its own treads are. This used to be a four-by-three
         // hole punched here by hand, three blocks deep, next to a one-block-wide run: two of
         // those columns were not over the stairs at all, so the floor of every storey had a
         // hole in it big enough to fall through, beside a staircase you had to jump up.

         for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
               roomsBuilt++;
               int rx = inner0 + i * cell;
               int rz = inner0 + j * cell;
               Block wallBlock = (i + j) % 2 == 0 ? Blocks.STONE_BRICKS : Blocks.POLISHED_DEEPSLATE;

               // The two walls that bound this cell, with a doorway in the middle of each, and a
               // rug through every one of them: a carpet at each door is what turns a nine-cell
               // grid of rooms into a court whose shape you can see from inside it.
               for (int d = 0; d < cell; d++) {
                  boolean doorRow = d == cell / 2 || d == cell / 2 + 1;
                  for (int h = floorY + 1; h <= floorY + STOREY; h++) {
                     boolean doorway = doorRow && h <= floorY + 3;
                     if (i < 2) {
                        plan(level, o.offset(rx + cell, h, rz + d), doorway ? Blocks.AIR : wallBlock);
                     }
                     if (j < 2) {
                        plan(level, o.offset(rx + d, h, rz + cell), doorway ? Blocks.AIR : wallBlock);
                     }
                  }

                  if (doorRow) {
                     if (i < 2) {
                        plan(level, o.offset(rx + cell, floorY + 1, rz + d), carpet);
                     }
                     if (j < 2) {
                        plan(level, o.offset(rx + d, floorY + 1, rz + cell), carpet);
                     }
                  }
               }

               // The room itself: its own floor, its own light, its own furniture, its own
               // centrepiece, and a chest worth what the room is worth.
               int cx = rx + cell / 2;
               int cz = rz + cell / 2;
               decorateRoom(level, o, cx, floorY, cz, storey, i, j);
            }
         }
      }

      // The roof deck, before the crown that sits on it. The wall loop above floors the ring of
      // the top storey and the battlements are planned two rows up from here, so the middle of
      // the roof was the one part of the keep nobody had planned: the stairs arrived at a hole in
      // the sky, which is what "the staircase is really buggy" looks like from on top of it.
      for (int dx = k0 + 1; dx < k0 + KEEP_SIZE - 1; dx++) {
         for (int dz = k0 + 1; dz < k0 + KEEP_SIZE - 1; dz++) {
            plan(level, o.offset(dx, KEEP_HEIGHT - 1, dz), Blocks.DEEPSLATE_TILES);
         }
      }

      // Battlements on the roof, and a way up the whole keep: one flight of stairs a storey,
      // against the inner face of the wall (see keepStairs).
      for (int dx = 0; dx < KEEP_SIZE; dx++) {
         for (int dz = 0; dz < KEEP_SIZE; dz++) {
            boolean edge = dx == 0 || dz == 0 || dx == KEEP_SIZE - 1 || dz == KEEP_SIZE - 1;
            if (edge && (dx + dz) % 2 == 0) {
               plan(level, o.offset(k0 + dx, KEEP_HEIGHT, k0 + dz), Blocks.STONE_BRICK_WALL);
            }
         }
      }

      keepStairs(level, o, inner0);
      throneHall(level, o);
      BUILDINGS.add(new Footprint(k0, k0, k0 + KEEP_SIZE - 1, k0 + KEEP_SIZE - 1));

      GUARD_SPOTS.add(o.offset(k0 + KEEP_SIZE / 2, KEEP_HEIGHT - 6, k0 + KEEP_SIZE / 2));
      GUARD_SPOTS.add(o.offset(inner0 + cell / 2, 1, inner0 + cell / 2));
   }

   /**
    * The keep's own stairs: one flight per storey, two lanes wide, against the inner wall.
    *
    * <p>This replaced a ladder shaft in the corner, which was the only way up and meant that
    * every floor above the ground was somewhere a fight could happen but nowhere a body could
    * walk to: a ladder is climbed one at a time, holding a key, with the hands that are meant
    * to be holding a sword. A run of stairs is one block of rise per block of run, so it is
    * walked at sprint speed.
    *
    * <p>What was wrong with the first version of it, precisely, was two things: the run carried
    * one tread too few, so the last thing a player did on the way up every storey was jump; and
    * the opening that lets a flight through the floor above was punched by hand, three blocks
    * deep and four wide, beside a run one block wide. Both are the flight's own business now.
    */
   private static void keepStairs(ServerLevel level, BlockPos o, int inner0) {
      for (int storey = 0; storey < 3; storey++) {
         int floorY = storey * STOREY;
         // Three flights: ground to the first floor, first to the second, and second to the
         // roof - which is where the keep's last guard post is.
         flight(
            level,
            o,
            inner0 + 2,
            floorY + 1,
            inner0,
            STOREY,
            Blocks.POLISHED_DEEPSLATE,
            Blocks.POLISHED_BLACKSTONE_BRICK_WALL,
            floorY + STOREY
         );
      }
   }

   /**
    * One kind of room the castle is made of, and what that looks like.
    *
    * <p>The keep used to have one room repeated thirty-five times: a five-by-five patch of one of
    * six palettes, a lantern, a chest, and a single piece of furniture chosen by {@code (i + j) % 5}.
    * That is a corridor with doors, not a castle - the armoury and the chapel were the same room
    * with a different barrel in it, and the only thing that ever differed between two rooms was
    * which way the pattern landed. A room is a plan now: the floor, the light, the furniture, the
    * thing the room is arranged around, what its chest is worth, and what its chest is *about*,
    * so an armoury pays in iron and a library pays in paper before either gets a tier's worth.
    */
   private record RoomPlan(
      String id,
      Block floor,
      Block light,
      Block furniture,
      Block centrepiece,
      int tier,
      List<Item> flavour
   ) {
   }

   /**
    * The eight rooms an ordinary cell of the keep can be, in one table.
    *
    * <p>Every plan in here is used by every castle - the rotation below is a walk of this list,
    * so a plan that is added is a room somebody will walk into, and a plan that is removed is one
    * the audit will miss. That is the property worth having: "the castle has eight kinds of room"
    * is a claim about all of them being reachable, not about eight entries existing.
    */
   /** The bed a barracks is furnished with: a bed is one block per half, and this is that block. */
   private static final Block BARRACKS_BED = Blocks.BED.pick(net.minecraft.world.item.DyeColor.RED);

   /** The bed block itself, for the self-test. */
   public static Block barracksBedForTest() {
      return BARRACKS_BED;
   }

   private static final List<RoomPlan> ROOM_PLANS = List.of(
      new RoomPlan(
         "armoury", Blocks.POLISHED_ANDESITE, Blocks.LANTERN, Blocks.ANVIL, Blocks.SMITHING_TABLE, 2,
         List.of(Items.IRON_INGOT, Items.GOLD_INGOT, Items.DIAMOND)
      ),
      new RoomPlan(
         "library", Blocks.DARK_OAK_PLANKS, Blocks.LANTERN, Blocks.BOOKSHELF, Blocks.LECTERN, 2,
         List.of(Items.PAPER, Items.BOOK, Items.MAP)
      ),
      new RoomPlan(
         "barracks", Blocks.SPRUCE_PLANKS, Blocks.LANTERN, Blocks.BARREL, BARRACKS_BED, 1,
         List.of(Items.ARROW, Items.BREAD, Items.IRON_INGOT)
      ),
      new RoomPlan(
         "banquet", Blocks.OAK_PLANKS, Blocks.LANTERN, Blocks.BARREL, Blocks.CAMPFIRE, 1,
         List.of(Items.COOKED_BEEF, Items.BREAD, Items.SWEET_BERRIES)
      ),
      new RoomPlan(
         "chapel", Blocks.POLISHED_DEEPSLATE, Blocks.SOUL_LANTERN, Blocks.CHISELED_STONE_BRICKS, Blocks.CANDLE, 2,
         List.of(Items.GOLD_INGOT, Items.GLOWSTONE_DUST, Items.AMETHYST_SHARD)
      ),
      new RoomPlan(
         "garden", Blocks.MOSS_BLOCK, Blocks.LANTERN, Blocks.OAK_LEAVES, Blocks.FLOWER_POT, 1,
         List.of(Items.WHEAT, Items.CARROT, Items.MELON_SLICE)
      ),
      new RoomPlan(
         "workshop", Blocks.STONE_BRICKS, Blocks.LANTERN, Blocks.CRAFTING_TABLE, Blocks.ANVIL, 1,
         List.of(Items.IRON_INGOT, Items.REDSTONE, Items.COAL)
      ),
      new RoomPlan(
         "treasury", Blocks.POLISHED_DEEPSLATE, Blocks.SOUL_LANTERN, Blocks.CHISELED_STONE_BRICKS, Blocks.GOLD_BLOCK, 2,
         List.of(Items.GOLD_INGOT, Items.EMERALD, Items.GOLD_NUGGET)
      )
   );

   /**
    * The middle cell of every storey: the room the storey is laid out around.
    *
    * <p>Three tiers of loot, so clearing the keep's centre is paid differently from opening the
    * first door inside the gate - and on the ground floor this is the room the King's hall is
    * built on.
    */
   private static final RoomPlan THE_VAULT = new RoomPlan(
      "vault", Blocks.POLISHED_DEEPSLATE, Blocks.SOUL_LANTERN, Blocks.IRON_BLOCK, Blocks.GOLD_BLOCK, 3,
      List.of(Items.DIAMOND, Items.NETHERITE_SCRAP, Items.EMERALD_BLOCK)
   );

   /** One half of a bed, facing north so the two halves are one piece of furniture. */
   private static BlockState bed(net.minecraft.world.level.block.state.properties.BedPart part) {
      return BARRACKS_BED.defaultBlockState()
         .setValue(net.minecraft.world.level.block.BedBlock.FACING, Direction.NORTH)
         .setValue(net.minecraft.world.level.block.BedBlock.PART, part);
   }

   /** Which kind of room a cell of the keep is: its own cell, or the vault at the middle of it. */
   private static RoomPlan planFor(int storey, int i, int j) {
      if (i == 1 && j == 1) {
         return THE_VAULT;
      }

      return ROOM_PLANS.get(Math.floorMod(storey * 9 + i * 3 + j, ROOM_PLANS.size()));
   }

   /**
    * One room as designed: where it is, what should be in it, and what its chest is worth.
    *
    * <p>Kept so the self-test can walk a settled castle and ask whether the world it built is the
    * design it wrote down. That is a check this structure has needed for a while: the failures of
    * the last few batches - a canopy over the King, a hand-punched hole beside the stairs, a
    * room's own floor replaced by whatever was planned after it - are all one later plan
    * overwriting an earlier one, and none of them were visible from the design at all.
    */
   public record RoomAt(
      String plan,
      BlockPos floor,
      BlockPos chest,
      BlockPos signature,
      Block signatureBlock,
      BlockPos centrepiece,
      Block centrepieceBlock,
      BlockPos light,
      Block lightBlock,
      Block floorBlock,
      int tier,
      boolean wing
   ) {
   }

   private static final List<RoomAt> DESIGN_ROOMS = new ArrayList<>();

   /** Every room a castle would build, for the self-test: what and where, not just how many. */
   public static List<RoomAt> roomsForTest() {
      return List.copyOf(DESIGN_ROOMS);
   }

   /** The rooms a castle can contain, for the self-test. A plan nobody uses is not a plan. */
   public static List<String> roomPlanIdsForTest() {
      List<String> ids = new ArrayList<>();
      for (RoomPlan plan : ROOM_PLANS) {
         ids.add(plan.id());
      }
      ids.add(THE_VAULT.id());
      return ids;
   }

   /**
    * One room's own character: its floor, its light, its chest, and what the room is for.
    *
    * <p>The carpet at the doorways is not decoration for its own sake: a rug through every door
    * is what turns a nine-cell grid into a court you can see the shape of, and it is the only
    * thing in the design that is drawn by the walls rather than by the rooms.
    */
   private static void decorateRoom(ServerLevel level, BlockPos o, int cx, int floorY, int cz, int storey, int i, int j) {
      RoomPlan plan = planFor(storey, i, j);

      for (int dx = -2; dx <= 2; dx++) {
         for (int dz = -2; dz <= 2; dz++) {
            plan(level, o.offset(cx + dx, floorY, cz + dz), plan.floor());
         }
      }

      BlockPos lightPos = o.offset(cx, floorY + STOREY - 1, cz);
      plan(level, lightPos, plan.light());
      BlockPos chestPos = o.offset(cx + 1, floorY + 1, cz + 1);
      plan(level, chestPos, Blocks.CHEST);
      LOOT.add(new Spot(chestPos, plan.tier(), plan.id()));

      // What the room is arranged around: its own furniture against the wall, and the thing the
      // room exists for standing in the middle of it. The barracks is the one room whose
      // centrepiece is two blocks - a bed is placed as a pair, and a single half of one pops off
      // the moment the world ticks.
      BlockPos signature = o.offset(cx - 2, floorY + 1, cz + 2);
      plan(level, signature, plan.furniture());
      plan(level, signature.above(), plan.furniture());
      BlockPos centrepiece = o.offset(cx + 2, floorY + 1, cz + 1);
      if (plan.centrepiece() == BARRACKS_BED) {
         plan(level, centrepiece, bed(BedPart.FOOT));
         plan(level, centrepiece.north(), bed(BedPart.HEAD));
      } else {
         plan(level, centrepiece, plan.centrepiece());
      }

      DESIGN_ROOMS.add(
         new RoomAt(
            plan.id(),
            o.offset(cx, floorY, cz),
            chestPos,
            signature,
            plan.furniture(),
            centrepiece,
            plan.centrepiece(),
            lightPos,
            plan.light(),
            plan.floor(),
            plan.tier(),
            false
         )
      );

      if (LIBRARY_ROOM.equals(plan.id())) {
         buildGrandLibrary(level, o, cx, floorY, cz, plan.tier());
      }

      if (roomsBuilt % 3 == 0) {
         GUARD_SPOTS.add(o.offset(cx, floorY + 1, cz));
      }
   }

   /** Grand libraries raised this rise, and the shelf blocks that went into them. */
   private static int grandLibraries;
   private static int grandLibraryShelves;

   /** For the self-test: a library that is one bookshelf and a lectern is not a library. */
   public static int grandLibrariesForTest() {
      return grandLibraries;
   }

   /** The blocks a grand library is furnished from, for the self-test. */
   public static java.util.List<Block> libraryFurnitureForTest() {
      return java.util.List.of(
         Blocks.BOOKSHELF, Blocks.CHISELED_BOOKSHELF, Blocks.LECTERN, Blocks.CANDLE, Blocks.DARK_OAK_LOG
      );
   }

   /**
    * How many shelf blocks one grand library is built from: the ring of the room's outer cells
    * (sixteen) minus the four lantern bays, times the two courses of shelving. Written out so a
    * "grand" library that quietly became a single bookshelf fails a check instead of shipping.
    */
   public static int libraryShelfBlocksExpected() {
      // Sixteen cells in the ring, four lantern bays, two cells the room's own design owns
      // (its furniture corner and the thing it is arranged around), two courses of shelf.
      return (16 - 4 - 2) * 2;
   }

   public static int grandLibraryShelvesForTest() {
      return grandLibraryShelves;
   }

   /**
    * The library, furnished as a library.
    *
    * <p>Every room used to get the same three things - a chest in one corner, a piece of
    * furniture in another, a centrepiece on the floor - which is a room, not a room of books.
    * The library is the one whose whole identity is what is on its <i>shelves</i>, so it gets
    * the shelf-wall treatment: two courses of shelving around the whole room, chiselled books
    * on the upper course, lantern bays where a doorway could be, reading desks with candles,
    * and two archive chests whose loot is a tier above the room's own.
    *
    * <p>The four cardinal cells are deliberately left free. The castle cuts passages into
    * rooms, and a shelf built across the only way in is a room nobody can enter - which is
    * exactly the kind of "improvement" that reads as a bug in game.
    */
   private static void buildGrandLibrary(ServerLevel level, BlockPos o, int cx, int floorY, int cz, int tier) {
      grandLibraries++;

      for (int dx = -2; dx <= 2; dx++) {
         for (int dz = -2; dz <= 2; dz++) {
            int d = Math.max(Math.abs(dx), Math.abs(dz));

            // The floor is the room plan's own and is deliberately left alone: the design
            // records it, and a later pass that redraws it is exactly the "something was
            // planned over it" the room-integrity check exists to catch.
            if (d != 2) {
               continue;
            }

            // A lantern bay: the room keeps its ways in, and the light is where the passage
            // is rather than in the middle of a wall of books.
            if (dx == 0 || dz == 0) {
               plan(level, o.offset(cx + dx, floorY + 1, cz + dz), Blocks.AIR.defaultBlockState());
               plan(level, o.offset(cx + dx, floorY + 2, cz + dz), Blocks.LANTERN.defaultBlockState());
               continue;
            }

            // Two cells of the ring are left to the room's own design: the corner the plan
            // puts its furniture in and the one it arranges itself around. The shelf wall
            // is built between them, not over them.
            if ((dx == -2 && dz == 2) || (dx == 2 && dz == 1)) {
               continue;
            }

            // Two courses of shelf, corner to corner, with the chiselled books above the
            // plain ones. This is what makes the room read as a library from the doorway.
            plan(level, o.offset(cx + dx, floorY + 1, cz + dz), Blocks.BOOKSHELF.defaultBlockState());
            plan(level, o.offset(cx + dx, floorY + 2, cz + dz), Blocks.CHISELED_BOOKSHELF.defaultBlockState());
            grandLibraryShelves += 2;
         }
      }

      // Reading desks inside the ring of shelves, with a candle on each and a chest under
      // the far one: the archive is where the tomes are kept, and it is worth the walk.
      BlockPos[] desks = {
         o.offset(cx - 1, floorY + 1, cz - 1),
         o.offset(cx + 1, floorY + 1, cz - 1),
         o.offset(cx - 1, floorY + 1, cz + 1)
      };
      for (BlockPos desk : desks) {
         plan(level, desk, Blocks.LECTERN.defaultBlockState());
         plan(level, desk.above(), Blocks.CANDLE.defaultBlockState());
      }

      // Two archive cases on the reading floor, both new cells: the room's own chest already
      // has a spot, and a second one on the same block would stock the same chest twice.
      BlockPos[] archives = {
         o.offset(cx + 1, floorY + 1, cz),
         o.offset(cx, floorY + 1, cz - 1)
      };
      for (BlockPos archive : archives) {
         if (!level.getBlockState(archive).isAir()) {
            continue;
         }
         plan(level, archive, Blocks.CHEST.defaultBlockState());
         LOOT.add(new Spot(archive, Math.min(3, tier + 1), LIBRARY_ROOM));
      }
   }

   /** A long hall hanging off the keep, two storeys of four rooms each. */
   private static void wing(ServerLevel level, BlockPos o, int x0, int z0) {
      int w = 29;
      int d = 15;
      BUILDINGS.add(new Footprint(x0, z0, x0 + w - 1, z0 + d - 1));

      for (int dx = 0; dx < w; dx++) {
         for (int dz = 0; dz < d; dz++) {
            boolean edge = dx == 0 || dz == 0 || dx == w - 1 || dz == d - 1;
            boolean joining = dx == 0 || dx == w - 1;
            for (int h = 0; h < 13; h++) {
               if (edge) {
                  // A wide arch onto the courtyard: the wing is entered, not climbed into.
                  boolean arch = joining && dz >= 5 && dz <= 9 && h <= 3;
                  if (arch) {
                     plan(level, o.offset(x0 + dx, h, z0 + dz), Blocks.AIR);
                     continue;
                  }
                  boolean window = dz % 5 == 2 && (h == 3 || h == 4 || h == 9 || h == 10);
                  plan(level, o.offset(x0 + dx, h, z0 + dz), window ? Blocks.GLASS
                     : (h == 6 ? Blocks.DEEPSLATE_TILES : Blocks.STONE_BRICKS));
                  continue;
               }
               if (h == 6) {
                  plan(level, o.offset(x0 + dx, h, z0 + dz), Blocks.DARK_OAK_PLANKS);
               } else if (h == 12) {
                  plan(level, o.offset(x0 + dx, h, z0 + dz), Blocks.DEEPSLATE_TILES);
               }
            }
         }
      }

      // Eight rooms, four to a storey, and a light and a chest in each.
      for (int storey = 0; storey < 2; storey++) {
         int floorY = storey * 6;

         for (int i = 0; i < 2; i++) {
            for (int j = 0; j < 2; j++) {
               roomsBuilt++;
               int rx = x0 + 2 + i * 13;
               int rz = z0 + 2 + j * 7;

               for (int step = 0; step < 11; step++) {
                  for (int h = floorY + 1; h <= floorY + 5; h++) {
                     boolean doorway = step == 5 && h <= floorY + 3;
                     if (i < 1) {
                        plan(level, o.offset(rx + 12, h, rz + step), doorway ? Blocks.AIR : Blocks.SPRUCE_PLANKS);
                     }
                     if (j < 1) {
                        plan(level, o.offset(rx + step, h, rz + 6), doorway ? Blocks.AIR : Blocks.SPRUCE_PLANKS);
                     }
                  }
               }

               // The wing is furnished from the same table as the keep, so a hall off the
               // courtyard is a barracks or a library rather than a bare room with a bookshelf
               // in the corner of every single one.
               RoomPlan plan = ROOM_PLANS.get(Math.floorMod(storey * 4 + i * 2 + j, ROOM_PLANS.size()));

               for (int dx = -2; dx <= 2; dx++) {
                  for (int dz = -2; dz <= 2; dz++) {
                     plan(level, o.offset(rx + 5 + dx, floorY, rz + 3 + dz), plan.floor());
                  }
               }

               BlockPos lightPos = o.offset(rx + 5, floorY + 4, rz + 3);
               plan(level, lightPos, plan.light());
               BlockPos chestPos = o.offset(rx + 6, floorY + 1, rz + 3);
               plan(level, chestPos, Blocks.CHEST);
               LOOT.add(new Spot(chestPos, 2, plan.id()));

               // Against the wall the wing's own flight does not use: both flights run up the
               // z = rz + 2 lane and their supports reach a block below each tread, so the room's
               // furniture belongs on the far side of that lane rather than in its path.
               BlockPos signature = o.offset(rx + 2, floorY + 1, rz + 4);
               plan(level, signature, plan.furniture());
               plan(level, signature.above(), plan.furniture());
               BlockPos centrepiece = o.offset(rx + 8, floorY + 1, rz + 4);
               if (plan.centrepiece() == BARRACKS_BED) {
                  plan(level, centrepiece, bed(BedPart.FOOT));
                  plan(level, centrepiece.north(), bed(BedPart.HEAD));
               } else {
                  plan(level, centrepiece, plan.centrepiece());
               }

               DESIGN_ROOMS.add(
                  new RoomAt(
                     plan.id(),
                     o.offset(rx + 5, floorY, rz + 3),
                     chestPos,
                     signature,
                     plan.furniture(),
                     centrepiece,
                     plan.centrepiece(),
                     lightPos,
                     plan.light(),
                     plan.floor(),
                     2,
                     true
                  )
               );
            }
         }

      }

      wingStairs(level, o, x0, z0);

      GUARD_SPOTS.add(o.offset(x0 + w / 2, 1, z0 + d / 2));
      GUARD_SPOTS.add(o.offset(x0 + w / 2, 7, z0 + d / 2));
   }

   /**
    * The wing's stairs: the ground to the first storey, and the first storey to the roof.
    *
    * <p>Two flights rather than two ladders, and both land in an opening punched through the
    * floor they arrive at - the flight's own footprint, and nothing else (see flight).
    */
   private static void wingStairs(ServerLevel level, BlockPos o, int x0, int z0) {
      // The wing's first floor is at six and its roof at twelve, so each flight is six treads
      // and ends level with the floor it arrives on.
      flight(level, o, x0 + 20, 1, z0 + 2, 6, Blocks.SPRUCE_PLANKS, Blocks.SPRUCE_PLANKS, 6);
      flight(level, o, x0 + 4, 7, z0 + 2, 6, Blocks.SPRUCE_PLANKS, Blocks.SPRUCE_PLANKS, 12);
   }

   /** The courtyard: the road in, four fountains, and lanterns along the walls. */
   private static void courtyard(ServerLevel level, BlockPos o) {
      int gate = SIZE / 2;

      for (int dz = 4; dz < 30; dz++) {
         for (int dx = gate - 4; dx <= gate + 4; dx++) {
            plan(level, o.offset(dx, 0, dz), (dx + dz) % 3 == 0 ? Blocks.POLISHED_ANDESITE : Blocks.STONE_BRICKS);
         }
      }

      for (int dz = 71; dz < 97; dz++) {
         for (int dx = gate - 4; dx <= gate + 4; dx++) {
            plan(level, o.offset(dx, 0, dz), (dx + dz) % 3 == 0 ? Blocks.POLISHED_ANDESITE : Blocks.STONE_BRICKS);
         }
      }

      // Everything the road does not cover, except the buildings: the keep and the wings laid
      // their own floors a moment ago, and paving over them is what made every ground-floor room
      // of the castle read as the courtyard.
      for (int dx = 1; dx < SIZE - 1; dx++) {
         for (int dz = 1; dz < SIZE - 1; dz++) {
            if (dx >= gate - 4 && dx <= gate + 4) {
               continue;
            }
            if (insideBuilding(dx, dz)) {
               continue;
            }
            plan(level, o.offset(dx, 0, dz), (dx * dz) % 5 == 0 ? Blocks.MOSSY_STONE_BRICKS : Blocks.STONE_BRICKS);
         }
      }

      fountain(level, o, gate - 20, 20);
      fountain(level, o, gate + 20, 20);
      fountain(level, o, gate - 20, 80);
      fountain(level, o, gate + 20, 80);

      for (int i = 6; i < SIZE - 6; i += 12) {
         plan(level, o.offset(i, 1, 5), Blocks.POLISHED_BLACKSTONE_BRICK_WALL);
         plan(level, o.offset(i, 2, 5), Blocks.LANTERN);
         plan(level, o.offset(i, 1, SIZE - 6), Blocks.POLISHED_BLACKSTONE_BRICK_WALL);
         plan(level, o.offset(i, 2, SIZE - 6), Blocks.LANTERN);
         plan(level, o.offset(5, 1, i), Blocks.POLISHED_BLACKSTONE_BRICK_WALL);
         plan(level, o.offset(5, 2, i), Blocks.LANTERN);
         plan(level, o.offset(SIZE - 6, 1, i), Blocks.POLISHED_BLACKSTONE_BRICK_WALL);
         plan(level, o.offset(SIZE - 6, 2, i), Blocks.LANTERN);
      }
   }

   private static void fountain(ServerLevel level, BlockPos o, int cx, int cz) {
      for (int dx = -3; dx <= 3; dx++) {
         for (int dz = -3; dz <= 3; dz++) {
            boolean rim = Math.abs(dx) == 3 || Math.abs(dz) == 3;
            plan(level, o.offset(cx + dx, 0, cz + dz), rim ? Blocks.STONE_BRICKS : Blocks.WATER);
            if (rim && (dx + dz) % 2 == 0) {
               plan(level, o.offset(cx + dx, 1, cz + dz), Blocks.STONE_BRICK_WALL);
            }
         }
      }
      plan(level, o.offset(cx, 1, cz), Blocks.POLISHED_DEEPSLATE);
      plan(level, o.offset(cx, 2, cz), Blocks.POLISHED_DEEPSLATE);
      plan(level, o.offset(cx, 3, cz), Blocks.LANTERN);
   }

   /** Trees, because a courtyard of stone is a car park. */
   private static void garden(ServerLevel level, BlockPos o, RandomSource deco) {
      for (int i = 0; i < 14; i++) {
         int dx = 8 + deco.nextInt(SIZE - 16);
         int dz = 8 + deco.nextInt(SIZE - 16);
         if (Math.abs(dx - SIZE / 2) < 24 && Math.abs(dz - SIZE / 2) < 24) {
            continue;
         }
         // And not through a building: a garden is the space between the walls, and a tree
         // planted through a wing is a trunk through somebody's library.
         if (insideBuilding(dx - 2, dz - 2) || insideBuilding(dx + 2, dz + 2)) {
            continue;
         }

         int height = 4 + deco.nextInt(3);
         for (int h = 0; h < height; h++) {
            plan(level, o.offset(dx, h + 1, dz), Blocks.OAK_LOG);
         }
         for (int lx = -2; lx <= 2; lx++) {
            for (int lz = -2; lz <= 2; lz++) {
               for (int ly = 0; ly <= 2; ly++) {
                  if (Math.abs(lx) + Math.abs(lz) + ly > 4) {
                     continue;
                  }
                  // Persistent, or the crown decays the moment a neighbour updates: leaves
                  // placed by hand carry the flag, and a tree the castle planted has no log
                  // under most of it to hold them on.
                  plan(level, o.offset(dx + lx, height + ly, dz + lz), Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true));
               }
            }
         }
      }
   }

   /**
    * Records one block of the castle.
    *
    * <p>Two rules in one place: a block that cannot be broken (bedrock, a barrier) is never
    * overwritten and never claimed, and a position that has already been planned is
    * <b>overwritten</b> in the plan rather than added twice - which is what lets the design
    * lay a floor across the whole plinth and then draw rooms on top of it.
    */
   private static void plan(ServerLevel level, BlockPos pos, Block block) {
      BlockPos at = pos.immutable();
      Integer known = INDEX.get(at);

      if (known != null) {
         PLAN.get(known).after = block.defaultBlockState();
         return;
      }

      BlockState before;
      try {
         before = level.getBlockState(at);
      } catch (Throwable t) {
         return;
      }

      if (before.getDestroySpeed(level, at) < 0.0F) {
         return;
      }

      // Somebody else's block is not terrain.
      //
      // The castle is built over whatever happens to be there, and terrain is fair game -
      // grass, stone, a tree. A chest is not: neither is a bed, which is a player's respawn
      // point, nor a spawner, a beacon or a portal. Those are left exactly where they are and
      // the castle has a gap where they are, which is the only honest way to raise a
      // structure over somebody's base. Nothing is recorded for them, so nothing is undone
      // for them either - the undo list names what this ever touched.
      if (isSomebodyElses(before)) {
         return;
      }

      INDEX.put(at, PLAN.size());
      PLAN.add(new Place(at, before, block.defaultBlockState()));
   }

   /** A block that belongs to somebody, and that a mirage has no business covering. */
   private static boolean isSomebodyElses(BlockState state) {
      String path = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
      return state.is(Blocks.CHEST)
         || state.is(Blocks.TRAPPED_CHEST)
         || state.is(Blocks.ENDER_CHEST)
         || state.is(Blocks.BARREL)
         || state.is(Blocks.SPAWNER)
         || state.is(Blocks.BEACON)
         || state.is(Blocks.RESPAWN_ANCHOR)
         || state.is(Blocks.NETHER_PORTAL)
         || state.is(Blocks.END_PORTAL)
         || state.is(Blocks.END_PORTAL_FRAME)
         || path.endsWith("_bed")
         || path.contains("shulker_box");
   }

   /** True for the elite, for the reward and for the death broadcast. */
   public static boolean isCaptain(Entity entity) {
      return entity != null && entity.entityTags().contains(CAPTAIN_TAG);
   }

   /**
    * True when something is already planned for this cell.
    *
    * <p>{@link #plan} overwrites, which is what lets the design lay a whole floor and then draw
    * rooms on it - but a later pass can also quietly bury what an earlier one built, and the
    * design cannot tell the difference. This is how a pass that means to *decorate* a room asks
    * whether the room has already spoken.
    */
   private static boolean alreadyPlanned(BlockPos pos) {
      return INDEX.containsKey(pos.immutable());
   }

   /** The same, for a block state the caller has already shaped (a ladder's facing). */
   private static void plan(ServerLevel level, BlockPos pos, BlockState state) {
      BlockPos at = pos.immutable();
      Integer known = INDEX.get(at);

      if (known != null) {
         PLAN.get(known).after = state;
         return;
      }

      BlockState before;
      try {
         before = level.getBlockState(at);
      } catch (Throwable t) {
         return;
      }

      if (before.getDestroySpeed(level, at) < 0.0F) {
         return;
      }

      // See the note in the other overload: somebody else's block is not terrain.
      if (isSomebodyElses(before)) {
         return;
      }

      INDEX.put(at, PLAN.size());
      PLAN.add(new Place(at, before, state));
   }

   /** One step of a stairway, set to ascend in the direction the walker is going. */
   private static BlockState stair(Direction facing) {
      return Blocks.STONE_BRICK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, facing);
   }

   /**
    * One flight of stairs a body can actually walk - which is four separate promises, and this
    * is the only place in the castle that makes all four.
    *
    * <ul>
    *   <li><b>Long enough.</b> A rise of `steps` needs `steps` treads, not `steps - 1`: the last
    *       tread has to sit at the level of the floor it arrives on, so that its upper half is
    *       flush with that floor's surface. One tread short and the last thing every player does
    *       on the way up a storey is jump.</li>
    *   <li><b>Wide enough.</b> Two lanes, both stepped: a second lane of open air beside a
    *       staircase is not a second lane, it is a ledge that needs a jump per step.</li>
    *   <li><b>Solid underneath and clear above</b>, both lanes, every tread.</li>
    *   <li><b>Its own opening through the floor above</b>, exactly the two columns behind the
    *       top tread whose headroom is inside that slab - and no wider, because every other
    *       column punched through a floor is a place somebody falls into the room below.</li>
    * </ul>
    *
    * @param x       the column of the first tread
    * @param y       the level of the first tread - one above the floor the flight starts from
    * @param z       the first of the two lanes; the flight also uses {@code z + 1}
    * @param steps   treads in the flight, which is also how many blocks it rises
    * @param support the block under a tread, which is what makes the flight a flight
    * @param rail    the block for the open side's rail, or null for none
    * @param through the level of the floor slab this flight passes through at the top
    */
   private static void flight(
      ServerLevel level, BlockPos o, int x, int y, int z, int steps, Block support, Block rail, int through
   ) {
      for (int i = 0; i < steps; i++) {
         int tx = x + i;
         int ty = y + i;

         for (int lane = 0; lane < 2; lane++) {
            plan(level, o.offset(tx, ty - 1, z + lane), support);
            plan(level, o.offset(tx, ty, z + lane), stair(Direction.EAST));
            plan(level, o.offset(tx, ty + 1, z + lane), Blocks.AIR);
            plan(level, o.offset(tx, ty + 2, z + lane), Blocks.AIR);
            TREADS.add(o.offset(tx, ty, z + lane));
         }

         if (rail != null) {
            plan(level, o.offset(tx, ty, z + 2), rail);
            plan(level, o.offset(tx, ty + 1, z + 2), rail);
         }
      }

      // The two columns whose headroom is inside the slab: the treads one and two below the
      // floor it arrives on. The top tread is level with that floor and takes the slab's place.
      for (int i = steps - 3; i <= steps - 2; i++) {
         for (int lane = 0; lane < 2; lane++) {
            BlockPos opened = o.offset(x + i, through, z + lane);
            plan(level, opened, Blocks.AIR);
            PUNCHES.add(opened);
         }
      }
   }

   // ------------------------------------------------------------------- rising

   /**
    * One tick of the build: the next few hundred blocks, and then the finishing touches.
    *
    * <p>Called from the disaster manager's tick for as long as a castle is standing, so a
    * rise that is interrupted by the event ending is simply never finished - and the undo
    * puts back exactly the blocks that had really been placed, because the plan is in order.
    */
   public static void tick(MinecraftServer server) {
      if (!isStanding() || standing == null) {
         return;
      }

      if (risen < PLAN.size()) {
         int budget = RISE_PER_TICK;

         while (budget-- > 0 && risen < PLAN.size()) {
            Place place = PLAN.get(risen++);
            try {
               standing.setBlock(place.pos, place.after, 3);
            } catch (Throwable ignored) {
               // A chunk that went away mid-rise is not a reason to stop the castle.
            }
         }

         long now = standing.getGameTime();
         if (now - lastAmbience >= 4L) {
            lastAmbience = now;
            double t = (double)risen / Math.max(1, PLAN.size());
            int x = origin.getX() + SIZE / 2;
            int z = origin.getZ() + SIZE / 2;
            mirageParticles(
               ParticleTypes.PORTAL, x + 0.5, origin.getY() + 2.0 + t * TOWER_HEIGHT, z + 0.5,
               30, SIZE / 3.0, 4.0, SIZE / 3.0, 0.35
            );
            standing.playSound(null, x, origin.getY(), z, SoundEvents.BEACON_AMBIENT, SoundSource.WEATHER, 1.2F, 0.6F + (float)t * 0.6F);
         }
         return;
      }

      if (!settled) {
         settled = true;
         stockChests();
         spawnGuards(server);
         seatTheKing(server);
         BlockPos middle0 = origin.offset(SIZE / 2, 0, SIZE / 2);
         standing.playSound(null, middle0, SoundEvents.BEACON_POWER_SELECT, SoundSource.WEATHER, 1.6F, 0.8F);

         for (ServerPlayer p : standing.getPlayers(pl -> pl.isAlive())) {
            Chat.raw(p, "§d§lThe mirage settles.§r §7" + roomsBuilt + " rooms, and the Royal Guard is already walking them.");
         }
         return;
      }

      // The chests are re-asked while the castle stands, and not only as it is stocked.
      //
      // A chest is part of the save, so a stack that was put in one under an older loot rule - or
      // by a player who thought a castle chest was a cupboard - cannot be reached by editing a
      // loot table at all. Every five seconds the rule is put to the real world instead, and what
      // the castle withholds comes back out. It is not dropped on the floor: it was never the
      // castle's to give, and a chest that quietly keeps one is the report this answers.
      if (standing.getGameTime() % 100L == 0L) {
         int taken = sweepWithheldLoot();
         if (taken > 0) {
            for (ServerPlayer p : standing.getPlayers(pl -> pl.isAlive())) {
               Chat.raw(p, "\u00a77\u00a7oThe castle takes back what it never meant to give.");
            }
         }
      }

      // The elite, once the guard is gone. Checked here rather than on a guard's death so a
      // guard that fell to a fall, a fall of gravel or an operator's /kill counts the same as
      // one that was fought, and so the check cannot be dodged by killing the last one with
      // something that does not fire the drop hook.
      tickKing(server);
      tickCollapse(server);

      if (captain == null && !captainSpent && standing.getGameTime() % 40L == 0L) {
         boolean anyone = false;
         for (ServerPlayer p : standing.getPlayers(pl -> pl.isAlive() && !pl.isSpectator() && inside(pl))) {
            anyone = true;
            break;
         }
         if (captainReady(guardsAlive(server), anyone, captainSpent)) {
            spawnCaptain(server);
         }
      }

   }

   /**
    * True for a stack the castle refuses to hand out - see {@link #CASTLE_WITHHOLDS}.
    *
    * <p>One question, asked by both halves of the rule: as the loot is built, and again over the
    * chests that are already in the world.
    */
   public static boolean withheldFromTheCastle(ItemStack stack) {
      if (stack == null || stack.isEmpty()) {
         return false;
      }

      for (Item item : CASTLE_WITHHOLDS) {
         if (stack.is(item)) {
            return true;
         }
      }

      return false;
   }

   /** What the castle withholds, for the self-test - an empty list here would make the checks mute. */
   public static List<Item> withheldItemsForTest() {
      return List.copyOf(CASTLE_WITHHOLDS);
   }

   /**
    * Takes back out of the castle's own chests anything the castle withholds.
    *
    * <p>This is the half that a code change alone can never do. A chest is part of the save: a
    * chest that was stocked by an older loot rule, or filled by a player, still holds whatever it
    * was given, and no amount of editing the loot table reaches into it. So the rule is re-asked
    * of the real world while the castle stands - and it keeps asking, because the next elytra can
    * arrive the same way the last one did.
    *
    * @return how many stacks were taken out, so the self-test can ask whether it happened
    */
   public static int sweepWithheldLoot() {
      if (standing == null || LOOT.isEmpty()) {
         return 0;
      }

      int taken = 0;

      for (Spot spot : LOOT) {
         try {
            if (!(standing.getBlockEntity(spot.pos()) instanceof ChestBlockEntity chest)) {
               continue;
            }

            for (int slot = 0; slot < chest.getContainerSize(); slot++) {
               if (withheldFromTheCastle(chest.getItem(slot))) {
                  chest.setItem(slot, ItemStack.EMPTY);
                  taken++;
               }
            }
         } catch (Throwable ignored) {
            // A chest that went away with its chunk is not a reason to stop sweeping.
         }
      }

      return taken;
   }

   /** Every chest the design recorded, for the self-test. */
   public static List<BlockPos> lootSpotsForTest() {
      List<BlockPos> out = new ArrayList<>();
      for (Spot spot : LOOT) {
         out.add(spot.pos());
      }

      return out;
   }

   /** One chest's worth of loot, for the self-test: the same call the castle stocks from. */
   public static List<ItemStack> lootForTest(int tier, String roomPlan) {
      return lootFor(tier, roomPlan);
   }

   /** Fills every chest the design marked, once the chests really exist. */
   private static void stockChests() {
      if (standing == null) {
         return;
      }

      for (Spot spot : LOOT) {
         try {
            if (standing.getBlockEntity(spot.pos()) instanceof ChestBlockEntity chest) {
               List<ItemStack> loot = lootFor(spot.tier(), spot.roomPlan());
               int slot = 0;
               for (ItemStack stack : loot) {
                  if (slot >= chest.getContainerSize()) {
                     break;
                  }
                  chest.setItem(slot++, stack);
               }
            }
         } catch (Throwable ignored) {
         }
      }

      // Nothing to take out of a chest this castle just filled - but the same call is made on
      // every settle, so the sweep is part of rising rather than a thing somebody remembers.
      sweepWithheldLoot();
   }

   /**
    * One plan by id, or null for a chest that belongs to no room (the throne hall's two).
    *
    * <p>Read from the same table the design was built from, so a room and the chest inside it can
    * never disagree about which room they are.
    */
   private static RoomPlan planById(String id) {
      if (id == null) {
         return null;
      }

      for (RoomPlan plan : ROOM_PLANS) {
         if (plan.id().equals(id)) {
            return plan;
         }
      }

      return THE_VAULT.id().equals(id) ? THE_VAULT : null;
   }

   /**
    * What a room is worth walking to: three tiers, and the room's own flavour on top of it.
    *
    * <p>The tier says how good it is and the room says what it is good at, which is the part that
    * was missing: an armoury chest that is worth tier two should contain armour, and a library's
    * should contain paper, or the castle's thirty-five chests are thirty-five copies of one tier
    * table with a different number on it.
    */
   private static List<ItemStack> lootFor(int tier, String roomPlan) {
      List<ItemStack> loot = new ArrayList<>();

      // Everything this room would pay, minus what the castle withholds. Filtered here rather
      // than at the chest, because this is the one call that builds loot: a room plan, a tier and
      // the throne hall's own tribute chests all come through it, and a future faucet that forgets
      // the rule is the one thing a comment cannot stop.
      for (ItemStack stack : lootForTier(tier)) {
         if (!withheldFromTheCastle(stack)) {
            loot.add(stack);
         }
      }

      RoomPlan plan = planById(roomPlan);
      if (plan != null && !plan.flavour().isEmpty()) {
         Item flavour = pick(plan.flavour().toArray(new Item[0]));
         ItemStack stack = new ItemStack(flavour);
         if (!withheldFromTheCastle(stack)) {
            loot.add(new ItemStack(flavour, stack.getMaxStackSize() > 1 ? 2 + RANDOM.nextInt(4) : 1));
         }
      }

      // The library is the one shelf in the world that carries the mod's own
      // enchantments. They used to be boss loot and nothing else, which made asking
      // "how do I get one of these" have exactly one answer: kill a raid boss. A
      // forgotten court's library is where knowledge is kept, so it is where they are
      // found - a book already on the shelf, not a trophy off a corpse.
      if (LIBRARY_ROOM.equals(roomPlan)) {
         ItemStack tome = libraryTome(RANDOM);
         if (tome != null && !withheldFromTheCastle(tome)) {
            loot.add(tome);
         }
      }

      return loot;
   }

   /** The room whose chests sell knowledge. */
   private static final String LIBRARY_ROOM = "library";

   /**
    * The library shelf: every custom enchantment in the mod, so there is a way to find
    * each one without killing the boss that carries it. Levels are deliberately low - the
    * books teach the enchantment, the bosses still own the top of the scale.
    */
   private static final List<String> LIBRARY_SHELF = List.of(CustomEnchantments.ALL_KEYS);

   /** How often a library chest is a tome at all. */
   private static final double LIBRARY_TOME_CHANCE = 0.35;

   /**
    * One shelved tome, or null when this chest is not one.
    *
    * <p>Takes the {@link Random} so the self-test can drive it deterministically and
    * assert the shelf really does cover every key - a library that quietly shelved a
    * subset would be the same bug as no library at all, one key at a time.
    */
   public static ItemStack libraryTome(Random random) {
      if (random == null || random.nextDouble() >= LIBRARY_TOME_CHANCE) {
         return null;
      }

      String key = LIBRARY_SHELF.get(random.nextInt(LIBRARY_SHELF.size()));
      int roll = random.nextInt(100);
      int level = roll < 60 ? 1 : roll < 90 ? 2 : 3;
      return CustomEnchantments.tome(key, level, "Recovered from the mirage's library.");
   }

   /** The shelf, for the self-test. */
   public static List<String> libraryShelfForTest() {
      return List.copyOf(LIBRARY_SHELF);
   }

   /** What one tier of loot is worth, before the room it stands in has its say. */
   private static List<ItemStack> lootForTier(int tier) {
      List<ItemStack> loot = new ArrayList<>();

      if (tier <= 1) {
         // A hall: enough to hold the ground you took with what you found, which is what a
         // room you walked into should be worth. The pool is wider than it is deep, so two
         // halls are not the same hall twice.
         loot.add(new ItemStack(Items.IRON_INGOT, 4 + RANDOM.nextInt(6)));
         loot.add(new ItemStack(Items.GOLD_INGOT, 2 + RANDOM.nextInt(5)));
         loot.add(new ItemStack(Items.COAL, 6 + RANDOM.nextInt(10)));
         loot.add(new ItemStack(Items.BREAD, 3 + RANDOM.nextInt(4)));
         loot.add(new ItemStack(Items.EXPERIENCE_BOTTLE, 2 + RANDOM.nextInt(4)));
         loot.add(new ItemStack(pick(Items.ARROW, Items.TORCH, Items.STRING, Items.LEATHER), 4 + RANDOM.nextInt(8)));
         loot.add(new ItemStack(pick(Items.OAK_LOG, Items.STONE_BRICKS, Items.GLASS, Items.HAY_BLOCK), 6 + RANDOM.nextInt(10)));
         if (RANDOM.nextInt(3) == 0) {
            loot.add(new ItemStack(Items.CHAINMAIL_CHESTPLATE));
         }
         return loot;
      }

      if (tier == 2) {
         // A wing: the armoury. Gear you can go on fighting in.
         loot.add(new ItemStack(Items.DIAMOND, 2 + RANDOM.nextInt(4)));
         loot.add(new ItemStack(Items.EMERALD, 4 + RANDOM.nextInt(8)));
         loot.add(new ItemStack(Items.GOLDEN_APPLE, 1 + RANDOM.nextInt(2)));
         loot.add(new ItemStack(Items.EXPERIENCE_BOTTLE, 3 + RANDOM.nextInt(6)));
         loot.add(new ItemStack(pick(Items.DIAMOND_SWORD, Items.DIAMOND_AXE, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_HELMET)));
         loot.add(new ItemStack(pick(Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS, Items.BOW, Items.CROSSBOW)));
         loot.add(new ItemStack(pick(Items.SPECTRAL_ARROW, Items.TIPPED_ARROW, Items.ARROW), 8 + RANDOM.nextInt(12)));
         if (RANDOM.nextBoolean()) {
            loot.add(new ItemStack(Items.ENCHANTED_BOOK));
         }
         return loot;
      }

      // The vault, and the throne's own tribute chests: the best this castle has, which is
      // deliberately **material, not finished**. What is here is what a court would have kept in
      // a chest before it was worth anything to anyone - scrap, ancient debris, raw treasure - and
      // not the trophies that skip the work the collected materials are for. A beacon and a
      // netherite chestplate were the two that read as "a castle chest paid me a finished build",
      // so they are out; the scrap and the debris that make a player go and *make* the thing stay.
      loot.add(new ItemStack(Items.NETHERITE_SCRAP, 1 + RANDOM.nextInt(3)));
      loot.add(new ItemStack(Items.ANCIENT_DEBRIS, 1 + RANDOM.nextInt(2)));
      loot.add(new ItemStack(Items.DIAMOND_BLOCK, 1 + RANDOM.nextInt(3)));
      loot.add(new ItemStack(Items.ENCHANTED_GOLDEN_APPLE));
      loot.add(new ItemStack(Items.TOTEM_OF_UNDYING));
      loot.add(new ItemStack(Items.EXPERIENCE_BOTTLE, 6 + RANDOM.nextInt(8)));
      // Not an elytra, deliberately. The castle is the one structure in the mod that cannot be
      // broken into and cannot be escaped by dying - the loot is meant to be worth the walk in,
      // not to hand out the mod's best movement item for free. Netherite, a totem and an
      // enchantment worth the trip are all still here.
      loot.add(new ItemStack(pick(Items.ENCHANTED_GOLDEN_APPLE, Items.ANCIENT_DEBRIS, Items.NETHERITE_SCRAP)));
      loot.add(new ItemStack(pick(Items.SHULKER_BOX, Items.CONDUIT, Items.TOTEM_OF_UNDYING)));
      return loot;
   }

   /** One of a set, at random: the difference between a pool and a list. */
   private static Item pick(Item... options) {
      return options[RANDOM.nextInt(options.length)];
   }

   /**
    * The Royal Guard: castle furniture with an axe.
    *
    * <p>They are mirage like everything else, so they neither drop nor award anything, and
    * {@link #dust(LivingEntity)} turns each one into a puff when it dies. Tagged so both of
    * those are one lookup rather than a list of entity types to keep in step.
    */
   private static void spawnGuards(MinecraftServer server) {
      if (standing == null) {
         return;
      }

      GUARDS.clear();
      captain = null;
      captainSpent = false;
      List<BlockPos> spots = new ArrayList<>(GUARD_SPOTS);
      if (spots.isEmpty()) {
         return;
      }

      // The guard scales with the crowd.
      //
      // A castle is worth raiding with friends, and a fixed roster meant a raid party of six
      // walked through the same twelve guards a lone visitor did: the place got easier the
      // more people arrived, which is the one direction a fight must never scale. Each player
      // past the first brings {@link #GUARDS_PER_EXTRA_PLAYER} more, and the spots are reused
      // in order with a small offset so a second guard on the same tower is not inside the
      // first.
      int visitors = 0;
      for (ServerPlayer p : standing.getPlayers(pl -> pl.isAlive() && !pl.isSpectator() && inside(pl))) {
         visitors++;
      }
      int roster = guardBudgetFor(visitors);

      for (int i = 0; i < roster; i++) {
         BlockPos spot = spots.get(i % spots.size()).offset((i / spots.size()) % 2 == 0 ? 0 : 1, 0, (i / spots.size()) % 2 == 0 ? 0 : 1);
         int roll = i % 6;

         try {
            if (roll == 5 && i % 18 == 5) {
               Ravager beast = EntityTypes.RAVAGER.create(standing, EntitySpawnReason.COMMAND);
               if (beast == null) {
                  continue;
               }
               beast.setPos(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5);
               dress(beast, "§5§lRoyal Beast", visitors);
               continue;
            }

            if (roll == 4) {
               Pillager archer = EntityTypes.PILLAGER.create(standing, EntitySpawnReason.COMMAND);
               if (archer == null) {
                  continue;
               }
               archer.setPos(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5);
               dress(archer, "§5Royal Guard", visitors);
               continue;
            }

            Vindicator guard = EntityTypes.VINDICATOR.create(standing, EntitySpawnReason.COMMAND);
            if (guard == null) {
               continue;
            }
            guard.setPos(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5);
            dress(guard, "§5Royal Guard", visitors);
         } catch (Throwable ignored) {
            // A guard that will not stand there is not worth failing the castle over.
         }
      }
   }

   /** The roster a castle of this crowd raises: a base of spots, plus three a head. */
   public static int guardBudgetFor(int visitors) {
      int base = Math.max(1, GUARD_SPOTS.size());
      return base + Math.max(0, visitors - 1) * GUARDS_PER_EXTRA_PLAYER;
   }

   /**
    * Whether the elite walk yet: the whole guard down, somebody left to fight it, and once.
    *
    * <p>Pure on purpose - the three conditions are the whole rule, so it can be asserted
    * without a castle, a server or a fight.
    */
   public static boolean captainReady(boolean guardsAlive, boolean visitorsInside, boolean alreadySpent) {
      return !guardsAlive && visitorsInside && !alreadySpent;
   }

   private static void dress(Mob guard, String name, int visitors) {
      guard.setPersistenceRequired();
      guard.setCustomName(net.minecraft.network.chat.Component.literal(name));
      guard.setCustomNameVisible(false);
      guard.addTag(GUARD_TAG);
      // Mirage: nothing is left behind to look at.
      guard.skipDropExperience();
      guard.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.CHAINMAIL_HELMET));
      guard.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.CHAINMAIL_CHESTPLATE));
      scaleGuard(guard, visitors);
      guard.setHealth(guard.getMaxHealth());
      standing.addFreshEntity(guard);
      GUARDS.add(guard.getUUID());
      mirageParticles(ParticleTypes.PORTAL, guard.getX(), guard.getY() + 1.0, guard.getZ(), 12, 0.4, 0.6, 0.4, 0.05);
   }

   /** Tougher and angrier the more of you there are, so the roster is not the only scaling. */
   private static void scaleGuard(Mob guard, int visitors) {
      try {
         double heads = Math.max(1.0, visitors);
         AttributeInstance hp = guard.getAttribute(Attributes.MAX_HEALTH);
         if (hp != null) {
            hp.setBaseValue(hp.getBaseValue() * (1.0 + 0.35 * (heads - 1.0)));
         }
         AttributeInstance dmg = guard.getAttribute(Attributes.ATTACK_DAMAGE);
         if (dmg != null) {
            dmg.setBaseValue(dmg.getBaseValue() * (1.0 + 0.2 * (heads - 1.0)));
         }
         AttributeInstance speed = guard.getAttribute(Attributes.MOVEMENT_SPEED);
         if (speed != null) {
            speed.setBaseValue(speed.getBaseValue() * (1.0 + 0.05 * (heads - 1.0)));
         }
      } catch (Throwable ignored) {
      }
   }

   /** True while at least one guard this castle raised is still standing. */
   private static boolean guardsAlive(MinecraftServer server) {
      for (UUID id : GUARDS) {
         for (ServerLevel level : server.getAllLevels()) {
            Entity e = level.getEntity(id);
            if (e instanceof LivingEntity le && le.isAlive()) {
               return true;
            }
         }
      }
      return false;
   }

   /**
    * The Royal Captain: what is waiting behind the guard.
    *
    * <p>He walks only once every Royal Guard this castle raised is on the floor, and only while
    * somebody is still inside to fight him - an elite that appears to an empty courtyard is a
    * body standing in a ruin. Unlike his guard he is <b>not</b> nothing: he drops real loot,
    * because clearing the whole guard of an unbreakable castle is the one thing here that
    * cannot be bought, and the reward for it should not also be a puff of ash.
    */
   private static void spawnCaptain(MinecraftServer server) {
      if (standing == null || captainSpent) {
         return;
      }

      captainSpent = true;
      try {
         Vindicator elite = EntityTypes.VINDICATOR.create(standing, EntitySpawnReason.COMMAND);
         if (elite == null) {
            return;
         }

         BlockPos at = origin.offset(SIZE / 2, KEEP_HEIGHT - 5, SIZE / 2);
         elite.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
         elite.setPersistenceRequired();
         elite.setCustomName(net.minecraft.network.chat.Component.literal("§6§lThe Royal Captain §7of the Mirage"));
         elite.setCustomNameVisible(true);
         elite.setGlowingTag(true);
         elite.addTag(GUARD_TAG);
         elite.addTag(CAPTAIN_TAG);
         elite.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.NETHERITE_AXE));
         elite.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.NETHERITE_HELMET));
         elite.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.NETHERITE_CHESTPLATE));
         elite.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.NETHERITE_LEGGINGS));
         elite.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.NETHERITE_BOOTS));
         AttributeInstance hp = elite.getAttribute(Attributes.MAX_HEALTH);
         if (hp != null) {
            hp.setBaseValue(120.0);
         }
         AttributeInstance dmg = elite.getAttribute(Attributes.ATTACK_DAMAGE);
         if (dmg != null) {
            dmg.setBaseValue(14.0);
         }
         elite.setHealth(120.0F);
         standing.addFreshEntity(elite);
         captain = elite.getUUID();
         GUARDS.add(captain);

         mirageParticles(ParticleTypes.SOUL_FIRE_FLAME, at.getX() + 0.5, at.getY() + 1.0, at.getZ() + 0.5, 80, 1.0, 1.2, 1.0, 0.08);
         mirageParticles(ParticleTypes.END_ROD, at.getX() + 0.5, at.getY() + 1.0, at.getZ() + 0.5, 40, 0.8, 1.0, 0.8, 0.06);
         standing.playSound(null, at, SoundEvents.RAVAGER_ROAR, SoundSource.HOSTILE, 1.6F, 0.7F);

         for (ServerPlayer p : standing.getPlayers(pl -> pl.isAlive())) {
            Chat.raw(p, "§6§lThe Royal Guard is down.§r §7Something heavier is walking down the stairs.");
            Chat.raw(p, "§7A guard is mirage and leaves nothing. §6The Captain is not.");
         }

         if (server != null) {
            ServerNewspaperManager.logEvent(server, "The Royal Captain of the Mirage Castle has risen to defend an empty guard.");
         }
      } catch (Throwable ignored) {
      }
   }

   /** What the elite leaves behind: the one thing in the castle that is really there. */
   private static void captainReward(ServerLevel level, double x, double y, double z) {
      List<ItemStack> reward = new ArrayList<>();
      reward.add(new ItemStack(Items.NETHERITE_SCRAP, 3 + RANDOM.nextInt(3)));
      reward.add(new ItemStack(Items.DIAMOND_BLOCK, 2));
      reward.add(new ItemStack(Items.ENCHANTED_GOLDEN_APPLE, 2));
      reward.add(new ItemStack(Items.TOTEM_OF_UNDYING));
      reward.add(new ItemStack(Items.EXPERIENCE_BOTTLE, 12));

      for (ItemStack stack : reward) {
         ItemEntity drop = new ItemEntity(level, x, y + 0.4, z, stack);
         drop.setDefaultPickUpDelay();
         level.addFreshEntity(drop);
      }

      level.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, x, y + 1.0, z, 80, 0.8, 1.0, 0.8, 0.4);
      level.sendParticles(ParticleTypes.FIREWORK, x, y + 1.2, z, 40, 0.6, 0.8, 0.6, 0.2);
      level.playSound(null, x, y, z, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.PLAYERS, 1.4F, 1.0F);
   }

   /** Where the elite stands, for the readout and the self-test. */
   public static UUID captainId() {
      return captain;
   }

   /** True once this castle has raised its elite - spent is spent, even if he dies. */
   public static boolean captainSpent() {
      return captainSpent;
   }

   /** True for anything this castle raised. */
   public static boolean isGuard(Entity entity) {
      return entity != null && entity.entityTags().contains(GUARD_TAG);
   }

   /**
    * Turns a dead guard into dust where it stood.
    *
    * <p>The whole visual of "it was never really here": no corpse, no drops, no experience -
    * a puff of ash on the flagstones and the fight carries on. Called from the drop mixin,
    * which is the call vanilla makes for exactly this moment.
    */
   public static void dust(LivingEntity victim) {
      if (!(victim.level() instanceof ServerLevel level)) {
         return;
      }
      double x = victim.getX();
      double y = victim.getY() + victim.getBbHeight() * 0.5;
      double z = victim.getZ();

      level.sendParticles(ParticleTypes.POOF, x, y, z, 30, 0.5, 0.6, 0.5, 0.06);
      level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, x, y, z, 18, 0.4, 0.5, 0.4, 0.02);
      level.sendParticles(ParticleTypes.WHITE_ASH, x, y, z, 40, 0.6, 0.7, 0.6, 0.04);
      level.sendParticles(ParticleTypes.SCULK_SOUL, x, y, z, 8, 0.3, 0.4, 0.3, 0.02);
      level.playSound(null, x, y, z, SoundEvents.SAND_BREAK, SoundSource.HOSTILE, 1.2F, 0.7F);

      // The Captain is the one body in here that was never a mirage.
      if (isCaptain(victim)) {
         captainReward(level, x, y, z);
         for (ServerPlayer p : level.getPlayers(pl -> pl.isAlive())) {
            Chat.raw(p, "§6§lThe Royal Captain falls.§r §7The guard is broken and the castle is yours to loot - §ffor as long as it lasts.");
         }
      }
      level.playSound(null, x, y, z, SoundEvents.FIRE_EXTINGUISH, SoundSource.HOSTILE, 0.9F, 1.3F);
   }

   // ------------------------------------------------------- the fatal blow rule

   /**
    * The mirage's rule in the lethal-blow walk: {@link LethalBlows#MIRAGE_CASTLE}.
    *
    * <p>The castle cannot be dug out of, it is full of armed guards, and it is standing in the
    * middle of wherever it landed - so "you die in the box" would make it a trap rather than an
    * event. A <b>fatal</b> blow is refused: the player is sent to their own respawn point (their
    * bed, or the world spawn when they have never slept) and the mirage drops them off with a
    * line saying what happened.
    *
    * <p>Whether the blow was fatal at all, and whether a totem was going to save them, is decided
    * once, in {@link LethalBlows} - a rule here only says what happens when the answer is "this
    * player would have died inside my walls".
    */
   public static LethalBlows.Answer answerFatalBlow(LethalBlows.Blow blow) {
      ServerPlayer player = blow.player();
      if (player == null || !isStanding() || standing == null || player.level() != standing) {
         return LethalBlows.Answer.INNOCENT;
      }
      if (FATED.contains(player.getUUID())) {
         // The one blow this castle does not soften. See FATED: the mirage letting go of a
         // player who fell in the fight is its promise, and this is the deadline - a hall that is
         // no longer there has nothing to hand anybody back to.
         return LethalBlows.Answer.INNOCENT;
      }
      if (!inside(player)) {
         return LethalBlows.Answer.INNOCENT;
      }

      try {
         sendToSpawn(player);
         Chat.raw(player, "\u00a7d\u00a7lTHE MIRAGE LETS GO\u00a7r \u00a77- that blow would have killed you, and it was never real.");
         Chat.raw(player, "\u00a77You are back at your spawn point. \u00a7dThe castle is still standing.");
      } catch (Throwable t) {
         // If the ride home cannot be arranged, the death stands: a refused blow that does
         // nothing at all would leave the player alive and stuck inside the castle.
         return LethalBlows.Answer.INNOCENT;
      }

      return LethalBlows.Answer.CLAIM;
   }

   /** This rule, asked on its own - the shape the harness and older callers use. */
   public static boolean onLethalDamage(ServerPlayer player, DamageSource source, float amount) {
      return LethalBlows.verdict(LethalBlows.MIRAGE_CASTLE, player, source, amount);
   }

   /**
    * Puts a player back at their own respawn point, on solid ground.
    *
    * <p>The mirage's one promise, and it is public because it is not only the castle's: the Last
    * Remembrance is a piece of this place, and the only thing it remembers how to do is the
    * thing the place did (see {@link LastRemembrance}). One implementation, so the relic and the
    * rescue cannot drift - including the anticheat being told about the jump, which is what stops
    * the teleport reading as a movement hack.
    */
   public static void sendToSpawn(ServerPlayer player) {
      MinecraftServer server = player.level().getServer();
      ServerLevel target = null;
      BlockPos spawn = null;

      try {
         ServerPlayer.RespawnConfig config = player.getRespawnConfig();
         if (config != null && config.respawnData() != null) {
            ServerLevel dimension = server.getLevel(config.respawnData().dimension());
            if (dimension != null) {
               target = dimension;
               spawn = config.respawnData().pos();
            }
         }
      } catch (Throwable ignored) {
      }

      if (target == null || spawn == null) {
         ServerLevel overworld = target == null ? server.overworld() : target;
         target = overworld;
         try {
            spawn = overworld.getRespawnData().pos();
         } catch (Throwable ignored) {
            spawn = BlockPos.ZERO;
         }
      }

      double x = spawn == null ? 0.5 : spawn.getX() + 0.5;
      double z = spawn == null ? 0.5 : spawn.getZ() + 0.5;
      double y = BossGrounding.surfaceY(target, x, z, spawn == null ? 80.0 : spawn.getY());

      player.stopRiding();
      player.setDeltaMovement(Vec3.ZERO);
      player.fallDistance = 0.0F;
      player.teleport(
         new TeleportTransition(target, new Vec3(x, y, z), Vec3.ZERO, player.getYRot(), player.getXRot(), TeleportTransition.PLACE_PORTAL_TICKET)
      );
      player.setHealth(Math.max(1.0F, Math.min(player.getMaxHealth(), 6.0F)));
      player.hurtMarked = true;
      com.fortuneandfavors.anticheat.AntiCheat.onServerTeleport(player, "the mirage letting go");
      target.sendParticles(ParticleTypes.REVERSE_PORTAL, x, y + 1.0, z, 60, 0.6, 1.0, 0.6, 0.2);
      target.playSound(null, x, y, z, SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 1.0F, 1.4F);
   }

   // ------------------------------------------------------------------- teardown

   /**
    * Takes the castle away, putting back everything it found.
    *
    * <p>Idempotent and safe to call when nothing is standing, because every path that ends
    * an event calls it: the timer, an operator, starting a different event, and the sweep at
    * boot. "The castle is already gone" is not an error.
    *
    * <p>Only the blocks that actually rose are put back - the plan is in rising order, so the
    * first {@link #risen} entries are exactly the ones that were placed. Anything still in
    * the queue was never touched and must be left alone.
    */
   /** How far past the walls a dropped item still counts as the castle's. */
   private static final int LOOT_SWEEP_MARGIN = 8;

   /**
    * Takes every dropped item standing where the castle is with the castle.
    *
    * <p>Restoring the ground puts every chest back exactly as it was found, and vanilla answers
    * that by dropping the contents on the floor - so the castle's own loot would land on the grass
    * at the moment the mirage ends, through a wall that is no longer there. The King's payout lands
    * as item entities beside the throne for the same reason, and so does anything a player dropped
    * mid-fight. A mirage that leaves diamonds behind where it stood is not a mirage; it is a chest
    * that refills, and it is why "the event ended and the loot was still there" reads as a dupe.
    *
    * @return how many item stacks were taken, for the self-test
    */
   public static int discardDroppedLoot(ServerLevel level) {
      if (level == null || origin == null) {
         return 0;
      }

      int removed = 0;
      try {
         AABB box = new AABB(
            origin.getX() - LOOT_SWEEP_MARGIN, origin.getY() - 12.0, origin.getZ() - LOOT_SWEEP_MARGIN,
            origin.getX() + SIZE + LOOT_SWEEP_MARGIN, origin.getY() + TOWER_HEIGHT + 24.0, origin.getZ() + SIZE + LOOT_SWEEP_MARGIN
         );

         for (Entity e : level.getEntities((Entity)null, box, en -> en instanceof ItemEntity)) {
            // What the monarch carried is not the castle's loot and does not go back with the
            // walls - his blade and his two keepings came off the body that was really there, so
            // sweeping them would mean a player who killed the boss and then let the event end
            // finds their drop deleted off the floor it fell on. Everything else standing in the
            // footprint, including the King's own payout and whatever a chest spilled when the
            // ground came back, goes.
            if (e instanceof ItemEntity item
               && (ModItems.isLastRemembrance(item.getItem()) || ModItems.isMonarchKeeping(item.getItem()))) {
               continue;
            }

            level.sendParticles(ParticleTypes.POOF, e.getX(), e.getY() + 0.2, e.getZ(), 6, 0.2, 0.2, 0.2, 0.02);
            e.discard();
            removed++;
         }
      } catch (Throwable ignored) {
         // A chunk that went away with the castle is not a reason to leave a diamond on the grass.
      }

      return removed;
   }

   // ------------------------------------------------------------------ the fall

   /** Starts the escape window. Idempotent, because two death paths arrive here. */
   public static void beginCollapse(MinecraftServer server) {
      if (collapsing || standing == null) {
         return;
      }
      collapsing = true;
      collapseTicks = COLLAPSE_ESCAPE_TICKS;
      collapseBarClock = 0;
      collapseMarked = 0;
      FATED.clear();
      buildWave();

      double cx = origin.getX() + SIZE / 2.0;
      double cy = origin.getY() + 8.0;
      double cz = origin.getZ() + SIZE / 2.0;
      standing.playSound(null, cx, cy, cz, SoundEvents.WITHER_SPAWN, SoundSource.AMBIENT, 2.5F, 0.6F);
      standing.playSound(null, cx, cy, cz, SoundEvents.BEACON_DEACTIVATE, SoundSource.AMBIENT, 2.0F, 0.5F);

      for (ServerPlayer p : standing.getPlayers(pl -> pl.isAlive() && !pl.isSpectator())) {
         Chat.raw(p, "\u00a7d\u00a7lTHE MIRAGE IS FADING\u00a7r \u00a77- the hall is coming down.");
         Chat.raw(p, "\u00a77You have \u00a7f60 seconds\u00a77 to be outside the walls. \u00a7cStaying inside is not survivable.\u00a77");
         p.sendOverlayMessage(net.minecraft.network.chat.Component.literal("\u00a7d\u00a7lGET OUT \u00a78| \u00a7f60s"));
      }
   }

   /**
    * One tick of the fall: the walls come apart, the halls shake, and the clock is on the bar.
    *
    * <p>The blocks are taken with {@code setBlock(..., 3)} - no drops, no neighbour cascade, no
    * explosion - because this is a mirage being withdrawn rather than a building being destroyed:
    * the castle's teardown puts the original terrain back block for block, and a falling castle
    * that dropped its own stone into the field would leave the map different from the one it was
    * built on, which is the one thing this structure promises not to do.
    */
   private static void tickCollapse(MinecraftServer server) {
      if (!collapsing || standing == null) {
         return;
      }
      long now = standing.getGameTime();
      // Read before the decrement: the phase is a statement about the time that is left, and a
      // deadline and a phase that disagree by a tick is how a countdown ends up describing
      // something other than what is happening.
      int left = collapseTicks;
      collapseTicks--;

      boolean heavy = left <= COLLAPSE_HEAVY_AT;
      boolean lastLight = left <= COLLAPSE_FINAL_AT;
      int budget = collapseBudget(left, heavy, lastLight);

      for (int i = 0; i < budget; i++) {
         Place place = nextToFall();
         if (place == null) {
            break;
         }
         try {
            if (!standing.isLoaded(place.pos)) {
               continue;
            }
            BlockState was = standing.getBlockState(place.pos);
            if (was.isAir()) {
               continue;
            }
            // The chest goes, and what was in the chest goes with it. Emptying it first is the
            // difference between the mirage withdrawing and the mirage spilling: a container's
            // contents are not the block, so they would otherwise land on the ground the fall is
            // about to give back untouched.
            takeWhatIsInside(standing, place.pos);
            standing.setBlock(place.pos, Blocks.AIR.defaultBlockState(), 3);
            crumble(place.pos, was, lastLight);
         } catch (Throwable ignored) {
         }
      }

      // And the backstop, on a clock, for anything a container handed back by a route the line
      // above cannot see (a hopper, another mod, a block entity that outlives its block). The
      // same sweep the teardown uses, run while the castle is still coming down, so a chest that
      // spilled is emptied again a half-second later rather than after the castle is gone.
      if (left % COLLAPSE_SWEEP_TICKS == 0) {
         discardDroppedLoot(standing);
      }

      // Everybody in the hall wears the fall: the ground drags at your feet, the air is thin
      // enough to be hungry in, and the stone has stopped answering your hands. Heavier as the
      // walls thin, and re-applied on a clock so it cannot be waited out.
      if (now % COLLAPSE_MOOD_TICKS == 0L) {
         collapseMood(heavy, lastLight);
      }

      // The sky answers the walls: a flash and a crack from above the footprint every few seconds,
      // which is what makes the collapse readable from outside the castle rather than only from
      // inside it. Visual only - lightning that struck would be setting fires in a structure we
      // are contractually putting back exactly as we found it.
      if (now % COLLAPSE_FLASH_TICKS == 0L) {
         double flashX = origin.getX() + RANDOM.nextDouble() * SIZE;
         double flashZ = origin.getZ() + RANDOM.nextDouble() * SIZE;
         // ELECTRIC_SPARK rather than FLASH: the flash particle carries a colour option and is a
         // client-side effect for real lightning, so a spark shower up in the sky is the version
         // that actually reaches every client.
         mirageParticles(ParticleTypes.ELECTRIC_SPARK, flashX, origin.getY() + 62.0, flashZ, 24, 6.0, 2.0, 6.0, 0.6);
         standing.playSound(
            null, flashX, origin.getY() + 40.0, flashZ, SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.WEATHER,
            lastLight ? 3.0F : 1.6F, 0.55F + RANDOM.nextFloat() * 0.3F
         );
      }

      // And something under the floor: not an explosion, a heartbeat. It is the one sound in the
      // game that reads as "the ground is about to stop being the ground".
      if (now % COLLAPSE_RUMBLE_TICKS == 0L) {
         standing.playSound(
            null, footX(), origin.getY() + 2.0, footZ(),
            heavy ? SoundEvents.WARDEN_HEARTBEAT : SoundEvents.END_PORTAL_SPAWN,
            SoundSource.AMBIENT, heavy ? 2.2F : 1.2F, 0.4F
         );
      }

      // Ground haze: ash and dust lying over the whole footprint, so the castle is a place that is
      // being erased rather than a hole with particles in it.
      if (now % 6L == 0L) {
         mirageParticles(
            ParticleTypes.WHITE_ASH, footX(), origin.getY() + 2.0, footZ(),
            heavy ? 40 : 16, SIZE / 2.6, 2.0, SIZE / 2.6, 0.01
         );
      }

      // Light coming out of the seams, once the walls are really going: beams of the mirage's own
      // light standing in the gaps where blocks were, which reads as the thing fading <i>through</i>
      // the stone rather than as the stone being removed.
      if (lastLight && now % 8L == 0L && !PLAN.isEmpty()) {
         for (int i = 0; i < 6; i++) {
            BlockPos seam = PLAN.get(RANDOM.nextInt(PLAN.size())).pos;
            double sx = seam.getX() + 0.5;
            double sy = seam.getY() + 0.5;
            double sz = seam.getZ() + 0.5;
            mirageParticles(ParticleTypes.END_ROD, sx, sy, sz, 5, 0.1, 1.4, 0.1, 0.14);
            mirageParticles(ParticleTypes.SCULK_SOUL, sx, sy, sz, 3, 0.15, 1.0, 0.15, 0.05);
         }
      }

      // A blast walks the castle every second and a half: the explosion itself is visual only -
      // no block damage and no entity damage - because the timer is the threat, not the fireball.
      // Once the walls are really coming down it walks faster and hits harder, so the last forty
      // seconds are a different experience from the first twenty rather than the same one longer.
      int blastEvery = lastLight ? COLLAPSE_BLAST_TICKS / 3 : heavy ? COLLAPSE_BLAST_TICKS / 2 : COLLAPSE_BLAST_TICKS;
      if (now % Math.max(1, blastEvery) == 0L && !PLAN.isEmpty()) {
         for (int blasts = 0; blasts < (lastLight ? 3 : heavy ? 2 : 1); blasts++) {
            Place place = PLAN.get(RANDOM.nextInt(PLAN.size()));
            if (!standing.isLoaded(place.pos)) {
               continue;
            }
            fallBlast(place.pos, lastLight);
         }
      }

      // A shockwave walks out of the walls every second: the castle is coming apart from the
      // inside, and the ground it stands on feels it. This is the piece that reads from a
      // distance - a ring of dust leaving the footprint is visible from the next biome.
      if (now % COLLAPSE_RING_TICKS == 0L) {
         shockwave(heavy, lastLight, left);
      }

      // The whole footprint breathes light out, harder as the walls thin. PORTAL is the mirage
      // itself leaving; REVERSE_PORTAL is the same light being drawn back in, which is what
      // "fading away" looks like from the ground.
      if (now % 4L == 0L) {
         double cx = origin.getX() + SIZE / 2.0;
         double cy = origin.getY() + 7.0;
         double cz = origin.getZ() + SIZE / 2.0;
         mirageParticles(ParticleTypes.PORTAL, cx, cy, cz, lastLight ? 220 : heavy ? 120 : 50, SIZE / 2.5, 9.0, SIZE / 2.5, 0.45);
         if (heavy) {
            mirageParticles(ParticleTypes.REVERSE_PORTAL, cx, cy, cz, 60, SIZE / 2.5, 8.0, SIZE / 2.5, -0.25);
         }
         if (lastLight) {
            mirageParticles(ParticleTypes.SCULK_SOUL, cx, cy, cz, 60, SIZE / 2.5, 10.0, SIZE / 2.5, 0.08);
            mirageParticles(ParticleTypes.FIREFLY, cx, cy, cz, 40, SIZE / 2.2, 12.0, SIZE / 2.2, 0.0);
            mirageParticles(ParticleTypes.END_ROD, cx, cy, cz, 30, SIZE / 3.0, 14.0, SIZE / 3.0, 0.12);
         }
      }

      // A pillar of light standing over the whole castle, so "something is happening over there"
      // is readable from anywhere in the render distance rather than only from inside it.
      if (now % 10L == 0L) {
         double cx = origin.getX() + SIZE / 2.0;
         double cz = origin.getZ() + SIZE / 2.0;
         double base = origin.getY() + (heavy ? 4.0 : 1.0);
         mirageParticles(
            ParticleTypes.END_ROD, cx, base + 10.0, cz, heavy ? 24 : 12, 0.6, 12.0, 0.6, 0.02
         );
      }

      if (--collapseBarClock <= 0) {
         collapseBarClock = COLLAPSE_BAR_TICKS;
         int seconds = collapseSecondsLeft();
         for (ServerPlayer p : standing.getPlayers(pl -> pl.isAlive() && !pl.isSpectator())) {
            p.sendOverlayMessage(net.minecraft.network.chat.Component.literal(
               inside(p)
                  ? "\u00a7c\u00a7lTHE MIRAGE IS FALLING \u00a78| \u00a7f" + seconds + "s \u00a77- get outside the walls"
                  : "\u00a7d\u00a7lTHE MIRAGE IS FADING \u00a78| \u00a7f" + seconds + "s"
            ));
         }
         if (collapseMarked < COLLAPSE_MARKS.length && seconds <= COLLAPSE_MARKS[collapseMarked]) {
            collapseMarked++;
            for (ServerPlayer p : standing.getPlayers(pl -> pl.isAlive() && !pl.isSpectator())) {
               Chat.raw(p, inside(p)
                  ? "\u00a7c\u00a7l" + seconds + " seconds \u00a77- you are still inside. \u00a7cRun."
                  : "\u00a7d" + seconds + " seconds \u00a77until the mirage goes.");
            }
         }
      }

      if (collapseTicks <= 0) {
         finishCollapse(server);
      }
   }

   /** The footprint's middle on X and Z - the point every layer of the fall is built around. */
   private static double footX() {
      return origin.getX() + SIZE / 2.0;
   }

   private static double footZ() {
      return origin.getZ() + SIZE / 2.0;
   }

   /**
    * The fall, worn by everybody in the hall.
    *
    * <p>Three effects, each with a job: slowness is the running, hunger is the regeneration and
    * the eating, and mining fatigue is the one that closes the loophole - a player who cannot walk
    * out fast enough could dig out, and the hall is not offering that. All three are invisible
    * (the icon is enough) so nobody is walking around in a permanent potion cloud, and all three
    * are replaced rather than added to, so a strength potion of the player's own still works.
    */
   private static void collapseMood(boolean heavy, boolean lastLight) {
      int slow = lastLight ? 2 : heavy ? 1 : 0;
      int thin = heavy ? 1 : 0;
      for (ServerPlayer p : standing.getPlayers(pl -> pl.isAlive() && !pl.isSpectator())) {
         p.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, COLLAPSE_MOOD_DURATION, slow, false, false, true));
         p.addEffect(new MobEffectInstance(MobEffects.HUNGER, COLLAPSE_MOOD_DURATION, thin, false, false, true));
         p.addEffect(new MobEffectInstance(MobEffects.MINING_FATIGUE, COLLAPSE_MOOD_DURATION, thin, false, false, true));
      }
   }

   /**
    * One block letting go.
    *
    * <p>The dust is the block's <b>own</b> dust - the particle carries the state that was there
    * a line earlier - because a castle that crumbles as generic grey smoke does not read as
    * this castle. What it never leaves is an item: the mirage is being withdrawn rather than
    * demolished, and the teardown underneath still has to be able to put the real terrain back
    * block for block, which a drop would make a lie.
    *
    * <p>Cheap on purpose. Every break runs this, and ten to thirty blocks break a tick, so it is
    * two small particle sends and - one time in three at the end, one time in eight before that -
    * a break sound. Nothing here walks the world, allocates a list, or touches an entity.
    */
   private static void crumble(BlockPos pos, BlockState was, boolean violent) {
      double x = pos.getX() + 0.5;
      double y = pos.getY() + 0.5;
      double z = pos.getZ() + 0.5;

      try {
         mirageParticles(
            new BlockParticleOption(ParticleTypes.BLOCK_CRUMBLE, was), x, y, z, violent ? 12 : 5, 0.35, 0.35, 0.35, 0.02
         );
         mirageParticles(
            new BlockParticleOption(ParticleTypes.DUST_PILLAR, was), x, y, z, violent ? 3 : 1, 0.5, 1.4, 0.5, 0.0
         );
      } catch (Throwable ignored) {
      }

      if (RANDOM.nextInt(violent ? 3 : 8) == 0) {
         try {
            standing.playSound(
               null, x, y, z, breakSound(), SoundSource.BLOCKS,
               violent ? 1.5F : 1.0F, 0.55F + RANDOM.nextFloat() * 0.5F
            );
         } catch (Throwable ignored) {
         }
      }
   }

   /** The stonework a falling castle sounds like: dressed stone, the deep stone under it, glass. */
   private static SoundEvent breakSound() {
      return switch (RANDOM.nextInt(5)) {
         case 0 -> SoundEvents.STONE_BREAK;
         case 1 -> SoundEvents.DEEPSLATE_BREAK;
         case 2 -> SoundEvents.GLASS_BREAK;
         case 3 -> SoundEvents.CALCITE_BREAK;
         default -> SoundEvents.GRAVEL_BREAK;
      };
   }

   /**
    * One of the blasts that walk the halls: the explosion is a <b>look and a sound</b>, never a
    * real explosion. The clock is the threat. A fireball that broke blocks would fight the undo
    * list and a fireball that hurt people would be a second, invisible timer.
    */
   /**
    * Every particle this castle sends, through the shared budget.
    *
    * <p>The collapse is the heaviest effect in the mod: a whole castle being erased, several sends a
    * tick, for a minute, at counts chosen so the shape reads from the far side of the biome. What
    * those counts were not is <em>bounded</em>. Each one fans out to every player in the level, and a
    * Bedrock client arriving through Geyser turns every single particle into its own packet on a
    * protocol with a fraction of Java's headroom - so the client is handed more than it can drain
    * and stops answering, which is the report: "the fade spams the particles and it lags and crashes
    * the other person".
    *
    * <p>So the castle draws through {@link BossVfx} like every boss effect does. Bedrock gets the same
    * shape at a fifth of the count, nobody is sent more than one tick's budget, and a Java client sees
    * what it always saw.
    */
   private static void mirageParticles(
      ParticleOptions type, double x, double y, double z, int count, double dx, double dy, double dz, double speed
   ) {
      BossVfx.at(standing, x, y, z, 0.0, type, count, dx, dy, dz, speed);
   }

   private static void fallBlast(BlockPos pos, boolean violent) {
      double x = pos.getX() + 0.5;
      double y = pos.getY() + 0.5;
      double z = pos.getZ() + 0.5;
      mirageParticles(ParticleTypes.EXPLOSION_EMITTER, x, y, z, 1, 0.0, 0.0, 0.0, 0.0);
      mirageParticles(ParticleTypes.LARGE_SMOKE, x, y, z, violent ? 45 : 24, 1.2, 1.2, 1.2, 0.05);
      mirageParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, x, y, z, violent ? 20 : 8, 0.8, 1.6, 0.8, 0.01);
      if (violent) {
         mirageParticles(ParticleTypes.SMALL_GUST, x, y, z, 6, 0.6, 0.6, 0.6, 0.0);
      }
      standing.playSound(
         null, pos, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.BLOCKS, violent ? 3.0F : 2.2F, 0.7F + RANDOM.nextFloat() * 0.4F
      );
   }

   /**
    * A shockwave walking out of the footprint.
    *
    * <p>The radius grows with the clock rather than with the wall, so the last ring is already
    * past the courtyard by the time the castle goes: the light beating the dust out of the ground
    * arrives <i>before</i> the collapse does, which is the tell that the whole thing is on a
    * deadline rather than coming apart slowly.
    */
   private static void shockwave(boolean heavy, boolean lastLight, int ticksLeft) {
      double cx = origin.getX() + SIZE / 2.0;
      double cz = origin.getZ() + SIZE / 2.0;
      double y = origin.getY() + 1.2;
      double radius = SIZE * 0.5 + 4.0 + (COLLAPSE_ESCAPE_TICKS - ticksLeft) * 0.09;
      int points = lastLight ? 56 : heavy ? 36 : 22;

      for (int i = 0; i < points; i++) {
         double angle = (Math.PI * 2.0) * i / points;
         double px = cx + Math.cos(angle) * radius;
         double pz = cz + Math.sin(angle) * radius;
         mirageParticles(
            heavy ? ParticleTypes.LARGE_SMOKE : ParticleTypes.CLOUD, px, y, pz, 1, 0.25, 0.1, 0.25, 0.02
         );
         if (lastLight) {
            mirageParticles(ParticleTypes.WHITE_ASH, px, y + 1.0, pz, 2, 0.2, 0.6, 0.2, 0.01);
         }
      }

      if (heavy) {
         standing.playSound(null, cx, y, cz, SoundEvents.WITHER_AMBIENT, SoundSource.AMBIENT, 2.4F, 0.4F);
      }
      if (lastLight) {
         standing.playSound(null, cx, y, cz, SoundEvents.RESPAWN_ANCHOR_AMBIENT, SoundSource.AMBIENT, 2.0F, 0.5F);
      }
   }

   /**
    * The end of the fall: a blast that can be seen from the next biome, and then the field again.
    *
    * <p>Everybody still inside goes with it. The death is an ordinary one - a real damage source
    * and a real health pool - so a Keep-Inventory rule, a grave, a totem and every other death
    * rule in the mod treat it exactly as they treat any other death. What is stood aside is the
    * castle's own rescue (see FATED), because "the mirage lets you go" is the promise for a fight
    * you could not win, and this is the price of staying in a hall that has stopped existing.
    */
   private static void finishCollapse(MinecraftServer server) {
      if (standing == null) {
         collapsing = false;
         return;
      }
      double cx = origin.getX() + SIZE / 2.0;
      double cy = origin.getY() + 8.0;
      double cz = origin.getZ() + SIZE / 2.0;

      // The last light, in six parts, because a single blast at 1/20th of a second is a thing you
      // can be looking away from. Everything here is inert: particles and sounds, no blocks, no
      // entities, so the field underneath is still the field the teardown is about to restore.
      for (int i = 0; i < 32; i++) {
         mirageParticles(
            ParticleTypes.EXPLOSION_EMITTER,
            cx + (RANDOM.nextDouble() - 0.5) * SIZE * 1.3,
            cy + RANDOM.nextDouble() * TOWER_HEIGHT * 1.2,
            cz + (RANDOM.nextDouble() - 0.5) * SIZE * 1.3,
            1, 0.0, 0.0, 0.0, 0.0
         );
      }
      // The wind, thrown off the footprint - the one particle in the game that reads as a shock
      // wave rather than as smoke, and the reason a blast this big does not look like a bonfire.
      mirageParticles(ParticleTypes.GUST_EMITTER_LARGE, cx, cy, cz, 8, SIZE / 3.0, 10.0, SIZE / 3.0, 0.0);
      mirageParticles(ParticleTypes.SMALL_GUST, cx, cy, cz, 120, SIZE / 2.0, 12.0, SIZE / 2.0, 0.02);
      mirageParticles(ParticleTypes.GUST, cx, cy, cz, 60, SIZE / 2.0, 8.0, SIZE / 2.0, 0.0);
      // And the mirage itself, going: the veil, the light drawn in behind it, the souls of a court
      // that was never there, and ash falling out of the sky over the whole footprint.
      mirageParticles(ParticleTypes.PORTAL, cx, cy, cz, 1400, SIZE / 2.0, TOWER_HEIGHT / 2.0, SIZE / 2.0, 0.7);
      mirageParticles(ParticleTypes.REVERSE_PORTAL, cx, cy, cz, 900, SIZE / 2.0, TOWER_HEIGHT / 2.0, SIZE / 2.0, -0.3);
      mirageParticles(ParticleTypes.SCULK_SOUL, cx, cy, cz, 320, SIZE / 2.0, 12.0, SIZE / 2.0, 0.1);
      mirageParticles(ParticleTypes.WHITE_ASH, cx, cy + 6.0, cz, 400, SIZE / 2.0, 16.0, SIZE / 2.0, 0.01);
      mirageParticles(ParticleTypes.FIREFLY, cx, cy, cz, 240, SIZE / 2.2, 14.0, SIZE / 2.2, 0.0);
      mirageParticles(ParticleTypes.END_ROD, cx, cy, cz, 200, SIZE / 3.0, 16.0, SIZE / 3.0, 0.15);

      // Three shockwaves walking out of a place that is no longer there.
      for (int ring = 0; ring < 3; ring++) {
         double radius = SIZE * 0.6 + ring * 9.0;
         int points = 48;
         for (int i = 0; i < points; i++) {
            double angle = (Math.PI * 2.0) * i / points;
            mirageParticles(
               ParticleTypes.CLOUD,
               cx + Math.cos(angle) * radius, origin.getY() + 1.2 + ring * 2.0, cz + Math.sin(angle) * radius,
               3, 0.4, 0.25, 0.4, 0.03
            );
         }
      }

      // The cacophony, layered so it lands as one event rather than as one noise: the blast, the
      // thing dying, the crack, the sky, and the light going out last of all.
      standing.playSound(null, cx, cy, cz, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.AMBIENT, 5.0F, 0.45F);
      standing.playSound(null, cx, cy, cz, SoundEvents.WITHER_DEATH, SoundSource.AMBIENT, 3.0F, 0.5F);
      standing.playSound(null, cx, cy, cz, SoundEvents.WARDEN_SONIC_BOOM, SoundSource.AMBIENT, 4.0F, 0.6F);
      standing.playSound(null, cx, cy, cz, SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.WEATHER, 3.0F, 0.6F);
      standing.playSound(null, cx, cy, cz, SoundEvents.ENDER_DRAGON_DEATH, SoundSource.AMBIENT, 2.0F, 0.7F);
      standing.playSound(null, cx, cy, cz, SoundEvents.END_PORTAL_SPAWN, SoundSource.AMBIENT, 3.0F, 0.5F);
      standing.playSound(null, cx, cy, cz, SoundEvents.RESPAWN_ANCHOR_DEPLETE.value(), SoundSource.AMBIENT, 2.0F, 0.4F);
      for (int i = 0; i < 8; i++) {
         standing.playSound(
            null,
            origin.getX() + RANDOM.nextDouble() * SIZE,
            origin.getY() + RANDOM.nextDouble() * TOWER_HEIGHT,
            origin.getZ() + RANDOM.nextDouble() * SIZE,
            SoundEvents.GENERIC_EXPLODE.value(), SoundSource.BLOCKS, 3.0F, 0.6F + RANDOM.nextFloat() * 0.6F
         );
      }

      List<ServerPlayer> caught = new ArrayList<>();
      for (ServerPlayer p : standing.getPlayers(pl -> pl.isAlive() && !pl.isSpectator() && !pl.isCreative() && inside(pl))) {
         caught.add(p);
         FATED.add(p.getUUID());
      }
      for (ServerPlayer p : caught) {
         Chat.raw(p, "\u00a7c\u00a7lTHE MIRAGE TAKES YOU WITH IT\u00a77 - you were still inside when it went.");
         float before = p.getHealth() + p.getAbsorptionAmount();
         try {
            p.hurtServer(standing, standing.damageSources().magic(), p.getMaxHealth() * 20.0F + 1000.0F);
         } catch (Throwable t) {
            com.fortuneandfavors.FortuneFavorsMod.LOGGER.warn("Fortune & Favors: the falling castle could not land its blow", t);
         }
         // Alive is not the same as saved. A save moves the body - a totem, a Curse of Undying
         // and the castle's own rescue all do - so a body that is exactly where the blow found
         // it is a blow something cancelled without saying so, and that is the one answer this
         // deadline cannot accept. See deadline().
         if (p.isAlive() && swallowed(before, p.getHealth() + p.getAbsorptionAmount())) {
            deadline(p, before);
         }
         FATED.remove(p.getUUID());
      }
      FATED.clear();
      collapsing = false;
      // The mood goes with the castle: the three effects are removed rather than left to run out,
      // because a player who walks out of a collapse and is still slowed, hungry and unable to
      // mine for the next seven seconds has been poisoned by a deadline that has already ended.
      for (ServerPlayer p : standing.getPlayers(pl -> !pl.isSpectator())) {
         p.removeEffect(MobEffects.SLOWNESS);
         p.removeEffect(MobEffects.HUNGER);
         p.removeEffect(MobEffects.MINING_FATIGUE);
      }

      dismantle(server);
   }

   /**
    * True when a blow left the body exactly as it found it.
    *
    * <p>The whole distinction between "saved" and "swallowed", in one comparison, and the reason
    * it is a method rather than a condition in a loop: every save this mod has <i>changes</i> the
    * body. A Totem of Undying and a Curse of Undying both put it on one heart and hand it two
    * effects, the castle's own rescue moves it to another dimension, the duel gate and the prison
    * both answer before the damage is applied. A body that did not move at all was not saved by
    * anything - it was cancelled by something that did not say so - and that is the one answer a
    * deadline cannot accept.
    */
   public static boolean swallowed(float before, float after) {
      return after >= before;
   }

   /**
    * The deadline, enforced when the blow itself was swallowed.
    *
    * <p>Reached only when a real blow with a real damage type left the body exactly as it found
    * it. Every save in this mod <i>changes</i> the body - a Totem of Undying and a Curse of
    * Undying both put it on one heart, the castle's own rescue moves it to another dimension,
    * the duel gate and the prison both answer before the damage is applied - so a body that did
    * not move is not a body something saved: it is a body something cancelled without saying so,
    * and this is the one place in the mod where that cannot be accepted.
    *
    * <p>Still an ordinary death, deliberately: a real damage type and a real health pool, so
    * keep-inventory, graves, the wormhole, the death message and every other death rule treat it
    * exactly like every other death. What it is not is <i>resisted</i>.
    *
    * @param before the health plus absorption the swallowed blow found, for the log line
    */
   private static void deadline(ServerPlayer p, float before) {
      // A creative player is not standing in this fight at all - and this path kills by hand, so
      // it is the one place the pipeline's own creative rule cannot reach. Enforcing a deadline on
      // somebody who is watching the castle fall in creative is how the report "the bosses can
      // damage me when I join as creative" ends with a death screen.
      //
      // Only creative is asked about: this mod puts a held player in spectator mode itself, and a
      // deadline that a camera move could switch off is not a deadline.
      if (p.isCreative()) {
         return;
      }
      try {
         p.hurtServer(standing, standing.damageSources().genericKill(), Float.MAX_VALUE);
      } catch (Throwable t) {
         com.fortuneandfavors.FortuneFavorsMod.LOGGER.warn("Fortune & Favors: the falling castle could not enforce its deadline", t);
      }
      if (p.isAlive()) {
         // A blow that did nothing at all, twice, with /kill's own damage type: something in the
         // mod or in another mod refuses this player damage outright. The death is applied
         // directly, and loudly, because the alternative is a player standing in a field where
         // the castle used to be, alive, with a chat line telling them they died.
         try {
            p.setHealth(0.0F);
            p.die(standing.damageSources().genericKill());
         } catch (Throwable t) {
            com.fortuneandfavors.FortuneFavorsMod.LOGGER.error("Fortune & Favors: could not apply the mirage's deadline", t);
         }
      }
      if (p.isAlive()) {
         com.fortuneandfavors.FortuneFavorsMod.LOGGER.error(
            "Fortune & Favors: {} was left alive by the falling castle at {} (health {}, absorption {}) - something is refusing fatal damage for this player",
            p.getName().getString(), p.blockPosition(), p.getHealth(), p.getAbsorptionAmount()
         );
      } else {
         com.fortuneandfavors.FortuneFavorsMod.LOGGER.info(
            "Fortune & Favors: the falling castle's deadline took {} (a blow of {} had been swallowed first)",
            p.getName().getString(), before
         );
      }
   }

   public static void dismantle(MinecraftServer server) {
      if (standing != null) {
         int limit = Math.min(risen, PLAN.size());

         for (int i = limit - 1; i >= 0; i--) {
            Place place = PLAN.get(i);
            try {
               standing.setBlock(place.pos, place.before, 3);
            } catch (Throwable ignored) {
            }
         }

         // After the ground is given back, not before: restoring a chest drops what was inside it,
         // and those items land where the chest stood - inside the footprint this sweep covers.
         discardDroppedLoot(standing);

         for (UUID id : GUARDS) {
            try {
               Entity guard = standing.getEntity(id);
               if (guard != null) {
                  mirageParticles(ParticleTypes.POOF, guard.getX(), guard.getY() + 1.0, guard.getZ(), 25, 0.5, 0.6, 0.5, 0.05);
                  guard.discard();
               }
            } catch (Throwable ignored) {
            }
         }

         // The guard and the elite go with the castle. A guard left standing in an empty field
         // with nothing to guard is the one leftover this whole design is careful not to leave.
         captain = null;
         captainSpent = false;
         // And so does the court: a King sitting in a field with no castle around him is the
         // same leftover, one throne worse.
         retireThroneHall();
      }

      standing = null;
      origin = null;
      kingSeat = null;
      THRONE_POSTS.clear();
      PLAN.clear();
      INDEX.clear();
      LOOT.clear();
      DESIGN_ROOMS.clear();
      BUILDINGS.clear();
      GUARD_SPOTS.clear();
      GUARDS.clear();
      risen = 0;
      settled = false;
      clearPlan(server);
   }

   /**
    * Boot-time sweep: finish any undo a killed session left behind.
    *
    * <p>This is what makes "it does not stay after a reset" true rather than hopeful. The
    * block list is written before the castle rises and cleared after it falls, so a file on
    * disk at boot can only mean one thing: a castle is standing in that world with nobody
    * left to take it down. The same file covers a single-player world being closed and
    * reopened, because the save carries it.
    */
   public static void sweepOnLoad(MinecraftServer server) {
      if (server == null) {
         return;
      }

      Path file = planFile(server);
      if (file == null || !Files.exists(file)) {
         return;
      }

      JsonObject root = JsonUtil.readOrCreate(file, new JsonObject());
      JsonArray blocks = root.has("blocks") && root.get("blocks").isJsonArray() ? root.getAsJsonArray("blocks") : null;

      if (blocks == null || blocks.size() < 4) {
         clearPlan(server);
         return;
      }

      String dim = JsonUtil.jsonString(root, "dimension", "minecraft:overworld");
      ServerLevel level = null;

      for (ServerLevel candidate : server.getAllLevels()) {
         if (candidate.dimension().identifier().toString().equals(dim)) {
            level = candidate;
            break;
         }
      }

      Block[] palette = new Block[0];

      if (root.has("palette") && root.get("palette").isJsonArray()) {
         JsonArray names = root.getAsJsonArray("palette");
         palette = new Block[names.size()];

         for (int i = 0; i < names.size(); i++) {
            palette[i] = BuiltInRegistries.BLOCK.get(Identifier.parse(names.get(i).getAsString())).map(h -> h.value()).orElse(Blocks.AIR);
         }
      }

      int restored = 0;

      if (level != null) {
         for (int i = 0; i + 3 < blocks.size(); i += 4) {
            try {
               BlockPos pos = new BlockPos(
                  blocks.get(i).getAsInt(), blocks.get(i + 1).getAsInt(), blocks.get(i + 2).getAsInt()
               );
               int index = blocks.get(i + 3).getAsInt();
               Block block = index >= 0 && index < palette.length ? palette[index] : Blocks.AIR;

               if (block == null) {
                  block = Blocks.AIR;
               }

               level.setBlock(pos, block.defaultBlockState(), 3);
               restored++;
            } catch (Throwable ignored) {
            }
         }
      }

      com.fortuneandfavors.FortuneFavorsMod.LOGGER.info(
         "[FF] A Mirage Castle was left standing by an earlier session in {} - {} block(s) put back.", dim, restored
      );
      clearPlan(server);
   }

   // ------------------------------------------------------------------- plan file

   private static Path planFile(MinecraftServer server) {
      try {
         return EconomyManager.getDataDir(server).resolve(PLAN_FILE);
      } catch (Throwable t) {
         return null;
      }
   }

   /**
    * Writes the way back, block by block, before the first one is placed.
    *
    * <p>Flat and palette-compressed on purpose: this file is around fifty thousand blocks,
    * and one JSON object per block would be megabytes of {@code {"x":..,"y":..}} for no
    * information the reader needs. The palette is the distinct block states, and each entry
    * is four numbers.
    */
   private static void savePlan(MinecraftServer server) {
      Path file = planFile(server);
      if (file == null) {
         return;
      }

      try {
         List<BlockState> palette = new ArrayList<>();
         Map<BlockState, Integer> paletteIndex = new HashMap<>();
         JsonArray flat = new JsonArray();

         for (Place place : PLAN) {
            Integer index = paletteIndex.get(place.before);

            if (index == null) {
               index = palette.size();
               palette.add(place.before);
               paletteIndex.put(place.before, index);
            }

            flat.add(place.pos.getX());
            flat.add(place.pos.getY());
            flat.add(place.pos.getZ());
            flat.add(index);
         }

         JsonObject root = new JsonObject();
         root.addProperty("dimension", standing == null ? "minecraft:overworld" : standing.dimension().identifier().toString());

         JsonArray names = new JsonArray();
         for (BlockState state : palette) {
            names.add(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
         }

         root.add("palette", names);
         root.add("blocks", flat);
         JsonUtil.write(file, root);
      } catch (Throwable t) {
         // A plan that could not be written is a castle that cannot be undone after a
         // crash - worth a warning, never worth crashing the tick that raised it.
         com.fortuneandfavors.FortuneFavorsMod.LOGGER.error("Fortune & Favors: could not write the mirage castle plan", t);
      }
   }

   // ---------------------------------------------------------------- test hooks

   /**
    * Raises a castle at a place the self-test can watch, and clears any earlier one.
    *
    * <p>Headless proof of the two promises that matter: it is big and it is undone exactly.
    * Nothing about this is a shortcut through the real path - it runs the same design, the
    * same plan write, the same rising and the same teardown a player's castle does.
    */
   public static boolean raiseAtForTest(MinecraftServer server, ServerLevel level, BlockPos corner) {
      dismantle(server);
      design(server, level, corner);
      return isStanding();
   }

   /** How many blocks the plan will touch. Test hook. */
   public static int plannedCount() {
      return PLAN.size();
   }

   /** Whether the last block has been placed and the guards are out. Test hook. */
   public static boolean settledForTest() {
      return settled;
   }

   /**
    * A position inside the castle that is really part of it: one of its own blocks.
    *
    * <p>Used to ask the question the whole event rests on - can a player break this? - of a
    * real wall rather than of a position that happens to be in the footprint and is actually
    * somebody's lawn.
    */
   public static BlockPos solidPointForTest() {
      for (Place place : PLAN) {
         if (!place.after.isAir()) {
            return place.pos;
         }
      }
      return null;
   }

   private static void clearPlan(MinecraftServer server) {
      try {
         Path file = planFile(server);
         if (file != null) {
            Files.deleteIfExists(file);
         }
      } catch (Throwable ignored) {
      }
   }
}
