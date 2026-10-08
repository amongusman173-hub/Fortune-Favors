package com.fortuneandfavors.mixin;

import net.minecraft.world.level.BaseSpawner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

@Mixin(BaseSpawner.class)
public abstract class BaseSpawnerMixin {
   @Shadow
   private int spawnDelay;
   @Shadow
   private int minSpawnDelay;
   @Shadow
   private int maxSpawnDelay;
   @Shadow
   private int spawnCount;
   @Shadow
   private int spawnRange;

   @Unique
   public void fortuneandfavors$boost(int level) {
      int lvl = Math.max(1, level);
      this.minSpawnDelay = Math.max(40, 200 - 20 * (lvl - 1));
      this.maxSpawnDelay = Math.max(80, 800 - 60 * (lvl - 1));
      this.spawnCount = 4 + 2 * (lvl - 1);
      this.spawnRange = Math.min(16, 4 + (lvl - 1));
      this.spawnDelay = Math.min(this.spawnDelay, Math.max(5, 60 - (lvl - 1) * 5));
   }
}
