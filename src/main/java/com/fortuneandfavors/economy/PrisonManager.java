package com.fortuneandfavors.economy;

import com.fortuneandfavors.util.Chat;
import com.fortuneandfavors.util.InventoryHelper;
import com.fortuneandfavors.util.JsonUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.Map.Entry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayer.RespawnConfig;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Unit;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.level.storage.LevelData.RespawnData;
import net.minecraft.world.phys.Vec3;

/**
 * Prison gamemode: a separate dimension with its own currency and progression.
 * You start at rank A with a stone pickaxe in the shallowest mine tier and rank
 * up all the way to Z, unlocking deeper, richer floors. Just like duels, your
 * personal inventory is stashed when you enter - you only play with the prison
 * kit (unbreakable pickaxe + the Nether Star that is always in hotbar slot 9).
 * Each tier is a real layered mine: a walkway over a deep ore slab that gets
 * richer the deeper you dig. Mining drops blocks, /sell (and the Prison Menu)
 * turn them into prison cash at prison prices, and prison cash converts into
 * main-server cash. Pickaxe upgrades (efficiency/fortune) are bought with
 * prison cash. The mine floors regenerate so the grind never runs out.
 */
public final class PrisonManager {
   public static final ResourceKey<Level> PRISON_DIM = ResourceKey.create(
      Registries.DIMENSION, Identifier.fromNamespaceAndPath("fortuneandfavors", "prison")
   );
   public static final int RANKS = 26; // A..Z
   public static final long CONVERT_RATE = 2L; // 2 prison cash -> 1 main cash
   public static final int PICK_MAX = 5;
   /** Percent a rank adds to what this prisoner's ore sells for - see {@link #trusteePercent}. */
   private static final int TRUSTEE_PERCENT_PER_RANK = 2;
   /** Prison sword tiers: 0 = iron, 1 = diamond, 2 = netherite. */
   public static final int SWORD_MAX = 2;
   /**
    * Where each rank tier's floor sits.
    *
    * <p>The gap is twenty and not the sixteen it used to be, and that is the whole of the mine's
    * size problem: a tier's room is the ore slab (six deep), the sealed band under it (six) and the
    * headroom over the walkway, and the three of them have to fit between one floor and the next.
    * At three blocks of headroom the arithmetic came out at sixteen and left no room to stand up
    * in; see {@link #ROOM_HEADROOM}.
    */
   private static final int[] TIER_FLOOR_Y = {97, 77, 57, 37, 17, -3, -23, -43};
   /**
    * Half the width of a mine room. The seam is a (2*16+1) square - 33x33 of ore - where it used to
    * be 25x25, and the extra room is the point: a bigger face to work, a shorter walk to a fresh
    * one, and space for the powder magazine and the crane that make a floor read as a place.
    */
   private static final int ROOM_HALF = 16;
   /** Air over the walkway, in blocks. Three was a corridor; five is a room. */
   private static final int ROOM_HEADROOM = 5;
   /** The level the intake platform, the processing pad and the cell block are built on. */
   public static final int SURFACE_Y = 112;
   /** The hole: one boxed cell, high above the mine so nobody wanders into it. */
   public static final int SOLITARY_X = 0;
   public static final int SOLITARY_Y = 130;
   public static final int SOLITARY_Z = 80;
   /** The cell block: a corridor with one room off it per rung of the ladder. */
   private static final int CELL_CORRIDOR_X = 24;
   private static final int CELL_FIRST_Z = -12;
   private static final int CELL_SPACING = 6;
   private static final String STAR_TAG = "ff_prison_star";
   private static final Map<UUID, Integer> ranks = new HashMap<>();
   private static final Map<UUID, Long> wallets = new HashMap<>();
   private static final Map<UUID, Integer> pickLevels = new HashMap<>();
   private static final Map<UUID, Integer> swordLevels = new HashMap<>();
   private static final Map<UUID, ItemStack[]> stashed = new HashMap<>();
   /**
    * The locker's layout, which is fixed no matter how wide the game's own inventory container
    * happens to be: the thirty-six carried slots, then the four armour slots in
    * {@link #ARMOUR_SLOTS} order, then the off hand. Five slots of the player's container are the
    * armour and the off hand themselves, and a build may report a couple more on top of that (the
    * body and saddle slots an animal would use), so reading the container's own index for a piece
    * is not the same as naming the piece - hence a layout of the block's own, written and read in
    * one place each.
    */
   private static final int CARRIED_SLOTS = 36;
   private static final int ARMOUR_BASE = CARRIED_SLOTS;
   private static final int OFFHAND_SLOT = ARMOUR_BASE + 4;
   private static final int STASH_SLOTS = OFFHAND_SLOT + 1;
   /** How far up the cell ladder a prisoner has bought. See {@link #cellUpgrade}. */
   private static final Map<UUID, Integer> cellLevels = new HashMap<>();
   /** Prison tokens: earned by declaring a haul, spent at the exchange. */
   private static final Map<UUID, Long> tokens = new HashMap<>();
   private static final Map<UUID, ResourceKey<Level>> lastDim = new HashMap<>();
   /** Where each player was standing when they entered prison - leave() returns here. */
   private static final Map<UUID, double[]> prisonOrigin = new HashMap<>();
   /** Which fight each prisoner is running in the Pit, how far through, and when the next round drops. */
   private static final Map<UUID, Pit> pits = new HashMap<>();
   /**
    * The bodies a run's current round is made of, by UUID.
    *
    * <p>The round used to be judged by sweeping the arena box for monsters, which is a fact about
    * the *world* and not about the fight: one body that wandered out of the box, one that spawned
    * inside a wall and suffocated, one that took a shove over the rim, and the count was wrong for
    * the rest of the run - which is how a Gauntlet round with three bodies still standing decided it
    * had been cleared, and how a round whose remaining bodies were somewhere the sweep could not see
    * them never dropped the next one. Tracking the bodies the round actually sent is the only version
    * of that question with an answer.
    */
   private static final Map<UUID, Set<UUID>> pitBodies = new HashMap<>();
   /** Runs whose current round has been sent. A run that has not been sent yet is spawned, not paid. */
   private static final Set<UUID> pitSent = new HashSet<>();
   /** The tier each prisoner was last seen working, so the floor they leave can be put back. */
   private static final Map<UUID, Integer> lastTier = new HashMap<>();
   /** The tag a prison uniform carries, so an issued one can be told from a coat brought in. */
   private static final String UNIFORM_TAG = "ff_prison_uniform";
   private static final EquipmentSlot[] ARMOUR_SLOTS = {
      EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
   };

   /**
    * One run in the Pit.
    *
    * <p>{@code nextRoundTick} of nought means the current round is still being fought; a non-zero
    * value means the round was cleared at that moment and the next one drops when the clock reaches
    * it. That is the same shape the old arena used with two parallel maps, and it is one record now
    * for a reason: the two maps could - and did - disagree about whether a run was live, and a
    * prisoner whose wave counter survived a logout was paid for a fight they were not in.
    */
   private record Pit(PitMode mode, int round, long nextRoundTick) {
   }

   /**
    * The five fights of the Pit.
    *
    * <p>The arena was ten identical waves of unarmed zombies, so the only thing a prisoner chose by
    * walking in was whether they wanted money - and the answer to "what fight do you want" was a
    * number. A mode says what the shape of the fight is before anybody is in it: single opponents,
    * a long grind, a name on somebody's head, the man himself, or the week's champion.
    *
    * <p>Each one is described by three numbers rather than by bespoke code - how many rounds, how
    * many bodies a round sends, how big those bodies are built - so a sixth mode is a row in this
    * table and not another branch in the tick loop.
    */
   public enum PitMode {
      DUEL("1v1", "five single opponents, each one bigger than the last", 5, 3),
      GAUNTLET("Gauntlet", "ten escalating waves - the long fight, and the old arena", 10, 2),
      BOUNTY("Bounty", "three enforcers sent for the block's most wanted head", 3, 5),
      WARDEN("Warden Challenge", "the man himself, once, for real money", 1, 25),
      CHAMPION("Champion", "this week's champion, and they keep the name", 1, 40);

      public final String label;
      public final String blurb;
      /** Rounds in a completed run. */
      public final int rounds;
      /** Tokens paid for clearing one round. */
      public final int tokensPerRound;

      PitMode(String label, String blurb, int rounds, int tokensPerRound) {
         this.label = label;
         this.blurb = blurb;
         this.rounds = rounds;
         this.tokensPerRound = tokensPerRound;
      }

      /** How many bodies one round sends. The Warden and the Champion send themselves. */
      public int bodies(int round) {
         return switch (this) {
            case DUEL -> 1;
            case GAUNTLET -> 3 + round;
            case BOUNTY -> round;
            case WARDEN, CHAMPION -> 1;
         };
      }

      /** The wave a round's bodies are built at - how big and how well equipped they come out. */
      public int waveOf(int round) {
         return switch (this) {
            case DUEL -> round * 2;
            case GAUNTLET -> round;
            case BOUNTY -> 5 + round;
            case WARDEN, CHAMPION -> 10;
         };
      }

      /** What clearing one round pays, in prison cash, before any bounty scale. */
      public long cashFor(int round) {
         return switch (this) {
            case DUEL -> 250L * round * round;
            case GAUNTLET -> 100L * round * round;
            case BOUNTY -> 400L * round * round;
            case WARDEN -> 20_000L;
            case CHAMPION -> 50_000L;
         };
      }

      /** What finishing the whole run pays, in tokens - the reason to fight rather than mine. */
      public long clearBonus() {
         return (long)tokensPerRound * rounds;
      }
   }

   /** The title the week's Champion fight hands out. */
   public static final String CHAMPION_TITLE = "Pit Champion";

   /**
    * The names the weekly champion rotates through.
    *
    * <p>A champion that is only a stat block is a wall with a health bar. A name is what makes the
    * week's fight a thing a prisoner can say they beat - and because the name is a function of the
    * week number rather than of a save file, every server, every restart and every player sees the
    * same champion in the same week without anything being stored.
    */
   private static final String[] CHAMPIONS = {
      "Ashgrove the Unpaid", "Kolv the Widowmaker", "Nine-Iron Hask", "Marrow", "Sergeant Vale", "the Undertaker"
   };

   /** Weeks since the epoch, which is what the champion rotation is a function of. */
   public static long weekNumber() {
      return java.time.LocalDate.now(java.time.ZoneId.of("America/New_York")).toEpochDay() / 7L;
   }

   public static String championForWeek(long week) {
      return CHAMPIONS[(int)java.lang.Math.floorMod(week, CHAMPIONS.length)];
   }

   public static String championName() {
      return championForWeek(weekNumber());
   }

   /**
    * What a Bounty run pays on top of its own cash, as a function of the block's Heat.
    *
    * <p>The bounty is on a head, and the block's business is which heads are hot. A prison where
    * nobody has Heat is a prison with nothing to collect, and a prison at lockdown is one where the
    * enforcers are worth sending. Linear and capped in practice by the meter, so it cannot run away.
    */
   public static double bountyScale(int topHeat) {
      return 1.0 + Math.max(0, Math.min(PrisonCellblock.HEAT_MAX, topHeat)) / 50.0;
   }
   private static final int ARENA_X = 80;
   private static final int ARENA_Y = 100;
   private static final int ARENA_Z = 0;
   private static final int ARENA_HALF = 8;
   /** The four cardinal gates the arena's horde comes out of, as unit offsets. Gate
    *  positions, gate frames and spawn points all read this one list, so a fifth gate
    *  cannot be added to one of them and forgotten in another. */
   private static final int[][] ARENA_GATES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
   /** The fishing harbour's centre. Far enough west of the pit that the two are
    *  separate places, close enough that the walk between them is one corridor. */
   private static final int POND_X = ARENA_X - 28;
   /** How far the harbour bench reaches in x from {@link #POND_X} - it is sized to
    *  meet the arena's west wall exactly, because the prison world is a void and a
    *  one-block gap between them is a fall out of the world. */
   private static final int POND_HALF_X = 13;
   private static final int POND_HALF_Z = 9;
   /** Half the water's width. The pool is (2*POOL_HALF+1) square. */
   private static final int POOL_HALF = 6;
   private static Path dataFile;
   private static boolean built = false;
   private static final Random RANDOM = new Random();

   private PrisonManager() {
   }

   // ------------------------------------------------------------------
   // Sectors and the security boundary
   // ------------------------------------------------------------------

   /**
    * The six sectors of the block, shallow to deep.
    *
    * <p>A sector is a <i>place</i> rather than a number, and the rename from "level 1..8" is not
    * cosmetic. The old ladder was eight anonymous floors, so the only thing that distinguished
    * where a prisoner was standing was a number in chat, and the depth that separated them meant
    * nothing: the rock between two floors was ordinary stone, and a prisoner who dug through it had
    * skipped a gate the prison meant to charge for. What was missing was not a wall but an
    * <i>idea of a place</i> - there is a yard, and a quarry, and a foundry, and the works below are
    * the block's own business and not yours. Nothing is mineable outside a sector's own seam, and
    * that is the whole of how progression is controlled: see {@link #isBoundary}.
    *
    * <p>{@link #heat} is the second half, and it is what makes depth a <i>decision</i> rather than a
    * reward. The same rich block pays more down here and draws the guards faster, so the deeper
    * sectors are the ones a prisoner has to be able to afford to be caught in.
    */
   public enum Sector {
      YARD("Yard", "§7", "the shallow spoil heaps the intake is put to work in", 0.7),
      QUARRY("Quarry", "§6", "cut stone, and the first veins worth smuggling", 1.0),
      FURNACE("Furnace", "§c", "a foundry cut over a magma duct, and it is always warm", 1.3),
      CONTAINMENT("Containment", "§a", "sealed cells and cold seams behind steel", 1.6),
      DEEP_CELL("Deep Cell", "§5", "galleries the lights do not reach the end of", 2.0),
      MAXIMUM_SECURITY("Maximum Security", "§4", "the bottom of the block, where the guards do not go alone", 2.6);

      public final String name;
      public final String colour;
      /** One line of what this place is, for the gate and the menu. */
      public final String sign;
      /** What this sector multiplies the Heat of a rich block by. Deeper is hotter. */
      public final double heat;

      Sector(String name, String colour, String sign, double heat) {
         this.name = name;
         this.colour = colour;
         this.sign = sign;
         this.heat = heat;
      }
   }

   /**
    * Which sector each of the eight physical floors belongs to.
    *
    * <p>Six sectors over eight floors, so Containment and the Deep Cell each own an upper and a
    * lower gallery. Two floors to a sector is not a compromise: a place with two levels is what
    * makes it a place, and the rank gate between them is inside the sector rather than at its door.
    */
   private static final Sector[] TIER_SECTOR = {
      Sector.YARD, Sector.QUARRY, Sector.FURNACE, Sector.CONTAINMENT,
      Sector.CONTAINMENT, Sector.DEEP_CELL, Sector.DEEP_CELL, Sector.MAXIMUM_SECURITY
   };

   /**
    * How many layers of a sector's own seam a prisoner may actually mine: the walkway itself and
    * the rock down to {@code floorY - (SEAM_DEPTH - 1)}.
    *
    * <p>It used to be six, and six reads as "the wall is three blocks down": the seam is the only
    * income a floor has, and a shelf you are done with in a minute is not a mine. Nine keeps the
    * rock between two sectors sealed exactly as before - what seals it is {@link #isBoundary}, a
    * refusal, and not the thickness of the wall - while giving a vertical shaft somewhere to go.
    */
   public static final int SEAM_DEPTH = 9;

   /**
    * How deep the sealed band under each seam runs.
    *
    * <p>Five, and it no longer doubles as the mineable depth (that is {@link #SEAM_DEPTH} now). The
    * band is the rock the block owns, sealed by {@link #isBoundary} rather than by being bedrock, so
    * its thickness only has to reach the ceiling of the sector below - there is no unsealed rung
    * anywhere between this seam's underside and the next floor's roof.
    */
   public static final int BOUNDARY_DEPTH = 5;

   private static final Map<UUID, Long> boundaryNag = new HashMap<>();

   /** The sector a rank's own mine is in. */
   public static Sector sectorOf(int rank) {
      return TIER_SECTOR[Math.max(0, Math.min(TIER_SECTOR.length - 1, tierOf(rank)))];
   }

   /** The sector a depth belongs to, or null above the works and below the bottom of them. */
   public static Sector sectorAtY(int y) {
      for (int tier = 0; tier < TIER_FLOOR_Y.length; tier++) {
         int floorY = TIER_FLOOR_Y[tier];
         if (y <= floorY + ROOM_HEADROOM + 1 && y >= floorY - SEAM_DEPTH - BOUNDARY_DEPTH + 1) {
            return TIER_SECTOR[tier];
         }
      }
      return null;
   }

   /** The sector a prisoner is standing in, or null if they are somewhere the block does not own. */
   public static Sector sectorOf(ServerPlayer player) {
      return player == null ? null : sectorAtY((int)Math.floor(player.getY()));
   }

   /** What this prisoner's own sector multiplies Heat by. */
   public static double sectorHeatFactor(ServerPlayer player) {
      Sector sector = sectorOf(player);
      return sector == null ? 1.0 : sector.heat;
   }

   /**
    * Whether a cell is part of the works rather than part of the seam.
    *
    * <p>The rule in one line: inside a sector a prisoner may mine the ore and nothing else. The
    * band under the seam, the four walls beside it and the ceiling over it are the block's own
    * structure, and they are refused by the break funnel with a word rather than by being bedrock -
    * a wall somebody can see and cannot touch reads as a rule, and bedrock reads as a map that ran
    * out. It is also what makes the ladder mean something: the rock between two sectors is protected
    * for its whole thickness, so there is no dig that reaches a sector a prisoner has not ranked
    * into, and no shape of tunnel that goes around the gate.
    *
    * <p>Deliberately geometry rather than a list of positions: the floors are rebuilt every few
    * seconds as prisoners mine, and a set of positions would have to be rebuilt with them.
    */
   public static boolean isBoundary(BlockPos pos) {
      if (pos == null) {
         return false;
      }
      int ax = Math.abs(pos.getX());
      int az = Math.abs(pos.getZ());
      if (ax > ROOM_HALF || az > ROOM_HALF) {
         return false;
      }
      int y = pos.getY();
      for (int tier = 0; tier < TIER_FLOOR_Y.length; tier++) {
         int floorY = TIER_FLOOR_Y[tier];
         if (y > floorY + ROOM_HEADROOM + 1 || y < floorY - SEAM_DEPTH - BOUNDARY_DEPTH + 1) {
            continue;
         }
         // The sealed band under the seam, all the way down to the ceiling of the sector below.
         if (y <= floorY - SEAM_DEPTH) {
            return true;
         }
         // The ceiling, and the four walls from the ceiling down to the band. The seam itself -
         // everything inside those four walls, from the walkway down {@link #SEAM_DEPTH} blocks - is
         // the income.
         return y > floorY + ROOM_HEADROOM || ax == ROOM_HALF || az == ROOM_HALF;
      }
      return false;
   }

   /**
    * Whether this break is one the block refuses, without saying so.
    *
    * <p>Split from the refusal itself so the decision can be read on its own: the sentence and the
    * sound below are things a player hears, and a rule that can only be tested by listening to it
    * is a rule that gets tested by not listening closely.
    *
    * <p>Three kinds of break answer yes, and they are three different rules wearing one sentence.
    * The sealed works ({@link #isBoundary}) are the block's own rock, and the rooms it shuts a
    * prisoner into ({@link #isHoldingArea}) are the ones the pickaxe must not open. The combat zone
    * ({@link #isArenaGround}) is neither: it is the block's building, standing in the open, where
    * the only thing a pickaxe can do is put a hole in the floor of the place the fights happen.
    */
   public static boolean isSecurityBreak(ServerPlayer player, BlockPos pos) {
      if (player == null || !isInPrison(player)) {
         return false;
      }
      // The maximum-security cell's gravel is the deliberate exception: the duct under it is a way
      // out the wing offers, and a rule that refused the dig would leave the vent as decoration.
      if (PrisonCellblock.isExecutionVentGravel(pos)) {
         return false;
      }
      return isBoundary(pos) || isHoldingArea(pos) || isArenaGround(pos);
   }

   /**
    * Whether a position is part of the combat zone: the walled arena the Pit is fought in.
    *
    * <p>Geometry rather than a list of positions, for the same reason {@link #isBoundary} is: the
    * arena is built over the void, so a floor with a hole in it is not somebody's shortcut, it is a
    * fall out of the world. Walls, floor, roof and the apron inside the wall line are all one place,
    * and a prisoner standing in the yard can reach every block of it - which is exactly why it was
    * diggable before: nothing about the arena is inside a sector, so none of the works rules ever
    * looked at it.
    */
   public static boolean isArenaGround(BlockPos pos) {
      if (pos == null) {
         return false;
      }
      int dx = pos.getX() - ARENA_X;
      int dz = pos.getZ() - ARENA_Z;
      return Math.abs(dx) <= ARENA_WALL_HALF
         && Math.abs(dz) <= ARENA_WALL_HALF
         && pos.getY() >= ARENA_Y - 4
         && pos.getY() <= ARENA_Y + 9;
   }

   /**
    * Whether a position is inside one of the rooms the block *puts* a prisoner in.
    *
    * <p>The hole and the cells are the two places a prisoner is not doing anything by choice, so
    * they are the two places where the pickaxe in their hand is a way out of the punishment rather
    * than a way through the rock. Solitary used to be dug out of - the walls were ordinary deepslate
    * bricks and every prisoner carries a pickaxe - which made the sentence advisory. The rule is now
    * the same rule the sector walls answer to, applied to the rooms the block owns outright, and it
    * is geometry for the same reason {@link #isBoundary} is: the rooms are rebuilt on every start.
    */
   public static boolean isHoldingArea(BlockPos pos) {
      if (pos == null) {
         return false;
      }
      // The execution room's own walls, floor and ceiling: the same rule as the hole's, so a
      // prisoner cannot dig out of the sentence. Its bars are deliberately exempt, because mining
      // them is one of the three ways the room offers out.
      if (PrisonCellblock.isExecutionStructure(pos)) {
         return true;
      }
      int y = pos.getY();
      // The Processing room and its corridor: the checkpoint is the block's own works now, and a
      // prisoner who could break its floor would be digging their way out of the surface. Same rule
      // the hole and the cells answer to.
      if (pos.getX() >= PROCESS_X - PROCESS_ROOM_HALF && pos.getX() <= -10
         && Math.abs(pos.getZ() - PROCESS_Z) <= PROCESS_ROOM_HALF + 1
         && y >= PROCESS_Y - 2 && y <= PROCESS_Y + PROCESS_ROOM_HEADROOM + 1) {
         return true;
      }
      if (Math.abs(pos.getX() - SOLITARY_X) <= 4
         && Math.abs(pos.getZ() - SOLITARY_Z) <= 4
         && y >= SOLITARY_Y - 2 && y <= SOLITARY_Y + 5) {
         return true;
      }
      // The hole's hallway: the roofed corridor in front of the barred wall, so the walk out of the
      // hole is as sealed as the hole itself.
      if (pos.getX() >= SOLITARY_X - 13 && pos.getX() <= SOLITARY_X - 4
         && Math.abs(pos.getZ() - SOLITARY_Z) <= 4
         && y >= SOLITARY_Y - 2 && y <= SOLITARY_Y + 4) {
         return true;
      }
      return Math.abs(pos.getX() - CELL_CORRIDOR_X + 2) <= 6
         && Math.abs(pos.getZ() - (CELL_FIRST_Z + 2 * CELL_SPACING)) <= 16
         && y >= SURFACE_Y - 3 && y <= SURFACE_Y + 4;
   }

   /**
    * Whether this break is the block telling a prisoner no.
    *
    * <p>Called from the same funnel that refuses a duel arena's walls, before vanilla has been told
    * the block is gone, so the refusal is a refusal and not a ghost block. The message is on a
    * cooldown of its own: a prisoner who digs at a wall digs at it thirty times a second, and the
    * one thing that makes a rule feel like a wall is that it says the same sentence once.
    *
    * @return true when the break must be cancelled
    */
   public static boolean refuseBreak(ServerPlayer player, BlockPos pos) {
      if (!isSecurityBreak(player, pos)) {
         return false;
      }
      long now = PrisonCellblock.clock(player.level());
      if (boundaryNag.getOrDefault(player.getUUID(), 0L) <= now) {
         boundaryNag.put(player.getUUID(), now + 30L);
         Sector sector = sectorOf(player);
         Chat.raw(
            player,
            "§4§lSECURITY BLOCK§r §7- this area is restricted. §fThe works are the block's"
               + "§7 and not yours; only the seam pays."
               + (isHoldingArea(pos) ? " §7And you are not digging your way out of here." : "")
               + (isArenaGround(pos) ? " §7And the arena is where the fights are - not a quarry." : "")
         );
         if (sector != null && sector != Sector.values()[Sector.values().length - 1]) {
            Sector next = Sector.values()[sector.ordinal() + 1];
            Chat.raw(player, "§8Rank up to be let down into §f" + next.name + "§8 - " + next.sign + ".");
         }
         player.level().playSound(
            null, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
            SoundEvents.NOTE_BLOCK_BASS.value(), SoundSource.BLOCKS, 0.7F, 0.5F
         );
      }
      return true;
   }

   /** The sector gate line a prisoner reads on the way in, or on the way back to their floor. */
   public static String sectorLine(UUID uuid) {
      Sector sector = sectorOf(rankOf(uuid));
      return "§8You are working §f" + sector.colour + sector.name + "§8 - " + sector.sign + ".";
   }

   // ------------------------------------------------------------------
   // Cells
   // ------------------------------------------------------------------

   /**
    * The cell ladder: the one thing in here a prisoner buys for themselves.
    *
    * <p>Everything else in the block is a tool or a rank, and both of them are about the mine. A
    * cell is the only purchase that changes what happens when a prisoner <i>stops</i> - and Heat
    * cooling is exactly the right thing to hang off it, because the answer to a hot record is
    * already "go and do something legal for a while", and there was nowhere legal to go. A better
    * cell is somewhere to go: Heat bleeds off faster behind your own door, up to three times as
    * fast at the top of the ladder, which is the difference between a prisoner who can afford to
    * run their Heat down between shifts and one who has to sell a haul to do it.
    */
   public static final int CELL_MAX = 4;
   /**
    * Heat cools on this many ticks per point in the worst cell, minus twenty a rung.
    *
    * <p>It was thirty - a point every 1.5 seconds, so a prisoner could dig at full Heat, stand
    * still for two minutes and be a stranger to the block again. Heat is the only thing making the
    * mine a decision, and a record that clears itself while the prisoner reads a menu is not a
    * record: the cooling rate is eight seconds a point at the bottom rung now, and the cell ladder
    * is what buys it back. Selling a haul is still the fast way out and always will be.
    */
   private static final int CELL_COOL_BASE_TICKS = 20 * 8;
   private static final int CELL_COOL_PER_LEVEL = 20;
   private static final long[] CELL_COSTS = {5_000L, 20_000L, 60_000L, 150_000L};

