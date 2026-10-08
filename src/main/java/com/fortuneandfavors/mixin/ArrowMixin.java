package com.fortuneandfavors.mixin;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.component.CustomData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractArrow.class)
public class ArrowMixin {
   private static final String FRIENDLY_ARROW_OWNER_KEY = "ff_friendly_arrow_owner";

   @Inject(method = "canHitEntity", at = @At("HEAD"), cancellable = true)
   private void fortuneandfavors$friendlyArrowSkipsOwner(Entity target, CallbackInfoReturnable<Boolean> cir) {
      AbstractArrow arrow = ((AbstractArrow)(Object)this);

      try {
         CustomData data = (CustomData)arrow.get(DataComponents.CUSTOM_DATA);
         if (data == null) {
            return;
         }

         String owner = data.copyTag().getString("ff_friendly_arrow_owner").orElse("");
         if (!owner.isEmpty() && target != null && owner.equals(target.getUUID().toString())) {
            cir.setReturnValue(false);
         }
      } catch (Exception var6) {
      }
   }
}
