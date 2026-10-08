package com.fortuneandfavors.economy;

import com.fortuneandfavors.economy.TagManager.Tag;
import com.fortuneandfavors.util.JsonUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Map.Entry;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;

public final class TagManager {
   public static final int OWNER_RGB = 16733525;
   private static final Map<UUID, Tag> tags = new HashMap<>();
   private static final Map<UUID, Set<String>> ownedTags = new HashMap<>();
   private static Path dataFile;
   /** The creator's badge is a title and there is exactly one of it - see {@link #tagComponent}. */
   private static final int CREATOR_RGB = 16766720;

   private TagManager() {
   }

   public static void load(MinecraftServer server) {
      tags.clear();
      ownedTags.clear();
      dataFile = EconomyManager.getDataDir(server).resolve("tags.json");
      JsonObject root = JsonUtil.readOrCreate(dataFile, new JsonObject());
      if (root.has("tags") && root.get("tags").isJsonObject()) {
         for (Entry<String, JsonElement> e : root.getAsJsonObject("tags").entrySet()) {
            try {
               JsonObject obj = e.getValue().getAsJsonObject();
               UUID uuid = UUID.fromString(e.getKey());
               String text = obj.get("text").getAsString();
               int rgb = obj.get("rgb").getAsInt();
               tags.put(uuid, new Tag(text, rgb));
            } catch (Exception var8) {
            }
         }
      }
      if (root.has("owned") && root.get("owned").isJsonObject()) {
         for (Entry<String, JsonElement> e : root.getAsJsonObject("owned").entrySet()) {
            try {
               UUID uuid = UUID.fromString(e.getKey());
               Set<String> set = new java.util.LinkedHashSet<>();
               for (JsonElement t : e.getValue().getAsJsonArray()) {
                  set.add(t.getAsString());
               }
               if (!set.isEmpty()) {
                  ownedTags.put(uuid, set);
               }
            } catch (Exception ignored) {
            }
         }
      }
   }

   public static void save(MinecraftServer server) {
      if (dataFile == null) {
         dataFile = EconomyManager.getDataDir(server).resolve("tags.json");
      }

      JsonObject root = new JsonObject();
      JsonObject tagsObj = new JsonObject();

      for (Entry<UUID, Tag> e : tags.entrySet()) {
         JsonObject obj = new JsonObject();
         obj.addProperty("text", e.getValue().text());
         obj.addProperty("rgb", e.getValue().rgb());
         tagsObj.add(e.getKey().toString(), obj);
      }

      root.add("tags", tagsObj);
      if (!ownedTags.isEmpty()) {
         JsonObject owned = new JsonObject();
         for (Entry<UUID, Set<String>> e : ownedTags.entrySet()) {
            JsonArray arr = new JsonArray();
            for (String t : e.getValue()) {
               arr.add(t);
            }
            owned.add(e.getKey().toString(), arr);
         }
         root.add("owned", owned);
      }
      JsonUtil.write(dataFile, root);
   }

   /** Re-broadcasts everyone's tab-list display names so title/tag/position
    *  changes show up immediately instead of waiting for the periodic refresh. */
   public static void refreshTabList(MinecraftServer server) {
      if (server == null) {
         return;
      }
      net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket packet = new net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket(
         java.util.EnumSet.of(net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME),
         server.getPlayerList().getPlayers()
      );
      server.getPlayerList().broadcastAll(packet);
   }

   public static void setTag(UUID uuid, String text, int rgb) {
      if (text != null && !text.isBlank()) {
         tags.put(uuid, new Tag(text.substring(0, Math.min(text.length(), 24)), rgb));
      } else {
         tags.remove(uuid);
      }
   }

   public static void clearTag(UUID uuid) {
      tags.remove(uuid);
   }

   /** Forgets this player's equipped and owned tags, for the self-test. See TitleManager.forgetForTest. */
   public static void forgetForTest(UUID uuid) {
      tags.remove(uuid);
      ownedTags.remove(uuid);
   }

   public static Tag getTag(UUID uuid) {
      return tags.get(uuid);
   }

   // ---------- Owned (milestone) tags ----------

   /** Records a milestone tag as permanently owned by the player. */
   public static void addOwned(UUID uuid, String tagText) {
      if (uuid != null && tagText != null && !tagText.isBlank()) {
         ownedTags.computeIfAbsent(uuid, u -> new LinkedHashSet<>()).add(tagText);
      }
   }

   /** Tags unlocked through milestones; empty if none yet. */
   public static Set<String> ownedTags(UUID uuid) {
      Set<String> set = ownedTags.get(uuid);
      return set == null ? Set.of() : set;
   }

