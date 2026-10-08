package com.fortuneandfavors.economy;

import com.fortuneandfavors.util.Chat;
import com.fortuneandfavors.util.JsonUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.Map.Entry;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.TeamColor;

/**
 * Persistent cosmetics: chat/tab titles, name colors and particle trails.
 * Unlocked from high-tier Mystery Chests and stored per-player across resets.
 */
public final class CosmeticManager {
   public record Cosmetic(String key, String display, String kind, Item icon, String desc, boolean rare) {
   }

   public static final List<Cosmetic> ALL = List.of(
      new Cosmetic("title_emerald", "§a[◆ Emerald ◆]", "title", Items.EMERALD, "A rich green title for the wealthy.", false),
      new Cosmetic("title_starlight", "§b[✧ Starlight ✧]", "title", Items.NETHER_STAR, "Twinkle like the night sky.", false),
      new Cosmetic("title_sovereign", "§e[✦ Sovereign ✦]", "title", Items.GOLD_INGOT, "Rule the server in gold.", true),
      new Cosmetic("title_shadow", "§5[☾ Shadow ☽]", "title", Items.ECHO_SHARD, "Move unseen, feared by all.", true),
      new Cosmetic("title_dragonheart", "§c[♥ Dragonheart ♥]", "title", Items.DRAGON_BREATH, "The heart of a raid boss.", true),
      new Cosmetic("color_gold", "§6Gold name", "color", Items.DYE.yellow(), "Your name gleams like gold in chat and the tab list.", false),
      new Cosmetic("color_aqua", "§bAqua name", "color", Items.DYE.cyan(), "Cool aqua name color.", false),
      new Cosmetic("color_pink", "§dPink name", "color", Items.DYE.pink(), "A soft pink glow.", true),
      new Cosmetic("color_green", "§aGreen name", "color", Items.DYE.lime(), "Fresh green name color.", false),
      new Cosmetic("trail_flame", "§6Flame trail", "trail", Items.BLAZE_POWDER, "Fire licks your heels.", false),
      new Cosmetic("trail_sparkle", "§bSparkle trail", "trail", Items.GLOWSTONE_DUST, "End rods sparkle behind you.", false),
      new Cosmetic("trail_heart", "§dHeart trail", "trail", Items.DYE.red(), "Hearts float around you.", true),
      new Cosmetic("trail_ender", "§5Ender trail", "trail", Items.ENDER_PEARL, "Portals whisper at your feet.", true),
      new Cosmetic("trail_cloud", "§fCloud trail", "trail", Items.QUARTZ, "You walk on clouds.", false)
   );

   private static final Map<UUID, Set<String>> owned = new HashMap<>();
   private static final Map<UUID, String> equippedTitle = new HashMap<>();
   private static final Map<UUID, String> equippedColor = new HashMap<>();
   private static final Map<UUID, String> equippedTrail = new HashMap<>();
   private static final Map<UUID, String> displayCache = new HashMap<>();
   /** Where each trail last put a particle. A trail is what a moving body leaves behind, so
    *  this is what tells a body that is going somewhere from one that is standing still. */
   private static final Map<UUID, Vec3> trailAnchor = new HashMap<>();
   /** How far (squared) a trail's owner has to have moved for the next sample to be placed -
    *  a third of a block, which a sneaking player still clears between two samples. */
   private static final double TRAIL_MIN_MOVE_SQR = 0.09D;
   private static final Random RANDOM = new Random();
   private static Path dataFile;

   private CosmeticManager() {
   }

   public static void load(MinecraftServer server) {
      owned.clear();
      equippedTitle.clear();
      equippedColor.clear();
      equippedTrail.clear();
      displayCache.clear();
      trailAnchor.clear();
      dataFile = EconomyManager.getDataDir(server).resolve("cosmetics.json");
      JsonObject root = JsonUtil.readOrCreate(dataFile, new JsonObject());
      if (root.has("players") && root.get("players").isJsonObject()) {
         for (Entry<String, JsonElement> e : root.getAsJsonObject("players").entrySet()) {
            try {
               UUID uuid = UUID.fromString(e.getKey());
               JsonObject obj = e.getValue().getAsJsonObject();
               Set<String> set = new HashSet<>();
               if (obj.has("owned") && obj.get("owned").isJsonArray()) {
                  for (JsonElement c : obj.getAsJsonArray("owned")) {
                     set.add(c.getAsString());
                  }
               }
               owned.put(uuid, set);
               if (obj.has("title")) {
                  equippedTitle.put(uuid, obj.get("title").getAsString());
               }
               if (obj.has("color")) {
                  equippedColor.put(uuid, obj.get("color").getAsString());
               }
               if (obj.has("trail")) {
                  equippedTrail.put(uuid, obj.get("trail").getAsString());
               }
            } catch (Exception ignored) {
            }
         }
      }
   }

