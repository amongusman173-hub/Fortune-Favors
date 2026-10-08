package com.fortuneandfavors.economy;

import com.fortuneandfavors.util.JsonUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Map.Entry;
import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;

public final class ModConfig {
   private static final Logger LOGGER = LogUtils.getLogger();

   public static final String BALANCE = "balance";
   public static final String SHOP = "shop";
   public static final String EXCLUSIVE = "exclusive";
   public static final String AUCTION = "auction";
   public static final String TRADE = "trade";
   public static final String TOKEN = "token";
   public static final String CLAIMS = "claims";
   public static final String TAGS = "tags";
   public static final String ELEVATOR = "elevator";
   public static final String AUTOSELL = "autosell";
   public static final String JOBS = "jobs";
   public static final String BOUNTY = "bounty";
   public static final String SKILLS = "skills";
   public static final String BOSS = "boss";
   public static final String DUELS = "duels";
   public static final String NICE_KEEP_INVENTORY = "nice_keep_inventory";
   public static final String SCULK = "sculk";
   public static final String[] FEATURES = new String[]{
      "balance",
      "shop",
      "exclusive",
      "auction",
      "trade",
      "token",
      "claims",
      "tags",
      "elevator",
      "autosell",
      "jobs",
      "bounty",
      "skills",
      "boss",
      "duels",
      "nice_keep_inventory",
      "sculk",
      // The player-facing sidebar. A server-wide gate rather than a per-player one, because
      // when it is off the whole point is that nobody is drawing one - the per-player switch
      // is /stats scoreboard enable, which lives on top of this.
      "scoreboard"
   };
   /**
    * Settings that are not simple "is this feature on" gates but still belong to
    * the same server config screen and the same {@code /ff config <name>} entry
    * point. They live here rather than in {@link #FEATURES} because several of
    * them are not booleans to the player (boss despawn is a three-way mode) and
    * the feature list is also what the /menu hub reads to grey out its windows.
    */
   public static final String[] EXTENDED_TOGGLES = new String[]{
      "explosion_rebuild", "raid_cooldown", "wither_rework", "boss_dialogue", "anticheat", "anticheat_ops",
      "anticheat_failsafe", "anticheat_op_kick", "anticheat_autoclicker", "anticheat_aim", "anticheat_abp",
      "anticheat_inventory_walk", "anticheat_quick_inventory", "anticheat_auto_jump", "anticheat_step_assist",
      // The machine particles. Not a feature gate - it turns nothing off but a sparkle - so it lives
      // with the settings rather than in FEATURES, where it would read as "the machines are off".
      "machine_particles"
   };
   private static final Map<String, Boolean> enabled = new HashMap<>();
   private static final Map<UUID, Set<String>> playerPrefs = new HashMap<>();
   private static long auctionMinutes = 60L;
   private static boolean buyNow = true;
   private static boolean dropSpawnersOnDeath = true;
   private static int bossMinions = 4;
   private static String bossDespawn = "fighters";
   private static boolean backups = true;
   private static int backupKeep = 5;
   private static boolean explosionRebuild = true;
   private static boolean raidCooldown = true;
   private static boolean niceKeepInventoryAllowOthersClaim = false;
   /** Reworked wither boss fight (WitherReworkManager). Default on - admins
    *  can revert to the vanilla wither from the /ff config menu. */
   private static boolean witherRework = true;
   /** Boss flavour text - the taunts, telegraphs and ceremony lines bosses
    *  broadcast while a fight runs. On by default; a server that finds it noisy
    *  can silence every boss at once from /ff config. When it is off, mechanics
    *  still announce themselves through particles, sounds and the boss bar, so
    *  turning line-spam down never hides a wind-up you need to dodge. */
   private static boolean bossDialogue = true;
   /**
    * The server-side anticheat. OFF, always, until an admin turns it on: a mod
    * that ships detection no server asked for is a mod that flags players on a
    * server whose owner has never heard of the feature.
    */
   private static boolean anticheat = false;
   /**
    * Whether the anticheat also looks at operators. OFF (operators are skipped),
    * because staff cannot build or test with a movement check mutin' them around;
    * the switch exists so they can turn it on and try to trip it themselves.
    */
   private static boolean anticheatOps = false;
   /**
    * Whether the anticheat's automatic half - the kicks and the five-minute timeout -
    * may act on an operator or an economy admin.
    *
    * <p>OFF, and OFF is the point: {@code /ff anticheat op on} makes operators <i>visible</i>
    * to the checks (so an owner can watch a flag land on their own screen and see that the
    * detector is alive), and it does not make them punishable. Those are two different
    * consents. Being shown that a check fired is information; being disconnected mid-build
    * by a heuristic is not, and staff who asked for the first did not ask for the second.
    *
    * <p>{@code /ff anticheat op kick on} is the second consent, said out loud. With it on,
    * an operator trips the same ladder a player does, kick line by kick line, which is what
    * an owner testing the failsafe actually needs - and it is deliberately its own command
    * rather than a side effect of the first.
    */
   private static boolean anticheatOpKick = false;
   /**
    * The anticheat's automatic half: when a finding is strong enough and no
    * moderator is online, the player is kicked (and, on repeats that day,
    * briefly timed out) rather than merely reported to an empty staff channel.
    * ON by default, but only reachable when the anticheat itself is on - and it
    * always stands down the moment a moderator is present.
    */
   private static boolean anticheatFailsafe = true;
   /**
    * The two checks that judge a <i>pattern</i> rather than an event, and so the
    * two an owner is most likely to want off: click timing and hit precision.
    * ON, because a statistical check that ships silent is a statistical check
    * nobody knows the server has - but each one is its own switch, so a server
    * that finds one of them noisy can silence that one without losing reach,
    * speed or the packet layer.
    *
    * <p>Both are off whenever the anticheat itself is off, and neither is
    * consulted for a player an exemption covers.
    */
   private static boolean anticheatAutoclicker = true;
   private static boolean anticheatAim = true;
   /**
    * The client-mod compatibility modes, all ON, because the whole point of them
    * is that the anticheat is meant to run on a server where players have mods.
    * See {@link com.fortuneandfavors.anticheat.QolCompat} for what each one is
    * allowed to move - and, more importantly, for what it is not: none of them
    * switches a check off, and reach and the packet budget are untouched by all
    * three.
    */
   private static boolean anticheatAbp = true;
   private static boolean anticheatInventoryWalk = true;
   private static boolean anticheatQuickInventory = true;
   /** Auto-jump and step-assist allowances. ON like the rest: see {@link QolCompat}. */
   private static boolean anticheatAutoJump = true;
   private static boolean anticheatStepAssist = true;
   /**
    * Whether a pattern check may teleport a player backwards instead of clamping their
    * velocity.
    *
    * <p><b>Off, and it should stay off.</b> The clamp stops a module gaining and is
    * harmless when the finding was wrong, which is the property that matters on a server
    * where players run their own mods and connections are not all good. The teleport is
    * the stronger correction and the one that produces the complaint this setting exists
    * to answer: a player on a bad connection produces the same bad window a speed module
    * does, and gets yanked backwards for it. The switch is here because a server owner
    * may reasonably disagree, and because having it visible in the config is how anyone
    * reading the file learns which of the two the server is doing.
    */
   private static boolean anticheatHardSetback = false;
   /**
    * The little coloured pulse a working machine gives off. ON by default, and it is the particle
    * budget in {@link MachineVfx} rather than this switch that keeps it cheap; this is the switch a
    * server with a very large machine park flips when even five a tick is five it does not want.
    *
    * <p>It governs the mod's own machine particles only. Vanilla's particles, a boss's telegraphs
    * and every other effect in this mod are untouched, so turning it off quietens the machines and
    * nothing else - which is exactly what a player asking "can I turn those sparkles off" means.
    */
   private static boolean machineParticles = true;
   private static Path file;

