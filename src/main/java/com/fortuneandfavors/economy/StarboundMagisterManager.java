package com.fortuneandfavors.economy;

import com.fortuneandfavors.ModItems;
import com.fortuneandfavors.ModSounds;
import com.fortuneandfavors.util.Chat;
import com.fortuneandfavors.util.Safe;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ColorParticleOption;
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
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * <b>The Starbound Magister</b> - a wandering caster with no arena at all.
 *
 * <h2>The design</h2>
 * Most bosses in this mod pin you into a ring and make you solve the ring. She is
 * the opposite: she keeps her distance, blinks away when you close, and the fight
 * happens wherever it happens - a forest, a village, the Nether. There is no
 * summoning circle to stand in and no border to stay inside.
 *
 * <h2>Astral Energy is the whole fight</h2>
 * From 60% health she starts <em>charging</em>: every few seconds she gains a
 * point of Astral Energy, and each point makes her spells wider, faster and
 * harder-hitting. Charge six of them and she spends all six at once on
 * <b>Judgement</b> - the sky opens along a line she chooses, and a beam sweeps
 * across it.
 *
 * <p>She used to detonate the six charges and then stand there defenceless for
 * five seconds, which made the sixth charge a reward for the player instead of a
 * threat: the best case was to let her fill up. Judgement inverts that. There is no
 * free-hit window any more - the cost of the sixth charge is that she loses every
 * charge she had and stands still to aim, so the answer is the same as it always
 * was: interrupt her before it completes, and if you cannot, keep something solid
 * between you and the line she is drawing on the ground.
 *
 * <h2>Phases</h2>
 * <ul>
 *   <li><b>I - Spellcaster</b>: Star Bolt, Comet (a marked ground strike), Astral
 *       Burst (a radial volley) and Blink.</li>
 *   <li><b>II - Overcharge</b> (60%): energy accumulates, spells scale, overload
 *       is on the table.</li>
 *   <li><b>III - Starfall</b> (25%): marked meteor rain, a Gravity Well, and her
 *       last spell - <b>Celestial Collapse</b>, which has to be out-run before it
 *       lands.</li>
 * </ul>
 *
 * <p>Bolts are tracked here rather than spawned as vanilla projectiles: they are
 * points with a trail that this class moves and collides itself, so nothing
 * inherits a wither effect, a fire tick or an arrow's pickup rules.
 */
public final class StarboundMagisterManager {

   private static final String TAG = "ff_starbound_magister";
   private static final String BOSS_NAME = "\u00a7b\u00a7l\u2728 The Starbound Magister";
   private static final String SAY = "\u00a7bThe Magister\u00a7r\u00a77 \u203a \u00a7f";

   private static final double MAX_HEALTH = 620.0;
   private static final double SEEK_RANGE = 90.0;
   /** See {@code ClockworkKingManager.STRAY_SWEEP_RADIUS}: just wider than the
    *  widest arena in the mod, and no wider. */
   public static final double STRAY_SWEEP_RADIUS = 96.0;
   /** She likes to sit about here from her target, and blinks if you get closer. */
   private static final double PREFERRED_RANGE = 13.0;
   private static final double BLINK_RANGE = 6.0;
   /** How long before she may reposition again. Long, on purpose - see the blink. */
   private static final int BLINK_COOLDOWN = 220;

   private static final int MAX_ENERGY = 6;
   private static final int ENERGY_INTERVAL = 60;
   /**
    * Judgement: how long the line is drawn before the beam fires, how long it burns,
    * how far it reaches, and how wide it is where it crosses a body.
    *
    * <p>The tell is deliberately almost as long as the beam itself. This is a fixed
    * ray across open ground, not a homing attack: two seconds of a bright line
    * sweeping back and forth is enough to read it, step out of it, or put a tree
    * between yourself and it, and not long enough to walk away from the fight.
    */
   private static final int JUDGEMENT_TELL = 44;
   private static final int JUDGEMENT_BEAM = 44;
   private static final double JUDGEMENT_RANGE = 36.0;
   private static final double JUDGEMENT_HALF_WIDTH = 1.8;
   /** Half the arc it sweeps through on its way across - about 110 degrees in all. */
   private static final double JUDGEMENT_SWEEP = 0.96;
   /** One tick of contact, and how often the same body can be caught by it. */
   private static final float JUDGEMENT_DAMAGE = 7.0F;
   private static final int JUDGEMENT_HIT_COOLDOWN = 10;
   private static final int EXHAUSTED_TICKS = 80;

   private static final float BOLT_DAMAGE = 7.0F;
   private static final float COMET_DAMAGE = 16.0F;
   private static final float COLLAPSE_DAMAGE = 34.0F;

   private static final int BOLT_COOLDOWN = 55;
   private static final int COMET_COOLDOWN = 130;
   private static final int BURST_COOLDOWN = 200;
   private static final int WELL_COOLDOWN = 260;
   private static final int COLLAPSE_COOLDOWN = 300;

   private static final int DEATH_CEREMONY_TICKS = 70;

   private static final Random RANDOM = new Random();

   /** One moving bolt. Position is authoritative; the trail is cosmetic. */
   private static final class Bolt {
      Vec3 pos;
      final Vec3 vel;
      final float damage;
      final UUID owner;
      int life;
      final int hue;

      Bolt(Vec3 pos, Vec3 vel, float damage, UUID owner, int life, int hue) {
         this.pos = pos;
         this.vel = vel;
         this.damage = damage;
         this.owner = owner;
         this.life = life;
         this.hue = hue;
      }
   }

   /** A telegraphed ground strike: shows where it will land, then lands. */
   private static final class Mark {
      final Vec3 pos;
      final double radius;
      final float damage;
      final int hue;
      int fuse;

      Mark(Vec3 pos, double radius, float damage, int hue, int fuse) {
         this.pos = pos;
         this.radius = radius;
         this.damage = damage;
         this.hue = hue;
         this.fuse = fuse;
      }
   }

