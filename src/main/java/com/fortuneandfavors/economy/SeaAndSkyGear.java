package com.fortuneandfavors.economy;

import com.fortuneandfavors.ModItems;
import com.fortuneandfavors.util.Chat;
import com.fortuneandfavors.util.Safe;
import com.mojang.math.Transformation;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Display.BlockDisplay;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * The six legendaries of the two newest fights - the Drowned Sovereign's and the Gale Warden's.
 *
 * <h2>One file, two ideas</h2>
 * The sea set is about <b>where the other body ends up</b>. Nothing in it is a damage ability: the
 * Grasp hauls a target in and drives it into the floor, the Tidecaller rolls a wave down your
 * sightline that picks up whatever it passes, and the Chain decides - per click - which of the two
 * of you crosses the room. Fighting one of them is a fight about footing.
 *
 * <p>The sky set is about <b>what is moving and how fast</b>. The Skybreaker pays more the faster you
 * already were, the Chakram is a body in flight that comes back, and the Mantle is not a weapon at
 * all: it is the boss's own mobility, which is why none of its abilities live on a click.
 *
 * <h2>Why the Mantle has no right-click</h2>
 * A chestplate's right-click is how you put it on. Everything it does is therefore read off the tick
 * loop - falling slower, the sneak dash, the updraft, the sprint tailwind, and the one save per
 * minute - which is the same shape the Astral Mantle already uses.
 *
 * <h2>Everything scheduled is in one place</h2>
 * A wave, a vortex, a thrown ring and a sky-slam are all "a thing that happens shortly, somewhere",
 * and several of them can be in the air at once. They are four small records in four lists, ticked
 * down together, rather than a thread per ability.
 *
 * <p>All particles go through {@link BossVfx}, so a Bedrock client is handed a bounded version of
 * every shape instead of a queue it cannot drain.
 */
public final class SeaAndSkyGear {

   private SeaAndSkyGear() {
   }

   // ---------------------------------------------------------------- the sea: Leviathan's Grasp

   /** Sea-green, for the Grasp and the Tidecaller. */
   private static final int TIDE = 0x2FA8C8;
   /** White water, for the crest of every splash. */
   private static final int FOAM = 0xD8F4FF;
   /** The deep's teal, for the Abyssal Chain. */
   private static final int ABYSS = 0x0E6A70;
   /** Pale wind, for the sky set. */
   private static final int GALE = 0xDDE8F0;

   /** How far the claw can reach to haul something in. */
   private static final double GRASP_REACH = 14.0;
   private static final double GRASP_PULL = 1.25;
   /** Inside this range Grasp becomes Crush - there is no point pulling a body that is already here. */
   private static final double CRUSH_RANGE = 3.4;
   private static final float CRUSH_DAMAGE = 13.0F;
   private static final double CRUSH_SLAM = -1.15;
   private static final long GRASP_COOLDOWN_TICKS = 140L;
   /**
    * The passive: knockback grows as the target's health falls.
    *
    * <p>Read as a fraction of maximum health rather than as a flat number, because "the weaker they
    * are the further they go" has to mean the same thing against a 20-health drowneder and a
    * 760-health Sovereign. A full-health body is thrown barely further than a punch; an empty one
    * leaves the room.
    */
   private static final double GRASP_DRAG_AT_FULL = 0.45;
   private static final double GRASP_DRAG_AT_EMPTY = 1.85;

   // ---------------------------------------------------------------- the sea: Tidecaller

   /** The wave: how far it rolls, how wide it is, and what it pays along the way. */
   private static final double WAVE_REACH = 19.0;
   private static final double WAVE_HALF_WIDTH = 2.3;
   private static final float WAVE_DAMAGE = 7.0F;
   private static final double WAVE_LAUNCH = 0.95;
   private static final int WAVE_TICKS_PER_STEP = 2;
   private static final long WAVE_COOLDOWN_TICKS = 26L;

   // ---------------------------------------------------------------- the sea: Abyssal Chain

   private static final double CHAIN_REACH = 16.0;
   private static final double CHAIN_PULL = 1.5;
   private static final float CHAIN_PULL_DAMAGE = 6.0F;
   private static final double HOOK_PULL = 1.25;
   private static final long CHAIN_COOLDOWN_TICKS = 24L;
   /** A marked body takes this much more knockback from the bearer's blows. */
   private static final double MARK_KNOCKBACK_MULTIPLIER = 2.1;
   private static final long MARK_TICKS = 120L;
   /** What a blow landed straight out of a hook pays on top. */
   private static final float DEEP_STRIKE_DAMAGE = 8.0F;
   private static final long DEEP_STRIKE_WINDOW_TICKS = 60L;

   /** The vortex both the Grasp and the Chain can open: how wide, how hard it sucks, how long. */
   private static final double VORTEX_RADIUS = 5.5;
   private static final double VORTEX_PULL = 0.42;
   private static final int VORTEX_TICKS = 60;
   private static final long VORTEX_COOLDOWN_TICKS = 140L;

   // ---------------------------------------------------------------- the sky: Skybreaker

   private static final double SLASH_HALF_WIDTH = 1.7;
   private static final float SLASH_DAMAGE = 9.0F;
   private static final double SLASH_LAUNCH = 0.8;
   private static final double SLASH_REACH = 15.0;
   private static final long SLASH_COOLDOWN_TICKS = 28L;
   /** Updraft: a landed blow lifts both bodies a little. */
   private static final double UPDRAFT_SELF = 0.42;
   private static final double UPDRAFT_VICTIM = 0.6;
   /** Downforce: a blow on an airborne body drives it into the ground for extra. */
   private static final float DOWNFORCE_DAMAGE = 9.0F;
   private static final double DOWNFORCE_SLAM = -1.5;
   /** Momentum: at this much horizontal speed the passive is at its ceiling. */
   private static final double MOMENTUM_FULL_SPEED = 0.32;
   private static final float MOMENTUM_MAX_BONUS = 7.0F;
   /** Break the Sky: the leap, then the ring it lands. */
   private static final double BREAK_RADIUS = 7.5;
   private static final float BREAK_DAMAGE = 15.0F;
   private static final double BREAK_PUSH = 1.4;
   private static final double BREAK_LEAP = 1.15;
   private static final int BREAK_FALL_TICKS = 12;
   private static final long BREAK_COOLDOWN_TICKS = 200L;

   // ---------------------------------------------------------------- the sky: Gale Chakram

   private static final double CHAKRAM_SPEED = 1.15;
   private static final double CHAKRAM_REACH = 15.0;
   private static final float CHAKRAM_DAMAGE = 7.0F;
   private static final double CHAKRAM_PUSH = 0.5;
   private static final long CHAKRAM_COOLDOWN_TICKS = 30L;
   /** Razor Current: each consecutive hit on one body knocks it further back. */
   private static final double RAZOR_STEP = 0.34;
   private static final double RAZOR_MAX = 1.7;
   /** Windcurve: how hard a sneak throw bends the outbound leg. */
   private static final double CURVE_SIDE = 0.055;
   /** Air Catch: catching it in the air is worth a shove upward. */
   private static final double AIR_CATCH_LIFT = 0.55;
   /** Cyclone Return: how often a throw is a cyclone, and how many passes it gets when it is. */
   /**
    * The ring's own body: what it is made of, how big, and how fast it turns.
    *
    * <p>The Chakram is the one legendary that is *out there* rather than in your hands, and a shape
    * drawn only in particles reads as a puff of smoke with a damage value. It carries a spinning
    * {@link BlockDisplay} from the moment it leaves the hand to the moment it is caught, so a player
    * can see where the return is coming from and time the catch - which is the whole weapon.
    */
   private static final BlockState CHAKRAM_BODY = Blocks.STAINED_GLASS.white().defaultBlockState();

   /** Degrees of yaw the ring turns per tick while it is in the air. */
   private static final float CHAKRAM_SPIN = 26.0F;

   private static final float CYCLONE_CHANCE = 0.30F;
   private static final int CYCLONE_HITS = 3;

   // ---------------------------------------------------------------- the sky: Warden's Mantle

   /** Light as Air: what a fall costs while wearing it. */
   private static final float MANTLE_FALL_MULTIPLIER = 0.25F;
   private static final double WINDSTEP_POWER = 1.3;
   private static final long WINDSTEP_COOLDOWN_TICKS = 60L;
   /** Two sneak taps inside this window are the dash. */
   private static final long DOUBLE_TAP_TICKS = 9L;
   /** Updraft: a burst of lift, on a short clock. */
   private static final double MANTLE_UPDRAFT = 1.05;
   private static final long MANTLE_UPDRAFT_COOLDOWN_TICKS = 50L;
   /** Tailwind: sprint this long, then the wind helps for this long. */
   private static final int TAILWIND_AFTER_TICKS = 100;
   private static final int TAILWIND_TICKS = 200;
   private static final int TAILWIND_AMPLIFIER = 1;
   /** Second Wind: the one save, and how long before it comes back. */
   private static final long SECOND_WIND_COOLDOWN_TICKS = 1200L;

   private static final Random RANDOM = new Random();

   // ---------------------------------------------------------------- state