   private ModConfig() {
   }

   public static void load(MinecraftServer server) {
      file = EconomyManager.getDataDir(server).resolve("config.json");
      JsonObject root = JsonUtil.readOrCreate(file, new JsonObject());

      for (String f : FEATURES) {
         enabled.put(f, !root.has(f) || root.get(f).getAsBoolean());
      }

      auctionMinutes = clampMinutes(JsonUtil.jsonLong(root, "auction_minutes", 60L));
      buyNow = !root.has("buy_now") || root.get("buy_now").getAsBoolean();
      dropSpawnersOnDeath = !root.has("drop_spawners_on_death") || root.get("drop_spawners_on_death").getAsBoolean();
      niceKeepInventoryAllowOthersClaim = root.has("nice_keep_inventory_allow_others_claim")
         && root.get("nice_keep_inventory_allow_others_claim").getAsBoolean();
      bossMinions = clampMinions(JsonUtil.jsonLong(root, "boss_minions", 4L));
      bossDespawn = normalizeDespawn(JsonUtil.jsonString(root, "boss_despawn", "fighters"));
      backups = !root.has("backups") || root.get("backups").getAsBoolean();
      backupKeep = clampKeep(JsonUtil.jsonLong(root, "backup_keep", 5L));
      // ON unless an admin turns it off. It used to default to OFF, which meant
      // a server that never touched the config silently had no blast recovery
      // at all - and "explosion rebuild does not work" is what that looks like.
      // The other safety features (raid cooldown, the wither rework) already
      // default ON; this one now matches them.
      explosionRebuild = !root.has("explosion_rebuild") || root.get("explosion_rebuild").getAsBoolean();
      if (!explosionRebuild) {
         LOGGER.info(
            "Explosion Rebuild is OFF (config.json has explosion_rebuild=false): blocks destroyed by TNT, creepers, blood revenants and boss blasts will stay gone. Run /explosionrebuild on to restore them."
         );
      }
      raidCooldown = !root.has("raid_cooldown") || root.get("raid_cooldown").getAsBoolean();
      witherRework = !root.has("wither_rework") || root.get("wither_rework").getAsBoolean();
      bossDialogue = !root.has("boss_dialogue") || root.get("boss_dialogue").getAsBoolean();
      anticheat = root.has("anticheat") && root.get("anticheat").getAsBoolean();
      anticheatOps = root.has("anticheat_ops") && root.get("anticheat_ops").getAsBoolean();
      anticheatOpKick = root.has("anticheat_op_kick") && root.get("anticheat_op_kick").getAsBoolean();
      anticheatFailsafe = !root.has("anticheat_failsafe") || root.get("anticheat_failsafe").getAsBoolean();
      anticheatAutoclicker = !root.has("anticheat_autoclicker") || root.get("anticheat_autoclicker").getAsBoolean();
      anticheatAim = !root.has("anticheat_aim") || root.get("anticheat_aim").getAsBoolean();
      anticheatAbp = !root.has("anticheat_abp") || root.get("anticheat_abp").getAsBoolean();
      anticheatInventoryWalk = !root.has("anticheat_inventory_walk") || root.get("anticheat_inventory_walk").getAsBoolean();
      anticheatQuickInventory = !root.has("anticheat_quick_inventory") || root.get("anticheat_quick_inventory").getAsBoolean();
      machineParticles = !root.has("machine_particles") || root.get("machine_particles").getAsBoolean();
      WitherReworkManager.setEnabled(witherRework);
      playerPrefs.clear();
      if (root.has("player_prefs") && root.get("player_prefs").isJsonObject()) {
         for (Entry<String, JsonElement> e : root.getAsJsonObject("player_prefs").entrySet()) {
            try {
               Set<String> keys = new HashSet<>();

               for (JsonElement k : e.getValue().getAsJsonArray()) {
                  keys.add(k.getAsString());
               }

               playerPrefs.put(UUID.fromString(e.getKey()), keys);
            } catch (Exception var7) {
            }
         }
      }
   }

