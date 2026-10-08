package com.fortuneandfavors.duel;

import com.fortuneandfavors.FortuneFavorsMod;
import com.fortuneandfavors.ModItems;
import com.fortuneandfavors.economy.ServerClock;
import com.fortuneandfavors.duel.DuelManager.Arena;
import com.fortuneandfavors.duel.DuelManager.Bet;
import com.fortuneandfavors.duel.DuelManager.BotDifficulty;
import com.fortuneandfavors.duel.DuelManager.BwShopEntry;
import com.fortuneandfavors.duel.DuelManager.Challenge;
import com.fortuneandfavors.duel.DuelManager.CombatStyle;
import com.fortuneandfavors.duel.DuelManager.Duel;
import com.fortuneandfavors.duel.DuelManager.DuelMode;
import com.fortuneandfavors.duel.DuelManager.FFALobby;
import com.fortuneandfavors.duel.DuelManager.FfaBoard;
import com.fortuneandfavors.duel.DuelManager.LastDuel;
import com.fortuneandfavors.duel.DuelManager.LeaderboardRow;
import com.fortuneandfavors.duel.DuelManager.Participant;
import com.fortuneandfavors.duel.DuelManager.PendingPick;
import com.fortuneandfavors.duel.DuelManager.Phase;
import com.fortuneandfavors.duel.DuelManager.SavedPlayer;
import com.fortuneandfavors.duel.DuelManager.SpectatorSession;
import com.fortuneandfavors.duel.DuelManager.ToolTier;
import com.fortuneandfavors.duel.DuelManager.WagerRequest;
import com.fortuneandfavors.duel.DuelManager.WagerStaged;
import com.fortuneandfavors.economy.Advancements;
import com.fortuneandfavors.economy.EconomyManager;
import com.fortuneandfavors.economy.ModConfig;
import com.fortuneandfavors.economy.SwordBlockManager;
import com.fortuneandfavors.map.MapEditor;
import com.fortuneandfavors.map.MapEditor.BlockData;
import com.fortuneandfavors.map.MapEditor.CustomMap;
import com.fortuneandfavors.menu.BedwarsShopMenu;
import com.fortuneandfavors.menu.BedwarsUpgradeMenu;
import com.fortuneandfavors.menu.DraftMenu;
import com.fortuneandfavors.menu.DuelModeMenu;
import com.fortuneandfavors.menu.KitsKitMenu;
import com.fortuneandfavors.menu.LuckyPvpMenu;
import com.fortuneandfavors.menu.RematchMenu;
import com.fortuneandfavors.menu.SkywarsKitMenu;
import com.fortuneandfavors.menu.SpectatorMenu;
import com.fortuneandfavors.net.FfArenaProbePayload;
import com.fortuneandfavors.net.FfScreenFxPayload;
import com.fortuneandfavors.guild.GuildManager;
import com.fortuneandfavors.util.Chat;
import com.fortuneandfavors.util.InventoryHelper;
import com.fortuneandfavors.util.JsonUtil;
import com.fortuneandfavors.util.PerfMonitor;
import com.fortuneandfavors.util.Safe;
import com.google.common.collect.ImmutableMultimap;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map.Entry;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import com.fortuneandfavors.net.FfNet;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.commands.arguments.blocks.BlockStateParser.BlockResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.Direction.Axis;
import net.minecraft.core.HolderLookup.Provider;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.numbers.BlankFormat;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.network.protocol.game.ClientboundHurtAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerAbilitiesPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket.Rot;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket.Action;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayer.RespawnConfig;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.BossEvent.BossBarColor;
import net.minecraft.world.BossEvent.BossBarOverlay;
import net.minecraft.world.damagesource.CombatRules;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Entity.RemovalReason;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.entity.projectile.hurtingprojectile.windcharge.WindCharge;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownSplashPotion;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.LodestoneTracker;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.Level.ExplosionInteraction;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap.Types;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.WorldData;
import net.minecraft.world.level.storage.LevelData.RespawnData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.TeamColor;
import net.minecraft.world.scores.Team.Visibility;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;
import net.minecraft.world.scores.criteria.ObjectiveCriteria.RenderType;
import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Multimap;

public final class DuelManager {
   public static final int ARENA_SURFACE_Y = 100;
   public static final ResourceKey<Level> DUEL_REALM = ResourceKey.create(
      Registries.DIMENSION, Identifier.fromNamespaceAndPath("fortuneandfavors", "duel_arena")
   );
   private static final int SURFACE_Y = 100;
   private static final int LOBBY_TICKS = 600;
   private static final int COUNTDOWN_TICKS = 100;
   private static final int SKYWARS_REFILL_TICKS = 1200;
   private static final int COMBO_WINDOW_TICKS = 100;
   private static final int WIN_ANIM_TICKS = 40;
   private static final Identifier LEGACY_ATTACK_SPEED = FortuneFavorsMod.id("duel_legacy_attack_speed");
   private static final String[] BOT_NAMES = new String[]{"§cSparBot", "§6DuelBot", "§bTrainingBot", "§aPracticeBot"};

   /**
    * How many cases {@code giveFightGear} actually has, pinned against the enum's
    * size by the self-test.
    *
    * <p>That method switches on {@code d.mode.ordinal()}, so a mode appended
    * without a matching case does not fail to compile - it just starts the match
    * with an empty inventory, which reads as "this gamemode is broken" rather than
    * "somebody forgot a case". Add the case, then bump this. The test also pins
    * the ordinals of the appended modes, because inserting one in the middle of
    * the enum would silently re-kit every mode after it.
    */
   private static final int KIT_CASES = 18;

   public static int kitCases() {
      return KIT_CASES;
   }
   private static final Map<UUID, Challenge> challenges = new HashMap<>();
   private static final Map<UUID, Duel> duels = new HashMap<>();
   private static final Map<UUID, SavedPlayer> pendingRestore = new HashMap<>();
   private static final Map<UUID, Map<String, long[]>> duelStats = new HashMap<>();
   private static final Map<UUID, Integer> bestCombos = new HashMap<>();
   private static final Map<UUID, String> statNames = new HashMap<>();
   private static final Map<UUID, Long> goldenFlashCooldown = new HashMap<>();
   private static final Map<UUID, LastDuel> lastDuels = new HashMap<>();
   private static final Map<UUID, Map<String, Integer>> botWinStreaks = new HashMap<>();
   /** Per-bot last-damage tick - anti-spam that does NOT block projectiles. */
   private static final Map<UUID, Long> botLastHitTick = new HashMap<>();
   private static final Set<UUID> hackerUnlockedPlayers = new HashSet<>();
   private static final Map<UUID, FFALobby> ffaLobbies = new HashMap<>();
   private static final Map<UUID, SpectatorSession> spectatorSessions = new HashMap<>();
   private static final Map<UUID, UUID> pendingSpectatorBets = new HashMap<>();
   /**
    * The bot each player has built for themselves, and the one filed for the
    * challenge they just started.
    *
    * <p>Kept per player rather than per difficulty on purpose: a hand-built bot is
    * meant to be the opponent in front of you, not a redefinition of what "Hard"
    * means for everyone else on the server. The pending map exists only to carry the
    * profile from the challenge into the duel that gets created for it.
    */
   private static final Map<UUID, BotProfile> customBotProfiles = new HashMap<>();
   private static final Map<UUID, BotProfile> pendingBotProfiles = new HashMap<>();
   private static final Map<String, List<Bet>> activeBets = new HashMap<>();
   private static final Map<UUID, List<Bet>> pendingBets = new HashMap<>();
   private static final Map<UUID, WagerRequest> pendingWagers = new HashMap<>();
   private static final Map<UUID, WagerStaged> wagerStaged = new HashMap<>();
   private static final long MAX_FIGHT_TICKS = 36000L;
   private static final int RANDOMIZER_SWAP_TICKS = 200;
   private static final int DRAFT_LOBBY_TICKS = 600;
   private static final int KITS_LOBBY_TICKS = 300;
   private static final int DRAFT_PICK_TICKS = 200;
   private static final int BEDWARS_RESPAWN_TICKS = 70;
   private static final Set<Integer> usedPlots = new HashSet<>();
   private static int startupSweepPlot = -1;
   private static int startupSweepChunk;
   private static final int STARTUP_SWEEP_CHUNKS_PER_TICK = 4;
   /** MSPT past which a realm sweep halves its chunk budget, then crawls at one chunk a tick. */
   private static final double SWEEP_SLOW_MSPT = 35.0D;
   private static final double SWEEP_CRAWL_MSPT = 45.0D;
   /**
    * Plots an arena was built on and has not been torn down from, persisted so that a crash
    * mid-match still names the plot that needs cleaning on the next boot.
    *
    * <p>The boot sweep used to walk every plot in the realm that was not in use - all 64 of
    * them, at 256 chunks each. That is 16,384 chunk loads, generated and re-primed, four a
    * tick, for a little over three minutes, on a realm that in the ordinary case holds
    * nothing: the log of one live server shows exactly that shape, a "Can't keep up! Running
    * 2729ms or 54 ticks behind" a few seconds into the session and the "duel realm swept"
    * line three and a half minutes later. A plot that was never built on has nothing to sweep,
    * and one that was is cleared again the moment a match claims it, so the boot now revisits
    * only what a match actually built on.
    */
   private static final Set<Integer> dirtyPlots = new LinkedHashSet<>();
   /** The plots this boot's sweep will walk, in order - empty when nothing needs cleaning. */
   private static final List<Integer> sweepQueue = new ArrayList<>();
   private static Path sweepFile;
   private static int nextArena = 0;
   private static Path dataFile;
   private static Path statsFile;
   private static MinecraftServer serverRef;
   private static final ArrayDeque<Runnable> pendingTeardowns = new ArrayDeque<>();
   private static final int DRAFT_MAX_PICKS = 5;
   private static final String[] DRAFT_KINDS = new String[]{
      "sword", "bow", "armor_diamond", "armor_iron", "blocks", "pearl", "heal", "speed", "gapple", "gapple_head", "axe", "shield", "rod"
   };
   private static final Set<Integer> usedBoardSlots = new HashSet<>();
   private static int boardSlotCursor = 0;
   private static final DisplaySlot[] TEAM_SLOTS = buildTeamSlots();
   private static final TeamColor[] TEAM_COLORS = TeamColor.values();
   private static final long SWORD_BLOCK_PRESS_MS = 500L;
   private static final Map<UUID, Long> swordBlockHolding = new HashMap<>();
   private static final Map<UUID, Boolean> swordBlockSent = new HashMap<>();
   private static final Map<UUID, PendingPick> pendingPicks = new HashMap<>();
   private static final String GOLDEN_APPLE_SKIN_VALUE = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNDIxY2FiNDA5NWU3MWJkOTI1Y2Y0NjQ5OTBlMThlNDNhZGI3MjVkYjdjYzE3NWZkOWQxZGVjODIwOTE0YjNkZSJ9fX0=";
   private static Difficulty originalDifficulty;
   private static final String[] KIT_NAMES = new String[]{
      "Knight",
      "Archer",
      "Tank",
      "Rush",
      "Builder",
      "Berserker",
      "Mage",
      "Ninja",
      "Pyro",
      "Frog",
      "Fisherman",
      "Scout",
      "Enderman",
      "Snowman",
      "Creeper",
      "Healer"
   };
   private static final int TEARDOWN_BATCH = 2000;
   private static final int TEARDOWN_Y_MAX = 164;
   private static final ToolTier[] SWORD_TIERS = new ToolTier[]{
      new ToolTier(Items.STONE_SWORD, Items.IRON_INGOT, 10),
      new ToolTier(Items.IRON_SWORD, Items.GOLD_INGOT, 7),
      new ToolTier(Items.DIAMOND_SWORD, Items.EMERALD, 4)
   };
   private static final ToolTier[] PICK_TIERS = new ToolTier[]{
      new ToolTier(Items.STONE_PICKAXE, Items.IRON_INGOT, 10),
      new ToolTier(Items.IRON_PICKAXE, Items.GOLD_INGOT, 8),
      new ToolTier(Items.DIAMOND_PICKAXE, Items.EMERALD, 5)
   };
   private static final ToolTier[] AXE_TIERS = new ToolTier[]{
      new ToolTier(Items.STONE_AXE, Items.IRON_INGOT, 12), new ToolTier(Items.IRON_AXE, Items.GOLD_INGOT, 8), new ToolTier(Items.DIAMOND_AXE, Items.EMERALD, 5)
   };
   private static final Item[][] ARMOR_SETS = new Item[][]{
      {Items.CHAINMAIL_HELMET, Items.CHAINMAIL_CHESTPLATE, Items.CHAINMAIL_LEGGINGS, Items.CHAINMAIL_BOOTS},
      {Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS},
      {Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS}
   };
   private static final ToolTier[] ARMOR_TIERS = new ToolTier[]{
      new ToolTier(Items.CHAINMAIL_CHESTPLATE, Items.IRON_INGOT, 24),
      new ToolTier(Items.IRON_CHESTPLATE, Items.GOLD_INGOT, 12),
      new ToolTier(Items.DIAMOND_CHESTPLATE, Items.EMERALD, 6)
   };
   private static final List<BwShopEntry> BW_SHOP = buildBwShop();
   private static final String[] BW_UPGRADES = new String[]{"forge", "trap", "fatigue", "haste", "protection", "sharpness"};

   public static boolean isHackerUnlocked(ServerPlayer p) {
      return p != null && hackerUnlockedPlayers.contains(p.getUUID());
   }

   // --------------------------------------------------------- custom bot builder

   /** The bot this player built, or the Normal preset when they have not built one. */
   public static BotProfile customProfileOf(ServerPlayer p) {
      if (p == null) {
         return BotProfile.of(BotDifficulty.NORMAL);
      }

      BotProfile stored = customBotProfiles.get(p.getUUID());
      return stored != null ? stored : BotProfile.of(BotDifficulty.NORMAL);
   }

   /** Stores the bot this player built. Every dial is clamped by the record. */
   public static void setCustomProfile(ServerPlayer p, BotProfile profile) {
      if (p != null && profile != null) {
         customBotProfiles.put(p.getUUID(), profile);
      }
   }

   /**
    * Challenges a bot built by this player.
    *
    * <p>The difficulty rides along as Normal, because the four presets describe a
    * bot and a custom bot is not one of them - its numbers come from the profile,
    * which is applied when the duel is created. Everything the preset would have
    * decided that the profile does not cover (the kit pool, the dodge chance) runs
    * at Normal's values, which is the honest reading of "a bot I built": the four
    * dials are the bot, and they are the four the player was offered.
    */
   public static String challengeCustomBot(ServerPlayer p) {
      if (p == null) {
         return "This command must be run by a player.";
      }

      pendingBotProfiles.put(p.getUUID(), customProfileOf(p));
      return beginChallenge(p, "bot", BotDifficulty.NORMAL);
   }

   private static void trackBotWinStreak(ServerPlayer player, Duel d) {
      if (player != null && d.botDifficulty == BotDifficulty.HARD) {
         String modeName = d.mode.name();
         botWinStreaks.computeIfAbsent(player.getUUID(), k -> new HashMap<>());
         Map<String, Integer> streaks = botWinStreaks.get(player.getUUID());
         int streak = streaks.getOrDefault(modeName, 0) + 1;
         streaks.put(modeName, streak);
         if (streak >= 3 && hackerUnlockedPlayers.add(player.getUUID())) {
            Chat.msg(player, "§5§l⚔ SECRET UNLOCKED: Hacker Difficulty ⚔");
            Chat.msg(player, "§7You conquered hard bots 3 in a row - the ultimate challenge awaits.");
         }
      }
   }

   private static void resetBotWinStreak(ServerPlayer player, Duel d) {
      if (player != null && d.botDifficulty == BotDifficulty.HARD) {
         Map<String, Integer> streaks = botWinStreaks.get(player.getUUID());
         if (streaks != null) {
            streaks.put(d.mode.name(), 0);
         }
      }
   }

   public static String requestFFA(ServerPlayer host, DuelMode mode) {
      if (!ModConfig.is("duels")) {
         return "Duels are disabled on this server.";
      }

      if (host == null) {
         return "This command must be run by a player.";
      }

      if (isInDuel(host.getUUID())) {
         return "You're already in a duel.";
      }

      if (ffaLobbies.containsKey(host.getUUID())) {
         return "You already have an FFA lobby open.";
      }

      FFALobby lobby = new FFALobby(mode, host.getUUID());
      lobby.startedAt = ServerClock.clock(host.level());
      ffaLobbies.put(host.getUUID(), lobby);
      lobby.members.add(host.getUUID());
      announceFFA(
         host,
         "&6&l" + host.getName().getString() + "&r&7 started a &e" + mode.display + " FFA&7! Type &a/ffa join &7to join (or the host can &a/ffa start&7).",
         30
      );
      host.level().playSound(null, host.getX(), host.getY(), host.getZ(), SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 1.0F, 1.4F);
      return null;
   }

   public static String joinFFA(ServerPlayer p) {
      if (!ModConfig.is("duels")) {
         return "Duels are disabled.";
      }

      if (p == null) {
         return "This command must be run by a player.";
      }

      if (isInDuel(p.getUUID())) {
         return "You're already in a duel.";
      }

      FFALobby lobby = findClosestFFA(p);
      if (lobby == null) {
         return "No open FFA lobbies nearby. Start one with /ffa.";
      }

      if (lobby.members.size() >= 16) {
         return "This FFA lobby is full (max 16 players).";
      }

      if (lobby.members.contains(p.getUUID())) {
         return "You're already in this lobby.";
      }

      lobby.members.add(p.getUUID());
      ServerPlayer host = p.level().getServer().getPlayerList().getPlayer(lobby.host);
      if (host != null) {
         Chat.msg(host, "&a" + p.getName().getString() + " &7joined the &eFFA&7! (&e" + lobby.members.size() + "&7 players)");
      }

      p.level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 1.0F, 1.4F);
      return null;
   }

   public static String startFFA(ServerPlayer host) {
      if (host == null) {
         return "This command must be run by a player.";
      } else {
         FFALobby lobby = ffaLobbies.remove(host.getUUID());
         if (lobby == null) {
            return "You don't have an open FFA lobby.";
         } else if (lobby.members.size() < 2) {
            dismissFFALobby(lobby, "Not enough players to start the FFA.");
            return null;
         } else {
            String err = createFFADuel(host, lobby);
            return err != null ? err : null;
         }
      }
   }

   public static String dismissFFA(ServerPlayer host) {
      if (host == null) {
         return "This command must be run by a player.";
      }

      FFALobby lobby = ffaLobbies.remove(host.getUUID());
      if (lobby == null) {
         return "You don't have an open FFA lobby.";
      }

      dismissFFALobby(lobby, "&cThe FFA lobby has been cancelled.");
      return null;
   }

   private static void dismissFFALobby(FFALobby lobby, String msg) {
      for (UUID uuid : lobby.members) {
         ServerPlayer p = serverRef != null ? serverRef.getPlayerList().getPlayer(uuid) : null;
         if (p != null) {
            Chat.msg(p, msg);
         }
      }
   }

   private static void announceFFA(ServerPlayer host, String msg, int range) {
      for (Player pl : host.level().players()) {
         if (pl instanceof ServerPlayer sp && sp.distanceTo(host) <= range) {
            Chat.msg(sp, msg);
         }
      }
   }

   private static FFALobby findClosestFFA(ServerPlayer p) {
      FFALobby closest = null;
      double best = Double.MAX_VALUE;

      for (FFALobby lobby : ffaLobbies.values()) {
         ServerPlayer host = p.level().getServer().getPlayerList().getPlayer(lobby.host);
         if (host != null) {
            double dist = p.distanceTo(host);
            if (dist < 30.0 && dist < best) {
               best = dist;
               closest = lobby;
            }
         }
      }

      return closest;
   }

   public static void setPendingSpectatorBet(UUID spectatorUuid, UUID targetUuid) {
      pendingSpectatorBets.put(spectatorUuid, targetUuid);
   }

   public static boolean handleSpectatorBetInput(ServerPlayer spectator, String message) {
      UUID target = pendingSpectatorBets.remove(spectator.getUUID());
      if (target == null) {
         return false;
      }

      try {
         long amount = Long.parseLong(message.trim().replaceAll("[^0-9]", ""));
         if (amount <= 0L) {
            Chat.msg(spectator, "§cBet must be at least $1.");
            return true;
         }

         String err = placeBet(spectator, serverRef.getPlayerList().getPlayer(target), amount);
         if (err != null) {
            Chat.msg(spectator, "§c" + err);
         } else {
            ServerPlayer targetP = serverRef.getPlayerList().getPlayer(target);
            String name = targetP != null ? targetP.getName().getString() : "?";
            Chat.msg(spectator, "§aBet placed! §7You wagered §e" + Chat.moneyStr(amount) + " §7on §e" + name + " §7to win.");
         }
      } catch (NumberFormatException e) {
         Chat.msg(spectator, "§cInvalid amount. Type a number (e.g. 100).");
      }

      return true;
   }

   public static boolean isSpectating(UUID uuid) {
      return spectatorSessions.containsKey(uuid);
   }

   public static boolean tryOpenSpectatorMenu(ServerPlayer sp) {
      SpectatorSession s = spectatorSessions.get(sp.getUUID());
      if (s == null) {
         return false;
      }

      Duel d = null;

      for (Duel du : duels.values()) {
         if (du.arena.ox == s.arenaOx() && du.arena.oz == s.arenaOz()) {
            d = du;
            break;
         }
      }

      // The menu offers a bet on either side by uuid, so it needs two real players:
      // a bot has no uuid to bet on, and the entry would be filed against nothing.
      if (d != null
         && d.parts.size() >= 2
         && isPlayer((Participant)d.parts.get(0))
         && isPlayer((Participant)d.parts.get(1))) {
         String n0 = ((Participant)d.parts.get(0)).displayName;
         String n1 = ((Participant)d.parts.get(1)).displayName;
         SpectatorMenu.open(sp, ((Participant)d.parts.get(0)).uuid, ((Participant)d.parts.get(1)).uuid, n0 != null ? n0 : "?", n1 != null ? n1 : "?");
         return true;
      } else {
         return false;
      }
   }

   public static SpectatorSession getSpectatorSession(UUID uuid) {
      return spectatorSessions.get(uuid);
   }

   public static String spectate(ServerPlayer spectator, UUID targetUuid) {
      if (spectator == null) {
         return "This command must be run by a player.";
      }

      Duel target = duels.get(targetUuid);
      if (target == null) {
         return "That player isn't in a duel anymore.";
      }

      if (target.phase == Phase.ENDED) {
         return "That duel has already ended.";
      }

      if (isInDuel(spectator.getUUID())) {
         return "You're already in a duel - quit first with /duel quit.";
      }

      if (spectatorSessions.containsKey(spectator.getUUID())) {
         return "You're already spectating.";
      }

      GameType prev = spectator.gameMode.getGameModeForPlayer();
      List<ItemStack> savedInv = new ArrayList<>();

      for (int i = 0; i < spectator.getInventory().getContainerSize(); i++) {
         savedInv.add(spectator.getInventory().getItem(i).copy());
      }

      int savedSlot = spectator.getInventory().getSelectedSlot();
      spectatorSessions.put(
         spectator.getUUID(),
         new SpectatorSession(
            target.arena.ox,
            target.arena.oz,
            prev,
            spectator.level().dimension(),
            spectator.getX(),
            spectator.getY(),
            spectator.getZ(),
            spectator.getYRot(),
            spectator.getXRot(),
            savedInv,
            savedSlot
         )
      );
      double[] sp = target.arena.spectatorSpawn;
      if (sp != null) {
         spectator.teleportTo(sp[0], sp[1], sp[2]);
      }

      spectator.setGameMode(GameType.SPECTATOR);
      addWatcherNametag(spectator, target);
      spectator.getInventory().clearContent();
      ItemStack star = new ItemStack(Items.NETHER_STAR);
      star.set(DataComponents.CUSTOM_NAME, Component.literal("§d§lDuel Spectator Menu"));
      star.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      spectator.getInventory().setItem(0, star);
      spectator.getInventory().setSelectedSlot(0);

      for (Participant part : target.parts) {
         ServerPlayer fighter = serverRef != null ? serverRef.getPlayerList().getPlayer(part.uuid) : null;
         if (fighter != null) {
            Chat.msg(fighter, "§7" + spectator.getName().getString() + " §7is now watching the fight.");
         }
      }

      Chat.msg(spectator, "§7You are now spectating. Right-click the §d§lNether Star§7 to open the menu.");
      return null;
   }

   public static void unspectate(ServerPlayer spectator) {
      if (spectator != null) {
         SpectatorSession s = spectatorSessions.remove(spectator.getUUID());
         if (s != null) {
            removeWatcherNametag(spectator);
            spectator.setGameMode(s.previousMode());
            spectator.getInventory().clearContent();

            try {
               for (int i = 0; i < s.inventory().size() && i < spectator.getInventory().getContainerSize(); i++) {
                  ItemStack saved = (ItemStack)s.inventory().get(i);
                  if (saved != null && !saved.isEmpty()) {
                     spectator.getInventory().setItem(i, saved.copy());
                  }
               }

               spectator.getInventory().setSelectedSlot(s.selectedSlot());
            } catch (Exception var8) {
            }

            try {
               MinecraftServer server = spectator.level().getServer();
               ServerLevel home = server != null ? server.getLevel(s.dim()) : null;
               if (home != null) {
                  spectator.teleport(
                     new TeleportTransition(home, new Vec3(s.x(), s.y(), s.z()), Vec3.ZERO, s.yaw(), s.pitch(), TeleportTransition.PLACE_PORTAL_TICKET)
                  );
               }
            } catch (Exception var7) {
            }

            for (Duel d : duels.values()) {
               if (d.arena.ox == s.arenaOx() && d.arena.oz == s.arenaOz()) {
                  for (Participant part : d.parts) {
                     ServerPlayer fighter = serverRef != null ? serverRef.getPlayerList().getPlayer(part.uuid) : null;
                     if (fighter != null) {
                        Chat.msg(fighter, "§7" + spectator.getName().getString() + " §7stopped watching.");
                     }
                  }
                  break;
               }
            }

            Chat.msg(spectator, "§7You left the spectator area.");
         }
      }
   }

   public static void toggleSpectatorMode(ServerPlayer spectator) {
      if (spectator != null) {
         SpectatorSession s = spectatorSessions.get(spectator.getUUID());
         if (s != null) {
            if (spectator.gameMode.getGameModeForPlayer() == GameType.SPECTATOR) {
               spectator.setGameMode(GameType.SURVIVAL);
               Chat.msg(spectator, "§7Switched to survival - you can move around. Right-click the §d§lNether Star§7 to go back.");
            } else {
               spectator.setGameMode(GameType.SPECTATOR);

               for (Duel d : duels.values()) {
                  if (d.arena.ox == s.arenaOx() && d.arena.oz == s.arenaOz() && d.arena.spectatorSpawn != null) {
                     double[] sp = d.arena.spectatorSpawn;
                     spectator.teleportTo(sp[0], sp[1], sp[2]);
                     break;
                  }
               }

               Chat.msg(spectator, "§7Switched back to spectator mode.");
            }
         }
      }
   }

   private static void unspectateDuelSpectators(Duel d) {
      List<UUID> toRemove = new ArrayList<>();

      for (Entry<UUID, SpectatorSession> e : spectatorSessions.entrySet()) {
         SpectatorSession s = e.getValue();
         if (s.arenaOx() == d.arena.ox && s.arenaOz() == d.arena.oz) {
            toRemove.add(e.getKey());
         }
      }

      for (UUID uuid : toRemove) {
         ServerPlayer p = serverRef != null ? serverRef.getPlayerList().getPlayer(uuid) : null;
         if (p != null) {
            Chat.msg(p, "§7The fight you were watching has ended.");
            unspectate(p);
         } else {
            spectatorSessions.remove(uuid);
         }
      }
   }

   /**
    * The key a pair of fighters' wagers are filed under, or null when the pair
    * cannot carry one.
    *
    * <p>A bot has no uuid - it is not a player, and the rest of this file already
    * works that way on purpose (see the bonus payout, which checks for it). A
    * match against one can never have a player's wager on it, so the honest
    * answer for that pair is "no key at all".
    *
    * <p>That null check is load-bearing, and it is the whole of this method's
    * history. {@code UUID.compareTo} is an instance method, so comparing a bot's
    * absent uuid against a player's threw a {@link NullPointerException} - and
    * because this runs from the duel teardown, the throw took the entire tick
    * with it and voided the match. Every bot duel that ended went out that way.
    */
   public static String betKey(UUID a, UUID b) {
      if (a == null || b == null) {
         return null;
      }

      return a.compareTo(b) < 0 ? a + ":" + b : b + ":" + a;
   }

   public static String placeBet(ServerPlayer bettor, ServerPlayer target, long amount) {
      if (amount <= 0L) {
         return "Wager must be at least $1.";
      }

      if (bettor.getUUID().equals(target.getUUID())) {
         return "You can't wager on yourself.";
      }

      if (!EconomyManager.hasCash(bettor.getUUID(), amount)) {
         return "Not enough money! Need " + Chat.moneyStr(amount) + ".";
      }

      if (isInDuel(bettor.getUUID())) {
         return "You can't wager while in a duel.";
      }

      if (isInDuel(target.getUUID())) {
         return target.getName().getString() + " is already in a duel.";
      }

      pendingWagers.entrySet().removeIf(e -> e.getValue().bettor().equals(bettor.getUUID()) && e.getValue().target().equals(target.getUUID()));
      UUID id = UUID.randomUUID();
      long expires = serverRef != null ? ServerClock.clock(serverRef) + 1200 : Long.MAX_VALUE;
      pendingWagers.put(id, new WagerRequest(bettor.getUUID(), target.getUUID(), amount, expires));
      Chat.msg(
         target,
         "§6§l"
            + bettor.getName().getString()
            + "§r§7 challenges you to a §e"
            + Chat.moneyStr(amount)
            + "§7 wager duel! Type §f/duel wager accept§7 or §f/duel wager deny§7."
      );
      Chat.msg(bettor, "§7Wager sent to §f" + target.getName().getString() + "§7 - they have 60 seconds to accept.");
      return null;
   }

   public static String anteUp(ServerPlayer a, ServerPlayer b, long amount) {
      if (amount <= 0L) {
         return null;
      }

      if (!EconomyManager.hasCash(a.getUUID(), amount)) {
         return a.getName().getString() + " doesn't have " + Chat.moneyStr(amount) + ".";
      }

      if (!EconomyManager.hasCash(b.getUUID(), amount)) {
         return b.getName().getString() + " doesn't have " + Chat.moneyStr(amount) + ".";
      }

      EconomyManager.takeCash(a.getUUID(), amount);
      EconomyManager.takeCash(b.getUUID(), amount);
      return null;
   }

   public static String acceptWager(ServerPlayer acceptor) {
      if (acceptor == null) {
         return "This command must be run by a player.";
      }

      if (isInDuel(acceptor.getUUID())) {
         return "You're already in a duel.";
      }

      WagerRequest wager = null;
      UUID wagerId = null;

      for (Entry<UUID, WagerRequest> e : pendingWagers.entrySet()) {
         if (e.getValue().target().equals(acceptor.getUUID())) {
            wager = e.getValue();
            wagerId = e.getKey();
            break;
         }
      }

      if (wager == null) {
         return "You have no pending wager offers.";
      }

      pendingWagers.remove(wagerId);
      if (serverRef != null && ServerClock.clock(serverRef) > wager.expiresAt()) {
         return "That wager has expired.";
      }

      ServerPlayer bettor = serverRef != null ? serverRef.getPlayerList().getPlayer(wager.bettor()) : null;
      if (bettor == null) {
         return "The wagerer went offline.";
      }

      if (isInDuel(bettor.getUUID())) {
         return "The wagerer is already in a duel.";
      }

      if (!EconomyManager.hasCash(acceptor.getUUID(), wager.amount())) {
         return "You don't have " + Chat.moneyStr(wager.amount()) + ".";
      }

      if (!EconomyManager.hasCash(bettor.getUUID(), wager.amount())) {
         return bettor.getName().getString() + " no longer has " + Chat.moneyStr(wager.amount()) + ".";
      }

      wagerStaged.put(
         acceptor.getUUID(), new WagerStaged(bettor.getUUID(), acceptor.getUUID(), wager.amount(), serverRef != null ? ServerClock.clock(serverRef) : 0L)
      );
      pendingPicks.put(acceptor.getUUID(), new PendingPick(false, bettor.getName().getString(), null));
      Chat.msg(
         acceptor,
         "§aWager accepted! §7Pick the duel mode - §f"
            + bettor.getName().getString()
            + "§7 then accepts and the §e"
            + Chat.moneyStr(wager.amount() * 2L)
            + "§7 pool locks in."
      );
      Chat.msg(
         bettor,
         "§a"
            + acceptor.getName().getString()
            + " accepted your wager! §7They're picking the mode - accept the challenge and the §e"
            + Chat.moneyStr(wager.amount() * 2L)
            + "§7 pool locks in."
      );
      DuelModeMenu.open(acceptor);
      return null;
   }

   public static String denyWager(ServerPlayer denier) {
      if (denier == null) {
         return "This command must be run by a player.";
      }

      WagerRequest wager = null;
      UUID wagerId = null;

      for (Entry<UUID, WagerRequest> e : pendingWagers.entrySet()) {
         if (e.getValue().target().equals(denier.getUUID())) {
            wager = e.getValue();
            wagerId = e.getKey();
            break;
         }
      }

      if (wager == null) {
         return "You have no pending wager offers.";
      }

      pendingWagers.remove(wagerId);
      ServerPlayer bettor = serverRef != null ? serverRef.getPlayerList().getPlayer(wager.bettor()) : null;
      if (bettor != null) {
         Chat.msg(bettor, "§c" + denier.getName().getString() + " declined your wager.");
      }

      return null;
   }

   private static void expirePendingWagers() {
      if (serverRef != null) {
         long now = ServerClock.clock(serverRef);
         pendingWagers.entrySet().removeIf(e -> {
            if (now > e.getValue().expiresAt()) {
               ServerPlayer bettor = serverRef.getPlayerList().getPlayer(e.getValue().bettor());
               ServerPlayer target = serverRef.getPlayerList().getPlayer(e.getValue().target());
               if (bettor != null) {
                  Chat.msg(bettor, "§7Your wager to " + (target != null ? target.getName().getString() : "?") + " expired.");
               }

               return true;
            } else {
               return false;
            }
         });
         wagerStaged.entrySet().removeIf(e -> now > e.getValue().stagedAt() + 1200L);
      }
   }

   private static void lockBets(Duel d) {
      if (d.parts.size() >= 2) {
         // A bot fight takes no wagers, so there is nothing to move and no key to
         // file anything under.
         String key = betKey(((Participant)d.parts.get(0)).uuid, ((Participant)d.parts.get(1)).uuid);
         if (key == null) {
            return;
         }

         for (Participant part : d.parts) {
            if (part.uuid == null) {
               continue;
            }

            List<Bet> pending = pendingBets.remove(part.uuid);
            if (pending != null) {
               activeBets.computeIfAbsent(key, k -> new ArrayList<>()).addAll(pending);
            }
         }
      }
   }

   private static void settleBets(Duel d, Participant winner) {
      if (winner != null && d.parts.size() >= 2) {
         // An ante only exists when two players both put one up, so a null uuid
         // here would mean paying a player who does not exist into the economy.
         if (d.ante > 0L && winner.uuid != null) {
            long pot = d.ante * 2L;
            EconomyManager.addCash(winner.uuid, pot);
            ServerPlayer winP = serverRef != null ? serverRef.getPlayerList().getPlayer(winner.uuid) : null;
            if (winP != null) {
               Chat.msg(winP, "&aYou won the ante! &e" + Chat.moneyStr(pot) + " &ahas been added to your balance.");
            }
         }

         String key = betKey(((Participant)d.parts.get(0)).uuid, ((Participant)d.parts.get(1)).uuid);
         List<Bet> bets = key == null ? null : activeBets.remove(key);
         if (bets != null && !bets.isEmpty()) {
            long poolBetAmount = 0L;

            for (Bet bet : bets) {
               if (bet.bettor.equals(((Participant)d.parts.get(0)).uuid) || bet.bettor.equals(((Participant)d.parts.get(1)).uuid)) {
                  for (Bet other : bets) {
                     if (other.bettor.equals(bet.target) && other.target.equals(bet.bettor) && other.amount == bet.amount) {
                        poolBetAmount = bet.amount;
                        break;
                     }
                  }
               }
            }

            if (poolBetAmount > 0L) {
               long pool = poolBetAmount * 2L;
               EconomyManager.addCash(winner.uuid, pool);
               ServerPlayer winP = serverRef != null ? serverRef.getPlayerList().getPlayer(winner.uuid) : null;
               if (winP != null) {
                  Chat.msg(winP, "&aYou won the wager pool! &e" + Chat.moneyStr(pool) + " &ahas been added to your balance.");
                  Advancements.grant(winP, "pool_wager");
               }

               Participant loser = winner.uuid.equals(((Participant)d.parts.get(0)).uuid) ? (Participant)d.parts.get(1) : (Participant)d.parts.get(0);
               ServerPlayer loserP = serverRef != null ? serverRef.getPlayerList().getPlayer(loser.uuid) : null;
               if (loserP != null) {
                  Chat.msg(loserP, "&cYou lost the wager! &e" + Chat.moneyStr(pool) + " &cgoes to &f" + winner.displayName + "&c.");
               }
            } else {
               for (Bet bet : bets) {
                  boolean won = bet.target.equals(winner.uuid);
                  if (won) {
                     long payout = bet.amount * 2L;
                     EconomyManager.addCash(bet.bettor, payout);
                     ServerPlayer bettor = serverRef != null ? serverRef.getPlayerList().getPlayer(bet.bettor) : null;
                     if (bettor != null) {
                        Chat.msg(bettor, "&aYou won your wager! &e" + Chat.moneyStr(payout) + " &ahas been added to your balance.");
                     }
                  }
               }
            }
         }
      }
   }

   public static int betCount(Duel d) {
      if (d != null && d.parts.size() >= 2) {
         String key = betKey(((Participant)d.parts.get(0)).uuid, ((Participant)d.parts.get(1)).uuid);
         List<Bet> bets = key == null ? null : activeBets.get(key);
         return bets != null ? bets.size() : 0;
      } else {
         return 0;
      }
   }

   private DuelManager() {
   }

   public static boolean isDuelRealm(Level level) {
      return level != null && level.dimension().equals(DUEL_REALM);
   }

   public static boolean anyActiveDuelFight(Level level) {
      if (!isDuelRealm(level)) {
         return false;
      }

      for (Duel d : duels.values()) {
         if (d != null && d.phase == Phase.FIGHT) {
            return true;
         }
      }

      return false;
   }

   public static void onPlayerBlockPlaced(ServerLevel level, BlockPos pos, LivingEntity placer) {
      if (isDuelRealm(level)) {
         ServerPlayer p = placer instanceof ServerPlayer sp ? sp : null;
         Duel d = p != null ? duels.get(p.getUUID()) : null;
         if (d != null && d.phase == Phase.FIGHT) {
            d.playerPlacedBlocks.add(BlockPos.asLong(pos.getX(), pos.getY(), pos.getZ()));
         }
      }
   }

   public static void onTntPlaced(ServerLevel level, BlockPos pos, LivingEntity placer) {
      if (isDuelRealm(level)) {
         ServerPlayer p = placer instanceof ServerPlayer sp ? sp : null;
         Duel d = p != null ? duels.get(p.getUUID()) : null;
         if (d != null && d.phase == Phase.FIGHT) {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 11);
            PrimedTnt tnt = new PrimedTnt(level, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, placer);
            tnt.setFuse(40);
            level.addFreshEntity(tnt);
            level.playSound(null, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, SoundEvents.NOTE_BLOCK_PLING, SoundSource.BLOCKS, 1.0F, 0.5F);
         }
      }
   }

   /**
    * True when this fighter's mode lets them mine the map itself.
    *
    * <p>Every other arena is scenery: breaking it would be breaking the game. A
    * Gladiator map is the opposite - the ore in it <i>is</i> the kit - so its map
    * is breakable, and only its map.
    */
   public static boolean canBreakArenaMap(ServerPlayer p) {
      Duel d = duels.get(p.getUUID());
      return d != null && d.mode == DuelMode.GLADIATOR;
   }

   public static boolean isPlayerPlacedDuelBlock(ServerPlayer p, BlockPos pos) {
      Duel d = duels.get(p.getUUID());
      return d != null && d.phase == Phase.FIGHT ? d.playerPlacedBlocks.contains(BlockPos.asLong(pos.getX(), pos.getY(), pos.getZ())) : false;
   }

   public static boolean isPlayerPlacedInAnyDuel(ServerLevel level, BlockPos pos) {
      long packed = BlockPos.asLong(pos.getX(), pos.getY(), pos.getZ());

      for (Duel d : duels.values()) {
         if (d != null && d.phase == Phase.FIGHT && d.arena != null && level == getRealm(serverRef) && d.playerPlacedBlocks.contains(packed)) {
            return true;
         }
      }

      return false;
   }      /**
       * Books a landed blow against the match report. Wired to Fabric's
    * AFTER_DAMAGE, so it sees every hit that actually connected rather than only
    * the lethal ones, which is what makes the end-of-duel card mean anything.
    */
   public static void onDuelAfterDamage(LivingEntity victim, DamageSource source, float amount) {
      try {
         if (victim == null || source == null || amount <= 0.0F || victim.level().isClientSide()) {
            return;
         }
         // Everything is resolved off the VICTIM's duel rather than the attacker's:
         // the attacker may be a bot, which is a player-shaped entity that is not
         // in the per-player duel map.
         Duel d = duels.get(victim.getUUID());
         if (d == null || d.phase != Phase.FIGHT) {
            return;
         }
         if (!(victim instanceof ServerPlayer struckPlayer)) {
            return;
         }
         Participant struck = participantOf(d, struckPlayer);
         if (struck == null) {
            return;
         }
         ServerPlayer attacker = playerAttackerOf(source);
         recordHitLedger(d, attacker, struck, amount);
      } catch (Throwable t) {
         // Stats are a nicety; a fight must never break because a bookkeeping
         // lookup failed.
      }
   }

   /**
    * Books one landed blow: damage onto the victim, hits and damage onto the
    * attacker, for whatever the match report wants to say later.
    */
   private static void recordHitLedger(Duel d, ServerPlayer attacker, Participant struck, float amount) {
      if (d == null || struck == null || amount <= 0.0F) {
         return;
      }
      struck.damageTaken += amount;
      struck.wasHit = true;
      // The low point of the fight is recorded as it happens: whether somebody was ever
      // one hit from death cannot be recovered from the health they finish on.
      if (!struck.bot && struck.player != null && struck.player.getHealth() <= COMEBACK_HEALTH) {
         struck.nearDeath = true;
      }
      if (attacker == null) {
         return;
      }
      Participant hitter = participantOf(d, attacker);
      if (hitter != null && hitter != struck) {
         hitter.hitsLanded++;
         hitter.damageDealt += amount;
      }
   }

   public static boolean isInDuel(UUID uuid) {
      Duel d = duels.get(uuid);
      return d != null && d.phase != Phase.ENDED;
   }

   public static Duel getDuel(UUID uuid) {
      return duels.get(uuid);
   }

   public static boolean isLegacyFight(Entity entity) {
      if (entity instanceof Player p && isDuelRealm(p.level())) {
         Duel d = duels.get(p.getUUID());
         return d != null && d.phase == Phase.FIGHT && d.combat == CombatStyle.LEGACY;
      } else {
         return false;
      }
   }

   public static boolean isLegacySwordBlocking(Player p) {
      if (!p.isAlive()) {
         return false;
      } else {
         boolean isDistantSword = ModItems.isDistantMemorySword(p.getMainHandItem());
         // Only the Distant Memory sword and legacy 1.8 duels allow sword blocking.
         // Normal swords outside duels cannot block.
         if (!isDistantSword && !isLegacyFight(p)) {
            return false;
         }
         if (!isSword(p.getMainHandItem().getItem()) && !isDistantSword) {
            return false;
         } else {
            // Blocking is the native blocks_attacks state now (see SwordBlockManager) -
            // it starts on right-click and ends the moment the button is released.
            return p.isBlocking();
         }
      }
   }

   public static boolean markSwordBlock(ServerPlayer p) {
      boolean isDistantSword = p != null && ModItems.isDistantMemorySword(p.getMainHandItem());
      if (p != null && (isLegacyFight(p) || isDistantSword) && (isSword(p.getMainHandItem().getItem()) || isDistantSword)) {
         boolean wasBlocking = isLegacySwordBlocking(p);
         swordBlockHolding.put(p.getUUID(), System.currentTimeMillis());
         // Only the Distant Memory sword gets block VFX - regular sword blocking stays clean.
         if (!wasBlocking && isDistantSword && p.level() instanceof ServerLevel sl) {
            com.fortuneandfavors.net.FfVfx.particles(sl, ParticleTypes.ENCHANT, p.getX(), p.getY() + 1.2, p.getZ(), 10, 0.3, 0.4, 0.3, 0.05);
         }

         return true;
      } else {
         return false;
      }
   }

   public static void clearSwordBlock(ServerPlayer p) {
      if (p != null) {
         swordBlockHolding.remove(p.getUUID());
      }
   }

   private static void sweepTapBlocks() {
      long now = System.currentTimeMillis();
      swordBlockHolding.entrySet().removeIf(e -> now - e.getValue() > 10000L);
      if (serverRef != null) {
         for (ServerPlayer p : serverRef.getPlayerList().getPlayers()) {
            if (!isInDuel(p.getUUID())) {
               if (!ModItems.isDistantMemorySword(p.getMainHandItem())) {
                  Boolean sent = swordBlockSent.remove(p.getUUID());
                  if (Boolean.TRUE.equals(sent)) {
                     sendSwordBlockPacket(p, false);
                  }
               } else {
                  boolean blocking = isLegacySwordBlocking(p);
                  Boolean sent = swordBlockSent.get(p.getUUID());
                  boolean send = blocking ? !Boolean.TRUE.equals(sent) || now % 40L == 0L : Boolean.TRUE.equals(sent);
                  if (send) {
                     swordBlockSent.put(p.getUUID(), blocking);
                     sendSwordBlockPacket(p, blocking);
                  }
               }
            }
         }
      }
   }

   private static void sendSwordBlockPacket(ServerPlayer p, boolean active) {
      try {
         FfNet.send(p, new FfScreenFxPayload(4, active));
      } catch (Exception var3) {
      }
   }

   private static void clearSwordBlockByUuid(UUID uuid) {
      swordBlockHolding.remove(uuid);
   }

   private static void syncSwordBlock(Duel d, long now) {
      for (Participant part : d.parts) {
         if (!part.bot && part.player != null) {
            boolean blocking = d.combat == CombatStyle.LEGACY && isLegacySwordBlocking(part.player);
            Boolean sent = swordBlockSent.get(part.player.getUUID());
            boolean send = blocking ? !Boolean.TRUE.equals(sent) || now % 40L == 0L : Boolean.TRUE.equals(sent);
            if (send) {
               swordBlockSent.put(part.player.getUUID(), blocking);

               try {
                  FfNet.send(part.player, new FfScreenFxPayload(4, blocking));
               } catch (Exception var9) {
               }
            }
         }
      }
   }

   private static boolean isSword(Item item) {
      return item != null && (item.builtInRegistryHolder().is(ItemTags.SWORDS) || item == Items.MACE || item == Items.TRIDENT);
   }

   public static boolean isSwordLike(ItemStack stack) {
      return stack != null && !stack.isEmpty() && isSword(stack.getItem());
   }

   private static EquipmentSlot armorSlotFor(Item item) {
      if (item == null) {
         return null;
      } else if (item.builtInRegistryHolder().is(ItemTags.HEAD_ARMOR)) {
         return EquipmentSlot.HEAD;
      } else if (item.builtInRegistryHolder().is(ItemTags.CHEST_ARMOR)) {
         return EquipmentSlot.CHEST;
      } else if (item.builtInRegistryHolder().is(ItemTags.LEG_ARMOR)) {
         return EquipmentSlot.LEGS;
      } else {
         return item.builtInRegistryHolder().is(ItemTags.FOOT_ARMOR) ? EquipmentSlot.FEET : null;
      }
   }

   public static boolean allowedCommand(String command) {
      if (command == null) {
         return false;
      }

      String c = command.trim().toLowerCase(Locale.ROOT);
      if (c.startsWith("/")) {
         c = c.substring(1);
      }

      return c.equals("duel quit") || c.equals("duel cancel") || c.equals("cancel") || c.equals("duel vote legacy") || c.equals("duel vote modern");
   }

   public static String beginChallenge(ServerPlayer challenger, String targetName) {
      return beginChallenge(challenger, targetName, null);
   }

   public static String beginChallenge(ServerPlayer challenger, String targetName, BotDifficulty difficulty) {
      if (!ModConfig.is("duels")) {
         return "Duels are disabled on this server.";
      }

      if (challenger == null) {
         return "This command must be run by a player.";
      }

      if (isInDuel(challenger.getUUID())) {
         return "You're already in a duel - finish it first!";
      }

      boolean bot = targetName != null && targetName.equalsIgnoreCase("bot");
      if (!bot) {
         ServerPlayer target = challenger.level().getServer().getPlayerList().getPlayerByName(targetName);
         if (target == null || com.fortuneandfavors.economy.VanishManager.isVanished(target) && target != challenger) {
            return targetName + " isn't online right now.";
         }

         if (target == challenger) {
            return "You can't duel yourself.";
         }

         if (isInDuel(target.getUUID())) {
            return target.getName().getString() + " is already in a duel.";
         }
      }

      pendingPicks.put(challenger.getUUID(), new PendingPick(bot, targetName, difficulty));
      return null;
   }

   public static String confirmChallenge(ServerPlayer challenger, DuelMode mode) {
      return confirmChallenge(challenger, mode, null);
   }

   public static String confirmChallenge(ServerPlayer challenger, DuelMode mode, DuelMode baseMode) {
      PendingPick pick = pendingPicks.remove(challenger.getUUID());
      if (pick == null) {
         return "No pending challenge - try /duel <player> again.";
      }

      CombatStyle style = null;
      if (mode == DuelMode.UHCDUEL) {
         style = baseMode == DuelMode.MODERN ? CombatStyle.MODERN : CombatStyle.LEGACY;
      }

      if (pick.bot) {
         return requestBotDuel(challenger, mode, pick.difficulty, style);
      }

      ServerPlayer target = challenger.level().getServer().getPlayerList().getPlayerByName(pick.targetName);
      return target == null ? "That player went offline." : requestDuel(challenger, mode, target, style);
   }

   public static String requestDuel(ServerPlayer challenger, DuelMode mode, ServerPlayer target) {
      return requestDuel(challenger, mode, target, null);
   }

   private static String requestDuel(ServerPlayer challenger, DuelMode mode, ServerPlayer target, CombatStyle style) {
      if (!ModConfig.is("duels")) {
         return "Duels are disabled on this server.";
      }

      if (challenger == null || target == null || challenger == target) {
         return "You can't duel yourself.";
      }

      if (isInDuel(challenger.getUUID())) {
         return "You're already in a duel - finish it first!";
      }

      if (isInDuel(target.getUUID())) {
         return target.getName().getString() + " is already in a duel.";
      }

      challenges.put(target.getUUID(), new Challenge(challenger.getUUID(), mode, ServerClock.clock(challenger.level()) + 1200));
      Chat.msg(target, "&6&l" + challenger.getName().getString() + "&r&7 challenges you to &e" + mode.display + "&7! Type &a/duel accept&7 or &c/duel deny&7.");
      Chat.msg(challenger, "&7Challenge sent to &f" + target.getName().getString() + "&7 - they have 60 seconds to accept.");
      challenger.level()
         .playSound(null, challenger.getX(), challenger.getY(), challenger.getZ(), SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 1.0F, 1.4F);
      return null;
   }

   public static String requestBotDuel(ServerPlayer challenger, DuelMode mode) {
      return requestBotDuel(challenger, mode, null);
   }

   public static String requestBotDuel(ServerPlayer challenger, DuelMode mode, BotDifficulty difficulty) {
      return requestBotDuel(challenger, mode, difficulty, null);
   }

   private static String requestBotDuel(ServerPlayer challenger, DuelMode mode, BotDifficulty difficulty, CombatStyle style) {
      if (!ModConfig.is("duels")) {
         return "Duels are disabled on this server.";
      }

      if (challenger == null) {
         return "This command must be run by a player.";
      }

      if (isInDuel(challenger.getUUID())) {
         return "You're already in a duel - finish it first!";
      }

      Duel d = createDuel(challenger, null, mode, difficulty, style);
      if (d == null) {
         return "The arena refused to open - try again.";
      }

      String botName = d.bot != null && d.bot.getCustomName() != null ? d.bot.getCustomName().getString() : "The bot";
      String diffMsg = difficulty != null ? " (&e" + difficulty.display + "&7)" : "";
      Chat.msg(challenger, "&7A &f" + botName + diffMsg + "&7 steps into the arena for &e" + mode.display + "&7.");
      return null;
   }

   public static String accept(ServerPlayer p) {
      Challenge c = challenges.remove(p.getUUID());
      if (c == null) {
         return "You have no pending duel challenge.";
      }

      ServerPlayer challenger = p.level().getServer().getPlayerList().getPlayer(c.challenger);
      if (challenger == null) {
         return "Your challenger went offline - the duel is cancelled.";
      }

      if (isInDuel(challenger.getUUID())) {
         return "Your challenger is already in another duel.";
      }

      Duel d = createDuel(challenger, p, c.mode);
      if (d == null) {
         return "The arena refused to open - try again.";
      }

      Chat.msg(challenger, "&a" + p.getName().getString() + " accepted the duel - entering the arena!");
      return null;
   }

   public static String deny(ServerPlayer p) {
      Challenge c = challenges.remove(p.getUUID());
      if (c == null) {
         return "You have no pending duel challenge.";
      }

      ServerPlayer challenger = p.level().getServer().getPlayerList().getPlayer(c.challenger);
      if (challenger != null) {
         Chat.msg(challenger, "&c" + p.getName().getString() + " declined your duel challenge.");
      }

      return null;
   }

   /**
    * The duel system's live state, as lines an admin can read.
    *
    * <p>Everything here is a question the bug reports actually turn on. How many
    * plots the realm thinks are in use, and which - a leaked plot is what "the arena
    * is broken" usually is, because the next match claims the plot next door and
    * inherits a half-swept floor. Whether the arena's own blocks are really there,
    * counted at the floor level rather than assumed. Which phase each fight is in,
    * how long it has been there, and whether every fighter has both a live saved
    * state and a durable snapshot - the two things that decide whether leaving a duel
    * gives your inventory back.
    */
   public static List<String> diagnose(ServerPlayer viewer) {
      List<String> out = new ArrayList<>();
      MinecraftServer server = viewer.level().getServer();
      ServerLevel realm = getRealm(server);
      out.add("§6§lDuel diagnostics");

      if (realm == null) {
         out.add("§cThe duel realm §f" + DUEL_REALM + "§c does not exist - every duel will be refused.");
         return out;
      }

      out.add("§7Realm: §f" + DUEL_REALM + "§7  plots in use: §f" + usedPlots.size() + "§7/64");
      if (!usedPlots.isEmpty()) {
         List<Integer> plots = new ArrayList<>(usedPlots);
         java.util.Collections.sort(plots);
         out.add("§7Claimed plots: §f" + plots);
      }

      if (startupSweepPlot >= 0 && startupSweepPlot < sweepQueue.size()) {
         out.add(
            "§7Leftover-arena sweep: §estill running§7 - plot §f" + sweepQueue.get(startupSweepPlot)
               + "§7 (§f" + (startupSweepPlot + 1) + "§7/§f" + sweepQueue.size() + "§7), chunk §f" + startupSweepChunk
         );
      } else {
         out.add("§7Leftover-arena sweep: §adone§7. Plots still marked: §f" + dirtyPlots.size());
      }

      Set<Duel> seen = new HashSet<>();
      List<Duel> active = new ArrayList<>();
      for (Duel d : duels.values()) {
         if (d != null && seen.add(d)) {
            active.add(d);
         }
      }

      if (active.isEmpty()) {
         out.add("§7No duel is running.");
      }

      for (Duel d : active) {
         int plot = d.arena.ox >= 16 ? (d.arena.ox - 16) / 200 + ((d.arena.oz - 16) / 200) * 8 : -1;
         out.add("§8--------------------");
         out.add(
            "§7Duel §f"
               + d.mode.display
               + "§7  phase §f"
               + d.phase
               + "§7  plot §f"
               + plot
               + (d.ffa ? "§7  §dFFA" : "")
         );
         out.add("§7Combat: §f" + d.combat + "§7   finalised: §f" + d.finalized + "§7   finalizeAt: §f" + d.finalizeAt);

         // The floor is where the arena visibly is or is not. Counting non-air blocks
         // on one layer is a few thousand reads and answers the question directly.
         // The layer is the one the build digest settled on whenever there is one,
         // so this counts exactly the blocks the report counts.
         Arena ar = d.arena;
         int floorY = ar.digest != null ? ar.digestY : (ar.voidY != 0 ? ar.voidY + 1 : 100);
         int solid = 0;
         int holes = 0;
         int minX = Math.min(ar.x0, ar.ox);
         int maxX = Math.max(ar.x1, ar.ox + 43);
         int minZ = Math.min(ar.z0, ar.oz);
         int maxZ = Math.max(ar.z1, ar.oz + 43);
         BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
         for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
               probe.set(x, floorY, z);
               if (realm.getBlockState(probe).isAir()) {
                  holes++;
               } else {
                  solid++;
               }
            }
         }

         out.add("§7Arena box: §f" + minX + "," + minZ + "§7 to §f" + maxX + "," + maxZ + "§7  floor y=§f" + floorY);
         out.add("§7Floor blocks: §a" + solid + "§7 solid, §e" + holes + "§7 air" + (holes > 0 ? " §8(a partially built or swept floor)" : ""));

         // The build digest and the map of it. The map is here rather than only in
         // the file because "the arena does not render" is usually answered by
         // seeing whether there is anything there at all, and that should not need
         // a second command.
         if (ar.digest == null) {
            out.add("§7Build digest: §enone recorded§7 - this arena was never captured, so there is nothing to compare a client against.");
         } else {
            int[] now = realm == null ? null : readDigestLayer(realm, ar);
            int nowSolid = 0;
            int changed = 0;
            if (now != null) {
               for (int i = 0; i < now.length; i++) {
                  if (now[i] != 0) {
                     nowSolid++;
                  }
                  if (now[i] != ar.digest[i]) {
                     changed++;
                  }
               }
            }

            out.add(
               "§7Build digest: §fy="
                  + ar.digestY
                  + "§7 §f"
                  + ar.digestW
                  + 'x'
                  + ar.digestH
                  + "§7 §a"
                  + ar.digestSolid
                  + "§7 solid, hash §f"
                  + ar.digestHash
            );
            out.add(
               "§7Since built: §a"
                  + nowSolid
                  + "§7 solid, "
                  + (changed == 0 ? "§anothing touched" : "§e" + changed + " block(s) changed §8(the arena has been modified)")
            );
            String path = writeDiagReport(realm, d, null, null);
            out.add("§7Report: §f" + (path != null ? path : "(could not write)"));
            out.add("§8/duel diag probe - ask your own client what it holds for this floor");
            out.addAll(digestChatMap(ar));
         }

         for (Participant part : d.parts) {
            ServerPlayer p = part.bot ? null : server.getPlayerList().getPlayer(part.uuid);
            String name = part.displayName != null ? part.displayName : String.valueOf(part.uuid);
            StringBuilder flags = new StringBuilder();
            flags.append(part.bot ? "§8[bot]" : part.eliminated ? "§c[out]" : "§a[in]");
            flags.append(p == null ? "§c online:no" : "§a online:yes");
            flags.append(part.saved == null ? "§c saved:none" : "§a saved:yes");
            if (!part.bot) {
               int snap = com.fortuneandfavors.util.LastInventoryHolder.previewCount(
                  p != null ? p : null
               );
               flags.append(snap >= 0 ? "§a snapshot:" + snap : "§e snapshot:none");
               if (p == null) {
                  flags.append(pendingRestore.containsKey(part.uuid) ? "§a exit-state:queued" : "§c exit-state:MISSING");
               }
            }

            out.add("§7Fighter §f" + name + "§7  " + flags);
         }
      }

      if (spectatorSessions.size() > 0) {
         out.add("§7Spectators: §f" + spectatorSessions.size());
      }

      if (pendingTeardowns.size() > 0) {
         out.add("§eArena teardowns still queued: §f" + pendingTeardowns.size() + "§7 (the tick loop drains three a tick)");
      }

      return out;
   }

   // ------------------------------------------------------------ floor digest

   /** The characters a printed grid draws a block with, handed out in the order
    *  the legend needs them. Air is always '.', and everything else gets the
    *  next letter, which the legend in the report then names. */
   private static final String DIGEST_PALETTE = "#BMWCGSLODNQRTPKJHFEUAXVYZbcdefghijklmnopqrstuvwxyz0123456789";

   /** Stands in for a block in a chunk the client has never received. */
   private static final char DIGEST_MISSING = '?';

   /** Stands in for a block the server has and the client does not, or the
    *  other way round. */
   private static final char DIGEST_DIFF = '!';

   /** How far above the void line the real floor is looked for. Gladiator puts
    *  its bedrock at y=98 with a void line of 96, so "one above the void" - what
    *  this used to assume - landed on air and reported a fully built world as an
    *  empty floor. */
   private static final int FLOOR_SEARCH_DEPTH = 11;

   /** How much of a grid the chat is willing to show. A wide arena is sampled
    *  rather than cut off, so it appears whole and coarse instead of half. */
   private static final int DIGEST_CHAT_COLS = 62;
   private static final int DIGEST_CHAT_ROWS = 22;

   /** Probes waiting on a client's answer, by the operator who asked. */
   private static final Map<UUID, PendingProbe> pendingProbes = new ConcurrentHashMap<>();

   private record PendingProbe(int id, Duel duel, long sentAt) {
   }

   /** The plot an arena was carved out of. A leaked plot is what "the arena is
    *  broken" usually turns out to be, so it is worth being able to say it. */
   public static int plotOf(Arena a) {
      return a.ox >= 16 ? (a.ox - 16) / 200 + ((a.oz - 16) / 200) * 8 : -1;
   }

   /** The floor map as the chat shows it: sampled until it fits, with a short
    *  legend so the letters mean something without opening the file. */
   private static List<String> digestChatMap(Arena a) {
      List<String> out = new ArrayList<>();
      int step = Math.max(
         1,
         Math.max(
            (a.digestW + DIGEST_CHAT_COLS - 1) / DIGEST_CHAT_COLS, (a.digestH + DIGEST_CHAT_ROWS - 1) / DIGEST_CHAT_ROWS
         )
      );
      Map<Integer, Character> legend = legendFor(a.digest);
      out.add("§8floor y=" + a.digestY + "§8 (" + (step > 1 ? "sampled every " + step + " blocks" : "every block") + "):");
      StringBuilder leg = new StringBuilder("§8legend: ");
      int shown = 0;

      for (Entry<Integer, Character> e : legend.entrySet()) {
         if (shown == 8) {
            leg.append("§8...");
            break;
         }

         leg.append("§f").append(e.getValue()).append("§8=").append(digestName(e.getKey())).append(' ');
         shown++;
      }

      out.add(leg.toString());
      out.addAll(gridLines(a, a.digest, legend, null, step, DIGEST_CHAT_ROWS, true));
      return out;
   }

   /** Picks the layer of a vertical run that most looks like a floor: the one
    *  holding the most non-air blocks. A tie keeps the lowest layer, which is
    *  what leaves a plain arena on its own floor rather than on the barrier ring
    *  one block above it. Pure, so the self-test can pin it. */
   public static int bestFloorLayer(int[] solidPerLayer) {
      int best = 0;
      int bestCount = -1;

      for (int i = 0; i < solidPerLayer.length; i++) {
         if (solidPerLayer[i] > bestCount) {
            bestCount = solidPerLayer[i];
            best = i;
         }
      }

      return best;
   }

   /** FNV-1a over a digest, so two reports of the same arena can be compared by
    *  eye instead of by diffing thousands of characters. Pure, so the self-test
    *  can pin it. */
   public static String digestHash(int[] ids) {
      int h = -2128831035;

      for (int id : ids) {
         h ^= id;
         h *= 16777619;
      }

      return String.format("%08x", h & 4294967295L);
   }

   /** The block a state id names, for the legend. */
   private static String digestName(int id) {
      try {
         net.minecraft.world.level.block.state.BlockState s = Block.stateById(id);
         return s == null
            ? "state#" + id
            : net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString();
      } catch (Exception e) {
         return "state#" + id;
      }
   }

   /** Hands every block state present on the layer a letter, in first-seen
    *  order. Air always draws as '.', because it is both the most common cell
    *  and the one the eye is looking for. */
   private static Map<Integer, Character> legendFor(int[]... sets) {
      Map<Integer, Character> legend = new LinkedHashMap<>();
      int next = 0;

      for (int[] set : sets) {
         if (set == null) {
            continue;
         }

         for (int id : set) {
            if (id < 0 || legend.containsKey(id)) {
               continue;
            }

            legend.put(id, next < DIGEST_PALETTE.length() ? DIGEST_PALETTE.charAt(next++) : DIGEST_MISSING);
         }
      }

      return legend;
   }

   private static char digestChar(int id, Map<Integer, Character> legend) {
      if (id == FfArenaProbePayload.ID_UNLOADED) {
         return DIGEST_MISSING;
      }

      Character c = legend.get(id);
      return c != null ? c : DIGEST_MISSING;
   }

   /** Reads the arena's digest layer as it stands right now. */
   private static int[] readDigestLayer(ServerLevel realm, Arena a) {
      if (a.digest == null) {
         return null;
      }

      int[] now = new int[a.digest.length];
      BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

      for (int xi = 0; xi < a.digestW; xi++) {
         for (int zi = 0; zi < a.digestH; zi++) {
            pos.set(a.digestX0 + xi, a.digestY, a.digestZ0 + zi);
            now[xi * a.digestH + zi] = Block.getId(realm.getBlockState(pos));
         }
      }

      return now;
   }

   /**
    * Records what the arena's floor is, block by block, the moment it has been
    * built - and writes the report.
    *
    * <p>This has to happen now rather than when someone complains: the useful
    * question later is what the floor <em>was</em>, and by the time anyone runs
    * a diagnostic the arena may have been mined, blown up or walked on. The
    * layer is found rather than assumed, because "one above the void" is right
    * for a flat arena and wrong for a world with terrain in it.
    */
   private static void captureFloor(ServerLevel realm, Duel d) {
      Arena a = d.arena;
      if (a == null || a.x1 < a.x0 || a.z1 < a.z0) {
         return;
      }

      int w = a.x1 - a.x0 + 1;
      int h = a.z1 - a.z0 + 1;
      if ((long)w * h > FfArenaProbePayload.MAX_FLOOR) {
         a.digest = null;
         return;
      }

      BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
      int[] solidPerLayer = new int[FLOOR_SEARCH_DEPTH];

      for (int i = 0; i < FLOOR_SEARCH_DEPTH; i++) {
         int y = a.voidY + 1 + i;
         int solid = 0;

         for (int x = a.x0; x <= a.x1; x++) {
            for (int z = a.z0; z <= a.z1; z++) {
               pos.set(x, y, z);
               if (!realm.getBlockState(pos).isAir()) {
                  solid++;
               }
            }
         }

         solidPerLayer[i] = solid;
      }

      int y = a.voidY + 1 + bestFloorLayer(solidPerLayer);
      int[] ids = new int[w * h];
      int solid = 0;

      for (int xi = 0; xi < w; xi++) {
         for (int zi = 0; zi < h; zi++) {
            pos.set(a.x0 + xi, y, a.z0 + zi);
            int id = Block.getId(realm.getBlockState(pos));
            ids[xi * h + zi] = id;
            if (id != 0) {
               solid++;
            }
         }
      }

      a.digestY = y;
      a.digestX0 = a.x0;
      a.digestZ0 = a.z0;
      a.digestW = w;
      a.digestH = h;
      a.digest = ids;
      a.digestSolid = solid;
      a.digestHash = digestHash(ids);
      a.reportPath = writeDiagReport(realm, d, null, null);
      FortuneFavorsMod.LOGGER.info(
         "Fortune & Favors: arena floor digest - {} plot {}, layer y={}, {}x{} ({} solid of {}), digest {}, report {}",
         d.mode.display,
         plotOf(a),
         y,
         w,
         h,
         solid,
         ids.length,
         a.digestHash,
         a.reportPath
      );
   }

   /**
    * Writes the report: what was built, what is there now, and - once a probe
    * has run - what the client actually received, block by block.
    *
    * <p>The grids line up, so the reading is direct. A cell that is solid in
    * <em>built</em> and air in <em>client</em> is a block the server ordered and
    * the client never got; a cell that is {@code ?} in <em>client</em> is inside
    * a chunk it never received at all. Telling those two apart is the whole
    * reason the probe exists.
    *
    * @return the path written, or null when there is nothing to report
    */
   private static String writeDiagReport(ServerLevel realm, Duel d, int[] client, String clientNote) {
      Arena a = d.arena;
      if (a == null || a.digest == null) {
         return null;
      }

      int[] now = realm == null ? null : readDigestLayer(realm, a);
      Map<Integer, Character> legend = legendFor(a.digest, now, client);
      StringBuilder sb = new StringBuilder(1 << 16);
      sb.append("# Fortune & Favors - duel arena floor report\n");
      sb.append("# written ")
         .append(new java.util.Date())
         .append('\n');
      sb.append("# mode ").append(d.mode.display).append("   plot ").append(plotOf(a)).append("   ffa ").append(d.ffa).append('\n');
      sb.append("# realm ")
         .append(DUEL_REALM)
         .append("   layer y=")
         .append(a.digestY)
         .append("   bounds x ")
         .append(a.digestX0)
         .append("..")
         .append(a.digestX0 + a.digestW - 1)
         .append("  z ")
         .append(a.digestZ0)
         .append("..")
         .append(a.digestZ0 + a.digestH - 1)
         .append('\n');
      sb.append("# built: ")
         .append(a.digestSolid)
         .append(" solid of ")
         .append(a.digest.length)
         .append("   digest ")
         .append(a.digestHash)
         .append('\n');

      int nowSolid = 0;
      int drifted = 0;
      if (now != null) {
         for (int i = 0; i < now.length; i++) {
            if (now[i] != 0) {
               nowSolid++;
            }
            if (now[i] != a.digest[i]) {
               drifted++;
            }
         }
      }

      sb.append("# now:   ")
         .append(nowSolid)
         .append(" solid, ")
         .append(drifted)
         .append(drifted > 0 ? " block(s) changed since the build" : " unchanged since the build")
         .append('\n');

      if (client != null) {
         int same = 0;
         int differ = 0;
         int unloaded = 0;

         for (int i = 0; i < client.length && i < a.digest.length; i++) {
            if (client[i] == FfArenaProbePayload.ID_UNLOADED) {
               unloaded++;
            } else if (client[i] == a.digest[i]) {
               same++;
            } else {
               differ++;
            }
         }

         sb.append("# client: ")
            .append(same)
            .append(" same, ")
            .append(differ)
            .append(" different, ")
            .append(unloaded)
            .append(" in chunks the client does not have\n");
         if (clientNote != null && !clientNote.isEmpty()) {
            sb.append("# client note: ").append(clientNote).append('\n');
         }
      }

      sb.append("# legend: ");

      for (Entry<Integer, Character> e : legend.entrySet()) {
         sb.append(e.getValue()).append('=').append(digestName(e.getKey())).append(' ');
      }

      if (client != null) {
         sb.append(DIGEST_MISSING).append("=no-chunk ").append(DIGEST_DIFF).append("=differs");
      }

      sb.append('\n');
      sb.append("\n=== built ===\n");
      appendGrid(sb, a, a.digest, legend);
      if (now != null) {
         sb.append("\n=== now ===\n");
         appendGrid(sb, a, now, legend);
      }

      if (client != null) {
         sb.append("\n=== client ===\n");
         appendGrid(sb, a, client, legend);
         sb.append("\n=== diff (built vs client) ===\n");
         appendGrid(sb, a, client, legendFor(a.digest), a.digest);
      }

      Path file = serverRef != null
         ? serverRef.getWorldPath(LevelResource.ROOT)
            .resolve("fortuneandfavors")
            .resolve("duel-diag-" + d.mode.name().toLowerCase(Locale.ROOT) + "-plot" + plotOf(a) + ".txt")
         : null;

      if (file == null) {
         return null;
      }

      try {
         java.nio.file.Files.createDirectories(file.getParent());
         java.nio.file.Files.writeString(file, sb.toString());
         return file.toString();
      } catch (Exception e) {
         FortuneFavorsMod.LOGGER.warn("Fortune & Favors: could not write the arena report", e);
         return null;
      }
   }

   private static void appendGrid(StringBuilder sb, Arena a, int[] ids, Map<Integer, Character> legend) {
      appendGrid(sb, a, ids, legend, null);
   }

   private static void appendGrid(StringBuilder sb, Arena a, int[] ids, Map<Integer, Character> legend, int[] against) {
      for (String line : gridLines(a, ids, legend, against, 1, Integer.MAX_VALUE, false)) {
         sb.append(line).append('\n');
      }
   }

   /**
    * Renders a layer, one character per block, sampled by {@code step} so a wide
    * world still fits. A non-null {@code against} switches the render to a
    * difference view, where the only mark drawn is a cell the two disagree on.
    *
    * <p>Colour is opt-in because the same renderer feeds both the chat and the
    * file, and section signs are noise in a plain text report.
    */
   private static List<String> gridLines(
      Arena a, int[] ids, Map<Integer, Character> legend, int[] against, int step, int maxRows, boolean colors
   ) {
      List<String> out = new ArrayList<>();
      if (a == null || ids == null || step < 1) {
         return out;
      }

      int rows = 0;
      int widthPerRow = (a.digestW + step - 1) / step;

      for (int zi = 0; zi < a.digestH && rows < maxRows; zi += step) {
         StringBuilder row = new StringBuilder(widthPerRow + 12);
         if (colors) {
            row.append("§7");
         }

         row.append(String.format("%4d ", a.digestZ0 + zi));
         if (colors) {
            row.append("§f");
         }

         for (int xi = 0; xi < a.digestW; xi += step) {
            int i = xi * a.digestH + zi;
            if (i < 0 || i >= ids.length) {
               row.append(' ');
            } else if (against == null) {
               row.append(digestChar(ids[i], legend));
            } else if (i >= against.length || ids[i] == FfArenaProbePayload.ID_UNLOADED) {
               row.append(DIGEST_MISSING);
            } else if (ids[i] != against[i]) {
               row.append(DIGEST_DIFF);
            } else {
               row.append('.');
            }
         }

         out.add(row.toString());
         rows++;
      }

      return out;
   }

   /** Asks the client at this keyboard what it actually holds for an arena
    *  floor, and prints what came back. */
   public static List<String> probe(ServerPlayer p, int wantPlot) {
      List<String> out = new ArrayList<>();
      MinecraftServer server = p.level().getServer();
      ServerLevel realm = getRealm(server);

      if (realm == null) {
         out.add("§cThe duel realm §f" + DUEL_REALM + "§c does not exist - there is nothing to probe.");
         return out;
      }

      Duel target = null;
      Set<Duel> seen = new HashSet<>();

      for (Duel d : duels.values()) {
         if (d == null || d.arena == null || !seen.add(d)) {
            continue;
         }

         if (wantPlot < 0 || plotOf(d.arena) == wantPlot) {
            target = d;
            break;
         }
      }

      if (target == null) {
         out.add(wantPlot < 0 ? "§cNo duel is running, so there is no floor to probe." : "§cNo duel is running in plot §f" + wantPlot + "§c.");
         return out;
      }

      Arena a = target.arena;
      if (a.digest == null) {
         // An arena that was never captured - a fight loaded from disk, or one
         // built before this existed. Take it now: what the floor is at this
         // instant is still worth comparing against the client.
         Duel capture = target;
         Safe.run("arena floor digest on demand", () -> captureFloor(realm, capture));
      }

      if (a.digest == null) {
         out.add("§cThis arena has no floor digest - its footprint was too large or it was never built.");
         return out;
      }

      int id = (int)(System.nanoTime() & 2147483647L);
      FfArenaProbePayload dump = new FfArenaProbePayload(
         FfArenaProbePayload.ACTION_DUMP,
         id,
         a.digestX0,
         a.digestZ0,
         a.digestX0 + a.digestW - 1,
         a.digestZ0 + a.digestH - 1,
         a.digestY,
         FfArenaProbePayload.packIds(a.digest),
         target.mode.display + " plot " + plotOf(a)
      );

      if (!FfNet.send(p, dump)) {
         out.add("§cYour client has not got the Fortune & Favors channel, so it cannot report what it received.");
         out.add("§7The dump is still on disk: §f" + a.reportPath);
         return out;
      }

      pendingProbes.put(p.getUUID(), new PendingProbe(id, target, System.currentTimeMillis()));
      out.add(
         "§7Asked your client for §f"
            + a.digestW
            + 'x'
            + a.digestH
            + "§7 blocks at y=§f"
            + a.digestY
            + "§7 (§f"
            + a.digestSolid
            + "§7 solid). The answer follows in a moment."
      );
      out.add("§8Before the client answers, the build-time dump is at §7" + a.reportPath);
      return out;
   }

   /** The client's answer to {@link #probe}: compares it against the build, says
    *  which of the two kinds of failure this is, and folds it into the report. */
   public static void handleProbeReply(ServerPlayer p, FfArenaProbePayload payload) {
      PendingProbe pending = p == null ? null : pendingProbes.remove(p.getUUID());
      if (pending == null) {
         FortuneFavorsMod.LOGGER.info(
            "Fortune & Favors: an arena probe answered with nothing outstanding - ignored"
         );
         return;
      }

      if (payload.probe() != pending.id()) {
         FortuneFavorsMod.LOGGER.info("Fortune & Favors: a stale arena probe answer was ignored");
         return;
      }

      Duel d = pending.duel();
      Arena a = d == null ? null : d.arena;
      if (a == null || a.digest == null) {
         Chat.msg(p, "§cThat arena has no digest to compare against any more.");
         return;
      }

      int cells = payload.cells();
      int[] client = FfArenaProbePayload.unpackIds(payload.ids(), cells);

      if (client == null || cells != a.digest.length) {
         Chat.msg(
            p,
            "§cThe client's answer did not decode (" + cells + " cells, expected " + a.digest.length + ") - build mismatch or a corrupt packet."
         );
         return;
      }

      int same = 0;
      int differ = 0;
      int unloaded = 0;
      int solidOnClient = 0;
      List<String> examples = new ArrayList<>();

      for (int xi = 0; xi < a.digestW; xi++) {
         for (int zi = 0; zi < a.digestH; zi++) {
            int i = xi * a.digestH + zi;
            int got = client[i];

            if (got == FfArenaProbePayload.ID_UNLOADED) {
               unloaded++;
               continue;
            }

            if (got != 0) {
               solidOnClient++;
            }

            if (got == a.digest[i]) {
               same++;
            } else {
               differ++;
               if (examples.size() < 12) {
                  examples.add(
                     "§7  ("
                        + (a.digestX0 + xi)
                        + ','
                        + a.digestY
                        + ','
                        + (a.digestZ0 + zi)
                        + ") server=§f"
                        + digestName(a.digest[i])
                        + "§7 client=§f"
                        + digestName(got)
                  );
               }
            }
         }
      }

      String path = writeDiagReport(getRealm(p.level().getServer()), d, client, payload.note());

      Chat.msg(p, "§6§lClient floor comparison - " + d.mode.display + " plot " + plotOf(a));
      if (payload.note() != null && !payload.note().isEmpty()) {
         Chat.msg(p, "§7Client says: §f" + payload.note());
      }

      Chat.msg(
         p,
         "§7Compared §f"
            + same
            + "§7 blocks at y=§f"
            + a.digestY
            + "§7: §a"
            + same
            + " match§7, §c"
            + differ
            + " differ§7, §e"
            + unloaded
            + " in chunks the client never received."
      );
      Chat.msg(
         p,
         "§7Client draws §f"
            + solidOnClient
            + "§7 solid here; the server built §f"
            + a.digestSolid
            + "§7."
      );

      if (differ == 0 && unloaded == 0) {
         Chat.msg(p, "§aThe client has the arena exactly as it was built - the render problem is not a missing chunk.");
      } else if (unloaded == a.digest.length) {
         Chat.msg(p, "§cThe client has none of this plot - it never received the arena at all. That is a chunk-send problem, not a build problem.");
      } else if (unloaded > 0) {
         Chat.msg(p, "§eThe client is missing §f" + unloaded + "§e of " + cells + " blocks entirely - those chunks never arrived.");
      } else {
         Chat.msg(p, "§cEvery block arrived and §f" + differ + "§c of them are wrong - so the build and the send agree, and something is rewriting them.");
      }

      for (String line : examples) {
         Chat.msg(p, line);
      }

      if (examples.size() == 12 && differ > 12) {
         Chat.msg(p, "§8... and " + (differ - 12) + " more, all of them in the report.");
      }

      Chat.msg(p, path != null ? "§7Three-panel report: §f" + path : "§7The report could not be written.");
   }

   public static String quit(ServerPlayer p) {
      Duel d = duels.get(p.getUUID());
      if (d == null || d.phase == Phase.ENDED) {
         return "You're not in a duel.";
      }

      if (d.ffa) {
         Participant part = participantOf(d, p);
         if (part != null && !part.eliminated) {
            handleFfaElimination(d, part, p, p.level().damageSources().genericKill());
            return null;
         } else {
            return "You're already out of this FFA.";
         }
      } else {
         Participant other = otherParticipant(d, p);
         String msg = "&c"
            + p.getName().getString()
            + " forfeited the duel"
            + (other != null && other.displayName != null ? " - &a" + other.displayName + "&c wins!" : "!");
         endDuel(d, other, msg);
         return null;
      }
   }

   public static String rematch(ServerPlayer p) {
      if (!ModConfig.is("duels")) {
         return "Duels are disabled on this server.";
      } else if (p == null) {
         return "This command must be run by a player.";
      } else if (isInDuel(p.getUUID())) {
         return "You're already in a duel - finish it first!";
      } else {
         LastDuel last = lastDuels.get(p.getUUID());
         if (last == null) {
            return "You have no recent duel to rematch! Try /duel <player> instead.";
         } else {
            ServerPlayer target = p.level().getServer().getPlayerList().getPlayer(last.opponent);
            if (target == null) {
               return "Your last opponent isn't online right now.";
            } else if (target == p) {
               return "You can't rematch yourself.";
            } else {
               return isInDuel(target.getUUID()) ? target.getName().getString() + " is already in a duel." : requestDuel(p, last.mode, target);
            }
         }
      }
   }

   public static String vote(ServerPlayer p, CombatStyle style) {
      Duel d = duels.get(p.getUUID());
      if (d != null && d.phase == Phase.LOBBY) {
         Participant part = participantOf(d, p);
         if (part != null && !part.voted) {
            part.voted = true;
            part.voteLegacy = style == CombatStyle.LEGACY;
            if (style == CombatStyle.LEGACY) {
               d.voteLegacy++;
            } else {
               d.voteModern++;
            }

            String styleName = style == CombatStyle.LEGACY ? "§c1.8 Legacy combat" : "§bModern combat";
            announce(d, "&f" + p.getName().getString() + "&7 voted for " + styleName + "&7 (" + (d.voteLegacy + d.voteModern) + "/2 votes).");
            if (d.voteLegacy + d.voteModern >= 2) {
               d.combat = d.voteLegacy > d.voteModern ? CombatStyle.LEGACY : CombatStyle.MODERN;
               startCountdown(d);
            }

            return null;
         } else {
            return "You already voted.";
         }
      } else {
         return "There's no vote running right now.";
      }
   }

   public static String setKit(ServerPlayer p, String kit) {
      Duel d = duels.get(p.getUUID());
      if (d != null && (d.phase == Phase.LOBBY || d.phase == Phase.COUNTDOWN)) {
         if (d.mode == DuelMode.KITS) {
            if (!isValidFunKit(kit)) {
               return "That's not a Kits kit.";
            }

            Participant part = participantOf(d, p);
            if (part == null) {
               return null;
            }

            part.kit = kit;
            part.kitChosen = true;
            Chat.msg(p, "&7Kits kit set to &e" + kit + "&7 - good luck!");
            return null;
         } else {
            if (d.mode != DuelMode.SKYWARS) {
               return "Kits are only used in Sky Wars.";
            }

            Participant part = participantOf(d, p);
            if (part == null) {
               return null;
            }

            part.kit = kit;
            Chat.msg(p, "&7Sky Wars kit set to &e" + kitName(kit) + "&7.");
            return null;
         }
      } else {
         return "Pick your kit before the fight starts.";
      }
   }

   private static boolean isValidFunKit(String kit) {
      if (kit == null) {
         return false;
      }

      for (String k : KIT_NAMES) {
         if (k.equalsIgnoreCase(kit)) {
            return true;
         }
      }

      return false;
   }

   private static void giveKitSelector(ServerPlayer p) {
      ItemStack star = new ItemStack(Items.NETHER_STAR);
      star.set(DataComponents.CUSTOM_NAME, Component.literal("§e§lChoose Class"));
      star.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("§7Right-click to pick your Sky Wars kit"))));
      p.getInventory().setItem(8, star);
   }

   private static void giveKitsKitSelector(ServerPlayer p) {
      ItemStack star = new ItemStack(Items.NETHER_STAR);
      star.set(DataComponents.CUSTOM_NAME, Component.literal("§d§lPick Your Kit"));
      star.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("§7Right-click to pick your fun kit"))));
      p.getInventory().setItem(8, star);
   }

   public static boolean tryOpenSkywarsKitMenu(ServerPlayer p, ItemStack held) {
      // The training star is the same item on purpose - a Nether Star in the ninth slot is what
      // this mod already uses for "the fight's own controls", so a player learns it once - and it
      // is checked here, before the kit branches, because a training duel is not a kit lobby.
      if (tryOpenTrainingMenu(p, held)) {
         return true;
      }
      if (p != null && held != null && held.is(Items.NETHER_STAR)) {
         Duel d = duels.get(p.getUUID());
         if (d != null && (d.phase == Phase.LOBBY || d.phase == Phase.COUNTDOWN)) {
            if (d.mode == DuelMode.SKYWARS) {
               SkywarsKitMenu.open(p);
               return true;
            } else if (d.mode == DuelMode.KITS) {
               KitsKitMenu.open(p);
               return true;
            } else {
               return false;
            }
         } else {
            return false;
         }
      } else {
         return false;
      }
   }

   /**
    * Is this duel a practice session against the unkillable dummy?
    *
    * <p>Asked in one place so every branch of the duel that has to stand down for training -
    * the kit, the inventory wipe, the AI - asks the same question, and a later mode cannot get
    * half of the treatment.
    */
   public static boolean isTrainingDuel(Duel d) {
      return d != null && d.botDifficulty == BotDifficulty.TRAIN;
   }

   /** The Nether Star the training player controls the dummy with, in the ninth hotbar slot. */
   private static void giveTrainingStar(ServerPlayer p) {
      ItemStack star = new ItemStack(Items.NETHER_STAR);
      star.set(DataComponents.CUSTOM_NAME, Component.literal("§6§lTraining Dummy"));
      star.set(
         DataComponents.LORE,
         new ItemLore(List.of(Component.literal("§7Right-click to control the dummy"), Component.literal("§8Infinite health, no AI, at the centre")))
      );
      p.getInventory().setItem(8, star);
   }

   /** Right-click on the training star: the dummy's own control screen. */
   public static boolean tryOpenTrainingMenu(ServerPlayer p, ItemStack held) {
      if (p == null || held == null || !held.is(Items.NETHER_STAR)) {
         return false;
      }
      if (trainingDummyFor(p) != null) {
         com.fortuneandfavors.menu.TrainingDummyMenu.open(p);
         return true;
      }
      return false;
   }

   /** The live dummy this player is training against, or null. */
   public static com.fortuneandfavors.duel.DuelBot trainingDummyFor(ServerPlayer p) {
      if (p == null) {
         return null;
      }
      Duel d = duels.get(p.getUUID());
      if (isTrainingDuel(d) && d.bot != null && d.bot.trainingDummy && d.bot.isAlive()) {
         return d.bot;
      }
      return null;
   }

   /**
    * The dummy's own controls, driven from the Nether Star screen.
    *
    * <p>Everything here is about the <i>setup</i> of a practice session rather than about the
    * fight: a dummy that cannot be moved, healed or dressed is a dummy you can only practise one
    * specific thing on, and the point of the mode is that it can be anything.
    */
   public static void trainingControl(ServerPlayer p, String action) {
      Duel d = duels.get(p.getUUID());
      com.fortuneandfavors.duel.DuelBot bot = trainingDummyFor(p);
      if (d == null || bot == null || action == null) {
         Chat.msg(p, "§cThe training dummy is not here any more.");
         return;
      }

      switch (action) {
         case "heal" -> {
            bot.setHealth(bot.getMaxHealth());
            Chat.msg(p, "§6The dummy is back at full strength.");
         }
         case "recentre" -> {
            moveDummyToCentre(d, bot);
            Chat.msg(p, "§6The dummy returns to the centre of the arena.");
         }
         case "armour" -> {
            bot.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.DIAMOND_HELMET));
            bot.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
            bot.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.DIAMOND_LEGGINGS));
            bot.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.DIAMOND_BOOTS));
            Chat.msg(p, "§6The dummy is armoured - practise against a real defence.");
         }
         case "strip" -> {
            bot.setItemSlot(EquipmentSlot.HEAD, ItemStack.EMPTY);
            bot.setItemSlot(EquipmentSlot.CHEST, ItemStack.EMPTY);
            bot.setItemSlot(EquipmentSlot.LEGS, ItemStack.EMPTY);
            bot.setItemSlot(EquipmentSlot.FEET, ItemStack.EMPTY);
            Chat.msg(p, "§6The dummy's armour is off.");
         }
         default -> Chat.msg(p, "§cUnknown dummy control: " + action);
      }
   }

   private static void moveDummyToCentre(Duel d, com.fortuneandfavors.duel.DuelBot bot) {
      ServerLevel realm = getRealm(serverRef);
      int x = (d.arena.x0 + d.arena.x1) / 2;
      int z = (d.arena.z0 + d.arena.z1) / 2;
      int gy = realm != null ? realm.getHeight(Types.MOTION_BLOCKING, x, z) + 1 : (int)bot.getY();
      bot.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
      bot.teleportTo(x + 0.5, gy, z + 0.5);
   }

   /**
    * The training dummy's own tick: keep the bar full, face the person hitting it, and nothing
    * else.
    *
    * <p>Deliberately not a single line of the fight AI. A dummy that dodges, W-taps, eats or
    * shields is a bot again, and the whole reason to want one is to be able to practise one input
    * on a thing that just stands there.
    */
   private static void tickTrainingDummy(Duel d, com.fortuneandfavors.duel.DuelBot bot, ServerLevel realm) {
      if (bot.getHealth() < bot.getMaxHealth()) {
         bot.setHealth(bot.getMaxHealth());
      }

      ServerPlayer look = null;
      double best = Double.MAX_VALUE;
      for (Participant part : d.parts) {
         ServerPlayer p = part.player;
         if (p != null && !part.bot && p.isAlive()) {
            double dist = p.distanceToSqr(bot);
            if (dist < best) {
               best = dist;
               look = p;
            }
         }
      }

      if (look != null) {
         double dx = look.getX() - bot.getX();
         double dz = look.getZ() - bot.getZ();
         float yaw = (float)(Math.atan2(dz, dx) * 180.0 / Math.PI) - 90.0F;
         bot.setYRot(yaw);
         bot.setYHeadRot(yaw);
      }

      for (Participant part : d.parts) {
         if (part.bot && part.botBossBar != null) {
            part.botBossBar.setProgress(1.0F);
         }
      }

      com.fortuneandfavors.net.FfVfx.particles(realm, ParticleTypes.ENCHANT, bot.getX(), bot.getY() + 1.2, bot.getZ(), 3, 0.5, 0.7, 0.5, 0.0);
   }

   public static void tick(MinecraftServer server) {
      serverRef = server;
      challenges.entrySet().removeIf(e -> e.getValue().expires < ServerClock.clock(server));
      expirePendingWagers();
      ServerLevel realm = getRealm(server);
      // NOTE: we used to freeze the arena clock by flipping doDaylightCycle /
      // doWeatherCycle off here, but since MC 26.2 game rules are shared across
      // the whole server (ServerLevel.getGameRules() == server.getGameRules()),
      // that froze the overworld's clock too and fought the daylight-cycle
      // watchdog in ModEvents every 10 seconds. The arena is a void flat world
      // with constant ambient light, so time-of-day doesn't matter - the arena
      // keeps the overworld timeline as-is.
      processStartupSweep(realm);
      pinDuelDifficulty(server);
      Set<Duel> seen = new HashSet<>();

      for (Duel sd : new ArrayList<>(duels.values())) {
         if (sd.phase == Phase.ENDED && sd.finalized) {
            for (Participant sp : sd.parts) {
               if (sp.hasPlayer() && duels.get(sp.uuid) == sd) {
                  duels.remove(sp.uuid);

                  try {
                     ServerPlayer sp2 = serverRef != null ? serverRef.getPlayerList().getPlayer(sp.uuid) : null;
                     if (sp2 != null) {
                        applyCombat(sp2, CombatStyle.MODERN);
                        resetMaxHealth(sp2);
                        sp2.setInvulnerable(false);
                        sp2.getAbilities().invulnerable = false;
                        sp2.setGameMode(GameType.SURVIVAL);
                        if (sp.saved != null) {
                           restore(sp2, sp.saved);
                        }
                     } else if (sp.saved != null) {
                        pendingRestore.put(sp.uuid, sp.saved);
                     }
                  } catch (Throwable var9) {
                  }
               }
            }
         }
      }

      // Each batch hands the world 2,000 block writes, which is a lot of work for one tick -
      // so a server already behind clears one batch instead of three, the same bargain the
      // realm sweeps make.
      int teardownBatches = sweepChunkBudget(3, PerfMonitor.mspt(), PerfMonitor.msptAvailable());

      for (int i = 0; i < teardownBatches && !pendingTeardowns.isEmpty(); i++) {
         Safe.run("arena teardown batch", pendingTeardowns.poll());
      }

      for (Duel d : new ArrayList<>(duels.values())) {
         if (seen.add(d)) {
            try {
               if (d.phase == Phase.ENDED) {
                  long now = realm != null ? ServerClock.clock(realm) : Long.MAX_VALUE;
                  if (realm != null && now < d.finalizeAt) {
                     ensureRematchMenus(d, now);
                  } else {
                     finalizeDuel(d);
                  }
               } else {
                  tickDuel(d);
               }
            } catch (Throwable t) {
               FortuneFavorsMod.LOGGER.error("Fortune & Favors: duel tick error - voiding the match", t);

               try {
                  endDuel(d, null, "&cThe duel glitched and was voided - everything is restored.");
               } catch (Throwable t2) {
                  FortuneFavorsMod.LOGGER.error("Fortune & Favors: could not clean up the voided duel", t2);
               }
            }
         }
      }
   }

   public static void onDisconnect(ServerPlayer p) {
      challenges.remove(p.getUUID());
      challenges.entrySet().removeIf(e -> e.getValue().challenger.equals(p.getUUID()));
      if (spectatorSessions.containsKey(p.getUUID())) {
         unspectate(p);
         pendingSpectatorBets.remove(p.getUUID());
      }

      Duel d = duels.get(p.getUUID());
      // The state is filed for a rejoin whether the match is still running or has
      // already ended but not yet been finalised. The old test was `!= ENDED`, so
      // logging out during the win-animation window - the one window where the
      // player is already a spectator in the arena - filed nothing, and the rejoin
      // handler found no state to put back: the player came back a spectator in a
      // torn-down arena holding a duel kit instead of their own inventory.
      if (d != null) {
         Participant part = participantOf(d, p);
         if (part != null && part.saved != null) {
            pendingRestore.put(p.getUUID(), part.saved);
         }
      }

      if (d != null && d.phase != Phase.ENDED) {
         Participant part = participantOf(d, p);
         if (d.ffa) {
            if (part != null && !part.eliminated) {
               handleFfaElimination(d, part, p, p.level().damageSources().genericKill());
            }
         } else {
            Participant other = otherParticipant(d, p);
            String msg = "&c"
               + p.getName().getString()
               + " disconnected -"
               + (other != null && other.displayName != null ? " &a" + other.displayName + "&c wins by forfeit!" : " the duel is over.");
            endDuel(d, other, msg);
         }
      }
   }

   public static void onJoin(ServerPlayer p) {
      SavedPlayer s = pendingRestore.remove(p.getUUID());
      if (s != null) {
         restore(p, s);
         Chat.msg(p, "&aYour duel ended while you were away - your items and position are restored.");
      } else {
         if (!duels.containsKey(p.getUUID())) {
            try {
               applyCombat(p, CombatStyle.MODERN);
               resetMaxHealth(p);
               p.setInvulnerable(false);
               p.getAbilities().invulnerable = false;
               if (p.connection != null) {
                  p.connection.send(new ClientboundPlayerAbilitiesPacket(p.getAbilities()));
               }
            } catch (Throwable var7) {
            }
         }

         if (isDuelRealm(p.level()) && !duels.containsKey(p.getUUID())) {
            MinecraftServer server = p.level().getServer();
            ServerLevel overworld = server.overworld();
            BlockPos spawn = overworld.getLevelData().getRespawnData().pos();

            // Being pulled out of the arena is only half a rescue. A player who
            // logged out during the end-of-match window comes back in SPECTATOR -
            // that is the state the fight left them in - and teleporting a
            // spectator to spawn strands them there with no way to act. The mode
            // goes back to something playable in the same breath as the move.
            try {
               p.setGameMode(GameType.SURVIVAL);
               p.setInvulnerable(false);
               p.getAbilities().invulnerable = false;
               p.setHealth(Math.max(1.0F, Math.min(p.getHealth(), p.getMaxHealth())));
               if (p.connection != null) {
                  p.connection.send(new ClientboundPlayerAbilitiesPacket(p.getAbilities()));
               }
            } catch (Throwable t) {
               FortuneFavorsMod.LOGGER.warn("Fortune & Favors: could not restore game mode for " + p.getName().getString() + " after a duel", t);
            }

            try {
               p.teleport(
                  new TeleportTransition(
                     overworld,
                     new Vec3(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5),
                     Vec3.ZERO,
                     p.getYRot(),
                     p.getXRot(),
                     TeleportTransition.PLACE_PORTAL_TICKET
                  )
               );
            } catch (Exception e) {
               FortuneFavorsMod.LOGGER.error("Fortune & Favors: failed to rescue player " + p.getName().getString() + " from duel realm", e);
               // A teleport into a realm that is fading can strand the client on
               // "loading terrain" forever, so the fallback is a plain move inside
               // the world the player is already standing in.
               try {
                  p.teleportTo(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5);
               } catch (Throwable t2) {
                  FortuneFavorsMod.LOGGER.error("Fortune & Favors: fallback rescue also failed for " + p.getName().getString(), t2);
               }
            }
         }
      }
   }

   /**
    * Mode gates that run before a blow lands, for the modes whose <b>rules</b> are
    * about what counts as a hit rather than about what you are carrying.
    *
    * <p>Archery is the only one so far, and it is here rather than in the kit
    * because a bow-only mode that merely hands you a bow is not a rule at all:
    * players would punch each other down and the fastest fist would win. Swing at
    * someone in an archery duel and the blow is simply cancelled - arrows (and
    * anything else thrown or launched) are untouched.
    *
    * @return false to cancel the damage
    */
   /**
    * The one rule the exit path asks about state that is keyed by uuid.
    *
    * <p>Two ways a fighter can fail it, and both have to mean the same thing:
    * a bot has no uuid on purpose, and a fighter that somehow has neither is not
    * somebody to restore to. Public so the self-test can pin it - there is exactly
    * one copy of this decision and {@link Participant#hasPlayer} is the other name
    * for it.
    */
   public static boolean ownsState(boolean bot, UUID uuid) {
      return !bot && uuid != null;
   }

   /** Null-safe form of {@link Participant#hasPlayer} for lists a half-built match
    *  can leave short. */
   static boolean isPlayer(Participant part) {
      return part != null && part.hasPlayer();
   }

   /**
    * What the duel did with a blow aimed at a bot.
    *
    * <p>These are two different questions, and answering them with one boolean is
    * what made a mace smash invisible on a bot. `taken` says the duel has already
    * applied the damage and vanilla must not apply it again. `landed` says the blow
    * actually connected - which is the answer `Entity.hurtOrSimulate` hands back to
    * `Player.attack`, and therefore the answer that decides whether the attacker's
    * own post-hit work runs at all: `MaceItem.hurtEnemy`'s ground slam, the slam
    * knockback that throws everyone standing nearby, the critical-hit particles and
    * the sound. Reporting "not taken" for every blow meant the duel applied the
    * damage and vanilla was told the swing had missed.
    */
   public record BotBlow(boolean taken, boolean landed) {
      /** The duel is not involved: vanilla owns the whole blow. */
      public static final BotBlow PASS = new BotBlow(false, false);
      /** The duel took the blow and it did not connect - raised shield, pacing window. */
      public static final BotBlow DEFLECTED = new BotBlow(true, false);
      /** The duel took the blow and it connected. */
      public static final BotBlow LANDED = new BotBlow(true, true);
   }

   public static boolean allowDuelDamage(LivingEntity victim, DamageSource source) {
      try {
         if (victim == null || source == null || !(victim instanceof ServerPlayer p)) {
            return true;
         }
         Duel d = duels.get(p.getUUID());
         if (d == null || d.phase != Phase.FIGHT) {
            return true;
         }
         // Gladiator's gearing window is a truce. Everyone is still digging, and a
         // race to the first cache should not be decided by whoever happens to meet
         // someone in a mineshaft with no sword yet. Only blows a *player* owns are
         // refused - falling down your own shaft, a lava pool you opened, the void -
         // so the map is peaceful between fighters rather than safe.
         if (d.mode == DuelMode.GLADIATOR && gladQuiet(d, ServerClock.clock(victim.level()))) {
            ServerPlayer attacker = playerAttackerOf(source);
            if (attacker != null && attacker != p) {
               if (!d.gladTruceTold) {
                  d.gladTruceTold = true;
                  announce(d, "&7A blow lands on nothing - &ethe hunt has not begun.&7 &7Gear up.");
               }
               attacker.sendOverlayMessage(Component.literal("\u00a77The truce holds \u00a78| \u00a7fno blows until the hunt opens"));
               return false;
            }
            return true;
         }
         if (d.mode != DuelMode.ARCHERY) {
            return true;
         }
         if (source.getDirectEntity() instanceof Projectile) {
            return true;
         }
         // A melee hit is one whose attacker is standing there. Anything else -
         // fall damage, the void - is allowed through and decided elsewhere.
         return !(source.getEntity() instanceof ServerPlayer);
      } catch (Throwable t) {
         // Never let a cosmetic rule eat real damage on an error path.
         return true;
      }
   }

   /**
    * True while a Gladiator match is still in its gearing window.
    *
    * <p>The window opens when the fight starts and closes when the fighters light
    * up. It is read off the match's own clock rather than a stopwatch, so a server
    * that ticks slowly gets a longer wall-clock window, never a shorter one - and
    * nobody can be ambushed in the first second because the first second was long.
    */
   private static boolean gladQuiet(Duel d, long now) {
      return d != null && gladiatorTruce(d.gladHighlightAt, now);
   }

   public static boolean onDuelLethalDamage(LivingEntity entity, DamageSource source, float amount) {
      if (!entity.level().isClientSide() && entity instanceof ServerPlayer p) {
         Duel d = duels.get(p.getUUID());
         if (d == null) {
            return true;
         }

         if (entity instanceof DuelBot) {
            handleBotDamage(d, (DuelBot)entity, source, amount);
            return false;
         }

         if (d.phase == Phase.ENDED) {
            if (d.finalized) {
               duels.remove(p.getUUID());
               applyCombat(p, CombatStyle.MODERN);
               resetMaxHealth(p);
               p.setInvulnerable(false);
               p.getAbilities().invulnerable = false;
               if (p.connection != null) {
                  p.connection.send(new ClientboundPlayerAbilitiesPacket(p.getAbilities()));
               }

               p.setGameMode(GameType.SURVIVAL);
               Participant stuck = participantOf(d, p);
               if (stuck != null && stuck.saved != null) {
                  try {
                     restore(p, stuck.saved);
                  } catch (Throwable var23) {
                  }
               }

               return true;
            } else {
               p.setGameMode(GameType.SPECTATOR);
               p.setHealth(p.getMaxHealth());
               p.setInvulnerable(true);
               p.setDeltaMovement(Vec3.ZERO);
               return false;
            }
         } else {
            if (d.phase != Phase.FIGHT) {
               return true;
            }

            Participant part = participantOf(d, p);
            if (part == null) {
               return true;
            }

            if (d.mode == DuelMode.TNTRUN && source.is(DamageTypeTags.IS_FALL)) {
               return false;
            }

            if (part.eliminated) {
               return false;
            }

            // A totem is the fighter's own answer, and it is asked before the match's.
            //
            // Every elimination branch below absorbs the blow itself - BEDWARS, LASTSTAND, the
            // free-for-alls - and absorbing the blow *is* cancelling the damage. Vanilla only
            // spends a Totem of Undying inside the damage application none of those branches
            // reach, so a fighter who was holding one still lost: that is the whole of "totems
            // do nothing in Gladiator", where a totem out of the map's own loot is the reward
            // for having dug for it.
            //
            // The save is made *here* rather than by letting the blow through, because this
            // gate runs in front of vanilla's own totem check: a refused blow never reaches it,
            // and a blow handed back gets re-read by the elimination branches below as the death
            // it was. So the totem is spent by hand, exactly as vanilla would spend it, and the
            // blow is refused - which is also the only shape that can be asserted from here.
            if (popDuelTotem(part, p, source, amount)) {
               return false;
            }

            if (d.mode == DuelMode.BEDWARS && part.respawnAt != 0L) {
               return false;
            }

            if (d.mode == DuelMode.COMBODUEL) {
               p.invulnerableTime = 0;
               if (source.getEntity() instanceof ServerPlayer attacker) {
                  bumpCombo(participantOf(d, attacker));
               }

               breakCombo(part);
            }

            float armor = 0.0F;
            float toughness = 0.0F;
            AttributeInstance armorAttr = p.getAttribute(Attributes.ARMOR);
            AttributeInstance toughAttr = p.getAttribute(Attributes.ARMOR_TOUGHNESS);
            if (armorAttr != null) {
               armor = (float)armorAttr.getValue();
            }

            if (toughAttr != null) {
               toughness = (float)toughAttr.getValue();
            }

            float dmg = CombatRules.getDamageAfterAbsorb(p, amount, source, armor, toughness);
            float effectiveHp = p.getHealth() + p.getAbsorptionAmount();
            if (effectiveHp > dmg) {
               return true;
            }

            if (d.mode == DuelMode.BEDWARS && part.bedIntact) {
               handleBedwarsDeath(d, part, p, source);
               return false;
            }

            if (d.mode == DuelMode.LASTSTAND) {
               handleLastStandDeath(d, part, p, source);
               return false;
            }

            if (d.ffa) {
               handleFfaElimination(d, part, p, source);
               return false;
            }


            Participant other = otherParticipant(d, p);
            String loser = p.getName().getString();
            String msg = "&c"
               + loser
               + " died -"
               + (other != null && other.displayName != null ? " &a" + other.displayName + "&c wins!" : " the duel is over.");
            endDuel(d, other, msg);
            ServerLevel realm = getRealm(serverRef);
            if (realm != null) {
               double[] sp = d.arena.spawnFor(part.slot);
               p.teleport(
                  new TeleportTransition(
                     realm, new Vec3(sp[0], sp[1] + 1.0, sp[2]), Vec3.ZERO, d.arena.spawnYaw(part.slot), 0.0F, TeleportTransition.PLACE_PORTAL_TICKET
                  )
               );
               p.setHealth(p.getMaxHealth());
               p.getFoodData().setFoodLevel(20);
               p.setInvulnerable(true);
               p.setDeltaMovement(Vec3.ZERO);
               if (p.connection != null) {
                  p.connection.send(new ClientboundSetTitleTextPacket(Component.literal("§c§lDEFEATED")));
                  p.connection.send(new ClientboundSetTitlesAnimationPacket(5, 40, 10));
                  p.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal("§7You have been eliminated")));
               }

               com.fortuneandfavors.net.FfVfx.particles(realm, ParticleTypes.EXPLOSION_EMITTER, p.getX(), p.getY() + 1.0, p.getZ(), 5, 0.3, 0.4, 0.3, 0.15);
               com.fortuneandfavors.net.FfVfx.particles(realm, ParticleTypes.EXPLOSION, p.getX(), p.getY() + 1.5, p.getZ(), 8, 0.4, 0.5, 0.4, 0.08);
               com.fortuneandfavors.net.FfVfx.particles(realm, ParticleTypes.SOUL_FIRE_FLAME, p.getX(), p.getY() + 0.1, p.getZ(), 35, 0.4, 1.0, 0.4, 0.04);
               com.fortuneandfavors.net.FfVfx.particles(realm, ParticleTypes.SOUL, p.getX(), p.getY() + 1.0, p.getZ(), 25, 0.5, 0.8, 0.5, 0.03);

               for (double angle = 0.0; angle < Math.PI * 2; angle += Math.PI / 8) {
                  double ox = Math.cos(angle) * 1.5;
                  double oz = Math.sin(angle) * 1.5;
                  com.fortuneandfavors.net.FfVfx.particles(realm, ParticleTypes.LARGE_SMOKE, p.getX() + ox, p.getY() + 0.8, p.getZ() + oz, 2, 0.1, 0.2, 0.1, 0.02);
               }

               com.fortuneandfavors.net.FfVfx.particles(realm, ParticleTypes.LAVA, p.getX(), p.getY() + 0.5, p.getZ(), 20, 0.5, 0.5, 0.5, 0.05);
               com.fortuneandfavors.net.FfVfx.particles(realm, ParticleTypes.CRIT, p.getX(), p.getY() + 0.5, p.getZ(), 30, 0.4, 0.5, 0.4, 0.08);
               realm.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.GLASS_BREAK, SoundSource.PLAYERS, 2.0F, 0.5F);
               realm.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.WITHER_DEATH, SoundSource.PLAYERS, 1.5F, 1.4F);
               realm.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.WITHER_BREAK_BLOCK, SoundSource.PLAYERS, 1.2F, 0.8F);
               realm.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.GENERIC_EXPLODE, SoundSource.PLAYERS, 1.0F, 0.6F);
               part.spawnProtectUntil = ServerClock.clock(realm) + 40L + 20L;
            }

            if (other != null && !other.bot && other.player != null && other.player.connection != null) {
               String victimName = part.displayName != null ? part.displayName : loser;
               other.player.connection.send(new ClientboundSetTitleTextPacket(Component.literal("§6§lFINAL KILL")));
               other.player.connection.send(new ClientboundSetTitlesAnimationPacket(2, 40, 10));
               other.player.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal("§c" + victimName + " §7has been eliminated")));
            }

            return false;
         }
      } else {
         return true;
      }
   }

   public static boolean suppressBedwarsDeathDrop(ServerPlayer p, ItemStack stack) {
      if (p != null && !p.level().isClientSide()) {
         Duel d = duels.get(p.getUUID());
         return d != null && d.mode == DuelMode.BEDWARS && d.phase != Phase.ENDED;
      } else {
         return false;
      }
   }

   public static void onDeath(ServerPlayer p, ServerPlayer killer) {
      Duel d = duels.get(p.getUUID());
      if (d != null && d.phase != Phase.ENDED) {
         Participant part = participantOf(d, p);
         Participant other = otherParticipant(d, p);
         if (d.mode == DuelMode.LASTSTAND) {
            handleLastStandDeath(d, part, p, p.level().damageSources().genericKill());
         } else if (d.ffa) {
            handleFfaElimination(d, part, p, p.level().damageSources().genericKill());
         } else if (d.mode == DuelMode.BEDWARS && part != null && part.bedIntact) {
            part.deathInventory = new ArrayList<>();

            for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
               part.deathInventory.add(p.getInventory().getItem(i).copy());
            }

            part.respawnAt = ServerClock.clock(p.level()) + 70L;
            Chat.msg(p, "&eYou died, but your bed still stands - respawning at your &fgenerator &ein 3.5s&e!");
            transferResourcesOnDeath(p, killer);
         } else {
            String loser = p.getName().getString();
            String msg = "&c"
               + loser
               + " died -"
               + (other != null && other.displayName != null ? " &a" + other.displayName + "&c wins!" : " the duel is over.");
            endDuel(d, other, msg);
         }
      }
   }

   private static void transferResourcesOnDeath(ServerPlayer victim, ServerPlayer killer) {
      if (killer != null && killer != victim && killer.isAlive()) {
         for (Item currency : new Item[]{Items.IRON_INGOT, Items.GOLD_INGOT, Items.DIAMOND, Items.EMERALD}) {
            int count = InventoryHelper.countItems(victim, currency);
            if (count > 0) {
               InventoryHelper.removeItems(victim, currency, count);
               InventoryHelper.giveOrDrop(killer, new ItemStack(currency, count));
            }
         }
      }
   }

   public static void load(MinecraftServer server) {
      serverRef = server;
      duels.clear();
      pendingProbes.clear();
      dataFile = server.getWorldPath(LevelResource.ROOT).resolve("fortuneandfavors").resolve("duel.json");
      sweepFile = server.getWorldPath(LevelResource.ROOT).resolve("fortuneandfavors").resolve("duel_sweep.json");
      dirtyPlots.clear();
      JsonObject sweepRoot = JsonUtil.readOrCreate(sweepFile, new JsonObject());
      if (sweepRoot.has("dirty_plots") && sweepRoot.get("dirty_plots").isJsonArray()) {
         for (JsonElement el : sweepRoot.getAsJsonArray("dirty_plots")) {
            try {
               dirtyPlots.add(el.getAsInt());
            } catch (Exception var9) {
            }
         }
      }

      dirtyPlots.removeIf(plot -> plot == null || plot < 0 || plot >= 64);
      JsonObject root = JsonUtil.readOrCreate(dataFile, new JsonObject());
      pendingRestore.clear();
      if (root.has("pending") && root.get("pending").isJsonArray()) {
         for (JsonElement el : root.getAsJsonArray("pending")) {
            try {
               JsonObject obj = el.getAsJsonObject();
               UUID uuid = UUID.fromString(obj.get("uuid").getAsString());
               SavedPlayer s = savedFromJson(obj.getAsJsonObject("saved"), server.registryAccess());
               if (s != null) {
                  pendingRestore.put(uuid, s);
               }
            } catch (Exception var11) {
            }
         }
      }

      statsFile = server.getWorldPath(LevelResource.ROOT).resolve("fortuneandfavors").resolve("duel_stats.json");
      JsonObject sroot = JsonUtil.readOrCreate(statsFile, new JsonObject());
      duelStats.clear();
      statNames.clear();
      bestCombos.clear();
      if (sroot.has("stats") && sroot.get("stats").isJsonArray()) {
         for (JsonElement el : sroot.getAsJsonArray("stats")) {
            try {
               JsonObject obj = el.getAsJsonObject();
               UUID uuid = UUID.fromString(obj.get("uuid").getAsString());
               statNames.put(uuid, JsonUtil.jsonString(obj, "name", null));
               Map<String, long[]> modes = new HashMap<>();
               if (obj.has("modes")) {
                  for (Entry<String, JsonElement> me : obj.getAsJsonObject("modes").entrySet()) {
                     JsonArray rec = me.getValue().getAsJsonArray();
                     modes.put(me.getKey(), new long[]{rec.get(0).getAsLong(), rec.get(1).getAsLong()});
                  }
               }

               duelStats.put(uuid, modes);
               if (obj.has("best_combo")) {
                  bestCombos.put(uuid, obj.get("best_combo").getAsInt());
               }
            } catch (Exception var12) {
            }
         }
      }

      // Bots a player built. Stored beside the duel state rather than in it, because
      // a profile outlives every individual match it was ever used in.
      customBotProfiles.clear();
      if (root.has("custom_bots") && root.get("custom_bots").isJsonObject()) {
         for (Entry<String, JsonElement> e : root.getAsJsonObject("custom_bots").entrySet()) {
            try {
               JsonObject obj = e.getValue().getAsJsonObject();
               customBotProfiles.put(
                  UUID.fromString(e.getKey()),
                  new BotProfile(
                     obj.get("speed").getAsDouble(),
                     obj.get("strafe").getAsDouble(),
                     obj.get("cps").getAsDouble(),
                     obj.get("smartness").getAsDouble()
                  )
               );
            } catch (Exception ignored) {
            }
         }
      }

      sweepQueue.clear();
      sweepQueue.addAll(bootSweepPlan(dirtyPlots, usedPlots));
      startupSweepPlot = server.getLevel(DUEL_REALM) != null && !sweepQueue.isEmpty() ? 0 : -1;
      startupSweepChunk = 0;
      if (startupSweepPlot < 0) {
         FortuneFavorsMod.LOGGER.info("Fortune & Favors: no leftover arena to sweep - the duel realm is left alone");
      } else {
         FortuneFavorsMod.LOGGER.info(
            "Fortune & Favors: sweeping {} plot(s) left by an unfinished match: {}", sweepQueue.size(), sweepQueue
         );
      }
   }

   public static void save(MinecraftServer server) {
      if (dataFile != null) {
         try {
            JsonObject root = new JsonObject();
            JsonArray arr = new JsonArray();

            for (Entry<UUID, SavedPlayer> e : pendingRestore.entrySet()) {
               JsonObject obj = new JsonObject();
               obj.addProperty("uuid", e.getKey().toString());
               obj.add("saved", savedToJson(e.getValue(), server.registryAccess()));
               arr.add(obj);
            }

            root.add("pending", arr);

            // The bot builder, saved with the rest of the duel state and written by
            // the same periodic autosave, so a profile survives a restart instead of
            // quietly resetting to Normal.
            JsonObject bots = new JsonObject();
            for (Entry<UUID, BotProfile> e : customBotProfiles.entrySet()) {
               JsonObject obj = new JsonObject();
               obj.addProperty("speed", e.getValue().speed());
               obj.addProperty("strafe", e.getValue().strafe());
               obj.addProperty("cps", e.getValue().cps());
               obj.addProperty("smartness", e.getValue().smartness());
               bots.add(e.getKey().toString(), obj);
            }

            root.add("custom_bots", bots);
            JsonUtil.write(dataFile, root);
         } catch (Exception e) {
            FortuneFavorsMod.LOGGER.error("Fortune & Favors: could not save duel state", e);
         }

         try {
            JsonObject sroot = new JsonObject();
            JsonArray sarr = new JsonArray();

            for (Entry<UUID, Map<String, long[]>> e : duelStats.entrySet()) {
               JsonObject obj = new JsonObject();
               obj.addProperty("uuid", e.getKey().toString());
               String name = statNames.get(e.getKey());
               if (name != null) {
                  obj.addProperty("name", name);
               }

               JsonObject modes = new JsonObject();

               for (Entry<String, long[]> me : e.getValue().entrySet()) {
                  JsonArray rec = new JsonArray();
                  rec.add(me.getValue()[0]);
                  rec.add(me.getValue()[1]);
                  modes.add(me.getKey(), rec);
               }

               obj.add("modes", modes);
               Integer best = bestCombos.get(e.getKey());
               if (best != null && best > 0) {
                  obj.addProperty("best_combo", best);
               }

               sarr.add(obj);
            }

            sroot.add("stats", sarr);
            JsonUtil.write(statsFile, sroot);
         } catch (Exception e) {
            FortuneFavorsMod.LOGGER.error("Fortune & Favors: could not save duel stats", e);
         }
      }
   }

   private static Duel createDuel(ServerPlayer a, ServerPlayer b, DuelMode mode) {
      return createDuel(a, b, mode, null);
   }

   private static String createFFADuel(ServerPlayer host, FFALobby lobby) {
      MinecraftServer server = host.level().getServer();
      ServerLevel realm = getRealm(server);
      if (realm == null) {
         return "Arena failed to generate.";
      }

      int plot = claimPlot();
      int ox = PLOT_ORIGIN + plot % PLOT_COLS * PLOT_STRIDE;
      int oz = PLOT_ORIGIN + plot / PLOT_COLS * PLOT_STRIDE;
      Duel d = new Duel(lobby.mode, ox, oz);
      int idx = 0;

      for (UUID uuid : lobby.members) {
         ServerPlayer p = server.getPlayerList().getPlayer(uuid);
         if (p != null) {
            d.parts.add(participantFrom(p, d, idx++));
         }
      }

      if (d.parts.size() < 2) {
         return "Not enough players to start.";
      }

      d.ffa = true;
      d.arena.ffaCount = d.parts.size();
      buildArena(realm, d);
      int n = d.parts.size();
      double cx = (d.arena.x0 + d.arena.x1) / 2.0;
      double cz = (d.arena.z0 + d.arena.z1) / 2.0;
      double radius = d.arena.spreadRadius > 0.0 ? d.arena.spreadRadius : Math.min(d.arena.x1 - d.arena.x0, d.arena.z1 - d.arena.z0) / 2.0 - 4.0;
      if (radius < 4.0) {
         radius = 4.0;
      }

      double spawnY = d.arena.spawn0 != null ? d.arena.spawn0[1] : 101.0;

      for (int slot = 0; slot < n; slot++) {
         if (!d.arena.extraSpawns.containsKey(slot)) {
            double ang = slot * 2.0 * Math.PI / n + (Math.PI / 2);
            double sx = cx + Math.cos(ang) * radius;
            double sz = cz + Math.sin(ang) * radius;
            // A gladiator world has hills in it, so the ring the free-for-all spreads
            // fighters around is rarely flat: the shared spawn height belongs to the
            // arena's own pad, and using it here dropped everyone into the hillside.
            double sy = d.mode == DuelMode.GLADIATOR ? gladSpawnY(realm, (int)Math.floor(sx), (int)Math.floor(sz)) : spawnY;
            d.arena.extraSpawns.put(slot, new double[]{Math.floor(sx) + 0.5, sy, Math.floor(sz) + 0.5});
            d.arena.extraYaws.put(slot, (float)Math.toDegrees(Math.atan2(cx - sx, cz - sz)));
         }
      }

      for (Participant part : d.parts) {
         ServerPlayer p = server.getPlayerList().getPlayer(part.uuid);
         if (p == null) {
            endDuel(d, null, "&cA fighter vanished - the FFA is cancelled.");
            return null;
         }

         part.player = p;
         snapshotInto(p, part);
         part.displayName = p.getName().getString();
         // The exit state waits under the fighter's own uuid. An FFA is players
         // only - a bot never reaches here - but the rule is asked for rather than
         // assumed, so a future bot-shaped entrant cannot file under a null key.
         if (part.hasPlayer()) {
            pendingRestore.put(part.uuid, part.saved);
         }
         p.getInventory().clearContent();
         p.removeAllEffects();
         p.setHealth(p.getMaxHealth());
         p.getFoodData().setFoodLevel(20);
         p.getFoodData().setSaturation(10.0F);
         p.setInvulnerable(true);
         p.setGameMode(GameType.SURVIVAL);
         p.setRespawnPosition(new RespawnConfig(RespawnData.of(DUEL_REALM, d.arena.padFor(part.slot), d.arena.spawnYaw(part.slot), 0.0F), true), false);
         p.setYRot(d.arena.spawnYaw(part.slot));
         p.setXRot(0.0F);
         if (!teleportTo(realm, p, d.arena.lobbySpawnFor(part.slot))) {
            p.setInvulnerable(false);
            p.setGameMode(GameType.SURVIVAL);
            restore(p, part.saved);
            p.setRespawnPosition(null, false);
            endDuel(d, null, "&cThe arena teleport failed - the FFA is cancelled.");
            return null;
         }

         Chat.raw(p, "§b⚡ You enter the FFA arena - §e" + lobby.mode.display + "§b!");
         if (lobby.mode == DuelMode.KITS) {
            Chat.raw(p, "§7Pick a kit: §fright-click the §eNether Star§7 in your hotbar!");
            giveKitsKitSelector(p);
         }
      }

      for (Participant part : d.parts) {
         // Keyed by the fighter's uuid - and only fighters who have one. An FFA is
         // players only; a bot's own duel is filed under its entity's uuid where it
         // is spawned, which is the key its damage gate and its AI look it up by.
         if (part.hasPlayer()) {
            duels.put(part.uuid, d);
         }
      }

      String modeList = d.parts.stream().map(px -> "&f" + px.displayName).collect(Collectors.joining("§7 vs "));
      announce(d, "§6§lFFA " + d.mode.display + "§r§7 - §e" + d.parts.size() + "§7 players: " + modeList);
      d.combat = CombatStyle.LEGACY;
      d.phase = Phase.LOBBY;
      int lobbyTicks = lobby.mode == DuelMode.KITS ? 300 : 100;
      d.phaseEndsAt = ServerClock.clock(realm) + lobbyTicks;
      lockBets(d);
      announce(d, "§7The FFA starts in §e" + lobbyTicks / 20 + "§7 seconds - §f" + d.parts.size() + "§7 fighters, last one standing wins!");
      return null;
   }

   private static Duel createDuel(ServerPlayer a, ServerPlayer b, DuelMode mode, BotDifficulty difficulty) {
      return createDuel(a, b, mode, difficulty, null);
   }

   private static Duel createDuel(ServerPlayer a, ServerPlayer b, DuelMode mode, BotDifficulty difficulty, CombatStyle combatOverride) {
      MinecraftServer server = a.level().getServer();
      ServerLevel realm = getRealm(server);
      if (realm == null) {
         return null;
      }

      // A player-made bot: the profile filed by the custom screen rides along with
      // the challenge and lands on the duel here, so it only ever affects the match
      // about to start rather than what "Normal" means for everyone else.
      BotProfile customProfile = pendingBotProfiles.remove(a.getUUID());

      int plot = claimPlot();
      int ox = PLOT_ORIGIN + plot % PLOT_COLS * PLOT_STRIDE;
      int oz = PLOT_ORIGIN + plot / PLOT_COLS * PLOT_STRIDE;
      Duel d = new Duel(mode, ox, oz);
      if (difficulty != null) {
         d.botDifficulty = difficulty;
      }

      if (customProfile != null && b == null) {
         d.botProfile = customProfile;
         Chat.msg(a, "§8Custom bot: §7" + customProfile.describe());
      }

      if (combatOverride != null) {
         d.combat = combatOverride;
      }

      boolean botMatch = b == null;
      WagerStaged ws = wagerStaged.remove(a.getUUID());
      if (ws != null && b != null && ws.amount > 0L) {
         String anteErr = anteUp(a, b, ws.amount);
         if (anteErr != null) {
            Chat.msg(a, "&c" + anteErr);
            Chat.msg(b, "&c" + anteErr);
            return null;
         }

         String key = betKey(a.getUUID(), b.getUUID());
         activeBets.computeIfAbsent(key, k -> new ArrayList<>()).add(new Bet(a.getUUID(), b.getUUID(), ws.amount));
         activeBets.computeIfAbsent(key, k -> new ArrayList<>()).add(new Bet(b.getUUID(), a.getUUID(), ws.amount));
         Chat.msg(a, "§6§lPool wager locked:§r §e" + Chat.moneyStr(ws.amount * 2L) + "§7 pool - winner takes all!");
         Chat.msg(b, "§6§lPool wager locked:§r §e" + Chat.moneyStr(ws.amount * 2L) + "§7 pool - winner takes all!");
      }

      d.parts.add(participantFrom(a, d, 0));
      if (b != null) {
         d.parts.add(participantFrom(b, d, 1));
      } else {
         d.parts.add(botParticipant(d, 1));
      }

      // A build that throws halfway leaves a floor with a hole in it and a ring
      // wall that stops before the corner - which is a match played on a broken map,
      // and from the player's side a map that "does not render". Nothing has been
      // put on the duel yet at this point, so the honest answer is to refuse the
      // match rather than run it on whatever got built.
      try {
         buildArena(realm, d);
      } catch (Throwable t) {
         FortuneFavorsMod.LOGGER.error("Fortune & Favors: arena build failed - the duel was refused", t);
         for (Participant part : d.parts) {
            if (!part.bot) {
               Chat.msg(server.getPlayerList().getPlayer(part.uuid), "&cThe arena could not be built - the duel was cancelled. Nothing was taken.");
            }
         }
         releasePlot(d.arena.ox, d.arena.oz);
         return null;
      }

      for (Participant part : d.parts) {
         if (!part.bot) {
            ServerPlayer p = server.getPlayerList().getPlayer(part.uuid);
            if (p == null) {
               endDuel(d, null, "&cA fighter vanished - the duel is cancelled.");
               return null;
            }

            part.player = p;
            snapshotInto(p, part);
            part.displayName = p.getName().getString();
            duels.put(p.getUUID(), d);
            if (!isTrainingDuel(d)) {
               // A training duel keeps the player's own kit: that is the whole point of the mode,
               // so the inventory is not cleared and the effects are not stripped. The snapshot
               // above still holds everything, so leaving restores exactly what came in.
               p.getInventory().clearContent();
               p.removeAllEffects();
            }
            p.setHealth(p.getMaxHealth());
            p.getFoodData().setFoodLevel(20);
            p.getFoodData().setSaturation(10.0F);
            p.setInvulnerable(true);
            p.setGameMode(GameType.SURVIVAL);
            p.setRespawnPosition(new RespawnConfig(RespawnData.of(DUEL_REALM, d.arena.padFor(part.slot), d.arena.spawnYaw(part.slot), 0.0F), true), false);
            p.setYRot(d.arena.spawnYaw(part.slot));
            p.setXRot(0.0F);
            if (!teleportTo(realm, p, d.arena.lobbySpawnFor(part.slot))) {
               p.setInvulnerable(false);
               p.setGameMode(GameType.SURVIVAL);
               restore(p, part.saved);
               p.setRespawnPosition(null, false);
               duels.remove(p.getUUID());
               p.getInventory().clearContent();
               endDuel(d, null, "&cThe arena teleport failed - the duel is cancelled.");
               return null;
            }

            Chat.raw(p, "§b⚡ You enter the duel arena - §e" + mode.display + "§b!");
            if (mode.voted && !botMatch) {
               Chat.raw(
                  p,
                  "§7Vote for combat with §f/duel vote legacy§7 or §f/duel vote modern§7"
                     + (mode != DuelMode.SKYWARS && mode != DuelMode.KITS ? "!" : " - and §fright-click your §eNether Star§7 to pick a kit!")
               );
            } else if (mode == DuelMode.SKYWARS || mode == DuelMode.KITS) {
               Chat.raw(p, "§7Pick a kit: §fright-click the §eNether Star§7 in your hotbar!");
            }

            if (!isTrainingDuel(d)) {
               if (mode == DuelMode.SKYWARS) {
                  giveKitSelector(p);
               } else if (mode == DuelMode.KITS) {
                  giveKitsKitSelector(p);
               }
            }
         } else {
            placeBot(d, part, realm);
         }
      }

      if (botMatch && mode.voted) {
         d.combat = Math.random() < 0.5 ? CombatStyle.LEGACY : CombatStyle.MODERN;
         announce(
            d,
            "&7No vote needed - &f" + (d.combat == CombatStyle.LEGACY ? "§c1.8 Legacy combat" : "§bModern combat") + "&7 was picked for this practice match."
         );
      }

      boolean draftLobby = mode == DuelMode.DRAFT;
      boolean kitLobby = mode == DuelMode.KITS;
      d.phase = (!mode.voted || botMatch) && !draftLobby && !kitLobby ? Phase.COUNTDOWN : Phase.LOBBY;
      d.phaseEndsAt = ServerClock.clock(realm) + (d.phase == Phase.LOBBY ? (draftLobby ? 600 : (kitLobby ? 300 : 600)) : 100);
      if (draftLobby) {
         announce(d, "&b⚡ Draft Duel! &7Take turns clicking kits from the shared pool - up to &e5&7 each. A picked kit disappears for everyone.");
      }

      if (kitLobby) {
         announce(d, "&d✨ Kits! &7Right-click the &eNether Star&7 in your hotbar to pick your fun kit - the fight starts in &e15&7 seconds!");
      }

      com.fortuneandfavors.net.FfVfx.particles(realm, ParticleTypes.PORTAL, ox, 102.0, oz, 80, 10.0, 3.0, 10.0, 0.1);
      return d;
   }

   private static void tickDuel(Duel d) {
      ServerLevel realm = getRealm(serverRef);
      if (realm == null) {
         endDuel(d, null, "&cThe duel realm vanished - the duel is cancelled.");
      } else {
         long now = ServerClock.clock(realm);
         reconcilePlayers(d);
         if (d.phase == Phase.LOBBY) {
            if (d.mode == DuelMode.DRAFT) {
               tickDraftLobby(d, realm, now);
            } else {
               if (d.bot != null && d.botVoteAt != 0L && now >= d.botVoteAt && !d.botVoted) {
                  d.botVoted = true;
                  d.botVoteLegacy = Math.random() < 0.5;
                  if (d.botVoteLegacy) {
                     d.voteLegacy++;
                  } else {
                     d.voteModern++;
                  }

                  String var10002 = d.botVoteLegacy ? "§c1.8 Legacy combat" : "§bModern combat";
                  announce(
                     d, "&f" + ((Participant)d.parts.get(1)).displayName + "&7 voted for " + var10002 + "&7 (" + (d.voteLegacy + d.voteModern) + "/2 votes)."
                  );
                  if (d.voteLegacy + d.voteModern >= 2) {
                     d.combat = d.voteLegacy > d.voteModern ? CombatStyle.LEGACY : CombatStyle.MODERN;
                     startCountdown(d);
                     return;
                  }
               }

               if (now >= d.phaseEndsAt) {
                  if (d.mode == DuelMode.KITS) {
                     announce(d, "&d✨ Kit-picking time is up - the fight starts!");
                     startCountdown(d);
                  } else if (d.voteLegacy + d.voteModern == 0 && d.ffa) {
                     announce(d, "§7The FFA lobby is over - " + (d.combat == CombatStyle.LEGACY ? "§c1.8 Legacy combat" : "§bModern combat") + "&7 it is!");
                     startCountdown(d);
                  } else {
                     CombatStyle winner = d.voteLegacy > d.voteModern ? CombatStyle.LEGACY : CombatStyle.MODERN;
                     announce(d, "&7Voting closed - " + (winner == CombatStyle.LEGACY ? "§c1.8 Legacy combat" : "§bModern combat") + "&7 wins!");
                     d.combat = winner;
                     startCountdown(d);
                  }
               } else {
                  if (!d.ffa) {
                     checkOutOfBounds(d, false);
                  }
               }
            }
         } else if (d.phase == Phase.COUNTDOWN) {
            long left = d.phaseEndsAt - now;
            int seconds = (int)Math.max(0.0, Math.ceil(left / 20.0));

            for (Participant part : d.parts) {
               if (!part.bot && part.player != null) {
                  part.player.sendSystemMessage(Component.literal("§eThe duel starts in " + Math.max(1, seconds) + "..."), true);
               }
            }

            checkOutOfBounds(d, false);
            if (now >= d.phaseEndsAt) {
               startFight(d);
            }
         } else {
            if (d.phase == Phase.FIGHT) {
               checkOutOfBounds(d, true);
               tickBedwarsRespawns(d, realm, now);
               if (d.mode == DuelMode.BEDWARS && now % 20L == 0L || d.mode == DuelMode.COMBODUEL && now % 10L == 0L || d.ffa && now % 20L == 0L) {
                  refreshDuelScoreboard(d);
               }

               if (d.ffa && now % 20L == 0L) {
                  sendFfaSpectatorHud(d);
               }

               for (Participant part : d.parts) {
                  if (part.spinInvulUntil > 0L && now >= part.spinInvulUntil) {
                     part.spinInvulUntil = 0L;
                     if (!part.bot && part.player != null) {
                        part.player.setInvulnerable(false);
                        part.player.getAbilities().invulnerable = false;
                        if (part.player.connection != null) {
                           part.player.connection.send(new ClientboundPlayerAbilitiesPacket(part.player.getAbilities()));
                        }
                     }
                  }
               }

               tickGamemode(d, realm, now);
               tickBedwarsHungerDurability(d, now);
               tickModeRules(d, realm, now);
               if (d.bot != null) {
                  tickBot(d, realm, now);
               }

               if (d.fightStartedAt != 0L && now - d.fightStartedAt > 36000L) {
                  Participant best = null;
                  double bestFrac = -1.0;
                  boolean tie = false;

                  for (Participant part : d.parts) {
                     if (!part.bot && !part.eliminated && part.player != null && part.player.isAlive()) {
                        double frac = part.player.getHealth() / part.player.getMaxHealth();
                        if (frac > bestFrac + 0.001) {
                           bestFrac = frac;
                           best = part;
                           tie = false;
                        } else if (frac > bestFrac - 0.001) {
                           tie = true;
                        }
                     }
                  }

                  if (best != null && !tie) {
                     endDuel(d, best, "&eThe duel timed out after 30 minutes - &a" + best.displayName + "&e wins on health!");
                  } else {
                     endDuel(d, null, "&eThe duel timed out after 30 minutes - it's a draw!");
                  }
               }
            }
         }
      }
   }

   private static void tickDraftLobby(Duel d, ServerLevel realm, long now) {
      DraftMenu.tickAll();
      if (draftComplete(d)) {
         startCountdown(d);
      } else {
         Participant turn = (Participant)d.parts.get(d.draftTurn % d.parts.size());
         if (turn.bot) {
            if (d.botPickAt == 0L) {
               d.botPickAt = now + 40L;
            }

            if (now >= d.botPickAt) {
               d.botPickAt = 0L;
               int idx = (int)(Math.random() * d.draftPool.size());
               pickDraftCategory(d, turn, (String)d.draftPool.get(idx));
            }
         } else {
            if (turn.player != null && turn.player.connection != null) {
               if (!(turn.player.containerMenu instanceof DraftMenu)) {
                  DraftMenu.open(turn.player);
               }

               if (d.draftPrompted.add(turn.uuid)) {
                  d.draftPickDeadline = now + 200L;
                  Chat.msg(turn.player, "&eIt's your pick! You have &f10 seconds&e - or a random kit gets assigned.");
               }
            }

            if (d.draftPickDeadline != 0L && now >= d.draftPickDeadline) {
               int idx = (int)(Math.random() * d.draftPool.size());
               String cat = (String)d.draftPool.get(idx);
               announce(d, "&e" + turn.displayName + "&7 took too long - &e" + draftCategoryName(cat) + "&7 was assigned at random!");
               pickDraftCategory(d, turn, cat);
            } else {
               if (d.draftPickDeadline != 0L && d.draftPickDeadline - now == 100L) {
                  announce(d, "&e" + turn.displayName + "&7 - pick now! &c5 seconds&7 left!");
               }

               if (now >= d.phaseEndsAt) {
                  announce(d, "&eDraft time is up - the rest are assigned at random!");

                  while (!draftComplete(d)) {
                     int idx = (int)(Math.random() * d.draftPool.size());
                     Participant recv = (Participant)d.parts.get(d.draftTurn % d.parts.size());
                     pickDraftCategory(d, recv, (String)d.draftPool.get(idx));
                  }

                  startCountdown(d);
               }
            }
         }
      }
   }

   private static boolean draftComplete(Duel d) {
      if (d.draftPool.isEmpty()) {
         return true;
      }

      for (Participant part : d.parts) {
         if (part.draftPicks < 5) {
            return false;
         }
      }

      return true;
   }

   private static void pickDraftCategory(Duel d, Participant recv, String category) {
      d.draftPool.remove(category);
      d.draftPrompted.clear();
      d.draftTurn++;
      d.draftPickDeadline = 0L;
      recv.draftPicks++;
      giveDraftKit(recv, category);
      announce(
         d,
         "&f"
            + recv.displayName
            + "&7 drafted &e"
            + draftCategoryName(category)
            + "&7 ("
            + recv.draftPicks
            + "/5 picks, "
            + d.draftPool.size()
            + " kit"
            + (d.draftPool.size() == 1 ? "" : "s")
            + " left)."
      );
      DraftMenu.rebuildAll();
      if (draftComplete(d)) {
         announce(d, "&a⚡ Draft complete - the duel starts in 5 seconds!");
         startCountdown(d);
      }
   }

   public static List<String> draftPoolFor(ServerPlayer p) {
      if (p == null) {
         return List.of();
      }

      Duel d = duels.get(p.getUUID());
      return d != null && d.mode == DuelMode.DRAFT && d.phase == Phase.LOBBY ? new ArrayList<>(d.draftPool) : List.of();
   }

   public static int draftSecondsLeft(ServerPlayer p) {
      if (p == null) {
         return 0;
      }

      Duel d = duels.get(p.getUUID());
      if (d != null && d.mode == DuelMode.DRAFT && d.phase == Phase.LOBBY && !d.parts.isEmpty()) {
         long now = ServerClock.clock(p.level());
         Participant turn = (Participant)d.parts.get(d.draftTurn % d.parts.size());
         long deadline = d.phaseEndsAt;
         if (turn != null && !turn.bot && turn.player != null && turn.player.getUUID().equals(p.getUUID()) && d.draftPickDeadline != 0L) {
            deadline = d.draftPickDeadline;
         }

         return (int)Math.max(0.0, Math.ceil((deadline - now) / 20.0));
      } else {
         return 0;
      }
   }

   public static String pickDraft(ServerPlayer p, String category) {
      Duel d = duels.get(p.getUUID());
      if (d != null && d.mode == DuelMode.DRAFT && d.phase == Phase.LOBBY) {
         Participant part = participantOf(d, p);
         if (part == null) {
            return null;
         }

         Participant turn = (Participant)d.parts.get(d.draftTurn % d.parts.size());
         if (turn != part) {
            return "It's not your turn to pick.";
         }

         if (!d.draftPool.contains(category)) {
            return "That kit was already taken.";
         }

         pickDraftCategory(d, part, category);
         return null;
      } else {
         return "There's no draft running right now.";
      }
   }

   private static void giveDraftKit(Participant part, String category) {
      ServerPlayer p = part.player;
      if (p != null) {
         List<ItemStack> stacks = new ArrayList<>();
         switch (category) {
            case "sword":
               ItemStack sw = new ItemStack(Items.DIAMOND_SWORD);
               enchantStack(p, sw, Enchantments.SHARPNESS, 1);
               stacks.add(sw);
               break;
            case "bow":
               stacks.add(new ItemStack(Items.BOW));
               stacks.add(new ItemStack(Items.ARROW, 16));
               break;
            case "armor_diamond":
               part.draftArmor.add(new ItemStack(Items.DIAMOND_HELMET));
               part.draftArmor.add(new ItemStack(Items.DIAMOND_CHESTPLATE));
               part.draftArmor.add(new ItemStack(Items.DIAMOND_LEGGINGS));
               part.draftArmor.add(new ItemStack(Items.DIAMOND_BOOTS));
               setArmor(p, Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS);
               break;
            case "armor_iron":
               part.draftArmor.add(new ItemStack(Items.IRON_HELMET));
               part.draftArmor.add(new ItemStack(Items.IRON_CHESTPLATE));
               part.draftArmor.add(new ItemStack(Items.IRON_LEGGINGS));
               part.draftArmor.add(new ItemStack(Items.IRON_BOOTS));
               setArmor(p, Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS);
               break;
            case "blocks":
               stacks.add(new ItemStack(whiteWool(), 64));
               stacks.add(new ItemStack(Items.OAK_PLANKS, 32));
               break;
            case "pearl":
               stacks.add(new ItemStack(Items.ENDER_PEARL, 2));
               break;
            case "heal":
               ItemStack heal = new ItemStack(Items.SPLASH_POTION);
               heal.set(DataComponents.POTION_CONTENTS, PotionContents.EMPTY.withPotion(Potions.HEALING));
               stacks.add(heal);
               stacks.add(heal.copy());
               break;
            case "speed":
               ItemStack speed = new ItemStack(Items.SPLASH_POTION);
               speed.set(DataComponents.POTION_CONTENTS, PotionContents.EMPTY.withPotion(Potions.STRONG_SWIFTNESS));
               stacks.add(speed);
               stacks.add(speed.copy());
               break;
            case "gapple":
               stacks.add(new ItemStack(Items.GOLDEN_APPLE, 4));
               break;
            case "gapple_head":
               stacks.add(goldenAppleHead(4));
               break;
            case "axe":
               stacks.add(new ItemStack(Items.IRON_AXE));
               break;
            case "shield":
               stacks.add(new ItemStack(Items.SHIELD));
               break;
            case "rod":
               stacks.add(new ItemStack(Items.FISHING_ROD));
         }

         for (ItemStack s : stacks) {
            part.draftKit.add(s.copy());
            InventoryHelper.giveOrDrop(p, s);
         }
      }
   }

   private static String draftCategoryName(String category) {
      return switch (category) {
         case "sword" -> "Diamond Sword";
         case "bow" -> "Bow + Arrows";
         case "armor_diamond" -> "Full Diamond Armor";
         case "armor_iron" -> "Full Iron Armor";
         case "blocks" -> "Blocks";
         case "pearl" -> "Ender Pearls";
         case "heal" -> "Healing Potions";
         case "speed" -> "Speed Potions";
         case "gapple" -> "Golden Apples";
         case "gapple_head" -> "Golden Apple Head";
         case "axe" -> "Battle Axe";
         case "shield" -> "Shield";
         case "rod" -> "Fishing Rod";
         default -> category;
      };
   }

   public static String draftCategoryDisplayName(String category) {
      return draftCategoryName(category);
   }

   public static boolean draftIsMyTurn(ServerPlayer p) {
      if (p == null) {
         return false;
      } else {
         Duel d = duels.get(p.getUUID());
         if (d != null && d.mode == DuelMode.DRAFT && d.phase == Phase.LOBBY && !d.parts.isEmpty()) {
            Participant turn = (Participant)d.parts.get(d.draftTurn % d.parts.size());
            return turn != null && turn.player != null && turn.player.getUUID().equals(p.getUUID());
         } else {
            return false;
         }
      }
   }

   public static int draftPicksFor(ServerPlayer p) {
      if (p == null) {
         return 0;
      } else {
         Duel d = duels.get(p.getUUID());
         if (d != null && d.mode == DuelMode.DRAFT && d.phase == Phase.LOBBY) {
            Participant part = participantOf(d, p);
            return part != null ? part.draftPicks : 0;
         } else {
            return 0;
         }
      }
   }

   private static void tickModeRules(Duel d, ServerLevel realm, long now) {
      if (d.mode == DuelMode.UHCDUEL) {
         if (now % 5L == 0L) {
            for (Participant part : d.parts) {
               if (!part.bot && part.player != null && part.player.isAlive()) {
                  MobEffectInstance regen = part.player.getEffect(MobEffects.REGENERATION);
                  if (regen != null && regen.getAmplifier() == 0) {
                     part.player.removeEffect(MobEffects.REGENERATION);
                  }

                  part.player.removeEffect(MobEffects.ABSORPTION);
               }
            }
         }
      } else if (d.mode == DuelMode.COMBODUEL) {
         for (Participant part : d.parts) {
            if (part.bot) {
               if (part.botEntity != null) {
                  part.botEntity.invulnerableTime = 0;
               }
            } else if (part.player != null) {
               part.player.invulnerableTime = 0;
            }
         }

         if (now % 20L == 0L) {
            for (Participant part : d.parts) {
               if (!part.bot && part.player != null && part.player.isAlive()) {
                  part.player.removeEffect(MobEffects.SLOWNESS);
                  if (!part.player.hasEffect(MobEffects.SPEED)) {
                     part.player.addEffect(new MobEffectInstance(MobEffects.SPEED, 120, 1, false, false));
                  }

                  if (part.comboCount > 0) {
                     long idle = now - part.comboLastHitAt;
                     if (idle >= 100L) {
                        breakCombo(part);
                     } else {
                        double left = (100L - idle) / 20.0;
                        String color = part.comboCount >= 10 ? "§c§l" : (part.comboCount >= 5 ? "§6" : "§e");
                        part.player
                           .sendSystemMessage(
                              Component.literal(color + "⚔ " + part.comboCount + " Combo! §7[" + String.format(Locale.US, "%.1f", left) + "s]"), true
                           );
                     }
                  }
               }
            }
         }
      } else if (d.mode == DuelMode.RANDOMIZER) {
         if (now % 200L == 0L) {
            for (Participant part : d.parts) {
               Entity entity = part.bot ? part.botEntity : part.player;
               if (entity instanceof ServerPlayer sp && sp.isAlive()) {
                  rollRandomizerWeapon(sp);
               }
            }
         }
      } else if (d.mode == DuelMode.GLADIATOR) {
         // A gladiator world is a place to walk across, so the one buff every
         // fighter gets is refreshed rather than handed out once: a player who dies
         // and respawns must not come back slower than the other.
         if (now % 20L == 0L) {
            for (Participant part : d.parts) {
               Entity entity = part.bot ? part.botEntity : part.player;
               if (entity instanceof ServerPlayer sp && sp.isAlive()) {
                  sp.addEffect(new MobEffectInstance(MobEffects.SPEED, 60, 0, false, false, true));
               }
            }
         }

         {
            // The hunt clock. For the first stretch the map is somewhere to gear
            // up in peace; then the fighters light up, so a match cannot be won by
            // burying yourself and waiting out a patient opponent.
            if (d.gladHighlightAt < 0L) {
               d.gladHighlightAt = now + GLADIATOR_QUIET_TICKS;
               announce(
                  d,
                  "&7Gear up - the hunt begins in &e" + GLADIATOR_QUIET_TICKS / 20 + "s&7, when every fighter lights up."
               );
               announce(d, "&7Until then &fno blow lands between fighters&7 - dig, loot, and then hunt.");
            } else if (now < d.gladHighlightAt) {
               // The window on screen, once a second. Two and a half minutes is long
               // enough that a fighter who has lost track of it is a fighter who gets
               // ambushed while still holding a stone pickaxe - and a countdown is also
               // what makes "the prep time is 0" visible as a number instead of a
               // feeling.
               if (now % 20L == 0L) {
                  long left = (d.gladHighlightAt - now + 19L) / 20L;

                  for (Participant part : d.parts) {
                     Entity entity = part.bot ? part.botEntity : part.player;
                     if (entity instanceof ServerPlayer sp && sp.isAlive() && sp.connection != null) {
                        sp.connection.send(
                           new ClientboundSetActionBarTextPacket(
                              Component.literal("§7Gearing up §8| §ehunt begins in §f" + left + "s")
                           )
                        );
                     }
                  }

                  if (left == 60L || left == 30L || left == 10L) {
                     announce(d, "&7The hunt begins in &e" + left + "s&7 - every fighter lights up.");
                  }
               }
            } else if (now >= d.gladHighlightAt) {
               if (now % 20L == 0L) {
                  for (Participant part : d.parts) {
                     Entity entity = part.bot ? part.botEntity : part.player;
                     if (entity instanceof ServerPlayer sp && sp.isAlive()) {
                        sp.addEffect(new MobEffectInstance(MobEffects.GLOWING, 60, 0, false, false, false));
                     }
                  }
               }
            }

            // The glow says you have been seen; the compass says which way to walk.
            // It is handed out and re-aimed from the very first tick now, so the map
            // has a direction in it while everyone is still gearing up - a tracker
            // that only appears ninety seconds in reads as a tracker that does not
            // work.
            gladAimTrackers(d, realm, now);

            // And nothing dies here. A body that died anyway - a damage source that
            // never asked this module, a hole punched through the floor while the
            // sweep was still clearing - is turned into the elimination it should
            // have been instead of being left as a corpse in a world whose whole
            // rule is that a mistake costs you the match, not your life.
            gladRescueBodies(d);
         }
      } else {
         if (d.mode == DuelMode.LASTSTAND) {
            for (Participant part : d.parts) {
               if (part.lsRespawning && part.player != null && part.player.isAlive() && now >= part.lsRespawnUntil) {
                  part.lsRespawning = false;
                  if (!part.bot) {
                     part.player.setInvulnerable(false);
                  }
               }
            }
         }

         if (d.mode == DuelMode.LUCKYPvP && now % 4L == 0L) {
            for (Participant part : d.parts) {
               if (!part.bot && part.player != null && part.player.isAlive()) {
                  ItemStack held = part.player.getMainHandItem();
                  if (held.has(DataComponents.CUSTOM_NAME) && ((Component)held.get(DataComponents.CUSTOM_NAME)).getString().contains("Excalibur")) {
                     com.fortuneandfavors.net.FfVfx.particles(realm, ParticleTypes.END_ROD, part.player.getX(), part.player.getY() + 1.3, part.player.getZ(), 3, 0.25, 0.4, 0.25, 0.01);
                  }
               }
            }
         }
      }
   }

   private static void rollRandomizerWeapon(ServerPlayer sp) {
      for (int i = 0; i < sp.getInventory().getContainerSize(); i++) {
         ItemStack s = sp.getInventory().getItem(i);
         if (!s.isEmpty() && isWeaponish(s.getItem())) {
            sp.getInventory().setItem(i, ItemStack.EMPTY);
         }
      }

      ItemStack weapon = randomizerWeapon(sp);
      sp.getInventory().setItem(0, weapon);
      sp.getInventory().setSelectedSlot(0);
      if (weapon.getItem() instanceof BowItem) {
         sp.getInventory().setItem(1, new ItemStack(Items.ARROW, 16));
      }

      if (sp.level() instanceof ServerLevel sl) {
         com.fortuneandfavors.net.FfVfx.particles(sl, ParticleTypes.END_ROD, sp.getX(), sp.getY() + 1.4, sp.getZ(), 14, 0.3, 0.5, 0.3, 0.06);
         com.fortuneandfavors.net.FfVfx.particles(sl, ParticleTypes.TOTEM_OF_UNDYING, sp.getX(), sp.getY() + 1.0, sp.getZ(), 10, 0.4, 0.4, 0.4, 0.12);
         sl.playSound(null, sp.getX(), sp.getY(), sp.getZ(), SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 1.0F, 2.0F);
         sl.playSound(null, sp.getX(), sp.getY(), sp.getZ(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.6F, 1.8F);
      }

      if (!(sp instanceof DuelBot)) {
         Chat.msg(sp, "&d⚡ New weapon: &f" + weapon.getItem().getName(weapon).getString() + "&d!");
      }
   }

   private static boolean isWeaponish(Item item) {
      return item == null
         ? false
         : isSword(item)
            || item == Items.BOW
            || item == Items.CROSSBOW
            || item == Items.MACE
            || item == Items.TRIDENT
            || item == Items.FISHING_ROD
            || item == Items.WOODEN_AXE
            || item == Items.STONE_AXE
            || item == Items.IRON_AXE
            || item == Items.DIAMOND_AXE
            || item == Items.GOLDEN_AXE
            || item == Items.NETHERITE_AXE
            || item == Items.STICK;
   }

   private static ItemStack randomizerWeapon(ServerPlayer p) {
      int r = (int)(Math.random() * 100.0);
      ItemStack s;
      if (r < 8) {
         s = new ItemStack(Items.NETHERITE_SWORD);
      } else if (r < 18) {
         s = new ItemStack(Items.DIAMOND_SWORD);
      } else if (r < 30) {
         s = new ItemStack(Items.IRON_SWORD);
      } else if (r < 40) {
         s = new ItemStack(Items.STONE_SWORD);
      } else if (r < 46) {
         s = new ItemStack(Items.WOODEN_SWORD);
      } else if (r < 54) {
         s = new ItemStack(Items.DIAMOND_AXE);
      } else if (r < 62) {
         s = new ItemStack(Items.IRON_AXE);
      } else if (r < 68) {
         s = new ItemStack(Items.MACE);
      } else if (r < 74) {
         s = new ItemStack(Items.TRIDENT);
         enchantStack(p, s, Enchantments.LOYALTY, 3);
         if (p instanceof DuelBot) {
            // Same spear, plus the riptide the fight AI knows how to spend: it reads
            // water or rain, spins the ten ticks vanilla asks for, and lets go.
            enchantStack(p, s, Enchantments.RIPTIDE, 2);
         }
      } else if (r < 82) {
         s = new ItemStack(Items.BOW);
      } else if (r < 88) {
         s = new ItemStack(Items.CROSSBOW);
      } else {
         if (r < 94) {
            s = new ItemStack(Items.STICK);
            s.set(DataComponents.CUSTOM_NAME, Component.literal("§6§lKnockback Stick"));
            enchantStack(p, s, Enchantments.KNOCKBACK, 2);
            return s;
         }

         s = new ItemStack(Items.FISHING_ROD);
      }

      if (Math.random() < 0.33 && s.getItem() != Items.FISHING_ROD) {
         int e = (int)(Math.random() * 4.0);
         if (e == 0) {
            enchantStack(p, s, Enchantments.SHARPNESS, 1 + (int)(Math.random() * 2.0));
         } else if (e == 1) {
            enchantStack(p, s, Enchantments.FIRE_ASPECT, 1);
         } else if (e == 2) {
            enchantStack(p, s, Enchantments.KNOCKBACK, 1 + (int)(Math.random() * 2.0));
         } else {
            enchantStack(p, s, Enchantments.SMITE, 1 + (int)(Math.random() * 3.0));
         }
      }

      return s;
   }

   public static boolean isUhcDuel(ServerPlayer p) {
      if (p != null && !p.level().isClientSide()) {
         Duel d = duels.get(p.getUUID());
         return d != null && d.mode == DuelMode.UHCDUEL && d.phase == Phase.FIGHT;
      } else {
         return false;
      }
   }

   public static ItemStack goldenAppleHead(int count) {
      ItemStack head = new ItemStack(Items.PLAYER_HEAD, count);
      head.set(DataComponents.CUSTOM_NAME, Component.literal("§6§lGolden Apple Head"));
      head.set(
         DataComponents.LORE,
         new ItemLore(List.of(Component.literal("§7Right-click to eat - golden apple power"), Component.literal("§8Restores like a golden apple")))
      );
      head.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
      PropertyMap props = new PropertyMap(ImmutableMultimap.of()) {
      private final Multimap<String, Property> backing = LinkedHashMultimap.create();
      protected Multimap<String, Property> delegate() { return this.backing; }
   };
      props.put(
         "textures",
         new Property(
            "textures",
            "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNDIxY2FiNDA5NWU3MWJkOTI1Y2Y0NjQ5OTBlMThlNDNhZGI3MjVkYjdjYzE3NWZkOWQxZGVjODIwOTE0YjNkZSJ9fX0="
         )
      );
      GameProfile gp = new GameProfile(UUID.fromString("9c177d3a-a9b3-4140-a930-8e35e9299fb4"), "GoldenApple", props);
      head.set(DataComponents.PROFILE, ResolvableProfile.createResolved(gp));
      return head;
   }

   public static boolean isGoldenAppleHeadAllowed(ServerPlayer p) {
      if (p != null && !p.level().isClientSide()) {
         Duel d = duels.get(p.getUUID());
         return d != null && d.phase == Phase.FIGHT && (d.mode == DuelMode.UHCDUEL || d.mode == DuelMode.DRAFT || d.mode == DuelMode.KITS);
      } else {
         return false;
      }
   }

   public static boolean isGoldenAppleHead(ItemStack stack) {
      if (stack != null && !stack.isEmpty() && stack.is(Items.PLAYER_HEAD)) {
         Component name = (Component)stack.get(DataComponents.CUSTOM_NAME);
         return name != null && name.getString().contains("Golden Apple Head");
      } else {
         return false;
      }
   }

   public static boolean eatGoldenAppleHead(ServerPlayer p, ItemStack stack) {
      if (p != null && !p.level().isClientSide() && isGoldenAppleHead(stack)) {
         p.heal(4.0F);
         p.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 100, 1, false, true));
         p.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 2400, 1, false, true));
         p.getFoodData().eat(4, 9.6F);
         stack.shrink(1);
         p.swing(InteractionHand.MAIN_HAND);
         ServerLevel sl = p.level();
         com.fortuneandfavors.net.FfVfx.particles(sl, ParticleTypes.TOTEM_OF_UNDYING, p.getX(), p.getY() + 1.0, p.getZ(), 40, 0.5, 0.7, 0.5, 0.6);
         com.fortuneandfavors.net.FfVfx.particles(sl, ParticleTypes.ENCHANT, p.getX(), p.getY() + 1.2, p.getZ(), 30, 0.4, 0.6, 0.4, 1.0);
         com.fortuneandfavors.net.FfVfx.particles(sl, ParticleTypes.HAPPY_VILLAGER, p.getX(), p.getY() + 1.5, p.getZ(), 15, 0.3, 0.4, 0.3, 0.3);
         com.fortuneandfavors.net.FfVfx.particles(sl, ParticleTypes.FLAME, p.getX(), p.getY() + 0.2, p.getZ(), 20, 0.3, 0.3, 0.3, 0.05);

         for (double angle = 0.0; angle < Math.PI * 2; angle += Math.PI / 8) {
            double ox = Math.cos(angle) * 1.5;
            double oz = Math.sin(angle) * 1.5;
            com.fortuneandfavors.net.FfVfx.particles(sl, ParticleTypes.END_ROD, p.getX() + ox, p.getY() + 0.5, p.getZ() + oz, 1, 0.0, 0.1, 0.0, 0.02);
         }

         p.level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.GENERIC_EAT, SoundSource.PLAYERS, 1.0F, (float)(0.9 + Math.random() * 0.2));
         p.level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.5F, 1.5F);
         p.level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 0.7F, 1.2F);

         for (double angle = 0.0; angle < Math.PI * 2; angle += Math.PI / 6) {
            double ox = Math.cos(angle) * 2.5;
            double oz = Math.sin(angle) * 2.5;
            com.fortuneandfavors.net.FfVfx.particles(sl, ParticleTypes.END_ROD, p.getX() + ox, p.getY() + 0.3, p.getZ() + oz, 2, 0.0, 0.2, 0.0, 0.02);
         }

         for (double y = 0.0; y < 2.0; y += 0.4) {
            com.fortuneandfavors.net.FfVfx.particles(sl, ParticleTypes.ENCHANT, p.getX(), p.getY() + y, p.getZ(), 6, 0.6, 0.1, 0.6, 0.8);
         }

         com.fortuneandfavors.net.FfVfx.particles(sl, ParticleTypes.SOUL_FIRE_FLAME, p.getX(), p.getY() + 0.1, p.getZ(), 25, 0.3, 0.8, 0.3, 0.03);
         long eatNow = System.currentTimeMillis();
         Long lastFlash = goldenFlashCooldown.getOrDefault(p.getUUID(), 0L);
         if (eatNow - lastFlash > 1000L && p.connection != null) {
            goldenFlashCooldown.put(p.getUUID(), eatNow);
            FfNet.send(p, new FfScreenFxPayload(5, true));
         }

         return true;
      } else {
         return false;
      }
   }

   public static boolean isRandomizerDuel(ServerPlayer p) {
      if (p != null && !p.level().isClientSide()) {
         Duel d = duels.get(p.getUUID());
         return d != null && d.mode == DuelMode.RANDOMIZER && d.phase == Phase.FIGHT;
      } else {
         return false;
      }
   }

   private static void pinDuelDifficulty(MinecraftServer server) {
      boolean anyLive = false;

      for (Duel d : duels.values()) {
         if (d.phase != Phase.ENDED) {
            anyLive = true;
            break;
         }
      }

      WorldData wd = server.getWorldData();

      try {
         if (anyLive) {
            if (originalDifficulty == null) {
               originalDifficulty = wd.getDifficulty();
            }

            if (wd.getDifficulty() == Difficulty.PEACEFUL) {
               wd.setDifficulty(Difficulty.NORMAL);
            }
         } else if (originalDifficulty != null) {
            if (wd.getDifficulty() != originalDifficulty) {
               wd.setDifficulty(originalDifficulty);
            }

            originalDifficulty = null;
         }
      } catch (Exception var4) {
      }
   }

   public static BotBlow handleBotDamage(DuelBot bot, DamageSource source, float amount) {
      Duel d = duels.get(bot.getUUID());
      return d == null ? BotBlow.PASS : handleBotDamage(d, bot, source, amount);
   }

   public static BotBlow handleBotDamage(Duel d, DuelBot bot, DamageSource source, float amount) {
      try {
         return handleBotDamageInner(d, bot, source, amount);
      } catch (Throwable t) {
         FortuneFavorsMod.LOGGER.error("Fortune & Favors: handleBotDamage failed", t);
         return BotBlow.PASS;
      }
   }

   private static BotBlow handleBotDamageInner(Duel d, DuelBot bot, DamageSource source, float amount) {
      Participant botPart = null;

      for (Participant part : d.parts) {
         if (part.bot && part.botEntity != null && part.botEntity.getUUID().equals(bot.getUUID())) {
            botPart = part;
            break;
         }
      }

      if (botPart != null && d.phase == Phase.FIGHT && bot.isAlive() && !(amount <= 0.0F)) {
         // Anti-spam: melee is limited by a per-bot tick window, but PROJECTILES
         // (arrows) always land - previously the 20-tick invulnerableTime the
         // melee loop kept re-pinning made bots effectively immune to bows.
         //
         // A falling mace smash is a third exception, and it is the one that made
         // "the mace does nothing to a bot" outlive the fix that handed the smash
         // back to vanilla: the smash now arrives through this gate like any other
         // blow, and a player lining one up has usually just swung, so the pacing
         // window ate the one heavy hit of the fight. Vanilla's own answer to "is
         // this a smash" is asked for here rather than a second copy of the fall
         // threshold.
         boolean projectile = source.getDirectEntity() instanceof net.minecraft.world.entity.projectile.Projectile;
         boolean smash = source.getDirectEntity() instanceof LivingEntity hitter && MaceItem.canSmashAttack(hitter);
         if (!projectile && !smash) {
            long last = botLastHitTick.getOrDefault(bot.getUUID(), 0L);
            if (ServerClock.clock(bot.level()) - last < 4L) {
               return BotBlow.DEFLECTED;
            }
            botLastHitTick.put(bot.getUUID(), ServerClock.clock(bot.level()));
         }

         ServerLevel realm = bot.level();
         // Environmental damage is the arena's, not a weapon's. A shield does not
         // stop a fall and armour does not soften one, and letting both apply made
         // a bot in diamond gear immune to the map - which is the opposite of the
         // survival limits the fight is supposed to have.
         boolean environmental = source.is(DamageTypeTags.IS_FALL)
            || source.is(DamageTypeTags.IS_FIRE)
            || source.is(DamageTypeTags.IS_DROWNING)
            || source.is(DamageTypeTags.IS_FREEZING);
         if (!environmental) {
            amount = botShieldAbsorb(bot, realm, source, amount);
            if (amount <= 0.0F) {
               return BotBlow.DEFLECTED;
            }
         }

         float armor = 0.0F;
         float toughness = 0.0F;
         AttributeInstance armorAttr = bot.getAttribute(Attributes.ARMOR);
         AttributeInstance toughAttr = bot.getAttribute(Attributes.ARMOR_TOUGHNESS);
         if (armorAttr != null) {
            armor = (float)armorAttr.getValue();
         }

         if (toughAttr != null) {
            toughness = (float)toughAttr.getValue();
         }

         float dmg = environmental ? amount : CombatRules.getDamageAfterAbsorb(bot, amount, source, armor, toughness);
         // A legacy duel is a 1.8 duel, and in 1.8 a raised sword halves a melee
         // blow it is facing. The mixin that does this for players hangs off
         // `actuallyHurt`, which a bot's damage never reaches - its blows are
         // applied here - so the same 50% has to be asked for here or the bot's
         // sword block does nothing at all.
         if (d.combat == CombatStyle.LEGACY
            && !environmental
            && bot.isBlocking()
            && bot.getMainHandItem().is(ItemTags.SWORDS)
            && botBlockFaces(bot, source)) {
            dmg *= 0.5F;
         }

         if (dmg <= 0.0F) {
            return BotBlow.DEFLECTED;
         }

         // A bot never goes through hurtServer - its damage is applied by hand
         // below - so AFTER_DAMAGE never fires for it and this is the only place
         // the match report can learn that the blow happened at all.
         recordHitLedger(d, playerAttackerOf(source), botPart, dmg);

         bot.invulnerableTime = 10;
         if (d.mode == DuelMode.LASTSTAND && bot.getHealth() - dmg <= 0.5F) {
            handleLastStandDeath(d, botPart, bot, source);
            if (botPart.lives > 0 && bot.isAlive()) {
               bot.setHealth(bot.getMaxHealth());
            }

            return BotBlow.LANDED;
         } else {
            if (bot.getHealth() - dmg <= 0.5F && hasBotTotem(bot)) {
               popBotTotem(realm, bot);
               return BotBlow.LANDED;
            }

            if (bot.getHealth() - dmg <= 0.5F) {
               bot.setHealth(0.0F);
               ServerPlayer attacker = playerAttackerOf(source);
               if (attacker != null) {
                  hitFx(realm, bot, attacker);
               } else {
                  com.fortuneandfavors.net.FfVfx.particles(realm, ParticleTypes.CRIT, bot.getX(), bot.getY() + 1.2, bot.getZ(), 10, 0.3, 0.5, 0.3, 0.1);
                  realm.playSound(null, bot.getX(), bot.getY(), bot.getZ(), SoundEvents.PLAYER_HURT, SoundSource.PLAYERS, 1.0F, 0.9F);
               }

               Participant human = null;

               for (Participant part : d.parts) {
                  if (!part.bot) {
                     human = part;
                     break;
                  }
               }

               endDuel(d, human, "&aYou defeated " + botPart.displayName + " - the duel is yours!");
               return BotBlow.LANDED;
            } else {
               bot.setHealth(Math.max(0.0F, bot.getHealth() - dmg));
               bot.hurtTime = 5;
               bot.hurtMarked = true;
               // A player spends hunger on every hit they take; the bot's damage is
               // applied here rather than through `actuallyHurt`, so nothing did.
               bot.causeFoodExhaustion(0.1F);
               // Remember the spot. A bot that keeps walking back into the trap
               // that just took a third of its health is not fighting, it is
               // feeding.
               if (d.botDanger.size() < 64) {
                  d.botDanger.put(bot.blockPosition().asLong(), ServerClock.clock(bot.level()) + 120L);
               }
               ServerPlayer attacker = playerAttackerOf(source);
               if (attacker != null) {
                  hitFx(realm, bot, attacker);
               } else {
                  com.fortuneandfavors.net.FfVfx.particles(realm, ParticleTypes.SMOKE, bot.getX(), bot.getY() + 1.1, bot.getZ(), 6, 0.2, 0.3, 0.2, 0.02);
                  realm.playSound(null, bot.getX(), bot.getY(), bot.getZ(), SoundEvents.PLAYER_HURT, SoundSource.PLAYERS, 1.0F, 0.9F);
               }

               try {
                  for (ServerPlayer v : realm.getPlayers(p -> p != null)) {
                     v.connection.send(new ClientboundHurtAnimationPacket(bot));
                  }
               } catch (Exception var15) {
               }

               for (Participant part : d.parts) {
                  if (part.bot && part.botBossBar != null) {
                     part.botBossBar.setProgress(Math.max(0.0F, Math.min(1.0F, bot.getHealth() / bot.getMaxHealth())));
                  }
               }

               return BotBlow.LANDED;
            }
         }
      } else {
         return BotBlow.PASS;
      }
   }

   /**
    * Runs a blow into the bot's raised shield, if it has one, and returns what is
    * left of it after the block.
    *
    * <p>A raised shield is the bot's own shield now, and this is where it pays:
    * the blow goes through `applyItemBlocking`, the same vanilla entry `hurtServer`
    * uses, so the block angle, the item's own reduction, the durability and the
    * block sound are the shield's rules rather than a number invented here. It used
    * to be nothing at all - the bot could hold a shield and still take every point
    * of an arrow in the face. Kept as its own call so the self-test can assert the
    * gate's shield path without standing up a whole duel.
    */
   public static float botShieldAbsorb(DuelBot bot, ServerLevel realm, DamageSource source, float amount) {
      float blocked = bot.applyItemBlocking(realm, source, amount);
      if (blocked <= 0.0F) {
         return amount;
      }
      bot.hurtTime = 5;
      bot.hurtMarked = true;
      return Math.max(0.0F, amount - blocked);
   }

   /**
    * True when a blow is coming from roughly in front of the bot, by the same
    * ~100 degree cone the player's own 1.8 sword block uses.
    */
   private static boolean botBlockFaces(DuelBot bot, DamageSource source) {
      Vec3 srcPos = source.getSourcePosition();
      if (srcPos == null) {
         return true;
      }
      Vec3 look = bot.getLookAngle();
      Vec3 to = srcPos.subtract(bot.position());
      double lookLen = Math.sqrt(look.x * look.x + look.z * look.z);
      double toLen = Math.sqrt(to.x * to.x + to.z * to.z);
      if (lookLen < 1.0E-4 || toLen < 1.0E-4) {
         return true;
      }
      return (look.x * to.x + look.z * to.z) / (lookLen * toLen) >= 0.64;
   }

   /**
    * Spends the totem in this fighter's hands, if one is there to spend.
    *
    * <p>Deliberately asked of the *hands* and nothing else, in every mode and whatever landed
    * the blow - a sword, an arrow, a fall, a lava pool a Gladiator map opened under somebody.
    * A totem that only works when the killer happens to be standing there is not a totem.
    *
    * <p>Two exceptions, both on purpose. A bot is a body this code drives and its own totem is
    * already popped by {@link #popBotTotem}, so spending it here as well would take two. And
    * <b>Excalibur</b> cuts through one: the weapon's entire description is that nothing saves
    * you from it, and that has to be true in a duel or it is not true anywhere.
    *
    * <p>What this does is everything vanilla's own save does - the totem is consumed, health is
    * set to one, the effects are granted and the burst is drawn - so a fighter cannot tell the
    * difference between a duel save and a survival one except that they are still in the match.
    *
    * @return true when the totem answered the blow, in which case the caller refuses the damage
    */
   /**
    * Whether a totem in this fighter's hands is the answer to this blow, and nothing else.
    *
    * <p>Two questions, in this order, and the order is the whole of the bug this exists to
    * prevent: <b>would the blow kill them</b>, and only then <b>do they have a totem</b>. It used
    * to ask only the second - so *every* blow in a duel burned a totem, and because the ritual
    * drops a body to one heart, a fighter at full health who was hit for two damage lost the
    * item that was meant to save their life and was left one hit from death. That is what
    * "getting hit at max hp, if I have a totem I instantly die and pop totem" was.
    *
    * <p>The arithmetic is {@code LethalBlows}' - the same figure the ordered walk uses, after
    * armor, with absorption counted - so the duel gate and every other rule in the mod agree on
    * what a fatal blow is. Public so a check can ask the same question without a live match.
    */
   public static boolean aTotemWouldAnswer(ServerPlayer p, DamageSource source, float amount) {
      if (p == null || !p.isAlive()) {
         return false;
      }

      if (!com.fortuneandfavors.economy.LethalBlows.wouldKill(p, source, amount)) {
         return false;
      }

      return com.fortuneandfavors.economy.LethalBlows.carriesTotem(p);
   }

   private static boolean popDuelTotem(Participant part, ServerPlayer p, DamageSource source, float amount) {
      try {
         if (part == null || part.bot || p == null || !p.isAlive()) {
            return false;
         }

         if (!aTotemWouldAnswer(p, source, amount)) {
            return false;
         }

         // The spend itself is vanilla's, and it lives in exactly one place now: this gate
         // refuses a blow, and refusing it is what stops vanilla from ever reaching its own totem
         // check - so the ritual is performed for it. Excalibur is refused inside the same call.
         if (!com.fortuneandfavors.economy.LethalBlows.spendTotem(p, source)) {
            return false;
         }

         String name = part.displayName != null ? part.displayName : p.getName().getString();
         Chat.msg(p, "&6&lTOTEM! &eThe totem burns for you - &f" + name + "&e is still in the match.");
         if (p.connection != null) {
            p.connection.send(new ClientboundSetTitleTextPacket(Component.literal("\u00a76\u00a7lTOTEM SAVE")));
            p.connection.send(new ClientboundSetTitlesAnimationPacket(2, 30, 8));
            p.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal("\u00a77You are still in this fight - \u00a7fget clear")));
         }

         return true;
      } catch (Throwable t) {
         // A broken save must not become an eaten totem: if anything above threw, the fighter
         // keeps the totem and the blow lands the way it would have without this method.
         return false;
      }
   }

   private static boolean hasBotTotem(DuelBot bot) {
      return bot.getMainHandItem().is(Items.TOTEM_OF_UNDYING) || bot.getOffhandItem().is(Items.TOTEM_OF_UNDYING);
   }

   private static void popBotTotem(ServerLevel realm, DuelBot bot) {
      ItemStack totem = bot.getMainHandItem().is(Items.TOTEM_OF_UNDYING) ? bot.getMainHandItem() : bot.getOffhandItem();
      totem.shrink(1);
      bot.setHealth(1.0F);
      bot.removeAllEffects();
      bot.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 900, 1));
      bot.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 100, 1));
      bot.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 800, 0));
      bot.invulnerableTime = 40;
      com.fortuneandfavors.net.FfVfx.particles(realm, ParticleTypes.TOTEM_OF_UNDYING, bot.getX(), bot.getY() + 1.0, bot.getZ(), 60, 0.5, 0.8, 0.5, 0.4);
      realm.playSound(null, bot.getX(), bot.getY(), bot.getZ(), SoundEvents.TOTEM_USE, SoundSource.PLAYERS, 1.2F, 1.0F);
   }

   private static ServerPlayer playerAttackerOf(DamageSource source) {
      if (source == null) {
         return null;
      } else if (source.getEntity() instanceof ServerPlayer p) {
         return p;
      } else {
         return source.getDirectEntity() instanceof Projectile pr && pr.getOwner() instanceof ServerPlayer p ? p : null;
      }
   }

   private static void handleBedwarsDeath(Duel d, Participant part, ServerPlayer p, DamageSource source) {
      // The blow that got here was absorbed by the mode rather than dealt, so the match
      // ledger has to be told: this fighter has been hit, and was one blow from losing it.
      part.wasHit = true;
      part.nearDeath = true;
      part.deathInventory = new ArrayList<>();

      for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
         part.deathInventory.add(p.getInventory().getItem(i).copy());
      }

      transferResourcesOnDeath(p, playerAttackerOf(source));
      p.setGameMode(GameType.SPECTATOR);
      p.setHealth(p.getMaxHealth());
      p.getFoodData().setFoodLevel(20);
      p.getFoodData().setSaturation(20.0F);
      p.setInvulnerable(true);
      p.getAbilities().invulnerable = true;
      if (p.connection != null) {
         p.connection.send(new ClientboundPlayerAbilitiesPacket(p.getAbilities()));
      }

      double[] sp = d.arena.spawnFor(part.slot);
      ServerLevel sl = p.level();
      teleportTo(sl, p, new double[]{sp[0], sp[1] + 2.0, sp[2]});
      part.respawnAt = ServerClock.clock(sl) + 70L;
      Chat.msg(p, "&eYou died, but your bed still stands - respawning at your &fgenerator &ein 3.5s&e!");
      com.fortuneandfavors.net.FfVfx.particles(sl, ParticleTypes.SMOKE, p.getX(), p.getY() + 1.2, p.getZ(), 16, 0.4, 0.5, 0.4, 0.02);
      sl.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.GLASS_BREAK, SoundSource.PLAYERS, 1.0F, 0.7F);
   }

   private static void handleLastStandDeath(Duel d, Participant part, ServerPlayer p, DamageSource source) {
      part.wasHit = true;
      part.nearDeath = true;
      part.lives--;
      if (part.lives > 0) {
         String penalty;
         if (!part.lsHealthPenalty) {
            part.lsHealthPenalty = true;
            penalty = "your §cmax health §7was reduced";
         } else if (!part.lsArmorPenalty) {
            part.lsArmorPenalty = true;
            penalty = "your §7armor was §cweakened";
         } else {
            part.lsHealingPenalty = true;
            penalty = "§cno more healing";
         }

         announce(d, "&c" + part.displayName + "&7 lost a life (&e" + part.lives + "&7 left) - " + penalty + "!");
         if (!part.bot) {
            Chat.msg(p, "&cYou lost a life! &7" + penalty + " &e(" + part.lives + " lives left).");
         }

         applyLastStandPenalties(d, part, p);
         respawnLastStand(d, part, p);
      } else {
         Participant other = otherParticipant(d, p);
         endDuel(
            d,
            other,
            "&c"
               + p.getName().getString()
               + " lost their final life -"
               + (other != null && other.displayName != null ? " &a" + other.displayName + "&c wins!" : " the duel is over.")
         );
      }
   }

   private static void applyLastStandPenalties(Duel d, Participant part, ServerPlayer p) {
      if (part.lsHealthPenalty) {
         try {
            AttributeInstance hp = p.getAttribute(Attributes.MAX_HEALTH);
            if (hp != null && hp.getBaseValue() > 14.0) {
               hp.setBaseValue(14.0);
               if (p.getHealth() > 14.0F) {
                  p.setHealth(14.0F);
               }
            }
         } catch (Exception var7) {
         }
      }

      if (part.lsArmorPenalty) {
         Item[][] downgrade = new Item[][]{
            {Items.IRON_HELMET, Items.CHAINMAIL_HELMET, Items.LEATHER_HELMET},
            {Items.IRON_CHESTPLATE, Items.CHAINMAIL_CHESTPLATE, Items.LEATHER_CHESTPLATE},
            {Items.IRON_LEGGINGS, Items.CHAINMAIL_LEGGINGS, Items.LEATHER_LEGGINGS},
            {Items.IRON_BOOTS, Items.CHAINMAIL_BOOTS, Items.LEATHER_BOOTS}
         };
         EquipmentSlot[] slots = new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

         for (int i = 0; i < slots.length; i++) {
            ItemStack cur = p.getItemBySlot(slots[i]);
            if (!cur.isEmpty()
               && !cur.is(Items.LEATHER_HELMET)
               && !cur.is(Items.LEATHER_CHESTPLATE)
               && !cur.is(Items.LEATHER_LEGGINGS)
               && !cur.is(Items.LEATHER_BOOTS)) {
               p.setItemSlot(slots[i], new ItemStack(downgrade[i][2]));
            }
         }
      }

      if (part.lsHealingPenalty) {
         for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (!s.isEmpty()) {
               if (s.is(Items.GOLDEN_APPLE) || s.is(Items.ENCHANTED_GOLDEN_APPLE)) {
                  p.getInventory().setItem(i, ItemStack.EMPTY);
               } else if (s.is(Items.POTION) || s.is(Items.SPLASH_POTION) || s.is(Items.LINGERING_POTION)) {
                  PotionContents pc = (PotionContents)s.get(DataComponents.POTION_CONTENTS);
                  if (pc != null && pc.is(Potions.HEALING)) {
                     p.getInventory().setItem(i, ItemStack.EMPTY);
                  }
               }
            }
         }
      }
   }

   private static void respawnLastStand(Duel d, Participant part, ServerPlayer p) {
      double[] sp = d.arena.spawnFor(part.slot);
      ServerLevel sl = p.level();
      teleportTo(sl, p, new double[]{sp[0], sp[1] + 0.2, sp[2]});
      p.setYRot(d.arena.spawnYaw(part.slot));
      p.setXRot(0.0F);
      p.setDeltaMovement(Vec3.ZERO);
      p.setHealth(p.getMaxHealth());
      p.getFoodData().setFoodLevel(20);
      p.getFoodData().setSaturation(10.0F);
      p.setInvulnerable(true);
      p.getAbilities().invulnerable = false;
      if (p.connection != null) {
         p.connection.send(new ClientboundPlayerAbilitiesPacket(p.getAbilities()));
      }

      part.lsRespawning = true;
      part.lsRespawnUntil = ServerClock.clock(sl) + 60L;
      if (part.lives == 1 && !part.lsStickGiven) {
         part.lsStickGiven = true;
         p.getInventory().clearContent();
         ItemStack sharpTwig = new ItemStack(Items.STICK);
         sharpTwig.set(DataComponents.CUSTOM_NAME, Component.literal("§a§lSharp Twig"));
         enchantStack(p, sharpTwig, Enchantments.SHARPNESS, 1);
         enchantStack(p, sharpTwig, Enchantments.KNOCKBACK, 1);
         give(p, 0, sharpTwig);
         p.getInventory().setSelectedSlot(0);
         Chat.raw(p, "§c§lFINAL LIFE§r§7 - no kit, just a §aSharp Twig§7. Make it count!");
      }

      com.fortuneandfavors.net.FfVfx.particles(sl, ParticleTypes.TOTEM_OF_UNDYING, p.getX(), p.getY() + 1.2, p.getZ(), 24, 0.5, 0.6, 0.5, 0.1);
      sl.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.BEACON_ACTIVATE, SoundSource.PLAYERS, 1.0F, 1.2F);
   }

   private static void handleFfaElimination(Duel d, Participant part, ServerPlayer p, DamageSource source) {
      if (!part.eliminated) {
         part.eliminated = true;
         part.wasHit = true;
         part.nearDeath = true;
         ServerLevel sl = p.level();
         p.setGameMode(GameType.SPECTATOR);
         p.setHealth(p.getMaxHealth());
         p.getFoodData().setFoodLevel(20);
         p.getFoodData().setSaturation(20.0F);
         p.setInvulnerable(true);
         p.getAbilities().invulnerable = true;
         if (p.connection != null) {
            p.connection.send(new ClientboundPlayerAbilitiesPacket(p.getAbilities()));
         }

         double[] sp = d.arena.spawnFor(part.slot);
         teleportTo(sl, p, new double[]{sp[0], sp[1] + 2.0, sp[2]});
         com.fortuneandfavors.net.FfVfx.particles(sl, ParticleTypes.EXPLOSION_EMITTER, p.getX(), p.getY() + 1.0, p.getZ(), 3, 0.3, 0.3, 0.3, 0.1);
         com.fortuneandfavors.net.FfVfx.particles(sl, ParticleTypes.LAVA, p.getX(), p.getY() + 0.5, p.getZ(), 20, 0.5, 0.5, 0.5, 0.05);
         sl.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.WITHER_DEATH, SoundSource.PLAYERS, 1.2F, 1.6F);
         int alive = 0;
         Participant last = null;

         for (Participant other : d.parts) {
            if (other != part && !other.eliminated) {
               boolean living = other.bot ? other.botEntity != null && other.botEntity.isAlive() : other.player != null && other.player.isAlive();
               if (living) {
                  alive++;
                  last = other;
               }
            }
         }

         String killLine = "&c" + part.displayName + " &7was eliminated";
         ServerPlayer killer = playerAttackerOf(source);
         if (killer != null && !killer.getUUID().equals(p.getUUID())) {
            Participant killerPart = participantOf(d, killer);
            if (killerPart != null) {
               killerPart.kills++;
               killerPart.killStreak++;
               announceFfaKillStreak(d, killerPart, killerPart.killStreak);
               if ("Healer".equals(killerPart.kitName) && killer != null) {
                  killer.setHealth(killer.getMaxHealth());
                  killer.getFoodData().setFoodLevel(20);
                  killer.getFoodData().setSaturation(10.0F);
                  if (killer.level() instanceof ServerLevel healRealm) {
                     com.fortuneandfavors.net.FfVfx.particles(healRealm, ParticleTypes.TOTEM_OF_UNDYING, killer.getX(), killer.getY() + 1.0, killer.getZ(), 25, 0.4, 0.6, 0.4, 0.5);
                     com.fortuneandfavors.net.FfVfx.particles(healRealm, ParticleTypes.HAPPY_VILLAGER, killer.getX(), killer.getY() + 1.5, killer.getZ(), 15, 0.3, 0.4, 0.3, 0.3);
                  }

                  killer.playSound(SoundEvents.PLAYER_LEVELUP, 0.5F, 1.5F);
                  Chat.msg(killer, "&6&lHealer &7- Kill heal &aFULL RESTORE!");
               }
            }

            ItemStack weapon = killer.getMainHandItem();
            String weaponName = weapon.isEmpty() ? "§7fists" : "§f" + weapon.getHoverName().getString();
            killLine = killLine + " by &c" + killer.getName().getString() + "&7 (" + weaponName + "&7)";
         } else {
            killLine = killLine + " &7(" + (source.getMsgId().contains("outOfWorld") ? "§bthe void" : "§7the arena") + "&7)";
         }

         if (part.killStreak >= 3) {
            announce(d, "&c" + part.displayName + "&7's &e" + part.killStreak + "&7 kill streak is over!");
         }

         part.killStreak = 0;
         if (alive == 1 && last != null) {
            endDuel(d, last, killLine + " - &a" + last.displayName + " &7is the last one standing and wins the FFA!");
         } else if (alive == 0) {
            endDuel(d, null, killLine + " - &6everyone fell into the void! &7Draw.");
         } else {
            announce(d, killLine + " - &e" + alive + " &7fighter" + (alive == 1 ? "" : "s") + " left!");
         }

         addSpectatorNametag(d, part, p);
         sendFfaSpectatorHud(d);
         refreshFfaScoreboards(d);
      }
   }

   private static void announceFfaKillStreak(Duel d, Participant part, int streak) {
      String tier = switch (streak) {
         case 2 -> "§eDouble Kill!";
         case 3 -> "§6Killing Spree!";
         case 4 -> "§c§lRampage!";
         case 5 -> "§4§lUNSTOPPABLE!";
         default -> null;
         case 7 -> "§d§lGODLIKE!";
         case 10 -> "§5§l⚡ LEGENDARY! ⚡";
      };
      if (tier != null) {
         long bonus = switch (streak) {
            case 2 -> 25L;
            case 3 -> 50L;
            case 4 -> 100L;
            case 5 -> 200L;
            default -> 0L;
            case 7 -> 400L;
            case 10 -> 1000L;
         };
         if (bonus > 0L && part.uuid != null) {
            EconomyManager.addCash(part.uuid, bonus);
         }

         announce(d, "&f" + part.displayName + " &7" + tier + (bonus > 0L ? " &a(+" + Chat.moneyStr(bonus) + " &astreak bonus)" : ""));
         ServerPlayer pp = part.player;
         if (pp != null && !part.bot) {
            Chat.msg(pp, "&7" + tier + " &7streak: &e" + streak + "&7!" + (bonus > 0L ? " &a+" + Chat.moneyStr(bonus) + " &7added to your balance." : ""));
         }
      }
   }

   private static void addSpectatorNametag(Duel d, Participant part, ServerPlayer p) {
      try {
         if (serverRef == null) {
            return;
         }

         Scoreboard sb = serverRef.getScoreboard();
         String teamName = "ffspec" + d.arena.ox + "x" + d.arena.oz;
         PlayerTeam team = sb.getPlayerTeam(teamName);
         if (team == null) {
            team = sb.addPlayerTeam(teamName);
            team.setColor(Optional.of(TeamColor.AQUA));
            team.setNameTagVisibility(Visibility.ALWAYS);
         }

         if (p != null && !team.getPlayers().contains(p.getScoreboardName())) {
            sb.addPlayerToTeam(p.getScoreboardName(), team);
         }
      } catch (Exception var6) {
      }
   }

   private static void removeSpectatorNametags(Duel d) {
      try {
         if (serverRef == null) {
            return;
         }

         Scoreboard sb = serverRef.getScoreboard();
         String teamName = "ffspec" + d.arena.ox + "x" + d.arena.oz;
         PlayerTeam team = sb.getPlayerTeam(teamName);
         if (team != null) {
            for (String member : new ArrayList<>(team.getPlayers())) {
               sb.removePlayerFromTeam(member);
            }

            sb.removePlayerTeam(team);
         }
      } catch (Exception var6) {
      }
   }

   private static void addWatcherNametag(ServerPlayer spectator, Duel d) {
      try {
         if (serverRef == null) {
            return;
         }

         Scoreboard sb = serverRef.getScoreboard();
         String teamName = "ffwatch" + d.arena.ox + "x" + d.arena.oz;
         PlayerTeam team = sb.getPlayerTeam(teamName);
         if (team == null) {
            team = sb.addPlayerTeam(teamName);
            team.setColor(Optional.of(TeamColor.LIGHT_PURPLE));
            team.setNameTagVisibility(Visibility.ALWAYS);
         }

         if (!team.getPlayers().contains(spectator.getScoreboardName())) {
            sb.addPlayerToTeam(spectator.getScoreboardName(), team);
         }
      } catch (Exception var5) {
      }
   }

   private static void removeWatcherNametag(ServerPlayer spectator) {
      try {
         if (serverRef == null) {
            return;
         }

         Scoreboard sb = serverRef.getScoreboard();

         for (PlayerTeam team : new ArrayList<>(sb.getPlayerTeams())) {
            if (team.getName().startsWith("ffwatch") && team.getPlayers().contains(spectator.getScoreboardName())) {
               sb.removePlayerFromTeam(spectator.getScoreboardName());
               break;
            }
         }
      } catch (Exception var4) {
      }
   }

   private static void removeWatcherNametags(Duel d) {
      try {
         if (serverRef == null) {
            return;
         }

         Scoreboard sb = serverRef.getScoreboard();
         String teamName = "ffwatch" + d.arena.ox + "x" + d.arena.oz;
         PlayerTeam team = sb.getPlayerTeam(teamName);
         if (team != null) {
            for (String member : new ArrayList<>(team.getPlayers())) {
               sb.removePlayerFromTeam(member);
            }

            sb.removePlayerTeam(team);
         }
      } catch (Exception var6) {
      }
   }

   private static void sendFfaSpectatorHud(Duel d) {
      if (d.ffa) {
         List<String> alive = new ArrayList<>();

         for (Participant part : d.parts) {
            if (!part.eliminated) {
               boolean living = part.bot ? part.botEntity != null && part.botEntity.isAlive() : part.player != null && part.player.isAlive();
               if (living) {
                  alive.add(part.displayName);
               }
            }
         }

         String names = alive.isEmpty() ? "§7nobody" : String.join("§7, §a", alive);
         String msg = "§c☠ Eliminated §8| §e" + alive.size() + " §7fighter" + (alive.size() == 1 ? "" : "s") + " left: §a" + names;

         for (Participant part : d.parts) {
            if (!part.bot && part.player != null && part.player.connection != null && part.eliminated) {
               try {
                  part.player.connection.send(new ClientboundSetActionBarTextPacket(Component.literal(msg)));
               } catch (Exception var7) {
               }
            }
         }
      }
   }

   private static void reconcilePlayers(Duel d) {
      if (serverRef != null) {
         for (Participant part : d.parts) {
            if (!part.bot) {
               ServerPlayer online = serverRef.getPlayerList().getPlayer(part.uuid);
               if (online != null && online != part.player) {
                  part.player = online;
                  if (d.mode == DuelMode.BEDWARS && part.deathInventory != null && !part.deathInventory.isEmpty()) {
                     online.getInventory().clearContent();

                     for (int i = 0; i < part.deathInventory.size() && i < online.getInventory().getContainerSize(); i++) {
                        online.getInventory().setItem(i, (ItemStack)part.deathInventory.get(i));
                     }

                     part.deathInventory = null;
                     applyCombat(online, d.combat);
                     applyTeamEnchants(online, part);
                     applyTeamEffects(online, part);
                     online.setHealth(online.getMaxHealth());
                     online.getFoodData().setFoodLevel(20);
                     online.getFoodData().setSaturation(20.0F);
                     online.setInvulnerable(true);
                     part.spawnProtectUntil = (serverRef.overworld() != null ? ServerClock.clock(serverRef) : 0L) + 100L;
                  }
               }
            }
         }
      }
   }

   private static void tickBedwarsRespawns(Duel d, ServerLevel realm, long now) {
      if (d.mode == DuelMode.BEDWARS) {
         for (Participant part : d.parts) {
            if (!part.bot && part.player != null) {
               if (part.spawnProtectUntil != 0L && now >= part.spawnProtectUntil) {
                  part.spawnProtectUntil = 0L;
                  part.player.setInvulnerable(false);
               }

               if (part.respawnAt != 0L && part.player.getY() < d.arena.voidY) {
                  double[] sp = d.arena.spawnFor(part.slot);

                  try {
                     part.player.teleportTo(sp[0], sp[1] + 2.0, sp[2]);
                     part.player.setDeltaMovement(Vec3.ZERO);
                  } catch (Exception var8) {
                  }
               }

               if (part.respawnAt != 0L && now >= part.respawnAt) {
                  part.respawnAt = 0L;
                  respawnAtBed(d, part, realm);
               }
            }
         }
      }
   }

   private static void respawnAtBed(Duel d, Participant part, ServerLevel realm) {
      ServerPlayer p = part.player;
      if (p != null) {
         try {
            p.setGameMode(GameType.SURVIVAL);
            p.setInvulnerable(true);
            p.getAbilities().invulnerable = false;
            if (p.connection != null) {
               p.connection.send(new ClientboundPlayerAbilitiesPacket(p.getAbilities()));
            }

            part.spawnProtectUntil = ServerClock.clock(realm) + 60L;
            double[] sp = d.arena.spawnFor(part.slot);
            teleportTo(realm, p, new double[]{sp[0], sp[1] + 0.2, sp[2]});
            p.setYRot(d.arena.spawnYaw(part.slot));
            p.setXRot(0.0F);
            p.setDeltaMovement(Vec3.ZERO);
            if (part.deathInventory != null && !part.deathInventory.isEmpty()) {
               p.getInventory().clearContent();

               for (int i = 0; i < part.deathInventory.size() && i < p.getInventory().getContainerSize(); i++) {
                  p.getInventory().setItem(i, (ItemStack)part.deathInventory.get(i));
               }

               part.deathInventory = null;
               applyTeamEnchants(p, part);
               applyTeamEffects(p, part);
            }

            applyCombat(p, d.combat);
            p.setHealth(p.getMaxHealth());
            p.getFoodData().setFoodLevel(20);
            p.getFoodData().setSaturation(20.0F);
            // Keyed by uuid, so only for a fighter that has one; the flag belongs to
            // the player, and a bot's raised block is tracked through its entity.
            if (part.hasPlayer()) {
               clearSwordBlockByUuid(part.uuid);
            }

            Chat.raw(p, "&eYou respawned at your generator!");
            com.fortuneandfavors.net.FfVfx.particles(realm, ParticleTypes.ENCHANT, p.getX(), p.getY() + 1.5, p.getZ(), 24, 0.5, 0.6, 0.5, 0.08);
            realm.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.BEACON_ACTIVATE, SoundSource.PLAYERS, 1.0F, 1.2F);
         } catch (Throwable t) {
            FortuneFavorsMod.LOGGER.error("Fortune & Favors: bedwars respawn failed", t);
         }
      }
   }

   private static void tickBedwarsHungerDurability(Duel d, long now) {
      if (d.mode == DuelMode.BEDWARS) {
         for (Participant part : d.parts) {
            if (!part.bot && part.player != null && part.player.isAlive()) {
               part.player.getFoodData().setFoodLevel(20);
               part.player.getFoodData().setSaturation(20.0F);
               part.player.removeEffect(MobEffects.HUNGER);

               for (int i = 0; i < part.player.getInventory().getContainerSize(); i++) {
                  ItemStack s = part.player.getInventory().getItem(i);
                  if (!s.isEmpty() && s.isDamageableItem()) {
                     s.setDamageValue(0);
                  }
               }

               for (EquipmentSlot slot : EquipmentSlot.values()) {
                  ItemStack s = part.player.getItemBySlot(slot);
                  if (!s.isEmpty() && s.isDamageableItem()) {
                     s.setDamageValue(0);
                  }
               }
            }
         }
      }
   }

   private static void startCountdown(Duel d) {
      if (d.phase != Phase.ENDED) {
         d.phase = Phase.COUNTDOWN;
         ServerLevel realm = getRealm(serverRef);
         d.phaseEndsAt = (realm != null ? ServerClock.clock(realm) : 0L) + 100L;
         announce(d, "&7The duel starts in &e5 &7seconds - get ready!");
      }
   }

   private static void startFight(Duel d) {
      if (d.phase != Phase.ENDED) {
         d.phase = Phase.FIGHT;
         ServerLevel frl = getRealm(serverRef);
         d.fightStartedAt = frl != null ? ServerClock.clock(frl) : 0L;
         // The gearing window is anchored here, at the moment the fight begins, rather
         // than being set on the first tick of the mode's script. "Prep time is 0" is
         // the symptom of that laziness: any path that never reaches the script line -
         // a mode branch that does not match, a fight whose realm is gone for a tick,
         // a clock read from a different level - left the deadline unset or set against
         // the wrong clock, and the truce was over before it began. The window is now
         // fixed at the one moment that is guaranteed to happen, and the script only
         // reads it.
         d.gladHighlightAt = d.mode == DuelMode.GLADIATOR && frl != null
            ? d.fightStartedAt + GLADIATOR_QUIET_TICKS
            : -1L;
         lockBets(d);
         if (d.mode == DuelMode.BEDWARS) {
            for (Participant part : d.parts) {
               part.bwUpgrades.putIfAbsent("forge", 1);
               part.teamChest = part.slot == 0 ? d.arena.chest0 : d.arena.chest1;
            }
         }

         for (Participant part : d.parts) {
            ServerPlayer p = part.player;
            if (p != null) {
               p.setInvulnerable(false);
               p.getAbilities().invulnerable = false;
               if (p.connection != null) {
                  p.connection.send(new ClientboundPlayerAbilitiesPacket(p.getAbilities()));
               }

               if (d.mode == DuelMode.SKYWARS && !part.bot) {
                  ServerLevel rl0 = frl != null ? frl : realmOf(p);
                  p.setYRot(d.arena.spawnYaw(part.slot));
                  p.setXRot(0.0F);
                  teleportTo(rl0, p, d.arena.spawnFor(part.slot));
               }

               giveFightGear(part, d);
               if (part.bot && !hasGapple(p)) {
                  give(p, 5, new ItemStack(Items.GOLDEN_APPLE, 3));
               }

               p.setHealth(p.getMaxHealth());
               p.getFoodData().setFoodLevel(20);
               p.getFoodData().setSaturation(10.0F);
               p.removeAllEffects();
               if (d.mode == DuelMode.KITS) {
                  applyFunKitEffects(p, part.kitName);
               }

               if (d.mode == DuelMode.BEDWARS) {
                  p.setRespawnPosition(new RespawnConfig(RespawnData.of(DUEL_REALM, d.arena.padFor(part.slot), d.arena.spawnYaw(part.slot), 0.0F), true), false);
               }

               Chat.raw(p, "§a⚡ FIGHT!");
               ServerLevel rl = realmOf(p);
               com.fortuneandfavors.net.FfVfx.particles(rl, ParticleTypes.CRIT, p.getX(), p.getY() + 1.0, p.getZ(), 30, 0.5, 0.6, 0.5, 0.1);
               com.fortuneandfavors.net.FfVfx.particles(rl, ParticleTypes.ENCHANT, p.getX(), p.getY() + 1.6, p.getZ(), 14, 0.4, 0.5, 0.4, 0.08);
               rl.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.LIGHTNING_BOLT_IMPACT, SoundSource.PLAYERS, 0.7F, 1.6F);
            }
         }

         if (d.mode == DuelMode.SKYWARS && frl != null) {
            for (int[] c : d.arena.chambers) {
               for (int x = c[0]; x <= c[1]; x++) {
                  for (int z = c[2]; z <= c[3]; z++) {
                     for (int y = 103; y <= 106; y++) {
                        frl.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 3);
                     }
                  }
               }
            }

            d.arena.chambers.clear();
         }

         announce(d, "&a⚡ FIGHT!");
         if (d.mode == DuelMode.BEDWARS) {
            announce(d, "§7Protect your §cbed§7 - break the enemy's and they can't respawn!");
         } else if (d.mode == DuelMode.SKYWARS) {
            announce(d, "§7Raid the §emiddle§7 for gear - chests refill every minute!");
         } else if (d.mode == DuelMode.LUCKYPvP) {
            announce(d, "§d✨ Lucky PvP! §7Three random items decide this fight! " + (d.combat == CombatStyle.LEGACY ? "§c(1.8 Legacy)" : "§b(Modern)") + "§7");
         } else if (d.mode == DuelMode.KITS) {
            announce(d, "§d§lKits!§r§7 - fight with the kit you picked!");
         } else if (d.mode == DuelMode.COMBODUEL) {
            announce(d, "§c§lCombo Duel§r§7 - no attack delay, Speed II, sprint-reset to lock combos!");
         } else if (d.mode == DuelMode.UHCDUEL) {
            announce(d, "§6§lUHC Duel§r§7 - no natural regen, limited healing. Use it wisely!");
         } else if (d.mode == DuelMode.RANDOMIZER) {
            announce(d, "§d§lRandomizer Duel§r§7 - your weapon re-rolls every 10 seconds!");
         } else if (d.mode == DuelMode.DRAFT) {
            announce(d, "§b§lDraft Duel§r§7 - fight with the kit you drafted!");
         } else if (d.mode == DuelMode.LASTSTAND) {
            announce(d, "§c§lLast Stand§r§7 - 4 lives, but every death permanently strips something!");
         }

         if (isTrainingDuel(d)) {
            announce(d, "§6§lTRAINING§r§7 - the dummy never fights back and never dies. §fRight-click the §eNether Star§f to control it.");
         }

         showDuelScoreboard(d);
      }
   }

   /**
    * The two health readings a won duel is judged on.
    *
    * <p>Comeback is about the <b>low point</b> of the fight - one hit from death at some
    * point, and still the winner - so it has to be remembered while the fight is running
    * rather than read off the result screen. Clutch is about the health the winner is left
    * standing on when it ends. They are deliberately different numbers: coming back from
    * the brink and winning by the skin of your teeth are not the same story.
    */
   private static final float COMEBACK_HEALTH = 2.0F;
   private static final float CLUTCH_HEALTH = 4.0F;

   /**
    * The three duel feats that are about how the fight went rather than that it was won.
    *
    * <p>First Blood is deliberately not counted anywhere: achievement grants are already
    * idempotent, so handing it to every winner gives it to the first one, which is the
    * whole condition, without a second per-player counter to keep honest. The other two
    * read the match ledger - the low point remembered by the damage hook, and the health
    * the winner happens to be standing on right now.
    */
   private static void grantDuelFeats(Participant winner) {
      if (winner == null || winner.bot || winner.player == null) {
         return;
      }
      Advancements.grant(winner.player, "first_blood");
      if (!winner.wasHit) {
         Advancements.grant(winner.player, "untouchable");
      }
      if (winner.nearDeath) {
         Advancements.grant(winner.player, "comeback");
      }
      float health = winner.player.getHealth();
      if (health > 0.0F && health <= CLUTCH_HEALTH) {
         Advancements.grant(winner.player, "clutch");
      }
   }

   /**
    * Whether a duel win is a war kill: the same rule the world uses - two guilds at
    * war with each other pay double - asked of the losers of this match rather than
    * of a single opponent, so an FFA win counts too.
    */
   private static boolean duellingAtWar(Duel d, Participant winner) {
      if (winner.uuid == null) {
         return false;
      }

      for (Participant other : d.parts) {
         if (other != winner && other.hasPlayer() && GuildManager.atWar(winner.uuid, other.uuid)) {
            return true;
         }
      }

      return false;
   }

   private static void endDuel(Duel d, Participant winner, String message) {
      if (d.phase != Phase.ENDED) {
         d.phase = Phase.ENDED;
         hideDuelScoreboard(d);
         settleBets(d, winner);

         for (Participant part : d.parts) {
            // A bot has no uuid, so there is no sword-block flag of its own to drop;
            // filing one under a null key is how an entry outlives the match that
            // made it. The sent-flag is keyed by the entity, not the participant, so
            // it is asked for on the player.
            if (part.hasPlayer()) {
               swordBlockHolding.remove(part.uuid);
            }

            if (!part.bot && part.player != null && swordBlockSent.remove(part.player.getUUID()) != null) {
               try {
                  FfNet.send(part.player, new FfScreenFxPayload(4, false));
               } catch (Exception var7) {
               }
            }
         }

         if (winner != null) {
            for (Participant part : d.parts) {
               if (part.hasPlayer()) {
                  recordResult(part.uuid, part.displayName, part == winner, d.mode);
                  if (part == winner && part.player != null) {
                     Advancements.grant(part.player, "duel_champion");
                     grantDuelFeats(part);
                     // Guild PvP score is paid here, not from a death: the losing blow of
                     // a duel is refused by the duel itself, so the loser never dies and
                     // no death event ever reports the win. This is the one place a duel
                     // is decided, and it runs once per match.
                     GuildManager.creditDuelWin(part.player, duellingAtWar(d, part));
                     com.fortuneandfavors.economy.ServerNewspaperManager.logEvent(serverRef, part.displayName + " won a duel (" + d.mode.display + ")");
                  }
               }
            }

            if (d.botDifficulty == BotDifficulty.HARD && winner.hasPlayer() && winner.player != null) {
               trackBotWinStreak(winner.player, d);
            }

            if (d.botDifficulty == BotDifficulty.HARD) {
               for (Participant part : d.parts) {
                  if (part.hasPlayer() && part != winner && part.player != null) {
                     resetBotWinStreak(part.player, d);
                  }
               }
            }
         }

         for (Participant part : d.parts) {
            if (part.hasPlayer() && part.player != null) {
               Participant other = otherParticipant(d, part.player);
               // A last duel is remembered so the loser can be offered a rematch by
               // name, which needs a second real player: a bot is not somebody to
               // rematch, and has no uuid to remember either.
               if (other != null && other.hasPlayer()) {
                  lastDuels.put(part.uuid, new LastDuel(other.uuid, d.mode));
               }
            }
         }

         for (Participant part : d.parts) {
            if (part.hasPlayer() && part.player != null && part.saved != null) {
               try {
                  if (part.saved.respawnData != null) {
                     part.player.setRespawnPosition(new RespawnConfig(part.saved.respawnData, part.saved.respawnForced), false);
                  } else {
                     part.player.setRespawnPosition(null, false);
                  }
               } catch (Exception var6) {
               }
            }
         }

         if (message != null) {
            announce(d, message);
         }

         // The card is a two-player report; in an FFA the live scoreboard is the
         // scoreboard, so it is skipped rather than printing every pairing.
         if (!d.ffa) {
            sendMatchReport(d, winner);
         }
         playWinAnimation(d, winner);
         if (d.bot == null && !d.ffa && d.parts.size() == 2) {
            // The GG window opens here, with the result, and this is the only line that
            // opens it. It used to be opened by `ensureRematchMenus`, which runs three
            // seconds later - so the moment manners are actually worth something (the
            // result line arriving) was three seconds of nothing, and the window only
            // started once the screen had been re-offered. Worse, the window's own
            // wording said "while the rematch screen is up", and a player cannot type
            // chat while a chest menu holds the keyboard: the honest sequence is press
            // Escape, then type, which lands inside the first seconds or not at all.
            if (d.rematchOpenedAt == 0L) {
               ServerLevel ggRealm = getRealm(serverRef);
               if (ggRealm != null) {
                  d.rematchOpenedAt = ServerClock.clock(ggRealm);
               }
            }
            for (Participant part : d.parts) {
               if (part.hasPlayer() && part.player != null && part.player.connection != null) {
                  Participant other = otherParticipant(d, part.player);
                  if (other != null && other.hasPlayer()) {
                     RematchMenu.open(part.player, other.uuid);
                  }
               }
            }
         }

         ServerLevel realm = getRealm(serverRef);
         if (realm == null) {
            finalizeDuel(d);
         } else {
            if (d.ffa) {
               d.finalizeAt = ServerClock.clock(realm) + 40L + 40L;
            } else {
               d.finalizeAt = ServerClock.clock(realm) + 40L + 200L;
            }
         }
      }
   }

   private static void ensureRematchMenus(Duel d, long now) {
      if (!d.rematchReopened && d.bot == null && !d.ffa && d.parts.size() == 2) {
         long endedAt = d.finalizeAt - 40L - 200L;
         if (now - endedAt >= 60L) {
            d.rematchReopened = true;
            // Deliberately does NOT touch rematchOpenedAt: that is the GG window, it was
            // opened when the duel ended, and re-offering the screen must not restart it
            // or a player could earn the gesture minutes later by pressing Escape again.

            for (Participant part : d.parts) {
               if (part.hasPlayer() && part.player != null && part.player.connection != null && !RematchMenu.hasChosen(part.uuid) && !RematchMenu.isOpenFor(part.uuid)
                  )
                {
                  Participant other = otherParticipant(d, part.player);
                  if (other != null && other.hasPlayer()) {
                     RematchMenu.open(part.player, other.uuid);
                  }
               }
            }
         }
      }
   }

   /** How long after the rematch offer a GG still counts. Five seconds. */
   public static final long GG_WINDOW_TICKS = 100L;

   /**
    * Whether the GG window is open, as a function of two tick readings.
    *
    * <p>Pure, so the self-test can pin every edge of it without staging two players, a
    * duel, a result screen and a chat message: closed before the offer appears, open on
    * the tick it appears, open for exactly five seconds, and shut after that. A window
    * that never closed would turn the gesture into "type gg at some point", which is not
    * the same thing at all.
    */
   public static boolean ggWindowOpen(long rematchOpenedAt, long now) {
      return rematchOpenedAt > 0L && now >= rematchOpenedAt && now - rematchOpenedAt <= GG_WINDOW_TICKS;
   }

   /**
    * Whether a chat message is a GG, generously.
    *
    * <p>"gg", "GG", "gg!", "gg wp", "ggs" - the point of the gesture is manners, and a
    * rule that only accepts the exact string would reward pedantry instead. It has to stop
    * somewhere, though: the word has to be the whole message or its first word, so "lol no
    * gg" is not one, while "gg that was close" still is.
    */
   public static boolean isGg(String message) {
      if (message == null) {
         return false;
      }

      String trimmed = message.trim().toLowerCase(java.util.Locale.ROOT);
      if (trimmed.isEmpty()) {
         return false;
      }

      String[] words = trimmed.split("\\s+");
      String first = words[0].replaceAll("[^a-z]", "");
      return first.equals("gg") || first.equals("ggs");
   }

   /**
    * Called from chat: a GG inside the five seconds after the rematch screen is Kind Sir.
    *
    * <p>Manners are the one thing a ranked fight cannot make you do, and the rematch
    * screen is the exact moment they are worth something: the person who just lost has
    * five seconds to say it before the result fades. It grants an achievement rather than
    * a reward on purpose - it is a mark on a profile, not a currency somebody farms by
    * typing two letters after every match.
    */
   public static void noteRematchChat(ServerPlayer player, String message) {
      if (player == null || !isGg(message)) {
         return;
      }

      Duel d = duels.get(player.getUUID());
      if (d == null || d.phase != Phase.ENDED || !d.parts.stream().anyMatch(part -> player.getUUID().equals(part.uuid))) {
         return;
      }

      // The window is measured from the instant the result landed, and is not gated on
      // the rematch screen being up: saying gg means closing that screen first, so
      // requiring it to still be open asked for two things at once.
      ServerLevel realm = getRealm(serverRef);
      long now = realm != null ? ServerClock.clock(realm) : ServerClock.clock(player.level());
      if (!ggWindowOpen(d.rematchOpenedAt, now)) {
         return;
      }

      if (!d.kindSirSaid.add(player.getUUID())) {
         return;
      }

      Advancements.grant(player, "kind_sir");
      announce(
         d,
         "&d&lKIND SIR &7- " + player.getName().getString() + " said &fgg&7 within the five seconds it counted."
      );
   }

   private static void playWinAnimation(Duel d, Participant winner) {
      if (winner != null) {
         ServerLevel realm = getRealm(serverRef);
         if (realm != null) {
            double x;
            double y;
            double z;
            if (winner.bot && winner.botEntity != null) {
               x = winner.botEntity.getX();
               y = winner.botEntity.getY();
               z = winner.botEntity.getZ();
            } else if (!winner.bot && winner.player != null) {
               x = winner.player.getX();
               y = winner.player.getY();
               z = winner.player.getZ();
            } else {
               double[] sp = d.arena.spawnFor(winner.slot);
               x = sp[0];
               y = sp[1];
               z = sp[2];
            }

            if (winner.player != null && winner.player.connection != null) {
               winner.player.connection.send(new ClientboundSetTitleTextPacket(Component.literal("§6§lVICTORY")));
               winner.player.connection.send(new ClientboundSetTitlesAnimationPacket(5, 60, 15));
               winner.player.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal("§eYou have won the duel!")));
            }

            if (winner.player != null && !winner.bot) {                com.fortuneandfavors.economy.StreakTrackerManager.onDuelWin(winner.player);
                com.fortuneandfavors.economy.DailyWeeklyChallengeManager.onDuelWin(winner.player);
                com.fortuneandfavors.economy.DynamicContractsManager.onDuelWon(winner.player);
            }

            com.fortuneandfavors.net.FfVfx.particles(realm, ParticleTypes.FIREWORK, x, y + 1.6, z, 40, 0.7, 1.0, 0.7, 0.15);
            com.fortuneandfavors.net.FfVfx.particles(realm, ParticleTypes.FIREWORK, x, y + 3.0, z, 30, 0.5, 0.8, 0.5, 0.1);
            com.fortuneandfavors.net.FfVfx.particles(realm, ParticleTypes.FIREWORK, x, y + 4.5, z, 20, 0.4, 0.6, 0.4, 0.08);
            com.fortuneandfavors.net.FfVfx.particles(realm, ParticleTypes.SOUL_FIRE_FLAME, x, y + 0.2, z, 40, 0.4, 1.5, 0.4, 0.05);
            com.fortuneandfavors.net.FfVfx.particles(realm, ParticleTypes.SOUL_FIRE_FLAME, x, y + 1.0, z, 30, 0.3, 1.0, 0.3, 0.04);
            com.fortuneandfavors.net.FfVfx.particles(realm, ParticleTypes.TOTEM_OF_UNDYING, x, y + 1.2, z, 60, 0.8, 1.2, 0.8, 0.2);
            com.fortuneandfavors.net.FfVfx.particles(realm, ParticleTypes.TOTEM_OF_UNDYING, x, y + 2.0, z, 40, 0.6, 0.8, 0.6, 0.15);

            for (double angle = 0.0; angle < Math.PI * 2; angle += Math.PI / 6) {
               double ox = Math.cos(angle) * 2.0;
               double oz = Math.sin(angle) * 2.0;
               com.fortuneandfavors.net.FfVfx.particles(realm, ParticleTypes.END_ROD, x + ox, y + 1.0, z + oz, 3, 0.0, 0.3, 0.0, 0.02);
            }

            for (double h = 0.0; h < 3.0; h += 0.5) {
               com.fortuneandfavors.net.FfVfx.particles(realm, ParticleTypes.ENCHANT, x, y + h, z, 12, 0.8, 0.1, 0.8, 0.9);
            }

            com.fortuneandfavors.net.FfVfx.particles(realm, ParticleTypes.GLOW, x, y + 1.5, z, 30, 0.6, 0.8, 0.6, 0.06);
            com.fortuneandfavors.net.FfVfx.particles(realm, ParticleTypes.GLOW_SQUID_INK, x, y + 2.0, z, 20, 0.5, 0.5, 0.5, 0.03);
            realm.playSound(null, x, y, z, SoundEvents.FIREWORK_ROCKET_LAUNCH, SoundSource.PLAYERS, 1.5F, 0.8F);
            realm.playSound(null, x, y, z, SoundEvents.FIREWORK_ROCKET_BLAST, SoundSource.PLAYERS, 1.2F, 1.2F);
            realm.playSound(null, x, y, z, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.PLAYERS, 1.0F, 1.0F);
            realm.playSound(null, x, y, z, SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.8F, 1.8F);
            realm.playSound(null, x, y, z, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 0.6F, 1.4F);
            if (d.mode == DuelMode.TNTRUN) {
               int w = (d.arena.x1 - d.arena.x0) / 2;
               int h = (d.arena.z1 - d.arena.z0) / 2;
               int cx = (d.arena.x0 + d.arena.x1) / 2;
               int cz = (d.arena.z0 + d.arena.z1) / 2;
               double topY = y;

               for (int i = 0; i < 6; i++) {
                  double px = cx + (Math.random() - 0.5) * w * 1.6;
                  double pz = cz + (Math.random() - 0.5) * h * 1.6;
                  com.fortuneandfavors.net.FfVfx.particles(realm, ParticleTypes.FIREWORK, px, topY + 2.0 + Math.random() * 2.0, pz, 14, 0.4, 0.5, 0.4, 0.08);
                  com.fortuneandfavors.net.FfVfx.particles(realm, ParticleTypes.TOTEM_OF_UNDYING, px, topY + 1.5, pz, 20, 0.6, 0.8, 0.6, 0.12);
               }

               realm.playSound(null, cx, topY, cz, SoundEvents.FIREWORK_ROCKET_TWINKLE, SoundSource.PLAYERS, 1.4F, 1.0F);
            }
         }
      }
   }

   private static void finalizeDuel(Duel d) {
      if (!d.finalized) {
         d.finalized = true;
         ServerLevel realm = getRealm(serverRef);

         try {
            if (d.bot == null
               && !d.ffa
               && d.parts.size() == 2
               && isPlayer((Participant)d.parts.get(0))
               && isPlayer((Participant)d.parts.get(1))) {
               UUID uuid0 = ((Participant)d.parts.get(0)).uuid;
               UUID uuid1 = ((Participant)d.parts.get(1)).uuid;
               boolean wantRematch = RematchMenu.bothWantRematch(uuid0, uuid1);
               RematchMenu.clearChoices(uuid0, uuid1);
               if (wantRematch) {
                  ServerPlayer p0 = serverRef != null ? serverRef.getPlayerList().getPlayer(uuid0) : null;
                  ServerPlayer p1 = serverRef != null ? serverRef.getPlayerList().getPlayer(uuid1) : null;
                  if (p0 != null && p1 != null) {
                     p0.closeContainer();
                     p1.closeContainer();

                     for (Participant part : d.parts) {
                        ServerPlayer p = serverRef.getPlayerList().getPlayer(part.uuid);
                        if (p != null) {
                           p.closeContainer();
                           restore(p, part.saved);
                        }
                     }

                     String err = requestDuel(p0, d.mode, p1);
                     if (err == null) {
                        if (realm != null) {
                           teardownArena(realm, d);
                        }

                        releasePlot(d.arena.ox, d.arena.oz);
                        return;
                     }

                     Chat.msg(p0, "&cRematch failed: " + err);
                  }
               }
            }
         } catch (Throwable t) {
            FortuneFavorsMod.LOGGER.error("Fortune & Favors: rematch handoff failed - running full duel cleanup", t);
         }

         for (Participant part : d.parts) {
            // The lookup map is keyed by player uuid: the bot's own entry is filed
            // under its entity's uuid where it is spawned, so there is nothing here
            // to remove for it and nothing to remove it from.
            if (part.hasPlayer()) {
               duels.remove(part.uuid);
            }
         }

         for (Participant part : d.parts) {
            try {
               if (part.bot) {
                  removeBot(d, part, realm);
               } else {
                  ServerPlayer p = serverRef != null ? serverRef.getPlayerList().getPlayer(part.uuid) : null;
                  if (p != null) {
                     applyCombat(p, CombatStyle.MODERN);
                     resetMaxHealth(p);

                     try {
                        p.closeContainer();
                     } catch (Exception var11) {
                     }

                     if (part.saved != null && part.saved.respawnData != null) {
                        p.setRespawnPosition(new RespawnConfig(part.saved.respawnData, part.saved.respawnForced), false);
                     } else {
                        p.setRespawnPosition(null, false);
                     }

                     if (!p.isAlive()) {
                        p = serverRef.getPlayerList().respawn(p, true, RemovalReason.DISCARDED);
                     }

                     if (part.saved != null) {
                        restore(p, part.saved);
                     } else {
                        p.setInvulnerable(false);
                        p.getAbilities().invulnerable = false;
                        p.setGameMode(GameType.SURVIVAL);
                     }
                  } else if (part.saved != null && part.hasPlayer()) {
                     // The exit state waits under the uuid it belongs to. A bot has
                     // none and a player always does; a null key would be an entry
                     // nothing can ever look up and nothing ever clears.
                     pendingRestore.put(part.uuid, part.saved);
                  }
               }
            } catch (Throwable t) {
               FortuneFavorsMod.LOGGER
                  .error("Fortune & Favors: could not restore " + (part.displayName != null ? part.displayName : part.uuid) + " after a duel", t);

               try {
                  ServerPlayer p = serverRef != null ? serverRef.getPlayerList().getPlayer(part.uuid) : null;
                  if (p != null) {
                     applyCombat(p, CombatStyle.MODERN);
                     p.setInvulnerable(false);
                     p.getAbilities().invulnerable = false;
                     p.setGameMode(GameType.SURVIVAL);
                  }
               } catch (Throwable var10) {
               }
            }
         }

         unspectateDuelSpectators(d);
         if (realm != null) {
            teardownArena(realm, d);
         }

         releasePlot(d.arena.ox, d.arena.oz);
      }
   }

   /** True while the player is an active participant in a UHC duel (FIGHT
    *  phase). UHC duels disable natural health regeneration, but game rules
    *  are server-global in MC 26.2 - so instead of flipping the gamerule (which
    *  used to kill regen for the whole server), NaturalRegenMixin checks this
    *  per player. */
   public static boolean isUhcNoRegen(UUID uuid) {
      Duel d = duels.get(uuid);
      return d != null && d.mode == DuelMode.UHCDUEL && d.phase == Phase.FIGHT;
   }

   private static void snapshotInto(ServerPlayer p, Participant part) {
      // This runs before a single piece of kit is handed out, so it is the last
      // moment the inventory is the player's own. Everything below is held in a
      // field on the duel and handed back by the exit path - which means a server
      // crash, or a missed exit, took the real kit with it. Filing the same state
      // with the durable snapshot store first means /ff restore can always hand it
      // back, whatever happens to this match afterwards.
      if (!part.bot) {
         com.fortuneandfavors.util.LastInventoryHolder.snapshotBeforeEvent(p, "before duel");
      }
      List<ItemStack> items = new ArrayList<>();

      for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
         items.add(p.getInventory().getItem(i).copy());
      }

      List<MobEffectInstance> effects = new ArrayList<>();

      for (MobEffectInstance e : p.getActiveEffects()) {
         effects.add(new MobEffectInstance(e));
      }

      RespawnConfig rc = p.getRespawnConfig();
      part.saved = new SavedPlayer(
         p.level().dimension(),
         p.getX(),
         p.getY(),
         p.getZ(),
         p.getYRot(),
         p.getXRot(),
         items,
         p.getHealth(),
         p.getFoodData().getFoodLevel(),
         p.getFoodData().getSaturationLevel(),
         p.experienceLevel,
         p.experienceProgress,
         p.getRemainingFireTicks(),
         effects,
         p.gameMode().getName(),
         rc != null ? rc.respawnData() : null,
         rc != null && rc.forced()
      );
   }

   private static void restore(ServerPlayer p, SavedPlayer s) {
      applyCombat(p, CombatStyle.MODERN);
      p.setInvulnerable(false);
      p.getAbilities().invulnerable = false;

      try {
         p.getInventory().clearContent();

         for (int i = 0; i < s.items.size() && i < p.getInventory().getContainerSize(); i++) {
            p.getInventory().setItem(i, (ItemStack)s.items.get(i));
         }

         p.setHealth(Math.min(s.health, p.getMaxHealth()));
         p.getFoodData().setFoodLevel(s.hunger);
         p.getFoodData().setSaturation(s.saturation);
         p.setExperienceLevels(s.xpLevel);
         p.setExperiencePoints((int)Math.ceil(s.xpProgress * p.getXpNeededForNextLevel()));
         p.setRemainingFireTicks(s.fireTicks);
         p.removeAllEffects();

         for (MobEffectInstance e : s.effects) {
            p.addEffect(new MobEffectInstance(e));
         }

         try {
            p.setGameMode(GameType.byName(s.gameMode, GameType.SURVIVAL));
         } catch (Exception var8) {
         }

         MinecraftServer server = p.level().getServer();
         ServerLevel home = server.getLevel(s.dim);
         // The dimension the player left can be gone by the time the match ends -
         // a realm that was torn down, or a save that no longer has that level. The
         // old code simply skipped the move, which left the player standing inside a
         // duel plot that was about to be scraped to air. Anything is better than
         // that, so the overworld is the fallback.
         if (home == null) {
            home = server.overworld();
         }
         if (home != null) {
            // Force the destination chunk to exist before the move. Teleporting a
            // client into a chunk the server has not loaded is what leaves it parked
            // on "Loading terrain" with nothing to render - the player is there, the
            // ground is not.
            try {
               home.getChunkAt(new BlockPos((int)Math.floor(s.x), (int)Math.floor(s.y), (int)Math.floor(s.z)));
            } catch (Throwable t) {
               FortuneFavorsMod.LOGGER.warn("Fortune & Favors: could not preload the exit chunk", t);
            }
            try {
               p.teleport(new TeleportTransition(home, new Vec3(s.x, s.y, s.z), Vec3.ZERO, s.yaw, s.pitch, TeleportTransition.PLACE_PORTAL_TICKET));
            } catch (Exception e) {
               try {
                  p.teleportTo(p.getX(), p.getY(), p.getZ());
                  p.teleport(new TeleportTransition(home, new Vec3(s.x, s.y, s.z), Vec3.ZERO, s.yaw, s.pitch, TeleportTransition.PLACE_PORTAL_TICKET));
               } catch (Exception e2) {
                  FortuneFavorsMod.LOGGER.error("Fortune & Favors: could not teleport " + p.getName().getString() + " home after a duel", e2);
                  // Last resort: put them at the overworld spawn, in a chunk that is
                  // always loaded, rather than leave them in the arena.
                  try {
                     ServerLevel fallback = server.overworld();
                     BlockPos fs = fallback.getLevelData().getRespawnData().pos();
                     p.teleport(
                        new TeleportTransition(
                           fallback,
                           new Vec3(fs.getX() + 0.5, fs.getY(), fs.getZ() + 0.5),
                           Vec3.ZERO,
                           s.yaw,
                           s.pitch,
                           TeleportTransition.PLACE_PORTAL_TICKET
                        )
                     );
                  } catch (Throwable t2) {
                     FortuneFavorsMod.LOGGER.error("Fortune & Favors: spawn fallback failed for " + p.getName().getString(), t2);
                  }
               }
            }
         }

         // Whatever the outcome of the move, the player must not be left a spectator
         // with no arena to spectate. A duel only ever sets SPECTATOR for its own
         // end-of-match window, so leaving that mode is always correct here.
         if (p.gameMode.getGameModeForPlayer() == GameType.SPECTATOR) {
            p.setGameMode(GameType.SURVIVAL);
            if (p.connection != null) {
               p.connection.send(new ClientboundPlayerAbilitiesPacket(p.getAbilities()));
            }
         }

         if (s.respawnData != null) {
            p.setRespawnPosition(new RespawnConfig(s.respawnData, s.respawnForced), false);
         } else {
            p.setRespawnPosition(null, false);
         }

         p.setInvulnerable(false);
         p.sendSystemMessage(Component.literal("§aYour items and position are restored."), true);
      } catch (Exception e) {
         FortuneFavorsMod.LOGGER.error("Fortune & Favors: could not fully restore duel state for " + p.getName().getString(), e);
      }
   }

   private static void applyCombat(ServerPlayer p, CombatStyle style) {
      try {
         AttributeInstance atk = p.getAttribute(Attributes.ATTACK_SPEED);
         if (atk == null) {
            return;
         }

         if (style == CombatStyle.LEGACY) {
            atk.addTransientModifier(new AttributeModifier(LEGACY_ATTACK_SPEED, 100.0, Operation.ADD_VALUE));
         } else {
            atk.removeModifier(LEGACY_ATTACK_SPEED);
         }
      } catch (Exception var3) {
      }
   }

         private static void giveFightGear(Participant part, Duel d) {
      ServerPlayer p = part.player;
      if (p != null) {
         if (isTrainingDuel(d)) {
            // No kit at all: the training duel is played with the player's own inventory, and the
            // only thing added is the control star - on the player's side only, because a dummy
            // holding a Nether Star is just a dummy holding a Nether Star.
            if (!part.bot) {
               giveTrainingStar(p);
            }
            return;
         }
         if (d.mode != DuelMode.DRAFT) {
            p.getInventory().clearContent();
            p.getInventory().setSelectedSlot(0);
         }

         switch (d.mode.ordinal()) {
            case 0:
               // The 1.9+ arena, and it should play like one: diamond armour, a
               // real shield to hold, and enough gapples to actually trade. It
               // used to hand out bare iron with no offhand at all, which made
               // "Vanilla 1.9+ combat, shield ready" a promise the kit broke.
               ItemStack modernSword = new ItemStack(Items.DIAMOND_SWORD);
               enchantStack(p, modernSword, Enchantments.SHARPNESS, 2);
               give(p, 0, modernSword);
               ItemStack modernBow = new ItemStack(Items.BOW);
               enchantStack(p, modernBow, Enchantments.POWER, 2);
               give(p, 1, modernBow);
               give(p, 2, new ItemStack(Items.ARROW, 32));
               give(p, 3, new ItemStack(Items.GOLDEN_APPLE, part.bot ? 4 : 8));
               give(p, 4, new ItemStack(Items.COOKED_BEEF, 8));
               setArmorEnchanted(
                  p, Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS, Enchantments.PROTECTION, 2
               );
               // The bot carries what you carry: a shield in the offhand that it
               // actually raises now, and an axe for yours. It used to get the axe
               // *instead* of the shield, because a shield the AI never raised was
               // just a stat.
               p.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
               if (part.bot) {
                  give(p, 5, new ItemStack(Items.IRON_AXE));
               }
               break;
            case 1:
               setArmorEnchanted(
                  p, Items.NETHERITE_HELMET, Items.NETHERITE_CHESTPLATE, Items.NETHERITE_LEGGINGS, Items.NETHERITE_BOOTS, Enchantments.PROTECTION, 3
               );
               ItemStack sword = new ItemStack(Items.NETHERITE_SWORD);
               enchantStack(p, sword, Enchantments.SHARPNESS, 3);
               give(p, 0, sword);
               ItemStack mace1 = new ItemStack(Items.MACE);
               enchantStack(p, mace1, Enchantments.DENSITY, 5);
               give(p, 1, mace1);
               ItemStack mace2 = new ItemStack(Items.MACE);
               enchantStack(p, mace2, Enchantments.BREACH, 4);
               give(p, 2, mace2);
               give(p, 3, new ItemStack(Items.WIND_CHARGE, 64));
               give(p, 5, new ItemStack(Items.WIND_CHARGE, 64));
               give(p, 4, new ItemStack(Items.GOLDEN_APPLE, part.bot ? 3 : 16));
               if (!part.bot) {
                  for (int i = 0; i < 16; i++) {
                     give(p, 9 + i, new ItemStack(Items.TOTEM_OF_UNDYING));
                  }
               }
               break;
            case 2:
               give(p, 0, new ItemStack(Items.DIAMOND_PICKAXE));
               give(p, 1, new ItemStack(Items.OBSIDIAN, 64));
               give(p, 2, new ItemStack(Items.END_CRYSTAL, 64));
               give(p, 3, new ItemStack(Items.END_CRYSTAL, 64));
               give(p, 4, new ItemStack(Items.RESPAWN_ANCHOR, 64));
               give(p, 5, new ItemStack(Items.GLOWSTONE, 32));
               give(p, 6, new ItemStack(Items.ENDER_PEARL, 8));
               give(p, 7, new ItemStack(Items.GOLDEN_APPLE, part.bot ? 3 : 8));
               setArmorEnchanted(
                  p, Items.NETHERITE_HELMET, Items.NETHERITE_CHESTPLATE, Items.NETHERITE_LEGGINGS, Items.NETHERITE_BOOTS, Enchantments.BLAST_PROTECTION, 3
               );
               if (!part.bot) {
                  for (int i = 9; i <= 35; i++) {
                     give(p, i, new ItemStack(Items.TOTEM_OF_UNDYING));
                  }
               }
               break;
            case 3:
               give(p, 0, new ItemStack(Items.IRON_SWORD));
               give(p, 1, new ItemStack(Items.BOW));
               give(p, 2, new ItemStack(Items.ARROW, 16));
               give(p, 3, goldenAppleHead(5));
               if (part.bot) {
                  give(p, 4, new ItemStack(Items.GOLDEN_APPLE, 3));
               }

               setArmorEnchanted(p, Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS, Enchantments.PROTECTION, 1);
               break;
            case 4:
               give(p, 0, new ItemStack(Items.IRON_SWORD));
               give(p, 1, new ItemStack(Items.FISHING_ROD));
               give(p, 2, new ItemStack(Items.BOW));
               give(p, 3, new ItemStack(Items.ARROW, 24));
               give(p, 4, new ItemStack(Items.GOLDEN_APPLE, 5));
               setArmor(p, Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS);
               break;
            case 5:
               ItemStack axe = new ItemStack(Items.DIAMOND_AXE);
               enchantStack(p, axe, Enchantments.SHARPNESS, 1);
               give(p, 0, axe);
               give(p, 1, new ItemStack(Items.GOLDEN_APPLE, 5));
               ItemStack healthPotion = new ItemStack(Items.SPLASH_POTION);
               healthPotion.set(DataComponents.POTION_CONTENTS, PotionContents.EMPTY.withPotion(Potions.HEALING));

               for (int i = 2; i <= 8; i++) {
                  give(p, i, healthPotion.copy());
               }

               ItemStack speedPotion = new ItemStack(Items.SPLASH_POTION);
               speedPotion.set(DataComponents.POTION_CONTENTS, PotionContents.EMPTY.withPotion(Potions.STRONG_SWIFTNESS));
               give(p, 9, speedPotion);
               if (part.bot) {
                  // Splash potions are thrown, never drunk, so the bot had nothing to
                  // drink and its "drinking" was an instant heal. This one it has to
                  // hold down for the full potion duration like anybody else.
                  ItemStack drink = new ItemStack(Items.POTION);
                  drink.set(DataComponents.POTION_CONTENTS, PotionContents.EMPTY.withPotion(Potions.HEALING));
                  give(p, 10, drink);
                  give(p, 11, drink.copy());
               }
               setArmorEnchanted(p, Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS, Enchantments.PROTECTION, 2);
               break;
            case 6:
               give(p, 0, new ItemStack(Items.DIAMOND_SWORD));
               setArmorEnchanted(p, Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS, Enchantments.PROTECTION, 4);
               break;
            case 7:
               give(p, 0, new ItemStack(Items.WOODEN_SWORD));
               give(p, 8, new ItemStack(whiteWool(), 16));
               p.setItemSlot(EquipmentSlot.HEAD, coloredLeatherArmorStack(part.slot, Items.LEATHER_HELMET));
               p.setItemSlot(EquipmentSlot.CHEST, coloredLeatherArmorStack(part.slot, Items.LEATHER_CHESTPLATE));
               p.setItemSlot(EquipmentSlot.LEGS, coloredLeatherArmorStack(part.slot, Items.LEATHER_LEGGINGS));
               p.setItemSlot(EquipmentSlot.FEET, coloredLeatherArmorStack(part.slot, Items.LEATHER_BOOTS));
               break;
            case 8:
               giveSkywarsKit(part);
               break;
            case 9:
               giveLuckyPvp(part, d);
               break;
            case 10:
               giveKitsFightGear(part, d);
               break;
            case 11:
               give(p, 0, randomizerWeapon(p));
               give(p, 2, new ItemStack(Items.COOKED_BEEF, 8));
               Item[] armorPool = new Item[]{
                  Items.LEATHER_HELMET,
                  Items.LEATHER_CHESTPLATE,
                  Items.LEATHER_LEGGINGS,
                  Items.LEATHER_BOOTS,
                  Items.CHAINMAIL_HELMET,
                  Items.CHAINMAIL_CHESTPLATE,
                  Items.CHAINMAIL_LEGGINGS,
                  Items.CHAINMAIL_BOOTS,
                  Items.IRON_HELMET,
                  Items.IRON_CHESTPLATE,
                  Items.IRON_LEGGINGS,
                  Items.IRON_BOOTS
               };
               setArmor(
                  p,
                  armorPool[(int)(Math.random() * 12.0)],
                  armorPool[(int)(Math.random() * 12.0)],
                  armorPool[(int)(Math.random() * 12.0)],
                  armorPool[(int)(Math.random() * 12.0)]
               );
               break;
            case 12:
               InventoryHelper.giveOrDrop(p, new ItemStack(Items.COOKED_BEEF, 6));
               if (part.draftArmor.isEmpty()) {
                  setArmor(p, Items.LEATHER_HELMET, Items.LEATHER_CHESTPLATE, Items.LEATHER_LEGGINGS, Items.LEATHER_BOOTS);
               } else {
                  EquipmentSlot[] slots = new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

                  for (int i = 0; i < 4 && i < part.draftArmor.size(); i++) {
                     p.setItemSlot(slots[i], ((ItemStack)part.draftArmor.get(i)).copy());
                  }
               }
               break;
            case 13:
               give(p, 0, new ItemStack(Items.IRON_SWORD));
               give(p, 1, new ItemStack(Items.GOLDEN_APPLE, 4));
               give(p, 2, new ItemStack(Items.COOKED_BEEF, 8));
               setArmor(p, Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS);
               break;
            case 14:
               give(p, 0, boostFeather());
               ItemStack tntSpeed = new ItemStack(Items.SPLASH_POTION);
               tntSpeed.set(DataComponents.POTION_CONTENTS, PotionContents.EMPTY.withPotion(Potions.STRONG_SWIFTNESS));
               give(p, 1, tntSpeed);
               ItemStack tntSlow = new ItemStack(Items.SPLASH_POTION);
               tntSlow.set(DataComponents.POTION_CONTENTS, PotionContents.EMPTY.withPotion(Potions.SLOWNESS));
               give(p, 2, tntSlow);
               break;
            case 15:
               // Sumo: a knockback blade, cloth armour, and no healing. The only
               // way to win is to put the other player past the rim, so damage is
               // deliberately too small to be a win condition on its own.
               ItemStack kbStick = new ItemStack(Items.WOODEN_SWORD);
               enchantStack(p, kbStick, Enchantments.KNOCKBACK, 2);
               give(p, 0, kbStick);
               give(p, 1, new ItemStack(Items.COOKED_BEEF, 6));
               setArmor(p, Items.LEATHER_HELMET, Items.LEATHER_CHESTPLATE, Items.LEATHER_LEGGINGS, Items.LEATHER_BOOTS);
               break;
            case 16:
               // Archery: a bow and nothing to swing. The melee gate below makes
               // that literal rather than merely implied.
               ItemStack archeryBow = new ItemStack(Items.BOW);
               enchantStack(p, archeryBow, Enchantments.POWER, 3);
               give(p, 0, archeryBow);
               give(p, 1, new ItemStack(Items.ARROW, part.bot ? 48 : 32));
               setArmor(p, Items.LEATHER_HELMET, Items.LEATHER_CHESTPLATE, Items.LEATHER_LEGGINGS, Items.LEATHER_BOOTS);
               break;
            case 17:
               // Gladiator: no gear. You are dropped into the middle of a wide map
               // with empty hands, and the sword is something you go and find - the
               // ore in the ground, the caches on the surface. Handing out a kit
               // here was the entire point of the mode, made moot.
               //
               // The bot gets the two things that let it work the map the way a
               // player does, and nothing that fights for it: a furnace and a
               // table. Without them "mine, smelt, craft" is a phrase rather than
               // three things that take time.
               if (part.bot) {
                  give(p, 9, new ItemStack(Items.FURNACE));
                  give(p, 10, new ItemStack(Items.CRAFTING_TABLE));
                  give(p, 11, new ItemStack(Items.COAL, 2));
               }
               break;
         }

         applyCombat(p, d.combat);
      }
   }

   private static ItemStack boostFeather() {
      ItemStack f = new ItemStack(Items.FEATHER);
      CompoundTag tag = new CompoundTag();
      tag.putInt("ff_boost_uses", 5);
      f.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
      f.set(DataComponents.CUSTOM_NAME, Component.literal("§b§lBoost Feather"));
      f.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("§7Right-click to launch upward"), Component.literal("§e5 uses"))));
      return f;
   }

   public static boolean isBoostFeather(ItemStack s) {
      if (s != null && !s.isEmpty() && s.is(Items.FEATHER)) {
         CustomData data = (CustomData)s.get(DataComponents.CUSTOM_DATA);
         return data != null && data.copyTag().contains("ff_boost_uses");
      } else {
         return false;
      }
   }

   public static int boostFeatherUses(ItemStack s) {
      return !isBoostFeather(s) ? 0 : ((CustomData)s.get(DataComponents.CUSTOM_DATA)).copyTag().getInt("ff_boost_uses").orElse(0);
   }

   public static boolean tryFeatherBoost(ServerPlayer p, ItemStack held) {
      if (p != null && held != null && isBoostFeather(held)) {
         Duel d = duels.get(p.getUUID());
         if (d != null && d.mode == DuelMode.TNTRUN && d.phase == Phase.FIGHT) {
            int uses = boostFeatherUses(held);
            if (uses <= 0) {
               Chat.msg(p, "&7Your boost feather is out of uses!");
               return true;
            }

            p.setDeltaMovement(p.getDeltaMovement().x, 1.2, p.getDeltaMovement().z);
            p.hurtMarked = true;
            p.resetFallDistance();
            if (p.connection != null) {
               p.connection.send(new ClientboundSetEntityMotionPacket(p.getId(), p.getDeltaMovement()));
            }

            ServerLevel sl = p.level();
            com.fortuneandfavors.net.FfVfx.particles(sl, ParticleTypes.CLOUD, p.getX(), p.getY() + 0.5, p.getZ(), 20, 0.4, 0.2, 0.4, 0.04);
            com.fortuneandfavors.net.FfVfx.particles(sl, ParticleTypes.ENCHANT, p.getX(), p.getY() + 0.5, p.getZ(), 12, 0.4, 0.3, 0.4, 0.06);
            sl.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.PHANTOM_FLAP, SoundSource.PLAYERS, 1.0F, 1.4F);
            if (uses - 1 <= 0) {
               held.shrink(1);
            } else {
               CompoundTag tag = ((CustomData)held.get(DataComponents.CUSTOM_DATA)).copyTag();
               tag.putInt("ff_boost_uses", uses - 1);
               held.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
               held.set(
                  DataComponents.LORE,
                  new ItemLore(
                     List.of(
                        Component.literal("§7Right-click to launch upward"),
                        Component.literal("§e" + (uses - 1) + " use" + (uses - 1 == 1 ? "" : "s") + " left")
                     )
                  )
               );
            }

            Chat.msg(p, "&bBoost! &7(" + (uses - 1) + " use" + (uses - 1 == 1 ? "" : "s") + " left)");
            return true;
         } else {
            return false;
         }
      } else {
         return false;
      }
   }

   private static void giveSkywarsKit(Participant part) {
      ServerPlayer p = part.player;
      if (p != null) {
         switch (part.kit == null ? "knight" : part.kit) {
            case "archer":
               give(p, 0, new ItemStack(Items.STONE_SWORD));
               give(p, 1, new ItemStack(Items.BOW));
               give(p, 2, new ItemStack(Items.ARROW, 32));
               give(p, 3, new ItemStack(whiteWool(), 48));
               give(p, 4, new ItemStack(Items.COOKED_BEEF, 6));
               give(p, 5, new ItemStack(Items.GOLDEN_APPLE));
               p.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET));
               p.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.LEATHER_CHESTPLATE));
               break;
            case "tank":
               give(p, 0, new ItemStack(Items.STONE_SWORD));
               give(p, 1, new ItemStack(whiteWool(), 48));
               give(p, 2, new ItemStack(Items.COOKED_BEEF, 10));
               give(p, 3, new ItemStack(Items.GOLDEN_APPLE, 2));
               p.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
               p.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.IRON_LEGGINGS));
               p.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.IRON_BOOTS));
               break;
            case "rush":
               give(p, 0, new ItemStack(Items.STONE_SWORD));
               give(p, 1, new ItemStack(whiteWool(), 96));
               give(p, 2, new ItemStack(Items.COOKED_BEEF, 8));
               give(p, 3, new ItemStack(Items.ENDER_PEARL));
               p.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.LEATHER_BOOTS));
               break;
            case "builder":
               give(p, 0, new ItemStack(Items.STONE_SWORD));
               give(p, 1, new ItemStack(whiteWool(), 128));
               give(p, 2, new ItemStack(Items.OAK_PLANKS, 64));
               give(p, 3, new ItemStack(Items.COOKED_BEEF, 8));
               give(p, 4, new ItemStack(Items.LAVA_BUCKET));
               give(p, 5, new ItemStack(Items.WATER_BUCKET));
               p.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET));
               p.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.LEATHER_BOOTS));
               break;
            case "berserker":
               give(p, 0, new ItemStack(Items.IRON_AXE));
               give(p, 1, new ItemStack(Items.STONE_SWORD));
               give(p, 2, new ItemStack(whiteWool(), 32));
               give(p, 3, new ItemStack(Items.COOKED_BEEF, 12));
               give(p, 4, new ItemStack(Items.GOLDEN_APPLE, 2));
               give(p, 5, new ItemStack(Items.ENDER_PEARL));
               p.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.LEATHER_CHESTPLATE));
               break;
            case "mage":
               give(p, 0, new ItemStack(Items.STONE_SWORD));
               give(p, 1, new ItemStack(Items.BOW));
               give(p, 2, new ItemStack(Items.ARROW, 16));
               give(p, 3, new ItemStack(whiteWool(), 32));
               give(p, 4, new ItemStack(Items.FIRE_CHARGE, 8));
               give(p, 5, new ItemStack(Items.COOKED_BEEF, 6));
               give(p, 6, new ItemStack(Items.GOLDEN_APPLE));
               p.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET));
               p.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.LEATHER_CHESTPLATE));
               p.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.LEATHER_LEGGINGS));
               break;
            default:
               give(p, 0, new ItemStack(Items.IRON_SWORD));
               give(p, 1, new ItemStack(whiteWool(), 48));
               give(p, 2, new ItemStack(Items.COOKED_BEEF, 8));
               give(p, 3, new ItemStack(Items.GOLDEN_APPLE));
               p.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.CHAINMAIL_HELMET));
               p.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.LEATHER_CHESTPLATE));
               p.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.CHAINMAIL_LEGGINGS));
         }
      }
   }

   private static void giveKitsFightGear(Participant part, Duel d) {
      ServerPlayer p = part.player;
      if (p != null && !p.level().isClientSide()) {
         String kit = part.kitChosen && isValidFunKit(part.kit)
            ? part.kit
            : (part.bot ? botPickFunKit(d.botDifficulty) : KIT_NAMES[(int)(Math.random() * KIT_NAMES.length)]);
         part.kitName = kit;
         applyFunKit(p, kit);
         Chat.msg(p, "§d✨ Your kit: §f" + kit + "§d!");
      }
   }

   private static void applyFunKitEffects(ServerPlayer p, String kit) {
      if (p != null && kit != null) {
         switch (kit) {
            case "Frog":
               p.addEffect(new MobEffectInstance(MobEffects.JUMP_BOOST, -1, 2, false, false));
               break;
            case "Scout":
               p.addEffect(new MobEffectInstance(MobEffects.SPEED, -1, 1, false, false));
               break;
            case "Enderman":
               p.addEffect(new MobEffectInstance(MobEffects.SPEED, -1, 0, false, false));
         }
      }
   }

   private static void applyFunKit(ServerPlayer p, String kit) {
      switch (kit) {
         case "Archer":
            give(p, 0, new ItemStack(Items.DIAMOND_SWORD));
            give(p, 1, new ItemStack(Items.BOW));
            give(p, 2, new ItemStack(Items.ARROW, 64));
            give(p, 3, new ItemStack(whiteWool(), 64));
            give(p, 4, new ItemStack(Items.COOKED_BEEF, 12));
            give(p, 5, new ItemStack(Items.GOLDEN_APPLE, 3));
            p.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.CHAINMAIL_HELMET));
            p.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.LEATHER_CHESTPLATE));
            p.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.LEATHER_LEGGINGS));
            break;
         case "Tank":
            give(p, 0, new ItemStack(Items.DIAMOND_SWORD));
            give(p, 1, new ItemStack(whiteWool(), 64));
            give(p, 2, new ItemStack(Items.COOKED_BEEF, 14));
            give(p, 3, new ItemStack(Items.GOLDEN_APPLE, 3));
            p.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
            p.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
            p.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.IRON_LEGGINGS));
            p.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.IRON_BOOTS));
            break;
         case "Rush":
            give(p, 0, new ItemStack(Items.DIAMOND_SWORD));
            give(p, 1, new ItemStack(whiteWool(), 128));
            give(p, 2, new ItemStack(Items.COOKED_BEEF, 10));
            give(p, 3, new ItemStack(Items.ENDER_PEARL, 3));
            give(p, 4, new ItemStack(Items.GOLDEN_APPLE, 2));
            p.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.CHAINMAIL_BOOTS));
            break;
         case "Builder":
            give(p, 0, new ItemStack(Items.DIAMOND_SWORD));
            give(p, 1, new ItemStack(whiteWool(), 192));
            give(p, 2, new ItemStack(Items.OAK_PLANKS, 64));
            give(p, 3, new ItemStack(Items.COOKED_BEEF, 10));
            give(p, 4, new ItemStack(Items.LAVA_BUCKET));
            give(p, 5, new ItemStack(Items.WATER_BUCKET));
            give(p, 6, new ItemStack(Items.GOLDEN_APPLE, 2));
            p.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.CHAINMAIL_HELMET));
            p.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.CHAINMAIL_BOOTS));
            break;
         case "Berserker":
            give(p, 0, new ItemStack(Items.DIAMOND_AXE));
            give(p, 1, new ItemStack(Items.DIAMOND_SWORD));
            give(p, 2, new ItemStack(whiteWool(), 48));
            give(p, 3, new ItemStack(Items.COOKED_BEEF, 16));
            give(p, 4, new ItemStack(Items.GOLDEN_APPLE, 3));
            give(p, 5, new ItemStack(Items.ENDER_PEARL, 2));
            p.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET));
            p.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.LEATHER_CHESTPLATE));
            p.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.LEATHER_LEGGINGS));
            p.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.LEATHER_BOOTS));
            break;
         case "Mage":
            give(p, 0, new ItemStack(Items.DIAMOND_SWORD));
            give(p, 1, new ItemStack(Items.BOW));
            give(p, 2, new ItemStack(Items.ARROW, 48));
            give(p, 3, new ItemStack(whiteWool(), 48));
            give(p, 4, new ItemStack(Items.FIRE_CHARGE, 32));
            give(p, 5, new ItemStack(Items.COOKED_BEEF, 12));
            give(p, 6, new ItemStack(Items.GOLDEN_APPLE, 3));
            p.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.CHAINMAIL_HELMET));
            p.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.CHAINMAIL_CHESTPLATE));
            p.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.CHAINMAIL_LEGGINGS));
            break;
         case "Ninja":
            give(p, 0, new ItemStack(Items.DIAMOND_SWORD));
            give(p, 1, speedPotion(Potions.STRONG_SWIFTNESS));
            give(p, 2, speedPotion(Potions.STRONG_SWIFTNESS));
            give(p, 3, speedPotion(Potions.STRONG_SWIFTNESS));
            give(p, 4, new ItemStack(Items.ENDER_PEARL, 2));
            give(p, 5, new ItemStack(Items.COOKED_BEEF, 10));
            p.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET));
            p.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.LEATHER_CHESTPLATE));
            p.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.LEATHER_LEGGINGS));
            p.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.LEATHER_BOOTS));
            break;
         case "Pyro":
            give(p, 0, new ItemStack(Items.DIAMOND_SWORD));
            give(p, 1, new ItemStack(Items.FLINT_AND_STEEL));
            give(p, 2, new ItemStack(Items.LAVA_BUCKET));
            give(p, 3, new ItemStack(Items.FIRE_CHARGE, 32));
            give(p, 4, new ItemStack(whiteWool(), 48));
            give(p, 5, new ItemStack(Items.COOKED_BEEF, 12));
            give(p, 6, new ItemStack(Items.GOLDEN_APPLE, 2));
            p.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET));
            p.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.LEATHER_CHESTPLATE));
            p.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.LEATHER_LEGGINGS));
            break;
         case "Frog":
            give(p, 0, new ItemStack(Items.DIAMOND_SWORD));
            give(p, 1, new ItemStack(whiteWool(), 64));
            give(p, 2, new ItemStack(Items.COOKED_BEEF, 10));
            give(p, 3, new ItemStack(Items.GOLDEN_APPLE, 2));
            ItemStack boots = new ItemStack(Items.LEATHER_BOOTS);
            enchantStack(p, boots, Enchantments.FEATHER_FALLING, 4);
            p.setItemSlot(EquipmentSlot.FEET, boots);
            break;
         case "Fisherman":
            give(p, 0, new ItemStack(Items.DIAMOND_SWORD));
            give(p, 1, new ItemStack(Items.FISHING_ROD));
            give(p, 2, new ItemStack(Items.WATER_BUCKET));
            give(p, 3, new ItemStack(whiteWool(), 64));
            give(p, 4, new ItemStack(Items.COOKED_BEEF, 12));
            give(p, 5, new ItemStack(Items.GOLDEN_APPLE, 3));
            p.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.CHAINMAIL_HELMET));
            p.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.LEATHER_CHESTPLATE));
            p.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.LEATHER_LEGGINGS));
            break;
         case "Scout":
            give(p, 0, new ItemStack(Items.DIAMOND_SWORD));
            give(p, 1, new ItemStack(whiteWool(), 64));
            give(p, 2, new ItemStack(Items.COOKED_BEEF, 10));
            give(p, 3, new ItemStack(Items.GOLDEN_APPLE, 2));
            p.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.LEATHER_LEGGINGS));
            p.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.LEATHER_BOOTS));
            break;
         case "Enderman":
            give(p, 0, new ItemStack(Items.DIAMOND_SWORD));
            give(p, 1, new ItemStack(Items.ENDER_PEARL, 4));
            give(p, 2, new ItemStack(whiteWool(), 64));
            give(p, 3, new ItemStack(Items.COOKED_BEEF, 12));
            give(p, 4, new ItemStack(Items.GOLDEN_APPLE, 2));
            p.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.CHAINMAIL_HELMET));
            p.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.LEATHER_CHESTPLATE));
            p.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.LEATHER_LEGGINGS));
            break;
         case "Snowman":
            give(p, 0, new ItemStack(Items.DIAMOND_SWORD));
            give(p, 1, new ItemStack(Items.SNOWBALL, 64));
            give(p, 2, speedPotion(Potions.SLOWNESS));
            give(p, 3, speedPotion(Potions.SLOWNESS));
            give(p, 4, speedPotion(Potions.SLOWNESS));
            give(p, 5, new ItemStack(Items.COOKED_BEEF, 12));
            give(p, 6, new ItemStack(Items.GOLDEN_APPLE, 2));
            p.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.CHAINMAIL_HELMET));
            p.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.CHAINMAIL_CHESTPLATE));
            p.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.CHAINMAIL_LEGGINGS));
            break;
         case "Creeper":
            give(p, 0, new ItemStack(Items.DIAMOND_SWORD));
            give(p, 1, new ItemStack(Items.TNT, 18));
            give(p, 2, new ItemStack(Items.FLINT_AND_STEEL));
            give(p, 3, new ItemStack(whiteWool(), 48));
            give(p, 4, new ItemStack(Items.COOKED_BEEF, 12));
            give(p, 5, new ItemStack(Items.GOLDEN_APPLE, 2));
            p.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.CHAINMAIL_HELMET));
            p.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.CHAINMAIL_CHESTPLATE));
            p.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.CHAINMAIL_BOOTS));
            break;
         case "Healer":
            give(p, 0, new ItemStack(Items.DIAMOND_SWORD));
            give(p, 1, new ItemStack(whiteWool(), 32));
            give(p, 2, new ItemStack(Items.COOKED_BEEF, 6));
            give(p, 3, goldenAppleHead(10));
            p.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.CHAINMAIL_HELMET));
            p.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.CHAINMAIL_CHESTPLATE));
            p.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.CHAINMAIL_LEGGINGS));
            p.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.CHAINMAIL_BOOTS));
            break;
         default:
            give(p, 0, new ItemStack(Items.DIAMOND_SWORD));
            give(p, 1, new ItemStack(whiteWool(), 64));
            give(p, 2, new ItemStack(Items.COOKED_BEEF, 12));
            give(p, 3, new ItemStack(Items.GOLDEN_APPLE, 3));
            p.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
            p.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.CHAINMAIL_CHESTPLATE));
            p.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.CHAINMAIL_LEGGINGS));
            p.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.IRON_BOOTS));
      }
   }

   private static ItemStack speedPotion(Holder<Potion> potion) {
      ItemStack s = new ItemStack(Items.SPLASH_POTION);
      s.set(DataComponents.POTION_CONTENTS, PotionContents.EMPTY.withPotion(potion));
      return s;
   }

   private static void giveLuckyPvp(Participant part, Duel d) {
      ServerPlayer p = part.player;
      if (p != null) {
         ItemStack weapon = rollLuckyWeapon(p);
         ItemStack armor = rollLuckyArmor(p);
         ItemStack wildcard = rollLuckyWildcard();
         EquipmentSlot slot = armorSlotFor(armor.getItem());
         if (slot != null) {
            p.setItemSlot(slot, armor);
         } else {
            give(p, 2, armor);
         }

         give(p, 0, weapon);
         give(p, 1, wildcard);
         if (weapon.getItem() instanceof BowItem) {
            give(p, 2, new ItemStack(Items.ARROW, 16 + (int)(Math.random() * 16.0)));
         }

         if (weapon.getItem() instanceof CrossbowItem) {
            give(p, 2, new ItemStack(Items.FIREWORK_ROCKET, 16 + (int)(Math.random() * 12.0)));
         }

         if (weapon.is(Items.MACE)) {
            give(p, 3, new ItemStack(Items.WIND_CHARGE, 3));
         }

         p.getInventory().setSelectedSlot(0);
         p.setInvulnerable(true);
         p.getAbilities().invulnerable = true;
         if (p.connection != null) {
            p.connection.send(new ClientboundPlayerAbilitiesPacket(p.getAbilities()));
         }

         part.spinInvulUntil = ServerClock.clock(p.level()) + 25L;
         if (weapon.has(DataComponents.CUSTOM_NAME)
            && ((Component)weapon.get(DataComponents.CUSTOM_NAME)).getString().contains("Excalibur")
            && p.level() instanceof ServerLevel sl) {
            double x = p.getX();
            double y = p.getY();
            double z = p.getZ();
            com.fortuneandfavors.net.FfVfx.particles(sl, ParticleTypes.TOTEM_OF_UNDYING, x, y + 1.5, z, 120, 1.2, 1.6, 1.2, 0.25);
            com.fortuneandfavors.net.FfVfx.particles(sl, ParticleTypes.END_ROD, x, y + 2.2, z, 90, 0.8, 1.4, 0.8, 0.2);
            com.fortuneandfavors.net.FfVfx.particles(sl, ParticleTypes.ENCHANT, x, y + 1.0, z, 160, 1.0, 1.2, 1.0, 0.2);
            com.fortuneandfavors.net.FfVfx.particles(sl, ParticleTypes.GLOW_SQUID_INK, x, y + 1.0, z, 40, 0.5, 0.8, 0.5, 0.05);
            com.fortuneandfavors.net.FfVfx.particles(sl, ParticleTypes.CRIT, x, y + 1.2, z, 60, 0.6, 0.8, 0.6, 0.2);
            sl.playSound(null, x, y, z, SoundEvents.TOTEM_USE, SoundSource.PLAYERS, 2.0F, 0.5F);
            sl.playSound(null, x, y, z, SoundEvents.END_PORTAL_SPAWN, SoundSource.PLAYERS, 2.0F, 0.4F);
            sl.playSound(null, x, y, z, SoundEvents.LIGHTNING_BOLT_IMPACT, SoundSource.PLAYERS, 2.0F, 0.6F);
            sl.playSound(null, x, y, z, SoundEvents.BEACON_ACTIVATE, SoundSource.PLAYERS, 2.0F, 0.8F);

            for (Participant otherPart : d.parts) {
               ServerPlayer viewer = otherPart.player;
               if (viewer != null) {
                  if (otherPart == part && viewer.connection != null) {
                     viewer.connection.send(new ClientboundSetTitleTextPacket(Component.literal("§6§l✦ EXCALIBUR ✦")));
                     viewer.connection.send(new ClientboundSetTitlesAnimationPacket(5, 60, 15));
                     viewer.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal("§eThe legendary sword is yours. No mercy.")));
                  } else if (viewer.connection != null) {
                     viewer.connection.send(new ClientboundSetTitleTextPacket(Component.literal("§6§l✦ EXCALIBUR ✦")));
                     viewer.connection.send(new ClientboundSetTitlesAnimationPacket(5, 50, 15));
                     viewer.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal("§c" + part.displayName + " §edrew the legendary sword!")));
                  }
               }
            }

            announce(d, "§6§l✦ EXCALIBUR ✦§r§7 - " + part.displayName + " §drolled the legendary sword! One hit. No mercy.");
         }

         if (!part.bot) {
            LuckyPvpMenu.open(p, weapon, armor, wildcard);
         }
      }
   }

   private static ItemStack rollLuckyWeapon(ServerPlayer p) {
      if (Math.random() < 0.001) {
         // Same sword as /ff give excalibur, so it renders with its own art here too.
         ItemStack s = com.fortuneandfavors.ModItems.excalibur();
         enchantStack(p, s, Enchantments.SHARPNESS, 255);
         enchantStack(p, s, Enchantments.FIRE_ASPECT, 255);
         enchantStack(p, s, Enchantments.KNOCKBACK, 10);
         return s;
      }

      if (Math.random() < 0.05) {
         ItemStack s = new ItemStack(Items.MACE);
         s.set(DataComponents.CUSTOM_NAME, Component.literal("§b§lLucky Mace"));
         if (Math.random() < 0.5) {
            enchantStack(p, s, Enchantments.SHARPNESS, 1 + (int)(Math.random() * 2.0));
         }

         return s;
      } else {
         int r = (int)(Math.random() * 100.0);
         if (r < 4) {
            ItemStack s = new ItemStack(Items.STICK);
            s.set(DataComponents.CUSTOM_NAME, Component.literal("§6§lKnockback Stick"));
            enchantStack(p, s, Enchantments.KNOCKBACK, 2);
            return s;
         }

         if (r < 7) {
            ItemStack s = new ItemStack(Items.STICK);
            s.set(DataComponents.CUSTOM_NAME, Component.literal("§7Stick"));
            return s;
         }

         if (r < 15) {
            ItemStack s = new ItemStack(Items.TRIDENT);
            s.set(DataComponents.CUSTOM_NAME, Component.literal("§a§lLucky Spear"));
            enchantStack(p, s, Enchantments.LOYALTY, 3);
            if (p instanceof DuelBot) {
               enchantStack(p, s, Enchantments.RIPTIDE, 2);
            }
            if (Math.random() < 0.5) {
               enchantStack(p, s, Enchantments.SHARPNESS, 1 + (int)(Math.random() * 2.0));
            }

            return s;
         } else if (r < 22) {
            ItemStack s = new ItemStack(Items.CROSSBOW);
            s.set(DataComponents.CUSTOM_NAME, Component.literal("§d§lLucky Crossbow"));
            if (Math.random() < 0.4) {
               enchantStack(p, s, Enchantments.QUICK_CHARGE, 1 + (int)(Math.random() * 2.0));
            }

            return s;
         } else if (r < 32) {
            ItemStack s = new ItemStack(Items.BOW);
            s.set(DataComponents.CUSTOM_NAME, Component.literal("§b§lLucky Bow"));
            if (Math.random() < 0.4) {
               enchantStack(p, s, Enchantments.POWER, 1 + (int)(Math.random() * 2.0));
            }

            return s;
         } else if (r < 45) {
            Item axe = Math.random() < 0.4 ? Items.IRON_AXE : Items.DIAMOND_AXE;
            ItemStack s = new ItemStack(axe);
            if (Math.random() < 0.35) {
               enchantStack(p, s, Enchantments.SHARPNESS, 1 + (int)(Math.random() * 2.0));
            }

            return s;
         } else {
            Item sword = switch ((int)(Math.random() * 4.0)) {
               case 0 -> Items.WOODEN_SWORD;
               case 1 -> Items.STONE_SWORD;
               case 2 -> Items.IRON_SWORD;
               default -> Items.DIAMOND_SWORD;
            };
            ItemStack s = new ItemStack(sword);
            if (Math.random() < 0.45) {
               enchantStack(p, s, Math.random() < 0.5 ? Enchantments.SHARPNESS : Enchantments.FIRE_ASPECT, 1 + (int)(Math.random() * 2.0));
            }

            return s;
         }
      }
   }

   private static ItemStack rollLuckyArmor(ServerPlayer p) {
      Item[] pool = new Item[]{
         Items.CHAINMAIL_HELMET,
         Items.CHAINMAIL_CHESTPLATE,
         Items.CHAINMAIL_LEGGINGS,
         Items.CHAINMAIL_BOOTS,
         Items.CHAINMAIL_HELMET,
         Items.CHAINMAIL_CHESTPLATE,
         Items.CHAINMAIL_LEGGINGS,
         Items.CHAINMAIL_BOOTS,
         Items.IRON_HELMET,
         Items.IRON_CHESTPLATE,
         Items.IRON_LEGGINGS,
         Items.IRON_BOOTS,
         Items.IRON_HELMET,
         Items.IRON_CHESTPLATE,
         Items.IRON_LEGGINGS,
         Items.IRON_BOOTS,
         Items.DIAMOND_HELMET,
         Items.DIAMOND_CHESTPLATE,
         Items.DIAMOND_LEGGINGS,
         Items.DIAMOND_BOOTS
      };
      ItemStack s = new ItemStack(pool[(int)(Math.random() * pool.length)]);
      if (Math.random() < 0.4) {
         enchantStack(p, s, Enchantments.PROTECTION, 1 + (int)(Math.random() * 3.0));
      }

      return s;
   }

   private static ItemStack rollLuckyWildcard() {
      if (Math.random() < 0.001) {
         ItemStack s = new ItemStack(Items.WITHER_SPAWN_EGG);
         s.set(DataComponents.CUSTOM_NAME, Component.literal("§8§l☠ THE WITHER ☠"));
         s.set(
            DataComponents.LORE, new ItemLore(List.of(Component.literal("§4§lLegendary Catastrophe"), Component.literal("§7A god of destruction awaits...")))
         );
         return s;
      } else if (Math.random() < 0.005) {
         ItemStack s = new ItemStack(Items.PLAYER_HEAD, 1);
         s.set(DataComponents.CUSTOM_NAME, Component.literal("§6§lGolden Apple Head"));
         s.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("§e§lLucky PvP Reward"), Component.literal("§7Wear it for absorption + regen!"))));
         return s;
      } else {
         int r = (int)(Math.random() * 150.0);
         if (r < 2) {
            return new ItemStack(Items.WARDEN_SPAWN_EGG);
         } else if (r < 6) {
            Item[] eggs = new Item[]{
               Items.ZOMBIE_SPAWN_EGG,
               Items.CREEPER_SPAWN_EGG,
               Items.BLAZE_SPAWN_EGG,
               Items.IRON_GOLEM_SPAWN_EGG,
               Items.ENDERMAN_SPAWN_EGG,
               Items.AXOLOTL_SPAWN_EGG,
               Items.VILLAGER_SPAWN_EGG,
               Items.CAT_SPAWN_EGG,
               Items.WOLF_SPAWN_EGG,
               Items.PIG_SPAWN_EGG,
               Items.COW_SPAWN_EGG,
               Items.SHEEP_SPAWN_EGG
            };
            return new ItemStack(eggs[(int)(Math.random() * eggs.length)]);
         } else if (r < 10) {
            return new ItemStack(Items.ENCHANTED_GOLDEN_APPLE);
         } else if (r < 17) {
            return new ItemStack(Items.GOLDEN_APPLE, 3);
         } else if (r < 24) {
            return new ItemStack(Items.COOKED_BEEF, 12);
         } else if (r < 30) {
            return new ItemStack(Items.GOLDEN_CARROT, 8);
         } else if (r < 36) {
            return new ItemStack(Items.ENDER_PEARL, 3);
         } else if (r < 40) {
            return new ItemStack(Items.TOTEM_OF_UNDYING);
         } else if (r < 45) {
            return new ItemStack(Items.EXPERIENCE_BOTTLE, 16);
         } else if (r < 50) {
            return new ItemStack(Items.SHIELD);
         } else if (r < 55) {
            return new ItemStack(Items.WIND_CHARGE, 8);
         } else if (r < 65) {
            return new ItemStack(Items.COBWEB, 4);
         } else if (r < 70) {
            return new ItemStack(Items.TNT, 4);
         } else if (r < 75) {
            return new ItemStack(Items.FIRE_CHARGE, 8);
         } else if (r < 80) {
            return new ItemStack(Items.LAVA_BUCKET);
         } else if (r < 86) {
            return new ItemStack(Items.SNOWBALL, 16);
         } else if (r < 92) {
            return new ItemStack(Items.SPLASH_POTION);
         } else if (r < 97) {
            return new ItemStack(Items.LINGERING_POTION);
         } else if (r < 102) {
            return new ItemStack(Items.POTION);
         } else if (r < 108) {
            return new ItemStack(Items.ENDER_EYE, 4);
         } else if (r < 114) {
            return new ItemStack(Items.SWEET_BERRIES, 16);
         } else if (r < 120) {
            return new ItemStack(Items.FLINT_AND_STEEL);
         } else if (r < 126) {
            return new ItemStack(Items.SHEARS);
         } else if (r < 132) {
            return new ItemStack(Items.CRYING_OBSIDIAN, 8);
         } else if (r < 138) {
            return new ItemStack(Items.GLOWSTONE, 8);
         } else {
            return r < 144 ? new ItemStack(Items.QUARTZ, 16) : new ItemStack(Items.BONE, 32);
         }
      }
   }

   private static void give(ServerPlayer p, int slot, ItemStack stack) {
      p.getInventory().setItem(slot, stack);
   }

   private static void setArmor(ServerPlayer p, Item head, Item chest, Item legs, Item feet) {
      p.setItemSlot(EquipmentSlot.HEAD, new ItemStack(head));
      p.setItemSlot(EquipmentSlot.CHEST, new ItemStack(chest));
      p.setItemSlot(EquipmentSlot.LEGS, new ItemStack(legs));
      p.setItemSlot(EquipmentSlot.FEET, new ItemStack(feet));
   }

   private static void setArmorEnchanted(ServerPlayer p, Item head, Item chest, Item legs, Item feet, ResourceKey<Enchantment> enchant, int level) {
      ItemStack h = new ItemStack(head);
      ItemStack c = new ItemStack(chest);
      ItemStack l = new ItemStack(legs);
      ItemStack b = new ItemStack(feet);
      enchantStack(p, h, enchant, level);
      enchantStack(p, c, enchant, level);
      enchantStack(p, l, enchant, level);
      enchantStack(p, b, enchant, level);
      p.setItemSlot(EquipmentSlot.HEAD, h);
      p.setItemSlot(EquipmentSlot.CHEST, c);
      p.setItemSlot(EquipmentSlot.LEGS, l);
      p.setItemSlot(EquipmentSlot.FEET, b);
   }

   private static void resetMaxHealth(ServerPlayer p) {
      try {
         AttributeInstance hp = p.getAttribute(Attributes.MAX_HEALTH);
         if (hp != null) {
            hp.setBaseValue(20.0);
         }

         if (p.getHealth() > p.getMaxHealth()) {
            p.setHealth(p.getMaxHealth());
         }
      } catch (Exception var2) {
      }
   }

   private static ItemStack coloredLeatherArmorStack(int slot, Item base) {
      ItemStack s = new ItemStack(base);
      int color = slot == 0 ? 13369344 : 22015;
      s.set(DataComponents.DYED_COLOR, new DyedItemColor(color));
      return s;
   }

   private static String kitName(String kit) {
      return switch (kit == null ? "knight" : kit) {
         case "archer" -> "Archer";
         case "tank" -> "Tank";
         case "rush" -> "Rush";
         case "builder" -> "Builder";
         case "berserker" -> "Berserker";
         case "mage" -> "Mage";
         default -> "Knight";
      };
   }

         private static String customMapKey(DuelMode mode) {
      return switch (mode.ordinal()) {
         case 0, 1, 2, 3, 4, 5, 6, 10, 11, 12, 13 -> "arena";
         case 7 -> "bedwars";
         case 8 -> "skywars";
         case 9 -> "arena";
         default -> null;
      };
   }

   private static DuelMode duelModeForMapKey(String mapKey) {
      if (mapKey == null) {
         return null;
      } else if (mapKey.equals("arena")) {
         return DuelMode.LEGACY;
      } else if (mapKey.equals("skywars")) {
         return DuelMode.SKYWARS;
      } else if (mapKey.equals("bedwars")) {
         return DuelMode.BEDWARS;
      } else {
         return mapKey.equals("luckypvp") ? DuelMode.LUCKYPvP : null;
      }
   }

   public static void premarkBedwars(int ox, int oz, BiConsumer<String, BlockPos> add) {
      int az = oz + 24;
      add.accept("p1spawn", new BlockPos(ox + 8, 100, az + 4));
      add.accept("p2spawn", new BlockPos(ox + 80, 100, az + 4));
      add.accept("bed", new BlockPos(ox + 19, 101, az + 2));
      add.accept("bed", new BlockPos(ox + 71, 101, az + 2));
      add.accept("gen_iron", new BlockPos(ox + 6, 99, az + 3));
      add.accept("gen_iron", new BlockPos(ox + 85, 99, az + 3));
      add.accept("gen_diamond", new BlockPos(ox + 43, 99, oz + 6));
      add.accept("gen_diamond", new BlockPos(ox + 46, 99, oz + 6));
      add.accept("gen_diamond", new BlockPos(ox + 43, 99, oz + 54));
      add.accept("gen_diamond", new BlockPos(ox + 46, 99, oz + 54));
      add.accept("gen_emerald", new BlockPos(ox + 39, 99, oz + 23));
      add.accept("gen_emerald", new BlockPos(ox + 47, 99, oz + 23));
      add.accept("gen_emerald", new BlockPos(ox + 43, 99, oz + 32));
      add.accept("shop", new BlockPos(ox + 4, 100, az + 8));
      add.accept("shop", new BlockPos(ox + 85, 100, az + 8));
      add.accept("upgrade", new BlockPos(ox + 10, 100, az + 9));
      add.accept("upgrade", new BlockPos(ox + 79, 100, az + 9));
      add.accept("chest", new BlockPos(ox + 14, 100, az - 8));
      add.accept("chest", new BlockPos(ox + 76, 100, az - 8));
   }

   public static void premarkSkywars(int ox, int oz, BiConsumer<String, BlockPos> add) {
      add.accept("p1spawn", new BlockPos(ox + 9, 100, oz + 9));
      add.accept("p2spawn", new BlockPos(ox + 68, 100, oz + 68));
      add.accept("chest_spawn", new BlockPos(ox + 8, 101, oz + 8));
      add.accept("chest_spawn", new BlockPos(ox + 67, 101, oz + 67));
      add.accept("chest_middle", new BlockPos(ox + 35, 101, oz + 35));
      add.accept("chest_middle", new BlockPos(ox + 42, 101, oz + 35));
      add.accept("chest_middle", new BlockPos(ox + 35, 101, oz + 42));
      add.accept("chest_middle", new BlockPos(ox + 42, 101, oz + 42));
   }

   public static void buildEditPreview(ServerLevel realm, String mapKey, int ox, int oz) {
      try {
         clearPlot(realm, ox, oz);
         DuelMode mode = duelModeForMapKey(mapKey);
         if (mode == null) {
            return;
         }

         Duel d = new Duel(mode, ox, oz);
         if (d.parts.isEmpty()) {
            Participant a = new Participant();
            a.slot = 0;
            Participant b = new Participant();
            b.slot = 1;
            d.parts.add(a);
            d.parts.add(b);
         }

         if (!tryBuildCustomMap(realm, d)) {
            buildArena(realm, d);
         }
      } catch (Exception e) {
         FortuneFavorsMod.LOGGER.error("Fortune & Favors: could not build the map-edit preview", e);
      }
   }

   private static boolean tryBuildCustomMap(ServerLevel realm, Duel d) {
      try {
         String key = customMapKey(d.mode);
         if (key == null) {
            return false;
         }

         CustomMap cm = MapEditor.customMapFor(key);
         if (cm != null && !cm.blocks.isEmpty()) {
            int dx = d.arena.ox + 50 - cm.centerX();
            int dz = d.arena.oz + 50 - cm.centerZ();
            Map<String, BlockState> cache = new HashMap<>();

            for (BlockData b : cm.blocks) {
               String stateKey = b.props() != null && !b.props().isEmpty() ? b.block() + b.props() : b.block();
               BlockState state = cache.computeIfAbsent(stateKey, k -> parseCustomBlockState(realm, b));
               if (state != null && !state.isAir()) {
                  realm.setBlock(new BlockPos(b.x() + dx, b.y(), b.z() + dz), state, 3);
               }
            }

            Arena a = d.arena;
            a.x0 = cm.minX + dx - 2;
            a.x1 = cm.maxX + dx + 2;
            a.z0 = cm.minZ + dz - 2;
            a.z1 = cm.maxZ + dz + 2;
            a.voidY = 95;
            applyCustomMarkers(realm, d, cm, dx, dz);
            return true;
         } else {
            return false;
         }
      } catch (Throwable t) {
         FortuneFavorsMod.LOGGER.error("Fortune & Favors: custom map {} failed to build - using procedural arena", customMapKey(d.mode), t);
         return false;
      }
   }

   private static BlockState parseCustomBlockState(ServerLevel realm, BlockData b) {
      try {
         Registry<Block> lookup = realm.registryAccess().lookupOrThrow(Registries.BLOCK);
         String s = b.block();
         if (b.props() != null && !b.props().isEmpty()) {
            StringBuilder sb = new StringBuilder(s).append("[");
            boolean first = true;

            for (Entry<String, String> e : b.props().entrySet()) {
               if (!first) {
                  sb.append(",");
               }

               sb.append(e.getKey()).append("=").append(e.getValue());
               first = false;
            }

            sb.append("]");
            s = sb.toString();
         }

         BlockResult res = BlockStateParser.parseForBlock(lookup, s, false);
         return res.blockState();
      } catch (Exception e) {
         return null;
      }
   }

   private static void applyCustomMarkers(ServerLevel realm, Duel d, CustomMap cm, int dx, int dz) {
      Arena a = d.arena;
      List<int[]> p1 = cm.markers.getOrDefault("p1spawn", List.of());
      List<int[]> p2 = cm.markers.getOrDefault("p2spawn", List.of());
      if (!p1.isEmpty()) {
         int[] m = p1.get(0);
         a.spawn0 = new double[]{m[0] + dx + 0.5, m[1] + 1, m[2] + dz + 0.5};
      }

      if (!p2.isEmpty()) {
         int[] m = p2.get(0);
         a.spawn1 = new double[]{m[0] + dx + 0.5, m[1] + 1, m[2] + dz + 0.5};
      }

      if (d.ffa) {
         int slot = 0;

         for (int[] m : p1) {
            a.extraSpawns.put(slot, new double[]{m[0] + dx + 0.5, m[1] + 1, m[2] + dz + 0.5});
            a.extraYaws.put(slot, 0.0F);
            slot++;
         }

         for (int[] m : p2) {
            a.extraSpawns.put(slot, new double[]{m[0] + dx + 0.5, m[1] + 1, m[2] + dz + 0.5});
            a.extraYaws.put(slot, 0.0F);
            slot++;
         }
      }

      List<int[]> beds = cm.markers.getOrDefault("bed", List.of());
      if (!beds.isEmpty()) {
         int[] m0 = beds.get(0);
         a.bed0 = new BlockPos(m0[0] + dx, m0[1], m0[2] + dz);
         a.bed0Head = bedHeadOf(realm, a.bed0);
      }

      if (beds.size() >= 2) {
         int[] m1 = beds.get(1);
         a.bed1 = new BlockPos(m1[0] + dx, m1[1], m1[2] + dz);
         a.bed1Head = bedHeadOf(realm, a.bed1);
      }

      for (int[] m : cm.markers.getOrDefault("gen_iron", List.of())) {
         a.forgeGens.add(new BlockPos(m[0] + dx, m[1], m[2] + dz));
      }

      for (int[] m : cm.markers.getOrDefault("gen_diamond", List.of())) {
         a.diamondGens.add(new BlockPos(m[0] + dx, m[1], m[2] + dz));
      }

      for (int[] m : cm.markers.getOrDefault("gen_emerald", List.of())) {
         a.emeraldGens.add(new BlockPos(m[0] + dx, m[1], m[2] + dz));
      }

      List<int[]> specSpawns = cm.markers.getOrDefault("spectator", List.of());
      if (!specSpawns.isEmpty()) {
         int[] m = specSpawns.get(0);
         a.spectatorSpawn = new double[]{m[0] + dx + 0.5, m[1] + 1, m[2] + dz + 0.5};
      }

      if (d.mode == DuelMode.BEDWARS) {
         List<int[]> chestMarks = cm.markers.getOrDefault("chest", List.of());
         if (!chestMarks.isEmpty()) {
            a.chest0 = new BlockPos(chestMarks.get(0)[0] + dx, chestMarks.get(0)[1], chestMarks.get(0)[2] + dz);
         }

         if (chestMarks.size() >= 2) {
            a.chest1 = new BlockPos(chestMarks.get(1)[0] + dx, chestMarks.get(1)[1], chestMarks.get(1)[2] + dz);
         }

         double[] s0 = a.spawn0 != null ? a.spawn0 : new double[]{a.ox + 10, 101.0, a.oz + 10};
         double[] s1 = a.spawn1 != null ? a.spawn1 : new double[]{a.ox + 60, 101.0, a.oz + 60};

         for (int[] m : cm.markers.getOrDefault("shop", List.of())) {
            boolean teamA = dist2(m[0] + dx, m[2] + dz, s0) <= dist2(m[0] + dx, m[2] + dz, s1);
            spawnVillagerAt(realm, d, m[0] + dx, m[1], m[2] + dz, teamA ? "shop0" : "shop1", "Item Shop", "§6");
         }

         for (int[] m : cm.markers.getOrDefault("upgrade", List.of())) {
            boolean teamA = dist2(m[0] + dx, m[2] + dz, s0) <= dist2(m[0] + dx, m[2] + dz, s1);
            spawnVillagerAt(realm, d, m[0] + dx, m[1], m[2] + dz, teamA ? "upgrade0" : "upgrade1", "Upgrades", "§b");
         }

         if (a.spawn0 != null && a.spawn1 != null) {
            a.yaw0 = (float)Math.toDegrees(Math.atan2(a.spawn1[0] - a.spawn0[0], a.spawn1[2] - a.spawn0[2]));
            a.yaw1 = (float)Math.toDegrees(Math.atan2(a.spawn0[0] - a.spawn1[0], a.spawn0[2] - a.spawn1[2]));
         }
      }

      if (d.mode == DuelMode.SKYWARS) {
         if (!p1.isEmpty()) {
            int[] m = p1.get(0);
            buildGlassChamber(realm, m[0] + dx, m[2] + dz);
            a.chambers.add(new int[]{m[0] + dx - 1, m[0] + dx + 1, m[2] + dz - 1, m[2] + dz + 1});
            a.lobbySpawn0 = new double[]{m[0] + dx + 0.5, 104.0, m[2] + dz + 0.5};
         }

         if (!p2.isEmpty()) {
            int[] m = p2.get(0);
            buildGlassChamber(realm, m[0] + dx, m[2] + dz);
            a.chambers.add(new int[]{m[0] + dx - 1, m[0] + dx + 1, m[2] + dz - 1, m[2] + dz + 1});
            a.lobbySpawn1 = new double[]{m[0] + dx + 0.5, 104.0, m[2] + dz + 0.5};
         }

         for (int[] m : cm.markers.getOrDefault("chest_spawn", List.of())) {
            BlockPos p = new BlockPos(m[0] + dx, m[1], m[2] + dz);
            a.chests.add(p);
            fillOrPlaceChest(realm, p, skywarsChest());
         }

         for (int[] m : cm.markers.getOrDefault("chest_middle", List.of())) {
            BlockPos p = new BlockPos(m[0] + dx, m[1], m[2] + dz);
            a.chests.add(p);
            a.refillChests.add(p);
            fillOrPlaceChest(realm, p, List.of());
         }
      }
   }

   private static void fillOrPlaceChest(ServerLevel realm, BlockPos pos, List<ItemStack> items) {
      if (realm.getBlockEntity(pos) instanceof ChestBlockEntity chest) {
         fillChest(chest, items);
      } else {
         placeChest(realm, pos, items);
      }
   }

   private static double dist2(double x, double z, double[] p) {
      double dx = x - p[0];
      double dz = z - p[2];
      return dx * dx + dz * dz;
   }

   private static BlockPos bedHeadOf(ServerLevel realm, BlockPos foot) {
      if (foot == null) {
         return null;
      } else {
         BlockState st = realm.getBlockState(foot);
         if (st.getBlock() instanceof BedBlock) {
            Direction facing = (Direction)st.getValue(BedBlock.FACING);
            return st.getValue(BedBlock.PART) == BedPart.HEAD ? foot : foot.relative(facing);
         } else {
            return null;
         }
      }
   }

   public static int claimPlot() {
      if (usedPlots.size() >= 64) {
         usedPlots.clear();
      }

      int plot;
      do {
         plot = (int)(Math.random() * 64.0);
      } while (usedPlots.contains(plot));

      usedPlots.add(plot);
      // The plot is about to be built on, so it is dirty until its arena is torn
      // down. Written now rather than at the next autosave: the file is what tells
      // the next boot which plot a crash left an arena on.
      markPlotDirty(plot);
      return plot;
   }

   public static void releasePlot(int ox, int oz) {
      int plot = plotOf(ox, oz);
      if (plot >= 0) {
         usedPlots.remove(plot);
      }
      // Note what is NOT here: the plot stays marked dirty. Releasing only means no
      // match owns it any more - the blocks come out later, in teardown batches that
      // run a few at a time, so a plot is clean only once the last of them has run
      // (see teardownArena). Clearing the mark here marked a plot clean while it was
      // still standing, and a crash in that window left an arena nobody would sweep.
   }

   /** The plot index an origin sits in, or -1 when the origin is off the grid. */
   public static int plotOf(int ox, int oz) {
      int plot = (oz - PLOT_ORIGIN) / PLOT_STRIDE * PLOT_COLS + (ox - PLOT_ORIGIN) / PLOT_STRIDE;
      return plot >= 0 && plot < 64 ? plot : -1;
   }

   /** The x of a plot's origin - the inverse of {@link #plotOf}, and the one the sweep walks. */
   public static int plotOriginX(int plot) {
      return PLOT_ORIGIN + plot % PLOT_COLS * PLOT_STRIDE;
   }

   /** The z of a plot's origin - the inverse of {@link #plotOf}, and the one the sweep walks. */
   public static int plotOriginZ(int plot) {
      return PLOT_ORIGIN + plot / PLOT_COLS * PLOT_STRIDE;
   }

   private static void markPlotDirty(int plot) {
      if (dirtyPlots.add(plot)) {
         saveSweepMarkers();
      }
   }

   private static void markPlotClean(int plot) {
      if (dirtyPlots.remove(plot)) {
         saveSweepMarkers();
      }
   }

   private static void saveSweepMarkers() {
      if (sweepFile == null) {
         return;
      }
      try {
         JsonObject root = new JsonObject();
         JsonArray arr = new JsonArray();
         for (int plot : dirtyPlots) {
            arr.add(plot);
         }

         root.add("dirty_plots", arr);
         JsonUtil.write(sweepFile, root);
      } catch (Throwable t) {
         FortuneFavorsMod.LOGGER.error("Fortune & Favors: could not save the duel sweep markers", t);
      }
   }

   /**
    * The plots a boot is allowed to sweep, in order.
    *
    * <p>An empty answer is the point of the whole thing: an ordinary boot has no
    * leftover arena anywhere, so it must not load a single chunk of the realm. A plot
    * somebody is standing in - a match already running - is never swept either.
    */
   public static List<Integer> bootSweepPlan(Set<Integer> dirty, Set<Integer> inUse) {
      List<Integer> plan = new ArrayList<>();
      if (dirty != null) {
         for (Integer plot : dirty) {
            if (plot != null && plot >= 0 && plot < 64 && (inUse == null || !inUse.contains(plot))) {
               plan.add(plot);
            }
         }
      }

      java.util.Collections.sort(plan);
      return plan;
   }

   /**
    * How many chunks a realm sweep may clear this tick, given how far behind the server is.
    *
    * <p>A sweep is background tidying and a struggling server is a live one, so the tidying is
    * what gives way: past {@link #SWEEP_SLOW_MSPT} it halves, past {@link #SWEEP_CRAWL_MSPT} it
    * crawls at one chunk a tick. It never reaches zero, because a sweep that stops entirely is
    * a sweep that never finishes, and the plot it is holding stays half-cleared.
    */
   public static int sweepChunkBudget(int wanted, double mspt, boolean msptKnown) {
      if (wanted <= 0) {
         return 0;
      }

      if (!msptKnown) {
         return wanted;
      }

      if (mspt >= SWEEP_CRAWL_MSPT) {
         return 1;
      }

      if (mspt >= SWEEP_SLOW_MSPT) {
         return Math.max(1, wanted / 2);
      }

      return wanted;
   }

   /** The chunk budget the sweeps use right now, read off the server's own clock. */
   private static int sweepChunkBudget(int wanted) {
      return sweepChunkBudget(wanted, PerfMonitor.mspt(), PerfMonitor.msptAvailable());
   }

   /**
    * Sweeps the whole extended gladiator plot.
    *
    * <p>The shared {@link #clearPlot} is sized for the standard arena, which is
    * narrower than a gladiator world and cleared all the way up to the height a
    * spectator stands at. A gladiator map only ever occupies the bottom of that
    * column - bedrock, terrain, and trees no taller than twelve or so - so this
    * takes the extra width the map needs and nothing else, which keeps a map twice
    * the width from costing three times the build.
    */
   private static void clearGladiatorPlot(ServerLevel realm, int ox, int oz, int w, int h) {
      int x0 = ox - 6;
      int x1 = ox + w + 6;
      int z0 = oz - 6;
      int z1 = oz + h + 6;

      for (int x = x0; x <= x1; x++) {
         for (int z = z0; z <= z1; z++) {
            for (int y = 98; y <= 122; y++) {
               realm.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 2);
            }
         }
      }

      AABB box = new AABB(x0, 96.0, z0, x1, 140.0, z1);

      for (ItemEntity e : realm.getEntitiesOfClass(ItemEntity.class, box)) {
         e.discard();
      }

      for (Projectile pr : realm.getEntitiesOfClass(Projectile.class, box)) {
         pr.discard();
      }

      for (ExperienceOrb orb : realm.getEntitiesOfClass(ExperienceOrb.class, box)) {
         orb.discard();
      }

      for (Villager v : realm.getEntitiesOfClass(Villager.class, box)) {
         v.discard();
      }
   }

   private static void clearPlot(ServerLevel realm, int ox, int oz) {
      int x0 = ox - 6;
      int x1 = ox + 96;
      int z0 = oz - 6;
      int z1 = oz + 76;

      for (int x = x0; x <= x1; x++) {
         for (int z = z0; z <= z1; z++) {
            for (int y = 98; y <= 164; y++) {
               realm.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 3);
            }
         }
      }

      AABB box = new AABB(x0, 96.0, z0, x1, 164.0, z1);

      for (ItemEntity e : realm.getEntitiesOfClass(ItemEntity.class, box)) {
         e.discard();
      }

      for (Projectile pr : realm.getEntitiesOfClass(Projectile.class, box)) {
         pr.discard();
      }

      for (ExperienceOrb orb : realm.getEntitiesOfClass(ExperienceOrb.class, box)) {
         orb.discard();
      }

      for (Villager v : realm.getEntitiesOfClass(Villager.class, box)) {
         v.discard();
      }
   }

         private static void buildArena(ServerLevel realm, Duel d) {
      Arena a = d.arena;
      // Gladiator is a world rather than an arena, so it is loaded and swept to
      // its own size. Everything else shares the standard footprint.
      boolean gladiator = d.mode == DuelMode.GLADIATOR;
      int spanX = gladiator ? GLADIATOR_W + 8 : 96;
      int spanZ = gladiator ? GLADIATOR_H + 8 : 80;
      int chunkX0 = (a.ox - 8) >> 4;
      int chunkZ0 = (a.oz - 8) >> 4;
      int chunkX1 = (a.ox + spanX) >> 4;
      int chunkZ1 = (a.oz + spanZ) >> 4;

      for (int cx = chunkX0; cx <= chunkX1; cx++) {
         for (int cz = chunkZ0; cz <= chunkZ1; cz++) {
            realm.getChunkAt(new BlockPos(cx << 4, 100, cz << 4));
         }
      }

      if (gladiator) {
         clearGladiatorPlot(realm, a.ox, a.oz, GLADIATOR_W, GLADIATOR_H);
      } else {
         clearPlot(realm, a.ox, a.oz);
      }
      if (!tryBuildCustomMap(realm, d)) {
         switch (d.mode.ordinal()) {
            case 0:
            case 1:
            case 2:
            case 3:
            case 4:
            case 5:
            case 6:
            case 9:
            case 10:
            case 11:
            case 12:
            case 13:
               buildArenaDuel(realm, a);
               break;
            case 7:
               buildBedwars(realm, d);
               break;
            case 8:
               buildSkywars(realm, d);
               break;
            case 14:
               buildTntrun(realm, a);
               break;
            case 15:
               // Sumo needs a bare platform: the barrier walls the other arenas
               // ring themselves with would make a ring-out impossible.
               buildSumo(realm, a);
               break;
            case 16:
               buildArchery(realm, d);
               break;
            case 17:
               buildGladiator(realm, d);
               break;
         }
      }

      if (d.mode == DuelMode.COMBODUEL) {
         buildComboWalls(realm, a);
      }

      sweepStrayVillagers(realm, d);
      if (a.spectatorSpawn == null) {
         a.spectatorSpawn = new double[]{a.ox + 50.5, 130.0, a.oz + 50.5};
      }

      // The floor as it left the builder, taken here rather than when someone
      // asks, because the useful question later is what it was - not what it
      // has become since. A failure to record it is never allowed to refuse a
      // match: the arena is built and playable either way.
      Safe.run("arena floor digest", () -> captureFloor(realm, d));
   }

   private static void buildComboWalls(ServerLevel realm, Arena a) {
      int x0 = a.x0 - 1;
      int x1 = a.x1 + 1;
      int z0 = a.z0 - 1;
      int z1 = a.z1 + 1;
      BlockState wall = Blocks.STONE_BRICKS.defaultBlockState();

      for (int i = x0; i <= x1; i++) {
         realm.setBlock(new BlockPos(i, 101, z0), wall, 3);
         realm.setBlock(new BlockPos(i, 102, z0), wall, 3);
         realm.setBlock(new BlockPos(i, 101, z1), wall, 3);
         realm.setBlock(new BlockPos(i, 102, z1), wall, 3);
      }

      for (int i = z0; i <= z1; i++) {
         realm.setBlock(new BlockPos(x0, 101, i), wall, 3);
         realm.setBlock(new BlockPos(x0, 102, i), wall, 3);
         realm.setBlock(new BlockPos(x1, 101, i), wall, 3);
         realm.setBlock(new BlockPos(x1, 102, i), wall, 3);
      }
   }

   private static void sweepStrayVillagers(ServerLevel realm, Duel d) {
      int spanX = d.mode == DuelMode.GLADIATOR ? GLADIATOR_W + 8 : 100;
      int spanZ = d.mode == DuelMode.GLADIATOR ? GLADIATOR_H + 8 : 80;
      AABB box = new AABB(d.arena.ox - 8, 96.0, d.arena.oz - 8, d.arena.ox + spanX, 108.0, d.arena.oz + spanZ);

      for (Villager v : realm.getEntitiesOfClass(Villager.class, box)) {
         if (!d.villagerRoles.containsKey(v.getUUID())) {
            v.discard();
         }
      }
   }

   private static void buildArenaDuel(ServerLevel realm, Arena a) {
      int ox = a.ox;
      int oz = a.oz;
      int size = a.ffaCount >= 3 ? Math.min(64 + (a.ffaCount - 2) * 12, 128) : 64;

      for (int x = 0; x < size; x++) {
         for (int z = 0; z < size; z++) {
            realm.setBlock(new BlockPos(ox + x, 100, oz + z), Blocks.STONE.defaultBlockState(), 3);
         }
      }

      for (int i = -1; i <= size; i++) {
         setBarrier(realm, ox + i, oz - 1);
         setBarrier(realm, ox + i, oz + size);
         setBarrier(realm, ox - 1, oz + i);
         setBarrier(realm, ox + size, oz + i);
      }

      setBlock(realm, ox + 8, 100, oz + 8, (Block)Blocks.WOOL.red());
      setBlock(realm, ox + size - 9, 100, oz + size - 9, (Block)Blocks.WOOL.blue());
      if (a.ffaCount >= 3) {
         int mid = size / 2;

         for (int i = -1; i <= 1; i++) {
            for (int j = -1; j <= 1; j++) {
               realm.setBlock(new BlockPos(ox + mid + i, 100, oz + mid + j), ((Block)Blocks.WOOL.yellow()).defaultBlockState(), 3);
            }
         }
      }

      a.x0 = ox;
      a.x1 = ox + size - 1;
      a.z0 = oz;
      a.z1 = oz + size - 1;
      a.voidY = 99;
      a.near = 9.5;
      a.far = size - 9.5;
   }

   // ---------------------------------------------------------------- gladiator

   /**
    * The distance between two plots, and how many fit in a row.
    *
    * <p>Was a bare `200` written out in five places, which is why the Gladiator map was
    * capped at 184 wide: the sweep margin had to fit inside a plot while the plot next door
    * started two hundred blocks away. The Gladiator map is the one arena that reaches its
    * plot's edge and the one mode where a bigger map is a more interesting match rather than
    * a longer walk, so the stride is a named number now and the map grows with it.
    *
    * <p>Only the Gladiator map uses the space; every other arena is a fraction of it and is
    * unaffected except by the spacing.
    */
   private static final int PLOT_STRIDE = 256;
   private static final int PLOT_COLS = 8;
   /** Where a plot's origin sits inside its stride. */
   private static final int PLOT_ORIGIN = 16;

   /**
    * A Gladiator world's size.
    *
    * <p>The realm hands every match a plot {@link #PLOT_STRIDE} blocks across - the chunk
    * loader, the plot sweeper and the neighbour's arena all work off that spacing - so the
    * map and the six-block sweep margin around it have to fit inside one stride: 240 wide
    * and 232 deep leaves the sweep at 252 and still clears the plot next door. It is
    * genuinely wide now: ground to walk on, rock to dig, trees for cover and caches to race
    * for, with the middle caches and the rim caches a very long walk apart - and no wings to
    * shorten that walk, which is what makes the far rim a decision rather than a trip.
    */
   private static final int GLADIATOR_W = 240;
   private static final int GLADIATOR_H = 232;
   /**
    * What the far-rim cache hands out instead of an elytra: pearls, all of them thrown by hand.
    *
    * <p>Three is the number that buys one escape and one chase, and not a way of life: a pearl
    * costs half a heart and puts you wherever you are looking, so what it buys is distance the
    * map still had to be crossed for.
    */
   private static final int GLADIATOR_ESCAPE_PEARLS = 3;
   /**
    * How long the fighters gear up before the hunt starts lighting them up.
    *
    * <p>Two and a half minutes, up from ninety seconds. The map is larger and its
    * best ore is now thirty blocks down, so the gearing window has to be long enough
    * to walk to a hillside, dig a shaft and come back with something - otherwise the
    * hunt opens on people who are still holding a stone pickaxe.
    */
   private static final int GLADIATOR_QUIET_TICKS = 20 * 180;
   /**
    * How deep the rock under the map goes.
    *
    * <p>The map used to be a shell: three or four blocks of stone lying straight on
    * its bedrock, so every ore in the world was visible from the surface and a pickaxe
    * was a formality. Now the rock runs from here to the grass, and what is in it
    * depends on how far down it is.
    */
   private static final int GLADIATOR_FLOOR_Y = 52;
   /** Below this line the rock is deepslate - and so are the ores inside it. */
   private static final int GLADIATOR_SLATE_Y = 78;

   /**
    * What a deep vein is made of, at a given depth.
    *
    * <p>{@code depth} is 0 at the bedrock and 1 just under the skin. The table is
    * written the wrong way round on purpose: a fighter who digs thirty blocks and
    * finds diamond has learned something about the map, and one who finds coal nine
    * blocks under the grass has learned not to dig there. Deepslate carries deepslate
    * variants of the same ores, so the rock and the loot always agree.
    */
   private static Block gladOre(java.util.Random rng, double depth, int y) {
      boolean slate = y < GLADIATOR_SLATE_Y;
      if (depth > 0.72) {
         return slate
            ? gladPick(rng, Blocks.DEEPSLATE_COAL_ORE, Blocks.DEEPSLATE_IRON_ORE, Blocks.DEEPSLATE_COPPER_ORE, Blocks.DEEPSLATE_COAL_ORE)
            : gladPick(rng, Blocks.COAL_ORE, Blocks.IRON_ORE, Blocks.COPPER_ORE, Blocks.COAL_ORE);
      }
      if (depth > 0.4) {
         return slate
            ? gladPick(
               rng,
               Blocks.DEEPSLATE_IRON_ORE,
               Blocks.DEEPSLATE_GOLD_ORE,
               Blocks.DEEPSLATE_REDSTONE_ORE,
               Blocks.DEEPSLATE_LAPIS_ORE,
               Blocks.DEEPSLATE_COAL_ORE
            )
            : gladPick(rng, Blocks.IRON_ORE, Blocks.GOLD_ORE, Blocks.REDSTONE_ORE, Blocks.LAPIS_ORE, Blocks.COAL_ORE);
      }
      return slate
         ? gladPick(
            rng,
            Blocks.DEEPSLATE_DIAMOND_ORE,
            Blocks.DEEPSLATE_EMERALD_ORE,
            Blocks.DEEPSLATE_GOLD_ORE,
            Blocks.DEEPSLATE_LAPIS_ORE,
            Blocks.DEEPSLATE_REDSTONE_ORE,
            Blocks.DEEPSLATE_IRON_ORE
         )
         : gladPick(rng, Blocks.DIAMOND_ORE, Blocks.EMERALD_ORE, Blocks.GOLD_ORE, Blocks.LAPIS_ORE, Blocks.REDSTONE_ORE, Blocks.IRON_ORE);
   }

   private static Block gladPick(java.util.Random rng, Block... options) {
      return options[rng.nextInt(options.length)];
   }

   // ----------------------------------------------------------- gladiator hooks
   // A self-test cannot build a 184x172 world on demand, so what it checks instead is
   // the shape of the rules that decide what that world is: how long the truce lasts,
   // how deep the rock goes, and whether digging actually pays.

   /** How long the gearing window lasts, in ticks. */
   public static int gladiatorQuietTicks() {
      return GLADIATOR_QUIET_TICKS;
   }

   /** The map's size, and where its rock starts. */
   public static int gladiatorWidth() {
      return GLADIATOR_W;
   }

   public static int gladiatorHeight() {
      return GLADIATOR_H;
   }

   public static int gladiatorFloorY() {
      return GLADIATOR_FLOOR_Y;
   }

   /** The distance between two plots, which is the ceiling on how big the map can be. */
   public static int plotStride() {
      return PLOT_STRIDE;
   }

   /**
    * The Gladiator mode's icon, in one place, because it appears in three menus.
    *
    * <p>A stick, and not a pickaxe. The pickaxe was the icon of the *reward* - the tool you
    * go and earn - which read as "this mode is about mining"; what the mode actually hands you
    * is nothing at all, in a world of stone, and the first thing anybody in it holds is a
    * stick picked up off the ground. It also stops the mode's icon colliding with the Mining
    * skills, the prison mine and the shop's own pickaxe rows, which is where it came from.
    */
   public static net.minecraft.world.item.Item gladiatorIcon() {
      return net.minecraft.world.item.Items.STICK;
   }

   /**
    * Is the gearing truce still holding, given when the hunt opens and the clock now?
    *
    * <p>Pure on purpose. This is the rule that says a blow between two fighters does
    * nothing during the gear-up window, and a rule that can only be checked by staging
    * a live match is a rule that ends up being checked by playing one.
    */
   public static boolean gladiatorTruce(long huntOpensAt, long now) {
      return huntOpensAt < 0L || now < huntOpensAt;
   }

   /**
    * What the deep ore table can produce at a given depth, sampled.
    *
    * <p>`depth` is 0 at the bedrock and 1 just under the skin. The suite uses this to
    * assert the thing the deep pass exists for: that the bottom of the map carries ore
    * the top of it does not.
    */
   public static java.util.Set<String> sampledOres(double depth, int y, int samples) {
      java.util.Set<String> out = new java.util.HashSet<>();
      java.util.Random rng = new java.util.Random(20240514L);
      for (int i = 0; i < samples; i++) {
         Block ore = gladOre(rng, depth, y);
         out.add(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(ore).getPath());
      }
      return out;
   }

   /** A gentle, seed-stable rise for a column of the gladiator map. */
   private static int gladLift(long seed, int x, int z) {
      double n = Math.sin(x * 0.13 + seed * 0.001)
         + Math.cos(z * 0.11 - seed * 0.002)
         + Math.sin((x + z) * 0.07 + seed * 0.003);
      return (int)Math.round((n + 3.0) / 2.0);
   }

   /**
    * Builds the Gladiator world.
    *
    * <p>The mode used to be a bare stone square with a sword and two apples handed
    * to you, which is the opposite of what its name promises. Now a match starts
    * with nothing in your hands in the middle of an open field: the ore is in the
    * ground, the chests are on the surface - a cache in the middle and caches
    * scattered out to the rim, better the further you travel - and the gear is
    * something you go and dig up or race for. After a grace period nobody can win
    * by hiding, because the fighters start glowing.
    */
   private static void buildGladiator(ServerLevel realm, Duel d) {
      Arena a = d.arena;
      int ox = a.ox;
      int oz = a.oz;
      int w = GLADIATOR_W;
      int h = GLADIATOR_H;
      long seed = d.botSeed;
      int floorY = GLADIATOR_FLOOR_Y;
      int baseY = 100;
      java.util.Random rng = new java.util.Random(seed);

      // A bedrock bottom, so this is a place with a floor rather than a hole into
      // the duel realm's void. It is also the one block no fighter can dig out
      // from under themselves.
      for (int x = -1; x <= w; x++) {
         for (int z = -1; z <= h; z++) {
            realm.setBlock(new BlockPos(ox + x, floorY, oz + z), Blocks.BEDROCK.defaultBlockState(), 3);
         }
      }

      // Terrain: dirt and grass over stone, with gentle rises. The two open layers
      // go in with block updates so the surface lights correctly; the rock under
      // them does not need to, and skipping its updates is what keeps a map this
      // size from stalling the build.
      int[][] lift = new int[w][h];
      for (int x = 0; x < w; x++) {
         for (int z = 0; z < h; z++) {
            int top = baseY + gladLift(seed, x, z);
            lift[x][z] = top - baseY;
            realm.setBlock(new BlockPos(ox + x, top, oz + z), Blocks.GRASS_BLOCK.defaultBlockState(), 3);
            realm.setBlock(new BlockPos(ox + x, top - 1, oz + z), Blocks.DIRT.defaultBlockState(), 3);
            for (int y = floorY + 1; y <= top - 2; y++) {
               // Deepslate in the bottom half. The change of rock is what tells a
               // fighter they are deep without a coordinate readout, and the ore table
               // below changes with it.
               realm.setBlock(
                  new BlockPos(ox + x, y, oz + z),
                  (y < GLADIATOR_SLATE_Y ? Blocks.DEEPSLATE : Blocks.STONE).defaultBlockState(),
                  2
               );
            }
         }
      }

      // Ore, in two passes, because they answer two different questions.
      //
      // The shallow pass is the hint: veins whose top blocks land in the grass or the
      // dirt under it, so a seam is visible from the surface and a fighter who has not
      // found a pickaxe yet can still see which way to dig.
      Block[] shallowOres = new Block[]{
         Blocks.COAL_ORE,
         Blocks.COAL_ORE,
         Blocks.IRON_ORE,
         Blocks.IRON_ORE,
         Blocks.COPPER_ORE,
         Blocks.GOLD_ORE,
         Blocks.REDSTONE_ORE
      };
      for (int vein = 0; vein < 240; vein++) {
         int vx = rng.nextInt(w);
         int vz = rng.nextInt(h);
         if (Math.abs(vx - w / 2) <= 3 && Math.abs(vz - h / 2) <= 3) {
            continue;
         }
         Block ore = shallowOres[rng.nextInt(shallowOres.length)];
         int top = baseY + lift[vx][vz] - 1;
         int size = 2 + rng.nextInt(4);
         for (int i = 0; i < size; i++) {
            int x = vx + rng.nextInt(3) - 1;
            int z = vz + rng.nextInt(3) - 1;
            int y = top - rng.nextInt(3);
            if (x < 0 || x >= w || z < 0 || z >= h || y <= floorY) {
               continue;
            }
            BlockPos pos = new BlockPos(ox + x, y, oz + z);
            BlockState here = realm.getBlockState(pos);
            if (here.is(Blocks.STONE) || here.is(Blocks.DIRT) || here.is(Blocks.GRASS_BLOCK)) {
               realm.setBlock(pos, ore.defaultBlockState(), 3);
            }
         }
      }

      // The deep pass is the mine. Every vein lands somewhere in the column between
      // the bedrock and six blocks under the skin, and what it is depends on how deep
      // it is - coal and copper near the top of the rock, iron and gold through the
      // middle, and the things actually worth a shaft (redstone, lapis, diamond,
      // emerald) only in the bottom half.
      for (int vein = 0; vein < 560; vein++) {
         int vx = rng.nextInt(w);
         int vz = rng.nextInt(h);
         if (Math.abs(vx - w / 2) <= 2 && Math.abs(vz - h / 2) <= 2) {
            continue;
         }
         int top = baseY + lift[vx][vz];
         int low = floorY + 2;
         int high = top - 6;
         if (high <= low) {
            continue;
         }
         int vy = low + rng.nextInt(high - low);
         double depth = (double)(vy - low) / Math.max(1, high - low);
         Block ore = gladOre(rng, depth, vy);
         int size = 4 + rng.nextInt(6);
         for (int i = 0; i < size; i++) {
            int x = vx + rng.nextInt(3) - 1;
            int z = vz + rng.nextInt(3) - 1;
            int y = vy + rng.nextInt(3) - 1;
            if (x < 0 || x >= w || z < 0 || z >= h || y <= floorY || y >= top) {
               continue;
            }
            BlockPos pos = new BlockPos(ox + x, y, oz + z);
            BlockState here = realm.getBlockState(pos);
            if (here.is(Blocks.STONE) || here.is(Blocks.DEEPSLATE) || here.is(Blocks.DIRT) || here.is(Blocks.GRASS_BLOCK)) {
               realm.setBlock(pos, ore.defaultBlockState(), 2);
            }
         }
      }

      // Trees, for cover and for the wood a fighter may actually want.
      for (int tree = 0; tree < 26; tree++) {
         int tx = 3 + rng.nextInt(w - 6);
         int tz = 3 + rng.nextInt(h - 6);
         if (Math.abs(tx - w / 2) <= 5 && Math.abs(tz - h / 2) <= 5) {
            continue;
         }
         int top = baseY + lift[tx][tz];
         int trunk = 4 + rng.nextInt(3);
         for (int i = 1; i <= trunk; i++) {
            realm.setBlock(new BlockPos(ox + tx, top + i, oz + tz), Blocks.OAK_LOG.defaultBlockState(), 3);
         }
         for (int ly = trunk - 2; ly <= trunk + 1; ly++) {
            int radius = ly >= trunk ? 1 : 2;
            for (int lx = -radius; lx <= radius; lx++) {
               for (int lz = -radius; lz <= radius; lz++) {
                  if (Math.abs(lx) == radius && Math.abs(lz) == radius && rng.nextBoolean()) {
                     continue;
                  }
                  BlockPos leaf = new BlockPos(ox + tx + lx, top + ly, oz + tz + lz);
                  if (realm.getBlockState(leaf).isAir()) {
                     realm.setBlock(leaf, Blocks.OAK_LEAVES.defaultBlockState(), 3);
                  }
               }
            }
         }
      }

      // A flat pad in the middle, because the spawn is there and a fighter who
      // drops into a hillside on the first tick has already lost something.
      int cx = ox + w / 2;
      int cz = oz + h / 2;
      for (int x = -4; x <= 4; x++) {
         for (int z = -4; z <= 4; z++) {
            // Take the rise down first. The terrain pass above put grass wherever the
            // lift wanted it - up to three blocks higher than the pad - and this loop
            // only ever wrote *below* its own level, so on any column the hill was
            // taller than the pad the spawn ended up inside the hill. That is what
            // "I spawn in the ground" was.
            for (int y = baseY + 1; y <= baseY + 6; y++) {
               realm.setBlock(new BlockPos(cx + x, y, cz + z), Blocks.AIR.defaultBlockState(), 3);
            }

            realm.setBlock(new BlockPos(cx + x, baseY, cz + z), Blocks.GRASS_BLOCK.defaultBlockState(), 3);
            for (int y = baseY - 1; y > floorY; y--) {
               Block fill = y >= baseY - 2 ? Blocks.DIRT : (y < GLADIATOR_SLATE_Y ? Blocks.DEEPSLATE : Blocks.STONE);
               realm.setBlock(new BlockPos(cx + x, y, cz + z), fill.defaultBlockState(), 2);
            }
         }
      }

      // The caches. One cluster in the middle - the low-tier gear everyone can
      // reach - and the rest thrown out across the field, where how good a chest
      // is scales with how far from the centre it had to be carried.
      for (int i = 0; i < 6; i++) {
         double angle = i * (Math.PI * 2.0 / 6.0);
         int dx = (int)Math.round(Math.cos(angle) * 2.5);
         int dz = (int)Math.round(Math.sin(angle) * 2.5);
         BlockPos pos = new BlockPos(cx + dx, baseY + 1, cz + dz);
         placeChest(realm, pos, gladiatorChest(0));
         a.chests.add(pos);
      }

      // Fifty-two caches out in the field. A bigger map with the same number of chests is a
      // map where the loot is always in the same three places; this keeps the density roughly
      // what it was while pushing the distance between them up again, and the top tier - the
      // one that used to carry wings - is only on the far rim, which is now further out.
      for (int i = 0; i < 52; i++) {
         int x = 2 + rng.nextInt(w - 4);
         int z = 2 + rng.nextInt(h - 4);
         if (Math.abs(x - w / 2) <= 3 && Math.abs(z - h / 2) <= 3) {
            continue;
         }
         double half = Math.min(w, h) / 2.0;
         double fromCentre = Math.max(Math.abs(x - w / 2), Math.abs(z - h / 2));
         int tier = fromCentre > half * 0.82 ? 3 : (fromCentre > half * 0.6 ? 2 : (fromCentre > half * 0.3 ? 1 : 0));
         BlockPos pos = new BlockPos(ox + x, baseY + lift[x][z] + 1, oz + z);
         placeChest(realm, pos, gladiatorChest(tier));
         a.chests.add(pos);
      }

      // Some of the loot is underground, where the digging is: a sealed pocket cache
      // at the bottom of a column, so a fighter who commits to a shaft is paid for it
      // and not only for the ore.
      for (int i = 0; i < 12; i++) {
         int x = 4 + rng.nextInt(w - 8);
         int z = 4 + rng.nextInt(h - 8);
         int y = GLADIATOR_FLOOR_Y + 5 + rng.nextInt(16);
         // A carved pocket rather than a chest buried in solid rock: a fighter who
         // digs a shaft into this one drops into a small vault, which is the moment
         // the digging was for.
         for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
               for (int dy = -1; dy <= 1; dy++) {
                  realm.setBlock(new BlockPos(ox + x + dx, y + dy, oz + z + dz), Blocks.AIR.defaultBlockState(), 2);
               }
            }
         }
         BlockPos pos = new BlockPos(ox + x, y, oz + z);
         placeChest(realm, pos, gladiatorChest(i % 3 == 0 ? 3 : 2));
         a.chests.add(pos);
      }

      a.x0 = ox;
      a.x1 = ox + w - 1;
      a.z0 = oz;
      a.z1 = oz + h - 1;
      // The bedrock floor is the bottom, and the out-of-bounds line sits below it -
      // a fighter digging at the bottom of the map is not "outside the arena", and
      // this line used to be at 96, which is what put the deep rock off limits.
      a.voidY = GLADIATOR_FLOOR_Y - 4;
      a.near = 6.0;
      a.far = w - 6.0;
      a.spreadRadius = Math.min(w, h) / 2.0 - 6.0;
      a.spawn0 = new double[]{cx - 3.5, baseY + 1.0, cz + 0.5};
      a.spawn1 = new double[]{cx + 3.5, baseY + 1.0, cz + 0.5};
      a.yaw0 = 90.0F;
      a.yaw1 = -90.0F;
      a.spectatorSpawn = new double[]{cx + 0.5, baseY + 26.0, cz + 0.5};
   }

   /**
    * The surface of a gladiator column, for putting a fighter on it.
    *
    * <p>Leaves and trunks are skipped on the way down, because landing on a treetop
    * is how a spawn ends up twenty blocks above the fight.
    */
   private static double gladSpawnY(ServerLevel realm, int x, int z) {
      for (int y = 108; y >= 98; y--) {
         BlockPos p = new BlockPos(x, y, z);
         BlockState st = realm.getBlockState(p);
         if (st.isAir() || st.is(Blocks.OAK_LEAVES) || st.is(Blocks.OAK_LOG)) {
            continue;
         }

         double top = st.getCollisionShape(realm, p).max(Axis.Y);
         if (top > 0.0) {
            return y + top;
         }
      }

      return 101.0;
   }

   /** A potion stack of a given kind, built the way the kits build theirs. */
   private static ItemStack gladPotion(Item item, Holder<Potion> potion) {
      ItemStack s = new ItemStack(item);
      s.set(DataComponents.POTION_CONTENTS, PotionContents.EMPTY.withPotion(potion));
      return s;
   }

   /**
    * What a Gladiator cache hands out where an elytra used to be: an escape with a price.
    *
    * <p>An elytra was never really an item in this mode, it was a rewrite of it. The map is
    * walked, and walking it *is* the fight: with wings, the fighter losing any exchange simply
    * leaves it, reaches the far caches first, and crosses the arena in a straight line while
    * everyone else goes around a hill - the map stops being an opponent. The one-durability
    * version was the compromise, and a compromise is still the item that answers every question
    * the map asks.
    *
    * <p>What replaces it is a burst of distance you have to aim and a fall you have to have
    * thought about: ender pearls, and a slow-falling splash in the far-rim cache so the cliff a
    * pearl throws you off is survivable but never free. Both are things the map is still in the
    * way of, which is the whole point - an escape should cost the ground you were standing on,
    * not the ground between you and them.
    */
   private static ItemStack gladEscape() {
      return new ItemStack(Items.ENDER_PEARL, GLADIATOR_ESCAPE_PEARLS);
   }

   /**
    * A Gladiator cache.
    *
    * <p>The further from the middle, the better the gear - that is the reason to
    * cross the map. Each cache also draws a handful of extras from its own tier's
    * pool, so two caches of the same tier are two different caches: a shield and a
    * battleaxe here, a bow and a stack of arrows there, a pearl, a strength splash,
    * a bucket of water for the fall you are about to take.
    */
   /**
    * One Gladiator cache, for the self-test.
    *
    * <p>The mode's caches are the one place in the mod that could still hand out wings, and the
    * rule that they do not is a rule about the *contents* of a chest, so it is checked against
    * the real contents of every tier rather than against the source of one line.
    */
   public static List<ItemStack> gladiatorChestForTest(int tier) {
      return gladiatorChest(tier);
   }

   private static List<ItemStack> gladiatorChest(int tier) {
      List<ItemStack> items = new ArrayList<>();
      java.util.Random rng = new java.util.Random();
      switch (tier) {
         case 0 -> {
            items.add(new ItemStack(rng.nextBoolean() ? Items.STONE_SWORD : Items.STONE_AXE));
            items.add(new ItemStack(rng.nextBoolean() ? Items.LEATHER_CHESTPLATE : Items.LEATHER_HELMET));
            items.add(new ItemStack(Items.BREAD, 3 + rng.nextInt(3)));
         }
         case 1 -> {
            items.add(new ItemStack(rng.nextBoolean() ? Items.IRON_SWORD : Items.IRON_AXE));
            items.add(new ItemStack(Items.IRON_CHESTPLATE));
            items.add(new ItemStack(rng.nextBoolean() ? Items.IRON_HELMET : Items.IRON_LEGGINGS));
            items.add(new ItemStack(Items.COOKED_BEEF, 4 + rng.nextInt(3)));
         }
         case 2 -> {
            items.add(new ItemStack(rng.nextBoolean() ? Items.DIAMOND_SWORD : Items.DIAMOND_AXE));
            items.add(new ItemStack(Items.DIAMOND_CHESTPLATE));
            items.add(new ItemStack(Items.DIAMOND_HELMET));
            items.add(new ItemStack(Items.GOLDEN_APPLE, 1 + rng.nextInt(2)));
         }
         default -> {
            // The far rim. These are the caches a fighter walks two minutes for, so they are
            // the only ones in the map worth crossing it: a full diamond set, and one of the
            // two things that end a fight on their own - the mace, or the escape.
            items.add(new ItemStack(Items.DIAMOND_SWORD));
            items.add(new ItemStack(Items.DIAMOND_CHESTPLATE));
            items.add(new ItemStack(Items.DIAMOND_LEGGINGS));
            items.add(new ItemStack(Items.DIAMOND_BOOTS));
            items.add(new ItemStack(Items.ENCHANTED_GOLDEN_APPLE, 1));
            items.add(rng.nextBoolean() ? new ItemStack(Items.MACE) : gladEscape());
         }
      }

      // The tier's own pool of extras. Everything in here is something a bot can
      // actually use - it drinks, throws, blocks, bridges, eats and mines with what
      // it finds, so a cache full of curiosities is not a cache full of dead weight.
      ItemStack[] pool = switch (tier) {
         case 0 -> new ItemStack[]{
            new ItemStack(Items.OAK_PLANKS, 8 + rng.nextInt(9)),
            new ItemStack(Items.COBBLESTONE, 8 + rng.nextInt(9)),
            new ItemStack(Items.BREAD, 2),
            new ItemStack(Items.STICK, 4),
            new ItemStack(Items.IRON_INGOT, 1 + rng.nextInt(2)),
            new ItemStack(Items.SHIELD),
            new ItemStack(Items.STONE_PICKAXE),
            new ItemStack(Items.LEATHER_LEGGINGS),
            new ItemStack(Items.LEATHER_BOOTS),
            new ItemStack(Items.STONE_HOE),
            new ItemStack(Items.TORCH, 8 + rng.nextInt(9)),
            new ItemStack(Items.COOKED_CHICKEN, 3),
            new ItemStack(Items.FISHING_ROD),
            new ItemStack(Items.BUCKET),
            new ItemStack(Items.FLINT, 2),
            new ItemStack(Items.IRON_SWORD),
            new ItemStack(Items.OAK_LOG, 4)
         };
         case 1 -> new ItemStack[]{
            new ItemStack(Items.OAK_PLANKS, 16),
            new ItemStack(Items.COBBLESTONE, 12 + rng.nextInt(13)),
            new ItemStack(Items.IRON_INGOT, 2 + rng.nextInt(2)),
            new ItemStack(Items.GOLD_INGOT, 1),
            new ItemStack(Items.BOW),
            new ItemStack(Items.ARROW, 8 + rng.nextInt(9)),
            new ItemStack(Items.SHIELD),
            new ItemStack(Items.IRON_PICKAXE),
            new ItemStack(Items.IRON_LEGGINGS),
            new ItemStack(Items.CROSSBOW),
            new ItemStack(Items.GOLDEN_APPLE, 1),
            new ItemStack(Items.ENDER_PEARL),
            new ItemStack(Items.WATER_BUCKET),
            new ItemStack(Items.IRON_BOOTS),
            new ItemStack(Items.IRON_HELMET),
            new ItemStack(Items.CHAINMAIL_CHESTPLATE),
            new ItemStack(Items.LAVA_BUCKET),
            new ItemStack(Items.TNT, 2),
            new ItemStack(Items.COBWEB, 2),
            new ItemStack(Items.LADDER, 8),
            new ItemStack(Items.TORCH, 12),
            new ItemStack(Items.IRON_INGOT, 3),
            new ItemStack(Items.COOKED_BEEF, 6),
            new ItemStack(Items.SHIELD),
            gladPotion(Items.POTION, Potions.HEALING)
         };
         case 2 -> new ItemStack[]{
            new ItemStack(Items.DIAMOND, 1 + rng.nextInt(2)),
            new ItemStack(Items.OBSIDIAN, 4),
            new ItemStack(Items.END_CRYSTAL, 2),
            new ItemStack(Items.DIAMOND_BOOTS),
            new ItemStack(Items.DIAMOND_LEGGINGS),
            new ItemStack(Items.GOLDEN_APPLE, 2),
            new ItemStack(Items.ENCHANTED_GOLDEN_APPLE),
            new ItemStack(Items.BOW),
            new ItemStack(Items.ARROW, 16),
            gladPotion(Items.SPLASH_POTION, Potions.HARMING),
            gladPotion(Items.POTION, Potions.STRENGTH),
            new ItemStack(Items.ENDER_PEARL, 2),
            new ItemStack(Items.TOTEM_OF_UNDYING),
            new ItemStack(Items.CROSSBOW),
            new ItemStack(Items.WIND_CHARGE, 8),
            new ItemStack(Items.MACE),
            new ItemStack(Items.COBBLESTONE, 16),
            new ItemStack(Items.OAK_PLANKS, 16)
         };
         default -> new ItemStack[]{
            new ItemStack(Items.DIAMOND_PICKAXE),
            new ItemStack(Items.DIAMOND_SWORD),
            new ItemStack(Items.DIAMOND_HELMET),
            gladEscape(),
            // The rockets went with the wings they were fuel for; what takes their place is the
            // other half of an escape - the thing that makes the fall the pearls put you into a
            // decision instead of a death.
            gladPotion(Items.SPLASH_POTION, Potions.SLOW_FALLING),
            new ItemStack(Items.ENCHANTED_GOLDEN_APPLE, 2),
            new ItemStack(Items.TOTEM_OF_UNDYING),
            new ItemStack(Items.MACE),
            new ItemStack(Items.END_CRYSTAL, 3),
            new ItemStack(Items.OBSIDIAN, 8),
            new ItemStack(Items.ENDER_PEARL, 3),
            new ItemStack(Items.WIND_CHARGE, 12),
            gladPotion(Items.POTION, Potions.STRONG_HEALING),
            gladPotion(Items.POTION, Potions.STRENGTH),
            gladPotion(Items.SPLASH_POTION, Potions.STRONG_SWIFTNESS),
            gladPotion(Items.SPLASH_POTION, Potions.STRONG_LEAPING),
            new ItemStack(Items.NETHERITE_INGOT),
            new ItemStack(Items.TNT, 4),
            new ItemStack(Items.FLINT_AND_STEEL),
            new ItemStack(Items.LADDER, 12),
            new ItemStack(Items.SCAFFOLDING, 16),
            new ItemStack(Items.COBWEB, 4),
            new ItemStack(Items.HONEY_BOTTLE, 2),
            new ItemStack(Items.HAY_BLOCK, 4),
            new ItemStack(Items.GOLDEN_APPLE, 4),
            new ItemStack(Items.ARROW, 24),
            new ItemStack(Items.ENCHANTING_TABLE)
         };
      };

      // Three to six extras rather than two to four, and the same for every tier: with
      // a table this long, two rolls out of the same pool is how every cache of a tier
      // starts to look like every other one.
      int extras = 3 + rng.nextInt(4);
      for (int i = 0; i < extras; i++) {
         items.add(pool[rng.nextInt(pool.length)].copy());
      }

      return items;
   }

   /**
    * Sumo: a bare twenty-block platform in the void, with no walls at all.
    *
    * <p>Every other arena rings itself with barriers to stop players wandering
    * off. Here that would delete the mode - the loss condition <i>is</i> being
    * knocked past the edge and falling out - so the platform is free-standing and
    * ringed with a rim you can see but not lean on.
    */
   private static void buildSumo(ServerLevel realm, Arena a) {
      int ox = a.ox;
      int oz = a.oz;
      int size = 20;

      for (int x = 0; x < size; x++) {
         for (int z = 0; z < size; z++) {
            boolean rim = x == 0 || z == 0 || x == size - 1 || z == size - 1;
            realm.setBlock(new BlockPos(ox + x, 100, oz + z), (rim ? Blocks.STONE_BRICKS : Blocks.SMOOTH_STONE).defaultBlockState(), 3);
         }
      }

      a.x0 = ox;
      a.x1 = ox + size - 1;
      a.z0 = oz;
      a.z1 = oz + size - 1;
      // The floor sits at y=100 and everything below it is air, so a knockback
      // that clears the rim drops straight past the out-of-bounds line.
      a.voidY = 97;
      a.near = 4.5;
      a.far = size - 4.5;
      double midZ = oz + size / 2.0 + 0.5;
      a.spawn0 = new double[]{ox + 3.5, 101.0, midZ};
      a.spawn1 = new double[]{ox + size - 3.5, 101.0, midZ};
      a.yaw0 = 90.0F;
      a.yaw1 = -90.0F;
      a.spreadRadius = size / 2.0 - 3.0;
      a.spectatorSpawn = new double[]{ox + size / 2.0, 108.0, oz + size / 2.0};
   }

   // ---------------------------------------------------------------- archery

   private static final int ARCHERY_SIZE = 44;

   /**
    * Archery: a wood, not an arena.
    *
    * <p>The mode used to be a flat box with two bows in it, which made it a stat
    * check - whoever's first arrow landed won, and cover was a thing neither side
    * had. This is a square of grass with an actual forest on it: trunks to break
    * line of sight, chest-high boulders to duck behind, and enough ground between
    * the spawns that closing the distance is a decision rather than a formality.
    *
    * <p>The layout is mirrored through the middle. Whatever cover one side gets, the
    * other side gets the same arrangement of it, which is the difference between a
    * fair map and a lucky one. Both spawns are cleared, faced at each other, and
    * ringed by a wall, because a ranged duel that ends with somebody wandering off
    * the edge is not a duel.
    */
   private static void buildArchery(ServerLevel realm, Duel d) {
      Arena a = d.arena;
      int ox = a.ox;
      int oz = a.oz;
      int size = ARCHERY_SIZE;
      int floorY = 100;
      double midZ = oz + size / 2.0 + 0.5;
      // Seeded from the fight rather than a constant, so a wood is a fresh wood each
      // match. The mirroring below is what makes that variety fair instead of lucky.
      java.util.Random rng = new java.util.Random(d.botSeed ^ 0x5EEDL);

      for (int x = -1; x <= size; x++) {
         for (int z = -1; z <= size; z++) {
            boolean edge = x < 0 || z < 0 || x >= size || z >= size;
            realm.setBlock(new BlockPos(ox + x, floorY, oz + z), Blocks.GRASS_BLOCK.defaultBlockState(), 3);
            realm.setBlock(new BlockPos(ox + x, floorY - 1, oz + z), Blocks.DIRT.defaultBlockState(), 2);
            if (edge) {
               for (int y = floorY + 1; y <= floorY + 4; y++) {
                  realm.setBlock(new BlockPos(ox + x, y, oz + z), Blocks.STONE_BRICKS.defaultBlockState(), 3);
               }
            }
         }
      }

      // The near half is generated and then mirrored onto the far half, so the two
      // sides are the same map rather than two maps that happen to be joined.
      for (int i = 0; i < 26; i++) {
         int tx = 3 + rng.nextInt(size / 2 - 6);
         int tz = 3 + rng.nextInt(size - 6);
         if (archerySpawnClear(tx, tz, size)) {
            continue;
         }

         int trunk = 4 + rng.nextInt(3);
         plantArcheryTree(realm, ox + tx, oz + tz, floorY, trunk);
         plantArcheryTree(realm, ox + (size - 1 - tx), oz + tz, floorY, trunk);
      }

      // Boulders. A tree is full cover; this is the kind you shoot over and then
      // step behind while the arrow it baited is still in the air.
      for (int i = 0; i < 12; i++) {
         int bx = 3 + rng.nextInt(size / 2 - 6);
         int bz = 3 + rng.nextInt(size - 6);
         if (archerySpawnClear(bx, bz, size)) {
            continue;
         }

         for (int dx = 0; dx < 2; dx++) {
            for (int dz = 0; dz < 2; dz++) {
               for (int dy = 0; dy < 2; dy++) {
                  BlockPos near = new BlockPos(ox + bx + dx, floorY + 1 + dy, oz + bz + dz);
                  BlockPos far = new BlockPos(ox + (size - 1 - bx) - dx, floorY + 1 + dy, oz + bz + dz);
                  realm.setBlock(near, Blocks.COBBLESTONE.defaultBlockState(), 3);
                  realm.setBlock(far, Blocks.COBBLESTONE.defaultBlockState(), 3);
               }
            }
         }
      }

      // Two caches on the centre line: more arrows, and a gapple. That is the only
      // reason either fighter has to step into the open, which is what stops a wood
      // from turning into two people holding angles until the match times out.
      for (int i = 0; i < 2; i++) {
         BlockPos cache = new BlockPos(ox + size / 2 + (i == 0 ? -1 : 1), floorY + 1, oz + size / 2);
         placeChest(realm, cache, archeryChest(rng));
         a.chests.add(cache);
      }

      a.x0 = ox;
      a.x1 = ox + size - 1;
      a.z0 = oz;
      a.z1 = oz + size - 1;
      a.voidY = 99;
      a.spawn0 = new double[]{ox + 4.5, floorY + 1, midZ};
      a.spawn1 = new double[]{ox + size - 4.5, floorY + 1, midZ};
      // Looking at each other across the wood: -90 of yaw faces +X, 90 faces -X.
      a.yaw0 = -90.0F;
      a.yaw1 = 90.0F;
      a.near = 4.5;
      a.far = size - 4.5;
      a.spreadRadius = size / 2.0 - 4.0;
      a.spectatorSpawn = new double[]{ox + size / 2.0, floorY + 14.0, midZ};
   }

   /** Keeps trunks and rocks off the two spawn pads, so nobody opens inside a tree. */
   private static boolean archerySpawnClear(int x, int z, int size) {
      return x < 11 && Math.abs(z - size / 2) < 6;
   }

   private static void plantArcheryTree(ServerLevel realm, int x, int z, int floorY, int trunk) {
      for (int i = 1; i <= trunk; i++) {
         realm.setBlock(new BlockPos(x, floorY + i, z), Blocks.OAK_LOG.defaultBlockState(), 3);
      }

      for (int ly = trunk - 2; ly <= trunk + 1; ly++) {
         int radius = ly >= trunk ? 1 : 2;
         for (int lx = -radius; lx <= radius; lx++) {
            for (int lz = -radius; lz <= radius; lz++) {
               if (Math.abs(lx) == radius && Math.abs(lz) == radius) {
                  continue;
               }

               BlockPos leaf = new BlockPos(x + lx, floorY + ly, z + lz);
               if (realm.getBlockState(leaf).isAir()) {
                  realm.setBlock(leaf, Blocks.OAK_LEAVES.defaultBlockState(), 3);
               }
            }
         }
      }
   }

   private static List<ItemStack> archeryChest(java.util.Random rng) {
      List<ItemStack> out = new ArrayList<>();
      out.add(new ItemStack(Items.ARROW, 16 + rng.nextInt(17)));
      out.add(new ItemStack(Items.GOLDEN_APPLE));
      if (rng.nextBoolean()) {
         out.add(new ItemStack(Items.BOW));
      }

      return out;
   }

   private static void buildTntrun(ServerLevel realm, Arena a) {
      int ox = a.ox;
      int oz = a.oz;
      int[] sizes = new int[]{30, 22, 16};
      int layers = sizes.length;
      int gap = 10;

      for (int layer = 0; layer < layers; layer++) {
         int floorY = 100 + layer * gap;
         int size = sizes[layer];
         int off = (sizes[0] - size) / 2;

         for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
               realm.setBlock(new BlockPos(ox + off + x, floorY, oz + off + z), (Math.random() < 0.5 ? Blocks.GRAVEL : Blocks.SAND).defaultBlockState(), 3);
               realm.setBlock(new BlockPos(ox + off + x, floorY - 1, oz + off + z), Blocks.TNT.defaultBlockState(), 3);
            }
         }
      }

      int topSize = sizes[layers - 1];
      int topOff = (sizes[0] - topSize) / 2;
      int topY = 100 + (layers - 1) * gap;
      a.spawn0 = new double[]{ox + topOff + topSize * 0.25, topY + 1, oz + topOff + topSize * 0.5};
      a.spawn1 = new double[]{ox + topOff + topSize * 0.75, topY + 1, oz + topOff + topSize * 0.5};
      a.x0 = ox;
      a.x1 = ox + sizes[0] - 1;
      a.z0 = oz;
      a.z1 = oz + sizes[0] - 1;
      a.spreadRadius = topSize / 2.0 - 4.0;
      a.voidY = 90;
      a.near = topOff + 5.5;
      a.far = topOff + topSize - 5.5;
   }

   private static void buildBedwars(ServerLevel realm, Duel d) {
      Arena a = d.arena;
      int ox = a.ox;
      int oz = a.oz;
      int az = oz + 24;
      buildIsland(realm, ox + 2, az - 10, 24, 24, Blocks.SMOOTH_SANDSTONE, (Block)Blocks.WOOL.red());
      buildIsland(realm, ox + 64, az - 10, 24, 24, Blocks.SMOOTH_SANDSTONE, (Block)Blocks.WOOL.blue());
      buildIsland(realm, ox + 36, az - 14, 18, 24, Blocks.SMOOTH_SANDSTONE, Blocks.CHISELED_STONE_BRICKS);
      buildIsland(realm, ox + 41, oz + 4, 8, 4, Blocks.STONE, (Block)Blocks.WOOL.yellow());
      buildIsland(realm, ox + 41, oz + 52, 8, 4, Blocks.STONE, (Block)Blocks.WOOL.yellow());
      // --- Revamp: lanes instead of voids ---------------------------------
      // The map used to be five islands and a lot of sky. Every objective was
      // reached by walking off your spawn island and bridging, so the opening
      // of every match was identical and the two side islands were decoration
      // nobody visited on purpose. Each spawn island now has a lane to the
      // middle, and the middle is bridged to both side islands, so the map has
      // somewhere to go and somewhere to fight on the way.
      buildIsland(realm, ox + 26, az - 2, 10, 3, Blocks.SMOOTH_SANDSTONE, Blocks.CHISELED_STONE_BRICKS);
      buildIsland(realm, ox + 54, az - 2, 10, 3, Blocks.SMOOTH_SANDSTONE, Blocks.CHISELED_STONE_BRICKS);
      buildIsland(realm, ox + 42, az - 16, 4, 2, Blocks.STONE, (Block)Blocks.WOOL.yellow());
      buildIsland(realm, ox + 42, az + 10, 4, 18, Blocks.STONE, (Block)Blocks.WOOL.yellow());
      // Four pillars on the middle island: a landmark you can navigate by from
      // spawn, and the only cover on the map that is not a block you placed.
      for (int px : new int[]{ox + 37, ox + 52}) {
         for (int pz : new int[]{az - 13, az + 8}) {
            buildPillar(realm, px, pz, 3, Blocks.CHISELED_STONE_BRICKS, (Block)Blocks.WOOL.white());
         }
      }
      placeBed(realm, new BlockPos(ox + 19, 101, az + 2), Direction.EAST);
      // A low wall behind each bed. Nothing about the bed itself changed - it is
      // still the thing that has to be broken - but a bed sitting in the open on
      // a plate is a formality, and this is the block an attacker has to spend
      // their first swing on instead.
      buildBedWall(realm, ox + 21, az + 1, (Block)Blocks.WOOL.red());
      buildBedWall(realm, ox + 69, az + 1, (Block)Blocks.WOOL.blue());
      a.bed0 = new BlockPos(ox + 19, 101, az + 2);
      a.bed0Head = new BlockPos(ox + 20, 101, az + 2);
      BlockPos forgeA = placeGeneratorPit(realm, ox + 5, az + 2, Blocks.IRON_BLOCK);
      a.forgeGens.add(forgeA);
      a.spawn0 = new double[]{ox + 8.5, 101.0, az + 4.5};
      spawnVillager(realm, d, ox + 4, az + 8, "shop0", "Item Shop", "§6");
      spawnVillager(realm, d, ox + 10, az + 9, "upgrade0", "Upgrades", "§b");
      a.chest0 = new BlockPos(ox + 14, 101, az - 8);
      realm.setBlock(a.chest0, Blocks.CHEST.defaultBlockState(), 3);
      placeBed(realm, new BlockPos(ox + 71, 101, az + 2), Direction.WEST);
      a.bed1 = new BlockPos(ox + 71, 101, az + 2);
      a.bed1Head = new BlockPos(ox + 70, 101, az + 2);
      BlockPos forgeB = placeGeneratorPit(realm, ox + 84, az + 2, Blocks.IRON_BLOCK);
      a.forgeGens.add(forgeB);
      a.spawn1 = new double[]{ox + 80.5, 101.0, az + 4.5};
      spawnVillager(realm, d, ox + 85, az + 8, "shop1", "Item Shop", "§6");
      spawnVillager(realm, d, ox + 79, az + 9, "upgrade1", "Upgrades", "§b");
      a.chest1 = new BlockPos(ox + 76, 101, az - 8);
      realm.setBlock(a.chest1, Blocks.CHEST.defaultBlockState(), 3);
      a.diamondGens.add(placeGeneratorPit(realm, ox + 42, oz + 5, Blocks.DIAMOND_BLOCK));
      a.diamondGens.add(placeGeneratorPit(realm, ox + 45, oz + 5, Blocks.DIAMOND_BLOCK));
      a.diamondGens.add(placeGeneratorPit(realm, ox + 42, oz + 53, Blocks.DIAMOND_BLOCK));
      a.diamondGens.add(placeGeneratorPit(realm, ox + 45, oz + 53, Blocks.DIAMOND_BLOCK));
      a.emeraldGens.add(placeGeneratorPit(realm, ox + 38, oz + 22, Blocks.EMERALD_BLOCK));
      a.emeraldGens.add(placeGeneratorPit(realm, ox + 46, oz + 22, Blocks.EMERALD_BLOCK));
      a.emeraldGens.add(placeGeneratorPit(realm, ox + 42, oz + 31, Blocks.EMERALD_BLOCK));
      a.yaw0 = -90.0F;
      a.yaw1 = 90.0F;
      a.x0 = ox - 3;
      a.x1 = ox + 93;
      a.z0 = oz - 3;
      a.z1 = oz + 62;
      a.voidY = 95;
   }

   private static void buildSkywars(ServerLevel realm, Duel d) {
      Arena a = d.arena;
      int ox = a.ox;
      int oz = a.oz;
      buildIsland(realm, ox + 5, oz + 5, 9, 9, Blocks.GRASS_BLOCK, Blocks.STONE);
      placeChest(realm, new BlockPos(ox + 8, 101, oz + 8), skywarsChest());
      a.chests.add(new BlockPos(ox + 8, 101, oz + 8));
      buildIsland(realm, ox + 64, oz + 64, 9, 9, Blocks.GRASS_BLOCK, Blocks.STONE);
      placeChest(realm, new BlockPos(ox + 67, 101, oz + 67), skywarsChest());
      a.chests.add(new BlockPos(ox + 67, 101, oz + 67));
      buildIsland(realm, ox + 34, oz + 34, 11, 11, Blocks.STONE, Blocks.CHISELED_STONE_BRICKS);
      BlockPos mid1 = new BlockPos(ox + 35, 101, oz + 35);
      BlockPos mid2 = new BlockPos(ox + 42, 101, oz + 35);
      BlockPos mid3 = new BlockPos(ox + 35, 101, oz + 42);
      BlockPos mid4 = new BlockPos(ox + 42, 101, oz + 42);

      for (BlockPos mid : new BlockPos[]{mid1, mid2, mid3, mid4}) {
         a.chests.add(mid);
         a.refillChests.add(mid);
         placeChest(realm, mid, List.of());
      }

      int cx0 = ox + 9;
      int cz0 = oz + 9;
      int cx1 = ox + 68;
      int cz1 = oz + 68;
      buildGlassChamber(realm, cx0, cz0);
      buildGlassChamber(realm, cx1, cz1);
      a.chambers.add(new int[]{cx0 - 1, cx0 + 1, cz0 - 1, cz0 + 1});
      a.chambers.add(new int[]{cx1 - 1, cx1 + 1, cz1 - 1, cz1 + 1});
      a.spawn0 = new double[]{ox + 9.5, 101.0, oz + 9.5};
      a.spawn1 = new double[]{ox + 68.5, 101.0, oz + 68.5};
      a.lobbySpawn0 = new double[]{cx0 + 0.5, 104.0, cz0 + 0.5};
      a.lobbySpawn1 = new double[]{cx1 + 0.5, 104.0, cz1 + 0.5};
      a.yaw0 = -45.0F;
      a.yaw1 = 135.0F;
      a.x0 = ox - 3;
      a.x1 = ox + 80;
      a.z0 = oz - 3;
      a.z1 = oz + 80;
      a.voidY = 95;
   }

   private static void buildGlassChamber(ServerLevel realm, int cx, int cz) {
      int baseY = 103;

      for (int dx = -1; dx <= 1; dx++) {
         for (int dz = -1; dz <= 1; dz++) {
            realm.setBlock(new BlockPos(cx + dx, baseY, cz + dz), Blocks.GLASS.defaultBlockState(), 3);
            realm.setBlock(new BlockPos(cx + dx, baseY + 3, cz + dz), Blocks.GLASS.defaultBlockState(), 3);
         }
      }

      for (int y = baseY + 1; y <= baseY + 2; y++) {
         for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
               if (dx != 0 || dz != 0) {
                  realm.setBlock(new BlockPos(cx + dx, y, cz + dz), Blocks.GLASS.defaultBlockState(), 3);
               }
            }
         }
      }
   }

   private static void teardownArena(ServerLevel realm, Duel d) {
      Arena a = d.arena;
      int plot = plotOf(a.ox, a.oz);
      int x0 = Math.min(a.x0, a.ox) - 3;
      int x1 = Math.max(a.x1, a.ox + 43) + 3;
      int z0 = Math.min(a.z0, a.oz) - 3;
      int z1 = Math.max(a.z1, a.oz + 43) + 3;
      int y0 = a.voidY != 0 ? a.voidY - 1 : 95;
      int y1 = 164;
      AABB box = new AABB(x0, y0, z0, x1, y1, z1);

      for (ItemEntity e : realm.getEntitiesOfClass(ItemEntity.class, box)) {
         e.discard();
      }

      for (Projectile pr : realm.getEntitiesOfClass(Projectile.class, box)) {
         pr.discard();
      }

      for (ExperienceOrb orb : realm.getEntitiesOfClass(ExperienceOrb.class, box)) {
         orb.discard();
      }

      for (UUID vuid : d.villagerRoles.keySet()) {
         Entity e = realm.getEntity(vuid);
         if (e != null) {
            e.discard();
         }
      }

      for (Villager v : realm.getEntitiesOfClass(Villager.class, box)) {
         v.discard();
      }

      sweepRealmEntities(realm);
      List<BlockPos> toClear = new ArrayList<>();

      for (int x = x0; x <= x1; x++) {
         for (int z = z0; z <= z1; z++) {
            for (int y = y0; y <= y1; y++) {
               toClear.add(new BlockPos(x, y, z));
            }
         }
      }

      List<BlockPos> batch = toClear;

      while (!batch.isEmpty()) {
         List<BlockPos> chunk = batch.subList(0, Math.min(2000, batch.size()));
         List<BlockPos> snapshot = new ArrayList<>(chunk);
         chunk.clear();
         pendingTeardowns.add(() -> {
            for (BlockPos pos : snapshot) {
               realm.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
            }
         });
      }

      pendingTeardowns.add(() -> sweepRealmEntities(realm));
      // Last in the queue for this plot, so it runs only once every batch above has:
      // this is the moment the plot stops holding an arena, and the only one that is
      // safe to record. Crash before it and the plot is swept again on the next boot.
      if (plot >= 0) {
         pendingTeardowns.add(() -> markPlotClean(plot));
      }
   }

   private static void sweepRealmEntities(ServerLevel realm) {
      if (realm != null) {
         AABB whole = new AABB(-3.0E7, -3.0E7, -3.0E7, 3.0E7, 3.0E7, 3.0E7);

         for (ItemEntity e : realm.getEntitiesOfClass(ItemEntity.class, whole, e2 -> !protectedFromSweep(e2.blockPosition()))) {
            e.discard();
         }

         for (Projectile pr : realm.getEntitiesOfClass(Projectile.class, whole, pr2 -> !protectedFromSweep(pr2.blockPosition()))) {
            pr.discard();
         }

         for (ExperienceOrb orb : realm.getEntitiesOfClass(ExperienceOrb.class, whole, orb2 -> !protectedFromSweep(orb2.blockPosition()))) {
            orb.discard();
         }

         for (Villager v : realm.getEntitiesOfClass(Villager.class, whole, v2 -> !protectedFromSweep(v2.blockPosition()))) {
            boolean claimed = false;

            for (Duel other : duels.values()) {
               if (other != null && other.villagerRoles.containsKey(v.getUUID())) {
                  claimed = true;
                  break;
               }
            }

            if (!claimed) {
               v.discard();
            }
         }
      }
   }

   private static boolean protectedFromSweep(BlockPos pos) {
      for (Duel other : duels.values()) {
         if (other != null && other.phase != Phase.ENDED) {
            Arena oa = other.arena;
            int x0 = Math.min(oa.x0, oa.ox) - 3;
            int x1 = Math.max(oa.x1, oa.ox + 43) + 3;
            int z0 = Math.min(oa.z0, oa.oz) - 3;
            int z1 = Math.max(oa.z1, oa.oz + 43) + 3;
            if (pos.getX() >= x0 && pos.getX() <= x1 && pos.getZ() >= z0 && pos.getZ() <= z1) {
               return true;
            }
         }
      }

      return MapEditor.isInEditPlot(pos);
   }

   private static void processStartupSweep(ServerLevel realm) {
      if (startupSweepPlot >= 0 && realm != null) {
         int budget = sweepChunkBudget(STARTUP_SWEEP_CHUNKS_PER_TICK);
         int loaded = 0;
         List<Integer> swept = new ArrayList<>();

         while (startupSweepPlot < sweepQueue.size() && loaded < budget) {
            if (usedPlots.contains(sweepQueue.get(startupSweepPlot))) {
               // A match claimed the plot first. Its own build clears the plot before
               // anything is placed on it, so there is nothing here for the sweep to do.
               startupSweepPlot++;
               startupSweepChunk = 0;
            } else {
               int plot = sweepQueue.get(startupSweepPlot);
               int ox = plotOriginX(plot);
               int oz = plotOriginZ(plot);
               // The sweep region is a gladiator world wide, because a gladiator
               // world is the one arena that reaches its plot's edge. A plot is a
               // full stride from its neighbour, so this still cannot touch one.
               int cx0 = (ox - 8) >> 4;
               int cz0 = (oz - 8) >> 4;
               int cx1 = (ox + GLADIATOR_W + 4) >> 4;
               int cz1 = (oz + GLADIATOR_H + 4) >> 4;
               int perRow = cx1 - cx0 + 1;

               int total;
               for (total = perRow * (cz1 - cz0 + 1); startupSweepChunk < total && loaded < budget; loaded++) {
                  int ci = startupSweepChunk++;
                  int cx = cx0 + ci % perRow;
                  int cz = cz0 + ci / perRow;
                  LevelChunk chunk = realm.getChunkAt(new BlockPos(cx << 4, 100, cz << 4));
                  clearChunkBlocks(realm, chunk);
               }

               if (startupSweepChunk >= total) {
                  discardPlotEntities(realm, plot);
                  swept.add(plot);
                  startupSweepPlot++;
                  startupSweepChunk = 0;
               }
            }
         }

         if (startupSweepPlot >= sweepQueue.size()) {
            finishStartupSweep(swept);
         }
      }
   }

   /** Ends a finished boot sweep, and drops the marks of the plots it cleared. */
   private static void finishStartupSweep(List<Integer> swept) {
      startupSweepPlot = -1;
      startupSweepChunk = 0;
      if (swept.isEmpty()) {
         return;
      }

      // Only the plots this run actually cleared stop being dirty: one claimed while the
      // sweep was running belongs to a live match and must stay marked.
      boolean changed = false;
      for (int plot : swept) {
         changed |= dirtyPlots.remove(plot);
      }

      if (changed) {
         saveSweepMarkers();
      }

      FortuneFavorsMod.LOGGER.info("Fortune & Favors: duel realm swept - leftover arenas cleared");
   }

   private static void discardPlotEntities(ServerLevel realm, int plot) {
      int ox = PLOT_ORIGIN + plot % PLOT_COLS * PLOT_STRIDE;
      int oz = PLOT_ORIGIN + plot / PLOT_COLS * PLOT_STRIDE;
      AABB box = new AABB(ox - 8, 96.0, oz - 8, ox + GLADIATOR_W + 4, 164.0, oz + GLADIATOR_H + 4);

      for (ItemEntity e : realm.getEntitiesOfClass(ItemEntity.class, box)) {
         e.discard();
      }

      for (Projectile pr : realm.getEntitiesOfClass(Projectile.class, box)) {
         pr.discard();
      }

      for (ExperienceOrb orb : realm.getEntitiesOfClass(ExperienceOrb.class, box)) {
         orb.discard();
      }

      for (Villager v : realm.getEntitiesOfClass(Villager.class, box)) {
         v.discard();
      }
   }

   private static void clearChunkBlocks(ServerLevel realm, LevelChunk chunk) {
      for (int sy = 0; sy < chunk.getSectionsCount(); sy++) {
         LevelChunkSection section = chunk.getSection(sy);
         int y0 = chunk.getMinSectionY() + sy << 4;
         if (y0 + 15 >= 98 && y0 <= 164 && !section.hasOnlyAir()) {
            int bx = chunk.getPos().getMinBlockX();
            int bz = chunk.getPos().getMinBlockZ();

            for (int x = 0; x < 16; x++) {
               for (int z = 0; z < 16; z++) {
                  for (int y = 0; y < 16; y++) {
                     int wy = y0 + y;
                     if (wy >= 98 && wy <= 164 && !section.getBlockState(x, y, z).isAir()) {
                        realm.setBlock(new BlockPos(bx + x, wy, bz + z), Blocks.AIR.defaultBlockState(), 3);
                     }
                  }
               }
            }
         }
      }
   }

   private static void paint(ServerLevel realm, int x, int z, int w, int h, Block block) {
      for (int dx = 0; dx < w; dx++) {
         for (int dz = 0; dz < h; dz++) {
            realm.setBlock(new BlockPos(x + dx, 100, z + dz), block.defaultBlockState(), 3);
         }
      }
   }

   private static void setBlock(ServerLevel realm, int x, int y, int z, Block block) {
      realm.setBlock(new BlockPos(x, y, z), block.defaultBlockState(), 3);
   }

   private static void setBarrier(ServerLevel realm, int x, int z) {
      realm.setBlock(new BlockPos(x, 100, z), Blocks.BARRIER.defaultBlockState(), 3);
      realm.setBlock(new BlockPos(x, 101, z), Blocks.BARRIER.defaultBlockState(), 3);
   }

   private static void buildIsland(ServerLevel realm, int x, int z, int w, int h, Block floor, Block ring) {
      for (int dx = 0; dx < w; dx++) {
         for (int dz = 0; dz < h; dz++) {
            realm.setBlock(new BlockPos(x + dx, 100, z + dz), floor.defaultBlockState(), 3);
         }
      }

      for (int dx = -1; dx <= w; dx++) {
         setRing(realm, x + dx, z - 1, ring);
         setRing(realm, x + dx, z + h, ring);
      }

      for (int dz = 0; dz < h; dz++) {
         setRing(realm, x - 1, z + dz, ring);
         setRing(realm, x + w, z + dz, ring);
      }
   }

   private static void setRing(ServerLevel realm, int x, int z, Block block) {
      realm.setBlock(new BlockPos(x, 100, z), block.defaultBlockState(), 3);
   }

   private static void buildPillar(ServerLevel realm, int x, int z, int height, Block body, Block cap) {
      for (int i = 0; i < height; i++) {
         realm.setBlock(new BlockPos(x, 101 + i, z), body.defaultBlockState(), 3);
      }

      realm.setBlock(new BlockPos(x, 101 + height, z), cap.defaultBlockState(), 3);
   }

   /** Two blocks of the team's own wool standing between the bed and the field. */
   private static void buildBedWall(ServerLevel realm, int x, int z, Block wool) {
      for (int dz = 0; dz < 3; dz++) {
         realm.setBlock(new BlockPos(x, 101, z + dz), wool.defaultBlockState(), 3);
         realm.setBlock(new BlockPos(x, 102, z + dz), wool.defaultBlockState(), 3);
      }
   }

   private static BlockPos placeGeneratorPit(ServerLevel realm, int x, int z, Block core) {
      for (int dx = 0; dx < 3; dx++) {
         for (int dz = 0; dz < 3; dz++) {
            realm.setBlock(new BlockPos(x + dx, 100, z + dz), Blocks.AIR.defaultBlockState(), 3);
            realm.setBlock(new BlockPos(x + dx, 99, z + dz), Blocks.SMOOTH_SANDSTONE.defaultBlockState(), 3);
         }
      }

      BlockPos corePos = new BlockPos(x + 1, 99, z + 1);
      realm.setBlock(corePos, core.defaultBlockState(), 3);

      for (int i = 0; i < 3; i++) {
         placePitStair(realm, x + i, z - 1, Direction.NORTH);
         placePitStair(realm, x + i, z + 3, Direction.SOUTH);
         placePitStair(realm, x - 1, z + i, Direction.WEST);
         placePitStair(realm, x + 3, z + i, Direction.EAST);
      }

      return corePos;
   }

   private static void placePitStair(ServerLevel realm, int x, int z, Direction facing) {
      BlockState stair = (BlockState)((BlockState)Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, facing))
         .setValue(StairBlock.HALF, Half.BOTTOM);
      realm.setBlock(new BlockPos(x, 100, z), stair, 3);
   }

   private static void spawnVillager(ServerLevel realm, Duel d, int x, int z, String role, String name, String color) {
      spawnVillagerAt(realm, d, x, 100, z, role, name, color);
   }

   private static void spawnVillagerAt(ServerLevel realm, Duel d, int x, int floorY, int z, String role, String name, String color) {
      Villager v = new Villager(EntityTypes.VILLAGER, realm);
      v.setPos(x + 0.5, floorY + 1, z + 0.5);
      v.setNoAi(true);
      v.setPersistenceRequired();
      v.setInvulnerable(true);
      v.setCustomName(Component.literal(color + "§l" + name));
      v.setCustomNameVisible(true);
      realm.addFreshEntity(v);
      d.villagerRoles.put(v.getUUID(), role);
   }

   private static void placeBed(ServerLevel realm, BlockPos foot, Direction facing) {
      BlockState bed = (BlockState)((Block)Blocks.BED.red()).defaultBlockState().setValue(BedBlock.FACING, facing);
      BlockState head = (BlockState)bed.setValue(BedBlock.PART, BedPart.HEAD);
      BlockState footState = (BlockState)bed.setValue(BedBlock.PART, BedPart.FOOT);
      realm.setBlock(foot, footState, 3);
      realm.setBlock(foot.relative(facing), head, 3);
   }

   private static void placeChest(ServerLevel realm, BlockPos pos, List<ItemStack> items) {
      realm.setBlock(pos, Blocks.CHEST.defaultBlockState(), 3);
      if (realm.getBlockEntity(pos) instanceof ChestBlockEntity chest) {
         fillChest(chest, items);
      }
   }

   private static void fillChest(ChestBlockEntity chest, List<ItemStack> items) {
      for (int i = 0; i < chest.getContainerSize(); i++) {
         chest.setItem(i, i < items.size() ? items.get(i).copy() : ItemStack.EMPTY);
      }
   }

   private static List<ItemStack> skywarsChest() {
      List<ItemStack> out = new ArrayList<>();
      out.add(randomWeapon(false));
      out.add(new ItemStack(Items.BOW));
      out.add(new ItemStack(Items.ARROW, 12 + (int)(Math.random() * 20.0)));
      out.add(new ItemStack(randomBridgingBlock(), 24 + (int)(Math.random() * 24.0)));
      out.add(randomFood());
      out.add(randomArmorPiece(false));
      int extras = 1 + (int)(Math.random() * 2.0);

      for (int i = 0; i < extras; i++) {
         out.add(randomChestUtility());
      }

      if (Math.random() < 0.35) {
         out.add(randomArmorPiece(false));
      }

      if (Math.random() < 0.25) {
         out.add(new ItemStack(Items.ENDER_PEARL, 1 + (int)(Math.random() * 2.0)));
      }

      return out;
   }

   private static Item whiteWool() {
      return (Item)Items.WOOL.pick(DyeColor.WHITE);
   }

   private static Item teamWool(int slot) {
      return (Item)Items.WOOL.pick(slot == 0 ? DyeColor.RED : DyeColor.BLUE);
   }

   private static Item teamWoolFor(ServerPlayer p) {
      if (p == null) {
         return whiteWool();
      } else {
         Duel d = duels.get(p.getUUID());
         if (d != null && d.mode == DuelMode.BEDWARS) {
            Participant part = participantOf(d, p);
            return part != null ? teamWool(part.slot) : whiteWool();
         } else {
            return whiteWool();
         }
      }
   }

   private static List<ItemStack> pvpLootChest() {
      List<ItemStack> out = new ArrayList<>();
      out.add(randomWeapon(false));
      if (Math.random() < 0.5) {
         out.add(randomWeapon(false));
      }

      if (Math.random() < 0.65) {
         out.add(randomArmorPiece(false));
      }

      out.add(randomFood());
      out.add(randomFood());
      int utils = 2 + (int)(Math.random() * 3.0);

      for (int i = 0; i < utils; i++) {
         out.add(randomChestUtility());
      }

      if (Math.random() < 0.3) {
         out.add(new ItemStack(Items.GOLDEN_APPLE, 1 + (int)(Math.random() * 2.0)));
      }

      if (Math.random() < 0.15) {
         out.add(new ItemStack(Items.ENCHANTED_GOLDEN_APPLE));
      }

      if (Math.random() < 0.2) {
         out.add(new ItemStack(Items.TOTEM_OF_UNDYING));
      }

      if (Math.random() < 0.25) {
         out.add(new ItemStack(Items.ENDER_PEARL, 1 + (int)(Math.random() * 2.0)));
      }

      return out;
   }

   private static List<ItemStack> midChest() {
      return randomMidChest(false);
   }

   private static List<ItemStack> midChestDiamond() {
      return randomMidChest(true);
   }

   private static List<ItemStack> randomMidChest(boolean diamond) {
      List<ItemStack> out = new ArrayList<>();
      out.add(randomWeapon(diamond));
      if (Math.random() < 0.7) {
         out.add(new ItemStack(Items.BOW));
         out.add(new ItemStack(Items.ARROW, 12 + (int)(Math.random() * 20.0)));
      }

      if (Math.random() < 0.7) {
         out.add(randomArmorPiece(diamond));
      }

      out.add(randomFood());
      out.add(new ItemStack(randomBridgingBlock(), diamond ? 24 : 16));
      out.add(new ItemStack(Items.ENDER_PEARL, 1 + (int)(Math.random() * 3.0)));
      if (Math.random() < 0.5) {
         out.add(new ItemStack(Items.OBSIDIAN, diamond ? 16 : 8));
      }

      if (Math.random() < 0.4) {
         out.add(new ItemStack(Items.GOLDEN_APPLE, 1 + (int)(Math.random() * 2.0)));
      }

      if (Math.random() < 0.2) {
         out.add(new ItemStack(Items.ENCHANTED_GOLDEN_APPLE));
      }

      int extras = (int)(Math.random() * 3.0);

      for (int i = 0; i < extras; i++) {
         out.add(randomChestUtility());
      }

      return out;
   }

   private static ItemStack randomFood() {
      Item[] pool = new Item[]{Items.COOKED_BEEF, Items.COOKED_PORKCHOP, Items.COOKED_CHICKEN, Items.BREAD, Items.GOLDEN_CARROT, Items.APPLE};
      int count = 3 + (int)(Math.random() * 7.0);
      return Math.random() < 0.15 ? new ItemStack(Items.GOLDEN_APPLE, 1) : new ItemStack(pool[(int)(Math.random() * pool.length)], count);
   }

   private static Item randomBridgingBlock() {
      int r = (int)(Math.random() * 10.0);
      if (r < 5) {
         return (Item)Items.WOOL.pick(DyeColor.values()[(int)(Math.random() * DyeColor.values().length)]);
      } else if (r < 7) {
         return Items.OAK_PLANKS;
      } else if (r < 8) {
         return Items.SANDSTONE;
      } else {
         return r < 9 ? Items.END_STONE : Items.OBSIDIAN;
      }
   }

   private static ItemStack randomChestUtility() {
      Item[] pool = new Item[]{
         Items.LAVA_BUCKET,
         Items.WATER_BUCKET,
         Items.ENDER_PEARL,
         Items.SNOWBALL,
         Items.EGG,
         Items.FISHING_ROD,
         Items.SHIELD,
         Items.FLINT_AND_STEEL,
         Items.TNT,
         Items.COBWEB,
         Items.EXPERIENCE_BOTTLE,
         Items.FIRE_CHARGE
      };
      Item pick = pool[(int)(Math.random() * pool.length)];
      int count = 1;
      if (pick == Items.ENDER_PEARL) {
         count = 2;
      } else if (pick == Items.SNOWBALL || pick == Items.EGG) {
         count = 16;
      } else if (pick == Items.TNT || pick == Items.COBWEB) {
         count = 4;
      } else if (pick == Items.EXPERIENCE_BOTTLE) {
         count = 8;
      } else if (pick == Items.FIRE_CHARGE) {
         count = 8;
      }

      return new ItemStack(pick, count);
   }

   private static ItemStack randomArmorPiece(boolean diamond) {
      Item[] pool = diamond
         ? new Item[]{Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS}
         : new Item[]{
            Items.LEATHER_HELMET,
            Items.LEATHER_CHESTPLATE,
            Items.LEATHER_LEGGINGS,
            Items.LEATHER_BOOTS,
            Items.CHAINMAIL_HELMET,
            Items.CHAINMAIL_CHESTPLATE,
            Items.CHAINMAIL_LEGGINGS,
            Items.CHAINMAIL_BOOTS,
            Items.IRON_HELMET,
            Items.IRON_CHESTPLATE,
            Items.IRON_LEGGINGS,
            Items.IRON_BOOTS
         };
      return new ItemStack(pool[(int)(Math.random() * pool.length)]);
   }

   private static ItemStack randomWeapon(boolean strong) {
      Item[] pool = strong
         ? new Item[]{Items.DIAMOND_SWORD, Items.IRON_SWORD, Items.DIAMOND_AXE, Items.IRON_AXE}
         : new Item[]{Items.STONE_SWORD, Items.IRON_SWORD, Items.STONE_AXE, Items.IRON_AXE};
      return new ItemStack(pool[(int)(Math.random() * pool.length)]);
   }

   private static void tickGamemode(Duel d, ServerLevel realm, long now) {
      if (d.mode == DuelMode.BEDWARS) {
         tickBedwarsGens(d, realm, now);

         for (Participant part : d.parts) {
            if (part.bedIntact) {
               BlockPos bed = d.arena.bedFor(part.slot);
               BlockPos head = d.arena.bedHeadFor(part.slot);
               boolean gone = bed != null && !(realm.getBlockState(bed).getBlock() instanceof BedBlock)
                  || head != null && !(realm.getBlockState(head).getBlock() instanceof BedBlock);
               if (gone) {
                  part.bedIntact = false;
                  announce(
                     d,
                     "&c⛔ "
                        + part.displayName
                        + "'s bed was destroyed"
                        + (part.player != null ? " - " + part.player.getName().getString() + " can no longer respawn!" : "!")
                  );
                  if (bed != null) {
                     double bx = bed.getX() + 0.5;
                     double by = bed.getY() + 0.5;
                     double bz = bed.getZ() + 0.5;
                     com.fortuneandfavors.net.FfVfx.particles(realm, ParticleTypes.EXPLOSION_EMITTER, bx, by, bz, 1, 0.0, 0.0, 0.0, 0.0);
                     com.fortuneandfavors.net.FfVfx.particles(realm, ParticleTypes.LAVA, bx, by, bz, 40, 1.0, 0.8, 1.0, 0.12);
                     com.fortuneandfavors.net.FfVfx.particles(realm, ParticleTypes.END_ROD, bx, by + 0.5, bz, 24, 0.6, 0.8, 0.6, 0.08);
                     com.fortuneandfavors.net.FfVfx.particles(realm, ParticleTypes.LARGE_SMOKE, bx, by, bz, 12, 0.4, 0.4, 0.4, 0.02);
                     realm.playSound(null, bx, by, bz, (SoundEvent)SoundEvents.GENERIC_EXPLODE.value(), SoundSource.BLOCKS, 1.2F, 0.9F);
                     realm.playSound(null, bx, by, bz, SoundEvents.ANVIL_PLACE, SoundSource.BLOCKS, 1.0F, 0.7F);
                  }
               }
            }
         }

         for (Participant part : d.parts) {
            if (!part.bot && part.bedIntact) {
               boolean hasAlarm = part.bwUpgrades.getOrDefault("trap", 0) >= 1;
               boolean hasFatigue = part.bwUpgrades.getOrDefault("fatigue", 0) >= 1;
               if (hasAlarm || hasFatigue) {
                  BlockPos bed = d.arena.bedFor(part.slot);
                  if (bed != null) {
                     Participant enemy = (Participant)d.parts.get(1 - part.slot);
                     if (enemy != null
                        && enemy.player != null
                        && enemy.player.isAlive()
                        && !(enemy.player.distanceToSqr(bed.getX() + 0.5, bed.getY(), bed.getZ() + 0.5) > 36.0)
                        && now >= part.bedAlarmAt) {
                        part.bedAlarmAt = now + 100L;
                        if (hasAlarm && !part.bot && part.player != null && part.player.connection != null) {
                           part.player.connection.send(new ClientboundSetTitleTextPacket(Component.literal("§c§l⚠ BED ALARM")));
                           part.player.connection.send(new ClientboundSetTitlesAnimationPacket(2, 30, 5));
                           part.player.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal("§c" + enemy.displayName + " is at your bed!")));
                        }

                        announce(d, "§c§l⚠ Bed Alarm§r§c - " + enemy.displayName + " is at " + part.displayName + "'s bed!");
                        realm.playSound(null, bed.getX() + 0.5, bed.getY(), bed.getZ() + 0.5, SoundEvents.NOTE_BLOCK_PLING, SoundSource.BLOCKS, 1.5F, 1.6F);
                        if (hasFatigue) {
                           enemy.player.addEffect(new MobEffectInstance(MobEffects.MINING_FATIGUE, 100, 1, false, true));
                           enemy.player.connection.send(new ClientboundSetTitleTextPacket(Component.literal("§c§lMINING FATIGUE")));
                           enemy.player.connection.send(new ClientboundSetTitlesAnimationPacket(2, 40, 5));
                           enemy.player.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal("§7You tripped the bed trap!")));
                        }
                     }
                  }
               }
            }
         }
      }

      if (d.mode == DuelMode.SKYWARS && now >= d.nextRefill) {
         d.nextRefill = now + 1200L;
         d.refillCount++;
         List<ItemStack> loot = d.refillCount >= 2 ? midChestDiamond() : midChest();

         for (BlockPos chestPos : d.arena.refillChests) {
            if (realm.getBlockEntity(chestPos) instanceof ChestBlockEntity chest) {
               fillChest(chest, loot);
            }
         }

         announce(d, d.refillCount >= 2 ? "§b✨ The middle chests refilled with diamond gear!" : "§e✨ The middle chests have refilled!");
      }

      if (d.mode == DuelMode.TNTRUN) {
         tickTntrun(d, realm, now);
      }
   }

   private static void tickBedwarsGens(Duel d, ServerLevel realm, long now) {
      Arena a = d.arena;
      int genLevel = 1 + (int)Math.min(2L, (now - d.fightStartedAt) / 12000L);

      for (int slot = 0; slot < a.forgeGens.size() && slot < d.parts.size(); slot++) {
         BlockPos g = (BlockPos)a.forgeGens.get(slot);
         int tier = ((Participant)d.parts.get(slot)).bwUpgrades.getOrDefault("forge", 1);
         String type;
         int interval;
         switch (tier) {
            case 2:
               type = "iron";
               interval = 36;
               break;
            case 3:
               type = "diamond";
               interval = 160;
               break;
            case 4:
               type = "mixed4";
               interval = 160;
               break;
            case 5:
               type = "mixed5";
               interval = 100;
               break;
            default:
               type = "iron";
               interval = 60;
         }

         if (now % interval == 0L) {
            if ("iron".equals(type) && Math.random() < 0.25) {
               spawnGeneratorDrop(realm, g, "gold");
            } else if (!"mixed4".equals(type) && !"mixed5".equals(type)) {
               spawnGeneratorDrop(realm, g, type);
            } else {
               String[] types = new String[]{"iron", "gold", "diamond", "emerald"};
               float[] weights = "mixed5".equals(type) ? new float[]{0.15F, 0.2F, 0.3F, 0.35F} : new float[]{0.2F, 0.25F, 0.25F, 0.3F};
               float roll = (float)Math.random();
               float cumulative = 0.0F;

               for (int ti = 0; ti < types.length; ti++) {
                  cumulative += weights[ti];
                  if (roll < cumulative) {
                     spawnGeneratorDrop(realm, g, types[ti]);
                     break;
                  }
               }
            }
         }
      }

      for (BlockPos g : a.diamondGens) {
         int interval = switch (genLevel) {
            case 2 -> 360;
            case 3 -> 260;
            default -> 480;
         };
         if (now % interval == 0L) {
            spawnGeneratorDrop(realm, g, "diamond");
         }
      }

      for (BlockPos g : a.emeraldGens) {
         int interval = switch (genLevel) {
            case 2 -> 500;
            case 3 -> 380;
            default -> 640;
         };
         if (now % interval == 0L) {
            spawnGeneratorDrop(realm, g, "emerald");
         }
      }
   }

   private static void spawnGeneratorDrop(ServerLevel realm, BlockPos g, String type) {
      Item item = switch (type) {
         case "iron" -> Items.IRON_INGOT;
         case "gold" -> Items.GOLD_INGOT;
         case "diamond" -> Items.DIAMOND;
         default -> Items.EMERALD;
      };

      int count = switch (type) {
         case "iron" -> 2;
         case "gold" -> 1;
         default -> 1;
      };
      List<ItemEntity> existing = realm.getEntitiesOfClass(ItemEntity.class, new AABB(g).inflate(3.0), e -> e.isAlive() && e.getItem().is(item));
      if (existing.size() < 24) {
         ItemEntity drop = new ItemEntity(realm, g.getX() + 0.5, 100.3, g.getZ() + 0.5, new ItemStack(item, count));
         drop.setDeltaMovement((Math.random() - 0.5) * 0.1, 0.2, (Math.random() - 0.5) * 0.1);
         drop.setPickUpDelay(20);
         realm.addFreshEntity(drop);
      }
   }

   private static void tickTntrun(Duel d, ServerLevel realm, long now) {
      int crumbleDelay = 8;
      if (now - d.fightStartedAt >= 40L) {
         for (Participant part : d.parts) {
            Entity entity = part.bot ? part.botEntity : part.player;
            if (entity != null && entity.isAlive() && (part.bot || entity.onGround())) {
               BlockPos tile = new BlockPos((int)Math.floor(entity.getX()), (int)Math.floor(entity.getY()) - 1, (int)Math.floor(entity.getZ()));
               if (tile.getY() >= 98) {
                  BlockState st = realm.getBlockState(tile);
                  if ((st.getBlock() == Blocks.GRAVEL || st.getBlock() == Blocks.SAND) && !d.tntrunCrumble.containsKey(tile)) {
                     d.tntrunCrumble.put(tile, now + crumbleDelay);
                     com.fortuneandfavors.net.FfVfx.particles(realm, ParticleTypes.CLOUD, tile.getX() + 0.5, tile.getY() + 0.6, tile.getZ() + 0.5, 6, 0.3, 0.1, 0.3, 0.02);
                     BlockPos[] adj = new BlockPos[]{tile.north(), tile.south(), tile.east(), tile.west()};

                     for (BlockPos extra : adj) {
                        BlockState ast = realm.getBlockState(extra);
                        if ((ast.getBlock() == Blocks.GRAVEL || ast.getBlock() == Blocks.SAND) && !d.tntrunCrumble.containsKey(extra)) {
                           double px = entity.getX();
                           double pz = entity.getZ();
                           double ex = extra.getX() + 0.5;
                           double ez = extra.getZ() + 0.5;
                           if (Math.abs(px - ex) < 1.0 && Math.abs(pz - ez) < 1.0) {
                              d.tntrunCrumble.put(extra, now + crumbleDelay + 2L);
                           }
                        }
                     }
                  }
               }
            }
         }

         Iterator<Entry<BlockPos, Long>> it = d.tntrunCrumble.entrySet().iterator();

         while (it.hasNext()) {
            Entry<BlockPos, Long> entry = it.next();
            if (now >= entry.getValue()) {
               BlockPos tile = entry.getKey();
               if (realm.getBlockState(tile).getBlock() == Blocks.GRAVEL || realm.getBlockState(tile).getBlock() == Blocks.SAND) {
                  realm.setBlock(tile, Blocks.AIR.defaultBlockState(), 2);
               }

               if (realm.getBlockState(tile.below()).getBlock() == Blocks.TNT) {
                  realm.setBlock(tile.below(), Blocks.AIR.defaultBlockState(), 2);
               }

               com.fortuneandfavors.net.FfVfx.particles(realm, ParticleTypes.CLOUD, tile.getX() + 0.5, tile.getY() + 0.5, tile.getZ() + 0.5, 8, 0.3, 0.2, 0.3, 0.02);
               it.remove();
            }
         }
      }
   }

   private static void checkOutOfBounds(Duel d, boolean fighting) {
      for (Participant part : d.parts) {
         if (!part.bot && part.player != null && part.player.isAlive()) {
            ServerPlayer p = part.player;
            if (!isDuelRealm(p.level())) {
               endDuel(d, otherOf(d, part), "&c" + p.getName().getString() + " left the arena - " + otherName(d, part) + " wins!");
               return;
            }

            Arena a = d.arena;
            if (!(p.getX() >= a.x0 - 1) || !(p.getX() <= a.x1 + 1) || !(p.getZ() >= a.z0 - 1) || !(p.getZ() <= a.z1 + 1) || !(p.getY() >= a.voidY)) {
               if (fighting) {
                  // Gladiator: out of bounds is eliminated, never killed. This used to
                  // be a `Float.MAX_VALUE` blow that relied on the lethal-blow hook to
                  // absorb it - which means the rule that keeps you alive in the
                  // gladiator world was, at its most dangerous moment, a lethal blow
                  // held back by another rule. Now the fatal blow is simply never
                  // dealt: a fighter who leaves the map is put out of the match with
                  // the same ceremony as any other elimination.
                  Participant gone = participantOf(d, p);
                  if (d.mode == DuelMode.GLADIATOR && d.ffa && gone != null && !gone.eliminated) {
                     handleFfaElimination(d, gone, p, p.level().damageSources().fellOutOfWorld());
                  } else {
                     p.hurtServer(p.level(), p.level().damageSources().fellOutOfWorld(), Float.MAX_VALUE);
                  }
               } else {
                  endDuel(d, otherOf(d, part), "&c" + p.getName().getString() + " left the arena before the fight - " + otherName(d, part) + " wins!");
               }

               return;
            }
         }
      }
   }

   private static Participant participantFrom(ServerPlayer p, Duel d, int slot) {
      Participant part = new Participant();
      part.uuid = p.getUUID();
      part.slot = slot;
      part.displayName = p.getName().getString();
      part.kit = "knight";
      return part;
   }

   private static Participant botParticipant(Duel d, int slot) {
      Participant part = new Participant();
      part.bot = true;
      part.slot = slot;
      part.displayName = BOT_NAMES[(int)(Math.random() * BOT_NAMES.length)];
      // The bot picks its own kit now, and it picks like someone who intends to win:
      // a hard bot does not turn up to a Skywars island in Mage robes, and it does
      // not leave the choice to a dice roll the way it used to.
      if (d.mode == DuelMode.KITS) {
         part.kit = botPickFunKit(d.botDifficulty);
         part.kitChosen = true;
      } else if (d.mode == DuelMode.SKYWARS) {
         part.kit = botPickSkywarsKit(d.botDifficulty);
         part.kitChosen = true;
      } else {
         part.kit = "knight";
      }
      return part;
   }

   /**
    * The Kits class a bot picks for itself.
    *
    * <p>Selection rather than chance: the pool narrows as the difficulty climbs and
    * the strong classes - the ones that heal, the ones that move, the ones that hit
    * harder - survive the cut. An easy bot genuinely might pick Snowman.
    */
   private static String botPickFunKit(BotDifficulty diff) {
      String[] pool = switch (diff) {
         case EASY -> KIT_NAMES;
         case NORMAL -> new String[]{"Knight", "Archer", "Tank", "Berserker", "Mage", "Rush", "Scout", "Healer", "Ninja", "Pyro"};
         case HARD -> new String[]{"Knight", "Berserker", "Scout", "Healer", "Mage", "Enderman", "Archer"};
         case HACKER -> new String[]{"Berserker", "Scout", "Healer", "Knight"};
         case TRAIN -> KIT_NAMES;
      };
      return pool[(int)(Math.random() * pool.length)];
   }

   /** The Skywars kit a bot picks for itself, by the same reasoning. */
   private static String botPickSkywarsKit(BotDifficulty diff) {
      return switch (diff) {
         case EASY -> new String[]{"knight", "archer", "tank", "rush", "builder", "berserker", "mage"}[(int)(Math.random() * 7.0)];
         case NORMAL -> new String[]{"knight", "archer", "tank", "berserker", "mage"}[(int)(Math.random() * 5.0)];
         case HARD -> new String[]{"knight", "berserker", "tank"}[(int)(Math.random() * 3.0)];
         case HACKER -> new String[]{"berserker", "knight"}[(int)(Math.random() * 2.0)];
         case TRAIN -> "knight";
      };
   }

   private static void placeBot(Duel d, Participant part, ServerLevel realm) {
      GameProfile profile = new GameProfile(UUID.randomUUID(), part.displayName.replace("§", ""));
      DuelBot bot = new DuelBot(realm.getServer(), realm, profile);
      boolean training = isTrainingDuel(d);
      // A training dummy stands at the middle of the arena, not on a fighter's spawn: it is a
      // thing you walk up to, and a spawn corner is the one place a player would have to cross the
      // whole map to reach. Everything else about the body is identical, because it is the same
      // body - just one the duel's AI never drives.
      double[] spawn = training
         ? new double[]{(d.arena.x0 + d.arena.x1) / 2.0 + 0.5, 0.0, (d.arena.z0 + d.arena.z1) / 2.0 + 0.5}
         : d.arena.spawnFor(part.slot);
      int groundY = realm.getHeight(Types.MOTION_BLOCKING, (int)Math.floor(spawn[0]), (int)Math.floor(spawn[2])) + 1;
      bot.setPos(spawn[0], groundY, spawn[2]);
      bot.setYRot(d.arena.spawnYaw(part.slot));
      bot.trainingDummy = training;
      bot.setXRot(0.0F);
      bot.setInvulnerable(true);
      bot.invulnerableTime = 0;
      bot.hurtTime = 0;
      bot.setGameMode(GameType.SURVIVAL);
      bot.getAbilities().invulnerable = false;
      bot.getAbilities().flying = false;
      bot.getAbilities().mayfly = false;
      bot.getAbilities().instabuild = false;
      AttributeInstance hp = bot.getAttribute(Attributes.MAX_HEALTH);
      if (hp != null) {
         hp.setBaseValue(100.0);
      }

      bot.setHealth(100.0F);
      bot.setCustomName(Component.literal(training ? "§6§lTraining Dummy" : part.displayName));
      bot.setCustomNameVisible(true);
      ClientboundPlayerInfoUpdatePacket info = new ClientboundPlayerInfoUpdatePacket(EnumSet.of(Action.ADD_PLAYER), List.of(bot));

      for (ServerPlayer v : realm.getServer().getPlayerList().getPlayers()) {
         v.connection.send(info);
      }

      realm.addFreshEntity(bot);
      part.botEntity = bot;
      part.player = bot;
      d.bot = bot;
      duels.put(bot.getUUID(), d);

      try {
         ServerBossEvent bar = new ServerBossEvent(UUID.randomUUID(), Component.literal("§c⚡ " + part.displayName), BossBarColor.RED, BossBarOverlay.PROGRESS);

         for (Participant p : d.parts) {
            if (!p.bot && p.player != null) {
               bar.addPlayer(p.player);
            }
         }

         bar.setProgress(1.0F);
         part.botBossBar = bar;
      } catch (Exception var12) {
      }
   }

   private static void removeBot(Duel d, Participant part, ServerLevel realm) {
      DuelBot bot = part.botEntity;
      if (bot != null) {
         duels.remove(bot.getUUID());
         com.fortuneandfavors.net.FfVfx.particles(realm, ParticleTypes.POOF, bot.getX(), bot.getY() + 1.0, bot.getZ(), 24, 0.5, 0.6, 0.5, 0.05);

         try {
            ClientboundPlayerInfoRemovePacket pkt = new ClientboundPlayerInfoRemovePacket(List.of(bot.getUUID()));

            for (ServerPlayer v : realm.getServer().getPlayerList().getPlayers()) {
               v.connection.send(pkt);
            }
         } catch (Exception var7) {
         }

         bot.remove(RemovalReason.DISCARDED);
         if (part.botBossBar != null) {
            part.botBossBar.removeAllPlayers();
            part.botBossBar.setVisible(false);
            part.botBossBar = null;
         }

         if (d.botCrystal != null) {
            if (d.botCrystal.isAlive()) {
               d.botCrystal.discard();
            }

            d.botCrystal = null;
         }

         d.botLeapUntil = 0;
      }
   }

   public static boolean isComboFight(Entity entity) {
      if (entity instanceof Player p && isDuelRealm(p.level())) {
         Duel d = duels.get(p.getUUID());
         return d != null && d.phase == Phase.FIGHT && d.mode == DuelMode.COMBODUEL;
      } else {
         return false;
      }
   }

   private static void bumpCombo(Participant part) {
      if (part != null && part.hasPlayer() && part.player != null) {
         ServerLevel realm = getRealm(serverRef);
         if (realm != null) {
            part.comboLastHitAt = ServerClock.clock(realm);
         }

         part.comboCount++;
         int c = part.comboCount;
         bestCombos.merge(part.uuid, c, Math::max);
         String color = c >= 10 ? "§c§l" : (c >= 5 ? "§6" : (c >= 3 ? "§e" : "§f"));
         part.player.sendSystemMessage(Component.literal(color + "⚔ " + c + " Combo!"), true);
         if (c == 5 || c == 10 || c == 15) {
            float pitch = c == 5 ? 1.2F : (c == 10 ? 1.6F : 2.0F);
            part.player.playSound((SoundEvent)SoundEvents.NOTE_BLOCK_PLING.value(), 1.0F, pitch);
         }
      }
   }

   private static boolean breakCombo(Participant part) {
      if (part != null && part.hasPlayer() && part.player != null && part.comboCount > 0) {
         part.comboCount = 0;
         part.player.sendSystemMessage(Component.literal("§c✕ Combo lost!"), true);
         return true;
      } else {
         return false;
      }
   }

   public static boolean shouldBlockRandomizerDrop(ServerPlayer p, ItemStack stack) {
      return isRandomizerDuel(p) && stack != null && !stack.isEmpty() ? isWeaponish(stack.getItem()) : false;
   }

   public static boolean onPlayerAttackBot(ServerPlayer attacker, Entity target) {
      if (attacker != null && target != null && target instanceof DuelBot) {
         Duel d = duels.get(attacker.getUUID());
         if (d != null && d.phase == Phase.FIGHT) {
            Participant botPart = null;

            for (Participant part : d.parts) {
               if (part.bot && part.botEntity != null && part.botEntity.getUUID().equals(target.getUUID())) {
                  botPart = part;
                  break;
               }
            }

            if (botPart != null && botPart.botEntity != null && botPart.botEntity.isAlive()) {
               DuelBot bot = botPart.botEntity;
               ServerLevel realm = bot.level();
               if (bot.distanceToSqr(attacker) > 16.0) {
                  return false;
               }

               // A real mace smash belongs to vanilla and to nobody else. The fall
               // bonus, the shockwave, the knockback and the blast on everything
               // around the impact are all MaceItem's, and the hand-rolled path below
               // can only imitate them - it never triggered the smash, which is
               // exactly what "the mace still does not trigger on the bot" was.
               // Handing the swing back lets `Player.attack` run; it lands on the bot
               // through `hurtServer`, so the bot's own shield and armour still
               // decide what the blow costs it.
               if (attacker.getMainHandItem().is(Items.MACE) && attacker.fallDistance > 1.5) {
                  return false;
               }

               long now = ServerClock.clock(bot.level());
               long hitGap = d.mode == DuelMode.COMBODUEL ? 1L : 10L;
               // A falling mace smash is a single heavy, deliberate hit, so it
               // must not be swallowed by the light-attack pacing window. That
               // window eating the one big swing is what "the bots are immune
               // to the mace" actually was.
               boolean maceSmash = attacker.getMainHandItem().is(Items.MACE) && attacker.fallDistance > 0.5;
               if (now < botPart.botHitAt && !maceSmash) {
                  return true;
               }

               // The whole swing, in vanilla's own terms - see
               // {@link #vanillaMeleeDamage}. The crit used to be a flat 9% roll and
               // the mace's part-fall bonus a curve invented here; both are the game's
               // now, and the mace's real smash (a fall past 1.5) is handed to vanilla
               // above, where its shockwave and its sound live.
               float dmg = vanillaMeleeDamage(attacker, bot, attacker.getMainHandItem());

               // A raised shield is a raised shield whoever swings it. This path
               // applies melee on the bot by hand and used to never ask the bot's
               // shield at all, which is why a player could swing straight through
               // a blocking bot: the shield was only read on the bot's own
               // incoming path. The blow now goes through the same vanilla blocking
               // entry a player's shield uses, so the angle, the reduction and the
               // durability are the shield's rules and not a number invented here.
               dmg = botShieldAbsorb(bot, realm, realm.damageSources().playerAttack(attacker), dmg);
               if (dmg <= 0.0F) {
                  // Fully blocked: the swing landed on the shield, so it never
                  // becomes a hit on the ledger, and the pacing window stays shut
                  // so the bot cannot be machine-gunned out of its block.
                  botPart.botHitAt = now + hitGap;
                  hitFx(realm, bot, attacker);
                  return true;
               }

               // Melee on a bot is applied by hand further down, so this swing is
               // booked here - past the range, pacing and shield gates, so a swing
               // at air or into a raised shield does not count as a landed hit.
               recordHitLedger(d, attacker, botPart, dmg);

               if (d.mode == DuelMode.LASTSTAND && bot.getHealth() - dmg <= 0.5F) {
                  handleLastStandDeath(d, botPart, bot, realm.damageSources().playerAttack(attacker));
                  if (botPart.lives > 0 && bot.isAlive()) {
                     bot.setHealth(bot.getMaxHealth());
                  }

                  botPart.botHitAt = now + hitGap;
                  hitFx(realm, bot, attacker);
                  return true;
               } else {
                  if (bot.getHealth() - dmg <= 0.5F) {
                     bot.setHealth(0.0F);
                     hitFx(realm, bot, attacker);
                     Participant human = participantOf(d, attacker);
                     endDuel(d, human, "&aYou defeated " + botPart.displayName + " - the duel is yours!");
                     return true;
                  }

                  bot.setHealth(Math.max(0.0F, bot.getHealth() - dmg));
                  botPart.botHitAt = now + hitGap;
                  bot.hurtTime = 5;
                  double dx = bot.getX() - attacker.getX();
                  double dz = bot.getZ() - attacker.getZ();
                  double dist = Math.max(0.01, Math.sqrt(dx * dx + dz * dz));
                  // How hard a hit shoves is the weapon's business, not a flat
                  // number: a sprinting hit shoves harder and every level of
                  // Knockback adds half a block of it. Neither used to reach the
                  // bot, because the bot never received a real hit.
                  double kb = (d.combat == CombatStyle.LEGACY ? 0.55 : 0.4)
                     + vanillaEnchant(realm, attacker.getMainHandItem(), Enchantments.KNOCKBACK) * 0.5;
                  if (attacker.isSprinting()) {
                     kb += 0.3;
                  }
                  double lift = bot.getY() <= 101.05 ? (d.combat == CombatStyle.LEGACY ? 0.42 : 0.34) : 0.0;
                  bot.push(dx / dist * kb, lift, dz / dist * kb);
                  // A Fire Aspect blade sets the bot alight the way it sets a
                  // player alight - four seconds per level.
                  int fire = vanillaEnchant(realm, attacker.getMainHandItem(), Enchantments.FIRE_ASPECT);
                  if (fire > 0) {
                     bot.setRemainingFireTicks(Math.max(bot.getRemainingFireTicks(), fire * 80));
                  }

                  bot.hurtMarked = true;
                  hitFx(realm, bot, attacker);
                  if (d.mode == DuelMode.COMBODUEL) {
                     bumpCombo(participantOf(d, attacker));
                  }

                  try {
                     ClientboundSetEntityDataPacket sync = new ClientboundSetEntityDataPacket(bot.getId(), bot.getEntityData().getNonDefaultValues());

                     for (ServerPlayer v : realm.getPlayers(p -> p != null)) {
                        v.connection.send(sync);
                     }
                  } catch (Exception var21) {
                  }

                  return true;
               }
            } else {
               return false;
            }
         } else {
            return false;
         }
      } else {
         return false;
      }
   }

   /**
    * What the attacker's own potion effects add to a blow, in vanilla's numbers.
    *
    * <p>A duel blow is applied by the duel rather than by `Player.attack`, so the
    * entire expression has to be assembled here - and it used to be assembled out of a
    * hand-written table of "what each weapon is worth", which is a second copy of the
    * game's own numbers and only as complete as whoever wrote it remembered: it listed
    * Sharpness and nothing else, so the Smite swords the mod's own kits hand out did
    * nothing at all against a bot, and neither did Bane of Arthropods, Impaling, the
    * mace's Density, Strength, or the attack cooldown.
    *
    * <p>The numbers are asked for rather than written down, and in vanilla's own
    * order - see {@link #vanillaMeleeDamage}, which is where this ended up living.
    */
   public static float vanillaMeleeDamage(ServerPlayer attacker, Entity target, ItemStack weapon) {
      if (attacker == null || target == null || weapon == null || !(attacker.level() instanceof ServerLevel level)) {
         return 0.0F;
      }

      // 1) The attribute. This is the weapon's own modifier, Strength, Weakness and
      //    anything else that ever touches ATTACK_DAMAGE, already added up by the
      //    game: reading it is what makes a Strength potion worth anything on a bot.
      float base;
      boolean hasAttribute;
      try {
         base = (float)attacker.getAttributeValue(Attributes.ATTACK_DAMAGE);
         hasAttribute = true;
      } catch (Throwable t) {
         base = 0.0F;
         hasAttribute = false;
      }

      // No attack-damage attribute at all means this is not something that can
      // swing - not the same as an attribute that has been reduced to nothing,
      // which is a real state a heavily weakened fighter can be in.
      if (!hasAttribute) {
         return 0.0F;
      }

      float scale = 1.0F;
      try {
         scale = attacker.getAttackStrengthScale(0.5F);
      } catch (Throwable ignored) {
      }

      scale = Math.max(0.0F, Math.min(1.0F, scale));
      // 2) The weapon's own damage source, so an item that changes it - the mace's
      //    smash is one - is handed the source it recognises rather than a generic
      //    player attack.
      DamageSource source = level.damageSources().playerAttack(attacker);
      try {
         source = weapon.getDamageSource(attacker);
      } catch (Throwable ignored) {
      }

      // 3) The charge, as Player.baseDamageScaleFactor computes it: an uncharged
      //    swing is worth a fifth of the weapon and a full one all of it. A duel blow
      //    used to be worth its full value however fast it was clicked, which is the
      //    one rule the bot's own blows have always obeyed.
      float dmg = base * (0.2F + scale * scale * 0.8F);
      // 4) Enchantment damage - Sharpness, Smite, Bane, Impaling - lives on the
      //    weapon and is applied through the *victim*, which a blow the duel applies
      //    by hand never reaches. It is the reason the mod's own tables had to list
      //    Sharpness by hand and why Smite swords did nothing against a bot.
      //
      //    Note the order: Player.attack computes this against the *unscaled* base
      //    and scales it by the charge, then folds it in after the crit. Whether an
      //    enchantment fires at all is the game's own business - the conditions live
      //    in its enchantment data (Smite and Bane are target-typed, Impaling wants
      //    the victim in water) - so an enchanted blade is worth whatever the game
      //    says it is worth against the thing in front of it.
      float enchanted = 0.0F;
      try {
         enchanted = (EnchantmentHelper.modifyDamage(level, weapon, target, source, base) - base) * scale;
      } catch (Throwable ignored) {
      }

      // Vanilla only abandons the swing when there is neither a base nor an
      // enchant bonus to apply - so a Sharpness blade still bites under enough
      // Weakness to take the attribute to zero, which is exactly what a player
      // over the arena would get.
      if (dmg <= 0.0F && enchanted <= 0.0F) {
         return 0.0F;
      }

      // 5) The item's own attack bonus: the mace's fall bonus, Density included,
      //    rather than a fall-distance curve written out here.
      try {
         dmg += weapon.getItem().getAttackDamageBonus(target, dmg, source);
      } catch (Throwable ignored) {
      }

      // 6) The crit, at vanilla's own full charge (Player.attack only rolls one above
      //    scale 0.9) on vanilla's own conditions.
      if (scale > 0.9F && isRealCrit(attacker)) {
         dmg *= 1.5F;
      }

      return Math.max(0.0F, dmg + enchanted);
   }

   /** The level of a vanilla enchantment on a stack, or 0 when there is none. */
   private static int vanillaEnchant(
      ServerLevel realm, ItemStack stack, net.minecraft.resources.ResourceKey<Enchantment> key
   ) {
      try {
         return EnchantmentHelper.getItemEnchantmentLevel(
            realm.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(key), stack
         );
      } catch (Throwable t) {
         return 0;
      }
   }

   /**
    * Vanilla's own critical-hit conditions: a real fall, off the ground, not
    * climbing, swimming, riding, sprinting or blinded. A crit should be something
    * a duelist does, and this replaced a flat dice roll for exactly that reason.
    */
   private static boolean isRealCrit(ServerPlayer attacker) {
      return attacker.fallDistance > 0.0F
         && !attacker.onGround()
         && !attacker.onClimbable()
         && !attacker.isInWater()
         && !attacker.isPassenger()
         && !attacker.isSprinting()
         && !attacker.hasEffect(MobEffects.BLINDNESS)
         && !attacker.getAbilities().flying;
   }

   private static void hitFx(ServerLevel realm, DuelBot bot, ServerPlayer attacker) {
      com.fortuneandfavors.net.FfVfx.particles(realm, ParticleTypes.CRIT, bot.getX(), bot.getY() + 1.2, bot.getZ(), 10, 0.3, 0.5, 0.3, 0.1);
      com.fortuneandfavors.net.FfVfx.particles(realm, ParticleTypes.SMOKE, bot.getX(), bot.getY() + 1.1, bot.getZ(), 6, 0.2, 0.3, 0.2, 0.02);
      realm.playSound(null, bot.getX(), bot.getY(), bot.getZ(), SoundEvents.PLAYER_ATTACK_STRONG, SoundSource.PLAYERS, 1.0F, 1.0F);
      realm.playSound(null, bot.getX(), bot.getY(), bot.getZ(), SoundEvents.PLAYER_HURT, SoundSource.PLAYERS, 1.0F, 0.9F);

      try {
         for (ServerPlayer v : realm.getPlayers(p -> p != null)) {
            v.connection.send(new ClientboundHurtAnimationPacket(bot));
         }

         realm.getServer().getPlayerList().broadcastAll(new ClientboundAnimatePacket(attacker, 0));
      } catch (Exception var5) {
      }
   }

   private static Participant participantOf(Duel d, ServerPlayer p) {
      if (d != null && p != null) {
         for (Participant part : d.parts) {
            // Only a real player can be matched by uuid; a bot's opponent slot is
            // found through its entity, not through this. (The order matters: the
            // uuid test would be a null dereference on the bot's own participant.)
            if (part.hasPlayer() && part.uuid.equals(p.getUUID())) {
               return part;
            }
         }

         return null;
      } else {
         return null;
      }
   }

   private static Participant otherParticipant(Duel d, ServerPlayer p) {
      if (d != null && p != null) {
         for (Participant part : d.parts) {
            if (part.bot && part.botEntity != null && part.botEntity.getUUID().equals(p.getUUID())) {
               return part;
            }

            if (!part.bot && !part.uuid.equals(p.getUUID())) {
               return part;
            }
         }

         return null;
      } else {
         return null;
      }
   }

   private static String otherName(Duel d, Participant part) {
      Participant other = otherOf(d, part);
      return other != null && other.displayName != null ? other.displayName : "the other fighter";
   }

   private static Participant otherOf(Duel d, Participant part) {
      for (Participant p : d.parts) {
         if (p != part) {
            return p;
         }
      }

      return null;
   }

   private static void announce(Duel d, String msg) {
      for (Participant part : d.parts) {
         if (!part.bot) {
            ServerPlayer p = serverRef != null ? serverRef.getPlayerList().getPlayer(part.uuid) : null;
            if (p != null) {
               Chat.raw(p, msg);
            }
         }
      }
   }

   private static void tickBot(Duel d, ServerLevel realm, long now) {
      DuelBot bot = d.bot;
      if (bot != null) {
         if (bot.trainingDummy) {
            tickTrainingDummy(d, bot, realm);
            return;
         }
         if (bot.isAlive() && !(bot.getHealth() <= 0.0F)) {
            if (d.phase == Phase.FIGHT) {
               if (bot.falling) {
                  // The falling itself is vanilla's - this is a player's tick, and
                  // LivingEntity.travel already has the gravity, the drag and the
                  // collision. Integrating it here as well was a second gravity, which
                  // is why the bot's drops came in faster than a player's. The one
                  // thing physics cannot know is where this arena's floor of no return
                  // is.
                  if (bot.onGround()) {
                     bot.falling = false;
                     com.fortuneandfavors.net.FfVfx.particles(realm, ParticleTypes.CLOUD, bot.getX(), bot.getY(), bot.getZ(), 4, 0.3, 0.1, 0.3, 0.02);
                  } else if (bot.getY() < d.arena.voidY) {
                     bot.falling = false;
                     bot.setHealth(0.0F);
                     bot.discard();
                     return;
                  }
               } else {
                  if (now % 2L == 0L) {
                     for (Participant part : d.parts) {
                        if (part.bot && part.botBossBar != null) {
                           part.botBossBar.setProgress(Math.max(0.0F, Math.min(1.0F, bot.getHealth() / bot.getMaxHealth())));
                        }
                     }
                  }

                  // Nothing to integrate here any more. The velocity every shove
                  // in the game writes - a sprint hit, the Knockback enchantment,
                  // a mace smash, an explosion, a fishing rod, the mod's own gear -
                  // is carried by the bot's own player tick now, through the same
                  // vanilla physics the player across the arena is running:
                  // gravity, friction, collision and landing.
                  if (!bot.falling) {
                     // Only a real drop counts. This used to fire for any airborne tick
                     // with a column of air under it - which is every tick of every
                     // jump - and while `falling` is set the AI stands down completely,
                     // so the bot froze at the top of its own leap and could not land a
                     // mace smash or a fall-crit on the way down. The line the arena
                     // cares about is the floor of no return, not the floor of the
                     // moment.
                     if (bot.getY() < d.arena.voidY + 0.5) {
                        bot.falling = true;
                     } else {
                        // Standing on the floor: whatever downwards velocity the bot
                        // banked on the way down has to go, the way landing cancels it
                        // for a player. Left alone it grows every tick, and the next
                        // knockback has to spend itself cancelling a fall that already
                        // ended. The descent in between is vanilla's - nothing to
                        // integrate by hand.
                        Vec3 standing = bot.getDeltaMovement();
                        if (bot.onGround() && standing.y < 0.0) {
                           bot.setDeltaMovement(standing.x, 0.0, standing.z);
                        }
                     }
                  }

                  bot.setInvulnerable(false);
                  bot.getAbilities().invulnerable = false;
                  bot.getAbilities().flying = false;
                  bot.getAbilities().mayfly = false;
                  if (bot.connection != null && now % 10L == 0L) {
                     bot.connection.send(new ClientboundPlayerAbilitiesPacket(bot.getAbilities()));
                  }

                  if (bot.gameMode.getGameModeForPlayer() != GameType.SURVIVAL) {
                     bot.setGameMode(GameType.SURVIVAL);
                  }

                  if (now % 20L == 0L) {
                     bot.setItemSlot(EquipmentSlot.MAINHAND, bot.getMainHandItem().copy());
                  }

                  if (now % 6L == 0L && bot.isAlive()) {
                     try {
                        for (ServerPlayer v : realm.getPlayers(p -> p != null && !p.equals(bot))) {
                           v.connection.send(new ClientboundSetEntityMotionPacket(bot.getId(), bot.getDeltaMovement()));
                        }
                     } catch (Exception var14) {
                     }
                  }

                  // There used to be a second test here - "air under the feet and no
                  // wool to bridge with" - which is also true for the whole of every
                  // jump. The two places that genuinely leave the map (the void, and
                  // stepping off a bridge with nothing to place) set this themselves.
                  {

                     Participant humanPart = null;

                     for (Participant part : d.parts) {
                        if (!part.bot) {
                           humanPart = part;
                           break;
                        }
                     }

                     if (humanPart != null && humanPart.player != null && humanPart.player.isAlive()) {
                        ServerPlayer target = humanPart.player;
                        // The housekeeping every bot does whatever the mode: keep the
                        // best sword in the slot the AI swings from, keep a totem in
                        // the free hand, and make room in the bag.
                        if (now >= d.botManageAt) {
                           d.botManageAt = now + 16L;
                           botManageInventory(bot);
                        }

                        if (d.mode == DuelMode.TNTRUN) {
                           botTntrunWander(d, bot, realm, target, now);
                        } else if (d.mode == DuelMode.SKYWARS || d.mode == DuelMode.GLADIATOR) {
                           botGatherTurn(d, bot, realm, target, now);
                        } else if (d.mode == DuelMode.BEDWARS) {
                           botBedwarsTurn(d, bot, realm, target, now, humanPart);
                        } else {
                           botFight(d, bot, realm, target, now);
                        }
                     }
                  }
               }
            }
         } else {
            if (d.phase == Phase.FIGHT) {
               Participant human = null;

               for (Participant part : d.parts) {
                  if (!part.bot) {
                     human = part;
                     break;
                  }
               }

               endDuel(d, human, "&aYou defeated the bot - the duel is yours!");
            }
         }
      }
   }

   /**
    * Skywars and Gladiator: loot first, fight when the opponent is on top of you.
    *
    * <p>Both modes used to send the bot on a beeline for the nearest cache - which
    * reads as "rush" - and then straight into the fight. A Skywars duel is decided
    * by who is holding what when the two of you meet, so this keeps looting while
    * there is a chest worth walking to and the opponent is still far, and only
    * turns to fight when the gap closes or the map runs out of caches. Gladiator
    * hands the bot nothing at all, so it may never stop looting.
    */
   private static void botGatherTurn(Duel d, DuelBot bot, ServerLevel realm, ServerPlayer target, long now) {
      boolean glad = d.mode == DuelMode.GLADIATOR;
      botPickup(realm, bot);
      double enemyDist = Math.sqrt(bot.distanceToSqr(target));

      // Somebody standing in the bot's face is the fight, whatever is still left to
      // loot. Gladiator short-circuited the "is the opponent far" test - a chest
      // within reach was always worth walking to - so the bot crossed the map from
      // cache to cache for the whole match and never once turned around, which read,
      // correctly, as the bot doing nothing.
      if (enemyDist < (glad ? 7.5 : 4.5)) {
         botFight(d, bot, realm, target, now);
         return;
      }

      BlockPos chest = nearestLootableChest(d, realm, bot);
      double chestDist = chest == null
         ? Double.MAX_VALUE
         : Math.sqrt(bot.distanceToSqr(chest.getX() + 0.5, chest.getY(), chest.getZ() + 0.5));
      // A gladiator world is eighteen chunks wide, so its caches are a long walk
      // apart; anywhere it can see one is worth crossing to.
      double reach = glad ? 110.0 : 24.0;
      boolean gathering = chest != null && chestDist < reach && (glad || enemyDist > 9.0);

      if (gathering) {
         if (chestDist > 1.6) {
            botMoveTo(realm, bot, chest.getX() + 0.5, chest.getZ() + 0.5, target, d, now);
         } else {
            lootChest(d, bot, realm, chest);
         }
         return;
      }

      // Out of caches. The map's ore is the other half of the mode - it is in the
      // ground precisely so somebody digs it up - and the bot had no way to touch
      // it, so a bot that ran out of chests simply wandered. It mines what it can
      // reach now, and a bot with metal but no blade forges one, which is the same
      // "gear up" the caches offer the player.
      if (glad && (botWorkCamp(d, bot, realm, now) || botMineNearby(d, bot, realm, target, now))) {
         return;
      }

      botFight(d, bot, realm, target, now);
   }

   /** The ore blocks a gladiator map puts in the ground. */
   private static boolean gladOre(BlockState state) {
      Block b = state.getBlock();
      return b == Blocks.COAL_ORE
         || b == Blocks.IRON_ORE
         || b == Blocks.GOLD_ORE
         || b == Blocks.DIAMOND_ORE
         || b == Blocks.REDSTONE_ORE
         || b == Blocks.DEEPSLATE_IRON_ORE
         || b == Blocks.DEEPSLATE_GOLD_ORE;
   }

   /**
    * What comes out of a gladiator vein.
    *
    * <p>Raw, exactly as it would come out of the ground for a player. The ore used
    * to arrive pre-smelted with a note explaining that the bot had no furnace - and
    * the note was the bug. A bot that gets ingots handed to it is a bot that skips
    * two thirds of the mode: mining is the digging, smelting is the furnace, and
    * crafting is the blade, and each one costs the person doing it real time. It has
    * a furnace and a table in its kit now, so it can pay for all three.
    */
   private static ItemStack gladOreDrop(BlockState state) {
      Block b = state.getBlock();
      if (b == Blocks.COAL_ORE) {
         return new ItemStack(Items.COAL, 1 + (int)(Math.random() * 2.0));
      }
      if (b == Blocks.IRON_ORE || b == Blocks.DEEPSLATE_IRON_ORE) {
         return new ItemStack(Items.RAW_IRON);
      }
      if (b == Blocks.GOLD_ORE || b == Blocks.DEEPSLATE_GOLD_ORE) {
         return new ItemStack(Items.RAW_GOLD);
      }
      if (b == Blocks.DIAMOND_ORE) {
         return new ItemStack(Items.DIAMOND);
      }
      if (b == Blocks.REDSTONE_ORE) {
         return new ItemStack(Items.REDSTONE, 3 + (int)(Math.random() * 3.0));
      }
      return ItemStack.EMPTY;
   }

   /**
    * Digs the nearest vein the bot can already see.
    *
    * <p>One block at a time, with a beat between them and a real block break, so a
    * bot that has run out of caches is still a person working a seam rather than a
    * statue in a field.
    */
   private static boolean botMineNearby(Duel d, DuelBot bot, ServerLevel realm, ServerPlayer target, long now) {
      BlockPos origin = bot.blockPosition();
      BlockPos best = null;
      double bestDist = 20.0;

      for (BlockPos p : BlockPos.betweenClosed(origin.offset(-4, -3, -4), origin.offset(4, 2, 4))) {
         if (!gladOre(realm.getBlockState(p))) {
            continue;
         }

         double dist = bot.distanceToSqr(p.getX() + 0.5, p.getY(), p.getZ() + 0.5);
         if (dist < bestDist) {
            bestDist = dist;
            best = p.immutable();
         }
      }

      if (best == null) {
         return false;
      }

      if (bestDist > 6.5) {
         botMoveTo(realm, bot, best.getX() + 0.5, best.getZ() + 0.5, target, d, now);
         return true;
      }

      if (now < d.botMineAt) {
         return true;
      }

      BlockState state = realm.getBlockState(best);
      ItemStack drop = gladOreDrop(state);
      realm.destroyBlock(best, false);
      if (!drop.isEmpty()) {
         addToInventory(bot, drop);
      }

      realm.playSound(null, best.getX() + 0.5, best.getY() + 0.5, best.getZ() + 0.5, SoundEvents.STONE_BREAK, SoundSource.BLOCKS, 0.8F, 1.0F);
      com.fortuneandfavors.net.FfVfx.particles(realm, ParticleTypes.CRIT, best.getX() + 0.5, best.getY() + 0.5, best.getZ() + 0.5, 6, 0.25, 0.25, 0.25, 0.02);
      d.botMineAt = now + 14L;
      return true;
   }

   /** Vanilla's own smelt time for one furnace burn, in ticks. */
   private static final int BOT_SMELT_TICKS = 200;
   /** How long a blade takes to put together at a table. */
   private static final int BOT_CRAFT_TICKS = 60;
   /**
    * How many items one furnace burn of the bot's converts.
    *
    * <p>A real furnace runs one item per 200 ticks; a duel is capped at half an
    * hour and a bot that spends ten seconds per ingot is not gearing up, it is
    * standing still. One burn therefore does the sitting's worth at once, which is
    * the deal a person makes with a furnace they lit while mining.
    */
   private static final int BOT_SMELT_BATCH = 4;

   /** True when this stack is an ore that has to go through a furnace. */
   private static Item smeltProduct(Item raw) {
      if (raw == Items.RAW_IRON) {
         return Items.IRON_INGOT;
      }
      if (raw == Items.RAW_GOLD) {
         return Items.GOLD_INGOT;
      }
      if (raw == Items.RAW_COPPER) {
         return Items.COPPER_INGOT;
      }
      return null;
   }

   /** True when the bot is holding something a furnace would improve. */
   private static boolean botHasRawOre(DuelBot bot) {
      for (int i = 0; i < BOT_BACKPACK; i++) {
         ItemStack s = bot.getInventory().getItem(i);
         if (!s.isEmpty() && smeltProduct(s.getItem()) != null) {
            return true;
         }
      }
      return false;
   }

   /** Coal first, then anything else that burns. Null when there is no fuel at all. */
   private static Item botFuel(DuelBot bot) {
      if (InventoryHelper.countItems(bot, Items.COAL) > 0) {
         return Items.COAL;
      }
      if (InventoryHelper.countItems(bot, Items.CHARCOAL) > 0) {
         return Items.CHARCOAL;
      }
      if (InventoryHelper.countItems(bot, Items.OAK_PLANKS) > 0) {
         return Items.OAK_PLANKS;
      }
      if (InventoryHelper.countItems(bot, Items.OAK_LOG) > 0) {
         return Items.OAK_LOG;
      }
      return null;
   }

   /**
    * The bot's camp: mine, smelt, craft - in that order, each one taking real time.
    *
    * <p>Gearing up used to be one instant step: ore went in, a blade came out, and
    * the bot was armed in the same tick it swung the pick. That is not gearing up,
    * that is a crafting table with no crafting. It walks the whole chain now, and
    * every link costs it something a person would also pay:
    *
    * <ul>
    *   <li>a furnace and a table have to be <b>placed</b>, as real blocks in the
    *       world, which is the moment the fight can catch it with its hands full;
    *   <li>smelting takes a furnace's own burn and needs <b>fuel</b>, so the coal it
    *       dug on the way is spoken for rather than decorative;
    *   <li>crafting takes a beat at the table, and the ingots are spent.
    * </ul>
    *
    * <p>And it stands still while it works. That is the survival limitation the mode
    * is asking for: a bot with a full bag and an empty sword is a bot that has to
    * stop, set up, and be somewhere with its back turned.
    *
    * @return true when it is busy at the camp this tick
    */
   private static boolean botWorkCamp(Duel d, DuelBot bot, ServerLevel realm, long now) {
      // A job in progress owns the bot until it finishes.
      if (d.botWorkUntil > 0L) {
         if (now < d.botWorkUntil) {
            // Standing at the bench is the cost. It crouches, so the pose reads as
            // working rather than as a bot that has frozen.
            bot.setShiftKeyDown(true);
            if (now % 12L == 0L) {
               BlockPos at = d.botWorkKind == 1 ? d.botCampFurnace : d.botCampTable;
               if (at != null) {
                  realm.playSound(
                     null, at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5,
                     d.botWorkKind == 1 ? SoundEvents.FURNACE_FIRE_CRACKLE : SoundEvents.WOOD_HIT,
                     SoundSource.BLOCKS, 0.7F, 1.0F
                  );
               }
            }
            return true;
         }

         finishBotWork(d, bot, realm);
         return true;
      }

      // Nothing worth doing once the blade is past what the ground can supply, and
      // nothing to smelt either.
      if (botWeaponRank(bot.getInventory().getItem(0)) >= 38 && !botHasRawOre(bot)) {
         return false;
      }

      if (!ensureBotCamp(d, bot, realm)) {
         return false;
      }

      // Smelting comes first: ingots are what a blade is made of, and raw ore is
      // worth nothing in a fight.
      if (botHasRawOre(bot) && botFuel(bot) != null) {
         d.botWorkKind = 1;
         d.botWorkUntil = now + BOT_SMELT_TICKS;
         return true;
      }

      if (botHasIngotsForBlade(bot)) {
         d.botWorkKind = 2;
         d.botWorkUntil = now + BOT_CRAFT_TICKS;
         return true;
      }

      return false;
   }

   /** True when the bot is carrying enough metal to make a blade worth making. */
   private static boolean botHasIngotsForBlade(DuelBot bot) {
      int ingots = InventoryHelper.countItems(bot, Items.IRON_INGOT);
      if (ingots >= 3 && !hasSwordAtLeast(bot, Items.IRON_SWORD)) {
         return true;
      }
      return ingots >= 2 && !hasSwordAtLeast(bot, Items.STONE_SWORD);
   }

   /**
    * Puts a furnace and a table down, if the bot still has them to put down.
    *
    * <p>Both are real blocks at real coordinates, two steps from the bot, so the
    * camp is visible on the map and breakable by anyone who finds it. That is the
    * point: a player who catches the bot mid-smelt can knock the furnace over.
    */
   private static boolean ensureBotCamp(Duel d, DuelBot bot, ServerLevel realm) {
      if (d.botCampFurnace != null && d.botCampTable != null) {
         return true;
      }

      BlockPos base = bot.blockPosition();
      int[] offsets = {1, 2, -1, -2};
      for (int off : offsets) {
         for (int dz = -1; dz <= 1; dz++) {
            BlockPos spot = base.offset(off, 0, dz);
            BlockPos floor = spot.below();
            if (!realm.getBlockState(spot).isAir() || realm.getBlockState(floor).isAir()) {
               continue;
            }
            if (d.botCampFurnace == null && InventoryHelper.countItems(bot, Items.FURNACE) > 0) {
               InventoryHelper.removeItems(bot, Items.FURNACE, 1);
               botPlaceBlock(d, realm, spot, Blocks.FURNACE.defaultBlockState());
               realm.playSound(null, spot.getX() + 0.5, spot.getY() + 0.5, spot.getZ() + 0.5, SoundEvents.STONE_PLACE, SoundSource.BLOCKS, 0.8F, 1.0F);
               d.botCampFurnace = spot.immutable();
               continue;
            }
            if (d.botCampTable == null && InventoryHelper.countItems(bot, Items.CRAFTING_TABLE) > 0) {
               InventoryHelper.removeItems(bot, Items.CRAFTING_TABLE, 1);
               botPlaceBlock(d, realm, spot, Blocks.CRAFTING_TABLE.defaultBlockState());
               realm.playSound(null, spot.getX() + 0.5, spot.getY() + 0.5, spot.getZ() + 0.5, SoundEvents.WOOD_PLACE, SoundSource.BLOCKS, 0.8F, 1.0F);
               d.botCampTable = spot.immutable();
            }
         }
      }

      return d.botCampFurnace != null || d.botCampTable != null;
   }

   /**
    * Hands over whatever the finished job produced.
    *
    * <p>Smelting converts a sitting's worth of ore and burns one fuel - the coal is
    * gone either way, which is what makes a bot with no fuel a bot with rock in its
    * bag. Crafting spends ingots on a blade: three is an iron sword, two makes do
    * with stone, and the quantities are deliberately small because this exists to
    * arm a bot that has worked a seam rather than to open a smithy.
    */
   private static void finishBotWork(Duel d, DuelBot bot, ServerLevel realm) {
      d.botWorkUntil = 0L;
      bot.setShiftKeyDown(false);
      BlockPos at = d.botWorkKind == 1 ? d.botCampFurnace : d.botCampTable;

      if (d.botWorkKind == 1) {
         Item fuel = botFuel(bot);
         int produced = 0;
         for (int i = 0; i < BOT_BACKPACK && produced < BOT_SMELT_BATCH; i++) {
            ItemStack s = bot.getInventory().getItem(i);
            if (s.isEmpty()) {
               continue;
            }
            Item out = smeltProduct(s.getItem());
            if (out == null) {
               continue;
            }
            int n = Math.min(s.getCount(), BOT_SMELT_BATCH - produced);
            s.shrink(n);
            if (s.isEmpty()) {
               bot.getInventory().setItem(i, ItemStack.EMPTY);
            }
            addToInventory(bot, new ItemStack(out, n));
            produced += n;
         }

         if (produced > 0 && fuel != null) {
            InventoryHelper.removeItems(bot, fuel, 1);
         }

         if (at != null) {
            com.fortuneandfavors.net.FfVfx.particles(realm, ParticleTypes.FLAME, at.getX() + 0.5, at.getY() + 1.0, at.getZ() + 0.5, 8, 0.2, 0.2, 0.2, 0.01);
            com.fortuneandfavors.net.FfVfx.particles(realm, ParticleTypes.SMOKE, at.getX() + 0.5, at.getY() + 1.1, at.getZ() + 0.5, 6, 0.15, 0.2, 0.15, 0.01);
         }
      } else {
         int ingots = InventoryHelper.countItems(bot, Items.IRON_INGOT);
         if (ingots >= 3 && !hasSwordAtLeast(bot, Items.IRON_SWORD)) {
            InventoryHelper.removeItems(bot, Items.IRON_INGOT, 3);
            bot.getInventory().setItem(1, new ItemStack(Items.IRON_SWORD));
         } else if (ingots >= 2 && !hasSwordAtLeast(bot, Items.STONE_SWORD)) {
            InventoryHelper.removeItems(bot, Items.IRON_INGOT, 2);
            bot.getInventory().setItem(1, new ItemStack(Items.STONE_SWORD));
         }

         if (at != null) {
            com.fortuneandfavors.net.FfVfx.particles(realm, ParticleTypes.CRIT, at.getX() + 0.5, at.getY() + 1.0, at.getZ() + 0.5, 10, 0.25, 0.25, 0.25, 0.02);
         }
      }

      realm.playSound(null, bot.getX(), bot.getY(), bot.getZ(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.6F, 1.4F);
   }

   /**
    * Bedwars: hold the base, gather what the generators drop, buy gear, and only
    * then push.
    *
    * <p>The bot's whole Bedwars plan used to be "run at the enemy bed". It left its
    * own bed open, it never stood on its own generator long enough to bank
    * anything, and it pushed the moment the match started, which is the opposite of
    * how the mode is played. This is the actual shape of a Bedwars turn: defend
    * when the base is threatened or the kit is not ready, otherwise go for the bed.
    */
   private static void botBedwarsTurn(Duel d, DuelBot bot, ServerLevel realm, ServerPlayer target, long now, Participant enemy) {
      botPickup(realm, bot);
      Participant self = botPartOf(d, bot);
      botBedwarsBuy(d, bot, self, now);

      BlockPos ownBed = self == null ? null : d.arena.bedFor(self.slot);
      double bedThreat = ownBed == null
         ? Double.MAX_VALUE
         : Math.sqrt(target.distanceToSqr(ownBed.getX() + 0.5, ownBed.getY(), ownBed.getZ() + 0.5));
      boolean enemyAtBase = bedThreat < 16.0;
      boolean geared = hasSwordAtLeast(bot, Items.IRON_SWORD);
      boolean hasBlocks = botHasItem(bot, whiteWool());
      boolean bedGone = self != null && !self.bedIntact;
      double enemyDist = Math.sqrt(bot.distanceToSqr(target));

      if (now % 20L == 0L) {
         // Defend while the base is threatened or the bot has nothing to leave it
         // with; push once it can actually win the walk across the map. A bot whose
         // own bed is gone has nothing left to defend, so it stops pretending.
         d.botBwRole = bedGone ? 2 : (enemyAtBase || !geared || !hasBlocks ? 1 : 2);
      }

      if (d.botBwRole == 1) {
         if (enemyAtBase) {
            // Nobody builds or shops while their bed is being broken.
            botFight(d, bot, realm, target, now);
            return;
         }

         // An unguarded bed is a lost bed. This is the part the bot never did: it
         // stood on its generator with a stack of wool in its bag and let the player
         // walk up to a bare bed. It walls the bed in first now, one block at a
         // time, and only then goes back to gathering.
         if (ownBed != null && now >= d.botBwWallAt + 10L && botBedwarsDefendBed(d, bot, realm, ownBed, self, now)) {
            return;
         }

         BlockPos forge = self != null && d.arena.forgeGens.size() > self.slot
            ? d.arena.forgeGens.get(self.slot)
            : (d.arena.forgeGens.isEmpty() ? ownBed : d.arena.forgeGens.get(0));
         if (forge != null && Math.sqrt(bot.distanceToSqr(forge.getX() + 0.5, forge.getY(), forge.getZ() + 0.5)) > 2.5) {
            botMoveTo(realm, bot, forge.getX() + 0.5, forge.getZ() + 0.5, target, d, now);
            return;
         }

         // On its own generator with nothing to chase, unless the player walks in -
         // then the fight comes to the base and it answers there.
         if (enemyDist < 10.0) {
            botFight(d, bot, realm, target, now);
         }
         return;
      }

      if (enemy != null && enemy.bedIntact && d.arena.bedFor(enemy.slot) != null) {
         BlockPos bed = d.arena.bedFor(enemy.slot);
         double bedDist = Math.sqrt(bot.distanceToSqr(bed.getX() + 0.5, bed.getY(), bed.getZ() + 0.5));
         if (bedDist < 6.0) {
            realm.destroyBlock(bed, true);
            BlockPos head = d.arena.bedHeadFor(enemy.slot);
            if (head != null) {
               realm.destroyBlock(head, true);
            }

            enemy.bedIntact = false;
            announce(d, "&c⛔ " + bot.getScoreboardName() + " destroyed " + enemy.displayName + "'s bed!");
            return;
         }

         // A push is not a walk past somebody. The bot used to march at the enemy
         // bed with the player standing in front of it and never swing, which is how
         // a bot "pushes" itself into a death it could have answered.
         if (enemyDist < 5.0) {
            botFight(d, bot, realm, target, now);
            return;
         }

         botMoveTo(realm, bot, bed.getX() + 0.5, bed.getZ() + 0.5, target, d, now);
         return;
      }

      botFight(d, bot, realm, target, now);
   }

   /** The bot's own participant entry - the other half of {@link #participantOf}. */
   private static Participant botPartOf(Duel d, DuelBot bot) {
      if (d == null || bot == null) {
         return null;
      }

      for (Participant part : d.parts) {
         if (part.bot && part.botEntity != null && part.botEntity.getUUID().equals(bot.getUUID())) {
            return part;
         }
      }

      return null;
   }

   /**
    * Walls the bot's own bed in with the wool it bought.
    *
    * <p>One real block in one real place, a beat apart, exactly as a person lays a
    * bed defence: the ring of four neighbours at the bed's own level, skipping any
    * that already have something in them. Public so the self-test can pin the shape.
    *
    * @return true when a block went down this tick
    */
   private static boolean botBedwarsDefendBed(Duel d, DuelBot bot, ServerLevel realm, BlockPos bed, Participant self, long now) {
      if (bed == null || !botHasItem(bot, whiteWool())) {
         return false;
      }

      BlockPos head = self == null ? null : d.arena.bedHeadFor(self.slot);

      for (BlockPos anchor : new BlockPos[]{bed, head}) {
         if (anchor == null) {
            continue;
         }

         for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos p = anchor.relative(dir);
            if (p.equals(bed) || p.equals(head) || !realm.getBlockState(p).isAir()) {
               continue;
            }

            botTakeItem(bot, whiteWool(), 1);
            botPlaceBlock(d, realm, p, ((Block)Blocks.WOOL.white()).defaultBlockState());
            realm.playSound(null, p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5, SoundEvents.WOOL_PLACE, SoundSource.BLOCKS, 0.6F, 1.1F);
            d.botBwWallAt = now;
            return true;
         }
      }

      return false;
   }

   private static void botBedwarsBuy(Duel d, DuelBot bot, Participant part, long now) {
      if (now % 40L == 0L) {
         // Its own entry, found by identity. This used to be "whatever part is at
         // index 1", which in a free-for-all is somebody else's team - the bot bought
         // its upgrades onto another player and then wondered why it was still
         // unarmed.
         if (part != null && part.bot) {
            int diamonds = InventoryHelper.countItems(bot, Items.DIAMOND);
            if (diamonds >= 2 && part.bwUpgrades.getOrDefault("sharpness", 0) < 1) {
               InventoryHelper.removeItems(bot, Items.DIAMOND, 2);
               part.bwUpgrades.put("sharpness", 1);
               applyTeamEnchants(bot, part);
            } else if (diamonds >= 2 && part.bwUpgrades.getOrDefault("protection", 0) < 1) {
               InventoryHelper.removeItems(bot, Items.DIAMOND, 2);
               part.bwUpgrades.put("protection", 1);
               applyTeamEnchants(bot, part);
            } else {
               int gold = InventoryHelper.countItems(bot, Items.GOLD_INGOT);
               if (gold >= 7 && !hasSwordAtLeast(bot, Items.IRON_SWORD)) {
                  InventoryHelper.removeItems(bot, Items.GOLD_INGOT, 7);
                  bot.getInventory().setItem(0, new ItemStack(Items.IRON_SWORD));
                  bot.getInventory().setSelectedSlot(0);
                  applyTeamEnchants(bot, part);
               } else {
                  int iron = InventoryHelper.countItems(bot, Items.IRON_INGOT);
                  if (iron >= 4 && !botHasItem(bot, whiteWool())) {
                     InventoryHelper.removeItems(bot, Items.IRON_INGOT, 4);
                     bot.getInventory().add(new ItemStack(whiteWool(), 16));
                  } else {
                     if (iron >= 10 && !hasSwordAtLeast(bot, Items.STONE_SWORD)) {
                        InventoryHelper.removeItems(bot, Items.IRON_INGOT, 10);
                        bot.getInventory().setItem(0, new ItemStack(Items.STONE_SWORD));
                        bot.getInventory().setSelectedSlot(0);
                        applyTeamEnchants(bot, part);
                     }
                  }
               }
            }
         }
      }
   }

   private static boolean modeGrantsGapples(DuelMode mode) {
      return mode != DuelMode.TNTRUN && mode != DuelMode.RANDOMIZER && mode != DuelMode.COMBODUEL;
   }

   private static boolean hasSwordAtLeast(DuelBot bot, Item min) {
      int minTier = weaponTier(new ItemStack(min));

      for (int i = 0; i < BOT_BACKPACK; i++) {
         ItemStack s = bot.getInventory().getItem(i);
         if (!s.isEmpty() && isSword(s.getItem()) && weaponTier(s) >= minTier) {
            return true;
         }
      }

      return false;
   }

   /**
    * What the bot's hands are committed to.
    *
    * <p>Every one of these is a real vanilla use-item action, so exactly one can be
    * running at a time and something has to arbitrate. The bot used to have none of
    * them: it could not block at all, its golden apple applied its effects the
    * instant it was picked up, and a trident was a heavy sword. Every answer a
    * player has with their hands is one the AI did not have, which is this list.
    */
   enum BotHands {
      NONE,
      SHIELD,
      CONSUME,
      RIPTIDE,
      BOW
   }

   /** Ticks a trident spins before vanilla will turn the release into a launch. */
   private static final int BOT_RIPTIDE_SPIN = 10;

   /**
    * What the player across the arena is doing right now, read off the player.
    *
    * <p>The bot used to fight its difficulty dial: it knew how far away you were
    * and nothing else, so a player standing behind a raised shield, drinking a
    * potion in its face, drawing a bow at ten paces or walking up holding an end
    * crystal got exactly the response a player doing none of those things got.
    * Every field here comes from the target's own state, and the fight answers that
    * rather than the dial.
    */
   private static final class BotRead {
      final boolean blocking;
      final boolean healing;
      final boolean drawing;
      final boolean crystal;
      final boolean axe;
      final double dist;
      final double dist3d;

      BotRead(boolean blocking, boolean healing, boolean drawing, boolean crystal, boolean axe, double dist, double dist3d) {
         this.blocking = blocking;
         this.healing = healing;
         this.drawing = drawing;
         this.crystal = crystal;
         this.axe = axe;
         this.dist = dist;
         this.dist3d = dist3d;
      }
   }

   private static BotRead readPlayer(DuelBot bot, ServerPlayer target) {
      double dx = target.getX() - bot.getX();
      double dz = target.getZ() - bot.getZ();
      double dy = target.getY() - bot.getY();
      double dist = Math.sqrt(dx * dx + dz * dz);
      double dist3d = Math.sqrt(dx * dx + dy * dy + dz * dz);
      boolean using = target.isUsingItem();
      ItemStack held = target.getUseItem();
      // Food covers the gapple, the head and cooked beef; the potion is the drink.
      // Both are held down for their whole duration, which is exactly what makes
      // them readable - a player who is eating is a player who is not swinging.
      boolean healing = using && (held.get(DataComponents.FOOD) != null || held.is(Items.POTION));
      boolean drawing = using && (held.getItem() instanceof BowItem || held.getItem() instanceof CrossbowItem);
      boolean crystal = target.getMainHandItem().is(Items.END_CRYSTAL)
         || target.getOffhandItem().is(Items.END_CRYSTAL)
         || target.getMainHandItem().is(Items.RESPAWN_ANCHOR)
         || target.getMainHandItem().is(Items.GLOWSTONE);
      return new BotRead(target.isBlocking(), healing, drawing, crystal, botHasAxe(bot), dist, dist3d);
   }

   /**
    * True when the bot is carrying an axe it can actually swing.
    *
    * <p>Only the thirty-six backpack slots count. An inventory is forty-one slots
    * wide - the four armour slots and the offhand are in there too - and asking all
    * of them meant a bot with a looted axe parked in its helmet slot "had an axe",
    * so it swung its sword into a raised shield as if it were holding one. That is
    * what "sometimes it has an axe when it doesn't" was.
    */
   private static boolean botHasAxe(DuelBot bot) {
      for (int i = 0; i < BOT_BACKPACK; i++) {
         ItemStack s = bot.getInventory().getItem(i);
         if (!s.isEmpty() && s.is(ItemTags.AXES)) {
            return true;
         }
      }

      return false;
   }

   private static boolean botHasShield(DuelBot bot) {
      return bot.getMainHandItem().is(Items.SHIELD) || bot.getOffhandItem().is(Items.SHIELD);
   }

   /**
    * Starts a real eat or drink on the bot.
    *
    * <p>The old bot applied a gapple's effects the instant it touched one and then
    * pretended to chew for 32 ticks by refusing to fight, which is not a meal: it
    * could not be interrupted, and nothing was spent at a moment the player could
    * see. This opens a vanilla use-item action instead - the stack stays in the
    * bot's hand, `LivingEntity.updateUsingItem` counts the ticks down, and
    * `ItemStack.finishUsingItem` fires the effects when the bite actually lands.
    * Break the chew and the bot loses the meal, exactly as you would.
    *
    * @return true when the use actually started
    */
   public static boolean botBeginMeal(DuelBot bot) {
      return botBeginMeal(bot, false);
   }

   /**
    * Starts a real eat on the bot, preferring plain food when the meal is for
    * hunger rather than for a wound.
    *
    * <p>A player does not spend a golden apple on an empty stomach, and the bot used
    * to have no hunger rule at all: it ate only when its health was low, so a bot
    * that could not regen simply stayed at whatever it was. {@code preferFood}
    * picks the cheapest edible first, which is what a hungry player does.
    */
   public static boolean botBeginMeal(DuelBot bot, boolean preferFood) {
      if (bot.isUsingItem()) {
         return false;
      }
      int golden = -1;
      int plain = -1;
      int potion = -1;

      for (int i = 0; i < bot.getInventory().getContainerSize(); i++) {
         ItemStack s = bot.getInventory().getItem(i);
         if (s.isEmpty()) {
            continue;
         }
         if ((s.is(Items.GOLDEN_APPLE) || s.is(Items.ENCHANTED_GOLDEN_APPLE)) && golden < 0) {
            golden = i;
         } else if (s.get(DataComponents.FOOD) != null && plain < 0 && !s.is(Items.GOLDEN_APPLE)) {
            plain = i;
         } else if (potion < 0 && potionWorthUsing(s)) {
            // A drinkable potion is the last resort, and only when its own effects
            // are a heal or a buff. "Any potion" used to be the rule here, so a
            // Potion of Poison was a legitimate meal.
            potion = i;
         }
      }

      int slot = preferFood ? (plain >= 0 ? plain : (golden >= 0 ? golden : potion)) : (golden >= 0 ? golden : (potion >= 0 ? potion : plain));
      if (slot < 0) {
         return false;
      }

      botHold(bot, slot);
      bot.startUsingItem(InteractionHand.MAIN_HAND);
      return bot.isUsingItem();
   }

   /**
    * Raises the bot's shield, for real.
    *
    * <p>A shield is only a stat until something reads it. The bot carries one, the
    * AI decides to raise it, and vanilla does the rest: `isBlocking()` turns true
    * once the shield's own warm-up has passed, and the duel's damage gate runs the
    * blow through `LivingEntity.applyItemBlocking` - so a raised shield really eats
    * a sword swing or an arrow, and really costs durability.
    *
    * @return true when the use actually started
    */
   public static boolean botBeginShield(DuelBot bot) {
      if (bot.isUsingItem() || !botHasShield(bot)) {
         return false;
      }
      bot.startUsingItem(bot.getOffhandItem().is(Items.SHIELD) ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND);
      return bot.isUsingItem();
   }

   /**
    * Spins up a riptide trident, for real.
    *
    * <p>Vanilla's riptide is not a velocity the mod gets to write. The trident has
    * to be held while `TridentItem.onUseTick` winds it up, and the launch only
    * happens on release, and only in water or rain. So this starts the real use and
    * the fight lets go of it ten ticks later; everything between is the item's.
    *
    * @return true when the use actually started
    */
   public static boolean botBeginRiptide(DuelBot bot) {
      if (bot.isUsingItem()) {
         return false;
      }
      int slot = findRiptideSlot(bot);
      if (slot < 0) {
         return false;
      }
      botHold(bot, slot);
      bot.startUsingItem(InteractionHand.MAIN_HAND);
      return bot.isUsingItem();
   }

   /** A slot holding a trident vanilla will actually spin, or -1. */
   private static int findRiptideSlot(DuelBot bot) {
      for (int i = 0; i < bot.getInventory().getContainerSize(); i++) {
         ItemStack s = bot.getInventory().getItem(i);
         if (!s.isEmpty() && s.is(Items.TRIDENT) && EnchantmentHelper.getTridentSpinAttackStrength(s, bot) > 0.0F) {
            return i;
         }
      }

      return -1;
   }

   /**
    * Whether the bot answers what it read with a shield rather than a swing.
    *
    * <p>Deliberately narrow, because a bot that blocks constantly is not a better
    * player, it is a wall. Three moments a real player reaches for the shield: an
    * arrow already in the air, a trade it has just lost, and a player hiding behind
    * a block it has no axe for.
    */
   private static boolean botWantsShield(BotRead read, DuelBot bot, long now, Duel d) {
      if (!botHasShield(bot) || read.healing) {
         // A player mid-chew is a player to hit, not a player to block.
         return false;
      }
      // Just put the shield down: whatever the read says, the bot's guard is
      // down for a beat. A shield with no recovery time is not a stance, it is a
      // permanent wall, which is what the bot used to be.
      if (d != null && now < d.botShieldCooldown) {
         return false;
      }
      if (read.drawing && read.dist < 26.0) {
         return true;
      }
      long lastHit = botLastHitTick.getOrDefault(bot.getUUID(), Long.MIN_VALUE);
      if (now - lastHit <= 20L && read.dist < 4.5) {
         return true;
      }
      // A player hiding behind a block it has no axe for used to be a permanent
      // mirror: the bot held its shield for exactly as long as the player held
      // theirs, so the fight was two shields and no swords. It still answers a
      // raised shield - but only with a beat between, so it has to come out and
      // trade like everyone else.
      return read.blocking && !read.axe && read.dist < 4.0;
   }

   /** How long the shield stays up before the bot gives up its hands to swing again. */
   private static int botShieldTicks(BotDifficulty diff) {
      return switch (diff) {
         case EASY -> 12;
         case NORMAL -> 10;
         case HARD -> 8;
         case HACKER -> 6;
         // The training dummy never reaches any of these - its AI never runs - but the switch has
         // to be total, and a value is cheaper than reasoning about an unreachable arm.
         case TRAIN -> 12;
      };
   }

   /**
    * How long the bot keeps its guard down after a block, before it may raise the
    * shield again.
    *
    * <p>The shield used to have no recovery at all: the moment it expired the
    * read was still true, so the bot re-raised it and a duel against it became a
    * duel against a permanent block. It still shields - but it has to come out and
    * trade in between, which is what makes it a fight rather than a wall.
    */
   private static int botShieldRecovery(BotDifficulty diff) {
      return switch (diff) {
         case EASY -> 60;
         case NORMAL -> 45;
         case HARD -> 30;
         case HACKER -> 22;
         case TRAIN -> 60;
      };
   }

   /**
    * One tick of whatever the bot's hands are already on, and the decision to start
    * the next thing. Returns true when vanilla owns the bot's hands this tick and
    * the fight AI must keep out of the way.
    */
   private static boolean botHandsTick(Duel d, DuelBot bot, ServerLevel realm, ServerPlayer target, long now, BotRead read, double dx, double dz, double dist) {
      if (d.botHands == BotHands.CONSUME) {
         if (!bot.isUsingItem()) {
            d.botHands = BotHands.NONE;
            return false;
         }
         // Chewing takes both hands, and standing still to chew is how a person
         // dies to a combo: back out of sword reach while the bite lands, still
         // inside reach of anything it wants to answer with. It walks out, though
         // - it does not sprint. A player cannot sprint while eating, and the bot
         // holding the sprint flag while its feet walked backwards was the
         // "sprinting backwards" the arena saw: the body moved one way and the
         // head kept looking at you the whole time.
         bot.setSprinting(false);
         double bx = bot.getX() - target.getX();
         double bz = bot.getZ() - target.getZ();
         double bd = Math.max(0.01, Math.sqrt(bx * bx + bz * bz));
         if (bd < 6.0) {
            // A pace a person can chew at, not a retreat: this was a full sprint
            // step, so the bot bit into a gapple while out-running anyone chasing it.
            botMoveTo(realm, bot, bot.getX() + bx / bd * 1.5, bot.getZ() + bz / bd * 1.5, target, d, now, BOT_WALK_STEP * 0.7);
         }
         return true;
      }

      if (d.botHands == BotHands.BOW) {
         if (!bot.isUsingItem()) {
            d.botHands = BotHands.NONE;
            return false;
         }

         // Pointed at where this shot was aimed - which is the target plus the miss
         // it was given when the string went back - and not at the target. A bow that
         // is re-aimed at the man every tick is a bow that cannot miss.
         faceAt(realm, bot, d.botBowYaw, d.botBowPitch, now);
         // A drawn bow is a walk, not a sprint.
         bot.setSprinting(false);
         double px = -dz / Math.max(0.01, dist);
         double pz = dx / Math.max(0.01, dist);
         double sway = Math.sin(now * 0.09 + d.botSeed * 0.021) * BOT_WALK_STEP * 0.45;
         botStepTo(realm, bot, bot.getX() + px * sway, bot.getZ() + pz * sway, d, now, BOT_WALK_STEP * 0.45);

         if (now >= d.botBowUntil) {
            // Let the string go. Vanilla's own `BowItem.releaseUsing` builds the
            // arrow, spends one out of the bag, and gives it the speed this draw
            // earned; vanilla's own spread is the rest of the accuracy story. Wrapped
            // because a throw here would otherwise escape into the server tick.
            try {
               bot.releaseUsingItem();
            } catch (Throwable t) {
               bot.stopUsingItem();
            }

            d.botHands = BotHands.NONE;
            d.botBowAt = now + botBowInterval(d, d.botDifficulty);
         }

         return true;
      }

      if (d.botHands == BotHands.RIPTIDE) {
         if (!bot.isUsingItem()) {
            d.botHands = BotHands.NONE;
            return false;
         }
         if (bot.getTicksUsingItem() >= BOT_RIPTIDE_SPIN) {
            // Exactly what a player does at the end of the spin: let go. Vanilla
            // turns the release into the launch - Player.push plus
            // startAutoSpinAttack - so the arc and the hit are both real.
            bot.releaseUsingItem();
            d.botHands = BotHands.NONE;
            d.botRiptideAt = now + 80L;
         } else {
            face(realm, bot, target, now);
         }
         // Vanilla is flying the bot now; steering it would only fight the launch.
         return true;
      }

      if (d.botHands == BotHands.SHIELD) {
         if (bot.isUsingItem() && now < d.botShieldUntil) {
            // Shield up: it still walks and strafes, it just cannot swing. Same deal
            // as a player - a shield is a stance you give up to attack.
            botFootwork(d, bot, realm, target, now, dx, dz, dist, read, d.botDifficulty);
            return true;
         }
         bot.stopUsingItem();
         d.botHands = BotHands.NONE;
         // The guard goes down for a beat. A shield the bot can re-raise on the
         // very next tick is not a stance, it is a wall - and a wall is all the
         // bot knew how to be.
         d.botShieldCooldown = now + botShieldRecovery(d.botDifficulty);
      }

      // A trident and open water is the bot's one way to initiate at range, and it
      // is only worth spinning with room to fly.
      if (dist > 6.0 && now >= d.botRiptideAt && bot.isInWaterOrRain() && botBeginRiptide(bot)) {
         d.botHands = BotHands.RIPTIDE;
         return true;
      }

      return false;
   }

   private static void botFight(Duel d, DuelBot bot, ServerLevel realm, ServerPlayer target, long now) {
      BotRead read = readPlayer(bot, target);
      double dx = target.getX() - bot.getX();
      double dz = target.getZ() - bot.getZ();
      double dist = read.dist;
      double dist3d = read.dist3d;
      BotDifficulty diff = d.botDifficulty;

      // Whatever the bot's hands are already on comes first: a raised shield, a
      // gapple it is chewing, a trident it is spinning - each a real use-item
      // action with a real duration, so the AI may not simply reach past it.
      if (botHandsTick(d, bot, realm, target, now, read, dx, dz, dist)) {
         return;
      }

      // Meals second: how early it panics is the clearest difficulty dial, and a
      // full-health bot eating is a wasted gapple. Hunger is the second reason to
      // eat, and it is the one a player acts on long before the health bar moves.
      boolean hurt = modeGrantsGapples(d.mode) && bot.getHealth() < bot.getMaxHealth() * gappleThreshold(diff);
      boolean hungry = bot.getFoodData().getFoodLevel() < 15 && read.dist > 8.0;
      if (now >= d.botGappleAt && (hurt || hungry)) {
         if (botBeginMeal(bot, hungry && !hurt)) {
            d.botHands = BotHands.CONSUME;
            d.botGappleAt = now + switch (diff) {
               case EASY -> 70L;
               case NORMAL -> 55L;
               case HARD -> 40L;
               case HACKER -> 30L;
               case TRAIN -> 70L;
            };
            realm.playSound(null, bot.getX(), bot.getY(), bot.getZ(), SoundEvents.GENERIC_EAT, SoundSource.NEUTRAL, 0.6F, 1.2F);
            return;
         }
      }

      // Then the shield, read off the player rather than the dial.
      if (botWantsShield(read, bot, now, d) && botBeginShield(bot)) {
         d.botHands = BotHands.SHIELD;
         d.botShieldUntil = now + botShieldTicks(diff);
         return;
      }

      // A legacy duel is a 1.8 duel, and a 1.8 duel is fought with the sword up
      // between swings. The block is the mod's `blocks_attacks` stance, so it is a
      // real use-item action: while it is raised the bot walks and strafes but does
      // not swing, which is exactly what holding right-click costs a player.
      if (d.combat == CombatStyle.LEGACY && bot.isUsingItem() && bot.getUseItem().is(ItemTags.SWORDS)) {
         if (now >= d.botSwordBlockUntil) {
            bot.stopUsingItem();
            d.botSwordBlockAt = now + 2L + (long)(Math.random() * 7.0);
         } else {
            botFootwork(d, bot, realm, target, now, dx, dz, dist, read, diff);
            return;
         }
      }

      // Potions come after the gapple: a gapple is the cheap answer and a potion is
      // the one that costs something.
      if (botTryPotion(d, bot, realm, target, now, read, diff)) {
         return;
      }

      int bowSlot = findBowSlot(bot);
      int rodSlot = findSlotOf(bot, Items.FISHING_ROD);
      // An archer keeps firing at any range: melee is cancelled outright in that
      // mode, so a bot that walked in to swing would simply stop fighting. It also
      // needs an arrow to do it with - the bot used to conjure one out of the air, so
      // it could not be disarmed and never ran out.
      if (bowSlot >= 0 && botHasItem(bot, Items.ARROW) && (dist > 5.5 || d.mode == DuelMode.ARCHERY) && dist < 32.0) {
         if (now >= d.botBowAt && botBeginDraw(d, bot, bowSlot, target, diff, dist, now)) {
            return;
         }

         // Already drawing: hold the stance and hold the shot.
         if (d.botHands == BotHands.BOW) {
            return;
         }

         bot.setSprinting(false);
         botHold(bot, bowSlot);
      } else if (d.combat == CombatStyle.LEGACY && rodSlot >= 0 && dist > 3.0 && dist < 7.0) {
         botHold(bot, rodSlot);
         if (now % 12L == 0L) {
            rodKnockback(realm, bot, target);
         }
      } else {
         // Crystal PvP is not only a mode. Obsidian and an End Crystal in the bag are
         // a trap, and a duelist builds it wherever they find the two - so a bot that
         // looted both out of a Gladiator cache or a Skywars mid chest knows the
         // trick as well as one that was handed them in the crystal arena.
         if (d.mode != DuelMode.CRYSTALPVP
            && read.dist < 4.5
            && findSlotOf(bot, Items.END_CRYSTAL) >= 0
            && findSlotOf(bot, Items.OBSIDIAN) >= 0) {
            botCrystalPlay(d, bot, realm, target, now, diff);
            return;
         }

         if (d.mode == DuelMode.CRYSTALPVP) {
            botCrystalPlay(d, bot, realm, target, now, diff);
            return;
         }

         if (d.mode == DuelMode.MACEPVP) {
            botMacePlay(d, bot, realm, target, now, diff);
            return;
         }

         // A wind charge is the bot's only ranged answer that also shoves, and it
         // goes out at the awkward middle distance where neither sword reaches.
         if (botWindCharge(d, bot, realm, target, now, dist3d)) {
            bot.getInventory().setSelectedSlot(0);
            syncBotHand(d, bot);
            botFootwork(d, bot, realm, target, now, dx, dz, dist, read, diff);
            return;
         }

         // A mace in the bag is a mace to land. The AI used one only in Mace PvP,
         // so everywhere else a looted mace was a heavy iron sword with no trick.
         if (botHasItem(bot, Items.MACE) && botMaceStrike(d, bot, realm, target, now, diff)) {
            botFootwork(d, bot, realm, target, now, dx, dz, dist, read, diff);
            return;
         }

         // A raised shield has one real answer and every player knows it: the axe.
         // The bot almost always carries one and never reached for it, which left
         // it swinging a sword into the block or standing there with its own shield
         // up - the whole of "the duel bot only knows how to hold shield".
         int axeSlot = read.blocking ? findAxeSlot(bot) : -1;
         botHold(bot, axeSlot >= 0 ? axeSlot : 0);

         // The W-tap, which is the whole reason a good player wins trades: sprint
         // into the hit for the knockback, release the sprint on the swing so the
         // attacker is not carried past. A bot that never sprints lands soft hits
         // that the opponent simply walks out of, and a bot that never releases
         // sprint throws its own target out of reach - both used to happen.
         boolean canWTap = diff == BotDifficulty.HARD || diff == BotDifficulty.HACKER;
         boolean closing = dist3d > 2.0 && dist3d < 6.0 && canWTap;
         bot.setSprinting(closing && dist3d > 2.6 && now % 2L != 0L);
         // Swinging into a raised block is a wasted hit, so the bot prefers the axe
         // or waits for the block to drop. It does not simply stand there, though:
         // a block is a direction and not a wall, so a hard bot circling the
         // player gets an angle where it is not facing the shield and takes it.
         // A player mid-chew is the opposite - that is the one window the bot
         // should be swinging as fast as the game allows.
         boolean swing = !read.blocking || read.axe || read.healing || (canWTap && now % 16L == 0L);

         // The crit is earned: vanilla only calls a hit critical when the attacker
         // is falling, so the bot has to jump first and connect on the way down. It
         // is deliberately rare - a bot that crits every swing is not a better
         // player, it is a damage multiplier.
         if (!bot.onGround() && bot.fallDistance > 0.1) {
            bot.setSprinting(false);
         }

         boolean critting = isRealCrit(bot)
            && dist3d < 3.4
            && Math.abs(target.getY() - bot.getY()) <= VERTICAL_SWING_REACH
            && (!read.blocking || read.axe || read.healing);
         if (critting) {
            if (now % 2L == 0L) {
               meleeHit(d, bot, realm, target, diff, false);
               d.botCritAt = now + botCritInterval(diff);
            }
         } else {
            boolean canCrit = diff != BotDifficulty.EASY
               && d.mode != DuelMode.ARCHERY
               && d.mode != DuelMode.SUMO
               && !botHasItem(bot, Items.MACE);
            if (canCrit
               && now >= d.botCritAt
               && bot.onGround()
               && dist3d > 1.2
               && dist3d < 3.2
               && !read.blocking
               && !read.crystal) {
               bot.setSprinting(false);
               bot.jumpFromGround();
               d.botCritAt = now + botCritInterval(diff);
            }

            if (dist3d < 3.0 && swing) {
               int rhythm;
               if (d.mode == DuelMode.COMBODUEL) {
                  rhythm = 5;
               } else if (d.botProfile != null) {
                  rhythm = d.botProfile.swingRhythm();
               } else {
                  rhythm = switch (diff.ordinal()) {
                     case 0 -> 16;
                     case 1 -> 10;
                     case 2 -> 7;
                     case 3 -> 5;
                     default -> throw new MatchException(null, null);
                  };
               }

               rhythm = Math.max(3, read.healing ? Math.min(rhythm, 4) : rhythm);
               if (now % rhythm == 0L) {
                  boolean sprintHit = closing && dist3d > 2.2;
                  bot.setSprinting(false);
                  meleeHit(d, bot, realm, target, diff, sprintHit);
               }
            }
         }

         // With the trade over, a 1.8 duelist puts the sword back up. The block is
         // deliberately not raised on a swing tick, because a raised sword is a
         // sword that is not swinging.
         if (d.combat == CombatStyle.LEGACY
            && !bot.isUsingItem()
            && now >= d.botSwordBlockAt
            && !critting
            && botBeginSwordBlock(bot)) {
            d.botSwordBlockUntil = now + 5L + (long)(Math.random() * 6.0);
            d.botSwordBlockAt = d.botSwordBlockUntil + 4L + (long)(Math.random() * 8.0);
         }
      }


      botFootwork(d, bot, realm, target, now, dx, dz, dist, read, diff);
   }

   /**
    * The bot's feet, split out because both the armed and the shielded bot need
    * them: a bot with its shield up still walks, strafes and keeps its spacing, it
    * just cannot swing, and the spacing decisions are the same either way.
    */
   private static void botFootwork(
      Duel d, DuelBot bot, ServerLevel realm, ServerPlayer target, long now, double dx, double dz, double dist, BotRead read, BotDifficulty diff
   ) {
      syncBotHand(d, bot);

      BotProfile profile = d.botProfile;
      double baseSpeed = profile != null
         ? profile.speed() * 1.5
         : switch (diff.ordinal()) {
            case 0 -> 0.22;
            case 1 -> 0.3;
            case 2 -> 0.38;
            case 3 -> 0.45;
            default -> throw new MatchException(null, null);
         };
      // Strafing is a dial in its own right now, not a side effect of picking a
      // difficulty: it is the one movement skill that separates a bot that trades
      // in a straight line from one that is hard to hit.
      double strafeAmount = profile != null
         ? profile.strafe()
         : (diff == BotDifficulty.HARD ? 0.6 : diff == BotDifficulty.HACKER ? 1.0 : 0.0);
      boolean canStrafe = strafeAmount > 0.05;
      // Backing off is a real answer and the bot almost never took it: at a
      // sliver of health, with nothing to eat, it walked straight back into the
      // sword that was killing it. It now keeps the fight at the edge of reach
      // and makes you come to it, which is what "run away" looks like without
      // turning into a five-minute chase across the map - it still has to be
      // cornered to be finished, so the duel can always end.
      boolean retreating = bot.getHealth() <= bot.getMaxHealth() * botFleeThreshold(diff)
         && !hasGapple(bot)
         && findFood(bot) == null;
      double mx;
      double mz;
      if (retreating && dist < 5.5) {
         mx = -dx / Math.max(0.01, dist) * baseSpeed;
         mz = -dz / Math.max(0.01, dist) * baseSpeed;
      } else if (read.crystal) {
         // Somebody holding an end crystal is holding the answer to "walk at me".
         // Give the blast nothing to land on, for as long as it is still in hand.
         mx = -dx / Math.max(0.01, dist) * baseSpeed;
         mz = -dz / Math.max(0.01, dist) * baseSpeed;
      } else if (d.mode == DuelMode.ARCHERY && dist < 6.5) {
         // Kiting: the only losing position in an archery duel is close range, so
         // the bot walks itself back out to where its bow works.
         mx = -dx / dist * baseSpeed;
         mz = -dz / dist * baseSpeed;
      } else if (read.drawing && dist > 4.0) {
         // A drawn bow is at its worst up close, so close - and weave while doing
         // it, so the shot has to lead a moving target instead of a standing one.
         double perpX = -dz / Math.max(0.01, dist);
         double perpZ = dx / Math.max(0.01, dist);
         double weave = Math.sin(now * 0.35 + d.botSeed * 0.02);
         mx = dx / dist * baseSpeed + perpX * weave * baseSpeed * 0.8;
         mz = dz / dist * baseSpeed + perpZ * weave * baseSpeed * 0.8;
      } else if (canStrafe && dist <= 3.2) {
         // Strafing is scaled by the bot's walk speed now - no more impossible
         // teleport-speed side-to-side movement. It used to be legacy-only, which
         // meant a hard bot walked in a straight line at you in every modern mode.
         double px = -dz / dist;
         double pz = dx / dist;
         double phase = now * (0.15 + strafeAmount * 0.08) + d.botSeed * 0.017;
         double strafeAmp = (0.18 + strafeAmount * 0.12) * baseSpeed * 2.2;
         mx = px * Math.sin(phase) * strafeAmp;
         mz = pz * Math.sin(phase) * strafeAmp;
         mx += dx / dist * (0.08 + strafeAmount * 0.08) * baseSpeed * 2.2;
         mz += dz / dist * (0.08 + strafeAmount * 0.08) * baseSpeed * 2.2;
      } else if (dist > 3.2) {
         mx = dx / dist * baseSpeed;
         mz = dz / dist * baseSpeed;
      } else if (dist < 1.7) {
         mx = -dx / dist * baseSpeed * 0.6;
         mz = -dz / dist * baseSpeed * 0.6;
      } else if (canStrafe) {
         double px = -dz / dist;
         double pz = dx / dist;
         double sway = Math.sin(now * 0.09 + d.botSeed * 0.031);
         mx = px * sway * baseSpeed * 0.55;
         mz = pz * sway * baseSpeed * 0.55;
      } else {
         mx = dx / dist * baseSpeed;
         mz = dz / dist * baseSpeed;
      }

      if (canStrafe && dist < 4.0 && Math.random() < strafeAmount * diff.dodgeRate * 0.3) {
         double perpX = -dz / dist;
         double perpZ = dx / dist;
         double dodgeDir = Math.random() < 0.5 ? 1.0 : -1.0;
         mx += perpX * dodgeDir * 0.18;
         mz += perpZ * dodgeDir * 0.18;
      }

      // How far the bot actually travels this tick. The direction is the footwork's
      // choice; the distance is its difficulty's business and nothing more. This is
      // the fix for "it walks backwards really fast": the step used to be normalised
      // to a flat 0.32 blocks a tick the moment the bot moved at all, so a bot
      // backing out of a trade - or out of a crystal, or away to eat - did it at
      // 6.4 blocks a second, faster than any player can run. Walking away is a walk:
      // it is scaled down, never up.
      double speed = Math.min(Math.hypot(mx, mz), botMaxStep(d, diff));
      boolean backwards = mx * dx + mz * dz < 0.0;
      if (backwards) {
         speed *= 0.62;
      }

      double mag = Math.hypot(mx, mz);
      if (mag > 1.0E-4) {
         mx = mx / mag * speed;
         mz = mz / mag * speed;
      }

      // A player cannot sprint backwards. The bot held the sprint flag while its
      // feet walked away from the player, so it read exactly like that: body
      // moving one way, head still turned to you. The flag only survives while
      // the bot is actually moving the way it is looking, and it is always
      // looking at you.
      if (backwards) {
         bot.setSprinting(false);
      }

      // Hunger is spent by movement as well as by fighting, and vanilla measures a
      // player's movement from the packets a client sends - which a bot does not
      // have. Without this the bot could sprint an entire duel and never be hungry.
      if (bot.isSprinting()) {
         bot.causeFoodExhaustion(0.02F);
      }

      double stepX = bot.getX() + mx;
      double stepZ = bot.getZ() + mz;
      // Sumo has no walls - that is the mode - so the bot must be the wall. It
      // still gets knocked out, but it never walks itself off the edge.
      if (d.mode == DuelMode.SUMO) {
         stepX = Math.max(d.arena.x0 + 1.5, Math.min(d.arena.x1 - 1.5, stepX));
         stepZ = Math.max(d.arena.z0 + 1.5, Math.min(d.arena.z1 - 1.5, stepZ));
      }

      // Where it was just hurt is not where it wants to be. Nothing the bot
      // learned about the fight used to outlive the tick it learned it in, so it
      // would walk straight back onto the crystal that had just taken half its
      // health, or into the corner that lost it the last trade. Sidestep the spot
      // instead, and forget it quickly.
      if (now % 100L == 0L) {
         d.botDanger.entrySet().removeIf(e -> e.getValue() < now);
      }
      Long dangerUntil = d.botDanger.get(
         new BlockPos((int)Math.floor(stepX), (int)Math.floor(bot.getY()), (int)Math.floor(stepZ)).asLong()
      );
      if (dangerUntil != null && dangerUntil > now) {
         double aroundX = -(stepZ - bot.getZ());
         double aroundZ = stepX - bot.getX();
         double len = Math.max(0.01, Math.sqrt(aroundX * aroundX + aroundZ * aroundZ));
         stepX = bot.getX() + aroundX / len * baseSpeed;
         stepZ = bot.getZ() + aroundZ / len * baseSpeed;
      }

      // The sprint-jump: sprint into the gap and jump, which is how a player closes
      // the last four blocks without telegraphing it, because vanilla's own jump
      // adds the +0.2 forward boost. It used to be impossible - the bot's altitude
      // was pinned to the floor every tick, so the arc was erased before it began.
      BlockPos under = new BlockPos((int)Math.floor(bot.getX()), (int)Math.floor(bot.getY()) - 1, (int)Math.floor(bot.getZ()));
      if (canStrafe
         && !read.crystal
         && d.mode != DuelMode.ARCHERY
         && dist > 3.0
         && dist < 7.5
         && now >= d.botJumpAt
         && (bot.onGround() || botSolidFloor(realm, under))
         && bot.isSprinting()) {
         bot.jumpFromGround();
         d.botJumpAt = now + 20L;
      }

      botMoveTo(realm, bot, stepX, stepZ, target, d, now, Math.max(speed, BOT_WALK_STEP * 0.5));
   }

   /**
    * The furthest a bot of this difficulty may travel in one tick.
    *
    * <p>A sprinting player covers about 0.28 blocks a tick, so this is also the
    * ceiling above which a bot stops looking like a person. The footwork used to
    * normalise every step to a flat 0.32 and the strafe branches produced vectors
    * several times that, which is where the impossible side-to-side movement and
    * the backward sprint came from.
    */
   private static double botMaxStep(Duel d, BotDifficulty diff) {
      if (d != null && d.botProfile != null) {
         return d.botProfile.speed();
      }

      return switch (diff) {
         case EASY -> 0.18;
         case NORMAL -> 0.21;
         case HARD -> 0.25;
         case HACKER -> 0.28;
         case TRAIN -> 0.0;
      };
   }

   /**
    * How long a bot waits between jump-crits.
    *
    * <p>Long on purpose. A crit is a 50% bonus, so a bot that hunts one every swing
    * is a bot with a flat damage buff; the point of the move is that it is a choice
    * a duelist makes a few times a fight, not a rotation.
    */
   private static long botCritInterval(BotDifficulty diff) {
      return switch (diff) {
         case EASY -> 260L;
         case NORMAL -> 130L;
         case HARD -> 80L;
         case HACKER -> 50L;
         case TRAIN -> 260L;
      };
   }

   /** How hurt a bot has to be before it stops trading and keeps its distance. */
   private static float botFleeThreshold(BotDifficulty diff) {
      return switch (diff) {
         case EASY -> 0.55F;
         case NORMAL -> 0.45F;
         case HARD -> 0.35F;
         case HACKER -> 0.3F;
         case TRAIN -> 0.0F;
      };
   }

   private static void botCrystalPlay(Duel d, DuelBot bot, ServerLevel realm, ServerPlayer target, long now, BotDifficulty diff) {
      int trapCooldown = diff == BotDifficulty.HARD ? 25 : (diff == BotDifficulty.EASY ? 80 : 40);
      int detonateWait = diff == BotDifficulty.HARD ? 16 : (diff == BotDifficulty.EASY ? 32 : 24);
      // Difficulty may make the bot pick better spots and wait less, never reach
      // further than the arm it has. It used to, and that was the "reach hack":
      // an Easy bot placed obsidian up to 5.2 blocks away, past the 4.5 a real
      // player's block-interaction range allows. The ceiling is vanilla's own,
      // read off the bot's attribute so a modded range would move it too.
      double reach = bot.blockInteractionRange();
      double attackReach = bot.entityInteractionRange();
      double placeRange = Math.min(diff == BotDifficulty.HARD ? 3.6 : (diff == BotDifficulty.EASY ? 5.2 : 4.2), reach - 0.2);
      double minBotClear = diff == BotDifficulty.HARD ? 3.5 : (diff == BotDifficulty.EASY ? 5.5 : 4.5);
      double maxEnemyBlast = diff == BotDifficulty.HARD ? 5.5 : (diff == BotDifficulty.EASY ? 8.0 : 6.5);
      if (findSlotOf(bot, Items.END_CRYSTAL) < 0 || findSlotOf(bot, Items.OBSIDIAN) < 0) {
         bot.getInventory().setSelectedSlot(0);
         syncBotHand(d, bot);
         if (now % 12L == 0L) {
            meleeHit(d, bot, realm, target);
         }

         botMoveTo(realm, bot, target.getX(), target.getZ(), target, d, now);
      } else if (d.botCrystal != null && d.botCrystal.isAlive()) {
         double cx = d.botCrystal.getX();
         double cz = d.botCrystal.getZ();
         double botDist = Math.sqrt((bot.getX() - cx) * (bot.getX() - cx) + (bot.getZ() - cz) * (bot.getZ() - cz));
         double enemyDist = Math.sqrt((target.getX() - cx) * (target.getX() - cx) + (target.getZ() - cz) * (target.getZ() - cz));
         // Detonating is an attack, not a remote control: a player has to hit the
         // crystal, so the bot may only set one off from its own attack reach. This
         // was the other half of the crystal "reach hack" - the explosion radius was
         // checked and the arm that triggered it never was.
         if (now - d.botCrystalAt >= detonateWait
            && botDist >= minBotClear
            && botDist <= attackReach + 0.5
            && enemyDist <= maxEnemyBlast) {
            realm.explode(bot, cx, d.botCrystal.getY(), cz, 6.0F, ExplosionInteraction.NONE);
            d.botCrystal.discard();
            d.botCrystal = null;
            d.botCrystalAt = now + trapCooldown;
         } else {
            botMoveTo(realm, bot, bot.getX() + (bot.getX() - cx), bot.getZ() + (bot.getZ() - cz), target, d, now);
         }
      } else {
         double dx = target.getX() - bot.getX();
         double dz = target.getZ() - bot.getZ();
         double dist = Math.sqrt(dx * dx + dz * dz);
         if (diff == BotDifficulty.EASY && now % 20L == 0L && Math.random() < 0.3) {
            botMoveTo(realm, bot, target.getX(), target.getZ(), target, d, now);
         } else if (now >= d.botCrystalAt && !(dist > placeRange)) {
            BlockPos feet = target.blockPosition();
            botPlaceBlock(d, realm, feet, Blocks.OBSIDIAN.defaultBlockState());
            EndCrystal crystal = new EndCrystal(realm, feet.getX() + 0.5, feet.getY() + 1.0, feet.getZ() + 0.5);
            realm.addFreshEntity(crystal);
            d.botCrystal = crystal;
            d.botCrystalAt = now;
            InventoryHelper.removeItems(bot, Items.END_CRYSTAL, 1);
            InventoryHelper.removeItems(bot, Items.OBSIDIAN, 1);
            realm.playSound(null, crystal.getX(), crystal.getY(), crystal.getZ(), SoundEvents.GLASS_PLACE, SoundSource.PLAYERS, 1.0F, 1.0F);
            com.fortuneandfavors.net.FfVfx.particles(realm, ParticleTypes.PORTAL, crystal.getX(), crystal.getY(), crystal.getZ(), 12, 0.4, 0.6, 0.4, 0.05);
         } else {
            botMoveTo(realm, bot, target.getX(), target.getZ(), target, d, now);
         }
      }
   }

   /** How far above or below a bot may be and still connect with a swing or a
    *  mace smash. A vanilla player's reach is ~3 blocks with almost no vertical
    *  slack, so a couple of blocks is generous without being absurd. */
   private static final double VERTICAL_SWING_REACH = 2.0;
   /** A mace leap only starts when the target is roughly on the same level. */
   private static final double LEAP_VERTICAL_REACH = 2.5;

   /**
    * Mace PvP, fought the way the weapon is actually used.
    *
    * <p>The old routine teleported the bot along a hand-built sine arc - a setPos
    * every tick, straight through anything in the way - and then applied a fixed
    * number as the smash. A mace's damage is bought with the fall, so this does what
    * a player does instead: it finds the mace wherever the kit put it (the old code
    * hard-selected slot 1, so a mace anywhere else was a bot swinging at numbers), it
    * jumps, it carries real forward momentum into the gap, and it lands the smash on
    * the way down with the fall it actually earned. Wind charges are the answer to a
    * target on a ledge, and a swipe with the charged mace is the answer to one inside
    * reach.
    */
   private static void botMacePlay(Duel d, DuelBot bot, ServerLevel realm, ServerPlayer target, long now, BotDifficulty diff) {
      int maceSlot = findSlotOf(bot, Items.MACE);
      if (maceSlot < 0) {
         // No mace in the bag: fight like anyone else rather than shadow-boxing.
         botFight(d, bot, realm, target, now);
         return;
      }

      botHold(bot, maceSlot);
      syncBotHand(d, bot);
      double dx = target.getX() - bot.getX();
      double dy = target.getY() - bot.getY();
      double dz = target.getZ() - bot.getZ();
      double horiz = Math.sqrt(dx * dx + dz * dz);
      double leapRange = diff == BotDifficulty.HARD ? 6.5 : (diff == BotDifficulty.EASY ? 3.5 : 5.0);
      int leapInterval = diff == BotDifficulty.HARD ? 24 : (diff == BotDifficulty.EASY ? 70 : 40);

      // On the way down with the mace in hand: that is the hit the weapon exists for,
      // and it is the fall - not the difficulty - that decides how much it hurts.
      // A flat jump tops out a little over a block above the floor, so the threshold
      // has to sit under that or the smash only ever lands from a ledge.
      if (bot.fallDistance > 1.0 && horiz < 3.4 && Math.abs(dy) <= VERTICAL_SWING_REACH) {
         float extra = (float)Math.min(9.0, bot.fallDistance * 2.0);
         applyBotHit(d, bot, realm, target, (6.0F + extra) * diff.damageMultiplier, true);
         com.fortuneandfavors.net.FfVfx.particles(realm, ParticleTypes.EXPLOSION, target.getX(), target.getY() + 0.8, target.getZ(), 1, 0.0, 0.0, 0.0, 0.0);
         com.fortuneandfavors.net.FfVfx.particles(realm, ParticleTypes.CRIT, target.getX(), target.getY() + 1.0, target.getZ(), 18, 0.4, 0.6, 0.4, 0.12);
         d.botMaceAt = now + 10L;
         return;
      }

      // Otherwise earn the fall: a jump with real forward momentum, which is the only
      // way up a mace smash ever gets bought.
      if (bot.onGround() && now >= d.botMaceAt && horiz < leapRange && Math.abs(dy) <= LEAP_VERTICAL_REACH) {
         double len = Math.max(0.01, horiz);
         bot.jumpFromGround();
         Vec3 v = bot.getDeltaMovement();
         bot.setDeltaMovement(v.x + dx / len * 0.26, v.y, v.z + dz / len * 0.26);
         realm.playSound(null, bot.getX(), bot.getY(), bot.getZ(), SoundEvents.PLAYER_SMALL_FALL, SoundSource.PLAYERS, 0.7F, 1.4F);
         d.botMaceAt = now + leapInterval;
         return;
      }

      // A wind charge launches the bot higher than a jump can, and it is also the
      // only ranged thing the mace has. Both reasons to throw one are real.
      if (botWindCharge(d, bot, realm, target, now, Math.sqrt(horiz * horiz + dy * dy))) {
         return;
      }

      if (horiz < 3.0 && Math.abs(dy) <= VERTICAL_SWING_REACH) {
         meleeHit(d, bot, realm, target, diff, false);
      }

      botMoveTo(realm, bot, target.getX(), target.getZ(), target, d, now);
   }

   private static int findBowSlot(ServerPlayer bot) {
      for (int i = 0; i < BOT_BACKPACK; i++) {
         ItemStack s = bot.getInventory().getItem(i);
         if (!s.isEmpty() && s.getItem() instanceof BowItem) {
            return i;
         }
      }

      return -1;
   }

   /** The slot holding an axe, or -1. An axe is the answer to a raised shield. */
   private static int findAxeSlot(ServerPlayer bot) {
      for (int i = 0; i < BOT_BACKPACK; i++) {
         ItemStack s = bot.getInventory().getItem(i);
         if (!s.isEmpty() && s.is(ItemTags.AXES)) {
            return i;
         }
      }

      return -1;
   }

   private static int findSlotOf(ServerPlayer bot, Item item) {
      for (int i = 0; i < BOT_BACKPACK; i++) {
         if (bot.getInventory().getItem(i).is(item)) {
            return i;
         }
      }

      return -1;
   }

   /** How hurt a bot of this difficulty lets itself get before it eats. */
   private static float gappleThreshold(BotDifficulty diff) {
      return switch (diff) {
         case EASY -> 0.45F;
         case NORMAL -> 0.55F;
         case HARD -> 0.65F;
         case HACKER -> 0.75F;
         case TRAIN -> 0.0F;
      };
   }

   /**
    * How well this bot reads a fight, 0 to 1.
    *
    * <p>One dial on purpose. Smartness is the reaction time it answers you in, how
    * wide its aim wanders, how much of a lead it puts on an arrow, how fast it can
    * turn its head, and how often it reaches for the clever answer - because that is
    * what separates a good player from a fast one, and because the custom difficulty's
    * slider has to move all of them together to mean anything.
    */
   private static double botSmartness(Duel d, BotDifficulty diff) {
      return d.botProfile != null
         ? d.botProfile.smartness()
         : switch (diff) {
            case EASY -> 0.35;
            case NORMAL -> 0.55;
            case HARD -> 0.8;
            case HACKER -> 1.0;
            case TRAIN -> 0.0;
         };
   }

   /** How long a bot draws the string before it lets go. A short draw is a weak shot. */
   private static int botDrawTicks(Duel d, BotDifficulty diff) {
      return (int)Math.round(8.0 + 12.0 * botSmartness(d, diff));
   }

   /** How long it waits after a shot before drawing the next one. */
   private static int botBowInterval(Duel d, BotDifficulty diff) {
      return (int)Math.round(34.0 - 18.0 * botSmartness(d, diff));
   }

   /**
    * The spread a shot carries, in degrees.
    *
    * <p>A bow is a projectile, not a hitscan, and nobody fires one dead straight. This
    * is the tell the arena noticed: accuracy used to be `(1 - aimAccuracy) * 18` on a
    * custom arrow, which is exactly zero for a Hacker bot - so the best bot fired with
    * no spread at all, at a perfectly led point, and could not miss. Every bot has a
    * floor it cannot shoot better than now, the floor is wider at range, and the shot
    * itself is vanilla's.
    */
   private static double botAimSpread(Duel d, BotDifficulty diff, double dist) {
      return (9.0 - 8.0 * botSmartness(d, diff)) * (1.0 + dist * 0.04);
   }

   /**
    * Where this shot is aimed, plus the miss it is going to have.
    *
    * <p>Aimed once, when the string goes back, and then held - which is how a shot
    * misses: the archer points at a place, not at the man, and by the time the arrow
    * arrives the man is somewhere else. The lead it takes is a read of the target's
    * velocity, scaled by smartness, so a Hard bot genuinely leads a strafing opponent
    * while an Easy one shoots behind them.
    */
   private static void botShotAim(Duel d, DuelBot bot, ServerPlayer target, BotDifficulty diff, double dist) {
      double smart = botSmartness(d, diff);
      double flight = Math.max(0.2, dist / 3.0);
      Vec3 v = target.getDeltaMovement();
      double lead = smart * 0.9;
      double ax = target.getX() + v.x * flight * lead;
      double az = target.getZ() + v.z * flight * lead;
      double ay = target.getY() + target.getEyeHeight() * 0.7 + v.y * flight * lead + dist * 0.03;
      double dx = ax - bot.getX();
      double dz = az - bot.getZ();
      double horiz = Math.max(0.1, Math.sqrt(dx * dx + dz * dz));
      float yaw = (float)Math.toDegrees(Math.atan2(-dx, dz));
      float pitch = (float)Math.toDegrees(-Math.atan2(ay - (bot.getY() + bot.getEyeHeight()), horiz));
      double spread = botAimSpread(d, diff, dist);
      yaw += (float)((Math.random() * 2.0 - 1.0) * spread);
      pitch += (float)((Math.random() * 2.0 - 1.0) * spread * 0.6);
      d.botBowYaw = yaw;
      d.botBowPitch = pitch;
   }

   /**
    * Starts a real bow draw.
    *
    * <p>Vanilla owns the whole thing from here: the string is drawn by the item's own
    * use duration, the charge at release decides the arrow's speed, `BowItem.releaseUsing`
    * spends an arrow out of the bag, and vanilla's own spread does the rest. This only
    * decides when to start, what to aim at, and when to let go.
    *
    * @return true when the draw actually started
    */
   private static boolean botBeginDraw(Duel d, DuelBot bot, int bowSlot, ServerPlayer target, BotDifficulty diff, double dist, long now) {
      if (bot.isUsingItem()) {
         return false;
      }

      botHold(bot, bowSlot);
      if (!bot.getMainHandItem().is(Items.BOW)) {
         return false;
      }

      bot.startUsingItem(InteractionHand.MAIN_HAND);
      if (!bot.isUsingItem()) {
         return false;
      }

      botShotAim(d, bot, target, diff, dist);
      d.botHands = BotHands.BOW;
      d.botBowUntil = now + botDrawTicks(d, diff);
      return true;
   }

   private static void rodKnockback(ServerLevel realm, DuelBot bot, ServerPlayer target) {
      double dx = target.getX() - bot.getX();
      double dz = target.getZ() - bot.getZ();
      double d = Math.max(0.01, Math.sqrt(dx * dx + dz * dz));
      target.push(dx / d * 1.1, 0.3, dz / d * 1.1);
      target.hurtMarked = true;
      com.fortuneandfavors.net.FfVfx.particles(realm, ParticleTypes.CRIT, target.getX(), target.getY() + 1.0, target.getZ(), 8, 0.3, 0.4, 0.3, 0.1);
      realm.playSound(null, target.getX(), target.getY(), target.getZ(), SoundEvents.PLAYER_ATTACK_KNOCKBACK, SoundSource.PLAYERS, 1.0F, 1.0F);
   }

   private static void meleeHit(Duel d, DuelBot bot, ServerLevel realm, ServerPlayer target) {
      meleeHit(d, bot, realm, target, d.botDifficulty);
   }

   private static void meleeHit(Duel d, DuelBot bot, ServerLevel realm, ServerPlayer target, BotDifficulty diff) {
      meleeHit(d, bot, realm, target, diff, false);
   }

   /**
    * How hard a bot's hit shoves, including its own weapon's Knockback enchantment.
    *
    * <p>`Player.attack` builds this for a player - the sprint hit's shove plus the
    * weapon's Knockback at half a block a level - and the bot does not go through
    * `Player.attack`, so it is the one place the term has to be added. It was
    * missing, which made the direction asymmetric in a way nobody would notice from
    * the outside: the player's Knockback cut the bot (the bot's own incoming path
    * reads it) while the bot's Knockback did nothing at all, so a bot holding a
    * Knockback stick shoved exactly as hard as bare hands. Public so the self-test
    * can pin the number without standing up a whole duel.
    */
   public static double botShoveStrength(ServerLevel realm, DuelBot bot, boolean sprintHit) {
      return (sprintHit ? 0.42 : 0.0) + vanillaEnchant(realm, bot.getMainHandItem(), Enchantments.KNOCKBACK) * 0.5;
   }

   private static void meleeHit(Duel d, DuelBot bot, ServerLevel realm, ServerPlayer target, BotDifficulty diff, boolean sprintHit) {
      // 1.9 combat has a cooldown, and it is the attacker's own attack speed that
      // decides it. A player cannot spend it twice, so a bot that swings on the
      // difficulty's rhythm alone is a bot with no cooldown at all - which is what
      // modern-combat duels felt like. The charge is read off the bot's own
      // attack-strength ticker: in a legacy duel the mod raises the bot's attack
      // speed to nothing, so this never holds it back, and in a modern one the hit
      // lands when the sword has come back up, for exactly what that charge is worth.
      float charge = botAttackCharge(bot);
      if (charge < botMinCharge(diff)) {
         return;
      }

      // The bot swings with the same pipeline the player does - its attribute, its
      // weapon's enchantments (a Bedwars upgrade or a Sharpness sword counts for it
      // exactly as it counts against it), its charge, and the item's own attack
      // bonus. Only the difficulty still scales it.
      float dmg = vanillaMeleeDamage(bot, target, bot.getMainHandItem()) * diff.damageMultiplier;
      if (diff == BotDifficulty.HARD && Math.random() < 0.12F) {
         dmg *= 1.25F;
      }

      applyBotHit(d, bot, realm, target, dmg, sprintHit);
   }

   /**
    * How charged the bot's swing is, 0 to 1.
    *
    * <p>{@code Player.getAttackStrengthScale} is the same number the client uses to
    * draw the attack indicator, so it already knows about the weapon's attack speed
    * and about the legacy duel's raised attack speed. Nothing here has to decide what
    * a cooldown means; it only reads one.
    */
   private static float botAttackCharge(DuelBot bot) {
      try {
         return Math.max(0.0F, Math.min(1.0F, bot.getAttackStrengthScale(0.5F)));
      } catch (Throwable t) {
         return 1.0F;
      }
   }

   /** How charged a swing has to be before this difficulty takes it. */
   private static float botMinCharge(BotDifficulty diff) {
      return switch (diff) {
         case EASY -> 1.0F;
         case NORMAL -> 1.0F;
         case HARD -> 0.95F;
         case HACKER -> 0.9F;
         case TRAIN -> 1.0F;
      };
   }

   /**
    * Lands one bot blow on a player: the damage, the shove and the effects.
    *
    * <p>Split out because the mace needs to land a blow with damage the weapon
    * table cannot produce - the fall bonus - and duplicating the knockback and the
    * hit feedback for one weapon is how the two paths drift apart.
    */
   private static void applyBotHit(Duel d, DuelBot bot, ServerLevel realm, ServerPlayer target, float dmg, boolean sprintHit) {
      target.hurtServer(realm, realm.damageSources().playerAttack(bot), dmg);
      // Spending the swing is what starts the cooldown again: a player's own attack
      // resets this, and the bot's blows are applied by hand, so nothing did.
      bot.resetAttackStrengthTicker();
      // And swinging costs food. A player burns hunger on every attack, on every
      // jump and on every hit taken, and the bot does none of it through vanilla's
      // own paths - which is why it "had infinite food": nothing it did ever made it
      // hungry.
      bot.causeFoodExhaustion(sprintHit ? 0.3F : 0.1F);
      // Sprint-hit knockback, applied by hand because the bot does not go through
      // Player.attack. Without it a bot's hits are shoves: the target never gets
      // pushed out of position, so the bot can never actually start a combo.
      double shove = botShoveStrength(realm, bot, sprintHit);
      if (shove > 0.0) {
         double kx = target.getX() - bot.getX();
         double kz = target.getZ() - bot.getZ();
         double kd = Math.max(0.01, Math.sqrt(kx * kx + kz * kz));
         target.push(kx / kd * shove, sprintHit ? 0.06 : 0.02, kz / kd * shove);
         // The flag ServerEntity.sendChanges reads to hand the client its new motion.
         // Without it a shove is server-side only and the client walks straight
         // through it.
         target.hurtMarked = true;
         realm.playSound(null, bot.getX(), bot.getY(), bot.getZ(), SoundEvents.PLAYER_ATTACK_KNOCKBACK, SoundSource.PLAYERS, 0.8F, 1.0F);
      }
      if (d.mode == DuelMode.COMBODUEL) {
         target.invulnerableTime = 1;
      }

      com.fortuneandfavors.net.FfVfx.particles(realm, ParticleTypes.CRIT, target.getX(), target.getY() + 1.0, target.getZ(), 12, 0.4, 0.5, 0.4, 0.1);
      realm.playSound(null, target.getX(), target.getY(), target.getZ(), SoundEvents.PLAYER_ATTACK_STRONG, SoundSource.PLAYERS, 1.0F, 1.0F);

      try {
         realm.getServer().getPlayerList().broadcastAll(new ClientboundAnimatePacket(bot, 0));
      } catch (Exception var7) {
      }
   }

   private static void syncBotHand(Duel d, DuelBot bot) {
      int sel = bot.getInventory().getSelectedSlot();
      if (sel != d.botLastSlot) {
         d.botLastSlot = sel;
         bot.setItemSlot(EquipmentSlot.MAINHAND, bot.getMainHandItem().copy());
      }
   }

   /** A player's walk and a bot's usual closing speed, in blocks per tick. */
   private static final double BOT_WALK_STEP = 0.2;
   private static final double BOT_RUN_STEP = 0.32;
   /**
    * A sneaking player's pace, in blocks per tick.
    *
    * <p>Sneaking multiplies a walk by about 0.3, so a person bridging edge-walks at
    * roughly 1.3 blocks a second - a fifth of a sprint. That is the number the bridge
    * has to move at, because the block placements were slowed down but the feet were
    * not, and a bot that lays one block every five ticks while striding at walking
    * pace is still a bot that crosses an island the way no one can.
    */
   private static final double BOT_SNEAK_STEP = 0.065;

   private static void botMoveTo(ServerLevel realm, DuelBot bot, double tx, double tz, ServerPlayer target, Duel d, long now) {
      // The fallback step is the difficulty's own ceiling, not a flat run: every
      // caller that walks a bot somewhere - to a chest, to a bed, to a trap - was
      // moving it at 6.4 blocks a second, which is faster than a sprinting player.
      botMoveTo(realm, bot, tx, tz, target, d, now, botMaxStep(d, d.botDifficulty));
   }

   private static void botMoveTo(ServerLevel realm, DuelBot bot, double tx, double tz, ServerPlayer target, Duel d, long now, double step) {
      botStepTo(realm, bot, tx, tz, d, now, step);
      face(realm, bot, target, now);
   }

   /**
    * Walks the bot a step, without touching where it is looking.
    *
    * <p>Split out from {@link #botMoveTo} because a bot drawing a bow walks while it
    * aims, and a walk that turns the bot's head back to the target every tick is a
    * walk that throws the shot away.
    */
   private static void botStepTo(ServerLevel realm, DuelBot bot, double tx, double tz, Duel d, long now, double step) {
      // Standing up is the default; only the bridging branch below crouches - and it
      // HOLDS the crouch for as long as it is bridging. The old version cleared the
      // key here every tick and set it again inside the branch, so a bridged block
      // flicked the sneak key off and on twenty times a second. A crouch that
      // flickers is not a crouch: it reads as the automated, twitchy motion a
      // scaffold hack produces, which is exactly what bridging was supposed to stop
      // looking like.
      if (now > d.botBridgeHoldUntil) {
         d.botBridging = false;
         bot.setShiftKeyDown(false);
      }
      double dx = tx - bot.getX();
      double dz = tz - bot.getZ();
      double dist = Math.sqrt(dx * dx + dz * dz);
      // The dead zone has to be smaller than the smallest step a bot ever takes,
      // or the bot never takes it. The footwork hands this method a destination
      // only one walk-step away (0.22 for an easy bot), and the old 0.35 gate was
      // larger than that - so an easy or normal bot walked nowhere and simply
      // stood in front of you, which is exactly how it looked.
      if (dist > 0.05) {
         // Travel is the smaller of the bot's step and the distance to the
         // destination: the footwork hands this method a point exactly one walk
         // step away, and normalising every one of those to the full step is what
         // made speed independent of difficulty - it also meant an easy bot moved
         // at a hard bot's pace the moment it moved at all.
         double travel = Math.min(step, dist);
         double nx = bot.getX() + dx / dist * travel;
         double nz = bot.getZ() + dz / dist * travel;
         BlockPos feet = botGroundBlock(realm, bot.getX(), bot.getY(), bot.getZ());
         BlockPos ahead = botGroundBlock(realm, nx, bot.getY(), nz);
         // A bot may only walk where there is ground to walk on, and only the next
         // step counts. `ahead2` - the block two steps out - used to be part of this
         // test, which made a bot treat its own solid tile as a gap whenever there
         // was a hole within two blocks; and because the bridging branch below is
         // guarded on having wool, a bot with none fell straight through to the
         // ordinary walk and stepped out over the hole anyway. That is what "the bot
         // flies in TNT Run" was: the floor went away underneath it and the AI kept
         // walking it through the air, at its own height, because `botGroundY` answers
         // "nowhere" with the height the bot is already at.
         if (feet == null || (ahead == null && !botHasItem(bot, whiteWool()))) {
            // Standing on nothing, or about to step onto nothing with nothing to
            // bridge with. The fall is vanilla's - physics has the gravity, and the
            // AI has no business steering a bot through the air. The wander re-plans
            // on its own timer, so holding position at an edge is a pause, not a wall.
            d.botBridging = false;
            bot.setShiftKeyDown(false);
            if (feet == null) {
               bot.falling = true;
            }

            return;
         }

         boolean overGap = ahead == null;
         if (overGap) {
            d.botBridging = true;
            // The crouch is held for a few ticks past this decision, so two adjacent
            // bridging ticks keep one continuous sneak rather than releasing and
            // re-pressing the key between them.
            d.botBridgeHoldUntil = now + 4L;
            // Sneaking at the edge is not decoration: it is the whole difference
            // between bridging and scaffolding. The bot used to place a block and
            // step onto it every single tick without ever slowing down, which is the
            // exact motion a scaffold hack produces and no player can make. Now it
            // crouches, lays one block, walks onto that one, crouches again - at a
            // person's pace, one placement every five ticks and no faster.
            bot.setShiftKeyDown(true);
            // Where the block goes: the empty space directly under the next step.
            // (There used to be a second choice two steps out, for when the near
            // space already had ground in it - a branch that is now unreachable, since
            // this whole path is only taken when the next step is over nothing.)
            BlockPos placeTarget = new BlockPos((int)Math.floor(nx), (int)Math.floor(bot.getY()) - 1, (int)Math.floor(nz));
            boolean placed = false;
            if (now >= d.botBridgeAt && realm.getBlockState(placeTarget).isAir()) {
               botTakeItem(bot, whiteWool(), 1);
               botPlaceBlock(d, realm, placeTarget, ((Block)Blocks.WOOL.white()).defaultBlockState());
               realm.playSound(
                  null, placeTarget.getX() + 0.5, placeTarget.getY() + 0.5, placeTarget.getZ() + 0.5, SoundEvents.WOOL_PLACE, SoundSource.BLOCKS, 0.6F, 1.1F
               );
               d.botBridgeAt = now + 5L;
               placed = true;
            }

            // Only step out once there is something to step onto, and onto the
            // surface that is really there. The height used to be a flat floor value
            // borrowed from the arena, which is wrong the moment the bot is bridging
            // anywhere that is not the arena's own level.
            //
            // The step itself is gated on the block being there. It used to be taken
            // whenever the placement timer was two ticks from firing, which is a step
            // out over the gap *before* anything has been laid in it - a bot walking
            // on air with a block appearing behind it.
            if (placed || now >= d.botBridgeAt - 2L) {
               // A sneaking player moves at a fraction of their walk - 1.3 blocks a
               // second against a walk's 4.3 - so the out-step is scaled to the sneak
               // pace instead of the bot's walking pace. Stepping out at full speed
               // was the other half of the scaffold tell: the block placements slowed
               // down but the feet did not.
               double sneak = Math.min(step, BOT_SNEAK_STEP);
               double bx = bot.getX() + dx / dist * sneak;
               double bz = bot.getZ() + dz / dist * sneak;
               if (botGroundBlock(realm, bx, bot.getY(), bz) != null) {
                  bot.steer(bx, bz, botGroundY(realm, bx, bot.getY(), bz));
               }
            }
         } else {
            d.botBridging = false;
            bot.setShiftKeyDown(false);
            bot.steer(nx, nz, botGroundY(realm, nx, bot.getY(), nz));
         }
      }
   }

   private static boolean botSolidFloor(ServerLevel realm, BlockPos pos) {
      BlockState st = realm.getBlockState(pos);
      return st.getBlock() != Blocks.AIR && st.getBlock() != Blocks.BARRIER;
   }

   /**
    * The block the bot is standing on, found by looking down from where its feet
    * actually are, or null when there is nothing solid under them.
    */
   private static BlockPos botGroundBlock(ServerLevel realm, double x, double y, double z) {
      int bx = (int)Math.floor(x);
      int bz = (int)Math.floor(z);
      int start = (int)Math.floor(y) + 1;

      for (int by = start; by >= start - 5; by--) {
         BlockPos p = new BlockPos(bx, by, bz);
         BlockState st = realm.getBlockState(p);
         if (st.isAir()) {
            continue;
         }

         double top = st.getCollisionShape(realm, p).max(Axis.Y);
         if (top > 0.0 && by + top <= y + 1.001) {
            return p;
         }
      }

      return null;
   }

   /**
    * The height of the floor under a point.
    *
    * <p>This replaced "the block one below {@code floor(y)}", which is only the
    * right block when the bot's feet sit exactly on a whole number. A bot standing
    * on a slab, a path, a stair or the bridge it just laid is at a fractional
    * height, so the block that formula named was one too low and the height it wrote
    * back was wrong - which is how a bot ended up hovering half a block above the
    * ground. This looks down from the feet, so the surface it returns is the one
    * under them, and it returns the bot's own height when there is nothing there -
    * a bot over a hole falls, it does not stand on a guess.
    */
   private static double botGroundY(ServerLevel realm, double x, double y, double z) {
      BlockPos p = botGroundBlock(realm, x, y, z);
      if (p == null) {
         return y;
      }

      double top = realm.getBlockState(p).getCollisionShape(realm, p).max(Axis.Y);
      return p.getY() + top;
   }

   /**
    * TNT Run: pick the next tile like someone who intends to still be standing.
    *
    * <p>The old wander was a random walk with a bias toward the player, which is a
    * coin flip against someone who is actually watching the floor. This scores
    * candidates instead: close enough to reach, kept at fighting distance from the
    * opponent, not on the tile the opponent has just left, and - the part that
    * decides the mode - on a tile that still has neighbours, because an island is a
    * tile about to fall. A bot standing on a tile that is already crumbling always
    * moves, whatever else is happening.
    */
   private static void botTntrunWander(Duel d, DuelBot bot, ServerLevel realm, ServerPlayer target, long now) {
      BlockPos under = new BlockPos((int)Math.floor(bot.getX()), (int)Math.floor(bot.getY()) - 1, (int)Math.floor(bot.getZ()));
      boolean onCrumble = d.tntrunCrumble.containsKey(under) || realm.getBlockState(under).isAir();
      if (onCrumble || d.botTntTarget == null || now >= d.botTntUntil) {
         int baseX = (int)Math.floor(bot.getX());
         int baseZ = (int)Math.floor(bot.getZ());
         double bestX = bot.getX();
         double bestZ = bot.getZ();
         double bestScore = -Double.MAX_VALUE;

         for (int attempt = 0; attempt < 120; attempt++) {
            // One hop at a time. The mode is won by moving to the tile next door,
            // and a target seven blocks out is a target whose path crosses whatever
            // the two of you have already broken - the bot picks a destination and
            // then finds its own holes on the way there. On crumbling floor it looks
            // only at the eight around it, which is what a player does.
            int radius = onCrumble ? 3 : 4;
            int dx2 = (int)(Math.random() * (radius * 2 + 1)) - radius;
            int dz2 = (int)(Math.random() * (radius * 2 + 1)) - radius;
            BlockPos candidate = new BlockPos(baseX + dx2, under.getY(), baseZ + dz2);
            if (!isTntRunTile(realm, candidate, d) || !realm.getBlockState(candidate.above()).isAir()) {
               continue;
            }
            double toBot = Math.hypot(candidate.getX() + 0.5 - bot.getX(), candidate.getZ() + 0.5 - bot.getZ());
            double toPlayer = Math.hypot(candidate.getX() + 0.5 - target.getX(), candidate.getZ() + 0.5 - target.getZ());
            // Standing in the opponent's swing, or on the block they just left, are
            // both ways to lose a mode that only requires you to be somewhere else.
            if (toPlayer < 3.0 || withinPlayerCrumble(target, candidate, 2.0)) {
               continue;
            }

            int neighbours = 0;

            for (int nx = -1; nx <= 1; nx++) {
               for (int nz = -1; nz <= 1; nz++) {
                  if ((nx != 0 || nz != 0) && isTntRunTile(realm, candidate.offset(nx, 0, nz), d)) {
                     neighbours++;
                  }
               }
            }

            double score = -toBot - Math.abs(toPlayer - 5.0) * 0.8 + neighbours * 1.6;
            if (score > bestScore) {
               bestScore = score;
               bestX = candidate.getX() + 0.5;
               bestZ = candidate.getZ() + 0.5;
            }
         }

         d.botTntTarget = new double[]{bestX, bestZ};
         // Re-plan sooner when the floor under it is going, later when it has a
         // solid tile - the same way a person commits to a run and then looks again.
         d.botTntUntil = now + (onCrumble ? 8L : 16L) + (long)(Math.random() * 16.0);
      }

      botMoveTo(realm, bot, d.botTntTarget[0], d.botTntTarget[1], target, d, now);
   }

   private static boolean isTntRunTile(ServerLevel realm, BlockPos pos, Duel d) {
      BlockState st = realm.getBlockState(pos);
      return (st.getBlock() == Blocks.GRAVEL || st.getBlock() == Blocks.SAND) && !d.tntrunCrumble.containsKey(pos);
   }

   private static boolean withinPlayerCrumble(ServerPlayer target, BlockPos pos, double radius) {
      double dx = pos.getX() + 0.5 - target.getX();
      double dz = pos.getZ() + 0.5 - target.getZ();
      return dx * dx + dz * dz < radius * radius;
   }

   private static void face(ServerLevel realm, DuelBot bot, ServerPlayer target, long now) {
      double dx = target.getX() - bot.getX();
      double dz = target.getZ() - bot.getZ();
      double dist = Math.max(0.1, Math.sqrt(dx * dx + dz * dz));
      float yaw = (float)Math.toDegrees(Math.atan2(-dx, dz));
      double dy = target.getY() + 1.1 - (bot.getY() + 1.1);
      float pitch = (float)Math.toDegrees(-Math.atan2(dy, dist));
      faceAt(realm, bot, yaw, pitch, now);
   }

   /**
    * Points the bot at a pair of angles, and tells the clients.
    *
    * <p>Split out from {@link #face} because a shot is aimed at a point rather than
    * at a person: the bot's bow is pointed at where it thinks the target will be, plus
    * the miss that shot is going to have, and that is a rotation, not a target.
    */
   private static void faceAt(ServerLevel realm, DuelBot bot, float yaw, float pitch, long now) {
      bot.setYRot(yaw);
      bot.setYHeadRot(yaw);
      bot.setXRot(pitch);
      if (now % 4L == 0L) {
         try {
            byte yawByte = (byte)(yaw * 256.0F / 360.0F);
            byte pitchByte = (byte)(pitch * 256.0F / 360.0F);
            Rot rot = new Rot(bot.getId(), yawByte, pitchByte, bot.onGround());
            ClientboundRotateHeadPacket head = new ClientboundRotateHeadPacket(bot, yawByte);

            for (ServerPlayer v : realm.getPlayers(p -> p != null)) {
               v.connection.send(rot);
               v.connection.send(head);
            }
         } catch (Exception var21) {
         }
      }
   }

   /**
    * What the weapon in the bot's hand is worth, by the weapon.
    *
    * <p>The default used to be five - a stone sword's damage, handed to anything the
    * table did not name. A bot holding a pickaxe, a shovel, a block or nothing at all
    * hit exactly as hard as a bot holding a stone sword, which is what "the bot deals
    * the same damage no matter the weapon" was: the loot it was swinging was never
    * what decided the number. Every entry here is now the real item's own attack
    * damage, and the fallback is a bare fist.
    */
   private static void lootChest(Duel d, DuelBot bot, ServerLevel realm, BlockPos chestPos) {
      if (d.arena.looted.add(chestPos)) {
         if (realm.getBlockEntity(chestPos) instanceof ChestBlockEntity chest) {
            for (int i = 0; i < chest.getContainerSize(); i++) {
               ItemStack s = chest.getItem(i);
               if (!s.isEmpty()) {
                  if (isSword(s.getItem()) || s.getItem() instanceof BowItem || armorSlotFor(s.getItem()) != null) {
                     tryEquip(bot, s);
                  } else if (s.get(DataComponents.FOOD) != null || s.is(Items.ENDER_PEARL) || s.is(Items.GOLDEN_APPLE)) {
                     addToInventory(bot, s.copy());
                  }

                  chest.setItem(i, ItemStack.EMPTY);
               }
            }
         }
      }
   }

   private static void tryEquip(DuelBot bot, ItemStack s) {
      EquipmentSlot slot = armorSlotFor(s.getItem());
      if (slot != null) {
         ItemStack current = bot.getItemBySlot(slot);
         if (current.isEmpty() || armorTier(s) > armorTier(current)) {
            bot.setItemSlot(slot, s.copy());
         }
      } else {
         ItemStack held = bot.getMainHandItem();
         if (held.isEmpty() || weaponTier(s) > weaponTier(held)) {
            bot.getInventory().setItem(0, s.copy());
            bot.getInventory().setSelectedSlot(0);
         }
      }
   }

   private static int weaponTier(ItemStack s) {
      Item i = s.getItem();
      if (i == Items.NETHERITE_SWORD) {
         return 5;
      } else if (i == Items.DIAMOND_SWORD) {
         return 4;
      } else if (i == Items.IRON_SWORD) {
         return 3;
      } else if (i == Items.STONE_SWORD) {
         return 2;
      } else if (i == Items.WOODEN_SWORD) {
         return 1;
      } else {
         return i instanceof BowItem ? 3 : 0;
      }
   }

   private static int armorTier(ItemStack s) {
      Item i = s.getItem();
      if (i == Items.NETHERITE_CHESTPLATE || i == Items.NETHERITE_HELMET || i == Items.NETHERITE_LEGGINGS || i == Items.NETHERITE_BOOTS) {
         return 5;
      } else if (i == Items.DIAMOND_CHESTPLATE || i == Items.DIAMOND_HELMET || i == Items.DIAMOND_LEGGINGS || i == Items.DIAMOND_BOOTS) {
         return 4;
      } else if (i == Items.IRON_CHESTPLATE || i == Items.IRON_HELMET || i == Items.IRON_LEGGINGS || i == Items.IRON_BOOTS) {
         return 3;
      } else if (i == Items.CHAINMAIL_CHESTPLATE || i == Items.CHAINMAIL_HELMET || i == Items.CHAINMAIL_LEGGINGS || i == Items.CHAINMAIL_BOOTS) {
         return 2;
      } else {
         return i != Items.LEATHER_CHESTPLATE && i != Items.LEATHER_HELMET && i != Items.LEATHER_LEGGINGS && i != Items.LEATHER_BOOTS ? 0 : 1;
      }
   }

   private static BlockPos nearestLootableChest(Duel d, ServerLevel realm, DuelBot bot) {
      BlockPos best = null;
      double bd = Double.MAX_VALUE;

      for (BlockPos c : d.arena.chests) {
         if (!d.arena.looted.contains(c)) {
            double dist = bot.distanceToSqr(c.getX() + 0.5, c.getY(), c.getZ() + 0.5);
            if (dist < bd) {
               bd = dist;
               best = c;
            }
         }
      }

      return best;
   }

   private static void botPickup(ServerLevel realm, DuelBot bot) {
      for (ItemEntity e : realm.getEntitiesOfClass(ItemEntity.class, bot.getBoundingBox().inflate(1.6), en -> en.isAlive() && !en.hasPickUpDelay())) {
         ItemStack s = e.getItem();
         if (!s.isEmpty() && addToInventory(bot, s.copy())) {
            e.discard();
         }
      }
   }

   /**
    * Puts a stack into the bot's bag.
    *
    * <p>Only the thirty-six backpack slots, never the four armour slots or the
    * offhand. An inventory is one flat list of forty-one slots, so the old loop over
    * {@code getContainerSize()} could file a looted axe into the helmet slot - where
    * the client draws it as nothing and the bot looks unarmed - or an item into the
    * offhand, where it is drawn in the bot's other hand alongside its sword.
    */
   private static boolean addToInventory(ServerPlayer bot, ItemStack stack) {
      try {
         Inventory inv = bot.getInventory();

         for (int i = 0; i < BOT_BACKPACK && !stack.isEmpty(); i++) {
            ItemStack slot = inv.getItem(i);
            if (!slot.isEmpty() && ItemStack.isSameItem(slot, stack) && slot.getCount() < slot.getMaxStackSize()) {
               int take = Math.min(stack.getCount(), slot.getMaxStackSize() - slot.getCount());
               slot.grow(take);
               stack.shrink(take);
            }
         }

         for (int i = 0; i < BOT_BACKPACK && !stack.isEmpty(); i++) {
            if (inv.getItem(i).isEmpty()) {
               inv.setItem(i, stack.copy());
               stack.setCount(0);
            }
         }

         return stack.isEmpty();
      } catch (Exception e) {
         return false;
      }
   }

   private static ItemStack findFood(DuelBot bot) {
      for (int i = 0; i < BOT_BACKPACK; i++) {
         ItemStack s = bot.getInventory().getItem(i);
         if (!s.isEmpty() && s.get(DataComponents.FOOD) != null) {
            return s;
         }
      }

      return ItemStack.EMPTY;
   }

   private static boolean hasGapple(ServerPlayer p) {
      for (int i = 0; i < BOT_BACKPACK; i++) {
         ItemStack s = p.getInventory().getItem(i);
         if (!s.isEmpty() && (s.is(Items.GOLDEN_APPLE) || s.is(Items.ENCHANTED_GOLDEN_APPLE))) {
            return true;
         }
      }

      return false;
   }

   private static boolean botHasItem(DuelBot bot, Item item) {
      for (int i = 0; i < BOT_BACKPACK; i++) {
         ItemStack s = bot.getInventory().getItem(i);
         if (!s.isEmpty() && s.is(item)) {
            return true;
         }
      }

      return false;
   }

   private static void botTakeItem(DuelBot bot, Item item, int count) {
      for (int i = 0; i < bot.getInventory().getContainerSize() && count > 0; i++) {
         ItemStack s = bot.getInventory().getItem(i);
         if (!s.isEmpty() && s.is(item)) {
            int take = Math.min(count, s.getCount());
            s.shrink(take);
            count -= take;
         }
      }
   }

   // ------------------------------------------------------- bot kit & inventory

   /**
    * The slots a bot is allowed to shuffle items around in: the hotbar and the
    * main inventory, never the armour or the offhand.
    */
   private static final int BOT_BACKPACK = 36;

   /**
    * Stages a Totem of Undying in the bot's free hand.
    *
    * <p>A totem anywhere in the bag is only a number the damage gate can read; the
    * totem that actually saves you is the one in the hand you are not swinging
    * with. This is what a player does the moment the fight turns, and the bot had
    * no such move at all: it held a totem, the gate popped it, and it never put
    * the next one up.
    */
   private static void botStageTotem(DuelBot bot) {
      if (bot.getOffhandItem().is(Items.TOTEM_OF_UNDYING)) {
         return;
      }

      int slot = -1;

      for (int i = 0; i < BOT_BACKPACK; i++) {
         if (bot.getInventory().getItem(i).is(Items.TOTEM_OF_UNDYING)) {
            slot = i;
            break;
         }
      }

      if (slot >= 0) {
         Inventory inv = bot.getInventory();
         ItemStack stash = inv.getItem(slot).copy();
         ItemStack off = inv.getItem(BOT_BACKPACK + 4);
         bot.setItemSlot(EquipmentSlot.OFFHAND, stash);
         inv.setItem(slot, off.isEmpty() ? ItemStack.EMPTY : off.copy());
         inv.setChanged();
      }
   }

   /**
    * Tidies the bot's kit into the slots it can actually use.
    *
    * <p>Three things a player does without thinking and the bot never did: keep the
    * best sword in the slot the AI swings from, keep the offhand honest, and make
    * room. A bot that loots a diamond sword into slot 14 owns a diamond sword it
    * never swings - the AI only ever selects slots 0 and the one it wants.
    */
   /**
    * Puts the stack in {@code slot} into the bot's hand, the way a player would.
    *
    * <p>{@code Inventory.setSelectedSlot} throws
    * {@code IllegalArgumentException("Invalid selected slot")} for anything outside the
    * nine hotbar slots, because that is what a selected slot is. Every bot routine that
    * found an item by searching the bag - a potion, a bow, an axe, a mace, a riptide
    * trident - then selected the slot it was found in, so the moment the item was not
    * already in the hotbar the exception escaped the duel tick and the player was
    * thrown out of the match. (A 1.8 axe duel's kits hand the potions out low in the
    * bag, which is why that is where it was met first.) A player's own answer is to
    * swap the thing into the slot they are holding, so that is what this does -
    * losslessly, because the old held stack goes back into the slot the new one came
    * from, exactly as pressing a hotbar key does in an inventory screen.
    *
    * @return the hotbar slot the stack is now held in
    */
   private static int botHold(DuelBot bot, int slot) {
      Inventory inv = bot.getInventory();
      int held = inv.getSelectedSlot();
      if (slot < 0 || slot == held || slot >= BOT_BACKPACK) {
         return held;
      }

      if (slot < 9) {
         inv.setSelectedSlot(slot);
         return slot;
      }

      ItemStack wanted = inv.getItem(slot);
      inv.setItem(slot, inv.getItem(held).copy());
      inv.setItem(held, wanted);
      inv.setChanged();
      return held;
   }

   /**
    * Places a block for the bot and books it as one a fighter placed.
    *
    * <p>The duel only lets a fighter break a block that the bookkeeping knows a
    * fighter placed - which is what keeps the arena map itself intact outside the modes
    * that mean to be dug up. Every bot placement used to go straight to
    * {@code realm.setBlock} and was never booked, so a Skywars bot's bridge and a
    * Bedwars bot's bed wall were unbreakable walls nobody could take down - including
    * the bot, which is what "their blocks they place can't be broken" was.
    */
   private static void botPlaceBlock(Duel d, ServerLevel realm, BlockPos pos, BlockState state) {
      realm.setBlock(pos, state, 3);
      if (d != null) {
         d.playerPlacedBlocks.add(BlockPos.asLong(pos.getX(), pos.getY(), pos.getZ()));
      }
   }

   private static void botManageInventory(DuelBot bot) {
      Inventory inv = bot.getInventory();
      // The best weapon the bot owns belongs in slot 0, because that is the slot the
      // AI swings from. This used to move a sword forward only when slot 0 already
      // held one - so a bot that looted a diamond sword into slot 9 spent the rest of
      // the match swinging whatever the kit had left in front, which is why its
      // damage looked identical whatever it was carrying. Only slot 0 is rearranged,
      // and only when what is in it is disposable.
      ItemStack front = inv.getItem(0);
      int best = -1;
      int bestRank = botWeaponRank(front);

      if (botSlotZeroIsDisposable(front)) {
         for (int i = 1; i < BOT_BACKPACK; i++) {
            int rank = botWeaponRank(inv.getItem(i));
            if (rank > bestRank) {
               bestRank = rank;
               best = i;
            }
         }
      }

      if (best > 0) {
         ItemStack weapon = inv.getItem(best).copy();
         inv.setItem(best, inv.getItem(0).copy());
         inv.setItem(0, weapon);
         if (inv.getSelectedSlot() == 0) {
            bot.setItemSlot(EquipmentSlot.MAINHAND, weapon.copy());
         }
         inv.setChanged();
      }

      botDropJunk(bot);
      botStageTotem(bot);
   }

   /**
    * How good a weapon is, for the purpose of deciding what the bot swings.
    *
    * <p>0 means "not a weapon". Ordered by what a duelist would rather hold, which
    * is not the same as the damage table: a netherite axe hits harder than a diamond
    * sword, but the sword is the safer thing to be swinging when the fight turns.
    */
   private static int botWeaponRank(ItemStack s) {
      if (s == null || s.isEmpty()) {
         return 0;
      }

      Item i = s.getItem();
      if (i == Items.NETHERITE_SWORD) {
         return 50;
      }
      if (i == Items.DIAMOND_SWORD) {
         return 46;
      }
      if (i == Items.MACE) {
         return 44;
      }
      if (i == Items.TRIDENT) {
         return 42;
      }
      if (i == Items.IRON_SWORD) {
         return 38;
      }
      if (i == Items.NETHERITE_AXE) {
         return 36;
      }
      if (i == Items.DIAMOND_AXE) {
         return 32;
      }
      if (i == Items.STONE_SWORD) {
         return 28;
      }
      if (i == Items.IRON_AXE) {
         return 26;
      }
      if (i == Items.WOODEN_SWORD || i == Items.GOLDEN_SWORD) {
         return 16;
      }
      if (isSword(i)) {
         return 20;
      }
      if (i == Items.STONE_AXE) {
         return 14;
      }
      if (i == Items.WOODEN_AXE || i == Items.GOLDEN_AXE) {
         return 10;
      }
      return 0;
   }

   /**
    * Whether the thing in slot 0 may be moved out of it.
    *
    * <p>Kits put deliberate things in the front slot and "keep the best weapon in
    * front" must not be what throws them away: TNT Run's boost feather, an archer's
    * bow, the Kits selector star. A weapon already in the slot is always replaceable
    * by a better one, and an empty slot always is.
    */
   private static boolean botSlotZeroIsDisposable(ItemStack front) {
      if (front == null || front.isEmpty() || botWeaponRank(front) > 0) {
         return true;
      }

      Item i = front.getItem();
      return !(i == Items.FEATHER || i instanceof BowItem || i instanceof CrossbowItem || i == Items.NETHER_STAR);
   }

   /**
    * Drops the mob-drop junk a bag fills up with, but only when the bag is tight.
    * A full inventory is an inventory that cannot pick up the gapple that would
    * have won the fight, and that is a limitation a player lives with and the bot
    * did not.
    */
   private static void botDropJunk(DuelBot bot) {
      Inventory inv = bot.getInventory();
      int free = 0;

      for (int i = 0; i < BOT_BACKPACK; i++) {
         if (inv.getItem(i).isEmpty()) {
            free++;
         }
      }

      if (free > 4) {
         return;
      }

      int dropped = 0;

      for (int i = 1; i < BOT_BACKPACK && dropped < 4; i++) {
         ItemStack s = inv.getItem(i);
         if (!s.isEmpty() && botJunk(s)) {
            inv.setItem(i, ItemStack.EMPTY);
            bot.drop(s.copy(), false);
            dropped++;
         }
      }

      if (dropped > 0) {
         inv.setChanged();
      }
   }

   /** The things a duelist throws away first when the bag is full. */
   private static boolean botJunk(ItemStack s) {
      Item i = s.getItem();
      return i == Items.ROTTEN_FLESH
         || i == Items.BONE
         || i == Items.STRING
         || i == Items.SPIDER_EYE
         || i == Items.GUNPOWDER
         || i == Items.FEATHER
         || i == Items.FLINT
         || i == Items.GRAVEL
         || i == Items.DIRT
         || i == Items.SUGAR_CANE
         || i == Items.WHEAT_SEEDS
         || i == Items.BEETROOT_SEEDS
         || i == Items.MELON_SEEDS
         || i == Items.PUMPKIN_SEEDS
         || i == Items.POISONOUS_POTATO
         || i == Items.WHEAT
         || i == Items.FERMENTED_SPIDER_EYE
         || i == Items.PHANTOM_MEMBRANE;
   }

   // ------------------------------------------------------------------ potions

   /** True when the potion's own effects are the healing kind. */
   private static boolean potionHeals(ItemStack s) {
      PotionContents pc = s.get(DataComponents.POTION_CONTENTS);
      if (pc == null) {
         return false;
      }

      for (MobEffectInstance e : pc.getAllEffects()) {
         MobEffect eff = e.getEffect().value();
         if (eff == MobEffects.INSTANT_HEALTH.value() || eff == MobEffects.REGENERATION.value() || eff == MobEffects.ABSORPTION.value()) {
            return true;
         }
      }

      return false;
   }

   /** True when the potion is worth drinking before a fight rather than during one. */
   private static boolean potionBuffs(ItemStack s) {
      PotionContents pc = s.get(DataComponents.POTION_CONTENTS);
      if (pc == null) {
         return false;
      }

      for (MobEffectInstance e : pc.getAllEffects()) {
         MobEffect eff = e.getEffect().value();
         if (eff == MobEffects.STRENGTH.value()
            || eff == MobEffects.SPEED.value()
            || eff == MobEffects.JUMP_BOOST.value()
            || eff == MobEffects.RESISTANCE.value()
            || eff == MobEffects.FIRE_RESISTANCE.value()) {
            return true;
         }
      }

      return false;
   }

   /**
    * True when the potion is one you throw at somebody else.
    *
    * <p>A splash potion is a weapon and nothing else: harming, poison, wither,
    * weakness, slowness, mining fatigue, blindness, nausea, hunger. This is what
    * decides whether a splash in the bot's bag leaves its hand at the opponent
    * rather than at its own feet, and it is the reason a Potion of Harming is never
    * drunk as a heal.
    */
   private static boolean potionHarmful(ItemStack s) {
      PotionContents pc = s.get(DataComponents.POTION_CONTENTS);
      if (pc == null) {
         return false;
      }

      for (MobEffectInstance e : pc.getAllEffects()) {
         MobEffect eff = e.getEffect().value();
         if (eff == MobEffects.INSTANT_DAMAGE.value()
            || eff == MobEffects.POISON.value()
            || eff == MobEffects.WITHER.value()
            || eff == MobEffects.WEAKNESS.value()
            || eff == MobEffects.SLOWNESS.value()
            || eff == MobEffects.MINING_FATIGUE.value()
            || eff == MobEffects.BLINDNESS.value()
            || eff == MobEffects.NAUSEA.value()
            || eff == MobEffects.HUNGER.value()
            || eff == MobEffects.LEVITATION.value()
            || eff == MobEffects.DARKNESS.value()) {
            return true;
         }
      }

      return false;
   }

   /**
    * True when the potion is one a duelist would actually want to use.
    *
    * <p>This is the gate the bot was missing. It used to treat *any* drinkable
    * potion as a last-resort heal, so a Potion of Weakness, a Potion of Poison or a
    * bucket of plain Water went down the bot's throat at low health and healed it
    * for nothing - which read, correctly, as the bot drinking potions it had no
    * business drinking.
    */
   private static boolean potionWorthUsing(ItemStack s) {
      return !s.isEmpty() && s.is(Items.POTION) && (potionHeals(s) || potionBuffs(s));
   }

   /**
    * The bot's answer to "I am losing" and to "this is about to start".
    *
    * <p>Potions are the one consumable the AI had no idea about. Kits that hand the
    * bot a Strength splash or a drinkable Healing potion handed it a stat it never
    * spent. This reads the potion's own effects and either drinks it (a real use-item
    * action, so it can be interrupted) or throws it (a real vanilla splash potion).
    */
   private static boolean botTryPotion(Duel d, DuelBot bot, ServerLevel realm, ServerPlayer target, long now, BotRead read, BotDifficulty diff) {
      // A potion is a consumable, not the match. Anything that goes wrong handing one
      // over - and in a 1.8 axe duel it did, which voided the whole match and threw
      // the player out of the arena - must not be allowed to reach the duel tick. If
      // this throws, the bot simply keeps its potions to itself for a while.
      try {
         return botTryPotionInner(d, bot, realm, target, now, read, diff);
      } catch (Throwable t) {
         FortuneFavorsMod.LOGGER.error("Fortune & Favors: bot potion use failed - the bot is skipping potions", t);
         bot.stopUsingItem();
         d.botHands = BotHands.NONE;
         d.botPotionAt = now + 600L;
         return true;
      }
   }

   private static boolean botTryPotionInner(Duel d, DuelBot bot, ServerLevel realm, ServerPlayer target, long now, BotRead read, BotDifficulty diff) {
      if (bot.isUsingItem() || now < d.botPotionAt) {
         return false;
      }

      Inventory inv = bot.getInventory();
      float hp = bot.getHealth() / Math.max(1.0F, bot.getMaxHealth());

      // The offensive splash goes first, because it is a weapon rather than a
      // comfort: a harming, poisoning, weakening or slowing splash is thrown at the
      // opponent, from the distance a potion can actually cross to them. Only a
      // splash whose own effects are the harmful kind is ever thrown at a person -
      // and it is thrown, which is the only thing you can do with one.
      // Thrown from outside a splash's own radius, so it does not poison, slow or
      // weaken the arm that threw it.
      if (read.dist > 3.5 && read.dist < 10.0) {
         for (int i = 0; i < BOT_BACKPACK; i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && s.is(Items.SPLASH_POTION) && potionHarmful(s)) {
               throwSplashPotion(bot, realm, i, target);
               d.botPotionAt = now + 45L;
               realm.playSound(null, bot.getX(), bot.getY(), bot.getZ(), SoundEvents.SPLASH_POTION_THROW, SoundSource.PLAYERS, 0.8F, 1.0F);
               return true;
            }
         }
      }

      // A helpful splash is thrown at the feet of the person who needs it - the
      // bot's own. The bot used to "drink" splashes instantly, which is not a thing
      // a splash potion can do.
      if (hp < 0.75F) {
         for (int i = 0; i < BOT_BACKPACK; i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && s.is(Items.SPLASH_POTION) && (potionHeals(s) || potionBuffs(s))) {
               throwSplashPotion(bot, realm, i, null);
               d.botPotionAt = now + 30L;
               realm.playSound(null, bot.getX(), bot.getY(), bot.getZ(), SoundEvents.SPLASH_POTION_THROW, SoundSource.PLAYERS, 0.8F, 1.0F);
               return true;
            }
         }
      }

      int healSlot = -1;
      int buffSlot = -1;

      for (int i = 0; i < BOT_BACKPACK; i++) {
         ItemStack s = inv.getItem(i);
         // Only a potion whose effects fit the job. `potionHeals` for a wound,
         // `potionBuffs` for the walk up - never simply "a potion".
         if (!potionWorthUsing(s)) {
            continue;
         }
         if (healSlot < 0 && hp < 0.8F && potionHeals(s)) {
            healSlot = i;
         } else if (buffSlot < 0 && potionBuffs(s)) {
            buffSlot = i;
         }
      }

      // A buff is drunk with the fight still a walk away, never in the middle of
      // one - drinking in sword reach is how a person dies with a full belly.
      int slot = healSlot >= 0 ? healSlot : (read.dist > 7.0 ? buffSlot : -1);
      if (slot < 0) {
         return false;
      }

      botHold(bot, slot);
      bot.startUsingItem(InteractionHand.MAIN_HAND);
      if (!bot.isUsingItem()) {
         return false;
      }

      d.botHands = BotHands.CONSUME;
      d.botPotionAt = now + 120L;
      realm.playSound(null, bot.getX(), bot.getY(), bot.getZ(), SoundEvents.GENERIC_DRINK, SoundSource.NEUTRAL, 0.6F, 1.0F);
      return true;
   }

   /** Throws a real vanilla splash potion, at the target or at the thrower's feet. */
   private static void throwSplashPotion(DuelBot bot, ServerLevel realm, int slot, ServerPlayer target) {
      Inventory inv = bot.getInventory();
      ItemStack stack = inv.getItem(slot);
      if (stack.isEmpty()) {
         return;
      }

      ThrownSplashPotion potion = new ThrownSplashPotion(realm, bot, stack.copy());
      potion.setPos(bot.getX(), bot.getEyeY() - 0.15, bot.getZ());
      double tx = target == null ? bot.getX() : target.getX();
      double ty = target == null ? bot.getY() : target.getY() + 1.0;
      double tz = target == null ? bot.getZ() : target.getZ();
      double dx = tx - bot.getX();
      double dz = tz - bot.getZ();
      double horiz = Math.max(0.01, Math.sqrt(dx * dx + dz * dz));
      // A lob, not a laser: the arc is what puts the splash on the ground where it
      // is wanted rather than in the air above it.
      potion.shoot(dx / horiz, (ty - bot.getEyeY()) / horiz + (target == null ? -1.0 : 0.35), dz / horiz, 0.7F, 2.0F);
      realm.addFreshEntity(potion);
      stack.shrink(1);
      if (stack.isEmpty()) {
         inv.setItem(slot, ItemStack.EMPTY);
      }
      inv.setChanged();
   }

   // ------------------------------------------------------- legacy sword block

   /**
    * Raises the bot's sword into the 1.8 blocking stance.
    *
    * <p>Sword blocking is the mod's version of the vanilla `blocks_attacks` state:
    * the SwordBlockManager puts an empty-reduction component on a legacy fighter's
    * sword, the LegacyBlockMixin owns the 50%, and holding right-click is what makes
    * `isBlocking()` true. The bot could not do any of that - it held a sword in a
    * legacy duel and stood there. This starts the real use, and the component is
    * applied here rather than assumed because a bot is not on the player list the
    * manager sweeps.
    *
    * @return true when the block actually started
    */
   public static boolean botBeginSwordBlock(DuelBot bot) {
      if (bot.isUsingItem()) {
         return false;
      }

      int slot = -1;

      for (int i = 0; i < 9; i++) {
         if (isSword(bot.getInventory().getItem(i).getItem())) {
            slot = i;
            break;
         }
      }

      if (slot < 0) {
         return false;
      }

      ItemStack sword = bot.getInventory().getItem(slot);
      if (!sword.has(DataComponents.BLOCKS_ATTACKS)) {
         sword.set(DataComponents.BLOCKS_ATTACKS, SwordBlockManager.blockComponent());
      }

      botHold(bot, slot);
      bot.startUsingItem(InteractionHand.MAIN_HAND);
      return bot.isUsingItem();
   }

   // --------------------------------------------------------------- mace & wind

   /**
    * The mace's whole trick is the fall, and this is the bot doing it.
    *
    * <p>A mace in a bot's bag used to be a slightly worse sword: the AI had one
    * mace routine and it ran only in Mace PvP. Everywhere else the bot looted a
    * mace and swung it like an iron sword, which throws away the one weapon whose
    * damage is earned by falling. This jumps, then lands the smash on the way down.
    *
    * @return true when the bot spent this tick on the mace
    */
   private static boolean botMaceStrike(Duel d, DuelBot bot, ServerLevel realm, ServerPlayer target, long now, BotDifficulty diff) {
      int slot = -1;

      for (int i = 0; i < 9; i++) {
         if (bot.getInventory().getItem(i).is(Items.MACE)) {
            slot = i;
            break;
         }
      }

      if (slot < 0) {
         return false;
      }

      double dx = target.getX() - bot.getX();
      double dy = target.getY() - bot.getY();
      double dz = target.getZ() - bot.getZ();
      double horiz = Math.sqrt(dx * dx + dz * dz);

      // On the way down with the mace in hand: that is the hit worth taking.
      if (bot.fallDistance > 0.7 && horiz < 3.2 && Math.abs(dy) <= VERTICAL_SWING_REACH + 1.0) {
         botHold(bot, slot);
         syncBotHand(d, bot);
         float extra = (float)Math.min(7.0, bot.fallDistance * 1.4);
         applyBotHit(d, bot, realm, target, (6.5F + extra) * diff.damageMultiplier, true);
         com.fortuneandfavors.net.FfVfx.particles(realm, ParticleTypes.EXPLOSION, target.getX(), target.getY() + 0.6, target.getZ(), 1, 0.0, 0.0, 0.0, 0.0);
         d.botCritAt = now + 20L;
         return true;
      }

      // Otherwise earn the fall. Only a bot that commits to the jump gets the
      // smash, and only when the target is roughly on its level.
      if (bot.onGround() && now >= d.botCritAt && horiz < 3.6 && Math.abs(dy) <= LEAP_VERTICAL_REACH && diff != BotDifficulty.EASY) {
         botHold(bot, slot);
         syncBotHand(d, bot);
         bot.jumpFromGround();
         d.botCritAt = now + (diff == BotDifficulty.HACKER ? 30L : (diff == BotDifficulty.HARD ? 45L : 80L));
         return true;
      }

      return false;
   }

   /**
    * Hurls a vanilla wind charge at the target.
    *
    * <p>Wind charges are the same entity the item throws, so the knockback, the
    * burst and the self-boost are all vanilla's; the AI just never used one, which
    * left a Mace PvP bot carrying 128 of them and never throwing a single one.
    *
    * @return true when a charge left the bot's hand
    */
   private static boolean botWindCharge(Duel d, DuelBot bot, ServerLevel realm, ServerPlayer target, long now, double dist) {
      if (now < d.botWindAt || dist < 2.0 || dist > 9.0) {
         return false;
      }

      int slot = findSlotOf(bot, Items.WIND_CHARGE);
      if (slot < 0 || slot >= BOT_BACKPACK) {
         return false;
      }

      ItemStack stack = bot.getInventory().getItem(slot);
      Vec3 dir = new Vec3(target.getX() - bot.getX(), target.getY() + 1.0 - bot.getEyeY(), target.getZ() - bot.getZ()).normalize().scale(1.3);
      WindCharge charge = new WindCharge(realm, bot.getX(), bot.getEyeY() - 0.2, bot.getZ(), dir);
      realm.addFreshEntity(charge);
      stack.shrink(1);
      if (stack.isEmpty()) {
         bot.getInventory().setItem(slot, ItemStack.EMPTY);
      }
      d.botWindAt = now + 35L + (long)(Math.random() * 45.0);
      realm.playSound(null, bot.getX(), bot.getY(), bot.getZ(), SoundEvents.WIND_CHARGE_THROW, SoundSource.PLAYERS, 1.0F, 1.0F);
      return true;
   }

   // ------------------------------------------------------- gladiator trackers

   /**
    * Aims every fighter's Hunter's Compass at their nearest opponent.
    *
    * <p>The Gladiator world is large enough to get lost in, and the hunt used to be
    * a glow and a guess. A compass is a real vanilla compass: `LODESTONE_TRACKER` is
    * the component the client's own compass needle reads, so pointing it at a
    * position makes the needle point there, with no lodestone and no custom renderer.
    *
    * <p>It has always been the same compass. What changed is <i>when</i>: the aim used
    * to be gated behind the end of the gearing window, so for the first ninety seconds
    * - the part of the match where a direction is most useful and hardest to guess -
    * nobody had one at all. It is handed out and re-aimed from the first tick now.
    */
   /**
    * Turns a Gladiator corpse back into an elimination.
    *
    * <p>Every lethal blow in a duel is supposed to arrive through
    * {@link #onDuelLethalDamage}, which absorbs it and marks the fighter eliminated.
    * The gladiator world is the one arena whose floor is dug through by the players in
    * it, so it is also the one place where a body can leave the fight without that hook
    * ever running: a source that damages through another path, a death already logged
    * on the server, an operator's own kill command. Nothing else in the mode notices,
    * and the fighter is left standing over their own corpse. This is the net: one
    * fighter-eliminated path on the match's own tick, so the module's rule (nobody dies
    * in a gladiator world) is enforced by the module rather than by everyone else's
    * damage code being careful.
    */
   private static void gladRescueBodies(Duel d) {
      if (!d.ffa) {
         return;
      }
      for (Participant part : d.parts) {
         if (part.bot || part.eliminated || part.player == null || part.player.isAlive()) {
            continue;
         }
         handleFfaElimination(d, part, part.player, part.player.level().damageSources().generic());
      }
   }

   private static void gladAimTrackers(Duel d, ServerLevel realm, long now) {
      if (now < d.botTrackerAt) {
         return;
      }

      if (d.botTrackerAt == 0L) {
         announce(d, "&bEvery fighter carries a &fHunter's Compass&b - it always points at the nearest opponent.");
         announce(d, "&7Read it from the first second: the needle is how you find the hunt when it opens.");
      }

      d.botTrackerAt = now + 20L;

      for (Participant part : d.parts) {
         ServerPlayer self = part.bot ? part.botEntity : part.player;
         if (self == null || !self.isAlive()) {
            continue;
         }

         ServerPlayer nearest = null;
         double best = Double.MAX_VALUE;

         for (Participant other : d.parts) {
            if (other == part) {
               continue;
            }
            ServerPlayer os = other.bot ? other.botEntity : other.player;
            if (os == null || !os.isAlive()) {
               continue;
            }
            double dist = self.distanceToSqr(os);
            if (dist < best) {
               best = dist;
               nearest = os;
            }
         }

         if (nearest != null) {
            gladAimTracker(self, realm, nearest);
         }
      }
   }

   private static void gladAimTracker(ServerPlayer self, ServerLevel realm, ServerPlayer nearest) {
      Inventory inv = self.getInventory();
      int slot = -1;

      for (int i = 0; i < BOT_BACKPACK; i++) {
         ItemStack s = inv.getItem(i);
         if (s.is(Items.COMPASS) && isHunterTracker(s)) {
            slot = i;
            break;
         }
      }

      if (slot < 0) {
         // Somewhere to put it that does not cost the fighter a slot they were
         // using: the hotbar first, because a tracker you have to open the bag to
         // read is not a tracker, then the main inventory, and nowhere at all if
         // the bag is genuinely full - never on top of something else.
         for (int i = 0; i < BOT_BACKPACK; i++) {
            if (inv.getItem(i).isEmpty()) {
               slot = i;
               break;
            }
         }
      }

      if (slot < 0) {
         return;
      }

      ItemStack tracker = new ItemStack(Items.COMPASS);
      tracker.set(DataComponents.CUSTOM_NAME, Component.literal("§b§lHunter's Compass"));
      tracker.set(
         DataComponents.LORE,
         new ItemLore(
            List.of(
               Component.literal("§7Points at the nearest fighter."),
               Component.literal("§8The lights are on - there is nowhere left to hide.")
            )
         )
      );
      tracker.set(
         DataComponents.LODESTONE_TRACKER,
         new LodestoneTracker(Optional.of(GlobalPos.of(realm.dimension(), nearest.blockPosition())), true)
      );
      inv.setItem(slot, tracker);
      inv.setChanged();
   }

   private static boolean isHunterTracker(ItemStack s) {
      Component name = s.get(DataComponents.CUSTOM_NAME);
      return name != null && name.getString().contains("Hunter");
   }

   private static String tierColor(int tier) {
      return switch (tier) {
         case 1 -> "§7";
         case 2 -> "§e";
         case 3 -> "§b";
         default -> "§7";
      };
   }

   private static List<BwShopEntry> buildBwShop() {
      List<BwShopEntry> out = new ArrayList<>();
      ItemStack wool = new ItemStack(whiteWool(), 16);
      out.add(new BwShopEntry("wool", wool, Items.IRON_INGOT, 4));
      out.add(new BwShopEntry("planks", new ItemStack(Items.OAK_PLANKS, 16), Items.IRON_INGOT, 4));
      out.add(new BwShopEntry("ladder", new ItemStack(Items.LADDER, 8), Items.IRON_INGOT, 4));
      out.add(new BwShopEntry("endstone", new ItemStack(Items.END_STONE, 12), Items.GOLD_INGOT, 6));
      out.add(new BwShopEntry("glass", new ItemStack(Items.GLASS, 4), Items.IRON_INGOT, 6));
      out.add(new BwShopEntry("clay", new ItemStack(Items.TERRACOTTA, 12), Items.IRON_INGOT, 5));
      out.add(new BwShopEntry("obsidian", new ItemStack(Items.OBSIDIAN, 4), Items.EMERALD, 4));
      out.add(new BwShopEntry("shears", new ItemStack(Items.SHEARS), Items.IRON_INGOT, 8));
      out.add(new BwShopEntry("bow", new ItemStack(Items.BOW), Items.GOLD_INGOT, 12));
      out.add(new BwShopEntry("arrows", new ItemStack(Items.ARROW, 8), Items.GOLD_INGOT, 2));
      ItemStack fireball = new ItemStack(Items.FIRE_CHARGE, 4);
      fireball.set(DataComponents.CUSTOM_NAME, Component.literal("§c§lFireball"));
      fireball.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("§7Right-click to hurl an exploding fireball!"))));
      out.add(new BwShopEntry("fireball", fireball, Items.GOLD_INGOT, 5));
      out.add(new BwShopEntry("tnt", new ItemStack(Items.TNT, 2), Items.GOLD_INGOT, 8));
      out.add(new BwShopEntry("cobweb", new ItemStack(Items.COBWEB, 4), Items.IRON_INGOT, 12));
      out.add(new BwShopEntry("golden_apple", new ItemStack(Items.GOLDEN_APPLE), Items.GOLD_INGOT, 3));
      out.add(new BwShopEntry("bread", new ItemStack(Items.BREAD, 8), Items.IRON_INGOT, 6));
      out.add(new BwShopEntry("water_bucket", new ItemStack(Items.WATER_BUCKET), Items.GOLD_INGOT, 6));
      out.add(new BwShopEntry("lava_bucket", new ItemStack(Items.LAVA_BUCKET), Items.GOLD_INGOT, 8));
      out.add(new BwShopEntry("ender_pearl", new ItemStack(Items.ENDER_PEARL), Items.EMERALD, 3));
      ItemStack healPotion = new ItemStack(Items.SPLASH_POTION);
      healPotion.set(DataComponents.POTION_CONTENTS, PotionContents.EMPTY.withPotion(Potions.STRONG_HEALING));
      healPotion.set(DataComponents.CUSTOM_NAME, Component.literal("§aHealing Potion"));
      out.add(new BwShopEntry("heal_potion", healPotion, Items.EMERALD, 2));
      ItemStack jumpPotion = new ItemStack(Items.SPLASH_POTION);
      jumpPotion.set(DataComponents.POTION_CONTENTS, PotionContents.EMPTY.withPotion(Potions.STRONG_LEAPING));
      jumpPotion.set(DataComponents.CUSTOM_NAME, Component.literal("§eJump Boost Potion"));
      out.add(new BwShopEntry("jump_potion", jumpPotion, Items.GOLD_INGOT, 5));
      out.add(new BwShopEntry("bridge_egg", bridgeEggItem(), Items.IRON_INGOT, 24));
      ItemStack kbStick = new ItemStack(Items.STICK);
      kbStick.set(DataComponents.CUSTOM_NAME, Component.literal("§6§lKnockback Stick"));
      kbStick.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("§7Whack enemies right off the map!"))));
      out.add(new BwShopEntry("kb_stick", kbStick, Items.GOLD_INGOT, 6));
      ItemStack speedPotion = new ItemStack(Items.SPLASH_POTION);
      speedPotion.set(DataComponents.POTION_CONTENTS, PotionContents.EMPTY.withPotion(Potions.STRONG_SWIFTNESS));
      speedPotion.set(DataComponents.CUSTOM_NAME, Component.literal("§bSpeed Potion"));
      out.add(new BwShopEntry("speed_potion", speedPotion, Items.GOLD_INGOT, 5));
      return out;
   }

   public static int bwShopSize() {
      return 4 + BW_SHOP.size();
   }

   public static int bwUpgradeSize() {
      return BW_UPGRADES.length;
   }

   public static ItemStack bwShopDisplay(ServerPlayer p, int index) {
      if (index < 0 || index >= bwShopSize()) {
         return ItemStack.EMPTY;
      }

      if (index < 4) {
         return progressiveToolDisplay(p, index);
      }

      int catIndex = index - 4;
      if (catIndex >= BW_SHOP.size()) {
         return ItemStack.EMPTY;
      }

      BwShopEntry e = BW_SHOP.get(catIndex);
      ItemStack s = e.stack.copy();
      if (e.id.equals("wool")) {
         s = new ItemStack(teamWoolFor(p), e.stack.getCount());
      }

      s.set(DataComponents.CUSTOM_NAME, Component.literal("§e§l" + s.getHoverName().getString()));
      List<Component> lore = new ArrayList<>();
      lore.add(Component.literal("§7Cost: §e" + e.cost + " " + currencyName(e.currency)));
      lore.add(Component.literal("§8Click: §f1 §8| §fShift-click: §8x8"));
      s.set(DataComponents.LORE, new ItemLore(lore));
      return s;
   }

   private static ItemStack progressiveToolDisplay(ServerPlayer p, int slotIndex) {
      Duel d = duels.get(p.getUUID());
      Participant part = d != null ? participantOf(d, p) : null;

      String key = switch (slotIndex) {
         case 0 -> "sword";
         case 1 -> "pick";
         case 2 -> "axe";
         default -> "armor";
      };
      int tier = part != null ? part.bwUpgrades.getOrDefault(key, 0) : 0;

      ToolTier[] tiers = switch (slotIndex) {
         case 0 -> SWORD_TIERS;
         case 1 -> PICK_TIERS;
         case 2 -> AXE_TIERS;
         default -> ARMOR_TIERS;
      };

      Item displayIcon = switch (slotIndex) {
         case 0 -> Items.WOODEN_SWORD;
         case 1 -> Items.WOODEN_PICKAXE;
         case 2 -> Items.WOODEN_AXE;
         default -> Items.LEATHER_CHESTPLATE;
      };

      String displayName = switch (slotIndex) {
         case 0 -> "Sword";
         case 1 -> "Pickaxe";
         case 2 -> "Axe";
         default -> "Armor";
      };
      ItemStack s = new ItemStack(displayIcon);
      if (tier >= tiers.length) {
         s.set(DataComponents.CUSTOM_NAME, Component.literal("§a§l" + displayName + " §7(MAXED)"));
         List<Component> lore = new ArrayList<>();
         lore.add(Component.literal("§aAlready at the highest tier!"));
         s.set(DataComponents.LORE, new ItemLore(lore));
      } else {
         ToolTier next = tiers[tier];
         String tc = tierColor(tier + 1);
         s = new ItemStack(next.item());
         s.set(DataComponents.CUSTOM_NAME, Component.literal(tc + "§l" + displayName));
         List<Component> lore = new ArrayList<>();
         if (tier > 0) {
            lore.add(Component.literal("§7Tier: " + tierColor(tier) + "§l" + tierName(tier)));
         }

         lore.add(Component.literal("§7Cost: §e" + next.cost() + " " + currencyName(next.currency())));
         lore.add(Component.literal("§7Replaces your current " + displayName.toLowerCase()));
         s.set(DataComponents.LORE, new ItemLore(lore));
      }

      return s;
   }

   private static String tierName(int tier) {
      return switch (tier) {
         case 1 -> "Wood/Leather";
         case 2 -> "Stone/Chain";
         case 3 -> "Iron";
         case 4 -> "Diamond";
         default -> "";
      };
   }

   public static String bwShopBuy(ServerPlayer p, int index, int amount) {
      if (index >= 0 && index < bwShopSize()) {
         Duel d = duels.get(p.getUUID());
         if (d != null && d.mode == DuelMode.BEDWARS && d.phase == Phase.FIGHT) {
            Participant part = participantOf(d, p);
            if (part == null) {
               return null;
            }

            if (index < 4) {
               return buyProgressiveTool(p, part, index);
            }

            int catIndex = index - 4;
            if (catIndex >= BW_SHOP.size()) {
               return null;
            }

            BwShopEntry e = BW_SHOP.get(catIndex);
            int qty = Math.max(1, Math.min(amount, 8));
            if (e.stack.getMaxStackSize() == 1) {
               qty = 1;
            }

            int cost = e.cost * qty;
            if (InventoryHelper.countItems(p, e.currency) < cost) {
               return "§cNot enough " + currencyName(e.currency) + "! Need " + cost + ".";
            }

            InventoryHelper.removeItems(p, e.currency, cost);
            ItemStack give = e.stack.copy();
            if (e.id.equals("wool")) {
               give = new ItemStack(teamWoolFor(p), e.stack.getCount());
            }

            if (e.id.equals("speed_potion")) {
               give.set(DataComponents.CUSTOM_NAME, Component.literal("§bSpeed Potion"));
            }

            give.setCount(e.stack.getCount() * qty);
            InventoryHelper.giveOrDrop(p, give);
            applyTeamEnchants(p, part);
            return null;
         } else {
            return "§cYou're not in a Bed Wars fight.";
         }
      } else {
         return null;
      }
   }

   private static String buyProgressiveTool(ServerPlayer p, Participant part, int slotIndex) {
      String key = switch (slotIndex) {
         case 0 -> "sword";
         case 1 -> "pick";
         case 2 -> "axe";
         default -> "armor";
      };
      int currentTier = part.bwUpgrades.getOrDefault(key, 0);

      ToolTier[] tiers = switch (slotIndex) {
         case 0 -> SWORD_TIERS;
         case 1 -> PICK_TIERS;
         case 2 -> AXE_TIERS;
         default -> ARMOR_TIERS;
      };
      if (currentTier >= tiers.length) {
         return "§cThat upgrade is already maxed out.";
      }

      ToolTier next = tiers[currentTier];
      if (InventoryHelper.countItems(p, next.currency()) < next.cost()) {
         return "§cNot enough " + currencyName(next.currency()) + "! Need " + next.cost() + ".";
      }

      InventoryHelper.removeItems(p, next.currency(), next.cost());
      part.bwUpgrades.put(key, currentTier + 1);
      if (slotIndex == 3) {
         for (int i = 0; i < ARMOR_SETS[currentTier].length; i++) {
            EquipmentSlot slot = armorSlotFor(ARMOR_SETS[currentTier][i]);
            if (slot != null) {
               p.setItemSlot(slot, new ItemStack(ARMOR_SETS[currentTier][i]));
            }
         }

         if (currentTier > 0 && currentTier - 1 < ARMOR_SETS.length) {
            for (Item oldPiece : ARMOR_SETS[currentTier - 1]) {
               InventoryHelper.removeItems(p, oldPiece, 1);
            }
         }
      } else {
         ToolTier[] toolTiers = switch (slotIndex) {
            case 0 -> SWORD_TIERS;
            case 1 -> PICK_TIERS;
            default -> AXE_TIERS;
         };
         boolean placed = false;
         if (currentTier > 0 && currentTier - 1 < toolTiers.length) {
            Item oldItem = toolTiers[currentTier - 1].item();

            for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
               ItemStack s = p.getInventory().getItem(i);
               if (!s.isEmpty() && s.is(oldItem)) {
                  p.getInventory().setItem(i, new ItemStack(next.item()));
                  placed = true;
                  break;
               }
            }
         }

         if (!placed) {
            InventoryHelper.giveOrDrop(p, new ItemStack(next.item()));
         }
      }

      applyTeamEnchants(p, part);
      p.getInventory().setChanged();
      return null;
   }

   public static ItemStack bwUpgradeDisplay(ServerPlayer p, int index) {
      if (index >= 0 && index < BW_UPGRADES.length) {
         String key = BW_UPGRADES[index];
         int tier = bwTier(p, key);
         int cost = bwUpgradeCost(key, tier);

         ItemStack s = switch (key) {
            case "trap" -> new ItemStack(Items.TRIPWIRE_HOOK);
            case "fatigue" -> new ItemStack(Items.COBWEB);
            case "haste" -> new ItemStack(Items.GOLDEN_PICKAXE);
            case "protection" -> new ItemStack(Items.IRON_CHESTPLATE);
            case "sharpness" -> new ItemStack(Items.IRON_SWORD);
            default -> new ItemStack(Items.FURNACE);
         };
         List<Component> lore = new ArrayList<>();
         lore.add(Component.literal("§7" + bwUpgradeDesc(key)));
         if (cost < 0) {
            lore.add(Component.literal("§aMaxed out!"));
         } else {
            lore.add(Component.literal("§7Cost: §b" + cost + " Diamond"));
         }

         if (key.equals("forge")) {
            lore.add(Component.literal("§7Current: §e" + forgeName(tier)));
         }

         s.set(DataComponents.LORE, new ItemLore(lore));
         s.set(DataComponents.CUSTOM_NAME, Component.literal("§e§l" + bwUpgradeName(key)));
         return s;
      } else {
         return ItemStack.EMPTY;
      }
   }

   public static String bwUpgradeBuy(ServerPlayer p, int index) {
      if (index >= 0 && index < BW_UPGRADES.length) {
         Duel d = duels.get(p.getUUID());
         if (d != null && d.mode == DuelMode.BEDWARS && d.phase == Phase.FIGHT) {
            Participant part = participantOf(d, p);
            if (part == null) {
               return null;
            }

            String key = BW_UPGRADES[index];
            int tier = part.bwUpgrades.getOrDefault(key, key.equals("forge") ? 1 : 0);
            int cost = bwUpgradeCost(key, tier);
            if (cost < 0) {
               return "§cThat upgrade is already maxed out.";
            }

            if (InventoryHelper.countItems(p, Items.DIAMOND) < cost) {
               return "§cNot enough diamonds! Need " + cost + ".";
            }

            InventoryHelper.removeItems(p, Items.DIAMOND, cost);
            part.bwUpgrades.put(key, tier + 1);
            if (key.equals("haste")) {
               applyTeamEffects(p, part);
            }

            if (key.equals("protection") || key.equals("sharpness")) {
               applyTeamEnchants(p, part);
            }

            if (key.equals("trap")) {
               announce(d, "§6§lBed Alarm§r§7 set - intruders will trip it!");
            }

            if (key.equals("fatigue")) {
               announce(d, "§c§lMining Fatigue Trap§r§7 set - intruders will be slowed!");
            }

            return null;
         } else {
            return "§cYou're not in a Bed Wars fight.";
         }
      } else {
         return null;
      }
   }

   private static int bwTier(ServerPlayer p, String key) {
      Duel d = duels.get(p.getUUID());
      if (d == null) {
         return 0;
      }

      Participant part = participantOf(d, p);
      return part == null ? 0 : part.bwUpgrades.getOrDefault(key, key.equals("forge") ? 1 : 0);
   }

   private static int bwUpgradeCost(String key, int tier) {
      return switch (key) {
         case "forge" -> tier >= 5 ? -1 : tier * 2;
         case "trap" -> tier >= 1 ? -1 : 1;
         case "fatigue" -> tier >= 1 ? -1 : 2;
         case "haste" -> tier >= 1 ? -1 : 2;
         case "protection" -> {
            switch (tier) {
               case 0:
                  yield 2;
               case 1:
                  yield 4;
               case 2:
                  yield 6;
               default:
                  yield -1;
            }
         }
         case "sharpness" -> {
            switch (tier) {
               case 0:
                  yield 3;
               case 1:
                  yield 5;
               default:
                  yield -1;
            }
         }
         default -> -1;
      };
   }

   private static String bwUpgradeName(String key) {
      return switch (key) {
         case "forge" -> "Forge";
         case "trap" -> "Bed Alarm";
         case "fatigue" -> "Mining Fatigue Trap";
         case "haste" -> "Haste";
         case "protection" -> "Protection";
         case "sharpness" -> "Sharpness";
         default -> key;
      };
   }

   private static String bwUpgradeDesc(String key) {
      return switch (key) {
         case "forge" -> "Upgrade the generator behind your bed";
         case "trap" -> "Big alarm title when enemy nears your bed";
         case "fatigue" -> "Gives intruders Mining Fatigue II for 5s";
         case "haste" -> "Mine and break blocks faster";
         case "protection" -> "Protection I-III on all your armor";
         case "sharpness" -> "Sharpness I-II on all your swords";
         default -> "";
      };
   }

   private static String forgeName(int tier) {
      return switch (tier) {
         case 2 -> "Iron Forge II";
         case 3 -> "Diamond Forge";
         case 4 -> "Emerald Forge";
         case 5 -> "Molten Forge";
         default -> "Iron Forge";
      };
   }

   private static String currencyName(Item currency) {
      if (currency == Items.GOLD_INGOT) {
         return "Gold";
      } else if (currency == Items.DIAMOND) {
         return "Diamond";
      } else {
         return currency == Items.EMERALD ? "Emerald" : "Iron";
      }
   }

   private static void applyTeamEnchants(ServerPlayer p, Participant part) {
      int prot = part.bwUpgrades.getOrDefault("protection", 0);
      if (prot > 0) {
         for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (!s.isEmpty() && armorSlotFor(s.getItem()) != null) {
               enchantStack(p, s, Enchantments.PROTECTION, prot);
            }
         }
      }

      int sharp = part.bwUpgrades.getOrDefault("sharpness", 0);
      if (sharp > 0) {
         for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (!s.isEmpty() && isSword(s.getItem())) {
               enchantStack(p, s, Enchantments.SHARPNESS, sharp);
            }
         }
      }
   }

   private static void applyTeamEffects(ServerPlayer p, Participant part) {
      if (part.bwUpgrades.getOrDefault("haste", 0) > 0 && !p.hasEffect(MobEffects.HASTE)) {
         p.addEffect(new MobEffectInstance(MobEffects.HASTE, 12000, 0, false, false));
      }
   }

   private static void enchantStack(ServerPlayer p, ItemStack stack, ResourceKey<Enchantment> key, int level) {
      try {
         Registry<Enchantment> reg = p.level().registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
         stack.enchant(reg.getOrThrow(key), level);
      } catch (Exception var5) {
      }
   }

   /**
    * The Bed Wars bridge egg - and, next to it, the test that recognises one.
    *
    * <p>The tag lives on the <b>item</b>, and that is the whole reason the item
    * used to do nothing: the block-laying mixin read the component map off the
    * thrown egg <i>entity</i>, which is empty, so a bridge egg was just an egg
    * that happened to be named one. The shop and the mixin both come through here
    * now, so there is one definition of what a bridge egg is and a probe for it
    * (see the self-test), instead of two copies that can drift apart again.
    */
   public static ItemStack bridgeEggItem() {
      ItemStack egg = new ItemStack(Items.EGG);
      egg.set(DataComponents.CUSTOM_NAME, Component.literal("§e§lBridge Egg"));
      egg.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("§7Throw it - it lays a bridge of blocks as it flies!"))));
      CompoundTag tag = new CompoundTag();
      tag.putInt("ff_bridge_egg", 1);
      egg.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
      return egg;
   }

   /** True when this stack, thrown, lays a bridge of blocks behind it. */
   public static boolean isBridgeEgg(ItemStack stack) {
      if (stack == null || stack.isEmpty()) {
         return false;
      }

      CustomData data = stack.get(DataComponents.CUSTOM_DATA);
      return data != null && data.copyTag().getInt("ff_bridge_egg").orElse(0) == 1;
   }

   public static boolean canOpenDuelChest(ServerPlayer p, BlockPos pos) {
      Duel d = duels.get(p.getUUID());
      if (d != null && d.mode == DuelMode.BEDWARS) {
         Participant part = participantOf(d, p);

         for (Participant other : d.parts) {
            if (other.teamChest != null && other.teamChest.equals(pos)) {
               return other == part;
            }
         }

         return true;
      } else {
         return true;
      }
   }

   public static boolean isTeamChest(ServerPlayer p, BlockPos pos) {
      Duel d = duels.get(p.getUUID());
      if (d != null && d.mode == DuelMode.BEDWARS) {
         for (Participant other : d.parts) {
            if (other.teamChest != null && other.teamChest.equals(pos)) {
               return true;
            }
         }

         return false;
      } else {
         return false;
      }
   }

   public static boolean isProtectedDuelBlock(ServerPlayer p, BlockPos pos) {
      Duel d = duels.get(p.getUUID());
      if (d == null) {
         return false;
      }

      // A block a fighter put down is never the map, so it is never protected -
      // whatever the map's own zones claim. This was the Skywars bug: the mode
      // protects a box around each spawn chest so nobody mines the island out from
      // under the spawn, and a bot's bridge *starts inside that box*. Every block
      // the bot laid on the way off its island was therefore unbreakable, which
      // read as "the bot's blocks can't be broken" - and the player could do
      // nothing about the bridge they were standing on.
      if (d.playerPlacedBlocks.contains(BlockPos.asLong(pos.getX(), pos.getY(), pos.getZ()))) {
         return false;
      }

      if (d.mode == DuelMode.SKYWARS && d.arena.inChamber(pos)) {
         return true;
      }

      if (d.mode == DuelMode.BEDWARS) {
         BlockState state = p.level().getBlockState(pos);
         if (state.is(Blocks.CRAFTING_TABLE)) {
            return true;
         }
      }

      return false;
   }

   public static boolean isBedwarsDuel(ServerPlayer p) {
      Duel d = duels.get(p.getUUID());
      return d != null && d.mode == DuelMode.BEDWARS && d.phase == Phase.FIGHT;
   }

   public static boolean isLuckyPvpDuel(ServerPlayer p) {
      Duel d = duels.get(p.getUUID());
      return d != null && d.mode == DuelMode.LUCKYPvP && d.phase == Phase.FIGHT;
   }

   public static boolean quickStoreToChest(ServerPlayer p, BlockPos pos) {
      Duel d = duels.get(p.getUUID());
      if (d != null && d.mode == DuelMode.BEDWARS) {
         Participant part = participantOf(d, p);
         if (part != null && part.teamChest != null && part.teamChest.equals(pos)) {
            Item currency = currencyOf(p.getMainHandItem());
            if (currency == null) {
               return false;
            }

            boolean ender = p.level().getBlockState(pos).is(Blocks.ENDER_CHEST);
            BlockEntity be = p.level().getBlockEntity(pos);
            ChestBlockEntity chest = ender ? null : (be instanceof ChestBlockEntity c ? c : null);
            if (chest == null && !ender) {
               return false;
            }

            int deposited = 0;

            for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
               ItemStack s = p.getInventory().getItem(i);
               if (!s.isEmpty() && s.is(currency)) {
                  int moved = ender ? depositIntoList(part.teamChestItems, s.copy()) : depositInto(chest, s.copy());
                  if (moved > 0) {
                     s.shrink(moved);
                     deposited += moved;
                  }
               }
            }

            if (deposited > 0) {
               p.getInventory().setChanged();
               // The team chest is a real chest now, so the total has to be read
               // off the chest itself - reporting the old virtual list would have
               // told every player their deposit went into an empty chest.
               int total = ender ? stashCount(part.teamChestItems, currency) : countInChest(chest, currency);
               Chat.msg(p, "§7Deposited §e" + deposited + " " + currencyName(currency) + " §7into your team chest (§e" + total + " §7total).");
               p.level().playSound(null, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, SoundEvents.CHEST_CLOSE, SoundSource.BLOCKS, 0.8F, 1.3F);
               return true;
            } else {
               return false;
            }
         } else {
            return false;
         }
      } else {
         return false;
      }
   }

   public static void describeTeamChest(ServerPlayer p, BlockPos pos) {
      Duel d = duels.get(p.getUUID());
      if (d != null) {
         Participant part = participantOf(d, p);
         if (part != null && part.teamChest != null && part.teamChest.equals(pos)) {
            List<ItemStack> items = new ArrayList<>();
            if (p.level().getBlockEntity(pos) instanceof ChestBlockEntity chest) {
               for (int i = 0; i < chest.getContainerSize(); i++) {
                  items.add(chest.getItem(i));
               }
            }
            if (items.isEmpty()) {
               items = part.teamChestItems;
            }
            boolean any = false;

            for (ItemStack s : items) {
               if (!s.isEmpty()) {
                  any = true;
                  break;
               }
            }

            if (!any) {
               Chat.msg(p, "§7Your team chest is empty - left-click it while holding a §eIron§7/§eGold§7/§eDiamond§7/§eEmerald§7 to store, or open it to take things out.");
            } else {
               List<String> parts = new ArrayList<>();

               for (Item item : new Item[]{Items.IRON_INGOT, Items.GOLD_INGOT, Items.DIAMOND, Items.EMERALD}) {
                  int n = stashCount(items, item);
                  if (n > 0) {
                     parts.add("§e" + n + " " + currencyName(item));
                  }
               }

               if (parts.isEmpty()) {
                  Chat.msg(p, "§7Your team chest holds §e" + items.size() + " §7stack(s) - open it to see them.");
               } else {
                  Chat.msg(p, "§7Team chest: " + String.join("§7, ", parts) + "§7 - left-click it while holding a resource to store more.");
               }
            }
         }
      }
   }

   private static int countInChest(ChestBlockEntity chest, Item item) {
      int n = 0;

      for (int i = 0; i < chest.getContainerSize(); i++) {
         ItemStack s = chest.getItem(i);
         if (!s.isEmpty() && s.is(item)) {
            n += s.getCount();
         }
      }

      return n;
   }

   private static int stashCount(List<ItemStack> items, Item item) {
      int n = 0;

      for (ItemStack s : items) {
         if (!s.isEmpty() && s.is(item)) {
            n += s.getCount();
         }
      }

      return n;
   }

   private static int depositIntoList(List<ItemStack> storage, ItemStack stack) {
      int remaining = stack.getCount();

      for (ItemStack s : storage) {
         if (!s.isEmpty() && ItemStack.isSameItem(s, stack) && s.getCount() < s.getMaxStackSize()) {
            int take = Math.min(remaining, s.getMaxStackSize() - s.getCount());
            s.grow(take);
            remaining -= take;
         }
      }

      for (int i = 0; i < storage.size() && remaining > 0; i++) {
         if (storage.get(i).isEmpty()) {
            ItemStack add = stack.copy();
            add.setCount(Math.min(remaining, add.getMaxStackSize()));
            storage.set(i, add);
            remaining -= add.getCount();
         }
      }

      while (remaining > 0) {
         ItemStack add = stack.copy();
         add.setCount(Math.min(remaining, add.getMaxStackSize()));
         storage.add(add);
         remaining -= add.getCount();
      }

      return stack.getCount() - remaining;
   }

   private static Item currencyOf(ItemStack s) {
      if (s.isEmpty()) {
         return null;
      }

      Item i = s.getItem();
      return i != Items.IRON_INGOT && i != Items.GOLD_INGOT && i != Items.DIAMOND && i != Items.EMERALD ? null : i;
   }

   private static int depositInto(ChestBlockEntity chest, ItemStack stack) {
      int remaining = stack.getCount();

      for (int i = 0; i < chest.getContainerSize() && remaining > 0; i++) {
         ItemStack s = chest.getItem(i);
         if (!s.isEmpty() && ItemStack.isSameItem(s, stack) && s.getCount() < s.getMaxStackSize()) {
            int take = Math.min(remaining, s.getMaxStackSize() - s.getCount());
            s.grow(take);
            remaining -= take;
         }
      }

      for (int i = 0; i < chest.getContainerSize() && remaining > 0; i++) {
         if (chest.getItem(i).isEmpty()) {
            ItemStack add = stack.copy();
            add.setCount(Math.min(remaining, add.getMaxStackSize()));
            chest.setItem(i, add);
            remaining -= add.getCount();
         }
      }

      return stack.getCount() - remaining;
   }

   public static InteractionResult onVillagerUse(ServerPlayer p, Entity entity) {
      Duel d = duels.get(p.getUUID());
      if (d != null && d.mode == DuelMode.BEDWARS) {
         String role = (String)d.villagerRoles.get(entity.getUUID());
         if (role == null) {
            return InteractionResult.PASS;
         }

         Participant part = participantOf(d, p);
         if (part == null) {
            return InteractionResult.PASS;
         }

         if (!role.endsWith(String.valueOf(part.slot))) {
            Chat.msg(p, "§cThat's the enemy's villager!");
            return InteractionResult.FAIL;
         }

         if (role.startsWith("shop")) {
            BedwarsShopMenu.open(p);
         } else {
            BedwarsUpgradeMenu.open(p);
         }

         return InteractionResult.SUCCESS;
      } else {
         return InteractionResult.PASS;
      }
   }

   public static void onDuelBlockBroken(ServerLevel level, BlockPos pos, ServerPlayer player) {
      if (isDuelRealm(level)) {
         Duel d = duels.get(player.getUUID());
         if (d != null && d.mode == DuelMode.BEDWARS) {
            for (int slot = 0; slot < d.parts.size(); slot++) {
               BlockPos bed = d.arena.bedFor(slot);
               BlockPos head = d.arena.bedHeadFor(slot);
               boolean isBedPart = bed != null && bed.equals(pos) || head != null && head.equals(pos);
               if (isBedPart && ((Participant)d.parts.get(slot)).bedIntact) {
                  ((Participant)d.parts.get(slot)).bedIntact = false;
                  Participant part = (Participant)d.parts.get(slot);
                  announce(
                     d,
                     "§c⛔ "
                        + part.displayName
                        + "'s bed was destroyed"
                        + (part.player != null ? " - " + part.player.getName().getString() + " can no longer respawn!" : "!")
                  );
                  com.fortuneandfavors.net.FfVfx.particles(level, ParticleTypes.LAVA, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 30, 0.8, 0.8, 0.8, 0.1);
                  level.playSound(null, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, SoundEvents.GLASS_BREAK, SoundSource.BLOCKS, 1.0F, 0.8F);
               }
            }
         }
      }
   }

   public static boolean isBedwarsBed(ServerLevel level, BlockPos pos) {
      if (!isDuelRealm(level)) {
         return false;
      }

      for (Duel d : duels.values()) {
         if (d != null && d.mode == DuelMode.BEDWARS && d.phase == Phase.FIGHT) {
            for (int slot = 0; slot < d.parts.size(); slot++) {
               BlockPos bed = d.arena.bedFor(slot);
               BlockPos head = d.arena.bedHeadFor(slot);
               if (bed != null && bed.equals(pos) || head != null && head.equals(pos)) {
                  return true;
               }
            }
         }
      }

      return false;
   }

   public static boolean isOwnBed(ServerPlayer p, ServerLevel level, BlockPos pos) {
      if (isDuelRealm(level) && p != null) {
         Duel d = duels.get(p.getUUID());
         if (d != null && d.mode == DuelMode.BEDWARS && d.phase == Phase.FIGHT) {
            Participant part = participantOf(d, p);
            if (part == null) {
               return false;
            }

            BlockPos bed = d.arena.bedFor(part.slot);
            BlockPos head = d.arena.bedHeadFor(part.slot);
            return bed != null && bed.equals(pos) || head != null && head.equals(pos);
         } else {
            return false;
         }
      } else {
         return false;
      }
   }

   private static ServerLevel getRealm(MinecraftServer server) {
      return server != null ? server.getLevel(DUEL_REALM) : null;
   }

   private static ServerLevel realmOf(ServerPlayer p) {
      return p.level();
   }

   private static boolean teleportTo(ServerLevel realm, ServerPlayer p, double[] xyz) {
      try {
         p.teleport(
            new TeleportTransition(realm, new Vec3(xyz[0], xyz[1], xyz[2]), Vec3.ZERO, p.getYRot(), p.getXRot(), TeleportTransition.PLACE_PORTAL_TICKET)
         );
         p.setDeltaMovement(0.0, 0.0, 0.0);
         return true;
      } catch (Exception e) {
         try {
            p.teleportTo(p.getX(), p.getY(), p.getZ());
            p.teleport(
               new TeleportTransition(realm, new Vec3(xyz[0], xyz[1], xyz[2]), Vec3.ZERO, p.getYRot(), p.getXRot(), TeleportTransition.PLACE_PORTAL_TICKET)
            );
            p.setDeltaMovement(0.0, 0.0, 0.0);
            return true;
         } catch (Exception e2) {
            FortuneFavorsMod.LOGGER.error("Fortune & Favors: teleport failed for " + p.getName().getString(), e2);
            return false;
         }
      }
   }

   /**
    * The end-of-match card: what both sides actually did, and a grade.
    *
    * <p>A duel that ends in "you won" and silence tells you nothing about why,
    * which is the difference between a match and a coin flip. Everything on this
    * card is real ledger data - {@link #onDuelAfterDamage} books every landed
    * blow - so a player can read their hits, their damage, their best combo and
    * how much health they finished on, and work out what to change.
    */
   private static void sendMatchReport(Duel d, Participant winner) {
      ServerLevel realm = getRealm(serverRef);
      long now = realm != null ? ServerClock.clock(realm) : 0L;
      long ticks = d.fightStartedAt > 0L && now > d.fightStartedAt ? now - d.fightStartedAt : 0L;
      String duration = ticks <= 0L ? "-" : String.format(Locale.US, "%d:%02d", ticks / 1200L, ticks / 20L % 60L);
      String rule = "§8§m                                            ";

      for (Participant part : d.parts) {
         if (part.bot || part.player == null || part.player.connection == null) {
            continue;
         }
         ServerPlayer p = part.player;
         Chat.raw(p, rule);
         Chat.raw(p, "§6§lMATCH REPORT §8| §f" + d.mode.display + " §8| §7lasted §f" + duration);

         for (Participant other : d.parts) {
            if (other == part) {
               continue;
            }
            String tag = other == winner ? "§a" : "§c";
            String hp;
            if (other.bot) {
               hp = "§7alive at §f" + Math.max(0, Math.round(other.botEntity != null ? other.botEntity.getHealth() : 0.0F)) + "♥";
            } else if (other.player != null) {
               hp = "§7finished on §f" + Math.max(0, Math.round(other.player.getHealth())) + "♥";
            } else {
               hp = "";
            }
            Chat.raw(
               p,
               tag + other.displayName + " §8- §f" + other.hitsLanded + " hits §8| §f" + oneDecimal(other.damageDealt) + " dealt"
                  + " §8| §f" + oneDecimal(other.damageTaken) + " taken §8| " + hp
            );
         }

         Chat.raw(
            p,
            "§7You: §f" + part.hitsLanded + " hits §8| §f" + oneDecimal(part.damageDealt) + " damage dealt §8| §f"
               + oneDecimal(part.damageTaken) + " taken §8| §7best combo §f" + bestCombos.getOrDefault(part.uuid, 0)
         );
         Chat.raw(p, "§7Grade: " + grade(part, winner, ticks));
         Chat.raw(p, rule);
      }
   }

   /** A rough S/A/B/C on how the match went, from accuracy, aggression and the
    *  health you finished on. It is a summary, not a ranking - the leaderboard is
    *  the ranking. */
   private static String grade(Participant part, Participant winner, long ticks) {
      if (part != winner) {
         return part.hitsLanded >= 6 ? "§cC §7- you landed real hits, you just lost the trade" : "§4D §7- outplayed this round";
      }
      float hp = part.player != null ? part.player.getHealth() : 0.0F;
      float max = part.player != null ? Math.max(1.0F, part.player.getMaxHealth()) : 20.0F;
      float share = hp / max;
      int score = 0;
      if (part.hitsLanded >= 8) {
         score += 2;
      } else if (part.hitsLanded >= 4) {
         score++;
      }
      if (share >= 0.6F) {
         score += 3;
      } else if (share >= 0.35F) {
         score += 2;
      } else if (share >= 0.15F) {
         score++;
      }
      if (ticks > 0L && ticks < 600L) {
         score++;
      }
      if (score >= 5) {
         return "§6§lS §7- near-perfect; barely touched";
      }
      if (score >= 4) {
         return "§aA §7- clean win";
      }
      if (score >= 2) {
         return "§eB §7- won the trade";
      }
      return "§7C §7- a win is a win";
   }

   private static String oneDecimal(float value) {
      return String.format(Locale.US, "%.1f", value);
   }

   private static void recordResult(UUID uuid, String name, boolean won, DuelMode mode) {
      if (uuid != null && mode != null) {
         long[] rec = duelStats.computeIfAbsent(uuid, k -> new HashMap<>()).computeIfAbsent(mode.name(), k -> new long[2]);
         if (won) {
            rec[0]++;
         } else {
            rec[1]++;
         }

         if (name != null && !name.isBlank()) {
            statNames.put(uuid, name);
         }
      }
   }

   public static long[] statsOf(UUID uuid, DuelMode mode) {
      return recordOf(uuid, mode);
   }

   public static List<LeaderboardRow> leaderboardRows(DuelMode mode) {
      List<Object[]> rows = new ArrayList<>();

      for (UUID uuid : duelStats.keySet()) {
         long wins = 0L;
         long losses = 0L;
         if (mode == null) {
            for (long[] rec : duelStats.get(uuid).values()) {
               wins += rec[0];
               losses += rec[1];
            }
         } else {
            long[] rec = recordOf(uuid, mode);
            wins = rec[0];
            losses = rec[1];
         }

         if (wins + losses > 0L) {
            rows.add(new Object[]{uuid, wins, losses});
         }
      }

      rows.sort((a, b) -> {
         long wd = (Long)b[1] - (Long)a[1];
         if (wd != 0L) {
            return (int)Math.signum((float)wd);
         }

         long ad = (Long)a[2] - (Long)b[2];
         return (int)Math.signum((float)ad);
      });
      List<LeaderboardRow> out = new ArrayList<>();

      for (int i = 0; i < Math.min(10, rows.size()); i++) {
         Object[] row = rows.get(i);
         UUID uuid = (UUID)row[0];
         String name = statNames.getOrDefault(uuid, uuid.toString().substring(0, 8));
         out.add(new LeaderboardRow(name, (Long)row[1], (Long)row[2]));
      }

      return out;
   }

   private static long[] recordOf(UUID uuid, DuelMode mode) {
      Map<String, long[]> modes = duelStats.get(uuid);
      if (modes == null) {
         return new long[2];
      }

      if (mode != null) {
         long[] rec = modes.get(mode.name());
         return rec != null ? rec : new long[2];
      }

      long wins = 0L;
      long losses = 0L;

      for (long[] rec : modes.values()) {
         wins += rec[0];
         losses += rec[1];
      }

      return new long[]{wins, losses};
   }

   public static String leaderboardText(MinecraftServer server, DuelMode mode) {
      List<Object[]> rows = new ArrayList<>();

      for (UUID uuid : duelStats.keySet()) {
         long wins = 0L;
         long losses = 0L;
         if (mode == null) {
            for (long[] rec : duelStats.get(uuid).values()) {
               wins += rec[0];
               losses += rec[1];
            }
         } else {
            long[] rec = recordOf(uuid, mode);
            wins = rec[0];
            losses = rec[1];
         }

         if (wins + losses > 0L) {
            rows.add(new Object[]{uuid, wins, losses});
         }
      }

      rows.sort((a, b) -> {
         long wd = (Long)b[1] - (Long)a[1];
         if (wd != 0L) {
            return (int)Math.signum((float)wd);
         }

         long ad = (Long)a[2] - (Long)b[2];
         return (int)Math.signum((float)ad);
      });
      StringBuilder sb = new StringBuilder();
      sb.append("§6§lDuel Leaderboard").append(mode != null ? " §r§7(" + mode.display + ")" : "").append("\n");
      int shown = Math.min(10, rows.size());
      if (shown == 0) {
         sb.append("§7No duels recorded yet - go fight!");
         return sb.toString();
      }

      for (int i = 0; i < shown; i++) {
         Object[] row = rows.get(i);
         UUID uuid = (UUID)row[0];
         long wins = (Long)row[1];
         long losses = (Long)row[2];
         String name = statNames.getOrDefault(uuid, uuid.toString().substring(0, 8));
         int pct = (int)Math.round(100.0 * wins / (wins + losses));
         sb.append("§7")
            .append(i + 1)
            .append(". §f")
            .append(name)
            .append(" §8- §a")
            .append(wins)
            .append("W §c")
            .append(losses)
            .append("L")
            .append(" §7(")
            .append(pct)
            .append("%)\n");
      }

      return sb.toString();
   }

   public static String statsText(UUID uuid, String displayName) {
      Map<String, long[]> modes = duelStats.get(uuid);
      String name = displayName != null ? displayName : statNames.getOrDefault(uuid, uuid.toString().substring(0, 8));
      StringBuilder sb = new StringBuilder();
      sb.append("§6§l").append(name).append("§r§7's duel record\n");
      boolean any = false;

      for (DuelMode m : DuelMode.values()) {
         long[] rec = modes != null && modes.containsKey(m.name()) ? modes.get(m.name()) : null;
         if (rec != null && rec[0] + rec[1] != 0L) {
            any = true;
            int pct = (int)Math.round(100.0 * rec[0] / (rec[0] + rec[1]));
            sb.append("§e")
               .append(m.display)
               .append(" §8- §a")
               .append(rec[0])
               .append("W")
               .append(" §c")
               .append(rec[1])
               .append("L §7(")
               .append(pct)
               .append("%)\n");
         }
      }

      if (!any) {
         sb.append("§7No duels recorded yet.");
      }

      return sb.toString();
   }

   private static int aliveFfaCount(Duel d) {
      int alive = 0;

      for (Participant part : d.parts) {
         if (!part.eliminated) {
            boolean living = part.bot ? part.botEntity != null && part.botEntity.isAlive() : part.player != null && part.player.isAlive();
            if (living) {
               alive++;
            }
         }
      }

      return alive;
   }

   private static List<String> ffaBoardLines(Duel d, Participant part) {
      List<String> lines = new ArrayList<>();
      lines.add("§eRemaining: §f" + aliveFfaCount(d));
      lines.add("§bYour kills: §f" + part.kills);
      lines.add("§dYour streak: §f" + part.killStreak);
      return lines;
   }

   private static void showFfaScoreboards(Duel d) {
      try {
         Scoreboard sb = serverRef.getScoreboard();

         for (Participant part : d.parts) {
            if (!part.bot && part.player != null) {
               int color = allocBoardSlot();
               if (color >= 0) {
                  String id = "fff" + d.arena.ox + "x" + d.arena.oz + "s" + part.slot;

                  try {
                     Objective leftover = sb.getObjective(id);
                     if (leftover != null) {
                        sb.removeObjective(leftover);
                     }
                  } catch (Exception var10) {
                  }

                  Objective obj = sb.addObjective(
                     id, ObjectiveCriteria.DUMMY, Component.literal("§6§l⚔ " + d.mode.display + " FFA"), RenderType.INTEGER, false, BlankFormat.INSTANCE
                  );
                  PlayerTeam team = sb.addPlayerTeam(id + "t");
                  team.setColor(Optional.of(TEAM_COLORS[color]));

                  try {
                     sb.addPlayerToTeam(part.player.getScoreboardName(), team);
                  } catch (Exception var9) {
                  }

                  FfaBoard board = new FfaBoard();
                  board.obj = obj;
                  board.team = team;
                  board.slotIdx = color;
                  board.playerName = part.player.getScoreboardName();
                  board.playerUuid = part.uuid;
                  board.lines = ffaBoardLines(d, part);
                  applyScoreboardLines(sb, obj, board.lines);
                  sb.setDisplayObjective(TEAM_SLOTS[color], obj);
                  d.ffaBoards.add(board);
               }
            }
         }

         d.scoreboardShown = true;
         d.scoreboardLines = null;
      } catch (Exception e) {
         FortuneFavorsMod.LOGGER.warn("Fortune & Favors: could not show the FFA scoreboards", e);
      }
   }

   private static void refreshFfaScoreboards(Duel d) {
      try {
         Scoreboard sb = serverRef.getScoreboard();

         for (FfaBoard board : d.ffaBoards) {
            Participant part = null;
            Iterator lines = d.parts.iterator();

            while (true) {
               if (lines.hasNext()) {
                  Participant p = (Participant)lines.next();
                  if (!p.uuid.equals(board.playerUuid)) {
                     continue;
                  }

                  part = p;
               }

               if (part == null || board.obj == null) {
                  break;
               }

               List<String> linesx = ffaBoardLines(d, part);
               if (board.lines != null) {
                  for (String old : board.lines) {
                     if (!linesx.contains(old)) {
                        sb.resetAllPlayerScores(ScoreHolder.forNameOnly(old));
                     }
                  }
               }

               applyScoreboardLines(sb, board.obj, linesx);
               board.lines = linesx;
               break;
            }
         }
      } catch (Exception var8) {
      }
   }

   private static void hideFfaScoreboards(Duel d, Scoreboard sb) {
      for (FfaBoard board : d.ffaBoards) {
         if (board.obj != null) {
            try {
               sb.removeObjective(board.obj);
            } catch (Exception var5) {
            }
         }

         if (board.team != null) {
            try {
               if (board.playerName != null) {
                  sb.removePlayerFromTeam(board.playerName);
               }

               sb.removePlayerTeam(board.team);
            } catch (Exception var6) {
            }
         }

         freeBoardSlot(board.slotIdx);
      }

      d.ffaBoards.clear();
   }

   private static void showDuelScoreboard(Duel d) {
      try {
         if (serverRef == null || d.scoreboardShown) {
            return;
         }

         if (d.ffa) {
            showFfaScoreboards(d);
            return;
         }

         Scoreboard sb = serverRef.getScoreboard();
         int color = allocBoardSlot();
         if (color < 0) {
            return;
         }

         d.scoreboardSlot = color;
         String id = "ffd" + d.arena.ox + "x" + d.arena.oz;

         try {
            Objective leftover = sb.getObjective(id);
            if (leftover != null) {
               sb.removeObjective(leftover);
            }
         } catch (Exception var8) {
         }

         try {
            PlayerTeam leftoverTeam = sb.getPlayerTeam(id + "t");
            if (leftoverTeam != null) {
               for (String member : new ArrayList<>(leftoverTeam.getPlayers())) {
                  sb.removePlayerFromTeam(member);
               }

               sb.removePlayerTeam(leftoverTeam);
            }
         } catch (Exception var9) {
         }

         Objective obj = sb.addObjective(
            id, ObjectiveCriteria.DUMMY, Component.literal("§6§l⚔ " + d.mode.display), RenderType.INTEGER, false, BlankFormat.INSTANCE
         );
         PlayerTeam team = sb.addPlayerTeam(id + "t");
         team.setColor(Optional.of(TEAM_COLORS[color]));

         for (Participant part : d.parts) {
            if (!part.bot && part.player != null) {
               sb.addPlayerToTeam(part.player.getScoreboardName(), team);
            }
         }

         applyScoreboardLines(sb, obj, scoreboardLines(d));
         sb.setDisplayObjective(TEAM_SLOTS[color], obj);
         d.scoreboardObj = obj;
         d.scoreboardTeam = team;
         d.scoreboardLines = scoreboardLines(d);
         d.scoreboardShown = true;
      } catch (Exception e) {
         FortuneFavorsMod.LOGGER.warn("Fortune & Favors: could not show the duel scoreboard", e);
      }
   }

   private static List<String> scoreboardLines(Duel d) {
      List<String> lines = new ArrayList<>();

      for (Participant part : d.parts) {
         if (!part.bot && part.player != null) {
            String name = part.displayName;
            if (d.mode == DuelMode.DRAFT) {
               lines.add("§b" + name + " §7Drafted:");
               String summary = draftSummary(part);
               if (summary.isEmpty()) {
                  lines.add("  §8(nothing yet)");
               } else {
                  for (String piece : splitSummary(summary)) {
                     lines.add("  §f" + piece);
                  }
               }
            } else if (d.mode == DuelMode.BEDWARS) {
               String bedIcon = part.bedIntact ? "§a✔" : "§c✖";
               int prot = part.bwUpgrades.getOrDefault("protection", 0);
               int sharp = part.bwUpgrades.getOrDefault("sharpness", 0);
               lines.add("§f" + name);
               lines.add("  §eBed §7" + bedIcon + " §r§7| §bProt §f" + prot + " §7| §fSharp §f" + sharp);
            } else {
               long[] rec = recordOf(part.uuid, d.mode);
               long wins = rec[0];
               long losses = rec[1];
               int pct = (int)Math.round(wins + losses == 0L ? 0.0 : 100.0 * wins / (wins + losses));
               lines.add("§f" + name + " §aWins: §f" + wins);
               lines.add("§f" + name + " §cLosses: §f" + losses);
               lines.add("§f" + name + " §eW/L: §f" + pct + "%");
               if (d.mode == DuelMode.COMBODUEL) {
                  lines.add("§f" + name + " §eCombo: §f" + part.comboCount + " §7| §dBest: §f" + bestCombos.getOrDefault(part.uuid, 0));
               }
            }
         }
      }

      int specCount = 0;

      for (SpectatorSession s : spectatorSessions.values()) {
         if (s.arenaOx() == d.arena.ox && s.arenaOz() == d.arena.oz) {
            specCount++;
         }
      }

      if (specCount > 0) {
         lines.add("");
         lines.add("§d◆ §f" + specCount + " §7" + (specCount == 1 ? "spectator" : "spectators"));
      }

      return lines;
   }

   private static String draftSummary(Participant part) {
      List<String> names = new ArrayList<>();
      if (!part.draftArmor.isEmpty()) {
         Item first = ((ItemStack)part.draftArmor.get(0)).getItem();
         if (first == Items.DIAMOND_HELMET || first == Items.DIAMOND_CHESTPLATE || first == Items.DIAMOND_LEGGINGS || first == Items.DIAMOND_BOOTS) {
            names.add("Full Diamond Armor");
         } else if (first != Items.IRON_HELMET && first != Items.IRON_CHESTPLATE && first != Items.IRON_LEGGINGS && first != Items.IRON_BOOTS) {
            names.add("Armor Set");
         } else {
            names.add("Full Iron Armor");
         }
      }

      for (ItemStack s : part.draftKit) {
         if (!s.isEmpty()) {
            Item i = s.getItem();
            String n = null;
            if (i == Items.DIAMOND_SWORD) {
               n = "Sword";
            } else if (i == Items.BOW) {
               n = "Bow";
            } else if (i == Items.ARROW) {
               n = "Arrows";
            } else if (i == Items.ENDER_PEARL) {
               n = "Ender Pearls";
            } else if (i == Items.SPLASH_POTION) {
               PotionContents pc = (PotionContents)s.get(DataComponents.POTION_CONTENTS);
               if (pc != null && pc.is(Potions.HEALING)) {
                  n = "Healing Potions";
               } else if (pc != null && pc.is(Potions.STRONG_SWIFTNESS)) {
                  n = "Speed Potions";
               } else {
                  n = "Potions";
               }
            } else if (i == Items.GOLDEN_APPLE) {
               n = "Golden Apples";
            } else if (i == Items.IRON_AXE) {
               n = "Axe";
            } else if (i == Items.SHIELD) {
               n = "Shield";
            } else if (i == Items.FISHING_ROD) {
               n = "Rod";
            } else if (i == whiteWool() || i == Items.OAK_PLANKS) {
               n = "Blocks";
            }

            if (n != null && !names.contains(n)) {
               names.add(n);
            }
         }
      }

      return String.join(", ", names);
   }

   private static List<String> splitSummary(String summary) {
      List<String> rows = new ArrayList<>();
      String[] parts = summary.split(", ");
      StringBuilder cur = new StringBuilder();

      for (String p : parts) {
         if (cur.length() > 0 && cur.length() + p.length() + 2 > 24) {
            rows.add(cur.toString());
            cur.setLength(0);
         }

         if (cur.length() > 0) {
            cur.append(", ");
         }

         cur.append(p);
      }

      if (cur.length() > 0) {
         rows.add(cur.toString());
      }

      return rows;
   }

   private static void applyScoreboardLines(Scoreboard sb, Objective obj, List<String> lines) {
      for (int i = 0; i < lines.size(); i++) {
         sb.getOrCreatePlayerScore(ScoreHolder.forNameOnly(lines.get(i)), obj).set(lines.size() - i);
      }
   }

   private static void refreshDuelScoreboard(Duel d) {
      if (d.scoreboardShown && serverRef != null) {
         if (d.ffa) {
            refreshFfaScoreboards(d);
         } else if (d.scoreboardObj != null) {
            try {
               Scoreboard sb = serverRef.getScoreboard();
               List<String> lines = scoreboardLines(d);
               if (d.scoreboardLines != null) {
                  for (String old : d.scoreboardLines) {
                     if (!lines.contains(old)) {
                        sb.resetAllPlayerScores(ScoreHolder.forNameOnly(old));
                     }
                  }
               }

               applyScoreboardLines(sb, d.scoreboardObj, lines);
               d.scoreboardLines = lines;
            } catch (Exception var5) {
            }
         }
      }
   }

   private static void hideDuelScoreboard(Duel d) {
      try {
         if (serverRef == null) {
            return;
         }

         Scoreboard sb = serverRef.getScoreboard();
         if (d.ffa) {
            hideFfaScoreboards(d, sb);
            d.scoreboardShown = false;
            d.scoreboardLines = null;
            return;
         }

         if (d.scoreboardObj != null) {
            try {
               sb.removeObjective(d.scoreboardObj);
            } catch (Exception var7) {
            }

            d.scoreboardObj = null;
         }

         try {
            Objective lingering = sb.getObjective("ffd" + d.arena.ox + "x" + d.arena.oz);
            if (lingering != null) {
               sb.removeObjective(lingering);
            }
         } catch (Exception var6) {
         }

         if (d.scoreboardTeam != null) {
            try {
               for (Participant part : d.parts) {
                  if (!part.bot && part.player != null) {
                     try {
                        sb.removePlayerFromTeam(part.player.getScoreboardName());
                     } catch (Exception var5) {
                     }
                  }
               }

               sb.removePlayerTeam(d.scoreboardTeam);
            } catch (Exception var8) {
            }

            d.scoreboardTeam = null;
         }

         removeSpectatorNametags(d);
         removeWatcherNametags(d);
         d.scoreboardShown = false;
         d.scoreboardLines = null;
         freeBoardSlot(d.scoreboardSlot);
         d.scoreboardSlot = -1;
      } catch (Exception e) {
         FortuneFavorsMod.LOGGER.warn("Fortune & Favors: could not clear the duel scoreboard", e);
      }
   }

   private static int allocBoardSlot() {
      for (int i = 0; i < TEAM_SLOTS.length; i++) {
         int idx = Math.floorMod(boardSlotCursor + i, TEAM_SLOTS.length);
         if (!usedBoardSlots.contains(idx)) {
            usedBoardSlots.add(idx);
            boardSlotCursor = idx + 1;
            return idx;
         }
      }

      return -1;
   }

   private static void freeBoardSlot(int idx) {
      if (idx >= 0) {
         usedBoardSlots.remove(idx);
      }
   }

   private static DisplaySlot[] buildTeamSlots() {
      List<DisplaySlot> slots = new ArrayList<>();

      for (DisplaySlot s : DisplaySlot.values()) {
         if (s.name().startsWith("TEAM_")) {
            slots.add(s);
         }
      }

      if (slots.isEmpty()) {
         slots.add(DisplaySlot.SIDEBAR);
      }

      return slots.toArray(new DisplaySlot[0]);
   }

   private static JsonObject savedToJson(SavedPlayer s, Provider access) {
      JsonObject obj = new JsonObject();
      obj.addProperty("dim", s.dim.identifier().toString());
      JsonArray pos = new JsonArray();
      pos.add(s.x);
      pos.add(s.y);
      pos.add(s.z);
      pos.add(s.yaw);
      pos.add(s.pitch);
      obj.add("pos", pos);
      JsonArray items = new JsonArray();

      for (ItemStack st : s.items) {
         items.add(JsonUtil.itemToJson(st, access));
      }

      obj.add("items", items);
      obj.addProperty("health", s.health);
      obj.addProperty("hunger", s.hunger);
      obj.addProperty("saturation", s.saturation);
      obj.addProperty("xp_level", s.xpLevel);
      obj.addProperty("xp_progress", s.xpProgress);
      obj.addProperty("fire_ticks", s.fireTicks);
      JsonArray effects = new JsonArray();

      for (MobEffectInstance e : s.effects) {
         JsonObject eo = new JsonObject();
         eo.addProperty("id", BuiltInRegistries.MOB_EFFECT.getKey((MobEffect)e.getEffect().value()).toString());
         eo.addProperty("amplifier", e.getAmplifier());
         eo.addProperty("duration", e.getDuration());
         eo.addProperty("ambient", e.isAmbient());
         eo.addProperty("visible", e.isVisible());
         effects.add(eo);
      }

      obj.add("effects", effects);
      obj.addProperty("gamemode", s.gameMode);
      if (s.respawnData != null) {
         JsonObject r = new JsonObject();
         r.addProperty("dim", s.respawnData.dimension().identifier().toString());
         r.addProperty("x", s.respawnData.pos().getX());
         r.addProperty("y", s.respawnData.pos().getY());
         r.addProperty("z", s.respawnData.pos().getZ());
         r.addProperty("yaw", s.respawnData.yaw());
         r.addProperty("pitch", s.respawnData.pitch());
         r.addProperty("forced", s.respawnForced);
         obj.add("respawn", r);
      }

      return obj;
   }

   private static SavedPlayer savedFromJson(JsonObject obj, Provider access) {
      try {
         ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION, Identifier.parse(obj.get("dim").getAsString()));
         JsonArray pos = obj.getAsJsonArray("pos");
         double x = pos.get(0).getAsDouble();
         double y = pos.get(1).getAsDouble();
         double z = pos.get(2).getAsDouble();
         float yaw = pos.get(3).getAsFloat();
         float pitch = pos.get(4).getAsFloat();
         List<ItemStack> items = new ArrayList<>();
         if (obj.has("items")) {
            for (JsonElement el : obj.getAsJsonArray("items")) {
               items.add(JsonUtil.jsonToItem(el, access));
            }
         }

         List<MobEffectInstance> effects = new ArrayList<>();
         if (obj.has("effects")) {
            for (JsonElement el : obj.getAsJsonArray("effects")) {
               JsonObject eo = el.getAsJsonObject();
               Holder<MobEffect> holder = (Holder<MobEffect>)BuiltInRegistries.MOB_EFFECT.get(Identifier.parse(eo.get("id").getAsString())).orElse(null);
               if (holder != null) {
                  effects.add(
                     new MobEffectInstance(
                        holder,
                        eo.get("duration").getAsInt(),
                        eo.get("amplifier").getAsInt(),
                        JsonUtil.jsonBool(eo, "ambient", false),
                        JsonUtil.jsonBool(eo, "visible", true)
                     )
                  );
               }
            }
         }

         RespawnData respawnData = null;
         boolean respawnForced = false;
         if (obj.has("respawn")) {
            JsonObject r = obj.getAsJsonObject("respawn");
            ResourceKey<Level> rdim = ResourceKey.create(Registries.DIMENSION, Identifier.parse(r.get("dim").getAsString()));
            respawnData = RespawnData.of(
               rdim,
               new BlockPos(r.get("x").getAsInt(), r.get("y").getAsInt(), r.get("z").getAsInt()),
               JsonUtil.jsonFloat(r, "yaw", 0.0F),
               JsonUtil.jsonFloat(r, "pitch", 0.0F)
            );
            respawnForced = JsonUtil.jsonBool(r, "forced", false);
         }

         return new SavedPlayer(
            dim,
            x,
            y,
            z,
            yaw,
            pitch,
            items,
            JsonUtil.jsonFloat(obj, "health", 20.0F),
            JsonUtil.jsonInt(obj, "hunger", 20),
            JsonUtil.jsonFloat(obj, "saturation", 5.0F),
            JsonUtil.jsonInt(obj, "xp_level", 0),
            JsonUtil.jsonFloat(obj, "xp_progress", 0.0F),
            JsonUtil.jsonInt(obj, "fire_ticks", 0),
            effects,
            JsonUtil.jsonString(obj, "gamemode", "survival"),
            respawnData,
            respawnForced
         );
      } catch (Exception e) {
         FortuneFavorsMod.LOGGER.warn("Fortune & Favors: could not parse a saved duel state", e);
         return null;
      }
   }


    static final class Arena {
       final int ox;
       final int oz;
       int x0;
       int x1;
       int z0;
       int z1;
       int voidY = 95;
       double near = 6.5;
       double far = 25.5;
       BlockPos bed0;
       BlockPos bed0Head;
       BlockPos bed1;
       BlockPos bed1Head;
       BlockPos chest0;
       BlockPos chest1;
       final List<BlockPos> chests = new ArrayList<>();
       final List<BlockPos> refillChests = new ArrayList<>();
       final List<BlockPos> gens = new ArrayList<>();
       final List<String> genTypes = new ArrayList<>();
       final Set<BlockPos> looted = new HashSet<>();
       final List<BlockPos> forgeGens = new ArrayList<>();
       final List<BlockPos> diamondGens = new ArrayList<>();
       final List<BlockPos> emeraldGens = new ArrayList<>();
       double[] spawn0;
       double[] spawn1;
       double[] lobbySpawn0;
       double[] lobbySpawn1;
       float yaw0 = Float.NaN;
       float yaw1 = Float.NaN;
       final Map<Integer, double[]> extraSpawns = new HashMap<>();
       final Map<Integer, Float> extraYaws = new HashMap<>();
       int ffaCount;
       double spreadRadius;
       final List<int[]> chambers = new ArrayList<>();
       double[] spectatorSpawn;
       // What the floor was the moment it was built, block by block. Kept per
       // arena rather than per fight because it is the floor that is under
       // suspicion, and the plot is what is wrong with it.
       int digestY = Integer.MIN_VALUE;
       int digestX0;
       int digestZ0;
       int digestW;
       int digestH;
       int[] digest;
       int digestSolid;
       String digestHash;
       String reportPath;
    
       Arena(int ox, int oz) {
          this.ox = ox;
          this.oz = oz;
       }
    
       double[] spawnFor(int slot) {
          double[] extra = this.extraSpawns.get(slot);
          if (extra != null) {
             return new double[]{extra[0], extra[1], extra[2]};
          } else if (slot == 0 && this.spawn0 != null) {
             return new double[]{this.spawn0[0], this.spawn0[1], this.spawn0[2]};
          } else if (slot == 1 && this.spawn1 != null) {
             return new double[]{this.spawn1[0], this.spawn1[1], this.spawn1[2]};
          } else {
             return slot == 0 ? new double[]{this.ox + this.near, 101.0, this.oz + this.near} : new double[]{this.ox + this.far, 101.0, this.oz + this.far};
          }
       }
    
       double[] lobbySpawnFor(int slot) {
          if (slot == 0 && this.lobbySpawn0 != null) {
             return new double[]{this.lobbySpawn0[0], this.lobbySpawn0[1], this.lobbySpawn0[2]};
          } else {
             return slot == 1 && this.lobbySpawn1 != null ? new double[]{this.lobbySpawn1[0], this.lobbySpawn1[1], this.lobbySpawn1[2]} : this.spawnFor(slot);
          }
       }
    
       BlockPos padFor(int slot) {
          double[] sp = this.spawnFor(slot);
          return new BlockPos((int)Math.floor(sp[0]), (int)Math.floor(sp[1]), (int)Math.floor(sp[2]));
       }
    
       float spawnYaw(int slot) {
          Float extraYaw = this.extraYaws.get(slot);
          if (extraYaw != null) {
             return extraYaw;
          } else if (slot == 0 && !Float.isNaN(this.yaw0)) {
             return this.yaw0;
          } else if (slot == 1 && !Float.isNaN(this.yaw1)) {
             return this.yaw1;
          } else {
             return slot == 0 ? -45.0F : 135.0F;
          }
       }
    
       boolean inChamber(BlockPos pos) {
          if (pos.getY() >= 103 && pos.getY() <= 106) {
             for (int[] c : this.chambers) {
                if (pos.getX() >= c[0] && pos.getX() <= c[1] && pos.getZ() >= c[2] && pos.getZ() <= c[3]) {
                   return true;
                }
             }
    
             return false;
          } else {
             return false;
          }
       }
    
       BlockPos bedFor(int slot) {
          return slot == 0 ? this.bed0 : this.bed1;
       }
    
       BlockPos bedHeadFor(int slot) {
          return slot == 0 ? this.bed0Head : this.bed1Head;
       }
    }

    record Bet(UUID bettor, UUID target, long amount) {
    }

   /**
    * A bot a player built themselves.
    *
    * <p>Four dials, and they are the four things that actually decide how a fight
    * against a bot goes: how fast its feet are, how much it strafes, how many
    * clicks a second it throws, and how well it reads the fight. Everything else -
    * the head-turn rate, the arrow spread, the draw time, the odds it reaches for
    * the clever answer - hangs off {@link #smartness()} through {@code
    * botSmartness}, because those are one quality measured in four places and
    * letting them drift apart is how a "slow but smart" bot quietly becomes an
    * aimbot.
    *
    * <p>Every value is clamped on construction, so the menu can only ever produce a
    * bot a person could be. That is the fairness half of the feature: a slider that
    * reaches past what a player can do is not a difficulty setting, it is a hack.
    */
   public record BotProfile(double speed, double strafe, double cps, double smartness) {
      /** The slowest to fastest feet a person has, in blocks a tick (a sprint is ~0.28). */
      public static final double MIN_SPEED = 0.10;
      public static final double MAX_SPEED = 0.30;
      /**
       * Clicks a second. Vanilla's attack cooldown holds a sword to about 1.6, so the
       * ceiling is set where a modded client's butterfly-clicking stops being
       * something the damage gate can tell apart from spam.
       */
      public static final double MIN_CPS = 1.0;
      public static final double MAX_CPS = 10.0;

      public BotProfile {
         speed = clamp(speed, MIN_SPEED, MAX_SPEED);
         strafe = clamp(strafe, 0.0, 1.0);
         cps = clamp(cps, MIN_CPS, MAX_CPS);
         smartness = clamp(smartness, 0.0, 1.0);
      }

      private static double clamp(double v, double lo, double hi) {
         return Math.max(lo, Math.min(hi, v));
      }

      /**
       * The four presets expressed as profiles, so the custom menu can open on the
       * difficulty the player was already using and edit outwards from there.
       */
      public static BotProfile of(BotDifficulty diff) {
         return switch (diff) {
            case EASY -> new BotProfile(0.16, 0.0, 2.0, 0.35);
            case NORMAL -> new BotProfile(0.20, 0.0, 3.0, 0.55);
            case HARD -> new BotProfile(0.25, 0.6, 4.0, 0.8);
            case HACKER -> new BotProfile(0.28, 1.0, 5.0, 1.0);
            // The dummy is not edited outwards from a profile; the floors are what a profile can
            // hold, and a dummy's feet, clicks and smartness are all meaningless.
            case TRAIN -> new BotProfile(MIN_SPEED, 0.0, MIN_CPS, 0.0);
         };
      }

      /** The tick gap between two swings, read off the clicks a second it throws. */
      public int swingRhythm() {
         return (int)Math.max(3.0, Math.round(20.0 / this.cps));
      }

      public String describe() {
         return "§7Speed §f"
            + Math.round((this.speed - MIN_SPEED) / (MAX_SPEED - MIN_SPEED) * 100.0)
            + "%§7  Strafe §f"
            + Math.round(this.strafe * 100.0)
            + "%§7  CPS §f"
            + Math.round(this.cps)
            + "§7  Smart §f"
            + Math.round(this.smartness * 100.0)
            + "%";
      }
   }

    public enum BotDifficulty {
       EASY("Easy", 0.0F, 0.3F, 0.8F),
       NORMAL("Normal", 0.0F, 0.6F, 0.9F),
       HARD("Hard", 0.2F, 0.85F, 0.85F),
       HACKER("Hacker", 0.8F, 0.95F, 0.9F),
       // Not an opponent at all: a stationary, unkillable dummy at the arena's centre, for
       // practising on. Appended rather than inserted so no existing difficulty's ordinal moves.
       TRAIN("Training Dummy", 0.0F, 0.0F, 0.0F);
    
       public final String display;
       public final float dodgeRate;
       public final float aimAccuracy;
       public final float damageMultiplier;
    
       BotDifficulty(String display, float dodgeRate, float aimAccuracy, float damageMultiplier) {
          this.display = display;
          this.dodgeRate = dodgeRate;
          this.aimAccuracy = aimAccuracy;
          this.damageMultiplier = damageMultiplier;
       }
    
       public static BotDifficulty byName(String name) {
          if (name == null) {
             return null;
          }
    
          for (BotDifficulty d : values()) {
             if (d.name().equalsIgnoreCase(name)) {
                return d;
             }
          }
    
          return null;
       }
    }

    record BwShopEntry(String id, ItemStack stack, Item currency, int cost, int tier) {
       BwShopEntry(String id, ItemStack stack, Item currency, int cost) {
          this(id, stack, currency, cost, 0);
       }
    }

    static final class Challenge {
       final UUID challenger;
       final DuelManager.DuelMode mode;
       final long expires;
    
       Challenge(UUID challenger, DuelManager.DuelMode mode, long expires) {
          this.challenger = challenger;
          this.mode = mode;
          this.expires = expires;
       }
    }

    public enum CombatStyle {
       MODERN,
       LEGACY;
    }

    static final class Duel {
       final DuelMode mode;
       final ArrayList<Participant> parts = new ArrayList<>();
       final Arena arena;
       CombatStyle combat = CombatStyle.MODERN;
       Phase phase = Phase.ENDED;
       long phaseEndsAt;
       int voteLegacy;
       int voteModern;
       long nextRefill;
       int refillCount;
       long fightStartedAt;
       long finalizeAt;
       boolean finalized;
       DuelBot bot;
       BotDifficulty botDifficulty = BotDifficulty.NORMAL;
       long botVoteAt;
       boolean botVoted;
       boolean botVoteLegacy;
       int botLastSlot = -1;
       final Map<UUID, String> villagerRoles = new HashMap<>();
       long ante = 0L;
       final Map<BlockPos, Long> tntrunCrumble = new HashMap<>();
       double[] botTntTarget;
       long botTntUntil;
       boolean ffa;
       final List<String> draftPool = new ArrayList<>();
       int draftTurn;
       final Set<UUID> draftPrompted = new HashSet<>();
       long botPickAt;
       /** When the bot may next start a real meal. How long the meal takes is vanilla's. */
       long botGappleAt;
       /**
        * What the bot's hands are committed to right now. Vanilla owns what the
        * commitment means - the use duration, the completion, the shield warm-up,
        * the riptide spin - so this only decides when to start one and when to give
        * up on it.
        */
       BotHands botHands = BotHands.NONE;
       long botShieldUntil;
       /**
        * When the bot's shield may be raised again after it drops one. Without
        * this the bot re-raised the shield on the very next tick, so a fight
        * against it was a fight against a wall that never put its guard down.
        */
       long botShieldCooldown;
       /**
        * Skywars and Bedwars are not duels, they are preparation followed by one:
        * this is the tick the bot stops looting and starts hunting. It used to
        * rush from the first second, which is why a Skywars bot walked into a
        * geared opponent with a wooden sword.
        */
       long botGatherUntil;
       /** Bedwars role: 1 = hold the base, 2 = push. Starts defensive. */
       int botBwRole = 1;
       long botBwRoleAt;
       /** Bedwars: the last tick the bot laid a block on its own bed defence. */
       long botBwWallAt;
       /** When the bot may next drink or throw a potion. */
       long botPotionAt;
       /** When the bot may next jump into a real critical hit. */
       long botCritAt;
       /**
        * When the bot may next hurl a wind charge, and when its last one left the
        * hand. A wind charge is a real vanilla projectile - the same one the item
        * throws - so this only paces the throw.
        */
       long botWindAt;
       /** When the bot may next place a bridging block. Sneaking at the edge is the tell. */
       long botBridgeAt;
       /**
        * True while the bot is mid-bridge, so the sneak key stays down across ticks.
        * Toggling it every tick - which is what keying off the branch did - is a
        * crouch flicker, and a crouch flicker is the scaffold tell.
        */
       boolean botBridging;
       /** The tick the bridge crouch may be released on - see {@link #botBridging}. */
       long botBridgeHoldUntil;
       /** When the bot may next tidy its kit into the slots the AI actually uses. */
       long botManageAt;
       /**
        * A player-made difficulty, or null when this fight is running off one of the
        * four presets. It is deliberately per-duel rather than a global: editing a
        * bot is meant to change the bot in front of you, not what "Hard" means for
        * every other match on the server.
        */
       BotProfile botProfile;
       /** Archery: the yaw and pitch the arrow in flight was actually loosed at. */
       float botBowYaw;
       float botBowPitch;
       /** When the current draw is released, and when the next one may start. */
       long botBowUntil;
       long botBowAt;
       /** Gladiator: when the hunter trackers were last re-aimed. */
       long botTrackerAt;
       /** Gladiator: when the bot may next break one more block of a vein. */
       long botMineAt;
       /**
        * The bot's camp: where it put its furnace and its table, when the current
        * job finishes, and which job it is. 1 smelts, 2 crafts.
        */
       BlockPos botCampFurnace;
       BlockPos botCampTable;
       long botWorkUntil;
       int botWorkKind;
       /** Legacy 1.8: when the bot's sword block comes down, and when it may go up again. */
       long botSwordBlockUntil;
       long botSwordBlockAt;
      /** Gladiator: when the fighters start glowing. -1 until the match sets it. */
      long gladHighlightAt = -1L;
      /** Set once when the gearing truce first refuses a blow, so it says so once. */
      boolean gladTruceTold;
       /** When the bot may next spin up a riptide trident, and next sprint-jump. */
       long botRiptideAt;
       long botJumpAt;
       /**
        * A per-fight offset for the bot's footwork. Every fight used to run the
        * same strafe pattern on the same clock, so the tenth duel against the same
        * difficulty looked exactly like the first one.
        */
       final long botSeed = (long)(Math.random() * 100000.0);
       /**
        * Where the bot has recently been hurt, against the tick that memory
        * expires. Short-lived on purpose: this is meant to stop it walking back
        * into the crystal that just took half its health, not to turn the fight
        * into a mapping exercise.
        */
       final Map<Long, Long> botDanger = new HashMap<>();
       long draftPickDeadline;
       EndCrystal botCrystal;
       long botCrystalAt;
       /** Mace PvP: when the bot may next leap, and when it last landed a smash. */
       long botMaceAt;
       int botLeapUntil;
       double botLeapFromX;
       double botLeapFromZ;
       double botLeapFromY;
       double botLeapToX;
       double botLeapToZ;
       Objective scoreboardObj;
       PlayerTeam scoreboardTeam;
       boolean scoreboardShown;
       List<String> scoreboardLines;
       final List<FfaBoard> ffaBoards = new ArrayList<>();
       int scoreboardSlot = -1;
       boolean rematchReopened;
      /**
       * When the rematch offer appeared, so the GG window is measured from it.
       *
       * <p>Five seconds after the result screen, and only for the two people who were
       * in the fight: a window that stayed open would be a window that is just "type gg
       * at some point", which is not a gesture, it is a queue.
       */
      long rematchOpenedAt;
      /** Who has already said it, so the achievement is announced once each. */
      final Set<UUID> kindSirSaid = new HashSet<>();
       final Set<Long> playerPlacedBlocks = new HashSet<>();
    
       Duel(DuelMode mode, int ox, int oz) {
          this.mode = mode;
          this.arena = new Arena(ox, oz);
          this.nextRefill = 0L;
          if (mode == DuelMode.LUCKYPvP) {
             this.combat = Math.random() < 0.3333333333333333 ? CombatStyle.LEGACY : CombatStyle.MODERN;
          } else {
             this.combat = mode != DuelMode.LEGACY && mode != DuelMode.AXEDUEL && mode != DuelMode.COMBODUEL && mode != DuelMode.UHCDUEL
                ? CombatStyle.MODERN
                : CombatStyle.LEGACY;
          }
    
          if (mode == DuelMode.DRAFT) {
             for (String kind : DuelManager.DRAFT_KINDS) {
                this.draftPool.add(kind);
             }
    
             this.draftTurn = 0;
          }
       }
    }

    public enum DuelMode {
       MODERN("Modern Arena", false),
       MACEPVP("Mace PvP", false),
       CRYSTALPVP("Crystal PvP", false),
       UHCDUEL("UHC Duel", false),
       LEGACY("Legacy 1.8 Arena", false),
       AXEDUEL("Axe Duel", false),
       COMBODUEL("Combo Duel", false),
       BEDWARS("Bed Wars", true),
       SKYWARS("Sky Wars", true),
       LUCKYPvP("Lucky PvP", false),
       KITS("Kits", false),
       RANDOMIZER("Randomizer Duel", false),
       DRAFT("Draft Duel", false),
       LASTSTAND("Last Stand", false),
       TNTRUN("TNT Run", false),
       // ------------------------------------------------------------ added modes
       // Appended, never inserted: giveFightGear dispatches on ordinal(), so a new
       // mode in the middle of this enum would silently re-kit every mode after it.
       SUMO("Sumo", false),
       ARCHERY("Archery Duel", false),
       GLADIATOR("Gladiator", false);

       public final String display;
       public final boolean voted;

       public DuelMode baseMode() {
          return switch (this) {
             // Gladiator is a survival-gear mode with modern combat, so it opens
             // under Modern PvP rather than beside it - that is also what puts it
             // on the free-for-all menu at all.
             case MACEPVP, CRYSTALPVP, GLADIATOR -> MODERN;
             // Sumo is a 1.8 game - no cooldown, knockback is the entire weapon -
             // so it belongs under Legacy PvP, not beside it as if it were a modern
             // mode with its own ruleset.
             case AXEDUEL, COMBODUEL, SUMO -> LEGACY;
             default -> null;
          };
       }
    
       public boolean isSubMode() {
          return this.baseMode() != null;
       }
    
       public static DuelMode[] subModesOf(DuelMode base) {
          return switch (base) {
             case MODERN -> new DuelMode[]{MODERN, MACEPVP, CRYSTALPVP, GLADIATOR, UHCDUEL};
             case LEGACY -> new DuelMode[]{LEGACY, AXEDUEL, COMBODUEL, SUMO, UHCDUEL};
             default -> new DuelMode[0];
          };
       }
    
       public boolean supportsFfa() {
          return this == LUCKYPvP
             || this == MODERN
             || this == LEGACY
             || this == TNTRUN
             || this == KITS
             || this == MACEPVP
             || this == CRYSTALPVP
             || this == AXEDUEL
             || this == COMBODUEL
             || this == UHCDUEL
             || this == SUMO
             || this == GLADIATOR;
       }
    
       DuelMode(String display, boolean voted) {
          this.display = display;
          this.voted = voted;
       }
    
       public static DuelMode byName(String name) {
          if (name == null) {
             return null;
          }
    
          for (DuelMode m : values()) {
             if (m.name().equalsIgnoreCase(name)) {
                return m;
             }
          }
    
          return null;
       }
    }

    static final class FFALobby {
       final DuelManager.DuelMode mode;
       final UUID host;
       final List<UUID> members = new ArrayList<>();
       long startedAt;
    
       FFALobby(DuelManager.DuelMode mode, UUID host) {
          this.mode = mode;
          this.host = host;
          this.startedAt = 0L;
       }
    }

    static final class FfaBoard {
       Objective obj;
       PlayerTeam team;
       int slotIdx;
       String playerName;
       UUID playerUuid;
       List<String> lines;
    
       private FfaBoard() {
       }
    }

    record LastDuel(UUID opponent, DuelManager.DuelMode mode) {
    }

    public record LeaderboardRow(String name, long wins, long losses) {
    }

    static final class Participant {
       /**
        * True when this fighter is a real player: not a bot, and with a uuid to file
        * anything under.
        *
        * <p>A bot is deliberately uuid-less - `botParticipant` never sets one,
        * because a bot is not an account, has no inventory to restore and no record
        * to keep - so every piece of the exit path that stores state by uuid has to
        * ask for a real player rather than assuming one. Asking twice (not a bot,
        * and a uuid) is the point: a `Participant` built for a player that lost its
        * uuid would otherwise file its exit state under a null key, where nothing
        * can ever find it again.
        */
       boolean hasPlayer() {
          return ownsState(this.bot, this.uuid);
       }

       UUID uuid;
       boolean bot;
       DuelBot botEntity;
       ServerPlayer player;
       SavedPlayer saved;
       int slot;
       String displayName;
       boolean voted;
       boolean voteLegacy;
       String kit = "knight";
       boolean kitChosen;
       boolean bedIntact = true;
       final Map<String, Integer> bwUpgrades = new HashMap<>();
       long respawnAt;
       long spawnProtectUntil;
       long bedAlarmAt;
       long spinInvulUntil;
       BlockPos teamChest;
       List<ItemStack> deathInventory;
       final List<ItemStack> draftKit = new ArrayList<>();
       final List<ItemStack> draftArmor = new ArrayList<>();
       int draftPicks;
       boolean eliminated;
       int kills;
       int killStreak;
       String kitName;
       int lives = 4;
       boolean lsHealthPenalty;
       boolean lsArmorPenalty;
       boolean lsHealingPenalty;
       boolean lsRespawning;
       long lsRespawnUntil;
       long botHitAt;
       ServerBossEvent botBossBar;
       int comboCount;
       long comboLastHitAt;
       /** Match-report ledger: filled by {@link #onDuelAfterDamage}. */
       int hitsLanded;
       float damageDealt;
       float damageTaken;
       /**
        * Whether this fighter was ever one hit from death during the fight, which is what
        * the Comeback advancement is about. Set by {@link #recordHitLedger}.
        */
       boolean nearDeath;
       /**
        * Whether anything ever landed on this fighter, which is the whole of Untouchable.
        * Set by {@link #recordHitLedger} and by the modes that resolve a death themselves
        * (bed wars, last stand, free-for-all), where the lethal blow is absorbed rather
        * than dealt and would otherwise leave no mark at all.
        */
       boolean wasHit;
       final List<ItemStack> teamChestItems = new ArrayList<>();
       boolean lsStickGiven;
    
       private Participant() {
       }
    }

    record PendingPick(boolean bot, String targetName, BotDifficulty difficulty) {
    }

    enum Phase {
       LOBBY,
       COUNTDOWN,
       FIGHT,
       ENDED;
    }

    record SavedPlayer(
       ResourceKey<Level> dim,
       double x,
       double y,
       double z,
       float yaw,
       float pitch,
       List<ItemStack> items,
       float health,
       int hunger,
       float saturation,
       int xpLevel,
       float xpProgress,
       int fireTicks,
       List<MobEffectInstance> effects,
       String gameMode,
       RespawnData respawnData,
       boolean respawnForced
    ) {
    }

    record SpectatorSession(
       int arenaOx,
       int arenaOz,
       GameType previousMode,
       ResourceKey<Level> dim,
       double x,
       double y,
       double z,
       float yaw,
       float pitch,
       List<ItemStack> inventory,
       int selectedSlot
    ) {
    }

    record ToolTier(Item item, Item currency, int cost) {
    }

    record WagerRequest(UUID bettor, UUID target, long amount, long expiresAt) {
    }

    record WagerStaged(UUID bettor, UUID acceptor, long amount, long stagedAt) {
    }
}
