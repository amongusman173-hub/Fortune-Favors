package com.fortuneandfavors.economy;

import com.fortuneandfavors.FortuneFavorsMod;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.Map.Entry;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * The layer every fight in this mod wears, so that "the bosses are too easy" is answered once
 * instead of fourteen times.
 *
 * <h2>Why one class and not fourteen edits</h2>
 * There are fifteen bodies in this mod that raise a boss bar and call themselves a fight - the
 * eight original raids registered through {@code BossManager.spawn}, the six newer scripted ones
 * with their own fight maps, plus the reworked Wither and the reworked Ender Dragon. Every one of
 * them has its own file, its own moveset and its own damage constants. Tuning "all of them" by
 * hand is fourteen edits that will drift apart the moment one is retuned, and there is no way to
 * look at the result and see whether the fights are consistent - which is how a boss can end up
 * hitting for three hearts while the one next to it hits for eight.
 *
 * <p>So the three things that should be true of <i>every</i> boss live here, at the one level all
 * of them pass through:
 * <ul>
 *   <li><b>Damage.</b> {@link #DAMAGE_MULTIPLIER} is read in the damage pipeline
 *       ({@code CombatBalanceMixin}), not per attack, so it covers a melee swing, a skull, a
 *       breath lance and a scripted slam without a single call site to remember. It also cannot
 *       be double-applied, because there is exactly one place it is multiplied in.</li>
 *   <li><b>Presence.</b> A themed aura, so a fight announces which fight it is from across the
 *       room rather than being a mob with a health bar.</li>
 *   <li><b>A move of its own.</b> {@link #surge}: once a boss is below half health it winds up
 *       in the open, and then everything standing close to it is thrown off its feet. Every boss
 *       gets it, because a player's answer to a boss is usually "stand next to it and swing", and
 *       no boss in this mod currently punishes that.</li>
 * </ul>
 *
 * <h2>Not too much</h2>
 * The multiplier is 1.25 and the surge is a flat 5 - deliberately the smallest numbers that
 * change how a fight is played. A quarter more damage turns a comfortable fight into one you
 * have to eat for, and a knockback pulse every twenty seconds turns "hold left click" into
 * "watch your feet"; neither of them retroactively kills anybody who was fighting well. The
 * surge also has a full 1.5-second tell with its own sound, and it does not fire above half
 * health, while the boss is invulnerable (arriving, rising, mid-ceremony) or with nobody within
 * twenty blocks - a scripted cutscene must never be interrupted by it.
 *
 * <h2>Server side only</h2>
 * Everything here is particles, sounds, damage and the boss bar's own room: no packet of its
 * own, no client state, nothing that a vanilla client can be wrong about.
 */
public final class BossEmpowerment {
   /**
    * What a boss's damage is multiplied by, read in the damage pipeline.
    *
    * <p>1.25 is the whole buff. It is a constant rather than a per-boss number on purpose: the
    * complaint being answered is "every boss is too easy", and fourteen hand-tuned multipliers is
    * fourteen chances to be wrong and no way to tell that you were.
    */
   public static final double DAMAGE_MULTIPLIER = 1.25;

   /** A boss below this fraction of its own health starts using {@link #surge}. */
   public static final double SURGE_HEALTH_FRACTION = 0.5;

   /** Ticks of wind-up before the surge lands - the window a player is meant to use. */
   public static final int SURGE_TELL_TICKS = 30;

   /** How long between surges, per boss. */
   public static final long SURGE_COOLDOWN_MS = 20_000L;

   /** How close a player must stand to be caught by the surge. */
   public static final double SURGE_RADIUS = 4.0;

   /** What the surge takes off anybody it catches. Small on purpose: it is a warning, not a hit. */
   public static final float SURGE_DAMAGE = 5.0F;

   /** Upward shove, so being caught lifts you off the ground instead of merely moving you. */
   public static final double SURGE_LIFT = 0.4;

   /** A player this far away counts as "in the fight" - no surge is wasted on an empty room. */
   public static final double SURGE_TRIGGER_RANGE = 20.0;

   /** How often the aura pulses. */
   private static final long AURA_PERIOD_MS = 2_000L;

   /** How long a watched boss may be missing from its level before it is forgotten. */
   private static final int MISSING_GRACE_SECONDS = 300;

   /** One boss the layer knows about. */
   private static final class Watched {
      final ServerLevel level;
      final String theme;
      /** Ticks of surge wind-up left; 0 means idle. */
      int windup;
      long nextSurge;
      long lastAura;
      int missingSeconds;

      Watched(ServerLevel level, String theme) {
         this.level = level;
         this.theme = theme;
      }
   }

   private static final Map<UUID, Watched> watched = new HashMap<>();

   private BossEmpowerment() {
   }

   /**
    * Remembers a body as one this layer drives.
    *
    * <p>Called from the two places a fight starts mattering: {@code BossManager.markBoss}, which
    * every rigged fight calls at summon, and the per-boss loop that drives the original raids -
    * plus the reworked Wither and Dragon, which own their bodies outright. Registration is
    * idempotent, and a boss that is already watched keeps its clock: a fight that is re-adopted
    * after a chunk reload does not get its surge reset.
    */
   public static void register(Entity entity) {
      if (!(entity instanceof LivingEntity) || !(entity.level() instanceof ServerLevel level)) {
         return;
      }
      watched.computeIfAbsent(entity.getUUID(), id -> new Watched(level, themeOf(entity)));
   }

   /**
    * Is this body one of the mod's bosses - the question the damage pipeline asks.
    *
    * <p>Deliberately not "is it in {@link #watched}": that map is filled by the summon path and by
    * the tick loop that drives the raids, so it can be a tick behind a boss that has just arrived
    * and is already swinging. This reads the marker the summon writes itself, which every fight
    * wears from the moment it exists, and falls back to the two reworked vanilla bodies, whose
    * reworks own them outright. The registry is only what drives the aura and the surge.
    *
    * <p>Registration itself is health-checked rather than trusted: this runs inside a mixin on the
    * damage path, and a boss whose tagging failed must mean "hits normally", never a crash in the
    * middle of somebody's fight.
    */
   public static boolean isEmpowered(Entity entity) {
      if (entity == null) {
         return false;
      }
      try {
         if (entity instanceof net.minecraft.world.entity.boss.wither.WitherBoss
            || entity instanceof net.minecraft.world.entity.boss.enderdragon.EnderDragon) {
            return true;
         }
         return BossManager.isMarkedBoss(entity) || BossManager.isBoss(entity);
      } catch (Throwable t) {
         return false;
      }
   }

   /**
    * The colour and particle family a body's aura wears, read off what the body is.
    *
    * <p>Matched on the registry id rather than on {@code EntityType} fields, which is how the rest
    * of this mod names a mob and the only way that also answers for a body from another mod's
    * registry: the six scripted fights in this file are ordinary vanilla bodies (a wither skeleton,
    * a slime, an iron golem, a witch, a warden, a phantom), so the theme falls out of what they
    * already are rather than out of a lookup table that would have to be kept in step by hand.
    */
   public static String themeOf(Entity entity) {
      if (entity == null) {
         return "soul";
      }
      String id;
      try {
         id = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
      } catch (Throwable t) {
         return "soul";
      }
      return switch (id) {
         case "minecraft:wither_skeleton", "minecraft:blaze", "minecraft:zombified_piglin" -> "ember";
         case "minecraft:slime", "minecraft:magma_cube" -> "slime";
         case "minecraft:iron_golem" -> "stone";
         case "minecraft:witch" -> "arcane";
         case "minecraft:warden" -> "sculk";
         case "minecraft:phantom" -> "crimson";
         case "minecraft:wither" -> "soul";
         case "minecraft:ender_dragon" -> "void";
         case "minecraft:evoker", "minecraft:vindicator", "minecraft:pillager", "minecraft:ravager" -> "raid";
         case "minecraft:snow_golem" -> "frozen";
         case "minecraft:zombie", "minecraft:husk", "minecraft:drowned" -> "ember";
         default -> "soul";
      };
   }

   /** How many bosses the layer is currently driving. Test hook. */
   public static int trackedCount() {
      return watched.size();
   }

   /** Forgets everything. Called on server shutdown so a restart starts clean. */
   public static void clear() {
      watched.clear();
   }

   /**
    * World stopping: the registry holds a live level per body, and a level must never outlive the
    * server that made it. Without this the map would keep one dead world alive until the next
    * stop, and every boss in it would be ticked against a level nobody can see.
    */
   public static void onServerStopping(MinecraftServer server) {
      clear();
   }

   /**
    * One server tick of the layer: aura, wind-up, surge.
    *
    * <p>Called from the server's tick loop, so the clocks here are real ticks and the tell is the
    * 1.5 seconds it says it is.
    */
   public static void tick(MinecraftServer server) {
      if (server == null || watched.isEmpty()) {
         return;
      }
      long now = System.currentTimeMillis();
      for (Iterator<Entry<UUID, Watched>> it = watched.entrySet().iterator(); it.hasNext(); ) {
         Entry<UUID, Watched> e = it.next();
         Watched w = e.getValue();
         try {
            LivingEntity body = find(server, w, e.getKey());
            if (body == null) {
               // Out of the loaded world - a chunk unloaded mid-fight, usually, and the body is
               // still there in every sense that matters. Only a body that has been gone for
               // minutes is forgotten, so a reloaded fight keeps its own clock.
               if (++w.missingSeconds > MISSING_GRACE_SECONDS) {
                  it.remove();
               }
               continue;
            }
            w.missingSeconds = 0;
            if (!body.isAlive()) {
               it.remove();
               continue;
            }
            ServerLevel level = body.level() instanceof ServerLevel sl ? sl : w.level;
            if (now - w.lastAura >= AURA_PERIOD_MS) {
               w.lastAura = now;
               aura(level, body, w.theme);
            }
            // A boss that is invulnerable is a boss inside one of its own ceremonies - arriving,
            // rising, judging, dying. It is not fighting, so it does not surge: a cutscene that
            // knocked the player over would read as a bug rather than as a move.
            if (body.isInvulnerable()) {
               continue;
            }
            if (w.windup > 0) {
               w.windup--;
               tell(level, body, w.theme, w.windup);
               if (w.windup == 0) {
                  surge(level, body, w.theme);
                  w.nextSurge = now + SURGE_COOLDOWN_MS;
               }
               continue;
            }
            if (now >= w.nextSurge && body.getHealth() <= body.getMaxHealth() * SURGE_HEALTH_FRACTION
               && hasCompany(level, body)) {
               w.windup = SURGE_TELL_TICKS;
               tellStart(level, body, w.theme);
            }
         } catch (Throwable t) {
            // A layer that is decoration half the time must not be able to take a fight down with
            // it: one body failing is one body skipped, not the end of the tick.
            FortuneFavorsMod.LOGGER.warn("Fortune & Favors: boss empowerment pass failed", t);
            it.remove();
         }
      }
   }

   /** Where the watched body is now. */
   private static LivingEntity find(MinecraftServer server, Watched w, UUID id) {
      if (w.level.getEntity(id) instanceof LivingEntity le) {
         return le;
      }
      for (ServerLevel level : server.getAllLevels()) {
         if (level.getEntity(id) instanceof LivingEntity le) {
            return le;
         }
      }
      return null;
   }

   /** Is anybody actually here to fight it? */
   private static boolean hasCompany(ServerLevel level, LivingEntity boss) {
      for (ServerPlayer p : level.players()) {
         if (!p.isCreative() && !p.isSpectator() && p.distanceToSqr(boss) <= SURGE_TRIGGER_RANGE * SURGE_TRIGGER_RANGE) {
            return true;
         }
      }
      return false;
   }

   /** The standing aura: a rising helix of the boss's own colour, plus its particle family. */
   private static void aura(ServerLevel level, LivingEntity boss, String theme) {
      double x = boss.getX();
      double y = boss.getY();
      double z = boss.getZ();
      double radius = Math.max(0.9, boss.getBbWidth() + 0.55);
      double spin = level.getGameTime() % 80 / 80.0 * Math.PI * 2.0;
      int color = colorOf(theme);
      for (int i = 0; i < 10; i++) {
         double angle = spin + i / 10.0 * Math.PI * 2.0;
         level.sendParticles(
            new DustParticleOptions(color, 1.1F),
            x + Math.cos(angle) * radius,
            y + 0.15 + 0.13 * i,
            z + Math.sin(angle) * radius,
            1,
            0.0,
            0.02,
            0.0,
            0.0
         );
      }
      level.sendParticles(particleOf(theme), x, y + 1.1, z, 3, radius * 0.6, 0.5, radius * 0.6, 0.01);
      if (level.getGameTime() % 100 < 2) {
         level.playSound(null, x, y + 1.0, z, SoundEvents.BEACON_AMBIENT, SoundSource.HOSTILE, 0.7F, 0.6F);
      }
   }

   /** The first instant of a wind-up: the sound and the light that say "move". */
   private static void tellStart(ServerLevel level, LivingEntity boss, String theme) {
      double x = boss.getX();
      double y = boss.getY();
      double z = boss.getZ();
      level.playSound(null, x, y, z, SoundEvents.WARDEN_SONIC_CHARGE, SoundSource.HOSTILE, 1.4F, 0.7F);
      level.sendParticles(ParticleTypes.END_ROD, x, y + 1.0, z, 6, 0.5, 0.7, 0.5, 0.06);
      for (ServerPlayer p : level.players()) {
         if (p.distanceToSqr(boss) <= SURGE_TRIGGER_RANGE * SURGE_TRIGGER_RANGE && !p.isSpectator()) {
            p.sendSystemMessage(Component.literal("§8§l» §7It is winding up - §fget clear!"), true);
         }
      }
      ring(level, x, y + 0.1, z, SURGE_RADIUS * 0.5, theme, 12);
   }

   /** The wind-up itself: a tightening ring that says how long is left. */
   private static void tell(ServerLevel level, LivingEntity boss, String theme, int ticksLeft) {
      double progress = 1.0 - (double)ticksLeft / SURGE_TELL_TICKS;
      double radius = SURGE_RADIUS * 0.5 + SURGE_RADIUS * 0.5 * progress;
      double x = boss.getX();
      double y = boss.getY();
      double z = boss.getZ();
      ring(level, x, y + 0.1, z, radius, theme, 10);
      if (ticksLeft % 4 == 0) {
         level.sendParticles(particleOf(theme), x, y + 1.0, z, 2, 0.4, 0.6, 0.4, 0.02);
      }
   }

   /** The surge: everything within {@link #SURGE_RADIUS} is hit and thrown clear. */
   /**
    * Whether this blow must be refused because the body it would land on is a creative player.
    *
    * <p>Every scripted move in this mod hurts a player by calling {@code hurtServer} directly, and
    * that is the one route vanilla's own creative rule does not always survive: a body that has
    * just logged in is still having its abilities set, and the mod's own hooks run *ahead* of
    * vanilla's invulnerability test, so a fight that shares a world with a creative player can hurt
    * them - the report being *"the bosses can damage me when I first join the world as creative."*
    *
    * <p>It is answered here, once, for the same reason {@link #DAMAGE_MULTIPLIER} lives here: a
    * rule per move is fourteen places to forget it, and the one that gets forgotten is the one a
    * player meets. A creative player is out of reach of every fight - a melee swing, a skull, a
    * breath lance, a scripted slam, a beam, a wave - and is still reachable by the two things that
    * belong to the player rather than to a fight: the void, and {@code /kill}. Those two are the
    * damage types vanilla itself lets through, so they are exactly the ones left alone.
    *
    * <p>Not public API for anyone else's sake: {@code ModEvents} registers it as the first damage
    * rule in the pipeline, so every fight - including the ones added later - is covered by
    * construction rather than by memory.
    */
   public static boolean blocksCreative(Entity victim, DamageSource source) {
      return victim instanceof ServerPlayer player && player.isCreative() && blocksACreativePlayer(source);
   }

   /**
    * The rule on its own - is this blow one that reaches a creative player?
    *
    * <p>Split out as a pure function of the damage source so it can be pinned without a body:
    * {@link #blocksCreative} is the wiring, and this is the decision the wiring is expected to
    * make. The player's own two doors stay open - the void, and {@code /kill} - which are exactly
    * the damage types vanilla itself lets past a creative player's invulnerability, and everything
    * else, including a source with no attacker at all (how every scripted move hurts people), is a
    * fight and is refused.
    */
   public static boolean blocksACreativePlayer(DamageSource source) {
      if (source == null) {
         return true;
      }
      return !(source.is(DamageTypeTags.BYPASSES_INVULNERABILITY) && !(source.getEntity() instanceof LivingEntity));
   }

   private static void surge(ServerLevel level, LivingEntity boss, String theme) {
      double x = boss.getX();
      double y = boss.getY() + 0.15;
      double z = boss.getZ();
      ring(level, x, y, z, SURGE_RADIUS, theme, 30);
      level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, x, y, z, 1, 0.0, 0.0, 0.0, 0.0);
      level.sendParticles(ParticleTypes.GUST, x, y, z, 1, 0.0, 0.0, 0.0, 0.0);
      level.playSound(null, x, y, z, SoundEvents.DRAGON_FIREBALL_EXPLODE, SoundSource.HOSTILE, 1.5F, 0.85F);
      for (ServerPlayer p : level.players()) {
         if (p.isCreative() || p.isSpectator()) {
            continue;
         }
         if (p.distanceToSqr(x, y, z) > SURGE_RADIUS * SURGE_RADIUS) {
            continue;
         }
         p.hurtServer(level, level.damageSources().mobAttack(boss), SURGE_DAMAGE);
         Vec3 away = new Vec3(p.getX() - x, 0.0, p.getZ() - z);
         Vec3 flat = away.lengthSqr() < 1.0E-4 ? new Vec3(0.0, 0.0, 1.0) : away.normalize();
         p.push(flat.x * 0.9, SURGE_LIFT, flat.z * 0.9);
         p.hurtMarked = true;
      }
   }

   /** One horizontal ring of the theme's dust, used by the tell, the wind-up and the blast. */
   private static void ring(ServerLevel level, double x, double y, double z, double radius, String theme, int points) {
      int color = colorOf(theme);
      for (int i = 0; i < points; i++) {
         double angle = i / (double)points * Math.PI * 2.0;
         level.sendParticles(
            new DustParticleOptions(color, 1.0F),
            x + Math.cos(angle) * radius,
            y,
            z + Math.sin(angle) * radius,
            1,
            0.0,
            0.05,
            0.0,
            0.0
         );
      }
   }

   private static ParticleOptions particleOf(String theme) {
      return switch (theme) {
         case "ember" -> ParticleTypes.SOUL_FIRE_FLAME;
         case "slime" -> ParticleTypes.ITEM_SLIME;
         case "stone" -> ParticleTypes.CRIT;
         case "arcane" -> ParticleTypes.ENCHANT;
         case "sculk" -> ParticleTypes.SCULK_SOUL;
         case "crimson" -> ParticleTypes.CRIMSON_SPORE;
         case "void" -> ParticleTypes.REVERSE_PORTAL;
         case "raid" -> ParticleTypes.ELECTRIC_SPARK;
         case "frozen" -> ParticleTypes.SNOWFLAKE;
         default -> ParticleTypes.SOUL;
      };
   }

   private static int colorOf(String theme) {
      return switch (theme) {
         case "ember" -> 0xFF7A2A;
         case "slime" -> 0x63D64A;
         case "stone" -> 0xB9B4A4;
         case "arcane" -> 0xB06CFF;
         case "sculk" -> 0x1FD6C0;
         case "crimson" -> 0xFF3B4E;
         case "void" -> 0x8A2BE2;
         case "raid" -> 0xE8D44D;
         case "frozen" -> 0xBFF0FF;
         default -> 0x9BE8FF;
      };
   }
}
