package com.fortuneandfavors.map;

import com.fortuneandfavors.FortuneFavorsMod;
import com.fortuneandfavors.duel.DuelManager;
import com.fortuneandfavors.economy.ClaimManager;
import com.fortuneandfavors.map.MapEditor.BlockData;
import com.fortuneandfavors.map.MapEditor.CustomMap;
import com.fortuneandfavors.map.MapEditor.EditSession;
import com.fortuneandfavors.map.MapEditor.MarkerDef;
import com.fortuneandfavors.map.MapEditor.SavedEditor;
import com.fortuneandfavors.util.Chat;
import com.fortuneandfavors.util.JsonUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Map.Entry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.resources.ResourceKey;

public final class MapEditor {
   private static final String MARKER_TAG = "ffmap";
   private static final String MARKER_KEY = "ffmap_marker";
   public static final String MODE_ARENA = "arena";
   public static final String MODE_SKYWARS = "skywars";
   public static final String MODE_BEDWARS = "bedwars";
   public static final String MODE_LUCKYPVP = "luckypvp";
   public static final String MODE_LEGACY = "arena";
   public static final String MODE_MODERN = "arena";
   private static final MarkerDef[] MARKERS = new MarkerDef[]{
      new MarkerDef("p1spawn", (Item)Items.WOOL.pick(DyeColor.RED), "§c§lPlayer 1 Spawn", "§7Place where player 1 starts"),
      new MarkerDef("p2spawn", (Item)Items.WOOL.pick(DyeColor.BLUE), "§9§lPlayer 2 Spawn", "§7Place where player 2 starts"),
      new MarkerDef("bed", (Item)Items.BED.pick(DyeColor.RED), "§e§lBed Location", "§7Bed Wars only - the team bed"),
      new MarkerDef("gen_iron", Items.IRON_BLOCK, "§f§lGenerator (Iron)", "§7Team forge / iron generator"),
      new MarkerDef("gen_diamond", Items.DIAMOND_BLOCK, "§b§lGenerator (Diamond)", "§7Diamond generator"),
      new MarkerDef("gen_emerald", Items.EMERALD_BLOCK, "§a§lGenerator (Emerald)", "§7Emerald generator"),
      new MarkerDef("shop", Items.GOLD_BLOCK, "§6§lItem Shop", "§7Bed Wars only - item shop villager"),
      new MarkerDef("upgrade", Items.LAPIS_BLOCK, "§9§lUpgrade Villager", "§7Bed Wars only - upgrades villager"),
      new MarkerDef("chest", Items.ENDER_CHEST, "§5§lTeam Ender Chest", "§7Bed Wars only - left-click to instant-store"),
      new MarkerDef("chest_spawn", Items.CHEST, "§e§lSpawn Chest", "§7Sky Wars only - island starter chest (looted once)"),
      new MarkerDef("chest_middle", Items.TRAPPED_CHEST, "§a§lMiddle Chest", "§7Sky Wars only - refills every minute"),
      new MarkerDef("spectator", Items.GLOWSTONE, "§e§lSpectator Spawn", "§7Spectators are TP'd here when they watch a duel")
   };
   private static final Map<UUID, EditSession> sessions = new HashMap<>();
   private static final Map<String, CustomMap> customMaps = new HashMap<>();
   private static MinecraftServer serverRef;

   private static MarkerDef def(String key) {
      for (MarkerDef m : MARKERS) {
         if (m.key.equals(key)) {
            return m;
         }
      }

      return null;
   }

   private static MarkerDef[] markersFor(String mode) {
      if ("bedwars".equals(mode)) {
         return MARKERS;
      } else {
         return "skywars".equals(mode)
            ? new MarkerDef[]{def("p1spawn"), def("p2spawn"), def("chest_spawn"), def("chest_middle")}
            : new MarkerDef[]{def("p1spawn"), def("p2spawn"), def("spectator")};
      }
   }

   private MapEditor() {
   }

   public static CustomMap customMapFor(String mode) {
      return customMaps.get(mode);
   }

