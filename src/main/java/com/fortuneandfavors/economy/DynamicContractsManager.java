package com.fortuneandfavors.economy;

import com.fortuneandfavors.util.Chat;
import com.fortuneandfavors.util.InventoryHelper;
import com.fortuneandfavors.util.JsonUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.Map.Entry;
import java.util.function.Predicate;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Dynamic jobs: contracts that appear in response to what actually happens on
 * the server, not a static rotation. A boss that killed a player becomes a
 * "slay the boss" job; a player on a 10+ kill spree becomes a hunt; a player
 * with the Bad Omen effect triggers a raid-defense job; and objective jobs
 * (kill mobs, mine ores, earn cash, win duels) roll in on a timer. Deliveries
 * are just one kind of contract, and every contract now pays cash + favor
 * tokens.
 */
public final class DynamicContractsManager {
   public static final long CONTRACT_LIFETIME_MS = 24L * 60L * 60L * 1000L;
   private static final long SHORTAGE_INTERVAL_MS = 4L * 60L * 60L * 1000L;
   private static final long OBJECTIVE_INTERVAL_MS = 30L * 60L * 1000L;
   private static final long URGENT_WINDOW_MS = 10L * 60L * 1000L;
   private static final long KILL_WINDOW_MS = 12L * 60L * 60L * 1000L;
   private static final int KILLS_FOR_HUNT = 10;
   private static final long URGENT_REWARD = 50000L;
   private static final Random RANDOM = new Random();
   private static final List<Contract> contracts = new ArrayList<>();
   private static final Map<String, List<Long>> bossDeaths = new HashMap<>();
   private static final Map<UUID, List<Long>> playerKills = new HashMap<>();
   private static final Map<UUID, Long> omenContractSeen = new HashMap<>();
   private static int nextId = 1;
   private static Path dataFile;
   private static long nextShortageAt = 0L;
   private static long nextObjectiveAt = 0L;

   private DynamicContractsManager() {
   }

   public static void load(MinecraftServer server) {
      contracts.clear();
      dataFile = EconomyManager.getDataDir(server).resolve("contracts.json");
      JsonObject root = JsonUtil.readOrCreate(dataFile, new JsonObject());
      nextId = JsonUtil.jsonInt(root, "next_id", 1);
      nextShortageAt = JsonUtil.jsonLong(root, "next_shortage_at", 0L);
      nextObjectiveAt = JsonUtil.jsonLong(root, "next_objective_at", 0L);
      if (root.has("contracts") && root.get("contracts").isJsonArray()) {
         for (JsonElement e : root.getAsJsonArray("contracts")) {
            try {
               JsonObject obj = e.getAsJsonObject();
               String type = JsonUtil.jsonString(obj, "type", "shortage");
               String target = JsonUtil.jsonString(obj, "target", "");
               int amount = JsonUtil.jsonInt(obj, "amount", 1);
               long reward = JsonUtil.jsonLong(obj, "reward", 1000L);
               long created = JsonUtil.jsonLong(obj, "created", 0L);
               long expires = JsonUtil.jsonLong(obj, "expires", 0L);
               String title = JsonUtil.jsonString(obj, "title", "");
               String desc = JsonUtil.jsonString(obj, "desc", "");
               Contract c = new Contract(type, target, amount, reward, created, expires, title, desc);
               c.delivered = JsonUtil.jsonInt(obj, "delivered", 0);
               c.progress = JsonUtil.jsonInt(obj, "progress", 0);
               c.done = JsonUtil.jsonInt(obj, "done", 0) == 1;
               if (c.expires > System.currentTimeMillis()) {
                  contracts.add(c);
               }
            } catch (Exception ignored) {
            }
         }
      }
      // If there are no live contracts after a restart (timers saved far in the
      // future), force a fresh objective job so /jobs always has something.
      if (contracts.isEmpty()) {
         nextObjectiveAt = 0L;
      }
   }

   public static void save(MinecraftServer server) {
      if (dataFile == null) {
         dataFile = EconomyManager.getDataDir(server).resolve("contracts.json");
      }
      JsonObject root = new JsonObject();
      root.addProperty("next_id", nextId);
      root.addProperty("next_shortage_at", nextShortageAt);
      root.addProperty("next_objective_at", nextObjectiveAt);
      JsonArray arr = new JsonArray();
      for (Contract c : contracts) {
         JsonObject obj = new JsonObject();
         obj.addProperty("type", c.type);
         obj.addProperty("target", c.target);
         obj.addProperty("amount", c.amount);
         obj.addProperty("reward", c.reward);
         obj.addProperty("created", c.created);
         obj.addProperty("expires", c.expires);
         obj.addProperty("title", c.title);
         obj.addProperty("desc", c.desc);
         obj.addProperty("delivered", c.delivered);
         obj.addProperty("progress", c.progress);
         obj.addProperty("done", c.done ? 1 : 0);
         arr.add(obj);
      }
      root.add("contracts", arr);
      JsonUtil.write(dataFile, root);
   }

   public static void tick(MinecraftServer server) {
      long now = System.currentTimeMillis();
      boolean changed = false;
      for (Iterator<Contract> it = contracts.iterator(); it.hasNext(); ) {
         if (it.next().expires < now) {
            it.remove();
            changed = true;
         }
      }
      // A new shortage rolls in every 4h, and a new objective job every 30m.
      if (now >= nextShortageAt && activeCount() < MAX_ACTIVE) {
         createShortage(server);
         nextShortageAt = now + SHORTAGE_INTERVAL_MS;
         changed = true;
      }
      if (now >= nextObjectiveAt && activeCount() < MAX_ACTIVE) {
         createObjective(server);
         nextObjectiveAt = now + OBJECTIVE_INTERVAL_MS;
         changed = true;
      }
      changed = tickOmenJobs(server, now) || changed;
      changed = tickWitherJobs(server, now) || changed;
      // The three live situations. Each of these is a situation rather than an event, so it is read
      // off the world every pass instead of being reported once by a hook that may have already
      // been missed - which is the whole reason the board could not see any of them.
      changed = tickDownedJobs(server, now) || changed;
      changed = tickAbandonedBossJobs(server, now) || changed;
      changed = tickAbandonedRaidJobs(server, now) || changed;
      changed = tickRecoveryJobs(server, now) || changed;
      if (changed) {
         save(server);
      }
   }

   /** Watches for players carrying Bad Omen and raises a raid-defense job. */
   private static boolean tickOmenJobs(MinecraftServer server, long now) {
      boolean changed = false;
      for (ServerPlayer p : server.getPlayerList().getPlayers()) {
         boolean hasOmen = p.hasEffect(MobEffects.BAD_OMEN);
         Long seenAt = omenContractSeen.get(p.getUUID());
         if (hasOmen && seenAt == null && activeCount() < MAX_ACTIVE) {
            if (!hasActive("raid_help", p.getUUID().toString())) {
               String name = p.getName().getString();
               Contract c = new Contract(
                  "raid_help",
                  p.getUUID().toString(),
                  1,
                  60000L,
                  now,
                  now + 24L * 60L * 60L * 1000L,
                  "§dRaid Defense: " + name,
                  "§7" + name + " carries the Bad Omen! Help them defeat the raid for a bounty."
               );
               contracts.add(c);
               nextId++;                broadcast(server, "§d⚠ RAID JOB: §f" + name + "§r §7carries the Bad Omen - help them defeat the raid for " + Chat.moneyStr(60000L) + "§7 + §52 gems§7!");
               changed = true;
            }
         }
         if (hasOmen) {
            omenContractSeen.put(p.getUUID(), now);
         } else if (seenAt != null) {
            omenContractSeen.remove(p.getUUID());
         }
      }
      return changed;
   }

