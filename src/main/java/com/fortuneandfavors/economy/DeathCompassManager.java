package com.fortuneandfavors.economy;

import com.fortuneandfavors.ModItems;
import com.fortuneandfavors.ModSounds;
import com.fortuneandfavors.economy.WormholeManager.LastDeath;
import com.fortuneandfavors.util.Chat;
import com.fortuneandfavors.util.SoundUtil;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

/** The Death Compass - a hold-to-track / right-click-to-ping grave finder.
 *
 *  While held, a soul-flame thread reaches from your hand toward your last
 *  death location and the distance shows on the action bar. Right-clicking
 *  fires a sonar ping: a soul-fire burst + beacon column marks the grave
 *  (even through walls) and the exact coords are announced. The compass
 *  goes quiet once the grave is retrieved (see NiceKeepInventoryManager).
 *
 *  Everything here is 100% server-side; particles/sounds auto-sync to the
 *  client. The compass does nothing when nobody is holding it. */
public final class DeathCompassManager {
   private static final Map<UUID, Long> pingCooldown = new HashMap<>();
   private static final String[] DIRS = {"S", "SW", "W", "NW", "N", "NE", "E", "SE"};
   private static final long PING_COOLDOWN_TICKS = 100L; // 5 seconds

   private DeathCompassManager() {
   }

