package com.fortuneandfavors.economy;

import com.fortuneandfavors.FortuneFavorsMod;
import com.fortuneandfavors.ModItems;
import com.fortuneandfavors.net.FfNet;
import com.fortuneandfavors.net.FfScreenFxPayload;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Server-side behavior for custom enchantments that need state or entity hooks. */
public final class AdvancedEnchantments {
   public static final String PARRY = CustomEnchantments.PARRY;
   public static final String COUNTER = CustomEnchantments.COUNTER;
   public static final String SOULBIND = CustomEnchantments.SOULBIND;
   public static final String POINT_BLANK = CustomEnchantments.POINT_BLANK;
   public static final String DEADEYE = CustomEnchantments.DEADEYE;
   public static final String CRAB_CLAW = CustomEnchantments.CRAB_CLAW;
   public static final String CURSE_UNDYING = CustomEnchantments.CURSE_UNDYING;
   public static final String FLARE = CustomEnchantments.FLARE;
   public static final String GUARD = CustomEnchantments.GUARD;
   public static final String HANDYMAN = CustomEnchantments.HANDYMAN;
   public static final String HUNGER_ASPECT = CustomEnchantments.HUNGER_ASPECT;
   public static final String MOONWALK = CustomEnchantments.MOONWALK;
   public static final String OCEAN_HEART = CustomEnchantments.OCEAN_HEART;
   public static final String REJUVENATION = CustomEnchantments.REJUVENATION;
   public static final String SAFE_LANDING = CustomEnchantments.SAFE_LANDING;
   public static final String VELOCITY = CustomEnchantments.VELOCITY;

   private static final String PARRY_MARK = "ff_parried_projectile";
   private static final String VELOCITY_MARK = "ff_velocity_applied";
   private static final String NO_FALLOFF_TAG = "ff_no_falloff";
   private static final String POINT_BLANK_MARK = "ff_point_blank_level";
   private static final String FLARE_MARK = "ff_flare_projectile";
   private static final String DEADEYE_MARK = "ff_deadeye_projectile";
   private static final String DEADEYE_TARGET_TAG = "ff_deadeye_target";
   private static final String DEAD_EYE_ARMED = "ff_deadeye_armed";
   private static final String COUNTER_READY_REMAINING = "ff_counter_ready_remaining";
   private static final String COUNTER_CD_REMAINING = "ff_counter_cd_remaining";
   private static final String COUNTER_LEVEL_TAG = "ff_counter_level";
   private static final String DEADEYE_STILL_TAG = "ff_deadeye_still";
   private static final String DEADEYE_CD_REMAINING = "ff_deadeye_cd_remaining";
   private static final long COUNTER_COOLDOWN = 300L;
   private static final long COUNTER_WINDOW = 60L;
   private static final long DEADEYE_CHARGE = 60L;
   private static final long DEADEYE_COOLDOWN = 200L;
   private static final Map<UUID, Long> counterReadyUntil = new HashMap<>();
   private static final Map<UUID, Long> counterCooldownUntil = new HashMap<>();
   private static final Map<UUID, Integer> counterLevels = new HashMap<>();
   private static final Map<UUID, List<ItemStack>> soulboundPending = new HashMap<>();
   private static final Map<UUID, Integer> deadeyeStillTicks = new HashMap<>();
   private static final Map<UUID, Vec3> deadeyePositions = new HashMap<>();
   private static final Map<UUID, Long> deadeyeCooldownUntil = new HashMap<>();

   private AdvancedEnchantments() {
   }

   public static boolean has(ItemStack stack, String key) {
      return CustomEnchantments.has(stack, key);
   }

   public static boolean isProjectileWeapon(ItemStack stack) {
      return stack != null && !stack.isEmpty()
         && (stack.is(ItemTags.BOW_ENCHANTABLE) || stack.is(ItemTags.CROSSBOW_ENCHANTABLE) || stack.is(Items.TRIDENT));
   }

   private static boolean isBowOrCrossbow(ItemStack stack) {
      return stack != null && !stack.isEmpty() && (stack.is(ItemTags.BOW_ENCHANTABLE) || stack.is(ItemTags.CROSSBOW_ENCHANTABLE));
   }

   public static boolean isAdvancedKey(String key) {
      return switch (key) {
         case CustomEnchantments.QUICK_CHARGE, PARRY, COUNTER, SOULBIND, POINT_BLANK, DEADEYE,
            CRAB_CLAW, CURSE_UNDYING, FLARE, GUARD, HANDYMAN, HUNGER_ASPECT, MOONWALK,
            OCEAN_HEART, REJUVENATION, SAFE_LANDING, VELOCITY -> true;
         default -> false;
      };
   }

