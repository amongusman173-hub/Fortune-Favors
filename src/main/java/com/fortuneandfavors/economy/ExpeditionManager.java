package com.fortuneandfavors.economy;

import com.fortuneandfavors.FortuneFavorsMod;
import com.fortuneandfavors.ModItems;
import com.fortuneandfavors.ModSounds;
import com.fortuneandfavors.VfxManager;
import com.fortuneandfavors.util.Chat;
import com.fortuneandfavors.util.JsonUtil;
import com.fortuneandfavors.util.SoundUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.LodestoneTracker;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.TeamColor;

/**
 * Extraction gameplay as a dungeon crawler: enter a dungeon, fight room by room, and
 * cash out alive. Dying or running out of time loses everything.
 *
 * <p><b>The dungeon is a maze of chambered halls, and every chamber is a fight you may pick.</b>
 * Rooms sit on an infinite grid, each one a pillared hall with a coffered ceiling, a lantern every
 * six paces and dressed columns climbing its walls. Nothing slams shut behind you: an archway you
 * walked through is still an archway when you want to walk back through it, and a pack that wakes
 * in a chamber is something to fight or something to leave. Slay a pack and a bounty lands in your
 * secured loot, and up to THREE doors groan open onto chambers nobody has walked yet. Choose one.
 * The maze never ends; the depth counter only ever goes up, and every chamber deeper than the last
 * pays more than the last.
 *
 * <p><b>Chambers have faces.</b> Treasure vaults, grand halls, trap floors, ore vaults, shrines,
 * drowned halls, web nests, mob dens, sanctuaries and more are rolled per chamber, so no two
 * rooms in a run look the same. Loot chests sit sealed until their chamber is cleared - the room
 * is the lock and the monsters are the key.
 */
public final class ExpeditionManager {
   public enum Type {
      DEEP_MINE("Deep Mine", "§7", Items.IRON_PICKAXE, 20 * 60 * 12, 1.5, "Low", "Low", "Endless stone halls with rich ore veins... and cave spiders in the dark."),
      MONSTER_CAVE("Monster Cave", "§c", Items.ZOMBIE_HEAD, 20 * 60 * 10, 2.0, "Extreme", "High", "Diamond-armored undead patrol every hall. They WILL chase you down."),
      CRYSTAL_CAVERN("Crystal Cavern", "§d", Items.AMETHYST_SHARD, 20 * 60 * 14, 2.5, "High", "Medium", "Amethyst halls and vindicator patrols guard the deepest loot chests."),
      VOID("Void Expedition", "§5", Items.NETHER_STAR, 20 * 60 * 16, 2.0, "Extreme", "High", "Black voidstone halls where blazes hunt by sound, skeletons by sight. The abyss watches."),
      SUNKEN_TEMPLE("Sunken Temple", "§9", Items.PRISMARINE_SHARD, 20 * 60 * 14, 3.0, "High", "Medium", "Drowned halls of prismarine where the tide kept the treasure - and the guards."),
      FROZEN_CRYPT("Frozen Crypt", "§f", Items.PACKED_ICE, 20 * 60 * 13, 2.5, "High", "Medium", "Blue-ice sepulchers, strays in the dark, and graves that pay like bank vaults."),
      MAGMA_FORGE("Magma Forge", "§6", Items.BLAZE_POWDER, 20 * 60 * 15, 3.5, "Extreme", "High", "An abandoned foundry over a magma sea. Everything here was forged - and everything burns.");

      public final String name;
      public final String color;
      public final Item icon;
      public final long durationTicks;
      public final double multiplier;
      public final String danger;
      public final String trapFreq;
      public final String desc;

      Type(String name, String color, Item icon, long durationTicks, double multiplier, String danger, String trapFreq, String desc) {
         this.name = name;
         this.color = color;
         this.icon = icon;
         this.durationTicks = durationTicks;
         this.multiplier = multiplier;
         this.danger = danger;
         this.trapFreq = trapFreq;
         this.desc = desc;
      }
   }

   /**
    * One rolled modifier per run, so two trips into the same site never play
    * out the same way. Shown in the action bar from the moment you arrive.
    */
   public enum Anomaly {
      STABLE("Stable Reality", "§7", 1.0, 1.0, 1.0, 1.0, "Nothing is wrong. That is the anomaly."),
      RICH_VEINS("Rich Veins", "§a", 2.0, 1.0, 1.0, 1.0, "Every ore you mine pays double."),
      BLOOD_MOON("Blood Moon", "§c", 1.0, 2.0, 1.0, 1.0, "Kills pay double - and the halls are packed with them."),
      GILDED_CACHES("Gilded Caches", "§6", 1.0, 1.0, 2.0, 1.0, "Every loot chest pays double."),
      UNSTABLE("Unstable Reality", "§5", 1.5, 1.5, 1.5, 0.7, "It collapses far sooner - everything pays half again.");

      public final String name;
      public final String colour;
      public final double oreMul;
      public final double mobMul;
      public final double chestMul;
      public final double durationMul;
      public final String desc;

      Anomaly(String name, String colour, double oreMul, double mobMul, double chestMul, double durationMul, String desc) {
         this.name = name;
         this.colour = colour;
         this.oreMul = oreMul;
         this.mobMul = mobMul;
         this.chestMul = chestMul;
         this.durationMul = durationMul;
         this.desc = desc;
      }

      public static Anomaly roll() {
         Anomaly[] pool = {RICH_VEINS, BLOOD_MOON, GILDED_CACHES, UNSTABLE};
         if (RANDOM.nextInt(10) < 3) {
            return STABLE;
         }
         return pool[RANDOM.nextInt(pool.length)];
      }
   }

   /** Every chamber face the maze can roll, in declaration order - the codex's own list. */
   public static List<Chamber> chamberFaces() {
      return List.of(Chamber.values());
   }

   /**
    * What a chamber is. Every chamber of the maze rolls one of these, and the roll decides
    * the look of the room, what stands in it, and what clearing it is worth.
    */
   public enum Chamber {
      ENTRANCE("The Threshold", "§a"),
      ARENA("Combat Pit", "§c"),
      GRAND_HALL("Grand Hall", "§6"),
      TREASURE_VAULT("Treasure Vault", "§6"),
      TRAP_FLOOR("Trap Floor", "§e"),
      ORE_VAULT("Ore Vault", "§b"),
      SANCTUARY("Sanctuary", "§d"),
      FOUNTAIN("Fountain Court", "§3"),
      MOB_DEN("Mob Den", "§c"),
      PILLAR_HALL("Pillar Hall", "§7"),
      CACHE("Smuggler's Cache", "§e"),
      SHRINE("Forgotten Shrine", "§5"),
      DROWNED_HALL("Drowned Hall", "§9"),
      WEB_NEST("Web Nest", "§8"),
      LIBRARY("Sunken Library", "§6"),
      ARMOURY("Armoury", "§7"),
      OUBLIETTE("Oubliette", "§8"),
      LARDER("Ruined Larder", "§e"),
      GARDEN("Sunken Garden", "§2"),
      FUNGAL_GROTTO("Fungal Grotto", "§d"),
      FORGE_HALL("Forge Hall", "§6"),
      BROKEN_CROSSING("Broken Crossing", "§9"),
      GALLERY("Gallery of Echoes", "§b"),
      WAGER_VAULT("Wager Vault", "§e"),
      MENAGERIE("Beast Menagerie", "§2"),
      OBSERVATORY("Star Observatory", "§3"),
      BATHHOUSE("Steam Baths", "§f"),
      CLOCKWORKS("Clockwork Hall", "§6"),
      OSSUARY("Ossuary", "§7"),
      GILDED_VAULT("Gilded Vault", "§6"),
      /**
       * The reading room: the site's other camp, and the one that has to be found.
       *
       * <p>A sanctuary is a room you fall into and leave mended. This is the same promise with a
       * price on it - bookshelf walls, a floor of planks and carpet, a table with an enchanting book
       * on it, and enchanted books in the chests - so that the run's one real decision, press on or
       * turn back, has somewhere to be made that is not a corridor.
       */
      REST_LIBRARY("Reader's Rest", "§6"),
      /**
       * The site's third camp: a caravan stopped mid-journey, fire still burning.
       *
       * <p>Both older camps are rooms built for another purpose and found quiet - a shrine's stone, a
       * library's chairs. This one is a camp on purpose, and it is the one a long descent meets most
       * often, because the middle of a run is where a health budget actually runs out.
       */
      WAYSTATION("Wayfarer's Waystation", "§6"),
      /**
       * The site's beautiful room: a nave of pillars, a ceiling of stars and an altar that pays.
       *
       * <p>Every other chamber in the maze is somewhere with a job. This one is somewhere to look at,
       * and it is here because a dungeon that is only ever a fight is a dungeon players stop walking
       * into. It costs a walk the length of a chamber and pays like a vault.
       */
      MOONLIT_CHAPEL("Moonlit Chapel", "§b"),
      /**
       * The alchemy hall: a witches' stillroom, and the one chamber the maze stocks with cures.
       *
       * <p>Every other fight room is a place to be crossed - you take the chest and you take the
       * wounds. This one is a room worth walking into hurt: the coven that took it over left its work
       * standing, and three of the cauldrons in it answer a right-click with an **instant mend and a
       * Potion of Healing to carry out of the room**. The price is the pack: a stillroom is the
       * biggest one the maze wakes, and nobody walks out of a witch's workshop quietly.
       */
      ALCHEMY("Alchemy Hall", "§5"),
      /**
       * The site's fourth camp, and the one that is only ever a camp.
       *
       * <p>A sanctuary is a shrine that happens to be quiet, a reading room is a library nobody is
       * reading in, and a waystation is a caravan that stopped: all three are places built for
       * something else and then found safe. This one is a glade with a fire in it and nothing else
       * at all - no pack, no trap, no puzzle, a mended body for as long as you stand in it - which
       * is what makes it the site's real answer to a health budget rather than one of its lucky
       * rooms. It mends better than any other camp and it can be rolled from the first chamber past
       * the threshold on, and the only thing it asks for is the walk.
       */
      CALM_CAMP("Calm Campplace", "§a"),
      FLOOR_BOSS("Floor Guardian's Arena", "§4");

      public final String name;
      public final String colour;

      Chamber(String name, String colour) {
         this.name = name;
         this.colour = colour;
      }
   }

   /**
    * A thing in a chamber that answers a right-click.
    *
    * <p>The maze used to offer exactly one verb - hit the pack, take the chest. Everything here
    * exists to give a chamber a second verb: something to spend a stake on, something to prise
    * open, something to light, something to pick up on the way through.
    */
   public enum Interact {
      /** Stake secured loot on a coin toss - double it or lose the stake. */
      WAGER("§6§lWager Pedestal"),
      /** A trapped reliquary: usually rich, sometimes a spring-loaded surprise. */
      RELIQUARY("§e§lSealed Reliquary"),
      /** One stone of a chamber's rune lock - light them all and the vault opens. */
      RUNE("§5§lRune Stone"),
      /** A supply crate: bandages, food and light for the road on. */
      SUPPLY("§a§lSupply Crate"),
      /** A cauldron left on the boil: an instant mend and a Potion of Healing to carry. */
      ELIXIR("§c§lHealing Draught");

      public final String label;

      Interact(String label) {
         this.label = label;
      }
   }

   /** The stake a wager costs, and the clean half-chance it pays. */
   public static final long WAGER_STAKE = 2_000L;
   /** How much a fully lit rune lock pays out. */
   public static final long RUNE_PAYOUT = 6_000L;

   // ------------------------------------------------------------------ room grid geometry
   //
   // The maze is a grid of chambers. Each cell owns a 28x28 footprint with a two-thick wall band
   // on every side (neighbours own their own band, so a wall between two halls is four blocks of
   // unbreakable stone) and a ten-tall interior. Doorways are two wide and three tall, centred on
   // the shared wall, so every archway lines up with its neighbour.
   //
   // The footprint is the single most load-bearing number here, because it is the one a player
   // feels. It has been wrong in both directions: at twenty-two a hall was a cupboard, and at
   // thirty-four it was a warehouse - thirty wide with a twelve-tall roof and one lantern in the
   // middle is a hall you cross in the dark, past nothing, on the way to the corner where the
   // fight is. Twenty-four of usable floor with a coffered ceiling, pilasters and a lantern grid
   // over it reads as architecture; the same blocks spread over a third more floor reads as an
   // empty room. Every chamber layout below is written relative to this constant rather than to
   // the size it happened to be when it was drawn.
   /** Chamber footprint, wall band included. */
   public static final int ROOM_PITCH = 28;
   /** Wall band thickness on each side of a cell. */
   private static final int WALL = 2;
   /**
    * Floor-to-ceiling span: floor blocks at Y, ceiling blocks at Y + ROOM_HEIGHT.
    *
    * <p>Ten. Enough headroom that the grand hall can raise its own roof three above this and the
    * pillar hall can hang a gallery off it, low enough that a light in the ceiling still reaches
    * the floor with some strength left - which at twelve it did not, and the halls were read as
    * caves rather than as rooms.
    */
   public static final int ROOM_HEIGHT = 10;
   /**
    * Spacing of the ceiling coffers, the wall pilasters and the lantern grid, in blocks.
    *
    * <p>One number for all three, deliberately: the beams cross the ceiling every COFFER paces,
    * a lantern hangs in the middle of every panel they cut, and a pilaster rises on the wall
    * directly under every beam. The dress lines up because it is all the same grid.
    */
   private static final int COFFER = 6;
   /**
    * How many blocks into a chamber an archway's walk lane is kept clear.
    *
    * <p>Nothing is ever placed in one: a chest, an ore stud or a baseboard block left in a
    * doorway mouth is a doorway that no longer opens.
    */
   private static final int DOOR_LANE = 3;
   /** Doorway width and height, measured from the first block above the floor. */
   private static final int DOOR_W = 2;
   private static final int DOOR_H = 3;
   /** Local (per-cell) coordinate of the first doorway column on either axis. */
   private static final int DOOR_LO = ROOM_PITCH / 2 - 1;

   /**
    * How tall the floor guardian's arena is, in blocks above the floor.
    *
    * <p>Twenty-two: the chamber's own roof is at ten, so the arena is that hall with a second one
    * standing on top of it and no floor in between - the only chamber in the maze that is one room
    * from the pit to the underside of the vault. The archways, the pit and the terrace all live in
    * the bottom ten, which is deliberate: the fight starts on the floor the dungeon built.
    */
   public static final int ARENA_HEIGHT = 22;
   /** How high the terrace around the arena's pit stands above the pit's floor. */
   public static final int ARENA_TERRACE = 3;
   /**
    * How many maze cells the guardian's arena spans, on each axis.
    *
    * <p>Three. The arena is the one chamber in the maze that is not a cell: it is a three-by-three
    * block of them - eighty-four blocks across, its roof at twenty-two - and the eight cells around
    * its middle are reserved the moment it is rolled, so the maze never grows into them and the
    * arena never has a wall in the middle of itself. It is the room a run is judged in, and it is
    * the size a room like that has to be for the fight to have distance in it.
    */
   public static final int ARENA_CELLS = 3;
   /** The arena's own footprint, in blocks. */
   public static final int ARENA_SPAN = ROOM_PITCH * ARENA_CELLS;
   /**
    * How deep the flat apron inside each of the arena's archways runs, in local blocks.
    *
    * <p>An archway's own cut sweeps the walk lane five blocks into the room behind it - the wall
    * band plus {@link #DOOR_LANE} - and the sweep sets those blocks to air, which is exactly right
    * in a chamber whose floor is empty and exactly wrong in one whose floor is a terrace. So the
    * terrace's ramp starts where the sweep stops: five flat blocks at the pit's own level, then
    * three steps up onto the rim. A terrace drawn straight through the doorway is a doorway with a
    * three-block wall behind it, and a ramp drawn inside the sweep is a trench with the same wall.
    */
   public static final int ARENA_APRON = WALL + DOOR_LANE;
   /** The pit's low ground, in local arena coordinates: the middle of the three-by-three block. */
   public static final int ARENA_PIT_LO = ARENA_SPAN / 2 - 18;
   public static final int ARENA_PIT_HI = ARENA_SPAN / 2 + 17;
   /** The pit of the cell-sized arena, for the rare block that cannot be a three-by-three one. */
   public static final int CELL_PIT_LO = ROOM_PITCH / 2 - 6;
   public static final int CELL_PIT_HI = ROOM_PITCH / 2 + 5;

   private static final int NORTH = 0;
   private static final int EAST = 1;
   private static final int SOUTH = 2;
   private static final int WEST = 3;
   private static final int[] DIR_X = {0, 1, 0, -1};
   private static final int[] DIR_Z = {-1, 0, 1, 0};
   private static final int[] OPPOSITE = {SOUTH, WEST, NORTH, EAST};

   /**
    * One chamber of the maze. The room is the unit of the whole dungeon-crawler loop:
    * walk in, the pack wakes, the pack dies, the bounty lands, three doors open.
    */
   private static final class Room {
      final int rx;
      final int rz;
      /** Distance in chambers from the entrance - what the bounty and the packs scale on. */
      final int depth;
      final Chamber chamber;
      /** Bitmask of {@link #NORTH}/{@link #EAST}/{@link #SOUTH}/{@link #WEST} with an open archway. */
      int doors;
      boolean visited;
      boolean cleared;
      /**
       * Whether this chamber's archways are barred, and where the bars are.
       *
       * <p>Recorded so the chamber can take its own bars down: a lock that outlives its room is a
       * lock that outlives the run, and a maze full of iron cages nobody can open is worse than no
       * lock at all.
       */
      boolean sealed;
      final List<BlockPos> bars = new ArrayList<>();
      /** The pack this chamber is holding. Clearing means every one of these is gone. */
      final List<UUID> monsters = new ArrayList<>();
      /** Every interactable this chamber planted, so a cleared room can tidy its own rune lock. */
      final List<BlockPos> props = new ArrayList<>();
      /** Rune stones this chamber's lock is made of, and whether the lock is already open. */
      int runesTotal;
      int runesLit;
      boolean runesOpened;

      /**
       * What the chamber is in the middle of happening to it, or null for an ordinary room.
       *
       * <p>Rolled once, when the room is carved, and then it is a property of the place: the same
       * cell always plays the same way, so a flooded hall can be remembered and routed around.
       */
      Condition condition;

      /**
       * The fall: whether this chamber is shedding now, how much rubble has already landed in it,
       * the first tick its roof is allowed to give way, and whether it has.
       *
       * <p>Split from the room's ordinary state on purpose. A collapsing chamber is still a chamber
       * - it has a pack in it, an archway out of it and a floor to walk on - until the moment it
       * does not, and these four fields are the only record of how far into that it is.
       */
      boolean shedding;
      int shedRubble;
      long breachAt;
      /** Site tick this shed may not outlive - see {@link #COLLAPSE_MAX_SHED_TICKS}. */
      long shedUntil;
      boolean breached;

      Room(int rx, int rz, int depth, Chamber chamber) {
         this.rx = rx;
         this.rz = rz;
         this.depth = depth;
         this.chamber = chamber;
      }
   }

   /**
    * The state a chamber is in on top of what it is.
    *
    * <p>The face says what a room IS - a library, a forge, a vault - and a condition says what is
    * currently wrong with it. Both are read off the room itself rather than announced, which is what
    * keeps a hundred chambers of the same four walls from being a hundred of the same room: two
    * libraries, one flooded and one alight, are two different places to be.
    */
   public enum Condition {
      FLOODED("Flooded", "§3"),
      BURNING("Burning", "§6"),
      DARK("Unlit", "§8"),
      OVERGROWN("Overgrown", "§a"),
      UNSTABLE("Unstable", "§e");

      public final String name;
      public final String colour;

      Condition(String name, String colour) {
         this.name = name;
         this.colour = colour;
      }

      /**
       * One condition, or none at all.
       *
       * <p>Most rooms are ordinary on purpose. If every chamber were flooded or alight the
       * conditions would be the texture rather than the news, and the maze's own rooms would stop
       * being readable at all - so a little over half of them are left alone.
       */
      public static Condition roll() {
         int r = RANDOM.nextInt(100);
         if (r < 55) {
            return null;
         }
         if (r < 64) {
            return FLOODED;
         }
         if (r < 73) {
            return BURNING;
         }
         if (r < 82) {
            return DARK;
         }
         if (r < 92) {
            return OVERGROWN;
         }
         return UNSTABLE;
      }
   }

   /**
    * What a mend keeps inside a site: a little over half of it, and half as often.
    *
    * <p>An expedition is meant to be a trip with a budget, and the budget is health. Mending in here
    * used to be cheap enough that a careful player never once had to think about a rest, which made
    * the quiet chambers decoration; the answer was to give back half of a mend and put twice as long
    * between them, and that is still what turns "find a camp" into the plan a run is built around.
    *
    * <p>Half was a touch too thin. A site's two reductions - this one and the food a body does not
    * get to digest, see {@link #SITE_REGEN_EFFICIENCY} - read together as "you heal a fifth to a
    * half of what you should", which is the shape of a mode nobody wants to play twice. Both were
    * softened by the same small step, and the rest of the relief is bought rather than given:
    * {@link ExpeditionProgression.Upgrade#FIELD_MEDICINE} adds fifty points back to <b>both</b> of
    * them, which is what makes the pair read as nothing-to-a-quarter rather than a fifth.
    */
   public static final float MEND_SCALE = 0.55F;
   /** How much longer between mends inside a site, as a multiple of the interval it used to be. */
   public static final int MEND_INTERVAL_SCALE = 2;
   /** What one beat of a resting place gives before the scale is applied. */
   public static final float REST_MEND = 2.0F;
   /** How often a resting place mends, before the scale is applied. */
   public static final int REST_MEND_TICKS = 40;
   /** Test seam: a mend's worth inside a site, whatever it would have been outside one. */
   public static float mendAmount(float base) {
      return base * MEND_SCALE;
   }
   /**
    * The fraction of a mend one explorer keeps inside a site: the site's own scale, plus whatever
    * {@link ExpeditionProgression.Upgrade#FIELD_MEDICINE} bought them, and never more than all of it.
    *
    * <p>A capped sum rather than a product, because that is what the line says it does - it is fifty
    * points of a penalty handed back, not fifty per cent more of a crushed number - and because a
    * product would leave a maxed explorer still healing a fifth short, which is a line that never
    * quite arrives.
    */
   public static float siteMendScale(UUID uuid) {
      return Math.min(1.0F, MEND_SCALE + ExpeditionProgression.healBonus(uuid));
   }
   /** One explorer's mend: the same seam as {@link #mendAmount(float)}, read through their own line. */
   public static float mendAmount(float base, UUID uuid) {
      return base * siteMendScale(uuid);
   }
   /** Test seam: how many ticks between mends inside a site. */
   public static long mendInterval(long base) {
      return base * MEND_INTERVAL_SCALE;
   }

   /**
    * How long an explorer has to stand on the deep exit pad before it extracts them.
    *
    * <p>Two seconds, and it is the whole fix for "sometimes it just kicks me out of the
    * expedition". The entrance pad is announced from the first second of a run and the explorer is
    * told to walk back to it, so stepping onto that one is already deliberate. The deep pad is the
    * opposite kind of thing: it is rolled into a random chamber at depth six and beyond, it is a
    * 2x2 square of glowstone in the middle of a room the explorer is walking across, and it used to
    * extract on a four-block-radius brush - so a player crossing a chamber they had never seen could
    * be paid out and thrown home mid-run with no idea what had happened to them. A pad that has to be
    * stood on is a pad that has to be chosen.
    */
   public static final int EXIT_PAD_HOLD_TICKS = 40;
   /** What an Expedition Broker is called - matched by name in the use handler that opens the shop. */
   public static final String BROKER_NAME = "\u00a76\u00a7lExpedition Broker";

   /**
    * Keeps the permanent upgrades on the body for as long as it is inside a site.
    *
    * <p>Refreshed every second rather than granted once at the threshold: an effect laid on at the
    * entrance and then drunk away with a milk bucket, or simply expired, is a purchase the player has
    * made and cannot rely on. The duration runs a little past the refresh interval so a missed tick
    * never blinks the buff off.
    */
   private static void applyUpgrades(ServerPlayer sp) {
      int speed = ExpeditionProgression.speedAmplifier(sp.getUUID());
      if (speed >= 0) {
         sp.addEffect(new MobEffectInstance(MobEffects.SPEED, 45, speed, false, false, true));
      }
      int might = ExpeditionProgression.strengthAmplifier(sp.getUUID());
      if (might >= 0) {
         sp.addEffect(new MobEffectInstance(MobEffects.STRENGTH, 45, might, false, false, true));
      }
   }

   /**
    * Trims a fresh poison down to the site's own clock.
    *
    * <p>Done here, on the run's tick, rather than at each of the places a site applies venom: the
    * spiders in a wave, the ones in a chamber pack, the trapped reliquary and anything the site grows
    * later all land on the same body, and a rule written at four call sites is a rule three of them
    * will eventually miss. The tick is the one place that sees every one of them.
    *
    * <p>Re-applied rather than edited in place because the effect engine has no setter for a
    * remaining duration. It costs one effect re-write per <i>application</i> of poison and nothing
    * on the ticks between: the copy it writes is already inside the cap, so the next tick finds
    * nothing to do - which is also why this cannot loop, and why the amplifier, the particles and the
    * HUD icon all survive the trim (the same effect comes back, just shorter).
    */
   private static void trimSitePoison(ServerPlayer sp) {
      int cap = sitePoisonTicks(sp.getUUID());
      MobEffectInstance poison = sp.getEffect(MobEffects.POISON);
      if (poison == null || poison.getDuration() <= cap) {
         return;
      }
      int amplifier = poison.getAmplifier();
      sp.removeEffect(MobEffects.POISON);
      sp.addEffect(new MobEffectInstance(
         MobEffects.POISON, poisonTicks(poison.getDuration(), sp.getUUID()), amplifier, false, true, true
      ));
   }
   /** How long a descent's new site refuses to read the pads - see {@link #SITE_ENTRY_GRACE_TICKS}. */
   public static final int SITE_ENTRY_GRACE_TICKS = 100;
   /** How many ticks a run may read its explorer as outside the site before the run ends. */
   public static final int SITE_LEAVE_TOLERANCE_TICKS = 60;
   /** How long each chamber of a collapse stands before the next one comes down. */
   public static final int COLLAPSE_STEP_TICKS = 14;
   /**
    * How long a doomed chamber spends shedding before its roof actually gives way.
    *
    * <p>A chamber used to come down in a single tick: a wall of gravel appeared where a room had
    * been and the explorer standing in it was already gone. The fall had no middle, so the only
    * thing that told anybody the site was coming apart was the chat line three rooms back. It has a
    * middle now, and the middle is physical: rubble lands on the floor of the doomed chamber for
    * this many ticks, and the collapse reads off the room the way it reads off a flooded one.
    */
   public static final int COLLAPSE_SHED_TICKS = 10;
   /**
    * Blocks of rubble that have to land in a chamber before its roof gives way.
    *
    * <p>The count is the gate rather than the clock: the room comes down when enough of it has
    * already fallen in it to be obvious, which is what makes the last few ticks of a doomed chamber
    * a room you can read and a door you should be using.
    */
   public static final int COLLAPSE_RUBBLE_TARGET = 36;
   /**
    * Rubble blocks laid on a doomed chamber's floor each tick of its shed.
    *
    * <p>Five rather than three, so the target is met inside the shortened shed: what used to be
    * twelve ticks of rock arriving in threes is now eight of it arriving in fives, which reads as
    * the same roof coming down harder rather than as a room that was simply deleted.
    */
   public static final int COLLAPSE_RUBBLE_RATE = 5;
   /**
    * Of the rubble a doomed chamber lays down each tick, how many blocks come down as real falling
    * rock rather than being written where they land.
    *
    * <p>The rubble used to simply appear on the floor - a few blocks a tick arriving at ground level,
    * which read as a room being filled rather than as a room coming down, because nothing in it ever
    * fell. These are the blocks that actually do: each one is spawned at the roof of its own chamber,
    * falls under the game's own physics, lands where it lands, stays there as part of the rubble and
    * hurts whatever it comes down on the way a falling block does. The rest of the rate is still
    * written in below, because a room's floor is a count rather than a spectacle and an arena's shed
    * is nine hundred blocks - see {@link #shedStone}.
    */
   public static final int COLLAPSE_FALLING_RATE = 3;
   /** How many of them are dropped per tick once the site is nearly done - the fall gets heavier. */
   public static final int COLLAPSE_FALLING_MAX = 6;
   /**
    * How much of a chamber's own roof comes down in one burst at the moment it gives way.
    *
    * <p>Capped on purpose: a hall is six hundred cells and dropping all of them as entities in one
    * tick is a server problem rather than a spectacle. What buries the floor is the written rubble
    * below - this is the part of the fall the player watches.
    */
   public static final int COLLAPSE_BREACH_FALLING = 28;
   /**
    * The longest a doomed chamber may shed before its roof comes down anyway.
    *
    * <p>The gate is the rubble count - see {@link #COLLAPSE_RUBBLE_TARGET} - but the count is a
    * gate on a room that still has air in it to bury, and a second gate is what keeps one room from
    * holding the whole collapse open: rubble only lands in a cell that is empty, so a chamber that
    * has been filled to its roof (or one whose floor was built up to the ceiling by the fight that
    * cleared it) can never reach the target and would sit there shedding for the rest of the run,
    * with the rest of the site still standing because the collapse is waiting on it. Past this
    * window the roof gives way on the clock instead, so chamber by chamber means chamber by chamber.
    */
   public static final int COLLAPSE_MAX_SHED_TICKS = 30;
   /**
    * How often the falling site groans at the first chamber, and at the last.
    *
    * <p>The groan is the sound half of the collapse's own countdown: it starts as something heard
    * through the walls every couple of seconds and ends as a rumble that will not leave the explorer
    * alone. See {@link #collapseRumbleTicks}.
    */
   public static final int COLLAPSE_RUMBLE_SLOW_TICKS = 36;
   public static final int COLLAPSE_RUMBLE_FAST_TICKS = 7;
   /**
    * How long the fall gives an explorer it has already caught before it takes the run.
    *
    * <p>The roof giving way over the chamber a body is standing in used to end the run in the same
    * tick the rock started moving: the warning was the dust and the run was already over, so the
    * last chamber of a collapse was not a decision - it was a dice roll on which door the explorer
    * happened to be standing behind. Eight seconds is the fix, and it is deliberately the whole of
    * it: long enough to turn around, read a compass and run for a pad that still works, and far too
    * short to loot the chamber on the way out. The fall keeps going for every one of those seconds,
    * so the time is bought under falling rock rather than granted in a lull, and reaching a pad in
    * that window is a real escape - the same escape, by the same walk, that the run always
    * promised. Anything the explorer secures in those eight seconds is secured.
    */
   public static final int COLLAPSE_GRACE_TICKS = 160;
   /** The fall has not caught this explorer. */
   public static final int GRACE_NONE = 0;
   /** The fall has caught them and the clock is theirs. */
   public static final int GRACE_RUNNING = 1;
   /** The fall has caught them and the time is up. */
   public static final int GRACE_SPENT = 2;

   /**
    * Which of the three states the fall's warning is in.
    *
    * <p>The arithmetic of the grace, as a function rather than as a branch buried in the collapse
    * tick, because the one thing that went wrong here is worth being able to drive directly: the
    * warning and the end of the run used to be the same instant, and a check that can jump straight
    * from "not caught" to "the window is spent" is a check that would catch it coming back.
    */
   public static int collapseGracePhase(long graceUntil, long now) {
      if (graceUntil == 0L) {
         return GRACE_NONE;
      }
      return now < graceUntil ? GRACE_RUNNING : GRACE_SPENT;
   }
   /**
    * How far out the ring of dust around the explorer is drawn when the fall starts, and when it
    * ends. The ring closes as chamber after chamber comes down, and it is the one thing in the
    * collapse a player can read without looking away from the corridor they are running down: a ring
    * that is still thirty blocks out is a site with most of itself left, and one drawn tight around
    * the body is a site with nothing left but the room they are standing in.
    */
   public static final double COLLAPSE_RING_FAR = 34.0;
   public static final double COLLAPSE_RING_NEAR = 9.0;
   /**
    * Whether a site's collapse runs this tick.
    *
    * <p>Once the roof is coming down, the clock no longer has a say: a run that buys time - the
    * mini-boss sentinel is worth a minute, a floor guardian three, a Time Shard seconds - used to
    * push {@code elapsed} back under {@code durationTicks} and stop the collapse where it stood,
    * mid-shed, with every chamber behind it still intact. The site is committed, so the collapse is
    * read off its own flag and the clock is only allowed to start it.
    */
   public static boolean collapseShouldRun(boolean collapsing, long elapsed, long durationTicks) {
      return collapsing || elapsed >= durationTicks;
   }

   /**
    * Whether a time bonus can still be bought on this site.
    *
    * <p>A site that is already coming down cannot be talked back into standing: the clock is what
    * starts a collapse, and a collapse that can be extended is a site that falls, stands up again
    * and falls again. See {@link #collapseShouldRun} for the other half of the same rule.
    */
   public static boolean timeBonusAllowed(boolean collapsing) {
      return !collapsing;
   }

   /**
    * Whether a chamber named by the collapse still has a fall left in it.
    *
    * <p>Two things take a chamber out of the collapse: it is already shedding, or its roof has
    * already come down. Being CLEARED is deliberately not one of them. A looted chamber still has a
    * roof, and every chamber of the site comes down in turn - the maze the player fought through is
    * exactly the maze they should watch fall, and a check for "cleared" here was what let the
    * collapse walk past most of it.
    */
   public static boolean chamberStillHasAFall(boolean shedding, boolean breached) {
      return !shedding && !breached;
   }

   /**
    * Whether a shedding chamber's roof is due to give way.
    *
    * <p>Two ways to be done: enough rubble has landed and its own moment has passed - the ordinary
    * rule, and the one the player reads - or the shed window has run out, which is what stops a
    * chamber that cannot be buried from holding the site open. See
    * {@link #COLLAPSE_MAX_SHED_TICKS}.
    */
   public static boolean shedIsDone(int rubble, int target, long tick, long breachAt, long shedUntil) {
      return tick >= shedUntil || (rubble >= target && tick >= breachAt);
   }

   /**
    * How many of a chamber's rubble blocks come down as real falling rock on this tick.
    *
    * <p>Never more than the chamber's own shed rate, so a cell-sized room does not suddenly drop six
    * blocks a tick and a hall is not made of two tiers of spectacle - the ceiling coming down is a
    * share of the rubble, not a second event bolted onto it.
    */
   public static int collapseFallingPerTick(int rate, double pressure) {
      double p = Math.max(0.0, Math.min(1.0, pressure));
      int want = COLLAPSE_FALLING_RATE
         + (int)Math.round((COLLAPSE_FALLING_MAX - COLLAPSE_FALLING_RATE) * p);
      return Math.max(0, Math.min(rate, want));
   }

   /**
    * How much of the site is already coming down - 0 at the first chamber, 1 when the last one is
    * under the fall.
    *
    * <p>One number, and every part of the collapse's spectacle is a function of it: the groans get
    * faster and lower, the ring of dust closes in, the shafts over the chambers that are mid-fall get
    * taller, and the booms get heavier. It is the answer to "how far along is this" that a player
    * racing the pad can read off the screen without a status line, which is the whole reason the
    * collapse has a spectacle at all - the first version was invisible unless the room you were
    * standing in happened to be the one coming down.
    */
   public static double collapsePressure(int felled, int total) {
      if (total <= 0) {
         return 1.0;
      }
      return Math.max(0.0, Math.min(1.0, (double)felled / (double)total));
   }

   /** Ticks between two groans of a falling site: the closer it is to done, the faster they come. */
   public static int collapseRumbleTicks(double pressure) {
      double p = Math.max(0.0, Math.min(1.0, pressure));
      return (int)Math.round(
         COLLAPSE_RUMBLE_SLOW_TICKS - (COLLAPSE_RUMBLE_SLOW_TICKS - COLLAPSE_RUMBLE_FAST_TICKS) * p
      );
   }

   /** The pitch of those groans: a rumble deepens as the site comes apart, it does not rise. */
   public static float collapseGroanPitch(double pressure) {
      double p = Math.max(0.0, Math.min(1.0, pressure));
      return (float)(0.78 - 0.36 * p);
   }

   /** How far out the closing ring of dust is drawn at this point in the fall. */
   public static double collapseRingRadius(double pressure) {
      double p = Math.max(0.0, Math.min(1.0, pressure));
      return COLLAPSE_RING_FAR - (COLLAPSE_RING_FAR - COLLAPSE_RING_NEAR) * p;
   }

   /**
    * How far apart two sites of one dungeon sit, in blocks.
    *
    * <p>A site is handed out rather than assumed - see {@link #acquireSite} - and each dungeon owns
    * a column of them running north from its own origin. Four thousand is a step the maze cannot
    * reach across, and it is the same spacing a descent has always used.
    */
   public static final int SITE_SPACING = 4000;
   /** Two live runs are never this close to one another - the allocator's whole guarantee. */
   public static final int SITE_CLEARANCE = 2000;

   /** Sites a live run is standing in right now, by centre. */
   private static final List<BlockPos> liveSites = new ArrayList<>();
   /** Every site each live run holds, so a run hands all of them back the moment it ends. */
   private static final Map<UUID, List<BlockPos>> runSites = new HashMap<>();

   private static final class State {
      final Type type;
      final Anomaly anomaly;
      final ResourceKey<Level> originDim;
      final double originX;
      final double originY;
      final double originZ;
      final float yaw;
      final float pitch;
      final ServerLevel zoneLevel;
      final BlockPos center;
      /**
       * The explorer this run belongs to - the name on every site it holds.
       *
       * <p>The run needs it because a site is no longer a constant: the entrance is taken out of a
       * ledger of live sites when the run starts and every descent takes another, so the run has to
       * know whose name to hand them back under. See {@link #acquireSite} and {@link #releaseSites}.
       */
      final UUID holder;
      final long startTick;
      /**
       * How long the run has. Not final: bosses and Time Shards buy more of it, which is the whole
       * reason a run can go deeper than its clock was built for.
       */
      long durationTicks;
      /**
       * Fatal blows this run may still survive - the Second Wind upgrade, taken off the ledger when
       * the run starts. A count rather than a flag so a level that granted two would need no second
       * field, and zero for a player who has not bought the line.
       */
      int revivesLeft;
      /**
       * The run this descent is - the id stamped on every pack it hands out.
       *
       * <p>Not final, because a descent keeps the run it is already on: the ladder down to a second
       * site is the same run continuing, and the pack that came down it has to keep answering.
       */
      long runId;
      long lootValue;
      int lootCount;
      boolean collapsing;
      long collapseEndTick;
      /**
       * Site tick the fall's last warning runs out, or 0 while it has not caught anybody.
       *
       * <p>Not a second collapse and not a pause in the first one: the site carries on coming down
       * around the explorer for the whole of it. See {@link #COLLAPSE_GRACE_TICKS}.
       */
      long collapseGraceUntil;
      /** Chambers the fall named, how many of them have been taken, and the next groan of the ground. */
      int collapseTotal;
      int collapseFelled;
      long collapseRumbleAt;
      /**
       * The run's draught ledger: cauldrons drunk (which is what thins the next one), the one bottle
       * the run gets, and the tick the coven next reaches for a pot. See {@link #draughtMend} and
       * {@link #covenTick}.
       */
      int draughtsDrunk;
      int draughtBottlesTaken;
      long nextCovenDrinkTick;
      boolean guardianSlain;
      UUID guardianId;
      /** Site tick of the floor guardian's next move, which of its moves is next, and whether it has
       *  been pushed past a third of its health yet. The whole of the fight's memory. */
      long guardianNextMoveTick;
      int guardianMoveStep;
      boolean guardianEnraged;
      /** Site tick until which the guardian is mid-rush - see {@link #guardianRushStep}. */
      long guardianChargeUntil;
      /**
       * The arena's shockwave, which is what the guardian's slam is when he has eighty-four blocks
       * to fill: the tick it stops sweeping and how far out its ring has got.
       *
       * <p>The ring itself is the site's, but *whether it has caught me* is not: one explorer can be
       * behind it and another in front of it, so the mark is per body. See {@link Body#waveHit}.
       */
      long guardianWaveUntil;
      double guardianWaveRadius;
      /** Site tick until which the guardian is staggered after a rush that hit nothing at all. */
      long guardianStaggerUntil;
      /** True while a rush is actually running, so its end can be noticed once and only once. */
      boolean guardianRushing;
      /** Where the column he threw is going to land, and the tick it lands - see {@link #guardianHurl}. */
      long guardianThrowTick;
      double guardianThrowX;
      double guardianThrowZ;
      /** Furthest horizontal distance (squared) the player has been from the entrance. */
      int maxDist2 = 0;
      /** Every chamber the maze has carved for this run, keyed by {@link #roomKey}. */
      final Map<Long, Room> rooms = new HashMap<>();
      /** The chamber the player is standing in right now. */
      Room lastRoom;
      /** Chambers cleared this run, and the deepest one reached - the run's real score. */
      int roomsCleared = 0;
      int deepest = 0;
      /** Floor guardians felled and descent ladders burnt this run. */
      int guardiansFelled = 0;
      int descents = 0;
      /** Centre of the entrance chamber's glowstone escape pad. */
      BlockPos entrancePad;
      /**
       * The guardian's arenas, by the cell at the middle of each.
       *
       * <p>Plural, because a floor guardian comes every ten chambers and a run that gets to depth
       * twenty has two of them: each gets its own three-by-three block, with the eight cells around
       * its middle reserved while it stands.
       */
      final Map<Long, Arena> arenas = new HashMap<>();
      /** UUID of the wandering trader spawned for this expedition (if any). */
      UUID traderId;
      /** Tick when the next trader should appear. */
      long nextTraderTick;
      /** Tick when the next mini-boss check should happen. */
      long nextMiniBossTick;
      /** UUID of the active mini-boss, if any. */
      UUID miniBossId;
      /** Position of a rare second exit pad deep in the maze (if one generated). */
      BlockPos exitPad;
      /** Whether the player has walked off the entrance pad (prevents instant kick on entry). */
      boolean hasLeftEntrance;
      /**
       * The deep exit pad's own gate: whether the explorer is standing on it right now, and how many
       * ticks in a row they have been. See {@link #EXIT_PAD_HOLD_TICKS} for why a pad nobody
       * announced has to be stood on rather than brushed past.
       */
      boolean atExitPad;
      int exitPadHold;
      /**
       * Site time before the escape pads will answer for this run.
       *
       * <p>A body lands on a pad by teleport - on the way in, after a descent, after being pulled
       * out of the void - and the tick that follows reads "standing on the pad" with no memory of
       * having walked there. The pads used to answer that reading on the very next tick, so a
       * descent could end the run instead of starting a new site: the run was reported as a
       * successful escape by a player who never moved. The grace is the memory the pads needed, and
       * it costs a player who genuinely sprints back to the pad five seconds of standing still.
       */
      long escapeArmedAt;
      /** Consecutive ticks this run has read its explorer as being in another level. */
      int outOfSiteTicks;
      /** The chambers still to come down when the clock runs out, outermost first. */
      final List<Room> collapseOrder = new ArrayList<>();
      /** Site time of the next chamber collapse. */
      long nextCollapseTick;
      /**
       * The chambers shedding rubble on their way down right now.
       *
       * <p>A chamber is named by {@link #collapseOrder} and then takes {@link #COLLAPSE_SHED_TICKS}
       * ticks to actually fall, so at any moment the collapse is holding one or two rooms mid-fall
       * while the next one is already being named. This is that list.
       */
      final List<Room> shedding = new ArrayList<>();
      /** Chests already looted this run - pays out exactly ONCE, ever. */
      final java.util.Set<BlockPos> lootedChests = new java.util.HashSet<>();
      /**
       * What each opened chest is still holding, keyed by position.
       *
       * <p>A chest keeps its pieces between openings: walk out, fight, walk back and the piece
       * you could not fit is still lying there. That is what makes a full pack a decision about a
       * piece rather than a lost chest.
       */
      final Map<BlockPos, List<ItemStack>> chestOffers = new HashMap<>();
      /** Cash secured from chests so far this run (capped by CHEST_LOOT_CAP). */
      long chestLoot = 0L;
      /** Run tally, used for the end-of-run scorecard. */
      int oresMined = 0;
      int mobsSlain = 0;
      int chestsOpened = 0;
      /** Tick of the next random expedition event, and the banner for the last one. */
      long nextEventTick = 0L;
      String eventLabel = null;
      long eventLabelUntil = 0L;
      /**
       * What each of this run's interactables is, keyed by the exact block it stands on.
       *
       * <p>Position-keyed rather than block-type-keyed on purpose: a wager pedestal is a gold
       * block and so is a vault's floor, and a room is described by which of its blocks answer,
       * not by which blocks happen to look a certain way.
       */
      final Map<BlockPos, Interact> props = new HashMap<>();
      /** Scoreboard names this run has added to the pack team, so the team is emptied on exit. */
      final java.util.Set<String> packMarks = new java.util.HashSet<>();
      /**
       * Every body this run has ever put the glowing mark on, by uuid.
       *
       * <p>The names in {@link #packMarks} are the scoreboard half of the mark and they are what
       * empties the team; this is the half that takes the glow back off a body that is still alive.
       * Both are needed. A mark is two things - the tag that draws the outline and the team that
       * colours it - and a release that only emptied the team left the body glowing white, which is
       * how a straggler from a run that ended an hour ago was still wearing a mark in a brand new
       * site on the same coordinates.
       */
      final java.util.Set<UUID> markedBodies = new java.util.HashSet<>();
      /** Rune stones already lit this run, and the stones each chamber's lock is made of. */
      final java.util.Set<BlockPos> runesLit = new java.util.HashSet<>();
      /** How many times each supplier line has been bought this run - what moves its price. */
      final Map<String, Integer> supplierDemand = new HashMap<>();
      /** How far the clock has been announced: 0 early, 1 half, 2 three-quarters, 3 the last tenth. */
      int timeStage;

      /**
       * What is true of one explorer rather than of the site.
       *
       * <p>A run used to be one body, so "where the run is" and "where the explorer is" were the
       * same sentence. A party's run puts several bodies in one site, and they do not walk together:
       * two of them are in different chambers, one of them has walked off the entrance pad and the
       * other has not, one is on the deep exit pad while the other is still looking for it, and each
       * of them came in from a different place and has to go back to it. Those are per-explorer
       * facts, so they live here - one Body for each name in the run, keyed by uuid. Everything else
       * on this class is the site's: one maze, one ledger of looted chests, one pack, one clock, one
       * collapse, one guardian.
       */
      static final class Body {
         /** Where this explorer came in from, so the way out is their own way out. */
         ResourceKey<Level> originDim;
         double originX;
         double originY;
         double originZ;
         float yaw;
         float pitch;
         /** The chamber this explorer is standing in, and the pads they have answered for. */
         Room lastRoom;
         boolean hasLeftEntrance;
         boolean atExitPad;
         int exitPadHold;
         /** Consecutive ticks this explorer has read as being in another level. */
         int outOfSiteTicks;
         /**
          * Has the arena's running wall of rubble already caught this explorer?
          *
          * <p>It was one flag on the site once, which is right for one explorer and wrong for four:
          * the wall runs outward across the whole floor, so each body is caught on the tick its own
          * radius matches, and only once. See guardianWaveStep.
          */
         boolean waveHit;
         /**
          * Whether the site has this explorer on the floor, and the tick it stops waiting for them.
          *
          * <p>A fall in a party is a rescue rather than an ending: the blow puts them down instead of
          * taking them out of the run, and what decides the outcome is whether somebody gets to them
          * before this clock does - the site finishing what the blow started. See downedTick.
          */
         boolean downed;
         long downedUntil;
         /** Consecutive ticks an upright member has been standing over them. */
         int reviveHold;
         /**
          * This explorer's own tally, for the squad card.
          *
          * <p>A party's haul is the party's, so "who earned it" cannot be a share of the payout - it
          * is what each of them personally took out of a chest, how many chests they opened, and the
          * two halves of a fall. The run's own tally is the site's (the counters on State); these are
          * the per-explorer half, which only means anything when there is more than one explorer.
          * See squadCard.
          */
         long carried;
         int chests;
         int downs;
         int revives;
         /** Chests this explorer has already been counted for: re-opening one is not a second. */
         final java.util.Set<BlockPos> openedChests = new java.util.HashSet<>();
      }

      /** Every explorer in this run, by uuid. A solo run has exactly one. */
      final Map<UUID, Body> bodies = new HashMap<>();
      /**
       * What the run has already banked.
       *
       * <p>On a shared site the haul is one number and it is one pack, so the only way to pay four
       * explorers out of it without paying the four of them four times is to pay each of them what
       * has been looted *since the last one banked*. This is that mark: the value of
       * {@link #lootValue} as of the last extraction. See {@link #unbanked}.
       */
      long settledLoot;
      /** The last server tick the site's fallen were looked after on. See downedTick. */
      long downedPass = -1L;
      /**
       * The last server tick the site's swarm was sent out on.
       *
       * <p>Everything else the site does once - the trader, the mini-boss, the guardian's moves, the
       * collapse's next step - is guarded by a shared countdown that the first explorer's tick
       * advances and everybody else's reads, so it stays once per site. The swarm is guarded by a
       * clock expression instead, which would have read as "once per explorer", and four explorers
       * is not four avalanches.
       */
      long swarmTick = -1L;

      /** One explorer's share of the run's per-body state, created on first ask. */
      Body body(UUID uuid) {
         return this.bodies.computeIfAbsent(uuid, k -> new Body());
      }

      State(
         Type type,
         Anomaly anomaly,
         ResourceKey<Level> originDim,
         double x,
         double y,
         double z,
         float yaw,
         float pitch,
         ServerLevel zoneLevel,
         BlockPos center,
         UUID holder,
         long startTick
      ) {
         this.type = type;
         this.anomaly = anomaly;
         this.originDim = originDim;
         this.originX = x;
         this.originY = y;
         this.originZ = z;
         this.yaw = yaw;
         this.pitch = pitch;
         this.zoneLevel = zoneLevel;
         this.center = center;
         this.holder = holder;
         this.startTick = startTick;
         // The clock, plus the one permanent upgrade that changes it before a second is earned.
         // Read here rather than at the descent menu so a run that descends to a second site keeps
         // both the clock it had and the Second Wind it was carrying. See ExpeditionProgression.
         this.durationTicks = (long)(type.durationTicks * anomaly.durationMul)
            + ExpeditionProgression.timeBonusTicks(holder);
         this.revivesLeft = ExpeditionProgression.reviveCharges(holder);
         // The first body of the run is the one who paid for the ground it is standing on. A party's
         // other bodies are filled in as they arrive - see joinSite.
         Body mine = this.body(holder);
         mine.originDim = originDim;
         mine.originX = x;
         mine.originY = y;
         mine.originZ = z;
         mine.yaw = yaw;
         mine.pitch = pitch;
      }
   }

   /**
    * What one explorer is carrying out of a shared haul.
    *
    * <p>The haul belongs to the site, and the pack belongs to the party, so "how much have I just
    * earned" is a difference rather than a total: what the run has looted since the last explorer
    * banked it. Paying each member the whole of {@link State#lootValue} in turn would pay a party of
    * four for the same loot four times over; paying them a difference pays the party exactly what
    * the party carried out, and the equal shares in {@link PartyManager#settle} do the rest.
    * Clamped at zero because a run can spend loot as well as earn it - a supplier is paid out of the
    * pot as much as a chest fills it.
    */
   public static long unbanked(long lootValue, long settledLoot) {
      return Math.max(0L, lootValue - settledLoot);
   }

   /**
    * Is anybody still standing in this run's site?
    *
    * <p>A party's run is one State with several names in it, so "my run has ended" and "the site is
    * empty" stop being the same sentence. The first of four to reach a pad must not sweep the mobs
    * out from around the other three, or hand the ground back to the ledger while they are still
    * walking it. Called after the leaving explorer's own entry is out of {@link #active}.
    */
   private static boolean siteOccupied(State s) {
      for (State other : active.values()) {
         if (other == s) {
            return true;
         }
      }
      return false;
   }

   /**
    * The bodies the site is judging right now: online, and standing in the site's own level.
    *
    * <p>A party's site is one maze with several explorers in it, and a move that only ever asks
    * about the explorer whose tick is running is a move the other three can stand inside and ignore.
    * So the site's own weapons - the slam's ring, the wall of rubble, the thrown column, the rush -
    * are judged against this list instead. A member mid-teleport is left out, because a body in
    * another level has no position in this one to be hit at.
    */
   private static List<ServerPlayer> crew(State s) {
      List<ServerPlayer> out = new ArrayList<>();
      MinecraftServer server = s.zoneLevel.getServer();
      if (server == null) {
         return out;
      }
      for (Map.Entry<UUID, State> e : active.entrySet()) {
         if (e.getValue() != s) {
            continue;
         }
         ServerPlayer member = server.getPlayerList().getPlayer(e.getKey());
         if (member != null && member.level() == s.zoneLevel && member.isAlive()) {
            out.add(member);
         }
      }
      return out;
   }

   /** Every name this run is holding, whether or not the body is in the level this tick. */
   private static List<UUID> crewIds(State s) {
      List<UUID> out = new ArrayList<>();
      for (Map.Entry<UUID, State> e : active.entrySet()) {
         if (e.getValue() == s) {
            out.add(e.getKey());
         }
      }
      return out;
   }

   /** Clears every explorer's "the rubble has already caught me" mark. See guardianSlam. */
   private static void clearWaveMarks(State s) {
      for (State.Body body : s.bodies.values()) {
         body.waveHit = false;
      }
   }

   /** How many explorers are standing in this run. */
   private static int siteCrew(State s) {
      int crew = 0;
      for (State other : active.values()) {
         if (other == s) {
            crew++;
         }
      }
      return crew;
   }

   /** How many explorers are standing in this player's run - zero when they are on none. */
   public static int crewOf(UUID uuid) {
      State s = uuid == null ? null : active.get(uuid);
      return s == null ? 0 : siteCrew(s);
   }

   private static final Map<UUID, State> active = new HashMap<>();
   /**
    * Run ids, handed out one per descent and stamped onto the pack it is handed out with.
    *
    * <p>They are wall-clock based rather than counted from zero on purpose: a pack that outlived a
    * restart of the server must not be able to answer for the first run of the next session, and a
    * counter that began again at one would let exactly that happen.
    */
   private static final java.util.concurrent.atomic.AtomicLong RUN_IDS =
      new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis() * 1000L);

   /** The id the next descent will be stamped with, so a party's members can share one. */
   public static long nextRunId() {
      return RUN_IDS.incrementAndGet();
   }

   /**
    * The run a body is on right now, or 0 when it is on none.
    *
    * <p>This is the whole test behind a pack being worth anything: a pack is live only while the
    * body carrying it is standing in the run whose id is stamped on it.
    */
   public static long liveRunId(UUID uuid) {
      State s = uuid == null ? null : active.get(uuid);
      return s == null ? 0L : s.runId;
   }
   private static final Random RANDOM = new Random();
   /** Hard per-run cap on money from loot chests (ores, kills and chamber bounties are uncapped). */
   public static final long CHEST_LOOT_CAP = 100_000L;
   /** The expedition realm: a custom dimension whose dungeons are carved by the room engine. */
   public static final ResourceKey<Level> EXPEDITION_DIM = ResourceKey.create(
      Registries.DIMENSION, Identifier.fromNamespaceAndPath("fortuneandfavors", "expedition")
   );

   /**
    * Players who took a fatal blow inside an expedition must wait before going back in - dying is
    * never fatal, but it still stings.
    *
    * <p>Eight minutes, which is the middle of the window this is meant to sit in: long enough that a
    * run is a decision rather than a retry button, short enough that "when can I go back" is a
    * number and not an evening. It used to be five.
    *
    * <p>The wait is the whole of what a fatal blow costs beyond the run's secured loot, and it has
    * exactly one way out - an Expedition Compass (see {@link #useExpeditionCompass}). A wait nothing
    * can shorten is a tax on the player who just lost a run; a wait one thing can shorten is a shop.
    */
   private static final Map<UUID, Long> fatalCooldown = new HashMap<>();
   /** Minutes a fatal blow costs before the same explorer may be handed a site again. */
   public static final int FATAL_WAIT_MINUTES = 8;
   private static final long FATAL_COOLDOWN_TICKS = 20L * 60L * FATAL_WAIT_MINUTES;

   /**
    * How much of a fed body's own regeneration survives inside a site: a quarter.
    *
    * <p>Saturation regen is the quietest thing in a dungeon and the largest one. Vanilla heals up to
    * a point of health every ten ticks while a body is full and has saturation to burn - six health
    * a second at its best, which is more than any camp mends and more than most of the maze's damage
    * does - and it arrives whether or not the explorer did anything to earn it. An expedition is a
    * trip with a health budget, so food brought into one still feeds it and no longer carries it: the
    * same meal heals a fifth of what it would in the overworld.
    *
    * <p>A quarter rather than the fifth this started at: the same small softening {@link #MEND_SCALE}
    * got, for the same reason. The rest of it is bought - see
    * {@link ExpeditionProgression.Upgrade#FIELD_MEDICINE}, which adds fifty points to this number and
    * to the mends in one purchase, because a penalty that is really one penalty should have one
    * answer.
    *
    * <p>Applied by {@code FoodDataMixin}, through {@link #siteRegen}, so the number lives here with
    * the rest of the run's arithmetic rather than inside an injector.
    */
   public static final float SITE_REGEN_EFFICIENCY = 0.25F;

   /**
    * How long poison lasts on a body inside a site: three and a half seconds, whatever was applied.
    *
    * <p>Venom is the one damage in the maze that does not care what the explorer does. A cave spider
    * lands it on contact, it cannot be out-ran or blocked, and at the seven seconds vanilla hands out
    * it is five points of a health budget the site has already thinned - so a pack of them is not a
    * fight, it is a bill. The cap is the nerf the ask asked for and it is a cap rather than a
    * multiplication because the interesting number is how long the screen keeps hurting: every
    * source of poison in a site now runs on the same short clock, the trapped reliquary included.
    *
    * <p>Amplifier is left alone. Poison deals the same damage per beat whatever its level and only
    * beats faster, so anything that would have been poison II still is - the site is thinning the
    * burn, not erasing the trap.
    */
   public static final int SITE_POISON_TICKS = 70;

   /** Test seam: what a poison lasts, whatever the thing that applied it asked for. */
   public static int poisonTicks(int requested) {
      return Math.min(requested, SITE_POISON_TICKS);
   }

   /**
    * The site's venom clock for one explorer: the site's own cap, shortened again by the Field Kit.
    *
    * <p>The site caps every venom at {@link #SITE_POISON_TICKS} because seven seconds of it is more
    * than a thinned health budget can pay. The kit's first level is the explorer's own answer to the
    * same problem - not a smaller cap for everybody, a shorter burn for one body - and it is applied
    * here so both the cap and the line reach the same effect, in the same place.
    */
   public static int sitePoisonTicks(UUID uuid) {
      return Math.max(1, (int)(SITE_POISON_TICKS * ExpeditionProgression.venomScale(uuid)));
   }

   /** The site's venom clock for one explorer, applied to whatever was asked for. */
   public static int poisonTicks(int requested, UUID uuid) {
      return Math.min(requested, sitePoisonTicks(uuid));
   }

   /**
    * How much one supply crate gives back, before the site's own mend scale is applied.
    *
    * <p>Sixteen is four hearts, which is a real patch-up and still less than half of a body that has
    * walked into a bad chamber - a crate is a reason to keep going, not a reason to keep opening
    * crates.
    */
   public static final float SUPPLY_HEAL = 16.0F;
   /** How far a supply crate's halo is drawn from - past this it is somebody else's problem. */
   private static final double CRATE_SIGHT = 96.0;

   private ExpeditionManager() {
   }

   public static boolean isInExpedition(UUID uuid) {
      return active.containsKey(uuid);
   }

   /**
    * The site this body is down, by name, or null when it is not down one.
    *
    * <p>What the tab list reads. It had only the realm to go on before, so the marker said
    * "[Expedition]" for anybody standing in the zone and nothing at all for an explorer whose
    * run was handed back mid-teleport - and it never said *which* site, on a mod with seven of
    * them. A run owns the body, so the run is what the name is read off.
    */
   public static String activeExpeditionName(UUID uuid) {
      State s = active.get(uuid);
      return s == null || s.type == null ? null : s.type.name;
   }

   /**
    * The tab-list marker for a body: the site it is down by name, the plain word for anybody
    * standing in the realm without a run, and nothing for everybody else.
    *
    * <p>A seam rather than the three lines written into the mixin, so what the tab list is
    * supposed to say can be asserted without starting a run - see the harness check.
    */
   public static String tabMarker(String activeSite, boolean inRealm) {
      if (activeSite != null && !activeSite.isBlank()) {
         return "§6[" + activeSite + "] §f";
      }

      return inRealm ? "§6[Expedition] §f" : null;
   }

   /**
    * One beat of a fed body's regeneration, thinned by the site it happens in.
    *
    * <p>Deliberately a seam rather than a number written into the mixin: "how much of a meal
    * survives a dungeon" is a design decision, and a design decision buried inside an injector is
    * one that nothing can assert and nobody can find. See {@link #SITE_REGEN_EFFICIENCY}.
    */
   public static float siteRegen(UUID uuid, float amount) {
      return siteRegenAt(amount, isInExpedition(uuid), siteRegenEfficiency(uuid));
   }

   /**
    * The fraction of a fed body's regeneration one explorer keeps inside a site: the site's own
    * efficiency, plus whatever {@link ExpeditionProgression.Upgrade#FIELD_MEDICINE} bought them, and
    * never more than all of it - the same capped sum the mends use, so one purchase moves both.
    */
   public static float siteRegenEfficiency(UUID uuid) {
      return Math.min(1.0F, SITE_REGEN_EFFICIENCY + ExpeditionProgression.healBonus(uuid));
   }

   /** The same arithmetic, told which side of the site line it is on - the harness's seam. */
   public static float siteRegenAt(float amount, boolean inSite) {
      return siteRegenAt(amount, inSite, SITE_REGEN_EFFICIENCY);
   }

   /** The same arithmetic, at an efficiency the caller names - the harness's seam for the line. */
   public static float siteRegenAt(float amount, boolean inSite, float efficiency) {
      return inSite ? amount * Math.max(0.0F, Math.min(1.0F, efficiency)) : amount;
   }

   /**
    * What a blow costs one explorer inside a site, after the Field Kit's fall answer.
    *
    * <p>Called from the damage pipeline's fall branch - the one place a fall's damage can be scaled
    * rather than cancelled - so it can only ever answer a fall, and only for a body the run owns.
    * Anything that is not a fall, or a fall outside a site, comes back untouched; a site's own
    * venom and its monsters are not this seam's business.
    */
   public static float fallDamage(net.minecraft.world.entity.Entity entity, DamageSource source, float amount) {
      if (!(entity instanceof ServerPlayer sp) || source == null
            || !source.is(net.minecraft.tags.DamageTypeTags.IS_FALL)
            || !isInExpedition(sp.getUUID())) {
         return amount;
      }
      return amount * ExpeditionProgression.fallScale(sp.getUUID());
   }

   /** True for the expedition realm itself - the one level whose monsters are all built by a run. */
   public static boolean isExpeditionRealm(Level level) {
      return level != null && EXPEDITION_DIM.equals(level.dimension());
   }

   /** Seconds left of a fatal-blow wait, or 0 when there is none. */
   public static long fatalWaitSeconds(UUID uuid, long now) {
      Long until = fatalCooldown.get(uuid);
      return until == null || now >= until ? 0L : (until - now) / 20L;
   }

   /** The wait as a line of text - {@code 7m 12s} - or null when there is no wait to name. */
   public static String fatalWaitText(UUID uuid, long now) {
      long left = fatalWaitSeconds(uuid, now);
      return left <= 0L ? null : (left / 60L) + "m " + (left % 60L) + "s";
   }

   /** Test seam: how long the wait a fatal blow buys is, in ticks. */
   public static long fatalWaitTicks() {
      return FATAL_COOLDOWN_TICKS;
   }

   /** Test seam: put an explorer on the clock a fatal blow leaves, without arranging the blow. */
   public static void waitAfterFatalBlowForTest(UUID uuid, long now, long ticks) {
      fatalCooldown.put(uuid, now + ticks);
   }

   /**
    * What an Expedition Compass costs at the descent menu.
    *
    * <p>Priced against the wait rather than against a run: a dungeon pays tens of thousands, so this
    * is a real purchase that a bad night makes worth making, and not a fee that every run pays. It
    * is sold where the wait is announced - the menu that hands out descents - because an item that
    * answers a problem has to be where the problem is.
    */
   public static final long COMPASS_PRICE = 25_000L;

   /**
    * Buys an Expedition Compass with cash - the shop half of the wait.
    *
    * <p>The other half is {@link #useExpeditionCompass}: this hands the compass over, that spends it.
    *
    * @return an error message for the player, or null when the compass was really bought
    */
   public static String buyExpeditionCompass(ServerPlayer sp) {
      if (!EconomyManager.takeCash(sp.getUUID(), COMPASS_PRICE)) {
         return "You can't afford an Expedition Compass: " + Chat.moneyStr(COMPASS_PRICE) + " (you have "
            + Chat.moneyStr(EconomyManager.balance(sp.getUUID())) + ").";
      }
      com.fortuneandfavors.util.InventoryHelper.giveOrDrop(sp, ModItems.expeditionCompass());
      SoundUtil.play(sp, ModSounds.TRANSFER);
      Chat.raw(
         sp,
         "§b§lEXPEDITION COMPASS §r§7bought for §f" + Chat.moneyStr(COMPASS_PRICE)
            + "§7 - one fatal-blow wait, erased when you right-click it."
      );
      return null;
   }

   /** The Expedition Compass: the wait, erased.
    *
    * <p>A fatal blow ends the run and costs the secured loot, and the only thing left of it when the
    * explorer is back in the overworld is a clock they cannot touch. That is the one thing in this
    * mod that takes time away with nothing to do about it, so the compass is the answer: hold it,
    * right-click, and the site takes you back early.
    *
    * <p>It is spent only when there is a wait to erase. The click that has nothing to skip says so
    * rather than eating the item, because an item that can be wasted by somebody who does not need
    * it yet is a worse item than one that cannot.
    *
    * @return an error message for the player, or null when the wait was really cleared
    */
   public static String useExpeditionCompass(ServerPlayer sp, ItemStack held) {
      MinecraftServer server = sp.level().getServer();
      long now = ServerClock.clock(server);
      long left = fatalWaitSeconds(sp.getUUID(), now);
      if (left <= 0L) {
         return "There is no fatal-blow wait on you - the compass is the way back in early, so keep it until you need it.";
      }
      fatalCooldown.remove(sp.getUUID());
      if (held != null && !held.isEmpty()) {
         held.shrink(1);
      }
      if (sp.level() instanceof ServerLevel level) {
         level.sendParticles(ParticleTypes.END_ROD, sp.getX(), sp.getY() + 1.2, sp.getZ(), 45, 0.6, 0.8, 0.6, 0.15);
         level.sendParticles(ParticleTypes.ENCHANT, sp.getX(), sp.getY() + 1.0, sp.getZ(), 30, 0.8, 0.6, 0.8, 0.4);
         level.sendParticles(ParticleTypes.FIREWORK, sp.getX(), sp.getY() + 1.6, sp.getZ(), 12, 0.4, 0.4, 0.4, 0.05);
      }
      SoundUtil.play(sp, ModSounds.JOB_COMPLETE);
      Chat.raw(
         sp,
         "\u00a7b\u00a7lTHE COMPASS TURNS. \u00a7r\u00a77The site takes you back early - \u00a7f" + (left / 60L) + "m "
            + (left % 60L) + "s\u00a77 of the wait is gone. Open the descent again."
      );
      return null;
   }

   /** Current secured loot value for the player's active expedition. */
   public static long lootOf(UUID uuid) {
      State s = active.get(uuid);
      return s == null ? 0L : s.lootValue;
   }

   /** Spends secured loot inside the expedition (used by the supplier shop).
    *  Returns false if not on an expedition or the loot is insufficient. */
   public static boolean spendLoot(UUID uuid, long amount) {
      if (amount <= 0L) {
         return false;
      }
      State s = active.get(uuid);
      if (s == null || s.lootValue < amount) {
         return false;
      }
      s.lootValue -= amount;
      return true;
   }

   /**
    * The expedition's rule in the lethal-blow walk: {@link LethalBlows#EXPEDITION}.
    *
    * <p>A fatal blow inside a run is ALWAYS prevented: the run ends, the loot you had secured is
    * lost, and you are thrown back to where you started - but you never die, never drop your real
    * inventory, and never become a ghost.
    *
    * <p>The arithmetic is not repeated here any more. Whether the blow was fatal after armor, and
    * whether the player's own absorption or a Totem of Undying was going to save them, is decided
    * once in {@link LethalBlows} (the totem is a rule in that walk, ahead of this one).
    */
   public static LethalBlows.Answer answerFatalBlow(LethalBlows.Blow blow) {
      ServerPlayer sp = blow.player();
      if (sp == null || sp.level().isClientSide()) {
         return LethalBlows.Answer.INNOCENT;
      }

      State s = active.get(sp.getUUID());
      if (s == null || s.zoneLevel != sp.level()) {
         return LethalBlows.Answer.INNOCENT;
      }

      // A blow on a body already on the floor is the site finishing what the first one started: the
      // rescue window is the whole of what being down buys you, and a second killing blow closes it.
      // Asked first, and ahead of Second Wind, because a body that cannot stand is not a body a
      // bought revive has anything to spend itself on.
      State.Body fallen = s.body(sp.getUUID());
      if (fallen.downed) {
         clearDowned(s, sp, false);
         eject(sp, s, "§c§lTHE SITE TOOK YOU", "you were struck down while you could not stand", true);
         return LethalBlows.Answer.CLAIM;
      }

      // Second Wind, first: the one fatal blow the permanent shop sells, spent surviving rather than
      // ending. Asked before the eject, because the eject is the thing that ends a run.
      if (s.revivesLeft > 0) {
         s.revivesLeft--;
         sp.setHealth(Math.max(2.0F, sp.getMaxHealth() * 0.5F));
         sp.clearFire();
         sp.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 120, 2, false, false, true));
         s.zoneLevel.sendParticles(
            ParticleTypes.TOTEM_OF_UNDYING, sp.getX(), sp.getY() + 1.0, sp.getZ(), 60, 0.6, 0.8, 0.6, 0.25
         );
         SoundUtil.play(sp, ModSounds.JOB_COMPLETE);
         Chat.raw(
            sp,
            "\u00a7d\u00a7lSECOND WIND. \u00a7r\u00a77The blow lands and you are still standing - the site did not take you."
               + "\u00a78 (" + s.revivesLeft + " left this run)"
         );
         return LethalBlows.Answer.CLAIM;
      }
      // A party's fall is not a death, it is a rescue: four explorers in one site means somebody is
      // standing three blocks away with a free hand, so the blow puts them down and the clock - not
      // the blow - is what takes them. Alone there is nobody to pick you up, so alone this is still
      // the eject it always was. See downedNeedsAnotherBody.
      if (downedNeedsAnotherBody(siteCrew(s))) {
         goDown(s, sp);
         return LethalBlows.Answer.CLAIM;
      }
      // The blow and the roof of a collapsing chamber end a run the same way, and now through the
      // same method: one body, several reasons, and one message shape for the player to learn.
      eject(sp, s, "\u00a7c\u00a7lFATAL BLOW ABSORBED", "the dungeon closed over you", true);
      return LethalBlows.Answer.CLAIM;
   }

   /** How long a party's site holds a fallen explorer before it takes them. Fifteen seconds. */
   public static final int DOWNED_TICKS = 300;
   /** How long an upright member has to stand over them to get them up. Two seconds. */
   public static final int REVIVE_HOLD_TICKS = 40;
   /** How close a rescuer has to be - close enough to reach them, not to shout at them. */
   private static final double REVIVE_RANGE = 3.0;

   /**
    * Is there anybody here to pick this explorer up?
    *
    * <p>The whole of the rule, and the reason a solo run is untouched by it: being down is a state
    * that exists because somebody else is standing in the site. With nobody else there, a fatal blow
    * ends the run exactly as it always did, which is what keeps this a party mechanic rather than a
    * free extra life for everybody.
    */
   public static boolean downedNeedsAnotherBody(int crew) {
      return crew > 1;
   }

   /** Has the site waited long enough for somebody to reach them? */
   public static boolean downedExpired(long downedUntil, long now) {
      return downedUntil > 0L && now >= downedUntil;
   }

   /** Has a rescuer stood over them for long enough to get them on their feet? */
   public static boolean reviveComplete(int holdTicks) {
      return holdTicks >= REVIVE_HOLD_TICKS;
   }

   /**
    * Puts a fallen explorer on the floor rather than out of the run.
    *
    * <p>Everything here is deliberately short of death: the body stays in the site, in the party and
    * in the sack of loot; it is slowed to a crawl so the rescue is a walk rather than a teleport; and
    * it is left wearing the outline the game draws through walls, so whoever is coming for them can
    * see where they are through a wall. The run carries on around them, clock and all, because a site
    * does not pause for a rescue.
    */
   private static void goDown(State s, ServerPlayer sp) {
      State.Body body = s.body(sp.getUUID());
      body.downed = true;
      body.downedUntil = ServerClock.clock(s.zoneLevel) + DOWNED_TICKS;
      body.reviveHold = 0;
      body.downs++;
      sp.setHealth(Math.max(1.0F, sp.getMaxHealth() * 0.2F));
      sp.clearFire();
      // Slowed rather than frozen: a body that cannot move at all is one the rescuer has to find by
      // memory, and the pair of them are meant to be able to crawl towards each other.
      sp.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, DOWNED_TICKS + 60, 6, false, false, true));
      sp.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, DOWNED_TICKS + 60, 4, false, false, true));
      sp.setGlowingTag(true);
      s.zoneLevel.sendParticles(
         ParticleTypes.DAMAGE_INDICATOR, sp.getX(), sp.getY() + 1.0, sp.getZ(), 30, 0.5, 0.6, 0.5, 0.06
      );
      SoundUtil.play(sp, ModSounds.DENY);
      Chat.raw(
         sp,
         "§c§lYOU ARE DOWN. §r§7A party member can still get you up - but only for §f" + (DOWNED_TICKS / 20)
            + " seconds§7, and the site keeps coming either way."
      );
      for (ServerPlayer mate : crew(s)) {
         if (mate.getUUID().equals(sp.getUUID())) {
            continue;
         }
         Chat.raw(
            mate,
            "§c§l" + sp.getName().getString() + " IS DOWN §r§7- §f" + (DOWNED_TICKS / 20)
               + "s to reach them §8· "
               + (int)Math.sqrt((double)horizDist2(mate.blockPosition(), sp.blockPosition())) + "m "
               + bearing(sp.getX() - mate.getX(), sp.getZ() - mate.getZ())
               + " §8· " + sp.getBlockX() + ", " + sp.getBlockY() + ", " + sp.getBlockZ()
         );
         SoundUtil.play(mate, ModSounds.MYSTERY);
      }
   }

   /** Takes the downed mark off a body: on a rescue, on a blow that finishes them, or on the clock. */
   private static void clearDowned(State s, ServerPlayer sp, boolean up) {
      State.Body body = s.body(sp.getUUID());
      body.downed = false;
      body.downedUntil = 0L;
      body.reviveHold = 0;
      sp.setGlowingTag(false);
      sp.removeEffect(MobEffects.SLOWNESS);
      sp.removeEffect(MobEffects.WEAKNESS);
      if (up) {
         sp.setHealth(Math.max(sp.getHealth(), sp.getMaxHealth() * 0.5F));
         sp.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 100, 1, false, false, true));
         s.zoneLevel.sendParticles(
            ParticleTypes.HEART, sp.getX(), sp.getY() + 1.2, sp.getZ(), 12, 0.4, 0.4, 0.4, 0.02
         );
      }
   }

   /**
    * The site's care of its fallen, run once a tick for the whole site.
    *
    * <p>Two things happen here and they are one thing seen from either end: a fall nobody reached in
    * time is taken by the site, and a fall with somebody standing over it is undone. The rescuer pays
    * for it the way the site charges for everything - with time, in a room that is still spawning -
    * and the fallen explorer is not moved, healed or sped up while they wait.
    */
   private static void downedTick(State s, long tick) {
      long now = ServerClock.clock(s.zoneLevel);
      for (ServerPlayer victim : crew(s)) {
         State.Body body = s.body(victim.getUUID());
         if (!body.downed) {
            continue;
         }
         if (downedExpired(body.downedUntil, now)) {
            // Nobody came, so the site finishes what the blow started - through eject, so a fall costs
            // exactly what a fatal blow costs: the run, the loot, the pack, and the wait to go back in.
            clearDowned(s, victim, false);
            eject(victim, s, "§c§lTHE SITE TOOK YOU", "you went down and nobody reached you in time", true);
            for (ServerPlayer mate : crew(s)) {
               if (!mate.getUUID().equals(victim.getUUID())) {
                  Chat.raw(
                     mate,
                     "§8" + victim.getName().getString()
                        + " was not reached in time - the site has taken them and everything they were carrying."
                  );
               }
            }
            continue;
         }
         // Who is standing over them - kept, rather than merely noticed, because a rescue has a name
         // on it. See squadCard: with four people in one site, who got somebody up is half of what
         // happened down there, and the nearest upright member is the one who did it.
         ServerPlayer helper = null;
         double closest = Double.MAX_VALUE;
         for (ServerPlayer mate : crew(s)) {
            if (mate.getUUID().equals(victim.getUUID()) || s.body(mate.getUUID()).downed) {
               continue;
            }
            double reach = mate.distanceTo(victim);
            if (reach <= REVIVE_RANGE && reach < closest) {
               closest = reach;
               helper = mate;
            }
         }
         boolean somebodyHere = helper != null;
         body.reviveHold = somebodyHere ? body.reviveHold + 1 : 0;
         if (reviveComplete(body.reviveHold)) {
            if (helper != null) {
               s.body(helper.getUUID()).revives++;
            }
            clearDowned(s, victim, true);
            SoundUtil.play(victim, ModSounds.JOB_COMPLETE);
            Chat.raw(victim, "§a§lBACK ON YOUR FEET. §r§7Somebody got to you in time - keep moving.");
            for (ServerPlayer mate : crew(s)) {
               if (!mate.getUUID().equals(victim.getUUID())) {
                  Chat.raw(mate, "§a§l" + victim.getName().getString() + " IS BACK UP.");
               }
            }
            continue;
         }
         long left = Math.max(0L, (body.downedUntil - now) / 20L);
         if (tick % 10L == 0L) {
            actionBar(
               victim,
               somebodyHere
                  ? "§a§lBEING PICKED UP §r§7- stay still §8· §c" + left + "s left"
                  : "§c§lDOWN §r§7- §c" + left + "s§7 left. Crawl to somebody, or wait to be reached."
            );
         }
         if (tick % 20L == 0L) {
            s.zoneLevel.sendParticles(
               ParticleTypes.DAMAGE_INDICATOR, victim.getX(), victim.getY() + 1.0, victim.getZ(), 4, 0.3, 0.4, 0.3, 0.02
            );
         }
      }
   }

   /** The eight directions a sidebar can spell, north first. */
   private static final String[] BEARINGS = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};

   /**
    * Which way a teammate is from here.
    *
    * <p>A distance on a sidebar says how far and never which way, which in a maze of identical halls
    * is most of the question. North is the game's own north - negative Z - because the compass the
    * site already hands out is read the same way.
    */
   public static String bearing(double dx, double dz) {
      if (dx == 0.0 && dz == 0.0) {
         return "here";
      }
      double degrees = Math.toDegrees(Math.atan2(dx, -dz));
      int sector = (int)Math.round(degrees / 45.0) & 7;
      return BEARINGS[sector];
   }

   /**
    * The party's line on an explorer's own sidebar: who is nearest, how far, and which way.
    *
    * <p>A fall outranks a nearest member, because a distance is information and a countdown is a job:
    * with somebody on the floor this line stops being a convenience and becomes the way the rest of the
    * party finds them in time. It replaces the compass hint rather than sitting beside it - the panel is
    * a fixed height, and in a party the living landmark is the more useful of the two.
    */
   private static String partyLine(ServerPlayer sp, State s) {
      ServerPlayer fallen = null;
      ServerPlayer nearest = null;
      double best = Double.MAX_VALUE;
      for (ServerPlayer mate : crew(s)) {
         if (mate.getUUID().equals(sp.getUUID())) {
            continue;
         }
         State.Body body = s.body(mate.getUUID());
         if (body.downed) {
            if (fallen == null || body.downedUntil < s.body(fallen.getUUID()).downedUntil) {
               fallen = mate;
            }
            continue;
         }
         double d2 = (double)horizDist2(sp.blockPosition(), mate.blockPosition());
         if (d2 < best) {
            best = d2;
            nearest = mate;
         }
      }
      if (fallen != null) {
         long left = Math.max(1L, (s.body(fallen.getUUID()).downedUntil - ServerClock.clock(s.zoneLevel)) / 20L);
         return "§c§l" + fallen.getName().getString() + " DOWN §r§7" + left + "s §8· §f"
            + (int)Math.sqrt((double)horizDist2(sp.blockPosition(), fallen.blockPosition())) + "m "
            + bearing(fallen.getX() - sp.getX(), fallen.getZ() - sp.getZ());
      }
      if (nearest == null) {
         return "§bParty §f" + siteCrew(s);
      }
      return "§bParty §f" + siteCrew(s) + " §8· §7" + nearest.getName().getString() + " §f"
         + (int)Math.sqrt(best) + "m " + bearing(nearest.getX() - sp.getX(), nearest.getZ() - sp.getZ());
   }

   /** This rule, asked on its own - the shape the harness and older callers use. */
   public static boolean onLethalDamage(ServerPlayer sp, net.minecraft.world.damagesource.DamageSource src, float amount) {
      return LethalBlows.verdict(LethalBlows.EXPEDITION, sp, src, amount);
   }

   public static State stateOf(UUID uuid) {
      return active.get(uuid);
   }

   /**
    * True if the block is part of the dungeon's unbreakable structure: floor, ceiling, wall
    * band, the barred cages of a mob den, loot chests and spawners.
    * Ore veins are deliberately NOT protected - the whole point is mining the ores.
    */
   public static boolean isProtectedBoundary(ServerPlayer sp, BlockPos pos) {
      State s = active.get(sp.getUUID());
      if (s == null || s.zoneLevel != sp.level()) {
         return false;
      }
      if (!s.rooms.containsKey(roomKeyOf(s, pos))) {
         return false; // Never carved - the player cannot be standing there anyway.
      }
      BlockState here = sp.level().getBlockState(pos);
      // Mob-den spawners are part of the structure - unbreakable, can't be picked up.
      // Chests are protected - open them to loot contents as cash, don't break them.
      // Mob-den cages are built out of bars - they are structure, not salvage.
      if (here.is(Blocks.SPAWNER) || here.is(Blocks.CHEST) || here.is(Blocks.IRON_BARS)) {
         return true;
      }
      if (isMineableOre(here)) {
         return false;
      }
      int fy = floorY(s);
      // Floor, sub-floor and everything at or above the ceiling (a grand hall raises its own
      // roof inside this span) are structure.
      if (pos.getY() <= fy || pos.getY() >= fy + ROOM_HEIGHT) {
         return true;
      }
      // The wall band of the cell the block sits in.
      int lx = Math.floorMod(pos.getX() - s.center.getX(), ROOM_PITCH);
      int lz = Math.floorMod(pos.getZ() - s.center.getZ(), ROOM_PITCH);
      return lx < WALL || lx >= ROOM_PITCH - WALL || lz < WALL || lz >= ROOM_PITCH - WALL;
   }

   private static boolean isMineableOre(BlockState state) {
      if (state == null || state.isAir()) return false;
      net.minecraft.world.level.block.Block b = state.getBlock();
      return b == Blocks.COAL_ORE || b == Blocks.DEEPSLATE_COAL_ORE
         || b == Blocks.IRON_ORE || b == Blocks.DEEPSLATE_IRON_ORE
         || b == Blocks.GOLD_ORE || b == Blocks.DEEPSLATE_GOLD_ORE
         || b == Blocks.DIAMOND_ORE || b == Blocks.DEEPSLATE_DIAMOND_ORE
         || b == Blocks.EMERALD_ORE || b == Blocks.DEEPSLATE_EMERALD_ORE
         || b == Blocks.REDSTONE_ORE || b == Blocks.DEEPSLATE_REDSTONE_ORE
         || b == Blocks.LAPIS_ORE || b == Blocks.DEEPSLATE_LAPIS_ORE
         || b == Blocks.COPPER_ORE || b == Blocks.DEEPSLATE_COPPER_ORE
         || b == Blocks.NETHER_QUARTZ_ORE || b == Blocks.NETHER_GOLD_ORE
         || b == Blocks.ANCIENT_DEBRIS || b == Blocks.AMETHYST_BLOCK;
   }

   private static long roomKey(int rx, int rz) {
      return ((long)rx << 32) | (rz & 0xFFFFFFFFL);
   }

   /**
    * One guardian's arena: the room itself, the cell at the middle of the block, and the eight rim
    * cells that hold its doors.
    *
    * <p>The middle cell is the room's key and the rim cells are separate chambers in the maze's own
    * books only so that archways can be cut in them: the arena's middle has no wall for a door, and a
    * door needs a cell with a wall in it. See {@link #buildWideArena}.
    */
   private static final class Arena {
      final int rx;
      final int rz;
      final Room room;
      final Map<Long, Room> rings = new HashMap<>();

      Arena(int rx, int rz, Room room) {
         this.rx = rx;
         this.rz = rz;
         this.room = room;
      }

      /** True for any of the nine cells this arena is built across. */
      boolean holds(int cellX, int cellZ) {
         return onArenaBlock(cellX - rx, cellZ - rz);
      }
   }

   /**
    * True for every cell an arena at {@code (0,0)} is built across: the middle and the eight cells
    * reserved around it.
    *
    * <p>Public and static so that the reservation itself is the thing under test rather than a copy
    * of it: the maze has to refuse to build in those eight cells, and a rework that dropped the
    * reservation to a single cell would otherwise compile into a maze growing through a boss room.
    */
   public static boolean onArenaBlock(int dx, int dz) {
      int reach = ARENA_CELLS / 2;
      return Math.abs(dx) <= reach && Math.abs(dz) <= reach;
   }

   /**
    * What a test-built arena came out as, read back off the blocks after it was carved.
    *
    * <p>Every field is a count of real blocks in a real level rather than a description of what the
    * code intended: {@code interiorWalls} is the number of wall blocks standing on the seams where
    * the maze's own cell walls would have gone, {@code openCells} is how many of the nine cell
    * middles have headroom, and {@code doorRuns} is how many openings there are in the outer shell
    * with {@code widestDoor} being the widest of them. See {@link #buildArenaForTest}.
    */
   public record ArenaReport(
      int span, int roomsBuilt, int interiorWalls, int openCells, int doorRuns, int sidesWithDoors,
      int widestDoor, int doorsRequested, int ox, int oz, int fy
   ) {
   }

   /**
    * Test seam: carves a real three-by-three guardian arena into a live level and reports it.
    *
    * <p>The arena's whole design is a geometric claim - nine cells, no interior walls, every way in
    * and out on the outer ring - and a claim like that can only be checked by building one and
    * looking at it. This builds it through the same {@link #buildWideArena} the game calls and then
    * reads the result back off the blocks, so a change that quietly grew a wall through the middle
    * of the boss room fails a test rather than shipping.
    *
    * <p>It builds at the cell the caller names (the middle of the nine), with a throwaway run state
    * whose only real content is the level, so the caller picks the ground. Returns null when the
    * three-by-three could not be laid out at all - which the real game reads as "fall back to the
    * cell-sized arena" and the test reads as a failure worth looking at.
    */
   public static ArenaReport buildArenaForTest(ServerLevel level, BlockPos center, Type type, int depth, int rx, int rz) {
      State s = new State(
         type, Anomaly.roll(), level.dimension(),
         center.getX() + 0.5, center.getY(), center.getZ() + 0.5, 0.0F, 0.0F,
         level, center, UUID.randomUUID(), ServerClock.clock(level)
      );
      Room room = buildWideArena(s, rx, rz, depth);
      if (room == null) {
         return null;
      }
      Arena arena = arenaOf(s, room);
      int roomsBuilt = s.rooms.size();
      int opened = arena == null ? 0 : openArenaExits(s, arena);
      return arenaReport(s, arena, opened, roomsBuilt);
   }

   /** Reads a built arena back off the level: the numbers {@link ArenaReport} is made of. */
   private static ArenaReport arenaReport(State s, Arena arena, int opened, int roomsBuilt) {
      ServerLevel level = s.zoneLevel;
      BlockState wall = wallBlockFor(s.type);
      int ox = arena == null ? 0 : baseX(s, arena.rx - ARENA_CELLS / 2);
      int oz = arena == null ? 0 : baseZ(s, arena.rz - ARENA_CELLS / 2);
      int fy = floorY(s);
      // The seams: the two planes each way where a wall between two of the nine cells would stand.
      int interiorWalls = 0;
      for (int l = WALL; l < ARENA_SPAN - WALL; l++) {
         for (int y = fy + 1; y <= fy + 4; y++) {
            for (int seam : new int[]{ROOM_PITCH, ROOM_PITCH * 2}) {
               if (level.getBlockState(new BlockPos(ox + seam, y, oz + l)).is(wall.getBlock())) {
                  interiorWalls++;
               }
               if (level.getBlockState(new BlockPos(ox + l, y, oz + seam)).is(wall.getBlock())) {
                  interiorWalls++;
               }
            }
         }
      }
      // The nine cell middles: each one has to be a place a body can stand. Not "air at the pit's
      // own level" - the arena's floor is three blocks higher than the pit's outside the pit, which
      // is the terrace the fight is built around - so this asks the honest question: is there some
      // height in this cell where two blocks are clear?
      int openCells = 0;
      for (int cx = 0; cx < ARENA_CELLS; cx++) {
         for (int cz = 0; cz < ARENA_CELLS; cz++) {
            int lx = cx * ROOM_PITCH + ROOM_PITCH / 2;
            int lz = cz * ROOM_PITCH + ROOM_PITCH / 2;
            boolean standable = false;
            for (int y = fy + 1; y <= fy + ARENA_HEIGHT - 4; y++) {
               if (level.getBlockState(new BlockPos(ox + lx, y, oz + lz)).isAir()
                  && level.getBlockState(new BlockPos(ox + lx, y + 1, oz + lz)).isAir()) {
                  standable = true;
                  break;
               }
            }
            if (standable) {
               openCells++;
            }
         }
      }
      // The outer ring, walked as one loop so a doorway crossing a corner is one opening and not two.
      List<int[]> ring = new ArrayList<>();
      for (int lx = WALL; lx < ARENA_SPAN - WALL; lx++) {
         ring.add(new int[]{lx, 0});
      }
      for (int lz = WALL; lz < ARENA_SPAN - WALL; lz++) {
         ring.add(new int[]{ARENA_SPAN - 1, lz});
      }
      for (int lx = ARENA_SPAN - 1 - WALL; lx >= WALL; lx--) {
         ring.add(new int[]{lx, ARENA_SPAN - 1});
      }
      for (int lz = ARENA_SPAN - 1 - WALL; lz >= WALL; lz--) {
         ring.add(new int[]{0, lz});
      }
      int doorRuns = 0;
      int widest = 0;
      int run = 0;           HashSet<Integer> sides = new HashSet<>();
      for (int i = 0; i <= ring.size(); i++) {
         int[] at = i < ring.size() ? ring.get(i) : null;
         boolean open = at != null
            && level.getBlockState(new BlockPos(ox + at[0], fy + 1, oz + at[1])).isAir()
            && level.getBlockState(new BlockPos(ox + at[0], fy + 2, oz + at[1])).isAir();
         if (open) {
            run++;
            if (at[1] == 0) {
               sides.add(0);
            } else if (at[0] == ARENA_SPAN - 1) {
               sides.add(1);
            } else if (at[1] == ARENA_SPAN - 1) {
               sides.add(2);
            } else {
               sides.add(3);
            }
         } else if (run > 0) {
            doorRuns++;
            widest = Math.max(widest, run);
            run = 0;
         }
      }
      return new ArenaReport(ARENA_SPAN, roomsBuilt, interiorWalls, openCells, doorRuns, sides.size(), widest, opened, ox, oz, fy);
   }

   /** The arena a room is the middle of, or null when it is an ordinary chamber. */
   private static Arena arenaOf(State s, Room room) {
      if (room == null) {
         return null;
      }
      for (Arena arena : s.arenas.values()) {
         if (arena.room == room) {
            return arena;
         }
      }
      return null;
   }

   /** True when this chamber is a guardian's arena at the three-by-three size. */
   private static boolean inArena(State s, Room room) {
      return arenaOf(s, room) != null;
   }

   /** True for one of the rim cells around an arena's middle - a chamber that is only doors. */
   private static boolean isArenaRing(State s, Room room) {
      if (room == null) {
         return false;
      }
      for (Arena arena : s.arenas.values()) {
         if (arena.rings.containsValue(room)) {
            return true;
         }
      }
      return false;
   }

   /** The arena one cell of the maze belongs to, if it belongs to one. */
   private static Arena arenaAt(State s, int rx, int rz) {
      for (Arena arena : s.arenas.values()) {
         if (arena.holds(rx, rz)) {
            return arena;
         }
      }
      return null;
   }

   private static long roomKeyOf(State s, BlockPos pos) {
      int rx = Math.floorDiv(pos.getX() - s.center.getX(), ROOM_PITCH);
      int rz = Math.floorDiv(pos.getZ() - s.center.getZ(), ROOM_PITCH);
      // An arena is nine cells of floor and one room of it: a body standing anywhere in the block -
      // pit, terrace or rim - is standing in the arena, and every lookup that asks where somebody is
      // has to agree with that. See buildWideArena.
      Arena arena = arenaAt(s, rx, rz);
      return arena != null ? roomKey(arena.rx, arena.rz) : roomKey(rx, rz);
   }

   /**
    * Puts an explorer who has fallen through the floor back onto it - without ending their run.
    *
    * <p>This used to teleport them onto the entrance pad, and the entrance pad is an extraction: the
    * tick straight after read a body standing on the glowstone and finished the run, teleporting
    * them home and paying out. So a flooded chamber was a trap that could not be read as one - dive
    * under the water in a flooded hall, drop into a pool, slip off the wreckage of a broken crossing,
    * and the site threw the explorer out of itself, mid-run, for going swimming. That is the whole
    * of "the water cave kicks you out".
    *
    * <p>The pull now lands them on the floor of the chamber they were already in, at their own
    * column where that column has something to stand on and at the chamber's middle where it does
    * not, so a rescue reads as resurfacing rather than as being shown the door. Only when the site
    * itself is gone - a collapse, a chamber mid-fall - does it fall back to the pad, and then it
    * disarms the pad for a moment first, because a rescue is a landing and not an exit.
    *
    * <p>Five ticks of water is a swim, so the water is not treated as the problem: this is written
    * for any body that ends up under the site's floor, however it got there.
    */
   private static void pullFromTheAbyss(State s, ServerPlayer sp) {
      BlockPos spot = rescueSpot(s, sp);
      teleportPlayer(sp, s.zoneLevel, spot.getX() + 0.5, standY(spot), spot.getZ() + 0.5);
      sp.hurt(sp.damageSources().fall(), 1.0F);
      Chat.raw(sp, "\u00a7bYou were pulled back from the abyss!");
   }

   /**
    * Where a rescue puts somebody back: their own column first, then the middle of the chamber they
    * were in, and only then the entrance pad - see {@link #pullFromTheAbyss} for why that order is
    * the difference between resurfacing and being extracted.
    */
   private static BlockPos rescueSpot(State s, ServerPlayer sp) {
      ServerLevel level = s.zoneLevel;
      int fy = floorY(s);
      BlockPos at = sp.blockPosition();
      for (int y = fy; y <= fy + 4; y++) {
         BlockPos here = new BlockPos(at.getX(), y, at.getZ());
         if (rescueFooting(level, here)) {
            return here;
         }
      }
      BlockPos middle = s.body(sp.getUUID()).lastRoom == null ? null : roomSpot(s, s.body(sp.getUUID()).lastRoom);
      if (middle != null) {
         for (int y = fy; y <= fy + 4; y++) {
            BlockPos here = new BlockPos(middle.getX(), y, middle.getZ());
            if (rescueFooting(level, here)) {
               return here;
            }
         }
      }
      if (s.entrancePad != null) {
         // The last resort, and disarmed while it is used: a body standing on the pad one tick after
         // a teleport is exactly what the escape grace exists for. See State.escapeArmedAt.
         s.body(sp.getUUID()).hasLeftEntrance = false;
         s.escapeArmedAt = ServerClock.clock(level) + SITE_ENTRY_GRACE_TICKS;
         return s.entrancePad;
      }
      return at.above(2);
   }

   /**
    * True when a body could stand here: floor under it, and room for the body and its head.
    *
    * <p>Water counts as room - a rescue that refused to put somebody back in the pool they fell into
    * would have to look for dry ground, and in a flooded chamber there is none.
    */
   private static boolean rescueFooting(ServerLevel level, BlockPos at) {
      BlockState below = level.getBlockState(at.below());
      if (below.isAir() || below.liquid() || !below.getFluidState().isEmpty()) {
         return false;
      }
      return bodyFits(level.getBlockState(at)) && bodyFits(level.getBlockState(at.above()));
   }

   /** Air, or a fluid a body can be in. Anything else is a wall, a floor or furniture. */
   private static boolean bodyFits(BlockState state) {
      return state.isAir() || state.liquid();
   }

   private static int baseX(State s, int rx) {
      return s.center.getX() + rx * ROOM_PITCH;
   }

   private static int baseZ(State s, int rz) {
      return s.center.getZ() + rz * ROOM_PITCH;
   }

   private static int floorY(State s) {
      return s.center.getY() - 1;
   }

   // ------------------------------------------------------------------
   // The supplier's book, and the Time Shard it sells
   // ------------------------------------------------------------------

   /**
    * The shop's price cycle, in blocks of two minutes of site time.
    *
    * <p>The supplier is not a vending machine. Its prices drift on their own clock - cheap when the
    * tide is out, dear when it is in - and on top of that each line gets dearer as this run buys
    * it, and dearer again as the run gets richer. That last part is deliberate: the supplier is the
    * one place secured loot can be spent, and a shop that stays cheap for a run carrying $80,000
    * is a shop nobody has to think about.
    */
   private static final double[] SUPPLIER_CYCLE = {1.0, 0.85, 1.15, 0.9, 1.1, 0.95};

   /** What one line of the supplier's stock costs right now. */
   public static long supplierPrice(UUID uuid, String key, long base) {
      State s = active.get(uuid);
      if (s == null) {
         return base;
      }
      int bought = s.supplierDemand.getOrDefault(key, 0);
      double demand = 1.0 + 0.15 * bought;
      double wealth = 1.0 + Math.min(0.8, s.lootValue / 50_000.0);
      double tide = SUPPLIER_CYCLE[(int)(ServerClock.clock(s.zoneLevel) / 2400L % SUPPLIER_CYCLE.length)];
      return Math.max(1L, Math.round(base * demand * wealth * tide));
   }

   /** The multiplier the shop's own tide is running at, for the window to print. */
   public static double supplierTide(UUID uuid) {
      State s = active.get(uuid);
      if (s == null) {
         return 1.0;
      }
      return SUPPLIER_CYCLE[(int)(ServerClock.clock(s.zoneLevel) / 2400L % SUPPLIER_CYCLE.length)];
   }

   /** Records a purchase, which is what makes the same line dearer next time. */
   public static void supplierBought(UUID uuid, String key) {
      State s = active.get(uuid);
      if (s != null) {
         s.supplierDemand.merge(key, 1, Integer::sum);
      }
   }

   /** How many seconds of site time one Time Shard buys. */
   public static final int TIME_SHARD_SECONDS = 45;
   /** Name and custom-data flag of the Time Shard - a renamed clock, bought from the supplier. */
   public static final String TIME_SHARD_NAME = "§d§lTime Shard";
   public static final String TIME_SHARD_TAG = "ff_time_shard";

   /** One Time Shard: a clock that, right-clicked, buys the run another {TIME_SHARD_SECONDS}. */
   public static ItemStack timeShard() {
      ItemStack shard = new ItemStack(Items.CLOCK);
      shard.set(DataComponents.CUSTOM_NAME, Component.literal(TIME_SHARD_NAME));
      shard.set(
         DataComponents.LORE,
         new net.minecraft.world.item.component.ItemLore(List.of(
            Component.literal("§7Right-click to give this site §d" + TIME_SHARD_SECONDS + " seconds§7 more of itself."),
            Component.literal("§8Time is the only thing here that is not loot.")
         ))
      );
      shard.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      CustomData.update(DataComponents.CUSTOM_DATA, shard, tag -> tag.putBoolean(TIME_SHARD_TAG, true));
      return shard;
   }

   /** True for a Time Shard, by the flag its own item carries. */
   public static boolean isTimeShard(ItemStack stack) {
      if (stack == null || stack.isEmpty() || !stack.is(Items.CLOCK)) {
         return false;
      }
      CustomData data = stack.get(DataComponents.CUSTOM_DATA);
      return data != null && data.copyTag().getBooleanOr(TIME_SHARD_TAG, false);
   }

   /** Right-clicking a Time Shard: the clock moves in the only direction that helps. */
   public static String useTimeShard(ServerPlayer sp, ItemStack held) {
      State s = active.get(sp.getUUID());
      if (s == null || s.zoneLevel != sp.level()) {
         return "A Time Shard only has anything to give inside a site.";
      }
      if (!timeBonusAllowed(s.collapsing)) {
         return "The site is already coming down - there is no more clock to buy.";
      }
      held.shrink(1);
      s.durationTicks += 20L * TIME_SHARD_SECONDS;
      s.zoneLevel.sendParticles(
         ParticleTypes.END_ROD, sp.getX(), sp.getY() + 1.2, sp.getZ(), 30, 0.5, 0.6, 0.5, 0.06
      );
      SoundUtil.play(sp, ModSounds.TRANSFER);
      Chat.raw(sp, "§d§lTHE CLOCK MOVES. §r§7+" + TIME_SHARD_SECONDS + " seconds on this site.");
      return null;
   }

   /**
    * Stitches a pack upgrader the player is holding into the pack they are carrying.
    *
    * <p>The chest window is where a patch is normally found and spent, but a patch that reaches a
    * player any other way - a trade, a drop, an operator handing one out - has to work as well, or
    * the find becomes a trinket with a name. The patch is spent, the pack's own capacity moves, and
    * the pieces already in the pack are untouched: a bigger pack is only ever more room.
    *
    * @return null on success, or the reason it could not be done
    */
   public static String patchPack(ServerPlayer sp) {
      ItemStack held = sp.getMainHandItem();
      if (!LootBackpack.isUpgrader(held)) {
         return "Hold a pack upgrader to stitch one in.";
      }
      ItemStack pack = LootBackpack.held(sp);
      if (pack.isEmpty()) {
         return "You have no Loot Backpack - the site hands you one on arrival.";
      }
      int patched = PartyManager.widen(sp);
      if (patched <= 0) {
         return "Your pack is already as big as it gets (" + LootBackpack.MAX_CAPACITY + " pieces).";
      }
      held.shrink(1);
      sp.level().sendParticles(
         ParticleTypes.HAPPY_VILLAGER, sp.getX(), sp.getY() + 1.2, sp.getZ(), 18, 0.5, 0.4, 0.5, 0.08
      );
      SoundUtil.play(sp, ModSounds.CLAIM);
      Chat.raw(
         sp, "§6§lTHE PACK IS PATCHED. §r§7It holds §f" + patched + "§7 pieces now §8(+"
            + LootBackpack.UPGRADE_STEP + "§8)."
      );
      return null;
   }

   /**
    * The Return Compass: the entrance hands you one at the start and takes it back on exit.
    *
    * <p>It is a real lodestone compass, not a named trinket: its tracker points at the lodestone
    * standing beside the entrance pad, which every threshold builds in a fixed spot. That matters
    * for two reasons. A compass whose tracker is missing or invalid does not point anywhere - it
    * spins, which is the "corrupted" compass a player cannot read. And this way the needle answers
    * the only navigation question extraction actually asks: which way is home.
    */
   public static final String EXIT_COMPASS_NAME = "§b§lReturn Compass";
   /** Custom-data flag identifying a Return Compass, so a stale one can always be found. */
   public static final String EXIT_COMPASS_TAG = "ff_return_compass";

   /** The lodestone the threshold builds beside its escape pad - the compass's anchor. */
   private static BlockPos entranceLodestone(State s) {
      return new BlockPos(baseX(s, 0) + ROOM_PITCH / 2 - 1, floorY(s) + 1, baseZ(s, 0) + ROOM_PITCH / 2);
   }

   /** A Return Compass locked onto this run's entrance. */
   private static ItemStack exitCompass(State s) {
      return returnCompassFor(entranceLodestone(s));
   }

   /** A Return Compass locked onto one anchor position - the shape every return compass takes. */
   public static ItemStack returnCompassFor(BlockPos lodestone) {
      ItemStack compass = new ItemStack(Items.COMPASS);
      compass.set(DataComponents.CUSTOM_NAME, Component.literal(EXIT_COMPASS_NAME));
      compass.set(
         DataComponents.LORE,
         new net.minecraft.world.item.component.ItemLore(List.of(
            Component.literal("§7The needle points at the lodestone by the §fentrance pad§7."),
            Component.literal("§7Stand on the glowstone to extract with everything you carry."),
            Component.literal("§8It is handed back when you leave - or when the site takes you.")
         ))
      );
      compass.set(
         DataComponents.LODESTONE_TRACKER,
         new LodestoneTracker(java.util.Optional.of(GlobalPos.of(EXPEDITION_DIM, lodestone)), true)
      );
      CustomData.update(DataComponents.CUSTOM_DATA, compass, tag -> tag.putBoolean(EXIT_COMPASS_TAG, true));
      return compass;
   }

   /** True for a Return Compass, by the flag its own item carries. */
   public static boolean isExitCompass(ItemStack stack) {
      if (stack == null || stack.isEmpty() || !stack.is(Items.COMPASS)) {
         return false;
      }
      CustomData data = stack.get(DataComponents.CUSTOM_DATA);
      return data != null && data.copyTag().getBooleanOr(EXIT_COMPASS_TAG, false);
   }

   /** Hands the player the Return Compass for this run, unless they already carry one. */
   private static void giveExitCompass(ServerPlayer sp, State s) {
      for (int i = 0; i < sp.getInventory().getContainerSize(); i++) {
         if (isExitCompass(sp.getInventory().getItem(i))) {
            return;
         }
      }
      com.fortuneandfavors.util.InventoryHelper.giveOrDrop(sp, exitCompass(s));
   }

   /** Takes every Return Compass out of the player's inventory - the site keeps its own. */
   private static void withdrawExitCompass(ServerPlayer sp) {
      if (sp == null) {
         return;
      }
      try {
         for (int i = 0; i < sp.getInventory().getContainerSize(); i++) {
            if (isExitCompass(sp.getInventory().getItem(i))) {
               sp.getInventory().setItem(i, ItemStack.EMPTY);
            }
         }
      } catch (Throwable ignored) {
      }
   }

   /**
    * The Y a body actually stands at on a pad.
    *
    * <p>A pad's anchor is the FLOOR block at its centre - the block you walk on, not the block you
    * occupy. Teleporting to the anchor's own Y puts the player inside (or under) the floor they
    * were meant to land on, which is the whole of the "I spawn in the ground" bug in one number:
    * one block above the anchor is the pad's standing surface, and it is the same number for every
    * pad the dungeon builds - the entrance pad, the deep exit pad and the void rescue.
    */
   public static double standY(BlockPos pad) {
      return pad.getY() + 1.0;
   }

   /** Returns an error message, or null on success. */
   public static String start(ServerPlayer sp, Type type) {
      return start(sp, type, nextRunId());
   }

   /**
    * The descent, stamped with a run id chosen by the caller.
    *
    * <p>The caller is a party when there is one: four explorers going in together share one id, so
    * the pack each of them is handed answers for the same run, and the bag they are carrying is one
    * bag. A solo descent picks its own id, which is what {@link #start(ServerPlayer, Type)} does.
    */
   public static String start(ServerPlayer sp, Type type, long runId) {
      UUID uuid = sp.getUUID();
      if (active.containsKey(uuid)) {
         return "You're already on an expedition! Leave it first.";
      }
      if (com.fortuneandfavors.duel.DuelManager.isInDuel(uuid)) {
         return "You can't start an expedition mid-duel.";
      }
      MinecraftServer server = sp.level().getServer();
      long now = ServerClock.clock(server);
      String wait = fatalWaitText(uuid, now);
      if (wait != null) {
         return "You took a fatal blow on your last expedition - wait " + wait
            + " before going back in, or use an Expedition Compass to skip the rest of it.";
      }
      fatalCooldown.remove(uuid);
      // The site's own price, in the currency the site pays in. Checked here with the other
      // refusals so the answer is a sentence rather than a surprise, and actually charged far
      // below - after the ground has been taken and the site purged, which are the only two steps
      // that can still fail. An expedition that cannot start must not have cost anything.
      long entry = ExpeditionProgression.entryCost(type);
      if (entry > 0L && ExpeditionProgression.available(uuid) < entry) {
         return type.name + " costs " + entry + " expedition EXP to unlock and you have "
            + ExpeditionProgression.available(uuid) + ". Run the Deep Mine to earn more.";
      }
      ServerLevel zoneLevel = server.getLevel(EXPEDITION_DIM);
      if (zoneLevel == null) {
         return "The expedition realm isn't ready yet - try again in a moment.";
      }
      // A site of this run's own. Not a fixed point per dungeon any more - see acquireSite for why
      // two explorers used to end up carving the same maze out of the same blocks.
      BlockPos center = acquireSite(zoneLevel, type, uuid);
      Anomaly anomaly = Anomaly.roll();
      State state = new State(
         type, anomaly, sp.level().dimension(), sp.getX(), sp.getY(), sp.getZ(), sp.getYRot(), sp.getXRot(), zoneLevel, center, uuid, ServerClock.clock(zoneLevel)
      );
      state.runId = runId;
      // Nothing from the last run of this ground survives into this one. This is the answer to
      // "why is there a glowing zombie standing in my brand new site": the previous explorer's pack
      // was spawned persistence-required, so nothing natural would ever have taken it away, and the
      // marks come off the scoreboard and off the bodies here - before a single block is carved.
      purgeSite(state);
      // The door's price, taken last: the site is real, the ledger is stocked and the maze is about
      // to be carved, so nothing below this line can hand the money back with an apology.
      if (!ExpeditionProgression.payEntry(uuid, entry)) {
         return "That descent's entry fee did not go through - nothing was charged.";
      }
      active.put(uuid, state);
      codexRuns++;
      dungeonStats(type).runs++;
      // The threshold: one grand entrance chamber with the escape pad in its middle and
      // THREE doors already open onto the unknown. The maze grows from here, room by room.
      Room entrance = buildRoom(state, 0, 0, 0, Chamber.ENTRANCE);
      entrance.visited = true;
      entrance.cleared = true;
      state.body(uuid).lastRoom = entrance;
      openExits(state, entrance);
      state.entrancePad = new BlockPos(baseX(state, 0) + ROOM_PITCH / 2, floorY(state), baseZ(state, 0) + ROOM_PITCH / 2);
      // The pads do not answer until the explorer has actually been in the site for a moment - a body
      // that arrives ON a pad by teleport must not be read as having walked back to it.
      state.escapeArmedAt = ServerClock.clock(state.zoneLevel) + SITE_ENTRY_GRACE_TICKS;
      // Land on the pad's standing surface, dead centre of it - never at the anchor's own Y, which
      // is the floor block itself, and never on a corner, which is where the pad's own mast is.
      teleportPlayer(
         sp, zoneLevel,
         state.entrancePad.getX() + 0.5, standY(state.entrancePad), state.entrancePad.getZ() + 0.5
      );
      Chat.raw(sp, type.color + "§l" + type.name + "§r §7- " + type.desc);
      Chat.raw(sp, "§8Anomaly: §r" + anomaly.colour + "§l" + anomaly.name + "§r §7- " + anomaly.desc);
      Chat.raw(
         sp,
         "&7Enter a chamber and the pack wakes. &aClear it and three doors open.&7 Loot chests, ores and kills all pay - &cbut die or run out of time and you lose everything.&7 §fFind the glowstone pad§7 to escape!"
      );
      SoundUtil.play(sp, ModSounds.TRANSFER);
      // The way home: a real lodestone compass to the threshold's own lodestone. Extraction is a
      // walk back to the pad, and the maze is infinite in every direction - without this the
      // return trip is navigation by memory, which is not a skill the maze can teach.
      giveExitCompass(sp, state);
      // ...and the pack the chests now fill. Nine pieces by default, plus whatever Deep Pockets has
      // bought: a starter pack that ignored the permanent shop's upgrade would be an upgrade that
      // only pays out from the second run onwards.
      // ...and it is the party's pack when there is a party: nine pieces a name, handed to every
      // member, so the bag a group carries is one bag rather than four that look alike. See
      // PartyManager - a solo descent gets exactly the pack it always did.
      LootBackpack.give(
         sp,
         PartyManager.packRoomFor(sp, ExpeditionProgression.backpackStartCapacity(sp.getUUID())),
         runId
      );
      Chat.raw(sp, "§6§lYour Loot Backpack holds §f" + LootBackpack.capacity(LootBackpack.held(sp)) + "§6§l pieces. §r§7Chests hand their loot over one piece at a time - right-click the pack to see it, and throw pieces away when something better turns up.");
      Chat.raw(sp, "§b§lThe site hands you a Return Compass. §r§7Its needle points at the entrance lodestone - follow it back and stand on the glowstone to extract.");
      return null;
   }

   /**
    * A party's descent: one site, one run id, everybody in it.
    *
    * <p>The site is taken once, by the host, exactly as a solo descent takes one - and every other
    * name in the party is then put into it, rather than being given a site of their own. That is the
    * whole difference between a party and four people who happen to be underground at the same time:
    * one maze, one ledger of chests, one guardian, one clock and one collapse, with everybody walking
    * it. Their entry fees are their own, and so is the walk home - the site is shared, the trip is
    * not.
    *
    * @return null when the site opened, or the reason the host could not take it
    */
   public static String startParty(ServerPlayer host, List<ServerPlayer> members, Type type, long runId) {
      if (host == null || members == null || members.isEmpty()) {
         return "Nobody to send in.";
      }
      String problem = start(host, type, runId);
      if (problem != null) {
         return problem;
      }
      State s = active.get(host.getUUID());
      if (s == null) {
         return "The site did not open - try again in a moment.";
      }
      for (ServerPlayer member : members) {
         if (member.getUUID().equals(host.getUUID())) {
            continue;
         }
         String trouble = joinSite(member, s, type, runId);
         if (trouble != null) {
            Chat.raw(member, "§c" + trouble);
            Chat.raw(host, "§e" + member.getName().getString() + "§7 stayed behind - §f" + trouble);
         }
      }
      return null;
   }

   /**
    * Puts one more explorer into a site that is already standing.
    *
    * <p>Everything that belongs to the site has already happened by the time this runs - the ground
    * is taken, the maze is carved and the ledgers are in place - so this is the arrival rather than
    * the descent: where this explorer came in from, their own pads, their own pack at the party's
    * size, and their own place on the entrance pad. It is deliberately not a second descent: a member
    * of a party paying for a site of their own is exactly the thing a party is meant to stop.
    *
    * @return null on success, or the reason this explorer is staying in the hub
    */
   private static String joinSite(ServerPlayer sp, State s, Type type, long runId) {
      UUID uuid = sp.getUUID();
      if (active.containsKey(uuid)) {
         return "You're already on an expedition! Leave it first.";
      }
      if (com.fortuneandfavors.duel.DuelManager.isInDuel(uuid)) {
         return "You can't start an expedition mid-duel.";
      }
      MinecraftServer server = sp.level().getServer();
      if (server == null) {
         return "The server isn't ready yet.";
      }
      String wait = fatalWaitText(uuid, ServerClock.clock(server));
      if (wait != null) {
         return "You took a fatal blow on your last expedition - wait " + wait
            + " before going back in, or use an Expedition Compass to skip the rest of it.";
      }
      long entry = ExpeditionProgression.entryCost(type);
      if (entry > 0L && ExpeditionProgression.available(uuid) < entry) {
         return type.name + " costs " + entry + " expedition EXP to unlock and you have "
            + ExpeditionProgression.available(uuid) + ".";
      }
      if (!ExpeditionProgression.payEntry(uuid, entry)) {
         return "That descent's entry fee did not go through - nothing was charged.";
      }
      fatalCooldown.remove(uuid);
      // Where this explorer came in from, so the way out is their own way out: the site is shared,
      // the trip home is not. See State.Body.
      State.Body body = s.body(uuid);
      body.originDim = sp.level().dimension();
      body.originX = sp.getX();
      body.originY = sp.getY();
      body.originZ = sp.getZ();
      body.yaw = sp.getYRot();
      body.pitch = sp.getXRot();
      body.outOfSiteTicks = 0;
      body.atExitPad = false;
      body.exitPadHold = 0;
      body.hasLeftEntrance = false;
      body.lastRoom = s.rooms.get(roomKey(0, 0));
      active.put(uuid, s);
      if (s.entrancePad != null) {
         teleportPlayer(
            sp, s.zoneLevel,
            s.entrancePad.getX() + 0.5, standY(s.entrancePad), s.entrancePad.getZ() + 0.5
         );
      }
      LootBackpack.give(
         sp,
         PartyManager.packRoomFor(sp, ExpeditionProgression.backpackStartCapacity(uuid)),
         runId
      );
      giveExitCompass(sp, s);
      Chat.raw(sp, type.color + "§l" + type.name + "§r §7- " + type.desc);
      Chat.raw(
         sp,
         "§6§lYOU ARE IN WITH A PARTY. §r§7The site, the pack and the payday are everybody's: §f"
            + LootBackpack.capacity(LootBackpack.held(sp)) + "§7 pieces between you, and everyone who "
            + "reaches a pad is paid the same."
      );
      Chat.raw(sp, "§b§lThe site hands you a Return Compass. §r§7Its needle points at the entrance lodestone.");
      SoundUtil.play(sp, ModSounds.TRANSFER);
      return null;
   }

   public static void leave(ServerPlayer sp) {
      State s = active.remove(sp.getUUID());
      if (s == null) {
         return;
      }
      finish(sp, s, true);
   }

   public static void onDeath(ServerPlayer dp) {
      State s = active.remove(dp.getUUID());
      if (s == null) {
         return;
      }
      // A party's death is one explorer's death: the others are still down there, so the mobs stay
      // and the ground stays held until the last of them is out.
      if (!siteOccupied(s)) {
         releaseSites(s.holder);
         // Death does not spare the pack either: the explorer is gone, so what is left standing in
         // the site is the site's problem rather than their next run's.
         sweepZone(s);
         releasePackMarks(s);
      }
      endBoard(dp);
      withdrawExitCompass(dp);
      LootBackpack.withdraw(dp);
      codexFalls++;
      Chat.raw(dp, "§c§lEXPEDITION FAILED§r §7- you died! All " + Chat.moneyStr(s.lootValue) + " of secured loot was lost.");
   }

   public static void onBlockBroken(ServerPlayer sp, BlockPos pos, BlockState state) {
      try {
         State s = active.get(sp.getUUID());
         if (s == null || s.zoneLevel != sp.level()) {
            return;
         }
         ItemStack drop = new ItemStack(state.getBlock().asItem());
         if (drop.isEmpty()) {
            return;
         }
         long value = BlockValues.valueOf(drop);
         if (value <= 0L) {
            return;
         }
         value = (long)(value * s.anomaly.oreMul);
         s.lootValue += value;
         s.lootCount++;
         s.oresMined++;
         ServerLevel level = (ServerLevel)sp.level();
         level.sendParticles(ParticleTypes.END_ROD, pos.getX() + 0.5, pos.getY() + 0.6, pos.getZ() + 0.5, 6, 0.3, 0.3, 0.3, 0.04);
      } catch (Exception ignored) {
      }
   }

   /** How many pieces one chest offers. A pack is nine, so a chest is never a single click. */
   private static final int CHEST_PIECES_MIN = 3;
   private static final int CHEST_PIECES_MAX = 6;

   /**
    * Called when a player opens a chest inside an expedition.
    *
    * <p>Chests do not pay cash. They hold a handful of real pieces rolled for this dungeon at the
    * depth the run has reached, and the opening shows them - the player takes them one at a time
    * into the Loot Backpack, which holds {@link LootBackpack#CAPACITY} and no more. A chest in an
    * UNCLEARED chamber stays sealed: the room is the lock and the monsters are the key. Whatever
    * is left when the player walks away stays in the chest, so a piece nobody could fit is still
    * there when they come back for it.
    */
   public static void onChestOpen(ServerPlayer sp, BlockPos pos) {
      try {
         State s = active.get(sp.getUUID());
         if (s == null || s.zoneLevel != sp.level()) {
            return;
         }
         BlockEntity be = sp.level().getBlockEntity(pos);
         if (!(be instanceof ChestBlockEntity chest)) {
            return;
         }
         // Once-only, enforced two ways: the renamed chest AND a per-run set of
         // looted positions (so a lost rename or a double-chest half can't pay twice).
         if (s.lootedChests.contains(pos)) {
            Chat.raw(sp, "§7This chest has already been looted.");
            return;
         }
         String name = chest.getCustomName() == null ? "" : chest.getCustomName().getString();
         if (name.contains("Looted")) {
            s.lootedChests.add(pos);
            Chat.raw(sp, "§7This chest has already been looted.");
            return;
         }

         // The dungeon-crawler rule: a chamber's treasure stays sealed while the
         // chamber stands uncleared. Kill the pack first.
         Room room = s.rooms.get(roomKeyOf(s, pos));
         if (room != null && !room.cleared) {
            Chat.raw(sp, "§8The chest is sealed while this chamber stands uncleared - §ckill the pack first§8.");
            return;
         }

         List<ItemStack> offers = s.chestOffers.get(pos);
         if (offers == null) {
            if (s.chestLoot >= CHEST_LOOT_CAP) {
               s.lootedChests.add(pos);
               setChestName(chest, "§8Looted Chest");
               Chat.raw(sp, "§cChest loot cap reached (" + Chat.moneyStr(CHEST_LOOT_CAP) + "§c this run) - this chest is empty for you.");
               return;
            }
            offers = rollChestOffers(s, chest, name.contains("Rich"), LootBackpack.capacity(LootBackpack.held(sp)));
            s.chestOffers.put(pos, offers);
         }
         if (offers.isEmpty()) {
            Chat.raw(sp, "§7The chest is empty - no loot left in it.");
            markChestLooted(s, pos);
            return;
         }
         // Who opened it, for the squad card: counted the first time this explorer opens this chest
         // and not again, because coming back for the piece that would not fit is not a new chest.
         State.Body mine = s.body(sp.getUUID());
         if (mine.openedChests.add(pos)) {
            mine.chests++;
         }
         com.fortuneandfavors.menu.LootChestMenu.open(sp, pos);
      } catch (Exception ignored) {
      }
   }

   /**
    * Rolls what one chest holds. The old cash range is still the budget - it just leaves the chest
    * as pieces now instead of arithmetic, so what is inside can be looked at, valued and dropped.
    * Pieces are drawn for the depth the run has reached, not the depth of the chest's own chamber:
    * going deeper makes every chest in the maze better, which is the pull that keeps a run going.
    */
   private static List<ItemStack> rollChestOffers(State s, ChestBlockEntity chest, boolean rich, int packCapacity) {
      List<ItemStack> offers = new ArrayList<>();
      // Legacy chests (placed before the backpack) may still hold real items - take those first.
      for (int i = 0; i < chest.getContainerSize(); i++) {
         ItemStack stack = chest.getItem(i);
         if (!stack.isEmpty()) {
            offers.add(stack.copy());
            chest.setItem(i, ItemStack.EMPTY);
         }
      }
      long budget = chestCash(s.type, rich);
      int want = CHEST_PIECES_MIN + RANDOM.nextInt(CHEST_PIECES_MAX - CHEST_PIECES_MIN + 1);
      for (int i = 0; i < want && budget > 0L; i++) {
         ItemStack piece = lootPiece(s, budget);
         if (piece.isEmpty()) {
            break;
         }
         budget -= LootBackpack.valueOf(piece);
         offers.add(piece);
      }
      // The patch: one more row of pack, and the only thing in a chest that is not money. It is
      // offered last, so it reads as the chest's surprise rather than as its headline, and it is
      // never offered to a pack that cannot take it - a find that does nothing is worse than no
      // find at all.
      if (rich
         && packCapacity < LootBackpack.MAX_CAPACITY
         && RANDOM.nextDouble() < upgraderChance(true, s.deepest)) {
         offers.add(LootBackpack.upgrader());
      }
      return offers;
   }

   /**
    * How often a rich chest holds a pack upgrader, before depth is taken into account.
    *
    * <p>Raised from 0.18, because a patch is the one find a run is built around and a player who
    * cleared a site top to bottom without ever seeing one reported it as missing rather than as
    * rare. It is still a rich chest or nothing, and it is still a coin flip at the very deepest
    * point of the maze, so a patch remains the thing you tell the other players about - the change
    * is that a run which goes looking for one now has a fair chance of coming home with it.
    */
   public static final double UPGRADER_CHANCE_RICH = 0.34;
   /** What each ten chambers of depth adds to that chance. */
   public static final double UPGRADER_CHANCE_PER_TIER = 0.07;
   /** The most often any chest may hold one. A rich chest deep in a site is about a coin flip. */
   public static final double UPGRADER_CHANCE_MAX = 0.55;

   /**
    * The chance a chest holds a pack upgrader.
    *
    * <p>Only the rich chests - the treasure vaults, grand halls and caches a run has to go looking
    * for - ever hold one, and the deeper the run has reached the likelier they are, because a patch
    * found at chamber thirty is a patch that has to be carried home from chamber thirty. The plain
    * chests in plain chambers are loot; this is the thing you tell the other players about.
    */
   public static double upgraderChance(boolean rich, int depth) {
      if (!rich) {
         return 0.0;
      }
      double chance = UPGRADER_CHANCE_RICH + UPGRADER_CHANCE_PER_TIER * (double)Math.max(0, depth / 10);
      return Math.min(UPGRADER_CHANCE_MAX, chance);
   }

   /** One piece of chest loot, sized to fit what is left of the chest's budget. */
   private static ItemStack lootPiece(State s, long budget) {
      int depth = Math.max(1, s.deepest);
      int tier = depth >= 10 ? 3 : depth >= 5 ? 2 : 1;
      long target = Math.max(60L, budget / 3L);
      for (int attempt = 0; attempt < 8; attempt++) {
         Item item = lootItemFor(s.type, tier, RANDOM.nextInt(100));
         if (item == null) {
            continue;
         }
         long single = BlockValues.valueOf(new ItemStack(item));
         if (single <= 0L) {
            continue;
         }
         int count = (int)Math.max(1L, Math.min(8L, target / single));
         ItemStack piece = new ItemStack(item, count);
         if (LootBackpack.valueOf(piece) <= budget) {
            return piece;
         }
      }
      return ItemStack.EMPTY;
   }

   /**
    * Which item a piece of loot is, by depth tier: the reading half of the reward.
    *
    * <p>Tier one is the dungeon's own trade in bulk, tier two is gemstones and blocks, and tier
    * three - the loot only a run past ten chambers deep can be showing you - is the game's own
    * endgame stock. What any of it is worth is the economy's business, not this table's.
    */
   private static Item lootItemFor(Type type, int tier, int roll) {
      if (tier >= 3) {
         return switch (roll % 6) {
            case 0 -> Items.NETHERITE_SCRAP;
            case 1 -> Items.DIAMOND_BLOCK;
            case 2 -> Items.EMERALD_BLOCK;
            case 3 -> Items.ANCIENT_DEBRIS;
            case 4 -> Items.NETHERITE_INGOT;
            default -> Items.ENCHANTED_GOLDEN_APPLE;
         };
      }
      if (tier == 2) {
         return switch (roll % 6) {
            case 0 -> Items.DIAMOND;
            case 1 -> Items.EMERALD;
            case 2 -> Items.GOLD_BLOCK;
            case 3 -> Items.IRON_BLOCK;
            case 4 -> Items.AMETHYST_SHARD;
            default -> Items.LAPIS_LAZULI;
         };
      }
      return switch (type) {
         case DEEP_MINE -> roll % 2 == 0 ? Items.IRON_INGOT : Items.COAL;
         case MONSTER_CAVE -> roll % 2 == 0 ? Items.BONE : Items.IRON_INGOT;
         case CRYSTAL_CAVERN -> roll % 2 == 0 ? Items.AMETHYST_SHARD : Items.GOLD_INGOT;
         case VOID -> roll % 2 == 0 ? Items.QUARTZ : Items.GOLD_INGOT;
         case SUNKEN_TEMPLE -> roll % 2 == 0 ? Items.PRISMARINE_SHARD : Items.GOLD_INGOT;
         case FROZEN_CRYPT -> roll % 2 == 0 ? Items.IRON_INGOT : Items.DIAMOND;
         case MAGMA_FORGE -> roll % 2 == 0 ? Items.QUARTZ : Items.GOLD_INGOT;
         default -> Items.GOLD_INGOT;
      };
   }

   /** Marks a chest spent and renames it, so a lost offer map cannot pay twice. */
   private static void markChestLooted(State s, BlockPos pos) {
      s.lootedChests.add(pos);
      s.chestOffers.remove(pos);
      try {
         BlockEntity be = s.zoneLevel.getBlockEntity(pos);
         if (be instanceof ChestBlockEntity chest) {
            setChestName(chest, "§8Looted Chest");
         }
      } catch (Exception ignored) {
      }
   }

   /** What a chest is still holding, for its own window. */
   public static List<ItemStack> chestOffers(UUID uuid, BlockPos pos) {
      State s = active.get(uuid);
      if (s == null) {
         return List.of();
      }
      List<ItemStack> offers = s.chestOffers.get(pos);
      return offers == null ? List.of() : new ArrayList<>(offers);
   }

   /** How much more chest loot this run may bank before the per-run cap stops paying. */
   public static long chestRoom(UUID uuid) {
      State s = active.get(uuid);
      return s == null ? 0L : Math.max(0L, CHEST_LOOT_CAP - s.chestLoot);
   }

   /**
    * Moves one piece out of a chest and into the player's pack.
    *
    * <p>Returns null on success, or the reason it could not be done - the caller says it. Every
    * refusal here is a rule the player can act on: no pack, no room, or the run's cap.
    */
   public static String takeChestPiece(ServerPlayer sp, BlockPos pos, int index) {
      State s = active.get(sp.getUUID());
      if (s == null || s.zoneLevel != sp.level()) {
         return "You are not on an expedition any more.";
      }
      List<ItemStack> offers = s.chestOffers.get(pos);
      if (offers == null || index < 0 || index >= offers.size()) {
         return "That piece is already gone.";
      }
      ItemStack piece = offers.get(index);
      ItemStack pack = LootBackpack.held(sp);
      if (pack.isEmpty()) {
         return "You have no Loot Backpack - the site hands you one on arrival.";
      }
      if (!LootBackpack.belongsTo(pack, s.runId)) {
         // A pack that is not this run's is a claim on a run that no longer exists, and filling it
         // would be the stash again with extra steps. See LootBackpack#belongsTo.
         return "That pack was handed out by another run - the site will not fill it.";
      }
      // A patch is not a piece of loot: it is taken by the pack itself, so it is applied here,
      // before the room and the cap are asked about. A pack at its ceiling leaves the patch in the
      // chest rather than swallowing it, so it is still a find for somebody whose pack is smaller.
      if (LootBackpack.isUpgrader(piece)) {
         // On a party run the patch widens the party's pack, not this one member's - a bag that
         // grew only for whoever found the patch would shrink again the moment somebody else
         // opened their copy of it.
         int patched = PartyManager.widen(sp);
         if (patched <= 0) {
            return "Your pack is already as big as it gets (" + LootBackpack.MAX_CAPACITY + " pieces).";
         }
         offers.remove(index);
         s.zoneLevel.sendParticles(
            ParticleTypes.HAPPY_VILLAGER, pos.getX() + 0.5, pos.getY() + 1.2, pos.getZ() + 0.5, 18, 0.5, 0.4, 0.5, 0.08
         );
         com.fortuneandfavors.util.SoundUtil.play(sp, ModSounds.CLAIM);
         Chat.raw(
            sp, "§6§lTHE PACK IS PATCHED. §r§7It holds §f" + patched + "§7 pieces now §8(+"
               + LootBackpack.UPGRADE_STEP + "§8)."
         );
         if (offers.isEmpty()) {
            markChestLooted(s, pos);
            Chat.raw(sp, "§7That chest is empty now.");
         }
         return null;
      }
      if (!PartyManager.hasRoom(sp)) {
         return "The pack is full (" + PartyManager.roomOf(sp) + " pieces). Open it and throw something away that you want less.";
      }
      long value = LootBackpack.valueOf(piece);
      if (chestRoom(sp.getUUID()) < value) {
         return "Chest loot cap reached this run (" + Chat.moneyStr(CHEST_LOOT_CAP) + ") - this piece would pay nothing.";
      }
      offers.remove(index);
      // One list, and every member's pack is a copy of it: this is the line that puts a piece the
      // first explorer looted into the second explorer's bag.
      PartyManager.addPiece(sp, piece);
      creditChest(sp.getUUID(), value);
      s.zoneLevel.sendParticles(
         ParticleTypes.HAPPY_VILLAGER, pos.getX() + 0.5, pos.getY() + 1.1, pos.getZ() + 0.5, 6, 0.3, 0.3, 0.3, 0.03
      );
      if (offers.isEmpty()) {
         markChestLooted(s, pos);
         Chat.raw(sp, "§7That chest is empty now.");
      }
      return null;
   }

   /**
    * Throws one piece out of the pack. This is the whole decision the pack exists to pose: the
    * money it would have been is given up in exchange for the room to carry something better.
    */
   public static String discardPackPiece(ServerPlayer sp, int index) {
      ItemStack pack = LootBackpack.held(sp);
      if (pack.isEmpty()) {
         return "You have no Loot Backpack.";
      }
      // Read off the party's bag on a party run, for the same reason the pack is sized by it: there
      // is one list of pieces, and throwing one away takes it off everybody's copy at once.
      List<ItemStack> before = PartyManager.entriesOf(sp);
      if (index < 0 || index >= before.size()) {
         return "That piece is already gone.";
      }
      ItemStack piece = before.get(index);
      long value = LootBackpack.valueOf(piece);
      PartyManager.removePiece(sp, index);
      debitChest(sp.getUUID(), value);
      Chat.raw(
         sp, "§7Threw away §f" + piece.getCount() + "x " + piece.getHoverName().getString()
            + " §8(-§c" + Chat.moneyStr(value) + "§8) - §7room for something better."
      );
      return null;
   }

   /** Banks a piece's worth: the run's secured loot and the chest ledger move together. */
   private static void creditChest(UUID uuid, long value) {
      State s = active.get(uuid);
      if (s == null || value <= 0L) {
         return;
      }
      s.chestLoot += value;
      s.lootValue += value;
      s.lootCount++;
      s.chestsOpened++;
      // ...and the explorer who took it, which is the only per-member reading of "carried" a shared
      // bag allows: four people are walking out with one haul, so the question the squad card asks
      // is who filled it.
      s.body(uuid).carried += value;
   }

   /** Gives that worth back when a piece is thrown away. */
   private static void debitChest(UUID uuid, long value) {
      State s = active.get(uuid);
      if (s == null || value <= 0L) {
         return;
      }
      s.chestLoot = Math.max(0L, s.chestLoot - value);
      s.lootValue = Math.max(0L, s.lootValue - value);
   }

   /** Random cash payout range for an expedition loot chest, lowest to highest.
    *  Rich chests (treasure vaults, grand halls) pay 2x the base range. */
   private static long chestCash(Type type, boolean rich) {
      long min;
      long max;
      switch (type) {
         case DEEP_MINE -> {
            min = 900L;
            max = 2400L;
         }
         case MONSTER_CAVE -> {
            min = 1200L;
            max = 3400L;
         }
         case CRYSTAL_CAVERN -> {
            min = 1800L;
            max = 4800L;
         }
         case VOID -> {
            min = 2000L;
            max = 5200L;
         }
         case SUNKEN_TEMPLE -> {
            min = 1800L;
            max = 4600L;
         }
         case FROZEN_CRYPT -> {
            min = 1600L;
            max = 4200L;
         }
         case MAGMA_FORGE -> {
            min = 2400L;
            max = 6000L;
         }
         default -> {
            min = 900L;
            max = 2400L;
         }
      }
      if (rich) {
         min *= 2L;
         max *= 2L;
      }
      return min + (long)(RANDOM.nextDouble() * (max - min + 1L));
   }

   /** Called when a mob is killed inside an expedition — gives cash reward. */
   public static void onMobKill(ServerPlayer sp, LivingEntity victim) {
      try {
         State s = active.get(sp.getUUID());
         if (s == null || s.zoneLevel != sp.level()) {
            return;
         }
         long reward = mobKillReward(victim);
         Component name = victim.getCustomName();
         if (name != null && name.getString().contains("Loot Goblin")) {
            // Caught it. The one bounty in the maze worth chasing rather than clearing.
            reward = LOOT_GOBLIN_BOUNTY;
         }
         if (reward <= 0) {
            return;
         }
         reward = (long)(reward * s.type.multiplier * s.anomaly.mobMul);
         s.lootValue += reward;
         s.lootCount++;
         s.mobsSlain++;
         // The kill goes on the action bar, which is what the sidebar freed up: a fight should not
         // fill the chat with arithmetic, and the number that matters is the one flashing in front
         // of the player as the body drops.
         actionBar(sp, "§a+" + Chat.moneyStr(reward) + " §7· " + victim.getName().getString());
         s.zoneLevel.sendParticles(ParticleTypes.HAPPY_VILLAGER, victim.getX(), victim.getY() + 1.0, victim.getZ(), 8, 0.3, 0.3, 0.3, 0.04);
      } catch (Exception ignored) {
      }
   }

   /**
    * What one body of a pack pays. Every kind a roster can produce is priced here, because a kill
    * that falls through to the default is a fight that paid less than the one beside it for no
    * reason the player can see.
    */
   private static long mobKillReward(LivingEntity entity) {
      return killReward(entity.getType());
   }

   /**
    * What one kind of body pays, as a function of the kind alone.
    *
    * <p>Split out from the entity so the table can be read as a table - by the self-test, which
    * pins that killing is the small income of a run and the chests and the chamber bounties are the
    * money, without having to stand a live zombie in front of it.
    */
   public static long killReward(EntityType<?> type) {
      // Roughly a quarter of what these used to pay, and deliberately so: a body in a chamber of
      // depth one is not worth more than the chest standing beside it or the chamber's own bounty,
      // and at the old rates a run was a fight with a bit of looting attached. Killing is the small
      // steady income of a run; the chests, the ores and clearing the chamber are the money.
      if (type == EntityTypes.ZOMBIE || type == EntityTypes.HUSK || type == EntityTypes.DROWNED) return 38;
      if (type == EntityTypes.SKELETON || type == EntityTypes.STRAY) return 45;
      if (type == EntityTypes.CAVE_SPIDER || type == EntityTypes.SPIDER) return 32;
      if (type == EntityTypes.SILVERFISH || type == EntityTypes.ENDERMITE) return 25;
      if (type == EntityTypes.SLIME) return 30;
      if (type == EntityTypes.CREEPER) return 75;
      if (type == EntityTypes.MAGMA_CUBE) return 70;
      if (type == EntityTypes.WITCH) return 90;
      if (type == EntityTypes.BLAZE) return 110;
      if (type == EntityTypes.GHAST) return 90;
      if (type == EntityTypes.VEX) return 85;
      if (type == EntityTypes.PHANTOM) return 80;
      if (type == EntityTypes.GUARDIAN) return 105;
      if (type == EntityTypes.POLAR_BEAR) return 75;
      if (type == EntityTypes.HOGLIN) return 140;
      if (type == EntityTypes.WITHER_SKELETON) return 150;
      if (type == EntityTypes.ZOMBIFIED_PIGLIN) return 80;
      if (type == EntityTypes.VINDICATOR) return 140;
      if (type == EntityTypes.PILLAGER) return 70;
      if (type == EntityTypes.RAVAGER) return 400;
      if (type == EntityTypes.EVOKER) return 200;
      return 20; // Default for any other mob
   }

   public static void tick(MinecraftServer server) {
      if (active.isEmpty()) {
         return;
      }
      long tick = ServerClock.clock(server);
      // Prune expired fatal-blow cooldowns - its own slot in the minute cycle, so it does not
      // share a tick with the mining zones' containment sweep (see MINUTE_OFFSETS).
      if (!fatalCooldown.isEmpty()
         && FortuneFavorsMod.due(tick, FortuneFavorsMod.MINUTE_CYCLE_TICKS, FortuneFavorsMod.MINUTE_EXPEDITION_PRUNE)) {
         fatalCooldown.entrySet().removeIf(e -> tick >= e.getValue());
      }
      // Strays: a pack is a run's property, so one found on a body that is not on a run - or on a
      // body standing in a different run than the one stamped on it - is the residue of a crash, of
      // an exit that never landed, or of a pack that was parked somewhere while its owner walked out
      // and was paid for it. The site takes all of them back, and says so when the body is there to
      // hear it: a pack that can be stashed and spent later is a pack with no run in it at all.
      for (ServerPlayer online : server.getPlayerList().getPlayers()) {
         ItemStack stray = LootBackpack.held(online);
         if (stray.isEmpty()) {
            continue;
         }
         long live = liveRunId(online.getUUID());
         boolean onRun = active.containsKey(online.getUUID());
         if (LootBackpack.belongsTo(stray, live)) {
            continue;
         }
         boolean hadEarlierRun = LootBackpack.runOf(stray) != 0L || !onRun;
         // Only the packs that answer for nothing are taken: a body carrying somebody's stale pack
         // beside its own live one must not lose the live one to the sweep.
         if (LootBackpack.reclaim(online, live) && hadEarlierRun) {
            Chat.raw(
               online,
               "§c§lTHE PACK IS SPENT §r§7- it was handed out by a run that is over, so the site has taken it back. §8A pack is only worth anything while you are carrying it out."
            );
         }
      }
      for (Map.Entry<UUID, State> e : new HashMap<>(active).entrySet()) {
         UUID uuid = e.getKey();
         State s = e.getValue();
         ServerPlayer sp = server.getPlayerList().getPlayer(uuid);
         if (sp == null || !sp.isAlive()) {
            active.remove(uuid);
            if (sp != null) {
               endBoard(sp);
            }
            // Last out closes the site, and it is closed under the name that took the ground -
            // which is the run's holder rather than whoever happened to be the last one standing.
            if (!siteOccupied(s)) {
               releaseSites(s.holder);
               sweepZone(s);
               releasePackMarks(s);
            }
            continue;
         }
         if (s.zoneLevel != sp.level()) {
            // A body mid-teleport is in another level for a tick or two, and a run that read that as
            // an escape ended itself on the way in. So the site waits, and pulls: the explorer is
            // put back on their pad, and only a body that stays gone for a full second is treated as
            // having left on purpose.
            if (s.entrancePad != null && ++s.body(uuid).outOfSiteTicks <= SITE_LEAVE_TOLERANCE_TICKS) {
               teleportPlayer(
                  sp, s.zoneLevel,
                  s.entrancePad.getX() + 0.5, standY(s.entrancePad), s.entrancePad.getZ() + 0.5
               );
               continue;
            }
            finish(sp, s, true);
            continue;
         }
         s.body(uuid).outOfSiteTicks = 0;
         // The permanent upgrades, kept on the body rather than handed over once. See applyUpgrades.
         if (tick % 20L == 0L) {
            applyUpgrades(sp);
         }
         // ...and the site's own venom clock, which has to be read every tick rather than once a
         // second: a poison applied and trimmed inside the same second is a poison nobody notices was
         // ever longer. See trimSitePoison.
         trimSitePoison(sp);
         // The room engine: which chamber am I in, is its pack still standing, is it cleared?
         Room room = enterRoom(s, sp);
         // ...and the chamber closes behind an explorer who commits to it. Past the threshold only,
         // never in a doorway, and only while the pack inside is still standing.
         if (room != null && !room.cleared && !room.sealed && room.visited && deepInside(s, room, sp)) {
            sealRoom(s, room, sp);
         }
         if (s.entrancePad == null) {
            s.entrancePad = new BlockPos(baseX(s, 0) + ROOM_PITCH / 2, floorY(s), baseZ(s, 0) + ROOM_PITCH / 2);
         }
         // Sanctuary chambers: slow regeneration. Also our last line of void defence -
         // nobody should ever be below the dungeon floor.
         if (sp.getY() < floorY(s) - 1.0) {
            pullFromTheAbyss(s, sp);
         } else if (room != null && isRestRoom(room.chamber) && tick % mendInterval(REST_MEND_TICKS) == 0L) {
            // The rest sites, and the point of the halved mend: a chamber that gives nothing back is a
            // chamber you leave, and one that gives a little back slowly is one you stay in and defend.
            if (sp.getHealth() < sp.getMaxHealth()) {
               sp.heal(restMend(room.chamber, sp.getUUID()));
               s.zoneLevel.sendParticles(ParticleTypes.HEART, sp.getX(), sp.getY() + 1.2, sp.getZ(), 3, 0.3, 0.3, 0.3, 0.01);
            }
         }
         // The camps, said out loud once a second while one is within reach: the whole of "make them
         // easier to find" is that a player who is hurt has something better to do than wander.
         if (tick % 20L == 0L && room != null) {
            campBeacon(s, room, sp);
         }
         // The supply crates, made findable from the doorway: a rising column over every one of them
         // within sight, which is the same trick the camps use for the same reason. A crate the
         // explorer walked past is a crate that was not there as far as the run is concerned.
         if (tick % 20L == 0L) {
            crateHalo(s, sp);
         }
         long elapsed = ServerClock.clock(s.zoneLevel) - s.startTick;
         // The clock, said out loud in stages. The last minute must never be the first the player
         // hears about the clock - extraction is a decision made at half time, not at zero.
         double runFrac = s.durationTicks <= 0L ? 1.0 : (double)elapsed / (double)s.durationTicks;
         int stage = runFrac >= 0.9 ? 3 : runFrac >= 0.75 ? 2 : runFrac >= 0.5 ? 1 : 0;
         if (stage > s.timeStage) {
            s.timeStage = stage;
            long leftS = Math.max(0L, (s.durationTicks - elapsed) / 20L);
            switch (stage) {
               case 1 -> Chat.raw(sp, "§e§lHALF THE CLOCK IS GONE. §r§7" + (leftS / 60L) + "m left - the walk back is part of the trip.");
               case 2 -> Chat.raw(sp, "§6§lTHREE QUARTERS GONE. §r§7" + (leftS / 60L) + "m - start thinking about the way out.");
               case 3 -> Chat.raw(sp, "§c§lTHE LAST TENTH. §r§7" + leftS + "s - the halls are waking up behind you.");
               default -> {
               }
            }
            SoundUtil.play(sp, ModSounds.MYSTERY);
         }
         // Critical time: the site starts coming after you, and then it stops being a site and
         // becomes an avalanche. Not a death sentence - a pressure, and one a player who kept their
         // route short can simply walk away from - but the last minutes are meant to be spent
         // running rather than looting.
         if (stage >= 2 && tick % (stage >= 3 ? 160L : 600L) == 0L && s.swarmTick != tick) {
            // Once per site per tick, not once per explorer: a party of four is not four avalanches,
            // it is four people running from one. See State#swarmTick.
            s.swarmTick = tick;
            int swarm = swarmSize(stage, room == null ? 0 : room.depth);
            spawnWave(s, sp.blockPosition(), s.type, swarm, heatOf(s));
            if (stage >= 3) {
               actionBar(sp, "§4§lTHE SITE IS TURNING ON YOU §7· " + swarm + " more are coming");
            }
         }
         if (collapseShouldRun(s.collapsing, elapsed, s.durationTicks)) {
            // The fall caught this explorer and the moment it gave them has run out. Everything
            // above this line still works while the warning is running - including the pads, which
            // is the whole point of it.
            int grace = collapseGracePhase(s.collapseGraceUntil, ServerClock.clock(s.zoneLevel));
            if (grace == GRACE_SPENT) {
               eject(sp, s, "§c§lTHE SITE COMES DOWN", "You were still inside when the fall caught up with you", true);
               continue;
            }
            if (grace == GRACE_RUNNING) {
               long left = s.collapseGraceUntil - ServerClock.clock(s.zoneLevel);
               if (left % 20L == 0L) {
                  actionBar(sp, "§4§l" + (left / 20L) + "s§r §7to reach a pad - the site is still coming down");
               }
            }
            if (!s.collapsing) {
               s.collapsing = true;
               s.collapseOrder.addAll(collapseOrder(s));
               s.nextCollapseTick = ServerClock.clock(s.zoneLevel);
               // One chamber starts shedding per step, and the last one takes a shed of its own on
               // top of that before the site is done with itself.
               s.collapseEndTick = ServerClock.clock(s.zoneLevel)
                  + (long)COLLAPSE_STEP_TICKS * s.collapseOrder.size() + COLLAPSE_SHED_TICKS + 40L;
               // The fall's own numbers: how many chambers there are to take, how many have been
               // named, and the tick its first groan is due. See collapsePressure.
               s.collapseTotal = s.collapseOrder.size();
               s.collapseFelled = 0;
               s.collapseRumbleAt = 0L;
               Chat.raw(sp, "§4§lTHE " + s.type.name().toUpperCase() + " IS COMING DOWN.");
               Chat.raw(sp, "§7Do not stand in it. Chamber by chamber, §foutermost first§7 - and the one you are standing in is somewhere in that line.");
               Chat.raw(sp, "§7A pad still works. Nothing else does.");
               SoundUtil.play(sp, ModSounds.MYSTERY);
               if (s.type == Type.MONSTER_CAVE) {
                  Chat.raw(sp, "&cThe undead are swarming!");
                  spawnWave(s, sp.blockPosition(), s.type, eventSwarm(10), 0);
               }
            }
            if (ServerClock.clock(s.zoneLevel) >= s.nextCollapseTick) {
               s.nextCollapseTick = ServerClock.clock(s.zoneLevel) + COLLAPSE_STEP_TICKS;
               Room doomed = s.collapseOrder.isEmpty() ? null : s.collapseOrder.remove(0);
               if (doomed != null) {
                  // The chamber starts coming down rather than simply being gone: it sheds for a
                  // moment and the roof gives way at the end of it, and THAT is the moment a body
                  // still standing in it is out of the run. See beginShed and shedTick.
                  beginShed(s, doomed, sp);
               } else if (s.shedding.isEmpty() && ServerClock.clock(s.zoneLevel) >= s.collapseEndTick) {
                  // Every chamber is down and this explorer is somehow still in the site: the last
                  // thing the maze does is hold the door it always held - and hold it for the eight
                  // seconds the fall owes anybody it has caught. See beginCollapseGrace.
                  beginCollapseGrace(s, sp, "the last chamber fell");
               }
            }
            // The fall itself, tick by tick, for every chamber currently shedding: rubble lands,
            // the room reads as a room coming down - and when enough of it is in there, the roof
            // gives way and the chamber is gone.
            if (shedTick(s, sp)) {
               continue;
            }
            collapseFx(s, sp);
            collapseRumble(s, sp);
         }
         // The floor guardian's fight, as opposed to his health bar: the four moves he answers the
         // arena with. See guardianTick - and his death, which is noticed here rather than in the
         // death handler because the bounty is paid by the chamber, not by the body. Handed the
         // site's own clock rather than the server's, because that is the clock the arena's timings
         // are written on (the collapse, the escape grace and the guardian's own opening delay).
         guardianTick(s, sp, ServerClock.clock(s.zoneLevel));
         int dist2 = (int)horizDist2(s.center, sp.blockPosition());
         if (dist2 > s.maxDist2) {
            s.maxDist2 = dist2;
         }
         // Clear detection: a chamber whose pack is gone pays its bounty and opens the way
         // onward. Checked a few times a second - cheap, and the last kill should never feel
         // like it lagged behind the payout.
         if (room != null && !room.cleared && tick % 5L == 0L && packDead(s, room)) {
            clearRoom(s, room, sp);
         }
         // Escape: stand on the glowstone entrance pad (or a generated exit pad
         // deeper in the maze) - no depth requirement, it always works.
         // The player must walk off the entrance pad first, then step back on to exit.
         // This prevents the instant-kick bug where spawning on the pad immediately
         // triggers exit on the first tick.
         long padDx = sp.blockPosition().getX() - s.entrancePad.getX();
         long padDz = sp.blockPosition().getZ() - s.entrancePad.getZ();
         boolean onEntrancePad = padDx * padDx + padDz * padDz <= 16;
         if (!onEntrancePad) {
            s.body(uuid).hasLeftEntrance = true;
         }
         boolean atEntrance = onEntrancePad && s.body(uuid).hasLeftEntrance;
         // The deep exit pad: found rather than announced, and therefore stood on rather than
         // brushed past. It is the only pad in the maze that appears where nobody expects one, and
         // treating it as a walk-through is what used to end runs. See EXIT_PAD_HOLD_TICKS.
         boolean onExitPad = false;
         if (s.exitPad != null) {
            long ex = sp.blockPosition().getX() - s.exitPad.getX();
            long ez = sp.blockPosition().getZ() - s.exitPad.getZ();
            long ey = sp.blockPosition().getY() - s.exitPad.getY();
            onExitPad = ex * ex + ez * ez <= 4 && Math.abs(ey) <= 2;
         }
         if (onExitPad && !s.body(uuid).atExitPad) {
            Chat.raw(sp, "\u00a7b\u00a7lA SECOND PAD. \u00a7r\u00a77Stand on it a moment and the site lets you out here - walk off and the run goes on.");
            SoundUtil.play(sp, ModSounds.MYSTERY);
         }
         s.body(uuid).atExitPad = onExitPad;
         s.body(uuid).exitPadHold = onExitPad ? s.body(uuid).exitPadHold + 1 : 0;
         if (!atEntrance && onExitPad && s.body(uuid).exitPadHold >= EXIT_PAD_HOLD_TICKS) {
            atEntrance = true;
         }
         // ...and neither pad answers for the first seconds at a site, because a body arrives ON
         // one by teleport and the tick cannot tell that from walking back to it. See escapeArmedAt.
         // ...and a body on the floor cannot walk out of a site. Extraction is a walk, and the pads
         // answer the explorer who made it rather than the one who had to be carried there.
         if (atEntrance && ServerClock.clock(s.zoneLevel) >= s.escapeArmedAt && !s.body(uuid).downed) {
            active.remove(uuid);
            finish(sp, s, true);
            continue;
         }
         // The party's own business, once per site per tick rather than once per explorer: who is on
         // the floor, and whether anybody is standing over them. See downedTick.
         if (s.downedPass != tick) {
            s.downedPass = tick;
            downedTick(s, tick);
         }
         // The other three are the only landmarks in a site that move, so they are handed the one
         // outline the game draws through walls. It is applied as an effect rather than as the glow
         // tag because the site's own mark sweep owns the tag, and it lapses on its own the moment
         // there is nobody left to see.
         if (tick % 10L == 0L && siteCrew(s) > 1) {
            for (ServerPlayer mate : crew(s)) {
               mate.addEffect(new MobEffectInstance(MobEffects.GLOWING, 25, 0, false, false, false));
            }
         }
         if (tick % 10L == 0L) {
            // The run reports itself on the explorer's own sidebar rather than as one long
            // action-bar line: a list stays on screen, and a value that has not moved costs
            // nothing to keep there. See {@link ExpeditionBoard} for why nobody else can read it.
            ExpeditionBoard.show(sp, boardLines(sp, s, room, elapsed));
         }
         // Random site events every 30-60 seconds keep the run moving.
         if (s.nextEventTick == 0L) {
            s.nextEventTick = elapsed + 20L * (30L + RANDOM.nextInt(31));
         } else if (elapsed >= s.nextEventTick) {
            s.nextEventTick = elapsed + 20L * (30L + RANDOM.nextInt(31));
            fireEvent(s, sp);
         }
         // Wandering trader: spawns every ~3 minutes in a nearby chamber
         if (s.nextTraderTick == 0L) {
            s.nextTraderTick = elapsed + 3600L; // 3 min in
         }
         if (elapsed >= s.nextTraderTick && s.traderId == null) {
            spawnWanderingTrader(s, sp);
            s.nextTraderTick = elapsed + 6000L; // Next one in 5 min
         }
         // Mini-boss: a sentinel hunts once the player is deep in the dungeon
         if (s.nextMiniBossTick == 0L) {
            s.nextMiniBossTick = elapsed + 2400L; // 2 min check interval
         }
         if (elapsed >= s.nextMiniBossTick && s.miniBossId == null) {
            if (room != null && room.depth >= 8) {
               spawnExpeditionMiniBoss(s, sp);
            }
            s.nextMiniBossTick = elapsed + 2400L;
         }
         // Check if mini-boss is dead
         if (s.miniBossId != null) {
            net.minecraft.world.entity.Entity mb = s.zoneLevel.getEntity(s.miniBossId);
            if (mb == null || !mb.isAlive()) {
            // Mini-boss slain — reward
            long bonus = 4000 + (long)(2500 * s.type.multiplier * s.anomaly.mobMul);
               s.lootValue += bonus;
               s.lootCount++;
               if (timeBonusAllowed(s.collapsing)) {
                  s.durationTicks += 20L * 60L;
                  Chat.raw(sp, "§6§lMINI-BOSS SLAIN! §r§7+" + Chat.moneyStr(bonus) + " §7bonus loot and §d+1 minute§7!");
               } else {
                  // ...and a site that is already falling is not a site that can be given another
                  // minute: the clock is what starts a collapse, not what sustains it.
                  Chat.raw(sp, "§6§lMINI-BOSS SLAIN! §r§7+" + Chat.moneyStr(bonus) + " §7bonus loot. §8The site is already coming down.");
               }
               s.zoneLevel.sendParticles(ParticleTypes.HAPPY_VILLAGER, sp.getX(), sp.getY() + 1, sp.getZ(), 20, 0.5, 0.5, 0.5, 0.1);
               s.miniBossId = null;
            }
         }
         // The pack's tell: a dark aura over every living body of the chamber being fought, so
         // nothing in a hall is ever just a shape you did not notice.
         if (room != null && tick % 4L == 0L) {
            packAura(s, room);
         }
         // Whatever the chamber is in the middle of: the rubble, the embers, the water, the dark.
         if (room != null && tick % 20L == 0L) {
            conditionTick(s, room, sp, tick);
            // ...and the alchemy hall's own clock, which is the pack drinking the room's cures while
            // the explorer is standing in it. See covenTick.
            covenTick(s, room, sp, tick);
         }
         // The pack's mark, re-asserted on the bodies that should wear it and taken off everything
         // else in sight. See glowSweep - this is what keeps "some random mob is glowing" from ever
         // being true inside a site.
         if (tick % 20L == 0L) {
            // ...and before the sweep runs, every hostile body standing in the chamber being fought
            // is given the mark. See markRoomBodies for what that is for.
            if (room != null) {
               markRoomBodies(s, room);
            }
            glowSweep(s, sp);
         }
         if (tick % 40L == 0L) {
            ringParticles(s.zoneLevel, sp.blockPosition(), 20);
         }
      }
   }

   /**
    * One line of overlay text: the action bar, above the hotbar.
    *
    * <p>The action bar is the run's *reaction* channel now that the sidebar carries its state -
    * a kill, a find, a thing that just happened. Transient by nature, which is exactly right for a
    * number that only matters at the moment the body drops.
    */
   public static void actionBar(ServerPlayer sp, String text) {
      try {
         sp.sendSystemMessage(Component.literal(text), true);
      } catch (Throwable ignored) {
      }
   }

   /**
    * The lines of one explorer's sidebar, top to bottom.
    *
    * <p>Written as lines rather than as one string because that is the whole reason it moved off
    * the action bar: the clock is the second line of a list that stays where it is, so a glance
    * costs a glance. Everything here is a fact the explorer cannot get any other way while they are
    * underground - where the run is, what is left of it, what it has earned, whether this chamber
    * is sealed, and how far the next guardian is.
    */
   private static List<String> boardLines(ServerPlayer sp, State s, Room room, long elapsed) {
      List<String> lines = new ArrayList<>();
      long left = Math.max(0L, (s.durationTicks - elapsed) / 20L);
      lines.add(s.type.color + "§l" + s.type.name);
      lines.add(
         "§7Time " + (left < 180L ? "§c" : left < 420L ? "§e" : "§f")
            + left / 60L + ":" + String.format("%02d", left % 60L)
      );         lines.add("§7Loot §a" + Chat.moneyStr(s.lootValue));
      if (room != null) {
         // Where you are is the first thing worth knowing and the last thing a player can read off
         // the world: every chamber is built out of the same rock and the only wall that differs is
         // the one you never see. It is also the line that decided how wide the whole panel was - a
         // sidebar is exactly as wide as its longest line, so the eleven characters of "You are in "
         // cost every line on the screen eleven characters of room, and the chamber's own name says
         // the same thing on its own. The condition rides beside it because a flooded hall is a
         // different fight, not a footnote, and every line below is sized to match.
         lines.add(
            room.chamber.colour + room.chamber.name
               + (room.condition == null ? "" : " " + room.condition.colour + room.condition.name)
         );
         lines.add("§7Depth §f" + room.depth + " §8· §7cleared §f" + s.roomsCleared);
         if (!room.cleared) {
            lines.add("§c" + livingCount(s, room) + " left §8· door barred");
         } else {
            lines.add("§aChamber clear");
         }
         lines.add(
            room.chamber == Chamber.FLOOR_BOSS
               ? "§4§lTHE GUARDIAN HOLDS"
               : "§7Guardian in §f" + (10 - room.depth % 10) + "§7 halls"
         );
      }
      ItemStack pack = LootBackpack.held(sp);
      if (!pack.isEmpty()) {
         lines.add(
            "§6Pack §f" + LootBackpack.entries(pack).size() + "§7/" + LootBackpack.capacity(pack)
               + " §8· §a" + Chat.moneyStr(LootBackpack.totalValue(pack))
         );
      }
      lines.add("§7Chest room §f" + Chat.moneyStr(chestRoom(sp.getUUID())));
      int threat = heatOf(s);
      lines.add(
         (threat >= 3 ? "§4" : threat > 0 ? "§c" : "§8") + "Threat " + threat + " §8· "
            + s.anomaly.colour + s.anomaly.name
      );
      if (s.eventLabel != null && elapsed < s.eventLabelUntil) {
         lines.add(s.eventLabel);
      }
      if (siteCrew(s) > 1) {
         lines.add(partyLine(sp, s));
      } else {
         lines.add("§8Compass points at the pad");
      }
      return lines;
   }

   // ------------------------------------------------------------------
   // The room engine: enter, wake, clear, choose a door
   // ------------------------------------------------------------------

   /**
    * The chamber the player is standing in, with the dungeon-crawler transition applied:
    * first entry into a chamber announces it, wakes the pack, and leaves every archway open.
    */
   private static Room enterRoom(State s, ServerPlayer sp) {
      BlockPos p = sp.blockPosition();
      int rx = Math.floorDiv(p.getX() - s.center.getX(), ROOM_PITCH);
      int rz = Math.floorDiv(p.getZ() - s.center.getZ(), ROOM_PITCH);
      // Resolved through roomKeyOf rather than by the cell the body is standing in, because the
      // arena answers for all nine of its cells: stepping onto the rim is stepping into the arena.
      Room room = s.rooms.get(roomKeyOf(s, p));
      if (room == null) {
         // Only reachable by somehow walking out of the maze - carve the cell so it is
         // solid ground rather than a hole in the world.
         room = buildRoom(s, rx, rz, s.body(sp.getUUID()).lastRoom == null ? 0 : s.body(sp.getUUID()).lastRoom.depth + 1, null);
      }
      if (room != s.body(sp.getUUID()).lastRoom) {
         s.body(sp.getUUID()).lastRoom = room;
         if (!room.visited) {
            firstEntry(s, room, sp);
         }
      }
      return room;
   }

   /** First time a player sets foot in a chamber: the announcement, the pack, the way on. */
   private static void firstEntry(State s, Room room, ServerPlayer sp) {
      room.visited = true;
      if (room.depth > s.deepest) {
         s.deepest = room.depth;
      }
      codexSee(room.chamber);
      Chat.raw(sp, room.chamber.colour + "§l" + room.chamber.name + " §r§8· §7depth §f" + room.depth + " §8· §7" + s.type.name);
      campHint(s, room, sp);
      if (room.condition != null) {
         // Said once, on the way in, because the room itself is the argument: a chamber that is
         // flooded, alight, unlit, overgrown or shedding its roof is a different fight, and the one
         // line of text is the difference between reading it and walking into it.
         Chat.raw(sp, "§8The chamber is " + room.condition.colour + "§l" + room.condition.name.toUpperCase() + "§r§8 - " + conditionNote(room.condition));
      }
      if (room.chamber == Chamber.ENTRANCE || isRestRoom(room.chamber)) {
         room.cleared = true;
         if (isRestRoom(room.chamber)) {
            codexClear(room.chamber);
            // A camp's arrival mend is the one heal left whole. Everything it gives after that is
            // halved - see MEND_SCALE - so the camp is what you walk to, not what you live in.
            sp.setHealth(sp.getMaxHealth());
            SoundUtil.play(sp, ModSounds.JOB_COMPLETE);
            switch (room.chamber) {
               case REST_LIBRARY -> {
                  Chat.raw(sp, "§6§lREADER'S REST. §7The shelves stand in rings around a table, and the reading mends you.");
                  Chat.raw(sp, "§7Enchanted books sit in the barrels - §ftake one and press on§7.");
                  sp.addEffect(new net.minecraft.world.effect.MobEffectInstance(MobEffects.REGENERATION, 20 * 10, 0, false, false, true));
               }
               case CALM_CAMP -> {
                  // The one room in the site whose whole job is the mend, so it says so twice: what
                  // the place is, and the two facts a hurt explorer is actually asking for.
                  Chat.raw(sp, "§a§lTHE CALM CAMPPLACE. §7A fire and a glade, and nothing in here that wants you dead.");
                  Chat.raw(sp, "§7Stand a while and it mends you - §ftwice the rate of a shrine's floor§7, and no pack ever wakes here.");
                  sp.addEffect(new net.minecraft.world.effect.MobEffectInstance(MobEffects.REGENERATION, 20 * 14, 0, false, false, true));
               }
               case WAYSTATION -> Chat.raw(sp, "§6§lWAYFARER'S WAYSTATION. §7A caravan stopped where it stands - the fire is still lit and the barrels are still full.");
               default -> Chat.raw(sp, "§d§lA SANCTUARY. §7The doors stand open and the quiet mends you.");
            }
         }
         return;
      }
      if (room.chamber == Chamber.FLOOR_BOSS) {
         wakeFloorGuardian(s, room, sp);
         return;
      }
      if (room.chamber == Chamber.ALCHEMY) {
         // The room's rule, said once on the way in: the cures are in the open and both sides can
         // take them, which is the whole fight. Not a tell - the cauldrons are standing in the middle
         // of the hall - just the one line that makes a hurt player look at them before the pack.
         Chat.raw(sp, "§5§lTHE COVEN IS AT WORK. §7Three cauldrons are still on the boil in the middle of the hall - §fand the witches drink from them too§7.");
      }
      int count = packSize(s, room);
      List<Mob> pack = spawnPack(s, room, count);
      for (Mob m : pack) {
         room.monsters.add(m.getUUID());
      }
      if (room.monsters.isEmpty()) {
         // Nothing woke in here - the room is yours.
         clearRoom(s, room, sp);
         return;
      }
      Chat.raw(sp, "§7Something wakes in here - §f" + room.monsters.size()
         + " §7of them, and the way back stays open.");
      // A chamber that planted something worth touching says so once, on the way in. The maze is
      // deep enough that a verb nobody knows about is a verb that does not exist.
      if (!room.props.isEmpty()) {
         boolean lock = room.runesTotal > 0;
         Chat.raw(sp, lock
            ? "§7The room is holding something back - §f" + room.runesTotal + " rune stones §7are set into the walls."
            : "§7Some of what is in here answers to a §fright-click§7.");
      }
      SoundUtil.play(sp, ModSounds.MYSTERY);
   }

   /**
    * True when a chamber's ledger entry is a body that is no longer part of the fight.
    *
    * <p>{@code isAlive()} alone is not enough, and the gap is exactly the creeper. A creeper that
    * detonates writes its own {@code dead} flag the moment it goes off - before the blast, the
    * lingering cloud and the final {@code discard()} are all done - so a body whose discard never
    * lands is left standing with a full health bar and {@code isAlive() == true} while the game
    * itself already calls it dead. The chamber then holds a body on its roll call that no player
    * can ever kill, and its doors never open: the "I cleared the room and it never opened" bug
    * wearing a creeper's face. {@link net.minecraft.world.entity.LivingEntity#isDeadOrDying()}
    * reads the same flag vanilla reads, so an exploded creeper is gone the instant it goes off
    * rather than the instant its discard happens to land. This is the predicate the rest of the
    * codebase already uses for "a body that is done" - see {@code BossManager}'s hand lookup.
    */
   private static boolean packBodyGone(net.minecraft.world.entity.Entity m) {
      return m == null
         || !m.isAlive()
         || m instanceof net.minecraft.world.entity.LivingEntity le && le.isDeadOrDying();
   }

   /**
    * The chamber's own answer to "is this body still part of the fight", exposed so the self-test
    * can plant the one case the ledger must not forget: a body that has set its {@code dead} flag
    * but is not yet removed from the world, which is exactly the state an exploding creeper passes
    * through. This is only {@link #packBodyGone} turned around, so the test cannot drift from the
    * predicate it is pinning.
    */
   public static boolean packBodyCountsForTest(net.minecraft.world.entity.Entity body) {
      return !packBodyGone(body);
   }

   /** Every tracked monster of this chamber is gone - the pack is beaten. */
   private static boolean packDead(State s, Room room) {
      for (UUID id : room.monsters) {
         net.minecraft.world.entity.Entity m = s.zoneLevel.getEntity(id);
         if (!packBodyGone(m)) {
            return false;
         }
      }
      return true;
   }

   private static int livingCount(State s, Room room) {
      int n = 0;
      for (UUID id : room.monsters) {
         net.minecraft.world.entity.Entity m = s.zoneLevel.getEntity(id);
         if (!packBodyGone(m)) {
            n++;
         }
      }
      return n;
   }

   /**
    * The room is beaten: the bounty is paid, and up to THREE doors groan
    * open onto chambers nobody has walked yet. That choice of three is the whole loop -
    * you picked this room, now pick the next one.
    */
   private static void clearRoom(State s, Room room, ServerPlayer sp) {
      room.cleared = true;
      // The chamber has no more use for its lock - and an open door is what makes the next choice
      // a choice at all.
      unsealRoom(s, room);
      codexClear(room.chamber);
      boolean guardian = room.chamber == Chamber.FLOOR_BOSS;
      long bonus = guardian ? guardianBounty(s, room) : chamberBounty(s, room);
      s.lootValue += bonus;
      s.lootCount++;
      s.roomsCleared++;
      // The Field Kit's clock answer: clearing ground hands back a quarter-minute of the time it
      // took to clear. Gated on the same rule the guardian's bonus uses, so a collapsing site does
      // not become a well of time, and read off the explorer who actually cleared the room - the
      // run's clock is the site's, and the only body the run can be sure of is the one standing in
      // the cleared chamber.
      if (!guardian && timeBonusAllowed(s.collapsing)) {
         long rebate = ExpeditionProgression.chamberRebateTicks(sp.getUUID());
         if (rebate > 0L) {
            s.durationTicks += rebate;
         }
      }
      if (guardian) {
         s.guardiansFelled++;
         codexGuardians++;
      }
      // The arena's way on is on the rim rather than in its middle: its middle has no wall to cut
      // an archway through, and the eight cells around it are the only part of the room with an
      // outside. See openArenaExits.
      Arena arena = arenaOf(s, room);
      int opened = arena != null ? openArenaExits(s, arena) : openExits(s, room);
      if (opened == 0 && arena != null) {
         opened = openExits(s, room);
      }
      SoundUtil.play(sp, ModSounds.JOB_COMPLETE);
      s.zoneLevel.sendParticles(ParticleTypes.FIREWORK, sp.getX(), sp.getY() + 1.2, sp.getZ(), 25, 0.6, 0.5, 0.6, 0.05);
      // The after-fight breath every dungeon crawler gives you: a cleared chamber mends
      // a little, and a fallen guardian mends everything.
      if (sp.getHealth() < sp.getMaxHealth()) {
         sp.heal(guardian ? sp.getMaxHealth() : mendAmount(4.0F, sp.getUUID()));
      }
      if (guardian) {
         dropDescentLadder(s, sp);
         // The boss buys time. Depth is only worth chasing if beating the thing guarding it buys
         // the clock back - otherwise every deep push is a walk home you cannot afford.
         if (timeBonusAllowed(s.collapsing)) {
            s.durationTicks += 20L * 180L;
            Chat.raw(sp, "§d§lTHE CLOCK MOVES: §r§7+3 minutes on this site for the guardian's fall.");
         }
         Chat.raw(sp, "§4§lTHE FLOOR GUARDIAN FALLS! §r§7Its bounty is §a" + Chat.moneyStr(bonus) + "§7.");
         Chat.raw(sp, "§6§lA DESCENT LADDER clatters to the floor. §fRight-click it §7to skip the next five chambers.");
         s.zoneLevel.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, sp.getX(), sp.getY() + 1.0, sp.getZ(), 40, 1.0, 1.0, 1.0, 0.05);
      }
      if (room.chamber == Chamber.SHRINE) {
         sp.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.REGENERATION, 20 * 20, 1, false, false, true));
         sp.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.RESISTANCE, 60 * 20, 0, false, false, true));
         Chat.raw(sp, "§5§lTHE SHRINE ANSWERS. §7Its blessing settles over you - and its offering is yours.");
      }
      if (opened >= 3) {
         Chat.raw(sp, "§a§lCHAMBER CLEAR! §r§7Three doors groan open - §fchoose your path§7. Bounty: §a" + Chat.moneyStr(bonus));
      } else {
         Chat.raw(sp, "§a§lCHAMBER CLEAR! §r§7The doors groan open. Bounty: §a" + Chat.moneyStr(bonus));
      }
   }

   /**
    * The bounty a cleared chamber pays. Depth is the point: the maze is infinite, so
    * going deeper is always worth more than going home.
    */
   private static long chamberBounty(State s, Room room) {
      double roomMul = switch (room.chamber) {
         case TREASURE_VAULT, GRAND_HALL, LIBRARY, GALLERY, WAGER_VAULT, GILDED_VAULT, OBSERVATORY -> 1.6;
         case ARENA, MOB_DEN, WEB_NEST, TRAP_FLOOR, OUBLIETTE, FORGE_HALL, BROKEN_CROSSING, MENAGERIE, OSSUARY,
              CLOCKWORKS, ARMOURY, ALCHEMY -> 1.3;
         case SHRINE, SANCTUARY, REST_LIBRARY, WAYSTATION, CALM_CAMP, LARDER, GARDEN, BATHHOUSE, FOUNTAIN -> 0.6;
         default -> 1.0;
      };
      long base = 600L + 300L * room.depth;
      return Math.min(30_000L, (long)(base * roomMul * s.type.multiplier));
   }

   /** The floor guardian's bounty - huge by design: it is the dungeon's boss gate. */
   private static long guardianBounty(State s, Room room) {
      return (long)((25_000L + 2_000L * room.depth) * s.type.multiplier);
   }

   /** Opens up to three fresh archways from a cleared chamber onto chambers unknown. */
   private static int openExits(State s, Room room) {
      List<Integer> free = new ArrayList<>();
      for (int d = 0; d < 4; d++) {
         if ((room.doors >> d & 1) == 0) {
            free.add(d);
         }
      }
      Collections.shuffle(free, RANDOM);
      int want = Math.min(3, free.size());
      for (int i = 0; i < want; i++) {
         connect(s, room, free.get(i));
      }
      return want;
   }

   /** Joins two chambers through a new archway, carving the neighbour if it is new. */
   private static void connect(State s, Room room, int dir) {
      int nrx = room.rx + DIR_X[dir];
      int nrz = room.rz + DIR_Z[dir];
      room.doors |= 1 << dir;
      Room nb = s.rooms.get(roomKey(nrx, nrz));
      if (nb == null) {
         nb = buildRoom(s, nrx, nrz, room.depth + 1, null);
      }
      nb.doors |= 1 << OPPOSITE[dir];
      carveArchway(s, room, dir);
   }

   /**
    * True when this cell stands in the walk lane of one of the room's open archways - the mouth
    * itself and {@link #DOOR_LANE} blocks in behind it, on either side of the wall.
    *
    * <p>Chamber builders dress floors and wall faces without knowing where the doors ended up, and
    * one block left in a mouth is a doorway the player can no longer walk through. Everything that
    * dresses a hall asks here first, and {@link #carveArchway} sweeps the lanes afterwards as
    * well, because a chamber is built before its archways are cut and cannot know about a door the
    * player has not opened yet.
    */
   private static boolean inDoorLane(Room room, int lx, int lz) {
      int last = ROOM_PITCH - WALL - 1;
      boolean alongX = lx >= DOOR_LO && lx < DOOR_LO + DOOR_W;
      boolean alongZ = lz >= DOOR_LO && lz < DOOR_LO + DOOR_W;
      if (alongX && (room.doors & 1 << NORTH) != 0 && lz >= WALL && lz < WALL + DOOR_LANE) {
         return true;
      }
      if (alongX && (room.doors & 1 << SOUTH) != 0 && lz <= last && lz > last - DOOR_LANE) {
         return true;
      }
      if (alongZ && (room.doors & 1 << WEST) != 0 && lx >= WALL && lx < WALL + DOOR_LANE) {
         return true;
      }
      return alongZ && (room.doors & 1 << EAST) != 0 && lx <= last && lx > last - DOOR_LANE;
   }

   /** How far into a chamber an explorer must be before its archways will bar behind them. */
   private static final int SEAL_DEPTH = WALL + DOOR_LANE + 1;

   /**
    * Whether the explorer is committed to a chamber rather than standing in one of its doorways.
    *
    * <p>The seal is the one part of the maze that can take freedom away, so it is the one part that
    * has to be careful: a player who is still in a doorway, or walking the inside of a wall, has not
    * chosen anything and must not be shut in. Which is also why a chamber can be crossed without
    * ever sealing - hugging the wall to the next door is a legitimate way through, and a room you
    * can walk past is a room you can scout.
    */
   private static boolean deepInside(State s, Room room, ServerPlayer sp) {
      int lx = (int)Math.floor(sp.getX()) - baseX(s, room.rx);
      int lz = (int)Math.floor(sp.getZ()) - baseZ(s, room.rz);
      int hi = ROOM_PITCH - WALL - DOOR_LANE - 1;
      return lx >= SEAL_DEPTH && lx <= hi && lz >= SEAL_DEPTH && lz <= hi;
   }

   /**
    * Bars the chamber the explorer has just committed to.
    *
    * <p>Walking into a chamber used to mean nothing: the pack woke, you could fight it or walk
    * straight back out through the door you came in by, and a maze whose rooms are optional is a
    * maze you sprint through. So the doors close - but only ever behind a player who is properly
    * inside, never across a threshold they are standing in. The bars go in the wall band of the
    * chamber's own half of each archway, they are recorded on the chamber so it can take them down
    * itself, and they cannot be mined while they hold.
    *
    * <p>Any of the pack that has wandered out of the room is dragged back in as the doors shut.
    * The alternative is a pack member standing in a corridor the explorer is now barred from, which
    * would leave the chamber unclearable and the bars up for the rest of the run.
    */
   private static void sealRoom(State s, Room room, ServerPlayer sp) {
      if (room.sealed || room.cleared) {
         return;
      }
      // The arena is the one room whose doors are not on it. Its middle has no wall to hold an
      // archway, so they are held by the rim cells around it and barred there. See sealArena.
      Arena arenaBlock = arenaOf(s, room);
      if (arenaBlock != null) {
         sealArena(s, arenaBlock, sp);
         return;
      }
      ServerLevel level = s.zoneLevel;
      int bx = baseX(s, room.rx);
      int bz = baseZ(s, room.rz);
      int fy = floorY(s);
      BlockState bars = Blocks.IRON_BARS.defaultBlockState();
      sealBars(s, room, room, fy, bars);
      if (room.bars.isEmpty()) {
         return;
      }
      room.sealed = true;
      pullInStrays(s, room, bx, bz, fy, ROOM_PITCH);
      SoundUtil.play(sp, net.minecraft.sounds.SoundEvents.IRON_DOOR_CLOSE);
      level.sendParticles(
         ParticleTypes.SMOKE, sp.getX(), sp.getY() + 1.0, sp.getZ(), 12, 0.5, 0.4, 0.5, 0.02
      );
      actionBar(sp, "§c§lTHE DOORS GRIND SHUT §7· " + livingCount(s, room) + " left in here");
   }

   /**
    * Brings any pack member that has slipped out of the chamber back into its fight.
    *
    * <p>The bounds are the room's own, which for the arena is the whole three-by-three block: a
    * guardian dragged back to the middle of the pit from the rim is back in the fight, and one
    * pulled three blocks sideways out of a cell would be dragged into the middle of the room he was
    * already standing in.
    */
   private static void pullInStrays(State s, Room room, int bx, int bz, int fy, int span) {
      int c = span / 2;
      for (UUID id : room.monsters) {
         net.minecraft.world.entity.Entity m = s.zoneLevel.getEntity(id);
         if (packBodyGone(m)) {
            continue;
         }
         int lx = m.blockPosition().getX() - bx;
         int lz = m.blockPosition().getZ() - bz;
         boolean inside = lx >= WALL && lx < span - WALL && lz >= WALL && lz < span - WALL;
         if (!inside) {
            m.teleportTo(bx + c + 0.5, standY(new BlockPos(bx + c, fy, bz + c)), bz + c + 0.5);
         }
      }
   }

   /**
    * Writes one room's own half of every open archway full of bars, recorded against {@code owner}.
    *
    * <p>The room and the owner are the same thing everywhere except in the arena, where the doors
    * belong to the rim cells and the bars belong to the room the explorer is locked into.
    */
   private static void sealBars(State s, Room room, Room owner, int fy, BlockState bars) {
      ServerLevel level = s.zoneLevel;
      int bx = baseX(s, room.rx);
      int bz = baseZ(s, room.rz);
      int first = 0;
      int last = ROOM_PITCH - 1;
      int nearEnd = ROOM_PITCH - WALL;
      for (int w = 0; w < DOOR_W; w++) {
         int lo = DOOR_LO + w;
         for (int y = fy + 1; y <= fy + DOOR_H; y++) {
            for (int d = 0; d < 4; d++) {
               if ((room.doors >> d & 1) == 0) {
                  continue;
               }
               List<BlockPos> cells = new ArrayList<>();
               switch (d) {
                  case NORTH -> {
                     for (int lz = first; lz < WALL; lz++) {
                        cells.add(new BlockPos(bx + lo, y, bz + lz));
                     }
                  }
                  case SOUTH -> {
                     for (int lz = nearEnd; lz <= last; lz++) {
                        cells.add(new BlockPos(bx + lo, y, bz + lz));
                     }
                  }
                  case WEST -> {
                     for (int lx = first; lx < WALL; lx++) {
                        cells.add(new BlockPos(bx + lx, y, bz + lo));
                     }
                  }
                  default -> {
                     for (int lx = nearEnd; lx <= last; lx++) {
                        cells.add(new BlockPos(bx + lx, y, bz + lo));
                     }
                  }
               }
               for (BlockPos cell : cells) {
                  if (level.getBlockState(cell).isAir()) {
                     level.setBlock(cell, bars, 3);
                     owner.bars.add(cell);
                  }
               }
            }
         }
      }
   }

   /** Takes a chamber's bars back down - the pack it was holding is gone. */
   private static void unsealRoom(State s, Room room) {
      if (!room.sealed) {
         return;
      }
      room.sealed = false;
      if (room.bars.isEmpty()) {
         return;
      }
      ServerLevel level = s.zoneLevel;
      for (BlockPos cell : room.bars) {
         setIfChanged(level, cell, Blocks.AIR.defaultBlockState());
      }
      room.bars.clear();
   }

   /**
    * True for a bar that is holding a chamber shut right now.
    *
    * <p>The lock is the point, so the lock is enforced: an explorer with a pickaxe and a good
    * reason cannot take the bars down, and the call site says why. The bars are gone the moment the
    * chamber is clear, so what this refuses is never a wall the run needs - it is the fight the
    * explorer walked into.
    */
   public static boolean isSealedDoorBar(ServerPlayer sp, BlockPos pos) {
      State s = active.get(sp.getUUID());
      if (s == null || pos == null || s.zoneLevel != sp.level()) {
         return false;
      }
      Room room = s.rooms.get(roomKeyOf(s, pos));
      return room != null && room.sealed && room.bars.contains(pos);
   }

   /** Whether the cell-local block sits in the doorway band of the given direction. */
   private static boolean inDoorBand(int dir, int lx, int lz) {
      return switch (dir) {
         case NORTH -> lz < WALL && lx >= DOOR_LO && lx < DOOR_LO + DOOR_W;
         case SOUTH -> lz >= ROOM_PITCH - WALL && lx >= DOOR_LO && lx < DOOR_LO + DOOR_W;
         case WEST -> lx < WALL && lz >= DOOR_LO && lz < DOOR_LO + DOOR_W;
         default -> lx >= ROOM_PITCH - WALL && lz >= DOOR_LO && lz < DOOR_LO + DOOR_W;
      };
   }

   /**
    * Cuts one archway through the four-block wall band between two chambers - both cells' halves
    * of it - and sweeps the walk lane on either side of it clear.
    *
    * <p>An archway is carved open, and stays open: what closes a chamber is {@link #sealRoom}, and
    * it closes it from the inside, on the way in, and only once the explorer is past the threshold.
    * The maze used to throw iron bars across every mouth of an uncleared chamber as it was built,
    * and because a barred archway barred its neighbour too, walking into a pack room barred the
    * doorway you had just come through: the chamber you were standing in stopped being a room with
    * three ways on and became a box with one pack in it. The bars are back, but as a decision rather
    * than as scenery - they go up behind you when you commit to the fight, and they come down the
    * moment the pack does.
    *
    * <p>The lane sweep is what makes the door real. A chamber is carved before its archways are,
    * so its builders cannot know which of its four faces will open - the lintel, the baseboard and
    * the ore studs go down as if the whole wall were solid. Once the archway exists, anything
    * standing in the mouth or the three blocks behind it goes, except chests, spawners and the
    * bars of a mob-den cage, which are loot and structure respectively.
    */
   private static void carveArchway(State s, Room room, int dir) {
      ServerLevel level = s.zoneLevel;
      BlockState air = Blocks.AIR.defaultBlockState();
      BlockState trim = trimBlockFor(s.type);
      int bx = baseX(s, room.rx);
      int bz = baseZ(s, room.rz);
      int fy = floorY(s);
      for (int w = 0; w < DOOR_W; w++) {
         int la = DOOR_LO + w;
         for (int i = 0; i < WALL * 2 + DOOR_LANE * 2; i++) {
            boolean band = i >= DOOR_LANE && i < DOOR_LANE + WALL * 2;
            int lx;
            int lz;
            switch (dir) {
               case NORTH -> {
                  lx = la;
                  lz = -WALL - DOOR_LANE + i;
               }
               case SOUTH -> {
                  lx = la;
                  lz = ROOM_PITCH - WALL - DOOR_LANE + i;
               }
               case WEST -> {
                  lx = -WALL - DOOR_LANE + i;
                  lz = la;
               }
               default -> {
                  lx = ROOM_PITCH - WALL - DOOR_LANE + i;
                  lz = la;
               }
            }
            for (int y = fy + 1; y <= fy + DOOR_H + (band ? 1 : 0); y++) {
               BlockPos at = new BlockPos(bx + lx, y, bz + lz);
               if (band && y == fy + DOOR_H + 1) {
                  // The dressed lintel over the mouth - the one bit of the band that stays stone.
                  setIfChanged(level, at, trim);
                  continue;
               }
               BlockState here = level.getBlockState(at);
               if (here.is(Blocks.CHEST) || here.is(Blocks.SPAWNER) || here.is(Blocks.IRON_BARS)) {
                  continue;
               }
               setIfChanged(level, at, air);
            }
         }
      }
   }

   /** Picks the face of a chamber: what it looks like and what lives in it. */
   private static Chamber rollChamber(Type type, int depth) {
      int total = 0;
      for (Chamber c : Chamber.values()) {
         total += chamberWeight(type, c, depth);
      }
      if (total <= 0) {
         return Chamber.PILLAR_HALL;
      }
      int r = RANDOM.nextInt(total);
      for (Chamber c : Chamber.values()) {
         r -= chamberWeight(type, c, depth);
         if (r < 0) {
            return c;
         }
      }
      return Chamber.PILLAR_HALL;
   }

   /**
    * How likely one face is in one dungeon at one depth. Zero means it cannot appear at all.
    *
    * <p>Thirty faces do not fit in a hundred-wide ladder of three-point bands, and a ladder is the
    * wrong instrument for a repertoire anyway: a dungeon's character and the run's depth should bend
    * the odds rather than slide a cutoff along. So every face carries a weight, the depth gates the
    * rooms that are worth the walk down, the dungeon's own taste multiplies what is left, and one
    * roll picks out of the remainder.
    *
    * <p>The taste multiplier is deliberately allowed to reach zero: a crystal cavern has no web
    * nests and a crypt has no sunken garden, and a face a dungeon can never roll is a face that
    * makes the dungeon where it does roll feel like somewhere rather than like anywhere.
    */
   public static int chamberWeight(Type type, Chamber c, int depth) {
      int base = switch (c) {
         case ENTRANCE, FLOOR_BOSS -> 0;
         case ARENA, GRAND_HALL, PILLAR_HALL, MOB_DEN, CACHE -> 8;
         // The camps: still heavy enough that "find a camp" is a plan a run can make, and no longer
         // so heavy that the maze is mostly camps - at eighteen apiece the three of them were two
         // chambers in nine and the maze stopped being a maze. The calm camp is the heaviest of the
         // four on purpose: it is the one with nothing in it but the mend.
         case SANCTUARY, REST_LIBRARY, WAYSTATION -> 9;
         case CALM_CAMP -> 12;
         case ALCHEMY -> 9;
         case MOONLIT_CHAPEL -> 5;
         case TREASURE_VAULT, TRAP_FLOOR, ORE_VAULT, DROWNED_HALL, WEB_NEST -> 10;
         case SHRINE, LIBRARY, ARMOURY, OUBLIETTE, LARDER, GARDEN, FUNGAL_GROTTO, FOUNTAIN -> 5;
         case FORGE_HALL, BROKEN_CROSSING, GALLERY, WAGER_VAULT, MENAGERIE, BATHHOUSE -> 4;
         case OBSERVATORY, CLOCKWORKS, OSSUARY -> 3;
         case GILDED_VAULT -> 2;
      };
      if (base == 0) {
         return 0;
      }
      // Depth unlocks the rooms worth their fight. A gilded vault on the way in would be a run
      // that pays for itself in its first minute.
      int gate = switch (c) {
         case GILDED_VAULT -> 8;
         case OBSERVATORY, CLOCKWORKS, OSSUARY -> 5;
         case WAGER_VAULT, MENAGERIE, BATHHOUSE, GALLERY, ARMOURY, OUBLIETTE -> 3;
         // The reading room stays gated by one chamber, deliberately: the sanctuary and the
         // waystation are the camps a shallow run is handed, and the library is the camp a run has
         // to have gone somewhere to find. The calm camp is the floor-level one - it is the heal the
         // whole system is built around, so a run meets it from the first chamber past the threshold
         // on. See expedition.mending-is-halved-and-the-camps-are-common.
         case CALM_CAMP -> 1;
         case REST_LIBRARY -> 2;
         case ALCHEMY -> 2;
         case MOONLIT_CHAPEL -> 3;
         default -> 0;
      };
      if (depth < gate) {
         return 0;
      }
      int taste = switch (type) {
         case MONSTER_CAVE -> switch (c) {
            case ARENA, MOB_DEN, WEB_NEST, OUBLIETTE, TRAP_FLOOR, LARDER, OSSUARY, BROKEN_CROSSING, MENAGERIE -> 3;
            case LIBRARY, GALLERY, OBSERVATORY, BATHHOUSE, SANCTUARY, FOUNTAIN, GARDEN, CLOCKWORKS -> 0;
            default -> 1;
         };
         case MAGMA_FORGE -> switch (c) {
            case FORGE_HALL, CLOCKWORKS, OSSUARY, TRAP_FLOOR, BROKEN_CROSSING -> 3;
            case GARDEN, FUNGAL_GROTTO, WEB_NEST, LIBRARY, OUBLIETTE -> 0;
            default -> 1;
         };
         case CRYSTAL_CAVERN -> switch (c) {
            case OBSERVATORY, GALLERY, GILDED_VAULT, ORE_VAULT, CLOCKWORKS, ALCHEMY -> 3;
            case WEB_NEST, FUNGAL_GROTTO, BROKEN_CROSSING, MENAGERIE, LARDER, OUBLIETTE -> 0;
            default -> 1;
         };
         case DEEP_MINE -> switch (c) {
            case ORE_VAULT, CLOCKWORKS, BROKEN_CROSSING, ARMOURY, TRAP_FLOOR -> 3;
            case GARDEN, BATHHOUSE, OBSERVATORY, MENAGERIE, FUNGAL_GROTTO -> 0;
            default -> 1;
         };
         case SUNKEN_TEMPLE -> switch (c) {
            case DROWNED_HALL, FOUNTAIN, BATHHOUSE, SANCTUARY, LIBRARY, LARDER, ALCHEMY -> 3;
            case FORGE_HALL, OSSUARY, CLOCKWORKS, WEB_NEST, MENAGERIE -> 0;
            default -> 1;
         };
         case VOID -> switch (c) {
            case OBSERVATORY, OSSUARY, BROKEN_CROSSING, CLOCKWORKS, GILDED_VAULT -> 3;
            case GARDEN, LARDER, BATHHOUSE, FOUNTAIN, MENAGERIE -> 0;
            default -> 1;
         };
         default -> switch (c) {
            case OSSUARY, LARDER, BATHHOUSE, CLOCKWORKS, ARMOURY -> 3;
            case GARDEN, FUNGAL_GROTTO, WEB_NEST, FOUNTAIN, DROWNED_HALL, MENAGERIE -> 0;
            default -> 1;
         };
      };
      int weight = base * taste;
      if (weight <= 0) {
         return 0;
      }
      // A deep run is mostly its dangerous rooms: the quiet places thin out the further down the
      // stairs go, which is what makes depth feel like pressure rather than like more of the same.
      // ...and the calm camp is deliberately NOT on this list. Every other quiet room thins out as
      // the stairs go down, which is what makes depth feel like pressure - but the room that is only
      // ever a mend is the one a deep run has the most reason to find, so the camp that survives the
      // thinning is the camp that has nothing in it but the healing.
      if (depth >= 10
         && (c == Chamber.SANCTUARY || c == Chamber.REST_LIBRARY || c == Chamber.WAYSTATION
            || c == Chamber.CACHE || c == Chamber.LARDER || c == Chamber.FOUNTAIN)) {
         weight = Math.max(1, weight / 2);
      }
      if (depth >= 16 && (c == Chamber.GILDED_VAULT || c == Chamber.WAGER_VAULT || c == Chamber.OBSERVATORY)) {
         weight *= 2;
      }
      return weight;
   }

   /**
    * Extra bodies an alchemy hall wakes with, over everything else the depth asks for.
    *
    * <p>The stillroom is the site's biggest pack on purpose: it is the price of the cures standing
    * in its cauldrons, and a room that hands out health for nothing is a room every run beelines for.
    */
   public static final int ALCHEMY_PACK_BONUS = 3;
   /**
    * Witches out of every four bodies in that pack - three, with an evoker as the fourth.
    *
    * <p>"A lot of witches" is the request and the room: the roster is one kind deliberately, and the
    * evoker that turns up now and then is what keeps it from being literally one body ten times.
    */
   public static final int ALCHEMY_WITCHES_IN_FOUR = 3;

   /** How big a pack a chamber wakes with - deeper is always more. */
   private static int packSize(State s, Room room) {
      int count = 3 + room.depth / 2 + RANDOM.nextInt(3);
      switch (room.chamber) {
         case ARENA, MOB_DEN -> count += 2;
         case WEB_NEST -> count += 3;
         case MENAGERIE -> count += 3;
         case OSSUARY -> count += 2;
         case SHRINE -> count = Math.max(1, count / 2);
         // The biggest pack in the site: a stillroom full of witches, which is what the cures in its
         // cauldrons are the price of.
         case ALCHEMY -> count += ALCHEMY_PACK_BONUS;
         case FOUNTAIN, CACHE, TREASURE_VAULT, LIBRARY, REST_LIBRARY, LARDER, GARDEN, GALLERY, WAGER_VAULT,
              GILDED_VAULT, OBSERVATORY, BATHHOUSE, WAYSTATION, MOONLIT_CHAPEL, CALM_CAMP -> count -= 2;
         case FORGE_HALL, OUBLIETTE -> count += 2;
         case CLOCKWORKS -> count += 1;
         default -> {
         }
      }
      if (s.type == Type.MONSTER_CAVE || s.type == Type.MAGMA_FORGE) {
         count += 2;
      }
      return Math.max(1, Math.min(12, count));
   }

   // ------------------------------------------------------------------
   // Chamber construction
   // ------------------------------------------------------------------

   /**
    * Carves one giant chamber: shell first (bedrock underfoot, floor, roof, four-block
    * wall bands with the archways this room already owns), then its face, then the ore
    * studs in the floor. Every block is written once - the maze is carved, not filled
    * and then carved again.
    */
   private static Room buildRoom(State s, int rx, int rz, int depth, Chamber forced) {
      // Every tenth chamber of depth is the floor guardian's arena: the dungeon's boss
      // gate, whose fall is what pays for a ladder to skip the next five chambers.
      Chamber face = forced != null
         ? forced
         : (depth >= 10 && depth % 10 == 0 ? Chamber.FLOOR_BOSS : rollChamber(s.type, depth));
      // The guardian gets the room he actually needs: three cells across, with the eight around them
      // reserved so the maze cannot grow into the middle of his arena. See buildWideArena - and the
      // cell he was rolled in is the whole of it when the block around him is already taken.
      if (face == Chamber.FLOOR_BOSS) {
         Room wide = buildWideArena(s, rx, rz, depth);
         if (wide != null) {
            return wide;
         }
      }
      Room room = new Room(rx, rz, depth, face);
      s.rooms.put(roomKey(rx, rz), room);

      ServerLevel level = s.zoneLevel;
      int bx = baseX(s, rx);
      int bz = baseZ(s, rz);
      int fy = floorY(s);
      int top = fy + ROOM_HEIGHT;
      BlockState wall = wallBlockFor(s.type);
      BlockState floor = floorBlockFor(s.type);
      BlockState ceil = ceilingBlockFor(s.type);
      BlockState trim = trimBlockFor(s.type);
      BlockState bedrock = Blocks.BEDROCK.defaultBlockState();
      BlockState air = Blocks.AIR.defaultBlockState();

      int first = WALL;
      int last = ROOM_PITCH - WALL - 1;
      for (int lx = 0; lx < ROOM_PITCH; lx++) {
         for (int lz = 0; lz < ROOM_PITCH; lz++) {
            boolean band = lx < WALL || lx >= ROOM_PITCH - WALL || lz < WALL || lz >= ROOM_PITCH - WALL;
            // The four interior corners become full-height dressed columns.
            boolean column = (lx == first || lx == last) && (lz == first || lz == last);
            // A pilaster every COFFER paces along each wall face, on the same grid the ceiling
            // beams use, so the vertical dress stands under the horizontal dress.
            boolean pilaster = ((lx == first || lx == last) && (lz - WALL) % COFFER == 0)
               || ((lz == first || lz == last) && (lx - WALL) % COFFER == 0);
            // The coffered ceiling: beams cross it every COFFER paces and a lantern hangs in the
            // middle of every panel they cut. Sixteen lanterns spread over a twenty-four-wide
            // hall, rather than one two-by-two cluster over the middle of it - the cluster lit
            // the dais and left every corner of the chamber in the dark, which is most of why a
            // hall this size read as a cave instead of as a room.
            boolean beam = (lx - WALL) % COFFER == 0 || (lz - WALL) % COFFER == 0;
            boolean lantern = (lx - WALL) % COFFER == COFFER / 2 && (lz - WALL) % COFFER == COFFER / 2;
            for (int y = fy - 1; y <= top; y++) {
               BlockState want;
               if (y == fy - 1) {
                  want = bedrock;
               } else if (y == fy) {
                  want = floor;
               } else if (y == top) {
                  want = lantern ? lightBlockFor(s.type) : beam ? trim : ceil;
               } else if (band) {
                  want = wall;
                  // Archway mouths, and their dressed lintels one block above the doorway.
                  for (int d = 0; d < 4; d++) {
                     if ((room.doors >> d & 1) == 0 || !inDoorBand(d, lx, lz)) {
                        continue;
                     }
                     if (y <= fy + DOOR_H) {
                        want = air;
                     } else if (y == fy + DOOR_H + 1) {
                        want = trim;
                     }
                  }
               } else {
                  // Wall dress: a baseboard at the floor, a cornice under the ceiling, and a lamp
                  // four blocks up every column and pilaster. The archways' walk lanes stay clear
                  // so no doorway is ever furnished shut.
                  boolean wallFace = lx == first || lx == last || lz == first || lz == last;
                  boolean dressed = wallFace && !inDoorLane(room, lx, lz);
                  if (dressed && (column || pilaster)) {
                     want = y == fy + 4 ? lightBlockFor(s.type) : trim;
                  } else if (dressed && (y == fy + 1 || y == top - 1)) {
                     want = trim;
                  } else {
                     want = air;
                  }
               }
               setIfChanged(level, new BlockPos(bx + lx, y, bz + lz), want);
            }
         }
      }

      buildChamber(s, room, bx, bz, fy, top);
      scatterFloorOres(s, room, bx, bz, fy);
      dressChamber(s, room, bx, bz, fy, top);
      decorate(s, room, bx, bz, fy, top);
      flourish(s, room, bx, bz, fy, top);

      // ...and then the room is put into whatever state it is in. Not the threshold (the way in has
      // to be readable from the first second), not a resting place (the whole point of one is that it
      // is safe - the rule used to name the sanctuary alone, which left the reading room, the
      // waystation and now the calm camp free to roll a shedding roof over a bed) and not the
      // guardian's arena (a boss fought in the dark is a boss fought twice).
      if (face != Chamber.ENTRANCE && !isRestRoom(face) && face != Chamber.FLOOR_BOSS) {
         room.condition = Condition.roll();
         if (room.condition != null) {
            applyCondition(s, room, bx, bz, fy, top);
         }
      }
      // ...and every body of water in the room is sealed before anybody sees it. Last, so it
      // covers the chamber's own water and the condition's alike. See sealWater.
      sealWater(s.zoneLevel, bx, bz, fy, top);
      // ...and the Expedition Broker, which is the permanent shop's door inside a run. Placed after
      // the condition so a flooded or burning chamber still gets its merchant standing on solid
      // floor rather than lost in whatever the room is going through.
      if (face != Chamber.ENTRANCE && face != Chamber.FLOOR_BOSS) {
         maybePlantBroker(s, room);
      }

      // A rare second glowstone escape pad deep in the maze.
      if (s.exitPad == null && room.depth >= 6 && RANDOM.nextInt(12) == 0) {
         buildExitPad(level, bx, bz, fy, s);
      }
      return room;
   }

   private static void buildChamber(State s, Room room, int bx, int bz, int fy, int top) {
      switch (room.chamber) {
         case ENTRANCE -> buildEntrance(s, bx, bz, fy, top);
         case GRAND_HALL -> buildGrandHall(s, room, bx, bz, fy, top);
         case TREASURE_VAULT -> buildTreasureVault(s, room, bx, bz, fy, top);
         case TRAP_FLOOR -> buildTrapFloor(s, bx, bz, fy);
         case ORE_VAULT -> buildOreVault(s, room, bx, bz, fy);
         case SANCTUARY -> buildSanctuary(s, bx, bz, fy, top);
         case FOUNTAIN -> buildFountain(s, room, bx, bz, fy, top);
         case MOB_DEN -> buildMobDen(s, bx, bz, fy, top);
         case PILLAR_HALL -> buildPillarHall(s, room, bx, bz, fy, top);
         case CACHE -> buildCache(s, room, bx, bz, fy);
         case SHRINE -> buildShrine(s, bx, bz, fy, top);
         case DROWNED_HALL -> buildDrownedHall(s, room, bx, bz, fy, top);
         case WEB_NEST -> buildWebNest(s, bx, bz, fy, top);
         case LIBRARY -> buildLibrary(s, room, bx, bz, fy, top);
         case REST_LIBRARY -> buildRestLibrary(s, room, bx, bz, fy, top);
         case WAYSTATION -> buildWaystation(s, room, bx, bz, fy, top);
         case CALM_CAMP -> buildCalmCamp(s, room, bx, bz, fy, top);
         case ALCHEMY -> buildAlchemyHall(s, room, bx, bz, fy, top);
         case MOONLIT_CHAPEL -> buildMoonlitChapel(s, room, bx, bz, fy, top);
         case ARMOURY -> buildArmoury(s, room, bx, bz, fy, top);
         case OUBLIETTE -> buildOubliette(s, room, bx, bz, fy, top);
         case LARDER -> buildLarder(s, room, bx, bz, fy, top);
         case GARDEN -> buildGarden(s, room, bx, bz, fy, top);
         case FUNGAL_GROTTO -> buildFungalGrotto(s, room, bx, bz, fy, top);
         case FORGE_HALL -> buildForgeHall(s, room, bx, bz, fy, top);
         case BROKEN_CROSSING -> buildBrokenCrossing(s, room, bx, bz, fy, top);
         case GALLERY -> buildGallery(s, room, bx, bz, fy, top);
         case WAGER_VAULT -> buildWagerVault(s, room, bx, bz, fy, top);
         case MENAGERIE -> buildMenagerie(s, room, bx, bz, fy, top);
         case OBSERVATORY -> buildObservatory(s, room, bx, bz, fy, top);
         case BATHHOUSE -> buildBathhouse(s, room, bx, bz, fy, top);
         case CLOCKWORKS -> buildClockworks(s, room, bx, bz, fy, top);
         case OSSUARY -> buildOssuary(s, room, bx, bz, fy, top);
         case GILDED_VAULT -> buildGildedVault(s, room, bx, bz, fy, top);
         case FLOOR_BOSS -> buildBossArena(s, room, bx, bz, fy, top);
         default -> buildPillarHall(s, room, bx, bz, fy, top);
      }
   }

   /** The local cell of a chamber's centre, and the two ends of its interior. */
   private static int interior() {
      return WALL + 1;
   }

   private static int interiorEnd() {
      return ROOM_PITCH - WALL - 2;
   }

   /**
    * A ring of barred pens with something alive in each one.
    *
    * <p>A chamber in the shape of a decision: fight the pack in the middle of a room full of cages,
    * or use the pens as cover - they are open to the hall on every side a bar is missing, and a bar
    * is missing every sixth cell.
    */
   private static void buildMenagerie(State s, Room room, int bx, int bz, int fy, int top) {
      ServerLevel level = s.zoneLevel;
      int c = ROOM_PITCH / 2;
      int ring = 7;
      BlockState bars = Blocks.IRON_BARS.defaultBlockState();
      BlockState straw = Blocks.HAY_BLOCK.defaultBlockState();
      for (int i = 0; i < 24; i++) {
         int lx = c + (int)Math.round(Math.cos(i / 24.0 * Math.PI * 2.0) * ring);
         int lz = c + (int)Math.round(Math.sin(i / 24.0 * Math.PI * 2.0) * ring);
         if (i % 6 == 0 || inDoorLane(room, lx, lz)) {
            continue;
         }
         for (int y = fy + 1; y <= fy + 3; y++) {
            setIfChanged(level, new BlockPos(bx + lx, y, bz + lz), bars);
         }
      }
      // Straw and a water trough in each corner pen.
      for (int[] corner : new int[][]{{6, 6}, {6, ROOM_PITCH - 7}, {ROOM_PITCH - 7, 6}, {ROOM_PITCH - 7, ROOM_PITCH - 7}}) {
         if (inDoorLane(room, corner[0], corner[1])) {
            continue;
         }
         setIfChanged(level, new BlockPos(bx + corner[0], fy, bz + corner[1]), straw);
         setIfChanged(level, new BlockPos(bx + corner[0] + 1, fy, bz + corner[1]), Blocks.HAY_BLOCK.defaultBlockState());
         setIfChanged(level, new BlockPos(bx + corner[0], fy + 1, bz + corner[1] + 1), Blocks.CAULDRON.defaultBlockState());
      }
      placeLootChest(level, bx + c, fy + 1, bz + c - 5, s.type, true);
   }

   /**
    * A raised quartz dais under a ceiling of stars.
    *
    * <p>The one chamber in the maze that is about looking up: a plinth in the middle, quartz
    * columns at the corners of the dais, and a grid of sea lanterns in the roof that reads as a
    * star field rather than as lighting.
    */
   private static void buildObservatory(State s, Room room, int bx, int bz, int fy, int top) {
      ServerLevel level = s.zoneLevel;
      int c = ROOM_PITCH / 2;
      int r = 6;
      for (int lx = c - r; lx <= c + r; lx++) {
         for (int lz = c - r; lz <= c + r; lz++) {
            boolean edge = lx == c - r || lx == c + r || lz == c - r || lz == c + r;
            if (inDoorLane(room, lx, lz)) {
               continue;
            }
            setIfChanged(
               level, new BlockPos(bx + lx, fy, bz + lz),
               edge ? Blocks.QUARTZ_BLOCK.defaultBlockState() : Blocks.POLISHED_DIORITE.defaultBlockState()
            );
         }
      }
      for (int[] corner : new int[][]{{c - r, c - r}, {c - r, c + r}, {c + r, c - r}, {c + r, c + r}}) {
         for (int y = fy + 1; y <= fy + 4; y++) {
            setIfChanged(level, new BlockPos(bx + corner[0], y, bz + corner[1]), Blocks.QUARTZ_PILLAR.defaultBlockState());
         }
         setIfChanged(level, new BlockPos(bx + corner[0], fy + 5, bz + corner[1]), lightBlockFor(s.type));
      }
      setIfChanged(level, new BlockPos(bx + c, fy, bz + c), Blocks.QUARTZ_BLOCK.defaultBlockState());
      setIfChanged(level, new BlockPos(bx + c, fy + 1, bz + c), Blocks.QUARTZ_PILLAR.defaultBlockState());
      setIfChanged(level, new BlockPos(bx + c, fy + 2, bz + c), Blocks.QUARTZ_PILLAR.defaultBlockState());
      setIfChanged(level, new BlockPos(bx + c, fy + 3, bz + c), Blocks.SEA_LANTERN.defaultBlockState());
      placeLootChest(level, bx + c + 2, fy + 1, bz + c, s.type, true);
   }

   /**
    * Three sunken pools over soul sand, ringed with quartz.
    *
    * <p>The bubble columns are the point: they lift a body that steps into one, which is the only
    * vertical movement in the maze that a player can improvise.
    */
   private static void buildBathhouse(State s, Room room, int bx, int bz, int fy, int top) {
      ServerLevel level = s.zoneLevel;
      int c = ROOM_PITCH / 2;
      for (int[] pool : new int[][]{{c - 8, c - 8}, {c + 8, c - 8}, {c, c + 8}}) {
         if (inDoorLane(room, pool[0], pool[1])) {
            continue;
         }
         for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
               boolean rim = Math.max(Math.abs(dx), Math.abs(dz)) == 2;
               BlockPos cell = new BlockPos(bx + pool[0] + dx, fy, bz + pool[1] + dz);
               setIfChanged(
                  level, cell, rim ? Blocks.QUARTZ_BLOCK.defaultBlockState() : Blocks.SOUL_SAND.defaultBlockState()
               );
               setIfChanged(
                  level, cell.above(),
                  rim ? Blocks.QUARTZ_BLOCK.defaultBlockState() : Blocks.WATER.defaultBlockState()
               );
            }
         }
         setIfChanged(level, new BlockPos(bx + pool[0], fy + 3, bz + pool[1]), Blocks.SEA_LANTERN.defaultBlockState());
      }
      for (int lx = interior(); lx < interiorEnd(); lx++) {
         for (int lz = interior(); lz < interiorEnd(); lz++) {
            if (RANDOM.nextInt(100) < 6 && !inDoorLane(room, lx, lz)) {
               setIfChanged(level, new BlockPos(bx + lx, fy, bz + lz), Blocks.POLISHED_DIORITE.defaultBlockState());
            }
         }
      }
      placeLootChest(level, bx + c - 4, fy + 1, bz + c + 4, s.type, true);
   }

   /**
    * Copper, iron and redstone: a hall built around an engine nobody has switched off.
    */
   private static void buildClockworks(State s, Room room, int bx, int bz, int fy, int top) {
      ServerLevel level = s.zoneLevel;
      int c = ROOM_PITCH / 2;
      for (int i = 0; i < 8; i++) {
         double a = i / 8.0 * Math.PI * 2.0;
         int lx = c + (int)Math.round(Math.cos(a) * 9);
         int lz = c + (int)Math.round(Math.sin(a) * 9);
         if (inDoorLane(room, lx, lz)) {
            continue;
         }
         for (int y = fy + 1; y <= fy + 3; y++) {
            setIfChanged(
               level, new BlockPos(bx + lx, y, bz + lz),
               y == fy + 2 ? Blocks.ANVIL.defaultBlockState() : Blocks.IRON_BLOCK.defaultBlockState()
            );
         }
         setIfChanged(level, new BlockPos(bx + lx, fy + 4, bz + lz), Blocks.REDSTONE_LAMP.defaultBlockState()
            .setValue(net.minecraft.world.level.block.RedstoneLampBlock.LIT, true));
      }
      // The engine itself: piston heads over a lit core, and the floor plate it sits on.
      for (int dx = -2; dx <= 2; dx++) {
         for (int dz = -2; dz <= 2; dz++) {
            boolean edge = Math.max(Math.abs(dx), Math.abs(dz)) == 2;
            setIfChanged(
               level, new BlockPos(bx + c + dx, fy, bz + c + dz),
               edge ? Blocks.DEEPSLATE_TILES.defaultBlockState() : Blocks.IRON_BLOCK.defaultBlockState()
            );
         }
      }
      setIfChanged(level, new BlockPos(bx + c, fy + 1, bz + c), Blocks.REDSTONE_LAMP.defaultBlockState()
         .setValue(net.minecraft.world.level.block.RedstoneLampBlock.LIT, true));
      setIfChanged(level, new BlockPos(bx + c, fy + 2, bz + c), Blocks.PISTON.defaultBlockState());
      for (int[] arm : new int[][]{{c - 2, c}, {c + 2, c}, {c, c - 2}, {c, c + 2}}) {
         setIfChanged(level, new BlockPos(bx + arm[0], fy + 1, bz + arm[1]), Blocks.PISTON.defaultBlockState());
         setIfChanged(level, new BlockPos(bx + arm[0], fy + 2, bz + arm[1]), Blocks.IRON_BLOCK.defaultBlockState());
      }
      placeLootChest(level, bx + c + 4, fy + 1, bz + c - 4, s.type, true);
   }

   /**
    * A hall of the dead: bone in the walls, skulls on the plinths, and a floor of tile.
    */
   private static void buildOssuary(State s, Room room, int bx, int bz, int fy, int top) {
      ServerLevel level = s.zoneLevel;
      int c = ROOM_PITCH / 2;
      int first = WALL;
      int last = ROOM_PITCH - WALL - 1;
      for (int lx = first; lx <= last; lx++) {
         for (int lz = first; lz <= last; lz++) {
            boolean wallFace = lx == first || lx == last || lz == first || lz == last;
            if (!wallFace || inDoorLane(room, lx, lz)) {
               continue;
            }
            if ((lx + lz) % 3 == 0) {
               setIfChanged(level, new BlockPos(bx + lx, fy + 2, bz + lz), Blocks.BONE_BLOCK.defaultBlockState());
            }
            if ((lx * 3 + lz) % 11 == 0) {
               setIfChanged(level, new BlockPos(bx + lx, fy + 4, bz + lz), Blocks.SKELETON_SKULL.defaultBlockState());
            }
         }
      }
      for (int[] spot : new int[][]{{c - 7, c}, {c + 7, c}, {c, c - 7}, {c, c + 7}}) {
         if (inDoorLane(room, spot[0], spot[1])) {
            continue;
         }
         setIfChanged(level, new BlockPos(bx + spot[0], fy, bz + spot[1]), Blocks.DEEPSLATE_TILES.defaultBlockState());
         setIfChanged(level, new BlockPos(bx + spot[0], fy + 1, bz + spot[1]), Blocks.BONE_BLOCK.defaultBlockState());
         setIfChanged(level, new BlockPos(bx + spot[0], fy + 2, bz + spot[1]), Blocks.SKELETON_SKULL.defaultBlockState());
      }
      placeLootChest(level, bx + c, fy + 1, bz + c - 4, s.type, true);
   }

   /**
    * The richest room in the maze: a gold floor, gilded stone, and three chests instead of one.
    */
   private static void buildGildedVault(State s, Room room, int bx, int bz, int fy, int top) {
      ServerLevel level = s.zoneLevel;
      int c = ROOM_PITCH / 2;
      for (int lx = interior(); lx < interiorEnd(); lx++) {
         for (int lz = interior(); lz < interiorEnd(); lz++) {
            if (inDoorLane(room, lx, lz)) {
               continue;
            }
            int ring = Math.max(Math.abs(lx - c), Math.abs(lz - c));
            if (ring == 8) {
               setIfChanged(
                  level, new BlockPos(bx + lx, fy, bz + lz),
                  (lx + lz) % 2 == 0 ? Blocks.GOLD_BLOCK.defaultBlockState() : Blocks.EMERALD_BLOCK.defaultBlockState()
               );
            }
         }
      }
      for (int[] corner : new int[][]{{c - 8, c - 8}, {c - 8, c + 8}, {c + 8, c - 8}, {c + 8, c + 8}}) {
         for (int y = fy + 1; y <= fy + 3; y++) {
            setIfChanged(level, new BlockPos(bx + corner[0], y, bz + corner[1]), Blocks.GILDED_BLACKSTONE.defaultBlockState());
         }
      }
      // A plinth in the middle, with the room's best chest on it.
      for (int dx = -1; dx <= 1; dx++) {
         for (int dz = -1; dz <= 1; dz++) {
            setIfChanged(level, new BlockPos(bx + c + dx, fy + 1, bz + c + dz), Blocks.GOLD_BLOCK.defaultBlockState());
         }
      }
      placeLootChest(level, bx + c, fy + 2, bz + c, s.type, true);
      placeLootChest(level, bx + c - 6, fy + 1, bz + c - 6, s.type, true);
      placeLootChest(level, bx + c + 6, fy + 1, bz + c + 6, s.type, true);
   }

   /**
    * The dressing pass: what a chamber is missing once its blocks are in place.
    *
    * <p>Shell, face and ore studs build a floor, four walls and something to fight or loot. None
    * of that makes a place: a hall is a hall because of its border course, its pilasters and the
    * crates somebody left in the corner. This runs last, after the chamber's own builders, so it
    * dresses whatever they drew rather than being painted over by them - and it asks
    * {@link #inDoorLane} before every block, because a chair in a doorway is a wall.
    */
   private static void dressChamber(State s, Room room, int bx, int bz, int fy, int top) {
      ServerLevel level = s.zoneLevel;
      BlockState inlay = daisBlock(s.type);
      Block crate = crateFor(s.type);
      int first = WALL;
      int last = ROOM_PITCH - WALL - 1;
      // An inlaid border ring in the floor: the same dressed stone around the edge of every hall,
      // so a chamber reads as a room with walls rather than as a floor that happens to stop.
      for (int lx = first; lx <= last; lx++) {
         for (int lz = first; lz <= last; lz++) {
            if (lx != first && lx != last && lz != first && lz != last) {
               continue;
            }
            if (inDoorLane(room, lx, lz)) {
               continue;
            }
            BlockPos at = new BlockPos(bx + lx, fy, bz + lz);
            BlockState here = level.getBlockState(at);
            if (isMineableOre(here) || here.is(Blocks.CHEST) || here.is(Blocks.SPAWNER)) {
               continue;
            }
            setIfChanged(level, at, inlay);
         }
      }
      // The four inner corners get the furniture: a crate against the wall, sometimes a second on
      // top of it, and a hoist chain hanging over it out of the ceiling.
      int[][] corners = {
         {first + 1, first + 1}, {first + 1, last - 1},
         {last - 1, first + 1}, {last - 1, last - 1}
      };
      int stacked = 0;
      for (int[] at : corners) {
         if (inDoorLane(room, at[0], at[1])) {
            continue;
         }
         setIfChanged(level, new BlockPos(bx + at[0], fy + 1, bz + at[1]), crate.defaultBlockState());
         if ((stacked++ & 1) == 0) {
            setIfChanged(level, new BlockPos(bx + at[0], fy + 2, bz + at[1]), crate.defaultBlockState());
         }
         for (int y = top - 1; y >= top - 3; y--) {
            setIfChanged(level, new BlockPos(bx + at[0], y, bz + at[1]), Blocks.IRON_CHAIN.defaultBlockState());
         }
      }
   }

   /** The crate a dungeon type leaves lying around - the props that say somebody lived here. */
   private static Block crateFor(Type type) {
      return switch (type) {
         case MONSTER_CAVE -> Blocks.BONE_BLOCK;
         case CRYSTAL_CAVERN -> Blocks.CALCITE;
         case VOID -> Blocks.POLISHED_BLACKSTONE;
         case SUNKEN_TEMPLE -> Blocks.DARK_PRISMARINE;
         case FROZEN_CRYPT -> Blocks.PACKED_ICE;
         case MAGMA_FORGE -> Blocks.MAGMA_BLOCK;
         default -> Blocks.BARREL;
      };
   }

   /** The threshold: a gold-ringed glowstone pad with the way out in the middle of it. */
   private static void buildEntrance(State s, int bx, int bz, int fy, int top) {
      ServerLevel level = s.zoneLevel;
      BlockState glow = Blocks.GLOWSTONE.defaultBlockState();
      BlockState gold = Blocks.GOLD_BLOCK.defaultBlockState();
      int c = ROOM_PITCH / 2;
      for (int x = -4; x <= 3; x++) {
         for (int z = -4; z <= 3; z++) {
            boolean edge = x == -4 || x == 3 || z == -4 || z == 3;
            setIfChanged(level, new BlockPos(bx + c + x, fy, bz + c + z), edge ? gold : glow);
         }
      }
      // The middle of the pad is kept CLEAR - it is where the player arrives, and a body dropped
      // into a block suffocates before it can read the room. The threshold used to stand a
      // four-tall sea-lantern column on the arrival point, so a run could end on its first tick.
      for (int y = fy + 1; y <= fy + 4; y++) {
         setIfChanged(level, new BlockPos(bx + c, y, bz + c), Blocks.AIR.defaultBlockState());
      }
      // The pad's light lives on its rim instead: a lantern mast on each corner of the gold edge.
      for (int[] at : new int[][]{{-4, -4}, {-4, 3}, {3, -4}, {3, 3}}) {
         for (int y = fy + 1; y <= fy + 3; y++) {
            setIfChanged(level, new BlockPos(bx + c + at[0], y, bz + c + at[1]), Blocks.GOLD_BLOCK.defaultBlockState());
         }
         setIfChanged(
            level, new BlockPos(bx + c + at[0], fy + 4, bz + c + at[1]), Blocks.SEA_LANTERN.defaultBlockState()
         );
      }
      // And one lodestone beside the arrival point, so "the pad" is a place you can pick out
      // from anywhere in the hall rather than a patch of floor you have to remember.
      setIfChanged(level, new BlockPos(bx + c - 1, fy + 1, bz + c), Blocks.LODESTONE.defaultBlockState());
   }

   /** Grand hall: the roof goes up three blocks, pillars ring the floor, a dais holds rich loot. */
   private static void buildGrandHall(State s, Room room, int bx, int bz, int fy, int top) {
      ServerLevel level = s.zoneLevel;
      BlockState wall = wallBlockFor(s.type);
      BlockState ceil = ceilingBlockFor(s.type);
      BlockState pillar = trimBlockFor(s.type);
      BlockState air = Blocks.AIR.defaultBlockState();
      // Raise the roof over the whole hall - the giant rooms deserve the headroom.
      for (int lx = 0; lx < ROOM_PITCH; lx++) {
         for (int lz = 0; lz < ROOM_PITCH; lz++) {
            boolean band = lx < WALL || lx >= ROOM_PITCH - WALL || lz < WALL || lz >= ROOM_PITCH - WALL;
            boolean beam = (lx - WALL) % COFFER == 0 || (lz - WALL) % COFFER == 0;
            boolean lantern = (lx - WALL) % COFFER == COFFER / 2 && (lz - WALL) % COFFER == COFFER / 2;
            for (int y = top; y <= top + 3; y++) {
               BlockState want;
               if (y < top + 3) {
                  want = band ? wall : air;
               } else if (lantern) {
                  want = lightBlockFor(s.type);
               } else if (beam) {
                  want = trimBlockFor(s.type);
               } else {
                  want = ceil;
               }
               setIfChanged(level, new BlockPos(bx + lx, y, bz + lz), want);
            }
         }
      }
      // Pillars in a ring, and a second ring inside it: a pillared hall you can lose sight in.
      int c = ROOM_PITCH / 2;
      int near = WALL + 3;
      int far = ROOM_PITCH - WALL - 4;
      int[][] pts = {
         {near, near}, {near, far}, {far, near}, {far, far},
         {WALL, c - 2}, {c - 2, WALL}, {c - 2, ROOM_PITCH - WALL - 3}, {ROOM_PITCH - WALL - 3, c - 2}
      };
      for (int[] p : pts) {
         for (int y = fy + 1; y <= top + 2; y++) {
            setIfChanged(level, new BlockPos(bx + p[0], y, bz + p[1]), pillar);
         }
      }
      // A raised gallery along the north wall: a stone balcony you can fight on, two steps up,
      // with its own rail of trim. The old hall was one flat floor with pillars on it; a hall
      // you can look down into is a hall worth crossing.
      for (int lx = WALL; lx < ROOM_PITCH - WALL; lx++) {
         for (int lz = WALL; lz < WALL + 5; lz++) {
            setIfChanged(level, new BlockPos(bx + lx, fy + 3, bz + lz), lz == WALL ? wall : daisBlock(s.type));
         }
         setIfChanged(level, new BlockPos(bx + lx, fy + 4, bz + WALL), pillar);
      }
      // Central dais under a lantern canopy, with the hall's treasure on the step.
      BlockState dais = floorBlockFor(s.type);
      for (int x = c - 3; x <= c; x++) {
         for (int z = c - 3; z <= c; z++) {
            setIfChanged(level, new BlockPos(bx + x, fy + 1, bz + z), dais);
         }
      }
      placeLootChest(level, bx + c - 3, fy + 2, bz + c - 3, s.type, true);
      placeLootChest(level, bx + c, fy + 2, bz + c, s.type, false);
      for (int[] l : new int[][]{{c - 2, c - 2}, {c - 2, c - 1}, {c - 1, c - 2}, {c - 1, c - 1}}) {
         setIfChanged(level, new BlockPos(bx + l[0], top + 3, bz + l[1]), lightBlockFor(s.type));
      }
   }

   /** The floor stone of a chamber's own dungeon type - used for the raised galleries. */
   private static BlockState daisBlock(Type type) {
      return switch (type) {
         case VOID -> Blocks.POLISHED_BLACKSTONE.defaultBlockState();
         case CRYSTAL_CAVERN -> Blocks.POLISHED_DEEPSLATE.defaultBlockState();
         case SUNKEN_TEMPLE -> Blocks.PRISMARINE_BRICKS.defaultBlockState();
         case FROZEN_CRYPT -> Blocks.POLISHED_DIORITE.defaultBlockState();
         case MAGMA_FORGE -> Blocks.POLISHED_BLACKSTONE.defaultBlockState();
         default -> Blocks.STONE_BRICKS.defaultBlockState();
      };
   }

   /** Treasure vault: a floor of precious stone, a dais, and two or three rich chests. */
   private static void buildTreasureVault(State s, Room room, int bx, int bz, int fy, int top) {
      ServerLevel level = s.zoneLevel;
      BlockState floorMat = switch (s.type) {
         case VOID -> Blocks.OBSIDIAN.defaultBlockState();
         case CRYSTAL_CAVERN -> Blocks.AMETHYST_BLOCK.defaultBlockState();
         case SUNKEN_TEMPLE -> Blocks.PRISMARINE.defaultBlockState();
         case FROZEN_CRYPT -> Blocks.PACKED_ICE.defaultBlockState();
         case MAGMA_FORGE -> Blocks.GOLD_BLOCK.defaultBlockState();
         default -> Blocks.GOLD_BLOCK.defaultBlockState();
      };
      for (int lx = WALL; lx < ROOM_PITCH - WALL; lx++) {
         for (int lz = WALL; lz < ROOM_PITCH - WALL; lz++) {
            setIfChanged(level, new BlockPos(bx + lx, fy, bz + lz), floorMat);
         }
      }
      // Dais in the middle with the vault's treasure on it, and a stair up to it.
      int c = ROOM_PITCH / 2;
      for (int x = c - 2; x <= c + 1; x++) {
         for (int z = c - 2; z <= c + 1; z++) {
            setIfChanged(level, new BlockPos(bx + x, fy + 1, bz + z), trimBlockFor(s.type));
         }
      }
      placeLootChest(level, bx + c - 2, fy + 2, bz + c - 2, s.type, true);
      placeLootChest(level, bx + c + 1, fy + 2, bz + c + 1, s.type, true);
      if (RANDOM.nextInt(3) == 0) {
         placeLootChest(level, bx + WALL + 2, fy + 1, bz + ROOM_PITCH - WALL - 4, s.type, true);
      }
      // Chandeliers at the corners.
      for (int[] l : new int[][]{
         {WALL + 1, WALL + 1}, {WALL + 1, ROOM_PITCH - WALL - 4},
         {ROOM_PITCH - WALL - 4, WALL + 1}, {ROOM_PITCH - WALL - 4, ROOM_PITCH - WALL - 4}
      }) {
         setIfChanged(level, new BlockPos(bx + l[0], top, bz + l[1]), lightBlockFor(s.type));
      }
      // ...and two more hanging over the dais, so the treasure is lit from above.
      for (int[] l : new int[][]{{c - 2, c + 1}, {c + 1, c - 2}}) {
         setIfChanged(level, new BlockPos(bx + l[0], top, bz + l[1]), lightBlockFor(s.type));
      }
   }

   /** Trap floor: pressure plates over hidden TNT, and a rich chest for the survivor. */
   private static void buildTrapFloor(State s, int bx, int bz, int fy) {
      ServerLevel level = s.zoneLevel;
      for (int i = WALL; i < ROOM_PITCH - WALL; i += 3) {
         setIfChanged(level, new BlockPos(bx + i, fy + 1, bz + WALL + 1), Blocks.STONE_PRESSURE_PLATE.defaultBlockState());
         setIfChanged(level, new BlockPos(bx + WALL + 1, fy + 1, bz + i), Blocks.STONE_PRESSURE_PLATE.defaultBlockState());
      }
      // And a proper minefield in one quarter of the floor: a lattice of charged plates with TNT
      // under every one of them, so crossing this half of the hall is a decision rather than a
      // walk. The shell under the floor is bedrock, so the blast cannot breach the chamber - it
      // is the player who is exposed, not the dungeon.
      int px = WALL + 3 + RANDOM.nextInt(Math.max(1, ROOM_PITCH / 2 - 12));
      int pz = WALL + 3 + RANDOM.nextInt(Math.max(1, ROOM_PITCH / 2 - 12));
      for (int x = 0; x < 8; x += 2) {
         for (int z = 0; z < 8; z += 2) {
            setIfChanged(level, new BlockPos(bx + px + x, fy, bz + pz + z), Blocks.TNT.defaultBlockState());
            setIfChanged(level, new BlockPos(bx + px + x, fy + 1, bz + pz + z), Blocks.STONE_PRESSURE_PLATE.defaultBlockState());
         }
      }
      for (int t = 0; t < 3; t++) {
         int tx = WALL + 2 + RANDOM.nextInt(ROOM_PITCH - 2 * WALL - 4);
         int tz = WALL + 2 + RANDOM.nextInt(ROOM_PITCH - 2 * WALL - 4);
         setIfChanged(level, new BlockPos(bx + tx, fy, bz + tz), Blocks.TNT.defaultBlockState());
         setIfChanged(level, new BlockPos(bx + tx, fy + 1, bz + tz), Blocks.STONE_PRESSURE_PLATE.defaultBlockState());
         setIfChanged(level, new BlockPos(bx + tx, fy + 2, bz + tz), Blocks.TRIPWIRE_HOOK.defaultBlockState());
      }
      placeLootChest(level, bx + ROOM_PITCH / 2, fy + 1, bz + ROOM_PITCH / 2, s.type, true);
   }

   /** Ore vault: walls and floor studded with this dungeon's ores. No chest - dig. */
   private static void buildOreVault(State s, Room room, int bx, int bz, int fy) {
      ServerLevel level = s.zoneLevel;
      for (int lx = WALL; lx < ROOM_PITCH - WALL; lx++) {
         for (int lz = WALL; lz < ROOM_PITCH - WALL; lz++) {
            if (RANDOM.nextInt(3) == 0) {
               Block ore = oreFor(s.type, RANDOM.nextInt(100), 1);
               if (ore != null) {
                  setIfChanged(level, new BlockPos(bx + lx, fy, bz + lz), ore.defaultBlockState());
               }
            }
            if (RANDOM.nextInt(6) == 0) {
               Block ore = oreFor(s.type, RANDOM.nextInt(100), 1);
               if (ore != null) {
                  setIfChanged(level, new BlockPos(bx + lx, fy + 1 + RANDOM.nextInt(3), bz + WALL), ore.defaultBlockState());
               }
            }
            if (RANDOM.nextInt(6) == 0) {
               Block ore = oreFor(s.type, RANDOM.nextInt(100), 1);
               if (ore != null) {
                  setIfChanged(level, new BlockPos(bx + WALL, fy + 1 + RANDOM.nextInt(3), bz + lz), ore.defaultBlockState());
               }
            }
         }
      }
   }

   /** Sanctuary: a carpeted quiet room with a lit shrine well. No packs ever wake here. */
   private static void buildSanctuary(State s, int bx, int bz, int fy, int top) {
      ServerLevel level = s.zoneLevel;
      BlockState carpet = Blocks.CARPET.pick(net.minecraft.world.item.DyeColor.CYAN).defaultBlockState();
      for (int lx = WALL; lx < ROOM_PITCH - WALL; lx++) {
         for (int lz = WALL; lz < ROOM_PITCH - WALL; lz++) {
            if (lx == ROOM_PITCH / 2 || lz == ROOM_PITCH / 2 || (lx + lz) % 3 == 0) {
               setIfChanged(level, new BlockPos(bx + lx, fy + 1, bz + lz), carpet);
            }
         }
      }
      for (int x = -1; x <= 0; x++) {
         for (int z = -1; z <= 0; z++) {
            setIfChanged(level, new BlockPos(bx + ROOM_PITCH / 2 + x, fy + 1, bz + ROOM_PITCH / 2 + z), Blocks.GLOWSTONE.defaultBlockState());
            setIfChanged(level, new BlockPos(bx + ROOM_PITCH / 2 + x, fy + 2, bz + ROOM_PITCH / 2 + z), Blocks.CRYING_OBSIDIAN.defaultBlockState());
         }
      }
      setIfChanged(level, new BlockPos(bx + ROOM_PITCH / 2 - 2, fy + 1, bz + ROOM_PITCH / 2), Blocks.LODESTONE.defaultBlockState());
   }

   /**
    * Wayfarer's Waystation: the site's third camp, and the one a long descent meets most often.
    *
    * <p>The other two camps are rooms built for another purpose and found quiet. This one is a camp
    * on purpose: a caravan stopped dead in the middle of the chamber, its fire still burning, its
    * barrels still full, hay underfoot and lamps on posts at the edge of the deck. A run that is
    * short on health and long on depth needs somewhere that reads as somewhere to stop rather than
    * somewhere that merely happens to be safe.
    */
   private static void buildWaystation(State s, Room room, int bx, int bz, int fy, int top) {
      ServerLevel level = s.zoneLevel;
      int c = ROOM_PITCH / 2;
      // The deck: planks inside a ring of stripped logs, so the stop has an edge you can see.
      for (int x = -6; x <= 5; x++) {
         for (int z = -6; z <= 5; z++) {
            setIfChanged(level, new BlockPos(bx + c + x, fy, bz + c + z), Blocks.SPRUCE_PLANKS.defaultBlockState());
         }
      }
      for (int i = -6; i <= 5; i++) {
         setIfChanged(level, new BlockPos(bx + c + i, fy, bz + c - 6), Blocks.STRIPPED_SPRUCE_LOG.defaultBlockState());
         setIfChanged(level, new BlockPos(bx + c + i, fy, bz + c + 5), Blocks.STRIPPED_SPRUCE_LOG.defaultBlockState());
         setIfChanged(level, new BlockPos(bx + c - 6, fy, bz + c + i), Blocks.STRIPPED_SPRUCE_LOG.defaultBlockState());
         setIfChanged(level, new BlockPos(bx + c + 5, fy, bz + c + i), Blocks.STRIPPED_SPRUCE_LOG.defaultBlockState());
      }
      // The fire, and the hay the wagons unloaded around it.
      setIfChanged(level, new BlockPos(bx + c, fy + 1, bz + c), Blocks.CAMPFIRE.defaultBlockState());
      for (int[] h : new int[][]{{2, 2}, {-3, 2}, {2, -3}, {-3, -3}}) {
         setIfChanged(level, new BlockPos(bx + c + h[0], fy + 1, bz + c + h[1]), Blocks.HAY_BLOCK.defaultBlockState());
      }
      for (int[] b : new int[][]{{-4, 0}, {-4, 1}, {4, 0}}) {
         setIfChanged(level, new BlockPos(bx + c + b[0], fy + 1, bz + c + b[1]), Blocks.BARREL.defaultBlockState());
      }
      // A lamp on a post at each corner, which is how a camp says where its edge is.
      for (int sx = -1; sx <= 1; sx += 2) {
         for (int sz = -1; sz <= 1; sz += 2) {
            int px = bx + c + sx * 5;
            int pz = bz + c + sz * 5;
            setIfChanged(level, new BlockPos(px, fy + 1, pz), Blocks.SPRUCE_FENCE.defaultBlockState());
            setIfChanged(level, new BlockPos(px, fy + 2, pz), Blocks.LANTERN.defaultBlockState());
         }
      }
      // A carpet through the middle, so the walk from a doorway to the fire is a walk.
      for (int i = -5; i <= 4; i++) {
         setIfChanged(
            level, new BlockPos(bx + c + i, fy + 1, bz + c),
            Blocks.CARPET.pick(net.minecraft.world.item.DyeColor.RED).defaultBlockState()
         );
         setIfChanged(
            level, new BlockPos(bx + c, fy + 1, bz + c + i),
            Blocks.CARPET.pick(net.minecraft.world.item.DyeColor.RED).defaultBlockState()
         );
      }
      // The stores: a supply crate, and the chest the caravan never got to load.
      plant(s, room, new BlockPos(bx + c + 3, fy + 1, bz + c + 3), Interact.SUPPLY, Blocks.BARREL.defaultBlockState());
      placeLootChest(level, bx + c - 3, fy + 1, bz + c - 3, s.type, true);
      // ...and lamps over it, because a camp you cannot see is a camp you walk past.
      for (int x = -4; x <= 3; x += 4) {
         for (int z = -4; z <= 3; z += 4) {
            setIfChanged(level, new BlockPos(bx + c + x, top - 1, bz + c + z), Blocks.LANTERN.defaultBlockState());
         }
      }
   }

   /**
    * Alchemy Hall: a witches' stillroom, and the site's one room that is worth walking into hurt.
    *
    * <p>The pack is the biggest the maze wakes - a coven, almost all of it witches - and the work
    * they left standing is the room's whole point: brewing stands down both sides, cauldrons on the
    * boil, nether wart growing in the corners, glowstone burning over the benches, and three
    * **Healing Draughts** planted in the cauldrons in the middle, each of which answers a right-click
    * with an instant mend and a real Potion of Healing to carry. A room that is only a bigger fight
    * is a room a hurt player walks past; this one is a bigger fight with a reason on the far side of
    * it.
    */
   private static void buildAlchemyHall(State s, Room room, int bx, int bz, int fy, int top) {
      ServerLevel level = s.zoneLevel;
      int c = ROOM_PITCH / 2;
      int r = 8;
      BlockState tile = Blocks.STONE_BRICKS.defaultBlockState();
      // The floor: dressed stone, so the hall reads as a workshop rather than as another cave.
      for (int x = -r; x <= r - 1; x++) {
         for (int z = -r; z <= r - 1; z++) {
            setIfChanged(level, new BlockPos(bx + c + x, fy, bz + c + z), tile);
         }
      }
      // The benches: a brewing stand with its own stores beside it, down each side of the hall.
      for (int z = -6; z <= 5; z += 4) {
         for (int side = -1; side <= 1; side += 2) {
            int px = bx + c + side * 5;
            setIfChanged(level, new BlockPos(px, fy + 1, bz + c + z), Blocks.BREWING_STAND.defaultBlockState());
            setIfChanged(level, new BlockPos(px + side, fy + 1, bz + c + z), Blocks.BARREL.defaultBlockState());
            setIfChanged(level, new BlockPos(px + side, fy + 2, bz + c + z), Blocks.CANDLE.defaultBlockState());
         }
      }
      // The boil: four cauldrons in the middle of the hall. Three of them are the cures - planted as
      // draughts - and the fourth is water, because a stillroom whose cauldrons are all magic reads
      // as a vending machine rather than as a room somebody works in.
      int[][] pots = {{-2, -2}, {1, -2}, {-2, 1}, {1, 1}};
      for (int i = 0; i < pots.length; i++) {
         BlockPos at = new BlockPos(bx + c + pots[i][0], fy + 1, bz + c + pots[i][1]);
         if (i == pots.length - 1) {
            setIfChanged(level, at, Blocks.CAULDRON.defaultBlockState());
         } else {
            plant(s, room, at, Interact.ELIXIR, Blocks.CAULDRON.defaultBlockState());
         }
      }
      setIfChanged(level, new BlockPos(bx + c, fy + 1, bz + c), Blocks.CAULDRON.defaultBlockState());
      // The garden: nether wart growing in all four corners, which is where it comes from.
      for (int[] g : new int[][]{{-7, -7}, {6, -7}, {-7, 6}, {6, 6}}) {
         int gx = bx + c + g[0];
         int gz = bz + c + g[1];
         for (int dx = 0; dx <= 1; dx++) {
            for (int dz = 0; dz <= 1; dz++) {
               setIfChanged(level, new BlockPos(gx + dx, fy + 1, gz + dz), Blocks.SOUL_SAND.defaultBlockState());
               setIfChanged(level, new BlockPos(gx + dx, fy + 2, gz + dz), Blocks.NETHER_WART.defaultBlockState());
            }
         }
      }
      // The shelves: what a stillroom keeps, stacked along the walls.
      for (int i = -7; i <= 6; i += 3) {
         setIfChanged(level, new BlockPos(bx + c + i, fy + 1, bz + WALL + 1), Blocks.BARREL.defaultBlockState());
         setIfChanged(level, new BlockPos(bx + WALL + 1, fy + 1, bz + c + i), Blocks.BOOKSHELF.defaultBlockState());
      }
      // Light: glowstone under the ceiling over the benches, so the work is lit from above and the
      // room is the brightest thing in the maze from the doorway.
      for (int x = -6; x <= 5; x += 4) {
         for (int z = -6; z <= 5; z += 4) {
            setIfChanged(level, new BlockPos(bx + c + x, top - 1, bz + c + z), Blocks.GLOWSTONE.defaultBlockState());
         }
      }
      // ...and the chest the coven keeps its takings in, which is the reason to fight the pack.
      placeLootChest(level, bx + c, fy + 1, bz + c - 6, s.type, true);
      setIfChanged(level, new BlockPos(bx + c - 1, fy + 1, bz + c - 6), Blocks.SOUL_LANTERN.defaultBlockState());
      setIfChanged(level, new BlockPos(bx + c + 1, fy + 1, bz + c - 6), Blocks.SOUL_LANTERN.defaultBlockState());
   }

   /**
    * Calm Campplace: the site's camp proper, and the room the healing budget is written around.
    *
    * <p>The other three camps are rooms built for something else and then found quiet - a shrine's
    * stone, a library nobody is reading in, a caravan that stopped. This one is a camp on purpose,
    * and it is the only chamber in the maze whose whole job is the mend, so it is built as the
    * opposite of the maze around it: grass underfoot in a mossed border, a grove of oaks at the
    * corners of the glade, and a fire on a hearth of dressed stone with the stores stacked inside
    * the reach of its light. Nothing in here wakes and nothing in here is a puzzle. The price of the
    * best healing in the site is the walk to it, and that is the whole of the trade.
    */
   private static void buildCalmCamp(State s, Room room, int bx, int bz, int fy, int top) {
      ServerLevel level = s.zoneLevel;
      int c = ROOM_PITCH / 2;
      int r = 8;
      BlockState grass = Blocks.GRASS_BLOCK.defaultBlockState();
      BlockState moss = Blocks.MOSS_BLOCK.defaultBlockState();
      // The glade: grass across the middle of the chamber with a mossed border, and the odd patch of
      // carpet moss where the light does not reach, so the floor alone says "you are outside now".
      for (int x = -r; x <= r - 1; x++) {
         for (int z = -r; z <= r - 1; z++) {
            boolean rim = Math.max(Math.abs(x + 0.5), Math.abs(z + 0.5)) > r - 1.5;
            setIfChanged(level, new BlockPos(bx + c + x, fy, bz + c + z), rim ? moss : grass);
            if (!rim && Math.floorMod(x * 7 + z * 11, 9) == 0) {
               setIfChanged(level, new BlockPos(bx + c + x, fy + 1, bz + c + z), Blocks.MOSS_CARPET.defaultBlockState());
            }
         }
      }
      // The fire, on a hearth of dressed stone: the one thing in the room that is doing anything,
      // and the thing every other light in here is placed around.
      for (int x = -2; x <= 1; x++) {
         for (int z = -2; z <= 1; z++) {
            setIfChanged(level, new BlockPos(bx + c + x, fy, bz + c + z), Blocks.STONE_BRICKS.defaultBlockState());
         }
      }
      setIfChanged(level, new BlockPos(bx + c, fy + 1, bz + c), Blocks.CAMPFIRE.defaultBlockState());
      setIfChanged(level, new BlockPos(bx + c, top - 1, bz + c), Blocks.LANTERN.defaultBlockState());
      // A grove at the four corners of the glade: the one room in the maze with a canopy in it.
      for (int[] t : new int[][]{{-6, -6}, {6, -6}, {-6, 6}, {6, 6}}) {
         int tx = bx + c + t[0];
         int tz = bz + c + t[1];
         for (int y = 1; y <= 3; y++) {
            setIfChanged(level, new BlockPos(tx, fy + y, tz), Blocks.OAK_LOG.defaultBlockState());
         }
         for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
               if (Math.abs(dx) + Math.abs(dz) > 3) {
                  continue;
               }
               for (int y = 3; y <= 4; y++) {
                  BlockPos leaf = new BlockPos(tx + dx, fy + y, tz + dz);
                  if (level.getBlockState(leaf).isAir()) {
                     setIfChanged(level, leaf, Blocks.OAK_LEAVES.defaultBlockState());
                  }
               }
            }
         }
      }
      // The stores, stacked inside the reach of the fire's light: hay off the wagon, three barrels,
      // the crate the camp's own supplies come out of, and one ordinary chest. Not a rich one -
      // the payout of this room is the mend, and a room that pays twice is a room every run beelines
      // for instead of a room every run is glad to find.
      for (int[] h : new int[][]{{3, 1}, {-4, -2}, {2, -4}, {-2, 4}}) {
         setIfChanged(level, new BlockPos(bx + c + h[0], fy + 1, bz + c + h[1]), Blocks.HAY_BLOCK.defaultBlockState());
      }
      for (int[] b : new int[][]{{4, 0}, {4, 1}, {-4, 1}}) {
         setIfChanged(level, new BlockPos(bx + c + b[0], fy + 1, bz + c + b[1]), Blocks.BARREL.defaultBlockState());
      }
      plant(s, room, new BlockPos(bx + c + 4, fy + 1, bz + c - 5), Interact.SUPPLY, Blocks.BARREL.defaultBlockState());
      placeLootChest(level, bx + c - 5, fy + 1, bz + c + 5, s.type, false);
      // A lamp on a post at each corner of the glade, which is how a camp says where its edge is.
      for (int sx = -1; sx <= 1; sx += 2) {
         for (int sz = -1; sz <= 1; sz += 2) {
            int px = bx + c + sx * 7;
            int pz = bz + c + sz * 7;
            setIfChanged(level, new BlockPos(px, fy + 1, pz), Blocks.SPRUCE_FENCE.defaultBlockState());
            setIfChanged(level, new BlockPos(px, fy + 2, pz), Blocks.LANTERN.defaultBlockState());
         }
      }
   }

   /**
    * Moonlit Chapel: the site's beautiful room, and the one room in the maze with no job to do.
    *
    * <p>A quartz nave between two rows of pillars, candles down both aisles, a ceiling punched
    * through with its own star field and a moon over the altar, and an altar at the end of it holding
    * the best chest in the chamber. Nothing about it is novel mechanically - it is a walk and a
    * payout - and that is the point: a dungeon whose every room is a decision is a dungeon nobody
    * looks up in.
    */
   private static void buildMoonlitChapel(State s, Room room, int bx, int bz, int fy, int top) {
      ServerLevel level = s.zoneLevel;
      int c = ROOM_PITCH / 2;
      BlockState pale = Blocks.QUARTZ_BLOCK.defaultBlockState();
      // The nave: quartz, with a darker aisle running its length to the altar.
      for (int x = -8; x <= 7; x++) {
         for (int z = -8; z <= 7; z++) {
            setIfChanged(level, new BlockPos(bx + c + x, fy, bz + c + z), pale);
         }
      }
      for (int z = -8; z <= 7; z++) {
         setIfChanged(level, new BlockPos(bx + c, fy, bz + c + z), Blocks.DARK_PRISMARINE.defaultBlockState());
         setIfChanged(level, new BlockPos(bx + c - 1, fy, bz + c + z), Blocks.DARK_PRISMARINE.defaultBlockState());
      }
      // Two rows of pillars, a candle at the foot of each.
      for (int z = -7; z <= 6; z += 4) {
         for (int side = -1; side <= 1; side += 2) {
            int px = bx + c + side * 6;
            for (int y = 1; y < ROOM_HEIGHT; y++) {
               setIfChanged(
                  level, new BlockPos(px, fy + y, bz + c + z),
                  y == 1 || y == ROOM_HEIGHT - 1 ? pale : Blocks.QUARTZ_PILLAR.defaultBlockState()
               );
            }
            setIfChanged(level, new BlockPos(px - side, fy + 1, bz + c + z), Blocks.CANDLE.defaultBlockState());
         }
      }
      // Candles down both edges of the aisle.
      for (int z = -7; z <= 6; z += 3) {
         setIfChanged(level, new BlockPos(bx + c + 2, fy + 1, bz + c + z), Blocks.CANDLE.defaultBlockState());
         setIfChanged(level, new BlockPos(bx + c - 3, fy + 1, bz + c + z), Blocks.CANDLE.defaultBlockState());
      }
      // The ceiling: a star field, and a moon over the altar that the whole nave is lit by.
      for (int x = -8; x <= 7; x++) {
         for (int z = -8; z <= 7; z++) {
            if (Math.floorMod(x * 31 + z * 17, 7) == 0) {
               setIfChanged(level, new BlockPos(bx + c + x, top, bz + c + z), Blocks.GLOWSTONE.defaultBlockState());
            }
         }
      }
      for (int x = -2; x <= 1; x++) {
         for (int z = -9; z <= -8; z++) {
            setIfChanged(level, new BlockPos(bx + c + x, top, bz + c + z), Blocks.SEA_LANTERN.defaultBlockState());
         }
      }
      // The altar: a two-step quartz dais under the moon, with the chamber's own chest on it. No
      // ender chest and no lectern, deliberately - one is a way to walk a run's loot out of the
      // run, and the other opens a book interface, and a room that is here to be looked at should
      // not also be a tool.
      for (int x = -3; x <= 2; x++) {
         for (int z = -8; z <= -6; z++) {
            setIfChanged(level, new BlockPos(bx + c + x, fy + 1, bz + c + z), pale);
            setIfChanged(level, new BlockPos(bx + c + x, fy + 2, bz + c + z), pale);
         }
      }
      for (int x = -2; x <= 1; x++) {
         for (int z = -5; z <= -4; z++) {
            setIfChanged(level, new BlockPos(bx + c + x, fy + 1, bz + c + z), Blocks.CHISELED_QUARTZ_BLOCK.defaultBlockState());
         }
      }
      placeLootChest(level, bx + c, fy + 3, bz + c - 7, s.type, true);
      setIfChanged(level, new BlockPos(bx + c - 1, fy + 3, bz + c - 8), Blocks.SOUL_LANTERN.defaultBlockState());
      setIfChanged(level, new BlockPos(bx + c + 1, fy + 3, bz + c - 8), Blocks.SOUL_LANTERN.defaultBlockState());
      // A crate at the near end, so the walk down the nave is not the only thing in the room.
      plant(s, room, new BlockPos(bx + c - 4, fy + 1, bz + c + 4), Interact.SUPPLY, Blocks.BARREL.defaultBlockState());
   }

   /** Fountain court: a rimmed pool with a rich chest at the bottom of it. */
   private static void buildFountain(State s, Room room, int bx, int bz, int fy, int top) {
      ServerLevel level = s.zoneLevel;
      BlockState basin = s.type == Type.VOID ? Blocks.BLACKSTONE.defaultBlockState() : Blocks.STONE_BRICKS.defaultBlockState();
      BlockState water = Blocks.WATER.defaultBlockState();
      int c = ROOM_PITCH / 2;
      for (int x = -3; x <= 2; x++) {
         for (int z = -3; z <= 2; z++) {
            boolean rim = x == -3 || x == 2 || z == -3 || z == 2;
            setIfChanged(level, new BlockPos(bx + c + x, fy + 1, bz + c + z), rim ? basin : water);
         }
      }
      setIfChanged(level, new BlockPos(bx + c, fy + 1, bz + c - 1), Blocks.SEA_LANTERN.defaultBlockState());
      placeLootChest(level, bx + c - 1, fy + 1, bz + c, s.type, true);
   }

   /**
    * Mob den: a raised dais in the middle of the hall with the spawner in an iron cage on it.
    *
    * <p>The platform is the point. A spawner sitting on the floor of a thirty-wide hall pours
    * bodies into open ground and the fight is a circle; a spawner two blocks up a ring of stairs
    * gives the room a high ground, a chokepoint at every side of it, and somewhere for the pack
    * to be coming from rather than simply being there.
    */
   private static void buildMobDen(State s, int bx, int bz, int fy, int top) {
      ServerLevel level = s.zoneLevel;
      BlockState dais = daisBlock(s.type);
      int c = ROOM_PITCH / 2;
      int half = 3;
      for (int x = -half; x <= half; x++) {
         for (int z = -half; z <= half; z++) {
            setIfChanged(level, new BlockPos(bx + c + x, fy + 1, bz + c + z), dais);
            if (Math.max(Math.abs(x), Math.abs(z)) == half) {
               setIfChanged(level, new BlockPos(bx + c + x, fy + 2, bz + c + z), dais);
            }
         }
      }
      BlockPos spawnerPos = new BlockPos(bx + c, fy + 3, bz + c);
      setIfChanged(level, spawnerPos, Blocks.SPAWNER.defaultBlockState());
      BlockEntity be = level.getBlockEntity(spawnerPos);
      if (be instanceof net.minecraft.world.level.block.entity.SpawnerBlockEntity se) {
         se.getSpawner().setEntityId(denMobFor(s.type), level, level.getRandom(), spawnerPos);
      }
      // The cage: four bars in a ring around the spawner, with the light on top of it.
      for (int[] at : new int[][]{{-1, -1}, {-1, 1}, {1, -1}, {1, 1}}) {
         for (int y = fy + 3; y <= fy + 5; y++) {
            setIfChanged(level, new BlockPos(bx + c + at[0], y, bz + c + at[1]), Blocks.IRON_BARS.defaultBlockState());
         }
      }
      setIfChanged(level, new BlockPos(bx + c, fy + 6, bz + c), lightBlockFor(s.type));
      setIfChanged(level, new BlockPos(bx + c - 1, fy + 3, bz + c - 1), lightBlockFor(s.type));
      // Ore pockets along the den's walls: this is a chamber that pays to be cleared thoroughly.
      for (int i = WALL + 2; i < ROOM_PITCH - WALL - 1; i += 4) {
         for (int y : new int[]{fy + 2, fy + 5}) {
            Block ore = oreFor(s.type, RANDOM.nextInt(100), 1);
            if (ore != null) {
               setIfChanged(level, new BlockPos(bx + i, y, bz + WALL), ore.defaultBlockState());
               setIfChanged(level, new BlockPos(bx + WALL, y, bz + i), ore.defaultBlockState());
            }
         }
      }
   }

   /**
    * Pillar hall: a forest of columns on the floor and a ring gallery above it.
    *
    * <p>Two levels, deliberately. A flat pillared room is a room you walk across, and a room you
    * walk across is a room where the pack can only meet you head on. With a gallery the fight has
    * a vertical: the floor breaks line of sight, the balcony above it is somewhere to fall back
    * to (and somewhere to be pushed off), and the whole chamber reads as a place rather than as
    * an area with obstacles in it.
    */
   private static void buildPillarHall(State s, Room room, int bx, int bz, int fy, int top) {
      ServerLevel level = s.zoneLevel;
      Block pillar = trimBlockFor(s.type).getBlock();
      Block floorMat = daisBlock(s.type).getBlock();
      int deck = Math.min(fy + 4, top - 3);
      int rail = deck + 1;
      int depth = 3;
      for (int i = WALL + 1; i < ROOM_PITCH - WALL; i += 5) {
         for (int j = WALL + 1; j < ROOM_PITCH - WALL; j += 5) {
            for (int y = fy + 1; y <= deck; y++) {
               setIfChanged(level, new BlockPos(bx + i, y, bz + j), pillar.defaultBlockState());
            }
            if (RANDOM.nextInt(3) == 0) {
               Block ore = oreFor(s.type, RANDOM.nextInt(100), 1);
               if (ore != null) {
                  setIfChanged(level, new BlockPos(bx + i, rail, bz + j), ore.defaultBlockState());
               }
            }
         }
      }
      // The gallery: a walkway of dressed stone in a ring around the hall, one block of trim as
      // its rail, and an ore stud under every third pillar so the balcony pays for the climb.
      for (int lx = WALL; lx < ROOM_PITCH - WALL; lx++) {
         for (int lz = WALL; lz < ROOM_PITCH - WALL; lz++) {
            int edge = Math.min(
               Math.min(lx - WALL, ROOM_PITCH - WALL - 1 - lx),
               Math.min(lz - WALL, ROOM_PITCH - WALL - 1 - lz)
            );
            if (edge >= depth) {
               continue;
            }
            setIfChanged(level, new BlockPos(bx + lx, deck, bz + lz), floorMat.defaultBlockState());
            if (edge == depth - 1) {
               setIfChanged(level, new BlockPos(bx + lx, rail, bz + lz), pillar.defaultBlockState());
            }
         }
      }
      // Stairs up from two opposite corners, so the gallery is reachable without a stack of dirt.
      int[][] starts = {{WALL, WALL}, {ROOM_PITCH - WALL - 1, ROOM_PITCH - WALL - 1}};
      for (int[] at : starts) {
         for (int step = 1; step < deck - fy; step++) {
            int sx = at[0] + ((at[0] < ROOM_PITCH / 2) ? step : -step) + 3;
            int sz = at[1] + ((at[1] < ROOM_PITCH / 2) ? step : -step);
            for (int y = fy + 1; y <= fy + step; y++) {
               setIfChanged(level, new BlockPos(bx + sx, y, bz + sz), y == fy + step ? floorMat.defaultBlockState() : Blocks.AIR.defaultBlockState());
            }
         }
      }
   }

   /** Smuggler's cache: ore-lined walls, a gold floor patch and one chest. */
   private static void buildCache(State s, Room room, int bx, int bz, int fy) {
      ServerLevel level = s.zoneLevel;
      Block ore = s.type == Type.CRYSTAL_CAVERN ? Blocks.EMERALD_ORE : Blocks.GOLD_ORE;
      for (int i = WALL; i < ROOM_PITCH - WALL; i++) {
         setIfChanged(level, new BlockPos(bx + i, fy + 1, bz + WALL), ore.defaultBlockState());
         setIfChanged(level, new BlockPos(bx + i, fy + 1, bz + ROOM_PITCH - WALL - 1), ore.defaultBlockState());
         setIfChanged(level, new BlockPos(bx + WALL, fy + 1, bz + i), ore.defaultBlockState());
         setIfChanged(level, new BlockPos(bx + ROOM_PITCH - WALL - 1, fy + 1, bz + i), ore.defaultBlockState());
      }
      BlockState patch = s.type == Type.VOID ? Blocks.OBSIDIAN.defaultBlockState() : Blocks.IRON_BLOCK.defaultBlockState();
      int c = ROOM_PITCH / 2;
      for (int x = c - 1; x <= c; x++) {
         for (int z = c - 1; z <= c; z++) {
            setIfChanged(level, new BlockPos(bx + x, fy, bz + z), patch);
         }
      }
      placeLootChest(level, bx + c - 1, fy + 1, bz + c - 1, s.type, false);
   }

   /** Forgotten shrine: a blessed altar. Clearing it grants a boon and an offering. */
   private static void buildShrine(State s, int bx, int bz, int fy, int top) {
      ServerLevel level = s.zoneLevel;
      BlockState carpet = Blocks.CARPET.pick(net.minecraft.world.item.DyeColor.RED).defaultBlockState();
      int c = ROOM_PITCH / 2;
      for (int x = -4; x <= 3; x++) {
         for (int z = -4; z <= 3; z++) {
            boolean edge = x == -4 || x == 3 || z == -4 || z == 3;
            if (!edge) {
               setIfChanged(level, new BlockPos(bx + c + x, fy + 1, bz + c + z), carpet);
            }
         }
      }
      setIfChanged(level, new BlockPos(bx + c, fy + 1, bz + c), Blocks.GOLD_BLOCK.defaultBlockState());
      setIfChanged(level, new BlockPos(bx + c - 1, fy + 1, bz + c - 1), Blocks.EMERALD_BLOCK.defaultBlockState());
      setIfChanged(level, new BlockPos(bx + c - 1, fy + 1, bz + c), Blocks.CRYING_OBSIDIAN.defaultBlockState());
      setIfChanged(level, new BlockPos(bx + c, fy + 1, bz + c - 1), Blocks.CRYING_OBSIDIAN.defaultBlockState());
      setIfChanged(level, new BlockPos(bx + c - 1, fy + 2, bz + c - 1), Blocks.LODESTONE.defaultBlockState());
      setIfChanged(level, new BlockPos(bx + c - 1, top, bz + c - 1), Blocks.SHROOMLIGHT.defaultBlockState());
   }

   /** Drowned hall: rimmed pools and a prismarine floor. The tide kept the treasure. */
   private static void buildDrownedHall(State s, Room room, int bx, int bz, int fy, int top) {
      ServerLevel level = s.zoneLevel;
      BlockState floorMat = s.type == Type.SUNKEN_TEMPLE ? Blocks.PRISMARINE.defaultBlockState() : Blocks.PRISMARINE_BRICKS.defaultBlockState();
      for (int lx = WALL; lx < ROOM_PITCH - WALL; lx++) {
         for (int lz = WALL; lz < ROOM_PITCH - WALL; lz++) {
            if ((lx + lz) % 2 == 0) {
               setIfChanged(level, new BlockPos(bx + lx, fy, bz + lz), floorMat);
            }
         }
      }
      BlockState basin = Blocks.PRISMARINE_BRICKS.defaultBlockState();
      BlockState water = Blocks.WATER.defaultBlockState();
      int mid = ROOM_PITCH / 2;
      for (int[] c : new int[][]{{mid - 5, mid - 5}, {mid - 5, mid + 4}, {mid + 4, mid - 5}, {mid + 4, mid + 4}}) {
         for (int x = -2; x <= 1; x++) {
            for (int z = -2; z <= 1; z++) {
               boolean rim = x == -2 || x == 1 || z == -2 || z == 1;
               setIfChanged(level, new BlockPos(bx + c[0] + x, fy + 1, bz + c[1] + z), rim ? basin : water);
            }
         }
      }
      placeLootChest(level, bx + mid - 5, fy + 1, bz + mid - 5, s.type, true);
      setIfChanged(level, new BlockPos(bx + ROOM_PITCH / 2, top, bz + ROOM_PITCH / 2), Blocks.SEA_LANTERN.defaultBlockState());
   }

   /** Web nest: cobwebs across the floor and spiders in every one of them. */
   private static void buildWebNest(State s, int bx, int bz, int fy, int top) {
      ServerLevel level = s.zoneLevel;
      for (int lx = WALL; lx < ROOM_PITCH - WALL; lx++) {
         for (int lz = WALL; lz < ROOM_PITCH - WALL; lz++) {
            if (RANDOM.nextInt(4) == 0) {
               setIfChanged(level, new BlockPos(bx + lx, fy + 1 + RANDOM.nextInt(2), bz + lz), Blocks.COBWEB.defaultBlockState());
            }
         }
      }
      placeLootChest(level, bx + ROOM_PITCH / 2, fy + 1, bz + ROOM_PITCH / 2, s.type, true);
      setIfChanged(level, new BlockPos(bx + ROOM_PITCH / 2 - 2, top, bz + ROOM_PITCH / 2 - 2), Blocks.SHROOMLIGHT.defaultBlockState());
   }

   /**
    * Plants one interactable: writes its block and registers the position as one that answers.
    *
    * <p>The registration is the point. A hundred rooms are built out of the same dozen blocks, so
    * "right-click this and something happens" cannot be decided by looking at what the block is -
    * only by remembering which positions the dungeon meant.
    */
   private static void plant(State s, Room room, BlockPos at, Interact kind, BlockState state) {
      setIfChanged(s.zoneLevel, at, state);
      s.props.put(at, kind);
      if (room != null) {
         room.props.add(at);
      }
   }

   /**
    * Forge hall: a crucible of fire ringed in brick, benches around it, ore in the walls.
    *
    * <p>The one chamber that fights back without a pack in it: the well is two paces of lava
    * with a rim you can be pushed over, and everything worth taking in here is on the far side
    * of it.
    */
   private static void buildForgeHall(State s, Room room, int bx, int bz, int fy, int top) {
      ServerLevel level = s.zoneLevel;
      BlockState rim = Blocks.NETHER_BRICKS.defaultBlockState();
      int c = ROOM_PITCH / 2;
      // The well: bedrock-cut, so nothing the player or a blast does can drain it into the maze.
      for (int x = -3; x <= 2; x++) {
         for (int z = -3; z <= 2; z++) {
            boolean edge = x == -3 || x == 2 || z == -3 || z == 2;
            BlockPos at = new BlockPos(bx + c + x, fy, bz + c + z);
            if (edge) {
               setIfChanged(level, at, rim);
               // A lip, not just a rim: a source block of lava with air beside it at the same
               // level runs out over the edge, and a forge that pours into the hall is a forge
               // nobody can fight in. One block of brick above the rim contains it.
               setIfChanged(level, at.above(), rim);
               continue;
            }
            setIfChanged(level, at, Blocks.MAGMA_BLOCK.defaultBlockState());
            setIfChanged(level, at.above(), Blocks.LAVA.defaultBlockState());
         }
      }
      setIfChanged(level, new BlockPos(bx + c, top - 1, bz + c), lightBlockFor(s.type));
      // Benches against the four inner corners, and a blast furnace on each: the foundry's tools.
      for (int[] at : new int[][]{{WALL + 2, WALL + 2}, {WALL + 2, ROOM_PITCH - WALL - 3},
            {ROOM_PITCH - WALL - 3, WALL + 2}, {ROOM_PITCH - WALL - 3, ROOM_PITCH - WALL - 3}}) {
         if (inDoorLane(room, at[0], at[1])) {
            continue;
         }
         setIfChanged(level, new BlockPos(bx + at[0], fy + 1, bz + at[1]), Blocks.BLAST_FURNACE.defaultBlockState());
         setIfChanged(level, new BlockPos(bx + at[0], fy + 2, bz + at[1]), Blocks.IRON_BLOCK.defaultBlockState());
      }
      // And an ore face in the walls of this hall, hot from the stone it came out of.
      for (int i = WALL + 2; i < ROOM_PITCH - WALL - 2; i += 5) {
         if (inDoorLane(room, i, WALL) || inDoorLane(room, i, ROOM_PITCH - WALL - 1)) {
            continue;
         }
         for (int y : new int[]{fy + 2, fy + 5}) {
            setIfChanged(level, new BlockPos(bx + i, y, bz + WALL), Blocks.NETHER_QUARTZ_ORE.defaultBlockState());
            setIfChanged(level, new BlockPos(bx + i, y, bz + ROOM_PITCH - WALL - 1), Blocks.NETHER_GOLD_ORE.defaultBlockState());
         }
      }
      placeLootChest(level, bx + c - 4, fy + 1, bz + c - 4, s.type, true);
      placeLootChest(level, bx + c + 3, fy + 1, bz + c + 3, s.type, true);
      plant(s, room, new BlockPos(bx + WALL + 2, fy + 1, bz + c), Interact.SUPPLY, Blocks.BLAST_FURNACE.defaultBlockState());
   }

   /**
    * Broken crossing: the floor fell in across the middle of the hall, and the way over is junk.
    *
    * <p>A gap you can walk around is scenery. This one runs wall to wall, so the two halves of the
    * chamber are two fights, and the only road between them is the wreckage - which is also where
    * you want to be standing when the pack finds you.
    */
   private static void buildBrokenCrossing(State s, Room room, int bx, int bz, int fy, int top) {
      ServerLevel level = s.zoneLevel;
      int c = ROOM_PITCH / 2;
      int w = 5;
      for (int lx = WALL; lx < ROOM_PITCH - WALL; lx++) {
         for (int lz = c; lz < c + w; lz++) {
            setIfChanged(level, new BlockPos(bx + lx, fy, bz + lz), Blocks.AIR.defaultBlockState());
            if (RANDOM.nextInt(5) == 0) {
               // The wreckage: a step you can make it across on, one block at a time.
               setIfChanged(level, new BlockPos(bx + lx, fy, bz + lz), daisBlock(s.type));
            }
         }
      }
      for (int[] at : new int[][]{{WALL + 1, c}, {WALL + 1, c + w - 1},
            {ROOM_PITCH - WALL - 2, c}, {ROOM_PITCH - WALL - 2, c + w - 1}}) {
         for (int y = fy + 1; y <= top - 1; y++) {
            setIfChanged(level, new BlockPos(bx + at[0], y, bz + at[1]), trimBlockFor(s.type));
         }
      }
      // A rich chest on each bank, so crossing pays twice, and a supply crate by the near one.
      placeLootChest(level, bx + WALL + 2, fy + 1, bz + c - 3, s.type, true);
      placeLootChest(level, bx + ROOM_PITCH - WALL - 3, fy + 1, bz + c + w + 2, s.type, true);
      plant(
         s, room,
         new BlockPos(bx + WALL + 2, fy + 1, bz + c - 5),
         Interact.SUPPLY, Blocks.BARREL.defaultBlockState()
      );
   }

   /**
    * Gallery of echoes: a colonnade of pedestals with the collection left on them.
    *
    * <p>Built as two rows facing each other down a central aisle, which is a shape no other
    * chamber in the maze has - and the reason a player can tell they have been here before.
    */
   private static void buildGallery(State s, Room room, int bx, int bz, int fy, int top) {
      ServerLevel level = s.zoneLevel;
      int c = ROOM_PITCH / 2;
      BlockState pedestal = trimBlockFor(s.type);
      BlockState caseStone = s.type == Type.VOID ? Blocks.OBSIDIAN.defaultBlockState() : Blocks.GLASS.defaultBlockState();
      for (int lz = WALL + 3; lz < ROOM_PITCH - WALL - 3; lz += 3) {
         for (int lx : new int[]{WALL + 4, ROOM_PITCH - WALL - 5}) {
            if (inDoorLane(room, lx, lz)) {
               continue;
            }
            setIfChanged(level, new BlockPos(bx + lx, fy + 1, bz + lz), pedestal);
            setIfChanged(level, new BlockPos(bx + lx, fy + 2, bz + lz), caseStone);
            setIfChanged(level, new BlockPos(bx + lx, fy + 3, bz + lz), Blocks.LANTERN.defaultBlockState());
         }
      }
      // The centrepiece on its plinth, under the room's own light.
      for (int x = -1; x <= 0; x++) {
         for (int z = -1; z <= 0; z++) {
            setIfChanged(level, new BlockPos(bx + c + x, fy + 1, bz + c + z), pedestal);
         }
      }
      placeLootChest(level, bx + c, fy + 2, bz + c, s.type, true);
      placeLootChest(level, bx + c - 1, fy + 2, bz + c - 1, s.type, false);
      setIfChanged(level, new BlockPos(bx + c, top - 1, bz + c), lightBlockFor(s.type));
      plant(
         s, room,
         new BlockPos(bx + c + 3, fy + 1, bz + c + 3),
         Interact.RELIQUARY, Blocks.CHISELED_DEEPSLATE.defaultBlockState()
      );
   }

   /**
    * Wager vault: a coin floor, a pedestal you can bet the run on, and a rune lock behind it.
    *
    * <p>The chamber with no combat in it at all - its whole point is that it is a decision. The
    * stake is secured loot, the odds are honest, and the rune stones are the vault's own lock:
    * light all four and the strongbox behind them opens.
    */
   private static void buildWagerVault(State s, Room room, int bx, int bz, int fy, int top) {
      ServerLevel level = s.zoneLevel;
      int c = ROOM_PITCH / 2;
      for (int lx = WALL; lx < ROOM_PITCH - WALL; lx++) {
         for (int lz = WALL; lz < ROOM_PITCH - WALL; lz++) {
            if (inDoorLane(room, lx, lz)) {
               continue;
            }
            setIfChanged(
               level, new BlockPos(bx + lx, fy, bz + lz),
               (lx + lz) % 2 == 0 ? Blocks.GOLD_BLOCK.defaultBlockState() : Blocks.POLISHED_BLACKSTONE.defaultBlockState()
            );
         }
      }
      // The pedestal: a gold block on a blackstone plinth, and it answers a right-click.
      setIfChanged(level, new BlockPos(bx + c, fy + 1, bz + c), Blocks.POLISHED_BLACKSTONE.defaultBlockState());
      plant(s, room, new BlockPos(bx + c, fy + 2, bz + c), Interact.WAGER, Blocks.GOLD_BLOCK.defaultBlockState());
      setIfChanged(level, new BlockPos(bx + c, top - 1, bz + c), lightBlockFor(s.type));
      // The rune stones: one per corner of the room, lit in any order.
      int[][] runeAt = {{c - 7, c - 7}, {c + 6, c - 7}, {c - 7, c + 6}, {c + 6, c + 6}};
      for (int[] at : runeAt) {
         if (inDoorLane(room, at[0], at[1])) {
            continue;
         }
         for (int y = fy + 1; y <= fy + 3; y++) {
            setIfChanged(level, new BlockPos(bx + at[0], y, bz + at[1]), Blocks.POLISHED_BLACKSTONE.defaultBlockState());
         }
         plant(s, room, new BlockPos(bx + at[0], fy + 4, bz + at[1]), Interact.RUNE, Blocks.CRYING_OBSIDIAN.defaultBlockState());
         room.runesTotal++;
      }
      // The strongbox the runes keep: sealed until every stone is lit, so it is not looted early.
      for (int x = -1; x <= 0; x++) {
         for (int z = c + 6; z <= c + 7; z++) {
            setIfChanged(level, new BlockPos(bx + c + x, fy + 1, bz + z), Blocks.IRON_BLOCK.defaultBlockState());
         }
      }
      placeLootChest(level, bx + c, fy + 2, bz + c + 6, s.type, true);
      placeLootChest(level, bx + c - 1, fy + 2, bz + c + 7, s.type, true);
   }

   /**
    * Oubliette: two rows of barred cells opening onto the hall.
    *
    * <p>Every cell is open on the side the hall can see, so a prisoner is never a body behind
    * bars the pack could leave standing and the chamber could never clear. What the bars buy is
    * shape: four walls of them make a fighting ring the pack has to come around while you hold
    * the corridor between the rows.
    */
   private static void buildOubliette(State s, Room room, int bx, int bz, int fy, int top) {
      ServerLevel level = s.zoneLevel;
      BlockState bars = Blocks.IRON_BARS.defaultBlockState();
      int w = 4;
      int depth = 4;
      // Cells along the north wall, and the same along the west wall.
      for (int i = WALL + 2; i + w <= ROOM_PITCH - WALL - 2; i += w + 1) {
         if (inDoorLane(room, i, WALL) || inDoorLane(room, i + w, WALL)) {
            continue;
         }
         for (int j = 1; j <= depth; j++) {
            for (int y = fy + 1; y <= fy + 4; y++) {
               setIfChanged(level, new BlockPos(bx + i, y, bz + WALL + j), bars);
               setIfChanged(level, new BlockPos(bx + i + w, y, bz + WALL + j), bars);
            }
         }
         for (int k = 0; k <= w; k++) {
            for (int y = fy + 1; y <= fy + 4; y++) {
               setIfChanged(level, new BlockPos(bx + i + k, y, bz + WALL + depth), bars);
            }
         }
         setIfChanged(level, new BlockPos(bx + i + 2, fy + 1, bz + WALL + 1), Blocks.CAULDRON.defaultBlockState());
         placeLootChest(level, bx + i + 1, fy + 1, bz + WALL + 2, s.type, false);
      }
      for (int i = WALL + 2; i + w <= ROOM_PITCH - WALL - 2; i += w + 1) {
         if (inDoorLane(room, WALL, i) || inDoorLane(room, WALL, i + w)) {
            continue;
         }
         for (int j = 1; j <= depth; j++) {
            for (int y = fy + 1; y <= fy + 4; y++) {
               setIfChanged(level, new BlockPos(bx + WALL + j, y, bz + i), bars);
               setIfChanged(level, new BlockPos(bx + WALL + j, y, bz + i + w), bars);
            }
         }
         for (int k = 0; k <= w; k++) {
            for (int y = fy + 1; y <= fy + 4; y++) {
               setIfChanged(level, new BlockPos(bx + WALL + depth, y, bz + i + k), bars);
            }
         }
         placeLootChest(level, bx + WALL + 2, fy + 1, bz + i + 1, s.type, false);
      }
      // The gaoler's table in the middle of the block, with the key the cells never needed.
      int c = ROOM_PITCH / 2;
      for (int x = -1; x <= 0; x++) {
         for (int z = -1; z <= 0; z++) {
            setIfChanged(level, new BlockPos(bx + c + x, fy + 1, bz + c + z), trimBlockFor(s.type));
         }
      }
      setIfChanged(level, new BlockPos(bx + c, fy + 2, bz + c), Blocks.ANVIL.defaultBlockState());
      setIfChanged(level, new BlockPos(bx + c, top - 1, bz + c), lightBlockFor(s.type));
      placeLootChest(level, bx + c - 1, fy + 2, bz + c - 1, s.type, true);
      plant(
         s, room,
         new BlockPos(bx + c + 1, fy + 1, bz + c + 1),
         Interact.RELIQUARY, Blocks.GILDED_BLACKSTONE.defaultBlockState()
      );
   }

   /**
    * Ruined larder: barrel shelves, hanging stores and a cook's bench.
    *
    * <p>Laid out on the same three-pace grid as the ceiling, so the stacks look like something
    * that was arranged once rather than dropped. Two paces of aisle between every stack keeps the
    * room walkable - a larder is dense, not impassable.
    */
   private static void buildLarder(State s, Room room, int bx, int bz, int fy, int top) {
      ServerLevel level = s.zoneLevel;
      for (int lz = WALL + 2; lz < ROOM_PITCH - WALL - 1; lz += 3) {
         for (int lx = WALL + 2; lx < ROOM_PITCH - WALL - 1; lx += 3) {
            if (inDoorLane(room, lx, lz) || inDoorLane(room, lx + 1, lz) || inDoorLane(room, lx, lz + 1)) {
               continue;
            }
            setIfChanged(level, new BlockPos(bx + lx, fy + 1, bz + lz), Blocks.BARREL.defaultBlockState());
            setIfChanged(level, new BlockPos(bx + lx, fy + 2, bz + lz), Blocks.BARREL.defaultBlockState());
            if ((lx + lz) % 2 == 0) {
               setIfChanged(level, new BlockPos(bx + lx, fy + 3, bz + lz), Blocks.HAY_BLOCK.defaultBlockState());
               setIfChanged(level, new BlockPos(bx + lx, top - 2, bz + lz), Blocks.IRON_CHAIN.defaultBlockState());
            }
         }
      }
      int c = ROOM_PITCH / 2;
      setIfChanged(level, new BlockPos(bx + c, fy + 1, bz + c), Blocks.SMOKER.defaultBlockState());
      setIfChanged(level, new BlockPos(bx + c - 1, fy + 1, bz + c), Blocks.CAULDRON.defaultBlockState());
      setIfChanged(level, new BlockPos(bx + c + 1, fy + 1, bz + c), Blocks.CRAFTING_TABLE.defaultBlockState());
      setIfChanged(level, new BlockPos(bx + c, top - 1, bz + c), lightBlockFor(s.type));
      placeLootChest(level, bx + c, fy + 1, bz + c - 2, s.type, false);
      placeLootChest(level, bx + c - 2, fy + 1, bz + c + 2, s.type, true);
      plant(
         s, room,
         new BlockPos(bx + ROOM_PITCH - WALL - 3, fy + 1, bz + c),
         Interact.SUPPLY, Blocks.BARREL.defaultBlockState()
      );
   }

   /**
    * Sunken garden: moss underfoot, a water channel down the middle and a grove that outgrew it.
    *
    * <p>The one chamber in the maze with something alive in it that is not trying to kill you -
    * which is exactly why it is worth walking into, and why the water is where the chest is.
    */
   private static void buildGarden(State s, Room room, int bx, int bz, int fy, int top) {
      ServerLevel level = s.zoneLevel;
      int c = ROOM_PITCH / 2;
      for (int lx = WALL; lx < ROOM_PITCH - WALL; lx++) {
         for (int lz = WALL; lz < ROOM_PITCH - WALL; lz++) {
            if (lx == c || lx == c - 1) {
               continue;
            }
            setIfChanged(level, new BlockPos(bx + lx, fy, bz + lz), Blocks.GRASS_BLOCK.defaultBlockState());
            setIfChanged(level, new BlockPos(bx + lx, fy + 1, bz + lz), Blocks.AIR.defaultBlockState());
         }
      }
      // The channel runs the length of the hall and turns the floor into two banks.
      for (int lz = WALL; lz < ROOM_PITCH - WALL; lz++) {
         for (int lx = c - 1; lx <= c; lx++) {
            BlockPos at = new BlockPos(bx + lx, fy, bz + lz);
            if (level.getBlockState(at).is(Blocks.GRASS_BLOCK)) {
               level.setBlock(at, Blocks.DIRT.defaultBlockState(), 2);
            }
         }
      }
      for (int lz = c - 5; lz <= c + 5; lz++) {
         for (int lx = c - 1; lx <= c; lx++) {
            setIfChanged(level, new BlockPos(bx + lx, fy, bz + lz), Blocks.WATER.defaultBlockState());
         }
      }
      // A small grove on each bank: trunk, canopy, and moss at the root.
      for (int[] at : new int[][]{{c - 7, c - 7}, {c + 6, c - 7}, {c - 7, c + 6}, {c + 6, c + 6}}) {
         if (inDoorLane(room, at[0], at[1])) {
            continue;
         }
         for (int y = fy + 1; y <= fy + 4; y++) {
            setIfChanged(level, new BlockPos(bx + at[0], y, bz + at[1]), Blocks.OAK_LOG.defaultBlockState());
         }
         for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
               if (Math.abs(x) == 2 && Math.abs(z) == 2) {
                  continue;
               }
               setIfChanged(level, new BlockPos(bx + at[0] + x, fy + 5, bz + at[1] + z), Blocks.OAK_LEAVES.defaultBlockState());
            }
         }
         for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
               setIfChanged(level, new BlockPos(bx + at[0] + x, fy + 6, bz + at[1] + z), Blocks.OAK_LEAVES.defaultBlockState());
            }
         }
         setIfChanged(level, new BlockPos(bx + at[0], fy + 1, bz + at[1] + 1), Blocks.MOSS_BLOCK.defaultBlockState());
      }
      placeLootChest(level, bx + c, fy, bz + c, s.type, true);
      placeLootChest(level, bx + c - 1, fy, bz + c - 1, s.type, false);
      plant(
         s, room,
         new BlockPos(bx + c - 3, fy + 1, bz + c - 3),
         Interact.SUPPLY, Blocks.MOSS_BLOCK.defaultBlockState()
      );
   }

   /**
    * Fungal grotto: a moss floor under mushroom caps that have gone on growing.
    *
    * <p>Audible in the dark, too: the caps put their own light in the room, so the one chamber
    * that is grown rather than built is also the one you can see into from the doorway.
    */
   private static void buildFungalGrotto(State s, Room room, int bx, int bz, int fy, int top) {
      ServerLevel level = s.zoneLevel;
      int c = ROOM_PITCH / 2;
      for (int lx = WALL; lx < ROOM_PITCH - WALL; lx++) {
         for (int lz = WALL; lz < ROOM_PITCH - WALL; lz++) {
            if (RANDOM.nextInt(3) == 0) {
               setIfChanged(level, new BlockPos(bx + lx, fy, bz + lz), Blocks.MYCELIUM.defaultBlockState());
            } else if (RANDOM.nextInt(4) == 0) {
               setIfChanged(level, new BlockPos(bx + lx, fy, bz + lz), Blocks.MOSS_BLOCK.defaultBlockState());
            }
         }
      }
      // Caps on stalks, of two sizes, at a handful of spots: the grotto's own lanterns.
      for (int i = 0; i < 7; i++) {
         int px = WALL + 2 + RANDOM.nextInt(ROOM_PITCH - 2 * WALL - 4);
         int pz = WALL + 2 + RANDOM.nextInt(ROOM_PITCH - 2 * WALL - 4);
         if (inDoorLane(room, px, pz) || inDoorLane(room, px + 1, pz) || inDoorLane(room, px, pz + 1)) {
            continue;
         }
         int h = 2 + RANDOM.nextInt(2);
         for (int y = fy + 1; y <= fy + h; y++) {
            setIfChanged(level, new BlockPos(bx + px, y, bz + pz), Blocks.MUSHROOM_STEM.defaultBlockState());
         }
         boolean red = RANDOM.nextBoolean();
         Block cap = red ? Blocks.RED_MUSHROOM_BLOCK : Blocks.BROWN_MUSHROOM_BLOCK;
         for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
               if (Math.abs(x) == 1 && Math.abs(z) == 1) {
                  continue;
               }
               setIfChanged(level, new BlockPos(bx + px + x, fy + h + 1, bz + pz + z), cap.defaultBlockState());
            }
         }
         setIfChanged(level, new BlockPos(bx + px, fy + h, bz + pz), Blocks.SHROOMLIGHT.defaultBlockState());
      }
      for (int y = fy + 1; y <= fy + 2; y++) {
         setIfChanged(level, new BlockPos(bx + c, y, bz + c), Blocks.SHROOMLIGHT.defaultBlockState());
      }
      placeLootChest(level, bx + c - 1, fy + 1, bz + c - 1, s.type, true);
      placeLootChest(level, bx + c + 1, fy + 1, bz + c + 1, s.type, false);
      plant(
         s, room,
         new BlockPos(bx + c + 2, fy + 1, bz + c - 2),
         Interact.RELIQUARY, Blocks.BROWN_MUSHROOM_BLOCK.defaultBlockState()
      );
   }

   /**
    * Sunken library: aisles of bookshelves with a reading dais at the middle of them.
    *
    * <p>Shelves run in rows with a walking aisle between them. Free-standing stacks across a whole
    * hall are a field of collision with no way through it; the point of a library is that you can
    * read the room from one end of it.
    */
   private static void buildLibrary(State s, Room room, int bx, int bz, int fy, int top) {
      ServerLevel level = s.zoneLevel;
      int c = ROOM_PITCH / 2;
      for (int lz = WALL + 2; lz <= ROOM_PITCH - WALL - 3; lz += 4) {
         for (int lx = WALL + 2; lx <= ROOM_PITCH - WALL - 3; lx++) {
            if (inDoorLane(room, lx, lz) || inDoorLane(room, lx, lz + 1)) {
               continue;
            }
            if (Math.abs(lx - c) <= 3 && Math.abs(lz - c) <= 3) {
               continue;
            }
            for (int y = fy + 1; y <= fy + 2; y++) {
               setIfChanged(level, new BlockPos(bx + lx, y, bz + lz), Blocks.BOOKSHELF.defaultBlockState());
            }
            if (lx % COFFER == 0) {
               setIfChanged(level, new BlockPos(bx + lx, top - 1, bz + lz), Blocks.LANTERN.defaultBlockState());
            }
         }
      }
      for (int x = -2; x <= 1; x++) {
         for (int z = -2; z <= 1; z++) {
            setIfChanged(level, new BlockPos(bx + c + x, fy + 1, bz + c + z), daisBlock(s.type));
         }
      }
      setIfChanged(level, new BlockPos(bx + c, fy + 2, bz + c), Blocks.LECTERN.defaultBlockState());
      placeLootChest(level, bx + c - 2, fy + 2, bz + c - 2, s.type, true);
      placeLootChest(level, bx + c + 1, fy + 2, bz + c + 1, s.type, false);
      // The catalogue itself: a reliquary in the corner of the reading room, and it is trapped.
      plant(
         s, room,
         new BlockPos(bx + WALL + 3, fy + 1, bz + ROOM_PITCH - WALL - 4),
         Interact.RELIQUARY, Blocks.GILDED_BLACKSTONE.defaultBlockState()
      );
   }

   /**
    * Reader's Rest: the reading room, and the site's second camp.
    *
    * <p>It is built to be recognised from the doorway - plank floor, carpet underfoot, shelves from
    * floor to ceiling and a table in the middle of them - because the whole value of a rest site is
    * that a hurt explorer can tell what it is without walking into it. The walls are shelves all the
    * way round, which is what makes the room bright: a library is the one chamber in the maze that
    * has no business being lit by a lantern.
    *
    * <p>The loot is a barrel of enchanted books rather than a chest of coins. A camp that pays out
    * its value in the run's currency would be a treasury with a bed in it; a camp that pays out in
    * something you can only spend on the way home is a reason to keep going.
    */
   private static void buildRestLibrary(State s, Room room, int bx, int bz, int fy, int top) {
      ServerLevel level = s.zoneLevel;
      int c = ROOM_PITCH / 2;
      // The floor is boards, not rock: the one chamber that does not sound like the others.
      for (int lx = WALL; lx < ROOM_PITCH - WALL; lx++) {
         for (int lz = WALL; lz < ROOM_PITCH - WALL; lz++) {
            setIfChanged(level, new BlockPos(bx + lx, fy, bz + lz), Blocks.OAK_PLANKS.defaultBlockState());
         }
      }
      // The walls, inside face, become shelves.
      for (int lx = WALL; lx < ROOM_PITCH - WALL; lx++) {
         for (int y = fy + 1; y <= fy + 3; y++) {
            for (int lz : new int[]{WALL, ROOM_PITCH - WALL - 1}) {
               if (inDoorLane(room, lx, lz) || inDoorLane(room, lx, lz) && y == fy + 1) {
                  continue;
               }
               setIfChanged(level, new BlockPos(bx + lx, y, bz + lz), Blocks.BOOKSHELF.defaultBlockState());
            }
         }
      }
      for (int lz = WALL; lz < ROOM_PITCH - WALL; lz++) {
         for (int y = fy + 1; y <= fy + 3; y++) {
            for (int lx : new int[]{WALL, ROOM_PITCH - WALL - 1}) {
               if (inDoorLane(room, lx, lz)) {
                  continue;
               }
               setIfChanged(level, new BlockPos(bx + lx, y, bz + lz), Blocks.BOOKSHELF.defaultBlockState());
            }
         }
      }
      // Carpet, laid in a rug down the middle and in squares at the reading corners.
      for (int i = -5; i <= 5; i++) {
         BlockState rug = Blocks.CARPET.pick(i % 2 == 0 ? net.minecraft.world.item.DyeColor.RED : net.minecraft.world.item.DyeColor.WHITE)
            .defaultBlockState();
         setIfChanged(level, new BlockPos(bx + c + i, fy + 1, bz + c), rug);
         setIfChanged(level, new BlockPos(bx + c, fy + 1, bz + c + i), rug);
      }
      for (int[] corner : new int[][]{{-8, -8}, {-8, 8}, {8, -8}, {8, 8}}) {
         for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
               setIfChanged(
                  level, new BlockPos(bx + c + corner[0] + dx, fy + 1, bz + c + corner[1] + dz),
                  Blocks.CARPET.pick(net.minecraft.world.item.DyeColor.RED).defaultBlockState()
               );
            }
         }
      }
      // The reading dais: shelves in a ring around a table, which is what the room is for.
      for (int dx = -2; dx <= 2; dx++) {
         for (int dz = -2; dz <= 2; dz++) {
            boolean ring = Math.abs(dx) == 2 || Math.abs(dz) == 2;
            setIfChanged(
               level, new BlockPos(bx + c + dx, fy + 1, bz + c + dz),
               ring ? Blocks.BOOKSHELF.defaultBlockState() : Blocks.DARK_OAK_PLANKS.defaultBlockState()
            );
         }
      }
      setIfChanged(level, new BlockPos(bx + c, fy + 1, bz + c), Blocks.ENCHANTING_TABLE.defaultBlockState());
      for (int dx = -3; dx <= 3; dx++) {
         setIfChanged(level, new BlockPos(bx + c + dx, fy + 4, bz + c - 3), Blocks.LANTERN.defaultBlockState());
         setIfChanged(level, new BlockPos(bx + c + dx, fy + 4, bz + c + 3), Blocks.LANTERN.defaultBlockState());
      }
      // The camp's own promise, made visible: the room glows, so the doorway glows.
      setIfChanged(level, new BlockPos(bx + c, top - 1, bz + c), Blocks.SHROOMLIGHT.defaultBlockState());
      placeLootChest(level, bx + c - 3, fy + 1, bz + c + 3, s.type, true);
      placeLootChest(level, bx + c + 3, fy + 1, bz + c - 3, s.type, true);
      placeLootChest(level, bx + c + 3, fy + 1, bz + c + 3, s.type, false);
      barrelOfBooks(s, level, new BlockPos(bx + c - 3, fy + 1, bz + c - 3));
      barrelOfBooks(s, level, new BlockPos(bx + c + 3, fy + 1, bz + c - 2));
      plant(s, room, new BlockPos(bx + WALL + 2, fy + 1, bz + c - 2), Interact.SUPPLY, Blocks.BARREL.defaultBlockState());
   }

   /**
    * A barrel of enchanted books, filled at build time.
    *
    * <p>Straight off the world's own enchantment registry rather than from a hand-written list, so
    * the room keeps working when a datapack adds one - and clamped to each enchantment's own maximum
    * level, because an over-levelled book is a bug that only shows up later, in an anvil.
    */
   private static void barrelOfBooks(State s, ServerLevel level, BlockPos at) {
      setIfChanged(level, at, Blocks.BARREL.defaultBlockState());
      try {
         if (level.getBlockEntity(at) instanceof net.minecraft.world.Container barrel) {
            int books = 2 + RANDOM.nextInt(2);
            for (int i = 0; i < books; i++) {
               barrel.setItem(i + 1, randomEnchantedBook(level));
            }
         }
      } catch (Throwable ignored) {
      }
   }

   /** One enchanted book, chosen from the world's enchantment registry. A plain book if that fails. */
   public static ItemStack randomEnchantedBook(ServerLevel level) {
      ItemStack book = new ItemStack(Items.ENCHANTED_BOOK);
      try {
         var lookup = level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT);
         List<net.minecraft.core.Holder.Reference<net.minecraft.world.item.enchantment.Enchantment>> all =
            lookup.listElements().toList();
         if (all.isEmpty()) {
            return book;
         }
         var holder = all.get(RANDOM.nextInt(all.size()));
         int max = Math.max(1, holder.value().getMaxLevel());
         int lvl = 1 + RANDOM.nextInt(Math.min(max, 3));
         var mut = new net.minecraft.world.item.enchantment.ItemEnchantments.Mutable(
            net.minecraft.world.item.enchantment.ItemEnchantments.EMPTY
         );
         mut.set(holder, Math.max(1, Math.min(max, lvl)));
         book.set(DataComponents.STORED_ENCHANTMENTS, mut.toImmutable());
      } catch (Throwable ignored) {
      }
      return book;
   }

   /**
    * Armoury: a wall of racks under iron bars and a smith's bench in the middle.
    *
    * <p>The racks are iron blocks under bars, so the room reads as a place where weapons were
    * kept and never taken - the bars are why the wall is still full.
    */
   private static void buildArmoury(State s, Room room, int bx, int bz, int fy, int top) {
      ServerLevel level = s.zoneLevel;
      BlockState rack = Blocks.IRON_BLOCK.defaultBlockState();
      BlockState bars = Blocks.IRON_BARS.defaultBlockState();
      for (int lx = WALL + 2; lx < ROOM_PITCH - WALL - 2; lx += 3) {
         if (inDoorLane(room, lx, WALL) || inDoorLane(room, lx, ROOM_PITCH - WALL - 1)) {
            continue;
         }
         for (int y : new int[]{fy + 2, fy + 4}) {
            setIfChanged(level, new BlockPos(bx + lx, y, bz + WALL), rack);
            setIfChanged(level, new BlockPos(bx + lx, y + 1, bz + WALL), bars);
            setIfChanged(level, new BlockPos(bx + lx, y, bz + ROOM_PITCH - WALL - 1), rack);
            setIfChanged(level, new BlockPos(bx + lx, y + 1, bz + ROOM_PITCH - WALL - 1), bars);
         }
      }
      int c = ROOM_PITCH / 2;
      for (int x = -3; x <= 2; x++) {
         for (int z = -1; z <= 0; z++) {
            setIfChanged(level, new BlockPos(bx + c + x, fy + 1, bz + c + z), trimBlockFor(s.type));
         }
      }
      setIfChanged(level, new BlockPos(bx + c - 2, fy + 2, bz + c), Blocks.ANVIL.defaultBlockState());
      setIfChanged(level, new BlockPos(bx + c, fy + 2, bz + c), Blocks.GRINDSTONE.defaultBlockState());
      setIfChanged(level, new BlockPos(bx + c + 2, fy + 2, bz + c - 1), Blocks.SMITHING_TABLE.defaultBlockState());
      setIfChanged(level, new BlockPos(bx + c, top - 1, bz + c), lightBlockFor(s.type));
      placeLootChest(level, bx + c - 2, fy + 2, bz + c, s.type, true);
      placeLootChest(level, bx + c + 1, fy + 2, bz + c, s.type, false);
      plant(s, room, new BlockPos(bx + WALL + 2, fy + 1, bz + c), Interact.SUPPLY, Blocks.BARREL.defaultBlockState());
   }

   /**
    * The guardian's arena at the size it wants to be: a three-by-three block of maze cells with
    * every wall inside it taken out.
    *
    * <p>The arena was one cell, and one cell is twenty-four blocks of floor with a pit in the middle
    * of it: a room the guardian crosses in two seconds and a fight with nowhere in it to be. So the
    * arena is now the nine cells around the one it was rolled in. The eight around it are reserved
    * the moment it is rolled - the maze is not allowed to build a chamber in any of them - and that
    * reservation is what makes the room possible at all, because the walls between those cells are
    * never written. The pit, the terrace and the colonnade run their full eighty-four blocks instead
    * of being cut into nine boxes by eight walls.
    *
    * <p>And the doors move to the edge, which is the other half of what this is for. The ring cells
    * are chambers as far as the maze is concerned: each keeps its own archways, and each stands at
    * the edge of the block. So the way in and the way out are on the rim and the middle is the
    * fight - an explorer who does not want him can cross the block along the terrace without ever
    * stepping down into the pit, and the doors only close on the one who has chosen to.
    *
    * @return the arena, or null when the eight cells around it are already spoken for and the
    *     cell-sized arena has to do
    */
   private static Room buildWideArena(State s, int rx, int rz, int depth) {
      for (int dx = -1; dx <= 1; dx++) {
         for (int dz = -1; dz <= 1; dz++) {
            if (s.rooms.containsKey(roomKey(rx + dx, rz + dz))) {
               return null;
            }
         }
      }
      int ox = baseX(s, rx - 1);
      int oz = baseZ(s, rz - 1);
      int fy = floorY(s);
      int arenaTop = fy + ARENA_HEIGHT;
      Room room = new Room(rx, rz, depth, Chamber.FLOOR_BOSS);
      Arena arena = new Arena(rx, rz, room);
      s.arenas.put(roomKey(rx, rz), arena);
      // The shell of the whole block first, then the fighting floor inside it - and then the ring
      // cells, which are what puts the doors on the edge.
      buildArenaShell(s, ox, oz, fy, arenaTop);
      buildArenaInterior(s, room, ox, oz, fy, arenaTop);
      s.rooms.put(roomKey(rx, rz), room);
      for (int dx = -1; dx <= 1; dx++) {
         for (int dz = -1; dz <= 1; dz++) {
            if (dx == 0 && dz == 0) {
               continue;
            }
            // Born cleared, and for a reason: there is nothing in a rim cell to fight, and a cell
            // that could be cleared would open three doorways of its own into the middle of the
            // arena and pay a bounty for the privilege.
            Room ring = new Room(rx + dx, rz + dz, depth, Chamber.FLOOR_BOSS);
            ring.cleared = true;
            ring.visited = true;
            s.rooms.put(roomKey(rx + dx, rz + dz), ring);
            arena.rings.put(roomKey(rx + dx, rz + dz), ring);
         }
      }
      return room;
   }

   /**
    * The arena's shell: bedrock, floor and a coffered roof over the whole block, and the four walls
    * of it.
    *
    * <p>What this deliberately does not build is the eight walls between the nine cells - taking
    * them out is the entire reason the arena is three cells across. Nothing of the ordinary chamber
    * builder runs in here either: no dressing, no floor ore, no condition, because the arena's
    * floor is the fight and its architecture is what the fight is fought around.
    */
   private static void buildArenaShell(State s, int ox, int oz, int fy, int arenaTop) {
      ServerLevel level = s.zoneLevel;
      BlockState wall = wallBlockFor(s.type);
      BlockState floor = floorBlockFor(s.type);
      BlockState ceil = ceilingBlockFor(s.type);
      BlockState trim = trimBlockFor(s.type);
      BlockState bedrock = Blocks.BEDROCK.defaultBlockState();
      for (int lx = 0; lx < ARENA_SPAN; lx++) {
         for (int lz = 0; lz < ARENA_SPAN; lz++) {
            boolean beam = lx % COFFER == 0 || lz % COFFER == 0;
            setIfChanged(level, new BlockPos(ox + lx, fy - 1, oz + lz), bedrock);
            setIfChanged(level, new BlockPos(ox + lx, fy, oz + lz), floor);
            setIfChanged(level, new BlockPos(ox + lx, arenaTop, oz + lz), beam ? trim : ceil);
            if (!beam && lx % COFFER == COFFER / 2 && lz % COFFER == COFFER / 2) {
               setIfChanged(level, new BlockPos(ox + lx, arenaTop - 1, oz + lz), lightBlockFor(s.type));
            }
         }
      }
      for (int l = 0; l < ARENA_SPAN; l++) {
         for (int t = 0; t < WALL; t++) {
            for (int y = fy + 1; y < arenaTop; y++) {
               setIfChanged(level, new BlockPos(ox + t, y, oz + l), wall);
               setIfChanged(level, new BlockPos(ox + ARENA_SPAN - 1 - t, y, oz + l), wall);
               setIfChanged(level, new BlockPos(ox + l, y, oz + t), wall);
               setIfChanged(level, new BlockPos(ox + l, y, oz + ARENA_SPAN - 1 - t), wall);
            }
         }
      }
   }

   /** The arena's floor: a pit in the middle, a terrace three blocks above it, and its colonnade. */
   private static void buildArenaInterior(State s, Room room, int ox, int oz, int fy, int arenaTop) {
      ServerLevel level = s.zoneLevel;
      BlockState terrace = arenaTerraceBlock(s.type);
      BlockState pillar = trimBlockFor(s.type);
      BlockState dais = floorBlockFor(s.type);
      Arena arena = arenaOf(s, room);
      int mid = ARENA_SPAN / 2;
      for (int lx = WALL; lx < ARENA_SPAN - WALL; lx++) {
         for (int lz = WALL; lz < ARENA_SPAN - WALL; lz++) {
            if (inPit(lx, lz, ARENA_PIT_LO, ARENA_PIT_HI)) {
               continue;
            }
            int rise = arenaRise(arena, lx, lz);
            for (int y = fy + 1; y <= fy + rise; y++) {
               setIfChanged(level, new BlockPos(ox + lx, y, oz + lz), terrace);
            }
         }
      }
      // A medallion of trimmed stone underfoot in the pit, so the low ground reads as a floor the
      // guardian holds rather than as the place the tiles ran out.
      for (int lx = ARENA_PIT_LO; lx <= ARENA_PIT_HI; lx++) {
         for (int lz = ARENA_PIT_LO; lz <= ARENA_PIT_HI; lz++) {
            if (Math.abs(lx - mid) + Math.abs(lz - mid) <= 14) {
               setIfChanged(level, new BlockPos(ox + lx, fy, oz + lz), pillar);
            }
         }
      }
      arenaPitHazard(s, level, ox, oz, fy, ARENA_PIT_LO, ARENA_PIT_HI);
      // The guardian's dais, dead centre of the pit.
      for (int x = -1; x <= 0; x++) {
         for (int z = -1; z <= 0; z++) {
            setIfChanged(level, new BlockPos(ox + mid + x, fy + 1, oz + mid + z), dais);
         }
      }
      arenaWideColonnade(s, ox, oz, fy, arenaTop - 1);
   }

   /**
    * How high the arena's terrace stands at one local block, in blocks above the pit's floor.
    *
    * <p>Flat three everywhere on the rim except in the lane of one of the arena's own archways,
    * where it drops away to the pit's level for the apron the archway's cut sweeps and then climbs
    * back in three steps. Which ring cell a lane belongs to is the whole question here: the arena's
    * middle cannot hold a door - there is no wall in there to cut one through - so every way in is
    * an archway on the shell, held by one of the eight cells around it.
    */
   private static int arenaRise(Arena arena, int lx, int lz) {
      int rise = ARENA_TERRACE;
      for (int d = 0; d < 4; d++) {
         int depth;
         int lateral;
         switch (d) {
            case NORTH -> {
               depth = lz;
               lateral = lx;
            }
            case SOUTH -> {
               depth = ARENA_SPAN - 1 - lz;
               lateral = lx;
            }
            case WEST -> {
               depth = lx;
               lateral = lz;
            }
            default -> {
               depth = ARENA_SPAN - 1 - lx;
               lateral = lz;
            }
         }
         if (depth >= ARENA_APRON + ARENA_TERRACE) {
            continue;
         }
         int along = Math.min(ARENA_CELLS - 1, lateral / ROOM_PITCH);
         int inCell = lateral - along * ROOM_PITCH;
         if (inCell < DOOR_LO || inCell >= DOOR_LO + DOOR_W) {
            continue;
         }
         int dx = 0;
         int dz = 0;
         switch (d) {
            case NORTH -> {
               dx = along - 1;
               dz = -1;
            }
            case SOUTH -> {
               dx = along - 1;
               dz = 1;
            }
            case WEST -> {
               dx = -1;
               dz = along - 1;
            }
            default -> {
               dx = 1;
               dz = along - 1;
            }
         }
         Room ring = arena.rings.get(roomKey(arena.rx + dx, arena.rz + dz));
         if (ring == null || (ring.doors >> d & 1) == 0) {
            continue;
         }
         rise = Math.min(rise, arenaStairRise(depth));
      }
      return rise;
   }

   /**
    * The wide arena's columns, floor to roof, in a different arrangement in every dungeon.
    *
    * <p>Same idea as the cell arena's colonnade, at three times the scale - and scale is exactly
    * what a room with sightlines in it needs, because a column eighty-four blocks away breaks
    * nothing. So the pit's own corners carry heavy posts, the middle of every side of the rim
    * carries one more, and the dungeons that want a proper colonnade get the far corners too: a room
    * crossed in three directions with something standing in each of them.
    */
   private static void arenaWideColonnade(State s, int ox, int oz, int fy, int roofY) {
      ServerLevel level = s.zoneLevel;
      BlockState post = arenaPostBlock(s.type);
      int lo = ARENA_PIT_LO;
      int hi = ARENA_PIT_HI;
      int mid = ARENA_SPAN / 2;
      int inset = 5;
      int corner = 15;
      int thickness = s.type == Type.MONSTER_CAVE || s.type == Type.MAGMA_FORGE ? 1 : 0;
      int[][] pit = new int[][]{{lo, lo}, {lo, hi}, {hi, lo}, {hi, hi}};
      for (int[] at : pit) {
         arenaPost(level, ox, oz, at[0], at[1], fy + 1, roofY, post, thickness);
      }
      List<int[]> rim = new ArrayList<>();
      rim.add(new int[]{mid, lo - inset});
      rim.add(new int[]{mid, hi + inset});
      rim.add(new int[]{lo - inset, mid});
      rim.add(new int[]{hi + inset, mid});
      if (s.type == Type.CRYSTAL_CAVERN || s.type == Type.SUNKEN_TEMPLE || s.type == Type.FROZEN_CRYPT) {
         rim.add(new int[]{corner, corner});
         rim.add(new int[]{corner, ARENA_SPAN - 1 - corner});
         rim.add(new int[]{ARENA_SPAN - 1 - corner, corner});
         rim.add(new int[]{ARENA_SPAN - 1 - corner, ARENA_SPAN - 1 - corner});
      }
      for (int[] at : rim) {
         arenaPost(level, ox, oz, at[0], at[1], fy + ARENA_TERRACE + 1, roofY, post, 0);
      }
      for (int[] at : pit) {
         setIfChanged(level, new BlockPos(ox + at[0], fy + 2, oz + at[1]), lightBlockFor(s.type));
      }
   }

   /** What each dungeon's colonnade is made of. */
   private static BlockState arenaPostBlock(Type type) {
      return switch (type) {
         case DEEP_MINE -> Blocks.OAK_LOG.defaultBlockState();
         case MONSTER_CAVE -> Blocks.MOSSY_COBBLESTONE.defaultBlockState();
         case CRYSTAL_CAVERN -> Blocks.CALCITE.defaultBlockState();
         case VOID -> Blocks.OBSIDIAN.defaultBlockState();
         case SUNKEN_TEMPLE -> Blocks.PRISMARINE.defaultBlockState();
         case FROZEN_CRYPT -> Blocks.BLUE_ICE.defaultBlockState();
         case MAGMA_FORGE -> Blocks.BASALT.defaultBlockState();
         default -> Blocks.STONE_BRICKS.defaultBlockState();
      };
   }

   /**
    * The arena's exits shut behind an explorer who steps down into the pit.
    *
    * <p>The arena holds no door of its own, so this is not {@link #sealRoom} - the bars go across
    * the archways the RIM cells own, and they are recorded on the arena rather than on those cells,
    * because the arena is what the explorer is standing in and the arena is what will be unsealed
    * when the guardian falls. Every rim cell with a door gets its own half barred, so what closes is
    * the shell of the block and nothing inside it: the terrace stays a way around the fight, and the
    * way around is only shut for the explorer who has already stopped using it.
    */
   private static void sealArena(State s, Arena arena, ServerPlayer sp) {
      Room room = arena.room;
      if (room.sealed) {
         return;
      }
      int fy = floorY(s);
      BlockState bars = Blocks.IRON_BARS.defaultBlockState();
      for (Room ring : arena.rings.values()) {
         sealBars(s, ring, room, fy, bars);
      }
      if (room.bars.isEmpty()) {
         return;
      }
      room.sealed = true;
      // The bounds are the block's, not the middle cell's: the arena's monsters are inside the
      // eighty-four blocks, and a guardian who has walked up onto the terrace is still in the fight.
      pullInStrays(s, room, baseX(s, arena.rx - 1), baseZ(s, arena.rz - 1), fy, ARENA_SPAN);
      SoundUtil.play(sp, net.minecraft.sounds.SoundEvents.IRON_DOOR_CLOSE);
      s.zoneLevel.sendParticles(
         ParticleTypes.SMOKE, sp.getX(), sp.getY() + 1.0, sp.getZ(), 18, 0.9, 0.5, 0.9, 0.02
      );
      actionBar(sp, "§c§lTHE ARENA CLOSES §7· " + livingCount(s, room) + " left in here");
   }

   /**
    * The arena's way out, opened on the rim where the arena keeps its doors.
    *
    * <p>A cleared chamber opens up to three fresh archways, and the arena's middle cannot hold one -
    * there is no wall in there to cut through. So the doors are cut in the ring cells, at faces that
    * lead off the block, one to a side where the block has that many sides free: the run carries on
    * from three different edges of the room it was judged in, and which edge is a walk the player
    * can see the length of.
    */
   private static int openArenaExits(State s, Arena arena) {
      List<int[]> candidates = new ArrayList<>();
      for (Room ring : arena.rings.values()) {
         for (int d = 0; d < 4; d++) {
            int nrx = ring.rx + DIR_X[d];
            int nrz = ring.rz + DIR_Z[d];
            if (arena.holds(nrx, nrz)) {
               continue;
            }
            if ((ring.doors >> d & 1) != 0) {
               continue;
            }
            candidates.add(new int[]{ring.rx, ring.rz, d});
         }
      }
      Collections.shuffle(candidates, RANDOM);
      boolean[] used = new boolean[4];
      int opened = 0;
      for (int pass = 0; pass < 2 && opened < 3; pass++) {
         for (int[] c : candidates) {
            if (opened >= 3) {
               break;
            }
            if (pass == 0 && used[c[2]]) {
               continue;
            }
            Room ring = arena.rings.get(roomKey(c[0], c[1]));
            if (ring == null || (ring.doors >> c[2] & 1) != 0) {
               continue;
            }
            used[c[2]] = true;
            connect(s, ring, c[2]);
            opened++;
         }
      }
      return opened;
   }

   /** The floor guardian's arena: a lit fighting pit with a dais for the thing that owns it. */
   private static void buildBossArena(State s, Room room, int bx, int bz, int fy, int top) {
      ServerLevel level = s.zoneLevel;
      BlockState pillar = trimBlockFor(s.type);
      BlockState dais = floorBlockFor(s.type);
      BlockState terrace = arenaTerraceBlock(s.type);
      int c = ROOM_PITCH / 2;
      int arenaTop = fy + ARENA_HEIGHT;

      // 1) The vault. The chamber's own roof comes off and a second storey goes on top of it, so
      //    the arena is ONE space from the pit to the underside of the roof rather than two rooms
      //    stacked on one another - which is the whole of what makes it read as a giant hall. The
      //    walls are written by hand above the old ceiling because there is nothing up there to
      //    write them: the dungeon is a box floating in an empty world, and a box without a top is
      //    a room with a view of nothing.
      BlockState ceil = ceilingBlockFor(s.type);
      BlockState wall = wallBlockFor(s.type);
      BlockState air = Blocks.AIR.defaultBlockState();
      for (int lx = 0; lx < ROOM_PITCH; lx++) {
         for (int lz = 0; lz < ROOM_PITCH; lz++) {
            boolean band = lx < WALL || lx >= ROOM_PITCH - WALL || lz < WALL || lz >= ROOM_PITCH - WALL;
            // The old ceiling plane sits at `top`. The vault is written from two blocks below it -
            // the beams' own height - because the chamber is dressed before it is fought in, and
            // anything the dress hung under the old roof is a prop hanging in the middle of the new
            // one. Above `top` rises the vault, and above that its roof.
            for (int y = top - 2; y <= arenaTop + 1; y++) {
               BlockState want;
               if (y > arenaTop) {
                  want = ceil;
               } else if (y == arenaTop) {
                  if (band) {
                     want = ceil;
                  } else {
                     want = (lx - WALL) % COFFER == 0 || (lz - WALL) % COFFER == 0 ? pillar : ceil;
                  }
               } else if (band) {
                  want = wall;
               } else {
                  want = air;
               }
               setIfChanged(level, new BlockPos(bx + lx, y, bz + lz), want);
            }
            // ...and the vault is lit from its own roof: a lantern under every panel it cuts, since
            // a hall this tall has no light in it at all otherwise.
            if (!band && (lx - WALL) % COFFER == COFFER / 2 && (lz - WALL) % COFFER == COFFER / 2) {
               setIfChanged(level, new BlockPos(bx + lx, arenaTop - 1, bz + lz), lightBlockFor(s.type));
            }
         }
      }

      // 2) The pit and the terrace. The middle of the arena stays at the chamber's own floor level
      //    and the whole rim around it is raised three blocks: a fighting floor you can be knocked
      //    off, and high ground you can be cornered on. The slam reaches into the pit and not up
      //    onto the terrace, so where the fight happens is a choice the player makes with their
      //    feet - and one the guardian answers with the rush and the step through rock.
      for (int lx = WALL; lx < ROOM_PITCH - WALL; lx++) {
         for (int lz = WALL; lz < ROOM_PITCH - WALL; lz++) {
            if (inPit(lx, lz, CELL_PIT_LO, CELL_PIT_HI)) {
               continue;
            }
            // A terrace across an archway would wall the doorway shut, so where a door opens the
            // terrace is a staircase instead: three steps up from the threshold, cut into the rim.
            int rise = arenaTerraceRise(room, lx, lz);
            for (int y = fy + 1; y <= fy + rise; y++) {
               setIfChanged(level, new BlockPos(bx + lx, y, bz + lz), terrace);
            }
         }
      }
      // A medallion of trimmed stone underfoot in the pit, so the low ground reads as a floor the
      // guardian holds rather than as the place the tiles ran out.
      for (int lx = CELL_PIT_LO; lx <= CELL_PIT_HI; lx++) {
         for (int lz = CELL_PIT_LO; lz <= CELL_PIT_HI; lz++) {
            if (Math.abs(lx - c) + Math.abs(lz - c) <= 6) {
               setIfChanged(level, new BlockPos(bx + lx, fy, bz + lz), pillar);
            }
         }
      }
      // ...and what the dungeon's own floor does to a fight, done to the pit: the temple's pool,
      // the forge's ring of magma, the crypt's ice, the cavern's crystal.
      arenaPitHazard(s, level, bx, bz, fy, CELL_PIT_LO, CELL_PIT_HI);
      // The guardian's dais, dead centre of the pit.
      for (int x = -1; x <= 0; x++) {
         for (int z = -1; z <= 0; z++) {
            setIfChanged(level, new BlockPos(bx + c + x, fy + 1, bz + c + z), dais);
         }
      }
      // 3) The colonnade: what the arena is really for. Columns that reach the roof break the room
      //    into sightlines, which is the only thing that makes the guardian's approach - or the
      //    player's retreat - a decision rather than a straight line.
      arenaColonnade(s, level, bx, bz, fy, arenaTop - 1, CELL_PIT_LO, CELL_PIT_HI);
   }

   /**
    * True inside a pit, by whatever local bounds that arena's low ground was drawn with.
    *
    * <p>Local coordinates, deliberately symmetric - the pit is the middle of the arena on both
    * axes, and the terrace is the rim around it on every side. Which numbers those are depends on
    * the arena's span: the three-by-three arena's pit is thirty-six blocks across, and the pit of the
    * cell-sized arena it falls back to is twelve.
    */
   private static boolean inPit(int lx, int lz, int lo, int hi) {
      return lx >= lo && lx <= hi && lz >= lo && lz <= hi;
   }

   /**
    * How high the terrace stands at one local cell, in blocks above the floor.
    *
    * <p>Flat three everywhere except in the lane of an open archway, where it ramps: one block at
    * the threshold, two behind that, full height from there. A terrace drawn straight across a door
    * is a door that has been bricked up with architecture, and the arena has to be walkable in and
    * out of - it is the room a run is judged in, not a cell the player is dropped into.
    */
   private static int arenaTerraceRise(Room room, int lx, int lz) {
      int rise = ARENA_TERRACE;
      boolean laneX = lx >= DOOR_LO && lx < DOOR_LO + DOOR_W;
      boolean laneZ = lz >= DOOR_LO && lz < DOOR_LO + DOOR_W;
      if (laneX && (room.doors & 1 << NORTH) != 0) {
         rise = Math.min(rise, arenaStairRise(lz));
      }
      if (laneX && (room.doors & 1 << SOUTH) != 0) {
         rise = Math.min(rise, arenaStairRise(ROOM_PITCH - 1 - lz));
      }
      if (laneZ && (room.doors & 1 << WEST) != 0) {
         rise = Math.min(rise, arenaStairRise(lx));
      }
      if (laneZ && (room.doors & 1 << EAST) != 0) {
         rise = Math.min(rise, arenaStairRise(ROOM_PITCH - 1 - lx));
      }
      return rise;
   }

   /**
    * Distance inside a threshold, in local blocks, as a step of the door's own staircase.
    *
    * <p>Flat for the apron's length and only then a step, because the archway's own cut sweeps the
    * walk lane five blocks into the room behind it and sets those blocks to air: anything raised in
    * there is a trench by the time the door exists. See {@link #ARENA_APRON}.
    */
   private static int arenaStairRise(int depth) {
      return depth <= ARENA_APRON ? 0 : Math.min(ARENA_TERRACE, depth - ARENA_APRON);
   }

   /** The stone or ice the arena's terrace is faced in - each dungeon builds its own grandstand. */
   private static BlockState arenaTerraceBlock(Type type) {
      return switch (type) {
         case DEEP_MINE -> Blocks.STONE_BRICKS.defaultBlockState();
         case MONSTER_CAVE -> Blocks.MOSSY_COBBLESTONE.defaultBlockState();
         case CRYSTAL_CAVERN -> Blocks.CALCITE.defaultBlockState();
         case VOID -> Blocks.POLISHED_BLACKSTONE.defaultBlockState();
         case SUNKEN_TEMPLE -> Blocks.PRISMARINE_BRICKS.defaultBlockState();
         case FROZEN_CRYPT -> Blocks.PACKED_ICE.defaultBlockState();
         case MAGMA_FORGE -> Blocks.POLISHED_BASALT.defaultBlockState();
         default -> Blocks.STONE_BRICKS.defaultBlockState();
      };
   }

   /**
    * The floor of the pit, as the dungeon it is cut into would have it.
    *
    * <p>Same arena, seven different fights: standing water that slows every step in the temple, a
    * ring of magma around the forge's low ground, black ice under the crypt, crystals to break the
    * line across the cavern. The geometry never moves - what the floor does to whoever is standing
    * on it is what changes, and that is the difference between a boss room and a boss room in a
    * dungeon.
    */
   private static void arenaPitHazard(State s, ServerLevel level, int bx, int bz, int fy, int lo, int hi) {
      switch (s.type) {
         case SUNKEN_TEMPLE -> {
            for (int lx = lo + 2; lx <= hi - 2; lx++) {
               for (int lz = lo + 2; lz <= hi - 2; lz++) {
                  setIfChanged(level, new BlockPos(bx + lx, fy + 1, bz + lz), Blocks.WATER.defaultBlockState());
                  setIfChanged(level, new BlockPos(bx + lx, fy + 2, bz + lz), Blocks.WATER.defaultBlockState());
               }
            }
            setIfChanged(level, new BlockPos(bx + lo, fy + 1, bz + lo), Blocks.SEA_LANTERN.defaultBlockState());
            setIfChanged(level, new BlockPos(bx + hi, fy + 1, bz + hi), Blocks.SEA_LANTERN.defaultBlockState());
            setIfChanged(level, new BlockPos(bx + lo, fy + 1, bz + hi), Blocks.SEA_LANTERN.defaultBlockState());
            setIfChanged(level, new BlockPos(bx + hi, fy + 1, bz + lo), Blocks.SEA_LANTERN.defaultBlockState());
         }
         case MAGMA_FORGE -> {
            // The rim of the pit is on fire and the middle is not, so the arena has a ring in it that
            // everybody fighting for room has to respect.
            for (int lx = lo; lx <= hi; lx++) {
               for (int lz = lo; lz <= hi; lz++) {
                  if (lx != lo && lx != hi && lz != lo && lz != hi) {
                     continue;
                  }
                  setIfChanged(level, new BlockPos(bx + lx, fy, bz + lz), Blocks.MAGMA_BLOCK.defaultBlockState());
               }
            }
         }
         case FROZEN_CRYPT -> {
            for (int lx = lo; lx <= hi; lx++) {
               for (int lz = lo; lz <= hi; lz++) {
                  setIfChanged(level, new BlockPos(bx + lx, fy + 1, bz + lz), Blocks.SNOW.defaultBlockState());
               }
            }
         }
         case CRYSTAL_CAVERN -> {
            // A cracking geode floor. The wide arena's pit is nine times the cell arena's, so what
            // was four clusters at a corner each is a bed of them: the growth is scaled to the floor
            // it stands on rather than to the room the code was written for.
            for (int[] at : arenaCorners(lo, hi)) {
               setIfChanged(level, new BlockPos(bx + at[0], fy + 1, bz + at[1]), Blocks.AMETHYST_CLUSTER.defaultBlockState());
            }
            for (int i = 0; i < (hi - lo) * (hi - lo) / 12; i++) {
               int lx = lo + 1 + RANDOM.nextInt(Math.max(1, hi - lo - 1));
               int lz = lo + 1 + RANDOM.nextInt(Math.max(1, hi - lo - 1));
               setIfChanged(level, new BlockPos(bx + lx, fy + 1, bz + lz), Blocks.SMALL_AMETHYST_BUD.defaultBlockState());
            }
         }
         case MONSTER_CAVE -> {
            // The bones of everything that has been through the gate he holds.
            for (int i = 0; i < (hi - lo) * (hi - lo) / 40; i++) {
               int lx = lo + 1 + RANDOM.nextInt(Math.max(1, hi - lo - 1));
               int lz = lo + 1 + RANDOM.nextInt(Math.max(1, hi - lo - 1));
               setIfChanged(level, new BlockPos(bx + lx, fy, bz + lz), Blocks.BONE_BLOCK.defaultBlockState());
               if (RANDOM.nextBoolean()) {
                  setIfChanged(level, new BlockPos(bx + lx, fy + 1, bz + lz), Blocks.SKELETON_SKULL.defaultBlockState());
               }
            }
         }
         case VOID -> {
            for (int[] at : arenaCorners(lo, hi)) {
               setIfChanged(level, new BlockPos(bx + at[0], fy, bz + at[1]), Blocks.CRYING_OBSIDIAN.defaultBlockState());
               setIfChanged(level, new BlockPos(bx + at[0], fy + 1, bz + at[1]), Blocks.END_ROD.defaultBlockState());
            }
         }
         case DEEP_MINE -> {
            // Rough ground: the mine's pit is dug, not laid, and nine times the floor gets nine
            // times the broken patches rather than the same twenty-four spread thin.
            int scatter = Math.max(12, (hi - lo) * (hi - lo) / 8);
            for (int i = 0; i < scatter; i++) {
               int lx = lo + RANDOM.nextInt(hi - lo + 1);
               int lz = lo + RANDOM.nextInt(hi - lo + 1);
               BlockPos cell = new BlockPos(bx + lx, fy, bz + lz);
               if (level.getBlockState(cell).is(floorBlockFor(s.type).getBlock())) {
                  setIfChanged(level, cell, Blocks.GRAVEL.defaultBlockState());
               }
            }
         }
         default -> {
         }
      }
   }

   /**
    * The arena's columns, floor to roof, in a different arrangement in every dungeon.
    *
    * <p>Three shapes across the seven dungeons - four heavy corner posts, a full eight-post
    * colonnade, and a ring of thin pillars - because what a column is FOR here is breaking the room
    * into pieces you cannot see each other through. A guardian who can be watched the whole time he
    * is crossing is a guardian with an announcement, and the rush and the step through rock are both
    * moves that stop working the moment the room is one open field.
    */
   private static void arenaColonnade(State s, ServerLevel level, int bx, int bz, int fy, int roofY, int lo, int hi) {
      BlockState post = arenaPostBlock(s.type);
      int c = ROOM_PITCH / 2;
      int[][] corners = new int[][]{{lo, lo}, {lo, hi}, {hi, lo}, {hi, hi}};
      int[][] midSides = new int[][]{{c, WALL + 3}, {c, ROOM_PITCH - WALL - 4}, {WALL + 3, c}, {ROOM_PITCH - WALL - 4, c}};
      // The corner posts stand in the pit, from its own floor: thick on the dungeons that are about
      // cover, thin where the room should be watched even standing still.
      int thickness = s.type == Type.MONSTER_CAVE || s.type == Type.MAGMA_FORGE ? 1 : 0;
      for (int[] at : corners) {
         arenaPost(level, bx, bz, at[0], at[1], fy + 1, roofY, post, thickness);
      }
      // ...and the terrace carries its own: a full colonnade in the cavern and the temple, a shorter
      // pair flanking the pit in the rest. Standing on the high ground is being in a room with posts
      // in it, which is exactly what the high ground should be.
      boolean full = s.type == Type.CRYSTAL_CAVERN || s.type == Type.SUNKEN_TEMPLE || s.type == Type.FROZEN_CRYPT;
      if (full) {
         for (int[] at : midSides) {
            arenaPost(level, bx, bz, at[0], at[1], fy + ARENA_TERRACE + 1, roofY, post, 0);
         }
      } else {
         for (int[] at : new int[][]{{lo - 1, lo - 1}, {hi + 1, hi + 1}}) {
            arenaPost(level, bx, bz, at[0], at[1], fy + ARENA_TERRACE + 1, roofY, post, 0);
         }
      }
      // A crown of light on every post, so the colonnade is also what lights the arena.
      for (int[] at : corners) {
         if (inPit(at[0], at[1], lo, hi)) {
            setIfChanged(level, new BlockPos(bx + at[0], fy + 2, bz + at[1]), lightBlockFor(s.type));
         }
      }
   }

   /** The four corners just inside a pit, for whatever its bounds happen to be. */
   private static int[][] arenaCorners(int lo, int hi) {
      return new int[][]{
         {lo + 1, lo + 1}, {lo + 1, hi - 1},
         {hi - 1, lo + 1}, {hi - 1, hi - 1}
      };
   }

   /** One post of the colonnade, from {@code fromY} up to {@code toY}, optionally two by two. */
   private static void arenaPost(ServerLevel level, int bx, int bz, int lx, int lz, int fromY, int toY, BlockState block, int thickness) {
      for (int dx = 0; dx <= thickness; dx++) {
         for (int dz = 0; dz <= thickness; dz++) {
            for (int y = fromY; y <= toY; y++) {
               setIfChanged(level, new BlockPos(bx + lx + dx, y, bz + lz + dz), block);
            }
         }
      }
   }

   /** Ore studs scattered through the floor of every chamber - the dungeon always pays to dig. */
   private static void scatterFloorOres(State s, Room room, int bx, int bz, int fy) {
      // The guardian's arena is the one chamber with no seam in it. Its floor is the fight - a pit,
      // a terrace and whatever the dungeon's own stone does to a body standing on it - and ore studs
      // punched through that are both a hole in the architecture and the site paying a player for
      // fighting a boss in a boss room. The arena pays its bounty; the ore is what the rest of the
      // maze is for.
      if (room.chamber == Chamber.TREASURE_VAULT || isRestRoom(room.chamber)
         || room.chamber == Chamber.ENTRANCE || room.chamber == Chamber.WAGER_VAULT
         || room.chamber == Chamber.FLOOR_BOSS) {
         return;
      }
      ServerLevel level = s.zoneLevel;
      for (int lx = WALL; lx < ROOM_PITCH - WALL; lx++) {
         for (int lz = WALL; lz < ROOM_PITCH - WALL; lz++) {
            if (RANDOM.nextInt(100) < 8) {
               Block ore = oreFor(s.type, RANDOM.nextInt(100), 0);
               if (ore != null) {
                  setIfChanged(level, new BlockPos(bx + lx, fy, bz + lz), ore.defaultBlockState());
               }
            }
         }
      }
   }

   /**
    * The props that make one site THAT site: amethyst in the tuff, rails in the mine, ice in the
    * crypt, lava in the forge.
    *
    * <p>The dungeon's materials are the rock it is cut through, which is why a cavern of amethyst
    * walls read as a purple box - every chamber of it was the same one block. This is the other half
    * of the fix: each dungeon grows the thing it is named after, in the ordinary rock, so the theme
    * is something the explorer walks past rather than something they are standing inside.
    *
    * <p>Every prop asks {@link #dressable} first, so nothing here can land on a chest, a spawner or
    * an interactable, and nothing goes in a doorway. Props are placed with a non-propagating write,
    * which is what keeps a lava trench in a forge hall from becoming a lava FLOOR.
    */
   private static void decorate(State s, Room room, int bx, int bz, int fy, int top) {
      ServerLevel level = s.zoneLevel;
      int first = WALL + 1;
      int span = Math.max(1, ROOM_PITCH - WALL * 2 - 2);
      int ceil = top - 1;
      switch (s.type) {
         case CRYSTAL_CAVERN -> {
            // The cavern is a geode, and a geode is ordinary rock with amethyst IN it: beds of the
            // stuff lining the floor, crystals standing on the beds at every stage of their growth,
            // budding seams hanging in the roof over them, and the odd block of it showing through
            // a wall where a vein broke out. Which is what a cave full of amethyst looks like - as
            // opposed to a hall built out of amethyst, which is a purple box with a floor.
            BlockState crystal = Blocks.AMETHYST_BLOCK.defaultBlockState();
            BlockState[] growth = {
               Blocks.SMALL_AMETHYST_BUD.defaultBlockState(),
               Blocks.MEDIUM_AMETHYST_BUD.defaultBlockState(),
               Blocks.LARGE_AMETHYST_BUD.defaultBlockState(),
               Blocks.AMETHYST_CLUSTER.defaultBlockState()
            };
            for (int i = 0; i < 26; i++) {
               int lx = first + RANDOM.nextInt(span);
               int lz = first + RANDOM.nextInt(span);
               if (inDoorLane(room, lx, lz)) {
                  continue;
               }
               BlockPos base = new BlockPos(bx + lx, fy, bz + lz);
               BlockPos above = base.above();
               if (!dressable(level, base, floorBlockFor(s.type)) || !dressable(level, above, null)) {
                  continue;
               }
               setIfChanged(level, base, crystal);
               setIfChanged(level, above, growth[RANDOM.nextInt(growth.length)]);
            }
            // A budding seam overhead: the rock the crystals are growing out of, not crystal rock.
            for (int i = 0; i < 16; i++) {
               int lx = first + RANDOM.nextInt(span);
               int lz = first + RANDOM.nextInt(span);
               if (inDoorLane(room, lx, lz)) {
                  continue;
               }
               BlockPos cell = new BlockPos(bx + lx, ceil, bz + lz);
               if (!dressable(level, cell, null)) {
                  continue;
               }
               setIfChanged(level, cell, Blocks.BUDDING_AMETHYST.defaultBlockState());
            }
            // Veins breaking through the walls, so the amethyst reads as coming out of the rock.
            for (int i = 0; i < 14; i++) {
               int lx = RANDOM.nextBoolean() ? first : ROOM_PITCH - WALL - 1;
               int lz = first + RANDOM.nextInt(span);
               if (RANDOM.nextBoolean()) {
                  int swap = lx;
                  lx = lz;
                  lz = swap;
               }
               if (inDoorLane(room, lx, lz)) {
                  continue;
               }
               BlockPos at = new BlockPos(bx + lx, fy + 1 + RANDOM.nextInt(ROOM_HEIGHT - 3), bz + lz);
               if (dressable(level, at, wallBlockFor(s.type))) {
                  setIfChanged(level, at, crystal);
               }
            }
            // One formation nobody has dug out: a spire of it in a corner, crystal over rock.
            int sx = RANDOM.nextBoolean() ? first + 3 : ROOM_PITCH - WALL - 4;
            int sz = RANDOM.nextBoolean() ? first + 3 : ROOM_PITCH - WALL - 4;
            if (!inDoorLane(room, sx, sz)) {
               for (int y = 1; y <= 4; y++) {
                  BlockPos at = new BlockPos(bx + sx, fy + y, bz + sz);
                  if (dressable(level, at, y == 1 ? floorBlockFor(s.type) : null)) {
                     setIfChanged(level, at, y == 4 ? Blocks.AMETHYST_CLUSTER.defaultBlockState() : crystal);
                  }
               }
            }
         }
         case DEEP_MINE -> {
            boolean alongX = RANDOM.nextBoolean();
            int lane = first + 2 + RANDOM.nextInt(Math.max(1, span - 4));
            BlockState rail = Blocks.RAIL.defaultBlockState();
            for (int t = first; t < first + span; t++) {
               int lx = alongX ? t : lane;
               int lz = alongX ? lane : t;
               if (inDoorLane(room, lx, lz)) {
                  continue;
               }
               // The rail runs ON the floor, not in it: a rail written into the floor cell is a rail
               // that has been swallowed by the rock it was laid on, and every sleeper in this mine
               // is standing one block too low. The cell above the floor is the one a cart would
               // actually roll through, so that - and only that - is where it is set.
               BlockPos cell = new BlockPos(bx + lx, fy + 1, bz + lz);
               if (dressable(level, cell, null)) {
                  setIfChanged(level, cell, rail);
               }
               if (t % 6 == 0) {
                  BlockPos post = new BlockPos(bx + lx, ceil, bz + lz);
                  if (dressable(level, post, null)) {
                     setIfChanged(level, post, Blocks.DEEPSLATE_TILES.defaultBlockState());
                  }
               }
            }
            for (int i = 0; i < 18; i++) {
               int lx = first + RANDOM.nextInt(span);
               int lz = first + RANDOM.nextInt(span);
               if (inDoorLane(room, lx, lz)) {
                  continue;
               }
               BlockPos cell = new BlockPos(bx + lx, fy, bz + lz);
               if (dressable(level, cell, floorBlockFor(s.type))) {
                  setIfChanged(level, cell, Blocks.COBBLED_DEEPSLATE.defaultBlockState());
               }
            }
            // Timbering: a post on either side of the haul road, with a beam across the top, the
            // way somebody left it when the vein played out.
            for (int t = first + 2; t < first + span - 3; t += 7) {
               int lx = alongX ? t : lane;
               int lz = alongX ? lane : t;
               if (inDoorLane(room, lx, lz)) {
                  continue;
               }
               int ox = 0;
               int oz = 0;
               ox = alongX ? 0 : 1;
               oz = alongX ? 1 : 0;
               for (int y = fy + 1; y <= top - 1; y++) {
                  BlockPos a = new BlockPos(bx + lx - (alongX ? 0 : 3), y, bz + lz - (alongX ? 3 : 0));
                  BlockPos b = new BlockPos(bx + lx + (alongX ? 0 : 3), y, bz + lz + (alongX ? 3 : 0));
                  if (dressable(level, a, null)) {
                     setIfChanged(level, a, Blocks.STRIPPED_SPRUCE_LOG.defaultBlockState());
                  }
                  if (dressable(level, b, null)) {
                     setIfChanged(level, b, Blocks.STRIPPED_SPRUCE_LOG.defaultBlockState());
                  }
               }
               for (int d = -3; d <= 3; d++) {
                  BlockPos beam = new BlockPos(bx + lx + (alongX ? d : 0), top - 1, bz + lz + (alongX ? 0 : d));
                  if (dressable(level, beam, null)) {
                     setIfChanged(level, beam, Blocks.STRIPPED_SPRUCE_LOG.defaultBlockState());
                  }
               }
            }
         }
         case MONSTER_CAVE -> {
            for (int i = 0; i < 22; i++) {
               int lx = first + RANDOM.nextInt(span);
               int lz = first + RANDOM.nextInt(span);
               if (inDoorLane(room, lx, lz)) {
                  continue;
               }
               BlockPos cell = new BlockPos(bx + lx, fy + 1 + RANDOM.nextInt(3), bz + lz);
               if (dressable(level, cell, null)) {
                  setIfChanged(level, cell, Blocks.COBWEB.defaultBlockState());
               }
            }
            // Whatever has been growing down here since the lights went out.
            for (int i = 0; i < 10; i++) {
               int lx = first + RANDOM.nextInt(span);
               int lz = first + RANDOM.nextInt(span);
               if (inDoorLane(room, lx, lz)) {
                  continue;
               }
               BlockPos cell = new BlockPos(bx + lx, fy + 1, bz + lz);
               if (dressable(level, cell, null)) {
                  setIfChanged(
                     level, cell,
                     RANDOM.nextBoolean()
                        ? Blocks.BROWN_MUSHROOM.defaultBlockState()
                        : Blocks.RED_MUSHROOM.defaultBlockState()
                  );
               }
            }
            for (int i = 0; i < 12; i++) {
               int lx = first + RANDOM.nextInt(span);
               int lz = first + RANDOM.nextInt(span);
               if (inDoorLane(room, lx, lz)) {
                  continue;
               }
               BlockPos cell = new BlockPos(bx + lx, fy, bz + lz);
               if (dressable(level, cell, floorBlockFor(s.type))) {
                  setIfChanged(
                     level, cell, RANDOM.nextBoolean() ? Blocks.BONE_BLOCK.defaultBlockState() : Blocks.MOSSY_COBBLESTONE.defaultBlockState()
                  );
               }
            }
         }
         case VOID -> {
            for (int i = 0; i < 16; i++) {
               int lx = first + RANDOM.nextInt(span);
               int lz = first + RANDOM.nextInt(span);
               if (inDoorLane(room, lx, lz)) {
                  continue;
               }
               BlockPos cell = new BlockPos(bx + lx, fy, bz + lz);
               if (dressable(level, cell, floorBlockFor(s.type))) {
                  setIfChanged(level, cell, Blocks.CRYING_OBSIDIAN.defaultBlockState());
               }
               BlockPos lamp = cell.above();
               if (RANDOM.nextInt(3) == 0 && dressable(level, lamp, null)) {
                  setIfChanged(level, lamp, Blocks.SOUL_LANTERN.defaultBlockState());
               }
            }
            // Standing stones: obsidian up to twice a body's height, with a soul lantern on top.
            // A void hall is a floor and a dark, and something to walk around is what makes it a
            // hall rather than a dark floor.
            for (int i = 0; i < 12; i++) {
               int lx = first + RANDOM.nextInt(span);
               int lz = first + RANDOM.nextInt(span);
               if (inDoorLane(room, lx, lz)) {
                  continue;
               }
               int high = 1 + RANDOM.nextInt(2);
               boolean clear = true;
               for (int y = 1; y <= high + 1 && clear; y++) {
                  clear = dressable(level, new BlockPos(bx + lx, fy + y, bz + lz), null);
               }
               if (!clear) {
                  continue;
               }
               for (int y = 1; y <= high; y++) {
                  setIfChanged(level, new BlockPos(bx + lx, fy + y, bz + lz), Blocks.OBSIDIAN.defaultBlockState());
               }
               setIfChanged(level, new BlockPos(bx + lx, fy + high + 1, bz + lz), Blocks.SOUL_LANTERN.defaultBlockState());
            }
         }
         case SUNKEN_TEMPLE -> {
            for (int i = 0; i < 4; i++) {
               int lx = first + 2 + RANDOM.nextInt(Math.max(1, span - 4));
               int lz = first + 2 + RANDOM.nextInt(Math.max(1, span - 4));
               if (inDoorLane(room, lx, lz)) {
                  continue;
               }
               BlockPos cell = new BlockPos(bx + lx, fy, bz + lz);
               if (!dressable(level, cell, floorBlockFor(s.type))) {
                  continue;
               }
               // A pool sunk into the floor, ringed with prismarine, with pickles growing in it.
               boolean clear = true;
               for (int dx = -1; dx <= 1 && clear; dx++) {
                  for (int dz = -1; dz <= 1; dz++) {
                     if (!dressable(level, cell.offset(dx, 0, dz), floorBlockFor(s.type))
                        || !dressable(level, cell.offset(dx, 1, dz), null)) {
                        clear = false;
                        break;
                     }
                  }
               }
               if (!clear) {
                  continue;
               }
               for (int dx = -1; dx <= 1; dx++) {
                  for (int dz = -1; dz <= 1; dz++) {
                     setIfChanged(level, cell.offset(dx, 0, dz), Blocks.PRISMARINE_BRICKS.defaultBlockState());
                  }
               }
               for (int dx = -1; dx <= 1; dx++) {
                  for (int dz = -1; dz <= 1; dz++) {
                     setIfChanged(level, cell.offset(dx, 1, dz), Blocks.WATER.defaultBlockState());
                  }
               }
               // No sea pickles in the basins. They were the one block in the temple that made a
               // sunken room read as a *pond*: they sit on the surface, they are bright, and they
               // announce themselves before anything else in the room does. The basins are for the
               // temple's own light, which is what was asked for.
               setIfChanged(level, cell.above(2), Blocks.SEA_LANTERN.defaultBlockState());
            }
         }
         case FROZEN_CRYPT -> cryptDressing(s, room, bx, bz, fy, top, first, span, ceil);
         case MAGMA_FORGE -> {
            boolean alongX = RANDOM.nextBoolean();
            int lane = first + 2 + RANDOM.nextInt(Math.max(1, span - 4));
            BlockState lava = Blocks.LAVA.defaultBlockState();
            BlockState rim = Blocks.MAGMA_BLOCK.defaultBlockState();
            // The crust: magma showing through the netherrack all over the floor. Placed first, so
            // every prop below that wants the floor block to still be netherrack politely skips
            // these cells - and never the other way round, which would pave the crust over.
            BlockState magma = Blocks.MAGMA_BLOCK.defaultBlockState();
            for (int lx = interior(); lx < interiorEnd(); lx++) {
               for (int lz = interior(); lz < interiorEnd(); lz++) {
                  if (inDoorLane(room, lx, lz) || RANDOM.nextInt(100) >= 34) {
                     continue;
                  }
                  BlockPos cell = new BlockPos(bx + lx, fy, bz + lz);
                  if (dressable(level, cell, floorBlockFor(s.type))) {
                     setIfChanged(level, cell, magma);
                  }
               }
            }
            for (int t = first; t < first + span; t++) {
               int lx = alongX ? t : lane;
               int lz = alongX ? lane : t;
               if (inDoorLane(room, lx, lz)) {
                  continue;
               }
               BlockPos cell = new BlockPos(bx + lx, fy, bz + lz);
               if (!dressable(level, cell, floorBlockFor(s.type))) {
                  continue;
               }
               // The trench is sunk one block: the channel runs where the floor was, so nothing can
               // step in it by accident and nothing can pour out of it.
               setIfChanged(level, cell, lava);
               setIfChanged(level, cell.offset(alongX ? 0 : 1, 0, alongX ? 1 : 0), rim);
            }
            for (int i = 0; i < 12; i++) {
               int lx = first + RANDOM.nextInt(span);
               int lz = first + RANDOM.nextInt(span);
               if (inDoorLane(room, lx, lz)) {
                  continue;
               }
               BlockPos cell = new BlockPos(bx + lx, fy + 1, bz + lz);
               if (dressable(level, cell, null)) {
                  setIfChanged(level, cell, Blocks.ANVIL.defaultBlockState());
                  BlockPos chain = cell.above();
                  if (dressable(level, chain, null)) {
                     setIfChanged(level, chain, Blocks.IRON_CHAIN.defaultBlockState());
                  }
               }
            }
            // Quench barrels along the wall, stood where the smiths left them.
            for (int lz = first + 3; lz < first + span - 3; lz += 6) {
               for (int lx : new int[]{first + 2, ROOM_PITCH - WALL - 3}) {
                  if (inDoorLane(room, lx, lz)) {
                     continue;
                  }
                  BlockPos cell = new BlockPos(bx + lx, fy + 1, bz + lz);
                  if (dressable(level, cell, null)) {
                     setIfChanged(level, cell, Blocks.CAULDRON.defaultBlockState());
                  }
               }
            }
         }
         default -> {
            // The plain dungeons keep the shell they were given.
         }
      }
   }

   /**
    * The sepulcher's own set pieces, one per kind of chamber.
    *
    * <p>Six, because a crypt needs to say what a room is for as soon as the player steps into it,
    * and a single dressing pass over every chamber says only "this is made of ice". A vault, a
    * chapel, a font and a camp are four different rooms in the same stone - and the last two matter
    * most, because a camp that has been dressed as a tomb is a camp a hurt explorer does not trust.
    */
   private enum CryptPiece {
      GATE, HOARD, ALTAR, FONT, CAMP, COLONNADE
   }

   /**
    * Which piece the Frozen Crypt raises in a chamber.
    *
    * <p>The grouping is by what the room is FOR rather than by its name: every room that pays gets
    * the hoard, every room that is looked at gets the altar, every room that is water gets the
    * font, and every room that mends gets the thawed camp - a fire burning in a ring of packed ice,
    * which is the one piece in the set that has to read as warm.
    */
   private static CryptPiece cryptPiece(Chamber chamber) {
      return switch (chamber) {
         case ENTRANCE -> CryptPiece.GATE;
         case TREASURE_VAULT, GILDED_VAULT, WAGER_VAULT, CACHE, ORE_VAULT -> CryptPiece.HOARD;
         case SHRINE, MOONLIT_CHAPEL, GALLERY, OSSUARY, LIBRARY -> CryptPiece.ALTAR;
         case FOUNTAIN, BATHHOUSE, DROWNED_HALL -> CryptPiece.FONT;
         case SANCTUARY, REST_LIBRARY, WAYSTATION -> CryptPiece.CAMP;
         default -> CryptPiece.COLONNADE;
      };
   }

   /** The piece's name - what the audit prints and what the self-test reads. */
   public static String cryptPieceName(Chamber chamber) {
      return switch (cryptPiece(chamber)) {
         case GATE -> "the frozen gate";
         case HOARD -> "the sepulcher hoard";
         case ALTAR -> "the ice altar";
         case FONT -> "the thawed font";
         case CAMP -> "the thawed camp";
         case COLONNADE -> "the ice colonnade";
      };
   }

   /**
    * The Frozen Crypt's dressing: a real blue-ice sepulcher rather than a stone room with snow in
    * it.
    *
    * <p>Four passes, and they are the four things that make a cave read as ice. <b>Icicles</b> come
    * down out of the roof in tapering columns with a blue tip, which is the one shape that says the
    * ceiling is frozen and not merely lit - and no other dungeon in the maze hangs anything from its
    * roof, so it is also how the crypt reads as itself from the doorway. <b>Frozen pools</b> break
    * the floor up into ice and blue ice instead of a single even sheet, which is what frozen water
    * leaves behind. <b>Drift</b> gathers along the walls where snow would actually gather, and
    * <b>spikes</b> stand out of the floor the way they do in a cave that has been freezing for a
    * thousand years.
    *
    * <p>Then the <b>graves</b>: real coffins, in facing rows down both aisles, each one a slab of
    * bone with a lid of packed ice over it and a soul lantern at the head of every other one - the
    * one piece of the crypt that is furniture rather than scenery. Then the face's own <b>set
    * piece</b> in the aisles, so two chambers of this dungeon are never dressed alike.
    *
    * <p>Every write asks {@link #dressable} and refuses {@link #inDoorLane}, exactly as the rest of
    * this pass does: a prop that lands on a chest, a spawner or an archway is a bug, and the crypt
    * has more of all three than any other room in the maze.
    */
   private static void cryptDressing(
      State s, Room room, int bx, int bz, int fy, int top, int first, int span, int ceil
   ) {
      ServerLevel level = s.zoneLevel;
      BlockState floor = floorBlockFor(s.type);
      BlockState pack = Blocks.PACKED_ICE.defaultBlockState();
      BlockState blue = Blocks.BLUE_ICE.defaultBlockState();
      BlockState drift = Blocks.SNOW.defaultBlockState();
      BlockState lamp = lightBlockFor(s.type);

      // --- icicles: the roof of a frozen cave is its most recognisable feature ---
      for (int i = 0; i < 30; i++) {
         int lx = first + RANDOM.nextInt(span);
         int lz = first + RANDOM.nextInt(span);
         if (inDoorLane(room, lx, lz)) {
            continue;
         }
         int length = 2 + RANDOM.nextInt(3);
         boolean clear = true;
         for (int d = 1; d <= length && clear; d++) {
            clear = dressable(level, new BlockPos(bx + lx, ceil - d, bz + lz), null);
         }
         if (!clear) {
            continue;
         }
         for (int d = 1; d <= length; d++) {
            // Packed ice the whole way down and blue at the very tip, so the point reads.
            setIfChanged(level, new BlockPos(bx + lx, ceil - d, bz + lz), d == length ? blue : pack);
         }
      }

      // --- frozen pools: water that stopped where it was ---
      for (int i = 0; i < 12; i++) {
         int lx = first + 1 + RANDOM.nextInt(Math.max(1, span - 2));
         int lz = first + 1 + RANDOM.nextInt(Math.max(1, span - 2));
         if (inDoorLane(room, lx, lz)) {
            continue;
         }
         BlockPos centre = new BlockPos(bx + lx, fy, bz + lz);
         if (!dressable(level, centre, floor)) {
            continue;
         }
         setIfChanged(level, centre, blue);
         for (int[] around : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            // The surface around the deep centre, and snow only where the pool ends.
            BlockPos rim = new BlockPos(bx + lx + around[0], fy, bz + lz + around[1]);
            if (inDoorLane(room, lx + around[0], lz + around[1])) {
               continue;
            }
            setIfChanged(level, rim, RANDOM.nextInt(3) == 0 ? drift : Blocks.ICE.defaultBlockState());
         }
      }

      // --- spikes standing out of the floor ---
      for (int i = 0; i < 14; i++) {
         int lx = first + RANDOM.nextInt(span);
         int lz = first + RANDOM.nextInt(span);
         if (inDoorLane(room, lx, lz)) {
            continue;
         }
         BlockPos cell = new BlockPos(bx + lx, fy, bz + lz);
         if (!dressable(level, cell, floor)) {
            continue;
         }
         boolean clear = true;
         for (int y = 1; y <= 3 && clear; y++) {
            clear = dressable(level, cell.above(y), null);
         }
         if (!clear) {
            continue;
         }
         setIfChanged(level, cell.above(1), pack);
         setIfChanged(level, cell.above(2), pack);
         setIfChanged(level, cell.above(3), blue);
      }

      // --- drift, where snow would actually gather: against the walls ---
      for (int i = 0; i < 34; i++) {
         int lx = first + RANDOM.nextInt(span);
         int lz = first + RANDOM.nextInt(span);
         if (inDoorLane(room, lx, lz)) {
            continue;
         }
         BlockPos cell = new BlockPos(bx + lx, fy, bz + lz);
         if (!dressable(level, cell, floor)) {
            continue;
         }
         setIfChanged(level, cell, Blocks.SNOW_BLOCK.defaultBlockState());
         if (dressable(level, cell.above(), null)) {
            setIfChanged(level, cell.above(), drift);
         }
      }

      // --- the graves: facing rows of ice-lidded coffins down both aisles ---
      for (int lz = first + 2; lz < first + span - 3; lz += 3) {
         for (int lx : new int[]{first + 4, ROOM_PITCH - WALL - 6}) {
            if (inDoorLane(room, lx, lz) || inDoorLane(room, lx, lz + 1)) {
               continue;
            }
            BlockPos cell = new BlockPos(bx + lx, fy, bz + lz);
            if (!dressable(level, cell, floor) || !dressable(level, cell.above(), null)) {
               continue;
            }
            setIfChanged(level, cell, Blocks.BONE_BLOCK.defaultBlockState());
            setIfChanged(level, cell.above(), pack);
            // The head of every other coffin, so the row has a rhythm rather than a strip light.
            if (lz % 2 == 0) {
               BlockPos head = lx > ROOM_PITCH / 2 ? cell.west() : cell.east();
               if (dressable(level, head.above(), null) && !level.getBlockState(head).isAir()) {
                  setIfChanged(level, head.above(), RANDOM.nextInt(3) == 0 ? Blocks.SOUL_LANTERN.defaultBlockState() : lamp);
               }
            }
         }
      }

      // --- and the piece this face is for ---
      int mid = first + span / 2;
      switch (cryptPiece(room.chamber)) {
         case GATE -> {
            // The way in: two blue-ice posts flanking the pad with the site's own lamp on top.
            for (int side = -5; side <= 5; side += 10) {
               int lx = ROOM_PITCH / 2 + side;
               int lz = ROOM_PITCH / 2;
               if (inDoorLane(room, lx, lz)) {
                  continue;
               }
               for (int y = 1; y <= 4; y++) {
                  BlockPos at = new BlockPos(bx + lx, fy + y, bz + lz);
                  if (dressable(level, at, floor)) {
                     setIfChanged(level, at, y == 4 ? blue : pack);
                  } else if (dressable(level, at, null)) {
                     setIfChanged(level, at, y == 4 ? blue : pack);
                  }
               }
               setIfChanged(level, new BlockPos(bx + lx, fy + 5, bz + lz), Blocks.SOUL_LANTERN.defaultBlockState());
            }
         }
         case HOARD -> {
            // A plinth of the crypt's own ice with the hoard frozen inside it.
            for (int dx = -1; dx <= 1; dx++) {
               for (int dz = -1; dz <= 1; dz++) {
                  BlockPos base = new BlockPos(bx + mid + dx, fy, bz + mid + dz);
                  if (inDoorLane(room, mid + dx, mid + dz) || !dressable(level, base, floor)) {
                     continue;
                  }
                  setIfChanged(level, base, pack);
                  if (dx == 0 && dz == 0) {
                     setIfChanged(level, base.above(), Blocks.GOLD_BLOCK.defaultBlockState());
                     setIfChanged(level, base.above(2), blue);
                  }
               }
            }
            setIfChanged(level, new BlockPos(bx + mid, fy + 3, bz + mid), Blocks.SOUL_LANTERN.defaultBlockState());
         }
         case ALTAR -> {
            // Three steps of blue ice with candles standing on the top of it.
            for (int dx = -2; dx <= 2; dx++) {
               for (int dz = -1; dz <= 1; dz++) {
                  BlockPos base = new BlockPos(bx + mid + dx, fy, bz + mid + dz);
                  if (inDoorLane(room, mid + dx, mid + dz) || !dressable(level, base, floor)) {
                     continue;
                  }
                  setIfChanged(level, base, pack);
                  if (Math.abs(dx) <= 1) {
                     setIfChanged(level, base.above(), blue);
                  }
                  if (dx == 0) {
                     setIfChanged(level, base.above(2), blue);
                  }
               }
            }
            setIfChanged(level, new BlockPos(bx + mid, fy + 3, bz + mid), Blocks.SOUL_LANTERN.defaultBlockState());
            for (int dx : new int[]{-1, 1}) {
               setIfChanged(level, new BlockPos(bx + mid + dx, fy + 2, bz + mid + 1), Blocks.SOUL_LANTERN.defaultBlockState());
               setIfChanged(level, new BlockPos(bx + mid + dx, fy + 2, bz + mid - 1), Blocks.SOUL_LANTERN.defaultBlockState());
            }
         }
         case FONT -> {
            // A rim of packed ice with water still standing in it - the thaw in the middle of it.
            for (int dx = -2; dx <= 2; dx++) {
               for (int dz = -2; dz <= 2; dz++) {
                  BlockPos at = new BlockPos(bx + mid + dx, fy, bz + mid + dz);
                  if (inDoorLane(room, mid + dx, mid + dz) || !dressable(level, at, floor)) {
                     continue;
                  }
                  boolean rim = Math.abs(dx) == 2 || Math.abs(dz) == 2;
                  setIfChanged(level, at, rim ? pack : Blocks.ICE.defaultBlockState());
               }
            }
            for (int dx = -1; dx <= 1; dx++) {
               for (int dz = -1; dz <= 1; dz++) {
                  setIfChanged(level, new BlockPos(bx + mid + dx, fy + 1, bz + mid + dz), Blocks.WATER.defaultBlockState());
               }
            }
            setIfChanged(level, new BlockPos(bx + mid, fy + 2, bz + mid), Blocks.SOUL_LANTERN.defaultBlockState());
         }
         case CAMP -> {
            // The one piece that has to read as warm: a fire in a ring of ice, with lanterns.
            for (int dx = -2; dx <= 2; dx++) {
               for (int dz = -2; dz <= 2; dz++) {
                  if (Math.abs(dx) != 2 && Math.abs(dz) != 2) {
                     continue;
                  }
                  BlockPos at = new BlockPos(bx + mid + dx, fy, bz + mid + dz);
                  if (inDoorLane(room, mid + dx, mid + dz) || !dressable(level, at, floor)) {
                     continue;
                  }
                  setIfChanged(level, at, pack);
               }
            }
            setIfChanged(level, new BlockPos(bx + mid, fy + 1, bz + mid), Blocks.CAMPFIRE.defaultBlockState());
            for (int[] corner : new int[][]{{-2, -2}, {-2, 2}, {2, -2}, {2, 2}}) {
               setIfChanged(
                  level,
                  new BlockPos(bx + mid + corner[0], fy + 1, bz + mid + corner[1]),
                  Blocks.SOUL_LANTERN.defaultBlockState()
               );
            }
         }
         default -> {
            // The colonnade: four pillars of blue ice with a cap of snow, standing in the aisles.
            for (int[] at : new int[][]{{-5, -5}, {-5, 5}, {5, -5}, {5, 5}}) {
               int lx = mid + at[0];
               int lz = mid + at[1];
               if (inDoorLane(room, lx, lz)) {
                  continue;
               }
               for (int y = 1; y <= 5; y++) {
                  BlockPos cell = new BlockPos(bx + lx, fy + y, bz + lz);
                  if (!dressable(level, cell, floor) && !dressable(level, cell, null)) {
                     break;
                  }
                  setIfChanged(level, cell, y == 5 ? blue : pack);
               }
               setIfChanged(level, new BlockPos(bx + lx, fy + 6, bz + lz), Blocks.SNOW_BLOCK.defaultBlockState());
            }
         }
      }
   }

   /** The six sides of a cell, for the water seal. */
   private static final int[][] SIDES = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

   /**
    * Seals every body of water in a chamber so it cannot run, with structure void.
    *
    * <p>The problem water has in a written dungeon is that it is <b>water</b>: it finds the one air
    * cell it is allowed to spread into and leaves, and a chamber authored as a flooded hall becomes
    * a dry hall with a wet corner in the first seconds of a run. Barriers under the pool (which the
    * flooded condition already places) fix the floor and nothing else - the surface still has air
    * over it, and a doorway, a broken block or an ore stud is all it takes.
    *
    * <p><b>Structure void</b> is the block that solves this properly, and it is exactly what it was
    * made for. It is invisible, it has no collision, it does not block light, and it is not air, so
    * water cannot spread into it and a player walks through it without noticing. One cell of it in
    * every air cell that touches water turns every pool in the maze into a fish tank: the water
    * stays where it was put, forever, and the room is unchanged to look at and to walk through.
    *
    * <p>The scan covers the room plus one cell in every direction, because water is often in the
    * floor or a wall and its shell is then outside the room's own bounds. Only air is ever written
    * over: a chest, a spawner, a lantern and the water itself are all left exactly as they were.
    *
    * @return how many cells were sealed, for the audit and the self-test
    */
   public static int sealWater(ServerLevel level, int bx, int bz, int fy, int top) {
      BlockState seal = Blocks.STRUCTURE_VOID.defaultBlockState();
      int sealed = 0;
      for (int lx = -1; lx <= ROOM_PITCH; lx++) {
         for (int lz = -1; lz <= ROOM_PITCH; lz++) {
            for (int y = fy - 2; y <= top + 1; y++) {
               BlockPos at = new BlockPos(bx + lx, y, bz + lz);
               if (!level.getBlockState(at).is(Blocks.WATER)) {
                  continue;
               }
               for (int[] side : SIDES) {
                  BlockPos next = at.offset(side[0], side[1], side[2]);
                  if (level.getBlockState(next).isAir()) {
                     setIfChanged(level, next, seal);
                     sealed++;
                  }
               }
            }
         }
      }
      return sealed;
   }

   /**
    * Whether a prop may be written here: the cell is empty, or it is the shell it is meant to
    * replace. Nothing else - a chest, a spawner, an interactable and a rune stone are all things a
    * prop must never land on, and every one of them is simply a block that is neither.
    */
   private static boolean dressable(ServerLevel level, BlockPos pos, BlockState expected) {
      BlockState now = level.getBlockState(pos);
      return now.isAir() || (expected != null && now == expected);
   }

   /** A rare second glowstone escape pad deep in the maze. */
   /**
    * The dressing no single face owns: the furniture that makes a tower of blocks read as a room.
    *
    * <p>Runs last of all, after the face and the dungeon's own props, so it fills what is left and
    * never paints over what a room already has. Every write asks {@link #dressable} first and refuses
    * a doorway, because a sconce in an archway is a wall the maze did not mean to build - and a
    * hundred chambers of shell, face and crates still read as empty without it.
    */
   private static void flourish(State s, Room room, int bx, int bz, int fy, int top) {
      ServerLevel level = s.zoneLevel;
      int first = WALL;
      int last = ROOM_PITCH - WALL - 1;
      int c = ROOM_PITCH / 2;
      BlockState trim = trimBlockFor(s.type);
      BlockState lamp = lightBlockFor(s.type);
      BlockState crate = crateFor(s.type).defaultBlockState();
      BlockState barrel = Blocks.BARREL.defaultBlockState();
      int ceiling = top - 1;
      // Sconces between the pilasters: a bracket of dressed stone with the site's own lamp on it,
      // four blocks up. The pilasters already carry a lamp each, but a twenty-four-wide wall lit by
      // two posts is a dark panel with two bright spots on it.
      for (int lx = first; lx <= last; lx++) {
         for (int lz = first; lz <= last; lz++) {
            if (inDoorLane(room, lx, lz)) {
               continue;
            }
            boolean onWestEast = lx == first || lx == last;
            boolean onNorthSouth = lz == first || lz == last;
            if (!onWestEast && !onNorthSouth) {
               continue;
            }
            boolean between = onWestEast
               ? (lz - WALL) % COFFER == COFFER / 2
               : (lx - WALL) % COFFER == COFFER / 2;
            if (!between) {
               continue;
            }
            BlockPos bracket = new BlockPos(bx + lx, fy + 4, bz + lz);
            if (dressable(level, bracket, null)) {
               setIfChanged(level, bracket, trim);
               setIfChanged(level, bracket.above(), lamp);
            }
         }
      }
      // Chains off the beams, with a lantern on the longer ones. Short by design: a chain that
      // reaches the floor is a tripwire and a lantern at head height is a hitbox in a fight.
      for (int lx = first + 3; lx <= last - 3; lx += COFFER) {
         for (int lz = first + 3; lz <= last - 3; lz += COFFER) {
            if (inDoorLane(room, lx, lz)) {
               continue;
            }
            if (Math.abs(lx - c) < 4 && Math.abs(lz - c) < 4) {
               continue;
            }
            BlockPos hang = new BlockPos(bx + lx, ceiling, bz + lz);
            if (!dressable(level, hang, null)) {
               continue;
            }
            setIfChanged(level, hang, Blocks.IRON_CHAIN.defaultBlockState());
            if (RANDOM.nextBoolean()) {
               BlockPos lower = hang.below();
               if (dressable(level, lower, null)) {
                  setIfChanged(level, lower, Blocks.IRON_CHAIN.defaultBlockState());
                  setIfChanged(level, lower.below(), Blocks.LANTERN.defaultBlockState());
               }
            }
         }
      }
      // A crate or a barrel against the middle of each wall, and a medallion inlaid in the floor at
      // each mid-edge, so a hall's floor reads as laid rather than as poured.
      int[][] wallProps = {
         {first, c + 4}, {first, c - 4}, {last, c + 4}, {last, c - 4},
         {c + 4, first}, {c - 4, first}, {c + 4, last}, {c - 4, last}
      };
      for (int[] at : wallProps) {
         if (inDoorLane(room, at[0], at[1])) {
            continue;
         }
         BlockPos cell = new BlockPos(bx + at[0], fy + 1, bz + at[1]);
         if (dressable(level, cell, null)) {
            setIfChanged(level, cell, RANDOM.nextBoolean() ? crate : barrel);
         }
      }
      int[][] medallions = {{c, first + 1}, {c, last - 1}, {first + 1, c}, {last - 1, c}};
      for (int[] at : medallions) {
         if (inDoorLane(room, at[0], at[1])) {
            continue;
         }
         BlockPos cell = new BlockPos(bx + at[0], fy, bz + at[1]);
         if (dressable(level, cell, floorBlockFor(s.type))) {
            setIfChanged(level, cell, daisBlock(s.type));
         }
      }
   }

   private static void buildExitPad(ServerLevel level, int bx, int bz, int fy, State s) {
      int ox = bx + ROOM_PITCH / 2;
      int oz = bz + ROOM_PITCH / 2;
      for (int x = ox - 1; x <= ox; x++) {
         for (int z = oz - 1; z <= oz; z++) {
            setIfChanged(level, new BlockPos(x, fy, z), Blocks.GLOWSTONE.defaultBlockState());
         }
      }
      level.setBlock(new BlockPos(ox, fy + 2, oz), Blocks.SEA_LANTERN.defaultBlockState(), 2);
      s.exitPad = new BlockPos(ox, fy, oz);
   }

   /** Places an EMPTY loot chest. Chests never hold physical items - opening one
    *  pays out a random cash amount (see onChestOpen). Keeping them empty means
    *  regenerating a dungeon can never break an old chest and spill items on the
    *  floor. The custom name marks the chest's tier and looted state. */
   private static void placeLootChest(ServerLevel level, int x, int y, int z, Type type, boolean rich) {
      level.setBlock(new BlockPos(x, y, z), Blocks.CHEST.defaultBlockState(), 3);
      try {
         BlockEntity be = level.getBlockEntity(new BlockPos(x, y, z));
         if (be instanceof ChestBlockEntity chest) {
            setChestName(chest, rich ? "§6§lRich Loot Chest" : "§e§lLoot Chest");
         }
      } catch (Exception ignored) {
      }
   }

   /** Sets a chest's custom name via its block-entity data components and pushes
    *  the change to clients. Used to mark chest tier and looted state. */
   private static void setChestName(ChestBlockEntity chest, String name) {
      DataComponentMap add = DataComponentMap.builder().set(DataComponents.CUSTOM_NAME, Component.literal(name)).build();
      chest.setComponents(DataComponentMap.composite(chest.collectComponents(), add));
      chest.setChanged();
   }

   /** Writes one block of the dungeon, skipping the write when the block is already right -
    *  a giant room is thousands of blocks and most of the world is already what we want. */
   private static void setIfChanged(ServerLevel level, BlockPos pos, BlockState state) {
      if (level == null) {
         return;
      }
      if (level.getBlockState(pos) != state) {
         level.setBlock(pos, state, 2);
      }
   }

   /**
    * What a dungeon is built OF.
    *
    * <p>A dungeon's shell is rock: the thing the site is cut through. What makes a cavern a crystal
    * cavern is not that its walls are made of crystal - a room with amethyst walls is a room made of
    * one block, and it reads as a flat purple box however many lanterns hang in it. It is that the
    * rock is the ordinary rock of a geode's outside, and the amethyst is IN it: lining the floor,
    * clustering on the ceiling, growing in the corners. Same for a frozen crypt, whose floor was
    * blue ice - the slickest walkable surface in the game, in every chamber of the dungeon - and for
    * a magma forge, which was a room whose whole floor hurt to stand on. Materials are the site; the
    * props in {@link #decorate} are the dungeon.
    */
   private static BlockState wallBlockFor(Type type) {
      return switch (type) {
         case DEEP_MINE -> Blocks.STONE.defaultBlockState();
         case MONSTER_CAVE -> Blocks.COBBLESTONE.defaultBlockState();
         case CRYSTAL_CAVERN -> Blocks.TUFF.defaultBlockState();
         case VOID -> Blocks.OBSIDIAN.defaultBlockState();
         case SUNKEN_TEMPLE -> Blocks.PRISMARINE_BRICKS.defaultBlockState();
         case FROZEN_CRYPT -> Blocks.PACKED_ICE.defaultBlockState();
         case MAGMA_FORGE -> Blocks.BLACKSTONE.defaultBlockState();
         default -> Blocks.STONE.defaultBlockState();
      };
   }

   private static BlockState floorBlockFor(Type type) {
      return switch (type) {
         case DEEP_MINE -> Blocks.DEEPSLATE.defaultBlockState();
         case MONSTER_CAVE -> Blocks.COBBLESTONE.defaultBlockState();
         // The pale middle layer of a geode, not the amethyst itself.
         case CRYSTAL_CAVERN -> Blocks.CALCITE.defaultBlockState();
         case VOID -> Blocks.POLISHED_BLACKSTONE.defaultBlockState();
         case SUNKEN_TEMPLE -> Blocks.PRISMARINE_BRICKS.defaultBlockState();
         // Packed ice walks like stone; blue ice is a skating rink, and it was the floor of every
         // chamber of the crypt.
         case FROZEN_CRYPT -> Blocks.PACKED_ICE.defaultBlockState();
         // Netherrack underfoot with the magma showing through it. The floor of a forge is a place
         // people worked with their shoes on: the burning crust is IN the rock, so the site costs
         // something to walk across rather than being a black floor with some lava in a trench.
         case MAGMA_FORGE -> Blocks.NETHERRACK.defaultBlockState();
         default -> Blocks.STONE.defaultBlockState();
      };
   }

   private static BlockState ceilingBlockFor(Type type) {
      return switch (type) {
         case DEEP_MINE -> Blocks.STONE.defaultBlockState();
         case MONSTER_CAVE -> Blocks.COBBLESTONE.defaultBlockState();
         // The geode's own outer shell overhead, so the amethyst beds below read as growing out of
         // the rock rather than as the rock.
         case CRYSTAL_CAVERN -> Blocks.SMOOTH_BASALT.defaultBlockState();
         case VOID -> Blocks.OBSIDIAN.defaultBlockState();
         case SUNKEN_TEMPLE -> Blocks.PRISMARINE.defaultBlockState();
         case FROZEN_CRYPT -> Blocks.PACKED_ICE.defaultBlockState();
         case MAGMA_FORGE -> Blocks.NETHER_BRICKS.defaultBlockState();
         default -> Blocks.STONE.defaultBlockState();
      };
   }

   /** The dressed stone every hall trims its corners and archways with. */
   private static BlockState trimBlockFor(Type type) {
      return switch (type) {
         case DEEP_MINE -> Blocks.STONE_BRICKS.defaultBlockState();
         case MONSTER_CAVE -> Blocks.MOSSY_STONE_BRICKS.defaultBlockState();
         case CRYSTAL_CAVERN -> Blocks.POLISHED_DEEPSLATE.defaultBlockState();
         case VOID -> Blocks.BLACKSTONE.defaultBlockState();
         case SUNKEN_TEMPLE -> Blocks.PRISMARINE_BRICKS.defaultBlockState();
         // Blue ice belongs on the cornices and the lintels, where nobody walks on it.
         case FROZEN_CRYPT -> Blocks.BLUE_ICE.defaultBlockState();
         case MAGMA_FORGE -> Blocks.NETHER_BRICKS.defaultBlockState();
         default -> Blocks.STONE_BRICKS.defaultBlockState();
      };
   }

   private static BlockState lightBlockFor(Type type) {
      return switch (type) {
         case VOID -> Blocks.SHROOMLIGHT.defaultBlockState();
         case CRYSTAL_CAVERN -> Blocks.SEA_LANTERN.defaultBlockState();
         case SUNKEN_TEMPLE -> Blocks.SEA_LANTERN.defaultBlockState();
         case FROZEN_CRYPT -> Blocks.GLOWSTONE.defaultBlockState();
         case MAGMA_FORGE -> Blocks.SHROOMLIGHT.defaultBlockState();
         default -> Blocks.GLOWSTONE.defaultBlockState();
      };
   }

   private static Block oreFor(Type type, int r, int layer) {
      int rich = layer == 0 ? 0 : 2;
      switch (type) {
         case DEEP_MINE:
            if (r < 4 + rich) return Blocks.COAL_ORE;
            if (r < 8 + rich) return Blocks.IRON_ORE;
            if (r < 11 + rich) return Blocks.GOLD_ORE;
            if (r < 13 + rich) return Blocks.DEEPSLATE_DIAMOND_ORE;
            return r < 14 + rich ? Blocks.EMERALD_ORE : null;
         case MONSTER_CAVE:
            if (r < 4 + rich) return Blocks.COAL_ORE;
            return r < 7 + rich ? Blocks.IRON_ORE : null;
         case CRYSTAL_CAVERN:
            if (r < 5 + rich) return Blocks.GOLD_ORE;
            if (r < 7 + rich) return Blocks.AMETHYST_BLOCK;
            return r < 8 + rich ? Blocks.DIAMOND_ORE : null;
         case VOID:
            if (r < 4 + rich) return Blocks.NETHER_QUARTZ_ORE;
            if (r < 7 + rich) return Blocks.GOLD_ORE;
            return r < 9 + rich ? Blocks.NETHER_GOLD_ORE : null;
         case SUNKEN_TEMPLE:
            if (r < 5 + rich) return Blocks.GOLD_ORE;
            if (r < 7 + rich) return Blocks.DIAMOND_ORE;
            return r < 8 + rich ? Blocks.EMERALD_ORE : null;
         case FROZEN_CRYPT:
            if (r < 5 + rich) return Blocks.IRON_ORE;
            if (r < 8 + rich) return Blocks.GOLD_ORE;
            return r < 10 + rich ? Blocks.DIAMOND_ORE : null;
         case MAGMA_FORGE:
            if (r < 5 + rich) return Blocks.NETHER_QUARTZ_ORE;
            if (r < 8 + rich) return Blocks.NETHER_GOLD_ORE;
            return r < 9 + rich ? Blocks.ANCIENT_DEBRIS : null;
         default:
            return null;
      }
   }

   /** The mob a mob-den spawner produces, per expedition type. */
   private static EntityType<? extends Mob> denMobFor(Type type) {
      return switch (type) {
         case DEEP_MINE -> EntityTypes.CAVE_SPIDER;
         case CRYSTAL_CAVERN -> EntityTypes.VINDICATOR;
         case VOID -> EntityTypes.BLAZE;
         case SUNKEN_TEMPLE -> EntityTypes.DROWNED;
         case FROZEN_CRYPT -> EntityTypes.STRAY;
         case MAGMA_FORGE -> EntityTypes.BLAZE;
         default -> EntityTypes.ZOMBIE;
      };
   }

   // ------------------------------------------------------------------
   // Monsters
   // ------------------------------------------------------------------

   /** The scoreboard team every body in the maze is drawn under: a black outline, no name tag. */
   public static final String PACK_TEAM = "ffmazeglow";
   /** How many names the pack team may hold before it is emptied and built again. */
   private static final int PACK_TEAM_CAP = 512;
   /** How far the glow sweep looks for a mark that no live run owns - a sight radius, in blocks. */
   private static final double GLOW_SWEEP_RADIUS = 64.0;

   /**
    * The tag on every body an expedition has ever put into the world.
    *
    * <p>A site is a permanent place and its pack is spawned persistence-required, so a run that
    * ends - extracted, died, abandoned, crashed out of - leaves its monsters standing in a maze
    * nobody is going to walk into again. The next run of that dungeon, handed the same ground, used
    * to start with the last one's bodies in it, still glowing from a run that finished days ago.
    *
    * <p>This tag is what makes "the bodies this site put here" a question with an answer. It is
    * written at the one factory every expedition monster comes out of (see {@link #newMob}), so
    * there is no list of spawn sites to keep in step, and it is the whole of the predicate the
    * run-end sweep and the site-purge both read. Nothing outside a site ever carries it - not a
    * farm animal, not a villager, not another dungeon's pack - which is exactly why a sweep keyed on
    * it cannot break a farm.
    */
   public static final String ZONE_TAG = "ff_expedition_mob";

   /**
    * How far from a site's own centre a body it spawned may stand and still be counted as its.
    *
    * <p>Four hundred blocks: comfortably wider than the maze a run carves in practice, and five
    * times narrower than the gap to the next site of the same dungeon, which is {@link #SITE_SPACING}
    * - four thousand. So one site's sweep can never reach into another's, and a body that has
    * wandered out of the site entirely is left alone rather than deleted on the far side of the
    * world.
    *
    * <p>It is a safety net rather than the primary answer, which is why it can be this tight: the
    * bodies a run actually spawned are in its own ledger and are removed by name, however far they
    * ran (a straight corridor of sixty chambers is sixteen hundred blocks of nothing, and the run
    * should still take its pack home). The box is what catches a pack no ledger claims - a run whose
    * state was lost, or one from a build before any of this existed - and those are standing in the
    * maze they were spawned in.
    */
   public static final int ZONE_SWEEP_RADIUS = 400;

   /**
    * Marks a body as part of a chamber's pack: it glows, and its glow is BLACK.
    *
    * <p>A glowing entity's outline takes its colour from the team it belongs to, so the black is
    * not a particle trick pretending to be one - it is the outline every client already draws for
    * a glowing body, tinted to a shade that reads as a silhouette in a dim hall rather than as a
    * spotlight. On top of that the pack carries a thin dark aura each tick (see {@link #packAura}),
    * so a body in the far corner of a coffered chamber is a shape with an edge on it instead of
    * one more shadow among the shadows.
    */
   private static void markPack(State s, Mob mob) {
      try {
         mob.setGlowingTag(true);
         mob.setPersistenceRequired();
         Scoreboard board = mob.level().getScoreboard();
         PlayerTeam team = board.getPlayerTeam(PACK_TEAM);
         if (team == null || team.getPlayers().size() > PACK_TEAM_CAP) {
            // The team is rebuilt when it fills: the scoreboard keeps a name for every body that
            // ever joined it, and an unbroken maze would otherwise grow the list forever.
            if (team != null) {
               board.removePlayerTeam(team);
            }
            team = board.addPlayerTeam(PACK_TEAM);
            team.setColor(java.util.Optional.of(TeamColor.BLACK));
            team.setNameTagVisibility(net.minecraft.world.scores.Team.Visibility.NEVER);
         }
         String name = mob.getScoreboardName();
         if (!team.getPlayers().contains(name)) {
            board.addPlayerToTeam(name, team);
            if (s != null) {
               s.packMarks.add(name);
            }
         }
         if (s != null) {
            // The body is remembered even when the team already knew its name - a rebuilt team
            // forgets names it was handed before, and the ledger has to outlive the roster.
            s.markedBodies.add(mob.getUUID());
         }
      } catch (Throwable t) {
         // A pack that fails to be marked is harder to see, never unusable - so this is a debug
         // line rather than a thrown fight, and it says which link broke.
         FortuneFavorsMod.LOGGER.debug("Fortune & Favors: could not mark an expedition pack body", t);
      }
   }

   /**
    * The pack's dark aura: one wisp of ink behind every living body of the chamber being fought.
    *
    * <p>Drawn only for the chamber the player is standing in, because the tell is for the room
    * they are in - a hundred bodies two halls away glowing at once is cost with no viewer.
    */
   private static void packAura(State s, Room room) {
      ServerLevel level = s.zoneLevel;
      for (UUID id : room.monsters) {
         net.minecraft.world.entity.Entity m = level.getEntity(id);
         if (packBodyGone(m)) {
            continue;
         }
         level.sendParticles(
            ParticleTypes.SQUID_INK,
            m.getX(), m.getY() + m.getBbHeight() * 0.5, m.getZ(),
            1, 0.24, 0.3, 0.24, 0.0
         );
      }
   }

   /**
    * Takes one body's glow mark back off it: the tag that draws it and the team that colours it.
    *
    * <p>Both halves or neither, and that is the whole of the bug this exists for. The mark is two
    * mechanisms - {@code glowingTag} on the entity, and membership of the pack team - and the
    * original release emptied only the team. A body with the tag and no team does not stop glowing;
    * it glows <b>white</b>. So every marked monster that outlived its run - and they all outlive
    * their runs, because a marked pack is {@code setPersistenceRequired()} - kept a bright outline
    * for the rest of the server's life, standing exactly where the next run of that same dungeon
    * type is built, since a site is a fixed coordinate per type. That is a body nobody marked,
    * glowing, in a site that had just been carved: read as "passively spawned mobs still glow".
    */
   private static void unmarkBody(net.minecraft.world.entity.Entity entity) {
      if (entity == null) {
         return;
      }
      try {
         if (entity.hasGlowingTag()) {
            entity.setGlowingTag(false);
         }
         Scoreboard board = entity.level().getScoreboard();
         PlayerTeam team = board.getPlayerTeam(PACK_TEAM);
         if (team != null) {
            String name = entity.getScoreboardName();
            if (team.getPlayers().contains(name)) {
               board.removePlayerFromTeam(name, team);
            }
         }
      } catch (Throwable ignored) {
         // A body that keeps a mark it should have lost is a cosmetic problem, not a crash.
      }
   }

   /**
    * A marked body died: take its mark off it and forget it.
    *
    * <p>Called from the death handler, so a kill never waits for the next sweep. It is no longer
    * the only release - a body that is discarded, despawned or unloaded never dies, and those are
    * the ones that used to keep a mark for good - but it is the timely one.
    */
   public static void releaseMark(net.minecraft.world.entity.Entity entity) {
      if (entity == null) {
         return;
      }
      unmarkBody(entity);
      UUID id = entity.getUUID();
      for (State s : active.values()) {
         s.markedBodies.remove(id);
      }
   }

   /**
    * Every hostile body standing in the chamber being fought wears the pack's mark.
    *
    * <p>The mark is written at {@link #newMob}, which is the one door the run's own monsters come out
    * of - so a body the run spawned can never miss it. What can miss it is a body the run did not
    * spawn: a mob den's spawner produces monsters of its own, tick by tick, from a machine the pack
    * mark has never heard of, and those arrive unmarked, in a room the explorer is fighting in.
    * Reported as "the enemies don't glow", which is exactly what it looks like - a chamber where
    * half the pack is an outline and half is a shadow.
    *
    * <p>Marked, and deliberately <i>not</i> added to the chamber's ledger. The glow is what makes a
    * body readable; the ledger is what the chamber has to kill before its doors open, and a spawner
    * that keeps producing would put a body in the ledger that the explorer cannot get rid of - the
    * "I cleared the room and it never opened" bug, written by a helpful fix.
    */
   private static void markRoomBodies(State s, Room room) {
      ServerLevel level = s.zoneLevel;
      int bx = baseX(s, room.rx);
      int bz = baseZ(s, room.rz);
      int fy = floorY(s);
      net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(
         bx + WALL, fy, bz + WALL,
         bx + ROOM_PITCH - WALL + 1.0, fy + ROOM_HEIGHT, bz + ROOM_PITCH - WALL + 1.0
      );
      for (Mob mob : level.getEntitiesOfClass(Mob.class, box)) {
         if (mob.hasGlowingTag() || !(mob instanceof net.minecraft.world.entity.monster.Enemy)) {
            continue;
         }
         markPack(s, mob);
      }
   }

   /**
    * The only writer of the pack's glow, and it both gives it and takes it away.
    *
    * <p>A mark is borrowed rather than owned, and this is the half that makes that true. Every
    * second it walks the ledger of every run alive on this level, drops the bodies that are gone or
    * dead (which is what catches a body that was discarded or unloaded instead of killed - no death
    * handler runs for those, so nothing else ever would), re-asserts the tag on the ones that are
    * still standing, and then strips the mark off anything else in sight that is wearing one.
    *
    * <p>That last pass is the one that matters for a mark that has escaped its bookkeeping: a
    * straggler from a finished run, a body whose team was rebuilt out from under it, a mark left by
    * a version of this file that only emptied the team. Nothing glows in a site unless a run that is
    * running right now says so, which is the property the pack's outline is supposed to have.
    */
   private static void glowSweep(State s, ServerPlayer sp) {
      ServerLevel level = s.zoneLevel;
      java.util.Set<UUID> keep = new HashSet<>();
      for (State other : new ArrayList<>(active.values())) {
         if (other.zoneLevel != level) {
            continue;
         }
         for (UUID id : new ArrayList<>(other.markedBodies)) {
            net.minecraft.world.entity.Entity m = level.getEntity(id);
            if (packBodyGone(m)) {
               other.markedBodies.remove(id);
               unmarkBody(m);
               continue;
            }
            keep.add(id);
         }
      }
      for (UUID id : keep) {
         net.minecraft.world.entity.Entity m = level.getEntity(id);
         if (!packBodyGone(m) && !m.hasGlowingTag()) {
            m.setGlowingTag(true);
         }
      }
      for (Mob m : level.getEntitiesOfClass(Mob.class, sp.getBoundingBox().inflate(GLOW_SWEEP_RADIUS, 24.0, GLOW_SWEEP_RADIUS))) {
         if (keep.contains(m.getUUID())) {
            continue;
         }
         if (m.hasGlowingTag()) {
            unmarkBody(m);
         }
      }
   }

   /**
    * True for a block whose ore pays in coin rather than in kind inside a site.
    *
    * <p>An expedition ore is money: the break credits the run's loot, and the item never exists -
    * see {@code OreDropGuard}, which cancels the drop before it can be picked up. Pocketing a stack
    * of diamonds on the way through a chamber would make the loot ledger a formality, and a run
    * would be worth more for its rock than for the chests it was built around.
    */
   public static boolean orePaysInCoin(BlockState state) {
      return isMineableOre(state);
   }

   /**
    * True for the rock a site keeps while its roof is still up: gravel, and nothing else.
    *
    * <p>A site is built out of gravel in exactly two places - the cracked roof of an unstable
    * chamber and the rubble that roof sheds - and both of them are there to be a hazard rather
    * than a seam. Mining them out quietly handed the explorer a stack of the one block the dungeon
    * keeps dropping on their head, which is a payout the run's ledger never saw and never priced:
    * the chests, the ores and the bounties are the run's income, and the ceiling is not.
    */
   public static boolean rockPaysInNothing(BlockState state) {
      return state.is(Blocks.GRAVEL);
   }

   /**
    * The one question the break guard asks: does this break leave anything behind?
    *
    * <p>Two rules with two different answers about the same site. An ore never drops (it pays the
    * run instead - see {@link #orePaysInCoin}), and gravel drops only once the site has started
    * coming down: the collapse is the last thing a run that went wrong still gets, and the rubble
    * it leaves behind is the one place the maze is allowed to hand over its own rock.
    */
   public static boolean swallowsBreak(UUID id, BlockState state) {
      if (!isInExpedition(id)) {
         return false;
      }
      if (orePaysInCoin(state)) {
         return true;
      }
      State s = active.get(id);
      return s != null && !s.collapsing && rockPaysInNothing(state);
   }

   /**
    * Empties this run out of the pack team, and takes the glow off every body it is still on.
    *
    * <p>A run that ends leaves nothing behind, and "nothing" has to include the mark on the bodies
    * that are still standing - which is most of them, because a marked pack does not despawn. Those
    * bodies are still in the site, and the site is where the next run of this dungeon is built.
    */
   private static void releasePackMarks(State s) {
      for (UUID id : new ArrayList<>(s.markedBodies)) {
         unmarkBody(s.zoneLevel.getEntity(id));
      }
      s.markedBodies.clear();
      if (s.packMarks.isEmpty()) {
         return;
      }
      try {
         Scoreboard board = s.zoneLevel.getServer().getScoreboard();
         PlayerTeam team = board.getPlayerTeam(PACK_TEAM);
         if (team != null) {
            for (String name : s.packMarks) {
               if (team.getPlayers().contains(name)) {
                  board.removePlayerFromTeam(name, team);
               }
            }
         }
      } catch (Throwable ignored) {
      }
      s.packMarks.clear();
   }

   /** The box a site's own bodies have to be inside to count as the site's. */
   private static net.minecraft.world.phys.AABB zoneBox(State s) {
      return new net.minecraft.world.phys.AABB(s.center)
         .inflate(ZONE_SWEEP_RADIUS, 320.0, ZONE_SWEEP_RADIUS);
   }

   /**
    * Takes a site's own bodies out of the world - and nothing else.
    *
    * <p>Two conditions, and both of them are load-bearing. The body has to wear {@link #ZONE_TAG},
    * which is the tag only this class writes and only at {@link #newMob}, so an animal, a villager,
    * a player's pet or another dungeon's pack is not merely unlikely to be caught, it cannot be: the
    * predicate has nothing to match against. And it has to be inside the site's own box, so a body
    * that chased somebody out of the site is not deleted from wherever it ended up.
    *
    * <p>Bodies go with {@code DISCARDED} rather than being killed, deliberately. A kill drops loot,
    * pays a bounty and fires death handlers, and the run this belongs to is over - an expedition that
    * already paid out must not quietly pay a second time for the same monsters as it tidies up
    * after itself.
    *
    * @return how many bodies were removed
    */
   private static int sweepZone(State s) {
      ServerLevel level = s.zoneLevel;
      if (level == null || s.center == null) {
         return 0;
      }
      int removed = 0;
      java.util.Set<UUID> done = new HashSet<>();
      // The run's own ledger first. It is the exact answer to "which bodies are this site's" and it
      // needs no box at all to be right about them, so a pack that chased somebody to the edge of a
      // maze is removed by name rather than looked for.
      for (UUID id : new ArrayList<>(s.markedBodies)) {
         done.add(id);
         net.minecraft.world.entity.Entity body = level.getEntity(id);
         if (packBodyGone(body)) {
            continue;
         }
         unmarkBody(body);
         body.remove(net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
         removed++;
      }
      // ...and then the box, which is what finds a body no ledger claims: a pack from a run whose
      // state was lost, the run's supplier, or anything spawned by a build that predates the tag.
      for (net.minecraft.world.entity.Entity body : level.getEntitiesOfClass(
         net.minecraft.world.entity.Entity.class, zoneBox(s), e -> e.entityTags().contains(ZONE_TAG)
      )) {
         if (done.contains(body.getUUID())) {
            continue;
         }
         unmarkBody(body);
         body.remove(net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
         removed++;
      }
      return removed;
   }

   /**
    * Clears the ground a new site is about to be carved out of.
    *
    * <p>Every run ends with {@link #sweepZone}, so this is the belt to that pair of braces: a run that
    * was interrupted - a server killed mid-collapse, a save rolled back, a state lost - leaves bodies
    * with no run left to sweep them, and the next explorer of that ground is the one who would find
    * them. They are removed here, before the first chamber is built.
    *
    * <p>It also catches the older ghost: a body from a run that predates this tag entirely, or one
    * whose mark outlived its team. Anything standing in the new site wearing a glow it cannot
    * account for is a leftover from somebody else's run, and it is removed rather than inherited.
    * Passively spawned, farm and overworld mobs are never glowing and never tagged, so neither pass
    * can reach one.
    *
    * @return how many bodies were removed
    */
   private static int purgeSite(State s) {
      ServerLevel level = s.zoneLevel;
      if (level == null || s.center == null) {
         return 0;
      }
      int removed = sweepZone(s);
      for (Mob leftover : level.getEntitiesOfClass(Mob.class, zoneBox(s), Mob::hasGlowingTag)) {
         unmarkBody(leftover);
         leftover.remove(net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
         removed++;
      }
      return removed;
   }

   /** Wakes the pack of a chamber: this dungeon's monsters, on the chamber's floor. */
   private static List<Mob> spawnPack(State s, Room room, int count) {
      List<Mob> pack = new ArrayList<>();
      for (int i = 0; i < count; i++) {
         BlockPos spot = packSpot(s, room);
         if (spot == null) {
            continue;
         }
         Mob mob = packMob(s, room);
         mob.setPos(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5);
         // Mostly NORMAL mobs - only a few roll as a rare variant. No name tags anywhere.
         if (RANDOM.nextInt(16) == 0) {
            RareMobVariantManager.applyTier(mob, 1 + RANDOM.nextInt(3));
         }
         markPack(s, mob);
         s.zoneLevel.addFreshEntity(mob);
         pack.add(mob);
      }
      return pack;
   }

   /**
    * One monster of a chamber's pack. The chamber's face decides who wakes up.
    *
    * <p>Every roster here holds several kinds rather than two, because a chamber is what it holds:
    * a nest of nothing but cave spiders and a vault of nothing but vindicators are both one fight
    * repeated, and a pack that mixes a fast body with a ranged one is a room you have to move in.
    * The face's list wins when it has one; otherwise the dungeon's own roster answers, and every
    * dungeon keeps enough variety that two chambers of the same site are not the same fight either.
    */
   private static Mob packMob(State s, Room room) {
      EntityType<? extends Mob> face = switch (room.chamber) {
         case WEB_NEST -> pick(EntityTypes.CAVE_SPIDER, EntityTypes.SPIDER, EntityTypes.SILVERFISH);
         case MENAGERIE -> pick(EntityTypes.POLAR_BEAR, EntityTypes.HOGLIN, EntityTypes.RAVAGER, EntityTypes.CAVE_SPIDER);
         case OUBLIETTE -> pick(EntityTypes.VINDICATOR, EntityTypes.PILLAGER, EntityTypes.WITCH, EntityTypes.EVOKER);
         // Almost all witches, with an evoker now and then wearing the coven's colours. The hall is
         // the one room in the maze whose pack is a single kind on purpose: "a lot of witches" is the
         // request and the fight, and the vexes the occasional evoker brings are the change of pace.
         case ALCHEMY -> alchemyCoven();
         case FORGE_HALL -> pick(EntityTypes.BLAZE, EntityTypes.MAGMA_CUBE, EntityTypes.ZOMBIFIED_PIGLIN);
         case GARDEN -> pick(EntityTypes.CAVE_SPIDER, EntityTypes.SPIDER, EntityTypes.CREEPER);
         case FUNGAL_GROTTO -> pick(EntityTypes.CREEPER, EntityTypes.ZOMBIE, EntityTypes.CAVE_SPIDER, EntityTypes.SPIDER);
         case OSSUARY -> pick(EntityTypes.SKELETON, EntityTypes.STRAY, EntityTypes.WITHER_SKELETON, EntityTypes.ZOMBIE);
         case CLOCKWORKS -> pick(EntityTypes.VINDICATOR, EntityTypes.SKELETON, EntityTypes.ZOMBIE);
         case OBSERVATORY -> pick(EntityTypes.PHANTOM, EntityTypes.VEX, EntityTypes.ENDERMITE, EntityTypes.WITCH);
         case BATHHOUSE -> pick(EntityTypes.DROWNED, EntityTypes.GUARDIAN, EntityTypes.WITCH);
         case GILDED_VAULT -> pick(EntityTypes.VINDICATOR, EntityTypes.EVOKER, EntityTypes.RAVAGER, EntityTypes.PILLAGER);
         case BROKEN_CROSSING -> pick(EntityTypes.SPIDER, EntityTypes.SKELETON, EntityTypes.SLIME);
         case GALLERY -> pick(EntityTypes.VEX, EntityTypes.PHANTOM, EntityTypes.ENDERMITE);
         case LIBRARY -> pick(EntityTypes.WITCH, EntityTypes.VEX, EntityTypes.SKELETON);
         case ARMOURY -> pick(EntityTypes.VINDICATOR, EntityTypes.PILLAGER, EntityTypes.ZOMBIE);
         case LARDER -> pick(EntityTypes.ZOMBIE, EntityTypes.HUSK, EntityTypes.SPIDER, EntityTypes.CREEPER);
         case TRAP_FLOOR -> pick(EntityTypes.CREEPER, EntityTypes.SKELETON, EntityTypes.SPIDER);
         case DROWNED_HALL -> pick(EntityTypes.DROWNED, EntityTypes.GUARDIAN, EntityTypes.DROWNED);
         case SHRINE -> pick(EntityTypes.WITCH, EntityTypes.VEX, EntityTypes.PHANTOM);
         case MOB_DEN -> pick(EntityTypes.ZOMBIE, EntityTypes.SKELETON, EntityTypes.SPIDER, EntityTypes.CREEPER);
         default -> null;
      };
      if (face != null) {
         return newMob(s.zoneLevel, face);
      }
      return switch (s.type) {
         case DEEP_MINE -> newMob(s.zoneLevel, pick(EntityTypes.CAVE_SPIDER, EntityTypes.SILVERFISH, EntityTypes.SKELETON, EntityTypes.ZOMBIE));
         case MONSTER_CAVE -> {
            // One in four is the diamond-clad patrol the site is named for; the rest of the roster
            // keeps a cave of ten chambers from being the same fight in every one of them.
            if (RANDOM.nextInt(4) == 0) {
               Mob z = newMob(s.zoneLevel, EntityTypes.ZOMBIE);
               z.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.DIAMOND_HELMET));
               z.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
               z.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.DIAMOND_LEGGINGS));
               z.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.DIAMOND_BOOTS));
               z.setDropChance(EquipmentSlot.HEAD, 0.05F);
               z.setDropChance(EquipmentSlot.CHEST, 0.05F);
               z.setDropChance(EquipmentSlot.LEGS, 0.05F);
               z.setDropChance(EquipmentSlot.FEET, 0.05F);
               yield z;
            }
            yield newMob(s.zoneLevel, pick(EntityTypes.HUSK, EntityTypes.SKELETON, EntityTypes.CREEPER, EntityTypes.ZOMBIE));
         }
         case CRYSTAL_CAVERN -> newMob(s.zoneLevel, pick(EntityTypes.VINDICATOR, EntityTypes.WITCH, EntityTypes.PILLAGER, EntityTypes.EVOKER));
         case VOID -> newMob(s.zoneLevel, pick(EntityTypes.BLAZE, EntityTypes.WITHER_SKELETON, EntityTypes.PHANTOM, EntityTypes.ENDERMITE));
         // The elder guardian is out of the roster, here and everywhere else in this site. Its
         // mining-fatigue aura is an area effect on a *timer* rather than a fight mechanic, so what
         // it actually contributes to a hall is a player's pickaxe quietly stopping - a punishment
         // for being in the wrong room, arriving from a body they may not even have seen yet. See
         // {@link #SUNKEN_PACK}.
         case SUNKEN_TEMPLE -> newMob(s.zoneLevel, pickOf(SUNKEN_PACK));
         case FROZEN_CRYPT -> newMob(s.zoneLevel, pick(EntityTypes.STRAY, EntityTypes.SKELETON, EntityTypes.ZOMBIE, EntityTypes.POLAR_BEAR));
         case MAGMA_FORGE -> newMob(s.zoneLevel, pick(EntityTypes.BLAZE, EntityTypes.MAGMA_CUBE, EntityTypes.ZOMBIFIED_PIGLIN, EntityTypes.WITHER_SKELETON));
         default -> newMob(s.zoneLevel, pick(EntityTypes.BLAZE, EntityTypes.WITHER_SKELETON, EntityTypes.PHANTOM));
      };
   }

   /** One of the kinds on a roster, chosen evenly. */
   @SafeVarargs
   private static EntityType<? extends Mob> pick(EntityType<? extends Mob>... options) {
      return options[RANDOM.nextInt(options.length)];
   }

   /** One of the kinds on a named roster, chosen evenly. */
   private static EntityType<? extends Mob> pickOf(java.util.List<EntityType<? extends Mob>> options) {
      return options.get(RANDOM.nextInt(options.size()));
   }

   /**
    * The Sunken Temple's hall roster, kept as a name so what is *not* in it can be read back.
    *
    * <p>The elder guardian is out of it, here and in the sentinel and in the reward table, and
    * this is the one copy the switch draws from. Its mining-fatigue aura is an area effect on a
    * timer rather than a fight mechanic, so what it actually contributed to a hall was a player's
    * pickaxe quietly stopping - a punishment for being in the wrong room, arriving from a body
    * they may not even have seen yet. The drowning, the guards and the prismarine were always the
    * temple; the elder guardian was the tide telling you to leave.
    */
   private static final java.util.List<EntityType<? extends Mob>> SUNKEN_PACK = java.util.List.of(
      EntityTypes.DROWNED, EntityTypes.GUARDIAN, EntityTypes.DROWNED, EntityTypes.DROWNED);

   /** A dungeon's hall roster as registry names, for the audit and the self-test. */
   public static java.util.List<String> hallRoster(Type type) {
      java.util.List<EntityType<? extends Mob>> pack =
         type == Type.SUNKEN_TEMPLE ? SUNKEN_PACK : java.util.List.of();
      java.util.List<String> out = new java.util.ArrayList<>();
      for (EntityType<? extends Mob> kind : pack) {
         out.add(kind.builtInRegistryHolder().key().identifier().getPath());
      }
      return out;
   }

   /**
    * Whether a flooded room in this dungeon grows sea pickles on its pool.
    *
    * <p>Every other dungeon keeps them: a pickle is the one thing in a flooded room that says the
    * water has been there long enough to grow something, which is exactly the texture a room the
    * explorer has just broken into wants. The Sunken Temple does not, because the whole temple is
    * already water - a pickle there is a garden pond in the middle of a drowned hall, and it was
    * the block that gave the site away as a decorated room rather than a flooded one.
    */
   public static boolean pondPickles(Type type) {
      return type != Type.SUNKEN_TEMPLE;
   }

   /** The stillroom's roster: three witches in four, and an evoker wearing the coven's colours. */
   private static EntityType<? extends Mob> alchemyCoven() {
      return RANDOM.nextInt(4) < ALCHEMY_WITCHES_IN_FOUR ? EntityTypes.WITCH : EntityTypes.EVOKER;
   }

   /**
    * Rolls whether this chamber holds an Expedition Broker, and plants one when it does.
    *
    * <p>This is the one upgrade the shop sells that the maze itself has to honour: "expedition shop
    * guy spawn chance". A broker is an ordinary villager standing in an otherwise ordinary chamber,
    * and its only job is to open the permanent shop where the explorer already is - so a run can
    * spend what earlier runs earned without walking home first. The roll is per carved chamber, so a
    * long descent meets several of them at the top of the line, and it is skipped entirely for a
    * player who has not bought the line (a zero chance, checked first, so the common case is free).
    */
   private static void maybePlantBroker(State s, Room room) {
      double chance = ExpeditionProgression.brokerChance(s.holder);
      if (chance <= 0.0 || RANDOM.nextDouble() >= chance) {
         return;
      }
      BlockPos spot = packSpot(s, room);
      if (spot == null) {
         return;
      }
      net.minecraft.world.entity.npc.villager.Villager broker =
         EntityTypes.VILLAGER.create(s.zoneLevel, EntitySpawnReason.COMMAND);
      if (broker == null) {
         return;
      }
      // Tagged like a pack body, so the site takes the broker back when the run ends: a merchant
      // left standing in a finished site is a shop in a room nobody is allowed to be in.
      broker.addTag(ZONE_TAG);
      broker.setCustomName(Component.literal(BROKER_NAME));
      broker.setCustomNameVisible(true);
      broker.setPersistenceRequired();
      broker.setPos(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5);
      s.zoneLevel.addFreshEntity(broker);
      s.zoneLevel.sendParticles(
         ParticleTypes.HAPPY_VILLAGER, spot.getX() + 0.5, spot.getY() + 1.2, spot.getZ() + 0.5, 20, 0.5, 0.6, 0.5, 0.05
      );
   }

   /** Spawns a wandering trader NPC that sells emergency supplies for expedition loot. */
   private static void spawnWanderingTrader(State s, ServerPlayer sp) {
      BlockPos spot = randomMazeSpot(s, sp.blockPosition(), 12);
      if (spot == null) {
         spot = sp.blockPosition().above();
      }
      // Use a villager as the trader (WanderingTrader class mapping varies)
      net.minecraft.world.entity.npc.villager.Villager trader = net.minecraft.world.entity.EntityTypes.VILLAGER.create(s.zoneLevel, EntitySpawnReason.COMMAND);
      if (trader == null) return;
      // Hand-built rather than through newMob, so it wears the site's tag here: a supplier standing
      // in a site nobody is in is also a body the site should take back when the run ends.
      trader.addTag(ZONE_TAG);
      trader.setCustomName(Component.literal("\u00a7a\u00a7lExpedition Supplier"));
      trader.setCustomNameVisible(true);
      trader.setPos(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5);
      trader.setPersistenceRequired();
      s.zoneLevel.addFreshEntity(trader);
      s.traderId = trader.getUUID();
      s.zoneLevel.sendParticles(ParticleTypes.HAPPY_VILLAGER, spot.getX() + 0.5, spot.getY() + 1.0, spot.getZ() + 0.5, 15, 0.5, 0.5, 0.5, 0.05);
      Chat.raw(sp, "&aAn &a&lExpedition Supplier&r&a has appeared nearby! Right-click to trade loot for supplies.");
      // Drop supply bundles on the ground as physical items too
      for (int i = 0; i < 3; i++) {
         ItemStack bundle = new ItemStack(Items.BREAD, 8);
         net.minecraft.world.entity.item.ItemEntity ie = new net.minecraft.world.entity.item.ItemEntity(s.zoneLevel, spot.getX() + 0.5, spot.getY() + 1, spot.getZ() + 0.5, bundle);
         s.zoneLevel.addFreshEntity(ie);
      }
      for (int i = 0; i < 2; i++) {
         ItemStack torches = new ItemStack(Items.TORCH, 16);
         net.minecraft.world.entity.item.ItemEntity ie = new net.minecraft.world.entity.item.ItemEntity(s.zoneLevel, spot.getX() + 0.5, spot.getY() + 1, spot.getZ() + 0.5, torches);
         s.zoneLevel.addFreshEntity(ie);
      }
   }

   /** Spawns a mini-boss (enhanced mob) deep in the expedition with guaranteed rare loot. */
   private static void spawnExpeditionMiniBoss(State s, ServerPlayer sp) {
      BlockPos spot = randomMazeSpot(s, sp.blockPosition(), 16);
      if (spot == null) {
         spot = sp.blockPosition().above(2);
      }
      Mob boss = sentinelMob(s);
      boss.setCustomName(Component.literal(s.type.color + "§l§l" + miniBossName(s.type)));
      boss.setCustomNameVisible(true);
      boss.setPos(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5);
      boss.setPersistenceRequired();
      // Scale HP
      var hp = boss.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH);
      if (hp != null) {
         hp.setBaseValue(hp.getBaseValue() * 5.0);
      }
      boss.setHealth(boss.getMaxHealth());
      // Give boss armor
      boss.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
      boss.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
      boss.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.IRON_LEGGINGS));
      boss.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.IRON_BOOTS));
      boss.setDropChance(EquipmentSlot.HEAD, 0.0F);
      boss.setDropChance(EquipmentSlot.CHEST, 0.0F);
      boss.setDropChance(EquipmentSlot.LEGS, 0.0F);
      boss.setDropChance(EquipmentSlot.FEET, 0.0F);
      RareMobVariantManager.applyTier(boss, 5);
      markPack(s, boss);
      s.zoneLevel.addFreshEntity(boss);
      s.miniBossId = boss.getUUID();
      s.zoneLevel.sendParticles(ParticleTypes.SMOKE, spot.getX() + 0.5, spot.getY() + 1.0, spot.getZ() + 0.5, 20, 0.5, 1.0, 0.5, 0.05);
      s.zoneLevel.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, spot.getX() + 0.5, spot.getY() + 1.5, spot.getZ() + 0.5, 15, 0.3, 0.5, 0.3, 0.03);
      Chat.raw(sp, s.type.color + "§l§lA " + miniBossName(s.type) + "§r§7 has appeared! Slay it for bonus loot!");
   }

   private static String miniBossName(Type type) {
      return switch (type) {
         case DEEP_MINE -> "Broodmother";
         case MONSTER_CAVE -> "Undying Champion";
         case CRYSTAL_CAVERN -> "Crystal Warden";
         case VOID -> "Void Reaper";
         case SUNKEN_TEMPLE -> "Tidecaller";
         case FROZEN_CRYPT -> "Grave Chill";
         case MAGMA_FORGE -> "Forge Tyrant";
         default -> "Champion";
      };
   }

   /** The sentinel body of a dungeon - its mini-boss and its floor guardian share it. */
   private static Mob sentinelMob(State s) {
      return switch (s.type) {
         case DEEP_MINE -> newMob(s.zoneLevel, EntityTypes.CAVE_SPIDER);
         case MONSTER_CAVE -> newMob(s.zoneLevel, EntityTypes.ZOMBIE);
         case CRYSTAL_CAVERN -> newMob(s.zoneLevel, EntityTypes.VINDICATOR);
         case VOID -> newMob(s.zoneLevel, EntityTypes.BLAZE);
         case SUNKEN_TEMPLE -> newMob(s.zoneLevel, EntityTypes.DROWNED);
         case FROZEN_CRYPT -> newMob(s.zoneLevel, EntityTypes.STRAY);
         case MAGMA_FORGE -> newMob(s.zoneLevel, EntityTypes.MAGMA_CUBE);
         default -> newMob(s.zoneLevel, EntityTypes.ZOMBIE);
      };
   }

   /** The name of the thing that owns this dungeon's tenth, twentieth, thirtieth chamber. */
   private static String floorBossName(Type type) {
      return switch (type) {
         case DEEP_MINE -> "Stonebreaker, Warden of the Deep";
         case MONSTER_CAVE -> "The Bone Tyrant";
         case CRYSTAL_CAVERN -> "The Crystal Maw";
         case VOID -> "The Void Hierarch";
         case SUNKEN_TEMPLE -> "The Tidebound King";
         case FROZEN_CRYPT -> "The Frost Marquis";
         case MAGMA_FORGE -> "Slagheart, Warden of the Forge";
         default -> "The Floor Guardian";
      };
   }

   /**
    * Wakes a floor guardian and his retinue to hold the descent.
    *
    * <p>The arena is not locked, and deliberately so: the guardian is the gate to the ladder down,
    * not the gate to the way you came in. Fight him or walk around him - these halls are big enough
    * to cross without him now, and a boss you can choose to fight is a boss worth choosing.
    */
   private static void wakeFloorGuardian(State s, Room room, ServerPlayer sp) {
      int floor = Math.max(1, room.depth / 10);
      Mob boss = spawnFloorGuardian(s, room, floor);
      room.monsters.add(boss.getUUID());
      // The fight's memory is per guardian, not per run: a second guardian on floor twenty starts
      // his own cycle rather than inheriting the cooldown and the step counter of the first.
      s.guardianId = boss.getUUID();
      s.guardianSlain = false;
      s.guardianMoveStep = 0;
      s.guardianEnraged = false;
      s.guardianChargeUntil = 0L;
      s.guardianRushing = false;
      s.guardianStaggerUntil = 0L;
      s.guardianWaveUntil = 0L;
      s.guardianWaveRadius = 0.0;
         clearWaveMarks(s);
      s.guardianThrowTick = 0L;
      s.guardianNextMoveTick = ServerClock.clock(s.zoneLevel) + 60L;
      for (int i = 0; i < 2 + Math.min(3, floor); i++) {
         BlockPos spot = packSpot(s, room);
         if (spot == null) {
            continue;
         }
         Mob add = packMob(s, room);
         add.setPos(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5);
         s.zoneLevel.addFreshEntity(add);
         room.monsters.add(add.getUUID());
      }
      Chat.raw(sp, "§4§l━━━ THE FLOOR GUARDIAN AWAKENS ━━━");
      Chat.raw(sp, "§4§l" + floorBossName(s.type) + " §r§7holds the descent.");
      SoundUtil.play(sp, ModSounds.MYSTERY);
      s.zoneLevel.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, sp.getX(), sp.getY() + 1.0, sp.getZ(), 50, 1.2, 1.0, 1.2, 0.05);
      s.zoneLevel.sendParticles(ParticleTypes.SMOKE, sp.getX(), sp.getY() + 1.5, sp.getZ(), 40, 1.5, 1.0, 1.5, 0.02);
   }

   /**
    * The guardian himself: forty times a mini-boss, and no longer a damage sponge with a name.
    *
    * <p>A guardian used to be one line of arithmetic - one body, multiplied health, netherite that
    * never drops - and the fight was the explorer's damage against that number. The number is still
    * here, because a floor boss that folds is not a floor boss, but it is now the floor rather than
    * the fight: what makes him dangerous is {@link #guardianTick}, and what makes him worth fighting
    * is that he can be walked around. Everything below is the part that survives contact - the
    * health, the reach, the resistance to being shoved, and an iron grip on the room so the whole
    * arena is his weapon rather than his hindrance.
    */
   private static Mob spawnFloorGuardian(State s, Room room, int floor) {
      Mob boss = sentinelMob(s);
      double x = baseX(s, room.rx) + ROOM_PITCH / 2 + 2.5;
      double z = baseZ(s, room.rz) + ROOM_PITCH / 2 + 2.5;
      boss.setCustomName(Component.literal("§4§l" + floorBossName(s.type) + " §8· §cFloor " + floor));
      boss.setCustomNameVisible(true);
      boss.setPos(x, floorY(s) + 1, z);
      boss.setPersistenceRequired();
      boss.setCanPickUpLoot(false);
      // Forty times the body he is wearing, inside a band rather than multiplied blind: the six
      // guardian bodies this dungeon can dress him in start anywhere from sixteen to eighty health,
      // and forty times a spider and forty times an elder guardian are not the same boss. The band
      // is what makes floor ten of the Deep Mine and floor ten of the Sunken Temple the same fight
      // with two different movesets, which is the point of the guardian being a person.
      var base = boss.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH);
      setAttribute(
         boss,
         net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH,
         guardianHealthFor(base == null ? 0.0 : base.getBaseValue())
      );
      scaleAttribute(boss, net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE, 2.5);
      scaleAttribute(boss, net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED, 1.15);
      scaleAttribute(boss, net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_KNOCKBACK, 1.5);
      setAttribute(boss, net.minecraft.world.entity.ai.attributes.Attributes.ARMOR, 20.0);
      // Knockback resistance is what stops the fight from being won by a sword with a wind charge
      // behind it: a guardian who can be juggled is a guardian who never gets to spend a move.
      setAttribute(boss, net.minecraft.world.entity.ai.attributes.Attributes.KNOCKBACK_RESISTANCE, 0.85);
      boss.setHealth(boss.getMaxHealth());
      boss.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.NETHERITE_HELMET));
      boss.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.NETHERITE_CHESTPLATE));
      boss.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.NETHERITE_LEGGINGS));
      boss.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.NETHERITE_BOOTS));
      boss.setDropChance(EquipmentSlot.HEAD, 0.0F);
      boss.setDropChance(EquipmentSlot.CHEST, 0.0F);
      boss.setDropChance(EquipmentSlot.LEGS, 0.0F);
      boss.setDropChance(EquipmentSlot.FEET, 0.0F);
      RareMobVariantManager.applyTier(boss, 5);
      markPack(s, boss);
      s.zoneLevel.addFreshEntity(boss);
      return boss;
   }

   /** Multiplies an attribute the body already has. Silent when the body does not have it at all. */
   private static void scaleAttribute(Mob mob, net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attribute, double factor) {
      var instance = mob.getAttribute(attribute);
      if (instance != null) {
         instance.setBaseValue(instance.getBaseValue() * factor);
      }
   }

   /** Sets an attribute outright. Used for the flat values - resistance and armour, not multipliers. */
   private static void setAttribute(Mob mob, net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attribute, double value) {
      var instance = mob.getAttribute(attribute);
      if (instance != null) {
         instance.setBaseValue(value);
      }
   }

   // ------------------------------------------------------------------
   // The floor guardian's moves
   // ------------------------------------------------------------------
   //
   // A rework is new moves, not a bigger health bar, and the guardian's whole problem was that he
   // had none: he walked at you and hit you, which is the fight every other mob in the site already
   // offers. Four moves now, each one a different answer to "where are you standing" - and each
   // guardian only knows three of them, in an order of his own, so the tenth chamber of the Deep
   // Mine is not the tenth chamber of the Sunken Temple with a different colour of rock.
   //
   // Nothing here is announced. The design rule for a rework in this mod is that the fight teaches
   // itself: a slam is an explosion of rock at his feet, a rush is a shoulder coming down the hall,
   // a rockfall is the ceiling opening above you, and a step through the wall is a portal flash
   // behind your back. The only warning is the one the world gives.

   /** Rock. */
   private static final int MOVE_SLAM = 0;
   /** Shoulder first, straight down the line. */
   private static final int MOVE_RUSH = 1;
   /** The ceiling above the explorer, opened in a line. */
   private static final int MOVE_ROCKFALL = 2;
   /** Through the rock and out behind you. */
   private static final int MOVE_WARP = 3;
   /**
    * A pillar of his own arena, torn out and thrown down the lane - the one move that only exists
    * where there is a lane to throw it down.
    *
    * <p>Every other move in the list answers "where are you standing". This one answers "how far
    * away are you": a boss whose only ranged answer is a slow rockfall can be kited across the
    * eighty-four blocks of his own room forever, and a thrown column is what makes the middle of the
    * arena a place you have to keep moving through rather than a place you can circle in.
    */
   public static final int MOVE_PILLAR = 4;
   /** How long the rush lasts, in ticks. */
   private static final int RUSH_TICKS = 22;
   /** How long the arena's shockwave sweeps for, in ticks. */
   private static final int WAVE_TICKS = 62;
   /** How far the shockwave's ring grows per tick, in blocks - the whole room in about a second. */
   private static final double WAVE_SPEED = 0.78;
   /** How long a thrown column is in the air, in ticks. */
   private static final int THROW_TICKS = 24;
   /** How long the guardian is staggered after a rush that connected with nothing. */
   private static final int STAGGER_TICKS = 45;
   /** How far the rush travels per tick, in blocks. Faster than an explorer's sprint, slower
    *  than the guardian wall-teleporting: a rush that outran the player's own swing would be a
    *  move with no counterplay in it at all. */
   private static final double RUSH_SPEED = 1.15;

   /**
    * The health a floor guardian is given, for the body he happens to be wearing.
    *
    * <p>Forty times the body, inside a band: the six bodies a guardian can be span sixteen to
    * eighty health, and forty times a spider and forty times an elder guardian are not the same
    * boss. The band is what makes the guardian a fixed fight rather than a lottery on which mob
    * the dungeon felt like dressing him in. (A test seam as well as the rule - the self-test reads
    * this rather than re-deriving it, so the two cannot drift.)
    */
   public static double guardianHealthFor(double bodyHealth) {
      double scaled = bodyHealth <= 0.0 ? 700.0 : bodyHealth * 40.0;
      return Math.max(500.0, Math.min(1_400.0, scaled));
   }

   /** Test seam: the moves this dungeon's guardian knows, in the order he cycles them. */
   public static List<Integer> guardianMoves(Type type) {
      List<Integer> moves = new ArrayList<>();
      for (int move : guardianMoveOrder(type)) {
         moves.add(move);
      }
      return List.copyOf(moves);
   }

   /**
    * The moves a guardian cycles in the wide arena, in the order he cycles them.
    *
    * <p>Four rather than three, and the fourth is the pillar he can only throw here - the arena is
    * the one room with the colonnade and the lane to throw it down. The chamber list is rotated by
    * the dungeon rather than replaced, so the tenth chamber of the Deep Mine is still not the tenth
    * chamber of the Sunken Temple once the fight moves onto the wide floor.
    */
   private static int[] arenaMoveOrder(Type type) {
      return switch (type) {
         case DEEP_MINE -> new int[] {MOVE_SLAM, MOVE_PILLAR, MOVE_RUSH, MOVE_ROCKFALL};
         case MONSTER_CAVE -> new int[] {MOVE_RUSH, MOVE_PILLAR, MOVE_SLAM, MOVE_ROCKFALL};
         case CRYSTAL_CAVERN -> new int[] {MOVE_PILLAR, MOVE_ROCKFALL, MOVE_SLAM, MOVE_RUSH};
         case VOID -> new int[] {MOVE_PILLAR, MOVE_RUSH, MOVE_ROCKFALL, MOVE_SLAM};
         case SUNKEN_TEMPLE -> new int[] {MOVE_PILLAR, MOVE_SLAM, MOVE_RUSH, MOVE_ROCKFALL};
         case FROZEN_CRYPT -> new int[] {MOVE_ROCKFALL, MOVE_PILLAR, MOVE_RUSH, MOVE_SLAM};
         case MAGMA_FORGE -> new int[] {MOVE_RUSH, MOVE_ROCKFALL, MOVE_PILLAR, MOVE_SLAM};
         default -> new int[] {MOVE_PILLAR, MOVE_SLAM, MOVE_RUSH, MOVE_ROCKFALL};
      };
   }

   /** Test seam: the moves a guardian cycles in an arena, which is one more than in a chamber. */
   public static List<Integer> arenaGuardianMoves(Type type) {
      List<Integer> moves = new ArrayList<>();
      for (int move : arenaMoveOrder(type)) {
         moves.add(move);
      }
      return List.copyOf(moves);
   }

   /** Test seam: the guard-cycle the guardian is actually running right now. */
   public static List<Integer> guardianCycle(Type type, boolean arena) {
      List<Integer> moves = new ArrayList<>();
      for (int move : (arena ? arenaMoveOrder(type) : guardianMoveOrder(type))) {
         moves.add(move);
      }
      return List.copyOf(moves);
   }

   /** The three moves this dungeon's guardian cycles, in the order he cycles them. */
   private static int[] guardianMoveOrder(Type type) {
      return switch (type) {
         case DEEP_MINE -> new int[] {MOVE_SLAM, MOVE_ROCKFALL, MOVE_RUSH};
         case MONSTER_CAVE -> new int[] {MOVE_RUSH, MOVE_SLAM, MOVE_WARP};
         case CRYSTAL_CAVERN -> new int[] {MOVE_ROCKFALL, MOVE_SLAM, MOVE_RUSH};
         case VOID -> new int[] {MOVE_WARP, MOVE_RUSH, MOVE_ROCKFALL};
         case SUNKEN_TEMPLE -> new int[] {MOVE_SLAM, MOVE_WARP, MOVE_RUSH};
         case FROZEN_CRYPT -> new int[] {MOVE_ROCKFALL, MOVE_RUSH, MOVE_SLAM};
         case MAGMA_FORGE -> new int[] {MOVE_SLAM, MOVE_RUSH, MOVE_WARP};
         default -> new int[] {MOVE_SLAM, MOVE_RUSH, MOVE_ROCKFALL, MOVE_WARP};
      };
   }

   /** The one particle that says which dungeon this guardian belongs to. */
   private static net.minecraft.core.particles.SimpleParticleType guardianSpark(Type type) {
      return switch (type) {
         case DEEP_MINE -> ParticleTypes.CRIT;
         case MONSTER_CAVE -> ParticleTypes.SOUL_FIRE_FLAME;
         case CRYSTAL_CAVERN -> ParticleTypes.END_ROD;
         case VOID -> ParticleTypes.PORTAL;
         case SUNKEN_TEMPLE -> ParticleTypes.BUBBLE;
         case FROZEN_CRYPT -> ParticleTypes.ITEM_SNOWBALL;
         case MAGMA_FORGE -> ParticleTypes.FLAME;
         default -> ParticleTypes.CRIT;
      };
   }

   /** The low haze a move throws up - smoke for most, ink for the void, soul fire for the caves. */
   private static net.minecraft.core.particles.SimpleParticleType guardianHaze(Type type) {
      return switch (type) {
         case VOID -> ParticleTypes.SQUID_INK;
         case MONSTER_CAVE -> ParticleTypes.SOUL;
         case FROZEN_CRYPT -> ParticleTypes.WHITE_SMOKE;
         case CRYSTAL_CAVERN -> ParticleTypes.END_ROD;
         default -> ParticleTypes.LARGE_SMOKE;
      };
   }

   /** What he throws, and what the ceiling above you is made of when he pulls it down. */
   private static BlockState guardianDebris(Type type) {
      return switch (type) {
         case DEEP_MINE -> Blocks.STONE.defaultBlockState();
         case MONSTER_CAVE -> Blocks.COBBLESTONE.defaultBlockState();
         case CRYSTAL_CAVERN -> Blocks.CALCITE.defaultBlockState();
         case VOID -> Blocks.OBSIDIAN.defaultBlockState();
         case SUNKEN_TEMPLE -> Blocks.PRISMARINE_BRICKS.defaultBlockState();
         case FROZEN_CRYPT -> Blocks.PACKED_ICE.defaultBlockState();
         case MAGMA_FORGE -> Blocks.MAGMA_BLOCK.defaultBlockState();
         default -> Blocks.STONE.defaultBlockState();
      };
   }

   /** The guardian's damage for one move: bigger deeper, and flavoured by which dungeon he holds. */
   private static double guardianHit(State s, double base) {
      double flavour = switch (s.type) {
         case MAGMA_FORGE, VOID -> 1.2;
         case MONSTER_CAVE, DEEP_MINE -> 1.1;
         case FROZEN_CRYPT -> 0.9;
         default -> 1.0;
      };
      return base * flavour * (1.0 + 0.06 * Math.min(10, Math.max(0, s.deepest / 10)));
   }

   /**
    * The guardian's turn, once a tick.
    *
    * <p>Three states and nothing else: a rush in progress (which drives itself while it lasts), a
    * move on cooldown, and the moment a move fires. The one exception is enrage, which is checked
    * first because it changes the length of every cooldown after it - and which is deliberately not
    * a second phase with a health gate and a speech, because a boss that announces its own good
    * part has spent the surprise it was saving.
    */
   private static void guardianTick(State s, ServerPlayer sp, long tick) {
      if (s.guardianId == null || s.guardianSlain) {
         return;
      }
      net.minecraft.world.entity.Entity found = s.zoneLevel.getEntity(s.guardianId);
      if (!(found instanceof Mob boss) || !boss.isAlive()) {
         s.guardianSlain = true;
         return;
      }
      // Nobody in the room to fight: the cycle waits rather than burning its moves on the walls.
      // The reach is the room's, because the room is what has changed: fifty-two blocks is most of a
      // chamber and less than two thirds of the arena the guardian now holds, and a boss who stands
      // still while an explorer crosses his own hall is a boss with a leash on him.
      double reach = inArena(s, s.body(sp.getUUID()).lastRoom) ? ARENA_SPAN * 0.8 : 52.0;
      if (horizDist2(boss.blockPosition(), sp.blockPosition()) > (long)(reach * reach)) {
         return;
      }
      if (!s.guardianEnraged && boss.getHealth() <= boss.getMaxHealth() / 3.0F) {
         s.guardianEnraged = true;
         boss.addEffect(new net.minecraft.world.effect.MobEffectInstance(MobEffects.SPEED, 20 * 120, 1, false, false, false));
         boss.addEffect(new net.minecraft.world.effect.MobEffectInstance(MobEffects.STRENGTH, 20 * 120, 1, false, false, false));
         boss.addEffect(new net.minecraft.world.effect.MobEffectInstance(MobEffects.RESISTANCE, 20 * 120, 1, false, false, false));
         s.zoneLevel.sendParticles(ParticleTypes.SONIC_BOOM, boss.getX(), boss.getY() + 1.0, boss.getZ(), 3, 0.6, 0.4, 0.6, 0.0);
         s.zoneLevel.sendParticles(guardianSpark(s.type), boss.getX(), boss.getY() + 1.0, boss.getZ(), 60, 1.4, 1.2, 1.4, 0.2);
         s.zoneLevel.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.WARDEN_ROAR, SoundSource.HOSTILE, 1.8F, 0.55F);
      }
      // A rush that hit nothing leaves him standing in the wall he charged into. In a chamber that is
      // a second and a half of a boss doing nothing; in the arena, where a charge is a ten-block
      // commitment, it is the whole reason the dodge is worth doing at all.
      if (tick < s.guardianStaggerUntil) {
         boss.setTarget(null);
         boss.setDeltaMovement(Vec3.ZERO);
         if (tick % 4L == 0L) {
            s.zoneLevel.sendParticles(guardianHaze(s.type), boss.getX(), boss.getY() + 1.4, boss.getZ(), 6, 0.35, 0.35, 0.35, 0.02);
         }
         return;
      }
      // A thrown column is in the air, and where it lands was decided the moment it left his hand.
      if (s.guardianThrowTick > 0L && tick >= s.guardianThrowTick) {
         guardianThrowImpact(s, boss, sp);
      }
      // The shockwave is the slam in here - see guardianSlam - and it keeps sweeping outward after he
      // has already started winding up whatever comes next.
      if (tick < s.guardianWaveUntil) {
         guardianWaveStep(s, boss, sp);
      }
      boolean arena = inArena(s, s.body(sp.getUUID()).lastRoom);
      if (s.guardianRushing) {
         if (tick < s.guardianChargeUntil) {
            guardianRushStep(s, boss, sp);
            return;
         }
         s.guardianRushing = false;
         // He missed, and a charge that ends with nothing in front of it ends in the wall: the
         // stagger is what a missed rush costs him, and it is the only window the arena gives you.
         // Missing means missing everybody - a rush that ran down one of the four and stopped is not
         // a rush that hit a wall.
         boolean reachedAnybody = false;
         for (ServerPlayer victim : crew(s)) {
            if (horizDist2(boss.blockPosition(), victim.blockPosition()) <= 12L) {
               reachedAnybody = true;
               break;
            }
         }
         if (!reachedAnybody) {
            s.guardianStaggerUntil = tick + STAGGER_TICKS;
            s.zoneLevel.sendParticles(ParticleTypes.LARGE_SMOKE, boss.getX(), boss.getY() + 1.0, boss.getZ(), 22, 0.6, 0.6, 0.6, 0.04);
            s.zoneLevel.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.IRON_GOLEM_HURT, SoundSource.HOSTILE, 1.4F, 0.6F);
            boss.setTarget(null);
            return;
         }
      }
      if (tick < s.guardianNextMoveTick) {
         return;
      }
      int[] order = (arena ? arenaMoveOrder(s.type) : guardianMoveOrder(s.type));
      int move = order[s.guardianMoveStep % order.length];
      s.guardianMoveStep++;
      // The wide arena's moves are bigger, so they come round less often; enrage shortens everything
      // by the same fraction it always did.
      long cooldown = s.guardianEnraged ? 50L + RANDOM.nextInt(20) : 80L + RANDOM.nextInt(40);
      if (arena) {
         cooldown = cooldown * 5L / 4L;
      }
      switch (move) {
         case MOVE_SLAM -> {
            guardianSlam(s, boss, sp);
            s.guardianNextMoveTick = tick + cooldown;
         }
         case MOVE_ROCKFALL -> {
            guardianRockfall(s, boss, sp);
            s.guardianNextMoveTick = tick + cooldown;
         }
         case MOVE_WARP -> {
            guardianWarp(s, boss, sp);
            s.guardianNextMoveTick = tick + cooldown;
         }
         case MOVE_PILLAR -> {
            guardianHurl(s, boss, sp);
            s.guardianNextMoveTick = tick + cooldown;
         }
         default -> {
            // The rush owns the next second of the fight, so its cooldown starts when it ends. In the
            // arena it is measured against the distance: a charge that always stopped after its own
            // fixed twenty-two ticks would be a move that cannot cross the room it was built for.
            long rush = RUSH_TICKS;
            if (arena) {
               rush += (long)(Math.sqrt((double)horizDist2(boss.blockPosition(), sp.blockPosition())) * 0.55);
            }
            s.guardianRushing = true;
            s.guardianChargeUntil = tick + rush;
            s.guardianNextMoveTick = tick + rush + cooldown;
            guardianRushStep(s, boss, sp);
         }
      }
   }

   /**
    * One tick of the rush: shoulder down, straight through whatever is in the line.
    *
    * <p>The body is <i>moved</i> rather than pushed. The obvious version of this - write a velocity
    * and let the game carry it - does not work, and not by a little: the run's own tick is the last
    * thing in the server tick, the pack has already walked, and the next thing that happens to this
    * body is its own move control deciding where it wants to go. A delta written here survives
    * exactly until the AI overwrites it one tick later, which is a rush that stands still and
    * smokes. So the step is applied to the position, and only into space the body fits in: a
    * guardian who charges into a wall and stays there for the rest of the move is worse than a
    * guardian who does not charge.
    */
   private static void guardianRushStep(State s, Mob boss, ServerPlayer sp) {
      ServerLevel level = s.zoneLevel;
      Vec3 to = new Vec3(sp.getX() - boss.getX(), 0.0, sp.getZ() - boss.getZ());
      Vec3 dir = to.lengthSqr() < 1.0E-4 ? Vec3.ZERO : to.normalize();
      boss.setDeltaMovement(Vec3.ZERO);
      boss.hurtMarked = true;
      if (to.lengthSqr() > 1.0E-4) {
         net.minecraft.world.phys.AABB moved
            = boss.getBoundingBox().move(dir.x * RUSH_SPEED, 0.0, dir.z * RUSH_SPEED);
         if (level.noCollision(boss, moved)) {
            boss.teleportTo(boss.getX() + dir.x * RUSH_SPEED, boss.getY(), boss.getZ() + dir.z * RUSH_SPEED);
            boss.hurtMarked = true;
         }
      }
      level.sendParticles(guardianHaze(s.type), boss.getX(), boss.getY() + 0.35, boss.getZ(), 8, 0.35, 0.2, 0.35, 0.03);
      level.sendParticles(guardianSpark(s.type), boss.getX(), boss.getY() + 0.9, boss.getZ(), 4, 0.3, 0.3, 0.3, 0.05);
      // The shoulder goes through whatever is in the line, and the line is the room's: a charge that
      // only ever touched one explorer would let a party stand shoulder to shoulder and be charged
      // through as if they were one body.
      for (ServerPlayer victim : crew(s)) {
         if (boss.distanceTo(victim) > 2.7) {
            continue;
         }
         victim.hurtServer(level, level.damageSources().mobAttack(boss), (float)guardianHit(s, 10.0));
         victim.push(dir.x * 2.4, 0.7, dir.z * 2.4);
         level.playSound(null, victim.getX(), victim.getY(), victim.getZ(), SoundEvents.IRON_GOLEM_ATTACK, SoundSource.HOSTILE, 1.1F, 1.3F);
      }
   }

   /**
    * The slam: the floor of the arena leaves the floor.
    *
    * <p>Rock erupts outward in a ring, and everything standing inside the ring is thrown off its
    * feet. The punishment for hugging the boss, and the reason the fight is about distance at all.
    */
   private static void guardianSlam(State s, Mob boss, ServerPlayer sp) {
      ServerLevel level = s.zoneLevel;
      double x = boss.getX();
      double y = boss.getY();
      double z = boss.getZ();
      // The ring is the room's: the slam is the pit's own move and the pit is thirty-six blocks
      // across now, so a ring that stops eight blocks out is a ring with a safe field around it.
      boolean arenaSlam = inArena(s, s.body(sp.getUUID()).lastRoom);
      double ring = arenaSlam ? 10.0 : 4.2;
      double reach = arenaSlam ? 15.0 : 8.0;
      level.sendParticles(new net.minecraft.core.particles.BlockParticleOption(ParticleTypes.BLOCK, guardianDebris(s.type)), x, y + 0.15, z, 90, ring, 0.25, ring, 0.35);
      level.sendParticles(ParticleTypes.EXPLOSION, x, y + 0.4, z, 4, 1.8, 0.2, 1.8, 0.0);
      level.sendParticles(guardianSpark(s.type), x, y + 0.8, z, 70, ring * 0.85, 0.5, ring * 0.85, 0.12);
      level.sendParticles(guardianHaze(s.type), x, y + 0.5, z, 40, ring * 0.75, 0.3, ring * 0.75, 0.05);
      level.playSound(null, x, y, z, SoundEvents.GENERIC_EXPLODE, SoundSource.HOSTILE, 1.7F, 0.55F);
      level.playSound(null, x, y, z, SoundEvents.ANVIL_LAND, SoundSource.HOSTILE, 1.5F, 0.5F);
      // The ring is the pit's own floor leaving the pit, so it is judged against everybody standing
      // in the pit rather than against whoever the tick happens to be running for.
      long reach2 = (long)(reach * reach);
      for (ServerPlayer victim : crew(s)) {
         if (Math.abs(victim.getY() - boss.getY()) > 2.0
            || horizDist2(boss.blockPosition(), victim.blockPosition()) > reach2) {
            continue;
         }
         sparkOut(level, victim.getX(), victim.getY(), victim.getZ(), ring);
         Vec3 away = new Vec3(victim.getX() - x, 0.0, victim.getZ() - z);
         Vec3 dir = away.lengthSqr() < 1.0E-4 ? new Vec3(0.0, 0.0, 1.0) : away.normalize();
         victim.hurtServer(level, level.damageSources().mobAttack(boss), (float)guardianHit(s, 14.0));
         victim.push(dir.x * 1.6, 1.0, dir.z * 1.6);
      }
      // In the wide arena the slam is not over yet: the eruption leaves a wall of rubble behind it
      // that keeps going until it has crossed the floor. Eighty-four blocks is not a bigger arena, it
      // is a longer one, and a one-tick ring eight blocks out is a move that covers a tenth of it and
      // asks the player nothing - see guardianWaveStep.
      if (arenaSlam) {
         long now = ServerClock.clock(s.zoneLevel);
         s.guardianWaveUntil = now + WAVE_TICKS;
         s.guardianWaveRadius = 5.0;
      clearWaveMarks(s);
         level.playSound(null, x, y, z, SoundEvents.WARDEN_STEP, SoundSource.HOSTILE, 1.6F, 0.5F);
         Chat.raw(sp, "§c§lTHE FLOOR BREAKS. §r§7Rubble is running outward from the guardian - §fmind your feet, or get up onto the terrace§7.");
      }
   }

   /**
    * One tick of the arena's shockwave.
    *
    * <p>The ring grows outward from the guardian's feet for about a second, and the only thing that
    * stops it is height: it runs along the floor he is standing on, so the arena's terrace is the
    * answer. That is the whole move - the room's length decides how long you have to get clear, and
    * the room's levels decide whether getting clear is moving away or moving up.
    */
   private static void guardianWaveStep(State s, Mob boss, ServerPlayer sp) {
      ServerLevel level = s.zoneLevel;
      s.guardianWaveRadius += WAVE_SPEED;
      double r = s.guardianWaveRadius;
      double x = boss.getX();
      double y = boss.getY();
      double z = boss.getZ();
      // The ring, drawn where it is this tick rather than as one burst where it started.
      int points = 28;
      for (int i = 0; i < points; i++) {
         double a = Math.PI * 2.0 * i / points;
         double px = x + Math.cos(a) * r;
         double pz = z + Math.sin(a) * r;
         level.sendParticles(
            new net.minecraft.core.particles.BlockParticleOption(ParticleTypes.BLOCK, guardianDebris(s.type)),
            px, y + 0.2, pz, 2, 0.15, 0.25, 0.15, 0.06
         );
         if (i % 4 == 0) {
            level.sendParticles(guardianSpark(s.type), px, y + 0.7, pz, 1, 0.1, 0.3, 0.1, 0.0);
         }
      }
      if (ServerClock.clock(level) % 8L == 0L) {
         level.playSound(null, x, y, z, SoundEvents.WARDEN_STEP, SoundSource.HOSTILE, 1.1F, 0.6F);
      }
      // The wall runs over everybody in the pit. Each explorer is caught once, on the tick the ring
      // crosses their own radius - so the "has it already caught me" mark is per body, and a party
      // strung out across the floor is caught in the order the wall reaches them rather than only
      // the member whose tick arrives first.
      for (ServerPlayer victim : crew(s)) {
         State.Body body = s.body(victim.getUUID());
         if (body.waveHit || Math.abs(victim.getY() - boss.getY()) > 2.0) {
            continue;
         }
         double dist = Math.sqrt((double)horizDist2(boss.blockPosition(), victim.blockPosition()));
         if (Math.abs(dist - r) > 2.4) {
            continue;
         }
         body.waveHit = true;
         Vec3 away = new Vec3(victim.getX() - x, 0.0, victim.getZ() - z);
         Vec3 dir = away.lengthSqr() < 1.0E-4 ? new Vec3(0.0, 0.0, 1.0) : away.normalize();
         victim.hurtServer(level, level.damageSources().mobAttack(boss), (float)guardianHit(s, 12.0));
         victim.push(dir.x * 1.9, 1.1, dir.z * 1.9);
         level.sendParticles(ParticleTypes.EXPLOSION, victim.getX(), victim.getY() + 0.5, victim.getZ(), 2, 0.4, 0.2, 0.4, 0.0);
         level.sendParticles(ParticleTypes.CRIT, victim.getX(), victim.getY() + 1.0, victim.getZ(), 18, 0.4, 0.4, 0.4, 0.15);
         level.playSound(null, victim.getX(), victim.getY(), victim.getZ(), SoundEvents.GENERIC_EXPLODE, SoundSource.HOSTILE, 1.1F, 0.8F);
      }
   }

   /**
    * The thrown column: the arena's own fifth move, and the only one that is about distance.
    *
    * <p>He tears a pillar out of his own colonnade and puts it down the lane, and where it lands was
    * decided the moment it left his hand - a little ahead of the explorer's own feet, because a throw
    * that always landed exactly where they had been standing is a throw nobody has to answer. The
    * impact follows {@link #THROW_TICKS} later rather than being read off the falling block itself,
    * so the move cannot be dodged by the game's own physics deciding to drop it somewhere else.
    */
   private static void guardianHurl(State s, Mob boss, ServerPlayer sp) {
      ServerLevel level = s.zoneLevel;
      double tx = sp.getX() + sp.getDeltaMovement().x * 10.0;
      double tz = sp.getZ() + sp.getDeltaMovement().z * 10.0;
      s.guardianThrowX = tx;
      s.guardianThrowZ = tz;
      s.guardianThrowTick = ServerClock.clock(level) + THROW_TICKS;
      int top = floorY(s) + ARENA_HEIGHT - 3;
      BlockPos from = new BlockPos((int)Math.floor(tx), top, (int)Math.floor(tz));
      try {
         net.minecraft.world.entity.item.FallingBlockEntity rock =
            net.minecraft.world.entity.item.FallingBlockEntity.fall(level, from, guardianDebris(s.type));
         rock.disableDrop();
      } catch (Throwable ignored) {
      }
      // The tear: the column he pulled out, and the lane he threw it down.
      level.sendParticles(guardianHaze(s.type), boss.getX(), boss.getY() + 1.6, boss.getZ(), 30, 0.7, 0.7, 0.7, 0.06);
      level.sendParticles(ParticleTypes.EXPLOSION, boss.getX(), boss.getY() + 1.2, boss.getZ(), 2, 0.5, 0.3, 0.5, 0.0);
      double dx = tx - boss.getX();
      double dz = tz - boss.getZ();
      double span = Math.max(1.0, Math.sqrt(dx * dx + dz * dz));
      for (int i = 1; i <= 12; i++) {
         double t = i / 13.0;
         level.sendParticles(
            guardianSpark(s.type), boss.getX() + dx * t, boss.getY() + 1.4 + Math.sin(t * Math.PI) * 3.0, boss.getZ() + dz * t, 1, 0.1, 0.1, 0.1, 0.0
         );
      }
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.IRON_GOLEM_ATTACK, SoundSource.HOSTILE, 1.5F, 0.5F);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.WITHER_SHOOT, SoundSource.HOSTILE, 0.9F, 0.6F);
      Chat.raw(sp, "§6§lA PILLAR COMES OFF ITS BASE. §r§7He is throwing the arena at you - §fkeep moving sideways§7.");
   }

   /** Where the thrown column lands: a debris ring the width of a doorway, and the hit if you are in it. */
   private static void guardianThrowImpact(State s, Mob boss, ServerPlayer sp) {
      s.guardianThrowTick = 0L;
      ServerLevel level = s.zoneLevel;
      double x = s.guardianThrowX;
      double z = s.guardianThrowZ;
      double y = boss.getY();
      level.sendParticles(
         new net.minecraft.core.particles.BlockParticleOption(ParticleTypes.BLOCK, guardianDebris(s.type)), x, y + 0.2, z, 85, 3.0, 0.3, 3.0, 0.3
      );
      level.sendParticles(ParticleTypes.EXPLOSION, x, y + 0.5, z, 3, 1.6, 0.2, 1.6, 0.0);
      level.sendParticles(guardianHaze(s.type), x, y + 0.6, z, 40, 2.6, 0.4, 2.6, 0.05);
      level.playSound(null, x, y, z, SoundEvents.GENERIC_EXPLODE, SoundSource.HOSTILE, 1.5F, 0.6F);
      level.playSound(null, x, y, z, SoundEvents.STONE_BREAK, SoundSource.HOSTILE, 1.3F, 0.5F);
      BlockPos at = new BlockPos((int)Math.floor(x), (int)Math.floor(y), (int)Math.floor(z));
      // The column lands where it lands, and everything standing there is under it - the whole party,
      // not the one member the throw was aimed at. A pillar put down on a group that only hit one of
      // them would be a move that rewards standing still beside your friend.
      for (ServerPlayer victim : crew(s)) {
         double dist = Math.sqrt((double)horizDist2(at, victim.blockPosition()));
         if (Math.abs(victim.getY() - y) > 3.0 || dist > 5.0) {
            continue;
         }
         victim.hurtServer(level, level.damageSources().mobAttack(boss), (float)guardianHit(s, 16.0));
         victim.push((victim.getX() - x) * 0.5, 0.9, (victim.getZ() - z) * 0.5);
      }
   }

   /**
    * The rockfall: the ceiling above the explorer, opened in a line coming his way.
    *
    * <p>Debris from the guardian's own dungeon falls between him and the explorer rather than on
    * the explorer's head: the ground he is about to stand on is the part of the room he cannot see,
    * and a move that only ever lands where the player already is teaches nothing.
    */
   private static void guardianRockfall(State s, Mob boss, ServerPlayer sp) {
      ServerLevel level = s.zoneLevel;
      // The line of ceiling he pulls down is the arena's own vault, not an ordinary chamber roof.
      int top = floorY(s) + ARENA_HEIGHT - 2;
      // The line of ceiling is sampled finely enough to be a line at whatever distance the fight is
      // being fought at: eight steps across a hall of eighty-four blocks is four gaps with nothing
      // falling in them.
      double apart = Math.sqrt(horizDist2(boss.blockPosition(), sp.blockPosition()));
      int steps = (int)Math.max(8.0, Math.min(28.0, apart / 2.0));
      // ...and it lands where the explorer is going, not where he is: in an eighty-four block room a
      // line thrown at your current feet is a line you walk out of without trying, and the ceiling
      // coming down ahead of you is the difference between a dodge and a decision.
      double leadX = sp.getDeltaMovement().x * 8.0;
      double leadZ = sp.getDeltaMovement().z * 8.0;
      double dx = (sp.getX() + leadX - boss.getX()) / steps;
      double dz = (sp.getZ() + leadZ - boss.getZ()) / steps;
      for (int i = 2; i <= steps; i++) {
         int fx = (int)Math.floor(boss.getX() + dx * i);
         int fz = (int)Math.floor(boss.getZ() + dz * i);
         BlockPos at = new BlockPos(fx, top, fz);
         if (!level.getBlockState(at).isAir() && !level.getBlockState(at.below()).isAir()) {
            continue;
         }
         net.minecraft.world.entity.item.FallingBlockEntity rock =
            net.minecraft.world.entity.item.FallingBlockEntity.fall(level, at, guardianDebris(s.type));
         rock.disableDrop();
         level.sendParticles(guardianHaze(s.type), fx + 0.5, top + 0.6, fz + 0.5, 10, 0.6, 0.5, 0.6, 0.03);
      }
      level.playSound(null, sp.getX(), sp.getY(), sp.getZ(), SoundEvents.WITHER_BREAK_BLOCK, SoundSource.HOSTILE, 1.3F, 0.6F);
      level.playSound(null, sp.getX(), sp.getY(), sp.getZ(), SoundEvents.GRAVEL_BREAK, SoundSource.HOSTILE, 1.2F, 0.7F);
   }

   /**
    * The step through the wall: he is not where he was, he is behind you.
    *
    * <p>The one move that answers the fight's own solution - backing off and using the hall's length
    * as a weapon. A guardian who can shorten the distance at will is a guardian the arena cannot
    * carry, and the arrival is a swing rather than a landing, so the move has teeth and not just
    * suspense. If there is no room behind the explorer the step simply does not happen; it fails
    * loudly in particles rather than putting him inside a wall.
    */
   private static void guardianWarp(State s, Mob boss, ServerPlayer sp) {
      ServerLevel level = s.zoneLevel;
      Vec3 look = sp.getLookAngle();
      double x = sp.getX() - look.x * 3.0;
      double z = sp.getZ() - look.z * 3.0;
      // He arrives at the height the explorer is standing at rather than at the pit's floor: the
      // arena has three levels to it and a step that always landed on the lowest one would be a
      // move that stops working the moment the fight moves up onto the terrace.
      int fy = (int)Math.floor(sp.getY());
      BlockPos landing = new BlockPos((int)Math.floor(x), fy, (int)Math.floor(z));
      level.sendParticles(ParticleTypes.PORTAL, boss.getX(), boss.getY() + 1.0, boss.getZ(), 30, 0.4, 0.6, 0.4, 0.3);
      level.playSound(null, boss.getX(), boss.getY() + 1.0, boss.getZ(), SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE, 0.9F, 0.6F);
      if (!level.getBlockState(landing).isAir() || !level.getBlockState(landing.above()).isAir()
         || level.getBlockState(landing.below()).isAir()) {
         level.sendParticles(guardianHaze(s.type), boss.getX(), boss.getY() + 1.0, boss.getZ(), 12, 0.4, 0.5, 0.4, 0.02);
         return;
      }
      boss.teleportTo(x, fy, z);
      boss.hurtMarked = true;
      level.sendParticles(ParticleTypes.PORTAL, x, fy + 1.0, z, 45, 0.5, 0.7, 0.5, 0.35);
      level.sendParticles(ParticleTypes.SWEEP_ATTACK, x, fy + 1.0, z, 6, 1.8, 0.4, 1.8, 0.0);
      level.playSound(null, x, fy + 1.0, z, SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE, 1.3F, 0.5F);
      if (horizDist2(landing, sp.blockPosition()) <= 16L) {
         sp.hurtServer(level, level.damageSources().mobAttack(boss), (float)guardianHit(s, 12.0));
         sp.push((sp.getX() - x) * 0.8, 0.55, (sp.getZ() - z) * 0.8);
         level.sendParticles(ParticleTypes.CRIT, sp.getX(), sp.getY() + 1.0, sp.getZ(), 20, 0.4, 0.4, 0.4, 0.2);
         level.playSound(null, sp.getX(), sp.getY(), sp.getZ(), SoundEvents.IRON_GOLEM_ATTACK, SoundSource.HOSTILE, 1.0F, 1.4F);
      }
   }

   /** The dust a slam kicks up at the explorer's feet, so the hit is felt where it landed. */
   private static void sparkOut(ServerLevel level, double x, double y, double z, double spread) {
      level.sendParticles(ParticleTypes.CRIT, x, y + 0.3, z, 24, spread, 0.4, spread, 0.25);
   }

   /** The floor guardian's drop: a ladder that skips five chambers of the maze. */
   private static void dropDescentLadder(State s, ServerPlayer sp) {
      ItemStack ladder = new ItemStack(Items.LADDER);
      ladder.set(DataComponents.CUSTOM_NAME, Component.literal("§6§lDescent Ladder"));
      ladder.set(
         DataComponents.LORE,
         new net.minecraft.world.item.component.ItemLore(
            List.of(
               Component.literal("§7The floor guardian's prize."),
               Component.literal("§fRight-click §7to skip the next five chambers"),
               Component.literal("§7of the maze and land far beyond any door on this floor.")
            )
         )
      );
      net.minecraft.world.entity.item.ItemEntity drop =
         new net.minecraft.world.entity.item.ItemEntity(s.zoneLevel, sp.getX(), sp.getY() + 0.5, sp.getZ(), ladder);
      s.zoneLevel.addFreshEntity(drop);
   }

   /** True for the named ladder a floor guardian drops - never for an ordinary ladder. */
   public static boolean isDescentLadder(ItemStack stack) {
      if (stack == null || stack.isEmpty() || !stack.is(Items.LADDER)) {
         return false;
      }
      Component name = stack.get(DataComponents.CUSTOM_NAME);
      return name != null && name.getString().contains("Descent Ladder");
   }

   /**
    * Right-clicking a descent ladder: the shaft drops the explorer into a site that is not the one
    * they were in.
    *
    * <p>It used to move them six chambers across the same maze, which is a shortcut rather than a
    * descent: the exit was the same pad, the compass pointed the same way, and the only thing that
    * changed was how much of the map was already drawn. A descent should cost the explorer
    * everything they know. So the ladder lands them in a brand-new site, thousands of blocks from
    * the one they were standing in, with its own threshold, its own entrance pad and its own
    * lodestone - and therefore its own Return Compass, because the one in their hand points at a
    * place they can no longer walk to.
    *
    * <p>What carries over is the RUN: the clock, the secured loot, the chest ledger, the tally and
    * the pack on their back. What does not carry over is the map.
    *
    * @return an error message, or null on success
    */
   public static String useDescentLadder(ServerPlayer sp, ItemStack held) {
      State s = active.get(sp.getUUID());
      if (s == null || s.zoneLevel != sp.level()) {
         return "The ladder only answers inside a dungeon.";
      }
      ServerLevel zone = s.zoneLevel;
      BlockPos centre = descentSite(s);
      // Who is going down the shaft. The site is the party's, so the ladder is the party's: one find
      // moves the whole group, which is also the only reading of a descent that does not leave
      // somebody behind in a maze that is still falling around them.
      List<UUID> going = crewIds(s);
      if (going.isEmpty()) {
         going = List.of(sp.getUUID());
      }
      // The run moves house. Everything it has earned or spent travels with it; everything that was
      // true about the OLD maze - its rooms, its chests, its lock, its pad - stays there.
      releasePackMarks(s);
      State.Body ladderOwner = s.body(sp.getUUID());
      State next = new State(
         s.type, s.anomaly, ladderOwner.originDim, ladderOwner.originX, ladderOwner.originY,
         ladderOwner.originZ, ladderOwner.yaw, ladderOwner.pitch, zone, centre, s.holder, s.startTick
      );
      next.durationTicks = s.durationTicks;
      next.timeStage = s.timeStage;
      next.runId = s.runId;
      next.lootValue = s.lootValue;
      next.lootCount = s.lootCount;
      next.chestLoot = s.chestLoot;
      next.roomsCleared = s.roomsCleared;
      next.deepest = s.deepest;
      next.guardiansFelled = s.guardiansFelled;
      next.mobsSlain = s.mobsSlain;
      next.oresMined = s.oresMined;
      next.chestsOpened = s.chestsOpened;
      next.descents = s.descents + 1;
      // What has already been banked stays banked. Without this the new site would consider the whole
      // of the run's haul unbanked again, and a party that descended after one of its members had
      // extracted would be paid for that loot a second time on the far side of the shaft.
      next.settledLoot = s.settledLoot;
      next.supplierDemand.putAll(s.supplierDemand);
      // The new site gets the same grace the first one did: every explorer is about to arrive on its
      // pad by teleport, and the pads here are not allowed to mistake that for a walk home.
      next.escapeArmedAt = ServerClock.clock(zone) + SITE_ENTRY_GRACE_TICKS;
      // Everybody's own body travels with them: where each of them came in from is theirs and stays
      // theirs, and the only thing the descent resets is which chamber of the new maze they are
      // standing in. See State.Body.
      next.bodies.putAll(s.bodies);
      for (UUID member : going) {
         active.put(member, next);
      }
      codexDescents++;
      // The new threshold, with its own pad and its own lodestone. The escape area is rebuilt rather
      // than reused: a compass has to have somewhere true to point, and the old pad is now several
      // thousand blocks of solid rock away.
      Room entrance = buildRoom(next, 0, 0, 0, Chamber.ENTRANCE);
      entrance.visited = true;
      entrance.cleared = true;
      next.entrancePad = new BlockPos(baseX(next, 0) + ROOM_PITCH / 2, floorY(next), baseZ(next, 0) + ROOM_PITCH / 2);
      openExits(next, entrance);
      // One ladder, one descent: the find is spent by the explorer who found it, and it is spent for
      // the whole group rather than once per head.
      held.shrink(1);
      MinecraftServer server = zone.getServer();
      int went = 0;
      for (UUID member : going) {
         ServerPlayer down = server == null ? null : server.getPlayerList().getPlayer(member);
         State.Body body = next.body(member);
         body.lastRoom = entrance;
         body.outOfSiteTicks = 0;
         body.atExitPad = false;
         body.exitPadHold = 0;
         body.hasLeftEntrance = false;
         if (down == null) {
            continue;
         }
         went++;
         teleportPlayer(
            down, zone, next.entrancePad.getX() + 0.5, standY(next.entrancePad), next.entrancePad.getZ() + 0.5
         );
         withdrawExitCompass(down);
         giveExitCompass(down, next);
         SoundUtil.play(down, ModSounds.TRANSFER);
         zone.sendParticles(ParticleTypes.PORTAL, down.getX(), down.getY() + 1.0, down.getZ(), 90, 0.7, 1.0, 0.7, 0.09);
         Chat.raw(down, "§6§lTHE LADDER SWALLOWS YOU WHOLE.");
         if (went > 1) {
            Chat.raw(
               down,
               "§7" + sp.getName().getString() + " found it, and it took the party with it: §f" + went + " of you§7."
            );
         }
         Chat.raw(
            down,
            "§7You come to in a §fchamber you have never seen§7, at a §fnew threshold§7 - a different site, "
               + "a new pad, and a §bReturn Compass§7 that points at §fit§7."
         );
         Chat.raw(
            down,
            "§7The run carries over: §a" + Chat.moneyStr(next.lootValue) + "§7 secured, §f"
               + Math.max(0L, next.durationTicks / 20L / 60L) + "m§7 on the clock. §8The bag and everything "
               + "already banked came down with you."
         );
      }
      return null;
   }

   /**
    * Where a descent lands: a site in this dungeon's own lane, far from every other one.
    *
    * <p>Each dungeon type owns a column of sites running north from its own origin, 4000 blocks a
    * step, so the second descent of a Deep Mine can never land on the Sunken Temple's first, nor on
    * another Deep Mine's. Four thousand is forty-odd maze cells of clearance in every direction,
    * which is far more than a run can carve.
    */
   private static BlockPos descentSite(State s) {
      return acquireSite(s.zoneLevel, s.type, s.holder);
   }

   /**
    * How many bodies the site sends when it turns on an explorer, as a function of its own clock.
    *
    * <p>The avalanche used to be four plus six at its worst, which on a Deep Mine - the site every
    * new explorer is sent into, and the one whose waves are cave spiders - was ten venomous bodies
    * arriving behind a body whose health budget had already been cut. The last act still escalates
    * with depth, but it escalates from three and stops at seven, and the second act is unchanged: the
    * pressure is the point, the stampede was the mistake.
    *
    * <p>A seam rather than a number in the tick so the ladder can be asserted without standing a
    * body in a site for twelve minutes - and so the "the swarms got easier" claim is a number the
    * harness can read rather than a sentence in a changelog.
    */
   public static int swarmSize(int stage, int depth) {
      if (stage < 2) {
         return 0;
      }
      int escalation = Math.min(4, Math.max(0, depth) / 8);
      return stage >= 3 ? 3 + escalation : 2;
   }

   /**
    * What a site event's own wave is cut by.
    *
    * <p>Both of the site's swarm shapes were too much at once - the timed avalanche (see
    * {@link #swarmSize}) and the random "SWARM" event, which arrives on top of whatever chamber the
    * explorer is already fighting in. Every event wave goes through here, so the trim is one number
    * for all of them rather than four literals that a future event will copy from the wrong one. The
    * floor keeps even the smallest wave a wave.
    */
   public static final int EVENT_SWARM_TRIM = 2;

   /** Test seam: a site event's wave, trimmed. */
   public static int eventSwarm(int rolled) {
      return Math.max(3, rolled - EVENT_SWARM_TRIM);
   }

   /**
    * The body a Deep Mine swarm wave sends, on a roll of 0..3 - one in four is the spider that
    * poisons.
    *
    * <p>The first site's wave used to be a coin toss between a cave spider and a skeleton, which is
    * the worst pairing in the mod to hand a beginner: venom that cannot be dodged, arriving in a pack,
    * in the one site whose whole job is to teach the mode. A wave is now mostly bodies with no venom
    * in them, and the cave spider is one rung of four - still present, still worth avoiding, and no
    * longer the default answer to "what is coming". The site's chambers are untouched: this is the
    * wave, not the bestiary.
    */
   public static EntityType<? extends Mob> deepMineSwarmBody(int roll) {
      return switch (Math.floorMod(roll, 4)) {
         case 0 -> EntityTypes.CAVE_SPIDER;
         case 1 -> EntityTypes.SPIDER;
         case 2 -> EntityTypes.SKELETON;
         default -> EntityTypes.ZOMBIE;
      };
   }

   private static void spawnWave(State s, BlockPos center, Type type, int count, int heat) {
      ServerLevel level = s.zoneLevel;
      for (int i = 0; i < count; i++) {
         BlockPos spot = randomMazeSpot(s, center, 24);
         if (spot == null) {
            continue;
         }
         if (heat >= 3 && RANDOM.nextInt(5) == 0) {
            // Mythic Hunter: strong but NO netherite gear - the old netherite
            // zombie was way too strong for expeditions. Iron kit at best.
            Mob hunter = newMob(level, EntityTypes.ZOMBIE);
            hunter.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
            hunter.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
            hunter.setDropChance(EquipmentSlot.HEAD, 0.05F);
            hunter.setDropChance(EquipmentSlot.CHEST, 0.05F);
            hunter.setPos(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5);
            RareMobVariantManager.applyTier(hunter, 4);
            hunter.setPersistenceRequired();
            markPack(s, hunter);
            level.addFreshEntity(hunter);
            continue;
         }
         Mob mob = switch (type) {
            case DEEP_MINE -> newMob(level, deepMineSwarmBody(RANDOM.nextInt(4)));
            case MONSTER_CAVE -> {
               Mob z = newMob(level, EntityTypes.ZOMBIE);
               z.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.DIAMOND_HELMET));
               z.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
               z.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.DIAMOND_LEGGINGS));
               z.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.DIAMOND_BOOTS));
               z.setDropChance(EquipmentSlot.HEAD, 0.05F);
               z.setDropChance(EquipmentSlot.CHEST, 0.05F);
               z.setDropChance(EquipmentSlot.LEGS, 0.05F);
               z.setDropChance(EquipmentSlot.FEET, 0.05F);
               yield z;
            }
            case CRYSTAL_CAVERN -> RANDOM.nextBoolean() ? newMob(level, EntityTypes.VINDICATOR) : newMob(level, EntityTypes.WITCH);
            case VOID -> RANDOM.nextBoolean() ? newMob(level, EntityTypes.BLAZE) : newMob(level, EntityTypes.WITHER_SKELETON);
            case SUNKEN_TEMPLE -> RANDOM.nextBoolean() ? newMob(level, EntityTypes.DROWNED) : newMob(level, EntityTypes.GUARDIAN);
            case FROZEN_CRYPT -> RANDOM.nextBoolean() ? newMob(level, EntityTypes.STRAY) : newMob(level, EntityTypes.SKELETON);
            case MAGMA_FORGE -> RANDOM.nextBoolean() ? newMob(level, EntityTypes.BLAZE) : newMob(level, EntityTypes.MAGMA_CUBE);
            default -> RANDOM.nextBoolean() ? newMob(level, EntityTypes.BLAZE) : newMob(level, EntityTypes.WITHER_SKELETON);
         };
         mob.setPos(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5);
         if (RANDOM.nextInt(16) == 0) {
            RareMobVariantManager.applyTier(mob, 1 + RANDOM.nextInt(3));
         }
         markPack(s, mob);
         level.addFreshEntity(mob);
      }
   }

   /**
    * The one factory every expedition monster comes out of, and therefore where the site's own tag
    * is written.
    *
    * <p>Tagged here rather than at each of the twenty-odd spawn sites on purpose: a spawn that
    * forgot it would be a body neither the run-end sweep nor the site purge could see, and keeping a
    * list of call sites in step is exactly the kind of bookkeeping that goes stale. There is one
    * door; this is it. The trader, which is built by hand rather than through here, is tagged where
    * it is built.
    */
   private static Mob newMob(ServerLevel level, EntityType<? extends Mob> type) {
      Mob mob = type.create(level, EntitySpawnReason.COMMAND);
      if (mob != null) {
         mob.addTag(ZONE_TAG);
      }
      return mob;
   }

   private static void teleportPlayer(ServerPlayer sp, ServerLevel level, double x, double y, double z) {
      sp.teleport(new TeleportTransition(level, new Vec3(x, y, z), Vec3.ZERO, sp.getYRot(), sp.getXRot(), TeleportTransition.PLACE_PORTAL_TICKET));
   }

   // ------------------------------------------------------------------
   // Random events
   // ------------------------------------------------------------------

   /**
    * A loot goblin: fast, lightly built, and worth more than anything else in the chamber.
    *
    * <p>Every event in the maze is something that comes at the player. This one runs - the reward
    * is for catching it, and the crown on its head is the only gold in the dungeon you cannot
    * simply take off a shelf.
    */
   private static void spawnLootGoblin(State s, ServerPlayer sp) {
      BlockPos spot = randomMazeSpot(s, sp.blockPosition(), 18);
      if (spot == null) {
         spot = sp.blockPosition().above();
      }
      Mob goblin = newMob(s.zoneLevel, EntityTypes.ZOMBIE_VILLAGER);
      goblin.setCustomName(Component.literal("§6§lLoot Goblin"));
      goblin.setCustomNameVisible(true);
      goblin.addEffect(new net.minecraft.world.effect.MobEffectInstance(
         net.minecraft.world.effect.MobEffects.SPEED, 20 * 900, 1, false, false, true
      ));
      goblin.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.GOLD_BLOCK));
      goblin.setDropChance(EquipmentSlot.HEAD, 0.0F);
      goblin.setPos(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5);
      markPack(s, goblin);
      s.zoneLevel.addFreshEntity(goblin);
      s.zoneLevel.sendParticles(
         ParticleTypes.HAPPY_VILLAGER, spot.getX() + 0.5, spot.getY() + 1.0, spot.getZ() + 0.5, 20, 0.5, 0.6, 0.5, 0.05
      );
      Chat.raw(sp, "§6§lA LOOT GOBLIN! §r§7Catch it before it gets away - the bounty is §a" + Chat.moneyStr(LOOT_GOBLIN_BOUNTY) + "§7.");
   }

   /** What the goblin is worth. High by design: it is the one thing in here that runs. */
   public static final long LOOT_GOBLIN_BOUNTY = 9_000L;

   /**
    * Right-click on a block inside a dungeon.
    *
    * <p>Returns what the click did, or null when the block is not one of this run's interactables
    * and the click should fall through to everything else a right-click usually means.
    */
   public static InteractionResult onInteract(ServerPlayer sp, BlockPos pos) {
      State s = active.get(sp.getUUID());
      if (s == null || s.zoneLevel != sp.level()) {
         return null;
      }
      Interact kind = s.props.get(pos);
      if (kind == null) {
         return null;
      }
      return switch (kind) {
         case WAGER -> useWager(sp, s);
         case RELIQUARY -> useReliquary(sp, s, pos);
         case RUNE -> useRune(sp, s, pos);
         case SUPPLY -> useSupply(sp, s, pos);
         case ELIXIR -> useElixir(sp, s, pos);
      };
   }

   /** The wager pedestal: stake secured loot on a coin toss, and the coin is honest. */
   private static InteractionResult useWager(ServerPlayer sp, State s) {
      if (s.lootValue < WAGER_STAKE) {
         Chat.raw(sp, "§eThe pedestal wants §6" + Chat.moneyStr(WAGER_STAKE) + "§e staked, and you have §6" + Chat.moneyStr(s.lootValue) + "§e secured.");
         SoundUtil.play(sp, ModSounds.DENY);
         return InteractionResult.SUCCESS;
      }
      s.lootValue -= WAGER_STAKE;
      if (RANDOM.nextBoolean()) {
         s.lootValue += WAGER_STAKE * 2L;
         Chat.raw(sp, "§6§lTHE COIN LANDS YOUR WAY! §r§a+" + Chat.moneyStr(WAGER_STAKE) + " §7secured.");
         SoundUtil.play(sp, ModSounds.JOB_COMPLETE);
         s.zoneLevel.sendParticles(ParticleTypes.FIREWORK, sp.getX(), sp.getY() + 1.2, sp.getZ(), 25, 0.6, 0.5, 0.6, 0.05);
      } else {
         Chat.raw(sp, "§c§lTHE COIN LANDS AGAINST YOU. §r§7Stake lost - §c-" + Chat.moneyStr(WAGER_STAKE) + "§7 secured loot.");
         SoundUtil.play(sp, ModSounds.DENY);
         s.zoneLevel.sendParticles(ParticleTypes.SMOKE, sp.getX(), sp.getY() + 1.2, sp.getZ(), 20, 0.5, 0.5, 0.5, 0.02);
      }
      return InteractionResult.SUCCESS;
   }

   /** A sealed reliquary: usually money, sometimes a spring-loaded surprise. */
   private static InteractionResult useReliquary(ServerPlayer sp, State s, BlockPos pos) {
      s.props.remove(pos);
      setIfChanged(s.zoneLevel, pos, Blocks.AIR.defaultBlockState());
      s.zoneLevel.sendParticles(ParticleTypes.LARGE_SMOKE, pos.getX() + 0.5, pos.getY() + 0.7, pos.getZ() + 0.5, 14, 0.3, 0.3, 0.3, 0.03);
      if (RANDOM.nextInt(3) == 0) {
         // Trapped: no blast, deliberately - a reliquary that breaches a chamber's floor is a
         // dungeon that can be taken apart from the inside. The surprise is the pack it wakes.
         sp.addEffect(new net.minecraft.world.effect.MobEffectInstance(
            net.minecraft.world.effect.MobEffects.POISON, 20 * 6, 0, false, false, true
         ));
         sp.hurt(sp.damageSources().magic(), 4.0F);
         spawnWave(s, pos, s.type, 3, heatOf(s));
         Chat.raw(sp, "§c§lIT WAS TRAPPED! §r§7Something was waiting in the lining.");
         SoundUtil.play(sp, ModSounds.DENY);
      } else {
         long pay = 2_500L + 700L * Math.max(1, s.deepest);
         pay = (long)(pay * s.anomaly.chestMul);
         s.lootValue += pay;
         s.lootCount++;
         Chat.raw(sp, "§e§lThe reliquary gives. §r§a+" + Chat.moneyStr(pay) + " §7secured.");
         SoundUtil.play(sp, ModSounds.JOB_COMPLETE);
         s.zoneLevel.sendParticles(ParticleTypes.END_ROD, pos.getX() + 0.5, pos.getY() + 0.7, pos.getZ() + 0.5, 16, 0.3, 0.3, 0.3, 0.05);
      }
      return InteractionResult.SUCCESS;
   }

   /** One stone of a chamber's rune lock. Light them all and the vault behind them opens. */
   private static InteractionResult useRune(ServerPlayer sp, State s, BlockPos pos) {
      Room room = s.rooms.get(roomKeyOf(s, pos));
      if (room == null || room.runesTotal <= 0) {
         return InteractionResult.SUCCESS;
      }
      if (room.runesOpened) {
         Chat.raw(sp, "§7This lock has already answered.");
         return InteractionResult.SUCCESS;
      }
      if (!s.runesLit.add(pos)) {
         Chat.raw(sp, "§5The stone is already lit. §7" + room.runesLit + " of " + room.runesTotal + " stones burn.");
         return InteractionResult.SUCCESS;
      }
      room.runesLit++;
      setIfChanged(s.zoneLevel, pos, Blocks.SHROOMLIGHT.defaultBlockState());
      s.zoneLevel.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, pos.getX() + 0.5, pos.getY() + 0.6, pos.getZ() + 0.5, 20, 0.3, 0.3, 0.3, 0.03);
      SoundUtil.play(sp, ModSounds.MYSTERY);
      if (room.runesLit >= room.runesTotal) {
         room.runesOpened = true;
         s.lootValue += RUNE_PAYOUT;
         s.lootCount++;
         Chat.raw(sp, "§5§lTHE RUNE LOCK OPENS. §r§a+" + Chat.moneyStr(RUNE_PAYOUT) + " §7secured, and the strongbox is yours.");
         SoundUtil.play(sp, ModSounds.JOB_COMPLETE);
         s.zoneLevel.sendParticles(ParticleTypes.FIREWORK, pos.getX() + 0.5, pos.getY() + 1.2, pos.getZ() + 0.5, 30, 0.8, 0.6, 0.8, 0.05);
      } else {
         Chat.raw(sp, "§5A rune takes the light. §7" + room.runesLit + "/" + room.runesTotal + " stones burn.");
      }
      return InteractionResult.SUCCESS;
   }

   /**
    * A supply crate: a patch-up and something to carry on with.
    *
    * <p>The heal is the point of the crate and it is now worth walking to - four hearts, the largest
    * single mend in the maze, plus a burst of light around the lid so the click is visibly a patch-up
    * rather than a line of chat. What it is not is a full heal; see {@link #SUPPLY_HEAL}.
    */
   private static InteractionResult useSupply(ServerPlayer sp, State s, BlockPos pos) {
      s.props.remove(pos);
      setIfChanged(s.zoneLevel, pos, Blocks.AIR.defaultBlockState());
      float healed = mendAmount(SUPPLY_HEAL, sp.getUUID());
      sp.heal(healed);
      com.fortuneandfavors.util.InventoryHelper.giveOrDrop(sp, new ItemStack(Items.BREAD, 8));
      com.fortuneandfavors.util.InventoryHelper.giveOrDrop(sp, new ItemStack(Items.TORCH, 16));
      s.zoneLevel.sendParticles(ParticleTypes.HEART, pos.getX() + 0.5, pos.getY() + 1.2, pos.getZ() + 0.5, 18, 0.6, 0.6, 0.6, 0.02);
      s.zoneLevel.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, 14, 0.4, 0.5, 0.4, 0.06);
      s.zoneLevel.sendParticles(ParticleTypes.HAPPY_VILLAGER, pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, 14, 0.5, 0.5, 0.5, 0.02);
      Chat.raw(
         sp,
         "§a§lSUPPLY CRATE OPEN. §r§7Bandages, bread and light - §f+" + (int)Math.ceil(healed) + " health§7 back."
      );
      SoundUtil.play(sp, ModSounds.JOB_COMPLETE);
      return InteractionResult.SUCCESS;
   }

   /** What one draught out of a witch's cauldron is worth before the site's own scale is applied. */
   public static final float HEAL_DRAUGHT = 12.0F;
   /**
    * How much of a draught's mend is left for each one the run has already drunk.
    *
    * <p>The stillroom is the one room in the maze that hands out healing rather than paying for it,
    * and a hall that heals full every time is a hall a run stops leaving - two of them in one run
    * used to be six full draughts and six bottles, which is more health than a whole expedition's
    * worth of camps. So the cures are a well rather than a shelf: the first draught of a run is worth
    * what it says on the cauldron, and every one after it pays half of the one before, down to a floor
    * that is still worth having. The first hall is a real reason to walk into a hurt room; the second
    * is a fight with a patch-up at the end of it, and the camps stay the thing the health budget is
    * actually written around.
    */
   public static final float DRAUGHT_FALLOFF = 0.5F;
   /** The mend a draught never falls below, however many the run has already drunk. */
   public static final float DRAUGHT_FLOOR = 1.0F;
   /**
    * How many Potion of Healing bottles one RUN gets out of the stillrooms, however many it finds.
    *
    * <p>The bottle is the part of a draught that leaves the room, and it is the part the falloff
    * above cannot price - a potion drunk three chambers later is worth exactly what it was worth when
    * it was handed over. So it is capped instead: the coven had one bottle left, the first cauldron in
    * the run gives it, and every cauldron after that is a mend and nothing to carry.
    */
   public static final int DRAUGHT_BOTTLES_PER_RUN = 1;
   /**
    * Ticks between one of the coven's drinks and the next - the clock the player races.
    *
    * <p>This is the whole reason the hall has to be fought fast: the witches drink their own cures,
    * and every cauldron they drain is a cauldron the explorer does not get. Three seconds is long
    * enough for a player who bursts in swinging to take the first pot and short enough that a slow
    * fight leaves the room dry.
    */
   public static final int COVEN_DRINK_TICKS = 60;
   /**
    * How far below full a witch has to be before she reaches for a cauldron.
    *
    * <p>Not half, and not all the way to dead: a coven that tops every member up the moment they are
    * scratched is impossible to actually clear, and one that only drinks at the last breath is a
    * mechanic nobody ever sees. At this much of her health gone the pot goes to the witch who is worst
    * off, which is what the room's own fight is about - the pack has to come down fast or its hurts
    * get undone.
    */
   public static final float COVEN_THIRST_FRACTION = 0.6F;
   /**
    * What one cauldron is worth to the witch who drains it: four hearts.
    *
    * <p>More than a draught is worth to the player, deliberately, and not a balance mistake: the
    * explorer's mend is passed through the site's own scale ({@link #MEND_SCALE}) and the pack's
    * is not, so the numbers only line up if the witch's is written against the same half-scaled mend
    * the player's is - this is a cauldron's worth to somebody who does not have to walk home with it.
    */
   public static final float COVEN_DRAUGHT_HEAL = 8.0F;

   /**
    * What a draught pays in a run that has already drunk this many: half of the last one, floored.
    *
    * <p>See {@link #DRAUGHT_FALLOFF} for why the ladder exists at all. It is a test seam as well as
    * the rule the cauldron obeys, so a future pass cannot quietly turn the well back into a shelf.
    */
   public static float draughtMend(int alreadyDrunk) {
      return draughtMend(alreadyDrunk, null);
   }

   /** The same draught, read through the drinker's own line - see {@link #siteMendScale}. */
   public static float draughtMend(int alreadyDrunk, UUID uuid) {
      float full = mendAmount(HEAL_DRAUGHT, uuid);
      if (alreadyDrunk <= 0) {
         return full;
      }
      return Math.max(DRAUGHT_FLOOR, full * (float)Math.pow(DRAUGHT_FALLOFF, alreadyDrunk));
   }

   /** Whether the run's one bottle is still in a cauldron - see {@link #DRAUGHT_BOTTLES_PER_RUN}. */
   public static boolean draughtBottle(int bottlesTaken) {
      return bottlesTaken < DRAUGHT_BOTTLES_PER_RUN;
   }

   /**
    * A cauldron left on the boil: an instant mend, and a Potion of Healing to carry out of the room.
    *
    * <p>The heal is the instant kind rather than the over-time kind the camps hand out - the alchemy
    * hall is a room you walk into hurt and leave patched, and it is the only place in the maze that
    * hands a body health and a bottle in the same click. It is deliberately neither a full heal nor a
    * shelf: three cauldrons stand in the hall, each of them answers exactly once, and after that the
    * room is a fight and a chest again. The bottle is the part that outlives the room - a real Potion
    * of Healing, which is the one thing a run can carry out of a dungeon that is still worth
    * something on the way home.
    */
   private static InteractionResult useElixir(ServerPlayer sp, State s, BlockPos pos) {
      s.props.remove(pos);
      // The cauldron is still standing when it is empty - the boil is the prop, not the pot.
      setIfChanged(s.zoneLevel, pos, Blocks.CAULDRON.defaultBlockState());
      // The well, not the shelf: what this draught is worth depends on how many the run has already
      // drunk, and what it carries depends on whether the coven's one bottle has been taken yet. See
      // draughtMend and draughtBottle.
      float healed = draughtMend(s.draughtsDrunk, sp.getUUID());
      boolean bottle = draughtBottle(s.draughtBottlesTaken);
      s.draughtsDrunk++;
      sp.heal(healed);
      if (bottle) {
         s.draughtBottlesTaken++;
         com.fortuneandfavors.util.InventoryHelper.giveOrDrop(
            sp, net.minecraft.world.item.alchemy.PotionContents.createItemStack(
               Items.POTION, net.minecraft.world.item.alchemy.Potions.HEALING
            )
         );
      }
      s.zoneLevel.sendParticles(ParticleTypes.HEART, pos.getX() + 0.5, pos.getY() + 1.1, pos.getZ() + 0.5, 16, 0.5, 0.5, 0.5, 0.02);
      s.zoneLevel.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, 12, 0.4, 0.5, 0.4, 0.06);
      s.zoneLevel.sendParticles(ParticleTypes.HAPPY_VILLAGER, pos.getX() + 0.5, pos.getY() + 1.2, pos.getZ() + 0.5, 12, 0.5, 0.4, 0.5, 0.02);
      Chat.raw(
         sp,
         "§c§lHEALING DRAUGHT. §r§7+" + (int)Math.ceil(healed) + " health now"
            + (bottle
               ? ", and a §fPotion of Healing§7 for the road."
               : ". §8The stillroom's one bottle is already gone - a draught thins the more of it you drink.")
      );
      SoundUtil.play(sp, ModSounds.JOB_COMPLETE);
      return InteractionResult.SUCCESS;
   }

   /**
    * The coven at its cauldrons: a hurt witch draining one of her own cures.
    *
    * <p>The alchemy hall is the only room in the maze that heals whoever holds it, and holding it is
    * a race. Three cauldrons stand in the middle of the hall; every few seconds, the witch who is
    * worst off in the pack drinks from one of them and the pot behind her is gone. So the cures cut
    * both ways: the pack is the biggest in the site and it gets bigger the longer it stands, and the
    * player who fights it slowly is fighting a pack that is patching itself up between blows.
    *
    * <p>Three rules keep it a race rather than a grind. She only drinks when she is properly hurt
    * ({@link #COVEN_THIRST_FRACTION}), so a fight the player is winning fast never pays for her; she
    * only drinks while the explorer is standing in the hall, because a cauldron drained behind
    * somebody's back is a cure they never had a chance at; and the room's cauldrons run out, which is
    * what makes bursting in and taking the pots yourself the greedy play rather than a formality.
    */
   private static void covenTick(State s, Room room, ServerPlayer sp, long tick) {
      if (room.chamber != Chamber.ALCHEMY || room.cleared) {
         return;
      }
      if (s.nextCovenDrinkTick == 0L) {
         // The witches take a moment to get to the pots, so a player who bursts in swinging has the
         // first cauldron before the coven answers.
         s.nextCovenDrinkTick = tick + COVEN_DRINK_TICKS;
         return;
      }
      if (tick < s.nextCovenDrinkTick) {
         return;
      }
      s.nextCovenDrinkTick = tick + COVEN_DRINK_TICKS;
      // What is still on the boil. The room's own list of props rather than the run's, so a cauldron
      // in another hall is never drunk from here.
      List<BlockPos> pots = new ArrayList<>();
      for (BlockPos p : room.props) {
         if (s.props.get(p) == Interact.ELIXIR) {
            pots.add(p);
         }
      }
      if (pots.isEmpty()) {
         return;
      }
      // The draught goes to whoever is worst off, which is the rule the room reads as: the pack is
      // not topped up evenly, its hurtest member is put back on her feet.
      net.minecraft.world.entity.monster.Witch drinker = null;
      double worst = 1.0;
      for (UUID id : room.monsters) {
         net.minecraft.world.entity.Entity m = s.zoneLevel.getEntity(id);
         if (!(m instanceof net.minecraft.world.entity.monster.Witch witch) || packBodyGone(witch)) {
            continue;
         }
         double frac = witch.getHealth() / Math.max(1.0F, witch.getMaxHealth());
         if (frac < worst) {
            worst = frac;
            drinker = witch;
         }
      }
      if (drinker == null || worst > COVEN_THIRST_FRACTION) {
         return;
      }
      // The pot nearest the witch, so the drink happens where the fight is.
      BlockPos pot = pots.get(0);
      double best = Double.MAX_VALUE;
      for (BlockPos p : pots) {
         double d = horizDist2(p, drinker.blockPosition());
         if (d < best) {
            best = d;
            pot = p;
         }
      }
      s.props.remove(pot);
      setIfChanged(s.zoneLevel, pot, Blocks.CAULDRON.defaultBlockState());
      drinker.heal(COVEN_DRAUGHT_HEAL);
      s.zoneLevel.playSound(
         null, drinker.getX(), drinker.getY() + 1.0, drinker.getZ(),
         SoundEvents.WITCH_DRINK, SoundSource.HOSTILE, 1.2F, 0.8F
      );
      BossVfx.at(
         s.zoneLevel, pot.getX() + 0.5, pot.getY() + 1.1, pot.getZ() + 0.5, 32.0,
         ParticleTypes.HEART, 12, 0.5, 0.5, 0.5, 0.02
      );
      BossVfx.column(
         s.zoneLevel, new net.minecraft.world.phys.Vec3(pot.getX() + 0.5, pot.getY() + 1.0, pot.getZ() + 0.5),
         3.0, ParticleTypes.CAMPFIRE_COSY_SMOKE, 2
      );
      int left = Math.max(0, pots.size() - 1);
      actionBar(sp, "§5§lTHE COVEN DRINKS §7· " + left + " cauldron" + (left == 1 ? "" : "s") + " left on the boil");
      Chat.raw(
         sp,
         "§5§lTHE COVEN DRINKS. §r§7The worst-off witch drains a cauldron - "
            + Math.max(0, pots.size() - 1) + " left standing. §fKill them faster than they can drink."
      );
   }

   /**
    * A rising column of light over every supply crate within sight.
    *
    * <p>A crate is worth four hearts and a stack of light, which is the difference between a run that
    * keeps going and one that ends at the next pack - and a crate in a coffered hall is a wooden box
    * among wooden boxes. The camps solved this with a beacon of their own (see {@link #campBeacon}),
    * so the crates get the same treatment: a line of sparks drawn once a second, and only for a crate
    * the explorer could actually see. The cost is one distance check per crate of the run, and the
    * run only ever holds the crates it has already walked past.
    */
   private static void crateHalo(State s, ServerPlayer sp) {
      for (Map.Entry<BlockPos, Interact> e : s.props.entrySet()) {
         // The healing draughts get the same halo as the crates, because the same thing is wrong with
         // both of them: a barrel among barrels is furniture, and an unopened cauldron in a hall full
         // of cauldrons is the one thing in the room a hurt explorer is looking for.
         if (e.getValue() != Interact.SUPPLY && e.getValue() != Interact.ELIXIR) {
            continue;
         }
         BlockPos c = e.getKey();
         double dx = c.getX() + 0.5 - sp.getX();
         double dz = c.getZ() + 0.5 - sp.getZ();
         if (dx * dx + dz * dz > CRATE_SIGHT * CRATE_SIGHT) {
            continue;
         }
         double y = c.getY() + 1.1;
         for (int i = 0; i < 12; i++) {
            s.zoneLevel.sendParticles(ParticleTypes.HAPPY_VILLAGER, c.getX() + 0.5, y + i * 0.5, c.getZ() + 0.5, 1, 0.16, 0.0, 0.16, 0.0);
         }
         s.zoneLevel.sendParticles(ParticleTypes.HEART, c.getX() + 0.5, y + 0.4, c.getZ() + 0.5, 2, 0.5, 0.3, 0.5, 0.01);
      }
   }

   /** True for the chambers that mend you for standing in them - the camps. */
   public static boolean isRestRoom(Chamber c) {
      return c == Chamber.SANCTUARY || c == Chamber.REST_LIBRARY || c == Chamber.WAYSTATION
         || c == Chamber.CALM_CAMP;
   }

   /**
    * What one beat of a resting place gives.
    *
    * <p>The reading room mends a little better than the bare sanctuary, because it is the one that
    * costs something to reach: a camp you have to find is worth more than a room you fall into. The
    * calm camp is the top of that ladder rather than a rung on it - it is the room the whole healing
    * budget is written around, it pays twice what the shrine's floor pays, and a run that finds one
    * early is a run that goes deeper, which is the trade it is there to offer.
    */
   public static float restMend(Chamber c) {
      return mendAmount(restBase(c));
   }

   /** The same beat, read through the resting body's own line - see {@link #siteMendScale}. */
   public static float restMend(Chamber c, UUID uuid) {
      return mendAmount(restBase(c), uuid);
   }

   /** What one beat of a resting place is worth before anything is scaled - one ladder, twice read. */
   private static float restBase(Chamber c) {
      return switch (c) {
         case CALM_CAMP -> REST_MEND * 2.0F;
         case REST_LIBRARY -> REST_MEND * 1.5F;
         case WAYSTATION -> REST_MEND * 1.25F;
         default -> REST_MEND;
      };
   }

   /**
    * The camps, made findable.
    *
    * <p>A rest site you cannot find is not a rest site, and the maze is a hundred chambers of the
    * same four walls - so every camp within a hundred and ten blocks burns a column of light from
    * its floor to its roof, once a second, drawn from the server and costing one distance check per
    * camp for anybody who is nowhere near one. Between that and {@link #campHint} at every chamber
    * mouth, "where is the nearest camp" has an answer that is not luck.
    */
   private static void campBeacon(State s, Room room, ServerPlayer sp) {
      for (Room r : s.rooms.values()) {
         if (!isRestRoom(r.chamber) || r == room) {
            continue;
         }
         BlockPos c = roomSpot(s, r);
         if (c == null) {
            continue;
         }
         double dx = c.getX() + 0.5 - sp.getX();
         double dz = c.getZ() + 0.5 - sp.getZ();
         if (dx * dx + dz * dz > 110.0 * 110.0) {
            continue;
         }
         double y = floorY(s) + 0.6;
         for (int i = 0; i < 20; i++) {
            s.zoneLevel.sendParticles(ParticleTypes.END_ROD, c.getX() + 0.5, y + i, c.getZ() + 0.5, 1, 0.02, 0.0, 0.02, 0.0);
         }
         s.zoneLevel.sendParticles(ParticleTypes.HEART, c.getX() + 0.5, y + 2.4, c.getZ() + 0.5, 2, 0.7, 0.7, 0.7, 0.01);
      }
   }

   /** Once per chamber, on the way in: if a camp is a chamber or two away, say which way it is. */
   private static void campHint(State s, Room room, ServerPlayer sp) {
      Room best = null;
      int bestD = Integer.MAX_VALUE;
      for (Room r : s.rooms.values()) {
         if (!isRestRoom(r.chamber) || r == room) {
            continue;
         }
         int d = Math.abs(r.rx - room.rx) + Math.abs(r.rz - room.rz);
         if (d < bestD) {
            bestD = d;
            best = r;
         }
      }
      if (best == null || bestD > 3) {
         return;
      }
      int dx = best.rx - room.rx;
      int dz = best.rz - room.rz;
      String dir = Math.abs(dx) >= Math.abs(dz)
         ? (dx > 0 ? "east" : "west")
         : (dz > 0 ? "south" : "north");
      Chat.raw(
         sp,
         "§d§lA CAMP §r§8· §7a chamber that mends is §f" + bestD + "§7 chamber(s) §f" + dir
            + "§7, under a column of light."
      );
   }

   /** Fires one random site event. The label it sets is echoed in the action bar. */
   private static void fireEvent(State s, ServerPlayer sp) {
      ServerLevel level = s.zoneLevel;
      int roll = RANDOM.nextInt(100);
      if (roll < 30) {
         int placed = 0;
         BlockPos at = sp.blockPosition();
         for (int i = 0; i < 16; i++) {
            BlockPos p = at.offset(RANDOM.nextInt(9) - 4, -1 - RANDOM.nextInt(2), RANDOM.nextInt(9) - 4);
            if (!isGenerated(s, p) || level.getBlockState(p).isAir()) {
               continue;
            }
            level.setBlock(p, oreFor(s.type, i * 7 + RANDOM.nextInt(5), 3).defaultBlockState(), 3);
            placed++;
         }
         if (placed > 0) {
            banner(s, sp, "§a§lORE RUSH", level, at, ParticleTypes.END_ROD);
         }
      } else if (roll < 52) {
         BlockPos p = chestSpot(s, sp);
         if (p != null) {
            placeLootChest(level, p.getX(), p.getY(), p.getZ(), s.type, true);
            banner(s, sp, "§6§lSUPPLY DROP", level, p, ParticleTypes.FIREWORK);
         } else {
            spawnWave(s, sp.blockPosition(), s.type, eventSwarm(4 + RANDOM.nextInt(3)), heatOf(s) + 1);
            banner(s, sp, "§c§lSWARM", level, sp.blockPosition(), ParticleTypes.SMOKE);
         }
      } else if (roll < 70) {
         spawnWave(s, sp.blockPosition(), s.type, eventSwarm(5 + RANDOM.nextInt(5)), heatOf(s) + 1);
         banner(s, sp, "§c§lSWARM", level, sp.blockPosition(), ParticleTypes.SMOKE);
      } else if (roll < 80) {
         spawnLootGoblin(s, sp);
         banner(s, sp, "§6§lLOOT GOBLIN", level, sp.blockPosition(), ParticleTypes.HAPPY_VILLAGER);
      } else if (roll < 90) {
         if (s.miniBossId == null) {
            spawnExpeditionMiniBoss(s, sp);
            banner(s, sp, "§4§lSENTINEL", level, sp.blockPosition(), ParticleTypes.SOUL_FIRE_FLAME);
         } else {
            spawnWave(s, sp.blockPosition(), s.type, eventSwarm(4 + RANDOM.nextInt(3)), heatOf(s) + 1);
            banner(s, sp, "§c§lSWARM", level, sp.blockPosition(), ParticleTypes.SMOKE);
         }
      } else {
         sp.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.DARKNESS, 260, 0, false, false, true));
         spawnWave(s, sp.blockPosition(), s.type, eventSwarm(4 + RANDOM.nextInt(3)), heatOf(s) + 1);
         banner(s, sp, "§8§lBLACKOUT", level, sp.blockPosition(), ParticleTypes.SQUID_INK);
      }
   }

   private static void banner(State s, ServerPlayer sp, String label, ServerLevel level, BlockPos at, net.minecraft.core.particles.SimpleParticleType particle) {
      long elapsed = ServerClock.clock(level) - s.startTick;
      s.eventLabel = label;
      s.eventLabelUntil = elapsed + 200L;
      Chat.raw(sp, label + " §r§7- a site event is under way!");
      SoundUtil.play(sp, ModSounds.MYSTERY);
      level.sendParticles(particle, at.getX() + 0.5, at.getY() + 1.2, at.getZ() + 0.5, 30, 0.8, 0.6, 0.8, 0.05);
   }

   /** True if the block sits inside a chamber this run has actually carved. */
   private static boolean isGenerated(State s, BlockPos p) {
      return s.rooms.containsKey(roomKeyOf(s, p));
   }

   /** Finds an open, on-floor spot near the player for a dropped chest. */
   private static BlockPos chestSpot(State s, ServerPlayer sp) {
      ServerLevel level = s.zoneLevel;
      BlockPos at = sp.blockPosition();
      for (int tries = 0; tries < 24; tries++) {
         BlockPos p = at.offset(RANDOM.nextInt(11) - 5, RANDOM.nextInt(3) - 2, RANDOM.nextInt(11) - 5);
         if (!isGenerated(s, p)) {
            continue;
         }
         BlockState here = level.getBlockState(p);
         BlockState below = level.getBlockState(p.below());
         if (here.isAir() && !below.isAir() && level.getBlockState(p.above()).isAir()) {
            return p;
         }
      }
      return null;
   }

   // ------------------------------------------------------------------
   // Run end
   // ------------------------------------------------------------------

   /**
    * The squad card, as the lines it prints.
    *
    * <p>Pure, and that is the point: the arithmetic a squad card is worth having for - who carried
    * the most, and who is on it only because they were carried - is a comparison, not a rendering.
    * The rows arrive in no particular order and leave in the order the run happened: whoever filled
    * the bag most first, then the most chests, then by name, so two equally useful explorers are not
    * ordered by hash.
    */
   public static List<String> squadLines(List<SquadRow> rows, long haul) {
      List<String> out = new ArrayList<>();
      out.add("§8§m                                        ");
      out.add("§6§lSQUAD CARD§r §8· §7one site, one bag: §a" + Chat.moneyStr(haul) + "§7 carried out");
      List<SquadRow> sorted = new ArrayList<>(rows);
      sorted.sort(
         Comparator.comparingLong(SquadRow::carried).reversed()
            .thenComparing(Comparator.comparingInt(SquadRow::chests).reversed())
            .thenComparing(SquadRow::name)
      );
      for (SquadRow r : sorted) {
         out.add(
            "§f" + r.name() + " §8- §a" + Chat.moneyStr(r.carried()) + "§7 carried §8· §f" + r.chests()
               + "§7 chests §8· §f" + r.revives() + "§7 rescues §8· §f" + r.downs() + "§7 downs"
         );
      }
      out.add(
         "§7Most carried: " + award(sorted, SquadRow::carried)
            + " §8· §7Most chests: " + award(sorted, SquadRow::chests)
            + " §8· §7Most rescues: " + award(sorted, SquadRow::revives)
            + " §8· §7Taken down: " + award(sorted, SquadRow::downs)
      );
      out.add("§8§m                                        ");
      return out;
   }

   /**
    * One explorer's line on the squad card.
    *
    * <p>Named rather than keyed, because a card is read by people: the name is the whole of what a
    * row is, and the four numbers are the four questions a party asks each other afterwards.
    */
   public record SquadRow(String name, long carried, int chests, int downs, int revives) {
   }

   /** The member with the most of something, named - or nobody, which is what a zero is. */
   private static String award(List<SquadRow> rows, java.util.function.ToLongFunction<SquadRow> of) {
      String best = null;
      long most = 0L;
      for (SquadRow r : rows) {
         long value = of.applyAsLong(r);
         if (value > most) {
            most = value;
            best = r.name();
         }
      }
      return best == null ? "§8nobody" : "§f" + best;
   }

   /**
    * Prints the squad card to everybody who was in the site, once the last of them is out of it.
    *
    * <p>A solo run has its own card and does not need this one. What this one is for is the thing a
    * party's run makes invisible: four people splitting one bag and one pot, where "the run went
    * well" says nothing about who was actually useful - and where the person who picked somebody up
    * is otherwise paid exactly what the person who wandered off and got downed was paid.
    */
   private static void squadCard(State s) {
      MinecraftServer server = s.zoneLevel.getServer();
      if (server == null || s.bodies.size() <= 1) {
         return;
      }
      List<SquadRow> rows = new ArrayList<>();
      for (Map.Entry<UUID, State.Body> e : s.bodies.entrySet()) {
         ServerPlayer online = server.getPlayerList().getPlayer(e.getKey());
         State.Body body = e.getValue();
         rows.add(
            new SquadRow(
               online == null ? "an explorer" : online.getName().getString(),
               body.carried, body.chests, body.downs, body.revives
            )
         );
      }
      List<String> lines = squadLines(rows, s.lootValue);
      for (UUID member : s.bodies.keySet()) {
         ServerPlayer online = server.getPlayerList().getPlayer(member);
         if (online == null) {
            continue;
         }
         for (String line : lines) {
            Chat.raw(online, line);
         }
      }
   }

   private static void finish(ServerPlayer sp, State s, boolean success) {
      if (sp == null) {
         return;
      }
      // This explorer's run is over, and the bookkeeping has to agree with that before anything
      // below asks whether the site is empty. Removing here as well as at the callers is deliberate:
      // it is what makes the answer the same however the run ended.
      active.remove(sp.getUUID());
      // The site's own business - its bodies, its glow marks, and the ground it is holding - only
      // happens when the last explorer is out of it. A party's site is one site: the first of them
      // to reach a pad must not sweep the mobs off the other three, nor hand the ground back to the
      // ledger while they are still walking it.
      if (!siteOccupied(s)) {
         // The bodies go first, while the run's ledger of them is still intact - the sweep reads
         // that ledger, and the sweep after it is what empties the scoreboard of their names.
         sweepZone(s);
         // ...and then the glow marker goes with them: the scoreboard keeps a name for every body
         // that ever joined a team, and a maze run is hundreds of bodies.
         releasePackMarks(s);
         // ...and every site it was standing in is handed back, so the next explorer of this dungeon
         // gets an entrance of their own rather than this one's ruins.
         releaseSites(s.holder);
      }
      endBoard(sp);
      withdrawExitCompass(sp);
      LootBackpack.withdraw(sp);
      if (success) {
         // Loot is already multiplied at pickup - paying it out again would double (and the old
         // code squared) the money. Pay exactly what was secured - and on a party run, pay the
         // party's pot instead, so that four explorers who carried one bag out are paid the same
         // amount however early or late each of them reached a pad. See PartyManager#settle.
         long carried = unbanked(s.lootValue, s.settledLoot);
         s.settledLoot = s.lootValue;
         long own = carried > 0L ? carried + scorecard(sp, s) : 0L;
         long payout = PartyManager.settle(sp, own);
         if (payout > 0L) {
            if (carried <= 0L) {
               // Nothing of their own, but the party's bag paid out. The card is what says so.
               scorecard(sp, s);
            }
            codexEscape(s, payout);
            EconomyManager.addCash(sp.getUUID(), payout);
            // ...and the part of a run that is kept. EXP is a function of the payout and nothing
            // else, so a run that pays more is the only way to earn more of it and a single run can
            // never buy the shop out. See ExpeditionProgression#expFor.
            long exp = ExpeditionProgression.award(sp.getUUID(), payout);
            VfxManager.celebrate(sp);
            SoundUtil.play(sp, ModSounds.JOB_COMPLETE);
            Chat.raw(sp, "§a§lEXPEDITION COMPLETE!§r §7Loot secured: §a" + Chat.moneyStr(payout) + "§7.");
            String partyNote = PartyManager.shareNote(sp);
            if (partyNote != null) {
               Chat.raw(sp, partyNote);
            }
            Chat.raw(
               sp,
               "§6§l+" + exp + " EXP§r §7- spend it at §f/expedition shop§7. §8("
                  + ExpeditionProgression.available(sp.getUUID()) + " banked)"
            );
            sp.level().getServer().getPlayerList()
               .broadcastSystemMessage(
                  Component.literal(Chat.colorize("§e[!] §f" + sp.getName().getString() + "§e returned from a " + s.type.color + s.type.name + "§e expedition with §a"
                     + Chat.moneyStr(payout) + "§e!")),
                  false
               );
         } else {
            Chat.raw(sp, "§7You escaped the " + s.type.name + " without securing any loot. Better luck next time!");
            scorecard(sp, s);
            codexEscape(s, 0L);
         }
      } else {
         codexFalls++;
         Chat.raw(sp, "§c§lEXPEDITION FAILED§r §7- the " + s.type.name + " collapsed. All secured loot was lost.");
      }
      // The squad's own card, and the last word on a party run: printed by the explorer who closes the
      // site, for everybody who was in it, because a party's haul is one haul and the only interesting
      // thing left to say about it is who actually carried it. See squadCard.
      if (!siteOccupied(s)) {
         squadCard(s);
      }
      ServerLevel origin = sp.level().getServer().getLevel(s.body(sp.getUUID()).originDim);
      if (origin != null) {
         teleportPlayer(sp, origin, s.body(sp.getUUID()).originX, s.body(sp.getUUID()).originY, s.body(sp.getUUID()).originZ);
      }
      sp.level().sendParticles(ParticleTypes.PORTAL, sp.getX(), sp.getY() + 1.0, sp.getZ(), 30, 0.5, 0.5, 0.5, 0.05);
      // The purse closes when the last member of the party is out of the site. While somebody is
      // still underground the pot stands, because they are still owed it.
      PartyManager.runEndedIfIdle(sp);
   }

   /**
    * Takes the explorer's sidebar down and hands the sidebar back to whatever owned it before.
    *
    * <p>Two boards cannot share one sidebar - the last display packet wins - so the site's board
    * owns it for the length of a run and gives it back on the way out. The personal board is
    * re-displayed rather than rebuilt: its objective is still on the world's scoreboard and its
    * client has already been told about it, and re-adding an objective the client already knows is
    * the one thing that is not safe to repeat.
    */
   private static void endBoard(ServerPlayer sp) {
      try {
         ExpeditionBoard.hide(sp);
         com.fortuneandfavors.economy.ScoreboardManager.reclaim(sp);
      } catch (Throwable ignored) {
         // A sidebar is decoration; a run that ends must end whether or not it drew.
      }
   }

   /**
    * Prints the end-of-run card and returns the medal bonus. Every stat here is
    * something the player actually did, so the run is readable at a glance.
    */
   private static long scorecard(ServerPlayer sp, State s) {
      double expected = 6000.0 * s.type.multiplier;
      double ratio = s.lootValue / expected;
      String medal;
      String colour;
      double bonusMul;
      if (ratio >= 2.0) {
         medal = "S";
         colour = "§b";
         bonusMul = 0.35;
      } else if (ratio >= 1.2) {
         medal = "A";
         colour = "§a";
         bonusMul = 0.18;
      } else if (ratio >= 0.6) {
         medal = "B";
         colour = "§e";
         bonusMul = 0.08;
      } else {
         medal = "C";
         colour = "§7";
         bonusMul = 0.0;
      }
      long bonus = (long)(s.lootValue * bonusMul);
      long minutes = Math.max(0L, (ServerClock.clock(s.zoneLevel) - s.startTick) / 1200L);
      int explored = (int)Math.sqrt(Math.max(0, s.maxDist2));
      Chat.raw(sp, "§8§m                                        ");
      Chat.raw(sp, s.type.color + "§l" + s.type.name + "§r §8· §7Run Summary");
      Chat.raw(sp, "§7Loot: §a" + Chat.moneyStr(s.lootValue) + "  §8(anomaly: " + s.anomaly.colour + s.anomaly.name + "§8)");
      Chat.raw(sp, "§7Chambers cleared: §f" + s.roomsCleared + "  §7Deepest: §f" + s.deepest + "  §7Chests: §f" + s.chestsOpened);
      Chat.raw(sp, "§7Mobs slain: §f" + s.mobsSlain + "  §7Ores mined: §f" + s.oresMined);
      Chat.raw(sp, "§7Guardians felled: §f" + s.guardiansFelled + "  §7Descents used: §f" + s.descents);
      Chat.raw(sp, "§7Explored: §f" + explored + "m  §7Time in site: §f" + minutes + "m");
      Chat.raw(sp, "§7Rating: " + colour + "§l" + medal + (bonus > 0L ? " §r§7- bonus §a" + Chat.moneyStr(bonus) : " §r§8- no bonus"));
      Chat.raw(sp, "§8§m                                        ");
      return bonus;
   }

   /**
    * The chambers of a run in the order the site takes them down: outermost first.
    *
    * <p>Sorted by distance from the threshold rather than by depth, because the two are not the same
    * thing in a maze that grows in every direction, and it is the distance the explorer feels. The
    * threshold comes down last, which is what a player sprinting home needs: the room their pad is
    * in is the last room to go.
    */
   private static List<Room> collapseOrder(State s) {
      int cx = baseX(s, 0) + ROOM_PITCH / 2;
      int cz = baseZ(s, 0) + ROOM_PITCH / 2;
      List<Room> order = new ArrayList<>();
      for (Room room : s.rooms.values()) {
         // The arena's rim cells are chambers only so that the maze can hang doors off them; the
         // room itself is the middle. It comes down once, as the arena. See buildWideArena.
         if (isArenaRing(s, room)) {
            continue;
         }
         order.add(room);
      }
      // Farthest first: the comparator is written the wrong way round on purpose, so the outermost
      // chamber of the maze is the first one the site takes and the threshold is the last.
      order.sort((a, b) -> Integer.compare(roomDist2(s, b, cx, cz), roomDist2(s, a, cx, cz)));
      return order;
   }

   private static int roomDist2(State s, Room room, int cx, int cz) {
      int dx = baseX(s, room.rx) + ROOM_PITCH / 2 - cx;
      int dz = baseZ(s, room.rz) + ROOM_PITCH / 2 - cz;
      return dx * dx + dz * dz;
   }

   /**
    * One chamber coming down.
    *
    * <p>The floor is buried under rubble, the pack inside is crushed, and the room is marked
    * cleared because a chamber that no longer exists cannot hold a door shut. Everybody in the site
    * hears it if they are close enough to see it, and a body standing in the room it happens to is
    * handled by the caller - the collapse does not care who is in the way.
    */
   private static void beginShed(State s, Room room, ServerPlayer sp) {
      if (!chamberStillHasAFall(room.shedding, room.breached)) {
         return;
      }
      ServerLevel level = s.zoneLevel;
      int bx = baseX(s, room.rx);
      int bz = baseZ(s, room.rz);
      int fy = floorY(s);
      int top = shedTop(s, room, fy);
      int lo = debrisLo(s, room);
      int hi = debrisHi(s, room);
      room.shedding = true;
      room.shedRubble = 0;
      room.breachAt = ServerClock.clock(level) + COLLAPSE_SHED_TICKS;
      room.shedUntil = ServerClock.clock(level) + COLLAPSE_MAX_SHED_TICKS;
      s.shedding.add(room);
      // The fall's clock: one more chamber of the site is on its way down, which is what every part
      // of the spectacle below - and the ring closing on the explorer - is measured against.
      s.collapseFelled++;
      // The chamber groans: grit off the whole roof at once, and a beat of sound that carries into
      // the halls around it. Nothing announces that the room is falling - the room is falling.
      for (int i = 0; i < 40; i++) {
         double x = bx + lo + RANDOM.nextDouble() * (hi - lo);
         double z = bz + lo + RANDOM.nextDouble() * (hi - lo);
         level.sendParticles(new net.minecraft.core.particles.BlockParticleOption(ParticleTypes.FALLING_DUST, Blocks.GRAVEL.defaultBlockState()), x, top - 1.2, z, 3, 0.35, 0.5, 0.35, 0.06);
         level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, x, fy + 1.6, z, 1, 0.5, 0.5, 0.5, 0.01);
      }
      double p = collapsePressure(s.collapseFelled, s.collapseTotal);
      double cx = bx + ROOM_PITCH / 2.0 + 0.5;
      double cz = bz + ROOM_PITCH / 2.0 + 0.5;
      // The chamber goes under a shaft of sparks and a ring of smoke drawn at its own floor, taller
      // and wider the further into the fall the site is: a room coming down is visible from the far
      // side of the maze, which is how the explorer learns which way the collapse is closing from.
      BossVfx.column(
         level, new net.minecraft.world.phys.Vec3(cx, fy + 1.0, cz), 3.0 + 7.0 * p, ParticleTypes.END_ROD, 1
      );
      BossVfx.ring(
         level, new net.minecraft.world.phys.Vec3(cx, fy + 0.3, cz), 6.0, 24, ParticleTypes.CAMPFIRE_COSY_SMOKE, 0.15
      );
      level.playSound(null, cx, fy + 3, cz, SoundEvents.GRAVEL_BREAK, SoundSource.BLOCKS, (float)(1.6 + p), collapseGroanPitch(p));
      level.playSound(null, cx, fy + 1, cz, SoundEvents.STONE_BREAK, SoundSource.BLOCKS, (float)(1.3 + p), collapseGroanPitch(p) * 1.2F);
      if (p > 0.6) {
         // The last third of the site falling is a different noise: a boom under the groan, which is
         // the cue that the maze is nearly down rather than merely noisy.
         level.playSound(null, cx, fy + 2, cz, SoundEvents.GENERIC_EXPLODE, SoundSource.BLOCKS, (float)(0.7 * p), 1.5F);
      }
      // Only the room the explorer is standing in says so out loud; every other doomed chamber is
      // something they hear, and then something they walk into.
      if (room == s.body(sp.getUUID()).lastRoom) {
         actionBar(sp, "§e§l" + room.chamber.name.toUpperCase() + " IS SHEDDING §7· get out of it");
      }
   }

   /**
    * One tick of a chamber's fall, for every chamber currently shedding.
    *
    * <p>Rubble lands a few blocks at a time - gravel and cobble, scattered across the chamber's
    * floor and left lying there one block deep, so a room mid-collapse is still a room - while the
    * roof sheds grit and dust over it and the odd lump of itself comes down on whoever is under it.
    * The rubble is the clock and the counter both: once {@link #COLLAPSE_RUBBLE_TARGET} blocks of it
    * are down and the chamber has had its moment, the roof gives way - and a chamber that cannot
    * reach the count because there is nothing left to bury gives way on its own window instead, so
    * that no single room can hold the rest of the site up. See {@link #shedIsDone}.
    *
    * <p>The doorway lanes are deliberately left clear while it sheds. A collapse that chokes the
    * archways with rubble on the way down is a collapse that ends the run of anybody standing in it,
    * and the fall is meant to be something an explorer can still walk out of - the chokes go in with
    * the roof, after the room is already lost.
    *
    * @return true when the run's explorer was standing in a chamber that gave way
    */
   private static boolean shedTick(State s, ServerPlayer sp) {
      if (s.shedding.isEmpty()) {
         return false;
      }
      ServerLevel level = s.zoneLevel;
      long tick = ServerClock.clock(level);
      for (int i = s.shedding.size() - 1; i >= 0; i--) {
         Room room = s.shedding.get(i);
         int bx = baseX(s, room.rx);
         int bz = baseZ(s, room.rz);
         int fy = floorY(s);
         int top = shedTop(s, room, fy);
         int lo = debrisLo(s, room);
         int hi = debrisHi(s, room);
         int span = Math.max(1, hi - lo);
         int target = shedTarget(s, room);
         int rate = shedRate(s, room);
         // The roof, actually falling: part of the chamber's rubble is real rock out of its own
         // ceiling, and the share of it that falls rather than appears grows as the site gets closer
         // to done. See shedStone and collapseFallingPerTick.
         int falling = collapseFallingPerTick(rate, collapsePressure(s.collapseFelled, s.collapseTotal));
         for (int n = 0; n < falling && room.shedRubble < target; n++) {
            if (!shedStone(s, room, bx, bz, fy, lo, hi)) {
               break;
            }
            room.shedRubble++;
         }
         for (int n = falling; n < rate && room.shedRubble < target; n++) {
            int lx = lo + RANDOM.nextInt(span);
            int lz = lo + RANDOM.nextInt(span);
            if (inDoorLane(room, lx, lz)) {
               continue;
            }
            BlockPos cell = new BlockPos(bx + lx, fy + 1, bz + lz);
            if (!level.getBlockState(cell).isAir()) {
               continue;
            }
            setIfChanged(level, cell, RANDOM.nextInt(4) == 0 ? Blocks.COBBLESTONE.defaultBlockState() : Blocks.GRAVEL.defaultBlockState());
            room.shedRubble++;
         }
         for (int n = 0; n < 7; n++) {
            double x = bx + lo + RANDOM.nextDouble() * span;
            double z = bz + lo + RANDOM.nextDouble() * span;
            level.sendParticles(new net.minecraft.core.particles.BlockParticleOption(ParticleTypes.FALLING_DUST, Blocks.GRAVEL.defaultBlockState()), x, top - 1.3, z, 1, 0.25, 0.4, 0.25, 0.05);
            if (RANDOM.nextInt(3) == 0) {
               level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, x, fy + 1.5, z, 1, 0.4, 0.3, 0.4, 0.01);
            }
            if (RANDOM.nextInt(6) == 0) {
               level.sendParticles(
                  new net.minecraft.core.particles.BlockParticleOption(ParticleTypes.BLOCK, Blocks.GRAVEL.defaultBlockState()),
                  x, fy + 1.2, z, 2, 0.3, 0.2, 0.3, 0.02
               );
            }
         }
         // The sound of it landing, which never stops once it has started: a chamber coming down
         // should be audible from the doorway for every second of it rather than only at the end,
         // and the rock actually falling is what the noise is.
         if (RANDOM.nextInt(3) == 0) {
            int lx = lo + RANDOM.nextInt(span);
            int lz = lo + RANDOM.nextInt(span);
            level.playSound(
               null, bx + lx + 0.5, fy + 1.4, bz + lz + 0.5,
               SoundEvents.GRAVEL_BREAK, SoundSource.BLOCKS, 0.9F, 0.6F + RANDOM.nextFloat() * 0.4F
            );
         }
         // The odd lump of the roof comes down on whoever is still under it - a knock, not the fall.
         if (tick % 20L == 0L && room == s.body(sp.getUUID()).lastRoom && sp.getY() > fy && sp.getY() < top) {
            sp.hurtServer(level, level.damageSources().generic(), 2.0F);
            level.playSound(null, sp.getX(), sp.getY() + 1.0, sp.getZ(), SoundEvents.GRAVEL_BREAK, SoundSource.BLOCKS, 1.1F, 0.6F);
         }
         if (shedIsDone(room.shedRubble, target, tick, room.breachAt, room.shedUntil)) {
            boolean ended = breachRoom(s, room, sp);
            s.shedding.remove(i);
            if (ended) {
               return true;
            }
         }
      }
      return false;
   }

   /**
    * One block of a dying chamber's roof letting go.
    *
    * <p>A real gravel block, spawned just under the roof of the chamber and left to the game's own
    * physics: it falls, it lands on whatever is below it, it stays there as part of the rubble, and
    * anything it comes down on takes a falling block's damage. Three things about it are deliberate.
    *
    * <p>The roof above it is turned to loose gravel rather than torn open. There is no rock above a
    * site - the expedition dimension is flat and empty and every floor, wall and ceiling in the maze
    * was written by this file - so a hole in a roof is a hole into the void, and a chamber that opens
    * upward during a collapse is a chamber the explorer can leave through the ceiling. The roof
    * therefore crumbles in place: the panels over a shedding chamber turn to gravel a few at a time,
    * lamp and beam and all, which is the crack spreading - and it is also why a doomed chamber gets
    * gradually darker, because its own light is in that ceiling.
    *
    * <p>It only drops over floor with nothing standing on it, and never in a doorway lane, on the same
    * two rules the written rubble follows: the chamber fills evenly, and the way out stays a way out
    * until the roof actually gives way.
    *
    * @return true when a block actually left the roof
    */
   private static boolean shedStone(State s, Room room, int bx, int bz, int fy, int lo, int hi) {
      ServerLevel level = s.zoneLevel;
      int top = shedTop(s, room, fy);
      int span = Math.max(1, hi - lo);
      BlockState gravel = Blocks.GRAVEL.defaultBlockState();
      BlockState falling = RANDOM.nextInt(4) == 0 ? Blocks.COBBLESTONE.defaultBlockState() : gravel;
      for (int tries = 0; tries < 6; tries++) {
         int lx = lo + RANDOM.nextInt(span);
         int lz = lo + RANDOM.nextInt(span);
         if (inDoorLane(room, lx, lz)) {
            continue;
         }
         if (!level.getBlockState(new BlockPos(bx + lx, fy + 1, bz + lz)).isAir()) {
            continue;
         }
         BlockPos under = new BlockPos(bx + lx, top - 1, bz + lz);
         if (!level.getBlockState(under).isAir()) {
            // Something is already hanging in that cell above the fight - a chandelier, a dropped
            // block, the room's own high dress. That column has had its turn.
            continue;
         }
         BlockPos roof = new BlockPos(bx + lx, top, bz + lz);
         if (!level.getBlockState(roof).isAir()) {
            setIfChanged(level, roof, gravel);
            level.sendParticles(
               new net.minecraft.core.particles.BlockParticleOption(ParticleTypes.FALLING_DUST, gravel),
               bx + lx + 0.5, top - 0.6, bz + lz + 0.5, 4, 0.3, 0.2, 0.3, 0.03
            );
         }
         net.minecraft.world.entity.item.FallingBlockEntity lump =
            net.minecraft.world.entity.item.FallingBlockEntity.fall(level, under, falling);
         lump.disableDrop();
         // A knock rather than a full falling block: ten blocks of gravel arriving on a head is five
         // hearts in vanilla, and the collapse is meant to be a room you leave rather than a room
         // that kills you the moment it starts. The breach below lands heavier, because that one is
         // the roof actually giving way.
         lump.setHurtsEntities(0.3F, 3);
         return true;
      }
      return false;
   }

   /**
    * The chamber goes.
    *
    * <p>Everything the old collapse did, plus the two things that make it read as a collapse rather
    * than as a room that had quietly been emptied. The pack under the roof dies with it and the
    * floor is buried, as before - and then the blast that does it is a blast, with sound and a
    * shockwave that throws whatever is standing in the hall off its feet; and every archway that led
    * into the chamber is choked with what came down in front of it, so the hall beyond ends in a
    * wall of gravel. A dead chamber that still had an open door in it was a shortcut through a
    * collapse, and a chamber whose doorway was spotless never looked like it had fallen at all.
    *
    * @return true when the run's explorer was standing in it and has been thrown out of the run
    */
   private static boolean breachRoom(State s, Room room, ServerPlayer sp) {
      ServerLevel level = s.zoneLevel;
      int bx = baseX(s, room.rx);
      int bz = baseZ(s, room.rz);
      int fy = floorY(s);
      double cx = bx + ROOM_PITCH / 2.0 + 0.5;
      double cz = bz + ROOM_PITCH / 2.0 + 0.5;
      room.breached = true;
      room.shedding = false;
      unsealRoom(s, room);
      room.cleared = true;
      // The roof arrives. A real blast, with the rock deliberately left alone: the rubble this
      // chamber leaves is written by hand below, so an explosion here can never open the maze up or
      // scatter the rooms around it. What the blast is for is everything standing in the room.
      level.explode(null, cx, fy + 2.0, cz, 7.0F, Level.ExplosionInteraction.NONE);
      // The pack is under the roof as well. A chamber that fell with its monsters still standing in
      // it would otherwise hold the run open on a room nobody can reach.
      for (UUID id : room.monsters) {
         net.minecraft.world.entity.Entity m = level.getEntity(id);
         if (!packBodyGone(m)) {
            m.hurtServer(level, level.damageSources().generic(), 1_000.0F);
         }
      }
      room.monsters.clear();
      BlockState rubble = Blocks.GRAVEL.defaultBlockState();
      BlockState stone = Blocks.COBBLESTONE.defaultBlockState();
      int bLo = buryLo(s, room);
      int bHi = buryHi(s, room);
      for (int lx = bLo; lx <= bHi; lx++) {
         for (int lz = bLo; lz <= bHi; lz++) {
            if (inDoorLane(room, lx, lz)) {
               continue;
            }
            BlockPos cell = new BlockPos(bx + lx, fy + 1, bz + lz);
            if (!level.getBlockState(cell).isAir()) {
               continue;
            }
            setIfChanged(level, cell, RANDOM.nextInt(3) == 0 ? stone : rubble);
         }
      }
      // ...and the roof itself came down in the middle of it: a mound three blocks at its crown,
      // which is what a fallen chamber looks like from the doorway.
      int mid = ROOM_PITCH / 2;
      int reach = inArena(s, room) ? COLLAPSE_MOUND_REACH * 2 : COLLAPSE_MOUND_REACH;
      for (int dx = -reach; dx <= reach; dx++) {
         for (int dz = -reach; dz <= reach; dz++) {
            int lx = mid + dx;
            int lz = mid + dz;
            if (inDoorLane(room, lx, lz)) {
               continue;
            }
            int crown = 3 - Math.max(Math.abs(dx), Math.abs(dz)) / 2;
            for (int y = 1; y <= Math.max(1, crown); y++) {
               BlockPos cell = new BlockPos(bx + lx, fy + y, bz + lz);
               if (!level.getBlockState(cell).isAir()) {
                  continue;
               }
               setIfChanged(level, cell, RANDOM.nextInt(4) == 0 ? stone : rubble);
            }
         }
      }
      // ...and it comes down as rock as well as rubble. A burst of the chamber's own ceiling, over the
      // middle of it, all at once: that is what a roof giving way is, and it is the one thing the
      // moment needs that a written floor cannot give it. Capped (see COLLAPSE_BREACH_FALLING) because
      // a hall this size dropping its whole roof as entities in one tick is a server problem rather
      // than a spectacle - the burial above is what actually fills the floor.
      for (int i = 0; i < COLLAPSE_BREACH_FALLING; i++) {
         int lx = bLo + RANDOM.nextInt(Math.max(1, bHi - bLo + 1));
         int lz = bLo + RANDOM.nextInt(Math.max(1, bHi - bLo + 1));
         if (inDoorLane(room, lx, lz)) {
            continue;
         }
         BlockPos roof = new BlockPos(bx + lx, shedTop(s, room, fy), bz + lz);
         if (level.getBlockState(roof).isAir()) {
            continue;
         }
         setIfChanged(level, roof, rubble);
         net.minecraft.world.entity.item.FallingBlockEntity lump =
            net.minecraft.world.entity.item.FallingBlockEntity.fall(
               level, new BlockPos(bx + lx, shedTop(s, room, fy) - 1, bz + lz), RANDOM.nextInt(3) == 0 ? stone : rubble
            );
         lump.disableDrop();
         // The roof giving way lands hard: this is the one part of the fall that is meant to be able
         // to finish somebody off, rather than a knock off a falling block.
         lump.setHurtsEntities(1.0F, 8);
      }
      // Every way in is choked with what came down in front of it - and for the arena that is the
      // rim cells' doors, because the arena's own middle holds none. A room this size that came down
      // with its doors still open would be a shortcut through the middle of a collapse.
      Arena arena = arenaOf(s, room);
      if (arena != null) {
         for (Room ring : arena.rings.values()) {
            for (int d = 0; d < 4; d++) {
               if ((ring.doors >> d & 1) != 0) {
                  chokeArchway(s, ring, d);
               }
            }
         }
      } else {
         for (int d = 0; d < 4; d++) {
            if ((room.doors >> d & 1) != 0) {
               chokeArchway(s, room, d);
            }
         }
      }
      if (horizDist2(new BlockPos(bx + mid, fy, bz + mid), sp.blockPosition()) <= 48 * 48) {
         double p = collapsePressure(s.collapseFelled, s.collapseTotal);
         level.sendParticles(ParticleTypes.LARGE_SMOKE, cx, fy + 2.0, cz, 40, 5.0, 2.0, 5.0, 0.05);
         level.playSound(null, cx, fy + 1, cz, SoundEvents.STONE_BREAK, SoundSource.BLOCKS, 1.4F, 0.6F);
         // A chamber leaving the world is the loudest thing in the fall, and it gets louder: a shock
         // ring of sparks across the floor of the chamber and a rising column of dust out of it, both
         // scaled by how far along the site is. The blast under the noise deepens as it goes, so the
         // difference between the third chamber and the twelfth is something the ear can hear from
         // the corridor - the light and the dust say where, the noise says how close to done.
         BossVfx.ring(
            level, new net.minecraft.world.phys.Vec3(cx, fy + 0.5, cz), 8.0 + 10.0 * p, 36, ParticleTypes.END_ROD, 0.0
         );
         BossVfx.column(
            level, new net.minecraft.world.phys.Vec3(cx, fy + 1.0, cz), 6.0 + 9.0 * p, ParticleTypes.CAMPFIRE_COSY_SMOKE, 2
         );
         level.playSound(null, cx, fy + 1, cz, SoundEvents.GENERIC_EXPLODE, SoundSource.BLOCKS, (float)(1.0 + p), (float)(1.2 - 0.5 * p));
      }
      // A body still in the chamber does not lose the run on the tick the roof moves: the fall tells
      // them, the site keeps coming down, and the eight seconds that follow are theirs to spend on
      // the walk back to a pad. See COLLAPSE_GRACE_TICKS.
      if (s.body(sp.getUUID()).lastRoom == room) {
         beginCollapseGrace(s, sp, "the " + room.chamber.name + " came down on you");
      }
      return false;
   }

   /**
    * The last thing the fall does to an explorer it has caught: it warns them.
    *
    * <p>Called from the two places the collapse used to end a run on the spot - the roof of the
    * chamber the body is standing in giving way, and the last chamber of the site coming down with
    * the explorer still inside it. Both now open the same window instead, once, and the ejection
    * moves to the expiry of that window at the top of the collapse tick. The site is not paused for
    * it: chambers keep shedding and rock keeps landing for every second of the grace, which is what
    * makes the window a run rather than a rest.
    */
   private static void beginCollapseGrace(State s, ServerPlayer sp, String why) {
      if (s.collapseGraceUntil != 0L) {
         return;
      }
      s.collapseGraceUntil = ServerClock.clock(s.zoneLevel) + (long)COLLAPSE_GRACE_TICKS;
      long seconds = COLLAPSE_GRACE_TICKS / 20L;
      Chat.raw(sp, "§4§lTHE FALL HAS YOU §r§7- " + why + ".");
      Chat.raw(
         sp,
         "§7You have §f" + seconds + " seconds§7. A pad still works, and it is the only thing that does - "
            + "§frun§7, and whatever you secure on the way counts."
      );
      actionBar(sp, "§4§l" + seconds + "s§r §7to reach a pad - the site is still coming down");
      SoundUtil.play(sp, ModSounds.MYSTERY);
   }

   /** Local bounds a doomed chamber sheds its rubble inside - the arena's whole floor, or one cell. */
   private static int debrisLo(State s, Room room) {
      return inArena(s, room) ? WALL - ROOM_PITCH : interior();
   }

   private static int debrisHi(State s, Room room) {
      return inArena(s, room) ? ARENA_SPAN - WALL - ROOM_PITCH : interiorEnd();
   }

   /** The height a chamber's roof is at, for the grit that comes off it on the way down. */
   private static int shedTop(State s, Room room, int fy) {
      return fy + (inArena(s, room) ? ARENA_HEIGHT : ROOM_HEIGHT);
   }

   /**
    * How much rubble a chamber has to shed before its roof gives way.
    *
    * <p>The count is what makes a collapse readable, so it is a property of the floor rather than a
    * constant: a chamber whose floor is a sixth of an acre needs nine times the rumble of a cell
    * before the roof is on its way, and it gets nine times the rate to shed it in the same moment of
    * site time.
    */
   private static int shedTarget(State s, Room room) {
      return inArena(s, room)
         ? COLLAPSE_RUBBLE_TARGET * ARENA_CELLS * ARENA_CELLS
         : COLLAPSE_RUBBLE_TARGET;
   }

   private static int shedRate(State s, Room room) {
      return inArena(s, room)
         ? COLLAPSE_RUBBLE_RATE * ARENA_CELLS * 3
         : COLLAPSE_RUBBLE_RATE;
   }

   /** Local bounds a fallen chamber's floor is buried inside. */
   private static int buryLo(State s, Room room) {
      return inArena(s, room) ? ARENA_PIT_LO - ROOM_PITCH : WALL;
   }

   private static int buryHi(State s, Room room) {
      return inArena(s, room) ? ARENA_PIT_HI - ROOM_PITCH : ROOM_PITCH - WALL - 1;
   }

   /** How far the crown of a fallen roof spreads from the middle of a chamber. */
   private static final int COLLAPSE_MOUND_REACH = 4;
   /** How far outside the wall band a choke is packed, so the rubble spills into both halls. */
   private static final int CHOKE_SPILL = 2;
   /** How deep an archway is packed when the chamber behind it comes down. */
   private static final int CHOKE_DEPTH = WALL * 2 + CHOKE_SPILL * 2;

   /**
    * Packs one of a fallen chamber's archways with its rubble.
    *
    * <p>Eight blocks deep: the two blocks of the hall it came from, both halves of the wall band,
    * and four blocks into the fallen room. Done deliberately all the way through rather than only at
    * the mouth, because a wall of gravel with a gap behind it reads as a door that is blocked, and
    * the point is that this chamber is not a door any more.
    */
   private static void chokeArchway(State s, Room room, int dir) {
      ServerLevel level = s.zoneLevel;
      int bx = baseX(s, room.rx);
      int bz = baseZ(s, room.rz);
      int fy = floorY(s);
      BlockState gravel = Blocks.GRAVEL.defaultBlockState();
      BlockState cobble = Blocks.COBBLESTONE.defaultBlockState();
      for (int w = 0; w < DOOR_W; w++) {
         int la = DOOR_LO + w;
         for (int i = 0; i < CHOKE_DEPTH; i++) {
            int lx;
            int lz;
            switch (dir) {
               case NORTH -> {
                  lx = la;
                  lz = -CHOKE_SPILL + i;
               }
               case SOUTH -> {
                  lx = la;
                  lz = ROOM_PITCH - 1 + CHOKE_SPILL - i;
               }
               case WEST -> {
                  lx = -CHOKE_SPILL + i;
                  lz = la;
               }
               default -> {
                  lx = ROOM_PITCH - 1 + CHOKE_SPILL - i;
                  lz = la;
               }
            }
            for (int y = fy + 1; y <= fy + DOOR_H; y++) {
               setIfChanged(level, new BlockPos(bx + lx, y, bz + lz), RANDOM.nextInt(3) == 0 ? cobble : gravel);
            }
         }
      }
   }

   /**
    * The end of a run that is not an escape: the site takes the explorer, and the loot with them.
    *
    * <p>One body, so every way of being thrown out of a run reads the same to the player - the fatal
    * blow absorbed on the way down, and the roof of a collapsing chamber. The pack, the compass and
    * the run itself are all handed back to the site, the loot is written off, and the explorer is put
    * back where they came in with their items and their health intact.
    */
   private static void eject(ServerPlayer sp, State s, String headline, String why, boolean cooldown) {
      active.remove(sp.getUUID());
      // ...and the site itself is only handed back when nobody else is standing in it: an ejection
      // is one explorer's, not the party's.
      if (!siteOccupied(s)) {
         releaseSites(s.holder);
         releasePackMarks(s);
      }
      endBoard(sp);
      codexFalls++;
      long lost = s.lootValue;
      if (cooldown) {
         fatalCooldown.put(sp.getUUID(), ServerClock.clock(sp.level()) + FATAL_COOLDOWN_TICKS);
      }
      withdrawExitCompass(sp);
      LootBackpack.withdraw(sp);
      Chat.raw(sp, headline + "\u00a7r \u00a77- " + why + ".");
      Chat.raw(sp, "\u00a77You lost \u00a7c" + Chat.moneyStr(lost) + "\u00a77 of secured loot, but your items are safe.");
      sp.setHealth(sp.getMaxHealth());
      ServerLevel origin = sp.level().getServer().getLevel(s.body(sp.getUUID()).originDim);
      if (origin != null) {
         teleportPlayer(sp, origin, s.body(sp.getUUID()).originX, s.body(sp.getUUID()).originY, s.body(sp.getUUID()).originZ);
      }
      sp.level().sendParticles(ParticleTypes.PORTAL, sp.getX(), sp.getY() + 1.0, sp.getZ(), 30, 0.5, 0.5, 0.5, 0.05);
      // An ejection ends a run as surely as an extraction does, so a party whose last member was
      // thrown out of the site must have its purse closed here too - the pot is only for a run that
      // is still going. See PartyManager#runEndedIfIdle.
      PartyManager.runEndedIfIdle(sp);
   }

   /**
    * A run does not survive its explorer leaving the server.
    *
    * <p>Left alone, a run whose player logged out carried on: the site stayed held against their
    * name, the clock kept running, and the body came back to a maze that had been falling for as
    * long as they were gone - which is a free escape on a good day and a stolen run on a bad one.
    * So logging out ends it, and it ends it the same way being caught ends it: no loot banked, the
    * expedition's own items taken back (the compass and the pack, which are the site's and not the
    * explorer's), and their ordinary inventory untouched. That is the honest reading of a run that
    * was abandoned rather than finished - the walk back to the pad is the run, and there is no
    * version of leaving that performs it for you.
    *
    * @return true when there was a run to end
    */
   public static boolean onPlayerLogout(ServerPlayer sp) {
      if (sp == null) {
         return false;
      }
      UUID uuid = sp.getUUID();
      State s = active.get(uuid);
      if (s == null) {
         return false;
      }
      Chat.raw(sp, "§c§lEXPEDITION ABANDONED §r§7- you left the server mid-run. No loot was banked.");
      try {
         eject(sp, s, "§c§lTHE SITE KEEPS WHAT IT TOOK", "You left the server mid-expedition", false);
      } catch (Throwable t) {
         // Leaving must never be held up by bookkeeping, and a half-finished ejection would be worse
         // than none: whatever the body did or did not manage, the run is gone.
         active.remove(uuid);
         releaseSites(s.holder);
         releasePackMarks(s);
         LootBackpack.withdraw(sp);
         withdrawExitCompass(sp);
      }
      return true;
   }

   /**
    * What a chamber is in the middle of doing, once every twenty ticks.
    *
    * <p>The conditions are meant to be readable from the room rather than from a status line, so
    * most of this is atmosphere - and the two that can hurt are the two that do: rubble out of an
    * unstable roof, and the embers of a room that is already alight.
    */
   private static void conditionTick(State s, Room room, ServerPlayer sp, long tick) {
      if (room.condition == null) {
         return;
      }
      ServerLevel level = s.zoneLevel;
      switch (room.condition) {
         case UNSTABLE -> {
            BlockPos at = roomSpot(s, room);
            if (at == null) {
               return;
            }
            int y = floorY(s) + ROOM_HEIGHT - 2;
            level.sendParticles(ParticleTypes.LARGE_SMOKE, at.getX() + 0.5, y, at.getZ() + 0.5, 12, 0.6, 0.4, 0.6, 0.02);
            if (tick % 40L == 0L) {
               // A body-sized lump of the roof, dropped where it stands. It falls, it hits, and it
               // does not care whose head it lands on. Its drop is switched off in the same breath:
               // an unstable roof is a hazard, not a seam, and the block landing in the floor as an
               // item was the site handing out its own ceiling forty blocks at a time. The rock is
               // still here when the collapse starts - see ExpeditionManager.rockPaysInNothing.
               net.minecraft.world.entity.item.FallingBlockEntity lump =
                  net.minecraft.world.entity.item.FallingBlockEntity.fall(
                     level, new BlockPos(at.getX(), y, at.getZ()), Blocks.GRAVEL.defaultBlockState()
                  );
               lump.disableDrop();
               level.playSound(
                  null, at.getX(), y, at.getZ(), net.minecraft.sounds.SoundEvents.GRAVEL_BREAK,
                  net.minecraft.sounds.SoundSource.BLOCKS, 1.0F, 0.7F
               );
            }
         }
         case BURNING -> {
            for (int i = 0; i < 6; i++) {
               double x = sp.getX() + (RANDOM.nextDouble() - 0.5) * 16.0;
               double z = sp.getZ() + (RANDOM.nextDouble() - 0.5) * 16.0;
               level.sendParticles(ParticleTypes.FLAME, x, floorY(s) + 1.2, z, 1, 0.2, 0.3, 0.2, 0.01);
               level.sendParticles(ParticleTypes.SMOKE, x, floorY(s) + 2.2, z, 1, 0.3, 0.4, 0.3, 0.01);
            }
         }
         case FLOODED -> {
            // At the water's own surface rather than a block above it: the pool sits in the floor
            // now, so a splash drawn at floor + 1 would be a splash hanging in the air.
            for (int i = 0; i < 5; i++) {
               double x = sp.getX() + (RANDOM.nextDouble() - 0.5) * 14.0;
               double z = sp.getZ() + (RANDOM.nextDouble() - 0.5) * 14.0;
               level.sendParticles(ParticleTypes.SPLASH, x, floorY(s) + 0.4, z, 1, 0.2, 0.2, 0.2, 0.01);
            }
         }
         case DARK -> {
            double x = sp.getX() + (RANDOM.nextDouble() - 0.5) * 14.0;
            double z = sp.getZ() + (RANDOM.nextDouble() - 0.5) * 14.0;
            level.sendParticles(ParticleTypes.SQUID_INK, x, floorY(s) + 1.5, z, 1, 0.2, 0.4, 0.2, 0.0);
         }
         case OVERGROWN -> {
            for (int i = 0; i < 4; i++) {
               double x = sp.getX() + (RANDOM.nextDouble() - 0.5) * 14.0;
               double z = sp.getZ() + (RANDOM.nextDouble() - 0.5) * 14.0;
               level.sendParticles(ParticleTypes.HAPPY_VILLAGER, x, floorY(s) + 1.4, z, 1, 0.3, 0.4, 0.3, 0.01);
            }
         }
      }
   }

   /**
    * Puts a chamber into its condition. Runs after the room is built and dressed, so it alters a
    * finished room rather than being painted over by one - and never blocks a doorway, because a
    * condition that walls a room in is a condition that ends runs by accident.
    */
   private static void applyCondition(State s, Room room, int bx, int bz, int fy, int top) {
      ServerLevel level = s.zoneLevel;
      int c = ROOM_PITCH / 2;
      BlockState water = Blocks.WATER.defaultBlockState();
      switch (room.condition) {
         case FLOODED -> {
            // Water across the floor, with a cross of dry stone through the middle where the four
            // archways stand: a flooded room has to be a room you can still cross, or the maze has
            // a chamber in it that eating a bucket of water would have been worth.
            //
            // The water sits IN the floor rather than ON it, and that one block is the whole of why
            // the room stays flooded. A layer laid over a walkable floor has a five-wide channel of
            // air beside it - the dry cross - and four open archways at the end of that channel, so
            // it runs out of the room in the first seconds of a run and what is left is a dry hall
            // with wet patches. That is the report: "the water in the flooded rooms goes away."
            // Sunk a block, the pool's only neighbours at its own level are the chamber's own floor
            // blocks, the wall band and the bedrock underfoot, and water with nowhere to go stays
            // where it was put. The explorer steps down into it and wades, which is the texture the
            // condition was always meant to have.
            for (int lx = interior(); lx < interiorEnd(); lx++) {
               for (int lz = interior(); lz < interiorEnd(); lz++) {
                  if (Math.abs(lx - c) <= 2 || Math.abs(lz - c) <= 2) {
                     // The cross keeps its floor, which is also the wall the pool is held by.
                     continue;
                  }
                  BlockPos floor = new BlockPos(bx + lx, fy, bz + lz);
                  if (isMineableOre(level.getBlockState(floor)) || level.getBlockState(floor).is(Blocks.CHEST)) {
                     continue;
                  }
                  // No sea pickles under the Sunken Temple, for the same reason the temple's own
                  // basins lost theirs: the temple is already a water room, so a pickle on the
                  // surface is the one block in it that reads as a garden pond rather than as a
                  // drowned hall. Every other dungeon's flooded room keeps them, where they are
                  // the only sign that the water has been there long enough to grow something.
                  boolean pickles = pondPickles(s.type) && RANDOM.nextInt(40) == 0;
                  setIfChanged(
                     level, floor,
                     pickles
                        ? Blocks.SEA_PICKLE.defaultBlockState()
                        : Blocks.WATER.defaultBlockState()
                  );
                  // ...and an invisible barrier under the pool. Barriers are the one block in the
                  // game that is watertight without being visible, so a rim of them beneath the
                  // water costs the room nothing to look at and is the reason a hole left behind by
                  // an ore stud or a chest can no longer drain half a chamber into the rock while
                  // nobody is watching. Only ever placed where the block it replaces was air.
                  BlockPos under = floor.below();
                  if (level.getBlockState(under).isAir()) {
                     setIfChanged(level, under, Blocks.BARRIER.defaultBlockState());
                  }
               }
            }
         }
         case BURNING -> {
            int placed = 0;
            for (int i = 0; i < 26 && placed < 10; i++) {
               int lx = interior() + RANDOM.nextInt(Math.max(1, interiorEnd() - interior()));
               int lz = interior() + RANDOM.nextInt(Math.max(1, interiorEnd() - interior()));
               if (inDoorLane(room, lx, lz)) {
                  continue;
               }
               BlockPos cell = new BlockPos(bx + lx, fy, bz + lz);
               BlockPos above = cell.above();
               if (!dressable(level, cell, floorBlockFor(s.type)) || !dressable(level, above, null)) {
                  continue;
               }
               // Braziers rather than naked fire: fire on its own burns out and takes the room's
               // furniture with it, and a campfire is lit for good and hurts anybody who stands on it.
               setIfChanged(level, cell, RANDOM.nextInt(3) == 0 ? Blocks.MAGMA_BLOCK.defaultBlockState() : Blocks.CAMPFIRE.defaultBlockState());
               placed++;
            }
         }
         case DARK -> {
            // Every lamp in the room is out. Nothing is added - the room simply stops being lit, and
            // the black outline every body of the pack wears becomes the only thing in it.
            for (int lx = interior(); lx < interiorEnd(); lx++) {
               for (int lz = interior(); lz < interiorEnd(); lz++) {
                  for (int y : new int[]{top, top - 1, fy + 4, fy + 3, fy + 2}) {
                     BlockPos at = new BlockPos(bx + lx, y, bz + lz);
                     if (isLamp(level.getBlockState(at))) {
                        setIfChanged(level, at, y == top ? ceilingBlockFor(s.type) : trimBlockFor(s.type));
                     }
                  }
               }
            }
         }
         case OVERGROWN -> {
            for (int i = 0; i < 40; i++) {
               int lx = interior() + RANDOM.nextInt(Math.max(1, interiorEnd() - interior()));
               int lz = interior() + RANDOM.nextInt(Math.max(1, interiorEnd() - interior()));
               if (inDoorLane(room, lx, lz)) {
                  continue;
               }
               BlockPos cell = new BlockPos(bx + lx, fy, bz + lz);
               BlockPos above = cell.above();
               if (dressable(level, cell, floorBlockFor(s.type)) && dressable(level, above, null)) {
                  setIfChanged(level, cell, Blocks.MOSS_BLOCK.defaultBlockState());
                  if (RANDOM.nextBoolean()) {
                     setIfChanged(level, above, Blocks.MOSS_CARPET.defaultBlockState());
                  }
               }
               BlockPos roof = new BlockPos(bx + lx, top - 1, bz + lz);
               if (RANDOM.nextInt(3) == 0 && dressable(level, roof, null)) {
                  setIfChanged(level, roof, Blocks.HANGING_ROOTS.defaultBlockState());
               }
            }
            for (int i = 0; i < 6; i++) {
               int lx = interior() + 1 + RANDOM.nextInt(Math.max(1, interiorEnd() - interior() - 2));
               int lz = interior() + 1 + RANDOM.nextInt(Math.max(1, interiorEnd() - interior() - 2));
               if (inDoorLane(room, lx, lz) || inDoorLane(room, lx + 1, lz) || inDoorLane(room, lx, lz + 1)) {
                  continue;
               }
               for (int dx = 0; dx <= 1; dx++) {
                  for (int dz = 0; dz <= 1; dz++) {
                     BlockPos cell = new BlockPos(bx + lx + dx, fy + 1, bz + lz + dz);
                     if (dressable(level, cell, null)) {
                        setIfChanged(level, cell, Blocks.OAK_LEAVES.defaultBlockState());
                     }
                  }
               }
            }
         }
         case UNSTABLE -> {
            // Cracks in the roof, and the first of the rubble already on the floor. The rest of it
            // arrives while somebody is standing under it - see conditionTick.
            for (int i = 0; i < 30; i++) {
               int lx = interior() + RANDOM.nextInt(Math.max(1, interiorEnd() - interior()));
               int lz = interior() + RANDOM.nextInt(Math.max(1, interiorEnd() - interior()));
               if (inDoorLane(room, lx, lz)) {
                  continue;
               }
               BlockPos cell = new BlockPos(bx + lx, fy, bz + lz);
               if (dressable(level, cell, floorBlockFor(s.type))) {
                  setIfChanged(level, cell, RANDOM.nextBoolean() ? Blocks.GRAVEL.defaultBlockState() : Blocks.COBBLESTONE.defaultBlockState());
               }
               BlockPos roof = new BlockPos(bx + lx, top - 1, bz + lz);
               if (dressable(level, roof, null) && RANDOM.nextInt(4) == 0) {
                  setIfChanged(level, roof, Blocks.GRAVEL.defaultBlockState());
               }
            }
         }
      }
   }

   /** One line on what a condition means for the way the room has to be fought. */
   private static String conditionNote(Condition condition) {
      return switch (condition) {
         case FLOODED -> "the floor is under water, and everything in it moves slower than you do";
         case BURNING -> "braziers and magma are burning through the floor - watch where you step";
         case DARK -> "every lamp in it is out; the pack's outline is all you will see of them";
         case OVERGROWN -> "the walls are growing back over it, and the growth is good cover for both sides";
         case UNSTABLE -> "the roof is shedding - it will bring rubble down on whoever is standing under it";
      };
   }

   /** True for a block whose whole job in a room is to be the light. */
   private static boolean isLamp(BlockState state) {
      return state.is(Blocks.LANTERN) || state.is(Blocks.SOUL_LANTERN) || state.is(Blocks.SEA_LANTERN)
         || state.is(Blocks.GLOWSTONE) || state.is(Blocks.SHROOMLIGHT) || state.is(Blocks.REDSTONE_LAMP)
         || state.is(Blocks.JACK_O_LANTERN) || state.is(Blocks.TORCH) || state.is(Blocks.END_ROD)
         || state.is(Blocks.CAMPFIRE);
   }

   private static void collapseFx(State s, ServerPlayer sp) {
      ServerLevel level = s.zoneLevel;
      for (int i = 0; i < 8; i++) {
         double x = sp.getX() + (RANDOM.nextDouble() - 0.5) * 6.0;
         double z = sp.getZ() + (RANDOM.nextDouble() - 0.5) * 6.0;
         level.sendParticles(ParticleTypes.LARGE_SMOKE, x, sp.getY() + 0.5, z, 1, 0.2, 0.4, 0.2, 0.01);
         level.sendParticles(ParticleTypes.CRIT, x, sp.getY() + 1.2, z, 2, 0.3, 0.3, 0.3, 0.05);
      }
      if (ServerClock.clock(level) % 20L == 0L) {
         sp.hurt(sp.damageSources().generic(), 2.0F);
      }
      if (RANDOM.nextInt(10) == 0) {
         spawnWave(s, sp.blockPosition(), Type.MONSTER_CAVE, 3, 0);
      }
   }

   /**
    * The fall, read from where the explorer is standing: the countdown, the groans and the map.
    *
    * <p>Everything here is a function of one number - {@link #collapsePressure}, the share of the
    * site already coming down - because a countdown that has to be read off a chat line is not a
    * countdown. Three things carry it, and they escalate together:
    *
    * <ul>
    *   <li><b>The ring.</b> A circle of dust is drawn around the explorer at a radius that closes with
    *       every chamber that falls, from {@link #COLLAPSE_RING_FAR} at the first to
    *       {@link #COLLAPSE_RING_NEAR} when the last one is under the fall. It is the one piece of the
    *       collapse a running player can read without looking away from the way out.</li>
    *   <li><b>The groans.</b> A rumble paced by {@link #collapseRumbleTicks} and pitched by
    *       {@link #collapseGroanPitch}, coming from the nearest chamber actually coming down rather
    *       than from the sky, so the sound has somewhere to look. Past the last third the ground
    *       starts booming under it.</li>
    *   <li><b>The shafts.</b> Every chamber mid-fall stands under a column of sparks that grows with
    *       the pressure, which is the map: a site coming down chamber by chamber draws itself across
    *       the maze, and the pieces of it left standing are the pieces with no light over them.</li>
    * </ul>
    */
   private static void collapseRumble(State s, ServerPlayer sp) {
      ServerLevel level = s.zoneLevel;
      long now = ServerClock.clock(level);
      if (now < s.collapseRumbleAt) {
         return;
      }
      double p = collapsePressure(s.collapseFelled, s.collapseTotal);
      s.collapseRumbleAt = now + collapseRumbleTicks(p);
      int fy = floorY(s);
      // Where the groan comes from: the nearest chamber that is actually coming down right now, or
      // the explorer themself when nothing is mid-fall at this instant.
      double gx = sp.getX();
      double gz = sp.getZ();
      double best = Double.MAX_VALUE;
      for (Room r : s.shedding) {
         BlockPos c = roomSpot(s, r);
         if (c == null) {
            continue;
         }
         double d = horizDist2(c, sp.blockPosition());
         if (d < best) {
            best = d;
            gx = c.getX() + 0.5;
            gz = c.getZ() + 0.5;
         }
      }
      float vol = (float)(1.2 + 1.7 * p);
      level.playSound(null, gx, fy + 2.0, gz, SoundEvents.GRAVEL_BREAK, SoundSource.BLOCKS, vol, collapseGroanPitch(p));
      if (p >= 0.35) {
         level.playSound(null, gx, fy + 1.0, gz, SoundEvents.STONE_BREAK, SoundSource.BLOCKS, vol * 0.9F, collapseGroanPitch(p) * 1.15F);
      }
      if (p >= 0.7) {
         level.playSound(null, gx, fy + 1.0, gz, SoundEvents.GENERIC_EXPLODE, SoundSource.BLOCKS, 0.9F, 1.5F);
      }
      if (p >= 1.0) {
         // The last chamber is the threshold, and the player is either on the pad or about to find
         // out what the roof of a falling site does: the chime under the boom is the site's own
         // last word, not another chamber.
         SoundUtil.play(sp, ModSounds.MYSTERY);
      }
      // The countdown, and the map.
      double radius = collapseRingRadius(p);
      BossVfx.ring(level, new net.minecraft.world.phys.Vec3(sp.getX(), fy + 0.3, sp.getZ()), radius, 26, ParticleTypes.CAMPFIRE_COSY_SMOKE, 0.0);
      BossVfx.ring(level, new net.minecraft.world.phys.Vec3(sp.getX(), fy + 1.1, sp.getZ()), radius, 12, ParticleTypes.LARGE_SMOKE, 0.0);
      for (Room r : s.shedding) {
         BlockPos c = roomSpot(s, r);
         if (c == null) {
            continue;
         }
         BossVfx.column(
            level, new net.minecraft.world.phys.Vec3(c.getX() + 0.5, fy + 1.0, c.getZ() + 0.5),
            3.0 + 7.0 * p, ParticleTypes.END_ROD, 1
         );
      }
   }

   /** Risk meter: the more loot secured, the deadlier the ambient spawns. 0-3 heat. */
   private static int heatOf(State s) {
      long v = s.lootValue;
      if (v >= 50000L) {
         return 3;
      }
      if (v >= 20000L) {
         return 2;
      }
      return v >= 8000L ? 1 : 0;
   }

   /** Fixed site location per expedition type in the expedition dimension. The
    *  dungeons are spaced a couple of thousand blocks apart: an infinite maze
    *  grows, and two runs of different types must never carve into each other. */
   /**
    * A site of this dungeon's own for one run.
    *
    * <p>A site used to be a constant per dungeon type, and that is the whole of the bug it no
    * longer has: two explorers who started the same dungeon were handed the same coordinates, so
    * the second run carved its threshold into the first run's maze - same rooms, same chests, same
    * pack, same escape pad - and whichever of them cleared a chamber cleared it for both. A site is
    * the one thing a run owns exclusively, so it is taken rather than assumed: the first slot in
    * this type's own lane that no other live run is standing in.
    *
    * <p>The lane is the type's own column, which is what keeps the dungeons apart - they were always
    * three thousand blocks from one another, and nothing here moves them. Sites only ever step
    * along it, {@link #SITE_SPACING} at a time.
    */
   private static BlockPos acquireSite(ServerLevel level, Type type, UUID holder) {
      BlockPos origin = siteFor(type);
      for (int slot = 0; slot < 512; slot++) {
         BlockPos candidate = new BlockPos(origin.getX(), origin.getY(), origin.getZ() + SITE_SPACING * slot);
         if (siteInUse(candidate) || groundUsed(level, candidate)) {
            continue;
         }
         holdSite(holder, candidate);
         return candidate;
      }
      // No free slot anywhere in this lane, which takes five hundred runs of one dungeon to reach -
      // and every one of those slots either live or already carved. Far out in the next lane still
      // beats sharing a site with somebody, or with somebody's last run.
      BlockPos far = new BlockPos(origin.getX() + SITE_SPACING, origin.getY(), origin.getZ() + SITE_SPACING * 512);
      holdSite(holder, far);
      return far;
   }

   private static void holdSite(UUID holder, BlockPos site) {
      liveSites.add(site);
      runSites.computeIfAbsent(holder, k -> new ArrayList<>()).add(site);
      usedGrounds.add(groundKey(site));
   }

   /**
    * Ground that has already held a site, by {@code x,z}, for this server's whole life.
    *
    * <p>This exists because of the second half of a site's story. A site is handed back the moment
    * a run ends - {@link #releaseSites} - and the allocator then takes the first slot nobody is
    * standing in, which used to be the same slot the run that just ended was standing in. The site
    * was free, so it was re-handed, and the run that got it was carved into the previous run's
    * maze: same rooms, same archways with the old run's cage bars standing in them, same chests
    * with the loot already taken, same sealed chambers - all of it inherited, and none of it
    * removed, because a run's end clears its <i>bodies</i> and leaves every block it cut exactly
    * where it was. Reported as "I can't get through - the new expedition didn't clear the old one",
    * which is what a barred archway nobody owns looks like from the inside.
    *
    * <p>So ground is burnt after use: a site that has ever held a run is never handed out again, and
    * the lane is a lane of one-shot sites. There is room for it - five hundred and twelve slots per
    * dungeon at {@link #SITE_SPACING} apart is twenty thousand blocks of lane - and the alternative,
    * clearing a whole carved dungeon before building the next one on top of it, is a job that costs
    * tens of thousands of block writes for a maze that is only ever walked once.
    */
   private static final java.util.Set<String> usedGrounds = new java.util.HashSet<>();

   private static String groundKey(BlockPos site) {
      return site.getX() + "," + site.getZ();
   }

   /**
    * Whether a candidate has ever held a site - asked twice, because the ledger can be lost.
    *
    * <p>The ledger is the answer for everything this server has seen, and it is saved with the
    * codex. The probe underneath it is the answer for the ground the ledger has forgotten: a save
    * rolled back, a world carried over from a build before any of this existed, a server killed
    * between a run's first block and its next save. It is one block read, at the middle of where
    * that site's threshold chamber would be - a used site has a chamber floor there and a fresh one
    * has the void - so the question costs nothing to ask five hundred times while looking for a
    * slot.
    */
   private static boolean groundUsed(ServerLevel level, BlockPos candidate) {
      if (usedGrounds.contains(groundKey(candidate))) {
         return true;
      }
      if (level == null) {
         return false;
      }
      BlockPos thresholdFloor = new BlockPos(
         candidate.getX() + ROOM_PITCH / 2, candidate.getY() - 1, candidate.getZ() + ROOM_PITCH / 2
      );
      return !level.getBlockState(thresholdFloor).isAir();
   }

   /** True when a live run is already standing in this site. */
   private static boolean siteInUse(BlockPos candidate) {
      for (BlockPos held : liveSites) {
         long dx = (long)held.getX() - candidate.getX();
         long dz = (long)held.getZ() - candidate.getZ();
         if (dx * dx + dz * dz < (long)SITE_CLEARANCE * SITE_CLEARANCE) {
            return true;
         }
      }
      return false;
   }

   /**
    * Hands every site a run was standing in back into the pool.
    *
    * <p>Called from every way a run can end - escape, death, the site taking the explorer, a body
    * that logs off mid-run - because a site held by a run that no longer exists is a site the next
    * explorer of that dungeon is pushed past for nothing.
    */
   private static void releaseSites(UUID holder) {
      if (holder == null) {
         return;
      }
      List<BlockPos> held = runSites.remove(holder);
      if (held != null) {
         liveSites.removeAll(held);
      }
   }

   /** Sites live runs are standing in, and how many of them there are - the audit's window on it. */
   public static int liveSiteCount() {
      return liveSites.size();
   }

   /** True when a live run is standing in this exact site - the audit's other window on it. */
   public static boolean siteIsHeld(BlockPos site) {
      return siteInUse(site);
   }

   /** The site a dungeon would hand its next run, before anything is standing on it. */
   public static BlockPos nextSiteFor(Type type) {
      BlockPos origin = siteFor(type);
      for (int slot = 0; slot < 512; slot++) {
         BlockPos candidate = new BlockPos(origin.getX(), origin.getY(), origin.getZ() + SITE_SPACING * slot);
         if (!siteInUse(candidate) && !usedGrounds.contains(groundKey(candidate))) {
            return candidate;
         }
      }
      return origin;
   }

   /** How many sites this server has ever carved - the audit's window on the ground ledger. */
   public static int usedGroundCount() {
      return usedGrounds.size();
   }

   /** Test seam: take a site the way a run does, without arranging a run. */
   public static BlockPos holdSiteForTest(Type type, UUID holder) {
      return acquireSite(null, type, holder);
   }

   /** Test seam: hand a run's sites back the way a run's own end does. */
   public static void releaseSitesForTest(UUID holder) {
      releaseSites(holder);
   }

   /**
    * Test seam: forget every site this server has carved, so a check can start from open ground.
    *
    * <p>Only ever called by the harness, and only ever when no run is live - the ledger it empties is
    * a description of the world, and a check that leaves it dirty would make the next check's answer
    * depend on the order the checks ran in.
    */
   public static void forgetGroundForTest() {
      usedGrounds.clear();
      liveSites.clear();
      runSites.clear();
   }

   /** Whether this exact ground has ever held a site, ledger only - the audit's other window. */
   public static boolean groundWasUsed(BlockPos site) {
      return usedGrounds.contains(groundKey(site));
   }

   private static BlockPos siteFor(Type type) {
      return switch (type) {
         case DEEP_MINE -> new BlockPos(0, 1, 0);
         case MONSTER_CAVE -> new BlockPos(3000, 1, 0);
         case CRYSTAL_CAVERN -> new BlockPos(6000, 1, 0);
         case VOID -> new BlockPos(9000, 1, 0);
         case SUNKEN_TEMPLE -> new BlockPos(15000, 1, 0);
         case FROZEN_CRYPT -> new BlockPos(18000, 1, 0);
         case MAGMA_FORGE -> new BlockPos(21000, 1, 0);
         default -> new BlockPos(0, 1, 0);
      };
   }

   // ------------------------------------------------------------------
   // The expedition codex: what this world's explorers have seen and taken
   // ------------------------------------------------------------------

   /** One dungeon's page of the codex, as the menu reads it. */
   public record DungeonPage(int runs, int escapes, int deepest, long bestPayout, long totalEarned) {
   }

   /** One chamber's page of the codex, as the menu reads it. */
   public record ChamberPage(int seen, int cleared) {
   }

   private static final class TypeStats {
      int runs;
      int escapes;
      int deepest;
      long bestPayout;
      long totalEarned;
   }

   private static final class ChamberStats {
      int seen;
      int cleared;
   }

   private static final Map<Type, TypeStats> codexDungeonStats = new java.util.EnumMap<>(Type.class);
   private static final Map<Chamber, ChamberStats> codexChamberStats = new java.util.EnumMap<>(Chamber.class);
   private static int codexRuns;
   private static int codexEscapes;
   private static int codexFalls;
   private static int codexGuardians;
   private static int codexDescents;
   private static int codexChambersCleared;
   private static long codexTotalEarned;
   private static Path codexFile;

   private static TypeStats dungeonStats(Type t) {
      return codexDungeonStats.computeIfAbsent(t, k -> new TypeStats());
   }

   private static ChamberStats chamberStats(Chamber c) {
      return codexChamberStats.computeIfAbsent(c, k -> new ChamberStats());
   }

   private static void codexSee(Chamber c) {
      if (c != Chamber.ENTRANCE) {
         chamberStats(c).seen++;
      }
   }

   private static void codexClear(Chamber c) {
      if (c != Chamber.ENTRANCE) {
         codexChambersCleared++;
         chamberStats(c).cleared++;
      }
   }

   private static void codexEscape(State s, long payout) {
      codexEscapes++;
      codexTotalEarned += payout;
      TypeStats st = dungeonStats(s.type);
      st.escapes++;
      st.deepest = Math.max(st.deepest, s.deepest);
      st.bestPayout = Math.max(st.bestPayout, payout);
      st.totalEarned += payout;
   }

   // --- the read side, for the expedition codex menu ---

   public static int codexRuns() {
      return codexRuns;
   }

   public static int codexEscapes() {
      return codexEscapes;
   }

   public static int codexFalls() {
      return codexFalls;
   }

   public static int codexGuardians() {
      return codexGuardians;
   }

   public static int codexDescents() {
      return codexDescents;
   }

   public static int codexChambersCleared() {
      return codexChambersCleared;
   }

   public static long codexTotalEarned() {
      return codexTotalEarned;
   }

   public static DungeonPage dungeonPage(Type t) {
      TypeStats st = codexDungeonStats.get(t);
      return st == null
         ? new DungeonPage(0, 0, 0, 0L, 0L)
         : new DungeonPage(st.runs, st.escapes, st.deepest, st.bestPayout, st.totalEarned);
   }

   public static ChamberPage chamberPage(Chamber c) {
      ChamberStats st = codexChamberStats.get(c);
      return st == null ? new ChamberPage(0, 0) : new ChamberPage(st.seen, st.cleared);
   }

   /** Loaded with everything else at boot. The codex is world history - it survives every run. */
   public static void loadCodex(MinecraftServer server) {
      codexDungeonStats.clear();
      codexChamberStats.clear();
      usedGrounds.clear();
      codexFile = EconomyManager.getDataDir(server).resolve("expedition_codex.json");
      JsonObject root = JsonUtil.readOrCreate(codexFile, new JsonObject());
      if (root.has("grounds") && root.get("grounds").isJsonArray()) {
         for (JsonElement e : root.getAsJsonArray("grounds")) {
            try {
               usedGrounds.add(e.getAsString());
            } catch (Exception ignored) {
            }
         }
      }
      codexRuns = (int)JsonUtil.jsonLong(root, "runs", 0L);
      codexEscapes = (int)JsonUtil.jsonLong(root, "escapes", 0L);
      codexFalls = (int)JsonUtil.jsonLong(root, "falls", 0L);
      codexGuardians = (int)JsonUtil.jsonLong(root, "guardians", 0L);
      codexDescents = (int)JsonUtil.jsonLong(root, "descents", 0L);
      codexChambersCleared = (int)JsonUtil.jsonLong(root, "chambers_cleared", 0L);
      codexTotalEarned = JsonUtil.jsonLong(root, "total_earned", 0L);
      if (root.has("dungeons") && root.get("dungeons").isJsonObject()) {
         for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("dungeons").entrySet()) {
            try {
               JsonObject o = e.getValue().getAsJsonObject();
               TypeStats st = new TypeStats();
               st.runs = (int)JsonUtil.jsonLong(o, "runs", 0L);
               st.escapes = (int)JsonUtil.jsonLong(o, "escapes", 0L);
               st.deepest = (int)JsonUtil.jsonLong(o, "deepest", 0L);
               st.bestPayout = JsonUtil.jsonLong(o, "best", 0L);
               st.totalEarned = JsonUtil.jsonLong(o, "total", 0L);
               codexDungeonStats.put(Type.valueOf(e.getKey()), st);
            } catch (Exception ignored) {
            }
         }
      }
      if (root.has("chambers") && root.get("chambers").isJsonObject()) {
         for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("chambers").entrySet()) {
            try {
               JsonObject o = e.getValue().getAsJsonObject();
               ChamberStats st = new ChamberStats();
               st.seen = (int)JsonUtil.jsonLong(o, "seen", 0L);
               st.cleared = (int)JsonUtil.jsonLong(o, "cleared", 0L);
               codexChamberStats.put(Chamber.valueOf(e.getKey()), st);
            } catch (Exception ignored) {
            }
         }
      }
   }

   public static void saveCodex(MinecraftServer server) {
      if (codexFile == null) {
         codexFile = EconomyManager.getDataDir(server).resolve("expedition_codex.json");
      }
      JsonObject root = new JsonObject();
      root.addProperty("runs", codexRuns);
      root.addProperty("escapes", codexEscapes);
      root.addProperty("falls", codexFalls);
      root.addProperty("guardians", codexGuardians);
      root.addProperty("descents", codexDescents);
      root.addProperty("chambers_cleared", codexChambersCleared);
      root.addProperty("total_earned", codexTotalEarned);
      JsonObject dungeons = new JsonObject();
      for (Map.Entry<Type, TypeStats> e : codexDungeonStats.entrySet()) {
         JsonObject o = new JsonObject();
         o.addProperty("runs", e.getValue().runs);
         o.addProperty("escapes", e.getValue().escapes);
         o.addProperty("deepest", e.getValue().deepest);
         o.addProperty("best", e.getValue().bestPayout);
         o.addProperty("total", e.getValue().totalEarned);
         dungeons.add(e.getKey().name(), o);
      }
      root.add("dungeons", dungeons);
      JsonObject chambers = new JsonObject();
      for (Map.Entry<Chamber, ChamberStats> e : codexChamberStats.entrySet()) {
         JsonObject o = new JsonObject();
         o.addProperty("seen", e.getValue().seen);
         o.addProperty("cleared", e.getValue().cleared);
         chambers.add(e.getKey().name(), o);
      }
      root.add("chambers", chambers);
      // The ground this server has carved, so the next run of a dungeon steps past every site that
      // has already been built on. See {@link #groundUsed}.
      JsonArray grounds = new JsonArray();
      for (String g : usedGrounds) {
         grounds.add(g);
      }
      root.add("grounds", grounds);
      JsonUtil.write(codexFile, root);
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

   /**
    * Finds a spot INSIDE the carved dungeon: open air at chamber-floor level, over
    *  a solid floor, in an already-carved chamber. Never spawns on the roof or
    *  in the void beyond the maze.
    */
   private static BlockPos randomMazeSpot(State s, BlockPos center, int radius) {
      ServerLevel level = s.zoneLevel;
      int fy = floorY(s);
      for (int tries = 0; tries < 16; tries++) {
         int x = center.getX() + RANDOM.nextInt(radius * 2 + 1) - radius;
         int z = center.getZ() + RANDOM.nextInt(radius * 2 + 1) - radius;
         int y = fy + 1 + RANDOM.nextInt(3); // chamber interior: floor at fy, roof at fy + ROOM_HEIGHT
         BlockPos p = new BlockPos(x, y, z);
         if (!isGenerated(s, p)) {
            continue;
         }
         if (level.getBlockState(p).isAir() && !level.getBlockState(p.below()).isAir()) {
            return p;
         }
      }
      return null;
   }

   /**
    * A standing spot on one chamber's floor, or null when this chamber has none to give.
    *
    * <p>Read-only on purpose: the camps' beacon and an unstable roof's embers both ask for "somewhere
    * in that room" once a second, and a spot-finder that rearranged the room to answer would be a
    * spot-finder that carved a hole in a chamber every time somebody looked at it. See
    * {@link #packSpot} for the version a pack wakes on.
    */
   private static BlockPos roomSpot(State s, Room room) {
      ServerLevel level = s.zoneLevel;
      int fy = floorY(s);
      int bx = baseX(s, room.rx);
      int bz = baseZ(s, room.rz);
      // Pass one: a clean floor cell. Pass two: anywhere a body fits at all, which is what the
      // flooded, overgrown and webbed chambers need - a drowned in a drowned hall is a body exactly
      // where it belongs, and the room is the fight either way.
      for (int tries = 0; tries < 64; tries++) {
         BlockPos p = roomCell(bx, bz, fy);
         if (openCell(level, p)) {
            return p;
         }
      }
      for (int tries = 0; tries < 64; tries++) {
         BlockPos p = roomCell(bx, bz, fy);
         if (passableCell(level, p)) {
            return p;
         }
      }
      return null;
   }

   /** Somewhere in one chamber's interior, at floor height - a roll, not a claim about it. */
   private static BlockPos roomCell(int bx, int bz, int fy) {
      int span = ROOM_PITCH - 2 * WALL - 2;
      int lx = WALL + 1 + RANDOM.nextInt(span);
      int lz = WALL + 1 + RANDOM.nextInt(span);
      return new BlockPos(bx + lx, fy + 1, bz + lz);
   }

   /**
    * The spot a chamber's pack wakes on, and the reason a pack is never short of members.
    *
    * <p>The search used to be "air over something solid, twenty-four tries, give up" - and it gave
    * up silently. A flooded chamber is water over stone, a web nest is cobwebs over stone, an
    * overgrown one is leaves and grass, and in every one of those rooms the pack woke as fewer
    * bodies than the chamber rolled, or as none at all: a chamber that clears itself, doors that
    * open onto a fight nobody had. So the search now has three passes, and the last one cannot fail.
    *
    * <p>Pass one is the clean floor cell. Pass two is anywhere a body fits at all, which is what the
    * flooded and overgrown rooms need - the dungeon's own monsters are chosen for the room they wake
    * in, and a drowned in a drowned hall is not a body in the wrong place. Pass three is a pocket cut
    * at the middle of the chamber: nothing in a built room fits, so the room is made to fit a
    * monster, skipping anything the run needs (a chest, a spawner, the sealed bars of a cage).
    */
   private static BlockPos packSpot(State s, Room room) {
      BlockPos spot = roomSpot(s, room);
      if (spot != null) {
         return spot;
      }
      ServerLevel level = s.zoneLevel;
      int fy = floorY(s);
      int bx = baseX(s, room.rx);
      int bz = baseZ(s, room.rz);
      int[][] middle = {{0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}};
      for (int[] off : middle) {
         BlockPos p = new BlockPos(bx + ROOM_PITCH / 2 + off[0], fy + 1, bz + ROOM_PITCH / 2 + off[1]);
         BlockPos above = p.above();
         if (!clearable(level, p) || !clearable(level, above)) {
            continue;
         }
         setIfChanged(level, p, Blocks.AIR.defaultBlockState());
         setIfChanged(level, above, Blocks.AIR.defaultBlockState());
         return p;
      }
      return null;
   }

   /** A cell a dry body can stand on: air, with air over its head and something under its feet. */
   private static boolean openCell(ServerLevel level, BlockPos p) {
      return level.getBlockState(p).isAir()
         && level.getBlockState(p.above()).isAir()
         && !level.getBlockState(p.below()).isAir();
   }

   /**
    * ...and the same question asked of anything a body can move through rather than only of air.
    *
    * <p>Water, lava, cobwebs and grass have no collision shape, and a chamber built out of them is
    * not a chamber a monster cannot stand in - it is a chamber the old search refused to put one in.
    */
   private static boolean passableCell(ServerLevel level, BlockPos p) {
      return level.getBlockState(p).getCollisionShape(level, p).isEmpty()
         && level.getBlockState(p.above()).getCollisionShape(level, p.above()).isEmpty()
         && !level.getBlockState(p.below()).getCollisionShape(level, p.below()).isEmpty();
   }

   /** A cell that may be cut open: anything that is not part of what the run is built out of. */
   private static boolean clearable(ServerLevel level, BlockPos p) {
      BlockState here = level.getBlockState(p);
      return !here.is(Blocks.CHEST) && !here.is(Blocks.SPAWNER) && !here.is(Blocks.IRON_BARS) && !here.is(Blocks.BEDROCK);
   }

   private static void ringParticles(ServerLevel level, BlockPos center, int radius) {
      for (int i = 0; i < 12; i++) {
         double a = i / 12.0 * Math.PI * 2.0;
         int x = center.getX() + (int)Math.round(Math.cos(a) * radius);
         int z = center.getZ() + (int)Math.round(Math.sin(a) * radius);
         int y = center.getY() + 2 + (i % 3);
         level.sendParticles(ParticleTypes.SOUL, x + 0.5, y + 0.5, z + 0.5, 1, 0.1, 0.1, 0.1, 0.01);
      }
   }

   private static long horizDist2(BlockPos a, BlockPos b) {
      long dx = a.getX() - b.getX();
      long dz = a.getZ() - b.getZ();
      return dx * dx + dz * dz;
   }
}
