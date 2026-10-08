package com.fortuneandfavors.economy;

import com.fortuneandfavors.ModItems;
import com.fortuneandfavors.menu.BetrayalMenu;
import com.fortuneandfavors.mixin.MobGoalAccessor;
import com.fortuneandfavors.util.Chat;
import com.fortuneandfavors.util.InventoryHelper;
import com.fortuneandfavors.util.JsonUtil;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Map.Entry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.BossEvent.BossBarColor;
import net.minecraft.world.BossEvent.BossBarOverlay;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.monster.Vex;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.entity.raid.Raids;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap.Types;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.Registry;

/**
 * Player Raids: vanilla village raids become server events. When a raid is won,
 * the village is safe but the Raid Warlord and its lieutenants still hold the
 * loot - slay them to collect the victory payout. Raids share a per-dimension
 * cooldown so they can't chain endlessly; players can opt in with a Raid Banner.
 *
 * Enhanced with:
 * - Boss bar showing combined HP of all raid bosses during the final phase
 * - Evoker summons vex reinforcements every 20 seconds
 * - Participation tracking: players who fight in more waves get more cash
 * - Raid Captain drops a special enchanted crossbow
 * - Illusioner and Elder Evoker as additional mini-bosses
 */
public final class PlayerRaidManager {
   public static final String WARLORD_TAG = "ff_raid_warlord";
   public static final String LIEUTENANT_TAG = "ff_raid_lieutenant";
   public static final String ELDER_EVOKER_TAG = "ff_raid_elder_evoker";
   public static final String ILLUSIONER_TAG = "ff_raid_illusioner";
   public static final String CAPTAIN_TAG = "ff_raid_captain";
   /** Tag on the wave raiders of a player raid (no village involved - they hunt the player). */
   public static final String WAVE_TAG = "ff_raid_wave";
   /** Tag on the Raid Illusioner's hostile illusion clones. */
   public static final String ILLUSION_TAG = "ff_raid_illusion";
   public static final long RAID_COOLDOWN_MS = 30L * 60L * 1000L; // 30 minutes
   private static final Map<String, RaidState> tracked = new HashMap<>();
   private static final Map<String, UUID> warlordIds = new HashMap<>();
   private static final Map<String, List<UUID>> lieutenantIds = new HashMap<>();
   private static final Map<String, UUID> elderEvokerIds = new HashMap<>();
   private static final Map<String, UUID> illusionerIds = new HashMap<>();
   private static final Map<String, UUID> captainIds = new HashMap<>();
   private static final Map<String, ServerBossEvent> bossBars = new HashMap<>();
   /** The betrayer's own health shown as a boss bar while they fight for the Warlord. */
   private static final Map<String, ServerBossEvent> betrayerBars = new HashMap<>();
   private static final Map<String, Long> cooldowns = new HashMap<>();
   /** Boss phases that run independently of the vanilla raid lifecycle - the vanilla
    *  raid is cleaned up ~30s after the waves are cleared, but the bosses and their
    *  boss bar keep going (and the victory only settles when all bosses are dead). */
   private static final Map<String, BossPhase> bossPhases = new HashMap<>();
   /** Raid ids started by PLAYERS (bell+bad omen or Raid Banner) - only these get
    *  announced/processed as player raids. Vanilla villager raids are left alone. */
   private static final Set<String> playerRaidKeys = new HashSet<>();
   /** Difficulty multiplier applied by the Bad Omen level that started the raid. */
   private static int raidDifficulty = 0;
   private static final Map<String, Long> fastestCompletions = new HashMap<>();
   private static final java.util.Random RANDOM = new java.util.Random();
   private static Path dataFile;

   private PlayerRaidManager() {
   }

   public static void load(MinecraftServer server) {
      cooldowns.clear();
      dataFile = EconomyManager.getDataDir(server).resolve("player_raids.json");
      JsonObject root = JsonUtil.readOrCreate(dataFile, new JsonObject());
      if (root.has("cooldowns") && root.get("cooldowns").isJsonObject()) {
         long now = System.currentTimeMillis();
         for (Entry<String, JsonElement> e : root.getAsJsonObject("cooldowns").entrySet()) {
            try {
               long end = e.getValue().getAsLong();
               if (end > now) {
                  cooldowns.put(e.getKey(), end);
               }
            } catch (Exception ignored) {
            }
         }
      }
   }

   public static void save(MinecraftServer server) {
      if (dataFile == null) {
         dataFile = EconomyManager.getDataDir(server).resolve("player_raids.json");
      }
      JsonObject root = new JsonObject();
      JsonObject cd = new JsonObject();
      for (Entry<String, Long> e : cooldowns.entrySet()) {
         cd.addProperty(e.getKey(), e.getValue());
      }
      root.add("cooldowns", cd);
      JsonUtil.write(dataFile, root);
   }

   public static boolean isOnCooldown(ServerLevel level) {
      if (!ModConfig.raidCooldown()) return false;
      Long end = cooldowns.get(level.dimension().identifier().toString());
      return end != null && System.currentTimeMillis() < end;
   }

   public static long remainingCooldownMs(ServerLevel level) {
      if (!ModConfig.raidCooldown()) return 0L;
      Long end = cooldowns.get(level.dimension().identifier().toString());
      if (end == null) {
         return 0L;
      }
      return Math.max(0L, end - System.currentTimeMillis());
   }

   public static void markCooldown(ServerLevel level) {
      if (!ModConfig.raidCooldown()) return;
      cooldowns.put(level.dimension().identifier().toString(), System.currentTimeMillis() + RAID_COOLDOWN_MS);
      if (level.getServer() != null) {
         save(level.getServer());
      }
   }

   /** Returns an error message, or null on success. This is a PLAYER raid: no
    *  village needed, no vanilla Raid object - raiders hunt the player directly
    *  and the final wave summons the custom minibosses. */
   public static String triggerRaid(ServerPlayer player, ItemStack banner) {
      if (!(player.level() instanceof ServerLevel level)) {
         return "Raids only happen in the overworld.";
      }
      if (isOnCooldown(level)) {
         long s = remainingCooldownMs(level) / 1000L;
         return "Raids are on cooldown here for another " + (s / 3600L) + "h " + ((s % 3600L) / 60L) + "m.";
      }
      String key = level.dimension().identifier().toString() + "#raid";
      if (tracked.containsKey(key) && !tracked.get(key).settled) {
         return "A player raid is already underway here!";
      }
      RaidState st = new RaidState();
      st.dimension = level.dimension();
      st.center = player.blockPosition();
      int omen = 0;
      MobEffectInstance badOmen = player.getEffect(MobEffects.BAD_OMEN);
      if (badOmen != null) {
         omen = Math.max(1, Math.min(3, badOmen.getAmplifier() + 1));
      }
      raidDifficulty = omen;
      st.omenLevel = omen;
      st.announced = true;
      st.wave = 0;
      st.nextWaveTick = level.getGameTime() - 1L; // first wave spawns immediately
      tracked.put(key, st);
      playerRaidKeys.add(key);
      if (banner != null && !player.getAbilities().instabuild) {
         banner.shrink(1);
      }
      announceRaidStart(level, st);
      player.sendSystemMessage(
         Component.literal("§c§lThe Raid Banner ignites - raiders are coming for YOU! Survive the waves and slay the Warlord for the loot."),
         true
      );
      return null;
   }

   /** Sets the difficulty boost based on the Bad Omen level that started the raid. */
   public static void setRaidDifficulty(int badOmenAmplifier) {
      raidDifficulty = Math.max(0, Math.min(5, badOmenAmplifier + 1));
   }

   public static int raidDifficulty() {
      return raidDifficulty;
   }

   /** Player raids no longer touch vanilla Raid objects at all, so vanilla
    *  villager raids behave 100% vanilla (RaidMixin never triggers). */
   public static boolean isTrackedRaid(Raid raid) {
      return false;
   }

   /** Dynamic difficulty multiplier for a given spot: 1.0 solo, +0.25 per
    *  extra nearby player, capped at 2.5 (7+ players). */
   public static double raidScale(Raid raid) {
      return 1.0;
   }