   public static void save(MinecraftServer server) {
      if (dataFile == null) {
         dataFile = EconomyManager.getDataDir(server).resolve("cosmetics.json");
      }
      JsonObject root = new JsonObject();
      JsonObject players = new JsonObject();
      for (Entry<UUID, Set<String>> e : owned.entrySet()) {
         JsonObject obj = new JsonObject();
         JsonArray arr = new JsonArray();
         for (String c : e.getValue()) {
            arr.add(c);
         }
         obj.add("owned", arr);
         if (equippedTitle.containsKey(e.getKey())) {
            obj.addProperty("title", equippedTitle.get(e.getKey()));
         }
         if (equippedColor.containsKey(e.getKey())) {
            obj.addProperty("color", equippedColor.get(e.getKey()));
         }
         if (equippedTrail.containsKey(e.getKey())) {
            obj.addProperty("trail", equippedTrail.get(e.getKey()));
         }
         players.add(e.getKey().toString(), obj);
      }
      root.add("players", players);
      JsonUtil.write(dataFile, root);
   }

   public static boolean has(UUID uuid, String key) {
      return owned.getOrDefault(uuid, Set.of()).contains(key);
   }

   public static Set<String> ownedSet(UUID uuid) {
      return owned.getOrDefault(uuid, Set.of());
   }

   public static boolean grant(ServerPlayer player, String key) {
      Cosmetic c = byKey(key);
      if (c == null) {
         return false;
      }
      Set<String> set = owned.computeIfAbsent(player.getUUID(), u -> new HashSet<>());
      if (!set.add(key)) {
         return false;
      }
      Chat.raw(player, "§d§lCosmetic unlocked: §r" + c.display + "§d!");
      return true;
   }

   public static Cosmetic byKey(String key) {
      for (Cosmetic c : ALL) {
         if (c.key().equals(key)) {
            return c;
         }
      }
      return null;
   }

   /** Grants a random cosmetic (optionally rare-only). Returns the cosmetic, or null if nothing to grant. */
   public static Cosmetic grantRandom(ServerPlayer player, boolean rareOnly) {
      List<Cosmetic> pool = new ArrayList<>();
      for (Cosmetic c : ALL) {
         if ((!rareOnly || c.rare()) && !has(player.getUUID(), c.key())) {
            pool.add(c);
         }
      }
      if (pool.isEmpty()) {
         return null;
      }
      Cosmetic c = pool.get(RANDOM.nextInt(pool.size()));
      grant(player, c.key());
      return c;
   }

   public static String equip(ServerPlayer player, String key) {
      Cosmetic c = byKey(key);
      if (c == null) {
         return "That cosmetic doesn't exist.";
      }
      if (!has(player.getUUID(), key)) {
         return "You don't own " + c.display() + "§c yet - open Mystery Chests to find it.";
      }
      UUID uuid = player.getUUID();
      String current = equipped(uuid, c.kind());
      if (key.equals(current)) {
         unequipKind(uuid, c.kind());
         Chat.raw(player, "§7Unequipped " + c.display() + "§7.");
         return null;
      }
      switch (c.kind()) {
         case "title" -> equippedTitle.put(uuid, key);
         case "color" -> equippedColor.put(uuid, key);
         default -> equippedTrail.put(uuid, key);
      }
      Chat.raw(player, "§dEquipped: §r" + c.display());
      displayCache.remove(uuid);
      return null;
   }

   private static void unequipKind(UUID uuid, String kind) {
      switch (kind) {
         case "title" -> equippedTitle.remove(uuid);
         case "color" -> equippedColor.remove(uuid);
         default -> equippedTrail.remove(uuid);
      }
   }

   public static String equipped(UUID uuid, String kind) {
      return switch (kind) {
         case "title" -> equippedTitle.get(uuid);
         case "color" -> equippedColor.get(uuid);
         default -> equippedTrail.get(uuid);
      };
   }

