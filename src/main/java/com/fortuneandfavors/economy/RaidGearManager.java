package com.fortuneandfavors.economy;

import com.fortuneandfavors.ModItems;
import com.fortuneandfavors.util.Chat;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.PropertyMap;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket.Action;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Vex;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.EvokerFangs;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap.Types;
import net.minecraft.world.phys.Vec3;


/**
 * Behaviors for the player raid legendary items:
 * - Raid Captain's Horn: summons up to 3 weighted raiders (pillager > vindicator > evoker)
 *   that fight beside the player for 60s. Long cooldown, max 3 alive.
 * - Evoker's Spellbook: right-click casts evoker fangs, sneak summons a vex ally
 *   (shorter-lived but tougher and harder-hitting; the cloak unlocks a second vex).
 * - Illusioner's Spellbook: blinds everyone near you for 5s (8s with the cloak), spawns
 *   3 fragile copies of you (5 with the cloak) that walk in formation at your side and
 *   mirror your attacks, and turns you truly invisible (no particles, no visible gear)
 *   for 20s - until you are hit, use the book again, die, leave or change dimension.
 * - Raiders Item Upgrader: upgrades a raid legendary in your offhand to Tier II/III.
 */
public final class RaidGearManager {
   private static final java.util.Random RANDOM = new java.util.Random();
   /** Horn raiders fight for 60s. */
   private static final long HORN_RAIDER_TICKS = 1200L;
   /** Spellbook vexes: shorter lifetime but stronger (25s). */
   private static final long SPELLBOOK_VEX_TICKS = 500L;
   /** Illusion vexes: fragile, short-lived (20s). */
   private static final long ILLUSION_VEX_TICKS = 400L;
   /** Illusioner's Spellbook player copies: 20s of chaos. */
   private static final long ILLUSION_COPY_TICKS = 400L;
   /** How much height difference a copy treats as ground rather than as flight. */
   private static final double COPY_STEP_UP = 1.0;
   /** Horn raiders: ally uuid -> owner uuid. */
   private static final Map<UUID, UUID> allyOwner = new HashMap<>();
   private static final Map<UUID, Long> allyBorn = new HashMap<>();
   /** Spellbook vex illusions: vex uuid -> owner uuid. */
   private static final Map<UUID, UUID> illusionOwner = new HashMap<>();
   private static final Map<UUID, Long> illusionBorn = new HashMap<>();
   private static final Map<UUID, Long> illusionStrike = new HashMap<>();
   /** Which summon a vex belongs to: "spellbook" or "illusion". */
   private static final Map<UUID, String> vexKind = new HashMap<>();
   /** Illusioner Spellbook player copies: copy uuid -> owner uuid. */
   private static final Map<UUID, UUID> copyOwner = new HashMap<>();
   private static final Map<UUID, Long> copyBorn = new HashMap<>();
   private static final Map<UUID, Long> copyStrike = new HashMap<>();
   /** Friendly evoker uuid -> tick when it may cast fangs again. */
   private static final Map<UUID, Long> allyFangCooldown = new HashMap<>();
   /** Server-side cooldowns (game time) - belt and braces alongside the item
    *  cooldown tracker so the horn / spellbooks can never be spammed even if
    *  the client-side item cooldown desyncs. */
   private static final Map<UUID, Long> captainHornCd = new HashMap<>();
   private static final Map<UUID, Long> evokerBookCd = new HashMap<>();
   private static final Map<UUID, Long> illusionBookCd = new HashMap<>();
   /** Wall-clock throttle for the tick's error log, so one broken summon cannot
    *  spam the console 20 times a second. */
   private static long lastTickErrorLog = 0L;

   private RaidGearManager() {
   }

   // === RAID CAPTAIN'S HORN ===

