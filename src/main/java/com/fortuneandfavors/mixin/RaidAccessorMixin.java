package com.fortuneandfavors.mixin;

import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.world.entity.raid.Raid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Raid.class)
public interface RaidAccessorMixin {
   @Accessor("raidEvent")
   ServerBossEvent fortuneandfavors$raidEvent();
}