   /** Equips an owned milestone tag. Returns an error message, or null on success. */
   public static String equipOwned(ServerPlayer player, String tagText) {
      if (tagText == null || tagText.isBlank()) {
         return "That tag doesn't exist.";
      }
      if (!ownedTags(player.getUUID()).contains(tagText)) {
         return "You don't own the tag \"" + tagText + "\".";
      }
      tags.put(player.getUUID(), new Tag(tagText, 5635925));
      return null;
   }

   public static Component tagComponent(ServerPlayer player) {
      // The most-wanted bounty target wears a server-issued WANTED tag
      // (display-only - it never overwrites their saved tag).
      if (player.level().getServer() != null && BountyManager.isMostWanted(player.level().getServer(), player.getUUID())) {
         return Component.literal("☠ WANTED").withStyle(s -> s.withColor(TextColor.fromRgb(16720460)));
      }
      // The server-wide "tags" feature toggle is respected for everything
      // cosmetic - stored tags, the creator tag and the Owner fallback all
      // disappear when it's off. Only the WANTED marker stays (gameplay info).
      if (!ModConfig.is("tags")) {
         return null;
      }
      Tag tag = tags.get(player.getUUID());
      if (tag != null) {
         return Component.literal(tag.text()).withStyle(s -> s.withColor(TextColor.fromRgb(tag.rgb())));
      }
      // The creator has no automatic *tag* any more.
      //
      // There used to be one here, and it was called "Creator of Mod" - the exact name of the
      // exclusive title in TitleManager. So the mod creator had two badges with one name: an
      // automatic tag that appeared on its own, and a title they unlocked on their first join,
      // with special-case code in three places trying to stop the pair of them rendering side by
      // side. The creator's badge is the title now. One name, one owner, one row in the menu -
      // and the suppression that used to hide *every other* title they unlocked goes with it.
      //
      // They are also excluded from the Owner fallback below, or the same problem comes back
      // wearing a different word.
      if (TitleManager.isCreator(player)) {
         return null;
      }
      return player.permissions() != null && player.permissions().hasPermission(Permissions.COMMANDS_OWNER)
         ? Component.literal("Owner").withStyle(s -> s.withColor(TextColor.fromRgb(OWNER_RGB)))
         : null;
   }

   public static Component appendTag(Component base, ServerPlayer player) {
      UUID uuid = player.getUUID();
      // Per-player "Show Tags & Titles" toggle (the /tags menu): hides this
      // player's cosmetic title and tag everywhere. The WANTED marker stays.
      boolean hide = !DisplayPrefsManager.showTags(uuid);
      boolean wanted = player.level().getServer() != null && BountyManager.isMostWanted(player.level().getServer(), uuid);
      Component tag = hide && !wanted ? null : tagComponent(player);
      String title = hide ? null : TitleManager.activeTitle(uuid);
      // If the equipped title and the visible tag are the same thing (e.g. the
      // creator's "Creator of Mod" title vs. their automatic Creator tag), show
      // only ONE instance - never "[Title] Name [Title]". The tag replaces the
      // title prefix, keeping its color in chat and the tab list.
      String tagPlain = tag == null ? null : tag.getString().replaceAll("§[0-9a-fk-or]", "");
      // The only suppression left is the honest one: a title and a tag that read the same are shown
      // once. Everything else is the player's choice, including for the creator - who used to have
      // every other title they unlocked silently swallowed by the automatic badge.
      boolean showTitle = title != null && ModConfig.is("tags") && !title.equalsIgnoreCase(tagPlain);
      Component titlePart = showTitle ? Component.literal("§6[" + title + "§6]§r") : null;
      Component tagPart = tag == null ? null : Component.literal("§7[").append(tag).append(Component.literal("§7]"));
      Component result = base;
      String pos = DisplayPrefsManager.positionOf(player.getUUID());
      if (DisplayPrefsManager.POS_BEFORE.equals(pos)) {
         // [Title] [Tag] Name
         if (titlePart != null) {
            result = Component.literal("").append(titlePart).append(Component.literal(" ")).append(result);
         }
         if (tagPart != null) {
            result = Component.literal("").append(tagPart).append(Component.literal(" ")).append(result);
         }
      } else if (DisplayPrefsManager.POS_AFTER.equals(pos)) {
         // Name [Title] [Tag]
         if (titlePart != null) {
            result = Component.literal("").append(result).append(Component.literal(" ")).append(titlePart);
         }
         if (tagPart != null) {
            result = Component.literal("").append(result).append(Component.literal(" ")).append(tagPart);
         }
      } else {
         // Default: [Title] Name [Tag]
         if (titlePart != null) {
            result = Component.literal("").append(titlePart).append(Component.literal(" ")).append(result);
         }
         if (tagPart != null) {
            result = Component.literal("").append(result).append(Component.literal(" ")).append(tagPart);
         }
      }
      return result;
   }


    public record Tag(String text, int rgb) {
    }
}
