package com.fortuneandfavors.economy;

import com.fortuneandfavors.ModItems;
import com.fortuneandfavors.util.ModPlatform;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The Ender Dragon's three legendary weapons: Voidfang, Starfall and Enderheart.
 *
 * <h2>Why these three live together</h2>
 * They are one set - forged from the same two materials, dropped by the same
 * fight - and each one is a bow, a sword or a mace underneath, so what is
 * special about them is only ever the layer on top. That layer is small enough
 * to be one file, and keeping it one file is what makes the set's numbers
 * comparable: {@link #RIFT_SLASH_COOLDOWN_TICKS} and
 * {@link #STARFALL_COOLDOWN_SECONDS} are deliberately read side by side.
 *
 * <h2>The weapons are slow on purpose</h2>
 * Every one of them has a long cooldown and a real cost, because the fight that
 * drops them is the mod's largest and a set that could be spammed would trivialise
 * it. Voidfang's mark could be an infinite damage hose if the third mark had no
 * ceiling; the ceiling is {@link #VOID_HUNGER_DETONATE_GAP} - per body, so three
 * players each landing one hit cannot detonate three times in a tick.
 *
 * <h2>What is read, and from where</h2>
 * Melee abilities are driven from the damage pipeline (see {@code ModEvents}),
 * arrows from the arrow's own {@code getWeaponItem()} - an arrow carries the
 * weapon that fired it for its whole flight, so a Starfall mark needs no tag and
 * survives the player swapping weapons mid-shot. Everything that lasts (a volley
 * in the air, a suspended room, a dive) is a small state object here, ticked from
 * {@link #tick}.
 */
public final class EnderGear {

   private static final Random RANDOM = new Random();

   private EnderGear() {
   }

   /** Everything these weapons say goes to the action bar, so combat stays legible. */
   private static void bar(ServerPlayer player, String text) {
      if (player != null) {
         player.sendOverlayMessage(Component.literal(text));
      }
   }

   /**
    * Whether this stack is the awakened tier of its weapon.
    *
    * <p>Every number below has two values and one of them is chosen here, so the upgrade is a
    * change of *rate* rather than a second weapon with its own rules: an awakened Voidfang runs
    * the exact code a Voidfang runs, only on shorter clocks.
    */
   private static boolean awakened(ItemStack held) {
      return ModItems.isEnderAwakened(held);
   }

   private static boolean ready(ServerPlayer player, ItemStack held, long now) {
      return ModItems.cooldownSecondsLeft(held, now) <= 0L;
   }

   private static void cool(ServerPlayer player, ItemStack held, long now, int ticks) {
      ModItems.setCooldownUntil(held, now + ticks);
      player.getCooldowns().addCooldown(held, ticks);
   }

   /**
    * Puts an ability on its own clock without greying the item.
    *
    * <p>Used by Starfall, which is a bow underneath: the vanilla item cooldown would sweep across
    * the hotbar and refuse the right-click, so the charged shot would take the bow's own attack
    * away every time it was used. The ability's clock is kept, and the player reads it on the
    * action bar.
    */
   private static void coolSoft(ItemStack held, long now, int ticks) {
      ModItems.setCooldownUntil(held, now + ticks);
   }

   private static ServerLevel levelOf(Player player) {
      return player.level() instanceof ServerLevel sl ? sl : null;
   }

   // ================================================================= Voidfang

   /**
    * How far forward the rift is carved, and how wide a body has to be to be caught.
    *
    * <p>The rift is the blade's bread and butter - it is the one button a Voidfang presses - so both
    * its harm and its clock are set against the *marks* rather than against other weapons: a rift
    * has to be worth about what one mark is, or the blade is really just a way to feed the
    * detonation. Nine is where the two halves of the sword line up.
    */
   private static final double RIFT_SLASH_RANGE = 13.0;
   private static final double RIFT_SLASH_HALF_WIDTH = 1.7;
   private static final float RIFT_SLASH_DAMAGE = 9.0F;
   private static final int RIFT_SLASH_COOLDOWN_TICKS = 40;

   /** The awakened blade: the same rift, closed sooner and cut deeper. */
   private static final float RIFT_SLASH_DAMAGE_AWAKENED = 12.0F;
   private static final int RIFT_SLASH_COOLDOWN_TICKS_AWAKENED = 28;

   /**
    * Marks to detonate, and how long each one lives on a body.
    *
    * <p>Six seconds for three hits, rather than the four and a half it used to be: three landed
    * blows is a rhythm, and a rhythm a target can break by walking backwards for four seconds is a
    * rhythm nobody ever plays. The window is what makes Voidfang a *pressure* weapon - it asks the
    * target for six seconds of standing still in melee range, which is the whole of the ask.
    */
   public static final int VOID_HUNGER_MARKS = 3;
   private static final int VOID_HUNGER_MARK_TICKS = 120;
   private static final float VOID_HUNGER_DAMAGE = 12.0F;
   /**
    * How long one body's marks are dead after they detonate.
    *
    * <p>This is the number that stops Voidfang being an infinite DPS machine: without it a
    * second attacker's marks could detonate the same body again on the very next tick, and a
    * group of three Voidfangs would out-damage every other weapon in the mod combined.
    *
    * <p>The dead time is shorter than it was, because the detonation is now worth more than the
    * three hits that bought it - a cannon that pays out that much does not need four and a half
    * seconds of being switched off as well.
    */
   private static final int VOID_HUNGER_DETONATE_GAP = 90;
   /** The awakened ceiling - still a ceiling, just a shorter one. */
   private static final int VOID_HUNGER_DETONATE_GAP_AWAKENED = 70;

   private static final class Marks {
      int count;
      long expiresAt;
      long detonateReadyAt;
   }

   /** Marks are per body, and the ceiling above is per body: three hiters share one detonation. */
   private static final Map<UUID, Marks> MARKS = new HashMap<>();

   /** Voidfang right-click: carve a rift forward through whatever stands in the lane. */
   public static String useVoidfang(ServerPlayer player, ItemStack held) {
      long now = player.level().getGameTime();
      if (!ready(player, held, now)) {
         bar(player, "§5Voidfang §8| §7the rift is still closing §8- §f" + ModItems.cooldownSecondsLeft(held, now) + "s");
         return null;
      }
      ServerLevel level = levelOf(player);
      if (level == null) {
         return "That can only be done in the world.";
      }

      boolean up = awakened(held);
      Vec3 from = player.getEyePosition();
      Vec3 dir = player.getViewVector(1.0F);
      Vec3 to = from.add(dir.scale(RIFT_SLASH_RANGE));

      int hit = 0;
      for (LivingEntity victim : inLane(level, player, from, to, RIFT_SLASH_HALF_WIDTH)) {
         victim.hurtServer(level, level.damageSources().playerAttack(player), up ? RIFT_SLASH_DAMAGE_AWAKENED : RIFT_SLASH_DAMAGE);
         mark(player, victim, now, up);
         hit++;
      }

      carve(level, from, to);
      // The slash is a blink: step out at the far end of the cut, short of any wall.
      net.minecraft.world.phys.HitResult clip = level.clip(new net.minecraft.world.level.ClipContext(
         from, to, net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, player));
      Vec3 land = clip.getLocation().subtract(dir.scale(0.8));
      com.fortuneandfavors.net.FfVfx.shape(level, com.fortuneandfavors.net.FfVfx.BEAM, ParticleTypes.REVERSE_PORTAL, from, land, 0.0, 0.0, 0xB06BFF);
      com.fortuneandfavors.net.FfVfx.shape(level, com.fortuneandfavors.net.FfVfx.RIFT, ParticleTypes.REVERSE_PORTAL, player.position(), Vec3.ZERO, 1.0, 0.0, 0xB06BFF);
      player.teleportTo(land.x, land.y - player.getEyeHeight(), land.z);
      player.resetFallDistance();
      com.fortuneandfavors.net.FfVfx.shape(level, com.fortuneandfavors.net.FfVfx.RIFT, ParticleTypes.REVERSE_PORTAL, player.position(), Vec3.ZERO, 1.0, 0.0, 0xB06BFF);
      level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 1.0F, 0.6F);
      level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 0.8F, 1.4F);
      cool(player, held, now, up ? RIFT_SLASH_COOLDOWN_TICKS_AWAKENED : RIFT_SLASH_COOLDOWN_TICKS);
      bar(player, hit == 0
         ? "§5Rift Slash §8| §7the rift opened on nothing"
         : "§5Rift Slash §8| §f" + hit + " §7cut, the rift is closing");
      return null;
   }

   /** Every living body whose centre lies within {@code half} of the segment. */
   private static List<LivingEntity> inLane(ServerLevel level, ServerPlayer caster, Vec3 from, Vec3 to, double half) {
      Vec3 seg = to.subtract(from);
      double lenSqr = seg.lengthSqr();
      List<LivingEntity> found = new ArrayList<>();
      if (lenSqr < 1.0E-4) {
         return found;
      }
      AABB box = new AABB(from, to).inflate(half + 1.0);
      for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, box, e -> e != caster && e.isAlive() && !e.isSpectator())) {
         Vec3 p = e.position().add(0.0, e.getBbHeight() * 0.5, 0.0);
         double t = p.subtract(from).dot(seg) / lenSqr;
         if (t < 0.0 || t > 1.0) {
            continue;
         }
         if (p.distanceToSqr(from.add(seg.scale(t))) <= half * half) {
            found.add(e);
         }
      }
      return found;
   }

   /** The rift itself: a widening tear in the air, plus the light leaking out of it. */
   private static void carve(ServerLevel level, Vec3 from, Vec3 to) {
      Vec3 seg = to.subtract(from);
      int steps = (int)Math.max(8.0, seg.length() * 3.0);
      for (int i = 0; i <= steps; i++) {
         double t = i / (double)steps;
         Vec3 p = from.add(seg.scale(t));
         double wobble = Math.sin(t * Math.PI) * 0.45;
         com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.REVERSE_PORTAL, p.x, p.y, p.z, 3, wobble, wobble, wobble, 0.4);
         com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.PORTAL, p.x, p.y, p.z, 2, wobble, wobble, wobble, 0.9);
         if (i % 3 == 0) {
            com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.END_ROD, p.x, p.y, p.z, 1, 0.05, 0.05, 0.05, 0.0);
         }
      }
   }

   /**
    * A landed Voidfang blow: one more mark on the body, and a detonation on the third.
    *
    * <p>The tier is read off the weapon in the hitter's hand rather than captured anywhere,
    * because this runs from the damage pipeline where the blow may have come from a sweep, a
    * crit or a riposte - the weapon that landed it is always the one in hand.
    */
   public static void onVoidfangHit(ServerPlayer hitter, LivingEntity victim) {
      mark(hitter, victim, hitter.level().getGameTime(), awakened(hitter.getMainHandItem()));
   }

   private static void mark(ServerPlayer hitter, LivingEntity victim, long now, boolean up) {
      Marks m = MARKS.computeIfAbsent(victim.getUUID(), k -> new Marks());
      if (now < m.detonateReadyAt) {
         return;
      }
      if (now >= m.expiresAt) {
         m.count = 0;
      }
      m.expiresAt = now + VOID_HUNGER_MARK_TICKS;
      m.count++;
      ServerLevel level = victim.level() instanceof ServerLevel sl ? sl : null;
      if (level != null) {
         com.fortuneandfavors.net.FfVfx.shape(level, com.fortuneandfavors.net.FfVfx.RING, ParticleTypes.REVERSE_PORTAL, victim.position().add(0.0, victim.getBbHeight() + 0.3, 0.0), Vec3.ZERO, 0.5 + 0.25 * m.count, 0.0, 0);
      }
      if (m.count >= VOID_HUNGER_MARKS) {
         detonate(hitter, victim, level);
         m.count = 0;
         m.detonateReadyAt = now + (up ? VOID_HUNGER_DETONATE_GAP_AWAKENED : VOID_HUNGER_DETONATE_GAP);
      }
   }

   private static void detonate(ServerPlayer hitter, LivingEntity victim, ServerLevel level) {
      if (level == null) {
         return;
      }
      // The marks go off as the weapon's own damage, so the credit lands where the hits came from.
      victim.hurtServer(level, level.damageSources().playerAttack(hitter), VOID_HUNGER_DAMAGE);
      victim.push(0.0, 0.45, 0.0);
      com.fortuneandfavors.net.FfVfx.shape(level, com.fortuneandfavors.net.FfVfx.NOVA, ParticleTypes.REVERSE_PORTAL, victim.position(), Vec3.ZERO, 3.5, 0.0, 0xB06BFF);
      // Hunger spreads: every other marked body near the blast takes a mark of its own.
      long now = level.getGameTime();
      for (LivingEntity near : level.getEntitiesOfClass(LivingEntity.class, victim.getBoundingBox().inflate(5.0),
            e -> e != victim && e != hitter && e.isAlive() && MARKS.containsKey(e.getUUID()) && MARKS.get(e.getUUID()).count > 0)) {
         com.fortuneandfavors.net.FfVfx.shape(level, com.fortuneandfavors.net.FfVfx.BEAM, ParticleTypes.REVERSE_PORTAL, victim.position().add(0.0, 1.0, 0.0), near.position().add(0.0, 1.0, 0.0), 0.0, 0.0, 0xB06BFF);
         mark(hitter, near, now, false);
      }
      victim.hurtMarked = true;
      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.EXPLOSION_EMITTER, victim.getX(), victim.getY() + victim.getBbHeight() * 0.5, victim.getZ(), 1, 0.0, 0.0, 0.0, 0.0);
      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.PORTAL, victim.getX(), victim.getY() + victim.getBbHeight() * 0.5, victim.getZ(), 60, 0.9, 0.9, 0.9, 0.7);
      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.END_ROD, victim.getX(), victim.getY() + victim.getBbHeight() * 0.5, victim.getZ(), 30, 1.1, 1.1, 1.1, 0.25);
      level.playSound(null, victim.getX(), victim.getY(), victim.getZ(), SoundEvents.GENERIC_EXPLODE, SoundSource.PLAYERS, 0.9F, 1.6F);
      bar(hitter, "§5Void Hunger §8| §7three marks §8- §fdetonated");
   }

   /** Marks left on a body, for the self-test and for nothing else. */
   public static int marksOn(UUID victim) {
      Marks m = MARKS.get(victim);
      return m == null ? 0 : m.count;
   }

   // ================================================================= Starfall

   /** How many arrows it takes to burst the mark, and how long a stack lasts. */
   public static final int ASTRAL_MARKS = 4;
   /** The awakened bow bursts an arrow sooner, which is what makes it a pressure weapon. */
   public static final int ASTRAL_MARKS_AWAKENED = 3;
   /**
    * How long a mark lives, and what the burst is worth.
    *
    * <p>Seven seconds to put four arrows into the same body, and a burst worth more than any single
    * one of the twelve stars. Six damage was less than a Starfall arrow and less than a crit, so a
    * mark that was four arrows in the making paid out like an accident - which made the bow's whole
    * pressure game into a rounding error next to its charge shot.
    */
   private static final int ASTRAL_MARK_TICKS = 140;
   private static final float ASTRAL_BURST_DAMAGE = 10.0F;

   /** The charged shot: how long the sky gathers, how many stars, and how wide they land. */
   private static final int STARFALL_COOLDOWN_SECONDS = 18;
   /**
    * How long the stars hang overhead before the first one falls.
    *
    * <p>The shot is not instant any more. A charged Starfall used to be a single frame of damage -
    * the stars were already on the ground by the time the arrow left - and that made the set's
    * biggest ability feel like a particle budget rather than a bombardment. Now the sky fills, the
    * stars come down one after another, and the target area is a place to get out of rather than a
    * place that was already hit.
    */
   private static final int STARFALL_SKY_TICKS = 22;
   /** How long one star takes to fall once it leaves the sky. */
   private static final int STARFALL_FALL_TICKS = 16;
   public static final int STARFALL_STARS = 14;
   /** The awakened volley brings down four more stars, and each lands harder. */
   public static final int STARFALL_STARS_AWAKENED = 18;
   /** Ticks between one star leaving the sky and the next, so the volley is a rain and not a wall. */
   private static final int STARFALL_STAR_GAP = 3;
   private static final double STARFALL_SPREAD = 6.5;
   private static final float STARFALL_DAMAGE = 8.0F;
   private static final float STARFALL_DAMAGE_AWAKENED = 10.0F;
   /**
    * How wide one falling star lands.
    *
    * <p>The radius is what decides whether the volley reads as a bombardment or as confetti: the
    * stars land on a six-and-a-half block spread, and a star that only claims two and a half of
    * those blocks is a volley you can walk between. Three and a bit is a floor you stand on at your
    * peril and still leaves a gap you can be standing in when the sky opens.
    */
   private static final double STARFALL_IMPACT_RADIUS = 3.2;
   /** How hard a falling star pulls toward a body under it, and how far it can see one. */
   private static final double STARFALL_HOMING = 0.35;
   private static final double STARFALL_HOMING_REACH = 8.0;
   /** How high a star starts its fall. */
   private static final double STARFALL_START_HEIGHT = 28.0;

   private static final class Astral {
      int count;
      long expiresAt;
   }

   private static final Map<UUID, Astral> ASTRAL = new HashMap<>();

   /** A charged Starfall shot: the sky filling, then the stars leaving it one at a time. */
   private static final class Volley {
      final ServerLevel level;
      final ServerPlayer caster;
      final Vec3 at;
      /**
       * The volley's numbers are captured when it is loosed, not read when it lands.
       *
       * <p>An arrow in flight cannot be un-fired, and the same is true of a charged shot: the
       * stars that are already falling were called by the bow that called them, so swapping
       * weapons mid-flight does not quietly upgrade or downgrade the rain.
       */
      final int stars;
      final float damage;
      int skyTicksLeft = STARFALL_SKY_TICKS;
      int launched = 0;
      int spawnClock = 0;

      Volley(ServerLevel level, ServerPlayer caster, Vec3 at, boolean up) {
         this.level = level;
         this.caster = caster;
         this.at = at;
         this.stars = up ? STARFALL_STARS_AWAKENED : STARFALL_STARS;
         this.damage = up ? STARFALL_DAMAGE_AWAKENED : STARFALL_DAMAGE;
      }
   }

   private static final List<Volley> VOLLEYS = new ArrayList<>();

   /** One star on its way down, aimed at a point on the ground. */
   private static final class Star {
      final ServerLevel level;
      final ServerPlayer caster;
      final Vec3 from;
      /** Mutable, because this is what the soft auto-aim nudges as the star falls. */
      Vec3 target;
      final float damage;
      int ticksLeft = STARFALL_FALL_TICKS;

      Star(ServerLevel level, ServerPlayer caster, Vec3 target, float damage) {
         this.level = level;
         this.caster = caster;
         this.target = target;
         this.from = target.add(0.0, STARFALL_START_HEIGHT, 0.0);
         this.damage = damage;
      }
   }

   private static final List<Star> STARS = new ArrayList<>();

   /** Starfall right-click: normal click draws nothing, sneak-right-click calls the stars down. */
   public static String useStarfall(ServerPlayer player, ItemStack held) {
      if (!player.isShiftKeyDown() && !com.fortuneandfavors.util.ModPlatform.isBedrock(player)) {
         // Defensive: the dispatch chain only routes a sneak click (or a Bedrock click) here,
         // because on Java the plain click has to reach the bow itself - a bow whose
         // right-click is claimed is a bow that can never fire.
         return null;
      }
      ServerLevel level = levelOf(player);
      if (level == null) {
         return "That can only be done in the world.";
      }
      long now = player.level().getGameTime();
      if (!ready(player, held, now)) {
         bar(player, "§5Starfall §8| §7the sky is still empty §8- §f" + ModItems.cooldownSecondsLeft(held, now) + "s");
         return null;
      }

      // The "charge" is the sneak: the shot is loosed upward from where the player is looking.
      // A real raycast: a fixed 24-block point sat underground whenever you aimed at nearer floor,
      // and the impact box down there hit nothing.
      Vec3 eye = player.getEyePosition();
      net.minecraft.world.phys.BlockHitResult hit = level.clip(new net.minecraft.world.level.ClipContext(eye, eye.add(player.getViewVector(1.0F).scale(24.0)),
         net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, player));
      Vec3 aim = hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK ? hit.getLocation().add(0.0, 0.5, 0.0) : hit.getLocation();
      Vec3 ground = aim;
      if (level.getBlockState(net.minecraft.core.BlockPos.containing(aim)).isAir()) {
         // Aiming into open sky: bring the landing point down to the floor under it.
         for (int drop = 1; drop <= 40; drop++) {
            Vec3 probe = aim.subtract(0.0, drop, 0.0);
            if (!level.getBlockState(net.minecraft.core.BlockPos.containing(probe)).isAir()) {
               ground = probe.add(0.0, 1.0, 0.0);
               break;
            }
         }
      }

      VOLLEYS.add(new Volley(level, player, ground, awakened(held)));
      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.END_ROD, player.getX(), player.getY() + 1.2, player.getZ(), 40, 0.4, 0.6, 0.4, 0.35);
      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.REVERSE_PORTAL, player.getX(), player.getY() + 1.2, player.getZ(), 30, 0.5, 0.8, 0.5, 0.3);
      level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.TRIDENT_RIPTIDE_1, SoundSource.PLAYERS, 1.0F, 1.4F);
      // The ability keeps its own clock and does NOT grey the bow: Starfall is still a bow, and a
      // bow whose right-click is locked out is a bow that cannot shoot an arrow. The cooldown is
      // read on the action bar instead - see tickStarCooldown.
      coolSoft(held, now, STARFALL_COOLDOWN_SECONDS * 20);
      bar(player, "§5Starfall §8| §7loosed §8- §fthe sky is filling");
      return null;
   }

   /**
    * A Starfall arrow landed. The arrow carries the weapon that fired it, so this needs no tag
    * and cannot be fooled by the archer swapping weapons while it is in the air.
    */
   public static void onArrowHit(ServerPlayer shooter, LivingEntity victim, AbstractArrow arrow) {
      if (arrow == null || !ModItems.isAnyStarfall(arrow.getWeaponItem())) {
         return;
      }
      // The tier rides on the arrow's own weapon, so the reading is the same one the shot was
      // fired with, whatever the archer is holding by the time it lands.
      int needed = awakened(arrow.getWeaponItem()) ? ASTRAL_MARKS_AWAKENED : ASTRAL_MARKS;
      long now = victim.level().getGameTime();
      Astral a = ASTRAL.computeIfAbsent(victim.getUUID(), k -> new Astral());
      if (now >= a.expiresAt) {
         a.count = 0;
      }
      a.expiresAt = now + ASTRAL_MARK_TICKS;
      a.count++;
      ServerLevel level = victim.level() instanceof ServerLevel sl ? sl : null;
      if (level != null) {
         com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.END_ROD, victim.getX(), victim.getY() + victim.getBbHeight() + 0.4, victim.getZ(), 4, 0.25, 0.15, 0.25, 0.02);
      }
      if (a.count >= needed) {
         a.count = 0;
         if (level != null) {
            victim.hurtServer(level, level.damageSources().playerAttack(shooter), ASTRAL_BURST_DAMAGE);
            com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.SOUL_FIRE_FLAME, victim.getX(), victim.getY() + victim.getBbHeight() * 0.5, victim.getZ(), 30, 0.7, 0.7, 0.7, 0.05);
            com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.END_ROD, victim.getX(), victim.getY() + victim.getBbHeight() * 0.5, victim.getZ(), 45, 0.8, 0.8, 0.8, 0.3);
            level.playSound(null, victim.getX(), victim.getY(), victim.getZ(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1.0F, 0.7F);
         }
      }
   }

   // =============================================================== Enderheart

   /** Impact: how wide the smash's shockwave reaches and how hard it throws. */
   private static final double IMPACT_RADIUS = 5.0;
   private static final double IMPACT_RADIUS_AWAKENED = 6.5;
   private static final double IMPACT_PUSH = 1.15;
   private static final double IMPACT_PUSH_AWAKENED = 1.45;
   private static final double IMPACT_MIN_FALL = 1.5;
   /**
    * What the shockwave itself pays, before the fall is added to it.
    *
    * <p>The mace's own smash pays the body that was hit; this is the shockwave's own blow, and it
    * used to be a shove and nothing else - so Impact's damage was vanilla's smash on one body and
    * zero on every other body in the ring.
    */
   private static final float IMPACT_DAMAGE = 5.0F;
   private static final float IMPACT_DAMAGE_AWAKENED = 6.5F;

   // ----------------------------------------------- the fall, and what the enchants add

   /**
    * The mace's own thresholds: three blocks before the curve bends, eight before it bends again.
    *
    * <p>The game's numbers ({@code MaceItem.SMASH_ATTACK_FALL_THRESHOLD} and the pair behind it)
    * rather than this weapon's, because all three Enderheart abilities are the same sentence - the
    * fall is the damage - and the fall has to mean the same thing in all three of them.
    */
   public static final double FALL_SOFT_THRESHOLD = 3.0;
   public static final double FALL_HARD_THRESHOLD = 8.0;
   /**
    * The most fall any one ability may be paid for.
    *
    * <p>A ceiling because the alternative is unbounded: a dive off a mountain, a lift into the
    * build limit and a fall off the world's edge are all "more fall", and none of them should
    * decide a fight on their own. Thirty-two blocks is well past the point where the curve
    * flattens, so it reads as generous rather than as a limit anyone meets on purpose.
    */
   public static final double ABILITY_FALL_CAP = 32.0;

   /**
    * What a fall is worth on its own: vanilla's smash curve, reproduced.
    *
    * <p>Four damage per block up to three blocks, two per block up to eight, one per block after
    * that - {@code MaceItem.getAttackDamageBonus}, which a scripted blow never reaches because it
    * is only asked for inside {@code Player.attack}. The curve is the half of the bonus that needs
    * no weapon, so it is its own function: {@link #fallBonus} adds the enchanted half to it.
    *
    * <p>Deliberately not gated on {@code canSmashAttack}: whether a fall <i>counts</i> is a
    * question about the ability (Impact wants a real fall, Gravity Break measures the one it made,
    * Enderfall brings its own), and this is only the arithmetic that turns one into damage. Zero
    * and negative falls are worth nothing, so any caller can hand it anything.
    */
   public static float fallCurve(double fall) {
      double f = Math.max(0.0, Math.min(ABILITY_FALL_CAP, fall));
      if (f <= 0.0) {
         return 0.0F;
      }
      double worth = f <= FALL_SOFT_THRESHOLD
         ? 4.0 * f
         : (f <= FALL_HARD_THRESHOLD
            ? 4.0 * FALL_SOFT_THRESHOLD + 2.0 * (f - FALL_SOFT_THRESHOLD)
            : 4.0 * FALL_SOFT_THRESHOLD
               + 2.0 * (FALL_HARD_THRESHOLD - FALL_SOFT_THRESHOLD)
               + (f - FALL_HARD_THRESHOLD));
      return (float)worth;
   }

   /**
    * Everything a fall is worth to an Enderheart ability: the curve, plus Density.
    *
    * <p>Every ability in this section is handed the fall it earned and pays it out through this
    * one function, so "the more you fall, the more it hurts" is one arithmetic rather than three
    * curves that drift apart. Density is asked for rather than written down: half a damage per
    * block fallen per level is vanilla's own {@code smash_damage_per_fallen_block}, read off the
    * mace in hand through {@link EnchantmentHelper#modifyFallBasedDamage}, exactly as
    * {@code MaceItem} reads it. The stack is a copy taken when the ability was cast, so a weapon
    * swapped mid-flight cannot change whose mace called the blow.
    *
    * <p><b>Breach and Astral need no line here, on purpose.</b> Both are read by the game, out of
    * the weapon on the damage source, by the time an ability's blow lands: a Breach mace's armour
    * effectiveness is applied inside {@code CombatRules.getDamageAfterAbsorb}, which asks the
    * source for its weapon - and the source of every ability blow here is a player attack made
    * while holding the mace; and Astral is multiplied in by this mod's own damage pipeline
    * ({@code CombatGear.apply}), which reads the same hand against mobs. Writing either of them a
    * second time here would double it.
    *
    * <p>One honest difference between the two halves, since it is decided here rather than there:
    * the fall bonus reads the stack captured at cast (so Density keeps paying for the mace that
    * called the blow), while Breach and Astral are read off whatever hand is holding it when the
    * blow finally lands. A player who swaps weapons on the way up therefore keeps the Density half
    * and loses the other two - which is the reading a player would expect, and is pinned rather than
    * assumed by {@code SelfTest}'s "the-enderheart-pays-in-fall" check, which measures all three
    * enchants on a real blow through the live pipeline.
    */
   public static float fallBonus(ServerLevel level, ItemStack weapon, LivingEntity victim, DamageSource source, double fall) {
      double f = Math.max(0.0, Math.min(ABILITY_FALL_CAP, fall));
      if (f <= 0.0) {
         return 0.0F;
      }
      float perBlock = 0.0F;
      try {
         perBlock = EnchantmentHelper.modifyFallBasedDamage(level, weapon, victim, source, 0.0F);
      } catch (Throwable ignored) {
         // A stack with no mace enchants on it, or a level the helper refuses, is worth the curve
         // alone - which is the same number a plain mace gives.
      }
      return fallCurve(f) + perBlock * (float)f;
   }

   /**
    * Gravity Break: how long the room is weightless, and how hard it lands.
    *
    * <p>The clock is shorter than this weapon used to run on, and the reason is the fall: the lift
    * is worth what each body drops from, so an Enderheart caster standing on flat ground with the
    * lever already in their hand gets the lift's own damage and almost nothing else. The weight of
    * the ability is paid *before* it, in the climb that has to happen first - so the item cooldown
    * is no longer the thing that keeps it honest, and holding a player out of their own weapon for
    * eighteen seconds to say so was the wrong price. Both Enderheart abilities share this one item
    * cooldown, so these two numbers are the mace's swing, not one ability's.
    */
   private static final int GRAVITY_BREAK_COOLDOWN_SECONDS = 12;
   /**
    * The awakened lever's clock: a third off, the way the awakened blade's rift is.
    *
    * <p>The tier's other halves are reach and harm, and both of those are *how much* the ability
    * does - so an awakened mace whose only difference was a wider ring would be the same weapon
    * with a bigger number on it, and the other two weapons in the set are not that. What the
    * awakened tier buys here is the ability to land the trick twice in one fight.
    */
   private static final int GRAVITY_BREAK_COOLDOWN_SECONDS_AWAKENED = 8;
   private static final int GRAVITY_BREAK_LIFT_TICKS = 45;
   private static final double GRAVITY_BREAK_RADIUS = 8.0;
   private static final double GRAVITY_BREAK_RADIUS_AWAKENED = 9.5;
   private static final float GRAVITY_BREAK_DAMAGE = 14.0F;
   private static final float GRAVITY_BREAK_DAMAGE_AWAKENED = 18.0F;

   /**
    * Enderfall: the dive.
    *
    * <p>Shorter than it was, on the same reading as the lever above - and here it is the dive's own
    * length that pays for it. The throw, the hang, the aim and the landing already cost two and a
    * half seconds of doing nothing but falling, and the landing is worth the height the throw
    * bought, so a caster who panics and lands on flat ground is paying the clock themselves. It
    * stays the longer of the two, because it is the set's biggest move.
    */
   private static final int ENDERFALL_COOLDOWN_SECONDS = 20;
   /**
    * The awakened dive's clock, on the same reading as the lever above - and it stays the longer
    * of the two, because it is still the set's biggest move rather than because of the tier.
    */
   private static final int ENDERFALL_COOLDOWN_SECONDS_AWAKENED = 14;
   private static final int ENDERFALL_LIFT_TICKS = 26;
   private static final int ENDERFALL_MAX_TICKS = 160;
   private static final double ENDERFALL_RADIUS = 7.0;
   private static final double ENDERFALL_RADIUS_AWAKENED = 8.5;
   private static final float ENDERFALL_DAMAGE = 22.0F;
   /**
    * The awakened dive's landing, about a third harder - the same step up as the rest of the set.
    *
    * <p>This was the last number the two tiers shared: the awakened crater was wider and, since the
    * clock was split, sooner to come back, but everyone it landed on took exactly what the base
    * mace's landing takes. In a set where awakening is a change of *rate* rather than a second
    * weapon, being the one ability that lands equally hard is the one difference that reads as
    * nothing.
    */
   private static final float ENDERFALL_DAMAGE_AWAKENED = 28.0F;

   /**
    * What one body's lift is worth: the ground it was standing on, and the highest it got.
    *
    * <p>Two numbers rather than one because the drop is the difference between them, and neither is
    * enough alone - the peak says how high the lever carried it, the floor says how far that is from
    * where the weight will put it back.
    */
   private static final class Rise {
      final double from;
      double to;

      Rise(double from) {
         this.from = from;
         this.to = from;
      }
   }

   /** A room whose weight has been taken away and is about to be given back. */
   private static final class Weightless {
      final ServerLevel level;
      final ServerPlayer caster;
      final Vec3 at;
      /** Captured at cast, so the room that comes back down is the room that went up. */
      final double radius;
      final float damage;
      /** The mace that called the lift, captured at cast for the same reason. */
      final ItemStack weapon;
      /**
       * What each caught body's lift is worth, by uuid - and the roll of who was caught at all.
       *
       * <p>The height is read off the bodies themselves rather than off the lift's clock: the lever
       * raises whatever stands in the ring, and how far the weight brings each one down is the
       * difference between the ground it stood on and the highest it got. Keyed by uuid rather than
       * by entity because a body can be unloaded and reloaded mid-lift, and because the landing has
       * to reach bodies that are no longer anywhere near the ring - see {@link #caught()}.
       */
      final Map<UUID, Rise> lifted = new HashMap<>();
      int ticksLeft = GRAVITY_BREAK_LIFT_TICKS;

      Weightless(ServerLevel level, ServerPlayer caster, Vec3 at, boolean up, ItemStack weapon) {
         this.level = level;
         this.caster = caster;
         this.at = at;
         this.radius = up ? GRAVITY_BREAK_RADIUS_AWAKENED : GRAVITY_BREAK_RADIUS;
         this.damage = up ? GRAVITY_BREAK_DAMAGE_AWAKENED : GRAVITY_BREAK_DAMAGE;
         this.weapon = weapon;
      }

      /** Remembers where each body stood and how high the lift has carried it. */
      void trackLift() {
         for (LivingEntity body : inSphere(this.level, this.at, this.radius, this.caster)) {
            Rise rise = this.lifted.computeIfAbsent(body.getUUID(), k -> new Rise(body.getY()));
            rise.to = Math.max(rise.to, body.getY());
         }
      }

      /**
       * How far the weight brings one body down out of this lift, in blocks.
       *
       * <p>Read as the height the lift actually gave the body - its peak against the ground it was
       * standing on when the lever was pulled - and not as the body's position at the moment the
       * weight returns. Those are the same number only if the body is already on its way down: the
       * weight comes back at the end of the rise, so a body measured against where it *is* scores
       * nothing at all, and the ability pays its flat opening and no fall. The measurement has to be
       * the fall the body is about to take, which is the height the room was raised.
       *
       * <p>It stays per body for the reason it always was: a body raised off a pillar has less of a
       * drop than one raised off the floor, and a body that was already in the air has less still.
       */
      double fallOf(LivingEntity body) {
         Rise rise = this.lifted.get(body.getUUID());
         return rise == null ? 0.0 : Math.max(0.0, rise.to - rise.from);
      }

      /**
       * Every body the lift caught, whether or not it is still standing in the ring.
       *
       * <p>The ring is where the weight was taken from, not a leash on it: the lever raises what is
       * standing there when it is pulled, and the weight has to come back to whoever was raised. A
       * body that walks out is still the body that is in the air - and a slam that only reaches the
       * ones who stayed is a slam whose counterplay is to walk, which is the one answer nobody should
       * have to learn. The escapee used to keep its Levitation as well, so it drifted off with the
       * ring's weight still in its pocket.
       *
       * <p>Bodies are looked up by uuid rather than held as entities, because the lift outlives the
       * chunk's entity list changing under it - and a body that has left the world entirely in the
       * meantime is simply not caught, which is the one escape the ability allows.
       */
      List<LivingEntity> caught() {
         List<LivingEntity> caught = new ArrayList<>();
         for (UUID id : this.lifted.keySet()) {
            if (this.level.getEntity(id) instanceof LivingEntity body
               && body != this.caster
               && body.isAlive()
               && !body.isSpectator()) {
               caught.add(body);
            }
         }
         // And anything that walked *into* the ring late enough not to have been weighed at all:
         // the weight is on the floor where they are standing, so it comes back to them too.
         for (LivingEntity body : inSphere(this.level, this.at, this.radius, this.caster)) {
            if (!this.lifted.containsKey(body.getUUID())) {
               caught.add(body);
            }
         }
         return caught;
      }
   }

   private static final List<Weightless> WEIGHTLESS = new ArrayList<>();

   /** A player thrown up by Enderfall, waiting for the moment to come back down. */
   private static final class Dive {
      final UUID owner;
      final ServerLevel level;
      final double radius;
      /**
       * What the landing is worth on its own, captured at cast.
       *
       * <p>The same reading as the lever's: an arrow in flight cannot be un-fired, and neither can
       * a player who is already in the air - the mace that threw them is the mace that is owed the
       * landing, whatever they are holding by the time they arrive. The fall on top of it is added
       * at the moment of the crash, out of the same captured weapon.
       */
      final float damage;
      /** The mace that threw them: captured at cast, so the landing is read off the weapon that called it. */
      final ItemStack weapon;
      int ticks;
      boolean falling;

      Dive(UUID owner, ServerLevel level, boolean up, ItemStack weapon) {
         this.owner = owner;
         this.level = level;
         this.radius = up ? ENDERFALL_RADIUS_AWAKENED : ENDERFALL_RADIUS;
         this.damage = up ? ENDERFALL_DAMAGE_AWAKENED : ENDERFALL_DAMAGE;
         this.weapon = weapon;
      }
   }

   private static final Map<UUID, Dive> DIVES = new HashMap<>();

   /** Enderheart right-click: Gravity Break. Sneak-right-click: Enderfall. */
   public static String useEnderheart(ServerPlayer player, ItemStack held) {
      return player.isShiftKeyDown() ? enderfall(player, held) : gravityBreak(player, held);
   }

   private static String gravityBreak(ServerPlayer player, ItemStack held) {
      ServerLevel level = levelOf(player);
      if (level == null) {
         return "That can only be done in the world.";
      }
      long now = player.level().getGameTime();
      if (!ready(player, held, now)) {
         bar(player, "§5Gravity Break §8| §7still recovering §8- §f" + ModItems.cooldownSecondsLeft(held, now) + "s");
         return null;
      }
      boolean up = awakened(held);
      double radius = up ? GRAVITY_BREAK_RADIUS_AWAKENED : GRAVITY_BREAK_RADIUS;
      Vec3 at = player.position();
      WEIGHTLESS.add(new Weightless(level, player, at, up, held.copy()));
      for (LivingEntity e : inSphere(level, at, radius, player)) {
         e.addEffect(new MobEffectInstance(MobEffects.LEVITATION, GRAVITY_BREAK_LIFT_TICKS, 2, false, true));
      }
      com.fortuneandfavors.net.FfVfx.shape(level, com.fortuneandfavors.net.FfVfx.SUMMON_CIRCLE, ParticleTypes.REVERSE_PORTAL, at, Vec3.ZERO, radius, GRAVITY_BREAK_LIFT_TICKS, 0xB06BFF);
      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.REVERSE_PORTAL, at.x, at.y + 0.3, at.z, 90, radius * 0.5, 0.4, radius * 0.5, -0.05);
      level.playSound(null, at.x, at.y, at.z, SoundEvents.WARDEN_SONIC_CHARGE, SoundSource.PLAYERS, 1.0F, 0.7F);
      cool(player, held, now, (up ? GRAVITY_BREAK_COOLDOWN_SECONDS_AWAKENED : GRAVITY_BREAK_COOLDOWN_SECONDS) * 20);
      bar(player, "§5Gravity Break §8| §7the weight is gone");
      return null;
   }

   private static String enderfall(ServerPlayer player, ItemStack held) {
      ServerLevel level = levelOf(player);
      if (level == null) {
         return "That can only be done in the world.";
      }
      long now = player.level().getGameTime();
      if (DIVES.containsKey(player.getUUID())) {
         bar(player, "§5Enderfall §8| §7already airborne");
         return null;
      }
      if (!ready(player, held, now)) {
         bar(player, "§5Enderfall §8| §7the wings need longer §8- §f" + ModItems.cooldownSecondsLeft(held, now) + "s");
         return null;
      }
      boolean up = awakened(held);
      Vec3 look = player.getViewVector(1.0F);
      player.setDeltaMovement(look.x * 0.6, 1.55, look.z * 0.6);
      player.hurtMarked = true;
      player.resetFallDistance();
      DIVES.put(player.getUUID(), new Dive(player.getUUID(), level, up, held.copy()));
      com.fortuneandfavors.net.FfVfx.shape(level, com.fortuneandfavors.net.FfVfx.PILLAR, ParticleTypes.END_ROD, player.position(), Vec3.ZERO, 8.0, 0.0, 0xB06BFF);
      level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ENDER_DRAGON_FLAP, SoundSource.PLAYERS, 1.2F, 1.5F);
      cool(player, held, now, (up ? ENDERFALL_COOLDOWN_SECONDS_AWAKENED : ENDERFALL_COOLDOWN_SECONDS) * 20);
      bar(player, "§5Enderfall §8| §7thrown up §8- §faim, then land on them");
      return null;
   }

   /** An Enderheart blow that landed: the smash leaves an End shockwave behind it. */
   public static void onEnderheartHit(ServerPlayer hitter, LivingEntity victim) {
      double fall = recentFall(hitter);
      if (fall < IMPACT_MIN_FALL) {
         return;
      }
      ServerLevel level = hitter.level() instanceof ServerLevel sl ? sl : null;
      if (level == null) {
         return;
      }
      // Read from the hand that landed the blow, for the same reason the sword's mark is.
      ItemStack weapon = hitter.getMainHandItem();
      boolean up = awakened(weapon);
      double radius = up ? IMPACT_RADIUS_AWAKENED : IMPACT_RADIUS;
      double push = up ? IMPACT_PUSH_AWAKENED : IMPACT_PUSH;
      Vec3 at = victim.position();
      // The ring's own blow, and it is worth the fall that made it. Every body the shockwave
      // reaches takes it - the one at the centre included, whose smash vanilla already paid, because
      // the promise of Impact is that the fall is the damage and a shove for everyone but the body
      // the hit already named is the half of that promise that was missing.
      DamageSource source = level.damageSources().playerAttack(hitter);
      float damage = (up ? IMPACT_DAMAGE_AWAKENED : IMPACT_DAMAGE)
         + fallBonus(level, weapon, victim, source, fall);
      for (LivingEntity e : inSphere(level, at, radius, hitter)) {
         e.hurtServer(level, source, damage);
         if (e == victim) {
            continue;
         }
         Vec3 away = e.position().subtract(at);
         Vec3 flat = new Vec3(away.x, 0.0, away.z);
         if (flat.lengthSqr() < 1.0E-4) {
            flat = new Vec3(RANDOM.nextDouble() - 0.5, 0.0, RANDOM.nextDouble() - 0.5);
         }
         Vec3 throwOut = flat.normalize().scale(push);
         e.push(throwOut.x, 0.45, throwOut.z);
         e.hurtMarked = true;
      }
      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.EXPLOSION_EMITTER, at.x, at.y + 0.4, at.z, 1, 0.0, 0.0, 0.0, 0.0);
      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.END_ROD, at.x, at.y + 0.4, at.z, 50, 1.4, 0.4, 1.4, 0.35);
      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.PORTAL, at.x, at.y + 0.4, at.z, 60, 1.6, 0.4, 1.6, 0.5);
      level.playSound(null, at.x, at.y, at.z, SoundEvents.GENERIC_EXPLODE, SoundSource.PLAYERS, 1.0F, 1.2F);
      level.playSound(null, at.x, at.y, at.z, SoundEvents.MACE_SMASH_GROUND_HEAVY, SoundSource.PLAYERS, 1.0F, 1.0F);
   }

   // ==================================================================== ticking

   /** Every living body within {@code radius} of {@code at}, the caster aside. */
   private static List<LivingEntity> inSphere(ServerLevel level, Vec3 at, double radius, ServerPlayer caster) {
      AABB box = new AABB(at, at).inflate(radius);
      return level.getEntitiesOfClass(
         LivingEntity.class,
         box,
         e -> e != caster && e.isAlive() && !e.isSpectator() && e.distanceToSqr(at) <= radius * radius
      );
   }

   /** All the set's lasting state, ticked once a server tick. */
   public static void tick(MinecraftServer server) {
      if (server == null) {
         return;
      }
      tickVolleys();
      tickStars();
      tickStarCooldown(server);
      tickFallTracking(server);
      tickWeightless();
      tickDives(server);
      if (server.getTickCount() % 40 == 0) {
         long now = server.overworld() == null ? 0L : server.getTickCount();
         prune(now);
      }
   }

   /** The sky gathering, then the stars leaving it one at a time. */
   private static void tickVolleys() {
      Iterator<Volley> it = VOLLEYS.iterator();
      while (it.hasNext()) {
         Volley v = it.next();
         if (v.skyTicksLeft > 0) {
            v.skyTicksLeft--;
            com.fortuneandfavors.net.FfVfx.particles(v.level, ParticleTypes.END_ROD, v.at.x, v.at.y + STARFALL_START_HEIGHT - 4.0, v.at.z, 5, 2.0, 0.8, 2.0, 0.06);
            com.fortuneandfavors.net.FfVfx.particles(v.level, ParticleTypes.REVERSE_PORTAL, v.at.x, v.at.y + STARFALL_START_HEIGHT, v.at.z, 3, 2.5, 1.0, 2.5, -0.05);
            if (v.skyTicksLeft == 0) {
               bar(v.caster, "§5Starfall §8| §7the sky opens");
               v.level.playSound(null, v.at.x, v.at.y, v.at.z, SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.PLAYERS, 1.6F, 0.7F);
            }
            continue;
         }
         if (v.launched < v.stars) {
            if (++v.spawnClock >= STARFALL_STAR_GAP) {
               v.spawnClock = 0;
               double a = RANDOM.nextDouble() * Math.PI * 2.0;
               double r = STARFALL_SPREAD * Math.sqrt(RANDOM.nextDouble());
               Vec3 ground = v.at.add(Math.cos(a) * r, 0.0, Math.sin(a) * r);
               Star star = new Star(v.level, v.caster, ground, v.damage);
               STARS.add(star);
               com.fortuneandfavors.net.FfVfx.shape(v.level, com.fortuneandfavors.net.FfVfx.METEOR, ParticleTypes.END_ROD, star.from, star.target, 0.0, STARFALL_FALL_TICKS, 0xB8A0FF);
               v.launched++;
            }
         } else {
            it.remove();
         }
      }
   }

   /** Every star on its way down: a trail, a soft pull, then the landing. */
   private static void tickStars() {
      Iterator<Star> it = STARS.iterator();
      while (it.hasNext()) {
         Star s = it.next();
         if (--s.ticksLeft <= 0) {
            it.remove();
            impactStar(s);
            continue;
         }
         double p = 1.0 - (double)s.ticksLeft / (double)STARFALL_FALL_TICKS;
         Vec3 pos = s.from.add(s.target.subtract(s.from).scale(p));
         com.fortuneandfavors.net.FfVfx.enter();
         com.fortuneandfavors.net.FfVfx.particles(s.level, ParticleTypes.END_ROD, pos.x, pos.y, pos.z, 6, 0.15, 0.4, 0.15, 0.1);
         com.fortuneandfavors.net.FfVfx.particles(s.level, ParticleTypes.FIREWORK, pos.x, pos.y, pos.z, 3, 0.1, 0.3, 0.1, 0.05);
         if (s.ticksLeft % 4 == 0) {
            com.fortuneandfavors.net.FfVfx.particles(s.level, ParticleTypes.PORTAL, pos.x, pos.y, pos.z, 4, 0.2, 0.4, 0.2, 0.4);
         }
         com.fortuneandfavors.net.FfVfx.exit();
         // No softAim: it moved the landing after the client had been told where the star falls.
      }
   }

   /**
    * The soft auto-aim.
    *
    * <p>A star drifts toward the nearest body under it, a little per tick, so a volley aimed at a
    * spot still lands on somebody who stayed in it - but only a little, so stepping out of the
    * circle is still the answer. Deliberately not enough to follow a sprint.
    */
   private static void softAim(Star s) {
      ServerPlayer best = null;
      double bestD = STARFALL_HOMING_REACH * STARFALL_HOMING_REACH;
      for (ServerPlayer p : s.level.getPlayers(pl -> pl.isAlive() && !pl.isSpectator() && pl != s.caster)) {
         double d = p.position().distanceToSqr(s.target);
         if (d < bestD) {
            bestD = d;
            best = p;
         }
      }
      if (best == null) {
         return;
      }
      Vec3 drift = best.position().subtract(s.target);
      s.target = s.target.add(drift.x * STARFALL_HOMING, 0.0, drift.z * STARFALL_HOMING);
   }

   /** One star landing: an impact, a shockwave of the End's own light, and what stood there. */
   private static void impactStar(Star s) {
      Vec3 p = s.target;
      com.fortuneandfavors.net.FfVfx.shape(s.level, com.fortuneandfavors.net.FfVfx.NOVA, ParticleTypes.END_ROD, p, Vec3.ZERO, STARFALL_IMPACT_RADIUS + 1.0, 0.0, 0xE8DCFF);
      com.fortuneandfavors.net.FfVfx.particles(s.level, ParticleTypes.EXPLOSION, p.x, p.y + 0.2, p.z, 1, 0.0, 0.0, 0.0, 0.0);
      com.fortuneandfavors.net.FfVfx.particles(s.level, ParticleTypes.END_ROD, p.x, p.y + 0.4, p.z, 34, 0.7, 0.5, 0.7, 0.35);
      com.fortuneandfavors.net.FfVfx.particles(s.level, ParticleTypes.PORTAL, p.x, p.y + 0.4, p.z, 24, 0.9, 0.5, 0.9, 0.5);
      for (LivingEntity e : s.level.getEntitiesOfClass(
         LivingEntity.class, new AABB(p, p).inflate(STARFALL_IMPACT_RADIUS),
         e -> e != s.caster && e.isAlive() && !e.isSpectator()
      )) {
         e.hurtServer(s.level, s.level.damageSources().playerAttack(s.caster), s.damage);
         // Struck from the sky: popped up and slowed for a moment.
         e.push(0.0, 0.35, 0.0);
         e.hurtMarked = true;
         e.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 40, 0, false, true));
      }
      s.level.playSound(null, p.x, p.y, p.z, SoundEvents.LIGHTNING_BOLT_IMPACT, SoundSource.PLAYERS, 1.2F, 0.9F);
   }

   /** The weight comes back all at once. */
   private static void tickWeightless() {
      Iterator<Weightless> it = WEIGHTLESS.iterator();
      while (it.hasNext()) {
         Weightless w = it.next();
         if (--w.ticksLeft > 0) {
            // Still holding the room up, so the bodies in it are still rising: remember how high
            // each one gets, because that is what its landing is about to be worth.
            w.trackLift();
            // Drawn together while they float, so the weight comes back on one pile.
            for (LivingEntity e : w.caught()) {
               Vec3 in = new Vec3(w.at.x - e.getX(), 0.0, w.at.z - e.getZ());
               if (in.lengthSqr() > 1.0) {
                  e.setDeltaMovement(e.getDeltaMovement().add(in.normalize().scale(0.06)));
                  e.hurtMarked = true;
               }
            }
            continue;
         }
         it.remove();
         DamageSource source = w.level.damageSources().playerAttack(w.caster);
         for (LivingEntity e : w.caught()) {
            e.removeEffect(MobEffects.LEVITATION);
            e.setDeltaMovement(e.getDeltaMovement().x, -2.2, e.getDeltaMovement().z);
            e.hurtMarked = true;
            // The weight coming back is worth how high the lift carried this body, which is the fall
            // it is about to take. This is the "them" half of the rule: what the room was raised is
            // what the room pays.
            double fall = w.fallOf(e);
            e.hurtServer(w.level, source, w.damage + fallBonus(w.level, w.weapon, e, source, fall));
         }
         com.fortuneandfavors.net.FfVfx.shape(w.level, com.fortuneandfavors.net.FfVfx.ROCKBURST, ParticleTypes.CLOUD, w.at, Vec3.ZERO, w.radius, 0.0, 0x2A1A3A);
         com.fortuneandfavors.net.FfVfx.shape(w.level, com.fortuneandfavors.net.FfVfx.NOVA, ParticleTypes.REVERSE_PORTAL, w.at, Vec3.ZERO, w.radius + 2.0, 0.0, 0xB06BFF);
         com.fortuneandfavors.net.FfVfx.particles(w.level, ParticleTypes.EXPLOSION_EMITTER, w.at.x, w.at.y + 1.0, w.at.z, 1, 0.0, 0.0, 0.0, 0.0);
         com.fortuneandfavors.net.FfVfx.particles(w.level, ParticleTypes.PORTAL, w.at.x, w.at.y + 0.4, w.at.z, 120, w.radius * 0.6, 1.0, w.radius * 0.6, 0.6);
         w.level.playSound(null, w.at.x, w.at.y, w.at.z, SoundEvents.GENERIC_EXPLODE, SoundSource.PLAYERS, 1.6F, 0.6F);
         w.level.playSound(null, w.at.x, w.at.y, w.at.z, SoundEvents.WARDEN_SONIC_BOOM, SoundSource.PLAYERS, 1.0F, 0.8F);
         bar(w.caster, "§5Gravity Break §8| §7and it comes back");
      }
   }

   /** An airborne Enderfall: hold at the top, then come down like the dragon. */
   private static void tickDives(MinecraftServer server) {
      Iterator<Map.Entry<UUID, Dive>> it = DIVES.entrySet().iterator();
      while (it.hasNext()) {
         Map.Entry<UUID, Dive> entry = it.next();
         Dive d = entry.getValue();
         ServerPlayer player = server.getPlayerList().getPlayer(d.owner);
         if (player == null || !player.isAlive() || player.level() != d.level) {
            it.remove();
            continue;
         }
         d.ticks++;
         if (!d.falling) {
            if (d.ticks >= ENDERFALL_LIFT_TICKS) {
               d.falling = true;
               player.setDeltaMovement(player.getDeltaMovement().x, -2.6, player.getDeltaMovement().z);
               player.hurtMarked = true;
               player.resetFallDistance();
               com.fortuneandfavors.net.FfVfx.particles(d.level, ParticleTypes.END_ROD, player.getX(), player.getY(), player.getZ(), 50, 0.5, 0.5, 0.5, 0.15);
               bar(player, "§5Enderfall §8| §7coming down");
            }
            continue;
         }
         boolean landed = player.onGround() || player.isInWater() || d.ticks >= ENDERFALL_MAX_TICKS;
         if (!landed) {
            com.fortuneandfavors.net.FfVfx.particles(d.level, ParticleTypes.PORTAL, player.getX(), player.getY() + 1.0, player.getZ(), 6, 0.3, 0.5, 0.3, 0.1);
            continue;
         }
         it.remove();
         crash(d, player);
      }
   }

   /** The landing: a large area of harm and a hard shove outward. */
   private static void crash(Dive d, ServerPlayer player) {
      Vec3 at = player.position();
      // The dive's own fall, read before the landing clears it: Enderfall throws its holder up, so
      // the crash is worth whatever height that bought - the "you" half of the rule. The peak of the
      // last few ticks rather than the current fall distance, because the landing tick is exactly
      // the tick vanilla is already busy consuming it on.
      double fall = recentFall(player);
      DamageSource source = d.level.damageSources().playerAttack(player);
      int hit = 0;
      for (LivingEntity e : inSphere(d.level, at, d.radius, player)) {
         Vec3 away = e.position().subtract(at);
         Vec3 flat = new Vec3(away.x, 0.0, away.z);
         if (flat.lengthSqr() < 1.0E-4) {
            flat = new Vec3(RANDOM.nextDouble() - 0.5, 0.0, RANDOM.nextDouble() - 0.5);
         }
         Vec3 push = flat.normalize().scale(1.4);
         e.push(push.x, 0.7, push.z);
         e.hurtMarked = true;
         e.hurtServer(d.level, source, d.damage + fallBonus(d.level, d.weapon, e, source, fall));
         hit++;
      }
      player.resetFallDistance();
      com.fortuneandfavors.net.FfVfx.shape(d.level, com.fortuneandfavors.net.FfVfx.ROCKBURST, ParticleTypes.CLOUD, at, Vec3.ZERO, d.radius, 0.0, 0x2A1A3A);
      com.fortuneandfavors.net.FfVfx.shape(d.level, com.fortuneandfavors.net.FfVfx.NOVA, ParticleTypes.END_ROD, at, Vec3.ZERO, d.radius + 3.0, 0.0, 0xB06BFF);
      com.fortuneandfavors.net.FfVfx.particles(d.level, ParticleTypes.EXPLOSION_EMITTER, at.x, at.y + 0.4, at.z, 3, 2.5, 0.4, 2.5, 0.0);
      com.fortuneandfavors.net.FfVfx.particles(d.level, ParticleTypes.END_ROD, at.x, at.y + 0.4, at.z, 140, 3.0, 0.6, 3.0, 0.5);
      com.fortuneandfavors.net.FfVfx.particles(d.level, ParticleTypes.PORTAL, at.x, at.y + 0.4, at.z, 160, 3.2, 0.8, 3.2, 0.8);
      d.level.playSound(null, at.x, at.y, at.z, SoundEvents.GENERIC_EXPLODE, SoundSource.PLAYERS, 2.0F, 0.5F);
      d.level.playSound(null, at.x, at.y, at.z, SoundEvents.ENDER_DRAGON_GROWL, SoundSource.PLAYERS, 1.6F, 0.6F);
      bar(player, "§5Enderfall §8| §7impact §8- §f" + hit + " §7caught");
   }

   /** Drop marks that have gone stale, so a long session's map does not grow forever. */
   private static void prune(long now) {
      MARKS.entrySet().removeIf(e -> now >= e.getValue().expiresAt && now >= e.getValue().detonateReadyAt);
      ASTRAL.entrySet().removeIf(e -> now >= e.getValue().expiresAt);
      RECENT_FALL_AT.entrySet().removeIf(e -> now - e.getValue() > 40L);
      RECENT_FALL.keySet().removeIf(id -> !RECENT_FALL_AT.containsKey(id));
   }

   // ------------------------------------------------------------ fall tracking (Impact)

   /**
    * The fall distance a player had a moment ago, because the damage event has none left.
    *
    * <p>Enderheart's Impact is "the farther you fall, the harder it lands", and the obvious way to
    * read that - {@code hitter.fallDistance} inside the damage event - is the one way that cannot
    * work: vanilla's mace consumes and resets the fall before an aftermath event ever sees it, so
    * the passive read zero and the shockwave never fired. This records what the player was falling
    * with on every tick, and the blow reads the peak of the last handful of ticks instead.
    */
   private static final Map<UUID, Float> RECENT_FALL = new HashMap<>();
   private static final Map<UUID, Long> RECENT_FALL_AT = new HashMap<>();
   private static final long RECENT_FALL_WINDOW = 8L;

   private static void tickFallTracking(MinecraftServer server) {
      try {
         for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            float f = (float)p.fallDistance;
            if (f > 0.0F) {
               RECENT_FALL.merge(p.getUUID(), f, Math::max);
               RECENT_FALL_AT.put(p.getUUID(), p.level().getGameTime());
            }
         }
      } catch (Throwable ignored) {
      }
   }

   /** The fall a blow should be credited with: the peak of the last few ticks, or what is left. */
   private static float recentFall(ServerPlayer p) {
      long now = p.level().getGameTime();
      long at = RECENT_FALL_AT.getOrDefault(p.getUUID(), Long.MIN_VALUE);
      if (now - at > RECENT_FALL_WINDOW) {
         return (float)p.fallDistance;
      }
      return Math.max((float)p.fallDistance, RECENT_FALL.getOrDefault(p.getUUID(), 0.0F));
   }

   /**
    * Starfall's cooldown, read on the action bar.
    *
    * <p>The ability does not use the vanilla item cooldown (see {@link #coolSoft}), so this is
    * where a player finds out when the sky will fill again - only while they are actually holding
    * the bow, and on a slow clock so it does not fight the boss line for the bar.
    */
   private static void tickStarCooldown(MinecraftServer server) {
      if (server.getTickCount() % 10 != 0) {
         return;
      }
      try {
         for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            ItemStack held = p.getMainHandItem();
            if (!ModItems.isAnyStarfall(held)) {
               continue;
            }
            long left = ModItems.cooldownSecondsLeft(held, p.level().getGameTime());
            if (left > 0L) {
               bar(p, "§5Starfall §8| §7recharging §8- §f" + left + "s");
            }
         }
      } catch (Throwable ignored) {
      }
   }

   // ------------------------------------------------------------------ test hooks

   /** Test-only: what a Starfall volley does when it lands, so the numbers cannot drift. */
   public static int starfallStarCount() {
      return STARFALL_STARS;
   }

   /**
    * Test-only: the awakened tier's numbers, so "the upgrade does something" is a pinned fact
    * rather than a claim in a lore line.
    */
   public static int starfallStarCountAwakened() {
      return STARFALL_STARS_AWAKENED;
   }

   /** Test-only: the awakened bow's burst threshold. */
   public static int astralMarksAwakened() {
      return ASTRAL_MARKS_AWAKENED;
   }

   /** Test-only: the awakened blade's rift cooldown, in ticks. */
   public static int riftSlashCooldownAwakened() {
      return RIFT_SLASH_COOLDOWN_TICKS_AWAKENED;
   }

   /** Test-only: the awakened blade's rift cooldown, in ticks. */
   public static int riftSlashCooldown() {
      return RIFT_SLASH_COOLDOWN_TICKS;
   }

   /** Test-only: how long the charged shot takes to come down. */
   public static int starfallFallTicks() {
      return STARFALL_FALL_TICKS;
   }

   /** Test-only: the mace's own clock, in ticks - the lever's half of it. */
   public static int gravityBreakCooldown() {
      return GRAVITY_BREAK_COOLDOWN_SECONDS * 20;
   }

   /** Test-only: the awakened mace's clock for the lever. */
   public static int gravityBreakCooldownAwakened() {
      return GRAVITY_BREAK_COOLDOWN_SECONDS_AWAKENED * 20;
   }

   /** Test-only: the mace's own clock, in ticks - the dive's half of it. */
   public static int enderfallCooldown() {
      return ENDERFALL_COOLDOWN_SECONDS * 20;
   }

   /** Test-only: the awakened mace's clock for the dive. */
   public static int enderfallCooldownAwakened() {
      return ENDERFALL_COOLDOWN_SECONDS_AWAKENED * 20;
   }

   /** Test-only: what one rift is worth, and what it costs. */
   public static float riftSlashDamage() {
      return RIFT_SLASH_DAMAGE;
   }

   /** Test-only: the awakened blade's rift. */
   public static float riftSlashDamageAwakened() {
      return RIFT_SLASH_DAMAGE_AWAKENED;
   }

   /** Test-only: what the third mark is worth. */
   public static float voidHungerDamage() {
      return VOID_HUNGER_DAMAGE;
   }

   /** Test-only: how long a mark waits for the next hit. */
   public static int voidHungerMarkTicks() {
      return VOID_HUNGER_MARK_TICKS;
   }

   /** Test-only: the dead time after a detonation, which the awakened tier shortens. */
   public static int voidHungerDetonateGap() {
      return VOID_HUNGER_DETONATE_GAP;
   }

   /** Test-only: the awakened blade's dead time. */
   public static int voidHungerDetonateGapAwakened() {
      return VOID_HUNGER_DETONATE_GAP_AWAKENED;
   }

   /** Test-only: what one star is worth, and what the bow's mark bursts for. */
   public static float starfallDamage() {
      return STARFALL_DAMAGE;
   }

   /** Test-only: the awakened bow's star. */
   public static float starfallDamageAwakened() {
      return STARFALL_DAMAGE_AWAKENED;
   }

   /** Test-only: what an Astral Mark bursts for. */
   public static float astralBurstDamage() {
      return ASTRAL_BURST_DAMAGE;
   }

   /** Test-only: what the dive's landing is worth before the fall is added. */
   public static float enderfallDamage() {
      return ENDERFALL_DAMAGE;
   }

   /** Test-only: the awakened dive's landing, before the fall. */
   public static float enderfallDamageAwakened() {
      return ENDERFALL_DAMAGE_AWAKENED;
   }
}