   /** Cooldowns, keyed by player then by ability name. */
   private static final Map<UUID, Map<String, Long>> COOLDOWNS = new HashMap<>();
   /** Abyssal Chain: bodies this bearer has marked, and until when. */
   private static final Map<UUID, Map<UUID, Long>> MARKS = new HashMap<>();
   /** Abyssal Chain: bearers whose next blow is a Deep Strike, and until when. */
   private static final Map<UUID, Long> DEEP_STRIKE = new HashMap<>();
   /** Gale Chakram: consecutive hits per bearer per body, for Razor Current. */
   private static final Map<UUID, Map<UUID, Integer>> RAZOR = new HashMap<>();
   /** Warden's Mantle: double-tap detection, sprint time banked, and the Second Wind clock. */
   private static final Map<UUID, Long> LAST_SNEAK = new HashMap<>();
   private static final Map<UUID, Boolean> WAS_SNEAKING = new HashMap<>();
   private static final Map<UUID, Integer> SPRINT_TICKS = new HashMap<>();
   private static final Map<UUID, Long> TAILWIND_UNTIL = new HashMap<>();
   private static final Map<UUID, Long> SECOND_WIND_UNTIL = new HashMap<>();
   private static final List<Grip> GRIPS = new java.util.ArrayList<>();
   private static final List<Grip> SURGES = new java.util.ArrayList<>();
   private static final Map<UUID, Map<UUID, Integer>> PRESSURE = new HashMap<>();
   private static final Map<UUID, Integer> GALE_CHARGE = new HashMap<>();
   private static final int GRIP_TICKS = 40;
   private static final float GRIP_SQUEEZE = 2.0F;
   private static final long DEPTHS_COOLDOWN_TICKS = 240L;
   private static final double DEPTHS_RADIUS = 5.0;
   private static final float DEPTHS_DAMAGE = 9.0F;
   private static final long SURGE_COOLDOWN_TICKS = 120L;
   private static final double SURGE_POWER = 1.7;
   private static final int SURGE_TICKS = 10;
   private static final float SURGE_DAMAGE = 6.0F;
   private static final int EYE_TICKS = 60;
   private static final int SAW_TICKS = 40;

   /** A held body (Leviathan's Maw), or a Riptide Surge in flight (target null, anchor = heading). */
   private static final class Grip {
      final UUID owner;
      final UUID target;
      final ServerLevel level;
      final Vec3 anchor;
      final Set<UUID> hit = new HashSet<>();
      int ticks;

      Grip(UUID owner, UUID target, ServerLevel level, Vec3 anchor, int ticks) {
         this.owner = owner;
         this.target = target;
         this.level = level;
         this.anchor = anchor;
         this.ticks = ticks;
      }
   }

   /** One rolling wave: a point that walks forward and pays whatever it overlaps. */
   private static final class Wave {
      final UUID owner;
      final ServerLevel level;
      final Vec3 forward;
      final Vec3 side;
      Vec3 at;
      double travelled;
      int step;

      Wave(UUID owner, ServerLevel level, Vec3 at, Vec3 forward) {
         this.owner = owner;
         this.level = level;
         this.at = at;
         this.forward = forward;
         this.side = new Vec3(-forward.z, 0.0, forward.x).normalize();
      }
   }

   /** One whirlpool: a point everything nearby is slowly dragged toward. */
   private static final class Vortex {
      final UUID owner;
      final ServerLevel level;
      final Vec3 at;
      int ticks;

      Vortex(UUID owner, ServerLevel level, Vec3 at, int ticks) {
         this.owner = owner;
         this.level = level;
         this.at = at;
         this.ticks = ticks;
      }
   }

   /**
    * One thrown ring: out, then home - and on a Cyclone Return, out and home a few more times.
    *
    * <p>{@code hit} is cleared at every turn, which is what makes "it hits twice, once going out and
    * once coming back" true for the same body: the second pass is a second hit rather than a
    * duplicate the bookkeeping quietly swallows.
    */
   private static final class Chakram {
      final UUID owner;
      final ServerLevel level;
      Vec3 forward;
      final boolean cyclone;
      /** Sawstorm: ticks left grinding at the far end before the return. */
      int hover;
      final Set<UUID> hit = new HashSet<>();
      Vec3 at;
      double travelled;
      boolean returning;
      int turnsLeft;
      /** The spinning body itself, or null when the display could not be created. */
      BlockDisplay display;
      /** Where the spin is, in degrees. Its own field: the return leg runs backwards. */
      float spin;

      Chakram(UUID owner, ServerLevel level, Vec3 at, Vec3 forward, boolean cyclone) {
         this.owner = owner;
         this.level = level;
         this.at = at;
         this.forward = forward;
         this.cyclone = cyclone;
         this.turnsLeft = cyclone ? CYCLONE_HITS : 1;
      }
   }

   /** One leap that is on its way down, to land as a ring where the caster comes back. */
   private static final class SkySlam {
      final UUID owner;
      final ServerLevel level;
      int fuse;

      SkySlam(UUID owner, ServerLevel level, int fuse) {
         this.owner = owner;
         this.level = level;
         this.fuse = fuse;
      }
   }

   private static final List<Wave> WAVES = new ArrayList<>();
   private static final List<Vortex> VORTEXES = new ArrayList<>();
   private static final List<Chakram> CHAKRAMS = new ArrayList<>();
   private static final List<SkySlam> SLAMS = new ArrayList<>();

   // ---------------------------------------------------------------- the sea: Grasp

   /** Right-click the Grasp: sneak opens a vortex, otherwise haul the nearest body in (or Crush it). */
   public static String useGrasp(ServerPlayer player, ItemStack held) {
      ServerLevel level = levelOf(player);
      if (level == null) {
         return null;
      }
      if (player.isShiftKeyDown()) {
         if (cooldown(player, "depths", DEPTHS_COOLDOWN_TICKS)) {
            return null;
         }
         depthCharge(level, player);
         return null;
      }
      LivingEntity target = rayTarget(player, level, GRASP_REACH);
      if (target == null) {
         bar(player, "&7Nothing in your sights &8- &7look at a body.");
         return null;
      }
      if (cooldown(player, "grasp", GRASP_COOLDOWN_TICKS)) {
         return null;
      }
      seize(level, player, target);
      return null;
   }

   /**
    * <b>Leviathan's Maw.</b> A tentacle of seawater erupts under the target and holds it two
    * blocks up for two seconds, wringing it every half second, then slams it into the floor.
    * Heavy bodies (bosses) are wrung but not lifted.
    */
   private static void seize(ServerLevel level, ServerPlayer player, LivingEntity target) {
      Vec3 floor = target.position();
      GRIPS.add(new Grip(player.getUUID(), target.getUUID(), level, floor.add(0.0, heft(target) >= 0.5 ? 2.0 : 0.0, 0.0), GRIP_TICKS));
      Fx.tentacle(level, ParticleTypes.SPLASH, floor, 3.5, GRIP_TICKS, TIDE);
      Fx.whirlpool(level, ParticleTypes.BUBBLE, floor.add(0.0, 0.05, 0.0), 2.2, GRIP_TICKS, ABYSS);
      Fx.shape(level, com.fortuneandfavors.net.FfVfx.GEYSER, ParticleTypes.SPLASH, floor, Vec3.ZERO, 3.0, 0.0, FOAM);
      level.playSound(null, floor.x, floor.y, floor.z, SoundEvents.ELDER_GUARDIAN_HURT, SoundSource.PLAYERS, 1.0F, 0.6F);
      level.playSound(null, floor.x, floor.y, floor.z, SoundEvents.GENERIC_SPLASH, SoundSource.PLAYERS, 1.2F, 0.7F);
      bar(player, "&3Leviathan's Maw &8- &7it has &f" + target.getName().getString() + "&7.");
   }

   private static void tickGrips(MinecraftServer server) {
      for (Iterator<Grip> it = GRIPS.iterator(); it.hasNext();) {
         Grip g = it.next();
         ServerPlayer owner = server.getPlayerList().getPlayer(g.owner);
         if (!(g.level.getEntity(g.target) instanceof LivingEntity target) || !target.isAlive() || owner == null) {
            it.remove();
            continue;
         }
         g.ticks--;
         if (g.ticks > 0) {
            if (heft(target) >= 0.5) {
               Vec3 to = g.anchor.subtract(target.position());
               target.setDeltaMovement(to.scale(0.35));
               target.fallDistance = 0.0F;
               target.hurtMarked = true;
            }
            if (g.ticks % 10 == 0) {
               target.invulnerableTime = 0;
               target.hurtServer(g.level, g.level.damageSources().playerAttack(owner), GRIP_SQUEEZE);
               Fx.shape(g.level, com.fortuneandfavors.net.FfVfx.RING, ParticleTypes.BUBBLE_POP, target.position().add(0.0, target.getBbHeight() * 0.5, 0.0), Vec3.ZERO, 1.2, 0.0, FOAM);
               g.level.playSound(null, target.getX(), target.getY(), target.getZ(), SoundEvents.PLAYER_HURT_DROWN, SoundSource.PLAYERS, 0.8F, 0.8F);
            }
            continue;
         }
         it.remove();
         target.invulnerableTime = 0;
         target.hurtServer(g.level, g.level.damageSources().playerAttack(owner), CRUSH_DAMAGE);
         target.setDeltaMovement(0.0, CRUSH_SLAM, 0.0);
         target.hurtMarked = true;
         Vec3 floor = new Vec3(target.getX(), g.anchor.y - (heft(target) >= 0.5 ? 2.0 : 0.0), target.getZ());
         Fx.shockwave(g.level, ParticleTypes.SPLASH, floor, 3.5, TIDE);
         Fx.shape(g.level, com.fortuneandfavors.net.FfVfx.GEYSER, ParticleTypes.SPLASH, floor, Vec3.ZERO, 4.0, 0.0, FOAM);
         Fx.pulseWave(g.level, ParticleTypes.SPLASH, floor, 4.0, 10, TIDE);
         g.level.playSound(null, floor.x, floor.y, floor.z, SoundEvents.MACE_SMASH_GROUND_HEAVY, SoundSource.PLAYERS, 1.3F, 0.8F);
         bar(owner, "&3Crush &8- &f" + target.getName().getString() + "&7 met the seabed.");
      }
   }