   public static boolean canApply(String key, ItemStack stack) {
      if (stack == null || stack.isEmpty() || stack.is(Items.ENCHANTED_BOOK)) {
         return false;
      }
      return switch (key) {
         case CustomEnchantments.QUICK_CHARGE -> stack.is(ItemTags.CROSSBOW_ENCHANTABLE);
         case PARRY -> stack.is(Items.SHIELD);
         case COUNTER -> stack.is(Items.SHIELD);
         case POINT_BLANK, VELOCITY -> isProjectileWeapon(stack);
         case FLARE -> isBowOrCrossbow(stack) && !hasInfinity(stack);
         case DEADEYE -> stack.is(ItemTags.CROSSBOW_ENCHANTABLE);
         case CRAB_CLAW, SOULBIND -> true;
         case CURSE_UNDYING -> stack.isDamageableItem();
         case GUARD -> stack.is(ItemTags.SWORDS) || stack.is(ItemTags.AXES) || stack.is(Items.MACE);
         case MOONWALK, SAFE_LANDING -> stack.is(Items.ELYTRA);
         case OCEAN_HEART -> stack.is(ItemTags.CHEST_ARMOR);
         case REJUVENATION -> stack.is(ItemTags.CHEST_ARMOR) && !hasProtection(stack);
         case HANDYMAN -> stack.isDamageableItem() && !hasMending(stack);
         default -> ModItems.isEnchantableWeapon(stack);
      };
   }

   public static boolean hasProtection(ItemStack stack) {
      return hasVanilla(stack, net.minecraft.world.item.enchantment.Enchantments.PROTECTION)
         || hasVanilla(stack, net.minecraft.world.item.enchantment.Enchantments.FIRE_PROTECTION)
         || hasVanilla(stack, net.minecraft.world.item.enchantment.Enchantments.BLAST_PROTECTION)
         || hasVanilla(stack, net.minecraft.world.item.enchantment.Enchantments.PROJECTILE_PROTECTION);
   }

   public static boolean hasMending(ItemStack stack) {
      return hasVanilla(stack, net.minecraft.world.item.enchantment.Enchantments.MENDING);
   }

   public static boolean hasInfinity(ItemStack stack) {
      return hasVanilla(stack, net.minecraft.world.item.enchantment.Enchantments.INFINITY);
   }

   private static boolean hasVanilla(ItemStack stack, net.minecraft.resources.ResourceKey<Enchantment> key) {
      if (stack == null || stack.isEmpty()) {
         return false;
      }
      ItemEnchantments enchantments = stack.get(DataComponents.ENCHANTMENTS);
      if (enchantments == null) {
         return false;
      }
      for (Holder<Enchantment> holder : enchantments.keySet()) {
         if (holder.is(key) && enchantments.getLevel(holder) > 0) {
            return true;
         }
      }
      return false;
   }

   public static boolean isShield(ItemStack stack) {
      return stack != null && !stack.isEmpty() && stack.is(Items.SHIELD);
   }

   public static ItemStack activeShield(ServerPlayer player) {
      ItemStack main = player.getMainHandItem();
      if (isShield(main) && player.isBlocking() && player.getUseItem() == main) {
         return main;
      }
      ItemStack off = player.getOffhandItem();
      return isShield(off) && player.isBlocking() && player.getUseItem() == off ? off : ItemStack.EMPTY;
   }

   /** Returns true when the player is holding a shield that is actively blocking. */
   public static boolean isBlockingWithShield(ServerPlayer player) {
      return !activeShield(player).isEmpty();
   }

   /** Perfect-block projectile parry and Counter charge. */
   public static boolean handleShieldBlock(ServerPlayer victim, DamageSource source) {
      // Runs before vanilla's block, which used to swallow the hit before the perfect-guard check ever saw it.
      if (tryPerfectGuard(victim, source)) {
         return true;
      }
      if (!isBlockingWithShield(victim)) {
         return false;
      }
      ItemStack shield = activeShield(victim);
      long now = victim.level().getGameTime();
      if (source.is(net.minecraft.tags.DamageTypeTags.BYPASSES_SHIELD) || !isInFront(victim, source)) {
         return false;
      }
      int counter = CustomEnchantments.levelOf(shield, COUNTER);
      if (counter > 0 && now >= counterCooldownUntil.getOrDefault(victim.getUUID(), 0L)) {
         counterReadyUntil.put(victim.getUUID(), now + COUNTER_WINDOW);
         counterCooldownUntil.put(victim.getUUID(), now + COUNTER_COOLDOWN);
         counterLevels.put(victim.getUUID(), counter);
      }
      if (has(shield, PARRY) && source.getDirectEntity() instanceof LivingEntity && victim.level() instanceof ServerLevel level) {
         // A melee blow caught on a Parry shield: the golden clash (vanilla still does the blocking).
         Vec3 look = victim.getLookAngle().normalize();
         com.fortuneandfavors.net.FfVfx.shape(level, com.fortuneandfavors.net.FfVfx.CLASH, net.minecraft.core.particles.ParticleTypes.CRIT, victim.getEyePosition().add(look.scale(0.9)).add(0.0, -0.3, 0.0), look, 0.0, 0.0, 0xFFD24A);
         level.playSound(null, victim.blockPosition(), SoundEvents.ANVIL_LAND, SoundSource.PLAYERS, 0.35F, 1.9F);
      }
      if (!has(shield, PARRY) || !(source.getDirectEntity() instanceof Projectile projectile)) {
         return false;
      }
      if (projectile.getCustomName() != null) {
         // Named projectiles are still parriable; this branch intentionally does nothing.
      }
      reflectProjectile(victim, projectile);
      return true;
   }

