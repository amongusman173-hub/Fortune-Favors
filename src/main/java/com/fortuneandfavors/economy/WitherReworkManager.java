package com.fortuneandfavors.economy;

import com.fortuneandfavors.ModItems;
import com.fortuneandfavors.mixin.WitherBossAccessor;
import com.fortuneandfavors.net.FfMusicPayload;
import com.fortuneandfavors.util.Chat;
import com.mojang.math.Transformation;
import com.fortuneandfavors.util.PerfMonitor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Entity.RemovalReason;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.hurtingprojectile.WitherSkull;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.Level.ExplosionInteraction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Reworked Wither ("The Ascended Wither"). Turns the vanilla fly-around into a
 * real multi-phase fight: bedrock charge, combo-breaker burst, homing skull
 * seekers, phase-2 ascension slams, wither-skeleton knight waves, side-head
 * wither-mist pools, an anti-burrow piercing skull that shreds cover and
 * rebuilds it, a projectile parry at 30%, and a soul-sand domain expansion.
 *
 * <p>On top of that sit the newer tools: the charging <b>Wither Nuke</b>, a
 * 360-degree <b>Soul Nova</b>, <b>Skull Rain</b> from the sky, a life-stealing
 * <b>Wither Drain</b> beam, a reeling <b>Soul Pull</b> and a <b>Shred Blink</b>
 * that teleports behind its target - plus a permanent soul-fire aura, a bigger
 * arrival show, a longer death ceremony, and a TROPHY offer that permanently
 * SUPERCHARGES the fight. The entity keeps a plain "Wither" name; the Ascended
 * branding lives in the boss bar.
 *
 * Custom block-display spawn/death animations and a nether-star handoff, and
 * the boss drops its own Wither Loot Box (the three wither legendaries).
 *
 * <p>The whole thing is a server toggle (ModConfig.witherRework, managed from
 * the /ff admin config menu): when off, nothing here runs and vanilla wither
 * behaviour is untouched. No mixins into WitherBoss - everything is done from
 * the outside via velocity nudges, damage events and entity hooks, so the
 * fight degrades gracefully instead of breaking the game if something throws.
 */
public final class WitherReworkManager {
   private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("fortuneandfavors-wither");
   private static final Random RANDOM = new Random();

   /** Per-wither fight state, keyed by the WitherBoss entity's UUID. */
   private static final Map<UUID, WitherFight> fights = new HashMap<>();

   private static final int BASE_MAX_HEALTH = 600;
   private static final double BASE_ARMOR = 12.0;
   private static final double BASE_ARMOR_TOUGHNESS = 6.0;
   /** Longer, fancier charge-up than vanilla's 220-tick stand. */
   private static final int SPAWN_ANIM_TICKS = 260;
   private static final double SLAM_VERTICAL_SPEED = 1.15;

   /** Identity markers live in entity TAGS, not in the display name. The old
    *  name parsing broke as soon as the name changed, and silently promoted a
    *  re-adopted fight to KING. Tags survive chunk reloads with the entity. */
   private static final String TAG_ASCENDED = "ff_ascended_wither";
   private static final String TAG_KING = "ff_wither_king";
   private static final String TAG_SUPER = "ff_wither_super";

   /** Plain, vanilla-style name for the entity itself - the "Ascended"
    *  branding only shows in the boss bar and announcements. */
   private static final String NAME_PLAIN = "Wither";
   private static final String NAME_KING = "Wither King";
   private static final String NAME_SUPER = "Wither King \u2726 SUPERCHARGED";

   /** Wither Nuke: charge-up ticks, then a boss-sized detonation. */
   private static final int NUKE_CHARGE_TICKS = 60;
   /** Drain beam duration. */
   private static final int BEAM_TICKS = 60;

   /** King fight (summon boosted with a Wither Skeleton Skull): stronger, not
    *  a stat wall - tuned so a well-geared group still wins with losses. */
   private static final float KING_HP_MULT = 1.5F;
   private static final float KING_DMG_MULT = 1.6F;
   private static final int KING_SKULLS = 2;
   private static final int KING_WAVE_SIZE = 4;

   private WitherReworkManager() {
   }

   // ------------------------------------------------------------------
   // Toggle + persistence (ModConfig fields live here so /ff config can
   // flip it without the manager knowing about IO).
   // ------------------------------------------------------------------

   private static boolean enabled = false;

   static boolean enabled() {
      return enabled;
   }

   /** Test hook + other packages: is the rework toggle on? */
   public static boolean isEnabled() {
      return enabled;
   }

   static void setEnabled(boolean on) {
      enabled = on;
      if (!on) {
         // Dropping the toggle while fights are live: leave the withers
         // standing (they become plain vanilla mobs again) but detach all
         // rework state so nothing keeps ticking for them.
         //
         // The music has to be taken off every screen first: a client's theme loops itself, so a
         // soundtrack nobody stopped would outlive the fight that owns it.
         for (Entry<UUID, WitherFight> e : new HashMap<>(fights).entrySet()) {
            WitherBoss body = e.getValue().bossRef;
            MinecraftServer owner = body != null && body.level() instanceof ServerLevel sl ? sl.getServer() : null;
            WitherMusic.stop(owner, e.getKey());
         }
         WitherMusic.clearAll();
         fights.clear();
         knights.clear();
         displays.clear();
         antiBurrow.clear();
         mistPools.clear();
         homingSkulls.clear();
         pendingRemovals.clear();
         pendingTransforms.clear();
      }
   }

   // ------------------------------------------------------------------
   // Public hooks (wired from ModEvents)
   // ------------------------------------------------------------------

   /** Server tick: advance all live fights, rebuild queues and pools. */
   public static void tick(MinecraftServer server) {
      // Robustness sweep: claim withers the spawn-charge path never reached
      // (summoned via commands/eggs, chunks that never AI-ticked, or the
      // toggle flipped on mid-fight). Every 100 ticks over the loaded entity
      // lists; just instanceof checks, so it stays cheap.
      if (enabled && server.getTickCount() % 100L == 0L) {
         claimSweep(server);
      }

      if (fights.isEmpty() && antiBurrow.isEmpty() && pendingRemovals.isEmpty()
         && mistPools.isEmpty() && homingSkulls.isEmpty() && pendingTransforms.isEmpty()) {
         return;
      }

      long start = System.nanoTime();
      try {
         rebuildTick(server);
         tickPendingTransforms(server);
         // Global once-per-tick systems (NOT per fight - with the per-fight
         // placement two concurrent withers double-ticked every pool/skull:
         // double wither stacks and doubled particle output).
         tickMistPools();
         tickAllHoming(server);

         Iterator<Map.Entry<UUID, WitherFight>> it = fights.entrySet().iterator();

         while (it.hasNext()) {
            Map.Entry<UUID, WitherFight> e = it.next();
            WitherFight f = e.getValue();
            WitherBoss w = findWither(server, e.getKey());

            if (w == null) {
               // Entity gone (unloaded chunk, command kill, ...) - take the
               // whole entourage with it so nothing is left behind.
               //
               // And *its health bar*, which was the one thing this branch did forget. The
               // bar is the wither's own vanilla {@code ServerBossEvent}, handed to every
               // player by the client tracker and taken off again by {@link #finishDeath} -
               // which only runs when the body is still there to die. A body removed by a
               // command, a plugin or a world event therefore left its bar on every screen it
               // had been sent to, for the rest of the session, over an empty patch of ground.
               hideBar(f);
               WitherMusic.stop(server, e.getKey());
               cleanupAll(server, e.getKey());
               it.remove();
            } else if (w.level() instanceof ServerLevel level) {
               fightTick(server, level, w, f);
            }
         }

         tickPendingRemovals(server);
      } finally {
         PerfMonitor.record("wither rework tick", System.nanoTime() - start);
      }
   }

   /** A WitherBoss finished spawning (or was already ticked) in a level.
    *  Claims it for the rework when the toggle is on and it isn't ours yet. */
   public static void onWitherSpawn(WitherBoss wither) {
      if (!enabled
         || !(wither.level() instanceof ServerLevel level)
         || wither.level().isClientSide()
         || fights.containsKey(wither.getUUID())) {
         return;
      }

      try {
         // The board is told a wither has arrived before the claim can decline it: a
         // player-summoned wither is a fight somebody may walk away from, and that is exactly the
         // job the dynamic board posts ("the party that summoned it are all down"). It costs one
         // lookup here and is the only place the mod sees the arrival.
         DynamicContractsManager.onWitherSummoned(level, wither);
         // Only the vanilla spawn charge-up takes the animated path; a wither
         // spawned by other means (commands, spawn eggs) is picked up by the
         // periodic claim sweep instead.
         if (wither.getInvulnerableTicks() <= 0) {
            return;
         }
         claimWither(level, wither, true);
      } catch (Throwable ignored) {
      }
   }

   /** Test/query hook: is this wither currently owned by the rework? */
   public static boolean isFightLive(java.util.UUID id) {
      return fights.containsKey(id);
   }

   /** Claim a wither for the rework. withAnimation=true runs the full soul
    *  charge-up show (only valid while the vanilla spawn charge is running,
    *  since it re-pins the countdown); false jumps straight into the fight. */
   private static void claimWither(ServerLevel level, WitherBoss w, boolean withAnimation) {
      try {
         // King detection by name, not max HP: maxHealth > 300 also matches an
         // already-Ascended wither being re-adopted after a chunk reload, which
         // silently promoted every resumed fight to a KING.
         boolean alreadyOurs = w.entityTags().contains(TAG_ASCENDED);
         boolean king = w.entityTags().contains(TAG_KING);
         boolean supercharged = w.entityTags().contains(TAG_SUPER);
         // The rework owns this body outright and it is not one of BossManager's fights, so it is
         // handed to the shared boss layer here - the aura and the surge every boss wears.
         BossEmpowerment.register(w);
         WitherFight f = new WitherFight(king);
         f.supercharged = supercharged;
         f.maxHealth = BASE_MAX_HEALTH * (f.king ? KING_HP_MULT : 1.0F);
         AttributeInstance hp = w.getAttribute(Attributes.MAX_HEALTH);
         if (hp != null) {
            hp.setBaseValue(BASE_MAX_HEALTH * (f.king ? KING_HP_MULT : 1.0F));
         }
         if (withAnimation) {
            // Fresh vanilla spawn: it arrives at the vanilla 300 max, so pin it
            // straight to Ascended health.
            w.setHealth(BASE_MAX_HEALTH * (f.king ? KING_HP_MULT : 1.0F));
         } else {
            // Re-adopted mid-fight: keep its current health (a full heal here
            // would let players reset every fight by forcing a chunk reload).
            w.setHealth(Math.min(w.getHealth(), f.maxHealth));
         }

         AttributeInstance armor = w.getAttribute(Attributes.ARMOR);
         if (armor != null) {
            armor.setBaseValue(BASE_ARMOR);
         }

         AttributeInstance tough = w.getAttribute(Attributes.ARMOR_TOUGHNESS);
         if (tough != null) {
            tough.setBaseValue(BASE_ARMOR_TOUGHNESS);
         }

         // A modest move-speed bump so it can actually keep up with players.
         AttributeInstance speed = w.getAttribute(Attributes.MOVEMENT_SPEED);
         if (speed != null) {
            speed.setBaseValue(speed.getBaseValue() + (f.king ? 0.08 : 0.04));
         }

         // Identity is tag-based, so the name is free to read like a plain
         // vanilla wither (the Ascended branding lives in the boss bar).
         w.addTag(TAG_ASCENDED);
         if (f.king) {
            w.addTag(TAG_KING);
         }
         if (f.supercharged) {
            w.addTag(TAG_SUPER);
         }
         w.setCustomName(Component.literal(nameFor(f)));
         w.setCustomNameVisible(true);
         w.setPersistenceRequired();
         w.setDropChance(EquipmentSlot.MAINHAND, 0.0F);
         w.setDropChance(EquipmentSlot.OFFHAND, 0.0F);

         f.spawnPos = w.position();
         fights.put(w.getUUID(), f);

         if (withAnimation) {
            // Re-run our own charge-up: pausing the vanilla AI freezes its
            // invulnerable-tick countdown, so we shorten it to our animation
            // length and let it hit zero right as the display show ends.
            w.setInvulnerableTicks(SPAWN_ANIM_TICKS);
            f.summonAnim = SPAWN_ANIM_TICKS;
            startSpawnAnimation(level, w, f);
            // The theme's own opening: the summon cut, and a clock that hands the fight over to the
            // looping cut at 0:21 exactly as the track does - see WitherMusic.
            WitherMusic.start(level, w.getUUID(), w.position(), FfMusicPayload.TRACK_INTRO);
            f.musicIntroTicks = WitherMusic.INTRO_TICKS;
            LOGGER.info("Wither rework: Ascended Wither {} claimed{} - {} HP, {}s charge-up", w.getUUID(), f.king ? " (KING)" : "", (int)f.maxHealth, SPAWN_ANIM_TICKS / 20);
         } else {
            // Adopted mid-fight: there is no summon to score, so it comes in on the loop.
            WitherMusic.start(level, w.getUUID(), w.position(), FfMusicPayload.TRACK_LOOP);
            LOGGER.info("Wither rework: claimed existing wither {} (no spawn show, straight into the fight)", w.getUUID());
         }
      } catch (Throwable t) {
         LOGGER.error("Wither rework: claim failed for {}", w.getUUID(), t);
      }
   }

   /** Periodic sweep over every loaded level: adopt any WitherBoss we don't
    *  already own (withers summoned outside the structure-spawn path, or the
    *  toggle flipped on while one was already live). */
   private static void claimSweep(MinecraftServer server) {
      for (ServerLevel level : server.getAllLevels()) {
         for (Entity e : level.getAllEntities()) {               if (e instanceof WitherBoss w && !fights.containsKey(w.getUUID())) {
               DynamicContractsManager.onWitherSummoned(level, w);
               claimWither(level, w, w.getInvulnerableTicks() > 0);
            }
         }
      }
   }

   /** Damage gate (runs BEFORE damage applies). Two jobs: while the wither is
    *  ascending for a slam it is immune to projectiles, and when a hit would
    *  kill it the hit is swallowed and the custom death sequence starts
    *  instead - the wither stays standing (invulnerable, health pinned) while
    *  the animation and star ceremony run. */
   public static boolean onWitherDamageAttempt(WitherBoss w, DamageSource source, float amount) {
      WitherFight f = fights.get(w.getUUID());
      if (f == null) {
         return true;
      }
      if (f.deathAnim > 0) {
         // The custom death sequence owns the entity. /kill and void damage
         // bypass invulnerability - letting them apply here would fire the
         // vanilla death (and its loot) on top of ours: double drops.
         return false;
      }

      try {
         // Ascension / nuke charge-up: projectiles can't touch it.
         if ((f.slamImmune || f.nukeImmune) && source.getDirectEntity() instanceof Projectile) {
            if (w.level() instanceof ServerLevel lvl) {
               com.fortuneandfavors.net.FfVfx.particles(lvl, ParticleTypes.END_ROD, w.getX(), w.getEyeY(), w.getZ(), 4, 0.6, 0.6, 0.6, 0.04);
            }
            return false;
         }

         if (amount >= w.getHealth() && w.level() instanceof ServerLevel level && !w.isInvulnerableTo(level, source)) {
            beginDeath(level, w, f, source);
            return false;
         }
      } catch (Throwable ignored) {
      }

      return true;
   }

   /** Called after damage was applied. Triggers the projectile parry at 30%
    *  HP once per fight. */
   public static void onWitherDamaged(WitherBoss w, DamageSource source, float amount) {
      WitherFight f = fights.get(w.getUUID());
      if (f == null || f.deathAnim > 0 || !(w.level() instanceof ServerLevel level)) {
         return;
      }

      try {
         if (!f.parryDone && w.getHealth() <= f.maxHealth * 0.30F) {
            f.parryDone = true;
            startParry(level, w, f);
         }
      } catch (Throwable ignored) {
      }
   }

