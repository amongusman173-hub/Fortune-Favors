package com.fortuneandfavors.economy;

import com.fortuneandfavors.ModItems;
import com.fortuneandfavors.util.Chat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * The Puppeteer's three legendaries.
 *
 * <ul>
 *   <li><b>Puppeteer's Mask</b> - helmet. Killing an enemy has a chance to raise a
 *       temporary puppet of them that fights for you for twenty seconds.</li>
 *   <li><b>Marionette Strings</b> - utility. Right-click an entity to tie a string
 *       to it, then right-click again to yank it toward you. The string snaps if
 *       the target gets far enough away.</li>
 *   <li><b>The Empty Mask</b> - helmet. Your display name goes blank, hostile mobs
 *       lose interest in you, and the blow that would have killed you leaves a
 *       puppet standing in your place instead.</li>
 * </ul>
 *
 * <p>Everything here is a copy, never a transfer: a puppet carries a photograph of
 * a player's gear, and no player's inventory is ever read destructively by any of
 * it.
 */
public final class PuppeteerGear {
   /** How long a mask-raised puppet lasts. */
   private static final int ALLY_TICKS = 400;
   /** Its health - a bodyguard, not a second player. */
   private static final double ALLY_HEALTH = 30.0;
   /** Chance a kill with the mask on raises one. */
   private static final float ALLY_CHANCE = 0.25F;
   /** Its own swing cadence. */
   private static final int ALLY_SWING_EVERY = 22;
   private static final double ALLY_CHASE = 20.0;

   /** How far a tied string reaches before it snaps. */
   private static final double TIED_RANGE = 24.0;
   /**
    * How long a tied string lasts before it goes slack by itself.
    *
    * <p>Half a minute was long enough that the rope stopped reading as an effect and
    * started reading as something stuck to the target: a line of particles that follows
    * somebody around the map long after the pull that put it there. Ten seconds is long
    * enough for a chase and short enough that the string is visibly a temporary thing.
    */
   private static final int TIE_TICKS = 200;
   /** Ticks between pulls with the Strings. */
   private static final int PULL_COOLDOWN_TICKS = 30;
   /** How hard a pull is. Enough to matter in a chase, never a teleport. */
   private static final double PULL_POWER = 1.15;
   /** How far you can tie a string. */
   private static final double TIE_RANGE = 18.0;

   /** The Empty Mask's escape: how long it holds, and how long it takes to recharge. */
   private static final int ESCAPE_TICKS = 100;
   private static final long EMPTY_MASK_COOLDOWN = 4000L;
   /** Health the mask leaves you on. It is a window, not a heal. */
   private static final float ESCAPE_HEALTH = 6.0F;
   /** How often hostile mobs re-consider whether they can see you at all. */
   private static final int SHRUG_OFF_CHANCE_PERCENT = 45;

   private static final Random RANDOM = new Random();
   /** Puppets raised by a mask, keyed by the puppet. */
   private static final Map<UUID, Ally> ALLIES = new HashMap<>();
   /** Live strings, keyed by who is holding them. */
   private static final Map<UUID, Tie> TIES = new HashMap<>();
   private static final Map<UUID, Long> PULL_READY = new HashMap<>();
   /** Players who just escaped on the Empty Mask, until their window closes. */
   private static final Map<UUID, Long> ESCAPED = new HashMap<>();
   private static long clock = 0L;

   /** Violet thread: the colour every string, sigil and puppet effect is drawn in. */
   private static final int THREAD = 0x9A6CFF;
   /** The pale glint of a string pulled taut. */
   private static final int TAUT = 0xE6D6FF;
   /** The paint on the mask, for the moments it bites. */
   private static final int PAINT = 0xC3283F;
   /** Most puppets one wearer can have standing at once - decoys included. */
   private static final int ALLIES_PER_OWNER = 3;

   private PuppeteerGear() {
   }

   private static final class Ally {
      final UUID puppet;
      final UUID owner;
      final String name;
      /**
       * A decoy stands still and is shot at; a puppet fights. The Empty Mask needs
       * the first and the Puppeteer's Mask needs the second, and the difference
       * matters: a decoy that fought would be an ally nobody equipped.
       */
      final boolean passive;
      long until;
      long nextSwing;

