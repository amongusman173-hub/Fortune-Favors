package com.fortuneandfavors.economy;

import com.fortuneandfavors.ModItems;
import com.fortuneandfavors.ModSounds;
import com.fortuneandfavors.util.Chat;
import com.fortuneandfavors.util.Safe;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import net.minecraft.core.particles.DustParticleOptions;
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
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * The three legendary weapons the Scarlet Devil's box drops, and the servants the
 * blood ritual leaves behind.
 *
 * <h2>Why this is not in {@link ScarletDevilManager}</h2>
 * That class is her fight script: the swoop, the rain, the ceremonies. These are
 * player-owned items with their own lifecycles (cooldowns, spell selection,
 * servants and rituals on timers), so they live here and the boss file stays
 * readable.
 *
 * <h2>Feedback lives in the action bar</h2>
 * Everything the Grimoire and the Prism say - which spell is selected, what is on
 * cooldown, how far through a ritual you are - goes to the action bar. These are
 * combat items used every few seconds; putting that in chat would drown the chat.
 *
 * <h2>The Blood Prism never touches an inventory</h2>
 * Dying to the Scarlet Devil's hands your gear to a bone servant, and getting it
 * back is a whole fight. The Prism deliberately does the opposite: it drains a
 * target through a channel that breaks if you are hit or move, and the servant it
 * leaves behind wears a <i>copy</i> of the victim's armour and carries nothing -
 * a player killed by the ritual keeps their inventory untouched.
 */
public final class ScarletGear {

   // ------------------------------------------------------------------ colour
   /** Fresh blood, the colour every Scarlet effect is drawn in. */
   private static final int SCARLET = 0xC0102A;
   /** Old blood, for the parts of an effect that sit in shadow. */
   private static final int CLOT = 0x5A0612;
   /** The glint on wet blood, for the brightest part of an effect. */
   private static final int GLINT = 0xFF5A6E;

   // --------------------------------------------------------------- grimoire

   private static final String[] SPELL_NAMES = {"Bloodsuck", "Blood Spears", "Night Swarm", "Blood Rain"};
   private static final int[] SPELL_COOLDOWN_SECONDS = {6, 8, 14, 25};
   /** Cast #0 is the bite; it reaches this far. */
   private static final double BITE_REACH = 5.0;
   private static final double SPELL_RANGE = 22.0;
   private static final float BITE_DAMAGE = 9.0F;
   private static final float SPEAR_DAMAGE = 6.0F;
   /** How fast a Blood Spear leaves the hand. */
   private static final double SPEAR_SPEED = 2.2;
   private static final float RAIN_DAMAGE = 4.0F;
   private static final double RAIN_RADIUS = 14.0;

   /** Per-wielder spell selection. Resets to the bite when the Grimoire is swapped out. */
   private static final Map<UUID, Integer> SPELL = new HashMap<>();

   // ------------------------------------------------------------------- bats

   /** Night Swarm bats: they follow the caster and actually bite. */
   private static final Map<UUID, Bat> BATS = new HashMap<>();
   private static final int BAT_TICKS = 18 * 20;
   private static final int BAT_COUNT = 8;
   private static final float BAT_DAMAGE = 2.5F;

   private static final class Bat {
      final UUID owner;
      int ticksLeft = BAT_TICKS;
      long nextBite;

      Bat(UUID owner) {
         this.owner = owner;
      }
   }

   // ---------------------------------------------------------------- servants

   private static final String SERVANT_KEY = "ff_blood_servant";
   private static final String SERVANT_OWNER_KEY = "ff_blood_servant_owner";
   private static final int SERVANT_TICKS = 60 * 20;
   private static final int SERVANT_MAX = 4;
   private static final float SERVANT_DAMAGE = 6.0F;

   private static final class Servant {
      final UUID owner;
      int ticksLeft = SERVANT_TICKS;
      long nextAttack;

      Servant(UUID owner) {
         this.owner = owner;
      }
   }

   private static final Map<UUID, Servant> SERVANTS = new HashMap<>();
   /** Players marked blood-sworn: the ritual's ally state, not an inventory state. */
   private static final Map<UUID, Integer> SWORN = new HashMap<>();

   // ---------------------------------------------------------------- rituals

   /** How long a channel can be held before it gives out. */
   private static final int RITUAL_MAX_TICKS = 300;
   private static final int RITUAL_PULSE_TICKS = 10;
   /** Drain per pulse, as a share of the target's maximum health. */
   private static final float RITUAL_DRAIN_SHARE = 0.05F;
   /**
    * What a forged tier is worth to the ritual: harder per pulse, and faster between them.
    *
    * <p>The Prism is a forge legendary like the rest of her kit, so a Tier III Prism is a
    * deliberate expense - and until this it drained exactly as hard as a Tier I one, which made
    * the upgrade a cosmetic. Each tier past the first takes half again as much blood per pulse
    * and arrives three ticks sooner: ten ticks between pulses at Tier I, seven at Tier II, four
    * at Tier III, against a channel that lasts the same fifteen seconds in every hand.
    */
   private static final float RITUAL_TIER_DRAIN_STEP = 0.5F;
   private static final int RITUAL_TIER_PULSE_STEP = 3;
   /** The floor on the pulse, because a drain with no beat between it is not a channel. */
   private static final int RITUAL_PULSE_FLOOR = 4;
   private static final double RITUAL_RANGE = 20.0;
   /** The caster must not wander further than this from where they planted. */
   private static final double RITUAL_ANCHOR = 0.6;

   /**
    * A channelled drain. Deliberately a *minigame* rather than an instant effect:
    * the caster is rooted to where they started, the target's health ticks down in
    * pulses, and the whole thing is cancelled by moving or taking a hit.
    */
   private static final class Ritual {
      final UUID caster;
      final UUID target;
      final ServerLevel level;
      final Vec3 anchor;
      /** The Prism's forge tier, read when the channel opened. */
      final int tier;
      int ticksLeft = RITUAL_MAX_TICKS;
      long nextPulse;
      float drained;
      boolean hit;

      Ritual(UUID caster, UUID target, ServerLevel level, Vec3 anchor, long now, int tier) {
         this.caster = caster;
         this.target = target;
         this.level = level;
         this.anchor = anchor;
         this.tier = Math.max(1, tier);
         this.nextPulse = now + pulseTicks(this.tier);
      }
   }

   /** Ticks between pulses for a Prism of this tier - the beat of the channel. */
   private static int pulseTicks(int tier) {
      return Math.max(RITUAL_PULSE_FLOOR, RITUAL_PULSE_TICKS - (Math.max(1, tier) - 1) * RITUAL_TIER_PULSE_STEP);
   }

   /** Drain per pulse for a Prism of this tier, as a share of the target's maximum health. */
   private static float drainShare(int tier) {
      return RITUAL_DRAIN_SHARE * (1.0F + (Math.max(1, tier) - 1) * RITUAL_TIER_DRAIN_STEP);
   }

   /** Test hooks: what a given tier is worth, so the upgrade cannot quietly become cosmetic. */
   public static int ritualPulseTicksForTier(int tier) {
      return pulseTicks(tier);
   }

