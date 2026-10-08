package com.fortuneandfavors.client.config;

import me.shedaniel.autoconfig.ConfigData;
import me.shedaniel.autoconfig.annotation.Config;
import me.shedaniel.autoconfig.annotation.ConfigEntry.Category;

@Config(name = "fortuneandfavors")
public class FfClientConfig implements ConfigData {
   @Category("client")
   public ClientCategory client = new ClientCategory();
   @Category("server")
   public ServerCategory server = new ServerCategory();

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
}
