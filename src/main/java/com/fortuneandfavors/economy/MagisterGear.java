package com.fortuneandfavors.economy;

import com.fortuneandfavors.ModItems;
import com.fortuneandfavors.util.Chat;
import com.fortuneandfavors.util.Safe;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ColorParticleOption;
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
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * The Starbound Magister's three legendaries.
 *
 * <ul>
 *   <li><b>Starpiercer</b> - every third landed hit fires a piercing star along
 *       your line of sight.</li>
 *   <li><b>Astral Mantle</b> - worn, it grants Slow Falling and a chance to blink
 *       backward out of a heavy blow. <b>Double-tap sneak</b> and you Astral Step
 *       forward on a 15 second cooldown (an armour piece cannot also be a
 *       right-click item - right-clicking it is how you put it on).</li>
 *   <li><b>Magister's Codex</b> - right-click casts the chosen spell,
 *       sneak-right-click turns the page. Star Bolt, Gravity, Meteor.</li>
 * </ul>
 */
public final class MagisterGear {

   private static final String[] SPELLS = {"Star Bolt", "Gravity", "Meteor"};
   private static final int[] SPELL_COOLDOWN_SECONDS = {2, 9, 7};
   private static final int STAR_EVERY_N_HITS = 3;
   private static final float STAR_DAMAGE = 11.0F;
   private static final long STEP_COOLDOWN_TICKS = 300L;
   private static final double STEP_DISTANCE = 7.0;
   /** Two sneak taps inside this window count as a double-tap. */
   private static final long DOUBLE_TAP_TICKS = 9L;
   private static final double BLINK_CHANCE = 0.35;
   private static final float BLINK_MIN_DAMAGE = 7.0F;

   /** Starlight: the pale blue every Magister effect is drawn in. */
   private static final int STARLIGHT = 0x99EEFF;
   /** The gold at the heart of a star. */
   private static final int STARGOLD = 0xFFE7A0;
   /** The violet of bent space, for Gravity and the Step. */
   private static final int WARP = 0x8C5CFF;
   /** How long a Meteor takes to land after it is called. */
   private static final int METEOR_FUSE = 26;
   private static final double METEOR_RADIUS = 3.6;
   private static final float METEOR_DAMAGE = 14.0F;

   private static final Map<UUID, Integer> SPELL = new HashMap<>();
   private static final Map<UUID, Integer> HITS = new HashMap<>();
   private static final Map<UUID, Long> COOLDOWN_UNTIL = new HashMap<>();
   private static final Map<UUID, Long> LAST_SNEAK = new HashMap<>();
   private static final Map<UUID, Boolean> SNEAKING = new HashMap<>();
   private static final Map<UUID, Long> STEP_UNTIL = new HashMap<>();

   /** A scheduled meteor: a point on the ground that is about to be hit. */
   private static final class Meteor {
      final Vec3 pos;
      int fuse;
      final UUID owner;
      /**
       * The world it was called down in. It used to be looked up from the caster each tick, so
       * a caster who stepped through a portal during the fuse dropped the meteor on the same
       * coordinates in the other world - and one who logged off cancelled it in mid-air.
       */
      final ServerLevel level;

      Meteor(Vec3 pos, int fuse, UUID owner, ServerLevel level) {
         this.pos = pos;
         this.fuse = fuse;
         this.owner = owner;
         this.level = level;
      }
   }

   private static final List<Meteor> METEORS = new ArrayList<>();
   private static final Random RANDOM = new Random();

   private MagisterGear() {
   }

   // -------------------------------------------------------------------- codex

   public static boolean isCodexSpellItem(ItemStack stack) {
      return ModItems.isMagistersCodex(stack);
   }

