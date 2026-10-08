package com.fortuneandfavors.economy;

import com.fortuneandfavors.util.JsonUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Map.Entry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;

/**
 * The small, mundane feats: the achievements that are about what a player did rather
 * than what the mod did to them.
 *
 * <p>Bed counts and death-spot counts are the only things here that have to survive a
 * restart - a player sleeping in twenty beds needs the twenty remembered across
 * sessions, and dying in the same chunk five times is a running total, not a session's
 * score. Everything else is a live condition (half a heart, an event starting, the
 * sixty-second absurdity below) and is read off the world the tick it is true.
 *
 * <h2>How Did You Even Do That?</h2>
 * The one demanding one. It is a strict, ordered chain, and each beat only counts if it
 * is the next one expected - a blaze that lights you up starts the clock, and then a
 * player snipe from fifty blocks, a hit on a zombie while you are still burning, a
 * creeper blast, a shulker strike below half health, and finally a death by falling.
 * Sixty seconds, start to finish. Every beat is read from the real damage source, so
 * the feat is only ever awarded for the genuine sequence and never for a lucky guess at
 * the ordering.
 */
public final class PlayerFeatTracker {
   public static final String THAT_WAS_CLOSE = "that_was_close";
   public static final String WRONG_PLACE = "wrong_place";
   public static final String ABSOLUTELY_NOT = "absolutely_not";
   public static final String HOW_DID_YOU_EVEN_DO_THAT = "how_did_you_even_do_that";
   public static final String FREE_REAL_ESTATE = "free_real_estate";
   public static final String LOCAL_IDIOT = "local_idiot";

   /** Half a heart, in health points. */
   private static final float HALF_A_HEART = 1.0F;
   /** Beds, distinct by block position, that "Free Real Estate" asks for. */
   private static final int BEDS_FOR_REAL_ESTATE = 20;
   /** Deaths in one chunk that "Local Idiot" asks for. */
   private static final int DEATHS_FOR_LOCAL_IDIOT = 5;
   /** How far from where a rare event caught you counts as having run from it. */
   private static final double RARE_EVENT_ESCAPE = 96.0;
   /** The whole absurd chain has to happen inside sixty seconds. */
   private static final long COMBO_WINDOW_TICKS = 60L * 20L;
   /** How far away the sniper has to be for the second beat to count. */
   private static final double SNIPE_DISTANCE = 50.0;

   // The chain, as "the beat that has just landed". Each one is only reachable from the
   // one before it, which is what makes the ordering real rather than a checklist.
   private static final int WAIT_SNIPE = 1;
   private static final int WAIT_ZOMBIE = 2;
   private static final int WAIT_CREEPER = 3;
   private static final int WAIT_SHULKER = 4;
   private static final int WAIT_FALL = 5;

   /** Beds slept in, by block position, per player. Persisted. */
   private static final Map<UUID, Set<Long>> BEDS = new HashMap<>();
   /** Deaths per chunk ("dimension|chunkX|chunkZ"), per player. Persisted. */
   private static final Map<UUID, Map<String, Integer>> CHUNK_DEATHS = new HashMap<>();
   /** Live sixty-second chains. Not persisted: a restart ends the attempt. */
   private static final Map<UUID, Combo> COMBOS = new HashMap<>();
   /** Where a rare event caught each player, so running from it can be measured. */
   private static final Map<UUID, Escape> ESCAPES = new HashMap<>();
   private static Path dataFile;

   private PlayerFeatTracker() {
   }

   private static final class Combo {
      int step;
      long deadline;
   }

   private static final class Escape {
      final String event;
      final ServerLevel level;
      final double x;
      final double y;
      final double z;

      Escape(String event, ServerLevel level, double x, double y, double z) {
         this.event = event;
         this.level = level;
         this.x = x;
         this.y = y;
         this.z = z;
      }
   }

   // ------------------------------------------------------------- persistence

   public static void load(MinecraftServer server) {
      BEDS.clear();
      CHUNK_DEATHS.clear();
      // Live state belongs to the session that made it: a chain mid-flight or a rare event
      // somebody was running from is a fact about a world that has since been closed.
      COMBOS.clear();
      ESCAPES.clear();
      dataFile = EconomyManager.getDataDir(server).resolve("feats.json");
      JsonObject root = JsonUtil.readOrCreate(dataFile, new JsonObject());
      if (!root.has("players") || !root.get("players").isJsonObject()) {
         return;
      }
      for (Entry<String, JsonElement> e : root.getAsJsonObject("players").entrySet()) {
         try {
            UUID uuid = UUID.fromString(e.getKey());
            if (!e.getValue().isJsonObject()) {
               continue;
            }
            JsonObject row = e.getValue().getAsJsonObject();
            if (row.has("beds") && row.get("beds").isJsonArray()) {
               Set<Long> beds = new HashSet<>();
               for (JsonElement b : row.getAsJsonArray("beds")) {
                  beds.add(b.getAsLong());
               }
               BEDS.put(uuid, beds);
            }
            if (row.has("deaths") && row.get("deaths").isJsonObject()) {
               Map<String, Integer> deaths = new HashMap<>();
               for (Entry<String, JsonElement> d : row.getAsJsonObject("deaths").entrySet()) {
                  deaths.put(d.getKey(), d.getValue().getAsInt());
               }
               CHUNK_DEATHS.put(uuid, deaths);
            }
         } catch (Exception ignored) {
         }
      }
   }

