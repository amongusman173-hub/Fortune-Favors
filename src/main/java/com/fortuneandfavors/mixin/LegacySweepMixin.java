package com.fortuneandfavors.mixin;

import com.fortuneandfavors.ModItems;
import com.fortuneandfavors.duel.DuelManager;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.List;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Player.class)
public abstract class LegacySweepMixin {
   // The old mixin suppressed sweep by forcing getAttackStrengthScale(0.5F) to return 0 during
   // legacy duels - but that same scale also gates the main-hit bonus damage, which caused the
   // infamous 1-dmg bare-hand punch. The +100 attack-speed modifier already makes the scale 1.0,
   // so let it run naturally and instead suppress the sweep by emptying the entity list the
   // sweep attack would hit. The Distant Memory sword never sweeps either - no sweep damage at all.
   @WrapOperation(
      method = "doSweepAttack",
      at = @At(
         value = "INVOKE",
         target = "Lnet/minecraft/world/level/Level;getEntitiesOfClass(Ljava/lang/Class;Lnet/minecraft/world/phys/AABB;)Ljava/util/List;"
      )
   )
   private List<LivingEntity> fortuneandfavors$noSweepTargetsInLegacy(Level level, Class<LivingEntity> entityClass, AABB box, Operation<List<LivingEntity>> original) {
      Player self = (Player)(Object)this;
      return DuelManager.isLegacyFight(self) || ModItems.isDistantMemorySword(self.getMainHandItem()) ? List.of() : original.call(level, entityClass, box);
   }
}