   public static boolean backups() {
      return backups;
   }

   public static String snapshotJson() {
      JsonObject root = new JsonObject();

      for (String f : FEATURES) {
         root.addProperty(f, is(f));
      }

      root.addProperty("auction_minutes", auctionMinutes);
      root.addProperty("buy_now", buyNow);
      root.addProperty("drop_spawners_on_death", dropSpawnersOnDeath);
      root.addProperty("nice_keep_inventory_allow_others_claim", niceKeepInventoryAllowOthersClaim);
      root.addProperty("boss_minions", bossMinions);
      root.addProperty("boss_despawn", bossDespawn);
      root.addProperty("backups", backups);
      root.addProperty("backup_keep", backupKeep);
      root.addProperty("explosion_rebuild", explosionRebuild);
      root.addProperty("raid_cooldown", raidCooldown);
      root.addProperty("wither_rework", witherRework);
      root.addProperty("boss_dialogue", bossDialogue);
      root.addProperty("anticheat", anticheat);
      root.addProperty("anticheat_ops", anticheatOps);
      root.addProperty("anticheat_op_kick", anticheatOpKick);
      root.addProperty("anticheat_failsafe", anticheatFailsafe);
      root.addProperty("anticheat_autoclicker", anticheatAutoclicker);
      root.addProperty("anticheat_aim", anticheatAim);
      root.addProperty("anticheat_auto_jump", anticheatAutoJump);
      root.addProperty("anticheat_step_assist", anticheatStepAssist);
      root.addProperty("anticheat_hard_setback", anticheatHardSetback);
      root.addProperty("anticheat_abp", anticheatAbp);
      root.addProperty("anticheat_inventory_walk", anticheatInventoryWalk);
      root.addProperty("anticheat_quick_inventory", anticheatQuickInventory);
      root.addProperty("machine_particles", machineParticles);
      return root.toString();
   }

