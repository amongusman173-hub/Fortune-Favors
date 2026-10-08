package com.fortuneandfavors.economy;

import com.fortuneandfavors.ModItems;
import com.fortuneandfavors.util.Chat;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/** The Bounty Compass - always points at the player holding the HIGHEST bounty\n *  on the server, as long as they are within 20,000 blocks (and online, not\n *  vanished). Hold it for a live action-bar readout + a red tracking thread;\n *  right-click announces the hunted player's exact position. Server-side only. */
public final class BountyCompassManager {
   /** The tracking range the compass works over. */
   public static final double RANGE = 20000.0;
   private static final String[] DIRS = {"S", "SW", "W", "NW", "N", "NE", "E", "SE"};
   /** Per-holder cached target so we never rescan every bounty every tick. */
   private static final java.util.Map<UUID, Object[]> targetCache = new java.util.HashMap<>();

   private BountyCompassManager() {
   }

   /** Called every player tick while a Bounty Compass is held. The heavy part
    *  (scanning every bounty + online players) only runs at most once a second
    *  per holder - everything else is a cached read. */
   public static void tick(ServerPlayer player) {
      if (player == null || !player.isAlive() || player.level().isClientSide()) {
         return;
      }
      if (!ModItems.isBountyCompass(player.getMainHandItem()) && !ModItems.isBountyCompass(player.getOffhandItem())) {
         targetCache.remove(player.getUUID());
         return;
      }
      long now = player.level().getGameTime();
      Object[] cached = targetCache.get(player.getUUID());
      ServerPlayer hunted;
      if (cached != null && cached[1] instanceof Long at && now - at < 20L) {
         hunted = (ServerPlayer)cached[0];
      } else {
         hunted = huntedTarget(player);
         targetCache.put(player.getUUID(), new Object[]{hunted, now});
      }
      if (hunted == null) {
         if (now % 100L == 0L) {
            player.sendSystemMessage(Component.literal("☠ §7The compass spins freely - no bounties in range."), true);
         }
         return;
      }

      double dx = hunted.getX() - player.getX();
      double dy = hunted.getY() - player.getY();
      double dz = hunted.getZ() - player.getZ();
      double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
      boolean sameDim = hunted.level().dimension().equals(player.level().dimension());

      if (now % 20L == 0L) {
         String dim = sameDim ? "" : " §8(" + hunted.level().dimension().identifier().toString().replace("minecraft:", "").replace("fortuneandfavors:", "") + ")";
         player.sendSystemMessage(
            Component.literal("☠ §7Hunted: §c" + hunted.getName().getString() + "§7 · §e" + (int)Math.round(dist) + "m §7" + compassDir(dx, dz) + dim),
            true
         );
      }

      if (sameDim && now % 10L == 0L && player.level() instanceof ServerLevel sl) {
         Vec3 from = player.getEyePosition(1.0F);
         Vec3 target = new Vec3(hunted.getX(), hunted.getY() + 1.0, hunted.getZ());
         Vec3 to = target;
         if (dist > 64.0) {
            to = from.add(target.subtract(from).normalize().scale(64.0));
         }
         drawThread(sl, from, to, Math.min(32, Math.max(6, (int)(from.distanceTo(to) / 2.0))));
         com.fortuneandfavors.net.FfVfx.particles(sl, ParticleTypes.FLAME, player.getX(), player.getY() + 1.3, player.getZ(), 1, 0.15, 0.25, 0.15, 0.01);
      }
   }

   /** Right-click: announce exactly where the hunted player is. */
   public static void use(ServerPlayer player) {
      ServerPlayer hunted = huntedTarget(player);
      if (hunted == null) {
         player.sendSystemMessage(Component.literal("☠ §7The compass spins freely - no bounties in range."), true);
         return;
      }
      double dx = hunted.getX() - player.getX();
      double dy = hunted.getY() - player.getY();
      double dz = hunted.getZ() - player.getZ();
      double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
      boolean sameDim = hunted.level().dimension().equals(player.level().dimension());
      String dim = sameDim ? "" : " §8(in " + hunted.level().dimension().identifier().toString().replace("minecraft:", "").replace("fortuneandfavors:", "") + ")";
      Chat.raw(
         player,
         Chat.colorize(
            "&4&l☠&r &7The hunted: &c"
               + hunted.getName().getString()
               + "&7 · &e"
               + (int)Math.round(dist)
               + "m "
               + compassDir(dx, dz)
               + "&7 · &f"
               + (int)Math.floor(hunted.getX())
               + ", "
               + (int)Math.floor(hunted.getY())
               + ", "
               + (int)Math.floor(hunted.getZ())
               + dim
         )
      );
   }

   /** The online, non-vanished player with the biggest bounty within RANGE of\n    *  the holder (their own bounty doesn't count). Null if none. */
   private static ServerPlayer huntedTarget(ServerPlayer holder) {
      ServerPlayer best = null;
      long bestAmount = 0L;
      for (Map.Entry<UUID, BountyManager.Bounty> e : BountyManager.all().entrySet()) {
         BountyManager.Bounty b = e.getValue();
         if (b == null || b.target == null || b.target.equals(holder.getUUID()) || b.amount <= bestAmount) {
            continue;
         }
         ServerPlayer target = holder.level().getServer() == null ? null : holder.level().getServer().getPlayerList().getPlayer(b.target);
         if (target == null || !target.isAlive() || com.fortuneandfavors.economy.VanishManager.isVanished(b.target)) {
            continue;
         }
         double dx = target.getX() - holder.getX();
         double dz = target.getZ() - holder.getZ();
         if (Math.sqrt(dx * dx + dz * dz) > RANGE) {
            continue;
         }
         best = target;
         bestAmount = b.amount;
      }
      return best;
   }

   private static String compassDir(double dx, double dz) {
      double deg = Math.toDegrees(Math.atan2(dx, dz));
      return DIRS[(int)Math.floor((deg + 22.5) / 45.0) & 7];
   }

   private static void drawThread(ServerLevel level, Vec3 from, Vec3 to, int steps) {
      for (int i = 0; i < steps; i++) {
         double t = (i + 1) / (double)steps;
         com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.FLAME, from.x + (to.x - from.x) * t, from.y + (to.y - from.y) * t, from.z + (to.z - from.z) * t, 1, 0.0, 0.0, 0.0, 0.0);
      }
   }
}