package com.fortuneandfavors.economy;

import com.fortuneandfavors.duel.DuelManager;
import com.fortuneandfavors.util.Chat;
import com.fortuneandfavors.util.JsonUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.math.Transformation;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.levelgen.Heightmap;
import java.util.Random;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.WeatherData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public final class ServerDisasterManager {
   public static final String BLOOD_MOON = "blood_moon";
   public static final String MINING_COLLAPSE = "mining_collapse";
   public static final String MERCHANT_FESTIVAL = "merchant_festival";
   public static final String MONSTER_INVASION = "monster_invasion";
   public static final String GOLD_RUSH = "gold_rush";
   public static final String THUNDERSTORM = "thunderstorm";
   public static final String DOUBLE_TROUBLE = "double_trouble";
   public static final String XP_FRENZY = "xp_frenzy";
   public static final String PHANTOM_SWARM = "phantom_swarm";
   /** Meteors fall, crater the ground and leave loot where they land. */
   public static final String METEOR_SHOWER = "meteor_shower";
   /** The falling body carries this, and the blast it makes is recognised by it. */
   public static final String METEOR_TAG = "ff_meteor";
   /** The day goes dark and the monsters stop caring that it is daytime. */
   public static final String SOLAR_ECLIPSE = "solar_eclipse";
   /** A rare peaceful one: coloured sky, and nothing hostile spawns at all. */
   public static final String AURORA = "aurora";
   /** A castle that is not really there, and takes itself away when the event ends. */
   public static final String MIRAGE_CASTLE = "mirage_castle";
   /**
    * It snows everywhere, and the cold is a clock rather than a colour.
    *
    * <p>The one event in this list whose danger is not a mob. Standing outside in it without
    * a heat source within reach chills the body until it freezes the way powder snow freezes
    * it - the same state, the same overlay, the same damage - so there is nothing new to learn
    * about what freezing looks like. A torch, a campfire, a fire, a lantern, lava, a magma
    * block or a lit furnace nearby is what stops the clock, which is what makes the event a
    * reason to build rather than a reason to log off.
    */
   public static final String SNOWSTORM = "snowstorm";

   /**
    * How long a meteor is in the air. Long enough to look up, short enough to matter.
    *
    * <p>A hundred and forty ticks - seven seconds - against the sixty it used to take, and that
    * is the point of the pair: the fall is the event's warning, and at three seconds from
    * seventy-four blocks up it was a warning you heard and a body you never saw, because the sky
    * turned to dust almost before anybody could follow the streak down.
    *
    * <p><b>The height and this number are one decision, not two.</b> Dropping a meteor from
    * higher while leaving the fall time alone makes it arrive <i>faster</i>, which is the opposite
    * of a longer look at it: 140 blocks over 140 ticks descends at a block a tick, which is
    * slower than the old fall (74 over 60, about 1.23) <i>and</i> nearly twice the height. The
    * self-test asserts the descent speed rather than either number, so a later "make it more
    * dramatic" cannot quietly change one without the other.
    */
   private static final int METEOR_FALL_TICKS = 140;
   /**
    * How far above the impact a meteor starts, in blocks. See {@link #METEOR_FALL_TICKS}.
    */
   private static final double METEOR_START_HEIGHT = 140.0;
   /** Every meteor currently falling, so a fall outlives the event that started it. */
   private static final List<Meteor> METEORS = new ArrayList<>();

   /**
    * One meteor in the air: where it started, where it will land, and what it is wearing.
    *
    * <p>Kept as a list rather than as state on the entity because the fall is the only part
    * of the meteor that is on a clock, and it has to survive the event ending - a shower that
    * stops mid-air would leave bodies hanging in the sky with nothing to bring them down.
    */
   private static final class Meteor {
      final ServerLevel level;
      final Vec3 from;
      final Vec3 to;
      final int ticks;
      final Entity body;
      int age;

      Meteor(ServerLevel level, Vec3 from, Vec3 to, int ticks, Entity body) {
         this.level = level;
         this.from = from;
         this.to = to;
         this.ticks = ticks;
         this.body = body;
      }
   }

   /** How close a heat source has to be to count, in blocks. */
   private static final double SNOWSTORM_WARM_RADIUS = 6.0;
   /**
    * The warmth radius inside a subzero area, where the cold eats it.
    *
    * <p>Two and a half blocks instead of six is the difference between "stand next to the
    * fire" and "be in the fire", which is the whole point of a blizzard landing on a snowy
    * plain: the storm is not the same event everywhere, and the places that are already
    * freezing are where it is worst.
    */
   private static final double SNOWSTORM_SUBZERO_RADIUS = 2.5;
   /**
    * How deep below the surface a body has to be for the storm to stop reaching it.
    *
    * <p>This replaced "can this body see the sky", which is the wrong question and was being
    * answered the wrong way: a player standing in a one-block hole, under a roof, or in a
    * doorway was completely safe, so the event's whole mechanic could be defeated by standing
    * inside and the answer it is built on - heat - never had to be found. "It is snowing
    * everywhere" cannot mean everywhere except the house.
    *
    * <p>Depth rather than a roof, because a roof is not a cave: twelve blocks is past a two
    * storey house and short of the first proper mine shaft, so a body in a building is in
    * the storm and a body underground is not. Nothing about this is a shelter test - the
    * heat in {@link #heatWithin} is the shelter, and it has to be found wherever you are.
    */
   private static final int SNOWSTORM_SHELTER_DEPTH = 12;
   /**
    * How long a body may be outside with no heat before it starts to freeze.
    *
    * <p>Twelve seconds: long enough that crossing a field is not a death sentence and that a
    * player who is caught out has time to place a torch, short enough that ignoring the event
    * has a cost inside one fight.
    */
   private static final int SNOWSTORM_GRACE_TICKS = 20 * 12;
   /**
    * How often a body's surroundings are searched for heat, in ticks.
    *
    * <p>The search is a disc of six blocks around the feet, six blocks tall - about a
    * thousand block reads - and it used to run for every player on every tick, which is
    * twenty thousand reads a second for one body and was the lag in "the snowstorm lags the
    * game". Heat does not move: half a second is far more often than a torch can appear
    * under somebody, and the answer is remembered between searches.
    */
   private static final int SNOWSTORM_HEAT_RECHECK = 10;
   /**
    * How often the storm draws its own snow, and how much of it at a time.
    *
    * <p>One call carrying a count is one packet however large the count is, so the flakes are
    * batched: the old code sent fourteen particles as fourteen separate packets per player,
    * and this sends twenty as one every four ticks - five packets a second per body for a
    * thicker storm, which is the difference between a blizzard and a lag spike.
    */
   private static final int SNOWSTORM_FLAKE_EVERY = 4;
   private static final int SNOWSTORM_FLAKE_COUNT = 20;
   private static final double SNOWSTORM_FLAKE_SPREAD = 17.0;
   /**
    * The frozen-tick figure the body is held at. Vanilla's own full-freeze figure is 140, and
    * this is deliberately that number rather than a house one: "frozen" here means exactly
    * what it means in powder snow, so the overlay, the shiver, the slow movement and the
    * damage all come from the game rather than from this file.
    */
   private static final int SNOWSTORM_FROZEN_TICKS = 140;
   /** Continuous exposure per body, in ticks. Reset the moment it finds heat again. */
   private static final java.util.Map<java.util.UUID, Integer> SNOW_CHILL = new java.util.HashMap<>();
   /** The last heat answer for each body, and the tick it was given on. */
   private static final java.util.Map<java.util.UUID, Boolean> SNOW_HEAT = new java.util.HashMap<>();
   private static final java.util.Map<java.util.UUID, Long> SNOW_HEAT_AT = new java.util.HashMap<>();
   /**
    * Bodies the storm is holding at the freeze figure right now.
    *
    * <p>Kept so the frost can be taken off them when the storm ends. Frozen ticks decay on
    * their own at one a second, so a body the storm froze and then abandoned would wear
    * vanilla's overlay, shiver and crawl for seven seconds after the snow had stopped - a
    * state outliving its cause, which is the same shape of bug as the sky outliving the
    * event. Only bodies this file froze are listed, so nobody else's frost is touched.
    */
   private static final java.util.Set<java.util.UUID> SNOW_FROZEN = new java.util.HashSet<>();
   /**
    * Frequency knobs. These were 30 min / 5% / 30 min, which works out to one
    * event roughly every ten hours of play - so a player who put in an evening
    * genuinely never saw one. A roll every five minutes at 35% puts the average
    * gap near twenty minutes, with a ten minute cooldown after each event so two
    * can't overlap or chain back to back.
    */
   private static final long EVENT_INTERVAL_TICKS = 5L * 60L * 20L; // 5 min
   private static final int EVENT_CHANCE_PERCENT = 35;
   private static final long EVENT_DURATION_TICKS = 10L * 60L * 20L; // 10 min
   private static final long COOLDOWN_TICKS = 10L * 60L * 20L;
   private static final Random RANDOM = new Random();
   private static String activeEvent = "";
   private static long eventUntil = 0L;
   private static long nextRoll = 0L;
   private static long cooldownUntil = 0L;
   private static long eventStartTick = 0L;
   /**
    * The event the rotation rolled last, so the same weather cannot follow itself.
    *
    * <p>Saved with the rest of the state rather than kept in memory: a repeat after a restart
    * would be the same report, just unfalsifiable.
    */
   private static String lastEvent = "";
   /**
    * The last few events the rotation picked, newest first, so the same weather cannot come
    * back for several rolls.
    *
    * <p>One slot only stops a repeat <i>back to back</i>, and that is not what a player means by
    * "it is always the same event". With twelve events and a roll every five minutes, a
    * single-slot memory lets the identical shower return well inside the hour, and a player who
    * reloads between the two draws sees the same event in two sessions in a row with nothing on
    * the anti-repeat path having fired. Four slots put the earliest possible repeat at the fifth
    * roll. The window is written to disk with the rest of the state, so quitting between two
    * draws cannot launder it.
    */
   private static final int RECENT_MEMORY = 4;
   private static final java.util.ArrayDeque<String> RECENT_EVENTS = new java.util.ArrayDeque<>();
   /**
    * Every event the automatic rotation may pick, one slot each.
    *
    * <p>The meteor shower used to hold two of the thirteen slots while every other event held
    * one, which made the most destructive thing in the catalogue the likeliest: about one roll in
    * seven and a half against one in thirteen for its neighbours. One slot each now, which is the
    * other half of "the meteor showers keep happening".
    */
   private static final String[] ROTATION = {
      BLOOD_MOON, MINING_COLLAPSE, MERCHANT_FESTIVAL, MONSTER_INVASION, GOLD_RUSH, THUNDERSTORM,
      XP_FRENZY, PHANTOM_SWARM, DOUBLE_TROUBLE, METEOR_SHOWER, SNOWSTORM, SOLAR_ECLIPSE
   };
   private static Path dataFile;

   private ServerDisasterManager() {
   }

   public static void load(MinecraftServer server) {
      dataFile = EconomyManager.getDataDir(server).resolve("disasters.json");
      JsonObject root = JsonUtil.readOrCreate(dataFile, new JsonObject());

      // The file records what was left *unfinished*, as ticks still to run rather than as tick
      // numbers, and this is the whole of "the events are unreachable" and of its opposite -
      // "the events happen the second I load". A tick number means nothing in the next session:
      // `getTickCount()` starts at zero every boot, so a world that saved `next_roll = 36000`
      // (an hour in) came back with a roll an hour away with the old clamp, and with the clamp
      // removed it came back with the roll already due. Both are the same mistake - a number
      // that describes the *world's* clock being read as though it described the session's.
      // A remainder in ticks is true in any session, so there is nothing to rebase.
      long cooldownRemaining = JsonUtil.jsonLong(root, "cooldown_remaining", 0L);

      // An event that was mid-flight when the session ended is over, not resumed. The world is
      // not being played while it is closed, and resuming is how the same weather ends up
      // waiting at every load - which is exactly the report this path exists to answer.
      activeEvent = "";
      eventUntil = 0L;
      eventStartTick = 0L;
      readRecent(root);

      long now = server.getTickCount();
      long[] timers = timersAfterLoad(now, cooldownRemaining);
      eventUntil = timers[0];
      nextRoll = timers[1];
      cooldownUntil = timers[2];

      // Never leave a mirage standing in a world that was loaded, however the last
      // session ended - including being killed mid-event.
      MirageCastleManager.sweepOnLoad(server);
      // A snowstorm the server did not survive leaves snow on the ground and ice on the water;
      // this is where that is taken back, before anybody sees it.
      sweepStormCoverOnLoad(server);
      // ...and never leave a sky applied either. A level's rain level is sent to every client
      // every weather tick, so an event that set one and then stopped existing - because the
      // server went down mid-event, which is the one ending that runs no code at all - leaves
      // a world raining forever. This is the teardown for that, run once per boot.
      clearEventSkies(server);
   }

   public static void save(MinecraftServer server) {
      if (dataFile == null) {
         dataFile = EconomyManager.getDataDir(server).resolve("disasters.json");
      }
      long now = server.getTickCount();
      JsonObject root = new JsonObject();
      root.addProperty("event", activeEvent);
      // Remainders, not tick numbers - see load(). Every one of these is "how much longer",
      // which is the only form of these facts that survives a restart.
      root.addProperty("event_remaining", activeEvent.isEmpty() ? 0L : Math.max(0L, eventUntil - now));
      root.addProperty("cooldown_remaining", Math.max(0L, cooldownUntil - now));
      root.addProperty("roll_in", Math.max(0L, nextRoll - now));
      // Newest first, so the window survives a restart in the order it was drawn.
      root.addProperty("recent_events", String.join(",", RECENT_EVENTS));
      // Kept in the file for anything reading it by eye; the window above is what the
      // rotation actually consults.
      root.addProperty("last_event", lastEvent);
      JsonUtil.write(dataFile, root);
   }

   /**
    * Where a loaded world's timers land. Pure, so the contract can be pinned without a world.
    *
    * <p>Three things are bought here, and they are the three the old code got wrong. An event is
    * <b>not resumed</b>: closing the world ends it, because otherwise the same weather is waiting
    * at every load. The <b>next roll is a full interval away</b>, so loading a world no longer
    * starts an event on its first tick - which is what made the rotation look like it had only
    * one entry, since the event a player met was the first roll of the session and nothing after
    * it was ever reached. And the cooldown carried in the file may only <b>shorten</b> that wait,
    * never extend it: it is clamped to one interval, so a player who quit mid-event gets the same
    * breathing room as one who quit between events rather than a longer silence for having died
    * inside a snowstorm.
    *
    * @return {@code {eventUntil, nextRoll, cooldownUntil}} as absolute ticks for this session
    */
   public static long[] timersAfterLoad(long now, long cooldownRemaining) {
      long carried = Math.max(0L, Math.min(cooldownRemaining, EVENT_INTERVAL_TICKS));
      return new long[] { 0L, now + EVENT_INTERVAL_TICKS, now + carried };
   }

   /**
    * Reads the anti-repeat window, accepting a file that predates it.
    *
    * <p>An older file carries only {@code last_event}, which is still the event that ran last -
    * it seeds the window rather than being thrown away, so upgrading the mod does not hand
    * everybody one free repeat.
    */
   private static void readRecent(JsonObject root) {
      RECENT_EVENTS.clear();
      String packed = JsonUtil.jsonString(root, "recent_events", "");
      for (String part : packed.split(",")) {
         String event = part.trim();
         if (!event.isEmpty() && RECENT_EVENTS.size() < RECENT_MEMORY) {
            RECENT_EVENTS.addLast(event);
         }
      }
      if (RECENT_EVENTS.isEmpty()) {
         String previous = JsonUtil.jsonString(root, "last_event", "");
         if (!previous.isEmpty()) {
            RECENT_EVENTS.addLast(previous);
         }
      }
      lastEvent = RECENT_EVENTS.isEmpty() ? "" : RECENT_EVENTS.peekFirst();
   }

   public static void tick(MinecraftServer server) {
      long now = server.getTickCount();

      // Not gated on the event: a meteor that is already falling when the shower ends still
      // has to land, or the sky keeps a body in it forever. The same call finishes any rise
      // a standing Mirage Castle is part way through - it grows whether or not anyone is
      // watching the event that raised it.
      tickMeteors(server);
      MirageCastleManager.tick(server);
      // And any of the snowstorm's ground cover that the event's own ending could not reach,
      // because its chunk was not loaded at that moment.
      finishStormCover(server, now);

      // Active event: particle effects + ongoing mechanics
      if (!activeEvent.isEmpty()) {
         // Per-tick particle effects for all online players
         if (now % 30L == 0L) {
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
               eventParticles(p.level(), p);
            }
         }

         // Event ending sequence (last 10 seconds)
         long remaining = eventUntil - now;
         if (remaining <= 200L && remaining > 0L && now % 40L == 0L) {
            // Warning particles as event winds down
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
               p.level().sendParticles(ParticleTypes.END_ROD, p.getX(), p.getY() + 2, p.getZ(), 10, 1.5, 0.8, 1.5, 0.05);
            }
            if (remaining <= 200L && remaining > 160L) {
               broadcast(server, "§7§oThe " + displayName(activeEvent) + "§7§o is fading...");
            }
         }

         if (now >= eventUntil) {
            String ended = activeEvent;
            activeEvent = "";
            cooldownUntil = now + COOLDOWN_TICKS;
            // Whatever the event put into the world comes back out of it here, in one
            // place, so "the event ended" is never a thing a per-event path can forget.
            if (MIRAGE_CASTLE.equals(ended)) {
               MirageCastleManager.dismantle(server);
            }
            if (SOLAR_ECLIPSE.equals(ended)) {
               for (ServerLevel level : server.getAllLevels()) {
                  clearEclipseSky(level);
               }
            }
            if (SNOWSTORM.equals(ended)) {
               // The chill goes with the storm. A body that was mid-freeze when the event
               // ended must not stay frosted for the rest of the session, and nobody's
               // exposure should carry into the next one.
               SNOW_CHILL.clear();
               SNOW_HEAT.clear();
               SNOW_HEAT_AT.clear();
               thawStormFrost(server);
               // And the ground goes back: the snow it laid and the ice it made. A storm that
               // left a white field behind would be the one event that permanently changes a
               // world, which is the thing this whole module is careful about.
               clearStormCover(server);
            }
            // Whatever sky the event owned goes back with it, and the check is generic rather
            // than per-event: every ending path has to take the sky back, and one that forgot
            // would leave a world permanently raining - which is a bug a player reports as
            // "the snowstorm never stopped".
            clearEventSkies(server);
            // Big ending broadcast with effects
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
               p.level().sendParticles(ParticleTypes.FIREWORK, p.getX(), p.getY() + 1, p.getZ(), 30, 1.0, 1.0, 1.0, 0.1);
               p.level().playSound(null, p.blockPosition(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.MASTER, 0.8F, 1.0F);
            }
            broadcast(server, "§8§m──────────────────────────§r");
            broadcast(server, "  §7The " + displayName(ended) + "§7 has ended.");
            broadcast(server, "§8§m──────────────────────────§r");
            save(server);
         }
      }

      if (activeEvent.isEmpty()) {
         if (now >= nextRoll && now >= cooldownUntil) {
            if (RANDOM.nextInt(100) < EVENT_CHANCE_PERCENT) {
               startEvent(server, nextEvent());
            }
            nextRoll = now + EVENT_INTERVAL_TICKS;
         }
      } else {
         // Ongoing event mechanics
         if (MONSTER_INVASION.equals(activeEvent) && now % 100L == 0L) {
            spawnInvasionWaves(server);
         }
         // Thunderstorm event: lightning handled in eventParticles()
         if (DOUBLE_TROUBLE.equals(activeEvent) && now % 100L == 0L) {
            spawnInvasionWaves(server);
         }
         if (PHANTOM_SWARM.equals(activeEvent) && now % 200L == 0L) {
            spawnPhantomSwarm(server);
         }
         // One impact every thirty seconds. It was every twelve, which is fifty craters in a
         // ten minute shower - a bombardment that never let a player get to the last one, and
         // fifty rolls of a loot table per event.
         if (METEOR_SHOWER.equals(activeEvent) && now % 600L == 0L) {
            dropMeteor(server);
         }
         if (SOLAR_ECLIPSE.equals(activeEvent) && now % 200L == 0L) {
            // Re-applied rather than set once: a world that was already raining has its own
            // weather timer, and a server restart or an operator's /weather clears the
            // thunder the event is made of. Ten seconds is well inside the shortest time
            // vanilla will let a sky clear itself.
            for (ServerLevel level : server.getAllLevels()) {
               darkenForEclipse(level);
            }
         }
         if (AURORA.equals(activeEvent) && now % 20L == 0L) {
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
               auroraOver(p);
            }
         }
         if (SNOWSTORM.equals(activeEvent)) {
            tickSnowstorm(server, now);
            snowstormFlakes(server, now);
            // The world itself, not only the bodies in it: snow on the ground, ice on the
            // water, and the same cold on the mobs. All of it remembered and all of it taken
            // back when the event ends.
            stormCover(server, now);
            stormMobs(server, now);
         }
      }
   }

   /**
    * One tick of the snowstorm's cold: outside with no heat within reach is a clock.
    *
    * <p>Exposure is measured per body and in ticks, and it is thrown away the instant the body
    * finds heat - so a player who runs from torch to torch never freezes, and a player who
    * walks out into the open with nothing to light has twelve seconds to do something about
    * it. Past that the body is held at vanilla's own full-freeze figure, which is what makes
    * this `powdered snow` rather than an invented effect: the overlay, the shiver, the reduced
    * movement and the freeze damage are the game's, not this file's.
    */
   private static void tickSnowstorm(MinecraftServer server, long now) {
      for (ServerPlayer p : server.getPlayerList().getPlayers()) {
         try {
            ServerLevel level = p.level() instanceof ServerLevel serverLevel ? serverLevel : null;
            java.util.UUID id = p.getUUID();
            if (level == null || !p.isAlive() || p.isSpectator() || p.isCreative() || !stormLevel(level)) {
               SNOW_CHILL.remove(id);
               forgetHeat(id);
               continue;
            }
            boolean subzero = subzeroAt(level, p);
            boolean exposed = inTheStorm(level, p) && !heatWithin(level, p, id, now, subzero);
            if (!exposed) {
               SNOW_CHILL.remove(id);
               // Warm again: the frost goes with the chill, so the state cannot outlive its
               // cause and be left frozen in a house.
               if (p.getTicksFrozen() > 0) {
                  p.setTicksFrozen(0);
               }
               continue;
            }
            // A subzero area is the storm with the choice taken away. There is no grace
            // timer to spend working out that it is cold - the freeze lands on the first
            // tick of exposure - the warmth only reaches two and a half blocks, and the
            // action bar says which of the two storms you are standing in, because a rule
            // nobody can see is a rule nobody can play around. Pinned by
            // {@code snowstorm.a-cold-biome-has-no-grace}.
            if (subzero && now % 20L == 0L) {
               p.sendSystemMessage(
                  net.minecraft.network.chat.Component.literal(
                     "§3§lSUBZERO AREA §8| §bthe cold lands instantly here §8- §ffire and lava only"
                  ),
                  true
               );
            }

            int chill = SNOW_CHILL.merge(id, 1, Integer::sum);
            int grace = subzero ? 0 : SNOWSTORM_GRACE_TICKS;
            if (chill < grace) {
               if (now % 20L == 0L) {
                  int left = (grace - chill) / 20;
                  p.sendSystemMessage(
                     net.minecraft.network.chat.Component.literal(
                        // Torches stopped being heat when the storm was reworked; the line
                        // telling players to find one outlived the rule by a release.
                        "§b§lCOLD §8| §fget to a fire or lava §8- §b" + left + "s"
                     ),
                     true
                  );
               }
               continue;
            }
            p.setTicksFrozen(Math.max(p.getTicksFrozen(), SNOWSTORM_FROZEN_TICKS));
            SNOW_FROZEN.add(id);
            if (now % 20L == 0L) {
               p.sendSystemMessage(
                  net.minecraft.network.chat.Component.literal("§b§lFROZEN §8| §fyou are freezing to death - §bget to heat"),
                  true
               );
            }
            if (now % 40L == 0L) {
               p.hurtServer(level, level.damageSources().freeze(), 1.0F);
            }
         } catch (Throwable ignored) {
            // One body's read must not stop the storm for everybody else.
         }
      }
   }

   /**
    * Whether the storm reaches this body at all.
    *
    * <p>Two answers, and neither of them is "is there a roof over your head". The storm only
    * exists in dimensions whose sky can be weather at all, and it does not reach a body far
    * enough under the ground - {@link #SNOWSTORM_SHELTER_DEPTH} - because a blizzard is a
    * thing that happens above the surface. Everything between the two is in it: standing in a
    * house, under a tree, in a doorway or in a one-block hole is being outside in a snowstorm,
    * and what those places lack is heat rather than exposure.
    */
   private static boolean inTheStorm(ServerLevel level, ServerPlayer p) {
      if (!level.canHaveWeather()) {
         return false;
      }
      try {
         int surface = level.getHeight(
            net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING,
            p.blockPosition().getX(),
            p.blockPosition().getZ()
         );
         return p.getY() >= surface - SNOWSTORM_SHELTER_DEPTH;
      } catch (Throwable t) {
         // A body that cannot be placed in its own column is left in the storm rather than
         // made immune by a read that failed.
         return true;
      }
   }

   /**
    * The same heat question, asked at most every {@link #SNOWSTORM_HEAT_RECHECK} ticks.
    *
    * <p>A disc of six blocks around the feet, six blocks tall, is about a thousand block
    * reads, and it used to run for every player every tick - which is what "the snowstorm
    * lags the game" was, and it got worse the more players were in it. Heat does not move:
    * half a second is far longer than a torch takes to appear, and the answer is remembered
    * between searches.
    */
   private static boolean heatWithin(ServerLevel level, ServerPlayer p, java.util.UUID id, long now, boolean subzero) {
      if (p.isInLava() || p.isOnFire()) {
         return true;
      }
      Long asked = SNOW_HEAT_AT.get(id);
      if (asked != null && now - asked < SNOWSTORM_HEAT_RECHECK && SNOW_HEAT.containsKey(id)) {
         return SNOW_HEAT.get(id);
      }
      boolean found = heatNearby(level, p, subzero);
      SNOW_HEAT.put(id, found);
      SNOW_HEAT_AT.put(id, now);
      return found;
   }

   private static void forgetHeat(java.util.UUID id) {
      SNOW_HEAT.remove(id);
      SNOW_HEAT_AT.remove(id);
   }

   /**
    * Whether there is anything within reach that melts snow.
    *
    * <p>Read from the blocks around the body rather than from a list of blessed block types a
    * player cannot see: what counts is anything that is visibly burning, glowing hot or made
    * of fire, which is a rule somebody can act on in the dark.
    */
   private static boolean heatNearby(ServerLevel level, ServerPlayer p, boolean subzero) {
      return heatNearby(level, p.blockPosition(), subzero ? SNOWSTORM_SUBZERO_RADIUS : SNOWSTORM_WARM_RADIUS);
   }

   /**
    * Is this body standing where the storm is heavier?
    *
    * <p>Asked of the biome's own base temperature rather than a list of biome names: a biome
    * the game already considers cold enough to snow in is a place the blizzard is worse, and
    * one added by a datapack is included by construction instead of by somebody remembering
    * to add it here. Pinned by {@code snowstorm.cold-biomes-are-subzero}.
    */
   public static boolean subzeroAt(ServerLevel level, ServerPlayer p) {
      if (level == null || p == null) {
         return false;
      }

      return subzeroAt(level, p.blockPosition());
   }

   /** The same question asked at a position, for the mobs the storm freezes too. */
   public static boolean subzeroAt(ServerLevel level, BlockPos pos) {
      if (level == null || pos == null) {
         return false;
      }

      try {
         return subzeroAt(level.getBiome(pos).value().getBaseTemperature());
      } catch (Throwable t) {
         return false;
      }
   }

   /** The rule itself, as a number, so the self-test can pin both sides of it. */
   public static boolean subzeroAt(float baseTemperature) {
      return baseTemperature <= 0.05F;
   }

   /**
    * Whether a block is heat.
    *
    * <p>One list, read from both the player's rule and the mobs', because two copies of it
    * drift: a torch warm for a person and cold for the zombie standing in it is a rule nobody
    * can learn.
    */
   private static boolean isHeatSource(BlockState state) {
      // Fire and lava, and nothing that merely glows.
      //
      // The old list included torches, lanterns, sea lanterns, glowstone, froglights, furnaces
      // and candles, and the report was simple: the storm was answered by a wall of torches, so
      // the one thing the event is about - finding real heat - was solved by lighting a corridor.
      // A torch is a light source that happens to be warm; it is not a fire. What warms you now
      // is a fire you have to stand in (or beside), magma, and lava, which is the only heat in
      // the game that never goes out - one lit campfire, which is the exception, and it burns
      // down: see {@link #campfireIsBurning}.
      return state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE)
                  || state.is(Blocks.LAVA) || state.is(Blocks.MAGMA_BLOCK);
   }

   /**
    * How long a campfire stays lit once the storm finds it.
    *
    * <p>Forty-five seconds, and it is the reason campfires are worth making: they are the one
    * heat a player can carry into the storm - put one down, warm up, keep moving - and a fire
    * that lasted forever beside a fire that never goes out at all (lava) would make the two the
    * same answer. It goes out rather than being taken away, so the block stays where the player
    * put it and can be relit.
    */
   public static final int CAMPFIRE_LIFE_TICKS = 900;
   /** When each campfire the storm has found goes out. Keyed by position, cleared with the event. */
   private static final Map<BlockPos, Long> CAMPFIRE_EMBERS = new HashMap<>();

   private static boolean campfireIsBurning(ServerLevel level, BlockPos at, BlockState state, long now) {
      Long until = CAMPFIRE_EMBERS.get(at);
      if (until == null) {
         CAMPFIRE_EMBERS.put(at, now + CAMPFIRE_LIFE_TICKS);
         return true;
      }
      if (now < until) {
         return true;
      }
      try {
         level.setBlock(at, state.setValue(net.minecraft.world.level.block.CampfireBlock.LIT, false), 3);
         level.playSound(null, at, net.minecraft.sounds.SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 1.0F, 1.0F);
      } catch (Throwable ignored) {
      }
      CAMPFIRE_EMBERS.remove(at);
      return false;
   }

   /**
    * Takes the storm's frost off everybody it was holding, and forgets them.
    *
    * <p>Called from every path that ends the event, including the operator's. The figure is
    * checked before it is cleared so a body that caught fire, or found powder snow, or was
    * frozen by the Snow Queen in the second the event ended keeps what it has - this is a
    * teardown for one specific cause, not a blanket amnesty.
    */
   private static void thawStormFrost(MinecraftServer server) {
      for (java.util.UUID id : SNOW_FROZEN) {
         ServerPlayer p = server.getPlayerList().getPlayer(id);
         if (p != null && p.getTicksFrozen() >= SNOWSTORM_FROZEN_TICKS) {
            p.setTicksFrozen(0);
         }
      }
      SNOW_FROZEN.clear();
   }

   /**
    * Takes back whatever sky an event left over its world.
    *
    * <p>Called on load, when an event ends, and on a slow tick while nothing owns the sky -
    * because "the event is over" has more than one way of happening and only two of them run
    * any ending code. A server that stops mid-event comes back with the event already cleared
    * by {@link #load} and its sky still applied; the level's rain level is the reason this
    * matters. It is <b>not</b> a private render value: {@code ServerLevel.advanceWeatherCycle}
    * reads it every weather tick and sends it to every client as a {@code RAIN_LEVEL_CHANGE},
    * which is exactly how a rain level set once keeps raining until something sets it back.
    *
    * <p>The storm no longer sets a sky at all - it draws its own snow, because a rain level is
    * rain - so what this mostly does is undo skies a previous version left behind, and keep the
    * eclipse's dark sky from outliving the eclipse.
    */
   private static void clearEventSkies(MinecraftServer server) {
      for (ServerLevel level : server.getAllLevels()) {
         try {
            if (!SOLAR_ECLIPSE.equals(activeEvent)) {
               clearEclipseSky(level);
            }
            if (level.canHaveWeather()) {
               level.setRainLevel(0.0F);
               level.setThunderLevel(0.0F);
            }
         } catch (Throwable ignored) {
         }
      }
   }

   /**
    * The storm's own snow, drawn by the mod because vanilla weather cannot do it.
    *
    * <p>Two reasons it has to be ours. Vanilla will not snow in a warm biome at all, and the
    * event is "it snows everywhere" - and the alternative, driving the level's rain level, is
    * rain: the server sends that figure to every client, so the storm spent ten minutes
    * looking like a wet afternoon in the one event that is supposed to be snow.
    *
    * <p>Batched on purpose. One call carrying a count is one packet however large the count is,
    * so twenty flakes are drawn for the price of one send, and the storm is thicker than the
    * fourteen single-particle packets it replaces while costing a third of the bandwidth.
    */
   private static void snowstormFlakes(MinecraftServer server, long now) {
      if (now % SNOWSTORM_FLAKE_EVERY != 0L) {
         return;
      }
      for (ServerPlayer p : server.getPlayerList().getPlayers()) {
         try {
            if (!(p.level() instanceof ServerLevel level) || !stormLevel(level)) {
               continue;
            }
            // The wind, so the storm has a direction instead of falling straight down. It is
            // re-rolled every ten seconds and every layer is offset along it, which is what
            // turns four separate blankets of particles into one blizzard blowing somewhere.
            if (now % 200L == 0L) {
               STORM_WIND_ANGLE = RANDOM.nextDouble() * Math.PI * 2.0;
            }
            double wx = Math.cos(STORM_WIND_ANGLE) * 5.0;
            double wz = Math.sin(STORM_WIND_ANGLE) * 5.0;
            double x = p.getX();
            double y = p.getY() + 6.0;
            double z = p.getZ();
            boolean subzero = subzeroAt(level, p);

            // The main fall, twice as thick as it was. One send carries the count, so this is
            // the same one packet for nearly double the flakes.
            level.sendParticles(
               ParticleTypes.SNOWFLAKE, x + wx * 0.5, y, z + wz * 0.5, SNOWSTORM_FLAKE_COUNT, SNOWSTORM_FLAKE_SPREAD, 5.0, SNOWSTORM_FLAKE_SPREAD, 0.02
            );
            // A subzero area gets a second, denser blanket on top of the same one packet, so
            // the worst of the storm is also the one you can see: white-out where the cold is
            // instantly lethal, ordinary snowfall everywhere else.
            if (subzero) {
               level.sendParticles(
                  ParticleTypes.SNOWFLAKE,
                  x + wx * 0.25,
                  y + 2.0,
                  z + wz * 0.25,
                  SNOWSTORM_FLAKE_COUNT * 2,
                  SNOWSTORM_FLAKE_SPREAD + 4.0,
                  4.0,
                  SNOWSTORM_FLAKE_SPREAD + 4.0,
                  0.04
               );
               level.sendParticles(
                  ParticleTypes.WHITE_ASH, x, y - 1.0, z, SNOWSTORM_FLAKE_COUNT, SNOWSTORM_FLAKE_SPREAD, 2.0, SNOWSTORM_FLAKE_SPREAD, 0.01
               );
            }
            // Fine ash above it: the layer that reads as distance, and the only one that looks
            // like driving snow rather than falling snow. Every other cycle now.
            if (now % (SNOWSTORM_FLAKE_EVERY * 2L) == 0L) {
               level.sendParticles(
                  ParticleTypes.WHITE_ASH,
                  x + wx,
                  y + 6.0,
                  z + wz,
                  SNOWSTORM_FLAKE_COUNT,
                  SNOWSTORM_FLAKE_SPREAD + 5.0,
                  8.0,
                  SNOWSTORM_FLAKE_SPREAD + 5.0,
                  0.01
               );
            }
            // A ground layer just above the feet: drifting snow along the floor, which is what
            // makes the storm feel like weather in the room rather than a filter on the screen.
            if (now % (SNOWSTORM_FLAKE_EVERY * 4L) == 0L) {
               level.sendParticles(
                  ParticleTypes.SNOWFLAKE,
                  x + wx,
                  p.getY() + 0.4,
                  z + wz,
                  SNOWSTORM_FLAKE_COUNT,
                  SNOWSTORM_FLAKE_SPREAD,
                  0.6,
                  SNOWSTORM_FLAKE_SPREAD,
                  0.06
               );
            }
            // And a gust every three seconds, a wall of flakes arriving from upwind. Batched,
            // so the whole burst is one packet.
            if (now % 60L == 0L) {
               level.sendParticles(
                  ParticleTypes.SNOWFLAKE,
                  x - wx * 3.0,
                  p.getY() + 3.0,
                  z - wz * 3.0,
                  SNOWSTORM_FLAKE_COUNT * 2,
                  SNOWSTORM_FLAKE_SPREAD * 0.8,
                  3.5,
                  SNOWSTORM_FLAKE_SPREAD * 0.8,
                  0.35
               );
               level.sendParticles(ParticleTypes.WHITE_ASH, x - wx * 3.0, p.getY() + 2.0, z - wz * 3.0, 12, 6.0, 2.0, 6.0, 0.25);
            }
         } catch (Throwable ignored) {
         }
      }
   }

   // ------------------------------------------------- the ground the storm leaves

   /**
    * Snow the storm has laid, and the air it was laid on.
    *
    * <p>Remembered rather than assumed, because "it snows everywhere" has to be reversible:
    * a snowstorm that leaves a permanent white sheet over somebody's farm is not weather, it
    * is a grief, and the whole point of the event is that it passes. Every position is written
    * down with what was there before, in memory and on disk, so the end of the event and a
    * server that died during it are the same operation - exactly the rule the Mirage Castle's
    * undo list follows, for exactly the same reason.
    */
   private static final Map<CoverKey, BlockState> STORM_SNOW = new LinkedHashMap<>();
   /** Water the storm iced over, and the water it was. */
   private static final Map<CoverKey, BlockState> STORM_ICE = new LinkedHashMap<>();

   /**
    * One piece of the storm's cover: which dimension it is in, where, and what stood there.
    *
    * <p>The dimension is part of the key because a position is not an address on its own - the
    * same x,y,z exists in every level - so a record without its level is restored into whichever
    * level a loop happened to be holding when it reached the entry, which is not the same thing
    * as the level it was placed in.
    */
   private static record CoverKey(ResourceKey<Level> dimension, BlockPos pos) {
   }

   /** When to try the cover the last clear could not reach. See {@link #restoreCover}. */
   private static long STORM_COVER_RETRY_AT = 0L;
   /** How long between attempts to finish cover in chunks nobody has open. */
   private static final int STORM_COVER_RETRY_TICKS = 100;
   /** Chest-like blocks the storm has found, which it then leaves alone. */
   private static final Map<BlockPos, Long> STORM_CHESTS = new HashMap<>();
   /** Everywhere the storm will not lay ground cover: chests, the world spawn, and beds. */
   private static final List<BlockPos> STORM_KEEP_CLEAR_AT = new ArrayList<>();
   private static long STORM_KEEP_CLEAR_READ = 0L;
   /** Per-mob cold, so a mob freezes on the same clock a player does. */
   private static final Map<java.util.UUID, Integer> SNOW_MOB_CHILL = new HashMap<>();
   private static final Map<java.util.UUID, Long> SNOW_MOB_FROZEN = new HashMap<>();
   /** Which way the storm is blowing, in radians. */
   private static double STORM_WIND_ANGLE = 0.0;
   private static final String SNOW_COVER_FILE = "snowstorm_cover.json";
   /** How far around a chest or a spawn point the storm will not touch the ground. */
   private static final int SNOWSTORM_KEEP_CLEAR = 50;
   /** How far from a player the storm lays ground cover. */
   private static final int SNOWSTORM_COVER_RADIUS = 18;
   /** Ground cover laid per pass, across every player in every level. */
   private static final int SNOWSTORM_COVER_BUDGET = 240;
   /** A mob out in the open for this long starts to freeze, as a player does. */
   private static final int SNOWSTORM_MOB_GRACE_TICKS = 20 * 15;

   /**
    * The dimensions the storm exists in.
    *
    * <p>The Nether is the one place snow cannot be: it has no sky to snow from, water does not
    * survive there, and a blizzard in a lava sea is not an event, it is a bug report. Its
    * players are untouched, and nothing is placed in it.
    */
   private static boolean stormLevel(ServerLevel level) {
      return level != null && !level.dimension().equals(Level.NETHER);
   }

   /**
    * Lays the storm's ground cover and takes it back afterwards.
    *
    * <p>Two things happen to the world, in one pass and under one rule. Snow settles on the
    * surface, so the event can be seen with the sky turned off; and standing water freezes
    * over, which is what a snowstorm does to a lake and is the only part of the event that
    * changes what a body can walk on. Both are remembered, both are restored.
    *
    * <p>Three places are left alone, and they are the three that matter: anywhere within
    * {@link #SNOWSTORM_KEEP_CLEAR} blocks of a chest, of the world spawn, or of a player's own
    * bed - so a base is not buried, a chest is not hidden under a slab of snow, and a spawn
    * point is not turned into a white field while its owner is asleep in it.
    */
   private static void stormCover(MinecraftServer server, long now) {
      try {
         int budget = SNOWSTORM_COVER_BUDGET;
         boolean touched = false;
         for (ServerLevel level : server.getAllLevels()) {
            if (!stormLevel(level) || !level.canHaveWeather()) {
               continue;
            }
            for (ServerPlayer p : level.getPlayers(pl -> pl.isAlive() && !pl.isSpectator())) {
               if (budget <= 0) {
                  break;
               }
               scanForChests(level, p, now);
               budget = coverAround(server, level, p, now, budget);
               touched = true;
            }
         }
         if (touched && now % 100L == 0L) {
            saveCover(server);
         }
      } catch (Throwable ignored) {
      }
   }

   private static int coverAround(MinecraftServer server, ServerLevel level, ServerPlayer p, long now, int budget) {
      List<BlockPos> keepClear = keepClearCentres(server, level, now);
      int r = SNOWSTORM_COVER_RADIUS;
      BlockPos base = p.blockPosition();

      for (int dx = -r; dx <= r && budget > 0; dx += 2) {
         for (int dz = -r; dz <= r && budget > 0; dz += 2) {
            if (dx * dx + dz * dz > r * r) {
               continue;
            }
            int x = base.getX() + dx;
            int z = base.getZ() + dz;
            if (!level.isLoaded(new BlockPos(x, base.getY(), z))) {
               continue;
            }

            int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
            BlockPos ground = new BlockPos(x, top - 1, z);
            if (nearKeepClear(keepClear, ground)) {
               continue;
            }

            BlockState below = level.getBlockState(ground);
            if (below.is(Blocks.WATER) && below.getFluidState().isSource()) {
               if (!STORM_ICE.containsKey(new CoverKey(level.dimension(), ground))) {
                  STORM_ICE.put(new CoverKey(level.dimension(), ground), below);
                  level.setBlock(ground, Blocks.ICE.defaultBlockState(), 3);
                  level.sendParticles(ParticleTypes.SNOWFLAKE, x + 0.5, top + 0.2, z + 0.5, 2, 0.3, 0.1, 0.3, 0.01);
                  budget--;
               }
               continue;
            }

            BlockPos above = ground.above();
            BlockState at = level.getBlockState(above);
            if (at.isAir() && !below.is(Blocks.SNOW) && !below.is(Blocks.ICE)
               && below.isSolidRender() && !below.is(Blocks.LAVA)) {
               if (!STORM_SNOW.containsKey(new CoverKey(level.dimension(), above))) {
                  STORM_SNOW.put(new CoverKey(level.dimension(), above), at);
                  level.setBlock(above, Blocks.SNOW.defaultBlockState(), 3);
                  budget--;
               }
            }
         }
      }

      return budget;
   }

   /**
    * Finds the chests near a player, a few hundred columns at a time every five seconds.
    *
    * <p>Discovery has to be cheap, because the rule it feeds - "do not bury a chest" - needs a
    * fifty-block answer for every candidate column. Reading a column's whole height every tick
    * is what a lag spike is made of, so the search walks a coarse grid once every five seconds
    * and remembers what it found; the fifty-block test itself is then arithmetic against a
    * handful of positions.
    */
   private static void scanForChests(ServerLevel level, ServerPlayer p, long now) {
      if (now % 100L != 0L || now - STORM_CHEST_SCAN_AT < 100L) {
         return;
      }
      STORM_CHEST_SCAN_AT = now;
      int r = 24;
      BlockPos base = p.blockPosition();

      for (int dx = -r; dx <= r; dx += 3) {
         for (int dz = -r; dz <= r; dz += 3) {
            int x = base.getX() + dx;
            int z = base.getZ() + dz;
            if (!level.isLoaded(new BlockPos(x, base.getY(), z))) {
               continue;
            }
            int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
            for (int dy = -6; dy <= 3; dy++) {
               BlockPos q = new BlockPos(x, top + dy, z);
               if (isChestLike(level.getBlockState(q))) {
                  STORM_CHESTS.put(q, now);
                  break;
               }
            }
         }
      }
   }

   private static long STORM_CHEST_SCAN_AT = 0L;

   private static boolean isChestLike(BlockState state) {
      return state.is(Blocks.CHEST)
         || state.is(Blocks.TRAPPED_CHEST)
         || state.is(Blocks.BARREL)
         || state.is(Blocks.ENDER_CHEST)
         || BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath().contains("shulker_box");
   }

   /** Chests found so far, the world spawn, and every online player's own bed. */
   private static List<BlockPos> keepClearCentres(MinecraftServer server, ServerLevel level, long now) {
      if (now - STORM_KEEP_CLEAR_READ < 100L && !STORM_KEEP_CLEAR_AT.isEmpty()) {
         return STORM_KEEP_CLEAR_AT;
      }

      STORM_KEEP_CLEAR_AT.clear();
      STORM_KEEP_CLEAR_AT.addAll(STORM_CHESTS.keySet());
      // The world's own spawn, and every online player's own bed. Both are read through the
      // objects this version stores them in - a level keeps a respawn record and a player keeps
      // a respawn config - rather than from a helper that no longer exists.
      try {
         STORM_KEEP_CLEAR_AT.add(level.getRespawnData().pos());
      } catch (Throwable ignored) {
      }
      for (ServerPlayer other : server.getPlayerList().getPlayers()) {
         try {
            BlockPos bed = other.getRespawnConfig().respawnData().pos();
            if (bed != null) {
               STORM_KEEP_CLEAR_AT.add(bed);
            }
         } catch (Throwable ignored) {
         }
      }
      STORM_KEEP_CLEAR_READ = now;
      return STORM_KEEP_CLEAR_AT;
   }

   private static boolean nearKeepClear(List<BlockPos> centres, BlockPos pos) {
      int r = SNOWSTORM_KEEP_CLEAR;
      for (BlockPos centre : centres) {
         int dx = centre.getX() - pos.getX();
         int dz = centre.getZ() - pos.getZ();
         if (dx * dx + dz * dz <= r * r) {
            return true;
         }
      }
      return false;
   }

   /**
    * Mobs feel the storm too.
    *
    * <p>The event used to be a player-only rule, which made it a fact about the HUD rather than
    * about the world: a zombie could stand in the open all night, unfrozen, while the person
    * watching it shivered. Hostile mobs in the open now chill on the same clock, under the same
    * heat rule, and freeze the same way - so a base's lights protect the animals around it and
    * the things that come for them.
    */
   private static void stormMobs(MinecraftServer server, long now) {
      if (now % 10L != 0L) {
         return;
      }

      try {
         for (ServerLevel level : server.getAllLevels()) {
            if (!stormLevel(level) || !level.canHaveWeather()) {
               continue;
            }

            for (ServerPlayer p : level.getPlayers(pl -> pl.isAlive() && !pl.isSpectator())) {
               for (Mob mob : level.getEntitiesOfClass(
                  Mob.class, p.getBoundingBox().inflate(40.0), m -> m.isAlive() && m instanceof net.minecraft.world.entity.monster.Enemy
               )) {
                  java.util.UUID id = mob.getUUID();
                  BlockPos at = mob.blockPosition();
                  boolean sheltered = mob.getY() < level.getHeight(Heightmap.Types.MOTION_BLOCKING, at.getX(), at.getZ()) - SNOWSTORM_SHELTER_DEPTH;
                  double warmth = subzeroAt(level, at) ? SNOWSTORM_SUBZERO_RADIUS : SNOWSTORM_WARM_RADIUS;
                  if (sheltered || heatNearby(level, at, warmth) || mob.isInLava() || mob.isOnFire()) {
                     SNOW_MOB_CHILL.remove(id);
                     SNOW_MOB_FROZEN.remove(id);
                     if (mob.getTicksFrozen() > 0) {
                        mob.setTicksFrozen(0);
                     }
                     continue;
                  }

                  int chill = SNOW_MOB_CHILL.merge(id, 10, Integer::sum);
                  if (chill < SNOWSTORM_MOB_GRACE_TICKS) {
                     mob.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 40, 0, false, false));
                     continue;
                  }

                  mob.setTicksFrozen(Math.max(mob.getTicksFrozen(), SNOWSTORM_FROZEN_TICKS));
                  mob.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 40, 1, false, false));
                  SNOW_MOB_FROZEN.put(id, now);
                  if (now % 40L == 0L) {
                     mob.hurtServer(level, level.damageSources().freeze(), 1.0F);
                     level.sendParticles(ParticleTypes.SNOWFLAKE, mob.getX(), mob.getY() + 1.0, mob.getZ(), 6, 0.4, 0.5, 0.4, 0.02);
                  }
               }
            }
         }

         // Mobs come and go, and their cold is only worth remembering while they are alive:
         // a cap rather than a scan, so a busy world cannot grow the map without bound. A mob
         // dropped by the cap simply starts its fifteen seconds again.
         if (SNOW_MOB_CHILL.size() > 512) {
            SNOW_MOB_CHILL.clear();
         }
         if (SNOW_MOB_FROZEN.size() > 512) {
            SNOW_MOB_FROZEN.clear();
         }
      } catch (Throwable ignored) {
      }
   }

   /** The heat question for a position rather than a body: what a mob, or a snow block, can see. */
   private static boolean heatNearby(ServerLevel level, BlockPos base, double radius) {
      int r = (int)Math.ceil(radius);
      for (int dx = -r; dx <= r; dx++) {
         for (int dz = -r; dz <= r; dz++) {
            if (dx * dx + dz * dz > radius * radius) {
               continue;
            }
            for (int dy = -3; dy <= 2; dy++) {
               BlockPos at = base.offset(dx, dy, dz);
               BlockState state = level.getBlockState(at);
               if (state.is(Blocks.CAMPFIRE) || state.is(Blocks.SOUL_CAMPFIRE)) {
                  // The one heat that expires, and the clock starts when the storm finds it
                  // rather than when it was placed: a campfire lit an hour before the event is
                  // as fresh as one lit in it.
                  if (state.getValue(net.minecraft.world.level.block.CampfireBlock.LIT)
                     && campfireIsBurning(level, at, state, level.getGameTime())) {
                     return true;
                  }
                  continue;
               }
               if (isHeatSource(state)) {
                  return true;
               }
            }
         }
      }
      return false;
   }

   /**
    * Takes the ground back: every snow layer it laid, and every ice it made.
    *
    * <p>Called from the event's own ending, from an operator's, and on load when the storm is
    * not running - because "the event ended" has more than one way of happening and a server
    * that stopped mid-storm comes back with the event already cleared and a white field still
    * on the ground.
    *
    * <p>It takes back <b>what it can reach and keeps the rest</b>: see {@link #restoreCover} for
    * why that distinction is the difference between a cleanup and a note of one.
    */
   public static void clearStormCover(MinecraftServer server) {
      // A new storm starts with fresh fuel: embers belong to the event they burned in.
      CAMPFIRE_EMBERS.clear();
      int left = 0;

      try {
         left = restoreCover(server, STORM_SNOW, false) + restoreCover(server, STORM_ICE, true);
      } catch (Throwable ignored) {
      }

      STORM_CHESTS.clear();
      STORM_KEEP_CLEAR_AT.clear();
      STORM_KEEP_CLEAR_READ = 0L;
      SNOW_MOB_CHILL.clear();
      SNOW_MOB_FROZEN.clear();

      if (server == null) {
         return;
      }

      if (left > 0) {
         // Some of it is standing in a chunk nobody has open. The record is the only thing
         // that knows it is the storm's and not the world's, so it is written back and
         // retried - never dropped, which is what made the snow permanent.
         STORM_COVER_RETRY_AT = server.getTickCount() + STORM_COVER_RETRY_TICKS;
         saveCover(server);
      } else {
         STORM_COVER_RETRY_AT = 0L;
         deleteCoverFile(server);
      }
   }

   /**
    * Puts back every piece of cover it can reach, and returns how many it could not.
    *
    * <p>The storm lays cover within eighteen blocks of players, so the record is always about
    * chunks that were loaded a moment ago - and the event can end, or the server can stop,
    * while some of them are not loaded any more. That case used to be silently skipped and the
    * whole record then thrown away with the file deleted: the snow stayed on the ground with
    * nothing anywhere saying who had put it there. "The snow does not disappear after the
    * event ends" is that line, and it is the one thing an event is not allowed to do to a
    * world. What cannot be reached is counted, kept and retried by
    * {@link #finishStormCover}.
    *
    * <p>A position whose block is no longer the storm's - the player broke the snow, mined the
    * ice, put a torch on the spot - is spent: the world moved on, and the world's version wins.
    */
   private static int restoreCover(MinecraftServer server, Map<CoverKey, BlockState> cover, boolean ice) {
      int left = 0;
      Iterator<Map.Entry<CoverKey, BlockState>> it = cover.entrySet().iterator();

      while (it.hasNext()) {
         Map.Entry<CoverKey, BlockState> e = it.next();
         CoverKey key = e.getKey();
         ServerLevel level = server.getLevel(key.dimension());
         if (level == null) {
            // The dimension is gone from this server: there is nothing to put back and no
            // world holding it, so the record goes with it.
            it.remove();
            continue;
         }

         if (!level.isLoaded(key.pos())) {
            left++;
            continue;
         }

         if (level.getBlockState(key.pos()).is(ice ? Blocks.ICE : Blocks.SNOW)) {
            level.setBlock(key.pos(), e.getValue(), 3);
         }

         it.remove();
      }

      return left;
   }

   /**
    * The storm's cover, finished after the fact.
    *
    * <p>Runs every tick from the main module tick and does nothing at all unless the last clear
    * had to leave something behind; then, once every few seconds, it asks those chunks again.
    * A player who walks back into one of them gets the ground they left.
    */
   private static void finishStormCover(MinecraftServer server, long now) {
      try {
         if (STORM_SNOW.isEmpty() && STORM_ICE.isEmpty()) {
            return;
         }
         if (SNOWSTORM.equals(activeEvent) || now < STORM_COVER_RETRY_AT) {
            return;
         }
         clearStormCover(server);
      } catch (Throwable ignored) {
      }
   }

   /**
    * What is left of a storm that did not get to finish.
    *
    * <p>Called once on load. A cover file with no storm running behind it is a storm the server
    * did not survive: the blocks it wrote down are put back before anybody can see them, which
    * is the same promise the Mirage Castle makes with its plan file.
    */
   public static void sweepStormCoverOnLoad(MinecraftServer server) {
      Path file = coverFile(server);
      if (file == null || !Files.exists(file)) {
         return;
      }

      try {
         if (SNOWSTORM.equals(activeEvent)) {
            // Still running: the record is adopted rather than acted on, so the end of the
            // event can still take it back.
            JsonObject root = JsonUtil.readOrCreate(file, new JsonObject());
            readCoverMap(root, "snow", STORM_SNOW);
            readCoverMap(root, "ice", STORM_ICE);
            return;
         }

         // Not running: finish it. The clear decides for itself whether the file still has a job
         // (cover in chunks that are not loaded yet), so the file is not deleted here as well.
         clearStormCover(server);
      } catch (Throwable ignored) {
      }
   }

   private static void readCoverMap(JsonObject root, String key, Map<CoverKey, BlockState> into) {
      try {
         if (!root.has(key)) {
            return;
         }
         JsonArray arr = root.getAsJsonArray(key);
         for (int i = 0; i < arr.size(); i++) {
            JsonArray e = arr.get(i).getAsJsonArray();
            // Five fields is the current shape - dimension first. Four is a record written by a
            // build before the dimension was kept, which only had one level to mean.
            int o = e.size() >= 5 ? 1 : 0;
            ResourceKey<Level> dim = e.size() >= 5
               ? ResourceKey.create(Registries.DIMENSION, Identifier.parse(e.get(0).getAsString()))
               : Level.OVERWORLD;
            BlockPos pos = new BlockPos(e.get(o).getAsInt(), e.get(o + 1).getAsInt(), e.get(o + 2).getAsInt());
            BlockState state = BuiltInRegistries.BLOCK.get(Identifier.parse(e.get(o + 3).getAsString()))
               .map(h -> h.value().defaultBlockState())
               .orElse(Blocks.AIR.defaultBlockState());
            into.put(new CoverKey(dim, pos), state);
         }
      } catch (Throwable ignored) {
      }
   }

   private static void saveCover(MinecraftServer server) {
      Path file = coverFile(server);
      if (file == null) {
         return;
      }

      try {
         JsonObject root = new JsonObject();
         root.add("snow", writeCoverMap(STORM_SNOW));
         root.add("ice", writeCoverMap(STORM_ICE));
         JsonUtil.write(file, root);
      } catch (Throwable ignored) {
      }
   }

   private static JsonArray writeCoverMap(Map<CoverKey, BlockState> map) {
      JsonArray arr = new JsonArray();
      for (Map.Entry<CoverKey, BlockState> e : map.entrySet()) {
         JsonArray one = new JsonArray();
         one.add(e.getKey().dimension().identifier().toString());
         one.add(e.getKey().pos().getX());
         one.add(e.getKey().pos().getY());
         one.add(e.getKey().pos().getZ());
         one.add(BuiltInRegistries.BLOCK.getKey(e.getValue().getBlock()).toString());
         arr.add(one);
      }
      return arr;
   }

   /** Test hook: how many pieces of cover are still waiting to be taken back. */
   public static int stormCoverPending() {
      return STORM_SNOW.size() + STORM_ICE.size();
   }

   /**
    * Test hook: record one piece of cover exactly as the storm would, so a check can drive the
    * clear against a position it chose - one in a loaded chunk, and one in a chunk nobody has
    * open.
    */
   public static void stageStormCoverForTest(ServerLevel level, BlockPos pos, BlockState was, boolean ice) {
      (ice ? STORM_ICE : STORM_SNOW).put(new CoverKey(level.dimension(), pos), was);
   }

   /** Test hook: how long a meteor is in the air, and how high it starts. */
   public static int meteorFallTicks() {
      return METEOR_FALL_TICKS;
   }

   public static double meteorStartHeight() {
      return METEOR_START_HEIGHT;
   }

   /** Test hook: does the on-disk record of the storm's cover still exist? */
   public static boolean stormCoverFilePending(MinecraftServer server) {
      try {
         Path file = coverFile(server);
         return file != null && Files.exists(file);
      } catch (Throwable t) {
         return false;
      }
   }

   private static Path coverFile(MinecraftServer server) {
      try {
         return EconomyManager.getDataDir(server).resolve(SNOW_COVER_FILE);
      } catch (Throwable t) {
         return null;
      }
   }

   private static void deleteCoverFile(MinecraftServer server) {
      try {
         Path file = coverFile(server);
         if (file != null) {
            Files.deleteIfExists(file);
         }
      } catch (Throwable ignored) {
      }
   }

   /** Visual particle effects for the active event, centered on each player. */
   private static void eventParticles(ServerLevel level, ServerPlayer p) {
      String ev = activeEvent;
      if (ev.isEmpty()) return;

      double x = p.getX();
      double y = p.getY();
      double z = p.getZ();         if (ev.equals(BLOOD_MOON)) {
            // Red dust particles swirling around the player
            for (int i = 0; i < 6; i++) {
               double a = RANDOM.nextDouble() * Math.PI * 2.0;
               double r = 2.0 + RANDOM.nextDouble() * 3.0;
               level.sendParticles(ParticleTypes.CRIMSON_SPORE, x + Math.cos(a) * r, y + 1.0 + RANDOM.nextDouble() * 2.0, z + Math.sin(a) * r, 2, 0.2, 0.3, 0.2, 0.02);
            }
      } else if (ev.equals(SNOWSTORM)) {
         // Deliberately empty: the storm draws its own snow in
         // {@link #snowstormFlakes}, on its own cadence and batched into one packet per send.
         // Fourteen single-particle packets every thirty ticks is what this branch used to be,
         // and it was the storm's whole lag budget for a fifth of the flakes.
      } else if (ev.equals(MINING_COLLAPSE)) {
         // Falling stone particles
         for (int i = 0; i < 4; i++) {
            level.sendParticles(ParticleTypes.CRIT, x + (RANDOM.nextDouble() - 0.5) * 8, y + 4.0 + RANDOM.nextDouble() * 3.0, z + (RANDOM.nextDouble() - 0.5) * 8, 1, 0.5, 0.1, 0.5, 0.0);
         }
      } else if (ev.equals(MERCHANT_FESTIVAL)) {
         // Happy villager / heart particles (golden aura)
         for (int i = 0; i < 4; i++) {
            double a = RANDOM.nextDouble() * Math.PI * 2.0;
            double r = 1.5 + RANDOM.nextDouble() * 2.0;
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, x + Math.cos(a) * r, y + 1.5, z + Math.sin(a) * r, 1, 0.2, 0.2, 0.2, 0.02);
         }
         // Occasional note particle
         if (RANDOM.nextInt(3) == 0) {
            level.sendParticles(ParticleTypes.NOTE, x, y + 2.0, z, 1, 0.5, 0.3, 0.5, 1.0);
         }
      } else if (ev.equals(MONSTER_INVASION)) {
         // Angry villager + large smoke particles (ominous aura)
         for (int i = 0; i < 5; i++) {
            double a = RANDOM.nextDouble() * Math.PI * 2.0;
            double r = 3.0 + RANDOM.nextDouble() * 4.0;
            level.sendParticles(ParticleTypes.ANGRY_VILLAGER, x + Math.cos(a) * r, y + 1.5, z + Math.sin(a) * r, 1, 0.3, 0.3, 0.3, 0.02);
         }
         level.sendParticles(ParticleTypes.LARGE_SMOKE, x, y + 0.5, z, 3, 1.5, 0.5, 1.5, 0.01);
      } else if (ev.equals(GOLD_RUSH)) {
         // Sparkling gold particles
         for (int i = 0; i < 6; i++) {
            double a = RANDOM.nextDouble() * Math.PI * 2.0;
            double r = 1.0 + RANDOM.nextDouble() * 4.0;
            level.sendParticles(ParticleTypes.END_ROD, x + Math.cos(a) * r, y + 0.5 + RANDOM.nextDouble() * 3.0, z + Math.sin(a) * r, 1, 0.1, 0.1, 0.1, 0.03);
         }
      } else if (ev.equals(THUNDERSTORM)) {
         // Electric / enchant particles
         for (int i = 0; i < 4; i++) {
            level.sendParticles(ParticleTypes.ENCHANT, x + (RANDOM.nextDouble() - 0.5) * 6, y + 1.0 + RANDOM.nextDouble() * 3.0, z + (RANDOM.nextDouble() - 0.5) * 6, 2, 0.5, 0.5, 0.5, 0.05);
         }
         // Occasional lightning strike nearby
         if (RANDOM.nextInt(60) == 0) {
         int lx = (int)(x + (RANDOM.nextDouble() - 0.5) * 40);
         int lz = (int)(z + (RANDOM.nextDouble() - 0.5) * 40);
         net.minecraft.world.entity.Entity bolt = EntityTypes.LIGHTNING_BOLT.create(level, EntitySpawnReason.COMMAND);
         if (bolt != null) {
            bolt.setPos(lx + 0.5, level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, lx, lz), lz + 0.5);
            level.addFreshEntity(bolt);
         }
         }
      } else if (ev.equals(DOUBLE_TROUBLE)) {
         // Dragon breath + soul particles (intense, chaotic)
         for (int i = 0; i < 6; i++) {
            double a = RANDOM.nextDouble() * Math.PI * 2.0;
            double r = 2.0 + RANDOM.nextDouble() * 5.0;
            level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, x + Math.cos(a) * r, y + 1.0, z + Math.sin(a) * r, 2, 0.3, 0.4, 0.3, 0.02);
            level.sendParticles(ParticleTypes.SOUL, x + Math.cos(a) * r, y + 1.5, z + Math.sin(a) * r, 1, 0.1, 0.1, 0.1, 0.01);
         }
      } else if (ev.equals(XP_FRENZY)) {
         // Learning sparks: enchant glyphs spiralling upward in a tight spiral.
         double t = (level.getGameTime() % 60L) / 60.0 * Math.PI * 2.0;
         for (int i = 0; i < 3; i++) {
            double a = t + i * (Math.PI * 2.0 / 3.0);
            level.sendParticles(ParticleTypes.ENCHANT, x + Math.cos(a) * 1.4, y + 0.6 + i * 0.5, z + Math.sin(a) * 1.4, 3, 0.15, 0.2, 0.15, 0.02);
         }
         if (RANDOM.nextInt(4) == 0) {
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, x, y + 1.8, z, 2, 0.6, 0.4, 0.6, 0.0);
         }
      } else if (ev.equals(PHANTOM_SWARM)) {
         // Wing shimmer above the player, plus a low drift of ash.
         for (int i = 0; i < 5; i++) {
            double a = RANDOM.nextDouble() * Math.PI * 2.0;
            double r = 3.0 + RANDOM.nextDouble() * 4.0;
            level.sendParticles(ParticleTypes.WITCH, x + Math.cos(a) * r, y + 3.0 + RANDOM.nextDouble() * 2.0, z + Math.sin(a) * r, 1, 0.2, 0.2, 0.2, 0.01);
         }
         level.sendParticles(ParticleTypes.ASH, x, y + 1.0, z, 4, 3.0, 1.5, 3.0, 0.01);
      }
   }

   private static String randomEvent() {
      int roll = RANDOM.nextInt(100);
      // The peaceful one is rare on purpose: an aurora that happened as often as a blood
      // moon would stop being an evening somebody remembers. Aurora, 4%. Mirage, 8%.
      if (roll < 4) {
         return AURORA;
      }
      if (roll < 12) {
         return MIRAGE_CASTLE;
      }
      return ROTATION[RANDOM.nextInt(ROTATION.length)];
   }

   /**
    * The event the rotation puts next, which is never one of the last few that ran.
    *
    * <p>A uniform roll can repeat, and twelve equally likely outcomes repeat about one time in
    * twelve - which a player reads as "that event again?" rather than as luck. The repeat is not
    * re-rolled repeatedly at random (that would only make a repeat unlikely rather than
    * impossible): the window is consulted first, and if the bounded draws cannot leave it the
    * rotation steps to the next slot outside the window instead. So "the same weather does not
    * come back while you can still remember it" is exact.
    */
   private static String nextEvent() {
      String rolled = randomEvent();
      // Bounded so a mis-sized window can never spin here. Twelve draws against a four-slot
      // window leaves it essentially always on the first try.
      for (int attempt = 0; attempt < 12 && RECENT_EVENTS.contains(rolled); attempt++) {
         rolled = randomEvent();
      }
      if (RECENT_EVENTS.contains(rolled)) {
         // Every draw landed inside the window. Walking the rotation always finds a slot
         // outside it, because the window is shorter than the rotation.
         int at = rotationIndex(lastEvent);
         for (int step = 1; step <= ROTATION.length; step++) {
            String candidate = ROTATION[(at + step) % ROTATION.length];
            if (!RECENT_EVENTS.contains(candidate)) {
               rolled = candidate;
               break;
            }
         }
      }
      remember(rolled);
      return rolled;
   }

   /** Files the event at the head of the recent window, oldest one out. */
   private static void remember(String event) {
      RECENT_EVENTS.addFirst(event);
      while (RECENT_EVENTS.size() > RECENT_MEMORY) {
         RECENT_EVENTS.removeLast();
      }
      lastEvent = event;
   }

   /** Test hook: the gap between automatic rolls, and the least a load can delay one. */
   public static long eventIntervalTicks() {
      return EVENT_INTERVAL_TICKS;
   }

   /** Test hook: reads a window back out of the packed string a save would have written. */
   public static void readRecentForTest(String packed, String previous) {
      JsonObject root = new JsonObject();
      root.addProperty("recent_events", packed);
      root.addProperty("last_event", previous);
      readRecent(root);
   }

   /** Test hook: how many events the anti-repeat window remembers. */
   public static int recentMemory() {
      return RECENT_MEMORY;
   }

   /** Test hook: the window, newest first. */
   public static java.util.List<String> recentForTest() {
      return new java.util.ArrayList<>(RECENT_EVENTS);
   }

   /** Test hook: empties the window, so a check starts from a known rotation. */
   public static void clearRecentForTest() {
      RECENT_EVENTS.clear();
      lastEvent = "";
   }

   /** Where an event sits in the rotation, or zero for the two rare ones that sit outside it. */
   private static int rotationIndex(String event) {
      for (int i = 0; i < ROTATION.length; i++) {
         if (ROTATION[i].equals(event)) {
            return i;
         }
      }
      return 0;
   }

   /** Test hook: one roll of the automatic rotation, through the real no-repeat path. */
   public static String nextEventForTest() {
      return nextEvent();
   }

   /** Admin-forced start of an event. Returns an error message, or null on success. */
   public static String startEvent(MinecraftServer server, String event) {
      if (server == null) {
         return "No server.";
      }
      String key = normalize(event);
      if (key == null) {
         return "Unknown event \"" + event + "\". Try: " + String.join(", ", eventNames()) + ".";
      }
      // Whatever was running before is taken out of the world first, so an operator
      // swapping events cannot strand a mirage or an eclipse behind them.
      if (MIRAGE_CASTLE.equals(activeEvent) && !MIRAGE_CASTLE.equals(key)) {
         MirageCastleManager.dismantle(server);
      }
      activeEvent = key;
      eventStartTick = server.getTickCount();
      eventUntil = server.getTickCount() + EVENT_DURATION_TICKS;
      cooldownUntil = server.getTickCount() + COOLDOWN_TICKS;

      if (SOLAR_ECLIPSE.equals(key)) {
         for (ServerLevel level : server.getAllLevels()) {
            darkenForEclipse(level);
         }
      }
      if (MIRAGE_CASTLE.equals(key)) {
         MirageCastleManager.raise(server);
      }
      if (SNOWSTORM.equals(key)) {
         SNOW_CHILL.clear();
         SNOW_HEAT.clear();
         SNOW_HEAT_AT.clear();
      }

      // Whoever is underground when the sky changes gets the wrong-place feat, and a
      // rare event records where it caught everybody so running from it can be measured.
      com.fortuneandfavors.economy.PlayerFeatTracker.onEventStarted(server, key);

      // Dramatic startup sequence: sounds + particles + title broadcast
      for (ServerPlayer p : server.getPlayerList().getPlayers()) {
         // Sound effect
         p.level().playSound(null, p.blockPosition(), SoundEvents.ENDER_DRAGON_GROWL, SoundSource.MASTER, 1.0F, 0.7F);
         p.level().playSound(null, p.blockPosition(), SoundEvents.WITHER_SPAWN, SoundSource.MASTER, 0.6F, 1.2F);

         // Burst of particles around the player
         ServerLevel lvl = (ServerLevel) p.level();
         if (lvl != null) {
            for (int i = 0; i < 30; i++) {
               double a = RANDOM.nextDouble() * Math.PI * 2.0;
               double r = 2.0 + RANDOM.nextDouble() * 5.0;
               lvl.sendParticles(ParticleTypes.PORTAL, p.getX() + Math.cos(a) * r, p.getY() + 1.0, p.getZ() + Math.sin(a) * r, 3, 0.5, 1.0, 0.5, 0.3);
            }
            // Type-specific startup particles
            if (key.equals(BLOOD_MOON)) {
               lvl.sendParticles(ParticleTypes.CRIMSON_SPORE, p.getX(), p.getY() + 1, p.getZ(), 40, 2.0, 1.5, 2.0, 0.1);
            } else if (key.equals(GOLD_RUSH)) {
               lvl.sendParticles(ParticleTypes.END_ROD, p.getX(), p.getY() + 1, p.getZ(), 40, 2.0, 1.5, 2.0, 0.1);
            } else if (key.equals(MONSTER_INVASION) || key.equals(DOUBLE_TROUBLE)) {
               lvl.sendParticles(ParticleTypes.LARGE_SMOKE, p.getX(), p.getY() + 0.5, p.getZ(), 20, 3.0, 0.5, 3.0, 0.05);
               lvl.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, p.getX(), p.getY() + 1.5, p.getZ(), 15, 2.0, 1.0, 2.0, 0.05);
            } else if (key.equals(MERCHANT_FESTIVAL)) {
               lvl.sendParticles(ParticleTypes.HAPPY_VILLAGER, p.getX(), p.getY() + 1, p.getZ(), 30, 2.0, 1.5, 2.0, 0.05);
               lvl.sendParticles(ParticleTypes.NOTE, p.getX(), p.getY() + 2, p.getZ(), 10, 1.5, 0.5, 1.5, 1.0);
            } else if (key.equals(THUNDERSTORM)) {
               lvl.sendParticles(ParticleTypes.ENCHANT, p.getX(), p.getY() + 1, p.getZ(), 40, 2.0, 2.0, 2.0, 0.1);
            } else if (key.equals(XP_FRENZY)) {
               lvl.sendParticles(ParticleTypes.ENCHANT, p.getX(), p.getY() + 1, p.getZ(), 60, 1.5, 1.5, 1.5, 0.4);
               lvl.sendParticles(ParticleTypes.HAPPY_VILLAGER, p.getX(), p.getY() + 1, p.getZ(), 20, 2.0, 1.5, 2.0, 0.05);
            } else if (key.equals(PHANTOM_SWARM)) {
               lvl.sendParticles(ParticleTypes.WITCH, p.getX(), p.getY() + 2, p.getZ(), 40, 3.0, 2.0, 3.0, 0.05);
               lvl.sendParticles(ParticleTypes.ASH, p.getX(), p.getY() + 1, p.getZ(), 50, 3.0, 1.0, 3.0, 0.02);
            }
         }
      }

      // Multi-line broadcast with decorative borders
      broadcast(server, "§8§m═══════════════════════════════");
      broadcast(server, "");
      broadcast(server, "    " + eventColor(key) + "§l⚡ " + displayName(key) + " " + eventColor(key) + "§l⚡");
      broadcast(server, "    §7" + eventHint(key));
      broadcast(server, "    §8Duration: §f10 minutes§8 · " + eventDurationHint(key));
      broadcast(server, "");
      broadcast(server, "§8§m═══════════════════════════════");
      save(server);
      return null;
   }

   /** Admin-forced stop of the current event. Returns false if none was active. */
   public static boolean stopEvent(MinecraftServer server) {
      if (activeEvent.isEmpty()) {
         return false;
      }
      String ended = activeEvent;
      activeEvent = "";

      if (MIRAGE_CASTLE.equals(ended)) {
         MirageCastleManager.dismantle(server);
      }
      if (SOLAR_ECLIPSE.equals(ended)) {
         for (ServerLevel level : server.getAllLevels()) {
            clearEclipseSky(level);
         }
      }
      if (SNOWSTORM.equals(ended)) {
         SNOW_CHILL.clear();
         SNOW_HEAT.clear();
         SNOW_HEAT_AT.clear();
         thawStormFrost(server);
      }
      // The sky goes back for every ending path, not only the one that ran out of time.
      clearEventSkies(server);

      // Ending effects
      for (ServerPlayer p : server.getPlayerList().getPlayers()) {
         p.level().playSound(null, p.blockPosition(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.MASTER, 0.8F, 1.0F);
         p.level().sendParticles(ParticleTypes.FIREWORK, p.getX(), p.getY() + 1, p.getZ(), 30, 1.0, 1.0, 1.0, 0.1);
      }

      broadcast(server, "§8§m──────────────────────────§r");
      broadcast(server, "  §7The " + displayName(ended) + "§7 has been called off.");
      broadcast(server, "§8§m──────────────────────────§r");
      save(server);
      return true;
   }

   private static String normalize(String raw) {
      if (raw == null) {
         return null;
      }
      String s = raw.trim().toLowerCase().replace(' ', '_').replace("-", "_");
      if (s.equals("bloodmoon") || s.equals("moon")) {
         s = BLOOD_MOON;
      } else if (s.equals("mining") || s.equals("collapse")) {
         s = MINING_COLLAPSE;
      } else if (s.equals("festival") || s.equals("merchant")) {
         s = MERCHANT_FESTIVAL;
      } else if (s.equals("invasion") || s.equals("monsters")) {
         s = MONSTER_INVASION;
      } else if (s.equals("gold") || s.equals("rush")) {
         s = GOLD_RUSH;
      } else if (s.equals("thunder") || s.equals("storm")) {
         s = THUNDERSTORM;
      } else if (s.equals("double") || s.equals("trouble")) {
         s = DOUBLE_TROUBLE;
      } else if (s.equals("xp") || s.equals("frenzy") || s.equals("xp_frenzy") || s.equals("learning")) {
         s = XP_FRENZY;
      } else if (s.equals("phantom") || s.equals("phantoms") || s.equals("swarm")) {
         s = PHANTOM_SWARM;
      } else if (s.equals("meteor") || s.equals("meteors") || s.equals("shower")) {
         s = METEOR_SHOWER;
      } else if (s.equals("eclipse") || s.equals("solar") || s.equals("dark")) {
         s = SOLAR_ECLIPSE;
      } else if (s.equals("aurora") || s.equals("borealis") || s.equals("lights")) {
         s = AURORA;
      } else if (s.equals("mirage") || s.equals("castle") || s.equals("mirage_castle")) {
         s = MIRAGE_CASTLE;
      } else if (s.equals("snow") || s.equals("snowstorm") || s.equals("blizzard") || s.equals("winter") || s.equals("cold")) {
         s = SNOWSTORM;
      }
      for (String e : eventNames()) {
         if (e.equals(s)) {
            return e;
         }
      }
      return null;
   }

   /** How long a body may be outside with no heat before it freezes, and how far heat reaches. */
   public static int snowstormGraceTicks() {
      return SNOWSTORM_GRACE_TICKS;
   }

   public static double snowstormWarmRadius() {
      return SNOWSTORM_WARM_RADIUS;
   }

   /** Vanilla's own full-freeze figure, which is the state the storm holds a body in. */
   public static int snowstormFrozenTicks() {
      return SNOWSTORM_FROZEN_TICKS;
   }

   /**
    * How far under the surface a body has to be to be out of the storm.
    *
    * <p>Pinned by the self-test, because the number carries the rule: it has to be deeper
    * than a house (or a roof is shelter, and the event can be slept through) and shallower
    * than a mine (or the event punishes mining, which is not what it is about).
    */
   public static int snowstormShelterDepth() {
      return SNOWSTORM_SHELTER_DEPTH;
   }

   /** How often a body's surroundings are searched for heat, in ticks. */
   public static int snowstormHeatRecheckTicks() {
      return SNOWSTORM_HEAT_RECHECK;
   }

   /** How often the storm draws a batch of its own snow, in ticks. */
   public static int snowstormFlakeEveryTicks() {
      return SNOWSTORM_FLAKE_EVERY;
   }

   /** How many flakes one batch carries - one packet, however many of them there are. */
   public static int snowstormFlakeCount() {
      return SNOWSTORM_FLAKE_COUNT;
   }

   /** Whether a body's frost should outlive the storm: it never should. */
   public static int snowstormChilled() {
      return SNOW_CHILL.size();
   }

   public static String[] eventNames() {
      return new String[]{
         BLOOD_MOON,
         MINING_COLLAPSE,
         MERCHANT_FESTIVAL,
         MONSTER_INVASION,
         GOLD_RUSH,
         THUNDERSTORM,
         DOUBLE_TROUBLE,
         XP_FRENZY,
         PHANTOM_SWARM,
         METEOR_SHOWER,
         SOLAR_ECLIPSE,
         AURORA,
         MIRAGE_CASTLE,
         SNOWSTORM
      };
   }

   public static String active() {
      return activeEvent;
   }

   /**
    * The two events that are rare on purpose: an aurora (4%) and a mirage castle (8%).
    *
    * <p>Public because "rare" is a claim the rest of the mod is allowed to act on -
    * the feats tracker measures somebody running from one - and a rarity that exists
    * only as a comment about two percentage rolls is not something anything else can
    * ask about.
    */
   public static boolean isRare(String event) {
      return AURORA.equals(event) || MIRAGE_CASTLE.equals(event);
   }

   public static boolean is(String event) {
      return activeEvent.equals(event);
   }

   public static boolean isActive() {
      return !activeEvent.isEmpty();
   }

   private static String displayName(String event) {
      return switch (event) {
         case BLOOD_MOON -> "§c§lBlood Moon";
         case MINING_COLLAPSE -> "§6§lMining Collapse";
         case MERCHANT_FESTIVAL -> "§e§lMerchant Festival";
         case MONSTER_INVASION -> "§4§lMonster Invasion";
         case GOLD_RUSH -> "§6§lGold Rush";
         case THUNDERSTORM -> "§b§lThunderstorm";
         case DOUBLE_TROUBLE -> "§5§lDouble Trouble";
         case XP_FRENZY -> "§a§lXP Frenzy";
         case PHANTOM_SWARM -> "§8§lPhantom Swarm";
         case METEOR_SHOWER -> "§6§l☄ Meteor Shower";
         case SOLAR_ECLIPSE -> "§0§l🌑 Solar Eclipse";
         case AURORA -> "§a§l🌌 Aurora";
         case MIRAGE_CASTLE -> "§d§lMirage Castle";
         case SNOWSTORM -> "§f§l❄ Snowstorm";
         default -> "Event";
      };
   }

   private static String eventColor(String event) {
      return switch (event) {
         case BLOOD_MOON -> "§c";
         case MINING_COLLAPSE -> "§6";
         case MERCHANT_FESTIVAL -> "§e";
         case MONSTER_INVASION -> "§4";
         case GOLD_RUSH -> "§6";
         case THUNDERSTORM -> "§b";
         case DOUBLE_TROUBLE -> "§5";
         case XP_FRENZY -> "§a";
         case PHANTOM_SWARM -> "§8";
         case METEOR_SHOWER -> "§6";
         case SOLAR_ECLIPSE -> "§5";
         case AURORA -> "§a";
         case MIRAGE_CASTLE -> "§d";
         case SNOWSTORM -> "§f";
         default -> "§7";
      };
   }

   private static String eventHint(String event) {
      return switch (event) {
         case BLOOD_MOON -> "Hostile mobs hit 50% harder and spawn more aggressively!";
         case MINING_COLLAPSE -> "Mining reveals double bonus ores everywhere!";
         case MERCHANT_FESTIVAL -> "Everything sells for 25% more cash!";
         case MONSTER_INVASION -> "Monsters are invading the overworld - defend your base!";
         case GOLD_RUSH -> "Ore blocks drop double items - mine while you can!";
         case THUNDERSTORM -> "Lightning storms engulf the world - charged creepers everywhere!";
         case DOUBLE_TROUBLE -> "Double mob spawns and double damage taken!";
         case XP_FRENZY -> "Every skill in the book earns double XP while it lasts!";
         case PHANTOM_SWARM -> "Phantoms descend on everyone - look up!";
         case METEOR_SHOWER -> "Meteors are falling - each crater holds loot. Race for it!";
         case SOLAR_ECLIPSE -> "The sun goes out and the monsters stop waiting for night.";
         case AURORA -> "The sky is lit from the north. Nothing hostile will come while it lasts.";
         case MIRAGE_CASTLE -> "A castle has appeared somewhere nearby - it will not be here for long.";
         case SNOWSTORM -> "It is snowing everywhere. Stay near a torch, a campfire or a fire - a roof is not shelter, and the cold does not care how far you have walked.";
         default -> "Something is happening...";
      };
   }

   private static String eventDurationHint(String event) {
      return switch (event) {
         case BLOOD_MOON -> "Mobs hit 50% harder";
         case MINING_COLLAPSE -> "2x mining pity";
         case MERCHANT_FESTIVAL -> "+25% sell prices";
         case MONSTER_INVASION -> "Mobs invading";
         case GOLD_RUSH -> "2x ore drops";
         case THUNDERSTORM -> "Lightning + charged creepers";
         case DOUBLE_TROUBLE -> "2x mobs, 2x damage taken";
         case XP_FRENZY -> "2x skill XP";
         case PHANTOM_SWARM -> "Phantoms stalk the skies";
         case METEOR_SHOWER -> "Craters + loot";
         case SOLAR_ECLIPSE -> "Daytime darkness, night spawning";
         case AURORA -> "No hostile spawns";
         case MIRAGE_CASTLE -> "A temporary castle";
         case SNOWSTORM -> "Cold - stay near heat, anywhere above ground";
         default -> "";
      };
   }

   public static double sellMultiplier() {
      return MERCHANT_FESTIVAL.equals(activeEvent) ? 1.25 : 1.0;
   }

   public static int miningPityMultiplier() {
      return MINING_COLLAPSE.equals(activeEvent) ? 2 : 1;
   }

   public static float mobDamageMultiplier() {
      return BLOOD_MOON.equals(activeEvent) || DOUBLE_TROUBLE.equals(activeEvent) ? 1.5F : 1.0F;
   }

   public static double mobCashMultiplier() {
      return DOUBLE_TROUBLE.equals(activeEvent) ? 2.0 : 1.0;
   }

   /** XP Frenzy doubles every skill-XP gain. Read from {@code SkillManager.addXp}.
    *  Kept as a double to match the other event multipliers. */
   public static double xpMultiplier() {
      return XP_FRENZY.equals(activeEvent) ? 2.0 : 1.0;
   }

   public static int oreDropMultiplier() {
      int mult = 1;
      if (GOLD_RUSH.equals(activeEvent)) mult *= 2;
      return mult;
   }

   public static boolean isThunderstormActive() {
      return THUNDERSTORM.equals(activeEvent);
   }

   /**
    * One meteor: a burning streak down from the sky, a crater where it lands, and loot
    * in the hole.
    *
    * <p>Aimed at a spot near a random player rather than at a random point on the map,
    * because the whole point of the event is that people can race to it - a meteor four
    * hundred blocks away from everybody is scenery. Two things are deliberately refused:
    * a landing spot inside somebody's claim, and one inside a duel realm. Nobody's build
    * is getting cratered by weather, which is also why the crater is carved by hand rather
    * than by {@code explode} (which would also fire the explosion-rebuild machinery and
    * quietly refill the hole).
    */
   private static void dropMeteor(MinecraftServer server) {
      java.util.List<ServerPlayer> online = server.getPlayerList().getPlayers();
      if (online.isEmpty()) {
         return;
      }

      ServerPlayer around = online.get(RANDOM.nextInt(online.size()));
      ServerLevel level = around.level();

      for (int attempt = 0; attempt < 8; attempt++) {
         double angle = RANDOM.nextDouble() * Math.PI * 2.0;
         double dist = 18.0 + RANDOM.nextDouble() * 42.0;
         int x = (int)Math.floor(around.getX() + Math.cos(angle) * dist);
         int z = (int)Math.floor(around.getZ() + Math.sin(angle) * dist);
         int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, x, z);
         BlockPos impact = new BlockPos(x, y - 1, z);

         if (y <= level.getMinY() + 2 || !level.isLoaded(impact)) {
            continue;
         }
         if (ClaimManager.claimAt(level, impact) != null || DuelManager.isDuelRealm(level)) {
            continue;
         }

         launch(level, impact);
         return;
      }
   }

   /**
    * Sends one down: a burning body of block that falls out of the sky, and a warning.
    *
    * <p>The fall is its own object rather than a loop of particles for three reasons, all of
    * which the old streak had wrong. It is a <b>body</b> - a block display two and a quarter
    * blocks across, on fire, with a tail - so a player looking up sees the thing that is
    * about to land on them and has time to move. The impact is a <b>real explosion</b>, so it
    * plays by the game's rules: radius, knockback, damage, and the capture that lets the
    * crater heal (see {@code ServerExplosionMixin}). And it is <b>announced</b>, which nothing
    * that falls out of the sky should have to be guessed at.
    */
   private static void launch(ServerLevel level, BlockPos impact) {
      Display.BlockDisplay body = (Display.BlockDisplay)EntityTypes.BLOCK_DISPLAY.create(level, EntitySpawnReason.COMMAND);
      if (body == null) {
         return;
      }

      double x = impact.getX() + 0.5;
      double z = impact.getZ() + 0.5;
      double from = impact.getY() + METEOR_START_HEIGHT;

      body.setBlockState(Blocks.MAGMA_BLOCK.defaultBlockState());
      body.setTransformation(
         new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(2.25F), new Quaternionf())
      );
      body.setPos(x, from, z);
      body.setNoGravity(true);
      body.setInvulnerable(true);
      body.addTag(METEOR_TAG);
      level.addFreshEntity(body);

      METEORS.add(new Meteor(level, new Vec3(x, from, z), new Vec3(x, impact.getY() + 0.2, z), METEOR_FALL_TICKS, body));
      level.playSound(null, x, from, z, SoundEvents.ELYTRA_FLYING, SoundSource.WEATHER, 2.0F, 0.5F);

      for (ServerPlayer p : level.getPlayers(pl -> pl.isAlive())) {
         Chat.raw(p, "§6§l☄ METEOR INBOUND§r §7- look up and move. It lands where it lands.");
      }
   }

   /** Test hooks: the self-test watches a meteor land on a shelf it built. Only it calls these. */
   public static boolean launchMeteorForTest(ServerLevel level, BlockPos impact) {
      launch(level, impact);
      return !METEORS.isEmpty();
   }

   public static void tickMeteorsForTest(MinecraftServer server) {
      tickMeteors(server);
   }

   public static int liveMeteors() {
      return METEORS.size();
   }

   /** One tick of every meteor in the air: the fall, the tail, and the end of it. */
   public static void tickMeteors(MinecraftServer server) {
      if (METEORS.isEmpty()) {
         return;
      }

      for (Iterator<Meteor> it = METEORS.iterator(); it.hasNext();) {
         Meteor m = it.next();
         m.age++;
         double t = Math.min(1.0, (double)m.age / Math.max(1, m.ticks));
         // Weight, not a lift: the first half of the fall is a slow drift and the last half
         // is the thing hitting the ground.
         double ease = t * t;
         double x = m.from.x + (m.to.x - m.from.x) * t;
         double z = m.from.z + (m.to.z - m.from.z) * t;
         double y = m.from.y + (m.to.y - m.from.y) * ease;

         if (m.body != null && m.body.isAlive()) {
            m.body.setPos(x, y, z);
         }

         m.level.sendParticles(ParticleTypes.FLAME, x, y, z, 14, 0.7, 0.7, 0.7, 0.06);
         m.level.sendParticles(ParticleTypes.LARGE_SMOKE, x, y, z, 8, 0.8, 0.8, 0.8, 0.03);
         m.level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, x, y, z, 6, 0.5, 0.5, 0.5, 0.04);

         if (m.age % 6 == 0) {
            m.level.sendParticles(ParticleTypes.EXPLOSION, x, y, z, 1, 0.1, 0.1, 0.1, 0.0);
            m.level.playSound(null, x, y, z, SoundEvents.FIREWORK_ROCKET_BLAST, SoundSource.WEATHER, 1.4F, 0.6F);
         }

         if (t >= 1.0) {
            meteorAt(m.level, BlockPos.containing(m.to), m.body);
            if (m.body != null) {
               m.body.discard();
            }
            it.remove();
         }
      }
   }

   /**
    * The impact: the blast, the light, and the loot.
    *
    * <p>A real explosion and not a hand-dug hole, which is the change that matters most here.
    * The hand-dug version refused to use {@code explode} because that machinery rebuilds what
    * it breaks - and a crater that heals is exactly what a weather event should do, since
    * nobody aimed it and nobody chose the spot. The explosion also carries what a meteor is
    * supposed to carry: knockback, damage, and a shape that follows the ground it lands on
    * rather than a perfect sphere floating in it.
    *
    * <p>What is <b>not</b> left to the explosion is the announcement, the shockwave, the column
    * of smoke, and the loot - all of which are still drawn by hand, in the order a player sees
    * them.
    */
   /**
    * What one crater is worth.
    *
    * <p>Cut to a third of what it was, and with the top of the table taken out. A shower drops an
    * impact every thirty seconds for ten minutes - twenty craters, not fifty - and the old table
    * paid two to four diamonds on one crater in three and a whole ancient debris on one crater in
    * four. That is a diamond-and-netherite faucet that nobody aimed and no player chose, aimed at
    * whoever happened to be standing nearest, which is the report underneath "the meteor loot is
    * too OP": the event was the best renewable source of the two rarest materials in the game.
    *
    * <p>What is left is iron, gold, coal and experience, with a single diamond in one crater in
    * five as the thing worth running for. It is still worth crossing the map for, and it is no
    * longer where netherite comes from.
    */
   private static ItemStack[] meteorLoot() {
      return new ItemStack[]{
         new ItemStack(Items.IRON_INGOT, 1 + RANDOM.nextInt(3)),
         new ItemStack(Items.GOLD_INGOT, 1 + RANDOM.nextInt(2)),
         RANDOM.nextInt(5) == 0 ? new ItemStack(Items.DIAMOND) : new ItemStack(Items.RAW_IRON, 2 + RANDOM.nextInt(2)),
         new ItemStack(Items.EXPERIENCE_BOTTLE, 1 + RANDOM.nextInt(2)),
         new ItemStack(Items.COAL, 2 + RANDOM.nextInt(3))
      };
   }

   /** Test hook: one roll of a crater's table. The self-test bounds what a shower can pay out. */
   public static java.util.List<ItemStack> meteorLootForTest() {
      return java.util.List.of(meteorLoot());
   }

   private static void meteorAt(ServerLevel level, BlockPos impact, Entity body) {
      double x = impact.getX() + 0.5;
      double y = impact.getY() + 0.5;
      double z = impact.getZ() + 0.5;

      // The blast, named by the falling body so it can be recognised afterwards: a meteor's
      // wreckage always rebuilds, whatever the global Explosion Rebuild setting says, because
      // nobody chose where this landed. The body is still alive here - it is taken out of the
      // world on the next line by the caller.
      try {
         level.explode(body, x, y, z, 6.0F, Level.ExplosionInteraction.TNT);
      } catch (Throwable ignored) {
         // An explosion that will not start is not a reason to lose the rest of the impact.
      }

      level.playSound(null, x, y, z, SoundEvents.GENERIC_EXPLODE, SoundSource.WEATHER, 2.0F, 0.5F);
      level.playSound(null, x, y, z, SoundEvents.DRAGON_FIREBALL_EXPLODE, SoundSource.WEATHER, 1.4F, 0.6F);
      level.playSound(null, x, y, z, SoundEvents.FIREWORK_ROCKET_LARGE_BLAST, SoundSource.WEATHER, 1.6F, 0.4F);
      level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, x, y + 1.0, z, 3, 1.5, 0.6, 1.5, 0.0);

      // The shockwave: rings on the ground and a column of it going up, both of which are
      // what a player on the other side of the hill actually notices.
      for (int ring = 0; ring < 3; ring++) {
         double r = 3.0 + ring * 3.0;
         for (int i = 0; i < 48; i++) {
            double angle = i / 48.0 * Math.PI * 2.0;
            level.sendParticles(
               ParticleTypes.LARGE_SMOKE, x + Math.cos(angle) * r, y + 0.3, z + Math.sin(angle) * r,
               1, 0.2, 0.1, 0.2, 0.01
            );
            if (ring == 0) {
               level.sendParticles(
                  ParticleTypes.FLAME, x + Math.cos(angle) * r, y + 0.3, z + Math.sin(angle) * r, 1, 0.1, 0.1, 0.1, 0.02
               );
            }
         }
      }

      for (int i = 0; i < 36; i++) {
         double t = i / 36.0;
         level.sendParticles(ParticleTypes.LARGE_SMOKE, x, y + t * 14.0, z, 3, 0.8, 0.5, 0.8, 0.02);
         level.sendParticles(ParticleTypes.FLAME, x, y + t * 10.0, z, 2, 0.6, 0.4, 0.6, 0.03);
      }

      // Loot, in the hole: what a meteor is worth walking for.
      for (ItemStack stack : meteorLoot()) {
         ItemEntity item = new ItemEntity(
            level, impact.getX() + 0.5, impact.getY() + 0.5, impact.getZ() + 0.5, stack
         );
         item.setDefaultPickUpDelay();
         level.addFreshEntity(item);
      }

      for (ServerPlayer p : level.getPlayers(pl -> pl.isAlive() && pl.distanceToSqr(impact.getX(), impact.getY(), impact.getZ()) < 40_000.0)) {
         Chat.raw(p, "§6☄ A meteor has landed nearby§7 - get there before someone else does.");
      }
   }

   /**
    * Aurora: light above every player, no hostile spawns anywhere.
    *
    * <p>The refusal half is in {@code NaturalSpawnerMixin} - this is only the sky. Green
    * and violet dust in slow curtains high up, drifting sideways, so a player who looks up
    * sees something that is obviously not the weather.
    */
   private static void auroraOver(ServerPlayer p) {
      ServerLevel level = p.level();
      double base = Math.min(level.getMaxY(), p.getY() + 26.0);

      for (int i = 0; i < 6; i++) {
         double x = p.getX() + (RANDOM.nextDouble() - 0.5) * 46.0;
         double z = p.getZ() + (RANDOM.nextDouble() - 0.5) * 46.0;
         double y = base + RANDOM.nextDouble() * 14.0;
         level.sendParticles(
            new net.minecraft.core.particles.DustParticleOptions(0x5CF2A0, 1.6F), x, y, z, 1, 2.4, 0.4, 2.4, 0.005
         );
         level.sendParticles(
            new net.minecraft.core.particles.DustParticleOptions(0xB47CF2, 1.2F), x, y - 2.0, z, 1, 2.0, 0.3, 2.0, 0.005
         );
      }
   }

   /**
    * "Noticeably darker during daytime", done with the game's own sky.
    *
    * <p>A thunderstorm is the only darkness Minecraft actually has for a daytime sky, and
    * it is exactly the effect wanted: the sun dims, the light level drops, and the world
    * reads as wrong. The rain is switched off on purpose - this is the dark, not a wet
    * afternoon - and with no rain there is no lightning either, so the eclipse is not also
    * a wildfire event. Thunder time is set to the event's own length so the sky does not
    * clear itself halfway through.
    */
   private static void darkenForEclipse(ServerLevel level) {
      WeatherData weather = level.getWeatherData();
      weather.setRaining(false);
      weather.setRainTime((int)Math.min(Integer.MAX_VALUE, EVENT_DURATION_TICKS));
      weather.setThundering(true);
      weather.setThunderTime((int)Math.min(Integer.MAX_VALUE, EVENT_DURATION_TICKS));
   }

   /** Puts the sky back to ordinary clear weather, if the eclipse was what darkened it. */
   private static void clearEclipseSky(ServerLevel level) {
      WeatherData weather = level.getWeatherData();
      if (weather.isThundering() && !weather.isRaining()) {
         weather.setThundering(false);
         weather.setThunderTime(6000);
         weather.setClearWeatherTime(6000);
      }
   }

   /** True while nothing hostile may spawn: read from the spawner's own rules. */
   public static boolean peaceActive() {
      return AURORA.equals(activeEvent);
   }

   /**
    * True while monsters ignore the daylight they normally wait out.
    *
    * <p>"Certain mobs spawn outside their normal conditions" is the eclipse's whole
    * mechanical half - a dark sky alone is only a mood. Read from the spawner, so it is the
    * game's own daylight rule that is relaxed rather than a second spawn system fighting it.
    */
   public static boolean nightRulesActive() {
      return SOLAR_ECLIPSE.equals(activeEvent);
   }

   private static void spawnInvasionWaves(MinecraftServer server) {
      for (ServerPlayer p : server.getPlayerList().getPlayers()) {
         if (RANDOM.nextInt(4) != 0) {
            continue;
         }
         ServerLevel level = p.level();
         if (level.getGameTime() % 400L == 0L && !level.getEntitiesOfClass(Monster.class, p.getBoundingBox().inflate(20.0)).isEmpty()) {
            continue;
         }
         int count = DOUBLE_TROUBLE.equals(activeEvent) ? 2 : 1;
         for (int c = 0; c < count; c++) {
            EntityType<? extends Mob> type = switch (RANDOM.nextInt(5)) {
               case 0 -> EntityTypes.ZOMBIE;
               case 1 -> EntityTypes.SKELETON;
               case 2 -> EntityTypes.SPIDER;
               case 3 -> EntityTypes.PILLAGER;
               default -> EntityTypes.CREEPER;
            };
            Mob mob = type.create(level, EntitySpawnReason.EVENT);
            if (mob != null) {
               double a = RANDOM.nextDouble() * Math.PI * 2.0;
               double dist = 14.0 + RANDOM.nextDouble() * 8.0;
               mob.setPos(p.getX() + Math.cos(a) * dist, p.getY(), p.getZ() + Math.sin(a) * dist);
               mob.setPersistenceRequired();
               AttributeInstance hp = mob.getAttribute(Attributes.MAX_HEALTH);
               if (hp != null) {
                  hp.setBaseValue(hp.getBaseValue() * 1.4);
               }
               mob.setHealth(mob.getMaxHealth());
               level.addFreshEntity(mob);
               // Particle effect on spawn
               level.sendParticles(ParticleTypes.SMOKE, mob.getX(), mob.getY() + 0.5, mob.getZ(), 8, 0.3, 0.5, 0.3, 0.02);
               RareMobVariantManager.apply(mob);
            }
         }
      }
   }

   private static void broadcast(MinecraftServer server, String message) {
      if (server == null) {
         return;
      }
      for (ServerPlayer p : server.getPlayerList().getPlayers()) {
         Chat.raw(p, message);
      }
   }

   /** Phantom Swarm: phantoms circle in above every player, with a few bats for
    *  texture. Capped so a full server can't turn one event into hundreds of
    *  entities - two phantoms per player every ten seconds is pressure, not a
    *  crash. Phantoms only spawn when the sky above is genuinely open. */
   private static void spawnPhantomSwarm(MinecraftServer server) {
      for (ServerPlayer p : server.getPlayerList().getPlayers()) {
         ServerLevel level = p.level();
         long mobCount = level.getEntitiesOfClass(Monster.class, p.getBoundingBox().inflate(24.0)).size();
         if (mobCount > 24L) {
            continue;
         }
         int phantoms = 1 + RANDOM.nextInt(2);
         for (int i = 0; i < phantoms; i++) {
            double a = RANDOM.nextDouble() * Math.PI * 2.0;
            double dist = 7.0 + RANDOM.nextDouble() * 6.0;
            double x = p.getX() + Math.cos(a) * dist;
            double z = p.getZ() + Math.sin(a) * dist;
            int surface = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, (int)x, (int)z);
            if (surface <= level.getMinY() + 1) {
               continue;
            }
            Mob phantom = EntityTypes.PHANTOM.create(level, EntitySpawnReason.EVENT);
            if (phantom != null) {
               phantom.setPos(x, Math.max(surface, p.getY()) + 6.0, z);
               phantom.setPersistenceRequired();
               level.addFreshEntity(phantom);
               level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, phantom.getX(), phantom.getY(), phantom.getZ(), 6, 0.3, 0.3, 0.3, 0.02);
               RareMobVariantManager.apply(phantom);
            }
         }
         if (RANDOM.nextInt(3) == 0) {
            Mob bat = EntityTypes.BAT.create(level, EntitySpawnReason.EVENT);
            if (bat != null) {
               bat.setPos(p.getX() + (RANDOM.nextDouble() - 0.5) * 8.0, p.getY() + 4.0, p.getZ() + (RANDOM.nextDouble() - 0.5) * 8.0);
               bat.setPersistenceRequired();
               level.addFreshEntity(bat);
            }
         }
      }
   }
}