      Ally(UUID puppet, UUID owner, String name, boolean passive, long until) {
         this.puppet = puppet;
         this.owner = owner;
         this.name = name;
         this.passive = passive;
         this.until = until;
      }
   }

   /** A string held by a player, tied to one target. */
   private static final class Tie {
      final UUID target;
      long until;

      Tie(UUID target, long until) {
         this.target = target;
         this.until = until;
      }
   }

   // ------------------------------------------------------- Puppeteer's Mask

   /**
    * A kill with the mask on: sometimes the thing you killed stands back up on your
    * side.
    *
    * <p>It prefers the victim's likeness - the mask's whole idea is that the dead
    * keep working - and falls back to a copy of the wearer when the victim was a
    * mob, since a cow-shaped bodyguard is not worth the cooldown it costs to wear
    * the thing.
    */
   public static void onKill(ServerPlayer killer, LivingEntity victim) {
      try {
         if (killer == null || victim == null || victim == killer) {
            return;
         }
         if (!ModItems.isPuppeteersMask(killer.getItemBySlot(EquipmentSlot.HEAD))) {
            return;
         }
         if (!(killer.level() instanceof ServerLevel level)) {
            return;
         }
         if (RANDOM.nextFloat() > ALLY_CHANCE) {
            return;
         }
         // Per wearer. The cap used to be four for the whole server, so one player's puppets and
         // decoys quietly switched the mask off for everybody else.
         if (alliesOf(killer.getUUID()) >= ALLIES_PER_OWNER) {
            return;
         }

         ServerPlayer template = victim instanceof ServerPlayer other ? other : killer;
         String name = template == killer
            ? "\u00a75Puppet of \u00a7f" + killer.getName().getString()
            : "\u00a75Puppet of \u00a7f" + victim.getName().getString();
         ServerPlayer puppet = BossManager.spawnPuppetCopy(level, template, name, ALLY_HEALTH, true);
         if (puppet == null) {
            return;
         }
         long now = ServerClock.clock(level);
         ALLIES.put(puppet.getUUID(), new Ally(puppet.getUUID(), killer.getUUID(), name, false, now + ALLY_TICKS));
         // The body is hauled up on four strings let down from nowhere.
         Vec3 feet = puppet.position();
         Fx.runeCircle(level, ParticleTypes.SOUL, feet.add(0.0, 0.05, 0.0), 1.8, 30, THREAD);
         stringsFromAbove(level, puppet, 9.0);
         Fx.vanilla(level, ParticleTypes.SOUL, puppet.getX(), puppet.getY() + 1.0, puppet.getZ(), 30, 0.6, 0.8, 0.6, 0.06);
         Fx.vanilla(level, ParticleTypes.END_ROD, puppet.getX(), puppet.getY() + 1.4, puppet.getZ(), 20, 0.5, 0.7, 0.5, 0.04);
         level.playSound(null, puppet.getX(), puppet.getY(), puppet.getZ(), SoundEvents.EVOKER_CAST_SPELL, SoundSource.PLAYERS, 1.0F, 0.8F);
         killer.sendOverlayMessage(Component.literal("\u00a75The mask takes the body \u00a78| \u00a7f" + name + " \u00a77is yours for 20s"));
      } catch (Throwable ignored) {
      }
   }

   // ------------------------------------------------- Marionette Strings

   /**
    * Right-click with the Strings.
    *
    * <p>Two actions, one button, resolved by context: aiming at a body ties a string
    * to it, and aiming at anything else (or at the body already tied) yanks the tied
    * thing toward you. A pull has a cooldown, the tie does not, and the string snaps
    * on distance - which is the counterplay, and the reason it is not just a remote
    * kidnapping tool.
    */
   public static String useStrings(ServerPlayer player, ItemStack held) {
      if (!(player.level() instanceof ServerLevel level)) {
         return null;
      }
      long now = ServerClock.clock(level);
      LivingEntity mark = markUnderCrosshair(level, player);
      Tie tie = TIES.get(player.getUUID());

      // Aiming at a body that is not already tied: tie it.
      if (mark != null && (tie == null || !tie.target.equals(mark.getUUID()))) {
         TIES.put(player.getUUID(), new Tie(mark.getUUID(), now + TIE_TICKS));
         drawTie(level, player, mark);
         Fx.shape(level, com.fortuneandfavors.net.FfVfx.RING, ParticleTypes.END_ROD, mark.position().add(0.0, mark.getBbHeight() * 0.6, 0.0), Vec3.ZERO, Math.max(0.6, mark.getBbWidth()), 0.0, TAUT);
         level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.TRIPWIRE_ATTACH, SoundSource.PLAYERS, 1.0F, 1.1F);
         player.sendOverlayMessage(Component.literal("\u00a75String tied \u00a78- \u00a7fright-click again to pull"));
         return null;
      }

