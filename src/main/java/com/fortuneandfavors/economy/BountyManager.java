package com.fortuneandfavors.economy;

import com.fortuneandfavors.ModSounds;
import com.fortuneandfavors.economy.BountyManager.Bounty;
import com.fortuneandfavors.util.Chat;
import com.fortuneandfavors.util.JsonUtil;
import com.fortuneandfavors.util.SoundUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

public final class BountyManager {
   private static final Map<UUID, Bounty> bounties = new LinkedHashMap<>();
   private static Path dataFile;
   /** Cached "most wanted" player + when it was computed (avoids rescanning
    *  every bounty from the tab-list/display mixins every packet). */
   private static UUID wantedTarget;
   private static long wantedAt = -1L;

   private BountyManager() {
   }

   public static void load(MinecraftServer server) {
      bounties.clear();
      dataFile = EconomyManager.getDataDir(server).resolve("bounties.json");
      JsonObject root = JsonUtil.readOrCreate(dataFile, new JsonObject());
      if (root.has("bounties") && root.get("bounties").isJsonArray()) {
         for (JsonElement el : root.getAsJsonArray("bounties")) {
            try {
               JsonObject obj = el.getAsJsonObject();
               Bounty b = new Bounty();
               b.target = UUID.fromString(obj.get("target").getAsString());
               b.poster = UUID.fromString(obj.get("poster").getAsString());
               b.targetName = JsonUtil.jsonString(obj, "target_name", "?");
               b.posterName = JsonUtil.jsonString(obj, "poster_name", "?");
               b.amount = JsonUtil.jsonLong(obj, "amount", 0L);
               if (b.amount > 0L) {
                  bounties.put(b.target, b);
               }
            } catch (Exception var6) {
            }
         }
      }
   }

   public static void save(MinecraftServer server) {
      if (dataFile == null) {
         dataFile = EconomyManager.getDataDir(server).resolve("bounties.json");
      }

      JsonObject root = new JsonObject();
      JsonArray arr = new JsonArray();

      for (Bounty b : bounties.values()) {
         JsonObject obj = new JsonObject();
         obj.addProperty("target", b.target.toString());
         obj.addProperty("poster", b.poster.toString());
         obj.addProperty("target_name", b.targetName);
         obj.addProperty("poster_name", b.posterName);
         obj.addProperty("amount", b.amount);
         arr.add(obj);
      }

      root.add("bounties", arr);
      JsonUtil.write(dataFile, root);
   }

   public static Bounty get(UUID target) {
      return bounties.get(target);
   }

   public static Map<UUID, Bounty> all() {
      return bounties;
   }

   /** The online, non-vanished player holding the biggest bounty, or null. */
   public static ServerPlayer highestBountyPlayer(MinecraftServer server) {
      if (server == null) {
         return null;
      }
      long now = server.getTickCount();
      if (wantedTarget != null && now - wantedAt < 20L) {
         return server.getPlayerList().getPlayer(wantedTarget);
      }
      ServerPlayer best = null;
      long bestAmount = 0L;
      for (Bounty b : bounties.values()) {
         if (b == null || b.target == null || b.amount <= bestAmount) {
            continue;
         }
         ServerPlayer p = server.getPlayerList().getPlayer(b.target);
         if (p != null && p.isAlive() && !com.fortuneandfavors.economy.VanishManager.isVanished(b.target)) {
            best = p;
            bestAmount = b.amount;
         }
      }
      wantedTarget = best == null ? null : best.getUUID();
      wantedAt = now;
      return best;
   }

   /** Red soul-fire aura around whoever currently holds the biggest bounty. */
   public static void tickAura(MinecraftServer server) {
      try {
         if (server == null || server.getTickCount() % 20L != 0L) {
            return;
         }
         ServerPlayer wanted = highestBountyPlayer(server);
         if (wanted == null) {
            return;
         }
         net.minecraft.server.level.ServerLevel level = (net.minecraft.server.level.ServerLevel)wanted.level();
         long t = level.getGameTime();
         double y = wanted.getY() + 0.4;
         com.fortuneandfavors.net.FfVfx.particles(level, net.minecraft.core.particles.ParticleTypes.SOUL_FIRE_FLAME, wanted.getX(), y, wanted.getZ(), 6, 1.1, 0.6, 1.1, 0.0);
         com.fortuneandfavors.net.FfVfx.particles(level, net.minecraft.core.particles.ParticleTypes.SOUL, wanted.getX(), y, wanted.getZ(), 3, 1.4, 0.8, 1.4, 0.01);
         if (t % 60L == 0L) {
            com.fortuneandfavors.net.FfVfx.particles(level, new net.minecraft.core.particles.DustParticleOptions(16719904, 1.4F), wanted.getX(), y + 0.2, wanted.getZ(), 12, 1.3, 0.5, 1.3, 0.02);
         }
      } catch (Exception ignored) {
      }
   }

