package com.fortuneandfavors.mixin;

import com.fortuneandfavors.economy.BossManager;
import java.util.UUID;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.throwableitemprojectile.Snowball;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.HitResult.Type;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Snowball.class)
public class SnowballMixin {
   private static final String OWNER_KEY = "ff_launcher_owner";
   private static final String BOUNCES_KEY = "ff_launcher_bounces";
   private static final String SPIKE_KEY = "ff_snow_spike";
   private static final String MIST_KEY = "ff_ice_mist";
   private static final String MIST_TIER_KEY = "ff_ice_mist_tier";
   private static final String MIST_OWNER_KEY = "ff_ice_mist_owner";
   private static final String ORB_KEY = "ff_snow_orb";
   private static final String BOULDER_KEY = "ff_boulder_baby";
   private static final String BOULDER_OWNER_KEY = "ff_boulder_owner";

   @Inject(method = "onHit", at = @At("HEAD"), cancellable = true)
   private void ffLauncherBall(HitResult result, CallbackInfo ci) {
      Snowball ball = ((Snowball)(Object)this);

      try {
         CustomData data = (CustomData)ball.get(DataComponents.CUSTOM_DATA);
         if (data == null) {
            return;
         }

         CompoundTag tag = data.copyTag();
         String spike = tag.getString("ff_snow_spike").orElse("");
         String mist = tag.getString("ff_ice_mist").orElse("");
         String orb = tag.getString("ff_snow_orb").orElse("");
         String boulder = tag.getString("ff_boulder_baby").orElse("");
         if (!spike.isEmpty() || !mist.isEmpty() || !orb.isEmpty() || !boulder.isEmpty()) {
            ci.cancel();
            if (!boulder.isEmpty()) {
               if (ball.level() instanceof ServerLevel level) {
                  BossManager.shatterBoulderBaby(level, ball.getX(), ball.getY(), ball.getZ(), tag.getString("ff_boulder_owner").orElse(""));
               }

               ball.discard();
            } else if (!orb.isEmpty()) {
               ffFrostOrb(result, ball, tag);
            } else {
               ffSnowBall(result, ball, tag, !mist.isEmpty());
            }

            return;
         }

         String ownerStr = tag.getString("ff_launcher_owner").orElse("");
         if (ownerStr.isEmpty()) {
            return;
         }

         ci.cancel();
         if (!(ball.level() instanceof ServerLevel level)) {
            return;
         }

         ServerPlayer owner = null;

         try {
            owner = level.getServer().getPlayerList().getPlayer(UUID.fromString(ownerStr));
         } catch (Exception var21) {
         }

         if (result.getType() == Type.ENTITY) {
            EntityHitResult ehr = (EntityHitResult)result;
            LivingEntity hit = ehr.getEntity() instanceof LivingEntity le ? le : null;
            if (hit != null && hit != owner && hit.isAlive()) {
               int tier = tag.getInt("ff_launcher_tier").orElse(1);
               float dmg = 16.0F + (Math.max(1, tier) - 1) * 4.0F;
               // Every bounce it has already taken makes it hit a quarter harder: bank shots pay.
               dmg *= 1.0F + 0.25F * tag.getInt("ff_launcher_banked").orElse(0);
               if (hit instanceof ServerPlayer) {
                  dmg *= 0.3F;
               }

               hit.hurtServer(level, level.damageSources().playerAttack(owner), dmg);
               Vec3 k = hit.position().subtract(ball.position()).normalize();
               hit.setDeltaMovement(hit.getDeltaMovement().add(k.x * 0.18, 0.06, k.z * 0.18));
               hit.hurtMarked = true;
               int sticky = tag.getInt("ff_launcher_sticky").orElse(0);
               hit.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 40 + Math.max(0, sticky) * 60, 1, false, false));
               com.fortuneandfavors.net.FfVfx.shape(level, com.fortuneandfavors.net.FfVfx.GOO_SPLASH, ParticleTypes.ITEM_SLIME, hit.position().add(0.0, 1.0, 0.0), Vec3.ZERO, 0.9, 0.0, 0x6FE36A);
               level.playSound(null, hit.getX(), hit.getY(), hit.getZ(), SoundEvents.SLIME_SQUISH, SoundSource.PLAYERS, 1.0F, 1.2F);
            }

            ball.discard();
         } else if (result.getType() == Type.BLOCK) {
            BlockHitResult bhr = (BlockHitResult)result;
            int bounces = Integer.parseInt(tag.getStringOr("ff_launcher_bounces", "0"));
            if (bounces > 0) {
               Direction dir = bhr.getDirection();
               Vec3 normal = Vec3.atLowerCornerOf(dir.getUnitVec3i());
               Vec3 vel = ball.getDeltaMovement();
               double dot = vel.dot(normal);
               Vec3 reflected = vel.subtract(normal.scale(2.0 * dot)).scale(0.9);
               ball.setPos(ball.getX() + normal.x * 0.2, ball.getY() + normal.y * 0.2, ball.getZ() + normal.z * 0.2);
               ball.setDeltaMovement(reflected);
               tag.putString("ff_launcher_bounces", String.valueOf(bounces - 1));
               tag.putInt("ff_launcher_banked", tag.getInt("ff_launcher_banked").orElse(0) + 1);
               int gen = tag.getInt("ff_launcher_gen").orElse(1);
               tag.putInt("ff_launcher_gen", gen + 1);
               ball.setComponent(DataComponents.CUSTOM_DATA, CustomData.of(tag));
               // The first bounce splits off a second ball at an angle (the child never splits again).
               if (gen == 0) {
                  Snowball child = new Snowball(net.minecraft.world.entity.EntityTypes.SNOWBALL, level);
                  child.setItem(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.SLIME_BALL));
                  child.setPos(ball.getX(), ball.getY(), ball.getZ());
                  child.setDeltaMovement(reflected.yRot((float)Math.toRadians(15.0)));
                  CompoundTag childTag = tag.copy();
                  childTag.putString("ff_launcher_bounces", "2");
                  childTag.putInt("ff_launcher_gen", 9);
                  child.setComponent(DataComponents.CUSTOM_DATA, CustomData.of(childTag));
                  level.addFreshEntity(child);
               }
               com.fortuneandfavors.net.FfVfx.shape(level, com.fortuneandfavors.net.FfVfx.GOO_SPLASH, ParticleTypes.ITEM_SLIME, bhr.getLocation(), Vec3.ZERO, 0.6, 0.0, 0x6FE36A);
               level.playSound(null, bhr.getLocation().x, bhr.getLocation().y, bhr.getLocation().z, SoundEvents.SLIME_SQUISH, SoundSource.PLAYERS, 0.6F, 1.5F);
            } else {
               ball.discard();
            }
         }
      } catch (Exception var22) {
      }
   }

   private static void ffSnowBall(HitResult result, Snowball ball, CompoundTag tag, boolean mistBall) {
      if (ball.level() instanceof ServerLevel level) {
         if (result.getType() == Type.ENTITY) {
            EntityHitResult ehr = (EntityHitResult)result;
            if (ehr.getEntity() instanceof LivingEntity hit && hit.isAlive() && !BossManager.isFriendlySkeleton(hit)) {
               float dmg = tag.getFloat("ff_snow_spike_dmg").orElse(4.0F);
               if (mistBall) {
                  int tier = Math.max(1, tag.getInt("ff_ice_mist_tier").orElse(1));
                  dmg = 4.0F + tier * 1.5F;
               }

               hit.hurtServer(level, level.damageSources().magic(), dmg);
               if (mistBall) {
                  BossManager.createIceMist(
                     level,
                     ball.getX(),
                     ball.getY() + 0.5,
                     ball.getZ(),
                     2.8 + (Math.max(1, tag.getInt("ff_ice_mist_tier").orElse(1)) - 1) * 0.4,
                     90 + Math.max(0, tag.getInt("ff_ice_mist_tier").orElse(1) - 1) * 15,
                     1.2 + Math.max(0, tag.getInt("ff_ice_mist_tier").orElse(1) - 1) * 0.4,
                     parseOwner(tag.getString("ff_ice_mist_owner").orElse(""))
                  );
                  applyFreeze(level, hit, 10 + (Math.max(1, tag.getInt("ff_ice_mist_tier").orElse(1)) - 1) * 5);
               } else {
                  int freeze = tag.getInt("ff_snow_spike_freeze").orElse(12);
                  applyFreeze(level, hit, freeze);
                  hit.setDeltaMovement(hit.getDeltaMovement().x, 0.22, hit.getDeltaMovement().z);
                  hit.hurtMarked = true;
               }

               com.fortuneandfavors.net.FfVfx.shape(level, com.fortuneandfavors.net.FfVfx.ICE_BURST, ParticleTypes.SNOWFLAKE, hit.position().add(0.0, hit.getBbHeight() * 0.5, 0.0), net.minecraft.world.phys.Vec3.ZERO, 0.7, 0.0, 0xBFEFFF);
               com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.SNOWFLAKE, hit.getX(), hit.getY() + 1.0, hit.getZ(), 12, 0.4, 0.4, 0.4, 0.04);
               com.fortuneandfavors.net.FfVfx.particles(level, new DustParticleOptions(-1, 1.2F), hit.getX(), hit.getY() + 1.0, hit.getZ(), 8, 0.35, 0.35, 0.35, 0.02);
               level.playSound(null, hit.getX(), hit.getY(), hit.getZ(), SoundEvents.GLASS_BREAK, SoundSource.HOSTILE, 0.7F, 1.3F);
            }
         } else if (mistBall) {
            BossManager.createIceMist(
               level,
               ball.getX(),
               ball.getY() + 0.5,
               ball.getZ(),
               2.8 + (Math.max(1, tag.getInt("ff_ice_mist_tier").orElse(1)) - 1) * 0.4,
               90 + Math.max(0, tag.getInt("ff_ice_mist_tier").orElse(1) - 1) * 15,
               1.2 + Math.max(0, tag.getInt("ff_ice_mist_tier").orElse(1) - 1) * 0.4,
               parseOwner(tag.getString("ff_ice_mist_owner").orElse(""))
            );
            com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.SNOWFLAKE, ball.getX(), ball.getY() + 0.5, ball.getZ(), 18, 0.8, 0.5, 0.8, 0.05);
            com.fortuneandfavors.net.FfVfx.particles(level, new DustParticleOptions(-1509121, 1.1F), ball.getX(), ball.getY() + 0.5, ball.getZ(), 10, 0.6, 0.4, 0.6, 0.02);
         }

         ball.discard();
      }
   }

   private static void ffFrostOrb(HitResult result, Snowball ball, CompoundTag tag) {
      if (ball.level() instanceof ServerLevel level) {
         float var9 = tag.getFloat("ff_snow_orb_dmg").orElse(4.0F);
         int freeze = tag.getInt("ff_snow_orb_freeze").orElse(10);
         if (result.getType() == Type.ENTITY) {
            EntityHitResult ehr = (EntityHitResult)result;
            if (ehr.getEntity() instanceof LivingEntity hit && hit.isAlive() && !BossManager.isFriendlySkeleton(hit) && !BossManager.isSnowServant(hit)) {
               hit.hurtServer(level, level.damageSources().magic(), var9);
               applyFreeze(level, hit, freeze);
            }
         }

         com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.SNOWFLAKE, ball.getX(), ball.getY(), ball.getZ(), 16, 0.5, 0.5, 0.5, 0.05);
         com.fortuneandfavors.net.FfVfx.particles(level, new DustParticleOptions(-2296577, 1.2F), ball.getX(), ball.getY(), ball.getZ(), 10, 0.4, 0.4, 0.4, 0.02);
         level.playSound(null, ball.getX(), ball.getY(), ball.getZ(), SoundEvents.GLASS_BREAK, SoundSource.HOSTILE, 0.7F, 1.4F);
         ball.discard();
      }
   }

   private static void applyFreeze(ServerLevel level, LivingEntity target, int amount) {
      BossManager.freezeBuildup(level, target, amount);
   }

   private static UUID parseOwner(String s) {
      if (s != null && !s.isEmpty()) {
         try {
            return UUID.fromString(s);
         } catch (Exception ignored) {
            return null;
         }
      } else {
         return null;
      }
   }
}