   /** A player just took damage while a fight is live. Phase 2+ domain aura
    *  tags them as the boss's target; a withered player hit near the KING
    *  feeds it (their withering siphons back into the boss). */
   public static void onPlayerDamaged(ServerPlayer victim, DamageSource source, float taken) {
      if (fights.isEmpty() || taken <= 0.0F || source.getEntity() == victim) {
         return;
      }

      try {
         WitherFight f = fightNear(victim, 24.0);
         if (f == null) {
            return;
         }

         WitherBoss w = f.bossRef;
         if (w == null || !w.isAlive()) {
            return;
         }

         // Domain aura: any hit the withered player takes counts as the domain
         // claiming them, so the boss keeps focus.
         if (f.domainTicks > 0 && victim.hasEffect(MobEffects.WITHER) && w.getTarget() != victim) {
            w.setTarget(victim);
         }

         // Wither feed: a still-withered player damaged within 16 blocks
         // feeds the boss a sliver of max HP (2s cooldown, boss-sourced
         // damage excluded so its own attacks can't loop-heal). The KING
         // siphons twice as much.
         if (source.getEntity() != w
            && victim.distanceToSqr(w) < 256.0
            && victim.hasEffect(MobEffects.WITHER)
            && ServerClock.clock(victim.level()) - f.lastFeed >= 40L) {
            f.lastFeed = ServerClock.clock(victim.level());
            float heal = f.maxHealth * (f.king ? 0.003F : 0.0015F);
            w.heal(heal);
            for (int i = 0; i < 4; i++) {
               double t = i / 4.0;
               victim.level().sendParticles(
                  ParticleTypes.SCULK_SOUL,
                  Mth.lerp(t, victim.getX(), w.getX()),
                  Mth.lerp(t, victim.getEyeY(), w.getEyeY()),
                  Mth.lerp(t, victim.getZ(), w.getZ()),
                  1, 0.1, 0.1, 0.1, 0.0
               );
            }
            victim.sendOverlayMessage(Component.literal(f.king ? "§8Your withering feeds the King..." : "§8Your withering feeds the Wither..."));
         }
      } catch (Throwable ignored) {
      }
   }

   /** Player right-clicked the boss with a Wither Skeleton Skull (during the
    *  summon charge-up) or with a boss TROPHY (at any point in the fight).
    *  The skull promotes it to the KING; the trophy SUPERCHARGES it. Returns
    *  true when the offered item was consumed. */
   public static boolean onWitherRightClick(ServerPlayer player, WitherBoss target, ItemStack held) {
      WitherFight f = fights.get(target.getUUID());
      if (f == null || held == null || held.isEmpty() || f.deathAnim > 0) {
         return false;
      }

      boolean skull = held.is(Items.WITHER_SKELETON_SKULL);
      boolean trophy = isTrophy(held);
      // A skull only counts inside the summon window - once the fight has
      // started it must not double as a promotion token.
      if (skull && (f.king || f.summonAnim <= 0)) {
         return false;
      }
      if (!skull && !trophy) {
         return false;
      }
      if (trophy && f.supercharged) {
         Chat.msg(player, "&dThis Wither is already SUPERCHARGED - nothing left to offer it.");
         return false;
      }

      try {
         held.shrink(1);
         ServerLevel tLevel = (ServerLevel)target.level();

         if (!f.king) {
            promoteToKing(target, f);
         }

         if (trophy) {
            supercharge(tLevel, target, f);
         } else {
            LOGGER.info("Wither rework: KING promotion accepted - {} max HP, faster, Royal Knights", (int)f.maxHealth);
            showTitle(player, "§5&lT H E   K I N G   A N S W E R S", "§dThe skull burns away in its grasp...");
            tLevel.playSound(null, target.getX(), target.getY(), target.getZ(), SoundEvents.WITHER_SPAWN, SoundSource.HOSTILE, 2.0F, 0.55F);
            com.fortuneandfavors.net.FfVfx.particles(tLevel, ParticleTypes.SCULK_SOUL, target.getX(), target.getEyeY(), target.getZ(), 80, 1.5, 1.5, 1.5, 0.08);
         }
      } catch (Throwable t) {
         LOGGER.warn("Wither rework: offering item failed", t);
      }

      return true;
   }

   /** Any item that calls itself a Trophy can be offered to supercharge the
    *  fight (King Wither Skeleton Trophy, generic Boss Trophy, ...). */
   public static boolean isTrophy(ItemStack stack) {
      if (stack == null || stack.isEmpty()) {
         return false;
      }

      try {
         return stack.getHoverName().getString().toLowerCase(java.util.Locale.ROOT).contains("trophy");
      } catch (Throwable t) {
         return false;
      }
   }

   private static String nameFor(WitherFight f) {
      if (f.supercharged) {
         return NAME_SUPER;
      }
      return f.king ? NAME_KING : NAME_PLAIN;
   }

   /** Turn the fight into the KING variant (health, speed, name, tags). */
   private static void promoteToKing(WitherBoss target, WitherFight f) {
      f.king = true;
      f.parryDone = false;
      f.maxHealth = BASE_MAX_HEALTH * KING_HP_MULT;

      AttributeInstance hp = target.getAttribute(Attributes.MAX_HEALTH);
      if (hp != null) {
         hp.setBaseValue(f.maxHealth);
      }
      // During the summon show it hasn't fought yet, so it gets the full bar;
      // a mid-fight promotion keeps whatever health it has left.
      if (f.summonAnim > 0) {
         target.setHealth(f.maxHealth);
      } else {
         target.setHealth(Math.min(target.getHealth(), f.maxHealth));
      }

      AttributeInstance speed = target.getAttribute(Attributes.MOVEMENT_SPEED);
      if (speed != null) {
         speed.setBaseValue(speed.getBaseValue() + 0.04);
      }

      target.addTag(TAG_KING);
      target.setCustomName(Component.literal(nameFor(f)));
   }

