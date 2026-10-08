package com.fortuneandfavors.client.config;

import com.fortuneandfavors.FortuneFavorsMod;
import com.fortuneandfavors.economy.ModConfig;
import com.fortuneandfavors.util.JsonUtil;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

/** The always-loadable config state for Fortune & Favors.
 *
 *  This class deliberately touches NO Cloth Config classes, so the whole client
 *  side keeps working on installs without cloth-config (which our jar only
 *  "suggests" - see the 26.2(1) instance crash). When Cloth IS present,
 *  {@link ClothBridge} mirrors the Cloth screen into this state and both share
 *  the same file: config/fortuneandfavors.json, with the exact nested
 *  "client"/"server" shape Cloth's GsonConfigSerializer writes, so the two
 *  never fight over the file. */
public final class FfConfigState {
   public static class ClientCategory {
      public boolean devourOverlay = true;
      public boolean corruptionOverlay = true;
      public boolean linkedOverlay = true;
      public boolean swordBlockPose = true;
      public boolean goldenAppleFlash = true;
      public boolean deadeyeFlash = true;
   }

   public static class ServerCategory {
      public boolean balance = true;
      public boolean shop = true;
      public boolean exclusive = true;
      public boolean auction = true;
      public boolean trade = true;
      public boolean token = true;
      public boolean claims = true;
      public boolean tags = true;
      public boolean elevator = true;
      public boolean autosell = true;
      public boolean jobs = true;
      public boolean bounty = true;
      public boolean skills = true;
      public boolean boss = true;
      public boolean duels = true;
      public boolean niceKeepInventory = true;
      public boolean sculk = true;
      public long auctionMinutes = 60L;
      public boolean buyNow = true;
      public boolean dropSpawnersOnDeath = true;
      public boolean niceKeepInventoryAllowOthersClaim = false;
      public int bossMinions = 4;
      public BossDespawn bossDespawn = BossDespawn.FIGHTERS;
      public boolean backups = true;
      public int backupKeep = 5;
      public boolean explosionRebuild = false;
      public boolean raidCooldown = true;
      public boolean witherRework = false;
   }

   public enum BossDespawn {
      FIGHTERS,
      SUMMONER,
      NEVER
   }

   private static final FfConfigState INSTANCE = new FfConfigState();

   public final ClientCategory client = new ClientCategory();
   public final ServerCategory server = new ServerCategory();

   private FfConfigState() {
   }

   public static FfConfigState get() {
      return INSTANCE;
   }

   private static Path file() {
      return FabricLoader.getInstance().getConfigDir().resolve("fortuneandfavors.json");
   }

   /** Reads config/fortuneandfavors.json (creating it with defaults if absent).
    *  Never throws - a broken file just means defaults. */
   public static void load() {
      try {
         Path f = file();
         if (!Files.isRegularFile(f)) {
            save();
            return;
         }
         JsonObject root = (JsonObject)JsonUtil.gson().fromJson(Files.readString(f), JsonObject.class);
         if (root == null) {
            return;
         }
         FfConfigState s = INSTANCE;
         if (root.has("client") && root.get("client").isJsonObject()) {
            JsonObject c = root.getAsJsonObject("client");
            s.client.devourOverlay = JsonUtil.jsonBool(c, "devourOverlay", s.client.devourOverlay);
            s.client.corruptionOverlay = JsonUtil.jsonBool(c, "corruptionOverlay", s.client.corruptionOverlay);
            s.client.linkedOverlay = JsonUtil.jsonBool(c, "linkedOverlay", s.client.linkedOverlay);
            s.client.swordBlockPose = JsonUtil.jsonBool(c, "swordBlockPose", s.client.swordBlockPose);
            s.client.goldenAppleFlash = JsonUtil.jsonBool(c, "goldenAppleFlash", s.client.goldenAppleFlash);
            s.client.deadeyeFlash = JsonUtil.jsonBool(c, "deadeyeFlash", s.client.deadeyeFlash);
         }
         if (root.has("server") && root.get("server").isJsonObject()) {
            s.readDiskServer(root.getAsJsonObject("server"));
         }
      } catch (Throwable t) {
         FortuneFavorsMod.LOGGER.warn("Fortune & Favors: could not read config/fortuneandfavors.json - using defaults", t);
      }
   }

