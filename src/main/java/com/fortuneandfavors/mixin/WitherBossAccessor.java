package com.fortuneandfavors.mixin;

import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes the Wither's own boss bar so the rework can retitle/recolour it
 *  (purple for the King fight) without reflecting over private fields. */
@Mixin(WitherBoss.class)
public interface WitherBossAccessor {
   @Accessor("bossEvent")
   ServerBossEvent fortuneandfavors$bossEvent();
}
