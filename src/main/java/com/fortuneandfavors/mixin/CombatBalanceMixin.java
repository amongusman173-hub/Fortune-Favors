package com.fortuneandfavors.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(LivingEntity.class)
public abstract class CombatBalanceMixin {
   // Balance pass that lives in the damage pipeline:
   // - TNT explosions (no direct source entity) deal 50% more damage.
   // - Ender pearl teleport damage is reduced to 60% of vanilla.
   // - Axes used in player-vs-player combat take extra durability damage.
   @WrapOperation(method = "actuallyHurt", at = @At(value = "INVOKE", target = "getDamageAfterArmorAbsorb(Lnet/minecraft/world/damagesource/DamageSource;F)F"))
   private float fortuneandfavors$balanceDamage(LivingEntity instance, DamageSource source, float amount, Operation<Float> original) {
      float dmg = (Float)original.call(new Object[]{instance, source, amount});
      if (source.is(DamageTypes.ENDER_PEARL)) {
         dmg *= 0.6F;
      } else if (source.is(DamageTypes.EXPLOSION) && source.getDirectEntity() == null) {
         dmg *= 1.5F;
      }
      // The Ender Dragon shrugs off the mace: the one weapon whose whole appeal is a single
      // enormous blow is answered with 30% of itself against the reworked boss, because a mace
      // smash skips the three phases, the last stand and the death ceremony in one swing. The
      // reduction is read here, in the damage pipeline, so it also covers the mace's own smash
      // damage (a second event with the same attacker still holding the mace).
      if (instance instanceof net.minecraft.world.entity.boss.enderdragon.EnderDragon && isMaceBlow(source)) {
         dmg *= 0.3F;
      }
      // Every fight in this mod wears one buff, read here rather than at fourteen attack sites:
      // the attacker is one of this mod's bosses, so its blow lands a quarter harder. The
      // multiplier lives in BossEmpowerment with the rest of the layer (aura, surge); this is the
      // only place in the codebase it is applied, which is what stops it being applied twice.
      if (source.getEntity() != null && com.fortuneandfavors.economy.BossEmpowerment.isEmpowered(source.getEntity())) {
         dmg *= (float)com.fortuneandfavors.economy.BossEmpowerment.DAMAGE_MULTIPLIER;
      }
      if (source.getDirectEntity() instanceof Player attacker && instance instanceof Player) {
         ItemStack held = attacker.getMainHandItem();
         if (held.getItem() instanceof AxeItem && held.isDamageableItem() && !held.has(DataComponents.UNBREAKABLE)) {
            held.hurtAndBreak(1, attacker, EquipmentSlot.MAINHAND);
         }
      }
      return dmg;
   }

   /** True for a hit struck with a mace, whichever of the mace's two damage events it is. */
   private static boolean isMaceBlow(DamageSource source) {
      ItemStack weapon = source.getWeaponItem();
      if (weapon != null && weapon.is(Items.MACE)) {
         return true;
      }
      // The smash is applied as its own event, and it does not carry the weapon with it - the
      // attacker is still holding the mace, which is the thing both events have in common.
      return source.getEntity() instanceof LivingEntity attacker && attacker.getMainHandItem().is(Items.MACE);
   }
}