   /** Trophy offered: the fight jumps a tier. Tankier, faster, shorter ability
    *  cooldowns, and a permanent purple soul-fire aura (see auraTick). */
   private static void supercharge(ServerLevel level, WitherBoss w, WitherFight f) {
      f.supercharged = true;
      f.maxHealth *= 1.25F;

      AttributeInstance hp = w.getAttribute(Attributes.MAX_HEALTH);
      if (hp != null) {
         hp.setBaseValue(f.maxHealth);
      }
      // Not a full reset: the offering pushes it back up to at least 60% of
      // the new bar, so a late-fight trophy is a real escalation, not an undo.
      w.setHealth(Math.max(w.getHealth(), f.maxHealth * 0.6F));

      AttributeInstance speed = w.getAttribute(Attributes.MOVEMENT_SPEED);
      if (speed != null) {
         speed.setBaseValue(speed.getBaseValue() + 0.05);
      }

      AttributeInstance armor = w.getAttribute(Attributes.ARMOR);
      if (armor != null) {
         armor.setBaseValue(BASE_ARMOR + 6.0);
      }

      w.addTag(TAG_SUPER);
      w.addTag(TAG_KING);
      w.setCustomName(Component.literal(nameFor(f)));
      w.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 400, 1, false, false));
      w.addEffect(new MobEffectInstance(MobEffects.SPEED, 400, 0, false, false));

      LOGGER.info("Wither rework: SUPERCHARGED - {} max HP, +armor, +speed, faster abilities", (int)f.maxHealth);
      superchargeFx(level, w, f);
   }

   /** The supercharge show: shockwave rings, a soul pillar, lightning around
    *  the arena and a hard sound stack so offering a trophy really lands. */
   private static void superchargeFx(ServerLevel level, WitherBoss w, WitherFight f) {
      com.fortuneandfavors.net.FfVfx.shape(level, com.fortuneandfavors.net.FfVfx.NOVA, ParticleTypes.SOUL_FIRE_FLAME, w.position(), Vec3.ZERO, 12.0, 0.0, 0x8C6CFF);
      com.fortuneandfavors.net.FfVfx.shape(level, com.fortuneandfavors.net.FfVfx.PILLAR, ParticleTypes.END_ROD, w.position(), Vec3.ZERO, 14.0, 0.0, 0xB4E6FF);
      double x = w.getX();
      double y = w.getY();
      double z = w.getZ();

      level.playSound(null, x, y, z, SoundEvents.WITHER_SPAWN, SoundSource.HOSTILE, 3.0F, 0.45F);
      level.playSound(null, x, y, z, SoundEvents.BEACON_ACTIVATE, SoundSource.HOSTILE, 2.0F, 0.5F);
      level.playSound(null, x, y, z, (net.minecraft.sounds.SoundEvent)SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 1.6F, 0.6F);

      // Expanding grounded rings.
      for (int ring = 0; ring < 3; ring++) {
         double r = 2.0 + ring * 2.4;
         for (int i = 0; i < 40; i++) {
            double a = i * (Math.PI / 20.0);
            com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.SOUL_FIRE_FLAME, x + Math.cos(a) * r, y + 0.15 + ring * 0.1, z + Math.sin(a) * r, 1, 0.0, 0.0, 0.0, 0.0
            );
         }
      }

      // Soul column + core flash.
      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.SCULK_SOUL, x, y + 1.0, z, 160, 1.0, 4.0, 1.0, 0.12);
      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.SOUL, x, y + 1.5, z, 120, 2.0, 3.0, 2.0, 0.1);
      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.EXPLOSION_EMITTER, x, y + 1.5, z, 3, 0.6, 0.8, 0.6, 0.05);
      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.END_ROD, x, y + 2.0, z, 60, 1.4, 2.4, 1.4, 0.1);
      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.TOTEM_OF_UNDYING, x, y + 1.5, z, 90, 1.6, 2.0, 1.6, 0.35);

      // Visual lightning ring (harmless) for scale.
      for (int i = 0; i < 6; i++) {
         double a = i * (Math.PI / 3.0);
         spawnVisualLightning(level, x + Math.cos(a) * 7.0, y, z + Math.sin(a) * 7.0);
      }

      showTitleNear(level, w.position(), 80, "§5&lSUPERCHARGED", "§dThe Wither takes the offering...");
      broadcastNear(level, w.position(), 80, "&5&lTHE WITHER IS SUPERCHARGED&7 - everything it does hits harder and comes faster!");

      // Boss bar colour + label follow the new tier.
      try {
         ServerBossEvent bar = ((WitherBossAccessor)(Object)w).fortuneandfavors$bossEvent();
         if (bar != null) {
            bar.setName(Component.literal("§5§lWither §d✦ SUPERCHARGED"));
            bar.setColor(net.minecraft.world.BossEvent.BossBarColor.PURPLE);
         }
      } catch (Throwable ignored) {
      }

      f.nextAbility = ServerClock.clock(w.level()) + 10L;
   }

   /** A purely cosmetic lightning bolt (no fire, no damage). */
   private static void spawnVisualLightning(ServerLevel level, double x, double y, double z) {
      try {
         net.minecraft.world.entity.LightningBolt bolt = (net.minecraft.world.entity.LightningBolt)EntityTypes.LIGHTNING_BOLT
            .create(level, EntitySpawnReason.COMMAND);
         if (bolt != null) {
            bolt.setPos(x, y, z);
            bolt.setVisualOnly(true);
            level.addFreshEntity(bolt);
         }
      } catch (Throwable ignored) {
      }
   }

   /** Is a live domain expansion currently claiming the ground around this
    *  player? Used by the wither-crown handler so the domain's wither cannot
    *  be converted away by crown regen - inside the sanctum, the wither
    *  sticks regardless of gear. */
   public static boolean inDomain(ServerPlayer p) {
      if (fights.isEmpty()) {
         return false;
      }

      for (WitherFight f : fights.values()) {
         WitherBoss w = f.bossRef;
         if (w != null && w.isAlive() && w.level() == p.level() && f.domainTicks > 0 && w.distanceToSqr(p) < 121.0) {
            return true;
         }
      }

      return false;
   }

   /**
    * The Ascended Wither's answer to a stopped clock.
    *
    * <p>A Pocket-Watch freezes the world around its wielder, which is a fair answer to a boss made
    * of scheduled beats - and no answer at all to this one, whose entire kit is the wither itself:
    * a stopped world is still a wither draining health out of the room. So the watch is refused
    * while an Ascended Wither is live and near, and it is the <b>Wither</b> that says so, in its own
    * voice - the stopwatch in your hand withers.
    */
   public static boolean witherRefusesTheWatch(ServerPlayer p) {
      if (p == null || fights.isEmpty()) {
         return false;
      }

      for (WitherFight f : fights.values()) {
         WitherBoss w = f.bossRef;
         if (w != null && w.isAlive() && !w.isRemoved() && w.level() == p.level() && w.distanceToSqr(p) < 4096.0) {
            w.level().playSound(null, w.getX(), w.getY(), w.getZ(), SoundEvents.WITHER_HURT, SoundSource.HOSTILE, 1.4F, 0.4F);
            Chat.msg(p, "&5The Ascended Wither&r&7 \u203a &fYour stopwatch withers in your hand - &7time belongs to the Wither.");
            fadeTheWatch(p);
            return true;
         }
      }

      return false;
   }

   /** A short wither effect on the hand that tried to hold time still: the price of asking. */
   private static void fadeTheWatch(ServerPlayer p) {
      try {
         p.addEffect(new MobEffectInstance(MobEffects.WITHER, 60, 1, false, false, true));
      } catch (Throwable ignored) {
      }
   }

   /** Display name for the boss codex. */
   public static String displayName() {
      return "The Ascended Wither";
   }

   /** Server stopping: drop all state (entities are going away anyway). */
   public static void clearAll() {
      clearAll(null);
   }

   /**
    * Forgets every reworked wither, and - when a server is given - takes the bodies, the
    * entourage and the health bars away with the state.
    *
    * <p>Without the bodies this is not a teardown, it is amnesia: {@code /ff boss wipe} used
    * to clear the maps and leave an invulnerable, scripted wither standing in the world with
    * nobody driving it and its bar still on every screen, which reads as the wipe not working.
    * The no-argument form is kept for the paths that have nothing to remove.
    */
   public static void clearAll(MinecraftServer server) {
      if (server != null) {
         for (Entry<UUID, WitherFight> e : new HashMap<>(fights).entrySet()) {
            WitherFight f = e.getValue();
            try {
               hideBar(f);
            } catch (Throwable ignored) {
            }
            WitherMusic.stop(server, e.getKey());
            WitherBoss w = findWither(server, e.getKey());
            if (w != null) {
               w.remove(RemovalReason.DISCARDED);
            }
            cleanupAll(server, e.getKey());
         }
      }

      fights.clear();
      knights.clear();
      displays.clear();
      antiBurrow.clear();
      mistPools.clear();
      homingSkulls.clear();
      pendingRemovals.clear();
      pendingTransforms.clear();
      WitherMusic.clearAll();
   }

   /**
    * Takes the wither's own health bar off every screen it was sent to, from the reference
    * the fight kept. Used by every exit that does not run a death, where the body is already
    * beyond asking.
    */
   private static void hideBar(WitherFight f) {
      if (f == null || f.bossRef == null) {
         return;
      }
      ServerBossEvent bar = ((WitherBossAccessor)(Object) f.bossRef).fortuneandfavors$bossEvent();
      if (bar != null) {
         bar.removeAllPlayers();
         bar.setVisible(false);
      }
   }

   // ------------------------------------------------------------------
   // Fight state
   // ------------------------------------------------------------------

   private static final class WitherFight {
      boolean king;
      /** The beat overlay is running for this fight (the King below half health). */
      boolean beatOn;
      /** Skull-storm waves still to fall after the nuke's first (every 15 ticks). */
      int stormWaves;
      /** Where the death animation holds the body. */
      Vec3 deathPos;
      /** Who the current Grave Bell toll has already hit (each wave hits a body once). */
      final Set<UUID> tollHit = new HashSet<>();
      /** Supercharged: a trophy was offered mid-fight. Tankier, meaner, and
       *  permanently wreathed in purple soul-fire. */
      boolean supercharged;
      float maxHealth;
      WitherBoss bossRef;

      // Summon show
      int summonAnim;
      Vec3 spawnPos = Vec3.ZERO;
      /** Ticks left of the theme's opening cut before the fight's loop takes over. */
      int musicIntroTicks;

      // Slam
      int slamDescend;
      long chargeFreeUntil;
      long lastSlam;
      boolean slamImmune;

      // Domain expansion
      int domainTicks;
      List<BlockPos> sandPlaced = new ArrayList<>();

      // Parry
      boolean parryDone;
      int parryTicks;

      // Wither Nuke
      int nukeCharge;
      boolean nukeImmune;

      // Drain beam
      int beamTicks;
      UUID beamTarget;

      // Ambient aura pulse
      long nextAura = 0L;

      // Blink
      long lastBlink;

      // Wither Nuke cooldown (its own, far longer than the ability cycle)
      long lastNuke;

      // Death
      int deathAnim;
      boolean deathExploded;
      boolean starGiven;
      DamageSource deathSource;

      // Cadence
      long nextMovePulse;
      long nextAbility;
      long nextSideHead;
      long nextAntiBurrow;
      long nextSummon;
      long lastFeed;
      int waves;

      WitherFight(boolean king) {
         this.king = king;
      }
   }

   /** One anti-burrow hole awaiting rebuild: the block is always restored, so
    *  the arena mechanic is self-contained (no Explosion Rebuild needed). */
   private record CapturedBlock(ServerLevel level, BlockPos pos, BlockState state, CompoundTag blockEntity, long rebuildAt) {
   }

   private static final List<CapturedBlock> antiBurrow = new ArrayList<>();

   private static final class MistPool {
      final ServerLevel level;
      final UUID ownerId;
      final double x;
      final double y;
      final double z;
      final double radius;
      final long expiresAt;

      MistPool(ServerLevel level, UUID ownerId, double x, double y, double z, double radius, long expiresAt) {
         this.level = level;
         this.ownerId = ownerId;
         this.x = x;
         this.y = y;
         this.z = z;
         this.radius = radius;
         this.expiresAt = expiresAt;
      }
   }

   private static final List<MistPool> mistPools = new ArrayList<>();

   private record PendingRemoval(ServerLevel level, UUID entityId, long at) {
   }

   private static final List<PendingRemoval> pendingRemovals = new ArrayList<>();

   /** A display transform to apply LATER - the display interpolation API only
    *  animates between two states sent on different ticks, so spawning the
    *  entity and setting its final transform in the same tick is a no-op
    *  (the client just sees the end state). */
   private record PendingTransform(ServerLevel level, UUID entityId, long at, Vector3f translation, Vector3f scale) {
   }

   private static final List<PendingTransform> pendingTransforms = new ArrayList<>();

   /** Minions and display entities owned by a wither (removed on cleanup). */
   private static final Map<UUID, Set<UUID>> knights = new HashMap<>();
   private static final Map<UUID, Set<UUID>> displays = new HashMap<>();
   private static final Map<UUID, UUID> homingSkulls = new HashMap<>();

   // ------------------------------------------------------------------
   // Main fight loop
   // ------------------------------------------------------------------

   private static void fightTick(MinecraftServer server, ServerLevel level, WitherBoss w, WitherFight f) {
      f.bossRef = w;
      if (f.stormWaves > 0 && f.deathAnim <= 0 && level.getGameTime() % 15L == 0L) {
         f.stormWaves--;
         skullStorm(level, w, f);
      }
      // The King below half: his theme's beat pulses round the screens of everyone at the fight.
      // Re-sent every two seconds so anyone who walks in late gets it; the client hides it
      // whenever the theme is not playing, so a stale "on" can never leave a pulse behind.
      if (f.king && f.deathAnim <= 0 && w.getHealth() <= f.maxHealth * 0.5F && level.getGameTime() % 40L == 0L) {
         f.beatOn = true;
         for (ServerPlayer p : level.getPlayers(pl -> pl.distanceToSqr(w) < 64.0 * 64.0)) {
            com.fortuneandfavors.net.FfNet.send(p, new com.fortuneandfavors.net.FfScreenFxPayload(com.fortuneandfavors.net.FfScreenFxPayload.FX_BEAT, true));
         }
      }

      // Custom death sequence owns the entity.
      if (f.deathAnim > 0) {
         deathAnimTick(level, w, f);
         return;
      }

      // Music first, because it plays through everything else here - including the summon show.
      //
      // The ensure is the door for every path that could have missed the claim-time start (a
      // rework toggled on under a live wither, an adopted fight, a claim that threw after the
      // body was registered): a live fight with no cut is given one on the next tick, in the same
      // spirit as the dragon theme's ensureTheme. Music that can only ever start at one instant is
      // music that goes missing whenever that instant is missed.
      if (WitherMusic.currentTrack(w.getUUID()) == 0) {
         boolean summoning = f.summonAnim > 0;
         WitherMusic.start(level, w.getUUID(), w.position(), summoning ? FfMusicPayload.TRACK_INTRO : FfMusicPayload.TRACK_LOOP);
         if (summoning && f.musicIntroTicks <= 0) {
            f.musicIntroTicks = WitherMusic.INTRO_TICKS;
         }
      }
      // The opening cut has its own clock: when 0:21 runs out the fight's looping cut takes over,
      // which is the same hand-off the track itself makes. The client crossfades it.
      if (f.musicIntroTicks > 0 && --f.musicIntroTicks <= 0) {
         WitherMusic.start(level, w.getUUID(), w.position(), FfMusicPayload.TRACK_LOOP);
      }
      WitherMusic.tick(level, w.getUUID(), w.position());

      // Summon show: pin the wither in place, layer particles, then hand it
      // back to the vanilla charge-up (whose countdown we shortened).
      if (f.summonAnim > 0) {
         f.summonAnim--;
         summonAnimTick(level, w, f);
         return;
      }

      long now = ServerClock.clock(level);
      float frac = w.getHealth() / f.maxHealth;
      boolean phase2 = frac <= 0.50F;

      // Constant aura: the boss should always be visibly "on" (soul-fire,
      // smoke, and purple flames once supercharged).
      auraTick(level, w, f, phase2, now);

      // The Wither Nuke charge-up owns the boss while it winds up.
      if (f.nukeCharge > 0) {
         nukeChargeTick(level, w, f);
         return;
      }

      movementPulse(level, w, f, now);

      sideHeadTick(level, w, f, now, phase2);

      if (f.beamTicks > 0) {
         beamTick(level, w, f);
      }

      if (f.domainTicks > 0) {
         domainAuraTick(level, w, f);
         f.domainTicks--;
         if (f.domainTicks == 0) {
            endDomain(level, f);
         }
      }

      if (f.parryTicks > 0) {
         f.parryTicks--;
         parryTick(level, w);
         // Refresh the bar by hand while the shield is up. Vanilla only writes
         // progress from its own AI step, so anything that suppresses that step
         // would otherwise leave the health bar frozen mid-shield.
         forceBar(w, f);
         if (f.parryTicks == 0) {
            broadcastNear(level, w.position(), 64, "&9The skull shield fades.");
         }
      }

      // Ability scheduler; paused while the domain is up (the boss channels
      // it instead) and while a drain beam is running. Supercharged fights
      // cycle noticeably faster.
      if (f.domainTicks <= 0 && f.beamTicks <= 0 && now >= f.nextAbility) {
         long base = f.supercharged ? 34L : (f.king ? 44L : 58L);
         f.nextAbility = now + base + RANDOM.nextInt(24);
         pickAbility(level, w, f, phase2, now);
      }

      if (phase2 && now >= f.nextSummon) {
         f.nextSummon = now + (f.king ? 340L : 480L);
         summonWave(level, w, f);
      }

      if (now >= f.nextAntiBurrow) {
         f.nextAntiBurrow = now + (f.king ? 200L : 300L) + RANDOM.nextInt(100);
         fireAntiBurrowSkull(level, w, f);
      }

      if (f.slamDescend > 0) {
         slamDescendTick(level, w, f);
      }
   }

   // ------------------------------------------------------------------
   // Movement: charge, follow, hover - overrides the vanilla drift
   // ------------------------------------------------------------------

   /**
    * How far above the body it is fighting the Wither may sit.
    *
    * <p>Vanilla's own drift climbs, and the rework's follow only ever pulled it *toward* a
    * target - so a fight that went on long enough, or a group that fought from under a roof,
    * ended with the boss in the sky, out of reach of anything but a bow and no longer in the
    * arena it is supposed to be a boss of. "It keeps going so high" is what that looks like
    * from underneath.
    *
    * <p>Fourteen blocks is deliberately above its own hover height and above a slam's launch, so
    * the scripted moves are unaffected: this is a ceiling on aimless climbing, not on the kit.
    */
   private static final double WITHER_ALTITUDE_CAP = 14.0;

   /** The altitude ceiling, for the self-test. */
   public static double witherAltitudeCap() {
      return WITHER_ALTITUDE_CAP;
   }

   private static void movementPulse(ServerLevel level, WitherBoss w, WitherFight f, long now) {
      // The shield does NOT stop it moving: a boss that parks itself while the
      // shield is up reads as broken, not as a mechanic.
      if (f.slamDescend > 0 || f.domainTicks > 0 || f.nukeCharge > 0 || f.beamTicks > 0) {
         return;
      }

      Vec3 vel = w.getDeltaMovement();
      LivingEntity target = w.getTarget();

      // ---- the altitude governor, and the first thing this method does.
      //
      // Run before the charge and the follow, because both of those can only ever pull it
      // sideways: a charge pointed at a player who is below it dives, and the hover band is
      // measured from the target - but neither ever brought it *down*, so a wither that had
      // climbed by any other route stayed climbed. This is the one line that answers for its
      // own height, and it answers every tick rather than once, because whatever lifted it
      // may try again next tick.
      LivingEntity anchor = target != null && target.isAlive() ? target : nearestPlayer(level, w, 64.0);
      if (anchor != null) {
         double ceiling = anchor.getY() + WITHER_ALTITUDE_CAP;
         if (w.getY() > ceiling) {
            // A fall, not a teleport: it comes down the way it went up, visibly, and any
            // player watching can see it being brought back into the fight rather than
            // blinking there.
            double over = w.getY() - ceiling;
            double sink = Math.min(0.7, 0.08 + over * 0.12);
            w.setDeltaMovement(vel.x * 0.6, -sink, vel.z * 0.6);
            w.hurtMarked = true;
            if (now % 10L == 0L) {
               com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.SOUL_FIRE_FLAME, w.getX(), w.getY() + 1.0, w.getZ(), 6, 0.7, 0.4, 0.7, 0.02);
            }
            return;
         }
      }

      // Bedrock charge: keep flying straight through anything for ~1.5s.
      if (now < f.chargeFreeUntil) {
         if (w.onGround() || w.verticalCollision) {
            f.chargeFreeUntil = 0L;
         } else {
            if (target != null) {
               Vec3 toTarget = target.position().subtract(w.position());
               w.setDeltaMovement(toTarget.normalize().scale(1.35));
               w.hurtMarked = true;
            }
            return;
         }
      }

      // Within 10 blocks of a live target: drift toward it and hover just
      // above head height (sin bob), so it presses the fight instead of
      // orbiting pointlessly.
      if (target != null && target.isAlive() && w.distanceToSqr(target) < 100.0 && now >= f.lastSlam) {
         Vec3 toTarget = target.position().add(0.0, 1.8, 0.0).subtract(w.position());
         double dist = Math.max(0.5, toTarget.horizontalDistance());
         Vec3 want = new Vec3(
            toTarget.x / dist * 0.55,
            Mth.clamp(toTarget.y * 0.2, -0.35, 0.35) + Math.sin(now * 0.45) * 0.12,
            toTarget.z / dist * 0.55
         );
         w.setDeltaMovement(new Vec3(
            Mth.lerp(0.2, vel.x, want.x), Mth.lerp(0.2, vel.y, want.y), Mth.lerp(0.2, vel.z, want.z)
         ));
         w.hurtMarked = true;
      }
   }

   // ------------------------------------------------------------------
   // Abilities
   // ------------------------------------------------------------------

   private static void pickAbility(ServerLevel level, WitherBoss w, WitherFight f, boolean phase2, long now) {
      if (w.getTarget() == null && nearestPlayer(level, w, 40.0) instanceof ServerPlayer p) {
         w.setTarget(p);
      }

      // Big, varied table: the boss should never repeat itself twice in a row
      // often enough to be predictable. Nuke is the rarest, biggest button.
      int roll = RANDOM.nextInt(150);
      String move;
      if (roll < 12) {
         // The nuke is the rarest button but no longer phase-2-locked: players
         // were reaching the end of fights without ever seeing it.
         if (f.slamDescend <= 0 && now - f.lastNuke >= 500L && (phase2 || f.king || RANDOM.nextInt(100) < 40)) {
            f.lastNuke = now;
            startWitherNuke(level, w, f);
            move = "WITHER NUKE";
         } else {
            bedrockCharge(level, w, f);
            move = "BEDROCK CHARGE";
         }
      } else if (roll < 26) {
         bedrockCharge(level, w, f);
         move = "BEDROCK CHARGE";
      } else if (roll < 36) {
         burstBlast(level, w, f);
         move = "BURST";
      } else if (roll < 46) {
         skullVolley(level, w, f, false);
         move = "SKULL VOLLEY";
      } else if (roll < 56) {
         soulNova(level, w, f);
         move = "SOUL NOVA";
      } else if (roll < 68) {
         skullRain(level, w, f);
         move = "SKULL RAIN";
      } else if (roll < 76) {
         drainBeam(level, w, f);
         move = "WITHER DRAIN";
      } else if (roll < 82) {
         soulPull(level, w, f);
         move = "SOUL PULL";
      } else if (roll < 90) {
         blinkStrike(level, w, f);
         move = "SHRED BLINK";
      } else if (roll < 96) {
         skullVolley(level, w, f, true);
         move = "SEEKER VOLLEY";
      } else if (roll < 106) {
         graveBurst(level, w, f);
         move = "GRAVE BURST";
      } else if (roll < 116) {
         witherWail(level, w, f);
         move = "WITHER WAIL";
      } else if (phase2 && roll < 124 && now - f.lastSlam >= 200L) {
         startAscension(level, w, f);
         move = "ASCENSION SLAM";
      } else if (phase2 && roll < 138) {
         // JJK-flavoured finisher: soul-sand sanctum that withers everyone
         // inside (bypasses wither-immunity gear by design).
         startDomain(level, w, f);
         move = "DOMAIN EXPANSION";
      } else if (roll < 148) {
         skullVolley(level, w, f, RANDOM.nextInt(100) < 45);
         move = "SKULL VOLLEY";
      } else {
         burstBlast(level, w, f);
         move = "BURST";
      }
      if (LOGGER.isDebugEnabled()) {
         LOGGER.debug("Wither rework: {} (phase{}) - roll {}", move, phase2 ? "2" : "1", roll);
      }
   }

   /** Signature phase-1 move: locks on, roars, and plows straight through the
    *  terrain (vanilla destroys blocks on contact; Explosion Rebuild restores
    *  them, and claim protection still applies). */
   private static void bedrockCharge(ServerLevel level, WitherBoss w, WitherFight f) {
      LivingEntity target = w.getTarget();
      Vec3 dir = target != null && target.isAlive()
         ? target.position().subtract(w.position()).normalize()
         : Vec3.directionFromRotation(0.0F, w.getYRot());
      if (dir.lengthSqr() < 0.01) {
         dir = new Vec3(0.0, 0.0, 1.0);
      }

      w.setDeltaMovement(dir.scale(1.35));
      w.hurtMarked = true;
      f.chargeFreeUntil = ServerClock.clock(level) + 30L;

      level.playSound(null, w.getX(), w.getY(), w.getZ(), SoundEvents.WITHER_SPAWN, SoundSource.HOSTILE, 1.5F, 0.55F);
      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.LARGE_SMOKE, w.getX(), w.getY() + 1.2, w.getZ(), 60, 1.2, 0.8, 1.2, 0.08);
      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.SOUL_FIRE_FLAME, w.getX(), w.getY() + 1.0, w.getZ(), 30, 1.0, 0.6, 1.0, 0.05);
      broadcastActionBar(level, w.position(), 64, "&8&lBEDROCK CHARGE&7 - get out of the way!");
   }

   /** Burst: nova knockback to escape melee combos. */
   private static void burstBlast(ServerLevel level, WitherBoss w, WitherFight f) {
      com.fortuneandfavors.net.FfVfx.shape(level, com.fortuneandfavors.net.FfVfx.NOVA, ParticleTypes.SMOKE, w.position(), Vec3.ZERO, 7.0, 0.0, 0x6E5A80);
      level.playSound(null, w.getX(), w.getY(), w.getZ(), SoundEvents.WITHER_BREAK_BLOCK, SoundSource.HOSTILE, 1.5F, 0.6F);
      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.EXPLOSION, w.getX(), w.getY() + 1.2, w.getZ(), 6, 0.6, 0.8, 0.6, 0.06);
      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.POOF, w.getX(), w.getY() + 1.0, w.getZ(), 60, 2.4, 1.4, 2.4, 0.12);
      broadcastActionBar(level, w.position(), 48, "&8The Wither &lBURSTS&7 - knockback!");

      for (Entity e : level.getEntities(w, w.getBoundingBox().inflate(6.0), en -> en instanceof LivingEntity && en.isAlive() && en != w)) {
         LivingEntity le = (LivingEntity)e;
         Vec3 away = le.position().subtract(w.position());
         if (away.lengthSqr() < 0.01) {
            away = new Vec3(0.0, 1.0, 0.0);
         }
         away = away.normalize();
         le.push(away.x * 1.9, 0.7, away.z * 1.9);
         le.hurtMarked = true;
         if (le instanceof ServerPlayer sp && (sp.isCreative() || sp.isSpectator())) {
            continue;
         }
         le.hurt(mobSource(level, w), (f.king ? 8.0F : 5.0F));
      }
   }

   /** 5-skull fan, optionally led by a homing seeker. */
   private static void skullVolley(ServerLevel level, WitherBoss w, WitherFight f, boolean withSeeker) {
      LivingEntity target = w.getTarget();
      Vec3 base = target != null && target.isAlive()
         ? target.getEyePosition().subtract(w.getEyePosition())
         : Vec3.directionFromRotation(0.0F, w.getYRot());
      level.playSound(null, w.getX(), w.getY(), w.getZ(), SoundEvents.WITHER_SHOOT, SoundSource.HOSTILE, 1.6F, 1.0F);

      for (int i = -2; i <= 2; i++) {
         spawnSkull(level, w, rotated(base, i * 14.0), 0.55);
      }

      if (withSeeker && target != null) {
         WitherSkull seeker = spawnSkull(level, w, new Vec3(0.0, 0.7, 0.0), 0.32);
         if (seeker != null) {
            homingSkulls.put(seeker.getUUID(), target.getUUID());
         }
         broadcastActionBar(level, w.position(), 64, "&9A skull is &lTRACKING&9 you - keep moving!");
      }
   }

   /** Homing skull steering - the vanilla projectile can't do this. Runs
    *  once per server tick over every live seeker. */
   private static void tickAllHoming(MinecraftServer server) {
      if (homingSkulls.isEmpty()) {
         return;
      }

      Iterator<Map.Entry<UUID, UUID>> it = homingSkulls.entrySet().iterator();

      while (it.hasNext()) {
         Map.Entry<UUID, UUID> e = it.next();
         if (findEntity(server, e.getKey()) instanceof WitherSkull skull && skull.isAlive()
            && skull.level() instanceof ServerLevel level) {
            LivingEntity victim = findEntity(server, e.getValue()) instanceof LivingEntity le && le.isAlive() ? le : null;
            if (victim == null) {
               it.remove();
            } else {
               Vec3 want = victim.getEyePosition().subtract(skull.position()).normalize().scale(0.45);
               skull.setDeltaMovement(skull.getDeltaMovement().scale(0.6).add(want.scale(0.4)));
               skull.hurtMarked = true;
               if (RANDOM.nextInt(3) == 0) {
                  com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.SMOKE, skull.getX(), skull.getY(), skull.getZ(), 2, 0.1, 0.1, 0.1, 0.01);
               }
            }
         } else {
            it.remove();
         }
      }
   }

   // ------------------------------------------------------------------
   // Phase 2: ascension slam
   // ------------------------------------------------------------------

   private static void startAscension(ServerLevel level, WitherBoss w, WitherFight f) {
      LivingEntity target = w.getTarget();
      if (target == null) {
         skullVolley(level, w, f, false);
         return;
      }

      f.lastSlam = ServerClock.clock(level);
      f.slamDescend = 12;
      f.slamImmune = true;
      LOGGER.debug("Wither rework: ASCENSION - immune to projectiles, slamming in ~0.6s");
      w.setPos(target.getX(), Math.min(level.getMaxY() - 4, target.getY() + 16.0), target.getZ());
      w.setXRot(90.0F);
      w.setDeltaMovement(Vec3.ZERO);
      w.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 60, 3, false, false));
      level.playSound(null, w.getX(), w.getY(), w.getZ(), SoundEvents.WITHER_AMBIENT, SoundSource.HOSTILE, 2.0F, 0.5F);
      broadcastActionBar(level, w.position(), 64, "&5The Wither &lASCENDS&5 - scatter! (projectiles won't reach it)");
   }

   private static void slamDescendTick(ServerLevel level, WitherBoss w, WitherFight f) {
      w.setDeltaMovement(0.0, -SLAM_VERTICAL_SPEED, 0.0);
      w.hurtMarked = true;
      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.LARGE_SMOKE, w.getX(), w.getY() + 2.0, w.getZ(), 8, 0.8, 0.4, 0.8, 0.03);

      boolean landed = w.onGround() || w.verticalCollision;

      if (landed || --f.slamDescend <= 0) {
         f.slamDescend = 0;
         f.slamImmune = false;
         slamImpact(level, w, f);
      }
   }

   private static void slamImpact(ServerLevel level, WitherBoss w, WitherFight f) {
      com.fortuneandfavors.net.FfVfx.shape(level, com.fortuneandfavors.net.FfVfx.NOVA, ParticleTypes.SMOKE, w.position(), Vec3.ZERO, 9.0, 0.0, 0x5A4A6A);
      LOGGER.debug("Wither rework: SLAM IMPACT - shockwave damage {}", f.king ? 18 : 11);
      level.playSound(null, w.getX(), w.getY(), w.getZ(), SoundEvents.WITHER_BREAK_BLOCK, SoundSource.HOSTILE, 2.0F, 0.5F);
      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.EXPLOSION_EMITTER, w.getX(), w.getY(), w.getZ(), 2, 0.3, 0.2, 0.3, 0.05);
      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.LARGE_SMOKE, w.getX(), w.getY(), w.getZ(), 80, 3.0, 0.6, 3.0, 0.12);
      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.CRIT, w.getX(), w.getY() + 0.5, w.getZ(), 40, 2.5, 0.3, 2.5, 0.2);

      AABB shockwave = new AABB(w.blockPosition()).inflate(6.5).expandTowards(0.0, 2.5, 0.0);

      for (Entity e : level.getEntities(w, shockwave, en -> en instanceof LivingEntity && en.isAlive() && en != w)) {
         LivingEntity le = (LivingEntity)e;
         Vec3 away = le.position().subtract(w.position());
         if (away.lengthSqr() < 0.01) {
            away = new Vec3(0.0, 1.0, 0.0);
         }
         away = away.normalize();
         le.push(away.x * 1.3, 0.8, away.z * 1.3);
         le.hurtMarked = true;
         le.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 40, 0, false, false));
         if (le instanceof ServerPlayer sp && (sp.isCreative() || sp.isSpectator())) {
            continue;
         }
         le.hurt(mobSource(level, w), (f.king ? 18.0F : 11.0F));
      }

      // Shallow crater; fully restored by Explosion Rebuild when enabled.
      craterAt(level, w.blockPosition().below(), 2, w);
   }

   // ------------------------------------------------------------------
   // Anti-burrow skull: pierce cover, always rebuild
   // ------------------------------------------------------------------

   private static void fireAntiBurrowSkull(ServerLevel level, WitherBoss w, WitherFight f) {
      LivingEntity target = w.getTarget();
      Vec3 dir = target != null && target.isAlive()
         ? target.getEyePosition().subtract(w.getEyePosition())
         : Vec3.directionFromRotation(0.0F, w.getYRot());
      if (dir.lengthSqr() < 0.01) {
         return;
      }
      dir = dir.normalize();

      level.playSound(null, w.getX(), w.getY(), w.getZ(), SoundEvents.WITHER_SHOOT, SoundSource.HOSTILE, 1.8F, 0.4F);

      // Ray march: carve up to 4 solid blocks along the aim line so hunkering
      // behind dirt/stone/obsidian stops being free. Every removed block is
      // queued for restoration ~6s later.
      BlockPos cursor = w.blockPosition();
      int carved = 0;

      for (int step = 3; step <= 18 && carved < 4; step++) {
         BlockPos p = new BlockPos(
            w.getBlockX() + (int)Math.round(dir.x * step),
            w.getBlockY() + (int)Math.round(dir.y * step),
            w.getBlockZ() + (int)Math.round(dir.z * step)
         );
         if (p.equals(cursor)) {
            continue;
         }
         cursor = p;

         BlockState state = level.getBlockState(p);
         if (state.isAir() || state.liquid() || state.getBlock().defaultDestroyTime() < 0.0F || state.getBlock() == Blocks.BEDROCK) {
            continue;
         }

         carved++;
         carveBlock(level, p, state);
         com.fortuneandfavors.net.FfVfx.particles(level, new BlockParticleOption(ParticleTypes.BLOCK, state), p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5, 10, 0.25, 0.25, 0.25, 0.1
         );
      }

      if (carved > 0) {
         LOGGER.info("Wither rework: anti-burrow skull carved {} block(s) - queued for rebuild", carved);
         level.playSound(null, w.getX(), w.getY(), w.getZ(), (net.minecraft.sounds.SoundEvent)SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 1.0F, 0.8F);
         broadcastActionBar(level, w.position(), 64, "&9&lPIERCING SKULL&7 - cover won't hold!");
      }

      WitherSkull piercer = spawnSkull(level, w, dir, 0.85);
      if (piercer != null) {
         piercer.setDangerous(true);
      }
   }

   private static void carveBlock(ServerLevel level, BlockPos pos, BlockState state) {
      // Never touch claimed land (checked BEFORE capturing, so a rejected
      // carve can't queue a rebuild for a block that was never removed).
      if (ClaimManager.claimAt(level, pos) != null) {
         return;
      }

      // Boss wreckage goes into the SAME queue as explosions, and it goes in
      // UNCONDITIONALLY. The wither's slam/nuke craters used to be restored by
      // this class's own private antiBurrow list, which nothing outside this
      // file could see - so "Explosion Rebuild" never applied to the wreckage a
      // boss actually made, and a crash mid-fight could strand it. Then it was
      // still config-gated, which meant an admin turning explosions off for
      // their players also condemned every arena to permanent craters. Abilities
      // that carve the world to deny players cover are a boss mechanic, not
      // player destruction, so they always heal.
      ExplosionRebuildManager.captureAlways(level, List.of(pos.immutable()));
      level.removeBlock(pos, false);
   }

   /** Test hook: run one real wither carve against a live block so the
    *  self-test can prove boss wreckage reaches the shared rebuild queue.
    *  Returns true when the block was actually removed. */
   public static boolean probeCarve(ServerLevel level, BlockPos pos) {
      BlockState state = level.getBlockState(pos);
      if (state.isAir()) {
         return false;
      }
      carveBlock(level, pos.immutable(), state);
      return level.getBlockState(pos).isAir();
   }

   /** Restore anti-burrow holes (and expire mist pools & homing skulls). */
   private static void rebuildTick(MinecraftServer server) {
      if (antiBurrow.isEmpty()) {
         return;
      }

      Iterator<CapturedBlock> it = antiBurrow.iterator();

      while (it.hasNext()) {
         CapturedBlock c = it.next();
         if (ServerClock.clock(c.level) < c.rebuildAt) {
            continue;
         }

         boolean taken = false;
         try {
            // Chunk not loaded (player walked away): keep it queued until the
            // chunk comes back, so the block is never silently lost.
            if (!c.level.isLoaded(c.pos)) {
               continue;
            }
            if (!c.level.getBlockState(c.pos).isAir()) {
               // Something was built in the hole meanwhile - respect it.
               it.remove();
               continue;
            }
            it.remove();
            taken = true;
            c.level.setBlock(c.pos, c.state, 3);
            if (c.blockEntity != null) {
               BlockEntity restored = BlockEntity.loadStatic(c.pos, c.state, c.blockEntity, c.level.registryAccess());
               if (restored != null) {
                  c.level.setBlockEntity(restored);
               }
            }
            if (LOGGER.isDebugEnabled()) {
               LOGGER.debug("Wither rework: anti-burrow block restored at {}", c.pos);
            }
            com.fortuneandfavors.net.FfVfx.particles(c.level, new BlockParticleOption(ParticleTypes.BLOCK, c.state), c.pos.getX() + 0.5, c.pos.getY() + 0.5, c.pos.getZ() + 0.5,
               8, 0.2, 0.2, 0.2, 0.05
            );
         } catch (Throwable t) {
            // Already-taken entries must not be removed twice (Iterator would
            // throw); not-yet-taken entries are dropped so a persistently
            // throwing block can't spin the queue forever.
            if (!taken) {
               it.remove();
            }
            LOGGER.debug("Wither rework: anti-burrow rebuild failed at {}", c.pos, t);
         }
      }
   }

   // ------------------------------------------------------------------
   // Ambient aura: the boss is always visibly "on"
   // ------------------------------------------------------------------

   private static void auraTick(ServerLevel level, WitherBoss w, WitherFight f, boolean phase2, long now) {
      if (now < f.nextAura) {
         return;
      }
      f.nextAura = now + 2L;

      double x = w.getX();
      double y = w.getY() + 1.0;
      double z = w.getZ();

      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.SOUL_FIRE_FLAME, x, y, z, 3, 0.7, 0.6, 0.7, 0.01);
      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.LARGE_SMOKE, x, y + 0.4, z, 2, 0.6, 0.4, 0.6, 0.01);

      if (phase2) {
         com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.SCULK_SOUL, x, y, z, 4, 1.1, 0.9, 1.1, 0.02);
      }

      if (f.supercharged) {
         // A rotating ring of purple soul-fire plus falling embers.
         double ang = now * 0.25;

         for (int i = 0; i < 4; i++) {
            double a = ang + i * (Math.PI / 2.0);
            com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.SOUL_FIRE_FLAME, x + Math.cos(a) * 1.6, y + 0.3, z + Math.sin(a) * 1.6, 1, 0.0, 0.0, 0.0, 0.0
            );
         }

         com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.SOUL, x, y + 1.6, z, 5, 1.3, 0.8, 1.3, 0.02);
         com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.END_ROD, x, y + 2.2, z, 2, 1.0, 0.4, 1.0, 0.01);
      }
   }

   /** Arrival show: expanding ground rings + a harmless lightning crown, fired
    *  on top of the vanilla spawn boom. */
   private static void arrivalFx(ServerLevel level, WitherBoss w, WitherFight f) {
      com.fortuneandfavors.net.FfVfx.shape(level, com.fortuneandfavors.net.FfVfx.RIFT, ParticleTypes.SMOKE, w.position(), Vec3.ZERO, 2.5, 0.0, 0x3A2A4A);
      double x = w.getX();
      double y = w.getY();
      double z = w.getZ();

      for (int ring = 0; ring < 3; ring++) {
         double r = 3.0 + ring * 3.0;

         for (int i = 0; i < 48; i++) {
            double a = i * (Math.PI / 24.0);
            com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.SOUL_FIRE_FLAME, x + Math.cos(a) * r, y + 0.2, z + Math.sin(a) * r, 1, 0.0, 0.0, 0.0, 0.0);
            com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.LARGE_SMOKE, x + Math.cos(a) * r, y + 1.0, z + Math.sin(a) * r, 1, 0.0, 0.2, 0.0, 0.02);
         }
      }

      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.EXPLOSION_EMITTER, x, y + 1.5, z, 3, 0.6, 0.6, 0.6, 0.05);
      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.SCULK_SOUL, x, y + 1.5, z, 200, 2.5, 2.5, 2.5, 0.15);

      for (int i = 0; i < 8; i++) {
         double a = i * (Math.PI / 4.0);
         spawnVisualLightning(level, x + Math.cos(a) * 5.0, y, z + Math.sin(a) * 5.0);
      }
   }

   // ------------------------------------------------------------------
   // WITHER NUKE - the signature finisher
   // ------------------------------------------------------------------

   private static void startWitherNuke(ServerLevel level, WitherBoss w, WitherFight f) {
      f.nukeCharge = NUKE_CHARGE_TICKS;
      f.nukeImmune = true;
      w.setDeltaMovement(Vec3.ZERO);
      w.hurtMarked = true;
      level.playSound(null, w.getX(), w.getY(), w.getZ(), SoundEvents.WITHER_AMBIENT, SoundSource.HOSTILE, 3.0F, 0.4F);
      broadcastActionBar(level, w.position(), 80, "&4&lWITHER NUKE&7 is charging - &4GET CLEAR&7!");
      showTitleNear(level, w.position(), 80, "§4&l\u2622 WITHER NUKE", "§cIt is drawing the souls in...");
   }

   private static void nukeChargeTick(ServerLevel level, WitherBoss w, WitherFight f) {
      f.nukeCharge--;
      int t = NUKE_CHARGE_TICKS - f.nukeCharge;

      // Rooted mid-air, gathering.
      w.setDeltaMovement(w.getDeltaMovement().scale(0.35));
      w.hurtMarked = true;

      double x = w.getX();
      double y = w.getY();
      double z = w.getZ();

      // Growing ground ring = the blast radius, telegraphed.
      double r = 2.0 + t * 0.2;

      for (int i = 0; i < 32; i++) {
         double a = i * (Math.PI / 16.0) + t * 0.12;
         com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.SOUL_FIRE_FLAME, x + Math.cos(a) * r, y + 0.2, z + Math.sin(a) * r, 1, 0.0, 0.0, 0.0, 0.0);
      }

      // Converging core.
      for (int i = 0; i < 6; i++) {
         double a = RANDOM.nextDouble() * Math.PI * 2.0;
         double cr = 3.5 + RANDOM.nextDouble() * 3.0;
         com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.SOUL, x + Math.cos(a) * cr, y + 1.0 + RANDOM.nextDouble() * 2.0, z + Math.sin(a) * cr, 1, 0.0, 0.0, 0.0, 0.0
         );
      }

      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.SCULK_SOUL, x, y + 1.2, z, 10, 0.8, 1.0, 0.8, 0.05);
      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.LARGE_SMOKE, x, y + 1.2, z, 8, 1.2, 1.0, 1.2, 0.04);

      if (t % 8 == 0) {
         level.playSound(null, x, y, z, SoundEvents.WITHER_HURT, SoundSource.HOSTILE, 2.0F, 0.5F + t * 0.012F);
      }

      if (f.nukeCharge <= 0) {
         f.nukeImmune = false;
         witherNukeBlast(level, w, f);
      }
   }

   private static void witherNukeBlast(ServerLevel level, WitherBoss w, WitherFight f) {
      com.fortuneandfavors.net.FfVfx.shape(level, com.fortuneandfavors.net.FfVfx.NOVA, ParticleTypes.SOUL_FIRE_FLAME, w.position(), Vec3.ZERO, 20.0, 0.0, 0xE6DCFF);
      double x = w.getX();
      double y = w.getY() + 1.0;
      double z = w.getZ();
      // The blast itself is smaller than it used to be: the nuke's damage now
      // lives in the storm of skulls that falls with it, so the fight reads as
      // "skulls rain out of a torn sky" rather than "one big explosion".
      float power = f.supercharged ? 8.0F : (f.king ? 7.0F : 6.0F);

      LOGGER.info("Wither rework: WITHER NUKE detonated (power {}, storm follows)", power);
      level.explode(w, x, y, z, power, ExplosionInteraction.TNT);
      skullStorm(level, w, f);
      // The storm keeps coming: the King's nuke rains for five waves, a plain Wither's for two.
      f.stormWaves = f.king ? 4 : 1;

      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.EXPLOSION_EMITTER, x, y, z, 6, 1.0, 1.0, 1.0, 0.1);
      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.LARGE_SMOKE, x, y, z, 200, 9.0, 2.0, 9.0, 0.2);
      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.SOUL_FIRE_FLAME, x, y, z, 160, 8.0, 1.2, 8.0, 0.15);
      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.SCULK_SOUL, x, y, z, 180, 7.0, 2.0, 7.0, 0.18);
      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.TOTEM_OF_UNDYING, x, y, z, 120, 6.0, 2.0, 6.0, 0.4);
      level.playSound(null, x, y, z, (net.minecraft.sounds.SoundEvent)SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 3.0F, 0.5F);
      level.playSound(null, x, y, z, SoundEvents.WITHER_BREAK_BLOCK, SoundSource.HOSTILE, 2.5F, 0.5F);

      // Wide shockwave + launch, so being in the ring is punished.
      float dmg = f.supercharged ? 30.0F : (f.king ? 24.0F : 18.0F);

      for (ServerPlayer sp : playersNear(level, w.position(), 22.0)) {
         if (sp.isCreative() || sp.isSpectator()) {
            continue;
         }
         Vec3 away = sp.position().subtract(w.position());
         if (away.lengthSqr() < 0.01) {
            away = new Vec3(0.0, 1.0, 0.0);
         }
         away = away.normalize();
         sp.push(away.x * 2.4, 1.0, away.z * 2.4);
         sp.hurtMarked = true;
         sp.addEffect(new MobEffectInstance(MobEffects.WITHER, 120, 1, false, true));
         sp.hurt(mobSource(level, w), dmg);
         sp.sendOverlayMessage(Component.literal("§4☢ The Wither Nuke scorches you."));
      }

      // Leaves a lingering hazard in the crater.
      mistPools.add(new MistPool(level, w.getUUID(), x, y - 0.5, z, 7.0, ServerClock.clock(level) + 240L));
      craterAt(level, w.blockPosition().below(), 4, w);
      broadcastActionBar(level, w.position(), 80, "&4&lWITHER NUKE&7 - &4BOOM&7!");
   }

   /**
    * The nuke's payload: a genuine storm of skulls over the whole arena.
    *
    * <p>Every meteor is cast into open air at a slightly different height, so
    * they arrive as a rolling barrage instead of a single wall, and the spread
    * covers the boss as well as every player in range - standing still is the
    * only way to be hit by all of them.
    */
   private static void skullStorm(ServerLevel level, WitherBoss w, WitherFight f) {
      // ~40 meteors per player, as asked: the nuke is the wither's signature
      // "the sky is falling" moment, and anything less read as a light shower.
      int perPlayer = f.supercharged ? 52 : (f.king ? 46 : 40);
      double spread = f.supercharged ? 8.0 : 7.0;

      // A carpet around the boss itself, so the blast zone is not a safe spot.
      for (int i = 0; i < 30; i++) {
         double sx = w.getX() + (RANDOM.nextDouble() - 0.5) * 2.0 * spread;
         double sz = w.getZ() + (RANDOM.nextDouble() - 0.5) * 2.0 * spread;
         double sy = clearMeteorY(level, sx, w.getY(), sz) + RANDOM.nextInt(6);
         dropMeteor(level, w, sx, sy, sz);
      }

      for (ServerPlayer p : playersNear(level, w.position(), 48.0)) {
         if (p.isCreative() || p.isSpectator()) {
            continue;
         }
         com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.SOUL_FIRE_FLAME, p.getX(), p.getY() + 0.2, p.getZ(), 40, 4.0, 0.1, 4.0, 0.03);
         com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.SCULK_SOUL, p.getX(), p.getY() + 6.0, p.getZ(), 24, 3.0, 1.5, 3.0, 0.06);
         for (int i = 0; i < perPlayer; i++) {
            double sx = p.getX() + (RANDOM.nextDouble() - 0.5) * 2.0 * spread;
            double sz = p.getZ() + (RANDOM.nextDouble() - 0.5) * 2.0 * spread;
            double sy = clearMeteorY(level, sx, p.getY(), sz) + RANDOM.nextInt(8);
            dropMeteor(level, w, sx, sy, sz);
         }
         p.sendOverlayMessage(Component.literal("§4§lTHE SKY OPENS - §4skulls are falling!"));
      }

      for (int i = 0; i < 20; i++) {
         double a = RANDOM.nextDouble() * Math.PI * 2.0;
         double r = RANDOM.nextDouble() * 14.0;
         com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.SOUL_FIRE_FLAME, w.getX() + Math.cos(a) * r, w.getY() + 8.0 + RANDOM.nextInt(10), w.getZ() + Math.sin(a) * r, 3, 0.3, 1.5, 0.3, 0.05);
      }
   }

   // ------------------------------------------------------------------
   // Extra moves: soul nova, skull rain, drain beam, soul pull, blink
   // ------------------------------------------------------------------

   /** Ring of skulls fired outward in every direction - impossible to dodge,
    *  easy to block: get behind cover. */
   private static void soulNova(ServerLevel level, WitherBoss w, WitherFight f) {
      com.fortuneandfavors.net.FfVfx.shape(level, com.fortuneandfavors.net.FfVfx.NOVA, ParticleTypes.SOUL_FIRE_FLAME, w.position(), Vec3.ZERO, 10.0, 0.0, 0x3FE0FF);
      int count = f.supercharged ? 20 : (f.king ? 16 : 12);
      level.playSound(null, w.getX(), w.getY(), w.getZ(), SoundEvents.WITHER_SHOOT, SoundSource.HOSTILE, 2.0F, 0.6F);

      for (int i = 0; i < count; i++) {
         double a = i * (Math.PI * 2.0 / count);
         Vec3 dir = new Vec3(Math.cos(a), 0.0, Math.sin(a));
         WitherSkull skull = spawnSkull(level, w, dir, 0.45);
         if (skull != null) {
            skull.setDangerous(false);
         }
      }

      for (int i = 0; i < 64; i++) {
         double a = i * (Math.PI / 32.0);
         com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.SOUL_FIRE_FLAME, w.getX() + Math.cos(a) * 2.0, w.getY() + 1.0, w.getZ() + Math.sin(a) * 2.0, 1, 0.0, 0.0, 0.0, 0.0);
      }

      broadcastActionBar(level, w.position(), 64, "&9&lSOUL NOVA&7 - skulls in every direction!");
   }

   /** Skulls called down out of the sky onto everyone nearby - a real barrage.
    *
    *  <p>Each meteor is cast into the lowest <i>open</i> column above its target
    *  rather than at the world-surface heightmap: the old version spawned them
    *  inside the ceiling of any arena that was not under open sky, which is
    *  exactly why the move looked like it did nothing underground.
    */
   private static void skullRain(ServerLevel level, WitherBoss w, WitherFight f) {
      int perPlayer = f.supercharged ? 22 : (f.king ? 18 : 14);
      level.playSound(null, w.getX(), w.getY(), w.getZ(), SoundEvents.WITHER_AMBIENT, SoundSource.HOSTILE, 2.2F, 0.5F);

      for (ServerPlayer p : playersNear(level, w.position(), 40.0)) {
         if (p.isCreative() || p.isSpectator()) {
            continue;
         }

         // Mark the impact zone so it is fair.
         com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.SOUL_FIRE_FLAME, p.getX(), p.getY() + 0.2, p.getZ(), 30, 2.6, 0.1, 2.6, 0.02);

         for (int i = 0; i < perPlayer; i++) {
            double sx = p.getX() + (RANDOM.nextDouble() - 0.5) * 8.0;
            double sz = p.getZ() + (RANDOM.nextDouble() - 0.5) * 8.0;
            double sy = clearMeteorY(level, sx, p.getY(), sz) + RANDOM.nextInt(3);
            dropMeteor(level, w, sx, sy, sz);
         }

         p.sendOverlayMessage(Component.literal("§9Skulls rain from above..."));
      }

      broadcastActionBar(level, w.position(), 64, "&9&lSKULL RAIN&7 - look up!");
   }

   /** The lowest Y above a spot that still has open air beneath the first solid
    *  block, so a falling skull never spawns buried in a ceiling. */
   private static double clearMeteorY(ServerLevel level, double x, double y, double z) {
      BlockPos base = BlockPos.containing(x, y, z);
      for (int i = 1; i < 320; i++) {
         BlockPos probe = base.above(i);
         if (probe.getY() >= level.getMaxY()) {
            break;
         }
         if (!level.getBlockState(probe).isAir()) {
            return Math.max(y + 2.0, probe.getY() - 1.0);
         }
      }
      return y + 12.0;
   }

   /** One dangerous skull dropped straight down from {@code sy}. */
   private static void dropMeteor(ServerLevel level, WitherBoss w, double sx, double sy, double sz) {
      try {
         WitherSkull meteor = new WitherSkull(EntityTypes.WITHER_SKULL, level);
         meteor.setPos(sx, sy, sz);
         meteor.setDeltaMovement(0.0, -0.9, 0.0);
         meteor.setOwner(w);
         meteor.setDangerous(true);
         level.addFreshEntity(meteor);
      } catch (Throwable ignored) {
      }
   }

   /** Keeps the boss bar honest: vanilla only writes progress from its own AI
    *  step, so any frame we hand-roll has to refresh it too. */
   private static void forceBar(WitherBoss w, WitherFight f) {
      try {
         ServerBossEvent bar = ((WitherBossAccessor)(Object)w).fortuneandfavors$bossEvent();
         if (bar != null && f.maxHealth > 0.0F) {
            bar.setProgress(Mth.clamp(w.getHealth() / f.maxHealth, 0.0F, 1.0F));
         }
      } catch (Throwable ignored) {
      }
   }

   /** A forward cone of skulls: the close-range answer to anyone hugging it. */
   private static void graveBurst(ServerLevel level, WitherBoss w, WitherFight f) {
      com.fortuneandfavors.net.FfVfx.shape(level, com.fortuneandfavors.net.FfVfx.NOVA, ParticleTypes.SMOKE, w.position(), Vec3.ZERO, 6.0, 0.0, 0x4A4452);
      LivingEntity target = w.getTarget();
      Vec3 dir = target != null && target.isAlive()
         ? target.position().add(0.0, 1.0, 0.0).subtract(w.position().add(0.0, 1.5, 0.0)).normalize()
         : w.getLookAngle();
      int count = f.supercharged ? 16 : (f.king ? 14 : 12);
      level.playSound(null, w.getX(), w.getY(), w.getZ(), SoundEvents.WITHER_SHOOT, SoundSource.HOSTILE, 2.4F, 0.7F);

      for (int i = 0; i < count; i++) {
         double spread = (RANDOM.nextDouble() - 0.5) * 0.34;
         double lift = (RANDOM.nextDouble() - 0.5) * 0.24;
         WitherSkull skull = spawnSkull(level, w, new Vec3(dir.x + spread, dir.y + lift, dir.z + spread), 0.55);
         if (skull != null) {
            skull.setDangerous(false);
         }
      }

      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.SOUL_FIRE_FLAME, w.getX(), w.getEyeY(), w.getZ(), 50, 1.2, 1.0, 1.2, 0.12);
      broadcastActionBar(level, w.position(), 64, "&8&lGRAVE BURST&7 - break the cone!");
   }

   /** A wail that roots everything nearby: the answer to kiting. */
   private static void witherWail(ServerLevel level, WitherBoss w, WitherFight f) {
      level.playSound(null, w.getX(), w.getY(), w.getZ(), SoundEvents.WITHER_HURT, SoundSource.HOSTILE, 2.6F, 0.35F);
      level.playSound(null, w.getX(), w.getY(), w.getZ(), SoundEvents.WARDEN_SONIC_CHARGE, SoundSource.HOSTILE, 1.6F, 0.6F);
      double radius = f.supercharged ? 20.0 : (f.king ? 16.0 : 12.0);

      // Three expanding rings of soul fire mark the wavefront.
      for (int ring = 0; ring < 3; ring++) {
         double r = 2.0 + ring * 3.0;
         for (int i = 0; i < 40; i++) {
            double a = i * (Math.PI * 2.0 / 40.0);
            com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.SOUL_FIRE_FLAME, w.getX() + Math.cos(a) * r, w.getY() + 1.0, w.getZ() + Math.sin(a) * r, 1, 0.0, 0.0, 0.0, 0.0);
         }
      }

      for (ServerPlayer p : playersNear(level, w.position(), radius)) {
         if (p.isCreative() || p.isSpectator()) {
            continue;
         }
         Vec3 away = p.position().subtract(w.position());
         away = new Vec3(away.x, 0.0, away.z);
         if (away.lengthSqr() < 0.01) {
            away = new Vec3(1.0, 0.0, 0.0);
         }
         away = away.normalize();
         p.push(away.x * 0.5, 0.35, away.z * 0.5);
         p.hurtMarked = true;
         p.addEffect(new MobEffectInstance(MobEffects.WITHER, 200, 1, false, true));
         p.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 80, 2, false, true));
         p.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 100, 0, false, true));
         p.hurt(mobSource(level, w), f.king ? 12.0F : 9.0F);
         p.sendOverlayMessage(Component.literal("§5The wail roots you where you stand."));
      }

      broadcastActionBar(level, w.position(), 64, "&5&lWITHER WAIL&7 - it will not let you run!");
   }

   /** A sustained drain beam: damage + wither, feeding the boss. */
   private static void drainBeam(ServerLevel level, WitherBoss w, WitherFight f) {
      LivingEntity target = w.getTarget();
      if (target == null || !target.isAlive()) {
         target = nearestPlayer(level, w, 32.0);
      }
      if (target == null) {
         skullVolley(level, w, f, false);
         return;
      }

      f.beamTarget = target.getUUID();
      f.beamTicks = BEAM_TICKS;
      level.playSound(null, w.getX(), w.getY(), w.getZ(), SoundEvents.BEACON_ACTIVATE, SoundSource.HOSTILE, 1.6F, 0.6F);
      broadcastActionBar(level, w.position(), 64, "&5&lWITHER DRAIN&7 - break line of sight!");
   }

   private static void beamTick(ServerLevel level, WitherBoss w, WitherFight f) {
      f.beamTicks--;

      LivingEntity target = null;
      if (f.beamTarget != null && findEntity(server(level), f.beamTarget) instanceof LivingEntity le && le.isAlive()) {
         target = le;
      }
      if (target == null) {
         f.beamTicks = 0;
         return;
      }

      Vec3 from = w.getEyePosition();
      Vec3 to = target.getEyePosition();

      // Beam VFX: a thick line of soul-fire between the two.
      for (int i = 0; i <= 16; i++) {
         double t = i / 16.0;
         com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.SOUL_FIRE_FLAME, Mth.lerp(t, from.x, to.x), Mth.lerp(t, from.y, to.y), Mth.lerp(t, from.z, to.z),
            1, 0.05, 0.05, 0.05, 0.0
         );
      }
      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.SCULK_SOUL, to.x, to.y, to.z, 6, 0.5, 0.6, 0.5, 0.02);

      if (ServerClock.clock(level) % 10L == 0L) {
         float dmg = f.supercharged ? 7.0F : (f.king ? 6.0F : 4.0F);
         if (target instanceof ServerPlayer sp && (sp.isCreative() || sp.isSpectator())) {
            return;
         }
         target.hurt(mobSource(level, w), dmg);
         target.addEffect(new MobEffectInstance(MobEffects.WITHER, 80, 1, false, true));
         // The drain feeds the boss a sliver of health.
         w.heal(f.maxHealth * 0.008F);
      }
   }

   /** Soul pull: yank everyone within range in, then burst for knock-up. */
   private static void soulPull(ServerLevel level, WitherBoss w, WitherFight f) {
      level.playSound(null, w.getX(), w.getY(), w.getZ(), SoundEvents.WITHER_AMBIENT, SoundSource.HOSTILE, 2.2F, 0.45F);

      for (ServerPlayer p : playersNear(level, w.position(), 22.0)) {
         if (p.isCreative() || p.isSpectator()) {
            continue;
         }

         Vec3 to = w.position().add(0.0, 1.0, 0.0).subtract(p.position());
         double dist = Math.max(1.0, to.length());
         p.push(to.x / dist * 2.3, 0.45, to.z / dist * 2.3);
         p.hurtMarked = true;
         p.sendOverlayMessage(Component.literal("§5The Wither drags you in..."));
      }

      // Converging ring so the pull is readable.
      for (int i = 0; i < 48; i++) {
         double a = i * (Math.PI / 24.0);
         double r = 12.0;
         com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.SOUL, w.getX() + Math.cos(a) * r, w.getY() + 1.0, w.getZ() + Math.sin(a) * r, 1, 0.0, 0.0, 0.0, 0.02);
      }

      broadcastActionBar(level, w.position(), 64, "&5&lSOUL PULL&7 - it is reeling you in!");
      burstBlast(level, w, f);
   }

   /** Blink behind the target with an instant burst - punishes hugging it. */
   private static void blinkStrike(ServerLevel level, WitherBoss w, WitherFight f) {
      LivingEntity target = w.getTarget();
      if (target == null || !target.isAlive()) {
         target = nearestPlayer(level, w, 40.0);
      }
      if (target == null) {
         bedrockCharge(level, w, f);
         return;
      }

      double ox = w.getX();
      double oy = w.getEyeY();
      double oz = w.getZ();

      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.POOF, ox, oy, oz, 60, 1.2, 1.4, 1.2, 0.06);
      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.LARGE_SMOKE, ox, oy, oz, 30, 1.0, 1.2, 1.0, 0.05);
      level.playSound(null, ox, oy, oz, SoundEvents.WITHER_SHOOT, SoundSource.HOSTILE, 1.6F, 0.6F);

      Vec3 behind = target.position().subtract(target.getLookAngle().scale(2.5));
      double ny = Math.min(level.getMaxY() - 4, target.getY() + 3.0);
      w.setPos(behind.x, ny, behind.z);
      w.setDeltaMovement(Vec3.ZERO);
      w.hurtMarked = true;
      f.lastBlink = ServerClock.clock(level);

      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.SCULK_SOUL, w.getX(), w.getY() + 1.0, w.getZ(), 60, 1.0, 1.2, 1.0, 0.08);
      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.SOUL_FIRE_FLAME, w.getX(), w.getY() + 1.0, w.getZ(), 40, 1.2, 1.0, 1.2, 0.05);

      // Immediate bite on arrival.
      float dmg = f.supercharged ? 16.0F : (f.king ? 13.0F : 9.0F);

      for (ServerPlayer sp : playersNear(level, w.position(), 4.5)) {
         if (sp.isCreative() || sp.isSpectator()) {
            continue;
         }
         Vec3 away = sp.position().subtract(w.position());
         if (away.lengthSqr() < 0.01) {
            away = new Vec3(0.0, 1.0, 0.0);
         }
         away = away.normalize();
         sp.push(away.x * 1.6, 0.9, away.z * 1.6);
         sp.hurtMarked = true;
         sp.addEffect(new MobEffectInstance(MobEffects.WITHER, 80, 0, false, true));
         sp.hurt(mobSource(level, w), dmg);
      }

      broadcastActionBar(level, w.position(), 48, "&8&lSHRED BLINK&7 - it's behind you!");
   }

   // ------------------------------------------------------------------
   // Side heads: mist pools
   // ------------------------------------------------------------------

   private static void sideHeadTick(ServerLevel level, WitherBoss w, WitherFight f, long now, boolean phase2) {
      if (now < f.nextSideHead) {
         return;
      }
      f.nextSideHead = now + (phase2 ? 200L : 320L) + RANDOM.nextInt(160);

      LivingEntity t = w.getTarget();
      if (t == null || !t.isAlive()) {
         return;
      }

      // A side head spits a lingering pool roughly at the target's feet.
      double px = t.getX() + (RANDOM.nextDouble() - 0.5) * 3.0;
      double pz = t.getZ() + (RANDOM.nextDouble() - 0.5) * 3.0;
      // Underground fight: if the surface sits far above the arena, pool at
      // the target's own feet instead of on the (unreachable) surface.
      int sy = level.getHeightmapPos(Heightmap.Types.WORLD_SURFACE, BlockPos.containing(px, 0, pz)).getY();
      double py = (sy > t.getBlockY() + 8 ? t.getY() : (double)sy) + 0.05;
      mistPools.add(new MistPool(level, w.getUUID(), px, py, pz, 2.6, now + 200L));

      WitherSkull spit = spawnSkull(level, w, new Vec3(px - w.getX(), Math.max(0.4, py - w.getEyeY()), pz - w.getZ()).normalize(), 0.5);
      if (spit != null) {
         spit.setDangerous(false);
      }
      level.playSound(null, px, py, pz, SoundEvents.WITHER_SHOOT, SoundSource.HOSTILE, 1.0F, 0.8F);
      broadcastActionBar(level, new Vec3(px, py, pz), 32, "&3Wither &lMIST&3 pools beneath you!");
   }

   private static void tickMistPools() {
      if (mistPools.isEmpty()) {
         return;
      }

      Iterator<MistPool> it = mistPools.iterator();

      while (it.hasNext()) {
         MistPool pool = it.next();
         ServerLevel level = pool.level;
         long now = ServerClock.clock(level);
         if (now >= pool.expiresAt) {
            it.remove();
            continue;
         }

         if (now % 5L == 0L) {
            com.fortuneandfavors.net.FfVfx.particles(pool.level, ParticleTypes.LARGE_SMOKE, pool.x, pool.y + 0.4, pool.z, 5, pool.radius * 0.5, 0.2, pool.radius * 0.5, 0.01);
            com.fortuneandfavors.net.FfVfx.particles(pool.level, ParticleTypes.SOUL, pool.x, pool.y + 0.3, pool.z, 2, pool.radius * 0.4, 0.15, pool.radius * 0.4, 0.01);
         }

         if (now % 20L == 0L) {
            AABB zone = new AABB(pool.x - pool.radius, pool.y - 1.0, pool.z - pool.radius, pool.x + pool.radius, pool.y + 1.8, pool.z + pool.radius);
            for (Entity e : pool.level.getEntities((Entity)null, zone, en -> en instanceof LivingEntity && en.isAlive())) {
               ((LivingEntity)e).addEffect(new MobEffectInstance(MobEffects.WITHER, 70, 0, false, true));
            }
         }
      }
   }

   // ------------------------------------------------------------------
   // Wither skeleton knights
   // ------------------------------------------------------------------

   private static void summonWave(ServerLevel level, WitherBoss w, WitherFight f) {
      int count = f.king ? KING_WAVE_SIZE : 2;         LOGGER.debug("Wither rework: wither knight wave {} summoned ({} knights)", f.waves + 1, count);
      level.playSound(null, w.getX(), w.getY(), w.getZ(), SoundEvents.WITHER_SKELETON_AMBIENT, SoundSource.HOSTILE, 1.6F, 0.5F);
      broadcastActionBar(level, w.position(), 64, "&8The Wither calls its &lKNIGHTS&8!");
      f.waves++;

      Set<UUID> minions = knights.computeIfAbsent(w.getUUID(), k -> new HashSet<>());

      for (int i = 0; i < count; i++) {
         double ang = RANDOM.nextDouble() * Math.PI * 2.0;
         double dist = 3.5 + RANDOM.nextDouble() * 2.5;
         double sx = w.getX() + Math.cos(ang) * dist;
         double sz = w.getZ() + Math.sin(ang) * dist;
         BlockPos ground = level.getHeightmapPos(Heightmap.Types.WORLD_SURFACE, BlockPos.containing(sx, 0, sz));

         Mob sk = (Mob)EntityTypes.WITHER_SKELETON.create(level, EntitySpawnReason.COMMAND);
         if (sk == null) {
            continue;
         }

         // Underground fight: the surface heightmap can be far above the
         // arena; if so, drop the knight at the wither's own level instead.
         int gy = ground.getY();
         sk.setPos(sx, gy > w.getBlockY() + 8 ? w.getY() : (double)gy, sz);
         float maxHp = f.king ? 60.0F : 30.0F;
         AttributeInstance hp = sk.getAttribute(Attributes.MAX_HEALTH);
         if (hp != null) {
            hp.setBaseValue(maxHp);
         }
         sk.setHealth(maxHp);
         AttributeInstance dmg = sk.getAttribute(Attributes.ATTACK_DAMAGE);
         if (dmg != null) {
            dmg.setBaseValue(f.king ? 10.0 : 6.0);
         }
         sk.setCustomName(Component.literal(f.king ? "§5§lRoyal Wither Knight" : "§8§lWither Knight"));
         sk.setCustomNameVisible(true);
         sk.setPersistenceRequired();
         sk.addTag(ModItems.WITHER_KNIGHT_TAG);
         sk.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.WITHER_SKELETON_SKULL));
         if (f.king) {
            sk.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
         }
         level.addFreshEntity(sk);
         minions.add(sk.getUUID());

         LivingEntity target = w.getTarget();
         if (target != null) {
            sk.setTarget(target);
         }

         com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.SOUL, sx, ground.getY() + 1.0, sz, 20, 0.4, 0.8, 0.4, 0.04);
         com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.LARGE_SMOKE, sx, ground.getY() + 1.0, sz, 10, 0.3, 0.6, 0.3, 0.03);
      }
   }

   // ------------------------------------------------------------------
   // Domain expansion: soul-sand ground ring + wither aura
   // ------------------------------------------------------------------

   /** How far a Grave Bell wave rolls, how long it takes, and how often the bell tolls. */
   private static final double TOLL_RADIUS = 12.0;
   private static final int TOLL_WAVE_TICKS = 14;
   private static final int TOLL_PERIOD = 20;

   /**
    * The Grave Bell: for seven and a half seconds the Wither tolls once a second, and every toll
    * sends a ring of the dead rolling out across the ground. It hits whoever is standing on the
    * ground when the front passes - jump it. (This replaced a sure-hit "domain" that withered
    * everyone inside it.)
    */
   private static void startDomain(ServerLevel level, WitherBoss w, WitherFight f) {
      f.domainTicks = 150;
      f.tollHit.clear();
      LOGGER.info("Wither rework: the Grave Bell tolls for {}s", 150 / 20);
      level.playSound(null, w.getX(), w.getY(), w.getZ(), SoundEvents.WITHER_SPAWN, SoundSource.HOSTILE, 2.0F, 0.5F);
      level.playSound(null, w.getX(), w.getY(), w.getZ(), SoundEvents.BELL_BLOCK, SoundSource.HOSTILE, 3.0F, 0.5F);
      broadcastNear(level, w.position(), 64, "&5&lTHE GRAVE BELL&d - every toll is a wave of the dead.");
      showTitleNear(level, w.position(), 64, "§5☠ THE GRAVE BELL", "§devery toll is a wave of the dead");
      com.fortuneandfavors.net.FfVfx.shape(level, com.fortuneandfavors.net.FfVfx.SUMMON_CIRCLE, ParticleTypes.SOUL_FIRE_FLAME, w.position(), Vec3.ZERO, 3.0, 150, 0x7A4CFF);
   }

   private static void domainAuraTick(ServerLevel level, WitherBoss w, WitherFight f) {
      int k = (150 - f.domainTicks) % TOLL_PERIOD;
      if (k == 0) {
         f.tollHit.clear();
         level.playSound(null, w.getX(), w.getY(), w.getZ(), SoundEvents.BELL_BLOCK, SoundSource.HOSTILE, 2.5F, 0.45F);
         com.fortuneandfavors.net.FfVfx.shape(level, com.fortuneandfavors.net.FfVfx.NOVA, ParticleTypes.SOUL_FIRE_FLAME, w.position(), Vec3.ZERO, TOLL_RADIUS, 0.0, 0x7A4CFF);
      }
      if (k >= TOLL_WAVE_TICKS) {
         return;
      }
      // The front, on the same curve the modded clients draw it on (ease-out over the wave).
      double t = (k + 1.0) / TOLL_WAVE_TICKS;
      double front = TOLL_RADIUS * (1.0 - Math.pow(1.0 - t, 3.0));
      com.fortuneandfavors.net.FfVfx.enter();
      try {
         for (int i = 0; i < 28; i++) {
            double a = Math.PI * 2.0 * i / 28;
            com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.SOUL_FIRE_FLAME, w.getX() + Math.cos(a) * front, w.getY() + 0.2, w.getZ() + Math.sin(a) * front, 1, 0.05, 0.05, 0.05, 0.0);
         }
      } finally {
         com.fortuneandfavors.net.FfVfx.exit();
      }
      for (ServerPlayer sp : level.getPlayers(pl -> pl.isAlive() && !pl.isCreative() && !pl.isSpectator())) {
         double d = Math.hypot(sp.getX() - w.getX(), sp.getZ() - w.getZ());
         if (Math.abs(d - front) > 1.0 || !sp.onGround() || Math.abs(sp.getY() - w.getY()) > 4.0 || !f.tollHit.add(sp.getUUID())) {
            continue;
         }
         sp.addEffect(new MobEffectInstance(MobEffects.WITHER, 60, f.king ? 2 : 1, false, true));
         sp.hurt(mobSource(level, w), f.king ? 7.0F : 5.0F);
         sp.sendOverlayMessage(Component.literal("§5The bell takes a piece of your §lsoul§5."));
      }
   }

   private static void endDomain(ServerLevel level, WitherFight f) {
      int restored = 0;
      for (BlockPos p : f.sandPlaced) {
         try {
            // Only undo what we actually placed: matching SOUL_SOIL exactly
            // means a player's own soul sand in the ring is never eaten.
            if (level.getBlockState(p).is(Blocks.SOUL_SOIL)) {
               level.removeBlock(p, false);
               restored++;
            }
         } catch (Throwable ignored) {
         }
      }
      f.sandPlaced.clear();
      if (restored > 0) {
         LOGGER.info("Wither rework: domain ended - {} soul-sand blocks restored", restored);
      }
   }

   // ------------------------------------------------------------------
   // Parry at 30%: projectile shield
   // ------------------------------------------------------------------

   private static void startParry(ServerLevel level, WitherBoss w, WitherFight f) {
      f.parryTicks = 100;
      LOGGER.info("Wither rework: PARRY at 30% HP - projectile shield up for 5s");
      level.playSound(null, w.getX(), w.getY(), w.getZ(), SoundEvents.BEACON_ACTIVATE, SoundSource.HOSTILE, 1.8F, 0.5F);
      broadcastNear(level, w.position(), 64, "&9&lThe Ascended Wither PARRIES!&7 Its skull shield turns your projectiles - switch to blades!");

      // Bone-block display ring for the shield visual.
      try {
         Set<UUID> owned = displays.computeIfAbsent(w.getUUID(), k -> new HashSet<>());

         for (int i = 0; i < 8; i++) {
            double ang = i * Math.PI / 4.0;
            Display.BlockDisplay shard = (Display.BlockDisplay)EntityTypes.BLOCK_DISPLAY.create(level, EntitySpawnReason.COMMAND);
            if (shard == null) {
               continue;
            }
            shard.setPos(w.getX() + Math.cos(ang) * 2.6 - 0.25, w.getY() + 0.5, w.getZ() + Math.sin(ang) * 2.6 - 0.25);
            shard.setBlockState(Blocks.BONE_BLOCK.defaultBlockState());
            shard.addTag(ModItems.DISPLAY_TMP_TAG);
            shard.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(0.5F), new Quaternionf()));
            level.addFreshEntity(shard);
            owned.add(shard.getUUID());
            scheduleRemoval(level, shard, 210);
         }
      } catch (Throwable ignored) {
      }

      for (int i = 0; i < 6; i++) {
         WitherSkull orb = spawnSkull(level, w, rotated(w.getLookAngle(), i * 60.0), 0.18);
         if (orb != null) {
            orb.setDangerous(false);
         }
      }
   }

   /** While parrying: arrow/crossbow bolts fired at the boss within 12 blocks
    *  get turned around at the shield ring. */
   private static void parryTick(ServerLevel level, WitherBoss w) {
      for (AbstractArrow arrow : level.getEntitiesOfClass(AbstractArrow.class, w.getBoundingBox().inflate(9.0))) {
         Entity owner = arrow.getOwner();
         if (!(owner instanceof ServerPlayer)) {
            continue;
         }

         Vec3 vel = arrow.getDeltaMovement();
         Vec3 toBoss = w.position().add(0.0, 1.5, 0.0).subtract(arrow.position());
         if (vel.lengthSqr() > 0.01 && vel.normalize().dot(toBoss.normalize()) > 0.85) {
            arrow.setDeltaMovement(vel.scale(-1.15));
            arrow.setOwner(w);
            arrow.hurtMarked = true;
            com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.END_ROD, arrow.getX(), arrow.getY(), arrow.getZ(), 4, 0.1, 0.1, 0.1, 0.05);
            level.playSound(null, arrow.getX(), arrow.getY(), arrow.getZ(), SoundEvents.SHIELD_BLOCK, SoundSource.HOSTILE, 0.8F, 1.4F);
         }
      }
   }

   // ------------------------------------------------------------------
   // Summon animation
   // ------------------------------------------------------------------

   private static void startSpawnAnimation(ServerLevel level, WitherBoss w, WitherFight f) {
      level.playSound(null, w.getX(), w.getY(), w.getZ(), SoundEvents.WITHER_SPAWN, SoundSource.HOSTILE, 2.5F, 0.45F);
      level.playSound(null, w.getX(), w.getY(), w.getZ(), (net.minecraft.sounds.SoundEvent)SoundEvents.SOUL_ESCAPE.value(), SoundSource.HOSTILE, 2.0F, 0.45F);
      broadcastActionBar(level, w.position(), 64, "&8&lThe souls converge...");
      showTitleNear(level, w.position(), 64, "§8☠", "§7Something ancient is rising...");

      // Wither's new spawn cage: park it dead centre and hold it still - its
      // own charge-up (which we shortened) ends with the vanilla boom right
      // as the display show finishes.
      w.setDeltaMovement(Vec3.ZERO);
      w.hurtMarked = true;
   }

   private static void summonAnimTick(ServerLevel level, WitherBoss w, WitherFight f) {
      int t = SPAWN_ANIM_TICKS - f.summonAnim;

      // Keep it pinned; the countdown freeze means vanilla does nothing else.
      w.setDeltaMovement(Vec3.ZERO);
      w.hurtMarked = true;

      if (t % 5L == 0L) {
         com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.SOUL, f.spawnPos.x, f.spawnPos.y + 1.0, f.spawnPos.z, 12, 2.2, 1.4, 2.2, 0.03);
         com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.SOUL_FIRE_FLAME, f.spawnPos.x, f.spawnPos.y + 0.4, f.spawnPos.z, 10, 2.0, 0.4, 2.0, 0.02);
         com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.LARGE_SMOKE, f.spawnPos.x, f.spawnPos.y + 1.5, f.spawnPos.z, 6, 1.8, 1.2, 1.8, 0.02);

         // Rising spiral of soul fire.
         double ang = t * 0.35;
         double r = 3.2 - t * 0.012;
         com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.SOUL_FIRE_FLAME, f.spawnPos.x + Math.cos(ang) * r, f.spawnPos.y + t * 0.02, f.spawnPos.z + Math.sin(ang) * r,
            2, 0.05, 0.05, 0.05, 0.0
         );
      }

      // Wither-soul soul lanterns blink around the cage.
      if (t == 40 || t == 90 || t == 130) {
         level.playSound(null, f.spawnPos.x, f.spawnPos.y, f.spawnPos.z, SoundEvents.WITHER_AMBIENT, SoundSource.HOSTILE, 1.4F, 0.6F + t / 200.0F);
      }

      // One-time hint: the summoning window is the king-promotion window.
      if (t == 30 && !f.king) {
         broadcastActionBar(level, f.spawnPos, 64, "&dRight-click the summoning wither with a &lWither Skeleton Skull&d to face the &5&lKING&d...");
      }

      // Display layers: a shrinking shroomlight "cocoon" shell + rising soul
      // sand pillars, spawned once at t=1 and interpolated away.
      if (t == 1) {
         com.fortuneandfavors.net.FfVfx.shape(level, com.fortuneandfavors.net.FfVfx.SUMMON_CIRCLE, ParticleTypes.SOUL_FIRE_FLAME, f.spawnPos, Vec3.ZERO, 3.4, SPAWN_ANIM_TICKS, 0x3FD8FF);
      }
      // Eight soul-fire pillars rising around the circle (the vanilla clients' version of the cue).
      if (t % 3 == 0) {
         com.fortuneandfavors.net.FfVfx.enter();
         try {
            double height = Math.min(4.0, t * 0.03);
            for (int i = 0; i < 8; i++) {
               double ang = i * Math.PI / 4.0;
               double px = f.spawnPos.x + Math.cos(ang) * 3.0;
               double pz = f.spawnPos.z + Math.sin(ang) * 3.0;
               com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.SOUL_FIRE_FLAME, px, f.spawnPos.y + height * level.getRandom().nextDouble(), pz, 1, 0.08, 0.1, 0.08, 0.01);
            }
         } finally {
            com.fortuneandfavors.net.FfVfx.exit();
         }
      }

      if (f.summonAnim == 0) {
         // Extra arrival punch: a shockwave ring + lightning crown on top of
         // the vanilla spawn boom.
         arrivalFx(level, w, f);
         showTitleNear(level, f.spawnPos, 64, f.king ? "§5&lTHE WITHER KING" : "§8&lTHE WITHER", "§7Survive.");
         handleSummonComplete(level, w, f);
      }
   }

   /** Vanilla charge-up finished: retitle the boss bar and supercharge a
    *  promoted king. */
   private static void handleSummonComplete(ServerLevel level, WitherBoss w, WitherFight f) {
      try {
         ServerBossEvent bar = ((WitherBossAccessor)(Object)w).fortuneandfavors$bossEvent();
         if (bar != null) {
            bar.setName(Component.literal(bossBarName(f)));
            bar.setColor(f.king ? net.minecraft.world.BossEvent.BossBarColor.PURPLE : net.minecraft.world.BossEvent.BossBarColor.WHITE);
         }
      } catch (Throwable ignored) {
      }

      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.END_ROD, w.getX(), w.getEyeY(), w.getZ(), 24, 0.3, 0.3, 0.3, 0.08);
      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.EXPLOSION_EMITTER, w.getX(), w.getEyeY(), w.getZ(), 2, 0.5, 0.5, 0.5, 0.05);
      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.SCULK_SOUL, w.getX(), w.getEyeY(), w.getZ(), 120, 3.0, 2.0, 3.0, 0.1);
      LOGGER.info("Wither rework: {} fully arrived - charge-up complete{}", f.king ? "KING" : "Ascended Wither", f.king ? " (purple boss bar)" : "");
      broadcastActionBar(level, w.position(), 64, f.king ? "&5&lTHE WITHER KING&7 has fully arrived." : "&8&lTHE WITHER&7 has fully arrived.");

      if (f.king) {
         buffKing(level, w);
      }
   }

   private static String bossBarName(WitherFight f) {
      if (f.supercharged) {
         return "§5§lWither §d✦ SUPERCHARGED";
      }
      return f.king ? "§5§lWither King" : "§8§lWither";
   }

   /** Post-spawn king buffs: a touch more toughness and first wave free. */
   private static void buffKing(ServerLevel level, WitherBoss w) {
      w.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 200, 1, false, false));
      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.TOTEM_OF_UNDYING, w.getX(), w.getEyeY(), w.getZ(), 60, 1.2, 1.5, 1.2, 0.3);
   }

   // ------------------------------------------------------------------
   // Death: custom sequence, no vanilla death or loot
   // ------------------------------------------------------------------

   private static void beginDeath(ServerLevel level, WitherBoss w, WitherFight f, DamageSource source) {
      com.fortuneandfavors.net.FfVfx.shape(level, com.fortuneandfavors.net.FfVfx.DEATH, ParticleTypes.END_ROD, w.position(), Vec3.ZERO, 140, 0.0, 0x9A8CC8);
      // A death mid-mechanic must not strand any world state: close the domain
      // carpet, cancel a nuke charge and stop any drain beam.
      f.domainTicks = 0;
      endDomain(level, f);
      f.nukeCharge = 0;
      f.nukeImmune = false;
      f.beamTicks = 0;
      f.beamTarget = null;
      f.slamDescend = 0;
      f.slamImmune = false;
      f.deathAnim = 140;
      f.deathPos = w.position();
      w.setNoAi(true);
      if (f.beatOn) {
         f.beatOn = false;
         for (ServerPlayer p : level.players()) {
            com.fortuneandfavors.net.FfNet.send(p, new com.fortuneandfavors.net.FfScreenFxPayload(com.fortuneandfavors.net.FfScreenFxPayload.FX_BEAT, false));
         }
      }
      f.deathExploded = false;
      f.starGiven = false;
      f.deathSource = source;
      // The ceremony cut, laid over the looping fight theme and crossfaded in - 2:33 on, played
      // once, and never looped: this is the one part of the track that has to end.
      WitherMusic.start(level, w.getUUID(), w.position(), FfMusicPayload.TRACK_DEATH);
      LOGGER.info("Wither rework: death sequence started (140 ticks) - soul pillars, explosion, star ceremony");
      w.setHealth(1.0F);
      w.setInvulnerable(true);
      w.setDeltaMovement(Vec3.ZERO);
      w.hurtMarked = true;
      level.playSound(null, w.getX(), w.getY(), w.getZ(), SoundEvents.WITHER_DEATH, SoundSource.HOSTILE, 2.5F, 0.55F);
      broadcastNear(level, w.position(), 64, "&8&lThe Wither is &4FALLING&8...");
      showTitleNear(level, w.position(), 64, "§8☠", "§7The sky goes quiet...");
   }

   private static void deathAnimTick(ServerLevel level, WitherBoss w, WitherFight f) {
      // Held where it fell: its AI is off, and anything else that nudges it is undone every tick.
      if (f.deathPos != null) {
         w.setPos(f.deathPos.x, f.deathPos.y, f.deathPos.z);
      }
      com.fortuneandfavors.net.FfVfx.enter();
      try {
         f.deathAnim--;
         w.setHealth(1.0F);
         w.setDeltaMovement(Vec3.ZERO);
         w.hurtMarked = true;

         int t = 140 - f.deathAnim;

         // Sinking + soul plume while the pillars erupt: a rising spiral of soul
         // fire, thickening as the sequence runs.
         if (t % 5L == 0L) {
            com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.SCULK_SOUL, w.getX(), w.getY() + 1.5, w.getZ(), 10, 1.6, 1.2, 1.6, 0.05);
            com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.LARGE_SMOKE, w.getX(), w.getY() + 0.8, w.getZ(), 8, 1.4, 0.8, 1.4, 0.03);
            com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.SOUL_FIRE_FLAME, w.getX(), w.getY() + 1.0, w.getZ(), 6, 1.2, 0.8, 1.2, 0.03);

            // Expanding spirit ring, one arm per 5 ticks of the sequence.
            double r = 2.0 + t * 0.06;
            double a = t * 0.22;
            com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.SOUL, w.getX() + Math.cos(a) * r, w.getY() + 1.0, w.getZ() + Math.sin(a) * r, 2, 0.05, 0.05, 0.05, 0.0);
         }

         if (t % 10L == 0L) {
            com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.END_ROD, w.getX(), w.getY() + 2.0, w.getZ(), 12, 1.6, 1.6, 1.6, 0.05);
         }

         if (t == 20) {
            // Soul-sand display pillars erupt around the body.
            try {
               Set<UUID> owned = displays.computeIfAbsent(w.getUUID(), k -> new HashSet<>());

               for (int i = 0; i < 6; i++) {
                  double ang = i * Math.PI / 3.0;
                  Display.BlockDisplay pillar = (Display.BlockDisplay)EntityTypes.BLOCK_DISPLAY.create(level, EntitySpawnReason.COMMAND);
                  if (pillar == null) {
                     continue;
                  }
                  pillar.setPos(w.getX() + Math.cos(ang) * 2.2 - 0.5, w.getY() - 0.5, w.getZ() + Math.sin(ang) * 2.2 - 0.5);
                  pillar.setBlockState(Blocks.SOUL_SOIL.defaultBlockState());
               pillar.addTag(ModItems.DISPLAY_TMP_TAG);
                  pillar.setTransformationInterpolationDelay(2 + i * 2);
                  pillar.setTransformationInterpolationDuration(40);
                  pillar.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(0.9F, 0.1F, 0.9F), new Quaternionf()));
                  pendingTransforms.add(new PendingTransform(level, pillar.getUUID(), ServerClock.clock(level) + 15L + i * 2L, new Vector3f(), new Vector3f(0.9F, 4.0F, 0.9F)));
                  level.addFreshEntity(pillar);
                  owned.add(pillar.getUUID());
                  scheduleRemoval(level, pillar, 120);
               }
            } catch (Throwable ignored) {
            }
         }

         if (t == 60 && !f.deathExploded) {
            f.deathExploded = true;
            LOGGER.debug("Wither rework: death explosion (radius {})", f.king ? 9.0F : 7.5F);
            // The big one - replaces the vanilla death pop. Explosion Rebuild
            // (if on) captures it like any other blast; claim protection holds.
            level.explode(w, w.getX(), w.getY() + 1.0, w.getZ(), f.king ? 9.0F : 7.5F, ExplosionInteraction.TNT);
            com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.END_ROD, w.getX(), w.getY() + 1.5, w.getZ(), 24, 0.4, 0.4, 0.4, 0.08);
            com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.EXPLOSION_EMITTER, w.getX(), w.getY() + 1.5, w.getZ(), 4, 0.8, 0.8, 0.8, 0.05);
            com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.TOTEM_OF_UNDYING, w.getX(), w.getY() + 1.5, w.getZ(), 80, 1.5, 1.8, 1.5, 0.3);
         }

         if (t == 100) {
            LOGGER.debug("Wither rework: nether-star ceremony - rising star display + light beam");
            spawnStarCeremony(level, w);

            // Skies answer: a ring of harmless lightning around the corpse.
            for (int i = 0; i < 6; i++) {
               double a = i * (Math.PI / 3.0);
               spawnVisualLightning(level, w.getX() + Math.cos(a) * 6.0, w.getY(), w.getZ() + Math.sin(a) * 6.0);
            }
         }

         if (t >= 120 && !f.starGiven) {
            f.starGiven = true;
               LOGGER.debug("Wither rework: rewards dropped (nether star + Wither Loot Box{})", f.king ? " x2, +2 skulls + Wither Essence" : "");
            dropRewards(level, w, f);
         }

         if (f.deathAnim <= 0) {
            // Belt and braces: whatever path ended the animation, the rewards have
            // to exist. A death that skipped the t>=120 window (/kill after the
            // capture, an interrupted sequence) used to vanish with no loot at all.
            if (!f.starGiven) {
               f.starGiven = true;
               LOGGER.info("Wither rework: rewards granted at the end of the death sequence (late path)");
               dropRewards(level, w, f);
            }
            finishDeath(server(level), level, w, f);
         }
      } finally {
         com.fortuneandfavors.net.FfVfx.exit();
      }
   }

   /** Rising item-display star + light beam, then the real item drop. */
   private static void spawnStarCeremony(ServerLevel level, WitherBoss w) {
      try {
         Display.ItemDisplay star = (Display.ItemDisplay)EntityTypes.ITEM_DISPLAY.create(level, EntitySpawnReason.COMMAND);
         if (star != null) {
            star.setPos(w.getX(), w.getY() + 0.8, w.getZ());
            star.setItemStack(new ItemStack(Items.NETHER_STAR));
            star.addTag(ModItems.DISPLAY_TMP_TAG);
            star.setBillboardConstraints(Display.BillboardConstraints.CENTER);
            star.setTransformationInterpolationDelay(0);
            star.setTransformationInterpolationDuration(40);
            star.setTransformation(new Transformation(new Vector3f(0.0F, 2.8F, 0.0F), new Quaternionf(), new Vector3f(1.3F), new Quaternionf()));
            level.addFreshEntity(star);
            scheduleRemoval(level, star, 60);
         }

         Display.BlockDisplay beam = (Display.BlockDisplay)EntityTypes.BLOCK_DISPLAY.create(level, EntitySpawnReason.COMMAND);
         if (beam != null) {
            beam.setPos(w.getX() - 0.15, w.getY() - 5.0, w.getZ() - 0.15);
            beam.setBlockState(Blocks.SEA_LANTERN.defaultBlockState());
            beam.addTag(ModItems.DISPLAY_TMP_TAG);
            beam.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(0.3F, 12.0F, 0.3F), new Quaternionf()));
            level.addFreshEntity(beam);
            scheduleRemoval(level, beam, 60);
         }

         com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.END_ROD, w.getX(), w.getY() + 2.0, w.getZ(), 60, 1.0, 2.0, 1.0, 0.06);
         com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.TOTEM_OF_UNDYING, w.getX(), w.getY() + 1.5, w.getZ(), 40, 0.8, 1.5, 0.8, 0.2);
      } catch (Throwable ignored) {
      }
   }

   private static void dropRewards(ServerLevel level, WitherBoss w, WitherFight f) {
      ItemEntity star = new ItemEntity(level, w.getX(), w.getY() + 2.5, w.getZ(), new ItemStack(Items.NETHER_STAR));
      star.setPickUpDelay(30);
      level.addFreshEntity(star);

      // The Wither's own loot box. It spins the King family pool, which is
      // exactly the three wither legendaries (Blade, Crown, Staff).
      int boxes = f.king ? 2 : 1;

      for (int i = 0; i < boxes; i++) {
         ItemEntity box = new ItemEntity(
            level,
            w.getX() + (RANDOM.nextDouble() - 0.5) * 0.8,
            w.getY() + 2.4,
            w.getZ() + (RANDOM.nextDouble() - 0.5) * 0.8,
            ModItems.witherLootBox()
         );
         box.setPickUpDelay(30);
         level.addFreshEntity(box);
      }

      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.TOTEM_OF_UNDYING, w.getX(), w.getY() + 2.2, w.getZ(), 40, 0.8, 0.8, 0.8, 0.2);

      try {
         ExperienceOrb.award(level, w.position(), f.king ? 240 : 120);
      } catch (Throwable ignored) {
      }

      if (f.king) {
         for (int i = 0; i < KING_SKULLS; i++) {
            ItemEntity skull = new ItemEntity(level, w.getX() + (RANDOM.nextDouble() - 0.5), w.getY() + 2.0, w.getZ() + (RANDOM.nextDouble() - 0.5), new ItemStack(Items.WITHER_SKELETON_SKULL));
            skull.setPickUpDelay(30);
            level.addFreshEntity(skull);
         }
         ItemEntity essence = new ItemEntity(level, w.getX(), w.getY() + 3.0, w.getZ(), ModItems.kingBone());
         essence.setPickUpDelay(30);
         level.addFreshEntity(essence);
      }
   }

   private static void finishDeath(MinecraftServer server, ServerLevel level, WitherBoss w, WitherFight f) {
      try {
         ServerBossEvent bar = ((WitherBossAccessor)(Object)w).fortuneandfavors$bossEvent();
         if (bar != null) {
            bar.setVisible(false);
         }
      } catch (Throwable ignored) {
      }

      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.POOF, w.getX(), w.getEyeY(), w.getZ(), 40, 1.2, 1.2, 1.2, 0.05);
      mistPools.removeIf(pool -> pool.level == level);
      endDomain(level, f);
      // The fight is over, but the death cut is not: it plays once and runs to its own end (the
      // ceremony animation is shorter than the music), so the session is only forgotten here. A
      // stop would cut the theme off mid-crescendo, which is the one part that must not be sliced.
      WitherMusic.forget(w.getUUID());
      w.remove(RemovalReason.KILLED);
      cleanupAll(server, w.getUUID());
      fights.remove(w.getUUID());
   }

   // ------------------------------------------------------------------
   // Cleanup + shared helpers
   // ------------------------------------------------------------------

   private static void cleanupAll(MinecraftServer server, UUID witherId) {
      cleanupOwned(server, knights.remove(witherId));
      cleanupOwned(server, displays.remove(witherId));
      mistPools.removeIf(pool -> pool.ownerId.equals(witherId));
      homingSkulls.keySet().removeIf(id -> findEntity(server, id) == null);
   }

   private static void cleanupOwned(MinecraftServer server, Set<UUID> owned) {
      if (owned == null) {
         return;
      }
      for (UUID id : owned) {
         Entity e = findEntity(server, id);
         if (e != null) {
            e.remove(RemovalReason.DISCARDED);
         }
      }
   }

   private static void scheduleRemoval(ServerLevel level, Entity entity, int delayTicks) {
      pendingRemovals.add(new PendingRemoval(level, entity.getUUID(), ServerClock.clock(level) + delayTicks));
   }

   private static void tickPendingTransforms(MinecraftServer server) {
      if (pendingTransforms.isEmpty()) {
         return;
      }

      Iterator<PendingTransform> it = pendingTransforms.iterator();

      while (it.hasNext()) {
         PendingTransform pt = it.next();
         if (ServerClock.clock(pt.level()) < pt.at()) {
            continue;
         }
         it.remove();

         if (findEntity(server, pt.entityId()) instanceof Display d && d.isAlive()) {
            d.setTransformationInterpolationDelay(0);
            d.setTransformationInterpolationDuration(pt.scale().y() >= 2.0F ? 60 : 30);
            d.setTransformation(new Transformation(pt.translation(), new Quaternionf(), pt.scale(), new Quaternionf()));
         }
      }
   }

   private static void tickPendingRemovals(MinecraftServer server) {
      if (pendingRemovals.isEmpty()) {
         return;
      }

      Iterator<PendingRemoval> it = pendingRemovals.iterator();

      while (it.hasNext()) {
         PendingRemoval pr = it.next();
         if (ServerClock.clock(pr.level) >= pr.at) {
            it.remove();
            Entity e = findEntity(server, pr.entityId());
            if (e != null) {
               e.remove(RemovalReason.DISCARDED);
            }
         }
      }
   }

   private static WitherSkull spawnSkull(ServerLevel level, WitherBoss w, Vec3 dir, double speed) {
      try {
         WitherSkull skull = new WitherSkull(EntityTypes.WITHER_SKULL, level);
         skull.setPos(w.getX(), w.getEyeY() - 0.2, w.getZ());
         skull.setDeltaMovement(dir.normalize().scale(speed));
         skull.setOwner(w);
         level.addFreshEntity(skull);
         return skull;
      } catch (Throwable t) {
         return null;
      }
   }

   private static Vec3 rotated(Vec3 v, double deg) {
      return v.yRot((float)Math.toRadians(deg));
   }

   /** The boss's own damage source: a mobAttack from the wither - never the
    *  wither-status type, so wither-immunity gear doesn't blanket-block boss
    *  mechanics (that's the crown-bypass design). */
   private static DamageSource mobSource(ServerLevel level, WitherBoss w) {
      return level.damageSources().mobAttack(w);
   }

   private static void craterAt(ServerLevel level, BlockPos center, int radius, Entity breaker) {
      List<BlockPos> positions = new ArrayList<>();

      for (BlockPos p : BlockPos.betweenClosed(center.offset(-radius, -1, -radius), center.offset(radius, 0, radius))) {
         BlockState st = level.getBlockState(p);
         if (!st.isAir() && !st.liquid() && st.getBlock().defaultDestroyTime() >= 0.0F && st.getBlock() != Blocks.BEDROCK) {
            positions.add(p.immutable());
         }
      }

      for (BlockPos p : positions) {
         carveBlock(level, p, level.getBlockState(p));
      }
   }

   private static WitherFight fightNear(ServerPlayer p, double range) {
      double best = range * range;
      WitherFight found = null;

      for (WitherFight f : fights.values()) {
         WitherBoss w = f.bossRef;
         if (w != null && w.isAlive() && w.level() == p.level()) {
            double d = w.distanceToSqr(p);
            if (d < best) {
               best = d;
               found = f;
            }
         }
      }

      return found;
   }

   private static LivingEntity nearestPlayer(ServerLevel level, WitherBoss w, double range) {
      LivingEntity best = null;
      double bestD = range * range;

      for (ServerPlayer p : level.getPlayers(pl -> pl.isAlive() && !pl.isCreative() && !pl.isSpectator() && !com.fortuneandfavors.economy.VanishManager.isVanished(pl))) {
         double d = p.distanceToSqr(w);
         if (d < bestD) {
            bestD = d;
            best = p;
         }
      }

      return best;
   }

   private static UUID summonerNear(ServerLevel level, WitherBoss w) {
      ServerPlayer closest = null;
      double best = 100.0;

      for (ServerPlayer p : level.getPlayers(pl -> pl.isAlive())) {
         double d = p.distanceToSqr(w);
         if (d < best) {
            best = d;
            closest = p;
         }
      }

      return closest != null ? closest.getUUID() : null;
   }

   private static List<ServerPlayer> playersNear(ServerLevel level, Vec3 pos, double range) {
      double r2 = range * range;
      List<ServerPlayer> out = new ArrayList<>();

      for (ServerPlayer p : level.getPlayers(pl -> pl.isAlive() && pl.distanceToSqr(pos.x, pos.y, pos.z) <= r2)) {
         out.add(p);
      }

      return out;
   }

   private static void broadcastNear(ServerLevel level, Vec3 pos, double range, String message) {
      for (ServerPlayer p : playersNear(level, pos, range)) {
         Chat.msg(p, message);
      }
   }

   private static void broadcastActionBar(ServerLevel level, Vec3 pos, double range, String message) {
      for (ServerPlayer p : playersNear(level, pos, range)) {
         p.sendOverlayMessage(Component.literal(Chat.colorize(message)));
      }
   }

   private static void showTitleNear(ServerLevel level, Vec3 pos, double range, String title, String subtitle) {
      for (ServerPlayer p : playersNear(level, pos, range)) {
         showTitle(p, title, subtitle);
      }
   }

   private static void showTitle(ServerPlayer p, String title, String subtitle) {
      if (p == null) {
         return;
      }

      try {
         p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket(10, 50, 10));
         p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket(net.minecraft.network.chat.Component.literal(Chat.colorize(title))));
         p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket(net.minecraft.network.chat.Component.literal(Chat.colorize(subtitle))));
      } catch (Throwable ignored) {
      }
   }

   private static MinecraftServer server(ServerLevel level) {
      return level.getServer();
   }

   private static WitherBoss findWither(MinecraftServer server, UUID id) {
      return findEntity(server, id) instanceof WitherBoss w && w.isAlive() ? w : null;
   }

   /** Crash/stop recovery: discard any wither-animation block/item display
    *  left in the world without live rework state owning it. Runs on the same
    *  periodic stale sweep as the other display cleanups. */
   public static void sweepOrphanedDisplays(MinecraftServer server) {
      if (fights.isEmpty() && pendingRemovals.isEmpty() && displays.isEmpty()) {
         return;
      }

      for (ServerLevel level : server.getAllLevels()) {
         for (Entity e : level.getAllEntities()) {
            boolean ours = e instanceof Display.BlockDisplay || e instanceof Display.ItemDisplay;
            if (!ours || !e.entityTags().contains(ModItems.DISPLAY_TMP_TAG)) {
               continue;
            }

            // A crash leaves the persistent WitherKnights (named, armoured,
            // persistenceRequired) standing around forever - sweep them too.
            if (e instanceof Mob && e.entityTags().contains(ModItems.WITHER_KNIGHT_TAG)
               && !knights.values().stream().anyMatch(s -> s.contains(e.getUUID()))) {
               e.remove(RemovalReason.DISCARDED);
               continue;
            }

            boolean tracked = false;
            for (Set<UUID> owned : displays.values()) {
               if (owned.contains(e.getUUID())) {
                  tracked = true;
                  break;
               }
            }

            for (PendingRemoval pr : pendingRemovals) {
               if (pr.entityId().equals(e.getUUID())) {
                  tracked = true;
                  break;
               }
            }

            if (!tracked) {
               e.remove(RemovalReason.DISCARDED);
            }
         }
      }
   }

   private static Entity findEntity(MinecraftServer server, UUID id) {
      for (ServerLevel level : server.getAllLevels()) {
         Entity e = level.getEntity(id);
         if (e != null) {
            return e;
         }
      }
      return null;
   }
}