   public static void save(MinecraftServer server) {
      if (dataFile == null) {
         dataFile = EconomyManager.getDataDir(server).resolve("feats.json");
      }
      JsonObject root = new JsonObject();
      JsonObject players = new JsonObject();
      Set<UUID> ids = new HashSet<>();
      ids.addAll(BEDS.keySet());
      ids.addAll(CHUNK_DEATHS.keySet());
      for (UUID uuid : ids) {
         JsonObject row = new JsonObject();
         Set<Long> beds = BEDS.get(uuid);
         if (beds != null && !beds.isEmpty()) {
            JsonArray arr = new JsonArray();
            for (long bed : beds) {
               arr.add(bed);
            }
            row.add("beds", arr);
         }
         Map<String, Integer> deaths = CHUNK_DEATHS.get(uuid);
         if (deaths != null && !deaths.isEmpty()) {
            JsonObject obj = new JsonObject();
            for (Entry<String, Integer> d : deaths.entrySet()) {
               obj.addProperty(d.getKey(), d.getValue());
            }
            row.add("deaths", obj);
         }
         if (row.size() > 0) {
            players.add(uuid.toString(), row);
         }
      }
      root.add("players", players);
      JsonUtil.write(dataFile, root);
   }

   // -------------------------------------------------------------------- tick

   /** Per-player tick: the conditions that are simply true or false right now. */
   public static void tick(ServerPlayer player) {
      if (player == null || !player.isAlive()) {
         return;
      }
      if (player.getHealth() <= HALF_A_HEART) {
         Advancements.grant(player, THAT_WAS_CLOSE);
      }
      if (player.isSleeping()) {
         noteBed(player);
      }
      tickRareEscape(player);
   }

   /** A bed is its block position: two beds in one room are two beds. */
   private static void noteBed(ServerPlayer player) {
      BlockPos bed = player.blockPosition();
      Set<Long> beds = BEDS.computeIfAbsent(player.getUUID(), k -> new HashSet<>());
      if (beds.add(bed.asLong()) && beds.size() >= BEDS_FOR_REAL_ESTATE) {
         Advancements.grant(player, FREE_REAL_ESTATE);
      }
   }

   /** Runs from a rare event, measured from where it caught you. */
   private static void tickRareEscape(ServerPlayer player) {
      Escape escape = ESCAPES.get(player.getUUID());
      if (escape == null) {
         return;
      }
      String active = ServerDisasterManager.active();
      if (!ServerDisasterManager.isRare(active) || !active.equals(escape.event)) {
         ESCAPES.remove(player.getUUID());
         return;
      }
      if (player.level() != escape.level) {
         ESCAPES.remove(player.getUUID());
         Advancements.grant(player, ABSOLUTELY_NOT);
         return;
      }
      double dx = player.getX() - escape.x;
      double dy = player.getY() - escape.y;
      double dz = player.getZ() - escape.z;
      if (dx * dx + dy * dy + dz * dz >= RARE_EVENT_ESCAPE * RARE_EVENT_ESCAPE) {
         ESCAPES.remove(player.getUUID());
         Advancements.grant(player, ABSOLUTELY_NOT);
      }
   }

   // ------------------------------------------------------------------ events

   /**
    * A world event just began.
    *
    * <p>Underground is read off the world rather than off a Y number: below sea level
    * <em>and</em> with no sky overhead is the only reading that is honest about being
    * in a cave when the sky changes.
    */
   public static void onEventStarted(MinecraftServer server, String event) {
      if (server == null || event == null) {
         return;
      }
      boolean rare = ServerDisasterManager.isRare(event);
      for (ServerPlayer p : server.getPlayerList().getPlayers()) {
         if (p.isSpectator()) {
            continue;
         }
         if (isUnderground(p)) {
            Advancements.grant(p, WRONG_PLACE);
         }
         if (rare) {
            ESCAPES.put(p.getUUID(), new Escape(event, p.level(), p.getX(), p.getY(), p.getZ()));
         }
      }
   }

   private static boolean isUnderground(ServerPlayer player) {
      if (!(player.level() instanceof ServerLevel level)) {
         return false;
      }
      return player.getY() < level.getSeaLevel() && !level.canSeeSky(player.blockPosition());
   }

   // ------------------------------------------------------------------ damage