      if (tie == null) {
         Chat.msg(player, "&7Right-click an entity to tie a string to it.");
         return null;
      }

      Entity target = findEntity(level.getServer(), tie.target);
      if (target == null || !(target instanceof LivingEntity living) || !living.isAlive()) {
         TIES.remove(player.getUUID());
         player.sendOverlayMessage(Component.literal("\u00a77The string goes slack - there is nothing on the end of it."));
         return null;
      }
      if (player.distanceTo(living) > TIED_RANGE) {
         TIES.remove(player.getUUID());
         player.sendOverlayMessage(Component.literal("\u00a77The string snaps - \u00a7fthey are out of range."));
         level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.TRIPWIRE_DETACH, SoundSource.PLAYERS, 1.0F, 1.3F);
         return null;
      }

      Long ready = PULL_READY.get(player.getUUID());
      if (ready != null && now < ready) {
         player.sendOverlayMessage(Component.literal("\u00a77The strings are still tightening &8(" + ((ready - now + 19) / 20) + "s)"));
         return null;
      }

      // PULL.
      PULL_READY.put(player.getUUID(), now + PULL_COOLDOWN_TICKS);
      Vec3 toward = player.position().subtract(living.position());
      Vec3 flat = new Vec3(toward.x, 0.0, toward.z);
      if (flat.lengthSqr() < 1.0E-4) {
         return null;
      }
      // Heavy things move less. A boss used to come across the arena exactly as far as a zombie,
      // which turned the Strings into a way to drag a fight out of its own room.
      double weight = 1.0;
      try {
         weight = 1.0 - Math.max(0.0, Math.min(1.0, living.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.KNOCKBACK_RESISTANCE)));
      } catch (Throwable ignored) {
      }
      if (BossManager.isMarkedBoss(living)) {
         weight = Math.min(weight, 0.25);
      }
      Vec3 yank = flat.normalize().scale(PULL_POWER * Math.max(0.15, weight));
      living.setDeltaMovement(living.getDeltaMovement().add(yank.x, 0.35 * Math.max(0.3, weight), yank.z));
      living.hurtMarked = true;
      drawTie(level, player, living);
      Vec3 at = living.position().add(0.0, living.getBbHeight() * 0.6, 0.0);
      Fx.shape(level, com.fortuneandfavors.net.FfVfx.CLASH, ParticleTypes.CRIT, at, flat.normalize(), 0.0, 0.0, TAUT);
      Fx.shape(level, com.fortuneandfavors.net.FfVfx.BEAM, ParticleTypes.END_ROD, at, player.position().add(0.0, 1.2, 0.0), 0.0, 0.0, TAUT);
      level.playSound(null, living.getX(), living.getY(), living.getZ(), SoundEvents.TRIPWIRE_CLICK_ON, SoundSource.PLAYERS, 1.2F, 1.0F);
      player.sendOverlayMessage(Component.literal("\u00a75PULL \u00a78| \u00a7f" + living.getName().getString() + " \u00a77is dragged toward you"));
      return null;
   }

   /** The living thing under the crosshair, if there is one within tying range. */
   private static LivingEntity markUnderCrosshair(ServerLevel level, ServerPlayer player) {
      try {
         Vec3 eye = player.getEyePosition();
         Vec3 view = player.getViewVector(1.0F).normalize();
         LivingEntity best = null;
         double bestScore = Double.MAX_VALUE;
         for (LivingEntity living : level.getEntitiesOfClass(
            LivingEntity.class,
            player.getBoundingBox().inflate(TIE_RANGE),
            e -> e.isAlive() && e != player
         )) {
            Vec3 to = new Vec3(living.getX(), living.getY() + living.getBbHeight() * 0.5, living.getZ()).subtract(eye);
            double distance = to.length();
            if (distance > TIE_RANGE || distance < 0.4) {
               continue;
            }
            double dot = view.dot(to.scale(1.0 / distance));
            if (dot < 0.86) {
               continue;
            }
            // A string has to reach: nothing is tied through a wall.
            net.minecraft.world.phys.HitResult wall = level.clip(new net.minecraft.world.level.ClipContext(
               eye, eye.add(to), net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, player
            ));
            if (wall.getType() != net.minecraft.world.phys.HitResult.Type.MISS) {
               continue;
            }
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

   private static void drawTie(ServerLevel level, Entity from, Entity to) {
      Vec3 start = new Vec3(from.getX(), from.getY() + 1.2, from.getZ());
      Vec3 end = new Vec3(to.getX(), to.getY() + to.getBbHeight() * 0.6, to.getZ());
      Vec3 dir = end.subtract(start);
      double length = dir.length();
      if (length < 0.01) {
         return;
      }
      // One beam cue. The old string sent a particle packet every half block, every tick, for as
      // long as it was tied - forty packets a second for a single thread.
      Fx.shape(level, com.fortuneandfavors.net.FfVfx.BEAM, ParticleTypes.END_ROD, start, end, 0.0, 0.0, THREAD);
   }

   /** Four strings let down onto a body from above, the way a marionette hangs. */
   private static void stringsFromAbove(ServerLevel level, Entity body, double height) {
      double h = body.getBbHeight();
      double w = Math.max(0.3, body.getBbWidth() * 0.5);
      double[][] anchors = {{w, h * 0.85, 0.0}, {-w, h * 0.85, 0.0}, {w * 0.5, h * 0.35, w * 0.5}, {-w * 0.5, h * 0.35, -w * 0.5}};
      for (double[] a : anchors) {
         Vec3 on = body.position().add(a[0], a[1], a[2]);
         Fx.shape(level, com.fortuneandfavors.net.FfVfx.BEAM, ParticleTypes.END_ROD, on.add(0.0, height, 0.0), on, 0.0, 0.0, TAUT);
      }
   }

   private static int alliesOf(UUID owner) {
      int n = 0;
      for (Ally ally : ALLIES.values()) {
         if (ally.owner.equals(owner)) {
            n++;
         }
      }
      return n;
   }

   private static Entity findEntity(MinecraftServer server, UUID id) {
      if (server == null) {
         return null;
      }
      for (ServerLevel level : server.getAllLevels()) {
         Entity entity = level.getEntity(id);
         if (entity != null) {
            return entity;
         }
      }
      return null;
   }

   // ------------------------------------------------------- The Empty Mask

   /**
    * True when this player wears the Empty Mask and it is ready to fire.
    *
    * <p>Used by the damage gate: while it returns true, that blow does not kill. The
    * mask is spent by {@link #escape}, so a second lethal blow in the same fight is
    * lethal.
    */
   public static boolean canEscape(ServerPlayer player) {
      if (player == null) {
         return false;
      }
      ItemStack helm = player.getItemBySlot(EquipmentSlot.HEAD);
      if (!ModItems.isEmptyMask(helm)) {
         return false;
      }
      long now = ServerClock.clock(player.level());
      return ModItems.cooldownSecondsLeft(helm, now) <= 0L;
   }

   /**
    * The mask takes your place: the blow is cancelled, a decoy wearing your face
    * drops where you were standing, and you get a short window with nothing able to
    * see you.
    *
    * <p>Deliberately not a heal - you are left on a few hearts so that the fight is
    * still on and the escape is still something you have to use.
    */
   public static boolean escape(ServerPlayer player) {
      if (!canEscape(player) || !(player.level() instanceof ServerLevel level)) {
         return false;
      }
      ItemStack helm = player.getItemBySlot(EquipmentSlot.HEAD);
      long now = ServerClock.clock(level);
      ModItems.setCooldownUntil(helm, now + EMPTY_MASK_COOLDOWN);

      ServerPlayer decoy = BossManager.spawnPuppetCopy(
         level, player, "\u00a78" + player.getName().getString(), player.getMaxHealth(), false
      );
      if (decoy != null) {
         Fx.vanilla(level, ParticleTypes.POOF, decoy.getX(), decoy.getY() + 1.0, decoy.getZ(), 30, 0.5, 0.8, 0.5, 0.06);
         // The swap: a mirror tears where they stood and the decoy is left hanging in it.
         Fx.shape(level, com.fortuneandfavors.net.FfVfx.TEAR, ParticleTypes.END_ROD, decoy.position().add(0.0, 1.0, 0.0), new Vec3(1.0, 0.0, 0.0), 1.6, 18, TAUT);
         stringsFromAbove(level, decoy, 7.0);
         // The decoy is scenery: it exists to be hit, and it goes quiet on its own.
         level.getServer().execute(() -> {
            try {
               decoy.setHealth(decoy.getMaxHealth());
            } catch (Throwable ignored) {
            }
         });
         ALLIES.put(decoy.getUUID(), new Ally(decoy.getUUID(), player.getUUID(), "\u00a78decoy", true, now + ESCAPE_TICKS));
      }

      player.setHealth(Math.max(1.0F, Math.min(ESCAPE_HEALTH, player.getMaxHealth())));
      player.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, ESCAPE_TICKS, 0, false, false, true));
      player.addEffect(new MobEffectInstance(MobEffects.SPEED, ESCAPE_TICKS, 1, false, false, true));
      ESCAPED.put(player.getUUID(), now + ESCAPE_TICKS);

      Chat.msg(player, "&8&lTHE PUPPET TAKES YOUR PLACE.");
      player.sendOverlayMessage(Component.literal("\u00a78THE PUPPET TAKES YOUR PLACE \u00a78| \u00a7frun."));
      Fx.vanilla(level, ParticleTypes.SMOKE, player.getX(), player.getY() + 1.0, player.getZ(), 40, 0.7, 1.0, 0.7, 0.05);
      Fx.shape(level, com.fortuneandfavors.net.FfVfx.NOVA, ParticleTypes.SMOKE, player.position().add(0.0, 0.3, 0.0), Vec3.ZERO, 3.5, 0.0, 0x2A2236);
      level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.TRIPWIRE_DETACH, SoundSource.PLAYERS, 1.4F, 0.8F);
      level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ILLUSIONER_MIRROR_MOVE, SoundSource.PLAYERS, 1.2F, 0.7F);
      return true;
   }

   /** True while the wearer of the Empty Mask is inside their escape window. */
   public static boolean isEscaping(ServerPlayer player) {
      if (player == null) {
         return false;
      }
      Long until = ESCAPED.get(player.getUUID());
      if (until == null) {
         return false;
      }
      if (ServerClock.clock(player.level()) >= until) {
         ESCAPED.remove(player.getUUID());
         return false;
      }
      return true;
   }

   /** The masked name the Empty Mask shows to everyone else. */
   public static String blankedName(String realName) {
      if (realName == null || realName.isEmpty()) {
         return "\u00a78\u2588\u2588\u2588";
      }
      // Partial, as promised: the last character survives so friends can still tell
      // each other apart, and nobody can read the rest off a name plate.
      String tail = realName.substring(realName.length() - 1);
      return "\u00a78\u2588\u2588\u2588" + tail;
   }

   // ------------------------------------------------------------------- tick

   public static void tick(MinecraftServer server) {
      if (server == null) {
         return;
      }
      clock++;
      tickAllies(server);
      tickTies(server);
      for (ServerPlayer p : server.getPlayerList().getPlayers()) {
         try {
            if (ModItems.isEmptyMask(p.getItemBySlot(EquipmentSlot.HEAD)) && p.isAlive()) {
               shrugOffMobs(p);
            }
            if (ESCAPED.containsKey(p.getUUID()) && ServerClock.clock(p.level()) >= ESCAPED.get(p.getUUID())) {
               ESCAPED.remove(p.getUUID());
               p.sendOverlayMessage(Component.literal("\u00a77The mask is quiet again."));
            }
         } catch (Throwable ignored) {
         }
      }
   }

   /**
    * Hostile mobs lose their grip on a masked player.
    *
    * <p>Rather than a stealth attribute the game does not have, the mask simply
    * answers the question a mob was about to ask: on a fraction of ticks, anything
    * currently targeting the wearer is told to forget. It is a shrug, not
    * invisibility, and it does nothing to a mob already swinging.
    */
   private static void shrugOffMobs(ServerPlayer player) {
      if (clock % 10L != 0L || !(player.level() instanceof ServerLevel level)) {
         return;
      }
      List<Mob> watching = new ArrayList<>();
      for (Mob mob : level.getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(18.0), m -> m.getTarget() == player)) {
         watching.add(mob);
      }
      for (Mob mob : watching) {
         if (RANDOM.nextInt(100) >= SHRUG_OFF_CHANCE_PERCENT) {
            continue;
         }
         mob.setTarget(null);
         Fx.vanilla(level, ParticleTypes.SMOKE, mob.getX(), mob.getEyeY(), mob.getZ(), 3, 0.2, 0.2, 0.2, 0.01);
      }
   }

   private static void tickAllies(MinecraftServer server) {
      if (ALLIES.isEmpty()) {
         return;
      }
      long now = ServerClock.clock(server.overworld());
      for (Iterator<Map.Entry<UUID, Ally>> it = ALLIES.entrySet().iterator(); it.hasNext();) {
         Map.Entry<UUID, Ally> entry = it.next();
         Ally ally = entry.getValue();
         Entity raw = findEntity(server, entry.getKey());
         if (!(raw instanceof ServerPlayer puppet) || !puppet.isAlive()) {
            it.remove();
            continue;
         }
         if (!(puppet.level() instanceof ServerLevel level)) {
            it.remove();
            continue;
         }
         now = ServerClock.clock(level);
         if (now >= ally.until) {
            it.remove();
            Fx.vanilla(level, ParticleTypes.POOF, puppet.getX(), puppet.getY() + 1.0, puppet.getZ(), 16, 0.4, 0.6, 0.4, 0.05);
            // Its strings are cut and it drops.
            Fx.shape(level, com.fortuneandfavors.net.FfVfx.RING, ParticleTypes.END_ROD, puppet.position().add(0.0, 0.1, 0.0), Vec3.ZERO, 1.2, 0.0, THREAD);
            level.playSound(null, puppet.getX(), puppet.getY(), puppet.getZ(), SoundEvents.TRIPWIRE_DETACH, SoundSource.PLAYERS, 0.8F, 1.2F);
            BossManager.removeFakePlayer(server, puppet);
            if (ally.passive) {
               // The decoy dissolving is the tell that the escape is over - and the
               // player it stood in for is told, so nobody wonders what just happened.
               ServerPlayer owner = server.getPlayerList().getPlayer(ally.owner);
               if (owner != null) {
                  owner.sendOverlayMessage(Component.literal("\u00a78The puppet they were hitting comes apart."));
               }
            }
            continue;
         }
         final long at = now;
         com.fortuneandfavors.util.Safe.run("puppeteer gear puppet", () -> driveAlly(level, puppet, ally, at));
      }
   }

   /** One target, one idea: walk at whoever is not ours and swing. */
   private static void driveAlly(ServerLevel level, ServerPlayer puppet, Ally ally, long now) {
      if (ally.passive) {
         // A decoy does not fight. It stands where it was put, looks alive, and is
         // meant to be hit instead of its owner.
         return;
      }
      // Whatever the owner is fighting, first: the thing they last hit, then the thing that last
      // hit them. Only then the nearest monster. It used to take the nearest player who was not
      // its owner - the owner's friends, and every other puppet, since puppets are players too -
      // so a pair of puppets would turn on each other, or on the decoy covering their owner.
      ServerPlayer owner = level.getServer().getPlayerList().getPlayer(ally.owner);
      LivingEntity target = null;
      if (owner != null) {
         for (LivingEntity wanted : new LivingEntity[]{owner.getLastHurtMob(), owner.getLastHurtByMob()}) {
            if (wanted != null && isFoe(wanted, ally) && wanted.distanceToSqr(puppet) < ALLY_CHASE * ALLY_CHASE * 4.0) {
               target = wanted;
               break;
            }
         }
      }
      double best = ALLY_CHASE * ALLY_CHASE;
      if (target == null) {
         for (Mob mob : level.getEntitiesOfClass(Mob.class, puppet.getBoundingBox().inflate(ALLY_CHASE), m -> m.isAlive())) {
            if (BossManager.isMarkedBoss(mob) || !(mob instanceof net.minecraft.world.entity.monster.Enemy) || !isFoe(mob, ally)) {
               continue;
            }
            double distance = mob.distanceToSqr(puppet);
            if (distance < best) {
               best = distance;
               target = mob;
            }
         }
      }
      if (target == null) {
         return;
      }
      puppet.setYRot(faceYaw(puppet, target));
      puppet.setYHeadRot(puppet.getYRot());
      double distance = puppet.distanceTo(target);
      if (distance > 2.6) {
         Vec3 direction = target.position().subtract(puppet.position());
         Vec3 flat = new Vec3(direction.x, 0.0, direction.z);
         if (flat.lengthSqr() > 1.0E-4) {
            Vec3 step = flat.normalize().scale(0.24);
            puppet.setDeltaMovement(step.x, puppet.getDeltaMovement().y, step.z);
            puppet.hurtMarked = true;
            if (puppet.horizontalCollision) {
               puppet.jumpFromGround();
            }
         }
      }
      if (distance <= 3.4 && now >= ally.nextSwing) {
         ally.nextSwing = now + ALLY_SWING_EVERY;
         try {
            puppet.attack(target);
         } catch (Throwable ignored) {
         }
      }
   }

   private static void tickTies(MinecraftServer server) {
      if (TIES.isEmpty()) {
         return;
      }
      for (Iterator<Map.Entry<UUID, Tie>> it = TIES.entrySet().iterator(); it.hasNext();) {
         Map.Entry<UUID, Tie> entry = it.next();
         Tie tie = entry.getValue();
         ServerPlayer holder = server.getPlayerList().getPlayer(entry.getKey());
         Entity target = findEntity(server, tie.target);
         if (holder == null || !holder.isAlive() || target == null || !(target instanceof LivingEntity) || !target.isAlive()) {
            it.remove();
            continue;
         }
         long now = ServerClock.clock(holder.level());
         if (now >= tie.until) {
            it.remove();
            // Said out loud, like the snap: a string that simply stops being drawn
            // leaves the holder wondering whether it is still there.
            holder.sendOverlayMessage(Component.literal("\u00a77The string goes slack - it only holds for so long."));
            if (holder.level() instanceof ServerLevel level) {
               level.playSound(null, holder.getX(), holder.getY(), holder.getZ(), SoundEvents.TRIPWIRE_DETACH, SoundSource.PLAYERS, 1.0F, 1.4F);
            }
            continue;
         }
         if (holder.distanceTo(target) > TIED_RANGE) {
            it.remove();
            holder.sendOverlayMessage(Component.literal("\u00a77The string snaps. \u00a7fToo far."));
            continue;
         }
         // The trail is the point: a string you cannot see is a string you forget. Redrawn four
         // times a second; the client's beam fills the gaps between cues.
         if (holder.level() instanceof ServerLevel level && clock % 5L == 0L) {
            drawTie(level, holder, target);
         }
      }
   }

   /** Not the owner, not a puppet or decoy, not any of the mod's fake players. */
   private static boolean isFoe(LivingEntity entity, Ally ally) {
      return entity.isAlive()
         && !entity.getUUID().equals(ally.owner)
         && !ALLIES.containsKey(entity.getUUID())
         && !BossManager.isFakePlayer(entity);
   }

   private static float faceYaw(Entity from, Entity to) {
      double dx = to.getX() - from.getX();
      double dz = to.getZ() - from.getZ();
      return (float)(Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
   }

   public static void onLogout(ServerPlayer player) {
      if (player == null) {
         return;
      }
      TIES.remove(player.getUUID());
      PULL_READY.remove(player.getUUID());
      ESCAPED.remove(player.getUUID());
      // Their puppets stay for the length of their timer and are cleaned up by it.
   }

   public static void clear() {
      ALLIES.clear();
      TIES.clear();
      PULL_READY.clear();
      ESCAPED.clear();
   }

   /** Test hooks. */
   public static double tiedRange() {
      return TIED_RANGE;
   }

   /** Test hook: how long a tied string lasts before it goes slack on its own. */
   public static int tieTicks() {
      return TIE_TICKS;
   }

   public static int pullCooldownTicks() {
      return PULL_COOLDOWN_TICKS;
   }

   public static double allyHealth() {
      return ALLY_HEALTH;
   }

   public static int allyTicks() {
      return ALLY_TICKS;
   }

   public static long emptyMaskCooldown() {
      return EMPTY_MASK_COOLDOWN;
   }

   public static int allyCount() {
      return ALLIES.size();
   }

   /** The names of the live mask puppets - used by diagnostics and the self-test. */
   public static Set<String> allyNames() {
      Set<String> names = new HashSet<>();
      for (Ally ally : ALLIES.values()) {
         names.add(ally.name);
      }
      return names;
   }
}