   public static void applyJson(String json) {
      JsonObject root = (JsonObject)JsonUtil.gson().fromJson(json, JsonObject.class);
      if (root != null) {
         for (String f : FEATURES) {
            if (root.has(f)) {
               enabled.put(f, JsonUtil.jsonBool(root, f, true));
            }
         }

         if (root.has("auction_minutes")) {
            auctionMinutes = clampMinutes(JsonUtil.jsonLong(root, "auction_minutes", auctionMinutes));
         }

         if (root.has("buy_now")) {
            buyNow = JsonUtil.jsonBool(root, "buy_now", buyNow);
         }

         if (root.has("drop_spawners_on_death")) {
            dropSpawnersOnDeath = JsonUtil.jsonBool(root, "drop_spawners_on_death", dropSpawnersOnDeath);
         }

         if (root.has("nice_keep_inventory_allow_others_claim")) {
            niceKeepInventoryAllowOthersClaim = JsonUtil.jsonBool(root, "nice_keep_inventory_allow_others_claim", niceKeepInventoryAllowOthersClaim);
         }

         if (root.has("boss_minions")) {
            bossMinions = clampMinions(JsonUtil.jsonLong(root, "boss_minions", bossMinions));
         }

         if (root.has("boss_despawn")) {
            bossDespawn = normalizeDespawn(JsonUtil.jsonString(root, "boss_despawn", bossDespawn));
         }

         if (root.has("backups")) {
            backups = JsonUtil.jsonBool(root, "backups", backups);
         }

         if (root.has("backup_keep")) {
            backupKeep = clampKeep(JsonUtil.jsonLong(root, "backup_keep", backupKeep));
         }

         if (root.has("explosion_rebuild")) {
            explosionRebuild = JsonUtil.jsonBool(root, "explosion_rebuild", explosionRebuild);
         }
         if (root.has("raid_cooldown")) {
            raidCooldown = JsonUtil.jsonBool(root, "raid_cooldown", raidCooldown);
         }
         if (root.has("wither_rework")) {
            witherRework = JsonUtil.jsonBool(root, "wither_rework", witherRework);
         }
         if (root.has("boss_dialogue")) {
            bossDialogue = JsonUtil.jsonBool(root, "boss_dialogue", bossDialogue);
         }
         if (root.has("anticheat")) {
            anticheat = JsonUtil.jsonBool(root, "anticheat", anticheat);
         }
         if (root.has("anticheat_ops")) {
            anticheatOps = JsonUtil.jsonBool(root, "anticheat_ops", anticheatOps);
         }
         if (root.has("anticheat_op_kick")) {
            anticheatOpKick = JsonUtil.jsonBool(root, "anticheat_op_kick", anticheatOpKick);
         }
         if (root.has("anticheat_failsafe")) {
            anticheatFailsafe = JsonUtil.jsonBool(root, "anticheat_failsafe", anticheatFailsafe);
         }
         if (root.has("anticheat_autoclicker")) {
            anticheatAutoclicker = JsonUtil.jsonBool(root, "anticheat_autoclicker", anticheatAutoclicker);
         }
         if (root.has("anticheat_aim")) {
            anticheatAim = JsonUtil.jsonBool(root, "anticheat_aim", anticheatAim);
         }
         if (root.has("anticheat_auto_jump")) {
            anticheatAutoJump = JsonUtil.jsonBool(root, "anticheat_auto_jump", anticheatAutoJump);
         }
         if (root.has("anticheat_step_assist")) {
            anticheatStepAssist = JsonUtil.jsonBool(root, "anticheat_step_assist", anticheatStepAssist);
         }
         if (root.has("anticheat_hard_setback")) {
            anticheatHardSetback = JsonUtil.jsonBool(root, "anticheat_hard_setback", anticheatHardSetback);
         }
         if (root.has("anticheat_abp")) {
            anticheatAbp = JsonUtil.jsonBool(root, "anticheat_abp", anticheatAbp);
         }
         if (root.has("anticheat_inventory_walk")) {
            anticheatInventoryWalk = JsonUtil.jsonBool(root, "anticheat_inventory_walk", anticheatInventoryWalk);
         }
         if (root.has("anticheat_quick_inventory")) {
            anticheatQuickInventory = JsonUtil.jsonBool(root, "anticheat_quick_inventory", anticheatQuickInventory);
         }
         if (root.has("machine_particles")) {
            machineParticles = JsonUtil.jsonBool(root, "machine_particles", machineParticles);
         }
      }
   }