   public static void tick(MinecraftServer server) {
      for (ServerPlayer p : server.getPlayerList().getPlayers()) {
         try {
            refreshNameDisplay(server, p);
            spawnTrail(p);
         } catch (Exception ignored) {
         }
      }
   }

   private static void refreshNameDisplay(MinecraftServer server, ServerPlayer p) {
      UUID uuid = p.getUUID();
      String titleKey = equippedTitle.get(uuid);
      String colorKey = equippedColor.get(uuid);
      String sig = (titleKey == null ? "" : titleKey) + "|" + (colorKey == null ? "" : colorKey);
      String cached = displayCache.get(uuid);
      if (sig.equals(cached)) {
         return;
      }
      displayCache.put(uuid, sig);
      Scoreboard sb = server.getScoreboard();
      String teamName = "ffcos" + uuid.toString().substring(0, 8);
      PlayerTeam team = sb.getPlayerTeam(teamName);
      if (titleKey == null && colorKey == null) {
         if (team != null) {
            sb.removePlayerTeam(team);
         }
         return;
      }
      if (team == null) {
         team = sb.addPlayerTeam(teamName);
         team.setNameTagVisibility(net.minecraft.world.scores.Team.Visibility.ALWAYS);
      }
      Cosmetic titleC = titleKey == null ? null : byKey(titleKey);
      Cosmetic colorC = colorKey == null ? null : byKey(colorKey);
      team.setPlayerPrefix(Component.literal(titleC == null ? "" : titleC.display() + " "));
      team.setColor(colorC == null ? java.util.Optional.empty() : java.util.Optional.of(teamColor(colorC.key())));
      // Leave any old cosmetics team, then join ours.
      for (PlayerTeam t : new ArrayList<>(sb.getPlayerTeams())) {
         if (t.getName().startsWith("ffcos") && !t.getName().equals(teamName) && t.getPlayers().contains(p.getScoreboardName())) {
            sb.removePlayerFromTeam(p.getScoreboardName(), t);
         }
      }
      if (!team.getPlayers().contains(p.getScoreboardName())) {
         sb.addPlayerToTeam(p.getScoreboardName(), team);
      }
   }

   private static TeamColor teamColor(String colorKey) {
      return switch (colorKey) {
         case "color_aqua" -> TeamColor.AQUA;
         case "color_pink" -> TeamColor.LIGHT_PURPLE;
         case "color_green" -> TeamColor.GREEN;
         default -> TeamColor.GOLD;
      };
   }

   /**
    * Whether a trail's next sample is owed, given where its last one was placed.
    *
    * <p>A seam rather than the comparison written inline, so "standing still draws nothing, and
    * walking draws a trail" can be asserted without a live player - see the harness check.
    */
   public static boolean trailSampleDue(Vec3 anchor, Vec3 here) {
      if (anchor == null || here == null) {
         return true;
      }

      return here.distanceToSqr(anchor) >= TRAIL_MIN_MOVE_SQR;
   }

   private static void spawnTrail(ServerPlayer p) {
      UUID uuid = p.getUUID();
      String trailKey = equippedTrail.get(uuid);
      if (trailKey == null) {
         trailAnchor.remove(uuid);
         return;
      }
      if (p.level().getGameTime() % 8L != 0L) {
         return;
      }
      // Only once the wearer has actually gone somewhere. This drew around a body standing
      // still as well, which reads as a sparkle stuck to somebody who is doing nothing at all -
      // and it never stopped, because there was nothing to stop for. The anchor is the last
      // place a particle was placed, so a slow drift still trails (the gap accumulates) while a
      // body at rest draws nothing.
      Vec3 here = p.position();
      if (!trailSampleDue(trailAnchor.get(uuid), here)) {
         return;
      }
      trailAnchor.put(uuid, here);
      ParticleOptions particle = switch (trailKey) {
         case "trail_flame" -> ParticleTypes.FLAME;
         case "trail_sparkle" -> ParticleTypes.END_ROD;
         case "trail_heart" -> ParticleTypes.HEART;
         case "trail_ender" -> ParticleTypes.PORTAL;
         default -> ParticleTypes.CLOUD;
      };
      double px = p.getX() + (RANDOM.nextDouble() - 0.5) * 1.2;
      double py = p.getY() + RANDOM.nextDouble() * 1.6;
      double pz = p.getZ() + (RANDOM.nextDouble() - 0.5) * 1.2;
      p.level().sendParticles(particle, px, py, pz, 1, 0.1, 0.1, 0.1, 0.01);
   }
}