   /**
    * Every landed blow, for the sixty-second chain.
    *
    * <p>Two shapes arrive here and both are needed: the victim being a player (the blaze,
    * the snipe, the creeper, the shulker) and the victim being a zombie (the player's own
    * hit, while on fire).
    */
   public static void onDamage(LivingEntity victim, DamageSource source, float taken) {
      if (victim == null || source == null) {
         return;
      }

      // The third beat: the player's own weapon lands on a zombie while they are burning.
      if (isZombie(victim) && source.getEntity() instanceof ServerPlayer attacker) {
         Combo playerCombo = liveCombo(attacker);
         if (playerCombo != null && playerCombo.step == WAIT_ZOMBIE && attacker.isOnFire()) {
            playerCombo.step = WAIT_CREEPER;
         }
      }

      if (!(victim instanceof ServerPlayer player)) {
         return;
      }

      // The first beat opens the chain (and restarts it, if a blaze catches you again).
      if (isBlazeFire(source, player)) {
         Combo fresh = new Combo();
         fresh.step = WAIT_SNIPE;
         fresh.deadline = player.level().getGameTime() + COMBO_WINDOW_TICKS;
         COMBOS.put(player.getUUID(), fresh);
         return;
      }

      Combo combo = liveCombo(player);
      if (combo == null) {
         return;
      }
      switch (combo.step) {
         case WAIT_SNIPE -> {
            if (isPlayerSnipe(source, player)) {
               combo.step = WAIT_ZOMBIE;
            }
         }
         case WAIT_CREEPER -> {
            if (isCreeperBlast(source)) {
               combo.step = WAIT_SHULKER;
            }
         }
         case WAIT_SHULKER -> {
            if (isShulkerStrike(source) && player.getHealth() <= player.getMaxHealth() * 0.5F) {
               combo.step = WAIT_FALL;
            }
         }
         default -> {
         }
      }
   }

   /** The chain, if it is still running; an expired one is dropped here. */
   private static Combo liveCombo(ServerPlayer player) {
      Combo combo = COMBOS.get(player.getUUID());
      if (combo == null) {
         return null;
      }
      if (player.level().getGameTime() > combo.deadline) {
         COMBOS.remove(player.getUUID());
         return null;
      }
      return combo;
   }

   private static boolean isBlazeFire(DamageSource source, ServerPlayer player) {
      Entity owner = source.getEntity();
      return owner != null
         && owner.getType() == EntityTypes.BLAZE
         && (source.is(DamageTypeTags.IS_FIRE) || player.isOnFire());
   }

   private static boolean isPlayerSnipe(DamageSource source, ServerPlayer victim) {
      if (!(source.getDirectEntity() instanceof Projectile)) {
         return false;
      }
      if (!(source.getEntity() instanceof ServerPlayer shooter) || shooter == victim) {
         return false;
      }
      return shooter.distanceTo(victim) >= SNIPE_DISTANCE;
   }

   private static boolean isCreeperBlast(DamageSource source) {
      Entity owner = source.getEntity();
      return owner != null && owner.getType() == EntityTypes.CREEPER && source.is(DamageTypeTags.IS_EXPLOSION);
   }

   private static boolean isShulkerStrike(DamageSource source) {
      Entity owner = source.getEntity();
      return owner != null && owner.getType() == EntityTypes.SHULKER;
   }

   private static boolean isZombie(LivingEntity victim) {
      return victim.getType() == EntityTypes.ZOMBIE
         || victim.getType() == EntityTypes.HUSK
         || victim.getType() == EntityTypes.DROWNED;
   }

   // ------------------------------------------------------------------- death

   public static void onPlayerDeath(ServerPlayer player, DamageSource source) {
      if (player == null) {
         return;
      }
      ESCAPES.remove(player.getUUID());

      // The last beat of the chain is the death itself, so the chain is spent here
      // whether or not it completes.
      Combo combo = COMBOS.remove(player.getUUID());
      if (combo != null
         && combo.step == WAIT_FALL
         && source != null
         && source.is(DamageTypeTags.IS_FALL)
         && player.level().getGameTime() <= combo.deadline) {
         Advancements.grant(player, HOW_DID_YOU_EVEN_DO_THAT);
      }

      BlockPos at = player.blockPosition();
      String key = player.level().dimension().identifier() + "|" + (at.getX() >> 4) + "|" + (at.getZ() >> 4);
      Map<String, Integer> deaths = CHUNK_DEATHS.computeIfAbsent(player.getUUID(), k -> new HashMap<>());
      if (deaths.merge(key, 1, Integer::sum) >= DEATHS_FOR_LOCAL_IDIOT) {
         Advancements.grant(player, LOCAL_IDIOT);
      }
   }

   // ------------------------------------------------------------- test hooks

   /** The window the absurd chain has to fit inside. */
   public static long comboWindowTicks() {
      return COMBO_WINDOW_TICKS;
   }

   /** How far from a rare event counts as running away from it. */
   public static double rareEventEscape() {
      return RARE_EVENT_ESCAPE;
   }

   /** How far away a sniper has to be. */
   public static double snipeDistance() {
      return SNIPE_DISTANCE;
   }

   /** How many beds "Free Real Estate" wants. */
   public static int bedsForRealEstate() {
      return BEDS_FOR_REAL_ESTATE;
   }

   /** How many deaths in one chunk "Local Idiot" wants. */
   public static int deathsForLocalIdiot() {
      return DEATHS_FOR_LOCAL_IDIOT;
   }
}