   public static int backupKeep() {
      return backupKeep;
   }

   public static boolean explosionRebuild() {
      return explosionRebuild;
   }

   public static void setExplosionRebuild(boolean on) {
      explosionRebuild = on;
   }

   public static boolean raidCooldown() {
      return raidCooldown;
   }

   public static void setRaidCooldown(boolean on) {
      raidCooldown = on;
   }

   public static boolean anticheat() {
      return anticheat;
   }

   public static void setAnticheat(boolean on) {
      anticheat = on;
   }

   public static boolean anticheatOps() {
      return anticheatOps;
   }

   public static void setAnticheatOps(boolean on) {
      anticheatOps = on;
   }

   /**
    * Whether the automatic kicks and timeouts may act on an operator.
    *
    * <p>Separate from {@link #anticheatOps()} on purpose and never set by it: see the field
    * for why "show me the finding" and "disconnect me for it" are two consents.
    */
   public static boolean anticheatOpKick() {
      return anticheatOpKick;
   }

   public static void setAnticheatOpKick(boolean on) {
      anticheatOpKick = on;
   }

   public static boolean anticheatFailsafe() {
      return anticheatFailsafe;
   }

   public static void setAnticheatFailsafe(boolean on) {
      anticheatFailsafe = on;
   }

   /** Whether the click-timing check is allowed to run at all. */
   public static boolean anticheatAutoclicker() {
      return anticheatAutoclicker;
   }