   /** <b>Depth Charge</b>: four tentacles burst up round you and throw everything close into the air. */
   private static void depthCharge(ServerLevel level, ServerPlayer player) {
      Vec3 at = player.position();
      for (int i = 0; i < 4; i++) {
         double a = i * Math.PI / 2.0 + player.getYRot() * Math.PI / 180.0;
         Fx.tentacle(level, ParticleTypes.SPLASH, at.add(Math.cos(a) * 3.0, 0.0, Math.sin(a) * 3.0), 4.5, 24, i % 2 == 0 ? TIDE : ABYSS);
      }
      Fx.whirlpool(level, ParticleTypes.BUBBLE, at.add(0.0, 0.05, 0.0), DEPTHS_RADIUS, 24, TIDE);
      Fx.pulseWave(level, ParticleTypes.SPLASH, at, DEPTHS_RADIUS + 1.0, 12, FOAM);
      int hits = 0;
      for (LivingEntity e : enemiesNear(player, level, at, DEPTHS_RADIUS)) {
         if (e.distanceToSqr(at) > DEPTHS_RADIUS * DEPTHS_RADIUS) {
            continue;
         }
         e.hurtServer(level, level.damageSources().playerAttack(player), DEPTHS_DAMAGE);
         Vec3 away = flatAway(at, e);
         e.setDeltaMovement(away.x * 0.6 * heft(e), 0.9 * heft(e), away.z * 0.6 * heft(e));
         e.hurtMarked = true;
         hits++;
      }
      level.playSound(null, at.x, at.y, at.z, SoundEvents.ELDER_GUARDIAN_CURSE, SoundSource.PLAYERS, 0.8F, 1.2F);
      level.playSound(null, at.x, at.y, at.z, SoundEvents.GENERIC_SPLASH, SoundSource.PLAYERS, 1.5F, 0.6F);
      bar(player, "&3Depth Charge &8- &7the sea threw &f" + hits + "&7.");
   }

   /** Every third hit on the same body calls a geyser under it and drags it to you. */
   public static void onGraspHit(ServerPlayer hitter, LivingEntity victim) {
      ServerLevel level = levelOf(hitter);
      if (level == null) {
         return;
      }
      int n = PRESSURE.computeIfAbsent(hitter.getUUID(), k -> new HashMap<>()).merge(victim.getUUID(), 1, Integer::sum);
      Fx.halo(level, ParticleTypes.BUBBLE, victim.position().add(0.0, victim.getBbHeight() + 0.3, 0.0), 0.3 + n * 0.15, 20, TIDE);
      if (n < 3) {
         return;
      }
      PRESSURE.get(hitter.getUUID()).remove(victim.getUUID());
      victim.invulnerableTime = 0;
      victim.hurtServer(level, level.damageSources().playerAttack(hitter), 6.0F);
      double drag = graspDrag(victim.getHealth(), victim.getMaxHealth());
      Vec3 flat = flatAway(victim.position(), hitter).scale(-1.0);
      victim.push(-flat.x * drag, 0.5, -flat.z * drag);
      victim.hurtMarked = true;
      Fx.shape(level, com.fortuneandfavors.net.FfVfx.GEYSER, ParticleTypes.SPLASH, victim.position(), Vec3.ZERO, 3.5, 0.0, FOAM);
      level.playSound(null, victim.getX(), victim.getY(), victim.getZ(), SoundEvents.GENERIC_SPLASH, SoundSource.PLAYERS, 1.2F, 0.8F);
      bar(hitter, "&3Pressure &8- &7the deep pulls it under.");
   }

   public static double graspDrag(float health, float maxHealth) {
      if (maxHealth <= 0.0F) {
         return GRASP_DRAG_AT_FULL;
      }
      double fraction = Math.max(0.0, Math.min(1.0, health / maxHealth));
      return GRASP_DRAG_AT_EMPTY + (GRASP_DRAG_AT_FULL - GRASP_DRAG_AT_EMPTY) * fraction;
   }

   // ---------------------------------------------------------------- the sea: Tidecaller

