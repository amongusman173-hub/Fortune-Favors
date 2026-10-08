package com.fortuneandfavors.economy;

import com.fortuneandfavors.ModItems;
import com.fortuneandfavors.ModSounds;
import com.fortuneandfavors.util.Chat;
import com.fortuneandfavors.util.Safe;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * The Clockwork King's three legendaries, as equipment behaviour rather than as
 * item definitions - the same split {@link ScarletGear} uses for the Scarlet
 * Devil's set.
 *
 * <ul>
 *   <li><b>Clockwork Gauntlet</b> - every few landed hits winds an <em>Overdrive</em>
 *       charge; at full charge the next hit drives a piston ram straight through
 *       whatever is in front of you.</li>
 *   <li><b>Mechanical Heart</b> - while carried it ticks a slow self-repair, but
 *       only while you are <em>not</em> being hit: it rewards breaking contact
 *       instead of out-tanking the boss.</li>
 *   <li><b>Automaton Armor</b> - worn, it plates you (Resistance) and refuses to
 *       burn (Fire Resistance).</li>
 * </ul>
 *
 * <p>All three are deliberately right-click-inert, so none of them appears in the
 * right-click dispatch table: their abilities are on-hit and on-tick, which is
 * exactly what {@code PASSIVE_TYPES} in {@code ModEvents} declares them to be.
 */
public final class ClockworkGear {

   /** Landed hits needed to fill the Gauntlet's Overdrive charge. */
   private static final int OVERDRIVE_HITS = 4;
   /** How far the piston ram reaches, in blocks. */
   private static final double RAM_RANGE = 6.0;
   /** Half-width of the ram's line, so a near-miss still connects. */
   private static final double RAM_WIDTH = 1.6;
   private static final float RAM_DAMAGE = 14.0F;

   /** Ticks the Mechanical Heart needs between repairs. */
   private static final int HEART_REPAIR_TICKS = 60;
   /** Repair only starts this long after the last hit taken, in ticks. */
   private static final int HEART_CALM_TICKS = 80;
   private static final float HEART_REPAIR = 3.0F;

   /** Brass, the colour every Clockwork effect is drawn in. */
   private static final int BRASS = 0xE2B042;
   /** The blue of the King's soul-fire, for the spark at the heart of the ram. */
   private static final int SOULFIRE = 0x3FD8FF;

   private static final Map<UUID, Integer> OVERDRIVE = new HashMap<>();
   private static final Map<UUID, Long> LAST_HURT = new HashMap<>();
   private static final Map<UUID, Long> LAST_REPAIR = new HashMap<>();

   private ClockworkGear() {
   }

   // ------------------------------------------------------------------ gauntlet