   public static void load(MinecraftServer server) {
      serverRef = server;
      customMaps.clear();
      Path dir = server.getWorldPath(LevelResource.ROOT).resolve("fortuneandfavors").resolve("maps");
      File mapFolder = dir.toFile();
      File[] files = null;
      if (mapFolder.isDirectory()) {
         files = mapFolder.listFiles((d, name) -> name.endsWith(".json"));
      }

      if (files == null) {
         files = new File[0];
      }

      for (File f : files) {
         try {
            String mode = f.getName().substring(0, f.getName().length() - 5);
            JsonObject root = JsonUtil.readOrCreate(f.toPath(), new JsonObject());
            CustomMap map = parseCustomMap(root);
            if (map != null && !map.blocks.isEmpty()) {
               customMaps.put(mode, map);
               FortuneFavorsMod.LOGGER.info("Fortune & Favors: loaded custom {} map ({} blocks)", mode, map.blocks.size());
            }
         } catch (Exception var14) {
         }
      }

      if (!customMaps.containsKey("arena")) {
         CustomMap legacy = customMaps.get("legacy");
         CustomMap modern = customMaps.get("modern");
         if (legacy != null) {
            customMaps.put("arena", legacy);
         } else if (modern != null) {
            customMaps.put("arena", modern);
         }
      }

      if (!customMaps.containsKey("arena")) {
         try (InputStream is = MapEditor.class.getResourceAsStream("/data/fortuneandfavors/maps/arena.json")) {
            if (is != null) {
               String json = new String(is.readAllBytes(), StandardCharsets.UTF_8);
               JsonParser parser = new JsonParser();
               JsonObject root = parser.parse(json).getAsJsonObject();
               CustomMap map = parseCustomMap(root);
               if (map != null && !map.blocks.isEmpty()) {
                  customMaps.put("arena", map);
                  FortuneFavorsMod.LOGGER.info("Fortune & Favors: loaded default arena map ({} blocks)", map.blocks.size());

                  try {
                     Path mapsDir = server.getWorldPath(LevelResource.ROOT).resolve("fortuneandfavors").resolve("maps");
                     File mapsFolder = mapsDir.toFile();
                     if (!mapsFolder.isDirectory()) {
                        mapsFolder.mkdirs();
                     }

                     Path arenaFile = mapsDir.resolve("arena.json");
                     if (!arenaFile.toFile().exists()) {
                        Files.writeString(arenaFile, json);
                        FortuneFavorsMod.LOGGER.info("Fortune & Favors: created default arena.json in maps folder");
                     }
                  } catch (Exception e) {
                     FortuneFavorsMod.LOGGER.warn("Fortune & Favors: could not write default arena.json to maps folder", e);
                  }
               }
            }
         } catch (Exception var16) {
         }
      }
   }

   private static CustomMap parseCustomMap(JsonObject root) {
      if (root != null && root.has("mode")) {
         String mode = root.get("mode").getAsString();
         CustomMap map = new CustomMap(mode);
         if (root.has("blocks")) {
            for (JsonElement el : root.getAsJsonArray("blocks")) {
               try {
                  JsonObject b = el.getAsJsonObject();
                  int x = b.get("x").getAsInt();
                  int y = b.get("y").getAsInt();
                  int z = b.get("z").getAsInt();
                  String block = b.get("block").getAsString();
                  Map<String, String> props = new LinkedHashMap<>();
                  if (b.has("props")) {
                     for (Entry<String, JsonElement> pe : b.getAsJsonObject("props").entrySet()) {
                        props.put(pe.getKey(), pe.getValue().getAsString());
                     }
                  }

                  map.blocks.add(new BlockData(x, y, z, block, props));
                  map.minX = Math.min(map.minX, x);
                  map.maxX = Math.max(map.maxX, x);
                  map.minZ = Math.min(map.minZ, z);
                  map.maxZ = Math.max(map.maxZ, z);
               } catch (Exception var13) {
               }
            }
         }

         if (root.has("markers")) {
            for (Entry<String, JsonElement> me : root.getAsJsonObject("markers").entrySet()) {
               List<int[]> list = map.markers.computeIfAbsent(me.getKey(), k -> new ArrayList<>());

               for (JsonElement el : me.getValue().getAsJsonArray()) {
                  JsonArray arr = el.getAsJsonArray();
                  list.add(new int[]{arr.get(0).getAsInt(), arr.get(1).getAsInt(), arr.get(2).getAsInt()});
               }
            }
         }

         return map;
      } else {
         return null;
      }
   }

