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

      Meteor(Vec3 pos, int fuse, UUID owner) {
         this.pos = pos;
         this.fuse = fuse;
         this.owner = owner;
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
      level.sendParticles(ColorParticleOption.create(ParticleTypes.FLASH, 0x99EEFF), from.x, from.y, from.z, 1, 0.0, 0.0, 0.0, 0.0);
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
         if (living instanceof ServerPlayer other && ScarletGear.isAlly(player, other)) {
            continue;
         }
         Vec3 pull = centre.subtract(living.position());
         double len = pull.length();
         if (len < 0.5) {
            continue;
         }
         Vec3 unit = pull.scale(1.0 / len);
         living.push(unit.x * 0.9, 0.25, unit.z * 0.9);
         living.hurtMarked = true;
         living.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 90, 2, false, true, true));
         living.hurtServer(level, level.damageSources().playerAttack(player), 3.0F);
         caught++;
      }
      for (int i = 0; i < 80; i++) {
         double a = RANDOM.nextDouble() * Math.PI * 2.0;
         double r = 7.0;
         double x = centre.x + Math.cos(a) * r;
         double z = centre.z + Math.sin(a) * r;
         level.sendParticles(ParticleTypes.REVERSE_PORTAL, x, centre.y, z, 1, -Math.cos(a) * 0.4, 0.0, -Math.sin(a) * 0.4, 0.0);
      }
      level.playSound(null, centre.x, centre.y, centre.z, SoundEvents.ENDER_DRAGON_FLAP, SoundSource.PLAYERS, 1.2F, 0.8F);
      bar(player, "&5Gravity &7- &f" + caught + " &7caught");
   }

   private static void castMeteor(ServerPlayer player) {
      if (!(player.level() instanceof ServerLevel level)) {
         return;
      }
      Vec3 dir = player.getViewVector(1.0F);
      Vec3 at = player.getEyePosition().add(dir.scale(12.0));
      BlockPos ground = level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, BlockPos.containing(at));
      METEORS.add(new Meteor(new Vec3(ground.getX() + 0.5, ground.getY(), ground.getZ() + 0.5), 26, player.getUUID()));
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
      arrow.setBaseDamage(damage);
      arrow.setCritArrow(false);
      arrow.pickup = AbstractArrow.Pickup.DISALLOWED;
      level.addFreshEntity(arrow);
      level.sendParticles(ParticleTypes.END_ROD, from.x, from.y, from.z, 6, 0.1, 0.1, 0.1, 0.02);
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
      level.sendParticles(ParticleTypes.END_ROD, from.x, from.y, from.z, 20, 0.3, 0.3, 0.3, 0.1);
      level.sendParticles(ColorParticleOption.create(ParticleTypes.FLASH, 0xBBEEFF), from.x, from.y, from.z, 1, 0.0, 0.0, 0.0, 0.0);
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
         ServerLevel level = levelOf(server, meteor.owner);
         if (level == null) {
            it.remove();
            continue;
         }
         int points = 22;
         for (int i = 0; i < points; i++) {
            double a = i * (Math.PI * 2.0 / points);
            level.sendParticles(ParticleTypes.END_ROD, meteor.pos.x + Math.cos(a) * 3.0, meteor.pos.y + 0.15, meteor.pos.z + Math.sin(a) * 3.0, 1, 0.0, 0.0, 0.0, 0.0);
         }
         meteor.fuse--;
         if (meteor.fuse > 0) {
            continue;
         }
         it.remove();
         level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, meteor.pos.x, meteor.pos.y + 0.4, meteor.pos.z, 3, 0.8, 0.4, 0.8, 0.0);
         level.sendParticles(ParticleTypes.GUST, meteor.pos.x, meteor.pos.y + 0.4, meteor.pos.z, 24, 2.4, 0.4, 2.4, 0.15);
         level.sendParticles(ParticleTypes.END_ROD, meteor.pos.x, meteor.pos.y + 0.4, meteor.pos.z, 60, 2.6, 0.8, 2.6, 0.2);
         level.playSound(null, meteor.pos.x, meteor.pos.y, meteor.pos.z, SoundEvents.GENERIC_EXPLODE, SoundSource.PLAYERS, 1.4F, 1.1F);
         ServerPlayer owner = server.getPlayerList().getPlayer(meteor.owner);
         for (ServerPlayer p : playersNear(level, meteor.pos.x, meteor.pos.y, meteor.pos.z, 3.6)) {
            if (owner != null && p != owner && ScarletGear.isAlly(owner, p)) {
               continue;
            }
            p.hurtServer(level, owner != null ? level.damageSources().playerAttack(owner) : level.damageSources().magic(), 14.0F);
            p.push(0.0, 0.5, 0.0);
            p.hurtMarked = true;
         }
      }
   }

   private static ServerLevel levelOf(MinecraftServer server, UUID playerId) {
      ServerPlayer p = server.getPlayerList().getPlayer(playerId);
      return p == null ? null : p.level();
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
      player.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 60, 0, false, false, true));

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
      level.sendParticles(ParticleTypes.REVERSE_PORTAL, from.x, from.y + 1.0, from.z, 40, 0.4, 0.6, 0.4, 0.1);
      Vec3 to = from.add(flat.scale(STEP_DISTANCE));
      player.teleportTo(to.x, to.y, to.z);
      player.hurtMarked = true;
      level.sendParticles(ParticleTypes.END_ROD, to.x, to.y + 1.0, to.z, 40, 0.4, 0.6, 0.4, 0.12);
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
      Vec3 to = player.position().add(flat.scale(5.0));
      level.sendParticles(ParticleTypes.REVERSE_PORTAL, player.getX(), player.getY() + 1.0, player.getZ(), 30, 0.4, 0.6, 0.4, 0.1);
      player.teleportTo(to.x, to.y, to.z);
      player.hurtMarked = true;
      level.sendParticles(ParticleTypes.END_ROD, to.x, to.y + 1.0, to.z, 24, 0.4, 0.6, 0.4, 0.1);
      level.playSound(null, to.x, to.y, to.z, SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 0.8F, 1.7F);
      bar(player, "&bThe Mantle blinks you clear");
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

   private static List<ServerPlayer> playersNear(ServerLevel level, double x, double y, double z, double range) {
      List<ServerPlayer> out = new ArrayList<>();
      double r2 = range * range;
      for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
         if (!p.isAlive() || p.level() != level) {
            continue;
         }
         if (p.distanceToSqr(x, y, z) <= r2) {
            out.add(p);
         }
      }
      return out;
   }

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