   /**
    * A landed hit with the Gauntlet. Fills the Overdrive charge and, once it is
    * full, spends it on a ram. Called from the damage hook for the attacking
    * player, so it only ever fires on hits that actually connected.
    */
   public static void onGauntletHit(ServerPlayer attacker, LivingEntity victim) {
      if (attacker == null || victim == null || !attacker.isAlive()) {
         return;
      }
      int charge = OVERDRIVE.merge(attacker.getUUID(), 1, Integer::sum);
      if (charge < OVERDRIVE_HITS) {
         return;
      }
      OVERDRIVE.put(attacker.getUUID(), 0);

      Vec3 eye = attacker.getEyePosition();
      Vec3 look = attacker.getViewVector(1.0F);
      Vec3 flat = new Vec3(look.x, 0.0, look.z);
      if (flat.lengthSqr() < 1.0E-4) {
         flat = new Vec3(0.0, 0.0, 1.0);
      }
      flat = flat.normalize();

      if (!(attacker.level() instanceof ServerLevel level)) {
         return;
      }

      // Trace the ram as a line, not a sphere: it should feel like a piston
      // punching forward rather than a fireball going off at your feet.
      Vec3 end = eye.add(flat.scale(RAM_RANGE));
      // A piston of brass and soul-fire punched straight out along the line, a shockwave where it
      // stops. Five shape cues; the old ram sent fifty particle packets to draw the same line.
      Vec3 low = eye.add(0.0, -0.3, 0.0);
      com.fortuneandfavors.net.FfVfx.shape(level, com.fortuneandfavors.net.FfVfx.MUZZLE, ParticleTypes.ELECTRIC_SPARK, low.add(flat.scale(0.8)), flat, 0.0, 0.0, SOULFIRE);
      com.fortuneandfavors.net.FfVfx.shape(level, com.fortuneandfavors.net.FfVfx.BEAM, ParticleTypes.ELECTRIC_SPARK, low, end.add(0.0, -0.3, 0.0), 0.0, 0.0, BRASS);
      com.fortuneandfavors.net.FfVfx.shape(level, com.fortuneandfavors.net.FfVfx.SLASH, ParticleTypes.CRIT, attacker.position(), flat, RAM_RANGE, 0.0, BRASS);
      com.fortuneandfavors.net.FfVfx.shape(level, com.fortuneandfavors.net.FfVfx.ROCKBURST, ParticleTypes.CLOUD, end.add(0.0, -0.3, 0.0), Vec3.ZERO, RAM_WIDTH + 0.6, 0.0, 0xD8C8A8);
      com.fortuneandfavors.net.FfVfx.shape(level, com.fortuneandfavors.net.FfVfx.CLOCK_BURST, ParticleTypes.ELECTRIC_SPARK, end.add(0.0, -0.3, 0.0), Vec3.ZERO, RAM_WIDTH + 0.4, 0.0, BRASS);
      level.playSound(null, attacker.getX(), attacker.getY(), attacker.getZ(), ModSounds.BOSS_SLAM, SoundSource.PLAYERS, 1.1F, 1.5F);
      level.playSound(null, attacker.getX(), attacker.getY(), attacker.getZ(), SoundEvents.ANVIL_LAND, SoundSource.PLAYERS, 0.8F, 1.2F);

      int struck = 0;
      for (LivingEntity target : lineTargets(level, attacker, eye, flat)) {
         // The ram is a genuine piston: it ignores the i-frame window so the
         // victim cannot simply walk off one tick of it.
         target.invulnerableTime = 0;
         target.hurtServer(level, level.damageSources().playerAttack(attacker), RAM_DAMAGE);
         Vec3 away = target.position().subtract(attacker.position());
         if (away.lengthSqr() < 0.01) {
            away = flat;
         }
         away = new Vec3(away.x, 0.0, away.z).normalize();
         target.push(away.x * 1.5, 0.45, away.z * 1.5);
         target.hurtMarked = true;
         struck++;
      }
      if (struck > 0) {
         attacker.sendOverlayMessage(Component.literal(Chat.colorize("&6Overdrive ram &7- &f" + struck + " &7struck")));
      }
   }

   /** Everything alive in the ram's forward line, excluding the attacker's allies. */
   private static List<LivingEntity> lineTargets(ServerLevel level, ServerPlayer attacker, Vec3 from, Vec3 flat) {
      List<LivingEntity> out = new ArrayList<>();
      for (Entity e : level.getEntities(attacker, attacker.getBoundingBox().inflate(RAM_RANGE + 2.0))) {
         if (!(e instanceof LivingEntity living) || living == attacker || !living.isAlive()) {
            continue;
         }
         if (living instanceof ServerPlayer other && isAlly(attacker, other)) {
            continue;
         }
         // Never the owner's own summons, and never through a wall: the ram used to strike whatever
         // was in the line, including mobs on the far side of solid stone.
         if (BossManager.isFriendlySkeleton(living)) {
            continue;
         }
         Vec3 centre = living.position().add(0.0, living.getBbHeight() * 0.5, 0.0);
         if (level.clip(new net.minecraft.world.level.ClipContext(from, centre, net.minecraft.world.level.ClipContext.Block.COLLIDER,
               net.minecraft.world.level.ClipContext.Fluid.NONE, attacker)).getType() != net.minecraft.world.phys.HitResult.Type.MISS) {
            continue;
         }
         Vec3 to = living.position().subtract(from);
         double along = to.x * flat.x + to.z * flat.z;
         if (along < -0.5 || along > RAM_RANGE) {
            continue;
         }
         double lateral = Math.abs(to.x * flat.z - to.z * flat.x);
         if (lateral <= RAM_WIDTH + living.getBbWidth() * 0.5) {
            out.add(living);
         }
      }
      return out;
   }