   /** Writes the state to config/fortuneandfavors.json in the same nested shape
    *  Cloth's serializer uses, so both writers share one file safely. */
   public static void save() {
      try {
         FfConfigState s = INSTANCE;
         JsonObject c = new JsonObject();
         c.addProperty("devourOverlay", s.client.devourOverlay);
         c.addProperty("corruptionOverlay", s.client.corruptionOverlay);
         c.addProperty("linkedOverlay", s.client.linkedOverlay);
         c.addProperty("swordBlockPose", s.client.swordBlockPose);
         c.addProperty("goldenAppleFlash", s.client.goldenAppleFlash);
         c.addProperty("deadeyeFlash", s.client.deadeyeFlash);

         // Written in Cloth's exact field-name shape (camelCase), so Cloth's
         // GsonConfigSerializer reads the same file without surprises.
         JsonObject srv = new JsonObject();
         srv.addProperty("balance", s.server.balance);
         srv.addProperty("shop", s.server.shop);
         srv.addProperty("exclusive", s.server.exclusive);
         srv.addProperty("auction", s.server.auction);
         srv.addProperty("trade", s.server.trade);
         srv.addProperty("token", s.server.token);
         srv.addProperty("claims", s.server.claims);
         srv.addProperty("tags", s.server.tags);
         srv.addProperty("elevator", s.server.elevator);
         srv.addProperty("autosell", s.server.autosell);
         srv.addProperty("jobs", s.server.jobs);
         srv.addProperty("bounty", s.server.bounty);
         srv.addProperty("skills", s.server.skills);
         srv.addProperty("boss", s.server.boss);
         srv.addProperty("duels", s.server.duels);
         srv.addProperty("niceKeepInventory", s.server.niceKeepInventory);
         srv.addProperty("sculk", s.server.sculk);
         srv.addProperty("auctionMinutes", s.server.auctionMinutes);
         srv.addProperty("buyNow", s.server.buyNow);
         srv.addProperty("dropSpawnersOnDeath", s.server.dropSpawnersOnDeath);
         srv.addProperty("niceKeepInventoryAllowOthersClaim", s.server.niceKeepInventoryAllowOthersClaim);
         srv.addProperty("bossMinions", s.server.bossMinions);
         srv.addProperty("bossDespawn", s.server.bossDespawn.name());
         srv.addProperty("backups", s.server.backups);
         srv.addProperty("backupKeep", s.server.backupKeep);
         srv.addProperty("explosionRebuild", s.server.explosionRebuild);
         srv.addProperty("raidCooldown", s.server.raidCooldown);
         srv.addProperty("witherRework", s.server.witherRework);

         JsonObject root = new JsonObject();
         root.add("client", c);
         root.add("server", srv);

         Path f = file();
         Files.createDirectories(f.getParent());
         Files.writeString(f, JsonUtil.gson().toJson(root));
      } catch (Throwable t) {
         FortuneFavorsMod.LOGGER.warn("Fortune & Favors: could not write config/fortuneandfavors.json", t);
      }
   }