   /** True while the player is the current most-wanted target (display tag). */
   public static boolean isMostWanted(MinecraftServer server, UUID uuid) {
      if (uuid == null || server == null) {
         return false;
      }
      ServerPlayer wanted = highestBountyPlayer(server);
      return wanted != null && wanted.getUUID().equals(uuid);
   }

   public static boolean place(ServerPlayer poster, ServerPlayer target, long amount) {
      if (poster.getUUID().equals(target.getUUID())) {
         Chat.msg(poster, "&cYou can't put a bounty on yourself!");
         return false;
      }

      if (!EconomyManager.takeCash(poster.getUUID(), amount)) {
         Chat.msg(poster, "&cYou don't have enough money! Need " + Chat.moneyStr(amount));
         return false;
      }

      Bounty b = bounties.get(target.getUUID());
      if (b == null) {
         b = new Bounty();
         b.target = target.getUUID();
         b.poster = poster.getUUID();
         bounties.put(b.target, b);
      }

      b.amount += amount;
      b.targetName = target.getName().getString();
      b.posterName = poster.getName().getString();
      MinecraftServer server = poster.level().getServer();
      save(server);
      Advancements.grant(poster, "bounty_placed");
      SoundUtil.play(poster, ModSounds.BOUNTY);
      Chat.raw(poster, "&aA bounty of " + Chat.moneyStr(b.amount) + "&a is now on &f" + target.getName().getString() + "&a's head!");
      Chat.raw(
         target, "&cA bounty of " + Chat.moneyStr(b.amount) + "&c is now on your head - set by &f" + poster.getName().getString() + "&c. Watch your back!"
      );
      server.getPlayerList()
         .broadcastSystemMessage(
            Component.literal(
               Chat.colorize("&e[!] &f" + target.getName().getString() + "&e now has a &a" + Chat.moneyStr(b.amount) + "&e bounty on their head!")
            ),
            false
         );
      return true;
   }

   public static void onKilled(MinecraftServer server, ServerPlayer victim, ServerPlayer killer) {
      Bounty b = bounties.remove(victim.getUUID());
      if (b != null) {
         if (killer.getUUID().equals(b.poster)) {
            EconomyManager.addCash(b.poster, b.amount);
            SoundUtil.play(killer, ModSounds.TRANSFER);
            Chat.raw(killer, "&eYou killed your own bounty target - " + Chat.moneyStr(b.amount) + "&e refunded.");
            server.getPlayerList()
               .broadcastSystemMessage(Component.literal(Chat.colorize("&7[!] The bounty on &f" + b.targetName + "&7 was withdrawn.")), false);
         } else {
            EconomyManager.addCash(killer.getUUID(), b.amount);
            SoundUtil.play(killer, ModSounds.BOUNTY_CLAIMED);
            Chat.raw(killer, "&a&lBounty claimed!&r &7You collected " + Chat.moneyStr(b.amount) + "&7 for &f" + b.targetName + "&7.");
            server.getPlayerList()
               .broadcastSystemMessage(
                  Component.literal(
                     Chat.colorize(
                        "&e[!] &f" + killer.getName().getString() + "&e claimed the " + Chat.moneyStr(b.amount) + "&e bounty on &f" + b.targetName + "&e!"
                     )
                  ),
                  false
               );
            StreakTrackerManager.onBountyCompleted(killer);
            CooperativeAchievementManager.onBountyCompleted(server, killer);
            DailyWeeklyChallengeManager.onBounty(killer);
            LeaderboardManager.onBountyClaimed(killer);
            Advancements.grant(killer, "bounty_claimed");
            if (StreakTrackerManager.bountyLevel(killer.getUUID()) >= 5) {
               Advancements.grant(killer, "bounty_hunter");
            }
         }

         save(server);
      }
   }

   public static boolean cancel(ServerPlayer poster, ServerPlayer target) {
      Bounty b = bounties.get(target.getUUID());
      if (b != null && b.poster.equals(poster.getUUID())) {
         bounties.remove(target.getUUID());
         EconomyManager.addCash(poster.getUUID(), b.amount);
         save(poster.level().getServer());
         SoundUtil.play(poster, ModSounds.TRANSFER);
         Chat.raw(poster, "&aBounty on &f" + target.getName().getString() + "&a withdrawn - " + Chat.moneyStr(b.amount) + "&a refunded.");
         return true;
      } else {
         Chat.msg(poster, "&cYou don't have a bounty on &f" + target.getName().getString() + "&c.");
         return false;
      }
   }


    static public class Bounty {
       public UUID target;
       public UUID poster;
       public String targetName = "";
       public String posterName = "";
       public long amount;
    }
}