   public static void setAnticheatAutoclicker(boolean on) {
      anticheatAutoclicker = on;
   }

   /** Whether the hit-precision check is allowed to run at all. */
   public static boolean anticheatAim() {
      return anticheatAim;
   }

   public static void setAnticheatAim(boolean on) {
      anticheatAim = on;
   }

   /** Accurate Block Placement Reborn compatibility. */
   public static boolean anticheatAutoJump() {
      return anticheatAutoJump;
   }

   public static boolean anticheatStepAssist() {
      return anticheatStepAssist;
   }

   /** Whether pattern checks may teleport rather than clamp. Off by default. */
   public static boolean anticheatHardSetback() {
      return anticheatHardSetback;
   }

   public static void setAnticheatHardSetback(boolean on) {
      anticheatHardSetback = on;
   }

   public static boolean anticheatAbp() {
      return anticheatAbp;
   }

   public static void setAnticheatAbp(boolean on) {
      anticheatAbp = on;
   }

   /** Inventory Walk compatibility. */
   public static boolean anticheatInventoryWalk() {
      return anticheatInventoryWalk;
   }

   public static void setAnticheatInventoryWalk(boolean on) {
      anticheatInventoryWalk = on;
   }

   /** Mouse Tweaks and inventory-sorting compatibility. */
   public static boolean anticheatQuickInventory() {
      return anticheatQuickInventory;
   }

   public static void setAnticheatQuickInventory(boolean on) {
      anticheatQuickInventory = on;
   }

   public static boolean bossDialogue() {
      return bossDialogue;
   }

   public static void setBossDialogue(boolean on) {
      bossDialogue = on;
   }

   public static boolean witherRework() {
      return witherRework;
   }

   public static void setWitherRework(boolean on) {
      witherRework = on;
      WitherReworkManager.setEnabled(on);
   }

   public static void save(MinecraftServer server) {
      if (file == null) {
         file = EconomyManager.getDataDir(server).resolve("config.json");
      }

      JsonObject root = new JsonObject();
      root.addProperty("_comment", "Server settings. Set a feature to false to disable it, or use /fortuneandfavors config in-game.");

      for (String f : FEATURES) {
         root.addProperty(f, enabled.getOrDefault(f, true));
      }

      root.addProperty("auction_minutes", auctionMinutes);
      root.addProperty("buy_now", buyNow);
      root.addProperty("drop_spawners_on_death", dropSpawnersOnDeath);
      root.addProperty("nice_keep_inventory_allow_others_claim", niceKeepInventoryAllowOthersClaim);
      root.addProperty("boss_minions", bossMinions);
      root.addProperty("boss_despawn", bossDespawn);
      root.addProperty("backups", backups);
      root.addProperty("backup_keep", backupKeep);
      root.addProperty("explosion_rebuild", explosionRebuild);
      root.addProperty("raid_cooldown", raidCooldown);
      root.addProperty("wither_rework", witherRework);
      root.addProperty("boss_dialogue", bossDialogue);
      root.addProperty("anticheat", anticheat);
      root.addProperty("anticheat_ops", anticheatOps);
      root.addProperty("anticheat_op_kick", anticheatOpKick);
      root.addProperty("anticheat_failsafe", anticheatFailsafe);
      root.addProperty("anticheat_autoclicker", anticheatAutoclicker);
      root.addProperty("anticheat_aim", anticheatAim);
      root.addProperty("anticheat_auto_jump", anticheatAutoJump);
      root.addProperty("anticheat_step_assist", anticheatStepAssist);
      root.addProperty("anticheat_hard_setback", anticheatHardSetback);
      root.addProperty("anticheat_abp", anticheatAbp);
      root.addProperty("anticheat_inventory_walk", anticheatInventoryWalk);
      root.addProperty("anticheat_quick_inventory", anticheatQuickInventory);
      root.addProperty("machine_particles", machineParticles);
      if (!playerPrefs.isEmpty()) {
         JsonObject prefs = new JsonObject();

         for (Entry<UUID, Set<String>> e : playerPrefs.entrySet()) {
            JsonArray arr = new JsonArray();

            for (String k : e.getValue()) {
               arr.add(k);
            }

            prefs.add(e.getKey().toString(), arr);
         }

         root.add("player_prefs", prefs);
      }

      JsonUtil.write(file, root);
   }

