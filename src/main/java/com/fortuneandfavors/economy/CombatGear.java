package com.fortuneandfavors.economy;

import com.fortuneandfavors.ModItems;
import com.fortuneandfavors.guild.GuildManager;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.util.ArrayList;
import java.util.HashMap;
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
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Display.BillboardConstraints;
import net.minecraft.world.entity.Display.TextDisplay;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.phys.Vec3;

public final class CombatGear {
   private static final Map<UUID, Long> crownReflectCooldowns = new HashMap<>();
   private static final Map<UUID, Long> lifeStealCooldowns = new HashMap<>();
   /** The Scarlet Fang's own bloodsuck, kept separate so it stacks with Life Steal. */
   private static final Map<UUID, Long> bloodsuckCooldowns = new HashMap<>();
   private static final long LIFE_STEAL_COOLDOWN_TICKS = 10L;
   /** Overclock's arc and Starfall's impact are area procs, so they carry a real
    *  cooldown rather than firing on every swing. */
   private static final Map<UUID, Long> overclockCooldowns = new HashMap<>();
   private static final Map<UUID, Long> starfallCooldowns = new HashMap<>();
   private static final long OVERCLOCK_COOLDOWN_TICKS = 40L;
   private static final long STARFALL_COOLDOWN_TICKS = 50L;
   private static final Map<UUID, Integer> bootCharge = new HashMap<>();
   private static final int BOOT_CHARGE_MAX = 60;
   private static final int BOOT_CHARGE_MIN = 15;
   /** While the cloak is charged the wearer stands their ground: Slowness I and a
    *  ledger of everything the shroud has swallowed. When the shroud ends - the
    *  timer runs out or the last charge is spent - the ledger is paid back as a
    *  withering reprisal burst, so tanking through hits is the point. */
   private static final Map<UUID, Long> wcsShroudUntil = new HashMap<>();
   private static final Map<UUID, Float> wcsLedger = new HashMap<>();
   private static final Map<UUID, List<UUID>> cloakCreepers = new HashMap<>();
   public static final int WCS_SHROUD_TICKS = 240;
   private static final float WCS_REPRISAL_RATIO = 0.6F;
   private static final float WCS_REPRISAL_MIN = 4.0F;
   private static final float WCS_REPRISAL_MAX = 30.0F;
   private static final double WCS_REPRISAL_RADIUS = 5.0D;
   private static final int WCS_WITHER_COLOR = -3611393;
   private static final Random RANDOM = new Random();

   private CombatGear() {
   }

   public static int wcsChargesForTier(int tier) {
      return 2 + Math.max(1, Math.min(3, tier));
   }

   public static void startWitherShroud(ServerPlayer p) {
      if (p != null && p.isAlive()) {
         wcsShroudUntil.put(p.getUUID(), p.level().getGameTime() + WCS_SHROUD_TICKS);
         wcsLedger.put(p.getUUID(), 0.0F);
      }
   }

   public static boolean shrouded(ServerPlayer p) {
      if (p == null) {
         return false;
      }

      Long until = wcsShroudUntil.get(p.getUUID());
      return until != null && p.level().getGameTime() < until;
   }

   /** Units of damage the shroud has swallowed since it was charged. */
   public static float shroudLedger(UUID id) {
      return wcsLedger.getOrDefault(id, 0.0F);
   }

   /** Pure: the reprisal payout for a ledger, clamped so it is never a slap and
    *  never a one-shot. Side-effect free so the self-test can pin the numbers. */
   public static float reprisalDamage(float absorbed, int tier) {
      float raw = absorbed * WCS_REPRISAL_RATIO + Math.max(1, tier);
      return Math.max(WCS_REPRISAL_MIN, Math.min(WCS_REPRISAL_MAX, raw));
   }

   /** The payoff: everything the cloak swallowed comes back out at once. */
   public static void releaseWitherShroud(ServerLevel sl, ServerPlayer p, boolean exhausted) {
      if (wcsShroudUntil.remove(p.getUUID()) == null) {
         return;
      }

      Float stored = wcsLedger.remove(p.getUUID());
      float ledger = stored == null ? 0.0F : stored;
      ItemStack held = p.getMainHandItem();
      int tier = ModItems.isWitherCloakSword(held) ? Math.max(1, ModItems.tierOf(held)) : 1;
      ModItems.setWcsBlocks(held, 0);
      float dmg = reprisalDamage(ledger, tier);
      double x = p.getX();
      double y = p.getY();
      double z = p.getZ();

      for (int i = 0; i < 40; i++) {
         double a = i * (Math.PI * 2.0 / 40.0);
         com.fortuneandfavors.net.FfVfx.particles(sl, ParticleTypes.SOUL_FIRE_FLAME,
            x + Math.cos(a) * 1.4,
            y + 0.9 + Math.sin(a * 2.0) * 0.45,
            z + Math.sin(a) * 1.4,
            1,
            0.0,
            0.02,
            0.0,
            0.02
         );
      }

      for (int ring = 0; ring < 3; ring++) {
         double r = 1.5 + ring * 1.2;
         int n = 18 + ring * 6;

         for (int i = 0; i < n; i++) {
            double a = i * (Math.PI * 2.0 / n) + ring * 0.4;
            double px = x + Math.cos(a) * r;
            double py = y + 0.5 + ring * 0.4;
            double pz = z + Math.sin(a) * r;
            com.fortuneandfavors.net.FfVfx.particles(sl, new DustParticleOptions(WCS_WITHER_COLOR, 1.5F), px, py, pz, 1, 0.03, 0.05, 0.03, 0.05);
            com.fortuneandfavors.net.FfVfx.particles(sl, ParticleTypes.ELECTRIC_SPARK, px, py, pz, 1, Math.cos(a) * 0.05, 0.06, Math.sin(a) * 0.05, 0.16);
         }
      }

      com.fortuneandfavors.net.FfVfx.particles(sl, ParticleTypes.ELECTRIC_SPARK, x, y + 1.0, z, 18, 0.35, 1.1, 0.35, 0.2);
      sl.playSound(null, x, y, z, SoundEvents.WITHER_SHOOT, SoundSource.PLAYERS, 0.9F, 0.7F);
      if (exhausted) {
         sl.playSound(null, x, y, z, SoundEvents.WITHER_HURT, SoundSource.PLAYERS, 0.5F, 0.6F);
      }

      for (LivingEntity e : sl.getEntitiesOfClass(LivingEntity.class, p.getBoundingBox().inflate(WCS_REPRISAL_RADIUS), e -> e != p && e.isAlive())) {
         e.hurtServer(sl, sl.damageSources().indirectMagic(p, p), dmg);
         e.addEffect(new MobEffectInstance(MobEffects.WITHER, 60 + tier * 20, tier >= 3 ? 1 : 0, false, true));
         double dx = e.getX() - x;
         double dz = e.getZ() - z;
         double len = Math.max(0.001, Math.sqrt(dx * dx + dz * dz));
         e.push(dx / len * 0.7, 0.35, dz / len * 0.7);
      }

      // Standing your ground pays off: the frost lets go and the momentum arrives.
      p.removeEffect(MobEffects.SLOWNESS);
      p.addEffect(new MobEffectInstance(MobEffects.SPEED, 100, 0, false, true));
      com.fortuneandfavors.net.FfVfx.particles(sl, ParticleTypes.HAPPY_VILLAGER, x, y + 1.4, z, 6, 0.4, 0.3, 0.4, 0.02);
   }