   /** Reads the server section from the on-disk file, which uses Cloth's
    *  camelCase field names (unlike the snake_case network protocol). */
   public void readDiskServer(JsonObject srv) {
      ServerCategory s = this.server;

      s.balance = JsonUtil.jsonBool(srv, "balance", s.balance);
      s.shop = JsonUtil.jsonBool(srv, "shop", s.shop);
      s.exclusive = JsonUtil.jsonBool(srv, "exclusive", s.exclusive);
      s.auction = JsonUtil.jsonBool(srv, "auction", s.auction);
      s.trade = JsonUtil.jsonBool(srv, "trade", s.trade);
      s.token = JsonUtil.jsonBool(srv, "token", s.token);
      s.claims = JsonUtil.jsonBool(srv, "claims", s.claims);
      s.tags = JsonUtil.jsonBool(srv, "tags", s.tags);
      s.elevator = JsonUtil.jsonBool(srv, "elevator", s.elevator);
      s.autosell = JsonUtil.jsonBool(srv, "autosell", s.autosell);
      s.jobs = JsonUtil.jsonBool(srv, "jobs", s.jobs);
      s.bounty = JsonUtil.jsonBool(srv, "bounty", s.bounty);
      s.skills = JsonUtil.jsonBool(srv, "skills", s.skills);
      s.boss = JsonUtil.jsonBool(srv, "boss", s.boss);
      s.duels = JsonUtil.jsonBool(srv, "duels", s.duels);
      s.niceKeepInventory = JsonUtil.jsonBool(srv, "niceKeepInventory", s.niceKeepInventory);
      s.sculk = JsonUtil.jsonBool(srv, "sculk", s.sculk);

      s.auctionMinutes = Math.max(1L, Math.min(10080L, JsonUtil.jsonLong(srv, "auctionMinutes", s.auctionMinutes)));
      s.buyNow = JsonUtil.jsonBool(srv, "buyNow", s.buyNow);
      s.dropSpawnersOnDeath = JsonUtil.jsonBool(srv, "dropSpawnersOnDeath", s.dropSpawnersOnDeath);
      s.niceKeepInventoryAllowOthersClaim = JsonUtil.jsonBool(srv, "niceKeepInventoryAllowOthersClaim", s.niceKeepInventoryAllowOthersClaim);
      s.bossMinions = (int)Math.max(1L, Math.min(12L, JsonUtil.jsonLong(srv, "bossMinions", s.bossMinions)));
      String bd = JsonUtil.jsonString(srv, "bossDespawn", "");
      if (!bd.isEmpty()) {
         try {
            s.bossDespawn = BossDespawn.valueOf(bd);
         } catch (IllegalArgumentException ignored) {
            // keep current
         }
      }
      s.backups = JsonUtil.jsonBool(srv, "backups", s.backups);
      s.backupKeep = (int)Math.max(1L, Math.min(20L, JsonUtil.jsonLong(srv, "backupKeep", s.backupKeep)));
      s.explosionRebuild = JsonUtil.jsonBool(srv, "explosionRebuild", s.explosionRebuild);
      s.raidCooldown = JsonUtil.jsonBool(srv, "raidCooldown", s.raidCooldown);
      s.witherRework = JsonUtil.jsonBool(srv, "witherRework", s.witherRework);
   }

   /** Pulls server settings out of the network snapshot (snake_case protocol
    *  keys) with the same clamps as before. */
   public void applyServerSnapshot(JsonObject root) {
      ServerCategory s = this.server;

      for (String f : ModConfig.FEATURES) {
         setFeature(s, f, JsonUtil.jsonBool(root, f, feature(s, f)));
      }

      s.auctionMinutes = Math.max(1L, Math.min(10080L, JsonUtil.jsonLong(root, "auction_minutes", s.auctionMinutes)));
      s.buyNow = JsonUtil.jsonBool(root, "buy_now", s.buyNow);
      s.dropSpawnersOnDeath = JsonUtil.jsonBool(root, "drop_spawners_on_death", s.dropSpawnersOnDeath);
      s.niceKeepInventoryAllowOthersClaim = JsonUtil.jsonBool(root, "nice_keep_inventory_allow_others_claim", s.niceKeepInventoryAllowOthersClaim);
      s.bossMinions = (int)Math.max(1L, Math.min(12L, JsonUtil.jsonLong(root, "boss_minions", s.bossMinions)));
      s.bossDespawn = bossDespawnFromJson(JsonUtil.jsonString(root, "boss_despawn", ""));
      s.backups = JsonUtil.jsonBool(root, "backups", s.backups);
      s.backupKeep = (int)Math.max(1L, Math.min(20L, JsonUtil.jsonLong(root, "backup_keep", s.backupKeep)));
      s.explosionRebuild = JsonUtil.jsonBool(root, "explosion_rebuild", s.explosionRebuild);
      s.raidCooldown = JsonUtil.jsonBool(root, "raid_cooldown", s.raidCooldown);
      s.witherRework = JsonUtil.jsonBool(root, "wither_rework", s.witherRework);
   }

