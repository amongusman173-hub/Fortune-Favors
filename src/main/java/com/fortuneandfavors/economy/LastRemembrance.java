package com.fortuneandfavors.economy;

import com.fortuneandfavors.ModItems;
import com.fortuneandfavors.duel.DuelManager;
import com.fortuneandfavors.util.Chat;
import java.util.List;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The Distant Memory's greatsword, and the two things a memory can still do.
 *
 * <p><b>Why this item needed a right-click at all.</b> Last Remembrance was built as the one
 * thing in the castle that was ever really there - it comes off the monarch's body rather than
 * out of a chest, which is the whole of its story - and it was armed with Sharpness V, Fire
 * Aspect II and a lore line, and then it did nothing a plain netherite sword does not do. A
 * trophy that is only a stat line is a stat line. So the blade remembers two things, and both of
 * them are things the castle itself did:
 *
 * <ul>
 *   <li><b>Let go</b> - a right-click. The mirage's own rescue, the one promise the place kept
 *       (a fatal blow inside it puts you back at your spawn rather than killing you), performed
 *       by the blade that was standing in the hall when it was made. It goes through
 *       {@link MirageCastleManager#sendToSpawn}, the very call the fatal-blow rule uses, so the
 *       relic and the rescue cannot drift apart.</li>
 *   <li><b>The Court Remembers</b> - a sneak-right-click. One arc of remembered swordwork: the
 *       king's greatsword swings the way it swung when there was still a court to swing for,
 *       once, and then it is only a sword again.</li>
 * </ul>
 *
 * <p><b>One clock, and it takes the longer of the two.</b> Both abilities arm the same cooldown
 * on the stack, and arming it takes the <b>maximum</b> of what is already there and what the new
 * ability wants - a five-minute rescue followed a second later by a twenty-second arc must not
 * shorten the rescue. That is the arithmetic {@code relic.one-clock-per-blade} pins, because
 * "which cooldown wins" is exactly the kind of thing that quietly becomes a free escape.
 *
 * <p><b>Where it refuses, and why.</b> A teleport home is an escape, so the places that already
 * treat leaving as a decision of their own refuse it, with a line saying which: the duel arena,
 * an expedition and the prison. So does a standing Mirage Castle, and so does a castle that is
 * <i>falling</i> - the sixty-second escape window is the event's climax, and a blade handed out
 * by the boss inside that window cannot be the thing that skips it. Everything else is left
 * alone: this is a relic off a boss on a five-minute clock, not a movement ability.
 */
public final class LastRemembrance {
   /** Five minutes between rides home. */
   private static final int LET_GO_TICKS = 6000;
   /** Twenty seconds between remembered arcs. */
   private static final int COURT_TICKS = 400;
   /** How far in front of the holder the remembered arc reaches. */
   private static final double ARC_RANGE = 5.0;
   /** Its damage. Between a netherite sword and a full charge, and it is an arc, not a stab. */
   private static final float ARC_DAMAGE = 12.0F;
   /** The shove it leaves behind - the second half of what "swept off your feet" means. */
   private static final double ARC_PUSH = 1.1;

   private LastRemembrance() {
   }

   /**
    * The click. Returns an error to say out loud, or null when something happened.
    *
    * <p>Sneak picks the arc because a rescue you can trip over is a rescue you will trip over:
    * the escape is the deliberate action.
    */
   public static String use(ServerPlayer player, ItemStack stack) {
      if (player == null || stack == null || player.level().isClientSide()) {
         return null;
      }
      return player.isShiftKeyDown() ? arc(player, stack) : letGo(player, stack);
   }

   // ------------------------------------------------------------------ let go

   /**
    * The mirage's rescue, on a five-minute clock.
    *
    * <p>Health comes back at six hearts rather than full, because that is what the castle's own
    * rescue does - and reusing that call is the point of the method: a relic with its own copy of
    * "put them somewhere safe" is a relic that becomes the exception the day the original moves.
    */
   private static String letGo(ServerPlayer player, ItemStack stack) {
      long now = player.level().getGameTime();
      long left = ModItems.cooldownSecondsLeft(stack, now);
      if (left > 0L) {
         return "The Remembrance is still remembering (" + left + "s).";
      }
      if (DuelManager.isInDuel(player.getUUID())) {
         return "The arena decides when you leave, not a sword.";
      }
      if (ExpeditionManager.isInExpedition(player.getUUID())) {
         return "The run is the run - an expedition ends on its own terms.";
      }
      if (PrisonManager.isInPrison(player)) {
         return "The cellblock has a door, and this is not it.";
      }
      if (MirageCastleManager.isStanding() && MirageCastleManager.inside(player)) {
         // Standing or falling, the castle is the one place this does not work: it handed you
         // the blade, and an escape from inside the thing that is trying to kill you is not
         // something it hands out. The sixty-second window stays a sixty-second window.
         return MirageCastleManager.isCollapsing()
            ? "The mirage is taking itself away - it will not carry you twice."
            : "The mirage will not let go of its own guest.";
      }

      armClock(player, stack, LET_GO_TICKS, now);

      if (player.level() instanceof ServerLevel level) {
         // Where they were, before the jump: a column of the castle's own light left standing in
         // the spot they vanished from, so the place they left is readable to whoever is chasing.
         level.sendParticles(ParticleTypes.REVERSE_PORTAL, player.getX(), player.getY() + 1.0, player.getZ(), 80, 0.5, 1.0, 0.5, -0.25);
         level.sendParticles(ParticleTypes.PORTAL, player.getX(), player.getY() + 1.0, player.getZ(), 120, 0.6, 1.1, 0.6, 0.5);
         level.sendParticles(ParticleTypes.SCULK_SOUL, player.getX(), player.getY() + 1.0, player.getZ(), 20, 0.5, 0.8, 0.5, 0.05);
         level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 1.2F, 0.7F);
         level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1.0F, 0.6F);
      }

      MirageCastleManager.sendToSpawn(player);

      if (player.level() instanceof ServerLevel arrived) {
         arrived.sendParticles(ParticleTypes.PORTAL, player.getX(), player.getY() + 1.0, player.getZ(), 120, 0.6, 1.1, 0.6, 0.5);
         arrived.sendParticles(ParticleTypes.END_ROD, player.getX(), player.getY() + 1.2, player.getZ(), 30, 0.4, 0.8, 0.4, 0.08);
         arrived.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.PLAYER_TELEPORT, SoundSource.PLAYERS, 1.0F, 0.8F);
      }

      Chat.msg(player, "&7&lTHE REMEMBRANCE LETS GO&r &7- the hall is not yours to walk any more, and the blade remembers the way home.");
      return null;
   }

   // ------------------------------------------------------------------ the arc

   /**
    * One arc of remembered swordwork: everything in front, once.
    *
    * <p>Vanilla's own sweep rules, deliberately - a real player-attack damage source, allies
    * skipped through {@code isAlliedTo} (so a tamed wolf and a teammate are safe for the same
    * reason they are safe from the sword itself), and the shove applied after the damage rather
    * than as a second hit. Nothing here reaches through walls: the filter is a box in front, and
    * the box is the range the swing is drawn at.
    */
   private static String arc(ServerPlayer player, ItemStack stack) {
      long now = player.level().getGameTime();
      long left = ModItems.cooldownSecondsLeft(stack, now);
      if (left > 0L) {
         return "The Remembrance is still remembering (" + left + "s).";
      }
      if (!(player.level() instanceof ServerLevel level)) {
         return null;
      }

      Vec3 look = player.getLookAngle();
      Vec3 flat = new Vec3(look.x, 0.0, look.z);
      if (flat.lengthSqr() < 1.0E-6) {
         flat = new Vec3(0.0, 0.0, 1.0);
      }
      Vec3 forward = flat.normalize();
      Vec3 origin = player.position();
      AABB box = new AABB(
         origin.x - ARC_RANGE, origin.y - 2.0, origin.z - ARC_RANGE,
         origin.x + ARC_RANGE, origin.y + 3.0, origin.z + ARC_RANGE
      );

      int struck = 0;
      List<LivingEntity> victims = level.getEntitiesOfClass(LivingEntity.class, box, candidate ->
         candidate != player && candidate.isAlive() && !candidate.isSpectator() && !player.isAlliedTo(candidate) && inArc(origin, forward, candidate)
      );
      for (LivingEntity victim : victims) {
         try {
            if (victim.hurtServer(level, level.damageSources().playerAttack(player), ARC_DAMAGE)) {
               struck++;
            }
            // After the damage, never instead of it: a mob knocked back before it is hit gets its
            // own knockback math and the shove lands on top, which is how one arc becomes two.
            victim.push(forward.x * ARC_PUSH, 0.35, forward.z * ARC_PUSH);
            victim.hurtMarked = true;
         } catch (Throwable ignored) {
         }
      }

      armClock(player, stack, COURT_TICKS, now);

      // The arc itself, drawn the way the swing is drawn: a sweep in front, the blade's own
      // light along it, and the dust the hall used to make.
      double px = origin.x + forward.x * 1.6;
      double pz = origin.z + forward.z * 1.6;
      level.sendParticles(ParticleTypes.SWEEP_ATTACK, px, player.getY() + 1.0, pz, 1, 0.0, 0.0, 0.0, 0.0);
      level.sendParticles(ParticleTypes.ENCHANT, px, player.getY() + 1.2, pz, 24, 1.6, 0.6, 1.6, 0.35);
      level.sendParticles(ParticleTypes.SMOKE, px, player.getY() + 1.0, pz, 12, 1.4, 0.4, 1.4, 0.02);
      level.playSound(null, px, player.getY(), pz, SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 1.4F, 0.9F);
      if (struck > 0) {
         level.playSound(null, px, player.getY(), pz, SoundEvents.PLAYER_ATTACK_KNOCKBACK, SoundSource.PLAYERS, 1.2F, 0.8F);
      }
      if (struck == 0) {
         // Nothing there: say so, because a click that visibly costs a cooldown and hits nothing
         // otherwise reads as a broken item.
         Chat.msg(player, "&7The court remembers, and there was nobody in front of it.");
      }
      return null;
   }

   /** True when a body is in the wedge in front of the holder rather than merely nearby. */
   private static boolean inArc(Vec3 origin, Vec3 forward, LivingEntity candidate) {
      Vec3 to = candidate.position().subtract(origin);
      Vec3 flat = new Vec3(to.x, 0.0, to.z);
      double length = flat.length();
      if (length < 1.0E-4) {
         return true;
      }
      // A 60-degree half-angle: the wedge the arc is drawn across, so what is hit is what the
      // particles show.
      return flat.normalize().dot(forward) >= 0.5;
   }

   /**
    * Arms the stack's single clock, and takes the longer of the two.
    *
    * <p>{@link ModItems#setCooldownUntil} is written with {@code Math.max} here rather than
    * directly, because both abilities share one field: a five-minute rescue followed by an arc
    * a second later would otherwise hand the rescue back after twenty seconds.
    */
   private static void armClock(ServerPlayer player, ItemStack stack, int ticks, long now) {
      long until = laterClock(ModItems.cooldownUntil(stack), now, ticks);
      ModItems.setCooldownUntil(stack, until);
      try {
         player.getCooldowns().addCooldown(stack, ticks);
      } catch (Throwable ignored) {
      }
      try {
         player.sendOverlayMessage(
            Component.literal("\u00a77The Remembrance is remembering\u00a78 | \u00a7f" + (ticks / 20) + "s")
         );
      } catch (Throwable ignored) {
      }
   }

   /**
    * The clock both abilities share: the later of the one already armed and the one being asked
    * for.
    *
    * <p>One field is one field, and both abilities write to it. Written the obvious way -
    * {@code until = now + ticks} - a twenty-second arc fired a second after a five-minute rescue
    * would hand the rescue back after twenty seconds, which is a free escape no amount of
    * play-testing would attribute to the arc. A method rather than an expression because this is
    * the arithmetic the self-test pins.
    */
   public static long laterClock(long armed, long now, int ticks) {
      return Math.max(armed, now + ticks);
   }

   /** Seconds left on the blade, for a command or a check to read back. */
   public static long secondsLeft(ServerPlayer player, ItemStack stack) {
      if (player == null || stack == null) {
         return 0L;
      }
      return ModItems.cooldownSecondsLeft(stack, player.level().getGameTime());
   }

   /** The two clocks, for the harness: which ability costs what. */
   public static int letGoTicks() {
      return LET_GO_TICKS;
   }

   public static int courtTicks() {
      return COURT_TICKS;
   }
}