   public static boolean playerPref(UUID uuid, String key) {
      Set<String> keys = playerPrefs.get(uuid);
      return keys != null && keys.contains(key);
   }

   public static boolean togglePlayerPref(UUID uuid, String key) {
      Set<String> keys = playerPrefs.computeIfAbsent(uuid, u -> new HashSet<>());
      if (!keys.add(key)) {
         keys.remove(key);
         return false;
      } else {
         return true;
      }
   }

   public static long auctionMinutes() {
      return auctionMinutes;
   }

   public static void setAuctionMinutes(long minutes) {
      auctionMinutes = clampMinutes(minutes);
   }

   public static boolean buyNow() {
      return buyNow;
   }

   public static void setBuyNow(boolean on) {
      buyNow = on;
   }

   public static boolean dropSpawnersOnDeath() {
      return dropSpawnersOnDeath;
   }

   public static void setDropSpawnersOnDeath(boolean on) {
      dropSpawnersOnDeath = on;
   }

   private static long clampMinutes(long m) {
      return Math.max(1L, Math.min(10080L, m));
   }

   private static int clampMinions(long n) {
      return (int)Math.max(1L, Math.min(12L, n));
   }

   private static int clampKeep(long n) {
      return (int)Math.max(1L, Math.min(20L, n));
   }

   public static int bossMinions() {
      return bossMinions;
   }

   public static void setBossMinions(int n) {
      bossMinions = clampMinions(n);
   }

   public static String bossDespawn() {
      return bossDespawn;
   }

   public static void setBossDespawn(String mode) {
      bossDespawn = normalizeDespawn(mode);
   }

   private static String normalizeDespawn(String mode) {
      if (mode == null) {
         return "fighters";
      } else {
         String m = mode.trim().toLowerCase();
         if (m.startsWith("never") || m.equals("off") || m.equals("false")) {
            return "never";
         } else {
            return m.startsWith("summoner") ? "summoner" : "fighters";
         }
      }
   }

   public static boolean is(String feature) {
      return enabled.getOrDefault(feature, true);
   }

   public static void setFeature(String feature, boolean on) {
      enabled.put(feature, on);
   }

   public static boolean niceKeepInventoryAllowOthersClaim() {
      return niceKeepInventoryAllowOthersClaim;
   }

   public static void setNiceKeepInventoryAllowOthersClaim(boolean on) {
      niceKeepInventoryAllowOthersClaim = on;
   }

   public static boolean toggle(String feature) {
      boolean now = !is(feature);
      enabled.put(feature, now);
      return now;
   }

   public static void enableAll() {
      for (String f : FEATURES) {
         enabled.put(f, true);
      }
   }

   public static boolean isExtendedToggle(String name) {
      if (name == null) {
         return false;
      }
      for (String t : EXTENDED_TOGGLES) {
         if (t.equals(name)) {
            return true;
         }
      }
      return false;
   }

   public static String extendedDisplayName(String name) {
      return switch (name) {
         case "explosion_rebuild" -> "Explosion rebuild";
         case "raid_cooldown" -> "Raid cooldown";
         case "wither_rework" -> "Reworked Wither";
         case "boss_dialogue" -> "Boss dialogue";
         case "anticheat" -> "Anticheat";
         case "anticheat_ops" -> "Anticheat: operators too";
         case "anticheat_op_kick" -> "Anticheat: operators may be kicked";
         case "anticheat_failsafe" -> "Anticheat: failsafe auto-kick";
         case "anticheat_autoclicker" -> "Anticheat: click timing";
         case "anticheat_aim" -> "Anticheat: hit precision";
         case "anticheat_abp" -> "Anticheat: accurate placement (ABP)";
         case "anticheat_inventory_walk" -> "Anticheat: inventory walk";
         case "anticheat_quick_inventory" -> "Anticheat: mouse tweaks / sorting";
         case "machine_particles" -> "Machine particles";
         default -> name;
      };
   }