   /**
    * Right-click the Tidecaller: a wave down your sightline.
    *
    * <p>Sneak does the same thing. Sneak used to open the shared Undertow vortex instead, which
    * made this a third copy of the Grasp's and the Chain's trick - the same vortex, from three
    * weapons, on a trident whose one idea is the wave. Worse, a sneak-use on a trident is a throw
    * in vanilla, so the branch was also what a player hit by accident. The vortex is the Grasp's
    * and the Chain's; this is the wave.
    */
   public static String useTidecaller(ServerPlayer player, ItemStack held) {
      ServerLevel level = levelOf(player);
      if (level == null) {
         return null;
      }
      if (player.isShiftKeyDown()) {
         if (cooldown(player, "surge", SURGE_COOLDOWN_TICKS)) {
            return null;
         }
         riptideSurge(level, player);
         return null;
      }
      if (cooldown(player, "wave", WAVE_COOLDOWN_TICKS)) {
         return null;
      }
      Vec3 look = flatLook(player);
      Vec3 from = player.position().add(0.0, 0.2, 0.0).add(look.scale(1.2));
      WAVES.add(new Wave(player.getUUID(), level, from, look));
      Fx.tideWave(level, ParticleTypes.SPLASH, from, look, WAVE_REACH, (int)(WAVE_REACH * WAVE_TICKS_PER_STEP), TIDE);
      Fx.shape(level, com.fortuneandfavors.net.FfVfx.GEYSER, ParticleTypes.SPLASH, from, Vec3.ZERO, 2.5, 0.0, FOAM);
      level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.TRIDENT_RIPTIDE_1, SoundSource.PLAYERS, 1.3F, 0.8F);
      bar(player, "&3The tide answers &8- &7it is going that way.");
      return null;
   }

   // ---------------------------------------------------------------- the sea: Abyssal Chain

   /** Right-click the Chain: pull a body in, pull yourself to a wall, or open a vortex. */
   /**
    * <b>Riptide Surge</b> (sneak): you ride a breaking wave forward. Anything you pass through is
    * bowled aside and hurt once, and the wave breaks where you stop.
    */
   private static void riptideSurge(ServerLevel level, ServerPlayer player) {
      Vec3 look = flatLook(player);
      player.setDeltaMovement(look.x * SURGE_POWER, 0.35, look.z * SURGE_POWER);
      player.hurtMarked = true;
      player.fallDistance = 0.0F;
      SURGES.add(new Grip(player.getUUID(), null, level, look, SURGE_TICKS));
      Fx.tideWave(level, ParticleTypes.SPLASH, player.position(), look, 9.0, SURGE_TICKS, TIDE);
      Fx.shape(level, com.fortuneandfavors.net.FfVfx.GEYSER, ParticleTypes.SPLASH, player.position(), Vec3.ZERO, 2.0, 0.0, FOAM);
      level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.TRIDENT_RIPTIDE_3, SoundSource.PLAYERS, 1.3F, 1.0F);
      bar(player, "&3Riptide Surge &8- &7ride it.");
   }

   private static void tickSurges(MinecraftServer server) {
      for (Iterator<Grip> it = SURGES.iterator(); it.hasNext();) {
         Grip g = it.next();
         ServerPlayer owner = server.getPlayerList().getPlayer(g.owner);
         if (owner == null || !owner.isAlive() || owner.level() != g.level) {
            it.remove();
            continue;
         }
         owner.fallDistance = 0.0F;
         Fx.vanilla(g.level, ParticleTypes.SPLASH, owner.getX(), owner.getY() + 0.2, owner.getZ(), 6, 0.4, 0.1, 0.4, 0.1);
         if (owner.tickCount % 2 == 0) {
            Fx.shape(g.level, com.fortuneandfavors.net.FfVfx.RING, ParticleTypes.BUBBLE_POP, owner.position().add(0.0, 0.1, 0.0), Vec3.ZERO, 1.4, 0.0, FOAM);
         }
         for (LivingEntity e : enemiesNear(owner, g.level, owner.position(), 2.2)) {
            if (!g.hit.add(e.getUUID())) {
               continue;
            }
            e.hurtServer(g.level, g.level.damageSources().playerAttack(owner), SURGE_DAMAGE);
            Vec3 side = new Vec3(-g.anchor.z, 0.0, g.anchor.x);
            double sign = side.dot(e.position().subtract(owner.position())) >= 0.0 ? 1.0 : -1.0;
            e.push(side.x * sign * 0.9, 0.45, side.z * sign * 0.9);
            e.hurtMarked = true;
         }
         if (--g.ticks <= 0) {
            it.remove();
            Fx.shockwave(g.level, ParticleTypes.SPLASH, owner.position(), 3.0, TIDE);
            Fx.shape(g.level, com.fortuneandfavors.net.FfVfx.GEYSER, ParticleTypes.SPLASH, owner.position(), Vec3.ZERO, 3.0, 0.0, FOAM);
            g.level.playSound(null, owner.getX(), owner.getY(), owner.getZ(), SoundEvents.GENERIC_SPLASH, SoundSource.PLAYERS, 1.2F, 0.9F);
         }
      }
   }

   public static String useChain(ServerPlayer player, ItemStack held) {
      ServerLevel level = levelOf(player);
      if (level == null) {
         return null;
      }
      if (player.isShiftKeyDown()) {
         return openVortex(player, level);
      }
      if (cooldown(player, "chain", CHAIN_COOLDOWN_TICKS)) {
         return null;
      }
      // One ray: a body in the crosshair before any block is pulled; otherwise the block hooks you.
      LivingEntity target = rayTarget(player, level, CHAIN_REACH);
      if (target != null) {
         chainPull(level, player, target);
         return null;
      }
      HitResult hit = player.pick(CHAIN_REACH, 0.0F, false);
      if (hit instanceof BlockHitResult block) {
         abyssalHook(level, player, block);
         return null;
      }
      bar(player, "&7Nothing to hook &8- &7point at a body or a wall.");
      return null;
   }

   private static void chainPull(ServerLevel level, ServerPlayer player, LivingEntity target) {
      Vec3 toward = player.position().subtract(target.position()).normalize();
      double heft = heft(target);
      target.setDeltaMovement(toward.x * CHAIN_PULL * heft, 0.22 * heft, toward.z * CHAIN_PULL * heft);
      target.hurtMarked = true;
      // It pays damage for the pull. A weapon that only decides *where* a body is cannot win a
      // fight, and the Chain has the reach, the cooldown and the hook to be a weapon rather than a
      // lever - the drag is what the damage is for.
      target.hurtServer(level, level.damageSources().playerAttack(player), CHAIN_PULL_DAMAGE);
      markTarget(player, target);
      Fx.shape(level, com.fortuneandfavors.net.FfVfx.BEAM, ParticleTypes.SCULK_SOUL, target.position().add(0.0, 1.0, 0.0), player.getEyePosition(), 0.0, 0.0, ABYSS);
      Fx.shape(level, com.fortuneandfavors.net.FfVfx.CLASH, ParticleTypes.CRIT, target.position().add(0.0, target.getBbHeight() * 0.6, 0.0), toward, 0.0, 0.0, ABYSS);
      level.playSound(null, target.getX(), target.getY(), target.getZ(), SoundEvents.CHAIN_BREAK, SoundSource.PLAYERS, 1.0F, 0.7F);
      bar(player, "&3Chain Pull &8- &f" + target.getName().getString() + "&7 came to you.");
   }

   private static void abyssalHook(ServerLevel level, ServerPlayer player, BlockHitResult block) {
      Vec3 to = new Vec3(block.getBlockPos().getX() + 0.5, block.getBlockPos().getY() + 0.5, block.getBlockPos().getZ() + 0.5);
      Vec3 pull = to.subtract(player.position());
      double distance = Math.max(1.0, pull.length());
      Vec3 flat = new Vec3(pull.x, 0.0, pull.z).normalize();
      // Scaled by distance so a hook across a room arrives and a hook at your feet does not overshoot.
      double speed = Math.min(2.4, HOOK_PULL + distance * 0.09);
      player.setDeltaMovement(flat.x * speed, Math.max(0.34, pull.y / distance * speed * 0.6), flat.z * speed);
      player.hurtMarked = true;
      player.fallDistance = 0.0F;
      DEEP_STRIKE.put(player.getUUID(), ServerClock.clock(player.level()) + DEEP_STRIKE_WINDOW_TICKS);
      Fx.shape(level, com.fortuneandfavors.net.FfVfx.BEAM, ParticleTypes.SCULK_SOUL, player.getEyePosition(), to, 0.0, 0.0, ABYSS);
      Fx.shape(level, com.fortuneandfavors.net.FfVfx.ICE_BURST, ParticleTypes.SCULK_SOUL, to, Vec3.ZERO, 0.8, 0.0, ABYSS);
      level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.CHAIN_FALL, SoundSource.PLAYERS, 1.0F, 1.3F);
      bar(player, "&3Abyssal Hook &8- &7the chain brought you instead.");
   }

   /** A landed Chain blow: a Deep Strike out of a hook, and the mark it leaves behind. */
   public static void onChainHit(ServerPlayer hitter, LivingEntity victim) {
      ServerLevel level = levelOf(hitter);
      if (level == null) {
         return;
      }
      long now = ServerClock.clock(hitter.level());
      Long until = DEEP_STRIKE.get(hitter.getUUID());
      if (until != null && now <= until) {
         DEEP_STRIKE.remove(hitter.getUUID());
         victim.hurtServer(level, level.damageSources().playerAttack(hitter), DEEP_STRIKE_DAMAGE);
         Fx.shape(level, com.fortuneandfavors.net.FfVfx.RESONANCE, ParticleTypes.SCULK_SOUL, victim.position().add(0.0, 1.0, 0.0), Vec3.ZERO, 14.0, 0.0, ABYSS);
         Fx.shape(level, com.fortuneandfavors.net.FfVfx.CLASH, ParticleTypes.CRIT, victim.position().add(0.0, 1.0, 0.0), hitter.getLookAngle(), 0.0, 0.0, ABYSS);
         level.playSound(null, victim.getX(), victim.getY(), victim.getZ(), SoundEvents.SCULK_CATALYST_BLOOM, SoundSource.PLAYERS, 1.0F, 0.8F);
         bar(hitter, "&3Deep Strike &8- &7it was still moving when it landed.");
      }
      boolean marked = markTarget(hitter, victim);
      if (marked) {
         Vec3 away = flatAway(hitter, victim);
         victim.push(away.x * 0.5, 0.18, away.z * 0.5);
         victim.hurtMarked = true;
      }
   }

   /** Marks {@code victim} for this bearer. Returns whether it was already marked. */
   private static boolean markTarget(ServerPlayer bearer, LivingEntity victim) {
      long until = ServerClock.clock(bearer.level()) + MARK_TICKS;
      Map<UUID, Long> marks = MARKS.computeIfAbsent(bearer.getUUID(), k -> new HashMap<>());
      Long previous = marks.put(victim.getUUID(), until);
      Fx.vanillaOnly(() -> BossVfx.at(
         victim.level() instanceof ServerLevel sl ? sl : null,
         victim.position().add(0.0, victim.getBbHeight() + 0.35, 0.0),
         0.0,
         ParticleTypes.SCULK_SOUL,
         8,
         0.2,
         0.1,
         0.2,
         0.02
      ));
      return previous != null && previous > ServerClock.clock(bearer.level());
   }

   /** How much knockback a marked body takes from the bearer's blows. */
   public static double markMultiplier(boolean marked) {
      return marked ? MARK_KNOCKBACK_MULTIPLIER : 1.0;
   }

   private static boolean isMarked(ServerPlayer bearer, LivingEntity victim) {
      Map<UUID, Long> marks = MARKS.get(bearer.getUUID());
      if (marks == null) {
         return false;
      }
      Long until = marks.get(victim.getUUID());
      return until != null && until >= ServerClock.clock(bearer.level());
   }

   // ---------------------------------------------------------------- the sky: Skybreaker

   /** Right-click the Skybreaker: a wind blade down your sightline, or a leap on a sneak. */
   public static String useSkybreaker(ServerPlayer player, ItemStack held) {
      ServerLevel level = levelOf(player);
      if (level == null) {
         return null;
      }
      if (player.isShiftKeyDown()) {
         if (cooldown(player, "break", BREAK_COOLDOWN_TICKS)) {
            return null;
         }
         // Eye of the Storm: you hang in the air at the centre of a storm that cuts at everything
         // around you, then drops you gently.
         player.setDeltaMovement(player.getDeltaMovement().x * 0.2, 0.6, player.getDeltaMovement().z * 0.2);
         player.hurtMarked = true;
         player.fallDistance = 0.0F;
         SLAMS.add(new SkySlam(player.getUUID(), level, EYE_TICKS));
         Fx.stormCell(level, ParticleTypes.ELECTRIC_SPARK, player.position(), 6.0, EYE_TICKS, GALE);
         Fx.featherStorm(level, ParticleTypes.GUST, player.position().add(0.0, 1.0, 0.0), 5.0, EYE_TICKS, 0xFFFFFF);
         level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.BREEZE_JUMP, SoundSource.PLAYERS, 1.3F, 0.7F);
         level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.TRIDENT_THUNDER, SoundSource.PLAYERS, 0.8F, 1.4F);
         bar(player, "&fEye of the Storm &8- &7everything near you is in the wind now.");
         return null;
      }
      if (cooldown(player, "slash", SLASH_COOLDOWN_TICKS)) {
         return null;
      }
      windSlash(level, player);
      return null;
   }

   private static void windSlash(ServerLevel level, ServerPlayer player) {
      Vec3 look = flatLook(player);
      Vec3 side = new Vec3(-look.z, 0.0, look.x);
      int hits = 0;
      for (LivingEntity e : enemiesNear(player, level, player.position().add(0.0, 1.0, 0.0), SLASH_REACH)) {
         Vec3 offset = e.position().subtract(player.position());
         double forward = offset.x * look.x + offset.z * look.z;
         double lateral = Math.abs(offset.x * side.x + offset.z * side.z);
         if (forward < 0.0 || forward > SLASH_REACH || lateral > SLASH_HALF_WIDTH + e.getBbWidth() * 0.5) {
            continue;
         }
         if (!visible(level, player, e)) {
            continue;
         }
         e.hurtServer(level, level.damageSources().playerAttack(player), SLASH_DAMAGE);
         e.push(look.x * SLASH_LAUNCH, UPDRAFT_VICTIM, look.z * SLASH_LAUNCH);
         e.hurtMarked = true;
         Fx.shape(level, com.fortuneandfavors.net.FfVfx.CLASH, ParticleTypes.CRIT, e.position().add(0.0, e.getBbHeight() * 0.6, 0.0), look, 0.0, 0.0, GALE);
         hits++;
      }
      // The slash travels: three crescents laid down the line, widening, then a burst of wind
      // where it runs out.
      Vec3 base = player.position().add(0.0, 0.9, 0.0);
      for (int i = 0; i < 3; i++) {
         Fx.crescent(level, ParticleTypes.SWEEP_ATTACK, base.add(look.scale(i * SLASH_REACH / 3.0)), look, 3.0 + i * 1.2, i == 1 ? 0xFFFFFF : GALE);
      }
      Fx.gust(level, ParticleTypes.GUST, base, look, SLASH_REACH, GALE);
      Fx.pulseWave(level, ParticleTypes.GUST, player.position().add(look.scale(SLASH_REACH)), 2.5, 8, 0xFFFFFF);
      level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.BREEZE_SHOOT, SoundSource.PLAYERS, 1.2F, 1.15F);
      level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 1.0F, 0.7F);
      bar(player, hits == 0 ? "&7Wind Slash &8- &7it found nothing." : "&fWind Slash &8- &7caught &f" + hits + "&7.");
   }

   /** Every fourth hit lets the stored wind go: a cyclone bursts off the target. */
   public static void onSkybreakerHit(ServerPlayer hitter, LivingEntity victim) {
      ServerLevel level = levelOf(hitter);
      if (level == null) {
         return;
      }
      int charge = GALE_CHARGE.merge(hitter.getUUID(), 1, Integer::sum);
      if (charge < 4) {
         Fx.halo(level, ParticleTypes.GUST, hitter.position().add(0.0, 0.2, 0.0), 0.5 + charge * 0.25, 30, GALE);
         return;
      }
      GALE_CHARGE.remove(hitter.getUUID());
      Vec3 at = victim.position();
      for (LivingEntity e : enemiesNear(hitter, level, at, 4.0)) {
         e.invulnerableTime = 0;
         e.hurtServer(level, level.damageSources().playerAttack(hitter), DOWNFORCE_DAMAGE);
         Vec3 away = flatAway(at, e);
         e.push(away.x * 1.1, 0.55, away.z * 1.1);
         e.hurtMarked = true;
      }
      Fx.featherStorm(level, ParticleTypes.GUST, at.add(0.0, 1.0, 0.0), 3.5, 20, 0xFFFFFF);
      Fx.pulseWave(level, ParticleTypes.GUST, at, 4.5, 10, GALE);
      Fx.helix(level, ParticleTypes.CLOUD, at, 4.0, 20, GALE);
      level.playSound(null, at.x, at.y, at.z, SoundEvents.BREEZE_WIND_CHARGE_BURST, SoundSource.PLAYERS, 1.4F, 0.9F);
      bar(hitter, "&fCyclone &8- &7the wind you stored let go.");
   }

   public static float momentumBonus(double horizontalSpeed) {
      double t = Math.max(0.0, Math.min(1.0, horizontalSpeed / MOMENTUM_FULL_SPEED));
      return (float)(t * MOMENTUM_MAX_BONUS);
   }

   public static double horizontalSpeed(ServerPlayer player) {
      Vec3 v = player.getDeltaMovement();
      return Math.sqrt(v.x * v.x + v.z * v.z);
   }

   // ---------------------------------------------------------------- the sky: Gale Chakram

   /** Right-click the Chakram: throw it. Sneaking throws it on a curve. */
   public static String useChakram(ServerPlayer player, ItemStack held) {
      ServerLevel level = levelOf(player);
      if (level == null) {
         return null;
      }
      if (cooldown(player, "chakram", CHAKRAM_COOLDOWN_TICKS)) {
         return null;
      }
      // Sneak-throw: Sawstorm - it parks at the end of its flight and grinds before coming home.
      boolean saw = player.isShiftKeyDown();
      Chakram thrown = new Chakram(player.getUUID(), level, player.getEyePosition(), flatLook(player), false);
      thrown.hover = saw ? SAW_TICKS : 0;
      thrown.display = spawnChakramBody(level, thrown.at);
      CHAKRAMS.add(thrown);
      Fx.crescent(level, ParticleTypes.SWEEP_ATTACK, player.getEyePosition(), flatLook(player), 2.5, 0xFFFFFF);
      level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.BREEZE_WHIRL, SoundSource.PLAYERS, 1.1F, 1.3F);
      bar(player, saw ? "&fSawstorm &8- &7it holds the far end." : "&fChakram out &8- &7catch it on the way back.");
      return null;
   }

   public static double razorPush(int consecutiveHits) {
      int n = Math.max(1, consecutiveHits);
      return Math.min(RAZOR_MAX, CHAKRAM_PUSH + RAZOR_STEP * (n - 1));
   }

   // ---------------------------------------------------------------- the sky: Warden's Mantle

   /**
    * The Mantle's save. Called from the damage pipeline's fall branch, so it can only ever answer a
    * fall: a lethal blow from anything else is not this item's business.
    */
   public static Boolean onLethalFallDamage(Entity entity, DamageSource source, float amount) {
      if (entity == null || source == null || !(entity instanceof ServerPlayer player) || !source.is(DamageTypeTags.IS_FALL)) {
         return null;
      }
      if (!wearingMantle(player) || player.getHealth() - amount > 0.0F) {
         return null;
      }
      long now = ServerClock.clock(player.level());
      Long until = SECOND_WIND_UNTIL.get(player.getUUID());
      if (until != null && now < until) {
         return null;
      }
      SECOND_WIND_UNTIL.put(player.getUUID(), now + SECOND_WIND_COOLDOWN_TICKS);
      player.fallDistance = 0.0F;
      player.setDeltaMovement(player.getDeltaMovement().x * 0.4, WINDSTEP_POWER, player.getDeltaMovement().z * 0.4);
      player.hurtMarked = true;
      if (player.level() instanceof ServerLevel level) {
         Fx.vanillaOnly(() -> BossVfx.ring(level, player.position().add(0.0, 0.4, 0.0), 4.0, 30, ParticleTypes.GUST, 0.0));
         // Second Wind throws back everything close as it lifts you.
         Fx.featherStorm(level, ParticleTypes.GUST, player.position().add(0.0, 1.0, 0.0), 4.0, 24, 0xFFFFFF);
         Fx.pulseWave(level, ParticleTypes.GUST, player.position(), 5.0, 10, GALE);
         for (LivingEntity e : enemiesNear(player, level, player.position(), 5.0)) {
            Vec3 away = flatAway(player.position(), e);
            e.push(away.x * 1.2, 0.5, away.z * 1.2);
            e.hurtMarked = true;
         }
         Fx.vanillaOnly(() -> BossVfx.at(level, player.position(), 0.0, ParticleTypes.GUST_EMITTER_SMALL, 4, 1.2, 0.6, 1.2, 0.0));
         level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.BREEZE_WIND_CHARGE_BURST, SoundSource.PLAYERS, 1.6F, 1.0F);
      }
      bar(player, "&fSecond Wind &8- &7the sky gave it back.");
      return Boolean.FALSE;
   }

   /** Light as Air: what a fall costs while the Mantle is on. */
   public static float scaleFallDamage(ServerPlayer player, float amount) {
      return wearingMantle(player) ? amount * MANTLE_FALL_MULTIPLIER : amount;
   }

   public static boolean wearingMantle(ServerPlayer player) {
      return player != null && ModItems.isWardensMantle(player.getItemBySlot(EquipmentSlot.CHEST));
   }

   // ---------------------------------------------------------------- tick

   public static void tick(MinecraftServer server) {
      if (server == null) {
         return;
      }
      tickWaves();
      tickVortexes();
      tickChakrams(server);
      tickSlams(server);
      tickGrips(server);
      tickSurges(server);
      for (ServerPlayer p : server.getPlayerList().getPlayers()) {
         Safe.run("sea and sky mantle tick", () -> tickMantle(p));
      }
   }

   /** The Mantle's four passives, all read off the body wearing it. */
   private static void tickMantle(ServerPlayer player) {
      UUID id = player.getUUID();
      if (!player.isAlive()) {
         LAST_SNEAK.remove(id);
         WAS_SNEAKING.remove(id);
         SPRINT_TICKS.remove(id);
         TAILWIND_UNTIL.remove(id);
         return;
      }
      if (!wearingMantle(player)) {
         // The tailwind is re-asserted every tick it lasts, so taking the Mantle off simply stops
         // renewing it and it fades on its own. Nothing here touches an effect it did not grant -
         // a player's own Speed potion must not be stripped by a chestplate.
         WAS_SNEAKING.remove(id);
         SPRINT_TICKS.remove(id);
         TAILWIND_UNTIL.remove(id);
         return;
      }
      long now = ServerClock.clock(player.level());

      // Light as Air: falling slowly, so the Mantle's own lethality stays a fall's job.
      if (!player.onGround() && player.getDeltaMovement().y < -0.4) {
         player.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 12, 0, false, false));
      }

      boolean sneaking = player.isShiftKeyDown();
      boolean wasSneaking = Boolean.TRUE.equals(WAS_SNEAKING.get(id));
      if (sneaking && !wasSneaking) {
         long now2 = ServerClock.clock(player.level());
         Long last = LAST_SNEAK.get(id);
         if (last != null && now2 - last <= DOUBLE_TAP_TICKS) {
            windstep(player, now2);
            LAST_SNEAK.remove(id);
         } else {
            LAST_SNEAK.put(id, now2);
         }
      }
      WAS_SNEAKING.put(id, sneaking);

      // Updraft: sneak and jump together, on a short clock.
      if (sneaking && !player.onGround() && player.getDeltaMovement().y > 0.0) {
         Long until = COOLDOWN_UNTIL(COOLDOWNS.get(id), "mantle_updraft");
         if (until == null || now >= until) {
            setCooldown(id, "mantle_updraft", now + MANTLE_UPDRAFT_COOLDOWN_TICKS);
            player.setDeltaMovement(player.getDeltaMovement().x, MANTLE_UPDRAFT, player.getDeltaMovement().z);
            player.hurtMarked = true;
            if (player.level() instanceof ServerLevel level) {
               // A cloud step under your feet.
               Fx.pulseWave(level, ParticleTypes.CLOUD, player.position(), 2.0, 8, 0xFFFFFF);
               Fx.gust(level, ParticleTypes.GUST, player.position(), new Vec3(0.0, 1.0, 0.0), 3.0, GALE);
               Fx.vanillaOnly(() -> BossVfx.at(level, player.position(), 0.0, ParticleTypes.GUST, 14, 0.4, 0.5, 0.4, 0.1));
               level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.BREEZE_CHARGE, SoundSource.PLAYERS, 0.9F, 1.4F);
            }
         }
      }

      // Tailwind: banked sprinting, then a stretch of help.
      if (player.isSprinting()) {
         int banked = Math.min(TAILWIND_AFTER_TICKS, SPRINT_TICKS.getOrDefault(id, 0) + 1);
         SPRINT_TICKS.put(id, banked);
         if (banked >= TAILWIND_AFTER_TICKS && now >= TAILWIND_UNTIL.getOrDefault(id, 0L)) {
            TAILWIND_UNTIL.put(id, now + TAILWIND_TICKS);
            SPRINT_TICKS.put(id, 0);
            bar(player, "&fTailwind &8- &7it is on your side now.");
         }
      }
      if (now < TAILWIND_UNTIL.getOrDefault(id, 0L)) {
         player.addEffect(new MobEffectInstance(MobEffects.SPEED, 12, TAILWIND_AMPLIFIER, false, false));
         if (player.tickCount % 6 == 0 && player.level() instanceof ServerLevel level && horizontalSpeed(player) > 0.1) {
            Fx.gust(level, ParticleTypes.CLOUD, player.position().add(0.0, 0.2, 0.0), flatLook(player).scale(-1.0), 1.5, 0xFFFFFF);
         }
      }
   }

   /** Where the second Windstep from a mis-tap would go, and what it costs. */
   private static void windstep(ServerPlayer player, long now) {
      Long until = COOLDOWN_UNTIL(COOLDOWNS.get(player.getUUID()), "windstep");
      if (until != null && now < until) {
         long left = (until - now + 19L) / 20L;
         bar(player, "&7Windstep recovers in &f" + left + "s");
         return;
      }
      setCooldown(player.getUUID(), "windstep", now + WINDSTEP_COOLDOWN_TICKS);
      Vec3 look = flatLook(player);
      player.setDeltaMovement(look.x * WINDSTEP_POWER, Math.max(0.18, player.getDeltaMovement().y), look.z * WINDSTEP_POWER);
      player.hurtMarked = true;
      if (player.level() instanceof ServerLevel level) {
         // The step cuts: anything in the four blocks ahead is clipped by the wind you leave.
         for (LivingEntity e : enemiesNear(player, level, player.position().add(look.scale(2.0)), 2.2)) {
            e.hurtServer(level, level.damageSources().playerAttack(player), 4.0F);
            e.push(look.x * 0.5, 0.3, look.z * 0.5);
            e.hurtMarked = true;
         }
         Fx.gust(level, ParticleTypes.GUST, player.position().add(0.0, 0.8, 0.0), look, 5.0, GALE);
         Fx.crescent(level, ParticleTypes.SWEEP_ATTACK, player.position().add(0.0, 0.8, 0.0), look, 3.0, 0xFFFFFF);
         Fx.vanillaOnly(() -> BossVfx.beam(level, player.position(), player.position().add(look.scale(3.0)), 0.2, ParticleTypes.CLOUD));
         Fx.vanillaOnly(() -> BossVfx.at(level, player.position(), 0.0, ParticleTypes.SMALL_GUST, 16, 0.5, 0.2, 0.5, 0.08));
         level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.BREEZE_SLIDE, SoundSource.PLAYERS, 1.0F, 1.2F);
      }
   }

   // ---------------------------------------------------------------- scheduled effects

   private static void tickWaves() {
      if (WAVES.isEmpty()) {
         return;
      }
      for (Iterator<Wave> it = WAVES.iterator(); it.hasNext();) {
         Wave wave = it.next();
         ServerPlayer owner = wave.level.getServer().getPlayerList().getPlayer(wave.owner);
         wave.step++;
         if (wave.step < WAVE_TICKS_PER_STEP) {
            continue;
         }
         wave.step = 0;
         wave.at = wave.at.add(wave.forward.scale(1.0));
         wave.travelled += 1.0;
         drawWave(wave);
         for (LivingEntity e : wave.level.getEntitiesOfClass(
            LivingEntity.class, boxAround(wave.at, WAVE_HALF_WIDTH, 2.6), e -> e.isAlive() && !e.isSpectator()
         )) {
            if (e == owner || owner != null && e instanceof ServerPlayer other && ScarletGear.isAlly(owner, other)) {
               continue;
            }
            e.hurtServer(wave.level, owner != null ? wave.level.damageSources().playerAttack(owner) : wave.level.damageSources().magic(), WAVE_DAMAGE);
            Vec3 away = flatAway(wave.at, e);
            e.push(away.x * WAVE_LAUNCH + wave.forward.x * WAVE_LAUNCH, 0.55, away.z * WAVE_LAUNCH + wave.forward.z * WAVE_LAUNCH);
            e.hurtMarked = true;
         }
         if (wave.travelled >= WAVE_REACH) {
            it.remove();
         }
      }
   }

   private static void drawWave(Wave wave) {
      Vec3 left = wave.at.add(wave.side.scale(WAVE_HALF_WIDTH));
      Vec3 right = wave.at.subtract(wave.side.scale(WAVE_HALF_WIDTH));
      Fx.vanillaOnly(() -> BossVfx.beam(wave.level, left.add(0.0, 0.6, 0.0), right.add(0.0, 0.6, 0.0), 0.5, ParticleTypes.SPLASH));
      Fx.vanillaOnly(() -> BossVfx.at(wave.level, wave.at.add(0.0, 0.4, 0.0), 0.0, ParticleTypes.FALLING_WATER, 22, 1.2, 0.8, 1.2, 0.14));
      Fx.vanillaOnly(() -> BossVfx.at(wave.level, wave.at.add(0.0, 0.2, 0.0), 0.0, ParticleTypes.BUBBLE, 12, 1.4, 0.3, 1.4, 0.06));
   }

   private static void tickVortexes() {
      if (VORTEXES.isEmpty()) {
         return;
      }
      for (Iterator<Vortex> it = VORTEXES.iterator(); it.hasNext();) {
         Vortex vortex = it.next();
         if (--vortex.ticks <= 0) {
            Fx.vanillaOnly(() -> BossVfx.at(vortex.level, vortex.at, 0.0, ParticleTypes.GUST, 20, 1.4, 0.4, 1.4, 0.15));
            it.remove();
            continue;
         }
         ServerPlayer owner = vortex.level.getServer().getPlayerList().getPlayer(vortex.owner);
         int points = 18;
         double spin = vortex.ticks * 0.28;
         for (int i = 0; i < points; i++) {
            double a = spin + i * (Math.PI * 2.0 / points);
            double r = VORTEX_RADIUS * (0.35 + 0.65 * ((i * 7 % points) / (double)points));
            Fx.vanillaOnly(() -> BossVfx.at(
               vortex.level,
               vortex.at.add(Math.cos(a) * r, 0.35, Math.sin(a) * r),
               0.0,
               ParticleTypes.BUBBLE_POP,
               1,
               0.0,
               0.0,
               0.0,
               0.0
            ));
         }
         if (vortex.ticks % 4 != 0) {
            continue;
         }
         for (LivingEntity e : vortex.level.getEntitiesOfClass(
            LivingEntity.class, boxAround(vortex.at, VORTEX_RADIUS, 3.5), e -> e.isAlive() && !e.isSpectator()
         )) {
            if (e == owner || owner != null && e instanceof ServerPlayer other && ScarletGear.isAlly(owner, other)) {
               continue;
            }
            Vec3 pull = vortex.at.subtract(e.position());
            if (pull.lengthSqr() < 0.25) {
               continue;
            }
            Vec3 flat = new Vec3(pull.x, 0.0, pull.z).normalize();
            e.push(flat.x * VORTEX_PULL, 0.045, flat.z * VORTEX_PULL);
            e.hurtMarked = true;
         }
      }
   }

   private static void tickChakrams(MinecraftServer server) {
      if (CHAKRAMS.isEmpty()) {
         return;
      }
      for (Iterator<Chakram> it = CHAKRAMS.iterator(); it.hasNext();) {
         Chakram chakram = it.next();
         ServerPlayer owner = server.getPlayerList().getPlayer(chakram.owner);
         if (owner == null || !owner.isAlive() || owner.level() != chakram.level) {
            discardChakramBody(chakram);
            it.remove();
            continue;
         }
         if (chakram.returning) {
            Vec3 to = owner.position().add(0.0, 1.0, 0.0);
            Vec3 step = to.subtract(chakram.at);
            if (step.length() <= CHAKRAM_SPEED * 1.6) {
               chakram.turnsLeft--;
               if (chakram.turnsLeft > 0) {
                  // Cyclone Return: it passes the wielder and goes out again rather than stopping.
                  chakram.forward = chakram.forward.scale(-1.0);
                  chakram.returning = false;
                  chakram.travelled = 0.0;
                  chakram.hit.clear();
                  chakram.at = owner.getEyePosition().add(chakram.forward.scale(1.0));
               } else {
                  // Caught. An airborne catch is worth a shove upward - Air Catch.
                  if (!owner.onGround()) {
                     owner.setDeltaMovement(owner.getDeltaMovement().x, AIR_CATCH_LIFT, owner.getDeltaMovement().z);
                     owner.hurtMarked = true;
                     Fx.vanillaOnly(() -> BossVfx.at(chakram.level, owner.position(), 0.0, ParticleTypes.GUST, 14, 0.4, 0.4, 0.4, 0.1));
                  }
                  Fx.vanillaOnly(() -> BossVfx.at(chakram.level, chakram.at, 0.0, ParticleTypes.CLOUD, 10, 0.3, 0.3, 0.3, 0.02));
                  // A clean catch: the throw is ready again almost at once.
                  setCooldown(owner.getUUID(), "chakram", ServerClock.clock(owner.level()) + 6L);
                  Fx.flare(chakram.level, ParticleTypes.END_ROD, chakram.at, 0.8, 0xFFFFFF);
                  chakram.level.playSound(null, owner.getX(), owner.getY(), owner.getZ(), SoundEvents.TRIDENT_RETURN, SoundSource.PLAYERS, 1.0F, 1.3F);
                  discardChakramBody(chakram);
                  it.remove();
                  continue;
               }
            } else {
               chakram.at = chakram.at.add(step.normalize().scale(CHAKRAM_SPEED));
            }
         } else {
            // Windcurve: a sneak throw bends sideways as it goes out, so it can come home from
            // somewhere it was never thrown at.
            Vec3 side = new Vec3(-chakram.forward.z, 0.0, chakram.forward.x);
            double bend = owner.isShiftKeyDown() ? CURVE_SIDE : 0.0;
            chakram.at = chakram.at.add(chakram.forward.scale(CHAKRAM_SPEED)).add(side.scale(bend));
            if ((chakram.travelled * 10) % 2 < 1) {
               Fx.crescent(chakram.level, ParticleTypes.GUST, chakram.at, chakram.forward, 1.4, GALE);
            }
            chakram.travelled += CHAKRAM_SPEED;
            if (chakram.travelled >= CHAKRAM_REACH) {
               chakram.travelled = CHAKRAM_REACH;
               if (chakram.hover > 0) {
                  // Parked: it grinds in place, pulling bodies in and cutting again every 8 ticks.
                  chakram.at = chakram.at.subtract(chakram.forward.scale(CHAKRAM_SPEED)).subtract(side.scale(bend));
                  chakram.hover--;
                  if (chakram.hover % 8 == 0) {
                     chakram.hit.clear();
                     Fx.pulseWave(chakram.level, ParticleTypes.GUST, chakram.at.subtract(0.0, 0.8, 0.0), 3.5, 8, GALE);
                     chakram.level.playSound(null, chakram.at.x, chakram.at.y, chakram.at.z, SoundEvents.BREEZE_WHIRL, SoundSource.PLAYERS, 0.9F, 1.6F);
                  }
                  for (LivingEntity e : enemiesNear(owner, chakram.level, chakram.at, 3.5)) {
                     Vec3 in = chakram.at.subtract(e.position());
                     e.push(in.x * 0.04, 0.0, in.z * 0.04);
                     e.hurtMarked = true;
                  }
               } else {
                  chakram.returning = true;
                  chakram.hit.clear();
               }
            }
         }
         moveChakramBody(chakram);
         drawChakram(chakram);
         bladeChakram(owner, chakram);
      }
   }

   /**
    * The ring's body, put into the air where it was thrown.
    *
    * <p>A display entity rather than an item or a projectile because it must do nothing at all on
    * its own: it has no gravity to fight, no collision to push players with and no AI to steer its
    * flight. It is a shape that is told where to be, once a tick, by the code that already owns
    * where the ring is - which is the only way the picture and the damage can never disagree.
    */
   private static BlockDisplay spawnChakramBody(ServerLevel level, Vec3 at) {
      BlockDisplay body = (BlockDisplay)EntityTypes.BLOCK_DISPLAY.create(level, EntitySpawnReason.COMMAND);
      if (body == null) {
         return null;
      }
      body.setPos(at.x, at.y, at.z);
      body.setBlockState(CHAKRAM_BODY);
      body.setNoGravity(true);
      body.setInvulnerable(true);
      body.setTransformationInterpolationDuration(2);
      body.setTransformationInterpolationDelay(0);
      body.setTransformation(chakramPose(0.0F));
      level.addFreshEntity(body);
      return body;
   }

   /** One tick of the ring's body: where it is now, and how far round it has turned. */
   private static void moveChakramBody(Chakram chakram) {
      BlockDisplay body = chakram.display;
      if (body == null) {
         return;
      }
      if (body.isRemoved() || body.level() != chakram.level) {
         // Something else took it - a chunk unload, a world edit, an admin. The flight carries on
         // (the damage never depended on the picture) and the body is simply not re-created.
         chakram.display = null;
         return;
      }
      chakram.spin = (chakram.spin + CHAKRAM_SPIN) % 360.0F;
      body.setPos(chakram.at.x, chakram.at.y, chakram.at.z);
      body.setTransformation(chakramPose(chakram.spin));
   }

   /** The ring's shape: a thin disc, tipped onto its edge and turned to the current spin. */
   private static Transformation chakramPose(float spinDegrees) {
      return new Transformation(
         new Vector3f(0.0F, 0.0F, 0.0F),
         new Quaternionf().rotateY((float)Math.toRadians(spinDegrees)).rotateZ((float)Math.toRadians(45.0)),
         new Vector3f(0.9F, 0.22F, 0.9F),
         new Quaternionf()
      );
   }

   /** Takes the ring's body out of the world. Safe to call on a ring that never got one. */
   private static void discardChakramBody(Chakram chakram) {
      if (chakram.display != null && !chakram.display.isRemoved()) {
         chakram.display.discard();
      }
      chakram.display = null;
   }

   private static void drawChakram(Chakram chakram) {
      // Modded clients: a spinning white halo with a gale-coloured ring of motes trailing it.
      Fx.halo(chakram.level, ParticleTypes.CLOUD, chakram.at, 0.75, 2, chakram.returning ? 0xFFFFFF : GALE);
      int points = 14;
      for (int i = 0; i < points; i++) {
         double a = i * (Math.PI * 2.0 / points);
         Fx.vanillaOnly(() -> BossVfx.at(
            chakram.level,
            chakram.at.add(Math.cos(a) * 0.7, Math.sin(a) * 0.7, 0.0),
            0.0,
            ParticleTypes.CLOUD,
            1,
            0.0,
            0.0,
            0.0,
            0.0
         ));
      }
      Fx.vanillaOnly(() -> BossVfx.at(chakram.level, chakram.at, 0.0, ParticleTypes.SMALL_GUST, 3, 0.2, 0.2, 0.2, 0.02));
   }

   private static void bladeChakram(ServerPlayer owner, Chakram chakram) {
      for (LivingEntity e : chakram.level.getEntitiesOfClass(
         LivingEntity.class, boxAround(chakram.at, 1.25, 1.6), e -> e.isAlive() && !e.isSpectator()
      )) {
         if (e == owner || e instanceof ServerPlayer other && ScarletGear.isAlly(owner, other)) {
            continue;
         }
         if (!chakram.hit.add(e.getUUID())) {
            continue;
         }
         int streak = RAZOR.computeIfAbsent(owner.getUUID(), k -> new HashMap<>()).merge(e.getUUID(), 1, Integer::sum);
         double push = razorPush(streak);
         e.hurtServer(chakram.level, chakram.level.damageSources().playerAttack(owner), CHAKRAM_DAMAGE);
         Vec3 away = flatAway(chakram.at, e);
         e.push(away.x * push, 0.3, away.z * push);
         e.hurtMarked = true;
         Fx.vanillaOnly(() -> BossVfx.at(chakram.level, chakram.at, 0.0, ParticleTypes.GUST, 10, 0.4, 0.4, 0.4, 0.1));
         Fx.shape(chakram.level, com.fortuneandfavors.net.FfVfx.CLASH, ParticleTypes.CRIT, e.position().add(0.0, e.getBbHeight() * 0.6, 0.0), chakram.forward, 0.0, 0.0, GALE);
         chakram.level.playSound(null, e.getX(), e.getY(), e.getZ(), SoundEvents.BREEZE_DEFLECT, SoundSource.PLAYERS, 1.0F, 1.2F);
         if (streak >= 2) {
            bar(owner, "&fRazor Current &8x&f" + streak + " &8- &7it is going further each time.");
         }
      }
   }

   private static void tickSlams(MinecraftServer server) {
      for (Iterator<SkySlam> it = SLAMS.iterator(); it.hasNext();) {
         SkySlam slam = it.next();
         ServerPlayer owner = server.getPlayerList().getPlayer(slam.owner);
         if (owner == null || !owner.isAlive() || owner.level() != slam.level) {
            it.remove();
            continue;
         }
         owner.fallDistance = 0.0F;
         if (owner.getDeltaMovement().y < 0.0) {
            owner.setDeltaMovement(owner.getDeltaMovement().x, owner.getDeltaMovement().y * 0.3, owner.getDeltaMovement().z);
            owner.hurtMarked = true;
         }
         if (--slam.fuse <= 0) {
            it.remove();
            continue;
         }
         if (slam.fuse % 15 != 0) {
            continue;
         }
         Vec3 eye = owner.position().add(0.0, 1.0, 0.0);
         List<LivingEntity> near = new java.util.ArrayList<>(enemiesNear(owner, slam.level, owner.position(), BREAK_RADIUS + 2.5));
         near.sort(java.util.Comparator.comparingDouble(e -> e.distanceToSqr(owner)));
         int cuts = 0;
         for (LivingEntity e : near) {
            if (cuts >= 3) {
               break;
            }
            Vec3 at = e.position().add(0.0, e.getBbHeight() * 0.5, 0.0);
            e.invulnerableTime = 0;
            e.hurtServer(slam.level, slam.level.damageSources().playerAttack(owner), BREAK_DAMAGE / 3.0F);
            Vec3 in = owner.position().subtract(e.position());
            Vec3 flat = new Vec3(in.x, 0.0, in.z).normalize();
            e.push(flat.x * 0.4, 0.2, flat.z * 0.4);
            e.hurtMarked = true;
            Fx.lightning(slam.level, ParticleTypes.ELECTRIC_SPARK, eye, at, 0xFFFFFF);
            Fx.crescent(slam.level, ParticleTypes.SWEEP_ATTACK, at, at.subtract(eye), 2.0, GALE);
            cuts++;
         }
         Fx.pulseWave(slam.level, ParticleTypes.GUST, owner.position(), BREAK_RADIUS, 10, GALE);
         slam.level.playSound(null, eye.x, eye.y, eye.z, SoundEvents.BREEZE_SHOOT, SoundSource.PLAYERS, 1.0F, 1.4F);
      }
   }


   private static String openVortex(ServerPlayer player, ServerLevel level) {
      if (cooldown(player, "vortex", VORTEX_COOLDOWN_TICKS)) {
         return null;
      }
      Vec3 at = player.position();
      VORTEXES.add(new Vortex(player.getUUID(), level, at, VORTEX_TICKS));
      Fx.vortex(level, ParticleTypes.BUBBLE_POP, at, VORTEX_RADIUS, VORTEX_TICKS, ABYSS);
      Fx.runeCircle(level, ParticleTypes.BUBBLE_POP, at.add(0.0, 0.05, 0.0), VORTEX_RADIUS, VORTEX_TICKS, TIDE);
      Fx.vanillaOnly(() -> BossVfx.ring(level, at, VORTEX_RADIUS, 30, ParticleTypes.BUBBLE, 0.0));
      level.playSound(null, at.x, at.y, at.z, SoundEvents.CONDUIT_ACTIVATE, SoundSource.PLAYERS, 1.4F, 0.7F);
      bar(player, "&3Undertow &8- &7everything nearby is leaning in.");
      return null;
   }

   // ---------------------------------------------------------------- helpers

   private static ServerLevel levelOf(ServerPlayer player) {
      return player != null && player.level() instanceof ServerLevel level ? level : null;
   }

   /** The nearest living body in front of the player, out to {@code reach}. */
   /** The living body under the crosshair, stopped by the first solid block; null when none. */
   private static LivingEntity rayTarget(ServerPlayer player, ServerLevel level, double reach) {
      Vec3 eye = player.getEyePosition();
      Vec3 end = eye.add(player.getLookAngle().scale(reach));
      HitResult block = level.clip(new net.minecraft.world.level.ClipContext(eye, end,
         net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, player));
      if (block.getType() != HitResult.Type.MISS) {
         end = block.getLocation();
      }
      net.minecraft.world.phys.EntityHitResult hit = net.minecraft.world.entity.projectile.ProjectileUtil.getEntityHitResult(
         level, player, eye, end, player.getBoundingBox().expandTowards(end.subtract(eye)).inflate(1.0),
         e -> e instanceof LivingEntity l && l.isAlive() && !l.isSpectator() && !(e instanceof ServerPlayer o && ScarletGear.isAlly(player, o)),
         0.4F
      );
      return hit != null && hit.getEntity() instanceof LivingEntity l ? l : null;
   }

   private static LivingEntity pickTarget(ServerPlayer player, ServerLevel level, double reach) {
      Vec3 look = flatLook(player);
      Vec3 eye = player.getEyePosition();
      LivingEntity best = null;
      double bestScore = Double.MAX_VALUE;
      for (LivingEntity e : enemiesNear(player, level, player.position(), reach)) {
         Vec3 offset = e.position().add(0.0, e.getBbHeight() * 0.5, 0.0).subtract(eye);
         double forward = offset.x * look.x + offset.z * look.z;
         if (forward < -1.0) {
            continue;
         }
         // The Grasp and the Chain reach for what the bearer can see, not through the wall beside them.
         if (!visible(level, player, e)) {
            continue;
         }
         double score = offset.length() - forward * 0.5;
         if (score < bestScore) {
            bestScore = score;
            best = e;
         }
      }
      return best;
   }

   /** Living bodies near a point that are not the caster and not their sworn allies. */
   private static List<LivingEntity> enemiesNear(ServerPlayer caster, ServerLevel level, Vec3 center, double radius) {
      return level.getEntitiesOfClass(
         LivingEntity.class,
         boxAround(center, radius, Math.max(3.0, radius)),
         e -> e != caster
            && e.isAlive()
            && !e.isSpectator()
            && !(e instanceof ServerPlayer other && ScarletGear.isAlly(caster, other))
      );
   }

   /** A clear line from the player's eyes to the middle of the body. */
   private static boolean visible(ServerLevel level, ServerPlayer player, LivingEntity e) {
      Vec3 centre = e.position().add(0.0, e.getBbHeight() * 0.5, 0.0);
      return level.clip(new net.minecraft.world.level.ClipContext(player.getEyePosition(), centre,
         net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, player
      )).getType() == net.minecraft.world.phys.HitResult.Type.MISS;
   }

   /**
    * How much of a pull a body takes: less for anything that resists knockback, and a quarter at
    * most for a boss. The Grasp and the Chain used to haul a boss exactly as far as a zombie, which
    * made them a way to drag a fight out of its arena.
    */
   private static double heft(LivingEntity e) {
      double weight = 1.0;
      try {
         weight = 1.0 - Math.max(0.0, Math.min(1.0, e.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.KNOCKBACK_RESISTANCE)));
      } catch (Throwable ignored) {
      }
      if (BossManager.isMarkedBoss(e)) {
         weight = Math.min(weight, 0.25);
      }
      return Math.max(0.15, weight);
   }

   private static AABB boxAround(Vec3 center, double horizontal, double vertical) {
      return new AABB(
         center.x - horizontal, center.y - vertical, center.z - horizontal,
         center.x + horizontal, center.y + vertical, center.z + horizontal
      );
   }

   private static Vec3 flatLook(ServerPlayer player) {
      Vec3 look = player.getLookAngle();
      Vec3 flat = new Vec3(look.x, 0.0, look.z);
      return flat.lengthSqr() < 1.0E-6 ? new Vec3(0.0, 0.0, 1.0) : flat.normalize();
   }

   private static Vec3 flatAway(ServerPlayer from, LivingEntity victim) {
      Vec3 away = victim.position().subtract(from.position());
      Vec3 flat = new Vec3(away.x, 0.0, away.z);
      return flat.lengthSqr() < 1.0E-6 ? flatLook(from) : flat.normalize();
   }

   private static Vec3 flatAway(Vec3 from, LivingEntity victim) {
      Vec3 away = victim.position().subtract(from);
      Vec3 flat = new Vec3(away.x, 0.0, away.z);
      return flat.lengthSqr() < 1.0E-6 ? new Vec3(0.0, 0.0, 1.0) : flat.normalize();
   }

   private static void bar(ServerPlayer player, String text) {
      player.sendOverlayMessage(Component.literal(Chat.colorize(text)));
   }

   private static Long COOLDOWN_UNTIL(Map<String, Long> map, String key) {
      return map == null ? null : map.get(key);
   }

   /** True (and says so) while {@code key} is still recovering for this player. */
   private static boolean cooldown(ServerPlayer player, String key, long ticks) {
      long now = ServerClock.clock(player.level());
      Long until = COOLDOWN_UNTIL(COOLDOWNS.get(player.getUUID()), key);
      if (until != null && now < until) {
         bar(player, "&7Recovering &8- &f" + ((until - now + 19L) / 20L) + "s");
         return true;
      }
      setCooldown(player.getUUID(), key, now + ticks);
      return false;
   }

   private static void setCooldown(UUID id, String key, long until) {
      COOLDOWNS.computeIfAbsent(id, k -> new HashMap<>()).put(key, until);
   }

   // ---------------------------------------------------------------- teardown and test seams

   /** Forgets a player at disconnect: marks on bodies outlive the bearer, and would be wrong. */
   public static void onPlayerDisconnect(UUID id) {
      COOLDOWNS.remove(id);
      MARKS.remove(id);
      DEEP_STRIKE.remove(id);
      RAZOR.remove(id);
      LAST_SNEAK.remove(id);
      WAS_SNEAKING.remove(id);
      SPRINT_TICKS.remove(id);
      TAILWIND_UNTIL.remove(id);
      SECOND_WIND_UNTIL.remove(id);
      for (Iterator<Chakram> it = CHAKRAMS.iterator(); it.hasNext();) {
         Chakram chakram = it.next();
         if (chakram.owner.equals(id)) {
            discardChakramBody(chakram);
            it.remove();
         }
      }
   }

   /** Drops every in-flight effect and cooldown. Called when the server stops. */
   public static void clear() {
      COOLDOWNS.clear();
      MARKS.clear();
      DEEP_STRIKE.clear();
      RAZOR.clear();
      LAST_SNEAK.clear();
      WAS_SNEAKING.clear();
      SPRINT_TICKS.clear();
      TAILWIND_UNTIL.clear();
      SECOND_WIND_UNTIL.clear();
      WAVES.clear();
      VORTEXES.clear();
      CHAKRAMS.clear();
      SLAMS.clear();
      GRIPS.clear();
      SURGES.clear();
      PRESSURE.clear();
      GALE_CHARGE.clear();
   }

   /** The number of effects still in flight, for the self-test. */
   public static int inFlightCount() {
      return WAVES.size() + VORTEXES.size() + CHAKRAMS.size() + SLAMS.size();
   }

   /** Whether a body is currently marked by this bearer, for the self-test. */
   public static boolean markedForTest(ServerPlayer bearer, LivingEntity victim) {
      return isMarked(bearer, victim);
   }

   /** Whether this bearer is holding a Deep Strike, for the self-test. */
   public static boolean deepStrikeForTest(ServerPlayer bearer) {
      Long until = DEEP_STRIKE.get(bearer.getUUID());
      return until != null && until >= ServerClock.clock(bearer.level());
   }

   /**
    * Whether every ability this file offers has a real number behind it.
    *
    * <p>The self-test uses this the way it uses the other gear seams: as a single question whose
    * answer cannot be true by accident. Each entry is a mechanic that would be silently inert at
    * zero - a pull with no power is a message, a wave with no reach never moves, a momentum passive
    * that caps at zero is a decoration.
    */
   public static boolean everyAbilityHasPower() {
      return GRASP_REACH > 0.0
         && GRASP_PULL > 0.0
         && CRUSH_DAMAGE > 0.0F
         && WAVE_REACH > 0.0
         && WAVE_DAMAGE > 0.0F
         && CHAIN_REACH > 0.0
         && CHAIN_PULL > 0.0
         && DEEP_STRIKE_DAMAGE > 0.0F
         && SLASH_DAMAGE > 0.0F
         && SLASH_REACH > 0.0
         && MOMENTUM_MAX_BONUS > 0.0F
         && DOWNFORCE_DAMAGE > 0.0F
         && BREAK_RADIUS > 0.0
         && BREAK_DAMAGE > 0.0F
         && CHAKRAM_SPEED > 0.0
         && CHAKRAM_DAMAGE > 0.0F
         && RAZOR_MAX > CHAKRAM_PUSH
         && WINDSTEP_POWER > 0.0
         && MANTLE_UPDRAFT > 0.0
         && VORTEX_RADIUS > 0.0
         && VORTEX_TICKS > 0
         && SECOND_WIND_COOLDOWN_TICKS > 0L;
   }

   /** Reads the numbers the self-test pins, so a knob turned to zero is visible. */
   public static double[] tuningForTest() {
      return new double[]{
         GRASP_REACH, GRASP_PULL, CRUSH_DAMAGE, WAVE_REACH, WAVE_DAMAGE, WAVE_HALF_WIDTH,
         CHAIN_REACH, CHAIN_PULL, DEEP_STRIKE_DAMAGE, MARK_KNOCKBACK_MULTIPLIER,
         SLASH_REACH, SLASH_HALF_WIDTH, SLASH_DAMAGE, MOMENTUM_FULL_SPEED, MOMENTUM_MAX_BONUS,
         DOWNFORCE_DAMAGE, BREAK_RADIUS, BREAK_DAMAGE, CHAKRAM_SPEED, CHAKRAM_REACH, CHAKRAM_DAMAGE,
         CHAKRAM_PUSH, RAZOR_STEP, RAZOR_MAX, CURVE_SIDE, AIR_CATCH_LIFT, WINDSTEP_POWER,
         MANTLE_UPDRAFT, TAILWIND_AFTER_TICKS, SECOND_WIND_COOLDOWN_TICKS, VORTEX_RADIUS, VORTEX_PULL,
         VORTEX_TICKS
      };
   }
}