   public static int cellLevelOf(UUID uuid) {
      return Math.max(0, Math.min(CELL_MAX, cellLevels.getOrDefault(uuid, 0)));
   }

   /** Puts a prisoner on a rung of the cell ladder, so the rate can be read per rung. */
   public static void cellLevelsForTest(UUID uuid, int level) {
      cellLevels.put(uuid, Math.max(0, Math.min(CELL_MAX, level)));
   }

   public static String cellName(int level) {
      return switch (Math.max(0, Math.min(CELL_MAX, level))) {
         case 0 -> "Tiny Cell";
         case 1 -> "Improved Cell";
         case 2 -> "Better Cell";
         case 3 -> "Secure Cell";
         default -> "Executive Cell";
      };
   }

   /** What the next rung costs, or -1 at the top of the ladder. */
   public static long cellUpgradeCost(int level) {
      return level < 0 || level >= CELL_MAX ? -1L : CELL_COSTS[level];
   }

   /**
    * How often a point of Heat bleeds off this prisoner, in ticks.
    *
    * <p>The tick loop reads this rather than its own number, because a cooling rate with two
    * owners is a rate that can be bought and not delivered.
    */
   public static int cellCoolingTicks(UUID uuid) {
      return cellCoolingTicksAt(cellLevelOf(uuid));
   }

   /**
    * The rate a rung of the cell ladder runs at, in ticks per point of Heat.
    *
    * <p>The ladder has one owner: the menu and the self-test both read a rung through this, so a
    * claim about "the next cell is faster" cannot be arithmetic done in the menu against a ruler
    * kept somewhere else.
    */
   public static int cellCoolingTicksAt(int level) {
      return CELL_COOL_BASE_TICKS - CELL_COOL_PER_LEVEL * Math.max(0, Math.min(CELL_MAX, level));
   }

   /** One rung up the cell ladder, paid for out of prison cash. */
   public static String cellUpgrade(ServerPlayer player) {
      UUID uuid = player.getUUID();
      int level = cellLevelOf(uuid);
      if (level >= CELL_MAX) {
         return "Your cell is already the best the block has.";
      }
      long cost = cellUpgradeCost(level);
      long cash = balanceOf(uuid);
      if (cash < cost) {
         return "You need $" + cost + " for a " + cellName(level + 1) + " - you have $" + cash + ".";
      }
      wallets.put(uuid, cash - cost);
      cellLevels.put(uuid, level + 1);
      Chat.raw(player, "§a§lMOVED UP§r §7- your new cell is a §f" + cellName(level + 1) + "§7.");
      Chat.raw(
         player,
         "§7Heat now bleeds off a point every §f" + cellCoolingTicks(uuid) / 20.0 + "s§7"
            + " instead of every §f" + (CELL_COOL_BASE_TICKS - CELL_COOL_PER_LEVEL * (level) ) / 20.0 + "s§7."
      );
      return null;
   }

   // ------------------------------------------------------------------
   // Trust
   // ------------------------------------------------------------------
   //
   // The answer to "mining is too hot to be worth it": a rung on a second ladder that does not make
   // the ore worth more, it makes the record cooler. Where the cell ladder buys back the *cooling*
   // rate, trust buys the *gain*, so a trusted prisoner can work the same seam for longer before the
   // guards notice - which is the thing a shift is actually measured in.

   /** How far up the trust ladder a prisoner has bought. */
   private static final Map<UUID, Integer> trustLevels = new HashMap<>();
   public static final int TRUST_MAX = 5;
   private static final long[] TRUST_COSTS = {4_000L, 12_000L, 30_000L, 70_000L, 150_000L};
   /** What each rung takes off the Heat a block would have cost, in percent. */
   public static final int TRUST_PERCENT_PER_LEVEL = 10;

   public static int trustLevelOf(UUID uuid) {
      return Math.max(0, Math.min(TRUST_MAX, trustLevels.getOrDefault(uuid, 0)));
   }

   /** Test hook: puts a prisoner on a rung of the trust ladder so the rate can be read. */
   public static void trustLevelsForTest(UUID uuid, int level) {
      trustLevels.put(uuid, Math.max(0, Math.min(TRUST_MAX, level)));
   }

   public static String trustName(int level) {
      return switch (Math.max(0, Math.min(TRUST_MAX, level))) {
         case 0 -> "Con";
         case 1 -> "Trusted Hand";
         case 2 -> "Trusted Wing";
         case 3 -> "Model Prisoner";
         case 4 -> "Trustee";
         default -> "The Warden's Word";
      };
   }

   /** What the next rung costs, or -1 at the top of the ladder. */
   public static long trustUpgradeCost(int level) {
      return level < 0 || level >= TRUST_MAX ? -1L : TRUST_COSTS[level];
   }

   /**
    * The multiplier a prisoner's Heat <b>gain</b> is scaled by - 1.0 at no trust, 0.5 at the top.
    *
    * <p>One owner for the number, so the menu, the mine and the self-test cannot disagree about what
    * a rung is worth.
    */
   public static double trustHeatMult(UUID uuid) {
      return 1.0 - (TRUST_PERCENT_PER_LEVEL * trustLevelOf(uuid)) / 100.0;
   }

   /** One rung up the trust ladder, paid for out of prison cash. */
   public static String trustUpgrade(ServerPlayer player) {
      UUID uuid = player.getUUID();
      int level = trustLevelOf(uuid);
      if (level >= TRUST_MAX) {
         return "The block already trusts you as far as it trusts anyone.";
      }
      long cost = trustUpgradeCost(level);
      long cash = balanceOf(uuid);
      if (cash < cost) {
         return "You need $" + cost + " to be a " + trustName(level + 1) + " - you have $" + cash + ".";
      }
      wallets.put(uuid, cash - cost);
      trustLevels.put(uuid, level + 1);
      Chat.raw(player, "§a§lTRUST EARNED§r §7- you are a §f" + trustName(level + 1) + "§7 now.");
      Chat.raw(
         player,
         "§7Every block you take builds §f" + TRUST_PERCENT_PER_LEVEL + "%§7 less Heat than it did "
            + "(now §f" + Math.round(trustHeatMult(uuid) * 100.0) + "%§7 of the burn)."
      );
      return null;
   }

   // ------------------------------------------------------------------
   // Contraband and the Processing pad
   // ------------------------------------------------------------------

   /**
    * The price at which a block stops being a prisoner's wages and starts being evidence.
    *
    * <p>Everything in the mine is on one side of this line or the other. Coal, stone, cobble, raw
    * iron, redstone, lapis and raw gold sit under it - they are what a prisoner is nominally in
    * here to move, and the till on the walkway buys them where they stand. Diamonds, emeralds,
    * netherite scrap and every ore block above them are over it, and the till will not touch them:
    * the block's own stone is priced as wages, but the goods that are worth more than the wage are
    * the goods somebody has to be seen carrying.
    *
    * <p>That is the whole reason the Processing pad exists. Mining the rich stone is not the risk -
    * a prisoner can mine all day and only be a little hot for it. Arriving at the checkpoint with it
    * is the risk, because that is the moment the prison gets to look inside the bag.
    */
   public static final long CONTRABAND_PRICE = 15L;

   /**
    * Centre of the Processing scale, in its own room off the intake deck.
    *
    * <p>It used to sit in the deck's own floor at {@code (5, SURFACE_Y, 5)}, which is the one place
    * in the block a prisoner arrives into a chunk the mine's builders have already been through: a
    * teleport onto the pad could land inside whatever the last floor pass left there, and did - the
    * report is "the processor puts me inside a block, in a pocket of reinforced deepslate". The
    * scale is in a room of its own now, built and cleared on the way in (see {@link #buildProcessingRoom}),
    * so the arrival is never a guess about what the world happens to hold.
    */
   private static final int PROCESS_X = -18;
   private static final int PROCESS_Y = SURFACE_Y;
   private static final int PROCESS_Z = 0;
   private static final int PROCESS_HALF = 2;
   /** Half the width of the Processing room's interior, in x and z. */
   private static final int PROCESS_ROOM_HALF = 5;
   /** Air over the scale in its own room; four is a hall rather than the two of a pad in a floor. */
   private static final int PROCESS_ROOM_HEADROOM = 4;

   public static long tokensOf(UUID uuid) {
      return Math.max(0L, tokens.getOrDefault(uuid, 0L));
   }

   public static void giveTokens(UUID uuid, long amount) {
      if (amount <= 0L) {
         return;
      }
      tokens.put(uuid, tokensOf(uuid) + amount);
   }

   /**
    * Takes tokens out of a prisoner's pocket for an exchange line.
    *
    * @return null on success, or how short they are
    */
   public static String spendTokens(ServerPlayer player, long cost) {
      long held = tokensOf(player.getUUID());
      if (held < cost) {
         return "You need §d" + cost + " tokens§c - you have §d" + held + "§c.";
      }
      tokens.put(player.getUUID(), held - cost);
      return null;
   }

   /** Unit price of a stack, or nought if it is the kind of thing the till buys outright. */
   public static long contrabandPrice(ItemStack stack) {
      long unit = priceForItem(stack);
      return unit >= CONTRABAND_PRICE ? unit : 0L;
   }

   /** Whether this stack is restricted - i.e. not something a prisoner may sell where they stand. */
   public static boolean isContraband(ItemStack stack) {
      return contrabandPrice(stack) > 0L;
   }

   /** What is worth declaring right now, at the till's own prices. */
   public static long contrabandValue(ServerPlayer player) {
      if (player == null) {
         return 0L;
      }
      Inventory inv = player.getInventory();
      long total = 0L;
      for (int i = 0; i < inv.getContainerSize(); i++) {
         ItemStack stack = inv.getItem(i);
         total += contrabandPrice(stack) * stack.getCount();
      }
      return total;
   }

   /**
    * What the checkpoint's scanner takes off a haul, in percent, as a function of Heat alone.
    *
    * <p>A pure function of one number, because it is the one rule in the whole loop that decides
    * whether a prisoner got away with it, and it has to be the same rule every time it is asked.
    * Nothing at a clean record - the scanner has no interest in a prisoner nobody has noticed.
    * Then it climbs with the tiers the guards already use: a tenth at SUSPICIOUS, a fifth at ALERT,
    * a third under lockdown, and half in CRITICAL, where the pad is less a checkpoint than a
    * confession.
    */
   public static int scanSeizurePercent(int heat) {
      return switch (PrisonCellblock.alertOf(heat)) {
         case CLEAR -> 0;
         case SUSPICIOUS -> 10;
         case ALERT -> 20;
         case LOCKDOWN -> 35;
         case CRITICAL -> 50;
      };
   }

   /**
    * Whether a body is at the Process pad.
    *
    * <p>It used to ask for the nine-square of the scale itself, so a prisoner who stepped onto the
    * chiselled edge the builders put around it - which reads as the pad from every angle - got
    * nothing. The whole room is the checkpoint now; standing anywhere in it is standing on the pad.
    */
   public static boolean atProcessing(ServerPlayer player) {
      if (player == null) {
         return false;
      }
      return Math.abs(player.getX() - (PROCESS_X + 0.5)) <= PROCESS_ROOM_HALF + 0.5
         && Math.abs(player.getZ() - (PROCESS_Z + 0.5)) <= PROCESS_ROOM_HALF + 0.5
         && Math.abs(player.getY() - PROCESS_Y) <= 4.0;
   }

   /**
    * Takes the restricted goods out of a bag until the scanner's share is covered.
    *
    * <p>From the most valuable thing down, because that is what a search does: the bag is emptied by
    * value, and a prisoner who wants to lose less has to carry less. Every restricted item leaves
    * the bag either way - the scanner's share is destroyed and the declared rest is what the pad
    * pays for - so the two halves always add up to what was carried in.
    *
    * @return {seized, declared}, in prison cash
    */
   private static long[] declare(ServerPlayer player, int percent) {
      Inventory inv = player.getInventory();
      List<int[]> order = new ArrayList<>();
      long total = 0L;
      for (int i = 0; i < inv.getContainerSize(); i++) {
         long unit = contrabandPrice(inv.getItem(i));
         if (unit > 0L) {
            order.add(new int[]{i, (int)Math.min(Integer.MAX_VALUE, unit)});
            total += unit * inv.getItem(i).getCount();
         }
      }
      order.sort((a, b) -> Integer.compare(b[1], a[1]));
      long target = total * Math.max(0, Math.min(100, percent)) / 100L;
      long seized = 0L;
      long declared = 0L;
      // Item by item rather than stack by stack: the scanner's share is a number of dollars, and a
      // stack that straddles the line has to be split for the payout and the seizure to add up to
      // what the bag held. A whole-stack version of this paid the declaration and left the goods in
      // the bag at a clean record, which is a printing press with a scan animation on it.
      for (int[] entry : order) {
         ItemStack stack = inv.getItem(entry[0]);
         if (stack.isEmpty()) {
            continue;
         }
         long unit = entry[1];
         int count = stack.getCount();
         for (int n = 0; n < count; n++) {
            if (seized < target) {
               seized += unit;
            } else {
               declared += unit;
            }
         }
         inv.setItem(entry[0], ItemStack.EMPTY);
      }
      return new long[]{seized, declared};
   }