   public static String start(MinecraftServer server, ServerPlayer host, String mode) {
      String key = normalizeMode(mode);
      if (key == null) {
         return "Unknown map style. Use: 1.8arena, modernarena, other, luckypvp (all share one arena map), skywars, or bedwars.";
      }

      if (sessionOf(host.getUUID()) != null) {
         return "You're already editing a map - /mapexport when you're done.";
      }

      ServerLevel realm = server.getLevel(DuelManager.DUEL_REALM);
      if (realm == null) {
         return "The arena realm isn't available right now.";
      }

      int plot = DuelManager.claimPlot();
      int ox = 16 + plot % 8 * 200;
      int oz = 16 + plot / 8 * 200;
      EditSession session = new EditSession(key, ox, oz, host.getUUID());
      // Only the plot being edited is cleared, and buildEditPreview is what clears it. This
      // used to start by clearing the *whole realm* - 132x132 chunks of it, 17,424 chunk loads
      // spread over a couple of minutes - to prepare one square of ground: it took a live
      // match's dropped items, villager shops and every other plot's leftovers with it, and it
      // ran on the same server the whole time. The plot is cleared again when a match claims it,
      // so nothing depends on the realm being empty behind the editor's back.
      DuelManager.buildEditPreview(realm, key, ox, oz);
      if ("bedwars".equals(key) || "skywars".equals(key)) {
         CustomMap cm = customMapFor(key);
         if (cm != null && !cm.markers.isEmpty()) {
            int dx = ox + 50 - cm.centerX();
            int dz = oz + 50 - cm.centerZ();

            for (Entry<String, List<int[]>> e : cm.markers.entrySet()) {
               for (int[] m : e.getValue()) {
                  session.markers.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(new BlockPos(m[0] + dx, m[1], m[2] + dz));
               }
            }
         } else if ("bedwars".equals(key)) {
            DuelManager.premarkBedwars(ox, oz, (marker, pos) -> ((List)session.markers.get(marker)).add(pos));
         } else {
            DuelManager.premarkSkywars(ox, oz, (marker, pos) -> ((List)session.markers.get(marker)).add(pos));
         }
      }

      boolean any = false;

      for (ServerPlayer p : server.getPlayerList().getPlayers()) {
         if ((p == host || isOp(p)) && !DuelManager.isInDuel(p.getUUID())) {
            enterSession(session, realm, p);
            any = true;
         }
      }

      if (!any) {
         DuelManager.releasePlot(ox, oz);
         return "No online admins to help build (you count, but you're in a duel).";
      }

      sessions.put(host.getUUID(), session);
      Chat.raw(
         host,
         "&6&lMap Edit&r&7 - editing the current &e"
            + modeName(key)
            + "&7 map. &f/mapexport&7 saves it and kicks everyone out; &f/mapexit&7 leaves without saving."
      );
      StringBuilder hint = new StringBuilder("&7Markers: ");
      MarkerDef[] palette = markersFor(key);

      for (int i = 0; i < palette.length; i++) {
         if (i > 0) {
            hint.append("&7, ");
         }

         hint.append(markerName(palette[i].key));
      }

      hint.append("&7. &7Shift-right-click a marker to remove it.");
      Chat.raw(host, hint.toString());
      return null;
   }

   public static String exit(ServerPlayer host) {
      EditSession session = sessionOf(host.getUUID());
      if (session == null) {
         return "You're not editing a map. Start one with /mapedit <style>.";
      }

      for (UUID uuid : new ArrayList<>(session.members)) {
         ServerPlayer p = serverRef != null ? serverRef.getPlayerList().getPlayer(uuid) : null;
         if (p != null) {
            leaveSession(session, p, false);
         }
      }

      sessions.values().remove(session);
      DuelManager.releasePlot(session.ox, session.oz);
      Chat.raw(host, "&eMap edit cancelled - nothing was saved.");
      return null;
   }

   public static String restore(ServerPlayer host, String mode) {
      String key = mode != null && !mode.isBlank() ? normalizeMode(mode) : "arena";
      if (key == null) {
         return "Unknown map style. Use: arena (1.8arena/modernarena/other/luckypvp), skywars, or bedwars.";
      }

      try {
         Path dir = serverRef.getWorldPath(LevelResource.ROOT).resolve("fortuneandfavors").resolve("maps");
         Path file = dir.resolve(key + ".json");
         if (Files.exists(file)) {
            Files.delete(file);
            Chat.raw(host, "&aRemoved the custom &e" + modeName(key) + " &aexport.");
         } else {
            Chat.raw(host, "&7No custom &e" + modeName(key) + " &7map was exported - the built-in arena is already in use.");
         }

         customMaps.remove(key);
         return null;
      } catch (Exception e) {
         return "Could not restore the map file - check the server log.";
      }
   }