   /** Flips an extended toggle and returns its new state. */
   public static boolean toggleExtended(String name) {
      switch (name) {
         case "explosion_rebuild" -> setExplosionRebuild(!explosionRebuild);
         case "raid_cooldown" -> setRaidCooldown(!raidCooldown);
         case "wither_rework" -> setWitherRework(!witherRework);
         case "boss_dialogue" -> setBossDialogue(!bossDialogue);
         case "anticheat" -> setAnticheat(!anticheat);
         case "anticheat_ops" -> setAnticheatOps(!anticheatOps);
         case "anticheat_op_kick" -> setAnticheatOpKick(!anticheatOpKick);
         case "anticheat_failsafe" -> setAnticheatFailsafe(!anticheatFailsafe);
         case "anticheat_autoclicker" -> setAnticheatAutoclicker(!anticheatAutoclicker);
         case "anticheat_aim" -> setAnticheatAim(!anticheatAim);
         case "anticheat_abp" -> setAnticheatAbp(!anticheatAbp);
         case "anticheat_inventory_walk" -> setAnticheatInventoryWalk(!anticheatInventoryWalk);
         case "anticheat_quick_inventory" -> setAnticheatQuickInventory(!anticheatQuickInventory);
         case "machine_particles" -> setMachineParticles(!machineParticles);
         default -> {
            return false;
         }
      }
      return switch (name) {
         case "explosion_rebuild" -> explosionRebuild;
         case "raid_cooldown" -> raidCooldown;
         case "wither_rework" -> witherRework;
         case "boss_dialogue" -> bossDialogue;
         case "anticheat" -> anticheat;
         case "anticheat_ops" -> anticheatOps;
         case "anticheat_op_kick" -> anticheatOpKick;
         case "anticheat_failsafe" -> anticheatFailsafe;
         case "anticheat_autoclicker" -> anticheatAutoclicker;
         case "anticheat_aim" -> anticheatAim;
         case "anticheat_abp" -> anticheatAbp;
         case "anticheat_inventory_walk" -> anticheatInventoryWalk;
         case "anticheat_quick_inventory" -> anticheatQuickInventory;
         case "machine_particles" -> machineParticles;
         default -> false;
      };
   }

   /** Whether working machines may give off their little coloured pulse. See {@link MachineVfx}. */
   public static boolean machineParticles() {
      return machineParticles;
   }

   public static void setMachineParticles(boolean on) {
      machineParticles = on;
   }

   public static boolean isFeature(String name) {
      for (String f : FEATURES) {
         if (f.equals(name)) {
            return true;
         }
      }

      return false;
   }

   public static String displayName(String feature) {
      return switch (feature) {
         case "balance" -> "Balance";
         case "shop" -> "Shop & sell";
         case "exclusive" -> "Exclusive chest shops";
         case "auction" -> "Auction house";
         case "trade" -> "Trading";
         case "token" -> "Favor tokens";
         case "claims" -> "Land claims";
         case "tags" -> "Tags";
         case "elevator" -> "Elevators";
         case "autosell" -> "Auto-sell hoppers";
         case "jobs" -> "Job board";
         case "bounty" -> "Bounties";
         case "skills" -> "Skills & mystery boxes";
         case "boss" -> "Server bosses";
         case "duels" -> "Duels";
         case "nice_keep_inventory" -> "Nice Keep Inventory";
         case "sculk" -> "Sculk abilities";
         default -> feature;
      };
   }
}