   /** Max number of contracts that can be live at once. */
   public static final int MAX_ACTIVE = 8;

   public static List<Contract> activeContracts() {
      long now = System.currentTimeMillis();
      List<Contract> out = new ArrayList<>();
      for (Contract c : contracts) {
         if (c.expires < now || c.done) {
            continue; // Expired OR already completed - completed jobs no longer count toward the cap
         }
         out.add(c);
         if (out.size() >= MAX_ACTIVE) {
            break;
         }
      }
      return out;
   }

   public static int activeCount() {
      return activeContracts().size();
   }

   private static boolean hasActive(String type, String target) {
      for (Contract c : contracts) {
         if (c.type.equals(type) && c.target.equals(target) && !c.done) {
            return true;
         }
      }
      return false;
   }

   // ---------- Event hooks ----------

   /** A boss killed a player - immediately post a "slay the boss" job. */
   public static void onPlayerKilledByBoss(MinecraftServer server, String bossKey, ServerPlayer victim) {
      if (victim == null || bossKey == null) {
         return;
      }
      long now = System.currentTimeMillis();
      List<Long> deaths = bossDeaths.computeIfAbsent(bossKey, k -> new ArrayList<>());
      deaths.add(now);
      deaths.removeIf(t -> now - t > URGENT_WINDOW_MS);
      if (activeCount() >= MAX_ACTIVE || hasActive("slay", bossKey)) {
         return;
      }
      String name = bossDisplayName(bossKey);
      Contract c = new Contract(
         "slay",
         bossKey,
         1,
         URGENT_REWARD,
         now,
         now + CONTRACT_LIFETIME_MS,
         "§cUrgent: Slay the " + name,
         "§7The " + name + " slew " + victim.getName().getString() + "! End it for a bounty."
      );
      contracts.add(c);
      nextId++;       broadcast(server, "§c⚠ DYNAMIC JOB: §fThe " + name + "§r §7killed " + victim.getName().getString() + " - slay it for " + Chat.moneyStr(URGENT_REWARD) + "§7 + §52 gems§7!");
      save(server);
   }

   public static void onBossKilled(MinecraftServer server, String bossKey, ServerPlayer killer) {
      if (killer == null || bossKey == null) {
         return;
      }
      long now = System.currentTimeMillis();
      List<Long> deaths = bossDeaths.get(bossKey);
      Contract urgent = findSlay(bossKey);
      if (urgent != null) {
         completeContract(server, urgent, killer, 2);
         if (deaths != null) {
            deaths.clear();
         }
      } else if (deaths != null && deaths.size() >= 3 && activeCount() < MAX_ACTIVE && !hasActive("slay", bossKey)) {
         String name = bossDisplayName(bossKey);
         Contract c = new Contract(
            "slay",
            bossKey,
            1,
            URGENT_REWARD,
            now,
            now + CONTRACT_LIFETIME_MS,
            "§cUrgent: Slay the " + name,
            "§7" + deaths.size() + " players fell to the " + name + " - end it for a bounty!"
         );
         contracts.add(c);
         nextId++;
         broadcast(server, "§c⚠ DYNAMIC JOB: §fSlay the " + name + "§r §7for " + Chat.moneyStr(URGENT_REWARD) + "§7! Deliver the kill within 24h!");
         save(server);
      }
   }

   /** A player killed another player - track the 12h kill window and raise a hunt job on sprees. */
   public static void onPlayerKilledByPlayer(MinecraftServer server, ServerPlayer killer, ServerPlayer victim) {
      if (killer == null || killer == victim) {
         return;
      }
      long now = System.currentTimeMillis();
      List<Long> kills = playerKills.computeIfAbsent(killer.getUUID(), k -> new ArrayList<>());
      kills.add(now);
      kills.removeIf(t -> now - t > KILL_WINDOW_MS);
      if (kills.size() >= KILLS_FOR_HUNT && activeCount() < MAX_ACTIVE && !hasActive("hunt", killer.getUUID().toString())) {
         String name = killer.getName().getString();
         Contract c = new Contract(
            "hunt",
            killer.getUUID().toString(),
            1,
            75000L,
            now,
            now + CONTRACT_LIFETIME_MS,
            "§cHunt: " + name,
            "§7" + name + " has " + kills.size() + " kills in 12h! Defeat them for a bounty."
         );
         contracts.add(c);
         nextId++;          broadcast(server, "§c⚠ DYNAMIC JOB: §f" + name + "§r §7is on a " + kills.size() + "-kill spree - defeat them for " + Chat.moneyStr(75000L) + "§7 + §52 gems§7!");
         save(server);
      }
   }

   /** The target of a hunt contract died - pay the killer. */
   public static void onHuntTargetDied(MinecraftServer server, ServerPlayer victim, ServerPlayer killer) {
      if (victim == null || killer == null) {
         return;
      }
      for (Contract c : contracts) {
         if ("hunt".equals(c.type) && c.target.equals(victim.getUUID().toString()) && !c.done) {
            completeContract(server, c, killer, 2);
            return;
         }
      }
   }

   /** A raid was won - fulfil any raid-defense jobs. */
   public static void onRaidVictory(MinecraftServer server, ServerPlayer warlordKiller) {
      if (server == null) {
         return;
      }
      List<Contract> done = new ArrayList<>();
      for (Contract c : contracts) {
         if ("raid_help".equals(c.type) && !c.done) {
            done.add(c);
         }
      }
      for (Contract c : done) {
         if (warlordKiller != null) {
            completeContract(server, c, warlordKiller, 2);
         } else {
            c.done = true;
            broadcast(server, "§d✓ RAID DEFENSE COMPLETE - the village was saved!");
         }
      }
   }

   // ---------- The summoned wither ----------
   //
   // A job that answers something that is actually happening on the server, rather than a timer.
   // The three rules the rest of the board reacts to - a boss that killed a player, a kill spree,
   // a player carrying the Bad Omen - all key off the eight scripted fights and the six rigged
   // ones, because those are the bodies BossManager can name. A *player-summoned vanilla wither*
   // is not one of them: nothing in this mod owns its identity, so the one fight on the server
   // that can be started by a player and then left running with nobody to finish it produced no
   // job at all. It does now: the party standing there when it tore out of the ground is recorded,
   // and the moment every one of them is down or logged out, the board posts the fight.

   /** How near a player must stand when a wither arrives to count as one of its summoners. */
   private static final double WITHER_SUMMON_RANGE = 32.0;

   /** What ending a party's own wither is worth: the largest bounty the board can carry. */
   private static final long WITHER_REWARD = 80000L;

   /** One summoned wither, and the players who were standing there when it arrived. */
   private static final class WitherWatch {
      final String dimension;
      final Set<UUID> summoners = new LinkedHashSet<>();
      /** How many players it has killed, so the job can say what it has already done. */
      int kills;
      boolean posted;

      WitherWatch(String dimension) {
         this.dimension = dimension;
      }
   }

   private static final Map<UUID, WitherWatch> withers = new HashMap<>();

   /**
    * A wither arrived in the world: remember who was standing there.
    *
    * <p>The party is read from the arrival rather than from who placed the skulls, because the mod
    * never sees the skulls go down - the rework adopts the body once the vanilla spawn sequence
    * starts ticking, which is afterwards. Everybody close enough to be part of the summon is
    * treated as part of it, which is the answer that helps the players who actually watched it
    * happen.
    */
   public static void onWitherSummoned(ServerLevel level, WitherBoss wither) {
      if (level == null || wither == null) {
         return;
      }
      WitherWatch w = withers.computeIfAbsent(wither.getUUID(), k -> new WitherWatch(level.dimension().identifier().toString()));
      for (ServerPlayer p : level.players()) {
         if (p.distanceToSqr(wither) <= WITHER_SUMMON_RANGE * WITHER_SUMMON_RANGE) {
            w.summoners.add(p.getUUID());
         }
      }
   }

