package com.fortuneandfavors.economy;

import com.fortuneandfavors.ModItems;
import com.fortuneandfavors.util.Chat;
import com.fortuneandfavors.util.Safe;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * The Void Shaper's three legendaries.
 *
 * <ul>
 *   <li><b>Void Reaver</b> - every third landed hit rips the block you are
 *       looking at out of the world and hurls it, using the boss's own
 *       "the block decides the blow" table.</li>
 *   <li><b>Colossus Plate</b> - worn, it grants Resistance and a real knockback
 *       resistance modifier, added and removed with the armour itself.</li>
 *   <li><b>Shaping Sigil</b> - right-click the ground to rip that block up and
 *       throw it where you are looking.</li>
 * </ul>
 *
 * <p>Nothing here can destroy or duplicate a block: the terrain block is only
 * ever <em>read</em>, and what flies is a display entity that is discarded on
 * impact. The world is never written to.
 */
public final class VoidShaperGear {

   private static final int HITS_PER_THROW = 3;
   private static final double REACH = 9.0;
   private static final long SIGIL_COOLDOWN_TICKS = 40L;
   private static final int THROW_COOLDOWN_TICKS = 20;
   /** Taking hold of a block is cheaper than throwing one, but not free: without
    *  this, spamming right-click could thrash a hold on and off every tick. */
   private static final long GRIP_COOLDOWN_TICKS = 8L;
   /**
    * How far the Sigil will reach out to find a mark for a throw.
    *
    * <p>Throwing "wherever you are looking" is the reason the item read as a
    * building tool with a combat animation: against anything that moves, a block
    * thrown dead along the crosshair arrives behind the target. The Sigil now
    * looks for a body in front of the throw and leads it, which is the difference
    * between a siege weapon and a toy.
    */
   private static final double AIM_RANGE = 26.0;
   private static final double AIM_CONE = 0.90;

   private static final Map<UUID, Long> NEXT_GRIP = new HashMap<>();

   /** Our own knockback-resistance modifier, so we can remove exactly ours. */
   private static final net.minecraft.resources.Identifier PLATE_KB_ID =
      net.minecraft.resources.Identifier.fromNamespaceAndPath("fortuneandfavors", "colossus_plate_knockback");
   private static final AttributeModifier PLATE_KB =
      new AttributeModifier(PLATE_KB_ID, 0.5, AttributeModifier.Operation.ADD_VALUE);

   private static final Map<UUID, Integer> HITS = new HashMap<>();
   private static final Map<UUID, Long> NEXT_THROW = new HashMap<>();
   private static final Map<UUID, Long> SIGIL_UNTIL = new HashMap<>();
   private static final Map<UUID, Boolean> PLATED = new HashMap<>();

   private VoidShaperGear() {
   }

   // ------------------------------------------------------------------ void reaver

   /**
    * Called from the damage hook when the Void Reaver lands a hit. Every third
    * landed hit rips a block out of the ground around the TARGET and throws it at
    * them.
    *
    * <p>This used to throw whatever the wielder was looking at, which in a fight
    * is the enemy and never terrain - so the blade's whole gimmick was silently
    * dead the entire time you were actually using it. It now finds its own
    * ammunition next to the victim.
    */
   public static void onReaverHit(ServerPlayer attacker, LivingEntity victim) {
      if (attacker == null || victim == null || !(attacker.level() instanceof ServerLevel level)) {
         return;
      }
      int hits = HITS.merge(attacker.getUUID(), 1, Integer::sum);
      if (hits < HITS_PER_THROW) {
         return;
      }
      long now = level.getGameTime();
      Long next = NEXT_THROW.get(attacker.getUUID());
      if (next != null && now < next) {
         return;
      }
      HITS.put(attacker.getUUID(), 0);
      BlockPos ammo = blockNear(level, victim.blockPosition(), 3);
      if (ammo == null) {
         // Nothing to tear up underfoot - fall back to the crosshair.
         if (!throwLookedAtBlock(attacker, "Void Reaver")) {
            return;
         }
      } else {
         Vec3 dir = victim.getEyePosition().subtract(Vec3.atCenterOf(ammo));
         if (!VoidShaperManager.hurlBlock(level, attacker, ammo, dir)) {
            return;
         }
      }
      NEXT_THROW.put(attacker.getUUID(), now + THROW_COOLDOWN_TICKS);
      attacker.sendOverlayMessage(Component.literal(Chat.colorize("&5Void Reaver &7- &fblock hurled")));
   }

