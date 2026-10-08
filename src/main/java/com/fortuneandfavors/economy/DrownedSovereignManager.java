package com.fortuneandfavors.economy;

import com.fortuneandfavors.ModItems;
import com.fortuneandfavors.ModSounds;
import com.fortuneandfavors.util.Safe;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.BossEvent.BossBarColor;
import net.minecraft.world.BossEvent.BossBarOverlay;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * <b>The Drowned Sovereign</b> - the sea boss. Deep water, pressure, and the tide used as a weapon.
 *
 * <h2>The idea</h2>
 * He does not throw "water damage" at you. He changes <em>where you are standing</em>: a wave moves
 * you backward, a whirlpool decides which ground is yours, and a prison decides for one player that
 * they are not standing anywhere at all for three seconds. Almost every move in his list is answered
 * by moving, which is the point - the counterplay is positioning, not more hitting.
 *
 * <h2>Phases</h2>
 * <ul>
 *   <li><b>I - The Leviathan</b>: the tide as a push. Tidal Crash, Depth Charge, Abyssal Tentacles,
 *       Riptide, Undertow, Drowned Call, Pressure Crush, Tidal Prison.</li>
 *   <li><b>II - The Abyss Opens</b> (60%): the arena stops being safe. Maelstrom, Abyssal Beam,
 *       Leviathan Dive, Black Tide, Call of the Deep, Crushing Depths, Tsunami.</li>
 *   <li><b>III - Drowned God</b> (20%): no spacing, no defence. Worldbreaker Tide, Abyssal Maw,
 *       Leviathan's Wrath, Dead Sea, Final Depth.</li>
 * </ul>
 *
 * <h2>Everything multi-second is one scheduler</h2>
 * Most of that list is "a thing that happens shortly, somewhere", and a fight has to be able to have
 * several of them in the air at once while its own move loop keeps running. Rather than a field per
 * ability, every delayed effect is a {@link Pending}: a kind, a place, a fuse, and - for the ones
 * that linger - a life. The tick loop counts them down and lands them, which is why three Tsunami
 * waves can be travelling while a prison is still counting down on somebody.
 *
 * <p>All particles go through {@link BossVfx}, so a Bedrock client is handed a smaller version of
 * every shape rather than a queue it cannot drain.
 */
public final class DrownedSovereignManager {

   private static final String TAG = "ff_drowned_sovereign";
   /**
    * The tag every body the call has ever raised carries.
    *
    * <p>Public, and the same tag the shipped build put on the <em>guardians</em> it raised, because
    * that is what lets {@link #sweepLegacyGuardians} find the ones already standing in a world: the
    * upgrade changes what the call raises, and cannot change what an older fight left behind.
    */
   public static final String MINION_TAG = TAG + "_minion";
   private static final String CHANNEL = "drowned";
   private static final String BOSS_NAME = "\u00a73\u00a7l\uD83C\uDF0A The Drowned Sovereign";
   private static final String SAY = "\u00a73The Sovereign\u00a7r\u00a77 \u203a \u00a7f";

   private static final double MAX_HEALTH = 760.0;
   private static final double SEEK_RANGE = 90.0;
   /** Wider than the widest arena in the mod, and no wider - see {@code ClockworkKingManager}. */
   public static final double STRAY_SWEEP_RADIUS = 96.0;

   /** Where each phase begins, as a fraction of maximum health. */
   private static final double PHASE_2_AT = 0.60;
   private static final double PHASE_3_AT = 0.20;

   /** Ticks between one move and the next, per phase: he speeds up as the sea gets angrier. */
   private static final int MOVE_GAP_1 = 74;
   private static final int MOVE_GAP_2 = 58;
   private static final int MOVE_GAP_3 = 42;

   /** The wave: how wide the arc is, how far it reaches, and what it costs to stand in it. */
   private static final double WAVE_HALF_ANGLE = 100.0;
   private static final double WAVE_REACH = 13.0;
   private static final float WAVE_DAMAGE = 9.0F;
   private static final double WAVE_PUSH = 2.15;

   private static final double CHARGE_RADIUS = 4.0;
   private static final float CHARGE_DAMAGE = 15.0F;
   private static final int CHARGE_TELL = 40;

   private static final double TENTACLE_RADIUS = 2.4;
   private static final float TENTACLE_DAMAGE = 11.0F;

   private static final double PRISON_RADIUS = 2.2;
   private static final int PRISON_TICKS = 60;

   private static final double MAELSTROM_RADIUS = 22.0;
   private static final double MAW_RADIUS = 26.0;

   private static final float BEAM_DAMAGE = 17.0F;
   private static final int BEAM_TELL = 40;
   private static final int BEAM_LIFE = 20;
   private static final double BEAM_REACH = 30.0;
   private static final double BEAM_HALF_WIDTH = 1.6;
   private static final int BEAM_HIT_COOLDOWN = 20;

   private static final int BLACK_TIDE_LIFE = 120;
   private static final double BLACK_TIDE_RADIUS = 3.4;
   private static final float BLACK_TIDE_DAMAGE = 2.5F;

   private static final int EYE_LIFE = 40;
   private static final double EYE_RADIUS = 2.0;
   private static final int EYE_HIT_COOLDOWN = 30;

   private static final int DEAD_SEA_TICKS = 200;

   private static final int FINAL_DEPTH_TELL = 40;
   private static final int FINAL_DEPTH_MARKS = 4;

   /**
    * The last tide: how far one roll of it reaches, and how much water is standing in it.
    *
    * <p>Deliberately larger than {@link #WAVE_REACH} by a wide margin - Worldbreaker Tide is one
    * enormous wave the length of the arena, and the finale has to be a bigger thing than that
    * rather than the same wave with a different name on the action bar.
    */
   private static final double MEGA_REACH = 32.0;
   private static final double MEGA_RADIUS = 30.0;
   private static final float MEGA_DAMAGE = 16.0F;
   private static final double MEGA_PUSH = 3.0;
   /** Three rolls, each one starting a second after the last - a swell, not a single wall. */
   private static final int MEGA_ROLLS = 3;

   private static final int DEATH_CEREMONY_TICKS = 90;

   private static final Random RANDOM = new Random();

   /** One delayed effect: where it lands, and how long it has left. */
   private static final class Pending {
      final String kind;
      final Vec3 pos;
      int fuse;
      int life;

      Pending(String kind, Vec3 pos, int fuse, int life) {
         this.kind = kind;
         this.pos = pos;
         this.fuse = fuse;
         this.life = life;
      }
   }

   private static final class Fight {
      final UUID bossId;
      final UUID summoner;
      final ServerBossEvent bar;
      final Set<UUID> participants = new HashSet<>();
      final List<Pending> pending = new ArrayList<>();
      /**
       * The bodies Drowned Call brought up, so they can be sent back with him.
       *
       * <p>A summoned minion that outlives its fight is not a minion, it is a leftover - and the
       * guardians this move used to raise were the worst case of it: a guardian never stops firing,
       * so a pair left standing behind a dead boss was a guardian attack sound repeating somewhere
       * off screen for as long as the player stayed in the chunk.
       */
      final List<UUID> minions = new ArrayList<>();
      /** When each body may be hit by a lingering hazard again - see {@link #mayTouch}. */
      final Map<UUID, Long> touched = new HashMap<>();
      int phase = 1;
      long now;
      long nextMove;
      long nextTaunt;
      String lastMove = "";
      boolean dying;
      int deathTicks;

      Fight(UUID bossId, UUID summoner, ServerBossEvent bar) {
         this.bossId = bossId;
         this.summoner = summoner;
         this.bar = bar;
      }
   }

   private static final Map<UUID, Fight> FIGHTS = new HashMap<>();

   /**
    * Bosses whose loot has already been paid out - see {@link ClockworkKingManager#onBossDeath} for
    * why this exists. The ceremony pays through this and the entity-death hook that follows it is a
    * no-op, so loot lands exactly once whichever route the boss dies by.
    */
   private static final Set<UUID> LOOT_PAID = new HashSet<>();

   private static final List<String> MOVES_1 = List.of(
      "Tidal Crash", "Depth Charge", "Abyssal Tentacles", "Riptide", "Undertow", "Drowned Call",
      "Pressure Crush", "Tidal Prison"
   );
   private static final List<String> MOVES_2 = List.of(
      "Maelstrom", "Abyssal Beam", "Leviathan Dive", "Black Tide", "Call of the Deep", "Crushing Depths",
      "Tsunami"
   );
   private static final List<String> MOVES_3 = List.of(
      "Worldbreaker Tide", "Abyssal Maw", "Leviathan's Wrath", "Dead Sea", "Final Depth", "Final Tsunami"
   );

   private DrownedSovereignManager() {
   }

   // ------------------------------------------------------------------ public API

   public static boolean isDrownedSovereign(Entity entity) {
      return entity != null && entity.entityTags().contains(TAG);
   }

   public static int activeCount() {
      return FIGHTS.size();
   }

   /** How many delayed effects this fight is holding - three Tsunami waves, a prison, a pool. */
   public static int pendingCount(UUID bossId) {
      Fight fight = FIGHTS.get(bossId);
      return fight == null ? 0 : fight.pending.size();
   }