   public static String export(ServerPlayer host) {
      EditSession session = sessionOf(host.getUUID());
      if (session == null) {
         return "You're not editing a map. Start one with /mapedit <style>.";
      }

      ServerLevel realm = serverRef != null ? serverRef.getLevel(DuelManager.DUEL_REALM) : null;
      if (realm == null) {
         return "The arena realm isn't available.";
      }

      String err = validate(session);
      if (err != null) {
         return err;
      }

      CustomMap map = capture(session, realm);
      if (map.blocks.isEmpty()) {
         return "The map is empty - place some blocks first!";
      }

      try {
         Path dir = serverRef.getWorldPath(LevelResource.ROOT).resolve("fortuneandfavors").resolve("maps");
         Files.createDirectories(dir);
         Path file = dir.resolve(session.mode + ".json");
         JsonUtil.write(file, mapToJson(map));
         customMaps.put(session.mode, map);
         Chat.raw(host, "&aMap exported! &7Saved to &f" + file.toAbsolutePath());
         Chat.raw(
            host,
            "&7That file is the shareable map - drop it in another world's &ffortuneandfavors/maps&7 folder (or hand it to the mod author) to use it everywhere."
         );
         Chat.raw(host, "&7Every future &e" + modeName(session.mode) + " &7duel will now use your custom map.");
      } catch (Exception e) {
         FortuneFavorsMod.LOGGER.error("Fortune & Favors: could not write map file", e);
         return "Could not write the map file - check the server log.";
      }

      for (UUID uuid : new ArrayList<>(session.members)) {
         ServerPlayer p = serverRef.getPlayerList().getPlayer(uuid);
         if (p != null) {
            leaveSession(session, p, false);
         }
      }

      sessions.values().remove(session);
      DuelManager.releasePlot(session.ox, session.oz);
      Chat.raw(host, "&aEveryone has been kicked back to where they were.");
      return null;
   }

   public static void onDisconnect(ServerPlayer p) {
      EditSession session = sessionOf(p.getUUID());
      if (session != null) {
         leaveSession(session, p, true);
         UUID next = null;
         Iterator nextP = session.members.iterator();
         if (nextP.hasNext()) {
            UUID uuid = (UUID)nextP.next();
            next = uuid;
         }

         if (next != null) {
            sessions.values().remove(session);
            sessions.put(next, session);
            ServerPlayer nextPx = serverRef != null ? serverRef.getPlayerList().getPlayer(next) : null;
            if (nextPx != null) {
               Chat.raw(nextPx, "&eAn editor left - you're in charge now. &f/mapexport&7 saves the map, &f/mapexit&7 leaves without saving.");
            }
         } else {
            sessions.values().remove(session);
            DuelManager.releasePlot(session.ox, session.oz);
         }
      }
   }

   private static void enterSession(EditSession session, ServerLevel realm, ServerPlayer p) {
      session.saved.put(p.getUUID(), new SavedEditor(p));
      session.members.add(p.getUUID());
      p.getInventory().clearContent();
      p.removeAllEffects();
      p.setHealth(p.getMaxHealth());
      p.getFoodData().setFoodLevel(20);
      p.getFoodData().setSaturation(20.0F);
      p.setGameMode(GameType.CREATIVE);
      p.setInvulnerable(true);
      int slot = 0;

      for (MarkerDef m : markersFor(session.mode)) {
         p.getInventory().setItem(slot++, markerStack(m, session.mode));
      }

      for (Item build : new Item[]{
         Items.STONE_BRICKS,
         Items.OAK_PLANKS,
         Items.GLASS,
         (Item)Items.WOOL.pick(DyeColor.WHITE),
         Items.SMOOTH_SANDSTONE,
         Items.TERRACOTTA,
         Items.LADDER,
         Items.OBSIDIAN
      }) {
         if (slot < 36) {
            p.getInventory().setItem(slot++, new ItemStack(build, 64));
         }
      }

      p.getInventory().setSelectedSlot(0);
      double[] spawn = new double[]{session.ox + 8.5, 101.0, session.oz + 8.5};

      try {
         p.teleport(
            new TeleportTransition(realm, new Vec3(spawn[0], spawn[1], spawn[2]), Vec3.ZERO, p.getYRot(), p.getXRot(), TeleportTransition.PLACE_PORTAL_TICKET)
         );
      } catch (Exception var8) {
      }

      Chat.raw(p, "&6&lMap Edit&7 - build the arena! Shift-right-click a marker block to remove it.");
   }

