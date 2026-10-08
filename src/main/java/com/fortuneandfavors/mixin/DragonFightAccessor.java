package com.fortuneandfavors.mixin;

import java.util.UUID;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.world.level.dimension.end.DragonRespawnStage;
import net.minecraft.world.level.dimension.end.EnderDragonFight;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The dragon's boss bar, reached from the outside.
 *
 * <p>The bar the room watches during this fight is not the dragon's: it is the
 * {@code EnderDragonFight}'s own {@code ServerBossEvent}, and the fight is the only thing that
 * writes to it. There is no public accessor, and dressing the bar - a name and colour per phase, a
 * health that drains smoothly rather than jumping - is exactly the sort of thing that has to touch
 * the event, so the field is reached through an accessor rather than by re-implementing the bar.
 *
 * <p>The fight's own dragon bookkeeping is reached the same way, for one reason: vanilla's
 * {@code setDragonKilled} leaves the dead dragon's uuid behind, and a fight holding a stale uuid
 * will not ask for a new dragon for sixty seconds. The released rite needs to clear that, which is
 * not something a public API offers.
 *
 * <p>Registered as {@code DragonFightAccessor} in {@code fortuneandfavors.mixins.json}; an accessor
 * that is not listed loads as nothing at all, and the bar quietly stays vanilla.
 */
@Mixin(EnderDragonFight.class)
public interface DragonFightAccessor {
   @Accessor("dragonEvent")
   ServerBossEvent fortuneandfavors$dragonEvent();

   @Accessor("dragonUUID")
   UUID fortuneandfavors$dragonUUID();

   @Accessor("dragonUUID")
   void fortuneandfavors$setDragonUUID(UUID id);

   @Accessor("dragonKilled")
   void fortuneandfavors$setDragonKilled(boolean killed);

   /**
    * Whether the fight is between dragons: the flag vanilla writes when a body dies and clears
    * when a new one is asked for.
    *
    * <p>Read, never guessed at, because it is the one piece of this fight's state that is
    * <b>saved</b>. Everything the manager used to remember - whether a rift had opened, whether a
    * rematch was owed - was a field on a class that does not survive leaving the world, so an
    * empty single-player save read as "this fight has never happened" and the summoning played
    * again over a corpse. This flag is in `ender_dragon_fight` in the level's own data.
    */
   @Accessor("dragonKilled")
   boolean fortuneandfavors$dragonKilled();

   @Accessor("respawnStage")
   DragonRespawnStage fortuneandfavors$respawnStage();
}