   /** Builds the server-payload JSON (snake_case protocol keys). */
   public JsonObject toServerJson() {
      JsonObject root = new JsonObject();
      ServerCategory s = this.server;

      for (String f : ModConfig.FEATURES) {
         root.addProperty(f, feature(s, f));
      }

      root.addProperty("auction_minutes", s.auctionMinutes);
      root.addProperty("buy_now", s.buyNow);
      root.addProperty("drop_spawners_on_death", s.dropSpawnersOnDeath);
      root.addProperty("nice_keep_inventory_allow_others_claim", s.niceKeepInventoryAllowOthersClaim);
      root.addProperty("boss_minions", s.bossMinions);
      root.addProperty("boss_despawn", bossDespawnName(s.bossDespawn));
      root.addProperty("backups", s.backups);
      root.addProperty("backup_keep", s.backupKeep);
      root.addProperty("explosion_rebuild", s.explosionRebuild);
      root.addProperty("raid_cooldown", s.raidCooldown);
      root.addProperty("wither_rework", s.witherRework);
      return root;
   }

   private static boolean feature(ServerCategory s, String key) {
      return switch (key) {
         case "balance" -> s.balance;
         case "shop" -> s.shop;
         case "exclusive" -> s.exclusive;
         case "auction" -> s.auction;
         case "trade" -> s.trade;
         case "token" -> s.token;
         case "claims" -> s.claims;
         case "tags" -> s.tags;
         case "elevator" -> s.elevator;
         case "autosell" -> s.autosell;
         case "jobs" -> s.jobs;
         case "bounty" -> s.bounty;
         case "skills" -> s.skills;
         case "boss" -> s.boss;
         case "duels" -> s.duels;
         case "nice_keep_inventory" -> s.niceKeepInventory;
         case "sculk" -> s.sculk;
         default -> true;
      };
   }

   private static void setFeature(ServerCategory s, String key, boolean on) {
      switch (key) {
         case "balance":
            s.balance = on;
            break;
         case "shop":
            s.shop = on;
            break;
         case "exclusive":
            s.exclusive = on;
            break;
         case "auction":
            s.auction = on;
            break;
         case "trade":
            s.trade = on;
            break;
         case "token":
            s.token = on;
            break;
         case "claims":
            s.claims = on;
            break;
         case "tags":
            s.tags = on;
            break;
         case "elevator":
            s.elevator = on;
            break;
         case "autosell":
            s.autosell = on;
            break;
         case "jobs":
            s.jobs = on;
            break;
         case "bounty":
            s.bounty = on;
            break;
         case "skills":
            s.skills = on;
            break;
         case "boss":
            s.boss = on;
            break;
         case "duels":
            s.duels = on;
            break;
         case "nice_keep_inventory":
            s.niceKeepInventory = on;
            break;
         case "sculk":
            s.sculk = on;
      }
   }

   private static BossDespawn bossDespawnFromJson(String v) {
      return switch (v) {
         case "summoner" -> BossDespawn.SUMMONER;
         case "never" -> BossDespawn.NEVER;
         default -> BossDespawn.FIGHTERS;
      };
   }

   private static String bossDespawnName(BossDespawn d) {
      return switch (d) {
         case SUMMONER -> "summoner";
         case NEVER -> "never";
         default -> "fighters";
      };
   }
}