   private static void leaveSession(EditSession session, ServerPlayer p, boolean silent) {
      session.members.remove(p.getUUID());
      SavedEditor s = (SavedEditor)session.saved.remove(p.getUUID());
      if (s != null) {
         p.getInventory().clearContent();

         for (int i = 0; i < s.items.size() && i < p.getInventory().getContainerSize(); i++) {
            p.getInventory().setItem(i, (ItemStack)s.items.get(i));
         }

         p.setHealth(Math.min(s.health, p.getMaxHealth()));
         p.getFoodData().setFoodLevel(s.hunger);
         p.getFoodData().setSaturation(s.saturation);
         p.setExperienceLevels(s.xpLevel);
         p.setExperiencePoints((int)Math.ceil(s.xpProgress * p.getXpNeededForNextLevel()));
         p.removeAllEffects();
         p.setGameMode(s.gameMode);
         p.setInvulnerable(false);
         ServerLevel home = serverRef != null ? serverRef.getLevel(s.dim) : null;
         if (home != null) {
            try {
               p.teleport(new TeleportTransition(home, new Vec3(s.x, s.y, s.z), Vec3.ZERO, s.yaw, s.pitch, TeleportTransition.PLACE_PORTAL_TICKET));
            } catch (Exception var6) {
            }
         }

         if (!silent) {
            Chat.raw(p, "&aYou left map edit mode - your items and position are restored.");
         }
      }
   }

   public static InteractionResult onUseBlock(ServerPlayer p, Level level, InteractionHand hand, BlockHitResult hit) {
      if (!level.isClientSide() && p != null) {
         EditSession session = sessionOf(p.getUUID());
         if (session == null) {
            return InteractionResult.PASS;
         }

         ItemStack held = p.getItemInHand(hand);
         if (p.isShiftKeyDown()) {
            BlockPos clicked = hit.getBlockPos();
            String type = markerAt(session, clicked);
            if (type == null) {
               BlockState st = level.getBlockState(clicked);
               if (st.getBlock() instanceof BedBlock && st.getValue(BedBlock.PART) == BedPart.HEAD) {
                  BlockPos foot = clicked.relative(((Direction)st.getValue(BedBlock.FACING)).getOpposite());
                  type = markerAt(session, foot);
                  if (type != null) {
                     clicked = foot;
                  }
               }
            }

            if (type != null) {
               BlockPos markerPos = clicked;
               ((List)session.markers.get(type)).removeIf(pos -> pos.equals(markerPos));
               level.setBlock(clicked, Blocks.AIR.defaultBlockState(), 3);
               level.playSound(null, clicked.getX() + 0.5, clicked.getY() + 0.5, clicked.getZ() + 0.5, SoundEvents.STONE_BREAK, SoundSource.BLOCKS, 1.0F, 1.0F);
               MarkerDef def = markerDefFor(type);
               if (def != null) {
                  p.getInventory().add(markerStack(def, session.mode));
               }

               Chat.msg(p, "&cMarker picked up - re-place it anywhere.");
               return InteractionResult.SUCCESS;
            }
         }

         String marker = markerTypeOf(held);
         if (marker == null) {
            return InteractionResult.PASS;
         }

         BlockPos target = hit.getBlockPos().relative(hit.getDirection());
         if (!((List)session.markers.get(marker)).contains(target)) {
            int cap = markerCap(marker);
            if (cap >= 0 && ((List)session.markers.get(marker)).size() >= cap) {
               Chat.msg(
                  p,
                  cap == 1
                     ? "&cOnly one " + markerName(marker) + " &7marker is allowed - pick it up (shift-right-click) or break it before placing another."
                     : "&cOnly " + cap + " " + markerName(marker) + " &7markers are allowed (one per team) - remove one before placing another."
               );
               level.playSound(
                  null,
                  target.getX() + 0.5,
                  target.getY() + 0.5,
                  target.getZ() + 0.5,
                  (SoundEvent)SoundEvents.NOTE_BLOCK_PLING.value(),
                  SoundSource.PLAYERS,
                  1.0F,
                  0.5F
               );
               return InteractionResult.FAIL;
            }

            ((List)session.markers.get(marker)).add(target);
            Chat.msg(p, "&a" + markerName(marker) + " &7marked at " + target.getX() + ", " + target.getY() + ", " + target.getZ());
            level.playSound(
               null,
               target.getX() + 0.5,
               target.getY() + 0.5,
               target.getZ() + 0.5,
               (SoundEvent)SoundEvents.NOTE_BLOCK_PLING.value(),
               SoundSource.PLAYERS,
               1.0F,
               1.6F
            );
         }

         return InteractionResult.PASS;
      } else {
         return InteractionResult.PASS;
      }
   }

   private static int markerCap(String key) {
      if ("p1spawn".equals(key) || "p2spawn".equals(key)) {
         return 1;
      } else {
         return "bed".equals(key) ? 2 : -1;
      }
   }

