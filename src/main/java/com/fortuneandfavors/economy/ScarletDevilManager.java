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
import net.minecraft.core.particles.DustParticleOptions;
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
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * <b>The Scarlet Devil</b> - the raid boss called through by the Scarlet-Blood.
 *
 * <p>Like the Time Lord she is a {@code setNoAi(true)} puppet: the silhouette is
 * a phantom, but every action (the swoop, the bloodsuck, the spears, the bat
 * swarm, the phase-two blood rain, both ceremonies) is performed by this file's
 * tick. That keeps her move set deterministic and testable instead of leaning on
 * mob AI that the arena's terrain would break.
 *
 * <h2>Her death is not a death</h2>
 * The killing blow is cancelled (see {@link #onLethalDamage}) and played out as a
 * ceremony, exactly like the Time Lord: she refuses to fall, rains one last time,
 * and only then comes apart. Loot is granted once, at the end of that ceremony.
 *
 * <h2>The blood claim</h2>
 * Dying inside her arena does <i>not</i> cost you your gear. Her blood claims the
 * body: a bone servant rises wearing a <b>copy</b> of your armour and carrying a
 * copy of your weapon, fighting for her until it is destroyed - and it drops
 * nothing, because it never held anything real. You keep every item you had, and
 * Nice Keep Inventory plus the ordinary drop rules apply exactly as they would to
 * any other death. That plumbing lives in {@code BossManager} (look-alike capture,
 * servant rise, boss bar, targeting); {@link #claimsDeath} is only the question
 * "was this player one of hers".
 */
public final class ScarletDevilManager {
   /** Entity tag marking the Scarlet Devil. Kept in sync with {@link #isScarletDevil}. */
   private static final String TAG = "ff_scarlet_devil";
   private static final String BOSS_NAME = "\u00a74\u00a7lThe Scarlet Devil";
   private static final String SAY = "\u00a74The Scarlet Devil\u00a7r\u00a77 \u203a \u00a7f";

   private static final int SCARLET_BLOOD_MODEL = 4550230;

   /** Fight tuning - balance knobs, not safety caps. */
   private static final double MAX_HEALTH = 900.0;
   private static final double BITE_DAMAGE = 16.0;
   private static final double SPEAR_DAMAGE = 9.0;
   private static final double ARENA_RADIUS = 84.0;
   private static final int BITE_COOLDOWN = 120;
   private static final int SPEAR_COOLDOWN = 90;
   private static final int SWARM_COOLDOWN = 260;
   private static final int TAUNT_COOLDOWN = 320;
   private static final int RAIN_COOLDOWN = 500;
   private static final int RAIN_TICKS = 200;
   private static final int BLOOD_RAIN_PULSE = 20;
   private static final int MAX_SWARMS = 6;
   /** How far the swoop reaches before it counts as whiffed. */
   private static final double BITE_REACH = 2.6;

   /**
    * Her bloodsuck. She used to heal 60% of a bite and 75% of every drop of
    * rain, which made her unkillable in a group: the more people standing in
    * the rain, the faster she healed. Both are now a small share, and every
    * heal is capped so a big hit can never hand her a whole health segment.
    */
   private static final float BITE_LIFESTEAL = 0.18F;
   private static final float MAX_BITE_HEAL = 4.0F;
   private static final float RAIN_LIFESTEAL = 0.25F;
   private static final float MAX_RAIN_HEAL_PER_TICK = 6.0F;
   /** Her kill reward: a token sip, never a panic button. */
   private static final float DEATH_SIP = 8.0F;

   // --- Sanguine Tide -------------------------------------------------------

   /**
    * The tide: a ring of her own blood that runs outward across the floor.
    *
    * <p>Everything else she does is answered by <i>moving</i> - out of the swoop, off
    * the line, behind something. The tide is answered by moving <b>up</b>: the wave is
    * a band on the ground, so a jump clears it, and a player who has spent the whole
    * fight strafing has to learn a second verb for it. Three of them roll out in
    * sequence, each a little faster than the last, and the third one catches people who
    * jumped the first two on muscle memory alone.
    */
   private static final int TIDE_COOLDOWN = 460;
   private static final int TIDE_TICKS = 70;
   private static final double TIDE_STEP = 0.42;
   private static final double TIDE_MAX = 27.0;
   /** How thick the band is on either side of the drawn ring. */
   private static final double TIDE_BAND = 1.6;
   private static final float TIDE_DAMAGE = 13.0F;
   /** How many waves one cast rolls out. */
   private static final int TIDE_WAVES = 3;
   /** Ticks between one wave finishing and the next leaving her.
    *  Short enough to be one move, long enough to land after the jump. */
   private static final int TIDE_GAP = 8;

   // --- Scarlet Brand and the Blood Pact ------------------------------------

   /**
    * The brand: a ring of her sigil burns onto the floor under a fighter and, a beat and a
    * half later, a lance of blood drops onto it. The answer is to leave the circle, which the
    * ring makes obvious. Phase two marks two more circles around the first, so stepping out
    * means stepping out in the right direction.
    */
   private static final int BRAND_COOLDOWN = 220;
   private static final int BRAND_WARN = 30;
   private static final double BRAND_RADIUS = 2.4;
   private static final float BRAND_DAMAGE = 12.0F;

   /**
    * The pact: a chain of her blood ties her to one fighter and drinks from them every half
    * second. It breaks the moment they put {@link #PACT_BREAK} blocks between themselves and
    * her, so it is a reason to back off, which her swoop then punishes. Every sip is small and
    * capped, the same rule as the rest of her healing.
    */
   private static final int PACT_COOLDOWN = 380;
   private static final int PACT_TICKS = 80;
   private static final int PACT_PULSE = 10;
   private static final double PACT_BREAK = 16.0;
   private static final float PACT_DAMAGE = 2.0F;
   private static final float PACT_HEAL = 1.0F;

   /** Her colours, for the client effects. */
   private static final int CRIMSON = 0xC0102A;
   private static final int BLOOD_DARK = 0x5A0010;
   private static final int MOON = 0xFF3355;

   private static final Random RANDOM = new Random();
   private static final Map<UUID, Fight> FIGHTS = new HashMap<>();
   private static final List<Spear> SPEARS = new ArrayList<>();

   /** Junk her blood throws off when a spear shatters on a player. */
   private static final ItemStack[] SPEAR_PAYLOAD = new ItemStack[]{
      new ItemStack(Items.REDSTONE), new ItemStack(Items.NETHER_WART), new ItemStack(Items.PHANTOM_MEMBRANE)
   };

   private ScarletDevilManager() {
   }

   // ------------------------------------------------------------------ state

   private static final class Fight {
      final UUID bossId;
      final UUID summoner;
      final ServerBossEvent bar;
      final Set<UUID> participants = new HashSet<>();
      int riseTicks = 50;
      int phase = 1;
      boolean phaseTwoAnnounced;
      long nextBite;
      long nextSpear;
      long nextSwarm;
      long nextTaunt;
      long nextRain;
      long rainUntil;
      long nextRainPulse;
      long nextAura;
      int swarms;
      /** The tide: the cooldown, the wave in flight, its radius, and its remaining waves. */
      long nextTide;
      int tideTicks;
      int tideGap;
      double tideRadius;
      int tideWaves;
      final Set<UUID> tideHit = new HashSet<>();
      UUID lungeTarget;
      int lungeTicks;
      long nextBrand;
      final List<Brand> brands = new ArrayList<>();
      long nextPact;
      UUID pactTarget;
      int pactTicks;
      boolean dying;
      int deathTicks;
      int questDrops;

      Fight(UUID bossId, UUID summoner, ServerBossEvent bar) {
         this.bossId = bossId;
         this.summoner = summoner;
         this.bar = bar;
      }
   }

   /** A brand burned on the floor: marked now, struck at {@code landAt}. */
   private static final class Brand {
      final Vec3 at;
      final long landAt;
      boolean falling;

      Brand(Vec3 at, long landAt) {
         this.at = at;
         this.landAt = landAt;
      }
   }

   /** A thrown blood spear, driven one at a time so the volley reads clearly. */
   private static final class Spear {
      final UUID target;
      final ServerLevel level;
      final Mob boss;
      final Vec3 origin;
      int remaining;
      long next;
      float damage;

      Spear(UUID target, ServerLevel level, Mob boss, Vec3 origin, int remaining, long next, float damage) {
         this.target = target;
         this.level = level;
         this.boss = boss;
         this.origin = origin;
         this.remaining = remaining;
         this.next = next;
         this.damage = damage;
      }
   }

   // ------------------------------------------------------------- public API

   public static boolean isScarletDevil(Entity entity) {
      return entity != null && entity.entityTags().contains(TAG);
   }

   /** How many Scarlet Devils are alive right now. */
   public static int activeCount() {
      return FIGHTS.size();
   }

   /** True while this player is part of a live Scarlet Devil fight. */
   public static boolean isParticipant(ServerPlayer player) {
      if (player == null) {
         return false;
      }
      for (Fight f : FIGHTS.values()) {
         if (f.participants.contains(player.getUUID())) {
            return true;
         }
      }
      return false;
   }

   /**
    * True when this player's death belongs to her: a live fight, not yet in its
    * death ceremony, and the player is one of her targets.
    *
    * <p>Nothing stands down for her any more: the servant wears copies and takes
    * nothing, so a claimed death is an ordinary death as far as Nice Keep
    * Inventory and the vanilla drop rules are concerned. This predicate is kept as
    * the single answer to "was this player part of a live fight of hers".
    */
   public static boolean claimsDeath(ServerPlayer player) {
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

   /** Called on shutdown / admin cleanup: end every fight and leave nothing behind. */
   public static int abandonAll(MinecraftServer server) {
      int ended = 0;
      for (Fight fight : new ArrayList<>(FIGHTS.values())) {
         BossManager.recallGearRevenants(server, fight.participants, "The Scarlet Devil's fight was ended");
         Safe.run("scarlet abandon", () -> {
            if (server != null) {
               for (ServerLevel level : server.getAllLevels()) {
                  Entity boss = level.getEntity(fight.bossId);
                  if (boss != null) {
                     boss.discard();
                  }
               }
            }
            release(fight);
         });
         ended++;
      }
      SPEARS.clear();
      // Her servants and her player-owned gear go with her: a servant summoned by
      // the Prism must not outlive the world it was raised in.
      ScarletGear.clear(server);
      return ended;
   }

   /** Called when the server stops so no boss bar outlives the world. */
   public static void onServerStopping(MinecraftServer server) {
      abandonAll(server);
   }

   // -------------------------------------------------------------- summoning

   /** Scarlet-Blood right-click: pour it out and let her through. */
   public static String summon(ServerPlayer summoner) {
      if (!ModConfig.is("boss")) {
         return "Bosses are disabled on this server.";
      }

      // A fight whose boss entity is gone is not a fight. Without this sweep a
      // single lost phantom (a chunk unload, a server restart, a crash mid-tick)
      // left the entry in FIGHTS forever and the summoner was told they "already
      // have an active Scarlet Devil" - so she could never be summoned again.
      for (Fight f : new ArrayList<>(FIGHTS.values())) {
         Entity live = findEntity(summoner.level().getServer(), f.bossId);
         if (live == null || !live.isAlive()) {
            release(f);
         }
      }

      for (Fight f : FIGHTS.values()) {
         if (summoner.getUUID().equals(f.summoner)) {
            return "You already have an active Scarlet Devil - finish her first!";
         }
      }

      ServerLevel level = summoner.level();
      Mob boss = (Mob) EntityTypes.PHANTOM.create(level, EntitySpawnReason.COMMAND);
      if (boss == null) {
         return "The blood pooled and went still - she did not come.";
      }

      AttributeInstance maxHp = boss.getAttribute(Attributes.MAX_HEALTH);
      if (maxHp != null) {
         maxHp.setBaseValue(MAX_HEALTH);
      }
      boss.setHealth((float) MAX_HEALTH);
      AttributeInstance dmg = boss.getAttribute(Attributes.ATTACK_DAMAGE);
      if (dmg != null) {
         dmg.setBaseValue(12.0);
      }
      AttributeInstance follow = boss.getAttribute(Attributes.FOLLOW_RANGE);
      if (follow != null) {
         follow.setBaseValue(ARENA_RADIUS);
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
      double x = summoner.getX();
      double y = summoner.getY();
      double z = summoner.getZ();
      // She rises out of the pool of her own blood, so the summon is never a pop-in.
      boss.setPos(x, y - 2.6, z);
      boss.setYRot(summoner.getYRot());
      boss.setXRot(summoner.getXRot());
      level.addFreshEntity(boss);

      ServerBossEvent bar = new ServerBossEvent(
         UUID.randomUUID(), Component.literal(BOSS_NAME), BossBarColor.RED, BossBarOverlay.PROGRESS
      );
      bar.setVisible(true);
      for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
         bar.addPlayer(p);
      }

      Fight fight = new Fight(boss.getUUID(), summoner.getUUID(), bar);
      fight.participants.add(summoner.getUUID());
      long now = ServerClock.clock(level);
      fight.nextBite = now + 70L;
      fight.nextSpear = now + 130L;
      fight.nextSwarm = now + 220L;
      fight.nextTide = now + 340L;
      fight.nextTaunt = now + 100L;
      fight.nextRain = now + 300L;
      fight.nextAura = now + 30L;
      fight.nextBrand = now + 180L;
      fight.nextPact = now + 260L;
      FIGHTS.put(boss.getUUID(), fight);

      // Spawn ceremony: the mist rolls in, the moon reddens, she arrives.
      announce(level, "\u00a78\u00a7m\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550");
      announce(level, "    \u00a74\u00a7l\u2620 THE SCARLET DEVIL DESCENDS \u2620");
      announce(level, "    \u00a77The blood on the floor starts to move.");
      announce(level, "    \u00a78\u201c\u00a7fYou spilled me. \u00a74Now I'm thirsty.\u00a78\u201d");
      announce(level, "\u00a78\u00a7m\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550");
      // The pool is a sigil before it is a boss: a turning ring of her runes, a heartbeat
      // under it and a slow red snow over the whole spot while she climbs out.
      Vec3 pool = new Vec3(x, y, z);
      Fx.runeCircle(level, ParticleTypes.CRIMSON_SPORE, pool.add(0.0, 0.05, 0.0), 4.0, fight.riseTicks + 10, CRIMSON);
      Fx.heartbeat(level, ParticleTypes.CRIMSON_SPORE, pool.add(0.0, 0.1, 0.0), 3.0, fight.riseTicks, BLOOD_DARK);
      Fx.emberRain(level, ParticleTypes.CRIMSON_SPORE, pool, 9.0, fight.riseTicks + 20, MOON);
      level.playSound(null, x, y, z, ModSounds.BOSS_SPAWN, SoundSource.HOSTILE, 1.3F, 0.7F);
      level.playSound(null, x, y, z, SoundEvents.PHANTOM_AMBIENT, SoundSource.HOSTILE, 1.6F, 0.5F);
      level.playSound(null, x, y, z, SoundEvents.ENDER_DRAGON_GROWL, SoundSource.HOSTILE, 1.0F, 0.6F);
      Advancements.grant(summoner, "summon_scarlet");
      return null;
   }

   // ------------------------------------------------------------------- tick

   public static void tick(MinecraftServer server) {
      if (FIGHTS.isEmpty() && SPEARS.isEmpty()) {
         return;
      }

      long now = ServerClock.clock(server.overworld());
      for (Fight fight : new ArrayList<>(FIGHTS.values())) {
         Safe.run("scarlet devil tick", () -> tickFight(server, fight, now));
      }

      for (Iterator<Spear> it = SPEARS.iterator(); it.hasNext();) {
         Spear s = it.next();
         if (s.remaining <= 0 || s.boss == null || !s.boss.isAlive()) {
            it.remove();
            continue;
         }
         if (ServerClock.clock(s.level) >= s.next) {
            fireSpear(s);
            s.remaining--;
            s.next = ServerClock.clock(s.level) + 3L;
            if (s.remaining <= 0) {
               it.remove();
            }
         }
      }
   }

   private static void tickFight(MinecraftServer server, Fight fight, long now) {
      Entity raw = findEntity(server, fight.bossId);
      if (!(raw instanceof Mob boss) || !boss.isAlive()) {
         // She is gone - so nothing is left to kill a servant for, and any gear
         // one is still wearing goes straight back to /claim loot instead of
         // being stranded on a servant nobody will ever fight again.
         BossManager.recallGearRevenants(server, fight.participants, "The Scarlet Devil is gone");
         release(fight);
         return;
      }
      ServerLevel level = (ServerLevel) boss.level();
      now = ServerClock.clock(level);
      refreshParticipants(server, level, boss, fight);

      // The bar She was given at summon time was never once told what her health
      // was, so it sat at full for the whole fight no matter how much she healed
      // or bled. Drive it every tick, before anything can end the fight.
      if (fight.bar != null) {
         fight.bar.setProgress(Math.max(0.0F, Math.min(1.0F, boss.getHealth() / Math.max(1.0F, boss.getMaxHealth()))));
      }

      // 0) She is a *phantom*, so dawn would burn her out of the fight while the
      //    player is still in it (and a boss who burns to death on her own is a
      //    boss nobody can summon again until the tick above notices). Keep her
      //    fireproof and unlit for as long as the fight lives.
      if (now % 40L == 0L) {
         boss.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 120, 0, false, false, false));
      }
      if (boss.isOnFire()) {
         boss.clearFire();
      }

      // 1) Death ceremony - she is already beaten, we are playing it out.
      if (fight.dying) {
         tickDeath(level, boss, fight);
         return;
      }

      // 2) Arrival: she climbs out of the blood pool.
      if (fight.riseTicks > 0) {
         fight.riseTicks--;
         drawPool(level, boss);
         boss.setPos(boss.getX(), boss.getY() + 0.052, boss.getZ());
         if (fight.riseTicks % 8 == 0) {
            level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.PHANTOM_FLAP, SoundSource.HOSTILE, 1.0F, 0.7F);
         }
         if (fight.riseTicks == 0) {
            // Out of the pool: the wings open and the room gets the message.
            Vec3 at = boss.position().add(0.0, 1.0, 0.0);
            Fx.flare(level, ParticleTypes.CRIMSON_SPORE, at, 2.6, MOON);
            Fx.starburst(level, ParticleTypes.CRIMSON_SPORE, at, 6.0, CRIMSON);
            Fx.shockwave(level, ParticleTypes.CRIMSON_SPORE, boss.position(), 10.0, BLOOD_DARK);
            level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.PHANTOM_AMBIENT, SoundSource.HOSTILE, 1.8F, 0.4F);
            level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.ENDER_DRAGON_FLAP, SoundSource.HOSTILE, 1.4F, 0.6F);
            announce(level, SAY + "\"\u00a7fMm. \u00a74More of you than I expected.\"");
         }
         return;
      }

      // 3) Nobody left: she loses interest and leaves.
      if (fight.participants.isEmpty()) {
         despawn(level, boss, fight, "Nobody left to drink. The mist thins.");
         return;
      }

      // 4) Phase 2 at half health: the blood rain begins.
      if (fight.phase == 1 && boss.getHealth() <= boss.getMaxHealth() * 0.5F) {
         fight.phase = 2;
         fight.phaseTwoAnnounced = true;
         if (fight.bar != null) {
            fight.bar.setColor(BossBarColor.PURPLE);
         }
         announce(level, SAY + "\"\u00a7fThat's half. \u00a74You don't get the other half.\u00a7f\"");
         Fx.heartbeat(level, ParticleTypes.CRIMSON_SPORE, boss.position().add(0.0, 0.1, 0.0), 6.0, 40, CRIMSON);
         Fx.flare(level, ParticleTypes.CRIMSON_SPORE, boss.position().add(0.0, 1.2, 0.0), 3.0, MOON);
         level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.ENDER_DRAGON_GROWL, SoundSource.HOSTILE, 1.2F, 0.6F);
         bloodBurst(level, boss.getX(), boss.getY() + 1.0, boss.getZ(), 80);
         startBloodRain(level, boss, fight);
      }

      // 5) Ambient bloody aura, always on, so she reads as a blood entity.
      if (now >= fight.nextAura) {
         fight.nextAura = now + 6L;
         drawAura(level, boss);
      }

      // 6) Blood rain upkeep while it is falling.
      if (fight.rainUntil > 0L) {
         tickBloodRain(level, boss, fight, now);
      }

      // Brands land and the pact drinks on their own clocks, whatever else she is doing.
      tickBrands(level, boss, fight, now);
      tickPact(level, boss, fight);

      ServerPlayer target = nearestTarget(level, boss, fight);
      // Unstick her every tick, before any state can return early: the swoop and
      // the spear volley used to leave her buried until the fight happened to
      // reach the baseline-pressure branch at the very end.
      keepOutOfBlocks(level, boss, target);
      if (target == null) {
         return;
      }

      // 7) The swoop. Once committed it is driven to its end before anything
      //    else, so the bite can never be interrupted halfway.
      if (fight.lungeTicks > 0) {
         tickLunge(level, boss, fight, now);
         return;
      }

      // The tide owns her while it rolls: three waves in sequence, and nothing else
      // can interrupt the run of them.
      if (fight.tideTicks > 0 || fight.tideGap > 0) {
         tickTide(level, boss, fight);
         return;
      }

      // 8) Ability rotation. Each is independently gated, so a missing target
      //    can never stall the rest of the move set.
      if (fight.phase == 2 && now >= fight.nextTide) {
         startTide(level, boss, fight);
         return;
      }
      if (now >= fight.nextBite && boss.distanceToSqr(target) < 900.0) {
         startLunge(level, boss, fight, target);
         return;
      }
      if (now >= fight.nextBrand) {
         fight.nextBrand = now + BRAND_COOLDOWN;
         brand(level, boss, fight, now);
         return;
      }
      if (fight.pactTicks <= 0 && now >= fight.nextPact) {
         fight.nextPact = now + PACT_COOLDOWN;
         startPact(level, boss, fight, target);
         return;
      }
      if (now >= fight.nextSpear) {
         fight.nextSpear = now + SPEAR_COOLDOWN;
         spearVolley(level, boss, fight, target);
         return;
      }
      if (fight.phase == 2 && now >= fight.nextRain) {
         startBloodRain(level, boss, fight);
         return;
      }
      if (now >= fight.nextSwarm && fight.swarms < MAX_SWARMS) {
         fight.nextSwarm = now + SWARM_COOLDOWN;
         nightSwarm(level, boss, fight, target);
         return;
      }
      if (now >= fight.nextTaunt) {
         fight.nextTaunt = now + TAUNT_COOLDOWN;
         taunt(level, fight);
      }

      // 9) Baseline pressure. She used to back away whenever a player closed to
      //    melee range, which read as "she runs away from you" - she now only
      //    ever closes the gap, and simply holds her ground once she is on top
      //    of you (the swoop and the spear volley are her spacing tools).
      double dist = Math.sqrt(boss.distanceToSqr(target));
      if (dist > 12.0) {
         double speed = dist > 30.0 ? 0.42 : 0.3;
         Vec3 toward = target.position().subtract(boss.position()).normalize().scale(speed);
         boss.setPos(boss.getX() + toward.x, boss.getY(), boss.getZ() + toward.z);
      }
      keepOutOfBlocks(level, boss, target);
      boss.setYRot(faceYaw(boss, target));
      Fx.vanilla(level, ParticleTypes.CRIMSON_SPORE, boss.getX(), boss.getY() + 0.8, boss.getZ(), 3, 0.35, 0.5, 0.35, 0.01);
   }

   // --------------------------------------------------------------- abilities

   /** Commits to a swoop at the target. */
   private static void startLunge(ServerLevel level, Mob boss, Fight fight, ServerPlayer target) {
      fight.lungeTarget = target.getUUID();
      fight.lungeTicks = 16;
      fight.nextBite = ServerClock.clock(level) + BITE_COOLDOWN;
      announce(level, SAY + "\"\u00a7fHold still.\u00a7f\"");
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.PHANTOM_SWOOP, SoundSource.HOSTILE, 1.5F, 0.7F);
      Fx.beam(level, ParticleTypes.CRIMSON_SPORE, boss.position().add(0.0, 0.8, 0.0), target.position().add(0.0, 1.0, 0.0), BLOOD_DARK);
   }

   /** Drives a committed swoop; when it connects, she drinks. */
   private static void tickLunge(ServerLevel level, Mob boss, Fight fight, long now) {
      ServerPlayer target = fight.lungeTarget == null ? null : level.getServer().getPlayerList().getPlayer(fight.lungeTarget);
      fight.lungeTicks--;
      if (target == null || !target.isAlive()) {
         fight.lungeTicks = 0;
         return;
      }

      Vec3 toward = target.position().add(0.0, 0.8, 0.0).subtract(boss.position());
      double dist = toward.length();
      if (dist <= BITE_REACH || fight.lungeTicks <= 0) {
         if (dist <= BITE_REACH + 1.2) {
            bite(level, boss, fight, target);
         } else {
            announce(level, SAY + "\"\u00a7fTch.\u00a7f\"");
         }
         fight.lungeTicks = 0;
         return;
      }

      Vec3 step = toward.normalize().scale(Math.min(1.1, dist));
      boss.setPos(boss.getX() + step.x, boss.getY() + step.y * 0.6, boss.getZ() + step.z);
      // The swoop is the one move that drives her through geometry, so it has to
      // unstick itself: waiting for the baseline-pressure branch left her parked
      // inside the terrain for the rest of the dive.
      keepOutOfBlocks(level, boss, target);
      boss.setYRot(faceYaw(boss, target));
      drawTrail(level, boss);
   }

   /**
    * The signature move. She bites for heavy damage and heals herself for a share
    * of it - the "bloodsuck" lifesteal - with a knockback kick so it reads as a hit
    * rather than a number appearing.
    */
   private static void bite(ServerLevel level, Mob boss, Fight fight, ServerPlayer target) {
      float before = boss.getHealth();
      // hurtServer only reports whether the hit landed (it can be blocked or
      // absorbed), so the lifesteal is taken from the bite's own damage rather
      // than a dealt amount the API does not hand back.
      boolean landed = target.hurtServer(level, level.damageSources().mobAttack(boss), (float) BITE_DAMAGE);
      float healed = landed ? Math.min(MAX_BITE_HEAL, (float) BITE_DAMAGE * BITE_LIFESTEAL) : 0.0F;
      boss.setHealth(Math.min(boss.getMaxHealth(), before + healed));

      double dx = target.getX() - boss.getX();
      double dz = target.getZ() - boss.getZ();
      double d = Math.max(0.1, Math.sqrt(dx * dx + dz * dz));
      target.setDeltaMovement(target.getDeltaMovement().add(dx / d * 0.9, 0.35, dz / d * 0.9));
      target.hurtMarked = true;

      level.playSound(null, target.getX(), target.getY(), target.getZ(), SoundEvents.PHANTOM_BITE, SoundSource.HOSTILE, 1.4F, 0.8F);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.GENERIC_DRINK, SoundSource.HOSTILE, 1.2F, 0.6F);
      Fx.vanilla(level, ParticleTypes.DAMAGE_INDICATOR, target.getX(), target.getY() + 1.0, target.getZ(), 12, 0.3, 0.3, 0.3, 0.1);
      bloodBurst(level, target.getX(), target.getY() + 1.0, target.getZ(), 30);
      Vec3 facing = target.position().subtract(boss.position());
      Fx.crescent(level, ParticleTypes.CRIMSON_SPORE, boss.position().add(0.0, 0.9, 0.0), facing, 3.2, CRIMSON);
      if (landed) {
         CombatGear.procPopupPublic(level, boss, "\u00a74Bloodsuck +\u00a7f" + Math.round(healed));
      }
      announce(level, SAY + (RANDOM.nextBoolean() ? "\"\u00a7fMm. \u00a74Next.\u00a7f\"" : "\"\u00a7fYou taste like iron and panic.\u00a7f\""));
   }

   /** Blood spears arc in at the target; surviving one leaves her essence behind. */
   private static void spearVolley(ServerLevel level, Mob boss, Fight fight, ServerPlayer target) {
      announce(level, SAY + "\"\u00a7fCatch.\u00a7f\"");
      level.playSound(null, target.getX(), target.getY(), target.getZ(), SoundEvents.ARROW_SHOOT, SoundSource.HOSTILE, 1.4F, 0.6F);
      Vec3 origin = boss.position().add(0.0, 0.8, 0.0);
      int count = fight.phase == 2 ? 4 : 3;
      SPEARS.add(new Spear(target.getUUID(), level, boss, origin, count, ServerClock.clock(level) + 8L, (float) SPEAR_DAMAGE));
   }

   private static void fireSpear(Spear s) {
      ServerPlayer tp = s.level.getServer().getPlayerList().getPlayer(s.target);
      if (tp == null || !tp.isAlive()) {
         s.remaining = 0;
         return;
      }
      Vec3 eye = tp.position().add(0.0, 1.0, 0.0);
      ItemStack payload = SPEAR_PAYLOAD[RANDOM.nextInt(SPEAR_PAYLOAD.length)].copy();
      Arrow arrow = new Arrow(s.level, s.boss, payload, new ItemStack(Items.BOW));
      arrow.setPos(s.origin.x, s.origin.y, s.origin.z);
      Vec3 dir = eye.subtract(s.origin);
      if (dir.lengthSqr() < 1.0E-4) {
         dir = new Vec3(0.0, -1.0, 0.0);
      }
      arrow.setDeltaMovement(dir.normalize().scale(2.0));
      arrow.setBaseDamage(s.damage);
      arrow.setCritArrow(true);
      arrow.pickup = AbstractArrow.Pickup.DISALLOWED;
      s.level.addFreshEntity(arrow);
      Fx.muzzle(s.level, ParticleTypes.CRIMSON_SPORE, s.origin, dir, CRIMSON);
   }

   /**
    * Night Swarm: a knot of bats erupts around her. It is pressure rather than
    * damage - everyone caught in it is blinded for a moment, which is exactly when
    * the swoop tends to land.
    */
   private static void nightSwarm(ServerLevel level, Mob boss, Fight fight, ServerPlayer target) {
      fight.swarms++;
      announce(level, SAY + "\"\u00a7fGo on, little ones.\u00a7f\"");
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.BAT_TAKEOFF, SoundSource.HOSTILE, 1.6F, 0.6F);
      for (int i = 0; i < 6; i++) {
         double a = RANDOM.nextDouble() * Math.PI * 2.0;
         double r = 1.5 + RANDOM.nextDouble() * 2.5;
         Mob bat = (Mob) EntityTypes.BAT.create(level, EntitySpawnReason.EVENT);
         if (bat == null) {
            continue;
         }
         bat.setPos(boss.getX() + Math.cos(a) * r, boss.getY() + 0.5, boss.getZ() + Math.sin(a) * r);
         level.addFreshEntity(bat);
      }
      for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
         if (fight.participants.contains(p.getUUID()) && p.distanceToSqr(boss) < 256.0) {
            p.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 60, 0, false, false, true));
         }
      }
      Fx.vortex(level, ParticleTypes.LARGE_SMOKE, boss.position(), 4.0, 40, BLOOD_DARK);
   }

   /** Burns her sigil under every fighter; a lance of blood drops on each a beat and a half later. */
   private static void brand(ServerLevel level, Mob boss, Fight fight, long now) {
      // Her sigil turns under her as she brands - a ring of crimson runes on the floor.
      Fx.crimsonSigil(level, ParticleTypes.CRIMSON_SPORE, boss.position().add(0.0, 0.05, 0.0), 3.2, 40, CRIMSON);
      int marked = 0;
      for (UUID id : fight.participants) {
         ServerPlayer p = level.getServer().getPlayerList().getPlayer(id);
         if (p == null || !p.isAlive() || p.level() != level || p.distanceToSqr(boss) > ARENA_RADIUS * ARENA_RADIUS) {
            continue;
         }
         markBrand(level, fight, p.position(), now);
         if (fight.phase == 2) {
            // Two more either side of the first, so the way out has to be picked.
            double a = RANDOM.nextDouble() * Math.PI * 2.0;
            for (int k = 0; k < 2; k++) {
               double ang = a + k * Math.PI;
               markBrand(level, fight, p.position().add(Math.cos(ang) * 4.0, 0.0, Math.sin(ang) * 4.0), now);
            }
         }
         marked++;
      }
      if (marked == 0) {
         return;
      }
      announce(level, SAY + "\"\u00a7fStay right there.\u00a7f\"");
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.EVOKER_PREPARE_ATTACK, SoundSource.HOSTILE, 1.3F, 0.6F);
   }

   private static void markBrand(ServerLevel level, Fight fight, Vec3 at, long now) {
      Vec3 floor = new Vec3(at.x, BossGrounding.groundY(level, at.x, at.z, at.y), at.z);
      fight.brands.add(new Brand(floor, now + BRAND_WARN));
      Fx.runeCircle(level, ParticleTypes.CRIMSON_SPORE, floor.add(0.0, 0.05, 0.0), BRAND_RADIUS, BRAND_WARN, CRIMSON);
   }

   private static void tickBrands(ServerLevel level, Mob boss, Fight fight, long now) {
      if (fight.brands.isEmpty()) {
         return;
      }
      for (Iterator<Brand> it = fight.brands.iterator(); it.hasNext();) {
         Brand b = it.next();
         if (!b.falling && now >= b.landAt - 6L) {
            // The lance is seen coming down for the last few ticks, so the hit is never a surprise.
            b.falling = true;
            Fx.comet(level, ParticleTypes.CRIMSON_SPORE, b.at.add(0.0, 16.0, 0.0), b.at.add(0.0, 0.4, 0.0), 6, MOON);
         }
         if (now < b.landAt) {
            continue;
         }
         it.remove();
         Fx.pillar(level, ParticleTypes.CRIMSON_SPORE, b.at, 7.0, CRIMSON);
         Fx.gooSplash(level, ParticleTypes.CRIMSON_SPORE, b.at.add(0.0, 0.3, 0.0), 1.6, BLOOD_DARK);
         level.playSound(null, b.at.x, b.at.y, b.at.z, SoundEvents.TRIDENT_THROW, SoundSource.HOSTILE, 1.4F, 0.6F);
         double r2 = BRAND_RADIUS * BRAND_RADIUS;
         for (UUID id : fight.participants) {
            ServerPlayer p = level.getServer().getPlayerList().getPlayer(id);
            if (p == null || !p.isAlive() || p.level() != level) {
               continue;
            }
            double dx = p.getX() - b.at.x;
            double dz = p.getZ() - b.at.z;
            if (dx * dx + dz * dz > r2 || Math.abs(p.getY() - b.at.y) > 3.0) {
               continue;
            }
            p.hurtServer(level, level.damageSources().mobAttack(boss), BRAND_DAMAGE);
            p.setDeltaMovement(p.getDeltaMovement().add(0.0, 0.4, 0.0));
            p.hurtMarked = true;
         }
      }
   }

   /** Ties a chain of blood to the target and starts drinking through it. */
   private static void startPact(ServerLevel level, Mob boss, Fight fight, ServerPlayer target) {
      if (target.distanceToSqr(boss) > PACT_BREAK * PACT_BREAK) {
         return;
      }
      // The pact is sealed in a sigil under the victim and a chain of blood between them.
      Fx.crimsonSigil(level, ParticleTypes.CRIMSON_SPORE, target.position().add(0.0, 0.05, 0.0), 1.6, 60, MOON);
      Fx.chains(level, ParticleTypes.CRIMSON_SPORE, boss.position().add(0.0, 1.4, 0.0), target.position().add(0.0, 1.0, 0.0), CRIMSON);
      fight.pactTarget = target.getUUID();
      fight.pactTicks = PACT_TICKS;
      announce(level, SAY + "\"\u00a7fShare a little.\u00a7f\"");
      Chat.raw(target, "\u00a74A chain of blood ties you to her. \u00a77Get " + (int) PACT_BREAK + " blocks away to snap it.");
      level.playSound(null, target.getX(), target.getY(), target.getZ(), SoundEvents.CHAIN_FALL, SoundSource.HOSTILE, 1.4F, 0.6F);
      Fx.chains(level, ParticleTypes.CRIMSON_SPORE, boss.position().add(0.0, 1.0, 0.0), target.position().add(0.0, 1.0, 0.0), CRIMSON);
   }

   private static void tickPact(ServerLevel level, Mob boss, Fight fight) {
      if (fight.pactTicks <= 0 || fight.pactTarget == null) {
         return;
      }
      fight.pactTicks--;
      ServerPlayer p = level.getServer().getPlayerList().getPlayer(fight.pactTarget);
      if (p == null || !p.isAlive() || p.level() != level) {
         fight.pactTicks = 0;
         fight.pactTarget = null;
         return;
      }
      if (p.distanceToSqr(boss) > PACT_BREAK * PACT_BREAK) {
         fight.pactTicks = 0;
         fight.pactTarget = null;
         announce(level, SAY + "\"\u00a7fClever.\u00a7f\"");
         Fx.shatter(level, ParticleTypes.CRIMSON_SPORE, p.position().add(0.0, 1.0, 0.0), 0.8, CRIMSON);
         level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.CHAIN_BREAK, SoundSource.HOSTILE, 1.4F, 0.7F);
         return;
      }
      if (fight.pactTicks % PACT_PULSE != 0) {
         return;
      }
      Fx.chains(level, ParticleTypes.CRIMSON_SPORE, boss.position().add(0.0, 1.0, 0.0), p.position().add(0.0, 1.0, 0.0), CRIMSON);
      if (p.hurtServer(level, level.damageSources().magic(), PACT_DAMAGE)) {
         boss.setHealth(Math.min(boss.getMaxHealth(), boss.getHealth() + PACT_HEAL));
      }
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.GENERIC_DRINK, SoundSource.HOSTILE, 0.7F, 0.6F);
      if (fight.pactTicks <= 0) {
         fight.pactTarget = null;
      }
   }

   /**
    * The tide rolls out of her, one wave at a time.
    *
    * <p>Drawn as a ring on the floor at the wave's own radius, so the gap between the
    * ring and the fighter <i>is</i> the timer. The band that hurts is narrow on purpose:
    * the wave is meant to be jumped, not out-run, and a fat band would make stepping
    * backwards through it work just as well as timing it.
    */
   private static void startTide(ServerLevel level, Mob boss, Fight fight) {
      Fx.bloodSplash(level, ParticleTypes.CRIMSON_SPORE, boss.position(), new Vec3(0.0, 1.0, 0.0), 2.4, CRIMSON);
      fight.nextTide = ServerClock.clock(level) + TIDE_COOLDOWN;
      fight.tideWaves = TIDE_WAVES;
      fight.tideRadius = 1.2;
      fight.tideTicks = TIDE_TICKS;
      fight.tideHit.clear();
      announce(level, SAY + "\"\u00a7fMind your feet.\u00a7f\"");
      announce(level, "\u00a78The floor runs red, and the red starts moving outward. \u00a77Jump it.");
      Fx.heartbeat(level, ParticleTypes.CRIMSON_SPORE, boss.position().add(0.0, 0.1, 0.0), 3.0, 20, CRIMSON);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.ENDER_DRAGON_GROWL, SoundSource.HOSTILE, 1.2F, 0.6F);
      bloodBurst(level, boss.getX(), boss.getY() + 0.2, boss.getZ(), 60);
   }

   private static void tickTide(ServerLevel level, Mob boss, Fight fight) {
      if (fight.tideGap > 0) {
         fight.tideGap--;
         if (fight.tideGap <= 0) {
            fight.tideRadius = 1.2;
            fight.tideTicks = TIDE_TICKS;
            fight.tideHit.clear();
            level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.PHANTOM_SWOOP, SoundSource.HOSTILE, 1.4F, 0.8F);
            bloodBurst(level, boss.getX(), boss.getY() + 0.2, boss.getZ(), 40);
         }
         return;
      }

      fight.tideTicks--;
      fight.tideRadius += TIDE_STEP;
      double r = fight.tideRadius;
      int ring = 40;
      for (int i = 0; i < ring; i++) {
         double a = i * (Math.PI * 2.0 / ring) + r * 0.05;
         double x = boss.getX() + Math.cos(a) * r;
         double z = boss.getZ() + Math.sin(a) * r;
         double y = BossGrounding.groundY(level, x, z, boss.getY());
         Fx.vanilla(level, ParticleTypes.CRIMSON_SPORE, x, y + 0.25, z, 1, 0.05, 0.05, 0.05, 0.0);
         if (i % 5 == 0) {
            Fx.vanilla(level, ParticleTypes.FALLING_LAVA, x, y + 0.3, z, 1, 0.05, 0.05, 0.05, 0.0);
         }
      }

      for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
         if (!fight.participants.contains(p.getUUID()) || p.level() != level) {
            continue;
         }
         double dist = Math.sqrt(p.distanceToSqr(boss.getX(), boss.getY(), boss.getZ()));
         if (Math.abs(dist - r) > TIDE_BAND) {
            continue;
         }
         if (!p.onGround()) {
            continue;
         }
         if (fight.tideHit.contains(p.getUUID())) {
            continue;
         }
         fight.tideHit.add(p.getUUID());
         p.hurtServer(level, level.damageSources().mobAttack(boss), TIDE_DAMAGE);
         Vec3 away = p.position().subtract(boss.position());
         Vec3 flat = new Vec3(away.x, 0.0, away.z).normalize().scale(1.1);
         p.push(flat.x, 0.35, flat.z);
         p.hurtMarked = true;
         bloodBurst(level, p.getX(), p.getY() + 0.6, p.getZ(), 30);
      }

      if (fight.tideTicks <= 0 || r > TIDE_MAX) {
         fight.tideTicks = 0;
         if (fight.tideWaves > 1) {
            fight.tideWaves--;
            fight.tideGap = TIDE_GAP;
         } else {
            fight.tideWaves = 0;
            fight.tideGap = 0;
            announce(level, "\u00a78The tide drains away.");
         }
      }
   }

   private static void taunt(ServerLevel level, Fight fight) {
      String line = switch (RANDOM.nextInt(5)) {
         case 0 -> "\"\u00a7fKeep running. It warms you up.\u00a7f\"";
         case 1 -> "\"\u00a7fAHAHA! \u00a7fLouder. I like it louder.\u00a7f\"";
         case 2 -> "\"\u00a7fThin blood. \u00a78Did you skip dinner?\u00a7f\"";
         case 3 -> "\"\u00a7fThe door's locked. \u00a7fI'm the door.\u00a7f\"";
         default -> "\"\u00a7fDawn's hours away. \u00a74I checked.\u00a7f\"";
      };
      announce(level, SAY + line);
   }

   // ------------------------------------------------------------- blood rain

   private static void startBloodRain(ServerLevel level, Mob boss, Fight fight) {
      // The blood moon rises for the length of the rain.
      Fx.bloodMoon(level, ParticleTypes.CRIMSON_SPORE, boss.position(), 6.0, (int)RAIN_TICKS, MOON);
      fight.rainUntil = ServerClock.clock(level) + RAIN_TICKS;
      fight.nextRain = ServerClock.clock(level) + RAIN_COOLDOWN + RAIN_TICKS;
      fight.nextRainPulse = ServerClock.clock(level);
      announce(level, SAY + "\"\u00a7fLook up.\u00a7f\"");
      announce(level, "\u00a78The sky starts to bleed. \u00a77Get under a roof.");
      for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
         if (fight.participants.contains(p.getUUID()) && p.level() == level) {
            Fx.emberRain(level, ParticleTypes.CRIMSON_SPORE, p.position(), 7.0, RAIN_TICKS, CRIMSON);
         }
      }
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.ENDER_DRAGON_GROWL, SoundSource.HOSTILE, 1.4F, 0.5F);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.PHANTOM_AMBIENT, SoundSource.HOSTILE, 1.8F, 0.5F);
      bloodBurst(level, boss.getX(), boss.getY() + 1.5, boss.getZ(), 90);
   }

   /** The phase-two downpour: bleeding sky, bleeding players, and she drinks it. */
   private static void tickBloodRain(ServerLevel level, Mob boss, Fight fight, long now) {
      if (now >= fight.rainUntil) {
         fight.rainUntil = 0L;
         announce(level, SAY + "\"\u00a7fDry already? \u00a74Pity.\u00a7f\"");
         return;
      }

      // Visuals every tick for vanilla clients, so the sky reads as raining blood. Modded
      // clients already have the ember rain sent when it started.
      com.fortuneandfavors.net.FfVfx.enter();
      try {
         for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
            if (fight.participants.contains(p.getUUID())) {
               Fx.vanilla(level, 
                  ParticleTypes.CRIMSON_SPORE, p.getX(), p.getY() + 6.0, p.getZ(), 8, 5.0, 0.5, 5.0, 0.35
               );
            }
         }
      } finally {
         com.fortuneandfavors.net.FfVfx.exit();
      }

      if (now < fight.nextRainPulse) {
         return;
      }
      fight.nextRainPulse = now + BLOOD_RAIN_PULSE;

      float drained = 0.0F;
      int hits = 0;
      for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
         if (!fight.participants.contains(p.getUUID()) || !p.isAlive()) {
            continue;
         }
         BlockPos at = p.blockPosition();
         boolean exposed = level.canSeeSky(at.above());
         if (!exposed) {
            continue;
         }
         p.hurtServer(level, level.damageSources().magic(), 2.0F);
         p.addEffect(new MobEffectInstance(MobEffects.WITHER, 60, 0, false, false, true));
         drained += 2.0F;
         hits++;
      }
      if (hits > 0) {
         float rainHeal = Math.min(MAX_RAIN_HEAL_PER_TICK, drained * RAIN_LIFESTEAL);
         boss.setHealth(Math.min(boss.getMaxHealth(), boss.getHealth() + rainHeal));
         level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.GENERIC_DRINK, SoundSource.HOSTILE, 0.9F, 0.5F);
      }
   }

   // ------------------------------------------------------------ death & loot

   /**
    * Intercepts the killing blow so the death can be performed rather than
    * animated. She never simply dies - she is reduced to her last blood and
    * brought down by her own rain.
    *
    * @return {@code null} when this is not our boss (let vanilla decide),
    *         otherwise {@code false} to cancel the lethal damage.
    */
   public static Boolean onLethalDamage(Entity entity, float amount) {
      if (!(entity instanceof Mob boss) || !isScarletDevil(boss)) {
         return null;
      }
      if (amount < boss.getHealth()) {
         return null;
      }
      Fight fight = FIGHTS.get(boss.getUUID());
      if (fight == null) {
         // No fight owns this phantom. Never swallow the blow: cancelling a
         // lethal hit for a boss the manager does not know about is how a
         // "boss" becomes permanently unkillable. Let vanilla have it.
         return null;
      }
      if (fight.dying) {
         // The death ceremony is already playing - ignore further lethal blows.
         return Boolean.FALSE;
      }
      fight.dying = true;
      fight.deathTicks = 90;
      fight.lungeTicks = 0;
      fight.pactTicks = 0;
      fight.pactTarget = null;
      fight.brands.clear();
      fight.tideTicks = 0;
      fight.tideGap = 0;
      ServerLevel level = (ServerLevel) boss.level();
      boss.setInvulnerable(true);
      announce(level, SAY + "\"\u00a7fNo. \u00a7fNo, no, I'm not \u00a74done\u00a7f-\"");
      Fx.spiral(level, ParticleTypes.CRIMSON_SPORE, boss.position(), 9.0, fight.deathTicks, CRIMSON);
      Fx.heartbeat(level, ParticleTypes.CRIMSON_SPORE, boss.position().add(0.0, 0.1, 0.0), 4.0, fight.deathTicks, BLOOD_DARK);
      Fx.aura(level, ParticleTypes.CRIMSON_SPORE, boss.position(), 3.0, fight.deathTicks, MOON);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.PHANTOM_HURT, SoundSource.HOSTILE, 1.6F, 0.5F);
      return Boolean.FALSE;
   }

   private static void tickDeath(ServerLevel level, Mob boss, Fight fight) {
      fight.deathTicks--;
      double progress = 1.0 - Math.min(1.0, fight.deathTicks / 90.0);

      // Her last act is an uncontrolled blood rain, thinning as she comes apart. She sinks
      // as it falls, back towards the pool she came out of.
      Fx.vanilla(level, ParticleTypes.CRIMSON_SPORE, boss.getX(), boss.getY() + 4.0, boss.getZ(), 10 + (int) (progress * 20.0), 4.0, 0.8, 4.0, 0.3);
      drawPool(level, boss);
      if (fight.deathTicks > 20) {
         boss.setPos(boss.getX(), boss.getY() - 0.012, boss.getZ());
      }
      if (fight.deathTicks % 20 == 0 && fight.deathTicks > 0) {
         Fx.shatter(level, ParticleTypes.CRIMSON_SPORE, boss.position().add(0.0, 1.0, 0.0), 0.8 + progress, CRIMSON);
      }
      if (fight.deathTicks % 15 == 0) {
         announce(level, SAY + (fight.deathTicks > 45
            ? "\"\u00a7fGive it back. \u00a74That's mine-\""
            : "\"\u00a7f...it's so bright out here.\""));
         level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.PHANTOM_HURT, SoundSource.HOSTILE, 1.2F, 0.5F);
      }
      if (fight.deathTicks > 0) {
         return;
      }

      // The collapse: one final crimson implosion, then nothing.
      bloodBurst(level, boss.getX(), boss.getY() + 1.0, boss.getZ(), 160);
      Vec3 heart = boss.position().add(0.0, 1.0, 0.0);
      Fx.flare(level, ParticleTypes.CRIMSON_SPORE, heart, 3.4, MOON);
      Fx.starburst(level, ParticleTypes.CRIMSON_SPORE, heart, 7.0, CRIMSON);
      Fx.shockwave(level, ParticleTypes.CRIMSON_SPORE, boss.position(), 12.0, BLOOD_DARK);
      Fx.petals(level, ParticleTypes.CRIMSON_SPORE, heart, 3.0, 60, CRIMSON);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), ModSounds.BOSS_DEATH, SoundSource.HOSTILE, 1.5F, 0.6F);
      level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.PHANTOM_DEATH, SoundSource.HOSTILE, 1.5F, 0.5F);
      announce(level, "\u00a74\u00a7lThe Scarlet Devil \u00a7rcomes apart into red mist, \u00a77and the mist blows away.");

      dropLoot(level, boss, fight);
      if (fight.bar != null) {
         fight.bar.removeAllPlayers();
         fight.bar.setVisible(false);
      }
      FIGHTS.remove(boss.getUUID());
      boss.discard();
   }

   private static void dropLoot(ServerLevel level, Mob boss, Fight fight) {
      drop(level, boss, ModItems.scarletTrophy());
      // ONE forge material, not two: the Crimson Essence that used to sit beside
      // the Core did the same job and only ever muddied the set.
      drop(level, boss, ModItems.bloodsoakedCore());
      drop(level, boss, ModItems.bloodsoakedCore());
      if (RANDOM.nextFloat() < 0.35F) {
         drop(level, boss, ModItems.bloodsoakedCore());
      }
      // One roll at one of her three legendary weapons, not three independent
      // 40% rolls that could hand over the entire set in a single kill. The whole
      // set is what the loot box is for.
      if (RANDOM.nextFloat() < 0.2F) {
         drop(level, boss, switch (RANDOM.nextInt(3)) {
            case 0 -> ModItems.scarletFang();
            case 1 -> ModItems.scarletGrimoire();
            default -> ModItems.bloodPrism();
         });
      }
      // Diamonds and netherite scrap off a boss that also drops nine other things
      // was noise, not reward.
      if (RANDOM.nextFloat() < 0.2F) {
         drop(level, boss, CustomEnchantments.tome("ff_lifesteal", 1 + RANDOM.nextInt(3)));
      }

      // Three boxes, guaranteed, into the inventory of everyone who fought her.
      BossPayout.payBoxes(level, fight.participants, ModItems::scarletLootBox, BossPayout.BOXES_PER_KILL, "\u00a74Scarlet Loot Box");
      for (UUID id : fight.participants) {
         ServerPlayer p = level.getServer().getPlayerList().getPlayer(id);
         if (p != null && p.isAlive()) {
            Advancements.grant(p, "kill_scarlet");
         }
      }
   }

   private static void drop(ServerLevel level, Mob boss, ItemStack stack) {
      if (stack != null && !stack.isEmpty()) {
         level.addFreshEntity(new ItemEntity(level, boss.getX(), boss.getY() + 0.6, boss.getZ(), stack));
      }
   }

   private static void despawn(ServerLevel level, Mob boss, Fight fight, String reason) {
      BossManager.recallGearRevenants(level.getServer(), fight.participants, "The Scarlet Devil withdrew");
      if (boss.isAlive()) {
         bloodBurst(level, boss.getX(), boss.getY() + 1.0, boss.getZ(), 60);
         level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.PHANTOM_DEATH, SoundSource.HOSTILE, 1.0F, 0.6F);
         boss.discard();
      }
      announce(level, "\u00a74The Scarlet Devil \u00a7r- \u00a77" + reason);
      release(fight);
   }

   private static void release(Fight fight) {
      if (fight.bar != null) {
         fight.bar.removeAllPlayers();
         fight.bar.setVisible(false);
      }
      FIGHTS.remove(fight.bossId);
   }

   // ------------------------------------------------- player death -> servant

   /**
    * A player died to her. She laughs (the requested line) and her blood claims the
    * body: a bone servant rises wearing copies of the victim's kit and fighting
    * everyone still in the arena, while the victim keeps everything they owned.
    *
    * <p>Called from the death handler like any other death feature - nothing stands
    * down for it any more, so Nice Keep Inventory and the drop rules have already
    * run their normal course.
    */
   public static void onPlayerDeath(ServerPlayer victim) {
      if (victim == null) {
         return;
      }
      Fight match = null;
      Mob boss = null;
      for (Fight f : FIGHTS.values()) {
         if (f.participants.contains(victim.getUUID())) {
            match = f;
            Entity e = findEntity(victim.level().getServer(), f.bossId);
            if (e instanceof Mob m) {
               boss = m;
            }
            break;
         }
      }
      if (match == null) {
         return;
      }
      if (!(victim.level() instanceof ServerLevel level)) {
         return;
      }

      // The laugh. Her single most recognisable line, and the reason players
      // remember which boss killed them.
      announce(level, SAY + "\"\u00a7fAHAHAHA! \u00a7fGet up. \u00a74You work for me now.\u00a7f\"");
      level.playSound(null, victim.getX(), victim.getY(), victim.getZ(), SoundEvents.WITCH_CELEBRATE, SoundSource.HOSTILE, 1.2F, 0.9F);

      if (boss != null && boss.isAlive()) {
         boss.setHealth(Math.min(boss.getMaxHealth(), boss.getHealth() + DEATH_SIP));
      }

      Set<UUID> targets = new HashSet<>(match.participants);
      BossManager.spawnGearRevenant(
         level,
         victim,
         targets,
         "\u00a74"
            + victim.getName().getString()
            + " has been claimed by the Scarlet Devil! \u00a77A bone servant rises wearing a copy of their kit - their own gear is untouched."
      );
   }

   // ---------------------------------------------------------- item behaviour

   /** Scarlet-Blood right-click: consumes the vial and summons her. */
   public static String useScarletBlood(ServerPlayer player, ItemStack held) {
      String err = summon(player);
      if (err != null) {
         return err;
      }
      if (!player.getAbilities().instabuild) {
         held.shrink(1);
      }
      return null;
   }

   // ------------------------------------------------------------------ helpers

   private static void refreshParticipants(MinecraftServer server, ServerLevel level, Mob boss, Fight fight) {
      // Anyone who lands a hit on her, or stands inside her arena, is a target -
      // the same "join by showing up" rule the other scripted boss uses.
      for (ServerPlayer p : level.getPlayers(pl -> pl != null && pl.isAlive() && !pl.isSpectator())) {
         if (p.distanceToSqr(boss) < ARENA_RADIUS * ARENA_RADIUS) {
            if (fight.participants.add(p.getUUID())) {
               Chat.raw(p, "\u00a74The Scarlet Devil\u00a7r \u00a77has seen you.");
            }
         }
      }
   }

   private static ServerPlayer nearestTarget(ServerLevel level, Mob boss, Fight fight) {
      ServerPlayer best = null;
      double bestDist = Double.MAX_VALUE;
      for (UUID id : fight.participants) {
         ServerPlayer p = level.getServer().getPlayerList().getPlayer(id);
         if (p == null || !p.isAlive() || !p.level().equals(level)) {
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

   private static Entity findEntity(MinecraftServer server, UUID id) {
      if (server == null) {
         return null;
      }
      for (ServerLevel l : server.getAllLevels()) {
         Entity e = l.getEntity(id);
         if (e != null) {
            return e;
         }
      }
      return null;
   }

   private static float faceYaw(Entity from, Entity to) {
      double dx = to.getX() - from.getX();
      double dz = to.getZ() - from.getZ();
      return (float) (Math.toDegrees(Math.atan2(-dx, dz)));
   }

   /**
    * She is a no-gravity phantom that this script moves by hand, so a hand-placed
    * position can land inside a wall (or inside the player she is standing on) and
    * she simply stays there - which is exactly the "she goes inside blocks" look.
    * If her box is buried, lift her to the first clear space above; failing that,
    * step her out towards the player; failing that, climb.
    */
   private static void keepOutOfBlocks(ServerLevel level, Mob boss, ServerPlayer target) {
      // The floor under her decides her height. This is also what fixes the old
      // "she climbs into blocks and ends up overhead" loop: the unstuck code below
      // only ever pushed her *up*, so a fight that started with her buried in a
      // hillside ended with her permanently above the treeline, out of reach.
      BossGrounding.clampToGround(level, boss, 1.2);
      if (level.noCollision(boss, boss.getBoundingBox())) {
         return;
      }
      for (int dy = 1; dy <= 4; dy++) {
         if (level.noCollision(boss, boss.getBoundingBox().move(0.0, dy, 0.0))) {
            boss.setPos(boss.getX(), boss.getY() + dy, boss.getZ());
            boss.hurtMarked = true;
            return;
         }
      }
      if (target != null) {
         Vec3 flat = target.position().subtract(boss.position());
         flat = new Vec3(flat.x, 0.0, flat.z);
         if (flat.lengthSqr() > 1.0E-4) {
            flat = flat.normalize().scale(1.6);
            if (level.noCollision(boss, boss.getBoundingBox().move(flat.x, 0.0, flat.z))) {
               boss.setPos(boss.getX() + flat.x, boss.getY(), boss.getZ() + flat.z);
               boss.hurtMarked = true;
               return;
            }
         }
      }
      // Last resort: stand her on whatever surface this column has, rather than
      // stacking her one block higher every tick forever.
      double floor = BossGrounding.groundY(level, boss.getX(), boss.getZ(), boss.getY());
      boss.setPos(boss.getX(), floor, boss.getZ());
      boss.hurtMarked = true;
   }

   private static void announce(ServerLevel level, String message) {
      if (level == null || level.getServer() == null) {
         return;
      }
      if (!BossChat.allowed("scarlet", message)) {
         return;
      }
      for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
         Chat.raw(p, message);
      }
   }

   // ------------------------------------------------------------- particle art

   /** A vertical column of rising blood, framed by dark red dust. */
   private static void drawPool(ServerLevel level, Mob boss) {
      double x = boss.getX();
      double y = boss.getY();
      double z = boss.getZ();
      Fx.vanilla(level, ParticleTypes.CRIMSON_SPORE, x, y, z, 12, 0.5, 0.2, 0.5, 0.04);
      Fx.vanilla(level, new DustParticleOptions(-65536, 1.2F), x, y + 0.2, z, 6, 0.6, 0.2, 0.6, 0.01);
   }

   private static void drawAura(ServerLevel level, Mob boss) {
      long t = ServerClock.clock(level);
      double angle = t * 0.12;
      double x = boss.getX() + Math.cos(angle) * 1.1;
      double z = boss.getZ() + Math.sin(angle) * 1.1;
      Fx.vanilla(level, new DustParticleOptions(-65536, 0.9F), x, boss.getY() + 1.2, z, 1, 0.0, 0.0, 0.0, 0.0);
      Fx.vanilla(level, ParticleTypes.CRIMSON_SPORE, boss.getX(), boss.getY() + 1.4, boss.getZ(), 2, 0.4, 0.3, 0.4, 0.01);
   }

   private static void drawTrail(ServerLevel level, Mob boss) {
      Fx.vanilla(level, ParticleTypes.CRIMSON_SPORE, boss.getX(), boss.getY() + 0.7, boss.getZ(), 6, 0.25, 0.25, 0.25, 0.02);
      Fx.vanilla(level, new DustParticleOptions(-65536, 1.0F), boss.getX(), boss.getY() + 0.7, boss.getZ(), 2, 0.2, 0.2, 0.2, 0.01);
   }

   private static void bloodBurst(ServerLevel level, double x, double y, double z, int count) {
      Fx.vanilla(level, ParticleTypes.CRIMSON_SPORE, x, y, z, count, 1.2, 1.4, 1.2, 0.12);
      Fx.vanilla(level, ParticleTypes.DAMAGE_INDICATOR, x, y, z, count / 3, 1.0, 1.0, 1.0, 0.1);
      Fx.vanilla(level, new DustParticleOptions(-65536, 1.4F), x, y, z, count / 2, 1.0, 1.2, 1.0, 0.06);
   }

   /** The Scarlet-Blood's model number, exposed so the item audit and the pack
    *  generator can never drift from {@link ModItems}. */
   public static int bloodModelId() {
      return SCARLET_BLOOD_MODEL;
   }
}
