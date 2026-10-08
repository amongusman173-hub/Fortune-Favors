package com.fortuneandfavors.duel;

import com.fortuneandfavors.util.Safe;
import com.mojang.authlib.GameProfile;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction.Axis;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.level.block.BarrierBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * A duel opponent that is a real {@link ServerPlayer} with no client behind it.
 *
 * <p>It is simulated like one. A player is ticked by the server every tick
 * whether or not they are doing anything - fire burns them, potion effects run
 * down and heal or poison, air runs out, food is spent, and vanilla physics
 * carries whatever shoves them - and this bot runs that same tick. What it does
 * not do is the half of a player that only exists because a client is connected:
 * there is nobody to steer it, so the duel's AI does that, and nobody to send
 * packets to, so anything the tick sends goes into an in-memory channel that
 * nobody reads.
 */
public class DuelBot extends ServerPlayer {
   /**
    * Whether the bot is off solid ground. It is not a fall simulation - vanilla
    * owns the falling, the gravity and the landing now, the same as it does for a
    * player - it is the duel asking "has this bot left the bridge", so that the
    * arena's floor of no return can be applied and the AI knows not to try to walk
    * in mid-air.
    */
   public boolean falling;

   /**
    * True when this body is a training dummy rather than an opponent.
    *
    * <p>A dummy is the same body with none of the fight in it: it never moves, it never swings and
    * it never runs out of health, so every blow a player lands reads as damage without ever ending
    * the practice. The duel's AI stands down for it (see DuelManager.tickBot) and this flag is
    * what keeps the body itself honest - no knockback, no drift, and a bar that cannot be emptied.
    */
   public boolean trainingDummy;

   public DuelBot(MinecraftServer server, ServerLevel level, GameProfile profile) {
      super(server, level, profile, ClientInformation.createDefault());
      this.getAbilities().flying = false;
      this.getAbilities().mayfly = false;
      this.getAbilities().instabuild = false;
      this.getAbilities().invulnerable = false;
      this.connection = new net.minecraft.server.network.ServerGamePacketListenerImpl(
         server, new net.minecraft.network.Connection(PacketFlow.SERVERBOUND), this, CommonListenerCookie.createInitial(profile, false)
      );
   }

   /**
    * Puts the bot's feet where the AI wants them, without erasing physics.
    *
    * <p>The AI steers and vanilla moves. While the bot is standing on something
    * the AI owns its altitude - that is how it walks up a step, stands on a bridge
    * it has just placed, or keeps its feet on the arena floor - but the moment it
    * is airborne the altitude belongs to whatever put it there: a sprint-jump, a
    * riptide launch, a mace smash, a knockback. Setting the Y by hand every tick is
    * exactly what made all four of those impossible.
    *
    * <p>What it does not do is teleport. This used to be a bare {@code setPos}, so
    * a bot could end a tick on the far side of an arena wall it had never walked
    * around - for the world, it simply was not there any more. The step is clipped
    * against block collisions first, then retried a block up (a step) and finally
    * one axis at a time (a slide along the wall), which is the short version of
    * what vanilla does when a player walks into something.
    *
    * @param groundY the floor the AI believes is under it
    */
   public void steer(double x, double z, double groundY) {
      boolean physicsOwnsAltitude = !this.onGround() || this.getDeltaMovement().y > 0.05;
      // And a surface is only a floor if it is within a step of the feet. Anything a
      // whole block up is a hill the bot jumps onto, not a height it teleports to -
      // handing this the surface of the block ahead is how a bot used to climb the
      // arena's own wall without ever leaving the ground.
      double targetY = physicsOwnsAltitude || groundY > this.getY() + FF_STEP_HEIGHT ? this.getY() : groundY;
      double dx = x - this.getX();
      double dz = z - this.getZ();
      if (dx * dx + dz * dz < 1.0E-6) {
         this.setPos(this.getX(), targetY, this.getZ());
         return;
      }

      // The straightforward step, when nothing is in the way.
      if (!this.ffBlocked(dx, targetY, dz)) {
         this.setPos(this.getX() + dx, targetY, this.getZ() + dz);
         return;
      }

      // A rise the bot can walk up the way a player walks onto a slab. The ceiling
      // is a player's own step height, not a whole block: this used to lift the bot
      // a full block whenever the space above the obstacle was clear, and the
      // arena's one-block wall is exactly that shape - so a push against the ring
      // wall ended with the bot standing on top of it, outside its own arena.
      if (this.onGround() && !this.ffBlocked(dx, targetY + FF_STEP_HEIGHT, dz)) {
         BlockPos under = BlockPos.containing(this.getX() + dx, targetY + FF_STEP_HEIGHT - 0.1, this.getZ() + dz);
         BlockState state = this.level().getBlockState(under);
         double top = state.getCollisionShape(this.level(), under).max(Axis.Y);
         double surface = under.getY() + top;
         if (top > 0.0 && surface > targetY && surface - targetY <= FF_STEP_HEIGHT + 1.0E-4) {
            this.setPos(this.getX() + dx, surface, this.getZ() + dz);
            return;
         }
      }

      // Still blocked: a wall, or a rise taller than a step. Sliding along it one
      // axis at a time is what vanilla collision resolution does; a full block of
      // rise is what a player clears with a jump, so that is what the bot does -
      // and only a jump, because the arc belongs to the physics, not to the AI.
      if (!this.ffBlocked(dx, targetY, 0.0)) {
         this.setPos(this.getX() + dx, targetY, this.getZ());
      } else if (!this.ffBlocked(0.0, targetY, dz)) {
         this.setPos(this.getX(), targetY, this.getZ() + dz);
      } else {
         this.setPos(this.getX(), targetY, this.getZ());
         if (this.onGround() && !this.ffBlocked(dx, targetY + 1.0, dz)) {
            double len = Math.max(1.0E-4, Math.sqrt(dx * dx + dz * dz));
            this.jumpFromGround();
            Vec3 v = this.getDeltaMovement();
            this.setDeltaMovement(v.x + dx / len * 0.19, v.y, v.z + dz / len * 0.19);
         }
      }
   }