   public static InteractionResult onUseEntity(ServerPlayer p, Entity entity) {
      if (p != null && entity != null && p.isShiftKeyDown() && entity instanceof Villager) {
         EditSession session = sessionOf(p.getUUID());
         if (session == null) {
            return InteractionResult.PASS;
         }

         BlockPos pos = entity.blockPosition();
         String found = null;
         double best = 9.0;

         for (String type : new String[]{"shop", "upgrade"}) {
            for (BlockPos m : session.markers.getOrDefault(type, List.of())) {
               double dx = m.getX() - pos.getX();
               double dz = m.getZ() - pos.getZ();
               double dist = dx * dx + dz * dz;
               if (dist < best) {
                  best = dist;
                  found = type;
               }
            }
         }

         if (found != null) {
            double matchDist = best;
            ((List<BlockPos>)session.markers.get(found)).removeIf((BlockPos mx) -> {
               double dxx = mx.getX() - pos.getX();
               double dzx = mx.getZ() - pos.getZ();
               return dxx * dxx + dzx * dzx <= matchDist + 0.01;
            });
         }

         entity.discard();
         Chat.msg(p, "&cVillager removed - it won't be part of the exported map.");
         return InteractionResult.SUCCESS;
      } else {
         return InteractionResult.PASS;
      }
   }

   public static void onBlockPlaced(Level level, BlockPos pos, LivingEntity placer) {
      if (!level.isClientSide() && placer != null && placer instanceof ServerPlayer sp) {
         EditSession session = sessionOf(sp.getUUID());
         if (session != null) {
            session.touched.add(BlockPos.asLong(pos.getX(), pos.getY(), pos.getZ()));
         }
      }
   }

   public static void onBlockBroken(Level level, BlockPos pos) {
      if (!level.isClientSide()) {
         long packed = BlockPos.asLong(pos.getX(), pos.getY(), pos.getZ());

         for (EditSession s : sessions.values()) {
            if (s.touched.remove(packed)) {
               for (List<BlockPos> list : s.markers.values()) {
                  list.removeIf(pp -> pp.equals(pos));
               }

               return;
            }
         }
      }
   }

   private static EditSession sessionOf(UUID uuid) {
      for (EditSession s : sessions.values()) {
         if (s.members.contains(uuid)) {
            return s;
         }
      }

      return null;
   }

   private static String markerAt(EditSession session, BlockPos pos) {
      for (Entry<String, List<BlockPos>> e : session.markers.entrySet()) {
         if (e.getValue().contains(pos)) {
            return e.getKey();
         }
      }

      return null;
   }

   private static ItemStack markerStack(MarkerDef m, String mode) {
      ItemStack s = new ItemStack(m.item);
      s.set(DataComponents.CUSTOM_NAME, Component.literal(m.name));
      List<Component> lore = new ArrayList<>();
      lore.add(Component.literal(m.hint));
      lore.add(Component.literal("§7Shift-right-click a placed marker to remove it"));
      lore.add(Component.literal("§8" + modeName(mode) + " markers: " + markerPaletteLine(mode)));
      s.set(DataComponents.LORE, new ItemLore(lore));
      CompoundTag tag = new CompoundTag();
      tag.putString("ffmap", "ffmap_marker");
      tag.putString("ffmap_marker", m.key);
      s.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
      return s;
   }

   private static String markerPaletteLine(String mode) {
      MarkerDef[] palette = markersFor(mode);
      StringBuilder sb = new StringBuilder();

      for (int i = 0; i < palette.length; i++) {
         if (i > 0) {
            sb.append(", ");
         }

         sb.append(markerName(palette[i].key));
      }

      return sb.toString();
   }

   private static String markerTypeOf(ItemStack held) {
      if (held != null && !held.isEmpty()) {
         CustomData data = (CustomData)held.get(DataComponents.CUSTOM_DATA);
         if (data == null) {
            return null;
         }

         CompoundTag tag = data.copyTag();
         if (!"ffmap_marker".equals(tag.getString("ffmap").orElse(""))) {
            return null;
         }

         String key = tag.getString("ffmap_marker").orElse("");

         for (MarkerDef m : MARKERS) {
            if (m.key.equals(key)) {
               return key;
            }
         }

         return null;
      } else {
         return null;
      }
   }

   private static String markerName(String key) {
      for (MarkerDef m : MARKERS) {
         if (m.key.equals(key)) {
            return m.name.replace("§", "");
         }
      }

      return key;
   }

   private static MarkerDef markerDefFor(String key) {
      for (MarkerDef m : MARKERS) {
         if (m.key.equals(key)) {
            return m;
         }
      }

      return null;
   }