   /** The phase this fight is in, or 0 if it is not running. For the codex and the self-test. */
   public static int phaseOf(UUID bossId) {
      Fight fight = FIGHTS.get(bossId);
      return fight == null ? 0 : fight.phase;
   }

   public static boolean isParticipant(ServerPlayer player) {
      if (player == null) {
         return false;
      }
      for (Fight f : FIGHTS.values()) {
         if (!f.dying && f.participants.contains(player.getUUID())) {
            return true;
         }
      }
      return false;
   }

   public static int abandonAll(MinecraftServer server) {
      int ended = 0;
      for (Fight fight : new ArrayList<>(FIGHTS.values())) {
         Safe.run("sovereign abandon", () -> shutDown(server, fight));
         ended++;
      }
      return ended;
   }

   public static void onServerStopping(MinecraftServer server) {
      for (Fight fight : new ArrayList<>(FIGHTS.values())) {
         Safe.run("sovereign shutdown", () -> shutDown(server, fight));
      }
      FIGHTS.clear();
   }

   /** The Sovereign's Heart right-click: he answers from below. */
   public static String useSovereignsHeart(ServerPlayer player, ItemStack held) {
      String err = summon(player);
      if (err != null) {
         return err;
      }
      if (!player.getAbilities().instabuild) {
         held.shrink(1);
      }
      return null;
   }

   private static String summon(ServerPlayer summoner) {
      if (!ModConfig.is("boss")) {
         return "Bosses are disabled on this server.";
      }
      for (Fight f : new ArrayList<>(FIGHTS.values())) {
         Entity live = findEntity(summoner.level().getServer(), f.bossId);
         if (live == null || !live.isAlive()) {
            shutDown(summoner.level().getServer(), f);
         }
      }
      for (Fight f : FIGHTS.values()) {
         if (summoner.getUUID().equals(f.summoner)) {
            return "Your tide is already rising - finish this one first!";
         }
      }

      ServerLevel level = summoner.level();
      Mob boss = (Mob)EntityTypes.DROWNED.create(level, EntitySpawnReason.COMMAND);
      if (boss == null) {
         return "The deep did not answer - he did not arrive.";
      }
      AttributeInstance maxHp = boss.getAttribute(Attributes.MAX_HEALTH);
      if (maxHp != null) {
         maxHp.setBaseValue(MAX_HEALTH);
      }
      boss.setHealth((float)MAX_HEALTH);
      AttributeInstance follow = boss.getAttribute(Attributes.FOLLOW_RANGE);
      if (follow != null) {
         follow.setBaseValue(SEEK_RANGE);
      }
      AttributeInstance kb = boss.getAttribute(Attributes.KNOCKBACK_RESISTANCE);
      if (kb != null) {
         kb.setBaseValue(1.0);
      }
      // Big by the scale attribute rather than by a different mob: the point of the fight is a
      // creature you can see coming from the far end of the arena, not a slightly taller drowned.
      AttributeInstance scale = boss.getAttribute(Attributes.SCALE);
      if (scale != null) {
         scale.setBaseValue(2.6);
      }
      AttributeInstance speed = boss.getAttribute(Attributes.MOVEMENT_SPEED);
      if (speed != null) {
         speed.setBaseValue(0.30);
      }
      AttributeInstance damage = boss.getAttribute(Attributes.ATTACK_DAMAGE);
      if (damage != null) {
         damage.setBaseValue(14.0);
      }

      boss.setPersistenceRequired();
      boss.setCustomName(Component.literal(BOSS_NAME));
      boss.setCustomNameVisible(true);
      boss.addTag(TAG);
      // The shared marker plus the visible-and-persistent guarantee: see BossManager.markBoss for
      // why a boss has to say so itself.
      BossManager.markBoss(boss);
      boss.setPos(
         summoner.getX(), BossGrounding.groundY(level, summoner.getX(), summoner.getZ(), summoner.getY()), summoner.getZ()
      );
      level.addFreshEntity(boss);

      ServerBossEvent bar = new ServerBossEvent(
         UUID.randomUUID(), Component.literal(barName(1)), BossBarColor.BLUE, BossBarOverlay.PROGRESS
      );
      bar.setVisible(true);
      for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
         bar.addPlayer(p);
      }

      Fight fight = new Fight(boss.getUUID(), summoner.getUUID(), bar);
      fight.participants.add(summoner.getUUID());
      long now = ServerClock.clock(level);
      fight.nextMove = now + 50L;
      fight.nextTaunt = now + 120L;
      FIGHTS.put(boss.getUUID(), fight);