   /** The dynamic difficulty multiplier for a given spot: how many players are
    *  within 128 blocks of the center right now. 1.0 solo, +0.25 per extra
    *  player, capped at 2.5 (7+ players). */
   /** How many players are within 128 blocks of the raid center right now. */
   public static int nearbyPlayers(ServerLevel level, BlockPos center) {
      int nearby = 0;
      for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
         if (p.level().dimension().equals(level.dimension()) && p.distanceToSqr(center.getX(), center.getY(), center.getZ()) < 128.0 * 128.0) {
            nearby++;
         }
      }
      return nearby;
   }

   /** How near a player must stand to a live raid Warlord to count as fighting it. */
   public static final double RAID_MANNED_RANGE = 48.0;

   /** How many living players are within {@code range} blocks of a point. */
   private static int livingPlayersWithin(ServerLevel level, double x, double y, double z, double range) {
      int found = 0;
      for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
         if (p.level().dimension().equals(level.dimension()) && p.isAlive()
            && p.distanceToSqr(x, y, z) < range * range) {
            found++;
         }
      }
      return found;
   }

   /**
    * Raid keys whose Warlord is alive with nobody within {@code range} of it.
    *
    * <p>The board's own abandoned-fight rule reads the bodies BossManager owns, and a raid's
    * Warlord is this manager's - so the question has to be asked here, or the loudest fight the
    * mod has (a boss bar over a village with nobody in it) is the one the board cannot see.
    */
   public static List<String> abandonedRaidWarlords(MinecraftServer server, double range) {
      List<String> out = new ArrayList<>();
      if (server == null) {
         return out;
      }
      for (String key : new ArrayList<>(tracked.keySet())) {
         RaidState state = tracked.get(key);
         if (state == null || state.settled || !state.warlordSpawned || state.dimension == null) {
            continue;
         }
         ServerLevel level = server.getLevel(state.dimension);
         if (level == null) {
            continue;
         }
         Mob warlord = warlordAlive(level, key);
         if (warlord == null || !warlord.isAlive()) {
            continue;
         }
         if (livingPlayersWithin(level, warlord.getX(), warlord.getY(), warlord.getZ(), range) == 0) {
            out.add(key);
         }
      }
      return out;
   }

   /** Where an abandoned raid's Warlord is standing, for a job's description. */
   public static String raidWarlordWhere(MinecraftServer server, String key) {
      RaidState state = tracked.get(key);
      if (state == null || state.dimension == null) {
         return "a raid";
      }
      ServerLevel level = server.getLevel(state.dimension);
      String dim = state.dimension.identifier().getPath().replace('_', ' ');
      Mob warlord = level != null ? warlordAlive(level, key) : null;
      BlockPos at = warlord != null ? warlord.blockPosition() : state.center;
      return at.getX() + ", " + at.getZ() + " in the " + dim;
   }

   /** How many LIVING players are within 128 blocks of the raid center - a dead
    *  player is not defending the raid, so deaths can never count toward a win. */
   private static int aliveNearbyPlayers(ServerLevel level, BlockPos center) {
      int nearby = 0;
      for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
         if (p.level().dimension().equals(level.dimension()) && p.isAlive()
            && p.distanceToSqr(center.getX(), center.getY(), center.getZ()) < 128.0 * 128.0) {
            nearby++;
         }
      }
      return nearby;
   }

   /** The dynamic difficulty multiplier for a given spot: 1.0 solo, +0.25 per
    *  extra nearby player, capped at 2.5 (7+ players). */
   public static double dynamicPlayerScale(ServerLevel level, BlockPos center) {
      return 1.0 + 0.25 * Math.min(6, Math.max(0, nearbyPlayers(level, center) - 1));
   }

   public static void tick(MinecraftServer server) {
      tickWaves(server);
      tickBossPhases(server);
      tickHud(server);
   }

   /**
    * Live raid readout in the action bar, once a second, for everyone in the
    * fight.
    *
    * <p>The wave phase announced itself and then went quiet, so a player who
    * joined halfway through had no way to tell wave 2 from wave 5, how many
    * raiders were still alive, or whether the boss phase had even begun. The
    * countdown warning for an empty arena rides the same line, because "nobody
    * is defending" is the one piece of raid state a player must not miss.
    */
   private static void tickHud(MinecraftServer server) {
      if (tracked.isEmpty()) {
         return;
      }
      for (Entry<String, RaidState> e : tracked.entrySet()) {
         RaidState state = e.getValue();
         if (state == null || state.settled) {
            continue;
         }
         ServerLevel level = server.getLevel(state.dimension);
         if (level == null) {
            continue;
         }
         long now = level.getGameTime();
         if (now % 20L != 0L) {
            continue;
         }
         String key = e.getKey();
         String text;
         if (state.noPlayersSince >= 0L) {
            long left = Math.max(0L, 1200L - (now - state.noPlayersSince)) / 20L;
            text = "\u00a7c\u00a7l\u26a0 NO DEFENDERS \u00a78| \u00a7fraiders leave in \u00a7f" + left + "s\u00a77 - get back!";
         } else if (!state.warlordSpawned) {
            int totalWaves = 3 + Math.min(3, state.omenLevel);
            int left = livingWaveRaiders(level, key);
            text = "\u00a7c\u00a7l\u2694 RAID \u00a78| \u00a7fWave " + Math.max(1, state.wave) + "\u00a77/\u00a7f" + totalWaves
               + " \u00a78| \u00a7f" + left + "\u00a77 raider" + (left == 1 ? "" : "s") + " left";
         } else {
            int alive = allBossMobs(level, key).size();
            long secs = state.fightStartMs > 0L ? (System.currentTimeMillis() - state.fightStartMs) / 1000L : 0L;
            text = "\u00a7c\u00a7l\u2694 RAID \u00a78| \u00a7f" + alive + "\u00a77 boss" + (alive == 1 ? "" : "es") + " left \u00a78| \u00a7f"
               + formatDuration(secs);
            if (state.betrayJoined) {
               text = text + " \u00a78| \u00a74a betrayer fights for them";
            } else if (state.betrayDenied) {
               text = text + " \u00a78| \u00a77the offer was refused";
            }
         }
         for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.isAlive()
               && p.level().dimension().equals(level.dimension())
               && p.distanceToSqr(state.center.getX(), state.center.getY(), state.center.getZ()) < 128.0 * 128.0) {
               p.sendOverlayMessage(Component.literal(text));
            }
         }
      }
   }

   /** Runs the player-raid wave phase: raiders spawn around the PLAYER (no
    *  village), each wave must be cleared before the next one drops, and the
    *  final wave hands over to the custom miniboss phase. */
   private static void tickWaves(MinecraftServer server) {
      for (Map.Entry<String, RaidState> e : new ArrayList<>(tracked.entrySet())) {
         RaidState state = e.getValue();
         if (state == null || state.settled || state.warlordSpawned) {
            continue;
         }
         String key = e.getKey();
         ServerLevel level = server.getLevel(state.dimension);
         if (level == null) {
            continue;
         }
         long now = level.getGameTime();
         // Abandonment: nobody alive near the fight for 60s -> the raid is lost.
         if (aliveNearbyPlayers(level, state.center) == 0) {
            if (state.noPlayersSince < 0L) {
               state.noPlayersSince = now;
               // Only the dimension the raid is running in: this used to be a
               // server-wide broadcast, so an unrelated player mining two
               // dimensions away was told a raid they had never seen was about
               // to be lost.
               for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
                  if (p.level().dimension().equals(level.dimension())) {
                     Chat.raw(p, "§c⚔ §7The raiders found no one to fight - they will scatter in §f60s§7 unless someone returns!");
                  }
               }
            } else if (now - state.noPlayersSince >= 1200L) {
               state.settled = true;
               raidDefeatVfx(level, state.center);
               discardWaveRaiders(level, key);
               for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                  Chat.raw(p, "§8§m═══════════════════════════§r");
                  Chat.raw(p, "  §c§l⚔ RAID LOST§r §7- no one faced the raiders, they claimed the loot!");
                  Chat.raw(p, "§8§m═══════════════════════════§r");
               }
               cleanupRaid(key);
               raidDifficulty = 0;
            }
            continue;
         }
         state.noPlayersSince = -1L;
         if (now % 10L == 0L && state.wave > 0) {
            updateWaveBar(level, key, state);
         }
         // Participation credit for anyone fighting the waves.
         if (now % 100L == 0L) {
            trackParticipation(level, state);
         }
         // Ambient VFX + horn during the wave phase.
         if (now % 60L == 0L) {
            raidAmbientVfx(level, state.center);
         }
         if (now % 600L == 0L) {
            playRaidHorn(level, state.center);
         }
         // First wave: fire as soon as the raid starts.
         if (state.wave == 0) {
            state.wave = 1;
            state.nextWaveTick = now - 1L;
            announceWaveStart(level, state, 1);
            spawnPlayerRaidWave(level, state);
            continue;
         }
         // Only spawn the next wave when the current one is cleared AND the
         // inter-wave delay has passed.
         if (now < state.nextWaveTick || livingWaveRaiders(level, key) > 0) {
            continue;
         }
         int totalWaves = 3 + Math.min(3, state.omenLevel); // 4-6 waves
         if (state.wave >= totalWaves) {
            // Final wave cleared -> the custom minibosses descend.
            state.warlordSpawned = true;
            state.fightStartMs = System.currentTimeMillis();
            discardWaveRaiders(level, key);
            removeWaveBar(key);   // the boss bar takes over
            spawnRaidBosses(level, key, state);
            bossPhases.putIfAbsent(key, new BossPhase(state.dimension));
            continue;
         }
         waveClearReward(level, state);
         waveSize.remove(key);
         state.wave++;
         state.nextWaveTick = now + 120L; // 6s breather between waves
         announceWaveStart(level, state, state.wave);
         spawnPlayerRaidWave(level, state);
      }
   }

   /** Spawns one wave of raiders around the raid center. The composition scales
    *  with wave number, Bad Omen level, and how many players are fighting. */
   private static void spawnPlayerRaidWave(ServerLevel level, RaidState state) {
      int w = state.wave;
      double playerScale = dynamicPlayerScale(level, state.center);
      ServerPlayer target = nearestFighter(level, state);
      // Wave 1: Pillagers + Vindicators (classic raid opener)
      spawnWaveRaider(level, state, EntityTypes.PILLAGER, (int)Math.round((3 + w) * playerScale));
      spawnWaveRaider(level, state, EntityTypes.VINDICATOR, (int)Math.round((1 + w / 2) * playerScale));
      // Wave 2+: Add witches for healing
      if (w >= 2) {
         spawnWaveRaider(level, state, EntityTypes.WITCH, Math.max(1, w / 2));
      }
      // Wave 3+: Ravagers charge in
      if (w >= 3) {
         spawnWaveRaider(level, state, EntityTypes.RAVAGER, Math.max(1, (w - 1) / 2));
      }
      // Wave 4+: Illusioners create chaos with clones
      if (w >= 4) {
         spawnWaveRaider(level, state, EntityTypes.ILLUSIONER, 1 + (w - 4) / 2);
      }
      // Wave 5+: Evokers summon vexes and fangs
      if (w >= 5) {
         spawnWaveRaider(level, state, EntityTypes.EVOKER, 1 + (w - 5) / 2);
      }
      // Wave 6+: Full raid escalation
      if (w >= 6) {
         spawnWaveRaider(level, state, EntityTypes.RAVAGER, 1 + (w - 6) / 2);
         spawnWaveRaider(level, state, EntityTypes.VINDICATOR, 1 + (w - 6) / 2);
         spawnWaveRaider(level, state, EntityTypes.PILLAGER, 2 + (w - 6) / 2);
      }
      if (target != null) {
         com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.SOUL_FIRE_FLAME, target.getX(), target.getY() + 2.0, target.getZ(), 25, 2.0, 1.5, 2.0, 0.05);
      }
      applyWaveTwist(level, state, target);
   }

   private static final String BOUNTY_TAG = "ff_raid_bounty";

   /**
    * Every wave from the second gets one twist, named as it lands, so no two waves play the same;
    * and every wave has one Bounty raider, glowing gold, worth emeralds to whoever drops it.
    */
   private static void applyWaveTwist(ServerLevel level, RaidState state, ServerPlayer target) {
      String key = stateWaveKey(state);
      List<Raider> wave = new ArrayList<>();
      for (Raider r : level.getEntitiesOfClass(Raider.class, boxAround(state.center, 64, 24))) {
         if (isTagged(r, WAVE_TAG, key) && r.tickCount < 5) {
            wave.add(r);
         }
      }
      if (wave.isEmpty()) {
         return;
      }
      Raider bounty = wave.get(RANDOM.nextInt(wave.size()));
      bounty.setGlowingTag(true);
      bounty.setCustomName(Component.literal("§6§l☠ Bounty §e" + bounty.getType().getDescription().getString()));
      bounty.setCustomNameVisible(true);
      tagMob(bounty, BOUNTY_TAG, key);
      String twist = null;
      if (state.wave >= 2) {
         switch (RANDOM.nextInt(4)) {
            case 0 -> {
               twist = "§4§lBLOOD MOON §7- the raiders hit harder.";
               wave.forEach(r -> r.addEffect(new MobEffectInstance(MobEffects.STRENGTH, 6000, 0)));
            }
            case 1 -> {
               twist = "§7§lIRON WALL §7- the raiders came armoured.";
               wave.forEach(r -> {
                  r.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 6000, 0));
                  r.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
                  r.setDropChance(EquipmentSlot.HEAD, 0.0F);
               });
            }
            case 2 -> {
               twist = "§b§lSWIFT STRIKE §7- the raiders are fast.";
               wave.forEach(r -> r.addEffect(new MobEffectInstance(MobEffects.SPEED, 6000, 1)));
            }
            default -> {
               if (target != null) {
                  // AMBUSH: half the wave comes out of the ground round the nearest fighter.
                  twist = "§c§lAMBUSH §7- they were waiting for you.";
                  for (int i = 0; i < wave.size(); i += 2) {
                     double a = RANDOM.nextDouble() * Math.PI * 2.0, r = 7.0 + RANDOM.nextDouble() * 4.0;
                     double x = target.getX() + Math.cos(a) * r, z = target.getZ() + Math.sin(a) * r;
                     double y = feetY(level, x, z, target.getBlockY());
                     if (!Double.isNaN(y)) {
                        wave.get(i).teleportTo(x, y, z);
                        com.fortuneandfavors.net.FfVfx.shape(level, com.fortuneandfavors.net.FfVfx.ICE_ERUPT, ParticleTypes.FLAME, new Vec3(x, y, z), Vec3.ZERO, 2.0, 0.0, 0x8A1020);
                     }
                  }
               }
            }
         }
      }
      for (ServerPlayer p : level.getPlayers(pl -> pl.distanceToSqr(state.center.getX(), state.center.getY(), state.center.getZ()) < 128.0 * 128.0)) {
         if (twist != null) {
            Chat.raw(p, "§c⚔ §fTwist: " + twist);
         }
         p.sendOverlayMessage(Component.literal("§6☠ A Bounty raider glows gold - §edrop it for emeralds."));
      }
   }

   /** A wave cleared: a gold burst at the center and a little experience for everyone who held it. */
   private static void waveClearReward(ServerLevel level, RaidState state) {
      BlockPos c = state.center;
      double y = surfaceY(level, c);
      spawnRaidFountain(level, c.getX() + 0.5, y, c.getZ() + 0.5, 0.35);
      com.fortuneandfavors.net.FfVfx.shape(level, com.fortuneandfavors.net.FfVfx.FROST_NOVA, ParticleTypes.TOTEM_OF_UNDYING, new Vec3(c.getX() + 0.5, y, c.getZ() + 0.5), Vec3.ZERO, 12.0, 0.0, WAR_GOLD);
      for (ServerPlayer p : level.getPlayers(pl -> pl.isAlive() && pl.distanceToSqr(c.getX(), c.getY(), c.getZ()) < 64.0 * 64.0)) {
         p.giveExperiencePoints(15 + state.wave * 10);
         p.sendOverlayMessage(Component.literal("§a§l✔ WAVE " + state.wave + " HELD §7+" + (15 + state.wave * 10) + " xp"));
      }
   }

   /** The player closest to the raid center - the raiders' target. A player who
    *  joined the Warlord is never chosen: the raid mobs won't touch them. */
   private static ServerPlayer nearestFighter(ServerLevel level, RaidState state) {
      ServerPlayer best = null;
      double bestDist = Double.MAX_VALUE;
      for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
         if (!p.level().dimension().equals(level.dimension())) {
            continue;
         }
         if (state.betrayJoined && state.betrayerId != null && state.betrayerId.equals(p.getUUID())) {
            continue;
         }
         double d = p.distanceToSqr(state.center.getX(), state.center.getY(), state.center.getZ());
         if (d < bestDist) {
            bestDist = d;
            best = p;
         }
      }
      return best;
   }

   /** Spawns {@code count} raiders of the given type on the surface near the
    *  raid center, tagged so the wave phase can track them, and tells them to
    *  hunt the player. */
   private static void spawnWaveRaider(ServerLevel level, RaidState state, EntityType<? extends Mob> type, int count) {
      for (int i = 0; i < count; i++) {
         double a = RANDOM.nextDouble() * Math.PI * 2.0;
         double r = 14.0 + RANDOM.nextDouble() * 18.0;
         int x = state.center.getX() + (int)Math.round(Math.cos(a) * r);
         int z = state.center.getZ() + (int)Math.round(Math.sin(a) * r);
         // Surface height is computed at the raider's OWN column (the old code
         // used the raid center's Y for every raider, which buried them inside
         // hills and 2x1 holes on uneven terrain), and the spot must have open
         // air - never spawn inside blocks, ravines or underground.
         double y = feetY(level, x + 0.5, z + 0.5, state.center.getY());
         if (Double.isNaN(y)) {
            continue;   // no honest floor here - better one raider fewer than one in a wall
         }
         Mob raider = type.create(level, EntitySpawnReason.EVENT);
         if (raider == null) {
            continue;
         }
         raider.setPos(x + 0.5, y, z + 0.5);
         raider.setPersistenceRequired();
         // EntityType.create() doesn't give default equipment - add weapons manually
         if (type == EntityTypes.PILLAGER) {
            // 20% chance: tank pillager with armor + shield + sword instead of crossbow
            if (RANDOM.nextInt(5) == 0) {
               raider.setCustomName(Component.literal("§6§l⚔ Tank Pillager"));
               raider.setCustomNameVisible(true);
               raider.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
               raider.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
               raider.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
               raider.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
               // Tank has 2x HP
               AttributeInstance tankHp = raider.getAttribute(Attributes.MAX_HEALTH);
               if (tankHp != null) tankHp.setBaseValue(tankHp.getBaseValue() * 2.0);
               raider.setHealth(raider.getMaxHealth());
               // A pillager without a crossbow has no attack goal at all - give
               // the tank real melee AI so it actually fights.
               if (raider instanceof MobGoalAccessor acc && raider instanceof PathfinderMob pm) {
                  acc.fortuneandfavors$goalSelector().addGoal(2, new MeleeAttackGoal(pm, 1.1, false));
               }
            } else {
               raider.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.CROSSBOW));
            }
         } else if (type == EntityTypes.VINDICATOR) {
            raider.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_AXE));
         } else if (type == EntityTypes.ILLUSIONER) {
            // Illusioners are archers - give them a real bow.
            raider.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
         }
         disjoinFromRaid(raider);
         tagMob(raider, WAVE_TAG, stateWaveKey(state));
         double hpMult = (1.0 + 0.2 * state.wave) * dynamicPlayerScale(level, state.center);
         AttributeInstance hp = raider.getAttribute(Attributes.MAX_HEALTH);
         if (hp != null) {
            hp.setBaseValue(hp.getBaseValue() * hpMult);
         }
         raider.setHealth(raider.getMaxHealth());
         level.addFreshEntity(raider);
         ServerPlayer target = nearestFighter(level, state);
         if (target != null) {
            raider.setTarget(target);
         }
         com.fortuneandfavors.net.FfVfx.shape(level, com.fortuneandfavors.net.FfVfx.ICE_BURST, ParticleTypes.FLAME, raider.position().add(0.0, 1.0, 0.0), Vec3.ZERO, 0.5, 0.0, 0x8A1020);
      }
   }

   /**
    * Where a body can stand near (x, z): solid ground under it, two clear blocks for it, no
    * fluid, and level with the fight. The floor nearest {@code nearY} wins (searched from above,
    * so the open floor beats a cave under it); the no-leaves surface is the fallback, and only if
    * it is within 12 blocks of the fight - never a treetop, a mountain peak or a ravine floor.
    * NaN when nothing within a few blocks of (x, z) qualifies.
    */
   static double feetY(ServerLevel level, double x, double z, int nearY) {
      for (int attempt = 0; attempt < 8; attempt++) {
         int cx = (int)Math.floor(x) + (attempt == 0 ? 0 : RANDOM.nextInt(7) - 3);
         int cz = (int)Math.floor(z) + (attempt == 0 ? 0 : RANDOM.nextInt(7) - 3);
         for (int y = nearY + 4; y >= nearY - 8; y--) {
            if (standable(level, new BlockPos(cx, y, cz))) {
               return y;
            }
         }
         int top = level.getHeight(Types.MOTION_BLOCKING_NO_LEAVES, cx, cz);
         if (Math.abs(top - nearY) <= 12 && standable(level, new BlockPos(cx, top, cz))) {
            return top;
         }
      }
      return Double.NaN;
   }

   private static boolean standable(ServerLevel level, BlockPos feet) {
      BlockPos below = feet.below();
      return level.getBlockState(below).isFaceSturdy(level, below, net.minecraft.core.Direction.UP)
         && level.getFluidState(feet).isEmpty()
         && level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
         && level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty();
   }

   /** Feet height at (x, z) near {@code nearY}, or {@code nearY} itself when nothing nearby qualifies. */
   private static double standY(ServerLevel level, double x, double z, int nearY) {
      double y = feetY(level, x, z, nearY);
      return Double.isNaN(y) ? nearY : y;
   }

   /** Feet height at the raid's own center: the fight's floor, falling back to the surface there. */
   private static int surfaceY(ServerLevel level, BlockPos center) {
      double y = feetY(level, center.getX() + 0.5, center.getZ() + 0.5, center.getY());
      return Double.isNaN(y) ? level.getHeight(Types.MOTION_BLOCKING_NO_LEAVES, center.getX(), center.getZ()) : (int)y;
   }

   private static String stateWaveKey(RaidState state) {
      return state.dimension.identifier().toString() + "#raid";
   }

   /** Counts living wave raiders tagged for this raid key. */
   private static int livingWaveRaiders(ServerLevel level, String key) {
      BlockPos center = stateCenterFor(level, key);
      List<Raider> raiders = level.getEntitiesOfClass(Raider.class, boxAround(center, 48, 16));
      int count = 0;
      for (Raider raider : raiders) {
         if (isTagged(raider, WAVE_TAG, key)) {
            count++;
         }
      }
      return count;
   }

   private static BlockPos stateCenterFor(ServerLevel level, String key) {
      RaidState s = tracked.get(key);
      return s != null ? s.center : BlockPos.ZERO;
   }

   private static AABB boxAround(BlockPos center, int horiz, int vert) {
      return new AABB(
         net.minecraft.world.phys.Vec3.atBottomCenterOf(center.offset(-horiz, -vert, -horiz)),
         net.minecraft.world.phys.Vec3.atBottomCenterOf(center.offset(horiz, vert + 1, horiz))
      );
   }

   /** Removes any remaining wave raiders (called when the boss phase begins). */
   private static void discardWaveRaiders(ServerLevel level, String key) {
      List<Raider> raiders = level.getEntitiesOfClass(Raider.class, boxAround(stateCenterFor(level, key), 64, 16));
      for (Raider raider : raiders) {
         if (isTagged(raider, WAVE_TAG, key)) {
            raider.discard();
         }
      }
   }

   private static boolean isTagged(LivingEntity entity, String tag, String key) {
      CustomData cd = (CustomData)entity.get(DataComponents.CUSTOM_DATA);
      if (cd == null) {
         return false;
      }
      return key.equals(cd.copyTag().getString(tag).orElse(""));
   }

   /** Runs the boss phase on its own lifecycle so the combined boss bar and the
    *  victory settlement keep working after the vanilla raid is cleaned up. */
   private static void tickBossPhases(MinecraftServer server) {
      for (String key : new ArrayList<>(bossPhases.keySet())) {
         BossPhase phase = bossPhases.get(key);
         if (phase == null) {
            continue;
         }
         ServerLevel level = server.getLevel(phase.dimension);
         RaidState state = tracked.get(key);
         if (level == null || state == null || state.settled || !state.warlordSpawned) {
            bossPhases.remove(key);
            continue;
         }
         // Evoker vex summoning every 15 seconds (300 ticks)
         if (level.getGameTime() % 300L == 0L) {
            summonVexReinforcements(level, key);
         }
         // Illusioner's illusion clones - fragile, but they actually attack
         if (level.getGameTime() % 200L == 40L) {
            illusionerSpawnClones(level, key);
         }
         // Spawn necromancer once when boss phase starts
         if (!state.necromancerSpawned) {
            state.necromancerSpawned = true;
            spawnNecromancer(level, key, state, state.center, 1.0 + 0.25 * raidDifficulty, dynamicPlayerScale(level, state.center));
         }
         // Necromancer: summon wither skeletons every 12s + lifesteal every 3s
         Mob necroMob = mobAlive(level, necromancerIds, key);
         if (necroMob != null && necroMob.isAlive()) {
            if (level.getGameTime() % 240L == 0L) {
               necromancerSummonWitherSkeletons(level, key);
            }
            if (level.getGameTime() % 60L == 40L) {
               necromancerLifesteal(level, key);
            }
         }
         // The Warlord: three phases driven by his escort and his own health, on a clock of his
         // own rather than the level's - see warlordTick.
         Mob warlordMob = warlordAlive(level, key);
         if (warlordMob != null && warlordMob.isAlive()) {
            int escort =
               livingLieutenants(level, key).size()
                  + (mobAlive(level, elderEvokerIds, key) != null ? 1 : 0)
                  + (mobAlive(level, illusionerIds, key) != null ? 1 : 0)
                  + (mobAlive(level, captainIds, key) != null ? 1 : 0)
                  + (mobAlive(level, necromancerIds, key) != null ? 1 : 0);
            float fraction = warlordMob.getMaxHealth() <= 0.0F
               ? 1.0F
               : warlordMob.getHealth() / warlordMob.getMaxHealth();
            int stage = warlordPhase(escort, fraction);
            if (stage != state.warlordPhase) {
               state.warlordPhase = stage;
               announceWarlordPhase(level, warlordMob, stage);
            }
            warlordTick(level, key, state, warlordMob, stage, level.getGameTime());
         }
         // === SECOND PHASE: THE WARLORD'S OFFER (multiplayer only, once per raid) ===
         if (warlordMob != null && warlordMob.isAlive() && !state.betrayalTriggered
               && warlordMob.getHealth() < warlordMob.getMaxHealth() * 0.4F
               && nearbyPlayers(level, state.center) >= 2) {
            state.betrayalTriggered = true;
            triggerBetrayal(level, key, state);
         }
         // Betrayer health shown as a boss bar while they fight for the Warlord
         updateBetrayerBar(level, key, state);
         // Bosses never target the player who joined the Warlord
         if (state.betrayJoined && state.betrayerId != null) {
            for (Mob b : allBossMobs(level, key)) {
               if (b != null && b.getTarget() != null && b.getTarget().getUUID().equals(state.betrayerId)) {
                  b.setTarget(null);
               }
            }
         }

         // Captain rapid fire every 4 seconds
         Mob captainMob = mobAlive(level, captainIds, key);
         if (captainMob != null && captainMob.isAlive() && level.getGameTime() % 80L == 60L) {
            captainRapidFire(level, captainMob, state);
         }
         // Update boss bar with combined HP
         updateBossBar(level, key, state);
         // Abandonment check: if every defender is dead or gone, give them a
         // grace period to come back, then the champions overrun the village.
         if (aliveNearbyPlayers(level, state.center) == 0) {
            if (state.noPlayersSince < 0L) {
               state.noPlayersSince = level.getGameTime();
               for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                  Chat.raw(p, "§c⚔ §7All fighters have fallen! The champions will claim the loot in §f60s§7 unless someone returns!");
               }
            } else if (level.getGameTime() - state.noPlayersSince >= 1200L) {
               state.settled = true;
               raidDefeatVfx(level, state.center);
               removeBossBar(key);
               removeBetrayerBar(key);
               clearRaidGlows(level);
               despawnBosses(level, key);
               for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                  Chat.raw(p, "§8§m═══════════════════════════§r");
                  Chat.raw(p, "  §c§l⚔ RAID LOST§r §7- no one fought the champions, they took the loot!");
                  Chat.raw(p, "§8§m═══════════════════════════§r");
               }
               cleanupRaid(key);
               raidDifficulty = 0;
               bossPhases.remove(key);
            }
         } else {
            state.noPlayersSince = -1L;
         }
         // Check if all bosses are dead
         Mob warlord = warlordAlive(level, key);
         List<Mob> livingLt = livingLieutenants(level, key);
         Mob elderEvoker = mobAlive(level, elderEvokerIds, key);
         Mob illusioner = mobAlive(level, illusionerIds, key);
         Mob captain = mobAlive(level, captainIds, key);
         Mob necro = mobAlive(level, necromancerIds, key);
         // The raid cannot be won while the betrayer still fights for the Warlord -
         // it only ends when THEY die. It also cannot be won if every defender is
         // dead or gone: a raid where everyone died is a LOSS, never a victory.
         boolean betrayerAlive = state.betrayJoined && state.betrayerId != null
            && level.getServer().getPlayerList().getPlayer(state.betrayerId) != null;
         // === THE BETRAYER'S VICTORY: "VILLAIN OF THE VILLAGE" ===
         // If the player who joined the Warlord is alive while every other
         // participant has fallen, they WON the raid. All raiders, minibosses
         // and the Warlord vanish, their boss bars drop, and the betrayer takes
         // the "Villain Of the Village" achievement.
         if (state.betrayJoined && state.betrayerId != null
               && betrayerAlive && otherParticipantsDeadOrGone(level, state)) {
            if (state.villainSince < 0L) {
               state.villainSince = level.getGameTime();
               level.getServer().getPlayerList().broadcastSystemMessage(
                  Component.literal(Chat.colorize("§7The last defender has fallen... the Warlord's champion stands alone.")), false);
            } else if (level.getGameTime() - state.villainSince >= 200L) {
               state.settled = true;
               settleBetrayalVictory(level, state);
               cleanupRaid(key);
               raidDifficulty = 0;
               bossPhases.remove(key);
            }
         } else {
            state.villainSince = -1L;
         }
         // === PLAYER RAID LOSS: EVERY PARTICIPANT DIED ===
         if (!state.settled && !state.betrayJoined && participantsAllDead(level, state)) {
            if (state.allDeadSince < 0L) {
               state.allDeadSince = level.getGameTime();
            } else if (level.getGameTime() - state.allDeadSince >= 200L) {
               state.settled = true;
               settleDefeatAllFell(level, key, state);
               cleanupRaid(key);
               raidDifficulty = 0;
               bossPhases.remove(key);
            }
         } else if (!state.settled) {
            state.allDeadSince = -1L;
         }
         if (warlord == null && livingLt.isEmpty() && elderEvoker == null && illusioner == null && captain == null && necro == null) {
            if (!betrayerAlive && aliveNearbyPlayers(level, state.center) > 0) {
               state.settled = true;
               removeBossBar(key);
               removeBetrayerBar(key);
               settleVictory(level, state);
               cleanupRaid(key);
               raidDifficulty = 0;
               bossPhases.remove(key);
            } else if (!betrayerAlive) {
               // The champions fell, but so did every defender - nobody survived
               // to claim the hoard. End it as a loss, not a victory.
               state.settled = true;
               raidDefeatVfx(level, state.center);
               removeBossBar(key);
               removeBetrayerBar(key);
               clearRaidGlows(level);
               despawnBosses(level, key);
               for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                  Chat.raw(p, "§8§m═══════════════════════════════§r");
                  Chat.raw(p, "  §c§l⚔ RAID LOST§r §7- every defender fell, the Warlord's hoard is lost!");
                  Chat.raw(p, "§8§m═══════════════════════════════§r");
               }
               cleanupRaid(key);
               raidDifficulty = 0;
               bossPhases.remove(key);
            }
         }
      }
   }

   /** Grants the "Bane of the Hoard" achievement once all five raid bosses
    *  (Warlord, Elder Evoker, Illusioner, Captain, Necromancer) have fallen. */
   private static void checkAllFiveBosses(ServerLevel level, RaidState state, ServerPlayer killer) {
      if (state != null && state.bossKillers.size() >= 5 && killer != null) {
         Advancements.grant(killer, "raid_all_bosses");
      }
   }

   /** Removes every trace of a raid key: markdown, player-raid flag and phase. */
   /** The wave bar: raiders left in the current wave, out of how many it brought. */
   private static final Map<String, ServerBossEvent> waveBars = new HashMap<>();
   private static final Map<String, Integer> waveSize = new HashMap<>();

   private static void updateWaveBar(ServerLevel level, String key, RaidState state) {
      int alive = livingWaveRaiders(level, key);
      int size = Math.max(alive, waveSize.getOrDefault(key, 0));
      waveSize.put(key, size);
      ServerBossEvent bar = waveBars.computeIfAbsent(key, k -> new ServerBossEvent(java.util.UUID.randomUUID(), Component.literal("Raid"), BossBarColor.RED, BossBarOverlay.NOTCHED_10));
      int total = 3 + Math.min(3, state.omenLevel);
      bar.setName(Component.literal("§c§l⚔ RAID §7- Wave §f" + state.wave + "§7/" + total + " §8· §f" + alive + " §7raiders"));
      bar.setProgress(size == 0 ? 0.0F : Math.min(1.0F, alive / (float)size));
      for (ServerPlayer p : level.getPlayers(pl -> pl.distanceToSqr(state.center.getX(), state.center.getY(), state.center.getZ()) < 128.0 * 128.0)) {
         bar.addPlayer(p);
      }
   }

   private static void removeWaveBar(String key) {
      ServerBossEvent bar = waveBars.remove(key);
      waveSize.remove(key);
      if (bar != null) {
         bar.removeAllPlayers();
      }
   }

   private static void cleanupRaid(String key) {
      removeWaveBar(key);
      tracked.remove(key);
      playerRaidKeys.remove(key);
      bossPhases.remove(key);
      removeBossBar(key);
      removeBetrayerBar(key);
   }

   /** Tracks which players participated in the raid (for reward scaling). */
   private static void trackParticipation(ServerLevel level, RaidState state) {
      BlockPos center = state.center;
      for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
         if (p.level().dimension().equals(level.dimension()) && p.distanceToSqr(center.getX(), center.getY(), center.getZ()) < 128.0 * 128.0) {
            state.participation.merge(p.getUUID(), 1, Integer::sum);
         }
      }
   }

   /** Summons vex reinforcements near the Elder Evoker every 20 seconds. */
   private static void summonVexReinforcements(ServerLevel level, String key) {
      UUID evokerId = elderEvokerIds.get(key);
      if (evokerId == null) return;
      net.minecraft.world.entity.Entity ent = level.getEntity(evokerId);
      if (!(ent instanceof Mob evoker) || !evoker.isAlive()) return;
      int vexCount = 3 + RANDOM.nextInt(3); // 3-5 vexes
      for (int i = 0; i < vexCount; i++) {
         net.minecraft.world.entity.monster.Vex vex = (net.minecraft.world.entity.monster.Vex)EntityTypes.VEX.create(level, EntitySpawnReason.COMMAND);
         if (vex == null) continue;
         double a = RANDOM.nextDouble() * Math.PI * 2.0;
         vex.setPos(evoker.getX() + Math.cos(a) * 2.0, evoker.getY() + 1.0, evoker.getZ() + Math.sin(a) * 2.0);
         vex.setLimitedLife(600); // 30 seconds
         vex.setPersistenceRequired();
         level.addFreshEntity(vex);
         com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.PORTAL, vex.getX(), vex.getY(), vex.getZ(), 10, 0.3, 0.5, 0.3, 0.05);
      }
      level.playSound(null, evoker.blockPosition(), SoundEvents.EVOKER_AMBIENT, SoundSource.HOSTILE, 1.0F, 1.0F);
   }

   /** The Raid Illusioner's illusions: fragile hostile clones that actually attack
    *  instead of just mimicking. Three appear around the nearest player and swarm
    *  them for 15 seconds before fading. */
   private static void illusionerSpawnClones(ServerLevel level, String key) {
      UUID ilId = illusionerIds.get(key);
      if (ilId == null) return;
      net.minecraft.world.entity.Entity ent = level.getEntity(ilId);
      if (!(ent instanceof Mob illusioner) || !illusioner.isAlive()) return;
      ServerPlayer target = nearestFighter(level, tracked.get(key));
      if (target == null) return;
      for (int i = 0; i < 3; i++) {
         Vex vex = (Vex)EntityTypes.VEX.create(level, EntitySpawnReason.COMMAND);
         if (vex == null) continue;
         double a = i / 3.0 * Math.PI * 2.0;
         vex.setPos(target.getX() + Math.cos(a) * 2.5, target.getY() + 1.5, target.getZ() + Math.sin(a) * 2.5);
         vex.setCustomName(Component.literal("§9Illusion"));
         vex.setCustomNameVisible(false);
         // Fragile clones - a couple of hits shatters them.
         AttributeInstance hp = vex.getAttribute(Attributes.MAX_HEALTH);
         if (hp != null) hp.setBaseValue(8.0);
         vex.setHealth(vex.getMaxHealth());
         vex.setLimitedLife(300); // 15 seconds
         vex.setPersistenceRequired();
         tagMob(vex, ILLUSION_TAG, key);
         level.addFreshEntity(vex);
         vex.setTarget(target);
         com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.PORTAL, vex.getX(), vex.getY(), vex.getZ(), 10, 0.4, 0.5, 0.4, 0.15);
      }
      level.playSound(null, illusioner.blockPosition(), SoundEvents.EVOKER_CAST_SPELL, SoundSource.HOSTILE, 1.0F, 1.2F);
   }

   // === WARLORD CUSTOM MOVES ===

   /** Warlord ground slam: damages and knocks back everyone inside the ring it wound up. */
   private static final int WAR_RED = 0xFF4A2A;
   private static final int WAR_GOLD = 0xFFD24A;

   private static void warlordGroundSlam(ServerLevel level, Mob warlord, int phase) {
      double r = warlordSlamRadius(phase);
      // The ground breaks: a red shockwave out to the slam's edge, rubble, and war-spires ringing him.
      com.fortuneandfavors.net.FfVfx.shape(level, com.fortuneandfavors.net.FfVfx.FROST_NOVA, ParticleTypes.CRIT, warlord.position(), Vec3.ZERO, r, 0.0, WAR_RED);
      com.fortuneandfavors.net.FfVfx.shape(level, com.fortuneandfavors.net.FfVfx.ROCKBURST, ParticleTypes.CRIT, warlord.position(), Vec3.ZERO, r * 0.6, 0.0, 0x6A4A3A);
      for (int i = 0; i < 8; i++) {
         double a = i * Math.PI / 4.0;
         com.fortuneandfavors.net.FfVfx.shape(level, com.fortuneandfavors.net.FfVfx.ICE_ERUPT, ParticleTypes.CRIT, warlord.position().add(Math.cos(a) * r * 0.7, 0.0, Math.sin(a) * r * 0.7), Vec3.ZERO, 2.0, 0.0, WAR_RED);
      }
      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.SONIC_BOOM, warlord.getX(), warlord.getY() + 0.5, warlord.getZ(), 5, 1.0, 0.5, 1.0, 0.02);
      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.CAMPFIRE_COSY_SMOKE, warlord.getX(), warlord.getY() + 0.2, warlord.getZ(), 12, 2.0, 0.5, 2.0, 0.03);
      level.playSound(null, warlord.blockPosition(), SoundEvents.WARDEN_SONIC_BOOM, SoundSource.HOSTILE, 1.2F, 0.5F);
      AABB slamBox = new AABB(warlord.getX() - r, warlord.getY() - 1, warlord.getZ() - r, warlord.getX() + r, warlord.getY() + 3, warlord.getZ() + r);
      for (ServerPlayer p : level.getEntitiesOfClass(ServerPlayer.class, slamBox)) {
         // The Warlord's own champion is never caught in the slam.
         if (isActiveBetrayer(p.getUUID())) {
            continue;
         }
         p.hurt(warlord.damageSources().mobAttack(warlord), 12.0F);
         Vec3 knock = p.position().subtract(warlord.position()).normalize().scale(1.5);
         p.setDeltaMovement(p.getDeltaMovement().add(knock.x, 0.4, knock.z));
      }
   }

   /** Warlord fire charge: shoots a fireball at the nearest player. */
   private static void warlordFireCharge(ServerLevel level, Mob warlord, RaidState state) {
      ServerPlayer target = nearestFighter(level, state);
      if (target == null) return;
      Vec3 dir = target.position().add(0, target.getBbHeight() / 2, 0).subtract(warlord.position().add(0, 1.5, 0)).normalize();
      net.minecraft.world.entity.item.ItemEntity fireball = new net.minecraft.world.entity.item.ItemEntity(level, warlord.getX(), warlord.getY() + 1.5, warlord.getZ(), new ItemStack(Items.FIRE_CHARGE));
      fireball.setDeltaMovement(dir.scale(1.5));
      fireball.setNoGravity(true);
      level.addFreshEntity(fireball);
      Vec3 from = warlord.position().add(0, 1.5, 0);
      Vec3 to = target.position().add(0, target.getBbHeight() / 2, 0);
      com.fortuneandfavors.net.FfVfx.shape(level, com.fortuneandfavors.net.FfVfx.METEOR, ParticleTypes.FLAME, from, to, 0.0, Math.max(4.0, from.distanceTo(to) / 1.5), WAR_RED);
      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.FLAME, warlord.getX(), warlord.getY() + 1.5, warlord.getZ(), 8, 0.3, 0.3, 0.3, 0.05);
      level.playSound(null, warlord.blockPosition(), SoundEvents.BLAZE_SHOOT, SoundSource.HOSTILE, 1.0F, 0.6F);
   }

   /** Warlord rage VFX when entering low HP. */
   private static void warlordRageVfx(ServerLevel level, Mob warlord) {
      com.fortuneandfavors.net.FfVfx.shape(level, com.fortuneandfavors.net.FfVfx.PILLAR, ParticleTypes.FLAME, warlord.position(), Vec3.ZERO, 8.0, 0.0, WAR_RED);
      com.fortuneandfavors.net.FfVfx.shape(level, com.fortuneandfavors.net.FfVfx.FROST_NOVA, ParticleTypes.FLAME, warlord.position(), Vec3.ZERO, 9.0, 0.0, WAR_RED);
      com.fortuneandfavors.net.FfVfx.follow(level, com.fortuneandfavors.net.FfVfx.ROD_ORBIT, ParticleTypes.FLAME, warlord, 3.0, 200.0, WAR_RED);
      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.ANGRY_VILLAGER, warlord.getX(), warlord.getY() + 2.0, warlord.getZ(), 20, 1.0, 1.0, 1.0, 0.05);
      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.SOUL_FIRE_FLAME, warlord.getX(), warlord.getY() + 1.0, warlord.getZ(), 30, 1.5, 1.0, 1.5, 0.03);
      level.playSound(null, warlord.blockPosition(), SoundEvents.WITHER_DEATH, SoundSource.HOSTILE, 0.8F, 1.2F);
      for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
         if (p.level().dimension().equals(level.dimension())) {
            Chat.raw(p, "§4§l⚔ The Warlord enters a RAGE! §7Speed + Damage increased!");
         }
      }
   }

   // === THE WARLORD'S CHAIN OF COMMAND ===
   //
   // The Warlord used to be a vindicator with a name and three moves on the *level's* clock: the
   // slam fired on every 160th tick of the world's game time, so it landed whenever the metronome
   // said, whether or not the Warlord was in the fight at all - and killing his entire escort
   // changed nothing about him. The fight is a chain of command now: three phases, each announced,
   // each with its own clock, and the only move that decides a room is a slam that is telegraphed
   // long enough to walk out of.

   /** Phase I - the escort still stands and the Warlord lets the line take the hits. */
   public static final int WARLORD_PHASE_LINE = 1;
   /** Phase II - the escort is gone and the Warlord fights for itself. */
   public static final int WARLORD_PHASE_WARCRY = 2;
   /** Phase III - below a third of its health, with nothing left to lose. */
   public static final int WARLORD_PHASE_LAST_STAND = 3;
   /** Health fraction at which the Warlord stops pacing itself. */
   public static final float WARLORD_ENRAGE_AT = 1.0F / 3.0F;

   /**
    * Which phase the Warlord is in, from the two things that decide it.
    *
    * <p>Pure and total, so the rule can be read rather than played: an escort still standing pins
    * him at the line whatever his health, and once the escort is gone his health alone carries him
    * to the last stand. Deliberately ordered that way - a Warlord at one heart with a room of
    * lieutenants around him has not lost anything yet.
    */
   public static int warlordPhase(int escortAlive, float healthFraction) {
      if (escortAlive > 0) {
         return WARLORD_PHASE_LINE;
      }
      return healthFraction <= WARLORD_ENRAGE_AT ? WARLORD_PHASE_LAST_STAND : WARLORD_PHASE_WARCRY;
   }

   /** How long a slam is wound up for, in ticks: the warning a player answers. */
   public static int warlordSlamWindup(int phase) {
      return switch (phase) {
         case WARLORD_PHASE_LAST_STAND -> 20;
         case WARLORD_PHASE_WARCRY -> 30;
         default -> 40;
      };
   }

   /** How long after a slam the next one may be wound up. Never shorter than the wind-up itself. */
   public static int warlordSlamCooldown(int phase) {
      return switch (phase) {
         case WARLORD_PHASE_LAST_STAND -> 80;
         case WARLORD_PHASE_WARCRY -> 100;
         default -> 120;
      };
   }

   /** How far a slam reaches, in blocks. */
   public static double warlordSlamRadius(int phase) {
      return switch (phase) {
         case WARLORD_PHASE_LAST_STAND -> 7.0;
         case WARLORD_PHASE_WARCRY -> 6.0;
         default -> 5.5;
      };
   }

   /**
    * Only the two fighting phases throw fire. The line has an escort for that, and a Warlord who
    * shells a room while his own lieutenants are still standing never has to be approached at all.
    */
   public static boolean warlordUsesFire(int phase) {
      return phase >= WARLORD_PHASE_WARCRY;
   }

   /** How long after a charge the next may be thrown. Zero in the line, which never throws one. */
   public static int warlordChargeCooldown(int phase) {
      return switch (phase) {
         case WARLORD_PHASE_LAST_STAND -> 60;
         case WARLORD_PHASE_WARCRY -> 90;
         default -> 0;
      };
   }

   /**
    * One tick of the Warlord's own clock.
    *
    * <p>The clock lives on the raid rather than on the level's game time, which is the whole
    * difference between a fight and a metronome: the slam is scheduled from the Warlord's previous
    * slam, and is only ever wound up with somebody inside its reach, so an empty field cannot eat
    * the fight's one telegraph.
    */
   private static void warlordTick(ServerLevel level, String key, RaidState state, Mob warlord, int phase, long now) {
      if (phase == WARLORD_PHASE_LINE) {
         warlordHoldTheLine(level, key, warlord, now);
         state.warlordNextSlam = now + warlordSlamCooldown(phase);
         state.warlordNextCharge = now + warlordChargeCooldown(phase);
         return;
      }
      // Mid wind-up: keep painting the ring, then land it.
      if (state.warlordSlamLands >= 0L) {
         if (now < state.warlordSlamLands) {
            warlordSlamTell(level, warlord, phase);
         } else {
            state.warlordSlamLands = -1L;
            warlordGroundSlam(level, warlord, phase);
         }
         return;
      }
      if (state.warlordNextSlam < 0L) {
         state.warlordNextSlam = now + warlordSlamCooldown(phase);
      }
      if (now >= state.warlordNextSlam && warlordHasTargetWithin(level, warlord, warlordSlamRadius(phase))) {
         state.warlordSlamLands = now + warlordSlamWindup(phase);
         state.warlordNextSlam = now + warlordSlamCooldown(phase);
         warlordSlamTell(level, warlord, phase);
         return;
      }
      if (warlordUsesFire(phase)) {
         if (state.warlordNextCharge < 0L) {
            state.warlordNextCharge = now + warlordChargeCooldown(phase);
         }
         if (now >= state.warlordNextCharge) {
            state.warlordNextCharge = now + warlordChargeCooldown(phase);
            warlordFireCharge(level, warlord, state);
         }
      }
      if (phase >= WARLORD_PHASE_LAST_STAND && !state.lastStandCalled) {
         state.lastStandCalled = true;
         warlordCallReinforcements(level, warlord);
      }
   }

   /**
    * The line covers him.
    *
    * <p>While any of his escort stands the Warlord is armoured by it and his escort is hurried
    * along - so a raid cannot be short-cut by ignoring the room and pouring everything into the one
    * big health bar, which is what the old fight was.
    */
   private static void warlordHoldTheLine(ServerLevel level, String key, Mob warlord, long now) {
      if (now % 100L != 0L) {
         return;
      }
      warlord.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 120, 1, false, false, true));
      for (Mob escort : allBossMobs(level, key)) {
         if (escort != null && escort != warlord && escort.isAlive()) {
            escort.addEffect(new MobEffectInstance(MobEffects.SPEED, 120, 0, false, false, true));
         }
      }
   }

   /** The ring on the floor that says where not to be, and the heartbeat under it. */
   private static void warlordSlamTell(ServerLevel level, Mob warlord, int phase) {
      double r = warlordSlamRadius(phase);
      if (level.getGameTime() % 5L == 0L) {
         com.fortuneandfavors.net.FfVfx.shape(level, com.fortuneandfavors.net.FfVfx.RING, ParticleTypes.CRIT, warlord.position().add(0.0, 0.15, 0.0), Vec3.ZERO, r, 0.0, WAR_RED);
      }
      com.fortuneandfavors.net.FfVfx.enter();   // the crit ring below is the vanilla clients' tell
      for (int i = 0; i < 36; i++) {
         double a = i / 36.0 * Math.PI * 2.0;
         com.fortuneandfavors.net.FfVfx.particles(level, 
            ParticleTypes.CRIT,
            warlord.getX() + Math.cos(a) * r,
            warlord.getY() + 0.15,
            warlord.getZ() + Math.sin(a) * r,
            2,
            0.1,
            0.05,
            0.1,
            0.0
         );
      }
      com.fortuneandfavors.net.FfVfx.exit();
      if (level.getGameTime() % 6L == 0L) {
         level.playSound(null, warlord.blockPosition(), SoundEvents.WARDEN_HEARTBEAT, SoundSource.HOSTILE, 1.4F, 0.7F);
      }
   }

   /** Whether anybody the Warlord may hit is inside the ring it is about to slam. */
   private static boolean warlordHasTargetWithin(ServerLevel level, Mob warlord, double radius) {
      for (ServerPlayer p : level.getEntitiesOfClass(ServerPlayer.class, warlord.getBoundingBox().inflate(radius + 1.0))) {
         if (p.isAlive() && !isActiveBetrayer(p.getUUID())) {
            return true;
         }
      }
      return false;
   }

   /** The last stand's reinforcements: two of his own guard, called once. */
   private static void warlordCallReinforcements(ServerLevel level, Mob warlord) {
      for (int i = 0; i < 2; i++) {
         Mob guard = EntityTypes.VINDICATOR.create(level, EntitySpawnReason.EVENT);
         if (guard == null) {
            continue;
         }
         double a = RANDOM.nextDouble() * Math.PI * 2.0;
         double gx = warlord.getX() + Math.cos(a) * 3.0, gz = warlord.getZ() + Math.sin(a) * 3.0;
         guard.setPos(gx, standY(level, gx, gz, warlord.getBlockY()), gz);
         guard.setCustomName(Component.literal("§4The Warlord's Guard"));
         guard.setCustomNameVisible(true);
         guard.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_AXE));
         guard.setDropChance(EquipmentSlot.MAINHAND, 0.0F);
         disjoinFromRaid(guard);
         guard.setPersistenceRequired();
         level.addFreshEntity(guard);
         com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.SOUL_FIRE_FLAME, guard.getX(), guard.getY() + 1, guard.getZ(), 12, 0.3, 0.5, 0.3, 0.05);
      }
      level.playSound(null, warlord.blockPosition(), SoundEvents.RAVAGER_ROAR, SoundSource.HOSTILE, 1.4F, 0.8F);
   }

   /** Announces a phase change and applies it, once per transition. */
   private static void announceWarlordPhase(ServerLevel level, Mob warlord, int phase) {
      if (phase == WARLORD_PHASE_LINE) {
         return;
      }
      if (phase == WARLORD_PHASE_WARCRY) {
         warlord.addEffect(new MobEffectInstance(MobEffects.SPEED, 40 * 20, 1, false, false, true));
         warlord.addEffect(new MobEffectInstance(MobEffects.STRENGTH, 40 * 20, 0, false, false, true));
         level.playSound(null, warlord.blockPosition(), SoundEvents.RAVAGER_ROAR, SoundSource.HOSTILE, 1.6F, 0.7F);
      } else {
         warlord.addEffect(new MobEffectInstance(MobEffects.SPEED, 60 * 20, 2, false, false, true));
         warlord.addEffect(new MobEffectInstance(MobEffects.STRENGTH, 60 * 20, 1, false, false, true));
         level.playSound(null, warlord.blockPosition(), SoundEvents.WITHER_SPAWN, SoundSource.HOSTILE, 1.2F, 0.6F);
         warlordRageVfx(level, warlord);
      }
      for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
         if (p.level().dimension().equals(level.dimension())) {
            Chat.raw(
               p,
               phase == WARLORD_PHASE_WARCRY
                  ? "§c§l⚔ THE WARLORD BARKS AN ORDER!§r §7The escort is gone - he fights for himself now."
                  : "§4§l⚔ THE WARLORD'S LAST STAND!§r §7Nothing left to lose - and he calls his guard."
            );
         }
      }
   }

   // === ILLUSIONER CUSTOM MOVES ===

   /** Illusioner creates an illusion clone near the nearest player. */
   // === NECROMANCER (rare evoker variant, summons wither skeletons, lifesteal) ===
   public static final String NECROMANCER_TAG = "ff_raid_necromancer";
   private static final Map<String, UUID> necromancerIds = new HashMap<>();

   /** Spawns a Necromancer near the warlord - rare variant with wither skeletons. */
   private static void spawnNecromancer(ServerLevel level, String key, RaidState state, BlockPos center, double diffScale, double playerScale) {
      Mob necro = EntityTypes.EVOKER.create(level, EntitySpawnReason.EVENT);
      if (necro == null) return;
      necro.setCustomName(Component.literal("§4§l⚔ Necromancer"));
      necro.setCustomNameVisible(true);
      necro.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.WITHER_SKELETON_SKULL));
      necro.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.STONE_SWORD));
      necro.setDropChance(EquipmentSlot.HEAD, 0.0F);
      necro.setDropChance(EquipmentSlot.MAINHAND, 0.0F);
      // Low HP but deadly
      double a = RANDOM.nextDouble() * Math.PI * 2.0;
      double nx = center.getX() + Math.cos(a) * 6 + 0.5, nz = center.getZ() + Math.sin(a) * 6 + 0.5;
      necro.setPos(nx, standY(level, nx, nz, surfaceY(level, center)), nz);
      applyBossStats(necro, 50.0, diffScale, playerScale);
      disjoinFromRaid(necro);
      tagMob(necro, NECROMANCER_TAG, key);
      necro.setPersistenceRequired();
      level.addFreshEntity(necro);
      necromancerIds.put(key, necro.getUUID());
      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.SOUL_FIRE_FLAME, necro.getX(), necro.getY() + 1, necro.getZ(), 30, 0.5, 1.0, 0.5, 0.1);
      level.playSound(null, necro.blockPosition(), SoundEvents.EVOKER_AMBIENT, SoundSource.HOSTILE, 1.0F, 0.6F);
   }

   /** Necromancer summons 2 wither skeletons every 12 seconds. */
   private static void necromancerSummonWitherSkeletons(ServerLevel level, String key) {
      UUID necroId = necromancerIds.get(key);
      if (necroId == null) return;
      net.minecraft.world.entity.Entity ent = level.getEntity(necroId);
      if (!(ent instanceof Mob necro) || !necro.isAlive()) return;
      for (int i = 0; i < 2; i++) {
         Mob ws = EntityTypes.WITHER_SKELETON.create(level, EntitySpawnReason.COMMAND);
         if (ws == null) continue;
         double angle = RANDOM.nextDouble() * Math.PI * 2.0;
         double wx = necro.getX() + Math.cos(angle) * 2.0, wz = necro.getZ() + Math.sin(angle) * 2.0;
         ws.setPos(wx, standY(level, wx, wz, necro.getBlockY()), wz);
         ws.setCustomName(Component.literal("§4§l⚔ Undead"));
         ws.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.STONE_SWORD));
         ws.setPersistenceRequired();
         level.addFreshEntity(ws);
         com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.SOUL_FIRE_FLAME, ws.getX(), ws.getY(), ws.getZ(), 8, 0.3, 0.5, 0.3, 0.05);
      }
      level.playSound(null, necro.blockPosition(), SoundEvents.WITHER_SKELETON_AMBIENT, SoundSource.HOSTILE, 1.0F, 0.8F);
   }

   /** Necromancer lifesteal: heals when nearby players take wither damage. */
   private static void necromancerLifesteal(ServerLevel level, String key) {
      UUID necroId = necromancerIds.get(key);
      if (necroId == null) return;
      net.minecraft.world.entity.Entity ent = level.getEntity(necroId);
      if (!(ent instanceof Mob necro) || !necro.isAlive()) return;
      // Heal 5 HP every tick
      necro.heal(5.0F);
      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.HAPPY_VILLAGER, necro.getX(), necro.getY() + 1.5, necro.getZ(), 5, 0.3, 0.3, 0.3, 0.02);
   }

   // === CAPTAIN CUSTOM MOVES ===

   // === CAPTAIN CUSTOM MOVES ===

   /** Captain fires a volley of arrows at the nearest player. */
   private static void captainRapidFire(ServerLevel level, Mob captain, RaidState state) {
      ServerPlayer target = nearestFighter(level, state);
      if (target == null) return;
      Vec3 aim = target.position().add(0, target.getBbHeight() / 2, 0).subtract(captain.position().add(0, 1.5, 0)).normalize();
      for (int i = 0; i < 3; i++) {
         double spread = (RANDOM.nextDouble() - 0.5) * 0.2;
         Vec3 dir = new Vec3(aim.x + spread, aim.y + spread, aim.z + spread).normalize();
         net.minecraft.world.entity.item.ItemEntity arrow = new net.minecraft.world.entity.item.ItemEntity(level, captain.getX(), captain.getY() + 1.5, captain.getZ(), new ItemStack(Items.ARROW));
         arrow.setDeltaMovement(dir.scale(2.0));
         arrow.setNoGravity(true);
         level.addFreshEntity(arrow);
      }
      level.playSound(null, captain.blockPosition(), SoundEvents.CROSSBOW_SHOOT, SoundSource.HOSTILE, 1.0F, 0.8F);
   }

   /** Creates and updates the boss bar showing combined HP of all raid bosses. */
   private static void updateBossBar(ServerLevel level, String key, RaidState state) {
      ServerBossEvent bar = bossBars.get(key);
      if (bar == null) return;

      double totalMax = 0;
      double totalCurrent = 0;
      int aliveCount = 0;

      Mob warlord = warlordAlive(level, key);
      if (warlord != null) {
         totalMax += warlord.getMaxHealth();
         totalCurrent += warlord.getHealth();
         aliveCount++;
      }
      for (Mob lt : livingLieutenants(level, key)) {
         totalMax += lt.getMaxHealth();
         totalCurrent += lt.getHealth();
         aliveCount++;
      }
      Mob elderEvoker = mobAlive(level, elderEvokerIds, key);
      if (elderEvoker != null) {
         totalMax += elderEvoker.getMaxHealth();
         totalCurrent += elderEvoker.getHealth();
         aliveCount++;
      }
      Mob illusioner = mobAlive(level, illusionerIds, key);
      if (illusioner != null) {
         totalMax += illusioner.getMaxHealth();
         totalCurrent += illusioner.getHealth();
         aliveCount++;
      }
      Mob captain = mobAlive(level, captainIds, key);
      if (captain != null) {
         totalMax += captain.getMaxHealth();
         totalCurrent += captain.getHealth();
         aliveCount++;
      }
      Mob necro = mobAlive(level, necromancerIds, key);
      if (necro != null) {
         totalMax += necro.getMaxHealth();
         totalCurrent += necro.getHealth();
         aliveCount++;
      }

      if (totalMax > 0) {
         float progress = (float)(totalCurrent / totalMax);
         bar.setProgress(Math.max(0.0F, Math.min(1.0F, progress)));
      } else {
         bar.setProgress(0.0F);
      }

      // Update title to show remaining bosses
      bar.setName(Component.literal("§c§l⚔ Raid Bosses §7(" + aliveCount + " remaining)"));
   }

   private static void removeBossBar(String key) {
      ServerBossEvent bar = bossBars.remove(key);
      if (bar != null) {
         bar.removeAllPlayers();
      }
   }

   // === SECOND PHASE: THE WARLORD'S OFFER ===

   /** The Warlord "brings away" one random nearby player for a private offer.
    *  Only triggers once, in multiplayer, when the Warlord is below 40% HP. */
   private static void triggerBetrayal(ServerLevel level, String key, RaidState state) {
      List<ServerPlayer> candidates = new ArrayList<>();
      for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
         if (p.level().dimension().equals(level.dimension())
               && p.distanceToSqr(state.center.getX(), state.center.getY(), state.center.getZ()) < 128.0 * 128.0) {
            candidates.add(p);
         }
      }
      if (candidates.isEmpty()) {
         return;
      }
      ServerPlayer chosen = candidates.get(RANDOM.nextInt(candidates.size()));
      state.betrayerId = chosen.getUUID();

      // "Brings them away" - yank them to the edge of the fight with heavy VFX.
      double a = RANDOM.nextDouble() * Math.PI * 2.0;
      double r = 16.0 + RANDOM.nextDouble() * 4.0;
      int tx = state.center.getX() + (int)Math.round(Math.cos(a) * r);
      int tz = state.center.getZ() + (int)Math.round(Math.sin(a) * r);
      int ty = level.getHeight(Types.MOTION_BLOCKING, tx, tz);
      chosen.teleportTo(tx + 0.5, ty + 1.0, tz + 0.5);
      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.SOUL_FIRE_FLAME, chosen.getX(), chosen.getY() + 1.0, chosen.getZ(), 40, 1.2, 1.0, 1.2, 0.08);
      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.REVERSE_PORTAL, chosen.getX(), chosen.getY() + 1.0, chosen.getZ(), 30, 1.0, 1.2, 1.0, 0.2);
      level.playSound(null, chosen.blockPosition(), SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE, 1.2F, 0.7F);
      level.playSound(null, chosen.blockPosition(), SoundEvents.RAID_HORN.value(), SoundSource.HOSTILE, 1.2F, 0.6F);

      for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
         if (p.level().dimension().equals(level.dimension())) {
            Chat.raw(p, "§8§m═══════════════════════════════§r");
            Chat.raw(p, "  §c§l⚔ THE WARLORD MAKES HIS MOVE ⚔");
            Chat.raw(p, "  §7He seizes §f" + chosen.getName().getString() + "§7 and drags them away for a §cprivate audience§7...");
            Chat.raw(p, "  §7§o\"There is always a price for loyalty...\"§r §7- the Warlord's voice booms.");
            Chat.raw(p, "§8§m═══════════════════════════════§r");
         }
      }
      Chat.raw(chosen, "");
      Chat.raw(chosen, "§c§l⚔ THE WARLORD SPEAKS TO YOU ALONE ⚔");
      Chat.raw(chosen, "§7§o\"You fight well, mortal. Join me - and I will spare you.\"");
      Chat.raw(chosen, "§7§o\"Your friends will call you traitor. Let them. Power remembers no names.\"");
      Chat.raw(chosen, "§7Choose your fate...");
      Chat.raw(chosen, "");
      BetrayalMenu.open(chosen);
   }

   /** True if the player is currently joined to the Warlord in an active raid. */
   public static boolean isActiveBetrayer(UUID uuid) {
      if (uuid == null) {
         return false;
      }
      for (RaidState st : tracked.values()) {
         if (st != null && !st.settled && st.betrayJoined && uuid.equals(st.betrayerId)) {
            return true;
         }
      }
      return false;
   }

   /** The raid center of the betrayal offer still pending for this player, or
    *  null if there is none (settled, already decided, or no offer was made). */
   public static BlockPos pendingBetrayalCenter(ServerPlayer player) {
      for (Entry<String, RaidState> e : tracked.entrySet()) {
         RaidState st = e.getValue();
         if (st != null && !st.settled && st.betrayerId != null && st.betrayerId.equals(player.getUUID())
               && !st.betrayJoined && !st.betrayDenied) {
            return st.center;
         }
      }
      return null;
   }

   /** True if the mob belongs to a player raid: a tagged boss/wave raider, or a
    *  raid support mob (vexes, etc.) near an active raid center. Friendly summons
    *  (horn raiders, spellbook vexes) are never raid mobs. */
   public static boolean isRaidMob(Mob mob) {
      if (mob == null || tracked.isEmpty()) {
         return false;
      }
      try {
         CustomData cd = (CustomData)mob.get(DataComponents.CUSTOM_DATA);
         if (cd != null) {
            CompoundTag tag = cd.copyTag();
            if (tag.contains("ff_friendly_owner") || tag.contains("ff_mind_dom")) {
               return false;
            }
            if (tag.contains(WAVE_TAG) || tag.contains(WARLORD_TAG) || tag.contains(ELDER_EVOKER_TAG)
                  || tag.contains(ILLUSIONER_TAG) || tag.contains(CAPTAIN_TAG)
                  || tag.contains(LIEUTENANT_TAG) || tag.contains(NECROMANCER_TAG)) {
               return true;
            }
         }
      } catch (Exception ignored) {
      }
      // Untagged support mobs (e.g. vexes summoned by the Elder Evoker): only
      // count them if they are close to an active raid center.
      if (mob.level() instanceof ServerLevel sl) {
         for (RaidState st : tracked.values()) {
            if (st == null || st.settled || !st.dimension.equals(sl.dimension())) {
               continue;
            }
            if (mob.distanceToSqr(st.center.getX(), st.center.getY(), st.center.getZ()) < 128.0 * 128.0) {
               return true;
            }
         }
      }
      return false;
   }

   /** Test command: simulates the Warlord's private offer so an admin can run
    *  through the whole betrayal flow with /ff test betrayal. */
   public static void testBetrayal(ServerPlayer player) {
      try {
         ServerLevel level = player.level() instanceof ServerLevel sl ? sl : null;
         if (level == null) {
            return;
         }
         String key = "test#" + player.getUUID();
         RaidState st = tracked.get(key);
         if (st == null) {
            st = new RaidState();
            st.dimension = level.dimension();
            st.center = player.blockPosition();
            tracked.put(key, st);
         }
         // Reset any previous test so it can be re-run.
         st.settled = false;
         st.betrayJoined = false;
         st.betrayDenied = false;
         st.betrayalTriggered = true;
         st.betrayerId = player.getUUID();
         BetrayalMenu.open(player);
      } catch (Exception ignored) {
      }
   }

   /** Handles the betrayer's choice from the BetrayalMenu. Returns an error, or
    *  null on success. */
   public static String onBetrayalChoice(ServerPlayer player, boolean accept) {
      for (Entry<String, RaidState> e : tracked.entrySet()) {
         RaidState st = e.getValue();
         if (st == null || st.settled || st.betrayerId == null || !st.betrayerId.equals(player.getUUID())
               || st.betrayJoined || st.betrayDenied) {
            continue;
         }
         String key = e.getKey();
         ServerLevel level = player.level() instanceof ServerLevel sl ? sl : null;
         if (accept) {
            st.betrayJoined = true;
            if (level != null) {
               addBetrayerBar(level, key, st, player);
               // Raiders may still be targeting the betrayer from before the
               // oath - drop every raid mob's target so the truce is real.
               for (Mob b : allBossMobs(level, key)) {
                  if (b != null && b.getTarget() != null && b.getTarget().getUUID().equals(player.getUUID())) {
                     b.setTarget(null);
                  }
               }
               for (Raider raider : level.getEntitiesOfClass(Raider.class, boxAround(st.center, 64, 16))) {
                  if (isTagged(raider, WAVE_TAG, key) && raider.getTarget() != null
                        && raider.getTarget().getUUID().equals(player.getUUID())) {
                     raider.setTarget(null);
                  }
               }
               // The betrayer can see everyone - all players glow for the rest of the fight
               for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
                  if (p.level().dimension().equals(level.dimension())) {
                     p.setGlowingTag(true);
                  }
               }
               com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.ANGRY_VILLAGER, player.getX(), player.getY() + 2.0, player.getZ(), 30, 1.0, 1.0, 1.0, 0.06);
               for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
                  if (p.level().dimension().equals(level.dimension())) {
                     Chat.raw(p, "§c§l⚠ " + player.getName().getString() + " HAS JOINED THE WARLORD! §7They now fight against you - anyone can strike them down to end the raid!");
                  }
               }
            }
            TagManager.addOwned(player.getUUID(), "§cUntrustable");
            TitleManager.unlock(player, "Untrustable");
            Advancements.grant(player, "raid_betray");
            if (level != null) {
               // Everyone participating in the raid earns the Betrayal achievement -
               // EXCEPT the betrayer themselves. The betrayer already got
               // "Warlord's Betrayer"; the "Betrayal" one is for the friends who
               // were turned on.
               for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
                  if (p.level().dimension().equals(level.dimension())
                        && !p.getUUID().equals(player.getUUID())
                        && p.distanceToSqr(st.center.getX(), st.center.getY(), st.center.getZ()) < 128.0 * 128.0) {
                     Advancements.grant(p, "raid_betrayal");
                  }
               }
               for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
                  if (p.level().dimension().equals(level.dimension())) {
                     Chat.raw(p, "§7§oThe Warlord laughs, a sound like grinding stone: §f\"Kneel, little wolf. The pack has no use for you now.\"");
                  }
               }
            }
            Chat.raw(player, "§c§lYou kneel before the Warlord. §7Your health now shows among the boss bars - the raid only ends when YOU fall.");
         } else {
            st.betrayDenied = true;
            if (level != null) {
               Mob warlord = warlordAlive(level, key);
               if (warlord != null && warlord.isAlive()) {
                  // The Warlord is furious: deals noticeably more damage now
                  AttributeInstance atk = warlord.getAttribute(Attributes.ATTACK_DAMAGE);
                  if (atk != null) {
                     atk.setBaseValue(atk.getBaseValue() * 1.3);
                  }
                  warlord.addEffect(new MobEffectInstance(MobEffects.STRENGTH, 400, 1, false, false, true));
                  com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.ANGRY_VILLAGER, warlord.getX(), warlord.getY() + 2.2, warlord.getZ(), 40, 1.2, 1.2, 1.2, 0.08);
                  com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.SOUL_FIRE_FLAME, warlord.getX(), warlord.getY() + 1.0, warlord.getZ(), 40, 1.5, 1.2, 1.5, 0.05);
                  level.playSound(null, warlord.blockPosition(), SoundEvents.WITHER_DEATH, SoundSource.HOSTILE, 0.9F, 1.1F);
               }
               for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
                  if (p.level().dimension().equals(level.dimension())) {
                     Chat.raw(p, "§4§l⚔ " + player.getName().getString() + " DENIED the Warlord! §7He is furious - his blows hit harder now!");
                     Chat.raw(p, "§7§oThe Warlord snarls: §f\"Then you will die with them, defiant fool!\"");
                  }
               }
            }
            TagManager.addOwned(player.getUUID(), "§aWarlord's Defier");
            Advancements.grant(player, "raid_stand_firm");
            Chat.raw(player, "§aYou spit in the Warlord's face and keep fighting! §7He deals a bit more damage now - stay sharp.");
         }
         return null;
      }
      return "That offer has already expired.";
   }

   /** Called when any player dies - if they were the betrayer, the raid becomes
    *  winnable again. */
   public static void onPlayerDeath(ServerPlayer player) {
      settleBetrayerGone(player, true);
   }

   /** Called when any player disconnects - a betrayer who logged off mid-raid
    *  would otherwise leave the raid unwinnable (the victory only settles when
    *  the betrayer dies). Treat an abandoned oath exactly like a fallen
    *  champion: drop the betrayer's bar and make the raid winnable again. */
   public static void onPlayerLogout(ServerPlayer player) {
      settleBetrayerGone(player, false);
   }

   private static void settleBetrayerGone(ServerPlayer player, boolean died) {
      for (Entry<String, RaidState> e : tracked.entrySet()) {
         RaidState st = e.getValue();
         if (st == null || st.settled || !st.betrayJoined || st.betrayerId == null
               || !st.betrayerId.equals(player.getUUID())) {
            continue;
         }
         String key = e.getKey();
         ServerLevel level = player.level() instanceof ServerLevel sl ? sl : null;
         if (level != null) {
            removeBetrayerBar(key);
            for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
               if (p.level().dimension().equals(level.dimension())) {
                  p.setGlowingTag(false);
                  if (died) {
                     Chat.raw(p, "§a§l✔ The betrayer §f" + player.getName().getString() + " §a§lhas fallen! §7The raid can finally end.");
                     Chat.raw(p, "§7§oThe Warlord roars in fury: §f\"A broken oath, and a broken champion!\"");
                  } else {
                     Chat.raw(p, "§e§l⚠ The betrayer §f" + player.getName().getString() + " §e§labandoned the Warlord! §7The raid can finally end.");
                     Chat.raw(p, "§7§oThe Warlord snarls: §f\"A coward's oath is worth less than dust!\"");
                  }
               }
            }
            if (died) {
               com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.TOTEM_OF_UNDYING, player.getX(), player.getY() + 1.0, player.getZ(), 30, 1.0, 1.0, 1.0, 0.1);
            }
         }
         st.betrayJoined = false;
         break;
      }
   }

   private static void addBetrayerBar(ServerLevel level, String key, RaidState state, ServerPlayer betrayer) {
      removeBetrayerBar(key);
      ServerBossEvent bar = new ServerBossEvent(UUID.randomUUID(),
         Component.literal("§c" + betrayer.getName().getString() + " §7(§4Warlord's Champion§7)"),
         BossBarColor.RED, BossBarOverlay.PROGRESS);
      bar.setVisible(true);
      bar.setProgress(1.0F);
      for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
         if (p.level().dimension().equals(level.dimension())) {
            bar.addPlayer(p);
         }
      }
      betrayerBars.put(key, bar);
   }

   private static void updateBetrayerBar(ServerLevel level, String key, RaidState state) {
      if (!state.betrayJoined || state.betrayerId == null) {
         removeBetrayerBar(key);
         return;
      }
      ServerPlayer betrayer = level.getServer().getPlayerList().getPlayer(state.betrayerId);
      ServerBossEvent bar = betrayerBars.get(key);
      if (betrayer == null || !betrayer.isAlive()) {
         removeBetrayerBar(key);
         return;
      }
      if (bar == null) {
         addBetrayerBar(level, key, state, betrayer);
         bar = betrayerBars.get(key);
      }
      if (bar != null) {
         float hp = betrayer.getMaxHealth() > 0.0F ? betrayer.getHealth() / betrayer.getMaxHealth() : 0.0F;
         bar.setProgress(Math.max(0.0F, Math.min(1.0F, hp)));
      }
      // If every boss is already dead, remind everyone what's left to slay.
      if (warlordAlive(level, key) == null && livingLieutenants(level, key).isEmpty()
            && mobAlive(level, elderEvokerIds, key) == null && mobAlive(level, illusionerIds, key) == null
            && mobAlive(level, captainIds, key) == null && mobAlive(level, necromancerIds, key) == null
            && level.getGameTime() % 200L == 0L) {
         for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
            if (p.level().dimension().equals(level.dimension())) {
               Chat.raw(p, "§c⚠ The Warlord is dead, but §f" + betrayer.getName().getString() + "§c still fights for him! Slay the betrayer to end the raid!");
            }
         }
      }
   }

   private static void removeBetrayerBar(String key) {
      ServerBossEvent bar = betrayerBars.remove(key);
      if (bar != null) {
         bar.removeAllPlayers();
      }
   }

   /** Stops everyone in the dimension from glowing (betrayer highlight cleanup). */
   private static void clearRaidGlows(ServerLevel level) {
      for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
         if (p.level().dimension().equals(level.dimension())) {
            p.setGlowingTag(false);
         }
      }
   }

   /** All living raid boss mobs for a key (used to keep them off the betrayer). */
   private static List<Mob> allBossMobs(ServerLevel level, String key) {
      List<Mob> mobs = new ArrayList<>();
      Mob w = warlordAlive(level, key);
      if (w != null) mobs.add(w);
      mobs.addAll(livingLieutenants(level, key));
      Mob ee = mobAlive(level, elderEvokerIds, key);
      if (ee != null) mobs.add(ee);
      Mob il = mobAlive(level, illusionerIds, key);
      if (il != null) mobs.add(il);
      Mob cap = mobAlive(level, captainIds, key);
      if (cap != null) mobs.add(cap);
      Mob necro = mobAlive(level, necromancerIds, key);
      if (necro != null) mobs.add(necro);
      return mobs;
   }

   /** Called from the death handler - resolves raid bosses on death. */
   public static void onEntityDeath(LivingEntity entity, ServerPlayer killer) {
      try {
         if (!(entity instanceof Mob mob)) {
            return;
         }
         CustomData cd = (CustomData)mob.get(DataComponents.CUSTOM_DATA);
         if (cd == null) {
            return;
         }
         CompoundTag tag = cd.copyTag();
         if (tag.contains(BOUNTY_TAG) && entity.level() instanceof ServerLevel bl) {
            int emeralds = 8 + RANDOM.nextInt(9);
            mob.spawnAtLocation(bl, new ItemStack(Items.EMERALD, emeralds));
            net.minecraft.world.entity.ExperienceOrb.award(bl, mob.position(), 40);
            com.fortuneandfavors.net.FfVfx.shape(bl, com.fortuneandfavors.net.FfVfx.FROST_NOVA, ParticleTypes.TOTEM_OF_UNDYING, mob.position(), Vec3.ZERO, 5.0, 0.0, WAR_GOLD);
            com.fortuneandfavors.net.FfVfx.shape(bl, com.fortuneandfavors.net.FfVfx.ICE_BURST, ParticleTypes.TOTEM_OF_UNDYING, mob.position().add(0.0, 1.0, 0.0), Vec3.ZERO, 1.2, 0.0, WAR_GOLD);
            bl.playSound(null, mob.blockPosition(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 1.0F, 1.4F);
            broadcastKillFeed(bl, "§6☠ Bounty §7claimed by §f" + (killer != null ? killer.getName().getString() : "the field") + " §7- §a" + emeralds + " emeralds");
         }
         if (tag.contains(WARLORD_TAG)) {
            String key = tag.getString(WARLORD_TAG).orElse("");
            RaidState state = tracked.get(key);
            if (state != null && !state.settled) {
               state.warlordKiller = killer;
               warlordDeathVfx((ServerLevel)entity.level(), entity.blockPosition());
               String killerName = killer != null ? killer.getName().getString() : "Unknown";
               state.bossKillers.put("§c§lRaid Warlord", killerName);
               checkAllFiveBosses((ServerLevel)entity.level(), state, killer);
               broadcastKillFeed((ServerLevel)entity.level(), "§c§l⚔ Raid Warlord §7slain by §f" + killerName + "§7!");
               // And the board's job for an abandoned raid is finished by the same blow that
               // finished the fight - not left advertising a Warlord who is already down.
               DynamicContractsManager.onRaidWarlordSlain(entity.level().getServer(), key, killer);
               if (killer != null) {
               // Warlord drops 3 Raid Loot Boxes + Raid Banner + Trophy + Upgrader.
               // Boxes go through the collect-aware path: a raid is the one fight in
               // the mod where a dozen things are on the floor at once, and a box
               // dropped by a full inventory is a box nobody ever picks up.
               for (int i = 0; i < 3; i++) {
                  BossPayout.giveOrCollect(killer, ModItems.raidLootBox());
               }
               InventoryHelper.giveOrDrop(killer, ModItems.raidBanner());
                  InventoryHelper.giveOrDrop(killer, ModItems.warlordTrophy());
                  InventoryHelper.giveOrDrop(killer, ModItems.raidersItemUpgrader());
                  // The Warlord always drops a rune (a mix of the five types),
                  // matching the other raid bosses.
                  InventoryHelper.giveOrDrop(killer, com.fortuneandfavors.economy.RuneManager.randomRune());
                  InventoryHelper.giveOrDrop(killer, com.fortuneandfavors.economy.RuneManager.randomRune());
                  Chat.raw(killer, "§6§lThe Warlord drops §f3 Raid Loot Boxes§6§l + a Raid Banner + §eWarlord's Trophy§6§l + §6Raiders Item Upgrader§6§l + §d2 Runes§6§l!");
               }
               warlordIds.remove(key);
            }
            return;
         }
         if (tag.contains(ELDER_EVOKER_TAG)) {
            String key = tag.getString(ELDER_EVOKER_TAG).orElse("");
            RaidState state = tracked.get(key);
            if (state != null && !state.settled) {
               lieutenantDeathVfx((ServerLevel)entity.level(), entity.blockPosition());
               String killerName = killer != null ? killer.getName().getString() : "Unknown";
               state.bossKillers.put("§5§lElder Evoker", killerName);
               checkAllFiveBosses((ServerLevel)entity.level(), state, killer);
               broadcastKillFeed((ServerLevel)entity.level(), "§5§l⚔ Elder Evoker §7slain by §f" + killerName + "§7!");
               if (killer != null) {
                  // Evoker drops 2 Raid Loot Boxes (legendaries come from loot boxes)
                  BossPayout.giveOrCollect(killer, ModItems.raidLootBox());
                  BossPayout.giveOrCollect(killer, ModItems.raidLootBox());
                  InventoryHelper.giveOrDrop(killer, com.fortuneandfavors.economy.RuneManager.randomRune());
                  Chat.raw(killer, "§5§lThe Elder Evoker drops §f2 Raid Loot Boxes + §d1 rune§5§l!");
               }
               elderEvokerIds.remove(key);
            }
            return;
         }
         if (tag.contains(ILLUSIONER_TAG)) {
            String key = tag.getString(ILLUSIONER_TAG).orElse("");
            RaidState state = tracked.get(key);
            if (state != null && !state.settled) {
               lieutenantDeathVfx((ServerLevel)entity.level(), entity.blockPosition());
               String killerName = killer != null ? killer.getName().getString() : "Unknown";
               state.bossKillers.put("§9§lRaid Illusioner", killerName);
               checkAllFiveBosses((ServerLevel)entity.level(), state, killer);
               broadcastKillFeed((ServerLevel)entity.level(), "§9§l⚔ Raid Illusioner §7slain by §f" + killerName + "§7!");
               if (killer != null) {
                  // Illusioner drops 2 Raid Loot Boxes (legendaries come from loot boxes)
                  BossPayout.giveOrCollect(killer, ModItems.raidLootBox());
                  BossPayout.giveOrCollect(killer, ModItems.raidLootBox());
                  InventoryHelper.giveOrDrop(killer, com.fortuneandfavors.economy.RuneManager.randomRune());
                  Chat.raw(killer, "§9§lThe Illusioner drops §f2 Raid Loot Boxes + §d1 rune§9§l!");
               }
               illusionerIds.remove(key);
            }
            return;
         }
         if (tag.contains(CAPTAIN_TAG)) {
            String key = tag.getString(CAPTAIN_TAG).orElse("");
            RaidState state = tracked.get(key);
            if (state != null && !state.settled) {
               lieutenantDeathVfx((ServerLevel)entity.level(), entity.blockPosition());
               String killerName = killer != null ? killer.getName().getString() : "Unknown";
               state.bossKillers.put("§6§lRaid Captain", killerName);
               checkAllFiveBosses((ServerLevel)entity.level(), state, killer);
               broadcastKillFeed((ServerLevel)entity.level(), "§6§l⚔ Raid Captain §7slain by §f" + killerName + "§7!");
               // Drop special crossbow
               if (killer != null) {
                  ItemStack specialCrossbow = createCaptainCrossbow((ServerLevel) entity.level(), killer);
                  InventoryHelper.giveOrDrop(killer, specialCrossbow);
                  Chat.raw(killer, "§6The Raid Captain drops a §6§lRaid Crossbow§7!");
               }
               captainIds.remove(key);
            }
            return;
         }
         if (tag.contains(LIEUTENANT_TAG)) {
            String key = tag.getString(LIEUTENANT_TAG).orElse("");
            RaidState state = tracked.get(key);
            if (state != null && !state.settled) {
               lieutenantDeathVfx((ServerLevel)entity.level(), entity.blockPosition());
               List<UUID> ids = lieutenantIds.get(key);
               if (ids != null) {
                  ids.remove(entity.getUUID());
               }
            }
         }
         if (tag.contains(NECROMANCER_TAG)) {
            String key = tag.getString(NECROMANCER_TAG).orElse("");
            RaidState state = tracked.get(key);
            if (state != null && !state.settled) {
               lieutenantDeathVfx((ServerLevel)entity.level(), entity.blockPosition());
               String killerName = killer != null ? killer.getName().getString() : "Unknown";
               state.bossKillers.put("§4§lNecromancer", killerName);
               checkAllFiveBosses((ServerLevel)entity.level(), state, killer);
               broadcastKillFeed((ServerLevel)entity.level(), "§4§l⚔ Necromancer §7slain by §f" + killerName + "§7!");
               necromancerIds.remove(key);
            }
         }
      } catch (Exception ignored) {
      }
   }

   /** Creates the special Raid Crossbow with unique enchantments. */
   private static ItemStack createCaptainCrossbow(ServerLevel level, ServerPlayer player) {
      ItemStack crossbow = new ItemStack(Items.CROSSBOW);
      Registry<Enchantment> enchants = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
      try {
         crossbow.enchant(enchants.getOrThrow(Enchantments.QUICK_CHARGE), 3);
         crossbow.enchant(enchants.getOrThrow(Enchantments.MULTISHOT), 1);
         crossbow.enchant(enchants.getOrThrow(Enchantments.UNBREAKING), 3);
         crossbow.enchant(enchants.getOrThrow(Enchantments.MENDING), 1);
      } catch (Exception ignored) {
      }
      crossbow.set(DataComponents.CUSTOM_NAME, Component.literal("§6§l⚔ Raid Crossbow"));
      crossbow.set(DataComponents.LORE, new net.minecraft.world.item.component.ItemLore(java.util.List.of(
         Component.literal("§7Quick Charge III · Multishot · Unbreaking III · Mending"),
         Component.literal("§8Dropped by the Raid Captain")
      )));
      return crossbow;
   }

   // --- Helpers for checking if a mob is alive ---

   private static Mob mobAlive(ServerLevel level, Map<String, UUID> idMap, String key) {
      UUID id = idMap.get(key);
      if (id == null) return null;
      net.minecraft.world.entity.Entity e = level.getEntity(id);
      if (e instanceof Mob mob && mob.isAlive()) {
         return mob;
      }
      idMap.remove(key);
      return null;
   }

   private static Mob warlordAlive(ServerLevel level, String key) {
      return mobAlive(level, warlordIds, key);
   }

   private static List<Mob> livingLieutenants(ServerLevel level, String key) {
      List<Mob> alive = new ArrayList<>();
      List<UUID> ids = lieutenantIds.get(key);
      if (ids == null) {
         return alive;
      }
      ids.removeIf(uuid -> {
         net.minecraft.world.entity.Entity e = level.getEntity(uuid);
         if (e instanceof Mob mob && mob.isAlive()) {
            alive.add(mob);
            return false;
         }
         return true;
      });
      return alive;
   }

   // --- Boss spawning ---

   /** Spawns all five raid bosses: Warlord, Elder Evoker, Illusioner, Captain and
    *  an Evoker lieutenant. HP scales with the Bad Omen level that started the raid
    *  AND with how many players are near the village, so a solo fight is fair while
    *  a big party gets a real challenge. The bosses are deliberately un-joined from
    *  the vanilla raid so its cleanup can never wipe them out mid-fight. */
   private static void spawnRaidBosses(ServerLevel level, String key, RaidState state) {
      BlockPos center = state.center;
      // Bosses always spawn on the surface, never in caves.
      int y = surfaceY(level, center);
      int nearby = nearbyPlayers(level, center);
      double playerScale = dynamicPlayerScale(level, center);
      double diffScale = 1.0 + 0.25 * raidDifficulty;

      // === WARLORD ===
      Mob warlord = EntityTypes.VINDICATOR.create(level, EntitySpawnReason.EVENT);
      if (warlord != null) {
         Registry<Enchantment> ench = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
         warlord.setCustomName(Component.literal("§c§l⚔ §4§lRAID WARLORD §c§l⚔"));
         warlord.setCustomNameVisible(true);
         // Scale up the warlord - make him visually larger
         warlord.setPos(center.getX() + 0.5, standY(level, center.getX() + 0.5, center.getZ() + 0.5, y), center.getZ() + 0.5);
         // HP nerfed (300 -> 210): the Warlord is a raid finale, not a raid wall.
         applyBossStats(warlord, 210.0, diffScale, playerScale);
         // Custom enchanted netherite gear
         ItemStack wlHelm = new ItemStack(Items.NETHERITE_HELMET);
         ItemStack wlChest = new ItemStack(Items.NETHERITE_CHESTPLATE);
         ItemStack wlLegs = new ItemStack(Items.NETHERITE_LEGGINGS);
         ItemStack wlFeet = new ItemStack(Items.NETHERITE_BOOTS);
         ItemStack wlAxe = new ItemStack(Items.NETHERITE_AXE);
         try {
            wlHelm.enchant(ench.getOrThrow(Enchantments.PROTECTION), 4);
            wlChest.enchant(ench.getOrThrow(Enchantments.PROTECTION), 4);
            wlLegs.enchant(ench.getOrThrow(Enchantments.PROTECTION), 4);
            wlFeet.enchant(ench.getOrThrow(Enchantments.PROTECTION), 4);
            wlAxe.enchant(ench.getOrThrow(Enchantments.SHARPNESS), 5);
            wlAxe.enchant(ench.getOrThrow(Enchantments.FIRE_ASPECT), 2);
            wlAxe.enchant(ench.getOrThrow(Enchantments.KNOCKBACK), 1);
         } catch (Exception ignored) {}
         warlord.setItemSlot(EquipmentSlot.HEAD, wlHelm);
         warlord.setItemSlot(EquipmentSlot.CHEST, wlChest);
         warlord.setItemSlot(EquipmentSlot.LEGS, wlLegs);
         warlord.setItemSlot(EquipmentSlot.FEET, wlFeet);
         warlord.setItemSlot(EquipmentSlot.MAINHAND, wlAxe);
         warlord.setDropChance(EquipmentSlot.HEAD, 0.0F);
         warlord.setDropChance(EquipmentSlot.CHEST, 0.0F);
         warlord.setDropChance(EquipmentSlot.LEGS, 0.0F);
         warlord.setDropChance(EquipmentSlot.FEET, 0.0F);
         warlord.setDropChance(EquipmentSlot.MAINHAND, 0.0F);
         disjoinFromRaid(warlord);
         tagMob(warlord, WARLORD_TAG, key);
         warlord.setPersistenceRequired();
         level.addFreshEntity(warlord);
         warlordIds.put(key, warlord.getUUID());
      }

      // === ELDER EVOKER (mini-boss, summons vexes, has Totem of Undying) ===
      Mob elderEvoker = EntityTypes.EVOKER.create(level, EntitySpawnReason.EVENT);
      if (elderEvoker != null) {
         elderEvoker.setCustomName(Component.literal("§5§l⚔ Elder Evoker"));
         elderEvoker.setCustomNameVisible(true);
         // Totem of Undying in offhand - he resurrects once!
         elderEvoker.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.TOTEM_OF_UNDYING));
         elderEvoker.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
         elderEvoker.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
         elderEvoker.setDropChance(EquipmentSlot.OFFHAND, 0.0F);
         elderEvoker.setDropChance(EquipmentSlot.HEAD, 0.0F);
         elderEvoker.setDropChance(EquipmentSlot.CHEST, 0.0F);
         elderEvoker.setPos(center.getX() + 4 + 0.5, standY(level, center.getX() + 4 + 0.5, center.getZ() + 0.5, y), center.getZ() + 0.5);
         applyBossStats(elderEvoker, 100.0, diffScale, playerScale);
         disjoinFromRaid(elderEvoker);
         tagMob(elderEvoker, ELDER_EVOKER_TAG, key);
         elderEvoker.setPersistenceRequired();
         level.addFreshEntity(elderEvoker);
         elderEvokerIds.put(key, elderEvoker.getUUID());
         com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.TOTEM_OF_UNDYING, elderEvoker.getX(), elderEvoker.getY() + 1, elderEvoker.getZ(), 30, 0.5, 1.0, 0.5, 0.1);
      }

      // === ILLUSIONER (mini-boss, creates duplicates, enchanted gear) ===
      Mob illusioner = EntityTypes.ILLUSIONER.create(level, EntitySpawnReason.EVENT);
      if (illusioner != null) {
         Registry<Enchantment> ench2 = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
         illusioner.setCustomName(Component.literal("§9§l⚔ Raid Illusioner"));
         illusioner.setCustomNameVisible(true);
         ItemStack illBow = new ItemStack(Items.BOW);
         try {
            illBow.enchant(ench2.getOrThrow(Enchantments.POWER), 7);
            illBow.enchant(ench2.getOrThrow(Enchantments.PUNCH), 2);
            illBow.enchant(ench2.getOrThrow(Enchantments.FLAME), 1);
            illBow.enchant(ench2.getOrThrow(Enchantments.UNBREAKING), 3);
         } catch (Exception ignored) {}
         illusioner.setItemSlot(EquipmentSlot.MAINHAND, illBow);
         illusioner.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
         illusioner.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
         illusioner.setDropChance(EquipmentSlot.MAINHAND, 0.0F);
         illusioner.setDropChance(EquipmentSlot.HEAD, 0.0F);
         illusioner.setDropChance(EquipmentSlot.CHEST, 0.0F);
         illusioner.setPos(center.getX() - 4 + 0.5, standY(level, center.getX() - 4 + 0.5, center.getZ() + 0.5, y), center.getZ() + 0.5);
         applyBossStats(illusioner, 90.0, diffScale, playerScale);
         disjoinFromRaid(illusioner);
         tagMob(illusioner, ILLUSIONER_TAG, key);
         illusioner.setPersistenceRequired();
         level.addFreshEntity(illusioner);
         illusionerIds.put(key, illusioner.getUUID());
         com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.PORTAL, illusioner.getX(), illusioner.getY() + 1, illusioner.getZ(), 20, 0.5, 1.0, 0.5, 0.1);
      }

      // === RAID CAPTAIN (quickshot bow + multishot + piercing + power 5) ===
      Mob captain = EntityTypes.PILLAGER.create(level, EntitySpawnReason.EVENT);
      if (captain != null) {
         Registry<Enchantment> ench3 = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
         captain.setCustomName(Component.literal("§6§l⚔ Raid Captain"));
         captain.setCustomNameVisible(true);
         ItemStack capBow = new ItemStack(Items.BOW);
         try {
            capBow.enchant(ench3.getOrThrow(Enchantments.QUICK_CHARGE), 3);
            capBow.enchant(ench3.getOrThrow(Enchantments.MULTISHOT), 1);
            capBow.enchant(ench3.getOrThrow(Enchantments.POWER), 5);
            capBow.enchant(ench3.getOrThrow(Enchantments.UNBREAKING), 3);
         } catch (Exception ignored) {}
         captain.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
         captain.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
         captain.setItemSlot(EquipmentSlot.MAINHAND, capBow);
         captain.setDropChance(EquipmentSlot.HEAD, 0.1F);
         captain.setDropChance(EquipmentSlot.CHEST, 0.1F);
         captain.setDropChance(EquipmentSlot.MAINHAND, 0.0F); // don't drop vanilla crossbow
         captain.setPos(center.getX() + 0.5, standY(level, center.getX() + 0.5, center.getZ() - 4 + 0.5, y), center.getZ() - 4 + 0.5);
         applyBossStats(captain, 65.0, diffScale, playerScale);
         disjoinFromRaid(captain);
         tagMob(captain, CAPTAIN_TAG, key);
         captain.setPersistenceRequired();
         level.addFreshEntity(captain);
         captainIds.put(key, captain.getUUID());
         com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.PORTAL, captain.getX(), captain.getY() + 1, captain.getZ(), 20, 0.5, 1.0, 0.5, 0.1);
      }

      // === EVOKER LIEUTENANT (caster) ===
      Mob evokerLt = EntityTypes.EVOKER.create(level, EntitySpawnReason.EVENT);
      if (evokerLt != null) {
         evokerLt.setCustomName(Component.literal("§5§l⚔ Evoker Lt"));
         evokerLt.setCustomNameVisible(true);
         evokerLt.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
         evokerLt.setDropChance(EquipmentSlot.HEAD, 0.0F);
         evokerLt.setPos(center.getX() - 4 + 0.5, standY(level, center.getX() - 4 + 0.5, center.getZ() - 4 + 0.5, y), center.getZ() - 4 + 0.5);
         applyBossStats(evokerLt, 70.0, diffScale, playerScale);
         disjoinFromRaid(evokerLt);
         tagMob(evokerLt, LIEUTENANT_TAG, key);
         evokerLt.setPersistenceRequired();
         level.addFreshEntity(evokerLt);
         lieutenantIds.computeIfAbsent(key, k -> new ArrayList<>()).add(evokerLt.getUUID());
         com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.PORTAL, evokerLt.getX(), evokerLt.getY() + 1, evokerLt.getZ(), 20, 0.5, 1.0, 0.5, 0.1);
      }

      // === RECORD FIGHT START TIME ===
      state.fightStartMs = System.currentTimeMillis();

      // === BOSS BAR ===
      ServerBossEvent bar = new ServerBossEvent(UUID.randomUUID(),
         Component.literal("§c§l⚔ Raid Bosses §7(5 remaining)"),
         BossBarColor.RED, BossBarOverlay.PROGRESS);
      bar.setVisible(true);
      bar.setProgress(1.0F);
      for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
         if (p.level().dimension().equals(level.dimension()) && p.distanceToSqr(center.getX(), center.getY(), center.getZ()) < 200.0 * 200.0) {
            bar.addPlayer(p);
         }
      }
      bossBars.put(key, bar);

      // === DRAMATIC SPAWN VFX ===
      for (int i = 0; i < 60; i++) {
         double a = i / 60.0 * Math.PI * 2.0;
         double r = 2.0 + RANDOM.nextDouble() * 4.0;
         com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.PORTAL,
            center.getX() + 0.5 + Math.cos(a) * r, y + 1.0 + RANDOM.nextDouble() * 3.0, center.getZ() + 0.5 + Math.sin(a) * r,
            3, 0.5, 1.0, 0.5, 0.1);
      }
      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.SOUL_FIRE_FLAME, center.getX() + 0.5, y + 1.0, center.getZ() + 0.5, 30, 2.0, 1.5, 2.0, 0.05);
      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.LARGE_SMOKE, center.getX() + 0.5, y + 0.5, center.getZ() + 0.5, 20, 3.0, 0.5, 3.0, 0.02);
      level.playSound(null, center, SoundEvents.ENDER_DRAGON_GROWL, SoundSource.HOSTILE, 1.5F, 0.6F);
      level.playSound(null, center, SoundEvents.WITHER_SPAWN, SoundSource.HOSTILE, 1.0F, 0.8F);
      level.playSound(null, center, SoundEvents.RAID_HORN.value(), SoundSource.HOSTILE, 1.5F, 0.7F);

      for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
         Chat.raw(p, "§8§m═══════════════════════════════§r");
         Chat.raw(p, "");
         Chat.raw(p, "    §c§l⚔ THE RAID BOSSES DESCEND! ⚔");
         Chat.raw(p, "    §7The waves are cleared - five champions close in on you:");
         Chat.raw(p, "    §c§lWarlord §7(melee) §5§lElder Evoker §7(vexes) §9§lIllusioner §7(clones)");
         Chat.raw(p, "    §6§lCaptain §7(crossbow drop) §5§lEvoker Lt §7(caster)");
         Chat.raw(p, "    §7Dynamic difficulty: §f" + nearby + " player" + (nearby == 1 ? "" : "s") + " near §8→ §fHP ×" + Math.round(diffScale * playerScale * 10.0) / 10.0);
         Chat.raw(p, "    §7Location: §f" + center.getX() + ", " + center.getZ() + "§7 - slay all five!");
         Chat.raw(p, "");
         Chat.raw(p, "§8§m═══════════════════════════════§r");
      }
   }

   /** Sets a raid boss's HP (scaled by Bad Omen and player count) plus a modest
    *  damage and armor bump - replaces the old 5x variant-tier bloat. */
   private static void applyBossStats(Mob mob, double baseHp, double diffScale, double playerScale) {
      AttributeInstance hp = mob.getAttribute(Attributes.MAX_HEALTH);
      if (hp != null) {
         hp.setBaseValue(baseHp * diffScale * playerScale);
      }
      mob.setHealth(mob.getMaxHealth());
      AttributeInstance dmg = mob.getAttribute(Attributes.ATTACK_DAMAGE);
      if (dmg != null) {
         dmg.setBaseValue(dmg.getBaseValue() * 1.3);
      }
      AttributeInstance armor = mob.getAttribute(Attributes.ARMOR);
      if (armor != null) {
         armor.setBaseValue(armor.getBaseValue() + 4.0);
      }
   }

   /** Keeps a raid boss out of the vanilla raid's tracking entirely - freshly
    *  spawned raiders auto-join raids at their position, and that absorption is
    *  what made killing one boss nuke the rest when the raid cleaned up. */
   private static void disjoinFromRaid(Mob mob) {
      if (mob instanceof Raider raider) {
         raider.setCurrentRaid(null);
         raider.setCanJoinRaid(false);
      }
   }

   /** Discards every remaining raid boss for a key (abandoned boss phase). */
   private static void despawnBosses(ServerLevel level, String key) {
      discardIfPresent(level, warlordIds.get(key));
      discardIfPresent(level, elderEvokerIds.get(key));
      discardIfPresent(level, illusionerIds.get(key));
      discardIfPresent(level, captainIds.get(key));
      List<UUID> lts = lieutenantIds.get(key);
      if (lts != null) {
         for (UUID id : new ArrayList<>(lts)) {
            discardIfPresent(level, id);
         }
      }
      warlordIds.remove(key);
      elderEvokerIds.remove(key);
      illusionerIds.remove(key);
      captainIds.remove(key);
      lieutenantIds.remove(key);
   }

   private static void discardIfPresent(ServerLevel level, UUID id) {
      if (id == null) {
         return;
      }
      net.minecraft.world.entity.Entity e = level.getEntity(id);
      if (e != null) {
         e.discard();
      }
   }

   /** Tags a mob with the given FF tag and raid key. */
   private static void tagMob(Mob mob, String tag, String key) {
      CustomData cd = (CustomData)mob.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
      CompoundTag nbt = cd.copyTag();
      nbt.putString(tag, key);
      mob.setComponent(DataComponents.CUSTOM_DATA, CustomData.of(nbt));
   }

   // --- VFX methods (same as before) ---

   private static void raidAmbientVfx(ServerLevel level, BlockPos center) {
      int y = level.getHeight(Types.MOTION_BLOCKING, center.getX(), center.getZ());
      for (int i = 0; i < 8; i++) {
         double a = RANDOM.nextDouble() * Math.PI * 2.0;
         double r = 3.0 + RANDOM.nextDouble() * 8.0;
         com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.CRIMSON_SPORE,
            center.getX() + 0.5 + Math.cos(a) * r, y + 1.0 + RANDOM.nextDouble() * 4.0, center.getZ() + 0.5 + Math.sin(a) * r,
            1, 0.3, 0.3, 0.3, 0.02);
      }
      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.CAMPFIRE_COSY_SMOKE, center.getX() + 0.5, y + 0.5, center.getZ() + 0.5, 3, 2.0, 1.0, 2.0, 0.01);
      for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
         if (p.level().dimension().equals(level.dimension()) && p.distanceToSqr(center.getX(), center.getY(), center.getZ()) < 100.0 * 100.0) {
            com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.ANGRY_VILLAGER, p.getX(), p.getY() + 2.0, p.getZ(), 2, 0.5, 0.3, 0.5, 0.02);
         }
      }
   }

   private static void playRaidHorn(ServerLevel level, BlockPos center) {
      level.playSound(null, center, SoundEvents.RAID_HORN.value(), SoundSource.HOSTILE, 1.5F, 0.8F + RANDOM.nextFloat() * 0.2F);
   }

   private static void announceWaveStart(ServerLevel level, RaidState state, int wave) {
      BlockPos center = state.center;
      Vec3 floor = new Vec3(center.getX() + 0.5, surfaceY(level, center), center.getZ() + 0.5);
      com.fortuneandfavors.net.FfVfx.shape(level, com.fortuneandfavors.net.FfVfx.SUMMON_CIRCLE, ParticleTypes.FLAME, floor, Vec3.ZERO, 4.0 + wave * 0.5, 50, WAR_RED);
      com.fortuneandfavors.net.FfVfx.shape(level, com.fortuneandfavors.net.FfVfx.PILLAR, ParticleTypes.FLAME, floor, Vec3.ZERO, 6.0 + wave, 0.0, WAR_RED);
      playRaidHorn(level, center);
      int y = level.getHeight(Types.MOTION_BLOCKING, center.getX(), center.getZ());
      // Escalating VFX per wave - more particles, more dramatic
      int particleCount = 15 + wave * 10;
      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.SOUL_FIRE_FLAME, center.getX() + 0.5, y + 1.0, center.getZ() + 0.5, particleCount, 2.0 + wave * 0.5, 1.0 + wave * 0.3, 2.0 + wave * 0.5, 0.03);
      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.SMOKE, center.getX() + 0.5, y + 0.5, center.getZ() + 0.5, 10 + wave * 5, 2.0, 0.5, 2.0, 0.02);
      if (wave >= 3) {
         com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.LARGE_SMOKE, center.getX() + 0.5, y + 1.0, center.getZ() + 0.5, wave * 5, 3.0, 1.0, 3.0, 0.03);
      }
      if (wave >= 5) {
         com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.LARGE_SMOKE, center.getX() + 0.5, y + 2.0, center.getZ() + 0.5, 20, 4.0, 2.0, 4.0, 0.02);
         level.playSound(null, center, SoundEvents.ENDER_DRAGON_GROWL, SoundSource.HOSTILE, 0.8F, 0.6F);
      }
      for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
         if (p.level().dimension().equals(level.dimension()) && p.distanceToSqr(center.getX(), center.getY(), center.getZ()) < 128.0 * 128.0) {
            String waveDesc = switch (Math.min(wave, 6)) {
               case 1 -> "§7Pillagers & Vindicators charge in!";
               case 2 -> "§7Witches join the assault with potions!";
               case 3 -> "§7Ravagers breach the defenses!";
               case 4 -> "§7§9Illusioners§7 create clones - find the real one!";
               case 5 -> "§7§5Evokers§7 summon vexes and fangs!";
               default -> "§7§4FULL RAID ESCALATION§7 - everything attacks!";
            };
            Chat.raw(p, "§c§l⚔ WAVE " + wave + "§r §8- §f" + waveDesc);
         }
      }
   }

   private static void raidDefeatVfx(ServerLevel level, BlockPos center) {
      int y = level.getHeight(Types.MOTION_BLOCKING, center.getX(), center.getZ());
      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.LARGE_SMOKE, center.getX() + 0.5, y + 1.0, center.getZ() + 0.5, 40, 4.0, 2.0, 4.0, 0.05);
      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.SMOKE, center.getX() + 0.5, y + 0.5, center.getZ() + 0.5, 30, 3.0, 1.0, 3.0, 0.03);
      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.SNOWFLAKE, center.getX() + 0.5, y + 1.0, center.getZ() + 0.5, 20, 3.0, 1.5, 3.0, 0.04);
      level.playSound(null, center, SoundEvents.ENDER_DRAGON_DEATH, SoundSource.HOSTILE, 0.8F, 0.5F);
      level.playSound(null, center, SoundEvents.WITHER_DEATH, SoundSource.HOSTILE, 0.6F, 0.6F);
   }

   private static void warlordDeathVfx(ServerLevel level, BlockPos pos) {
      com.fortuneandfavors.net.FfVfx.shape(level, com.fortuneandfavors.net.FfVfx.DEATH, ParticleTypes.FLAME, Vec3.atCenterOf(pos), Vec3.ZERO, 50.0, 0.0, WAR_GOLD);
      com.fortuneandfavors.net.FfVfx.shape(level, com.fortuneandfavors.net.FfVfx.FROST_NOVA, ParticleTypes.FLAME, Vec3.atBottomCenterOf(pos), Vec3.ZERO, 14.0, 0.0, WAR_GOLD);
      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.END_ROD, pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, 30, 1.5, 1.5, 1.5, 0.1);
      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.TOTEM_OF_UNDYING, pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, 20, 1.0, 1.0, 1.0, 0.15);
      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.FIREWORK, pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, 15, 1.0, 1.0, 1.0, 0.08);
      level.playSound(null, pos, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 1.0F, 0.8F);
      level.playSound(null, pos, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.MASTER, 1.0F, 1.0F);
   }

   private static void lieutenantDeathVfx(ServerLevel level, BlockPos pos) {
      com.fortuneandfavors.net.FfVfx.shape(level, com.fortuneandfavors.net.FfVfx.ICE_BURST, ParticleTypes.FLAME, Vec3.atCenterOf(pos), Vec3.ZERO, 1.6, 0.0, WAR_RED);
      com.fortuneandfavors.net.FfVfx.shape(level, com.fortuneandfavors.net.FfVfx.PILLAR, ParticleTypes.FLAME, Vec3.atBottomCenterOf(pos), Vec3.ZERO, 5.0, 0.0, WAR_GOLD);
      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.END_ROD, pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, 15, 0.8, 0.8, 0.8, 0.08);
      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.SOUL_FIRE_FLAME, pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, 10, 0.5, 0.8, 0.5, 0.04);
      level.playSound(null, pos, SoundEvents.SOUL_ESCAPE.value(), SoundSource.HOSTILE, 0.8F, 0.8F);
   }

   /** Broadcasts a kill-feed message to all players in the dimension. */
   private static void broadcastKillFeed(ServerLevel level, String message) {
      for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
         if (p.level().dimension().equals(level.dimension())) {
            Chat.raw(p, "§8[§c⚔ Kill§8] §r" + message);
         }
      }
   }

   private static void announceRaidStart(ServerLevel level, RaidState state) {
      BlockPos center = state.center;
      playRaidHorn(level, center);
      int y = level.getHeight(Types.MOTION_BLOCKING, center.getX(), center.getZ());
      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.PORTAL, center.getX() + 0.5, y + 1.0, center.getZ() + 0.5, 40, 3.0, 2.0, 3.0, 0.1);
      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.SOUL_FIRE_FLAME, center.getX() + 0.5, y + 1.0, center.getZ() + 0.5, 20, 2.0, 1.5, 2.0, 0.05);
      level.playSound(null, center, SoundEvents.ENDER_DRAGON_GROWL, SoundSource.HOSTILE, 1.0F, 0.7F);
      for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
         Chat.raw(p, "§8§m═══════════════════════════§r");
         Chat.raw(p, "  §c§l⚔ PLAYER RAID!§r §7The raiders are coming for §c§lYOU§7!");
         Chat.raw(p, "  §7Location: §f" + center.getX() + ", " + center.getZ());
         Chat.raw(p, "  §7Survive " + (3 + Math.min(3, state.omenLevel)) + " waves of §cpillagers§7, §dvindicators§7, §9illusioners§7 & §5evokers§7!");
         Chat.raw(p, "  §7Then slay the §c§lWarlord§7 for §6massive cash§7 + §53 gems§7 + §flegendary loot§7!");
         if (raidDifficulty > 0) {
            Chat.raw(p, "  §4Difficulty: §f" + raidDifficulty + "x §7(from Bad Omen)");
         }
         Chat.raw(p, "§8§m═══════════════════════════§r");
         p.sendSystemMessage(Component.literal("§c§l⚔ PLAYER RAID! §7Raiders hunting you at " + center.getX() + ", " + center.getZ()), true);
      }
   }

   private static void settleVictory(ServerLevel level, RaidState state) {
      BlockPos center = state.center;
      int omenLevel = state.omenLevel;
      long baseReward = 15000L + omenLevel * 10000L;

      // === COMPLETION TIMER ===
      long fightMs = state.fightStartMs > 0 ? System.currentTimeMillis() - state.fightStartMs : 0L;
      long fightSeconds = fightMs / 1000L;
      // Bonus cash tiers: <2min = 50%, <3min = 25%, <5min = 10%, else 0%
      double timeBonusMult = 0.0;
      String timeRank = null;
      if (fightSeconds < 120) {
         timeBonusMult = 0.5;
         timeRank = "§6§l⚡ BLITZ §7(<2 min)";
      } else if (fightSeconds < 180) {
         timeBonusMult = 0.25;
         timeRank = "§e§l★ SPEEDY §7(<3 min)";
      } else if (fightSeconds < 300) {
         timeBonusMult = 0.10;
         timeRank = "§a✓ SWIFT §7(<5 min)";
      }
      long timeBonusCash = (long)(baseReward * timeBonusMult);

      // === FASTEST COMPLETION RECORD (server-wide broadcast) ===
      String dimKey = level.dimension().identifier().toString();
      Long best = fastestCompletions.get(dimKey);
      boolean newRecord = best == null || fightSeconds < best;
      if (newRecord) {
         fastestCompletions.put(dimKey, fightSeconds);
      }

      // === COMPLETION BONUS (flat cash for finishing the raid) ===
      long completionBonus = 10000L + omenLevel * 5000L;

      // === PARTICLE FOUNTAIN INTENSITY (scales with speed) ===
      // fountainIntensity: 1.0 = base, up to 5.0 for blitz speed
      double fountainIntensity = 1.0;
      if (fightSeconds < 120) {
         fountainIntensity = 5.0;       // BLITZ: massive fountain
      } else if (fightSeconds < 180) {
         fountainIntensity = 3.5;       // SPEEDY: strong fountain
      } else if (fightSeconds < 300) {
         fountainIntensity = 2.0;       // SWIFT: moderate fountain
      }

      // === VICTORY VFX ===
      int y = level.getHeight(Types.MOTION_BLOCKING, center.getX(), center.getZ());
      double cx = center.getX() + 0.5;
      double cy = y + 1.0;
      double cz = center.getZ() + 0.5;

      // Base victory particles (scaled by fountain intensity)
      int fw = (int)(50 * fountainIntensity);
      int ft = (int)(30 * fountainIntensity);
      int fe = (int)(25 * fountainIntensity);
      int fh = (int)(20 * fountainIntensity);
      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.FIREWORK, cx, cy, cz, fw, 3.0, 2.0, 3.0, 0.15);
      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.TOTEM_OF_UNDYING, cx, cy, cz, ft, 2.0, 2.0, 2.0, 0.12);
      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.END_ROD, cx, cy, cz, fe, 2.0, 2.0, 2.0, 0.08);
      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.HAPPY_VILLAGER, cx, cy, cz, fh, 2.0, 1.0, 2.0, 0.05);

      // === PARTICLE FOUNTAIN: column of particles shooting upward ===
      spawnRaidFountain(level, cx, y, cz, fountainIntensity);
      com.fortuneandfavors.net.FfVfx.shape(level, com.fortuneandfavors.net.FfVfx.FROST_NOVA, ParticleTypes.TOTEM_OF_UNDYING, new Vec3(cx, y, cz), Vec3.ZERO, 18.0, 0.0, WAR_GOLD);
      com.fortuneandfavors.net.FfVfx.shape(level, com.fortuneandfavors.net.FfVfx.ULTIMATE, ParticleTypes.TOTEM_OF_UNDYING, new Vec3(cx, y + 1.0, cz), Vec3.ZERO, 40.0, 0.0, WAR_GOLD);

      // Extra celebration ring for blitz speed
      if (fightSeconds < 120) {
         for (int i = 0; i < 40; i++) {
            double angle = i / 40.0 * Math.PI * 2.0;
            double r = 4.0 + RANDOM.nextDouble() * 2.0;
            com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.FIREWORK,
               cx + Math.cos(angle) * r, cy + 2.0, cz + Math.sin(angle) * r,
               3, 0.1, 0.1, 0.1, 0.05);
            com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.TOTEM_OF_UNDYING,
               cx + Math.cos(angle) * r, cy + 1.0, cz + Math.sin(angle) * r,
               2, 0.1, 0.3, 0.1, 0.08);
         }
         // Crown of soul fire above the fountain
         com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.SOUL_FIRE_FLAME, cx, cy + 6.0, cz, 30, 1.5, 0.5, 1.5, 0.03);
      }

      // Dramatic sounds
      level.playSound(null, center, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.MASTER, 1.5F, 1.0F);
      level.playSound(null, center, SoundEvents.FIREWORK_ROCKET_BLAST, SoundSource.MASTER, 1.0F, 1.0F);
      if (fightSeconds < 120) {
         // Blitz gets extra fanfare
         level.playSound(null, center, SoundEvents.FIREWORK_ROCKET_BLAST, SoundSource.MASTER, 1.2F, 0.8F);
         level.playSound(null, center, SoundEvents.FIREWORK_ROCKET_BLAST, SoundSource.MASTER, 1.2F, 1.2F);
      }

      // === Participation-based rewards ===
      int totalParticipation = 0;
      for (int val : state.participation.values()) {
         totalParticipation += val;
      }
      if (totalParticipation == 0) totalParticipation = 1;

      // Paid to everyone who FOUGHT, not just everyone still standing here.
      // state.participation is credited every five seconds to anyone inside the
      // 128-block bubble, so it is the honest record of who defended the raid.
      // Paying only the players the victory loop can see meant a defender who
      // died to the Warlord, respawned, and ran back was simply skipped - the
      // survivors split a hoard that their teammate had bled for.
      Set<UUID> paid = new LinkedHashSet<>(state.participation.keySet());
      for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
         if (p.level().dimension().equals(level.dimension()) && p.distanceToSqr(center.getX(), center.getY(), center.getZ()) < 128.0 * 128.0) {
            paid.add(p.getUUID());
         }
      }

      for (UUID payId : paid) {
         ServerPlayer p = level.getServer().getPlayerList().getPlayer(payId);
         if (p == null) {
            continue;
         }
         {
            int myParticipation = state.participation.getOrDefault(payId, 1);
            double participationMult = 1.0 + 0.5 * ((double)myParticipation / Math.max(1, totalParticipation));
            long each = (long)(baseReward * participationMult);
            long total = each + timeBonusCash + completionBonus;
            EconomyManager.addCash(p.getUUID(), total);
            TokenManager.giveGems(p, 3);
            // Every participant loots 5 Raid Loot Boxes from the Warlord's hoard,
            // with anything that will not fit collected for /claim loot rather
            // than dropped in the middle of a burning village.
            for (int b = 0; b < 5; b++) {
               BossPayout.giveOrCollect(p, ModItems.raidLootBox());
            }
            // Achievements: first ever raid win, and a solo win if you fought alone.
            Advancements.grant(p, "raid_first_victory");
            if (state.participation.size() <= 1) {
               Advancements.grant(p, "raid_solo");
            }

            // === HERO OF THE VILLAGE (10 min, amplifier 1) ===
            p.addEffect(new MobEffectInstance(MobEffects.HERO_OF_THE_VILLAGE, 12000, 1, false, true, true));

            String partMsg = myParticipation > 5 ? " §7(§a+" + (int)((participationMult - 1.0) * 100) + "% participation§7)" : "";
            String timeMsg = timeBonusCash > 0 ? " §7+ §6" + Chat.moneyStr(timeBonusCash) + "§7 speed bonus" : "";
            String completionMsg = " §7+ §e" + Chat.moneyStr(completionBonus) + "§7 completion bonus";
            Chat.raw(p, "§d§lRAID VICTORY!§r §7You earned " + Chat.moneyStr(total) + "§7 + §53 gems§7!" + partMsg + timeMsg + completionMsg);
            Chat.raw(p, "§c§l5× Raid Loot Boxes§7 looted from the Warlord's hoard!");
            Chat.raw(p, "§6§l★ Hero of the Village §7applied for 10 minutes!");
            DailyWeeklyChallengeManager.onDeliver(p);
         }
      }
      // === KILL-FEED SUMMARY ===
      for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
         Chat.raw(p, "§8§m═══════════════════════════════§r");
         Chat.raw(p, "  §a§l⚔ RAID VICTORY!§r §7All bosses slain!");
         Chat.raw(p, "  §7⏱ Completion time: §f" + formatDuration(fightSeconds) + (timeRank != null ? " §7- " + timeRank : " §7- §7no speed bonus"));
         Chat.raw(p, "  §7💰 Completion bonus: §e" + Chat.moneyStr(completionBonus));
         Chat.raw(p, "  §6§l★ Hero of the Village §7applied!");
         Chat.raw(p, "");
         Chat.raw(p, "  §7§lKill Feed:");
         for (Map.Entry<String, String> entry : state.bossKillers.entrySet()) {
            Chat.raw(p, "    " + entry.getKey() + " §8→ §f" + entry.getValue());
         }
         if (state.warlordKiller != null) {
            Chat.raw(p, "");
            Chat.raw(p, "  §e🏆 " + state.warlordKiller.getName().getString() + " §7dealt the final blow to the Warlord!");
         }
         Chat.raw(p, "§8§m═══════════════════════════════§r");
      }
      if (newRecord) {
         String recordName = state.warlordKiller != null ? state.warlordKiller.getName().getString() : "A raid party";
         level.getServer()
            .getPlayerList()
            .broadcastSystemMessage(
               Component.literal(
                  Chat.colorize("§e[!] §f" + recordName + "§e set a new §c§lFASTEST RAID§e record: §f" + formatDuration(fightSeconds) + "§e!")
               ),
               false
            );
      }
      if (state.warlordKiller != null) {
         level.getServer()
            .getPlayerList()
            .broadcastSystemMessage(
               Component.literal(
                  Chat.colorize("§e[!] §f" + state.warlordKiller.getName().getString() + "§e slew the §cRaid Warlord§e and claimed the raid treasure!")
               ),
               false
            );
      }
      try {
         DynamicContractsManager.onRaidVictory(level.getServer(), state.warlordKiller);
      } catch (Exception ignored) {
      }
      try {
         level.getServer().getFunctions()
            .get(Identifier.fromNamespaceAndPath("fortuneandfavors", "raid_victory"))
            .ifPresent(
               fn -> level.getServer().getFunctions().execute(
                  fn,
                  level.getServer().createCommandSourceStack().withLevel(level).withPosition(net.minecraft.world.phys.Vec3.atCenterOf(center))
               )
            );
      } catch (Throwable ignored) {
      }
      markCooldown(level);
   }

   /** True when every participant in the raid has died (or logged off) and no
    *  living player is still fighting. This is the player-raid LOSS condition:
    *  the Warlord's hoard is lost and everyone who fought gets the
    *  "All Fell" achievement. */
   private static boolean participantsAllDead(ServerLevel level, RaidState state) {
      if (state.participation.isEmpty()) {
         return false;
      }
      // Someone alive is still near the fight - the raid lives on.
      if (aliveNearbyPlayers(level, state.center) > 0) {
         return false;
      }
      for (UUID id : state.participation.keySet()) {
         ServerPlayer p = level.getServer().getPlayerList().getPlayer(id);
         if (p != null && p.isAlive()) {
            return false;
         }
      }
      return true;
   }

   /** True when every participant except the betrayer has died or is gone, and
    *  no other living player is near the raid. That makes the betrayer the
    *  last one standing - they win the raid. */
   private static boolean otherParticipantsDeadOrGone(ServerLevel level, RaidState state) {
      for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
         if (!p.level().dimension().equals(level.dimension())) {
            continue;
         }
         if (state.betrayerId != null && state.betrayerId.equals(p.getUUID())) {
            continue;
         }
         // A corpse is not a defender. This loop never asked whether the player
         // it found was alive, and a dead player's body sits exactly where they
         // fell - almost always inside the 128-block bubble around the raid
         // centre. So "every defender is dead" was the one case that could never
         // satisfy this test: the betrayer stood alone on a field of their
         // teammates' bodies, the check kept finding those bodies, and the
         // betrayal victory - and the hoard that comes with it - never settled.
         if (!p.isAlive()) {
            continue;
         }
         if (p.distanceToSqr(state.center.getX(), state.center.getY(), state.center.getZ()) < 128.0 * 128.0) {
            return false;
         }
      }
      for (UUID id : state.participation.keySet()) {
         if (state.betrayerId != null && state.betrayerId.equals(id)) {
            continue;
         }
         ServerPlayer p = level.getServer().getPlayerList().getPlayer(id);
         if (p != null && p.isAlive()) {
            return false;
         }
      }
      return true;
   }

   /** The betrayer won: every raider, miniboss and the Warlord himself vanishes,
    *  all boss bars drop, and the betrayer takes the "Villain Of the Village"
    *  achievement. */
   private static void settleBetrayalVictory(ServerLevel level, RaidState state) {
      String key = null;
      for (Map.Entry<String, RaidState> e : tracked.entrySet()) {
         if (e.getValue() == state) {
            key = e.getKey();
            break;
         }
      }
      ServerPlayer betrayer = state.betrayerId != null ? level.getServer().getPlayerList().getPlayer(state.betrayerId) : null;
      String name = betrayer != null ? betrayer.getName().getString() : "The traitor";
      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.TOTEM_OF_UNDYING, state.center.getX(), state.center.getY() + 4.0, state.center.getZ(), 60, 3.0, 3.0, 3.0, 0.2);
      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.FIREWORK, state.center.getX() + 0.5, state.center.getY() + 2.0, state.center.getZ() + 0.5, 60, 3.0, 2.0, 3.0, 0.15);
      level.playSound(null, state.center, SoundEvents.WITHER_SPAWN, SoundSource.HOSTILE, 1.0F, 1.4F);
      level.playSound(null, state.center, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.MASTER, 1.5F, 1.0F);
      if (key != null) {
         removeBossBar(key);
         removeBetrayerBar(key);
         clearRaidGlows(level);
         despawnBosses(level, key);
         discardWaveRaiders(level, key);
      }
      level.getServer().getPlayerList().broadcastSystemMessage(
         Component.literal(Chat.colorize("§8§m═══════════════════════════════════§r\n  §c§l☠ VILLAIN OF THE VILLAGE §r\n  §f" + name + "§c betrayed them all and won the raid!\n§8§m═══════════════════════════════════§r")), false);
      if (betrayer != null) {
         // The betrayer takes the whole hoard: 5 Raid Loot Boxes per fighter who
         // stood against them. Everyone else gets nothing.
         int fighters = Math.max(1, state.participation.size());
         int hoardBoxes = fighters * 5;
         for (int b = 0; b < hoardBoxes; b++) {
            InventoryHelper.giveOrDrop(betrayer, ModItems.raidLootBox());
         }
         EconomyManager.addCash(betrayer.getUUID(), 30000L);
         TokenManager.giveGems(betrayer, 5);
         Chat.raw(
            betrayer,
            "§4§lYou claimed the Warlord's hoard for yourself! §7+§e" + Chat.moneyStr(30000L) + "§7 + §53 gems§7 + §c" + hoardBoxes + "× Raid Loot Boxes§7."
         );
         Advancements.grant(betrayer, "villain_of_the_village");
      }
      markCooldown(level);
   }

   /** Every participant died: the whole raid vanishes - wave raiders, bosses and
    *  all - and everyone who fought gets the "All Fell" achievement. */
   private static void settleDefeatAllFell(ServerLevel level, String key, RaidState state) {
      raidDefeatVfx(level, state.center);
      removeBossBar(key);
      removeBetrayerBar(key);
      clearRaidGlows(level);
      despawnBosses(level, key);
      discardWaveRaiders(level, key);
      for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
         Chat.raw(p, "§8§m═══════════════════════════════§r");
         Chat.raw(p, "  §c§l⚔ RAID LOST §7- every participant fell in battle!");
         Chat.raw(p, "  §7With no one left to fight, the raiders take the hoard and fade away.");
         Chat.raw(p, "§8§m═══════════════════════════════§r");
      }
      for (UUID id : state.participation.keySet()) {
         ServerPlayer p = level.getServer().getPlayerList().getPlayer(id);
         if (p != null) {
            Advancements.grant(p, "raid_all_fallen");
         }
      }
      markCooldown(level);
   }

   /** Spawns a particle fountain at the raid center. Intensity scales with speed (1.0-5.0). */
   private static void spawnRaidFountain(ServerLevel level, double cx, double baseY, double cz, double intensity) {
      // Core fountain column: particles shoot upward, height and count scale with intensity
      int coreCount = (int)(80 * intensity);
      double maxHeight = 4.0 + intensity * 4.0;
      for (int i = 0; i < coreCount; i++) {
         double progress = RANDOM.nextDouble();
         double py = baseY + 1.0 + progress * maxHeight;
         double spread = (1.0 - progress) * 1.5 * intensity;
         double px = cx + (RANDOM.nextDouble() - 0.5) * spread;
         double pz = cz + (RANDOM.nextDouble() - 0.5) * spread;
         // Core: firework sparks in the center
         com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.FIREWORK, px, py, pz, 1, 0.05, 0.15, 0.05, 0.02);
         // Glow: end rods along the edges
         if (RANDOM.nextInt(3) == 0) {
            double edgeX = cx + (RANDOM.nextDouble() - 0.5) * spread * 2.0;
            double edgeZ = cz + (RANDOM.nextDouble() - 0.5) * spread * 2.0;
            com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.END_ROD, edgeX, py, edgeZ, 1, 0.02, 0.1, 0.02, 0.03);
         }
      }
      // Outer spiral rings at intervals
      int rings = (int)(3 + intensity * 2);
      for (int ring = 0; ring < rings; ring++) {
         double ringY = baseY + 2.0 + ring * (maxHeight / rings);
         double ringRadius = 0.5 + intensity * 0.6;
         int ringCount = (int)(12 * intensity);
         for (int i = 0; i < ringCount; i++) {
            double angle = i / (double)ringCount * Math.PI * 2.0 + ring * 0.5;
            double rx = cx + Math.cos(angle) * ringRadius;
            double rz = cz + Math.sin(angle) * ringRadius;
            com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.TOTEM_OF_UNDYING, rx, ringY, rz, 1, 0.03, 0.05, 0.03, 0.04);
         }
      }
      // Crown burst at the top
      double topY = baseY + 1.0 + maxHeight;
      int crownCount = (int)(30 * intensity);
      for (int i = 0; i < crownCount; i++) {
         double angle = RANDOM.nextDouble() * Math.PI * 2.0;
         double r = RANDOM.nextDouble() * 2.0 * intensity;
         com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.HAPPY_VILLAGER,
            cx + Math.cos(angle) * r, topY + RANDOM.nextDouble() * 2.0, cz + Math.sin(angle) * r,
            1, 0.1, 0.1, 0.1, 0.02);
      }
      // Base glow
      com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.SOUL_FIRE_FLAME, cx, baseY + 1.0, cz,
         (int)(20 * intensity), 1.5, 0.3, 1.5, 0.02);
   }

   /** Formats seconds into a human-readable duration string. */
   private static String formatDuration(long seconds) {
      long mins = seconds / 60;
      long secs = seconds % 60;
      if (mins > 0) {
         return mins + "m " + secs + "s";
      }
      return secs + "s";
   }

   private static class RaidState {
      boolean announced = false;
      boolean settled = false;
      boolean warlordSpawned = false;
      boolean necromancerSpawned = false;
      /** The dimension the raid runs in (player raids need no village). */
      ResourceKey<Level> dimension;
      /** Current wave number (0 = not started). */
      int wave = 0;
      /** Game time when the next wave may spawn. */
      long nextWaveTick = -1L;
      /** Game time when the wave phase was last seen with zero defenders (-1 = defending). */
      long noPlayersSince = -1L;
      /** Raid center + Bad Omen level captured at start, so the wave phase and
       *  the victory settlement keep working without any vanilla raid object. */
      BlockPos center;
      int omenLevel;
      ServerPlayer warlordKiller = null;
      /** Tracks how many ticks each player participated in the raid. */
      final Map<UUID, Integer> participation = new HashMap<>();
      /** Timestamp when the boss phase began (for completion timer). */
      long fightStartMs = 0L;
      /** Tracks who killed each boss type: boss name -> killer name. */
      final Map<String, String> bossKillers = new HashMap<>();
      /** Second phase: the Warlord's offer has been made (once per raid, multiplayer only). */
      boolean betrayalTriggered = false;
      /** The player the Warlord brought away for the offer. */
      UUID betrayerId = null;
      /** True if that player accepted and joined the Warlord. */
      boolean betrayJoined = false;
      /** True if that player denied the offer. */
      boolean betrayDenied = false;
      /** Tick when the last non-betrayer participant died (betrayal victory grace). */
      long villainSince = -1L;
      /** Tick when all participants were dead (defeat grace period). */
      long allDeadSince = -1L;
      /** The Warlord's own move clock - per raid, not the level's game time. */
      long warlordNextSlam = -1L;
      long warlordNextCharge = -1L;
      /** Tick the winding-up slam lands on, or -1 while none is winding up. */
      long warlordSlamLands = -1L;
      /** The phase the fight is currently in, so each transition is announced once. */
      int warlordPhase = 0;
      /** The last stand's reinforcements have been called. */
      boolean lastStandCalled = false;
   }

   /** A boss phase running independently of the vanilla raid lifecycle. */
   private static class BossPhase {
      final ResourceKey<Level> dimension;

      BossPhase(ResourceKey<Level> dimension) {
         this.dimension = dimension;
      }
   }
}