   private static String validate(EditSession session) {
      List<BlockPos> p1 = (List<BlockPos>)session.markers.get("p1spawn");
      List<BlockPos> p2 = (List<BlockPos>)session.markers.get("p2spawn");
      if (!p1.isEmpty() && !p2.isEmpty()) {
         if ("bedwars".equals(session.mode) && ((List)session.markers.get("bed")).size() < 2) {
            return "Bed Wars needs &ctwo bed markers&r &7(one per team)!";
         } else {
            return "skywars".equals(session.mode) && ((List)session.markers.get("chest_middle")).isEmpty()
               ? "Sky Wars needs at least one &aMiddle Chest&r &7marker (they refill every minute)!"
               : null;
         }
      } else {
         return "You need both a &cPlayer 1 Spawn&r &7and a &9Player 2 Spawn&r &7marker!";
      }
   }

   public static boolean isInEditPlot(BlockPos pos) {
      long packed = BlockPos.asLong(pos.getX(), pos.getY(), pos.getZ());

      for (EditSession s : sessions.values()) {
         if (pos.getX() >= s.ox && pos.getX() <= s.ox + 160 && pos.getZ() >= s.oz && pos.getZ() <= s.oz + 160) {
            return true;
         }

         if (s.touched.contains(packed)) {
            return true;
         }
      }

      return false;
   }

   private static CustomMap capture(EditSession session, ServerLevel realm) {
      CustomMap map = new CustomMap(session.mode);
      Set<Long> seen = new HashSet<>();
      int x0 = session.ox;
      int x1 = session.ox + 160;
      int z0 = session.oz;
      int z1 = session.oz + 160;

      for (int x = x0; x <= x1; x++) {
         for (int z = z0; z <= z1; z++) {
            for (int y = 98; y <= 148; y++) {
               BlockPos pos = new BlockPos(x, y, z);
               BlockState st = realm.getBlockState(pos);
               if (!st.isAir()) {
                  addCaptured(map, seen, pos, st);
               }
            }
         }
      }

      for (long packed : session.touched) {
         if (!seen.contains(packed)) {
            BlockPos pos = BlockPos.of(packed);
            BlockState st = realm.getBlockState(pos);
            if (!st.isAir()) {
               addCaptured(map, seen, pos, st);
            }
         }
      }

      ArrayDeque<BlockPos> queue = new ArrayDeque<>();

      for (long packed : session.touched) {
         if (!seen.contains(packed)) {
            queue.add(BlockPos.of(packed));
         }
      }

      while (!queue.isEmpty()) {
         BlockPos pos = queue.poll();
         long packed = pos.asLong();
         if (!seen.contains(packed)) {
            BlockState st = realm.getBlockState(pos);
            if (st.isAir()) {
               seen.add(packed);
            } else {
               addCaptured(map, seen, pos, st);

               for (Direction d : Direction.values()) {
                  long np = pos.relative(d).asLong();
                  if (!seen.contains(np)) {
                     queue.add(pos.relative(d));
                  }
               }
            }
         }
      }

      for (Entry<String, List<BlockPos>> e : session.markers.entrySet()) {
         List<int[]> list = map.markers.computeIfAbsent(e.getKey(), k -> new ArrayList<>());

         for (BlockPos pos : e.getValue()) {
            list.add(new int[]{pos.getX(), pos.getY(), pos.getZ()});
         }
      }

      return map;
   }

   private static void addCaptured(CustomMap map, Set<Long> seen, BlockPos pos, BlockState st) {
      seen.add(pos.asLong());
      Map<String, String> props = new LinkedHashMap<>();
      st.getValues().forEach(v -> props.put(v.property().getName(), v.valueName()));
      map.blocks.add(new BlockData(pos.getX(), pos.getY(), pos.getZ(), BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString(), props));
      map.minX = Math.min(map.minX, pos.getX());
      map.maxX = Math.max(map.maxX, pos.getX());
      map.minZ = Math.min(map.minZ, pos.getZ());
      map.maxZ = Math.max(map.maxZ, pos.getZ());
   }

