package com.fortuneandfavors.economy;

import com.fortuneandfavors.util.Chat;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

/**
 * The Cell Block - the half of prison that isn't the mine.
 *
 * <p>Mining rich ore draws the wrong kind of attention. Prisoners build
 * <b>Heat</b>: guards start sweeping the tunnels, and if they put you down you
 * are dragged to <b>Solitary</b> (never killed - your gear is stashed and your
 * items are never lost). Selling hauls at the Prison Menu launders Heat away.
 *
 * <p>Between sweeps you can work <b>Contracts</b> out of the Prison Menu for
 * bonus cash, and every now and then a <b>loose brick</b> shows up in your mine.
 * Break it and you start an <b>escape run</b>: a tunnel opens with guards on
 * your heels and a glowstone exit at the far end. Reach it and you walk free
 * with a jackpot.
 */
public final class PrisonCellblock {
   public static final int HEAT_MAX = 100;
   /** Guards begin sweeping at this Heat. */
   public static final int WANTED = 45;
   /** Full lockdown: more guards, faster spawns. */
   public static final int MANHUNT = 75;
   /**
    * Prison cash of ore value that buys one point of Heat.
    *
    * <p>The meter used to move at {@code price / 45}, so a deep diamond was worth several points a
    * block and a rich face filled the bar in under a minute - the seam you came down for was also
    * the thing that summoned the guards, which made working the good rock cost more than it paid.
    * The divisor is larger now: the bar still climbs with value and with depth (see
    * {@code sectorHeatFactor}), but a run of ore is a day's work rather than an instant lockdown.
    */
   public static final double HEAT_PER_VALUE = 155.0;

   private static final String GUARD_TAG = "ff_prison_guard";
   /** The tag that names the prisoner a guard belongs to. It is the guard's only memory of why it is
    * there, which is what makes an orphaned one findable after a restart has emptied the registry. */
   private static final String GUARD_OWNER_TAG = "ff_prison_guard_of:";
   /**
    * The tag that names a prisoner a guard has <b>already</b> put cuffs on.
    *
    * <p>The cuff minigame is a greeting, not a loop: one man tries once. A guard that has already
    * arrested a prisoner never reaches for a second pair - it fights, and if it is the Warden that
    * is the whole difference between a killing blow (the chair) and a shackle that re-opens on every
    * swing and means the chair can never come. The mark goes on the guard's own body, so it survives
    * with the entity across a restart and two guards are still two attempts. */
   private static final String CUFFED_TAG = "ff_prison_cuffed_of:";
   /** The manhunt's own man. Carries {@link #GUARD_TAG} too, so the drop hook finds him. */
   public static final String WARDEN_TAG = "ff_prison_warden";
   /** How long the block waits before sending him again, if the last one never got the job done. */
   private static final long WARDEN_COOLDOWN = 20L * 90L;
   private static final int SOLITARY_X = PrisonManager.SOLITARY_X;
   private static final int SOLITARY_Y = PrisonManager.SOLITARY_Y;
   private static final int SOLITARY_Z = PrisonManager.SOLITARY_Z;
   /** How long a stint in the hole lasts. Thirty seconds, which is what a capture costs. */
   private static final long SOLITARY_TICKS = 20L * 30L;
   /** How long a prisoner is held in their own cell after being put down. */
   private static final long CELL_TICKS = 20L * 12L;
   /**
    * How long a prisoner has to click the lit lock before the guards walk them down.
    *
    * <p>It is a deadline per lock, not a budget for the whole struggle: the clock is reset by every
    * lock that opens, so the question is never "can you click five times in nine seconds" but "are
    * you watching the row". Two seconds is long enough to be a reaction and short enough that
    * looking away is the mistake - and it is the reason the guard stops swinging while it runs, so
    * the only thing being timed is the prisoner's own hands.
    */
   private static final long CUFF_CLICK_TICKS = 20L * 2L;
   /** The slots the cuff minigame's locks occupy, and how many locks a set of cuffs has. */
   public static final int CUFF_FIRST_SLOT = 10;
   public static final int CUFF_LOCKS = 7;
   private static final int CUFF_STEPS = 5;
   /**
    * How many items a guard takes on a hit, once the prisoner has shown they can get out of cuffs.
    *
    * <p>The first guard who lays hands on a prisoner cuffs them: one struggle, and failing it costs
    * the whole bag. After that the block stops being polite - every hit from a guard is five stacks
    * off the top of whatever is being carried, which is the reason the run is "sell it, or get off
    * the floor", and the reason a bigger mine is worth having.
    */
   private static final int GUARD_TAKE = 5;
   /**
    * How long a prisoner has to break away after a guard shakes them down, before he can take again.
    *
    * <p>A search that steals on every swing is not a chase, it is a tax with a walk animation: the
    * guard keeps pace with the prisoner, so five stacks a hit empties a bag in six seconds whatever
    * the player does. The block pays for the theft with a window - the guard who has just taken is
    * briefly slowed, the prisoner is briefly faster than him, and the next hit inside the window
    * takes nothing. "Sell it or run" only means anything if the running is a thing that can work.
    */
   private static final long SHAKEDOWN_GRACE = 20L * 4L;
   /** Chance per rich block mined that a loose brick appears. */
   private static final int LOOSE_BRICK_CHANCE = 220;
   private static final int ESCAPE_LENGTH = 26;
   /**
    * How long a break-out runs.
    *
    * <p>It was 45 seconds, which was right when the exit was a glowstone pad you sprinted at. The
    * last stretch is a locked gate and the Warden standing in front of it now, so the clock has to
    * cover a fight as well as a walk.
    */
   private static final long ESCAPE_TICKS = 20L * 90L;
   /** How far short of the exit the gate stands, so the run has an inside and an outside. */
   private static final int GATE_BACK = 4;
   /**
    * How far down the tunnel the Warden starts - and comes back - from the prisoner.
    *
    * <p>He used to be handed the floor two steps behind the loose brick, which put the man who is
    * supposed to be the price of the door in arm's reach of it: the prisoner broke the wall and he
    * was already swinging. He now starts near the gate at the far end, so the tunnel is a walk with
    * him at the end of it rather than a fight that begins in the prisoner's face.
    */
   private static final int WARDEN_SPAWN_BACK = 8;
   /**
    * The Warden's cuffs: a harder struggle than a cell-block guard's.
    *
    * <p>His shackle is the man himself in miniature. Where a guard's cuffs are five locks on a
    * two-second clock, the netherite shackle is seven locks on a one-second clock - the same test,
    * asked of better hands - and failing it costs a minute in the hole rather than a cell.
    */
   private static final int HARD_CUFF_STEPS = 7;
   private static final long HARD_CUFF_CLICK_TICKS = 20L;
   /** A Warden's capture sentence: a whole minute in solitary, not a cell. */
   private static final long WARDEN_SOLITARY_TICKS = 20L * 60L;

   /**
    * The execution block: the prison's own maximum-security wing, built into the block rather than
    * hung in the void.
    *
    * <p>It is a cell block like the one upstairs - a hall with the chair chamber off it, roofed and
    * walled on every face, so there is no sky to see and nowhere to fall. The shape is the one the
    * prisoner reads from their own bunk: a cell with a barred gate, a hall beyond it to run down,
    * and - straight across that hall, raised on a stepped dais with the executioner standing over it
    * - the chair. The gate is the bars, and the duct under the cell floor is the vent: it is covered
    * by gravel, digging it is loud, and the block sends a man to look.
    *
    * <p>The shell is built, not guessed at: every face of the whole wing is laid down as one of
    * three solid rings (floor, walls, roof) before anything is hollowed out, so there is no seam
    * between two rooms for a prisoner - or the void underneath - to find. See
    * {@link #buildExecutionShell}. Because the wing is the block's own building, the same protection
    * that refuses a dig through the hole's walls can refuse a dig through these, with the gravel in
    * the cell floor the one deliberate exception.
    */
   private static final int EXEC_X = SOLITARY_X + 24;
   private static final int EXEC_Y = SOLITARY_Y;
   private static final int EXEC_Z = SOLITARY_Z;
   /** The hall's own half-width: five wide, running along Z past the cell and the chair chamber. */
   private static final int EXEC_HALL_HALF_X = 2;
   /** How far the hall runs north and south of the cell's middle. The north leg is the run out: a
    *  long straight from the cell gate to the exit, so the chase down it is a chase. */
   private static final int EXEC_HALL_NORTH = 18;
   private static final int EXEC_HALL_SOUTH = 4;
   /** How tall the wing's rooms are inside, floor to roof. The chair chamber reads as a chamber. */
   private static final int EXEC_HEIGHT = 4;
   /** How long the guards pause after the gate gives, so the run is a run and not a reflex test. */
   private static final long EXECUTION_CATCH_GRACE = 20L;
   /** The cell's interior: four wide by five deep, off the hall's west wall. */
   private static final int EXEC_CELL_WEST = 7;
   private static final int EXEC_CELL_EAST = 4;
   private static final int EXEC_CELL_Z = 2;
   /** The chair chamber, across the hall from the cell gate: wide enough to be a room of its own. */
   private static final int EXEC_ALCOVE_EAST = 7;
   /** How far the one-by-one vent duct runs north under the hall, from the cell floor to its far end. */
   private static final int EXEC_DUCT_NORTH = EXEC_HALL_NORTH - 2;
   /** The outer shell of the whole wing, for protection and for walking out anybody stranded in it. */
   private static final int EXEC_MIN_X = EXEC_X - EXEC_CELL_WEST - 1;
   private static final int EXEC_MAX_X = EXEC_X + EXEC_ALCOVE_EAST + 1;
   private static final int EXEC_MIN_Y = EXEC_Y - 3;
   private static final int EXEC_MAX_Y = EXEC_Y + EXEC_HEIGHT;
   private static final int EXEC_MIN_Z = EXEC_Z - EXEC_HALL_NORTH - 1;
   private static final int EXEC_MAX_Z = EXEC_Z + EXEC_HALL_SOUTH + 1;
   /** How long a prisoner has to get out of the block before the chair takes over. */
   public static final long EXECUTION_TICKS = 20L * 60L;
   /**
    * How long the chair takes to kill once the escape window is gone, and how hard each charge is.
    *
    * <p>Two hearts a charge, and a charge every two-thirds of a second, which is the chair working
    * rather than the chair hinting. It used to be half a heart a second - a full minute of standing
    * still in a room that had already killed you - and the report that produced this number was
    * simply "the chair should deal way more damage". The clock stays as the backstop it always was;
    * the charges are what ends it now.
    */
   private static final long CHAIR_TICKS = 20L * 12L;
   private static final long EXECUTION_SHOCK_TICKS = 13L;
   private static final float EXECUTION_SHOCK = 4.0F;
   /** Each charge is worth a little more than the last, so the last seconds are the loudest. */
   private static final float EXECUTION_SHOCK_STEP = 0.5F;
   /** How close a cell-block guard has to get before he drags the prisoner to the chair early. */
   private static final double EXECUTION_CATCH_RANGE = 2.0;
   /**
    * The vent: how many blocks of gravel cover the hatch, how far the man sent to look gets, and how
    * long he takes to arrive.
    *
    * <p>The vent used to be a trapdoor a prisoner stood on until a timer ran out, which is a wait
    * with a hatch painted on it. It is a digging job now: the duct mouth is buried under gravel in
    * the cell floor, taking the gravel up is what opens it, and every block that comes away is heard
    * - so the block puts a man in the hall and gives the prisoner the one thing a sentence should
    * give, which is a decision about how fast to work.
    *
    * <p>{@value #VENT_DIGS} is the size of the patch itself and not a wish about it: the counter on
    * the action bar reads against this number, so a patch of a different size is a counter that
    * promises gravel that is not there. The self-test asserts the two are the same, and that every
    * position the patch records is really {@code minecraft:gravel} in the world - which is the check
    * that catches a duct cut through its own hatch, where the block counts a wall as gravel.
    */
   private static final int VENT_DIGS = 4;
   private static final long VENT_SENTRY_TICKS = 20L * 8L;
   /** How close the man sent to look must get - measured through the bars, so a little further. */
   private static final double VENT_CATCH_RANGE = 5.0;
   /**
    * How long the block refuses a prisoner after the sentence is carried out.
    *
    * <p>Being thrown out of the mode is the punishment; the cooldown is what stops it being a free
    * exit. Two minutes is long enough to be a wait and short enough that a player comes back.
    */
   public static final long PRISON_COOLDOWN_TICKS = 20L * 120L;

   private static final Map<UUID, Integer> heat = new HashMap<>();
   private static final Map<UUID, Long> lastGuardSpawn = new HashMap<>();
   /**
    * Who is locked up, where, and until when.
    *
    * <p>One map rather than two booleans, because the two places a prisoner can be held - the hole
    * and their own cell - are the same situation with different furniture, and everything that asks
    * about it (guards, Heat, mining, the menu) wants the same answer either way. What differs is the
    * release: out of the hole goes to the mine, out of a cell goes to the mine as well, and the one
    * thing both must do is actually put the prisoner back - being left standing in a locked room with
    * the sentence over is what "it does not teleport me back" was.
    */
   private static final Map<UUID, Sentence> sentences = new HashMap<>();
   /** Prisoners in cuffs right now, and how the struggle is going. */
   private static final Map<UUID, Cuffs> cuffs = new HashMap<>();
   /** Guards each prisoner has already shaken off - which is what makes the next one a thief. */
   private static final Map<UUID, Integer> breakouts = new HashMap<>();
   /** When each shaken-down prisoner's window to run closes. Absent means no window is open. */
   private static final Map<UUID, Long> shakeGrace = new HashMap<>();

   /**
    * A stint behind a door: the hole ({@code cell = false}) or the prisoner's own cell.
    *
    * <p>{@code total} is the sentence as it was handed down, carried so the stay-time readout can
    * draw a bar that actually empties instead of a number with nothing to measure it against. It is
    * stored rather than derived because the hole is served at three different lengths - a capture, a
    * Warden's shackle and a fall - and the bar has to know which one this is.
    */
   private record Sentence(long until, boolean cell, long total) {
   }

   /**
    * What one pane of the lock row is doing, so the menu and the self-test read the same fact.
    *
    * <p>"At a glance" is a property of the row, not of a tooltip: exactly one lock is ever
    * {@link #LIT}, the locks already worked are {@link #OPENED}, and everything else is
    * {@link #IDLE}. A prisoner looking at the board therefore has one question to answer - the lit
    * one - and the row itself keeps score of how far through they are.
    */
   public enum Lock {
      OPENED,
      LIT,
      IDLE
   }

   /**
    * One struggle with a pair of cuffs.
    *
    * <p>The locks are a sliding series rather than a code: the menu lights one slot, the prisoner
    * clicks it, the next lights, and a wrong click puts them back to the first. Five clicks of work
    * inside two seconds of it lighting, which is a thing a person does and a script has no reason to.
    */
   private static final class Cuffs {
      final List<Integer> locks = new ArrayList<>(CUFF_STEPS);
      int step;
      long until;
      int slips;
      /** True for the Warden's netherite shackle: more locks, a shorter clock. */
      boolean hard;
      /** How long each lock stays lit. Per-struggle, because the Warden's row is faster. */
      long clickTicks = CUFF_CLICK_TICKS;
   }
   private static final Map<UUID, List<Contract>> contracts = new HashMap<>();
   private static final Map<UUID, Escape> escapes = new HashMap<>();
   /** Loose bricks currently in the world, with the block they replaced so they can be put back. */
   private static final Map<BlockPos, LooseBrick> looseBricks = new HashMap<>();
   /**
    * The bars of every live break-out's gate, by position, so the gate can be protected and found.
    *
    * <p>A prisoner holds an iron pickaxe. A gate made of anything breakable is a gate that opens
    * for anyone who brought the right tool, so the bars are refused by the same block-breaking
    * funnel that refuses a standing Mirage Castle's walls - see {@code LevelDestroyBlockMixin}.
    */
   private static final Map<BlockPos, UUID> gateBars = new HashMap<>();

   private record LooseBrick(UUID owner, BlockState original) {
   }
   private static final Map<UUID, Set<UUID>> guards = new HashMap<>();
   private static final Map<UUID, Integer> guardRewardCarry = new HashMap<>();
   /** The Warden currently on each prisoner's trail, and when one was last sent. */
   private static final Map<UUID, UUID> wardens = new HashMap<>();
   private static final Map<UUID, Long> lastWardenSent = new HashMap<>();
   /** Prisoners in the chair right now, and the room they are sitting in. */
   private static final Map<UUID, Execution> executions = new HashMap<>();
   /** When each thrown-out prisoner may come back, by game tick. Absent means they may walk in. */
   private static final Map<UUID, Long> prisonCooldown = new HashMap<>();

   /**
    * One trip to the chair.
    *
    * <p>The block itself is carved and remembered, exactly as a break-out tunnel is, so the wing is
    * given back as it was found the moment the sentence ends - by the vent, by the bars, or by the
    * executioner. Everything else is the sentence: which half of it the prisoner is in (the
    * sixty-second escape window, or the chair), the clock, the charges, and how far they have got
    * with the two ways out.
    */
   private static final class Execution {
      /** The two halves of the sentence: the escape window in the block, then the chair. */
      enum Phase { CELL, CHAIR }

      final BlockPos chair;
      /** The duct mouth, under the gravel in the cell floor. */
      final BlockPos vent;
      /** Where the duct comes up into the hall: the far end of the crawl, and a way out. */
      final BlockPos riser;
      /** Where a body is put down when the sentence begins: the cell, facing the chair. */
      final BlockPos start;
      /** The far end of the hall: the finish of the run once the cell gate is open. */
      final BlockPos exit;
      /** Absolute corners of the whole wing - cell, hall, chamber and duct - for protection. */
      final BlockPos wingMin;
      final BlockPos wingMax;
      /** The bars still standing, and every bar the wing was built with. */
      final List<BlockPos> bars = new ArrayList<>();
      final List<BlockPos> allBars = new ArrayList<>();
      /** The gravel over the duct mouth, and the duct's own far end - both shut until the dig is done. */
      final List<BlockPos> ventGravel = new ArrayList<>();
      final List<BlockPos> ventSeal = new ArrayList<>();
      /**
       * Every block the vent's dig covers: the four gravel over the duct, and the duct's own walk
       * level beneath them.
       *
       * <p>The patch is laid over an open hole - one whole column of the duct is under it - so the
       * block beside the one a prisoner breaks falls a level the moment the break lands. Ground
       * rather than the patch is what the dig and the refusal both read: see the dig in
       * {@link #onBlockMined}.
       */
      final Set<BlockPos> ventGround = new HashSet<>();
      final Map<BlockPos, BlockState> carved = new HashMap<>();
      final Set<UUID> guards = new HashSet<>();
      /** The block's own company - the executioner and the gallery - which is not the capture detail. */
      final Set<UUID> cast = new HashSet<>();
      Phase phase = Phase.CELL;
      long endTick;
      long nextShockTick;
      long crawlUntil;
      int barsBroken;
      /** Blocks of gravel taken off the duct mouth, and whether the way down is open yet. */
      int ventDirt;
      boolean ventOpen;
      /** When the noise started, and the man sent to look at it. */
      long ventAlertTick;
      UUID sentry;
      /** Charges the chair has delivered, so each one can be worth a little more than the last. */
      int shocks;
      boolean guardsOut;
      /** True once every bar of the cell gate is gone: the run to the exit has begun. */
      boolean gateOpen;
      long gateOpenTick;

      Execution(BlockPos chair, BlockPos vent) {
         this.chair = chair;
         this.vent = vent;
         this.riser = new BlockPos(EXEC_X + 2, EXEC_Y - 1, EXEC_Z - EXEC_HALL_NORTH + 3);
         this.start = new BlockPos(EXEC_X - 6, EXEC_Y, EXEC_Z);
         this.exit = new BlockPos(EXEC_X, EXEC_Y, EXEC_Z - EXEC_HALL_NORTH);
         this.wingMin = new BlockPos(EXEC_MIN_X, EXEC_MIN_Y, EXEC_MIN_Z);
         this.wingMax = new BlockPos(EXEC_MAX_X, EXEC_MAX_Y, EXEC_MAX_Z);
      }
   }

   /** True when a position lies inside the execution wing's own shell. */
   private static boolean insideWing(Execution exec, BlockPos pos) {
      return pos.getX() >= exec.wingMin.getX() && pos.getX() <= exec.wingMax.getX()
         && pos.getY() >= exec.wingMin.getY() && pos.getY() <= exec.wingMax.getY()
         && pos.getZ() >= exec.wingMin.getZ() && pos.getZ() <= exec.wingMax.getZ();
   }
   private static final Random RANDOM = new Random();
   /**
    * The block's clock as the last tick left it, and the part of that clock which belongs to
    * <i>earlier</i> server processes.
    *
    * <p>Every timer in the block is an absolute reading of this clock (see {@link #clock}), so what
    * makes them survive a restart is that the clock itself does: {@code clockBase} is the ticks the
    * block had already run before this process started, read back from the block's own file, and the
    * live clock is that base plus the server's tick counter. Both are written together, and the
    * difference between a deadline and the base it was written against is the wait the next boot
    * reads back.
    */
   private static long clockBase = 0L;
   private static long lastKnownTick = 0L;

   /**
    * The clock every timer in the block runs on: the server's own tick counter.
    *
    * <p>Not {@code level.getGameTime()}, which is what every one of these timers was measured from,
    * and which does not advance outside the overworld at all. {@code ServerLevel.tickTime()} is the
    * only writer of a level's game time and it returns immediately unless the level was created
    * with {@code tickTime} true - and the only level {@code MinecraftServer} creates that way is
    * the overworld. Every dimension this mod makes its own, the prison included, therefore has a
    * game time frozen at the value it was created with.
    *
    * <p>What that cost, invisibly, was every clock in the block: a sentence never expired, a
    * struggle never ran out of clicks, an escape never ended, the maximum-security countdown never
    * reached its chair, and the vent - dig the gravel out of the cell floor, drop into the duct,
    * crawl to the far end - could be dug but never finished, because the crawl's own ending was
    * compared against a number that never changed. The gravel minigame was unwinnable in the way
    * that is hardest to report: everything worked, and the door never opened.
    *
    * <p>One helper rather than a dozen swapped call sites, so the timers cannot drift apart from
    * each other again - they are all the same subtraction, and it has to be the same clock.
    *
    * <p>What it is <i>not</i> is a clock that survives a restart. {@code getTickCount()} counts from
    * zero every time the process starts, which is fine for a timer that lives and dies inside one
    * session and wrong for every one that is written to disk: a two-minute lock-out stored as "tick
    * 33,197" read back, on the next boot, as a wait of thirty-three thousand ticks - the "the block
    * takes you back in 20000s" report, exactly. So the block keeps its own clock. {@link #clockBase}
    * is the part of it that belongs to earlier sessions, restored from the same file the deadlines
    * are, and what is written beside each deadline is the base it was measured from. A wait served
    * inside the block therefore pauses while the server is down, which is what a wait should do.
    */
   public static long clock(Level level) {
      if (level == null || level.getServer() == null) {
         return clockBase;
      }
      return clockBase + level.getServer().getTickCount();
   }

   private PrisonCellblock() {
   }

   // ------------------------------------------------------------------
   // Heat
   // ------------------------------------------------------------------

   /**
    * What the block makes of a prisoner's Heat, in words.
    *
    * <p>Heat used to be a number and a bar, which is the shape of a meter rather than of a
    * situation: a prisoner at 44 and a prisoner at 74 read as the same orange, and the guard that
    * arrives at 45 was therefore a surprise every time. A tier has a name, a colour and - this is
    * the part that matters - a sentence about what the prison is now doing about you, so the meter
    * answers the only question a prisoner has about it, which is "what happens next".
    *
    * <p>The thresholds are the constants the block already acts on rather than new numbers, so the
    * name and the behaviour cannot disagree: {@link #ALERT} begins exactly where the guards do,
    * {@link #LOCKDOWN} exactly where the Warden does. A display that invented its own boundaries
    * would be a second source of truth about the same fact.
    */
   public enum Alert {
      CLEAR("CLEAR", "§a", 0, "nothing - the block has not noticed you"),
      SUSPICIOUS("SUSPICIOUS", "§e", 25, "the guards are watching the tunnels, not you"),
      ALERT("ALERT", "§6", WANTED, "a guard has your trail and is closing on your mine"),
      LOCKDOWN("LOCKDOWN", "§c", MANHUNT, "the whole block is hunting you - sell your haul"),
      CRITICAL("CRITICAL", "§4", 95, "the Warden is coming for you personally");

      public final String label;
      public final String colour;
      public final int from;
      /** What the prison is doing about a prisoner at this tier. */
      public final String doing;

      Alert(String label, String colour, int from, String doing) {
         this.label = label;
         this.colour = colour;
         this.from = from;
         this.doing = doing;
      }
   }

   /** The tier a Heat level falls in. */
   public static Alert alertOf(int h) {
      Alert out = Alert.CLEAR;
      for (Alert alert : Alert.values()) {
         if (h >= alert.from) {
            out = alert;
         }
      }
      return out;
   }

   public static Alert alertOf(UUID uuid) {
      return alertOf(heatOf(uuid));
   }

   /** The meter, with the tier's name on it, for the menu and the messages. */
   public static String alertLine(UUID uuid) {
      Alert alert = alertOf(uuid);
      return alert.colour + "§l" + alert.label + "§r " + heatBar(uuid) + "§8 - " + alert.doing;
   }

   public static int heatOf(UUID uuid) {
      return Math.max(0, Math.min(HEAT_MAX, heat.getOrDefault(uuid, 0)));
   }

   public static boolean isWanted(UUID uuid) {
      return heatOf(uuid) >= WANTED;
   }

   public static boolean isManhunt(UUID uuid) {
      return heatOf(uuid) >= MANHUNT;
   }

   public static String heatBar(UUID uuid) {
      int h = heatOf(uuid);
      int filled = Math.min(10, (h + 9) / 10);
      String colour = alertOf(h).colour;
      StringBuilder sb = new StringBuilder("§8[");
      for (int i = 0; i < 10; i++) {
         sb.append(i < filled ? colour : "§8").append('❚');
      }
      return sb.append("§8] ").append(colour).append(h).append("§8/§7").append(HEAT_MAX).toString();
   }