   /** Right-click with the Codex: sneak turns the page, otherwise cast. */
   public static String useCodex(ServerPlayer player, ItemStack held) {
      if (player.isShiftKeyDown()) {
         String next = cycleSpell(player);
         bar(player, "&bCodex turned to &f" + next);
         return null;
      }
      String spell = currentSpell(player);
      long now = ServerClock.clock(player.level());
      Long until = COOLDOWN_UNTIL.get(player.getUUID());
      if (until != null && now < until) {
         bar(player, "&7" + spell + " recovers in &f" + ((until - now + 19) / 20) + "s");
         return null;
      }
      return switch (spell) {
         case "Gravity" -> {
            castGravity(player);
            COOLDOWN_UNTIL.put(player.getUUID(), now + SPELL_COOLDOWN_SECONDS[1] * 20L);
            yield null;
         }
         case "Meteor" -> {
            castMeteor(player);
            COOLDOWN_UNTIL.put(player.getUUID(), now + SPELL_COOLDOWN_SECONDS[2] * 20L);
            yield null;
         }
         default -> {
            castStarBolt(player);
            COOLDOWN_UNTIL.put(player.getUUID(), now + SPELL_COOLDOWN_SECONDS[0] * 20L);
            yield null;
         }
      };
   }

   private static String cycleSpell(ServerPlayer player) {
      int next = (SPELL.getOrDefault(player.getUUID(), 0) + 1) % SPELLS.length;
      SPELL.put(player.getUUID(), next);
      return SPELLS[next];
   }

   private static String currentSpell(ServerPlayer player) {
      return SPELLS[SPELL.getOrDefault(player.getUUID(), 0)];
   }