   private static JsonObject mapToJson(CustomMap map) {
      JsonObject root = new JsonObject();
      root.addProperty("mode", map.mode);
      JsonArray blocks = new JsonArray();

      for (BlockData b : map.blocks) {
         JsonObject o = new JsonObject();
         o.addProperty("x", b.x);
         o.addProperty("y", b.y);
         o.addProperty("z", b.z);
         o.addProperty("block", b.block);
         if (b.props != null && !b.props.isEmpty()) {
            JsonObject props = new JsonObject();

            for (Entry<String, String> pe : b.props.entrySet()) {
               props.addProperty(pe.getKey(), pe.getValue());
            }

            o.add("props", props);
         }

         blocks.add(o);
      }

      root.add("blocks", blocks);
      JsonObject markers = new JsonObject();

      for (Entry<String, List<int[]>> e : map.markers.entrySet()) {
         JsonArray arr = new JsonArray();

         for (int[] pos : e.getValue()) {
            JsonArray p = new JsonArray();
            p.add(pos[0]);
            p.add(pos[1]);
            p.add(pos[2]);
            arr.add(p);
         }

         markers.add(e.getKey(), arr);
      }

      root.add("markers", markers);
      return root;
   }

   private static boolean isOp(ServerPlayer p) {
      return ClaimManager.isAdminOp(p);
   }

   private static String normalizeMode(String mode) {
      if (mode == null) {
         return null;
      } else {
         String m = mode.toLowerCase(Locale.ROOT).replace(" ", "").replace("_", "");
         if (m.equals("1.8arena")
            || m.equals("legacy")
            || m.equals("18")
            || m.equals("modern")
            || m.equals("modernarena")
            || m.equals("1.9")
            || m.equals("other")
            || m.equals("otherarena")
            || m.equals("duel")
            || m.equals("arena")
            || m.equals("pvp")) {
            return "arena";
         } else if (m.equals("skywars") || m.equals("sky")) {
            return "skywars";
         } else if (m.equals("bedwars") || m.equals("bed")) {
            return "bedwars";
         } else {
            return !m.equals("luckypvp") && !m.equals("lucky") && !m.equals("luckypv") ? null : "arena";
         }
      }
   }

   private static String modeName(String key) {
      return switch (key) {
         case "arena" -> "Arena";
         case "skywars" -> "Sky Wars";
         case "bedwars" -> "Bed Wars";
         default -> key;
      };
   }


    public record BlockData(int x, int y, int z, String block, Map<String, String> props) {
    }

    static public final class CustomMap {
       public final String mode;
       public final List<BlockData> blocks = new ArrayList<>();
       public final Map<String, List<int[]>> markers = new HashMap<>();
       public int minX = Integer.MAX_VALUE;
       public int maxX = Integer.MIN_VALUE;
       public int minZ = Integer.MAX_VALUE;
       public int maxZ = Integer.MIN_VALUE;
    
       CustomMap(String mode) {
          this.mode = mode;
    
          for (MarkerDef m : MapEditor.MARKERS) {
             this.markers.put(m.key, new ArrayList<>());
          }
       }
    
       public int centerX() {
          return (this.minX + this.maxX) / 2;
       }
    
       public int centerZ() {
          return (this.minZ + this.maxZ) / 2;
       }
    }

    static final class EditSession {
       final String mode;
       final int ox;
       final int oz;
       final UUID host;
       final Map<String, List<BlockPos>> markers = new HashMap<>();
       final Map<UUID, SavedEditor> saved = new HashMap<>();
       final List<UUID> members = new ArrayList<>();
       final Set<Long> touched = new HashSet<>();
    
       EditSession(String mode, int ox, int oz, UUID host) {
          this.mode = mode;
          this.ox = ox;
          this.oz = oz;
          this.host = host;
    
          for (MarkerDef m : MapEditor.MARKERS) {
             this.markers.put(m.key, new ArrayList<>());
          }
       }
    }

    record MarkerDef(String key, Item item, String name, String hint) {
    }

    static final class SavedEditor {
       final List<ItemStack> items = new ArrayList<>();
       final float health;
       final int hunger;
       final float saturation;
       final int xpLevel;
       final float xpProgress;
       final GameType gameMode;
       final ResourceKey<Level> dim;
       final double x;
       final double y;
       final double z;
       final float yaw;
       final float pitch;
    
       SavedEditor(ServerPlayer p) {
          for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
             this.items.add(p.getInventory().getItem(i).copy());
          }
    
          this.health = p.getHealth();
          this.hunger = p.getFoodData().getFoodLevel();
          this.saturation = p.getFoodData().getSaturationLevel();
          this.xpLevel = p.experienceLevel;
          this.xpProgress = p.experienceProgress;
          this.gameMode = p.gameMode();
          this.dim = p.level().dimension();
          this.x = p.getX();
          this.y = p.getY();
          this.z = p.getZ();
          this.yaw = p.getYRot();
          this.pitch = p.getXRot();
       }
    }
}