   private static final class Fight {
      final UUID bossId;
      final UUID summoner;
      final ServerBossEvent bar;
      final Set<UUID> participants = new HashSet<>();
      int phase = 1;
      int energy;
      long nextEnergy;
      boolean dying;
      int deathTicks;
      long nextBolt;
      long nextComet;
      long nextBurst;
      long nextBlink;
      long nextWell;
      long nextCollapse;
      long nextTaunt;
      int collapseCharge;
      /** Judgement: the tell countdown, then the beam's burn, and where it points. */
      int judgementTell;
      int judgementBeam;
      double judgementFrom;
      double judgementTo;
      Vec3 judgementOrigin;
      /** When each body may be caught by the beam again, so contact cannot stack. */
      final Map<UUID, Long> judgementHits = new HashMap<>();
      int wellTicks;
      Vec3 wellCenter;
      final List<Bolt> bolts = new ArrayList<>();
      final List<Mark> marks = new ArrayList<>();

      Fight(UUID bossId, UUID summoner, ServerBossEvent bar) {
         this.bossId = bossId;
         this.summoner = summoner;
         this.bar = bar;
      }
   }

   private static final Map<UUID, Fight> FIGHTS = new HashMap<>();

   private StarboundMagisterManager() {
   }

   // ------------------------------------------------------------------ public API

   public static boolean isMagister(Entity entity) {
      return entity != null && entity.entityTags().contains(TAG);
   }

   public static int activeCount() {
      return FIGHTS.size();
   }