   /** The nearest grabbable block to a point, searched outward in rings. */
   private static BlockPos blockNear(ServerLevel level, BlockPos centre, int radius) {
      for (int r = 0; r <= radius; r++) {
         for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
               if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                  continue;
               }
               for (int dy = 0; dy >= -2; dy--) {
                  BlockPos p = centre.offset(dx, dy, dz);
                  if (VoidShaperManager.isGrabbable(level, p)) {
                     return p;
                  }
               }
            }
         }
      }
      return null;
   }

   // ------------------------------------------------------------------ shaping sigil

   /**
    * Right-click with the Sigil: GRIP the block you are looking at, so it hovers
    * in front of you. Sneak-right-click hurls whatever you are holding.
    *
    * <p>The old version did both in one click, which read as the tool throwing
    * blocks at you: you would right-click a wall to look at it and a block would
    * fly off across the room. Gripping and throwing are separate actions now, and
    * a gripped block can simply be swapped out or let go.
    */
   public static String useSigil(ServerPlayer player, ItemStack held) {
      if (!(player.level() instanceof ServerLevel level)) {
         return null;
      }
      long now = level.getGameTime();
      UUID gripping = VoidShaperManager.heldBy(player);

      if (player.isShiftKeyDown()) {
         // Throw what you hold - or grip and throw in one motion if empty-handed.
         Long until = SIGIL_UNTIL.get(player.getUUID());
         if (until != null && now < until) {
            bar(player, "&7The Sigil is still recovering &8(" + ((until - now + 19) / 20) + "s)");
            return null;
         }
         UUID displayId = gripping;
         if (displayId == null) {
            BlockPos pos = lookedAtBlock(level, player, REACH);
            displayId = pos == null ? null : VoidShaperManager.liftBlock(level, player, pos);
            if (displayId == null) {
               bar(player, "&7There is nothing solid to shape there.");
               return null;
            }
         }
         // Led at whatever is in front of the throw, so a hurled block chases the
         // body it was aimed at instead of the air they were standing in.
         Vec3 aim = aimDir(level, player);
         if (!VoidShaperManager.hurlLifted(level, player, displayId, aim)) {
            bar(player, "&7The grip slipped - nothing was thrown.");
            return null;
         }
         SIGIL_UNTIL.put(player.getUUID(), now + SIGIL_COOLDOWN_TICKS);
         bar(player, aimed(level, player) ? "&5Shaping Sigil &7- &fblock hurled &8(at a mark)" : "&5Shaping Sigil &7- &fblock hurled");
         return null;
      }

      // Plain right-click: take hold of a block.
      Long gripUntil = NEXT_GRIP.get(player.getUUID());
      if (gripUntil != null && now < gripUntil) {
         bar(player, "&7The Sigil is still closing on the last block.");
         return null;
      }
      BlockPos pos = lookedAtBlock(level, player, REACH);
      if (pos == null) {
         bar(player, "&7There is nothing solid to shape there.");
         return null;
      }
      if (gripping != null) {
         VoidShaperManager.dropLifted(level.getServer(), gripping, true);
      }
      UUID displayId = VoidShaperManager.liftBlock(level, player, pos);
      if (displayId == null) {
         bar(player, "&7That block cannot be shaped.");
         return null;
      }
      NEXT_GRIP.put(player.getUUID(), now + GRIP_COOLDOWN_TICKS);
      bar(player, "&5Gripped &7- &fsneak-right-click &7to hurl it");
      return null;
   }

   /**
    * Where a throw should be aimed: at the body in front of the crosshair, led by
    * its own speed, or straight down the view ray when there is nobody there.
    *
    * <p>The lead is the simple version - time-to-target at the block's own throw
    * speed, times the target's current velocity - which is enough to hit a running
    * player and never enough to hit one who steps behind cover.
    */
   private static Vec3 aimDir(ServerLevel level, ServerPlayer player) {
      LivingEntity mark = markOf(level, player);
      Vec3 view = player.getViewVector(1.0F).normalize();
      if (mark == null) {
         return view;
      }
      Vec3 at = new Vec3(mark.getX(), mark.getY() + mark.getBbHeight() * 0.5, mark.getZ());
      Vec3 to = at.subtract(player.getEyePosition());
      double distance = to.length();
      if (distance < 1.0E-3) {
         return view;
      }
      double speed = 1.25;
      double lead = Math.min(1.4, distance / (speed * 20.0));
      Vec3 aim = at.add(mark.getDeltaMovement().scale(lead)).subtract(player.getEyePosition());
      return aim.lengthSqr() < 1.0E-6 ? view : aim.normalize();
   }

   /** True when the crosshair is on a body - used only for the throw's feedback. */
   private static boolean aimed(ServerLevel level, ServerPlayer player) {
      return markOf(level, player) != null;
   }

   /** The nearest living thing inside the throw's cone, or null. */
   private static LivingEntity markOf(ServerLevel level, ServerPlayer player) {
      try {
         Vec3 eye = player.getEyePosition();
         Vec3 view = player.getViewVector(1.0F).normalize();
         LivingEntity best = null;
         double bestScore = Double.MAX_VALUE;
         for (LivingEntity living : level.getEntitiesOfClass(
            LivingEntity.class,
            player.getBoundingBox().inflate(AIM_RANGE),
            e -> e.isAlive()
               && e != player
               && !(e instanceof ServerPlayer other && (other.isCreative() || other.isSpectator()))
         )) {
            Vec3 to = new Vec3(living.getX(), living.getY() + living.getBbHeight() * 0.5, living.getZ()).subtract(eye);
            double distance = to.length();
            if (distance > AIM_RANGE || distance < 0.4) {
               continue;
            }
            double dot = view.dot(to.scale(1.0 / distance));
            if (dot < AIM_CONE) {
               continue;
            }
            // Closest to the crosshair wins, then closest overall.
            double score = (1.0 - dot) * 20.0 + distance;
            if (score < bestScore) {
               bestScore = score;
               best = living;
            }
         }
         return best;
      } catch (Throwable t) {
         return null;
      }
   }

   /** Shared: read the block under the crosshair and launch it forward. */
   private static boolean throwLookedAtBlock(ServerPlayer player, String source) {
      if (!(player.level() instanceof ServerLevel level)) {
         return false;
      }
      long now = level.getGameTime();
      Long next = NEXT_THROW.get(player.getUUID());
      if (next != null && now < next) {
         return false;
      }
      BlockPos pos = lookedAtBlock(level, player, REACH);
      if (pos == null) {
         return false;
      }
      Vec3 dir = aimDir(level, player);
      if (!VoidShaperManager.hurlBlock(level, player, pos, dir)) {
         return false;
      }
      NEXT_THROW.put(player.getUUID(), now + THROW_COOLDOWN_TICKS);
      level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.DEEPSLATE_BREAK, SoundSource.PLAYERS, 1.0F, 1.0F);
      return true;
   }

   /** The first solid, non-block-entity block along the view ray. */
   private static BlockPos lookedAtBlock(ServerLevel level, ServerPlayer player, double reach) {
      Vec3 eye = player.getEyePosition();
      Vec3 dir = player.getViewVector(1.0F).normalize();
      for (double d = 1.0; d <= reach; d += 0.25) {
         Vec3 at = eye.add(dir.scale(d));
         BlockPos pos = BlockPos.containing(at);
         var state = level.getBlockState(pos);
         if (state.isAir() || !state.getFluidState().isEmpty()) {
            continue;
         }
         if (!state.isSolidRender() || state.hasBlockEntity()) {
            continue;
         }
         return pos;
      }
      return null;
   }

   // ------------------------------------------------------------------ colossus plate

   public static void tick(MinecraftServer server) {
      if (server == null) {
         return;
      }
      // Held blocks hover in front of their owner and time out on their own.
      Safe.run("void shaper sigil holds", () -> VoidShaperManager.tickLifted(server));
      for (ServerPlayer p : server.getPlayerList().getPlayers()) {
         Safe.run("void shaper gear tick", () -> tickPlate(p));
      }
   }

   /**
    * The Plate's own armour: Resistance while worn, plus a knockback-resistance
    * modifier that is added when the chestplate goes on and removed when it comes
    * off. Managing the modifier by identity is what stops it stacking up every
    * tick or leaking onto the player after the armour is gone.
    */
   private static void tickPlate(ServerPlayer player) {
      boolean worn = player.isAlive() && ModItems.isColossusPlate(player.getItemBySlot(EquipmentSlot.CHEST));
      AttributeInstance attr = player.getAttribute(Attributes.KNOCKBACK_RESISTANCE);
      boolean has = attr != null && attr.getModifier(PLATE_KB_ID) != null;

      if (worn) {
         player.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 60, 0, false, false, true));
         if (attr != null && !has) {
            attr.addTransientModifier(PLATE_KB);
            PLATED.put(player.getUUID(), true);
         }
         return;
      }
      if (attr != null && has) {
         attr.removeModifier(PLATE_KB_ID);
      }
      PLATED.remove(player.getUUID());
   }

   public static void onPlayerDisconnect(UUID id) {
      HITS.remove(id);
      NEXT_THROW.remove(id);
      SIGIL_UNTIL.remove(id);
      NEXT_GRIP.remove(id);
      PLATED.remove(id);
   }

   /** Drops whatever the player was gripping so no display is orphaned. */
   public static void onPlayerLeave(MinecraftServer server, ServerPlayer player) {
      onPlayerDisconnect(player.getUUID());
      Safe.run("void shaper sigil release", () -> VoidShaperManager.forgetLifted(server, player.getUUID()));
   }

   public static void clear() {
      HITS.clear();
      NEXT_THROW.clear();
      SIGIL_UNTIL.clear();
      NEXT_GRIP.clear();
      PLATED.clear();
   }

   private static void bar(ServerPlayer player, String text) {
      player.sendOverlayMessage(Component.literal(Chat.colorize(text)));
   }

   /** Test hook: hits needed between throws. */
   public static int hitsPerThrow() {
      return HITS_PER_THROW;
   }
}