   /**
    * A player's own step height. Anything taller is a jump.
    *
    * <p>It is written out here rather than read off the entity because the bot
    * needs it as a hard number: {@code getMaxUpStep()} is a value the mod's own
    * movement features could raise, and this is the number that decides whether
    * the bot walked up something or climbed it.
    */
   private static final double FF_STEP_HEIGHT = 0.6;

   /**
    * True when the bot's own body, moved by (dx, dy, dz), would end up inside a
    * block.
    *
    * <p>Blocks only - not entities. The bot has to be able to walk into the space
    * the player is standing in (that is how it melees), but the arena's walls have
    * to be walls: teleporting to the far side of one is exactly what "the bots have
    * no collision" was.
    */
   private boolean ffBlocked(double dx, double dy, double dz) {
      AABB box = this.getBoundingBox().move(dx, dy, dz);
      BlockPos min = BlockPos.containing(box.minX + 1.0E-4, box.minY + 1.0E-4, box.minZ + 1.0E-4);      BlockPos max = BlockPos.containing(box.maxX - 1.0E-4, box.maxY - 1.0E-4, box.maxZ - 1.0E-4);

      for (BlockPos p : BlockPos.betweenClosed(min, max)) {
         BlockState state = this.level().getBlockState(p);
         // A barrier is asked for by name as well as by shape. The duel realm rings
         // every arena with them, they render as nothing, and a bot that treats one
         // as air walks out of the map through a wall nobody can see.
         if (state.getBlock() instanceof BarrierBlock || !state.getCollisionShape(this.level(), p).isEmpty()) {
            return true;
         }
      }

      return false;
   }

   /**
    * The real player tick, so the bot's body behaves like a body.
    *
    * <p>{@link ServerPlayer#doTick} is where a player is actually simulated - the
    * body half of it, the one a real player's connection drives - and this used to
    * run none of it. It counted down two timers by hand and nothing else, which
    * left the bot only half alive: a Slowness with thirty ticks left on it never
    * expired, so one hit slowed a bot for the rest of the duel, and the
    * Regeneration from a golden apple never healed a single heart because nothing
    * was ticking effects at all. Fire did not burn it. Air did not run out. And
    * nothing integrated the velocity anything shoved it with, which is why it
    * could not be knocked back.
    *
    * <p>{@code LivingEntity.travel} now does that last part properly, and it comes
    * with the rest: gravity, friction, collision and landing - the same physics the
    * player across the arena is running.
    */
   @Override
   public void tick() {
      Safe.run("duel bot tick", () -> super.doTick());
      // A duel bot never flies, never builds and is never invulnerable: the kit
      // and the damage gate decide what it can do, not vanilla's defaults.
      this.setInvulnerable(false);
      this.getAbilities().invulnerable = false;
      this.getAbilities().flying = false;
      this.getAbilities().mayfly = false;
      this.getAbilities().instabuild = false;
      if (this.trainingDummy) {
         // No AI, no shove and no such thing as a killing blow: the dummy holds its spot and its
         // full bar, so a player can practise a combo for an hour without it ever ending.
         this.setDeltaMovement(Vec3.ZERO);
         this.hurtTime = 0;
         if (this.getHealth() < this.getMaxHealth()) {
            this.setHealth(this.getMaxHealth());
         }
      }
   }

   /**
    * The duel owns death.
    *
    * <p>Last-stand lives, Totems of Undying, Bedwars beds and Skywars' void all
    * decide what a killing blow means, and the duel tick is what acts on that
    * decision. Vanilla player death would do neither: it would scatter the bot's
    * kit across the arena floor, broadcast a death message for a name that was
    * never on the server, and leave a corpse waiting to respawn. So the body dies
    * - the hurt animation, the death state - and nothing else does.
    */
   @Override
   public void die(DamageSource source) {
   }
}