   public static int boltCount(UUID bossId) {
      Fight fight = FIGHTS.get(bossId);
      return fight == null ? 0 : fight.bolts.size();
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
         Safe.run("magister abandon", () -> shutDown(server, fight));
         ended++;
      }
      return ended;
   }

   public static void onServerStopping(MinecraftServer server) {
      for (Fight fight : new ArrayList<>(FIGHTS.values())) {
         Safe.run("magister shutdown", () -> shutDown(server, fight));
      }
      FIGHTS.clear();
   }

   /** Astral Compass right-click: follow the needle and she will be there. */
   public static String useAstralCompass(ServerPlayer player, ItemStack held) {
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
            return "You already have a Magister walking - let her finish first!";
         }
      }

      ServerLevel level = summoner.level();
      Mob boss = (Mob) EntityTypes.EVOKER.create(level, EntitySpawnReason.COMMAND);
      if (boss == null) {
         return "The stars would not answer - she did not arrive.";
      }

      AttributeInstance maxHp = boss.getAttribute(Attributes.MAX_HEALTH);
      if (maxHp != null) {
         maxHp.setBaseValue(MAX_HEALTH);
      }
      boss.setHealth((float) MAX_HEALTH);
      AttributeInstance follow = boss.getAttribute(Attributes.FOLLOW_RANGE);
      if (follow != null) {
         follow.setBaseValue(SEEK_RANGE);
      }
      AttributeInstance kb = boss.getAttribute(Attributes.KNOCKBACK_RESISTANCE);
      if (kb != null) {
         kb.setBaseValue(1.0);
      }

      boss.setPersistenceRequired();
      boss.setCustomName(Component.literal(BOSS_NAME));
      boss.setCustomNameVisible(true);
      boss.setNoAi(true);
      boss.setNoGravity(true);
      boss.addTag(TAG);
      // The shared marker plus the visible-and-persistent guarantee: see
      // BossManager.markBoss for why a boss has to say so itself.
      BossManager.markBoss(boss);
      boss.setPos(
         summoner.getX(),
         BossGrounding.groundY(level, summoner.getX(), summoner.getZ(), summoner.getY()),
         summoner.getZ()
      );
      level.addFreshEntity(boss);

      ServerBossEvent bar = new ServerBossEvent(
         UUID.randomUUID(), Component.literal(BOSS_NAME), BossBarColor.BLUE, BossBarOverlay.PROGRESS
      );
      bar.setVisible(true);
      for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
         bar.addPlayer(p);
      }

      Fight fight = new Fight(boss.getUUID(), summoner.getUUID(), bar);
      fight.participants.add(summoner.getUUID());
      long now = ServerClock.clock(level);
      fight.nextBolt = now + 40L;
      fight.nextComet = now + 120L;
      fight.nextBurst = now + 200L;
      fight.nextBlink = now + 60L;
      fight.nextEnergy = now + ENERGY_INTERVAL;
      fight.nextTaunt = now + 100L;
      FIGHTS.put(boss.getUUID(), fight);

      announce(level, "\u00a73\u00a7m\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550");
      announce(level, "    \u00a7b\u00a7l\u2728 THE STARBOUND MAGISTER \u2728");
      announce(level, "    \u00a77The sky leans down a little to listen.");
      announce(level, "    \u00a78\u201c\u00a7fYou are standing under my ceiling.\u00a78\u201d");
      announce(level, "    \u00a77\u00a7oSilence her charging or she will spend it all at once.");
      announce(level, "\u00a73\u00a7m\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550");
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), ModSounds.BOSS_SPAWN, SoundSource.HOSTILE, 1.3F, 1.5F);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.HOSTILE, 1.6F, 0.7F);
      Advancements.grant(summoner, "summon_magister");
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
         Safe.run("magister tick", () -> tickFight(server, fight, now));
      }
   }

   /**
    * Bosses whose loot has already been paid out - see
    * {@link ClockworkKingManager#onBossDeath} for why this exists. The ceremony
    * pays through this and the entity-death hook that follows it is a no-op, so
    * loot lands exactly once whichever route the boss dies by.
    */
   private static final Set<UUID> LOOT_PAID = new HashSet<>();

   /**
    * Sweeps up Magister bodies left behind by a crash or a hard restart - see
    * {@link ClockworkKingManager#sweepStrays} for the full story. Run near
    * players, because chunks are not loaded on boot.
    *
    * @return how many bodies were removed
    */
   public static int sweepStrays(MinecraftServer server) {
      int removed = 0;
      // Perf: one 257-block entity query per player, repeated verbatim for every
      // player standing together, in a single tick. Now the radius matches the
      // arena (96 >= the widest arena's 90) and a player already inside a box
      // swept this pass is skipped instead of queried again.
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
            if (isMagister(mob) && !FIGHTS.containsKey(mob.getUUID())) {
               mob.discard();
               removed++;
            }
         }
      }
      return removed;
   }

   /** Pays this boss's loot exactly once, however she dies, and releases the fight. */
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

   private static void tickFight(MinecraftServer server, Fight fight, long now) {
      Mob boss = bossOf(server, fight);
      if (boss == null) {
         shutDown(server, fight);
         return;
      }
      ServerLevel level = (ServerLevel) boss.level();

      for (ServerPlayer p : server.getPlayerList().getPlayers()) {
         if (p.level() == level && p.isAlive() && p.distanceToSqr(boss) < SEEK_RANGE * SEEK_RANGE) {
            fight.participants.add(p.getUUID());
         }
      }

      tickBolts(level, fight, boss);
      tickMarks(level, fight, boss);

      if (fight.dying) {
         tickDeath(server, boss, fight);
         return;
      }

      fight.bar.setProgress(Math.max(0.0F, Math.min(1.0F, boss.getHealth() / boss.getMaxHealth())));
      fight.bar.setName(Component.literal(barName(fight)));

      // She stands on the ground. She used to hover three blocks above the
      // summoner for the entire fight, which put her out of melee reach and made
      // her blink - a repositioning tool - a way to get further away than anyone
      // could follow. Gravity stays off so a ravine cannot swallow her, but the
      // floor under her is what decides her height.
      BossGrounding.clampToGround(level, boss, 0.9);

      float share = boss.getHealth() / boss.getMaxHealth();
      if (share <= 0.25F && fight.phase < 3) {
         enterPhase(level, boss, fight, 3);
      } else if (share <= 0.6F && fight.phase < 2) {
         enterPhase(level, boss, fight, 2);
      }

      if (fight.phase >= 2 && now >= fight.nextEnergy) {
         fight.nextEnergy = now + ENERGY_INTERVAL;
         fight.energy = Math.min(MAX_ENERGY, fight.energy + 1);
         level.sendParticles(
            ColorParticleOption.create(ParticleTypes.FLASH, hue(fight.energy)),
            boss.getX(),
            boss.getY() + 1.4,
            boss.getZ(),
            1,
            0.0,
            0.0,
            0.0,
            0.0
         );
         level.sendParticles(ParticleTypes.END_ROD, boss.getX(), boss.getY() + 1.2, boss.getZ(), 14 + fight.energy * 4, 0.6, 0.6, 0.6, 0.05);
         level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.HOSTILE, 1.0F, 0.8F + fight.energy * 0.12F);
         if (fight.energy >= MAX_ENERGY) {
            startJudgement(level, boss, fight);
            return;
         }
         announceNear(level, boss, 64.0, "&b&lAstral Energy &7" + fight.energy + "&7/&b" + MAX_ENERGY + " &8- interrupt her!");
      }

      // Judgement owns her whole body while it runs: she does not step, blink or
      // cast anything else, which is the window the move costs her.
      if (fight.judgementTell > 0 || fight.judgementBeam > 0) {
         tickJudgement(level, boss, fight, now);
         return;
      }
      if (fight.collapseCharge > 0) {
         tickCollapse(level, boss, fight);
         return;
      }
      if (fight.wellTicks > 0) {
         tickWell(level, boss, fight);
         return;
      }

      // The wander: keep her own distance instead of chasing you down.
      ServerPlayer target = nearestPlayer(boss, SEEK_RANGE);
      if (target != null) {
         maintainDistance(level, boss, fight, target, now);
      }
      chooseMove(level, boss, fight, now, target);

      if (now >= fight.nextTaunt) {
         fight.nextTaunt = now + 300L + RANDOM.nextInt(200);
         taunt(level, boss, fight);
      }
   }

   private static String barName(Fight fight) {
      String phase = fight.phase == 1 ? "Phase I" : fight.phase == 2 ? "Phase II" : "Phase III";
      String energy = fight.phase >= 2 ? " \u00a78| \u00a7b" + fight.energy + "/" + MAX_ENERGY + " energy" : "";
      if (fight.judgementTell > 0 || fight.judgementBeam > 0) {
         energy = energy + " \u00a78| \u00a7c\u00a7lJUDGEMENT";
      }
      return BOSS_NAME + " \u00a78| \u00a7f" + phase + energy;
   }

   /**
    * She keeps roughly {@link #PREFERRED_RANGE} between herself and her target,
    * and blinks outright if anyone gets inside {@link #BLINK_RANGE}.
    *
    * <p>What she no longer does is <b>run</b>. The band used to be a strafe - inside it she
    * circled sideways every single tick - so a fight against her was a chase with no
    * moment to swing, and the report "she will not stop running" is what that looks like
    * from the arena. Inside the band she now holds her ground and casts: she still backs off
    * if you close and steps in if you run, which is what keeps her a duelist, but the
    * decision is per distance rather than per tick, and the step itself is slow enough to
    * be walked down.
    */
   private static void maintainDistance(ServerLevel level, Mob boss, Fight fight, ServerPlayer target, long now) {
      Vec3 toTarget = target.position().subtract(boss.position());
      double dist = toTarget.length();
      if (dist >= 0.01) {
         Vec3 unit = toTarget.scale(1.0 / dist);
         Vec3 wish;
         if (dist < PREFERRED_RANGE - 3.0) {
            wish = unit.scale(-1.0);
         } else if (dist > PREFERRED_RANGE + 3.0) {
            wish = unit;
         } else {
            // Standing in her band: no step at all. This is the change that stops the
            // fight being a footrace - she is a caster, and a caster who has her range
            // has nothing to do but cast.
            wish = Vec3.ZERO;
         }
         // Moved by position, not by delta movement: a no-AI puppet never runs
         // its own physics step, exactly like the Clockwork machines. Feeding
         // setDeltaMovement here would have left her hovering in place.
         double speed = 0.085 + fight.energy * 0.005;
         double x = boss.getX() + wish.x * speed;
         double z = boss.getZ() + wish.z * speed;
         // Follow the ground rather than a sine wave: at a fixed altitude a
         // scripted caster walks into hills and out over cliffs.
         double y = BossGrounding.groundY(level, x, z, boss.getY());
         boss.setPos(x, y, z);
         boss.hurtMarked = true;
         level.sendParticles(ParticleTypes.END_ROD, x, y + 0.6, z, 2, 0.25, 0.2, 0.25, 0.0);
      }

      // The blink is her answer to being cornered, and nothing else. It is on a long
      // cooldown and it puts her back inside her own casting band rather than out of
      // it, so it is a way to stop being in melee, not a way to leave the fight: a
      // player who sprints after her is on top of her again in under two seconds.
      if (dist <= BLINK_RANGE && now >= fight.nextBlink) {
         fight.nextBlink = now + BLINK_COOLDOWN;
         blink(level, boss, target);
      }
   }

   private static void blink(ServerLevel level, Mob boss, ServerPlayer target) {
      double angle = RANDOM.nextDouble() * Math.PI * 2.0;
      double dist = PREFERRED_RANGE - 1.0 + RANDOM.nextDouble() * 3.0;
      double x = target.getX() + Math.cos(angle) * dist;
      double z = target.getZ() + Math.sin(angle) * dist;
      // Blink onto the ground, not into the sky. Landing two to four blocks up
      // made every reposition a free escape from melee.
      double y = BossGrounding.groundY(level, x, z, target.getY());
      Vec3 from = boss.position();
      level.sendParticles(ParticleTypes.REVERSE_PORTAL, from.x, from.y + 1.0, from.z, 40, 0.4, 0.6, 0.4, 0.1);
      level.sendParticles(ParticleTypes.WITCH, from.x, from.y + 1.0, from.z, 24, 0.4, 0.6, 0.4, 0.05);
      boss.setPos(x, y, z);
      boss.setDeltaMovement(Vec3.ZERO);
      boss.hurtMarked = true;
      level.sendParticles(ParticleTypes.END_ROD, x, y + 1.0, z, 40, 0.4, 0.6, 0.4, 0.12);
      level.sendParticles(ColorParticleOption.create(ParticleTypes.FLASH, 0x88DDFF), x, y + 1.0, z, 1, 0.0, 0.0, 0.0, 0.0);
      level.playSound(null, x, y, z, SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE, 1.2F, 1.6F);
   }

   // --------------------------------------------------------------------- moves

   private static void chooseMove(ServerLevel level, Mob boss, Fight fight, long now, ServerPlayer target) {
      if (target == null) {
         return;
      }
      if (fight.phase >= 3 && now >= fight.nextCollapse) {
         fight.nextCollapse = now + COLLAPSE_COOLDOWN;
         startCollapse(level, boss, fight, target);
         return;
      }
      if (fight.phase >= 3 && now >= fight.nextWell) {
         fight.nextWell = now + WELL_COOLDOWN;
         startWell(level, boss, fight, target);
         return;
      }
      if (now >= fight.nextBurst) {
         fight.nextBurst = now + BURST_COOLDOWN;
         astralBurst(level, boss, fight);
         return;
      }
      if (now >= fight.nextComet) {
         fight.nextComet = now + COMET_COOLDOWN;
         comet(level, boss, fight, target);
         return;
      }
      if (now >= fight.nextBolt) {
         fight.nextBolt = now + BOLT_COOLDOWN;
         starBolt(level, boss, fight, target);
      }
   }

   private static void starBolt(ServerLevel level, Mob boss, Fight fight, ServerPlayer target) {
      int count = 3 + fight.energy;
      for (int i = 0; i < count; i++) {
         Vec3 from = boltOrigin(boss, i, count);
         Vec3 to = target.position().add(0.0, target.getBbHeight() * 0.5, 0.0);
         Vec3 dir = to.subtract(from);
         if (dir.lengthSqr() < 1.0E-4) {
            dir = new Vec3(0.0, 0.0, 1.0);
         }
         dir = dir.normalize();
         fight.bolts.add(new Bolt(from, dir.scale(0.85), BOLT_DAMAGE, boss.getUUID(), 90, 0x88DDFF));
      }
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.EVOKER_CAST_SPELL, SoundSource.HOSTILE, 1.1F, 1.4F);
   }

   private static void astralBurst(ServerLevel level, Mob boss, Fight fight) {
      int count = 10 + fight.energy * 3;
      Vec3 from = boss.position().add(0.0, boss.getBbHeight() * 0.6, 0.0);
      for (int i = 0; i < count; i++) {
         double angle = i * (Math.PI * 2.0 / count);
         Vec3 dir = new Vec3(Math.cos(angle), 0.06, Math.sin(angle)).normalize();
         fight.bolts.add(new Bolt(from, dir.scale(0.7), BOLT_DAMAGE * 0.8F, boss.getUUID(), 70, 0xD9A0FF));
      }
      level.sendParticles(ColorParticleOption.create(ParticleTypes.FLASH, 0xBB88FF), from.x, from.y, from.z, 1, 0.0, 0.0, 0.0, 0.0);
      level.sendParticles(ParticleTypes.END_ROD, from.x, from.y, from.z, 60, 1.4, 1.0, 1.4, 0.2);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.EVOKER_PREPARE_ATTACK, SoundSource.HOSTILE, 1.4F, 1.2F);
      announceNear(level, boss, 64.0, "&b&lASTRAL BURST&7 - she throws the sky outward!");
   }

   private static void comet(ServerLevel level, Mob boss, Fight fight, ServerPlayer target) {
      Vec3 at = target.position();
      double radius = 4.0;
      fight.marks.add(new Mark(at, radius, COMET_DAMAGE, 0xFFCC55, 30));
      level.playSound(null, at.x, at.y, at.z, SoundEvents.RESPAWN_ANCHOR_CHARGE, SoundSource.HOSTILE, 1.0F, 1.2F);
      announceNear(level, boss, 64.0, "&e\u2604 Comet inbound&7 - leave the ring!");
   }

   // --------------------------------------------------------------- judgement

   /**
    * The sixth charge, spent all at once: she stops, draws a line, and cuts along it.
    *
    * <p>Three things make it answerable rather than lethal. The line is on the ground
    * for two seconds before anything happens. It is a <b>ray</b>, so it stops at the
    * first block of anything solid - a tree, a boulder, a hill, a wall somebody built - 
    * and it cannot be steered, only swept across a fixed arc. And she cannot walk, blink
    * or cast while it runs, so the whole move is her standing in the open with a target
    * painted on her.
    */
   private static void startJudgement(ServerLevel level, Mob boss, Fight fight) {
      ServerPlayer target = nearestPlayer(boss, SEEK_RANGE);
      fight.energy = 0;
      if (target == null) {
         // Nobody to judge. She keeps the pause and starts charging again rather than
         // firing the beam into a forest.
         fight.nextEnergy = ServerClock.clock(level) + ENERGY_INTERVAL;
         return;
      }
      fight.judgementOrigin = boss.position().add(0.0, boss.getBbHeight() * 0.65, 0.0);
      Vec3 dir = target.position().subtract(boss.position());
      double aim = dir.lengthSqr() < 1.0E-4 ? 0.0 : Math.atan2(dir.z, dir.x);
      fight.judgementFrom = aim - JUDGEMENT_SWEEP;
      fight.judgementTo = aim + JUDGEMENT_SWEEP;
      fight.judgementTell = JUDGEMENT_TELL;
      fight.judgementHits.clear();
      announce(level, SAY + "\"\u00a7cSix. \u00a7fAll of it, along that line. Do not be on it.\"");
      announceNear(level, boss, 72.0, "&c&l\u26a0 JUDGEMENT&7 - get off the line, or get behind something!");
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.WARDEN_SONIC_CHARGE, SoundSource.HOSTILE, 2.0F, 0.7F);
   }

   /** The tell, then the beam. Called from the fight tick while either is running. */
   private static void tickJudgement(ServerLevel level, Mob boss, Fight fight, long now) {
      if (fight.judgementTell > 0) {
         fight.judgementTell--;
         drawJudgementLine(level, boss, fight, true);
         if (fight.judgementTell % 8 == 0) {
            level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.HOSTILE, 1.4F, 0.6F + (1.0F - (float)fight.judgementTell / JUDGEMENT_TELL) * 1.6F);
         }
         if (fight.judgementTell <= 0) {
            fight.judgementBeam = JUDGEMENT_BEAM;
            level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.WARDEN_SONIC_BOOM, SoundSource.HOSTILE, 3.0F, 1.1F);
            level.sendParticles(ColorParticleOption.create(ParticleTypes.FLASH, 0xCCEEFF), boss.getX(), boss.getY() + 1.2, boss.getZ(), 1, 0.0, 0.0, 0.0, 0.0);
         }
         return;
      }

      fight.judgementBeam--;
      double progress = 1.0 - (double)fight.judgementBeam / JUDGEMENT_BEAM;
      double angle = fight.judgementFrom + (fight.judgementTo - fight.judgementFrom) * progress;
      Vec3 dir = new Vec3(Math.cos(angle), 0.0, Math.sin(angle));
      // The ray starts at her hands and is re-anchored to her every tick, so the beam
      // follows her if something moves her - it just cannot be aimed.
      Vec3 from = boss.position().add(0.0, boss.getBbHeight() * 0.65, 0.0);
      fight.judgementOrigin = from;
      double reach = drawBeam(level, from, dir);

      for (ServerPlayer p : participantsNear(level, boss, JUDGEMENT_RANGE + 4.0)) {
         Vec3 rel = p.position().add(0.0, p.getBbHeight() * 0.5, 0.0).subtract(from);
         if (Math.abs(rel.y) > 2.0) {
            continue;
         }
         double along = rel.x * dir.x + rel.z * dir.z;
         if (along < 0.0 || along > reach) {
            continue;
         }
         double lateral = Math.abs(rel.x * dir.z - rel.z * dir.x);
         if (lateral > JUDGEMENT_HALF_WIDTH) {
            continue;
         }
         Long ready = fight.judgementHits.get(p.getUUID());
         if (ready != null && now < ready) {
            continue;
         }
         fight.judgementHits.put(p.getUUID(), now + JUDGEMENT_HIT_COOLDOWN);
         p.hurtServer(level, level.damageSources().mobAttack(boss), JUDGEMENT_DAMAGE);
         // Pushed along the beam, not away from her: the sweep carries you with it,
         // which is what makes standing in it feel like being swept rather than hit.
         p.push(dir.x * 0.9, 0.18, dir.z * 0.9);
         p.hurtMarked = true;
         level.sendParticles(ParticleTypes.CRIT, p.getX(), p.getY() + 1.0, p.getZ(), 12, 0.3, 0.4, 0.3, 0.06);
      }

      if (fight.judgementBeam <= 0) {
         fight.judgementHits.clear();
         // Spent: every charge is gone, and the pause before she can start gathering
         // again is twice her usual interval.
         fight.nextEnergy = now + ENERGY_INTERVAL * 2L;
         announceNear(level, boss, 72.0, "&b\u2600 The line goes out&7 - &fevery charge is spent. Hit her.");
         level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.BEACON_DEACTIVATE, SoundSource.HOSTILE, 2.0F, 1.5F);
      }
   }

   /** The line she is about to fire, drawn along the ground while the tell runs. */
   private static void drawJudgementLine(ServerLevel level, Mob boss, Fight fight, boolean tell) {
      double angle = fight.judgementFrom;
      Vec3 dir = new Vec3(Math.cos(angle), 0.0, Math.sin(angle));
      Vec3 from = boss.position().add(0.0, boss.getBbHeight() * 0.65, 0.0);
      double reach = 0.0;
      for (double d = 0.0; d < JUDGEMENT_RANGE; d += 0.5) {
         Vec3 point = from.add(dir.scale(d));
         if (!level.getBlockState(BlockPos.containing(point)).isAir()) {
            break;
         }
         reach = d;
      }
      double heat = 1.0 - (double)fight.judgementTell / JUDGEMENT_TELL;
      for (double d = 0.0; d <= reach; d += 1.2) {
         Vec3 point = from.add(dir.scale(d));
         level.sendParticles(ParticleTypes.END_ROD, point.x, boss.getY() + 0.15, point.z, 1, 0.05, 0.0, 0.05, 0.0);
         if (heat > 0.5) {
            level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, point.x, boss.getY() + 0.15, point.z, 1, 0.05, 0.02, 0.05, 0.0);
         }
      }
   }

   /**
    * Draws the beam and returns how far it actually got before something blocked it.
    *
    * <p>The blocking check is the whole reason the beam is fair: the reach it returns
    * is the reach the damage uses, so cover is real cover rather than a visual effect
    * on top of an attack that still hits through a hill.
    */
   private static double drawBeam(ServerLevel level, Vec3 from, Vec3 dir) {
      double reach = 0.0;
      boolean first = true;
      for (double d = 0.0; d < JUDGEMENT_RANGE; d += 0.4) {
         Vec3 point = from.add(dir.scale(d));
         if (!level.getBlockState(BlockPos.containing(point)).isAir()) {
            level.sendParticles(ParticleTypes.CRIT, point.x, point.y, point.z, 8, 0.2, 0.2, 0.2, 0.05);
            break;
         }
         reach = d;
         level.sendParticles(ParticleTypes.END_ROD, point.x, point.y, point.z, first ? 3 : 1, 0.18, 0.18, 0.18, 0.0);
         level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, point.x, point.y, point.z, 1, 0.12, 0.12, 0.12, 0.0);
         level.sendParticles(ColorParticleOption.create(ParticleTypes.FLASH, 0xCCEEFF), point.x, point.y, point.z, 1, 0.0, 0.0, 0.0, 0.0);
         first = false;
      }
      return reach;
   }

   private static void startWell(ServerLevel level, Mob boss, Fight fight, ServerPlayer target) {
      fight.wellCenter = target.position();
      fight.wellTicks = 70;
      announceNear(level, boss, 64.0, "&5&lGRAVITY WELL&7 - it is dragging you in!");
      level.playSound(null, fight.wellCenter.x, fight.wellCenter.y, fight.wellCenter.z, SoundEvents.ENDER_DRAGON_FLAP, SoundSource.HOSTILE, 1.2F, 0.7F);
   }

   private static void tickWell(ServerLevel level, Mob boss, Fight fight) {
      fight.wellTicks--;
      Vec3 c = fight.wellCenter;
      for (int i = 0; i < 3; i++) {
         double angle = RANDOM.nextDouble() * Math.PI * 2.0;
         double r = 5.0 + RANDOM.nextDouble() * 3.0;
         double x = c.x + Math.cos(angle) * r;
         double z = c.z + Math.sin(angle) * r;
         Vec3 inward = new Vec3(c.x - x, c.y + 1.0 - (c.y + 1.0), c.z - z).normalize().scale(r / 12.0);
         level.sendParticles(ParticleTypes.REVERSE_PORTAL, x, c.y + 1.0, z, 1, inward.x, inward.y, inward.z, 0.0);
      }
      level.sendParticles(ParticleTypes.PORTAL, c.x, c.y + 1.0, c.z, 12, 1.2, 1.2, 1.2, 0.1);

      for (ServerPlayer p : participantsNear(level, boss, 20.0)) {
         Vec3 pull = c.add(0.0, 1.0, 0.0).subtract(p.position());
         double len = pull.length();
         if (len < 1.5) {
            continue;
         }
         Vec3 unit = pull.scale(1.0 / len);
         p.push(unit.x * 0.42, 0.06, unit.z * 0.42);
         p.hurtMarked = true;
         p.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 30, 0, false, true, true));
      }
      if (fight.wellTicks <= 0) {
         level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, c.x, c.y + 0.5, c.z, 3, 1.0, 0.5, 1.0, 0.0);
         level.playSound(null, c.x, c.y, c.z, SoundEvents.GENERIC_EXPLODE, SoundSource.HOSTILE, 1.6F, 0.8F);
         for (ServerPlayer p : playersNear(level, c.x, c.y, c.z, 7.0)) {
            p.hurtServer(level, level.damageSources().mobAttack(boss), 9.0F);
         }
      }
   }

   /** Her last spell: a long telegraph you have to walk out of, then an opening. */
   private static void startCollapse(ServerLevel level, Mob boss, Fight fight, ServerPlayer target) {
      fight.collapseCharge = 60;
      fight.marks.add(new Mark(target.position(), 6.5, COLLAPSE_DAMAGE, 0xFFE08A, 60));
      level.sendParticles(ColorParticleOption.create(ParticleTypes.FLASH, 0xFFF3C4), boss.getX(), boss.getY() + 1.4, boss.getZ(), 1, 0.0, 0.0, 0.0, 0.0);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.BEACON_ACTIVATE, SoundSource.HOSTILE, 2.0F, 0.6F);
      announce(level, SAY + "\"\u00a7fLAST SPELL. \u00a7bCELESTIAL COLLAPSE\u00a7f. Keep moving.\"");
   }

   private static void tickCollapse(ServerLevel level, Mob boss, Fight fight) {
      fight.collapseCharge--;
      double r = 2.0 + (60 - fight.collapseCharge) * 0.1;
      for (int i = 0; i < 40; i++) {
         double a = i * (Math.PI * 2.0 / 40.0) + fight.collapseCharge * 0.06;
         level.sendParticles(ParticleTypes.END_ROD, boss.getX() + Math.cos(a) * r, boss.getY() + 1.2, boss.getZ() + Math.sin(a) * r, 1, 0.0, 0.0, 0.0, 0.0);
      }
      boss.addEffect(new MobEffectInstance(MobEffects.GLOWING, 8, 0, false, false, false));
      if (fight.collapseCharge > 0) {
         return;
      }
      // Landing the spell empties her completely: the cost of her best move is
      // the window you get to punish it.
      boss.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, EXHAUSTED_TICKS, 1, false, true, true));
      boss.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, EXHAUSTED_TICKS, 1, false, true, true));
      announceNear(level, boss, 64.0, "&b\u2600 She is spent&7 - &fhit her now!");
   }

   // --------------------------------------------------------------------- phases

   private static void enterPhase(ServerLevel level, Mob boss, Fight fight, int phase) {
      fight.phase = phase;
      if (phase == 2) {
         announce(level, SAY + "\"\u00a7fFine. Let us make this \u00a7binteresting\u00a7f.\"");
         announceNear(level, boss, 72.0, "&b&lOVERCHARGE&7 - Astral Energy is building. Interrupt her.");
         fight.nextEnergy = ServerClock.clock(level) + 40L;
      } else {
         announce(level, SAY + "\"\u00a7bThen the sky can \u00a7ldelete\u00a7b this island. I do not mind.\"");
         announceNear(level, boss, 72.0, "&b&lSTARFALL&7 - the sky is falling. Keep moving.");
         fight.energy = MAX_ENERGY;
      }
      level.sendParticles(ParticleTypes.END_ROD, boss.getX(), boss.getY() + 1.5, boss.getZ(), 120, 4.0, 2.0, 4.0, 0.25);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.BEACON_ACTIVATE, SoundSource.HOSTILE, 2.0F, 1.4F);
   }

   private static void taunt(ServerLevel level, Mob boss, Fight fight) {
      String line = switch (fight.phase) {
         case 1 -> "\u00a78\u201c\u00a7fYou are very small from up here.\u00a78\u201d";
         case 2 -> "\u00a78\u201c\u00a7fCareful. I am filling up.\u00a78\u201d";
         default -> "\u00a78\u201c\u00a7fEvery star you have ever wished on was one of mine.\u00a78\u201d";
      };
      announce(level, SAY + "\"" + line + "\"");
   }

   // ------------------------------------------------------------------ projectiles

   private static Vec3 boltOrigin(Mob boss, int index, int count) {
      double spread = (index - (count - 1) / 2.0) * 0.45;
      Vec3 side = new Vec3(Math.cos(spread), 0.0, Math.sin(spread));
      return boss.position().add(side.x * 0.9, boss.getBbHeight() * 0.65, side.z * 0.9);
   }

   /** Moves every bolt, draws its trail, and resolves impacts. */
   private static void tickBolts(ServerLevel level, Fight fight, Mob boss) {
      if (fight.bolts.isEmpty()) {
         return;
      }
      for (Iterator<Bolt> it = fight.bolts.iterator(); it.hasNext();) {
         Bolt bolt = it.next();
         // Deliberately a fast, small step: at 0.85 blocks a tick a bolt can
         // otherwise tunnel through a player standing still.
         int steps = 4;
         boolean done = false;
         for (int s = 0; s < steps && !done; s++) {
            bolt.pos = bolt.pos.add(bolt.vel.scale(1.0 / steps));
            level.sendParticles(ParticleTypes.END_ROD, bolt.pos.x, bolt.pos.y, bolt.pos.z, 1, 0.02, 0.02, 0.02, 0.0);
            level.sendParticles(ParticleTypes.GLOW, bolt.pos.x, bolt.pos.y, bolt.pos.z, 1, 0.02, 0.02, 0.02, 0.0);
            if (!level.getBlockState(net.minecraft.core.BlockPos.containing(bolt.pos)).isAir()) {
               level.sendParticles(ParticleTypes.CRIT, bolt.pos.x, bolt.pos.y, bolt.pos.z, 8, 0.2, 0.2, 0.2, 0.05);
               level.playSound(null, bolt.pos.x, bolt.pos.y, bolt.pos.z, SoundEvents.AMETHYST_BLOCK_HIT, SoundSource.HOSTILE, 0.6F, 1.6F);
               done = true;
               break;
            }
            for (ServerPlayer p : playersNear(level, bolt.pos.x, bolt.pos.y, bolt.pos.z, 1.1)) {
               p.hurtServer(level, level.damageSources().mobAttack(boss), bolt.damage);
               level.sendParticles(ParticleTypes.CRIT, p.getX(), p.getY() + 1.0, p.getZ(), 12, 0.3, 0.3, 0.3, 0.08);
               done = true;
               break;
            }
         }
         bolt.life--;
         if (done || bolt.life <= 0) {
            it.remove();
         }
      }
   }

   /** Runs every mark's fuse and lands it. */
   private static void tickMarks(ServerLevel level, Fight fight, Mob boss) {
      if (fight.marks.isEmpty()) {
         return;
      }
      for (Iterator<Mark> it = fight.marks.iterator(); it.hasNext();) {
         Mark mark = it.next();
         int points = 24;
         for (int i = 0; i < points; i++) {
            double a = i * (Math.PI * 2.0 / points) + mark.fuse * 0.08;
            level.sendParticles(
               ColorParticleOption.create(ParticleTypes.FLASH, mark.hue),
               mark.pos.x + Math.cos(a) * mark.radius,
               mark.pos.y + 0.15,
               mark.pos.z + Math.sin(a) * mark.radius,
               1,
               0.0,
               0.0,
               0.0,
               0.0
            );
         }
         mark.fuse--;
         if (mark.fuse > 0) {
            continue;
         }
         it.remove();
         level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, mark.pos.x, mark.pos.y + 0.4, mark.pos.z, 3, 0.8, 0.4, 0.8, 0.0);
         level.sendParticles(ParticleTypes.GUST, mark.pos.x, mark.pos.y + 0.4, mark.pos.z, 30, mark.radius * 0.6, 0.4, mark.radius * 0.6, 0.15);
         level.sendParticles(ParticleTypes.END_ROD, mark.pos.x, mark.pos.y + 0.4, mark.pos.z, 60, mark.radius * 0.7, 0.6, mark.radius * 0.7, 0.2);
         level.playSound(null, mark.pos.x, mark.pos.y, mark.pos.z, ModSounds.BOSS_SLAM, SoundSource.HOSTILE, 1.8F, 1.0F);
         for (ServerPlayer p : playersNear(level, mark.pos.x, mark.pos.y, mark.pos.z, mark.radius)) {
            p.hurtServer(level, level.damageSources().mobAttack(boss), mark.damage);
            p.push(0.0, 0.6, 0.0);
            p.hurtMarked = true;
         }
      }
   }

   // -------------------------------------------------------------- lethal blow

   public static Boolean onLethalDamage(Entity entity, float amount) {
      if (!isMagister(entity) || !(entity instanceof Mob boss) || !(boss.level() instanceof ServerLevel level)) {
         return null;
      }
      Fight fight = FIGHTS.get(boss.getUUID());
      if (fight == null) {
         return null;
      }
      if (fight.dying) {
         return Boolean.FALSE;
      }
      if (boss.getHealth() - amount > 0.5F) {
         return null;
      }
      fight.dying = true;
      fight.deathTicks = DEATH_CEREMONY_TICKS;
      boss.setHealth(1.0F);
      boss.setNoAi(true);
      fight.bar.setProgress(0.0F);
      fight.bolts.clear();
      fight.marks.clear();
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.BEACON_DEACTIVATE, SoundSource.HOSTILE, 2.0F, 1.4F);
      announce(level, SAY + "\"\u00a7f...oh. \u00a7bYou actually looked up.\"");
      // FALSE cancels the blow (Fabric: true means "allow the damage"). Returning
      // TRUE here let the killing hit land, which ran vanilla's die() immediately,
      // skipped the whole ceremony and - because grantLoot only runs from
      // tickDeath - meant she dropped literally nothing.
      return Boolean.FALSE;
   }

   private static void tickDeath(MinecraftServer server, Mob boss, Fight fight) {
      ServerLevel level = (ServerLevel) boss.level();
      fight.deathTicks--;
      level.sendParticles(ParticleTypes.END_ROD, boss.getX(), boss.getY() + 1.4, boss.getZ(), 20, 1.4, 1.2, 1.4, 0.12);
      level.sendParticles(ParticleTypes.GLOW, boss.getX(), boss.getY() + 1.4, boss.getZ(), 12, 1.2, 1.0, 1.2, 0.05);
      if (fight.deathTicks > 0) {
         return;
      }
      level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, boss.getX(), boss.getY() + 1.0, boss.getZ(), 8, 2.0, 1.5, 2.0, 0.15);
      level.sendParticles(ParticleTypes.END_ROD, boss.getX(), boss.getY() + 1.0, boss.getZ(), 300, 6.0, 3.0, 6.0, 0.35);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), ModSounds.BOSS_DEATH, SoundSource.HOSTILE, 2.0F, 1.3F);
      boss.setNoAi(false);
      onBossDeath(level, boss);
      boss.hurtServer(level, level.damageSources().generic(), boss.getMaxHealth() * 4.0F + 100.0F);
   }

   // --------------------------------------------------------------------- loot

   private static void grantLoot(ServerLevel level, Mob boss, Fight fight) {
      drop(level, boss, ModItems.starboundTrophy());
      // Forge material only - the amethyst, lapis and pearls that used to fall
      // alongside it were there to make the pile look bigger, and a boss that
      // empties its own crafting set is a boss you only need to kill once.
      int essence = 2 + RANDOM.nextInt(3);
      for (int i = 0; i < essence; i++) {
         drop(level, boss, ModItems.magicalEssence());
      }
      // Starfall is hers: the same star she calls down, handed to the player.
      if (RANDOM.nextFloat() < 0.15F) {
         drop(level, boss, CustomEnchantments.tome(CustomEnchantments.STARFALL, 1 + RANDOM.nextInt(3)));
      }

      // One roll at one legendary. Three of them, on every kill, is a dump.
      if (RANDOM.nextFloat() < 0.2F) {
         drop(level, boss, switch (RANDOM.nextInt(3)) {
            case 0 -> ModItems.starpiercer();
            case 1 -> ModItems.astralMantle();
            default -> ModItems.magistersCodex();
         });
      }

      BossPayout.payBoxes(level, fight.participants, ModItems::starboundLootBox, BossPayout.BOXES_PER_KILL, "\u00a7bStarbound Loot Box");
      for (UUID id : fight.participants) {
         ServerPlayer p = level.getServer().getPlayerList().getPlayer(id);
         if (p != null && p.isAlive()) {
            Advancements.grant(p, "kill_magister");
         }
      }
      announce(level, "\u00a73\u00a7m\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550");
      announce(level, "    \u00a7b\u00a7l\u2728 THE MAGISTER FALLS SILENT \u2728");
      announce(level, "\u00a73\u00a7m\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550");
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

   private static int hue(int energy) {
      int[] hues = {0x88DDFF, 0x99CCFF, 0xAABBFF, 0xBBA0FF, 0xCC99FF, 0xDD88FF, 0xFFCC66};
      return hues[Math.max(0, Math.min(hues.length - 1, energy))];
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

   private static ServerPlayer nearestPlayer(Mob from, double range) {
      if (!(from.level() instanceof ServerLevel level)) {
         return null;
      }
      ServerPlayer best = null;
      double bestDist = range * range;
      for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
         if (!p.isAlive() || p.isCreative() || p.isSpectator() || p.level() != level) {
            continue;
         }
         double d = p.distanceToSqr(from);
         if (d < bestDist) {
            bestDist = d;
            best = p;
         }
      }
      return best;
   }

   private static List<ServerPlayer> participantsNear(ServerLevel level, Entity at, double range) {
      return playersNear(level, at.getX(), at.getY(), at.getZ(), range);
   }

   private static List<ServerPlayer> playersNear(ServerLevel level, double x, double y, double z, double range) {
      List<ServerPlayer> out = new ArrayList<>();
      double r2 = range * range;
      for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
         if (!p.isAlive() || p.isCreative() || p.isSpectator() || p.level() != level) {
            continue;
         }
         if (p.distanceToSqr(x, y, z) <= r2) {
            out.add(p);
         }
      }
      return out;
   }

   private static void announce(ServerLevel level, String message) {
      if (!BossChat.allowed("starbound", message)) {
         return;
      }
      for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
         Chat.raw(p, message);
      }
   }

   private static void announceNear(ServerLevel level, Mob boss, double range, String message) {
      if (!BossChat.allowed("starbound", message)) {
         return;
      }
      double r2 = range * range;
      for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
         if (p.level() == boss.level() && p.distanceToSqr(boss) <= r2) {
            Chat.raw(p, message);
         }
      }
   }

   /** Test hook: energy needed to spend on a Judgement. */
   public static int maxEnergy() {
      return MAX_ENERGY;
   }

   /** Test hook: how long the beam burns once the sixth charge is spent. */
   public static int judgementTicks() {
      return JUDGEMENT_BEAM;
   }

   /**
    * Test hook: how long the line is on the ground before the beam fires.
    *
    * <p>This number is the difference between a mechanic and an ambush. It is
    * deliberately close to the beam's own length, and the suite pins that: a tell
    * shorter than a player's reaction time would make the sixth charge unfair rather
    * than hard, and the beam would still be reachable by every tuning pass after this
    * one.
    */
   public static int judgementTellTicks() {
      return JUDGEMENT_TELL;
   }

   /** Test hook: how far the beam reaches before something stops it. */
   public static double judgementRange() {
      return JUDGEMENT_RANGE;
   }

   /** Test hook: her health, so no future tuning can quietly make her unkillable. */
   public static double maxHealth() {
      return MAX_HEALTH;
   }
}