   public static float ritualDrainShareForTier(int tier) {
      return drainShare(tier);
   }

   private static final Map<UUID, Ritual> RITUALS = new HashMap<>();

   /**
    * Inventories of players killed by a ritual, captured so the death can be
    * "safe": the drop hook holds every stack (see {@link #holdsSafeDeath}) and the
    * contents are written straight back in {@link #restoreAfterRitualDeath}. That
    * is what makes a ritual kill cost nothing but the walk back.
    */
   private static final Map<UUID, SafeDeath> SAFE_DEATH = new HashMap<>();
   private static final long SAFE_DEATH_MILLIS = 5_000L;

   /** A ritual victim's inventory, held only long enough for the death to land. */
   private static final class SafeDeath {
      final List<ItemStack> items;
      final long expiresAt;

      SafeDeath(List<ItemStack> items, long expiresAt) {
         this.items = items;
         this.expiresAt = expiresAt;
      }
   }

   private static final List<Spear> SPEARS = new ArrayList<>();
   private static final Random RANDOM = new Random();

   private static final class Spear {
      final UUID target;
      final ServerLevel level;
      final ServerPlayer caster;
      Vec3 origin;
      int remaining;
      long next;

      Spear(UUID target, ServerLevel level, ServerPlayer caster, Vec3 origin, int remaining, long next) {
         this.target = target;
         this.level = level;
         this.caster = caster;
         this.origin = origin;
         this.remaining = remaining;
         this.next = next;
      }
   }

   private ScarletGear() {
   }

   /** How many ritual servants are abroad - exposed for the self-test. */
   public static int servantCount() {
      return SERVANTS.size();
   }

   /** Everything ScarletGear says goes here, so the chat stays legible. */
   private static void bar(ServerPlayer player, String text) {
      if (player != null) {
         player.sendOverlayMessage(Component.literal(text));
      }
   }

   // ------------------------------------------------------------- the grimoire

   /** True when this stack is a Grimoire still doing the job the lore promises. */
   public static boolean isSpellItem(ItemStack stack) {
      return ModItems.isScarletGrimoire(stack);
   }

   /**
    * Grimoire right-click. Sneak cycles the spell, a plain click casts it.
    *
    * <p>Always returns {@code null}: every outcome (cast, cooldown, nothing in
    * reach) is reported in the action bar, so the right-click is consumed without
    * adding a line to chat each time.
    */
   public static String useGrimoire(ServerPlayer player, ItemStack held) {
      int index = Math.floorMod(SPELL.getOrDefault(player.getUUID(), 0), SPELL_NAMES.length);

      if (player.isShiftKeyDown()) {
         index = (index + 1) % SPELL_NAMES.length;
         SPELL.put(player.getUUID(), index);
         bar(player, "§4Scarlet Grimoire §8| §f" + SPELL_NAMES[index] + " §7selected §8(" + (index + 1) + "/" + SPELL_NAMES.length + ")");
         player.level().playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.BOOK_PAGE_TURN, SoundSource.PLAYERS, 1.0F, 1.2F);
         return null;
      }

      long now = ServerClock.clock(player.level());
      long left = ModItems.cooldownSecondsLeft(held, now);
      if (left > 0L) {
         bar(player, "§4Grimoire §8| §7still bleeding dry §8- §f" + left + "s");
         return null;
      }

      boolean cast = switch (index) {
         case 0 -> castBloodsuck(player);
         case 1 -> castSpears(player);
         case 2 -> castSwarm(player);
         default -> castRain(player);
      };
      if (!cast) {
         bar(player, index == 0
            ? "§4Grimoire §8| §7nothing within reach to bite"
            : "§4Grimoire §8| §7look at a living target first");
         return null;
      }

