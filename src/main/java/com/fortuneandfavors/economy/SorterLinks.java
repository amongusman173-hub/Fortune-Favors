package com.fortuneandfavors.economy;

import com.fortuneandfavors.util.Chat;
import com.fortuneandfavors.util.JsonUtil;
import com.fortuneandfavors.util.SoundUtil;
import com.fortuneandfavors.ModSounds;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.level.Level;

/**
 * Marked containers: a chest with a name, and a sorter that can reach it from across the base.
 *
 * <p>An item sorter only ever looked at the six blocks touching it, which is the right default and a
 * hopeless answer for the build where the farm's output collects in one chest in another room. The
 * answer here is a mark and an id, and deliberately only those two things: a player tags a container
 * once - with a {@code Sorter Tag} - and from then on every sorter that names that id pulls out of
 * it, wherever it stands within reach. An id is a *name for a place*, so moving the mark to a
 * different chest moves every sorter that was using it, which is the whole reason to write a name
 * down instead of a coordinate.
 *
 * <p>The tag is a custom item and not a vanilla sign, on purpose. A sign is what a player already
 * puts on a chest to label it; answering that gesture as "now everything with this name is a remote
 * input" quietly rewired a base on a decoration. Only the mod's own tag item tags, so an ordinary
 * sign is an ordinary sign and nothing else.
 *
 * <p>What this is not: a chunk loader. A container in an unloaded chunk has no block entity to ask,
 * so a linked sorter simply finds nothing there until the chunk is loaded again - by a player, or by
 * a chunk anchor. That is stated in the window because a machine that silently does nothing is worse
 * than one that is honestly far away.
 */
public final class SorterLinks {
   /** How far a sorter may reach a marked container, in blocks. */
   public static final int MAX_REACH = 32;
   /** How many marks one sorter's window can offer at once. */
   public static final int MAX_LINKS = 8;
   /** An id long enough to be a name, short enough to read in a chat line. */
   public static final int MAX_ID_LENGTH = 24;

   /** One marked container: the id a sorter names, and the place it currently stands for. */
   public record Link(String id, String dimension, BlockPos pos, UUID owner, String ownerName) {
   }

   private static final Map<String, Link> links = new LinkedHashMap<>();
   private static Path dataFile;
   private static boolean loadHealthy = true;

   private SorterLinks() {
   }