   /** Summons up to 3 raiders (weighted: pillager > vindicator > evoker) that help
    *  the player fight. Returns an error message, or null on success. */
   public static String useCaptainHorn(ServerPlayer owner) {
      try {
         ServerLevel level = (ServerLevel)owner.level();
         long now = ServerClock.clock(level);
         if (now < captainHornCd.getOrDefault(owner.getUUID(), 0L)) {
            return "The horn is still recovering.";
         }
         int living = livingAllyCount(owner);
         if (living >= 3) {
            return "Your raiders are still fighting - you can only have 3 at a time.";
         }

         int toSpawn = 3 - living;
         for (int i = 0; i < toSpawn; i++) {
            EntityType<? extends Mob> type = rollRaiderType();
            Mob raider = type.create(level, EntitySpawnReason.COMMAND);
            if (raider == null) {
               continue;
            }
            double a = RANDOM.nextDouble() * Math.PI * 2.0;
            double r = 2.5 + RANDOM.nextDouble() * 2.0;
            int x = owner.getBlockX() + (int)Math.round(Math.cos(a) * r);
            int z = owner.getBlockZ() + (int)Math.round(Math.sin(a) * r);
            int y = groundY(level, x, z, owner.getBlockY());
            raider.setPos(x + 0.5, y + 1.0, z + 0.5);
            raider.setPersistenceRequired();
            // Equip them like proper raiders
            if (type == EntityTypes.PILLAGER) {
               raider.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.CROSSBOW));
            } else if (type == EntityTypes.VINDICATOR) {
               raider.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_AXE));
               raider.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
            } else {
               raider.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOOK));
               // The friendly evoker carries a totem of undying - when it would
               // die, the totem blazes and it survives (vanilla totem behavior).
               raider.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.TOTEM_OF_UNDYING));
            }
            raider.setDropChance(EquipmentSlot.MAINHAND, 0.0F);
            raider.setDropChance(EquipmentSlot.HEAD, 0.0F);
            raider.setDropChance(EquipmentSlot.OFFHAND, 0.0F);
            String label = type == EntityTypes.PILLAGER ? "Raider Pillager" : (type == EntityTypes.VINDICATOR ? "Raider Vindicator" : "Raider Evoker");
            raider.setCustomName(Component.literal("§6" + owner.getName().getString() + "'s " + label));
            raider.setCustomNameVisible(true);
            tagFriendly(raider, owner.getUUID());
            level.addFreshEntity(raider);
            allyOwner.put(raider.getUUID(), owner.getUUID());
            allyBorn.put(raider.getUUID(), ServerClock.clock(level));
            Fx.shape(level, com.fortuneandfavors.net.FfVfx.SUMMON_CIRCLE, ParticleTypes.FLAME, raider.position(), net.minecraft.world.phys.Vec3.ZERO, 1.8, 30, 0xFFD24A);
            Fx.shape(level, com.fortuneandfavors.net.FfVfx.PILLAR, ParticleTypes.FLAME, raider.position(), net.minecraft.world.phys.Vec3.ZERO, 4.0, 0.0, 0xFF4A2A);
            com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.CAMPFIRE_COSY_SMOKE, raider.getX(), raider.getY() + 1.0, raider.getZ(), 14, 0.6, 0.8, 0.6, 0.04);
            com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.END_ROD, raider.getX(), raider.getY() + 1.2, raider.getZ(), 8, 0.3, 0.6, 0.3, 0.03);
         }
         level.playSound(null, owner.blockPosition(), SoundEvents.RAID_HORN.value(), SoundSource.PLAYERS, 1.4F, 0.9F);
         Fx.shape(level, com.fortuneandfavors.net.FfVfx.FROST_NOVA, ParticleTypes.FLAME, owner.position(), net.minecraft.world.phys.Vec3.ZERO, 7.0, 0.0, 0xFFD24A);
         Fx.shape(level, com.fortuneandfavors.net.FfVfx.RING, ParticleTypes.FLAME, owner.position().add(0.0, 1.2, 0.0), net.minecraft.world.phys.Vec3.ZERO, 3.0, 0.0, 0xFF4A2A);
         Chat.raw(owner, "§6§lThe horn blares - §f" + toSpawn + " raider" + (toSpawn == 1 ? "" : "s") + " answer your call! §7(60s ally)");
         int tier = ModItems.tierOf(owner.getMainHandItem());
         captainHornCd.put(owner.getUUID(), ServerClock.clock(level) + Math.max(1, 5 - (tier - 1)) * 400L); // I=100s, II=80s, III=60s
         return null;
      } catch (Exception e) {
         return "The horn fizzles.";
      }
   }

   /** Pillager 60%, vindicator 30%, evoker 10%. */
   private static EntityType<? extends Mob> rollRaiderType() {
      int roll = RANDOM.nextInt(100);
      if (roll < 10) {
         return EntityTypes.EVOKER;
      } else if (roll < 40) {
         return EntityTypes.VINDICATOR;
      }
      return EntityTypes.PILLAGER;
   }

   // === EVOKER'S SPELLBOOK ===

   /** Right-click: cast evoker fangs toward where the player looks. Sneak: summon
    *  a vex ally (two with the Evoker's Cloak). Server-side cooldown enforced
    *  here on top of the item cooldown tracker. */
   public static String useEvokerSpellbook(ServerPlayer owner) {
      long now = ServerClock.clock(owner.level());
      if (now < evokerBookCd.getOrDefault(owner.getUUID(), 0L)) {
         return "The spellbook is still recharging.";
      }
      String err = owner.isShiftKeyDown() ? summonVexAlly(owner) : castPlayerFangs(owner);
      if (err != null) {
         return err;
      }
      evokerBookCd.put(owner.getUUID(), ServerClock.clock(owner.level()) + (owner.isShiftKeyDown() ? 500L : 120L));
      return null;
   }

   private static String castPlayerFangs(ServerPlayer owner) {
      try {
         ServerLevel level = (ServerLevel)owner.level();
         boolean cloak = ModItems.isEvokerCloak(owner.getItemBySlot(EquipmentSlot.CHEST));
         Vec3 from = owner.position();
         // Flat, not the look vector: a caster looking at the floor used to have every fang
         // land at their own feet, because the pitch ate the horizontal reach.
         Vec3 dir = horizontal(owner.getLookAngle(), owner.getYRot());
         // EvokerFangs takes its yaw in radians, the way vanilla's evoker passes it.
         float yRot = (float)Math.atan2(dir.z, dir.x);
         double len = 7.0;
         int rows = cloak ? 9 : 6;
         for (int step = 0; step < rows; step++) {
            double f = (step + 1) / (double)rows * len;
            double fx = from.x + dir.x * f;
            double fz = from.z + dir.z * f;
            double gy = fangFloor(level, fx, fz, owner.getY() + 2.0, owner.getY() - 4.0);
            if (Double.isNaN(gy)) {
               // A gap or a wall: vanilla's evoker skips the fang rather than hanging it in the air.
               continue;
            }
            EvokerFangs fang = new EvokerFangs(level, fx, gy, fz, yRot, step * 2, owner);
            Fx.shape(level, com.fortuneandfavors.net.FfVfx.RING, ParticleTypes.ENCHANT, new net.minecraft.world.phys.Vec3(fx, gy + 0.1, fz), net.minecraft.world.phys.Vec3.ZERO, 0.8, 0.0, 0xC8F08A);
            level.addFreshEntity(fang);
         }
         com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.ENCHANT, owner.getX(), owner.getY() + 1.4, owner.getZ(), 18, 0.4, 0.5, 0.4, 0.35);
         Fx.shape(level, com.fortuneandfavors.net.FfVfx.SUMMON_CIRCLE, ParticleTypes.ENCHANT, owner.position(), net.minecraft.world.phys.Vec3.ZERO, 2.2, 24, 0xC8F08A);
         Fx.shape(level, com.fortuneandfavors.net.FfVfx.ICE_BURST, ParticleTypes.ENCHANT, owner.position().add(0.0, 1.6, 0.0), net.minecraft.world.phys.Vec3.ZERO, 0.8, 0.0, 0xC8F08A);
         level.playSound(null, owner.blockPosition(), SoundEvents.EVOKER_CAST_SPELL, SoundSource.PLAYERS, 0.9F, 1.1F);
         return null;
      } catch (Exception e) {
         return "The spellbook fizzles.";
      }
   }

   /** Casts a short line of evoker fangs from the evoker toward its target. */
   private static void castEvokerFangsAt(Mob evoker, LivingEntity target) {
      try {
         if (!(evoker.level() instanceof ServerLevel level) || target == null || !target.isAlive()) {
            return;
         }
         Vec3 from = evoker.position();
         Vec3 dir = horizontal(target.position().subtract(from), evoker.getYRot());
         float yRot = (float)Math.atan2(dir.z, dir.x);
         double reach = Math.min(6.0, evoker.distanceTo(target));
         double top = Math.max(evoker.getY(), target.getY()) + 1.0;
         double bottom = Math.min(evoker.getY(), target.getY()) - 1.0;
         for (int step = 0; step < 5; step++) {
            double f = (step + 1) / 5.0 * reach;
            double fx = from.x + dir.x * f;
            double fz = from.z + dir.z * f;
            double gy = fangFloor(level, fx, fz, top, bottom);
            if (Double.isNaN(gy)) {
               continue;
            }
            EvokerFangs fang = new EvokerFangs(level, fx, gy, fz, yRot, step * 2, evoker);
            level.addFreshEntity(fang);
         }
         com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.ENCHANT, evoker.getX(), evoker.getY() + 1.2, evoker.getZ(), 10, 0.4, 0.5, 0.4, 0.3);
         level.playSound(null, evoker.blockPosition(), SoundEvents.EVOKER_CAST_SPELL, SoundSource.PLAYERS, 0.8F, 1.2F);
      } catch (Exception ignored) {
      }
   }

   private static String summonVexAlly(ServerPlayer owner) {
      try {
         ServerLevel level = (ServerLevel)owner.level();
         int max = ModItems.isEvokerCloak(owner.getItemBySlot(EquipmentSlot.CHEST)) ? 2 : 1;
         int living = livingIllusionCount(owner);
         if (living >= max) {
            return max == 1 ? "Your vex is still fighting - you can only have one at a time." : "Your two vexes are still fighting - wait for one to fall.";
         }
         for (int i = living; i < max; i++) {
            Vex vex = (Vex)EntityTypes.VEX.create(level, EntitySpawnReason.COMMAND);
            if (vex == null) {
               continue;
            }
            vex.setPos(owner.getX() + (RANDOM.nextDouble() - 0.5) * 1.4, owner.getY() + 1.4 + RANDOM.nextDouble() * 0.6, owner.getZ() + (RANDOM.nextDouble() - 0.5) * 1.4);
            vex.setPersistenceRequired();
            vex.setCustomName(Component.literal("§5" + owner.getName().getString() + "'s Vex"));
            vex.setCustomNameVisible(false);
            vexKind.put(vex.getUUID(), "spellbook");
            // Spellbook vexes: shorter lifetime, but tougher and hit harder.
            AttributeInstance hp = vex.getAttribute(Attributes.MAX_HEALTH);
            if (hp != null) {
               hp.setBaseValue(20.0);
            }
            AttributeInstance atk = vex.getAttribute(Attributes.ATTACK_DAMAGE);
            if (atk != null) {
               atk.setBaseValue(8.0);
            }
            vex.setHealth(vex.getMaxHealth());
            tagFriendly(vex, owner.getUUID());
            level.addFreshEntity(vex);
            illusionOwner.put(vex.getUUID(), owner.getUUID());
            illusionBorn.put(vex.getUUID(), ServerClock.clock(level));
            illusionStrike.put(vex.getUUID(), ServerClock.clock(level) + 30L);
            com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.REVERSE_PORTAL, vex.getX(), vex.getY(), vex.getZ(), 16, 0.4, 0.6, 0.4, 0.25);
            Fx.shape(level, com.fortuneandfavors.net.FfVfx.TEAR, ParticleTypes.REVERSE_PORTAL, vex.position(), new net.minecraft.world.phys.Vec3(1.0, 0.0, 0.0), 0.8, 18, 0x9BB8FF);
         }
         level.playSound(null, owner.blockPosition(), SoundEvents.EVOKER_CAST_SPELL, SoundSource.PLAYERS, 0.8F, 1.6F);
         return null;
      } catch (Exception e) {
         return "The spellbook fizzles.";
      }
   }

   /** One-shot: Evoker's Cloak active that summons a spellbook vex ally. */
   public static String summonCloakVex(ServerPlayer owner) {
      try {
         ServerLevel level = (ServerLevel)owner.level();
         Vex vex = (Vex)EntityTypes.VEX.create(level, EntitySpawnReason.COMMAND);
         if (vex == null) {
            return "The vex refuses to manifest.";
         }
         vex.setPos(owner.getX() + (RANDOM.nextDouble() - 0.5) * 1.4, owner.getY() + 1.4 + RANDOM.nextDouble() * 0.6, owner.getZ() + (RANDOM.nextDouble() - 0.5) * 1.4);
         vex.setPersistenceRequired();
         vex.setCustomName(Component.literal("§5" + owner.getName().getString() + "'s Vex"));
         vex.setCustomNameVisible(false);
         vexKind.put(vex.getUUID(), "spellbook");
         AttributeInstance hp = vex.getAttribute(Attributes.MAX_HEALTH);
         if (hp != null) {
            hp.setBaseValue(20.0);
         }
         AttributeInstance atk = vex.getAttribute(Attributes.ATTACK_DAMAGE);
         if (atk != null) {
            atk.setBaseValue(8.0);
         }
         vex.setHealth(vex.getMaxHealth());
         tagFriendly(vex, owner.getUUID());
         level.addFreshEntity(vex);
         illusionOwner.put(vex.getUUID(), owner.getUUID());
         illusionBorn.put(vex.getUUID(), ServerClock.clock(level));
         illusionStrike.put(vex.getUUID(), ServerClock.clock(level) + 30L);
         com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.REVERSE_PORTAL, vex.getX(), vex.getY(), vex.getZ(), 16, 0.4, 0.6, 0.4, 0.25);
         Fx.shape(level, com.fortuneandfavors.net.FfVfx.TEAR, ParticleTypes.REVERSE_PORTAL, vex.position(), new net.minecraft.world.phys.Vec3(1.0, 0.0, 0.0), 0.8, 18, 0x9BB8FF);
         return null;
      } catch (Exception e) {
         return "The vex refuses to manifest.";
      }
   }

   // === ILLUSIONER'S SPELLBOOK ===

   /** Blinds everyone near you for 5s (8s with the cloak), spawns 3 illusion
    *  copies (5 with the cloak) that walk with you, and makes you truly invisible.
    *  Used again while the illusion is live, it cancels it instead. */
   public static String useIllusionerSpellbook(ServerPlayer owner) {
      try {
         ServerLevel level = (ServerLevel)owner.level();
         long now = ServerClock.clock(level);
         if (cancelIllusion(owner)) {
            return null;
         }
         if (now < illusionBookCd.getOrDefault(owner.getUUID(), 0L)) {
            return "The illusion is still settling.";
         }
         boolean cloak = ModItems.isIllusionerCloak(owner.getItemBySlot(EquipmentSlot.CHEST));
         int blindTicks = cloak ? 160 : 100;
         int illusionCount = cloak ? 5 : 3;
         double radius = 10.0;

         int blindedPlayers = 0;
         int stunnedMobs = 0;
         for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class,
               new net.minecraft.world.phys.AABB(owner.getX() - radius, owner.getY() - 4, owner.getZ() - radius,
                  owner.getX() + radius, owner.getY() + 4, owner.getZ() + radius))) {
            if (e == owner || !e.isAlive()) {
               continue;
            }
            if (e instanceof ServerPlayer) {
               e.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, blindTicks, 0, false, true, true));
               e.addEffect(new MobEffectInstance(MobEffects.GLOWING, blindTicks, 0, false, true, true));
               blindedPlayers++;
            } else if (e instanceof Mob mob && !BossManager.isFriendlySkeleton(mob)) {
               // "Mobs hit by it can't do anything for 5 seconds" - heavy stun debuff
               e.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, blindTicks, 0, false, true, true));
               e.addEffect(new MobEffectInstance(MobEffects.GLOWING, blindTicks, 0, false, true, true));
               e.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, blindTicks, 6, false, true, true));
               e.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, blindTicks, 5, false, true, true));
               stunnedMobs++;
            }
         }

         // The window's state is built first: the copies take their ring slots from its yaw.
         IllusionState state = new IllusionState(ServerClock.clock(level) + ILLUSION_COPY_TICKS, owner.getId(), level, owner.getYRot());
         for (int i = 0; i < illusionCount; i++) {
            Vec3 at = formationSlot(owner, level, null, state.formationYaw, i, illusionCount);
            Player copy = spawnIllusionCopy(owner, level, at);
            if (copy == null) {
               continue;
            }
            copyOwner.put(copy.getUUID(), owner.getUUID());
            copyBorn.put(copy.getUUID(), ServerClock.clock(level));
            copyStrike.put(copy.getUUID(), ServerClock.clock(level) + 20L + i * 10L);
            copySlot.put(copy.getUUID(), i);
            copySlotCount.put(copy.getUUID(), illusionCount);
            // Fx only: a raw sendParticles here reached modded clients too, who then saw the
            // vanilla portal cloud stacked on top of their custom beam.
            Fx.beam(level, ParticleTypes.PORTAL, owner.position().add(0.0, 1.0, 0.0), copy.position().add(0.0, 1.0, 0.0), ILLUSION_PURPLE);
            Fx.iceBurst(level, ParticleTypes.PORTAL, copy.position().add(0.0, 1.0, 0.0), 0.8, ILLUSION_PURPLE);
         }
         Fx.nova(level, ParticleTypes.PORTAL, owner.position(), 4.0, ILLUSION_PURPLE);
         Fx.clockBurst(level, ParticleTypes.PORTAL, owner.position().add(0.0, 1.0, 0.0), 3.0, ILLUSION_PURPLE);
         level.playSound(null, owner.blockPosition(), SoundEvents.EVOKER_CAST_SPELL, SoundSource.PLAYERS, 1.0F, 0.8F);
         // The caster turns TRULY invisible for the whole illusion window while the
         // copies walk at their side - a hit, a second use of the book, leaving or
         // the timer running out ends it (see endIllusion).
         activeIllusions.put(owner.getUUID(), state);
         applyTrueInvisibility(owner, state);
         illusionBookCd.put(owner.getUUID(), now + 600L);
         Chat.raw(owner, "§d§lIllusion burst! §7Blinded §f" + blindedPlayers + " player" + (blindedPlayers == 1 ? "" : "s")
            + "§7, stunned §f" + stunnedMobs + " mob" + (stunnedMobs == 1 ? "" : "s")
            + "§7 - §f" + illusionCount + " copies of you\u00a77 walk at your side. \u00a78(Use the book again to drop the illusion.)");
         return null;
      } catch (Exception e) {
         return "The spellbook fizzles.";
      }
   }

   /** Why an illusion window closed - it decides the message, the effect and the cooldown. */
   private enum EndReason {
      /** Damage landed on the caster. */
      HIT,
      /** The caster used the book again. */
      CANCEL,
      /** The 20 second window ran out. */
      EXPIRED,
      /** Death, a dimension change or a respawn - the body the spell was cast on is gone. */
      LEFT,
      /** Logout or server stop: nobody is there to see an effect or read a message. */
      SILENT
   }

   /** Everything one live illusion window needs. One per caster, keyed by uuid. */
   private static final class IllusionState {
      /** Clock tick at which the window closes on its own. */
      final long until;
      /** Entity id of the body the spell was cast on: a respawn makes a new body with a new id. */
      final int casterEntityId;
      /** The level it was cast in: walking through a portal ends the spell. */
      final ServerLevel level;
      /** The ring's facing. It eases toward the caster's yaw instead of snapping to it, so a
       *  quick turn swings the copies round in an arc rather than through the caster. */
      float formationYaw;
      /** An invisibility effect the caster already had (a real potion), put back afterwards. */
      MobEffectInstance priorInvisibility;
      /** The gear last hidden, so a swap is re-hidden on the very next tick. */
      final ItemStack[] hiddenGear = new ItemStack[MIRRORED_SLOTS.length];
      /** The caster's last attack the copies have already mirrored. */
      int mirroredAttackStamp = Integer.MIN_VALUE;

      IllusionState(long until, int casterEntityId, ServerLevel level, float yaw) {
         this.until = until;
         this.casterEntityId = casterEntityId;
         this.level = level;
         this.formationYaw = yaw;
      }
   }

   /** Purple used for every Illusioner Spellbook cue. */
   private static final int ILLUSION_PURPLE = 0x8C6BFF;
   /** How far out the copies stand. Shrunk toward the caster when a wall is in the way. */
   private static final double FORMATION_RADIUS = 2.2;
   /** A copy further than this from its slot gives up walking and is placed there. */
   private static final double COPY_SNAP_DISTANCE = 14.0;
   /** Fastest a copy walks per tick - a little over sprint-jumping, so it keeps up. */
   private static final double COPY_MAX_STEP = 0.75;
   /** Chance a mob that lost the caster picks one of the copies instead of nothing. */
   private static final double REDIRECT_CHANCE = 0.6;

   /** Illusioner copies: copy uuid -> its slot in the ring, and how many slots there are. */
   private static final Map<UUID, Integer> copySlot = new HashMap<>();
   private static final Map<UUID, Integer> copySlotCount = new HashMap<>();
   /** Live illusion windows: caster uuid -> state. */
   private static final Map<UUID, IllusionState> activeIllusions = new HashMap<>();

   /** Using the book while the spell is live drops it on purpose. Returns true when it did. */
   public static boolean cancelIllusion(ServerPlayer owner) {
      if (owner == null || !activeIllusions.containsKey(owner.getUUID())) {
         return false;
      }
      endIllusion(owner, EndReason.CANCEL);
      return true;
   }

   /** Damage landed on the caster while the spell is live (any source, from
    *  ModEvents' AFTER_DAMAGE): the illusions shatter, the caster reappears,
    *  and the spellbook starts its cooldown again. */
   public static void onPlayerHit(ServerPlayer victim) {
      if (victim == null || !activeIllusions.containsKey(victim.getUUID())) {
         return;
      }
      endIllusion(victim, EndReason.HIT);
   }

   /** Logout: the caster must never be saved invisible or leave copies walking around. */
   public static void onPlayerLeave(ServerPlayer player) {
      if (player != null && activeIllusions.containsKey(player.getUUID())) {
         endIllusion(player, EndReason.SILENT);
      }
   }

   /** Server stop: close every window and discard every copy, so nothing outlives the
    *  in-memory state that drives it. */
   public static void onServerStopping(MinecraftServer server) {
      for (UUID id : new ArrayList<>(activeIllusions.keySet())) {
         ServerPlayer owner = server.getPlayerList().getPlayer(id);
         if (owner != null) {
            endIllusion(owner, EndReason.SILENT);
         }
      }
      activeIllusions.clear();
      for (UUID copyId : new ArrayList<>(copyOwner.keySet())) {
         Entity e = findEntity(server, copyId);
         if (e != null && !e.isRemoved()) {
            e.remove(Entity.RemovalReason.DISCARDED);
         }
         removeCopyInfo(server, copyId);
      }
      copyOwner.clear();
      copyBorn.clear();
      copyStrike.clear();
      copySlot.clear();
      copySlotCount.clear();
   }

   /**
    * The one way an illusion window closes, whatever closed it: the copies break
    * apart, the caster's body and gear come back for everyone, and (for a hit or a
    * cancel) the book's 30 second cooldown restarts from now.
    */
   private static void endIllusion(ServerPlayer owner, EndReason reason) {
      UUID id = owner.getUUID();
      IllusionState state = activeIllusions.remove(id);
      boolean effects = reason != EndReason.SILENT;
      MinecraftServer server = owner.level().getServer();
      if (server != null) {
         for (UUID copyId : new ArrayList<>(copyOwner.keySet())) {
            if (id.equals(copyOwner.get(copyId))) {
               discardCopy(server, copyId, effects);
            }
         }
      }
      clearTrueInvisibility(owner, state);
      if (!(owner.level() instanceof ServerLevel level)) {
         return;
      }
      if (effects && owner.isAlive()) {
         Fx.flare(level, ParticleTypes.WITCH, owner.position().add(0.0, 1.0, 0.0), 1.2, ILLUSION_PURPLE);
         level.playSound(null, owner.blockPosition(), SoundEvents.EVOKER_CAST_SPELL, SoundSource.PLAYERS, 1.0F, 1.6F);
      }
      if (reason == EndReason.HIT || reason == EndReason.CANCEL) {
         long now = ServerClock.clock(level);
         illusionBookCd.put(id, now + 600L);
         ItemStack book = owner.getMainHandItem();
         if (!ModItems.isIllusionerSpellbook(book)) {
            book = ItemStack.EMPTY;
            for (int i = 0; i < owner.getInventory().getContainerSize(); i++) {
               ItemStack s = owner.getInventory().getItem(i);
               if (ModItems.isIllusionerSpellbook(s)) {
                  book = s;
                  break;
               }
            }
         }
         if (!book.isEmpty()) {
            owner.getCooldowns().addCooldown(book, 600);
         }
      }
      switch (reason) {
         case HIT -> Chat.raw(owner, "§dYour illusions shatter - §7you're exposed! The spellbook begins recharging.");
         case CANCEL -> Chat.raw(owner, "\u00a7dYou let the illusion go. \u00a77The spellbook begins recharging.");
         case EXPIRED -> Chat.raw(owner, "\u00a7dThe illusion fades - \u00a77you're visible again.");
         default -> {
         }
      }
   }

   /** Removes one copy, with a purple shatter where it stood unless the end is silent. */
   private static void discardCopy(MinecraftServer server, UUID copyId, boolean effects) {
      Entity e = findEntity(server, copyId);
      if (e != null && !e.isRemoved()) {
         if (effects && e.level() instanceof ServerLevel lv) {
            Fx.shatter(lv, ParticleTypes.WITCH, e.position().add(0.0, 1.0, 0.0), 0.9, ILLUSION_PURPLE);
         }
         e.remove(Entity.RemovalReason.DISCARDED);
      }
      removeCopyInfo(server, copyId);
      copyOwner.remove(copyId);
      copyBorn.remove(copyId);
      copyStrike.remove(copyId);
      copySlot.remove(copyId);
      copySlotCount.remove(copyId);
   }

   // ------------------------------------------------------- true invisibility

   private static final EquipmentSlot[] MIRRORED_SLOTS = {
      EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND
   };

   /** True while the caster is inside an illusion window. */
   public static boolean isTrulyHidden(ServerPlayer player) {
      return player != null && activeIllusions.containsKey(player.getUUID());
   }

   /**
    * The Illusioner's true invisibility - what the copies are a decoy for:
    *
    * <ul>
    *   <li><b>No tell-tale swirl.</b> Invisibility goes on as an ambient, particle-free,
    *       icon-free effect, so nothing drifts off the caster's body and their own HUD
    *       stays clean. The effect (not the bare entity flag) is used because vanilla
    *       re-derives the flag from the effect list whenever any effect changes - the
    *       old flag-only version reappeared the moment a potion wore off mid-window.
    *       It is sized to the window, so even a missed cleanup can never outlast it.</li>
    *   <li><b>The gear is hidden, not removed.</b> Other players are sent empty
    *       equipment for all six slots, so no armour or held item floats in the air
    *       to give the caster away - while the server keeps the real pieces equipped,
    *       so full netherite still protects. The tick re-sends it whenever the gear
    *       changes and every few ticks for anyone who just came into range.</li>
    *   <li><b>Mobs lose you.</b> Whatever was hunting the caster turns on a copy (or on
    *       nothing), and {@code VanishMobTargetMixin} refuses to hand the caster out
    *       as a new target.</li>
    * </ul>
    */
   private static void applyTrueInvisibility(ServerPlayer owner, IllusionState state) {
      MobEffectInstance prior = owner.getEffect(MobEffects.INVISIBILITY);
      if (prior != null) {
         state.priorInvisibility = new MobEffectInstance(prior);
         owner.removeEffect(MobEffects.INVISIBILITY);
      }
      int ticks = (int)Math.max(20L, state.until - ServerClock.clock(owner.level())) + 20;
      // ambient = true, visible (particles) = false, showIcon = false
      owner.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, ticks, 0, true, false, false));
      owner.setInvisible(true);
      sendEquipment(owner, true);
      redirectHunters(owner, 64.0, true);
   }

   /** Undoes {@link #applyTrueInvisibility}. Safe to call on a dead or leaving player. */
   private static void clearTrueInvisibility(ServerPlayer owner, IllusionState state) {
      MobEffectInstance current = owner.getEffect(MobEffects.INVISIBILITY);
      // Only the spell's own instance is taken away - never a potion the caster drank
      // during the window - and one drunk before the cast is handed back.
      if (current != null && current.isAmbient() && !current.isVisible() && !current.showIcon()) {
         owner.removeEffect(MobEffects.INVISIBILITY);
      }
      if (state != null && state.priorInvisibility != null && owner.isAlive() && !owner.hasEffect(MobEffects.INVISIBILITY)) {
         owner.addEffect(state.priorInvisibility);
      }
      owner.setInvisible(owner.hasEffect(MobEffects.INVISIBILITY));
      sendEquipment(owner, false);
   }

   /** True when the caster's gear differs from what was last hidden (a swap mid-window). */
   private static boolean gearChanged(ServerPlayer owner, IllusionState state) {
      boolean changed = false;
      for (int i = 0; i < MIRRORED_SLOTS.length; i++) {
         ItemStack now = owner.getItemBySlot(MIRRORED_SLOTS[i]);
         if (state.hiddenGear[i] == null || !ItemStack.matches(state.hiddenGear[i], now)) {
            state.hiddenGear[i] = now.copy();
            changed = true;
         }
      }
      return changed;
   }

   /**
    * Sends the caster's six equipment slots to every other player in range: empty
    * stacks while hidden, the real ones when the spell ends. The server's own
    * equipment never changes - this only decides what other clients draw.
    */
   private static void sendEquipment(ServerPlayer owner, boolean hide) {
      try {
         MinecraftServer server = owner.level().getServer();
         if (server == null) {
            return;
         }
         List<com.mojang.datafixers.util.Pair<EquipmentSlot, ItemStack>> slots = new ArrayList<>();
         for (EquipmentSlot slot : MIRRORED_SLOTS) {
            slots.add(com.mojang.datafixers.util.Pair.of(slot, hide ? ItemStack.EMPTY : owner.getItemBySlot(slot).copy()));
         }
         ClientboundSetEquipmentPacket packet = new ClientboundSetEquipmentPacket(owner.getId(), slots);
         for (ServerPlayer other : server.getPlayerList().getPlayers()) {
            // Hiding goes to anyone who could be tracking the caster; restoring goes to the
            // whole level so nobody is left with an empty-handed ghost.
            if (other != owner && other.connection != null && other.level() == owner.level()
                  && (!hide || other.distanceToSqr(owner) < 192.0 * 192.0)) {
               other.connection.send(packet);
            }
         }
      } catch (Exception ignored) {
      }
   }

   /**
    * The confusion half of the spell. Mobs within {@code radius} that were after the
    * caster - targeting them, or just hit by them from nowhere - turn on one of the
    * copies instead, some of the time; the rest simply lose the thread. {@code all}
    * is the cast itself, where every hunter is re-rolled.
    */
   private static void redirectHunters(ServerPlayer owner, double radius, boolean all) {
      if (!(owner.level() instanceof ServerLevel level)) {
         return;
      }
      List<Player> copies = new ArrayList<>();
      for (Map.Entry<UUID, UUID> entry : copyOwner.entrySet()) {
         if (owner.getUUID().equals(entry.getValue()) && level.getEntity(entry.getKey()) instanceof Player copy && copy.isAlive()) {
            copies.add(copy);
         }
      }
      for (Mob mob : level.getEntitiesOfClass(Mob.class, owner.getBoundingBox().inflate(radius))) {
         if (copyOwner.containsKey(mob.getUUID()) || BossManager.isFriendlySkeleton(mob)) {
            continue;
         }
         boolean hunting = mob.getTarget() == owner;
         boolean provoked = mob.getTarget() == null && mob.getLastHurtByMob() == owner;
         if (!hunting && !(provoked && (all || mob.tickCount % 2 == 0))) {
            continue;
         }
         if (hunting) {
            // Cleared first: the mixin refuses owner targets, but a target set before the
            // cast is still sitting in the field.
            mob.setTarget(null);
         }
         if (!copies.isEmpty() && RANDOM.nextDouble() < REDIRECT_CHANCE) {
            mob.setTarget(copies.get(RANDOM.nextInt(copies.size())));
         }
      }
   }

   /**
    * Where copy {@code index} of {@code count} should stand: a ring round the caster
    * turned to the eased formation yaw, at the caster's feet height. A slot inside a
    * wall is pulled in toward the caster until it is clear, so copies hug a corridor
    * instead of grinding into its sides; failing that they share the caster's spot.
    */
   private static Vec3 formationSlot(ServerPlayer owner, ServerLevel level, Entity copy, float yawDeg, int index, int count) {
      double a = Math.toRadians(yawDeg) + Math.PI / 2.0 + index / (double)Math.max(1, count) * Math.PI * 2.0;
      for (double r = FORMATION_RADIUS; r > 0.5; r -= 0.7) {
         Vec3 at = new Vec3(owner.getX() + Math.cos(a) * r, owner.getY(), owner.getZ() + Math.sin(a) * r);
         Entity body = copy != null ? copy : owner;
         if (level.noCollision(body, body.getBoundingBox().move(at.x - body.getX(), at.y - body.getY(), at.z - body.getZ()))) {
            return at;
         }
      }
      return owner.position();
   }

   /**
    * One tick of a copy walking with its caster: steered (not teleported) toward its
    * ring slot with a speed that eases off as it arrives, so it glides to a stop
    * instead of jittering on the spot. Gravity and collision are its own body's -
    * {@code PossessedPlayer} runs a real player tick - and it hops when it walks into
    * a step. Only a copy left far behind (a fall, a pearl, an elytra) is placed back
    * in its slot. It always looks where the caster looks.
    */
   private static void followCaster(Player copy, ServerPlayer owner, IllusionState state, int index, int count) {
      if (!(copy.level() instanceof ServerLevel level)) {
         return;
      }
      Vec3 slot = formationSlot(owner, level, copy, state.formationYaw, index, count);
      double dx = slot.x - copy.getX();
      double dy = slot.y - copy.getY();
      double dz = slot.z - copy.getZ();
      double flat = Math.sqrt(dx * dx + dz * dz);
      if (flat > COPY_SNAP_DISTANCE || Math.abs(dy) > COPY_SNAP_DISTANCE) {
         copy.setPos(slot.x, slot.y, slot.z);
         copy.setDeltaMovement(Vec3.ZERO);
         copy.resetFallDistance();
      } else {
         double vy = copy.getDeltaMovement().y;
         if (flat > 0.15) {
            double speed = Math.min(COPY_MAX_STEP, flat * 0.35);
            if (copy.onGround() && (copy.horizontalCollision || dy > COPY_STEP_UP * 0.5) && dy > -0.5) {
               vy = 0.42; // a jump, the same height a player's jump has
            }
            copy.setDeltaMovement(dx / flat * speed, vy, dz / flat * speed);
         } else {
            copy.setDeltaMovement(0.0, vy, 0.0);
         }
         // A copy that drops off a ledge after its caster must not die of the landing.
         copy.resetFallDistance();
      }
      copy.setYRot(owner.getYRot());
      copy.setXRot(owner.getXRot());
      copy.setYHeadRot(owner.getYHeadRot());
      copy.yBodyRot = owner.yBodyRot;
   }

   /**
    * Illusions UPDATE: every copy re-wears the caster's <i>current</i> kit, so
    * swapping a weapon or a chestplate mid-window changes every clone with you
    * instead of leaving them standing in the armour you had when you cast.
    */
   private static void mirrorCopyGear(Player copy, ServerPlayer owner) {
      boolean changed = false;
      for (EquipmentSlot slot : MIRRORED_SLOTS) {
         ItemStack want = owner.getItemBySlot(slot);
         if (!ItemStack.matches(copy.getItemBySlot(slot), want)) {
            copy.setItemSlot(slot, want.copy());
            changed = true;
         }
      }
      if (!changed) {
         return;
      }
      try {
         MinecraftServer server = owner.level().getServer();
         if (server == null) {
            return;
         }
         List<com.mojang.datafixers.util.Pair<EquipmentSlot, ItemStack>> slots = new ArrayList<>();
         for (EquipmentSlot slot : MIRRORED_SLOTS) {
            slots.add(com.mojang.datafixers.util.Pair.of(slot, copy.getItemBySlot(slot).copy()));
         }
         ClientboundSetEquipmentPacket packet = new ClientboundSetEquipmentPacket(copy.getId(), slots);
         for (ServerPlayer other : server.getPlayerList().getPlayers()) {
            if (other.connection != null && other.level() == copy.level()) {
               other.connection.send(packet);
            }
         }
      } catch (Exception ignored) {
      }
   }

   /** Spawns a 1-HP illusion copy of the owner: exact skin, armor and tools
    *  copied, glowing magenta. The tick walks it in formation with the caster
    *  (see followCaster) and lets it swing at whatever the caster hits. */
   private static Player spawnIllusionCopy(ServerPlayer owner, ServerLevel level, Vec3 at) {
      try {
         UUID fakeId = UUID.randomUUID();
         GameProfile profile;
         try {
            profile = new GameProfile(fakeId, owner.getName().getString(), new PropertyMap(owner.getGameProfile().properties()));
         } catch (Exception ignored) {
            profile = new GameProfile(fakeId, owner.getName().getString());
         }
         BossManager.PossessedPlayer copy = new BossManager.PossessedPlayer(level.getServer(), level, profile);
         copy.setInvulnerable(false);
         AttributeInstance hp = copy.getAttribute(Attributes.MAX_HEALTH);
         if (hp != null) {
            hp.setBaseValue(1.0);
         }
         copy.setHealth(1.0F);
         copy.setCustomName(Component.literal("§d" + owner.getName().getString() + " (Illusion)"));
         copy.setCustomNameVisible(true);
         copy.setGlowingTag(true);
         copy.setItemSlot(EquipmentSlot.HEAD, owner.getItemBySlot(EquipmentSlot.HEAD).copy());
         copy.setItemSlot(EquipmentSlot.CHEST, owner.getItemBySlot(EquipmentSlot.CHEST).copy());
         copy.setItemSlot(EquipmentSlot.LEGS, owner.getItemBySlot(EquipmentSlot.LEGS).copy());
         copy.setItemSlot(EquipmentSlot.FEET, owner.getItemBySlot(EquipmentSlot.FEET).copy());
         copy.setItemSlot(EquipmentSlot.MAINHAND, owner.getMainHandItem().copy());
         copy.setItemSlot(EquipmentSlot.OFFHAND, owner.getOffhandItem().copy());
         copy.setPos(at.x, at.y, at.z);
         // The player-info entry has to reach every client BEFORE the entity does. A vanilla
         // client drops a player entity it has no info for ("added prior to sending player
         // info"), and addFreshEntity sends the spawn straight away - so the copies were sent in
         // the wrong order and could simply never appear. They were also added at 0,0,0 and
         // moved afterwards, so the spawn they did get was in the wrong place.
         ClientboundPlayerInfoUpdatePacket info =
            new ClientboundPlayerInfoUpdatePacket(EnumSet.of(Action.ADD_PLAYER), List.of((ServerPlayer)copy));
         for (ServerPlayer viewer : level.getServer().getPlayerList().getPlayers()) {
            try {
               viewer.connection.send(info);
            } catch (Exception ignored) {
            }
         }
         level.addFreshEntity(copy);
         return copy;
      } catch (Exception e) {
         return null;
      }
   }

   /** How close a copy must be to something to hit it - a player's own reach. */
   private static final double COPY_REACH = 3.0;

   /**
    * What a copy swings at, now that it walks with the caster instead of roaming:
    * the thing the caster just hit, if it is in this copy's reach (the copies mirror
    * the caster's attacks), else a mob in reach that has turned on this copy. The
    * caster and every other summon of theirs are always spared.
    */
   private static LivingEntity copyStrikeTarget(Player copy, ServerPlayer owner, boolean ownerJustAttacked) {
      if (!(copy.level() instanceof ServerLevel level)) {
         return null;
      }
      double reachSq = COPY_REACH * COPY_REACH;
      if (owner != null && ownerJustAttacked) {
         LivingEntity mark = owner.getLastHurtMob();
         if (mark != null && mark.isAlive() && mark != owner && !copyOwner.containsKey(mark.getUUID())
               && !BossManager.isFriendlySkeleton(mark) && copy.distanceToSqr(mark) < reachSq) {
            return mark;
         }
      }
      for (Mob mob : level.getEntitiesOfClass(Mob.class, copy.getBoundingBox().inflate(COPY_REACH))) {
         if (mob.isAlive() && mob.getTarget() == copy && copy.distanceToSqr(mob) < reachSq) {
            return mob;
         }
      }
      return null;
   }

   private static void removeCopyInfo(MinecraftServer server, UUID copyId) {
      try {
         ClientboundPlayerInfoRemovePacket pkt = new ClientboundPlayerInfoRemovePacket(List.of(copyId));
         for (ServerPlayer viewer : server.getPlayerList().getPlayers()) {
            viewer.connection.send(pkt);
         }
      } catch (Exception ignored) {
      }
   }

   // === RAIDERS ITEM UPGRADER ===

   // The Raiders Item Upgrader is now a FORGE MATERIAL - upgrade raid legendaries
   // by combining them with it in the Item Forge (see ForgeOps / ItemForgeMenu).

   // === ALLY TICK ===

   /** Runs every tick: keeps horn raiders and spellbook illusions fighting. */
   public static void tick(MinecraftServer server) {
      try {
         long now = ServerClock.clock(server);
         // Illusioner Spellbook active windows. Each closes on whichever comes first:
         // the timer, the caster leaving (logout is handled by onPlayerLeave), dying,
         // respawning into a new body, or walking into another dimension. A hit and a
         // second use of the book close it from onPlayerHit / cancelIllusion.
         for (UUID id : new ArrayList<>(activeIllusions.keySet())) {
            IllusionState state = activeIllusions.get(id);
            ServerPlayer owner = server.getPlayerList().getPlayer(id);
            if (owner == null) {
               activeIllusions.remove(id);
               for (UUID copyId : new ArrayList<>(copyOwner.keySet())) {
                  if (id.equals(copyOwner.get(copyId))) {
                     discardCopy(server, copyId, true);
                  }
               }
               continue;
            }
            if (!owner.isAlive() || owner.getId() != state.casterEntityId || owner.level() != state.level) {
               endIllusion(owner, EndReason.LEFT);
               continue;
            }
            if (now > state.until) {
               endIllusion(owner, EndReason.EXPIRED);
               continue;
            }
            // The ring turns with the caster, eased so a flick of the mouse does not
            // whip the copies through them.
            state.formationYaw += net.minecraft.util.Mth.wrapDegrees(owner.getYRot() - state.formationYaw) * 0.15F;
            // Gear is re-hidden the tick it changes, and every 5 ticks regardless, which
            // covers a player who has just walked into tracking range (their client was
            // sent the real gear when the caster was paired to it).
            if (gearChanged(owner, state) || now % 5L == 0L) {
               sendEquipment(owner, true);
            }
            if (now % 10L == 0L) {
               redirectHunters(owner, 24.0, false);
            }
         }
         // Horn raiders
         for (UUID id : new java.util.ArrayList<>(allyOwner.keySet())) {
            Entity e = findEntity(server, id);
            if (e == null || !e.isAlive() || now - allyBorn.getOrDefault(id, now) > HORN_RAIDER_TICKS) {
               if (e != null && e.isAlive()) {
                  // Fade out at end of lifetime
                  ServerLevel lv = (ServerLevel)e.level();
                  com.fortuneandfavors.net.FfVfx.particles(lv, ParticleTypes.POOF, e.getX(), e.getY() + 1.0, e.getZ(), 10, 0.4, 0.6, 0.4, 0.04);
               }
               allyOwner.remove(id);
               allyBorn.remove(id);
               continue;
            }
            if (e instanceof Mob mob) {
               LivingEntity target = mob.getTarget();
               if (target == null || !target.isAlive() || target == friendlyOwnerPlayer(mob)) {
                  LivingEntity next = nearestHostile(mob);
                  if (next != null) {
                     mob.setTarget(next);
                  }
               }
            }
         }
         // Friendly evokers (from the horn) summon vexes that would attack the
         // owner and their allies - take control of those vexes so they fight FOR
         // the owner instead. MobMixin strips their AI once tagged, and the vex
         // loop below moves them and strikes with them. Any untagged vex near a
         // friendly evoker is claimed instantly (before it can reach the player),
         // and its target is cleared so it never keeps swinging at the owner.
         for (UUID id : new java.util.ArrayList<>(allyOwner.keySet())) {
            Entity e = findEntity(server, id);
            if (!(e instanceof Mob ally) || !ally.isAlive() || ally.getType() != EntityTypes.EVOKER) {
               continue;
            }
            UUID ownerId = allyOwner.get(id);
            if (ownerId == null) {
               continue;
            }
            for (Vex vex : ally.level().getEntitiesOfClass(Vex.class, ally.getBoundingBox().inflate(32.0))) {
               if (BossManager.isFriendlySkeleton(vex) || illusionOwner.containsKey(vex.getUUID())) {
                  continue;
               }
               ServerPlayer owner = server.getPlayerList().getPlayer(ownerId);
               tagFriendly(vex, ownerId);
               vex.setTarget(null);
               vex.setCustomName(Component.literal("§5" + (owner != null ? owner.getName().getString() : "Friendly") + "'s Vex"));
               vex.setCustomNameVisible(false);
               vex.setPersistenceRequired();
               illusionOwner.put(vex.getUUID(), ownerId);
               illusionBorn.put(vex.getUUID(), ServerClock.clock(ally.level()));
               illusionStrike.put(vex.getUUID(), ServerClock.clock(ally.level()) + 30L);
               vexKind.put(vex.getUUID(), "spellbook");
            }
            // The friendly evoker also fights: it blasts fangs at whatever it's
            // targeting every few seconds.
            LivingEntity fangTarget = ally.getTarget();
            if (fangTarget != null && fangTarget.isAlive() && now >= allyFangCooldown.getOrDefault(id, 0L)) {
               allyFangCooldown.put(id, now + 100L); // every 5 seconds
               castEvokerFangsAt(ally, fangTarget);
            }
         }
         // Vexes (spellbook + illusion)
         for (UUID id : new java.util.ArrayList<>(illusionOwner.keySet())) {
            Entity e = findEntity(server, id);
            long vexLifetime = "spellbook".equals(vexKind.get(id)) ? SPELLBOOK_VEX_TICKS : ILLUSION_VEX_TICKS;
            if (e == null || !e.isAlive() || now - illusionBorn.getOrDefault(id, now) > vexLifetime) {
               if (e != null && e.isAlive()) {
                  ServerLevel lv = (ServerLevel)e.level();
                  com.fortuneandfavors.net.FfVfx.particles(lv, ParticleTypes.POOF, e.getX(), e.getY() + 1.0, e.getZ(), 10, 0.4, 0.6, 0.4, 0.04);
               }
               illusionOwner.remove(id);
               illusionBorn.remove(id);
               illusionStrike.remove(id);
               vexKind.remove(id);
               continue;
            }
            if (e instanceof Vex vex) {
               UUID ownerId = illusionOwner.get(id);
               ServerPlayer owner = ownerId != null ? server.getPlayerList().getPlayer(ownerId) : null;
               LivingEntity target = vex.getTarget();
               if (owner != null) {
                  // Mimic the owner: if they're fighting something, hit it;
                  // otherwise hunt any nearby hostile creature. (This block used
                  // to appear twice in a row - the first copy was subsumed by
                  // the second, so every vex read getLastHurtMob() an extra time
                  // every tick for a result it then discarded.)
                  LivingEntity ownerTarget = owner.getLastHurtMob();
                  if (ownerTarget != null && ownerTarget.isAlive() && ownerTarget != owner
                        && !BossManager.isFriendlySkeleton(ownerTarget)) {
                     target = ownerTarget;
                     vex.setTarget(target);
                  } else {
                     LivingEntity next = nearestHostile(vex);
                     if (next != null) {
                        target = next;
                        vex.setTarget(next);
                     }
                  }
               }
               if (target == null || !target.isAlive()) {
                  target = nearestHostile(vex);
               }
               if (target == null) {
                  // Nothing to fight - drift back toward the owner.
                  target = owner != null ? owner : null;
               }
               double hx = target != null ? target.getX() : vex.getX();
               double hy = (target != null ? target.getY() : vex.getY()) + 1.5 + Math.sin(now * 0.1 + id.hashCode()) * 0.35;
               double hz = target != null ? target.getZ() : vex.getZ();
               double ang = now * 0.055 + (id.hashCode() & 32767);
               double ox = hx + Math.cos(ang) * 1.8;
               double oz = hz + Math.sin(ang) * 1.8;
               double dx = ox - vex.getX();
               double dy = hy - vex.getY();
               double dz = oz - vex.getZ();
               double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
               if (dist > 0.35) {
                  vex.setDeltaMovement(dx / dist * 0.55, dy / dist * 0.55, dz / dist * 0.55);
                  vex.hurtMarked = true;
               } else {
                  vex.setDeltaMovement(0.0, 0.0, 0.0);
               }
               long strike = illusionStrike.getOrDefault(id, 0L);
               // Never strike the owner (the drift-home target above) or another of their summons.
               // Checked before the blink, which used to teleport the vex into its own owner first
               // and only then decide not to hit them.
               boolean friendly = target != null && (BossManager.isFriendlySkeleton(target)
                  || copyOwner.containsKey(target.getUUID())
                  || illusionOwner.containsKey(target.getUUID())
                  || (owner != null && target.getUUID().equals(owner.getUUID())));
               if (target != null && !friendly && now >= strike && vex.distanceToSqr(target) < 169.0) {
                  illusionStrike.put(id, now + 50L);
                  vex.setPos(target.getX(), target.getY() + 0.7, target.getZ());
                  vex.setDeltaMovement(0.0, 0.0, 0.0);
                  vex.hurtMarked = true;
                  ServerLevel lv = (ServerLevel)vex.level();
                  com.fortuneandfavors.net.FfVfx.particles(lv, ParticleTypes.SWEEP_ATTACK, target.getX(), target.getY() + 0.6, target.getZ(), 8, 0.3, 0.3, 0.3, 0.05);
                  lv.playSound(null, target.blockPosition(), SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 0.8F, 1.2F);
                  boolean cloakBoost = "spellbook".equals(vexKind.get(id)) && owner != null && ModItems.isEvokerCloak(owner.getItemBySlot(EquipmentSlot.CHEST));
                  float strikeDmg = "spellbook".equals(vexKind.get(id)) ? (cloakBoost ? 12.0F : 8.0F) : 2.5F;
                  target.hurtServer(lv, vex.damageSources().mobAttack(vex), strikeDmg);
               } else if (friendly) {
                  vex.setTarget(null);
               }
            }
         }

         // Illusioner Spellbook player copies: they walk in formation with their
         // caster, look where the caster looks, wear what the caster wears, and swing
         // when the caster swings - the decoys the mobs get pointed at. A copy whose
         // caster's window is gone, or that has died, breaks apart.
         for (UUID id : new ArrayList<>(copyOwner.keySet())) {
            Entity e = findEntity(server, id);
            UUID ownerId = copyOwner.get(id);
            IllusionState state = ownerId != null ? activeIllusions.get(ownerId) : null;
            ServerPlayer owner = ownerId != null ? server.getPlayerList().getPlayer(ownerId) : null;
            if (e == null || !e.isAlive() || state == null || owner == null
                  || now - copyBorn.getOrDefault(id, now) > ILLUSION_COPY_TICKS || e.level() != owner.level()) {
               discardCopy(server, id, true);
               continue;
            }
            if (!(e instanceof Player copy)) {
               continue;
            }
            if (now % 10L == 0L) {
               mirrorCopyGear(copy, owner);
            }
            followCaster(copy, owner, state, copySlot.getOrDefault(id, 0), copySlotCount.getOrDefault(id, 1));
            // The caster's last landed hit, read off its own tick stamp: fresh means
            // "within the last half second", and each hit is mirrored once.
            int stamp = owner.getLastHurtMobTimestamp();
            boolean ownerJustAttacked = owner.tickCount - stamp < 10 && stamp != state.mirroredAttackStamp;
            LivingEntity target = copyStrikeTarget(copy, owner, ownerJustAttacked);
            long strike = copyStrike.getOrDefault(id, 0L);
            if (target != null && (ownerJustAttacked || now >= strike)) {
               copyStrike.put(id, now + 20L);
               ServerLevel lv = (ServerLevel)copy.level();
               copy.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
               // The copies hit for real, at a fraction of the caster's strength.
               // A copy that cannot hurt anything is not a threat to be sorted from
               // the real one, it is a decoration. Half the weapon's damage is enough
               // to matter and not enough to beat the player who cast them.
               boolean cloaked = ModItems.isIllusionerCloak(owner.getItemBySlot(EquipmentSlot.CHEST));
               float dmg = (cloaked ? 4.0F : 3.0F) + owner.getMainHandItem().getItem().getAttackDamageBonus(target, 3.0F, lv.damageSources().playerAttack(owner));
               target.hurtServer(lv, lv.damageSources().mobAttack(copy), dmg);
               Fx.slash(lv, ParticleTypes.SWEEP_ATTACK, copy.position().add(0.0, 1.1, 0.0), target.position().subtract(copy.position()), COPY_REACH, ILLUSION_PURPLE);
               lv.playSound(null, target.blockPosition(), SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 0.9F, 1.1F);
            }
         }
         // Marked after the loop so every copy gets to mirror the same hit.
         for (UUID ownerId : activeIllusions.keySet()) {
            ServerPlayer owner = server.getPlayerList().getPlayer(ownerId);
            if (owner != null && owner.tickCount - owner.getLastHurtMobTimestamp() < 10) {
               activeIllusions.get(ownerId).mirroredAttackStamp = owner.getLastHurtMobTimestamp();
            }
         }
         // Live cooldown readouts on the horn / spellbook tooltips, refreshed
         // once a second from the authoritative server-side cooldown maps.
         if (server.getTickCount() % 20L == 0L) {
            updateCooldownTooltips(server);
         }
      } catch (Exception e) {
         // This was `catch (Exception ignored)`, which is not the same thing as
         // defensive: a throw anywhere in the driver silently skipped every horn
         // raider, vex and copy after it, for that tick and every tick after,
         // with nothing written anywhere - Safe.run would have logged it, but
         // this catch consumed the error first. The summons then just stalled
         // forever with their state maps still populated. Log it, throttled.
         long nowMs = System.currentTimeMillis();
         if (nowMs - lastTickErrorLog > 60_000L) {
            lastTickErrorLog = nowMs;
            com.fortuneandfavors.FortuneFavorsMod.LOGGER.error(
               "Fortune & Favors: error during raid gear tick - remaining summons skipped this tick", e
            );
         }
      }
   }

   /** Keeps a "ready in Xs" line on held horn / spellbook tooltips while the
    *  item is on cooldown, and strips it the moment the item can be used again.
    *  Only rewrites the lore when the displayed value actually changed, so we
    *  don't rebroadcast the whole slot every second for no reason. */
   private static void updateCooldownTooltips(MinecraftServer server) {
      try {
         for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            long now = ServerClock.clock(p.level());
            updateCooldownTooltip(p, p.getMainHandItem(), now);
            updateCooldownTooltip(p, p.getOffhandItem(), now);
            for (int i = 0; i < 9; i++) {
               updateCooldownTooltip(p, p.getInventory().getItem(i), now);
            }
         }
      } catch (Exception ignored) {
      }
   }

   private static void updateCooldownTooltip(ServerPlayer p, ItemStack stack, long now) {
      if (stack == null || stack.isEmpty()) {
         return;
      }
      long until = 0L;
      if (ModItems.isCaptainHorn(stack)) {
         until = captainHornCd.getOrDefault(p.getUUID(), 0L);
      } else if (ModItems.isEvokerSpellbook(stack)) {
         until = evokerBookCd.getOrDefault(p.getUUID(), 0L);
      } else if (ModItems.isIllusionerSpellbook(stack)) {
         until = illusionBookCd.getOrDefault(p.getUUID(), 0L);
      } else {
         return;
      }
      String line = null;
      long remain = until - now;
      if (remain > 0L) {
         int secs = (int)Math.ceil(remain / 20.0);
         // Red once it's close to ready, muted gray while it's a long wait.
         line = (secs <= 10 ? "§c" : "§8") + "⏳ " + secs + "s until ready";
      }
      ItemLore lore = (ItemLore)stack.get(DataComponents.LORE);
      List<Component> lines = lore != null ? new ArrayList<>(lore.lines()) : new ArrayList<>();
      boolean dirty = false;
      for (int i = 0; i < lines.size(); i++) {
         if (!lines.get(i).getString().contains("until ready")) {
            continue;
         }
         if (line != null && line.equals(lines.get(i).getString())) {
            return; // unchanged - skip the container sync
         }
         lines.remove(i);
         dirty = true;
         break;
      }
      if (line != null && !dirty) {
         lines.add(Component.literal(line));
         dirty = true;
      } else if (line == null && !dirty) {
         return;
      }
      if (dirty) {
         stack.set(DataComponents.LORE, new ItemLore(lines));
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

   private static ServerPlayer friendlyOwnerPlayer(Mob mob) {
      UUID ownerId = BossManager.friendlyOwner(mob);
      if (ownerId == null || mob.level().getServer() == null) {
         return null;
      }
      return mob.level().getServer().getPlayerList().getPlayer(ownerId);
   }

   /** Nearest hostile mob (anything that fights the player) within 24 blocks. */
   private static LivingEntity nearestHostile(Mob mob) {
      ServerLevel level = mob.level() instanceof ServerLevel sl ? sl : null;
      if (level == null) {
         return null;
      }
      LivingEntity best = null;
      double bestDist = 24.0 * 24.0;
      for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class,
            new net.minecraft.world.phys.AABB(mob.getX() - 24, mob.getY() - 12, mob.getZ() - 24,
               mob.getX() + 24, mob.getY() + 12, mob.getZ() + 24))) {
         if (e == mob || !e.isAlive() || BossManager.isFriendlySkeleton(e)) {
            continue;
         }
         if (e instanceof ServerPlayer || e.getType() == EntityTypes.VILLAGER || e.getType() == EntityTypes.IRON_GOLEM) {
            continue;
         }
         double d = mob.distanceToSqr(e);
         if (d < bestDist) {
            bestDist = d;
            best = e;
         }
      }
      return best;
   }

   private static int livingAllyCount(ServerPlayer owner) {
      int n = 0;
      for (UUID id : new java.util.ArrayList<>(allyOwner.keySet())) {
         Entity e = findEntity(owner.level().getServer(), id);
         if (e != null && e.isAlive() && owner.getUUID().equals(allyOwner.get(id))) {
            n++;
         } else {
            allyOwner.remove(id);
            allyBorn.remove(id);
         }
      }
      return n;
   }

   private static int livingIllusionCount(ServerPlayer owner) {
      int n = 0;
      for (UUID id : new java.util.ArrayList<>(illusionOwner.keySet())) {
         Entity e = findEntity(owner.level().getServer(), id);
         if (e != null && e.isAlive() && owner.getUUID().equals(illusionOwner.get(id))) {
            n++;
         } else {
            illusionOwner.remove(id);
            illusionBorn.remove(id);
            illusionStrike.remove(id);
            vexKind.remove(id);
         }
      }
      return n;
   }

   private static void tagFriendly(Mob mob, UUID ownerId) {
      CustomData data = (CustomData)mob.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
      CompoundTag tag = data.copyTag();
      tag.putString("ff_friendly_owner", ownerId.toString());
      mob.setComponent(DataComponents.CUSTOM_DATA, CustomData.of(tag));
   }

   /** A direction on the ground plane, falling back to the body's yaw when the vector is vertical. */
   private static Vec3 horizontal(Vec3 v, float yawDegrees) {
      Vec3 flat = new Vec3(v.x, 0.0, v.z);
      if (flat.lengthSqr() < 1.0E-6) {
         double yaw = Math.toRadians(yawDegrees);
         return new Vec3(-Math.sin(yaw), 0.0, Math.cos(yaw));
      }
      return flat.normalize();
   }

   /**
    * Where a fang stands in this column, searched downward from {@code top} to {@code bottom} -
    * the same search vanilla's evoker uses: the first block whose top face is solid, raised onto
    * whatever partial block (a slab, a carpet) sits on it. {@code NaN} when there is no floor in
    * range, so the caller skips the fang instead of hanging it in the air or burying it in a wall.
    * The old heightmap lookup answered "the top of the world here", which under a roof or in a
    * cave was the roof.
    */
   private static double fangFloor(ServerLevel level, double x, double z, double top, double bottom) {
      BlockPos pos = BlockPos.containing(x, top, z);
      int floorY = net.minecraft.util.Mth.floor(bottom) - 1;
      while (pos.getY() >= floorY) {
         BlockPos below = pos.below();
         if (level.getBlockState(below).isFaceSturdy(level, below, net.minecraft.core.Direction.UP)) {
            double offset = 0.0;
            if (!level.getBlockState(pos).isAir()) {
               net.minecraft.world.phys.shapes.VoxelShape shape = level.getBlockState(pos).getCollisionShape(level, pos);
               if (!shape.isEmpty()) {
                  offset = shape.max(net.minecraft.core.Direction.Axis.Y);
               }
            }
            return pos.getY() + offset;
         }
         pos = below;
      }
      return Double.NaN;
   }

   private static int groundY(ServerLevel level, int x, int z, int fallback) {
      int y = level.getHeight(Types.MOTION_BLOCKING, x, z);
      if (y > fallback) {
         return fallback;
      }
      return Math.max(y, level.getMinY() + 1);
   }
}