   /**
    * Declares the bag at the checkpoint: the scanner takes its share, the rest is paid, and the
    * record is laundered by the fact of having declared anything at all.
    *
    * @return null on success (the player has been told what happened), or why there was nothing to do
    */
   public static String cashOut(ServerPlayer player) {
      if (player == null) {
         return null;
      }
      long total = contrabandValue(player);
      if (total <= 0L) {
         return "Nothing to declare - the pad only takes the rich stone. Coal, iron and lapis go at the till on the walkway.";
      }
      int percent = scanSeizurePercent(PrisonCellblock.heatOf(player.getUUID()));
      long[] split = declare(player, percent);
      long taken = split[0];
      long declared = split[1];
      int rank = rankOf(player.getUUID());
      long paid = declared * (100L + trusteePercent(rank)) / 100L;
      wallets.put(player.getUUID(), balanceOf(player.getUUID()) + paid);
      PrisonCellblock.onSold(player, total);
      // Tokens are the part of the payout that reaches no other part of the economy: they are earned
      // per haul rather than per dollar, so a deep sector's rich stone is worth more of them for the
      // same amount of work, and they cannot be converted into anything but prison gear and gear's
      // worth of gems.
      long earned = 1L + total / 500L;
      giveTokens(player.getUUID(), earned);
      if (taken > 0L) {
         Chat.raw(
            player,
            "§4§lSECURITY SCAN§r §7- " + percent + "% of the haul was found and seized: §c$"
               + String.format("%,d", taken) + "§7 gone."
         );
         player.level()
            .playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ANVIL_LAND, SoundSource.BLOCKS, 0.8F, 0.6F);
      } else {
         Chat.raw(player, "§a§lCLEARED§r §7- the scanner found nothing to take.");
      }
      Chat.raw(
         player,
         "§aDeclared §e$" + String.format("%,d", declared) + "§a - paid §e$" + String.format("%,d", paid)
            + "§a prison cash" + (trusteePercent(rank) > 0 ? "§7 (trustee +" + trusteePercent(rank) + "%)" : "")
            + "§a, §d+" + earned + " token" + (earned == 1L ? "" : "s") + "§a."
      );
      Chat.raw(player, "§7Balance: §e$" + String.format("%,d", balanceOf(player.getUUID())) + "§7 · Tokens: §d" + tokensOf(player.getUUID()));
      return null;
   }

   /** Walks a prisoner to the Processing pad, and tells them what the scan is about to do. */
   public static String gotoProcessing(ServerPlayer player) {
      if (player == null) {
         return null;
      }
      MinecraftServer server = player.level().getServer();
      ServerLevel level = server == null ? null : server.getLevel(PRISON_DIM);
      if (level == null) {
         return "The prison isn't ready yet - try again in a moment.";
      }
      // The room is loaded and rebuilt before anybody is put inside it. "The processor puts me
      // inside a block, in a pocket of reinforced deepslate" was a teleport that trusted whatever
      // the world happened to hold at a fixed coordinate - and a chunk that had not been generated
      // since the last start holds an older floor pass, not the pad. The room is its own space with
      // its own builders now, and building it is idempotent, so the arrival is never a guess.
      level.getChunkAt(new BlockPos(PROCESS_X, PROCESS_Y, PROCESS_Z));
      buildProcessingRoom(level);
      player.teleport(new TeleportTransition(
         level,
         new Vec3(PROCESS_X + 0.5, PROCESS_Y, PROCESS_Z + 0.5),
         Vec3.ZERO, player.getYRot(), player.getXRot(), TeleportTransition.PLACE_PORTAL_TICKET
      ));
      // Said on arrival rather than at the scan, so the number a prisoner is gambling with is the
      // number they were shown - the pad reads first and takes a second later.
      Chat.raw(player, "§6§lPROCESSING§r §7- stand on the pad to declare.");
      Chat.raw(player, processingReadout(player));
      return null;
   }

   /**
    * The short line a prisoner reads when they stand on the pad, before anything is taken.
    *
    * <p>Reading the scanner's share before the scan is the difference between a risk and a mugging:
    * a player who knows the pad is about to take a third of a diamond haul can walk away, mine
    * something cheaper, or take the loss deliberately.
    */
   public static String processingReadout(ServerPlayer player) {
      int heat = PrisonCellblock.heatOf(player.getUUID());
      long worth = contrabandValue(player);
      int percent = scanSeizurePercent(heat);
      return "§7Carrying §e$" + String.format("%,d", worth) + "§7 of restricted stone · Heat §f" + heat
         + "§7 → scanner takes §c" + percent + "%";
   }

   // ---------- Persistence ----------

   public static void load(MinecraftServer server) {
      ranks.clear();
      wallets.clear();
      pickLevels.clear();
      cellLevels.clear();
      trustLevels.clear();
      tokens.clear();
      stashed.clear();
      prisonOrigin.clear();
      dataFile = EconomyManager.getDataDir(server).resolve("prison.json");
      JsonObject root = JsonUtil.readOrCreate(dataFile, new JsonObject());
      PrisonCellblock.load(root);
      if (root.has("players") && root.get("players").isJsonObject()) {
         for (Entry<String, JsonElement> e : root.getAsJsonObject("players").entrySet()) {
            try {
               UUID uuid = UUID.fromString(e.getKey());
               JsonObject obj = e.getValue().getAsJsonObject();
               ranks.put(uuid, Math.max(0, Math.min(RANKS - 1, JsonUtil.jsonInt(obj, "rank", 0))));
               wallets.put(uuid, Math.max(0L, JsonUtil.jsonLong(obj, "cash", 0L)));
               cellLevels.put(uuid, Math.max(0, Math.min(CELL_MAX, (int)JsonUtil.jsonLong(obj, "cell_level", 0L))));
               trustLevels.put(uuid, Math.max(0, Math.min(TRUST_MAX, (int)JsonUtil.jsonLong(obj, "trust_level", 0L))));
               tokens.put(uuid, Math.max(0L, JsonUtil.jsonLong(obj, "tokens", 0L)));
               pickLevels.put(uuid, Math.max(0, Math.min(PICK_MAX, (int)JsonUtil.jsonLong(obj, "pick_level", 0L))));
               swordLevels.put(uuid, Math.max(0, Math.min(SWORD_MAX, (int)JsonUtil.jsonLong(obj, "sword_level", 0L))));
            } catch (Exception ignored) {
            }
         }
      }
      // Where each prisoner was standing when they were booked in.
      //
      // Without this, a restart while a player is inside meant the way out was the world spawn:
      // the stash survived on disk (it always did) and the *place they came from* did not, so a
      // prisoner who served their time overnight was released somewhere they had never been.
      if (root.has("origins") && root.get("origins").isJsonObject()) {
         for (Entry<String, JsonElement> e : root.getAsJsonObject("origins").entrySet()) {
            try {
               JsonElement v = e.getValue();
               if (!v.isJsonArray() || v.getAsJsonArray().size() < 3) {
                  continue;
               }
               JsonArray arr = v.getAsJsonArray();
               double[] where = new double[]{
                  arr.get(0).getAsDouble(), arr.get(1).getAsDouble(), arr.get(2).getAsDouble(),
                  arr.size() > 3 ? arr.get(3).getAsDouble() : 0.0,
                  arr.size() > 4 ? arr.get(4).getAsDouble() : 0.0
               };
               prisonOrigin.put(UUID.fromString(e.getKey()), where);
            } catch (Exception ignored) {
            }
         }
      }
      if (root.has("stash") && root.get("stash").isJsonObject()) {
         var access = server.registryAccess();
         for (Entry<String, JsonElement> e : root.getAsJsonObject("stash").entrySet()) {
            try {
               UUID uuid = UUID.fromString(e.getKey());
               ItemStack[] items = new ItemStack[STASH_SLOTS];
               if (e.getValue().isJsonArray()) {
                  // Each entry names the slot it came out of, so armour goes back on the body and
                  // the off hand back in the off hand rather than the whole kit being re-packed from
                  // slot nought. The old form was a bare list of the items the locker held, with no
                  // slot on file at all; those are laid back down in order, which is all that can be
                  // said about them.
                  int next = 0;
                  for (JsonElement el : e.getValue().getAsJsonArray()) {
                     if (el.isJsonObject() && el.getAsJsonObject().has("slot") && el.getAsJsonObject().has("item")) {
                        int slot = el.getAsJsonObject().get("slot").getAsInt();
                        if (slot < 0) {
                           continue;
                        }
                        if (slot >= items.length) {
                           // A file from a wider layout still keeps every item: the locker grows to
                           // the slot it names rather than dropping the piece that sits past its own
                           // idea of how wide a kit is.
                           items = java.util.Arrays.copyOf(items, slot + 1);
                        }
                        ItemStack s = JsonUtil.jsonToItem(el.getAsJsonObject().get("item"), access);
                        items[slot] = s.isEmpty() ? null : s;
                        continue;
                     }
                     ItemStack s = JsonUtil.jsonToItem(el, access);
                     while (next < items.length && items[next] != null) {
                        next++;
                     }
                     if (next >= items.length) {
                        break;
                     }
                     items[next] = s.isEmpty() ? null : s;
                     next++;
                  }
               }
               stashed.put(uuid, items);
            } catch (Exception ignored) {
            }
         }
      }
   }

   public static void save(MinecraftServer server) {
      if (dataFile == null) {
         dataFile = EconomyManager.getDataDir(server).resolve("prison.json");
      }
      JsonObject root = new JsonObject();
      JsonObject players = new JsonObject();
      for (UUID uuid : ranks.keySet()) {
         JsonObject obj = new JsonObject();
         obj.addProperty("rank", rankOf(uuid));
         obj.addProperty("cash", balanceOf(uuid));
         obj.addProperty("pick_level", pickLevelOf(uuid));
         obj.addProperty("sword_level", swordLevelOf(uuid));
         obj.addProperty("cell_level", cellLevelOf(uuid));
         obj.addProperty("trust_level", trustLevelOf(uuid));
         obj.addProperty("tokens", tokensOf(uuid));
         players.add(uuid.toString(), obj);
      }
      root.add("players", players);
      JsonObject stashObj = new JsonObject();
      var access = server.registryAccess();
      for (Entry<UUID, ItemStack[]> e : stashed.entrySet()) {
         JsonArray arr = new JsonArray();
         ItemStack[] items = e.getValue();
         for (int i = 0; i < items.length; i++) {
            ItemStack s = items[i];
            if (s == null || s.isEmpty()) {
               continue;
            }
            JsonElement item = JsonUtil.itemToJson(s, access);
            if (item == null) {
               continue;
            }
            // Slot-indexed, like the snapshots in LastInventoryHolder: a locker that only recorded
            // the items it held handed a full kit back re-packed from the first slot, so a helmet
            // came out as a loose item in the hotbar and an empty hotbar slot came out empty.
            JsonObject entry = new JsonObject();
            entry.addProperty("slot", i);
            entry.add("item", item);
            arr.add(entry);
         }
         stashObj.add(e.getKey().toString(), arr);
      }
      root.add("stash", stashObj);
      JsonObject originObj = new JsonObject();
      for (Entry<UUID, double[]> e : prisonOrigin.entrySet()) {
         JsonArray arr = new JsonArray();
         for (double d : e.getValue()) {
            arr.add((double)Math.round(d * 100.0) / 100.0);
         }
         originObj.add(e.getKey().toString(), arr);
      }
      root.add("origins", originObj);
      PrisonCellblock.save(root);
      JsonUtil.write(dataFile, root);
   }

   // ---------- Accessors ----------

   public static boolean isInPrison(ServerPlayer player) {
      return player.level().dimension().equals(PRISON_DIM);
   }

   /**
    * Whether a prisoner may type this command at all.
    *
    * <p>The block answers to its own doors and to the mod's console: {@code /sell} and the rest of
    * the jobs a prisoner has, plus {@code /ff}, whose subcommands each carry their own permission
    * level - so opening the name hands a prisoner nothing, while closing it refused the one command
    * that exists *for* this room. {@code /ff test prison loosebrick} is how a break-out is tried
    * without waiting on the random brick, and it used to answer "no commands in prison" from inside
    * the place it is for. The rule lives here rather than in the packet hook so it can be asked
    * directly.
    */
   public static boolean commandAllowedInPrison(String command) {
      String base = command == null ? "" : command.trim().toLowerCase(java.util.Locale.ROOT);
      if (base.startsWith("/")) {
         base = base.substring(1);
      }
      int space = base.indexOf(' ');
      if (space > 0) {
         base = base.substring(0, space);
      }
      return base.equals("sell")
         || base.equals("sellall")
         || base.equals("rankup")
         || base.equals("prison")
         || base.equals("ff");
   }

   /**
    * What a prisoner is told when the block refuses a command, or null when it runs it.
    *
    * <p>The packet hook asks this rather than the predicate, so the refusal itself is part of the
    * rule and not a string buried in a mixin: /shop is the case that prompted it - the block is a
    * place you work, not a place you buy your way out of, and the catalog even sells the pickaxes
    * the mine exists to replace. The door is the Prison Menu on the Nether Star, and that is what
    * the sentence points at.
    */
   public static String commandRefusal(ServerPlayer player, String command) {
      if (player == null || !isInPrison(player) || commandAllowedInPrison(command)) {
         return null;
      }
      return "§cNo commands in prison - use your §dPrison Menu§c (Nether Star) instead.";
   }

   /**
    * Arm the prison's shutdown flag. A stop keeps a sentence on the books so the next boot resumes
    * it; a quit walks the prisoner out instead (see the disconnect hook, the softlock guard).
    */
   public static void markServerStopping() {
      PrisonCellblock.markServerStopping();
   }

   /** True while the server is stopping, as opposed to a player having quit. */
   public static boolean isServerStopping() {
      return PrisonCellblock.isServerStopping();
   }

   public static int rankOf(UUID uuid) {
      return Math.max(0, Math.min(RANKS - 1, ranks.getOrDefault(uuid, 0)));
   }

   /**
    * True when this player has a prison record at all - booked in, ranked, on file.
    *
    * <p>Different from {@code rankOf}, which answers 0 for a stranger: an entry point is the block's
    * memory that a prisoner belongs to it, and that memory is what a restart must not lose. The
    * guard sweep reads it to tell a guard whose owner is merely offline from one whose owner never
    * existed, so a reload keeps a prisoner's guards instead of putting them down.
    */
   public static boolean hasRecord(UUID uuid) {
      // A rank means they climbed the ladder; an entry point means they were booked in at all and
      // have not walked out. Either is a record, and the entry point is the one a prisoner has from
      // the moment they arrive - so a restart while they are offline does not lose them.
      return ranks.containsKey(uuid) || prisonOrigin.containsKey(uuid);
   }

   public static long balanceOf(UUID uuid) {
      return wallets.getOrDefault(uuid, 0L);
   }

   public static int pickLevelOf(UUID uuid) {
      return Math.max(0, Math.min(PICK_MAX, pickLevels.getOrDefault(uuid, 0)));
   }

   public static int swordLevelOf(UUID uuid) {
      return Math.max(0, Math.min(SWORD_MAX, swordLevels.getOrDefault(uuid, 0)));
   }

   /** Adjusts a prisoner's wallet. Negative amounts spend; the balance never goes below zero. */
   public static void addCash(UUID uuid, long amount) {
      long next = balanceOf(uuid) + amount;
      wallets.put(uuid, Math.max(0L, next));
   }

   public static void addCash(ServerPlayer player, long amount) {
      addCash(player.getUUID(), amount);
   }

   /** Where a prisoner of this rank stands when nobody is watching: their own mine floor. */
   public static BlockPos mineSpawnFor(int rank) {
      return floorSpawn(tierOf(rank));
   }

   /** Test hook: puts an entry point on record without booking anybody in. */
   public static void rememberOriginForTest(UUID uuid, double x, double y, double z) {
      prisonOrigin.put(uuid, new double[]{x, y, z, 0.0, 0.0});
   }

   /** Test hook: the entry point on record, or null. */
   public static double[] originForTest(UUID uuid) {
      double[] where = prisonOrigin.get(uuid);
      return where == null ? null : where.clone();
   }

   /** Test hook: fills a locker without booking anybody in - pass null to empty it again. */
   public static void stashForTest(UUID uuid, ItemStack[] items) {
      if (items == null) {
         stashed.remove(uuid);
      } else {
         stashed.put(uuid, items);
      }
   }

   /** Test hook: the locker on file for this player, or null when they hold none. */
   public static ItemStack[] stashOfForTest(UUID uuid) {
      return stashed.get(uuid);
   }

   /** Test hook: puts a prisoner on a rank without making them pay for it. */
   public static void setRankForTest(UUID uuid, int rank) {
      ranks.put(uuid, Math.max(0, Math.min(RANKS - 1, rank)));
   }

   /** Prison value of a carried stack - the same number {@code /sell} would pay. */
   public static long sellValueOf(ItemStack stack) {
      return priceForItem(stack);
   }

   /** Awarded from the block-break hook: Heat, contracts and the occasional loose brick. */
   public static void onBlockMined(ServerPlayer player, BlockPos pos, BlockState state) {
      // A loose brick is the one block that isn't ore - breaking it is the break-out.
      if (PrisonCellblock.onLooseBrickBroken(player, pos)) {
         return;
      }
      PrisonCellblock.onBlockMined(player, pos, state);
   }

   /** True while the prisoner is behind a door of either kind - the hole or their own cell. */
   public static boolean isConfined(ServerPlayer player) {
      return PrisonCellblock.isConfined(player.getUUID());
   }

   /** True only while the prisoner is in the hole, never for a sentence served in their cell. */
   public static boolean isInSolitary(ServerPlayer player) {
      return PrisonCellblock.isInSolitary(player.getUUID());
   }

   public static String rankName(int rank) {
      return String.valueOf((char)('A' + rank));
   }

   public static int tierOf(int rank) {
      return Math.min(7, rank / 3);
   }

   public static long rankUpCost(int rank) {
      if (rank >= RANKS - 1) {
         return -1L;
      }
      long n = rank + 1L;
      return 80L * n * n + 80L; // 3x cheaper
   }

   /**
    * What a prisoner's rank is worth at the scale, in percent on top of the ore price.
    *
    * <p>Rank used to change one thing about a prisoner's life: which ore was in the rock under
    * their feet. The price table underneath it was flat, so a rank F diamond paid exactly what a
    * rank A diamond did and the whole ladder was a cheaper way of doing the same work - the
    * number went up, the work did not change. A trustee rate is the other half: the deeper ranks
    * are where the rich ore is *and* where a haul is worth more, so climbing compounds instead of
    * repeating.
    *
    * <p>Deliberately slow - {@value #TRUSTEE_PERCENT_PER_RANK}% a rank, so the whole ladder is
    * worth half as much again and not a printing press. Prison cash converts to main cash
    * 2:1, so this is the one number in here that reaches the rest of the economy and it is kept
    * small on purpose.
    */
   public static int trusteePercent(int rank) {
      return TRUSTEE_PERCENT_PER_RANK * Math.max(0, Math.min(RANKS - 1, rank));
   }

   /** What one stack is worth to one prisoner: the ore price, and the rank's rate on top of it. */
   public static long sellPriceFor(UUID uuid, ItemStack stack) {
      long base = priceForItem(stack);
      if (base <= 0L) {
         return 0L;
      }

      // Rounded down: a rate is never allowed to pay more than it says it pays.
      return base * (100L + trusteePercent(rankOf(uuid))) / 100L;
   }

   /** Prison price for a mined block, in prison cash. */
   public static long priceFor(BlockState state) {
      if (state == null || state.isAir()) {
         return 0L;
      }
      String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
      return switch (id) {
         case "stone", "cobblestone", "deepslate", "cobbled_deepslate" -> 5L;
         case "coal_ore", "deepslate_coal_ore" -> 15L;
         case "iron_ore", "deepslate_iron_ore" -> 30L;
         case "gold_ore", "deepslate_gold_ore" -> 60L;
         case "redstone_ore", "deepslate_redstone_ore" -> 25L;
         case "lapis_ore", "deepslate_lapis_ore" -> 40L;
         case "diamond_ore", "deepslate_diamond_ore" -> 125L;
         case "emerald_ore", "deepslate_emerald_ore" -> 100L;
         case "ancient_debris" -> 300L;
         default -> 0L;
      };
   }

   // ------------------------------------------------------------------
   // The mining loop's two reasons to keep digging
   // ------------------------------------------------------------------
   //
   // Mining paid exactly one thing: the ore in your hand, sold later. The slab refills on its own
   // clock right in front of a prisoner who never has to move, so "work the seams" was really
   // "stand still and hold left-click" - the work was flat and the reward was a menu away.
   //
   // Two mechanics answer that, and they are the same idea from both ends: pay for *how* the ore
   // is taken, not only for what it is. Momentum is the chain - ore-to-ore inside a short window
   // escalates the bonus, so stopping costs money and the reason to keep moving is the clock. A
   // rich seam is the score - a pocket of the tier's best material that pays a jackpot for being
   // cleared out, so the reason to look around is that the ordinary wall does not have one.

   /** Ore-to-ore window, in ticks: break the next one inside this and the chain continues. */
   public static final long MOMENTUM_WINDOW_TICKS = 60L;
   /** What every ore already in the chain adds to the next ore's bonus. */
   public static final int MOMENTUM_PERCENT_PER_ORE = 5;
   /** The chain stops climbing past this length: a streak, not a machine. */
   public static final int MOMENTUM_CAP_CHAIN = 10;

   /**
    * What a chain of this length is worth on the <em>next</em> ore, in percent.
    *
    * <p>Read as "the chain you already have": the first ore of a run pays nothing extra, the
    * second pays 5%, and the tenth pays the cap. Deliberately small - the ore in the hand is
    * still the haul, and this is what keeping the run alive is worth on top of it.
    */
   public static int momentumPercent(int chain) {
      return Math.max(0, Math.min(MOMENTUM_CAP_CHAIN, chain)) * MOMENTUM_PERCENT_PER_ORE;
   }

   /**
    * The chain after one more ore, given how long it has been since the last one.
    *
    * <p>Pure and total, so the rule can be read without a world: inside the window the chain
    * grows, and outside it - or on a clock that has gone backwards - the run starts again at one.
    */
   public static int nextChain(int previousChain, long sinceLastTicks) {
      boolean inWindow = sinceLastTicks >= 0L && sinceLastTicks <= MOMENTUM_WINDOW_TICKS;
      return inWindow ? Math.max(0, previousChain) + 1 : 1;
   }

   private record Momentum(int chain, long atTick) {
   }

   private static final Map<UUID, Momentum> momentum = new HashMap<>();

   /** The chain a prisoner is carrying right now, with an expired chain read as zero. */
   public static int momentumOf(UUID uuid, long nowTick) {
      Momentum m = momentum.get(uuid);
      if (m == null || nowTick - m.atTick() > MOMENTUM_WINDOW_TICKS || nowTick < m.atTick()) {
         return 0;
      }
      return m.chain();
   }

   /** The chain the last ore left behind, with no clock: what the HUD and the scoreboard read. */
   public static int momentumOf(UUID uuid) {
      Momentum m = momentum.get(uuid);
      return m == null ? 0 : m.chain();
   }

   public static void forgetMomentum(UUID uuid) {
      momentum.remove(uuid);
   }

   /** Test hook: put a chain on record without breaking anything. */
   public static void setMomentumForTest(UUID uuid, int chain, long atTick) {
      momentum.put(uuid, new Momentum(chain, atTick));
   }

   /**
    * One sellable block, broken by hand: extends the chain, pays the chain's bonus, and clears a
    * seam block if that is what this was.
    *
    * @return the extra prison cash this block paid, on top of the ore it dropped
    */
   public static long onOreMined(ServerPlayer player, BlockPos pos, BlockState state, long price) {
      if (player == null || price <= 0L) {
         return 0L;
      }
      UUID uuid = player.getUUID();
      long now = PrisonCellblock.clock(player.level());
      Momentum previous = momentum.get(uuid);
      long since = previous == null ? Long.MAX_VALUE : now - previous.atTick();
      int chain = nextChain(previous == null ? 0 : previous.chain(), since);
      long bonus = price * momentumPercent(chain - 1) / 100L;
      momentum.put(uuid, new Momentum(chain, now));
      if (bonus > 0L) {
         addCash(uuid, bonus);
      }
      if (chain >= 2) {
         player.sendSystemMessage(
            Component.literal(
               "§6⛏ Momentum §f"
                  + chain
                  + "§7 · §e+"
                  + momentumPercent(chain - 1)
                  + "%§7 ore bonus"
                  + (bonus > 0L ? " §8(+" + bonus + ")" : "")
            ),
            true
         );
      }
      if (chain > 1 && chain % MOMENTUM_CAP_CHAIN == 0) {
         player.level()
            .playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.7F, 1.6F);
         Chat.raw(
            player,
            "§6§lMOMENTUM " + chain + "!§r §7Your chain is paying §e+" + momentumPercent(chain - 1) + "%§7 on every ore."
         );
      }
      return bonus + tapSeam(player, pos);
   }

   // ---------- Rich seams ----------

   /** One floor refill in this many plants a rich seam, per prisoner digging that tier. */
   public static final int SEAM_CHANCE = 12;
   /** How many blocks a rich seam is cut from. */
   public static final int SEAM_BLOCKS = 9;

   /**
    * The material a rich seam is cut from: the best ore its tier actually has.
    *
    * <p>A seam is a *pocket*, so it has to read as richer than the wall around it at every depth -
    * a tier-0 seam of coal would be a joke, and a tier-7 seam of diamonds would be ordinary. The
    * table walks the tiers the same way {@code tierBlock} does, one step ahead of it.
    */
   public static Block seamOreFor(int tier) {
      return switch (Math.max(0, Math.min(7, tier))) {
         case 0, 1 -> Blocks.IRON_ORE;
         case 2, 3 -> Blocks.GOLD_ORE;
         case 4 -> Blocks.LAPIS_ORE;
         case 5 -> Blocks.DIAMOND_ORE;
         case 6 -> Blocks.EMERALD_ORE;
         default -> Blocks.ANCIENT_DEBRIS;
      };
   }

   /**
    * What clearing a whole seam pays on top of the nine ore in it.
    *
    * <p>It has to be worth the detour without out-earning the ladder: a few ore's worth at the
    * bottom, a stack's worth at the top, and always climbing.
    */
   public static long seamJackpot(int tier) {
      return 250L + 250L * Math.max(0, Math.min(7, tier));
   }

   private record Seam(UUID owner, int remaining, int tier) {
   }

   private static final Map<BlockPos, Seam> seams = new LinkedHashMap<>();

   /** Forgets a prisoner's seams - they leave with them, and a seam is not a place, it is a trip. */
   public static void dropSeamsOf(UUID owner) {
      seams.entrySet().removeIf(e -> e.getValue().owner().equals(owner));
   }

   /** Test hook: how many blocks are on record as part of a seam. */
   public static int seamBlocksForTest() {
      return seams.size();
   }

   /**
    * Removes one seam block; pays the jackpot when the pocket is emptied.
    *
    * <p>The count is kept on every block of the pocket and moved together, because the pockets are
    * cleared from any direction: counting the *removed* block only would leave a player who broke
    * the last one looking at a jackpot that never arrives.
    */
   private static long tapSeam(ServerPlayer player, BlockPos pos) {
      Seam hit = seams.remove(pos);
      if (hit == null || !hit.owner().equals(player.getUUID())) {
         return 0L;
      }
      int left = -1;
      for (Iterator<Entry<BlockPos, Seam>> it = seams.entrySet().iterator(); it.hasNext(); ) {
         Entry<BlockPos, Seam> e = it.next();
         if (!e.getValue().owner().equals(hit.owner())) {
            continue;
         }
         left = e.getValue().remaining() - 1;
         if (left <= 0) {
            it.remove();
         } else {
            e.setValue(new Seam(hit.owner(), left, hit.tier()));
         }
      }
      if (left > 0) {
         if (left == 1) {
            Chat.raw(player, "§6One block left of that seam...");
         }
         return 0L;
      }
      long jackpot = seamJackpot(hit.tier());
      addCash(player.getUUID(), jackpot);
      player.level()
         .playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 1.0F, 0.8F);
      Chat.raw(
         player,
         "§6§lRICH SEAM CLEARED!§r §7You took the whole pocket - §e+" + jackpot + "§7 prison cash."
      );
      return jackpot;
   }

   /**
    * Plants one rich seam in a prisoner's current tier, replacing any seam they already had.
    *
    * <p>One per prisoner on purpose: the mine is a shared slab, so a pocket that accumulated per
    * refill would be a wall of diamond the server hands out on a timer.
    */
   private static void plantSeam(ServerLevel level, int tier, UUID owner) {
      seams.entrySet().removeIf(e -> e.getValue().owner().equals(owner));
      for (int tries = 0; tries < 24; tries++) {
         int x = RANDOM.nextInt(ROOM_HALF * 2 + 1) - ROOM_HALF;
         int z = RANDOM.nextInt(ROOM_HALF * 2 + 1) - ROOM_HALF;
         int depth = RANDOM.nextInt(SEAM_DEPTH);
         List<BlockPos> face = new ArrayList<>();
         for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
               BlockPos p = new BlockPos(x + dx, TIER_FLOOR_Y[tier] - depth, z + dz);
               if (!level.getBlockState(p).isAir()) {
                  face.add(p);
               }
            }
         }
         if (face.size() < SEAM_BLOCKS) {
            continue;
         }
         // A seam repaints rock that is already there, so it can never put a block in the air - but
         // it can still paint the block a prisoner is standing on, which reads as the floor changing
         // under them. Same clearance rule as the refill.
         boolean besideBody = false;
         for (BlockPos p : face) {
            if (bodyNear(level, p)) {
               besideBody = true;
               break;
            }
         }
         if (besideBody) {
            continue;
         }
         BlockState ore = seamOreFor(tier).defaultBlockState();
         for (BlockPos p : face) {
            level.setBlock(p, ore, 3);
            seams.put(p, new Seam(owner, SEAM_BLOCKS, tier));
         }
         // It glints, so a prisoner looking up from the wall they are working can see it.
         BlockPos first = face.get(0);
         com.fortuneandfavors.net.FfVfx.particles(level, 
            ParticleTypes.END_ROD,
            first.getX() + 0.5,
            first.getY() + 1.2,
            first.getZ() + 0.5,
            24,
            1.6,
            0.6,
            1.6,
            0.01
         );
         return;
      }
   }

   public static ItemStack prisonStar() {
      ItemStack stack = new ItemStack(Items.NETHER_STAR);
      CompoundTag tag = new CompoundTag();
      tag.putString("ff", STAR_TAG);
      stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§d§lPrison Menu"));
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Right-click to open the prison GUI:"),
               Component.literal("§7sell, rank up, upgrade your pickaxe"),
               Component.literal("§7and convert cash."),
               Component.literal("§8Always in your hotbar slot 9.")
            )
         )
      );
      return stack;
   }

   public static boolean isPrisonStar(ItemStack stack) {
      if (stack == null || stack.isEmpty()) {
         return false;
      }
      CustomData data = (CustomData)stack.get(DataComponents.CUSTOM_DATA);
      return data != null && STAR_TAG.equals(data.copyTag().getString("ff").orElse(""));
   }

   private static void removeStars(ServerPlayer player) {
      Inventory inv = player.getInventory();
      for (int i = 0; i < inv.getContainerSize(); i++) {
         if (isPrisonStar(inv.getItem(i))) {
            inv.setItem(i, ItemStack.EMPTY);
         }
      }
   }

   // ---------- Inventory lockout (like duels) ----------

   /**
    * Everything a prisoner owns, INCLUDING what they are wearing.
    *
    * <p>The armour slots are the half this used to miss, and the omission was not cosmetic: the
    * intake issue now puts a uniform in those slots, so a snapshot that only covered the thirty-six
    * carried slots would have left a prisoner's own chestplate inside the kit that overwrote it - and
    * walking out would have returned everything except the four pieces they had on. The array is the
    * carried slots followed by the four armour slots and the off hand, in that order.
    */
   private static ItemStack[] snapshotInventory(ServerPlayer player) {
      Inventory inv = player.getInventory();
      ItemStack[] items = new ItemStack[STASH_SLOTS];
      int carried = Math.min(inv.getContainerSize(), CARRIED_SLOTS);
      for (int i = 0; i < carried; i++) {
         ItemStack s = inv.getItem(i);
         items[i] = s.isEmpty() ? null : s.copy();
      }
      for (int i = 0; i < ARMOUR_SLOTS.length; i++) {
         ItemStack s = player.getItemBySlot(ARMOUR_SLOTS[i]);
         items[ARMOUR_BASE + i] = s.isEmpty() ? null : s.copy();
      }
      ItemStack off = player.getItemBySlot(EquipmentSlot.OFFHAND);
      items[OFFHAND_SLOT] = off.isEmpty() ? null : off.copy();
      return items;
   }

   private static void restoreInventory(ServerPlayer player, ItemStack[] items) {
      if (items == null) {
         return;
      }
      Inventory inv = player.getInventory();
      inv.clearContent();
      int carried = Math.min(inv.getContainerSize(), CARRIED_SLOTS);
      for (int i = 0; i < carried && i < items.length; i++) {
         if (items[i] != null && !items[i].isEmpty()) {
            inv.setItem(i, items[i]);
         }
      }
      // The armour and the off hand go back through the body's own slots rather than by container
      // index, so the piece that was on the head is the piece that comes back on the head even on a
      // build whose container orders them differently, and the client is told either way.
      for (int i = 0; i < ARMOUR_SLOTS.length; i++) {
         int at = ARMOUR_BASE + i;
         player.setItemSlot(ARMOUR_SLOTS[i], at < items.length && items[at] != null ? items[at] : ItemStack.EMPTY);
      }
      player.setItemSlot(
         EquipmentSlot.OFFHAND,
         OFFHAND_SLOT < items.length && items[OFFHAND_SLOT] != null ? items[OFFHAND_SLOT] : ItemStack.EMPTY
      );
   }

   /**
    * The uniform: plain leather, named, issued on intake and taken back on the way out.
    *
    * <p>Prisoners arrive with whatever they own stashed in a locker and are handed a pickaxe and a
    * sword - which left them in whatever they were wearing, so the first guard's first hit was a
    * fight against bare skin and every death was a haul of someone's own gear. The uniform is the
    * other half of that kit and it is deliberately the block's own: leather, so the richer armour a
    * prisoner owns is not what is being worn in here, and named, so a prisoner can see it is issued
    * kit and not loot. It carries <b>no enchantments</b>: Protection IV on all four pieces made the
    * guards a formality, and a manhunt you can stand still through is not a manhunt. It is re-issued
    * from the kit check, so losing it to a guard costs a second and not a run.
    */
   private static void giveUniform(ServerPlayer player) {
      if (hasUniform(player)) {
         return;
      }
      for (EquipmentSlot slot : ARMOUR_SLOTS) {
         ItemStack piece = new ItemStack(uniformFor(slot));
         CompoundTag tag = new CompoundTag();
         tag.putString("ff", UNIFORM_TAG);
         piece.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
         piece.set(DataComponents.CUSTOM_NAME, Component.literal("§7§lPrison Uniform"));
         piece.set(DataComponents.LORE, new ItemLore(List.of(
            Component.literal("§7Plain leather, issued on intake."),
            Component.literal("§7Handed back the moment you walk out.")
         )));
         player.setItemSlot(slot, piece);
      }
   }

   private static net.minecraft.world.item.Item uniformFor(EquipmentSlot slot) {
      return switch (slot) {
         case HEAD -> Items.LEATHER_HELMET;
         case CHEST -> Items.LEATHER_CHESTPLATE;
         case LEGS -> Items.LEATHER_LEGGINGS;
         default -> Items.LEATHER_BOOTS;
      };
   }

   private static boolean isUniform(ItemStack stack) {
      if (stack == null || stack.isEmpty()) {
         return false;
      }
      CustomData data = (CustomData)stack.get(DataComponents.CUSTOM_DATA);
      return data != null && UNIFORM_TAG.equals(data.copyTag().getString("ff").orElse(""));
   }

   private static boolean hasUniform(ServerPlayer player) {
      for (EquipmentSlot slot : ARMOUR_SLOTS) {
         if (isUniform(player.getItemBySlot(slot))) {
            return true;
         }
      }
      return false;
   }

   /** Takes the issued kit back off. Nothing else in those four slots is touched. */
   public static void removeUniform(ServerPlayer player) {
      for (EquipmentSlot slot : ARMOUR_SLOTS) {
         if (isUniform(player.getItemBySlot(slot))) {
            player.setItemSlot(slot, ItemStack.EMPTY);
         }
      }
   }

   // ---------- Flow ----------

   /** Teleports a player into prison, stashes their gear, builds the map and hands them their tools. */
   public static String enter(ServerPlayer player) {
      MinecraftServer server = player.level().getServer();
      ServerLevel level = server.getLevel(PRISON_DIM);
      if (level == null) {
         return "The prison isn't ready yet - try again in a moment.";
      }
      // An execution carried out is a lock-out, not just a teleport home: the block refuses the
      // prisoner for a couple of minutes so being thrown out is a punishment and not a shortcut.
      long cooldown = PrisonCellblock.cooldownLeft(player.getUUID());
      if (cooldown > 0L) {
         return "The block is done with you - you may come back in " + ((cooldown + 19L) / 20L) + "s.";
      }
      ensureBuilt(level);
      UUID uuid = player.getUUID();
      int rank = rankOf(uuid);
      boolean alreadyIn = player.level().dimension().equals(PRISON_DIM);
      // The block is swept clean of this prisoner's own detail before they are put back on a floor:
      // a Warden or a detail left over from the last sentence is not something the next one walks
      // into. See PrisonCellblock.onEnter.
      PrisonCellblock.onEnter(player);
      // Item lockout, duels-style: stash everything personal, then kit only.
      if (!alreadyIn) {
         if (!stashed.containsKey(uuid)) {
            stashed.put(uuid, snapshotInventory(player));
         }
         // Remember exactly where they were so leave() returns them there
         // (NOT world spawn).
         prisonOrigin.put(uuid, new double[]{player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot()});
      }
      if (!alreadyIn) {
         // The locker takes everything, including what they are wearing - the uniform goes on in the
         // slots the armour was in, and the armour itself is part of the snapshot now (see
         // snapshotInventory).
         player.getInventory().clearContent();
         for (EquipmentSlot slot : ARMOUR_SLOTS) {
            player.setItemSlot(slot, ItemStack.EMPTY);
         }
         player.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
         givePrisonKit(player);
         // The swap goes to disk now rather than at the next autosave. Between the two the only copy
         // of the prisoner's own kit was in memory while the body wore the block's - so a crash, a
         // kill or a rollback in that window lost it for good, and the prisoner walked out with
         // nothing. The locker is the block's whole promise to them; a promise that only survives a
         // timer is not one.
         save(server);
      }
      // A shift starts with a clean slate: no chain carried in from last time, and no seam left
      // glowing in a wall from a run that is over.
      forgetMomentum(uuid);
      dropSeamsOf(uuid);
      int arrivingTier = tierOf(rank);
      // The floor is put back as it was cut before anybody stands on it, so a prisoner never walks
      // into the pocket a previous shift left half-mined.
      if (!tierOccupied(server, arrivingTier, uuid)) {
         buildFloor(level, TIER_FLOOR_Y[arrivingTier], arrivingTier);
      }
      // A floor another shift is still working is left as it stands - but the pad this prisoner is
      // about to be put down on is not: see prepareLanding for why a landing is not somebody
      // else's work to leave a hole in.
      prepareLanding(level, arrivingTier);
      lastTier.put(uuid, arrivingTier);
      BlockPos spawn = floorSpawn(arrivingTier);
      player.teleport(new TeleportTransition(level, new Vec3(spawn.getX() + 0.5, spawn.getY() + 1.0, spawn.getZ() + 0.5), Vec3.ZERO, player.getYRot(), player.getXRot(), TeleportTransition.PLACE_PORTAL_TICKET));
      player.setRespawnPosition(new RespawnConfig(RespawnData.of(PRISON_DIM, spawn, 0.0F, 0.0F), true), false);
      Sector sector = sectorOf(rank);
      Chat.raw(player, "§c§lTHE CELL BLOCK§r §7- Rank §f" + rankName(rank) + "§7 · " + sector.colour + sector.name + "§7 · Balance §e$" + String.format("%,d", balanceOf(uuid)));
      Chat.raw(player, sectorLine(uuid));
      Chat.raw(player, "&7Mine the §fseam under your feet§7 and §f/sell&7 it. The walls, the floor and the ceiling are the block's own works - §fyou cannot mine them§7, and rank is the only way down.");
      Chat.raw(player, "&7Rich ore builds §cHeat§7: §aCLEAR§7 / §eSUSPICIOUS 25§7 / §6ALERT 45§7 / §cLOCKDOWN 75§7 / §4CRITICAL 95§7. Selling launder it; so does your cell.");
      Chat.raw(player, "&7The deeper the sector, the richer the seam §fand§7 the faster it burns you - §f" + sector.colour + sector.name + "§7 pays §f" + String.format("%.1f", sector.heat) + "×§7 the Heat.");
      Chat.raw(player, "&7The till on the walkway buys the &fwages§7 - coal, iron, lapis. It will §cnot§7 touch §fdiamond, emerald or netherite§7: that is §econtraband§7.");
      Chat.raw(player, "&7Carry contraband to §fProcessing§7 (the lodestone pad on the intake platform) and §fdeclare§7 it. The scanner takes its share - §cnone at CLEAR, half at CRITICAL§7.");
      Chat.raw(player, "&7Declaring pays §dprison tokens§7 as well as cash. Spend them in the Prison Menu: §d10 tokens §7= 1 gem, §d25 tokens§7 = a Common Key.");
      Chat.raw(player, "&7Heat past §c75§7 brings §4§lThe Warden§r&7 - put him down and your record is clean, debt and all.");
      Chat.raw(player, "&7Three §fcontracts&7 sit on the noticeboard in the Prison Menu. Break a §floose brick§7 for a shot at a break-out.");
      Chat.raw(player, "&7Ore-to-ore inside a minute builds §6Momentum§7; a §6rich seam§7 in the wall pays a jackpot for clearing it.");
      Chat.raw(player, "§8You can't die in here - only a killing blow takes you off the floor, and it ends at your own cell, never a respawn screen.");
      return null;
   }

   public static void leave(ServerPlayer player) {
      if (player == null) {
         return;
      }
      UUID uuid = player.getUUID();
      PrisonCellblock.onLeave(player);
      // A run the prisoner walks out on is abandoned with them - and, more to the point, a run that
      // is still seated after they leave the block is the "the Pit keeps saying I am in a fight"
      // report: the menu reads the seat, and nothing else ever clears it once they are outside.
      abandonPit(player);
      forgetMomentum(uuid);
      dropSeamsOf(uuid);
      // The pond's clock goes with the sentence: the wait is part of the wage, and leaving the
      // block is a much longer walk than the wait was.
      lastPaidCatch.remove(uuid);
      // The floor they were working belongs to the block again the moment nobody is standing on it:
      // every block they opened is put back, so the next shift arrives at a full seam.
      Integer worked = lastTier.remove(uuid);
      if (worked != null && player.level() instanceof ServerLevel prison && prison.dimension().equals(PRISON_DIM)
         && !tierOccupied(player.level().getServer(), worked, uuid)) {
         buildFloor(prison, TIER_FLOOR_Y[worked], worked);
      }
      removeUniform(player);
      ItemStack[] saved = stashed.remove(uuid);
      if (saved != null) {
         player.getInventory().clearContent();
         restoreInventory(player, saved);
         // The giving-back half goes to disk as well, so a crash in the second after walking out
         // leaves an empty locker rather than one that hands the same kit over twice. What the kit
         // is now sits on the body, which the ordinary player save keeps.
         MinecraftServer server = player.level().getServer();
         if (server != null) {
            save(server);
         }
      }
      removeStars(player);
      // Nobody is teleported out of this block on the way to dying. The block's own rule is that
      // there is no blow in it that ends in a death - every fatal one is answered with a door or the
      // chair - but the chair's answer is to throw the prisoner out, and it throws out a body the
      // charges have already taken to nothing. That body used to land in the overworld at zero
      // health and die there, outside the block and outside every promise the block makes: the kit
      // came back out of the locker onto a corpse, and what happened to it after that was vanilla's.
      // Healing here rather than only in the carrying-out path means no ending - a carried-out
      // sentence, a command, a crash between the two, a leave the server does on its own - can put a
      // dying player on the other side of this teleport.
      player.setHealth(player.getMaxHealth());
      player.getFoodData().setFoodLevel(20);
      player.getFoodData().setSaturation(20.0F);
      player.removeEffect(MobEffects.SLOWNESS);
      player.removeEffect(MobEffects.WEAKNESS);
      player.removeEffect(MobEffects.DARKNESS);
      player.removeEffect(MobEffects.BLINDNESS);
      player.removeEffect(MobEffects.MINING_FATIGUE);
      // Return to the exact spot the player entered from - never world spawn.
      double[] origin = prisonOrigin.remove(uuid);
      ServerLevel level = player.level().getServer().getLevel(Level.OVERWORLD);
      if (level != null) {
         if (origin != null) {
            player.teleport(new TeleportTransition(level, new Vec3(origin[0], origin[1], origin[2]), Vec3.ZERO, (float)origin[3], (float)origin[4], TeleportTransition.PLACE_PORTAL_TICKET));
         } else {
            BlockPos spawn = level.getRespawnData().pos();
            player.teleport(new TeleportTransition(level, new Vec3(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5), Vec3.ZERO, player.getYRot(), player.getXRot(), TeleportTransition.PLACE_PORTAL_TICKET));
         }
      }
      player.setRespawnPosition(null, false);
   }

   private static void givePrisonKit(ServerPlayer player) {
      // The uniform is issued on the same check as the pickaxe, so a piece taken off a prisoner by a
      // guard or a boat is back on them within the second rather than for the rest of the shift.
      giveUniform(player);
      if (hasPrisonPick(player)) {
         return;
      }
      int tier = tierOf(rankOf(player.getUUID()));
      ItemStack pick = pickaxeFor(tier, pickLevelOf(player.getUUID()), player.level().registryAccess().lookupOrThrow(Registries.ENCHANTMENT));
      player.getInventory().add(pick);
      // Nether Star in hotbar slot 9 (index 8) - always available
      ItemStack star = prisonStar();
      player.getInventory().setItem(8, star);
      // Max saturation so players don't need to eat while mining
      player.getFoodData().setFoodLevel(20);
      player.getFoodData().setSaturation(20.0F);
   }

   private static boolean hasPrisonPick(ServerPlayer player) {
      for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
         if (isPrisonPick(player.getInventory().getItem(i))) {
            return true;
         }
      }
      return false;
   }

   private static boolean isPrisonPick(ItemStack stack) {
      if (stack == null || stack.isEmpty()) {
         return false;
      }
      CustomData data = (CustomData)stack.get(DataComponents.CUSTOM_DATA);
      return data != null && "prison_pick".equals(data.copyTag().getString("ff").orElse(""));
   }

   /** Prison pickaxes never lose durability (UNBREAKABLE) and scale with the bought upgrade level. */
   private static ItemStack pickaxeFor(int tier, int pickLvl, Registry<Enchantment> enchants) {
      // Always start with iron pickaxe - rank determines ore tier, not pickaxe material
      ItemStack stack = new ItemStack(Items.IRON_PICKAXE);
      if (tier >= 3) stack = new ItemStack(Items.DIAMOND_PICKAXE);
      if (tier >= 6) stack = new ItemStack(Items.NETHERITE_PICKAXE);
      CompoundTag tag = new CompoundTag();
      tag.putString("ff", "prison_pick");
      stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
      stack.set(DataComponents.UNBREAKABLE, Unit.INSTANCE);
      // Enchantments scale with upgrade level - much better progression
      int eff = Math.min(5, 1 + pickLvl);
      int fort = Math.min(3, Math.max(0, pickLvl - 2));
      int unbreak = pickLvl >= 3 ? 1 : 0;
      if (enchants != null) {
         try {
            stack.enchant(enchants.getOrThrow(Enchantments.EFFICIENCY), eff);
            if (fort > 0) {
               stack.enchant(enchants.getOrThrow(Enchantments.FORTUNE), fort);
            }
            if (unbreak > 0) {
               stack.enchant(enchants.getOrThrow(Enchantments.UNBREAKING), unbreak);
            }
         } catch (Exception ignored) {
         }
      }
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§e§lPrison Pickaxe" + (pickLvl > 0 ? " §7(Lv " + pickLvl + ")" : "")));
      List<Component> lore = new java.util.ArrayList<>();
      lore.add(Component.literal("§7Never loses durability in prison."));
      lore.add(Component.literal("§7Efficiency " + eff + (fort > 0 ? " · Fortune " + fort : "") + (unbreak > 0 ? " · Unbreaking" : "")));
      lore.add(Component.literal("§7Upgrade it with prison cash in the Prison Menu."));
      stack.set(DataComponents.LORE, new ItemLore(lore));
      return stack;
   }

   public static long pickUpgradeCost(int level) {
      return 1500L * (level + 1) * (level + 1); // 3x cheaper than before
   }

   public static String pickUpgrade(ServerPlayer player) {
      UUID uuid = player.getUUID();
      int lvl = pickLevelOf(uuid);
      if (lvl >= PICK_MAX) {
         return "Your prison pickaxe is already maxed out (Lv " + PICK_MAX + ")!";
      }
      long cost = pickUpgradeCost(lvl);
      long bal = balanceOf(uuid);
      if (bal < cost) {
         return "Pickaxe Lv " + (lvl + 1) + " costs " + Chat.moneyStr(cost) + " prison cash - you have " + Chat.moneyStr(bal) + ".";
      }
      wallets.put(uuid, bal - cost);
      pickLevels.put(uuid, lvl + 1);
      replacePicks(player);
      Chat.raw(player, "§b§lPICKAXE UPGRADED to Lv " + (lvl + 1) + "!§r §7Your prison pick now mines faster and richer.");
      return null;
   }

   private static void replacePicks(ServerPlayer player) {
      Inventory inv = player.getInventory();
      for (int i = 0; i < inv.getContainerSize(); i++) {
         if (isPrisonPick(inv.getItem(i))) {
            inv.setItem(i, ItemStack.EMPTY);
         }
      }
      givePrisonKit(player);
   }

   // ---------- Prison sword (combat zone) ----------

   /** The combat-zone sword for a tier: Iron -> Diamond -> Netherite with scaling Sharpness. */
   private static ItemStack prisonSwordFor(int tier, Registry<Enchantment> enchants) {
      ItemStack stack = switch (tier) {
         case 1 -> new ItemStack(Items.DIAMOND_SWORD);
         case 2 -> new ItemStack(Items.NETHERITE_SWORD);
         default -> new ItemStack(Items.IRON_SWORD);
      };
      CompoundTag tag = new CompoundTag();
      tag.putString("ff", "prison_sword");
      stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
      stack.set(DataComponents.UNBREAKABLE, Unit.INSTANCE);
      if (enchants != null) {
         try {
            stack.enchant(enchants.getOrThrow(Enchantments.SHARPNESS), 1 + tier);
            stack.enchant(enchants.getOrThrow(Enchantments.UNBREAKING), 2 + tier);
         } catch (Exception ignored) {
         }
      }
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("§c§lPrison Sword" + (tier > 0 ? " §7(Tier " + (tier + 1) + ")" : "")));
      List<Component> lore = new java.util.ArrayList<>();
      lore.add(Component.literal("§7Never loses durability in prison."));
      lore.add(Component.literal("§7Sharpness " + (1 + tier) + " · Unbreaking " + (2 + tier)));
      lore.add(Component.literal("§7Upgrade it with prison cash in the Prison Menu."));
      stack.set(DataComponents.LORE, new ItemLore(lore));
      return stack;
   }

   private static boolean isPrisonSword(ItemStack stack) {
      if (stack == null || stack.isEmpty()) {
         return false;
      }
      CustomData data = (CustomData)stack.get(DataComponents.CUSTOM_DATA);
      return data != null && "prison_sword".equals(data.copyTag().getString("ff").orElse(""));
   }

   public static long swordUpgradeCost(int level) {
      return 2000L * (level + 1) * (level + 1);
   }

   /** Buys the next sword tier and replaces any prison sword in the inventory. */
   public static String swordUpgrade(ServerPlayer player) {
      UUID uuid = player.getUUID();
      int lvl = swordLevelOf(uuid);
      if (lvl >= SWORD_MAX) {
         return "Your prison sword is already maxed out (Tier " + (SWORD_MAX + 1) + ")!";
      }
      long cost = swordUpgradeCost(lvl);
      long bal = balanceOf(uuid);
      if (bal < cost) {
         return "Sword Tier " + (lvl + 2) + " costs " + Chat.moneyStr(cost) + " prison cash - you have " + Chat.moneyStr(bal) + ".";
      }
      wallets.put(uuid, bal - cost);
      swordLevels.put(uuid, lvl + 1);
      replaceSwords(player);
      Chat.raw(player, "§c§lSWORD UPGRADED to Tier " + (lvl + 2) + "!§r §7Your prison sword cuts deeper.");
      return null;
   }

   private static void replaceSwords(ServerPlayer player) {
      Inventory inv = player.getInventory();
      for (int i = 0; i < inv.getContainerSize(); i++) {
         if (isPrisonSword(inv.getItem(i))) {
            inv.setItem(i, ItemStack.EMPTY);
         }
      }
      ItemStack sword = prisonSwordFor(swordLevelOf(player.getUUID()), player.level().registryAccess().lookupOrThrow(Registries.ENCHANTMENT));
      InventoryHelper.giveOrDrop(player, sword);
   }

   /** Hands the player their current-tier prison sword (no-op if they already hold one). */
   private static void ensureSword(ServerPlayer player) {
      for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
         if (isPrisonSword(player.getInventory().getItem(i))) {
            return;
         }
      }
      ItemStack sword = prisonSwordFor(swordLevelOf(player.getUUID()), player.level().registryAccess().lookupOrThrow(Registries.ENCHANTMENT));
      player.getInventory().add(sword);
   }

   public static String sellHand(ServerPlayer player) {
      long total = 0L;
      ItemStack held = player.getMainHandItem();
      if (!held.isEmpty()) {
         long v = priceForItem(held);
         if (v > 0L) {
            if (v >= CONTRABAND_PRICE) {
               // The till's answer to the rich stone, in one line: not here.
               return "The till won't touch restricted stone - carry it to §fProcessing§7 on the intake pad and take the scan.";
            }
            total = v * held.getCount();
            held.shrink(held.getCount());
         }
      }
      return finishSell(player, total);
   }

   public static String sellAll(ServerPlayer player) {
      long total = 0L;
      long restricted = 0L;
      Inventory inv = player.getInventory();
      for (int i = 0; i < inv.getContainerSize(); i++) {
         ItemStack stack = inv.getItem(i);
         if (stack.isEmpty() || isPrisonStar(stack) || isPrisonPick(stack)) {
            continue;
         }
         long v = priceForItem(stack);
         if (v <= 0L) {
            continue;
         }
         if (v >= CONTRABAND_PRICE) {
            // Skipped rather than swallowed: a prison that quietly kept the diamond would be a
            // prison that stole from its prisoners, and the difference between "the till will not
            // buy it" and "it vanished" is the whole reason the stone is worth carrying.
            restricted += v * stack.getCount();
            continue;
         }
         total += v * stack.getCount();
         inv.setItem(i, ItemStack.EMPTY);
      }
      if (total <= 0L && restricted > 0L) {
         return "The till won't touch §e$" + String.format("%,d", restricted)
            + "§c of restricted stone. Carry it to §fProcessing§7 on the intake pad.";
      }
      String err = finishSell(player, total);
      if (err == null && restricted > 0L) {
         Chat.raw(
            player,
            "§7The till took the wages. §e$" + String.format("%,d", restricted)
               + "§7 of restricted stone is still in your bag - decla it at §fProcessing§7."
         );
      }
      return err;
   }

   /** Prison price for a held item - covers both blocks (silk touch) and the raw drops from mining. */
   private static long priceForItem(ItemStack stack) {
      if (stack == null || stack.isEmpty()) {
         return 0L;
      }
      long blockPrice = priceFor(fromItem(stack));
      if (blockPrice > 0L) {
         return blockPrice;
      }
      net.minecraft.world.item.Item item = stack.getItem();
      if (item == Items.COAL) {
         return 3L;
      } else if (item == Items.RAW_IRON) {
         return 6L;
      } else if (item == Items.RAW_GOLD) {
         return 12L;
      } else if (item == Items.REDSTONE) {
         return 5L;
      } else if (item == Items.LAPIS_LAZULI) {
         return 8L;
      } else if (item == Items.DIAMOND) {
         return 25L;
      } else if (item == Items.EMERALD) {
         return 20L;
      } else {
         return item == Items.NETHERITE_SCRAP ? 60L : 0L;
      }
   }

   private static String finishSell(ServerPlayer player, long total) {
      if (total <= 0L) {
         return "Nothing to sell - mine some blocks first!";
      }

      // One place pays, so the rank's rate can only ever be applied once - and the contract counts
      // what was *carried up*, not what the trustee rate multiplied it by, or a high rank would
      // finish its own sell contracts by existing.
      int percent = trusteePercent(rankOf(player.getUUID()));
      long paid = total * (100L + percent) / 100L;
      double till = PrisonEvents.tillMult(PrisonCellblock.clock(player.level()));
      if (till != 1.0) {
         paid = Math.round(paid * till);
      }
      wallets.put(player.getUUID(), balanceOf(player.getUUID()) + paid);
      PrisonCellblock.onSold(player, total);
      Chat.raw(
         player,
         "§aSold for §e$" + String.format("%,d", paid) + (percent > 0 ? "§7 (trustee +" + percent + "%)" : "")
            + (till != 1.0 ? " §6§lORE RUSH§r" : "")
            + "§a prison cash. Balance: §e$" + String.format("%,d", balanceOf(player.getUUID())) + "§a."
      );
      return null;
   }

   /** The block a dropped item corresponds to, for prison selling. */
   private static BlockState fromItem(ItemStack stack) {
      if (stack == null || stack.isEmpty()) {
         return null;
      }
      net.minecraft.world.item.Item item = stack.getItem();
      return net.minecraft.world.level.block.Block.byItem(item) == net.minecraft.world.level.block.Blocks.AIR
         ? null
         : net.minecraft.world.level.block.Block.byItem(item).defaultBlockState();
   }

   public static String rankUp(ServerPlayer player) {
      UUID uuid = player.getUUID();
      int rank = rankOf(uuid);
      if (rank >= RANKS - 1) {
         return "You're already at rank Z - the top of the prison!";
      }
      long cost = rankUpCost(rank);
      long bal = balanceOf(uuid);
      if (bal < cost) {
         return "Rank " + rankName(rank + 1) + " costs " + Chat.moneyStr(cost) + " prison cash - you have " + Chat.moneyStr(bal) + ".";
      }
      wallets.put(uuid, bal - cost);
      ranks.put(uuid, rank + 1);
      int tier = tierOf(rank + 1);
      ServerLevel level = player.level().getServer().getLevel(PRISON_DIM);
      if (level != null) {
         ensureBuilt(level);
         // Rank up does NOT yank you out of the combat arena, the fishing pond or the walkway
         // around the pit - you keep fighting/fishing right where you are and only get the new
         // mine unlocked. If you're in a mine, hop you to your new floor.
         //
         // The apron is on that list because of what "the arena" means: the fight is the pit, and a
         // prisoner who stepped back to the rail to breathe is still standing on the surface and not
         // in a mine. Ranking up there used to send them down the shaft, which is the "it spawns me
         // somewhere above the area" report - from their side, a rank-up took them up to a floor
         // they had never seen rather than leaving them where they were standing.
         if (!isInSideZone(player) && !isOnArenaApron(player)) {
            BlockPos spawn = freshFloorSpawn(player);
            player.teleport(new TeleportTransition(level, new Vec3(spawn.getX() + 0.5, spawn.getY() + 1.0, spawn.getZ() + 0.5), Vec3.ZERO, player.getYRot(), player.getXRot(), TeleportTransition.PLACE_PORTAL_TICKET));
            player.setRespawnPosition(new RespawnConfig(RespawnData.of(PRISON_DIM, spawn, 0.0F, 0.0F), true), false);
         } else {
            Chat.raw(player, "§7Stay in the zone - your new mine tier is ready whenever you return to the mine.");
         }
      }
      replacePicks(player);
      Chat.raw(player, "§d§lRANK UP! §f" + rankName(rank) + " §7→ §f" + rankName(rank + 1) + "§7! New mine tier unlocked. Next rank: §e$" + String.format("%,d", rankUpCost(rank + 1)) + "§7.");
      // The other half of a rank, said out loud, because a rate nobody can see is not a rate.
      Chat.raw(
         player,
         "§7Trustee rate: your ore now sells for §f+" + trusteePercent(rank + 1) + "%§7 · Tier §f" + (tier + 1)
            + " §7holds the richer rock."
      );
      // Celebrate notable rank milestones so prison feels alive, and feed the
      // newspaper a real story.
      int newRank = rank + 1;
      if (newRank % 5 == 0 || newRank == RANKS - 1) {
         for (ServerPlayer p : player.level().getServer().getPlayerList().getPlayers()) {
            Chat.raw(
               p,
               "§d§l⚒ " + player.getName().getString() + "§r§d climbed the prison ladder to rank §f§l" + rankName(newRank) + "§r§d!"
            );
         }
         com.fortuneandfavors.economy.ServerNewspaperManager.logEvent(
            player.level().getServer(),
            player.getName().getString() + " climbed to prison rank " + rankName(newRank) + "."
         );
      }
      return null;
   }

   public static String convertAll(ServerPlayer player) {
      UUID uuid = player.getUUID();
      long bal = balanceOf(uuid);
      if (bal < 500L) {
         return "Convert at least 500 prison cash (2 prison = 1 main cash).";
      }
      long main = bal / CONVERT_RATE;
      wallets.put(uuid, 0L);
      EconomyManager.addCash(uuid, main);
      Chat.raw(player, "§aConverted §e$" + String.format("%,d", bal) + "§a prison cash into §f$" + String.format("%,d", main) + "§a main cash!");
      return null;
   }

   /** Called when a mob is killed in the prison dimension - gives prison cash. */
   public static void onMobKill(ServerPlayer player, net.minecraft.world.entity.LivingEntity victim) {
      if (!isInPrison(player)) return;
      // Cell block guards are a bounty, not ordinary mob income - and taking one
      // down is the only way to bleed off the Heat they were sent to enforce.
      if (PrisonCellblock.isGuard(victim)) {
         if (victim instanceof net.minecraft.world.entity.Mob guard) {
            PrisonCellblock.onGuardKilled(player, guard);
         }
         return;
      }
      PrisonCellblock.onMobKilled(player);
      long reward = victimKillReward(victim);
      if (reward > 0) {
         wallets.put(player.getUUID(), balanceOf(player.getUUID()) + reward);
         Chat.raw(player, "§a+§e$" + reward + "§a prison cash for slaying " + victim.getName().getString() + "§a.");
      }
   }

   /** Prison cash reward for killing a mob. */
   private static long victimKillReward(net.minecraft.world.entity.LivingEntity victim) {
      String id = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(victim.getType()).getPath();
      return switch (id) {
         case "zombie" -> 20L;
         case "skeleton" -> 25L;
         case "creeper" -> 35L;
         case "spider" -> 15L;
         case "enderman" -> 50L;
         case "witch" -> 40L;
         case "blaze" -> 60L;
         case "pillager" -> 35L;
         case "vindicator" -> 55L;
         case "ravager" -> 150L;
         case "evoker" -> 80L;
         case "ghast" -> 70L;
         case "guardian" -> 55L;
         case "elder_guardian" -> 300L;
         case "wither" -> 500L;
         case "ender_dragon" -> 1000L;
         case "iron_golem" -> 100L;
         case "hoglin", "zoglin" -> 45L;
         case "piglin" -> 30L;
         case "piglin_brute" -> 120L;
         case "warden" -> 2000L;
         default -> 10L; // any other mob
      };
   }

   /**
    * The least time between two full-price catches.
    *
    * <p>The pond pays per fish, and a fish is a thing a Lure III rod produces one of every five
    * seconds with nothing asked of the player but a right-click - so the purse has a clock on it.
    * A catch inside the window is not refused, because silence from a pond reads as a broken pond:
    * it still pays, and it pays the quick price - see {@link #catchPay}.
    */
   public static final long FISH_PAY_COOLDOWN_TICKS = 100L;
   /** And what a catch inside that window is worth: a trickle, never nothing. */
   private static final double FISH_QUICK_PAY = 0.25D;
   private static final Map<UUID, Long> lastPaidCatch = new HashMap<>();

   /**
    * Whether this catch is the pond's to pay for.
    *
    * <p>The money belongs to the harbour bench, and that is asked of where the prisoner is standing
    * rather than assumed from the dimension: a rod in a puddle dug out of a mine wall is the same
    * rod and the same fish, and paying it there is what "spam fishing is OP" was - the pond's wage,
    * paid anywhere in the block, with the walk out to the pier left out of it.
    */
   public static boolean pondPaysForCatch(ServerPlayer player) {
      return player != null && isInPrison(player) && isInPond(player);
   }

   /** What a catch pays: the rolled purse, cut to a trickle when the rod is already on the clock. */
   public static long catchPay(long rolled, boolean quick) {
      return quick ? Math.max(1L, Math.round(rolled * FISH_QUICK_PAY)) : rolled;
   }

   /** Called when a player fishes in prison - gives a cash reward scaled by the
    *  pond's enchanted rod. */
   public static void onFishCatch(ServerPlayer player) {
      if (!pondPaysForCatch(player)) {
         return;
      }
      UUID uuid = player.getUUID();
      long now = PrisonCellblock.clock(player.level());
      boolean quick = now - lastPaidCatch.getOrDefault(uuid, Long.MIN_VALUE) < FISH_PAY_COOLDOWN_TICKS;
      long rolled = 30L + RANDOM.nextInt(51); // $30-$80
      // Fishing Frenzy multiplies the catch itself, before the treasure roll, so the one-in-twenty
      // jackpot is five times a frenzy catch rather than a frenzy applied to five times a catch.
      double frenzy = PrisonEvents.fishMult(now);
      if (frenzy != 1.0) {
         rolled = Math.round(rolled * frenzy);
      }
      // Rare jackpot catch: 1 in 20 fish is a "treasure" worth 5x.
      boolean treasure = RANDOM.nextInt(20) == 0;
      if (treasure) {
         rolled *= 5L;
      }
      long reward = catchPay(rolled, quick);
      lastPaidCatch.put(uuid, now);
      wallets.put(uuid, balanceOf(uuid) + reward);
      if (treasure) {
         Chat.raw(player, "§6§lTREASURE CATCH! §r§7+§e$" + reward + "§7 prison cash!");
      }
      Chat.raw(player, "§b+§e$" + reward + "§b prison cash for fishing!");
   }

   // ---------- World / map ----------

   public static void tick(MinecraftServer server) {
      ServerLevel level = server.getLevel(PRISON_DIM);
      if (level == null) {
         return;
      }
      ensureBuilt(level);
      PrisonCellblock.tick(server);
      PrisonEvents.tick(server);
      // The once-a-second gates below, and the Pit's own round machine, count the *server's* ticks:
      // the block's clock carries a base from earlier sessions and need not be a multiple of twenty.
      long tick = server.getTickCount();
      for (ServerPlayer p : server.getPlayerList().getPlayers()) {
         ResourceKey<Level> dim = p.level().dimension();
         ResourceKey<Level> prev = lastDim.put(p.getUUID(), dim);
         if (prev != null && !prev.equals(dim)) {
            if (dim.equals(PRISON_DIM)) {
               givePrisonKit(p);               } else if (prev.equals(PRISON_DIM)) {
                  // Defensive: restore the stashed gear for any exit that bypasses leave().
                  abandonPit(p);
                  ItemStack[] saved = stashed.remove(p.getUUID());
                  if (saved != null) {
                     p.getInventory().clearContent();
                     restoreInventory(p, saved);
                  }
                  removeStars(p);
                  removeUniform(p);
                  prisonOrigin.remove(p.getUUID());
                  lastTier.remove(p.getUUID());
               }
         }
         if (dim.equals(PRISON_DIM)) {
            int tier = tierOf(rankOf(p.getUUID()));
            // A prisoner who changes floor leaves that floor's work behind - for everyone, so the
            // room they just emptied fills back in and there is always a full face to come back to.
            Integer was = lastTier.put(p.getUUID(), tier);
            if (was != null && was != tier && !tierOccupied(server, was, p.getUUID())) {
               buildFloor(level, TIER_FLOOR_Y[was], was);
               Chat.raw(p, "§8The seam you left behind has been worked back over behind you.");
            }
            if (tick % 20L == 0L) {
               ensureStar(p);
               givePrisonKit(p);
               // Max saturation so players never need to eat
               p.getFoodData().setFoodLevel(20);
               p.getFoodData().setSaturation(20.0F);
            }
            // The mine no longer refills itself on a timer. A block that comes back in front of a
            // prisoner - and, worse, over the glowstone landing - is the one thing a mine must never
            // do. A tier is put back when a prisoner leaves it and when they arrive on it again; a
            // prisoner who wants a fresh face without walking away uses the menu's Refresh Blocks.
            // (The pass itself lives on for the self-test, see refillFloorForTest.)
            // The checkpoint. A prisoner standing on the pad has their bag looked at, once a second
            // - and it reads first, then takes, so a player who sees a third about to go can step
            // off and mine something cheaper. Standing there is the consent. See cashOut.
            if (tick % 20L == 0L && atProcessing(p)) {
               cashOut(p);
            }
            // The Pit. A run only ticks while the prisoner is ACTUALLY standing in the box: the
            // old wave ticker ran from anywhere in the prison, so walking away mid-run left an
            // empty arena clearing itself and paying out round bonuses forever.
            if (tick % 20L == 0L) {
               if (isInArena(p) && !PrisonCellblock.isConfined(p.getUUID())) {
                  tickPit(p);
               } else if (pits.containsKey(p.getUUID())) {
                  abandonPit(p);
               }
            }
         }
      }
   }

   /** The prison menu star is ALWAYS in hotbar slot 9 (index 8); anything in the way gets moved. */
   private static void ensureStar(ServerPlayer p) {
      Inventory inv = p.getInventory();
      ItemStack star = inv.getItem(8);
      if (isPrisonStar(star)) {
         return;
      }
      if (!star.isEmpty()) {
         int free = -1;
         for (int i = 0; i < inv.getContainerSize(); i++) {
            if (i != 8 && inv.getItem(i).isEmpty()) {
               free = i;
               break;
            }
         }
         if (free >= 0) {
            inv.setItem(free, star);
         } else {
            InventoryHelper.giveOrDrop(p, star);
         }
         inv.setItem(8, ItemStack.EMPTY);
      }
      inv.setItem(8, prisonStar());
   }

   private static void ensureBuilt(ServerLevel level) {
      if (built) {
         return;
      }
      // The mine grew: floors are twenty apart where they were sixteen, and the walkway is five
      // tall where it was three. A world built before that keeps the old roof hanging in what is
      // now headroom, so the first thing this does is clear the two layers the old ceiling sat on.
      // One pass, once per start, and after it the old geometry is gone for good.
      sweepOldMine(level);
      sweepLanternLitter(level);
      // The same one-pass migration for the maximum-security wing: a sentence carves it out of the
      // block's own rock and gives the rock back when the sentence ends, so a world that has been
      // played before must not keep a wing from an older shape of it standing where the next one
      // will be cut. Why this matters in practice: a wing that was left standing becomes the
      // "original" the next sentence records, and the ending then gives back the *stale* wing -
      // including whatever hole a shape of it that no longer exists happened to leave in its floor.
      // See PrisonCellblock.sweepExecutionWing.
      PrisonCellblock.sweepExecutionWing(level);
      for (int tier = 0; tier < 8; tier++) {
         buildFloor(level, TIER_FLOOR_Y[tier], tier);
      }
      // The surface goes on last, because the mine's own air fill reaches up to floorY+ROOM_HEADROOM
      // and the intake platform used to sit inside tier zero's room. That is why the processing pad
      // was a ring of polished deepslate in the dark with a lodestone in it that existed until the
      // first floor was laid over it - a checkpoint whose own pad the map had erased.
      buildSpawnPlatform(level);
      buildCombatArena(level);
      buildSolitary(level);
      buildCellBlock(level);
      built = true;
   }

   /**
    * Clears the roof of the old, shorter mine.
    *
    * <p>The room used to be three blocks of air with a stone ceiling over it, sixteen blocks below
    * the tier above. It is now five blocks of air with the ceiling six up. Nothing about a taller
    * room removes the ceiling of the shorter one, and a world that has been played before keeps it
    * as a stone lid floating in the middle of the new headroom - so the two layers the old ceiling
    * occupied are wiped once, before anything is built, and the new roof is laid above them.
    */
   private static void sweepOldMine(ServerLevel level) {
      int[] oldFloors = {97, 81, 65, 49, 33, 17, 1, -15};
      for (int floorY : oldFloors) {
         fill(
            level, new BlockPos(-ROOM_HALF - 2, floorY + 4, -ROOM_HALF - 2),
            new BlockPos(ROOM_HALF + 2, floorY + 6, ROOM_HALF + 2), Blocks.AIR.defaultBlockState()
         );
      }
      // The copies buildFloor used to lay at twice each floor's height. Real tiers' bands are left
      // to buildFloor; the intake deck and the cell block's edge it cuts through are rebuilt right
      // after by ensureBuilt. Air is skipped, so after the first clean start this only reads.
      BlockState air = Blocks.AIR.defaultBlockState();
      for (int floorY : TIER_FLOOR_Y) {
         int lo = 2 * floorY - SEAM_DEPTH - BOUNDARY_DEPTH + 1;
         int hi = 2 * floorY + ROOM_HEADROOM + 1;
         for (int y = Math.max(lo, level.getMinY()); y <= Math.min(hi, level.getMaxY()); y++) {
            if (sectorAtY(y) != null) {
               continue; // a real tier's band - buildFloor overwrites all of it anyway
            }
            for (int x = -ROOM_HALF - 1; x <= ROOM_HALF + 1; x++) {
               for (int z = -ROOM_HALF - 1; z <= ROOM_HALF + 1; z++) {
                  setIfDifferent(level, new BlockPos(x, y, z), air);
               }
            }
         }
      }
   }

   /**
    * Picks the lanterns up off the floor.
    *
    * <p>The arena gates, the harbour shack and the processing pad all hung a floor lantern one
    * block above nothing, and a floor lantern with no support is not a light: it is an item on the
    * ground, one per light, forever, in the middle of the walkway. The placements are sea lanterns
    * now (see the builders), and this is the litter the old ones left behind.
    */
   private static void sweepLanternLitter(ServerLevel level) {
      for (net.minecraft.world.entity.item.ItemEntity drop : level.getEntitiesOfClass(
         net.minecraft.world.entity.item.ItemEntity.class,
         new net.minecraft.world.phys.AABB(-1.0E4, -128.0, -1.0E4, 1.0E4, 320.0, 1.0E4),
         e -> e.getItem().is(Items.LANTERN)
      )) {
         drop.discard();
      }
   }

   /**
    * The hole: a bricked-up cell for one, high above the mine so nobody wanders into it.
    *
    * <p>It was a 5x5x4 box of deepslate bricks with one lamp and three bars, which read as a map
    * volume rather than as a punishment - and its walls were ordinary bricks, so the pickaxe in the
    * prisoner's own hand opened it. It is a real cell now: a stone-brick room with a barred front,
    * a bunk, a sink, a window and light you can see by, floored and roofed one block thick in
    * reinforced deepslate. The reinforcement is the visible half of the rule and
    * {@link #isHoldingArea} is the other half - the one that does not care which tool you brought.
    */
   private static void buildSolitary(ServerLevel level) {
      int x = SOLITARY_X;
      int y = SOLITARY_Y;
      int z = SOLITARY_Z;
      int h = 4;
      fill(level, new BlockPos(x - 4, y - 1, z - 4), new BlockPos(x + 4, y - 1, z + 4), Blocks.POLISHED_DEEPSLATE.defaultBlockState());
      fill(level, new BlockPos(x - 4, y, z - 4), new BlockPos(x + 4, y + h, z + 4), Blocks.AIR.defaultBlockState());
      for (int dx = -4; dx <= 4; dx++) {
         for (int dz = -4; dz <= 4; dz++) {
            for (int dy = 0; dy <= h; dy++) {
               boolean shell = Math.max(Math.abs(dx), Math.abs(dz)) == 4 || dy == h;
               if (!shell) {
                  continue;
               }
               // A real cell has a dressed wall rather than one flat brick: a polished wainscot at
               // the floor, chiseled pilasters, the odd cracked brick and a band of tiles - so the
               // room reads as something built and then used, not as a filled-in volume.
               BlockState wall;
               if (dy == h) {
                  wall = Math.floorMod(dx + dz, 3) == 0
                     ? Blocks.CHISELED_DEEPSLATE.defaultBlockState()
                     : Blocks.POLISHED_DEEPSLATE.defaultBlockState();
               } else if (dy == 0) {
                  wall = Blocks.POLISHED_DEEPSLATE.defaultBlockState();
               } else {
                  int k = Math.floorMod(dx * 5 + dz * 3 + dy * 7, 13);
                  if (k == 0 || k == 7) {
                     wall = Blocks.CHISELED_DEEPSLATE.defaultBlockState();
                  } else if (k == 4) {
                     wall = Blocks.CRACKED_DEEPSLATE_BRICKS.defaultBlockState();
                  } else if (k == 9) {
                     wall = Blocks.DEEPSLATE_TILES.defaultBlockState();
                  } else {
                     wall = Blocks.DEEPSLATE_BRICKS.defaultBlockState();
                  }
               }
               level.setBlock(new BlockPos(x + dx, y + dy, z + dz), wall, 2);
            }
            // The roof of it is the one layer that is reinforced, because a roof is the face a
            // prisoner digs at with the most optimism.
            level.setBlock(new BlockPos(x + dx, y + h + 1, z + dz), Blocks.REINFORCED_DEEPSLATE.defaultBlockState(), 2);
         }
      }
      // The barred front, so it still reads as a jail and not as a box.
      for (int dz = -3; dz <= 3; dz++) {
         level.setBlock(new BlockPos(x - 4, y, z + dz), Blocks.IRON_BARS.defaultBlockState(), 2);
         level.setBlock(new BlockPos(x - 4, y + 1, z + dz), Blocks.IRON_BARS.defaultBlockState(), 2);
         level.setBlock(new BlockPos(x - 4, y + 2, z + dz), Blocks.IRON_BARS.defaultBlockState(), 2);
      }
      // The bunk, the sink, and a light that is not a single bulb: two embedded lamps so the cell is
      // dim but legible, which is what the punishment is - not being unable to see the walls.
      level.setBlock(new BlockPos(x + 3, y, z - 2), ((net.minecraft.world.level.block.Block)Blocks.WOOL.red()).defaultBlockState(), 2);
      level.setBlock(new BlockPos(x + 3, y, z - 1), ((net.minecraft.world.level.block.Block)Blocks.WOOL.red()).defaultBlockState(), 2);
      // A shelf and a light you can read by - hung under the ceiling rather than set into it, so
      // the room keeps its roof and not a pane of glass onto the void.
      level.setBlock(new BlockPos(x + 3, y + 3, z - 2), Blocks.POLISHED_DEEPSLATE.defaultBlockState(), 2);
      level.setBlock(new BlockPos(x, y + 3, z), Blocks.LANTERN.defaultBlockState(), 2);
      level.setBlock(new BlockPos(x + 3, y + 3, z + 2), Blocks.SEA_LANTERN.defaultBlockState(), 2);
      level.setBlock(new BlockPos(x + 3, y, z + 2), Blocks.CAULDRON.defaultBlockState(), 2);
      // The rest of the cell: a pillow on the bunk, a drain in the corner, a chain on the wall and
      // a little age in the corners - the furniture of a room somebody actually served time in.
      level.setBlock(new BlockPos(x + 3, y, z - 3), ((net.minecraft.world.level.block.Block)Blocks.WOOL.white()).defaultBlockState(), 2);
      level.setBlock(new BlockPos(x - 3, y - 1, z + 3), Blocks.IRON_TRAPDOOR.defaultBlockState(), 2);
      level.setBlock(new BlockPos(x + 3, y + 2, z + 3), Blocks.IRON_CHAIN.defaultBlockState(), 2);
      level.setBlock(new BlockPos(x + 3, y + 1, z + 3), Blocks.IRON_CHAIN.defaultBlockState(), 2);
      level.setBlock(new BlockPos(x - 3, y + 3, z - 3), Blocks.COBWEB.defaultBlockState(), 2);
      level.setBlock(new BlockPos(x + 2, y + 3, z + 3), Blocks.COBWEB.defaultBlockState(), 2);
      // A slot in the wall to watch the corridor through, so being in the hole is being looked at -
      // with a backing wall behind it, so the slot looks at stone rather than at the sky.
      level.setBlock(new BlockPos(x + 4, y + 2, z), Blocks.IRON_BARS.defaultBlockState(), 2);
      level.setBlock(new BlockPos(x + 5, y + 2, z), Blocks.DEEPSLATE_BRICKS.defaultBlockState(), 2);

      // ---- Grounding. The hole was a lone box in the void, and it read as exactly that: a map
      //      volume hanging in nothing. An annex the block built has a foundation under it, a
      //      battlement on it and lamps by the door, and now so does this one. None of it is
      //      interior, so none of it changes the room or the rule that owns it.
      // The plinth: a slab one under the floor, then a tapering stack of rock that drops away as it
      // goes, so the cell sits on a foundation rather than in the air.
      fill(level, new BlockPos(x - 7, y - 2, z - 7), new BlockPos(x + 7, y - 2, z + 7), Blocks.DEEPSLATE_BRICKS.defaultBlockState());
      for (int drop = 3; drop <= 11; drop++) {
         int r = Math.max(2, 7 - (drop - 2));
         fill(level, new BlockPos(x - r, y - drop, z - r), new BlockPos(x + r, y - drop, z + r), Blocks.DEEPSLATE.defaultBlockState());
      }
      // Corner buttresses: four outside columns from the plinth to the roof, with a crenellated
      // parapet along the top, so the box reads as a keep rather than as a crate.
      for (int cx : new int[]{-5, 5}) {
         for (int cz : new int[]{-5, 5}) {
            for (int dy = -2; dy <= h + 1; dy++) {
               level.setBlock(new BlockPos(x + cx, y + dy, z + cz), Blocks.CHISELED_DEEPSLATE.defaultBlockState(), 2);
            }
         }
      }
      for (int dx = -5; dx <= 5; dx++) {
         for (int dz = -5; dz <= 5; dz++) {
            if (Math.max(Math.abs(dx), Math.abs(dz)) != 5) {
               continue;
            }
            if (Math.floorMod(dx + dz, 2) == 0) {
               level.setBlock(new BlockPos(x + dx, y + h + 2, z + dz), Blocks.DEEPSLATE_BRICKS.defaultBlockState(), 2);
            }
         }
      }
      // ---- The hallway. The cell's barred front used to open onto a bare landing, and in a void
      //      world a bare landing is a view of nothing: from inside the hole a prisoner could see
      //      straight out at the sky. A corridor is what makes it a cell block - a roofed walk the
      //      guards take you down - so the bars look at a lit hallway and there is no sky over any
      //      of it. The cell block upstairs has the same shape, and now so does the hole.
      int hallX0 = x - 12;
      int hallX1 = x - 4;
      int hallZ = 3;
      fill(level, new BlockPos(hallX0, y - 1, z - hallZ), new BlockPos(hallX1, y - 1, z + hallZ), Blocks.POLISHED_DEEPSLATE.defaultBlockState());
      fill(level, new BlockPos(hallX0, y + 3, z - hallZ), new BlockPos(hallX1, y + 3, z + hallZ), Blocks.DEEPSLATE_BRICKS.defaultBlockState());
      fill(level, new BlockPos(hallX0, y, z - hallZ), new BlockPos(hallX1, y + 2, z + hallZ), Blocks.AIR.defaultBlockState());
      for (int hx = hallX0 - 1; hx <= hallX1 - 1; hx++) {
         for (int hy = y - 1; hy <= y + 3; hy++) {
            level.setBlock(new BlockPos(hx, hy, z - hallZ - 1), Blocks.DEEPSLATE_BRICKS.defaultBlockState(), 2);
            level.setBlock(new BlockPos(hx, hy, z + hallZ + 1), Blocks.DEEPSLATE_BRICKS.defaultBlockState(), 2);
         }
      }
      for (int hz = z - hallZ - 1; hz <= z + hallZ + 1; hz++) {
         for (int hy = y - 1; hy <= y + 3; hy++) {
            level.setBlock(new BlockPos(hallX0 - 1, hy, hz), Blocks.DEEPSLATE_BRICKS.defaultBlockState(), 2);
         }
      }
      // Chiseled pilasters every few paces, with a lantern hung between each pair and a lamp out of
      // the roof - a ceiling lamp is a pane of glass onto the sky, and the hallway must not have one.
      for (int hx = hallX0 + 2; hx <= hallX1 - 2; hx += 3) {
         for (int hy = y; hy <= y + 2; hy++) {
            level.setBlock(new BlockPos(hx, hy, z - hallZ - 1), Blocks.CHISELED_DEEPSLATE.defaultBlockState(), 2);
            level.setBlock(new BlockPos(hx, hy, z + hallZ + 1), Blocks.CHISELED_DEEPSLATE.defaultBlockState(), 2);
         }
         level.setBlock(new BlockPos(hx, y + 2, z), Blocks.LANTERN.defaultBlockState(), 2);
      }
      // A wainscot down both walls, so the corridor reads as a walk and not as a tube.
      for (int hx = hallX0; hx <= hallX1; hx++) {
         level.setBlock(new BlockPos(hx, y - 1, z - hallZ - 1), Blocks.POLISHED_DEEPSLATE.defaultBlockState(), 2);
         level.setBlock(new BlockPos(hx, y - 1, z + hallZ + 1), Blocks.POLISHED_DEEPSLATE.defaultBlockState(), 2);
      }
      // The guard's desk at the far end of the walk: a barrel, a cauldron and a lectern, so the
      // corridor ends at a post rather than at a wall.
      level.setBlock(new BlockPos(hallX0 + 1, y, z - 1), Blocks.BARREL.defaultBlockState(), 2);
      level.setBlock(new BlockPos(hallX0 + 1, y, z + 1), Blocks.CAULDRON.defaultBlockState(), 2);
      level.setBlock(new BlockPos(hallX0 + 2, y, z), Blocks.LECTERN.defaultBlockState(), 2);
      // A barred watch-window on the end wall, backed by stone so it looks inward, not outward.
      level.setBlock(new BlockPos(hallX0 - 1, y + 1, z), Blocks.IRON_BARS.defaultBlockState(), 2);
      level.setBlock(new BlockPos(hallX0 - 2, y + 1, z), Blocks.DEEPSLATE_BRICKS.defaultBlockState(), 2);
      // A slab under the corridor, so the hallway stands on the same foundation as the cell.
      fill(level, new BlockPos(hallX0 - 1, y - 2, z - hallZ - 1), new BlockPos(hallX1, y - 2, z + hallZ + 1), Blocks.DEEPSLATE_BRICKS.defaultBlockState());
   }

   /**
    * The cell block: one corridor with a room off it per rung of the cell ladder.
    *
    * <p>"Your cell" used to be a number and a line of menu text - there was no room, so buying the
    * next rung bought nothing a prisoner could stand in. There is a room now, and a prisoner who is
    * put down is put in it rather than sent to a respawn screen: see {@code PrisonCellblock} for
    * the death rule. The rooms are furnished in the order the ladder is climbed, so the rung you
    * paid for is the one you walk back into.
    */
   private static void buildCellBlock(ServerLevel level) {
      int y = SURFACE_Y;
      int cx = CELL_CORRIDOR_X;
      int z0 = CELL_FIRST_Z - 3;
      int z1 = CELL_FIRST_Z + (CELL_MAX) * CELL_SPACING + 3;
      // The corridor, as a closed box: it is reached by teleport and left by teleport, and the prison
      // world is a void, so a doorway here would be a doorway into a fall.
      fill(level, new BlockPos(cx - 1, y - 1, z0 - 1), new BlockPos(cx + 3, y + 3, z1 + 1), Blocks.AIR.defaultBlockState());
      fill(level, new BlockPos(cx - 1, y - 1, z0 - 1), new BlockPos(cx + 3, y - 1, z1 + 1), Blocks.POLISHED_DEEPSLATE.defaultBlockState());
      fill(level, new BlockPos(cx - 1, y + 3, z0 - 1), new BlockPos(cx + 3, y + 3, z1 + 1), Blocks.POLISHED_DEEPSLATE.defaultBlockState());
      for (int z = z0 - 1; z <= z1 + 1; z++) {
         for (int dy = 0; dy <= 2; dy++) {
            level.setBlock(new BlockPos(cx - 1, y + dy, z), Blocks.DEEPSLATE_BRICKS.defaultBlockState(), 2);
            level.setBlock(new BlockPos(cx + 3, y + dy, z), Blocks.DEEPSLATE_BRICKS.defaultBlockState(), 2);
         }
      }
      for (int x = cx - 1; x <= cx + 3; x++) {
         for (int dy = 0; dy <= 2; dy++) {
            level.setBlock(new BlockPos(x, y + dy, z0 - 1), Blocks.DEEPSLATE_BRICKS.defaultBlockState(), 2);
            level.setBlock(new BlockPos(x, y + dy, z1 + 1), Blocks.DEEPSLATE_BRICKS.defaultBlockState(), 2);
         }
      }
      // One light every six paces down the middle of the ceiling.
      for (int z = z0; z <= z1; z += 6) {
         level.setBlock(new BlockPos(cx + 1, y + 3, z), Blocks.SEA_LANTERN.defaultBlockState(), 2);
      }
      for (int i = 0; i <= CELL_MAX; i++) {
         int cz = CELL_FIRST_Z + i * CELL_SPACING;
         int west = cx - 6;
         // Interior, walls, floor, roof.
         fill(level, new BlockPos(west, y, cz - 2), new BlockPos(cx - 2, y + 2, cz + 2), Blocks.AIR.defaultBlockState());
         fill(level, new BlockPos(west - 1, y - 1, cz - 3), new BlockPos(cx - 1, y - 1, cz + 3), Blocks.POLISHED_DEEPSLATE.defaultBlockState());
         for (int dx = west - 1; dx <= cx - 1; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
               if (Math.abs(dx - west) > 5) {
                  continue;
               }
               for (int dy = 0; dy <= 3; dy++) {
                  boolean shell = dx == west - 1 || dx == cx - 1 || Math.abs(dz) == 3 || dy == 3;
                  if (!shell) {
                     continue;
                  }
                  BlockState wall = dy == 3
                     ? Blocks.POLISHED_DEEPSLATE.defaultBlockState()
                     : Math.floorMod(dx * 3 + dz * 7 + dy, 5) == 0
                        ? Blocks.CHISELED_DEEPSLATE.defaultBlockState()
                        : Blocks.DEEPSLATE_BRICKS.defaultBlockState();
                  level.setBlock(new BlockPos(dx, y + dy, cz + dz), wall, 2);
               }
            }
         }
         // The barred front, a bunk and a sink - and the rung of the ladder picks the finish.
         for (int dz = -2; dz <= 2; dz++) {
            level.setBlock(new BlockPos(cx - 1, y, cz + dz), Blocks.IRON_BARS.defaultBlockState(), 2);
            level.setBlock(new BlockPos(cx - 1, y + 1, cz + dz), Blocks.IRON_BARS.defaultBlockState(), 2);
         }
         level.setBlock(new BlockPos(cx - 1, y + 2, cz), Blocks.CHISELED_DEEPSLATE.defaultBlockState(), 2);
         level.setBlock(new BlockPos(west, y, cz - 1), ((net.minecraft.world.level.block.Block)Blocks.WOOL.red()).defaultBlockState(), 2);
         level.setBlock(new BlockPos(west, y, cz), ((net.minecraft.world.level.block.Block)Blocks.WOOL.red()).defaultBlockState(), 2);
         level.setBlock(new BlockPos(west, y, cz + 2), Blocks.CAULDRON.defaultBlockState(), 2);
         level.setBlock(new BlockPos(west, y + 3, cz), Blocks.SEA_LANTERN.defaultBlockState(), 2);
      }
   }

   /** Where a prisoner's own cell is - the rung of the ladder they have bought. */
   public static BlockPos cellSpawnFor(UUID uuid) {
      int rung = Math.max(0, Math.min(CELL_MAX, cellLevelOf(uuid)));
      // The open floor between the bunk (at x - 6) and the bars, not the bunk itself: a cell spawn on
      // the wool was a prisoner teleported into a solid block.
      return new BlockPos(CELL_CORRIDOR_X - 3, SURFACE_Y, CELL_FIRST_Z + rung * CELL_SPACING);
   }

   /** The self-test's view of the cell block: {corridorX, firstZ, spacing, cells, surfaceY}. */
   public static int[] cellLayoutForTest() {
      return new int[]{CELL_CORRIDOR_X, CELL_FIRST_Z, CELL_SPACING, CELL_MAX + 1, SURFACE_Y};
   }

   /** Builds the combat zone: a large OPEN flat arena (no ceiling, no sealed room)
    *  with a fishing pond beside it. Players get TP'd in and handed a weapon. */
   /** How wide the solid walkway around the pit is, in blocks. */
   private static final int APRON_WIDTH = 5;

   private static void buildCombatArena(ServerLevel level) {
      int y = ARENA_Y;
      int half = ARENA_HALF + 6;   // 14 - the outer wall line, which is what `isInArena` measures
      int pit = ARENA_HALF + 3;    // 11 - the fighting floor inside the rim
      // ---- 1. The ground the pit stands on, and then the ground around it.
      //
      // The arena used to be ONE layer of blocks floating in the void world: inside the rim
      // a mottle of sand, gravel and coarse dirt, and one step past the outer wall nothing at
      // all. Two reports came out of that - "it is just random dirt", and "then the void",
      // because a knockback over the wall, a step through a gate, or a chunk of floor broken
      // by anything at all was a fall with no bottom. So: a solid base under the whole thing,
      // a pit floor that reads as an arena rather than as somebody's garden, and an apron of
      // real ground wide enough to walk on out past the wall.
      int apron = half + APRON_WIDTH;
      for (int dx = -apron; dx <= apron; dx++) {
         for (int dz = -apron; dz <= apron; dz++) {
            int d = Math.max(Math.abs(dx), Math.abs(dz));
            boolean inArena = d <= half;
            BlockPos at = new BlockPos(ARENA_X + dx, y, ARENA_Z + dz);
            BlockState floor;
            if (!inArena) {
               // The apron: the same stone the walls are made of, kerbed at its edge so the
               // walkway reads as a walkway and not as a platform that ends in a cliff.
               floor = d == apron
                  ? Blocks.POLISHED_ANDESITE.defaultBlockState()
                  : Math.floorMod(dx * 7 + dz * 11, 9) == 0
                     ? Blocks.CRACKED_STONE_BRICKS.defaultBlockState()
                     : Blocks.STONE_BRICKS.defaultBlockState();
            } else if (d == half) {
               floor = Blocks.STONE_BRICKS.defaultBlockState();
            } else if (d == pit) {
               floor = Blocks.CHISELED_STONE_BRICKS.defaultBlockState();
            } else if (d > pit) {
               floor = Math.floorMod(dx * 3 + dz * 5, 6) == 0
                  ? Blocks.POLISHED_ANDESITE.defaultBlockState()
                  : Blocks.STONE_BRICKS.defaultBlockState();
            } else {
               // The pit itself. Sand that has been fought on: grain and gravel trodden
               // into it, with the odd cracked slab showing through. Deliberately no dirt -
               // coarse dirt in three of every thirteen blocks is what "random dirt" was.
               int r = Math.floorMod(dx * 5 + dz * 3, 13);
               floor = r == 0
                  ? Blocks.GRAVEL.defaultBlockState()
                  : r <= 2 ? Blocks.CRACKED_STONE_BRICKS.defaultBlockState() : Blocks.SAND.defaultBlockState();
            }
            level.setBlock(at, floor, 2);
            // A base under every block of it, and a second course under that: the floor is
            // the top of a solid mass rather than a sheet somebody can fall through the
            // edge of, and the world below it is no longer a thing to worry about.
            level.setBlock(at.below(), Blocks.STONE.defaultBlockState(), 2);
            level.setBlock(at.below(2), Blocks.DEEPSLATE.defaultBlockState(), 2);
            if (inArena || d > half + 1) {
               level.setBlock(new BlockPos(ARENA_X + dx, y + 1, ARENA_Z + dz), Blocks.AIR.defaultBlockState(), 2);
               level.setBlock(new BlockPos(ARENA_X + dx, y + 2, ARENA_Z + dz), Blocks.AIR.defaultBlockState(), 2);
            }
         }
      }
      // A railing along the outer edge of the apron, with a gap at each gate, so the
      // walkway around the pit is somewhere a prisoner can be knocked back onto rather than
      // somewhere they get knocked off.
      for (int dx = -apron; dx <= apron; dx++) {
         for (int dz = -apron; dz <= apron; dz++) {
            if (Math.max(Math.abs(dx), Math.abs(dz)) != apron) {
               continue;
            }
            boolean gateLine = (Math.abs(dx) <= 1 || Math.abs(dz) <= 1);
            if (gateLine) {
               continue;
            }
            level.setBlock(new BlockPos(ARENA_X + dx, y + 1, ARENA_Z + dz), Blocks.STONE_BRICK_WALL.defaultBlockState(), 2);
         }
      }
      // The landing brand: a prisoner teleported in from the menu arrives here, so the
      // middle of the pit is lit, flat, and the one place a horde never spawns on top of.
      fill(level, new BlockPos(ARENA_X - 1, y, ARENA_Z - 1), new BlockPos(ARENA_X + 1, y, ARENA_Z + 1), Blocks.GLOWSTONE.defaultBlockState());
      // ---- 2. The rim: a two-high curb around the pit, open at the four gates. Two blocks
      //         and not one, because a zombie walks up a single step - and open, so the pit
      //         does not become a cage that the player cannot climb out of either.
      for (int dx = -pit; dx <= pit; dx++) {
         for (int dz = -pit; dz <= pit; dz++) {
            if (Math.max(Math.abs(dx), Math.abs(dz)) != pit) {
               continue;
            }
            boolean gate = (Math.abs(dx) <= 1 && Math.abs(dz) == pit)
               || (Math.abs(dz) <= 1 && Math.abs(dx) == pit);
            if (gate) {
               continue;
            }
            level.setBlock(new BlockPos(ARENA_X + dx, y + 1, ARENA_Z + dz), Blocks.STONE_BRICKS.defaultBlockState(), 2);
            level.setBlock(new BlockPos(ARENA_X + dx, y + 2, ARENA_Z + dz),
               Math.floorMod(dx * 2 + dz * 3, 5) == 0
                  ? Blocks.POLISHED_ANDESITE.defaultBlockState()
                  : Blocks.STONE_BRICKS.defaultBlockState(), 2);
         }
      }
      // The gates: bars drawn back against the posts, a keystone over the opening, and a
      // lamp on the curb beside it so a gate reads from the far side of the pit.
      for (int[] gate : ARENA_GATES) {
         int gx = gate[0];
         int gz = gate[1];
         for (int off = -1; off <= 1; off++) {
            int px = ARENA_X + gx * pit + (gx == 0 ? off : 0);
            int pz = ARENA_Z + gz * pit + (gz == 0 ? off : 0);
            if (off == 0) {
               level.setBlock(new BlockPos(px, y + 3, pz), Blocks.CHISELED_STONE_BRICKS.defaultBlockState(), 2);
            } else {
               level.setBlock(new BlockPos(px, y + 1, pz), Blocks.IRON_BARS.defaultBlockState(), 2);
               level.setBlock(new BlockPos(px, y + 2, pz), Blocks.IRON_BARS.defaultBlockState(), 2);
            }
         }
         int lx = gx != 0 ? ARENA_X + gx * pit : ARENA_X + 2;
         int lz = gz != 0 ? ARENA_Z + gz * pit : ARENA_Z + 2;
         // A sea lantern on the curb post, not a hanging floor lantern in mid-air: the gate lamp
         // used to be the latter, which meant every gate dropped one lantern onto the sand.
         level.setBlock(new BlockPos(lx, y + 3, lz), Blocks.SEA_LANTERN.defaultBlockState(), 2);
      }
      // ---- 3. The wall: five blocks of stone brick on every face, a barred arch high in the
      //         middle of each one, a fence railing along the top, banners inside, and lamps
      //         out in the aisle so walking the rim is not walking in the dark.
      for (int dx = -half; dx <= half; dx++) {
         for (int dz = -half; dz <= half; dz++) {
            if (Math.max(Math.abs(dx), Math.abs(dz)) != half) {
               continue;
            }
            for (int dy = 1; dy <= 5; dy++) {
               level.setBlock(new BlockPos(ARENA_X + dx, y + dy, ARENA_Z + dz),
                  Math.floorMod(dx + dz + dy * 3, 7) == 0
                     ? Blocks.POLISHED_ANDESITE.defaultBlockState()
                     : Blocks.STONE_BRICKS.defaultBlockState(), 2);
            }
            level.setBlock(new BlockPos(ARENA_X + dx, y + 6, ARENA_Z + dz), Blocks.SPRUCE_FENCE.defaultBlockState(), 2);
         }
      }
      for (int[] gate : ARENA_GATES) {
         int gx = gate[0];
         int gz = gate[1];
         for (int off = -1; off <= 1; off++) {
            int px = ARENA_X + gx * half + (gx == 0 ? off : 0);
            int pz = ARENA_Z + gz * half + (gz == 0 ? off : 0);
            level.setBlock(new BlockPos(px, y + 3, pz), Blocks.AIR.defaultBlockState(), 2);
            level.setBlock(new BlockPos(px, y + 4, pz), Blocks.AIR.defaultBlockState(), 2);
            if (off == 0) {
               level.setBlock(new BlockPos(px, y + 5, pz), Blocks.CHISELED_STONE_BRICKS.defaultBlockState(), 2);
            }
         }
         // Banners on the inside of the wall, one colour per facing, hung from a rail.
         BlockState banner = ((net.minecraft.world.level.block.Block)(gx != 0 ? Blocks.WOOL.red() : Blocks.WOOL.black())).defaultBlockState();
         for (int side = -1; side <= 1; side += 2) {
            for (int row = 0; row < 3; row++) {
               int off = side * (3 + row * 2);
               int px = ARENA_X + gx * half + (gx == 0 ? off : 0);
               int pz = ARENA_Z + gz * half + (gz == 0 ? off : 0);
               level.setBlock(new BlockPos(px, y + 4, pz), Blocks.SPRUCE_PLANKS.defaultBlockState(), 2);
               level.setBlock(new BlockPos(px, y + 3, pz), banner, 2);
            }
         }
      }
      int[][] posts = {{-8, -8}, {8, -8}, {-8, 8}, {8, 8}};
      for (int[] post : posts) {
         level.setBlock(new BlockPos(ARENA_X + post[0], y + 1, ARENA_Z + post[1]), Blocks.SPRUCE_FENCE.defaultBlockState(), 2);
         level.setBlock(new BlockPos(ARENA_X + post[0], y + 2, ARENA_Z + post[1]), Blocks.SPRUCE_FENCE.defaultBlockState(), 2);
         level.setBlock(new BlockPos(ARENA_X + post[0], y + 3, ARENA_Z + post[1]), Blocks.SEA_LANTERN.defaultBlockState(), 2);
      }
      // ---- 4. The way out, which is not a way out: a barred service gate in the west wall.
      //         The pit is not allowed a second exit mid-wave, so the gate is closed and the
      //         horde stays inside the box `tickPit` counts. The corridor beyond it is the
      //         walk to the harbour, and it is built with the harbour so the bench under it
      //         cannot lag behind this wall by a block and drop somebody into the void.
      for (int cz = -1; cz <= 1; cz++) {
         level.setBlock(new BlockPos(ARENA_X - half, y + 1, ARENA_Z + cz), Blocks.IRON_BARS.defaultBlockState(), 2);
         level.setBlock(new BlockPos(ARENA_X - half, y + 2, ARENA_Z + cz), Blocks.IRON_BARS.defaultBlockState(), 2);
      }
      buildFishingHarbour(level);
   }

   /** The fishing harbour west of the pit: a bench in the void, a sand-bottomed pool with a
    *  stone curb, a plank pier on log pilings with lamps at the end, a bait shack, reeds
    *  where the bank meets the water and lily pads on it. The prison world is a void world,
    *  so every one of these blocks is also floor: a gap here is a very long fall. */
   private static void buildFishingHarbour(ServerLevel level) {
      int y = ARENA_Y;
      int cx = POND_X;
      int cz = ARENA_Z;
      int pool = POOL_HALF;
      // 1. The bench. Two layers of stone brick under everything, so the harbour is a floor
      //    and not a raft, with a beach over it where the walking happens.
      for (int dx = -POND_HALF_X; dx <= POND_HALF_X; dx++) {
         for (int dz = -POND_HALF_Z; dz <= POND_HALF_Z; dz++) {
            int d = Math.max(Math.abs(dx), Math.abs(dz));
            BlockState top;
            if (d <= pool + 1) {
               top = Blocks.STONE_BRICKS.defaultBlockState();
            } else if (Math.floorMod(dx * 3 + dz * 5, 7) == 0) {
               top = Blocks.GRAVEL.defaultBlockState();
            } else if (Math.floorMod(dx + dz, 4) == 0) {
               top = Blocks.COARSE_DIRT.defaultBlockState();
            } else {
               top = Blocks.SAND.defaultBlockState();
            }
            level.setBlock(new BlockPos(cx + dx, y, cz + dz), top, 2);
            level.setBlock(new BlockPos(cx + dx, y - 1, cz + dz), Blocks.STONE_BRICKS.defaultBlockState(), 2);
            level.setBlock(new BlockPos(cx + dx, y + 1, cz + dz), Blocks.AIR.defaultBlockState(), 2);
            level.setBlock(new BlockPos(cx + dx, y + 2, cz + dz), Blocks.AIR.defaultBlockState(), 2);
            // Three layers and not two, because the benches of an older harbour stood inside
            // this footprint: this pass is also what removes the lamp posts of a pool that no
            // longer exists, and a post's lamp is one block above its fence.
            level.setBlock(new BlockPos(cx + dx, y + 3, cz + dz), Blocks.AIR.defaultBlockState(), 2);
         }
      }
      // 2. The pool: two deep over a sand and gravel bed, with a stone curb along the rim.
      for (int dx = -pool; dx <= pool; dx++) {
         for (int dz = -pool; dz <= pool; dz++) {
            boolean rim = Math.max(Math.abs(dx), Math.abs(dz)) == pool;
            BlockPos at = new BlockPos(cx + dx, y, cz + dz);
            level.setBlock(at, rim ? Blocks.STONE_BRICKS.defaultBlockState() : Blocks.WATER.defaultBlockState(), 2);
            level.setBlock(at.below(), Blocks.WATER.defaultBlockState(), 2);
            level.setBlock(at.below(2),
               Math.floorMod(dx * 2 + dz * 3, 5) == 0 ? Blocks.GRAVEL.defaultBlockState() : Blocks.SAND.defaultBlockState(), 2);
         }
      }
      // 3. The pier: planks from the east bank out over the water, on log pilings, with a
      //    lamplit end. This is where a prisoner is put down to fish.
      for (int dx = pool - 4; dx <= POND_HALF_X; dx++) {
         for (int dz = -1; dz <= 1; dz++) {
            level.setBlock(new BlockPos(cx + dx, y, cz + dz), Blocks.SPRUCE_PLANKS.defaultBlockState(), 2);
         }
      }
      for (int dx = pool - 2; dx <= POND_HALF_X - 1; dx += 4) {
         for (int dz = -1; dz <= 1; dz += 2) {
            level.setBlock(new BlockPos(cx + dx, y - 1, cz + dz), Blocks.SPRUCE_LOG.defaultBlockState(), 2);
            level.setBlock(new BlockPos(cx + dx, y - 2, cz + dz), Blocks.SPRUCE_LOG.defaultBlockState(), 2);
         }
      }
      level.setBlock(new BlockPos(cx + pool - 4, y + 1, cz - 1), Blocks.SPRUCE_FENCE.defaultBlockState(), 2);
      level.setBlock(new BlockPos(cx + pool - 4, y + 1, cz + 1), Blocks.SPRUCE_FENCE.defaultBlockState(), 2);
      level.setBlock(new BlockPos(cx + pool - 4, y + 2, cz - 1), Blocks.SEA_LANTERN.defaultBlockState(), 2);
      level.setBlock(new BlockPos(cx + pool - 4, y + 2, cz + 1), Blocks.SEA_LANTERN.defaultBlockState(), 2);
      // 4. The bait shack on the north-west bank: a roof, a bench to work at, a barrel of bait
      //    and a lamp inside it.
      for (int dx = -12; dx <= -8; dx++) {
         for (int dz = -8; dz <= -5; dz++) {
            boolean door = dz == -5 && (dx == -10 || dx == -9);
            boolean wall = (dx == -12 || dx == -8 || dz == -8 || dz == -5) && !door;
            for (int dy = 1; dy <= 3; dy++) {
               level.setBlock(new BlockPos(cx + dx, y + dy, cz + dz),
                  wall ? Blocks.SPRUCE_PLANKS.defaultBlockState() : Blocks.AIR.defaultBlockState(), 2);
            }
            level.setBlock(new BlockPos(cx + dx, y + 4, cz + dz), Blocks.SPRUCE_PLANKS.defaultBlockState(), 2);
         }
      }
      level.setBlock(new BlockPos(cx - 10, y + 1, cz - 7), Blocks.CRAFTING_TABLE.defaultBlockState(), 2);
      level.setBlock(new BlockPos(cx - 11, y + 1, cz - 7), Blocks.BARREL.defaultBlockState(), 2);
      level.setBlock(new BlockPos(cx - 10, y + 3, cz - 6), Blocks.SEA_LANTERN.defaultBlockState(), 2);
      // 5. Reeds where the bank meets the water, lily pads on it, crates along the walk and
      //    lamps at the bench's corners.
      for (int spot : new int[]{-4, -1, 3}) {
         for (int h = 0; h < 3; h++) {
            level.setBlock(new BlockPos(cx + spot, y + 1 + h, cz - pool - 1), Blocks.SUGAR_CANE.defaultBlockState(), 2);
         }
      }
      for (int spot : new int[]{0, 5}) {
         for (int h = 0; h < 3; h++) {
            level.setBlock(new BlockPos(cx + spot, y + 1 + h, cz + pool + 1), Blocks.SUGAR_CANE.defaultBlockState(), 2);
         }
      }
      level.setBlock(new BlockPos(cx - 2, y + 1, cz + 3), Blocks.LILY_PAD.defaultBlockState(), 2);
      level.setBlock(new BlockPos(cx + 1, y + 1, cz - 2), Blocks.LILY_PAD.defaultBlockState(), 2);
      level.setBlock(new BlockPos(cx - 4, y + 1, cz - 1), Blocks.LILY_PAD.defaultBlockState(), 2);
      level.setBlock(new BlockPos(cx + 4, y + 1, cz + 4), Blocks.LILY_PAD.defaultBlockState(), 2);
      level.setBlock(new BlockPos(cx - 6, y + 1, cz + 7), Blocks.BARREL.defaultBlockState(), 2);
      level.setBlock(new BlockPos(cx + 9, y + 1, cz - 7), Blocks.BARREL.defaultBlockState(), 2);
      int[][] lamps = {
         {-POND_HALF_X, -POND_HALF_Z}, {POND_HALF_X, -POND_HALF_Z},
         {-POND_HALF_X, POND_HALF_Z}, {POND_HALF_X, POND_HALF_Z},
         {-POND_HALF_X, 0}, {POND_HALF_X, 0}
      };
      for (int[] lamp : lamps) {
         level.setBlock(new BlockPos(cx + lamp[0], y + 1, cz + lamp[1]), Blocks.SPRUCE_FENCE.defaultBlockState(), 2);
         level.setBlock(new BlockPos(cx + lamp[0], y + 2, cz + lamp[1]), Blocks.SEA_LANTERN.defaultBlockState(), 2);
      }
   }

   /**
    * Walks a prisoner into the Pit and starts the given fight.
    *
    * <p>One entry point for all five modes, because the interesting part of a run is the round
    * machine below and not the five ways of beginning one.
    *
    * @return null on success, or why the run could not start
    */
   public static String startPit(ServerPlayer player, PitMode mode) {
      if (player == null || mode == null) {
         return null;
      }
      if (!isInPrison(player)) {
         return "You must be in prison to fight in the Pit!";
      }
      ServerLevel level = (ServerLevel) player.level();
      ensureSword(player);
      // A run owns the prisoner until it is over: walking back in mid-run used to overwrite the
      // counter, which paid out for a fight that had been abandoned.
      if (pits.containsKey(player.getUUID())) {
         return "You are already in a fight - finish it or walk out of the Pit.";
      }
      player.teleport(new TeleportTransition(
         level, new Vec3(ARENA_X + 0.5, ARENA_Y + 1.0, ARENA_Z + 0.5),
         Vec3.ZERO, player.getYRot(), player.getXRot(), TeleportTransition.PLACE_PORTAL_TICKET
      ));
      pits.put(player.getUUID(), new Pit(mode, 1, 0L));
      spawnPitRound(level, player, mode, 1);
      Chat.raw(player, "§c§lTHE PIT · " + mode.label.toUpperCase() + "§r §7- " + mode.blurb + ".");
      Chat.raw(
         player,
         "§7Round §f1§7/§f" + mode.rounds + "§7 · each round pays §e$" + mode.cashFor(1)
            + "§7 and §d" + mode.tokensPerRound + " token(s)§7 · the whole run pays §d" + mode.clearBonus() + "§7 more."
      );
      if (mode == PitMode.BOUNTY) {
         Chat.raw(player, "§7The board's scale today: §f" + String.format("%.2f", bountyScale(topHeat(player.level().getServer()))) + "×§7 on a §cmost-wanted§7 head.");
      }
      if (mode == PitMode.CHAMPION) {
         Chat.raw(player, "§6This week's champion: §f" + championName() + "§6. Beat them and the title is yours.");
      }
      return null;
   }

   /** Kept as the Pit's plain front door: the old arena is the Gauntlet, exactly as it was. */
   public static String enterCombatZone(ServerPlayer player) {
      return startPit(player, PitMode.GAUNTLET);
   }

   /**
    * Which fight a prisoner is in, or null. Read by the menu so the tile can say so.
    */
   public static PitMode pitModeOf(UUID uuid) {
      Pit pit = pits.get(uuid);
      return pit == null ? null : pit.mode();
   }

   public static int pitRoundOf(UUID uuid) {
      Pit pit = pits.get(uuid);
      return pit == null ? 0 : pit.round();
   }

   /**
    * Test hook: seat a run without teleporting or spawning, so the round machine can be driven.
    *
    * <p>The round is seated *sent* and empty, which is exactly the state a live round is in once the
    * bodies it sent have been cleared: the round machine pays a round it has sent, and sending one
    * here would put a real body in the arena for every check that drives the machine.
    */
   public static void seatPitForTest(ServerPlayer player, PitMode mode, int round, long nextRoundTick) {
      pits.put(player.getUUID(), new Pit(mode, round, nextRoundTick));
      pitBodies.put(player.getUUID(), new HashSet<>());
      pitSent.add(player.getUUID());
   }

   /** Test hook: one trip through the round machine, exactly as the prison tick would call it. */
   public static void tickPitForTest(ServerPlayer player) {
      tickPit(player);
   }

   /**
    * Whether this prisoner has a Pit run seated right now.
    *
    * <p>Public because the combat zone's fight is a scene the block owns: it sends the Warden
    * himself into the arena, and a scene that brought its own man is not a patrol the quiet-Heat
    * rule is allowed to call off. See PrisonCellblock.sceneOwnsGuards.
    */
   public static boolean pitLive(UUID uuid) {
      return uuid != null && pits.containsKey(uuid);
   }

   /** Test hook: whether a run is still seated. */
   public static boolean pitLiveForTest(UUID uuid) {
      return pitLive(uuid);
   }

   /** Test hook: put the Pit back the way it was found. */
   public static void forgetPitForTest(UUID uuid) {
      pits.remove(uuid);
      pitBodies.remove(uuid);
      pitSent.remove(uuid);
   }

   /** Test hook: how many bodies the current round is waiting on. */
   public static int pitBodiesForTest(UUID uuid) {
      Set<UUID> bodies = pitBodies.get(uuid);
      return bodies == null ? 0 : bodies.size();
   }

   /**
    * The block's hottest record, which is what a Bounty run is priced against.
    *
    * <p>Nobody hot means the enforcers have nothing to collect: a Bounty run in a quiet block still
    * runs, it just pays its own cash and no scale, which is the honest answer.
    */
   public static int topHeat(MinecraftServer server) {
      if (server == null) {
         return 0;
      }
      int top = 0;
      for (ServerPlayer p : server.getPlayerList().getPlayers()) {
         if (isInPrison(p)) {
            top = Math.max(top, PrisonCellblock.heatOf(p.getUUID()));
         }
      }
      return top;
   }

   /** Everything currently standing in the Pit, from the walled box the fights happen inside. */
   private static List<net.minecraft.world.entity.monster.Monster> pitMobs(ServerLevel level) {
      return level.getEntitiesOfClass(
         net.minecraft.world.entity.monster.Monster.class,
         new net.minecraft.world.phys.AABB(
            ARENA_X - ARENA_WALL_HALF, ARENA_Y, ARENA_Z - ARENA_WALL_HALF,
            ARENA_X + ARENA_WALL_HALF, ARENA_Y + 8, ARENA_Z + ARENA_WALL_HALF
         )
      );
   }

   /** Sends one round: the bodies the mode asks for, or the mode's own single entrant. */
   private static void spawnPitRound(ServerLevel level, ServerPlayer player, PitMode mode, int round) {
      Set<UUID> sent = pitBodies.computeIfAbsent(player.getUUID(), k -> new HashSet<>());
      sent.clear();
      pitSent.add(player.getUUID());
      if (mode == PitMode.WARDEN) {
         // The Warden is the block's own man rather than a body this file invents, so the fight and
         // the manhunt cannot become two different Wardens. If he is already out there, the run has
         // its opponent already.
         Mob man = PrisonCellblock.callWarden(player);
         if (man != null) {
            sent.add(man.getUUID());
         } else {
            pitSent.remove(player.getUUID());
            Chat.raw(player, "§7The Warden is not answering - the fight will start the moment the block sends him.");
         }
         return;
      }
      if (mode == PitMode.CHAMPION) {
         Mob champion = arenaMob(level, mode.waveOf(round));
         if (champion != null) {
            champion.setCustomName(net.minecraft.network.chat.Component.literal("§6§l" + championName()));
            champion.setCustomNameVisible(true);
            var hp = champion.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH);
            if (hp != null) {
               hp.setBaseValue(hp.getBaseValue() * 4.0);
            }
            champion.setHealth(champion.getMaxHealth());
            if (level.addFreshEntity(champion)) {
               sent.add(champion.getUUID());
            }
         }
         return;
      }
      int bodies = mode.bodies(round);
      int wave = mode.waveOf(round);
      for (int i = 0; i < bodies; i++) {
         Mob mob = arenaMob(level, wave, i);
         if (mob != null && level.addFreshEntity(mob)) {
            sent.add(mob.getUUID());
         }
      }
   }

   /**
    * Pays out a finished run and puts the Pit back the way it was found.
    *
    * <p>The Champion's prize is deliberately two things a prisoner cannot keep by standing still: a
    * title, and restricted stone. The stone is the point - the Pit's best reward is a bag that still
    * has to be carried past a scanner, which is the loop the whole block is built around.
    */
   private static void finishPit(ServerPlayer player, Pit pit) {
      pits.remove(player.getUUID());
      // The run bonus is part of the Pit's payout, so Pit Night raises it the same way it raises a
      // round - "the Pit pays double" has to mean the run, not just the parts before the last one.
      long tokens = Math.round(pit.mode().clearBonus() * PrisonEvents.pitTokenMult(PrisonCellblock.clock(player.level())));
      giveTokens(player.getUUID(), tokens);
      if (pit.mode() == PitMode.CHAMPION) {
         TitleManager.unlock(player, CHAMPION_TITLE);
         for (int i = 0; i < 3; i++) {
            InventoryHelper.giveOrDrop(player, new ItemStack(Items.DIAMOND_ORE));
         }
      }
      Chat.raw(player, "§6§l" + pit.mode().label.toUpperCase() + " COMPLETE§r §7- +§d" + tokens + " tokens.");
      if (pit.mode() == PitMode.CHAMPION) {
         Chat.raw(player, "§7Three diamond ore in the bag - and the block will not buy them here. §fTake them to Processing§7.");
      }
   }

   /**
    * The round machine: one call per prison tick while a prisoner is in the Pit.
    *
    * <p>Round payout happens exactly once, on the tick the box goes quiet, and the flag that says
    * so is the next-round clock - a cleared round that has been paid carries a non-zero value there
    * and cannot be paid twice. The old wave ticker used a second map for the same fact, which is how
    * it managed to pay a wave twice after a restart.
    */
   private static void tickPit(ServerPlayer player) {
      UUID uuid = player.getUUID();
      Pit pit = pits.get(uuid);
      if (pit == null) {
         return;
      }
      ServerLevel level = (ServerLevel)player.level();
      long now = PrisonCellblock.clock(level);
      Set<UUID> bodies = pitBodies.computeIfAbsent(uuid, k -> new HashSet<>());
      // The round's own roll call, taken against the fight rather than against the world: a body
      // that has died, despawned or been left behind by a restart is not part of this round any
      // more, and one that wandered out of the arena box still is. That is the whole difference
      // between a fight that stalls and a fight that finishes.
      bodies.removeIf(id -> {
         net.minecraft.world.entity.Entity body = level.getEntity(id);
         return body == null || !body.isAlive();
      });
      if (pit.nextRoundTick() != 0L) {
         if (now < pit.nextRoundTick()) {
            return;
         }
         int round = pit.round() + 1;
         pits.put(uuid, new Pit(pit.mode(), round, 0L));
         spawnPitRound(level, player, pit.mode(), round);
         Chat.raw(player, "§c§lROUND " + round + "§r §7- " + pit.mode().bodies(round) + " of them this time.");
         return;
      }
      if (!pitSent.contains(uuid)) {
         // The round has not been sent - a fight that survived a restart, or a spawn that failed on
         // the way in. Send it rather than pay for it: a run that stalls is a bug, and a run that
         // pays out for bodies that were never there is a payout for nothing.
         spawnPitRound(level, player, pit.mode(), pit.round());
         return;
      }
      if (!bodies.isEmpty()) {
         return;
      }
      {
         long cash = pit.mode().cashFor(pit.round());
         long tokens = pit.mode().tokensPerRound;
         if (pit.mode() == PitMode.BOUNTY) {
            cash = (long)(cash * bountyScale(topHeat(level.getServer())));
         }
         // The block's clock. Pit Night pays on every round of every run at once, which is what makes
         // it something two prisoners can be doing at the same moment rather than a private buff.
         double cashMult = PrisonEvents.pitCashMult(now);
         double tokenMult = PrisonEvents.pitTokenMult(now);
         if (cashMult != 1.0) {
            cash = Math.round(cash * cashMult);
         }
         if (tokenMult != 1.0) {
            tokens = Math.round(tokens * tokenMult);
         }
         String night = cashMult != 1.0 || tokenMult != 1.0 ? " §d§lPIT NIGHT§r" : "";
         wallets.put(uuid, balanceOf(uuid) + cash);
         giveTokens(uuid, tokens);
         // The round is spent: the roll call is empty and the run is not waiting on a body until the
         // next round is sent, which is what `pitSent` keeps honest.
         pitSent.remove(uuid);
         bodies.clear();
         if (pit.round() >= pit.mode().rounds) {
            Chat.raw(player, "§a§lROUND " + pit.round() + " CLEARED§r §7- +§e$" + cash + "§7, +§d" + tokens + " token(s)§7." + night);
            finishPit(player, pit);
            return;
         }
         pits.put(uuid, new Pit(pit.mode(), pit.round(), now + 200L));
         Chat.raw(
            player,
            "§a§lROUND " + pit.round() + " CLEARED§r §7- +§e$" + cash + "§7, +§d" + tokens + " token(s)§7. Next round in 10s." + night
         );
         return;
      }
   }

   /**
    * Ends a run because the prisoner walked out of the Pit.
    *
    * <p>The abandoned round's bodies are cleared with it - a Gauntlet's fourth wave left standing in
    * an empty arena is a wave that wanders into the walkway and starts hitting whoever comes back -
    * with the one exception of the Warden, who belongs to the manhunt rather than to this floor.
    */
   private static void abandonPit(ServerPlayer player) {
      Pit pit = pits.remove(player.getUUID());
      if (pit != null && pit.mode() != PitMode.WARDEN) {
         for (UUID id : pitBodies.getOrDefault(player.getUUID(), Set.of())) {
            net.minecraft.world.entity.Entity body = player.level().getEntity(id);
            if (body instanceof Mob mob && mob.isAlive()) {
               mob.discard();
            }
         }
      }
      pitBodies.remove(player.getUUID());
      pitSent.remove(player.getUUID());
      if (pit != null) {
         Chat.raw(player, "§7You left the Pit - the run is abandoned. No payout.");
      }
   }

   /**
    * Whether anybody else is working this tier. A floor may only be put back when nobody is on it.
    *
    * <p>Resetting a floor under a prisoner's feet would be a worse bug than the one it fixes, so
    * every caller of {@link #buildFloor} outside {@link #ensureBuilt} asks this first.
    */
   private static boolean tierOccupied(MinecraftServer server, int tier, UUID except) {
      if (server == null) {
         return false;
      }
      for (ServerPlayer other : server.getPlayerList().getPlayers()) {
         if (other.getUUID().equals(except) || !isInPrison(other)) {
            continue;
         }
         if (other.level().dimension().equals(PRISON_DIM) && tierOf(rankOf(other.getUUID())) == tier) {
            return true;
         }
      }
      return false;
   }

   /** Teleports the player to the fishing pond next to the combat zone and hands
    *  them an enchanted fishing rod. */
   public static String enterFishingPond(ServerPlayer player) {
      if (!isInPrison(player)) {
         return "You must be in prison to fish here!";
      }
      ServerLevel level = (ServerLevel) player.level();
      // Onto the pier, facing the water. It used to be the middle of the pool, which put a
      // prisoner in the water with a fishing rod - the one place in the harbour that is
      // not a place to stand.
      player.teleport(new TeleportTransition(level, new Vec3(POND_X + 11.5, ARENA_Y + 1.0, ARENA_Z + 0.5), Vec3.ZERO, 90.0F, 0.0F, TeleportTransition.PLACE_PORTAL_TICKET));
      giveFishingRod(player);
      Chat.raw(player, "§b§lFISHING POND§r §7- a fishing rod has been added to your inventory!");
      Chat.raw(player, "§7Each catch gives §e$30-$80§7 prison cash. Use the Prison Menu to return to the mine.");
      return null;
   }

   /** The fishing rod players get at the pond: Lure III, Luck of the Sea III, Unbreaking III. */
   public static void giveFishingRod(ServerPlayer player) {
      for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
         ItemStack s = player.getInventory().getItem(i);
         if (s.is(Items.FISHING_ROD) && isPrisonRod(s)) {
            return;
         }
      }
      ItemStack rod = new ItemStack(Items.FISHING_ROD);
      CompoundTag tag = new CompoundTag();
      tag.putString("ff", "prison_rod");
      rod.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
      rod.set(DataComponents.UNBREAKABLE, Unit.INSTANCE);
      try {
         var enchants = player.level().registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
         rod.enchant(enchants.getOrThrow(Enchantments.LURE), 3);
         rod.enchant(enchants.getOrThrow(Enchantments.LUCK_OF_THE_SEA), 3);
         rod.enchant(enchants.getOrThrow(Enchantments.UNBREAKING), 3);
      } catch (Exception ignored) {
      }
      rod.set(DataComponents.CUSTOM_NAME, Component.literal("§b§lPrison Fishing Rod"));
      rod.set(DataComponents.LORE, new ItemLore(List.of(
         Component.literal("§7Lure III · Luck of the Sea III · Unbreaking III"),
         Component.literal("§7Never breaks in prison.")
      )));
      player.getInventory().add(rod);
   }

   private static boolean isPrisonRod(ItemStack stack) {
      if (stack == null || stack.isEmpty()) {
         return false;
      }
      CustomData data = (CustomData)stack.get(DataComponents.CUSTOM_DATA);
      return data != null && "prison_rod".equals(data.copyTag().getString("ff").orElse(""));
   }

   /** Returns the player to their mine floor (the default zone). */
   public static String returnToMine(ServerPlayer player) {
      if (!isInPrison(player)) {
         return "You must be in prison to use that!";
      }
      ServerLevel level = (ServerLevel) player.level();
      BlockPos spawn = freshFloorSpawn(player);
      player.teleport(new TeleportTransition(level, new Vec3(spawn.getX() + 0.5, spawn.getY() + 1.0, spawn.getZ() + 0.5), Vec3.ZERO, player.getYRot(), player.getXRot(), TeleportTransition.PLACE_PORTAL_TICKET));
      Chat.raw(player, "§e§lBACK TO THE MINE§r §7- your ore floors await.");
      return null;
   }

   /** The actual arena wall radius (including the cobblestone wall border). */
   private static final int ARENA_WALL_HALF = ARENA_HALF + 6;

   /** True if the player is inside the combat arena box (including the walled area). */
   /**
    * How far from the middle of the Pit still counts as being in it.
    *
    * <p>The pit, its curb, and one pace of walkway - twelve blocks, where the outer wall is fourteen.
    * The walled apron in between is *outside* the fight, and treating it as inside is what "I left the
    * arena and it still says I am in it" was: a prisoner who stepped back to the rail to breathe was
    * still on the run's clock, still counted for the round, and could not start a fresh one.
    */
   private static final int ARENA_FLOOR_HALF = ARENA_HALF + 4;

   /** True if the player is inside the fight itself - not the apron around it. */
   public static boolean isInArena(ServerPlayer player) {
      if (!isInPrison(player)) return false;
      int dx = player.blockPosition().getX() - ARENA_X;
      int dz = player.blockPosition().getZ() - ARENA_Z;
      return Math.abs(dx) <= ARENA_FLOOR_HALF && Math.abs(dz) <= ARENA_FLOOR_HALF;
   }

   /** True for the walled apron and the harbour: the ring a prisoner walks to when they leave. */
   public static boolean isOnArenaApron(ServerPlayer player) {
      if (!isInPrison(player)) return false;
      int dx = player.blockPosition().getX() - ARENA_X;
      int dz = player.blockPosition().getZ() - ARENA_Z;
      return Math.abs(dx) <= ARENA_WALL_HALF && Math.abs(dz) <= ARENA_WALL_HALF;
   }

   /** True if the player is anywhere on the harbour bench (pool, pier or shack). */
   public static boolean isInPond(ServerPlayer player) {
      if (!isInPrison(player)) return false;
      int dx = player.blockPosition().getX() - POND_X;
      int dz = player.blockPosition().getZ() - ARENA_Z;
      return Math.abs(dx) <= POND_HALF_X && Math.abs(dz) <= POND_HALF_Z;
   }

   /** True if the player is in a non-mine zone (combat arena, fishing pond or Processing). */
   public static boolean isInSideZone(ServerPlayer player) {
      return isInArena(player) || isInPond(player) || atProcessing(player);
   }

   /** Test hook: one arena mob, built exactly as the wave builder builds it, but not put into
    *  the world - so the checks can read what the horde is issued without running a fight. */
   public static Mob arenaMobForTest(ServerLevel level, int wave) {
      return arenaMob(level, wave);
   }

   /**
    * Test hook: how the mine is shaped - {roomHalf, headroom, tierSpacing, surfaceY, bandDepth, seamDepth}.
    *
    * <p>The size of a mine floor is a decision about how a room feels, and it is the one part of that
    * decision that cannot be seen from a rule: the numbers are the only place "it is too cramped" and
    * "it is big enough now" can be told apart. Pinning them means the next person to change the
    * arithmetic has to change the sentence with it.
    */
   public static int[] roomLayoutForTest() {
      return new int[]{
         ROOM_HALF, ROOM_HEADROOM, TIER_FLOOR_Y[0] - TIER_FLOOR_Y[1], SURFACE_Y, BOUNDARY_DEPTH, SEAM_DEPTH
      };
   }

   /** Test hook: where each rank tier's floor sits, so geometry rules can be checked per floor. */
   public static int[] tierFloorsForTest() {
      return TIER_FLOOR_Y.clone();
   }

   /** Test hook: the processing pad's centre, so a live world can be asked what is built there. */
   public static int[] processingPosForTest() {
      return new int[]{PROCESS_X, PROCESS_Y, PROCESS_Z, PROCESS_HALF};
   }

   /** Test hook: hand a prisoner the intake kit, exactly as entering the block does. */
   public static void kitForTest(ServerPlayer player) {
      givePrisonKit(player);
   }

   /** Test hook: take the issued kit back, exactly as leaving the block does. */
   public static void removeUniformForTest(ServerPlayer player) {
      removeUniform(player);
   }

   /** Test hook: whether the given worn stack is the issued uniform. */
   public static boolean isUniformForTest(ItemStack stack) {
      return isUniform(stack);
   }

   /** Test hook: rebuild the intake platform and the pad above it. */
   public static void surfaceForTest(ServerLevel level) {
      buildSpawnPlatform(level);
   }

   /** Test hook: put a floor back, exactly as leaving a tier does. */
   public static void resetFloorForTest(ServerLevel level, int tier) {
      buildFloor(level, TIER_FLOOR_Y[Math.max(0, Math.min(TIER_FLOOR_Y.length - 1, tier))], tier);
   }

   /** Test hook: {arenaX, arenaZ, arenaWallHalf, pondX, pondHalfX, pondHalfZ, y, poolHalf}.
    *  The self-test cannot read the private layout constants, and the gap between the arena's
    *  west wall and the harbour bench is a fall out of a void world if it is ever one block. */
   public static int[] layoutForTest() {
      return new int[]{
         ARENA_X, ARENA_Z, ARENA_HALF + 6, POND_X, POND_HALF_X, POND_HALF_Z, ARENA_Y, POOL_HALF
      };
   }

   /**
    * The eight places a round's bodies are put down: the four gates first, then the four corners of
    * the fight floor. Spawning used to pick a gate at random per body with no memory of the last
    * pick, so a four-body round could put all four on one tile - which reads in play as a single
    * opponent who will not fall over.
    */
   private static final int[][] ARENA_RING = arenaRing();

   /**
    * The eight tiles a round can be put down on: the four gates first, in the order
    * {@link #ARENA_GATES} declares them, then the four corners of the fight floor for a wave bigger
    * than the gate count. Derived from the gates rather than written out beside them, because a gate
    * that moves and a spawn tile that does not is a body walking out of the wall.
    */
   private static int[][] arenaRing() {
      int edge = ARENA_HALF - 1;
      int corner = ARENA_HALF - 3;
      int[][] ring = new int[ARENA_GATES.length + 4][];
      for (int i = 0; i < ARENA_GATES.length; i++) {
         ring[i] = new int[]{ARENA_GATES[i][0] * edge, ARENA_GATES[i][1] * edge};
      }
      ring[ARENA_GATES.length] = new int[]{corner, corner};
      ring[ARENA_GATES.length + 1] = new int[]{-corner, corner};
      ring[ARENA_GATES.length + 2] = new int[]{corner, -corner};
      ring[ARENA_GATES.length + 3] = new int[]{-corner, -corner};
      return ring;
   }

   private static Mob arenaMob(ServerLevel level, int wave) {
      return arenaMob(level, wave, -1);
   }

   /** The two-argument form is the test hook's; a round passes the body's place in the wave. */
   private static Mob arenaMob(ServerLevel level, int wave, int index) {
      net.minecraft.world.entity.monster.zombie.Zombie mob =
         net.minecraft.world.entity.EntityTypes.ZOMBIE.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
      if (mob == null) {
         return null;
      }
      boolean veteran = wave >= 6 && RANDOM.nextInt(4) == 0;
      int start = index < 0 ? RANDOM.nextInt(ARENA_RING.length) : Math.floorMod(index, ARENA_RING.length);
      int spawnX = ARENA_X;
      int spawnZ = ARENA_Z;
      for (int step = 0; step < ARENA_RING.length; step++) {
         int[] spot = ARENA_RING[(start + step) % ARENA_RING.length];
         int x = ARENA_X + spot[0];
         int z = ARENA_Z + spot[1];
         if (level.getBlockState(new BlockPos(x, ARENA_Y + 1, z)).isAir()
            && level.getBlockState(new BlockPos(x, ARENA_Y + 2, z)).isAir()) {
            spawnX = x;
            spawnZ = z;
            break;
         }
      }
      mob.setPos(spawnX + 0.5, ARENA_Y + 1, spawnZ + 0.5);
      mob.setPersistenceRequired();
      mob.setCanPickUpLoot(false);
      mob.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
      mob.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET));
      if (veteran) {
         mob.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.LEATHER_CHESTPLATE));
         mob.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.LEATHER_LEGGINGS));
         mob.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.LEATHER_BOOTS));
      } else if (wave >= 4) {
         mob.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.LEATHER_BOOTS));
      }
      for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.MAINHAND, EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
         mob.setDropChance(slot, 0.0F);
      }
      // Health carries the escalation the gear used to carry.
      var hp = mob.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH);
      if (hp != null) {
         hp.setBaseValue(hp.getBaseValue() * (1.0 + 0.15 * (wave - 1)) * (veteran ? 1.6 : 1.0));
      }
      mob.setHealth(mob.getMaxHealth());
      return mob;
   }

   private static BlockPos floorSpawn(int tier) {
      return new BlockPos(0, TIER_FLOOR_Y[tier], 0);
   }

   /**
    * The blocks a landing is made of, relative to the world: the three-by-three pad a prisoner is
    * put down on, and the two blocks of headroom over it.
    *
    * <p>Pure geometry rather than a world edit, so "the pad covers the spot a prisoner is put down
    * on, and nothing else" can be asserted directly - see the harness check.
    */
   public static List<BlockPos> landingRepairPlan(int tier) {
      int floorY = TIER_FLOOR_Y[Math.max(0, Math.min(TIER_FLOOR_Y.length - 1, tier))];
      List<BlockPos> plan = new ArrayList<>(27);
      for (int dy = 0; dy <= 2; dy++) {
         for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
               plan.add(new BlockPos(dx, floorY + dy, dz));
            }
         }
      }
      return plan;
   }

   /**
    * The pad an arriving prisoner is put down on, put back if a previous shift has taken it up.
    *
    * <p>{@link #buildFloor} is skipped whenever another prisoner is standing on the tier, because
    * resetting a floor under somebody's feet is worse than the hole it would fix - and the arriving
    * prisoner is teleported to the middle of that same tier regardless. The middle of a tier is a
    * three-by-three of glowstone over the seam, and glowstone sells for nothing, so nothing refused
    * a shift that dug its own landing out: the next prisoner was put down over the hole and fell
    * into the mine they were meant to start at the face of. This is the repair that is safe to do
    * under somebody else's feet - the pad, and the air a body needs over it, and not one block of
    * anybody's seam.
    *
    * @see #landingRepairPlan for exactly which blocks that is
    */
   private static void prepareLanding(ServerLevel level, int tier) {
      int floorY = TIER_FLOOR_Y[Math.max(0, Math.min(TIER_FLOOR_Y.length - 1, tier))];
      BlockState pad = Blocks.GLOWSTONE.defaultBlockState();
      BlockState air = Blocks.AIR.defaultBlockState();
      for (BlockPos pos : landingRepairPlan(tier)) {
         setIfDifferent(level, pos, pos.getY() == floorY ? pad : air);
      }
   }

   /** A write only when the block is actually wrong, so landing on a sound pad costs nothing. */
   private static void setIfDifferent(ServerLevel level, BlockPos pos, BlockState state) {
      if (!level.getBlockState(pos).equals(state)) {
         level.setBlock(pos, state, 2);
      }
   }

   /**
    * Where a prisoner should be put down on their own floor, with the floor put back first.
    *
    * <p>Every menu action that moves a prisoner between floors - arriving, ranking up, being let out
    * of the hole - lands on the middle of a tier they have probably never stood on. The middle of a
    * tier is only a place to stand if the slab is there, and the slab is the one thing in this map
    * that prisoners take away: a floor somebody else has worked over is a hole with a landing pad
    * and six blocks of band under it. So the floor is put back before anybody is put down on it -
    * and only when nobody else is working it, because resetting a floor under another prisoner's
    * feet would be a worse bug than the one it fixes.
    */
   public static BlockPos freshFloorSpawn(ServerPlayer player) {
      int tier = tierOf(rankOf(player.getUUID()));
      if (player.level() instanceof ServerLevel level && level.dimension().equals(PRISON_DIM)) {
         if (!tierOccupied(player.level().getServer(), tier, player.getUUID())) {
            buildFloor(level, TIER_FLOOR_Y[tier], tier);
            // The rich seam used to be planted by the periodic refill; with that gone, a fresh floor
            // is where one is dropped instead - one per prisoner, on the face they are about to work.
            plantSeam(level, tier, player.getUUID());
         }
         // Whether the floor was rebuilt or left standing for its current shift, the pad under the
         // spawn is solid before anybody is put on it - see prepareLanding.
         prepareLanding(level, tier);
      }
      return floorSpawn(tier);
   }

   /**
    * Puts the prisoner's own tier back, on demand - the menu's Refresh Blocks.
    *
    * <p>The mine does not refill itself any more: a block appearing in front of a prisoner, and
    * especially over the glowstone landing, is exactly the bug the timer caused. A floor is
    * restored when it is left and when it is entered; this is the button for a prisoner who wants a
    * full face without walking off it. Refused while somebody else is working the same floor, since
    * rebuilding under another prisoner's feet would be a worse bug than the one it fixes.
    *
    * @return an error to show, or null on success
    */
   public static String refreshFloor(ServerPlayer player) {
      if (player == null || !isInPrison(player)) {
         return "You are not in the block.";
      }
      if (player.level() instanceof ServerLevel level) {
         // The floor the prisoner is actually standing on, not the one their rank would put them on:
         // a prisoner on somebody else's tier (or an older one they walked back to) was told their
         // own seam was full while the rock in front of them stayed mined out.
         int tier = tierStandingOn(player);
         if (tierOccupied(level.getServer(), tier, player.getUUID())) {
            return "Somebody else is working this floor - try again when it is yours.";
         }
         buildFloor(level, TIER_FLOOR_Y[tier], tier);
         // Deliberately no plantSeam: this puts the rock back, it does not hand out ore. A refresh
         // that seeded a fresh seam was free money on a button.
         Chat.raw(player, "§b§lFRESH FACE§r §7- the floor under you is whole again.");
         return null;
      }
      return "The mine is not ready yet - try again in a moment.";
   }

   /**
    * The mine tier whose floor this prisoner is standing on - the highest floor at or below them.
    *
    * <p>The tiers are a stack of slabs twenty apart, so "which floor am I on" is the one whose
    * walkway has not been passed yet. Used by Refresh Blocks so the button acts on the rock the
    * prisoner can see rather than the rock their rank says is theirs.
    */
   public static int tierStandingOn(ServerPlayer player) {
      int y = player == null ? 0 : (int)Math.floor(player.getY());
      int best = 0;
      long bestGap = Long.MAX_VALUE;
      for (int t = 0; t < TIER_FLOOR_Y.length; t++) {
         // Measured to the walkway, not the slab, so a prisoner halfway down their own seam is still
         // on their own floor: the slab of one tier is closer to its own walkway than to the next
         // floor down, and this is the number that keeps Refresh on the rock in front of them.
         long gap = Math.abs((long)y - (TIER_FLOOR_Y[t] + 1));
         if (gap < bestGap) {
            bestGap = gap;
            best = t;
         }
      }
      return best;
   }

   /**
    * The intake: the 21x21 deck a prisoner arrives on, its rail, and the processing pad.
    *
    * <p>It sits on {@link #SURFACE_Y}, eleven blocks above the roof of the shallowest seam, because
    * it used to sit at ninety-nine and shared its footprint with tier zero's room - which meant the
    * floor build laid its ore slab through the deck and filled the deck with air. What a prisoner
    * arrived on was invisible and the pad was a hole.
    */
   private static void buildSpawnPlatform(ServerLevel level) {
      int y = SURFACE_Y;
      fill(level, new BlockPos(-10, y - 1, -10), new BlockPos(10, y - 1, 10), Blocks.STONE_BRICKS.defaultBlockState());
      fill(level, new BlockPos(-10, y - 2, -10), new BlockPos(10, y - 2, 10), Blocks.STONE.defaultBlockState());
      fill(level, new BlockPos(-1, y - 1, -1), new BlockPos(1, y - 1, 1), Blocks.GLOWSTONE.defaultBlockState());
      for (int x = -10; x <= 10; x++) {
         fill(level, new BlockPos(x, y, -10), new BlockPos(x, y + 2, -10), Blocks.IRON_BARS.defaultBlockState());
         fill(level, new BlockPos(x, y, 10), new BlockPos(x, y + 2, 10), Blocks.IRON_BARS.defaultBlockState());
      }
      for (int z = -10; z <= 10; z++) {
         fill(level, new BlockPos(-10, y, z), new BlockPos(-10, y + 2, z), Blocks.IRON_BARS.defaultBlockState());
         fill(level, new BlockPos(10, y, z), new BlockPos(10, y + 2, z), Blocks.IRON_BARS.defaultBlockState());
      }
      for (int[] corner : new int[][]{{-10, -10}, {10, -10}, {-10, 10}, {10, 10}}) {
         level.setBlock(new BlockPos(corner[0], y - 1, corner[1]), Blocks.SEA_LANTERN.defaultBlockState(), 2);
      }
      buildProcessingRoom(level);
   }

   /**
    * The Processing room: a sealed hall off the intake deck's west side, joined to it by a short
    * corridor, with the scale set into its own floor.
    *
    * <p>Deliberately its own space rather than a patch of the deck: the deck is where the mine's
    * floor passes can reach, and a checkpoint a prisoner is teleported to has to be somewhere the
    * block controls completely. Everything here is rebuilt from scratch every time it is entered,
    * so a stale block from an earlier build can never be the thing a prisoner is dropped inside.
    */
   private static void buildProcessingRoom(ServerLevel level) {
      int y = PROCESS_Y;
      int r = PROCESS_ROOM_HALF;
      int h = PROCESS_ROOM_HEADROOM;
      int cx = PROCESS_X;
      int cz = PROCESS_Z;
      int ceil = y + h;
      // Floor and ceiling of the room itself.
      fill(level, new BlockPos(cx - r, y - 1, cz - r), new BlockPos(cx + r, y - 1, cz + r),
         Blocks.STONE_BRICKS.defaultBlockState());
      fill(level, new BlockPos(cx - r, ceil, cz - r), new BlockPos(cx + r, ceil, cz + r),
         Blocks.SMOOTH_STONE.defaultBlockState());
      // A ring of walls, and the interior cleared of everything - floor to ceiling - first.
      fill(level, new BlockPos(cx - r + 1, y, cz - r + 1), new BlockPos(cx + r - 1, y + h - 1, cz + r - 1),
         Blocks.AIR.defaultBlockState());
      for (int dx = -r; dx <= r; dx++) {
         for (int dy = 0; dy < h; dy++) {
            level.setBlock(new BlockPos(cx + dx, y + dy, cz - r), Blocks.DEEPSLATE_BRICKS.defaultBlockState(), 2);
            level.setBlock(new BlockPos(cx + dx, y + dy, cz + r), Blocks.DEEPSLATE_BRICKS.defaultBlockState(), 2);
         }
      }
      for (int dz = -r; dz <= r; dz++) {
         for (int dy = 0; dy < h; dy++) {
            level.setBlock(new BlockPos(cx - r, y + dy, cz + dz), Blocks.DEEPSLATE_BRICKS.defaultBlockState(), 2);
            level.setBlock(new BlockPos(cx + r, y + dy, cz + dz), Blocks.DEEPSLATE_BRICKS.defaultBlockState(), 2);
         }
      }
      // Lights in the four ceiling corners, so the room is a room and not a slot.
      for (int[] corner : new int[][]{{-r + 1, -r + 1}, {r - 1, -r + 1}, {-r + 1, r - 1}, {r - 1, r - 1}}) {
         level.setBlock(new BlockPos(cx + corner[0], ceil, cz + corner[1]), Blocks.SEA_LANTERN.defaultBlockState(), 2);
      }
      // The corridor: three wide, joining the room's east door to the deck's west edge.
      int doorZ0 = cz - 1;
      int doorZ1 = cz + 1;
      int westEdge = -10;
      fill(level, new BlockPos(cx + r + 1, y - 1, doorZ0), new BlockPos(westEdge, y - 1, doorZ1),
         Blocks.STONE_BRICKS.defaultBlockState());
      fill(level, new BlockPos(cx + r + 1, y, doorZ0), new BlockPos(westEdge, y + h - 1, doorZ1),
         Blocks.AIR.defaultBlockState());
      fill(level, new BlockPos(cx + r + 1, ceil, doorZ0 - 1), new BlockPos(westEdge, ceil, doorZ1 + 1),
         Blocks.SMOOTH_STONE.defaultBlockState());
      for (int dx = cx + r + 1; dx <= westEdge; dx++) {
         for (int dy = 0; dy < h; dy++) {
            level.setBlock(new BlockPos(dx, y + dy, doorZ0 - 1), Blocks.DEEPSLATE_BRICKS.defaultBlockState(), 2);
            level.setBlock(new BlockPos(dx, y + dy, doorZ1 + 1), Blocks.DEEPSLATE_BRICKS.defaultBlockState(), 2);
         }
      }
      // Open the deck's own rail where the corridor meets it.
      for (int dz = doorZ0; dz <= doorZ1; dz++) {
         for (int dy = 0; dy <= 2; dy++) {
            level.setBlock(new BlockPos(westEdge, y + dy, dz), Blocks.AIR.defaultBlockState(), 2);
         }
      }
      // The scale itself, set into the room's floor.
      buildProcessing(level);
   }

   /**
    * The Processing pad: a scale set into the floor of the intake platform.
    *
    * <p>It is deliberately on the spawn platform rather than down a corridor somewhere. A prisoner
    * arrives here, and the pad is the first thing in the block that is not the mine - which is the
    * shape the whole loop wants: the stone comes up out of the sector, and the only place that will
    * look at it is the room you walk into when you come back up for air. It is a lodestone because a
    * lodestone is the one block in the game that obviously belongs to a checkpoint and does nothing
    * else, and the ring of polished deepslate is there so the pad has an edge to stand on rather
    * than being a differently-coloured patch of floor nobody notices.
    */
   private static void buildProcessing(ServerLevel level) {
      fill(
         level, new BlockPos(PROCESS_X - PROCESS_HALF, PROCESS_Y - 1, PROCESS_Z - PROCESS_HALF),
         new BlockPos(PROCESS_X + PROCESS_HALF, PROCESS_Y - 1, PROCESS_Z + PROCESS_HALF),
         Blocks.POLISHED_DEEPSLATE.defaultBlockState()
      );
      // Two blocks of headroom over the scale, cleared explicitly: a checkpoint a prisoner cannot
      // stand on is not a checkpoint, whatever happened to be built over it since the last start.
      fill(level, new BlockPos(PROCESS_X - PROCESS_HALF, PROCESS_Y, PROCESS_Z - PROCESS_HALF),
         new BlockPos(PROCESS_X + PROCESS_HALF, PROCESS_Y + 1, PROCESS_Z + PROCESS_HALF), Blocks.AIR.defaultBlockState());
      level.setBlock(new BlockPos(PROCESS_X, PROCESS_Y - 1, PROCESS_Z), Blocks.LODESTONE.defaultBlockState(), 2);
      // A lamp set into each corner of the scale - sea lanterns, in the floor, because a floor
      // lantern placed one block above nothing is an item on the ground and not a light (see
      // sweepLanternLitter).
      for (int[] corner : new int[][]{{-1, -1}, {1, -1}, {-1, 1}, {1, 1}}) {
         level.setBlock(
            new BlockPos(PROCESS_X + corner[0] * (PROCESS_HALF + 1), PROCESS_Y - 1, PROCESS_Z + corner[1] * (PROCESS_HALF + 1)),
            Blocks.SEA_LANTERN.defaultBlockState(), 2
         );
      }
      // The scale has a bright edge, so a pad whose whole job is to be stood on reads as one.
      for (int d = -PROCESS_HALF - 1; d <= PROCESS_HALF + 1; d++) {
         level.setBlock(new BlockPos(PROCESS_X + d, PROCESS_Y - 1, PROCESS_Z + PROCESS_HALF + 1), Blocks.CHISELED_STONE_BRICKS.defaultBlockState(), 2);
         level.setBlock(new BlockPos(PROCESS_X + d, PROCESS_Y - 1, PROCESS_Z - PROCESS_HALF - 1), Blocks.CHISELED_STONE_BRICKS.defaultBlockState(), 2);
         level.setBlock(new BlockPos(PROCESS_X + PROCESS_HALF + 1, PROCESS_Y - 1, PROCESS_Z + d), Blocks.CHISELED_STONE_BRICKS.defaultBlockState(), 2);
         level.setBlock(new BlockPos(PROCESS_X - PROCESS_HALF - 1, PROCESS_Y - 1, PROCESS_Z + d), Blocks.CHISELED_STONE_BRICKS.defaultBlockState(), 2);
      }
   }

   /**
    * A proper layered mine, not a flat floor: a walkway on top (with the spawn
    * pad and lit walls) over a 5-deep ore slab that gets richer the deeper you
    * dig, sealed underneath by a solid separator so lower tiers stay rank-gated.
    */
   private static void buildFloor(ServerLevel level, int floorY, int tier) {
      // The origin, not the floor: every offset below is already an absolute Y. With c at floorY
      // the whole room was built at twice its height - the pad at floorY, the mine floating far
      // above it, and the upper copies landing inside the intake deck and the tiers above.
      BlockPos c = BlockPos.ZERO;
      int half = ROOM_HALF;
      int top = floorY + ROOM_HEADROOM;   // head height - the ceiling rests on this
      int ceil = top + 1;
      // Walkway air, and it is five tall now rather than three: the room was a corridor you could
      // not jump in, which is most of why a 25x25 seam read as cramped. One block of slack past the
      // wall line as well, because the old room's wall is standing there in a world that predates
      // the change (see sweepOldMine).
      fill(level, c.offset(-half - 1, floorY + 1, -half - 1), c.offset(half + 1, top, half + 1), Blocks.AIR.defaultBlockState());
      fill(level, c.offset(-half, ceil, -half), c.offset(half, ceil, half), Blocks.STONE.defaultBlockState());
      // The light grid, set into the ceiling itself. Every five becomes every four, because the room
      // is bigger and a grid sized for 25x25 leaves the corners of a 33x33 dark.
      for (int x = -half + 2; x <= half - 2; x += 4) {
         for (int z = -half + 2; z <= half - 2; z += 4) {
            level.setBlock(c.offset(x, ceil, z), Blocks.GLOWSTONE.defaultBlockState(), 2);
         }
      }
      // Walkway surface + the SEAM_DEPTH-deep ore slab below. The seam is a shaft's worth of rock
      // now rather than a shelf; see SEAM_DEPTH for why it is nine and not six.
      for (int depth = 0; depth <= SEAM_DEPTH - 1; depth++) {
         for (int dx = -half; dx <= half; dx++) {
            for (int dz = -half; dz <= half; dz++) {
               level.setBlock(c.offset(dx, floorY - depth, dz), tierBlock(tier, dx * 7 + dz * 13 + depth * 5, depth), 2);
            }
         }
      }
      // ---- The security band. This used to be three rows of bedrock, which is the one material
      //      in the game that says "the map ran out" - a prisoner who dug into it learned nothing
      //      about the prison, only about the world's edges. It is a solid band of the block's own
      //      rock instead, sealed by the break funnel rather than by being unbreakable (see
      //      isBoundary), so the answer to digging at it is a sentence rather than a shrug. It
      //      reaches the ceiling of the sector below, so there is no depth in it anywhere that is
      //      not sealed - the seam is deeper now, not the wall thicker.
      fill(
         level, c.offset(-half, floorY - SEAM_DEPTH - BOUNDARY_DEPTH + 1, -half),
         c.offset(half, floorY - SEAM_DEPTH - 1, half), Blocks.DEEPSLATE.defaultBlockState()
      );
      // The top layer of the band is the face a prisoner actually sees, so it is the one layer
      // that is allowed to say what it is.
      fill(
         level, c.offset(-half, floorY - SEAM_DEPTH, -half), c.offset(half, floorY - SEAM_DEPTH, half),
         Blocks.REINFORCED_DEEPSLATE.defaultBlockState()
      );
      // The four walls: a shell of stone from the walkway floor to the ceiling.
      fill(level, c.offset(-half, floorY, -half), c.offset(-half, top, half), Blocks.STONE.defaultBlockState());
      fill(level, c.offset(half, floorY, -half), c.offset(half, top, half), Blocks.STONE.defaultBlockState());
      fill(level, c.offset(-half, floorY, -half), c.offset(half, top, -half), Blocks.STONE.defaultBlockState());
      fill(level, c.offset(-half, floorY, half), c.offset(half, top, half), Blocks.STONE.defaultBlockState());
      // ...and then the ore studs, ON TOP of the shell. They used to be written first and painted
      // over by it, which is why a wall that was supposed to say which floor you were on said
      // "stone" and nothing else.
      for (int dx = -half; dx <= half; dx++) {
         for (int y = floorY + 1; y <= top; y++) {
            if (Math.floorMod(dx * 3 + y, 9) == 0) {
               level.setBlock(c.offset(dx, y, -half), tierBlock(tier, dx + y, 0), 2);
               level.setBlock(c.offset(dx, y, half), tierBlock(tier, dx + y + 1, 0), 2);
               level.setBlock(c.offset(-half, y, dx), tierBlock(tier, dx + y + 2, 0), 2);
               level.setBlock(c.offset(half, y, dx), tierBlock(tier, dx + y + 3, 0), 2);
            }
         }
      }
      // The landing: a nine-square of glowstone under the shaft mouth, so arriving on a floor is
      // always arriving somewhere lit rather than wherever the old pad happened to be.
      fill(level, c.offset(-1, floorY, -1), c.offset(1, floorY, 1), Blocks.GLOWSTONE.defaultBlockState());
      // ---- Four pillars holding the roof up. A 33x33 room with nothing in it is a field; a mine is
      //      posts with a roof on them, and they give a prisoner something to break line of sight
      //      behind when the guards come down.
      for (int px : new int[]{-8, 8}) {
         for (int pz : new int[]{-8, 8}) {
            for (int dy = 0; dy <= ROOM_HEADROOM + 1; dy++) {
               level.setBlock(c.offset(px, floorY + 1 + dy, pz),
                  dy == 0 ? Blocks.CHISELED_STONE_BRICKS.defaultBlockState()
                     : dy == ROOM_HEADROOM + 1 ? Blocks.STONE.defaultBlockState()
                     : Blocks.DARK_OAK_LOG.defaultBlockState(), 2);
            }
         }
      }
      // ---- Three ore boulders standing proud of the walkway: the tier's own rock, one block up, so
      //      the middle of a bigger floor is something to work and something to walk around.
      int[][] boulders = {{-10, -3}, {5, 9}, {11, -8}};
      for (int i = 0; i < boulders.length; i++) {
         int bx = boulders[i][0];
         int bz = boulders[i][1];
         for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
               if (Math.abs(dx) + Math.abs(dz) > 1) {
                  continue;
               }
               level.setBlock(c.offset(bx + dx, floorY, bz + dz), tierBlock(tier, (bx + dx) * 7 + (bz + dz) * 13 + 17 * i + 3, 3), 2);
            }
         }
      }
      // ---- Mine dressing. Every block in the slab under your feet is income, so all of
      //      this goes on the walls and in the headroom instead: a timber frame on the
      //      nine-paces, a plank cornice under the ceiling, rubble in the rock, a lamp
      //      bracket on each of the four faces. None of it is mineable, so none of it is
      //      worth anything, and none of it reduces what the floor pays.
      for (int[] post : new int[][]{{-13, -13}, {13, -13}, {-13, 13}, {13, 13}, {-13, 0}, {13, 0}, {0, -13}, {0, 13}}) {
         for (int dy = 1; dy <= top - floorY; dy++) {
            level.setBlock(c.offset(post[0], floorY + dy, post[1]), Blocks.DARK_OAK_LOG.defaultBlockState(), 2);
         }
      }
      for (int dx = -half; dx <= half; dx++) {
         for (int dz = -half; dz <= half; dz++) {
            if (Math.max(Math.abs(dx), Math.abs(dz)) != half) {
               continue;
            }
            // Rubble in the wall, so a wall reads as rock rather than as brick.
            if (Math.floorMod(dx * 5 + dz * 3, 4) == 0) {
               level.setBlock(c.offset(dx, floorY + 1, dz), Blocks.GRAVEL.defaultBlockState(), 2);
               level.setBlock(c.offset(dx, floorY + 2, dz), Blocks.COBBLESTONE.defaultBlockState(), 2);
            }
            // The cornice the ceiling rests on - skipped where the wall already carries an
            // ore stud, because the studs are what tells a prisoner which floor they are on.
            if (Math.floorMod(dx * 3 + floorY + 3, 9) != 0) {
               level.setBlock(c.offset(dx, top, dz), Blocks.SPRUCE_PLANKS.defaultBlockState(), 2);
            }
         }
      }
      for (int[] lamp : new int[][]{{0, -half}, {0, half}, {-half, 0}, {half, 0}}) {
         level.setBlock(c.offset(lamp[0], floorY + 3, lamp[1]), Blocks.CHISELED_STONE_BRICKS.defaultBlockState(), 2);
         level.setBlock(c.offset(lamp[0], floorY + 4, lamp[1]), Blocks.SEA_LANTERN.defaultBlockState(), 2);
      }
      // Mob spawner at the far corner of each floor for combat income, on a chiselled base
      // so it reads as part of the works rather than as an accident of the generation.
      if (tier >= 1) {
         BlockPos spawnerPos = c.offset(half - 2, floorY + 1, half - 2);
         level.setBlock(spawnerPos.below(), Blocks.CHISELED_STONE_BRICKS.defaultBlockState(), 2);
         level.setBlock(spawnerPos, Blocks.SPAWNER.defaultBlockState(), 2);
         armSpawner(level, spawnerPos, tier);
         // Mark spawner position with a sea lantern above
         level.setBlock(spawnerPos.above(2), Blocks.SEA_LANTERN.defaultBlockState(), 2);
      }
   }

   private static final String[] SPAWNER_MOBS = {
      "zombie", "zombie", "husk", "skeleton", "stray", "spider", "wither_skeleton", "wither_skeleton"
   };

   /**
    * Gives a floor's spawner something to spawn. A bare spawner block has been empty since 1.19.3,
    * and the mine is lit far past the light a spawner's monsters accept, so it gets a mob and a
    * custom light rule that lets it work under the lamps.
    */
   private static void armSpawner(ServerLevel level, BlockPos pos, int tier) {
      if (!(level.getBlockEntity(pos) instanceof net.minecraft.world.level.block.entity.SpawnerBlockEntity spawner)) {
         return;
      }
      net.minecraft.nbt.CompoundTag entity = new net.minecraft.nbt.CompoundTag();
      entity.putString("id", "minecraft:" + SPAWNER_MOBS[Math.max(0, Math.min(SPAWNER_MOBS.length - 1, tier))]);
      net.minecraft.nbt.CompoundTag anyLight = new net.minecraft.nbt.CompoundTag();
      anyLight.putInt("min_inclusive", 0);
      anyLight.putInt("max_inclusive", 15);
      net.minecraft.nbt.CompoundTag rules = new net.minecraft.nbt.CompoundTag();
      rules.put("block_light_limit", anyLight.copy());
      rules.put("sky_light_limit", anyLight);
      net.minecraft.nbt.CompoundTag data = new net.minecraft.nbt.CompoundTag();
      data.put("entity", entity);
      data.put("custom_spawn_rules", rules);
      net.minecraft.nbt.CompoundTag root = new net.minecraft.nbt.CompoundTag();
      root.put("SpawnData", data);
      root.putShort("MaxNearbyEntities", (short)4);
      spawner.loadCustomOnly(net.minecraft.world.level.storage.TagValueInput.create(
         net.minecraft.util.ProblemReporter.DISCARDING, level.registryAccess(), root
      ));
      spawner.setChanged();
      level.sendBlockUpdated(pos, level.getBlockState(pos), level.getBlockState(pos), 3);
   }

   /** Ore distribution per tier; deeper layers (higher {@code depth}) mine richer. */
   private static BlockState tierBlock(int tier, int seed, int depth) {
      int r = Math.floorMod(seed * 31 + tier * 7, 100);
      int rich = Math.min(4, depth * 2);
      String base = tier >= 6 ? "deepslate" : "stone";
      switch (tier) {
         case 0 -> {
            if (r < 6 + rich) {
               return Blocks.COAL_ORE.defaultBlockState();
            }
            if (r < 9 + rich) {
               return Blocks.IRON_ORE.defaultBlockState();
            }
         }
         case 1 -> {
            if (r < 5 + rich) {
               return Blocks.COAL_ORE.defaultBlockState();
            }
            if (r < 9 + rich) {
               return Blocks.IRON_ORE.defaultBlockState();
            }
            if (r < 11 + rich) {
               return Blocks.GOLD_ORE.defaultBlockState();
            }
         }
         case 2 -> {
            if (r < 6 + rich) {
               return Blocks.IRON_ORE.defaultBlockState();
            }
            if (r < 10 + rich) {
               return Blocks.GOLD_ORE.defaultBlockState();
            }
            if (r < 13 + rich) {
               return Blocks.REDSTONE_ORE.defaultBlockState();
            }
         }
         case 3 -> {
            if (r < 5 + rich) {
               return Blocks.GOLD_ORE.defaultBlockState();
            }
            if (r < 9 + rich) {
               return Blocks.LAPIS_ORE.defaultBlockState();
            }
            if (r < 13 + rich) {
               return Blocks.IRON_ORE.defaultBlockState();
            }
         }
         case 4 -> {
            if (r < 4 + rich) {
               return Blocks.DIAMOND_ORE.defaultBlockState();
            }
            if (r < 9 + rich) {
               return Blocks.LAPIS_ORE.defaultBlockState();
            }
            if (r < 13 + rich) {
               return Blocks.GOLD_ORE.defaultBlockState();
            }
         }
         case 5 -> {
            if (r < 5 + rich) {
               return Blocks.DIAMOND_ORE.defaultBlockState();
            }
            if (r < 7 + rich) {
               return Blocks.EMERALD_ORE.defaultBlockState();
            }
            if (r < 11 + rich) {
               return Blocks.LAPIS_ORE.defaultBlockState();
            }
         }
         case 6 -> {
            if (r < 6 + rich) {
               return Blocks.DIAMOND_ORE.defaultBlockState();
            }
            if (r < 9 + rich) {
               return Blocks.EMERALD_ORE.defaultBlockState();
            }
            if (r < 12 + rich) {
               return Blocks.GOLD_ORE.defaultBlockState();
            }
         }
         default -> {
            if (r < 2 + rich) {
               return Blocks.ANCIENT_DEBRIS.defaultBlockState();
            }
            if (r < 7 + rich) {
               return Blocks.DIAMOND_ORE.defaultBlockState();
            }
            if (r < 10 + rich) {
               return Blocks.EMERALD_ORE.defaultBlockState();
            }
         }
      }
      return "deepslate".equals(base) ? Blocks.DEEPSLATE.defaultBlockState() : Blocks.STONE.defaultBlockState();
   }

   /** How many blocks one pass of {@link #refillFloor} may put back. */
   private static final int REFILL_PER_PASS = 8;
   /**
    * How far from a prisoner {@link #refillFloor} refuses to place a block, in blocks.
    *
    * <p>This is the whole of "do not spawn blocks on me": a body is a sphere the refill does not
    * touch, so whatever it puts back is always somewhere the prisoner has already walked away from.
    */
   private static final double REFILL_CLEARANCE = 6.0;

   /**
    * Slowly restores mined blocks across the ore slab so the mine never runs dry - and, a refill in
    * {@link #SEAM_CHANCE}, drops a rich seam into the wall for whoever is digging it.
    *
    * <p>The old pass picked twelve random air cells out of the whole slab every two seconds and
    * filled them, and the slab includes the walkway a prisoner stands on: blocks appeared under a
    * standing body, behind a digging player's head, and floating in the cavern they had just opened
    * out - which is exactly "the prison keeps respawning blocks above the pit". The rules are three
    * now, and each one is a mistake the old pass made:
    *
    * <ul>
    *   <li><b>Never the walk surface.</b> The top layer of the slab is the floor prisoners walk on;
    *       patching it puts a block under a standing body and a step where there was none. Only
    *       depth one and deeper is touched, so the floor you walk on stays the floor you made.</li>
    *   <li><b>Always onto something.</b> A cell is filled only when the block under it is solid, so a
    *       pass builds a pit back up from its own floor instead of leaving stone hanging in mid-air
    *       over a chamber.</li>
    *   <li><b>Never beside a body.</b> Nothing is placed within {@link #REFILL_CLEARANCE} of a
    *       player, so the block that comes back is never one they can turn around and see appear.</li>
    * </ul>
    */
   private static void refillFloor(ServerLevel level, int tier, UUID owner) {
      int floorY = TIER_FLOOR_Y[tier];
      int half = ROOM_HALF;
      int restored = 0;
      for (int tries = 0; tries < 48 && restored < REFILL_PER_PASS; tries++) {
         int x = RANDOM.nextInt(half * 2 + 1) - half;
         int z = RANDOM.nextInt(half * 2 + 1) - half;
         // Depth one and deeper: the top layer of the slab is the walkway.
         int depth = 1 + RANDOM.nextInt(SEAM_DEPTH - 1);
         BlockPos p = new BlockPos(x, floorY - depth, z);
         if (!level.getBlockState(p).isAir()) {
            continue;
         }
         BlockPos below = p.below();
         if (level.getBlockState(below).getCollisionShape(level, below).isEmpty()) {
            // Mid-air: filling here is how a mine grows stalactites. Build from a floor upward.
            continue;
         }
         if (bodyNear(level, p)) {
            continue;
         }
         level.setBlock(p, tierBlock(tier, x * 7 + z * 13 + depth * 5, depth), 2);
         restored++;
      }
      if (owner != null && RANDOM.nextInt(SEAM_CHANCE) == 0) {
         plantSeam(level, tier, owner);
      }
   }

   /**
    * True when a body is close enough to the cell that a block appearing there would be on them.
    *
    * <p>Measured against the prisoner's own feet rather than a block position, because a player
    * mid-jump or mid-fall is between two cells and both of them are theirs.
    */
   private static boolean bodyNear(ServerLevel level, BlockPos p) {
      for (ServerPlayer pl : level.players()) {
         if (refillWouldLandOn(p, pl.getX(), pl.getY(), pl.getZ())) {
            return true;
         }
      }
      return false;
   }

   /**
    * True when a block going in at {@code pos} would land on a body standing at the point given.
    *
    * <p>The rule itself, asked of two positions rather than of a level, so it can be pinned by a
    * test the same way it is applied in the world - and so the refill and the seam share exactly
    * one answer to "is somebody here".
    */
   public static boolean refillWouldLandOn(BlockPos pos, double bx, double by, double bz) {
      double dx = bx - (pos.getX() + 0.5);
      double dy = by - (pos.getY() + 0.5);
      double dz = bz - (pos.getZ() + 0.5);
      return dx * dx + dy * dy + dz * dz <= REFILL_CLEARANCE * REFILL_CLEARANCE;
   }

   /** Test hook: one refill pass over a floor, the way the tick loop would run it. */
   public static void refillFloorForTest(ServerLevel level, int tier) {
      refillFloor(level, Math.max(0, Math.min(TIER_FLOOR_Y.length - 1, tier)), null);
   }

   /**
    * Whether a prisoner may put this block down.
    *
    * <p>The cell block has no building trades. A prisoner who can place a block can wall off his
    * own tunnel behind him, bridge over the pit, or roof a guard into a corner - and the mine stops
    * being a place the block made and becomes a place the prisoner did. This is a rule about where
    * the block goes, not about the inventory: the stack stays in the hand, unspent, so a refusal
    * costs nothing but the attempt.
    */
   public static boolean mayPlace(ServerPlayer player, ItemStack held) {
      if (player == null || held == null || held.isEmpty()) {
         return true;
      }
      if (!isInPrison(player)) {
         return true;
      }
      return !(held.getItem() instanceof net.minecraft.world.item.BlockItem);
   }

   private static void fill(ServerLevel level, BlockPos min, BlockPos max, BlockState state) {
      for (int x = min.getX(); x <= max.getX(); x++) {
         for (int y = min.getY(); y <= max.getY(); y++) {
            for (int z = min.getZ(); z <= max.getZ(); z++) {
               level.setBlock(new BlockPos(x, y, z), state, 2);
            }
         }
      }
   }
}