   private static boolean isInFront(Player player, DamageSource source) {
      Vec3 sourcePosition = source.getSourcePosition();
      if (sourcePosition == null) {
         return true;
      }
      Vec3 look = player.getLookAngle();
      Vec3 toSource = sourcePosition.subtract(player.position());
      double lookLength = Math.sqrt(look.x * look.x + look.z * look.z);
      double sourceLength = Math.sqrt(toSource.x * toSource.x + toSource.z * toSource.z);
      return lookLength < 1.0E-4D || sourceLength < 1.0E-4D
         || (look.x * toSource.x + look.z * toSource.z) / (lookLength * sourceLength) >= 0.2D;
   }

   /** How many ticks after raising a Guard weapon a melee hit still counts as a perfect block. */
   public static final int PERFECT_GUARD_TICKS = 2;

   /**
    * Guard's perfect block: a melee blow caught in the first instant of raising the weapon is
    * negated outright, and the attacker is thrown back off-balance - Parry's timing, for blades.
    */
   public static boolean tryPerfectGuard(Player player, DamageSource source) {
      if (!isGuardBlocking(player, source) || player.getTicksUsingItem() > PERFECT_GUARD_TICKS
         || !(source.getEntity() instanceof LivingEntity attacker) || source.getDirectEntity() != attacker) {
         return false;
      }
      Vec3 away = attacker.position().subtract(player.position()).normalize();
      attacker.setDeltaMovement(away.x * 0.9, 0.35, away.z * 0.9);
      attacker.hurtMarked = true;
      attacker.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.SLOWNESS, 40, 1));
      attacker.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.WEAKNESS, 40, 0));
      if (player.level() instanceof ServerLevel level) {
         Vec3 look = player.getLookAngle().normalize();
         com.fortuneandfavors.net.FfVfx.shape(level, com.fortuneandfavors.net.FfVfx.CLASH, net.minecraft.core.particles.ParticleTypes.CRIT, player.getEyePosition().add(look.scale(0.9)).add(0.0, -0.3, 0.0), look, 0.0, 0.0, 0xFFD24A);
         level.playSound(null, player.blockPosition(), SoundEvents.SHIELD_BLOCK.value(), SoundSource.PLAYERS, 1.2F, 1.7F);
         level.playSound(null, player.blockPosition(), SoundEvents.ANVIL_LAND, SoundSource.PLAYERS, 0.4F, 1.9F);
      }
      return true;
   }

   public static boolean isGuardBlocking(Player player, DamageSource source) {
      if (player == null || !player.isBlocking()) {
         return false;
      }
      ItemStack used = player.getUseItem();
      return has(used, GUARD) && !source.is(net.minecraft.tags.DamageTypeTags.IS_PROJECTILE)
         && !source.is(net.minecraft.tags.DamageTypeTags.BYPASSES_SHIELD) && isInFront(player, source);
   }

   public static boolean conflictsWithVanillaBook(ItemStack target, ItemStack book) {
      ItemEnchantments additions = book == null ? null : book.get(DataComponents.STORED_ENCHANTMENTS);
      if (additions == null || additions.isEmpty()) {
         return false;
      }
      for (Holder<Enchantment> holder : additions.keySet()) {
         if (holder.is(net.minecraft.world.item.enchantment.Enchantments.MENDING) && has(target, HANDYMAN)) {
            return true;
         }
         if (holder.is(net.minecraft.world.item.enchantment.Enchantments.INFINITY) && has(target, FLARE)) {
            return true;
         }
         if (hasProtection(target) && holder.is(net.minecraft.world.item.enchantment.Enchantments.PROTECTION)
            || has(target, REJUVENATION) && (holder.is(net.minecraft.world.item.enchantment.Enchantments.PROTECTION)
               || holder.is(net.minecraft.world.item.enchantment.Enchantments.FIRE_PROTECTION)
               || holder.is(net.minecraft.world.item.enchantment.Enchantments.BLAST_PROTECTION)
               || holder.is(net.minecraft.world.item.enchantment.Enchantments.PROJECTILE_PROTECTION))) {
            return true;
         }
      }
      return false;
   }

   public static boolean tryParryProjectile(Projectile projectile, LivingEntity target) {
      if (!(target instanceof ServerPlayer victim) || !isBlockingWithShield(victim) || !has(activeShield(victim), PARRY)) {
         return false;
      }
      net.minecraft.world.item.component.CustomData data = projectile.get(DataComponents.CUSTOM_DATA);
      if (data != null && data.copyTag().getBoolean(PARRY_MARK).orElse(false)) {
         // The original collision already reflected this projectile in the
         // damage callback; cancel the rest of that same collision once.
         return true;
      }
      Vec3 delta = projectile.position().subtract(victim.getEyePosition());
      Vec3 look = victim.getLookAngle().normalize();
      if (delta.lengthSqr() > 1.0E-6D && look.dot(delta.normalize()) < 0.2D) {
         return false;
      }
      reflectProjectile(victim, projectile);
      return true;
   }

   private static void reflectProjectile(ServerPlayer player, Projectile projectile) {
      Vec3 direction = player.getLookAngle().normalize();
      double speed = Math.max(0.7D, projectile.getDeltaMovement().length());
      projectile.setOwner(player);
      projectile.setPos(player.getX() + direction.x * 1.2D, player.getEyeY() + direction.y * 1.2D, player.getZ() + direction.z * 1.2D);
      projectile.setDeltaMovement(direction.scale(speed));
      projectile.hurtMarked = true;
      CompoundTag tag = projectile.getOrDefault(DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.EMPTY).copyTag();
      tag.putBoolean(PARRY_MARK, true);
      projectile.setComponent(DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(tag));
      if (player.level() instanceof ServerLevel level) {
         com.fortuneandfavors.net.FfVfx.shape(level, com.fortuneandfavors.net.FfVfx.CLASH, net.minecraft.core.particles.ParticleTypes.CRIT, player.getEyePosition().add(direction.scale(1.0)), direction, 0.0, 0.0, 0xFFD24A);
         com.fortuneandfavors.net.FfVfx.enter();
         try {
            com.fortuneandfavors.net.FfVfx.particles(level, net.minecraft.core.particles.ParticleTypes.CRIT, player.getX(), player.getEyeY(), player.getZ(), 12, 0.3, 0.3, 0.3, 0.15);
         } finally {
            com.fortuneandfavors.net.FfVfx.exit();
         }
         level.playSound(null, player.blockPosition(), SoundEvents.SHIELD_BLOCK.value(), SoundSource.PLAYERS, 1.0F, 1.35F);
      }
   }

   /** Returns the next melee multiplier and consumes a valid Counter window. */
   public static float counterMultiplier(ServerPlayer attacker, DamageSource source) {
      if (source.getDirectEntity() instanceof Projectile || source.getEntity() != attacker) {
         return 1.0F;
      }
      long now = attacker.level().getGameTime();
      long expires = counterReadyUntil.getOrDefault(attacker.getUUID(), 0L);
      if (expires <= now) {
         counterReadyUntil.remove(attacker.getUUID());
         return 1.0F;
      }
      int level = counterLevels.getOrDefault(attacker.getUUID(), 0);
      if (level <= 0) {
         counterReadyUntil.remove(attacker.getUUID());
         counterLevels.remove(attacker.getUUID());
         return 1.0F;
      }
      counterReadyUntil.remove(attacker.getUUID());
      counterLevels.remove(attacker.getUUID());
      return 1.0F + level * 0.25F;
   }

   /** Applies projectile tags when a player-owned projectile first ticks. */
   public static void markProjectileFromOwner(Projectile projectile) {
      if (!(projectile.getOwner() instanceof ServerPlayer owner)) {
         return;
      }
      net.minecraft.world.item.component.CustomData existing = projectile.get(DataComponents.CUSTOM_DATA);
      CompoundTag tag = existing == null ? new CompoundTag() : existing.copyTag();
      boolean parried = tag.getBoolean(PARRY_MARK).orElse(false);
      if (parried) {
         tag.remove(PARRY_MARK);
      }
      ItemStack weapon = projectile instanceof net.minecraft.world.entity.projectile.arrow.AbstractArrow arrow && !arrow.getWeaponItem().isEmpty()
         ? arrow.getWeaponItem()
         : projectileWeapon(owner);
      if (parried) {
         projectile.setComponent(DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(tag));
         return;
      }
      if (!isProjectileWeapon(weapon)) {
         return;
      }
      if (!(projectile instanceof net.minecraft.world.entity.projectile.arrow.AbstractArrow) && !weapon.is(Items.TRIDENT)) {
         return;
      }
      int velocity = CustomEnchantments.levelOf(weapon, VELOCITY);
      int pointBlank = CustomEnchantments.levelOf(weapon, POINT_BLANK);
      boolean flare = has(weapon, FLARE);
      if (velocity > 0 && !tag.getBoolean(VELOCITY_MARK).orElse(false)) {
         projectile.setDeltaMovement(projectile.getDeltaMovement().scale(2.0D));
         tag.putBoolean(VELOCITY_MARK, true);
      }
      // Velocity and Sniper arrows fly straight - no gravity falloff (the
      // ArrowEnchantMixin reads this flag plus the weapon directly).
      if (velocity > 0 || CCEnchantments.isSniperCrossbow(weapon)) {
         tag.putBoolean(NO_FALLOFF_TAG, true);
      }
      if (pointBlank > 0) {
         tag.putInt(POINT_BLANK_MARK, pointBlank);
      }
      if (flare) {
         tag.putBoolean(FLARE_MARK, true);
      }
      if (has(weapon, DEADEYE) && ownerHasDeadeyeArmed(owner)) {
         tag.putBoolean(DEADEYE_MARK, true);
         setDeadeyeArmed(owner, false);
         deadeyeCooldownUntil.put(owner.getUUID(), owner.level().getGameTime() + DEADEYE_COOLDOWN);
      }
      projectile.setComponent(DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(tag));
   }

   private static ItemStack projectileWeapon(ServerPlayer owner) {
      ItemStack main = owner.getMainHandItem();
      if (isProjectileWeapon(main)) {
         return main;
      }
      return isProjectileWeapon(owner.getOffhandItem()) ? owner.getOffhandItem() : ItemStack.EMPTY;
   }

   private static boolean ownerHasDeadeyeArmed(ServerPlayer owner) {
      net.minecraft.world.item.component.CustomData data = owner.get(DataComponents.CUSTOM_DATA);
      return data != null && data.copyTag().getBoolean(DEAD_EYE_ARMED).orElse(false);
   }

   private static void setDeadeyeArmed(ServerPlayer owner, boolean armed) {
      CompoundTag tag = owner.getOrDefault(DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.EMPTY).copyTag();
      tag.putBoolean(DEAD_EYE_ARMED, armed);
      owner.setComponent(DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(tag));
   }

   public static int projectilePointBlankLevel(Projectile projectile) {
      net.minecraft.world.item.component.CustomData data = projectile.get(DataComponents.CUSTOM_DATA);
      return data == null ? 0 : data.copyTag().getInt(POINT_BLANK_MARK).orElse(0);
   }

   public static boolean projectileHasFlare(Projectile projectile) {
      net.minecraft.world.item.component.CustomData data = projectile.get(DataComponents.CUSTOM_DATA);
      return data != null && data.copyTag().getBoolean(FLARE_MARK).orElse(false);
   }

   /** Homes a charged Deadeye projectile toward the best nearby living target.
    *  The locked target is remembered on the projectile so the arrow never
    *  oscillates between candidates and strays past its mark. */
   public static void tickDeadeyeProjectile(Projectile projectile) {
      net.minecraft.world.item.component.CustomData data = projectile.get(DataComponents.CUSTOM_DATA);
      if (data == null || !data.copyTag().getBoolean(DEADEYE_MARK).orElse(false) || !(projectile.level() instanceof ServerLevel level)) {
         return;
      }
      LivingEntity owner = projectile.getOwner() instanceof LivingEntity living ? living : null;
      CompoundTag tag = data.copyTag();
      String targetId = tag.getString(DEADEYE_TARGET_TAG).orElse("");
      LivingEntity best = null;
      if (!targetId.isEmpty()) {
         for (LivingEntity candidate : level.getEntitiesOfClass(
            LivingEntity.class, projectile.getBoundingBox().inflate(96.0D),
            entity -> entity.isAlive() && entity.getUUID().toString().equals(targetId)
         )) {
            best = candidate;
            break;
         }
      }
      if (best == null) {
         AABB search = projectile.getBoundingBox().inflate(96.0D);
         double bestScore = Double.MAX_VALUE;
         Vec3 travel = projectile.getDeltaMovement().normalize();
         for (LivingEntity candidate : level.getEntitiesOfClass(LivingEntity.class, search, entity -> entity.isAlive() && entity != owner)) {
            Vec3 to = candidate.getEyePosition().subtract(projectile.position());
            double distance = to.length();
            if (distance < 1.0D) {
               continue;
            }
            double alignment = travel.dot(to.normalize());
            double score = distance + Math.max(0.0D, 0.5D - alignment) * 24.0D;
            if (score < bestScore) {
               bestScore = score;
               best = candidate;
            }
         }
         if (best != null) {
            tag.putString(DEADEYE_TARGET_TAG, best.getUUID().toString());
            projectile.setComponent(DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(tag));
         }
      }
      if (best != null) {
         Vec3 toTarget = best.getEyePosition().subtract(projectile.position()).normalize();
         double speed = Math.max(1.0D, projectile.getDeltaMovement().length());
         projectile.setDeltaMovement(toTarget.scale(speed));
         projectile.hurtMarked = true;
      }
   }

   /** Handles equipped-item attributes and persistent Deadeye charging. */
   public static void tickPlayer(ServerPlayer player) {
      tickDeadeyeCharge(player);
      applyEquipmentAttributes(player);
      applyEquipmentEffects(player);
      restoreSoulbound(player);
      applyGuardComponent(player.getMainHandItem());
      applyGuardComponent(player.getOffhandItem());
   }

   private static void applyGuardComponent(ItemStack stack) {
      if (has(stack, GUARD) && !stack.has(DataComponents.BLOCKS_ATTACKS)) {
         stack.set(DataComponents.BLOCKS_ATTACKS, com.fortuneandfavors.economy.SwordBlockManager.blockComponent());
      }
   }

   private static void tickDeadeyeCharge(ServerPlayer player) {
      ItemStack held = player.getMainHandItem();
      boolean eligible = held.is(ItemTags.CROSSBOW_ENCHANTABLE) && has(held, DEADEYE);
      UUID id = player.getUUID();
      long now = player.level().getGameTime();
      if (!eligible || now < deadeyeCooldownUntil.getOrDefault(id, 0L)) {
         deadeyeStillTicks.remove(id);
         deadeyePositions.remove(id);
         setDeadeyeArmed(player, false);
         return;
      }
      Vec3 pos = player.position();
      Vec3 previous = deadeyePositions.put(id, pos);
      if (previous != null && pos.distanceToSqr(previous) > 0.0025D) {
         deadeyeStillTicks.put(id, 0);
         setDeadeyeArmed(player, false);
         return;
      }
      int still = Math.min((int)DEADEYE_CHARGE, deadeyeStillTicks.getOrDefault(id, 0) + 1);
      deadeyeStillTicks.put(id, still);
      if (still >= DEADEYE_CHARGE) {
         boolean wasArmed = ownerHasDeadeyeArmed(player);
         setDeadeyeArmed(player, true);
         if (!wasArmed) {
            // Deadeye just became READY - subtle red client-side flash so the
            // shooter knows the next shot is a guaranteed hit (rendered only on
            // clients with the mod installed). One-shot: only on the tick the
            // charge completes, not on every armed tick and not at shot time.
            try {
               FfNet.send(player, new FfScreenFxPayload(FfScreenFxPayload.FX_DEADEYE, true));
            } catch (Throwable ignored) {
            }
            deadeyeReadyCue(player);
         }
      }
   }

   /**
    * Whether this tick is the one the Deadeye cue is drawn on.
    *
    * <p>It is drawn on the tick a charge completes and on no other: not on every armed tick, and
    * not at shot time. The gate used to be {@code still % 8 == 0} read from <i>inside</i> the armed
    * branch, where {@code still} is clamped at {@link #DEADEYE_CHARGE} - sixty, which is not a
    * multiple of eight - so the ring and the spark were unreachable, and the cue existed only in
    * the comment that promised it. Split out so what is drawn and when it is drawn can be read, and
    * asserted, on their own.
    */
   public static boolean deadeyeCueDue(boolean wasArmed, int still) {
      return !wasArmed && still >= DEADEYE_CHARGE;
   }

   /** The charge-complete cue itself: a faint ring at the feet, one spark above the head. */
   private static void deadeyeReadyCue(ServerPlayer player) {
      if (!(player.level() instanceof ServerLevel level)) {
         return;
      }
      double y = player.getY() + 0.15;
      for (int i = 0; i < 4; i++) {
         double a = i / 4.0 * Math.PI * 2.0;
         com.fortuneandfavors.net.FfVfx.particles(level, net.minecraft.core.particles.ParticleTypes.END_ROD, player.getX() + Math.cos(a) * 0.5, y, player.getZ() + Math.sin(a) * 0.5, 1, 0.0, 0.0, 0.0, 0.01);
      }
      com.fortuneandfavors.net.FfVfx.particles(level, net.minecraft.core.particles.ParticleTypes.ENCHANT, player.getX(), player.getEyeY() + 0.4, player.getZ(), 1, 0.1, 0.1, 0.1, 0.02);
   }

   private static void applyEquipmentAttributes(ServerPlayer player) {
      int crab = 0;
      int rejuvenation = 0;
      for (ItemStack stack : equippedItems(player)) {
         crab = Math.max(crab, CustomEnchantments.levelOf(stack, CRAB_CLAW));
         rejuvenation = Math.max(rejuvenation, CustomEnchantments.levelOf(stack, REJUVENATION));
      }
      applyInteractionRange(player, crab);
      setModifier(player, Attributes.MAX_HEALTH, "rejuvenation_health", rejuvenation > 0 ? rejuvenation * 4.0D : 0.0D);
      if (rejuvenation > 0 && player.getHealth() > player.getMaxHealth()) {
         player.setHealth(player.getMaxHealth());
      }
   }

   /**
    * The Crab Claw's interaction range, which is the mirror the anticheat cannot afford to
    * be a tick behind.
    *
    * <p>This is the same write {@link #applyEquipmentAttributes} makes every tick, exposed
    * so the reach check can make it <i>before</i> it measures a swing: a player who picks
    * the claw up and swings inside the same tick is reaching with a weapon the server can
    * see, and the honest reading of that is the range the weapon grants rather than the
    * three blocks the attribute was still showing.
    */
   public static void applyInteractionRange(ServerPlayer player, int levels) {
      setModifier(player, Attributes.BLOCK_INTERACTION_RANGE, EquipmentAttributes.BLOCK_RANGE_PATH, levels > 0 ? levels : 0.0D);
      setModifier(player, Attributes.ENTITY_INTERACTION_RANGE, EquipmentAttributes.ENTITY_RANGE_PATH, levels > 0 ? levels : 0.0D);
   }

   /** Re-runs the range half of the equipment mirror for this player, right now. */
   public static void restoreInteractionRange(ServerPlayer player) {
      if (player == null) {
         return;
      }
      int crab = 0;
      for (ItemStack stack : equippedItems(player)) {
         crab = Math.max(crab, CustomEnchantments.levelOf(stack, CRAB_CLAW));
      }
      applyInteractionRange(player, crab);
   }

   private static void setModifier(ServerPlayer player, Holder<net.minecraft.world.entity.ai.attributes.Attribute> attribute, String path, double amount) {
      AttributeInstance instance = player.getAttribute(attribute);
      if (instance == null) {
         return;
      }
      AttributeModifier modifier = instance.getModifier(FortuneFavorsMod.id(path));
      if (amount <= 0.0D) {
         if (modifier != null) {
            instance.removeModifier(modifier);
         }
      } else if (modifier == null) {
         instance.addTransientModifier(new AttributeModifier(FortuneFavorsMod.id(path), amount, AttributeModifier.Operation.ADD_VALUE));
      } else if (modifier.amount() != amount) {
         instance.removeModifier(modifier);
         instance.addTransientModifier(new AttributeModifier(FortuneFavorsMod.id(path), amount, AttributeModifier.Operation.ADD_VALUE));
      }
   }

   private static List<ItemStack> equippedItems(ServerPlayer player) {
      return List.of(
         player.getMainHandItem(), player.getOffhandItem(),
         player.getItemBySlot(EquipmentSlot.HEAD), player.getItemBySlot(EquipmentSlot.CHEST),
         player.getItemBySlot(EquipmentSlot.LEGS), player.getItemBySlot(EquipmentSlot.FEET)
      );
   }

   private static void applyEquipmentEffects(ServerPlayer player) {
      ItemStack chest = player.getItemBySlot(EquipmentSlot.CHEST);
      // Moonwalk is handled by the getEffectiveGravity mixin - vanilla elytra
      // physics overwrites velocity nudges every tick, so gravity itself is cut
      // instead (see LivingEntityMixin).
      int oceanHeart = CustomEnchantments.levelOf(chest, OCEAN_HEART);
      if (oceanHeart > 0 && player.isInWater()) {
         player.addEffect(new MobEffectInstance(MobEffects.CONDUIT_POWER, 40, 0, false, false, true));
      }
   }

   public static boolean isSoulbound(ItemStack stack) {
      return has(stack, SOULBIND);
   }

   public static void captureSoulbound(ServerPlayer player, ItemStack stack) {
      if (!isSoulbound(stack)) {
         return;
      }
      soulboundPending.computeIfAbsent(player.getUUID(), ignored -> new java.util.ArrayList<>()).add(stack.copy());
   }

   /** Number of soulbound items still waiting to be restored for this player. */
   public static int pendingSoulboundCount(UUID id) {
      List<ItemStack> pending = soulboundPending.get(id);
      if (pending == null) {
         return 0;
      }
      int n = 0;
      for (ItemStack stack : pending) {
         if (stack != null && !stack.isEmpty()) {
            n += stack.getCount();
         }
      }
      return n;
   }

   /** Immediately restores every pending soulbound item for the player.
    *  Returns the number of items returned. */
   public static int claimSoulbound(ServerPlayer player) {
      List<ItemStack> pending = soulboundPending.remove(player.getUUID());
      if (pending == null || pending.isEmpty()) {
         return 0;
      }
      int given = 0;
      for (ItemStack stack : pending) {
         if (stack == null || stack.isEmpty()) {
            continue;
         }
         given += stack.getCount();
         if (!player.getInventory().add(stack)) {
            player.drop(stack, false);
         }
      }
      return given;
   }

   private static void restoreSoulbound(ServerPlayer player) {
      int given = claimSoulbound(player);
      if (given > 0) {
         com.fortuneandfavors.util.Chat.raw(player, "§dYour soulbound item" + (given == 1 ? " has" : "s have") + " returned to you.");
      }
   }

   /** Writes the live Counter / Deadeye cooldown state onto the player's custom
    *  data so a logout mid-fight doesn't reset them (see restorePlayerState). */
   public static void persistPlayerState(ServerPlayer player) {
      UUID id = player.getUUID();
      long now = player.level().getGameTime();
      CompoundTag tag = player.getOrDefault(DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.EMPTY).copyTag();
      tag.putLong(COUNTER_READY_REMAINING, Math.max(0L, counterReadyUntil.getOrDefault(id, 0L) - now));
      tag.putLong(COUNTER_CD_REMAINING, Math.max(0L, counterCooldownUntil.getOrDefault(id, 0L) - now));
      tag.putInt(COUNTER_LEVEL_TAG, counterLevels.getOrDefault(id, 0));
      tag.putInt(DEADEYE_STILL_TAG, deadeyeStillTicks.getOrDefault(id, 0));
      tag.putLong(DEADEYE_CD_REMAINING, Math.max(0L, deadeyeCooldownUntil.getOrDefault(id, 0L) - now));
      player.setComponent(DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(tag));
   }

   /** Restores persisted Counter / Deadeye state from the player's custom data. */
   public static void restorePlayerState(ServerPlayer player) {
      UUID id = player.getUUID();
      net.minecraft.world.item.component.CustomData data = player.get(DataComponents.CUSTOM_DATA);
      if (data == null) {
         return;
      }
      CompoundTag tag = data.copyTag();
      long now = player.level().getGameTime();
      long ready = tag.getLong(COUNTER_READY_REMAINING).orElse(0L);
      long cd = tag.getLong(COUNTER_CD_REMAINING).orElse(0L);
      int level = tag.getInt(COUNTER_LEVEL_TAG).orElse(0);
      if (ready > 0L && cd > 0L && level > 0) {
         counterReadyUntil.put(id, now + ready);
         counterCooldownUntil.put(id, now + cd);
         counterLevels.put(id, level);
      }
      int still = tag.getInt(DEADEYE_STILL_TAG).orElse(0);
      long deadeyeCd = tag.getLong(DEADEYE_CD_REMAINING).orElse(0L);
      if (still > 0) {
         deadeyeStillTicks.put(id, still);
      }
      if (deadeyeCd > 0L) {
         deadeyeCooldownUntil.put(id, now + deadeyeCd);
      }
   }

   /** Persists captured soulbound items so they survive a logout before
    *  respawn and a server restart. Entries are removed on restore, so the
    *  file only ever holds items whose owner has not come back yet. */
   public static void save(MinecraftServer server) {
      try {
         net.minecraft.core.HolderLookup.Provider access = server.registryAccess();
         com.google.gson.JsonObject root = new com.google.gson.JsonObject();
         for (Map.Entry<UUID, List<ItemStack>> entry : soulboundPending.entrySet()) {
            com.google.gson.JsonArray items = new com.google.gson.JsonArray();
            for (ItemStack stack : entry.getValue()) {
               if (stack == null || stack.isEmpty()) {
                  continue;
               }
               com.google.gson.JsonElement json = com.fortuneandfavors.util.JsonUtil.itemToJson(stack, access);
               if (json != null) {
                  items.add(json);
               }
            }
            if (items.size() > 0) {
               root.add(entry.getKey().toString(), items);
            }
         }
         java.nio.file.Path file = com.fortuneandfavors.economy.EconomyManager.getDataDir(server).resolve("soulbound_pending.json");
         file.toFile().getParentFile().mkdirs();
         com.fortuneandfavors.util.JsonUtil.write(file, root);
      } catch (Throwable t) {
         FortuneFavorsMod.LOGGER.error("Failed to save soulbound items", t);
      }
   }

   /** Restores captured soulbound items from disk (see {@link #save}). */
   public static void load(MinecraftServer server) {
      try {
         java.nio.file.Path file = com.fortuneandfavors.economy.EconomyManager.getDataDir(server).resolve("soulbound_pending.json");
         com.google.gson.JsonObject root = com.fortuneandfavors.util.JsonUtil.readOrCreate(file, new com.google.gson.JsonObject());
         net.minecraft.core.HolderLookup.Provider access = server.registryAccess();
         soulboundPending.clear();
         for (String key : root.keySet()) {
            try {
               UUID id = UUID.fromString(key);
               com.google.gson.JsonElement element = root.get(key);
               if (element == null || !element.isJsonArray()) {
                  continue;
               }
               List<ItemStack> items = new java.util.ArrayList<>();
               for (com.google.gson.JsonElement itemElement : element.getAsJsonArray()) {
                  ItemStack stack = com.fortuneandfavors.util.JsonUtil.jsonToItem(itemElement, access);
                  if (!stack.isEmpty()) {
                     items.add(stack);
                  }
               }
               if (!items.isEmpty()) {
                  soulboundPending.put(id, items);
               }
            } catch (Throwable ignored) {
               // skip one malformed entry, keep the rest
            }
         }
      } catch (Throwable t) {
         FortuneFavorsMod.LOGGER.error("Failed to load soulbound items", t);
      }
   }

   /** Finds and consumes a Curse of Undying item before lethal player damage. */
   public static boolean tryCurseOfUndying(ServerPlayer player) {
      if (player.isCreative() || player.isSpectator()) {
         return false;
      }
      ItemStack found = findSoulItem(player, CURSE_UNDYING);
      if (found.isEmpty()) {
         return false;
      }
      found.shrink(1);
      player.setHealth(1.0F);
      player.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 900, 1));
      player.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 100, 1));
      player.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 800, 0));
      if (player.level() instanceof ServerLevel level) {
         com.fortuneandfavors.net.FfVfx.particles(level, net.minecraft.core.particles.ParticleTypes.TOTEM_OF_UNDYING, player.getX(), player.getY() + 1.0, player.getZ(), 40, 0.6, 0.9, 0.6, 0.15);
         level.playSound(null, player.blockPosition(), SoundEvents.TOTEM_USE, SoundSource.PLAYERS, 1.0F, 1.0F);
      }
      return true;
   }

   private static ItemStack findSoulItem(ServerPlayer player, String key) {
      for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
         ItemStack stack = player.getInventory().getItem(i);
         if (has(stack, key)) {
            return stack;
         }
      }
      return ItemStack.EMPTY;
   }

   public static int pointBlankBonus(Projectile projectile, LivingEntity target) {
      int level = projectilePointBlankLevel(projectile);
      if (level <= 0 || projectile.getOwner() == null) {
         return 0;
      }
      return projectile.getOwner().distanceTo(target) <= 8.0D ? level : 0;
   }

   public static float modifyProjectileDamage(Projectile projectile, LivingEntity target, float damage) {
      int pointBlank = pointBlankBonus(projectile, target);
      return pointBlank <= 0 ? damage : damage * (1.0F + pointBlank * 0.20F);
   }

   public static void triggerFlare(Projectile projectile, double x, double y, double z) {
      if (!isFlareProjectile(projectile) || projectile.level().isClientSide()) {
         return;
      }
      net.minecraft.world.item.component.CustomData data = projectile.get(DataComponents.CUSTOM_DATA);
      if (data == null || data.copyTag().getBoolean("ff_flare_detonated").orElse(false)) {
         return;
      }
      CompoundTag tag = data.copyTag();
      tag.putBoolean("ff_flare_detonated", true);
      projectile.setComponent(DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(tag));
      if (projectile.level() instanceof ServerLevel level) {
         level.explode(projectile, x, y, z, 1.8F, Level.ExplosionInteraction.NONE);
         com.fortuneandfavors.net.FfVfx.particles(level, net.minecraft.core.particles.ParticleTypes.FIREWORK, x, y, z, 28, 0.35, 0.35, 0.35, 0.12);
         projectile.discard();
      }
   }

   public static boolean isFlareProjectile(Projectile projectile) {
      return projectileHasFlare(projectile);
   }

   public static void tick(MinecraftServer server) {
      // State maps are intentionally session-scoped; stale entries are removed
      // when players are no longer online so long-running servers stay bounded.
      counterReadyUntil.keySet().removeIf(id -> server.getPlayerList().getPlayer(id) == null);
      counterCooldownUntil.keySet().removeIf(id -> server.getPlayerList().getPlayer(id) == null);
      counterLevels.keySet().removeIf(id -> server.getPlayerList().getPlayer(id) == null);
      deadeyeStillTicks.keySet().removeIf(id -> server.getPlayerList().getPlayer(id) == null);
      deadeyePositions.keySet().removeIf(id -> server.getPlayerList().getPlayer(id) == null);
      deadeyeCooldownUntil.keySet().removeIf(id -> server.getPlayerList().getPlayer(id) == null);
   }
}