      int ticks = SPELL_COOLDOWN_SECONDS[index] * 20;
      ModItems.setCooldownUntil(held, now + ticks);
      player.getCooldowns().addCooldown(held, ticks);
      bar(player, "§4" + SPELL_NAMES[index] + " §8| §7cast §8- §f" + SPELL_COOLDOWN_SECONDS[index] + "s");
      if (player.level() instanceof ServerLevel level) {
         // The sigil the Grimoire draws under its reader on every cast.
         com.fortuneandfavors.net.FfVfx.shape(level, com.fortuneandfavors.net.FfVfx.RING, ParticleTypes.CRIMSON_SPORE, player.position().add(0.0, 0.1, 0.0), Vec3.ZERO, 1.6, 0.0, SCARLET);
         com.fortuneandfavors.net.FfVfx.shape(level, com.fortuneandfavors.net.FfVfx.RING, ParticleTypes.CRIMSON_SPORE, player.position().add(0.0, 0.1, 0.0), Vec3.ZERO, 0.9, 0.0, GLINT);
         level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.EVOKER_CAST_SPELL, SoundSource.PLAYERS, 1.0F, 0.7F);
      }
      return null;
   }

   /** Bloodsuck - her bite. Heavy on contact, and it drinks. */
   private static boolean castBloodsuck(ServerPlayer player) {
      if (!(player.level() instanceof ServerLevel level)) {
         return false;
      }
      // Look first, then fall back to whatever is closest: a bite that refuses to
      // fire because the crosshair was a degree off reads as "the spell is broken".
      LivingEntity target = lookedAt(player, BITE_REACH);
      if (target == null) {
         target = nearestLiving(player, BITE_REACH + 0.5);
      }
      if (target == null || target == player) {
         return false;
      }
      float before = player.getHealth();
      boolean landed = target.hurtServer(level, level.damageSources().playerAttack(player), BITE_DAMAGE);
      if (landed) {
         float heal = Math.min(4.0F, BITE_DAMAGE * 0.45F);
         player.setHealth(Math.min(player.getMaxHealth(), before + heal));
         CombatGear.procPopupPublic(level, player, "§4Bloodsuck +" + Math.round(heal));
      }
      double dx = target.getX() - player.getX();
      double dz = target.getZ() - player.getZ();
      double d = Math.max(0.1, Math.sqrt(dx * dx + dz * dz));
      target.setDeltaMovement(target.getDeltaMovement().add(dx / d * 0.7, 0.3, dz / d * 0.7));
      target.hurtMarked = true;
      level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.PHANTOM_BITE, SoundSource.PLAYERS, 1.2F, 0.9F);
      Vec3 wound = target.position().add(0.0, target.getBbHeight() * 0.6, 0.0);
      // The bite, then the blood drawn back along the line to the mouth that took it.
      com.fortuneandfavors.net.FfVfx.shape(level, com.fortuneandfavors.net.FfVfx.GOO_SPLASH, ParticleTypes.CRIMSON_SPORE, wound, Vec3.ZERO, 1.1, 0.0, SCARLET);
      com.fortuneandfavors.net.FfVfx.shape(level, com.fortuneandfavors.net.FfVfx.BEAM, ParticleTypes.CRIMSON_SPORE, wound, player.getEyePosition().add(0.0, -0.4, 0.0), 0.0, 0.0, GLINT);
      com.fortuneandfavors.net.FfVfx.shape(level, com.fortuneandfavors.net.FfVfx.CLASH, ParticleTypes.CRIT, wound, player.getLookAngle(), 0.0, 0.0, SCARLET);
      blood(level, target.getX(), target.getY() + 1.0, target.getZ(), 24);
      return true;
   }

   /** Blood Spears - her volley, thrown by hand. */
   private static boolean castSpears(ServerPlayer player) {
      LivingEntity target = lookedAt(player, SPELL_RANGE);
      if (target == null || !(player.level() instanceof ServerLevel level)) {
         return false;
      }
      SPEARS.add(new Spear(target.getUUID(), level, player, player.getEyePosition(), 3, ServerClock.clock(level) + 5L));
      // Three spears drawn up out of the caster's own blood before they are thrown.
      Vec3 behind = player.getEyePosition().add(player.getLookAngle().scale(-0.6));
      com.fortuneandfavors.net.FfVfx.shape(level, com.fortuneandfavors.net.FfVfx.TEAR, ParticleTypes.CRIMSON_SPORE, behind.add(0.0, 0.5, 0.0), new Vec3(-player.getLookAngle().z, 0.0, player.getLookAngle().x), 1.4, 16, SCARLET);
      level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ARROW_SHOOT, SoundSource.PLAYERS, 1.2F, 0.7F);
      return true;
   }

   /**
    * Night Swarm - her bats, and the blind that goes with them.
    *
    * <p>The bats used to be ordinary bats: spawned, given no owner and no target,
    * they just flapped off. They are tracked as a swarm now - each one hunts the
    * nearest enemy of the caster and bites it - so the spell is a swarm rather
    * than a cosmetic puff.
    */
   private static boolean castSwarm(ServerPlayer player) {
      if (!(player.level() instanceof ServerLevel level)) {
         return false;
      }
      for (ServerPlayer other : level.getServer().getPlayerList().getPlayers()) {
         if (other != player && !isAlly(player, other) && other.level() == level && other.distanceToSqr(player) < 144.0) {
            other.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 60, 0, false, false, true));
         }
      }
      for (Entity e : level.getEntities(player, player.getBoundingBox().inflate(12.0), en -> en instanceof Monster m && m.isAlive())) {
         if (e instanceof LivingEntity le) {
            le.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 60, 0, false, false, true));
         }
      }

      for (int i = 0; i < BAT_COUNT; i++) {
         double a = RANDOM.nextDouble() * Math.PI * 2.0;
         double r = 1.5 + RANDOM.nextDouble() * 2.5;
         Mob bat = (Mob) EntityTypes.BAT.create(level, EntitySpawnReason.EVENT);
         if (bat == null) {
            continue;
         }
         bat.setPos(player.getX() + Math.cos(a) * r, player.getY() + 1.2, player.getZ() + Math.sin(a) * r);
         bat.setPersistenceRequired();
         bat.setCustomName(Component.literal("§4" + player.getName().getString() + "'s bat"));
         bat.setCustomNameVisible(false);
         level.addFreshEntity(bat);
         BATS.put(bat.getUUID(), new Bat(player.getUUID()));
      }

      level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.BAT_TAKEOFF, SoundSource.PLAYERS, 1.4F, 0.7F);
      // Night falls outward from the caster to the edge of the blind, and the swarm bursts out of it.
      com.fortuneandfavors.net.FfVfx.shape(level, com.fortuneandfavors.net.FfVfx.NOVA, ParticleTypes.LARGE_SMOKE, player.position().add(0.0, 0.4, 0.0), Vec3.ZERO, 12.0, 0.0, CLOT);
      com.fortuneandfavors.net.FfVfx.shape(level, com.fortuneandfavors.net.FfVfx.WORMHOLE, ParticleTypes.LARGE_SMOKE, player.position().add(0.0, 1.0, 0.0), Vec3.ZERO, 0.0, 1.0, CLOT);
      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.LARGE_SMOKE, player.getX(), player.getY() + 1.0, player.getZ(), 30, 1.4, 0.9, 1.4, 0.03);
      return true;
   }

   /**
    * Blood Rain - a crimson downpour over everyone who is not yours.
    *
    * <p>It used to only look for {@code Monster}s, so casting it over a field of
    * ordinary mobs (or on your own) produced nothing at all; it now drains every
    * living thing in range that is not the caster or blood-sworn.
    */
   private static boolean castRain(ServerPlayer player) {
      if (!(player.level() instanceof ServerLevel level)) {
         return false;
      }
      double drained = 0.0;
      int hits = 0;
      for (Entity e : level.getEntities(player, player.getBoundingBox().inflate(RAIN_RADIUS), en -> en instanceof LivingEntity le && le.isAlive() && le != player)) {
         if (!(e instanceof LivingEntity le) || isAllyLiving(player, le)) {
            continue;
         }
         // Credited to the caster, so what the rain kills counts as theirs. Plain magic() left
         // every kill unowned - no kill credit, no loot looting, no feat.
         le.hurtServer(level, level.damageSources().indirectMagic(player, player), RAIN_DAMAGE);
         le.addEffect(new MobEffectInstance(MobEffects.WITHER, 60, 0, false, false, true));
         drained += RAIN_DAMAGE;
         hits++;
         if (hits <= 8) {
            // A drop falls on each victim, and what it takes runs back to the caster.
            Vec3 hit = le.position().add(0.0, le.getBbHeight() * 0.6, 0.0);
            com.fortuneandfavors.net.FfVfx.shape(level, com.fortuneandfavors.net.FfVfx.METEOR, ParticleTypes.CRIMSON_SPORE, hit.add(0.0, 7.0, 0.0), hit, 0.0, 6.0, SCARLET);
            com.fortuneandfavors.net.FfVfx.shape(level, com.fortuneandfavors.net.FfVfx.BEAM, ParticleTypes.CRIMSON_SPORE, hit, player.position().add(0.0, 1.0, 0.0), 0.0, 0.0, GLINT);
         }
      }
      if (drained > 0.0) {
         player.setHealth(Math.min(player.getMaxHealth(), player.getHealth() + (float) Math.min(8.0, drained * 0.35)));
      }
      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.CRIMSON_SPORE, player.getX(), player.getY() + 6.0, player.getZ(), 120, 7.0, 1.0, 7.0, 0.3);
      // The circle the rain falls inside, drawn on the ground at its true radius.
      com.fortuneandfavors.net.FfVfx.shape(level, com.fortuneandfavors.net.FfVfx.SUMMON_CIRCLE, ParticleTypes.CRIMSON_SPORE, player.position().add(0.0, 0.1, 0.0), Vec3.ZERO, RAIN_RADIUS, 50.0, SCARLET);
      com.fortuneandfavors.net.FfVfx.shape(level, com.fortuneandfavors.net.FfVfx.PILLAR, ParticleTypes.CRIMSON_SPORE, player.position(), Vec3.ZERO, 8.0, 0.0, CLOT);
      level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ENDER_DRAGON_GROWL, SoundSource.PLAYERS, 1.0F, 0.6F);
      if (hits == 0) {
         bar(player, "§4Blood Rain §8| §7nothing in the downpour to drink");
      }
      return true;
   }

   // --------------------------------------------------------------- the prism

   /**
    * Blood Prism right-click.
    *
    * <p>On a mob: opens a channelled drain and, when the target's health reaches
    * zero, unmakes it and raises a servant in its place.
    *
    * <p>On a player: the same channel against them. A player killed by the ritual
    * loses <b>nothing</b> - their inventory is written straight back - and the
    * servant that rises wears only a copy of their armour, holding no gear at all.
    * It is a duel-ending ritual, not the Scarlet Devil's robbery.
    */
   public static String usePrism(ServerPlayer player, ItemStack held) {
      // Every outcome lands in the action bar, like the Grimoire: a legendary you
      // use over and over should not narrate itself into chat.
      if (!(player.level() instanceof ServerLevel level)) {
         bar(player, "§4Prism §8| §7needs solid ground to work on");
         return null;
      }
      if (RITUALS.containsKey(player.getUUID())) {
         bar(player, "§4Prism §8| §7you are already channelling");
         return null;
      }
      LivingEntity target = lookedAt(player, RITUAL_RANGE);
      if (target == null) {
         bar(player, "§4Prism §8| §7look at a mob or a player to bleed them");
         return null;
      }
      if (target == player) {
         bar(player, "§4Prism §8| §7it will not drink from its own hand");
         return null;
      }
      if (target instanceof ServerPlayer other && isAlly(player, other)) {
         bar(player, "§4Prism §8| §7the blood-sworn cannot be bled");
         return null;
      }
      if (target instanceof ServerPlayer && !player.gameMode.isSurvival()) {
         bar(player, "§4Prism §8| §7you must be in survival to bleed a player");
         return null;
      }

      long now = ServerClock.clock(level);
      long left = ModItems.cooldownSecondsLeft(held, now);
      if (left > 0L) {
         bar(player, "§4Prism §8| §7still clouded §8- §f" + left + "s");
         return null;
      }
      if (countServants(player.getUUID()) >= SERVANT_MAX) {
         bar(player, "§4Prism §8| §7you already command §f" + SERVANT_MAX + "§7 Revenants");
         return null;
      }

      // The Prism's own tier decides how hard the channel bites and how fast it beats, so it is
      // read here, at the one place a channel can start.
      int tier = Math.max(1, ModItems.tierOf(held));
      RITUALS.put(player.getUUID(), new Ritual(player.getUUID(), target.getUUID(), level, player.position(), now, tier));

      int ticks = 45 * 20;
      ModItems.setCooldownUntil(held, now + ticks);
      player.getCooldowns().addCooldown(held, ticks);

      // A visible tether, so both sides can see the channel and its direction.
      blood(level, player.getX(), player.getY() + 1.0, player.getZ(), 40);
      blood(level, target.getX(), target.getY() + 1.0, target.getZ(), 30);
      com.fortuneandfavors.net.FfVfx.shape(level, com.fortuneandfavors.net.FfVfx.SUMMON_CIRCLE, ParticleTypes.CRIMSON_SPORE, player.position().add(0.0, 0.1, 0.0), Vec3.ZERO, RITUAL_ANCHOR + 0.5, 40.0, SCARLET);
      com.fortuneandfavors.net.FfVfx.shape(level, com.fortuneandfavors.net.FfVfx.CLOCK_BURST, ParticleTypes.CRIMSON_SPORE, target.position().add(0.0, target.getBbHeight() * 0.5, 0.0), Vec3.ZERO, 1.6, 0.0, GLINT);
      level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.WITCH_CELEBRATE, SoundSource.PLAYERS, 1.0F, 0.7F);
      bar(
         player,
         "§4RITUAL §8| §fTier " + CustomEnchantments.roman(tier) + " §8| §7stand still§8 - §7the drain breaks if you move or are hit"
      );
      if (target instanceof ServerPlayer other) {
         bar(other, "§4" + player.getName().getString() + " §7is draining you - §fhurt them or run§7 to break it");
      }
      return null;
   }

   // ------------------------------------------------------------------- ticks

   public static void tick(MinecraftServer server) {
      if (server == null) {
         return;
      }
      SAFE_DEATH.entrySet().removeIf(e -> e.getValue().expiresAt <= System.currentTimeMillis());
      tickSpears(server);
      tickServants(server);
      tickBats(server);
      tickRituals(server);
      tickSworn(server);
   }

   private static void tickSpears(MinecraftServer server) {
      for (Iterator<Spear> it = SPEARS.iterator(); it.hasNext();) {
         Spear s = it.next();
         if (s.remaining <= 0 || !s.caster.isAlive()) {
            it.remove();
            continue;
         }
         if (ServerClock.clock(s.level) < s.next) {
            continue;
         }
         // Resolve the target generically. The volley used to look the target up
         // through the player list only, so aiming at a mob cancelled the whole
         // cast on the very next tick - which is why Blood Spears "did nothing".
         LivingEntity target = resolveLiving(server, s.level, s.target);
         if (target == null || !target.isAlive()) {
            it.remove();
            continue;
         }
         Vec3 eye = target.position().add(0.0, target.getBbHeight() * 0.5, 0.0);
         // Thrown from where the caster stands now, not from where they stood when they began.
         s.origin = s.caster.getEyePosition().add(0.0, -0.2, 0.0);
         Vec3 dir = eye.subtract(s.origin);
         if (dir.lengthSqr() < 1.0E-4) {
            dir = new Vec3(0.0, -1.0, 0.0);
         }
         net.minecraft.world.entity.projectile.arrow.Arrow arrow = new net.minecraft.world.entity.projectile.arrow.Arrow(
            s.level, s.caster, new ItemStack(Items.REDSTONE), new ItemStack(Items.BOW)
         );
         arrow.setPos(s.origin.x, s.origin.y, s.origin.z);
         arrow.setDeltaMovement(dir.normalize().scale(SPEAR_SPEED));
         // An arrow hits for its speed times its base damage, so a base of SPEAR_DAMAGE at this
         // speed was a 14-point spear. Divided back out, each spear hits for SPEAR_DAMAGE.
         arrow.setBaseDamage(SPEAR_DAMAGE / SPEAR_SPEED);
         com.fortuneandfavors.net.FfVfx.shape(s.level, com.fortuneandfavors.net.FfVfx.BEAM, ParticleTypes.CRIMSON_SPORE, s.origin, eye, 0.0, 0.0, SCARLET);
         com.fortuneandfavors.net.FfVfx.shape(s.level, com.fortuneandfavors.net.FfVfx.MUZZLE, ParticleTypes.CRIMSON_SPORE, s.origin, dir.normalize(), 0.0, 0.0, GLINT);
         arrow.setCritArrow(false);
         arrow.pickup = net.minecraft.world.entity.projectile.arrow.AbstractArrow.Pickup.DISALLOWED;
         s.level.addFreshEntity(arrow);
         s.level.sendParticles(ParticleTypes.CRIMSON_SPORE, s.origin.x, s.origin.y, s.origin.z, 8, 0.2, 0.2, 0.2, 0.05);
         s.remaining--;
         s.next = ServerClock.clock(s.level) + 3L;
         if (s.remaining <= 0) {
            it.remove();
         }
      }
   }

   /** The swarm: every bat hunts the caster's nearest enemy and bites it. */
   private static void tickBats(MinecraftServer server) {
      if (BATS.isEmpty()) {
         return;
      }
      for (Iterator<Map.Entry<UUID, Bat>> it = BATS.entrySet().iterator(); it.hasNext();) {
         Map.Entry<UUID, Bat> entry = it.next();
         Bat bat = entry.getValue();
         Entity raw = findEntity(server, entry.getKey());
         if (!(raw instanceof Mob mob) || !mob.isAlive() || --bat.ticksLeft <= 0) {
            if (raw instanceof Mob dying && dying.isAlive() && bat.ticksLeft <= 0) {
               dying.discard();
            }
            it.remove();
            continue;
         }
         ServerLevel level = (ServerLevel) mob.level();
         long now = ServerClock.clock(level);
         if (now % 10L == 0L) {
            level.sendParticles(new DustParticleOptions(-65536, 0.7F), mob.getX(), mob.getY() + 0.3, mob.getZ(), 1, 0.15, 0.15, 0.15, 0.0);
         }

         ServerPlayer owner = server.getPlayerList().getPlayer(bat.owner);
         LivingEntity foe = nearestFoe(level, mob, bat.owner);
         if (foe == null) {
            // Idle: drift back to the caster rather than scattering to the horizon.
            if (owner != null && mob.distanceToSqr(owner) > 9.0) {
               Vec3 toward = owner.position().add(0.0, 1.4, 0.0).subtract(mob.position());
               if (toward.lengthSqr() > 1.0E-4) {
                  mob.setDeltaMovement(toward.normalize().scale(0.34));
                  mob.hurtMarked = true;
               }
            }
            continue;
         }

         Vec3 toward = foe.position().add(0.0, foe.getBbHeight() * 0.6, 0.0).subtract(mob.position());
         if (toward.lengthSqr() > 0.04) {
            mob.setDeltaMovement(toward.normalize().scale(0.42));
            mob.hurtMarked = true;
         }
         mob.setTarget(foe);
         if (now >= bat.nextBite && mob.distanceToSqr(foe) < 5.0) {
            bat.nextBite = now + 20L;
            foe.hurtServer(level, level.damageSources().mobAttack(mob), BAT_DAMAGE);
            blood(level, foe.getX(), foe.getY() + 1.0, foe.getZ(), 5);
         }
      }
   }

   private static void tickServants(MinecraftServer server) {
      if (SERVANTS.isEmpty()) {
         return;
      }
      for (Iterator<Map.Entry<UUID, Servant>> it = SERVANTS.entrySet().iterator(); it.hasNext();) {
         Map.Entry<UUID, Servant> entry = it.next();
         Servant servant = entry.getValue();
         Entity raw = findEntity(server, entry.getKey());
         if (!(raw instanceof Mob mob) || !mob.isAlive()) {
            it.remove();
            continue;
         }
         if (--servant.ticksLeft <= 0) {
            scrub(mob);
            it.remove();
            continue;
         }
         ServerLevel level = (ServerLevel) mob.level();
         ServerPlayer owner = server.getPlayerList().getPlayer(servant.owner);
         if (ServerClock.clock(level) % 6L == 0L) {
            level.sendParticles(ParticleTypes.CRIMSON_SPORE, mob.getX(), mob.getY() + 0.6, mob.getZ(), 3, 0.3, 0.5, 0.3, 0.02);
         }

         // Hard guard: an ally is never a target. Vanilla's own player-seeking
         // goal can set one behind our back, which is why the servant used to bite
         // the person who summoned it.
         LivingEntity current = mob.getTarget();
         if (current instanceof ServerPlayer sp && isAlly(owner, sp)) {
            mob.setTarget(null);
            current = null;
         }
         if (current instanceof LivingEntity le && isFriendlyServant(le)) {
            mob.setTarget(null);
            current = null;
         }

         LivingEntity foe = current != null && current.isAlive() ? current : nearestFoe(level, mob, servant.owner);
         if (foe != null) {
            mob.setTarget(foe);
            // The servant fights for real: it closes and strikes on its own cadence
            // instead of hoping a vanilla goal fires.
            double dist = mob.distanceToSqr(foe);
            if (dist > 3.0) {
               // Walked, not slid: the old step moved the body by setPos, straight through walls
               // and out over drops. The path is asked for twice a second; only when there is no
               // path at all does it fall back to the straight-line step it used to take.
               if (servant.ticksLeft % 10 == 0 && !mob.getNavigation().moveTo(foe, dist > 100.0 ? 1.4 : 1.15)) {
                  Vec3 toward = foe.position().subtract(mob.position()).normalize().scale(0.3);
                  mob.setPos(mob.getX() + toward.x, mob.getY(), mob.getZ() + toward.z);
               }
               mob.setYRot(faceYaw(mob, foe));
            } else if (ServerClock.clock(level) >= servant.nextAttack) {
               servant.nextAttack = ServerClock.clock(level) + 20L;
               foe.hurtServer(level, level.damageSources().mobAttack(mob), SERVANT_DAMAGE);
               blood(level, foe.getX(), foe.getY() + 1.0, foe.getZ(), 8);
            }
         } else if (owner != null) {
            // Idle: heel on the owner instead of wandering off.
            if (mob.distanceToSqr(owner) > 100.0 && servant.ticksLeft % 10 == 0) {
               mob.getNavigation().moveTo(owner, 1.2);
            }
            if (servant.ticksLeft % 100 == 0) {
               bar(owner, "§4Blood Revenant §8| §f" + (servant.ticksLeft / 20) + "s§7 of service left");
            }
         }
      }
   }

   /** The ritual channel: rooted caster, pulsed drain, cancelled by movement or a hit. */
   private static void tickRituals(MinecraftServer server) {
      if (RITUALS.isEmpty()) {
         return;
      }
      for (Iterator<Map.Entry<UUID, Ritual>> it = RITUALS.entrySet().iterator(); it.hasNext();) {
         Map.Entry<UUID, Ritual> entry = it.next();
         Ritual ritual = entry.getValue();
         ServerPlayer caster = server.getPlayerList().getPlayer(entry.getKey());
         LivingEntity target = resolveLiving(server, ritual.level, ritual.target);

         if (caster == null || !caster.isAlive()) {
            it.remove();
            continue;
         }
         if (ritual.hit) {
            it.remove();
            bar(caster, "§4RITUAL BROKEN §8| §7you were hit");
            failFx(ritual);
            continue;
         }
         if (!caster.level().equals(ritual.level) || caster.position().distanceToSqr(ritual.anchor) > RITUAL_ANCHOR * RITUAL_ANCHOR) {
            it.remove();
            bar(caster, "§4RITUAL BROKEN §8| §7you moved");
            failFx(ritual);
            continue;
         }
         if (target == null || !target.isAlive()) {
            it.remove();
            continue;
         }
         boolean targetIsPlayer = target instanceof ServerPlayer;
         boolean tooFar = caster.distanceToSqr(target) > RITUAL_RANGE * RITUAL_RANGE * 2.0;
         if (ritual.ticksLeft-- <= 0 || tooFar) {
            it.remove();
            bar(caster, tooFar ? "§4RITUAL BROKEN §8| §7the target escaped" : "§4RITUAL FADED §8| §7the blood went cold");
            failFx(ritual);
            continue;
         }

         Vec3 from = caster.getEyePosition().add(0.0, -0.3, 0.0);
         Vec3 to = target.position().add(0.0, target.getBbHeight() * 0.5, 0.0);
         Vec3 dir = to.subtract(from);
         double len = dir.length();
         if (len > 0.01 && ritual.ticksLeft % 4 == 0) {
            // One beam cue every few ticks. The old tether sent a particle packet for every third
            // of a block, every tick - sixty packets a second on a long channel, for one line.
            com.fortuneandfavors.net.FfVfx.shape(ritual.level, com.fortuneandfavors.net.FfVfx.BEAM, ParticleTypes.CRIMSON_SPORE, to, from, 0.0, 0.0, SCARLET);
         }
         if (targetIsPlayer) {
            bar((ServerPlayer) target, "§4BEING DRAINED §8| §f" + Math.max(0, (int) Math.ceil(target.getHealth() / 2.0)) + " ❤ §7- hurt them or run");
         }

         long now = ServerClock.clock(ritual.level);
         if (now < ritual.nextPulse) {
            continue;
         }
         ritual.nextPulse = now + pulseTicks(ritual.tier);

         float drain = Math.max(1.0F, target.getMaxHealth() * drainShare(ritual.tier));
         if (drain >= target.getHealth() + target.getAbsorptionAmount()) {
            // This pulse would kill. Finish the ritual instead of landing it, because the finish is
            // what holds a player's inventory before they die: a pulse that killed on its own
            // dropped everything on the floor, which is exactly what the ritual promises not to do.
            it.remove();
            completeRitual(caster, ritual, target);
            continue;
         }
         ritual.drained += drain;
         boolean landed = target.hurtServer(ritual.level, ritual.level.damageSources().indirectMagic(caster, caster), drain);
         target.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 60, 0, false, false, false));
         blood(ritual.level, target.getX(), target.getY() + 1.0, target.getZ(), 10);
         ritual.level.playSound(null, target.getX(), target.getY(), target.getZ(), SoundEvents.GENERIC_DRINK, SoundSource.PLAYERS, 0.8F, 0.6F);
         if (!landed && target.getHealth() > 0.001F) {
            // Immune or absorbed: the ritual cannot finish, so let it go rather
            // than draining a target who cannot die from it.
            ritual.ticksLeft = Math.min(ritual.ticksLeft, RITUAL_PULSE_TICKS * 3);
         }

         if (target.getHealth() <= 0.001F || !target.isAlive()) {
            it.remove();
            completeRitual(caster, ritual, target);
            continue;
         }
         bar(caster, "§4RITUAL §8| §f" + Math.max(0, (int) Math.ceil(target.getHealth() / 2.0)) + " ❤§7 left §8- §7stand still");
      }
   }

   private static void failFx(Ritual ritual) {
      LivingEntity target = resolveLiving(null, ritual.level, ritual.target);
      double x = target != null ? target.getX() : ritual.anchor.x;
      double y = target != null ? target.getY() : ritual.anchor.y;
      double z = target != null ? target.getZ() : ritual.anchor.z;
      com.fortuneandfavors.net.FfVfx.particles(ritual.level, ParticleTypes.POOF, x, y + 1.0, z, 24, 1.0, 1.0, 1.0, 0.05);
      com.fortuneandfavors.net.FfVfx.shape(ritual.level, com.fortuneandfavors.net.FfVfx.RING, ParticleTypes.SMOKE, new Vec3(x, y + 0.2, z), Vec3.ZERO, 1.8, 0.0, CLOT);
      ritual.level.playSound(null, x, y, z, SoundEvents.FIRE_EXTINGUISH, SoundSource.PLAYERS, 1.0F, 0.8F);
   }

   /** The drain finished: the target dies and a servant rises wearing what it wore. */
   private static void completeRitual(ServerPlayer caster, Ritual ritual, LivingEntity target) {
      ServerLevel level = ritual.level;
      String victimName = victimName(target);
      // Armour copies are taken before anything dies, so a player's set survives
      // the safe death we are about to give them.
      ItemStack[] armour = new ItemStack[]{
         target.getItemBySlot(EquipmentSlot.HEAD).copy(),
         target.getItemBySlot(EquipmentSlot.CHEST).copy(),
         target.getItemBySlot(EquipmentSlot.LEGS).copy(),
         target.getItemBySlot(EquipmentSlot.FEET).copy(),
         target.getItemBySlot(EquipmentSlot.MAINHAND).copy()
      };

      blood(level, target.getX(), target.getY() + 1.0, target.getZ(), 80);
      Vec3 body = target.position();
      com.fortuneandfavors.net.FfVfx.shape(level, com.fortuneandfavors.net.FfVfx.PILLAR, ParticleTypes.CRIMSON_SPORE, body, Vec3.ZERO, 9.0, 0.0, SCARLET);
      com.fortuneandfavors.net.FfVfx.shape(level, com.fortuneandfavors.net.FfVfx.NOVA, ParticleTypes.CRIMSON_SPORE, body.add(0.0, 0.3, 0.0), Vec3.ZERO, 5.0, 0.0, CLOT);
      com.fortuneandfavors.net.FfVfx.shape(level, com.fortuneandfavors.net.FfVfx.GOO_SPLASH, ParticleTypes.CRIMSON_SPORE, body.add(0.0, 1.0, 0.0), Vec3.ZERO, 2.2, 0.0, GLINT);
      level.playSound(null, target.getX(), target.getY(), target.getZ(), ModSounds.BOSS_DEATH, SoundSource.PLAYERS, 0.9F, 0.8F);
      level.playSound(null, target.getX(), target.getY(), target.getZ(), SoundEvents.WITHER_DEATH, SoundSource.PLAYERS, 0.6F, 1.4F);

      if (target instanceof ServerPlayer victim) {
         // A ritual kill costs nothing: the drop hook holds the inventory and
         // ScarletGear puts it straight back (see onPlayerDeath).
         captureForSafeDeath(victim);
         victim.hurtServer(level, level.damageSources().magic(), victim.getMaxHealth() * 4.0F + 100.0F);
         bar(victim, "§4The ritual takes you §8| §7the blood is owed nothing - §fyour items are safe");
         bar(caster, "§4RITUAL COMPLETE §8| §f" + victimName + " §7was bled dry");
      } else {
         target.hurtServer(level, level.damageSources().magic(), target.getMaxHealth() * 4.0F + 100.0F);
         bar(caster, "§4RITUAL COMPLETE §8| §f" + victimName + " §7rose as your servant");
      }

      spawnServant(level, target.getX(), target.getY(), target.getZ(), caster, victimName, armour);
   }

   // --------------------------------------------------------- ally protection

   /** Called when a player takes damage, so a channel breaks on the first hit. */
   public static void onCasterDamage(ServerPlayer player) {
      if (player == null) {
         return;
      }
      Ritual ritual = RITUALS.get(player.getUUID());
      if (ritual != null) {
         ritual.hit = true;
      }
   }

   /**
    * True when the given attacker is one of {@code victim}'s own servants or bats.
    * Used by the damage gate so a servant can never hurt the person who summoned
    * it, whatever its AI decides in the gap between our ticks.
    */
   public static boolean isFriendlyAttack(ServerPlayer victim, Entity attacker) {
      if (victim == null || attacker == null) {
         return false;
      }
      UUID id = attacker.getUUID();
      Servant servant = SERVANTS.get(id);
      if (servant != null && servant.owner.equals(victim.getUUID())) {
         return true;
      }
      Bat bat = BATS.get(id);
      return bat != null && bat.owner.equals(victim.getUUID());
   }

   /** True while a servant exists that belongs to this player. */
   private static boolean isFriendlyServant(LivingEntity entity) {
      return entity != null && entity.entityTags().contains(SERVANT_KEY);
   }

   // -------------------------------------------------------------- safe death

   /** True while a ritual is draining this player, so their loot is held. */
   public static boolean holdsSafeDeath(ServerPlayer player) {
      if (player == null) {
         return false;
      }
      SafeDeath held = SAFE_DEATH.get(player.getUUID());
      return held != null && held.expiresAt > System.currentTimeMillis();
   }

   private static void captureForSafeDeath(ServerPlayer victim) {
      List<ItemStack> snapshot = new ArrayList<>();
      Inventory inv = victim.getInventory();
      for (int i = 0; i < inv.getContainerSize(); i++) {
         ItemStack s = inv.getItem(i);
         snapshot.add(s == null ? ItemStack.EMPTY : s.copy());
      }
      // Held only for the two ticks it takes the killing blow to land. A totem of
      // undying leaves the player alive with the entry still pending, and an entry
      // with no expiry would then hand that player their old inventory back on
      // some unrelated death hours later.
      SAFE_DEATH.put(victim.getUUID(), new SafeDeath(snapshot, System.currentTimeMillis() + SAFE_DEATH_MILLIS));
   }

   /**
    * Puts a ritual victim's inventory back, exactly where it was. Runs from the
    * death hook, after vanilla has emptied the slots (which is why the items had
    * to be copied first) and before the player respawns - so the death is real,
    * the walk back is real, and nothing is lost.
    */
   public static void onPlayerDeath(ServerPlayer victim) {
      if (victim == null) {
         return;
      }
      SafeDeath held = SAFE_DEATH.remove(victim.getUUID());
      if (held == null || held.expiresAt <= System.currentTimeMillis()) {
         return;
      }
      List<ItemStack> snapshot = held.items;
      try {
         Inventory inv = victim.getInventory();
         for (int i = 0; i < snapshot.size() && i < inv.getContainerSize(); i++) {
            ItemStack s = snapshot.get(i);
            if (s != null && !s.isEmpty()) {
               inv.setItem(i, s);
            }
         }
         if (victim.containerMenu != null) {
            victim.containerMenu.broadcastChanges();
         }
         bar(victim, "§4Blood-owed death §8| §7nothing was taken from you");
      } catch (Throwable ignored) {
      }
   }

   // ---------------------------------------------------------------- helpers

   private static String victimName(LivingEntity target) {
      try {
         if (target.hasCustomName()) {
            return target.getCustomName().getString();
         }
      } catch (Throwable ignored) {
      }
      return target.getType().getDescription().getString();
   }

   /** Resolves a tracked target in a level: a player by list, anything else by id. */
   private static LivingEntity resolveLiving(MinecraftServer server, ServerLevel level, UUID id) {
      if (id == null) {
         return null;
      }
      if (level != null) {
         Entity local = level.getEntity(id);
         if (local instanceof LivingEntity le) {
            return le;
         }
      }
      if (server != null) {
         ServerPlayer p = server.getPlayerList().getPlayer(id);
         if (p != null) {
            return p;
         }
         for (ServerLevel l : server.getAllLevels()) {
            if (l.getEntity(id) instanceof LivingEntity le) {
               return le;
            }
         }
      }
      return null;
   }

   private static float faceYaw(Entity from, Entity to) {
      double dx = to.getX() - from.getX();
      double dz = to.getZ() - from.getZ();
      return (float) (Math.toDegrees(Math.atan2(-dx, dz)));
   }

   /** The nearest living thing in reach that is not the player - aim-assist for the bite. */
   private static LivingEntity nearestLiving(ServerPlayer player, double range) {
      LivingEntity best = null;
      double bestDist = range * range;
      for (Entity e : player.level().getEntities(player, player.getBoundingBox().inflate(range), en -> en instanceof LivingEntity le && le.isAlive() && le != player)) {
         if (e instanceof LivingEntity le && !isAllyLiving(player, le)) {
            double d = le.distanceToSqr(player);
            if (d < bestDist) {
               bestDist = d;
               best = le;
            }
         }
      }
      return best;
   }

   /** The nearest hostile mob, or a player who is not the owner or blood-sworn. */
   private static LivingEntity nearestFoe(ServerLevel level, Mob servant, UUID owner) {
      LivingEntity best = null;
      double bestDist = 24.0 * 24.0;
      for (Entity e : level.getEntities(servant, servant.getBoundingBox().inflate(24.0), en -> en instanceof LivingEntity le && le.isAlive() && le != servant)) {
         if (e instanceof ServerPlayer p && (p.getUUID().equals(owner) || SWORN.getOrDefault(p.getUUID(), 0) > 0)) {
            continue;
         }
         if (isFriendlyServant((LivingEntity) e) || BATS.containsKey(e.getUUID())) {
            continue;
         }
         if (e instanceof ServerPlayer p && owner != null && BossManager.isBloodThrall(p)) {
            continue;
         }
         if (e instanceof ServerPlayer || e instanceof Monster) {
            double d = e.distanceToSqr(servant);
            if (d < bestDist) {
               bestDist = d;
               best = (LivingEntity) e;
            }
         }
      }
      return best;
   }

   private static void tickSworn(MinecraftServer server) {
      if (SWORN.isEmpty()) {
         return;
      }
      for (Iterator<Map.Entry<UUID, Integer>> it = SWORN.entrySet().iterator(); it.hasNext();) {
         Map.Entry<UUID, Integer> entry = it.next();
         int left = entry.getValue() - 1;
         ServerPlayer p = server.getPlayerList().getPlayer(entry.getKey());
         if (left <= 0 || p == null || !p.isAlive()) {
            it.remove();
            if (p != null) {
               bar(p, "§7The blood-sworn mark fades.");
            }
            continue;
         }
         entry.setValue(left);
         if (left % 40 == 0 && p.level() instanceof ServerLevel level) {
            level.sendParticles(new DustParticleOptions(-65536, 0.8F), p.getX(), p.getY() + 1.2, p.getZ(), 2, 0.35, 0.5, 0.35, 0.0);
         }
      }
   }

   private static int countServants(UUID owner) {
      int n = 0;
      for (Servant s : SERVANTS.values()) {
         if (s.owner.equals(owner)) {
            n++;
         }
      }
      return n;
   }

   private static void scrub(Mob mob) {
      if (mob.level() instanceof ServerLevel level) {
         blood(level, mob.getX(), mob.getY() + 0.8, mob.getZ(), 40);
         level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), ModSounds.BOSS_DEATH, SoundSource.HOSTILE, 0.6F, 0.8F);
      }
      mob.discard();
   }

   /** Raises a servant at a spot, wearing (only) the armour copies it was given. */
   private static Mob spawnServant(ServerLevel level, double x, double y, double z, ServerPlayer owner, String name, ItemStack[] armour) {
      Mob servant = (Mob) EntityTypes.WITHER_SKELETON.create(level, EntitySpawnReason.COMMAND);
      if (servant == null) {
         return null;
      }
      AttributeInstance hp = servant.getAttribute(Attributes.MAX_HEALTH);
      if (hp != null) {
         hp.setBaseValue(40.0);
      }
      servant.setHealth(40.0F);
      AttributeInstance dmg = servant.getAttribute(Attributes.ATTACK_DAMAGE);
      if (dmg != null) {
         dmg.setBaseValue(6.0);
      }
      AttributeInstance follow = servant.getAttribute(Attributes.FOLLOW_RANGE);
      if (follow != null) {
         follow.setBaseValue(40.0);
      }
      servant.setPersistenceRequired();
      // "Say whoever died": the servant is named for the victim it was bled from,
      // not for the person who cast the ritual.
      servant.setCustomName(Component.literal("§4§lBlood Revenant of " + name));
      servant.setCustomNameVisible(true);
      servant.addTag(SERVANT_KEY);
      servant.addTag("ff_blood_servant_owner:" + owner.getUUID());
      if (armour != null) {
         EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.MAINHAND};
         for (int i = 0; i < slots.length && i < armour.length; i++) {
            if (armour[i] != null && !armour[i].isEmpty()) {
               servant.setItemSlot(slots[i], armour[i]);
               // A copy, not cargo: nothing this servant wears drops.
               servant.setDropChance(slots[i], 0.0F);
            }
         }
      }
      servant.setPos(x, y, z);
      level.addFreshEntity(servant);
      SERVANTS.put(servant.getUUID(), new Servant(owner.getUUID()));

      // It climbs out of a pool of the victim's blood.
      Vec3 pool = new Vec3(x, y + 0.05, z);
      com.fortuneandfavors.net.FfVfx.shape(level, com.fortuneandfavors.net.FfVfx.SUMMON_CIRCLE, ParticleTypes.CRIMSON_SPORE, pool, Vec3.ZERO, 2.0, 30.0, SCARLET);
      com.fortuneandfavors.net.FfVfx.shape(level, com.fortuneandfavors.net.FfVfx.GEYSER, ParticleTypes.CRIMSON_SPORE, pool, Vec3.ZERO, 3.0, 0.0, SCARLET);
      com.fortuneandfavors.net.FfVfx.shape(level, com.fortuneandfavors.net.FfVfx.RING, ParticleTypes.CRIMSON_SPORE, pool, Vec3.ZERO, 1.1, 0.0, GLINT);
      bar(owner, "§4The blood answers §8| §fa Blood Revenant of §f" + name + "§a fights for you for a minute");
      return servant;
   }

   /** True for the servant's owner and anyone the ritual has sworn to them. */
   /** Shared with {@link ClockworkGear}: one definition of "not a valid target". */
   public static boolean isAlly(ServerPlayer owner, ServerPlayer other) {
      if (owner == null || other == null) {
         return false;
      }
      Integer sworn = SWORN.get(other.getUUID());
      return other.getUUID().equals(owner.getUUID()) || sworn != null && sworn > 0;
   }

   /** Ally test for a non-player bystander: servants and bats of the caster are theirs. */
   private static boolean isAllyLiving(ServerPlayer owner, LivingEntity other) {
      if (owner == null || other == null) {
         return false;
      }
      if (other instanceof ServerPlayer sp) {
         return isAlly(owner, sp);
      }
      if (isFriendlyServant(other) || BATS.containsKey(other.getUUID())) {
         return true;
      }
      return BossManager.isFriendlySkeleton(other);
   }

   /** The entity the player is looking at, within {@code range}. */
   private static LivingEntity lookedAt(ServerPlayer player, double range) {
      Vec3 eye = player.getEyePosition();
      Vec3 look = player.getLookAngle();
      LivingEntity best = null;
      double bestDist = range * range;
      for (Entity e : player.level().getEntities(player, player.getBoundingBox().inflate(range), en -> en instanceof LivingEntity le && le.isAlive())) {
         Vec3 centre = e.position().add(0.0, e.getBbHeight() * 0.5, 0.0);
         Vec3 to = centre.subtract(eye);
         double dist = to.length();
         if (dist > range || dist < 0.2) {
            continue;
         }
         // 0.65 rather than 0.8: the old cone was so tight that a caster aiming at
         // a mob's feet simply cast nothing.
         if (to.scale(1.0 / dist).dot(look) < 0.65) {
            continue;
         }
         // Something you can see. Without this the Prism and the Spears locked onto a mob on the
         // other side of a wall, and a ritual could be started through solid stone.
         net.minecraft.world.phys.HitResult wall = player.level().clip(new net.minecraft.world.level.ClipContext(
            eye, centre, net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, player
         ));
         if (wall.getType() != net.minecraft.world.phys.HitResult.Type.MISS) {
            continue;
         }
         if (dist < bestDist) {
            bestDist = dist;
            best = (LivingEntity) e;
         }
      }
      return best;
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

   private static void blood(ServerLevel level, double x, double y, double z, int count) {
      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.CRIMSON_SPORE, x, y, z, count, 0.9, 0.9, 0.9, 0.1);
      com.fortuneandfavors.net.FfVfx.particles(level, new DustParticleOptions(-65536, 1.3F), x, y, z, count / 2, 0.8, 0.8, 0.8, 0.05);
   }

   /** Called on shutdown so no servant outlives the world it was summoned in. */
   public static void clear(MinecraftServer server) {
      for (UUID id : new ArrayList<>(SERVANTS.keySet())) {
         Safe.run("scarlet servant cleanup", () -> {
            Entity raw = findEntity(server, id);
            if (raw != null) {
               raw.discard();
            }
         });
      }
      SERVANTS.clear();
      SPEARS.clear();
      SWORN.clear();
      SPELL.clear();
      RITUALS.clear();
      SAFE_DEATH.clear();
      for (UUID id : new ArrayList<>(BATS.keySet())) {
         // Discarded with the servants: a persistent bat left behind is a "Steve's bat" that
         // outlives its spell, its owner and the server it was cast on.
         Safe.run("scarlet bat cleanup", () -> {
            Entity raw = findEntity(server, id);
            if (raw != null) {
               raw.discard();
            }
         });
      }
      BATS.clear();
   }
}
