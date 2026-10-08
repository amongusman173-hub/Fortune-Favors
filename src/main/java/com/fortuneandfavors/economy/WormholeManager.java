package com.fortuneandfavors.economy;

import com.fortuneandfavors.ModItems;
import com.fortuneandfavors.ModSounds;
import com.fortuneandfavors.economy.WormholeManager.LastDeath;
import com.fortuneandfavors.menu.WormholeMenu;
import com.fortuneandfavors.util.Chat;
import com.fortuneandfavors.util.JsonUtil;
import com.fortuneandfavors.util.SoundUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.Map.Entry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ClickEvent.RunCommand;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap.Types;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.level.storage.LevelData.RespawnData;
import net.minecraft.world.phys.Vec3;
import net.minecraft.resources.ResourceKey;

public final class WormholeManager {
   public static final long COOLDOWN_MS = 30000L;
   public static final long ENDERPEARL_COOLDOWN_MS = 1000L;
   public static final int RTP_RADIUS = 250;
   public static final int MAX_WAYPOINTS = 3;
   /** Getting hit tags you for this long: warps (and opening the portal
    *  window) are refused while tagged - no menu, just an action-bar line. */
   public static final long COMBAT_TAG_MS = 3000L;
   private static final Map<UUID, UUID> pending = new HashMap<>();
   private static final Map<UUID, Long> cooldownUntil = new HashMap<>();
   private static final Map<UUID, Long> enderpearlCooldownUntil = new HashMap<>();
   private static final Map<UUID, Long> combatTagUntil = new HashMap<>();
   private static final Map<UUID, LastDeath> lastDeaths = new HashMap<>();
   private static final Map<UUID, List<Waypoint>> waypoints = new HashMap<>();
   private static Path dataFile;

   private WormholeManager() {
   }

   public static void onDisconnect(ServerPlayer player) {
      UUID uuid = player.getUUID();
      cooldownUntil.remove(uuid);
      enderpearlCooldownUntil.remove(uuid);
      combatTagUntil.remove(uuid);
      pending.values().removeIf(v -> v.equals(uuid));
      pending.remove(uuid);
   }

   // --- combat tag (3s of no-warp after taking a hit) ---

   /** Called when a player takes damage: arms/refreshes the 3s combat tag. */
   public static void tagCombat(ServerPlayer player) {
      if (player != null) {
         combatTagUntil.put(player.getUUID(), System.currentTimeMillis() + COMBAT_TAG_MS);
      }
   }

   public static boolean isCombatTagged(ServerPlayer player) {
      Long until = combatTagUntil.get(player.getUUID());
      if (until == null) {
         return false;
      }
      if (System.currentTimeMillis() >= until) {
         combatTagUntil.remove(player.getUUID());
         return false;
      }
      return true;
   }

   /** Shared refusal for every warp path while combat-tagged: closes the
    *  wormhole window if it's open and shows the reason on the action bar
    *  only - the player asked for silence, so no chat spam, no GUIs. */
   private static boolean refuseCombat(ServerPlayer player) {
      if (!isCombatTagged(player)) {
         return false;
      }
      if (player.containerMenu instanceof WormholeMenu) {
         player.closeContainer();
      }
      long leftMs = combatTagUntil.getOrDefault(player.getUUID(), System.currentTimeMillis()) - System.currentTimeMillis();
      int tenths = (int)Math.max(1, Math.round(leftMs / 100.0));
      player.sendSystemMessage(Component.literal("§c⚔ Combat tag - wormhole locked for " + (tenths / 10) + "." + (tenths % 10) + "s"), true);
      return true;
   }

   // --- Personal waypoints (max 3 per player) ---

   public static List<Waypoint> waypointsOf(ServerPlayer player) {
      return waypoints.getOrDefault(player.getUUID(), List.of());
   }

   public static Waypoint waypointAt(ServerPlayer player, int slot) {
      List<Waypoint> list = waypoints.get(player.getUUID());
      return list != null && slot >= 0 && slot < list.size() ? list.get(slot) : null;
   }

   /** Marks the player's current spot as waypoint {@code slot} (0-based). Setting is
    *  free - only teleporting consumes the potion. Returns false if it didn't stick. */
   public static boolean setWaypoint(ServerPlayer player, int slot) {
      if (refuseCombat(player)) {
         return false;
      }
      List<Waypoint> list = waypoints.computeIfAbsent(player.getUUID(), k -> new ArrayList<>());
      if (list.size() >= MAX_WAYPOINTS) {
         Chat.msg(player, "&cYou already have " + MAX_WAYPOINTS + " waypoints - remove one before setting another.");
         return false;
      }
      if (slot < list.size()) {
         Chat.msg(player, "&cWaypoint " + (slot + 1) + " is already set - right-click it to remove it first.");
         return false;
      }

      BlockPos pos = player.blockPosition();
      list.add(new Waypoint(player.level().dimension(), pos.immutable()));
      if (list.size() >= MAX_WAYPOINTS) {
         Advancements.grant(player, "wormhole_wayfarer");
      }
      save(player.level().getServer());
      playSetVfx(player, pos);
      Chat.raw(
         player,
         "&5Waypoint &f#"
            + (slot + 1)
            + "&5 set at &7"
            + dimLabel(player.level().dimension())
            + " ("
            + pos.getX()
            + ", "
            + pos.getY()
            + ", "
            + pos.getZ()
            + ")\n&7Teleport back anytime from the wormhole window (left-click)."
      );
      SoundUtil.play(player, ModSounds.WORMHOLE_OPEN);
      return true;
   }