   /**
    * The rule, as a function: is there nobody left standing from this party?
    *
    * <p>Split out from the tick that applies it so the answer is testable without a world: an
    * empty party is <b>not</b> "everyone fell" - a wither summoned alone in a corner nobody saw
    * has no party to avenge, and posting a job for it would put a bounty on a fight no player was
    * ever part of.
    */
   public static boolean partyIsDown(Set<UUID> summoners, Predicate<UUID> stillStanding) {
      if (summoners == null || summoners.isEmpty()) {
         return false;
      }
      for (UUID id : summoners) {
         if (stillStanding.test(id)) {
            return false;
         }
      }
      return true;
   }

   /** The wither died: pay whoever ended it, and stop watching. */
   public static void onWitherSlain(MinecraftServer server, ServerPlayer killer) {
      if (server == null) {
         return;
      }
      withers.clear();
      for (Contract c : contracts) {
         if ("slay".equals(c.type) && "wither".equals(c.target) && !c.done) {
            if (killer != null) {
               completeContract(server, c, killer, 3);
            } else {
               c.done = true;
               broadcast(server, "§a✓ The Wither is dead, and the board is clear.");
               save(server);
            }
         }
      }
   }

   /** The wither killed somebody: the job goes up on the first death, as a boss's does. */
   public static void onWitherKilledPlayer(MinecraftServer server, ServerPlayer victim) {
      if (server == null || victim == null || withers.isEmpty()) {
         return;
      }
      long now = System.currentTimeMillis();
      for (Entry<UUID, WitherWatch> e : withers.entrySet()) {
         WitherWatch w = e.getValue();
         w.kills++;
         if (w.posted || activeCount() >= MAX_ACTIVE || hasActive("slay", "wither")) {
            continue;
         }
         w.posted = true;
         postWitherJob(server, w, now, "§7The Wither has slain " + victim.getName().getString() + " - end it for a bounty!");
      }
   }

   /** The sweep: a party that is gone leaves the fight to whoever will take it. */
   private static boolean tickWitherJobs(MinecraftServer server, long now) {
      if (withers.isEmpty()) {
         return false;
      }
      boolean changed = false;
      for (Iterator<Entry<UUID, WitherWatch>> it = withers.entrySet().iterator(); it.hasNext(); ) {
         Entry<UUID, WitherWatch> e = it.next();
         WitherWatch w = e.getValue();
         WitherBoss body = findWither(server, w.dimension, e.getKey());
         if (body == null || !body.isAlive()) {
            // Gone - unloaded for good, killed while the rework was off, or removed by a command.
            it.remove();
            changed = true;
            continue;
         }
         // A bounty nobody took expires while the fight is still standing, so the board is allowed
         // to offer it again rather than treat the one posting as final.
         if (w.posted && !hasActive("slay", "wither")) {
            w.posted = false;
         }
         if (w.posted || activeCount() >= MAX_ACTIVE || hasActive("slay", "wither")) {
            continue;
         }
         boolean down = partyIsDown(w.summoners, id -> {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            return p != null && p.isAlive();
         });
         if (!down) {
            continue;
         }
         w.posted = true;
         postWitherJob(
            server,
            w,
            now,
            "§7The party that summoned the Wither are all down - finish it for a bounty!"
         );
         changed = true;
      }
      return changed;
   }

   private static void postWitherJob(MinecraftServer server, WitherWatch w, long now, String reason) {
      Contract c = new Contract(
         "slay",
         "wither",
         1,
         WITHER_REWARD,
         now,
         now + CONTRACT_LIFETIME_MS,
         "§8Urgent: Slay the Wither",
         reason
      );
      contracts.add(c);
      nextId++;
      broadcast(
         server,
         "§8⚠ DYNAMIC JOB: §fSlay the Wither§r " + reason + " §7Reward: " + Chat.moneyStr(WITHER_REWARD) + " + §53 gems§7!"
      );
      save(server);
   }