   private static void addHeat(ServerPlayer player, int amount) {
      if (amount == 0) {
         return;
      }
      UUID uuid = player.getUUID();
      // The Trust upgrade buys a cooler record: see PrisonManager.trustHeatMult. It scales the gain
      // only - a blow that adds Heat still adds at least a point - so it is a discount and not a
      // switch that turns the whole ladder off.
      int gain = amount > 0 ? Math.max(1, (int)Math.round(amount * PrisonManager.trustHeatMult(uuid))) : amount;
      int before = heatOf(uuid);
      int after = Math.max(0, Math.min(HEAT_MAX, before + gain));
      heat.put(uuid, after);
      if (before < WANTED && after >= WANTED) {
         player.level()
            .playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.RAVAGER_ROAR, SoundSource.HOSTILE, 0.8F, 1.4F);
         Chat.raw(player, "§c§lA GUARD HAS PICKED UP YOUR TRAIL!§r §7- sell your haul to lose them.");
      }
      if (before < MANHUNT && after >= MANHUNT) {
         Chat.raw(player, "§4§lLOCKDOWN!§r §7The whole block is after you. Get to the Prison Menu and sell.");
         broadcast(player, "§4⚒ §f" + player.getName().getString() + " §4has the whole cell block hunting them!");
      }
      // The two ends of the ladder that had no name of their own. The middle three already speak -
      // a guard at 45, the lockdown at 75 - and these are the rungs either side of them: the first
      // time the block starts paying attention, and the point where it stops sending guards and
      // starts sending the man.
      if (before < Alert.SUSPICIOUS.from && after >= Alert.SUSPICIOUS.from) {
         Chat.raw(
            player,
            "§e§lSUSPICIOUS§r §7- the tunnels are being watched. §fHeat"
               + "§7 climbs faster the deeper you work, so this is the shallow end of the problem."
         );
      }
      if (before < Alert.CRITICAL.from && after >= Alert.CRITICAL.from) {
         Chat.raw(player, "§4§lCRITICAL§r §7- you are the file now. §cThe Warden is coming for you personally.");
         player.level()
            .playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.WARDEN_ROAR, SoundSource.HOSTILE, 1.0F, 0.7F);
      }
   }

   private static void broadcast(ServerPlayer player, String text) {
      for (ServerPlayer p : player.level().getServer().getPlayerList().getPlayers()) {
         if (PrisonManager.isInPrison(p)) {
            Chat.raw(p, text);
         }
      }
   }

   // ------------------------------------------------------------------
   // Mining hook
   // ------------------------------------------------------------------

   /** Called for every block a prisoner breaks inside the mine. */
   public static void onBlockMined(ServerPlayer player, BlockPos pos, BlockState state) {
      if (isConfined(player.getUUID())) {
         return;
      }
      // The chair's bars first: they are worth nothing, the price check below would drop them, and
      // two of them coming away is what brings the cell block down on the prisoner.
      Execution exec = executions.get(player.getUUID());
      if (exec != null && exec.bars.remove(pos)) {
         exec.barsBroken++;
         Chat.raw(player, "§7A bar comes away. §f" + exec.bars.size() + "§7 left in the gate.");
         if (exec.barsBroken >= 2 && !exec.guardsOut) {
            exec.guardsOut = true;
            spawnExecutionGuards(player, exec);
            Chat.raw(player, "§4§lTHE CELL BLOCK STORMS THE HALL!§r §7Two bars is two too many.");
         }
         if (exec.bars.isEmpty()) {
            // The gate gives, but the gate is not the exit: the run down the hall is. Every bar
            // gone opens the cell and turns the guards loose; the sentence only ends when the
            // prisoner reaches the far end of the hall.
            openExecutionGate(player, (ServerLevel)player.level(), exec);
         }
         return;
      }
      // The gravel over the duct: the vent, and the only part of this wing a prisoner is meant to
      // break. Every block that comes away is heard - see spawnVentSentry - and the last one opens
      // the way down.
      //
      // The ground rather than the patch, and the block above the one dug: a gravel knocked down a
      // level is still the vent's own dig, and it takes the patch position it fell out of with it,
      // so the counter reaches four whichever way the gravel went.
      if (exec != null && exec.ventGround.contains(pos)) {
         ServerLevel execLevel = (ServerLevel)player.level();
         exec.ventGravel.remove(pos);
         exec.ventGravel.remove(pos.above());
         exec.ventDirt++;
         if (exec.ventAlertTick == 0L) {
            exec.ventAlertTick = clock(execLevel);
            spawnVentSentry(player, execLevel, exec);
            Chat.raw(player, "§c§lSOMETHING HEARD THAT§r §7- the block is sending a man. §f" + (VENT_SENTRY_TICKS / 20L) + "s§7.");
            title(player, "§c§lDIG. FAST.", "§7he is already walking");
         }
         execLevel.playSound(null, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, SoundEvents.GRAVEL_BREAK, SoundSource.BLOCKS, 1.4F, 0.6F);
         execLevel.sendParticles(ParticleTypes.CRIT, pos.getX() + 0.5, pos.getY() + 0.4, pos.getZ() + 0.5, 12, 0.4, 0.3, 0.4, 0.03);
         Chat.raw(player, "§7Gravel comes away. §f" + exec.ventGravel.size() + "§7 left over the duct.");
         if (exec.ventGravel.isEmpty()) {
            openVent(exec, execLevel);
            Chat.raw(player, "§a§lTHE DUCT IS OPEN§r §7- get in before the man they sent finds you.");
            title(player, "§a§lIN", "§7down the hole - now");
         }
         return;
      }
      // A gate bar next, because a bar is worth nothing and the price check below would drop it:
      // cutting your way out of a break-out is the one break in the block that has nothing to do
      // with what the block is worth.
      UUID owner = gateBars.get(pos);
      if (owner != null && owner.equals(player.getUUID())) {
         Escape running = escapes.get(owner);
         if (running != null) {
            barBroken((ServerLevel)player.level(), running, pos);
            Chat.raw(player, "§7A bar comes away. §f" + running.gate.size() + "§7 left in the gate.");
         }
         return;
      }
      long price = PrisonManager.priceFor(state);
      if (price <= 0L) {
         return;
      }
      // Contracts count every sellable block, not just the rich ones.
      progress(player, Kind.MINE, 1);
      // A loose brick can work free of any wall the prisoner is actually working, not only the rich
      // ore - the escape is a break-out, and gating it behind a diamond face was the reason it felt
      // like it stopped existing on the shallow floors a prisoner spends their first ranks on.
      maybeLooseBrick(player, pos);
      // Common stone is invisible to the guards - only ore worth smuggling draws them.
      if (price < 15L) {
         return;
      }
      // Momentum and the rich seams are about the blocks a prisoner came down here for, so digging
      // through the rock *between* two veins never breaks the chain.
      PrisonManager.onOreMined(player, pos, state, price);
      // Sector hazard: the same seam is worth more the deeper it is and costs more to take, which is
      // the decision the whole ladder is built around. Rounded to whole points, so the Yard is a
      // mild place to work rather than a place where every stud still costs a point of Heat.
      addHeat(player, (int)Math.max(1L, Math.round(
         price / HEAT_PER_VALUE * PrisonManager.sectorHeatFactor(player) * PrisonEvents.heatGainMult(clock(player.level()))
      )));
   }

   /**
    * Force a loose brick at the prisoner, for {@code /ff test prison loosebrick}.
    *
    * <p>It goes through the same door mining does - the same block choice, the same one-per-prisoner
    * rule, the same particles - so a test of the escape is a test of the escape and not of a second
    * implementation of it. The only difference is that it is not a roll.
    *
    * @return null on success, or why no brick could be set
    */
   public static String forceLooseBrick(ServerPlayer player) {
      if (player == null || !PrisonManager.isInPrison(player)) {
         return "You are not in the block - this is a prison escape.";
      }
      UUID uuid = player.getUUID();
      if (isConfined(uuid)) {
         return "You cannot dig your way out of solitary - serve the sentence.";
      }
      if (escapes.containsKey(uuid)) {
         return "You are already in a break-out.";
      }
      ServerLevel level = (ServerLevel)player.level();
      if (placeLooseBrick(player, level, player.blockPosition(), true)) {
         return null;
      }
      return "No solid wall near you to work a brick out of";
   }

   /**
    * Put one loose brick in a wall near a point, if there is a minerable block to loosen.
    *
    * @param announce true to tell the prisoner in chat; a random roll does, the test command does too
    * @return true when a brick was set
    */
   private static boolean placeLooseBrick(ServerPlayer player, ServerLevel level, BlockPos near, boolean announce) {
      UUID uuid = player.getUUID();
      for (int tries = 0; tries < 8; tries++) {
         int dx = RANDOM.nextInt(5) - 2;
         int dz = RANDOM.nextInt(5) - 2;
         BlockPos p = near.offset(dx, 0, dz);
         BlockState s = level.getBlockState(p);
         if (s.isAir() || PrisonManager.priceFor(s) <= 0L) {
            continue;
         }
         // Only ever one brick per prisoner: the old one goes back where it was, so a mine never
         // accumulates a trail of magic loose bricks.
         restoreLooseBricks(level, uuid);
         level.setBlock(p, Blocks.CRACKED_STONE_BRICKS.defaultBlockState(), 3);
         looseBricks.put(p, new LooseBrick(uuid, s));
         level.sendParticles(ParticleTypes.CRIT, p.getX() + 0.5, p.getY() + 1.1, p.getZ() + 0.5, 14, 0.35, 0.35, 0.35, 0.03);
         level.sendParticles(ParticleTypes.LAVA, p.getX() + 0.5, p.getY() + 0.6, p.getZ() + 0.5, 4, 0.2, 0.2, 0.2, 0.0);
         level.playSound(null, p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5, SoundEvents.STONE_BREAK, SoundSource.BLOCKS, 1.0F, 0.7F);
         if (announce) {
            Chat.raw(player, "§7A §f• loose brick §7has worked free of the wall nearby...");
            Chat.raw(player, "§8Break it to attempt an escape.");
         }
         return true;
      }
      return false;
   }

   private static void maybeLooseBrick(ServerPlayer player, BlockPos mined) {
      UUID uuid = player.getUUID();
      if (escapes.containsKey(uuid) || isConfined(uuid)) {
         return;
      }
      if (RANDOM.nextInt(LOOSE_BRICK_CHANCE) != 0) {
         return;
      }
      ServerLevel level = (ServerLevel)player.level();
      // Mark a nearby still-solid block as loose - a brick knocked out of the wall.
      placeLooseBrick(player, level, mined, true);
   }

   private static void restoreLooseBricks(ServerLevel level, UUID owner) {
      looseBricks.entrySet().removeIf(e -> {
         if (!e.getValue().owner().equals(owner)) {
            return false;
         }
         level.setBlock(e.getKey(), e.getValue().original(), 3);
         return true;
      });
   }

   /** Test hook: the loose brick this prisoner has worked free, or null when they have none. */
   public static BlockPos looseBrickForTest(UUID uuid) {
      for (Map.Entry<BlockPos, LooseBrick> e : looseBricks.entrySet()) {
         if (e.getValue().owner().equals(uuid)) {
            return e.getKey();
         }
      }
      return null;
   }

   /** True if the broken position was a loose brick (which then starts an escape run). */
   public static boolean onLooseBrickBroken(ServerPlayer player, BlockPos pos) {
      LooseBrick brick = looseBricks.remove(pos);
      if (brick == null || !brick.owner().equals(player.getUUID())) {
         return false;
      }
      startEscape(player, pos);
      return true;
   }

   // ------------------------------------------------------------------
   // Escape runs
   // ------------------------------------------------------------------

   private static final class Escape {
      final net.minecraft.core.Direction dir;
      final BlockPos start;
      final BlockPos end;
      final long endTick;
      final Map<BlockPos, BlockState> carved = new HashMap<>();
      /** The bars of the gate, and whether the Warden has handed over the key yet. */
      final List<BlockPos> gate = new ArrayList<>();
      boolean key = false;
      boolean gateOpen = false;
      /**
       * The last stretch, held: the gate's own walls, floor and roof.
       *
       * <p>Without this the gate is not a door, it is a sign. The tunnel is carved through a mine
       * slab and every prisoner is holding an iron pickaxe, so a wall beside a locked gate is a
       * detour measured in seconds - the bars would be decoration. What is held is this run's own
       * rock, and sealing the run gives it back exactly as it was found.
       */
      net.minecraft.world.phys.AABB zone = null;
      /** When the block may send the Warden again, if the tunnel's own one has been lost. */
      long nextWardenTick = 0L;

      Escape(net.minecraft.core.Direction dir, BlockPos start, BlockPos end, long endTick) {
         this.dir = dir;
         this.start = start;
         this.end = end;
         this.endTick = endTick;
      }
   }

   /**
    * The bars a break-out raises: a wall three wide and three tall across the tunnel, four blocks
    * short of the exit.
    *
    * <p>Pure geometry, because it is the thing the run is *about* - a gate one block off the
    * tunnel's centre line is a hole next to a wall, and a gate level with the exit is not a gate at
    * all - and because the self-test can then ask where it would stand without carving a tunnel to
    * find out.
    */
   public static List<BlockPos> gateBars(BlockPos from, net.minecraft.core.Direction dir) {
      BlockPos gate = from.relative(dir, ESCAPE_LENGTH - GATE_BACK);
      net.minecraft.core.Direction side = dir.getClockWise();
      List<BlockPos> bars = new ArrayList<>(9);
      for (int w = -1; w <= 1; w++) {
         for (int h = 0; h <= 2; h++) {
            bars.add(gate.relative(side, w).above(h));
         }
      }
      return bars;
   }

   /**
    * The box the gate's wall stands in, from the exit's own geometry.
    *
    * <p>Derived rather than remembered so a break-out resumed after a restart rebuilds the exact
    * same wall it was protecting before: a stored box and a live one could disagree, and a gate
    * whose wall is a block out is a hole beside a door.
    */
   private static net.minecraft.world.phys.AABB zoneFor(BlockPos end, net.minecraft.core.Direction dir) {
      net.minecraft.core.Direction side = dir.getClockWise();
      BlockPos a = end.relative(dir.getOpposite(), GATE_BACK + 2).relative(side, 3).above(-1);
      BlockPos b = end.relative(dir, 3).relative(side, -3).above(4);
      return new net.minecraft.world.phys.AABB(
         Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()),
         Math.max(a.getX(), b.getX()) + 1.0, Math.max(a.getY(), b.getY()) + 1.0, Math.max(a.getZ(), b.getZ()) + 1.0
      );
   }

   /**
    * Does the gate open?
    *
    * <p>One rule, and it is deliberately not a roll and not a lockpick: the Warden of the break-out
    * carries the key, and he hands it over by dying. Which is the whole shape of the run - the
    * tunnel is the walk, the gate is the door, and the Warden is the price of the door.
    */
   public static boolean escapeGateOpens(boolean hasKey) {
      return hasKey;
   }

   /**
    * What a completed break-out pays.
    *
    * <p>It was 1,200 and a rank's worth of pocket change - less than two guard bounties, for the
    * only run in the block that can get you killed by a Warden. A break-out is the mode's set
    * piece, so it pays like one: the top of the ladder is a whole rank-up out of one tunnel.
    */
   public static long escapeJackpot(int rank) {
      return 3000L + 700L * rank;
   }

   /** True while this block is a live break-out's gate: the one thing a prisoner cannot dig through. */
   /**
    * The wall the gate stands in - the thing a prisoner cannot tunnel around.
    *
    * <p>The bars themselves are deliberately NOT part of this any more. They were, which made the
    * gate the one door in the block that could only be opened by killing a boss: if the Warden never
    * arrived, wandered off the edge of the world, or was simply too much for the prisoner, the run
    * was over with the exit in sight and no way to take it. A barred gate is nine iron bars; a
    * prisoner with an iron pickaxe can work through it, slowly and at the cost of the clock, and that
    * is the safety valve the run needed. The wall beside and past it is still the block's own rock,
    * so cutting the bars is a detour and not a way past the door.
    */
   public static boolean isEscapeGate(BlockPos pos) {
      if (pos == null) {
         return false;
      }

      // The bars are part of the gate. They used to be the one exception - a safety valve for a run
      // whose Warden never arrived - but the Warden is guaranteed now (see startEscape), so the
      // exception is only a door anybody with the issued pickaxe can take apart.
      if (gateBars.containsKey(pos)) {
         return true;
      }

      for (Escape esc : escapes.values()) {
         if (esc.zone != null && esc.zone.contains(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5)) {
            return true;
         }
      }

      return false;
   }

   /**
    * A prisoner has taken one of the gate's bars out.
    *
    * <p>Counted rather than remembered: the run's own list of bars is what the wall is measured
    * against, so the gate opens exactly when the last bar is gone, and the Warden's key opens it the
    * moment it arrives either way.
    */
   private static void barBroken(ServerLevel level, Escape esc, BlockPos pos) {
      esc.gate.remove(pos);
      gateBars.remove(pos);
      level.sendParticles(ParticleTypes.CRIT, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 8, 0.3, 0.3, 0.3, 0.02);
      if (esc.gate.isEmpty() && !esc.gateOpen) {
         openGate(level, esc);
      }
   }

   /** Whether this prisoner is carrying the key the Warden dropped. */
   public static boolean hasEscapeKey(UUID uuid) {
      Escape esc = escapes.get(uuid);
      return esc != null && esc.key;
   }

   private static void startEscape(ServerPlayer player, BlockPos from) {
      UUID uuid = player.getUUID();
      if (escapes.containsKey(uuid)) {
         return;
      }
      ServerLevel level = (ServerLevel)player.level();
      // Nearest horizontal facing decides which way the tunnel goes.
      float yaw = player.getYRot();
      double rad = Math.toRadians(yaw);
      double fx = -Math.sin(rad);
      double fz = Math.cos(rad);
      net.minecraft.core.Direction dir = Math.abs(fx) >= Math.abs(fz)
         ? (fx >= 0 ? net.minecraft.core.Direction.EAST : net.minecraft.core.Direction.WEST)
         : (fz >= 0 ? net.minecraft.core.Direction.SOUTH : net.minecraft.core.Direction.NORTH);
      Escape esc = new Escape(dir, from, from.relative(dir, ESCAPE_LENGTH), clock(level) + ESCAPE_TICKS);
      // Carve a proper corridor: SEVEN wide (two walls, five walkable), five tall, stone floor and a
      // stone roof.
      //
      // <p>It was three wide, which is a corridor for a queue and not for a fight: the Warden is two
      // blocks of hitbox and a sprint, the guards come up behind, and a prisoner who had to turn
      // around in it was already caught. Five walkable blocks is enough to strafe a boss in, which is
      // the whole difference between a run that can be won and one that is a corridor with a boss at
      // the end of it. It also starts two steps behind the loose brick, so the prisoner begins the
      // run inside their own tunnel instead of standing in the hole they just made.
      for (int step = -2; step <= ESCAPE_LENGTH; step++) {
         BlockPos centre = from.relative(dir, step);
         net.minecraft.core.Direction side = dir.getClockWise();
         for (int w = -2; w <= 2; w++) {
            BlockPos col = centre.relative(side, w);
            for (int h = -1; h <= 4; h++) {
               BlockState fill = h == -1
                  ? Blocks.STONE_BRICKS.defaultBlockState()
                  : h == 4 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
               carve(esc, level, col.above(h), fill);
            }
            for (int h = -1; h <= 4; h++) {
               carve(esc, level, col.above(h).relative(side, 3), Blocks.STONE_BRICKS.defaultBlockState());
               carve(esc, level, col.above(h).relative(side, -3), Blocks.STONE_BRICKS.defaultBlockState());
            }
         }
      }
      BlockPos end = esc.end;
      for (int dx = -3; dx <= 3; dx++) {
         for (int dz = -3; dz <= 3; dz++) {
            carve(esc, level, end.offset(dx, -1, dz), Blocks.GLOWSTONE.defaultBlockState());
            carve(esc, level, end.offset(dx, 4, dz), Blocks.STONE.defaultBlockState());
            for (int dy = 0; dy <= 3; dy++) {
               carve(esc, level, end.offset(dx, dy, dz), Blocks.AIR.defaultBlockState());
            }
         }
      }
      // The gate's own wall: stone either side of the nine bars, floor to roof, so a five-wide tunnel
      // still has exactly one way through the door line.
      BlockPos gateLine = esc.end.relative(dir.getOpposite(), GATE_BACK);
      net.minecraft.core.Direction gateSide = dir.getClockWise();
      for (int w = -2; w <= 2; w++) {
         for (int h = -1; h <= 4; h++) {
            if (Math.abs(w) <= 1 && h >= 0 && h <= 2) {
               continue;
            }
            carve(esc, level, gateLine.relative(gateSide, w).above(h), Blocks.STONE_BRICKS.defaultBlockState());
         }
      }
      // The gate: nine bars across the tunnel four blocks short of the exit, with a lantern over
      // them so the last stretch reads as a door rather than as more corridor. Carved like
      // everything else, so a failed run puts the rock back exactly as it was found.
      for (BlockPos bar : gateBars(from, dir)) {
         carve(esc, level, bar, Blocks.IRON_BARS.defaultBlockState());
         esc.gate.add(bar);
         gateBars.put(bar, uuid);
      }
      BlockPos gateAt = esc.end.relative(dir.getOpposite(), GATE_BACK);
      carve(esc, level, gateAt.above(3), Blocks.SEA_LANTERN.defaultBlockState());

      // And the wall the door is set in. Two blocks of rock before the gate, three past the exit,
      // three to each side and from the floor to above head height: a bypass has to go around all
      // of it, which is the difference between a gate and a gate-shaped suggestion.
      esc.zone = zoneFor(esc.end, dir);

      escapes.put(uuid, esc);
      // Guards flood the tunnel behind you - and the Warden is already down there, because the
      // gate at the end of this tunnel is his to open.
      int guardCount = 3 + Math.min(3, PrisonManager.rankOf(uuid) / 8);
      for (int i = 0; i < guardCount; i++) {
         BlockPos spot = from.relative(dir, 2 + i * 6);
         // On the tunnel's own floor, which is the stone the carve laid one block under the corridor.
         spawnGuard(player, isStandable(level, spot) ? spot : from.relative(dir, 2));
      }
      // Down at the gate end, not over the prisoner's shoulder: see WARDEN_SPAWN_BACK.
      wardenFor(player, tunnelSpot(level, player, esc.end.relative(dir.getOpposite(), WARDEN_SPAWN_BACK)));
      broadcast(player, "§4§lBREAK OUT! §f" + player.getName().getString() + " §4has cracked the wall open - the Warden is coming!");
      Chat.raw(player, "§c§lESCAPE RUN§r §7- the exit is §f" + (ESCAPE_LENGTH - GATE_BACK) + " §7blocks away, behind a §fbarred gate§7.");
      Chat.raw(player, "§7The gate needs the Warden's key. Put him down, then walk out. You have §f90s§7.");
      level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.WITHER_SPAWN, SoundSource.HOSTILE, 0.7F, 1.6F);
   }

   /**
    * The Warden, or the one already out - a break-out does not get a second one of him.
    *
    * <p>He is the manhunt's man as well, so a prisoner who is already at full Heat has one on his
    * trail by definition. Handing out another would make the gate open twice.
    */
   private static Mob wardenFor(ServerPlayer player) {
      return wardenFor(player, null);
   }

   /**
    * The Warden, or the one already out - a break-out does not get a second one of him.
    *
    * <p>He is the manhunt's man as well, so a prisoner who is already at full Heat has one on his
    * trail by definition. Handing out another would make the gate open twice.
    *
    * @param at an exact spot to put him on, or null to look for one around the prisoner. A
    *           break-out passes the tunnel's own floor, because a Warden spawned into the rock
    *           beside a hand-carved corridor is a Warden who is never seen again.
    */
   private static Mob wardenFor(ServerPlayer player, BlockPos at) {
      ServerLevel level = (ServerLevel)player.level();
      UUID existing = wardens.get(player.getUUID());
      net.minecraft.world.entity.Entity live = existing == null ? null : level.getEntity(existing);

      if (live instanceof Mob mob && mob.isAlive()) {
         return mob;
      }

      return spawnWarden(player, at);
   }

   /** A standable spot on the tunnel's own floor near a point, or the point itself as a fallback. */
   private static BlockPos tunnelSpot(ServerLevel level, ServerPlayer player, BlockPos preferred) {
      if (isStandable(level, preferred)) {
         return preferred;
      }
      BlockPos at = player.blockPosition();
      return isStandable(level, at) ? at : at.above();
   }

   /**
    * Put the Warden on this prisoner's trail right now.
    *
    * <p>{@link #wardenFor} is the manhunt's own door and refuses to hand out a second Warden, which
    * is exactly what the Pit's Warden Challenge wants too: the fight is with the block's man, not
    * with a copy of him. This just exposes that door, so the Pit does not have to invent a second
    * Warden who would then disagree with the manhunt about who is on the trail.
    */
   public static Mob callWarden(ServerPlayer player) {
      if (player == null) {
         return null;
      }
      return wardenFor(player);
   }

   /** The Warden on this prisoner's trail right now, or null. */
   private static Mob wardenOf(ServerLevel level, UUID uuid) {
      UUID id = wardens.get(uuid);
      net.minecraft.world.entity.Entity e = id == null ? null : level.getEntity(id);
      return e instanceof Mob mob && mob.isAlive() ? mob : null;
   }

   /**
    * A break-out ending badly: the tunnel is put back, and the prisoner is put back with it.
    *
    * <p>The tunnel being sealed around a prisoner who is still standing in it is what "it replaces
    * the blocks and teleports me back" looked like from the inside - the rock came back, and the body
    * was left inside it. Every ending that is not the exit now ends with the prisoner back on their
    * own floor.
    */
   private static void endEscape(ServerPlayer player, ServerLevel level, Escape esc, String why) {
      sealTunnel(level, esc);
      for (UUID id : new ArrayList<>(guards.getOrDefault(player.getUUID(), Set.of()))) {
         net.minecraft.world.entity.Entity e = level.getEntity(id);
         if (e != null && !isWarden(e)) {
            e.discard();
         }
         pruneGuard(player.getUUID(), id);
      }
      heat.put(player.getUUID(), Math.min(HEAT_MAX, heatOf(player.getUUID()) + 25));
      Chat.raw(player, why);
      BlockPos spawn = PrisonManager.freshFloorSpawn(player);
      player.teleport(
         new TeleportTransition(
            level, new Vec3(spawn.getX() + 0.5, spawn.getY() + 1.0, spawn.getZ() + 0.5),
            Vec3.ZERO, player.getYRot(), player.getXRot(), TeleportTransition.PLACE_PORTAL_TICKET
         )
      );
      level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ANVIL_LAND, SoundSource.HOSTILE, 1.0F, 0.8F);
   }

   private static void tickEscapes(ServerLevel level) {
      if (escapes.isEmpty()) {
         return;
      }
      for (Map.Entry<UUID, Escape> entry : new ArrayList<>(escapes.entrySet())) {
         UUID uuid = entry.getKey();
         Escape esc = entry.getValue();
         ServerPlayer p = level.getServer().getPlayerList().getPlayer(uuid);
         if (p == null || !PrisonManager.isInPrison(p)) {
            sealTunnel(level, esc);
            escapes.remove(uuid);
            continue;
         }
         // The gate, before the exit: the barred wall is what the run is for, and the key is what
         // opens it. Standing at it without the key is not a failure - it is the choice of whether
         // to go back for the Warden or to run for the tunnel mouth.
         if (!esc.gate.isEmpty() && !esc.gateOpen && p.blockPosition().closerThan(esc.end.relative(esc.dir.getOpposite(), GATE_BACK), 5.0)) {
            if (escapeGateOpens(esc.key)) {
               openGate(level, esc);
            } else {
               p.sendSystemMessage(
                  Component.literal("§c§lTHE GATE IS LOCKED §8| §7the Warden is carrying the key"), true
               );
            }
         }

         // The Warden is the price of the door, so he has to actually be walking the tunnel. He is
         // re-targeted every tick, and if the block has lost track of him - he fell out of the world,
         // a restart dropped him, a prisoner put him in a wall - he is sent again after a short wait.
         // "The breakout has no Warden chasing you" was a Warden who had been left standing at the
         // mouth of the tunnel, or who had never arrived at all.
         if (!esc.key && !esc.gateOpen) {
            Mob man = wardenOf(level, uuid);
            if (man == null) {
               if (clock(level) >= esc.nextWardenTick) {
                  Mob back = wardenFor(p, tunnelSpot(level, p, esc.end.relative(esc.dir.getOpposite(), WARDEN_SPAWN_BACK)));
                  if (back != null) {
                     // Only a Warden who actually arrived buys the wait. A refused spawn - the level
                     // will not hold a body there - is retried on the next tick, the same rule the
                     // manhunt uses, so a break-out cannot spend its whole ninety seconds with
                     // nobody carrying the key because the first attempt landed in the rock.
                     esc.nextWardenTick = clock(level) + 100L;
                     p.sendSystemMessage(Component.literal("§4§lTHE WARDEN §8| §7he is back on your trail"), true);
                  }
               }
            } else {
               man.setTarget(p);
               // If he has been left three dozen blocks behind, he is not chasing - he is scenery.
               // He is put back in the tunnel behind the prisoner instead.
               if (man.distanceToSqr(p) > 36.0 * 36.0) {
                  BlockPos behind = p.blockPosition().relative(esc.dir.getOpposite(), 6);
                  if (isStandable(level, behind)) {
                     man.teleportTo(behind.getX() + 0.5, behind.getY(), behind.getZ() + 0.5);
                  }
               }
            }
         }

         boolean arrived = p.blockPosition().distSqr(esc.end) <= 9.0;
         boolean expired = clock(level) >= esc.endTick;
         boolean recaptured = PrisonManager.isInSideZone(p) || isConfined(uuid);
         if (arrived) {
            escapes.remove(uuid);
            // The run is over, so its tunnel goes back exactly as it was found: success used to
            // leave the carved corridor standing in the mine forever, a permanent hole in a wall
            // that was meant to be a one-off. The guards and the Warden are stood down with it.
            sealTunnel(level, esc);
            releaseGuards(level, uuid);
            int rank = PrisonManager.rankOf(uuid);
            long jackpot = escapeJackpot(rank);
            PrisonManager.addCash(p, jackpot);
            heat.put(uuid, 0);
            progress(p, Kind.ESCAPE, 1);
            Chat.raw(p, "§a§lFREEDOM!§r §7You slipped the block and picked the lock on the evidence locker.");
            Chat.raw(p, "§7+§e" + Chat.moneyStr(jackpot) + "§7 prison cash · Heat wiped.");
            broadcast(p, "§a⚒ §f" + p.getName().getString() + " §aescaped the cell block!");
            com.fortuneandfavors.economy.ServerNewspaperManager.logEvent(
               level.getServer(), p.getName().getString() + " broke out of the prison cell block."
            );
            level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 1.2F, 1.4F);
            level.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, p.getX(), p.getY() + 1.0, p.getZ(), 60, 0.8, 0.8, 0.8, 0.3);
         } else if (expired || recaptured) {
            escapes.remove(uuid);
            endEscape(p, level, esc, "§c§lRECAPTURED!§r §7The guards dragged you back. Heat §c+25§7.");
         } else {
            // Trail markers so you can't lose the corridor.
            BlockPos here = p.blockPosition();
            level.sendParticles(ParticleTypes.END_ROD, here.getX() + 0.5, here.getY() + 1.2, here.getZ() + 0.5, 3, 0.3, 0.3, 0.3, 0.01);
            long left = Math.max(0L, (esc.endTick - clock(level)) / 20L);
            p.sendSystemMessage(
               Component.literal("§4§lESCAPE §8| §7Exit §f" + (int)Math.sqrt(here.distSqr(esc.end)) + "m §8| §7Time §f" + left + "s"), true
            );
         }
      }
   }

   /**
    * Replaces one block of the tunnel, remembering what was there first. The
    * original state is recorded exactly once per position, so sealing restores
    * the site byte-for-byte instead of leaving a stray corridor behind.
    */
   private static void carve(Escape esc, ServerLevel level, BlockPos pos, BlockState state) {
      esc.carved.putIfAbsent(pos, level.getBlockState(pos));
      level.setBlock(pos, state, 3);
   }

   /** Puts the carved tunnel back exactly as it was, so failed break-outs leave no holes. */
   private static void sealTunnel(ServerLevel level, Escape esc) {
      for (Map.Entry<BlockPos, BlockState> e : esc.carved.entrySet()) {
         level.setBlock(e.getKey(), e.getValue(), 3);
      }

      forgetGate(esc);
   }

   /** Opens the gate: the bars come out, and the wall they were is gone for good in this run. */
   private static void openGate(ServerLevel level, Escape esc) {
      for (BlockPos bar : esc.gate) {
         level.setBlock(bar, Blocks.AIR.defaultBlockState(), 3);
         gateBars.remove(bar);
      }

      esc.gateOpen = true;
      level.playSound(null, esc.end, SoundEvents.IRON_DOOR_OPEN, SoundSource.BLOCKS, 1.4F, 0.8F);
      level.playSound(null, esc.end, SoundEvents.IRON_TRAPDOOR_OPEN, SoundSource.BLOCKS, 1.2F, 1.1F);
      level.sendParticles(
         ParticleTypes.ELECTRIC_SPARK, esc.end.getX() + 0.5, esc.end.getY() + 1.0, esc.end.getZ() + 0.5, 40, 1.2, 1.0, 1.2, 0.05
      );
   }

   /** The gate is only a gate while the run is live: once it is over, the bars are nobody's. */
   private static void forgetGate(Escape esc) {
      for (BlockPos bar : esc.gate) {
         gateBars.remove(bar);
      }
   }

   public static boolean isEscaping(UUID uuid) {
      return escapes.containsKey(uuid);
   }

   // ------------------------------------------------------------------
   // Guards
   // ------------------------------------------------------------------

   public static int guardCount(UUID uuid) {
      Set<UUID> set = guards.get(uuid);
      return set == null ? 0 : set.size();
   }

   private static Mob spawnGuard(ServerPlayer player, BlockPos at) {
      ServerLevel level = (ServerLevel)player.level();
      Mob guard = EntityTypes.VINDICATOR.create(level, EntitySpawnReason.COMMAND);
      if (guard == null) {
         return null;
      }
      guard.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
      guard.setPersistenceRequired();
      guard.addTag(GUARD_TAG);
      guard.addTag(GUARD_OWNER_TAG + player.getUUID());
      guard.setCustomName(Component.literal("§4§lCell Block Guard"));
      guard.setCustomNameVisible(true);
      int rank = PrisonManager.rankOf(player.getUUID());
      guard.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_AXE));
      guard.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
      guard.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
      var hp = guard.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH);
      if (hp != null) {
         hp.setBaseValue(guardHealth(rank));
      }
      var dmg = guard.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE);
      if (dmg != null) {
         dmg.setBaseValue(guardDamage(rank));
      }
      guard.setHealth(guard.getMaxHealth());
      guard.setTarget(player);
      // A spawn the level refused is not a guard. Recording it anyway would put a body on the block's
      // books that is not in the world - and, worse, one that no release can ever take off again.
      if (!level.addFreshEntity(guard)) {
         return null;
      }
      guards.computeIfAbsent(player.getUUID(), k -> new HashSet<>()).add(guard.getUUID());
      guardRewardCarry.put(guard.getUUID(), 120 + 18 * rank);
      level.sendParticles(ParticleTypes.ANGRY_VILLAGER, at.getX() + 0.5, at.getY() + 2.0, at.getZ() + 0.5, 4, 0.3, 0.3, 0.3, 0.0);
      return guard;
   }

   /**
    * What the Warden pays, what he tanks and what he swings - pure, so all three can be pinned.
    *
    * <p>The manhunt used to escalate by arithmetic nobody could feel: four guards instead of two,
    * spawning a little sooner. Those are the same fight held for longer. The Warden is the one
    * thing full Heat now sends that a prisoner has to *answer*: he hits like a boss, he does not
    * stop, and taking him down is the only way back to a clean record without selling.
    */
   public static long wardenReward(int rank) {
      return 2500L + 500L * rank;
   }

   public static double wardenHealth(int rank) {
      return 40.0 + 6.0 * rank;
   }

   public static double wardenDamage(int rank) {
      return 3.0 + 0.12 * rank;
   }

   /**
    * A cell-block guard's health and axe, pure so the fight can be judged without one.
    *
    * <p>Both used to climb hard with rank - thirty health and four damage at rank A, three hundred
    * and twelve at Z - which made the rank-and-file a damage race rather than the arrest they are
    * supposed to be: a prisoner died to the axe before the cuffs minigame ever opened. A guard is a
    * grip, not a killing stroke, so the numbers are small now and the fight is winnable.
    */
   public static double guardHealth(int rank) {
      return 10.0 + 1.5 * rank;
   }

   public static double guardDamage(int rank) {
      return 2.0 + 0.08 * rank;
   }

   /** WARNING: the manhunt's answer to a prisoner who keeps digging. One at a time, per prisoner. */
   private static Mob spawnWarden(ServerPlayer player) {
      return spawnWarden(player, null);
   }

   /**
    * The Warden on a trail, at a spot of the caller's choosing or one found around the prisoner.
    *
    * <p>The spot matters: the manhunt spawns him wherever there is footing, but a break-out hands
    * him the tunnel's own floor, because a Warden put down inside the rock beside a hand-carved
    * corridor is exactly the bug where "the Warden never spawns".
    */
   private static Mob spawnWarden(ServerPlayer player, BlockPos at) {
      ServerLevel level = (ServerLevel)player.level();
      Mob warden = EntityTypes.VINDICATOR.create(level, EntitySpawnReason.COMMAND);
      if (warden == null) {
         return null;
      }

      int rank = PrisonManager.rankOf(player.getUUID());
      BlockPos spot = at != null ? at : safeGuardSpot(player);
      // The chunk has to exist before he is put in it. An entity handed to a chunk the server is
      // not simulating is dropped again on the next tick, which is exactly "the Warden spawns and
      // instantly despawns": he was real for one frame and then the level stopped holding him.
      level.getChunkAt(spot);
      warden.setPos(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5);
      warden.setPersistenceRequired();
      warden.addTag(GUARD_TAG);
      warden.addTag(WARDEN_TAG);
      warden.addTag(GUARD_OWNER_TAG + player.getUUID());
      warden.setCustomName(Component.literal("§4§lThe Warden"));
      warden.setCustomNameVisible(true);
      warden.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.NETHERITE_AXE));
      warden.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.NETHERITE_HELMET));
      warden.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.NETHERITE_CHESTPLATE));
      warden.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.NETHERITE_LEGGINGS));
      warden.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.NETHERITE_BOOTS));
      for (EquipmentSlot slot : EquipmentSlot.values()) {
         warden.setDropChance(slot, 0.0F);
      }
      var hp = warden.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH);
      if (hp != null) {
         hp.setBaseValue(wardenHealth(rank));
      }
      var dmg = warden.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE);
      if (dmg != null) {
         dmg.setBaseValue(wardenDamage(rank));
      }
      var speed = warden.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED);
      if (speed != null) {
         speed.setBaseValue(0.31);
      }
      warden.setHealth(warden.getMaxHealth());
      warden.setTarget(player);
      if (!level.addFreshEntity(warden)) {
         // A spawn the level refused is not a Warden on the trail: say so by returning nothing, so
         // the caller does not record him as sent and leave a 90-second gap where a man should be.
         return null;
      }
      guards.computeIfAbsent(player.getUUID(), k -> new HashSet<>()).add(warden.getUUID());
      guardRewardCarry.put(warden.getUUID(), (int)Math.min(Integer.MAX_VALUE, wardenReward(rank)));
      wardens.put(player.getUUID(), warden.getUUID());
      // The Warden does not share the block: every cell-block guard on the same prisoner is stood
      // down the moment he arrives, so the manhunt is one man and one fight rather than a crowd with
      // a boss in it.
      clearCellGuards(level, player.getUUID(), warden.getUUID());
      level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, spot.getX() + 0.5, spot.getY() + 1.0, spot.getZ() + 0.5, 30, 0.5, 0.8, 0.5, 0.03);
      level.playSound(null, spot, SoundEvents.WARDEN_ROAR, SoundSource.HOSTILE, 1.2F, 0.7F);
      broadcast(player, "§4§lTHE WARDEN§r §7is on your trail. Put him down and your record is §fclean§7.");
      return warden;
   }

   /** True for the manhunt's man - he is a guard, and something else besides. */
   public static boolean isWarden(net.minecraft.world.entity.Entity entity) {
      return entity != null && entity.entityTags().contains(WARDEN_TAG);
   }

   /** How many Wardens are out right now, for the self-test. */
   /**
    * Puts a Heat level on a body, so the checkpoint's scan can be read at each tier.
    *
    * <p>Heat is otherwise only ever raised by mining, which needs a live mine, a live sector and a
    * live player - none of which a rule about a percentage should require.
    */
   public static void setHeatForTest(UUID uuid, int value) {
      heat.put(uuid, Math.max(0, Math.min(HEAT_MAX, value)));
   }

   // ------------------------------------------------------------------
   // Heat tools
   // ------------------------------------------------------------------
   //
   // Heat had exactly one sink outside the mine - selling - which made a prisoner who had dug
   // themselves hot choose between the till and being hunted. These are the other answer: contraband
   // tools bought with tokens at the exchange that bleed Heat while they are carried. Deliberately
   // slow and small, because a tool that cooled faster than mining heats would just delete the
   // ladder; what it buys is the walk to the next seam.

   /** Ticks between single points of Heat off, for the cheap tool and the strong one. */
   public static final int RIG_COOL_TICKS = 40;
   public static final int COOLANT_COOL_TICKS = 20;
   /** The tool names, in one place, so the buyer and the carrier read the same string. */
   private static final String RIG_NAME = "§b§lCooling Rig";
   private static final String COOLANT_NAME = "§b§lContraband Coolant";

   /** The cheap prison tool: a rig of borrowed ice that bleeds a point of Heat every two seconds. */
   public static ItemStack coolingRig() {
      ItemStack s = new ItemStack(Items.BLUE_ICE);
      s.set(DataComponents.CUSTOM_NAME, Component.literal(RIG_NAME));
      s.set(DataComponents.LORE, new net.minecraft.world.item.component.ItemLore(List.of(
         Component.literal("§7A rig of smuggled ice. Carry it and"),
         Component.literal("§7your Heat drops §f1§7 every §f" + (RIG_COOL_TICKS / 20) + "s§7."),
         Component.literal("§8Lost when you leave the block.")
      )));
      return s;
   }

   /** The strong prison tool: coolant off the maintenance truck, a point of Heat every second. */
   public static ItemStack coolingCoolant() {
      ItemStack s = new ItemStack(Items.PACKED_ICE);
      s.set(DataComponents.CUSTOM_NAME, Component.literal(COOLANT_NAME));
      s.set(DataComponents.LORE, new net.minecraft.world.item.component.ItemLore(List.of(
         Component.literal("§7Coolant off the works cart. Carry it and"),
         Component.literal("§7your Heat drops §f1§7 every §f" + (COOLANT_COOL_TICKS / 20) + "s§7."),
         Component.literal("§8Lost when you leave the block.")
      )));
      return s;
   }

   /**
    * The strongest cooling tool a prisoner is carrying right now, in ticks per point, or 0.
    *
    * <p>Read by name off the carried stack: the names are the tool, and there is no second copy of
    * them anywhere, so what the exchange hands out is exactly what the tick loop looks for.
    */
   public static int toolCoolTicks(ServerPlayer player) {
      boolean coolant = false;
      boolean rig = false;
      net.minecraft.world.entity.player.Inventory inv = player.getInventory();
      for (int i = 0; i < inv.getContainerSize(); i++) {
         ItemStack s = inv.getItem(i);
         if (s.isEmpty() || !(s.getItem() == Items.BLUE_ICE || s.getItem() == Items.PACKED_ICE)) {
            continue;
         }
         Component name = s.get(DataComponents.CUSTOM_NAME);
         if (name == null) {
            continue;
         }
         String text = name.getString();
         if (text.equals(COOLANT_NAME)) {
            coolant = true;
         } else if (text.equals(RIG_NAME)) {
            rig = true;
         }
      }
      return coolant ? COOLANT_COOL_TICKS : rig ? RIG_COOL_TICKS : 0;
   }

   /** A one-line description of the tool being carried, for the menu, or null when carrying none. */
   public static String toolLine(ServerPlayer player) {
      int ticks = toolCoolTicks(player);
      if (ticks <= 0) {
         return null;
      }
      return "§b" + (ticks == COOLANT_COOL_TICKS ? "Contraband Coolant" : "Cooling Rig")
         + "§7: §f1§7 Heat every §f" + (ticks / 20) + "s§7.";
   }

   /** Once a second, a carried tool bleeds a point of Heat off its prisoner. */
   private static void tickHeatTools(ServerLevel level) {
      long now = clock(level);
      if (now % 20L != 0L) {
         return;
      }
      for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
         if (!PrisonManager.isInPrison(p)) {
            continue;
         }
         int interval = toolCoolTicks(p);
         if (interval <= 0 || now % interval != 0L) {
            continue;
         }
         UUID uuid = p.getUUID();
         int h = heatOf(uuid);
         if (h > 0) {
            heat.put(uuid, h - 1);
         }
      }
   }

   public static int wardenCountForTest() {
      return wardens.size();
   }

   /**
    * Test hook: how many Warden bodies are alive in a level right now.
    *
    * <p>Deliberately a count of the world and not of {@link #wardens}, which is the bug this exists
    * for: a release that drops the map entry and leaves the man is a release the map cannot tell from
    * a real one.
    */
   public static int wardenBodiesForTest(ServerLevel level) {
      if (level == null) {
         return 0;
      }
      return level.getEntities(
         (net.minecraft.world.entity.Entity)null,
         new net.minecraft.world.phys.AABB(-3.0E7, -128.0, -3.0E7, 3.0E7, 512.0, 3.0E7),
         PrisonCellblock::isWarden
      ).size();
   }

   /** A guard was killed: pay the bounty, drop the Heat and count the contract. */
   public static void onGuardKilled(ServerPlayer player, Mob guard) {
      UUID uuid = player.getUUID();
      boolean wardenKill = isWarden(guard);
      Integer reward = guardRewardCarry.remove(guard.getUUID());
      long pay = reward == null ? 150L : reward.longValue();
      PrisonManager.addCash(player, pay);

      if (wardenKill) {
         // A clean record, not a discount on one: the Warden is what full Heat *means*, so
         // surviving him is the answer to it. Selling is still the quiet way out.
         heat.put(uuid, 0);
         wardens.remove(uuid);
         Chat.raw(player, "§4§lTHE WARDEN FALLS§r §7- §a+§e" + Chat.moneyStr(pay) + "§a prison cash, and your record is §fclean§7.");
         broadcast(player, "§7The cell block loses interest in you. §aHeat §f0§7.");
         progress(player, Kind.GUARDS, 5);

         // And if he fell while you were in the tunnel, he hands over the key to his own gate.
         // Taking him down *is* the run's objective; the exit is just where you spend it.
         Escape esc = escapes.get(uuid);
         if (esc != null && !esc.gateOpen) {
            esc.key = true;
            Chat.raw(player, "§a§lKEY TAKEN§r §7- the barred gate will open for you now.");
            Chat.raw(player, "§7Break for the gate: it is §f" + (ESCAPE_LENGTH - GATE_BACK) + "§7 blocks down the tunnel.");
            com.fortuneandfavors.util.SoundUtil.play(player, com.fortuneandfavors.ModSounds.LOOT_TICK);
         }
      } else {
         int after = Math.max(0, heatOf(uuid) - 15);
         heat.put(uuid, after);
         // Putting a guard down is the other way to beat one: the block has learned this prisoner is
         // hard to hold, so the next one to reach them takes instead of cuffing.
         enterSecondPhase(player);
         Chat.raw(player, "§a+§e" + Chat.moneyStr(pay) + "§a for putting a guard down. §7Heat §a-15§7.");
         progress(player, Kind.GUARDS, 1);
      }

      pruneGuard(uuid, guard.getUUID());
   }

   public static boolean isGuard(net.minecraft.world.entity.Entity entity) {
      return entity != null && entity.entityTags().contains(GUARD_TAG);
   }

   private static void pruneGuard(UUID owner, UUID guard) {
      Set<UUID> set = guards.get(owner);
      if (set != null) {
         set.remove(guard);
      }
      guardRewardCarry.remove(guard);
   }

   /**
    * Stands down every cell-block guard on a prisoner except the one named.
    *
    * <p>The Warden is the escalation, not an addition. Sending him and four guards at once is four
    * bodies to fight through before the one that matters, which is the opposite of a manhunt; he
    * arrives alone, and the rank and file go back to the block. The chair's own guard book is
    * cleared too, so a guard put down by the Warden's arrival cannot be re-released from a list.
    */
   private static void clearCellGuards(ServerLevel level, UUID owner, UUID keep) {
      Set<UUID> set = guards.get(owner);
      if (set != null) {
         for (UUID id : new ArrayList<>(set)) {
            if (id.equals(keep)) {
               continue;
            }
            net.minecraft.world.entity.Entity e = level.getEntity(id);
            if (e != null) {
               e.discard();
            }
            set.remove(id);
            guardRewardCarry.remove(id);
         }
      }
      Execution exec = executions.get(owner);
      if (exec != null) {
         for (UUID id : new ArrayList<>(exec.guards)) {
            if (id.equals(keep)) {
               continue;
            }
            net.minecraft.world.entity.Entity e = level.getEntity(id);
            if (e != null) {
               e.discard();
            }
            guardRewardCarry.remove(id);
         }
         exec.guards.clear();
      }
   }

   private static void releaseGuards(ServerLevel level, UUID owner) {
      // The Warden first, and off his own record rather than out of the guard list. He is the one
      // body in this block that is tracked twice - {@code guards} holds the detail and {@code wardens}
      // holds the man - and the two come back off disk separately (see the load), so a Warden restored
      // from the save can be tracked with no guard list under him at all. Reading him out of the list
      // alone is what left a maximum-security Warden patrolling an empty block: the map forgot him,
      // the body did not, and the only thing that could still find him was the orphan sweep - which
      // deliberately spares a guard whose owner is offline with a record.
      Mob man = wardenOf(level, owner);
      if (man != null) {
         man.discard();
      }
      wardens.remove(owner);
      lastWardenSent.remove(owner);
      Set<UUID> set = guards.remove(owner);
      if (set == null) {
         return;
      }
      for (UUID id : set) {
         net.minecraft.world.entity.Entity e = level.getEntity(id);
         if (e != null) {
            e.discard();
         }
         guardRewardCarry.remove(id);
      }
   }

   /** The prisoner a guard belongs to, read off its own tag, or null when it carries none. */
   private static UUID guardOwnerOf(net.minecraft.world.entity.Entity entity) {
      for (String tag : entity.entityTags()) {
         if (tag.startsWith(GUARD_OWNER_TAG)) {
            try {
               return UUID.fromString(tag.substring(GUARD_OWNER_TAG.length()));
            } catch (IllegalArgumentException notAUuid) {
               return null;
            }
         }
      }
      return null;
   }

   /**
    * True while the prisoner is inside a scene that brought its own guards.
    *
    * <p>The clean-record rule in the tick releases the manhunt's patrols as soon as a prisoner's
    * Heat is quiet, and that rule is about the patrols. The men a scripted scene puts on the floor -
    * the five who storm the execution hall, the squad and the Warden who come with a break-out
    * tunnel - are the scene's, and they are not a manhunt to be called off. Sweeping them is what
    * "the Warden spawns and instantly despawns" was, and a break-out needs no Heat at all: the
    * sweep fired on the tick after he arrived, left a locked gate with nobody holding the key, and
    * did it again to every man the block sent after him.
    */
   private static boolean sceneOwnsGuards(UUID uuid) {
      return escapes.containsKey(uuid) || executions.containsKey(uuid) || PrisonManager.pitLive(uuid);
   }

   /**
    * Test hook: whether a scene that brings its own guards owns this prisoner right now.
    *
    * <p>Asked of the rule rather than inferred from a guard surviving a tick, because the tick that
    * sweeps them is level-wide and only ever looks at bodies on the server's own player list.
    */
   public static boolean sceneOwnsGuardsForTest(UUID uuid) {
      return sceneOwnsGuards(uuid);
   }

   /**
    * A guard that has nobody left to guard, put down wherever it is standing.
    *
    * <p>Release alone cannot close this, and the reason is the shape of the state rather than a
    * missing call. The registry that owns the guards lives in memory, and a guard is persistent and
    * carries its owner only as a tag - so a guard outlives its reason in two ways that no release at
    * the moment of the sentence can reach. A restart empties the registry and leaves every guard it
    * was holding standing in the mine forever, and a release that runs while the guard's chunk is
    * unloaded cannot find the body to discard it, so it is forgotten rather than removed. Both end
    * the same way: a cell-block guard patrolling a mine for a prisoner who is behind a door, or
    * offline, or gone.
    *
    * <p>So the guard is asked the one question that survives all of that - whose are you - and put
    * down when the answer has stopped being true. The sweep is deliberately blind to heat and to
    * escapes: it only removes a guard whose owner is <b>absent or confined</b>, which is exactly
    * when no guard should be standing anywhere.
    */
   private static void sweepOrphanGuards(ServerLevel level) {
      net.minecraft.world.phys.AABB everything = new net.minecraft.world.phys.AABB(
         -3.0E7, -128.0, -3.0E7, 3.0E7, 512.0, 3.0E7
      );
      for (net.minecraft.world.entity.Entity e : level.getEntities((net.minecraft.world.entity.Entity)null, everything, ent -> isGuard(ent))) {
         UUID owner = guardOwnerOf(e);
         if (owner == null) {
            // A guard with no owner on it at all is nobody's, and always was.
            e.discard();
            continue;
         }
         ServerPlayer p = level.getServer().getPlayerList().getPlayer(owner);
         if (p != null) {
            // The owner is here. A guard on duty for a prisoner who is still in the block and not
            // behind a door stays; everyone else goes.
            if (PrisonManager.isInPrison(p) && !isConfined(owner)) {
               continue;
            }
         } else if (PrisonManager.hasRecord(owner)) {
            // The owner is offline but still has a prison record - the guard is his, restored from
            // the save with the record, and it waits for him instead of being put down on every
            // restart. That is the whole point of persisting ownership.
            continue;
         }
         e.discard();
         pruneGuard(owner, e.getUUID());
         wardens.remove(owner);
      }
   }

   /** Test hook: one pass of the orphan sweep, so "a guard nobody owns is put down" can be pinned. */
   public static void sweepOrphanGuardsForTest(ServerLevel level) {
      sweepOrphanGuards(level);
   }

   /**
    * Bats, put down the moment they appear.
    *
    * <p>The block is a lit, roofed, enclosed world and none of that matters to a bat: it spawns in
    * the dark pockets behind the walls and the roof, then flaps into the mine and into the cuffs
    * minigame, which is a bug with wings. There is no natural spawn the mine wants, so the sweep is
    * a rule rather than a filter - it catches them wherever they came from (a spawn, an egg, a
    * script) instead of guessing at the one door they used.
    */
   private static void clearBats(ServerLevel level) {
      for (net.minecraft.world.entity.ambient.Bat bat : level.getEntitiesOfClass(
         net.minecraft.world.entity.ambient.Bat.class,
         new net.minecraft.world.phys.AABB(-3.0E7, -128.0, -3.0E7, 3.0E7, 512.0, 3.0E7)
      )) {
         bat.discard();
      }
   }

   /** Test hook: one pass of the bat sweep. */
   public static void clearBatsForTest(ServerLevel level) {
      clearBats(level);
   }

   // ------------------------------------------------------------------
   // Solitary
   // ------------------------------------------------------------------

   /**
    * True while a prisoner is serving a sentence <b>of any kind</b>: the hole or their own cell.
    *
    * <p>This is the umbrella, and it is the one most callers want - "is this body behind a door
    * right now" is a different question from "which door", and the answer to it decides whether
    * mining, escapes, heat and guard captures run at all. The two rooms are separated below.
    */
   public static boolean isConfined(UUID uuid) {
      return sentences.containsKey(uuid);
   }

   /**
    * True only while a prisoner is in the hole - solitary confinement, the punishment.
    *
    * <p>Deliberately NOT true for a sentence served in the prisoner's own cell. The cell is the
    * prison's answer to a death ("you wake up somewhere the block put you, and the room you have
    * been upgrading is where") and the hole is the punishment for losing a struggle. They share the
    * clock and nothing else: the hole blinds and slows, the cell does not; the hole takes the whole
    * bag, the cell takes the haul; the hole is a fixed box, the cell is the room the ladder bought.
    * A predicate that answered for both is what made "in solitary" meaningless in the code, and the
    * reason this is now two questions with two names.
    */
   public static boolean isInSolitary(UUID uuid) {
      Sentence s = sentences.get(uuid);
      return s != null && !s.cell();
   }

   /** True only while the sentence is being served in the prisoner's own cell, never in the hole. */
   public static boolean isInCell(UUID uuid) {
      Sentence s = sentences.get(uuid);
      return s != null && s.cell();
   }

   /** Seconds left on the current sentence, or 0 when not locked up. */
   public static long solitarySecondsLeft(UUID uuid) {
      Sentence s = sentences.get(uuid);
      if (s == null) {
         return 0L;
      }
      return Math.max(0L, (s.until() - lastKnownTick) / 20L);
   }

   /** True while a prisoner is in cuffs and working them off. */
   public static boolean isCuffed(UUID uuid) {
      return cuffs.containsKey(uuid);
   }

   /** True when the cuffs are the Warden's netherite shackle rather than a guard's pair. */
   public static boolean cuffIsHard(UUID uuid) {
      Cuffs struggle = cuffs.get(uuid);
      return struggle != null && struggle.hard;
   }

   /** Guards this prisoner has shaken off. The next one to reach them takes rather than cuffs. */
   public static int breakoutsOf(UUID uuid) {
      return breakouts.getOrDefault(uuid, 0);
   }

   /** Test hook: put a rung of the capture ladder on record without a guard in the room. */
   public static void breakoutsForTest(UUID uuid, int count) {
      breakouts.put(uuid, Math.max(0, count));
   }

   /** Called when a prisoner leaves the dimension: abandon any break-out, cuffs and lock-up. */
   public static void onLeave(ServerPlayer player) {
      UUID uuid = player.getUUID();
      Escape esc = escapes.remove(uuid);
      if (esc != null && player.level() instanceof ServerLevel level) {
         sealTunnel(level, esc);
      }
      sentences.remove(uuid);
      cuffs.remove(uuid);
      breakouts.remove(uuid);
      shakeGrace.remove(uuid);
      lastGuardSpawn.remove(uuid);
      Execution exec = executions.remove(uuid);
      if (player.level() instanceof ServerLevel level) {
         if (exec != null) {
            sealExecution(level, exec);
            discardExecGuards(level, exec);
         }
         restoreLooseBricks(level, uuid);
         releaseGuards(level, uuid);
      }
   }

   /**
    * A prisoner walking back into the block: none of the detail that was hunting them is still here.
    *
    * <p>The other half of {@link #onLeave}, and the half a thrown-out prisoner actually needs. Being
    * carried out of the chair is the one ending that <i>ends</i>: the record is wiped, the door locks
    * for two minutes, and the man who comes back through it is a stranger to the block. What he is
    * not, and what he used to be, is a man with a Warden already walking towards the door.
    *
    * <p>The detail is tracked and discarded rather than merely forgotten, because forgetting a Warden
    * is what left him standing there: his body is put down, his map entry is dropped, the guards the
    * bars called go with him, the wing that was carved for the last sentence is given back, and the
    * prison's loose bricks are put back in the wall they came out of. Nothing about a sentence that is
    * over is allowed to be waiting on the other side of the door for the next one.
    */
   public static void onEnter(ServerPlayer player) {
      if (player == null || !(player.level() instanceof ServerLevel level)) {
         return;
      }
      UUID uuid = player.getUUID();
      Execution exec = executions.remove(uuid);
      if (exec != null) {
         sealExecution(level, exec);
         discardExecGuards(level, exec);
      }
      releaseGuards(level, uuid);
      restoreLooseBricks(level, uuid);
      // No bat in a lit stone box: it is the one mob that spawns in the pockets behind the walls and
      // then flaps through the cuffs minigame. Swept on the way in so a shift starts with none.
      clearBats(level);
      sweepOrphanGuards(level);
   }

   /**
    * The prison's rule in the lethal-blow walk: {@link LethalBlows#PRISON_SOLITARY}.
    *
    * <p>A fatal blow inside the cell block never kills, and never has: the prisoner is dragged to a
    * door instead. Their mined haul is confiscated but the stashed personal inventory is untouched,
    * so nothing a player owns can be lost - and whether the blow was fatal, or a totem was going to
    * save them, is decided once in {@link LethalBlows}.
    *
    * <p>Which door is {@link #routeArrest}'s decision, and it is asked for <b>every</b> fatal blow in
    * the prison, including one that lands on a prisoner already behind a door. The exception that
    * used to be here - a confined prisoner's blow was left to land - is what "when the server lags I
    * just die instead of being sent out" was: a struggling server delivers several ticks of damage in
    * one tick, the first blow of the lump is answered by this rule, and if the answer to a body that
    * is already locked up is "no opinion" then the rest of the lump kills it. There is no blow in this
    * block that ends in a death, so there is no body in it that has to be told apart.
    */
   public static LethalBlows.Answer answerFatalBlow(LethalBlows.Blow blow) {
      ServerPlayer player = blow.player();
      if (player == null || !PrisonManager.isInPrison(player)) {
         return LethalBlows.Answer.INNOCENT;
      }
      UUID uuid = player.getUUID();

      // The chair owns every fatal blow while it is running: the guards the bars called put you
      // back in it, and anything else carries the sentence out. Both are decided in one place so
      // the two endings cannot disagree.
      Execution exec = executions.get(uuid);
      if (exec != null) {
         takeExecutionBlow(player, (ServerLevel)player.level(), exec, blow);
         return LethalBlows.Answer.CLAIM;
      }

      // The Warden's killing blow is an execution, not a cell. He is the block's only executioner,
      // and the distinction is the whole reason the manhunt and this room both exist.
      if (blow.source() != null && isWarden(blow.source().getEntity())) {
         sendToExecution(player);
         return LethalBlows.Answer.CLAIM;
      }

      // A beating ends behind a door, not on a respawn screen, and which door is the block's own
      // ladder: the same Heat the guards are already reacting to decides it.
      routeArrest(player, false);
      return LethalBlows.Answer.CLAIM;
   }

   /**
    * Where the block puts a prisoner it has just put down, by how hot the record is.
    *
    * <p>One rule, and it is the prison's own alert ladder read back at the moment a prisoner has
    * lost a fight: <b>Critical</b> goes to maximum security - the wing the chair is in - because a
    * record that has the Warden on it has stopped being a mine problem; <b>Lockdown</b> goes to the
    * hole, which is what solitary confinement is for; and anything under that goes to the cell they
    * have been paying to improve, which is what a beating has always cost. The tier names and the
    * thresholds are {@link Alert}'s, so the door a prisoner wakes up behind and the tier the menu says
    * they are in cannot disagree.
    *
    * @param capture true when this is a capture rather than a beating: failure at the cuffs costs the
    *                whole bag, not just the ore in it
    */
   public static void routeArrest(ServerPlayer player, boolean capture) {
      if (player == null || !PrisonManager.isInPrison(player)) {
         return;
      }
      Alert tier = alertOf(heatOf(player.getUUID()));
      com.fortuneandfavors.FortuneFavorsMod.LOGGER.info(
         "[FF-DEBUG] routeArrest {} tier={} heat={}", player.getName().getString(), tier, heatOf(player.getUUID())
      );
      if (tier == Alert.CRITICAL) {
         sendToExecution(player);
         return;
      }
      if (tier == Alert.LOCKDOWN) {
         sendToSolitary(player);
         return;
      }
      sendToCell(player, capture);
   }

   /**
    * This rule, asked on its own.
    *
    * @return true to let the damage through, false to cancel it.
    */
   public static boolean onLethalDamage(ServerPlayer player, float amount) {
      return LethalBlows.verdict(LethalBlows.PRISON_SOLITARY, player, null, amount);
   }

   /**
    * Sends a prisoner to the hole for the standard sentence.
    *
    * @param takeEverything true when the sentence is a *capture* - failing to work off a pair of
    *                       cuffs costs the whole bag, not just the ore in it
    */
   public static void sendToSolitary(ServerPlayer player) {
      sendToSolitary(player, SOLITARY_TICKS, true);
   }

   public static void sendToSolitary(ServerPlayer player, long ticks, boolean takeEverything) {
      UUID uuid = player.getUUID();
      ServerLevel level = (ServerLevel)player.level();
      long confiscated = takeEverything ? confiscateEverything(player) : confiscateHaul(player);
      player.closeContainer();
      Escape esc = escapes.remove(uuid);
      if (esc != null) {
         sealTunnel(level, esc);
      }
      Execution exec = executions.remove(uuid);
      if (exec != null) {
         sealExecution(level, exec);
         discardExecGuards(level, exec);
      }
      cuffs.remove(uuid);
      shakeGrace.remove(uuid);
      releaseGuards(level, uuid);
      heat.put(uuid, Math.max(0, heatOf(uuid) - 35));
      // The clock starts now rather than at the next tick's staging pass: the sentence is an absolute
      // instant, so nothing has to remember that it is due and no restart can leave a prisoner
      // standing in the hole with the sentence over.
      sentences.put(uuid, new Sentence(clock(level) + Math.max(1L, ticks), false, Math.max(1L, ticks)));
      player.setHealth(player.getMaxHealth());
      player.getFoodData().setFoodLevel(20);
      player.getFoodData().setSaturation(20.0F);
      applySolitaryEffects(player);
      BlockPos cell = new BlockPos(SOLITARY_X, SOLITARY_Y, SOLITARY_Z);
      player.teleport(
         new TeleportTransition(
            level, new Vec3(cell.getX() + 0.5, cell.getY(), cell.getZ() + 0.5), Vec3.ZERO, 0.0F, 0.0F, TeleportTransition.PLACE_PORTAL_TICKET
         )
      );
      level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.IRON_DOOR_CLOSE, SoundSource.BLOCKS, 1.0F, 0.7F);
      Chat.raw(player, "§4§lSOLITARY CONFINEMENT§r §7- you were put down, not put out.");
      if (confiscated > 0L) {
         Chat.raw(
            player,
            takeEverything
               ? "§7They emptied your pockets on the way in: §c" + Chat.moneyStr(confiscated) + "§7 worth gone."
               : "§7The guards confiscated §c" + Chat.moneyStr(confiscated) + "§7 worth of ore from your pockets."
         );
      }
      Chat.raw(player, "§7" + (ticks / 20L) + " seconds in the hole. Your own gear stays safely stashed.");
   }

   /**
    * A beating ends in the prisoner's own cell.
    *
    * <p>Shorter than the hole, because it is not a punishment - it is the prison's answer to a
    * death: you do not respawn, you wake up somewhere the block put you, and the cell you have been
    * upgrading is where. The haul goes; nothing else does.
    */
   public static void sendToCell(ServerPlayer player) {
      sendToCell(player, false);
   }

   /**
    * The cell, and how much of the bag goes with it.
    *
    * @param takeEverything true when the prisoner is here off a <b>capture</b> - a struggle lost to
    *                       the clock - rather than off a beating, where only the haul is taken
    */
   public static void sendToCell(ServerPlayer player, boolean takeEverything) {
      if (player == null || !PrisonManager.isInPrison(player)) {
         return;
      }
      UUID uuid = player.getUUID();
      ServerLevel level = (ServerLevel)player.level();
      long confiscated = takeEverything ? confiscateEverything(player) : confiscateHaul(player);
      player.closeContainer();
      Escape esc = escapes.remove(uuid);
      if (esc != null) {
         sealTunnel(level, esc);
      }
      Execution exec = executions.remove(uuid);
      if (exec != null) {
         sealExecution(level, exec);
         discardExecGuards(level, exec);
      }
      cuffs.remove(uuid);
      shakeGrace.remove(uuid);
      releaseGuards(level, uuid);
      heat.put(uuid, Math.max(0, heatOf(uuid) - 20));
      sentences.put(uuid, new Sentence(clock(level) + CELL_TICKS, true, CELL_TICKS));
      player.setHealth(player.getMaxHealth());
      player.getFoodData().setFoodLevel(20);
      player.getFoodData().setSaturation(20.0F);
      BlockPos c = PrisonManager.cellSpawnFor(uuid);
      player.teleport(
         new TeleportTransition(
            level, new Vec3(c.getX() + 0.5, c.getY(), c.getZ() + 0.5), Vec3.ZERO, 90.0F, 0.0F, TeleportTransition.PLACE_PORTAL_TICKET
         )
      );
      level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.IRON_DOOR_CLOSE, SoundSource.BLOCKS, 1.0F, 0.7F);
      Chat.raw(player, "§4§lTHEY DRAGGED YOU TO YOUR CELL§r §7- you were put down, not put out.");
      if (confiscated > 0L) {
         Chat.raw(player, "§7Your haul was taken off you in the corridor: §c" + Chat.moneyStr(confiscated) + "§7 gone.");
      }
      Chat.raw(
         player,
         "§7Twelve seconds in your §f" + PrisonManager.cellName(PrisonManager.cellLevelOf(uuid))
            + "§7, then back to the mine. Upgrading it is the only purchase in here that changes this."
      );
   }

   /** Empties the bag outright - the capture sentence. Issued kit is not carried, so it stays on. */
   private static long confiscateEverything(ServerPlayer player) {
      long value = 0L;
      var inv = player.getInventory();
      for (int i = 0; i < inv.getContainerSize(); i++) {
         ItemStack s = inv.getItem(i);
         if (s.isEmpty() || PrisonManager.isPrisonStar(s) || isGuardDropped(s)) {
            continue;
         }
         long v = PrisonManager.sellValueOf(s);
         value += Math.max(v, 1L) * s.getCount();
         inv.setItem(i, ItemStack.EMPTY);
      }
      return value;
   }

   /**
    * The prison's own tools are not loot. A guard who carries off a prisoner's pickaxe has not made
    * a point, they have broken the loop the pickaxe is the door to.
    */
   private static boolean isGuardDropped(ItemStack stack) {
      if (stack == null || stack.isEmpty()) {
         return false;
      }
      CustomData data = (CustomData)stack.get(DataComponents.CUSTOM_DATA);
      if (data == null) {
         return false;
      }
      String tag = data.copyTag().getString("ff").orElse("");
      return "prison_pick".equals(tag) || "prison_sword".equals(tag) || "prison_rod".equals(tag);
   }

   private static long confiscateHaul(ServerPlayer player) {
      long value = 0L;
      var inv = player.getInventory();
      for (int i = 0; i < inv.getContainerSize(); i++) {
         ItemStack s = inv.getItem(i);
         if (s.isEmpty() || PrisonManager.isPrisonStar(s)) {
            continue;
         }
         long v = PrisonManager.sellValueOf(s);
         if (v > 0L) {
            value += v * s.getCount();
            inv.setItem(i, ItemStack.EMPTY);
         }
      }
      return value;
   }

   private static void applySolitaryEffects(ServerPlayer player) {
      player.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 200, 0, false, false, false));
      player.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 200, 3, false, false, false));
      player.addEffect(new MobEffectInstance(MobEffects.MINING_FATIGUE, 200, 2, false, false, false));
   }

   private static void releaseFromSolitary(ServerPlayer player) {
      boolean wasCell = isInCell(player.getUUID());
      sentences.remove(player.getUUID());
      player.removeEffect(MobEffects.BLINDNESS);
      player.removeEffect(MobEffects.SLOWNESS);
      player.removeEffect(MobEffects.MINING_FATIGUE);
      ServerLevel level = (ServerLevel)player.level();
      BlockPos spawn = PrisonManager.freshFloorSpawn(player);
      player.teleport(
         new TeleportTransition(
            level, new Vec3(spawn.getX() + 0.5, spawn.getY() + 1.0, spawn.getZ() + 0.5), Vec3.ZERO, player.getYRot(), player.getXRot(), TeleportTransition.PLACE_PORTAL_TICKET
         )
      );
      Chat.raw(
         player,
         wasCell
            ? "§aYour door opens.§r §7Back to the mine - and your haul was already taken."
            : "§aYou're out of the hole.§r §7Back to the mine - mind the guards."
      );
      level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.IRON_DOOR_OPEN, SoundSource.BLOCKS, 1.0F, 1.1F);
   }

   /**
    * Sentences and struggles are administered from the wall clock, not from stored state, so a
    * server restart can never strand a prisoner behind a door - and, more to the point, the release
    * is a teleport that always runs when the clock passes it, which is what "it does not teleport me
    * back" was: a sentence that had been served with nobody there to end it.
    */
   private static void tickSolitary(ServerLevel level) {
      long now = clock(level);
      for (Map.Entry<UUID, Sentence> e : new ArrayList<>(sentences.entrySet())) {
         ServerPlayer p = level.getServer().getPlayerList().getPlayer(e.getKey());
         if (p == null || !PrisonManager.isInPrison(p)) {
            sentences.remove(e.getKey());
            continue;
         }
         if (now >= e.getValue().until()) {
            releaseFromSolitary(p);
         } else if (now % 20L == 0L && !e.getValue().cell()) {
            // Only the hole reads like a punishment. A cell is just where a beating puts you, and
            // neither one touches the hotbar: being locked up here is an ordinary thing, so the
            // readout lives in the Prison Menu rather than shouted across the screen every second.
            applySolitaryEffects(p);
         }
      }
      // The safety net for a prisoner who is standing in the hole with no sentence to serve - a
      // restart between the teleport and the sentence, or a release that ran while they were out of
      // the dimension. They are put back in the mine rather than left in a box.
      for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
         if (!PrisonManager.isInPrison(p) || sentences.containsKey(p.getUUID())) {
            continue;
         }
         BlockPos at = p.blockPosition();
         if (at.getY() >= SOLITARY_Y - 2 && at.getY() <= SOLITARY_Y + 4
            && at.getX() >= SOLITARY_X - 13 && at.getX() <= SOLITARY_X + 5
            && Math.abs(at.getZ() - SOLITARY_Z) <= 5) {
            releaseFromSolitary(p);
         }
      }
   }

   // ------------------------------------------------------------------
   // Capture: cuffs, the struggle, and what a guard takes
   // ------------------------------------------------------------------

   /**
    * A guard's axe landing on a prisoner.
    *
    * <p>The block used to fight the prisoner and nothing else: a guard was a bag of hit points with
    * an axe, and losing to one cost a wait in a box. This is the thing a guard is actually for. His
    * first landed hit is not a wound, it is an arrest - cuffs go on, the guard stops swinging, and
    * the prisoner has a reaction test in front of them. Work them off and the block has learned they are
    * hard to hold: every guard hit after that takes five stacks out of the bag instead of chaining
    * them, which is what turns the wider mine into a place worth running to and the till into a
    * place worth reaching.
    *
    * <p>Once a prisoner is a known escapee the loop is a chase rather than a drain: a taking hit is
    * always followed by {@link #SHAKEDOWN_GRACE} in which the guard cannot take again, and the row is
    * told out loud - a title for the hit, a status line for the window - so "he is on your bag, run"
    * is something the player is told before it costs them anything else.
    *
    * @return true when the hit was a capture (cuffs or a taking) rather than an ordinary blow
    */
   public static boolean onGuardHit(ServerPlayer player, net.minecraft.world.entity.Entity attacker) {
      if (player == null || !PrisonManager.isInPrison(player)) {
         return false;
      }
      UUID uuid = player.getUUID();
      if (isConfined(uuid)) {
         return false;
      }
      // A break-out is a chase, not a shift: nobody arrests anybody while the tunnel is open. The
      // Warden still hunts - he is the gate's key - but he fights rather than cuffs, so the run is
      // about reaching the door instead of losing a minigame in the middle of it.
      if (escapes.containsKey(uuid)) {
         return false;
      }
      // The Pit is the one fight in the block that is a fight. A guard who walked into the arena to
      // arrest the prisoner they are duelling was the whole "the Warden cuffs you in the arena" bug.
      if (PrisonManager.isInArena(player)) {
         return false;
      }
      if (cuffs.containsKey(uuid)) {
         // Still in the cuffs somebody already put on: give them the locks back rather than a second
         // pair, because the struggle is the only thing standing between them and the hole.
         openCuffMenu(player);
         return true;
      }
      boolean warden = attacker != null && isWarden(attacker);
      // One arrest per man. A guard who has already put cuffs on this prisoner does not carry a
      // second pair; the Warden is the same - he shackles you once, and after that his hands are
      // just hands. That is what lets his killing blow land at all, which is the only way to the
      // chair (see answerFatalBlow). The rank-and-file fall through to the shakedown below.
      boolean arrested = attacker != null && hasCuffed(attacker, uuid);
      if (!arrested && (warden || breakoutsOf(uuid) <= 0)) {
         startCuffs(player, warden, attacker);
         return true;
      }
      if (warden) {
         // The man does not pick pockets: once his shackle has been tried, he simply swings, and a
         // fatal one is the execution rather than a shakedown.
         return false;
      }
      long now = clock((ServerLevel)player.level());
      Long window = shakeGrace.get(uuid);
      if (window != null && now < window) {
         // Still inside the window the last hit bought: the guard grabs and comes away with nothing.
         // The countdown is carried by the status line, so the hit has to answer here rather than
         // quietly extend, or the bag could be held open all day by a guard who never stops swinging.
         player.level().playSound(
            null, player.getX(), player.getY(), player.getZ(), SoundEvents.NOTE_BLOCK_BASS.value(),
            SoundSource.HOSTILE, 0.7F, 0.6F
         );
         return true;
      }
      int taken = takeItems(player, GUARD_TAKE);
      addHeat(player, warden ? 6 : 3);
      if (taken > 0) {
         title(
            player,
            "§4§lSHAKEDOWN",
            "§7-" + taken + " stacks §8| §aRUN §7- he cannot take again for §f" + (SHAKEDOWN_GRACE / 20L) + "s"
         );
         Chat.raw(
            player,
            "§4§l" + (warden ? "THE WARDEN" : "A GUARD") + " TAKES FROM YOU§r §7- " + taken
               + " stack" + (taken == 1 ? "" : "s") + " off the top of your bag. §fSell it, or get off the floor."
         );
      } else {
         title(player, "§6§lHE GRABS FOR YOUR BAG", "§7and comes away with nothing - §fkeep running");
         Chat.raw(player, "§7" + (warden ? "The Warden" : "A guard") + " finds nothing worth taking.");
      }
      openShakedown(player, attacker, now);
      player.level().playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ITEM_PICKUP, SoundSource.HOSTILE, 1.0F, 0.5F);
      return true;
   }

   /**
    * The window that follows a taking: the prisoner faster, the guard behind them slower, and a
    * clock the status line reads out.
    */
   private static void openShakedown(ServerPlayer player, net.minecraft.world.entity.Entity attacker, long now) {
      shakeGrace.put(player.getUUID(), now + SHAKEDOWN_GRACE);
      player.addEffect(new MobEffectInstance(MobEffects.SPEED, (int)SHAKEDOWN_GRACE + 20, 1, false, false, false));
      if (attacker instanceof net.minecraft.world.entity.LivingEntity guard) {
         guard.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, (int)SHAKEDOWN_GRACE, 1, false, false, false));
      }
      player.level().playSound(
         null, player.getX(), player.getY(), player.getZ(), SoundEvents.ARMOR_EQUIP_CHAIN.value(),
         SoundSource.PLAYERS, 1.1F, 1.6F
      );
      ((ServerLevel)player.level()).sendParticles(
         ParticleTypes.CRIT, player.getX(), player.getY() + 1.2, player.getZ(), 16, 0.4, 0.5, 0.4, 0.08
      );
   }

   /** The countdown while a window is open, so a prisoner always knows how much of it is left. */
   private static void tickShakedown(ServerLevel level) {
      if (shakeGrace.isEmpty()) {
         return;
      }
      long now = clock(level);
      for (Map.Entry<UUID, Long> e : new ArrayList<>(shakeGrace.entrySet())) {
         long until = e.getValue();
         if (now >= until) {
            shakeGrace.remove(e.getKey());
            continue;
         }
         ServerPlayer p = level.getServer().getPlayerList().getPlayer(e.getKey());
         if (p == null || !PrisonManager.isInPrison(p)) {
            shakeGrace.remove(e.getKey());
            continue;
         }
         if (now % 20L == 0L) {
            long left = (until - now) / 20L + 1L;
            p.sendSystemMessage(
               Component.literal(
                  "§6§lSHAKEDOWN §8| §7he is on your bag §8| §aRUN §8| §7he cannot take for §f" + left + "s"
               ),
               true
            );
         }
      }
   }

   /** A title and subtitle, for the moments the chat line is not enough. */
   private static void title(ServerPlayer player, String big, String small) {
      try {
         player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket(4, 40, 8));
         player.connection.send(
            new net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket(Component.literal(Chat.colorize(big)))
         );
         player.connection.send(
            new net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket(Component.literal(Chat.colorize(small)))
         );
      } catch (Throwable ignored) {
         // A body with no connection - a test harness, a headless one - has no title to be given, and
         // that is no reason for the hit not to land.
      }
   }

   /** Cuffs go on: the struggle opens, and the clock is the whole of the sentence. */
   private static void startCuffs(ServerPlayer player, boolean warden, net.minecraft.world.entity.Entity attacker) {
      UUID uuid = player.getUUID();
      // Remember, on the body that did it, that this man has had his one arrest. Any later hit from
      // him is a wound (or a shakedown), never another pair of cuffs.
      if (attacker != null) {
         attacker.addTag(CUFFED_TAG + uuid);
      }
      Cuffs struggle = new Cuffs();
      List<Integer> pool = new ArrayList<>(CUFF_LOCKS);
      for (int i = 0; i < CUFF_LOCKS; i++) {
         pool.add(CUFF_FIRST_SLOT + i);
      }
      struggle.hard = warden;
      struggle.clickTicks = warden ? HARD_CUFF_CLICK_TICKS : CUFF_CLICK_TICKS;
      int steps = warden ? HARD_CUFF_STEPS : CUFF_STEPS;
      for (int i = 0; i < steps; i++) {
         struggle.locks.add(pool.remove(RANDOM.nextInt(pool.size())));
      }
      struggle.until = clock((ServerLevel)player.level()) + struggle.clickTicks;
      cuffs.put(uuid, struggle);
      player.level().playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ARMOR_EQUIP_CHAIN.value(), SoundSource.HOSTILE, 1.0F, 0.6F);
      if (warden) {
         player.level()
            .playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ANVIL_LAND, SoundSource.HOSTILE, 0.8F, 0.5F);
         Chat.raw(player, "§4§lTHE NETHERITE SHACKLE§r §7- The Warden clamps a shackle on you. §fBreak it or it is the hole!");
         Chat.raw(
            player,
            "§7This one is not a guard's cuffs: §e" + steps + " locks§7, each lit for only §f"
               + (struggle.clickTicks / 20L) + "s§7, and one wrong click starts the row again. Fail and you get §f"
               + (WARDEN_SOLITARY_TICKS / 20L) + "s§7 in solitary."
         );
         title(player, "§4§lSHACKLED", "§e" + steps + " locks - §f" + (struggle.clickTicks / 20L) + "s each");
      } else {
         Chat.raw(player, "§4§lCUFFED§r §7- A guard has a hold of you. §fBreak the cuffs!");
         Chat.raw(
            player,
            "§7Click the §elit lock§7 within §f" + (struggle.clickTicks / 20L)
               + "s§7, five in a row. He is holding you, not hitting you - fail and it is your cell, and everything you are carrying."
         );
         title(player, "§4§lCUFFED", "§eClick the lit lock - §f" + (struggle.clickTicks / 20L) + "s");
      }
      openCuffMenu(player);
   }

   /**
    * True while a guard has hold of a prisoner: the man is holding them, not hitting them.
    *
    * <p>A struggle with a countdown and an axe landing on the prisoner at the same time is not a
    * minigame, it is a coin flip with a menu over it. The capture is the blow; everything after it
    * is the prisoner's hands against the clock, and the guard's job in that window is to keep
    * holding on. So a blow from a cell-block guard on a cuffed prisoner is cancelled outright.
    */
   public static boolean holdsFire(net.minecraft.world.entity.Entity victim, net.minecraft.world.entity.Entity source) {
      if (!(victim instanceof ServerPlayer prisoner) || !isGuard(source)) {
         return false;
      }
      return cuffs.containsKey(prisoner.getUUID());
   }

   private static void openCuffMenu(ServerPlayer player) {
      try {
         com.fortuneandfavors.menu.CuffMenu.open(player);
      } catch (Throwable ignored) {
         // A body with no connection - a test harness, a headless one - cannot be shown a menu, and
         // that is no reason for the cuffs not to be on. The clock is the sentence either way.
      }
   }

   /** True when this guard has already put its one pair of cuffs on this prisoner. */
   private static boolean hasCuffed(net.minecraft.world.entity.Entity attacker, UUID prisoner) {
      return attacker != null && attacker.entityTags().contains(CUFFED_TAG + prisoner);
   }

   /** Test hook: where the block would put a guard, so "not over the void" can be asked directly. */
   public static BlockPos guardSpotForTest(ServerPlayer player) {
      return safeGuardSpot(player);
   }

   /**
    * Test hook: a guard the block actually owns - spawned through the same door the manhunt uses, so
    * it lands in the {@code guards} registry and a release has something real to release.
    */
   public static Mob spawnGuardForTest(ServerPlayer player) {
      return spawnGuard(player, safeGuardSpot(player));
   }

   /** Test hook: how long the standard sentence is, in ticks. */
   public static long solitaryTicksForTest() {
      return SOLITARY_TICKS;
   }

   /** Test hook: how many stacks one guard hit takes once the prisoner is a known escapee. */
   public static int guardTakeForTest() {
      return GUARD_TAKE;
   }

   /** Test hook: how long the window a taking buys lasts, in ticks. */
   public static long shakedownGraceForTest() {
      return SHAKEDOWN_GRACE;
   }

   /** Test hook: end a prisoner's window early, the way the clock would. */
   public static void clearShakedownForTest(UUID uuid) {
      shakeGrace.remove(uuid);
   }

   /** Test hook: is a window open right now? */
   public static boolean inShakedownForTest(UUID uuid) {
      return shakeGrace.containsKey(uuid);
   }

   /**
    * Seconds left in a prisoner's window to run, or 0 when there is none.
    *
    * <p>Read by the menu, which is the screen a prisoner opens when they are hiding - so the window
    * has to be legible somewhere other than the action bar they were looking at while running.
    */
   public static long shakedownSecondsLeft(UUID uuid) {
      Long until = shakeGrace.get(uuid);
      return until == null ? 0L : Math.max(0L, (until - lastKnownTick) / 20L);
   }

   /** True when this prisoner is on the block's search list at all. */
   public static boolean isSearched(UUID uuid) {
      return breakoutsOf(uuid) > 0;
   }

   /** The locks as the menu renders them, or null when this prisoner is not cuffed. */
   public static List<Integer> cuffLocks(UUID uuid) {
      Cuffs struggle = cuffs.get(uuid);
      return struggle == null ? null : struggle.locks;
   }

   /** Which lock is lit, zero-based, or -1 when not cuffed. */
   public static int cuffStep(UUID uuid) {
      Cuffs struggle = cuffs.get(uuid);
      return struggle == null ? -1 : struggle.step;
   }

   /**
    * What the lock at pane {@code index} of the row is doing.
    *
    * <p>The row is the one place the struggle is legible, so the rule for reading it lives here
    * rather than in the menu: exactly one pane is {@link Lock#LIT} while a struggle is on, the panes
    * already worked in this run are {@link Lock#OPENED}, and the rest are {@link Lock#IDLE}. A
    * wrong click restarts the run, which is what takes a worked pane back to idle - the row shows
    * the slips, and a prisoner can see they just lost their progress rather than being told.
    */
   public static Lock cuffLockState(UUID uuid, int index) {
      Cuffs struggle = cuffs.get(uuid);
      if (struggle == null || index < 0 || index >= CUFF_LOCKS) {
         return Lock.IDLE;
      }
      int slot = CUFF_FIRST_SLOT + index;
      if (struggle.step < struggle.locks.size() && struggle.locks.get(struggle.step) == slot) {
         return Lock.LIT;
      }
      for (int i = 0; i < struggle.step && i < struggle.locks.size(); i++) {
         if (struggle.locks.get(i) == slot) {
            return Lock.OPENED;
         }
      }
      return Lock.IDLE;
   }

   /** Seconds left to work the cuffs off. */
   public static long cuffSecondsLeft(UUID uuid) {
      Cuffs struggle = cuffs.get(uuid);
      return struggle == null ? 0L : Math.max(0L, (struggle.until - lastKnownTick) / 20L);
   }

   /**
    * One click on a lock.
    *
    * @return true when the click was the lit lock. A wrong click is a slip: the series restarts.
    *
    * <p>An opened lock refreshes the deadline and a slip does not: the clock is per lock, so the
    * prisoner who is working the row is paying for the next one with their attention, and the
    * prisoner who is flailing has nothing left to fall back on but the two seconds already running.
    */
   public static boolean cuffStrike(ServerPlayer player, int slot) {
      Cuffs struggle = cuffs.get(player.getUUID());
      if (struggle == null) {
         return false;
      }
      if (struggle.step < struggle.locks.size() && struggle.locks.get(struggle.step) == slot) {
         struggle.step++;
         struggle.until = clock((ServerLevel)player.level()) + struggle.clickTicks;
         player.level().playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.NOTE_BLOCK_HAT.value(), SoundSource.PLAYERS, 0.8F, 1.2F + struggle.step * 0.1F);
         if (struggle.step >= struggle.locks.size()) {
            breakCuffs(player);
         }
         return true;
      }
      struggle.slips++;
      struggle.step = 0;
      player.level().playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.NOTE_BLOCK_BASS.value(), SoundSource.PLAYERS, 0.7F, 0.5F);
      return false;
   }

   /** Test hook: the per-lock window, in ticks. */
   public static long cuffClickTicksForTest() {
      return CUFF_CLICK_TICKS;
   }

   /** Test hook: run a pair of cuffs' clock out, the way missing the click would. */
   public static void expireCuffsForTest(UUID uuid) {
      Cuffs struggle = cuffs.get(uuid);
      if (struggle != null) {
         struggle.until = Long.MIN_VALUE / 2L;
      }
   }

   /** Test hook: put a guard's tag on a body, so the guard rules can be asked with one in the room. */
   public static void markGuardForTest(net.minecraft.world.entity.Entity entity) {
      if (entity != null) {
         entity.addTag(GUARD_TAG);
      }
   }

   /** Test hook: put the Warden's tags on a body, so his shackle can be asked about him. */
   public static void markWardenForTest(net.minecraft.world.entity.Entity entity) {
      if (entity != null) {
         entity.addTag(GUARD_TAG);
         entity.addTag(WARDEN_TAG);
      }
   }

   /** Test hook: how many locks a Warden's shackle holds, and how long each one stays lit. */
   public static int hardCuffStepsForTest() {
      return HARD_CUFF_STEPS;
   }

   public static long hardCuffClickTicksForTest() {
      return HARD_CUFF_CLICK_TICKS;
   }

   /** The cuffs come off: the prisoner is now one the block sends thieves after. */
   public static void breakCuffs(ServerPlayer player) {
      UUID uuid = player.getUUID();
      Cuffs struggle = cuffs.remove(uuid);
      if (struggle == null) {
         return;
      }
      ServerLevel level = (ServerLevel)player.level();
      player.addEffect(new MobEffectInstance(MobEffects.SPEED, 100, 1, false, false, false));
      player.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 100, 0, false, false, false));
      level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ARMOR_EQUIP_CHAIN.value(), SoundSource.PLAYERS, 1.2F, 1.4F);
      level.sendParticles(ParticleTypes.ELECTRIC_SPARK, player.getX(), player.getY() + 1.0, player.getZ(), 24, 0.5, 0.6, 0.5, 0.05);
      player.closeContainer();
      Chat.raw(player, "§a§lYOU ARE OUT OF THE CUFFS§r §7- " + struggle.slips + " slip" + (struggle.slips == 1 ? "" : "s") + " on the way.");
      enterSecondPhase(player);
   }

   /**
    * On the record as somebody the block has to search, and - the first time only - told so.
    *
    * <p>Being promoted from "arrest" to "search" quietly is the worst version of this rule: the
    * prisoner's bag starts emptying on contact and nothing said the rules had changed. The block
    * announces it once, says exactly what changes and exactly what they now get in exchange, and
    * never repeats itself.
    */
   private static void enterSecondPhase(ServerPlayer player) {
      UUID uuid = player.getUUID();
      int was = breakoutsOf(uuid);
      breakouts.put(uuid, was + 1);
      if (was > 0) {
         return;
      }
      title(player, "§c§lTHE BLOCK HAS YOUR NUMBER", "§7guards take §f" + GUARD_TAKE + " stacks§7 a hit now - §esell it, or run");
      Chat.raw(player, "§c§lSECOND PHASE§r §7- the guards stop arresting you and start searching you: §fevery hit costs " + GUARD_TAKE + " stacks§7 off the top of your bag.");
      Chat.raw(player, "§7You are not helpless, though - after each one he is slow and you are fast for §f" + (SHAKEDOWN_GRACE / 20L) + "s§7, and he cannot take a thing inside that window. §aRun, hide, or reach the till.");
      player.level().playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.WITHER_SPAWN, SoundSource.HOSTILE, 0.6F, 1.4F);
   }

   /**
    * The struggle is lost: the cell, and everything carried.
    *
    * <p>Not the hole. A capture that ran off a guard's axe is a beating and belongs in the room the
    * prisoner has been paying to improve; this one is a search that went to plan, so it ends at a
    * cell door with the bag emptied - the same sentence the block hands out to a prisoner it put
    * down, which is the point: the two ways to lose in the block end in the same place.
    */
   private static void failCuffs(ServerPlayer player) {
      Cuffs struggle = cuffs.remove(player.getUUID());
      if (struggle == null) {
         return;
      }
      UUID uuid = player.getUUID();
      ServerLevel level = (ServerLevel)player.level();
      // Losing a struggle no longer walks the prisoner anywhere. The block only takes a body off the
      // floor for a blow that would kill (see answerFatalBlow): failing the cuffs now costs the bag,
      // a moment on the back foot and some Heat, and then the guard is just a man with an axe again.
      // "The guards instantly send you to a cell" was this teleport, and it is gone.
      long confiscated = confiscateEverything(player);
      heat.put(uuid, Math.min(HEAT_MAX, heatOf(uuid) + (struggle.hard ? 10 : 5)));
      player.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 60, 1, false, false, false));
      level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ANVIL_LAND, SoundSource.HOSTILE, 0.9F, 0.6F);
      level.sendParticles(ParticleTypes.CRIT, player.getX(), player.getY() + 1.0, player.getZ(), 20, 0.4, 0.5, 0.4, 0.05);
      title(player, struggle.hard ? "§4§lTHE SHACKLE HOLDS" : "§4§lTHE CUFFS HOLD", "§7they take what you carry - §fgo");
      Chat.raw(
         player,
         struggle.hard
            ? "§4§lTHE SHACKLE HOLDS§r §7- The Warden empties your pockets, but he has not put you down: §frun, or put him down first."
            : "§4§lTHE CUFFS HOLD§r §7- they empty your pockets and let go: §fbreak away - only a killing blow puts you in a cell."
      );
      if (confiscated > 0L) {
         Chat.raw(player, "§7Taken on the way down: §c" + Chat.moneyStr(confiscated) + "§7 worth.");
      }
      // Arrested on a hot record, the block does not stop at the bag. Lockdown puts the prisoner in
      // the hole and Critical puts them in the wing the chair is in - the same ladder a guard's
      // killing blow answers to, so the two ways of losing to a cell-block guard end in the same
      // place for the same reason. Below Lockdown, nothing happens beyond the emptying: an ordinary
      // shakedown is not an arrest, and sending a prisoner to a cell for losing one was a bug.
      Alert tier = alertOf(heatOf(uuid));
      if (tier == Alert.LOCKDOWN || tier == Alert.CRITICAL) {
         routeArrest(player, true);
      }
   }

   /** One trip through the cuff clock, exactly as the prison tick would call it. */
   private static void tickCuffs(ServerLevel level) {
      if (cuffs.isEmpty()) {
         return;
      }
      long now = clock(level);
      for (Map.Entry<UUID, Cuffs> e : new ArrayList<>(cuffs.entrySet())) {
         ServerPlayer p = level.getServer().getPlayerList().getPlayer(e.getKey());
         if (p == null || !PrisonManager.isInPrison(p)) {
            cuffs.remove(e.getKey());
            continue;
         }
         Cuffs struggle = e.getValue();
         if (now >= struggle.until) {
            failCuffs(p);
            continue;
         }
         // Every half second, because the window is two: a countdown that only ticks once inside a
         // two-second deadline is a countdown the prisoner never sees move.
         if (now % 10L == 0L) {
            // Closing the board is closing your eyes, not ending the struggle: the locks go back
            // in front of the prisoner while the cuffs are still on, so the only way out is to
            // work them.
            if (!(p.containerMenu instanceof com.fortuneandfavors.menu.CuffMenu)) {
               openCuffMenu(p);
            }
            long left = Math.max(1L, (struggle.until - now) / 20L + 1L);
            p.sendSystemMessage(
               Component.literal(
                  "§4§lCUFFED §8| §e▶ CLICK THE §e§lLIT§e LOCK §8| §7lock §f"
                     + (struggle.step + 1) + "§7/§f" + struggle.locks.size()
                     + " §8| §c" + left + "s"
               ),
               true
            );
         }
      }
   }

   /** Test hook: one trip through the cuff clock. */
   public static void tickCuffsForTest(ServerLevel level) {
      tickCuffs(level);
   }

   /**
    * Test hook: the cuff clock as it applies to one body.
    *
    * <p>The level-wide trip skips anybody who is not on the server's player list, which is every
    * body a test harness can hold, so the harness asks the clock about its prisoner directly - the
    * same predicate and the same ending, on a body it can watch.
    */
   public static void tickCuffsForTest(ServerPlayer player) {
      Cuffs struggle = cuffs.get(player.getUUID());
      if (struggle == null) {
         return;
      }
      if (clock((ServerLevel)player.level()) >= struggle.until) {
         failCuffs(player);
      }
   }

   /**
    * Five stacks out of the top of a prisoner's bag - the biggest it holds first, the same way a
    * search empties a bag, so a guard takes what the running was for.
    *
    * <p>The issued kit is never taken: a guard who carries off the pickaxe has not made a point,
    * he has shut the loop the pickaxe is the only door to.
    */
   private static int takeItems(ServerPlayer player, int count) {
      var inv = player.getInventory();
      List<int[]> order = new ArrayList<>();
      for (int i = 0; i < inv.getContainerSize(); i++) {
         ItemStack s = inv.getItem(i);
         if (s.isEmpty() || PrisonManager.isPrisonStar(s) || isGuardDropped(s)) {
            continue;
         }
         order.add(new int[]{i, (int)Math.min(Integer.MAX_VALUE, Math.max(1L, PrisonManager.sellValueOf(s)) * s.getCount())});
      }
      order.sort((a, b) -> Integer.compare(b[1], a[1]));
      int taken = 0;
      for (int[] entry : order) {
         if (taken >= count) {
            break;
         }
         inv.setItem(entry[0], ItemStack.EMPTY);
         taken++;
      }
      if (taken > 0) {
         // Test hook parity for the menu: the same number the chat line quotes.
         lastTaken = taken;
      }
      return taken;
   }

   /** How many stacks the last guard hit took, for the self-test. */
   private static int lastTaken = 0;

   public static int lastTakenForTest() {
      return lastTaken;
   }

   /** Empties the bag by brute force for the self-test, and reports what was in it. */
   public static long confiscateForTest(ServerPlayer player, boolean everything) {
      return everything ? confiscateEverything(player) : confiscateHaul(player);
   }

   // ------------------------------------------------------------------
   // Contracts
   // ------------------------------------------------------------------

   public enum Kind {
      MINE("Mine Ore", "§e"),
      SLAY("Slay Inmates", "§c"),
      GUARDS("Take Down Guards", "§4"),
      SELL("Sell Haul", "§6"),
      ESCAPE("Escape the Block", "§a");

      public final String label;
      public final String colour;

      Kind(String label, String colour) {
         this.label = label;
         this.colour = colour;
      }
   }

   public static final class Contract {
      public final Kind kind;
      public final int target;
      public final long reward;
      public int progress;

      Contract(Kind kind, int target, long reward) {
         this.kind = kind;
         this.target = target;
         this.reward = reward;
      }

      public boolean done() {
         return progress >= target;
      }

      public String progressText() {
         return Math.min(progress, target) + "§7/§f" + target;
      }
   }

   public static List<Contract> contractsOf(UUID uuid) {
      return contracts.computeIfAbsent(uuid, PrisonCellblock::rollContracts);
   }

   private static List<Contract> rollContracts(UUID uuid) {
      int rank = PrisonManager.rankOf(uuid);
      List<Contract> list = new ArrayList<>(3);
      Kind[] pool = RANDOM.nextInt(4) == 0
         ? new Kind[]{Kind.MINE, Kind.SLAY, Kind.SELL}
         : new Kind[]{Kind.MINE, Kind.SLAY, Kind.GUARDS};
      for (Kind kind : pool) {
         list.add(newContract(kind, rank));
      }
      return list;
   }

   private static Contract newContract(Kind kind, int rank) {
      return switch (kind) {
         case MINE -> new Contract(kind, 24 + 3 * rank, 250L + 60L * rank);
         case SLAY -> new Contract(kind, 8 + rank / 2, 300L + 80L * rank);
         case GUARDS -> new Contract(kind, 3 + rank / 6, 900L + 150L * rank);
         case SELL -> new Contract(kind, 1500 + 300 * rank, 400L + 90L * rank);
         case ESCAPE -> new Contract(kind, 1, 2500L + 400L * rank);
      };
   }

   public static long rerollCost(UUID uuid) {
      return 500L + 100L * PrisonManager.rankOf(uuid);
   }

   public static String reroll(ServerPlayer player) {
      UUID uuid = player.getUUID();
      long cost = rerollCost(uuid);
      long bal = PrisonManager.balanceOf(uuid);
      if (bal < cost) {
         return "A fresh contract sheet costs " + Chat.moneyStr(cost) + " prison cash - you have " + Chat.moneyStr(bal) + ".";
      }
      PrisonManager.addCash(player, -cost);
      contracts.put(uuid, rollContracts(uuid));
      Chat.raw(player, "§7New contracts pinned to the noticeboard.");
      return null;
   }

   /** Claims a completed contract, replacing it with a fresh one. */
   public static String claimContract(ServerPlayer player, int index) {
      UUID uuid = player.getUUID();
      List<Contract> list = contractsOf(uuid);
      if (index < 0 || index >= list.size()) {
         return "No contract there.";
      }
      Contract c = list.get(index);
      if (!c.done()) {
         return "That contract isn't finished yet (" + c.progressText() + ").";
      }
      PrisonManager.addCash(player, c.reward);
      Chat.raw(
         player,
         "§a§lCONTRACT COMPLETE§r §7- " + c.kind.colour + c.kind.label + "§7. +§e" + Chat.moneyStr(c.reward) + "§7 prison cash."
      );
      list.set(index, newContract(c.kind == Kind.ESCAPE ? Kind.GUARDS : c.kind, PrisonManager.rankOf(uuid)));
      return null;
   }

   private static void progress(ServerPlayer player, Kind kind, int amount) {
      for (Contract c : contractsOf(player.getUUID())) {
         if (c.kind == kind && !c.done()) {
            c.progress += amount;
            if (c.done()) {
               Chat.raw(player, "§a✔ Contract ready:§r " + c.kind.colour + c.kind.label + " §7(" + Chat.moneyStr(c.reward) + "). §8Prison Menu to claim.");
               com.fortuneandfavors.util.SoundUtil.play(player, com.fortuneandfavors.ModSounds.LOOT_TICK);
            }
         }
      }
   }

   /** Selling launders Heat and feeds the Sell contract. */
   public static void onSold(ServerPlayer player, long amount) {
      if (amount <= 0L) {
         return;
      }
      UUID uuid = player.getUUID();
      int before = heatOf(uuid);
      if (before > 0) {
         int after = Math.max(0, before - Math.max(8, before / 5));
         heat.put(uuid, after);
         if (before >= WANTED && after < WANTED) {
            Chat.raw(player, "§aThe heat is off.§r §7Your record is clean - for now.");
         }
      }
      progress(player, Kind.SELL, (int)Math.min(Integer.MAX_VALUE, amount));
   }

   public static void onMobKilled(ServerPlayer player) {
      progress(player, Kind.SLAY, 1);
   }

   // ------------------------------------------------------------------
   // Tick
   // ------------------------------------------------------------------

   public static void tick(MinecraftServer server) {
      ServerLevel level = server.getLevel(PrisonManager.PRISON_DIM);
      if (level == null) {
         return;
      }
      lastKnownTick = clock(level);
      // Once a second, the guards nobody needs any more. Cheap because the prison level holds a
      // handful of entities, and it is the only thing that can reach a guard the registry has
      // already forgotten - see sweepOrphanGuards.
      // The tick these gates count is the *server's*, not the block's: the block's clock carries a
      // base that need not be a multiple of twenty, and "once a second" has to stay once a second.
      if (server.getTickCount() % 20L == 0L) {
         sweepOrphanGuards(level);
         clearBats(level);
      }
      tickSolitary(level);
      tickHeatTools(level);
      tickCuffs(level);
      tickShakedown(level);
      tickEscapes(level);
      tickExecutions(level);
      long now = clock(level);
      for (ServerPlayer p : server.getPlayerList().getPlayers()) {
         if (!PrisonManager.isInPrison(p)) {
            continue;
         }
         UUID uuid = p.getUUID();
         if (isConfined(uuid)) {
            continue;
         }
         // Heat cools off on its own, a point per 1.5 seconds.
         // The rate is the prisoner's own, off the cell ladder: a better cell is somewhere to lie
         // low, and the whole reason to buy one. See PrisonManager.cellCoolingTicks.
         if (now % PrisonManager.cellCoolingTicks(uuid) == 0L) {
            int h = heatOf(uuid);
            if (h > 0) {
               heat.put(uuid, h - 1);
            }
         }
         int h = heatOf(uuid);
         // A clean record makes the guards lose interest entirely - but not in the middle of a break
         // out or a sentence, where the men on the floor belong to the scene rather than to the
         // manhunt. See sceneOwnsGuards.
         if (h < 20 && !sceneOwnsGuards(uuid)) {
            releaseGuards(level, uuid);
            lastGuardSpawn.remove(uuid);
         } else if (h >= WANTED) {
            int cap = h >= MANHUNT ? 4 : 2;
            Set<UUID> live = guards.get(uuid);
            int alive = 0;
            if (live != null) {
               for (UUID id : new HashSet<>(live)) {
                  net.minecraft.world.entity.Entity e = level.getEntity(id);
                  if (e == null || !e.isAlive()) {
                     live.remove(id);
                     guardRewardCarry.remove(id);
                  } else {
                     alive++;
                  }
               }
            }
            // The block's clock: a Guard Shift halves the gap between patrols, so the same Heat is
            // a worse problem while it runs. The interval is still the same arithmetic - only the
            // multiplier is new - so nothing else about how guards arrive has moved.
            long interval = (long)((h >= MANHUNT ? 160L : 240L) * PrisonEvents.guardIntervalMult(now));
            Long last = lastGuardSpawn.get(uuid);
            if (alive < cap && (last == null || now - last >= interval)) {
               lastGuardSpawn.put(uuid, now);
               BlockPos at = safeGuardSpot(p);
               if (spawnGuard(p, at) != null) {
                  Chat.raw(p, "§cA cell block guard is closing in on you!");
               }
            }

            // Full Heat sends the man himself, and only ever one of him: while a Warden is
            // breathing there is nothing left to escalate to, and once he is gone the block waits
            // before sending another, so a prisoner who runs from the fight cannot farm the same
            // one for the same pile.
            if (h >= MANHUNT) {
               UUID wardenId = wardens.get(uuid);
               net.minecraft.world.entity.Entity aliveWarden = wardenId == null ? null : level.getEntity(wardenId);
               if (aliveWarden == null || !aliveWarden.isAlive()) {
                  wardens.remove(uuid);
                  long sent = lastWardenSent.getOrDefault(uuid, Long.MIN_VALUE / 2L);
                  // A Warden Purge halves his cooldown: the same rule as always, from the clock.
                  long cooldown = (long)(WARDEN_COOLDOWN * PrisonEvents.wardenCooldownMult(now));
                  if (now - sent >= cooldown) {
                     // Only on a man who actually landed: a refused spawn is retried next tick rather
                     // than bought with a full cooldown.
                     if (spawnWarden(p) != null) {
                        lastWardenSent.put(uuid, now);
                     }
                  }
               }
            }
         }
      }

      // The lock-out, read to the prisoner it applies to. A refusal at the door is a rule, but a
      // rule nobody can see the clock on is a bug from the outside - so the block says how long it
      // will be closed. Only outside prison: inside, there is no cooldown to serve.
      if (server.getTickCount() % 20L == 0L) {
         for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (PrisonManager.isInPrison(p)) {
               continue;
            }
            long left = cooldownLeft(p.getUUID());
            if (left > 0L) {
               p.sendSystemMessage(
                  Component.literal("§4§lLOCKED OUT §8| §7the block takes you back in §f" + ((left + 19L) / 20L) + "s"),
                  true
               );
            }
         }
      }
   }

   /**
    * Where a guard is put down: open air, two blocks of headroom, and something solid under it.
    *
    * <p>The old version asked two questions - "is this air" and "is the block above it air" - and
    * nothing else. In a void world that is a question air answers yes to over the edge of every
    * floor, so a good share of the guards the block sent were sent into the dark, fell out of the
    * world, and left the prisoner with a chat line about a guard closing in and nobody to show for
    * it. A spawn needs a floor under it; that is the whole of the fix, and it is why the search now
    * walks down from the player's own eye level looking for one rather than trusting the first
    * empty square it finds.
    */
   private static BlockPos safeGuardSpot(ServerPlayer player) {
      ServerLevel level = (ServerLevel)player.level();
      int eyes = player.blockPosition().getY();
      for (int tries = 0; tries < 24; tries++) {
         double a = RANDOM.nextDouble() * Math.PI * 2.0;
         double r = 5.0 + RANDOM.nextDouble() * 8.0;
         int x = (int)Math.round(player.getX() + Math.cos(a) * r);
         int z = (int)Math.round(player.getZ() + Math.sin(a) * r);
         for (int dy = 2; dy >= -3; dy--) {
            BlockPos p = new BlockPos(x, eyes + dy, z);
            if (isStandable(level, p)) {
               return p;
            }
         }
      }
      // Nothing open in a ring around them: put him on the prisoner's own floor, a few paces away,
      // which is always somewhere he can stand because the prisoner is standing there.
      BlockPos here = player.blockPosition();
      for (int d = 2; d <= 8; d++) {
         for (int[] off : new int[][]{{d, 0}, {-d, 0}, {0, d}, {0, -d}, {d, d}, {-d, -d}, {d, -d}, {-d, d}}) {
            for (int dy = 1; dy >= -2; dy--) {
               BlockPos q = here.offset(off[0], dy, off[1]);
               if (isStandable(level, q)) {
                  return q;
               }
            }
         }
      }
      return here;
   }

   /** Air with air over it and a collision shape under it - a square a body can actually occupy. */
   private static boolean isStandable(ServerLevel level, BlockPos p) {
      if (!level.getBlockState(p).getCollisionShape(level, p).isEmpty()) {
         return false;
      }
      if (!level.getBlockState(p.above()).getCollisionShape(level, p.above()).isEmpty()) {
         return false;
      }
      BlockPos below = p.below();
      return !level.getBlockState(below).getCollisionShape(level, below).isEmpty();
   }

   // ------------------------------------------------------------------
   // Execution
   // ------------------------------------------------------------------

   /** True while a prisoner is sitting in the chair. */
   public static boolean isInExecution(UUID uuid) {
      return executions.containsKey(uuid);
   }

   /** Seconds left before the sentence is carried out, or 0. */
   public static long executionSecondsLeft(UUID uuid) {
      Execution exec = executions.get(uuid);
      return exec == null ? 0L : Math.max(0L, (exec.endTick - lastKnownTick) / 20L);
   }

   /**
    * True while a position is the chair, the vent or the room's own walls.
    *
    * <p>The walls are protected for the same reason the hole's are: an execution cell a prisoner
    * can dig through is no sentence at all. Two things are deliberately exempt, and both are ways
    * out rather than ways through - the bars of the cell gate, and the gravel the duct mouth is
    * buried under.
    */
   public static boolean isExecutionStructure(BlockPos pos) {
      if (pos == null) {
         return false;
      }
      for (Execution exec : executions.values()) {
         if (isExecutionVentGravel(pos, exec)) {
            return false;
         }
         if (pos.equals(exec.chair) || pos.equals(exec.vent)) {
            return true;
         }
         if (insideWing(exec, pos)) {
            return !exec.allBars.contains(pos);
         }
      }
      return false;
   }

   /** True when a position is the gravel over a live execution's vent, which is the dig to work. */
   public static boolean isExecutionVentGravel(BlockPos pos) {
      if (pos == null) {
         return false;
      }
      for (Execution exec : executions.values()) {
         if (isExecutionVentGravel(pos, exec)) {
            return true;
         }
      }
      return false;
   }

   /**
    * The vent's own ground: the gravel over the duct, and the duct's walk level under it, where a
    * block knocked out of the patch lands.
    *
    * <p>Both halves are the vent, and that is not a detail: gravel falls, so a patch laid over an
    * open hole loses a block a level down the first time the prisoner breaks the one beside it -
    * and a rule that knew only the four recorded positions both refused the fallen block (it is in
    * the wing, and the wing is the block's own rock) and left the dig stuck at three of four. See
    * the dig in {@link #onBlockMined}.
    */
   private static boolean isExecutionVentGravel(BlockPos pos, Execution exec) {
      return exec.ventGround.contains(pos);
   }

   /**
    * The Warden's killing blow is an execution, not a cell.
    *
    * <p>The Warden is the one thing in the block that kills on purpose. Where a guard putting a
    * prisoner down ends in the room they have been upgrading, the man himself ends at the chair -
    * which is the difference the whole Heat ladder was climbing towards.
    */
   public static void sendToExecution(ServerPlayer player) {
      if (player == null || !PrisonManager.isInPrison(player)) {
         return;
      }
      UUID uuid = player.getUUID();
      ServerLevel level = (ServerLevel)player.level();
      if (executions.containsKey(uuid)) {
         return;
      }
      Escape esc = escapes.remove(uuid);
      if (esc != null) {
         sealTunnel(level, esc);
      }
      cuffs.remove(uuid);
      sentences.remove(uuid);
      shakeGrace.remove(uuid);
      releaseGuards(level, uuid);
      com.fortuneandfavors.FortuneFavorsMod.LOGGER.info("[FF-DEBUG] sendToExecution {}", player.getName().getString());
      Execution exec = new Execution(
         // The chair: on the dais in the chamber, straight across the hall from the gate.
         new BlockPos(EXEC_X + 5, EXEC_Y + 1, EXEC_Z),
         // The duct mouth: buried under gravel in the cell floor, over a duct that runs north.
         new BlockPos(EXEC_X - 6, EXEC_Y - 1, EXEC_Z + 1)
      );
      buildExecutionBlock(level, exec);
      long now = clock(level);
      exec.endTick = now + EXECUTION_TICKS;
      exec.phase = Execution.Phase.CELL;
      executions.put(uuid, exec);
      player.setHealth(player.getMaxHealth());
      player.getFoodData().setFoodLevel(20);
      player.getFoodData().setSaturation(20.0F);
      BlockPos start = exec.start;
      player.teleport(new TeleportTransition(
         level, new Vec3(start.getX() + 0.5, start.getY(), start.getZ() + 0.5), Vec3.ZERO, 0.0F, 0.0F, TeleportTransition.PLACE_PORTAL_TICKET
      ));
      level.playSound(null, start.getX() + 0.5, start.getY(), start.getZ() + 0.5, SoundEvents.IRON_DOOR_CLOSE, SoundSource.BLOCKS, 1.0F, 0.6F);
      Chat.raw(
         player,
         "§4§lMAXIMUM SECURITY§r §7- they lock you in the cell. Across the hall is the chair; you have §f"
            + (EXECUTION_TICKS / 20L) + "s§7 to get out, or it takes you."
      );
      Chat.raw(player, "§7Two ways out. The §fbars§7 of the cell gate - two gone brings the cell block down the hall - or the §fvent§7: dig the gravel out of the cell floor and get into the duct §fbefore the man they send finds you§7.");
      title(player, "§4§lMAXIMUM SECURITY", "§760 seconds - the gate, or the gravel on the floor");
   }

   /** The chair holds you: slow, weak and blind, because now there is nothing left to work. */
   private static void bindToChair(ServerPlayer player) {
      player.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, (int)CHAIR_TICKS + 60, 4, false, false, false));
      player.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, (int)CHAIR_TICKS + 60, 1, false, false, false));
      player.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 80, 0, false, false, false));
   }

   /**
    * The chair, once the escape clock is gone: seat the prisoner and start the charges.
    *
    * <p>This is the second half of the sentence and the only place it kills. Every charge is loud
    * and visible - a red flash, a sound, a real bite out of the bar, each one bigger than the last -
    * so the death is a thing the prisoner watches rather than an instant they read about afterwards.
    */
   private static void toChair(ServerPlayer player, ServerLevel level, Execution exec, String why) {
      if (exec.phase == Execution.Phase.CHAIR) {
         return;
      }
      exec.phase = Execution.Phase.CHAIR;
      // The men the bars called are done with their part: whoever is in the chair is the chair's.
      discardExecGuards(level, exec);
      discardSentry(level, exec);
      long now = clock(level);
      exec.endTick = now + CHAIR_TICKS;
      exec.nextShockTick = now + EXECUTION_SHOCK_TICKS;
      exec.shocks = 0;
      player.setHealth(player.getMaxHealth());
      // Empty the belly, so the charges are the only thing moving the health bar: a full one would
      // out-regenerate a charge and the chair would never finish, which is exactly the bug this half
      // of the sentence exists to fix.
      player.getFoodData().setFoodLevel(0);
      player.getFoodData().setSaturation(0.0F);
      bindToChair(player);
      BlockPos seat = exec.chair;
      player.teleport(new TeleportTransition(
         level, new Vec3(seat.getX() + 0.5, seat.getY(), seat.getZ() + 0.5), Vec3.ZERO, 90.0F, 0.0F, TeleportTransition.PLACE_PORTAL_TICKET
      ));
      level.playSound(null, seat.getX() + 0.5, seat.getY(), seat.getZ() + 0.5, SoundEvents.WARDEN_ROAR, SoundSource.HOSTILE, 0.9F, 1.5F);
      Chat.raw(player, why);
      title(player, "§4§lTHE CHAIR", "§7two hearts a charge - the block is done waiting");
   }

   /**
    * Raises the maximum-security wing, remembering every block it replaces so it can be given back.
    *
    * <p>Built in three passes, because the shape it used to be - a set of rooms each laying its own
    * floor, roof and walls and hoping they met - is how a wing ends up with a hole in it. First the
    * shell: a floor slab, a roof slab and four outer walls across the whole footprint, so the outside
    * of the block is one building rather than a set of rooms that agree with each other. Then the
    * rooms, hollowed out of that shell. Then {@link #solidifyWing}, which fills whatever the carving
    * left behind with stone - so "is there a hole in it" is answered by the geometry and not by the
    * arithmetic of the loops above it.
    *
    * <p>The shape is the one the prisoner reads from their own bunk: the cell they wake up in, the
    * barred gate that is the way out the block calls "bars", a hall beyond it to run down, and -
    * straight across that hall, up a step and beside the executioner - the chair.
    */
   private static void buildExecutionBlock(ServerLevel level, Execution exec) {
      int floorY = EXEC_Y - 1;
      int roofY = EXEC_Y + EXEC_HEIGHT;
      int topY = EXEC_Y + EXEC_HEIGHT - 1;
      int hallWest = EXEC_X - EXEC_HALL_HALF_X - 1;
      int hallEast = EXEC_X + EXEC_HALL_HALF_X + 1;
      int hallNorth = EXEC_Z - EXEC_HALL_NORTH;
      int hallSouth = EXEC_Z + EXEC_HALL_SOUTH;
      int cellWest = EXEC_X - EXEC_CELL_WEST;
      int cellEast = EXEC_X - EXEC_CELL_EAST;
      int cellNorth = EXEC_Z - EXEC_CELL_Z;
      int cellSouth = EXEC_Z + EXEC_CELL_Z;
      int alcoveEast = EXEC_X + EXEC_ALCOVE_EAST;

      // ---- Pass one: the shell. Floor, roof and the four outer walls over the whole footprint.
      for (int x = EXEC_MIN_X; x <= EXEC_MAX_X; x++) {
         for (int z = EXEC_MIN_Z; z <= EXEC_MAX_Z; z++) {
            carveExec(exec, level, new BlockPos(x, floorY, z), Blocks.DEEPSLATE_TILES.defaultBlockState());
            carveExec(exec, level, new BlockPos(x, roofY, z), Blocks.POLISHED_DEEPSLATE.defaultBlockState());
         }
      }
      for (int y = floorY; y <= roofY; y++) {
         for (int z = EXEC_MIN_Z; z <= EXEC_MAX_Z; z++) {
            carveExec(exec, level, new BlockPos(EXEC_MIN_X, y, z), Blocks.DEEPSLATE_BRICKS.defaultBlockState());
            carveExec(exec, level, new BlockPos(EXEC_MAX_X, y, z), Blocks.DEEPSLATE_BRICKS.defaultBlockState());
         }
         for (int x = EXEC_MIN_X; x <= EXEC_MAX_X; x++) {
            carveExec(exec, level, new BlockPos(x, y, EXEC_MIN_Z), Blocks.DEEPSLATE_BRICKS.defaultBlockState());
            carveExec(exec, level, new BlockPos(x, y, EXEC_MAX_Z), Blocks.DEEPSLATE_BRICKS.defaultBlockState());
         }
      }

      // ---- Pass two: the rooms, hollowed out of the shell.
      // The hall: five wide and roofed - the run out of the cell, from the gate to the far wall.
      for (int x = hallWest + 1; x <= hallEast - 1; x++) {
         for (int z = hallNorth; z <= hallSouth; z++) {
            for (int y = EXEC_Y; y <= topY; y++) {
               carveExec(exec, level, new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
            }
         }
      }
      // The cell: four wide by five deep, off the hall's west wall.
      for (int x = cellWest; x <= cellEast; x++) {
         for (int z = cellNorth; z <= cellSouth; z++) {
            for (int y = EXEC_Y; y <= topY; y++) {
               carveExec(exec, level, new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
            }
         }
      }
      // The chair chamber: straight across the hall, a room of its own, so the chair is the one
      // thing a prisoner looking through the bars can see.
      for (int x = hallEast + 1; x <= alcoveEast; x++) {
         for (int z = cellNorth; z <= cellSouth; z++) {
            for (int y = EXEC_Y; y <= topY; y++) {
               carveExec(exec, level, new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
            }
         }
      }
      // The doorway between the hall and the chamber, in the chamber's middle.
      for (int y = EXEC_Y; y <= topY; y++) {
         carveExec(exec, level, new BlockPos(hallEast, y, EXEC_Z), Blocks.AIR.defaultBlockState());
      }

      // ---- Pass three: the walls the rooms are cut off from each other by.
      // The hall's west wall runs its whole length; the cell's gate is the hole in it.
      for (int z = EXEC_MIN_Z + 1; z <= EXEC_MAX_Z - 1; z++) {
         boolean gate = z >= EXEC_Z - 1 && z <= EXEC_Z + 1;
         if (gate) {
            continue;
         }
         for (int y = floorY; y <= roofY; y++) {
            carveExec(exec, level, new BlockPos(hallWest, y, z), Blocks.DEEPSLATE_BRICKS.defaultBlockState());
         }
      }
      // The hall's east wall the same, with the doorway to the chamber in the middle of it.
      for (int z = EXEC_MIN_Z + 1; z <= EXEC_MAX_Z - 1; z++) {
         if (z == EXEC_Z) {
            continue;
         }
         for (int y = floorY; y <= roofY; y++) {
            carveExec(exec, level, new BlockPos(hallEast, y, z), Blocks.DEEPSLATE_BRICKS.defaultBlockState());
         }
      }
      // The cell's own end walls, and the chamber's.
      for (int x = EXEC_MIN_X; x <= EXEC_MAX_X; x++) {
         if (x > hallWest && x < hallEast) {
            continue;
         }
         for (int y = floorY; y <= roofY; y++) {
            carveExec(exec, level, new BlockPos(x, y, cellNorth - 1), Blocks.DEEPSLATE_BRICKS.defaultBlockState());
            carveExec(exec, level, new BlockPos(x, y, cellSouth + 1), Blocks.DEEPSLATE_BRICKS.defaultBlockState());
         }
      }
      // The gate: the bars of the cell, which are the way out the block calls "bars". They are
      // deliberately the one breakable part of the shell - mining the gate is one of the ways out, and
      // two of them gone is what brings the cell block down on the prisoner.
      exec.allBars.clear();
      exec.bars.clear();
      for (int z = EXEC_Z - 1; z <= EXEC_Z + 1; z++) {
         for (int y = EXEC_Y; y <= EXEC_Y + 2; y++) {
            BlockPos bar = new BlockPos(hallWest, y, z);
            carveExec(exec, level, bar, Blocks.IRON_BARS.defaultBlockState());
            exec.allBars.add(bar);
            exec.bars.add(bar);
         }
      }
      // Anything still open inside the shell is stone. This is the pass that closes the corners the
      // rooms do not reach and the dead space behind the chamber.
      solidifyWing(exec, level);

      // ---- The vent: the duct, and then the gravel the duct mouth is buried under.
      //
      // The order of these two is the whole of "the gravel in the block does not work". The patch
      // is a 2x2 of the cell's floor, and the duct it covers is one square wide with a wall on
      // either side of it running up to the floor's own level - so laying the gravel first and
      // cutting the duct second buried the gravel: two of the four blocks the dig counted were
      // the duct's west wall by the time the dust settled, and the block recorded them as gravel.
      // From the cell that reads as two blocks of gravel that open nothing: take both up, the
      // counter says 2 of 5, and the other three it wants are stone that looks exactly like the
      // rest of the floor and stone the miner has no reason to touch. The vent was decoration.
      //
      // The duct goes in underneath first and the gravel goes over it, which is the shape the
      // room actually has: the patch is the floor, and everything the dig counts is really gravel.
      buildExecutionDuct(exec, level, exec.vent);
      exec.ventGravel.clear();
      exec.ventGround.clear();
      for (int x = exec.vent.getX() - 1; x <= exec.vent.getX(); x++) {
         for (int z = exec.vent.getZ() - 1; z <= exec.vent.getZ(); z++) {
            BlockPos gravel = new BlockPos(x, floorY, z);
            carveExec(exec, level, gravel, Blocks.GRAVEL.defaultBlockState());
            exec.ventGravel.add(gravel);
            exec.ventGround.add(gravel);
            // ...and the duct's own walk level under the patch's duct column, which is air: gravel
            // falls, so the first break drops the block beside it a level and the counter never
            // reaches four. Only the duct's column is added - the patch's other column has the
            // duct's wall under it, and a wall is not the vent's ground.
            if (x == exec.vent.getX()) {
               exec.ventGround.add(gravel.below());
            }
         }
      }

      // ---- The chair chamber: the dais the chair stands on, the chair, and the executioner's stand.
      for (int x = hallEast + 1; x <= alcoveEast; x++) {
         for (int z = cellNorth; z <= cellSouth; z++) {
            carveExec(exec, level, new BlockPos(x, EXEC_Y, z), Blocks.POLISHED_DEEPSLATE.defaultBlockState());
         }
      }
      // A chiseled lip along the dais edge, so the step up to the chair is one you can see.
      for (int z = cellNorth; z <= cellSouth; z++) {
         carveExec(exec, level, new BlockPos(hallEast + 1, EXEC_Y, z), Blocks.CHISELED_DEEPSLATE.defaultBlockState());
      }
      carveExec(exec, level, exec.chair, Blocks.DARK_OAK_STAIRS.defaultBlockState());

      // ---- Dressing. The wing is the block's maximum-security floor and should read as one
      //      building: a carpet run from the cell to the chair, a wainscot and pilasters down the
      //      hall, a lit finish at the exit, a gate frame, and a chamber laid out around the chair.
      // The carpet run lies *on* the floor, which is the whole of "the floor of the block has a hole
      // in it down the middle": it was written at the floor's own level, which does not lay a carpet
      // over the floor, it takes the floor block away and leaves the carpet hanging over the void
      // under the hall. A carpet over air does not stay - it pops the moment the block below it is
      // read - so the run survived only where the floor underneath it happened to be more than one
      // block thick (over the duct's own walls) and was a hole everywhere else. The finish sits one
      // block up for the same reason.
      for (int z = hallNorth; z <= hallSouth; z++) {
         carveExec(exec, level, new BlockPos(EXEC_X, EXEC_Y, z), Blocks.CARPET.pick(net.minecraft.world.item.DyeColor.RED).defaultBlockState());
         if (z < EXEC_Z - 1 || z > EXEC_Z + 1) {
            carveExec(exec, level, new BlockPos(hallWest, EXEC_Y, z), Blocks.POLISHED_DEEPSLATE.defaultBlockState());
         }
         carveExec(exec, level, new BlockPos(hallEast, EXEC_Y, z), Blocks.POLISHED_DEEPSLATE.defaultBlockState());
      }
      // Pilasters every few paces on both hall walls, with a lamp hung under the roof between them.
      for (int z = hallNorth + 1; z <= hallSouth - 1; z += 4) {
         boolean gate = z >= EXEC_Z - 1 && z <= EXEC_Z + 1;
         for (int y = EXEC_Y; y <= topY; y++) {
            if (!gate) {
               carveExec(exec, level, new BlockPos(hallWest, y, z), Blocks.CHISELED_DEEPSLATE.defaultBlockState());
            }
            carveExec(exec, level, new BlockPos(hallEast, y, z), Blocks.CHISELED_DEEPSLATE.defaultBlockState());
         }
         carveExec(exec, level, new BlockPos(EXEC_X, topY, z), Blocks.LANTERN.defaultBlockState());
      }
      // A chiseled frame around the gate, and two lamps hung on the wall beside it - not in it, which
      // is where they used to be, and which is one of the ways this gate had holes in it.
      for (int y = floorY; y <= roofY; y++) {
         carveExec(exec, level, new BlockPos(hallWest, y, cellNorth - 1), Blocks.CHISELED_DEEPSLATE.defaultBlockState());
         carveExec(exec, level, new BlockPos(hallWest, y, cellSouth + 1), Blocks.CHISELED_DEEPSLATE.defaultBlockState());
         carveExec(exec, level, new BlockPos(hallWest, y, EXEC_Z - 2), Blocks.CHISELED_DEEPSLATE.defaultBlockState());
         carveExec(exec, level, new BlockPos(hallWest, y, EXEC_Z + 2), Blocks.CHISELED_DEEPSLATE.defaultBlockState());
      }
      carveExec(exec, level, new BlockPos(hallWest, topY, cellNorth - 1), Blocks.LANTERN.defaultBlockState());
      carveExec(exec, level, new BlockPos(hallWest, topY, cellSouth + 1), Blocks.LANTERN.defaultBlockState());
      // The cell: a bunk down the west wall, a chain, cobwebs and a drain.
      for (int z = cellNorth; z <= cellSouth; z++) {
         carveExec(exec, level, new BlockPos(cellWest, EXEC_Y, z), ((net.minecraft.world.level.block.Block)Blocks.WOOL.red()).defaultBlockState());
      }
      carveExec(exec, level, new BlockPos(cellWest, EXEC_Y, cellNorth), ((net.minecraft.world.level.block.Block)Blocks.WOOL.white()).defaultBlockState());
      carveExec(exec, level, new BlockPos(cellWest + 2, EXEC_Y + 2, cellNorth), Blocks.COBWEB.defaultBlockState());
      carveExec(exec, level, new BlockPos(cellEast - 1, EXEC_Y + 2, cellSouth), Blocks.COBWEB.defaultBlockState());
      carveExec(exec, level, new BlockPos(cellEast, EXEC_Y + 2, cellNorth), Blocks.IRON_CHAIN.defaultBlockState());
      carveExec(exec, level, new BlockPos(cellWest, EXEC_Y + 2, cellSouth), Blocks.IRON_TRAPDOOR.defaultBlockState());
      carveExec(exec, level, new BlockPos(cellEast, topY, cellSouth), Blocks.SEA_LANTERN.defaultBlockState());
      // The chamber: soul lanterns over the chair, chains in the corners, a red walk up to the dais,
      // a barred lintel over the doorway, and the executioner's own corner beside the chair.
      carveExec(exec, level, new BlockPos(alcoveEast, EXEC_Y + 3, cellNorth), Blocks.SOUL_LANTERN.defaultBlockState());
      carveExec(exec, level, new BlockPos(alcoveEast, EXEC_Y + 3, cellSouth), Blocks.SOUL_LANTERN.defaultBlockState());
      carveExec(exec, level, new BlockPos(alcoveEast, EXEC_Y + 2, cellNorth), Blocks.IRON_CHAIN.defaultBlockState());
      carveExec(exec, level, new BlockPos(alcoveEast, EXEC_Y + 2, cellSouth), Blocks.IRON_CHAIN.defaultBlockState());
      carveExec(exec, level, new BlockPos(hallEast, topY, EXEC_Z), Blocks.IRON_BARS.defaultBlockState());
      carveExec(exec, level, new BlockPos(EXEC_X + 1, EXEC_Y, EXEC_Z), Blocks.DARK_OAK_PLANKS.defaultBlockState());
      carveExec(exec, level, new BlockPos(EXEC_X + 1, floorY, EXEC_Z), Blocks.DARK_OAK_SLAB.defaultBlockState());
      carveExec(exec, level, new BlockPos(EXEC_X + 1, floorY, EXEC_Z - 1), Blocks.DARK_OAK_SLAB.defaultBlockState());
      carveExec(exec, level, new BlockPos(EXEC_X + 1, floorY, EXEC_Z + 1), Blocks.DARK_OAK_SLAB.defaultBlockState());
      // The finish: a marker on the floor at the far end and two lanterns either side of it, so the
      // end of the run is visible from the cell.
      carveExec(exec, level, new BlockPos(EXEC_X, EXEC_Y, hallNorth + 1), Blocks.GOLD_BLOCK.defaultBlockState());
      carveExec(exec, level, new BlockPos(EXEC_X - 2, topY, hallNorth), Blocks.LANTERN.defaultBlockState());
      carveExec(exec, level, new BlockPos(EXEC_X + 2, topY, hallNorth), Blocks.LANTERN.defaultBlockState());
      spawnExecutionCast(level, exec);
   }

   /**
    * Fills anything still open inside the shell with stone.
    *
    * <p>The last pass of {@link #buildExecutionBlock}, and the reason this wing cannot have a hole in
    * it: the rooms are carved out of a shell that was laid down first, so whatever the carving left
    * behind - a corner a room does not reach, the dead space behind the chair chamber - is closed
    * here rather than being argued about by the loops that built it. The one thing it must not touch
    * is a way out, so the rooms' own volumes and the barred gate are handed to it and skipped.
    */
   private static void solidifyWing(Execution exec, ServerLevel level) {
      int topY = EXEC_Y + EXEC_HEIGHT - 1;
      int hallWest = EXEC_X - EXEC_HALL_HALF_X - 1;
      int hallEast = EXEC_X + EXEC_HALL_HALF_X + 1;
      int hallNorth = EXEC_Z - EXEC_HALL_NORTH;
      int hallSouth = EXEC_Z + EXEC_HALL_SOUTH;
      int cellWest = EXEC_X - EXEC_CELL_WEST;
      int cellEast = EXEC_X - EXEC_CELL_EAST;
      int cellNorth = EXEC_Z - EXEC_CELL_Z;
      int cellSouth = EXEC_Z + EXEC_CELL_Z;
      int alcoveEast = EXEC_X + EXEC_ALCOVE_EAST;
      Set<BlockPos> open = new HashSet<>();
      for (int x = hallWest + 1; x <= hallEast - 1; x++) {
         for (int z = hallNorth; z <= hallSouth; z++) {
            for (int y = EXEC_Y; y <= topY; y++) {
               open.add(new BlockPos(x, y, z));
            }
         }
      }
      for (int x = cellWest; x <= cellEast; x++) {
         for (int z = cellNorth; z <= cellSouth; z++) {
            for (int y = EXEC_Y; y <= topY; y++) {
               open.add(new BlockPos(x, y, z));
            }
         }
      }
      for (int x = hallEast + 1; x <= alcoveEast; x++) {
         for (int z = cellNorth; z <= cellSouth; z++) {
            for (int y = EXEC_Y; y <= topY; y++) {
               open.add(new BlockPos(x, y, z));
            }
         }
      }
      for (int y = EXEC_Y; y <= topY; y++) {
         open.add(new BlockPos(hallEast, y, EXEC_Z));
      }
      for (int x = EXEC_MIN_X + 1; x <= EXEC_MAX_X - 1; x++) {
         for (int z = EXEC_MIN_Z + 1; z <= EXEC_MAX_Z - 1; z++) {
            for (int y = EXEC_Y; y <= topY; y++) {
               BlockPos p = new BlockPos(x, y, z);
               if (open.contains(p) || !level.getBlockState(p).isAir()) {
                  continue;
               }
               carveExec(exec, level, p, Blocks.DEEPSLATE_BRICKS.defaultBlockState());
            }
         }
      }
   }

   /**
    * The vent duct: one wide and one tall, running north under the cell floor, then east under the
    * hall, then up a ladder into the far end of the hall.
    *
    * <p>A duct that dead-ends is a wait with a tunnel painted on it, which is what the vent used to
    * be: a trapdoor under a timer with a ladder that led into the ceiling above it. This one goes
    * somewhere. The crawl starts at the hole in the cell floor and finishes at the ladder under the
    * hall's north end - the same far end of the same hall the gate opens onto, which is the whole
    * point of it being a way out.
    *
    * <p>Both ends are walled with stone while it is built and opened only when the dig finishes, so a
    * hole in the cell floor is never a hole in the block.
    */
   private static void buildExecutionDuct(Execution exec, ServerLevel level, BlockPos hatch) {
      int ductX = hatch.getX();
      int startZ = hatch.getZ();
      int turnZ = exec.riser.getZ();
      int riserX = exec.riser.getX();
      int yFloor = hatch.getY() - 2;
      int yWalk = hatch.getY() - 1;
      int yCeil = hatch.getY();

      // The north leg: from under the hatch to the turn, one square, walled on both sides.
      for (int z = startZ; z >= turnZ; z--) {
         carveExec(exec, level, new BlockPos(ductX, yFloor, z), Blocks.DEEPSLATE_TILES.defaultBlockState());
         carveExec(exec, level, new BlockPos(ductX, yWalk, z), Blocks.AIR.defaultBlockState());
         if (z != startZ) {
            carveExec(exec, level, new BlockPos(ductX, yCeil, z), Blocks.DEEPSLATE_TILES.defaultBlockState());
         }
         for (int y = yFloor; y <= yFloor + 2; y++) {
            carveExec(exec, level, new BlockPos(ductX - 1, y, z), Blocks.DEEPSLATE_BRICKS.defaultBlockState());
            carveExec(exec, level, new BlockPos(ductX + 1, y, z), Blocks.DEEPSLATE_BRICKS.defaultBlockState());
         }
      }
      // The turn, and the east leg that carries it under the hall to the riser.
      for (int x = ductX; x <= riserX; x++) {
         carveExec(exec, level, new BlockPos(x, yFloor, turnZ), Blocks.DEEPSLATE_TILES.defaultBlockState());
         carveExec(exec, level, new BlockPos(x, yWalk, turnZ), Blocks.AIR.defaultBlockState());
         carveExec(exec, level, new BlockPos(x, yCeil, turnZ), Blocks.DEEPSLATE_TILES.defaultBlockState());
         for (int y = yFloor; y <= yFloor + 2; y++) {
            carveExec(exec, level, new BlockPos(x, y, turnZ - 1), Blocks.DEEPSLATE_BRICKS.defaultBlockState());
            carveExec(exec, level, new BlockPos(x, y, turnZ + 1), Blocks.DEEPSLATE_BRICKS.defaultBlockState());
         }
      }
      // The riser is plugged: the duct's far end is stone and the hall's floor is whole over it until
      // the dig opens the way. Both are remembered, so both go back with everything else.
      exec.ventSeal.clear();
      BlockPos plug = new BlockPos(riserX, yWalk, turnZ);
      carveExec(exec, level, plug, Blocks.DEEPSLATE_BRICKS.defaultBlockState());
      exec.ventSeal.add(plug);
   }

   /**
    * The dig is done: the duct mouth is opened and the riser becomes a way up.
    *
    * <p>Every write goes through {@link #carveExec}, which remembers the first state it saw at a
    * position - so a way out opened here is given back with the rest of the wing, and the sentence
    * leaves nothing behind it in the sky.
    */
   private static void openVent(Execution exec, ServerLevel level) {
      if (exec.ventOpen) {
         return;
      }
      exec.ventOpen = true;
      for (BlockPos seal : exec.ventSeal) {
         carveExec(exec, level, seal, Blocks.AIR.defaultBlockState());
      }
      carveExec(exec, level, exec.vent, Blocks.AIR.defaultBlockState());
      // And the block the hatch opens onto, because the way out is the one place in this shaft a
      // gravel that fell a level must not be left standing.
      carveExec(exec, level, exec.vent.below(), Blocks.AIR.defaultBlockState());
      carveExec(exec, level, exec.riser, Blocks.LADDER.defaultBlockState());
   }

   /**
    * The block's company: the executioner standing over the chair, and a witness gallery in the hall.
    *
    * <p>Both are set dressing with no AI and no drops - the sentence is the clock and the two ways
    * out, and a body that swings at the prisoner would be a third thing to fight instead. They are
    * tracked so the block can be emptied the moment the sentence ends.
    *
    * <p>The executioner stands on the dais beside the chair, facing the cell, so the one thing a
    * prisoner sees straight across the hall is the man the chair belongs to. He is invulnerable and
    * he does not move: he is the furniture of the room, and the room is the sentence.
    */
   private static void spawnExecutionCast(ServerLevel level, Execution exec) {
      Mob executioner = EntityTypes.VINDICATOR.create(level, EntitySpawnReason.COMMAND);
      if (executioner != null) {
         executioner.setPos(exec.chair.getX() + 0.5, exec.chair.getY(), exec.chair.getZ() - 1.5);
         executioner.setPersistenceRequired();
         executioner.setNoAi(true);
         executioner.setInvulnerable(true);
         // Facing west, at the cell and the gate: the man the prisoner is looking at.
         executioner.setYRot(90.0F);
         executioner.setYHeadRot(90.0F);
         executioner.setCustomName(Component.literal("§4§lThe Executioner"));
         executioner.setCustomNameVisible(true);
         executioner.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.NETHERITE_AXE));
         executioner.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.NETHERITE_HELMET));
         executioner.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.NETHERITE_CHESTPLATE));
         for (EquipmentSlot slot : EquipmentSlot.values()) {
            executioner.setDropChance(slot, 0.0F);
         }
         level.addFreshEntity(executioner);
         exec.cast.add(executioner.getUUID());
      }
      for (int i = -1; i <= 1; i++) {
         net.minecraft.world.entity.decoration.ArmorStand witness =
            new net.minecraft.world.entity.decoration.ArmorStand(level, EXEC_X + 0.5, EXEC_Y, EXEC_Z - 6 + i + 0.5);
         witness.setYRot(-90.0F);
         witness.setYHeadRot(-90.0F);
         witness.setCustomName(Component.literal("§7Witness"));
         witness.setCustomNameVisible(true);
         witness.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET));
         witness.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.LEATHER_CHESTPLATE));
         witness.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.EMERALD));
         level.addFreshEntity(witness);
         exec.cast.add(witness.getUUID());
      }
   }

   /**
    * The man the noise sends: a cell-block guard put into the hall on the first block of gravel.
    *
    * <p>He is the dig's clock. The vent is a way out that has to be worked, and the one thing that
    * makes working it a decision rather than a chore is that it is audible - so the block answers it,
    * and the prisoner gets to choose between digging faster and being somewhere else when he arrives.
    */
   private static void spawnVentSentry(ServerPlayer player, ServerLevel level, Execution exec) {
      if (exec.sentry != null) {
         net.minecraft.world.entity.Entity alive = level.getEntity(exec.sentry);
         if (alive != null && alive.isAlive()) {
            return;
         }
         exec.sentry = null;
      }
      BlockPos post = new BlockPos(EXEC_X, EXEC_Y, EXEC_Z - EXEC_HALL_NORTH + 1);
      if (!isStandable(level, post)) {
         post = new BlockPos(EXEC_X, EXEC_Y, EXEC_Z - EXEC_HALL_NORTH + 2);
      }
      Mob sentry = spawnGuard(player, post);
      if (sentry == null) {
         return;
      }
      sentry.setCustomName(Component.literal("§c§lThe Patrol"));
      sentry.setCustomNameVisible(true);
      sentry.setTarget(player);
      exec.sentry = sentry.getUUID();
   }

   /** True once the man sent to look has reached the cell, measured through the bars. */
   private static boolean ventSentryHasPlayer(ServerLevel level, Execution exec, ServerPlayer player) {
      if (exec.sentry == null) {
         return false;
      }
      net.minecraft.world.entity.Entity sentry = level.getEntity(exec.sentry);
      return sentry != null && sentry.distanceToSqr(player) <= VENT_CATCH_RANGE * VENT_CATCH_RANGE;
   }

   /** Puts the man sent to look down, and forgets him. */
   private static void discardSentry(ServerLevel level, Execution exec) {
      if (exec.sentry == null) {
         return;
      }
      net.minecraft.world.entity.Entity sentry = level.getEntity(exec.sentry);
      if (sentry != null) {
         sentry.discard();
      }
      guardRewardCarry.remove(exec.sentry);
      for (Set<UUID> owned : guards.values()) {
         owned.remove(exec.sentry);
      }
      exec.sentry = null;
   }

   /** Replaces one block of the room, remembering the original exactly once. */
   private static void carveExec(Execution exec, ServerLevel level, BlockPos pos, BlockState state) {
      exec.carved.putIfAbsent(pos, level.getBlockState(pos));
      level.setBlock(pos, state, 3);
   }

   /** Puts the room back exactly as it was found, so no sentence leaves a box in the sky. */
   private static void sealExecution(ServerLevel level, Execution exec) {
      for (Map.Entry<BlockPos, BlockState> e : exec.carved.entrySet()) {
         level.setBlock(e.getKey(), e.getValue(), 3);
      }
   }

   private static void discardExecGuards(ServerLevel level, Execution exec) {
      discardSentry(level, exec);
      for (UUID id : new ArrayList<>(exec.guards)) {
         net.minecraft.world.entity.Entity e = level.getEntity(id);
         if (e != null) {
            e.discard();
         }
         guardRewardCarry.remove(id);
         for (Set<UUID> owned : guards.values()) {
            owned.remove(id);
         }
      }
      exec.guards.clear();
      // The executioner and the gallery go with the sentence: they are furniture, not a detail, and
      // an empty room that still has a man in it is worse than an empty one.
      for (UUID id : new ArrayList<>(exec.cast)) {
         net.minecraft.world.entity.Entity e = level.getEntity(id);
         if (e != null) {
            e.discard();
         }
      }
      exec.cast.clear();
   }

   /** Puts the cell's gate back, so a killed prisoner is standing in front of the choice again. */
   private static void restoreBars(ServerLevel level, Execution exec) {
      for (BlockPos bar : exec.allBars) {
         level.setBlock(bar, Blocks.IRON_BARS.defaultBlockState(), 3);
      }
      exec.bars.clear();
      exec.bars.addAll(exec.allBars);
      exec.barsBroken = 0;
      exec.guardsOut = false;
      exec.gateOpen = false;
      exec.gateOpenTick = 0L;
   }

   /**
    * Two bars gone brings five cell-block guards into the hall - held at the far end until the gate
    * gives.
    *
    * <p>They are put down the hall from the cell rather than on top of it, and they are not set on
    * the prisoner yet: a guard who charges a locked cell just stands at the bars, and a guard
    * standing at the bars is a guard who takes the prisoner the instant the gate opens. They wait
    * at their end of the hall for the gate, and the gate is what starts the run.
    */
   private static void spawnExecutionGuards(ServerPlayer player, Execution exec) {
      ServerLevel level = (ServerLevel)player.level();
      int southEnd = EXEC_Z + EXEC_HALL_SOUTH - 1;
      for (int i = 0; i < 5; i++) {
         BlockPos spot = new BlockPos(EXEC_X - 1 + (i % 3), EXEC_Y, southEnd + (i % 2));
         if (!isStandable(level, spot)) {
            spot = new BlockPos(EXEC_X, EXEC_Y, southEnd + (i % 2));
         }
         Mob guard = spawnGuard(player, spot);
         if (guard != null) {
            exec.guards.add(guard.getUUID());
         }
      }
      level.playSound(null, EXEC_X + 0.5, EXEC_Y, EXEC_Z, SoundEvents.WITHER_SPAWN, SoundSource.HOSTILE, 0.8F, 1.3F);
   }

   /**
    * The gate gives: the cell is open, the guards are turned loose, and the run to the exit starts.
    *
    * <p>This is the moment the "bars" way out stops being a mining job and becomes a chase. Every
    * bar gone opens the cell; that is not the escape. The escape is the far end of the hall, and the
    * guards are between the prisoner and it now that they have a prisoner to want.
    */
   private static void openExecutionGate(ServerPlayer player, ServerLevel level, Execution exec) {
      if (exec.gateOpen) {
         return;
      }
      exec.gateOpen = true;
      exec.gateOpenTick = clock(level);
      for (UUID id : exec.guards) {
         net.minecraft.world.entity.Entity e = level.getEntity(id);
         if (e instanceof Mob mob) {
            mob.setTarget(player);
         }
      }
      level.playSound(null, EXEC_X + 0.5, EXEC_Y, EXEC_Z, SoundEvents.IRON_DOOR_OPEN, SoundSource.BLOCKS, 1.0F, 0.6F);
      level.sendParticles(
         new net.minecraft.core.particles.DustParticleOptions(0x7FFFD4, 1.0F),
         EXEC_X + 0.5, EXEC_Y + 1.0, EXEC_Z + 0.5, 14, 0.4, 0.5, 0.4, 0.04
      );
      Chat.raw(player, "§a§lTHE GATE GIVES§r §7- out of the cell. §fRun the hall§7: the exit is at the far end, and the guards are behind you.");
      title(player, "§a§lRUN", "§7the far end of the hall - do not let them reach you");
   }

   /**
    * True when the body is inside the duct rather than on the floor above it.
    *
    * <p>The whole of the "the vent counts me as escaping while I walk over it" bug is in this test.
    * What the vent acts on is being <b>in the duct</b>, which means below the cell floor's own level -
    * a body standing on the gravel is a body standing on the floor, and the hole it has dug is a hole
    * it has not gone through. The volume here is the duct's own: the north leg, the turn, and the
    * riser at the far end.
    */
   private static boolean inVentDuct(Execution exec, ServerPlayer p) {
      int ductX = exec.vent.getX();
      int riserX = exec.riser.getX();
      int turnZ = exec.riser.getZ();
      return p.getY() >= EXEC_Y - 2.2
         && p.getY() < EXEC_Y - 1.0
         && p.getX() >= ductX - 0.7 && p.getX() <= riserX + 0.7
         && p.getZ() >= turnZ - 0.7 && p.getZ() <= exec.vent.getZ() + 0.7;
   }

   /** The finish of the run: within arm's reach of the hall's far end. */
   private static boolean atExecutionExit(Execution exec, ServerPlayer player) {
      return Math.abs(player.getX() - (exec.exit.getX() + 0.5)) <= 1.5
         && Math.abs(player.getZ() - (exec.exit.getZ() + 0.5)) <= 1.5;
   }

   /**
    * Cell-block guards reaching the prisoner drag them to the chair early.
    *
    * <p>Only once the gate has given - a guard cannot lay hands on a prisoner through the bars - and
    * only after a beat, so the run is a run rather than a reflex. Run the hall and they never reach
    * you; stand, or let them close, and the sentence is carried out ahead of its clock.
    */
   private static boolean guardsHaveCaught(ServerLevel level, Execution exec, ServerPlayer player, long now) {
      if (exec.phase != Execution.Phase.CELL || !exec.gateOpen || exec.guards.isEmpty()) {
         return false;
      }
      if (now < exec.gateOpenTick + EXECUTION_CATCH_GRACE) {
         return false;
      }
      double limit = EXECUTION_CATCH_RANGE * EXECUTION_CATCH_RANGE;
      for (UUID id : exec.guards) {
         net.minecraft.world.entity.Entity e = level.getEntity(id);
         if (e != null && e.distanceToSqr(player) <= limit) {
            return true;
         }
      }
      return false;
   }

   /**
    * A fatal blow in the chair.
    *
    * <p>Killed by the guards the bars called, the prisoner is dragged back to the chair and the
    * room is put back the way it was - the sentence goes on. Anything else, including the chair's
    * own shocks, carries the sentence out.
    */
   private static void takeExecutionBlow(ServerPlayer player, ServerLevel level, Execution exec, LethalBlows.Blow blow) {
      net.minecraft.world.entity.Entity killer = blow.source() == null ? null : blow.source().getEntity();
      if (killer instanceof Mob mob && exec.guards.contains(mob.getUUID())) {
         discardExecGuards(level, exec);
         restoreBars(level, exec);
         Chat.raw(player, "§c§lTHE GUARDS HAVE YOU§r §7- they drag you off the bars and hand you to the chair early.");
         toChair(player, level, exec, "§4§lTHE SENTENCE IS CARRIED OUT EARLY§r §7- the cell block takes its prisoner.");
         return;
      }
      carryOutExecution(player, level, exec, "§4§lTHE SENTENCE IS CARRIED OUT§r §7- the chair takes what is left.");
   }

   /** Out: the room goes back, the haul stays on the prisoner, and they return to the mine. */
   /**
    * The ways out of the maximum-security wing, and what each one is worth.
    *
    * <p>They used to end identically, which made the choice a formality. Each has its own price now:
    * the vent is quiet but it is work and it is loud while you do it, and the bars are a fight that
    * pays - so the two cool the meter by different amounts, and the one that pays you is the one that
    * puts five men in the hall after you.
    */
   public enum Out { VENT, BARS }

   private static void freeFromExecution(ServerPlayer player, ServerLevel level, Execution exec, String why, Out out) {
      if (executions.remove(player.getUUID()) == null) {
         return;
      }
      sealExecution(level, exec);
      discardExecGuards(level, exec);
      sentences.remove(player.getUUID());
      cuffs.remove(player.getUUID());
      player.removeEffect(MobEffects.SLOWNESS);
      player.removeEffect(MobEffects.WEAKNESS);
      player.removeEffect(MobEffects.DARKNESS);
      // Going through the men at the bars is the one that reads as *the record*; the vent is work
      // nobody saw, and a palm greased is not a leaf turned over.
      int cool = switch (out) {
         case BARS -> 30;
         case VENT -> 20;
      };
      heat.put(player.getUUID(), Math.max(0, heatOf(player.getUUID()) - cool));
      player.setHealth(player.getMaxHealth());
      BlockPos spawn = PrisonManager.freshFloorSpawn(player);
      player.teleport(new TeleportTransition(
         level, new Vec3(spawn.getX() + 0.5, spawn.getY() + 1.0, spawn.getZ() + 0.5), Vec3.ZERO, player.getYRot(), player.getXRot(), TeleportTransition.PLACE_PORTAL_TICKET
      ));
      Chat.raw(player, why);
      Chat.raw(player, "§7Your haul is still on you. §aHeat -" + cool + "§7.");
      if (out == Out.BARS) {
         int rank = PrisonManager.rankOf(player.getUUID());
         long pay = 500L + 100L * rank;
         PrisonManager.addCash(player.getUUID(), pay);
         Chat.raw(player, "§a§lYOU WENT THROUGH THEM§r §7- the men at the bars are dealt with. §e+" + Chat.moneyStr(pay) + "§a prison cash.");
      } else if (out == Out.VENT) {
         PrisonManager.giveTokens(player.getUUID(), 1L);
         Chat.raw(player, "§a§lQUIET AS A DUCT§r §7- nobody saw you leave. §d+1 token§7.");
      }
      level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 1.0F, 1.5F);
   }

   /**
    * The sentence carried out: thrown out of the mode, bag gone, the record wiped, and a cooldown on
    * the door.
    *
    * <p>Three things are finished here rather than left to the walk home.
    *
    * <p>The prisoner is put back together <i>before</i> the teleport. The charge that ends a sentence
    * is real damage, so the body being carried out is a body on its way to nothing - and the block
    * does not kill, it throws out, so what used to arrive in the overworld was a player at zero
    * health who died the moment they landed. The death was outside the block, which means the prison
    * could not answer it: the kit came back out of the locker onto a body that died holding it, and
    * the grave, the items and the walk back were all the overworld's problem. A sentence carried out
    * costs the bag and the wait; it does not cost what the block's own rule says it never costs.
    *
    * <p>The record is wiped. A thrown-out prisoner is a stranger to the block: {"all your heat"} is
    * the sentence ending, not a debt that follows the prisoner home to be paid on the way back in -
    * otherwise the door locks behind the escape and the manhunt is waiting on the other side of it,
    * which makes the one ending that is supposed to be the end of the matter the worst one to take.
    *
    * <p>And the detail is put down. The guards the bars called, the sentry, the executioner and his
    * gallery, and the Warden himself if he was out: {@link #releaseGuards} stands the prisoner's own
    * detail down and {@code PrisonManager.leave} runs the wing's own ending, so nothing that was
    * hunting the prisoner is still walking the block when the door opens again.
    */
   private static void carryOutExecution(ServerPlayer player, ServerLevel level, Execution exec, String why) {
      executions.remove(player.getUUID());
      sealExecution(level, exec);
      discardExecGuards(level, exec);
      sentences.remove(player.getUUID());
      cuffs.remove(player.getUUID());
      shakeGrace.remove(player.getUUID());
      player.removeEffect(MobEffects.SLOWNESS);
      player.removeEffect(MobEffects.WEAKNESS);
      player.removeEffect(MobEffects.DARKNESS);
      // The record goes with the sentence: heat is wiped, not carried out of the block. Handing a
      // freshly thrown-out prisoner the same Heat they were thrown out with is a walking loop of
      // manhunts, and the Heat ladder is the thing the block is *for*, not a sentence of its own.
      heat.put(player.getUUID(), 0);
      long lost = confiscateEverything(player);
      prisonCooldown.put(player.getUUID(), clock(level) + PRISON_COOLDOWN_TICKS);
      Chat.raw(player, why);
      if (lost > 0L) {
         Chat.raw(player, "§7They emptied the bag into the furnace: §c" + Chat.moneyStr(lost) + "§7 worth gone.");
      }
      Chat.raw(player, "§4§lTHROWN OUT§r §7- the block is done with you, and so is your record: §fyou are §aCLEAR§f again§7. It will not take you back for §f" + (PRISON_COOLDOWN_TICKS / 20L) + "s§7.");
      PrisonManager.leave(player);
   }

   /** Ticks until a thrown-out prisoner may enter the block again, or 0. */
   public static long cooldownLeft(UUID uuid) {
      Long until = prisonCooldown.get(uuid);
      return until == null ? 0L : Math.max(0L, until - lastKnownTick);
   }

   /**
    * Test hook: lock a prisoner out for this many ticks from now, or clear it with anything at all
    * short of that.
    *
    * <p>Ticks left rather than an absolute tick, because the block's clock is not the server's and
    * "a minute of lock-out" is the thing a test means whatever the clock happens to read.
    */
   public static void putOnCooldownForTest(UUID uuid, long ticks) {
      if (ticks <= 0L) {
         prisonCooldown.remove(uuid);
      } else {
         prisonCooldown.put(uuid, lastKnownTick + ticks);
      }
   }

   /**
    * Test hook: one trip through the execution clock as it applies to one body.
    *
    * <p>The level-wide trip looks its prisoner up on the server player list, which never holds a
    * test harness body, so the harness asks the clock about its prisoner directly - the same
    * predicate and the same ending, on a body it can watch.
    */
   public static void tickExecutionsForTest(ServerPlayer player) {
      Execution exec = executions.get(player.getUUID());
      if (exec == null) {
         return;
      }
      if (!(player.level() instanceof ServerLevel level)) {
         return;
      }
      tickExecution(player, level, exec, clock(level));
   }

   /** Test hook: the bars the cell gate was built with. */
   public static List<BlockPos> executionBarsForTest(UUID uuid) {
      Execution x = executions.get(uuid);
      return x == null ? List.of() : List.copyOf(x.allBars);
   }

   /** Test hook: the far end of the hall - the finish of the run a broken gate starts. */
   public static BlockPos executionExitForTest(UUID uuid) {
      Execution x = executions.get(uuid);
      return x == null ? null : x.exit;
   }

   /** Test hook: has every bar of the cell gate come away? */
   public static boolean gateOpenForTest(UUID uuid) {
      Execution x = executions.get(uuid);
      return x != null && x.gateOpen;
   }

   /** Test hook: let the guards close immediately, as if the run had already been lost. */
   public static void armGuardCatchForTest(UUID uuid) {
      Execution x = executions.get(uuid);
      if (x != null) {
         x.gateOpenTick = 0L;
      }
   }

   public static BlockPos executionVentForTest(UUID uuid) {
      Execution x = executions.get(uuid);
      return x == null ? null : x.vent;
   }

   /** Test hook: how many sentences to the chair are live right now. */
   public static int executionCountForTest() {
      return executions.size();
   }

   /** Test hook: who is in the wing right now, so a leaked sentence can be traced to its check. */
   public static List<String> executionOwnersForTest() {
      List<String> out = new ArrayList<>();
      for (UUID id : executions.keySet()) {
         out.add(id.toString().substring(0, 8));
      }
      return out;
   }

   /**
    * Clears the wing's own footprint of anything a sentence from an older shape of it left standing.
    *
    * <p>The same one-pass migration the mine's old ceiling gets, for the same reason: a sentence is
    * carved out of the block's rock and the rock is given back when the sentence ends, so the world a
    * fresh sentence finds should be rock and nothing else. A wing left standing by the sentence of an
    * older build is not rock: it is what the next {@link #buildExecutionBlock} records as the
    * "original" at every position it writes, so the ending gives the stale wing back instead of the
    * void - a maximum-security room that never goes away, one hole in its floor included, which is
    * exactly what a walk of the wing turns up after a few boots on the same world.
    *
    * <p>Deliberately only when no sentence is live: a wing that somebody is serving belongs to them,
    * and the shape of it they are standing in is the shape this build makes.
    */
   public static void sweepExecutionWing(ServerLevel level) {
      if (level == null || !executions.isEmpty()) {
         return;
      }
      for (int x = EXEC_MIN_X; x <= EXEC_MAX_X; x++) {
         for (int y = EXEC_MIN_Y; y <= EXEC_MAX_Y; y++) {
            for (int z = EXEC_MIN_Z; z <= EXEC_MAX_Z; z++) {
               BlockPos p = new BlockPos(x, y, z);
               if (!level.getBlockState(p).isAir()) {
                  level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
               }
            }
         }
      }
   }

   /**
    * Test hook: what a position held before the wing was carved there, or null if it was never
    * touched. A hole in the shell is either a position the build never wrote or one it is about to
    * hand back as it found it, and the two read identically in the world.
    */
   public static String executionOriginalForTest(UUID uuid, BlockPos pos) {
      Execution x = executions.get(uuid);
      if (x == null) {
         return "no live sentence";
      }
      BlockState original = x.carved.get(pos);
      return original == null ? "never carved" : stateToText(original);
   }

   /** Test hook: the wing's own outer corners, so a test can walk its shell and look for holes. */
   public static BlockPos[] executionWingForTest(UUID uuid) {
      Execution x = executions.get(uuid);
      return x == null ? null : new BlockPos[]{x.wingMin, x.wingMax};
   }

   /** Test hook: the gravel still covering a live execution's duct mouth. */
   public static List<BlockPos> executionVentGravelForTest(UUID uuid) {
      Execution x = executions.get(uuid);
      return x == null ? List.of() : List.copyOf(x.ventGravel);
   }

   /** Test hook: every block the vent's dig covers, patch and the duct's floor beneath it. */
   public static List<BlockPos> executionVentGroundForTest(UUID uuid) {
      Execution x = executions.get(uuid);
      return x == null ? List.of() : List.copyOf(x.ventGround);
   }

   /** The number of blocks the dig is against: the size of the patch, and of the counter. */
   public static int ventDigs() {
      return VENT_DIGS;
   }

   /** Test hook: how many blocks of gravel this prisoner has taken off the duct mouth. */
   public static int executionVentDirtForTest(UUID uuid) {
      Execution x = executions.get(uuid);
      return x == null ? 0 : x.ventDirt;
   }

   /** Test hook: whether the dig has opened the way down yet. */
   public static boolean executionVentOpenForTest(UUID uuid) {
      Execution x = executions.get(uuid);
      return x != null && x.ventOpen;
   }

   /** Test hook: whether the noise has put a man in the hall yet. */
   public static boolean executionSentrySentForTest(UUID uuid) {
      Execution x = executions.get(uuid);
      return x != null && x.sentry != null;
   }

   /** Test hook: drive one block of the dig through the same door a pickaxe does. */
   public static boolean breakVentGravelForTest(ServerPlayer player) {
      Execution x = executions.get(player.getUUID());
      if (x == null || x.ventGravel.isEmpty()) {
         return false;
      }
      BlockPos pos = x.ventGravel.get(0);
      ServerLevel level = (ServerLevel)player.level();
      BlockState state = level.getBlockState(pos);
      level.removeBlock(pos, false);
      onBlockMined(player, pos, state);
      return true;
   }

   /** Test hook: move the man sent to look onto the prisoner, so the catch rule can be driven. */
   public static void bringVentSentryForTest(ServerPlayer player) {
      Execution x = executions.get(player.getUUID());
      if (x == null || x.sentry == null || !(player.level() instanceof ServerLevel level)) {
         return;
      }
      net.minecraft.world.entity.Entity sentry = level.getEntity(x.sentry);
      if (sentry != null) {
         sentry.setPos(player.getX(), player.getY(), player.getZ());
      }
   }

   /** Test hook: the blocks the duct's far end is plugged with until the dig finishes. */
   public static List<BlockPos> executionVentSealForTest(UUID uuid) {
      Execution x = executions.get(uuid);
      return x == null ? List.of() : List.copyOf(x.ventSeal);
   }

   /** Test hook: where the duct comes up into the hall. */
   public static BlockPos executionRiserForTest(UUID uuid) {
      Execution x = executions.get(uuid);
      return x == null ? null : x.riser;
   }

   public static BlockPos executionChairForTest(UUID uuid) {
      Execution x = executions.get(uuid);
      return x == null ? null : x.chair;
   }

   /** Test hook: where the sentence puts a body down - the hall, clear of the chair. */
   public static BlockPos executionStartForTest(UUID uuid) {
      Execution x = executions.get(uuid);
      return x == null ? null : x.start;
   }

   /** True once the escape window is gone and the chair, rather than the block, has the prisoner. */
   public static boolean inChair(UUID uuid) {
      Execution x = executions.get(uuid);
      return x != null && x.phase == Execution.Phase.CHAIR;
   }

   /** Test hook: {@code true} once the escape window is gone and the chair has taken over. */
   public static boolean inChairForTest(UUID uuid) {
      return inChair(uuid);
   }

   /** Test hook: how many cell-block guards the bar break has put in the world. */
   public static int executionGuardsForTest(UUID uuid) {
      Execution x = executions.get(uuid);
      return x == null ? 0 : x.guards.size();
   }

   /** Test hook: move the bar-break guards onto the prisoner, so the catch rule can be driven. */
   public static void bringExecutionGuardsForTest(ServerPlayer player, ServerLevel level) {
      Execution x = executions.get(player.getUUID());
      if (x == null) {
         return;
      }
      for (UUID id : x.guards) {
         net.minecraft.world.entity.Entity e = level.getEntity(id);
         if (e != null) {
            e.setPos(player.getX(), player.getY(), player.getZ());
         }
      }
   }

   /** Test hook: hand the prisoner to the chair, the way the escape clock would. */
   public static void putInChairForTest(ServerPlayer player) {
      Execution x = executions.get(player.getUUID());
      if (x != null && player.level() instanceof ServerLevel level) {
         toChair(player, level, x, "§4§lTIME IS UP§r §7- the block hands you to the chair.");
      }
   }

   /** Test hook: one charge of the chair, so the damage-over-time can be read on its own. */
   public static void chairChargeForTest(ServerPlayer player) {
      Execution x = executions.get(player.getUUID());
      if (x != null && player.level() instanceof ServerLevel level) {
         chairCharge(player, level, x);
      }
   }

   /** Test hook: how long the chair takes, in ticks. */
   public static long chairTicksForTest() {
      return CHAIR_TICKS;
   }

   /** Test hook: half a heart, in damage points. */
   public static float chairShockForTest() {
      return EXECUTION_SHOCK;
   }

   /** Test hook: put a real Warden on a prisoner, through the same door the manhunt uses. */
   public static Mob spawnWardenForTest(ServerPlayer player) {
      return spawnWarden(player);
   }

   /** Test hook: true once the prisoner has started down the vent. */
   public static boolean crawlArmedForTest(UUID uuid) {
      Execution x = executions.get(uuid);
      return x != null && x.crawlUntil > 0L;
   }

   /** Test hook: shorten the vent crawl so the next tick finishes it. */
   public static void finishCrawlForTest(UUID uuid) {
      Execution x = executions.get(uuid);
      if (x != null && x.crawlUntil > 0L) {
         x.crawlUntil = 1L;
      }
   }

   /**
    * {@code /ff test prison skipwait} - the wait, on demand, without the sentence's price.
    *
    * <p>Two waits hide behind one complaint. A prisoner who has just been thrown out of the block
    * by a carried-out sentence is locked out for {@link #PRISON_COOLDOWN_TICKS} - and the same
    * meter runs from the other side, on the sentence itself.
    *
    * <p>This drops both, and neither cheaply: a live sentence ends the way the vent does - the room
    * is sealed, the guards are gone, the bars are back, the cuffs come off - but <i>without</i>
    * taking the haul or throwing the prisoner out of the block, so what is left is the state a
    * fresh sentence should start from. The lockout is cleared the same way. Neither branch pays
    * anything out: no breakout is credited and no money changes hands, because this is a way to
    * reach the next test rather than a way to win.
    *
    * @return null on success, or why there was nothing to skip
    */
   public static String skipWait(ServerPlayer player) {
      if (player == null || !(player.level() instanceof ServerLevel level)) {
         return "that body is not in a world a prison wait can be skipped in";
      }
      UUID uuid = player.getUUID();
      Execution exec = executions.get(uuid);
      if (exec != null) {
         restoreBars(level, exec);
         endExecutionForTest(player);
         sentences.remove(uuid);
         cuffs.remove(uuid);
         player.removeEffect(MobEffects.SLOWNESS);
         player.removeEffect(MobEffects.WEAKNESS);
         player.removeEffect(MobEffects.DARKNESS);
      }
      boolean wasCooling = prisonCooldown.remove(uuid) != null;
      if (exec == null && !wasCooling) {
         return "there is no execution and no lockout to skip";
      }
      return null;
   }

   /** Test hook: drop an execution and seal the room without the sentence's sides effects. */
   public static void endExecutionForTest(ServerPlayer player) {
      if (player == null) {
         return;
      }
      Execution x = executions.remove(player.getUUID());
      if (x != null && player.level() instanceof ServerLevel level) {
         sealExecution(level, x);
         discardExecGuards(level, x);
      }
   }

   private static void tickExecutions(ServerLevel level) {
      if (executions.isEmpty()) {
         evictStranded(level);
         return;
      }
      long now = clock(level);
      for (Map.Entry<UUID, Execution> entry : new ArrayList<>(executions.entrySet())) {
         UUID uuid = entry.getKey();
         Execution exec = entry.getValue();
         ServerPlayer p = level.getServer().getPlayerList().getPlayer(uuid);
         if (p == null || !PrisonManager.isInPrison(p)) {
            sealExecution(level, exec);
            discardExecGuards(level, exec);
            executions.remove(uuid);
            continue;
         }
         tickExecution(p, level, exec, now);
      }
      evictStranded(level);
   }

   /**
    * One body's trip through the execution clock. Split out from the level-wide trip so a test
    * harness can drive the same clock on a body the server player list never holds.
    */
   private static void tickExecution(ServerPlayer p, ServerLevel level, Execution exec, long now) {
      UUID uuid = p.getUUID();
      if (exec.phase == Execution.Phase.CHAIR) {
         tickChair(p, level, exec, now);
         return;
      }
      // The vent: the gravel is off, the prisoner has dropped into the duct, and the crawl is the
      // rest of it. Asked of the duct's own volume rather than of a square of floor: standing on the
      // hatch is not going through it, and treating it as though it were is what "I am not even
      // trying and it still says I escaped" was.
      if (exec.crawlUntil > 0L) {
         if (now >= exec.crawlUntil) {
            freeFromExecution(p, level, exec, "§a§lINTO THE VENT§r §7- you squeeze through the duct and come up at the far end of the hall.", Out.VENT);
            return;
         }
      } else if (exec.ventOpen && inVentDuct(exec, p)) {
         exec.crawlUntil = now + 40L;
         p.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 60, 0, false, false, false));
         Chat.raw(p, "§7You drop to your belly and crawl into the duct... §8hold still.");
      }
      // The man the noise sent. If he reaches the cell - or if he has simply had the time it takes
      // to walk the hall - the block does not wait for the clock: it takes the prisoner straight to
      // the chair, which is the whole price of digging somewhere they can hear you dig.
      if (!exec.ventOpen && exec.ventAlertTick > 0L) {
         boolean reached = ventSentryHasPlayer(level, exec, p) || now >= exec.ventAlertTick + VENT_SENTRY_TICKS;
         if (reached) {
            Chat.raw(p, "§4§lCAUGHT AT THE HATCH§r §7- they heard the gravel, and they put you in the chair for it.");
            toChair(p, level, exec, "§4§lSTRAIGHT TO THE CHAIR§r §7- caught digging your own way out.");
            return;
         }
      } else if (exec.sentry != null) {
         // Keep the man on his errand: a guard with no target stands in the hall looking at the
         // ceiling, which is not what the noise is supposed to have bought.
         net.minecraft.world.entity.Entity sentry = level.getEntity(exec.sentry);
         if (sentry == null || !sentry.isAlive()) {
            exec.sentry = null;
         } else if (sentry instanceof Mob mob && (now % 20L == 0L)) {
            mob.setTarget(p);
            mob.getNavigation().moveTo(p, 1.0);
         }
      }
      // The bars are open: the sentence is a run now, and the far end of the hall is the finish.
      if (exec.gateOpen && atExecutionExit(exec, p)) {
         freeFromExecution(p, level, exec, "§a§lTHROUGH THE BLOCK§r §7- you reach the end of the hall and you are gone.", Out.BARS);
         return;
      }
      // The men two bars called: if they reach the prisoner, the wait is over.
      if (guardsHaveCaught(level, exec, p, now)) {
         discardExecGuards(level, exec);
         Chat.raw(p, "§c§lTHE CELL BLOCK HAS YOU§r §7- they drag you to the chair.");
         toChair(p, level, exec, "§4§lTHE SENTENCE IS CARRIED OUT EARLY§r §7- the guards end the wait.");
         return;
      }
      if (now >= exec.endTick) {
         toChair(p, level, exec, "§4§lTIME IS UP§r §7- the block hands you to the chair.");
         return;
      }
      if (now % 20L == 0L) {
         long left = Math.max(0L, (exec.endTick - now) / 20L);
         // The alarm: the block gets redder and louder as the clock runs down, so the countdown is
         // something the prisoner can see without reading the action bar mid-crawl.
         double urgency = Math.max(0.0, Math.min(1.0, 1.0 - (double)(exec.endTick - now) / (double)EXECUTION_TICKS));
         level.sendParticles(
            new net.minecraft.core.particles.DustParticleOptions(0xFF2A2A, 0.9F + (float)urgency),
            EXEC_X + 0.5, EXEC_Y + 1.0, EXEC_Z + 0.5, 2 + (int)(urgency * 16.0), 1.5, 0.8, 1.5, 0.02
         );
         if (urgency > 0.35) {
            level.playSound(
               null, EXEC_X + 0.5, EXEC_Y, EXEC_Z + 0.5, SoundEvents.ANVIL_LAND, SoundSource.HOSTILE,
               0.4F + (float)urgency * 0.5F, 0.6F + (float)urgency
            );
         }
         p.sendSystemMessage(
            Component.literal(
               "§4§lMAXIMUM SECURITY §8| §7Out in §f" + left + "s §8| §7bars §f" + exec.bars.size()
                  + " §8| §7vent §f" + (exec.ventOpen ? (exec.crawlUntil > 0L ? "crawling" : "open") : exec.ventDirt + "/" + VENT_DIGS)
                  + (exec.ventAlertTick > 0L && !exec.ventOpen ? " §8| §cTHE PATROL IS COMING" : "")
            ),
            true
         );
      }
   }

   /**
    * The chair's half of the sentence: a bite of health a second, until the clock or a fatal charge.
    *
    * <p>Split out from the escape window because they are two different questions. In the block the
    * prisoner is working the rules; in the chair there is nothing left to work, and the only thing
    * to read is the health bar going down.
    */
   private static void tickChair(ServerPlayer p, ServerLevel level, Execution exec, long now) {
      if (now >= exec.nextShockTick) {
         exec.nextShockTick = now + EXECUTION_SHOCK_TICKS;
         chairCharge(p, level, exec);
         if (!executions.containsKey(p.getUUID())) {
            return;
         }
      }
      if (now >= exec.endTick) {
         carryOutExecution(p, level, exec, "§4§lTHE CHAIR IS FINISHED§r §7- the sentence is carried out.");
      }
   }

   /**
    * One charge of the chair: two hearts and climbing, a red flash, a sound, and a shake of the dark.
    *
    * <p>Magic rather than a generic blow, because the chair is not a man with an axe: armour is not
    * what saves you from it, and a prisoner in a chestplate out-sitting the chair was one of the ways
    * this used to take a minute. Each charge is worth a little more than the one before it, so the
    * last seconds of the sentence are the loudest, and the health bar is the only thing left to read.
    */
   private static void chairCharge(ServerPlayer p, ServerLevel level, Execution exec) {
      exec.shocks++;
      float amount = EXECUTION_SHOCK + EXECUTION_SHOCK_STEP * Math.min(6, Math.max(0, exec.shocks - 1));
      boolean dealt = p.hurtServer(level, level.damageSources().magic(), amount);
      if (!dealt) {
         // A body the world will not damage - a harness player, or anything else immune to a hurt
         // call - still takes the charge: the chair is not a source a prisoner can be immune to, and
         // the whole point of it is that the clock is real.
         p.setHealth(Math.max(0.0F, p.getHealth() - amount));
      }
      level.sendParticles(
         new net.minecraft.core.particles.DustParticleOptions(0xFF2A2A, 1.4F),
         p.getX(), p.getY() + 1.0, p.getZ(), 18, 0.4, 0.6, 0.4, 0.05
      );
      level.sendParticles(ParticleTypes.DAMAGE_INDICATOR, p.getX(), p.getY() + 1.0, p.getZ(), 3, 0.3, 0.4, 0.3, 0.05);
      level.sendParticles(ParticleTypes.CRIT, exec.chair.getX() + 0.5, exec.chair.getY() + 1.0, exec.chair.getZ() + 0.5, 8, 0.3, 0.4, 0.3, 0.08);
      level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.PLAYER_HURT, SoundSource.PLAYERS, 0.9F, 0.7F);
      level.playSound(null, exec.chair.getX() + 0.5, exec.chair.getY(), exec.chair.getZ() + 0.5, SoundEvents.BEACON_DEACTIVATE, SoundSource.BLOCKS, 0.8F, 1.5F);
      p.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 30, 0, false, false, false));
      if (p.getHealth() <= 0.0F && executions.containsKey(p.getUUID())) {
         carryOutExecution(p, level, exec, "§4§lTHE CHAIR IS FINISHED§r §7- the sentence is carried out.");
      }
   }

   /**
    * Anybody standing in the sealed wing with no sentence to serve is walked out.
    *
    * <p>The wing is carved for the duration of one sentence and given back afterwards, so a player
    * found inside it without a live execution is a restart between the teleport and the sentence, or
    * a body the sentence forgot. Both are put back in the mine rather than left in a sealed box; the
    * geometry is a constant rather than a live {@link Execution} so the net still catches them after
    * a restart has emptied the map.
    */
   private static void evictStranded(ServerLevel level) {
      for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
         if (!PrisonManager.isInPrison(p) || executions.containsKey(p.getUUID())) {
            continue;
         }
         BlockPos at = p.blockPosition();
         if (at.getX() >= EXEC_MIN_X && at.getX() <= EXEC_MAX_X
            && at.getY() >= EXEC_MIN_Y && at.getY() <= EXEC_MAX_Y
            && at.getZ() >= EXEC_MIN_Z && at.getZ() <= EXEC_MAX_Z) {
            BlockPos spawn = PrisonManager.freshFloorSpawn(p);
            p.teleport(new TeleportTransition(
               level, new Vec3(spawn.getX() + 0.5, spawn.getY() + 1.0, spawn.getZ() + 0.5), Vec3.ZERO, p.getYRot(), p.getXRot(), TeleportTransition.PLACE_PORTAL_TICKET
            ));
            Chat.raw(p, "§7The block is sealed - the guards walk you back to the mine.");
         }
      }
   }

   // ------------------------------------------------------------------
   // Persistence
   // ------------------------------------------------------------------

   public static void save(JsonObject root) {
      JsonObject heatObj = new JsonObject();
      for (Map.Entry<UUID, Integer> e : heat.entrySet()) {
         if (e.getValue() > 0) {
            heatObj.addProperty(e.getKey().toString(), e.getValue());
         }
      }
      root.add("heat", heatObj);
      JsonObject contractObj = new JsonObject();
      for (Map.Entry<UUID, List<Contract>> e : contracts.entrySet()) {
         JsonArray arr = new JsonArray();
         for (Contract c : e.getValue()) {
            JsonObject o = new JsonObject();
            o.addProperty("kind", c.kind.name());
            o.addProperty("target", c.target);
            o.addProperty("reward", c.reward);
            o.addProperty("progress", c.progress);
            arr.add(o);
         }
         contractObj.add(e.getKey().toString(), arr);
      }
      root.add("contracts", contractObj);

      // The half of the block that is a *situation* rather than a record: sentences, struggles,
      // break-outs, the chair and the lock-out. Every clock in here is an absolute prison game
      // tick, which the world already persists, so a resumed sentence resumes where the clock left
      // it rather than restarting - and a restart can no longer drop a prisoner out of a state the
      // block put them in.
      JsonObject sentenceObj = new JsonObject();
      for (Map.Entry<UUID, Sentence> e : sentences.entrySet()) {
         JsonObject o = new JsonObject();
         o.addProperty("until", e.getValue().until());
         o.addProperty("cell", e.getValue().cell());
         o.addProperty("total", e.getValue().total());
         sentenceObj.add(e.getKey().toString(), o);
      }
      root.add("sentences", sentenceObj);

      JsonObject cuffObj = new JsonObject();
      for (Map.Entry<UUID, Cuffs> e : cuffs.entrySet()) {
         Cuffs c = e.getValue();
         JsonObject o = new JsonObject();
         JsonArray locks = new JsonArray();
         for (int slot : c.locks) {
            locks.add(slot);
         }
         o.add("locks", locks);
         o.addProperty("step", c.step);
         o.addProperty("until", c.until);
         o.addProperty("slips", c.slips);
         o.addProperty("hard", c.hard);
         o.addProperty("click", c.clickTicks);
         cuffObj.add(e.getKey().toString(), o);
      }
      root.add("cuffs", cuffObj);

      JsonObject execObj = new JsonObject();
      for (Map.Entry<UUID, Execution> e : executions.entrySet()) {
         Execution x = e.getValue();
         JsonObject o = new JsonObject();
         o.add("chair", posToJson(x.chair));
         o.add("vent", posToJson(x.vent));
         o.addProperty("end", x.endTick);
         o.addProperty("next", x.nextShockTick);
         o.addProperty("crawl", x.crawlUntil);
         o.addProperty("broken", x.barsBroken);
         o.addProperty("guardsOut", x.guardsOut);
         o.addProperty("ventDirt", x.ventDirt);
         o.addProperty("ventOpen", x.ventOpen);
         o.addProperty("ventAlert", x.ventAlertTick);
         JsonArray gravel = new JsonArray();
         for (BlockPos g : x.ventGravel) {
            gravel.add(posToJson(g));
         }
         o.add("ventGravel", gravel);
         JsonArray seals = new JsonArray();
         for (BlockPos g : x.ventSeal) {
            seals.add(posToJson(g));
         }
         o.add("ventSeal", seals);
         JsonArray bars = new JsonArray();
         for (BlockPos b : x.bars) {
            bars.add(posToJson(b));
         }
         o.add("bars", bars);
         JsonArray allBars = new JsonArray();
         for (BlockPos b : x.allBars) {
            allBars.add(posToJson(b));
         }
         o.add("allBars", allBars);
         o.add("carved", carvedToJson(x.carved));
         JsonArray guardIds = new JsonArray();
         for (UUID g : x.guards) {
            guardIds.add(g.toString());
         }
         o.add("guards", guardIds);
         execObj.add(e.getKey().toString(), o);
      }
      root.add("executions", execObj);

      JsonObject escObj = new JsonObject();
      for (Map.Entry<UUID, Escape> e : escapes.entrySet()) {
         Escape x = e.getValue();
         JsonObject o = new JsonObject();
         o.addProperty("dir", x.dir.getName());
         o.add("start", posToJson(x.start));
         o.add("end", posToJson(x.end));
         o.addProperty("endTick", x.endTick);
         o.addProperty("key", x.key);
         o.addProperty("gateOpen", x.gateOpen);
         o.addProperty("nextWarden", x.nextWardenTick);
         JsonArray gate = new JsonArray();
         for (BlockPos b : x.gate) {
            gate.add(posToJson(b));
         }
         o.add("gate", gate);
         o.add("carved", carvedToJson(x.carved));
         escObj.add(e.getKey().toString(), o);
      }
      root.add("escapes", escObj);

      // The clock every deadline in this file is measured against, written beside them. Not derived
      // from the server's uptime next time round, because that begins again at zero: "when does this
      // expire" only means anything next to the tick the block's clock stood at when it was written.
      root.addProperty("blockClock", lastKnownTick);
      JsonObject cooldownObj = new JsonObject();
      for (Map.Entry<UUID, Long> e : prisonCooldown.entrySet()) {
         cooldownObj.addProperty(e.getKey().toString(), e.getValue());
      }
      root.add("cooldown", cooldownObj);

      // Guard ownership, and the Warden on each trail. Without this the registry is memory only, so
      // a restart orphans every guard the block was holding - they stayed in the world as tagged,
      // persistent bodies and the next boot had no idea whose they were. Saved by owner, so a
      // prisoner's guards are his again the moment the record is read back.
      JsonObject guardObj = new JsonObject();
      for (Map.Entry<UUID, Set<UUID>> e : guards.entrySet()) {
         JsonArray ids = new JsonArray();
         for (UUID g : e.getValue()) {
            ids.add(g.toString());
         }
         guardObj.add(e.getKey().toString(), ids);
      }
      root.add("guardIds", guardObj);
      JsonObject wardenObj = new JsonObject();
      for (Map.Entry<UUID, UUID> e : wardens.entrySet()) {
         wardenObj.addProperty(e.getKey().toString(), e.getValue().toString());
      }
      root.add("wardenIds", wardenObj);
      JsonObject breakoutObj = new JsonObject();
      for (Map.Entry<UUID, Integer> e : breakouts.entrySet()) {
         breakoutObj.addProperty(e.getKey().toString(), e.getValue());
      }
      root.add("breakouts", breakoutObj);

      JsonArray bricks = new JsonArray();
      for (Map.Entry<BlockPos, LooseBrick> e : looseBricks.entrySet()) {
         JsonObject o = new JsonObject();
         o.add("pos", posToJson(e.getKey()));
         o.addProperty("owner", e.getValue().owner().toString());
         o.addProperty("original", stateToText(e.getValue().original()));
         bricks.add(o);
      }
      root.add("looseBricks", bricks);
   }

   public static void load(JsonObject root) {
      heat.clear();
      contracts.clear();
      sentences.clear();
      cuffs.clear();
      breakouts.clear();
      shakeGrace.clear();
      escapes.clear();
      executions.clear();
      prisonCooldown.clear();
      looseBricks.clear();
      gateBars.clear();
      guards.clear();
      wardens.clear();
      breakouts.clear();
      // A fresh world is not mid-shutdown: the flag is armed by the lifecycle hook and cleared
      // here, so "did this disconnect come from a stop or from a player quitting" is answered
      // correctly on every boot.
      serverStopping = false;
      // The block's clock, resumed where the last session left it. A file written before the block
      // kept one has no base, and its deadlines are absolute ticks from a process that is gone:
      // the only answer they can still give is "hours". Those lock-outs are dropped rather than
      // read - a wait is a punishment, not a life sentence - see the clock and prisonCooldown.
      boolean clocked = root.has("blockClock");
      clockBase = clocked ? root.get("blockClock").getAsLong() : 0L;
      lastKnownTick = clockBase;
      if (root.has("heat") && root.get("heat").isJsonObject()) {
         for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("heat").entrySet()) {
            try {
               heat.put(UUID.fromString(e.getKey()), Math.max(0, Math.min(HEAT_MAX, e.getValue().getAsInt())));
            } catch (Exception ignored) {
            }
         }
      }
      if (root.has("contracts") && root.get("contracts").isJsonObject()) {
         for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("contracts").entrySet()) {
            try {
               UUID uuid = UUID.fromString(e.getKey());
               List<Contract> list = new ArrayList<>(3);
               for (JsonElement el : e.getValue().getAsJsonArray()) {
                  JsonObject o = el.getAsJsonObject();
                  Kind kind;
                  try {
                     kind = Kind.valueOf(o.get("kind").getAsString());
                  } catch (Exception ex) {
                     continue;
                  }
                  Contract c = new Contract(kind, o.get("target").getAsInt(), o.get("reward").getAsLong());
                  c.progress = o.get("progress").getAsInt();
                  list.add(c);
               }
               if (!list.isEmpty()) {
                  contracts.put(uuid, list);
               }
            } catch (Exception ignored) {
            }
         }
      }
      loadTransient(root);
      if (!clocked) {
         // A file from before the block kept its own clock: see the base read above. Its lock-outs
         // are ticks from a process that is gone, and reading one back is what "the block takes you
         // back in 20000s" was.
         prisonCooldown.clear();
      }
   }

   // ------------------------------------------------------------------
   // Persistence: the situations
   // ------------------------------------------------------------------

   /**
    * Reads back the half of the block that is a *situation* rather than a record.
    *
    * <p>Each map is rebuilt from the same numbers the live code uses, and each entry is its own
    * try: one corrupt sentence in the file must not cost every other prisoner theirs. The gate bars
    * are derived from the restored break-outs rather than stored separately, so the live gate map
    * can never disagree with the runs that own it.
    */
   private static void loadTransient(JsonObject root) {
      if (root.has("sentences") && root.get("sentences").isJsonObject()) {
         for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("sentences").entrySet()) {
            try {
               JsonObject o = e.getValue().getAsJsonObject();
               long until = o.get("until").getAsLong();
               boolean cell = o.get("cell").getAsBoolean();
               long total = o.has("total") ? o.get("total").getAsLong()
                  : (cell ? CELL_TICKS : SOLITARY_TICKS);
               sentences.put(UUID.fromString(e.getKey()), new Sentence(until, cell, Math.max(1L, total)));
            } catch (Exception ignored) {
            }
         }
      }
      if (root.has("cuffs") && root.get("cuffs").isJsonObject()) {
         for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("cuffs").entrySet()) {
            try {
               JsonObject o = e.getValue().getAsJsonObject();
               Cuffs c = new Cuffs();
               for (JsonElement slot : o.getAsJsonArray("locks")) {
                  c.locks.add(slot.getAsInt());
               }
               c.step = o.get("step").getAsInt();
               c.until = o.get("until").getAsLong();
               c.slips = o.get("slips").getAsInt();
               c.hard = o.get("hard").getAsBoolean();
               c.clickTicks = o.get("click").getAsLong();
               if (!c.locks.isEmpty()) {
                  cuffs.put(UUID.fromString(e.getKey()), c);
               }
            } catch (Exception ignored) {
            }
         }
      }
      if (root.has("executions") && root.get("executions").isJsonObject()) {
         for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("executions").entrySet()) {
            try {
               JsonObject o = e.getValue().getAsJsonObject();
               Execution x = new Execution(posFromJson(o.get("chair")), posFromJson(o.get("vent")));
               x.endTick = o.get("end").getAsLong();
               x.nextShockTick = o.get("next").getAsLong();
               x.crawlUntil = o.get("crawl").getAsLong();
               x.barsBroken = o.get("broken").getAsInt();
               x.guardsOut = o.get("guardsOut").getAsBoolean();
               x.ventDirt = o.has("ventDirt") ? o.get("ventDirt").getAsInt() : 0;
               x.ventOpen = o.has("ventOpen") && o.get("ventOpen").getAsBoolean();
               x.ventAlertTick = o.has("ventAlert") ? o.get("ventAlert").getAsLong() : 0L;
               for (JsonElement g : o.getAsJsonArray("ventGravel")) {
                  x.ventGravel.add(posFromJson(g));
               }
               for (JsonElement g : o.getAsJsonArray("ventSeal")) {
                  x.ventSeal.add(posFromJson(g));
               }
               for (JsonElement b : o.getAsJsonArray("bars")) {
                  x.bars.add(posFromJson(b));
               }
               for (JsonElement b : o.getAsJsonArray("allBars")) {
                  x.allBars.add(posFromJson(b));
               }
               carvedFromJson(o.get("carved"), x.carved);
               for (JsonElement g : o.getAsJsonArray("guards")) {
                  try {
                     x.guards.add(UUID.fromString(g.getAsString()));
                  } catch (Exception ignored) {
                  }
               }
               executions.put(UUID.fromString(e.getKey()), x);
            } catch (Exception ignored) {
            }
         }
      }
      if (root.has("escapes") && root.get("escapes").isJsonObject()) {
         for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("escapes").entrySet()) {
            try {
               JsonObject o = e.getValue().getAsJsonObject();
               net.minecraft.core.Direction dir = net.minecraft.core.Direction.byName(o.get("dir").getAsString());
               if (dir == null) {
                  continue;
               }
               BlockPos start = posFromJson(o.get("start"));
               BlockPos end = posFromJson(o.get("end"));
               Escape x = new Escape(dir, start, end, o.get("endTick").getAsLong());
               x.key = o.get("key").getAsBoolean();
               x.gateOpen = o.get("gateOpen").getAsBoolean();
               x.nextWardenTick = o.get("nextWarden").getAsLong();
               for (JsonElement b : o.getAsJsonArray("gate")) {
                  x.gate.add(posFromJson(b));
               }
               carvedFromJson(o.get("carved"), x.carved);
               x.zone = zoneFor(end, dir);
               UUID uuid = UUID.fromString(e.getKey());
               escapes.put(uuid, x);
               for (BlockPos bar : x.gate) {
                  gateBars.put(bar, uuid);
               }
            } catch (Exception ignored) {
            }
         }
      }
      if (root.has("cooldown") && root.get("cooldown").isJsonObject()) {
         for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("cooldown").entrySet()) {
            try {
               prisonCooldown.put(UUID.fromString(e.getKey()), e.getValue().getAsLong());
            } catch (Exception ignored) {
            }
         }
      }
      if (root.has("guardIds") && root.get("guardIds").isJsonObject()) {
         for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("guardIds").entrySet()) {
            try {
               UUID owner = UUID.fromString(e.getKey());
               Set<UUID> ids = new HashSet<>();
               for (JsonElement g : e.getValue().getAsJsonArray()) {
                  try {
                     ids.add(UUID.fromString(g.getAsString()));
                  } catch (Exception ignored) {
                  }
               }
               if (!ids.isEmpty()) {
                  guards.put(owner, ids);
               }
            } catch (Exception ignored) {
            }
         }
      }
      if (root.has("wardenIds") && root.get("wardenIds").isJsonObject()) {
         for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("wardenIds").entrySet()) {
            try {
               wardens.put(UUID.fromString(e.getKey()), UUID.fromString(e.getValue().getAsString()));
            } catch (Exception ignored) {
            }
         }
      }
      if (root.has("breakouts") && root.get("breakouts").isJsonObject()) {
         for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("breakouts").entrySet()) {
            try {
               breakouts.put(UUID.fromString(e.getKey()), Math.max(0, e.getValue().getAsInt()));
            } catch (Exception ignored) {
            }
         }
      }
      if (root.has("looseBricks") && root.get("looseBricks").isJsonArray()) {
         for (JsonElement el : root.getAsJsonArray("looseBricks")) {
            try {
               JsonObject o = el.getAsJsonObject();
               looseBricks.put(
                  posFromJson(o.get("pos")),
                  new LooseBrick(UUID.fromString(o.get("owner").getAsString()), stateFromText(o.get("original").getAsString()))
               );
            } catch (Exception ignored) {
            }
         }
      }
   }

   /** A position as {@code [x,y,z]}, the one shape every map above writes. */
   private static JsonArray posToJson(BlockPos pos) {
      JsonArray a = new JsonArray();
      a.add(pos.getX());
      a.add(pos.getY());
      a.add(pos.getZ());
      return a;
   }

   private static BlockPos posFromJson(JsonElement el) {
      JsonArray a = el.getAsJsonArray();
      return new BlockPos(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt());
   }

   /**
    * A block state as its registry name, restored as the default state.
    *
    * <p>Everything these maps replace is plain - the void, and the mine's own stone and ore - so a
    * name round-trip is exact here and does not need the property map. It is also the shape the
    * disaster manager already uses for the same job, so there is one convention for saving a
    * block rather than two.
    */
   private static String stateToText(BlockState state) {
      return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
   }

   private static BlockState stateFromText(String name) {
      try {
         return net.minecraft.core.registries.BuiltInRegistries.BLOCK
            .get(net.minecraft.resources.Identifier.parse(name))
            .map(h -> h.value().defaultBlockState())
            .orElse(Blocks.AIR.defaultBlockState());
      } catch (Throwable t) {
         return Blocks.AIR.defaultBlockState();
      }
   }

   private static JsonArray carvedToJson(Map<BlockPos, BlockState> carved) {
      JsonArray out = new JsonArray();
      for (Map.Entry<BlockPos, BlockState> e : carved.entrySet()) {
         JsonArray row = new JsonArray();
         row.add(e.getKey().getX());
         row.add(e.getKey().getY());
         row.add(e.getKey().getZ());
         row.add(stateToText(e.getValue()));
         out.add(row);
      }
      return out;
   }

   private static void carvedFromJson(JsonElement el, Map<BlockPos, BlockState> into) {
      if (el == null || !el.isJsonArray()) {
         return;
      }
      for (JsonElement row : el.getAsJsonArray()) {
         try {
            JsonArray a = row.getAsJsonArray();
            into.put(
               new BlockPos(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt()),
               stateFromText(a.get(3).getAsString())
            );
         } catch (Exception ignored) {
         }
      }
   }

   /**
    * Armed by the shutdown lifecycle hook, so a disconnect can tell "the server is stopping" from
    * "the player quit": a quit walks them out of the block (the softlock guard), a stop keeps them
    * in it so the next boot resumes the sentence.
    */
   private static boolean serverStopping = false;

   public static void markServerStopping() {
      serverStopping = true;
   }

   public static boolean isServerStopping() {
      return serverStopping;
   }
}