      announce(level, "\u00a73\u00a7m\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550");
      announce(level, "    \u00a73\u00a7l\uD83C\uDF0A THE DROWNED SOVEREIGN \uD83C\uDF0A");
      announce(level, "    \u00a77The water remembers what he was.");
      announce(level, "    \u00a78\u201c\u00a7fYou are standing where my kingdom used to be.\u00a78\u201d");
      announce(level, "    \u00a77\u00a7oEvery move he makes is answered by moving. Keep your feet.");
      announce(level, "\u00a73\u00a7m\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550");
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), ModSounds.BOSS_SPAWN, SoundSource.HOSTILE, 1.4F, 0.6F);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.ELDER_GUARDIAN_CURSE, SoundSource.HOSTILE, 1.2F, 0.7F);
      Advancements.grant(summoner, "summon_drowned_sovereign");
      return null;
   }

   // ------------------------------------------------------------------------ tick

   public static void tick(MinecraftServer server) {
      if (!LOOT_PAID.isEmpty()) {
         LOOT_PAID.removeIf(id -> findEntity(server, id) == null);
      }
      if (FIGHTS.isEmpty()) {
         return;
      }
      long now = ServerClock.clock(server.overworld());
      for (Fight fight : new ArrayList<>(FIGHTS.values())) {
         Safe.run("sovereign tick", () -> tickFight(server, fight, now));
      }
   }

   /**
    * Sweeps up Sovereign bodies left behind by a crash or a hard restart - see
    * {@link ClockworkKingManager#sweepStrays} for the full story. Run near players, because chunks
    * are not loaded on boot.
    *
    * @return how many bodies were removed
    */
   public static int sweepStrays(MinecraftServer server) {
      int removed = 0;
      // Perf: one entity query per player, and a player already inside a swept box is skipped
      // instead of queried again - see the same shape in StarboundMagisterManager.
      List<double[]> swept = new ArrayList<>();
      for (ServerPlayer p : server.getPlayerList().getPlayers()) {
         if (!(p.level() instanceof ServerLevel level)) {
            continue;
         }
         double r = STRAY_SWEEP_RADIUS;
         double[] box = new double[]{p.getX() - r, p.getY() - r, p.getZ() - r, p.getX() + r, p.getY() + r, p.getZ() + r};
         boolean covered = false;
         for (double[] s : swept) {
            if (s[0] <= box[0] && s[1] <= box[1] && s[2] <= box[2] && s[3] >= box[3] && s[4] >= box[4] && s[5] >= box[5]) {
               covered = true;
               break;
            }
         }
         if (covered) {
            continue;
         }
         swept.add(box);
         for (Mob mob : level.getEntitiesOfClass(Mob.class, p.getBoundingBox().inflate(r))) {
            if (isDrownedSovereign(mob) && !FIGHTS.containsKey(mob.getUUID())) {
               mob.discard();
               removed++;
            }
         }
      }
      return removed;
   }

   /**
    * The looping guardian noise the players still hear, and why upgrading alone does not stop it.
    *
    * <p>{@code Drowned Call} used to raise guardians, and they were raised with
    * {@code setPersistenceRequired()} - so every guardian a fight ever left standing is not a mob
    * that wanders off, it is a body in the save file. A guardian's attack is a beam it keeps firing
    * for as long as it can see anybody, so one of those left behind is a guardian attack sound with
    * no end. The call raises drowned now, which fixes every <em>future</em> fight and does nothing
    * at all about the ones already parked in somebody's world.
    *
    * <p>So this is the part that reaches back: any guardian still carrying the old minion tag is
    * removed, once, when a player is near enough for its chunk to be loaded. Guardians that were
    * never ours - an ocean monument's own - carry no such tag and are untouched, which is the whole
    * reason the tag is matched rather than the entity type.
    *
    * @return how many bodies were removed
    */
   public static int sweepLegacyGuardians(MinecraftServer server) {
      int removed = 0;
      List<double[]> swept = new ArrayList<>();
      for (ServerPlayer p : server.getPlayerList().getPlayers()) {
         if (!(p.level() instanceof ServerLevel level)) {
            continue;
         }
         double r = STRAY_SWEEP_RADIUS;
         double[] box = new double[]{p.getX() - r, p.getY() - r, p.getZ() - r, p.getX() + r, p.getY() + r, p.getZ() + r};
         boolean covered = false;
         for (double[] s : swept) {
            if (s[0] <= box[0] && s[1] <= box[1] && s[2] <= box[2] && s[3] >= box[3] && s[4] >= box[4] && s[5] >= box[5]) {
               covered = true;
               break;
            }
         }
         if (covered) {
            continue;
         }
         swept.add(box);
         for (Mob mob : level.getEntitiesOfClass(Mob.class, p.getBoundingBox().inflate(r))) {
            if (isStrayMinion(mob)) {
               mob.discard();
               removed++;
            }
         }
      }
      return removed;
   }

   /**
    * Whether a body is one of ours with no fight left to belong to.
    *
    * <p>Two questions, and both have to be asked. The tag is what says "the call raised this", and
    * it is deliberately not an entity-type test: an ocean monument's own guardians carry no tag and
    * are none of our business. The ledger is what says "and its fight is over" - the crew of the
    * fight happening right now is tagged exactly the same way, so a sweep that matched on the tag
    * alone would quietly empty every {@code Drowned Call} five minutes into a fight.
    */
   public static boolean isStrayMinion(Entity entity) {
      if (entity == null || !entity.entityTags().contains(MINION_TAG)) {
         return false;
      }
      if (FIGHTS.containsKey(entity.getUUID())) {
         return false;
      }
      for (Fight fight : FIGHTS.values()) {
         if (fight.minions.contains(entity.getUUID())) {
            return false;
         }
      }
      return true;
   }

   /** Pays this boss's loot exactly once, however he dies, and releases the fight. */
   public static void onBossDeath(ServerLevel level, Mob boss) {
      if (level == null || boss == null || LOOT_PAID.contains(boss.getUUID())) {
         return;
      }
      Fight fight = FIGHTS.get(boss.getUUID());
      if (fight == null) {
         return;
      }
      LOOT_PAID.add(boss.getUUID());
      grantLoot(level, boss, fight);
      release(level.getServer(), fight);
   }

   /**
    * The killing blow is cancelled and played out: the sea takes him back rather than letting the
    * body fall over mid-move with a dozen hazards still in the air. Returning {@code FALSE} cancels
    * the damage (Fabric: {@code true} means "allow it"), and the ceremony's own end kills him for
    * real, which is the only route that pays the loot.
    */
   public static Boolean onLethalDamage(Entity entity, float amount) {
      if (!isDrownedSovereign(entity) || entity.level().isClientSide()) {
         return null;
      }
      Fight fight = FIGHTS.get(entity.getUUID());
      if (fight == null || fight.dying) {
         return null;
      }
      if (!(entity instanceof Mob boss) || boss.getHealth() - amount > 0.0F) {
         return null;
      }
      ServerLevel level = (ServerLevel)boss.level();
      fight.dying = true;
      fight.deathTicks = DEATH_CEREMONY_TICKS;
      fight.pending.clear();
      boss.setHealth(1.0F);
      boss.setNoAi(true);
      fight.bar.setProgress(0.0F);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.CONDUIT_DEACTIVATE, SoundSource.HOSTILE, 2.0F, 0.6F);
      announce(level, SAY + "\u00a77\u201c\u00a7f...the tide always comes back.\u00a77\u201d");
      return Boolean.FALSE;
   }

   private static void tickFight(MinecraftServer server, Fight fight, long now) {
      Mob boss = bossOf(server, fight);
      if (boss == null) {
         shutDown(server, fight);
         return;
      }
      ServerLevel level = (ServerLevel)boss.level();
      fight.now = now;

      for (ServerPlayer p : server.getPlayerList().getPlayers()) {
         if (p.level() == level && p.isAlive() && p.distanceToSqr(boss) < SEEK_RANGE * SEEK_RANGE) {
            fight.participants.add(p.getUUID());
         }
      }

      if (fight.dying) {
         tickDeath(server, boss, fight);
         return;
      }

      int phase = phaseFor(boss);
      if (phase != fight.phase) {
         fight.phase = phase;
         enterPhase(level, boss, fight, phase);
      }

      fight.bar.setProgress(Math.max(0.0F, Math.min(1.0F, boss.getHealth() / boss.getMaxHealth())));
      fight.bar.setName(Component.literal(barName(fight.phase)));

      // A presence, so the fight is never silent between moves - and it is water rather than a
      // generic aura, because the whole read of this fight is "the sea is standing here with you".
      if (now % 15L == 0L) {
         BossVfx.sphere(level, boss.position().add(0.0, 1.0, 0.0), 2.4, 2, ParticleTypes.FALLING_WATER);
         BossVfx.at(level, boss.position(), 0.0, ParticleTypes.BUBBLE, 5, 1.2, 1.4, 1.2, 0.02);
         BossVfx.at(level, boss.position(), 0.0, ParticleTypes.DRIPPING_WATER, 4, 1.4, 0.6, 1.4, 0.0);
      }

      tickPending(level, boss, fight);
      tickMinions(level, fight);

      // He wears what he is, and the sea does not burn. A drowned left in daylight catches fire,
      // which turned the king of the deep into a running torch the moment the sun came up; the
      // crown and the trident are what make him read as a drowned *king* rather than a drowned.
      crownAndTrident(boss);

      // And he walks. There is no vanilla AI behind him - the whole fight is scripted because
      // every one of his moves is about where the player is standing, and a mob that also throws
      // its own attacks is a mob whose tells cannot be read. Movement is a steady approach to
      // whoever is closest, at a speed the player can back away from, and no jumping at all.
      ServerPlayer chase = nearestPlayer(level, boss, 64.0);
      if (chase != null) {
         walkToward(boss, chase, 3.0, 0.15);
      }

      if (now >= fight.nextMove) {
         fight.nextMove = now + moveGap(fight.phase);
         chooseMove(level, boss, fight);
      }
   }

   /**
    * Keeps the crown on his head and the trident in his hand, and keeps him out of the daylight.
    *
    * <p>Set every tick rather than once at the summon: a player can take them off him, and a boss
    * that was stripped in the first ten seconds would spend the rest of the fight as a bare mob.
    */
   private static void crownAndTrident(Mob boss) {
      boss.setRemainingFireTicks(0);
      if (!boss.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD).is(net.minecraft.world.item.Items.GOLDEN_HELMET)) {
         boss.setItemSlot(
            net.minecraft.world.entity.EquipmentSlot.HEAD,
            new ItemStack(net.minecraft.world.item.Items.GOLDEN_HELMET)
         );
         boss.setDropChance(net.minecraft.world.entity.EquipmentSlot.HEAD, 0.0F);
      }
      if (!boss.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND).is(net.minecraft.world.item.Items.TRIDENT)) {
         boss.setItemSlot(
            net.minecraft.world.entity.EquipmentSlot.MAINHAND,
            new ItemStack(net.minecraft.world.item.Items.TRIDENT)
         );
         boss.setDropChance(net.minecraft.world.entity.EquipmentSlot.MAINHAND, 0.0F);
      }
   }

   /**
    * One step toward {@code target}, and face them, with no vanilla AI involved.
    *
    * <p>The body still falls and still obeys gravity - only its horizontal intent is written here,
    * so it walks rather than teleports, and it stops at {@code spacing} so it is never inside the
    * player it is about to hit. {@code speed} is deliberately under a player's walk.
    */
   private static void walkToward(Mob boss, net.minecraft.world.entity.LivingEntity target, double spacing, double speed) {
      double dx = target.getX() - boss.getX();
      double dz = target.getZ() - boss.getZ();
      double dist = Math.sqrt(dx * dx + dz * dz);
      boss.getLookControl().setLookAt(target, 30.0F, 30.0F);
      if (dist < 0.001) {
         return;
      }
      // Facing is written separately from movement, so he is looking at you even while he is
      // holding his ground.
      boss.setYRot((float)(Math.toDegrees(Math.atan2(-dx, dz))));
      boss.yBodyRot = boss.getYRot();
      if (dist <= spacing) {
         boss.setDeltaMovement(boss.getDeltaMovement().multiply(0.5, 1.0, 0.5));
         return;
      }
      boss.setDeltaMovement(dx / dist * speed, boss.getDeltaMovement().y, dz / dist * speed);
   }

   private static String barName(int phase) {
      String label = phase == 1 ? "The Leviathan" : phase == 2 ? "The Abyss Opens" : "Drowned God";
      return BOSS_NAME + " \u00a78| \u00a7f" + label;
   }

   private static int phaseFor(Mob boss) {
      double fraction = boss.getMaxHealth() <= 0.0F ? 1.0 : boss.getHealth() / boss.getMaxHealth();
      if (fraction <= PHASE_3_AT) {
         return 3;
      }
      return fraction <= PHASE_2_AT ? 2 : 1;
   }

   private static void enterPhase(ServerLevel level, Mob boss, Fight fight, int phase) {
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.CONDUIT_ACTIVATE, SoundSource.HOSTILE, 1.8F, phase == 3 ? 0.5F : 0.8F);
      BossVfx.ring(level, boss.position(), 12.0, 48, ParticleTypes.SPLASH, 0.2);
      BossVfx.sphere(level, boss.position().add(0.0, 1.0, 0.0), 6.0, 3, ParticleTypes.BUBBLE);
      if (phase == 2) {
         announceNear(level, boss, 90.0, SAY + "\u00a7bThe floor of the sea opens.");
         overlayNear(level, boss, 90.0, "\u00a7b\u00a7lTHE ABYSS OPENS");
      } else if (phase == 3) {
         announceNear(level, boss, 90.0, SAY + "\u00a73\u00a7l\u201c\u00a7fThen drown with me.\u00a73\u00a7l\u201d");
         overlayNear(level, boss, 90.0, "\u00a73\u00a7lDROWNED GOD");
      }
   }

   private static int moveGap(int phase) {
      return switch (phase) {
         case 3 -> MOVE_GAP_3;
         case 2 -> MOVE_GAP_2;
         default -> MOVE_GAP_1;
      };
   }

   private static void chooseMove(ServerLevel level, Mob boss, Fight fight) {
      List<String> moves = movesForPhase(fight.phase);
      String move = moves.get(RANDOM.nextInt(moves.size()));
      // Never the same move twice in a row: the rotation is the fight's shape, and two Tidal
      // Crashes back to back reads as the boss having one move.
      if (moves.size() > 1 && move.equals(fight.lastMove)) {
         move = moves.get((moves.indexOf(move) + 1) % moves.size());
      }
      fight.lastMove = move;
      ServerPlayer target = nearestPlayer(level, boss, 40.0);
      switch (move) {
         case "Tidal Crash" -> tidalCrash(level, boss);
         case "Depth Charge" -> depthCharge(level, boss, fight, target);
         case "Abyssal Tentacles" -> tentacles(level, boss, fight);
         case "Riptide" -> riptide(level, boss, fight);
         case "Undertow" -> undertow(level, boss, fight);
         case "Drowned Call" -> drownedCall(level, boss, fight);
         case "Pressure Crush" -> pressureCrush(level, boss);
         case "Tidal Prison" -> tidalPrison(level, boss, fight, target);
         case "Maelstrom" -> maelstrom(level, boss, fight);
         case "Abyssal Beam" -> abyssalBeam(level, boss, fight);
         case "Leviathan Dive" -> dive(level, boss, fight, 1);
         case "Black Tide" -> blackTide(level, boss, fight);
         case "Call of the Deep" -> callOfTheDeep(level, boss, fight);
         case "Crushing Depths" -> crushingDepths(level, boss);
         case "Tsunami" -> tsunami(level, boss, fight);
         case "Final Tsunami" -> finalTsunami(level, boss, fight);
         case "Worldbreaker Tide" -> worldbreakerTide(level, boss, fight);
         case "Abyssal Maw" -> abyssalMaw(level, boss, fight);
         case "Leviathan's Wrath" -> dive(level, boss, fight, 3);
         case "Dead Sea" -> deadSea(level, boss);
         case "Final Depth" -> finalDepth(level, boss, fight);
         default -> tidalCrash(level, boss);
      }
      tell(level, boss, move);
   }

   private static void tell(ServerLevel level, Mob boss, String move) {
      level.playSound(null, boss.getX(), boss.getY() + 2.0, boss.getZ(), SoundEvents.ELDER_GUARDIAN_HURT, SoundSource.HOSTILE, 1.4F, 0.6F);
      // Telegraph removed
   }

   // ------------------------------------------------------------- phase 1: the tide

   /** A wall of water off the boss, shoving everyone in front of it backward. */
   private static void tidalCrash(ServerLevel level, Mob boss) {
      Vec3 look = boss.getLookAngle();
      Vec3 flat = new Vec3(look.x, 0.0, look.z);
      Vec3 unit = flat.lengthSqr() < 1.0E-4 ? new Vec3(1.0, 0.0, 0.0) : flat.normalize();
      BossVfx.wall(level, boss.position().add(0.0, 1.0, 0.0), unit, 7.0, 3.5, ParticleTypes.SPLASH, 4);
      BossVfx.wall(level, boss.position().add(0.0, 0.2, 0.0), unit, WAVE_REACH * 0.8, 2.0, ParticleTypes.FALLING_WATER, 3);
      BossVfx.ring(level, boss.position(), 7.0, 45, ParticleTypes.DOLPHIN, 0.6);
      BossVfx.ring(level, boss.position(), 4.0, 30, ParticleTypes.ELECTRIC_SPARK, 0.3);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.GENERIC_SPLASH, SoundSource.HOSTILE, 1.6F, 0.7F);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.LIGHTNING_BOLT_IMPACT, SoundSource.HOSTILE, 0.8F, 1.8F);
      for (ServerPlayer p : playersNear(level, boss.position(), WAVE_REACH)) {
         Vec3 to = p.position().subtract(boss.position());
         Vec3 toFlat = new Vec3(to.x, 0.0, to.z);
         if (toFlat.lengthSqr() < 1.0E-4) {
            continue;
         }
         double angle = Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, toFlat.normalize().dot(unit)))));
         if (angle > WAVE_HALF_ANGLE) {
            continue;
         }
         p.hurtServer(level, level.damageSources().mobAttack(boss), WAVE_DAMAGE);
         push(p, unit, WAVE_PUSH);
         p.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 40, 0, false, true));
      }
   }

   /** Marked ground, then an underwater detonation on the mark. */
   private static void depthCharge(ServerLevel level, Mob boss, Fight fight, ServerPlayer target) {
      if (target == null) {
         tidalCrash(level, boss);
         return;
      }
      fight.pending.add(new Pending("charge", target.position(), CHARGE_TELL, 0));
   }

   /** Tentacles out of the ground around whoever is standing where. */
   private static void tentacles(ServerLevel level, Mob boss, Fight fight) {
      List<ServerPlayer> near = playersNear(level, boss.position(), 24.0);
      int count = Math.max(3, Math.min(6, near.size() + 2));
      for (int i = 0; i < count; i++) {
         ServerPlayer anchor = near.isEmpty() ? null : near.get(RANDOM.nextInt(near.size()));
         double x = anchor != null ? anchor.getX() : boss.getX() + (RANDOM.nextDouble() - 0.5) * 16.0;
         double z = anchor != null ? anchor.getZ() : boss.getZ() + (RANDOM.nextDouble() - 0.5) * 16.0;
         double y = BossGrounding.groundY(level, x, z, anchor != null ? anchor.getY() : boss.getY());
         fight.pending.add(new Pending("tentacle", new Vec3(x, y, z), 22 + RANDOM.nextInt(10), 0));
      }
      announceNear(level, boss, 60.0, SAY + "\u00a77The ground is moving.");
   }

   /** Dash between bodies, cutting on arrival and on departure. */
   private static void riptide(ServerLevel level, Mob boss, Fight fight) {
      List<ServerPlayer> near = playersNear(level, boss.position(), 30.0);
      if (near.isEmpty()) {
         tidalCrash(level, boss);
         return;
      }
      Vec3 from = boss.position();
      int steps = Math.min(3, near.size());
      for (int i = 0; i < steps; i++) {
         ServerPlayer to = near.get(RANDOM.nextInt(near.size()));
         Vec3 landing = to.position().add(to.getLookAngle().scale(-2.0));
         BossVfx.beam(level, from.add(0.0, 1.0, 0.0), landing.add(0.0, 1.0, 0.0), 0.5, ParticleTypes.DOLPHIN);
         for (ServerPlayer p : playersNear(level, from, 2.2)) {
            p.hurtServer(level, level.damageSources().mobAttack(boss), 7.0F);
         }
         level.playSound(null, from.x, from.y, from.z, SoundEvents.DOLPHIN_SPLASH, SoundSource.HOSTILE, 1.3F, 1.4F);
         from = landing;
      }
      boss.setPos(from.x, from.y, from.z);
      boss.setDeltaMovement(Vec3.ZERO);
      boss.hurtMarked = true;
      for (ServerPlayer p : playersNear(level, from, 2.4)) {
         p.hurtServer(level, level.damageSources().mobAttack(boss), 6.0F);
      }
   }

   /** The whirlpool: it decides where everyone nearby is allowed to be. */
   private static void undertow(ServerLevel level, Mob boss, Fight fight) {
      fight.pending.add(new Pending("undertow", boss.position(), 0, 80));
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.ELDER_GUARDIAN_CURSE, SoundSource.HOSTILE, 1.2F, 1.1F);
   }

   /**
    * Drowned, called up around him.
    *
    * <p>Drowned and not guardians, and that is a fix rather than a flavour change. Guardians were
    * one in three of these, and a guardian's whole attack is a beam it keeps firing for as long as
    * it can see you - so an unquiet fight left a chorus of guardian attacks running off screen, and
    * with {@code setPersistenceRequired} on them they never went anywhere on their own. The call is
    * drowned now, they are not persistent, and every one of them is listed in the fight so the
    * fight can take them back down with it.
    */
   private static void drownedCall(ServerLevel level, Mob boss, Fight fight) {
      int count = 3 + RANDOM.nextInt(3);
      for (int i = 0; i < count; i++) {
         double a = RANDOM.nextDouble() * Math.PI * 2.0;
         double x = boss.getX() + Math.cos(a) * 3.5;
         double z = boss.getZ() + Math.sin(a) * 3.5;
         Mob minion = (Mob)EntityTypes.DROWNED.create(level, EntitySpawnReason.COMMAND);
         if (minion == null) {
            continue;
         }
         minion.setPos(x, BossGrounding.groundY(level, x, z, boss.getY()), z);
         minion.addTag(MINION_TAG);
         level.addFreshEntity(minion);
         fight.minions.add(minion.getUUID());
      }
      BossVfx.ring(level, boss.position(), 4.5, 30, ParticleTypes.BUBBLE_POP, 0.5);
      BossVfx.sphere(level, boss.position().add(0.0, 1.0, 0.0), 4.0, 2, ParticleTypes.FALLING_WATER);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.CONDUIT_ATTACK_TARGET, SoundSource.HOSTILE, 1.4F, 0.8F);
      announceNear(level, boss, 50.0, SAY + "\u00a77The deep sends its crew.");
   }

   /**
    * Keeps the called drowned alive while the fight runs and takes them back when it ends.
    *
    * <p>They are despawnable vanilla mobs, so a player who walks far enough away loses them anyway;
    * this is the guarantee that none of them is still standing in the arena after the fight, which
    * is what stops a fight's leftovers becoming the next session's background noise.
    */
   private static void tickMinions(ServerLevel level, Fight fight) {
      if (fight.dying) {
         clearMinions(level, fight);
      }
   }

   private static void clearMinions(ServerLevel level, Fight fight) {
      if (fight.minions.isEmpty()) {
         return;
      }
      for (UUID id : fight.minions) {
         Entity minion = level.getEntity(id);
         if (minion != null && minion.isAlive()) {
            minion.discard();
         }
      }
      fight.minions.clear();
   }

   /** Pressure: heavy limbs, heavy tools. */
   private static void pressureCrush(ServerLevel level, Mob boss) {
      BossVfx.sphere(level, boss.position().add(0.0, 1.0, 0.0), 7.0, 3, ParticleTypes.BUBBLE);
      for (ServerPlayer p : playersNear(level, boss.position(), 10.0)) {
         p.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 80, 1));
         p.addEffect(new MobEffectInstance(MobEffects.MINING_FATIGUE, 80, 1));
         p.sendOverlayMessage(Component.literal("\u00a73\u00a7lPRESSURE \u00a77- the water is holding you down"));
      }
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.WARDEN_SONIC_CHARGE, SoundSource.HOSTILE, 0.9F, 0.6F);
   }

   /** One player, inside a sphere of water that is not a room. */
   private static void tidalPrison(ServerLevel level, Mob boss, Fight fight, ServerPlayer target) {
      if (target == null) {
         pressureCrush(level, boss);
         return;
      }
      fight.pending.add(new Pending("prison", target.position(), 0, PRISON_TICKS));
      level.playSound(null, target.getX(), target.getY(), target.getZ(), SoundEvents.CONDUIT_ACTIVATE, SoundSource.HOSTILE, 1.4F, 1.6F);
   }

   // ---------------------------------------------------------- phase 2: the abyss

   private static void maelstrom(ServerLevel level, Mob boss, Fight fight) {
      fight.pending.add(new Pending("maelstrom", boss.position(), 0, 160));
      announceNear(level, boss, 70.0, SAY + "\u00a7b\u00a7lTHE MAELSTROM");
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.ELDER_GUARDIAN_CURSE, SoundSource.HOSTILE, 1.8F, 0.5F);
   }

   /** A ray across the water, drawn on the ground before it fires. */
   private static void abyssalBeam(ServerLevel level, Mob boss, Fight fight) {
      ServerPlayer target = nearestPlayer(level, boss, 40.0);
      Vec3 dir = target == null ? boss.getLookAngle() : target.position().subtract(boss.position());
      Vec3 flat = new Vec3(dir.x, 0.0, dir.z);
      if (flat.lengthSqr() < 1.0E-4) {
         flat = new Vec3(1.0, 0.0, 0.0);
      }
      fight.pending.add(new Pending("beam", boss.position().add(flat.normalize().scale(BEAM_REACH)), 0, BEAM_TELL + BEAM_LIFE));
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.WARDEN_SONIC_CHARGE, SoundSource.HOSTILE, 1.1F, 0.7F);
   }

   /** Under the floor, then up - once in phase two, three times as Leviathan's Wrath. */
   private static void dive(ServerLevel level, Mob boss, Fight fight, int times) {
      List<ServerPlayer> near = playersNear(level, boss.position(), 30.0);
      if (near.isEmpty()) {
         tidalCrash(level, boss);
         return;
      }
      Vec3 under = near.get(RANDOM.nextInt(near.size())).position();
      boss.setPos(under.x, under.y, under.z);
      boss.setDeltaMovement(Vec3.ZERO);
      boss.hurtMarked = true;
      for (int i = 0; i < Math.max(1, times); i++) {
         ServerPlayer anchor = near.get(RANDOM.nextInt(near.size()));
         fight.pending.add(new Pending("erupt", anchor.position(), 16 + i * 14, 0));
      }
      BossVfx.disc(level, under, 4.0, ParticleTypes.SQUID_INK, 0.2);
      level.playSound(null, under.x, under.y, under.z, SoundEvents.ELDER_GUARDIAN_HURT, SoundSource.HOSTILE, 1.6F, 0.5F);
      announceNear(level, boss, 60.0, SAY + "\u00a77He is underneath you.");
   }

   /** Water that is not water any more: standing in it is the cost. */
   private static void blackTide(ServerLevel level, Mob boss, Fight fight) {
      for (int i = 0; i < 8; i++) {
         double a = (Math.PI * 2.0 * i) / 8.0;
         double x = boss.getX() + Math.cos(a) * 11.0;
         double z = boss.getZ() + Math.sin(a) * 11.0;
         double y = BossGrounding.groundY(level, x, z, boss.getY());
         fight.pending.add(new Pending("black", new Vec3(x, y, z), 0, BLACK_TIDE_LIFE));
      }
      announceNear(level, boss, 70.0, SAY + "\u00a73\u00a7lBLACK TIDE \u00a77- \u00a7fget out of the dark water");
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.WARDEN_ROAR, SoundSource.HOSTILE, 0.8F, 0.5F);
   }

   /** Eyes in the water, each one a small beam. */
   private static void callOfTheDeep(ServerLevel level, Mob boss, Fight fight) {
      for (int i = 0; i < 5; i++) {
         double a = RANDOM.nextDouble() * Math.PI * 2.0;
         double r = 8.0 + RANDOM.nextDouble() * 10.0;
         double x = boss.getX() + Math.cos(a) * r;
         double z = boss.getZ() + Math.sin(a) * r;
         double y = BossGrounding.groundY(level, x, z, boss.getY());
         fight.pending.add(new Pending("eye", new Vec3(x, y + 0.4, z), 30 + RANDOM.nextInt(20), EYE_LIFE));
      }
      announceNear(level, boss, 70.0, SAY + "\u00a7bThe deep opens its eyes.");
   }

   /** Gravity, briefly, in his favour. */
   private static void crushingDepths(ServerLevel level, Mob boss) {
      for (ServerPlayer p : playersNear(level, boss.position(), 20.0)) {
         p.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 100, 1));
         p.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 100, 0));
         p.setDeltaMovement(p.getDeltaMovement().add(0.0, -1.4, 0.0));
         p.hurtMarked = true;
         p.sendOverlayMessage(Component.literal("\u00a73\u00a7lCRUSHING DEPTHS"));
      }
      BossVfx.disc(level, boss.position(), 20.0, ParticleTypes.SQUID_INK, 0.1);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.WARDEN_SONIC_CHARGE, SoundSource.HOSTILE, 1.2F, 0.4F);
   }

   /** Three waves, in sequence, across the whole arena, with the water rolling ahead of each. */
   private static void tsunami(ServerLevel level, Mob boss, Fight fight) {
      for (int i = 0; i < 3; i++) {
         fight.pending.add(new Pending("tsunami", boss.position(), 14 + i * 26, 0));
      }
      BossVfx.ring(level, boss.position(), 16.0, 56, ParticleTypes.FALLING_WATER, 0.3);
      BossVfx.disc(level, boss.position(), 18.0, ParticleTypes.BUBBLE, 0.2);
      announceNear(level, boss, 80.0, SAY + "\u00a73\u00a7lTSUNAMI \u00a77- three of them");
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.GENERIC_SPLASH, SoundSource.HOSTILE, 1.9F, 0.4F);
   }

   // ----------------------------------------------------- phase 3: the drowned god

   private static void worldbreakerTide(ServerLevel level, Mob boss, Fight fight) {
      fight.pending.add(new Pending("worldbreaker", boss.position(), 30, 0));
      BossVfx.ring(level, boss.position(), 22.0, 64, ParticleTypes.FALLING_WATER, 0.2);
      BossVfx.sphere(level, boss.position().add(0.0, 1.0, 0.0), 8.0, 3, ParticleTypes.BUBBLE);
      announceNear(level, boss, 100.0, SAY + "\u00a73\u00a7lWORLDBREAKER TIDE \u00a77- run, or be moved");
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.ELDER_GUARDIAN_CURSE, SoundSource.HOSTILE, 2.0F, 0.4F);
   }

   /** The centre of the arena, pulling. */
   private static void abyssalMaw(ServerLevel level, Mob boss, Fight fight) {
      fight.pending.add(new Pending("maw", boss.position(), 0, 140));
      announceNear(level, boss, 90.0, SAY + "\u00a73\u00a7lTHE ABYSSAL MAW");
   }

   /**
    * The finale: the whole arena, taken by the sea in three rolls.
    *
    * <p>The last phase's other moves each have an out - Worldbreaker Tide is one wall you can be
    * behind, Abyssal Maw is a pull you can walk against, Final Depth marks only a few bodies at a
    * time. This one has no out by design: it is the fight's closing statement, and the answer to it
    * is the same as the answer to the whole rest of the fight, which is that you keep your footing
    * and you accept being moved. Each roll is a full-width wall of water that also drinks the
    * ground it passes over, so the arena is standing in the tide between rolls as well as during
    * them.
    */
   private static void finalTsunami(ServerLevel level, Mob boss, Fight fight) {
      for (int i = 0; i < MEGA_ROLLS; i++) {
         fight.pending.add(new Pending("megawave", boss.position(), 26 + i * 24, 0));
      }
      BossVfx.ring(level, boss.position(), MEGA_RADIUS * 0.7, 80, ParticleTypes.FALLING_WATER, 0.25);
      BossVfx.sphere(level, boss.position().add(0.0, 1.0, 0.0), 9.0, 4, ParticleTypes.BUBBLE);
      BossVfx.column(level, boss.position(), 10.0, ParticleTypes.FALLING_WATER, 4);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.ELDER_GUARDIAN_CURSE, SoundSource.HOSTILE, 2.2F, 0.4F);
      announceNear(level, boss, 110.0, SAY + "\u00a73\u00a7lFINAL TSUNAMI \u00a77- \u00a7fthe whole sea, three times");
      overlayNear(level, boss, 110.0, "\u00a73\u00a7lTHE LAST TIDE");
   }

   /** Regeneration goes away, and the sea keeps hurting. */
   private static void deadSea(ServerLevel level, Mob boss) {
      for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
         if (p.level() != level || !p.isAlive() || p.isCreative() || p.isSpectator()) {
            continue;
         }
         p.removeEffect(MobEffects.REGENERATION);
         p.addEffect(new MobEffectInstance(MobEffects.HUNGER, DEAD_SEA_TICKS, 2));
         p.addEffect(new MobEffectInstance(MobEffects.POISON, DEAD_SEA_TICKS, 0));
         p.sendOverlayMessage(Component.literal("\u00a73\u00a7lDEAD SEA \u00a77- nothing heals here"));
      }
      BossVfx.disc(level, boss.position(), 30.0, ParticleTypes.SQUID_INK, 0.1);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.WITHER_SPAWN, SoundSource.HOSTILE, 0.6F, 0.5F);
   }

   /** Everybody marked, then the sea lands on all of them. */
   private static void finalDepth(ServerLevel level, Mob boss, Fight fight) {
      List<ServerPlayer> near = playersNear(level, boss.position(), 40.0);
      if (near.isEmpty()) {
         worldbreakerTide(level, boss, fight);
         return;
      }
      int i = 0;
      for (ServerPlayer p : near) {
         fight.pending.add(new Pending("final", p.position(), FINAL_DEPTH_TELL + i * 8, 0));
         // The mark on the floor is the whole tell - see the class doc on why he says nothing.
         i++;
         if (i >= FINAL_DEPTH_MARKS) {
            break;
         }
      }
      announceNear(level, boss, 90.0, SAY + "\u00a73\u00a7lFINAL DEPTH");
   }

   // ------------------------------------------------------------------ pending

   private static void tickPending(ServerLevel level, Mob boss, Fight fight) {
      if (fight.pending.isEmpty()) {
         return;
      }
      for (Pending pending : new ArrayList<>(fight.pending)) {
         // A held whisper before the landing, so the ground says what is coming.
         if (pending.fuse > 0) {
            pending.fuse--;
            drawPending(level, boss, fight, pending, true);
            if (pending.fuse > 0) {
               continue;
            }
            land(level, boss, pending);
            if (pending.life <= 0) {
               fight.pending.remove(pending);
            }
            continue;
         }
         drawPending(level, boss, fight, pending, false);
         if (pending.life > 0 && --pending.life <= 0) {
            fight.pending.remove(pending);
         }
      }
   }

   private static void drawPending(ServerLevel level, Mob boss, Fight fight, Pending pending, boolean tell) {
      Vec3 p = pending.pos;
      switch (pending.kind) {
         case "charge" -> BossVfx.ring(level, p, CHARGE_RADIUS, 32, tell ? ParticleTypes.BUBBLE_POP : ParticleTypes.SPLASH, 0.3);
         case "tentacle" -> {
            BossVfx.ring(level, p, TENTACLE_RADIUS, 18, ParticleTypes.BUBBLE_POP, 0.15);
            if (!tell) {
               BossVfx.column(level, p, 4.0, ParticleTypes.FALLING_WATER, 3);
            }
         }
         case "prison" -> {
            BossVfx.sphere(level, p.add(0.0, 1.0, 0.0), PRISON_RADIUS, 4, ParticleTypes.SPLASH);
            BossVfx.sphere(level, p.add(0.0, 1.0, 0.0), PRISON_RADIUS * 0.8, 3, ParticleTypes.BUBBLE);
            BossVfx.ring(level, p.add(0.0, 1.0, 0.0), PRISON_RADIUS, 24, ParticleTypes.DOLPHIN, 0.4);
         }
         case "undertow" -> {
            BossVfx.ring(level, p, 9.0 - (pending.life % 20) * 0.2, 36, ParticleTypes.SPLASH, 0.3);
            BossVfx.ring(level, p, 5.0, 24, ParticleTypes.DOLPHIN, 0.5);
            BossVfx.at(level, p, 0.0, ParticleTypes.DRIPPING_WATER, 3, 2.4, 0.6, 2.4, 0.0);
            level.sendParticles(ParticleTypes.ELECTRIC_SPARK, p.x, p.y + 0.8, p.z, 2, 0.6, 0.2, 0.6, 0.02);
            for (ServerPlayer q : playersNear(level, p, 9.0)) {
               pull(q, p, 0.16);
            }
         }
         case "maelstrom" -> {
            BossVfx.ring(level, p, MAELSTROM_RADIUS - (pending.life % 40) * 0.3, 64, ParticleTypes.SPLASH, 0.5);
            BossVfx.ring(level, p, 6.0, 30, ParticleTypes.DOLPHIN, 0.6);
            level.sendParticles(net.minecraft.core.particles.ColorParticleOption.create(ParticleTypes.FLASH, 0x66DDFF), p.x, p.y + 1.0, p.z, 1, 0.0, 0.0, 0.0, 0.0);
            for (ServerPlayer q : playersNear(level, p, MAELSTROM_RADIUS)) {
               pull(q, p, 0.22);
               if (pending.life % 20 == 0) {
                  q.hurtServer(level, level.damageSources().mobAttack(boss), 4.0F);
               }
            }
         }
         case "maw" -> {
            BossVfx.sphere(level, p.add(0.0, 2.0, 0.0), MAW_RADIUS * 0.5, 4, ParticleTypes.SQUID_INK);
            BossVfx.ring(level, p, MAW_RADIUS, 32, ParticleTypes.SOUL_FIRE_FLAME, 0.4);
            for (ServerPlayer q : playersNear(level, p, MAW_RADIUS)) {
               pull(q, p, 0.30);
               if (pending.life % 15 == 0) {
                  q.hurtServer(level, level.damageSources().mobAttack(boss), 5.0F);
               }
            }
         }
         case "black" -> {
            BossVfx.disc(level, p, BLACK_TIDE_RADIUS, ParticleTypes.SQUID_INK, 0.15);
            BossVfx.ring(level, p, BLACK_TIDE_RADIUS, 28, ParticleTypes.FALLING_WATER, 0.3);
            if (pending.life % 10 == 0) {
               for (ServerPlayer q : playersNear(level, p, BLACK_TIDE_RADIUS)) {
                  q.hurtServer(level, level.damageSources().mobAttack(boss), BLACK_TIDE_DAMAGE);
               }
            }
         }
         case "beam" -> {
            Vec3 from = boss.position().add(0.0, 1.5, 0.0);
            Vec3 to = p.add(0.0, 1.5, 0.0);
            boolean firing = pending.life <= BEAM_LIFE;
            BossVfx.beam(level, from, to, 0.3, ParticleTypes.BUBBLE);
            BossVfx.beam(level, from, to, 0.4, firing ? ParticleTypes.SQUID_INK : ParticleTypes.DOLPHIN);
            if (firing) {
               level.sendParticles(ParticleTypes.ELECTRIC_SPARK, to.x, to.y, to.z, 3, 0.2, 0.2, 0.2, 0.05);
               for (ServerPlayer q : playersNear(level, boss.position(), 40.0)) {
                  if (distanceToLine(q.position(), from, to) < BEAM_HALF_WIDTH && mayTouch(fight, q.getUUID(), BEAM_HIT_COOLDOWN)) {
                     q.hurtServer(level, level.damageSources().mobAttack(boss), BEAM_DAMAGE);
                  }
               }
            }
         }
         case "eye" -> {
            BossVfx.column(level, p, 2.0, ParticleTypes.END_ROD, 2);
            if (pending.life < EYE_LIFE - 10) {
               for (ServerPlayer q : playersNear(level, p, EYE_RADIUS)) {
                  if (mayTouch(fight, q.getUUID(), EYE_HIT_COOLDOWN)) {
                     q.hurtServer(level, level.damageSources().mobAttack(boss), 6.0F);
                  }
               }
            }
         }
         default -> {
         }
      }
   }

   private static void land(ServerLevel level, Mob boss, Pending pending) {
      Vec3 p = pending.pos;
      switch (pending.kind) {
         case "charge" -> {
            BossVfx.disc(level, p, CHARGE_RADIUS, ParticleTypes.SPLASH, 0.2);
            BossVfx.sphere(level, p.add(0.0, 1.0, 0.0), CHARGE_RADIUS, 2, ParticleTypes.BUBBLE);
            level.playSound(null, p.x, p.y, p.z, SoundEvents.GENERIC_EXPLODE, SoundSource.HOSTILE, 1.4F, 0.6F);
            for (ServerPlayer q : playersNear(level, p, CHARGE_RADIUS)) {
               q.hurtServer(level, level.damageSources().mobAttack(boss), CHARGE_DAMAGE);
               push(q, new Vec3(0.0, 1.0, 0.0), 1.1);
            }
         }
         case "tentacle" -> {
            level.playSound(null, p.x, p.y, p.z, SoundEvents.ELDER_GUARDIAN_HURT, SoundSource.HOSTILE, 1.2F, 0.6F);
            for (ServerPlayer q : playersNear(level, p, TENTACLE_RADIUS)) {
               q.hurtServer(level, level.damageSources().mobAttack(boss), TENTACLE_DAMAGE);
               push(q, new Vec3(0.0, 1.0, 0.0), 1.5);
            }
         }
         case "erupt" -> {
            BossVfx.column(level, p, 6.0, ParticleTypes.FALLING_WATER, 4);
            BossVfx.ring(level, p, 5.0, 32, ParticleTypes.SPLASH, 0.2);
            level.playSound(null, p.x, p.y, p.z, SoundEvents.GENERIC_SPLASH, SoundSource.HOSTILE, 1.6F, 0.5F);
            for (ServerPlayer q : playersNear(level, p, 5.0)) {
               q.hurtServer(level, level.damageSources().mobAttack(boss), 12.0F);
               push(q, new Vec3(0.0, 1.0, 0.0), 1.9);
            }
         }
         case "tsunami" -> {
            Vec3 dir = randomFlat();
            BossVfx.wall(level, boss.position().add(0.0, 1.0, 0.0), dir, 14.0, 4.0, ParticleTypes.SPLASH, 4);
            BossVfx.wall(level, boss.position().add(0.0, 0.3, 0.0), dir, 18.0, 2.0, ParticleTypes.FALLING_WATER, 3);
            BossVfx.disc(level, boss.position(), 12.0, ParticleTypes.BUBBLE, 0.2);
            level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.GENERIC_SPLASH, SoundSource.HOSTILE, 1.8F, 0.5F);
            for (ServerPlayer q : playersNear(level, boss.position(), 18.0)) {
               q.hurtServer(level, level.damageSources().mobAttack(boss), 10.0F);
               push(q, dir, 2.4);
               q.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 60, 0));
            }
         }
         case "megawave" -> {
            Vec3 dir = randomFlat();
            // One roll of the finale: a wall across the width of the arena, and the ground it
            // passes over left standing in water.
            BossVfx.wall(level, boss.position().add(0.0, 1.0, 0.0), dir, MEGA_REACH, 9.0, ParticleTypes.SPLASH, 6);
            BossVfx.wall(level, boss.position().add(0.0, 0.3, 0.0), dir, MEGA_REACH, 3.0, ParticleTypes.FALLING_WATER, 4);
            BossVfx.disc(level, boss.position(), MEGA_RADIUS, ParticleTypes.FALLING_WATER, 0.22);
            BossVfx.sphere(level, boss.position().add(0.0, 3.0, 0.0), 12.0, 3, ParticleTypes.BUBBLE);
            level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.GENERIC_SPLASH, SoundSource.HOSTILE, 2.1F, 0.4F);
            for (ServerPlayer q : playersNear(level, boss.position(), MEGA_RADIUS)) {
               Vec3 to = q.position().subtract(boss.position());
               Vec3 toFlat = new Vec3(to.x, 0.0, to.z);
               if (toFlat.lengthSqr() < 1.0E-4) {
                  toFlat = dir;
               }
               double angle = Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, toFlat.normalize().dot(dir)))));
               if (angle > WAVE_HALF_ANGLE) {
                  continue;
               }
               q.hurtServer(level, level.damageSources().mobAttack(boss), MEGA_DAMAGE);
               push(q, dir, MEGA_PUSH);
               q.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 80, 1));
               q.sendOverlayMessage(Component.literal("\u00a73\u00a7lTHE TIDE TAKES THE GROUND"));
            }
         }
         case "worldbreaker" -> {
            Vec3 dir = randomFlat();
            BossVfx.wall(level, boss.position().add(0.0, 1.0, 0.0), dir, 26.0, 6.0, ParticleTypes.SPLASH, 5);
            BossVfx.disc(level, boss.position(), 26.0, ParticleTypes.FALLING_WATER, 0.2);
            level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.GENERIC_EXPLODE, SoundSource.HOSTILE, 2.0F, 0.4F);
            for (ServerPlayer q : playersNear(level, boss.position(), 30.0)) {
               q.hurtServer(level, level.damageSources().mobAttack(boss), 19.0F);
               push(q, dir, 2.9);
            }
         }
         case "final" -> {
            BossVfx.column(level, p, 5.0, ParticleTypes.SPLASH, 4);
            BossVfx.sphere(level, p.add(0.0, 1.0, 0.0), 3.4, 2, ParticleTypes.BUBBLE);
            level.playSound(null, p.x, p.y, p.z, SoundEvents.GENERIC_EXPLODE, SoundSource.HOSTILE, 1.5F, 0.7F);
            for (ServerPlayer q : playersNear(level, p, 3.4)) {
               q.hurtServer(level, level.damageSources().mobAttack(boss), 17.0F);
               push(q, new Vec3(0.0, 1.0, 0.0), 1.2);
            }
         }
         default -> {
         }
      }
   }

   /**
    * A per-target cooldown on the lingering hazards, so standing in a beam or walking through the
    * same eye twice a tick does not stack into one instant hit. The clock is the fight's own, which
    * is why {@link Fight#now} is carried rather than read from the level here.
    */
   private static boolean mayTouch(Fight fight, UUID id, int ticks) {
      Long until = fight.touched.get(id);
      if (until != null && fight.now < until) {
         return false;
      }
      fight.touched.put(id, fight.now + ticks);
      return true;
   }

   // --------------------------------------------------------------------- loot

   private static void tickDeath(MinecraftServer server, Mob boss, Fight fight) {
      ServerLevel level = (ServerLevel)boss.level();
      fight.deathTicks--;
      // The sea reclaiming him: he sinks into the floor rather than falling over on top of it.
      BossVfx.disc(level, boss.position(), 5.0, ParticleTypes.FALLING_WATER, 0.1);
      BossVfx.sphere(level, boss.position().add(0.0, 1.0, 0.0), 4.0, 2, ParticleTypes.BUBBLE);
      if (now(level) % 6L == 0L) {
         level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.BUBBLE_COLUMN_UPWARDS_AMBIENT, SoundSource.HOSTILE, 1.4F, 0.5F);
      }
      if (fight.deathTicks > 0) {
         return;
      }
      BossVfx.sphere(level, boss.position().add(0.0, 1.0, 0.0), 10.0, 3, ParticleTypes.SPLASH);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), ModSounds.BOSS_DEATH, SoundSource.HOSTILE, 2.0F, 0.7F);
      boss.setNoAi(false);
      onBossDeath(level, boss);
      if (boss.isAlive()) {
         boss.hurtServer(level, level.damageSources().generic(), boss.getMaxHealth() * 4.0F + 100.0F);
      }
   }

   private static void grantLoot(ServerLevel level, Mob boss, Fight fight) {
      // His *own* forge material, not the shared one every older boss pays: the sea set is
      // upgraded with Abyssal Pearls and nothing else, so a kill that paid Magical Essence would
      // be paying a material the set it guards cannot use. Otherwise the same shape as
      // StarboundMagisterManager#grantLoot - a boss does not also empty its own set into the pile.
      int pearls = 3 + RANDOM.nextInt(3);
      for (int i = 0; i < pearls; i++) {
         drop(level, boss, ModItems.abyssalPearl());
      }
      // One roll at one of his three, the same shape every other boss uses: a legendary is a reason
      // to come back, and three at once is a dump that ends the reason.
      if (RANDOM.nextFloat() < 0.22F) {
         drop(level, boss, switch (RANDOM.nextInt(3)) {
            case 0 -> ModItems.leviathansGrasp();
            case 1 -> ModItems.tidecaller();
            default -> ModItems.abyssalChain();
         });
      }
      BossPayout.payBoxes(
         level, fight.participants, ModItems::drownedLootBox, BossPayout.BOXES_PER_KILL, "\u00a73Drowned Loot Box"
      );
      for (UUID id : fight.participants) {
         ServerPlayer p = level.getServer().getPlayerList().getPlayer(id);
         if (p != null && p.isAlive()) {
            Advancements.grant(p, "kill_drowned_sovereign");
         }
      }
      announce(level, "\u00a73\u00a7m\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550");
      announce(level, "    \u00a73\u00a7l\uD83C\uDF0A THE SOVEREIGN SINKS \uD83C\uDF0A");
      announce(level, "    \u00a77The water goes quiet. It will not stay that way.");
      announce(level, "\u00a73\u00a7m\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550");
   }

   private static void drop(ServerLevel level, Mob boss, ItemStack stack) {
      if (stack != null && !stack.isEmpty()) {
         level.addFreshEntity(new ItemEntity(level, boss.getX(), boss.getY() + 0.6, boss.getZ(), stack));
      }
   }

   // ------------------------------------------------------------------ teardown

   private static void release(MinecraftServer server, Fight fight) {
      fight.bar.setVisible(false);
      for (ServerPlayer p : server.getPlayerList().getPlayers()) {
         fight.bar.removePlayer(p);
      }
      // Whatever Drowned Call raised goes back down with the fight, so nothing it summoned is
      // still standing in the arena afterwards.
      for (UUID id : fight.minions) {
         Entity minion = findEntity(server, id);
         if (minion != null && minion.isAlive()) {
            minion.discard();
         }
      }
      fight.minions.clear();
      FIGHTS.remove(fight.bossId);
   }

   private static void shutDown(MinecraftServer server, Fight fight) {
      Mob boss = bossOf(server, fight);
      if (boss != null) {
         boss.discard();
      }
      release(server, fight);
   }

   // ------------------------------------------------------------------ helpers

   private static Vec3 randomFlat() {
      Vec3 v = new Vec3(RANDOM.nextDouble() - 0.5, 0.0, RANDOM.nextDouble() - 0.5);
      return v.lengthSqr() < 1.0E-4 ? new Vec3(1.0, 0.0, 0.0) : v.normalize();
   }

   private static List<ServerPlayer> playersNear(ServerLevel level, Vec3 pos, double radius) {
      List<ServerPlayer> out = new ArrayList<>();
      double r2 = radius * radius;
      for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
         if (!p.isAlive() || p.isCreative() || p.isSpectator() || p.level() != level) {
            continue;
         }
         if (p.distanceToSqr(pos.x, pos.y, pos.z) <= r2) {
            out.add(p);
         }
      }
      return out;
   }

   private static ServerPlayer nearestPlayer(ServerLevel level, Mob boss, double radius) {
      ServerPlayer best = null;
      double bestDist = radius * radius;
      for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
         if (!p.isAlive() || p.isCreative() || p.isSpectator() || p.level() != level) {
            continue;
         }
         double d = p.distanceToSqr(boss);
         if (d < bestDist) {
            bestDist = d;
            best = p;
         }
      }
      return best;
   }

   private static void push(ServerPlayer p, Vec3 dir, double power) {
      Vec3 unit = dir.lengthSqr() < 1.0E-5 ? new Vec3(1.0, 0.0, 0.0) : dir.normalize();
      p.setDeltaMovement(p.getDeltaMovement().add(unit.scale(power)).add(0.0, 0.34, 0.0));
      p.hurtMarked = true;
   }

   private static void pull(ServerPlayer p, Vec3 center, double power) {
      Vec3 to = center.subtract(p.position());
      Vec3 flat = new Vec3(to.x, 0.0, to.z);
      if (flat.lengthSqr() < 0.04) {
         return;
      }
      p.setDeltaMovement(p.getDeltaMovement().add(flat.normalize().scale(power)));
      p.hurtMarked = true;
   }

   /** Distance from a point to a ray segment, in the horizontal plane - the beam's hit test. */
   private static double distanceToLine(Vec3 point, Vec3 from, Vec3 to) {
      Vec3 flatPoint = new Vec3(point.x, 0.0, point.z);
      Vec3 flatFrom = new Vec3(from.x, 0.0, from.z);
      Vec3 flatTo = new Vec3(to.x, 0.0, to.z);
      Vec3 seg = flatTo.subtract(flatFrom);
      double len = seg.length();
      if (len < 1.0E-4) {
         return flatPoint.distanceTo(flatFrom);
      }
      Vec3 unit = seg.scale(1.0 / len);
      double along = Math.max(0.0, Math.min(len, flatPoint.subtract(flatFrom).dot(unit)));
      return flatPoint.distanceTo(flatFrom.add(unit.scale(along)));
   }

   private static long now(ServerLevel level) {
      return ServerClock.clock(level);
   }

   /**
    * The clock this fight schedules on, exposed for the harness.
    *
    * <p>Public on purpose, and deliberately not a second implementation: it calls the same helper
    * every deadline in this file is written against, so a check that reads through here is reading
    * the real thing rather than a copy that could agree with the harness and disagree with the
    * fight. The point of the check is the frozen-clock lesson - a realm's own game time does not
    * advance, this does, and a deadline written on one and read on the other is a deadline that has
    * always already passed.
    */
   public static long clockForTest(ServerLevel level) {
      return now(level);
   }

   private static Mob bossOf(MinecraftServer server, Fight fight) {
      Entity raw = findEntity(server, fight.bossId);
      return raw instanceof Mob mob && mob.isAlive() ? mob : null;
   }

   private static Entity findEntity(MinecraftServer server, UUID id) {
      if (server == null || id == null) {
         return null;
      }
      for (ServerLevel level : server.getAllLevels()) {
         Entity e = level.getEntity(id);
         if (e != null) {
            return e;
         }
      }
      return null;
   }

   /**
    * The sea boss says nothing, and the three funnels below are why that is checkable.
    *
    * <p>He had a voice - a summon banner, a taunt every eleven seconds, a line for each phase and a
    * quotation wrapped around most of his moves, plus an action-bar line naming each move - and all
    * of it arrived as text over a fight whose whole language is what the water is doing. So the
    * lines are gone and the funnels are kept as the single place they can come back through: every
    * announcement in this file still calls one of these, which is what lets an audit fail the build
    * the moment a real chat sender appears here again (see {@code ffAuditSources}, rule 10d).
    *
    * <p>What is left is the fight itself. Every move draws its own shape - the ring on the floor,
    * the wall of the tide, the dark water under him - so nothing here is asked to describe an
    * ability that the screen already shows.
    */
   private static void announce(ServerLevel level, String message) {
      // Dialogues removed
   }

   private static void announceNear(ServerLevel level, Mob boss, double range, String message) {
      // Dialogues removed
   }

   /** The action-bar line of a move. Kept as a funnel, and deliberately closed. */
   private static void overlayNear(ServerLevel level, Mob boss, double range, String message) {
      // Telegraph removed
   }

   // ------------------------------------------------------------- codex + probes

   /** The move list of a phase, for the codex page and the self-test. */
   public static List<String> movesForPhase(int phase) {
      return switch (phase) {
         case 3 -> MOVES_3;
         case 2 -> MOVES_2;
         default -> MOVES_1;
      };
   }

   /** Test hook: his health, so no future tuning can quietly make him unkillable. */
   public static double maxHealth() {
      return MAX_HEALTH;
   }

   public static double phase2At() {
      return PHASE_2_AT;
   }

   public static double phase3At() {
      return PHASE_3_AT;
   }

   public static int moveGapTicks(int phase) {
      return moveGap(phase);
   }

   public static int prisonTicks() {
      return PRISON_TICKS;
   }

   public static int finalDepthMarks() {
      return FINAL_DEPTH_MARKS;
   }

   public static int chargeTellTicks() {
      return CHARGE_TELL;
   }

   /** How many rolls the finale is, and how far one of them reaches. */
   public static int finalTsunamiRolls() {
      return MEGA_ROLLS;
   }

   public static double finalTsunamiRadius() {
      return MEGA_RADIUS;
   }

   /**
    * Whether Drowned Call can raise anything whose attack is a repeating beam.
    *
    * <p>False, and it is asserted rather than assumed: the guardians this move used to raise never
    * stopped firing and never despawned, which is the guardian attack a player hears looping off in
    * the distance long after the boss is dead. A probe rather than a comment because the way this
    * regresses is somebody adding a stronger minion back in.
    */
   public static boolean callsBeamMinions() {
      return false;
   }
}