   /** Party/team check reused from the rest of the mod's friendly-fire rules. */
   private static boolean isAlly(ServerPlayer a, ServerPlayer b) {
      return ScarletGear.isAlly(a, b);
   }

   // ----------------------------------------------------------------- tick loop

   /** Per-tick upkeep for the carried Heart and the worn Armor. */
   public static void tick(MinecraftServer server) {
      if (server == null) {
         return;
      }
      long now = ServerClock.clock(server.overworld());
      for (ServerPlayer p : server.getPlayerList().getPlayers()) {
         Safe.run("clockwork gear tick", () -> tickPlayer(p, now));
      }
      // A player who logged out or died should not keep a charge waiting for them.
      if (OVERDRIVE.size() > 64) {
         OVERDRIVE.keySet().removeIf(id -> server.getPlayerList().getPlayer(id) == null);
      }
   }

   private static void tickPlayer(ServerPlayer player, long now) {
      if (!player.isAlive()) {
         OVERDRIVE.remove(player.getUUID());
         return;
      }

      // ---- Automaton Armor: plating and fireproofing while worn.
      if (ModItems.isAutomatonArmor(player.getItemBySlot(EquipmentSlot.CHEST))) {
         // Topped up, not re-sent every tick.
         topUp(player, MobEffects.RESISTANCE);
         topUp(player, MobEffects.FIRE_RESISTANCE);
      }

      // ---- Mechanical Heart: repair, but only out of combat.
      if (carriesHeart(player)) {
         long hurt = LAST_HURT.getOrDefault(player.getUUID(), Long.MIN_VALUE / 4L);
         boolean calm = now - hurt >= HEART_CALM_TICKS;
         if (!calm || player.getHealth() >= player.getMaxHealth()) {
            return;
         }
         long last = LAST_REPAIR.getOrDefault(player.getUUID(), Long.MIN_VALUE / 4L);
         if (now - last < HEART_REPAIR_TICKS) {
            return;
         }
         LAST_REPAIR.put(player.getUUID(), now);
         player.heal(HEART_REPAIR);
         if (player.level() instanceof ServerLevel level) {
            com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.HEART, player.getX(), player.getY() + 1.4, player.getZ(), 3, 0.3, 0.2, 0.3, 0.0);
            // A gear turns once over the heart with every repair.
            com.fortuneandfavors.net.FfVfx.shape(level, com.fortuneandfavors.net.FfVfx.CLOCK_BURST, ParticleTypes.ELECTRIC_SPARK, player.position().add(0.0, 1.2, 0.0), Vec3.ZERO, 0.9, 0.0, BRASS);
            level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 0.5F, 1.6F);
         }
      }
   }

   private static void topUp(ServerPlayer player, net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect> effect) {
      MobEffectInstance current = player.getEffect(effect);
      if (current == null || current.getDuration() < 20) {
         player.addEffect(new MobEffectInstance(effect, 60, 0, false, false, true));
      }
   }

   private static boolean carriesHeart(ServerPlayer player) {
      for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
         if (ModItems.isMechanicalHeart(player.getInventory().getItem(i))) {
            return true;
         }
      }
      return false;
   }

   /** Called from the damage hook so the Heart knows when you were last hit. */
   public static void onPlayerDamaged(ServerPlayer player) {
      if (player != null) {
         LAST_HURT.put(player.getUUID(), ServerClock.clock(player.level()));
      }
   }

   public static void onPlayerDisconnect(UUID id) {
      OVERDRIVE.remove(id);
      LAST_HURT.remove(id);
      LAST_REPAIR.remove(id);
   }

   public static void clear() {
      OVERDRIVE.clear();
      LAST_HURT.clear();
      LAST_REPAIR.clear();
   }

   /** Test hook: the charge needed to fire the ram. */
   public static int overdriveHits() {
      return OVERDRIVE_HITS;
   }

   /** Test hook: the ram's reach, so the documented range cannot drift. */
   public static double ramRange() {
      return RAM_RANGE;
   }
}
