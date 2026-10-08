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

   /** How far the claw can reach to haul something in. */
   private static final double GRASP_REACH = 9.0;
   private static final double GRASP_PULL = 1.25;
   /** Inside this range Grasp becomes Crush - there is no point pulling a body that is already here. */
   private static final double CRUSH_RANGE = 3.4;
   private static final float CRUSH_DAMAGE = 13.0F;
   private static final double CRUSH_SLAM = -1.15;
   private static final long GRASP_COOLDOWN_TICKS = 30L;
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
         return openVortex(player, level);
      }
      LivingEntity target = pickTarget(player, level, GRASP_REACH);
      if (target == null) {
         bar(player, "&7Nothing in reach to grasp &8- &7get closer.");
         return null;
      }
      if (cooldown(player, "grasp", GRASP_COOLDOWN_TICKS)) {
         return null;
      }
      double distance = Math.sqrt(player.distanceToSqr(target));
      if (distance <= CRUSH_RANGE) {
         crush(level, player, target);
      } else {
         grasp(level, player, target);
      }
      return null;
   }

   /** The haul: a body pulled off its feet and toward the claw. */
   private static void grasp(ServerLevel level, ServerPlayer player, LivingEntity target) {
      Vec3 toward = player.position().subtract(target.position()).normalize();
      target.setDeltaMovement(toward.x * GRASP_PULL, 0.34, toward.z * GRASP_PULL);
      target.hurtMarked = true;
      target.hurtServer(level, level.damageSources().playerAttack(player), 4.5F);
      BossVfx.beam(level, target.position().add(0.0, 1.0, 0.0), player.getEyePosition(), 0.16, ParticleTypes.DRIPPING_WATER);
      BossVfx.ring(level, target.position().add(0.0, 0.35, 0.0), 1.5, 10, ParticleTypes.BUBBLE_POP, 0.0);
      level.playSound(null, target.getX(), target.getY(), target.getZ(), SoundEvents.TRIDENT_THROW, SoundSource.PLAYERS, 0.9F, 0.7F);
      bar(player, "&3Grasp &8- &7hauled in &f" + target.getName().getString() + "&7.");
   }

   /** The Crush: a body already inside the claw's reach is driven into the floor instead. */
   private static void crush(ServerLevel level, ServerPlayer player, LivingEntity target) {
      target.hurtServer(level, level.damageSources().playerAttack(player), CRUSH_DAMAGE);
      target.setDeltaMovement(target.getDeltaMovement().x * 0.2, CRUSH_SLAM, target.getDeltaMovement().z * 0.2);
      target.hurtMarked = true;
      BossVfx.ring(level, target.position().add(0.0, 0.15, 0.0), 2.6, 22, ParticleTypes.SPLASH, 0.0);
      BossVfx.at(level, target.position().add(0.0, 0.2, 0.0), 0.0, ParticleTypes.FALLING_WATER, 26, 0.9, 0.3, 0.9, 0.12);
      level.playSound(null, target.getX(), target.getY(), target.getZ(), SoundEvents.MACE_SMASH_GROUND_HEAVY, SoundSource.PLAYERS, 1.2F, 0.85F);
      bar(player, "&3Crush &8- &f" + target.getName().getString() + "&7 met the floor.");
   }

   /** A landed Grasp blow: the passive, and the jump-strike slam. */
   public static void onGraspHit(ServerPlayer hitter, LivingEntity victim) {
      ServerLevel level = levelOf(hitter);
      if (level == null) {
         return;
      }
      if (!hitter.onGround() && hitter.getDeltaMovement().y < 0.0) {
         // Depth Breaker: a blow landed out of the air comes down with a water shockwave.
         victim.hurtServer(level, level.damageSources().playerAttack(hitter), 8.0F);
         victim.setDeltaMovement(0.0, CRUSH_SLAM, 0.0);
         victim.hurtMarked = true;
         BossVfx.ring(level, victim.position().add(0.0, 0.2, 0.0), 4.4, 30, ParticleTypes.SPLASH, 0.0);
         BossVfx.at(level, victim.position().add(0.0, 0.3, 0.0), 0.0, ParticleTypes.FALLING_WATER, 34, 1.6, 0.4, 1.6, 0.18);
         level.playSound(null, victim.getX(), victim.getY(), victim.getZ(), SoundEvents.GENERIC_SPLASH, SoundSource.PLAYERS, 1.3F, 0.7F);
         bar(hitter, "&3Depth Breaker &8- &7the drop did the rest.");
         return;
      }
      // The passive drags the target *in*, and drags harder the closer it is to death.
      //
      // It threw them away instead, and that was the one thing the weapon could not afford: the
      // Grasp is a claw whose whole kit is closing distance - the haul, the crush, the vortex at
      // your feet - so a passive that paid the player in distance fought every other move it had.
      // A body the claw has been beating on is a body the sea is already pulling under.
      double drag = graspDrag(victim.getHealth(), victim.getMaxHealth());
      Vec3 toward = hitter.position().subtract(victim.position());
      if (toward.lengthSqr() < 1.0E-4) {
         toward = hitter.getLookAngle().scale(-1.0);
      }
      Vec3 flat = new Vec3(toward.x, 0.0, toward.z).normalize();
      victim.push(flat.x * drag, 0.2, flat.z * drag);
      victim.hurtMarked = true;
   }

   /**
    * The Grasp's passive, as a pure function of how hurt the target is.
    *
    * <p>Kept public and side-effect free so the self-test can assert the shape rather than a number
    * someone read off one fight: full health is the floor, empty is the ceiling, and it is monotone
    * between them. The magnitude is a pull toward the bearer - see {@link #onGraspHit}.
    */
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
      if (cooldown(player, "wave", WAVE_COOLDOWN_TICKS)) {
         return null;
      }
      Vec3 look = flatLook(player);
      Vec3 from = player.position().add(0.0, 0.2, 0.0).add(look.scale(1.2));
      WAVES.add(new Wave(player.getUUID(), level, from, look));
      level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.TRIDENT_RIPTIDE_1, SoundSource.PLAYERS, 1.3F, 0.8F);
      bar(player, "&3The tide answers &8- &7it is going that way.");
      return null;
   }

   // ---------------------------------------------------------------- the sea: Abyssal Chain

   /** Right-click the Chain: pull a body in, pull yourself to a wall, or open a vortex. */
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
      LivingEntity target = pickTarget(player, level, CHAIN_REACH);
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
      target.setDeltaMovement(toward.x * CHAIN_PULL, 0.22, toward.z * CHAIN_PULL);
      target.hurtMarked = true;
      // It pays damage for the pull. A weapon that only decides *where* a body is cannot win a
      // fight, and the Chain has the reach, the cooldown and the hook to be a weapon rather than a
      // lever - the drag is what the damage is for.
      target.hurtServer(level, level.damageSources().playerAttack(player), CHAIN_PULL_DAMAGE);
      markTarget(player, target);
      BossVfx.beam(level, target.position().add(0.0, 1.0, 0.0), player.getEyePosition(), 0.1, ParticleTypes.SCULK_SOUL);
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
      DEEP_STRIKE.put(player.getUUID(), player.level().getGameTime() + DEEP_STRIKE_WINDOW_TICKS);
      BossVfx.beam(level, player.getEyePosition(), to, 0.1, ParticleTypes.SCULK_SOUL);
      level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.CHAIN_FALL, SoundSource.PLAYERS, 1.0F, 1.3F);
      bar(player, "&3Abyssal Hook &8- &7the chain brought you instead.");
   }

   /** A landed Chain blow: a Deep Strike out of a hook, and the mark it leaves behind. */
   public static void onChainHit(ServerPlayer hitter, LivingEntity victim) {
      ServerLevel level = levelOf(hitter);
      if (level == null) {
         return;
      }
      long now = hitter.level().getGameTime();
      Long until = DEEP_STRIKE.get(hitter.getUUID());
      if (until != null && now <= until) {
         DEEP_STRIKE.remove(hitter.getUUID());
         victim.hurtServer(level, level.damageSources().playerAttack(hitter), DEEP_STRIKE_DAMAGE);
         BossVfx.at(level, victim.position().add(0.0, 1.0, 0.0), 0.0, ParticleTypes.SCULK_SOUL, 20, 0.5, 0.6, 0.5, 0.08);
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
      long until = bearer.level().getGameTime() + MARK_TICKS;
      Map<UUID, Long> marks = MARKS.computeIfAbsent(bearer.getUUID(), k -> new HashMap<>());
      Long previous = marks.put(victim.getUUID(), until);
      BossVfx.at(
         victim.level() instanceof ServerLevel sl ? sl : null,
         victim.position().add(0.0, victim.getBbHeight() + 0.35, 0.0),
         0.0,
         ParticleTypes.SCULK_SOUL,
         8,
         0.2,
         0.1,
         0.2,
         0.02
      );
      return previous != null && previous > bearer.level().getGameTime();
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
      return until != null && until >= bearer.level().getGameTime();
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
         player.setDeltaMovement(player.getDeltaMovement().x, BREAK_LEAP, player.getDeltaMovement().z);
         player.hurtMarked = true;
         player.fallDistance = 0.0F;
         SLAMS.add(new SkySlam(player.getUUID(), level, BREAK_FALL_TICKS));
         BossVfx.at(level, player.position(), 0.0, ParticleTypes.GUST, 18, 0.5, 0.3, 0.5, 0.12);
         level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.BREEZE_JUMP, SoundSource.PLAYERS, 1.3F, 0.9F);
         bar(player, "&fBreak the Sky &8- &7come down on all of it.");
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
      Vec3 eye = player.getEyePosition();
      int hits = 0;
      for (LivingEntity e : enemiesNear(player, level, player.position().add(0.0, 1.0, 0.0), SLASH_REACH)) {
         Vec3 offset = e.position().subtract(player.position());
         double forward = offset.x * look.x + offset.z * look.z;
         double lateral = Math.abs(offset.x * side.x + offset.z * side.z);
         if (forward < 0.0 || forward > SLASH_REACH || lateral > SLASH_HALF_WIDTH + e.getBbWidth() * 0.5) {
            continue;
         }
         e.hurtServer(level, level.damageSources().playerAttack(player), SLASH_DAMAGE);
         e.push(look.x * SLASH_LAUNCH, UPDRAFT_VICTIM, look.z * SLASH_LAUNCH);
         e.hurtMarked = true;
         hits++;
      }
      int points = 24;
      for (int i = 0; i < points; i++) {
         double d = (i / (double)points) * SLASH_REACH;
         Vec3 p = eye.add(look.scale(d));
         BossVfx.at(level, p, 0.0, ParticleTypes.GUST, 3, 0.1, 0.3, 0.1, 0.0);
         BossVfx.at(level, p.add(side.scale(SLASH_HALF_WIDTH)), 0.0, ParticleTypes.SMALL_GUST, 1, 0.0, 0.0, 0.0, 0.0);
         BossVfx.at(level, p.subtract(side.scale(SLASH_HALF_WIDTH)), 0.0, ParticleTypes.SMALL_GUST, 1, 0.0, 0.0, 0.0, 0.0);
      }
      level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.BREEZE_SHOOT, SoundSource.PLAYERS, 1.2F, 1.15F);
      bar(player, hits == 0 ? "&7Wind Slash &8- &7it found nothing." : "&fWind Slash &8- &7caught &f" + hits + "&7.");
   }

   /** A landed Skybreaker blow: Momentum, Updraft, and Downforce on an airborne body. */
   public static void onSkybreakerHit(ServerPlayer hitter, LivingEntity victim) {
      ServerLevel level = levelOf(hitter);
      if (level == null) {
         return;
      }
      float bonus = momentumBonus(horizontalSpeed(hitter));
      if (bonus > 0.0F) {
         victim.hurtServer(level, level.damageSources().playerAttack(hitter), bonus);
      }
      if (!victim.onGround()) {
         // Downforce: a body already in the air is driven into the ground instead of lifted.
         victim.hurtServer(level, level.damageSources().playerAttack(hitter), DOWNFORCE_DAMAGE);
         victim.setDeltaMovement(victim.getDeltaMovement().x * 0.3, DOWNFORCE_SLAM, victim.getDeltaMovement().z * 0.3);
         victim.hurtMarked = true;
         BossVfx.ring(level, victim.position().add(0.0, 0.3, 0.0), 3.2, 22, ParticleTypes.GUST, 0.0);
         level.playSound(null, victim.getX(), victim.getY(), victim.getZ(), SoundEvents.BREEZE_LAND, SoundSource.PLAYERS, 1.2F, 0.8F);
         bar(hitter, "&fDownforce &8- &7back to the floor.");
         return;
      }
      victim.push(0.0, UPDRAFT_VICTIM, 0.0);
      hitter.push(0.0, UPDRAFT_SELF, 0.0);
      victim.hurtMarked = true;
      hitter.hurtMarked = true;
   }

   /**
    * Momentum, as a pure function of how fast the wielder was already moving.
    *
    * <p>Horizontal speed only: falling is not momentum, and a weapon that paid for its own gravity
    * would pay best for standing on a cliff.
    */
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
      boolean cyclone = RANDOM.nextFloat() < CYCLONE_CHANCE;
      Chakram thrown = new Chakram(player.getUUID(), level, player.getEyePosition(), flatLook(player), cyclone);
      thrown.display = spawnChakramBody(level, thrown.at);
      CHAKRAMS.add(thrown);
      level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.BREEZE_WHIRL, SoundSource.PLAYERS, 1.1F, 1.3F);
      if (cyclone) {
         bar(player, "&fCyclone Return &8- &7it is not coming straight home.");
      } else {
         bar(player, player.isShiftKeyDown() ? "&fWindcurve &8- &7thrown wide." : "&fChakram out &8- &7it comes back.");
      }
      return null;
   }

   /**
    * Razor Current: the knockback of a hit, by how many it is in a row on the same body.
    *
    * <p>Pure so the ramp can be asserted instead of described - it rises with every consecutive hit
    * and it has a ceiling, which is the difference between a combo and a launch pad.
    */
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
      long now = player.level().getGameTime();
      Long until = SECOND_WIND_UNTIL.get(player.getUUID());
      if (until != null && now < until) {
         return null;
      }
      SECOND_WIND_UNTIL.put(player.getUUID(), now + SECOND_WIND_COOLDOWN_TICKS);
      player.fallDistance = 0.0F;
      player.setDeltaMovement(player.getDeltaMovement().x * 0.4, WINDSTEP_POWER, player.getDeltaMovement().z * 0.4);
      player.hurtMarked = true;
      if (player.level() instanceof ServerLevel level) {
         BossVfx.ring(level, player.position().add(0.0, 0.4, 0.0), 4.0, 30, ParticleTypes.GUST, 0.0);
         BossVfx.at(level, player.position(), 0.0, ParticleTypes.GUST_EMITTER_SMALL, 4, 1.2, 0.6, 1.2, 0.0);
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
      long now = player.level().getGameTime();

      // Light as Air: falling slowly, so the Mantle's own lethality stays a fall's job.
      if (!player.onGround() && player.getDeltaMovement().y < -0.4) {
         player.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 12, 0, false, false));
      }

      boolean sneaking = player.isShiftKeyDown();
      boolean wasSneaking = Boolean.TRUE.equals(WAS_SNEAKING.get(id));
      if (sneaking && !wasSneaking) {
         long now2 = player.level().getGameTime();
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
               BossVfx.at(level, player.position(), 0.0, ParticleTypes.GUST, 14, 0.4, 0.5, 0.4, 0.1);
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
         BossVfx.beam(level, player.position(), player.position().add(look.scale(3.0)), 0.2, ParticleTypes.CLOUD);
         BossVfx.at(level, player.position(), 0.0, ParticleTypes.SMALL_GUST, 16, 0.5, 0.2, 0.5, 0.08);
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
      BossVfx.beam(wave.level, left.add(0.0, 0.6, 0.0), right.add(0.0, 0.6, 0.0), 0.5, ParticleTypes.SPLASH);
      BossVfx.at(wave.level, wave.at.add(0.0, 0.4, 0.0), 0.0, ParticleTypes.FALLING_WATER, 22, 1.2, 0.8, 1.2, 0.14);
      BossVfx.at(wave.level, wave.at.add(0.0, 0.2, 0.0), 0.0, ParticleTypes.BUBBLE, 12, 1.4, 0.3, 1.4, 0.06);
   }

   private static void tickVortexes() {
      if (VORTEXES.isEmpty()) {
         return;
      }
      for (Iterator<Vortex> it = VORTEXES.iterator(); it.hasNext();) {
         Vortex vortex = it.next();
         if (--vortex.ticks <= 0) {
            BossVfx.at(vortex.level, vortex.at, 0.0, ParticleTypes.GUST, 20, 1.4, 0.4, 1.4, 0.15);
            it.remove();
            continue;
         }
         ServerPlayer owner = vortex.level.getServer().getPlayerList().getPlayer(vortex.owner);
         int points = 18;
         double spin = vortex.ticks * 0.28;
         for (int i = 0; i < points; i++) {
            double a = spin + i * (Math.PI * 2.0 / points);
            double r = VORTEX_RADIUS * (0.35 + 0.65 * ((i * 7 % points) / (double)points));
            BossVfx.at(
               vortex.level,
               vortex.at.add(Math.cos(a) * r, 0.35, Math.sin(a) * r),
               0.0,
               ParticleTypes.BUBBLE_POP,
               1,
               0.0,
               0.0,
               0.0,
               0.0
            );
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
                     BossVfx.at(chakram.level, owner.position(), 0.0, ParticleTypes.GUST, 14, 0.4, 0.4, 0.4, 0.1);
                  }
                  BossVfx.at(chakram.level, chakram.at, 0.0, ParticleTypes.CLOUD, 10, 0.3, 0.3, 0.3, 0.02);
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
            chakram.travelled += CHAKRAM_SPEED;
            if (chakram.travelled >= CHAKRAM_REACH) {
               chakram.returning = true;
               chakram.hit.clear();
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
      int points = 14;
      for (int i = 0; i < points; i++) {
         double a = i * (Math.PI * 2.0 / points);
         BossVfx.at(
            chakram.level,
            chakram.at.add(Math.cos(a) * 0.7, Math.sin(a) * 0.7, 0.0),
            0.0,
            ParticleTypes.CLOUD,
            1,
            0.0,
            0.0,
            0.0,
            0.0
         );
      }
      BossVfx.at(chakram.level, chakram.at, 0.0, ParticleTypes.SMALL_GUST, 3, 0.2, 0.2, 0.2, 0.02);
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
         BossVfx.at(chakram.level, chakram.at, 0.0, ParticleTypes.GUST, 10, 0.4, 0.4, 0.4, 0.1);
         chakram.level.playSound(null, e.getX(), e.getY(), e.getZ(), SoundEvents.BREEZE_DEFLECT, SoundSource.PLAYERS, 1.0F, 1.2F);
         if (streak >= 2) {
            bar(owner, "&fRazor Current &8x&f" + streak + " &8- &7it is going further each time.");
         }
      }
   }

   private static void tickSlams(MinecraftServer server) {
      if (SLAMS.isEmpty()) {
         return;
      }
      for (Iterator<SkySlam> it = SLAMS.iterator(); it.hasNext();) {
         SkySlam slam = it.next();
         if (--slam.fuse > 0) {
            continue;
         }
         it.remove();
         ServerPlayer owner = server.getPlayerList().getPlayer(slam.owner);
         if (owner == null || !owner.isAlive() || owner.level() != slam.level) {
            continue;
         }
         // Lands where the caster actually is, not where they jumped from: the ability is a dive,
         // and a dive that hit the ground behind you would be a different move.
         Vec3 at = owner.position();
         int hits = 0;
         for (LivingEntity e : enemiesNear(owner, slam.level, at, BREAK_RADIUS)) {
            e.hurtServer(slam.level, slam.level.damageSources().playerAttack(owner), BREAK_DAMAGE);
            Vec3 away = flatAway(at, e);
            e.push(away.x * BREAK_PUSH, 0.75, away.z * BREAK_PUSH);
            e.hurtMarked = true;
            hits++;
         }
         BossVfx.ring(slam.level, at.add(0.0, 0.3, 0.0), BREAK_RADIUS, 46, ParticleTypes.GUST, 0.0);
         BossVfx.ring(slam.level, at.add(0.0, 0.9, 0.0), BREAK_RADIUS * 0.6, 30, ParticleTypes.CLOUD, 0.0);
         BossVfx.at(slam.level, at, 0.0, ParticleTypes.GUST_EMITTER_LARGE, 5, 1.6, 0.6, 1.6, 0.0);
         slam.level.playSound(null, at.x, at.y, at.z, SoundEvents.BREEZE_WIND_CHARGE_BURST, SoundSource.PLAYERS, 1.8F, 0.8F);
         slam.level.playSound(null, at.x, at.y, at.z, SoundEvents.MACE_SMASH_GROUND_HEAVY, SoundSource.PLAYERS, 1.4F, 1.1F);
         bar(owner, "&fBreak the Sky &8- &7the ring caught &f" + hits + "&7.");
      }
   }

   // ---------------------------------------------------------------- the vortex both sea weapons share

   private static String openVortex(ServerPlayer player, ServerLevel level) {
      if (cooldown(player, "vortex", VORTEX_COOLDOWN_TICKS)) {
         return null;
      }
      Vec3 at = player.position();
      VORTEXES.add(new Vortex(player.getUUID(), level, at, VORTEX_TICKS));
      BossVfx.ring(level, at, VORTEX_RADIUS, 30, ParticleTypes.BUBBLE, 0.0);
      level.playSound(null, at.x, at.y, at.z, SoundEvents.CONDUIT_ACTIVATE, SoundSource.PLAYERS, 1.4F, 0.7F);
      bar(player, "&3Undertow &8- &7everything nearby is leaning in.");
      return null;
   }

   // ---------------------------------------------------------------- helpers

   private static ServerLevel levelOf(ServerPlayer player) {
      return player != null && player.level() instanceof ServerLevel level ? level : null;
   }

   /** The nearest living body in front of the player, out to {@code reach}. */
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
      long now = player.level().getGameTime();
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
      return until != null && until >= bearer.level().getGameTime();
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
