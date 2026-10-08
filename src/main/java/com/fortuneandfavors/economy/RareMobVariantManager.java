package com.fortuneandfavors.economy;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Rare mob variants: weighted tiers (Armored → Cursed → Elite → Ancient →
 * Mythic) with stat multipliers and rare drops per tier. Variants carry no
 * custom name tag - instead each tier is marked by a small particle aura, and
 * the mob itself stays invisible until it notices a player (sets a player as
 * its target or has one right next to it), so it can ambush rather than
 * announce itself. Particles are cheap and throttled, and only spawned for
 * tracked variants, to keep the server light.
 */
public final class RareMobVariantManager {
   public static final String TIER_KEY = "ff_variant_tier";
   private static final Random RANDOM = new Random();
   private static final Map<UUID, VariantState> ACTIVE = new HashMap<>();

   private static final class VariantState {
      final int tier;
      boolean hidden;

      VariantState(int tier, boolean hidden) {
         this.tier = tier;
         this.hidden = hidden;
      }
   }

   private RareMobVariantManager() {
   }

   public static void apply(Mob mob) {
      try {
         if (mob == null || mob.getType().getCategory() != MobCategory.MONSTER || mob instanceof Monster == false) {
            return;
         }
         // Both tests, because they answer different questions: `isBoss` covers the
         // raids this manager's own class spawns, `isMarkedBoss` covers the six
         // fights that keep their own maps and were never registered with it. Without
         // the second one a raid boss was an eligible variant, and a variant tier
         // below Mythic is hidden on purpose.
         if (BossManager.isBoss(mob) || BossManager.isMarkedBoss(mob) || BossManager.isFriendlySkeleton(mob) || mob.level().isClientSide()) {
            return;
         }
         net.minecraft.world.item.component.CustomData cd = (net.minecraft.world.item.component.CustomData)mob.get(DataComponents.CUSTOM_DATA);
         if (cd != null && cd.copyTag().contains("ff_variant_tier")) {
            return;
         }
         // Much rarer now: only ~6% of mobs become a variant at all, and the
         // top tiers are extremely rare - stops the server being flooded.
         int roll = RANDOM.nextInt(1000);
         int tier;
         if (roll < 940) {
            tier = 0;
         } else if (roll < 985) {
            tier = 1;
         } else if (roll < 997) {
            tier = 2;
         } else if (roll < 999) {
            tier = 3;
         } else if (roll < 1000) {
            tier = 4;
         } else {
            tier = 5;
         }
         if (tier == 0) {
            return;
         }
         applyTier(mob, tier);
         if (tier == 5) {
            FirstEverRecordManager.onMythicSpawn(mob.level().getServer(), mob);
         }
      } catch (Exception ignored) {
      }
   }