   public static void tickCloakSword(ServerPlayer p) {
      if (p.isAlive() && p.level() instanceof ServerLevel sl) {
         ItemStack sword = p.getMainHandItem();
         int blocks = ModItems.isWitherCloakSword(sword) ? ModItems.wcsBlocks(sword) : 0;
         long now = sl.getGameTime();
         if (blocks > 0) {
            steerCloakCreepers(sl, p);
         } else {
            dropCloakCreepers(p);
         }

         boolean cloakOut = ModItems.isWitherCloakSword(sword);
         if (cloakOut && now % 3L == 0L) {
            if (blocks > 0) {
               int[] sparksPerRing = new int[]{12, 10, 8};
               double[] radii = new double[]{1.55, 1.15, 0.75};
               double[] heights = new double[]{0.45, 1.15, 1.8};
               double base = now * 0.22;

               for (int ring = 0; ring < 3; ring++) {
                  int n = sparksPerRing[ring];

                  for (int i = 0; i < n; i++) {
                     double a = base * (1.0 + ring * 0.25) + i * ((Math.PI * 2) / n);
                     double r = radii[ring] * (0.9 + 0.1 * Math.sin(now * 0.12 + i));
                     double y = p.getY() + heights[ring] + Math.sin(now * 0.1 + i * 1.3) * 0.18;
                     com.fortuneandfavors.net.FfVfx.particles(sl, ParticleTypes.ELECTRIC_SPARK, p.getX() + Math.cos(a) * r, y, p.getZ() + Math.sin(a) * r, 1, 0.02, 0.02, 0.02, 0.0);
                  }
               }

               if (now % 6L == 0L) {
                  for (int i = 0; i < 4; i++) {
                     double a = now * 0.31 + i * (Math.PI / 2);
                     double r = 0.4 + RANDOM.nextDouble() * 1.1;
                     com.fortuneandfavors.net.FfVfx.particles(sl, ParticleTypes.ELECTRIC_SPARK,
                        p.getX() + Math.cos(a) * r,
                        p.getY() + 0.4 + RANDOM.nextDouble() * 1.5,
                        p.getZ() + Math.sin(a) * r,
                        1,
                        -Math.cos(a) * 0.12,
                        0.0,
                        -Math.sin(a) * 0.12,
                        0.12
                     );
                  }
               }

               com.fortuneandfavors.net.FfVfx.particles(sl, ParticleTypes.ELECTRIC_SPARK, p.getX(), p.getY() + 1.5, p.getZ(), 4, 0.55, 0.45, 0.55, 0.02);

               float ledger = shroudLedger(p.getUUID());
               if (ledger > 0.0F) {
                  int plumes = Math.min(8, 1 + (int)(ledger * 0.4F));

                  for (int i = 0; i < plumes; i++) {
                     double a = now * 0.14 + i * (Math.PI * 2.0 / plumes);
                     double r = 1.0 + Math.sin(now * 0.07 + i) * 0.12;
                     com.fortuneandfavors.net.FfVfx.particles(sl, ParticleTypes.SOUL_FIRE_FLAME,
                        p.getX() + Math.cos(a) * r,
                        p.getY() + 0.25 + i % 3 * 0.55,
                        p.getZ() + Math.sin(a) * r,
                        1,
                        0.01,
                        0.03,
                        0.01,
                        0.0
                     );
                  }

                  if (now % 10L == 0L) {
                     com.fortuneandfavors.net.FfVfx.particles(sl, new DustParticleOptions(WCS_WITHER_COLOR, 1.6F), p.getX(), p.getY() + 1.0, p.getZ(), 6, 0.55, 0.7, 0.55, 0.01);
                  }
               }
            } else {
               int sparks = 4;
               double base = now * 0.22;

               for (int i = 0; i < sparks; i++) {
                  double a = base + i * ((Math.PI * 2) / sparks);
                  double r = 0.55;
                  double y = p.getY() + 0.7 + Math.sin(now * 0.08 + i) * 0.45;
                  com.fortuneandfavors.net.FfVfx.particles(sl, ParticleTypes.ELECTRIC_SPARK, p.getX() + Math.cos(a) * r, y, p.getZ() + Math.sin(a) * r, 1, 0.02, 0.02, 0.02, 0.0);
               }
            }
         }

         Long until = wcsShroudUntil.get(p.getUUID());
         if (until != null) {
            if (now >= until) {
               releaseWitherShroud(sl, p, false);
            } else {
               // The shroud holds you still - you are a wall, not a sprinter - but it
               // also thickens visibly as it eats, so the trade reads at a glance.
               p.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 30, 0, false, false));
            }
         }
      } else {
         dropCloakCreepers(p);
      }
   }

   private static void burstCloakShield(ServerLevel sl, LivingEntity v, boolean broke) {
      double x = v.getX();
      double y = v.getY() + 1.0;
      double z = v.getZ();
      long now = sl.getGameTime();
      int ring = broke ? 28 : 20;

      for (int i = 0; i < ring; i++) {
         double a = now * 0.35 + i * ((Math.PI * 2) / ring);
         double r = broke ? 2.0 : 1.4;
         double px = x + Math.cos(a) * r;
         double pz = z + Math.sin(a) * r;
         com.fortuneandfavors.net.FfVfx.particles(sl, ParticleTypes.ELECTRIC_SPARK, px, y + 0.7, pz, 1, Math.cos(a) * 0.16, 0.0, Math.sin(a) * 0.16, 0.22);
         com.fortuneandfavors.net.FfVfx.particles(sl, ParticleTypes.ELECTRIC_SPARK, px, y - 0.2, pz, 1, Math.cos(a) * 0.16, 0.0, Math.sin(a) * 0.16, 0.22);
      }

      int spray = broke ? 36 : 20;

      for (int i = 0; i < spray; i++) {
         double a = RANDOM.nextDouble() * Math.PI * 2.0;
         double r = 0.35 + RANDOM.nextDouble() * 0.75;
         com.fortuneandfavors.net.FfVfx.particles(sl, ParticleTypes.ELECTRIC_SPARK, x + Math.cos(a) * r, y - 0.5 + RANDOM.nextDouble() * 1.8, z + Math.sin(a) * r, 1, 0.05, 0.05, 0.05, 0.3);
      }

      com.fortuneandfavors.net.FfVfx.particles(sl, new DustParticleOptions(-3611393, 1.3F), x, y, z, broke ? 18 : 10, 0.5, 0.8, 0.5, 0.02);
      if (broke) {
         for (int i = 0; i < 22; i++) {
            double a = i * (Math.PI / 11);
            com.fortuneandfavors.net.FfVfx.particles(sl, ParticleTypes.ELECTRIC_SPARK, x + Math.cos(a) * 2.7, y + 1.5, z + Math.sin(a) * 2.7, 1, 0.03, 0.03, 0.03, 0.12);
         }

         com.fortuneandfavors.net.FfVfx.particles(sl, ParticleTypes.ELECTRIC_SPARK, x, v.getY(), z, 14, 0.3, 2.0, 0.3, 0.12);
      }
   }

   private static void steerCloakCreepers(ServerLevel sl, ServerPlayer p) {
      List<UUID> ids = cloakCreepers.get(p.getUUID());
      if (ids == null) {
         ids = new ArrayList<>();

         for (int i = 0; i < 3; i++) {
            Creeper c = (Creeper)EntityTypes.CREEPER.create(sl, EntitySpawnReason.COMMAND);
            if (c != null) {
               c.setPos(p.getX(), p.getY() + 1.0, p.getZ());
               c.setInvisible(true);
               c.setInvulnerable(true);
               c.setSilent(true);
               c.setNoAi(true);
               c.setNoGravity(true);
               c.noPhysics = true;
               c.setPersistenceRequired();
               sl.addFreshEntity(c);
               ids.add(c.getUUID());
            }
         }

         if (ids.isEmpty()) {
            return;
         }

         cloakCreepers.put(p.getUUID(), ids);
      }

      double base = sl.getGameTime() * 0.18;
      List<UUID> alive = new ArrayList<>();

      for (int i = 0; i < ids.size(); i++) {
         Entity e = sl.getEntity(ids.get(i));
         if (e != null && e.isAlive()) {
            double a = base + i * Math.PI * 2.0 / 3.0;
            double y = p.getY() + 0.85 + Math.sin(sl.getGameTime() * 0.09 + i * 2.1) * 0.35;
            e.setPos(p.getX() + Math.cos(a) * 1.6, y, p.getZ() + Math.sin(a) * 1.6);
            alive.add(e.getUUID());
         }
      }

      if (alive.isEmpty()) {
         cloakCreepers.remove(p.getUUID());
      } else {
         cloakCreepers.put(p.getUUID(), alive);
      }
   }

   private static void dropCloakCreepers(ServerPlayer p) {
      List<UUID> ids = cloakCreepers.remove(p.getUUID());
      if (ids != null && p.level() instanceof ServerLevel sl) {
         for (UUID id : ids) {
            for (ServerLevel l : sl.getServer().getAllLevels()) {
               Entity e = l.getEntity(id);
               if (e != null) {
                  e.discard();
                  break;
               }
            }
         }
      }
   }

   public static void onPlayerDisconnect(ServerPlayer p) {
      dropCloakCreepers(p);
      // Every other map in this class is keyed by player UUID and holds its entry
      // until something removes it - and nothing did. On a public server that grew
      // one entry per player who had ever landed a hit or charged a super jump,
      // permanently. The newer gear classes all release their rows on disconnect;
      // this one predates the habit, so it is brought in line here.
      forget(p.getUUID());
   }

   /** Drops every per-player row this class keeps. One place to add a new map. */
   private static void forget(UUID id) {
      crownReflectCooldowns.remove(id);
      lifeStealCooldowns.remove(id);
      bloodsuckCooldowns.remove(id);
      overclockCooldowns.remove(id);
      starfallCooldowns.remove(id);
      bootCharge.remove(id);
      wcsShroudUntil.remove(id);
      wcsLedger.remove(id);
      procLabelExpiry.remove(id);
      procPopupCooldown.remove(id);
   }

   /** Resets all per-player state. Called when the server stops, so a world loaded
    *  in the same process cannot inherit the last one's charges and cooldowns. */
   public static void clear() {
      crownReflectCooldowns.clear();
      lifeStealCooldowns.clear();
      bloodsuckCooldowns.clear();
      overclockCooldowns.clear();
      starfallCooldowns.clear();
      bootCharge.clear();
      wcsShroudUntil.clear();
      wcsLedger.clear();
      cloakCreepers.clear();
      procLabelExpiry.clear();
      procPopupCooldown.clear();
   }

   public static void tickBoots(ServerPlayer p) {
      if (p.isAlive()) {
         boolean wearing = ModItems.isSlimeBoots(p.getItemBySlot(EquipmentSlot.FEET));
         int tier = wearing ? ModItems.tierOf(p.getItemBySlot(EquipmentSlot.FEET)) : 1;
         int chargeMax = 60 - (tier - 1) * 10;
         int charge = bootCharge.getOrDefault(p.getUUID(), 0);
         if (wearing && p.isShiftKeyDown() && p.onGround()) {
            if (charge < chargeMax) {
               bootCharge.put(p.getUUID(), ++charge);
            }

            if (charge % 10 == 0 && p.level() instanceof ServerLevel sl) {
               com.fortuneandfavors.net.FfVfx.particles(sl, ParticleTypes.ITEM_SLIME, p.getX(), p.getY() + 0.1, p.getZ(), 8, 0.3, 0.05, 0.3, 0.02);
               int pct = Math.min(100, charge * 100 / chargeMax);
               p.sendSystemMessage(Component.literal("§aSuper jump charging... §f" + pct + "%§7 (release Shift to launch)"), true);
            }
         } else {
            if (charge > 0) {
               bootCharge.remove(p.getUUID());
               if (wearing && p.onGround() && charge >= 15) {
                  double power = Math.min(1.9 + (tier - 1) * 0.15, 0.7 + charge * 0.02 + (tier - 1) * 0.1);
                  p.setDeltaMovement(p.getDeltaMovement().x, power, p.getDeltaMovement().z);
                  p.hurtMarked = true;
                  p.resetFallDistance();
                  if (p.level() instanceof ServerLevel sl) {
                     com.fortuneandfavors.net.FfVfx.shape(sl, com.fortuneandfavors.net.FfVfx.GOO_SPLASH, ParticleTypes.ITEM_SLIME, p.position(), net.minecraft.world.phys.Vec3.ZERO, 1.4, 0.0, 0x6FE36A);
                     com.fortuneandfavors.net.FfVfx.particles(sl, ParticleTypes.ITEM_SLIME, p.getX(), p.getY() + 0.2, p.getZ(), 20, 0.5, 0.2, 0.5, 0.05);
                     sl.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.SLIME_JUMP, SoundSource.PLAYERS, 1.0F, 0.9F);
                  }
               }
            }

            if (p.isBlocking() && ModItems.isSlimeShield(p.getUseItem()) && p.level() instanceof ServerLevel slw && slw.getGameTime() % 5L == 0L) {
               emitSlimeWall(slw, p);
            }

            if (p.level() instanceof ServerLevel sl && p.level().getGameTime() % 10L == 0L) {
               ItemStack shield = ModItems.findSlimeShield(p);
               if (shield != null) {
                  int max = ModItems.slimeShieldMaxCharges(shield);
                  int charges = ModItems.slimeShieldCharges(shield);
                  int show = charges;
                  long rechargeAt = ModItems.slimeShieldRechargeAt(shield);
                  if (charges <= 0 && rechargeAt > 0L) {
                     long now = p.level().getGameTime();
                     long total = ModItems.slimeShieldRechargeTicks(shield);
                     long left = Math.max(0L, rechargeAt - now);
                     show = (int)Math.ceil((double)(max * (total - left)) / Math.max(1L, total));
                  }

                  show = Math.max(0, Math.min(show, max));
                  int count = Math.max(1, show);
                  double y = p.getY() + 0.15;

                  for (int i = 0; i < count; i++) {
                     double a = (p.level().getGameTime() * 0.12 + i * ((Math.PI * 2) / count)) % (Math.PI * 2);
                     com.fortuneandfavors.net.FfVfx.particles(sl, ParticleTypes.ITEM_SLIME, p.getX() + Math.cos(a) * 0.55, y, p.getZ() + Math.sin(a) * 0.55, 1, 0.0, 0.02, 0.0, 0.0);
                  }
               }
            }
         }
      }
   }

   private static void emitSlimeWall(ServerLevel sl, ServerPlayer p) {
      try {
         Vec3 look = p.getLookAngle();
         Vec3 f = new Vec3(look.x, 0.0, look.z).normalize();
         if (f.lengthSqr() < 0.01) {
            f = new Vec3(0.0, 0.0, 1.0);
         }

         Vec3 right = new Vec3(-f.z, 0.0, f.x);
         double cx = p.getX() + f.x * 1.9;
         double cz = p.getZ() + f.z * 1.9;

         for (int col = -3; col <= 3; col++) {
            double t = col / 3.0;
            double bulge = (1.0 - t * t) * 0.45;
            double x = cx + right.x * t * 1.15;
            double z = cz + right.z * t * 1.15;

            for (int row = 0; row <= 3; row++) {
               double y = p.getY() + 0.25 + row * 0.55;
               com.fortuneandfavors.net.FfVfx.particles(sl, ParticleTypes.ITEM_SLIME, x + f.x * bulge, y, z + f.z * bulge, 1, 0.07, 0.06, 0.07, 0.0);
            }
         }

         for (int i = 0; i < 5; i++) {
            com.fortuneandfavors.net.FfVfx.particles(sl, ParticleTypes.ITEM_SLIME,
               cx + (sl.getRandom().nextDouble() - 0.5) * 2.2,
               p.getY() + 0.05,
               cz + (sl.getRandom().nextDouble() - 0.5) * 2.2,
               1,
               0.04,
               0.03,
               0.04,
               0.0
            );
         }
      } catch (Exception var21) {
      }
   }

   public static float apply(LivingEntity instance, DamageSource source, float amount, Operation<Float> operation) {
      // DamageSource.getEntity() is the CAUSING entity in 26.2, so ranged hits are
      // already credited to the projectile's owner - custom enchantments fused onto
      // bows, crossbows and tridents proc through this same path as melee.
      if (source.getEntity() instanceof ServerPlayer attacker) {
         if (!(instance instanceof Player)) {
            // Astral lives here, and only here, on purpose: this branch is the mob-only
            // side of the damage pipeline, so "never players" needs no condition to be
            // right. Rare, and the plainest of the boss enchants - it does one thing.
            int astral = CustomEnchantments.levelOf(attacker.getMainHandItem(), CustomEnchantments.ASTRAL);
            if (astral > 0) {
               amount *= 1.0F + 0.07F * astral;
            }
            amount *= SkillManager.combatDamageMultiplier(attacker.getUUID());
            // Guild skills: Might is a flat bonus against every mob, Rally stacks
            // on top of it for anything the boss system recognises.
            amount *= GuildManager.damageMultiplier(attacker.getUUID());
            if (BossManager.isBoss(instance)) {
               amount *= GuildManager.bossDamageMultiplier(attacker.getUUID());
            }
            if (instance.getMaxHealth() > 0.0F && instance.getHealth() < instance.getMaxHealth() * 0.25F) {
               amount *= SkillManager.executeMultiplier(attacker.getUUID());
            }
         }

         ItemStack weapon = attacker.getMainHandItem();
         if ((ModItems.isWitherBlade(weapon) || ModItems.isWitherCloakSword(weapon)) && instance != attacker) {
            float witherChance = (ModItems.isWitherCloakSword(weapon) ? 0.35F : 0.3F) + 0.1F * (ModItems.tierOf(weapon) - 1);
            if (attacker.getRandom().nextFloat() < witherChance) {
               instance.addEffect(new MobEffectInstance(MobEffects.WITHER, 140, 1));
               if (instance.level() instanceof ServerLevel wl) {
                  com.fortuneandfavors.net.FfVfx.shape(wl, com.fortuneandfavors.net.FfVfx.NOVA, ParticleTypes.SOUL_FIRE_FLAME, instance.position().add(0.0, 0.5, 0.0), net.minecraft.world.phys.Vec3.ZERO, 1.6, 0.0, 0x3FD8FF);
               }
            }
         }

         // Raid gear: the Warlord's Axe deals +15% while held, and the Warlord's
         // Cloak pushes ALL axe damage +15% while worn.
         if (ModItems.isWarlordAxe(weapon) && instance != attacker) {
            amount *= 1.15F;
         }
         if (ModItems.isWarlordCloak(attacker.getItemBySlot(EquipmentSlot.CHEST)) && weapon.is(ItemTags.AXES) && instance != attacker) {
            amount *= 1.15F;
         }

         int lifeSteal = CustomEnchantments.levelOf(weapon, "ff_lifesteal");
         if (lifeSteal > 0 && instance != attacker && attacker.isAlive()) {
            long now = attacker.level().getGameTime();
            Long last = lifeStealCooldowns.get(attacker.getUUID());
            if (last == null || now - last >= 10L) {
               lifeStealCooldowns.put(attacker.getUUID(), now);
               attacker.heal(lifeSteal);
               if (attacker.level() instanceof ServerLevel sl) {
                  com.fortuneandfavors.net.FfVfx.particles(sl, ParticleTypes.HEART, attacker.getX(), attacker.getY() + 1.6, attacker.getZ(), 2, 0.3, 0.3, 0.3, 0.0);
                  procPopup(sl, attacker, "§c♥ +" + lifeSteal);
               }
            }
         }

         // The Scarlet Fang's Bloodsuck: unlike Life Steal (a flat heal) it returns
         // a share of the blow, so the heavy hits are the ones that give blood.
         if (ModItems.isScarletFang(weapon) && instance != attacker && attacker.isAlive()) {
            long now = attacker.level().getGameTime();
            Long last = bloodsuckCooldowns.get(attacker.getUUID());
            if (last == null || now - last >= 10L) {
               float heal = Math.min(4.0F, amount * 0.15F);
               if (heal > 0.0F) {
                  bloodsuckCooldowns.put(attacker.getUUID(), now);
                  attacker.heal(heal);
                  if (attacker.level() instanceof ServerLevel sl) {
                     com.fortuneandfavors.net.FfVfx.particles(sl, ParticleTypes.HEART, attacker.getX(), attacker.getY() + 1.6, attacker.getZ(), 2, 0.3, 0.3, 0.3, 0.0);
                     procPopup(sl, attacker, "§4Bloodsuck +" + Math.round(heal));
                  }
               }
            }
         }

         int sticky = CustomEnchantments.levelOf(weapon, "ff_sticky");
         if (sticky > 0 && instance != attacker) {
            instance.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, sticky * 100, 0));
            if (attacker.level() instanceof ServerLevel sl) {
               procPopup(sl, instance, "§aSticky!");
            }
         }

         int seismic = CustomEnchantments.levelOf(weapon, "ff_seismic");
         if (seismic > 0 && instance != attacker) {
            double dx = instance.getX() - attacker.getX();
            double dz = instance.getZ() - attacker.getZ();
            double d = Math.max(0.1, Math.sqrt(dx * dx + dz * dz));
            instance.setDeltaMovement(
               instance.getDeltaMovement().add(dx / d * (0.35 + seismic * 0.22), 0.32 + seismic * 0.14, dz / d * (0.35 + seismic * 0.22))
            );
            instance.hurtMarked = true;
            if (attacker.level() instanceof ServerLevel sl) {
               procPopup(sl, instance, "§7Seismic!");
            }
         }

         int mindWrack = CustomEnchantments.levelOf(weapon, "ff_mindwrack");
         if (mindWrack > 0 && instance != attacker) {
            instance.addEffect(new MobEffectInstance(MobEffects.NAUSEA, mindWrack * 50, 0));
            instance.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, mindWrack * 50, 0));
            if (attacker.level() instanceof ServerLevel sl) {
               procPopup(sl, instance, "§dMind Wrack!");
            }
         }

         int frostbite = CustomEnchantments.levelOf(weapon, "ff_frostbite");
         if (frostbite > 0 && instance != attacker && attacker.level() instanceof ServerLevel sl) {
            instance.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, frostbite * 60, 0));
            BossManager.freezeBuildup(sl, instance, 5 + frostbite * 3);
            instance.setTicksFrozen(Math.min(instance.getTicksRequiredToFreeze(), instance.getTicksFrozen() + 30));
            procPopup(sl, instance, "§bFrostbite!");
         }

         // Aging counts up on every hit; the fifth stack detonates (see
         // TimeLordManager.age, which also owns the boss-stack cap).
         int aging = CustomEnchantments.levelOf(weapon, CustomEnchantments.AGING);
         if (aging > 0 && instance != attacker && instance.isAlive()) {
            TimeLordManager.age(instance, attacker);
         }

         // Overclock (Clockwork King): the discharge arcs out of the target into
         // everything packed around it, so it rewards fighting in a crowd.
         int overclock = CustomEnchantments.levelOf(weapon, CustomEnchantments.OVERCLOCK);
         if (overclock > 0 && instance != attacker && attacker.level() instanceof ServerLevel sl) {
            long now = sl.getGameTime();
            Long last = overclockCooldowns.get(attacker.getUUID());
            if (last == null || now - last >= OVERCLOCK_COOLDOWN_TICKS) {
               overclockCooldowns.put(attacker.getUUID(), now);
               // The attacker is excluded, not the target. This used to read
               // getEntities(instance, ...) - which is the API's "everything except this
               // entity" - so the one body the proc is named after was left out of its own
               // cleave, and with no crowd around it the enchant did nothing at all: no
               // damage, no effect, no tell. "The target is always overloaded" was in the
               // comment the whole time; the query was the thing that disagreed.
               for (Entity e : sl.getEntities(attacker, instance.getBoundingBox().inflate(3.5 + overclock * 0.5))) {
                  if (!(e instanceof LivingEntity le) || !le.isAlive() || le == attacker) {
                     continue;
                  }
                  // The struck target always overloads; its neighbours are a roll, so
                  // the arc is a crowd proc rather than a guaranteed cleave.
                  if (le != instance && sl.getRandom().nextFloat() >= 0.6F) {
                     continue;
                  }
                  le.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 60 + overclock * 20, 0, false, true, true));
                  le.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 40, 0, false, true, true));
                  com.fortuneandfavors.net.FfVfx.particles(sl, ParticleTypes.ELECTRIC_SPARK, le.getX(), le.getY() + 1.0, le.getZ(), 12, 0.35, 0.5, 0.35, 0.05);
               }
               sl.playSound(null, instance.getX(), instance.getY(), instance.getZ(), SoundEvents.REDSTONE_TORCH_BURNOUT, SoundSource.PLAYERS, 1.0F, 1.6F);
               procPopup(sl, instance, "§6⚙ Overclock!");
            }
         }

         // Starfall (Starbound Magister): the blow is heavier and a star lands on
         // the victim, cleaving the enemies gathered around them. The victim takes
         // the extra damage through this same blow; only the neighbours are hit
         // directly, which keeps the proc from re-entering the damage pipeline on
         // the target it is already resolving.
         int starfall = CustomEnchantments.levelOf(weapon, CustomEnchantments.STARFALL);
         if (starfall > 0 && instance != attacker && attacker.level() instanceof ServerLevel sl) {
            amount *= 1.0F + 0.1F * starfall;
            long now = sl.getGameTime();
            Long last = starfallCooldowns.get(attacker.getUUID());
            if (last == null || now - last >= STARFALL_COOLDOWN_TICKS) {
               starfallCooldowns.put(attacker.getUUID(), now);
               float star = 1.5F + starfall;
               for (Entity e : sl.getEntities(instance, instance.getBoundingBox().inflate(2.5 + starfall * 0.5))) {
                  if (e instanceof LivingEntity le && le.isAlive() && le != attacker && le != instance && !BossManager.isBoss(le)) {
                     le.hurtServer(sl, sl.damageSources().magic(), star);
                  }
               }
               com.fortuneandfavors.net.FfVfx.particles(sl, ParticleTypes.END_ROD, instance.getX(), instance.getY() + 1.2, instance.getZ(), 30, 0.4, 0.6, 0.4, 0.12);
               com.fortuneandfavors.net.FfVfx.particles(sl, ParticleTypes.FIREWORK, instance.getX(), instance.getY() + 1.0, instance.getZ(), 12, 0.3, 0.3, 0.3, 0.08);
               sl.playSound(null, instance.getX(), instance.getY(), instance.getZ(), SoundEvents.FIREWORK_ROCKET_LARGE_BLAST, SoundSource.PLAYERS, 0.9F, 1.4F);
               procPopup(sl, instance, "§b✦ Starfall!");
            }
         }

         // Voidrend (Void Shaper): the blow tears a seam through armour and drags
         // the target back toward the attacker instead of knocking it away.
         int voidrend = CustomEnchantments.levelOf(weapon, CustomEnchantments.VOIDREND);
         if (voidrend > 0 && instance != attacker) {
            amount *= 1.0F + 0.08F * voidrend;
            double dx = attacker.getX() - instance.getX();
            double dz = attacker.getZ() - instance.getZ();
            double d = Math.max(0.1, Math.sqrt(dx * dx + dz * dz));
            instance.setDeltaMovement(
               instance.getDeltaMovement().add(dx / d * (0.18 + voidrend * 0.08), 0.1, dz / d * (0.18 + voidrend * 0.08))
            );
            instance.hurtMarked = true;
            if (attacker.level() instanceof ServerLevel sl) {
               com.fortuneandfavors.net.FfVfx.particles(sl, ParticleTypes.PORTAL, instance.getX(), instance.getY() + 1.0, instance.getZ(), 20, 0.3, 0.5, 0.3, 0.4);
               procPopup(sl, instance, "§5Voidrend!");
            }
         }

         // Sovereign's Levy (Emerald Sovereign): the hit marks the target as a
         // debtor. What they owe is collected on death - see onKill below.
         int levy = CustomEnchantments.levelOf(weapon, CustomEnchantments.LEVY);
         if (levy > 0 && instance != attacker) {
            amount *= 1.0F + 0.05F * levy;
            instance.addEffect(new MobEffectInstance(MobEffects.GLOWING, 20 * (4 + levy), 0, false, false, true));
            if (attacker.level() instanceof ServerLevel sl) {
               procPopup(sl, instance, "§aTribute Marked!");
            }
         }

         if ((ModItems.isWitherBlade(weapon) || ModItems.isWitherCloakSword(weapon)) && instance instanceof Player) {
            amount *= 0.35F;
         }
      }

      if (instance instanceof ServerPlayer victim) {
         if (source.getEntity() instanceof ServerPlayer attacker
            && attacker != victim
            && GuildManager.isGuildmate(victim.getUUID(), attacker.getUUID())
            && !GuildManager.friendlyFireOn(victim.getUUID())) {
            amount *= 0.75F;
         }

         // Guild skill: Ward softens incoming mob damage (never player damage, so
         // it can't be used to make PvP one-sided), and a Blood Moon / Double
         // Trouble makes mobs hit harder - the event's headline promise, which
         // previously existed only in its chat text.
         if (source.getEntity() instanceof LivingEntity && !(source.getEntity() instanceof Player)) {
            amount *= GuildManager.damageTakenMultiplier(victim.getUUID());
            amount *= ServerDisasterManager.mobDamageMultiplier();
         }

         // ServerClock, because BossManager stamps its devour windows on that clock - a realm's own
         // game time is frozen, so asking the level here would read as "the devour started in the
         // far future" and hand out the bypass at random.
         boolean kingBypass = BossManager.isAnyKingDevouring(ServerClock.clock(victim.level())) || BossManager.isRitualVictim(victim);
         if (source.is(DamageTypeTags.IS_FALL) && ModItems.isSlimeBoots(victim.getItemBySlot(EquipmentSlot.FEET)) && !kingBypass) {
            boolean bounceOn = ModItems.slimeBootsBounce(victim.getItemBySlot(EquipmentSlot.FEET));
            if (bounceOn && !victim.isShiftKeyDown()) {
               double bounce = Math.min(1.6, 0.35 + victim.fallDistance * 0.08);
               victim.setDeltaMovement(victim.getDeltaMovement().x, bounce, victim.getDeltaMovement().z);
               victim.resetFallDistance();
               victim.hurtMarked = true;
               if (victim.level() instanceof ServerLevel sl) {
                  com.fortuneandfavors.net.FfVfx.shape(sl, com.fortuneandfavors.net.FfVfx.GOO_SPLASH, ParticleTypes.ITEM_SLIME, victim.position(), net.minecraft.world.phys.Vec3.ZERO, 1.8, 0.0, 0x6FE36A);
                  com.fortuneandfavors.net.FfVfx.particles(sl, ParticleTypes.ITEM_SLIME, victim.getX(), victim.getY() + 0.2, victim.getZ(), 10, 0.4, 0.2, 0.4, 0.05);
               }
            } else {
               victim.resetFallDistance();
            }

            return (Float)operation.call(new Object[]{instance, source, 0.0F});
         }

         ItemStack shield = ModItems.findSlimeShield(victim);
         boolean raising = victim.isBlocking() && ModItems.isSlimeShield(victim.getUseItem());
         if (shield != null
            && !raising
            && !source.is(DamageTypeTags.IS_FALL)
            && !BossManager.isDevouringAttacker(source.getEntity() instanceof LivingEntity le ? le : null)
            && !BossManager.isRitualVictim(victim)) {
            int maxCharges = ModItems.slimeShieldMaxCharges(shield);
            long rechargeTicks = ModItems.slimeShieldRechargeTicks(shield);
            int charges = ModItems.slimeShieldCharges(shield);
            if (charges <= 0 && victim.level().getGameTime() >= ModItems.slimeShieldRechargeAt(shield)) {
               ModItems.setSlimeShieldState(shield, maxCharges, 0L);
               charges = maxCharges;
               victim.level().playSound(null, victim.getX(), victim.getY(), victim.getZ(), SoundEvents.SLIME_JUMP, SoundSource.PLAYERS, 0.9F, 1.6F);
            }

            if (charges > 0) {
               int left = charges - 1;
               long rechargeAt = left <= 0 ? victim.level().getGameTime() + rechargeTicks : 0L;
               ModItems.setSlimeShieldState(shield, left, rechargeAt);
               if (victim.level() instanceof ServerLevel sl) {
                  com.fortuneandfavors.net.FfVfx.shape(sl, com.fortuneandfavors.net.FfVfx.GOO_SPLASH, ParticleTypes.ITEM_SLIME, victim.position().add(0.0, 1.0, 0.0), net.minecraft.world.phys.Vec3.ZERO, 1.0, 0.0, 0x6FE36A);
                  com.fortuneandfavors.net.FfVfx.particles(sl, ParticleTypes.ITEM_SLIME, victim.getX(), victim.getY() + 1.0, victim.getZ(), 14, 0.5, 0.5, 0.5, 0.05);
               }

               victim.level().playSound(null, victim.getX(), victim.getY(), victim.getZ(), SoundEvents.SLIME_SQUISH, SoundSource.PLAYERS, 1.0F, 1.3F);
               if (left <= 0) {
                  victim.level().playSound(null, victim.getX(), victim.getY(), victim.getZ(), SoundEvents.SLIME_SQUISH, SoundSource.PLAYERS, 1.0F, 0.6F);
                  if (victim instanceof Player pl && ModItems.tierOf(shield) < 3) {
                     pl.getCooldowns().addCooldown(shield.copy(), (int)rechargeTicks);
                  }
               }

               return (Float)operation.call(new Object[]{instance, source, 0.0F});
            }
         }

         ItemStack cloakSword = victim.getMainHandItem();
         int cloakBlocks = ModItems.isWitherCloakSword(cloakSword) ? ModItems.wcsBlocks(cloakSword) : 0;
         if (cloakBlocks > 0
            && !source.is(DamageTypeTags.IS_FALL)
            && (source.getDirectEntity() instanceof LivingEntity || source.getDirectEntity() instanceof Projectile)) {
            ModItems.setWcsBlocks(cloakSword, cloakBlocks - 1);
            boolean broke = cloakBlocks - 1 <= 0;
            // Everything the shroud eats is remembered - it is paid back when the
            // shroud drops (or the instant the last charge is spent).
            wcsLedger.merge(victim.getUUID(), amount, Float::sum);
            if (victim.level() instanceof ServerLevel sl) {
               burstCloakShield(sl, victim, broke);
            }

            victim.level().playSound(null, victim.getX(), victim.getY(), victim.getZ(), SoundEvents.LIGHTNING_BOLT_IMPACT, SoundSource.PLAYERS, 0.7F, 1.7F);
            if (broke) {
               victim.level().playSound(null, victim.getX(), victim.getY(), victim.getZ(), SoundEvents.GLASS_BREAK, SoundSource.PLAYERS, 1.0F, 0.7F);
               if (victim.level() instanceof ServerLevel sl) {
                  releaseWitherShroud(sl, victim, true);
               }
            }

            return (Float)operation.call(new Object[]{instance, source, 0.0F});
         }

         if (!(source.getEntity() instanceof Player)) {
            amount *= SkillManager.tankMultiplier(victim.getUUID());
         }

         if (ModItems.isWitherCrown(victim.getItemBySlot(EquipmentSlot.HEAD))
            && source.getEntity() instanceof LivingEntity attackerEntity
            && attackerEntity != victim) {
            long now = System.currentTimeMillis();
            Long last = crownReflectCooldowns.get(attackerEntity.getUUID());
            if (last == null || now - last > 3000L) {
               crownReflectCooldowns.put(attackerEntity.getUUID(), now);
               attackerEntity.addEffect(new MobEffectInstance(MobEffects.WITHER, 100, 0));
            }
         }
      }

      return (Float)operation.call(new Object[]{instance, source, amount});
   }

   public static void onKill(ServerPlayer killer) {
      ItemStack weapon = killer.getMainHandItem();
      // Sovereign's Levy: the marked debtor pays up. Chance is per level, and the
      // purse scales with it, so a Levy III weapon shakes loose a real handful.
      int levy = CustomEnchantments.levelOf(weapon, CustomEnchantments.LEVY);
      if (levy > 0 && killer.isAlive() && killer.level() instanceof ServerLevel levyLevel) {
         if (RANDOM.nextFloat() < 0.2F * levy) {
            int emeralds = 1 + RANDOM.nextInt(levy * 2);
            killer.getInventory().placeItemBackInInventory(new ItemStack(net.minecraft.world.item.Items.EMERALD, emeralds));
            levyLevel.playSound(null, killer.getX(), killer.getY(), killer.getZ(), SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 1.0F, 1.2F);
            procPopup(levyLevel, killer, "§aTribute +" + emeralds);
         }
      }
      if ((ModItems.isWitherBlade(weapon) || ModItems.isWitherCloakSword(weapon)) && killer.isAlive()) {
         int heal = 2 * ModItems.tierOf(weapon);
         if (heal > 0) {
            killer.heal(heal);
            if (killer.level() instanceof ServerLevel sl) {
               com.fortuneandfavors.net.FfVfx.shape(sl, com.fortuneandfavors.net.FfVfx.PILLAR, ParticleTypes.SOUL_FIRE_FLAME, killer.position(), net.minecraft.world.phys.Vec3.ZERO, 2.5, 0.0, 0x3FD8FF);
               com.fortuneandfavors.net.FfVfx.particles(sl, ParticleTypes.HEART, killer.getX(), killer.getY() + 1.6, killer.getZ(), 4, 0.3, 0.3, 0.3, 0.0);
            }
         }
      }
   }

   // ------------------------------------------------------- proc popups (floating text)

   private static final Map<UUID, Long> procLabelExpiry = new HashMap<>();
   private static final Map<UUID, Long> procPopupCooldown = new HashMap<>();
   private static final long PROC_POPUP_INTERVAL = 20L;
   private static final long PROC_LABEL_LIFETIME = 30L;

   /** Small floating text above the target (or the attacker for Life Steal) so
    *  custom-enchantment procs are visible without reading chat. Throttled per
    *  entity so fast weapons don't spam the world with labels. */
   /** Public passthrough so the Time Lord fight (and other out-of-file callers)
    *  can raise the same throttled proc label without duplicating the throttle. */
   public static void procPopupPublic(ServerLevel level, Entity at, String text) {
      procPopup(level, at, text);
   }

   private static void procPopup(ServerLevel level, Entity at, String text) {
      // Both the popup throttling and the label expiry are keyed off the server
      // tick count, so tickProcLabels (which compares against server.getTickCount())
      // always lets labels expire on time. Using level.getGameTime() here was the bug
      // that made popups linger: on a long-lived world gameTime runs far ahead of the
      // tick count, so the expiry never became due.
      MinecraftServer server = level.getServer();
      long now = server.getTickCount();
      Long last = procPopupCooldown.get(at.getUUID());
      if (last != null && now - last < PROC_POPUP_INTERVAL) {
         return;
      }
      procPopupCooldown.put(at.getUUID(), now);
      TextDisplay td = (TextDisplay)EntityTypes.TEXT_DISPLAY.create(level, EntitySpawnReason.COMMAND);
      if (td == null) {
         return;
      }
      td.setPos(at.getX(), at.getEyeY() + 0.55, at.getZ());
      td.setNoGravity(true);
      td.setInvulnerable(true);
      // Marked so the orphan sweep can always recognize and remove these, even
      // if this server dies before the 30s expiry tick runs.
      td.addTag(ModItems.DISPLAY_TAG);
      td.addTag(ModItems.DISPLAY_TMP_TAG);
      td.setBillboardConstraints(BillboardConstraints.CENTER);
      td.setText(Component.literal(text));
      level.addFreshEntity(td);
      procLabelExpiry.put(td.getUUID(), now + PROC_LABEL_LIFETIME);
   }

   /** True when a text display belongs to the mod's ephemeral popup systems
    *  (combat procs, possessed-mask price labels). Ephemeral labels carry both
    *  the mod tag and the tmp tag; the tmp tag alone is enough to identify them. */
   private static boolean isTmpDisplay(TextDisplay td) {
      return td.entityTags().contains(ModItems.DISPLAY_TMP_TAG);
   }

   /** True when the display sits directly above a skull block - the signature of
    *  a NiceKeepInventory grave label (a player-head grave marker). */
   private static boolean aboveSkull(ServerLevel level, TextDisplay td) {
      return level.getBlockState(td.blockPosition().below()).getBlock()
         instanceof net.minecraft.world.level.block.SkullBlock;
   }

   /** True for text displays spawned by pre-1.0.42 builds (which carried no
    *  tags), recognized by the exact popup texts this mod used - combat procs
    *  ("Sticky!", "Seismic!", ...) and mask-sense price labels ("$12") - plus
    *  the old spawn pattern: invulnerable, frozen, never ridden. Vanilla-summoned
    *  displays are not invulnerable, so they never match. */
   private static boolean looksLikeLegacyPopup(ServerLevel level, TextDisplay td) {
      if (td.entityTags().contains(ModItems.DISPLAY_TAG)) {
         return false;
      }
      if (!td.isInvulnerable() || !td.isNoGravity() || td.isVehicle() || aboveSkull(level, td)) {
         return false;
      }
      try {
         String text = td.textRenderState().text().getString();
         return text.equals("§aSticky!") || text.equals("§7Seismic!")
            || text.equals("§dMind Wrack!") || text.equals("§bFrostbite!")
            || text.startsWith("§c♥ +") || text.startsWith("§a$");
      } catch (Exception e) {
         return false;
      }
   }

   /** Discards stale mod text displays in every world. "Stale" means:
    *  <ul>
    *  <li>tmp-tagged (proc popup / mask label) but no longer tracked by the
    *  live systems that own them - a crash or missed cleanup left them behind;</li>
    *  <li>tagged grave labels whose player-head skull is gone;</li>
    *  <li>untagged displays carrying this mod's old popup texts (pre-1.0.42
    *  builds tagged nothing).</li>
    *  </ul>
    *  Safe to run any time: live popups and mask labels are tracked in memory
    *  and never match. Returns how many were removed. */
   public static int sweepOrphanedDisplays(MinecraftServer server) {
      int removed = 0;
      for (ServerLevel level : server.getAllLevels()) {
         for (Entity e : level.getAllEntities()) {
            if (!(e instanceof TextDisplay td) || td.isRemoved()) {
               continue;
            }
            if (isTmpDisplay(td)) {
               // Live popups and mask labels are tracked in memory; anything
               // tmp-tagged but untracked is a leftover from a crash/restart or
               // a mask wearer who logged out mid-glow.
               boolean tracked = procLabelExpiry.containsKey(td.getUUID())
                  || com.fortuneandfavors.ModEvents.isTrackedMaskLabel(td.getUUID());
               if (!tracked) {
                  td.discard();
                  removed++;
               }
            } else if (td.entityTags().contains(ModItems.DISPLAY_TAG)) {
               // Tagged but not tmp: a grave label. Keep it only while the
               // player-head skull it labels still exists.
               if (!aboveSkull(level, td)) {
                  td.discard();
                  removed++;
               }
            } else if (looksLikeLegacyPopup(level, td)) {
               td.discard();
               removed++;
            }
         }
      }
      if (removed > 0) {
         com.fortuneandfavors.FortuneFavorsMod.LOGGER.info("[FF] Discarded {} stale floating-text display(s) from the world", removed);
      }
      return removed;
   }

   /** Called from the server tick: removes expired proc labels. */
   public static void tickProcLabels(MinecraftServer server) {
      if (procLabelExpiry.isEmpty()) {
         return;
      }
      long now = server.getTickCount();
      procLabelExpiry.entrySet().removeIf(e -> {
         if (now >= e.getValue()) {
            for (ServerLevel level : server.getAllLevels()) {
               Entity ent = level.getEntity(e.getKey());
               if (ent != null) {
                  ent.discard();
                  break;
               }
            }
            return true;
         }
         return false;
      });
   }

   /** Discards every outstanding proc label and clears its bookkeeping. Called when
    *  the server stops so lingering popups never carry over (or save) into a newly
    *  loaded world. */
   public static void clearProcLabels(MinecraftServer server) {
      if (procLabelExpiry.isEmpty()) {
         return;
      }
      procLabelExpiry.keySet().forEach(uuid -> {
         for (ServerLevel level : server.getAllLevels()) {
            Entity ent = level.getEntity(uuid);
            if (ent != null) {
               ent.discard();
               break;
            }
         }
      });
      procLabelExpiry.clear();
      procPopupCooldown.clear();
   }
}
