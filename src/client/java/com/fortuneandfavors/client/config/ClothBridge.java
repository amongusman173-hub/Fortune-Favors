package com.fortuneandfavors.client.config;

import com.fortuneandfavors.client.ScreenFx;
import com.fortuneandfavors.client.config.FfConfigState.BossDespawn;
import com.fortuneandfavors.client.config.FfClientConfig.ServerCategory;
import com.fortuneandfavors.economy.ModConfig;
import me.shedaniel.autoconfig.AutoConfig;
import me.shedaniel.autoconfig.ConfigHolder;
import me.shedaniel.autoconfig.serializer.GsonConfigSerializer;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.InteractionResult;

/** The ONLY class allowed to touch Cloth Config classes. It is class-loaded
 *  exclusively from behind {@code FabricLoader.isModLoaded("cloth-config")}
 *  checks in {@link FfConfigManager}, so installs without Cloth Config never
 *  trigger a NoClassDefFoundError (the 26.2(1) crash).
 *
 *  {@link FfConfigState} is the source of truth; this bridge keeps the Cloth
 *  holder in sync with it and both share config/fortuneandfavors.json. */
final class ClothBridge {
   private ClothBridge() {
   }

   /** Registers the Cloth config (same file as FfConfigState) and mirrors the
    *  loaded values into the state. */
   static void init() {
      AutoConfig.register(FfClientConfig.class, GsonConfigSerializer::new);
      ConfigHolder<FfClientConfig> holder = AutoConfig.getConfigHolder(FfClientConfig.class);
      holder.registerSaveListener((h, cfg) -> {
         copyToState(cfg);
         // Single authoritative post-save path: applies FX, persists the shared
         // file and pushes server settings to the server when it granted us
         // edit rights (ops only).
         FfConfigManager.onSaved();
         return InteractionResult.SUCCESS;
      });
      copyToState(holder.getConfig());
   }

   /** Re-reads the Cloth config from disk and re-mirrors it into the state -
    *  used when the file may have changed underneath us. */
   static void reload() {
      ConfigHolder<FfClientConfig> holder = AutoConfig.getConfigHolder(FfClientConfig.class);
      holder.load();
      copyToState(holder.getConfig());
   }

   /** Builds the ModMenu config screen for the Cloth-backed config. */
   static Screen screen(Screen parent) {
      return (Screen)me.shedaniel.autoconfig.AutoConfigClient
         .getConfigScreen(FfClientConfig.class, parent).get();
   }

   private static void copyToState(FfClientConfig cfg) {
      FfConfigState s = FfConfigState.get();

      s.client.devourOverlay = cfg.client.devourOverlay;
      s.client.corruptionOverlay = cfg.client.corruptionOverlay;
      s.client.linkedOverlay = cfg.client.linkedOverlay;
      s.client.swordBlockPose = cfg.client.swordBlockPose;
      s.client.goldenAppleFlash = cfg.client.goldenAppleFlash;
      s.client.deadeyeFlash = cfg.client.deadeyeFlash;

      ServerCategory from = cfg.server;
      FfConfigState.ServerCategory to = s.server;

      for (String f : ModConfig.FEATURES) {
         setFeature(to, f, feature(from, f));
      }

      to.auctionMinutes = from.auctionMinutes;
      to.buyNow = from.buyNow;
      to.dropSpawnersOnDeath = from.dropSpawnersOnDeath;
      to.niceKeepInventoryAllowOthersClaim = from.niceKeepInventoryAllowOthersClaim;
      to.bossMinions = from.bossMinions;
      to.bossDespawn = fromEnum(from.bossDespawn);
      to.backups = from.backups;
      to.backupKeep = from.backupKeep;
      to.explosionRebuild = from.explosionRebuild;
      to.raidCooldown = from.raidCooldown;
      to.witherRework = from.witherRework;
   }

   /** Copies the state's values into the Cloth holder's config IN MEMORY ONLY
    *  (no save, no listeners) - used after a server snapshot corrects our
    *  mirror, so the config screen shows the server's values on next open. */
   static void mirrorStateToCloth() {
      FfConfigState s = FfConfigState.get();
      ConfigHolder<FfClientConfig> holder = AutoConfig.getConfigHolder(FfClientConfig.class);
      FfClientConfig cfg = holder.getConfig();

      cfg.client.devourOverlay = s.client.devourOverlay;
      cfg.client.corruptionOverlay = s.client.corruptionOverlay;
      cfg.client.linkedOverlay = s.client.linkedOverlay;
      cfg.client.swordBlockPose = s.client.swordBlockPose;
      cfg.client.goldenAppleFlash = s.client.goldenAppleFlash;
      cfg.client.deadeyeFlash = s.client.deadeyeFlash;

      ServerCategory to = cfg.server;
      FfConfigState.ServerCategory from = s.server;

      for (String f : ModConfig.FEATURES) {
         setFeature(to, f, feature(from, f));
      }

      to.auctionMinutes = from.auctionMinutes;
      to.buyNow = from.buyNow;
      to.dropSpawnersOnDeath = from.dropSpawnersOnDeath;
      to.niceKeepInventoryAllowOthersClaim = from.niceKeepInventoryAllowOthersClaim;
      to.bossMinions = from.bossMinions;
      to.bossDespawn = toEnum(from.bossDespawn);
      to.backups = from.backups;
      to.backupKeep = from.backupKeep;
      to.explosionRebuild = from.explosionRebuild;
      to.raidCooldown = from.raidCooldown;
      to.witherRework = from.witherRework;
   }

   static void applyClientFx() {
      FfConfigState.ClientCategory c = FfConfigState.get().client;
      ScreenFx.setEffectEnabled(1, c.devourOverlay);
      ScreenFx.setEffectEnabled(2, c.corruptionOverlay);
      ScreenFx.setEffectEnabled(3, c.linkedOverlay);
      ScreenFx.setEffectEnabled(4, c.swordBlockPose);
      ScreenFx.setEffectEnabled(5, c.goldenAppleFlash);
      ScreenFx.setEffectEnabled(6, c.deadeyeFlash);
   }

   private static boolean feature(FfConfigState.ServerCategory s, String key) {
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

   private static boolean feature(FfClientConfig.ServerCategory s, String key) {
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

   private static void setFeature(FfConfigState.ServerCategory s, String key, boolean on) {
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

   private static void setFeature(FfClientConfig.ServerCategory s, String key, boolean on) {
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

   private static BossDespawn fromEnum(FfClientConfig.BossDespawn d) {
      if (d == null) {
         return BossDespawn.FIGHTERS;
      }
      return switch (d) {
         case SUMMONER -> BossDespawn.SUMMONER;
         case NEVER -> BossDespawn.NEVER;
         default -> BossDespawn.FIGHTERS;
      };
   }

   private static FfClientConfig.BossDespawn toEnum(BossDespawn d) {
      if (d == null) {
         return FfClientConfig.BossDespawn.FIGHTERS;
      }
      return switch (d) {
         case SUMMONER -> FfClientConfig.BossDespawn.SUMMONER;
         case NEVER -> FfClientConfig.BossDespawn.NEVER;
         default -> FfClientConfig.BossDespawn.FIGHTERS;
      };
   }
}