   /** Particle ring + rising beacon at the newly anchored waypoint.
    *  Purple portal dust spirals outward while end-rod sparkles climb above it,
    *  capped at y=260 so topside anchors don't push particles out of build range. */
   private static void playSetVfx(ServerPlayer player, BlockPos pos) {
      ServerLevel level = player.level() instanceof ServerLevel sl ? sl : null;
      if (level == null) {
         return;
      }
      double cx = pos.getX() + 0.5;
      double cy = pos.getY() + 0.6;
      double cz = pos.getZ() + 0.5;
      DustParticleOptions dust = new DustParticleOptions(1858418, 1.6F); // portal purple
      for (int i = 0; i < 16; i++) {
         double a = Math.PI * 2.0 * i / 16.0;
         double ox = Math.cos(a) * 0.9;
         double oz = Math.sin(a) * 0.9;
         com.fortuneandfavors.net.FfVfx.particles(level, dust, cx + ox, cy, cz + oz, 1, 0.05, 0.1, 0.05, 0.0);
         if ((i & 1) == 0) {
            com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.REVERSE_PORTAL, cx + ox * 0.5, cy + 0.4, cz + oz * 0.5, 1, 0.05, 0.15, 0.05, 0.02);
         }
      }
      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.PORTAL, cx, cy + 0.5, cz, 24, 0.3, 0.4, 0.3, 0.4);
      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.END_ROD, cx, cy + 0.8, cz, 10, 0.2, 0.6, 0.2, 0.02);
      level.playSound(
         null, cx, cy, cz, SoundEvents.RESPAWN_ANCHOR_CHARGE, SoundSource.PLAYERS, 0.7F, 1.4F
      );
   }

   public static boolean removeWaypoint(ServerPlayer player, int slot) {
      List<Waypoint> list = waypoints.get(player.getUUID());
      if (list == null || slot < 0 || slot >= list.size()) {
         Chat.msg(player, "&cWaypoint " + (slot + 1) + " is empty.");
         return false;
      }

      list.remove(slot);
      save(player.level().getServer());
      Chat.msg(player, "&7Waypoint &f#" + (slot + 1) + "&7 removed.");
      SoundUtil.play(player, ModSounds.DENY);
      return true;
   }

   public static boolean clearWaypoints(ServerPlayer player) {
      List<Waypoint> list = waypoints.remove(player.getUUID());
      int count = list == null ? 0 : list.size();
      if (count > 0) {
         save(player.level().getServer());
         Chat.msg(player, "&7All " + count + " waypoint" + (count == 1 ? "" : "s") + " cleared.");
      } else {
         Chat.msg(player, "&7You have no waypoints to clear.");
      }
      SoundUtil.play(player, ModSounds.DENY);
      return count > 0;
   }

   /** Warps to a saved waypoint. Consumes a potion and arms the 30s cooldown. */
   public static boolean toWaypoint(ServerPlayer player, int slot) {
      if (refuseCombat(player)) {
         return false;
      }
      long wait = remainingCooldown(player);
      if (wait > 0L) {
         Chat.msg(player, "&cThe wormhole is recharging - wait " + (wait / 1000L + 1L) + "s before using another potion.");
         return false;
      }

      Waypoint wp = waypointAt(player, slot);
      if (wp == null) {
         Chat.msg(player, "&cWaypoint " + (slot + 1) + " is empty - set it first.");
         return false;
      }

      if (!consumePotion(player)) {
         Chat.msg(player, "&cYou don't have the wormhole potion in your inventory anymore.");
         return false;
      }

      MinecraftServer server = serverOf(player);
      ServerLevel targetLevel = server.getLevel(wp.dimension());
      if (targetLevel == null) {
         targetLevel = server.overworld();
      }

      BlockPos pos = wp.pos();
      if (!teleportTo(player, targetLevel, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, player.getYRot(), player.getXRot())) {
         return false;
      }

      armCooldown(player);
      Chat.raw(
         player,
         "&5&lWhoosh!&r &7You warped to waypoint &f#"
            + (slot + 1)
            + "&7 ("
            + dimLabel(wp.dimension())
            + " "
            + pos.getX()
            + ", "
            + pos.getY()
            + ", "
            + pos.getZ()
            + "). The wormhole is recharging for 30s."
      );
      return true;
   }

   private static String dimLabel(ResourceKey<Level> dim) {
      return dim.identifier().toString().replace("minecraft:", "").replace("fortuneandfavors:", "");
   }

   /** Loads saved waypoints from disk (called on server start). */
   public static void load(MinecraftServer server) {
      waypoints.clear();
      dataFile = EconomyManager.getDataDir(server).resolve("wormhole_waypoints.json");
      JsonObject root = JsonUtil.readOrCreate(dataFile, new JsonObject());
      if (root.has("players") && root.get("players").isJsonObject()) {
         for (Entry<String, JsonElement> e : root.getAsJsonObject("players").entrySet()) {
            try {
               UUID uuid = UUID.fromString(e.getKey());
               if (!e.getValue().isJsonArray()) {
                  continue;
               }

               List<Waypoint> list = new ArrayList<>();

               for (JsonElement el : e.getValue().getAsJsonArray()) {
                  if (list.size() >= MAX_WAYPOINTS || !el.isJsonObject()) {
                     break;
                  }

                  JsonObject obj = el.getAsJsonObject();
                  String dimStr = JsonUtil.jsonString(obj, "dim", "");
                  if (dimStr.isBlank()) {
                     continue;
                  }

                  ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION, Identifier.parse(dimStr));
                  int x = JsonUtil.jsonInt(obj, "x", 0);
                  int y = JsonUtil.jsonInt(obj, "y", 0);
                  int z = JsonUtil.jsonInt(obj, "z", 0);
                  list.add(new Waypoint(dim, new BlockPos(x, y, z)));
               }

               if (!list.isEmpty()) {
                  waypoints.put(uuid, list);
               }
            } catch (Exception ignored) {
            }
         }
      }

      // Last-death records (used by the wormhole's "last death" warp AND the
      // Death Compass) also survive restarts.
      if (root.has("deaths") && root.get("deaths").isJsonObject()) {
         for (Entry<String, JsonElement> e : root.getAsJsonObject("deaths").entrySet()) {
            try {
               UUID uuid = UUID.fromString(e.getKey());
               if (!e.getValue().isJsonObject()) {
                  continue;
               }

               JsonObject obj = e.getValue().getAsJsonObject();
               String dimStr = JsonUtil.jsonString(obj, "dim", "");
               if (dimStr.isBlank()) {
                  continue;
               }

               ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION, Identifier.parse(dimStr));
               int x = JsonUtil.jsonInt(obj, "x", 0);
               int y = JsonUtil.jsonInt(obj, "y", 0);
               int z = JsonUtil.jsonInt(obj, "z", 0);
               lastDeaths.put(uuid, new LastDeath(dim, new BlockPos(x, y, z)));
            } catch (Exception ignored) {
            }
         }
      }
   }

   /** Persists every player's waypoints to disk (autosave / disconnect / stop). */
   public static void save(MinecraftServer server) {
      if (dataFile == null) {
         dataFile = EconomyManager.getDataDir(server).resolve("wormhole_waypoints.json");
      }

      JsonObject root = new JsonObject();
      JsonObject players = new JsonObject();

      for (Entry<UUID, List<Waypoint>> e : waypoints.entrySet()) {
         if (e.getValue() == null || e.getValue().isEmpty()) {
            continue;
         }

         JsonArray arr = new JsonArray();

         for (Waypoint wp : e.getValue()) {
            JsonObject obj = new JsonObject();
            obj.addProperty("dim", wp.dimension().identifier().toString());
            obj.addProperty("x", wp.pos().getX());
            obj.addProperty("y", wp.pos().getY());
            obj.addProperty("z", wp.pos().getZ());
            arr.add(obj);
         }

         players.add(e.getKey().toString(), arr);
      }

      root.add("players", players);

      JsonObject deaths = new JsonObject();

      for (Entry<UUID, LastDeath> e : lastDeaths.entrySet()) {
         if (e.getValue() == null) {
            continue;
         }

         JsonObject obj = new JsonObject();
         obj.addProperty("dim", e.getValue().dimension().identifier().toString());
         obj.addProperty("x", e.getValue().pos().getX());
         obj.addProperty("y", e.getValue().pos().getY());
         obj.addProperty("z", e.getValue().pos().getZ());
         deaths.add(e.getKey().toString(), obj);
      }

      root.add("deaths", deaths);
      JsonUtil.write(dataFile, root);
   }

   public static void recordDeath(ServerPlayer player) {
      if (player != null) {
         lastDeaths.put(player.getUUID(), new LastDeath(player.level().dimension(), player.blockPosition()));
         save(player.level().getServer());
      }
   }

   public static LastDeath getLastDeath(ServerPlayer player) {
      return lastDeaths.get(player.getUUID());
   }

   /** Forgets a player's death record - called once they retrieve their grave. */
   public static void clearDeath(ServerPlayer player) {
      if (player != null) {
         if (lastDeaths.remove(player.getUUID()) != null) {
            save(player.level().getServer());
         }
      }
   }

   public static boolean toLastDeath(ServerPlayer player) {
      if (refuseCombat(player)) {
         return false;
      }
      long wait = remainingCooldown(player);
      if (wait > 0L) {
         Chat.msg(player, "&cThe wormhole is recharging - wait " + (wait / 1000L + 1L) + "s before using another potion.");
         return false;
      }

      LastDeath death = lastDeaths.get(player.getUUID());
      if (death == null) {
         Chat.msg(player, "&cYou haven't died yet - no death location to warp to.");
         return false;
      }

      if (!consumePotion(player)) {
         Chat.msg(player, "&cYou don't have the wormhole potion in your inventory anymore.");
         return false;
      }

      MinecraftServer server = serverOf(player);
      ServerLevel targetLevel = server.getLevel(death.dimension());
      if (targetLevel == null) {
         targetLevel = server.overworld();
      }

      BlockPos pos = findSafePos(targetLevel, death.pos());
      if (!teleportTo(player, targetLevel, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, player.getYRot(), player.getXRot())) {
         return false;
      }

      float currentHp = player.getHealth();
      float cost = currentHp * 0.5F;
      float newHp = Math.max(1.0F, currentHp - cost);
      player.setHealth(newHp);
      armCooldown(player);
      Chat.raw(player, "&5&lWhoosh!&r &7You warped to your last death location! &cThe wormhole drained 50% of your health. &7Recharging for 30s.");
      Advancements.grant(player, "wormhole_death");
      return true;
   }

   public static boolean open(ServerPlayer player) {
      if (refuseCombat(player)) {
         return false;
      }
      long wait = remainingCooldown(player);
      if (wait > 0L) {
         Chat.msg(player, "&cThe wormhole is recharging - wait " + (wait / 1000L + 1L) + "s before using another potion.");
         return false;
      }

      SoundUtil.play(player, ModSounds.WORMHOLE_OPEN);
      ServerLevel level = player.level();
      double x = player.getX();
      double y = player.getY() + 1.0;
      double z = player.getZ();

      for (int i = 0; i < 32; i++) {
         double a = (Math.PI * 2) * i / 32.0;
         double r = 0.3 + 0.6 * (i / 32.0);
         com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.PORTAL, x + Math.cos(a) * r, y + 0.2, z + Math.sin(a) * r, 1, 0.0, 0.4, 0.0, 0.02);
      }

      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.END_ROD, x, y + 0.5, z, 8, 0.2, 0.3, 0.2, 0.04);
      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.ENCHANT, x, y, z, 16, 0.4, 0.5, 0.4, 0.08);
      portalRing(level, x, y, z, 12);
      WormholeMenu.open(player);
      return true;
   }

   public static boolean request(ServerPlayer requester, ServerPlayer target) {
      if (requester.getUUID().equals(target.getUUID())) {
         Chat.msg(requester, "&cYou're already at your own location - pick another player.");
         return false;
      } else if (requestFor(requester.getUUID()) != null) {
         Chat.msg(requester, "&cYou already have a pending wormhole request. Wait for it to be answered.");
         return false;
      } else {
         pending.put(target.getUUID(), requester.getUUID());
         SoundUtil.play(requester, ModSounds.WORMHOLE_OPEN);
         Chat.raw(requester, "&5Wormhole request sent to &f" + target.getName().getString() + "&5. The potion is consumed only if they accept.");
         Component accept = Component.literal("§a[ACCEPT]").withStyle(s -> s.withClickEvent(new RunCommand("/wormhole accept")));
         Component deny = Component.literal("§c[DENY]").withStyle(s -> s.withClickEvent(new RunCommand("/wormhole deny")));
         target.sendSystemMessage(
            Component.literal(
                  "§5"
                     + requester.getName().getString()
                     + "§7 wants to teleport to you through a wormhole potion! Type §f/wormhole accept§7 to accept or §f/wormhole deny§7 to refuse: "
               )
               .append(accept)
               .append(Component.literal(" "))
               .append(deny)
         );
         return true;
      }
   }

   private static UUID requestFor(UUID uuid) {
      for (Entry<UUID, UUID> e : pending.entrySet()) {
         if (e.getValue().equals(uuid)) {
            return e.getKey();
         }
      }

      return null;
   }

   public static boolean accept(ServerPlayer target) {
      UUID requesterId = pending.get(target.getUUID());
      if (requesterId == null) {
         Chat.msg(target, "&cYou have no pending wormhole requests.");
         return false;
      }

      ServerPlayer requester = serverOf(target).getPlayerList().getPlayer(requesterId);
      if (requester == null) {
         pending.remove(target.getUUID());
         Chat.msg(target, "&cThat player is no longer online.");
         return false;
      }

      if (refuseCombat(requester)) {
         Chat.raw(target, "&c" + requester.getName().getString() + "&c is in combat and can't warp right now.");
         return false;
      }

      long wait = remainingCooldown(requester);
      if (wait > 0L) {
         Chat.raw(
            target,
            "&c" + requester.getName().getString() + "&c can't warp yet - their wormhole is recharging (" + (wait / 1000L + 1L) + "s). Try again in a moment."
         );
         Chat.msg(requester, "&7Your wormhole is still recharging - the request is waiting.");
         return false;
      }

      pending.remove(target.getUUID());
      if (!consumePotion(requester)) {
         Chat.msg(target, "&c" + requester.getName().getString() + " no longer has the wormhole potion.");
         return false;
      }

      if (!teleportTo(requester, target.level(), target.getX(), target.getY(), target.getZ(), target.getYRot(), target.getXRot())) {
         return false;
      }

      armCooldown(requester);
      Chat.raw(requester, "&5&lWhoosh!&r &7You warped to &f" + target.getName().getString() + "&7. The wormhole is recharging for 30s.");
      Chat.raw(target, "&7" + requester.getName().getString() + "&7 warped to you through a wormhole potion.");
      return true;
   }

   public static boolean deny(ServerPlayer target) {
      UUID requesterId = pending.remove(target.getUUID());
      if (requesterId == null) {
         Chat.msg(target, "&cYou have no pending wormhole requests.");
         return false;
      }

      ServerPlayer requester = serverOf(target).getPlayerList().getPlayer(requesterId);
      if (requester != null) {
         Chat.raw(requester, "&c" + target.getName().getString() + "&c declined the wormhole. You keep the potion.");
      }

      Chat.msg(target, "&7Wormhole request declined.");
      return true;
   }

   public static boolean toRespawn(ServerPlayer player) {
      if (refuseCombat(player)) {
         return false;
      }
      long wait = remainingCooldown(player);
      if (wait > 0L) {
         Chat.msg(player, "&cThe wormhole is recharging - wait " + (wait / 1000L + 1L) + "s before using another potion.");
         return false;
      }

      if (!consumePotion(player)) {
         Chat.msg(player, "&cYou don't have the wormhole potion in your inventory anymore.");
         return false;
      }

      // Ask the game where the respawn really is rather than reading the stored anchor.
      // getRespawnConfig().respawnData().pos() is the *anchor block* - the bed itself, the
      // charged respawn anchor, or the world spawn column - and being at it is not the same
      // as standing on it: for a bed, vanilla steps the body off the mattress onto the floor
      // beside it, and for the world spawn it finds the first clear pair of blocks. Reading
      // the anchor and teleporting to it put the player inside the bed, which is what
      // "warping to your respawn point" stopped working. This runs the game's own search
      // (the one a real respawn runs) and warps to what it finds; the false flag leaves a
      // charged respawn anchor charged, because a warp is not a death.
      TeleportTransition to = player.findRespawnPositionAndUseSpawnBlock(
         false, TeleportTransition.PLACE_PORTAL_TICKET
      );
      if (to == null || to.newLevel() == null) {
         Chat.msg(player, "&cYour respawn point isn't available right now.");
         return false;
      }

      Vec3 where = to.position();
      if (!teleportTo(player, to.newLevel(), where.x, where.y, where.z, to.yRot(), to.xRot())) {
         return false;
      }

      armCooldown(player);
      Chat.raw(player, "&5&lWhoosh!&r &7You warped back to your respawn point. The wormhole is recharging for 30s.");
      return true;
   }

   public static boolean toRandomSurface(ServerPlayer player) {
      if (refuseCombat(player)) {
         return false;
      }
      long wait = remainingCooldown(player);
      if (wait > 0L) {
         Chat.msg(player, "&cThe wormhole is recharging - wait " + (wait / 1000L + 1L) + "s before using another potion.");
         return false;
      }

      if (!consumePotion(player)) {
         Chat.msg(player, "&cYou don't have the wormhole potion in your inventory anymore.");
         return false;
      }

      ServerLevel level = player.level();
      Random rng = new Random();
      // Random surface scatter, now a full 1000 blocks out in any direction.
      int dx = rng.nextInt(2001) - 1000;
      int dz = rng.nextInt(2001) - 1000;
      int baseX = player.blockPosition().getX() + dx;
      int baseZ = player.blockPosition().getZ() + dz;
      BlockPos best = null;

      for (int attempt = 0; attempt < 8; attempt++) {
         int cx = baseX + rng.nextInt(17) - 8;
         int cz = baseZ + rng.nextInt(17) - 8;
         BlockPos top = level.getHeightmapPos(Types.MOTION_BLOCKING, new BlockPos(cx, 320, cz));
         if (top.getY() >= 0) {
            BlockPos feet = top.above();
            if (level.getBlockState(feet).isAir()
               && level.getBlockState(feet.above()).isAir()
               && !level.getBlockState(feet.below()).getFluidState().is(Fluids.LAVA)) {
               best = feet;
               break;
            }
         }
      }

      if (best == null) {
         RespawnData rd = level.getRespawnData();
         BlockPos spawn = rd != null && rd.pos() != null ? rd.pos() : new BlockPos(0, 64, 0);
         best = new BlockPos(spawn.getX(), level.getHeightmapPos(Types.MOTION_BLOCKING, spawn).getY() + 1, spawn.getZ());
      }

      if (!teleportTo(player, level, best.getX() + 0.5, best.getY(), best.getZ() + 0.5, player.getYRot(), player.getXRot())) {
         return false;
      }

      armCooldown(player);
      Chat.raw(player, "&5&lWhoosh!&r &7The wormhole scattered you to a random surface location! Recharging for 30s.");
      return true;
   }

   private static BlockPos findSafePos(ServerLevel level, BlockPos deathPos) {
      for (int dy = 0; dy >= -10; dy--) {
         BlockPos check = deathPos.below(dy);
         BlockPos feet = check.above();
         BlockPos head = check.above(2);
         if (!level.getBlockState(check).isAir()
            && level.getBlockState(feet).isAir()
            && level.getBlockState(head).isAir()
            && !isHazard(level, check)
            && !isHazard(level, feet)) {
            return feet;
         }
      }

      for (int dy = 1; dy <= 10; dy++) {
         BlockPos check = deathPos.above(dy);
         BlockPos feet = check;
         BlockPos head = check.above();
         BlockPos below = check.below();
         if (!level.getBlockState(below).isAir()
            && level.getBlockState(feet).isAir()
            && level.getBlockState(head).isAir()
            && !isHazard(level, feet)
            && !isHazard(level, head)) {
            return feet;
         }
      }

      BlockPos surface = level.getHeightmapPos(Types.MOTION_BLOCKING, deathPos);
      return surface.above();
   }

   private static boolean isHazard(ServerLevel level, BlockPos pos) {
      BlockState state = level.getBlockState(pos);
      return state.getFluidState().is(Fluids.LAVA) || state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE) || state.is(Blocks.MAGMA_BLOCK);
   }

   private static long remainingCooldown(ServerPlayer player) {
      Long until = cooldownUntil.get(player.getUUID());
      if (until == null) {
         return 0L;
      } else {
         long left = until - System.currentTimeMillis();
         if (left <= 0L) {
            cooldownUntil.remove(player.getUUID());
            return 0L;
         } else {
            return left;
         }
      }
   }

   private static void armCooldown(ServerPlayer player) {
      cooldownUntil.put(player.getUUID(), System.currentTimeMillis() + 30000L);
   }

   private static MinecraftServer serverOf(ServerPlayer player) {
      return player.level().getServer();
   }

   private static boolean consumePotion(ServerPlayer player) {
      for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
         ItemStack stack = player.getInventory().getItem(i);
         if (ModItems.isWormholePotion(stack)) {
            stack.shrink(1);
            player.getInventory().setChanged();
            return true;
         }
      }

      return false;
   }

   private static void portalRing(ServerLevel level, double cx, double cy, double cz, int points) {
      for (int i = 0; i < points; i++) {
         double a = (Math.PI * 2) * i / points;
         com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.REVERSE_PORTAL, cx + Math.cos(a) * 1.3, cy + 0.1, cz + Math.sin(a) * 1.3, 1, 0.0, 0.0, 0.0, 0.0);
         double tilt = 0.6;
         com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.PORTAL, cx + Math.cos(a) * 1.0, cy + 0.5 + Math.sin(a) * Math.sin(tilt) * 1.0, cz + Math.sin(a) * 1.0, 1, 0.0, 0.0, 0.0, 0.0
         );
         com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.SOUL_FIRE_FLAME, cx + Math.cos(a) * 1.6, cy + 0.1, cz + Math.sin(a) * 1.6, 1, 0.0, 0.15, 0.0, 0.01);
      }
   }

   private static boolean teleportTo(ServerPlayer player, Level targetLevel, double x, double y, double z, float yaw, float pitch) {
      if (!(targetLevel instanceof ServerLevel serverLevel)) {
         Chat.msg(player, "&cThat location isn't available right now.");
         return false;
      } else {
         ServerLevel from = player.level();
         double fx = player.getX();
         double fy = player.getY() + 1.0;
         double fz = player.getZ();
         com.fortuneandfavors.net.FfVfx.shape(from, com.fortuneandfavors.net.FfVfx.WORMHOLE, ParticleTypes.PORTAL, new Vec3(fx, fy - 1.0, fz), Vec3.ZERO, 0.0, 0.0, 0x9B4DFF);
         // From here to the jump, what is drawn is the vanilla clients' version of the cue above.
         com.fortuneandfavors.net.FfVfx.enter();

         for (int ringColors = 0; ringColors < 36; ringColors++) {
            double a = (Math.PI * 2) * ringColors / 36.0;
            double r = 1.7 - 1.1 * (ringColors / 36.0);
            double h = ringColors % 6 * 0.35;
            com.fortuneandfavors.net.FfVfx.particles(from, ParticleTypes.REVERSE_PORTAL, fx + Math.cos(a) * r, fy - 0.2 + h, fz + Math.sin(a) * r, 1, 0.0, 0.0, 0.0, 0.0);
            com.fortuneandfavors.net.FfVfx.particles(from, ParticleTypes.PORTAL, fx + Math.cos(a) * (r + 0.5), fy - 0.2 + h + 0.2, fz + Math.sin(a) * (r + 0.5), 1, 0.0, 0.0, 0.0, 0.0);
         }

         for (int i = 0; i < 28; i++) {
            double a = (Math.PI * 2) * i / 28.0;
            double r = 0.8 + i % 4 * 0.25;
            com.fortuneandfavors.net.FfVfx.particles(from, new DustParticleOptions(11167487, 1.2F), fx + Math.cos(a) * r, fy + 0.4 + i % 5 * 0.25, fz + Math.sin(a) * r, 1, 0.0, 0.0, 0.0, 0.0
            );
         }

         com.fortuneandfavors.net.FfVfx.particles(from, ParticleTypes.ELECTRIC_SPARK, fx, fy + 0.5, fz, 18, 1.2, 1.0, 1.2, 0.01);
         com.fortuneandfavors.net.FfVfx.particles(from, ParticleTypes.ENCHANT, fx, fy, fz, 30, 0.6, 1.0, 0.6, 0.06);
         com.fortuneandfavors.net.FfVfx.particles(from, ParticleTypes.GLOW, fx, fy + 0.6, fz, 16, 0.7, 0.8, 0.7, 0.02);
         com.fortuneandfavors.net.FfVfx.particles(from, ColorParticleOption.create(ParticleTypes.FLASH, 16777215), fx, fy + 1.0, fz, 1, 0.0, 0.0, 0.0, 0.0);
         portalRing(from, fx, fy, fz, 16);
         from.playSound(null, fx, fy, fz, SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 1.2F, 0.8F);
         from.playSound(null, fx, fy, fz, SoundEvents.PORTAL_TRAVEL, SoundSource.PLAYERS, 0.5F, 1.2F);
         if (player.containerMenu instanceof WormholeMenu) {
            player.closeContainer();
         }

         com.fortuneandfavors.net.FfVfx.exit();
         player.teleport(new TeleportTransition(serverLevel, new Vec3(x, y, z), Vec3.ZERO, yaw, pitch, TeleportTransition.PLACE_PORTAL_TICKET));
         com.fortuneandfavors.net.FfVfx.shape(serverLevel, com.fortuneandfavors.net.FfVfx.WORMHOLE, ParticleTypes.PORTAL, new Vec3(x, y, z), Vec3.ZERO, 0.0, 1.0, 0x9B4DFF);
         SoundUtil.play(player, ModSounds.ELEVATOR);

         for (int i = 0; i < 42; i++) {
            double a = (Math.PI * 2) * i / 42.0;
            double r = 0.6 + 1.6 * (i % 5) / 4.0;
            com.fortuneandfavors.net.FfVfx.particles(serverLevel, ParticleTypes.REVERSE_PORTAL, x + Math.cos(a) * r, y + 0.2 + i % 3 * 0.3, z + Math.sin(a) * r, 1, 0.0, 0.0, 0.0, 0.0);
            com.fortuneandfavors.net.FfVfx.particles(serverLevel, ParticleTypes.END_ROD, x + Math.cos(a) * (r * 0.7), y + 0.1 + (i % 7) * 0.25, z + Math.sin(a) * (r * 0.7), 1, 0.0, 0.02, 0.0, 0.01);
         }
         com.fortuneandfavors.net.FfVfx.particles(serverLevel, ColorParticleOption.create(ParticleTypes.FLASH, 0xAA44FF), x, y + 1.0, z, 2, 0.0, 0.0, 0.0, 0.0);
         com.fortuneandfavors.net.FfVfx.particles(serverLevel, ParticleTypes.SOUL_FIRE_FLAME, x, y + 0.5, z, 20, 0.5, 0.6, 0.5, 0.03);
         com.fortuneandfavors.net.FfVfx.particles(serverLevel, net.minecraft.core.particles.PowerParticleOption.create(ParticleTypes.DRAGON_BREATH, 1.0F), x, y + 0.8, z, 15, 0.4, 0.5, 0.4, 0.02);
         com.fortuneandfavors.net.FfVfx.particles(serverLevel, ParticleTypes.PORTAL, x, y + 0.5, z, 35, 0.8, 1.0, 0.8, 0.3);

         int[] ringColors = new int[]{11167487, 16737996, 6728447};

         for (int ring = 0; ring < ringColors.length; ring++) {
            double r0 = 0.8 + ring * 0.9;

            for (int i = 0; i < 24; i++) {
               double a = (Math.PI * 2) * i / 24.0;
               com.fortuneandfavors.net.FfVfx.particles(serverLevel, new DustParticleOptions(ringColors[ring], 1.4F), x + Math.cos(a) * r0, y + 0.4, z + Math.sin(a) * r0, 1, 0.0, 0.0, 0.0, 0.0
               );
               com.fortuneandfavors.net.FfVfx.particles(serverLevel, new DustParticleOptions(ringColors[ring], 1.1F),
                  x + Math.cos(a) * r0 * 0.7,
                  y + 1.6 + ring * 0.5,
                  z + Math.sin(a) * r0 * 0.7,
                  1,
                  0.0,
                  0.0,
                  0.0,
                  0.0
               );
            }
         }

         for (int i = 0; i < 30; i++) {
            double a = (Math.PI * 2) * i / 30.0;
            double r = 0.5 + 0.4 * Math.sin(i * 0.7);
            com.fortuneandfavors.net.FfVfx.particles(serverLevel, ParticleTypes.PORTAL, x + Math.cos(a) * r, y + 0.3 + i % 10 * 0.18, z + Math.sin(a) * r, 1, 0.0, 0.0, 0.0, 0.0);
         }

         com.fortuneandfavors.net.FfVfx.particles(serverLevel, ParticleTypes.SONIC_BOOM, x, y + 0.4, z, 1, 0.0, 0.0, 0.0, 0.0);
         com.fortuneandfavors.net.FfVfx.particles(serverLevel, ParticleTypes.SONIC_BOOM, x, y + 1.4, z, 1, 0.0, 0.0, 0.0, 0.0);
         com.fortuneandfavors.net.FfVfx.particles(serverLevel, ColorParticleOption.create(ParticleTypes.FLASH, 16777215), x, y + 1.0, z, 1, 0.0, 0.0, 0.0, 0.0);
         com.fortuneandfavors.net.FfVfx.particles(serverLevel, ParticleTypes.END_ROD, x, y + 0.8, z, 24, 0.6, 1.0, 0.6, 0.05);
         com.fortuneandfavors.net.FfVfx.particles(serverLevel, ParticleTypes.SOUL_FIRE_FLAME, x, y + 0.5, z, 18, 0.5, 0.8, 0.5, 0.02);
         com.fortuneandfavors.net.FfVfx.particles(serverLevel, ParticleTypes.GLOW, x, y + 1.2, z, 20, 0.8, 1.0, 0.8, 0.03);
         com.fortuneandfavors.net.FfVfx.particles(serverLevel, ParticleTypes.ENCHANT, x, y, z, 36, 0.8, 1.4, 0.8, 0.09);
         portalRing(serverLevel, x, y, z, 20);
         serverLevel.playSound(null, x, y, z, SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 1.5F, 0.7F);
         serverLevel.playSound(null, x, y, z, SoundEvents.PORTAL_TRAVEL, SoundSource.PLAYERS, 0.6F, 1.0F);
         serverLevel.playSound(null, x, y, z, SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.PLAYERS, 0.3F, 1.5F);
         return true;
      }
   }

   public static boolean isEnderpearlCooling(ServerPlayer player) {
      Long until = enderpearlCooldownUntil.get(player.getUUID());
      if (until == null) {
         return false;
      }

      if (System.currentTimeMillis() < until) {
         return true;
      }

      enderpearlCooldownUntil.remove(player.getUUID());
      return false;
   }

   public static void armEnderpearlCooldown(ServerPlayer player) {
      enderpearlCooldownUntil.put(player.getUUID(), System.currentTimeMillis() + 1000L);
   }

   public static void tickCooldownDisplay(ServerPlayer player) {
      long wait = remainingCooldown(player);
      if (wait > 0L) {
         int total = 30;
         int remaining = (int)Math.ceil(wait / 1000.0);
         int filled = total - remaining;
         StringBuilder bar = new StringBuilder("§5§l⟐ ");

         for (int i = 0; i < total; i++) {
            bar.append(i < filled ? "§a▎" : "§8▎");
         }

         bar.append(" §7").append(remaining).append("s §5§l⟐");
         player.sendSystemMessage(Component.literal(bar.toString()), true);
      }
   }


    record LastDeath(ResourceKey<Level> dimension, BlockPos pos) {
    }

    public record Waypoint(ResourceKey<Level> dimension, BlockPos pos) {
    }
}