   public static void load(MinecraftServer server) {
      links.clear();
      loadHealthy = true;
      dataFile = EconomyManager.getDataDir(server).resolve("sorter_links.json");
      if (!Files.exists(dataFile)) {
         return;
      }
      try {
         String content = Files.readString(dataFile);
         if (content.isBlank()) {
            return;
         }
         JsonObject root = (JsonObject)JsonUtil.gson().fromJson(content, JsonObject.class);
         if (root == null || !root.has("links") || !root.get("links").isJsonObject()) {
            return;
         }
         for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("links").entrySet()) {
            JsonElement el = e.getValue();
            if (el == null || !el.isJsonObject()) {
               continue;
            }
            JsonObject obj = el.getAsJsonObject();
            BlockPos pos = parsePos(JsonUtil.jsonString(obj, "pos", ""));
            String dimension = JsonUtil.jsonString(obj, "dim", "");
            if (pos == null || dimension.isBlank()) {
               continue;
            }
            UUID owner = null;
            try {
               String raw = JsonUtil.jsonString(obj, "owner", "");
               owner = raw.isBlank() ? null : UUID.fromString(raw);
            } catch (Exception ignored) {
            }
            links.put(e.getKey(), new Link(e.getKey(), dimension, pos, owner, JsonUtil.jsonString(obj, "ownerName", "")));
         }
      } catch (Exception e) {
         loadHealthy = false;
         com.fortuneandfavors.FortuneFavorsMod.LOGGER.error("Sorter link load failed - writes are disabled to protect sorter_links.json", e);
      }
   }

   public static boolean save(MinecraftServer server) {
      if (!loadHealthy) {
         return false;
      }
      if (dataFile == null && server != null) {
         dataFile = EconomyManager.getDataDir(server).resolve("sorter_links.json");
      }
      if (dataFile == null) {
         return false;
      }
      JsonObject root = new JsonObject();
      JsonObject all = new JsonObject();
      for (Link link : links.values()) {
         JsonObject obj = new JsonObject();
         obj.addProperty("dim", link.dimension());
         obj.addProperty("pos", posKey(link.pos()));
         if (link.owner() != null) {
            obj.addProperty("owner", link.owner().toString());
         }
         obj.addProperty("ownerName", link.ownerName());
         all.add(link.id(), obj);
      }
      root.add("links", all);
      return JsonUtil.write(dataFile, root);
   }

   public static int count() {
      return links.size();
   }

   public static Link find(String id) {
      return id == null ? null : links.get(id);
   }

   public static List<Link> all() {
      return new ArrayList<>(links.values());
   }

   /** The mark standing on one exact position, or null. */
   public static Link at(Level level, BlockPos pos) {
      if (level == null || pos == null) {
         return null;
      }
      String dimension = level.dimension().identifier().toString();
      String key = posKey(pos);
      for (Link link : links.values()) {
         if (link.dimension().equals(dimension) && posKey(link.pos()).equals(key)) {
            return link;
         }
      }
      return null;
   }

   /**
    * The marks one sorter can be pointed at: the same dimension, in reach, and either unowned or the
    * machine owner's. Nearest first, because the nearest barrel is the one a player meant.
    */
   public static List<Link> reachable(Level level, BlockPos from, UUID owner) {
      List<Link> found = new ArrayList<>();
      if (level == null || from == null) {
         return found;
      }
      String dimension = level.dimension().identifier().toString();
      for (Link link : links.values()) {
         if (!link.dimension().equals(dimension)) {
            continue;
         }
         if (link.owner() != null && owner != null && !link.owner().equals(owner)) {
            continue;
         }
         if (distance(from, link.pos()) > (double)MAX_REACH * MAX_REACH) {
            continue;
         }
         found.add(link);
      }
      found.sort((a, b) -> Double.compare(distance(from, a.pos()), distance(from, b.pos())));
      return found;
   }

   /** True when two positions are close enough for one sorter to work with the other. */
   public static boolean inReach(BlockPos from, BlockPos to) {
      return from != null && to != null && distance(from, to) <= (double)MAX_REACH * MAX_REACH;
   }

   private static double distance(BlockPos a, BlockPos b) {
      double dx = a.getX() - b.getX();
      double dy = a.getY() - b.getY();
      double dz = a.getZ() - b.getZ();
      return dx * dx + dy * dy + dz * dz;
   }

   /**
    * Tags a container with a Sorter Tag.
    *
    * <p>The id is the tag's own chosen name if the player renamed it, and the next free {@code C1},
    * {@code C2} if not - so naming a link is optional and lands in the same place either way. A name
    * that is already on another container is refused rather than moved: silently stealing an id would
    * rewire every sorter in the base to a different chest, which is the one thing this system must
    * never do.
    */
   public static String mark(ServerPlayer player, Level level, BlockPos pos, String wanted) {
      if (!(level.getBlockEntity(pos) instanceof Container)) {
         Chat.msg(player, "&cThat is not a container - tag a chest, barrel, hopper or machine.");
         return null;
      }
      if (ChestShopManager.get(level, pos) != null) {
         Chat.msg(player, "&cThat container is a chest shop - tag a container of your own.");
         return null;
      }
      if (!ClaimManager.canInteract(player.getUUID(), level, pos)) {
         Chat.msg(player, "&cThis land is claimed - you can't tag that container.");
         return null;
      }
      Link existing = at(level, pos);
      if (existing != null && existing.owner() != null && !existing.owner().equals(player.getUUID())) {
         Chat.msg(player, "&cThat container is already tagged &f" + existing.id() + "&c by " + existing.ownerName() + ".");
         return null;
      }
      String id = wanted == null || wanted.isBlank() ? null : sanitize(wanted);
      if (id == null) {
         id = existing == null ? freeId() : existing.id();
      }
      Link holder = find(id);
      boolean samePlace = holder != null && holder.dimension().equals(level.dimension().identifier().toString())
         && posKey(holder.pos()).equals(posKey(pos));
      if (holder != null && !samePlace) {
         Chat.msg(player, "&cThe id &f" + id + "&c is already on another container. Untag it first, or tag this one an unclaimed id.");
         return null;
      }
      if (existing != null && !existing.id().equals(id)) {
         links.remove(existing.id());
      }
      links.put(id, new Link(id, level.dimension().identifier().toString(), pos, player.getUUID(), player.getName().getString()));
      save(player.level().getServer());
      Chat.msg(player, "&aTagged this container as &f" + id + "&a. Open a sorter and click &f" + id + "&a in its input row to pull from it.");
      Chat.msg(player, "&7Rename the Sorter Tag in an anvil to choose its name. Sneak-right-click with it to untag.");
      SoundUtil.play(player, ModSounds.TRANSFER);
      if (level instanceof ServerLevel server) {
         server.sendParticles(ParticleTypes.END_ROD, pos.getX() + 0.5, pos.getY() + 1.1, pos.getZ() + 0.5, 12, 0.3, 0.2, 0.3, 0.03);
      }
      return id;
   }

   /** Removes the mark on one position. Returns true when there was one. */
   public static boolean unmark(ServerPlayer player, Level level, BlockPos pos) {
      Link link = at(level, pos);
      if (link == null) {
         return false;
      }
      if (link.owner() != null && player != null && !link.owner().equals(player.getUUID())) {
         Chat.msg(player, "&cThat tag belongs to " + link.ownerName() + ".");
         return false;
      }
      links.remove(link.id());
      save(level.getServer());
      if (player != null) {
         Chat.msg(player, "&7Untagged &f#" + link.id() + "&7 - anything that named it stops reaching.");
         SoundUtil.play(player, ModSounds.TRANSFER);
      }
      return true;
   }

   /**
    * Drops the mark on a broken container.
    *
    * <p>Sorters keep the id they were pointed at rather than having it torn out of their settings:
    * an id is a name, so tagging a replacement chest with the same name picks the line back up -
    * which is the reason to have ids at all. The mark only means "this place", and the place is gone.
    */
   public static void onContainerBroken(Level level, BlockPos pos) {
      Link link = at(level, pos);
      if (link != null) {
         links.remove(link.id());
         save(level.getServer());
      }
   }

   /** The next free short id, so a player who does not care about names still gets one. */
   private static String freeId() {
      for (int i = 1; i < 10000; i++) {
         String candidate = "C" + i;
         if (!links.containsKey(candidate)) {
            return candidate;
         }
      }
      return "C" + UUID.randomUUID().toString().substring(0, 6);
   }

   /** A name a link can carry: letters, digits, dash and underscore, short enough to type back. */
   public static String sanitize(String raw) {
      if (raw == null) {
         return null;
      }
      StringBuilder out = new StringBuilder();
      for (char c : raw.trim().toCharArray()) {
         if (Character.isLetterOrDigit(c) || c == '_' || c == '-') {
            out.append(c);
         } else if (c == ' ') {
            out.append('_');
         }
         if (out.length() >= MAX_ID_LENGTH) {
            break;
         }
      }
      return out.isEmpty() ? null : out.toString();
   }

   private static String posKey(BlockPos pos) {
      return pos.getX() + "," + pos.getY() + "," + pos.getZ();
   }

   /** The id an item's own name would claim, or null when it has none worth using. */
   public static String idFromName(String name) {
      return name == null || name.isBlank() ? null : sanitize(name);
   }

   private static BlockPos parsePos(String key) {
      if (key == null || key.isBlank()) {
         return null;
      }
      String[] parts = key.split(",");
      if (parts.length != 3) {
         return null;
      }
      try {
         return new BlockPos(Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()), Integer.parseInt(parts[2].trim()));
      } catch (NumberFormatException e) {
         return null;
      }
   }

}
