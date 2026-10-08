package com.fortuneandfavors;

import com.fortuneandfavors.duel.DuelManager;
import com.fortuneandfavors.economy.AuctionManager;
import com.fortuneandfavors.economy.AdvancedEnchantments;
import com.fortuneandfavors.economy.BlockValues;
import com.fortuneandfavors.economy.BossEmpowerment;
import com.fortuneandfavors.economy.BossManager;
import com.fortuneandfavors.economy.BountyManager;
import com.fortuneandfavors.economy.CCEnchantments;
import com.fortuneandfavors.economy.ChestShopManager;
import com.fortuneandfavors.economy.ClaimManager;
import com.fortuneandfavors.economy.EconomyManager;
import com.fortuneandfavors.economy.ElytraLunge;
import com.fortuneandfavors.economy.JobManager;
import com.fortuneandfavors.economy.MachineManager;
import com.fortuneandfavors.economy.ModConfig;
import com.fortuneandfavors.economy.ShopData;
import com.fortuneandfavors.economy.SkillManager;
import com.fortuneandfavors.economy.SpawnerManager;
import com.fortuneandfavors.economy.WormholeManager;
import com.fortuneandfavors.guild.GuildManager;
import com.fortuneandfavors.map.MapEditor;
import com.fortuneandfavors.net.FfArenaProbePayload;
import com.fortuneandfavors.net.FfClientContextPayload;
import com.fortuneandfavors.net.FfConfigPayload;
import com.fortuneandfavors.net.FfCreditsPayload;
import com.fortuneandfavors.net.FfEndIntroPayload;
import com.fortuneandfavors.net.FfLungePayload;
import com.fortuneandfavors.net.FfMusicPayload;
import com.fortuneandfavors.net.FfNet;
import com.fortuneandfavors.net.FfJukeboxPayload;
import com.fortuneandfavors.net.FfScreenFxPayload;
import com.fortuneandfavors.util.JsonUtil;
import com.fortuneandfavors.util.PerfMonitor;
import com.fortuneandfavors.util.Safe;
import com.fortuneandfavors.util.WorldBackup;
import com.google.gson.JsonObject;
import java.util.List;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.BeforeSave;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.ServerStarted;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.ServerStarting;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.ServerStopping;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.EndTick;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.Context;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.fabricmc.fabric.api.resource.v1.pack.PackActivationType;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.level.gamerules.GameRules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FortuneFavorsMod implements ModInitializer {
   public static final String MOD_ID = "fortuneandfavors";
   public static final Logger LOGGER = LoggerFactory.getLogger("fortuneandfavors");
   private static volatile boolean dataLoaded = false;

   public void onInitialize() {
      LOGGER.info("Fortune & Favors initializing");
      registerArtPack();
      ModEvents.register();
      registerCreativeTomes();
      PayloadTypeRegistry.clientboundPlay().register(FfScreenFxPayload.TYPE, FfScreenFxPayload.CODEC);
      PayloadTypeRegistry.clientboundPlay().register(FfJukeboxPayload.TYPE, FfJukeboxPayload.CODEC);
      // The Wither fight's music: the server names the cut, the client's player fades and loops it.
      PayloadTypeRegistry.clientboundPlay().register(FfMusicPayload.TYPE, FfMusicPayload.CODEC);
      // The End's credits: the server says how long to hold and how long to let go for, and only
      // the client's own sound can do the second half. Clientbound only - nothing asks for this back,
      // and a client that cannot decode it is served a plain sound instead (economy.EnderCreditsMusic).
      PayloadTypeRegistry.clientboundPlay().register(FfCreditsPayload.TYPE, FfCreditsPayload.CODEC);
      // The End's opening theme is the same shape of command for the same reason: a track that has to
      // hold and then fade, which no sound packet can do (economy.EndIntroMusic).
      PayloadTypeRegistry.clientboundPlay().register(FfEndIntroPayload.TYPE, FfEndIntroPayload.CODEC);
      PayloadTypeRegistry.clientboundPlay().register(FfConfigPayload.TYPE, FfConfigPayload.CODEC);
      // Visual cues for modded clients, which draw the fight's effects themselves (net.FfVfx).
      PayloadTypeRegistry.clientboundPlay().register(com.fortuneandfavors.net.FfVfxPayload.TYPE, com.fortuneandfavors.net.FfVfxPayload.CODEC);
      ServerTickEvents.END_SERVER_TICK.register((EndTick)com.fortuneandfavors.net.FfVfx::flush);
      PayloadTypeRegistry.serverboundPlay().register(FfConfigPayload.TYPE, FfConfigPayload.CODEC);
      PayloadTypeRegistry.serverboundPlay().register(FfLungePayload.TYPE, FfLungePayload.CODEC);
      // The arena floor probe travels in both directions: the dump goes out, the
      // client's own copy of the same layer comes back.
      PayloadTypeRegistry.clientboundPlay().register(FfArenaProbePayload.TYPE, FfArenaProbePayload.CODEC);
      PayloadTypeRegistry.serverboundPlay().register(FfArenaProbePayload.TYPE, FfArenaProbePayload.CODEC);
      // Client context is serverbound only: the server never asks for it, so a client that
      // does not send it is simply unknown rather than silent. See ClientContext.
      PayloadTypeRegistry.serverboundPlay().register(FfClientContextPayload.TYPE, FfClientContextPayload.CODEC);
      ServerPlayNetworking.registerGlobalReceiver(FfConfigPayload.TYPE, FortuneFavorsMod::handleConfigSync);
      ServerPlayNetworking.registerGlobalReceiver(FfLungePayload.TYPE, FortuneFavorsMod::handleLunge);
      ServerPlayNetworking.registerGlobalReceiver(FfArenaProbePayload.TYPE, FortuneFavorsMod::handleArenaProbe);
      ServerPlayNetworking.registerGlobalReceiver(FfClientContextPayload.TYPE, FortuneFavorsMod::handleClientContext);
      CommandRegistrationCallback.EVENT.register((CommandRegistrationCallback)(dispatcher, context, selection) -> ModCommands.register(dispatcher, context));
      ServerLifecycleEvents.SERVER_STARTING.register((ServerStarting)server -> dataLoaded = false);
      ServerLifecycleEvents.SERVER_STARTED.register((ServerStarted)server -> {
         Safe.run("daylight cycle check", () -> checkDayCycle(server));
         Safe.run("legacy data migration", () -> EconomyManager.migrateLegacyData(server));
         Safe.run("config load", () -> ModConfig.load(server));
         Safe.run("economy load", () -> EconomyManager.load(server));
         // The shared clock, read as soon as the data directory exists: every timer that lives
         // outside the overworld is measured against it, and it has to be restored before anything
         // that holds a deadline from the last session is loaded.
         Safe.run("server clock load", () -> com.fortuneandfavors.economy.ServerClock.load(server));
         Safe.run("block values load", () -> BlockValues.load(server));
         Safe.run("shop data load", () -> ShopData.load(server));
         Safe.run("chest shop load", () -> ChestShopManager.load(server));
         Safe.run("auction load", () -> AuctionManager.load(server));
         Safe.run("claims load", () -> ClaimManager.load(server));
         Safe.run("machines load", () -> MachineManager.load(server));
         Safe.run("sorter filters load", () -> com.fortuneandfavors.economy.ItemSorter.load(server));
         Safe.run("sorter links load", () -> com.fortuneandfavors.economy.SorterLinks.load(server));
         Safe.run("machine tuning load", () -> com.fortuneandfavors.economy.MachineTuning.load(server));
         // A Chunk Anchor holds its chunks with a ticket the world saves, and the world does not
         // know which tickets belong to a machine that has since been picked up: the anchors that
         // are still in the ledger are re-asserted here, every restart.
         Safe.run("chunk anchors reassert", () -> com.fortuneandfavors.economy.ChunkAnchor.reaffirm(server));
         Safe.run("spawners load", () -> SpawnerManager.load(server));
         Safe.run("jobs load", () -> JobManager.load(server));
         Safe.run("trade market load", () -> com.fortuneandfavors.economy.TradeManager.load(server));
         Safe.run("bounties load", () -> BountyManager.load(server));
         Safe.run("skills load", () -> SkillManager.load(server));
         Safe.run("duel load", () -> DuelManager.load(server));
         Safe.run("custom maps load", () -> MapEditor.load(server));
         Safe.run("nice keep inventory load", () -> NiceKeepInventoryManager.load(server));
         Safe.run("soulbound items load", () -> com.fortuneandfavors.economy.AdvancedEnchantments.load(server));
         Safe.run("guilds load", () -> GuildManager.load(server));
         Safe.run("elder warden load", () -> BossManager.loadWardenState(server));
         Safe.run("snow realm arena", () -> BossManager.ensureSnowRealmArena(server));
         Safe.run("titles load", () -> com.fortuneandfavors.economy.TitleManager.load(server));
         Safe.run("tags load", () -> com.fortuneandfavors.economy.TagManager.load(server));
         Safe.run("display prefs load", () -> com.fortuneandfavors.economy.DisplayPrefsManager.load(server));
         Safe.run("streaks load", () -> com.fortuneandfavors.economy.StreakTrackerManager.load(server));
         Safe.run("login streaks load", () -> com.fortuneandfavors.economy.DailyLoginStreakManager.load(server));
         Safe.run("contracts load", () -> com.fortuneandfavors.economy.DynamicContractsManager.load(server));
         Safe.run("disasters load", () -> com.fortuneandfavors.economy.ServerDisasterManager.load(server));
         Safe.run("boss codex load", () -> com.fortuneandfavors.economy.BossCodexManager.load(server));
         Safe.run("expedition codex load", () -> com.fortuneandfavors.economy.ExpeditionManager.loadCodex(server));
         Safe.run("expedition progress load", () -> com.fortuneandfavors.economy.ExpeditionProgression.load(server));
         Safe.run("first records load", () -> com.fortuneandfavors.economy.FirstEverRecordManager.load(server));
         Safe.run("end intro load", () -> com.fortuneandfavors.economy.EndIntroMusic.load(server));
         Safe.run("achievements load", () -> com.fortuneandfavors.economy.CooperativeAchievementManager.load(server));
         Safe.run("player feats load", () -> com.fortuneandfavors.economy.PlayerFeatTracker.load(server));
         Safe.run("challenges load", () -> com.fortuneandfavors.economy.DailyWeeklyChallengeManager.load(server));
         Safe.run("lottery load", () -> com.fortuneandfavors.economy.LotteryManager.load(server));
         Safe.run("bank load", () -> com.fortuneandfavors.economy.BankManager.load(server));
         Safe.run("market load", () -> com.fortuneandfavors.economy.MarketManager.load(server));
         Safe.run("newspaper load", () -> com.fortuneandfavors.economy.ServerNewspaperManager.load(server));
         Safe.run("activity log load", () -> com.fortuneandfavors.economy.RealActivityLog.load(server));
         Safe.run("cosmetics load", () -> com.fortuneandfavors.economy.CosmeticManager.load(server));
         Safe.run("leaderboard load", () -> com.fortuneandfavors.economy.LeaderboardManager.load(server));
         Safe.run("scoreboard load", () -> com.fortuneandfavors.economy.ScoreboardManager.load(server));
         Safe.run("mining zones load", () -> com.fortuneandfavors.economy.MiningZoneManager.load(server));
         // Loaded BEFORE anything can be broken, and saved on the same hooks as everything
         // else: this ledger is what stops a placed block from paying mining bonuses, and
         // an in-memory-only version is a dupe with a restart (or a thirty-minute wait) in
         // the middle of it.
         Safe.run("natural blocks load", () -> com.fortuneandfavors.economy.NaturalBlocks.load(server));
         Safe.run("player raids load", () -> com.fortuneandfavors.economy.PlayerRaidManager.load(server));
         Safe.run("wormhole waypoints load", () -> WormholeManager.load(server));
         Safe.run("loot box pity load", () -> com.fortuneandfavors.menu.LootBoxMenu.loadPity(server));
         Safe.run("prison load", () -> com.fortuneandfavors.economy.PrisonManager.load(server));
         Safe.run("last inventory load", () -> com.fortuneandfavors.util.LastInventoryHolder.load(server));
         Safe.run("anticheat history load", () -> com.fortuneandfavors.anticheat.AntiCheatStore.load(server));
         Safe.run("anticheat policy load", () -> com.fortuneandfavors.anticheat.AntiCheatPolicy.load(server));
         LOGGER.info("Fortune & Favors data loaded");
         dataLoaded = true;
         // Prove the data we just loaded is actually readable, and that anything
         // unreadable still has a backup which can rebuild it - BEFORE the backup
         // rotation below overwrites the last good copy. A silent wipe otherwise
         // stays invisible for weeks; this names it on the boot it happens.
         Safe.run("data integrity audit", () -> com.fortuneandfavors.util.DataIntegrity.audit(server));
         // iCloud Drive mints a conflict copy every time it sees one of these data
         // files change twice, and the mod rewrites all of them on a two-minute
         // timer - so it mints them continuously. Nothing ever removed them, and
         // the folder had grown to 3447 entries holding forty documents. Clear
         // them before the backup below has to walk the folder; only a copy whose
         // original is still present is removed.
         Safe.run("icloud conflict sweep", () -> {
            int gone = WorldBackup.scrubConflictCopies(
               server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("fortuneandfavors")
            );
            if (gone > 0) {
               LOGGER.info("Fortune & Favors: removed {} iCloud conflict copy(ies) from the data folder", gone);
            }
         });
         // TEMP dev-only: headless smoke test for the wither rework (wtest.flag).
         WitherTestDriver.onServerStarted(server);
         // Opt-in headless self-test: asserts the catalog, every recovery path,
         // data integrity, the join fingerprint, the command surface and the
         // recipe unlocks. No-op unless -Dff.selftest=true / FF_SELFTEST=1.
         SelfTest.onServerStarted(server);
         // Clean out any floating-text popups a crash saved into the world
         // ("Sticky!" etc. stacking forever and lagging the server).
         Safe.run("stale display sweep", () -> com.fortuneandfavors.economy.CombatGear.sweepOrphanedDisplays(server));
         if (ModConfig.backups()) {
            // Off the server thread: copying a large data folder on a slow
            // disk (e.g. iCloud Drive) can block startup past the watchdog
            // limit and kill the whole server. The backup only reads JSON
            // files, so a concurrent copy is safe.
            Thread backup = new Thread(() -> Safe.run("data backup", () -> WorldBackup.backupData(server)), "ff-data-backup");
            backup.setDaemon(true);
            backup.start();
         }
      });
      ServerLifecycleEvents.BEFORE_SAVE.register((BeforeSave)(server, flush, force) -> save(server));
      // Discard any lingering proc-popup text displays before the world is saved so
      // they never persist into a world that gets loaded later.
      ServerLifecycleEvents.SERVER_STOPPING.register((ServerStopping)server -> {
         Safe.run("combat gear cleanup", () -> com.fortuneandfavors.economy.CombatGear.clearProcLabels(server));
      });
      // Tear the live encounters down BEFORE the world is saved, not after.
      //
      // Every one of these teardown entry points already existed and none of them
      // was registered anywhere, so a server stop left each fight's entities in the
      // world - bosses, their machines, rigging, guards and summoned servants -
      // while the in-memory state that drove them vanished with the process. What
      // came back on the next boot was a body with no fight attached: no AI, no
      // ticking, no loot. That is the orphaned-statue bug, and a dead entry point
      // compiles perfectly, which is why it survived. The periodic stray sweeps are
      // now a backstop for crashes rather than the only line of defence.
      ServerLifecycleEvents.SERVER_STOPPING.register((ServerStopping)server -> {
         Safe.run("clockwork teardown", () -> com.fortuneandfavors.economy.ClockworkKingManager.onServerStopping(server));
         Safe.run("magister teardown", () -> com.fortuneandfavors.economy.StarboundMagisterManager.onServerStopping(server));
         Safe.run("void shaper teardown", () -> com.fortuneandfavors.economy.VoidShaperManager.onServerStopping(server));
         Safe.run("sovereign teardown", () -> com.fortuneandfavors.economy.EmeraldSovereignManager.onServerStopping(server));
         // The sea and the sky. Both fights keep a boss bar, a scheduler of hazards in the air
         // and a body the server moves by hand, so a stop that did not release them would leave
         // a Sovereign standing in the water with nothing driving it - the orphan bug again.
         Safe.run("drowned sovereign teardown", () -> com.fortuneandfavors.economy.DrownedSovereignManager.onServerStopping(server));
         Safe.run("gale warden teardown", () -> com.fortuneandfavors.economy.GaleWardenManager.onServerStopping(server));
         Safe.run("time lord teardown", () -> com.fortuneandfavors.economy.TimeLordManager.onServerStopping(server));
         Safe.run("puppeteer teardown", () -> com.fortuneandfavors.economy.PuppeteerManager.onServerStopping(server));
         Safe.run("puppeteer gear teardown", com.fortuneandfavors.economy.PuppeteerGear::clear);
         Safe.run("scarlet teardown", () -> com.fortuneandfavors.economy.ScarletDevilManager.onServerStopping(server));
         Safe.run("boss teardown", () -> com.fortuneandfavors.economy.BossManager.onServerStopping(server));
         // The shared boss layer holds a live level per body, so it has to be told the world is
         // going away - a registry that outlives its server ticks bosses in a level nobody has.
         Safe.run("boss empowerment teardown", () -> BossEmpowerment.onServerStopping(server));
         Safe.run("wither rework teardown", () -> com.fortuneandfavors.economy.WitherReworkManager.clearAll(server));
         Safe.run("sovereign gear teardown", () -> com.fortuneandfavors.economy.SovereignGear.onServerStopping(server));
         Safe.run("scarlet gear teardown", () -> com.fortuneandfavors.economy.ScarletGear.clear(server));
      });
      // Per-player gear state has to go too, or the next world in the same process
      // inherits charges, shields and cooldowns that belong to the last one.
      ServerLifecycleEvents.SERVER_STOPPING.register((ServerStopping)server -> {
         Safe.run("combat gear state reset", com.fortuneandfavors.economy.CombatGear::clear);
         Safe.run("clockwork gear state reset", com.fortuneandfavors.economy.ClockworkGear::clear);
         Safe.run("magister gear state reset", com.fortuneandfavors.economy.MagisterGear::clear);
         Safe.run("void shaper gear state reset", com.fortuneandfavors.economy.VoidShaperGear::clear);
         Safe.run("sovereign gear state reset", com.fortuneandfavors.economy.SovereignGear::clear);
         // Waves, whirlpools, thrown rings and sky-slams all hold a live level; so do the
         // cooldowns and the marks one bearer left on somebody else's body.
         Safe.run("sea and sky gear state reset", com.fortuneandfavors.economy.SeaAndSkyGear::clear);
         // Kits captured for a death that never finished. A map keyed by player that only ever
         // grows is a slow leak, and every entry in it is somebody's gear.
         Safe.run("reactive zombie state reset", com.fortuneandfavors.economy.ReactiveZombie::clear);
         Safe.run("anticheat state reset", () -> com.fortuneandfavors.anticheat.AntiCheat.onServerStopping(server));
      });
      // The prison arms this before anything else on the way down: its disconnect hook needs to
      // know a stop from a quit, and by the time players are being kicked it is too late to tell.
      ServerLifecycleEvents.SERVER_STOPPING.register((ServerStopping)server -> Safe.run(
         "prison stopping flag", com.fortuneandfavors.economy.PrisonManager::markServerStopping
      ));
      ServerLifecycleEvents.SERVER_STOPPING.register(FortuneFavorsMod::save);
      ServerLifecycleEvents.SERVER_STOPPING.register((ServerStopping)server -> {
         if (ModConfig.backups()) {
            Safe.run("world backup", () -> WorldBackup.backupWorld(server));
         }
      });
      ServerTickEvents.END_SERVER_TICK.register((EndTick)server -> {
         // TEMP dev-only: wither rework smoke test driver.
         WitherTestDriver.tick(server);
         SelfTest.tick(server);
         Safe.run("chainfire tick", CCEnchantments::tickChainfire);
         Safe.run("advanced enchantment tick", () -> AdvancedEnchantments.tick(server));
         Safe.run("auction tick", () -> AuctionManager.tick(server));
         Safe.run("boss tick", () -> BossManager.tick(server));
         Safe.run("duel tick", () -> DuelManager.tick(server));
         Safe.run("raid gear tick", () -> com.fortuneandfavors.economy.RaidGearManager.tick(server));
         Safe.run("anticheat tick", () -> com.fortuneandfavors.anticheat.AntiCheat.tick(server));
         Safe.run("combat gear tick", () -> com.fortuneandfavors.economy.CombatGear.tickProcLabels(server));
         if (server.getTickCount() % 10L == 0L) {
            Safe.run("sword block tick", () -> com.fortuneandfavors.economy.SwordBlockManager.tick(server));
            Safe.run("variant mob tick", () -> com.fortuneandfavors.economy.RareMobVariantManager.tick(server));
         }
         // The machines that run on their own clock - the farm blocks, and the anchor's ticket.
         if (server.getTickCount() % 20L == 0L) {
            Safe.run("machine tick", () -> MachineManager.tickAll(server));
         }
         if (server.getTickCount() % 5L == 0L) {
            Safe.run("expedition tick", () -> com.fortuneandfavors.economy.ExpeditionManager.tick(server));
            Safe.run("cosmetics tick", () -> com.fortuneandfavors.economy.CosmeticManager.tick(server));
            Safe.run("prison tick", () -> com.fortuneandfavors.economy.PrisonManager.tick(server));
            // The party roster is kept honest - names that are gone are dropped and the crown handed
            // down - and every member's copy of a shared pack is rewritten, so a bag that was looted
            // one tick ago is the bag the next member opens. See PartyManager#tick.
            Safe.run("party tick", () -> com.fortuneandfavors.economy.PartyManager.tick(server));
         }
         // Perf: wall-clock gate (System.currentTimeMillis compare per tick,
         // no work except during the actual minute window).
         if (System.currentTimeMillis() >= nextMinutePoll) {
            nextMinutePoll = System.currentTimeMillis() + 60_000L;
            Safe.run("leaderboard tick", () -> com.fortuneandfavors.economy.LeaderboardManager.tick(server));
         }
         // The sidebar refreshes itself and writes itself back when a player has edited it.
         // The tick is gated on its own interval inside the manager, so this is one call a
         // tick that does nothing at all most of the time.
         Safe.run("scoreboard tick", () -> com.fortuneandfavors.economy.ScoreboardManager.tick(server));
         Safe.run("scoreboard save", () -> com.fortuneandfavors.economy.ScoreboardManager.saveIfDirty(server));
         Safe.run("mining zones tick", () -> com.fortuneandfavors.economy.MiningZoneManager.tick(server));
         // Perf: every one of these eight sweeps used to share the same test,
         // `tick % 6000 == 0`, so once every five minutes a single tick paid for
         // all of them at once - a hitch on a fixed, predictable schedule. They
         // all still run every five minutes; each just owns its own slot in the
         // cycle now, so the work lands in eight quiet ticks instead of one.
         sweep(server, SWEEP_MACHINE, "machine stale sweep", () -> MachineManager.cleanupStale(server));
         sweep(server, SWEEP_SPAWNER, "spawner stale sweep", () -> SpawnerManager.cleanupStale(server));
         sweep(server, SWEEP_DISPLAYS, "stale display sweep", () -> com.fortuneandfavors.economy.CombatGear.sweepOrphanedDisplays(server));
         sweep(server, SWEEP_WITHER_DISPLAYS, "wither display sweep", () -> com.fortuneandfavors.economy.WitherReworkManager.sweepOrphanedDisplays(server));
         // Raid-boss fights live in memory, so a crash mid-fight can leave a
         // boss body in the world with nothing to drive it: an immortal statue
         // that never moves and never drops. Clear any such body near a player.
         sweep(server, SWEEP_CLOCKWORK, "clockwork stray sweep", () -> com.fortuneandfavors.economy.ClockworkKingManager.sweepStrays(server));
         sweep(server, SWEEP_MAGISTER, "magister stray sweep", () -> com.fortuneandfavors.economy.StarboundMagisterManager.sweepStrays(server));
         sweep(server, SWEEP_VOID_SHAPER, "void shaper stray sweep", () -> com.fortuneandfavors.economy.VoidShaperManager.sweepStrays(server));
         sweep(server, SWEEP_SOVEREIGN, "sovereign stray sweep", () -> com.fortuneandfavors.economy.EmeraldSovereignManager.sweepStrays(server));
         sweep(server, SWEEP_DROWNED, "drowned sovereign stray sweep", () -> com.fortuneandfavors.economy.DrownedSovereignManager.sweepStrays(server));
         // And the bodies an older build left behind: a persistent guardian is a guardian-attack
         // sound with no end, and an upgrade cannot un-summon one - this is what can.
         sweep(server, SWEEP_LEGACY_MINIONS, "legacy minion sweep", () -> com.fortuneandfavors.economy.DrownedSovereignManager.sweepLegacyGuardians(server));
         // Perf: flush this tick's per-system timings and sample the server's
         // own MSPT (self-throttled to ~1s inside). Both no-ops when profiling
         // is off; last so every Safe.run above is included in the tick.
         PerfMonitor.endTick();
         PerfMonitor.tickServer(server);
      });
   }

   /** Perf: next wall-clock poll for minute-gated ticks (leaderboard week
    *  rollover). Only meaningful while the server is ticking. */
   private static volatile long nextMinutePoll = 0L;

   /** The cycle the periodic world sweeps are spread across (5 minutes at 20
    *  TPS). Every sweep is given a distinct offset in this cycle - see
    *  {@link #SWEEP_OFFSETS} for why that matters. */
   public static final long SWEEP_CYCLE_TICKS = 6000L;

   // ------------------------------------------------------------------ the item art pack

   /**
    * The mod's own item-art pack, as an identifier: the namespace is the mod and the path names the
    * sub-pack. Fabric resolves a built-in sub-pack at {@code resourcepacks/<path>} inside the mod,
    * which is why the build stages the art at {@link #ART_PACK_ROOT} rather than at the mod root -
    * a sub-pack root of its own is what lets the art be its own entry in the stack.
    */
   public static final Identifier ART_PACK_ID = Identifier.fromNamespaceAndPath(MOD_ID, "art");

   /** The pack root the build stages inside the jar, for the self-test to look for. */
   public static final String ART_PACK_ROOT = "resourcepacks/art";

   /** Whether the art pack was accepted as a built-in, always-on pack at startup. */
   private static volatile boolean artPackRegistered = false;

   /**
    * True once the item art has been registered as a pack of its own that cannot be switched off.
    *
    * <p>What this is for: the art also ships loose in the mod's resources, which means it rides on
    * Fabric's shared "mod resources" entry in the resource-pack screen - an entry a player, a pack
    * shuffle or another launcher can turn off, and a client that has it off shows every custom item
    * as the plain vanilla thing it is built on. That is the report this answers: "the textures
    * unload when there is too much loaded". A pack registered with {@code ALWAYS_ENABLED} goes into
    * the stack unconditionally: nothing to install, nothing to keep in step, no zip that can go
    * stale or arrive disabled.
    */
   public static boolean artPackRegistered() {
      return artPackRegistered;
   }

   /**
    * Registers the item art as an always-on built-in pack, once, at mod init.
    *
    * <p>Never throws: the art still loads from the mod's own resources if this is refused, so a
    * failure here costs the belt and not the braces. It is logged rather than swallowed, because
    * "the art is one disabled pack away from vanishing" is something an operator should be able to
    * find out from the log instead of from a player.
    */
   private static void registerArtPack() {
      try {
         var container = FabricLoader.getInstance().getModContainer(MOD_ID).orElse(null);
         if (container == null) {
            LOGGER.warn("Fortune & Favors: could not find its own mod container, so the item art pack was not registered as a built-in");
            return;
         }

         artPackRegistered = ResourceLoader.registerBuiltinPack(
            ART_PACK_ID, container, net.minecraft.network.chat.Component.literal("Fortune & Favors - item art"), PackActivationType.ALWAYS_ENABLED
         );
         if (artPackRegistered) {
            LOGGER.info("Fortune & Favors: item art registered as an always-on built-in pack ({})", ART_PACK_ID);
         } else {
            LOGGER.warn(
               "Fortune & Favors: the item art pack ({}) was refused by the resource loader - custom items still render, but only while the mod resources pack is enabled in the resource-pack screen",
               ART_PACK_ID
            );
         }
      } catch (Throwable t) {
         LOGGER.warn("Fortune & Favors: could not register the item art pack as a built-in; the art still loads from the mod's own resources", t);
      }
   }
   private static final long SWEEP_MACHINE = 0L;
   private static final long SWEEP_SPAWNER = 750L;
   private static final long SWEEP_DISPLAYS = 1500L;
   private static final long SWEEP_WITHER_DISPLAYS = 2250L;
   private static final long SWEEP_CLOCKWORK = 3000L;
   private static final long SWEEP_MAGISTER = 3750L;
   private static final long SWEEP_VOID_SHAPER = 4500L;
   private static final long SWEEP_SOVEREIGN = 5250L;
   // The Drowned Sovereign's own stray sweep, and the one that clears the guardian minions an
   // older build left standing - both landed in the same file and neither was ever scheduled, so
   // this is the first boot that reaches them. They are slotted between the existing eight rather
   // than after them, because 6000 / 8 is 750 and two more at that spacing would collide.
   private static final long SWEEP_DROWNED = 375L;
   private static final long SWEEP_LEGACY_MINIONS = 1125L;
   /** Every periodic world sweep's slot in the cycle.
    *
    *  <p>These were all zero, spelled {@code tick % 6000 == 0}, which meant one
    *  single tick every five minutes paid for every one of them at once: a hitch on a
    *  fixed schedule, and the worst of them reached outside every loaded chunk
    *  near every player. The offsets are the fix, and the self-test asserts no
    *  two of them collide and that no tick ever owes more than one sweep. */
   public static final long[] SWEEP_OFFSETS = new long[]{
      SWEEP_MACHINE, SWEEP_SPAWNER, SWEEP_DISPLAYS, SWEEP_WITHER_DISPLAYS,
      SWEEP_CLOCKWORK, SWEEP_MAGISTER, SWEEP_VOID_SHAPER, SWEEP_SOVEREIGN,
      SWEEP_DROWNED, SWEEP_LEGACY_MINIONS
   };

   /** How many periodic sweeps fall due on the given tick. The whole point of
    *  the spread is that this is never more than one. */
   public static int sweepsDueAt(long tick) {
      int due = 0;
      for (long offset : SWEEP_OFFSETS) {
         if (Math.floorMod(tick, SWEEP_CYCLE_TICKS) == offset) {
            due++;
         }
      }
      return due;
   }

   /** Runs one periodic maintenance sweep in its own slot of the shared cycle.
    *  Giving every sweep a different offset is what keeps them from stacking
    *  into a single long tick. */
   private static void sweep(MinecraftServer server, long offset, String what, Runnable action) {
      if (Math.floorMod(server.getTickCount(), SWEEP_CYCLE_TICKS) == offset) {
         Safe.run(what, action);
      }
   }

   // -------------------------------------------------------- the once-a-minute cycle

   /**
    * The cycle the once-a-minute periodic work is spread across (a minute at 20 TPS).
    *
    * <p>This is the same bug the sweeps above were fixed for, one period down: three unrelated
    * tasks all spelled their gate {@code tick % 1200 == 0}, so once a minute a single tick paid
    * for all three at once - including the mining zones' containment sweep, which walks every zone
    * the server has and every hostile inside it. They all still run every minute; each one owns
    * its own slot in the cycle now.
    */
   public static final long MINUTE_CYCLE_TICKS = 1200L;
   public static final long MINUTE_ZONE_CONTAINMENT = 100L;
   public static final long MINUTE_EXPEDITION_PRUNE = 500L;
   public static final long MINUTE_MAILBOX = 1100L;

   /**
    * Every offset in the minute cycle, in order.
    *
    * <p>Chosen to clear both neighbours rather than only each other: none of them is a multiple of
    * 200 (where the mining zones' hostile spawn and the mindbinder's expiry prune both sit) and
    * none lands on a five-minute sweep's own slot, so the work falls in quiet ticks whichever
    * cycle it is measured against. The self-test asserts all three properties.
    */
   public static final long[] MINUTE_OFFSETS = new long[]{
      MINUTE_ZONE_CONTAINMENT, MINUTE_EXPEDITION_PRUNE, MINUTE_MAILBOX
   };

   /** True when a periodic task with this offset owns this tick. */
   public static boolean due(long tick, long cycle, long offset) {
      return Math.floorMod(tick - offset, cycle) == 0L;
   }

   /** How many of the given offsets fall due on the given tick. */
   public static int dueCount(long tick, long cycle, long[] offsets) {
      int due = 0;
      for (long offset : offsets) {
         if (due(tick, cycle, offset)) {
            due++;
         }
      }
      return due;
   }

   /** One-shot check at server start: if the world's daylight or weather cycle
    *  rules are off (legacy state from the old arena-clock freeze, or a stray
    *  gamerule), re-enable them once so the SMP keeps a normal day/night cycle.
    *  Runs exactly once per server session and never touches the rules again —
    *  the old per-tick watchdog fought owners/plugins and is what made the
    *  clock stutter or snap back. The mod itself never sets the time of day. */
   private static void checkDayCycle(MinecraftServer server) {
      ServerLevel overworld = server.overworld();
      if (overworld == null) {
         return;
      }
      GameRules rules = overworld.getGameRules();
      if (!(Boolean)rules.get(GameRules.ADVANCE_TIME)) {
         rules.set(GameRules.ADVANCE_TIME, true, server);
         LOGGER.warn("Re-enabled the daylight cycle (advanceTime was off).");
      }
      if (!(Boolean)rules.get(GameRules.ADVANCE_WEATHER)) {
         rules.set(GameRules.ADVANCE_WEATHER, true, server);
         LOGGER.warn("Re-enabled the weather cycle (advanceWeather was off).");
      }
   }

   /** Puts every custom enchantment tome in the Combat creative tab as a real,
    *  grabbable item (NBT-decorated enchanted books, so they render fine even
    *  for vanilla clients). Runs server-side — the tab contents sync to clients.
    *  /ff give keeps working as before. */
   private static void registerCreativeTomes() {
      try {
         CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.COMBAT).register(output -> {
            CCEnchantments.acceptAllTomes(output::accept, output.getContext().holders());
         });
      } catch (Throwable t) {
         LOGGER.error("Failed to register creative tomes", t);
      }
   }

   private static void handleLunge(FfLungePayload payload, Context ctx) {
      ServerPlayer player = ctx.player();
      // Legacy packet path: only fires while the player is actually holding
      // the Lunge Spear, so a stale client can't trigger lunges without it.
      ctx.server().execute(() -> {
         if (ElytraLunge.holdsSpear(player)) {
            ElytraLunge.tryLunge(player);
         }
      });
   }

   /** The client's half of an arena probe: what it actually holds for the layer
    *  the server built. Kept off the tick thread and behind the same null guards
    *  as everything else, so a probe can never take a fight down with it. */
   private static void handleArenaProbe(FfArenaProbePayload payload, Context ctx) {
      ServerPlayer player = ctx.player();
      ctx.server().execute(() -> Safe.run("arena probe reply", () -> DuelManager.handleProbeReply(player, payload)));
   }

   /**
    * Records what a client says it is running.
    *
    * <p>Threading is the only thing this has to get right: the payload arrives on the netty
    * thread, the map it lands in is read by the tick and by commands, so it is handed to the
    * server thread first. Nothing else happens - there is deliberately no reply, no
    * acknowledgement, and no code path from here to a violation.
    */
   private static void handleClientContext(FfClientContextPayload payload, Context ctx) {
      ServerPlayer player = ctx.player();
      ctx.server()
         .execute(
            () -> Safe.run(
               "client context",
               () -> com.fortuneandfavors.anticheat.ClientContext.record(player, payload.protocol(), payload.json())
            )
         );
   }

   private static void handleConfigSync(FfConfigPayload payload, Context ctx) {
      ServerPlayer player = ctx.player();
      ctx.server().execute(() -> {
         if (payload.action() == 0 || payload.action() == 1) {
            boolean applied = payload.action() == 1 && player.permissions().hasPermission(Permissions.COMMANDS_ADMIN);
            if (applied) {
               // Log any server setting this push actually changed. A silent
               // change here is how "the explosion rebuild turned itself off"
               // happened (a rejoin used to replay a client's stale local copy),
               // so the server now says out loud who changed what.
               String before = ModConfig.snapshotJson();
               ModConfig.applyJson(payload.json());
               String after = ModConfig.snapshotJson();
               if (!before.equals(after)) {
                  String diff = changedKeys(before, after);
                  LOGGER.info(
                     "Fortune & Favors: {} pushed config changes from their client - {}",
                     player.getName().getString(),
                     diff
                  );
                  // Say it out loud. A server setting used to be able to flip - for
                  // instance Explosion Rebuild switching itself off on every rejoin -
                  // with nothing written anywhere the players or the owner could see.
                  com.fortuneandfavors.util.Chat.msg(
                     player,
                     "&eYou just changed server settings: &f" + diff
                  );
                  for (ServerPlayer other : ctx.server().getPlayerList().getPlayers()) {
                     if (other != player && other.permissions().hasPermission(Permissions.COMMANDS_ADMIN)) {
                        com.fortuneandfavors.util.Chat.msg(
                           other,
                           "&7" + player.getName().getString() + " &7changed server settings: &f" + diff
                        );
                     }
                  }
               }
               ModConfig.save(ctx.server());
            }

            if (applied) {
               // Broadcast so every player's Cloth Config screen stays in sync.
               // The "__can_edit" flag is per-recipient: only ops may push config
               // changes back, so a regular player's save never touches the server.
               String baseSnapshot = ModConfig.snapshotJson();
               for (ServerPlayer p : ctx.server().getPlayerList().getPlayers()) {
                  boolean pCanEdit = p.permissions().hasPermission(Permissions.COMMANDS_ADMIN);
                  FfNet.send(p, new FfConfigPayload(2, withCanEdit(baseSnapshot, pCanEdit)));
               }
            } else {
               boolean canEdit = player.permissions().hasPermission(Permissions.COMMANDS_ADMIN);
               FfNet.send(player, new FfConfigPayload(2, withCanEdit(ModConfig.snapshotJson(), canEdit)));
            }
         }
      });
   }

   /** The JSON keys whose values differ between a config push's before and
    *  after snapshots, for the change log line above. */
   private static String changedKeys(String before, String after) {
      try {
         JsonObject b = (JsonObject)JsonUtil.gson().fromJson(before, JsonObject.class);
         JsonObject a = (JsonObject)JsonUtil.gson().fromJson(after, JsonObject.class);
         if (b == null || a == null) {
            return "(could not diff)";
         }
         List<String> changed = new java.util.ArrayList<>();
         for (String key : a.keySet()) {
            if (!a.get(key).equals(b.get(key))) {
               changed.add(key + "=" + a.get(key));
            }
         }
         return changed.isEmpty() ? "(no effective change)" : String.join(", ", changed);
      } catch (Throwable t) {
         return "(could not diff)";
      }
   }

   /** Stamps the snapshot with a per-recipient "__can_edit" flag telling the
    *  client whether it may push config changes back (ops only), plus this
    *  server's network protocol, mod version, and block-registry fingerprint.
    *  The flags are stripped before the JSON is fed to
    *  {@link ModConfig#applyJson}, and let the client report a build mismatch
    *  instead of hitting an opaque "Network Protocol Error" - or, in the case of
    *  the registry fingerprint, silently failing to decode chunks. */
   private static String withCanEdit(String snapshotJson, boolean canEdit) {
      JsonObject root = (JsonObject)JsonUtil.gson().fromJson(snapshotJson, JsonObject.class);
      if (root != null) {
         root.addProperty("__can_edit", canEdit);
         root.addProperty(FfNet.KEY_PROTOCOL, FfNet.PROTOCOL);
         root.addProperty(FfNet.KEY_VERSION, modVersion());
         try {
            root.addProperty(FfNet.KEY_BLOCK_STATES, com.fortuneandfavors.util.RegistryFingerprint.blockStates());
            root.addProperty(FfNet.KEY_BLOCK_COUNT, com.fortuneandfavors.util.RegistryFingerprint.blockCount());
            root.addProperty(FfNet.KEY_BLOCK_HASH, com.fortuneandfavors.util.RegistryFingerprint.blockHash());
         } catch (Throwable ignored) {
            // A registry that cannot be walked is not worth failing a join over -
            // the version stamp still covers the build-mismatch case.
         }
         return root.toString();
      }
      return snapshotJson;
   }

   /** The running build's version, reported to clients in the config snapshot. */
   public static String modVersion() {
      return FabricLoader.getInstance().getModContainer(MOD_ID).map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("unknown");
   }

   /** Periodic autosave entry point (also used on disconnect) - persists every
    *  mod data file so a crash only rolls back at most a couple of minutes of
    *  progress instead of an entire session. */
   public static void saveAll(MinecraftServer server) {
      save(server);
   }

   private static void save(MinecraftServer server) {
      if (dataLoaded) {
         Safe.run("economy save", () -> {
            if (EconomyManager.isDirty()) {
               EconomyManager.save(server);
               ChestShopManager.save(server);
            }
         });
         // The shared clock's own save: the reading at this moment becomes the base the next boot
         // resumes from, so a deadline written in a realm is not read as "forty thousand ticks away"
         // after a restart.
         Safe.run("server clock save", () -> com.fortuneandfavors.economy.ServerClock.save(server));
         Safe.run("auction save", () -> AuctionManager.save(server));
         Safe.run("claims save", () -> ClaimManager.save(server));
         Safe.run("machines save", () -> MachineManager.save(server));
         Safe.run("sorter links save", () -> com.fortuneandfavors.economy.SorterLinks.save(server));
         Safe.run("machine tuning save", () -> com.fortuneandfavors.economy.MachineTuning.save(server));
         Safe.run("spawners save", () -> SpawnerManager.save(server));
         Safe.run("config save", () -> ModConfig.save(server));
         Safe.run("anticheat history save", () -> com.fortuneandfavors.anticheat.AntiCheatStore.save(server));
         Safe.run("anticheat policy save", () -> com.fortuneandfavors.anticheat.AntiCheatPolicy.save(server));
         Safe.run("jobs save", () -> JobManager.save(server));
         Safe.run("trade market save", () -> com.fortuneandfavors.economy.TradeManager.save(server));
         Safe.run("bounties save", () -> BountyManager.save(server));
         Safe.run("skills save", () -> {
            if (SkillManager.isDirty()) {
               SkillManager.save(server);
            }
         });
         Safe.run("duel save", () -> DuelManager.save(server));
         Safe.run("nice keep inventory save", () -> NiceKeepInventoryManager.save(server));
         Safe.run("soulbound items save", () -> com.fortuneandfavors.economy.AdvancedEnchantments.save(server));
         Safe.run("guilds save", () -> GuildManager.save(server));
         Safe.run("elder warden save", () -> BossManager.saveWardenState(server));
         Safe.run("titles save", () -> com.fortuneandfavors.economy.TitleManager.save(server));
         Safe.run("tags save", () -> com.fortuneandfavors.economy.TagManager.save(server));
         Safe.run("display prefs save", () -> com.fortuneandfavors.economy.DisplayPrefsManager.save(server));
         Safe.run("streaks save", () -> com.fortuneandfavors.economy.StreakTrackerManager.save(server));
         Safe.run("login streaks save", () -> com.fortuneandfavors.economy.DailyLoginStreakManager.save(server));
         Safe.run("contracts save", () -> com.fortuneandfavors.economy.DynamicContractsManager.save(server));
         Safe.run("disasters save", () -> com.fortuneandfavors.economy.ServerDisasterManager.save(server));
         Safe.run("natural blocks save", () -> com.fortuneandfavors.economy.NaturalBlocks.saveIfDirty(server));
         Safe.run("player raids save", () -> com.fortuneandfavors.economy.PlayerRaidManager.save(server));
         Safe.run("loot box pity save", () -> com.fortuneandfavors.menu.LootBoxMenu.savePity(server));
         Safe.run("prison save", () -> com.fortuneandfavors.economy.PrisonManager.save(server));
         Safe.run("boss codex save", () -> com.fortuneandfavors.economy.BossCodexManager.save(server));
         Safe.run("expedition codex save", () -> com.fortuneandfavors.economy.ExpeditionManager.saveCodex(server));
         Safe.run("expedition progress save", () -> com.fortuneandfavors.economy.ExpeditionProgression.save(server));
         Safe.run("first records save", () -> com.fortuneandfavors.economy.FirstEverRecordManager.save(server));
         Safe.run("end intro save", () -> com.fortuneandfavors.economy.EndIntroMusic.save(server));
         Safe.run("achievements save", () -> com.fortuneandfavors.economy.CooperativeAchievementManager.save(server));
         Safe.run("player feats save", () -> com.fortuneandfavors.economy.PlayerFeatTracker.save(server));
         Safe.run("challenges save", () -> com.fortuneandfavors.economy.DailyWeeklyChallengeManager.save(server));
         Safe.run("lottery save", () -> com.fortuneandfavors.economy.LotteryManager.save(server));
         Safe.run("bank save", () -> com.fortuneandfavors.economy.BankManager.save(server));
         Safe.run("market save", () -> com.fortuneandfavors.economy.MarketManager.save(server));
         Safe.run("newspaper save", () -> com.fortuneandfavors.economy.ServerNewspaperManager.save(server));
         Safe.run("cosmetics save", () -> com.fortuneandfavors.economy.CosmeticManager.save(server));
         Safe.run("leaderboard save", () -> com.fortuneandfavors.economy.LeaderboardManager.save(server));
         Safe.run("scoreboard save", () -> com.fortuneandfavors.economy.ScoreboardManager.save(server));
         Safe.run("last inventory save", () -> com.fortuneandfavors.util.LastInventoryHolder.save(server));
         Safe.run("wormhole waypoints save", () -> WormholeManager.save(server));
      }
   }

   public static Identifier id(String path) {
      return Identifier.fromNamespaceAndPath("fortuneandfavors", path);
   }
}