   private static void castStarBolt(ServerPlayer player) {
      if (!(player.level() instanceof ServerLevel level)) {
         return;
      }
      Vec3 from = player.getEyePosition().subtract(0.0, 0.1, 0.0);
      for (int i = -1; i <= 1; i++) {
         Vec3 aim = rotate(player.getViewVector(1.0F), i * 4.0);
         fireStar(level, player, from, aim, 6.0F, 1.5);
      }
      // Three stars leave the page in a spray of rays and a gold bloom, the Magister's own
      // casting flash. (It used to be a clock burst - the Time Lord's cue, not hers.) The
      // vanilla flash is for clients without the mod only; a bare FfVfx.particles reached all.
      Vec3 page = from.add(player.getViewVector(1.0F).scale(0.8));
      Fx.starburst(level, ParticleTypes.END_ROD, page, 1.4, STARLIGHT);
      Fx.flare(level, ParticleTypes.END_ROD, page, 0.6, STARGOLD);
      Fx.vanilla(level, ColorParticleOption.create(ParticleTypes.FLASH, STARLIGHT), from.x, from.y, from.z, 1, 0.0, 0.0, 0.0, 0.0);
      level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.EVOKER_CAST_SPELL, SoundSource.PLAYERS, 0.9F, 1.5F);
      bar(player, "&bStar Bolt");
   }

   private static void castGravity(ServerPlayer player) {
      if (!(player.level() instanceof ServerLevel level)) {
         return;
      }
      Vec3 centre = player.getEyePosition().add(player.getViewVector(1.0F).scale(6.0));
      int caught = 0;
      for (Entity e : level.getEntities(player, player.getBoundingBox().inflate(14.0))) {
         if (!(e instanceof LivingEntity living) || living == player || !living.isAlive()) {
            continue;
         }
         // Spectators are not in the world to be pulled, and the well used to drag (and hurt)
         // anyone watching in spectator mode.
         if (living instanceof ServerPlayer other && (other.isSpectator() || ScarletGear.isAlly(player, other))) {
            continue;
         }
         Vec3 pull = centre.subtract(living.position());
         double len = pull.length();
         if (len < 0.5) {
            continue;
         }
         Vec3 unit = pull.scale(1.0 / len);
         // Heavy things are pulled less: the well is not a way to drag a boss out of its arena.
         double weight = 1.0 - Math.max(0.0, Math.min(1.0, living.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE)));
         if (BossManager.isMarkedBoss(living)) {
            weight = Math.min(weight, 0.25);
         }
         weight = Math.max(0.15, weight);
         living.push(unit.x * 0.9 * weight, 0.25 * weight, unit.z * 0.9 * weight);
         living.hurtMarked = true;
         living.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 90, 2, false, true, true));
         living.hurtServer(level, level.damageSources().playerAttack(player), 3.0F);
         if (caught < 6) {
            // Capped so a crowd is six threads, not sixty.
            Fx.soulStream(level, ParticleTypes.END_ROD, living.position().add(0.0, living.getBbHeight() * 0.5, 0.0), centre, 0.4, 14, STARLIGHT);
         }
         caught++;
      }
      // A well opens in the air and everything in reach falls sideways into it: the funnel
      // turns, the dark motes of bent space implode into the centre over a second and throw a
      // ring back out, and a thread of starlight runs from each body caught to the well.
      Fx.vortex(level, ParticleTypes.REVERSE_PORTAL, centre, 6.0, 30, WARP);
      Fx.voidCollapse(level, ParticleTypes.REVERSE_PORTAL, centre, 7.0, 20, WARP);
      Fx.shape(level, com.fortuneandfavors.net.FfVfx.RING, ParticleTypes.END_ROD, centre, Vec3.ZERO, 3.5, 0.0, STARLIGHT);
      level.playSound(null, centre.x, centre.y, centre.z, SoundEvents.ENDER_DRAGON_FLAP, SoundSource.PLAYERS, 1.2F, 0.8F);
      bar(player, "&5Gravity &7- &f" + caught + " &7caught");
   }

   private static void castMeteor(ServerPlayer player) {
      if (!(player.level() instanceof ServerLevel level)) {
         return;
      }
      Vec3 dir = player.getViewVector(1.0F);
      Vec3 eye = player.getEyePosition();
      // The spot under the crosshair, up to 24 blocks out; the heightmap only when the look hits
      // nothing. The heightmap alone put a meteor cast in a cave on the grass above the cave.
      net.minecraft.world.phys.BlockHitResult look = level.clip(new net.minecraft.world.level.ClipContext(
         eye, eye.add(dir.scale(24.0)), net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, player
      ));
      Vec3 impact;
      if (look.getType() != net.minecraft.world.phys.HitResult.Type.MISS) {
         BlockPos hit = look.getBlockPos();
         impact = new Vec3(hit.getX() + 0.5, hit.getY() + 1.0, hit.getZ() + 0.5);
      } else {
         BlockPos ground = level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, BlockPos.containing(eye.add(dir.scale(12.0))));
         impact = new Vec3(ground.getX() + 0.5, ground.getY(), ground.getZ() + 0.5);
      }
      METEORS.add(new Meteor(impact, METEOR_FUSE, player.getUUID(), level));
      // Marked once and drawn by the client for the whole fuse - the old ring re-sent twenty-two
      // particle packets every tick until it landed - with the star already falling toward it.
      Fx.shape(level, com.fortuneandfavors.net.FfVfx.SUMMON_CIRCLE, ParticleTypes.END_ROD, impact.add(0.0, 0.1, 0.0), Vec3.ZERO, METEOR_RADIUS, METEOR_FUSE, STARLIGHT);
      Vec3 sky = impact.add(dir.x * -6.0, 22.0, dir.z * -6.0);
      Fx.comet(level, ParticleTypes.END_ROD, sky, impact, METEOR_FUSE, STARGOLD);
      // Small stars fall inside the ring with it, so the blast circle reads from any angle.
      Fx.starfall(level, ParticleTypes.END_ROD, impact, METEOR_RADIUS, METEOR_FUSE, STARLIGHT);
      level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.RESPAWN_ANCHOR_CHARGE, SoundSource.PLAYERS, 1.0F, 0.8F);
      bar(player, "&bMeteor &7- marked, clear the ring");
   }

   private static Vec3 rotate(Vec3 v, double degrees) {
      double rad = Math.toRadians(degrees);
      double cos = Math.cos(rad);
      double sin = Math.sin(rad);
      return new Vec3(v.x * cos - v.z * sin, v.y, v.x * sin + v.z * cos).normalize();
   }

   private static void fireStar(ServerLevel level, ServerPlayer owner, Vec3 from, Vec3 dir, float damage, double speed) {
      Arrow arrow = new Arrow(level, owner, new ItemStack(Items.ARROW), new ItemStack(Items.BOW));
      arrow.setPos(from.x, from.y, from.z);
      arrow.setDeltaMovement(dir.scale(speed));
      // An arrow hits for its speed times its base damage, so the base is divided back out:
      // Starpiercer's "11" star was hitting for 23, and each Star Bolt shard for 9.
      arrow.setBaseDamage(damage / Math.max(0.1, speed));
      arrow.setCritArrow(false);
      arrow.pickup = AbstractArrow.Pickup.DISALLOWED;
      level.addFreshEntity(arrow);
      Fx.shape(level, com.fortuneandfavors.net.FfVfx.MUZZLE, ParticleTypes.END_ROD, from, dir, 0.0, 0.0, STARLIGHT);
   }

   // ------------------------------------------------------------------ starpiercer

   /** Called from the damage hook when the Starpiercer lands a hit. */
   public static void onStarpiercerHit(ServerPlayer attacker, LivingEntity victim) {
      if (attacker == null || !(attacker.level() instanceof ServerLevel level)) {
         return;
      }
      int hits = HITS.merge(attacker.getUUID(), 1, Integer::sum);
      if (hits < STAR_EVERY_N_HITS) {
         return;
      }
      HITS.put(attacker.getUUID(), 0);
      Vec3 from = attacker.getEyePosition();
      Vec3 dir = attacker.getViewVector(1.0F).normalize();
      fireStar(level, attacker, from, dir, STAR_DAMAGE, 2.1);
      // The star leaves a lance of light down the line it was fired along, a gold comet head
      // racing down it, and the blade's last hit bursts into starlight on the victim.
      Fx.shape(level, com.fortuneandfavors.net.FfVfx.BEAM, ParticleTypes.END_ROD, from.add(dir.scale(0.8)), from.add(dir.scale(18.0)), 0.0, 0.0, STARLIGHT);
      Fx.comet(level, ParticleTypes.END_ROD, from.add(dir.scale(0.8)), from.add(dir.scale(18.0)), 6, STARGOLD);
      Vec3 struck = victim.position().add(0.0, victim.getBbHeight() * 0.6, 0.0);
      Fx.shape(level, com.fortuneandfavors.net.FfVfx.CLASH, ParticleTypes.CRIT, struck, dir, 0.0, 0.0, STARGOLD);
      Fx.starburst(level, ParticleTypes.END_ROD, struck, 1.6, STARLIGHT);
      Fx.vanilla(level, ColorParticleOption.create(ParticleTypes.FLASH, 0xBBEEFF), from.x, from.y, from.z, 1, 0.0, 0.0, 0.0, 0.0);
      level.playSound(null, attacker.getX(), attacker.getY(), attacker.getZ(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1.0F, 1.8F);
      attacker.sendOverlayMessage(Component.literal(Chat.colorize("&bStarpiercer &7- &fpiercing star")));
   }

   // ----------------------------------------------------------------- tick loop

   public static void tick(MinecraftServer server) {
      if (server == null) {
         return;
      }
      long now = ServerClock.clock(server.overworld());
      tickMeteors(server);
      for (ServerPlayer p : server.getPlayerList().getPlayers()) {
         Safe.run("magister gear tick", () -> tickPlayer(p, now));
      }
   }

   private static void tickMeteors(MinecraftServer server) {
      if (METEORS.isEmpty()) {
         return;
      }
      for (Iterator<Meteor> it = METEORS.iterator(); it.hasNext();) {
         Meteor meteor = it.next();
         ServerLevel level = meteor.level;
         if (level == null || level.getServer() != server) {
            it.remove();
            continue;
         }
         meteor.fuse--;
         if (meteor.fuse > 0) {
            continue;
         }
         it.remove();
         Vec3 at = meteor.pos.add(0.0, 0.3, 0.0);
         Fx.shape(level, com.fortuneandfavors.net.FfVfx.ROCKBURST, ParticleTypes.CLOUD, at, Vec3.ZERO, 3.0, 0.0, 0xD8E6F2);
         Fx.shape(level, com.fortuneandfavors.net.FfVfx.NOVA, ParticleTypes.END_ROD, at, Vec3.ZERO, METEOR_RADIUS + 1.5, 0.0, STARLIGHT);
         Fx.shape(level, com.fortuneandfavors.net.FfVfx.PILLAR, ParticleTypes.END_ROD, meteor.pos, Vec3.ZERO, 7.0, 0.0, STARGOLD);
         Fx.starburst(level, ParticleTypes.END_ROD, at.add(0.0, 0.6, 0.0), METEOR_RADIUS + 1.0, STARLIGHT);
         Fx.shockwave(level, ParticleTypes.END_ROD, at, METEOR_RADIUS + 1.5, STARGOLD);
         Fx.gemShards(level, ParticleTypes.END_ROD, at.add(0.0, 0.4, 0.0), 1.4, STARGOLD);
         Fx.vanilla(level, ParticleTypes.EXPLOSION_EMITTER, meteor.pos.x, meteor.pos.y + 0.4, meteor.pos.z, 1, 0.4, 0.2, 0.4, 0.0);
         level.playSound(null, meteor.pos.x, meteor.pos.y, meteor.pos.z, SoundEvents.GENERIC_EXPLODE, SoundSource.PLAYERS, 1.4F, 1.1F);
         ServerPlayer owner = server.getPlayerList().getPlayer(meteor.owner);
         // Everything in the blast, not only players: the meteor used to skip every mob, which made
         // it the one attack spell in the mod that did nothing in PvE - and it hit its own caster,
         // because the ally check let the owner through.
         net.minecraft.world.phys.AABB blast = new net.minecraft.world.phys.AABB(meteor.pos, meteor.pos).inflate(METEOR_RADIUS);
         for (LivingEntity victim : level.getEntitiesOfClass(LivingEntity.class, blast, LivingEntity::isAlive)) {
            if (victim.distanceToSqr(meteor.pos) > METEOR_RADIUS * METEOR_RADIUS) {
               continue;
            }
            if (victim instanceof ServerPlayer watcher && watcher.isSpectator()) {
               continue;
            }
            if (owner != null && (victim == owner || victim instanceof ServerPlayer p && ScarletGear.isAlly(owner, p))) {
               continue;
            }
            victim.hurtServer(level, owner != null ? level.damageSources().playerAttack(owner) : level.damageSources().magic(), METEOR_DAMAGE);
            victim.push(0.0, 0.5, 0.0);
            victim.hurtMarked = true;
         }
      }
   }

   private static void tickPlayer(ServerPlayer player, long now) {
      if (!player.isAlive()) {
         HITS.remove(player.getUUID());
         return;
      }
      boolean wearing = ModItems.isAstralMantle(player.getItemBySlot(EquipmentSlot.CHEST));
      if (!wearing) {
         SNEAKING.remove(player.getUUID());
         LAST_SNEAK.remove(player.getUUID());
         return;
      }

      // Slow Falling is the Mantle's passive: it is a cloak cut from the sky.
      MobEffectInstance falling = player.getEffect(MobEffects.SLOW_FALLING);
      if (falling == null || falling.getDuration() < 20) {
         player.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 60, 0, false, false, true));
      }

      // Astral Step: two sneak taps in quick succession, because a chestplate's
      // right-click is how you put it on.
      boolean sneaking = player.isShiftKeyDown();
      boolean was = SNEAKING.getOrDefault(player.getUUID(), false);
      if (sneaking && !was) {
         Long last = LAST_SNEAK.get(player.getUUID());
         if (last != null && now - last <= DOUBLE_TAP_TICKS) {
            LAST_SNEAK.remove(player.getUUID());
            astralStep(player, now);
         } else {
            LAST_SNEAK.put(player.getUUID(), now);
         }
      }
      SNEAKING.put(player.getUUID(), sneaking);
   }

   private static void astralStep(ServerPlayer player, long now) {
      Long until = STEP_UNTIL.get(player.getUUID());
      if (until != null && now < until) {
         bar(player, "&7Astral Step recovers in &f" + ((until - now + 19) / 20) + "s");
         return;
      }
      if (!(player.level() instanceof ServerLevel level)) {
         return;
      }
      Vec3 dir = player.getViewVector(1.0F);
      Vec3 flat = new Vec3(dir.x, 0.0, dir.z);
      if (flat.lengthSqr() < 1.0E-4) {
         flat = new Vec3(0.0, 0.0, 1.0);
      }
      flat = flat.normalize();
      Vec3 from = player.position();
      Vec3 to = clearPath(level, player, flat, STEP_DISTANCE);
      if (to == null) {
         bar(player, "&7Astral Step &8| &7something is in the way");
         return;
      }
      blinkFx(level, from, to);
      player.teleportTo(to.x, to.y, to.z);
      player.hurtMarked = true;
      level.playSound(null, to.x, to.y, to.z, SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 1.0F, 1.7F);
      STEP_UNTIL.put(player.getUUID(), now + STEP_COOLDOWN_TICKS);
      bar(player, "&bAstral Step");
   }

   /** Called from the damage hook: a heavy blow can blink you backward. */
   public static void onPlayerDamaged(ServerPlayer player, float taken) {
      if (player == null || taken < BLINK_MIN_DAMAGE || RANDOM.nextDouble() > BLINK_CHANCE) {
         return;
      }
      if (!ModItems.isAstralMantle(player.getItemBySlot(EquipmentSlot.CHEST))) {
         return;
      }
      if (!(player.level() instanceof ServerLevel level)) {
         return;
      }
      Vec3 back = player.getViewVector(1.0F);
      Vec3 flat = new Vec3(-back.x, 0.0, -back.z);
      if (flat.lengthSqr() < 1.0E-4) {
         return;
      }
      flat = flat.normalize();
      Vec3 to = clearPath(level, player, flat, 5.0);
      if (to == null) {
         return;
      }
      blinkFx(level, player.position(), to);
      player.teleportTo(to.x, to.y, to.z);
      player.hurtMarked = true;
      level.playSound(null, to.x, to.y, to.z, SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 0.8F, 1.7F);
      bar(player, "&bThe Mantle blinks you clear");
   }

   /**
    * The furthest point along {@code flat}, up to {@code distance}, that the body can stand in
    * without touching anything - walked half a block at a time and stopped at the first
    * obstruction. Both blinks used to teleport the full distance blind: into a wall to suffocate,
    * or straight through it into a base or out of a boss arena.
    *
    * @return null when not even the first half-block is clear
    */
   private static Vec3 clearPath(ServerLevel level, ServerPlayer player, Vec3 flat, double distance) {
      Vec3 from = player.position();
      Vec3 best = null;
      for (double d = 0.5; d <= distance + 1.0E-6; d += 0.5) {
         Vec3 at = from.add(flat.scale(d));
         if (!level.noCollision(player, player.getBoundingBox().move(at.subtract(from)))) {
            break;
         }
         best = at;
      }
      return best;
   }

   /**
    * A tear where the body left, a thread of starlight along the way, and the Mantle's arrival
    * where it lands: a bloom of starlight, rays thrown out, and a brief fall of stars round the
    * feet. (The landing used to be a clock burst, which is the Time Lord's cue.)
    */
   private static void blinkFx(ServerLevel level, Vec3 from, Vec3 to) {
      Vec3 across = new Vec3(to.z - from.z, 0.0, from.x - to.x);
      across = across.lengthSqr() < 1.0E-6 ? new Vec3(1.0, 0.0, 0.0) : across.normalize();
      Fx.shape(level, com.fortuneandfavors.net.FfVfx.TEAR, ParticleTypes.REVERSE_PORTAL, from.add(0.0, 1.0, 0.0), across, 1.4, 14, WARP);
      Fx.shape(level, com.fortuneandfavors.net.FfVfx.BEAM, ParticleTypes.END_ROD, from.add(0.0, 1.0, 0.0), to.add(0.0, 1.0, 0.0), 0.0, 0.0, STARLIGHT);
      Fx.flare(level, ParticleTypes.END_ROD, to.add(0.0, 1.0, 0.0), 0.9, STARGOLD);
      Fx.starburst(level, ParticleTypes.END_ROD, to.add(0.0, 1.0, 0.0), 1.8, STARLIGHT);
      Fx.starfall(level, ParticleTypes.END_ROD, to, 1.6, 16, STARLIGHT);
   }

   public static void onPlayerDisconnect(UUID id) {
      SPELL.remove(id);
      HITS.remove(id);
      COOLDOWN_UNTIL.remove(id);
      LAST_SNEAK.remove(id);
      SNEAKING.remove(id);
      STEP_UNTIL.remove(id);
   }

   public static void clear() {
      SPELL.clear();
      HITS.clear();
      COOLDOWN_UNTIL.clear();
      LAST_SNEAK.clear();
      SNEAKING.clear();
      STEP_UNTIL.clear();
      METEORS.clear();
   }

   // ------------------------------------------------------------------ helpers

   private static void bar(ServerPlayer player, String text) {
      player.sendOverlayMessage(Component.literal(Chat.colorize(text)));
   }

   /** Test hook: the Codex's spell list, so the documented pages cannot drift. */
   public static String[] spells() {
      return SPELLS.clone();
   }

   public static int starEveryNHits() {
      return STAR_EVERY_N_HITS;
   }
}