   /** Where the watched wither is now: its recorded dimension first, then anywhere else. */
   private static WitherBoss findWither(MinecraftServer server, String dimension, UUID id) {
      try {
         ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(dimension)));
         if (level != null && level.getEntity(id) instanceof WitherBoss w) {
            return w;
         }
      } catch (Exception ignored) {
      }
      for (ServerLevel level : server.getAllLevels()) {
         if (level.getEntity(id) instanceof WitherBoss w) {
            return w;
         }
      }
      return null;
   }

   /** Test hook: how many withers the board is currently watching. */
   public static int watchedWithers() {
      return withers.size();
   }

   /** Test hook: does a live job already answer a summoned wither? */
   public static boolean hasWitherJob() {
      return hasActive("slay", "wither");
   }

   // ---------- Live situations ----------
   //
   // Everything above answers something that *happened*: a kill, a spree, a boss that drew blood.
   // Three situations this server can be in are not events at all and had no shape on the board -
   // a teammate who has just gone down while the rest of the party is still standing, a boss bar
   // still on screen with nobody anywhere near the body, and a death drop nobody has come back
   // for. Each is checked against the world rather than against a clock, and each pays its own
   // bounty, because a rescue, a mop-up and a recovery are not the same work.

   /** How long a downed teammate's call for backup stays on the board. */
   private static final long DOWNED_WINDOW_MS = 4L * 60L * 1000L;
   /** The fee for covering a teammate's fall, plus the bounty per body of the thing that dropped them. */
   private static final long DOWNED_REWARD = 35_000L;
   /** How near the body a player must be to count as fighting it. */
   public static final double BOSS_MANNED_RANGE = 48.0;
   /** How long a boss bar may sit with nobody near it before the board calls it abandoned. */
   private static final long BOSS_ABANDONED_MS = 45_000L;
   /** A grave whose owner has been gone this long is anyone's to bring home. */
   public static final long GRAVE_ABANDONED_MS = 10L * 60L * 1000L;
   /** What bringing somebody else's gear home pays. */
   private static final long RECOVER_REWARD = 45_000L;

   /** A teammate's fall, and the body that dropped them. */
   private record Downed(String name, String mobId, long at) {
   }

   private static final Map<UUID, Downed> downed = new HashMap<>();
   private static final Map<String, Long> unattendedSince = new HashMap<>();

   /**
    * A player went down. If a mob did it, the board can hand the fight back to whoever is still
    * standing - which is the one thing a party that has just lost a member needs told, and the
    * one thing the board never said.
    *
    * <p>A player kill is deliberately not this: the hunt board already owns that, and a backup
    * job aimed at a player would be a second bounty on somebody for one kill.
    */
   public static void onPlayerDowned(ServerPlayer victim, Entity killer) {
      if (victim == null) {
         return;
      }
      // A player is a LivingEntity too, so the old condition let a PvP death through and filed it
      // as if a mob had done it - and the board then posted "Backup: <name> is down - clear 1x
      // Player", a bounty on a body it can never hand out against, from an event the hunt board
      // already owns. The killer is tested for player-ness first, so the one case the doc above
      // calls out is the one case that cannot reach the board.
      String mobId = null;
      if (killer != null && killer != victim
         && !(killer instanceof ServerPlayer)
         && killer instanceof net.minecraft.world.entity.LivingEntity) {
         mobId = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(killer.getType()).toString();
      }
      downed.put(victim.getUUID(), new Downed(victim.getName().getString(), mobId, System.currentTimeMillis()));
   }

   /** A teammate fell with others still up: name the body that did it and pay for the mop-up. */
   private static boolean tickDownedJobs(MinecraftServer server, long now) {
      if (downed.isEmpty()) {
         return false;
      }
      boolean changed = false;
      int online = server.getPlayerList().getPlayerCount();
      for (Iterator<Entry<UUID, Downed>> it = downed.entrySet().iterator(); it.hasNext(); ) {
         Entry<UUID, Downed> e = it.next();
         Downed d = e.getValue();
         if (now - d.at() > DOWNED_WINDOW_MS) {
            it.remove();
            changed = true;
            continue;
         }
         // Somebody has to be left to answer it. A call for backup on an empty server is noise.
         if (d.mobId() == null || online < 2) {
            continue;
         }
         // One call per fall, and never two bounties on the same body at once.
         if (hasActive("kill_mob", d.mobId()) || activeCount() >= MAX_ACTIVE) {
            continue;
         }
         int goal = 1 + Math.min(2, online / 4);
         long reward = DOWNED_REWARD + (goal - 1) * 8_000L;
         Contract c = new Contract(
            "kill_mob",
            d.mobId(),
            goal,
            reward,
            now,
            now + CONTRACT_LIFETIME_MS,
            "§cBackup: " + d.name() + " is down",
            "§7A " + mobName(d.mobId()) + " dropped " + d.name() + " - clear " + goal + "x " + mobName(d.mobId()) + " and cover the recovery."
         );
         contracts.add(c);
         nextId++;
         broadcast(
            server,
            "§c⚠ DYNAMIC JOB: §f" + d.name() + " went down to a " + mobName(d.mobId()) + "§r §7- clear it for " + Chat.moneyStr(reward) + "§7!"
         );
         it.remove();
         changed = true;
      }
      return changed;
   }

   /**
    * A boss bar with nobody near the body: the fight was started and then left. The bar is the
    * loudest thing on the screen and the quietest thing on the board, so a lobby that walks away
    * from a boss currently gets a bar that never leaves and no way for anyone else to take it.
    */
   private static boolean tickAbandonedBossJobs(MinecraftServer server, long now) {
      List<String> abandoned = BossManager.abandonedBossKeys(server, BOSS_MANNED_RANGE);
      boolean changed = false;
      if (unattendedSince.keySet().retainAll(abandoned)) {
         changed = true;
      }
      for (String key : abandoned) {
         Long since = unattendedSince.get(key);
         if (since == null) {
            unattendedSince.put(key, now);
            changed = true;
            continue;
         }
         if (now - since < BOSS_ABANDONED_MS || hasActive("slay", key) || activeCount() >= MAX_ACTIVE) {
            continue;
         }
         String name = bossDisplayName(key);
         Contract c = new Contract(
            "slay",
            key,
            1,
            URGENT_REWARD,
            now,
            now + CONTRACT_LIFETIME_MS,
            "§cAbandoned: Slay the " + name,
            "§7The " + name + " has been standing alone with nobody near it - finish it for a bounty."
         );
         contracts.add(c);
         nextId++;
         broadcast(
            server,
            "§c⚠ DYNAMIC JOB: §fThe " + name + "§r §7is standing abandoned - slay it for " + Chat.moneyStr(URGENT_REWARD) + "§7 + §52 gems§7!"
         );
         changed = true;
      }
      return changed;
   }

   /**
    * A raid whose Warlord is standing with nobody fighting it.
    *
    * <p>{@link #tickAbandonedBossJobs} answers this for the fights BossManager owns, and a raid's
    * Warlord belongs to {@link PlayerRaidManager} - so a party that started a raid, died to it and
    * logged off left a bar over a village and nothing on the board. Like the boss rule, this is a
    * situation read off the world each pass, and unlike the wither rule it retires the moment it
    * stops being true: a fight somebody has picked up is not an abandoned one.
    */
   private static boolean tickAbandonedRaidJobs(MinecraftServer server, long now) {
      if (server == null) {
         return false;
      }
      List<String> abandoned = PlayerRaidManager.abandonedRaidWarlords(server, PlayerRaidManager.RAID_MANNED_RANGE);
      Set<String> live = new LinkedHashSet<>(abandoned);
      boolean changed = false;
      for (String key : abandoned) {
         if (hasActive("raid_slay", key) || activeCount() >= MAX_ACTIVE) {
            continue;
         }
         Contract c = new Contract(
            "raid_slay",
            key,
            1,
            URGENT_REWARD,
            now,
            now + CONTRACT_LIFETIME_MS,
            "§cAbandoned: Raid Warlord",
            "§7A raid is standing with nobody fighting it at "
               + PlayerRaidManager.raidWarlordWhere(server, key)
               + " - end the Warlord for a bounty."
         );
         contracts.add(c);
         nextId++;
         broadcast(
            server,
            "§c⚠ DYNAMIC JOB: §fAn abandoned Raid Warlord§r §7is still standing - slay it for "
               + Chat.moneyStr(URGENT_REWARD) + "§7 + §52 gems§7!"
         );
         changed = true;
      }
      for (Iterator<Contract> it = contracts.iterator(); it.hasNext(); ) {
         Contract c = it.next();
         if ("raid_slay".equals(c.type) && !c.done && !live.contains(c.target)) {
            c.done = true;
            changed = true;
         }
      }
      return changed;
   }

   /**
    * A raid's Warlord fell. The killer is paid if the fight was on the board - the job is about
    * the fight, and the fight is over either way.
    */
   public static void onRaidWarlordSlain(MinecraftServer server, String raidKey, ServerPlayer killer) {
      if (server == null || raidKey == null) {
         return;
      }
      for (Contract c : contracts) {
         if ("raid_slay".equals(c.type) && c.target.equals(raidKey) && !c.done) {
            if (killer != null) {
               completeContract(server, c, killer, 2);
            } else {
               c.done = true;
               save(server);
            }
            return;
         }
      }
   }

   /** Test hook: is the board carrying a job for this raid's Warlord? */
   public static boolean hasRaidJob(String raidKey) {
      return hasActive("raid_slay", raidKey);
   }

   /**
    * A death drop nobody has come back for. The grave is the owner's to claim, and stays theirs
    * while they are around; once they have been gone long enough it becomes the one job on the
    * board nobody can complete alone - and the gear still belongs to them either way.
    */
   private static boolean tickRecoveryJobs(MinecraftServer server, long now) {
      Set<String> live = new LinkedHashSet<>();
      for (com.fortuneandfavors.NiceKeepInventoryManager.GraveRecord g
            : com.fortuneandfavors.NiceKeepInventoryManager.unclaimedGraves(server)) {
         if (g.ownerName() == null || server.getPlayerList().getPlayer(g.ownerUUID()) != null) {
            continue; // The owner is online, so it is theirs to walk back to.
         }
         if (g.ageTicks() * 50L < GRAVE_ABANDONED_MS) {
            continue;
         }
         live.add(g.key());
         if (hasActive("recover", g.key()) || activeCount() >= MAX_ACTIVE) {
            continue;
         }
         Contract c = new Contract(
            "recover",
            g.key(),
            1,
            RECOVER_REWARD,
            now,
            now + CONTRACT_LIFETIME_MS,
            "§eRecovery: " + g.ownerName() + "'s grave",
            "§7" + g.ownerName() + " has been gone a while - bring their gear home from "
               + g.pos().getX() + ", " + g.pos().getY() + ", " + g.pos().getZ() + " in " + prettyDimension(g.dimension()) + "."
         );
         contracts.add(c);
         nextId++;
         broadcast(
            server,
            "§e⚠ DYNAMIC JOB: §fRecover " + g.ownerName() + "'s grave§r §7- their gear is still out there, and it is worth "
               + Chat.moneyStr(RECOVER_REWARD) + "§7 to bring it home."
         );
      }
      // A job for a grave that has been claimed, or whose owner has come back, is stale the moment
      // it is untrue - so it retires itself rather than advertising a drop that is no longer there.
      boolean changed = !live.isEmpty();
      for (Iterator<Contract> it = contracts.iterator(); it.hasNext(); ) {
         Contract c = it.next();
         if ("recover".equals(c.type) && !c.done && !live.contains(c.target)) {
            c.done = true;
            changed = true;
         }
      }
      return changed;
   }

   /**
    * Somebody claimed an abandoned grave. The gear goes to the player it belongs to - mailed if
    * they are offline, handed over if they are not - and the finder is paid for the trip.
    */
   public static void onGraveRecovered(MinecraftServer server, ServerPlayer finder, String graveKey) {
      if (server == null || graveKey == null) {
         return;
      }
      for (Contract c : contracts) {
         if ("recover".equals(c.type) && c.target.equals(graveKey) && !c.done) {
            completeContract(server, c, finder, 1);
            return;
         }
      }
   }

   /** A dimension id as a player would say it. */
   private static String prettyDimension(String id) {
      if (id == null) {
         return "the world";
      }
      return switch (id) {
         case "minecraft:overworld" -> "the Overworld";
         case "minecraft:the_nether" -> "the Nether";
         case "minecraft:the_end" -> "the End";
         default -> id.substring(id.indexOf(':') + 1).replace('_', ' ');
      };
   }

   /** Test hook: is the board carrying a backup call for this mob? */
   public static boolean hasDownedJob(String mobId) {
      return hasActive("kill_mob", mobId);
   }

   /** Test hook: is the board carrying a recovery for this grave? */
   public static boolean hasRecoveryJob(String graveKey) {
      return hasActive("recover", graveKey);
   }

   /** Test hook: how many bosses the board is currently treating as abandoned. */
   public static int abandonedBosses() {
      return unattendedSince.size();
   }

   /** Test hook: post a live-situation job through the real path, so a check can drive it. */
   public static void postLiveForTest(Contract c) {
      if (c != null && !contracts.contains(c)) {
         contracts.add(c);
      }
   }

   /** Test hook: drop a posted job without waiting for it to expire. */
   public static void dropForTest(Contract c) {
      contracts.remove(c);
   }

   /** Generic progress for objective jobs (kill mobs, mine ores, earn, duel). */
   public static void onProgress(ServerPlayer player, String type, int amount) {
      if (player == null || amount <= 0) {
         return;
      }
      MinecraftServer server = player.level().getServer();
      for (Contract c : contracts) {
         if (c.type.equals(type) && !c.done) {
            c.progress += amount;
            if (c.progress >= c.amount) {
               completeContract(server, c, player, c.type.equals("sell_earn") ? 1 : 0);
            }
         }
      }
   }

   /**
    * A block was broken: the category job counts it if it is an ore, and a dig order counts it if
    * it is *its* block.
    *
    * <p>The named target is matched on the block's registry id, including the deepslate variant of
    * an ore, because "mine 8x Diamond Ore" that refused to count the deepslate kind would be a job
    * a player could not finish by doing exactly what it asked.
    */
   public static void onBlockBroken(ServerPlayer player, BlockState state) {
      if (state.getBlock() == Blocks.AIR) {
         return;
      }
      String full = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
      String path = full.substring(full.indexOf(':') + 1);
      if (path.contains("ore") || path.contains("debris")) {
         onProgress(player, "mine_ores", 1);
      }
      for (Contract c : contracts) {
         if (!c.done && "mine_block".equals(c.type) && targetBlockMatches(c.target, full)) {
            credit(player, c, 1);
         }
      }
   }

   /** Whether a broken block is the one a dig order named - itself or its deepslate form. */
   private static boolean targetBlockMatches(String target, String brokenId) {
      if (target.equals(brokenId)) {
         return true;
      }
      if (target.startsWith("minecraft:deepslate_")) {
         return false;
      }
      int colon = target.indexOf(':');
      if (colon < 0) {
         return false;
      }
      String path = target.substring(colon + 1);
      return path.endsWith("_ore") && brokenId.equals(target.substring(0, colon + 1) + "deepslate_" + path);
   }

   public static void onMobKilled(ServerPlayer player) {
      onProgress(player, "kill_mobs", 1);
   }

   /**
    * A mob died to this player: the category job counts every hostile kill, and a bounty counts
    * the mob it named.
    */
   public static void onMobKilled(ServerPlayer player, Entity killed) {
      onProgress(player, "kill_mobs", 1);
      if (player == null || killed == null) {
         return;
      }
      String id = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(killed.getType()).toString();
      for (Contract c : contracts) {
         if (!c.done && "kill_mob".equals(c.type) && c.target.equals(id)) {
            credit(player, c, 1);
         }
      }
   }

   /**
    * A stack was actually sold. The standing-order job is about the goods, not the money, so it
    * needs the item - which is the one thing {@link #onSell} cannot tell it.
    */
   public static void onSellItem(ServerPlayer player, ItemStack stack) {
      if (player == null || stack == null || stack.isEmpty()) {
         return;
      }
      String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
      for (Contract c : contracts) {
         if (!c.done && "sell_item".equals(c.type) && c.target.equals(id)) {
            credit(player, c, stack.getCount());
         }
      }
   }

   /** One contract's own progress, with the completion check every hook has to make. */
   private static void credit(ServerPlayer player, Contract c, int amount) {
      c.progress += amount;
      if (c.progress >= c.amount) {
         completeContract(player.level().getServer(), c, player, 0);
      } else {
         save(player.level().getServer());
      }
   }

   public static void onSell(ServerPlayer player, long amount) {
      onProgress(player, "sell_earn", (int)amount);
   }

   public static void onDuelWon(ServerPlayer player) {
      onProgress(player, "duel_win", 1);
   }

   // ---------- Contract creation ----------

   private static void createShortage(MinecraftServer server) {
      if (activeCount() >= MAX_ACTIVE) {
         return;
      }
      String[] pool = new String[]{"iron_ingot", "diamond", "emerald", "gold_ingot", "netherite_scrap", "redstone", "lapis", "quartz", "slime_ball", "bone", "string", "gunpowder", "ender_pearl", "blaze_rod", "ghast_tear"};
      String target = pool[RANDOM.nextInt(pool.length)];
      int amount = 8 + RANDOM.nextInt(24);
      long unit = target.equals("diamond") || target.equals("netherite_scrap") ? 350L : target.equals("ender_pearl") || target.equals("blaze_rod") ? 200L : 120L;
      long reward = amount * unit;
      long now = System.currentTimeMillis();
      Contract c = new Contract(
         "shortage",
         target,
         amount,
         reward,
         now,
         now + CONTRACT_LIFETIME_MS,
         "Shortage: " + displayItemName(target),
         "§7The market needs " + amount + "x " + displayItemName(target) + " - deliver them at /deliver for cash!"
      );
      contracts.add(c);
      nextId++;
      broadcast(server, "§e📦 NEW CONTRACT: §f" + strip(c.title) + "§r §7- deliver " + amount + "x " + displayItemName(target) + " for " + Chat.moneyStr(reward) + "§7!");
      save(server);
   }

   /**
    * Every shape a dynamic job can take.
    *
    * <p>Four of these were the whole catalogue, with one fixed title and one fixed reward each -
    * "Dynamic jobs" was four canned errands drawn from a hat, and a player who played an evening
    * had seen all four twice. The three targeted shapes are the ones that make the board move:
    * the goal names a real block, mob or good, so the job is about a specific thing in the world
    * this week rather than a category of thing forever.
    */
   private static final String[] OBJECTIVE_KINDS = {
      "mine_ores", "mine_block", "kill_mobs", "kill_mob", "sell_earn", "sell_item", "duel_win"
   };

   /** One target, with the goal range a job of that shape may ask for. */
   private record Target(String id, int min, int max) {
   }

   /** What a dig order may name, and how much of it is a fair evening's work. */
   private static final List<Target> ORE_TARGETS = List.of(
      new Target("minecraft:coal_ore", 48, 96),
      new Target("minecraft:copper_ore", 40, 80),
      new Target("minecraft:iron_ore", 32, 64),
      new Target("minecraft:gold_ore", 24, 48),
      new Target("minecraft:redstone_ore", 32, 64),
      new Target("minecraft:lapis_ore", 24, 48),
      new Target("minecraft:nether_quartz_ore", 32, 64),
      new Target("minecraft:diamond_ore", 4, 12),
      new Target("minecraft:emerald_ore", 4, 12),
      new Target("minecraft:ancient_debris", 2, 6),
      new Target("minecraft:deepslate", 96, 192),
      new Target("minecraft:stone", 128, 256),
      new Target("minecraft:cobblestone", 128, 256),
      new Target("minecraft:obsidian", 12, 32),
      new Target("minecraft:glowstone", 24, 48),
      new Target("minecraft:amethyst_block", 16, 32),
      new Target("minecraft:end_stone", 64, 128),
      new Target("minecraft:netherrack", 128, 256),
      new Target("minecraft:tuff", 96, 192),
      new Target("minecraft:calcite", 48, 96),
      new Target("minecraft:basalt", 96, 192),
      new Target("minecraft:blackstone", 96, 192),
      new Target("minecraft:soul_sand", 64, 128),
      new Target("minecraft:gravel", 64, 128),
      new Target("minecraft:prismarine", 64, 128),
      new Target("minecraft:dripstone_block", 64, 128),
      new Target("minecraft:sandstone", 96, 192)
   );

   /** What a bounty may name. The rare names ask for one or two because that is already the hunt. */
   private static final List<Target> MOB_TARGETS = List.of(
      new Target("minecraft:zombie", 20, 40),
      new Target("minecraft:skeleton", 15, 30),
      new Target("minecraft:spider", 20, 40),
      new Target("minecraft:creeper", 12, 24),
      new Target("minecraft:drowned", 15, 30),
      new Target("minecraft:husk", 15, 30),
      new Target("minecraft:stray", 12, 24),
      new Target("minecraft:slime", 20, 40),
      new Target("minecraft:magma_cube", 12, 24),
      new Target("minecraft:witch", 6, 12),
      new Target("minecraft:pillager", 12, 24),
      new Target("minecraft:vindicator", 6, 12),
      new Target("minecraft:enderman", 8, 16),
      new Target("minecraft:blaze", 10, 20),
      new Target("minecraft:piglin", 12, 24),
      new Target("minecraft:ghast", 4, 8),
      new Target("minecraft:guardian", 8, 16),
      new Target("minecraft:shulker", 5, 10),
      new Target("minecraft:phantom", 6, 12),
      new Target("minecraft:ravager", 2, 4),
      new Target("minecraft:evoker", 3, 6),
      new Target("minecraft:elder_guardian", 1, 2)
   );

   /** Goods a standing order may name: the same market the delivery board buys from, sold by name. */
   private static final List<Target> SELL_TARGETS = List.of(
      new Target("minecraft:cobblestone", 256, 512),
      new Target("minecraft:oak_log", 128, 256),
      new Target("minecraft:coal", 128, 256),
      new Target("minecraft:wheat", 192, 384),
      new Target("minecraft:iron_ingot", 96, 192),
      new Target("minecraft:gold_ingot", 64, 128),
      new Target("minecraft:redstone", 128, 256),
      new Target("minecraft:lapis_lazuli", 64, 128),
      new Target("minecraft:gunpowder", 64, 128),
      new Target("minecraft:bone", 96, 192),
      new Target("minecraft:string", 96, 192),
      new Target("minecraft:ender_pearl", 24, 48),
      new Target("minecraft:blaze_rod", 24, 48)
   );

   /**
    * The bag the shapes are drawn from, so every one of them appears before any appears twice.
    *
    * <p>A roll per job is what made the old catalogue feel canned: four shapes rolled
    * independently means a board can ask for ores three times running, and a player reads that
    * as "there are three jobs" rather than as luck. The bag is refilled with all of them, in a
    * fresh order, every time it empties.
    */
   private static final java.util.Deque<String> KIND_BAG = new java.util.ArrayDeque<>();
   private static String lastKind = "";

   private static String drawKind() {
      if (KIND_BAG.isEmpty()) {
         List<String> refill = new ArrayList<>(List.of(OBJECTIVE_KINDS));
         java.util.Collections.shuffle(refill, RANDOM);
         KIND_BAG.addAll(refill);
      }
      String kind = KIND_BAG.poll();
      if (kind.equals(lastKind) && !KIND_BAG.isEmpty()) {
         // Never the same shape twice running - a board that repeats itself is the static
         // rotation this replaces, with a shorter list of jobs.
         String next = KIND_BAG.poll();
         KIND_BAG.addLast(kind);
         kind = next;
      }
      lastKind = kind;
      return kind;
   }

   /**
    * One dynamic job, rolled whole: a shape, a target, a goal and a reward that follows from them.
    *
    * <p>Nothing here is a canned pair. The goal is drawn from the target's own range, raised by a
    * little for every extra player online - the same errand is five times the work for one player
    * alone and for a full lobby, and neither number is the right one for both - and the reward is
    * computed from what the goal is actually worth rather than written next to a title.
    */
   private static Contract rollObjective(MinecraftServer server) {
      long now = System.currentTimeMillis();
      double pressure = 1.0 + Math.min(1.0, Math.max(0, server.getPlayerList().getPlayerCount() - 1) * 0.12);
      String kind = drawKind();
      String target = "any";
      String title;
      String desc;
      int amount;
      long reward;
      switch (kind) {
         case "mine_block" -> {
            Target t = pickAvailable(ORE_TARGETS);
            target = t.id();
            amount = goal(t, pressure);
            reward = jobReward(target, amount, 25L);
            title = "§bDig Order: " + itemName(target);
            desc = "§7Mine " + amount + "x " + itemName(target) + " anywhere on the server.";
         }
         case "kill_mob" -> {
            Target t = pickAvailable(MOB_TARGETS);
            target = t.id();
            amount = goal(t, pressure);
            reward = jobReward(null, amount, mobWage(target));
            title = "§cBounty: " + mobName(target);
            desc = "§7Slay " + amount + "x " + mobName(target) + " anywhere on the server.";
         }
         case "sell_item" -> {
            Target t = pickAvailable(SELL_TARGETS);
            target = t.id();
            amount = goal(t, pressure);
            reward = jobReward(target, amount, 25L);
            title = "§aStanding Order: " + itemName(target);
            desc = "§7Sell " + amount + "x " + itemName(target) + " - anywhere the market pays for it.";
         }
         case "sell_earn" -> {
            amount = round500(12000 + RANDOM.nextInt(20000), pressure);
            reward = clamp(6000L + amount / 3L, 8000L, 40000L);
            title = "§aProfiteer";
            desc = "§7Earn §a$" + String.format("%,d", amount) + "§7 from selling.";
         }
         case "duel_win" -> {
            amount = 2 + RANDOM.nextInt(4);
            reward = Math.max(6000L, Math.min(30000L, 4000L + amount * 4000L));
            title = "§bArena Star";
            desc = "§7Win " + amount + " duel" + (amount == 1 ? "" : "s") + ".";
         }
         case "kill_mobs" -> {
            amount = scaled(15, 40, pressure);
            reward = jobPay(amount, 200L);
            title = "§cHunter's Bounty";
            desc = "§7Slay " + amount + " hostile mobs.";
         }
         case "mine_ores" -> {
            amount = scaled(40, 80, pressure);
            reward = jobPay(amount, 120L);
            title = "§bMiner's Rush";
            desc = "§7Mine " + amount + " ores anywhere on the server.";
         }
         default -> throw new IllegalStateException("Unknown dynamic job shape " + kind);
      }
      return new Contract(kind, target, amount, reward, now, now + CONTRACT_LIFETIME_MS, title, desc);
   }

   private static void createObjective(MinecraftServer server) {
      if (activeCount() >= MAX_ACTIVE) {
         return;
      }
      Contract c = rollObjective(server);
      if (c == null) {
         return;
      }
      contracts.add(c);
      nextId++;
      broadcast(
         server,
         "§b📋 DYNAMIC JOB: §f" + strip(c.title) + "§r §7- " + strip(c.desc) + " Reward: " + Chat.moneyStr(c.reward)
            + ("sell_earn".equals(c.type) ? "§7 + §51 gem" : "") + "§7!"
      );
      save(server);
   }

   /** A goal inside the target's range, raised a little for every extra player online. */
   private static int goal(Target t, double pressure) {
      int rolled = t.min() + RANDOM.nextInt(t.max() - t.min() + 1);
      return Math.max(t.min(), (int)Math.round(rolled * pressure));
   }

   private static int scaled(int min, int max, double pressure) {
      return (int)Math.round((min + RANDOM.nextInt(max - min + 1)) * pressure);
   }

   /** A cash goal rounded to something a player can read at a glance. */
   private static int round500(int value, double pressure) {
      return (int)(Math.round(value * pressure / 500.0) * 500L);
   }

   /**
    * What a job pays for a goal: a fee plus a wage per unit of the work.
    *
    * <p>The wage for a named target is the higher of the shape's floor and the target's own
    * market worth at a premium - so a job that asks for diamonds pays like a job that asks for
    * diamonds, from {@code BlockValues}, rather than a number written next to a title. Floored so
    * the cheapest errand is still worth running, capped so the rarest one cannot out-pay a boss.
    */
   private static long jobReward(String itemId, int amount, long perUnit) {
      long wage = perUnit;
      if (itemId != null) {
         Item item = net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(net.minecraft.resources.Identifier.tryParse(itemId));
         if (item != null && item != Items.AIR) {
            wage = Math.max(perUnit, BlockValues.valueOf(item) * 4L);
         }
      }
      return jobPay(amount, wage);
   }

   /** The fee-plus-wage arithmetic, for the shapes that name no target at all. */
   private static long jobPay(int amount, long wage) {
      return clamp(6000L + wage * amount, 8000L, 60000L);
   }

   /** What one kill of this mob is worth to a bounty. Boss-tier names pay for being a fight. */
   private static long mobWage(String entityId) {
      return switch (entityId) {
         case "minecraft:elder_guardian" -> 2600L;
         case "minecraft:ravager" -> 900L;
         case "minecraft:evoker", "minecraft:ghast" -> 500L;
         case "minecraft:witch", "minecraft:vindicator", "minecraft:blaze" -> 220L;
         case "minecraft:enderman", "minecraft:shulker", "minecraft:guardian" -> 140L;
         default -> 45L;
      };
   }

   private static long clamp(long value, long floor, long ceiling) {
      return Math.max(floor, Math.min(ceiling, value));
   }

   /** A target that is not already on the board if one can be found, so no two jobs are the same. */
   private static Target pickAvailable(List<Target> pool) {
      for (int attempt = 0; attempt < 8; attempt++) {
         Target t = pool.get(RANDOM.nextInt(pool.size()));
         if (!hasActiveTarget(t.id())) {
            return t;
         }
      }
      return pool.get(RANDOM.nextInt(pool.size()));
   }

   private static boolean hasActiveTarget(String target) {
      long now = System.currentTimeMillis();
      for (Contract c : contracts) {
         if (c.target.equals(target) && !c.done && c.expires > now) {
            return true;
         }
      }
      return false;
   }

   /** A block or item's display name, for a job's title. */
   private static String itemName(String id) {
      Item item = net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(net.minecraft.resources.Identifier.tryParse(id));
      if (item == null || item == Items.AIR) {
         return id;
      }
      String name = new ItemStack(item).getHoverName().getString();
      return name.endsWith(" Ore") ? name.substring(0, name.length() - 4) : name;
   }

   /** An entity type's display name, singular or plural as the name already reads. */
   private static String mobName(String id) {
      net.minecraft.world.entity.EntityType<?> type =
         net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getValue(net.minecraft.resources.Identifier.tryParse(id));
      if (type == null) {
         return id;
      }
      String name = type.getDescription().getString();
      return !name.endsWith("s") && !name.endsWith("x") && !name.endsWith("h") ? name + "s" : name;
   }

   /** Test hooks: the self-test rolls the real catalogue, posts a job and clears up after itself. */
   public static Contract rollObjectiveForTest(MinecraftServer server) {
      return rollObjective(server);
   }

   public static void postObjectiveForTest(Contract c) {
      if (c != null && !contracts.contains(c)) {
         contracts.add(c);
      }
   }

   public static void removeObjectiveForTest(MinecraftServer server, Contract c) {
      contracts.remove(c);
      save(server);
   }

   // ---------- Delivery / completion ----------

   /**
    * True when some live shortage is asking for this stack - the same rule {@link #deliver} uses,
    * asked as a question rather than as a hand-in. The Deliveries window's sweep reads it to fill
    * the intake from a player's pack, so what the sweep collects and what the delivery accepts can
    * never drift apart.
    */
   public static boolean wantedByActiveContract(ItemStack stack) {
      if (stack == null || stack.isEmpty()) {
         return false;
      }
      Item item = stack.getItem();
      for (Contract c : contracts) {
         if (!"shortage".equals(c.type) || c.done || c.delivered >= c.amount) {
            continue;
         }
         if (c.target.equals("minecraft:any") || isItemTarget(item, c.target)) {
            return true;
         }
      }
      return false;
   }

   public static int deliver(ServerPlayer player, ItemStack stack) {
      if (stack == null || stack.isEmpty()) {
         return 0;
      }
      Item item = stack.getItem();
      int delivered = 0;
      for (Contract c : contracts) {
         if (!"shortage".equals(c.type) || c.done) {
            continue;
         }
         String target = c.target;
         boolean matches = target.equals("minecraft:any") || isItemTarget(item, target);
         if (matches) {
            int take = Math.min(stack.getCount(), c.amount - c.delivered);
            if (take > 0) {
               stack.shrink(take);
               c.delivered += take;
               delivered += take;
               if (c.delivered >= c.amount) {
                  completeContract(player.level().getServer(), c, player, 1);
               }
            }
         }
      }
      if (delivered > 0) {
         save(player.level().getServer());
      }
      return delivered;
   }

   private static Contract findSlay(String bossKey) {
      for (Contract c : contracts) {
         if ("slay".equals(c.type) && c.target.equals(bossKey) && !c.done) {
            return c;
         }
      }
      return null;
   }

   /**
    * A finished contract pushes the job board, the way a finished job pushes this one.
    *
    * <p>The two boards were a loop that did not close: a contract is a day's work and a job is an
    * errand, and the player who had done one had no reason at all to look at the other. A contract
    * now advances any live job of the same shape by a fifth of that job's size, so bringing a
    * shortage home also moves the board along - which is the whole point of a server with two
    * boards rather than two servers.
    */
   private static void feedJobs(ServerPlayer player, Contract c, int tokens) {
      try {
         String task = switch (c.type) {
            case "mine_ores", "mine_block" -> "MINE";
            case "kill_mobs", "kill_mob", "slay" -> "KILL";
            case "sell_earn", "sell_item" -> "TRADE";
            default -> null;
         };
         if (task == null) {
            return;
         }
         for (JobManager.Job job : JobManager.jobs(player.getUUID())) {
            if (job.claimed || !task.equals(job.task)) {
               continue;
            }
            job.progress = Math.min(job.goal, job.progress + Math.max(1, job.goal / 5));
            Chat.msg(
               player,
               "&7Contract delivered - your &f" + task.toLowerCase(java.util.Locale.ROOT)
                  + " job&7 is at &f" + job.progress + "&7/&f" + job.goal + "&7."
            );
            return;
         }
      } catch (Throwable ignored) {
      }
   }

   private static void completeContract(MinecraftServer server, Contract c, ServerPlayer player, int tokens) {
      if (c.done || player == null) {
         return;
      }
      c.done = true;
      EconomyManager.addCash(player.getUUID(), c.reward);
      feedJobs(player, c, tokens);
      if (tokens > 0) {          TokenManager.giveGems(player, tokens);
      }
      broadcast(
         server,
         "§a✓ CONTRACT COMPLETE: §f" + strip(c.title) + "§r §7by " + player.getName().getString() + " - earned " + Chat.moneyStr(c.reward)
            + (tokens > 0 ? "§7 + §5" + tokens + " gem" + (tokens == 1 ? "" : "s") : "")
            + "§7!"
      );
      save(server);
   }

   private static boolean isItemTarget(Item item, String target) {
      String id = target;
      if (target.startsWith("#")) {
         net.minecraft.resources.Identifier tagId = net.minecraft.resources.Identifier.tryParse(target.substring(1));
         if (tagId == null) {
            return false;
         }
         return item.builtInRegistryHolder().is(net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.ITEM, tagId));
      }
      Item wanted = switch (id) {
         case "iron_ingot" -> Items.IRON_INGOT;
         case "gold_ingot" -> Items.GOLD_INGOT;
         case "diamond" -> Items.DIAMOND;
         case "emerald" -> Items.EMERALD;
         case "netherite_scrap" -> Items.NETHERITE_SCRAP;
         case "redstone" -> Items.REDSTONE;
         case "lapis" -> Items.LAPIS_LAZULI;
         case "quartz" -> Items.QUARTZ;
         case "slime_ball" -> Items.SLIME_BALL;
         case "bone" -> Items.BONE;
         case "string" -> Items.STRING;
         case "gunpowder" -> Items.GUNPOWDER;
         case "ender_pearl" -> Items.ENDER_PEARL;
         case "blaze_rod" -> Items.BLAZE_ROD;
         case "ghast_tear" -> Items.GHAST_TEAR;
         default -> Items.AIR;
      };
      return wanted != null && item == wanted;
   }

   private static void broadcast(MinecraftServer server, String message) {
      if (server == null) {
         return;
      }
      for (ServerPlayer p : server.getPlayerList().getPlayers()) {
         Chat.raw(p, message);
      }
   }

   private static String strip(String title) {
      return title == null ? "" : title.replaceAll("§[0-9a-fk-or]", "");
   }

   private static String bossDisplayName(String key) {
      return switch (key) {
         case "king" -> "King Wither Skeleton";
         case "slime" -> "Slime King";
         case "golem" -> "Stone Golem";
         case "mind" -> "Mindbinder";
         case "snow" -> "Snow Queen";
         case "warden" -> "Elder Warden";
         default -> "Boss";
      };
   }

   private static String displayItemName(String key) {
      return switch (key) {
         case "iron_ingot" -> "Iron Ingots";
         case "gold_ingot" -> "Gold Ingots";
         case "diamond" -> "Diamonds";
         case "emerald" -> "Emeralds";
         case "netherite_scrap" -> "Netherite Scrap";
         case "redstone" -> "Redstone";
         case "lapis" -> "Lapis Lazuli";
         case "quartz" -> "Quartz";
         case "slime_ball" -> "Slimeballs";
         case "bone" -> "Bones";
         case "string" -> "String";
         case "gunpowder" -> "Gunpowder";
         case "ender_pearl" -> "Ender Pearls";
         case "blaze_rod" -> "Blaze Rods";
         case "ghast_tear" -> "Ghast Tears";
         default -> key;
      };
   }

   public static ItemStack contractIcon(Contract c) {
      Item base = switch (c.type) {
         case "slay" -> Items.WITHER_SKELETON_SKULL;
         case "hunt" -> Items.PLAYER_HEAD;
         case "raid_help" -> Items.OMINOUS_BOTTLE;
         case "recover" -> Items.SOUL_LANTERN;
         case "raid_slay" -> Items.OMINOUS_BOTTLE;
         case "kill_mobs" -> Items.ZOMBIE_HEAD;
         case "mine_ores" -> Items.DIAMOND_ORE;
         case "sell_earn" -> Items.GOLD_INGOT;
         case "duel_win" -> Items.IRON_SWORD;
         // The targeted shapes show what they are about: the block a dig order names, the spawn
         // egg of the mob a bounty names, the goods a standing order names. The icon is the job.
         case "mine_block" -> iconOf(c.target, Items.DIAMOND_PICKAXE);
         case "sell_item" -> iconOf(c.target, Items.GOLD_INGOT);
         case "kill_mob" -> mobIcon(c.target);
         default -> Items.PAPER;
      };
      ItemStack stack = new ItemStack(base);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal(c.title));
      String progress = "shortage".equals(c.type) ? c.delivered + "/" + c.amount : c.progress + "/" + c.amount;
      stack.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal(c.desc),
               Component.literal("§8Progress: " + progress),
               Component.literal(c.done ? "§aComplete!" : "§7Active")
            )
         )
      );
      return stack;
   }

   /** A named item's own icon, or a stand-in when the id no longer resolves. */
   private static Item iconOf(String itemId, Item fallback) {
      Item item = net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(net.minecraft.resources.Identifier.tryParse(itemId));
      return item == null || item == Items.AIR ? fallback : item;
   }

   /** A named mob's spawn egg, or a head when it has none. */
   private static Item mobIcon(String entityId) {
      net.minecraft.world.entity.EntityType<?> type =
         net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getValue(net.minecraft.resources.Identifier.tryParse(entityId));
      if (type != null) {
         java.util.Optional<net.minecraft.core.Holder<Item>> egg =
            net.minecraft.world.item.SpawnEggItem.byId(type);
         if (egg.isPresent()) {
            return egg.get().value();
         }
      }
      return Items.ZOMBIE_HEAD;
   }

   public static class Contract {
      public final String type;
      public final String target;
      public final int amount;
      public final long reward;
      public final long created;
      public final long expires;
      public final String title;
      public final String desc;
      public int delivered = 0;
      public int progress = 0;
      public boolean done = false;

      Contract(String type, String target, int amount, long reward, long created, long expires, String title, String desc) {
         this.type = type;
         this.target = target;
         this.amount = amount;
         this.reward = reward;
         this.created = created;
         this.expires = expires;
         this.title = title;
         this.desc = desc;
      }
   }
}
