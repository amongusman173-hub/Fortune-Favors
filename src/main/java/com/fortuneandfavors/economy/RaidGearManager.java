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
 * - Illusioner's Spellbook: blinds everyone near you for 5s (8s with the cloak) and
 *   spawns fragile illusion vexes (5 with the cloak) that mimic your attacks.
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
   /** Illusioner Spellbook active window: owner uuid -> until game time. */
   private static final Map<UUID, Long> illusionActiveUntil = new HashMap<>();
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
         long now = level.getGameTime();
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
            allyBorn.put(raider.getUUID(), level.getGameTime());
            com.fortuneandfavors.net.FfVfx.shape(level, com.fortuneandfavors.net.FfVfx.SUMMON_CIRCLE, ParticleTypes.FLAME, raider.position(), net.minecraft.world.phys.Vec3.ZERO, 1.8, 30, 0xFFD24A);
            com.fortuneandfavors.net.FfVfx.shape(level, com.fortuneandfavors.net.FfVfx.PILLAR, ParticleTypes.FLAME, raider.position(), net.minecraft.world.phys.Vec3.ZERO, 4.0, 0.0, 0xFF4A2A);
            com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.CAMPFIRE_COSY_SMOKE, raider.getX(), raider.getY() + 1.0, raider.getZ(), 14, 0.6, 0.8, 0.6, 0.04);
            com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.END_ROD, raider.getX(), raider.getY() + 1.2, raider.getZ(), 8, 0.3, 0.6, 0.3, 0.03);
         }
         level.playSound(null, owner.blockPosition(), SoundEvents.RAID_HORN.value(), SoundSource.PLAYERS, 1.4F, 0.9F);
         com.fortuneandfavors.net.FfVfx.shape(level, com.fortuneandfavors.net.FfVfx.FROST_NOVA, ParticleTypes.FLAME, owner.position(), net.minecraft.world.phys.Vec3.ZERO, 7.0, 0.0, 0xFFD24A);
         com.fortuneandfavors.net.FfVfx.shape(level, com.fortuneandfavors.net.FfVfx.RING, ParticleTypes.FLAME, owner.position().add(0.0, 1.2, 0.0), net.minecraft.world.phys.Vec3.ZERO, 3.0, 0.0, 0xFF4A2A);
         Chat.raw(owner, "§6§lThe horn blares - §f" + toSpawn + " raider" + (toSpawn == 1 ? "" : "s") + " answer your call! §7(60s ally)");
         int tier = ModItems.tierOf(owner.getMainHandItem());
         captainHornCd.put(owner.getUUID(), level.getGameTime() + Math.max(1, 5 - (tier - 1)) * 400L); // I=100s, II=80s, III=60s
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
      long now = owner.level().getGameTime();
      if (now < evokerBookCd.getOrDefault(owner.getUUID(), 0L)) {
         return "The spellbook is still recharging.";
      }
      String err = owner.isShiftKeyDown() ? summonVexAlly(owner) : castPlayerFangs(owner);
      if (err != null) {
         return err;
      }
      evokerBookCd.put(owner.getUUID(), owner.level().getGameTime() + (owner.isShiftKeyDown() ? 500L : 120L));
      return null;
   }

   private static String castPlayerFangs(ServerPlayer owner) {
      try {
         ServerLevel level = (ServerLevel)owner.level();
         boolean cloak = ModItems.isEvokerCloak(owner.getItemBySlot(EquipmentSlot.CHEST));
         Vec3 from = owner.position().add(0.0, 0.4, 0.0);
         Vec3 look = owner.getLookAngle();
         Vec3 to = from.add(look.scale(7.0));
         Vec3 dir = to.subtract(from).normalize();
         float yRot = (float)Math.toDegrees(Math.atan2(dir.z, dir.x)) - 90.0F;
         double len = 7.0;
         int rows = cloak ? 9 : 6;
         for (int step = 0; step < rows; step++) {
            double f = (step + 1) / (double)rows * len;
            double fx = from.x + dir.x * f;
            double fz = from.z + dir.z * f;
            int gy = groundY(level, (int)Math.floor(fx), (int)Math.floor(fz), owner.getBlockY());
            EvokerFangs fang = new EvokerFangs(level, fx, gy, fz, yRot, step * 2, owner);
            com.fortuneandfavors.net.FfVfx.shape(level, com.fortuneandfavors.net.FfVfx.RING, ParticleTypes.ENCHANT, new net.minecraft.world.phys.Vec3(fx, gy + 0.1, fz), net.minecraft.world.phys.Vec3.ZERO, 0.8, 0.0, 0xC8F08A);
            level.addFreshEntity(fang);
         }
         com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.ENCHANT, owner.getX(), owner.getY() + 1.4, owner.getZ(), 18, 0.4, 0.5, 0.4, 0.35);
         com.fortuneandfavors.net.FfVfx.shape(level, com.fortuneandfavors.net.FfVfx.SUMMON_CIRCLE, ParticleTypes.ENCHANT, owner.position(), net.minecraft.world.phys.Vec3.ZERO, 2.2, 24, 0xC8F08A);
         com.fortuneandfavors.net.FfVfx.shape(level, com.fortuneandfavors.net.FfVfx.ICE_BURST, ParticleTypes.ENCHANT, owner.position().add(0.0, 1.6, 0.0), net.minecraft.world.phys.Vec3.ZERO, 0.8, 0.0, 0xC8F08A);
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
         Vec3 from = evoker.position().add(0.0, 0.3, 0.0);
         Vec3 to = target.position().add(0.0, target.getBbHeight() * 0.5, 0.0);
         Vec3 dir = to.subtract(from).normalize();
         float yRot = (float)Math.toDegrees(Math.atan2(dir.z, dir.x)) - 90.0F;
         double reach = Math.min(6.0, evoker.distanceTo(target));
         for (int step = 0; step < 5; step++) {
            double f = (step + 1) / 5.0 * reach;
            double fx = from.x + dir.x * f;
            double fz = from.z + dir.z * f;
            int gy = groundY(level, (int)Math.floor(fx), (int)Math.floor(fz), evoker.getBlockY());
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
            illusionBorn.put(vex.getUUID(), level.getGameTime());
            illusionStrike.put(vex.getUUID(), level.getGameTime() + 30L);
            com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.REVERSE_PORTAL, vex.getX(), vex.getY(), vex.getZ(), 16, 0.4, 0.6, 0.4, 0.25);
            com.fortuneandfavors.net.FfVfx.shape(level, com.fortuneandfavors.net.FfVfx.TEAR, ParticleTypes.REVERSE_PORTAL, vex.position(), new net.minecraft.world.phys.Vec3(1.0, 0.0, 0.0), 0.8, 18, 0x9BB8FF);
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
         illusionBorn.put(vex.getUUID(), level.getGameTime());
         illusionStrike.put(vex.getUUID(), level.getGameTime() + 30L);
         com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.REVERSE_PORTAL, vex.getX(), vex.getY(), vex.getZ(), 16, 0.4, 0.6, 0.4, 0.25);
         com.fortuneandfavors.net.FfVfx.shape(level, com.fortuneandfavors.net.FfVfx.TEAR, ParticleTypes.REVERSE_PORTAL, vex.position(), new net.minecraft.world.phys.Vec3(1.0, 0.0, 0.0), 0.8, 18, 0x9BB8FF);
         return null;
      } catch (Exception e) {
         return "The vex refuses to manifest.";
      }
   }

   // === ILLUSIONER'S SPELLBOOK ===

   /** Blinds everyone near you for 5s (8s with the cloak) and spawns 3 illusion
    *  vexes (5 with the cloak) that mimic your attacks. */
   public static String useIllusionerSpellbook(ServerPlayer owner) {
      try {
         ServerLevel level = (ServerLevel)owner.level();
         long now = level.getGameTime();
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

         for (int i = 0; i < illusionCount; i++) {
            Player copy = spawnIllusionCopy(owner, level);
            if (copy == null) {
               continue;
            }
            double a = i / (double)illusionCount * Math.PI * 2.0;
            copy.setPos(owner.getX() + Math.cos(a) * 2.2, owner.getY() + 1.6, owner.getZ() + Math.sin(a) * 2.2);
            copyOwner.put(copy.getUUID(), owner.getUUID());
            copyBorn.put(copy.getUUID(), level.getGameTime());
            copyStrike.put(copy.getUUID(), level.getGameTime() + 20L + i * 10L);
            for (ServerPlayer viewer : level.getServer().getPlayerList().getPlayers()) {
         try {
            viewer.connection.send(
               new ClientboundPlayerInfoUpdatePacket(EnumSet.of(Action.ADD_PLAYER), List.of((ServerPlayer)copy))
            );
         } catch (Exception ignored) {
         }
      }
            com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.PORTAL, copy.getX(), copy.getY(), copy.getZ(), 14, 0.5, 0.5, 0.5, 0.2);
            com.fortuneandfavors.net.FfVfx.shape(level, com.fortuneandfavors.net.FfVfx.BEAM, ParticleTypes.PORTAL, owner.position().add(0.0, 1.0, 0.0), copy.position().add(0.0, 1.0, 0.0), 0.0, 0.0, 0x8C6BFF);
            com.fortuneandfavors.net.FfVfx.shape(level, com.fortuneandfavors.net.FfVfx.ICE_BURST, ParticleTypes.PORTAL, copy.position().add(0.0, 1.0, 0.0), net.minecraft.world.phys.Vec3.ZERO, 0.8, 0.0, 0x8C6BFF);
         }
         com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.END_ROD, owner.getX(), owner.getY() + 1.5, owner.getZ(), 30, 1.5, 1.2, 1.5, 0.08);
         com.fortuneandfavors.net.FfVfx.shape(level, com.fortuneandfavors.net.FfVfx.NOVA, ParticleTypes.PORTAL, owner.position(), net.minecraft.world.phys.Vec3.ZERO, 4.0, 0.0, 0x8C6BFF);
         com.fortuneandfavors.net.FfVfx.shape(level, com.fortuneandfavors.net.FfVfx.CLOCK_BURST, ParticleTypes.PORTAL, owner.position().add(0.0, 1.0, 0.0), net.minecraft.world.phys.Vec3.ZERO, 3.0, 0.0, 0x8C6BFF);
         level.playSound(null, owner.blockPosition(), SoundEvents.EVOKER_CAST_SPELL, SoundSource.PLAYERS, 1.0F, 0.8F);
         // The caster turns TRULY invisible for the whole illusion window (copies
         // fight in their place) - a hit shatters it (see onPlayerHit).
         illusionActiveUntil.put(owner.getUUID(), level.getGameTime() + ILLUSION_COPY_TICKS);
         applyTrueInvisibility(owner);
         illusionBookCd.put(owner.getUUID(), now + 600L);
         Chat.raw(owner, "§d§lIllusion burst! §7Blinded §f" + blindedPlayers + " player" + (blindedPlayers == 1 ? "" : "s")
            + "§7, stunned §f" + stunnedMobs + " mob" + (stunnedMobs == 1 ? "" : "s")
            + "§7 - §f" + illusionCount + " copies of you§7 materialize and mimic your every move!");
         return null;
      } catch (Exception e) {
         return "The spellbook fizzles.";
      }
   }

   /** The Illusioner's Spellbook bearer took a hit while the spell is live:
    *  the illusions shatter, the caster reappears, and the spellbook starts
    *  its cooldown. */
   public static void onPlayerHit(ServerPlayer victim) {
      UUID id = victim.getUUID();
      if (!illusionActiveUntil.containsKey(id)) {
         return;
      }
      breakIllusions(victim);
   }

   private static void breakIllusions(ServerPlayer owner) {
      UUID id = owner.getUUID();
      illusionActiveUntil.remove(id);
      MinecraftServer server = owner.level().getServer();
      if (server != null && owner.level() instanceof ServerLevel level) {
         for (UUID copyId : new java.util.ArrayList<>(copyOwner.keySet())) {
            if (!id.equals(copyOwner.get(copyId))) {
               continue;
            }
            Entity e = findEntity(server, copyId);
            if (e != null && e.isAlive()) {
               com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.POOF, e.getX(), e.getY() + 1.0, e.getZ(), 12, 0.5, 0.7, 0.5, 0.05);
               e.remove(Entity.RemovalReason.DISCARDED);
            }
            removeCopyInfo(server, copyId);
            copyOwner.remove(copyId);
            copyBorn.remove(copyId);
            copyStrike.remove(copyId);
         }
      }
      clearTrueInvisibility(owner);
      long now = owner.level().getGameTime();
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
      if (owner.level() instanceof ServerLevel level) {
         level.playSound(null, owner.blockPosition(), SoundEvents.EVOKER_CAST_SPELL, SoundSource.PLAYERS, 1.0F, 1.6F);
      }
      Chat.raw(owner, "§dYour illusions shatter - §7you're exposed! The spellbook begins recharging.");
   }

   // ------------------------------------------------------- true invisibility

   private static final EquipmentSlot[] ARMOUR_SLOTS = {
      EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
   };
   private static final EquipmentSlot[] MIRRORED_SLOTS = {
      EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND
   };

   /** True while the caster is inside an illusion window. */
   public static boolean isTrulyHidden(ServerPlayer player) {
      return player != null && illusionActiveUntil.containsKey(player.getUUID());
   }

   /**
    * The Illusioner's true invisibility - three things at once, which is what makes
    * it worth a 30 second cooldown:
    *
    * <ul>
    *   <li><b>Nothing is hidden that should not be.</b> The body is hidden with the
    *       entity flag, not the potion effect, so the caster keeps every particle
    *       they were already giving off instead of having them suppressed.</li>
    *   <li><b>The armour is hidden, not removed.</b> Other players are sent an
    *       empty equipment packet, so nothing renders on your body - while the
    *       server keeps the real pieces equipped, so full netherite still
    *       protects. Vanilla invisibility leaves armour on show and lets it give
    *       your position away; this does not.</li>
    *   <li><b>Mobs lose you completely.</b> Existing targets are dropped here, and
    *       {@code VanishMobTargetMixin} refuses to hand out new ones, so armour no
    *       longer widens the range at which you are spotted.</li>
    * </ul>
    */
   private static void applyTrueInvisibility(ServerPlayer owner) {
      owner.setInvisible(true);
      sendEquipment(owner, true);
      dropStaleTargets(owner);
   }

   private static void clearTrueInvisibility(ServerPlayer owner) {
      owner.setInvisible(false);
      sendEquipment(owner, false);
   }

   /** Re-hides the armour from everyone else; sent again mid-window so a gear swap
    *  cannot leak the real equipment back onto their screens. */
   private static void hideArmourFromOthers(ServerPlayer owner) {
      sendEquipment(owner, true);
   }

   private static void sendEquipment(ServerPlayer owner, boolean hide) {
      try {
         MinecraftServer server = owner.level().getServer();
         if (server == null) {
            return;
         }
         List<com.mojang.datafixers.util.Pair<EquipmentSlot, ItemStack>> slots = new java.util.ArrayList<>();
         for (EquipmentSlot slot : ARMOUR_SLOTS) {
            slots.add(com.mojang.datafixers.util.Pair.of(slot, hide ? ItemStack.EMPTY : owner.getItemBySlot(slot).copy()));
         }
         ClientboundSetEquipmentPacket packet = new ClientboundSetEquipmentPacket(owner.getId(), slots);
         for (ServerPlayer other : server.getPlayerList().getPlayers()) {
            if (other != owner && other.connection != null && other.level() == owner.level()) {
               other.connection.send(packet);
            }
         }
      } catch (Exception ignored) {
      }
   }

   /** Everything already fighting the caster loses the thread the moment it turns. */
   private static void dropStaleTargets(ServerPlayer owner) {
      if (!(owner.level() instanceof ServerLevel level)) {
         return;
      }
      for (Mob mob : level.getEntitiesOfClass(Mob.class, owner.getBoundingBox().inflate(64.0))) {
         if (mob.getTarget() == owner) {
            mob.setTarget(null);
         }
      }
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
         List<com.mojang.datafixers.util.Pair<EquipmentSlot, ItemStack>> slots = new java.util.ArrayList<>();
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
    *  copied, glowing magenta. They are separate entities with no leash to you -
    *  they attack anything that moves. */
   private static Player spawnIllusionCopy(ServerPlayer owner, ServerLevel level) {
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
         level.addFreshEntity(copy);
         return copy;
      } catch (Exception e) {
         return null;
      }
   }

   /** Nearest living thing within 14 blocks of an illusion copy - players,
    *  animals and monsters alike. The caster is the only creature spared. */
   private static LivingEntity illusionCopyTarget(Player copy, ServerPlayer owner) {
      if (!(copy.level() instanceof ServerLevel level)) {
         return null;
      }
      LivingEntity best = null;
      double bestDist = 14.0 * 14.0;
      for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class,
            new net.minecraft.world.phys.AABB(copy.getX() - 14, copy.getY() - 8, copy.getZ() - 14,
               copy.getX() + 14, copy.getY() + 8, copy.getZ() + 14))) {
         if (e == copy || !e.isAlive() || BossManager.isFriendlySkeleton(e)
               || copyOwner.containsKey(e.getUUID()) || illusionOwner.containsKey(e.getUUID())) {
            continue;
         }
         if (owner != null && e.getUUID().equals(owner.getUUID())) {
            continue;
         }
         double d = copy.distanceToSqr(e);
         if (d < bestDist) {
            bestDist = d;
            best = e;
         }
      }
      return best;
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
         long now = server.getTickCount();
         // Illusioner Spellbook active window: the caster is invisible and
         // shimmers with enchant particles; when the window ends they reappear.
         for (UUID id : new java.util.ArrayList<>(illusionActiveUntil.keySet())) {
            ServerPlayer owner = server.getPlayerList().getPlayer(id);
            if (owner == null || !owner.isAlive()) {
               illusionActiveUntil.remove(id);
               continue;
            }
            if (now > illusionActiveUntil.get(id)) {
               illusionActiveUntil.remove(id);
               clearTrueInvisibility(owner);
               continue;
            }
            if (now % 10L == 0L && owner.level() instanceof ServerLevel lv) {
               // Deliberately still visible: the illusion keeps its shimmer, and
               // re-hiding the armour every half second stops a mid-window gear
               // swap from leaking the real equipment to nearby clients.
               com.fortuneandfavors.net.FfVfx.particles(lv, ParticleTypes.ENCHANT, owner.getX(), owner.getY() + 1.0, owner.getZ(), 2, 0.3, 0.5, 0.3, 0.02);
               hideArmourFromOthers(owner);
               dropStaleTargets(owner);
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
               illusionBorn.put(vex.getUUID(), ally.level().getGameTime());
               illusionStrike.put(vex.getUUID(), ally.level().getGameTime() + 30L);
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
               if (target != null && now >= strike && vex.distanceToSqr(target) < 169.0) {
                  illusionStrike.put(id, now + 50L);
                  vex.setPos(target.getX(), target.getY() + 0.7, target.getZ());
                  vex.setDeltaMovement(0.0, 0.0, 0.0);
                  vex.hurtMarked = true;
                  ServerLevel lv = (ServerLevel)vex.level();
                  com.fortuneandfavors.net.FfVfx.particles(lv, ParticleTypes.SWEEP_ATTACK, target.getX(), target.getY() + 0.6, target.getZ(), 8, 0.3, 0.3, 0.3, 0.05);
                  lv.playSound(null, target.blockPosition(), SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 0.8F, 1.2F);               boolean cloakBoost = "spellbook".equals(vexKind.get(id)) && owner != null && ModItems.isEvokerCloak(owner.getItemBySlot(EquipmentSlot.CHEST));
               float strikeDmg = "spellbook".equals(vexKind.get(id)) ? (cloakBoost ? 12.0F : 8.0F) : 2.5F;
               // Never let a friendly vex strike its own owner (or another summon).
               if (BossManager.isFriendlySkeleton(target) || copyOwner.containsKey(target.getUUID())
                     || (owner != null && target.getUUID().equals(owner.getUUID()))) {
                  vex.setTarget(null);
               } else {
                  target.hurt(vex.damageSources().mobAttack(vex), strikeDmg);
               }
               }
            }
         }

         // Illusioner Spellbook player copies: separate entities that ATTACK
         // EVERYTHING - players, animals and monsters - but die at 1 HP.
         for (UUID id : new java.util.ArrayList<>(copyOwner.keySet())) {
            Entity e = findEntity(server, id);
            if (e == null || !e.isAlive() || now - copyBorn.getOrDefault(id, now) > ILLUSION_COPY_TICKS) {
               if (e != null && e.isAlive()) {
                  ServerLevel lv = (ServerLevel)e.level();
                  com.fortuneandfavors.net.FfVfx.particles(lv, ParticleTypes.POOF, e.getX(), e.getY() + 1.0, e.getZ(), 12, 0.5, 0.7, 0.5, 0.05);
                  lv.playSound(null, e.blockPosition(), SoundEvents.EVOKER_CAST_SPELL, SoundSource.PLAYERS, 1.0F, 1.4F);
                  e.remove(Entity.RemovalReason.DISCARDED);
               }
               removeCopyInfo(server, id);
               copyOwner.remove(id);
               copyBorn.remove(id);
               copyStrike.remove(id);
               continue;
            }
            if (e instanceof Player copy) {
               UUID ownerId = copyOwner.get(id);
               ServerPlayer owner = ownerId != null ? server.getPlayerList().getPlayer(ownerId) : null;
               if (owner != null && now % 10L == 0L) {
                  mirrorCopyGear(copy, owner);
               }
               LivingEntity target = copy.getLastHurtMob();
               if (target == null || !target.isAlive()) {
                  target = illusionCopyTarget(copy, owner);
               } else if (owner != null && target.getUUID().equals(owner.getUUID())) {
                  target = illusionCopyTarget(copy, owner);
               }
               double hx = target != null ? target.getX() : copy.getX();
               double hz = target != null ? target.getZ() : copy.getZ();
               // The FEET, not the eyeline. The chase used to aim at getY() + 1.0 and push
               // the whole vector - y included - so every copy was permanently asked to
               // stand one block above whatever it was chasing, and a body that can never
               // quite arrive climbs for as long as it can see the mark. That is the
               // report of illusions floating up into the sky.
               double hy = target != null ? target.getY() : copy.getY();
               double dx = hx - copy.getX();
               double dy = hy - copy.getY();
               double dz = hz - copy.getZ();
               double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
               if (dist > 0.4) {
                  // An illusion walks. It is pushed along the ground plane and only lifted
                  // when the mark is a real step away - a target on a ledge, or below it -
                  // with a one-block difference in height counted as ground, not as flight.
                  double vy = 0.0;
                  if (dy > COPY_STEP_UP) {
                     vy = Math.min(0.5, dy / Math.max(1.0, dist) * 0.5);
                  } else if (dy < -COPY_STEP_UP) {
                     vy = Math.max(-0.5, dy / Math.max(1.0, dist) * 0.5);
                  }

                  copy.setDeltaMovement(dx / dist * 0.5, vy, dz / dist * 0.5);
                  copy.hurtMarked = true;
               } else {
                  copy.setDeltaMovement(0.0, 0.0, 0.0);
               }
               long strike = copyStrike.getOrDefault(id, 0L);
               if (target != null && target.isAlive() && now >= strike && copy.distanceToSqr(target) < 4.0) {
                  copyStrike.put(id, now + 30L);
                  ServerLevel lv = (ServerLevel)copy.level();
                  // The copies hit for real, at a fraction of the caster's strength.
                  // They used to mime the swing and deal nothing at all, which made
                  // the whole spellbook a light show: a copy that cannot hurt you is
                  // not a threat to be sorted from the real one, it is a decoration.
                  // Half the weapon's damage is enough to matter and not enough to
                  // make the copies better than the player who cast them.
                  boolean cloaked = owner != null && ModItems.isIllusionerCloak(owner.getItemBySlot(EquipmentSlot.CHEST));
                  float dmg = (cloaked ? 4.0F : 3.0F) + (owner != null ? owner.getMainHandItem().getItem().getAttackDamageBonus(target, 3.0F, lv.damageSources().playerAttack(owner)) : 0.0F);
                  target.hurtServer(lv, lv.damageSources().mobAttack(copy), dmg);
                  com.fortuneandfavors.net.FfVfx.particles(lv, ParticleTypes.SWEEP_ATTACK, target.getX(), target.getY() + 0.6, target.getZ(), 10, 0.4, 0.4, 0.4, 0.06);
                  lv.playSound(null, target.blockPosition(), SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 0.9F, 1.1F);
               }
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
            long now = p.level().getGameTime();
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

   private static int groundY(ServerLevel level, int x, int z, int fallback) {
      int y = level.getHeight(Types.MOTION_BLOCKING, x, z);
      if (y > fallback) {
         return fallback;
      }
      return Math.max(y, level.getMinY() + 1);
   }
}