   /** Forces a specific variant tier onto a mob (admin/testing). */
   public static void applyTier(Mob mob, int tier) {
      // Admins and other systems call this directly, so the boss check has to live
      // here too - `apply` is not the only door in.
      if (BossManager.isBoss(mob) || BossManager.isMarkedBoss(mob)) {
         return;
      }
      net.minecraft.world.item.component.CustomData cd = (net.minecraft.world.item.component.CustomData)mob.getOrDefault(DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.EMPTY);
      CompoundTag tag = cd.copyTag();
      tag.putInt("ff_variant_tier", tier);
      mob.setComponent(DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(tag));
      double hpMul = switch (tier) {
         case 1 -> 1.6;
         case 2 -> 2.2;
         case 3 -> 3.2;
         case 4 -> 5.0;
         default -> 8.0;
      };
      double dmgMul = switch (tier) {
         case 1 -> 1.3;
         case 2 -> 1.6;
         case 3 -> 2.0;
         case 4 -> 2.8;
         default -> 4.0;
      };
      AttributeInstance hp = mob.getAttribute(Attributes.MAX_HEALTH);
      if (hp != null) {
         hp.setBaseValue(hp.getBaseValue() * hpMul);
      }
      mob.setHealth(mob.getMaxHealth());
      AttributeInstance dmg = mob.getAttribute(Attributes.ATTACK_DAMAGE);
      if (dmg != null) {
         dmg.setBaseValue(dmg.getBaseValue() * dmgMul);
      }
      AttributeInstance armor = mob.getAttribute(Attributes.ARMOR);
      if (armor != null) {
         armor.setBaseValue(armor.getBaseValue() + tier * 2.0);
      }
      if (tier >= 2) {
         mob.addEffect(new MobEffectInstance(MobEffects.SPEED, 999999, Math.min(2, tier - 2), false, false));
      }
      if (tier >= 4) {
         mob.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 999999, 0, false, false));
      }
      if (tier == 1) {
         mob.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
      } else if (tier == 5) {
         mob.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.NETHERITE_HELMET));
         mob.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.NETHERITE_CHESTPLATE));
      }
      // Only high tiers persist - low tiers despawn naturally so they don't flood
      // the world. NEVER glow and NEVER show a name tag.
      if (tier >= 4) {
         mob.setPersistenceRequired();
      }
      if (tier >= 1) {
         // No name tag, no glow. The tier is told by the particle aura, and the
         // mob stays invisible until it notices a player.
         boolean hidden = tier < 5;
         ACTIVE.put(mob.getUUID(), new VariantState(tier, hidden));
         if (hidden) {
            mob.setInvisible(true);
         }
      }
   }

   /**
    * Backfill for variants that already existed when tracking started (e.g. a
    * server restart with hidden variants in the world). Invisible ones get
    * re-registered so they can still reveal and show their particle aura.
    */
   public static void registerIfUntracked(Mob mob) {
      // A boss is never an ambusher. This is also the unsticker: a world saved with a
      // raid boss wearing a variant's invisibility comes back via this path, and
      // refusing to track it here is what puts its body back rather than leaving it
      // as a floating name tag.
      if (BossManager.isBoss(mob) || BossManager.isMarkedBoss(mob)) {
         reveal(mob);
         return;
      }
      if (ACTIVE.containsKey(mob.getUUID())) {
         return;
      }
      int tier = tierOf(mob);
      if (tier <= 0) {
         return;
      }
      ACTIVE.put(mob.getUUID(), new VariantState(tier, mob.isInvisible()));
   }

   /**
    * Releases a mob from the tracker and makes sure it is not left hidden.
    *
    * <p>Called whenever something turns out to be a boss - at summon, and on every
    * upkeep pass - so a hidden state can never outlive the mistake that created it.
    * It clears the variant tag too: the tag is what a restart re-reads, so leaving
    * it in place would rebuild the same hidden ambusher on the next boot.
    */
   public static void forgetBoss(Entity entity) {
      if (entity == null) {
         return;
      }

      ACTIVE.remove(entity.getUUID());
      if (entity instanceof Mob mob) {
         clearVariantTag(mob);
         reveal(mob);
      }
   }

   /** Clears the invisibility flag a hidden variant put on a mob. */
   private static void reveal(Mob mob) {
      if (mob.isInvisible()) {
         mob.setInvisible(false);
      }
   }

   /** Removes the variant marker from a mob's saved data. */
   private static void clearVariantTag(Mob mob) {
      try {
         net.minecraft.world.item.component.CustomData cd = (net.minecraft.world.item.component.CustomData)mob.get(
            DataComponents.CUSTOM_DATA
         );
         if (cd == null) {
            return;
         }
         CompoundTag tag = cd.copyTag();
         if (!tag.contains(TIER_KEY)) {
            return;
         }
         tag.remove(TIER_KEY);
         mob.setComponent(DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(tag));
      } catch (Exception ignored) {
      }
   }

   /** Per-tick upkeep: cheap tier particles + reveal hidden variants once they notice a player. */
   public static void tick(MinecraftServer server) {
      if (ACTIVE.isEmpty()) {
         return;
      }
      Iterator<Map.Entry<UUID, VariantState>> it = ACTIVE.entrySet().iterator();
      while (it.hasNext()) {
         Map.Entry<UUID, VariantState> e = it.next();
         Mob mob = findMob(server, e.getKey());
         if (mob == null) {
            continue; // unloaded chunk - keep tracking, it may come back
         }
         if (!mob.isAlive() || mob.isRemoved()) {
            it.remove();
            continue;
         }
         // A boss that somehow got into the tracker is removed and revealed rather
         // than hidden: the upkeep is the last line of defence for the case where a
         // marker arrived after the tracking did.
         if (BossManager.isBoss(mob) || BossManager.isMarkedBoss(mob)) {
            forgetBoss(mob);
            it.remove();
            continue;
         }
         VariantState state = e.getValue();
         if (mob.level() instanceof ServerLevel sl) {
            if (state.hidden && hasNoticedPlayer(mob)) {
               state.hidden = false;
               mob.setInvisible(false);
            }
            // Only emit particles once the mob has noticed a player (or is
            // revealed) - invisible ambushers shouldn't spam particles.
            if (!state.hidden || hasNoticedPlayer(mob)) {
               spawnTierParticles(sl, mob, state.tier);
            }
         }
      }
   }

   private static Mob findMob(MinecraftServer server, UUID uuid) {
      for (ServerLevel level : server.getAllLevels()) {
         Entity e = level.getEntity(uuid);
         if (e instanceof Mob mob) {
            return mob;
         }
      }
      return null;
   }

   private static boolean hasNoticedPlayer(Mob mob) {
      LivingEntity target = mob.getTarget();
      if (target instanceof Player) {
         return true;
      }
      if (mob.level().isClientSide()) {
         return false;
      }
      return !mob.level()
         .getEntitiesOfClass(Player.class, mob.getBoundingBox().inflate(4.0), p -> p.isAlive() && !p.isSpectator())
         .isEmpty();
   }

   private static void spawnTierParticles(ServerLevel level, Mob mob, int tier) {
      double x = mob.getX() + (RANDOM.nextDouble() - 0.5) * 0.6;
      double y = mob.getY() + mob.getBbHeight() * (0.4 + RANDOM.nextDouble() * 0.5);
      double z = mob.getZ() + (RANDOM.nextDouble() - 0.5) * 0.6;
      switch (tier) {
         case 1 -> level.sendParticles(new DustParticleOptions(0xC7C9CC, 1.0F), x, y, z, 1, 0.0, 0.0, 0.0, 0.0);
         case 2 -> level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, x, y, z, 1, 0.0, 0.0, 0.0, 0.02);
         case 3 -> level.sendParticles(ParticleTypes.ENCHANT, x, y, z, 2, 0.2, 0.3, 0.2, 0.04);
         case 4 -> level.sendParticles(ParticleTypes.PORTAL, x, y, z, 1, 0.15, 0.2, 0.15, 0.02);
         case 5 -> level.sendParticles(ParticleTypes.ELECTRIC_SPARK, x, y, z, 2, 0.15, 0.25, 0.15, 0.05);
         default -> {
         }
      }
   }

   public static int tierOf(Mob mob) {
      net.minecraft.world.item.component.CustomData cd = (net.minecraft.world.item.component.CustomData)mob.get(DataComponents.CUSTOM_DATA);
      if (cd == null) {
         return 0;
      }
      return cd.copyTag().getInt("ff_variant_tier").orElse(0);
   }

   public static String tierName(int tier) {
      return switch (tier) {
         case 1 -> "Armored";
         case 2 -> "Cursed";
         case 3 -> "Elite";
         case 4 -> "Ancient";
         case 5 -> "Mythic";
         default -> "Normal";
      };
   }
}