   /** Called every player tick while a Death Compass is in either hand. */
   public static void tick(ServerPlayer player) {
      if (player == null || !player.isAlive() || player.level().isClientSide()) {
         return;
      }
      if (!ModItems.isDeathCompass(player.getMainHandItem()) && !ModItems.isDeathCompass(player.getOffhandItem())) {
         return;
      }

      long now = player.level().getGameTime();
      LastDeath death = WormholeManager.getLastDeath(player);
      if (death == null) {
         // Quiet: only nudge the player occasionally, never spam.
         if (now % 100L == 0L) {
            player.sendSystemMessage(Component.literal("☠ §7The compass is quiet - no grave to track."), true);
         }
         return;
      }

      if (now % 20L == 0L) {
         player.sendSystemMessage(Component.literal(statusLine(player, death)), true);
      }

      // /ff compassfx off silences the visual effects (beam, pulse, beacon) -
      // the action-bar readout above always stays.
      if (!DisplayPrefsManager.compassFx(player.getUUID())) {
         return;
      }

      if (!death.dimension().equals(player.level().dimension())) {
         return;
      }

      ServerLevel level = (ServerLevel)player.level();
      if (now % 10L == 0L) {
         Vec3 from = player.getEyePosition(1.0F);
         Vec3 target = new Vec3(death.pos().getX() + 0.5, death.pos().getY() + 0.5, death.pos().getZ() + 0.5);
         Vec3 to = target;
         double dist = from.distanceTo(target);
         if (dist > 64.0) {
            Vec3 dir = target.subtract(from).normalize();
            to = from.add(dir.scale(64.0));
         }
         drawThread(level, from, to, Math.min(32, Math.max(6, (int)(from.distanceTo(to) / 2.0))), ParticleTypes.SOUL);
         // A tiny spark off the compass itself so the held hand reads as \"live\".
         level.sendParticles(ParticleTypes.END_ROD, player.getX(), player.getY() + 1.3, player.getZ(), 1, 0.15, 0.25, 0.15, 0.01);
      }

      // Faint grave-side pulse so the destination itself breathes.
      if (now % 80L == 0L) {
         level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, death.pos().getX() + 0.5, death.pos().getY() + 0.5, death.pos().getZ() + 0.5, 3, 0.4, 0.6, 0.4, 0.02);
      }
   }

   /** Right-click: sonar ping - marks the grave with a beacon + burst. */
   public static void use(ServerPlayer player) {
      long now = player.level().getGameTime();
      Long readyAt = pingCooldown.get(player.getUUID());
      if (readyAt != null && now < readyAt) {
         int left = (int)((readyAt - now) / 20L) + 1;
         player.sendSystemMessage(Component.literal("☠ §7The compass is recharging - " + left + "s."), true);
         return;
      }

      LastDeath death = WormholeManager.getLastDeath(player);
      if (death == null) {
         SoundUtil.play(player, ModSounds.DENY);
         player.sendSystemMessage(Component.literal("☠ §7You have no grave to find."), true);
         return;
      }

      pingCooldown.put(player.getUUID(), now + PING_COOLDOWN_TICKS);
      ServerLevel level = (ServerLevel)player.level();
      Vec3 from = player.getEyePosition(1.0F);
      double gx = death.pos().getX() + 0.5;
      double gy = death.pos().getY() + 0.5;
      double gz = death.pos().getZ() + 0.5;
      Vec3 target = new Vec3(gx, gy, gz);
      double dist = from.distanceTo(target);

      if (death.dimension().equals(player.level().dimension()) && DisplayPrefsManager.compassFx(player.getUUID())) {
         Vec3 to = target;
         if (dist > 128.0) {
            Vec3 dir = target.subtract(from).normalize();
            to = from.add(dir.scale(128.0));
         }
         // Full thread from the compass to the grave (clamped for particle sanity).
         drawThread(level, from, to, Math.min(64, Math.max(6, (int)(from.distanceTo(to) / 2.0))), ParticleTypes.SOUL_FIRE_FLAME);
         // Beacon column straight up from the grave - visible across the map.
         for (int i = 0; i < 30; i++) {
            level.sendParticles(ParticleTypes.END_ROD, gx, gy + i * 0.4, gz, 1, 0.0, 0.0, 0.0, 0.0);
         }
         level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, gx, gy, gz, 16, 0.8, 0.8, 0.8, 0.05);
         level.sendParticles(ParticleTypes.SCULK_SOUL, gx, gy + 0.3, gz, 20, 0.6, 0.5, 0.6, 0.06);
         level.playSound(null, gx, gy, gz, SoundEvents.RESPAWN_ANCHOR_SET_SPAWN, SoundSource.PLAYERS, 0.8F, 0.9F);
      }

      level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.BELL_RESONATE, SoundSource.PLAYERS, 0.7F, 1.0F);
      int distInt = (int)Math.round(dist);
      String dir = compassDir(target.x - player.getX(), target.z - player.getZ());
      String dim = death.dimension().equals(player.level().dimension()) ? "" : " §8(in " + dimLabel(death.dimension()) + ")";
      Chat.raw(
         player,
         Chat.colorize(
            "&4&l☠&r &7Grave located: &e"
               + death.pos().getX()
               + ", "
               + death.pos().getY()
               + ", "
               + death.pos().getZ()
               + "&7 · &e"
               + distInt
               + "m &7"
               + dir
               + dim
         )
      );
      Advancements.grant(player, "grave_finder");
   }

   private static String statusLine(ServerPlayer player, LastDeath death) {
      double dx = death.pos().getX() + 0.5 - player.getX();
      double dy = death.pos().getY() + 0.5 - player.getY();
      double dz = death.pos().getZ() + 0.5 - player.getZ();
      int dist = (int)Math.round(Math.sqrt(dx * dx + dy * dy + dz * dz));
      String dir = compassDir(dx, dz);
      String dim = death.dimension().equals(player.level().dimension()) ? "" : " §8(" + dimLabel(death.dimension()) + ")";
      return "☠ §7Grave §e" + dist + "m §7" + dir + dim;
   }

   private static String compassDir(double dx, double dz) {
      double deg = Math.toDegrees(Math.atan2(dx, dz)); // 0 = +Z (south)
      int idx = (int)Math.floor((deg + 22.5) / 45.0) & 7;
      return DIRS[idx];
   }

   private static String dimLabel(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dim) {
      return dim.identifier().toString().replace("minecraft:", "").replace("fortuneandfavors:", "");
   }

   private static void drawThread(ServerLevel level, Vec3 from, Vec3 to, int steps, ParticleOptions particle) {
      for (int i = 0; i < steps; i++) {
         double t = (i + 1) / (double)steps;
         level.sendParticles(particle, from.x + (to.x - from.x) * t, from.y + (to.y - from.y) * t, from.z + (to.z - from.z) * t, 1, 0.0, 0.0, 0.0, 0.0);
      }
   }
}